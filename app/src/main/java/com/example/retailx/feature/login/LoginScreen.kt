package com.example.retailx.feature.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.datastore.AppPreferences
import com.example.retailx.core.designsystem.BadgeTone
import com.example.retailx.core.designsystem.StatusBadge
import com.example.retailx.core.model.RuntimeMode
import com.example.retailx.core.model.StoreSettings
import com.example.retailx.core.model.User
import com.example.retailx.domain.repository.AuthRepository
import com.example.retailx.lan.client.LanApiClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    storeSettings: StoreSettings?,
    appPrefs: AppPreferences,
    authRepository: AuthRepository,
    lanApiClient: LanApiClient,
    onLoginSuccess: (User, Boolean) -> Unit,
    onDisconnectLanClient: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var usePinMode by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val isLanClient = appPrefs.runtimeMode == RuntimeMode.LAN_CLIENT

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Storefront,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = storeSettings?.name ?: "RetailX POS",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Staff Sign-In",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        StatusBadge(
                            text = appPrefs.runtimeMode.label,
                            tone = if (isLanClient) BadgeTone.INFO else BadgeTone.SUCCESS
                        )
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !usePinMode,
                            onClick = { usePinMode = false; errorMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            modifier = Modifier.testTag("login_mode_password")
                        ) {
                            Text("Password")
                        }
                        SegmentedButton(
                            selected = usePinMode,
                            onClick = { usePinMode = true; errorMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            modifier = Modifier.testTag("login_mode_pin")
                        ) {
                            Text("Quick PIN")
                        }
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_username_input")
                    )

                    if (!usePinMode) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_password_input")
                        )
                    } else {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                            label = { Text("Quick PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_pin_input")
                        )
                        // Touch PIN keypad
                        val rows = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("CLR", "0", "DEL")
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { key ->
                                        FilledTonalButton(
                                            onClick = {
                                                when (key) {
                                                    "CLR" -> pin = ""
                                                    "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                                    else -> if (pin.length < 6) pin += key
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                        ) {
                                            if (key == "DEL") {
                                                Icon(Icons.Filled.Backspace, contentDescription = "Delete")
                                            } else {
                                                Text(key, style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (errorMsg != null) {
                        Text(
                            text = errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            isBusy = true
                            errorMsg = null
                            val secret = if (usePinMode) pin else password
                            scope.launch {
                                val res = if (isLanClient) {
                                    lanApiClient.loginOnHost(
                                        baseUrl = appPrefs.lanServerBaseUrl,
                                        deviceId = appPrefs.lanDeviceId,
                                        deviceToken = appPrefs.lanDeviceToken,
                                        username = username,
                                        secret = secret,
                                        isPin = usePinMode
                                    )
                                } else {
                                    if (usePinMode) {
                                        authRepository.loginWithPin(username, secret)
                                    } else {
                                        authRepository.loginWithPassword(username, secret)
                                    }
                                }
                                isBusy = false
                                when (res) {
                                    is AppResult.Success -> onLoginSuccess(res.data, isLanClient)
                                    is AppResult.Failure -> errorMsg = res.error.userMessage
                                }
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("login_submit_button")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Sign In")
                        }
                    }

                    if (isLanClient) {
                        TextButton(
                            onClick = onDisconnectLanClient,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Switch back to Standalone Mode")
                        }
                    }
                }
            }
        }
    }
}
