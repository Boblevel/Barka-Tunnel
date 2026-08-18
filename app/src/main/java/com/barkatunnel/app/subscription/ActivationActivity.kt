package com.barkatunnel.app.subscription

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class ActivationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        val codeInput = findViewById<EditText>(R.id.activationCodeInput)
        val activateButton = findViewById<MaterialButton>(R.id.activateButton)

        activateButton.setOnClickListener {
            val code = codeInput.text.toString().trim()

            if (code.isBlank()) {
                Toast.makeText(this, "Entre ton code d’abonnement.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(
                this,
                "Validation serveur du code au prochain bloc.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
