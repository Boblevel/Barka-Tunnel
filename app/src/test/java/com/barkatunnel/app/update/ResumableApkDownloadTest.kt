package com.barkatunnel.app.update
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
class ResumableApkDownloadTest {
    @get:Rule val folder=TemporaryFolder()
    private val url="https://updates.example.org/app.apk"
    private fun response(req:okhttp3.Request,bytes:ByteArray,code:Int,etag:String="\"one\"",range:String?=null):Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("ETag",etag).apply{range?.let{header("Content-Range",it)}}.body(bytes.toResponseBody()).build()
    @Test fun interruptedDownloadResumesExactlyAtStoredOffset() {
        val bytes=ByteArray(200000){(it%251).toByte()};var requests=0;var seen:String?=null
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests++;val req=chain.request();seen=req.header("Range")
            val offset=seen?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?:0
            if(offset>0){assertEquals("\"one\"",req.header("If-Range"));response(req,bytes.copyOfRange(offset,bytes.size),206,range="bytes $offset-${bytes.size-1}/${bytes.size}")}
            else response(req,bytes,200)
        }.build()
        val dir=folder.newFolder();var pause=false
        try {ResumableApkDownload(client).download(url,dir,{pause}){done,_->if(done>=65536)pause=true};fail("must pause")}catch(_:IOException){}
        val offset=java.io.File(dir,"update.part").length();assertTrue(offset in 1 until bytes.size.toLong())
        val result=ResumableApkDownload(client).download(url,dir,{false}){_,_->}
        assertEquals("bytes=$offset-",seen);assertArrayEquals(bytes,result.readBytes())
        ResumableApkDownload(client).download(url,dir,{false}){_,_->};assertEquals(2,requests)
    }
    @Test fun replacementFileIsNotAppendedToOldBytes() {
        val dir=folder.newFolder();var pause=false
        val first=OkHttpClient.Builder().addInterceptor{response(it.request(),ByteArray(100000){1},200)}.build()
        try{ResumableApkDownload(first).download(url,dir,{pause}){done,_->if(done>0)pause=true}}catch(_:IOException){}
        val bytes=ByteArray(130000){2}
        val second=OkHttpClient.Builder().addInterceptor{assertNotNull(it.request().header("Range"));response(it.request(),bytes,200,"\"two\"")}.build()
        assertArrayEquals(bytes,ResumableApkDownload(second).download(url,dir,{false}){_,_->}.readBytes())
    }
    @Test fun incorrectRangeIsRejected() {
        val dir=folder.newFolder()
        val client=OkHttpClient.Builder().addInterceptor{response(it.request(),byteArrayOf(1,2),206,range="bytes 12-13/14")}.build()
        try{ResumableApkDownload(client).download(url,dir,{false}){_,_->};fail("invalid range accepted")}
        catch(_:IOException){assertEquals(0,java.io.File(dir,"update.part").length())}
    }
    @Test fun htmlErrorWithHttp200IsNeverTreatedAsAnApk() {
        val dir = folder.newFolder()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html>Site unavailable</html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            ResumableApkDownload(client).download(url, dir, { false }) { _, _ -> fail("Invalid download reported progress") }
            fail("HTML must be rejected")
        } catch (error: ApkDownloadRejected) {
            assertTrue(error.message.orEmpty().contains("APK"))
            assertEquals(0, java.io.File(dir, "update.part").length())
        }
    }

    @Test fun temporaryServerFailureRemainsRetryableEvenWithHtmlBody() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Busy")
                .body("maintenance".toResponseBody("text/html".toMediaType())).build()
        }.build()
        try { ResumableApkDownload(client).download(url, folder.newFolder(), { false }) { _, _ -> }; fail() }
        catch (error: IOException) { assertFalse(error is ApkDownloadRejected) }
    }
    @Test fun expiredRangeClearsValidatorAndNextAttemptStartsFresh() {
        val dir = folder.newFolder(); var pause = false
        val first = OkHttpClient.Builder().addInterceptor { response(it.request(), ByteArray(100000), 200) }.build()
        try { ResumableApkDownload(first).download(url, dir, { pause }) { done, _ -> if (done > 0) pause = true } }
        catch (_: IOException) { }
        val expired = OkHttpClient.Builder().addInterceptor { response(it.request(), byteArrayOf(), 416) }.build()
        try { ResumableApkDownload(expired).download(url, dir, { false }) { _, _ -> }; fail() }
        catch (_: IOException) { }
        val bytes = byteArrayOf(7, 8, 9)
        val fresh = OkHttpClient.Builder().addInterceptor {
            assertNull(it.request().header("Range")); response(it.request(), bytes, 200)
        }.build()
        assertArrayEquals(bytes, ResumableApkDownload(fresh).download(url, dir, { false }) { _, _ -> }.readBytes())
    }
    @Test fun cancelInterruptsActiveCallBeforeReadTimeout() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(chain.call().isCanceled())
            throw IOException("Cancelled")
        }.build()
        val download = ResumableApkDownload(client)
        val dir = folder.newFolder()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Boolean> {
                try { download.download(url, dir, { false }) { _, _ -> }; false }
                catch (_: IOException) { true }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            download.cancel()
            assertTrue(task.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { download.cancel(); executor.shutdownNow() }
    }
}
