package com.barkatunnel.app.subscription

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private var selectedPlanId: String? = null
    private var selectedAmount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        val plansGroup = findViewById<RadioGroup>(R.id.plansGroup)
        val payButton = findViewById<MaterialButton>(R.id.payButton)

        plansGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.plan24h -> select("24h", 300)
                R.id.plan1w -> select("1w", 800)
                R.id.plan2w -> select("2w", 1000)
                R.id.plan1m -> select("1m", 2000)
            }
        }

        payButton.setOnClickListener {
            if (selectedPlanId == null) {
                Toast.makeText(this, "Choisis d’abord un abonnement.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(
                this,
                "Paiement $selectedAmount XOF : connexion serveur au prochain bloc.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun select(planId: String, amount: Int) {
        selectedPlanId = planId
        selectedAmount = amount
    }
}
