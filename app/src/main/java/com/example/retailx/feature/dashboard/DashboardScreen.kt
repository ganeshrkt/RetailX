package com.example.retailx.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.SaleStatus
import com.example.retailx.domain.repository.DashboardSummary

@Composable
fun DashboardScreen(
    container: AppContainer,
    onNavigateToPos: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit
) {
    val sales by container.salesRepository.observeSales().collectAsStateWithLifecycle(initialValue = emptyList())
    val products by container.catalogRepository.observeProducts(activeOnly = true).collectAsStateWithLifecycle(initialValue = emptyList())
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)
    var summary by remember { mutableStateOf<DashboardSummary?>(null) }

    LaunchedEffect(sales.size, products.size) {
        summary = container.financeAndReportsRepository.getDashboardSummary()
    }

    val s = summary
    val lowStockItems = products.filter { it.isLowStock || it.isOutOfStock }.take(6)
    val recentSales = sales.take(6)
    val semantics = LocalSemanticColors.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionHeader(
                title = "Store Dashboard",
                subtitle = "Today's live figures (${store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE}) • Profit based on weighted-average cost",
                trailing = {
                    Button(
                        onClick = onNavigateToPos,
                        modifier = Modifier.testTag("dash_open_pos_button")
                    ) {
                        Icon(Icons.Filled.PointOfSale, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open POS")
                    }
                }
            )
        }

        if (s != null) {
            item {
                // Responsive KPI grid
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth >= 900.dp) 4 else if (maxWidth >= 560.dp) 2 else 1
                    val cards = listOf(
                        Triple("Today's Net Sales", MoneyFormatter.formatPaise(s.todayNetSalesPaise), "${s.todayBillCount} bills • Gross ${MoneyFormatter.formatPaiseCompact(s.todayGrossSalesPaise)}"),
                        Triple("Est. Gross Profit", MoneyFormatter.formatPaise(s.todayEstimatedProfitPaise), "Net ex-tax minus weighted-avg COGS"),
                        Triple("Discounts & Returns", "${MoneyFormatter.formatPaiseCompact(s.todayDiscountsPaise)} / ${MoneyFormatter.formatPaiseCompact(s.todayReturnsPaise)}", "Expenses today: ${MoneyFormatter.formatPaiseCompact(s.todayExpensesPaise)}"),
                        Triple("Stock Alerts", "${s.lowStockCount} Low / ${s.outOfStockCount} Out", "Inventory Value: ${MoneyFormatter.formatPaiseCompact(s.totalStockValuePaise)}")
                    )
                    val icons = listOf(Icons.Filled.CurrencyRupee, Icons.Filled.TrendingUp, Icons.Filled.ReceiptLong, Icons.Filled.Inventory)
                    val accents = listOf(MaterialTheme.colorScheme.primary, semantics.success, semantics.warning, semantics.error)

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cards.chunked(cols).forEachIndexed { rIdx, rowCards ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowCards.forEachIndexed { cIdx, (title, value, sub) ->
                                    val idx = rIdx * cols + cIdx
                                    MetricCard(
                                        title = title,
                                        value = value,
                                        subtitle = sub,
                                        icon = icons[idx],
                                        accentColor = accents[idx],
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(cols - rowCards.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isWide = maxWidth >= 760.dp
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TrendChartCard(s, modifier = Modifier.weight(1.4f))
                            PaymentSummaryCard(s, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrendChartCard(s, modifier = Modifier.fillMaxWidth())
                            PaymentSummaryCard(s, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isWide = maxWidth >= 760.dp
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TopProductsCard(s, modifier = Modifier.weight(1f))
                            LowStockCard(lowStockItems, onNavigateToInventory, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TopProductsCard(s, modifier = Modifier.fillMaxWidth())
                            LowStockCard(lowStockItems, onNavigateToInventory, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        item {
            Card(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Sales", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onNavigateToSales) { Text("View Register") }
                    }
                    if (recentSales.isEmpty()) {
                        Text("No completed sales yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        recentSales.forEach { sale ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${sale.invoiceNo} • ${sale.customerName}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${DateTimeUtils.formatDateTime(sale.createdAt, store?.timezone ?: "Asia/Kolkata")} • ${sale.cashierName}",
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
                                Spacer(Modifier.width(12.dp))
                                Text(MoneyFormatter.formatPaise(sale.totalPaise), style = RetailXMono.medium)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendChartCard(summary: DashboardSummary, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val profitColor = LocalSemanticColors.current.success
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("7-Day Sales & Profit Trend", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBadge("Sales", BadgeTone.INFO)
                    StatusBadge("Profit", BadgeTone.SUCCESS)
                }
            }
            val maxVal = (summary.dailyTrend.maxOfOrNull { maxOf(it.salesPaise, it.profitPaise) } ?: 0L).coerceAtLeast(100L)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            ) {
                val count = summary.dailyTrend.size.coerceAtLeast(1)
                val slotWidth = size.width / count
                val barW = (slotWidth * 0.28f).coerceAtLeast(8f)
                summary.dailyTrend.forEachIndexed { idx, pt ->
                    val centerX = slotWidth * idx + slotWidth / 2f
                    val salesH = (pt.salesPaise.toFloat() / maxVal.toFloat()) * (size.height - 16f)
                    val profitH = (pt.profitPaise.coerceAtLeast(0L).toFloat() / maxVal.toFloat()) * (size.height - 16f)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(centerX - barW - 2f, size.height - salesH),
                        size = Size(barW, salesH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawRoundRect(
                        color = profitColor,
                        topLeft = Offset(centerX + 2f, size.height - profitH),
                        size = Size(barW, profitH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                summary.dailyTrend.forEach { pt ->
                    Text(pt.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PaymentSummaryCard(summary: DashboardSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Today's Payment Mix", style = MaterialTheme.typography.titleMedium)
            if (summary.paymentBreakdown.isEmpty()) {
                Text("No payments recorded today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val total = summary.paymentBreakdown.sumOf { it.amountPaise }.coerceAtLeast(1L)
                summary.paymentBreakdown.forEach { pt ->
                    val ratio = (pt.amountPaise.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pt.method.label, style = MaterialTheme.typography.bodyMedium)
                            Text(MoneyFormatter.formatPaise(pt.amountPaise), style = RetailXMono.small)
                        }
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopProductsCard(summary: DashboardSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Selling Today", style = MaterialTheme.typography.titleMedium)
            if (summary.topProducts.isEmpty()) {
                Text("Complete sales to see top products.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                summary.topProducts.take(5).forEach { tp ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${tp.productName} (${QuantityScale.formatScaled(tp.qtyScaled)})",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(MoneyFormatter.formatPaise(tp.revenuePaise), style = RetailXMono.small)
                    }
                }
            }
        }
    }
}

@Composable
private fun LowStockCard(
    items: List<com.example.retailx.core.model.Product>,
    onViewInventory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Low & Out-of-Stock Alerts", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onViewInventory) { Text("Inventory") }
            }
            if (items.isEmpty()) {
                Text("All active products are adequately stocked.", style = MaterialTheme.typography.bodySmall, color = LocalSemanticColors.current.success)
            } else {
                items.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        StatusBadge(
                            text = "${QuantityScale.formatScaled(p.stockScaled)} ${p.unitShortName}",
                            tone = if (p.isOutOfStock) BadgeTone.ERROR else BadgeTone.WARNING
                        )
                    }
                }
            }
        }
    }
}
