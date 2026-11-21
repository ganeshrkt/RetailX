package com.example.retailx.feature.sales

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.retailx.domain.repository.ProcessSalesReturnCommand
import com.example.retailx.domain.repository.ReturnLineInput
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun SalesScreen(
    container: AppContainer,
    currentUser: User?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<SaleStatus?>(null) }

    val allSales by container.salesRepository.observeSales(searchQuery).collectAsStateWithLifecycle(initialValue = emptyList())
    val returns by container.salesRepository.observeSalesReturns().collectAsStateWithLifecycle(initialValue = emptyList())
    val wallets by container.financeAndReportsRepository.observeWallets().collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val filteredSales = allSales.filter { statusFilter == null || it.status == statusFilter }
    var selectedSaleId by remember { mutableStateOf<String?>(null) }
    val selectedSale = filteredSales.firstOrNull { it.id == selectedSaleId } ?: filteredSales.firstOrNull()

    var showReturnDialog by remember { mutableStateOf(false) }
    var showVoidDialog by remember { mutableStateOf(false) }
    var activePdfFile by remember { mutableStateOf<File?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val canReturn = currentUser?.permissions?.contains(PermissionCode.SALES_RETURN.code) == true
    val canVoid = currentUser?.permissions?.contains(PermissionCode.SALES_VOID.code) == true

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val f = activePdfFile
        if (uri != null && f != null && f.exists()) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            statusMsg = "PDF saved successfully"
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        val m = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        statusMsg = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            val isWide = maxWidth >= 800.dp

            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(
                    title = "Sales Register & Returns",
                    subtitle = "${filteredSales.size} invoices • ${returns.size} returns recorded"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search invoice #, customer, cashier...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sales_search_input")
                    )
                    FilterChip(
                        selected = statusFilter == null,
                        onClick = { statusFilter = null },
                        label = { Text("All") }
                    )
                    SaleStatus.entries.forEach { st ->
                        FilterChip(
                            selected = statusFilter == st,
                            onClick = { statusFilter = if (statusFilter == st) null else st },
                            label = { Text(st.label) }
                        )
                    }
                }

                if (filteredSales.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Filled.ReceiptLong,
                        title = "No sales found",
                        message = "Completed POS invoices will appear here for reprinting, returns, and audit review."
                    )
                } else if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SalesListPane(
                            sales = filteredSales,
                            selectedId = selectedSale?.id,
                            timezone = store?.timezone ?: "Asia/Kolkata",
                            onSelect = { selectedSaleId = it.id },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        if (selectedSale != null) {
                            SaleDetailPane(
                                sale = selectedSale,
                                timezone = store?.timezone ?: "Asia/Kolkata",
                                canReturn = canReturn && selectedSale.status != SaleStatus.VOIDED && selectedSale.status != SaleStatus.RETURNED,
                                canVoid = canVoid && selectedSale.status == SaleStatus.COMPLETED,
                                onExportPdf = { fmt, share ->
                                    val st = store ?: return@SaleDetailPane
                                    scope.launch {
                                        val file = container.pdfGenerator.generateSaleInvoicePdf(st, selectedSale, fmt)
                                        activePdfFile = file
                                        if (share) {
                                            context.startActivity(Intent.createChooser(container.pdfGenerator.buildShareIntent(file), "Share Invoice"))
                                        } else {
                                            savePdfLauncher.launch(file.name)
                                        }
                                    }
                                },
                                onOpenReturn = { showReturnDialog = true },
                                onOpenVoid = { showVoidDialog = true },
                                modifier = Modifier
                                    .weight(1.25f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredSales, key = { it.id }) { sale ->
                            SaleRowCard(
                                sale = sale,
                                selected = selectedSale?.id == sale.id,
                                timezone = store?.timezone ?: "Asia/Kolkata",
                                onClick = { selectedSaleId = sale.id }
                            )
                        }
                        if (selectedSale != null) {
                            item {
                                SaleDetailPane(
                                    sale = selectedSale,
                                    timezone = store?.timezone ?: "Asia/Kolkata",
                                    canReturn = canReturn && selectedSale.status != SaleStatus.VOIDED && selectedSale.status != SaleStatus.RETURNED,
                                    canVoid = canVoid && selectedSale.status == SaleStatus.COMPLETED,
                                    onExportPdf = { fmt, share ->
                                        val st = store ?: return@SaleDetailPane
                                        scope.launch {
                                            val file = container.pdfGenerator.generateSaleInvoicePdf(st, selectedSale, fmt)
                                            activePdfFile = file
                                            if (share) {
                                                context.startActivity(Intent.createChooser(container.pdfGenerator.buildShareIntent(file), "Share Invoice"))
                                            } else {
                                                savePdfLauncher.launch(file.name)
                                            }
                                        }
                                    },
                                    onOpenReturn = { showReturnDialog = true },
                                    onOpenVoid = { showVoidDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReturnDialog && selectedSale != null && currentUser != null) {
        SalesReturnDialog(
            sale = selectedSale,
            wallets = wallets,
            onDismiss = { showReturnDialog = false },
            onConfirm = { cmd ->
                scope.launch {
                    val res = container.salesRepository.processSalesReturn(cmd)
                    when (res) {
                        is AppResult.Success -> {
                            showReturnDialog = false
                            statusMsg = "Return ${res.data.returnNo} processed (${MoneyFormatter.formatPaise(res.data.totalRefundPaise)})"
                            store?.let { st ->
                                val pdf = container.pdfGenerator.generateCreditNotePdf(st, res.data)
                                context.startActivity(Intent.createChooser(container.pdfGenerator.buildShareIntent(pdf), "Share Credit Note PDF"))
                            }
                        }
                        is AppResult.Failure -> statusMsg = res.error.userMessage
                    }
                }
            },
            actorUser = currentUser
        )
    }

    if (showVoidDialog && selectedSale != null && currentUser != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showVoidDialog = false },
            title = { Text("Void Invoice ${selectedSale.invoiceNo}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voiding reverses stock and wallet postings and marks this invoice as VOIDED (audit preserved).")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Void Reason *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = container.salesRepository.voidSale(selectedSale.id, reason, currentUser.id, currentUser.fullName)
                            when (res) {
                                is AppResult.Success -> {
                                    showVoidDialog = false
                                    statusMsg = "Invoice ${selectedSale.invoiceNo} voided"
                                }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Confirm Void") }
            },
            dismissButton = { TextButton(onClick = { showVoidDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SalesListPane(
    sales: List<Sale>,
    selectedId: String?,
    timezone: String,
    onSelect: (Sale) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sales, key = { it.id }) { sale ->
            SaleRowCard(
                sale = sale,
                selected = sale.id == selectedId,
                timezone = timezone,
                onClick = { onSelect(sale) }
            )
        }
    }
}

@Composable
private fun SaleRowCard(
    sale: Sale,
    selected: Boolean,
    timezone: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sale.invoiceNo, style = RetailXMono.medium)
                Text(
                    "${sale.customerName} • ${DateTimeUtils.formatDateTime(sale.createdAt, timezone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(MoneyFormatter.formatPaise(sale.totalPaise), style = RetailXMono.medium)
                StatusBadge(
                    text = sale.status.label,
                    tone = when (sale.status) {
                        SaleStatus.COMPLETED -> BadgeTone.SUCCESS
                        SaleStatus.VOIDED -> BadgeTone.ERROR
                        else -> BadgeTone.WARNING
                    }
                )
            }
        }
    }
}

@Composable
private fun SaleDetailPane(
    sale: Sale,
    timezone: String,
    canReturn: Boolean,
    canVoid: Boolean,
    onExportPdf: (InvoiceFormat, Boolean) -> Unit,
    onOpenReturn: () -> Unit,
    onOpenVoid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(sale.invoiceNo, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${sale.customerName} • Cashier: ${sale.cashierName} • ${DateTimeUtils.formatDateTime(sale.createdAt, timezone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(
                    text = sale.status.label,
                    tone = when (sale.status) {
                        SaleStatus.COMPLETED -> BadgeTone.SUCCESS
                        SaleStatus.VOIDED -> BadgeTone.ERROR
                        else -> BadgeTone.WARNING
                    }
                )
            }

            if (!sale.voidReason.isNullOrBlank()) {
                Text("Void Reason: ${sale.voidReason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sale.items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.descriptionSnapshot, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${QuantityScale.formatScaled(item.qtyScaled)} ${item.unitSnapshot} x ${MoneyFormatter.formatPaise(item.ratePaise)} • HSN ${item.hsnSnapshot} • GST ${item.gstRateBps / 100}%" +
                                    if (item.returnedQtyScaled > 0L) " (Returned: ${QuantityScale.formatScaled(item.returnedQtyScaled)})" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(MoneyFormatter.formatPaise(item.totalPaise), style = RetailXMono.medium)
                    }
                }
            }

            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Taxable: ${MoneyFormatter.formatPaise(sale.taxablePaise)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (sale.isInterState) "IGST: ${MoneyFormatter.formatPaise(sale.igstPaise)}"
                    else "CGST ${MoneyFormatter.formatPaise(sale.cgstPaise)} + SGST ${MoneyFormatter.formatPaise(sale.sgstPaise)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Grand Total", style = MaterialTheme.typography.titleMedium)
                Text(MoneyFormatter.formatPaise(sale.totalPaise), style = RetailXMono.large, color = MaterialTheme.colorScheme.primary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onExportPdf(InvoiceFormat.A4, true) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("A4 PDF")
                }
                OutlinedButton(onClick = { onExportPdf(InvoiceFormat.THERMAL_80MM, true) }) {
                    Text("80mm")
                }
                OutlinedButton(onClick = { onExportPdf(InvoiceFormat.A4, false) }) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
                Spacer(Modifier.weight(1f))
                if (canReturn) {
                    FilledTonalButton(onClick = onOpenReturn, modifier = Modifier.testTag("sale_return_button")) {
                        Text("Return")
                    }
                }
                if (canVoid) {
                    OutlinedButton(onClick = onOpenVoid, modifier = Modifier.testTag("sale_void_button")) {
                        Text("Void", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesReturnDialog(
    sale: Sale,
    wallets: List<Wallet>,
    actorUser: User,
    onDismiss: () -> Unit,
    onConfirm: (ProcessSalesReturnCommand) -> Unit
) {
    var reason by remember { mutableStateOf("Customer return") }
    var refundMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var restock by remember { mutableStateOf(true) }
    val returnQtys = remember {
        mutableStateMapOf<String, String>().apply {
            sale.items.forEach { put(it.id, "0") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sales Return for ${sale.invoiceNo}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sale.items.forEach { item ->
                    val maxRet = item.returnableQtyScaled
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.descriptionSnapshot, style = MaterialTheme.typography.titleSmall)
                            Text("Returnable: ${QuantityScale.formatScaled(maxRet)} ${item.unitSnapshot}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = returnQtys[item.id] ?: "0",
                            onValueChange = { returnQtys[item.id] = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.width(96.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Return Reason") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restock, onCheckedChange = { restock = it })
                    Text("Restock returned items into inventory")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.BANK).forEach { pm ->
                        FilterChip(
                            selected = refundMethod == pm,
                            onClick = { refundMethod = pm },
                            label = { Text(pm.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lines = sale.items.mapNotNull { item ->
                        val scaled = QuantityScale.parseScaled(returnQtys[item.id] ?: "0") ?: 0L
                        if (scaled > 0L) ReturnLineInput(item.id, scaled, restock) else null
                    }
                    val walletId = wallets.firstOrNull {
                        when (refundMethod) {
                            PaymentMethod.CASH -> it.type == WalletType.CASH
                            PaymentMethod.UPI -> it.type == WalletType.UPI
                            else -> it.type == WalletType.BANK
                        }
                    }?.id
                    onConfirm(
                        ProcessSalesReturnCommand(
                            idempotencyKey = UUID.randomUUID().toString(),
                            saleId = sale.id,
                            reason = reason,
                            refundMethod = refundMethod,
                            refundWalletId = walletId,
                            lines = lines,
                            actorUserId = actorUser.id,
                            actorName = actorUser.fullName
                        )
                    )
                }
            ) { Text("Process Return") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
