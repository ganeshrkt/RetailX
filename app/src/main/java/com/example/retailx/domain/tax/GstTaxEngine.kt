package com.example.retailx.domain.tax

import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.model.CartItemInput
import com.example.retailx.core.model.ComputedSaleTotals
import com.example.retailx.core.model.ComputedTaxLine
import com.example.retailx.core.model.TaxCategory
import java.math.BigDecimal
import java.math.RoundingMode

object GstTaxEngine {

    fun calculate(
        items: List<CartItemInput>,
        billDiscountPaise: Long,
        storeStateCode: String,
        placeOfSupplyStateCode: String,
        autoRoundInvoice: Boolean
    ): ComputedSaleTotals {
        val isInterState = storeStateCode.isNotBlank() &&
            placeOfSupplyStateCode.isNotBlank() &&
            storeStateCode != placeOfSupplyStateCode

        if (items.isEmpty()) {
            return ComputedSaleTotals(
                lines = emptyList(),
                subtotalBeforeDiscountPaise = 0L,
                lineDiscountTotalPaise = 0L,
                billDiscountPaise = 0L,
                taxableTotalPaise = 0L,
                cgstTotalPaise = 0L,
                sgstTotalPaise = 0L,
                igstTotalPaise = 0L,
                taxTotalPaise = 0L,
                roundOffPaise = 0L,
                grandTotalPaise = 0L,
                isInterState = isInterState
            )
        }

        val lineGrossAmounts = items.map { item ->
            BigDecimal(item.unitPricePaise)
                .multiply(BigDecimal(item.qtyScaled))
                .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
                .longValueExact()
        }
        val clampedLineDiscounts = items.mapIndexed { idx, item ->
            item.lineDiscountPaise.coerceIn(0L, lineGrossAmounts[idx])
        }
        val afterLineDiscounts = items.indices.map { idx ->
            (lineGrossAmounts[idx] - clampedLineDiscounts[idx]).coerceAtLeast(0L)
        }
        val totalAfterLineDisc = afterLineDiscounts.sum()
        val effectiveBillDiscount = billDiscountPaise.coerceIn(0L, totalAfterLineDisc)

        val billShares = allocateProportional(afterLineDiscounts, effectiveBillDiscount)

        val computedLines = items.mapIndexed { idx, item ->
            val netLine = (afterLineDiscounts[idx] - billShares[idx]).coerceAtLeast(0L)
            val isTaxable = item.taxCategory == TaxCategory.TAXABLE && item.gstRateBps > 0
            val taxablePaise: Long
            val totalTaxPaise: Long
            val totalPaise: Long

            if (!isTaxable) {
                taxablePaise = netLine
                totalTaxPaise = 0L
                totalPaise = netLine
            } else if (item.taxInclusive) {
                val denom = BigDecimal(10_000 + item.gstRateBps)
                taxablePaise = BigDecimal(netLine)
                    .multiply(BigDecimal(10_000))
                    .divide(denom, 0, RoundingMode.HALF_UP)
                    .longValueExact()
                totalTaxPaise = (netLine - taxablePaise).coerceAtLeast(0L)
                totalPaise = netLine
            } else {
                taxablePaise = netLine
                totalTaxPaise = BigDecimal(taxablePaise)
                    .multiply(BigDecimal(item.gstRateBps))
                    .divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP)
                    .longValueExact()
                totalPaise = taxablePaise + totalTaxPaise
            }

            val cgst = if (!isInterState) totalTaxPaise / 2L else 0L
            val sgst = if (!isInterState) totalTaxPaise - cgst else 0L
            val igst = if (isInterState) totalTaxPaise else 0L

            ComputedTaxLine(
                lineId = item.lineId,
                productId = item.productId,
                productVariantId = item.productVariantId,
                description = item.name,
                sku = item.sku,
                hsnCode = item.hsnCode,
                unitShortName = item.unitShortName,
                qtyScaled = item.qtyScaled,
                ratePaise = item.unitPricePaise,
                costPricePaise = item.costPricePaise,
                lineDiscountPaise = clampedLineDiscounts[idx],
                billDiscountSharePaise = billShares[idx],
                taxablePaise = taxablePaise,
                gstRateBps = item.gstRateBps,
                taxCategory = item.taxCategory,
                cgstPaise = cgst,
                sgstPaise = sgst,
                igstPaise = igst,
                totalTaxPaise = totalTaxPaise,
                totalPaise = totalPaise
            )
        }

        val rawGrand = computedLines.sumOf { it.totalPaise }
        val roundedGrand = if (autoRoundInvoice) {
            ((rawGrand + 50L) / 100L) * 100L
        } else {
            rawGrand
        }
        val roundOff = roundedGrand - rawGrand

        return ComputedSaleTotals(
            lines = computedLines,
            subtotalBeforeDiscountPaise = lineGrossAmounts.sum(),
            lineDiscountTotalPaise = clampedLineDiscounts.sum(),
            billDiscountPaise = effectiveBillDiscount,
            taxableTotalPaise = computedLines.sumOf { it.taxablePaise },
            cgstTotalPaise = computedLines.sumOf { it.cgstPaise },
            sgstTotalPaise = computedLines.sumOf { it.sgstPaise },
            igstTotalPaise = computedLines.sumOf { it.igstPaise },
            taxTotalPaise = computedLines.sumOf { it.totalTaxPaise },
            roundOffPaise = roundOff,
            grandTotalPaise = roundedGrand,
            isInterState = isInterState
        )
    }

    private fun allocateProportional(bases: List<Long>, totalToAllocate: Long): LongArray {
        val result = LongArray(bases.size)
        val sum = bases.sum()
        if (sum <= 0L || totalToAllocate <= 0L) return result
        var allocated = 0L
        var maxIdx = 0
        for (i in bases.indices) {
            if (bases[i] > bases[maxIdx]) maxIdx = i
            val share = BigDecimal(totalToAllocate)
                .multiply(BigDecimal(bases[i]))
                .divide(BigDecimal(sum), 0, RoundingMode.DOWN)
                .longValueExact()
            result[i] = share
            allocated += share
        }
        val remainder = totalToAllocate - allocated
        if (remainder != 0L) {
            result[maxIdx] += remainder
        }
        return result
    }
}
