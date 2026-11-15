package com.barkatunnel.app.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BackendAppUpdate
import com.barkatunnel.app.ui.SystemBars

class ApkDownloadActivity : AppCompatActivity() {
    private lateinit var download: HomeApkDownload

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_apk_download)
        SystemBars.apply(this)
        download = HomeApkDownload(this, findViewById(R.id.apkDownloadScreen))
    }

    override fun onResume() {
        super.onResume()
        download.resume()
    }

    override fun onStop() {
        download.stop()
        super.onStop()
    }

    companion object {
        fun open(context: Context, update: BackendAppUpdate) {
            HomeApkDownload.select(context, update)
            val intent = Intent(context, ApkDownloadActivity::class.java)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
