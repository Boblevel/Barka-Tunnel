package com.barkatunnel.app.reseller

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.ui.SystemBars
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.UUID

class ResellerPurchaseActivity : AppCompatActivity() {
    private lateinit var state: JSONObject
    private lateinit var credentialsCard: View
    private lateinit var statusCard: View
    private lateinit var status: TextView
    private lateinit var pay: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var plans: RadioGroup
    private var months = 1
    private var account: JSONObject? = null
    private var busy = false
    private var resumed = false
    private var polls = 0
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = try { ResellerPurchaseStore.read(this) } catch (_: Exception) {
            Toast.makeText(this,R.string.reseller_storage_error,Toast.LENGTH_LONG).show()
            finish(); return
        }
        setContentView(R.layout.activity_reseller_purchase)
        SystemBars.apply(this)
        findViewById<View>(R.id.resellerBackButton).setOnClickListener { finish() }
        plans = findViewById(R.id.resellerPlans)
        plans.setOnCheckedChangeListener { _, id -> months = if (id == R.id.resellerTwoMonths) 2 else 1 }
        plans.check(if (state.optInt("months", 1) == 2) R.id.resellerTwoMonths else R.id.resellerMonth)
        pay = findViewById(R.id.resellerPay)
        pay.setOnClickListener { startPurchase() }
        findViewById<View>(R.id.resellerVerify).setOnClickListener { polls=0; refresh() }
        status = findViewById(R.id.resellerStatus)
        statusCard = findViewById(R.id.resellerStatusCard)
        credentialsCard = findViewById(R.id.resellerCredentialsCard)
        findViewById<View>(R.id.resellerCopy).setOnClickListener {
            account?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.reseller_credentials), credentialText(it)))
                Toast.makeText(this,R.string.reseller_copied,Toast.LENGTH_SHORT).show()
            }
        }
        restore = findViewById(R.id.resellerRestore)
        restore.setOnClickListener { restoreAccount() }
    }

    override fun onResume() { super.onResume(); if (!::state.isInitialized || isFinishing) return; resumed=true; polls=0; refresh() }
    override fun onPause() { resumed=false; handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { dialog?.dismiss(); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun showStatus(message: String) {
        status.text = message
        statusCard.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun localizedMessage(message: String?): String {
        val key = when (message) {
            "Sous-panel supprimé. Contactez le support avec la référence du paiement." -> R.string.reseller_deleted
            "Sous-panel désactivé par l’administration. Contactez le support." -> R.string.reseller_disabled
            "Identifiants incorrects." -> R.string.reseller_bad_credentials
            "Cet appareil est déjà associé à un autre sous-panel." -> R.string.reseller_other_account
            "Terminez le paiement en cours avant de récupérer un autre compte." -> R.string.reseller_finish_payment
            "Un paiement est déjà en cours. Vérifiez sa confirmation." -> R.string.reseller_payment_exists
            "Paiement introuvable." -> R.string.reseller_not_found
            null, "Connectez-vous à un réseau, puis réessayez." -> R.string.reseller_network_error
            else -> null
        }
        return if (key != null) getString(key) else message.orEmpty()
            .replace(Regex("(?i)sous[- ]panel"), if (androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]?.language == "en") "reseller account" else "compte revendeur")
    }

    private fun showCredentials(value: JSONObject) {
        credentialsCard.visibility = View.VISIBLE
        findViewById<TextView>(R.id.resellerLink).text = value.optString("panel_url")
        findViewById<TextView>(R.id.resellerUsername).text = value.optString("username")
        findViewById<TextView>(R.id.resellerPassword).text = passwordText(value)
        findViewById<TextView>(R.id.resellerExpiry).text = expiryText(value)
    }

    private fun passwordText(value: JSONObject): String = if (value.isNull("password"))
        getString(R.string.reseller_password_help) else value.optString("password")

    private fun expiryText(value: JSONObject): String = runCatching {
        val date = java.util.Date(java.time.Instant.parse(value.getString("expires_at")).toEpochMilli())
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
            androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]).format(date)
    }.getOrDefault(value.optString("expires_at"))

    private fun payload():JSONObject = JSONObject().put("owner_key",state.getString("owner_key"))
    private fun api(action:String,data:JSONObject):JSONObject = BarkaBackendClient(this).resellerPurchase(action,data)
    private fun request(action:()->JSONObject,done:(JSONObject)->Unit) {
        if(busy)return
        busy=true;pay.isEnabled=false;restore.isEnabled=false
        Thread {
            val result=runCatching(action)
            runOnUiThread {
                busy=false
                if(isDestroyed || isFinishing)return@runOnUiThread
                pay.isEnabled=true;restore.isEnabled=true
                result.onSuccess(done).onFailure { showStatus(localizedMessage(it.message));schedule() }
            }
        }.start()
    }
    private fun startPurchase() {
        if(busy)return
        val reference=state.optString("reference")
        if(reference.isNotBlank()) {
            val url=state.optString("checkout_url")
            if(url.isNotBlank())openCheckout(url) else refresh()
            return
        }
        if(state.optString("request_id").isBlank()) {
            state.put("request_id",UUID.randomUUID().toString()).put("months",months)
            ResellerPurchaseStore.write(this,state)
        }
        request({ api("start",payload().put("request_id",state.getString("request_id")).put("months",state.getInt("months"))) }) {
            val ref=it.optString("payment_reference")
            if(ref.isBlank()) { showStatus(getString(R.string.reseller_incomplete));return@request }
            state.put("reference",ref)
            val url=if(it.isNull("checkout_url")) "" else it.optString("checkout_url")
            state.put("checkout_url",url);ResellerPurchaseStore.write(this,state)
            render(it)
            if(it.optString("status")=="pending" && url.isNotBlank())openCheckout(url)
        }
    }
    private fun openCheckout(url:String) {
        if(!com.barkatunnel.app.update.AppUpdateDestination.isValid(url)) {
            showStatus(getString(R.string.reseller_bad_link));return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch(_:Exception) { showStatus(getString(R.string.reseller_browser)) }
    }
    private fun refresh() {
        if(!resumed || busy)return
        val reference=state.optString("reference")
        if(reference.isBlank() && state.optString("request_id").isNotBlank()) {
            // Reuse the persisted request identifier when the first reply was lost.
            startPurchase();return
        }
        request({ if(reference.isBlank())api("account",payload()) else api("status",payload().put("payment_reference",reference)) }) { render(it) }
    }
    private fun render(result:JSONObject) {
        result.optJSONObject("account")?.let { account=it;showCredentials(it) }
        val paymentState=result.optString("status")
        val messageKey = when (paymentState) {
            "pending" -> R.string.reseller_pending
            "creating" -> R.string.reseller_creating
            "paid" -> R.string.reseller_paid
            "failed" -> R.string.reseller_failed
            "error" -> R.string.reseller_error
            else -> null
        }
        val rawMessage = result.optString("message")
        showStatus(if (paymentState == "paid" && rawMessage != "Paiement confirmé.") localizedMessage(rawMessage)
            else messageKey?.let { getString(it) } ?: localizedMessage(rawMessage))
        if (result.has("amount")) status.append("\n" + getString(R.string.reseller_amount,
            java.text.NumberFormat.getIntegerInstance(androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]).format(result.optInt("amount"))))
        if(paymentState in listOf("paid","failed","error")) {
            if(paymentState!="paid" || result.optJSONObject("account")!=null) {
                for(key in listOf("reference","checkout_url","request_id","months"))state.remove(key)
                ResellerPurchaseStore.write(this,state)
            }
        }
        if(state.optString("reference").isNotBlank()) {
            status.append("\n" + getString(R.string.reseller_reference, state.optString("reference")))
            statusCard.visibility = View.VISIBLE
            pay.setText(R.string.reseller_resume)
            schedule()
        } else pay.setText(if(account==null)R.string.subscription_pay else R.string.reseller_renew)
    }
    private fun schedule() {
        if(resumed && state.optString("reference").isNotBlank() && polls++<30) {
            handler.removeCallbacksAndMessages(null);handler.postDelayed({refresh()},3000)
        }
    }
    private fun credentialText(value:JSONObject):String {
        return listOf(
            getString(R.string.reseller_link) to value.optString("panel_url"),
            getString(R.string.reseller_username) to value.optString("username"),
            getString(R.string.reseller_password) to passwordText(value),
            getString(R.string.reseller_expiry) to expiryText(value)
        ).joinToString("\n") { (label, text) -> "$label : $text" }
    }
    private fun restoreAccount() {
        if(busy)return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
        val username=EditText(this).apply { hint=getString(R.string.reseller_username);setSingleLine() }
        val password=EditText(this).apply { hint=getString(R.string.reseller_password);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(username);box.addView(password)
        dialog=AlertDialog.Builder(this).setTitle(R.string.reseller_restore_title).setView(box)
            .setNegativeButton(R.string.reseller_cancel,null).setPositiveButton(R.string.reseller_continue) { _,_ ->
                if(username.text.toString().isBlank() || password.text.length < 12) {
                    showStatus(getString(R.string.reseller_enter_credentials))
                    return@setPositiveButton
                }
                val data=payload().put("username",username.text.toString().trim()).put("password",password.text.toString())
                request({api("restore",data)}) { render(it) }
            }.show()
    }
}
