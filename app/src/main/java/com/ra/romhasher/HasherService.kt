package com.ra.romhasher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * Foreground service that performs the full ROM scan → hash → API lookup → cache write pipeline.
 *
 * Shows a persistent notification with progress and a Cancel button.
 * Saves cache incrementally every 50 games.
 */
class HasherService : Service() {

    companion object {
        private const val TAG = "HasherService"
        private const val CHANNEL_ID = "hasher_progress"
        private const val NOTIFICATION_ID = 1
        private const val SAVE_INTERVAL = 30
        const val EXTRA_CONFIG = "config_json"

        // I/O degradation thresholds
        private const val IO_COOLDOWN_MS = 500L            // pause when I/O is degraded
        private const val IO_SEVERE_COOLDOWN_MS = 1500L    // longer pause for severe degradation

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var progressFile: File? = null
    private lateinit var powerManager: PowerManager

    // ── I/O degradation tracking ────────────────────────────────────────
    // Track the best observed hash rate to detect SD card I/O degradation.
    // Uses a dual approach: absolute threshold + relative degradation.
    private var bestObservedKBperMs = 0.0   // best rate seen so far (self-calibrating)
    private var hashCount = 0               // number of hashes measured

    /**
     * Compute I/O cooldown based on measured hash throughput.
     *
     * Two-tier detection:
     * Compute I/O cooldown based on RELATIVE degradation vs best observed rate.
     *
     * The previous absolute threshold (rate < 5 KB/ms) was too aggressive: 3-5 KB/ms is the
     * nominal speed of a budget SD card on non-cached ROMs, NOT degradation.
     *
     * Strategy: self-calibration of best rate after warmup (20 hashes). If rate drops
     * below 1/10 of best observed, it's real degradation (thermal or SD card wear).
     */
    private fun ioDelayMs(hashTimeMs: Long, fileKB: Long): Long {
        if (hashTimeMs <= 0 || fileKB < 256) return 0
        val rate = fileKB.toDouble() / hashTimeMs
        hashCount++

        // Exclude OS page-cache hits (< 200ms) and cap at 100 KB/ms (realistic SD max)
        if (hashCount > 10 && hashTimeMs > 200 && rate > bestObservedKBperMs && rate < 100.0) {
            bestObservedKBperMs = rate
        }

        // Only relative threshold: degradation = collapse vs best observed rate
        if (hashCount > 20 && bestObservedKBperMs > 50.0 && rate < bestObservedKBperMs / 10.0) {
            return if (rate < bestObservedKBperMs / 30.0) IO_SEVERE_COOLDOWN_MS else IO_COOLDOWN_MS
        }

        return 0L
    }

    /**
     * Adaptive delay based on real-time thermal status from PowerManager.
     * Returns the delay in ms to wait after each hash operation.
     */
    private fun thermalDelayMs(): Long {
        val status = try {
            powerManager.currentThermalStatus
        } catch (_: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE     -> 0L      // Cool — full speed
            PowerManager.THERMAL_STATUS_LIGHT    -> 150L    // Warm — light brake
            PowerManager.THERMAL_STATUS_MODERATE -> 600L    // Hot  — steady pace
            PowerManager.THERMAL_STATUS_SEVERE   -> 2000L   // Very hot — heavy brake
            PowerManager.THERMAL_STATUS_CRITICAL -> 5000L   // Critical — long pause
            else                                  -> 10000L  // Emergency/shutdown — cool down hard
        }
    }

    /**
     * Log thermal status periodically so we can see transitions in logcat.
     */
    private fun thermalStatusName(): String {
        val status = try { powerManager.currentThermalStatus } catch (_: Exception) { -1 }
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE     -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT    -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE   -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {        // Handle cancel action from notification
        if (intent?.action == "CANCEL") {
            Log.i(TAG, "Cancel requested from notification")
            job?.cancel()
            return START_NOT_STICKY
        }
        if (isRunning) {
            Log.w(TAG, "Scan already in progress — ignoring")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent?.let { SettingsReader.read(it) }
        if (config == null) {
            Log.e(TAG, "No valid config — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Scanning ROM folders…", 0, 0))
        acquireWakeLock()

        job = scope.launch {
            try {
                runScan(config)
            } catch (e: CancellationException) {
                Log.i(TAG, "Scan cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
            } finally {
                isRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Main scan pipeline ──────────────────────────────────────────────

    private data class HashJob(val file: File, val key: String, val hash: HashResult, val platform: String, val fileKB: Long)
    private data class ResultJob(val job: HashJob, val metadata: GameMetadata?)

    private suspend fun runScan(config: HasherConfig) = coroutineScope {
        val cache = CacheManager(config.cachePath)
        val existingCount = cache.load()
        Log.i(TAG, "Loaded cache: $existingCount existing entries")

        // 1. Scan for ROM files
        updateNotification("Scanning ROM folders…", 0, 0)
        val romFiles = RomScanner.scan(config.romDirs)
        val total = romFiles.size
        Log.i(TAG, "Found $total ROM files")

        if (total == 0) {
            updateNotification("No ROMs found", 0, 0)
            delay(2000)
            return@coroutineScope
        }

        val apiClient = RAApiClient(config.raUser, config.raApiKey)
        var cachedCount = 0
        var toProcess = total

        // Progress file for theme monitoring
        val pDir = File(config.cachePath).parentFile ?: File("/sdcard/ReStory")
        pDir.mkdirs()
        progressFile = File(pDir, "hasher_progress.json")
        writeProgress("running", 0, toProcess, "", 0, "", cachedCount, total)

        // ── Pipeline: producer (hash) → channel → worker pool (API) → channel → collector (cache) ──

        val hashChannel = Channel<HashJob>(capacity = 16)
        val resultChannel = Channel<ResultJob>(capacity = 64)

        // In-memory hash dedup: avoids duplicate API calls for ROMs with identical hashes
        val hashLookupCache = mutableMapOf<String, GameMetadata?>()

        // Producer: sequential hashing with thermal/IO throttle
        val producer = launch(Dispatchers.Default) {
            for (file in romFiles) {
                if (!isActive) break

                val platform = guessPlatformFolder(file)
                val key = CacheManager.makeKey(file.name, platform)

                // Skip if already cached with valid data
                if (cache.hasEntry(key)) {
                    cachedCount++
                    toProcess = total - cachedCount
                    continue
                }

                // Hash the file
                val fileKB = file.length() / 1024
                Log.d(TAG, "Hashing: ${file.name} (${fileKB}KB)")
                val hashStart = System.nanoTime()
                val hashResult = try {
                    withContext(Dispatchers.IO) { hashFile(file) }
                } catch (e: Exception) {
                    Log.e(TAG, "Hash failed: ${file.name}", e)
                    null
                }
                val hashTimeMs = (System.nanoTime() - hashStart) / 1_000_000
                if (hashResult == null) {
                    Log.w(TAG, "No hash for: ${file.name}")
                    resultChannel.send(ResultJob(HashJob(file, key, HashResult("", 0), platform, fileKB), null))
                    continue
                }
                Log.d(TAG, "Hash OK: ${file.name} → ${hashResult.hash} (${hashTimeMs}ms)")

                // ── Dual adaptive throttle: thermal (SoC) + I/O (SD card) ──
                val thermDelay = thermalDelayMs()
                val ioDelay = ioDelayMs(hashTimeMs, fileKB)
                val effectiveDelay = maxOf(thermDelay, ioDelay)
                if (effectiveDelay > 0) {
                    if (effectiveDelay >= 400L) {
                        val reason = when {
                            ioDelay > thermDelay -> "I/O degraded"
                            thermDelay > ioDelay -> "Thermal ${thermalStatusName()}"
                            else -> "I/O+Thermal"
                        }
                        Log.i(TAG, "$reason — pause ${effectiveDelay}ms (hash=${hashTimeMs}ms)")
                    }
                    delay(effectiveDelay)
                }

                hashChannel.send(HashJob(file, key, hashResult, platform, fileKB))
            }
            hashChannel.close()
        }

        // Worker pool: parallel API lookups (8 workers, matching RAApiClient semaphore)
        val workers = List(8) {
            launch(Dispatchers.IO) {
                for (job in hashChannel) {
                    // Dedup: skip API call if identical hash already looked up
                    val metadata = synchronized(hashLookupCache) {
                        if (job.hash.hash in hashLookupCache) hashLookupCache[job.hash.hash]
                        else null // sentinel: need to look up
                    }
                    val finalMeta = if (metadata != null || synchronized(hashLookupCache) { job.hash.hash in hashLookupCache }) {
                        if (metadata != null) Log.d(TAG, "Hash dedup hit: ${job.file.name} → ${job.hash.hash}")
                        metadata
                    } else {
                        val result = try {
                            apiClient.lookupHash(job.hash.hash)
                        } catch (e: Exception) {
                            Log.e(TAG, "API failed: ${job.file.name}", e)
                            null
                        }
                        synchronized(hashLookupCache) { hashLookupCache[job.hash.hash] = result }
                        result
                    }
                    resultChannel.send(ResultJob(job, finalMeta))
                }
            }
        }

        // Close resultChannel when all workers are done
        launch {
            producer.join()
            workers.forEach { it.join() }
            resultChannel.close()
        }

        // Collector: serial cache writes + progress updates (CacheManager is not thread-safe)
        var processed = 0
        var newEntries = 0
        for (r in resultChannel) {
            if (r.job.hash.hash.isNotEmpty()) {
                if (r.metadata != null) {
                    cache.put(r.job.key, r.job.hash.hash, r.metadata)
                    newEntries++
                    Log.i(TAG, "[$processed/$toProcess] FOUND: ${r.job.file.name} → gameId=${r.metadata.gameId}")
                } else {
                    cache.put(r.job.key, r.job.hash.hash, GameMetadata(gameId = 0))
                    Log.d(TAG, "[$processed/$toProcess] No match: ${r.job.file.name}")
                }
            }
            processed++

            // Update notification + progress file every 3 files
            if (processed % 3 == 0 || processed == toProcess) {
                val pct = if (toProcess > 0) (processed * 100) / toProcess else 100
                updateNotification("[$processed/$toProcess] $pct% — ${r.job.file.name}", processed, toProcess)
                writeProgress("running", processed, toProcess, r.job.file.name, newEntries, r.job.platform, cachedCount, total)
            }

            // Intermediate save
            if (newEntries > 0 && newEntries % SAVE_INTERVAL == 0) {
                Log.i(TAG, "Intermediate save: $newEntries new entries")
                cache.save()
            }

            // Log thermal + I/O status every 50 files for monitoring
            if (processed % 50 == 0) {
                Log.i(TAG, "Status at $processed/$toProcess: thermal=${thermalStatusName()} " +
                        "bestIO=${"%.0f".format(bestObservedKBperMs)}KB/ms " +
                        "newEntries=$newEntries cached=$cachedCount")
            }
        }

        // 3. Final save
        cache.save()
        Log.i(TAG, "Scan complete: $processed processed, $newEntries new entries, $cachedCount cached, ${cache.size()} total")
        updateNotification("Done — $newEntries new games found (${cache.size()} total)", toProcess, toProcess)
        writeProgress("done", processed, toProcess, "", newEntries, "", cachedCount, total)
        delay(3000) // Keep notification visible briefly
    }

    private fun writeProgress(status: String, processed: Int, total: Int, current: String, newEntries: Int, platform: String, cached: Int, totalFiles: Int) {
        try {
            val pct = if (total > 0) (processed * 100) / total else 100
            val json = org.json.JSONObject().apply {
                put("status", status)
                put("processed", processed)
                put("total", total)
                put("percent", pct)
                put("current", current)
                put("newEntries", newEntries)
                put("platform", platform)
                put("cached", cached)
                put("totalFiles", totalFiles)
                put("timestamp", System.currentTimeMillis())
            }
            progressFile?.let { pf ->
                val tmp = File(pf.absolutePath + ".tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(pf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write progress file", e)
        }
    }

    // ── File hashing (with ZIP support) ─────────────────────────────────

    private fun hashFile(file: File): HashResult? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "zip" -> hashZipFile(file)
            "7z"  -> hash7zFile(file)
            else  -> NativeHasher.hash(file.absolutePath)
        }
    }

    /**
     * For ZIP files: use ZipFile (random access) for single-pass extraction of the largest entry.
     */
    private fun hashZipFile(zipFile: File): HashResult? {
        return try {
            ZipFile(zipFile).use { zf ->
                val largest = zf.entries().asSequence()
                    .filter { !it.isDirectory }
                    .maxByOrNull { it.size } ?: return null

                val ext = largest.name.substringAfterLast(".", "bin")
                val tmp = File.createTempFile("rahasher_", ".$ext", cacheDir)
                try {
                    zf.getInputStream(largest).use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    NativeHasher.hash(tmp.absolutePath)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP extraction failed for ${zipFile.name}", e)
            null
        }
    }

    /**
     * For 7z files: extract the largest entry to a temp file, hash it, then clean up.
     */
    private fun hash7zFile(sevenZFile: File): HashResult? {
        try {
            SevenZFile(sevenZFile).use { archive ->
                // Find the largest entry
                val largest = archive.entries
                    .filter { !it.isDirectory && it.size > 0 }
                    .maxByOrNull { it.size }
                    ?: return null

                val ext = largest.name.substringAfterLast(".", "bin")
                val tmp = File.createTempFile("rahasher_", ".$ext", cacheDir)
                try {
                    archive.getInputStream(largest).use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    return NativeHasher.hash(tmp.absolutePath)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "7z extraction failed for ${sevenZFile.name}", e)
        }
        return null
    }

    /**
     * Guess the platform from the parent folder name.
     * e.g. /storage/.../Roms/GBA/game.gba → "GBA"
     */
    private fun guessPlatformFolder(file: File): String {
        return file.parentFile?.name ?: "unknown"
    }

    // ── Notification management ─────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ROM hashing progress"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelIntent = Intent(this, HasherService::class.java).apply {
            action = "CANCEL"
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_cancel), cancelPending)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RAHasher::ScanWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // max 1 hour
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
