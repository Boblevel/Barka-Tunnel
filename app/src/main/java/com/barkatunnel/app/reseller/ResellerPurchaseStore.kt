package com.barkatunnel.app.reseller

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Ownership token and pending order stay outside Android cloud backups. */
object ResellerPurchaseStore {
    @Synchronized fun read(context: Context): JSONObject {
        val file = AtomicFile(File(context.noBackupFilesDir, "reseller_purchase.json"))
        return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() })
        else JSONObject().put("owner_key", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).also { write(context, it) }
    }
    @Synchronized fun write(context: Context, data: JSONObject) {
        val file = AtomicFile(File(context.noBackupFilesDir, "reseller_purchase.json"))
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}
