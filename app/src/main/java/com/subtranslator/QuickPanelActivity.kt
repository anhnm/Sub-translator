package com.subtranslator

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.subtranslator.databinding.ActivityQuickPanelBinding
import kotlinx.coroutines.launch

/**
 * What the launcher icon opens: a small bottom panel to start/stop subtitles quickly.
 * The full settings screen ([MainActivity]) is one tap away.
 */
class QuickPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickPanelBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // First run: nothing can work until a speech model is downloaded.
        if (!ModelManager(this).isVoskModelReady(Languages.source(prefs.sourceLang))) {
            openSettings()
            return
        }

        binding = ActivityQuickPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scrim.setOnClickListener { finish() }
        binding.closeButton.setOnClickListener { finish() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.languageTile.setOnClickListener { openSettings() }
        binding.toggleTile.setOnClickListener {
            if (SubtitleService.isRunning) {
                SubtitleService.stop(this)
            } else {
                startActivity(StartCaptureActivity.intent(this))
            }
            finish()
        }

        if (savedInstanceState == null) checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.toggleLabel.setText(
            if (SubtitleService.isRunning) R.string.stop_subtitles else R.string.start_subtitles
        )
        binding.languageLabel.text =
            "${prefs.sourceLang.uppercase()} → ${prefs.targetLang.uppercase()}"
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = runCatching { Updater(this@QuickPanelActivity).check() }.getOrNull()
                ?: return@launch
            binding.updateBanner.text = getString(R.string.update_banner, info.versionName)
            binding.updateBanner.visibility = View.VISIBLE
            binding.updateBanner.setOnClickListener {
                startActivity(
                    Intent(this@QuickPanelActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
                )
                finish()
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
