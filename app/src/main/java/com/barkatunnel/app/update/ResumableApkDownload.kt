package com.barkatunnel.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Properties

class ApkDownloadRejected(message: String) : IOException(message)

/** Partial bytes and validators survive process death. One writer per directory. */
class ResumableApkDownload(private val client: OkHttpClient) {
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var stopped = false
    fun cancel() {
        stopped = true
        activeCall?.cancel()
    }

    fun download(url: String, directory: File, cancelled: () -> Boolean,
                 progress: (Long, Long) -> Unit): File {
        require(AppUpdateDestination.isValid(url)) { "Lien HTTPS invalide." }
        directory.mkdirs()
        RandomAccessFile(File(directory,"lock"),"rw").use { lock ->
            lock.channel.lock().use {
                val part = File(directory,"update.part")
                val metadata = File(directory,"download.properties")
                val meta = Properties().apply { if(metadata.isFile) metadata.inputStream().use { load(it) } }
                var offset = part.length()
                val totalSaved = meta.getProperty("total","-1").toLongOrNull() ?: -1
                val validator = meta.getProperty("validator","")
                if(meta.getProperty("url","")!=url || validator.isBlank() || offset>totalSaved) offset=0
                if(stopped || cancelled()) throw IOException("Téléchargement en pause.")
                if(offset>0 && offset==totalSaved) { progress(offset,totalSaved); return part }
                val request = Request.Builder().url(url).header("Accept-Encoding","identity")
                if(offset>0) request.header("Range","bytes=$offset-").header("If-Range",validator)
                val call = client.newCall(request.build())
                activeCall = call
                if (stopped || cancelled()) call.cancel()
                try { call.execute().use { response ->
                    if(response.request.url.scheme!="https") throw IOException("Redirection non sécurisée refusée.")
                    val body=response.body ?: throw IOException("Réponse vide.")
                    if (response.code in listOf(408, 429, 500, 502, 503, 504))
                        throw IOException("Serveur temporairement indisponible (HTTP ${response.code}).")
                    if (response.code == 416 && offset > 0) {
                        metadata.delete()
                        throw IOException("Reprise expirée. Le téléchargement recommencera sans mélanger les fichiers.")
                    }
                    val contentType = body.contentType()
                    if (contentType?.type == "text" || contentType?.subtype == "json" ||
                        contentType?.subtype?.endsWith("+json") == true)
                        throw ApkDownloadRejected("Le serveur ne fournit pas un APK. Réessayez plus tard ou contactez le support.")
                    val currentValidator=response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified").orEmpty()
                    val total: Long
                    when(response.code) {
                        200 -> { offset=0; total=body.contentLength() }
                        206 -> {
                            val range=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                                ?: run { metadata.delete(); throw IOException("Réponse de reprise invalide.") }
                            val start=range.groupValues[1].toLong(); val end=range.groupValues[2].toLong()
                            total=range.groupValues[3].toLong()
                            if(start!=offset || end<start || end>=total || (offset>0 && (currentValidator!=validator || total!=totalSaved))) {
                                metadata.delete()
                                throw IOException("Le fichier du serveur a changé. Republiez un lien APK distinct.")
                            }
                        }
                        else -> throw ApkDownloadRejected("Téléchargement refusé (HTTP ${response.code}). Réessayez plus tard.")
                    }
                    if(total !in 1..MAX_BYTES) throw ApkDownloadRejected("Taille APK invalide (300 Mo maximum).")
                    if(directory.usableSpace<total-offset+8*1024*1024) throw ApkDownloadRejected("Espace insuffisant sur le téléphone.")
                    RandomAccessFile(part,"rw").use { output ->
                        output.setLength(offset); output.seek(offset)
                        meta.setProperty("url",url);meta.setProperty("total",total.toString());meta.setProperty("validator",currentValidator)
                        val temp=File(directory,"download.tmp")
                        temp.outputStream().use { meta.store(it,null) }
                        if(!temp.renameTo(metadata)) throw IOException("Impossible de conserver la reprise.")
                        progress(offset,total)
                        body.byteStream().use { input ->
                            val buffer=ByteArray(64*1024)
                            while(true) {
                                if(stopped || cancelled()) throw IOException("Téléchargement en pause.")
                                val count=input.read(buffer);if(count<0)break
                                if(offset+count>total)throw IOException("Taille APK incohérente.")
                                output.write(buffer,0,count);offset+=count;progress(offset,total)
                            }
                        }
                        output.fd.sync()
                    }
                    if(offset!=total)throw IOException("Connexion interrompue. Appuyez sur Reprendre.")
                } } finally { activeCall = null }
                return part
            }
        }
    }
    companion object { const val MAX_BYTES=300L*1024*1024 }
}
