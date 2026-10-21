package com.barkatunnel.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object ApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, url: String, version: Long) {
        val packageManager = context.packageManager
        val packageName = context.packageName
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
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
