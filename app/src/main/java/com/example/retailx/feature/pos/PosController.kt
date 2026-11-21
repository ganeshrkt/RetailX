package com.example.retailx.feature.pos

import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CompleteSaleCommand
import com.example.retailx.domain.repository.CompleteSalePaymentInput
import com.example.retailx.domain.tax.GstTaxEngine
import com.example.retailx.lan.client.LanConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class PosUiState(
    val searchQuery: String = "",
    val selectedCategoryId: String? = null,
    val products: List<Product> = emptyList(),
    val categories: List<Category> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val wallets: List<Wallet> = emptyList(),
    val heldBills: List<HeldBill> = emptyList(),
    val cartItems: List<CartItemInput> = emptyList(),
    val selectedCustomer: Customer? = null,
    val placeOfSupplyStateCode: String = "27",
    val billDiscountPaise: Long = 0L,
    val computedTotals: ComputedSaleTotals = GstTaxEngine.calculate(emptyList(), 0L, "27", "27", true),
    val activeHeldBillId: String? = null,
    val storeSettings: StoreSettings? = null,
    val runtimeMode: RuntimeMode = RuntimeMode.STANDALONE,
    val lanState: LanConnectionState = LanConnectionState.STANDALONE,
    val isCheckingOut: Boolean = false,
    val activeIdempotencyKey: String? = null,
    val lastCompletedSale: Sale? = null,
    val lastGeneratedPdf: File? = null,
    val feedbackMessage: String? = null,
    val isErrorFeedback: Boolean = false
)

class PosController(
    private val container: AppContainer,
    private val currentUserProvider: () -> User?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val searchQuery = MutableStateFlow("")
    private val selectedCategoryId = MutableStateFlow<String?>(null)
    private val cartItems = MutableStateFlow<List<CartItemInput>>(emptyList())
    private val selectedCustomer = MutableStateFlow<Customer?>(null)
    private val placeOfSupply = MutableStateFlow("27")
    private val billDiscountPaise = MutableStateFlow(0L)
    private val activeHeldBillId = MutableStateFlow<String?>(null)
    private val isCheckingOut = MutableStateFlow(false)
    private val activeIdempotencyKey = MutableStateFlow<String?>(null)
    private val lastCompletedSale = MutableStateFlow<Sale?>(null)
    private val lastGeneratedPdf = MutableStateFlow<File?>(null)
    private val feedback = MutableStateFlow<Pair<String, Boolean>?>(null)
    private val lanCachedProducts = MutableStateFlow<List<Product>?>(null)

    private val catalogInputs = combine(
        container.catalogRepository.observeProducts(activeOnly = true),
        container.catalogRepository.observeCategories(),
        container.partiesAndPurchasesRepository.observeCustomers(),
        container.financeAndReportsRepository.observeWallets(),
        container.salesRepository.observeHeldBills()
    ) { prods, cats, custs, wallets, held ->
        CatalogSlice(prods, cats, custs, wallets, held)
    }

    private val cartInputs = combine(
        cartItems,
        selectedCustomer,
        placeOfSupply,
        billDiscountPaise,
        container.storeAndAuthRepository.observeStoreSettings()
    ) { items, cust, posState, billDisc, store ->
        CartSlice(items, cust, posState, billDisc, store)
    }

    val uiState: StateFlow<PosUiState> = combine(
        searchQuery,
        selectedCategoryId,
        catalogInputs,
        cartInputs,
        combine(
            container.preferencesStore.preferences,
            container.lanApiClient.connectionState,
            isCheckingOut,
            lastCompletedSale,
            combine(activeHeldBillId, activeIdempotencyKey, lastGeneratedPdf, feedback, lanCachedProducts) { h, k, pdf, fb, lanProds ->
                MetaSlice(h, k, pdf, fb, lanProds)
            }
        ) { prefs, lanState, busy, sale, meta ->
            StatusSlice(prefs.runtimeMode, lanState, busy, sale, meta)
        }
    ) { q, catId, catSlice, cartSlice, statusSlice ->
        val storeState = cartSlice.store?.stateCode ?: "27"
        val effectivePosState = cartSlice.posState.ifBlank { storeState }
        val totals = GstTaxEngine.calculate(
            items = cartSlice.items,
            billDiscountPaise = cartSlice.billDiscountPaise,
            storeStateCode = storeState,
            placeOfSupplyStateCode = effectivePosState,
            autoRoundInvoice = cartSlice.store?.autoRoundInvoice ?: true
        )
        val sourceProducts = if (statusSlice.runtimeMode == RuntimeMode.LAN_CLIENT && statusSlice.meta.lanProds != null) {
            statusSlice.meta.lanProds
        } else {
            catSlice.products
        }
        val ql = q.trim().lowercase()
        val filteredProducts = sourceProducts.filter { p ->
            (catId == null || p.categoryId == catId) &&
                (ql.isEmpty() || p.name.lowercase().contains(ql) || p.sku.lowercase().contains(ql) || (p.barcode?.lowercase()?.contains(ql) == true))
        }
        val activeCustomer = cartSlice.customer ?: catSlice.customers.firstOrNull { it.isWalkIn }

        PosUiState(
            searchQuery = q,
            selectedCategoryId = catId,
            products = filteredProducts,
            categories = catSlice.categories,
            customers = catSlice.customers,
            wallets = catSlice.wallets.filter { it.active },
            heldBills = catSlice.heldBills,
            cartItems = cartSlice.items,
            selectedCustomer = activeCustomer,
            placeOfSupplyStateCode = effectivePosState,
            billDiscountPaise = cartSlice.billDiscountPaise,
            computedTotals = totals,
            activeHeldBillId = statusSlice.meta.heldId,
            storeSettings = cartSlice.store,
            runtimeMode = statusSlice.runtimeMode,
            lanState = statusSlice.lanState,
            isCheckingOut = statusSlice.busy,
            activeIdempotencyKey = statusSlice.meta.idemKey,
            lastCompletedSale = statusSlice.sale,
            lastGeneratedPdf = statusSlice.meta.pdf,
            feedbackMessage = statusSlice.meta.feedback?.first,
            isErrorFeedback = statusSlice.meta.feedback?.second ?: false
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), PosUiState())

    init {
        scope.launch {
            container.storeAndAuthRepository.observeStoreSettings().collectLatest { store ->
                if (store != null && selectedCustomer.value == null) {
                    placeOfSupply.value = store.stateCode
                }
            }
        }
        refreshLanCatalogIfClient()
    }

    fun refreshLanCatalogIfClient() {
        scope.launch {
            val prefs = container.preferencesStore.preferences.first()
            if (prefs.runtimeMode == RuntimeMode.LAN_CLIENT && prefs.lanServerBaseUrl.isNotBlank()) {
                container.lanApiClient.checkHealth(prefs.lanServerBaseUrl)
                val res = container.lanApiClient.fetchProductsFromHost(
                    prefs.lanServerBaseUrl,
                    prefs.lanDeviceId,
                    prefs.lanDeviceToken
                )
                if (res is AppResult.Success) {
                    lanCachedProducts.value = res.data
                }
            } else {
                container.lanApiClient.setStandalone()
                lanCachedProducts.value = null
            }
        }
    }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    fun selectCategory(catId: String?) {
        selectedCategoryId.value = catId
    }

    fun submitBarcodeOrQuery(rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) return
        scope.launch {
            val prod = container.catalogRepository.findByBarcodeOrSku(code)
                ?: uiState.value.products.firstOrNull {
                    it.barcode.equals(code, ignoreCase = true) || it.sku.equals(code, ignoreCase = true)
                }
            if (prod != null) {
                addProductToCart(prod, prod.variants.firstOrNull())
                searchQuery.value = ""
            } else {
                feedback.value = "No product found for barcode/SKU '$code'" to true
            }
        }
    }

    fun addProductToCart(product: Product, variant: ProductVariant? = null) {
        val vId = variant?.id ?: product.defaultVariantId
        val sku = variant?.sku ?: product.sku
        val price = variant?.sellingPricePaise ?: product.sellingPricePaise
        val cost = variant?.costPricePaise ?: product.costPricePaise
        val avail = variant?.stockScaled ?: product.stockScaled
        val allowNeg = uiState.value.storeSettings?.allowNegativeStock == true

        val current = cartItems.value.toMutableList()
        val idx = current.indexOfFirst { it.productVariantId == vId }
        val step = QuantityScale.SCALE
        if (idx >= 0) {
            val existing = current[idx]
            val nextQty = existing.qtyScaled + step
            if (!allowNeg && nextQty > avail) {
                feedback.value = "Stock limit reached for ${product.name}" to true
                return
            }
            current[idx] = existing.copy(qtyScaled = nextQty)
        } else {
            if (!allowNeg && avail < step) {
                feedback.value = "${product.name} is out of stock" to true
                return
            }
            val label = if (variant != null && variant.label != "Default") "${product.name} (${variant.label})" else product.name
            current.add(
                CartItemInput(
                    lineId = UUID.randomUUID().toString(),
                    productId = product.id,
                    productVariantId = vId,
                    name = label,
                    sku = sku,
                    hsnCode = product.hsnCode,
                    unitShortName = product.unitShortName,
                    qtyScaled = step,
                    unitPricePaise = price,
                    costPricePaise = cost,
                    lineDiscountPaise = 0L,
                    gstRateBps = product.gstRateBps,
                    taxCategory = product.taxCategory,
                    taxInclusive = product.taxInclusive,
                    maxAvailableScaled = avail
                )
            )
        }
        cartItems.value = current
        activeIdempotencyKey.value = null
    }

    fun updateLineQuantity(lineId: String, deltaScaled: Long) {
        val allowNeg = uiState.value.storeSettings?.allowNegativeStock == true
        val current = cartItems.value.toMutableList()
        val idx = current.indexOfFirst { it.lineId == lineId }
        if (idx < 0) return
        val item = current[idx]
        val next = item.qtyScaled + deltaScaled
        if (next <= 0L) {
            current.removeAt(idx)
        } else if (!allowNeg && next > item.maxAvailableScaled) {
            feedback.value = "Insufficient stock for ${item.name}" to true
            return
        } else {
            current[idx] = item.copy(qtyScaled = next)
        }
        cartItems.value = current
        activeIdempotencyKey.value = null
    }

    fun updateLinePriceAndDiscount(lineId: String, newRatePaise: Long, newDiscountPaise: Long) {
        cartItems.value = cartItems.value.map {
            if (it.lineId == lineId) {
                it.copy(
                    unitPricePaise = newRatePaise.coerceAtLeast(0L),
                    lineDiscountPaise = newDiscountPaise.coerceAtLeast(0L)
                )
            } else it
        }
        activeIdempotencyKey.value = null
    }

    fun setBillDiscount(paise: Long) {
        billDiscountPaise.value = paise.coerceAtLeast(0L)
        activeIdempotencyKey.value = null
    }

    fun selectCustomer(customer: Customer) {
        selectedCustomer.value = customer
        if (customer.stateCode.isNotBlank()) {
            placeOfSupply.value = customer.stateCode
        }
    }

    fun setPlaceOfSupplyState(code: String) {
        placeOfSupply.value = code
    }

    fun clearCart() {
        cartItems.value = emptyList()
        billDiscountPaise.value = 0L
        activeHeldBillId.value = null
        activeIdempotencyKey.value = null
    }

    fun holdCurrentCart(label: String) {
        val user = currentUserProvider() ?: return
        val state = uiState.value
        if (state.cartItems.isEmpty()) return
        scope.launch {
            val res = container.salesRepository.saveHeldBill(
                id = state.activeHeldBillId,
                label = label.ifBlank { state.selectedCustomer?.name ?: "Held Bill" },
                customerId = state.selectedCustomer?.id,
                customerName = state.selectedCustomer?.name ?: "Walk-in Customer",
                placeOfSupplyStateCode = state.placeOfSupplyStateCode,
                billDiscountPaise = state.billDiscountPaise,
                items = state.cartItems,
                totalPreviewPaise = state.computedTotals.grandTotalPaise,
                userId = user.id,
                userName = user.fullName
            )
            when (res) {
                is AppResult.Success -> {
                    clearCart()
                    feedback.value = "Bill held: ${res.data.label}" to false
                }
                is AppResult.Failure -> feedback.value = res.error.userMessage to true
            }
        }
    }

    fun resumeHeldBill(bill: HeldBill) {
        cartItems.value = bill.items
        billDiscountPaise.value = bill.billDiscountPaise
        placeOfSupply.value = bill.placeOfSupplyStateCode
        activeHeldBillId.value = bill.id
        selectedCustomer.value = uiState.value.customers.firstOrNull { it.id == bill.customerId }
        activeIdempotencyKey.value = null
    }

    fun deleteHeldBill(id: String) {
        scope.launch { container.salesRepository.deleteHeldBill(id) }
    }

    fun completeCheckout(payments: List<CompleteSalePaymentInput>) {
        val user = currentUserProvider() ?: return
        val state = uiState.value
        if (state.cartItems.isEmpty()) return
        val idemKey = activeIdempotencyKey.value ?: UUID.randomUUID().toString().also {
            activeIdempotencyKey.value = it
        }
        isCheckingOut.value = true
        scope.launch {
            val prefs = container.preferencesStore.preferences.first()
            val cmd = CompleteSaleCommand(
                idempotencyKey = idemKey,
                customerId = state.selectedCustomer?.id,
                placeOfSupplyStateCode = state.placeOfSupplyStateCode,
                billDiscountPaise = state.billDiscountPaise,
                items = state.cartItems,
                payments = payments,
                actorUserId = user.id,
                actorName = user.fullName,
                heldBillIdToDelete = state.activeHeldBillId
            )

            val res = if (prefs.runtimeMode == RuntimeMode.LAN_CLIENT) {
                container.lanApiClient.completeSaleOnHost(
                    baseUrl = prefs.lanServerBaseUrl,
                    deviceId = prefs.lanDeviceId,
                    deviceToken = prefs.lanDeviceToken,
                    command = cmd
                )
            } else {
                container.salesRepository.completeSale(cmd)
            }

            isCheckingOut.value = false
            when (res) {
                is AppResult.Success -> {
                    clearCart()
                    lastCompletedSale.value = res.data
                    feedback.value = "Invoice ${res.data.invoiceNo} completed!" to false
                    if (prefs.runtimeMode == RuntimeMode.LAN_CLIENT) {
                        refreshLanCatalogIfClient()
                    }
                }
                is AppResult.Failure -> {
                    feedback.value = res.error.userMessage to true
                }
            }
        }
    }

    fun generateInvoicePdf(sale: Sale, format: InvoiceFormat) {
        val store = uiState.value.storeSettings ?: return
        scope.launch {
            val file = container.pdfGenerator.generateSaleInvoicePdf(store, sale, format)
            lastGeneratedPdf.value = file
        }
    }

    fun dismissCompletedSale() {
        lastCompletedSale.value = null
        lastGeneratedPdf.value = null
    }

    fun clearFeedback() {
        feedback.value = null
    }

    private data class CatalogSlice(
        val products: List<Product>,
        val categories: List<Category>,
        val customers: List<Customer>,
        val wallets: List<Wallet>,
        val heldBills: List<HeldBill>
    )

    private data class CartSlice(
        val items: List<CartItemInput>,
        val customer: Customer?,
        val posState: String,
        val billDiscountPaise: Long,
        val store: StoreSettings?
    )

    private data class MetaSlice(
        val heldId: String?,
        val idemKey: String?,
        val pdf: File?,
        val feedback: Pair<String, Boolean>?,
        val lanProds: List<Product>?
    )

    private data class StatusSlice(
        val runtimeMode: RuntimeMode,
        val lanState: LanConnectionState,
        val busy: Boolean,
        val sale: Sale?,
        val meta: MetaSlice
    )
}
