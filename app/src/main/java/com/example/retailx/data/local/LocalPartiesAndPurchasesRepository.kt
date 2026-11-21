package com.example.retailx.data.local

import androidx.room.withTransaction
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.*
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CreatePurchaseCommand
import com.example.retailx.domain.repository.PartiesAndPurchasesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class LocalPartiesAndPurchasesRepository(
    private val db: RetailXDatabase
) : PartiesAndPurchasesRepository {

    private val partyDao = db.purchaseAndPartyDao()
    private val storeDao = db.storeAndAuthDao()
    private val catDao = db.catalogDao()
    private val salesDao = db.salesDao()
    private val financeDao = db.financeAndAuditDao()

    override fun observeCustomers(query: String): Flow<List<Customer>> =
        partyDao.observeCustomers().map { rows ->
            val q = query.trim().lowercase()
            rows.asSequence()
                .filter { c ->
                    q.isEmpty() ||
                        c.name.lowercase().contains(q) ||
                        c.mobile.lowercase().contains(q) ||
                        c.gstin.lowercase().contains(q)
                }
                .map { it.toModel() }
                .toList()
        }

    override fun observeSuppliers(query: String): Flow<List<Supplier>> =
        partyDao.observeSuppliers().map { rows ->
            val q = query.trim().lowercase()
            rows.asSequence()
                .filter { s ->
                    q.isEmpty() ||
                        s.name.lowercase().contains(q) ||
                        s.mobile.lowercase().contains(q) ||
                        s.gstin.lowercase().contains(q)
                }
                .map { it.toModel() }
                .toList()
        }

    override fun observePurchases(): Flow<List<Purchase>> =
        combine(
            partyDao.observePurchases(),
            partyDao.observeAllPurchaseItems(),
            partyDao.observeAllPurchasePayments()
        ) { purchases, items, payments ->
            val itemsMap = items.groupBy { it.purchaseId }
            val payMap = payments.groupBy { it.purchaseId }
            purchases.map { p ->
                Purchase(
                    id = p.id,
                    purchaseNo = p.purchaseNo,
                    supplierId = p.supplierId,
                    supplierName = p.supplierName,
                    supplierInvoiceNo = p.supplierInvoiceNo,
                    supplierInvoiceDate = p.supplierInvoiceDate,
                    isInterState = p.isInterState,
                    status = PurchaseStatus.entries.firstOrNull { it.name == p.status } ?: PurchaseStatus.DRAFT,
                    subtotalPaise = p.subtotalPaise,
                    discountPaise = p.discountPaise,
                    taxablePaise = p.taxablePaise,
                    cgstPaise = p.cgstPaise,
                    sgstPaise = p.sgstPaise,
                    igstPaise = p.igstPaise,
                    taxPaise = p.taxPaise,
                    additionalChargesPaise = p.additionalChargesPaise,
                    roundOffPaise = p.roundOffPaise,
                    totalPaise = p.totalPaise,
                    paidPaise = p.paidPaise,
                    balancePayablePaise = p.balancePayablePaise,
                    createdAt = p.createdAt,
                    receivedAt = p.receivedAt,
                    items = itemsMap[p.id].orEmpty().map {
                        PurchaseItem(
                            id = it.id,
                            purchaseId = it.purchaseId,
                            productVariantId = it.productVariantId,
                            descriptionSnapshot = it.descriptionSnapshot,
                            skuSnapshot = it.skuSnapshot,
                            hsnSnapshot = it.hsnSnapshot,
                            unitSnapshot = it.unitSnapshot,
                            qtyScaled = it.qtyScaled,
                            returnedQtyScaled = it.returnedQtyScaled,
                            ratePaise = it.ratePaise,
                            discountPaise = it.discountPaise,
                            taxablePaise = it.taxablePaise,
                            gstRateBps = it.gstRateBps,
                            cgstPaise = it.cgstPaise,
                            sgstPaise = it.sgstPaise,
                            igstPaise = it.igstPaise,
                            taxPaise = it.taxPaise,
                            totalPaise = it.totalPaise
                        )
                    },
                    payments = payMap[p.id].orEmpty().map {
                        PurchasePayment(
                            id = it.id,
                            purchaseId = it.purchaseId,
                            walletId = it.walletId,
                            walletName = it.walletName,
                            amountPaise = it.amountPaise,
                            reference = it.reference,
                            createdAt = it.createdAt
                        )
                    }
                )
            }
        }

    override suspend fun upsertCustomer(
        customer: Customer,
        actorUserId: String,
        actorName: String
    ): AppResult<Customer> {
        if (customer.name.isBlank()) return AppResult.Failure(AppError.ValidationError("Customer name is required"))
        val now = System.currentTimeMillis()
        val id = customer.id.ifBlank { UUID.randomUUID().toString() }
        val isNew = customer.id.isBlank()
        val saved = customer.copy(
            id = id,
            name = customer.name.trim(),
            mobile = customer.mobile.trim(),
            gstin = customer.gstin.trim().uppercase(),
            currentBalancePaise = if (isNew) customer.openingBalancePaise else customer.currentBalancePaise,
            createdAt = if (isNew) now else customer.createdAt
        )
        db.withTransaction {
            partyDao.upsertCustomer(saved.toEntity())
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = if (isNew) "CUSTOMER_CREATED" else "CUSTOMER_UPDATED",
                    entityType = "CUSTOMER",
                    entityId = id,
                    metadataJson = """{"name":"${saved.name}"}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(saved)
    }

    override suspend fun collectCustomerPayment(
        customerId: String,
        walletId: String,
        amountPaise: Long,
        reference: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (amountPaise <= 0L) return AppResult.Failure(AppError.ValidationError("Amount must be positive"))
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val cust = partyDao.getCustomerById(customerId)
                    ?: throw DomainTxnException(AppError.NotFound("Customer not found"))
                val wallet = financeDao.getWalletById(walletId)
                    ?: throw DomainTxnException(AppError.NotFound("Wallet not found"))
                partyDao.upsertCustomer(
                    cust.copy(currentBalancePaise = (cust.currentBalancePaise - amountPaise).coerceAtLeast(0L))
                )
                financeDao.upsertWallet(
                    wallet.copy(currentBalancePaise = wallet.currentBalancePaise + amountPaise)
                )
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = wallet.id,
                        walletName = wallet.name,
                        direction = FinancialDirection.IN.name,
                        amountPaise = amountPaise,
                        sourceType = "CUSTOMER_RECEIPT",
                        sourceId = customerId,
                        reversalOfId = null,
                        description = "Payment from ${cust.name}",
                        reference = reference,
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
                        action = "CUSTOMER_PAYMENT_COLLECTED",
                        entityType = "CUSTOMER",
                        entityId = customerId,
                        metadataJson = """{"amountPaise":$amountPaise}""",
                        createdAt = now
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    override suspend fun upsertSupplier(
        supplier: Supplier,
        actorUserId: String,
        actorName: String
    ): AppResult<Supplier> {
        if (supplier.name.isBlank()) return AppResult.Failure(AppError.ValidationError("Supplier name is required"))
        val now = System.currentTimeMillis()
        val id = supplier.id.ifBlank { UUID.randomUUID().toString() }
        val isNew = supplier.id.isBlank()
        val saved = supplier.copy(
            id = id,
            name = supplier.name.trim(),
            gstin = supplier.gstin.trim().uppercase(),
            currentPayablePaise = if (isNew) supplier.openingBalancePaise else supplier.currentPayablePaise,
            createdAt = if (isNew) now else supplier.createdAt
        )
        db.withTransaction {
            partyDao.upsertSupplier(saved.toEntity())
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = if (isNew) "SUPPLIER_CREATED" else "SUPPLIER_UPDATED",
                    entityType = "SUPPLIER",
                    entityId = id,
                    metadataJson = """{"name":"${saved.name}"}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(saved)
    }

    override suspend fun createPurchase(command: CreatePurchaseCommand): AppResult<Purchase> {
        if (command.lines.isEmpty()) {
            return AppResult.Failure(AppError.ValidationError("Add at least one purchase item"))
        }
        val now = System.currentTimeMillis()
        return try {
            val purchaseId = db.withTransaction {
                val supplier = partyDao.getSupplierById(command.supplierId)
                    ?: throw DomainTxnException(AppError.NotFound("Supplier not found"))
                val store = storeDao.getStore()
                    ?: throw DomainTxnException(AppError.ValidationError("Store missing"))
                val fy = DateTimeUtils.currentFinancialYear(now, store.timezone)
                val seq = salesDao.getSequence("PURCHASE", fy)
                val nextNum = seq?.nextNumber ?: 1L
                salesDao.upsertSequence(
                    seq?.copy(nextNumber = nextNum + 1L)
                        ?: InvoiceSequenceEntity("PURCHASE_$fy", "PURCHASE", store.purchasePrefix, nextNum + 1L, fy)
                )
                val purNo = "${store.purchasePrefix}-$fy-${nextNum.toString().padStart(6, '0')}"
                val purId = UUID.randomUUID().toString()

                var subtotal = 0L
                var discountTotal = 0L
                var taxableTotal = 0L
                var cgstTotal = 0L
                var sgstTotal = 0L
                var igstTotal = 0L
                var taxTotal = 0L

                val itemEntities = command.lines.map { line ->
                    val gross = BigDecimal(line.ratePaise)
                        .multiply(BigDecimal(line.qtyScaled))
                        .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
                        .longValueExact()
                    val disc = line.discountPaise.coerceIn(0L, gross)
                    val taxable = gross - disc
                    val tax = BigDecimal(taxable)
                        .multiply(BigDecimal(line.gstRateBps))
                        .divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP)
                        .longValueExact()
                    val cgst = if (!command.isInterState) tax / 2L else 0L
                    val sgst = if (!command.isInterState) tax - cgst else 0L
                    val igst = if (command.isInterState) tax else 0L
                    val lineTotal = taxable + tax

                    subtotal += gross
                    discountTotal += disc
                    taxableTotal += taxable
                    cgstTotal += cgst
                    sgstTotal += sgst
                    igstTotal += igst
                    taxTotal += tax

                    PurchaseItemEntity(
                        id = UUID.randomUUID().toString(),
                        purchaseId = purId,
                        productVariantId = line.productVariantId,
                        descriptionSnapshot = line.description,
                        skuSnapshot = line.sku,
                        hsnSnapshot = line.hsnCode,
                        unitSnapshot = line.unitShortName,
                        qtyScaled = line.qtyScaled,
                        returnedQtyScaled = 0L,
                        ratePaise = line.ratePaise,
                        discountPaise = disc,
                        taxablePaise = taxable,
                        gstRateBps = line.gstRateBps,
                        cgstPaise = cgst,
                        sgstPaise = sgst,
                        igstPaise = igst,
                        taxPaise = tax,
                        totalPaise = lineTotal
                    )
                }

                val grandTotal = taxableTotal + taxTotal + command.additionalChargesPaise + command.roundOffPaise
                val status = if (command.receiveImmediately) PurchaseStatus.RECEIVED.name else PurchaseStatus.DRAFT.name
                val purEntity = PurchaseEntity(
                    id = purId,
                    purchaseNo = purNo,
                    supplierId = supplier.id,
                    supplierName = supplier.name,
                    supplierInvoiceNo = command.supplierInvoiceNo.trim(),
                    supplierInvoiceDate = command.supplierInvoiceDate,
                    isInterState = command.isInterState,
                    status = status,
                    subtotalPaise = subtotal,
                    discountPaise = discountTotal,
                    taxablePaise = taxableTotal,
                    cgstPaise = cgstTotal,
                    sgstPaise = sgstTotal,
                    igstPaise = igstTotal,
                    taxPaise = taxTotal,
                    additionalChargesPaise = command.additionalChargesPaise,
                    roundOffPaise = command.roundOffPaise,
                    totalPaise = grandTotal,
                    paidPaise = 0L,
                    balancePayablePaise = grandTotal,
                    createdAt = now,
                    receivedAt = if (command.receiveImmediately) now else null
                )
                partyDao.insertPurchase(purEntity)
                partyDao.insertPurchaseItems(itemEntities)

                if (command.receiveImmediately) {
                    applyPurchaseReceiptInternal(purEntity, itemEntities, supplier, command.actorName, now)
                }

                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = command.actorUserId,
                        username = command.actorName,
                        deviceId = "local",
                        action = "PURCHASE_CREATED",
                        entityType = "PURCHASE",
                        entityId = purId,
                        metadataJson = """{"purchaseNo":"$purNo","received":${command.receiveImmediately}}""",
                        createdAt = now
                    )
                )
                purId
            }
            val p = partyDao.getPurchaseById(purchaseId)!!
            AppResult.Success(
                Purchase(
                    id = p.id,
                    purchaseNo = p.purchaseNo,
                    supplierId = p.supplierId,
                    supplierName = p.supplierName,
                    supplierInvoiceNo = p.supplierInvoiceNo,
                    supplierInvoiceDate = p.supplierInvoiceDate,
                    isInterState = p.isInterState,
                    status = PurchaseStatus.valueOf(p.status),
                    subtotalPaise = p.subtotalPaise,
                    discountPaise = p.discountPaise,
                    taxablePaise = p.taxablePaise,
                    cgstPaise = p.cgstPaise,
                    sgstPaise = p.sgstPaise,
                    igstPaise = p.igstPaise,
                    taxPaise = p.taxPaise,
                    additionalChargesPaise = p.additionalChargesPaise,
                    roundOffPaise = p.roundOffPaise,
                    totalPaise = p.totalPaise,
                    paidPaise = p.paidPaise,
                    balancePayablePaise = p.balancePayablePaise,
                    createdAt = p.createdAt,
                    receivedAt = p.receivedAt
                )
            )
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    override suspend fun receivePurchase(
        purchaseId: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val p = partyDao.getPurchaseById(purchaseId)
                    ?: throw DomainTxnException(AppError.NotFound("Purchase not found"))
                if (p.status != PurchaseStatus.DRAFT.name) {
                    throw DomainTxnException(AppError.Conflict("Purchase is already ${p.status}"))
                }
                val supplier = partyDao.getSupplierById(p.supplierId)
                    ?: throw DomainTxnException(AppError.NotFound("Supplier not found"))
                val items = partyDao.getItemsForPurchase(purchaseId)
                partyDao.updatePurchase(p.copy(status = PurchaseStatus.RECEIVED.name, receivedAt = now))
                applyPurchaseReceiptInternal(p, items, supplier, actorName, now)
                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = actorUserId,
                        username = actorName,
                        deviceId = "local",
                        action = "PURCHASE_RECEIVED",
                        entityType = "PURCHASE",
                        entityId = purchaseId,
                        metadataJson = """{"purchaseNo":"${p.purchaseNo}"}""",
                        createdAt = now
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    private suspend fun applyPurchaseReceiptInternal(
        purchase: PurchaseEntity,
        items: List<PurchaseItemEntity>,
        supplier: SupplierEntity,
        actorName: String,
        now: Long
    ) {
        for (item in items) {
            val variant = catDao.getVariantById(item.productVariantId) ?: continue
            val currentBal = catDao.getStockBalance(item.productVariantId)?.quantityScaled ?: 0L
            val newBal = currentBal + item.qtyScaled

            // Weighted-average cost calculation
            val recvUnitCost = if (item.qtyScaled > 0L) {
                BigDecimal(item.taxablePaise)
                    .multiply(BigDecimal(QuantityScale.SCALE))
                    .divide(BigDecimal(item.qtyScaled), 0, RoundingMode.HALF_UP)
                    .longValueExact()
            } else {
                item.ratePaise
            }
            val newAvgCost = if (newBal > 0L && currentBal > 0L) {
                val oldVal = BigDecimal(variant.costPricePaise).multiply(BigDecimal(currentBal))
                val recvVal = BigDecimal(recvUnitCost).multiply(BigDecimal(item.qtyScaled))
                oldVal.add(recvVal).divide(BigDecimal(newBal), 0, RoundingMode.HALF_UP).longValueExact()
            } else {
                recvUnitCost
            }

            catDao.upsertStockBalance(
                StockBalanceEntity(
                    productVariantId = item.productVariantId,
                    locationId = "MAIN",
                    quantityScaled = newBal,
                    updatedAt = now
                )
            )
            catDao.updateVariant(variant.copy(costPricePaise = newAvgCost))
            catDao.getProductById(variant.productId)?.let { prod ->
                catDao.upsertProduct(prod.copy(costPricePaise = newAvgCost))
            }
            catDao.insertStockMovement(
                StockMovementEntity(
                    id = UUID.randomUUID().toString(),
                    productVariantId = item.productVariantId,
                    movementType = MovementType.PURCHASE_RECEIPT.name,
                    quantityDeltaScaled = item.qtyScaled,
                    unitCostPaise = recvUnitCost,
                    sourceType = "PURCHASE",
                    sourceId = purchase.id,
                    reason = "Received ${purchase.purchaseNo}",
                    actorName = actorName,
                    createdAt = now
                )
            )
        }
        partyDao.upsertSupplier(
            supplier.copy(currentPayablePaise = supplier.currentPayablePaise + purchase.totalPaise)
        )
    }

    override suspend fun paySupplierPurchase(
        purchaseId: String,
        walletId: String,
        amountPaise: Long,
        reference: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (amountPaise <= 0L) return AppResult.Failure(AppError.ValidationError("Amount must be positive"))
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val p = partyDao.getPurchaseById(purchaseId)
                    ?: throw DomainTxnException(AppError.NotFound("Purchase not found"))
                val supplier = partyDao.getSupplierById(p.supplierId)
                    ?: throw DomainTxnException(AppError.NotFound("Supplier not found"))
                val wallet = financeDao.getWalletById(walletId)
                    ?: throw DomainTxnException(AppError.NotFound("Wallet not found"))

                val payAmt = amountPaise.coerceAtMost(p.balancePayablePaise.coerceAtLeast(amountPaise))
                partyDao.insertPurchasePayment(
                    PurchasePaymentEntity(
                        id = UUID.randomUUID().toString(),
                        purchaseId = purchaseId,
                        walletId = wallet.id,
                        walletName = wallet.name,
                        amountPaise = payAmt,
                        reference = reference,
                        createdAt = now
                    )
                )
                partyDao.updatePurchase(
                    p.copy(
                        paidPaise = p.paidPaise + payAmt,
                        balancePayablePaise = (p.balancePayablePaise - payAmt).coerceAtLeast(0L)
                    )
                )
                partyDao.upsertSupplier(
                    supplier.copy(currentPayablePaise = (supplier.currentPayablePaise - payAmt).coerceAtLeast(0L))
                )
                financeDao.upsertWallet(
                    wallet.copy(currentBalancePaise = wallet.currentBalancePaise - payAmt)
                )
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = wallet.id,
                        walletName = wallet.name,
                        direction = FinancialDirection.OUT.name,
                        amountPaise = payAmt,
                        sourceType = "SUPPLIER_PAYMENT",
                        sourceId = purchaseId,
                        reversalOfId = null,
                        description = "Paid ${supplier.name} (${p.purchaseNo})",
                        reference = reference,
                        actorName = actorName,
                        createdAt = now
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    override suspend fun processPurchaseReturn(
        purchaseId: String,
        reason: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val p = partyDao.getPurchaseById(purchaseId)
                    ?: throw DomainTxnException(AppError.NotFound("Purchase not found"))
                if (p.status != PurchaseStatus.RECEIVED.name) {
                    throw DomainTxnException(AppError.ValidationError("Only received purchases can be returned"))
                }
                val supplier = partyDao.getSupplierById(p.supplierId)
                    ?: throw DomainTxnException(AppError.NotFound("Supplier not found"))
                val items = partyDao.getItemsForPurchase(purchaseId)
                for (item in items) {
                    val curBal = catDao.getStockBalance(item.productVariantId)?.quantityScaled ?: 0L
                    catDao.upsertStockBalance(
                        StockBalanceEntity(
                            productVariantId = item.productVariantId,
                            locationId = "MAIN",
                            quantityScaled = curBal - item.qtyScaled,
                            updatedAt = now
                        )
                    )
                    catDao.insertStockMovement(
                        StockMovementEntity(
                            id = UUID.randomUUID().toString(),
                            productVariantId = item.productVariantId,
                            movementType = MovementType.PURCHASE_RETURN.name,
                            quantityDeltaScaled = -item.qtyScaled,
                            unitCostPaise = item.ratePaise,
                            sourceType = "PURCHASE_RETURN",
                            sourceId = purchaseId,
                            reason = "Purchase return ${p.purchaseNo}: ${reason.ifBlank { "Returned to supplier" }}",
                            actorName = actorName,
                            createdAt = now
                        )
                    )
                }
                val retNo = "PRET-${p.purchaseNo.takeLast(6)}"
                partyDao.insertPurchaseReturn(
                    PurchaseReturnEntity(
                        id = UUID.randomUUID().toString(),
                        returnNo = retNo,
                        purchaseId = purchaseId,
                        reason = reason.ifBlank { "Returned to supplier" },
                        totalPaise = p.totalPaise,
                        createdAt = now
                    )
                )
                partyDao.updatePurchase(p.copy(status = PurchaseStatus.CANCELLED.name, balancePayablePaise = 0L))
                partyDao.upsertSupplier(
                    supplier.copy(currentPayablePaise = (supplier.currentPayablePaise - p.balancePayablePaise).coerceAtLeast(0L))
                )
                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = actorUserId,
                        username = actorName,
                        deviceId = "local",
                        action = "PURCHASE_RETURNED",
                        entityType = "PURCHASE",
                        entityId = purchaseId,
                        metadataJson = """{"purchaseNo":"${p.purchaseNo}"}""",
                        createdAt = now
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }
}

private fun CustomerEntity.toModel(): Customer = Customer(
    id = id,
    name = name,
    mobile = mobile,
    email = email,
    address = address,
    stateCode = stateCode,
    gstin = gstin,
    customerType = CustomerType.entries.firstOrNull { it.name == customerType } ?: CustomerType.RETAIL,
    openingBalancePaise = openingBalancePaise,
    currentBalancePaise = currentBalancePaise,
    creditLimitPaise = creditLimitPaise,
    defaultDiscountBps = defaultDiscountBps,
    isWalkIn = isWalkIn,
    createdAt = createdAt
)

private fun Customer.toEntity(): CustomerEntity = CustomerEntity(
    id = id,
    name = name,
    mobile = mobile,
    email = email,
    address = address,
    stateCode = stateCode,
    gstin = gstin,
    customerType = customerType.name,
    openingBalancePaise = openingBalancePaise,
    currentBalancePaise = currentBalancePaise,
    creditLimitPaise = creditLimitPaise,
    defaultDiscountBps = defaultDiscountBps,
    isWalkIn = isWalkIn,
    createdAt = createdAt
)

private fun SupplierEntity.toModel(): Supplier = Supplier(
    id = id,
    name = name,
    contactPerson = contactPerson,
    mobile = mobile,
    email = email,
    address = address,
    stateCode = stateCode,
    gstin = gstin,
    notes = notes,
    openingBalancePaise = openingBalancePaise,
    currentPayablePaise = currentPayablePaise,
    createdAt = createdAt
)

private fun Supplier.toEntity(): SupplierEntity = SupplierEntity(
    id = id,
    name = name,
    contactPerson = contactPerson,
    mobile = mobile,
    email = email,
    address = address,
    stateCode = stateCode,
    gstin = gstin,
    notes = notes,
    openingBalancePaise = openingBalancePaise,
    currentPayablePaise = currentPayablePaise,
    createdAt = createdAt
)
