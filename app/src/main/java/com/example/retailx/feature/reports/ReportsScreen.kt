package com.example.retailx.feature.reports

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.CsvCodec
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.domain.repository.ReportsBundle
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

private enum class ReportRangePreset(val label: String) {
    TODAY("Today"),
    LAST_7("7 Days"),
    LAST_30("30 Days"),
    THIS_FY("This FY")
}

@Composable
fun ReportsScreen(
    container: AppContainer
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)
    val timezone = store?.timezone ?: DateTimeUtils.DEFAULT_TIMEZONE

    var preset by remember { mutableStateOf(ReportRangePreset.LAST_7) }
    var bundle by remember { mutableStateOf<ReportsBundle?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val rangeMillis = remember(preset, timezone) {
        when (preset) {
            ReportRangePreset.TODAY -> DateTimeUtils.todayRange(timezone)
            ReportRangePreset.LAST_7 -> DateTimeUtils.lastNDaysRange(7, timezone)
            ReportRangePreset.LAST_30 -> DateTimeUtils.lastNDaysRange(30, timezone)
            ReportRangePreset.THIS_FY -> {
                val cal = Calendar.getInstance(TimeZone.getTimeZone(timezone))
                val month = cal.get(Calendar.MONTH) // 0 = Jan, 3 = Apr
                val fyStartYear = if (month >= Calendar.APRIL) cal.get(Calendar.YEAR) else cal.get(Calendar.YEAR) - 1
                val startCal = Calendar.getInstance(TimeZone.getTimeZone(timezone)).apply {
                    set(fyStartYear, Calendar.APRIL, 1, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                startCal.timeInMillis to System.currentTimeMillis()
            }
        }
    }

    LaunchedEffect(rangeMillis) {
        isLoading = true
        bundle = container.financeAndReportsRepository.getReportsBundle(rangeMillis.first, rangeMillis.second)
        isLoading = false
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val b = bundle
        if (uri != null && b != null) {
            scope.launch {
                val headers = listOf("Section", "Label", "Metric1", "Metric2", "Metric3", "Metric4")
                val rows = mutableListOf<List<String>>()
                rows.add(listOf("SUMMARY", "Completed Bills", b.billCount.toString(), "", "", ""))
                rows.add(listOf("SUMMARY", "Gross Sales", MoneyFormatter.formatPaise(b.grossSalesPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Discounts", MoneyFormatter.formatPaise(b.discountsPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Returns", MoneyFormatter.formatPaise(b.returnsPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Net Sales", MoneyFormatter.formatPaise(b.netSalesPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Estimated COGS", MoneyFormatter.formatPaise(b.cogsPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Estimated Profit", MoneyFormatter.formatPaise(b.estimatedProfitPaise), "", "", ""))
                rows.add(listOf("SUMMARY", "Expenses", MoneyFormatter.formatPaise(b.expensesPaise), "", "", ""))
                b.gstSlabs.forEach { s ->
                    rows.add(
                        listOf(
                            "GST_SLAB",
                            "${s.gstRateBps / 100}%",
                            "Taxable: ${MoneyFormatter.formatPaise(s.taxablePaise)}",
                            "CGST: ${MoneyFormatter.formatPaise(s.cgstPaise)}",
                            "SGST: ${MoneyFormatter.formatPaise(s.sgstPaise)}",
                            "IGST: ${MoneyFormatter.formatPaise(s.igstPaise)}"
                        )
                    )
                }
                b.topProducts.forEach { tp ->
                    rows.add(
                        listOf(
                            "TOP_PRODUCT",
                            tp.productName,
                            "Qty: ${QuantityScale.formatScaled(tp.qtyScaled)}",
                            "Revenue: ${MoneyFormatter.formatPaise(tp.revenuePaise)}",
                            "",
                            ""
                        )
                    )
                }
                val csv = CsvCodec.encode(headers, rows)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                statusMsg = "Report exported to CSV"
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        val m = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        statusMsg = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader(
                    title = "Business & GST Reports",
                    subtitle = "Period: ${DateTimeUtils.formatDate(rangeMillis.first, timezone)} to ${DateTimeUtils.formatDate(rangeMillis.second, timezone)}",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    exportCsvLauncher.launch("RetailX_Report_${preset.name}.csv")
                                },
                                enabled = bundle != null,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("reports_export_csv_button")
                            ) {
                                Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export CSV")
                            }
                            Button(
                                onClick = {
                                    val s = store ?: return@Button
                                    val b = bundle ?: return@Button
                                    scope.launch {
                                        val file = container.pdfGenerator.generateBusinessReportPdf(s, b)
                                        val intent = container.pdfGenerator.buildShareIntent(file)
                                        context.startActivity(Intent.createChooser(intent, "Share Business Report PDF"))
                                    }
                                },
                                enabled = bundle != null && store != null,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("reports_export_pdf_button")
                            ) {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export PDF")
                            }
                        }
                    }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportRangePreset.entries.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p.label) },
                            modifier = Modifier.testTag("report_preset_${p.name.lowercase()}")
                        )
                    }
                }
            }

            if (isLoading && bundle == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            val b = bundle
            if (b != null) {
                item {
                    val semantics = LocalSemanticColors.current
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val cols = if (maxWidth >= 900.dp) 4 else if (maxWidth >= 560.dp) 2 else 1
                        val cards = listOf(
                            Triple("Net Sales", MoneyFormatter.formatPaise(b.netSalesPaise), "${b.billCount} bills • Gross ${MoneyFormatter.formatPaiseCompact(b.grossSalesPaise)}"),
                            Triple("Est. Gross Profit", MoneyFormatter.formatPaise(b.estimatedProfitPaise), "Ex-tax sales minus COGS (${MoneyFormatter.formatPaiseCompact(b.cogsPaise)})"),
                            Triple("Discounts & Returns", "${MoneyFormatter.formatPaiseCompact(b.discountsPaise)} / ${MoneyFormatter.formatPaiseCompact(b.returnsPaise)}", "Expenses: ${MoneyFormatter.formatPaiseCompact(b.expensesPaise)}"),
                            Triple("Receivable / Payable", MoneyFormatter.formatPaiseCompact(b.totalReceivablePaise), "Payables: ${MoneyFormatter.formatPaiseCompact(b.totalPayablePaise)} • Stock: ${MoneyFormatter.formatPaiseCompact(b.inventoryValuationPaise)}")
                        )
                        val icons = listOf(Icons.Filled.CurrencyRupee, Icons.Filled.TrendingUp, Icons.Filled.ReceiptLong, Icons.Filled.AccountBalance)
                        val accents = listOf(MaterialTheme.colorScheme.primary, semantics.success, semantics.warning, semantics.info)

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
                                    repeat(cols - rowCards.size) { Spacer(Modifier.weight(1f)) }
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
                                Text("GST Summary by Tax Slab", style = MaterialTheme.typography.titleMedium)
                                StatusBadge("CGST / SGST / IGST", BadgeTone.INFO)
                            }
                            if (b.gstSlabs.isEmpty()) {
                                Text("No taxable sales recorded in this period.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text("Rate", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.7f))
                                    Text("Taxable", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.1f))
                                    Text("CGST", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    Text("SGST", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    Text("IGST", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider()
                                b.gstSlabs.forEach { s ->
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${s.gstRateBps / 100}%", style = RetailXMono.small, modifier = Modifier.weight(0.7f))
                                        Text(MoneyFormatter.formatPaise(s.taxablePaise), style = RetailXMono.small, modifier = Modifier.weight(1.1f))
                                        Text(MoneyFormatter.formatPaise(s.cgstPaise), style = RetailXMono.small, modifier = Modifier.weight(1f))
                                        Text(MoneyFormatter.formatPaise(s.sgstPaise), style = RetailXMono.small, modifier = Modifier.weight(1f))
                                        Text(MoneyFormatter.formatPaise(s.igstPaise), style = RetailXMono.small, modifier = Modifier.weight(1f))
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }

                item {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isWide = maxWidth >= 760.dp
                        if (isWide) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PaymentMixReportCard(b, Modifier.weight(1f))
                                TopProductsReportCard(b, Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PaymentMixReportCard(b, Modifier.fillMaxWidth())
                                TopProductsReportCard(b, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                item {
                    Card(
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cashier Performance", style = MaterialTheme.typography.titleMedium)
                            if (b.cashierPerformance.isEmpty()) {
                                Text("No cashier sales in selected period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                b.cashierPerformance.forEach { row ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${row.cashierName} (${row.billCount} bills)", style = MaterialTheme.typography.bodyMedium)
                                        Text(MoneyFormatter.formatPaise(row.totalSalesPaise), style = RetailXMono.medium)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentMixReportCard(b: ReportsBundle, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Collections by Payment Method", style = MaterialTheme.typography.titleMedium)
            if (b.paymentBreakdown.isEmpty()) {
                Text("No payment entries in selected period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val total = b.paymentBreakdown.sumOf { it.amountPaise }.coerceAtLeast(1L)
                b.paymentBreakdown.forEach { p ->
                    val ratio = (p.amountPaise.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.method.label, style = MaterialTheme.typography.bodyMedium)
                            Text(MoneyFormatter.formatPaise(p.amountPaise), style = RetailXMono.small)
                        }
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopProductsReportCard(b: ReportsBundle, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Selling Products", style = MaterialTheme.typography.titleMedium)
            if (b.topProducts.isEmpty()) {
                Text("No product sales in selected period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                b.topProducts.forEachIndexed { idx, tp ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${idx + 1}. ${tp.productName} (${QuantityScale.formatScaled(tp.qtyScaled)})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(MoneyFormatter.formatPaise(tp.revenuePaise), style = RetailXMono.small)
                    }
                }
            }
        }
    }
}
