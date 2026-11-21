package com.example.retailx.lan.client

import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.CompleteSaleCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class LanConnectionState(val label: String) {
    STANDALONE("Standalone"),
    CONNECTED("Host Connected"),
    DISCONNECTED("Host Unreachable")
}

data class LanPairingResult(
    val deviceId: String,
    val deviceToken: String,
    val deviceName: String
)

class LanApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val _connectionState = MutableStateFlow(LanConnectionState.STANDALONE)
    val connectionState: StateFlow<LanConnectionState> = _connectionState.asStateFlow()

    fun setStandalone() {
        _connectionState.value = LanConnectionState.STANDALONE
    }

    suspend fun checkHealth(baseUrl: String): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/health")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    _connectionState.value = LanConnectionState.DISCONNECTED
                    return@withContext AppResult.Failure(AppError.HostUnavailable("Host returned HTTP ${resp.code}"))
                }
                val data = JSONObject(raw).getJSONObject("data")
                _connectionState.value = LanConnectionState.CONNECTED
                AppResult.Success(data.optString("storeName", "RetailX Host"))
            }
        } catch (ex: Exception) {
            _connectionState.value = LanConnectionState.DISCONNECTED
            AppResult.Failure(AppError.HostUnavailable(ex.message ?: "Host unreachable"))
        }
    }

    suspend fun completePairing(
        baseUrl: String,
        pairingCode: String,
        deviceName: String
    ): AppResult<LanPairingResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("pairingCode", pairingCode.trim())
                put("deviceName", deviceName.trim())
            }.toString()
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/pairing/complete")
                .post(payload.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                if (!resp.isSuccessful) {
                    val msg = obj.optJSONObject("error")?.optString("message") ?: "Pairing rejected"
                    return@withContext AppResult.Failure(AppError.Unauthorized(msg))
                }
                val data = obj.getJSONObject("data")
                _connectionState.value = LanConnectionState.CONNECTED
                AppResult.Success(
                    LanPairingResult(
                        deviceId = data.getString("deviceId"),
                        deviceToken = data.getString("deviceToken"),
                        deviceName = data.getString("deviceName")
                    )
                )
            }
        } catch (ex: Exception) {
            _connectionState.value = LanConnectionState.DISCONNECTED
            AppResult.Failure(AppError.HostUnavailable(ex.message ?: "Cannot reach host"))
        }
    }

    suspend fun loginOnHost(
        baseUrl: String,
        deviceId: String,
        deviceToken: String,
        username: String,
        secret: String,
        isPin: Boolean
    ): AppResult<User> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("username", username)
                put("secret", secret)
                put("isPin", isPin)
            }.toString()
            val req = authedRequest(baseUrl, "/api/v1/auth/login", deviceId, deviceToken)
                .post(payload.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                if (!resp.isSuccessful) {
                    val msg = obj.optJSONObject("error")?.optString("message") ?: "Authentication failed"
                    return@withContext AppResult.Failure(AppError.Unauthorized(msg))
                }
                _connectionState.value = LanConnectionState.CONNECTED
                val d = obj.getJSONObject("data")
                val permsArr = d.getJSONArray("permissions")
                val perms = (0 until permsArr.length()).map { permsArr.getString(it) }.toSet()
                AppResult.Success(
                    User(
                        id = d.getString("id"),
                        username = d.getString("username"),
                        fullName = d.getString("fullName"),
                        roleId = d.getString("roleId"),
                        roleName = d.getString("roleName"),
                        permissions = perms,
                        active = d.getBoolean("active"),
                        hasQuickPin = d.getBoolean("hasQuickPin"),
                        createdAt = d.getLong("createdAt")
                    )
                )
            }
        } catch (ex: Exception) {
            _connectionState.value = LanConnectionState.DISCONNECTED
            AppResult.Failure(AppError.HostUnavailable(ex.message ?: "Host unreachable"))
        }
    }

    suspend fun fetchProductsFromHost(
        baseUrl: String,
        deviceId: String,
        deviceToken: String
    ): AppResult<List<Product>> = withContext(Dispatchers.IO) {
        try {
            val req = authedRequest(baseUrl, "/api/v1/products", deviceId, deviceToken).get().build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext AppResult.Failure(AppError.HostUnavailable("Failed to load host catalog"))
                }
                _connectionState.value = LanConnectionState.CONNECTED
                val arr = JSONObject(raw).getJSONObject("data").getJSONArray("items")
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Product(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description"),
                        sku = o.getString("sku"),
                        barcode = o.optString("barcode").ifBlank { null },
                        categoryId = o.optString("categoryId").ifBlank { null },
                        categoryName = o.optString("categoryName", "Uncategorized"),
                        brandId = null,
                        brandName = "Store",
                        unitId = o.optString("unitId", "unit_pcs"),
                        unitShortName = o.optString("unitShortName", "Pcs"),
                        hsnCode = o.optString("hsnCode"),
                        gstRateBps = o.getInt("gstRateBps"),
                        taxCategory = TaxCategory.entries.firstOrNull { it.name == o.optString("taxCategory") } ?: TaxCategory.TAXABLE,
                        taxInclusive = o.optBoolean("taxInclusive", true),
                        costPricePaise = o.optLong("costPricePaise", 0L),
                        sellingPricePaise = o.getLong("sellingPricePaise"),
                        mrpPaise = o.optLong("mrpPaise", 0L).takeIf { it > 0L },
                        reorderLevelScaled = o.optLong("reorderLevelScaled", 5000L),
                        imageUri = null,
                        active = true,
                        defaultVariantId = o.getString("defaultVariantId"),
                        stockScaled = o.getLong("stockScaled")
                    )
                }
                AppResult.Success(list)
            }
        } catch (ex: Exception) {
            _connectionState.value = LanConnectionState.DISCONNECTED
            AppResult.Failure(AppError.HostUnavailable(ex.message ?: "Host unreachable"))
        }
    }

    suspend fun completeSaleOnHost(
        baseUrl: String,
        deviceId: String,
        deviceToken: String,
        command: CompleteSaleCommand
    ): AppResult<Sale> = withContext(Dispatchers.IO) {
        try {
            val itemsArr = JSONArray().apply {
                command.items.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("lineId", item.lineId)
                            put("productId", item.productId)
                            put("productVariantId", item.productVariantId)
                            put("name", item.name)
                            put("sku", item.sku)
                            put("hsnCode", item.hsnCode)
                            put("unitShortName", item.unitShortName)
                            put("qtyScaled", item.qtyScaled)
                            put("unitPricePaise", item.unitPricePaise)
                            put("costPricePaise", item.costPricePaise)
                            put("lineDiscountPaise", item.lineDiscountPaise)
                            put("gstRateBps", item.gstRateBps)
                            put("taxCategory", item.taxCategory.name)
                            put("taxInclusive", item.taxInclusive)
                            put("maxAvailableScaled", item.maxAvailableScaled)
                        }
                    )
                }
            }
            val payArr = JSONArray().apply {
                command.payments.forEach { p ->
                    put(
                        JSONObject().apply {
                            put("walletId", p.walletId ?: "")
                            put("method", p.method.name)
                            put("amountPaise", p.amountPaise)
                            put("reference", p.reference)
                        }
                    )
                }
            }
            val payload = JSONObject().apply {
                put("idempotencyKey", command.idempotencyKey)
                put("customerId", command.customerId ?: "")
                put("placeOfSupplyStateCode", command.placeOfSupplyStateCode)
                put("billDiscountPaise", command.billDiscountPaise)
                put("actorUserId", command.actorUserId)
                put("items", itemsArr)
                put("payments", payArr)
            }.toString()

            val req = authedRequest(baseUrl, "/api/v1/sales", deviceId, deviceToken)
                .post(payload.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                if (!resp.isSuccessful) {
                    val err = obj.optJSONObject("error")
                    val code = err?.optString("code") ?: "ERROR"
                    val msg = err?.optString("message") ?: "Host rejected sale"
                    return@withContext when (code) {
                        "INSUFFICIENT_STOCK" -> AppResult.Failure(AppError.InsufficientStock(msg))
                        "UNAUTHORIZED_DEVICE", "DEVICE_REVOKED_OR_INVALID", "FORBIDDEN" -> AppResult.Failure(AppError.Unauthorized(msg))
                        else -> AppResult.Failure(AppError.ValidationError(msg))
                    }
                }
                _connectionState.value = LanConnectionState.CONNECTED
                val d = obj.getJSONObject("data")
                AppResult.Success(
                    Sale(
                        id = d.getString("id"),
                        invoiceNo = d.getString("invoiceNo"),
                        customerId = command.customerId,
                        customerName = d.getString("customerName"),
                        customerGstin = "",
                        placeOfSupplyStateCode = command.placeOfSupplyStateCode,
                        isInterState = false,
                        cashierId = command.actorUserId,
                        cashierName = d.getString("cashierName"),
                        status = SaleStatus.valueOf(d.getString("status")),
                        subtotalPaise = d.getLong("totalPaise"),
                        discountPaise = command.billDiscountPaise,
                        taxablePaise = d.getLong("taxablePaise"),
                        cgstPaise = d.getLong("cgstPaise"),
                        sgstPaise = d.getLong("sgstPaise"),
                        igstPaise = d.getLong("igstPaise"),
                        taxPaise = d.getLong("taxPaise"),
                        roundOffPaise = 0L,
                        totalPaise = d.getLong("totalPaise"),
                        paidPaise = d.getLong("paidPaise"),
                        balanceDuePaise = d.getLong("balanceDuePaise"),
                        voidReason = null,
                        createdAt = d.getLong("createdAt")
                    )
                )
            }
        } catch (ex: Exception) {
            _connectionState.value = LanConnectionState.DISCONNECTED
            AppResult.Failure(AppError.HostUnavailable(ex.message ?: "Host disconnected during checkout. Retry safely with same idempotency key."))
        }
    }

    private fun authedRequest(
        baseUrl: String,
        path: String,
        deviceId: String,
        deviceToken: String
    ): Request.Builder {
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Device-Id", deviceId)
            .header("Authorization", "Bearer $deviceToken")
    }
}
