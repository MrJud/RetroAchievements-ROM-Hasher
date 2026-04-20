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
         * Same logic as RAService.qml: cleanName.lowercase().replace([^a-z0-9],"") + "|" + normalizePlatform
         */
        fun makeKey(fileName: String, platformFolder: String): String {
            val clean = fileName
                .substringBeforeLast(".")           // remove extension
                .replace(Regex("\\(.*?\\)"), "")    // remove (USA) etc.
                .replace(Regex("\\[.*?]"), "")      // remove [!] etc.
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
            val platform = normalizePlatform(platformFolder)
            return "$clean|$platform"
        }

        /**
         * Normalize a platform folder name to canonical Pegasus shortName.
         * Must stay in sync with RAFuzzyMatch.js normalizePlatform().
         */
        private val platformAliases = mapOf(
            // Nintendo
            "supernintendo" to "snes", "supernes" to "snes", "superfamicom" to "snes",
            "snesfamicom" to "snes", "snessuperfamicom" to "snes",
            "nintendo64" to "n64", "n64" to "n64",
            "nintendods" to "nds", "nds" to "nds", "ds" to "nds",
            "nintendo3ds" to "3ds", "3ds" to "3ds",
            "nintendoentertainmentsystem" to "nes", "famicom" to "nes",
            "gameboyadvance" to "gba", "gba" to "gba",
            "gameboycolor" to "gbc", "gbc" to "gbc",
            "gameboy" to "gb", "gb" to "gb",
            "virtualboy" to "virtualboy",
            "gamecube" to "gc", "gc" to "gc", "ngc" to "gc",
            "wii" to "wii", "wiiu" to "wiiu",
            "switch" to "switch", "nintendoswitch" to "switch",
            // Sega
            "megadrive" to "genesis", "segagenesis" to "genesis", "genesis" to "genesis",
            "segamegadrive" to "genesis", "megadrivegenesis" to "genesis",
            "mastersystem" to "mastersystem", "segamastersystem" to "mastersystem", "sms" to "mastersystem",
            "gamegear" to "gamegear", "gg" to "gamegear", "segagamegear" to "gamegear",
            "segacd" to "segacd", "megacd" to "segacd",
            "sega32x" to "sega32x", "32x" to "sega32x",
            "saturn" to "saturn", "segasaturn" to "saturn",
            "dreamcast" to "dreamcast", "segadreamcast" to "dreamcast", "dc" to "dreamcast",
            "sg1000" to "sg1000",
            // Sony
            "playstation" to "psx", "psx" to "psx", "ps1" to "psx", "psone" to "psx",
            "playstation2" to "ps2", "ps2" to "ps2",
            "playstationportable" to "psp", "psp" to "psp",
            // Atari
            "atari2600" to "atari2600", "atari7800" to "atari7800",
            "atarilynx" to "lynx", "lynx" to "lynx",
            "atarijaguar" to "jaguar", "jaguar" to "jaguar",
            // NEC
            "pcengine" to "pcengine", "turbografx16" to "pcengine", "tg16" to "pcengine",
            "pcenginecd" to "pcenginecd", "turbografxcd" to "pcenginecd",
            "pcfx" to "pcfx",
            // SNK
            "neogeo" to "neogeo", "neogeopocket" to "ngp", "ngp" to "ngp",
            "neogeopocketcolor" to "ngpc", "ngpc" to "ngpc",
            // Other
            "arcade" to "arcade", "mame" to "arcade", "fbneo" to "arcade", "fba" to "arcade",
            "wonderswan" to "wonderswan", "ws" to "wonderswan",
            "wonderswancolor" to "wonderswancolor", "wsc" to "wonderswancolor",
            "colecovision" to "colecovision", "intellivision" to "intellivision",
            "vectrex" to "vectrex", "msx" to "msx", "msx2" to "msx2",
            "amstradcpc" to "amstradcpc", "cpc" to "amstradcpc",
            "zxspectrum" to "zxspectrum", "spectrum" to "zxspectrum",
            "commodore64" to "c64", "c64" to "c64",
            "amiga" to "amiga", "3do" to "3do",
            "pokemini" to "pokemini", "watara" to "supervision"
        )

        fun normalizePlatform(raw: String): String {
            val key = raw.lowercase().replace(Regex("[^a-z0-9]"), "")
            return platformAliases[key] ?: key
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
