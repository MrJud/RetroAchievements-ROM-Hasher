package com.ra.romhasher

import android.util.Log

/**
 * Kotlin wrapper for the native rcheevos JNI hashing library.
 *
 * Call [hash] with a file path to get "HASH|CONSOLE_ID" or null if unhashable.
 */
object NativeHasher {

    private const val TAG = "NativeHasher"

    init {
        System.loadLibrary("rahasher")
    }

    /**
     * Hash a ROM file using rcheevos.
     * @param path absolute path to the ROM file
     * @return "HASH|CONSOLE_ID" string, or null if the file could not be hashed
     */
    fun hash(path: String): HashResult? {
        return try {
            val raw = hashFile(path) ?: return null
            val parts = raw.split("|", limit = 2)
            if (parts.size == 2) {
                HashResult(hash = parts[0], consoleId = parts[1].toIntOrNull() ?: 0)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Hash failed for $path", e)
            null
        }
    }

    /** JNI native method — implemented in ra_hasher_jni.c */
    private external fun hashFile(path: String): String?
}

data class HashResult(val hash: String, val consoleId: Int)
