package com.barkatunnel.app.consumption

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import java.util.concurrent.Executors

class ConsumptionActivity : AppCompatActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var visible=false
    private var refreshGeneration=0L
    private var busy=false
    private val refresh=object: Runnable {
        override fun run() {
            if (!visible) return
            val generation=refreshGeneration
            worker.execute {
                val result=runCatching { ConsumptionStore.totals(this@ConsumptionActivity) }
                runOnUiThread {
                    if (!visible || refreshGeneration!=generation) return@runOnUiThread
                    result.onSuccess { showTotals(it) }
                    handler.postDelayed(this,3000L)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_consumption);SystemBars.apply(this)
        findViewById<android.view.View>(R.id.consumptionBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.consumptionSave).setOnClickListener {
            if(!busy)pick(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                putExtra(Intent.EXTRA_TITLE,"BarkaTunnel-consommation.json")
            },SAVE)
        }
        findViewById<android.view.View>(R.id.consumptionRestore).setOnClickListener {
            if(!busy)pick(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="*/*" },RESTORE)
        }
    }
    @Suppress("DEPRECATION") private fun pick(intent: Intent,code: Int) {
        try { startActivityForResult(intent,code) }
        catch (_: android.content.ActivityNotFoundException) { notice(R.string.consumption_file_error) }
    }
    override fun onStart() { super.onStart();refreshGeneration++;visible=true;handler.post(refresh) }
    override fun onStop() { visible=false;refreshGeneration++;handler.removeCallbacks(refresh);super.onStop() }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
    private fun showTotals(t: ConsumptionStore.Totals) {
        findViewById<TextView>(R.id.consumptionToday).text=volume(t.today)
        findViewById<TextView>(R.id.consumptionYesterday).text=volume(t.yesterday)
        findViewById<TextView>(R.id.consumptionTotal).text=volume(t.total)
        findViewById<TextView>(R.id.consumptionDirections).text=getString(R.string.consumption_directions,volume(t.down),volume(t.up))
        val scale=maxOf(t.today,t.yesterday,1L).toDouble()
        findViewById<ProgressBar>(R.id.consumptionTodayBar).progress=(t.today/scale*100).toInt()
        findViewById<ProgressBar>(R.id.consumptionYesterdayBar).progress=(t.yesterday/scale*100).toInt()
    }
    private fun volume(bytes: Long): String {
        val gb=bytes>=1_000_000_000L
        return String.format(java.util.Locale.getDefault(),"%.2f %s",bytes/(if(gb)1_000_000_000.0 else 1_000_000.0),getString(if(gb)R.string.consumption_unit_gb else R.string.consumption_unit_mb))
    }
    private fun notice(message: Int) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    @Deprecated("Legacy activity result callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || requestCode !in listOf(SAVE,RESTORE))return
        val uri: Uri=data?.data ?: return
        if(busy)return
        busy=true
        worker.execute {
            val result=runCatching {
                if(requestCode==SAVE) {
                    val bytes=ConsumptionStore.export(this).toByteArray(Charsets.UTF_8)
                    val output=contentResolver.openOutputStream(uri,"wt") ?: error("Fichier inaccessible")
                    output.use { it.write(bytes) }
                } else {
                    val input=contentResolver.openInputStream(uri) ?: error("Fichier inaccessible")
                    val bytes=input.use { stream ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) { val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=4_000_000);out.write(buffer,0,n) };out.toByteArray()
                    }
                    ConsumptionStore.restore(this,String(bytes,Charsets.UTF_8))
                }
            }
            runOnUiThread {
                busy=false
                if(!isFinishing && !isDestroyed)notice(if(result.isSuccess) {
                    if(requestCode==SAVE)R.string.consumption_saved else R.string.consumption_restored
                } else R.string.consumption_file_error)
            }
        }
    }
    companion object { private const val SAVE=710;private const val RESTORE=711 }
}
