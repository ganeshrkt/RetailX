package com.example.retailx.core.model

enum class TaxCategory(val label: String) {
    TAXABLE("Taxable GST"),
    EXEMPT("Exempt"),
    NIL_RATED("Nil-Rated (0%)"),
    NON_GST("Non-GST")
}

data class Category(
    val id: String,
    val name: String,
    val parentId: String? = null
)

data class Brand(
    val id: String,
    val name: String
)

data class UnitModel(
    val id: String,
    val name: String,
    val shortName: String
)

data class ProductVariant(
    val id: String,
    val productId: String,
    val label: String,
    val sku: String,
    val barcode: String?,
    val costPricePaise: Long,
    val sellingPricePaise: Long,
    val mrpPaise: Long?,
    val stockScaled: Long,
    val active: Boolean
)

data class Product(
    val id: String,
    val name: String,
    val description: String,
    val sku: String,
    val barcode: String?,
    val categoryId: String?,
    val categoryName: String,
    val brandId: String?,
    val brandName: String,
    val unitId: String,
    val unitShortName: String,
    val hsnCode: String,
    val gstRateBps: Int, // e.g., 1800 = 18.00%, 500 = 5.00%
    val taxCategory: TaxCategory,
    val taxInclusive: Boolean,
    val costPricePaise: Long,
    val sellingPricePaise: Long,
    val mrpPaise: Long?,
    val reorderLevelScaled: Long,
    val imageUri: String?,
    val active: Boolean,
    val defaultVariantId: String,
    val stockScaled: Long,
    val variants: List<ProductVariant> = emptyList()
) {
    val gstPercent: Double get() = gstRateBps / 100.0
    val isLowStock: Boolean get() = stockScaled > 0L && stockScaled <= reorderLevelScaled
    val isOutOfStock: Boolean get() = stockScaled <= 0L
}

enum class MovementType(val label: String) {
    OPENING("Opening Stock"),
    PURCHASE_RECEIPT("Purchase Receipt"),
    SALE("Sale"),
    SALE_VOID("Sale Void Reversal"),
    SALE_RETURN("Sales Return"),
    PURCHASE_RETURN("Purchase Return"),
    MANUAL_ADJUSTMENT("Manual Adjustment"),
    STOCK_COUNT("Stock Reconciliation")
}

data class StockMovement(
    val id: String,
    val productVariantId: String,
    val productName: String,
    val sku: String,
    val movementType: MovementType,
    val quantityDeltaScaled: Long,
    val unitCostPaise: Long,
    val sourceType: String,
    val sourceId: String?,
    val reason: String,
    val actorName: String,
    val createdAt: Long
)
