package com.example.retailx.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.IndianStates
import com.example.retailx.core.datastore.AppPreferencesStore
import com.example.retailx.core.model.User
import com.example.retailx.domain.repository.StoreRepository
import com.example.retailx.lan.client.LanApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    storeRepository: StoreRepository,
    preferencesStore: AppPreferencesStore,
    lanApiClient: LanApiClient,
    onSetupComplete: (User) -> Unit,
    onLanPairedComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var modeTab by remember { mutableIntStateOf(0) } // 0 = New Standalone/Host Store, 1 = Join LAN Host

    var storeName by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var selectedState by remember { mutableStateOf(IndianStates.findByCode("27") ?: IndianStates.all.first()) }
    var stateExpanded by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var invoicePrefix by remember { mutableStateOf("INV") }
    var adminUsername by remember { mutableStateOf("admin") }
    var adminFullName by remember { mutableStateOf("Store Owner") }
    var adminPassword by remember { mutableStateOf("") }
    var adminPin by remember { mutableStateOf("") }
    var loadStarterCatalog by remember { mutableStateOf(true) }

    // LAN Client pairing fields
    var hostUrl by remember { mutableStateOf("http://192.168.1.10:8443") }
    var pairingCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Counter Tablet 2") }

    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PointOfSale,
                            contentDescription = "RetailX POS",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(32.dp)
                        )
                    }
                    Column {
                        Text("Welcome to RetailX POS", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Offline-first Indian GST billing, inventory & LAN multi-device store management",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TabRow(selectedTabIndex = modeTab) {
                    Tab(
                        selected = modeTab == 0,
                        onClick = { modeTab = 0; errorMsg = null },
                        text = { Text("Create Store (Standalone / Host)") },
                        modifier = Modifier.testTag("onboarding_tab_new_store")
                    )
                    Tab(
                        selected = modeTab == 1,
                        onClick = { modeTab = 1; errorMsg = null },
                        text = { Text("Pair as LAN Client") },
                        modifier = Modifier.testTag("onboarding_tab_lan_client")
                    )
                }

                if (errorMsg != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = errorMsg!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (modeTab == 0) {
                    Card {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Store Profile & Tax Setup", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = storeName,
                                onValueChange = { storeName = it },
                                label = { Text("Store Name *") },
                                placeholder = { Text("e.g., Shree Ganesh Supermart") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_store_name")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = gstin,
                                    onValueChange = { gstin = it.uppercase() },
                                    label = { Text("GSTIN (Optional)") },
                                    placeholder = { Text("27AAAAA0000A1Z5") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("setup_gstin")
                                )
                                ExposedDropdownMenuBox(
                                    expanded = stateExpanded,
                                    onExpandedChange = { stateExpanded = !stateExpanded },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = selectedState.label,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Store State") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(stateExpanded) },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = stateExpanded,
                                        onDismissRequest = { stateExpanded = false }
                                    ) {
                                        IndianStates.all.forEach { st ->
                                            DropdownMenuItem(
                                                text = { Text(st.label) },
                                                onClick = {
                                                    selectedState = st
                                                    stateExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text("Store Address") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = phone,
                                    onValueChange = { phone = it },
                                    label = { Text("Phone") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = invoicePrefix,
                                    onValueChange = { invoicePrefix = it.uppercase() },
                                    label = { Text("Invoice Prefix") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.6f)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("Owner / Admin Account", style = MaterialTheme.typography.titleMedium)

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = adminUsername,
                                    onValueChange = { adminUsername = it },
                                    label = { Text("Admin Username *") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("setup_admin_username")
                                )
                                OutlinedTextField(
                                    value = adminFullName,
                                    onValueChange = { adminFullName = it },
                                    label = { Text("Full Name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = adminPassword,
                                    onValueChange = { adminPassword = it },
                                    label = { Text("Password (min 4 chars) *") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("setup_admin_password")
                                )
                                OutlinedTextField(
                                    value = adminPin,
                                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) adminPin = it },
                                    label = { Text("Quick PIN (4-6 digits)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("setup_admin_pin")
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = loadStarterCatalog,
                                    onCheckedChange = { loadStarterCatalog = it },
                                    modifier = Modifier.testTag("setup_starter_catalog_checkbox")
                                )
                                Column {
                                    Text("Load Indian Retail Starter Pack (10 sample GST products)", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Includes sample HSN codes, 0%/5%/12%/18% GST rates & opening stock. Uncheck to start empty.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (!IndianStates.isValidGstin(gstin)) {
                                        errorMsg = "GSTIN format looks invalid (expected 15-character Indian GSTIN)"
                                        return@Button
                                    }
                                    isBusy = true
                                    errorMsg = null
                                    scope.launch {
                                        val res = storeRepository.setupInitialStore(
                                            storeName = storeName,
                                            gstin = gstin,
                                            stateCode = selectedState.code,
                                            address = address,
                                            phone = phone,
                                            email = email,
                                            invoicePrefix = invoicePrefix,
                                            adminUsername = adminUsername,
                                            adminFullName = adminFullName,
                                            adminPassword = adminPassword,
                                            adminPin = adminPin,
                                            loadStarterCatalog = loadStarterCatalog
                                        )
                                        isBusy = false
                                        when (res) {
                                            is AppResult.Success -> onSetupComplete(res.data)
                                            is AppResult.Failure -> errorMsg = res.error.userMessage
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("setup_complete_button")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Initialize RetailX Store")
                                }
                            }
                        }
                    }
                } else {
                    Card {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Connect to RetailX LAN Host", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "On your Host tablet, open Settings -> LAN Multi-Device, start the Host service, and generate a 6-digit pairing code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = hostUrl,
                                onValueChange = { hostUrl = it },
                                label = { Text("Host Address (http://IP:Port)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = pairingCode,
                                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pairingCode = it },
                                label = { Text("6-Digit Pairing Code") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = deviceName,
                                onValueChange = { deviceName = it },
                                label = { Text("This Tablet Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    isBusy = true
                                    errorMsg = null
                                    scope.launch {
                                        val res = lanApiClient.completePairing(hostUrl, pairingCode, deviceName)
                                        isBusy = false
                                        when (res) {
                                            is AppResult.Success -> {
                                                preferencesStore.setLanClientConfig(
                                                    baseUrl = hostUrl.trim(),
                                                    deviceId = res.data.deviceId,
                                                    deviceToken = res.data.deviceToken,
                                                    deviceName = res.data.deviceName
                                                )
                                                onLanPairedComplete()
                                            }
                                            is AppResult.Failure -> errorMsg = res.error.userMessage
                                        }
                                    }
                                },
                                enabled = !isBusy && pairingCode.length == 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Text("Pair & Connect to Host")
                            }
                        }
                    }
                }
            }
        }
    }
}
