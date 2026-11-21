package com.example.retailx.feature.staff

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffScreen(
    container: AppContainer,
    currentUser: User?
) {
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) } // 0 = Staff Users, 1 = Role Permissions, 2 = Audit Log
    var auditFilter by remember { mutableStateOf("") }

    val users by container.storeAndAuthRepository.observeUsers().collectAsStateWithLifecycle(initialValue = emptyList())
    val roles by container.storeAndAuthRepository.observeRoles().collectAsStateWithLifecycle(initialValue = emptyList())
    val logs by container.storeAndAuthRepository.observeActivityLogs(250).collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val canManage = currentUser?.permissions?.contains(PermissionCode.STAFF_MANAGE.code) == true
    var showCreateUserDialog by remember { mutableStateOf(false) }
    var resettingUser by remember { mutableStateOf<User?>(null) }
    var selectedRoleId by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        val m = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        statusMsg = null
    }

    val activeRole = roles.firstOrNull { it.id == selectedRoleId } ?: roles.firstOrNull()
    var editedPermissions by remember(activeRole?.id, activeRole?.permissions) {
        mutableStateOf(activeRole?.permissions ?: emptySet())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (canManage && tabIndex == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateUserDialog = true },
                    icon = { Icon(Icons.Filled.PersonAdd, contentDescription = "Add Staff") },
                    text = { Text("Add Staff") },
                    modifier = Modifier.testTag("staff_add_user_fab")
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Staff, Roles & Audit Log",
                subtitle = "${users.count { it.active }} active staff • ${roles.size} configurable roles"
            )

            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("Staff (${users.size})") },
                    modifier = Modifier.testTag("staff_tab_users")
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("Role Permissions") },
                    modifier = Modifier.testTag("staff_tab_roles")
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = { Text("Audit Log (${logs.size})") },
                    modifier = Modifier.testTag("staff_tab_audit")
                )
            }

            when (tabIndex) {
                0 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(users, key = { it.id }) { u ->
                            Card(
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(u.fullName, style = MaterialTheme.typography.titleMedium)
                                            StatusBadge(u.roleName, BadgeTone.INFO)
                                            StatusBadge(
                                                if (u.active) "Active" else "Disabled",
                                                if (u.active) BadgeTone.SUCCESS else BadgeTone.ERROR
                                            )
                                            if (u.hasQuickPin) {
                                                StatusBadge("PIN Set", BadgeTone.NEUTRAL)
                                            }
                                        }
                                        Text(
                                            "@${u.username} • ${u.permissions.size} permissions",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (canManage) {
                                        OutlinedButton(
                                            onClick = { resettingUser = u },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("Reset Auth")
                                        }
                                        val isSelf = u.id == currentUser?.id
                                        Switch(
                                            checked = u.active,
                                            enabled = !isSelf,
                                            onCheckedChange = { nextActive ->
                                                scope.launch {
                                                    val actor = currentUser ?: return@launch
                                                    when (val res = container.storeAndAuthRepository.setUserActive(u.id, nextActive, actor.id, actor.fullName)) {
                                                        is AppResult.Success -> statusMsg = "${u.username} ${if (nextActive) "enabled" else "disabled"}"
                                                        is AppResult.Failure -> statusMsg = res.error.userMessage
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (activeRole != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            roles.forEach { r ->
                                FilterChip(
                                    selected = r.id == activeRole.id,
                                    onClick = { selectedRoleId = r.id },
                                    label = { Text(r.name) },
                                    modifier = Modifier.testTag("role_chip_${r.id}")
                                )
                            }
                        }

                        val grouped = PermissionCode.entries.groupBy { it.group }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            grouped.forEach { (group, perms) ->
                                item(key = group) {
                                    Card(
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                            perms.forEach { perm ->
                                                val checked = editedPermissions.contains(perm.code)
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(perm.label, style = MaterialTheme.typography.bodyMedium)
                                                        Text(perm.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Checkbox(
                                                        checked = checked,
                                                        enabled = canManage && !(activeRole.id == "role_admin" && perm == PermissionCode.STAFF_MANAGE),
                                                        onCheckedChange = { isChecked ->
                                                            editedPermissions = if (isChecked) editedPermissions + perm.code else editedPermissions - perm.code
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (canManage) {
                            Button(
                                onClick = {
                                    val actor = currentUser ?: return@Button
                                    scope.launch {
                                        when (val res = container.storeAndAuthRepository.updateRolePermissions(activeRole.id, editedPermissions, actor.id, actor.fullName)) {
                                            is AppResult.Success -> statusMsg = "Updated permissions for ${activeRole.name}"
                                            is AppResult.Failure -> statusMsg = res.error.userMessage
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("staff_save_permissions_button")
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Save ${activeRole.name} Permissions")
                            }
                        }
                    }
                }

                2 -> {
                    OutlinedTextField(
                        value = auditFilter,
                        onValueChange = { auditFilter = it },
                        placeholder = { Text("Filter audit log by action, user, or entity...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val filteredLogs = logs.filter {
                        auditFilter.isBlank() ||
                            it.action.contains(auditFilter, ignoreCase = true) ||
                            it.username.contains(auditFilter, ignoreCase = true) ||
                            it.entityType.contains(auditFilter, ignoreCase = true) ||
                            it.metadataJson.contains(auditFilter, ignoreCase = true)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredLogs, key = { it.id }) { log ->
                            Card(
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            StatusBadge(log.action, BadgeTone.INFO)
                                            Text(log.username, style = MaterialTheme.typography.titleSmall)
                                        }
                                        Text(
                                            DateTimeUtils.formatDateTime(log.createdAt, store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        "${log.entityType}${log.entityId?.let { " • $it" } ?: ""} • ${log.metadataJson}",
                                        style = MaterialTheme.typography.bodySmall,
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

    if (showCreateUserDialog) {
        var username by remember { mutableStateOf("") }
        var fullName by remember { mutableStateOf("") }
        var selectedRole by remember { mutableStateOf(roles.firstOrNull { it.id == "role_cashier" } ?: roles.firstOrNull()) }
        var roleExpanded by remember { mutableStateOf(false) }
        var password by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateUserDialog = false },
            title = { Text("Create Staff Account") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.trim() },
                        label = { Text("Username *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("staff_create_username")
                    )
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("staff_create_fullname")
                    )
                    ExposedDropdownMenuBox(
                        expanded = roleExpanded,
                        onExpandedChange = { roleExpanded = !roleExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedRole?.name ?: "Select Role",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Role") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                            roles.forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(r.name) },
                                    onClick = { selectedRole = r; roleExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (min 4 chars) *") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("staff_create_password")
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                        label = { Text("Quick PIN (Optional 4-6 digits)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val actor = currentUser ?: return@Button
                        val roleId = selectedRole?.id ?: return@Button
                        scope.launch {
                            when (
                                val res = container.storeAndAuthRepository.createUser(
                                    username = username,
                                    fullName = fullName,
                                    roleId = roleId,
                                    password = password,
                                    pin = pin.takeIf { it.isNotBlank() },
                                    actorUserId = actor.id,
                                    actorName = actor.fullName
                                )
                            ) {
                                is AppResult.Success -> {
                                    statusMsg = "Created user ${res.data.username}"
                                    showCreateUserDialog = false
                                }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    },
                    modifier = Modifier.testTag("staff_create_confirm_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateUserDialog = false }) { Text("Cancel") }
            }
        )
    }

    val resetTarget = resettingUser
    if (resetTarget != null) {
        var newPass by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resettingUser = null },
            title = { Text("Reset Credentials: ${resetTarget.username}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text("New Password (leave blank to keep)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it },
                        label = { Text("New Quick PIN (leave blank to keep)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val actor = currentUser ?: return@Button
                    scope.launch {
                        when (
                            val res = container.storeAndAuthRepository.resetUserCredentials(
                                userId = resetTarget.id,
                                newPassword = newPass.takeIf { it.isNotBlank() },
                                newPin = newPin.takeIf { it.isNotBlank() },
                                actorUserId = actor.id,
                                actorName = actor.fullName
                            )
                        ) {
                            is AppResult.Success -> {
                                statusMsg = "Updated credentials for ${resetTarget.username}"
                                resettingUser = null
                            }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = { resettingUser = null }) { Text("Cancel") }
            }
        )
    }
}
