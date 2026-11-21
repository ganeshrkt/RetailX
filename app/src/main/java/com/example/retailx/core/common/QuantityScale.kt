package com.example.retailx.core.common

import java.math.BigDecimal
import java.math.RoundingMode

object QuantityScale {
    const val SCALE: Long = 1000L

    fun fromWhole(qty: Int): Long = qty.toLong() * SCALE

    fun parseScaled(input: String): Long? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd < BigDecimal.ZERO) return null
            bd.multiply(BigDecimal(SCALE))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    fun formatScaled(qtyScaled: Long): String {
        val whole = qtyScaled / SCALE
        val frac = kotlin.math.abs(qtyScaled % SCALE)
        return if (frac == 0L) {
            whole.toString()
        } else {
            val bd = BigDecimal(qtyScaled).divide(BigDecimal(SCALE), 3, RoundingMode.HALF_UP)
            bd.stripTrailingZeros().toPlainString()
        }
    }
}
