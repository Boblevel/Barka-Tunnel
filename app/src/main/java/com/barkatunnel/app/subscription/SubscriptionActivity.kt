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
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.journal.AppLogStore
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private var selectedPlanId: String = "24h"
    private var selectedAmount: Int = 300
    private lateinit var payButton: MaterialButton
    private val paymentHandler = Handler(Looper.getMainLooper())
    @Volatile private var checkingPayment = false
    private var paymentPollAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        findViewById<android.view.View>(R.id.subscriptionBackButton).setOnClickListener { finish() }

        val plansGroup = findViewById<RadioGroup>(R.id.plansGroup)
        payButton = findViewById(R.id.payButton)

        plansGroup.check(R.id.plan24h)

        plansGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.plan24h -> select("24h", 300)
                R.id.plan1w -> select("1w", 800)
                R.id.plan2w -> select("2w", 1000)
                R.id.plan1m -> select("1m", 2000)
            }
        }

        payButton.setOnClickListener {
            startPayment()
        }
    }

    override fun onResume() {
        super.onResume()
        paymentPollAttempts = 0
        checkPendingPayment()
    }

    override fun onPause() {
        paymentHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        paymentHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startPayment() {
        payButton.isEnabled = false
        AppLogStore.add(
            this,
            "Paiement • Offre sélectionnée : $selectedPlanId • $selectedAmount XOF"
        )

        Thread {
            try {
                val payment = BarkaBackendClient(this).startPayment(selectedPlanId)
                PendingPaymentStore.save(
                    context = this,
                    reference = payment.paymentReference,
                    planId = selectedPlanId
                )
                AppLogStore.add(
                    this,
                    "Paiement • Demande créée • ${payment.amount} ${payment.currency}."
                )

                runOnUiThread {
                    payButton.isEnabled = true
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(payment.checkoutUrl))
                    )
                }
            } catch (e: Exception) {
                AppLogStore.add(this, "Paiement • Échec de création.")
                runOnUiThread {
                    payButton.isEnabled = true
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
        val reference = PendingPaymentStore.reference(this) ?: return
        if (checkingPayment) return
        checkingPayment = true

        Thread {
            try {
                val status = BarkaBackendClient(this).checkPaymentStatus(reference)
                runOnUiThread {
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
                                PendingPaymentStore.clear(this)
                                showActivationCode(code)
                            }
                        }

                        "pending", "creating" -> schedulePaymentCheck()

                        "failed", "error" -> {
                            PendingPaymentStore.clear(this)
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
        if (paymentPollAttempts >= MAX_PAYMENT_POLL_ATTEMPTS) return
        paymentPollAttempts += 1
        paymentHandler.postDelayed(
            { checkPendingPayment() },
            PAYMENT_POLL_DELAY_MS
        )
    }

    private fun showActivationCode(code: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.payment_confirmed)
            .setMessage(getString(R.string.payment_code_format, code))
            .setPositiveButton(R.string.payment_activate_now) { _, _ ->
                startActivity(
                    Intent(this, ActivationActivity::class.java)
                        .putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, code)
                )
            }
            .setNeutralButton(R.string.copy_code) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code Barka Tunnel", code))
                Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
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
