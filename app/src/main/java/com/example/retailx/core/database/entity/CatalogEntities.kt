package com.example.retailx.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null
)

@Entity(tableName = "brands")
data class BrandEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(tableName = "units")
data class UnitEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "short_name") val shortName: String
)

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["sku"], unique = true),
        Index(value = ["barcode"], unique = true),
        Index(value = ["category_id"]),
        Index(value = ["brand_id"])
    ]
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val sku: String,
    val barcode: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "brand_id") val brandId: String?,
    @ColumnInfo(name = "unit_id") val unitId: String,
    @ColumnInfo(name = "hsn_code") val hsnCode: String,
    @ColumnInfo(name = "gst_rate_bps") val gstRateBps: Int,
    @ColumnInfo(name = "tax_category") val taxCategory: String,
    @ColumnInfo(name = "tax_inclusive") val taxInclusive: Boolean,
    @ColumnInfo(name = "cost_price_paise") val costPricePaise: Long,
    @ColumnInfo(name = "selling_price_paise") val sellingPricePaise: Long,
    @ColumnInfo(name = "mrp_paise") val mrpPaise: Long?,
    @ColumnInfo(name = "reorder_level_scaled") val reorderLevelScaled: Long,
    @ColumnInfo(name = "image_uri") val imageUri: String?,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "product_variants",
    foreignKeys = [
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["product_id"]),
        Index(value = ["sku"], unique = true),
        Index(value = ["barcode"], unique = true)
    ]
)
data class ProductVariantEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    val label: String,
    val sku: String,
    val barcode: String?,
    @ColumnInfo(name = "attributes_json") val attributesJson: String,
    @ColumnInfo(name = "cost_price_paise") val costPricePaise: Long,
    @ColumnInfo(name = "selling_price_paise") val sellingPricePaise: Long,
    @ColumnInfo(name = "mrp_paise") val mrpPaise: Long?,
    val active: Boolean
)

@Entity(
    tableName = "stock_balances",
    primaryKeys = ["product_variant_id", "location_id"],
    foreignKeys = [
        ForeignKey(entity = ProductVariantEntity::class, parentColumns = ["id"], childColumns = ["product_variant_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["product_variant_id"])]
)
data class StockBalanceEntity(
    @ColumnInfo(name = "product_variant_id") val productVariantId: String,
    @ColumnInfo(name = "location_id") val locationId: String = "MAIN",
    @ColumnInfo(name = "quantity_scaled") val quantityScaled: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(entity = ProductVariantEntity::class, parentColumns = ["id"], childColumns = ["product_variant_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index(value = ["product_variant_id"]),
        Index(value = ["created_at"])
    ]
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_variant_id") val productVariantId: String,
    @ColumnInfo(name = "movement_type") val movementType: String,
    @ColumnInfo(name = "quantity_delta_scaled") val quantityDeltaScaled: Long,
    @ColumnInfo(name = "unit_cost_paise") val unitCostPaise: Long,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String?,
    val reason: String,
    @ColumnInfo(name = "actor_name") val actorName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
