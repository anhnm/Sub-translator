package com.subtranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Foreground service: captures playback audio, recognizes speech, translates it and
 * shows the result in a floating overlay.
 */
class SubtitleService : Service(), SpeechEngine.Listener, SubtitleOverlay.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var engine: SpeechEngine? = null
    private var overlay: SubtitleOverlay? = null
    private var translator: Translator? = null

    // Caption state: the sentence currently being spoken (finished ones live in the overlay).
    private var curOriginal = ""
    private var curTranslated = ""
    private var utterance = 0
    private var partialInFlight = false
    private var pendingPartial: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (resultCode != Activity.RESULT_OK || data == null || isRunning) {
                    if (!isRunning) stopSelf()
                    return START_NOT_STICKY
                }
                start(resultCode, data)
            }
            ACTION_STOP -> stopSelf()
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(resultCode: Int, data: Intent) {
        createChannel()
        // Must be in the foreground before obtaining the MediaProjection (Android 14+).
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        setRunning(this, true)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        overlay = SubtitleOverlay(this, prefs, this).also { it.show() }
        startPipeline()
    }

    /** (Re)creates the recognizer and translator for the current language settings. */
    private fun startPipeline() {
        val mp = projection ?: return
        val previous = engine
        previous?.stop()
        translator?.close()
        // Drop captions and pending translations from the previous languages.
        utterance++
        curOriginal = ""
        curTranslated = ""
        pendingPartial = null

        val source = prefs.language
        val target = Languages.get(prefs.targetLang)
        overlay?.updateLanguageChip()
        overlay?.setLive("", "")
        overlay?.setStatus(getString(R.string.status_loading_model))

        translator = createTranslator(source.code, target.code)
        val spec = prefs.recognitionModel
        if (spec == null || !ModelManager(this).isReady(spec)) {
            onError(getString(R.string.need_model))
            return
        }
        val modelPath = ModelManager(this).modelPath(spec)
        engine = when (prefs.engine) {
            Engine.WHISPER -> WhisperEngine(mp, source, modelPath, this)
            Engine.VOSK -> VoskEngine(mp, source, modelPath, this)
        }.also { it.start(previous) }
        updateNotification()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        translator?.close()
        translator = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        setRunning(this, false)
        super.onDestroy()
    }

    // ---- SpeechEngine.Listener (main thread) ----

    override fun onReady() {
        overlay?.setStatus(getString(R.string.status_listening))
    }

    override fun onPartial(text: String) {
        if (text == SpeechEngine.SPEAKING) {
            // Whisper heard speech but has no text yet.
            overlay?.setLive("", text)
            return
        }
        curOriginal = text
        overlay?.setLive(curOriginal, curTranslated)
        translatePartial(text)
    }

    override fun onFinal(text: String) {
        utterance++
        pendingPartial = null
        // Show the partial translation until the full sentence is translated.
        val line = overlay?.addLine(text, curTranslated) ?: return
        curOriginal = ""
        curTranslated = ""
        translate(text) { result -> overlay?.updateLine(line, text, result) }
    }

    override fun onError(message: String) {
        Toast.makeText(this, getString(R.string.error_engine, message), Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun createTranslator(source: String, target: String): Translator? {
        if (source == target) return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also { t ->
            // No-op when the models are already on the device (normal case).
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnFailureListener { Log.w(TAG, "Translation model unavailable", it) }
        }
    }

    // ---- SubtitleOverlay.Listener ----

    override fun onStopRequested() = stopSelf()

    override fun onSettingsRequested() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onSourceSelected(code: String) {
        prefs.sourceLang = code
        startPipeline()
    }

    override fun onTargetSelected(code: String) {
        prefs.targetLang = code
        // Keep listening; only the translator changes.
        translator?.close()
        translator = createTranslator(prefs.sourceLang, code)
        overlay?.updateLanguageChip()
        updateNotification()
    }

    // ---- Captions ----

    private fun translatePartial(text: String) {
        if (partialInFlight) {
            pendingPartial = text
            return
        }
        partialInFlight = true
        val id = utterance
        translate(text) { result ->
            partialInFlight = false
            if (id == utterance) {
                curTranslated = result
                overlay?.setLive(curOriginal, curTranslated)
            }
            pendingPartial?.let {
                pendingPartial = null
                translatePartial(it)
            }
        }
    }

    private fun translate(text: String, onDone: (String) -> Unit) {
        val t = translator
        if (t == null) {
            onDone(text)
            return
        }
        t.translate(text)
            .addOnSuccessListener { onDone(it) }
            .addOnFailureListener { onDone(text) }
    }

    // ---- Notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, SubtitleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val source = prefs.language
        val target = Languages.get(prefs.targetLang)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("${source.label} → ${target.label}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "SubtitleService"
        const val ACTION_START = "com.subtranslator.START"
        const val ACTION_STOP = "com.subtranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "subtitles"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        private fun setRunning(context: Context, running: Boolean) {
            isRunning = running
            TileService.requestListeningState(
                context, ComponentName(context, SubtitleTileService::class.java)
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, SubtitleService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not stop service", e)
            }
        }
    }
}
