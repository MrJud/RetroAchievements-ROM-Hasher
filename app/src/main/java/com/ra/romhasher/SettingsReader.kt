package com.ra.romhasher

import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Reads scan configuration from Intent extras or a fallback config file.
 *
 * Intent extras (from theme):
 *   - "ra_user"    : String — RetroAchievements username
 *   - "ra_api_key" : String — RetroAchievements API key
 *   - "rom_dirs"   : String[] — directories to scan (optional)
 *   - "cache_path" : String — output cache path (optional, default /sdcard/ReStory/ra_hashes_cache.json)
 *
 * Fallback config: /sdcard/ReStory/hasher_config.json
 * {
 *   "ra_user": "...",
 *   "ra_api_key": "...",
 *   "rom_dirs": ["/path/to/roms", ...],
 *   "cache_path": "/sdcard/ReStory/ra_hashes_cache.json"
 * }
 */
data class HasherConfig(
    val raUser: String,
    val raApiKey: String,
    val romDirs: List<String>,
    val cachePath: String
)

object SettingsReader {

    private const val TAG = "SettingsReader"
    private const val FALLBACK_CONFIG = "/sdcard/ReStory/hasher_config.json"
    private const val GAME_DIRS_FILE = "/sdcard/ReStory/game_dirs.txt"

    /**
     * Build config from Intent extras, falling back to config file.
     */
    fun read(intent: Intent?): HasherConfig? {
        // 1. Try Intent extras
        val user = intent?.getStringExtra("ra_user")?.takeIf { it.isNotBlank() }
        val key = intent?.getStringExtra("ra_api_key")?.takeIf { it.isNotBlank() }
        val dirs = intent?.getStringArrayExtra("rom_dirs")?.toList()
        val path = intent?.getStringExtra("cache_path")?.takeIf { it.isNotBlank() }

        // 2. If missing credentials, try config file
        val (fileUser, fileKey, fileDirs, filePath) = readConfigFile()

        val finalUser = user ?: fileUser
        val finalKey = key ?: fileKey

        if (finalUser.isNullOrBlank() || finalKey.isNullOrBlank()) {
            Log.e(TAG, "Missing ra_user or ra_api_key — cannot proceed")
            return null
        }

        // 3. ROM dirs: Intent > config file > game_dirs.txt
        val finalDirs = when {
            !dirs.isNullOrEmpty() -> dirs
            !fileDirs.isNullOrEmpty() -> fileDirs
            else -> readGameDirsFile()
        }

        if (finalDirs.isEmpty()) {
            Log.e(TAG, "No ROM directories configured")
            return null
        }

        val finalPath = path ?: filePath ?: CacheManager.DEFAULT_PATH

        return HasherConfig(
            raUser = finalUser,
            raApiKey = finalKey,
            romDirs = finalDirs,
            cachePath = finalPath
        )
    }

    private data class FileConfig(
        val user: String?,
        val key: String?,
        val dirs: List<String>?,
        val path: String?
    )

    private fun readConfigFile(): FileConfig {
        val file = File(FALLBACK_CONFIG)
        if (!file.exists()) return FileConfig(null, null, null, null)
        return try {
            val json = JSONObject(file.readText())
            val dirs = json.optJSONArray("rom_dirs")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            FileConfig(
                user = json.optString("ra_user", "").takeIf { it.isNotBlank() },
                key = json.optString("ra_api_key", "").takeIf { it.isNotBlank() },
                dirs = dirs,
                path = json.optString("cache_path", "").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $FALLBACK_CONFIG", e)
            FileConfig(null, null, null, null)
        }
    }

    /** Read game_dirs.txt (one directory per line, same as bash script) */
    private fun readGameDirsFile(): List<String> {
        val file = File(GAME_DIRS_FILE)
        if (!file.exists()) {
            // Also try theme_settings path
            val themeSettings = File("/storage/emulated/0/Android/data/org.pegasus_frontend.android/files/pegasus-frontend/themes/ReStory/game_dirs.txt")
            if (themeSettings.exists()) {
                return themeSettings.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
            return emptyList()
        }
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
