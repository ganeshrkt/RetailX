package com.example.retailx.data.local

import androidx.room.withTransaction
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.*
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CompleteSaleCommand
import com.example.retailx.domain.repository.ProcessSalesReturnCommand
import com.example.retailx.domain.repository.SalesRepository
import com.example.retailx.domain.tax.GstTaxEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class LocalSalesRepository(
    private val db: RetailXDatabase
) : SalesRepository {

    private val salesDao = db.salesDao()
    private val storeDao = db.storeAndAuthDao()
    private val catDao = db.catalogDao()
    private val partyDao = db.purchaseAndPartyDao()
    private val financeDao = db.financeAndAuditDao()

    override fun observeSales(query: String): Flow<List<Sale>> =
        combine(
            salesDao.observeSales(),
            salesDao.observeAllSaleItems(),
            salesDao.observeAllSalePayments()
        ) { sales, allItems, allPayments ->
            val itemsBySale = allItems.groupBy { it.saleId }
            val paymentsBySale = allPayments.groupBy { it.saleId }
            val q = query.trim().lowercase()
            sales.asSequence()
                .filter { s ->
                    q.isEmpty() ||
                        s.invoiceNo.lowercase().contains(q) ||
                        s.customerName.lowercase().contains(q) ||
                        s.cashierName.lowercase().contains(q)
                }
                .map { s ->
                    s.toModel(
                        items = itemsBySale[s.id].orEmpty().map { it.toModel() },
                        payments = paymentsBySale[s.id].orEmpty().map { it.toModel() }
                    )
                }
                .toList()
        }

    override fun observeHeldBills(): Flow<List<HeldBill>> =
        salesDao.observeHeldBills().map { rows ->
            rows.map { row ->
                HeldBill(
                    id = row.id,
                    label = row.label,
                    userId = row.userId,
                    userName = row.userName,
                    customerId = row.customerId,
                    customerName = row.customerName,
                    placeOfSupplyStateCode = row.placeOfSupplyStateCode,
                    billDiscountPaise = row.billDiscountPaise,
                    items = decodeCartItems(row.cartJson),
                    totalPreviewPaise = row.totalPreviewPaise,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt
                )
            }
        }

    override fun observeSalesReturns(): Flow<List<SalesReturn>> =
        combine(
            salesDao.observeSalesReturns(),
            salesDao.observeAllSalesReturnItems()
        ) { returns, items ->
            val byRet = items.groupBy { it.returnId }
            returns.map { r ->
                SalesReturn(
                    id = r.id,
                    returnNo = r.returnNo,
                    saleId = r.saleId,
                    invoiceNo = r.invoiceNo,
                    reason = r.reason,
                    refundMethod = PaymentMethod.entries.firstOrNull { it.name == r.refundMethod } ?: PaymentMethod.CASH,
                    totalRefundPaise = r.totalRefundPaise,
                    actorName = r.actorName,
                    createdAt = r.createdAt,
                    items = byRet[r.id].orEmpty().map {
                        SalesReturnItem(
                            id = it.id,
                            returnId = it.returnId,
                            saleItemId = it.saleItemId,
                            productVariantId = it.productVariantId,
                            description = it.description,
                            qtyScaled = it.qtyScaled,
                            refundPaise = it.refundPaise,
                            restock = it.restock
                        )
                    }
                )
            }
        }

    override suspend fun getSaleById(saleId: String): Sale? {
        val s = salesDao.getSaleById(saleId) ?: return null
        val items = salesDao.getItemsForSale(saleId).map { it.toModel() }
        val payments = salesDao.getPaymentsForSale(saleId).map { it.toModel() }
        return s.toModel(items, payments)
    }

    override suspend fun completeSale(command: CompleteSaleCommand): AppResult<Sale> {
        if (command.items.isEmpty()) {
            return AppResult.Failure(AppError.ValidationError("Cart cannot be empty"))
        }
        val now = System.currentTimeMillis()
        return try {
            val committedSaleId = db.withTransaction {
                val existingIdem = salesDao.getIdempotencyRecord(command.idempotencyKey)
                if (existingIdem != null) {
                    return@withTransaction existingIdem.resultEntityId
                }

                val store = storeDao.getStore()
                    ?: throw DomainTxnException(AppError.ValidationError("Store is not initialized"))

                val totals = GstTaxEngine.calculate(
                    items = command.items,
                    billDiscountPaise = command.billDiscountPaise,
                    storeStateCode = store.stateCode,
                    placeOfSupplyStateCode = command.placeOfSupplyStateCode.ifBlank { store.stateCode },
                    autoRoundInvoice = store.autoRoundInvoice
                )

                // Check stock policy inside transaction
                if (!store.allowNegativeStock) {
                    for (line in command.items) {
                        val bal = catDao.getStockBalance(line.productVariantId)?.quantityScaled ?: 0L
                        if (bal < line.qtyScaled) {
                            throw DomainTxnException(
                                AppError.InsufficientStock("Insufficient stock for ${line.name}")
                            )
                        }
                    }
                }

                val totalPaidNonCredit = command.payments
                    .filter { it.method != PaymentMethod.CREDIT }
                    .sumOf { it.amountPaise }
                val totalCreditAllocated = command.payments
                    .filter { it.method == PaymentMethod.CREDIT }
                    .sumOf { it.amountPaise }

                val totalTendered = totalPaidNonCredit + totalCreditAllocated
                if (totalTendered < totals.grandTotalPaise) {
                    throw DomainTxnException(
                        AppError.ValidationError("Payment allocation (${totalTendered}) is less than grand total (${totals.grandTotalPaise})")
                    )
                }

                val customer = command.customerId?.let { partyDao.getCustomerById(it) }
                if (totalCreditAllocated > 0L) {
                    if (customer == null || customer.isWalkIn) {
                        throw DomainTxnException(
                            AppError.ValidationError("Select a registered customer for credit billing")
                        )
                    }
                    if (customer.creditLimitPaise > 0L &&
                        customer.currentBalancePaise + totalCreditAllocated > customer.creditLimitPaise
                    ) {
                        throw DomainTxnException(
                            AppError.ValidationError("Customer credit limit exceeded for ${customer.name}")
                        )
                    }
                }

                val fy = DateTimeUtils.currentFinancialYear(now, store.timezone)
                val invoiceNo = allocateNextDocNo("SALE", store.invoicePrefix, fy)
                val saleId = UUID.randomUUID().toString()

                val effectivePaid = (totals.grandTotalPaise - totalCreditAllocated).coerceAtLeast(0L)
                val saleEntity = SaleEntity(
                    id = saleId,
                    invoiceNo = invoiceNo,
                    customerId = customer?.id,
                    customerName = customer?.name ?: "Walk-in Customer",
                    customerGstin = customer?.gstin.orEmpty(),
                    placeOfSupplyStateCode = command.placeOfSupplyStateCode.ifBlank { store.stateCode },
                    isInterState = totals.isInterState,
                    cashierId = command.actorUserId,
                    cashierName = command.actorName,
                    status = SaleStatus.COMPLETED.name,
                    subtotalPaise = totals.subtotalBeforeDiscountPaise,
                    discountPaise = totals.lineDiscountTotalPaise + totals.billDiscountPaise,
                    taxablePaise = totals.taxableTotalPaise,
                    cgstPaise = totals.cgstTotalPaise,
                    sgstPaise = totals.sgstTotalPaise,
                    igstPaise = totals.igstTotalPaise,
                    taxPaise = totals.taxTotalPaise,
                    roundOffPaise = totals.roundOffPaise,
                    totalPaise = totals.grandTotalPaise,
                    paidPaise = effectivePaid,
                    balanceDuePaise = totalCreditAllocated,
                    voidReason = null,
                    createdAt = now
                )
                salesDao.insertSale(saleEntity)

                val itemEntities = totals.lines.map { l ->
                    SaleItemEntity(
                        id = UUID.randomUUID().toString(),
                        saleId = saleId,
                        productVariantId = l.productVariantId,
                        descriptionSnapshot = l.description,
                        skuSnapshot = l.sku,
                        hsnSnapshot = l.hsnCode,
                        unitSnapshot = l.unitShortName,
                        qtyScaled = l.qtyScaled,
                        returnedQtyScaled = 0L,
                        ratePaise = l.ratePaise,
                        costPriceSnapshotPaise = l.costPricePaise,
                        discountPaise = l.lineDiscountPaise + l.billDiscountSharePaise,
                        taxablePaise = l.taxablePaise,
                        gstRateBps = l.gstRateBps,
                        taxCategory = l.taxCategory.name,
                        cgstPaise = l.cgstPaise,
                        sgstPaise = l.sgstPaise,
                        igstPaise = l.igstPaise,
                        taxPaise = l.totalTaxPaise,
                        totalPaise = l.totalPaise
                    )
                }
                salesDao.insertSaleItems(itemEntities)

                // Allocate payments (cap net cash received to invoice total minus credit)
                var remainingCashCap = effectivePaid
                val paymentEntities = mutableListOf<SalePaymentEntity>()
                for (p in command.payments) {
                    if (p.amountPaise <= 0L) continue
                    if (p.method == PaymentMethod.CREDIT) {
                        paymentEntities.add(
                            SalePaymentEntity(
                                id = UUID.randomUUID().toString(),
                                saleId = saleId,
                                walletId = null,
                                walletName = "Customer Credit",
                                method = PaymentMethod.CREDIT.name,
                                amountPaise = p.amountPaise,
                                reference = p.reference,
                                createdAt = now
                            )
                        )
                    } else {
                        val appliedAmount = p.amountPaise.coerceAtMost(remainingCashCap)
                        if (appliedAmount <= 0L) continue
                        remainingCashCap -= appliedAmount
                        val wallet = resolveWallet(p.walletId, p.method)
                        paymentEntities.add(
                            SalePaymentEntity(
                                id = UUID.randomUUID().toString(),
                                saleId = saleId,
                                walletId = wallet?.id,
                                walletName = wallet?.name ?: p.method.label,
                                method = p.method.name,
                                amountPaise = appliedAmount,
                                reference = p.reference,
                                createdAt = now
                            )
                        )
                        if (wallet != null) {
                            financeDao.upsertWallet(
                                wallet.copy(currentBalancePaise = wallet.currentBalancePaise + appliedAmount)
                            )
                            financeDao.insertFinancialTransaction(
                                FinancialTransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    walletId = wallet.id,
                                    walletName = wallet.name,
                                    direction = FinancialDirection.IN.name,
                                    amountPaise = appliedAmount,
                                    sourceType = "SALE",
                                    sourceId = saleId,
                                    reversalOfId = null,
                                    description = "Sale $invoiceNo (${p.method.label})",
                                    reference = p.reference,
                                    actorName = command.actorName,
                                    createdAt = now
                                )
                            )
                        }
                    }
                }
                if (paymentEntities.isNotEmpty()) {
                    salesDao.insertSalePayments(paymentEntities)
                }

                // Update customer credit receivable if applicable
                if (customer != null && totalCreditAllocated > 0L) {
                    partyDao.upsertCustomer(
                        customer.copy(currentBalancePaise = customer.currentBalancePaise + totalCreditAllocated)
                    )
                }

                // Deduct stock and append immutable stock movements
                for (line in totals.lines) {
                    val curBal = catDao.getStockBalance(line.productVariantId)?.quantityScaled ?: 0L
                    catDao.upsertStockBalance(
                        StockBalanceEntity(
                            productVariantId = line.productVariantId,
                            locationId = "MAIN",
                            quantityScaled = curBal - line.qtyScaled,
                            updatedAt = now
                        )
                    )
                    catDao.insertStockMovement(
                        StockMovementEntity(
                            id = UUID.randomUUID().toString(),
                            productVariantId = line.productVariantId,
                            movementType = MovementType.SALE.name,
                            quantityDeltaScaled = -line.qtyScaled,
                            unitCostPaise = line.costPricePaise,
                            sourceType = "SALE",
                            sourceId = saleId,
                            reason = "Sale $invoiceNo",
                            actorName = command.actorName,
                            createdAt = now
                        )
                    )
                }

                if (command.heldBillIdToDelete != null) {
                    salesDao.deleteHeldBill(command.heldBillIdToDelete)
                }

                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = command.actorUserId,
                        username = command.actorName,
                        deviceId = "local",
                        action = "SALE_COMPLETED",
                        entityType = "SALE",
                        entityId = saleId,
                        metadataJson = """{"invoiceNo":"$invoiceNo","totalPaise":${totals.grandTotalPaise}}""",
                        createdAt = now
                    )
                )

                salesDao.insertIdempotencyRecord(
                    IdempotencyRecordEntity(
                        id = UUID.randomUUID().toString(),
                        idempotencyKey = command.idempotencyKey,
                        operationType = "SALE_COMPLETE",
                        resultEntityId = saleId,
                        createdAt = now
                    )
                )

                saleId
            }

            AppResult.Success(getSaleById(committedSaleId)!!)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        } catch (ex: Exception) {
            AppResult.Failure(AppError.Unexpected(ex.message ?: "Sale failed and rolled back"))
        }
    }

    override suspend fun saveHeldBill(
        id: String?,
        label: String,
        customerId: String?,
        customerName: String,
        placeOfSupplyStateCode: String,
        billDiscountPaise: Long,
        items: List<CartItemInput>,
        totalPreviewPaise: Long,
        userId: String,
        userName: String
    ): AppResult<HeldBill> {
        if (items.isEmpty()) return AppResult.Failure(AppError.ValidationError("Cannot hold an empty cart"))
        val now = System.currentTimeMillis()
        val billId = id ?: UUID.randomUUID().toString()
        val cleanLabel = label.trim().ifBlank { "Held Bill" }
        val entity = HeldBillEntity(
            id = billId,
            label = cleanLabel,
            userId = userId,
            userName = userName,
            customerId = customerId,
            customerName = customerName,
            placeOfSupplyStateCode = placeOfSupplyStateCode,
            billDiscountPaise = billDiscountPaise,
            cartJson = encodeCartItems(items),
            totalPreviewPaise = totalPreviewPaise,
            createdAt = now,
            updatedAt = now
        )
        salesDao.upsertHeldBill(entity)
        return AppResult.Success(
            HeldBill(
                id = billId,
                label = cleanLabel,
                userId = userId,
                userName = userName,
                customerId = customerId,
                customerName = customerName,
                placeOfSupplyStateCode = placeOfSupplyStateCode,
                billDiscountPaise = billDiscountPaise,
                items = items,
                totalPreviewPaise = totalPreviewPaise,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun deleteHeldBill(id: String) {
        salesDao.deleteHeldBill(id)
    }

    override suspend fun processSalesReturn(command: ProcessSalesReturnCommand): AppResult<SalesReturn> {
        val requestedLines = command.lines.filter { it.qtyScaled > 0L }
        if (requestedLines.isEmpty()) {
            return AppResult.Failure(AppError.ValidationError("Select at least one item quantity to return"))
        }
        val now = System.currentTimeMillis()
        return try {
            val retId = db.withTransaction {
                val existingIdem = salesDao.getIdempotencyRecord(command.idempotencyKey)
                if (existingIdem != null) {
                    return@withTransaction existingIdem.resultEntityId
                }

                val sale = salesDao.getSaleById(command.saleId)
                    ?: throw DomainTxnException(AppError.NotFound("Sale not found"))
                if (sale.status == SaleStatus.VOIDED.name) {
                    throw DomainTxnException(AppError.ValidationError("Cannot return a voided sale"))
                }
                val store = storeDao.getStore()
                    ?: throw DomainTxnException(AppError.ValidationError("Store missing"))

                val saleItems = salesDao.getItemsForSale(sale.id).associateBy { it.id }
                val retIdNew = UUID.randomUUID().toString()
                var totalRefund = 0L
                val returnItemEntities = mutableListOf<SalesReturnItemEntity>()

                for (line in requestedLines) {
                    val sItem = saleItems[line.saleItemId]
                        ?: throw DomainTxnException(AppError.NotFound("Sale line not found"))
                    val remaining = sItem.qtyScaled - sItem.returnedQtyScaled
                    if (line.qtyScaled > remaining) {
                        throw DomainTxnException(
                            AppError.ValidationError("Return quantity exceeds remaining returnable qty for ${sItem.descriptionSnapshot}")
                        )
                    }
                    val lineRefund = BigDecimal(sItem.totalPaise)
                        .multiply(BigDecimal(line.qtyScaled))
                        .divide(BigDecimal(sItem.qtyScaled), 0, RoundingMode.HALF_UP)
                        .longValueExact()
                    totalRefund += lineRefund

                    salesDao.updateSaleItem(
                        sItem.copy(returnedQtyScaled = sItem.returnedQtyScaled + line.qtyScaled)
                    )

                    returnItemEntities.add(
                        SalesReturnItemEntity(
                            id = UUID.randomUUID().toString(),
                            returnId = retIdNew,
                            saleItemId = sItem.id,
                            productVariantId = sItem.productVariantId,
                            description = sItem.descriptionSnapshot,
                            qtyScaled = line.qtyScaled,
                            refundPaise = lineRefund,
                            restock = line.restock
                        )
                    )

                    if (line.restock) {
                        val curBal = catDao.getStockBalance(sItem.productVariantId)?.quantityScaled ?: 0L
                        catDao.upsertStockBalance(
                            StockBalanceEntity(
                                productVariantId = sItem.productVariantId,
                                locationId = "MAIN",
                                quantityScaled = curBal + line.qtyScaled,
                                updatedAt = now
                            )
                        )
                        catDao.insertStockMovement(
                            StockMovementEntity(
                                id = UUID.randomUUID().toString(),
                                productVariantId = sItem.productVariantId,
                                movementType = MovementType.SALE_RETURN.name,
                                quantityDeltaScaled = line.qtyScaled,
                                unitCostPaise = sItem.costPriceSnapshotPaise,
                                sourceType = "SALES_RETURN",
                                sourceId = retIdNew,
                                reason = "Return for ${sale.invoiceNo}: ${command.reason.ifBlank { "Customer return" }}",
                                actorName = command.actorName,
                                createdAt = now
                            )
                        )
                    }
                }

                val fy = DateTimeUtils.currentFinancialYear(now, store.timezone)
                val returnNo = allocateNextDocNo("RETURN", store.returnPrefix, fy)
                salesDao.insertSalesReturn(
                    SalesReturnEntity(
                        id = retIdNew,
                        returnNo = returnNo,
                        saleId = sale.id,
                        invoiceNo = sale.invoiceNo,
                        reason = command.reason.trim().ifBlank { "Customer return" },
                        refundMethod = command.refundMethod.name,
                        totalRefundPaise = totalRefund,
                        actorName = command.actorName,
                        createdAt = now
                    )
                )
                salesDao.insertSalesReturnItems(returnItemEntities)

                // Recompute sale status
                val updatedItems = salesDao.getItemsForSale(sale.id)
                val allReturned = updatedItems.all { it.returnedQtyScaled >= it.qtyScaled }
                val newStatus = if (allReturned) SaleStatus.RETURNED.name else SaleStatus.PARTIALLY_RETURNED.name
                salesDao.updateSale(sale.copy(status = newStatus))

                // Financial refund posting
                if (command.refundMethod == PaymentMethod.CREDIT && sale.customerId != null) {
                    val cust = partyDao.getCustomerById(sale.customerId)
                    if (cust != null) {
                        partyDao.upsertCustomer(
                            cust.copy(currentBalancePaise = (cust.currentBalancePaise - totalRefund).coerceAtLeast(0L))
                        )
                    }
                } else {
                    val wallet = resolveWallet(command.refundWalletId, command.refundMethod)
                    if (wallet != null) {
                        financeDao.upsertWallet(
                            wallet.copy(currentBalancePaise = wallet.currentBalancePaise - totalRefund)
                        )
                        financeDao.insertFinancialTransaction(
                            FinancialTransactionEntity(
                                id = UUID.randomUUID().toString(),
                                walletId = wallet.id,
                                walletName = wallet.name,
                                direction = FinancialDirection.OUT.name,
                                amountPaise = totalRefund,
                                sourceType = "SALES_RETURN",
                                sourceId = retIdNew,
                                reversalOfId = null,
                                description = "Refund $returnNo against ${sale.invoiceNo}",
                                reference = returnNo,
                                actorName = command.actorName,
                                createdAt = now
                            )
                        )
                    }
                }

                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = command.actorUserId,
                        username = command.actorName,
                        deviceId = "local",
                        action = "SALES_RETURN",
                        entityType = "SALES_RETURN",
                        entityId = retIdNew,
                        metadataJson = """{"returnNo":"$returnNo","invoiceNo":"${sale.invoiceNo}","refundPaise":$totalRefund}""",
                        createdAt = now
                    )
                )

                salesDao.insertIdempotencyRecord(
                    IdempotencyRecordEntity(
                        id = UUID.randomUUID().toString(),
                        idempotencyKey = command.idempotencyKey,
                        operationType = "SALES_RETURN",
                        resultEntityId = retIdNew,
                        createdAt = now
                    )
                )

                retIdNew
            }

            // Return freshly created model
            val retEntity = salesDao.getSalesReturnsInRange(0L, Long.MAX_VALUE).first { it.id == retId }
            AppResult.Success(
                SalesReturn(
                    id = retEntity.id,
                    returnNo = retEntity.returnNo,
                    saleId = retEntity.saleId,
                    invoiceNo = retEntity.invoiceNo,
                    reason = retEntity.reason,
                    refundMethod = PaymentMethod.entries.firstOrNull { it.name == retEntity.refundMethod } ?: PaymentMethod.CASH,
                    totalRefundPaise = retEntity.totalRefundPaise,
                    actorName = retEntity.actorName,
                    createdAt = retEntity.createdAt
                )
            )
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        } catch (ex: Exception) {
            AppResult.Failure(AppError.Unexpected(ex.message ?: "Return failed"))
        }
    }

    override suspend fun voidSale(
        saleId: String,
        reason: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (reason.isBlank()) return AppResult.Failure(AppError.ValidationError("Void reason is required"))
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val sale = salesDao.getSaleById(saleId)
                    ?: throw DomainTxnException(AppError.NotFound("Sale not found"))
                if (sale.status == SaleStatus.VOIDED.name) {
                    throw DomainTxnException(AppError.Conflict("Sale is already voided"))
                }
                if (sale.status == SaleStatus.PARTIALLY_RETURNED.name || sale.status == SaleStatus.RETURNED.name) {
                    throw DomainTxnException(AppError.ValidationError("Cannot void a sale that already has returns"))
                }

                val items = salesDao.getItemsForSale(saleId)
                for (item in items) {
                    val curBal = catDao.getStockBalance(item.productVariantId)?.quantityScaled ?: 0L
                    catDao.upsertStockBalance(
                        StockBalanceEntity(
                            productVariantId = item.productVariantId,
                            locationId = "MAIN",
                            quantityScaled = curBal + item.qtyScaled,
                            updatedAt = now
                        )
                    )
                    catDao.insertStockMovement(
                        StockMovementEntity(
                            id = UUID.randomUUID().toString(),
                            productVariantId = item.productVariantId,
                            movementType = MovementType.SALE_VOID.name,
                            quantityDeltaScaled = item.qtyScaled,
                            unitCostPaise = item.costPriceSnapshotPaise,
                            sourceType = "SALE_VOID",
                            sourceId = saleId,
                            reason = "Void ${sale.invoiceNo}: ${reason.trim()}",
                            actorName = actorName,
                            createdAt = now
                        )
                    )
                }

                val payments = salesDao.getPaymentsForSale(saleId)
                for (p in payments) {
                    if (p.method == PaymentMethod.CREDIT.name && sale.customerId != null) {
                        val cust = partyDao.getCustomerById(sale.customerId)
                        if (cust != null) {
                            partyDao.upsertCustomer(
                                cust.copy(currentBalancePaise = (cust.currentBalancePaise - p.amountPaise).coerceAtLeast(0L))
                            )
                        }
                    } else if (p.walletId != null) {
                        val wallet = financeDao.getWalletById(p.walletId)
                        if (wallet != null) {
                            financeDao.upsertWallet(
                                wallet.copy(currentBalancePaise = wallet.currentBalancePaise - p.amountPaise)
                            )
                            financeDao.insertFinancialTransaction(
                                FinancialTransactionEntity(
                                    id = UUID.randomUUID().toString(),
                                    walletId = wallet.id,
                                    walletName = wallet.name,
                                    direction = FinancialDirection.OUT.name,
                                    amountPaise = p.amountPaise,
                                    sourceType = "SALE_VOID",
                                    sourceId = saleId,
                                    reversalOfId = p.id,
                                    description = "Reversal for voided ${sale.invoiceNo}",
                                    reference = sale.invoiceNo,
                                    actorName = actorName,
                                    createdAt = now
                                )
                            )
                        }
                    }
                }

                salesDao.updateSale(
                    sale.copy(status = SaleStatus.VOIDED.name, voidReason = reason.trim())
                )
                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = actorUserId,
                        username = actorName,
                        deviceId = "local",
                        action = "SALE_VOIDED",
                        entityType = "SALE",
                        entityId = saleId,
                        metadataJson = """{"invoiceNo":"${sale.invoiceNo}","reason":"${reason.trim()}"}""",
                        createdAt = now
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        } catch (ex: Exception) {
            AppResult.Failure(AppError.Unexpected(ex.message ?: "Void failed"))
        }
    }

    private suspend fun allocateNextDocNo(docType: String, prefix: String, fy: String): String {
        val current = salesDao.getSequence(docType, fy)
        val nextNum = current?.nextNumber ?: 1L
        val seqEntity = current?.copy(nextNumber = nextNum + 1L)
            ?: InvoiceSequenceEntity(
                id = "${docType}_$fy",
                documentType = docType,
                prefix = prefix,
                nextNumber = nextNum + 1L,
                financialYear = fy
            )
        salesDao.upsertSequence(seqEntity)
        return "$prefix-$fy-${nextNum.toString().padStart(6, '0')}"
    }

    private suspend fun resolveWallet(walletId: String?, method: PaymentMethod): WalletEntity? {
        if (walletId != null) {
            financeDao.getWalletById(walletId)?.let { return it }
        }
        val targetType = when (method) {
            PaymentMethod.CASH -> WalletType.CASH.name
            PaymentMethod.UPI -> WalletType.UPI.name
            PaymentMethod.CARD -> WalletType.CARD_CLEARING.name
            PaymentMethod.BANK -> WalletType.BANK.name
            PaymentMethod.CREDIT -> return null
        }
        return financeDao.getFirstWalletByType(targetType)
            ?: financeDao.getAllWallets().firstOrNull { it.active }
    }

    private fun encodeCartItems(items: List<CartItemInput>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("lineId", item.lineId)
                    put("productId", item.productId)
                    put("productVariantId", item.productVariantId)
                    put("name", item.name)
                    put("sku", item.sku)
                    put("hsnCode", item.hsnCode)
                    put("unitShortName", item.unitShortName)
                    put("qtyScaled", item.qtyScaled)
                    put("unitPricePaise", item.unitPricePaise)
                    put("costPricePaise", item.costPricePaise)
                    put("lineDiscountPaise", item.lineDiscountPaise)
                    put("gstRateBps", item.gstRateBps)
                    put("taxCategory", item.taxCategory.name)
                    put("taxInclusive", item.taxInclusive)
                    put("maxAvailableScaled", item.maxAvailableScaled)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeCartItems(json: String): List<CartItemInput> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                CartItemInput(
                    lineId = o.getString("lineId"),
                    productId = o.getString("productId"),
                    productVariantId = o.getString("productVariantId"),
                    name = o.getString("name"),
                    sku = o.getString("sku"),
                    hsnCode = o.optString("hsnCode"),
                    unitShortName = o.optString("unitShortName", "Pcs"),
                    qtyScaled = o.getLong("qtyScaled"),
                    unitPricePaise = o.getLong("unitPricePaise"),
                    costPricePaise = o.optLong("costPricePaise", 0L),
                    lineDiscountPaise = o.optLong("lineDiscountPaise", 0L),
                    gstRateBps = o.optInt("gstRateBps", 0),
                    taxCategory = TaxCategory.entries.firstOrNull { it.name == o.optString("taxCategory") } ?: TaxCategory.TAXABLE,
                    taxInclusive = o.optBoolean("taxInclusive", true),
                    maxAvailableScaled = o.optLong("maxAvailableScaled", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class DomainTxnException(val error: AppError) : RuntimeException(error.userMessage)

private fun SaleEntity.toModel(items: List<SaleItem>, payments: List<SalePayment>): Sale = Sale(
    id = id,
    invoiceNo = invoiceNo,
    customerId = customerId,
    customerName = customerName,
    customerGstin = customerGstin,
    placeOfSupplyStateCode = placeOfSupplyStateCode,
    isInterState = isInterState,
    cashierId = cashierId,
    cashierName = cashierName,
    status = SaleStatus.entries.firstOrNull { it.name == status } ?: SaleStatus.COMPLETED,
    subtotalPaise = subtotalPaise,
    discountPaise = discountPaise,
    taxablePaise = taxablePaise,
    cgstPaise = cgstPaise,
    sgstPaise = sgstPaise,
    igstPaise = igstPaise,
    taxPaise = taxPaise,
    roundOffPaise = roundOffPaise,
    totalPaise = totalPaise,
    paidPaise = paidPaise,
    balanceDuePaise = balanceDuePaise,
    voidReason = voidReason,
    createdAt = createdAt,
    items = items,
    payments = payments
)

private fun SaleItemEntity.toModel(): SaleItem = SaleItem(
    id = id,
    saleId = saleId,
    productVariantId = productVariantId,
    descriptionSnapshot = descriptionSnapshot,
    skuSnapshot = skuSnapshot,
    hsnSnapshot = hsnSnapshot,
    unitSnapshot = unitSnapshot,
    qtyScaled = qtyScaled,
    returnedQtyScaled = returnedQtyScaled,
    ratePaise = ratePaise,
    costPriceSnapshotPaise = costPriceSnapshotPaise,
    discountPaise = discountPaise,
    taxablePaise = taxablePaise,
    gstRateBps = gstRateBps,
    taxCategory = TaxCategory.entries.firstOrNull { it.name == taxCategory } ?: TaxCategory.TAXABLE,
    cgstPaise = cgstPaise,
    sgstPaise = sgstPaise,
    igstPaise = igstPaise,
    taxPaise = taxPaise,
    totalPaise = totalPaise
)

private fun SalePaymentEntity.toModel(): SalePayment = SalePayment(
    id = id,
    saleId = saleId,
    walletId = walletId,
    walletName = walletName,
    method = PaymentMethod.entries.firstOrNull { it.name == method } ?: PaymentMethod.CASH,
    amountPaise = amountPaise,
    reference = reference,
    createdAt = createdAt
)
