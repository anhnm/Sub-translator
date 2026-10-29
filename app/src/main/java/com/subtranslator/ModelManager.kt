package com.subtranslator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data object Ready : DownloadState()
    /** [percent] is -1 while the size is unknown; [waiting] when paused (e.g. no network). */
    data class Downloading(val percent: Int, val waiting: Boolean) : DownloadState()
    /** Download finished; the file still has to be unpacked/moved by [ModelManager.finish]. */
    data object Finishing : DownloadState()
    data class Failed(val reason: Int) : DownloadState()
}

/**
 * Speech models are fetched with the system DownloadManager, so downloads keep going when the
 * app is closed or the screen is off and resume after network drops. After that everything
 * runs offline.
 */
class ModelManager(context: Context) {

    private val context = context.applicationContext
    private val dm = this.context.getSystemService(DownloadManager::class.java)
    private val ids = this.context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** DownloadManager can only write to external storage; the app's own folder needs no permission. */
    private val downloadDir get() = File(context.getExternalFilesDir(null), "models")

    private fun tempFile(spec: ModelSpec) = File(downloadDir, spec.name + ".download")

    /** Where a ready model lives: an unpacked folder (Vosk) or a single file (Whisper). */
    fun modelPath(spec: ModelSpec): File =
        if (spec.zipped) File(context.filesDir, "vosk/${spec.name}") else File(downloadDir, spec.name)

    fun isReady(spec: ModelSpec?): Boolean = when {
        spec == null -> false
        spec.zipped -> File(modelPath(spec), ".complete").exists()
        else -> modelPath(spec).exists()
    }

    fun state(spec: ModelSpec): DownloadState {
        if (isReady(spec)) return DownloadState.Ready
        val id = ids.getLong(spec.name, -1)
        if (id < 0) return DownloadState.NotDownloaded
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                ids.edit { remove(spec.name) }
                return DownloadState.NotDownloaded
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val percent = if (total > 0) (done * 100 / total).toInt() else -1
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Finishing
                DownloadManager.STATUS_FAILED -> DownloadState.Failed(reason)
                DownloadManager.STATUS_PAUSED -> DownloadState.Downloading(percent, waiting = true)
                else -> DownloadState.Downloading(percent, waiting = false)
            }
        }
    }

    fun download(spec: ModelSpec, title: String) {
        cancel(spec)
        downloadDir.mkdirs()
        tempFile(spec).delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle(title)
            .setDescription(context.getString(R.string.app_name))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(tempFile(spec)))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        ids.edit { putLong(spec.name, dm.enqueue(request)) }
    }

    fun cancel(spec: ModelSpec) {
        val id = ids.getLong(spec.name, -1)
        if (id >= 0) dm.remove(id)
        ids.edit { remove(spec.name) }
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        modelPath(spec).deleteRecursively()
    }

    /**
     * Unpacks/moves a successfully downloaded model into place. Slow for zips: call off the
     * main thread. Safe to call repeatedly and from several places at once.
     */
    fun finish(spec: ModelSpec) = synchronized(LOCK) {
        if (isReady(spec) || state(spec) != DownloadState.Finishing) return@synchronized
        val temp = tempFile(spec)
        val target = modelPath(spec)
        if (spec.zipped) {
            target.deleteRecursively()
            target.mkdirs()
            unzipStrippingTopDir(temp, target)
            File(target, ".complete").createNewFile()
        } else if (!temp.renameTo(target)) {
            throw IOException("Cannot move ${temp.name}")
        }
        // Removing the finished entry also deletes the leftover zip.
        cancel(spec)
    }

    /** Finishes every completed download (used when the app or the receiver wakes up). */
    fun finishAll() {
        val specs = Languages.all.mapNotNull { it.voskSpec } + WhisperModel.entries.map { it.spec }
        specs.filter { ids.contains(it.name) }.forEach { runCatching { finish(it) } }
    }

    /** Model archives contain a single top-level folder; extract its contents into [target]. */
    private fun unzipStrippingTopDir(zip: File, target: File) {
        val canonicalTarget = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(canonicalTarget)) {
                    throw IOException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
    }

    // ---- ML Kit translation models ----

    private val remoteModels = RemoteModelManager.getInstance()

    suspend fun isTranslationReady(source: String, target: String): Boolean {
        if (source == target) return true
        val downloaded = remoteModels.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }.toSet()
        // English is built in; every other language needs its own model.
        return listOf(source, target).all { it == TranslateLanguage.ENGLISH || it in downloaded }
    }

    suspend fun downloadTranslation(source: String, target: String) {
        val conditions = DownloadConditions.Builder().build()
        for (lang in setOf(source, target)) {
            if (lang == TranslateLanguage.ENGLISH) continue
            remoteModels.download(TranslateRemoteModel.Builder(lang).build(), conditions).await()
        }
    }

    private companion object {
        val LOCK = Any()
    }
}

/** Unpacks models as soon as their download completes, even if the app isn't open. */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                ModelManager(context).finishAll()
            } finally {
                pending.finish()
            }
        }.start()
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
