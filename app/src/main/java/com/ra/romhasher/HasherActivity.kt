package com.ra.romhasher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast

/**
 * Transparent activity that receives the scan Intent, validates permissions,
 * starts the HasherService, and immediately finishes.
 *
 * No visible UI — uses Theme.Translucent.NoTitleBar.
 */
class HasherActivity : Activity() {

    companion object {
        private const val REQUEST_MANAGE_STORAGE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If launched via custom URI, extract query params into extras
        intent?.data?.let { uri ->
            if (uri.scheme == "rahasher") {
                uri.getQueryParameter("ra_user")?.let { intent.putExtra("ra_user", it) }
                uri.getQueryParameter("ra_api_key")?.let { intent.putExtra("ra_api_key", it) }
                uri.getQueryParameter("rom_dirs")?.let { intent.putExtra("rom_dirs", it) }
                uri.getQueryParameter("cache_path")?.let { intent.putExtra("cache_path", it) }
            }
        }

        // Handle cancel action forwarded to service
        if (intent?.action == "CANCEL") {
            stopService(Intent(this, HasherService::class.java))
            finish()
            return
        }

        // Check MANAGE_EXTERNAL_STORAGE permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            Toast.makeText(this, "Storage permission required — please grant access", Toast.LENGTH_LONG).show()
            val permIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(permIntent, REQUEST_MANAGE_STORAGE)
            return
        }

        startScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                startScan()
            } else {
                Toast.makeText(this, "Permission denied — cannot scan ROMs", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startScan() {
        if (HasherService.isRunning) {
            Toast.makeText(this, "Scan already in progress", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val config = SettingsReader.read(intent)
        if (config == null) {
            Toast.makeText(this,
                "Missing RA credentials — configure ra_user and ra_api_key",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Forward to the foreground service
        val serviceIntent = Intent(this, HasherService::class.java).apply {
            putExtra("ra_user", config.raUser)
            putExtra("ra_api_key", config.raApiKey)
            putExtra("rom_dirs", config.romDirs.toTypedArray())
            putExtra("cache_path", config.cachePath)
        }
        startForegroundService(serviceIntent)

        Toast.makeText(this, "ROM scan started", Toast.LENGTH_SHORT).show()
        finish()
    }
}
