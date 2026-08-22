package com.barkatunnel.app.subscription

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.journal.AppLogStore
import com.google.android.material.button.MaterialButton
import java.util.Locale

class ActivationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTIVATION_CODE = "activation_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        findViewById<android.view.View>(R.id.activationBackButton).setOnClickListener { finish() }

        val codeInput = findViewById<EditText>(R.id.activationCodeInput)
        val activateButton = findViewById<MaterialButton>(R.id.activateButton)
        val help = findViewById<TextView>(R.id.activationHelp)

        intent.getStringExtra(EXTRA_ACTIVATION_CODE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { codeInput.setText(it) }

        help.setOnClickListener {
            Toast.makeText(
                this,
                "Après paiement, copie le code reçu puis colle-le ici.",
                Toast.LENGTH_LONG
            ).show()
        }

        activateButton.setOnClickListener {
            val code = codeInput.text.toString()
                .trim()
                .uppercase(Locale.ROOT)

            if (code.isBlank()) {
                Toast.makeText(
                    this,
                    "Entre ton code d’activation.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            codeInput.setText(code)
            activateButton.isEnabled = false
            AppLogStore.add(
                this,
                "Activation • Tentative de validation d’un code d’activation."
            )

            Thread {
                try {
                    val result = BarkaBackendClient(this).redeemActivationCode(code)
                    runOnUiThread {
                        activateButton.isEnabled = true
                        if (result.success) {
                            AppLogStore.add(
                                this,
                                "Activation • Abonnement activé • ${result.access.remainingSeconds}s restantes."
                            )
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            AppLogStore.add(this, "Activation • Code refusé.")
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        activateButton.isEnabled = true
                        Toast.makeText(
                            this,
                            e.message ?: "Validation impossible pour le moment.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }
}
