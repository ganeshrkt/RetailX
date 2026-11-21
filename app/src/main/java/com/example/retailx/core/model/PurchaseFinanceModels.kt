package com.example.retailx.core.model

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class RuntimeMode(val label: String) {
    STANDALONE("Standalone Offline"),
    LAN_HOST("LAN Store Host"),
    LAN_CLIENT("LAN Client")
}

data class StoreSettings(
    val id: String,
    val name: String,
    val tagline: String,
    val gstin: String,
    val stateCode: String,
    val address: String,
    val phone: String,
    val email: String,
    val timezone: String,
    val invoicePrefix: String,
    val returnPrefix: String,
    val purchasePrefix: String,
    val invoiceFooter: String,
    val defaultTaxInclusive: Boolean,
    val allowNegativeStock: Boolean,
    val autoRoundInvoice: Boolean,
    val defaultInvoiceFormat: InvoiceFormat,
    val varianceApprovalThresholdPaise: Long,
    val logoUri: String?,
    val isSetupComplete: Boolean
)

data class Supplier(
    val id: String,
    val name: String,
    val contactPerson: String,
    val mobile: String,
    val email: String,
    val address: String,
    val stateCode: String,
    val gstin: String,
    val notes: String,
    val openingBalancePaise: Long,
    val currentPayablePaise: Long,
    val createdAt: Long
)

enum class PurchaseStatus(val label: String) {
    DRAFT("Draft"),
    RECEIVED("Received"),
    CANCELLED("Cancelled")
}

data class PurchaseItem(
    val id: String,
    val purchaseId: String,
    val productVariantId: String,
    val descriptionSnapshot: String,
    val skuSnapshot: String,
    val hsnSnapshot: String,
    val unitSnapshot: String,
    val qtyScaled: Long,
    val returnedQtyScaled: Long,
    val ratePaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val gstRateBps: Int,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val taxPaise: Long,
    val totalPaise: Long
)

data class PurchasePayment(
    val id: String,
    val purchaseId: String,
    val walletId: String,
    val walletName: String,
    val amountPaise: Long,
    val reference: String,
    val createdAt: Long
)

data class Purchase(
    val id: String,
    val purchaseNo: String,
    val supplierId: String,
    val supplierName: String,
    val supplierInvoiceNo: String,
    val supplierInvoiceDate: Long,
    val isInterState: Boolean,
    val status: PurchaseStatus,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val taxPaise: Long,
    val additionalChargesPaise: Long,
    val roundOffPaise: Long,
    val totalPaise: Long,
    val paidPaise: Long,
    val balancePayablePaise: Long,
    val createdAt: Long,
    val receivedAt: Long?,
    val items: List<PurchaseItem> = emptyList(),
    val payments: List<PurchasePayment> = emptyList()
)

enum class WalletType(val label: String) {
    CASH("Cash Drawer"),
    UPI("UPI Merchant"),
    BANK("Bank Account"),
    CARD_CLEARING("Card Clearing"),
    OTHER("Other")
}

data class Wallet(
    val id: String,
    val name: String,
    val type: WalletType,
    val openingBalancePaise: Long,
    val currentBalancePaise: Long,
    val active: Boolean
)

enum class FinancialDirection(val label: String) {
    IN("Credit / In"),
    OUT("Debit / Out")
}

data class FinancialTransaction(
    val id: String,
    val walletId: String,
    val walletName: String,
    val direction: FinancialDirection,
    val amountPaise: Long,
    val sourceType: String,
    val sourceId: String?,
    val reversalOfId: String?,
    val description: String,
    val reference: String,
    val actorName: String,
    val createdAt: Long
)

data class ExpenseCategory(
    val id: String,
    val name: String
)

data class Expense(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val walletId: String,
    val walletName: String,
    val payee: String,
    val amountPaise: Long,
    val note: String,
    val attachmentUri: String?,
    val actorName: String,
    val createdAt: Long
)

data class CashRegisterShift(
    val id: String,
    val userId: String,
    val userName: String,
    val openedAt: Long,
    val openingCashPaise: Long,
    val closedAt: Long?,
    val expectedCashPaise: Long,
    val countedCashPaise: Long?,
    val variancePaise: Long?,
    val varianceReason: String?,
    val approvedBy: String?,
    val cashSalesPaise: Long = 0L,
    val cashRefundsPaise: Long = 0L,
    val cashExpensesPaise: Long = 0L,
    val cashMovementsNetPaise: Long = 0L
) {
    val isOpen: Boolean get() = closedAt == null
}

data class BackupRecord(
    val id: String,
    val fileName: String,
    val uriString: String,
    val status: String,
    val checksumSha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val message: String,
    val createdAt: Long
)
