package com.example.retailx.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.IndianStates
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.datastore.AppPreferences
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import com.example.retailx.lan.client.LanConnectionState
import com.example.retailx.lan.host.LanHostForegroundService
import com.example.retailx.lan.host.LanHostServer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    currentUser: User?,
    onSessionRestoredOrDisconnected: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)
    val prefs by container.preferencesStore.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val hostStatus by container.lanHostServer.status.collectAsStateWithLifecycle()
    val clientState by container.lanApiClient.connectionState.collectAsStateWithLifecycle()
    val pairedDevices by container.storeAndAuthRepository.observePairedDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val backupHistory by container.backupManager.observeBackupHistory().collectAsStateWithLifecycle(initialValue = emptyList())

    val canSettings = currentUser?.permissions?.contains(PermissionCode.SETTINGS_MANAGE.code) == true
    val canLan = currentUser?.permissions?.contains(PermissionCode.LAN_MANAGE.code) == true
    val canBackup = currentUser?.permissions?.contains(PermissionCode.BACKUP_RESTORE.code) == true

    var tabIndex by remember { mutableIntStateOf(0) } // 0=Store & Tax, 1=Theme & Session, 2=LAN Multi-Device, 3=Backup & Restore
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        val m = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        statusMsg = null
    }

    // SAF launchers for backup & restore
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val actor = currentUser ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                when (val res = container.backupManager.createBackupToUri(uri, prefs.backupRetentionCount, actor.id, actor.fullName)) {
                    is AppResult.Success -> statusMsg = "Backup saved: ${res.data.fileName}"
                    is AppResult.Failure -> statusMsg = res.error.userMessage
                }
            }
        }
    }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val openRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val port = prefs.lanHostPort
        if (granted) {
            LanHostForegroundService.start(context, port)
            scope.launch { container.preferencesStore.setRuntimeMode(RuntimeMode.LAN_HOST) }
            statusMsg = "LAN Host started on port $port"
        } else {
            statusMsg = "Notification permission denied; foreground service notification may be hidden"
            LanHostForegroundService.start(context, port)
            scope.launch { container.preferencesStore.setRuntimeMode(RuntimeMode.LAN_HOST) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Settings & Administration",
                subtitle = "Store profile, GST invoice rules, theme, LAN host/client & encrypted backups"
            )

            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Store & GST") }, modifier = Modifier.testTag("settings_tab_store"))
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Appearance") }, modifier = Modifier.testTag("settings_tab_theme"))
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("LAN Sync") }, modifier = Modifier.testTag("settings_tab_lan"))
                Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Backup") }, modifier = Modifier.testTag("settings_tab_backup"))
            }

            when (tabIndex) {
                0 -> {
                    val s = store
                    if (s == null) {
                        EmptyStateCard(
                            icon = Icons.Filled.Store,
                            title = "Store settings managed on LAN Host",
                            message = "When paired as a LAN Client, store & tax configuration is managed on the Host tablet."
                        )
                    } else {
                        StoreSettingsTab(
                            store = s,
                            canEdit = canSettings,
                            onSave = { updated ->
                                val actor = currentUser ?: return@StoreSettingsTab
                                scope.launch {
                                    when (val res = container.storeAndAuthRepository.updateStoreSettings(updated, actor.id, actor.fullName)) {
                                        is AppResult.Success -> statusMsg = "Store settings saved"
                                        is AppResult.Failure -> statusMsg = res.error.userMessage
                                    }
                                }
                            }
                        )
                    }
                }

                1 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("App Theme", style = MaterialTheme.typography.titleMedium)
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        ThemeMode.entries.forEachIndexed { idx, mode ->
                                            SegmentedButton(
                                                selected = prefs.themeMode == mode,
                                                onClick = { scope.launch { container.preferencesStore.setThemeMode(mode) } },
                                                shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
                                                modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}")
                                            ) {
                                                Text(mode.label)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Staff Session Auto-Lock", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Automatically sign out idle cashiers after inactivity. Currently: ${prefs.sessionTimeoutMinutes} minutes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(15, 30, 60, 120).forEach { mins ->
                                            FilterChip(
                                                selected = prefs.sessionTimeoutMinutes == mins,
                                                onClick = { scope.launch { container.preferencesStore.setSessionTimeoutMinutes(mins) } },
                                                label = { Text("${mins}m") }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    var portStr by remember(prefs.lanHostPort) { mutableStateOf(prefs.lanHostPort.toString()) }
                    var clientHostUrl by remember(prefs.lanServerBaseUrl) {
                        mutableStateOf(prefs.lanServerBaseUrl.ifBlank { "http://192.168.1.10:8443" })
                    }
                    var pairingCodeInput by remember { mutableStateOf("") }
                    var clientDeviceName by remember(prefs.lanClientDeviceName) { mutableStateOf(prefs.lanClientDeviceName) }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("This Tablet as LAN Store Host", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "Serves live catalog, stock, customers, and atomic checkout over local Wi-Fi.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        StatusBadge(
                                            text = if (hostStatus.isRunning) "HOST RUNNING" else "STOPPED",
                                            tone = if (hostStatus.isRunning) BadgeTone.SUCCESS else BadgeTone.NEUTRAL
                                        )
                                    }

                                    val localIp = LanHostServer.findLocalIpv4Address() ?: hostStatus.ipAddress
                                    Text(
                                        "Endpoint: http://$localIp:${if (hostStatus.isRunning) hostStatus.port else portStr} • Requests served: ${hostStatus.requestCount}",
                                        style = RetailXMono.small
                                    )
                                    hostStatus.lastError?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = portStr,
                                            onValueChange = { if (it.all(Char::isDigit)) portStr = it },
                                            label = { Text("Port") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            enabled = !hostStatus.isRunning && canLan,
                                            modifier = Modifier.width(120.dp)
                                        )
                                        if (canLan) {
                                            if (!hostStatus.isRunning) {
                                                Button(
                                                    onClick = {
                                                        val port = portStr.toIntOrNull()?.coerceIn(1024, 65535) ?: 8443
                                                        scope.launch { container.preferencesStore.setLanHostPort(port) }
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                                        ) {
                                                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                        } else {
                                                            LanHostForegroundService.start(context, port)
                                                            scope.launch { container.preferencesStore.setRuntimeMode(RuntimeMode.LAN_HOST) }
                                                            statusMsg = "LAN Host started on port $port"
                                                        }
                                                    },
                                                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings_start_lan_host")
                                                ) {
                                                    Icon(Icons.Filled.WifiTethering, contentDescription = null)
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Start Host Server")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        LanHostForegroundService.stop(context)
                                                        scope.launch { container.preferencesStore.setRuntimeMode(RuntimeMode.STANDALONE) }
                                                        statusMsg = "LAN Host stopped"
                                                    },
                                                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings_stop_lan_host")
                                                ) {
                                                    Text("Stop Host")
                                                }
                                                FilledTonalButton(
                                                    onClick = { container.lanHostServer.generatePairingChallenge() },
                                                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings_generate_pairing_code")
                                                ) {
                                                    Icon(Icons.Filled.QrCode, contentDescription = null)
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Generate Pairing Code")
                                                }
                                            }
                                        }
                                    }

                                    hostStatus.activeChallenge?.let { ch ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("Active Single-Use Pairing Code", style = MaterialTheme.typography.labelMedium)
                                                    Text(ch.code, style = RetailXMono.large, color = MaterialTheme.colorScheme.primary)
                                                }
                                                Text(
                                                    "Expires ${DateTimeUtils.formatShortTime(ch.expiresAtMillis, store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE)}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (pairedDevices.isNotEmpty()) {
                            item {
                                Text("Paired Client Devices", style = MaterialTheme.typography.titleMedium)
                            }
                            items(pairedDevices, key = { it.id }) { dev ->
                                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(dev.name, style = MaterialTheme.typography.titleSmall)
                                                StatusBadge(
                                                    dev.status,
                                                    if (dev.status == "ACTIVE") BadgeTone.SUCCESS else BadgeTone.ERROR
                                                )
                                            }
                                            Text(
                                                "Paired: ${DateTimeUtils.formatDateTime(dev.pairedAt, store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (canLan && dev.status == "ACTIVE") {
                                            OutlinedButton(
                                                onClick = {
                                                    val actor = currentUser ?: return@OutlinedButton
                                                    scope.launch {
                                                        when (val res = container.storeAndAuthRepository.revokeDevice(dev.id, actor.id, actor.fullName)) {
                                                            is AppResult.Success -> statusMsg = "Revoked ${dev.name}"
                                                            is AppResult.Failure -> statusMsg = res.error.userMessage
                                                        }
                                                    }
                                                }
                                            ) { Text("Revoke") }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Pair This Tablet as LAN Client", style = MaterialTheme.typography.titleMedium)
                                        StatusBadge(
                                            text = if (prefs.runtimeMode == RuntimeMode.LAN_CLIENT) clientState.label else "Not Paired",
                                            tone = when {
                                                prefs.runtimeMode != RuntimeMode.LAN_CLIENT -> BadgeTone.NEUTRAL
                                                clientState == LanConnectionState.CONNECTED -> BadgeTone.SUCCESS
                                                else -> BadgeTone.ERROR
                                            }
                                        )
                                    }
                                    if (prefs.runtimeMode == RuntimeMode.LAN_CLIENT) {
                                        Text("Connected Host: ${prefs.lanServerBaseUrl} (${prefs.lanClientDeviceName})", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    when (val res = container.lanApiClient.checkHealth(prefs.lanServerBaseUrl)) {
                                                        is AppResult.Success -> statusMsg = "Host healthy: ${res.data}"
                                                        is AppResult.Failure -> statusMsg = res.error.userMessage
                                                    }
                                                }
                                            }) { Text("Test Connection") }
                                            Button(onClick = {
                                                scope.launch {
                                                    container.preferencesStore.clearLanClientPairing()
                                                    container.lanApiClient.setStandalone()
                                                    container.sessionManager.logout()
                                                    onSessionRestoredOrDisconnected()
                                                }
                                            }) { Text("Unpair & Switch to Standalone") }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = clientHostUrl,
                                            onValueChange = { clientHostUrl = it },
                                            label = { Text("Host Base URL") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OutlinedTextField(
                                                value = pairingCodeInput,
                                                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pairingCodeInput = it },
                                                label = { Text("6-Digit Code") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = clientDeviceName,
                                                onValueChange = { clientDeviceName = it },
                                                label = { Text("Client Name") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    when (val res = container.lanApiClient.completePairing(clientHostUrl, pairingCodeInput, clientDeviceName)) {
                                                        is AppResult.Success -> {
                                                            container.preferencesStore.setLanClientConfig(
                                                                clientHostUrl.trim(),
                                                                res.data.deviceId,
                                                                res.data.deviceToken,
                                                                res.data.deviceName
                                                            )
                                                            container.sessionManager.logout()
                                                            onSessionRestoredOrDisconnected()
                                                        }
                                                        is AppResult.Failure -> statusMsg = res.error.userMessage
                                                    }
                                                }
                                            },
                                            enabled = pairingCodeInput.length == 6
                                        ) { Text("Pair & Connect") }
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Backup & Disaster Recovery", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Backups run a full SQLite WAL checkpoint, bundle a SHA-256 checksum manifest, and enforce pre-restore safety snapshots.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { createBackupLauncher.launch("RetailX_Backup_${System.currentTimeMillis()}.retailxbackup") },
                                            enabled = canBackup,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_export_backup_button")
                                        ) {
                                            Icon(Icons.Filled.Backup, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Export Backup (.retailxbackup)")
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                val actor = currentUser ?: return@FilledTonalButton
                                                scope.launch {
                                                    when (val res = container.backupManager.createBackupToUri(null, prefs.backupRetentionCount, actor.id, actor.fullName)) {
                                                        is AppResult.Success -> statusMsg = "Local backup saved: ${res.data.fileName}"
                                                        is AppResult.Failure -> statusMsg = res.error.userMessage
                                                    }
                                                }
                                            },
                                            enabled = canBackup,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_local_backup_button")
                                        ) {
                                            Text("Quick Local Backup")
                                        }
                                        OutlinedButton(
                                            onClick = { openRestoreLauncher.launch(arrayOf("*/*")) },
                                            enabled = canBackup,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_restore_backup_button")
                                        ) {
                                            Icon(Icons.Filled.Restore, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Restore Backup")
                                        }
                                    }

                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Daily Scheduled Auto-Backup", style = MaterialTheme.typography.titleSmall)
                                            Text("Keeps last ${prefs.backupRetentionCount} local verified backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = prefs.autoBackupEnabled,
                                            enabled = canBackup,
                                            onCheckedChange = { enabled ->
                                                scope.launch {
                                                    container.preferencesStore.setBackupSchedule(enabled, prefs.backupRetentionCount)
                                                    container.backupManager.schedulePeriodicBackup(enabled)
                                                    statusMsg = if (enabled) "Daily auto-backup scheduled" else "Auto-backup disabled"
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text("Backup History", style = MaterialTheme.typography.titleMedium)
                        }
                        if (backupHistory.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.CloudDone,
                                    title = "No backups recorded yet",
                                    message = "Create your first verified database backup above."
                                )
                            }
                        } else {
                            items(backupHistory, key = { it.id }) { rec ->
                                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(rec.fileName, style = MaterialTheme.typography.titleSmall)
                                                StatusBadge(
                                                    rec.status,
                                                    if (rec.status == "SUCCESS" || rec.status == "RESTORED") BadgeTone.SUCCESS else BadgeTone.ERROR
                                                )
                                            }
                                            Text(rec.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                "Schema v${rec.schemaVersion} • ${rec.sizeBytes / 1024} KB • ${DateTimeUtils.formatDateTime(rec.createdAt, store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE)}",
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
            }
        }
    }

    val restoreUri = pendingRestoreUri
    if (restoreUri != null) {
        var adminPassword by remember { mutableStateOf("") }
        var authErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Verify Admin Password to Restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Restoring a backup replaces the current store database after creating a mandatory Pre-Restore safety snapshot.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = adminPassword,
                        onValueChange = { adminPassword = it; authErr = null },
                        label = { Text("Admin Password *") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("restore_admin_password")
                    )
                    if (authErr != null) {
                        Text(authErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val actor = currentUser ?: return@Button
                    scope.launch {
                        if (!container.storeAndAuthRepository.verifyAdminPassword(adminPassword)) {
                            authErr = "Invalid admin password"
                            return@launch
                        }
                        when (val res = container.backupManager.restoreFromUri(restoreUri, actor.id, actor.fullName)) {
                            is AppResult.Success -> {
                                pendingRestoreUri = null
                                container.sessionManager.logout()
                                statusMsg = res.data
                                onSessionRestoredOrDisconnected()
                            }
                            is AppResult.Failure -> authErr = res.error.userMessage
                        }
                    }
                }) { Text("Verify & Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreSettingsTab(
    store: StoreSettings,
    canEdit: Boolean,
    onSave: (StoreSettings) -> Unit
) {
    var name by remember(store.name) { mutableStateOf(store.name) }
    var tagline by remember(store.tagline) { mutableStateOf(store.tagline) }
    var gstin by remember(store.gstin) { mutableStateOf(store.gstin) }
    var stateCode by remember(store.stateCode) { mutableStateOf(store.stateCode) }
    var stateExpanded by remember { mutableStateOf(false) }
    var address by remember(store.address) { mutableStateOf(store.address) }
    var phone by remember(store.phone) { mutableStateOf(store.phone) }
    var email by remember(store.email) { mutableStateOf(store.email) }
    var invoicePrefix by remember(store.invoicePrefix) { mutableStateOf(store.invoicePrefix) }
    var invoiceFooter by remember(store.invoiceFooter) { mutableStateOf(store.invoiceFooter) }
    var taxInclusive by remember(store.defaultTaxInclusive) { mutableStateOf(store.defaultTaxInclusive) }
    var allowNegStock by remember(store.allowNegativeStock) { mutableStateOf(store.allowNegativeStock) }
    var autoRound by remember(store.autoRoundInvoice) { mutableStateOf(store.autoRoundInvoice) }
    var defaultFormat by remember(store.defaultInvoiceFormat) { mutableStateOf(store.defaultInvoiceFormat) }
    var validationErr by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Business & GST Profile", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Store Name *") },
                        enabled = canEdit,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_store_name")
                    )
                    OutlinedTextField(
                        value = tagline,
                        onValueChange = { tagline = it },
                        label = { Text("Tagline") },
                        enabled = canEdit,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = gstin,
                            onValueChange = { gstin = it.uppercase() },
                            label = { Text("GSTIN") },
                            enabled = canEdit,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuBox(
                            expanded = stateExpanded,
                            onExpandedChange = { if (canEdit) stateExpanded = !stateExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = IndianStates.findByCode(stateCode)?.label ?: stateCode,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Store State") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(stateExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = stateExpanded, onDismissRequest = { stateExpanded = false }) {
                                IndianStates.all.forEach { st ->
                                    DropdownMenuItem(
                                        text = { Text(st.label) },
                                        onClick = { stateCode = st.code; stateExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, enabled = canEdit, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, enabled = canEdit, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, enabled = canEdit, singleLine = true, modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider()
                    Text("Billing & Invoice Defaults", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = invoicePrefix, onValueChange = { invoicePrefix = it.uppercase() }, label = { Text("Invoice Prefix") }, enabled = canEdit, singleLine = true, modifier = Modifier.weight(0.5f))
                        OutlinedTextField(value = invoiceFooter, onValueChange = { invoiceFooter = it }, label = { Text("Invoice Footer") }, enabled = canEdit, modifier = Modifier.weight(1.5f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InvoiceFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = defaultFormat == fmt,
                                onClick = { if (canEdit) defaultFormat = fmt },
                                label = { Text(fmt.label) }
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Default Product Prices Include GST", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = taxInclusive, onCheckedChange = { taxInclusive = it }, enabled = canEdit)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow Negative Stock on Checkout", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = allowNegStock, onCheckedChange = { allowNegStock = it }, enabled = canEdit)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-Round Invoice Total to Nearest Rupee", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoRound, onCheckedChange = { autoRound = it }, enabled = canEdit)
                    }

                    if (validationErr != null) {
                        Text(validationErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (canEdit) {
                        Button(
                            onClick = {
                                if (!IndianStates.isValidGstin(gstin)) {
                                    validationErr = "Invalid 15-character GSTIN format"
                                    return@Button
                                }
                                validationErr = null
                                onSave(
                                    store.copy(
                                        name = name.trim(),
                                        tagline = tagline.trim(),
                                        gstin = gstin.trim().uppercase(),
                                        stateCode = stateCode,
                                        address = address.trim(),
                                        phone = phone.trim(),
                                        email = email.trim(),
                                        invoicePrefix = invoicePrefix.trim().ifBlank { "INV" },
                                        invoiceFooter = invoiceFooter.trim(),
                                        defaultTaxInclusive = taxInclusive,
                                        allowNegativeStock = allowNegStock,
                                        autoRoundInvoice = autoRound,
                                        defaultInvoiceFormat = defaultFormat
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("settings_save_store_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save Store Settings")
                        }
                    }
                }
            }
        }
    }
}
