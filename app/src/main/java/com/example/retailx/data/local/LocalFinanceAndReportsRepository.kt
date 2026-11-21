package com.example.retailx.data.local

import androidx.room.withTransaction
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.*
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class LocalFinanceAndReportsRepository(
    private val db: RetailXDatabase
) : FinanceAndReportsRepository {

    private val financeDao = db.financeAndAuditDao()
    private val salesDao = db.salesDao()
    private val catDao = db.catalogDao()
    private val storeDao = db.storeAndAuthDao()
    private val partyDao = db.purchaseAndPartyDao()

    override fun observeWallets(): Flow<List<Wallet>> =
        financeDao.observeWallets().map { rows ->
            rows.map {
                Wallet(
                    id = it.id,
                    name = it.name,
                    type = WalletType.entries.firstOrNull { wt -> wt.name == it.type } ?: WalletType.OTHER,
                    openingBalancePaise = it.openingBalancePaise,
                    currentBalancePaise = it.currentBalancePaise,
                    active = it.active
                )
            }
        }

    override fun observeFinancialTransactions(limit: Int): Flow<List<FinancialTransaction>> =
        financeDao.observeFinancialTransactions(limit).map { rows ->
            rows.map {
                FinancialTransaction(
                    id = it.id,
                    walletId = it.walletId,
                    walletName = it.walletName,
                    direction = FinancialDirection.entries.firstOrNull { d -> d.name == it.direction } ?: FinancialDirection.IN,
                    amountPaise = it.amountPaise,
                    sourceType = it.sourceType,
                    sourceId = it.sourceId,
                    reversalOfId = it.reversalOfId,
                    description = it.description,
                    reference = it.reference,
                    actorName = it.actorName,
                    createdAt = it.createdAt
                )
            }
        }

    override fun observeExpenseCategories(): Flow<List<ExpenseCategory>> =
        financeDao.observeExpenseCategories().map { rows ->
            rows.map { ExpenseCategory(it.id, it.name) }
        }

    override fun observeExpenses(limit: Int): Flow<List<Expense>> =
        financeDao.observeExpenses(limit).map { rows ->
            rows.map {
                Expense(
                    id = it.id,
                    categoryId = it.categoryId,
                    categoryName = it.categoryName,
                    walletId = it.walletId,
                    walletName = it.walletName,
                    payee = it.payee,
                    amountPaise = it.amountPaise,
                    note = it.note,
                    attachmentUri = it.attachmentUri,
                    actorName = it.actorName,
                    createdAt = it.createdAt
                )
            }
        }

    override fun observeCashRegisterShifts(): Flow<List<CashRegisterShift>> =
        financeDao.observeCashRegisters().map { rows -> rows.map { it.toModel() } }

    override fun observeOpenShift(): Flow<CashRegisterShift?> =
        financeDao.observeOpenCashRegister().map { shift ->
            shift?.let { computeShiftFigures(it) }
        }

    override suspend fun createWallet(
        name: String,
        type: WalletType,
        openingBalancePaise: Long,
        actorUserId: String,
        actorName: String
    ): AppResult<Wallet> {
        val clean = name.trim()
        if (clean.isEmpty()) return AppResult.Failure(AppError.ValidationError("Wallet name required"))
        val id = "wallet_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        db.withTransaction {
            financeDao.upsertWallet(
                WalletEntity(
                    id = id,
                    name = clean,
                    type = type.name,
                    openingBalancePaise = openingBalancePaise,
                    currentBalancePaise = openingBalancePaise,
                    active = true
                )
            )
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "WALLET_CREATED",
                    entityType = "WALLET",
                    entityId = id,
                    metadataJson = """{"name":"$clean","openingPaise":$openingBalancePaise}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(Wallet(id, clean, type, openingBalancePaise, openingBalancePaise, true))
    }

    override suspend fun transferBetweenWallets(
        fromWalletId: String,
        toWalletId: String,
        amountPaise: Long,
        note: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (fromWalletId == toWalletId) return AppResult.Failure(AppError.ValidationError("Choose two different wallets"))
        if (amountPaise <= 0L) return AppResult.Failure(AppError.ValidationError("Transfer amount must be positive"))
        val now = System.currentTimeMillis()
        val transferId = UUID.randomUUID().toString()
        return try {
            db.withTransaction {
                val fromW = financeDao.getWalletById(fromWalletId)
                    ?: throw DomainTxnException(AppError.NotFound("Source wallet not found"))
                val toW = financeDao.getWalletById(toWalletId)
                    ?: throw DomainTxnException(AppError.NotFound("Destination wallet not found"))
                financeDao.upsertWallet(fromW.copy(currentBalancePaise = fromW.currentBalancePaise - amountPaise))
                financeDao.upsertWallet(toW.copy(currentBalancePaise = toW.currentBalancePaise + amountPaise))
                val desc = note.trim().ifBlank { "Transfer ${fromW.name} -> ${toW.name}" }
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = fromW.id,
                        walletName = fromW.name,
                        direction = FinancialDirection.OUT.name,
                        amountPaise = amountPaise,
                        sourceType = "WALLET_TRANSFER",
                        sourceId = transferId,
                        reversalOfId = null,
                        description = desc,
                        reference = transferId.take(8),
                        actorName = actorName,
                        createdAt = now
                    )
                )
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = toW.id,
                        walletName = toW.name,
                        direction = FinancialDirection.IN.name,
                        amountPaise = amountPaise,
                        sourceType = "WALLET_TRANSFER",
                        sourceId = transferId,
                        reversalOfId = null,
                        description = desc,
                        reference = transferId.take(8),
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

    override suspend fun recordCashMovement(
        walletId: String,
        direction: FinancialDirection,
        amountPaise: Long,
        note: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (amountPaise <= 0L) return AppResult.Failure(AppError.ValidationError("Amount must be positive"))
        if (note.isBlank()) return AppResult.Failure(AppError.ValidationError("Note is required"))
        val now = System.currentTimeMillis()
        return try {
            db.withTransaction {
                val w = financeDao.getWalletById(walletId)
                    ?: throw DomainTxnException(AppError.NotFound("Wallet not found"))
                val delta = if (direction == FinancialDirection.IN) amountPaise else -amountPaise
                financeDao.upsertWallet(w.copy(currentBalancePaise = w.currentBalancePaise + delta))
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = w.id,
                        walletName = w.name,
                        direction = direction.name,
                        amountPaise = amountPaise,
                        sourceType = if (direction == FinancialDirection.IN) "CASH_DEPOSIT" else "CASH_WITHDRAWAL",
                        sourceId = null,
                        reversalOfId = null,
                        description = note.trim(),
                        reference = "",
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

    override suspend fun recordExpense(
        categoryId: String,
        walletId: String,
        payee: String,
        amountPaise: Long,
        note: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Expense> {
        if (amountPaise <= 0L) return AppResult.Failure(AppError.ValidationError("Expense amount must be positive"))
        val now = System.currentTimeMillis()
        return try {
            val entity = db.withTransaction {
                val cat = financeDao.getExpenseCategoryById(categoryId)
                    ?: throw DomainTxnException(AppError.NotFound("Expense category not found"))
                val wallet = financeDao.getWalletById(walletId)
                    ?: throw DomainTxnException(AppError.NotFound("Wallet not found"))
                val expId = UUID.randomUUID().toString()
                val exp = ExpenseEntity(
                    id = expId,
                    categoryId = cat.id,
                    categoryName = cat.name,
                    walletId = wallet.id,
                    walletName = wallet.name,
                    payee = payee.trim().ifBlank { "General Payee" },
                    amountPaise = amountPaise,
                    note = note.trim(),
                    attachmentUri = null,
                    actorName = actorName,
                    createdAt = now
                )
                financeDao.insertExpense(exp)
                financeDao.upsertWallet(wallet.copy(currentBalancePaise = wallet.currentBalancePaise - amountPaise))
                financeDao.insertFinancialTransaction(
                    FinancialTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        walletId = wallet.id,
                        walletName = wallet.name,
                        direction = FinancialDirection.OUT.name,
                        amountPaise = amountPaise,
                        sourceType = "EXPENSE",
                        sourceId = expId,
                        reversalOfId = null,
                        description = "Expense: ${cat.name} - ${exp.payee}",
                        reference = "",
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
                        action = "EXPENSE_RECORDED",
                        entityType = "EXPENSE",
                        entityId = expId,
                        metadataJson = """{"category":"${cat.name}","amountPaise":$amountPaise}""",
                        createdAt = now
                    )
                )
                exp
            }
            AppResult.Success(
                Expense(
                    id = entity.id,
                    categoryId = entity.categoryId,
                    categoryName = entity.categoryName,
                    walletId = entity.walletId,
                    walletName = entity.walletName,
                    payee = entity.payee,
                    amountPaise = entity.amountPaise,
                    note = entity.note,
                    attachmentUri = entity.attachmentUri,
                    actorName = entity.actorName,
                    createdAt = entity.createdAt
                )
            )
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    override suspend fun openCashRegister(
        openingCashPaise: Long,
        userId: String,
        userName: String
    ): AppResult<CashRegisterShift> {
        if (openingCashPaise < 0L) return AppResult.Failure(AppError.ValidationError("Opening cash cannot be negative"))
        val existing = financeDao.getOpenCashRegister()
        if (existing != null) {
            return AppResult.Failure(AppError.Conflict("A cash register shift is already open (${existing.userName})"))
        }
        val now = System.currentTimeMillis()
        val shift = CashRegisterEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            userName = userName,
            openedAt = now,
            openingCashPaise = openingCashPaise,
            closedAt = null,
            expectedCashPaise = openingCashPaise,
            countedCashPaise = null,
            variancePaise = null,
            varianceReason = null,
            approvedBy = null
        )
        db.withTransaction {
            financeDao.insertCashRegister(shift)
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    username = userName,
                    deviceId = "local",
                    action = "REGISTER_OPENED",
                    entityType = "CASH_REGISTER",
                    entityId = shift.id,
                    metadataJson = """{"openingCashPaise":$openingCashPaise}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(shift.toModel())
    }

    override suspend fun closeCashRegister(
        shiftId: String,
        countedCashPaise: Long,
        varianceReason: String?,
        approvedBy: String?,
        actorUserId: String,
        actorName: String
    ): AppResult<CashRegisterShift> {
        val now = System.currentTimeMillis()
        return try {
            val closed = db.withTransaction {
                val shift = financeDao.getCashRegisterById(shiftId)
                    ?: throw DomainTxnException(AppError.NotFound("Shift not found"))
                if (shift.closedAt != null) {
                    throw DomainTxnException(AppError.Conflict("Shift is already closed"))
                }
                val computed = computeShiftFigures(shift, now)
                val variance = countedCashPaise - computed.expectedCashPaise
                val store = storeDao.getStore()
                val threshold = store?.varianceApprovalThresholdPaise ?: 50_000L
                if (kotlin.math.abs(variance) > threshold && varianceReason.isNullOrBlank()) {
                    throw DomainTxnException(
                        AppError.ValidationError("Variance exceeds threshold; provide explanation")
                    )
                }
                val updated = shift.copy(
                    closedAt = now,
                    expectedCashPaise = computed.expectedCashPaise,
                    countedCashPaise = countedCashPaise,
                    variancePaise = variance,
                    varianceReason = varianceReason?.trim()?.ifBlank { null },
                    approvedBy = approvedBy
                )
                financeDao.updateCashRegister(updated)
                financeDao.insertActivityLog(
                    ActivityLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = actorUserId,
                        username = actorName,
                        deviceId = "local",
                        action = "REGISTER_CLOSED",
                        entityType = "CASH_REGISTER",
                        entityId = shiftId,
                        metadataJson = """{"expected":${computed.expectedCashPaise},"counted":$countedCashPaise,"variance":$variance}""",
                        createdAt = now
                    )
                )
                computed.copy(
                    closedAt = now,
                    countedCashPaise = countedCashPaise,
                    variancePaise = variance,
                    varianceReason = updated.varianceReason,
                    approvedBy = approvedBy
                )
            }
            AppResult.Success(closed)
        } catch (ex: DomainTxnException) {
            AppResult.Failure(ex.error)
        }
    }

    private suspend fun computeShiftFigures(shift: CashRegisterEntity, endMillis: Long = System.currentTimeMillis()): CashRegisterShift {
        val cashWalletIds = financeDao.getAllWallets()
            .filter { it.type == WalletType.CASH.name }
            .map { it.id }
            .toSet()
        val txs = financeDao.getTransactionsInRange(shift.openedAt, endMillis)
            .filter { it.walletId in cashWalletIds }

        val cashSales = txs.filter { it.sourceType == "SALE" && it.direction == FinancialDirection.IN.name }.sumOf { it.amountPaise }
        val cashRefunds = txs.filter {
            (it.sourceType == "SALES_RETURN" || it.sourceType == "SALE_VOID") && it.direction == FinancialDirection.OUT.name
        }.sumOf { it.amountPaise }
        val cashExpenses = txs.filter { it.sourceType == "EXPENSE" && it.direction == FinancialDirection.OUT.name }.sumOf { it.amountPaise }
        val cashMovementsIn = txs.filter {
            (it.sourceType == "CASH_DEPOSIT" || it.sourceType == "WALLET_TRANSFER") && it.direction == FinancialDirection.IN.name
        }.sumOf { it.amountPaise }
        val cashMovementsOut = txs.filter {
            (it.sourceType == "CASH_WITHDRAWAL" || it.sourceType == "WALLET_TRANSFER") && it.direction == FinancialDirection.OUT.name
        }.sumOf { it.amountPaise }
        val netMovements = cashMovementsIn - cashMovementsOut
        val expected = shift.openingCashPaise + cashSales - cashRefunds - cashExpenses + netMovements

        return shift.toModel().copy(
            expectedCashPaise = expected,
            cashSalesPaise = cashSales,
            cashRefundsPaise = cashRefunds,
            cashExpensesPaise = cashExpenses,
            cashMovementsNetPaise = netMovements
        )
    }

    override suspend fun getDashboardSummary(): DashboardSummary {
        val store = storeDao.getStore()
        val tz = store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE
        val (todayStart, todayEnd) = DateTimeUtils.todayRange(tz)
        val todayBundle = getReportsBundle(todayStart, todayEnd)

        val products = catDao.getAllProducts().filter { it.active }
        val variants = catDao.getAllVariants().groupBy { it.productId }
        val balances = catDao.getAllStockBalances().associateBy { it.productVariantId }

        var lowStock = 0
        var outStock = 0
        var stockValue = 0L
        for (p in products) {
            val pVars = variants[p.id].orEmpty()
            val qty = pVars.sumOf { balances[it.id]?.quantityScaled ?: 0L }
            if (qty <= 0L) outStock++ else if (qty <= p.reorderLevelScaled) lowStock++
            if (qty > 0L) {
                stockValue += BigDecimal(p.costPricePaise)
                    .multiply(BigDecimal(qty))
                    .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
                    .longValueExact()
            }
        }

        val zone = DateTimeUtils.zoneOrDefault(tz)
        val todayDate = LocalDate.now(zone)
        val dayFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
        val trend = (6L downTo 0L).map { offset ->
            val d = todayDate.minusDays(offset)
            val s = DateTimeUtils.dayStartMillis(d, tz)
            val e = DateTimeUtils.dayEndMillis(d, tz)
            val b = getReportsBundle(s, e)
            DailyTrendPoint(
                label = d.format(dayFmt),
                salesPaise = b.netSalesPaise,
                profitPaise = b.estimatedProfitPaise
            )
        }

        return DashboardSummary(
            todayGrossSalesPaise = todayBundle.grossSalesPaise,
            todayNetSalesPaise = todayBundle.netSalesPaise,
            todayDiscountsPaise = todayBundle.discountsPaise,
            todayReturnsPaise = todayBundle.returnsPaise,
            todayBillCount = todayBundle.billCount,
            todayEstimatedProfitPaise = todayBundle.estimatedProfitPaise,
            todayExpensesPaise = todayBundle.expensesPaise,
            lowStockCount = lowStock,
            outOfStockCount = outStock,
            totalStockValuePaise = stockValue,
            dailyTrend = trend,
            paymentBreakdown = todayBundle.paymentBreakdown,
            topProducts = todayBundle.topProducts
        )
    }

    override suspend fun getReportsBundle(fromMillis: Long, toMillis: Long): ReportsBundle {
        val sales = salesDao.getSalesInRange(fromMillis, toMillis)
            .filter { it.status != SaleStatus.VOIDED.name }
        val returns = salesDao.getSalesReturnsInRange(fromMillis, toMillis)
        val expenses = financeDao.getExpensesInRange(fromMillis, toMillis)

        var gross = 0L
        var discounts = 0L
        var cogs = 0L
        val slabMap = mutableMapOf<Int, LongArray>() // taxable, cgst, sgst, igst, totalTax
        val paymentMap = mutableMapOf<PaymentMethod, Long>()
        val productAgg = mutableMapOf<String, Pair<Long, Long>>() // qtyScaled, revenuePaise
        val cashierAgg = mutableMapOf<String, Pair<Int, Long>>() // bills, salesPaise

        for (s in sales) {
            gross += s.subtotalPaise
            discounts += s.discountPaise

            val curCashier = cashierAgg[s.cashierName] ?: (0 to 0L)
            cashierAgg[s.cashierName] = (curCashier.first + 1) to (curCashier.second + s.totalPaise)

            val items = salesDao.getItemsForSale(s.id)
            for (item in items) {
                val netQty = (item.qtyScaled - item.returnedQtyScaled).coerceAtLeast(0L)
                if (netQty > 0L) {
                    cogs += BigDecimal(item.costPriceSnapshotPaise)
                        .multiply(BigDecimal(netQty))
                        .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
                        .longValueExact()
                }
                val arr = slabMap.getOrPut(item.gstRateBps) { LongArray(5) }
                arr[0] += item.taxablePaise
                arr[1] += item.cgstPaise
                arr[2] += item.sgstPaise
                arr[3] += item.igstPaise
                arr[4] += item.taxPaise

                val pCur = productAgg[item.descriptionSnapshot] ?: (0L to 0L)
                productAgg[item.descriptionSnapshot] = (pCur.first + netQty) to (pCur.second + item.totalPaise)
            }

            val payments = salesDao.getPaymentsForSale(s.id)
            for (p in payments) {
                val pm = PaymentMethod.entries.firstOrNull { it.name == p.method } ?: PaymentMethod.CASH
                paymentMap[pm] = (paymentMap[pm] ?: 0L) + p.amountPaise
            }
        }

        val returnsTotal = returns.sumOf { it.totalRefundPaise }
        val netSales = (sales.sumOf { it.totalPaise } - returnsTotal).coerceAtLeast(0L)
        val totalTaxCollected = sales.sumOf { it.taxPaise }
        val netExTax = (netSales - totalTaxCollected).coerceAtLeast(0L)
        val estProfit = netExTax - cogs
        val expTotal = expenses.sumOf { it.amountPaise }

        val customers = partyDao.getAllCustomers()
        val suppliers = partyDao.getAllSuppliers()
        val products = catDao.getAllProducts()
        val variants = catDao.getAllVariants().groupBy { it.productId }
        val balances = catDao.getAllStockBalances().associateBy { it.productVariantId }

        val invVal = products.sumOf { p ->
            val qty = variants[p.id].orEmpty().sumOf { balances[it.id]?.quantityScaled ?: 0L }.coerceAtLeast(0L)
            BigDecimal(p.costPricePaise)
                .multiply(BigDecimal(qty))
                .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
                .longValueExact()
        }

        return ReportsBundle(
            fromMillis = fromMillis,
            toMillis = toMillis,
            grossSalesPaise = gross,
            discountsPaise = discounts,
            returnsPaise = returnsTotal,
            netSalesPaise = netSales,
            cogsPaise = cogs,
            estimatedProfitPaise = estProfit,
            expensesPaise = expTotal,
            billCount = sales.size,
            gstSlabs = slabMap.entries.sortedBy { it.key }.map { (bps, arr) ->
                GstSlabSummary(bps, arr[0], arr[1], arr[2], arr[3], arr[4])
            },
            paymentBreakdown = paymentMap.entries.sortedByDescending { it.value }.map {
                PaymentBreakdownPoint(it.key, it.value)
            },
            topProducts = productAgg.entries.sortedByDescending { it.value.second }.take(8).map {
                TopProductPoint(it.key, it.value.first, it.value.second)
            },
            cashierPerformance = cashierAgg.entries.sortedByDescending { it.value.second }.map {
                CashierPerfRow(it.key, it.value.first, it.value.second)
            },
            totalReceivablePaise = customers.sumOf { it.currentBalancePaise },
            totalPayablePaise = suppliers.sumOf { it.currentPayablePaise },
            inventoryValuationPaise = invVal
        )
    }
}

private fun CashRegisterEntity.toModel(): CashRegisterShift = CashRegisterShift(
    id = id,
    userId = userId,
    userName = userName,
    openedAt = openedAt,
    openingCashPaise = openingCashPaise,
    closedAt = closedAt,
    expectedCashPaise = expectedCashPaise,
    countedCashPaise = countedCashPaise,
    variancePaise = variancePaise,
    varianceReason = varianceReason,
    approvedBy = approvedBy
)
