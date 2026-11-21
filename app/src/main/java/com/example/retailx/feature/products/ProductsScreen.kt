package com.example.retailx.feature.products

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.retailx.app.AppContainer
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.CsvCodec
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.*
import kotlinx.coroutines.launch

@Composable
fun ProductsScreen(
    container: AppContainer,
    currentUser: User?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }

    val products by container.catalogRepository.observeProducts(query, selectedCategoryId, activeOnly = false)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by container.catalogRepository.observeCategories().collectAsStateWithLifecycle(initialValue = emptyList())
    val brands by container.catalogRepository.observeBrands().collectAsStateWithLifecycle(initialValue = emptyList())
    val units by container.catalogRepository.observeUnits().collectAsStateWithLifecycle(initialValue = emptyList())

    val canManage = currentUser?.permissions?.contains(PermissionCode.CATALOG_MANAGE.code) == true
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCsvImportDialog by remember { mutableStateOf(false) }
    var csvPreviewText by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = container.catalogRepository.exportProductsCsv()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                statusMsg = "Exported ${products.size} products to CSV"
            }
        }
    }

    val importCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            csvPreviewText = content
            showCsvImportDialog = true
        }
    }

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
                    onClick = { editingProduct = null; showCreateDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add Product") },
                    modifier = Modifier.testTag("products_add_button")
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
                title = "Product Catalog",
                subtitle = "${products.size} items • Indian GST slabs, HSN codes & stock tracking",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canManage) {
                            OutlinedButton(onClick = { importCsvLauncher.launch(arrayOf("text/*", "text/csv")) }) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import CSV")
                            }
                        }
                        OutlinedButton(onClick = { exportCsvLauncher.launch("retailx_products.csv") }) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export CSV")
                        }
                    }
                }
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search name, SKU, barcode, HSN...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("products_search_input")
            )

            if (products.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Filled.Category,
                    title = "No products in catalog",
                    message = "Add your first product or import a CSV sheet to start billing."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(products, key = { it.id }) { prod ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canManage) {
                                    editingProduct = prod
                                    showCreateDialog = true
                                },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(prod.name, style = MaterialTheme.typography.titleMedium)
                                        if (!prod.active) StatusBadge("Inactive", BadgeTone.NEUTRAL)
                                    }
                                    Text(
                                        "SKU: ${prod.sku} • Barcode: ${prod.barcode ?: "—"} • HSN: ${prod.hsnCode.ifBlank { "—" }} • GST ${prod.gstRateBps / 100}% (${if (prod.taxInclusive) "Incl." else "Excl."})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(MoneyFormatter.formatPaise(prod.sellingPricePaise), style = RetailXMono.medium, color = MaterialTheme.colorScheme.primary)
                                    StatusBadge(
                                        text = "Stock: ${QuantityScale.formatScaled(prod.stockScaled)} ${prod.unitShortName}",
                                        tone = when {
                                            prod.isOutOfStock -> BadgeTone.ERROR
                                            prod.isLowStock -> BadgeTone.WARNING
                                            else -> BadgeTone.SUCCESS
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog && currentUser != null) {
        ProductEditorDialog(
            existing = editingProduct,
            categories = categories,
            brands = brands,
            units = units,
            onCreateCategory = { name -> container.catalogRepository.createCategory(name) },
            onSave = { data ->
                scope.launch {
                    val res = container.catalogRepository.upsertProduct(
                        id = editingProduct?.id,
                        name = data.name,
                        description = data.description,
                        sku = data.sku,
                        barcode = data.barcode,
                        categoryId = data.categoryId,
                        brandId = data.brandId,
                        unitId = data.unitId,
                        hsnCode = data.hsnCode,
                        gstRateBps = data.gstRateBps,
                        taxCategory = data.taxCategory,
                        taxInclusive = data.taxInclusive,
                        costPricePaise = data.costPaise,
                        sellingPricePaise = data.sellPaise,
                        mrpPaise = data.mrpPaise,
                        reorderLevelScaled = data.reorderScaled,
                        openingStockScaled = data.openingStockScaled,
                        imageUri = data.imageUri,
                        active = data.active,
                        actorUserId = currentUser.id,
                        actorName = currentUser.fullName
                    )
                    when (res) {
                        is AppResult.Success -> {
                            showCreateDialog = false
                            statusMsg = "Saved ${res.data.name}"
                        }
                        is AppResult.Failure -> statusMsg = res.error.userMessage
                    }
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (showCsvImportDialog && currentUser != null) {
        val parsed = remember(csvPreviewText) { CsvCodec.decode(csvPreviewText) }
        AlertDialog(
            onDismissRequest = { showCsvImportDialog = false },
            title = { Text("CSV Bulk Import Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Rows detected: ${(parsed.size - 1).coerceAtLeast(0)}. Expected columns: Name, SKU, Barcode, HSN, GST%, Cost, SellingPrice, OpeningStock",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = csvPreviewText,
                        onValueChange = { csvPreviewText = it },
                        label = { Text("CSV Data") },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val res = container.catalogRepository.importProductsCsv(csvPreviewText, currentUser.id, currentUser.fullName)
                        when (res) {
                            is AppResult.Success -> {
                                showCsvImportDialog = false
                                statusMsg = "Imported ${res.data} products"
                            }
                            is AppResult.Failure -> statusMsg = res.error.userMessage
                        }
                    }
                }) { Text("Import Rows") }
            },
            dismissButton = { TextButton(onClick = { showCsvImportDialog = false }) { Text("Cancel") } }
        )
    }
}

private data class ProductDraftData(
    val name: String,
    val description: String,
    val sku: String,
    val barcode: String?,
    val categoryId: String?,
    val brandId: String?,
    val unitId: String,
    val hsnCode: String,
    val gstRateBps: Int,
    val taxCategory: TaxCategory,
    val taxInclusive: Boolean,
    val costPaise: Long,
    val sellPaise: Long,
    val mrpPaise: Long?,
    val reorderScaled: Long,
    val openingStockScaled: Long,
    val imageUri: String?,
    val active: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductEditorDialog(
    existing: Product?,
    categories: List<Category>,
    brands: List<Brand>,
    units: List<UnitModel>,
    onCreateCategory: suspend (String) -> AppResult<Category>,
    onSave: (ProductDraftData) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var sku by remember { mutableStateOf(existing?.sku.orEmpty()) }
    var barcode by remember { mutableStateOf(existing?.barcode.orEmpty()) }
    var hsn by remember { mutableStateOf(existing?.hsnCode.orEmpty()) }
    var gstBps by remember { mutableIntStateOf(existing?.gstRateBps ?: 1800) }
    var taxInclusive by remember { mutableStateOf(existing?.taxInclusive ?: true) }
    var costText by remember { mutableStateOf(existing?.let { MoneyFormatter.paiseToPlainRupees(it.costPricePaise) } ?: "0") }
    var sellText by remember { mutableStateOf(existing?.let { MoneyFormatter.paiseToPlainRupees(it.sellingPricePaise) } ?: "0") }
    var mrpText by remember { mutableStateOf(existing?.mrpPaise?.let { MoneyFormatter.paiseToPlainRupees(it) }.orEmpty()) }
    var reorderText by remember { mutableStateOf(existing?.let { QuantityScale.formatScaled(it.reorderLevelScaled) } ?: "5") }
    var openingStockText by remember { mutableStateOf("0") }
    var selectedCatId by remember { mutableStateOf(existing?.categoryId) }
    var selectedUnitId by remember { mutableStateOf(existing?.unitId ?: units.firstOrNull()?.id ?: "unit_pcs") }
    var imageUri by remember { mutableStateOf(existing?.imageUri) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var newCategoryName by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) imageUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Product" else "Edit ${existing.name}") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 380.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Product Name *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("product_name_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it.uppercase() },
                        label = { Text("SKU *") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_sku_input")
                    )
                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it },
                        label = { Text("Barcode") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Purchase Cost (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sellText,
                        onValueChange = { sellText = it },
                        label = { Text("Selling Price (₹) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_price_input")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mrpText,
                        onValueChange = { mrpText = it },
                        label = { Text("MRP (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hsn,
                        onValueChange = { hsn = it },
                        label = { Text("HSN Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("GST Rate", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 500, 1200, 1800, 2800).forEach { bps ->
                        FilterChip(
                            selected = gstBps == bps,
                            onClick = { gstBps = bps },
                            label = { Text("${bps / 100}%") }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = taxInclusive, onCheckedChange = { taxInclusive = it })
                    Text("Selling price is GST-inclusive")
                }

                // Category quick picker + inline creation
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("New Category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val res = onCreateCategory(newCategoryName)
                                if (res is AppResult.Success) {
                                    selectedCatId = res.data.id
                                    newCategoryName = ""
                                }
                            }
                        },
                        enabled = newCategoryName.isNotBlank()
                    ) { Text("Add") }
                }

                if (categories.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.take(4).forEach { c ->
                            FilterChip(
                                selected = selectedCatId == c.id,
                                onClick = { selectedCatId = if (selectedCatId == c.id) null else c.id },
                                label = { Text(c.name) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reorderText,
                        onValueChange = { reorderText = it },
                        label = { Text("Reorder Level") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (existing == null) {
                        OutlinedTextField(
                            value = openingStockText,
                            onValueChange = { openingStockText = it },
                            label = { Text("Opening Stock") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("product_opening_stock_input")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (imageUri == null) "Attach Photo" else "Photo Attached")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = active, onCheckedChange = { active = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Active")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ProductDraftData(
                            name = name,
                            description = description,
                            sku = sku,
                            barcode = barcode.ifBlank { null },
                            categoryId = selectedCatId,
                            brandId = null,
                            unitId = selectedUnitId,
                            hsnCode = hsn,
                            gstRateBps = gstBps,
                            taxCategory = if (gstBps == 0) TaxCategory.NIL_RATED else TaxCategory.TAXABLE,
                            taxInclusive = taxInclusive,
                            costPaise = MoneyFormatter.parseRupeesToPaise(costText) ?: 0L,
                            sellPaise = MoneyFormatter.parseRupeesToPaise(sellText) ?: 0L,
                            mrpPaise = MoneyFormatter.parseRupeesToPaise(mrpText),
                            reorderScaled = QuantityScale.parseScaled(reorderText) ?: QuantityScale.fromWhole(5),
                            openingStockScaled = QuantityScale.parseScaled(openingStockText) ?: 0L,
                            imageUri = imageUri,
                            active = active
                        )
                    )
                },
                modifier = Modifier.testTag("product_save_button")
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
