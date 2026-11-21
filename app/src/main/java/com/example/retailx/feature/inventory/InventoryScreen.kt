package com.example.retailx.feature.inventory

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
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun InventoryScreen(
    container: AppContainer,
    currentUser: User?
) {
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) } // 0 = Stock Balances, 1 = Movement Ledger
    var filterMode by remember { mutableIntStateOf(0) } // 0 = All, 1 = Low/Out Only
    var search by remember { mutableStateOf("") }

    val products by container.catalogRepository.observeProducts(search).collectAsStateWithLifecycle(initialValue = emptyList())
    val movements by container.catalogRepository.observeStockMovements(200).collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)

    val canAdjust = currentUser?.permissions?.contains(PermissionCode.INVENTORY_ADJUST.code) == true
    var adjustingProduct by remember { mutableStateOf<Product?>(null) }
    var isStockCountMode by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val displayedProducts = products.filter {
        filterMode == 0 || it.isLowStock || it.isOutOfStock
    }
    val totalValuationPaise = products.sumOf { p ->
        if (p.stockScaled <= 0L) 0L
        else BigDecimal(p.costPricePaise)
            .multiply(BigDecimal(p.stockScaled))
            .divide(BigDecimal(QuantityScale.SCALE), 0, RoundingMode.HALF_UP)
            .longValueExact()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = "Inventory & Stock Ledger",
                subtitle = "Weighted-average valuation: ${MoneyFormatter.formatPaise(totalValuationPaise)} • ${products.count { it.isLowStock || it.isOutOfStock }} alerts"
            )

            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Stock on Hand (${displayedProducts.size})") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Movement Ledger (${movements.size})") })
            }

            if (tabIndex == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Filter products...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterMode == 0,
                        onClick = { filterMode = 0 },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filterMode == 1,
                        onClick = { filterMode = 1 },
                        label = { Text("Low / Out") }
                    )
                }

                if (displayedProducts.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Filled.Inventory,
                        title = "No inventory items match",
                        message = "Adjust filters or add products in Catalog."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedProducts, key = { it.id }) { p ->
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "SKU ${p.sku} • Avg Cost ${MoneyFormatter.formatPaise(p.costPricePaise)} • Reorder @ ${QuantityScale.formatScaled(p.reorderLevelScaled)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusBadge(
                                        text = "${QuantityScale.formatScaled(p.stockScaled)} ${p.unitShortName}",
                                        tone = when {
                                            p.isOutOfStock -> BadgeTone.ERROR
                                            p.isLowStock -> BadgeTone.WARNING
                                            else -> BadgeTone.SUCCESS
                                        }
                                    )
                                    if (canAdjust) {
                                        Spacer(Modifier.width(8.dp))
                                        FilledTonalButton(
                                            onClick = { isStockCountMode = false; adjustingProduct = p },
                                            modifier = Modifier.testTag("inv_adjust_${p.sku}")
                                        ) { Text("Adjust") }
                                        Spacer(Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { isStockCountMode = true; adjustingProduct = p }
                                        ) { Text("Count") }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (movements.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Filled.History,
                        title = "No stock movements yet",
                        message = "Every opening balance, sale, return, purchase receipt, and manual adjustment is logged here."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(movements, key = { it.id }) { m ->
                            val isPositive = m.quantityDeltaScaled >= 0L
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${m.productName} (${m.sku})", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${m.movementType.label} • ${m.reason} • ${m.actorName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            DateTimeUtils.formatDateTime(m.createdAt, store?.timezone ?: "Asia/Kolkata"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusBadge(
                                        text = "${if (isPositive) "+" else ""}${QuantityScale.formatScaled(m.quantityDeltaScaled)}",
                                        tone = if (isPositive) BadgeTone.SUCCESS else BadgeTone.WARNING
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    adjustingProduct?.let { prod ->
        if (currentUser != null) {
            var qtyInput by remember {
                mutableStateOf(if (isStockCountMode) QuantityScale.formatScaled(prod.stockScaled) else "0")
            }
            var isDeduct by remember { mutableStateOf(false) }
            var reason by remember { mutableStateOf(if (isStockCountMode) "Physical stock count reconciliation" else "") }

            AlertDialog(
                onDismissRequest = { adjustingProduct = null },
                title = {
                    Text(if (isStockCountMode) "Stock Count: ${prod.name}" else "Adjust Stock: ${prod.name}")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Current Balance: ${QuantityScale.formatScaled(prod.stockScaled)} ${prod.unitShortName}")
                        if (!isStockCountMode) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !isDeduct, onClick = { isDeduct = false }, label = { Text("Add (+)") })
                                FilterChip(selected = isDeduct, onClick = { isDeduct = true }, label = { Text("Deduct (-)") })
                            }
                        }
                        OutlinedTextField(
                            value = qtyInput,
                            onValueChange = { qtyInput = it },
                            label = { Text(if (isStockCountMode) "Physical Counted Qty" else "Adjustment Quantity") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text("Reason *") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val parsed = QuantityScale.parseScaled(qtyInput) ?: 0L
                        val delta = if (isStockCountMode) {
                            parsed - prod.stockScaled
                        } else {
                            if (isDeduct) -parsed else parsed
                        }
                        scope.launch {
                            val res = container.catalogRepository.adjustStock(
                                productVariantId = prod.defaultVariantId,
                                deltaScaled = delta,
                                movementType = if (isStockCountMode) MovementType.STOCK_COUNT else MovementType.MANUAL_ADJUSTMENT,
                                reason = reason,
                                actorUserId = currentUser.id,
                                actorName = currentUser.fullName
                            )
                            when (res) {
                                is AppResult.Success -> {
                                    adjustingProduct = null
                                    statusMsg = "Stock updated"
                                }
                                is AppResult.Failure -> statusMsg = res.error.userMessage
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { adjustingProduct = null }) { Text("Cancel") } }
            )
        }
    }
}
