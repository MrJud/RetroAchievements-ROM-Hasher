package com.ra.romhasher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the RetroAchievements web API.
 *
 * Mirrors the bash ra-hasher.sh logic:
 *  1. dorequest.php?r=gameid&m=HASH → GameID
 *  2. API_GetGame.php?z=USER&y=KEY&i=GAMEID → metadata
 *
 * Handles the known API quirk where API_GetGame returns [1, {...}] instead of {...}.
 */
class RAApiClient(
    private val raUser: String,
    private val raApiKey: String
) {

    companion object {
        private const val TAG = "RAApiClient"
        private const val BASE = "https://retroachievements.org"
        private const val USER_AGENT = "RAHasher/1.0"
        private const val MAX_PARALLEL = 8
        private const val MAX_RETRIES = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_PARALLEL)

    /**
     * Look up a hash and return full game metadata if found.
     * Runs within the shared semaphore (max 8 parallel).
     */
    suspend fun lookupHash(hash: String): GameMetadata? = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            try {
                val gameId = fetchGameId(hash) ?: return@withContext null
                if (gameId == 0) return@withContext GameMetadata(gameId = 0)
                fetchMetadata(gameId)
            } catch (e: Exception) {
                Log.e(TAG, "lookupHash failed for $hash", e)
                null
            }
        }
    }

    /**
     * Step 1: hash → GameID
     */
    private suspend fun fetchGameId(hash: String): Int? {
        val url = "$BASE/dorequest.php?r=gameid&m=$hash"
        val body = httpGetWithRetry(url) ?: return null
        return try {
            val obj = JSONObject(body)
            val success = obj.optBoolean("Success", false)
            if (success) obj.optInt("GameID", 0) else 0
        } catch (e: Exception) {
            Log.e(TAG, "GameID parse error for hash=$hash", e)
            0
        }
    }

    /**
     * Step 2: GameID → metadata (title, console, icon, achievement count)
     */
    private suspend fun fetchMetadata(gameId: Int): GameMetadata? {
        val url = "$BASE/API/API_GetGame.php?z=$raUser&y=$raApiKey&i=$gameId"
        val body = httpGetWithRetry(url) ?: return null
        return try {
            // API sometimes returns [1, {...}] instead of {...}
            val obj = parseResponseObject(body)
                ?: return GameMetadata(gameId = gameId)
            GameMetadata(
                gameId = obj.optInt("ID", gameId),
                title = obj.optString("Title", ""),
                consoleName = obj.optString("ConsoleName", ""),
                imageIcon = obj.optString("ImageIcon", ""),
                numAchievements = obj.optInt("NumAchievements", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata parse error for gameId=$gameId", e)
            GameMetadata(gameId = gameId)
        }
    }

    /**
     * Handle both JSON object {...} and array [1, {...}] responses.
     */
    private fun parseResponseObject(body: String): JSONObject? {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                // Find the first object in the array
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) return item
                }
                null
            }
            else -> null
        }
    }

    /**
     * HTTP GET with retry on 429/5xx, exponential backoff.
     */
    private suspend fun httpGetWithRetry(url: String): String? {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return response.body?.string()
                        response.code == 429 || response.code >= 500 -> {
                            val backoffMs = (1000L shl attempt) // 1s, 2s, 4s
                            Log.w(TAG, "HTTP ${response.code} for $url — retry in ${backoffMs}ms")
                            delay(backoffMs)
                        }
                        else -> {
                            Log.w(TAG, "HTTP ${response.code} for $url — not retrying")
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
                val backoffMs = (1000L shl attempt)
                Log.w(TAG, "Request error for $url — retry in ${backoffMs}ms", e)
                delay(backoffMs)
            }
        }
        Log.e(TAG, "All retries exhausted for $url", lastException)
        return null
    }
}

data class GameMetadata(
    val gameId: Int = 0,
    val title: String = "",
    val consoleName: String = "",
    val imageIcon: String = "",
    val numAchievements: Int = 0
)
