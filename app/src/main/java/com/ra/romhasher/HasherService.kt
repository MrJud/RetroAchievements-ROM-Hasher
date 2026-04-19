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
import java.io.File
import java.util.zip.ZipInputStream

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
        private const val IO_COOLDOWN_MS = 1500L          // pause when I/O is degraded
        private const val IO_SEVERE_COOLDOWN_MS = 3000L   // longer pause for severe degradation

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
     * 1. ABSOLUTE: if hash rate < 10 KB/ms (~10 MB/s) for files > 1MB, the SD card is clearly struggling
     * 2. RELATIVE: if rate drops to < 1/5 of the best observed rate, degradation is occurring
     *
     * The best rate self-calibrates upward — when the SD card is fresh it will
     * hash at 400-600 KB/ms, setting a high bar. When it degrades, we detect it.
     */
    private fun ioDelayMs(hashTimeMs: Long, fileKB: Long): Long {
        if (hashTimeMs <= 0 || fileKB < 64) return 0 // skip tiny files
        val rate = fileKB.toDouble() / hashTimeMs     // KB/ms
        hashCount++

        // Self-calibrating: always track the best rate we've seen
        if (rate > bestObservedKBperMs) {
            val old = bestObservedKBperMs
            bestObservedKBperMs = rate
            if (hashCount > 1 && rate > old * 2) {
                Log.i(TAG, "I/O best rate updated: ${"%.1f".format(rate)} KB/ms (was ${"%.1f".format(old)})")
            }
        }

        // Absolute threshold: < 10 KB/ms for files > 1MB is clearly degraded
        // (A healthy SD card does 100-600 KB/ms for ROM-sized files)
        if (fileKB > 1024 && rate < 10.0) {
            return if (rate < 2.0) IO_SEVERE_COOLDOWN_MS else IO_COOLDOWN_MS
        }

        // Relative threshold: if we've seen fast rates and now it's much slower
        if (bestObservedKBperMs > 50.0 && rate < bestObservedKBperMs / 5.0) {
            return if (rate < bestObservedKBperMs / 20.0) IO_SEVERE_COOLDOWN_MS else IO_COOLDOWN_MS
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private suspend fun runScan(config: HasherConfig) {
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
            return
        }

        // 2. Hash + API lookup for each file
        val apiClient = RAApiClient(config.raUser, config.raApiKey)
        var processed = 0
        var newEntries = 0

        // Pre-count cached files so the UI shows only remaining work
        var cachedCount = 0
        for (file in romFiles) {
            val platform = guessPlatformFolder(file)
            val key = CacheManager.makeKey(file.name, platform)
            if (cache.hasEntry(key)) cachedCount++
        }
        val toProcess = total - cachedCount
        Log.i(TAG, "Pre-scan: $total total, $cachedCount cached, $toProcess to process")

        // Progress file for theme monitoring
        val pDir = File(config.cachePath).parentFile ?: File("/sdcard/ReStory")
        pDir.mkdirs()
        progressFile = File(pDir, "hasher_progress.json")
        writeProgress("running", 0, toProcess, "", 0, "", cachedCount, total)

        // Process files sequentially (hashing is CPU-bound / IO-bound for large files)
        for (file in romFiles) {
            if (!scope.isActive) break

            val platform = guessPlatformFolder(file)
            val key = CacheManager.makeKey(file.name, platform)

            // Skip if already cached with valid data
            if (cache.hasEntry(key)) {
                continue
            }

            // Hash the file (handle ZIP extraction)
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
                processed++
                continue
            }
            Log.d(TAG, "Hash OK: ${file.name} → ${hashResult.hash} (${hashTimeMs}ms)")

            // ── Dual adaptive throttle: thermal (SoC) + I/O (SD card) ──
            val thermDelay = thermalDelayMs()
            val ioDelay = ioDelayMs(hashTimeMs, fileKB)
            val effectiveDelay = maxOf(thermDelay, ioDelay)
            if (effectiveDelay > 0) {
                if (effectiveDelay >= 1000L) {
                    val reason = when {
                        ioDelay > thermDelay -> "I/O degraded"
                        thermDelay > ioDelay -> "Thermal ${thermalStatusName()}"
                        else -> "I/O+Thermal"
                    }
                    Log.i(TAG, "$reason — pause ${effectiveDelay}ms at $processed/$total (hash=${hashTimeMs}ms)")
                }
                delay(effectiveDelay)
            }
            // API lookup
            val metadata = try {
                apiClient.lookupHash(hashResult.hash)
            } catch (e: Exception) {
                Log.e(TAG, "API failed: ${file.name}", e)
                null
            }

            // Store result
            if (metadata != null) {
                cache.put(key, hashResult.hash, metadata)
                newEntries++
                Log.i(TAG, "[$processed/$total] FOUND: ${file.name} → gameId=${metadata.gameId}")
            } else {
                // Store as gameId=0 so we don't re-hash next time
                cache.put(key, hashResult.hash, GameMetadata(gameId = 0))
                Log.d(TAG, "[$processed/$total] No match: ${file.name}")
            }
            processed++

            // Update notification + progress file every 3 files
            if (processed % 3 == 0 || processed == toProcess) {
                val pct = if (toProcess > 0) (processed * 100) / toProcess else 100
                updateNotification("[$processed/$toProcess] $pct% \u2014 ${file.name}", processed, toProcess)
                writeProgress("running", processed, toProcess, file.name, newEntries, platform, cachedCount, total)
            }

            // Intermediate save
            if (newEntries > 0 && newEntries % SAVE_INTERVAL == 0) {
                Log.i(TAG, "Intermediate save: $newEntries new entries")
                cache.save()
            }

            // Log thermal + I/O status every 50 files for monitoring
            if (processed % 50 == 0) {
                val rate = if (hashTimeMs > 0) fileKB.toDouble() / hashTimeMs else 0.0
                Log.i(TAG, "Status at $processed/$toProcess: thermal=${thermalStatusName()} " +
                        "bestIO=${"%.0f".format(bestObservedKBperMs)}KB/ms " +
                        "currentIO=${"%.1f".format(rate)}KB/ms " +
                        "lastHash=${hashTimeMs}ms/${fileKB}KB")
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
        return if (ext == "zip") {
            hashZipFile(file)
        } else {
            NativeHasher.hash(file.absolutePath)
        }
    }

    /**
     * For ZIP files: extract the largest entry to a temp file, hash it, then clean up.
     */
    private fun hashZipFile(zipFile: File): HashResult? {
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var largestEntry: Pair<String, Long>? = null
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.size > (largestEntry?.second ?: 0)) {
                        largestEntry = entry.name to entry.size
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                if (largestEntry == null) return null

                // Re-open and extract the largest entry
                ZipInputStream(zipFile.inputStream()).use { zis2 ->
                    var e = zis2.nextEntry
                    while (e != null) {
                        if (e.name == largestEntry.first) {
                            val ext = e.name.substringAfterLast(".", "bin")
                            val tmp = File.createTempFile("rahasher_", ".$ext", cacheDir)
                            try {
                                tmp.outputStream().use { out -> zis2.copyTo(out) }
                                return NativeHasher.hash(tmp.absolutePath)
                            } finally {
                                tmp.delete()
                            }
                        }
                        zis2.closeEntry()
                        e = zis2.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP extraction failed for ${zipFile.name}", e)
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
