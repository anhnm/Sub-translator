package com.subtranslator

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslateLanguage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Downloads models once; after that, recognition and translation run fully offline.
 */
class ModelManager(private val context: Context) {

    private val voskRoot get() = File(context.filesDir, "vosk")

    fun voskModelDir(lang: SourceLanguage) = File(voskRoot, lang.voskModel)

    fun isVoskModelReady(lang: SourceLanguage) = File(voskModelDir(lang), ".complete").exists()

    /** Downloads and unpacks the Vosk model. [onProgress] gets 0..100, or -1 if size is unknown. */
    suspend fun downloadVoskModel(lang: SourceLanguage, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            voskRoot.mkdirs()
            val zip = File(voskRoot, "${lang.voskModel}.zip.part")
            val conn = URL(lang.modelUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -2
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done * 100 / total).toInt() else -1
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val target = voskModelDir(lang)
            target.deleteRecursively()
            target.mkdirs()
            unzipStrippingTopDir(zip, target)
            zip.delete()
            File(target, ".complete").createNewFile()
        }

    fun deleteVoskModel(lang: SourceLanguage) {
        voskModelDir(lang).deleteRecursively()
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
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
