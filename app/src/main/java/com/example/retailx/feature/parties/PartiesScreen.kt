package com.example.retailx.feature.parties

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.IndianStates
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import kotlinx.coroutines.launch

@Composable
fun PartiesScreen(
    container: AppContainer,
    currentUser: User?
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) } // 0 = Customers, 1 = Suppliers
    var query by remember { mutableStateOf("") }

    val customers by container.partiesAndPurchasesRepository.observeCustomers(query).collectAsStateWithLifecycle(initialValue = emptyList())
    val suppliers by container.partiesAndPurchasesRepository.observeSuppliers(query).collectAsStateWithLifecycle(initialValue = emptyList())
    val wallets by container.financeAndReportsRepository.observeWallets().collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val canManage = currentUser?.permissions?.contains(PermissionCode.PARTIES_MANAGE.code) == true
    var showCustomerDialog by remember { mutableStateOf(false) }
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var collectingCustomer by remember { mutableStateOf<Customer?>(null) }
    var showSupplierDialog by remember { mutableStateOf(false) }
    var editingSupplier by remember { mutableStateOf<Supplier?>(null) }
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
                    onClick = {
                        if (tab == 0) {
                            editingCustomer = null; showCustomerDialog = true
                        } else {
                            editingSupplier = null; showSupplierDialog = true
                        }
                    },
                    icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                    text = { Text(if (tab == 0) "Add Customer" else "Add Supplier") },
                    modifier = Modifier.testTag("parties_add_button")
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
                title = "Customers & Suppliers",
                subtitle = "Credit receivables, GSTIN state codes, and supplier payables"
            )

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Customers (${customers.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Suppliers (${suppliers.size})") })
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search name, mobile, GSTIN...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (tab == 0) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(customers, key = { it.id }) { c ->
                        Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                                        StatusBadge(c.customerType.label, BadgeTone.INFO)
                                    }
                                    Text(
                                        listOfNotNull(
                                            c.mobile.takeIf { it.isNotBlank() },
                                            "State: ${c.stateCode}",
                                            c.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        if (c.currentBalancePaise > 0L) "Due ${MoneyFormatter.formatPaise(c.currentBalancePaise)}" else "Settled",
                                        style = RetailXMono.medium,
                                        color = if (c.currentBalancePaise > 0L) LocalSemanticColors.current.warning else LocalSemanticColors.current.success
                                    )
                                    if (c.creditLimitPaise > 0L) {
                                        Text("Limit ${MoneyFormatter.formatPaiseCompact(c.creditLimitPaise)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (canManage && !c.isWalkIn) {
                                    Spacer(Modifier.width(8.dp))
                                    if (c.currentBalancePaise > 0L) {
                                        FilledTonalButton(onClick = { collectingCustomer = c }) {
                                            Text("Collect")
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    IconButton(onClick = { editingCustomer = c; showCustomerDialog = true }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit customer")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (suppliers.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Filled.Business,
                        title = "No suppliers added",
                        message = "Add suppliers to record purchases and track payables."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(suppliers, key = { it.id }) { s ->
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            listOfNotNull(
                                                s.contactPerson.takeIf { it.isNotBlank() },
                                                s.mobile.takeIf { it.isNotBlank() },
                                                "State ${s.stateCode}",
                                                s.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
                                            ).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Payable ${MoneyFormatter.formatPaise(s.currentPayablePaise)}",
                                            style = RetailXMono.medium,
                                            color = if (s.currentPayablePaise > 0L) LocalSemanticColors.current.warning else LocalSemanticColors.current.success
                                        )
                                    }
                                    if (canManage) {
                                        IconButton(onClick = { editingSupplier = s; showSupplierDialog = true }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Edit supplier")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomerDialog && currentUser != null) {
        val existing = editingCustomer
        var name by remember { mutableStateOf(existing?.name.orEmpty()) }
        var mobile by remember { mutableStateOf(existing?.mobile.orEmpty()) }
        var gstin by remember { mutableStateOf(existing?.gstin.orEmpty()) }
        var stateCode by remember { mutableStateOf(existing?.stateCode ?: store?.stateCode ?: "27") }
        var address by remember { mutableStateOf(existing?.address.orEmpty()) }
        var creditLimitText by remember { mutableStateOf(existing?.let { MoneyFormatter.paiseToPlainRupees(it.creditLimitPaise) } ?: "0") }

        val duplicateMobile = mobile.isNotBlank() && customers.any { it.mobile == mobile.trim() && it.id != existing?.id }

        AlertDialog(
            onDismissRequest = { showCustomerDialog = false },
            title = { Text(if (existing == null) "Add Customer" else "Edit ${existing.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Customer Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = { mobile = it },
                        label = { Text("Mobile") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (duplicateMobile) {
                        Text("Warning: Another customer already uses this mobile number.", color = LocalSemanticColors.current.warning, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = gstin, onValueChange = { gstin = it.uppercase() }, label = { Text("GSTIN") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = stateCode, onValueChange = { stateCode = it.take(2) }, label = { Text("State Code") }, singleLine = true, modifier = Modifier.weight(0.5f))
                    }
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = creditLimitText,
                        onValueChange = { creditLimitText = it },
                        label = { Text("Credit Limit (₹, 0 = none)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!IndianStates.isValidGstin(gstin)) {
                        statusMsg = "Invalid GSTIN format"
                        return@Button
                    }
                    val model = Customer(
                        id = existing?.id.orEmpty(),
                        name = name,
                        mobile = mobile,
                        email = existing?.email.orEmpty(),
                        address = address,
                        stateCode = stateCode.ifBlank { "27" },
                        gstin = gstin,
                        customerType = if (gstin.isNotBlank()) CustomerType.BUSINESS else CustomerType.RETAIL,
                        openingBalancePaise = existing?.openingBalancePaise ?: 0L,
                        currentBalancePaise = existing?.currentBalancePaise ?: 0L,
                        creditLimitPaise = MoneyFormatter.parseRupeesToPaise(creditLimitText) ?: 0L,
                        defaultDiscountBps = existing?.defaultDiscountBps ?: 0,
                        isWalkIn = false,
                        createdAt = existing?.createdAt ?: 0L
                    )
                    scope.launch {
                        val res = container.partiesAndPurchasesRepository.upsertCustomer(model, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> { showCustomerDialog = false; statusMsg = "Saved ${res.data.name}" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCustomerDialog = false }) { Text("Cancel") } }
        )
    }

    collectingCustomer?.let { cust ->
        if (currentUser != null) {
            var amtText by remember { mutableStateOf(MoneyFormatter.paiseToPlainRupees(cust.currentBalancePaise)) }
            var walletId by remember { mutableStateOf(wallets.firstOrNull { it.active }?.id.orEmpty()) }
            var ref by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { collectingCustomer = null },
                title = { Text("Collect Payment from ${cust.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Balance Due: ${MoneyFormatter.formatPaise(cust.currentBalancePaise)}", style = RetailXMono.medium)
                        OutlinedTextField(
                            value = amtText,
                            onValueChange = { amtText = it },
                            label = { Text("Amount Collected (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            wallets.filter { it.active }.take(3).forEach { w ->
                                FilterChip(selected = walletId == w.id, onClick = { walletId = w.id }, label = { Text(w.name) })
                            }
                        }
                        OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Reference / Receipt Note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val amt = MoneyFormatter.parseRupeesToPaise(amtText) ?: 0L
                        scope.launch {
                            val res = container.partiesAndPurchasesRepository.collectCustomerPayment(
                                cust.id, walletId, amt, ref, currentUser.id, currentUser.fullName
                            )
                            when (res) {
                                is AppResult.Success -> { collectingCustomer = null; statusMsg = "Payment recorded" }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    }) { Text("Collect") }
                },
                dismissButton = { TextButton(onClick = { collectingCustomer = null }) { Text("Cancel") } }
            )
        }
    }

    if (showSupplierDialog && currentUser != null) {
        val existing = editingSupplier
        var name by remember { mutableStateOf(existing?.name.orEmpty()) }
        var contact by remember { mutableStateOf(existing?.contactPerson.orEmpty()) }
        var mobile by remember { mutableStateOf(existing?.mobile.orEmpty()) }
        var gstin by remember { mutableStateOf(existing?.gstin.orEmpty()) }
        var stateCode by remember { mutableStateOf(existing?.stateCode ?: store?.stateCode ?: "27") }
        var address by remember { mutableStateOf(existing?.address.orEmpty()) }

        AlertDialog(
            onDismissRequest = { showSupplierDialog = false },
            title = { Text(if (existing == null) "Add Supplier" else "Edit ${existing.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Supplier Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Contact Person") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = gstin, onValueChange = { gstin = it.uppercase() }, label = { Text("GSTIN") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = stateCode, onValueChange = { stateCode = it.take(2) }, label = { Text("State Code") }, singleLine = true, modifier = Modifier.weight(0.5f))
                    }
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val model = Supplier(
                        id = existing?.id.orEmpty(),
                        name = name,
                        contactPerson = contact,
                        mobile = mobile,
                        email = existing?.email.orEmpty(),
                        address = address,
                        stateCode = stateCode.ifBlank { "27" },
                        gstin = gstin,
                        notes = existing?.notes.orEmpty(),
                        openingBalancePaise = existing?.openingBalancePaise ?: 0L,
                        currentPayablePaise = existing?.currentPayablePaise ?: 0L,
                        createdAt = existing?.createdAt ?: 0L
                    )
                    scope.launch {
                        val res = container.partiesAndPurchasesRepository.upsertSupplier(model, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> { showSupplierDialog = false; statusMsg = "Saved ${res.data.name}" }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSupplierDialog = false }) { Text("Cancel") } }
        )
    }
}
