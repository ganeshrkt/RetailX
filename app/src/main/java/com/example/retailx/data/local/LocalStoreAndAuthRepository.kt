package com.example.retailx.data.local

import androidx.room.withTransaction
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.*
import com.example.retailx.core.model.*
import com.example.retailx.core.security.PasswordHasher
import com.example.retailx.domain.repository.AuthRepository
import com.example.retailx.domain.repository.StoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

class LocalStoreAndAuthRepository(
    private val db: RetailXDatabase
) : StoreRepository, AuthRepository {

    private val dao = db.storeAndAuthDao()
    private val financeDao = db.financeAndAuditDao()

    override fun observeStoreSettings(): Flow<StoreSettings?> =
        dao.observeStore().map { it?.toModel() }

    override suspend fun getStoreSettings(): StoreSettings? =
        dao.getStore()?.toModel()

    override suspend fun setupInitialStore(
        storeName: String,
        gstin: String,
        stateCode: String,
        address: String,
        phone: String,
        email: String,
        invoicePrefix: String,
        adminUsername: String,
        adminFullName: String,
        adminPassword: String,
        adminPin: String,
        loadStarterCatalog: Boolean
    ): AppResult<User> {
        if (storeName.isBlank()) return AppResult.Failure(AppError.ValidationError("Store name is required"))
        if (adminUsername.isBlank() || adminPassword.length < 4) {
            return AppResult.Failure(AppError.ValidationError("Admin username and password (min 4 chars) are required"))
        }

        val now = System.currentTimeMillis()
        val adminUserId = UUID.randomUUID().toString()
        val ownerRoleId = "role_owner"

        db.withTransaction {
            val store = StoreEntity(
                id = "store_main",
                name = storeName.trim(),
                tagline = "GST Tax Invoice & Retail POS",
                gstin = gstin.trim().uppercase(),
                stateCode = stateCode.ifBlank { "27" },
                address = address.trim(),
                phone = phone.trim(),
                email = email.trim(),
                timezone = "Asia/Kolkata",
                invoicePrefix = invoicePrefix.trim().uppercase().ifBlank { "INV" },
                returnPrefix = "RET",
                purchasePrefix = "PUR",
                invoiceFooter = "Thank you for shopping with us! Goods once sold will be exchanged per store policy.",
                defaultTaxInclusive = true,
                allowNegativeStock = false,
                autoRoundInvoice = true,
                defaultInvoiceFormat = InvoiceFormat.A4.name,
                varianceApprovalThresholdPaise = 50_000L, // ₹500
                logoUri = null,
                isSetupComplete = true
            )
            dao.upsertStore(store)

            val permissionEntities = PermissionCode.entries.map {
                PermissionEntity(id = "perm_${it.name.lowercase()}", code = it.code)
            }
            dao.insertPermissions(permissionEntities)

            val roles = listOf(
                RoleEntity(ownerRoleId, "Owner / Admin"),
                RoleEntity("role_manager", "Manager"),
                RoleEntity("role_cashier", "Cashier"),
                RoleEntity("role_accountant", "Accountant")
            )
            dao.insertRoles(roles)

            val permByCode = permissionEntities.associateBy { it.code }
            fun assignPerms(roleId: String, codes: List<PermissionCode>) {
                // handled via suspend below
            }
            val ownerPerms = PermissionCode.entries.mapNotNull { permByCode[it.code] }
                .map { RolePermissionEntity(ownerRoleId, it.id) }
            val managerPerms = listOf(
                PermissionCode.POS_BILLING, PermissionCode.POS_DISCOUNT, PermissionCode.POS_PRICE_OVERRIDE,
                PermissionCode.SALES_VIEW, PermissionCode.SALES_RETURN, PermissionCode.SALES_VOID,
                PermissionCode.CATALOG_MANAGE, PermissionCode.INVENTORY_ADJUST,
                PermissionCode.PURCHASE_MANAGE, PermissionCode.PARTIES_MANAGE,
                PermissionCode.ACCOUNTS_MANAGE, PermissionCode.CASH_REGISTER_MANAGE,
                PermissionCode.CASH_REGISTER_APPROVE, PermissionCode.REPORTS_VIEW
            ).mapNotNull { permByCode[it.code] }.map { RolePermissionEntity("role_manager", it.id) }
            val cashierPerms = listOf(
                PermissionCode.POS_BILLING, PermissionCode.POS_DISCOUNT,
                PermissionCode.SALES_VIEW, PermissionCode.SALES_RETURN,
                PermissionCode.PARTIES_MANAGE, PermissionCode.CASH_REGISTER_MANAGE
            ).mapNotNull { permByCode[it.code] }.map { RolePermissionEntity("role_cashier", it.id) }
            val accountantPerms = listOf(
                PermissionCode.SALES_VIEW, PermissionCode.PURCHASE_MANAGE,
                PermissionCode.PARTIES_MANAGE, PermissionCode.ACCOUNTS_MANAGE,
                PermissionCode.CASH_REGISTER_APPROVE, PermissionCode.REPORTS_VIEW
            ).mapNotNull { permByCode[it.code] }.map { RolePermissionEntity("role_accountant", it.id) }

            dao.insertRolePermissions(ownerPerms + managerPerms + cashierPerms + accountantPerms)

            val pwHashed = PasswordHasher.hashSecret(adminPassword)
            val pinHashed = if (adminPin.isNotBlank()) PasswordHasher.hashSecret(adminPin) else null
            val adminEntity = UserEntity(
                id = adminUserId,
                username = adminUsername.trim().lowercase(),
                fullName = adminFullName.trim().ifBlank { "Store Owner" },
                passwordHash = pwHashed.hashBase64,
                passwordSalt = pwHashed.saltBase64,
                pinHash = pinHashed?.hashBase64,
                pinSalt = pinHashed?.saltBase64,
                status = "ACTIVE",
                failedAttempts = 0,
                lockedUntil = null,
                createdAt = now
            )
            dao.insertUser(adminEntity)
            dao.insertUserRole(UserRoleEntity(adminUserId, ownerRoleId))

            seedBaseOperationalDefaults(now, stateCode.ifBlank { "27" })
            if (loadStarterCatalog) {
                seedStarterCatalog(now, stateCode.ifBlank { "27" })
            }

            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = adminUserId,
                    username = adminEntity.username,
                    deviceId = "local",
                    action = "STORE_SETUP",
                    entityType = "STORE",
                    entityId = store.id,
                    metadataJson = """{"storeName":"${store.name}","starterCatalog":$loadStarterCatalog}""",
                    createdAt = now
                )
            )
        }

        val createdUser = buildUserModel(dao.getUserById(adminUserId)!!)
        return AppResult.Success(createdUser)
    }

    private suspend fun seedBaseOperationalDefaults(now: Long, stateCode: String) {
        val catDao = db.catalogDao()
        val partyDao = db.purchaseAndPartyDao()

        catDao.insertUnits(
            listOf(
                UnitEntity("unit_pcs", "Piece", "Pcs"),
                UnitEntity("unit_kg", "Kilogram", "Kg"),
                UnitEntity("unit_g", "Gram", "g"),
                UnitEntity("unit_ltr", "Litre", "Ltr"),
                UnitEntity("unit_box", "Box", "Box"),
                UnitEntity("unit_pack", "Pack", "Pack"),
                UnitEntity("unit_m", "Meter", "m")
            )
        )

        val wallets = listOf(
            WalletEntity("wallet_cash", "Cash Drawer", WalletType.CASH.name, 0L, 0L, true),
            WalletEntity("wallet_upi", "UPI Merchant", WalletType.UPI.name, 0L, 0L, true),
            WalletEntity("wallet_bank", "Main Bank Account", WalletType.BANK.name, 0L, 0L, true),
            WalletEntity("wallet_card", "Card Clearing", WalletType.CARD_CLEARING.name, 0L, 0L, true)
        )
        wallets.forEach { financeDao.upsertWallet(it) }

        financeDao.insertExpenseCategories(
            listOf(
                ExpenseCategoryEntity("exp_rent", "Rent & Utilities"),
                ExpenseCategoryEntity("exp_staff", "Staff & Refreshments"),
                ExpenseCategoryEntity("exp_freight", "Transport & Freight"),
                ExpenseCategoryEntity("exp_pack", "Packaging & Supplies"),
                ExpenseCategoryEntity("exp_maint", "Store Maintenance")
            )
        )

        partyDao.upsertCustomer(
            CustomerEntity(
                id = "cust_walkin",
                name = "Walk-in Customer",
                mobile = "",
                email = "",
                address = "",
                stateCode = stateCode,
                gstin = "",
                customerType = CustomerType.RETAIL.name,
                openingBalancePaise = 0L,
                currentBalancePaise = 0L,
                creditLimitPaise = 0L,
                defaultDiscountBps = 0,
                isWalkIn = true,
                createdAt = now
            )
        )
    }

    private suspend fun seedStarterCatalog(now: Long, stateCode: String) {
        val catDao = db.catalogDao()
        val partyDao = db.purchaseAndPartyDao()

        val categories = listOf(
            CategoryEntity("cat_grocery", "Staples & Grocery"),
            CategoryEntity("cat_beverages", "Beverages & Snacks"),
            CategoryEntity("cat_personal", "Personal Care"),
            CategoryEntity("cat_elec", "Accessories & Utilities")
        )
        categories.forEach { catDao.upsertCategory(it) }

        val brands = listOf(
            BrandEntity("brand_house", "RetailX Select"),
            BrandEntity("brand_metro", "Metro Fresh"),
            BrandEntity("brand_volt", "VoltTech")
        )
        brands.forEach { catDao.upsertBrand(it) }

        data class SeedItem(
            val id: String,
            val name: String,
            val sku: String,
            val barcode: String,
            val catId: String,
            val brandId: String,
            val unitId: String,
            val hsn: String,
            val gstBps: Int,
            val costPaise: Long,
            val sellPaise: Long,
            val mrpPaise: Long,
            val stockUnits: Int
        )

        val items = listOf(
            SeedItem("prod_1", "Basmati Rice Premium 5Kg", "GRC-RICE-5K", "8901001000011", "cat_grocery", "brand_metro", "unit_pack", "1006", 500, 42000, 52000, 55000, 24),
            SeedItem("prod_2", "Cold-Pressed Mustard Oil 1L", "GRC-OIL-1L", "8901001000028", "cat_grocery", "brand_metro", "unit_ltr", "1514", 500, 16500, 19800, 21500, 35),
            SeedItem("prod_3", "Masala Chai Leaf Pack 250g", "BEV-TEA-250", "8901001000035", "cat_beverages", "brand_house", "unit_pack", "0902", 500, 9500, 13000, 14000, 40),
            SeedItem("prod_4", "Roasted Arabica Filter Coffee 200g", "BEV-COF-200", "8901001000042", "cat_beverages", "brand_house", "unit_pack", "0901", 1200, 18000, 24500, 26000, 18),
            SeedItem("prod_5", "Artisanal Whole Wheat Biscuits", "BEV-BIS-150", "8901001000059", "cat_beverages", "brand_metro", "unit_pack", "1905", 1800, 3200, 4500, 5000, 60),
            SeedItem("prod_6", "Herbal Aloe Face Wash 100ml", "PER-FW-100", "8901001000066", "cat_personal", "brand_house", "unit_pcs", "3304", 1800, 11000, 16500, 18500, 28),
            SeedItem("prod_7", "Neem & Tulsi Bath Soap (Pack of 3)", "PER-SOP-3P", "8901001000073", "cat_personal", "brand_house", "unit_pack", "3401", 1800, 8200, 12000, 13500, 45),
            SeedItem("prod_8", "USB-C Braided Fast Cable 1.5m", "ELC-USBC-15", "8901001000080", "cat_elec", "brand_volt", "unit_pcs", "8544", 1800, 19000, 34900, 49900, 15),
            SeedItem("prod_9", "20W GaN Compact Wall Charger", "ELC-GAN-20W", "8901001000097", "cat_elec", "brand_volt", "unit_pcs", "8504", 1800, 52000, 79900, 99900, 8),
            SeedItem("prod_10", "Fresh Farm Organic Eggs (12s)", "GRC-EGG-12", "8901001000103", "cat_grocery", "brand_metro", "unit_box", "0407", 0, 8500, 11500, 12000, 3)
        )

        items.forEach { item ->
            val variantId = "${item.id}_v1"
            catDao.upsertProduct(
                ProductEntity(
                    id = item.id,
                    name = item.name,
                    description = "Standard retail pack",
                    sku = item.sku,
                    barcode = item.barcode,
                    categoryId = item.catId,
                    brandId = item.brandId,
                    unitId = item.unitId,
                    hsnCode = item.hsn,
                    gstRateBps = item.gstBps,
                    taxCategory = if (item.gstBps == 0) TaxCategory.NIL_RATED.name else TaxCategory.TAXABLE.name,
                    taxInclusive = true,
                    costPricePaise = item.costPaise,
                    sellingPricePaise = item.sellPaise,
                    mrpPaise = item.mrpPaise,
                    reorderLevelScaled = QuantityScale.fromWhole(5),
                    imageUri = null,
                    active = true,
                    createdAt = now
                )
            )
            catDao.upsertVariant(
                ProductVariantEntity(
                    id = variantId,
                    productId = item.id,
                    label = "Default",
                    sku = item.sku,
                    barcode = item.barcode,
                    attributesJson = "{}",
                    costPricePaise = item.costPaise,
                    sellingPricePaise = item.sellPaise,
                    mrpPaise = item.mrpPaise,
                    active = true
                )
            )
            val qtyScaled = QuantityScale.fromWhole(item.stockUnits)
            catDao.upsertStockBalance(
                StockBalanceEntity(
                    productVariantId = variantId,
                    locationId = "MAIN",
                    quantityScaled = qtyScaled,
                    updatedAt = now
                )
            )
            catDao.insertStockMovement(
                StockMovementEntity(
                    id = UUID.randomUUID().toString(),
                    productVariantId = variantId,
                    movementType = MovementType.OPENING.name,
                    quantityDeltaScaled = qtyScaled,
                    unitCostPaise = item.costPaise,
                    sourceType = "SEED",
                    sourceId = null,
                    reason = "Starter catalog opening stock",
                    actorName = "System",
                    createdAt = now
                )
            )
        }

        partyDao.upsertSupplier(
            SupplierEntity(
                id = "sup_default",
                name = "Metro Wholesale Distributors",
                contactPerson = "Rajesh Kulkarni",
                mobile = "9820011223",
                email = "orders@metrowholesale.in",
                address = "APMC Market Yard, Phase 2",
                stateCode = stateCode,
                gstin = "27AABCM1234A1Z5",
                notes = "Primary FMCG distributor",
                openingBalancePaise = 0L,
                currentPayablePaise = 0L,
                createdAt = now
            )
        )
    }

    override suspend fun updateStoreSettings(
        settings: StoreSettings,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (settings.name.isBlank()) return AppResult.Failure(AppError.ValidationError("Store name cannot be empty"))
        db.withTransaction {
            dao.upsertStore(settings.toEntity())
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "SETTINGS_UPDATED",
                    entityType = "STORE",
                    entityId = settings.id,
                    metadataJson = """{"storeName":"${settings.name}","prefix":"${settings.invoicePrefix}"}""",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    override fun observeUsers(): Flow<List<User>> =
        combine(
            dao.observeUsers(),
            dao.observeRoles(),
            dao.observeUserRoles(),
            dao.observeRolePermissions()
        ) { users, roles, userRoles, rolePerms ->
            val roleMap = roles.associateBy { it.id }
            val permsFlowMap = mutableMapOf<String, MutableSet<String>>()
            // Look up permission code map asynchronously? Wait: Permission codes are deterministic from id or we fetch once
            val permIdToCode = PermissionCode.entries.associate { "perm_${it.name.lowercase()}" to it.code }
            rolePerms.forEach { rp ->
                val code = permIdToCode[rp.permissionId]
                if (code != null) {
                    permsFlowMap.getOrPut(rp.roleId) { mutableSetOf() }.add(code)
                }
            }
            val userRoleMap = userRoles.associateBy { it.userId }
            users.map { u ->
                val roleId = userRoleMap[u.id]?.roleId ?: "role_cashier"
                val roleName = roleMap[roleId]?.name ?: "Staff"
                val perms = permsFlowMap[roleId] ?: emptySet()
                User(
                    id = u.id,
                    username = u.username,
                    fullName = u.fullName,
                    roleId = roleId,
                    roleName = roleName,
                    permissions = perms,
                    active = u.status == "ACTIVE",
                    hasQuickPin = !u.pinHash.isNullOrEmpty(),
                    createdAt = u.createdAt
                )
            }
        }

    override fun observeRoles(): Flow<List<Role>> =
        combine(dao.observeRoles(), dao.observeRolePermissions()) { roles, rolePerms ->
            val permIdToCode = PermissionCode.entries.associate { "perm_${it.name.lowercase()}" to it.code }
            val byRole = rolePerms.groupBy { it.roleId }
            roles.map { r ->
                val perms = byRole[r.id].orEmpty().mapNotNull { permIdToCode[it.permissionId] }.toSet()
                Role(id = r.id, name = r.name, permissions = perms)
            }
        }

    override fun observeActivityLogs(limit: Int): Flow<List<ActivityLog>> =
        financeDao.observeActivityLogs(limit).map { list ->
            list.map {
                ActivityLog(
                    id = it.id,
                    userId = it.userId,
                    username = it.username,
                    deviceId = it.deviceId,
                    action = it.action,
                    entityType = it.entityType,
                    entityId = it.entityId,
                    metadataJson = it.metadataJson,
                    createdAt = it.createdAt
                )
            }
        }

    override fun observePairedDevices(): Flow<List<PairedDevice>> =
        dao.observeDevices().map { list ->
            list.map {
                PairedDevice(
                    id = it.id,
                    name = it.name,
                    status = it.status,
                    pairedAt = it.pairedAt,
                    lastSeenAt = it.lastSeenAt
                )
            }
        }

    override suspend fun loginWithPassword(username: String, password: String): AppResult<User> =
        authenticateInternal(username, password, isPin = false)

    override suspend fun loginWithPin(username: String, pin: String): AppResult<User> =
        authenticateInternal(username, pin, isPin = true)

    private suspend fun authenticateInternal(username: String, secret: String, isPin: Boolean): AppResult<User> {
        val now = System.currentTimeMillis()
        val normalized = username.trim().lowercase()
        val userEntity = dao.getUserByUsername(normalized)
            ?: return AppResult.Failure(AppError.Unauthorized("Invalid username or credentials"))

        if (userEntity.status != "ACTIVE") {
            return AppResult.Failure(AppError.Unauthorized("This user account is disabled"))
        }
        val lockedUntil = userEntity.lockedUntil
        if (lockedUntil != null && now < lockedUntil) {
            val secs = ((lockedUntil - now) / 1000L).coerceAtLeast(1L)
            return AppResult.Failure(AppError.Unauthorized("Account temporarily throttled. Retry in ${secs}s"))
        }

        val verified = if (isPin) {
            val ph = userEntity.pinHash
            val ps = userEntity.pinSalt
            !ph.isNullOrEmpty() && !ps.isNullOrEmpty() && PasswordHasher.verifySecret(secret, ph, ps)
        } else {
            PasswordHasher.verifySecret(secret, userEntity.passwordHash, userEntity.passwordSalt)
        }

        if (!verified) {
            val attempts = userEntity.failedAttempts + 1
            val lockUntil = if (attempts >= 5) now + 60_000L else null
            dao.updateUser(userEntity.copy(failedAttempts = attempts, lockedUntil = lockUntil))
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userEntity.id,
                    username = userEntity.username,
                    deviceId = "local",
                    action = "LOGIN_FAILED",
                    entityType = "USER",
                    entityId = userEntity.id,
                    metadataJson = """{"attempts":$attempts,"isPin":$isPin}""",
                    createdAt = now
                )
            )
            return AppResult.Failure(AppError.Unauthorized("Invalid ${if (isPin) "PIN" else "password"}"))
        }

        if (userEntity.failedAttempts > 0 || userEntity.lockedUntil != null) {
            dao.updateUser(userEntity.copy(failedAttempts = 0, lockedUntil = null))
        }
        financeDao.insertActivityLog(
            ActivityLogEntity(
                id = UUID.randomUUID().toString(),
                userId = userEntity.id,
                username = userEntity.username,
                deviceId = "local",
                action = "LOGIN_SUCCESS",
                entityType = "USER",
                entityId = userEntity.id,
                metadataJson = """{"method":"${if (isPin) "PIN" else "PASSWORD"}"}""",
                createdAt = now
            )
        )
        return AppResult.Success(buildUserModel(userEntity))
    }

    override suspend fun verifyAdminPassword(password: String): Boolean {
        val users = dao.getAllUsers()
        for (u in users) {
            if (u.status != "ACTIVE") continue
            val role = dao.getRoleForUser(u.id) ?: continue
            val perms = dao.getPermissionCodesForRole(role.id)
            if (perms.contains(PermissionCode.BACKUP_RESTORE.code) &&
                PasswordHasher.verifySecret(password, u.passwordHash, u.passwordSalt)
            ) {
                return true
            }
        }
        return false
    }

    override suspend fun createUser(
        username: String,
        fullName: String,
        roleId: String,
        password: String,
        pin: String?,
        actorUserId: String,
        actorName: String
    ): AppResult<User> {
        val cleanUsername = username.trim().lowercase()
        if (cleanUsername.isEmpty() || password.length < 4) {
            return AppResult.Failure(AppError.ValidationError("Username and password (min 4 chars) required"))
        }
        if (dao.getUserByUsername(cleanUsername) != null) {
            return AppResult.Failure(AppError.DuplicateCode("Username '$cleanUsername' already exists"))
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val pw = PasswordHasher.hashSecret(password)
        val pPin = if (!pin.isNullOrBlank()) PasswordHasher.hashSecret(pin) else null

        db.withTransaction {
            dao.insertUser(
                UserEntity(
                    id = id,
                    username = cleanUsername,
                    fullName = fullName.trim().ifBlank { cleanUsername },
                    passwordHash = pw.hashBase64,
                    passwordSalt = pw.saltBase64,
                    pinHash = pPin?.hashBase64,
                    pinSalt = pPin?.saltBase64,
                    status = "ACTIVE",
                    createdAt = now
                )
            )
            dao.insertUserRole(UserRoleEntity(id, roleId))
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "USER_CREATED",
                    entityType = "USER",
                    entityId = id,
                    metadataJson = """{"username":"$cleanUsername","roleId":"$roleId"}""",
                    createdAt = now
                )
            )
        }
        return AppResult.Success(buildUserModel(dao.getUserById(id)!!))
    }

    override suspend fun setUserActive(
        userId: String,
        active: Boolean,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        if (userId == actorUserId && !active) {
            return AppResult.Failure(AppError.ValidationError("Cannot disable the currently signed-in user"))
        }
        val u = dao.getUserById(userId) ?: return AppResult.Failure(AppError.NotFound("User not found"))
        db.withTransaction {
            dao.updateUser(u.copy(status = if (active) "ACTIVE" else "DISABLED"))
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = if (active) "USER_ENABLED" else "USER_DISABLED",
                    entityType = "USER",
                    entityId = userId,
                    metadataJson = """{"target":"${u.username}"}""",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun updateRolePermissions(
        roleId: String,
        permissions: Set<String>,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        val permEntities = dao.getAllPermissions().associateBy { it.code }
        db.withTransaction {
            dao.deleteRolePermissions(roleId)
            val rows = permissions.mapNotNull { permEntities[it]?.id }
                .map { RolePermissionEntity(roleId, it) }
            dao.insertRolePermissions(rows)
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "ROLE_PERMISSIONS_UPDATED",
                    entityType = "ROLE",
                    entityId = roleId,
                    metadataJson = """{"count":${rows.size}}""",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun resetUserCredentials(
        userId: String,
        newPassword: String?,
        newPin: String?,
        actorUserId: String,
        actorName: String
    ): AppResult<Unit> {
        val u = dao.getUserById(userId) ?: return AppResult.Failure(AppError.NotFound("User not found"))
        val pw = if (!newPassword.isNullOrBlank()) PasswordHasher.hashSecret(newPassword) else null
        val pin = if (!newPin.isNullOrBlank()) PasswordHasher.hashSecret(newPin) else null
        if (pw == null && pin == null) {
            return AppResult.Failure(AppError.ValidationError("Provide a new password or PIN"))
        }
        db.withTransaction {
            dao.updateUser(
                u.copy(
                    passwordHash = pw?.hashBase64 ?: u.passwordHash,
                    passwordSalt = pw?.saltBase64 ?: u.passwordSalt,
                    pinHash = pin?.hashBase64 ?: u.pinHash,
                    pinSalt = pin?.saltBase64 ?: u.pinSalt,
                    failedAttempts = 0,
                    lockedUntil = null
                )
            )
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "CREDENTIALS_RESET",
                    entityType = "USER",
                    entityId = userId,
                    metadataJson = """{"target":"${u.username}"}""",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun revokeDevice(deviceId: String, actorUserId: String, actorName: String): AppResult<Unit> {
        db.withTransaction {
            dao.updateDeviceStatus(deviceId, "REVOKED")
            financeDao.insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "DEVICE_REVOKED",
                    entityType = "DEVICE",
                    entityId = deviceId,
                    metadataJson = "{}",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return AppResult.Success(Unit)
    }

    private suspend fun buildUserModel(entity: UserEntity): User {
        val role = dao.getRoleForUser(entity.id)
        val perms = if (role != null) dao.getPermissionCodesForRole(role.id).toSet() else emptySet()
        return User(
            id = entity.id,
            username = entity.username,
            fullName = entity.fullName,
            roleId = role?.id ?: "role_cashier",
            roleName = role?.name ?: "Staff",
            permissions = perms,
            active = entity.status == "ACTIVE",
            hasQuickPin = !entity.pinHash.isNullOrEmpty(),
            createdAt = entity.createdAt
        )
    }
}

private fun StoreEntity.toModel(): StoreSettings = StoreSettings(
    id = id,
    name = name,
    tagline = tagline,
    gstin = gstin,
    stateCode = stateCode,
    address = address,
    phone = phone,
    email = email,
    timezone = timezone,
    invoicePrefix = invoicePrefix,
    returnPrefix = returnPrefix,
    purchasePrefix = purchasePrefix,
    invoiceFooter = invoiceFooter,
    defaultTaxInclusive = defaultTaxInclusive,
    allowNegativeStock = allowNegativeStock,
    autoRoundInvoice = autoRoundInvoice,
    defaultInvoiceFormat = InvoiceFormat.entries.firstOrNull { it.name == defaultInvoiceFormat } ?: InvoiceFormat.A4,
    varianceApprovalThresholdPaise = varianceApprovalThresholdPaise,
    logoUri = logoUri,
    isSetupComplete = isSetupComplete
)

private fun StoreSettings.toEntity(): StoreEntity = StoreEntity(
    id = id,
    name = name,
    tagline = tagline,
    gstin = gstin.trim().uppercase(),
    stateCode = stateCode,
    address = address,
    phone = phone,
    email = email,
    timezone = timezone,
    invoicePrefix = invoicePrefix.trim().uppercase().ifBlank { "INV" },
    returnPrefix = returnPrefix.trim().uppercase().ifBlank { "RET" },
    purchasePrefix = purchasePrefix.trim().uppercase().ifBlank { "PUR" },
    invoiceFooter = invoiceFooter,
    defaultTaxInclusive = defaultTaxInclusive,
    allowNegativeStock = allowNegativeStock,
    autoRoundInvoice = autoRoundInvoice,
    defaultInvoiceFormat = defaultInvoiceFormat.name,
    varianceApprovalThresholdPaise = varianceApprovalThresholdPaise,
    logoUri = logoUri,
    isSetupComplete = isSetupComplete
)
