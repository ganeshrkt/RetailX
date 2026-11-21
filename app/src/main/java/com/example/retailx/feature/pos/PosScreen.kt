package com.example.retailx.feature.pos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.core.common.IndianStates
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import com.example.retailx.lan.client.LanConnectionState
import com.example.retailx.platform.pdf.PdfDocumentGenerator

@Composable
fun PosScreen(
    controller: PosController,
    currentUser: User?,
    pdfGenerator: PdfDocumentGenerator,
    onBackPressed: (() -> Unit)? = null
) {
    val state by controller.uiState.collectAsStateWithLifecycle()

    var showCheckout by remember { mutableStateOf(false) }
    var showHeldBills by remember { mutableStateOf(false) }
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showBarcodeDialog by remember { mutableStateOf(false) }
    var showHoldPrompt by remember { mutableStateOf(false) }
    var showBillDiscountDialog by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<CartItemInput?>(null) }
    var variantPickingProduct by remember { mutableStateOf<Product?>(null) }
    var showStatePicker by remember { mutableStateOf(false) }

    val canDiscount = currentUser?.permissions?.contains(PermissionCode.POS_DISCOUNT.code) == true
    val canOverridePrice = currentUser?.permissions?.contains(PermissionCode.POS_PRICE_OVERRIDE.code) == true
    val isLanDisconnected = state.runtimeMode == RuntimeMode.LAN_CLIENT && state.lanState == LanConnectionState.DISCONNECTED

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.feedbackMessage) {
        val msg = state.feedbackMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        controller.clearFeedback()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val isWide = maxWidth >= 820.dp

            Column(modifier = Modifier.fillMaxSize()) {
                if (state.runtimeMode == RuntimeMode.LAN_CLIENT) {
                    Surface(
                        color = if (isLanDisconnected) LocalSemanticColors.current.errorBg else LocalSemanticColors.current.infoBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isLanDisconnected) {
                                    "LAN Host Disconnected — Read-Only / Draft Mode. Unfinished carts can be held, checkout resumes when host reconnects."
                                } else {
                                    "Connected to RetailX Store Host • Live Stock & Atomic Billing"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isLanDisconnected) LocalSemanticColors.current.error else LocalSemanticColors.current.info,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { controller.refreshLanCatalogIfClient() }) {
                                Text("Refresh")
                            }
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Left + Center: Search, Categories & Product Grid
                        Column(
                            modifier = Modifier
                                .weight(1.45f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PosSearchAndCategoryBar(
                                state = state,
                                onQueryChange = controller::setSearchQuery,
                                onBarcodeSubmit = controller::submitBarcodeOrQuery,
                                onOpenBarcodePad = { showBarcodeDialog = true },
                                onSelectCategory = controller::selectCategory
                            )
                            PosProductGrid(
                                products = state.products,
                                onProductClick = { prod ->
                                    if (prod.variants.size > 1) {
                                        variantPickingProduct = prod
                                    } else {
                                        controller.addProductToCart(prod, prod.variants.firstOrNull())
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Right: Cart, Totals & Checkout
                        PosCartPanel(
                            state = state,
                            canDiscount = canDiscount,
                            isLanDisconnected = isLanDisconnected,
                            onSelectCustomer = { showCustomerPicker = true },
                            onSelectState = { showStatePicker = true },
                            onUpdateQty = controller::updateLineQuantity,
                            onEditLine = { editingLine = it },
                            onOpenBillDiscount = { showBillDiscountDialog = true },
                            onHold = { showHoldPrompt = true },
                            onOpenHeld = { showHeldBills = true },
                            onClear = controller::clearCart,
                            onCheckout = { showCheckout = true },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Compact Portrait / Narrow Layout: Tabbed Catalog & Cart
                    var compactTab by remember { mutableIntStateOf(0) }
                    TabRow(selectedTabIndex = compactTab) {
                        Tab(
                            selected = compactTab == 0,
                            onClick = { compactTab = 0 },
                            text = { Text("Catalog (${state.products.size})") }
                        )
                        Tab(
                            selected = compactTab == 1,
                            onClick = { compactTab = 1 },
                            text = {
                                val count = state.cartItems.size
                                Text("Cart ($count) • ${MoneyFormatter.formatPaiseCompact(state.computedTotals.grandTotalPaise)}")
                            }
                        )
                    }
                    if (compactTab == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PosSearchAndCategoryBar(
                                state = state,
                                onQueryChange = controller::setSearchQuery,
                                onBarcodeSubmit = controller::submitBarcodeOrQuery,
                                onOpenBarcodePad = { showBarcodeDialog = true },
                                onSelectCategory = controller::selectCategory
                            )
                            PosProductGrid(
                                products = state.products,
                                onProductClick = { prod ->
                                    if (prod.variants.size > 1) variantPickingProduct = prod
                                    else controller.addProductToCart(prod, prod.variants.firstOrNull())
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        PosCartPanel(
                            state = state,
                            canDiscount = canDiscount,
                            isLanDisconnected = isLanDisconnected,
                            onSelectCustomer = { showCustomerPicker = true },
                            onSelectState = { showStatePicker = true },
                            onUpdateQty = controller::updateLineQuantity,
                            onEditLine = { editingLine = it },
                            onOpenBillDiscount = { showBillDiscountDialog = true },
                            onHold = { showHoldPrompt = true },
                            onOpenHeld = { showHeldBills = true },
                            onClear = controller::clearCart,
                            onCheckout = { showCheckout = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showCheckout) {
        CheckoutDialog(
            state = state,
            onDismiss = { showCheckout = false },
            onConfirmSale = { payments ->
                showCheckout = false
                controller.completeCheckout(payments)
            }
        )
    }

    state.lastCompletedSale?.let { completed ->
        CompletedSaleDialog(
            sale = completed,
            generatedPdf = state.lastGeneratedPdf,
            pdfGenerator = pdfGenerator,
            onGeneratePdf = { fmt -> controller.generateInvoicePdf(completed, fmt) },
            onDismiss = { controller.dismissCompletedSale() }
        )
    }

    if (showHeldBills) {
        HeldBillsDialog(
            heldBills = state.heldBills,
            timezone = state.storeSettings?.timezone ?: "Asia/Kolkata",
            onResume = controller::resumeHeldBill,
            onDelete = controller::deleteHeldBill,
            onDismiss = { showHeldBills = false }
        )
    }

    if (showCustomerPicker) {
        CustomerPickerDialog(
            customers = state.customers,
            onSelect = controller::selectCustomer,
            onDismiss = { showCustomerPicker = false }
        )
    }

    editingLine?.let { line ->
        EditCartLineDialog(
            line = line,
            canOverridePrice = canOverridePrice,
            canDiscount = canDiscount,
            onSave = { rate, disc -> controller.updateLinePriceAndDiscount(line.lineId, rate, disc) },
            onDismiss = { editingLine = null }
        )
    }

    if (showBarcodeDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBarcodeDialog = false },
            title = { Text("Enter Barcode / SKU") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Barcode or SKU") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        controller.submitBarcodeOrQuery(code)
                        showBarcodeDialog = false
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    controller.submitBarcodeOrQuery(code)
                    showBarcodeDialog = false
                }) { Text("Add to Cart") }
            },
            dismissButton = { TextButton(onClick = { showBarcodeDialog = false }) { Text("Cancel") } }
        )
    }

    if (showHoldPrompt) {
        var label by remember { mutableStateOf(state.selectedCustomer?.name ?: "Counter Hold") }
        AlertDialog(
            onDismissRequest = { showHoldPrompt = false },
            title = { Text("Hold Current Bill") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Reference Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    controller.holdCurrentCart(label)
                    showHoldPrompt = false
                }) { Text("Hold Bill") }
            },
            dismissButton = { TextButton(onClick = { showHoldPrompt = false }) { Text("Cancel") } }
        )
    }

    if (showBillDiscountDialog) {
        var discText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(state.billDiscountPaise)) }
        AlertDialog(
            onDismissRequest = { showBillDiscountDialog = false },
            title = { Text("Bill Discount (₹)") },
            text = {
                OutlinedTextField(
                    value = discText,
                    onValueChange = { discText = it },
                    label = { Text("Discount Amount in ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val paise = MoneyFormatter.parseRupeesToPaise(discText) ?: 0L
                    controller.setBillDiscount(paise)
                    showBillDiscountDialog = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showBillDiscountDialog = false }) { Text("Cancel") } }
        )
    }

    if (showStatePicker) {
        AlertDialog(
            onDismissRequest = { showStatePicker = false },
            title = { Text("Place of Supply (GST State)") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(IndianStates.all) { st ->
                        ListItem(
                            headlineContent = { Text(st.label) },
                            modifier = Modifier.clickable {
                                controller.setPlaceOfSupplyState(st.code)
                                showStatePicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStatePicker = false }) { Text("Close") } }
        )
    }

    variantPickingProduct?.let { prod ->
        AlertDialog(
            onDismissRequest = { variantPickingProduct = null },
            title = { Text("Select Variant: ${prod.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prod.variants.forEach { v ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.addProductToCart(prod, v)
                                    variantPickingProduct = null
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${v.label} (${v.sku})")
                                Text(MoneyFormatter.formatPaise(v.sellingPricePaise), style = RetailXMono.medium)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { variantPickingProduct = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun PosSearchAndCategoryBar(
    state: PosUiState,
    onQueryChange: (String) -> Unit,
    onBarcodeSubmit: (String) -> Unit,
    onOpenBarcodePad: () -> Unit,
    onSelectCategory: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search name, SKU, or scan barcode (Enter)...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onBarcodeSubmit(state.searchQuery) }),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                            onBarcodeSubmit(state.searchQuery)
                            true
                        } else false
                    }
                    .testTag("pos_search_input")
            )
            FilledTonalIconButton(
                onClick = onOpenBarcodePad,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("pos_barcode_button")
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Enter barcode")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.selectedCategoryId == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("All Items") },
                    modifier = Modifier.testTag("pos_cat_all")
                )
            }
            items(state.categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = state.selectedCategoryId == cat.id,
                    onClick = { onSelectCategory(cat.id) },
                    label = { Text(cat.name) }
                )
            }
        }
    }
}

@Composable
private fun PosProductGrid(
    products: List<Product>,
    onProductClick: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    if (products.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyStateCard(
                icon = Icons.Filled.Inventory2,
                title = "No matching products",
                message = "Try another search term or add products in Catalog."
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 165.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
        ) {
            items(products, key = { it.id }) { product ->
                Card(
                    onClick = { onProductClick(product) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("pos_product_${product.sku}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = product.sku,
                                style = RetailXMono.small,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            StatusBadge(
                                text = if (product.isOutOfStock) "Out" else "${QuantityScale.formatScaled(product.stockScaled)} ${product.unitShortName}",
                                tone = when {
                                    product.isOutOfStock -> BadgeTone.ERROR
                                    product.isLowStock -> BadgeTone.WARNING
                                    else -> BadgeTone.SUCCESS
                                }
                            )
                        }
                        Text(
                            text = product.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.heightIn(min = 36.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = MoneyFormatter.formatPaise(product.sellingPricePaise),
                                style = RetailXMono.medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "GST ${product.gstRateBps / 100}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosCartPanel(
    state: PosUiState,
    canDiscount: Boolean,
    isLanDisconnected: Boolean,
    onSelectCustomer: () -> Unit,
    onSelectState: () -> Unit,
    onUpdateQty: (String, Long) -> Unit,
    onEditLine: (CartItemInput) -> Unit,
    onOpenBillDiscount: () -> Unit,
    onHold: () -> Unit,
    onOpenHeld: () -> Unit,
    onClear: () -> Unit,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Customer + Place of supply + Held Bills bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSelectCustomer,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pos_customer_button")
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.selectedCustomer?.name ?: "Walk-in",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onSelectState,
                    modifier = Modifier.testTag("pos_supply_state_button")
                ) {
                    Text(if (state.computedTotals.isInterState) "IGST (${state.placeOfSupplyStateCode})" else "CGST/SGST (${state.placeOfSupplyStateCode})")
                }
                FilledTonalButton(
                    onClick = onOpenHeld,
                    modifier = Modifier.testTag("pos_held_bills_button")
                ) {
                    Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${state.heldBills.size}")
                }
            }

            HorizontalDivider()

            // Cart items list
            if (state.cartItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.ShoppingCart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Tap a product or scan barcode to start billing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.computedTotals.lines, key = { it.lineId }) { line ->
                        val rawInput = state.cartItems.first { it.lineId == line.lineId }
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditLine(rawInput) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(line.description, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${MoneyFormatter.formatPaise(line.ratePaise)} • GST ${line.gstRateBps / 100}%" +
                                            if (line.lineDiscountPaise > 0L) " • -${MoneyFormatter.formatPaise(line.lineDiscountPaise)}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onUpdateQty(line.lineId, -QuantityScale.SCALE) },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
                                    }
                                    Text(
                                        text = QuantityScale.formatScaled(line.qtyScaled),
                                        style = RetailXMono.medium,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    IconButton(
                                        onClick = { onUpdateQty(line.lineId, QuantityScale.SCALE) },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
                                    }
                                }
                                Text(
                                    text = MoneyFormatter.formatPaise(line.totalPaise),
                                    style = RetailXMono.medium
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Totals breakdown
            val t = state.computedTotals
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryRow("Taxable Value", MoneyFormatter.formatPaise(t.taxableTotalPaise))
                if (t.isInterState) {
                    SummaryRow("IGST", MoneyFormatter.formatPaise(t.igstTotalPaise))
                } else {
                    SummaryRow("CGST + SGST", "${MoneyFormatter.formatPaise(t.cgstTotalPaise)} + ${MoneyFormatter.formatPaise(t.sgstTotalPaise)}")
                }
                if (t.lineDiscountTotalPaise + t.billDiscountPaise > 0L) {
                    SummaryRow("Total Discount", "-${MoneyFormatter.formatPaise(t.lineDiscountTotalPaise + t.billDiscountPaise)}")
                }
                if (t.roundOffPaise != 0L) {
                    SummaryRow("Round Off", MoneyFormatter.formatPaise(t.roundOffPaise))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grand Total", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = MoneyFormatter.formatPaise(t.grandTotalPaise),
                        style = RetailXMono.large,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = state.cartItems.isNotEmpty(),
                    modifier = Modifier.testTag("pos_clear_button")
                ) {
                    Text("Clear")
                }
                OutlinedButton(
                    onClick = onHold,
                    enabled = state.cartItems.isNotEmpty(),
                    modifier = Modifier.testTag("pos_hold_button")
                ) {
                    Text("Hold")
                }
                if (canDiscount) {
                    OutlinedButton(
                        onClick = onOpenBillDiscount,
                        enabled = state.cartItems.isNotEmpty(),
                        modifier = Modifier.testTag("pos_discount_button")
                    ) {
                        Text("Disc")
                    }
                }
                Button(
                    onClick = onCheckout,
                    enabled = state.cartItems.isNotEmpty() && !isLanDisconnected,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pos_checkout_button")
                ) {
                    Icon(Icons.Filled.Payments, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isLanDisconnected) "Host Offline" else "Checkout")
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = RetailXMono.small)
    }
}
