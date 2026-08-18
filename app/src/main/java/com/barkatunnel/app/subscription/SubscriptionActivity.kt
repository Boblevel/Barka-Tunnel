package com.barkatunnel.app.subscription

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private var selectedPlanId: String = "24h"
    private var selectedAmount: Int = 300

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        val plansGroup = findViewById<RadioGroup>(R.id.plansGroup)
        val payButton = findViewById<MaterialButton>(R.id.payButton)

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
            AppLogStore.add(
                this,
                "Paiement",
                "Offre sélectionnée : $selectedPlanId • $selectedAmount XOF"
            )
            Toast.makeText(
                this,
                "Offre sélectionnée : $selectedAmount XOF. Paiement serveur à connecter.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun select(planId: String, amount: Int) {
        selectedPlanId = planId
        selectedAmount = amount
    }
}
