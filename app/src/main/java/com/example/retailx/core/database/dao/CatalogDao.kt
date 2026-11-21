package com.example.retailx.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.retailx.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity)

    @Query("SELECT * FROM brands ORDER BY name ASC")
    fun observeBrands(): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brands ORDER BY name ASC")
    suspend fun getAllBrands(): List<BrandEntity>

    @Upsert
    suspend fun upsertBrand(brand: BrandEntity)

    @Query("SELECT * FROM units ORDER BY name ASC")
    fun observeUnits(): Flow<List<UnitEntity>>

    @Query("SELECT * FROM units ORDER BY name ASC")
    suspend fun getAllUnits(): List<UnitEntity>

    @Upsert
    suspend fun insertUnits(units: List<UnitEntity>)

    @Query("SELECT * FROM products ORDER BY name ASC")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getProductById(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE sku = :sku LIMIT 1")
    suspend fun getProductBySku(sku: String): ProductEntity?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun getProductByBarcode(barcode: String): ProductEntity?

    @Upsert
    suspend fun upsertProduct(product: ProductEntity)

    @Query("SELECT * FROM product_variants")
    fun observeAllVariants(): Flow<List<ProductVariantEntity>>

    @Query("SELECT * FROM product_variants")
    suspend fun getAllVariants(): List<ProductVariantEntity>

    @Query("SELECT * FROM product_variants WHERE id = :id LIMIT 1")
    suspend fun getVariantById(id: String): ProductVariantEntity?

    @Query("SELECT * FROM product_variants WHERE product_id = :productId")
    suspend fun getVariantsForProduct(productId: String): List<ProductVariantEntity>

    @Query("SELECT * FROM product_variants WHERE barcode = :code OR sku = :code LIMIT 1")
    suspend fun findVariantByBarcodeOrSku(code: String): ProductVariantEntity?

    @Upsert
    suspend fun upsertVariant(variant: ProductVariantEntity)

    @Update
    suspend fun updateVariant(variant: ProductVariantEntity)

    @Query("SELECT * FROM stock_balances")
    fun observeStockBalances(): Flow<List<StockBalanceEntity>>

    @Query("SELECT * FROM stock_balances")
    suspend fun getAllStockBalances(): List<StockBalanceEntity>

    @Query("SELECT * FROM stock_balances WHERE product_variant_id = :variantId AND location_id = 'MAIN' LIMIT 1")
    suspend fun getStockBalance(variantId: String): StockBalanceEntity?

    @Upsert
    suspend fun upsertStockBalance(balance: StockBalanceEntity)

    @Query("SELECT * FROM stock_movements ORDER BY created_at DESC LIMIT :limit")
    fun observeStockMovements(limit: Int): Flow<List<StockMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStockMovement(movement: StockMovementEntity)
}
