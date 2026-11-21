package com.example.retailx.data.local

import androidx.room.withTransaction
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.CsvCodec
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.*
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

class LocalCatalogRepository(
    private val db: RetailXDatabase
) : CatalogRepository {

    private val catDao = db.catalogDao()
    private val financeDao = db.financeAndAuditDao()

    override fun observeProducts(
        query: String,
        categoryId: String?,
        activeOnly: Boolean
    ): Flow<List<Product>> {
        val baseFlow = combine(
            catDao.observeProducts(),
            catDao.observeAllVariants(),
            catDao.observeStockBalances()
        ) { products, variants, balances ->
            Triple(products, variants, balances)
        }
        val metaFlow = combine(
            catDao.observeCategories(),
            catDao.observeBrands(),
            catDao.observeUnits()
        ) { cats, brands, units ->
            Triple(cats, brands, units)
        }
        return combine(baseFlow, metaFlow) { (products, variants, balances), (cats, brands, units) ->
            val catMap = cats.associateBy { it.id }
            val brandMap = brands.associateBy { it.id }
            val unitMap = units.associateBy { it.id }
            val balanceMap = balances.filter { it.locationId == "MAIN" }.associateBy { it.productVariantId }
            val variantsByProduct = variants.groupBy { it.productId }
            val q = query.trim().lowercase()

            products.asSequence()
                .filter { !activeOnly || it.active }
                .filter { categoryId == null || it.categoryId == categoryId }
                .map { p ->
                    val prodVariants = variantsByProduct[p.id].orEmpty().map { v ->
                        ProductVariant(
                            id = v.id,
                            productId = v.productId,
                            label = v.label,
                            sku = v.sku,
                            barcode = v.barcode,
                            costPricePaise = v.costPricePaise,
                            sellingPricePaise = v.sellingPricePaise,
                            mrpPaise = v.mrpPaise,
                            stockScaled = balanceMap[v.id]?.quantityScaled ?: 0L,
                            active = v.active
                        )
                    }
                    val totalStock = prodVariants.sumOf { it.stockScaled }
                    val defaultVariantId = prodVariants.firstOrNull()?.id ?: "${p.id}_v1"
                    Product(
                        id = p.id,
                        name = p.name,
                        description = p.description,
                        sku = p.sku,
                        barcode = p.barcode,
                        categoryId = p.categoryId,
                        categoryName = p.categoryId?.let { catMap[it]?.name } ?: "Uncategorized",
                        brandId = p.brandId,
                        brandName = p.brandId?.let { brandMap[it]?.name } ?: "Generic",
                        unitId = p.unitId,
                        unitShortName = unitMap[p.unitId]?.shortName ?: "Pcs",
                        hsnCode = p.hsnCode,
                        gstRateBps = p.gstRateBps,
                        taxCategory = TaxCategory.entries.firstOrNull { it.name == p.taxCategory } ?: TaxCategory.TAXABLE,
                        taxInclusive = p.taxInclusive,
                        costPricePaise = p.costPricePaise,
                        sellingPricePaise = p.sellingPricePaise,
                        mrpPaise = p.mrpPaise,
                        reorderLevelScaled = p.reorderLevelScaled,
                        imageUri = p.imageUri,
                        active = p.active,
                        defaultVariantId = defaultVariantId,
                        stockScaled = totalStock,
                        variants = prodVariants
                    )
                }
                .filter { p ->
                    q.isEmpty() ||
                        p.name.lowercase().contains(q) ||
                        p.sku.lowercase().contains(q) ||
                        (p.barcode?.lowercase()?.contains(q) == true) ||
                        p.hsnCode.lowercase().contains(q)
                }
                .toList()
        }
    }

    override fun observeCategories(): Flow<List<Category>> =
        combine(catDao.observeCategories()) { array ->
            array[0].map { Category(it.id, it.name, it.parentId) }
        }

    override fun observeBrands(): Flow<List<Brand>> =
        combine(catDao.observeBrands()) { array ->
            array[0].map { Brand(it.id, it.name) }
        }

    override fun observeUnits(): Flow<List<UnitModel>> =
        combine(catDao.observeUnits()) { array ->
            array[0].map { UnitModel(it.id, it.name, it.shortName) }
        }

    override fun observeStockMovements(limit: Int): Flow<List<StockMovement>> =
        combine(
            catDao.observeStockMovements(limit),
            catDao.observeAllVariants(),
            catDao.observeProducts()
        ) { movements, variants, products ->
            val variantMap = variants.associateBy { it.id }
            val prodMap = products.associateBy { it.id }
            movements.map { m ->
                val v = variantMap[m.productVariantId]
                val p = v?.let { prodMap[it.productId] }
                StockMovement(
                    id = m.id,
                    productVariantId = m.productVariantId,
                    productName = p?.name ?: "Product",
                    sku = v?.sku ?: p?.sku ?: "",
                    movementType = MovementType.entries.firstOrNull { it.name == m.movementType } ?: MovementType.MANUAL_ADJUSTMENT,
                    quantityDeltaScaled = m.quantityDeltaScaled,
                    unitCostPaise = m.unitCostPaise,
                    sourceType = m.sourceType,
                    sourceId = m.sourceId,
                    reason = m.reason,
                    actorName = m.actorName,
                    createdAt = m.createdAt
                )
            }
        }

    override suspend fun findByBarcodeOrSku(code: String): Product? {
        val clean = code.trim()
        if (clean.isEmpty()) return null
        val variant = catDao.findVariantByBarcodeOrSku(clean)
        val prodEntity = if (variant != null) {
            catDao.getProductById(variant.productId)
        } else {
            catDao.getProductByBarcode(clean) ?: catDao.getProductBySku(clean)
        } ?: return null

        val variants = catDao.getVariantsForProduct(prodEntity.id)
        val balances = catDao.getAllStockBalances().associateBy { it.productVariantId }
        val cats = catDao.getAllCategories().associateBy { it.id }
        val brands = catDao.getAllBrands().associateBy { it.id }
        val units = catDao.getAllUnits().associateBy { it.id }

        val mappedVariants = variants.map { v ->
            ProductVariant(
                id = v.id,
                productId = v.productId,
                label = v.label,
                sku = v.sku,
                barcode = v.barcode,
                costPricePaise = v.costPricePaise,
                sellingPricePaise = v.sellingPricePaise,
                mrpPaise = v.mrpPaise,
                stockScaled = balances[v.id]?.quantityScaled ?: 0L,
                active = v.active
            )
        }
        return Product(
            id = prodEntity.id,
            name = prodEntity.name,
            description = prodEntity.description,
            sku = prodEntity.sku,
            barcode = prodEntity.barcode,
            categoryId = prodEntity.categoryId,
            categoryName = prodEntity.categoryId?.let { cats[it]?.name } ?: "Uncategorized",
            brandId = prodEntity.brandId,
            brandName = prodEntity.brandId?.let { brands[it]?.name } ?: "Generic",
            unitId = prodEntity.unitId,
            unitShortName = units[prodEntity.unitId]?.shortName ?: "Pcs",
            hsnCode = prodEntity.hsnCode,
            gstRateBps = prodEntity.gstRateBps,
            taxCategory = TaxCategory.entries.firstOrNull { it.name == prodEntity.taxCategory } ?: TaxCategory.TAXABLE,
            taxInclusive = prodEntity.taxInclusive,
            costPricePaise = prodEntity.costPricePaise,
            sellingPricePaise = prodEntity.sellingPricePaise,
            mrpPaise = prodEntity.mrpPaise,
            reorderLevelScaled = prodEntity.reorderLevelScaled,
            imageUri = prodEntity.imageUri,
            active = prodEntity.active,
            defaultVariantId = mappedVariants.firstOrNull()?.id ?: "${prodEntity.id}_v1",
            stockScaled = mappedVariants.sumOf { it.stockScaled },
            variants = mappedVariants
        )
    }

    override suspend fun upsertProduct(
        id: String?,
        name: String,
        description: String,
        sku: String,
        barcode: String?,
        categoryId: String?,
        brandId: String?,
        unitId: String,
        hsnCode: String,
        gstRateBps: Int,
        taxCategory: TaxCategory,
        taxInclusive: Boolean,
        costPricePaise: Long,
        sellingPricePaise: Long,
        mrpPaise: Long?,
        reorderLevelScaled: Long,
        openingStockScaled: Long,
        imageUri: String?,
        active: Boolean,
        actorUserId: String,
        actorName: String
    ): AppResult<Product> {
        val cleanName = name.trim()
        val cleanSku = sku.trim().uppercase()
        val cleanBarcode = barcode?.trim()?.ifBlank { null }
        if (cleanName.isEmpty() || cleanSku.isEmpty()) {
            return AppResult.Failure(AppError.ValidationError("Product name and SKU are required"))
        }
        val existingBySku = catDao.getProductBySku(cleanSku)
        if (existingBySku != null && existingBySku.id != id) {
            return AppResult.Failure(AppError.DuplicateCode("SKU '$cleanSku' already exists"))
        }
        if (cleanBarcode != null) {
            val existingByBarcode = catDao.getProductByBarcode(cleanBarcode)
            if (existingByBarcode != null && existingByBarcode.id != id) {
                return AppResult.Failure(AppError.DuplicateCode("Barcode '$cleanBarcode' already exists"))
            }
        }

        val prodId = id ?: UUID.randomUUID().toString()
        val isNew = id == null
        val now = System.currentTimeMillis()
        val variantId = "${prodId}_v1"

        db.withTransaction {
            val prev = if (!isNew) catDao.getProductById(prodId) else null
            catDao.upsertProduct(
                ProductEntity(
                    id = prodId,
                    name = cleanName,
                    description = description.trim(),
                    sku = cleanSku,
                    barcode = cleanBarcode,
                    categoryId = categoryId,
                    brandId = brandId,
                    unitId = unitId,
                    hsnCode = hsnCode.trim(),
                    gstRateBps = gstRateBps,
                    taxCategory = taxCategory.name,
                    taxInclusive = taxInclusive,
                    costPricePaise = costPricePaise,
                    sellingPricePaise = sellingPricePaise,
                    mrpPaise = mrpPaise,
                    reorderLevelScaled = reorderLevelScaled,
                    imageUri = imageUri,
                    active = active,
                    createdAt = prev?.createdAt ?: now
                )
            )

            val existingVariants = catDao.getVariantsForProduct(prodId)
            val targetVariantId = existingVariants.firstOrNull()?.id ?: variantId
            catDao.upsertVariant(
                ProductVariantEntity(
                    id = targetVariantId,
                    productId = prodId,
                    label = "Default",
                    sku = cleanSku,
                    barcode = cleanBarcode,
                    attributesJson = "{}",
                    costPricePaise = costPricePaise,
                    sellingPricePaise = sellingPricePaise,
                    mrpPaise = mrpPaise,
                    active = active
                )
            )
            if (isNew) {
                catDao.upsertStockBalance(
                    StockBalanceEntity(
                        productVariantId = targetVariantId,
                        locationId = "MAIN",
                        quantityScaled = openingStockScaled,
                        updatedAt = now
                    )
                )
                if (openingStockScaled != 0L) {
                    catDao.insertStockMovement(
                        StockMovementEntity(
                            id = UUID.randomUUID().toString(),
                            productVariantId = targetVariantId,
                            movementType = MovementType.OPENING.name,
                            quantityDeltaScaled = openingStockScaled,
                            unitCostPaise = costPricePaise,
                            sourceType = "PRODUCT_CREATE",
                            sourceId = prodId,
                            reason = "Opening stock on product creation",
                            actorName = actorName,
                            createdAt = now
                        )
                    )
                }
            }

            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = if (isNew) "PRODUCT_CREATED" else "PRODUCT_UPDATED",
                    entityType = "PRODUCT",
                    entityId = prodId,
                    metadataJson = """{"sku":"$cleanSku","name":"$cleanName"}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(findByBarcodeOrSku(cleanSku)!!)
    }

    override suspend fun createCategory(name: String): AppResult<Category> {
        val clean = name.trim()
        if (clean.isEmpty()) return AppResult.Failure(AppError.ValidationError("Category name required"))
        val cat = CategoryEntity(id = "cat_${UUID.randomUUID().toString().take(8)}", name = clean)
        catDao.upsertCategory(cat)
        return AppResult.Success(Category(cat.id, cat.name, cat.parentId))
    }

    override suspend fun createBrand(name: String): AppResult<Brand> {
        val clean = name.trim()
        if (clean.isEmpty()) return AppResult.Failure(AppError.ValidationError("Brand name required"))
        val brand = BrandEntity(id = "brand_${UUID.randomUUID().toString().take(8)}", name = clean)
        catDao.upsertBrand(brand)
        return AppResult.Success(Brand(brand.id, brand.name))
    }

    override suspend fun adjustStock(
        productVariantId: String,
        deltaScaled: Long,
        movementType: MovementType,
        reason: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (deltaScaled == 0L) return AppResult.Failure(AppError.ValidationError("Adjustment quantity cannot be zero"))
        if (reason.isBlank()) return AppResult.Failure(AppError.ValidationError("Adjustment reason is required"))
        val now = System.currentTimeMillis()
        db.withTransaction {
            val variant = catDao.getVariantById(productVariantId)
                ?: throw IllegalStateException("Variant not found")
            val currentBal = catDao.getStockBalance(productVariantId)?.quantityScaled ?: 0L
            val newBal = currentBal + deltaScaled
            catDao.upsertStockBalance(
                StockBalanceEntity(
                    productVariantId = productVariantId,
                    locationId = "MAIN",
                    quantityScaled = newBal,
                    updatedAt = now
                )
            )
            catDao.insertStockMovement(
                StockMovementEntity(
                    id = UUID.randomUUID().toString(),
                    productVariantId = productVariantId,
                    movementType = movementType.name,
                    quantityDeltaScaled = deltaScaled,
                    unitCostPaise = variant.costPricePaise,
                    sourceType = "MANUAL_ADJUSTMENT",
                    sourceId = null,
                    reason = reason.trim(),
                    actorName = actorName,
                    createdAt = now
                )
            )
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "STOCK_ADJUSTED",
                    entityType = "VARIANT",
                    entityId = productVariantId,
                    metadataJson = """{"deltaScaled":$deltaScaled,"newBal":$newBal,"reason":"${reason.trim()}"}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun importProductsCsv(
        csvText: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Int> {
        val rows = CsvCodec.decode(csvText)
        if (rows.size < 2) {
            return AppResult.Failure(AppError.ValidationError("CSV must include a header row and at least one product row"))
        }
        var imported = 0
        val dataRows = rows.drop(1)
        for ((index, cols) in dataRows.withIndex()) {
            if (cols.size < 6) {
                return AppResult.Failure(AppError.ValidationError("Row ${index + 2} has too few columns (need Name, SKU, Barcode, HSN, GST%, Cost, Price, Stock)"))
            }
            val name = cols[0]
            val sku = cols[1]
            val barcode = cols.getOrNull(2)?.ifBlank { null }
            val hsn = cols.getOrNull(3).orEmpty()
            val gstPct = cols.getOrNull(4)?.toDoubleOrNull() ?: 0.0
            val costPaise = MoneyFormatter.parseRupeesToPaise(cols.getOrNull(5) ?: "0") ?: 0L
            val pricePaise = MoneyFormatter.parseRupeesToPaise(cols.getOrNull(6) ?: "0") ?: 0L
            val stockScaled = QuantityScale.parseScaled(cols.getOrNull(7) ?: "0") ?: 0L

            val res = upsertProduct(
                id = null,
                name = name,
                description = "Imported via CSV",
                sku = sku,
                barcode = barcode,
                categoryId = null,
                brandId = null,
                unitId = "unit_pcs",
                hsnCode = hsn,
                gstRateBps = (gstPct * 100).toInt(),
                taxCategory = if (gstPct == 0.0) TaxCategory.NIL_RATED else TaxCategory.TAXABLE,
                taxInclusive = true,
                costPricePaise = costPaise,
                sellingPricePaise = pricePaise,
                mrpPaise = null,
                reorderLevelScaled = QuantityScale.fromWhole(5),
                openingStockScaled = stockScaled,
                imageUri = null,
                active = true,
                actorUserId = actorUserId,
                actorName = actorName
            )
            if (res is AppResult.Success) {
                imported++
            }
        }
        return AppResult.Success(imported)
    }

    override suspend fun exportProductsCsv(): String {
        val products = catDao.getAllProducts()
        val variants = catDao.getAllVariants().groupBy { it.productId }
        val balances = catDao.getAllStockBalances().associateBy { it.productVariantId }
        val headers = listOf("Name", "SKU", "Barcode", "HSN", "GST_Percent", "Cost_INR", "SellingPrice_INR", "Stock")
        val rows = products.map { p ->
            val stock = variants[p.id].orEmpty().sumOf { balances[it.id]?.quantityScaled ?: 0L }
            listOf(
                p.name,
                p.sku,
                p.barcode.orEmpty(),
                p.hsnCode,
                (p.gstRateBps / 100.0).toString(),
                MoneyFormatter.paiseToPlainRupees(p.costPricePaise),
                MoneyFormatter.paiseToPlainRupees(p.sellingPricePaise),
                QuantityScale.formatScaled(stock)
            )
        }
        return CsvCodec.encode(headers, rows)
    }
}
