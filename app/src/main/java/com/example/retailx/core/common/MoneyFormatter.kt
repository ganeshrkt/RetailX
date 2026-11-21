package com.example.retailx.core.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object MoneyFormatter {
    private val indianSymbols = DecimalFormatSymbols(Locale("en", "IN"))
    private val indianGrouping = DecimalFormat("##,##,##0.00", indianSymbols)
    private val indianWholeGrouping = DecimalFormat("##,##,##0", indianSymbols)

    fun formatPaise(paise: Long, includeSymbol: Boolean = true): String {
        val sign = if (paise < 0) "-" else ""
        val absRupees = abs(paise) / 100.0
        val formatted = indianGrouping.format(absRupees)
        return if (includeSymbol) "${sign}₹$formatted" else "$sign$formatted"
    }

    fun formatPaiseCompact(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val absVal = abs(paise)
        return if (absVal % 100L == 0L) {
            "${sign}₹${indianWholeGrouping.format(absVal / 100L)}"
        } else {
            formatPaise(paise, includeSymbol = true)
        }
    }

    fun parseRupeesToPaise(input: String): Long? {
        val trimmed = input.trim().replace(",", "").replace("₹", "")
        if (trimmed.isEmpty()) return null
        return try {
            val bd = BigDecimal(trimmed)
            bd.multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    fun paiseToPlainRupees(paise: Long): String {
        val bd = BigDecimal(paise).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        return bd.stripTrailingZeros().let {
            if (it.scale() <= 0) it.toPlainString() else bd.toPlainString()
        }
    }
}

object IndianRupeesInWords {
    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    fun convert(paise: Long): String {
        val absPaise = abs(paise)
        val rupees = absPaise / 100L
        val remPaise = (absPaise % 100L).toInt()
        val prefix = if (paise < 0) "Minus " else ""
        val rupeePart = if (rupees == 0L) "Zero Rupees" else "${convertWhole(rupees)} Rupees"
        val paisePart = if (remPaise > 0) " and ${convertTwoDigits(remPaise)} Paise" else ""
        return "${prefix}INR $rupeePart$paisePart Only"
    }

    private fun convertWhole(n: Long): String {
        if (n == 0L) return ""
        val parts = mutableListOf<String>()
        var rem = n
        val crores = rem / 10_000_000L
        if (crores > 0) {
            parts += "${convertWhole(crores)} Crore"
            rem %= 10_000_000L
        }
        val lakhs = rem / 100_000L
        if (lakhs > 0) {
            parts += "${convertTwoDigits(lakhs.toInt())} Lakh"
            rem %= 100_000L
        }
        val thousands = rem / 1_000L
        if (thousands > 0) {
            parts += "${convertTwoDigits(thousands.toInt())} Thousand"
            rem %= 1_000L
        }
        val hundreds = rem / 100L
        if (hundreds > 0) {
            parts += "${ones[hundreds.toInt()]} Hundred"
            rem %= 100L
        }
        if (rem > 0) {
            parts += convertTwoDigits(rem.toInt())
        }
        return parts.joinToString(" ")
    }

    private fun convertTwoDigits(n: Int): String {
        return when {
            n < 20 -> ones[n]
            else -> {
                val t = tens[n / 10]
                val o = ones[n % 10]
                if (o.isEmpty()) t else "$t $o"
            }
        }
    }
}
