package com.example.retailx

import com.example.retailx.core.common.CsvCodec
import com.example.retailx.core.common.IndianRupeesInWords
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.model.CartItemInput
import com.example.retailx.core.model.TaxCategory
import com.example.retailx.domain.tax.GstTaxEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GstTaxEngineTest {

    private fun sampleItem(
        lineId: String = "l1",
        qtyScaled: Long = QuantityScale.fromWhole(1),
        unitPricePaise: Long = 11800L, // Rs 118.00
        lineDiscountPaise: Long = 0L,
        gstRateBps: Int = 1800,
        taxCategory: TaxCategory = TaxCategory.TAXABLE,
        taxInclusive: Boolean = true
    ) = CartItemInput(
        lineId = lineId,
        productId = "p1",
        productVariantId = "v1",
        name = "Test Item",
        sku = "SKU1",
        hsnCode = "1001",
        unitShortName = "Pcs",
        qtyScaled = qtyScaled,
        unitPricePaise = unitPricePaise,
        costPricePaise = 7000L,
        lineDiscountPaise = lineDiscountPaise,
        gstRateBps = gstRateBps,
        taxCategory = taxCategory,
        taxInclusive = taxInclusive,
        maxAvailableScaled = QuantityScale.fromWhole(100)
    )

    @Test
    fun `intra-state tax-inclusive splits CGST and SGST evenly`() {
        val result = GstTaxEngine.calculate(
            items = listOf(sampleItem(unitPricePaise = 11800L, gstRateBps = 1800, taxInclusive = true)),
            billDiscountPaise = 0L,
            storeStateCode = "27",
            placeOfSupplyStateCode = "27",
            autoRoundInvoice = false
        )
        assertEquals(false, result.isInterState)
        assertEquals(10000L, result.taxableTotalPaise)
        assertEquals(900L, result.cgstTotalPaise)
        assertEquals(900L, result.sgstTotalPaise)
        assertEquals(0L, result.igstTotalPaise)
        assertEquals(1800L, result.taxTotalPaise)
        assertEquals(11800L, result.grandTotalPaise)
    }

    @Test
    fun `inter-state tax-exclusive charges IGST only`() {
        val result = GstTaxEngine.calculate(
            items = listOf(sampleItem(unitPricePaise = 10000L, gstRateBps = 1800, taxInclusive = false)),
            billDiscountPaise = 0L,
            storeStateCode = "27",
            placeOfSupplyStateCode = "29",
            autoRoundInvoice = false
        )
        assertTrue(result.isInterState)
        assertEquals(10000L, result.taxableTotalPaise)
        assertEquals(0L, result.cgstTotalPaise)
        assertEquals(0L, result.sgstTotalPaise)
        assertEquals(1800L, result.igstTotalPaise)
        assertEquals(11800L, result.grandTotalPaise)
    }

    @Test
    fun `bill discount allocates proportionally and auto-rounds to nearest rupee`() {
        val items = listOf(
            sampleItem(lineId = "a", unitPricePaise = 20000L, gstRateBps = 0, taxCategory = TaxCategory.EXEMPT),
            sampleItem(lineId = "b", unitPricePaise = 10000L, gstRateBps = 0, taxCategory = TaxCategory.EXEMPT)
        )
        val result = GstTaxEngine.calculate(
            items = items,
            billDiscountPaise = 3051L, // leaves 26949 -> rounds to 26900 (-49 paise roundOff)
            storeStateCode = "27",
            placeOfSupplyStateCode = "27",
            autoRoundInvoice = true
        )
        assertEquals(30000L, result.subtotalBeforeDiscountPaise)
        assertEquals(3051L, result.billDiscountPaise)
        assertEquals(-49L, result.roundOffPaise)
        assertEquals(26900L, result.grandTotalPaise)
    }

    @Test
    fun `indian rupees in words and csv roundtrip work cleanly`() {
        assertEquals("INR One Lakh Two Thousand Three Hundred Forty Five Rupees and Fifty Paise Only", IndianRupeesInWords.convert(10234550L))
        val csv = CsvCodec.encode(
            headers = listOf("name", "note"),
            rows = listOf(listOf("Tea, Assam", "Quote \"special\""))
        )
        val decoded = CsvCodec.decode(csv)
        assertEquals(2, decoded.size)
        assertEquals("Tea, Assam", decoded[1][0])
        assertEquals("Quote \"special\"", decoded[1][1])
    }
}
