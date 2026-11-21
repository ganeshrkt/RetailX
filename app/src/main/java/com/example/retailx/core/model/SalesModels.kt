package com.example.retailx.core.model

enum class CustomerType(val label: String) {
    RETAIL("Retail"),
    WHOLESALE("Wholesale"),
    BUSINESS("B2B / GST Registered")
}

data class Customer(
    val id: String,
    val name: String,
    val mobile: String,
    val email: String,
    val address: String,
    val stateCode: String,
    val gstin: String,
    val customerType: CustomerType,
    val openingBalancePaise: Long,
    val currentBalancePaise: Long,
    val creditLimitPaise: Long,
    val defaultDiscountBps: Int,
    val isWalkIn: Boolean = false,
    val createdAt: Long = 0L
)

enum class SaleStatus(val label: String) {
    COMPLETED("Completed"),
    PARTIALLY_RETURNED("Partially Returned"),
    RETURNED("Returned"),
    VOIDED("Voided")
}

enum class PaymentMethod(val label: String) {
    CASH("Cash"),
    UPI("UPI"),
    CARD("Card"),
    BANK("Bank Transfer"),
    CREDIT("Store Credit")
}

enum class InvoiceFormat(val label: String, val widthPoints: Int) {
    A4("A4 Tax Invoice", 595),
    THERMAL_80MM("80mm Receipt PDF", 226),
    THERMAL_58MM("58mm Receipt PDF", 164)
}

data class CartItemInput(
    val lineId: String,
    val productId: String,
    val productVariantId: String,
    val name: String,
    val sku: String,
    val hsnCode: String,
    val unitShortName: String,
    val qtyScaled: Long,
    val unitPricePaise: Long,
    val costPricePaise: Long,
    val lineDiscountPaise: Long,
    val gstRateBps: Int,
    val taxCategory: TaxCategory,
    val taxInclusive: Boolean,
    val maxAvailableScaled: Long
)

data class ComputedTaxLine(
    val lineId: String,
    val productId: String,
    val productVariantId: String,
    val description: String,
    val sku: String,
    val hsnCode: String,
    val unitShortName: String,
    val qtyScaled: Long,
    val ratePaise: Long,
    val costPricePaise: Long,
    val lineDiscountPaise: Long,
    val billDiscountSharePaise: Long,
    val taxablePaise: Long,
    val gstRateBps: Int,
    val taxCategory: TaxCategory,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val totalTaxPaise: Long,
    val totalPaise: Long
)

data class ComputedSaleTotals(
    val lines: List<ComputedTaxLine>,
    val subtotalBeforeDiscountPaise: Long,
    val lineDiscountTotalPaise: Long,
    val billDiscountPaise: Long,
    val taxableTotalPaise: Long,
    val cgstTotalPaise: Long,
    val sgstTotalPaise: Long,
    val igstTotalPaise: Long,
    val taxTotalPaise: Long,
    val roundOffPaise: Long,
    val grandTotalPaise: Long,
    val isInterState: Boolean
)

data class SalePayment(
    val id: String,
    val saleId: String,
    val walletId: String?,
    val walletName: String,
    val method: PaymentMethod,
    val amountPaise: Long,
    val reference: String,
    val createdAt: Long
)

data class SaleItem(
    val id: String,
    val saleId: String,
    val productVariantId: String,
    val descriptionSnapshot: String,
    val skuSnapshot: String,
    val hsnSnapshot: String,
    val unitSnapshot: String,
    val qtyScaled: Long,
    val returnedQtyScaled: Long,
    val ratePaise: Long,
    val costPriceSnapshotPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val gstRateBps: Int,
    val taxCategory: TaxCategory,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val taxPaise: Long,
    val totalPaise: Long
) {
    val returnableQtyScaled: Long get() = (qtyScaled - returnedQtyScaled).coerceAtLeast(0L)
}

data class Sale(
    val id: String,
    val invoiceNo: String,
    val customerId: String?,
    val customerName: String,
    val customerGstin: String,
    val placeOfSupplyStateCode: String,
    val isInterState: Boolean,
    val cashierId: String,
    val cashierName: String,
    val status: SaleStatus,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val taxPaise: Long,
    val roundOffPaise: Long,
    val totalPaise: Long,
    val paidPaise: Long,
    val balanceDuePaise: Long,
    val voidReason: String?,
    val createdAt: Long,
    val items: List<SaleItem> = emptyList(),
    val payments: List<SalePayment> = emptyList()
)

data class HeldBill(
    val id: String,
    val label: String,
    val userId: String,
    val userName: String,
    val customerId: String?,
    val customerName: String,
    val placeOfSupplyStateCode: String,
    val billDiscountPaise: Long,
    val items: List<CartItemInput>,
    val totalPreviewPaise: Long,
    val createdAt: Long,
    val updatedAt: Long
)

data class SalesReturnItem(
    val id: String,
    val returnId: String,
    val saleItemId: String,
    val productVariantId: String,
    val description: String,
    val qtyScaled: Long,
    val refundPaise: Long,
    val restock: Boolean
)

data class SalesReturn(
    val id: String,
    val returnNo: String,
    val saleId: String,
    val invoiceNo: String,
    val reason: String,
    val refundMethod: PaymentMethod,
    val totalRefundPaise: Long,
    val actorName: String,
    val createdAt: Long,
    val items: List<SalesReturnItem> = emptyList()
)
