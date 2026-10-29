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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.subtranslator.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var models: ModelManager
    private var voskJob: Job? = null
    private var translateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        models = ModelManager(this)

        setupSpinner(binding.sourceSpinner, Languages.sources,
            Languages.sources.indexOf(Languages.source(prefs.sourceLang))) {
            prefs.sourceLang = Languages.sources[it].code
            refresh()
        }
        setupSpinner(binding.targetSpinner, Languages.targets,
            Languages.targets.indexOf(Languages.target(prefs.targetLang))) {
            prefs.targetLang = Languages.targets[it].code
            refresh()
        }

        binding.voskButton.setOnClickListener { onVoskButton() }
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
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        val source = Languages.source(prefs.sourceLang)
        val target = Languages.target(prefs.targetLang)
        val running = SubtitleService.isRunning

        binding.sourceSpinner.isEnabled = !running
        binding.targetSpinner.isEnabled = !running
        binding.toggleButton.setText(if (running) R.string.stop_subtitles else R.string.start_subtitles)

        if (voskJob?.isActive != true) {
            val ready = models.isVoskModelReady(source)
            binding.voskStatus.setText(if (ready) R.string.model_ready else R.string.model_missing)
            binding.voskButton.setText(if (ready) R.string.delete_model else R.string.download_model)
            binding.voskButton.isEnabled = !running
            binding.voskProgress.visibility = View.GONE
        }

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

    private fun onVoskButton() {
        val source = Languages.source(prefs.sourceLang)
        if (models.isVoskModelReady(source)) {
            models.deleteVoskModel(source)
            refresh()
            return
        }
        binding.voskButton.isEnabled = false
        binding.voskProgress.visibility = View.VISIBLE
        binding.voskProgress.isIndeterminate = true
        binding.voskStatus.setText(R.string.downloading)
        voskJob = lifecycleScope.launch {
            try {
                models.downloadVoskModel(source) { p ->
                    runOnUiThread {
                        if (p >= 0) {
                            binding.voskProgress.isIndeterminate = false
                            binding.voskProgress.progress = p
                            binding.voskStatus.text = getString(R.string.downloading_percent, p)
                            if (p == 100) binding.voskStatus.setText(R.string.unpacking)
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed, e.message),
                    Toast.LENGTH_LONG).show()
            }
            voskJob = null
            refresh()
        }
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
        private const val MIN_TEXT_SIZE = 12
    }
}
