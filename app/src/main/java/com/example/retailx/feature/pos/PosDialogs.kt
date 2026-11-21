package com.example.retailx.feature.pos

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.BadgeTone
import com.example.retailx.core.designsystem.LocalSemanticColors
import com.example.retailx.core.designsystem.RetailXMono
import com.example.retailx.core.designsystem.StatusBadge
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CompleteSalePaymentInput
import com.example.retailx.platform.pdf.PdfDocumentGenerator
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutDialog(
    state: PosUiState,
    onDismiss: () -> Unit,
    onConfirmSale: (List<CompleteSalePaymentInput>) -> Unit
) {
    val grandTotal = state.computedTotals.grandTotalPaise
    var isSplitMode by remember { mutableStateOf(false) }
    var selectedMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var cashTenderedInput by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(grandTotal)) }
    var referenceInput by remember { mutableStateOf("") }

    // Split payment amounts per method
    val splitAmounts = remember {
        mutableStateMapOf(
            PaymentMethod.CASH to MoneyFormatter.paiseToPlainRupees(grandTotal),
            PaymentMethod.UPI to "",
            PaymentMethod.CARD to "",
            PaymentMethod.BANK to "",
            PaymentMethod.CREDIT to ""
        )
    }

    val singleTenderPaise = MoneyFormatter.parseRupeesToPaise(cashTenderedInput) ?: 0L
    val changeDue = if (!isSplitMode && selectedMethod == PaymentMethod.CASH) {
        (singleTenderPaise - grandTotal).coerceAtLeast(0L)
    } else 0L

    val splitPayments = PaymentMethod.entries.mapNotNull { method ->
        val amt = MoneyFormatter.parseRupeesToPaise(splitAmounts[method].orEmpty()) ?: 0L
        if (amt > 0L) {
            val wallet = state.wallets.firstOrNull {
                when (method) {
                    PaymentMethod.CASH -> it.type == WalletType.CASH
                    PaymentMethod.UPI -> it.type == WalletType.UPI
                    PaymentMethod.CARD -> it.type == WalletType.CARD_CLEARING
                    PaymentMethod.BANK -> it.type == WalletType.BANK
                    PaymentMethod.CREDIT -> false
                }
            }
            CompleteSalePaymentInput(wallet?.id, method, amt, referenceInput)
        } else null
    }
    val splitTotalPaise = splitPayments.sumOf { it.amountPaise }
    val splitRemaining = grandTotal - splitTotalPaise

    val canSubmit = if (isSplitMode) {
        splitTotalPaise >= grandTotal
    } else {
        if (selectedMethod == PaymentMethod.CASH) singleTenderPaise >= grandTotal else true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Checkout")
                Text(
                    text = MoneyFormatter.formatPaise(grandTotal),
                    style = RetailXMono.large,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Customer: ${state.selectedCustomer?.name ?: "Walk-in"}", style = MaterialTheme.typography.bodyMedium)
                    FilterChip(
                        selected = isSplitMode,
                        onClick = { isSplitMode = !isSplitMode },
                        label = { Text("Split Tender") },
                        leadingIcon = { Icon(Icons.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("checkout_split_toggle")
                    )
                }

                if (!isSplitMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val canCredit = state.selectedCustomer != null && !state.selectedCustomer.isWalkIn
                        PaymentMethod.entries.forEach { method ->
                            val enabled = method != PaymentMethod.CREDIT || canCredit
                            FilterChip(
                                selected = selectedMethod == method,
                                onClick = { if (enabled) selectedMethod = method },
                                enabled = enabled,
                                label = { Text(method.label, maxLines = 1) },
                                modifier = Modifier.testTag("checkout_method_${method.name.lowercase()}")
                            )
                        }
                    }

                    if (selectedMethod == PaymentMethod.CASH) {
                        OutlinedTextField(
                            value = cashTenderedInput,
                            onValueChange = { cashTenderedInput = it },
                            label = { Text("Amount Tendered (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("checkout_cash_tendered_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Exact" to grandTotal, "+₹100" to (grandTotal + 10_000L), "₹500" to 50_000L, "₹2000" to 200_000L).forEach { (lbl, amt) ->
                                AssistChip(
                                    onClick = { cashTenderedInput = MoneyFormatter.paiseToPlainRupees(amt) },
                                    label = { Text(lbl) }
                                )
                            }
                        }
                        Surface(
                            color = LocalSemanticColors.current.successBg,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Change Due", color = LocalSemanticColors.current.success, style = MaterialTheme.typography.titleSmall)
                                Text(MoneyFormatter.formatPaise(changeDue), style = RetailXMono.medium, color = LocalSemanticColors.current.success)
                            }
                        }
                    } else if (selectedMethod != PaymentMethod.CREDIT) {
                        OutlinedTextField(
                            value = referenceInput,
                            onValueChange = { referenceInput = it },
                            label = { Text("${selectedMethod.label} Reference / UTR (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    PaymentMethod.entries.forEach { method ->
                        val enabled = method != PaymentMethod.CREDIT || (state.selectedCustomer != null && !state.selectedCustomer.isWalkIn)
                        OutlinedTextField(
                            value = splitAmounts[method].orEmpty(),
                            onValueChange = { splitAmounts[method] = it },
                            enabled = enabled,
                            label = { Text("${method.label} Amount (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Surface(
                        color = if (splitRemaining <= 0L) LocalSemanticColors.current.successBg else LocalSemanticColors.current.warningBg,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(if (splitRemaining > 0L) "Remaining" else "Change / Covered")
                            Text(MoneyFormatter.formatPaise(kotlin.math.abs(splitRemaining)), style = RetailXMono.medium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalPayments = if (isSplitMode) {
                        splitPayments
                    } else {
                        val wallet = state.wallets.firstOrNull {
                            when (selectedMethod) {
                                PaymentMethod.CASH -> it.type == WalletType.CASH
                                PaymentMethod.UPI -> it.type == WalletType.UPI
                                PaymentMethod.CARD -> it.type == WalletType.CARD_CLEARING
                                PaymentMethod.BANK -> it.type == WalletType.BANK
                                PaymentMethod.CREDIT -> false
                            }
                        }
                        val amt = if (selectedMethod == PaymentMethod.CASH) singleTenderPaise else grandTotal
                        listOf(CompleteSalePaymentInput(wallet?.id, selectedMethod, amt, referenceInput))
                    }
                    onConfirmSale(finalPayments)
                },
                enabled = canSubmit && !state.isCheckingOut,
                modifier = Modifier.testTag("checkout_confirm_button")
            ) {
                if (state.isCheckingOut) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Complete Sale")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CompletedSaleDialog(
    sale: Sale,
    generatedPdf: File?,
    pdfGenerator: PdfDocumentGenerator,
    onGeneratePdf: (InvoiceFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val file = pendingSaveFile ?: generatedPdf
        if (uri != null && file != null && file.exists()) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = LocalSemanticColors.current.success, modifier = Modifier.size(36.dp)) },
        title = { Text("Sale Completed: ${sale.invoiceNo}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Grand Total: ${MoneyFormatter.formatPaise(sale.totalPaise)}", style = RetailXMono.large)
                Text("Customer: ${sale.customerName}  |  Paid: ${MoneyFormatter.formatPaise(sale.paidPaise)}")
                if (sale.balanceDuePaise > 0L) {
                    StatusBadge("Credit Due: ${MoneyFormatter.formatPaise(sale.balanceDuePaise)}", BadgeTone.WARNING)
                }
                HorizontalDivider()
                Text("Generate / Share PDF Invoice:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InvoiceFormat.entries.forEach { fmt ->
                        FilledTonalButton(
                            onClick = { onGeneratePdf(fmt) },
                            modifier = Modifier.testTag("pdf_format_${fmt.name.lowercase()}")
                        ) {
                            Text(
                                when (fmt) {
                                    InvoiceFormat.A4 -> "A4 PDF"
                                    InvoiceFormat.THERMAL_80MM -> "80mm"
                                    InvoiceFormat.THERMAL_58MM -> "58mm"
                                }
                            )
                        }
                    }
                }
                if (generatedPdf != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = pdfGenerator.buildShareIntent(generatedPdf)
                                context.startActivity(Intent.createChooser(intent, "Share Invoice PDF"))
                            }
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        OutlinedButton(
                            onClick = {
                                pendingSaveFile = generatedPdf
                                saveLauncher.launch(generatedPdf.name)
                            }
                        ) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save PDF")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.testTag("completed_sale_done_button")) {
                Text("New Bill")
            }
        }
    )
}

@Composable
fun HeldBillsDialog(
    heldBills: List<HeldBill>,
    timezone: String,
    onResume: (HeldBill) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Held / Draft Bills (${heldBills.size})") },
        text = {
            if (heldBills.isEmpty()) {
                Text("No held bills right now.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(heldBills, key = { it.id }) { bill ->
                        Card {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bill.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${bill.customerName} • ${bill.items.size} items • ${DateTimeUtils.formatShortTime(bill.updatedAt, timezone)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(MoneyFormatter.formatPaise(bill.totalPreviewPaise), style = RetailXMono.medium)
                                }
                                IconButton(onClick = { onDelete(bill.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete held bill")
                                }
                                Button(onClick = { onResume(bill); onDismiss() }) {
                                    Text("Resume")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun CustomerPickerDialog(
    customers: List<Customer>,
    onSelect: (Customer) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = customers.filter {
        query.isBlank() || it.name.contains(query, true) || it.mobile.contains(query, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or mobile") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(filtered, key = { it.id }) { cust ->
                        ListItem(
                            headlineContent = { Text(cust.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        cust.mobile.takeIf { it.isNotBlank() },
                                        if (cust.currentBalancePaise > 0L) "Due: ${MoneyFormatter.formatPaise(cust.currentBalancePaise)}" else null
                                    ).joinToString(" • ").ifBlank { "Walk-in / Retail" }
                                )
                            },
                            modifier = Modifier.clickable {
                                onSelect(cust)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun EditCartLineDialog(
    line: CartItemInput,
    canOverridePrice: Boolean,
    canDiscount: Boolean,
    onSave: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var rateText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(line.unitPricePaise)) }
    var discText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(line.lineDiscountPaise)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Line: ${line.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Qty: ${QuantityScale.formatScaled(line.qtyScaled)} ${line.unitShortName} | GST: ${line.gstRateBps / 100}%")
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it },
                    enabled = canOverridePrice,
                    label = { Text(if (canOverridePrice) "Unit Rate (₹)" else "Unit Rate (Locked by role)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = discText,
                    onValueChange = { discText = it },
                    enabled = canDiscount,
                    label = { Text(if (canDiscount) "Line Discount (₹)" else "Line Discount (Locked by role)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val r = MoneyFormatter.parseRupeesToPaise(rateText) ?: line.unitPricePaise
                    val d = MoneyFormatter.parseRupeesToPaise(discText) ?: line.lineDiscountPaise
                    onSave(r, d)
                    onDismiss()
                }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
