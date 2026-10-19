package com.barkatunnel.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.barkatunnel.app.backend.BackendAppUpdate
import com.barkatunnel.app.vpnc6.BarkaVpnService
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ApkDownloadActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button
    private var ready: File?=null
    private val paused=AtomicBoolean(false)
    private var worker: Thread?=null
    private var awaitingPermission=false
    private lateinit var url: String
    private var version=0L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        url=intent.getStringExtra("url").orEmpty();version=intent.getLongExtra("version",0)
        if(!AppUpdateDestination.isValid(url)||version<=0){finish();return}
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            val pad=(24*resources.displayMetrics.density).toInt();setPadding(pad,pad*2,pad,pad)
        }
        status=TextView(this).apply {textSize=18f;text="Préparation de la mise à jour…"}
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {max=100}
        action=Button(this).apply {text="METTRE EN PAUSE"}
        val close=Button(this).apply {text="FERMER";setOnClickListener{finish()}}
        layout.addView(status);layout.addView(bar);layout.addView(action);layout.addView(close)
        setContentView(layout);com.barkatunnel.app.ui.SystemBars.apply(this)
        action.setOnClickListener {
            when {
                ready!=null -> install()
                worker?.isAlive==true -> {paused.set(true);action.isEnabled=false;status.text="Mise en pause…"}
                else -> startDownload()
            }
        }
        startDownload()
    }
    private fun startDownload() {
        if(worker?.isAlive==true)return
        paused.set(false);action.isEnabled=true;action.text="METTRE EN PAUSE"
        status.text="Connexion… Les données déjà reçues sont conservées."
        val context=applicationContext
        worker=Thread {
            try {
                val key=sha256((url+"|"+version).toByteArray())
                val root=File(context.filesDir,"apk-updates/$key")
                val builder=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).followSslRedirects(false)
                BarkaVpnService.connectedSocksPort()?.let { builder.proxy(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",it))) }
                var lastUi=0L
                val apk=File(root,"BarkaTunnel.apk")
                val file=apk.takeIf{it.isFile} ?: ResumableApkDownload(builder.build()).download(url,root,{paused.get()}) {done,total ->
                    val now=System.currentTimeMillis()
                    if(now-lastUi>200||done==total){lastUi=now;runOnUiThread {if(!isDestroyed){bar.progress=(done*100/total).toInt();status.text="${done/1048576} / ${total/1048576} Mo • ${bar.progress}%"}}}
                }
                try{verifyApk(file)}catch(error:Exception){file.delete();throw error}
                if(file!=apk&&!file.renameTo(apk))throw IllegalStateException("Impossible de préparer l’installation.")
                ready=apk
                runOnUiThread {if(!isDestroyed){bar.progress=100;status.text="Téléchargement terminé. APK vérifié.";action.isEnabled=true;action.text="INSTALLER";if(!paused.get())install()}}
            }catch(error:Exception){runOnUiThread{if(!isDestroyed){status.text=error.message?:"Téléchargement interrompu.";action.isEnabled=true;action.text="REPRENDRE"}}}
        }.also {it.start()}
    }
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File) {
        val expected=Regex("/downloads/apk/([a-f0-9]{64})\\.apk$").find(url)?.groupValues?.get(1)
        if(expected!=null){
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            check(digest.digest().joinToString(""){"%02x".format(it)}==expected){"APK endommagé : vérification SHA-256 refusée."}
        }
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw IllegalStateException("APK Android invalide.")
        val installed=packageManager.getPackageInfo(packageName,flags)
        fun versionOf(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
        check(archive.packageName==packageName&&versionOf(archive)==version&&version>versionOf(installed)){"Cet APK ne correspond pas à la version annoncée."}
        fun signers(info:PackageInfo):Set<String> = (if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map{sha256(it.toByteArray())}.toSet()
        check(signers(installed).isNotEmpty()&&signers(archive)==signers(installed)){"Signature APK différente : installation refusée."}
    }
    private fun install() {
        val apk=ready?:return
        try {
            if(Build.VERSION.SDK_INT>=26&&!packageManager.canRequestPackageInstalls()){
                status.text="Autorisez Barka Tunnel à installer cette mise à jour, puis revenez ici."
                awaitingPermission=true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")));return
            }
            val uri=FileProvider.getUriForFile(this,"$packageName.apk-provider",apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }catch(_:Exception){status.text="Installation non ouverte. Appuyez sur Installer pour réessayer."}
    }
    override fun onResume(){super.onResume();if(awaitingPermission){awaitingPermission=false;if(Build.VERSION.SDK_INT<26||packageManager.canRequestPackageInstalls())install()}}
    override fun onStop(){paused.set(true);super.onStop()}
    companion object {
        private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun open(context:Context,update:BackendAppUpdate){context.startActivity(Intent(context,ApkDownloadActivity::class.java).putExtra("url",update.apkUrl).putExtra("version",update.latestVersionCode))}
    }
}
