package com.barkatunnel.app.subscription

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    @Volatile private var checkingPayment = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

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
        checkPendingPayment()
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
                        e.message ?: "Paiement indisponible pour le moment.",
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
                            PendingPaymentStore.clear(this)
                            AppLogStore.add(this, "Paiement • Confirmation reçue.")
                            val code = status.activationCode
                            if (code.isNullOrBlank()) {
                                Toast.makeText(
                                    this,
                                    "Paiement confirmé. Le code est en préparation.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                showActivationCode(code)
                            }
                        }

                        "failed", "error" -> {
                            PendingPaymentStore.clear(this)
                            AppLogStore.add(this, "Paiement • ${status.message}")
                            Toast.makeText(this, status.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (_: Exception) {
                // Le paiement reste mémorisé : une nouvelle vérification sera faite au prochain retour.
            } finally {
                checkingPayment = false
            }
        }.start()
    }

    private fun showActivationCode(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Paiement confirmé")
            .setMessage("Votre code d’activation :\n\n$code")
            .setPositiveButton("ACTIVER MAINTENANT") { _, _ ->
                startActivity(
                    Intent(this, ActivationActivity::class.java)
                        .putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, code)
                )
            }
            .setNeutralButton("COPIER LE CODE") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code Barka Tunnel", code))
                Toast.makeText(this, "Code copié.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("FERMER", null)
            .show()
    }

    private fun select(planId: String, amount: Int) {
        selectedPlanId = planId
        selectedAmount = amount
    }
}
