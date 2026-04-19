package com.ra.romhasher

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Manages the ra_hashes_cache.json file.
 *
 * JSON format (identical to bash ra-hasher.sh output):
 * {
 *   "external_hashes": {
 *     "cleankey|platform": { "gameId": 123, "title": "...", "consoleName": "...", "imageIcon": "...", "numAchievements": 42 }
 *   },
 *   "verify_map": {
 *     "cleankey|platform": "HASH"
 *   }
 * }
 *
 * Supports incremental merge: load existing → skip already-hashed → merge new → atomic write.
 */
class CacheManager(private val cachePath: String) {

    companion object {
        private const val TAG = "CacheManager"
        const val DEFAULT_PATH = "/sdcard/ReStory/ra_hashes_cache.json"

        /**
         * Normalize a game name + platform into the cache key format.
         * Same logic as RAService.qml: cleanName.lowercase().replace([^a-z0-9],"") + "|" + platform
         */
        fun makeKey(fileName: String, platformFolder: String): String {
            val clean = fileName
                .substringBeforeLast(".")           // remove extension
                .replace(Regex("\\(.*?\\)"), "")    // remove (USA) etc.
                .replace(Regex("\\[.*?]"), "")      // remove [!] etc.
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
            val platform = platformFolder.lowercase().replace(Regex("[^a-z0-9]"), "")
            return "$clean|$platform"
        }
    }

    private var externalHashes = JSONObject()
    private var verifyMap = JSONObject()

    /** Load existing cache from disk. Returns number of existing entries. */
    fun load(): Int {
        val file = File(cachePath)
        if (!file.exists()) return 0
        return try {
            val json = JSONObject(file.readText())
            externalHashes = json.optJSONObject("external_hashes") ?: JSONObject()
            verifyMap = json.optJSONObject("verify_map") ?: JSONObject()
            externalHashes.length()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache from $cachePath", e)
            0
        }
    }

    /** Check if a key already has a valid entry (gameId > 0 with a title). */
    fun hasEntry(key: String): Boolean {
        val entry = externalHashes.optJSONObject(key) ?: return false
        return entry.optInt("gameId", 0) > 0 && entry.optString("title", "").isNotEmpty()
    }

    /** Add or update an entry in the cache. */
    fun put(key: String, hash: String, metadata: GameMetadata) {
        val entry = JSONObject().apply {
            put("gameId", metadata.gameId)
            put("title", metadata.title)
            put("consoleName", metadata.consoleName)
            put("imageIcon", metadata.imageIcon)
            put("numAchievements", metadata.numAchievements)
        }
        externalHashes.put(key, entry)
        if (hash.isNotEmpty() && metadata.gameId > 0) {
            verifyMap.put(key, hash)
        }
    }

    /** Write cache to disk atomically (.tmp → rename). */
    fun save() {
        try {
            val file = File(cachePath)
            file.parentFile?.mkdirs()

            val json = JSONObject().apply {
                put("external_hashes", externalHashes)
                put("verify_map", verifyMap)
            }

            val tmp = File("${cachePath}.tmp")
            tmp.writeText(json.toString(2))
            tmp.renameTo(file)

            Log.i(TAG, "Cache saved: ${externalHashes.length()} entries → $cachePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache to $cachePath", e)
        }
    }

    /** Total entries currently in cache. */
    fun size(): Int = externalHashes.length()


}
