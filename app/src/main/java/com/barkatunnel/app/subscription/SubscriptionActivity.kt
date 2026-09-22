package com.barkatunnel.app.subscription

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.pricing.PricingStore
import com.barkatunnel.app.pricing.PricingLabels
import com.barkatunnel.app.pricing.PricingSync
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.ui.SystemBars
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private var priceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var displayedPrices = PricingStore.defaults
    private var selectedPlanId: String = "24h"
    private var selectedAmount: Int = 300
    private lateinit var payButton: MaterialButton
    private val paymentHandler = Handler(Looper.getMainLooper())
    @Volatile private var checkingPayment = false
    private var paymentPollAttempts = 0
    private var confirmationDialog: AlertDialog? = null
    @Volatile private var paymentPageResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        SystemBars.apply(this)

        findViewById<android.view.View>(R.id.subscriptionBackButton).setOnClickListener { finish() }

        val plansGroup = findViewById<RadioGroup>(R.id.plansGroup)
        payButton = findViewById(R.id.payButton)

        plansGroup.check(R.id.plan24h)

        plansGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.plan24h -> select("24h", displayedPrices.getValue("24h"))
                R.id.plan1w -> select("1w", displayedPrices.getValue("1w"))
                R.id.plan2w -> select("2w", displayedPrices.getValue("2w"))
                R.id.plan1m -> select("1m", displayedPrices.getValue("1m"))
            }
        }

        payButton.setOnClickListener {
            startPayment()
        }
    }

    override fun onResume() {
        super.onResume()
        paymentPageResumed = true
        paymentPollAttempts = 0
        syncPendingPlan()
        priceListener = PricingStore.listen(this) { runOnUiThread { renderPrices() } }
        checkPendingPayment()
    }

    override fun onPause() {
        priceListener?.let { PricingStore.unlisten(this, it) }; priceListener = null
        paymentPageResumed = false
        paymentHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        paymentHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startPayment() {
        PendingPaymentStore.confirmedCode(this)?.let { showActivationCode(it); return }
        if (PendingPaymentStore.reference(this) != null) {
            checkPendingPayment()
            PendingPaymentStore.checkoutUrl(this)?.let { url ->
                if (com.barkatunnel.app.update.AppUpdateDestination.isValid(url)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
            return
        }
        val purchasePlanId = selectedPlanId
        val purchaseAmount = selectedAmount
        payButton.isEnabled = false
        AppLogStore.add(
            this,
            "Paiement • Offre sélectionnée : $selectedPlanId • $selectedAmount XOF"
        )

        Thread {
            try {
                val payment = BarkaBackendClient(this).startPayment(purchasePlanId, purchaseAmount)
                val saved = PendingPaymentStore.save(
                    context = this,
                    reference = payment.paymentReference,
                    planId = purchasePlanId,
                    checkoutUrl = payment.checkoutUrl,
                    amount = payment.amount
                )
                if (!saved) {
                    runOnUiThread {
                        payButton.isEnabled = true
                        val previous = PendingPaymentStore.confirmedCode(this)
                        if (previous != null) showActivationCode(previous)
                        else Toast.makeText(this, R.string.payment_action_retry, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                AppLogStore.add(
                    this,
                    "Paiement • Demande créée • ${payment.amount} ${payment.currency}."
                )

                runOnUiThread {
                    payButton.isEnabled = true
                    syncPendingPlan()
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(payment.checkoutUrl))
                    )
                }
            } catch (e: Exception) {
                AppLogStore.add(this, "Paiement • Échec de création.")
                runOnUiThread {
                    payButton.isEnabled = true
                    renderPrices()
                    if (e.message == "PRICING_CHANGED") {
                        PricingSync.refresh(this)
                        return@runOnUiThread
                    }
                    Toast.makeText(
                        this,
                        e.message ?: getString(R.string.payment_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun checkPendingPayment() {
        if (!paymentPageResumed || isFinishing || isDestroyed) return
        PendingPaymentStore.confirmedCode(this)?.let { showActivationCode(it); return }
        val reference = PendingPaymentStore.reference(this) ?: return
        if (checkingPayment) return
        checkingPayment = true

        Thread {
            try {
                val status = BarkaBackendClient(this).checkPaymentStatus(reference)
                runOnUiThread {
                    if (PendingPaymentStore.reference(this) != reference) return@runOnUiThread
                    when (status.status) {
                        "paid" -> {
                            AppLogStore.add(this, "Paiement • Confirmation reçue.")
                            val code = status.activationCode
                            if (code.isNullOrBlank()) {
                                Toast.makeText(
                                    this,
                                    R.string.payment_code_preparing,
                                    Toast.LENGTH_LONG
                                ).show()
                                schedulePaymentCheck()
                            } else {
                                val saved = PendingPaymentStore.saveConfirmedCode(this, reference, code)
                                if (saved && paymentPageResumed && !isFinishing && !isDestroyed) showActivationCode(code)
                            }
                        }

                        "pending", "creating" -> schedulePaymentCheck()

                        "failed", "error" -> {
                            PendingPaymentStore.clear(this)
                            syncPendingPlan()
                            AppLogStore.add(this, "Paiement • ${status.message}")
                            Toast.makeText(this, status.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (_: Exception) {
                // Le paiement reste mémorisé et la vérification reprend automatiquement.
                schedulePaymentCheck()
            } finally {
                checkingPayment = false
            }
        }.start()
    }

    private fun schedulePaymentCheck() {
        if (!paymentPageResumed || isFinishing || isDestroyed) return
        paymentPollAttempts += 1
        paymentHandler.postDelayed(
            { checkPendingPayment() },
            if (paymentPollAttempts <= MAX_PAYMENT_POLL_ATTEMPTS) PAYMENT_POLL_DELAY_MS else 10_000L
        )
    }

    private fun showActivationCode(code: String) {
        if (isFinishing || isDestroyed || confirmationDialog?.isShowing == true) return
        val planLabel = when (PendingPaymentStore.planId(this)) {
            "24h" -> R.string.subscription_24h
            "1w" -> R.string.subscription_week
            "2w" -> R.string.subscription_two_weeks
            "1m" -> R.string.subscription_month
            else -> R.string.payment_purchased_subscription
        }
        val planId = PendingPaymentStore.planId(this)
        val paidAmount = PendingPaymentStore.amount(this)
        val offerLabel = if (paidAmount != null && planId in displayedPrices)
            PricingLabels.offer(this, planId!!, paidAmount) else getString(planLabel)
        val offer = offerLabel.replace(Regex("\\s+"), " ").trim()
        confirmationDialog = PaymentConfirmationDialog.show(this, code, offer,
            onCopy = {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code Barka Tunnel", code))
                PendingPaymentStore.clear(this)
                syncPendingPlan()
                Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
            },
            onActivate = {
                startActivity(Intent(this, ActivationActivity::class.java)
                    .putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, code))
                PendingPaymentStore.clear(this)
                syncPendingPlan()
            }
        )
    }

    private fun syncPendingPlan() {
        val plans = findViewById<RadioGroup>(R.id.plansGroup)
        val pending = PendingPaymentStore.reference(this) != null
        if (pending) {
            val selected = when (PendingPaymentStore.planId(this)) {
                "24h" -> R.id.plan24h
                "1w" -> R.id.plan1w
                "2w" -> R.id.plan2w
                "1m" -> R.id.plan1m
                else -> plans.checkedRadioButtonId
            }
            plans.check(selected)
        }
        for (index in 0 until plans.childCount) plans.getChildAt(index).isEnabled = !pending
        renderPrices()
    }

    private fun renderPrices() {
        if (!::payButton.isInitialized || !payButton.isEnabled || isDestroyed) return
        val pendingPlan = PendingPaymentStore.planId(this)
        displayedPrices = PricingStore.read(this).prices.toMutableMap().apply {
            if (PendingPaymentStore.reference(this@SubscriptionActivity) != null && pendingPlan in this) {
                put(pendingPlan!!, PendingPaymentStore.amount(this@SubscriptionActivity)
                    ?: PricingStore.defaults.getValue(pendingPlan))
            }
        }
        for ((key, id) in listOf("24h" to R.id.plan24h, "1w" to R.id.plan1w,
            "2w" to R.id.plan2w, "1m" to R.id.plan1m)) {
            findViewById<android.widget.TextView>(id).text = PricingLabels.offer(this, key, displayedPrices.getValue(key))
        }
        selectedAmount = displayedPrices.getValue(selectedPlanId)
    }

    private fun select(planId: String, amount: Int) {
        selectedPlanId = planId
        selectedAmount = amount
    }

    companion object {
        private const val PAYMENT_POLL_DELAY_MS = 2_000L
        private const val MAX_PAYMENT_POLL_ATTEMPTS = 30
    }
}
