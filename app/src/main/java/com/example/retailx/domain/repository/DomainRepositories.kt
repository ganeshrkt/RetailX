package com.example.retailx.domain.repository

import com.example.retailx.core.common.AppResult
import com.example.retailx.core.model.*
import kotlinx.coroutines.flow.Flow

data class CompleteSalePaymentInput(
    val walletId: String?,
    val method: PaymentMethod,
    val amountPaise: Long,
    val reference: String
)

data class CompleteSaleCommand(
    val idempotencyKey: String,
    val customerId: String?,
    val placeOfSupplyStateCode: String,
    val billDiscountPaise: Long,
    val items: List<CartItemInput>,
    val payments: List<CompleteSalePaymentInput>,
    val actorUserId: String,
    val actorName: String,
    val heldBillIdToDelete: String? = null
)

data class ReturnLineInput(
    val saleItemId: String,
    val qtyScaled: Long,
    val restock: Boolean
)

data class ProcessSalesReturnCommand(
    val idempotencyKey: String,
    val saleId: String,
    val reason: String,
    val refundMethod: PaymentMethod,
    val refundWalletId: String?,
    val lines: List<ReturnLineInput>,
    val actorUserId: String,
    val actorName: String
)

data class PurchaseLineDraft(
    val productVariantId: String,
    val description: String,
    val sku: String,
    val hsnCode: String,
    val unitShortName: String,
    val qtyScaled: Long,
    val ratePaise: Long,
    val discountPaise: Long,
    val gstRateBps: Int
)

data class CreatePurchaseCommand(
    val supplierId: String,
    val supplierInvoiceNo: String,
    val supplierInvoiceDate: Long,
    val isInterState: Boolean,
    val additionalChargesPaise: Long,
    val roundOffPaise: Long,
    val receiveImmediately: Boolean,
    val lines: List<PurchaseLineDraft>,
    val actorUserId: String,
    val actorName: String
)

data class DashboardSummary(
    val todayGrossSalesPaise: Long,
    val todayNetSalesPaise: Long,
    val todayDiscountsPaise: Long,
    val todayReturnsPaise: Long,
    val todayBillCount: Int,
    val todayEstimatedProfitPaise: Long,
    val todayExpensesPaise: Long,
    val lowStockCount: Int,
    val outOfStockCount: Int,
    val totalStockValuePaise: Long,
    val dailyTrend: List<DailyTrendPoint>,
    val paymentBreakdown: List<PaymentBreakdownPoint>,
    val topProducts: List<TopProductPoint>
)

data class DailyTrendPoint(
    val label: String,
    val salesPaise: Long,
    val profitPaise: Long
)

data class PaymentBreakdownPoint(
    val method: PaymentMethod,
    val amountPaise: Long
)

data class TopProductPoint(
    val productName: String,
    val qtyScaled: Long,
    val revenuePaise: Long
)

data class GstSlabSummary(
    val gstRateBps: Int,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val totalTaxPaise: Long
)

data class ReportsBundle(
    val fromMillis: Long,
    val toMillis: Long,
    val grossSalesPaise: Long,
    val discountsPaise: Long,
    val returnsPaise: Long,
    val netSalesPaise: Long,
    val cogsPaise: Long,
    val estimatedProfitPaise: Long,
    val expensesPaise: Long,
    val billCount: Int,
    val gstSlabs: List<GstSlabSummary>,
    val paymentBreakdown: List<PaymentBreakdownPoint>,
    val topProducts: List<TopProductPoint>,
    val cashierPerformance: List<CashierPerfRow>,
    val totalReceivablePaise: Long,
    val totalPayablePaise: Long,
    val inventoryValuationPaise: Long
)

data class CashierPerfRow(
    val cashierName: String,
    val billCount: Int,
    val totalSalesPaise: Long
)

interface StoreRepository {
    fun observeStoreSettings(): Flow<StoreSettings?>
    suspend fun getStoreSettings(): StoreSettings?
    suspend fun setupInitialStore(
        storeName: String,
        gstin: String,
        stateCode: String,
        address: String,
        phone: String,
        email: String,
        invoicePrefix: String,
        adminUsername: String,
        adminFullName: String,
        adminPassword: String,
        adminPin: String,
        loadStarterCatalog: Boolean
    ): AppResult<User>
    suspend fun updateStoreSettings(settings: StoreSettings, actorUserId: String, actorName: String): AppResult<Unit>
}

interface AuthRepository {
    fun observeUsers(): Flow<List<User>>
    fun observeRoles(): Flow<List<Role>>
    fun observeActivityLogs(limit: Int = 200): Flow<List<ActivityLog>>
    fun observePairedDevices(): Flow<List<PairedDevice>>
    suspend fun loginWithPassword(username: String, password: String): AppResult<User>
    suspend fun loginWithPin(username: String, pin: String): AppResult<User>
    suspend fun verifyAdminPassword(password: String): Boolean
    suspend fun createUser(
        username: String,
        fullName: String,
        roleId: String,
        password: String,
        pin: String?,
        actorUserId: String,
        actorName: String
    ): AppResult<User>
    suspend fun setUserActive(userId: String, active: Boolean, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun updateRolePermissions(roleId: String, permissions: Set<String>, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun resetUserCredentials(userId: String, newPassword: String?, newPin: String?, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun revokeDevice(deviceId: String, actorUserId: String, actorName: String): AppResult<Unit>
}

interface CatalogRepository {
    fun observeProducts(query: String = "", categoryId: String? = null, activeOnly: Boolean = false): Flow<List<Product>>
    fun observeCategories(): Flow<List<Category>>
    fun observeBrands(): Flow<List<Brand>>
    fun observeUnits(): Flow<List<UnitModel>>
    fun observeStockMovements(limit: Int = 200): Flow<List<StockMovement>>
    suspend fun findByBarcodeOrSku(code: String): Product?
    suspend fun upsertProduct(
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
    ): AppResult<Product>
    suspend fun createCategory(name: String): AppResult<Category>
    suspend fun createBrand(name: String): AppResult<Brand>
    suspend fun adjustStock(
        productVariantId: String,
        deltaScaled: Long,
        movementType: MovementType,
        reason: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit>
    suspend fun importProductsCsv(csvText: String, actorUserId: String, actorName: String): AppResult<Int>
    suspend fun exportProductsCsv(): String
}

interface SalesRepository {
    fun observeSales(query: String = ""): Flow<List<Sale>>
    fun observeHeldBills(): Flow<List<HeldBill>>
    fun observeSalesReturns(): Flow<List<SalesReturn>>
    suspend fun getSaleById(saleId: String): Sale?
    suspend fun completeSale(command: CompleteSaleCommand): AppResult<Sale>
    suspend fun saveHeldBill(
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
    ): AppResult<HeldBill>
    suspend fun deleteHeldBill(id: String)
    suspend fun processSalesReturn(command: ProcessSalesReturnCommand): AppResult<SalesReturn>
    suspend fun voidSale(saleId: String, reason: String, actorUserId: String, actorName: String): AppResult<Unit>
}

interface PartiesAndPurchasesRepository {
    fun observeCustomers(query: String = ""): Flow<List<Customer>>
    fun observeSuppliers(query: String = ""): Flow<List<Supplier>>
    fun observePurchases(): Flow<List<Purchase>>
    suspend fun upsertCustomer(customer: Customer, actorUserId: String, actorName: String): AppResult<Customer>
    suspend fun collectCustomerPayment(
        customerId: String,
        walletId: String,
        amountPaise: Long,
        reference: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit>
    suspend fun upsertSupplier(supplier: Supplier, actorUserId: String, actorName: String): AppResult<Supplier>
    suspend fun createPurchase(command: CreatePurchaseCommand): AppResult<Purchase>
    suspend fun receivePurchase(purchaseId: String, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun paySupplierPurchase(
        purchaseId: String,
        walletId: String,
        amountPaise: Long,
        reference: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit>
    suspend fun processPurchaseReturn(
        purchaseId: String,
        reason: String,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit>
}

interface FinanceAndReportsRepository {
    fun observeWallets(): Flow<List<Wallet>>
    fun observeFinancialTransactions(limit: Int = 200): Flow<List<FinancialTransaction>>
    fun observeExpenseCategories(): Flow<List<ExpenseCategory>>
    fun observeExpenses(limit: Int = 200): Flow<List<Expense>>
    fun observeCashRegisterShifts(): Flow<List<CashRegisterShift>>
    fun observeOpenShift(): Flow<CashRegisterShift?>
    suspend fun createWallet(name: String, type: WalletType, openingBalancePaise: Long, actorUserId: String, actorName: String): AppResult<Wallet>
    suspend fun transferBetweenWallets(fromWalletId: String, toWalletId: String, amountPaise: Long, note: String, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun recordCashMovement(walletId: String, direction: FinancialDirection, amountPaise: Long, note: String, actorUserId: String, actorName: String): AppResult<Unit>
    suspend fun recordExpense(categoryId: String, walletId: String, payee: String, amountPaise: Long, note: String, actorUserId: String, actorName: String): AppResult<Expense>
    suspend fun openCashRegister(openingCashPaise: Long, userId: String, userName: String): AppResult<CashRegisterShift>
    suspend fun closeCashRegister(shiftId: String, countedCashPaise: Long, varianceReason: String?, approvedBy: String?, actorUserId: String, actorName: String): AppResult<CashRegisterShift>
    suspend fun getDashboardSummary(): DashboardSummary
    suspend fun getReportsBundle(fromMillis: Long, toMillis: Long): ReportsBundle
}
