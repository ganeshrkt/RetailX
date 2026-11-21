package com.example.retailx.lan.host

import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.ActivityLogEntity
import com.example.retailx.core.database.entity.DeviceEntity
import com.example.retailx.core.model.*
import com.example.retailx.core.security.DeviceCrypto
import com.example.retailx.data.local.*
import com.example.retailx.domain.repository.CompleteSaleCommand
import com.example.retailx.domain.repository.CompleteSalePaymentInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActivePairingChallenge(
    val code: String,
    val expiresAtMillis: Long
)

data class LanHostStatus(
    val isRunning: Boolean = false,
    val ipAddress: String = "127.0.0.1",
    val port: Int = 8443,
    val activeChallenge: ActivePairingChallenge? = null,
    val lastError: String? = null,
    val requestCount: Long = 0L
)

class LanHostServer(
    private val dbProvider: () -> RetailXDatabase,
    private val storeAuthRepoProvider: () -> LocalStoreAndAuthRepository,
    private val catalogRepoProvider: () -> LocalCatalogRepository,
    private val salesRepoProvider: () -> LocalSalesRepository,
    private val partyRepoProvider: () -> LocalPartiesAndPurchasesRepository,
    private val financeRepoProvider: () -> LocalFinanceAndReportsRepository
) {
    private val _status = MutableStateFlow(LanHostStatus())
    val status: StateFlow<LanHostStatus> = _status.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateWindow = ConcurrentHashMap<String, Pair<Long, Int>>()

    fun start(port: Int) {
        if (_status.value.isRunning) return
        val ip = findLocalIpv4Address() ?: "127.0.0.1"
        try {
            val socket = ServerSocket(port)
            serverSocket = socket
            _status.value = LanHostStatus(
                isRunning = true,
                ipAddress = ip,
                port = socket.localPort,
                activeChallenge = _status.value.activeChallenge
            )
            serverJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleConnection(client) }
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            }
        } catch (ex: Exception) {
            _status.value = _status.value.copy(
                isRunning = false,
                lastError = ex.message ?: "Failed to bind port $port"
            )
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _status.value = _status.value.copy(isRunning = false, activeChallenge = null)
    }

    fun generatePairingChallenge(): ActivePairingChallenge {
        val challenge = ActivePairingChallenge(
            code = DeviceCrypto.generatePairingCode(),
            expiresAtMillis = System.currentTimeMillis() + 5 * 60_000L
        )
        _status.value = _status.value.copy(
            ipAddress = findLocalIpv4Address() ?: _status.value.ipAddress,
            activeChallenge = challenge
        )
        return challenge
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val rawRequest = readHttpRequest(input) ?: return
            val remoteIp = sock.inetAddress.hostAddress ?: "unknown"

            if (!checkRateLimit(remoteIp)) {
                writeJsonResponse(sock, 429, errorJson("req", "RATE_LIMITED", "Too many requests"))
                return
            }

            _status.value = _status.value.copy(requestCount = _status.value.requestCount + 1L)

            val response = try {
                routeRequest(rawRequest)
            } catch (ex: Exception) {
                400 to errorJson("req", "BAD_REQUEST", ex.message ?: "Malformed request")
            }
            writeJsonResponse(sock, response.first, response.second)
        }
    }

    private suspend fun routeRequest(req: ParsedHttpRequest): Pair<Int, String> {
        val path = req.path.substringBefore('?')
        val reqId = req.headers["x-request-id"] ?: UUID.randomUUID().toString()

        if (req.method == "GET" && path == "/api/v1/health") {
            val store = storeAuthRepoProvider().getStoreSettings()
            val data = JSONObject().apply {
                put("status", "ok")
                put("storeName", store?.name ?: "RetailX Store")
                put("stateCode", store?.stateCode ?: "27")
                put("serverTime", System.currentTimeMillis())
            }
            return 200 to successJson(reqId, data)
        }

        if (req.method == "POST" && path == "/api/v1/pairing/complete") {
            val body = JSONObject(req.body.ifBlank { "{}" })
            val code = body.optString("pairingCode")
            val deviceName = body.optString("deviceName", "Client Tablet").trim().ifBlank { "Client Tablet" }
            val active = _status.value.activeChallenge
            if (active == null || System.currentTimeMillis() > active.expiresAtMillis || !DeviceCrypto.constantTimeEquals(active.code, code)) {
                return 401 to errorJson(reqId, "INVALID_PAIRING_CODE", "Pairing code is invalid or expired")
            }
            val deviceId = UUID.randomUUID().toString()
            val rawToken = DeviceCrypto.generateSecureToken(32)
            val tokenHash = DeviceCrypto.sha256Hex(rawToken)
            val now = System.currentTimeMillis()
            val db = dbProvider()
            db.storeAndAuthDao().upsertDevice(
                DeviceEntity(
                    id = deviceId,
                    name = deviceName,
                    deviceKeyHash = tokenHash,
                    status = "ACTIVE",
                    pairedAt = now,
                    lastSeenAt = now
                )
            )
            db.financeAndAuditDao().insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = null,
                    username = "LAN_PAIRING",
                    deviceId = deviceId,
                    action = "DEVICE_PAIRED",
                    entityType = "DEVICE",
                    entityId = deviceId,
                    metadataJson = """{"deviceName":"$deviceName"}""",
                    createdAt = now
                )
            )
            // Single-use pairing challenge invalidated after pairing
            _status.value = _status.value.copy(activeChallenge = null)

            val data = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceToken", rawToken)
                put("deviceName", deviceName)
            }
            return 200 to successJson(reqId, data)
        }

        // All subsequent endpoints require valid paired device bearer credentials
        val deviceId = req.headers["x-device-id"].orEmpty()
        val authHeader = req.headers["authorization"].orEmpty()
        val bearer = if (authHeader.startsWith("Bearer ", ignoreCase = true)) authHeader.substring(7).trim() else ""
        if (deviceId.isBlank() || bearer.isBlank()) {
            return 401 to errorJson(reqId, "UNAUTHORIZED_DEVICE", "Missing device credentials")
        }
        val db = dbProvider()
        val device = db.storeAndAuthDao().getDeviceById(deviceId)
        if (device == null || device.status != "ACTIVE" || !DeviceCrypto.constantTimeEquals(device.deviceKeyHash, DeviceCrypto.sha256Hex(bearer))) {
            return 401 to errorJson(reqId, "DEVICE_REVOKED_OR_INVALID", "Device is not authorized")
        }
        db.storeAndAuthDao().updateDeviceLastSeen(deviceId, System.currentTimeMillis())

        return when {
            req.method == "POST" && path == "/api/v1/auth/login" -> {
                val body = JSONObject(req.body)
                val username = body.optString("username")
                val secret = body.optString("secret")
                val isPin = body.optBoolean("isPin", false)
                val res = if (isPin) {
                    storeAuthRepoProvider().loginWithPin(username, secret)
                } else {
                    storeAuthRepoProvider().loginWithPassword(username, secret)
                }
                when (res) {
                    is AppResult.Success -> 200 to successJson(reqId, userToJson(res.data))
                    is AppResult.Failure -> 401 to errorJson(reqId, res.error.code, res.error.userMessage)
                }
            }

            req.method == "GET" && path == "/api/v1/store" -> {
                val store = storeAuthRepoProvider().getStoreSettings()
                    ?: return 404 to errorJson(reqId, "STORE_NOT_SETUP", "Store is not configured on host")
                200 to successJson(reqId, storeToJson(store))
            }

            req.method == "GET" && path == "/api/v1/products" -> {
                val products = db.catalogDao().getAllProducts()
                val variants = db.catalogDao().getAllVariants().groupBy { it.productId }
                val balances = db.catalogDao().getAllStockBalances().associateBy { it.productVariantId }
                val units = db.catalogDao().getAllUnits().associateBy { it.id }
                val cats = db.catalogDao().getAllCategories().associateBy { it.id }
                val arr = JSONArray()
                products.filter { it.active }.forEach { p ->
                    val pVars = variants[p.id].orEmpty()
                    val stock = pVars.sumOf { balances[it.id]?.quantityScaled ?: 0L }
                    arr.put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("description", p.description)
                            put("sku", p.sku)
                            put("barcode", p.barcode ?: "")
                            put("categoryId", p.categoryId ?: "")
                            put("categoryName", p.categoryId?.let { cats[it]?.name } ?: "Uncategorized")
                            put("unitId", p.unitId)
                            put("unitShortName", units[p.unitId]?.shortName ?: "Pcs")
                            put("hsnCode", p.hsnCode)
                            put("gstRateBps", p.gstRateBps)
                            put("taxCategory", p.taxCategory)
                            put("taxInclusive", p.taxInclusive)
                            put("costPricePaise", p.costPricePaise)
                            put("sellingPricePaise", p.sellingPricePaise)
                            put("mrpPaise", p.mrpPaise ?: 0L)
                            put("reorderLevelScaled", p.reorderLevelScaled)
                            put("defaultVariantId", pVars.firstOrNull()?.id ?: "${p.id}_v1")
                            put("stockScaled", stock)
                        }
                    )
                }
                200 to successJson(reqId, JSONObject().put("items", arr))
            }

            req.method == "GET" && path.startsWith("/api/v1/products/barcode/") -> {
                val code = URLDecoder.decode(path.removePrefix("/api/v1/products/barcode/"), "UTF-8")
                val prod = catalogRepoProvider().findByBarcodeOrSku(code)
                    ?: return 404 to errorJson(reqId, "NOT_FOUND", "No product for code $code")
                200 to successJson(
                    reqId,
                    JSONObject().apply {
                        put("id", prod.id)
                        put("name", prod.name)
                        put("sku", prod.sku)
                        put("barcode", prod.barcode ?: "")
                        put("defaultVariantId", prod.defaultVariantId)
                        put("sellingPricePaise", prod.sellingPricePaise)
                        put("gstRateBps", prod.gstRateBps)
                        put("stockScaled", prod.stockScaled)
                    }
                )
            }

            req.method == "GET" && path == "/api/v1/customers" -> {
                val customers = db.purchaseAndPartyDao().getAllCustomers()
                val arr = JSONArray()
                customers.forEach { c ->
                    arr.put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("name", c.name)
                            put("mobile", c.mobile)
                            put("stateCode", c.stateCode)
                            put("gstin", c.gstin)
                            put("isWalkIn", c.isWalkIn)
                            put("currentBalancePaise", c.currentBalancePaise)
                            put("creditLimitPaise", c.creditLimitPaise)
                        }
                    )
                }
                200 to successJson(reqId, JSONObject().put("items", arr))
            }

            req.method == "POST" && path == "/api/v1/sales" -> {
                val body = JSONObject(req.body)
                val actorUserId = body.optString("actorUserId")
                val user = db.storeAndAuthDao().getUserById(actorUserId)
                    ?: return 401 to errorJson(reqId, "UNAUTHORIZED_USER", "Unknown cashier")
                val role = db.storeAndAuthDao().getRoleForUser(user.id)
                val perms = if (role != null) db.storeAndAuthDao().getPermissionCodesForRole(role.id) else emptyList()
                if (!perms.contains(PermissionCode.POS_BILLING.code)) {
                    return 403 to errorJson(reqId, "FORBIDDEN", "User lacks POS billing permission")
                }

                val itemsArr = body.getJSONArray("items")
                val items = (0 until itemsArr.length()).map { idx ->
                    val o = itemsArr.getJSONObject(idx)
                    CartItemInput(
                        lineId = o.getString("lineId"),
                        productId = o.getString("productId"),
                        productVariantId = o.getString("productVariantId"),
                        name = o.getString("name"),
                        sku = o.getString("sku"),
                        hsnCode = o.optString("hsnCode"),
                        unitShortName = o.optString("unitShortName", "Pcs"),
                        qtyScaled = o.getLong("qtyScaled"),
                        unitPricePaise = o.getLong("unitPricePaise"),
                        costPricePaise = o.optLong("costPricePaise", 0L),
                        lineDiscountPaise = o.optLong("lineDiscountPaise", 0L),
                        gstRateBps = o.optInt("gstRateBps", 0),
                        taxCategory = TaxCategory.entries.firstOrNull { it.name == o.optString("taxCategory") } ?: TaxCategory.TAXABLE,
                        taxInclusive = o.optBoolean("taxInclusive", true),
                        maxAvailableScaled = o.optLong("maxAvailableScaled", 0L)
                    )
                }
                val payArr = body.getJSONArray("payments")
                val payments = (0 until payArr.length()).map { idx ->
                    val o = payArr.getJSONObject(idx)
                    CompleteSalePaymentInput(
                        walletId = o.optString("walletId").ifBlank { null },
                        method = PaymentMethod.valueOf(o.getString("method")),
                        amountPaise = o.getLong("amountPaise"),
                        reference = o.optString("reference")
                    )
                }

                val cmd = CompleteSaleCommand(
                    idempotencyKey = body.getString("idempotencyKey"),
                    customerId = body.optString("customerId").ifBlank { null },
                    placeOfSupplyStateCode = body.optString("placeOfSupplyStateCode", "27"),
                    billDiscountPaise = body.optLong("billDiscountPaise", 0L),
                    items = items,
                    payments = payments,
                    actorUserId = user.id,
                    actorName = user.fullName
                )
                when (val res = salesRepoProvider().completeSale(cmd)) {
                    is AppResult.Success -> 200 to successJson(reqId, saleToJson(res.data))
                    is AppResult.Failure -> 409 to errorJson(reqId, res.error.code, res.error.userMessage)
                }
            }

            req.method == "GET" && path == "/api/v1/reports/sales" -> {
                val (from, to) = DateTimeUtils.todayRange()
                val bundle = financeRepoProvider().getReportsBundle(from, to)
                200 to successJson(
                    reqId,
                    JSONObject().apply {
                        put("billCount", bundle.billCount)
                        put("grossSalesPaise", bundle.grossSalesPaise)
                        put("netSalesPaise", bundle.netSalesPaise)
                        put("estimatedProfitPaise", bundle.estimatedProfitPaise)
                    }
                )
            }

            else -> 404 to errorJson(reqId, "NOT_FOUND", "Endpoint not found: $path")
        }
    }

    private fun checkRateLimit(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = rateWindow[ip]
        if (prev == null || now - prev.first > 10_000L) {
            rateWindow[ip] = now to 1
            return true
        }
        val nextCount = prev.second + 1
        rateWindow[ip] = prev.first to nextCount
        return nextCount <= 120
    }

    private fun userToJson(u: User): JSONObject = JSONObject().apply {
        put("id", u.id)
        put("username", u.username)
        put("fullName", u.fullName)
        put("roleId", u.roleId)
        put("roleName", u.roleName)
        put("permissions", JSONArray(u.permissions.toList()))
        put("active", u.active)
        put("hasQuickPin", u.hasQuickPin)
        put("createdAt", u.createdAt)
    }

    private fun storeToJson(s: StoreSettings): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("tagline", s.tagline)
        put("gstin", s.gstin)
        put("stateCode", s.stateCode)
        put("address", s.address)
        put("phone", s.phone)
        put("email", s.email)
        put("timezone", s.timezone)
        put("invoicePrefix", s.invoicePrefix)
        put("invoiceFooter", s.invoiceFooter)
        put("defaultTaxInclusive", s.defaultTaxInclusive)
        put("allowNegativeStock", s.allowNegativeStock)
        put("autoRoundInvoice", s.autoRoundInvoice)
    }

    private fun saleToJson(s: Sale): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("invoiceNo", s.invoiceNo)
        put("customerName", s.customerName)
        put("cashierName", s.cashierName)
        put("status", s.status.name)
        put("taxablePaise", s.taxablePaise)
        put("cgstPaise", s.cgstPaise)
        put("sgstPaise", s.sgstPaise)
        put("igstPaise", s.igstPaise)
        put("taxPaise", s.taxPaise)
        put("totalPaise", s.totalPaise)
        put("paidPaise", s.paidPaise)
        put("balanceDuePaise", s.balanceDuePaise)
        put("createdAt", s.createdAt)
    }

    private fun successJson(requestId: String, data: JSONObject): String =
        JSONObject().apply {
            put("requestId", requestId)
            put("operationId", UUID.randomUUID().toString())
            put("status", "completed")
            put("data", data)
        }.toString()

    private fun errorJson(requestId: String, code: String, message: String): String =
        JSONObject().apply {
            put("requestId", requestId)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()

    private data class ParsedHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedHttpRequest? {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
            if (headerBuf.size() > 64 * 1024) return null
        }

        val headerLines = headerBuf.toString(Charsets.UTF_8.name()).split("\r\n").filter { it.isNotEmpty() }
        if (headerLines.isEmpty()) return null
        val requestParts = headerLines[0].split(" ")
        if (requestParts.size < 2) return null
        val method = requestParts[0].uppercase()
        val path = requestParts[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until headerLines.size) {
            val idx = headerLines[i].indexOf(':')
            if (idx > 0) {
                headers[headerLines[i].substring(0, idx).trim().lowercase()] =
                    headerLines[i].substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return ParsedHttpRequest(method, path, headers, String(bodyBytes, 0, read, Charsets.UTF_8))
    }

    private fun writeJsonResponse(socket: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        fun findLocalIpv4Address(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
