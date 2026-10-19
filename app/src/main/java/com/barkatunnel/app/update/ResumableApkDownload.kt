package com.barkatunnel.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Properties

/** Partial bytes and validators survive process death. One writer per directory. */
class ResumableApkDownload(private val client: OkHttpClient) {
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
                if(offset>0 && offset==totalSaved) { progress(offset,totalSaved); return part }
                if(cancelled()) throw IOException("Téléchargement en pause.")
                val request = Request.Builder().url(url).header("Accept-Encoding","identity")
                if(offset>0) request.header("Range","bytes=$offset-").header("If-Range",validator)
                client.newCall(request.build()).execute().use { response ->
                    if(response.request.url.scheme!="https") throw IOException("Redirection non sécurisée refusée.")
                    val body=response.body ?: throw IOException("Réponse vide.")
                    val currentValidator=response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified").orEmpty()
                    val total: Long
                    when(response.code) {
                        200 -> { offset=0; total=body.contentLength() }
                        206 -> {
                            val range=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("Réponse de reprise invalide.")
                            val start=range.groupValues[1].toLong(); val end=range.groupValues[2].toLong()
                            total=range.groupValues[3].toLong()
                            if(start!=offset || end<start || end>=total || (offset>0 && (currentValidator!=validator || total!=totalSaved)))
                                throw IOException("Le fichier du serveur a changé. Republiez un lien APK distinct.")
                        }
                        else -> throw IOException("Téléchargement refusé (HTTP ${response.code}).")
                    }
                    if(total !in 1..MAX_BYTES) throw IOException("Taille APK invalide (300 Mo maximum).")
                    if(directory.usableSpace<total-offset+8*1024*1024) throw IOException("Espace insuffisant sur le téléphone.")
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
                                if(cancelled()) throw IOException("Téléchargement en pause.")
                                val count=input.read(buffer);if(count<0)break
                                if(offset+count>total)throw IOException("Taille APK incohérente.")
                                output.write(buffer,0,count);offset+=count;progress(offset,total)
                            }
                        }
                        output.fd.sync()
                    }
                    if(offset!=total)throw IOException("Connexion interrompue. Appuyez sur Reprendre.")
                }
                return part
            }
        }
    }
    companion object { const val MAX_BYTES=300L*1024*1024 }
}
