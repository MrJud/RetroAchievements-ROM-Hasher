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
        private const val SAVE_INTERVAL = 50
        const val EXTRA_CONFIG = "config_json"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

        // Process in parallel batches via coroutines (semaphore in RAApiClient limits to 8)
        coroutineScope {
            romFiles.map { file ->
                async {
                    if (!isActive) return@async

                    val platform = guessPlatformFolder(file)
                    val key = CacheManager.makeKey(file.name, platform)

                    // Skip if already cached with valid data
                    if (cache.hasEntry(key)) {
                        synchronized(this@HasherService) {
                            processed++
                            if (processed % 20 == 0) {
                                updateNotification(
                                    "[$processed/$total] ${file.name}",
                                    processed, total
                                )
                            }
                        }
                        return@async
                    }

                    // Hash the file (handle ZIP extraction)
                    val hashResult = hashFile(file)
                    if (hashResult == null) {
                        synchronized(this@HasherService) { processed++ }
                        return@async
                    }

                    // API lookup
                    val metadata = apiClient.lookupHash(hashResult.hash)

                    // Store result
                    synchronized(this@HasherService) {
                        if (metadata != null) {
                            cache.put(key, hashResult.hash, metadata)
                            newEntries++
                        } else {
                            // Store as gameId=0 so we don't re-hash next time
                            cache.put(key, hashResult.hash, GameMetadata(gameId = 0))
                        }
                        processed++

                        // Update notification
                        if (processed % 5 == 0 || processed == total) {
                            val pct = (processed * 100) / total
                            updateNotification(
                                "[$processed/$total] $pct% — ${file.name}",
                                processed, total
                            )
                        }

                        // Intermediate save
                        if (newEntries > 0 && newEntries % SAVE_INTERVAL == 0) {
                            cache.save()
                        }
                    }
                }
            }.awaitAll()
        }

        // 3. Final save
        cache.save()
        Log.i(TAG, "Scan complete: $processed processed, $newEntries new entries, ${cache.size()} total")
        updateNotification("Done — $newEntries new games found (${cache.size()} total)", total, total)
        delay(3000) // Keep notification visible briefly
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
