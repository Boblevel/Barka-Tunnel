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
    private lateinit var content: LinearLayout
    private lateinit var details: TextView
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
            Toast.makeText(this,"Impossible de récupérer le paiement mémorisé. Contactez le support.",Toast.LENGTH_LONG).show()
            finish(); return
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad,pad,pad,pad)
            setBackgroundColor(ContextCompat.getColor(this@ResellerPurchaseActivity,R.color.barka_background))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        SystemBars.apply(this)
        button("RETOUR") { finish() }
        label("Devenir Revendeur",24f)
        label("Choisissez votre durée. Votre sous-panel sera créé après confirmation du paiement.")
        plans = RadioGroup(this)
        for (n in 1..2) {
            plans.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = "$n MOIS — ${if(n==1) "5 000" else "10 000"} XOF\nRenouvelable"
                setPadding(0,20,0,20)
                setOnClickListener { months=n }
            })
        }
        plans.check(plans.getChildAt(0).id)
        content.addView(plans)
        pay = button("PAYER") { startPurchase() }
        button("VÉRIFIER LE PAIEMENT") { polls=0; refresh() }
        status = label("")
        details = label("").apply { setTextIsSelectable(true) }
        button("TOUT COPIER") {
            account?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sous-panel Barka",credentialText(it)))
                Toast.makeText(this,"Identifiants copiés.",Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this,"Les identifiants apparaîtront après confirmation du paiement.",Toast.LENGTH_LONG).show()
        }
        restore = button("J’AI DÉJÀ UN SOUS-PANEL") { restoreAccount() }
    }

    override fun onResume() { super.onResume(); if (!::state.isInitialized || isFinishing) return; resumed=true; polls=0; refresh() }
    override fun onPause() { resumed=false; handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { dialog?.dismiss(); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun label(value:String,size:Float=16f):TextView = TextView(this).apply {
        text=value; textSize=size; setPadding(0,16,0,16)
        setTextColor(ContextCompat.getColor(this@ResellerPurchaseActivity,R.color.barka_text));content.addView(this)
    }
    private fun button(value:String,action:()->Unit):MaterialButton = MaterialButton(this).apply {
        text=value; content.addView(this);setOnClickListener { action() }
    }
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
                result.onSuccess(done).onFailure { status.text=it.message ?: "Connectez-vous à un réseau, puis réessayez.";schedule() }
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
            if(ref.isBlank()) { status.text="Réponse de paiement incomplète.";return@request }
            state.put("reference",ref)
            val url=if(it.isNull("checkout_url")) "" else it.optString("checkout_url")
            state.put("checkout_url",url);ResellerPurchaseStore.write(this,state)
            render(it)
            if(it.optString("status")=="pending" && url.isNotBlank())openCheckout(url)
        }
    }
    private fun openCheckout(url:String) {
        if(!com.barkatunnel.app.update.AppUpdateDestination.isValid(url)) {
            status.text="Lien de paiement invalide.";return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch(_:Exception) { status.text="Ouvrez un navigateur pour continuer le paiement." }
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
        result.optJSONObject("account")?.let { account=it;details.text=credentialText(it) }
        val paymentState=result.optString("status")
        status.text=result.optString("message")
        if (result.has("amount")) status.append("\nMontant : ${result.optInt("amount")} XOF")
        if(paymentState in listOf("paid","failed","error")) {
            if(paymentState!="paid" || result.optJSONObject("account")!=null) {
                for(key in listOf("reference","checkout_url","request_id","months"))state.remove(key)
                ResellerPurchaseStore.write(this,state)
            }
        }
        if(state.optString("reference").isNotBlank()) {
            status.append("\nRéférence : ${state.optString("reference")}")
            pay.text="REPRENDRE LE PAIEMENT"
            schedule()
        } else pay.text=if(account==null)"PAYER" else "RENOUVELER"
    }
    private fun schedule() {
        if(resumed && state.optString("reference").isNotBlank() && polls++<30) {
            handler.removeCallbacksAndMessages(null);handler.postDelayed({refresh()},3000)
        }
    }
    private fun credentialText(value:JSONObject):String {
        val password=if(value.isNull("password"))"Contactez le support pour récupérer votre mot de passe." else value.optString("password")
        return "Lien : ${value.optString("panel_url")}\nIdentifiant : ${value.optString("username")}\nMot de passe : $password\nExpiration : ${value.optString("expires_at")}"
    }
    private fun restoreAccount() {
        if(busy)return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
        val username=EditText(this).apply { hint="Identifiant du sous-panel";setSingleLine() }
        val password=EditText(this).apply { hint="Mot de passe";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(username);box.addView(password)
        dialog=AlertDialog.Builder(this).setTitle("Retrouver mon sous-panel").setView(box)
            .setNegativeButton("Annuler",null).setPositiveButton("CONTINUER") { _,_ ->
                if(username.text.toString().isBlank() || password.text.length < 12) {
                    status.text="Renseignez les identifiants de votre sous-panel."
                    return@setPositiveButton
                }
                val data=payload().put("username",username.text.toString().trim()).put("password",password.text.toString())
                request({api("restore",data)}) { render(it) }
            }.show()
    }
}
