package com.barkatunnel.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object ApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, url: String, version: Long) {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val expected=Regex("/downloads/apk/([a-f0-9]{64})\\.apk$").find(java.net.URI(url).path)?.groupValues?.get(1)
        if(expected!=null){
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            check(digest.digest().joinToString(""){"%02x".format(it)}==expected){"APK endommagé : vérification SHA-256 refusée."}
        }
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw IllegalStateException("APK Android invalide.")
        val installed=packageManager.getPackageInfo(packageName,flags)
        fun versionOf(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
        check(archive.packageName==packageName) { "Cet APK ne correspond pas à Barka Tunnel." }
        check(versionOf(archive)==version&&version>versionOf(installed)){
            "Version annoncée : $version ; version de l’APK : ${versionOf(archive)}. Contactez le support pour corriger la publication."
        }
        if (Build.VERSION.SDK_INT >= 24) {
            check((archive.applicationInfo?.minSdkVersion ?: 23) <= Build.VERSION.SDK_INT) {
                "La version Android de ce téléphone est trop ancienne pour cet APK."
            }
        }
        ZipFile(file).use { apk ->
            check(Build.SUPPORTED_ABIS.any { abi -> apk.getEntry("lib/$abi/libbarka_xray.so") != null }) {
                "Cet APK ne contient pas le moteur adapté à ce téléphone."
            }
        }
        fun signers(info:PackageInfo):Set<String> = (if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map{sha256(it.toByteArray())}.toSet()
        check(signers(installed).isNotEmpty()&&signers(archive)==signers(installed)){"Signature APK différente : installation refusée."}
    }
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
