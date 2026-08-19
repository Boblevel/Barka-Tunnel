package com.barkatunnel.app.subscription

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.google.android.material.button.MaterialButton
import java.util.Locale

class ActivationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        val codeInput = findViewById<EditText>(R.id.activationCodeInput)
        val activateButton = findViewById<MaterialButton>(R.id.activateButton)
        val help = findViewById<TextView>(R.id.activationHelp)

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
            AppLogStore.add(
                this,
                "Activation • Tentative de validation d’un code d’activation."
            )

            Toast.makeText(
                this,
                "Code prêt pour la validation serveur.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
