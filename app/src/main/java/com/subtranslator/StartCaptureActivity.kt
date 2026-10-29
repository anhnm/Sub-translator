package com.subtranslator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Invisible activity that walks through permissions and the screen-capture consent dialog,
 * then starts [SubtitleService]. Used by both the main screen and the Quick Settings tile.
 */
class StartCaptureActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                requestProjection()
            } else {
                Toast.makeText(this, R.string.need_audio_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, SubtitleService::class.java)
                    .setAction(SubtitleService.ACTION_START)
                    .putExtra(SubtitleService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(SubtitleService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // a launcher result is on its way

        if (SubtitleService.isRunning) {
            finish()
            return
        }

        val prefs = Prefs(this)
        val source = Languages.source(prefs.sourceLang)
        if (!ModelManager(this).isVoskModelReady(source)) {
            Toast.makeText(this, R.string.need_model, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionHelp()
            return
        }

        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Sideloaded apps on Android 13+ must first enable "Allow restricted settings" in App info,
     * otherwise the overlay toggle is greyed out. Explain both steps.
     */
    private fun showOverlayPermissionHelp() {
        val packageUri = Uri.parse("package:$packageName")
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_help_title)
            .setMessage(R.string.overlay_help_message)
            .setPositiveButton(R.string.open_overlay_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            }
            .setNeutralButton(R.string.open_app_info) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        fun intent(context: Context) = Intent(context, StartCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
