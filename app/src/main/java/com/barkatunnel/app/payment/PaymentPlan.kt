package com.barkatunnel.app.payment

data class PaymentPlan(
    val id: String,
    val label: String,
    val amountXof: Int,
    val durationSeconds: Long
) {
    companion object {
        val ALL = listOf(
            PaymentPlan("24h", "24 HEURES", 300, 24L * 60L * 60L),
            PaymentPlan("1w", "1 SEMAINE", 800, 7L * 24L * 60L * 60L),
            PaymentPlan("2w", "2 SEMAINES", 1000, 14L * 24L * 60L * 60L),
            PaymentPlan("1m", "1 MOIS", 2000, 30L * 24L * 60L * 60L)
        )
    }
}
