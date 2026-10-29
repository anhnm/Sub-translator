package com.subtranslator

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.subtranslator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var models: ModelManager
    private var finishJob: Job? = null
    private var translateJob: Job? = null
    private lateinit var updater: Updater
    private var updateJob: Job? = null
    /** Downloaded update waiting for the "install unknown apps" permission. */
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        models = ModelManager(this)
        updater = Updater(this)

        val engines = listOf(getString(R.string.engine_whisper), getString(R.string.engine_vosk))
        setupSpinner(binding.engineSpinner, engines, prefs.engine.ordinal) {
            prefs.engine = Engine.entries[it]
            refresh()
        }
        val whisperModels = listOf(getString(R.string.whisper_base), getString(R.string.whisper_small))
        setupSpinner(binding.whisperModelSpinner, whisperModels, prefs.whisperModel.ordinal) {
            prefs.whisperModel = WhisperModel.entries[it]
            refresh()
        }
        setupSpinner(binding.sourceSpinner, Languages.all, Languages.all.indexOf(prefs.language)) {
            prefs.sourceLang = Languages.all[it].code
            refresh()
        }
        setupSpinner(binding.targetSpinner, Languages.all,
            Languages.all.indexOf(Languages.get(prefs.targetLang))) {
            prefs.targetLang = Languages.all[it].code
            refresh()
        }

        binding.modelButton.setOnClickListener { onModelButton() }
        binding.translateButton.setOnClickListener { downloadTranslation() }

        binding.showOriginal.isChecked = prefs.showOriginal
        binding.showOriginal.setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }

        binding.textSize.progress = prefs.textSize - MIN_TEXT_SIZE
        binding.textSizeLabel.text = getString(R.string.text_size, prefs.textSize)
        binding.textSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress + MIN_TEXT_SIZE
                prefs.textSize = size
                binding.textSizeLabel.text = getString(R.string.text_size, size)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.visibleLines.progress = prefs.visibleLines - MIN_VISIBLE_LINES
        binding.visibleLinesLabel.text = getString(R.string.visible_lines, prefs.visibleLines)
        binding.visibleLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val lines = progress + MIN_VISIBLE_LINES
                prefs.visibleLines = lines
                binding.visibleLinesLabel.text = getString(R.string.visible_lines, lines)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.toggleButton.setOnClickListener {
            if (SubtitleService.isRunning) {
                SubtitleService.stop(this)
                binding.root.postDelayed({ refresh() }, 300)
            } else {
                startActivity(StartCaptureActivity.intent(this))
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            binding.addTileButton.visibility = View.VISIBLE
            binding.addTileButton.setOnClickListener { requestAddTile() }
        }

        // Background downloads keep running while the app is closed; poll their progress.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refreshModel()
                    delay(700)
                }
            }
        }

        binding.versionLabel.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        binding.updateButton.setOnClickListener { checkForUpdate(manual = true) }
        if (savedInstanceState == null) {
            checkForUpdate(manual = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Back from the "install unknown apps" screen.
        val apk = pendingApk
        if (apk != null && updater.canInstall()) {
            pendingApk = null
            updater.install(apk)
        }
    }

    // ---- Self-update ----

    private fun checkForUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        if (manual) {
            binding.updateButton.isEnabled = false
            binding.updateButton.setText(R.string.checking_update)
        }
        updateJob = lifecycleScope.launch {
            val result = runCatching { updater.check() }
            binding.updateButton.isEnabled = true
            binding.updateButton.setText(R.string.check_update)
            val info = result.getOrNull()
            when {
                info != null -> showUpdateDialog(info)
                !manual -> Unit // stay quiet on automatic checks
                result.isFailure -> Toast.makeText(this@MainActivity,
                    getString(R.string.update_failed, result.exceptionOrNull()?.message),
                    Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this@MainActivity, R.string.up_to_date, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available, info.versionName))
            .setMessage(info.notes.ifBlank { null })
            .setPositiveButton(R.string.update_now) { _, _ -> downloadUpdate(info) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun downloadUpdate(info: UpdateInfo) {
        binding.updateButton.isEnabled = false
        binding.updateProgress.visibility = View.VISIBLE
        binding.updateProgress.progress = 0
        updateJob = lifecycleScope.launch {
            try {
                val apk = updater.download(info) { p ->
                    runOnUiThread {
                        binding.updateProgress.progress = p
                        binding.updateButton.text = getString(R.string.downloading_percent, p)
                    }
                }
                if (updater.canInstall()) {
                    updater.install(apk)
                } else {
                    pendingApk = apk
                    Toast.makeText(this@MainActivity, R.string.allow_install, Toast.LENGTH_LONG).show()
                    updater.requestInstallPermission()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.update_failed, e.message),
                    Toast.LENGTH_LONG).show()
            }
            binding.updateProgress.visibility = View.GONE
            binding.updateButton.isEnabled = true
            binding.updateButton.setText(R.string.check_update)
        }
    }

    private fun <T> setupSpinner(spinner: Spinner, items: List<T>, selected: Int, onSelect: (Int) -> Unit) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(selected.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refresh() {
        val source = prefs.language
        val target = Languages.get(prefs.targetLang)
        val running = SubtitleService.isRunning

        binding.engineSpinner.isEnabled = !running
        binding.whisperModelSpinner.isEnabled = !running
        binding.whisperModelSpinner.visibility =
            if (prefs.engine == Engine.WHISPER) View.VISIBLE else View.GONE
        binding.sourceSpinner.isEnabled = !running
        binding.targetSpinner.isEnabled = !running
        binding.toggleButton.setText(if (running) R.string.stop_subtitles else R.string.start_subtitles)
        refreshModel()

        if (translateJob?.isActive != true) {
            binding.translateStatus.setText(R.string.checking)
            binding.translateButton.isEnabled = false
            lifecycleScope.launch {
                val ready = runCatching { models.isTranslationReady(source.code, target.code) }
                    .getOrDefault(false)
                if (translateJob?.isActive == true) return@launch
                binding.translateStatus.setText(
                    if (ready) R.string.translation_ready else R.string.translation_missing
                )
                binding.translateButton.visibility = if (ready) View.GONE else View.VISIBLE
                binding.translateButton.isEnabled = true
            }
        }
    }

    /** Shows the state of the speech model the current settings need. */
    private fun refreshModel() {
        val running = SubtitleService.isRunning
        val spec = prefs.recognitionModel
        val status = binding.modelStatus
        val button = binding.modelButton
        val progress = binding.modelProgress
        progress.visibility = View.GONE
        button.visibility = View.VISIBLE
        button.isEnabled = !running

        if (prefs.engine == Engine.WHISPER && !WhisperLib.isCpuSupported) {
            status.setText(R.string.whisper_cpu_unsupported)
            button.visibility = View.GONE
            return
        }
        if (spec == null) {
            status.setText(R.string.vosk_unsupported)
            button.visibility = View.GONE
            return
        }
        val hint = if (prefs.engine == Engine.WHISPER) "\n" + getString(R.string.whisper_shared_hint) else ""
        when (val state = models.state(spec)) {
            DownloadState.Ready -> {
                status.text = getString(R.string.model_ready) + hint
                button.setText(R.string.delete_model)
            }
            DownloadState.NotDownloaded -> {
                status.text = getString(R.string.model_missing, spec.sizeMb) + hint
                button.setText(R.string.download_model)
            }
            is DownloadState.Downloading -> {
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.percent < 0
                if (state.percent >= 0) progress.progress = state.percent
                val pct = state.percent.coerceAtLeast(0)
                status.text = getString(
                    if (state.waiting) R.string.download_waiting else R.string.downloading_percent, pct
                )
                button.setText(R.string.cancel_download)
                button.isEnabled = true
            }
            DownloadState.Finishing -> {
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                status.setText(R.string.unpacking)
                button.visibility = View.GONE
                if (finishJob?.isActive != true) {
                    finishJob = lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { models.finish(spec) } }
                        result.exceptionOrNull()?.let {
                            Toast.makeText(this@MainActivity,
                                getString(R.string.download_failed, it.message), Toast.LENGTH_LONG).show()
                            models.delete(spec)
                        }
                        refreshModel()
                    }
                }
            }
            is DownloadState.Failed -> {
                status.text = getString(R.string.download_failed_reason, state.reason)
                button.setText(R.string.download_model)
            }
        }
    }

    private fun onModelButton() {
        val spec = prefs.recognitionModel ?: return
        when (models.state(spec)) {
            DownloadState.Ready -> models.delete(spec)
            is DownloadState.Downloading -> models.cancel(spec)
            else -> {
                val name = if (prefs.engine == Engine.WHISPER) "Whisper ${prefs.whisperModel.name.lowercase()}"
                    else prefs.language.label
                models.download(spec, getString(R.string.model_download_title, name))
            }
        }
        refreshModel()
    }

    private fun downloadTranslation() {
        val source = prefs.sourceLang
        val target = prefs.targetLang
        binding.translateButton.isEnabled = false
        binding.translateStatus.setText(R.string.downloading)
        translateJob = lifecycleScope.launch {
            try {
                models.downloadTranslation(source, target)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed, e.message),
                    Toast.LENGTH_LONG).show()
            }
            translateJob = null
            refresh()
        }
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, SubtitleTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_subtitles),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(this, R.string.tile_already_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_CHECK_UPDATE = "check_update"
        private const val MIN_TEXT_SIZE = 12
        private const val MIN_VISIBLE_LINES = 1
    }
}
