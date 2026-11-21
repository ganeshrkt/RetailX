package com.example.retailx.feature.purchases

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CreatePurchaseCommand
import com.example.retailx.domain.repository.PurchaseLineDraft
import kotlinx.coroutines.launch

@Composable
fun PurchasesScreen(
    container: AppContainer,
    currentUser: User?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val purchases by container.partiesAndPurchasesRepository.observePurchases().collectAsStateWithLifecycle(initialValue = emptyList())
    val suppliers by container.partiesAndPurchasesRepository.observeSuppliers().collectAsStateWithLifecycle(initialValue = emptyList())
    val products by container.catalogRepository.observeProducts(activeOnly = true).collectAsStateWithLifecycle(initialValue = emptyList())
    val wallets by container.financeAndReportsRepository.observeWallets().collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val canManage = currentUser?.permissions?.contains(PermissionCode.PURCHASE_MANAGE.code) == true
    var showCreateDialog by remember { mutableStateOf(false) }
    var payingPurchase by remember { mutableStateOf<Purchase?>(null) }
    var returningPurchase by remember { mutableStateOf<Purchase?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        val m = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        statusMsg = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Filled.AddShoppingCart, contentDescription = null) },
                    text = { Text("New Purchase") },
                    modifier = Modifier.testTag("purchases_new_button")
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Purchases & Supplier Receiving",
                subtitle = "${purchases.size} purchase orders • Receiving updates stock & weighted-average cost"
            )

            if (purchases.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Filled.LocalShipping,
                    title = "No purchases recorded",
                    message = "Create a purchase entry for a supplier to receive stock and track payables."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(purchases, key = { it.id }) { p ->
                        Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${p.purchaseNo} • ${p.supplierName}", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Supplier Inv: ${p.supplierInvoiceNo.ifBlank { "—" }} • ${DateTimeUtils.formatDateTime(p.createdAt, store?.timezone ?: "Asia/Kolkata")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusBadge(
                                        text = p.status.label,
                                        tone = when (p.status) {
                                            PurchaseStatus.RECEIVED -> BadgeTone.SUCCESS
                                            PurchaseStatus.DRAFT -> BadgeTone.WARNING
                                            PurchaseStatus.CANCELLED -> BadgeTone.ERROR
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Items: ${p.items.size} • Tax: ${MoneyFormatter.formatPaise(p.taxPaise)}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Total: ${MoneyFormatter.formatPaise(p.totalPaise)} | Payable: ${MoneyFormatter.formatPaise(p.balancePayablePaise)}",
                                        style = RetailXMono.medium
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (canManage && p.status == PurchaseStatus.DRAFT && currentUser != null) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val res = container.partiesAndPurchasesRepository.receivePurchase(p.id, currentUser.id, currentUser.fullName)
                                                    statusMsg = when (res) {
                                                        is AppResult.Success -> "Purchase ${p.purchaseNo} received into stock"
                                                        is AppResult.Failure -> res.error.userMessage
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag("purchase_receive_${p.purchaseNo}")
                                        ) { Text("Mark Received") }
                                    }
                                    if (canManage && p.status == PurchaseStatus.RECEIVED && p.balancePayablePaise > 0L) {
                                        FilledTonalButton(onClick = { payingPurchase = p }) {
                                            Text("Pay Supplier")
                                        }
                                    }
                                    if (canManage && p.status == PurchaseStatus.RECEIVED) {
                                        OutlinedButton(onClick = { returningPurchase = p }) {
                                            Text("Return")
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        val st = store ?: return@OutlinedButton
                                        scope.launch {
                                            val pdf = container.pdfGenerator.generatePurchasePdf(st, p)
                                            context.startActivity(Intent.createChooser(container.pdfGenerator.buildShareIntent(pdf), "Share Purchase PDF"))
                                        }
                                    }) {
                                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("PDF")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog && currentUser != null) {
        CreatePurchaseDialog(
            suppliers = suppliers,
            products = products,
            storeStateCode = store?.stateCode ?: "27",
            onDismiss = { showCreateDialog = false },
            onSave = { cmd ->
                scope.launch {
                    val res = container.partiesAndPurchasesRepository.createPurchase(cmd)
                    when (res) {
                        is AppResult.Success -> {
                            showCreateDialog = false
                            statusMsg = "Purchase ${res.data.purchaseNo} created (${res.data.status.label})"
                        }
                        is AppResult.Failure -> statusMsg = res.error.userMessage
                    }
                }
            },
            actorUser = currentUser
        )
    }

    payingPurchase?.let { p ->
        if (currentUser != null) {
            var amountText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(p.balancePayablePaise)) }
            var selectedWalletId by remember { mutableStateOf(wallets.firstOrNull { it.type == WalletType.BANK }?.id ?: wallets.firstOrNull()?.id.orEmpty()) }
            var ref by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { payingPurchase = null },
                title = { Text("Pay Supplier for ${p.purchaseNo}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Outstanding: ${MoneyFormatter.formatPaise(p.balancePayablePaise)}", style = RetailXMono.medium)
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it },
                            label = { Text("Payment Amount (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            wallets.filter { it.active }.take(3).forEach { w ->
                                FilterChip(
                                    selected = selectedWalletId == w.id,
                                    onClick = { selectedWalletId = w.id },
                                    label = { Text(w.name) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = ref,
                            onValueChange = { ref = it },
                            label = { Text("Reference / UTR") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val amt = MoneyFormatter.parseRupeesToPaise(amountText) ?: 0L
                        scope.launch {
                            val res = container.partiesAndPurchasesRepository.paySupplierPurchase(
                                p.id, selectedWalletId, amt, ref, currentUser.id, currentUser.fullName
                            )
                            when (res) {
                                is AppResult.Success -> {
                                    payingPurchase = null
                                    statusMsg = "Supplier payment posted"
                                }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    }) { Text("Post Payment") }
                },
                dismissButton = { TextButton(onClick = { payingPurchase = null }) { Text("Cancel") } }
            )
        }
    }

    returningPurchase?.let { p ->
        if (currentUser != null) {
            var reason by remember { mutableStateOf("Damaged / excess stock returned to supplier") }
            AlertDialog(
                onDismissRequest = { returningPurchase = null },
                title = { Text("Return Purchase ${p.purchaseNo}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This will deduct the received items from inventory and reduce supplier payable.")
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text("Reason") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val res = container.partiesAndPurchasesRepository.processPurchaseReturn(
                                p.id, reason, currentUser.id, currentUser.fullName
                            )
                            when (res) {
                                is AppResult.Success -> {
                                    returningPurchase = null
                                    statusMsg = "Purchase returned and stock deducted"
                                }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    }) { Text("Confirm Return") }
                },
                dismissButton = { TextButton(onClick = { returningPurchase = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun CreatePurchaseDialog(
    suppliers: List<Supplier>,
    products: List<Product>,
    storeStateCode: String,
    actorUser: User,
    onDismiss: () -> Unit,
    onSave: (CreatePurchaseCommand) -> Unit
) {
    var selectedSupplier by remember { mutableStateOf(suppliers.firstOrNull()) }
    var supplierInvNo by remember { mutableStateOf("") }
    var receiveNow by remember { mutableStateOf(true) }
    var selectedProduct by remember { mutableStateOf(products.firstOrNull()) }
    var qtyText by remember { mutableStateOf("10") }
    var rateText by remember { mutableStateOf(selectedProduct?.let { MoneyFormatter.paiseToPlainRupees(it.costPricePaise) } ?: "100") }
    var chargesText by remember { mutableStateOf("0") }
    val lines = remember { mutableStateListOf<PurchaseLineDraft>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Purchase Entry") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 380.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (suppliers.isEmpty() || products.isEmpty()) {
                    Text("Add at least one Supplier and one Product before creating a purchase.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Supplier", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suppliers.take(4).forEach { sup ->
                            FilterChip(
                                selected = selectedSupplier?.id == sup.id,
                                onClick = { selectedSupplier = sup },
                                label = { Text(sup.name) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = supplierInvNo,
                        onValueChange = { supplierInvNo = it },
                        label = { Text("Supplier Invoice Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider()
                    Text("Add Product Line", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        products.take(4).forEach { p ->
                            FilterChip(
                                selected = selectedProduct?.id == p.id,
                                onClick = {
                                    selectedProduct = p
                                    rateText = MoneyFormatter.paiseToPlainRupees(p.costPricePaise)
                                },
                                label = { Text(p.name.take(16)) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it },
                            label = { Text("Qty") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = rateText,
                            onValueChange = { rateText = it },
                            label = { Text("Rate (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = {
                            val p = selectedProduct ?: return@FilledTonalButton
                            val q = QuantityScale.parseScaled(qtyText) ?: return@FilledTonalButton
                            val r = MoneyFormatter.parseRupeesToPaise(rateText) ?: return@FilledTonalButton
                            lines.add(
                                PurchaseLineDraft(
                                    productVariantId = p.defaultVariantId,
                                    description = p.name,
                                    sku = p.sku,
                                    hsnCode = p.hsnCode,
                                    unitShortName = p.unitShortName,
                                    qtyScaled = q,
                                    ratePaise = r,
                                    discountPaise = 0L,
                                    gstRateBps = p.gstRateBps
                                )
                            )
                        }) { Text("Add") }
                    }

                    lines.forEachIndexed { idx, l ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${l.description} • ${QuantityScale.formatScaled(l.qtyScaled)} x ${MoneyFormatter.formatPaise(l.ratePaise)}", style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { lines.removeAt(idx) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove line")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = chargesText,
                        onValueChange = { chargesText = it },
                        label = { Text("Additional Freight/Charges (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = receiveNow, onCheckedChange = { receiveNow = it })
                        Text("Receive stock immediately (else save as Draft)")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sup = selectedSupplier ?: return@Button
                    val isInter = sup.stateCode.isNotBlank() && sup.stateCode != storeStateCode
                    onSave(
                        CreatePurchaseCommand(
                            supplierId = sup.id,
                            supplierInvoiceNo = supplierInvNo,
                            supplierInvoiceDate = System.currentTimeMillis(),
                            isInterState = isInter,
                            additionalChargesPaise = MoneyFormatter.parseRupeesToPaise(chargesText) ?: 0L,
                            roundOffPaise = 0L,
                            receiveImmediately = receiveNow,
                            lines = lines.toList(),
                            actorUserId = actorUser.id,
                            actorName = actorUser.fullName
                        )
                    )
                },
                enabled = selectedSupplier != null && lines.isNotEmpty()
            ) { Text("Save Purchase") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
