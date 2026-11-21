package com.example.retailx.feature.accounts

import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(
    container: AppContainer,
    currentUser: User?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) } // 0 = Wallets & Ledger, 1 = Expenses, 2 = Cash Register

    val wallets by container.financeAndReportsRepository.observeWallets().collectAsStateWithLifecycle(initialValue = emptyList())
    val ledger by container.financeAndReportsRepository.observeFinancialTransactions(200).collectAsStateWithLifecycle(initialValue = emptyList())
    val expCats by container.financeAndReportsRepository.observeExpenseCategories().collectAsStateWithLifecycle(initialValue = emptyList())
    val expenses by container.financeAndReportsRepository.observeExpenses(200).collectAsStateWithLifecycle(initialValue = emptyList())
    val openShift by container.financeAndReportsRepository.observeOpenShift().collectAsStateWithLifecycle(initialValue = null)
    val shifts by container.financeAndReportsRepository.observeCashRegisterShifts().collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val canManageAccounts = currentUser?.permissions?.contains(PermissionCode.ACCOUNTS_MANAGE.code) == true
    val canManageRegister = currentUser?.permissions?.contains(PermissionCode.CASH_REGISTER_MANAGE.code) == true

    var showTransferDialog by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var showOpenShiftDialog by remember { mutableStateOf(false) }
    var showCloseShiftDialog by remember { mutableStateOf(false) }
    var showCashMoveDialog by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Accounts, Expenses & Cash Register",
                subtitle = "Total liquid balance: ${MoneyFormatter.formatPaise(wallets.sumOf { it.currentBalancePaise })}",
                trailing = {
                    if (canManageAccounts) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showCashMoveDialog = true }) { Text("Cash In/Out") }
                            OutlinedButton(onClick = { showTransferDialog = true }) { Text("Transfer") }
                            Button(onClick = { showExpenseDialog = true }, modifier = Modifier.testTag("accounts_add_expense_button")) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Expense")
                            }
                        }
                    }
                }
            )

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Wallets & Ledger") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Expenses (${expenses.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Cash Register") })
            }

            when (tab) {
                0 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        wallets.forEach { w ->
                            MetricCard(
                                title = w.name,
                                value = MoneyFormatter.formatPaise(w.currentBalancePaise),
                                subtitle = w.type.label,
                                icon = Icons.Filled.AccountBalanceWallet,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text("Wallet Ledger", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ledger, key = { it.id }) { tx ->
                            val isIn = tx.direction == FinancialDirection.IN
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tx.description, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${tx.walletName} • ${tx.sourceType} • ${DateTimeUtils.formatDateTime(tx.createdAt, store?.timezone ?: "Asia/Kolkata")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        "${if (isIn) "+" else "-"}${MoneyFormatter.formatPaise(tx.amountPaise)}",
                                        style = RetailXMono.medium,
                                        color = if (isIn) LocalSemanticColors.current.success else LocalSemanticColors.current.error
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (expenses.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Receipt,
                            title = "No expenses recorded",
                            message = "Record store expenses like rent, freight, or supplies from any wallet."
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(expenses, key = { it.id }) { exp ->
                                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${exp.categoryName} • ${exp.payee}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${exp.walletName} • ${exp.note.ifBlank { "No note" }} • ${DateTimeUtils.formatDateTime(exp.createdAt, store?.timezone ?: "Asia/Kolkata")}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(MoneyFormatter.formatPaise(exp.amountPaise), style = RetailXMono.medium)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val active = openShift
                            if (active == null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("No Active Cash Register Shift", style = MaterialTheme.typography.titleMedium)
                                        Text("Open a shift with starting cash float before cashier operations.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (canManageRegister) {
                                        Button(onClick = { showOpenShiftDialog = true }, modifier = Modifier.testTag("register_open_button")) {
                                            Text("Open Shift")
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Active Shift: ${active.userName}", style = MaterialTheme.typography.titleMedium)
                                        Text("Opened: ${DateTimeUtils.formatDateTime(active.openedAt, store?.timezone ?: "Asia/Kolkata")}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    StatusBadge("OPEN", BadgeTone.SUCCESS)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MetricCard("Opening Float", MoneyFormatter.formatPaise(active.openingCashPaise), modifier = Modifier.weight(1f))
                                    MetricCard("Cash Sales", MoneyFormatter.formatPaise(active.cashSalesPaise), modifier = Modifier.weight(1f))
                                    MetricCard("Refunds + Exp", MoneyFormatter.formatPaise(active.cashRefundsPaise + active.cashExpensesPaise), modifier = Modifier.weight(1f))
                                    MetricCard("Expected Drawer", MoneyFormatter.formatPaise(active.expectedCashPaise), modifier = Modifier.weight(1f))
                                }
                                if (canManageRegister) {
                                    Button(
                                        onClick = { showCloseShiftDialog = true },
                                        modifier = Modifier.testTag("register_close_button")
                                    ) { Text("Close & Reconcile Shift") }
                                }
                            }
                        }
                    }

                    Text("Shift History", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shifts, key = { it.id }) { sh ->
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${sh.userName} • Opened ${DateTimeUtils.formatDateTime(sh.openedAt, store?.timezone ?: "Asia/Kolkata")}", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "Expected: ${MoneyFormatter.formatPaise(sh.expectedCashPaise)} | Counted: ${sh.countedCashPaise?.let { MoneyFormatter.formatPaise(it) } ?: "Open"} | Var: ${sh.variancePaise?.let { MoneyFormatter.formatPaise(it) } ?: "—"}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    OutlinedButton(onClick = {
                                        val st = store ?: return@OutlinedButton
                                        scope.launch {
                                            val pdf = container.pdfGenerator.generateShiftReportPdf(st, sh)
                                            context.startActivity(Intent.createChooser(container.pdfGenerator.buildShareIntent(pdf), "Share Shift PDF"))
                                        }
                                    }) { Text("PDF") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExpenseDialog && currentUser != null) {
        var catId by remember { mutableStateOf(expCats.firstOrNull()?.id.orEmpty()) }
        var walletId by remember { mutableStateOf(wallets.firstOrNull { it.active }?.id.orEmpty()) }
        var payee by remember { mutableStateOf("") }
        var amtText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showExpenseDialog = false },
            title = { Text("Record Expense Voucher") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        expCats.take(3).forEach { c ->
                            FilterChip(selected = catId == c.id, onClick = { catId = c.id }, label = { Text(c.name) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wallets.filter { it.active }.take(3).forEach { w ->
                            FilterChip(selected = walletId == w.id, onClick = { walletId = w.id }, label = { Text(w.name) })
                        }
                    }
                    OutlinedTextField(value = payee, onValueChange = { payee = it }, label = { Text("Payee") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = amtText,
                        onValueChange = { amtText = it },
                        label = { Text("Amount (₹) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = MoneyFormatter.parseRupeesToPaise(amtText) ?: 0L
                    scope.launch {
                        val res = container.financeAndReportsRepository.recordExpense(catId, walletId, payee, amt, note, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> { showExpenseDialog = false; statusMsg = "Expense recorded" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Save Expense") }
            },
            dismissButton = { TextButton(onClick = { showExpenseDialog = false }) { Text("Cancel") } }
        )
    }

    if (showTransferDialog && currentUser != null && wallets.size >= 2) {
        var fromId by remember { mutableStateOf(wallets[0].id) }
        var toId by remember { mutableStateOf(wallets[1].id) }
        var amtText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Wallet-to-Wallet Transfer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From Wallet", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wallets.take(3).forEach { w -> FilterChip(selected = fromId == w.id, onClick = { fromId = w.id }, label = { Text(w.name) }) }
                    }
                    Text("To Wallet", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wallets.take(3).forEach { w -> FilterChip(selected = toId == w.id, onClick = { toId = w.id }, label = { Text(w.name) }) }
                    }
                    OutlinedTextField(
                        value = amtText,
                        onValueChange = { amtText = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = MoneyFormatter.parseRupeesToPaise(amtText) ?: 0L
                    scope.launch {
                        val res = container.financeAndReportsRepository.transferBetweenWallets(fromId, toId, amt, note, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> { showTransferDialog = false; statusMsg = "Transfer posted" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Transfer") }
            },
            dismissButton = { TextButton(onClick = { showTransferDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCashMoveDialog && currentUser != null) {
        var walletId by remember { mutableStateOf(wallets.firstOrNull { it.type == WalletType.CASH }?.id ?: wallets.firstOrNull()?.id.orEmpty()) }
        var isDeposit by remember { mutableStateOf(true) }
        var amtText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCashMoveDialog = false },
            title = { Text("Cash Deposit / Withdrawal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = isDeposit, onClick = { isDeposit = true }, label = { Text("Deposit (+)") })
                        FilterChip(selected = !isDeposit, onClick = { isDeposit = false }, label = { Text("Withdraw (-)") })
                    }
                    OutlinedTextField(
                        value = amtText,
                        onValueChange = { amtText = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Reason / Note *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = MoneyFormatter.parseRupeesToPaise(amtText) ?: 0L
                    scope.launch {
                        val res = container.financeAndReportsRepository.recordCashMovement(
                            walletId,
                            if (isDeposit) FinancialDirection.IN else FinancialDirection.OUT,
                            amt, note, currentUser.id, currentUser.fullName
                        )
                        when (res) {
                            is AppResult.Success -> { showCashMoveDialog = false; statusMsg = "Cash movement recorded" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Post") }
            },
            dismissButton = { TextButton(onClick = { showCashMoveDialog = false }) { Text("Cancel") } }
        )
    }

    if (showOpenShiftDialog && currentUser != null) {
        var openingText by remember { mutableStateOf("2000") }
        AlertDialog(
            onDismissRequest = { showOpenShiftDialog = false },
            title = { Text("Open Cash Register Shift") },
            text = {
                OutlinedTextField(
                    value = openingText,
                    onValueChange = { openingText = it },
                    label = { Text("Opening Cash Float (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val paise = MoneyFormatter.parseRupeesToPaise(openingText) ?: 0L
                    scope.launch {
                        val res = container.financeAndReportsRepository.openCashRegister(paise, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> { showOpenShiftDialog = false; statusMsg = "Cash register opened" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Open Shift") }
            },
            dismissButton = { TextButton(onClick = { showOpenShiftDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCloseShiftDialog && openShift != null && currentUser != null) {
        var countedText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(openShift!!.expectedCashPaise)) }
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCloseShiftDialog = false },
            title = { Text("Close & Reconcile Shift") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Expected Closing Cash: ${MoneyFormatter.formatPaise(openShift!!.expectedCashPaise)}", style = RetailXMono.medium)
                    OutlinedTextField(
                        value = countedText,
                        onValueChange = { countedText = it },
                        label = { Text("Counted Cash in Drawer (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Variance Explanation (if any)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val counted = MoneyFormatter.parseRupeesToPaise(countedText) ?: 0L
                    scope.launch {
                        val res = container.financeAndReportsRepository.closeCashRegister(
                            shiftId = openShift!!.id,
                            countedCashPaise = counted,
                            varianceReason = reason,
                            approvedBy = currentUser.fullName,
                            actorUserId = currentUser.id,
                            actorName = currentUser.fullName
                        )
                        when (res) {
                            is AppResult.Success -> { showCloseShiftDialog = false; statusMsg = "Shift closed & reconciled" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Close Shift") }
            },
            dismissButton = { TextButton(onClick = { showCloseShiftDialog = false }) { Text("Cancel") } }
        )
    }
}
