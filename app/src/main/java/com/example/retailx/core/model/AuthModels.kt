package com.example.retailx.core.model

enum class PermissionCode(val code: String, val label: String, val group: String) {
    POS_BILLING("pos.billing", "POS Billing & Held Bills", "POS"),
    POS_DISCOUNT("pos.discount", "Apply Discounts", "POS"),
    POS_PRICE_OVERRIDE("pos.price_override", "Override Item Prices", "POS"),
    SALES_VIEW("sales.view", "View Sales Register", "Sales"),
    SALES_RETURN("sales.return", "Process Sales Returns", "Sales"),
    SALES_VOID("sales.void", "Void Completed Sales", "Sales"),
    CATALOG_MANAGE("catalog.manage", "Manage Products & Categories", "Inventory"),
    INVENTORY_ADJUST("inventory.adjust", "Adjust & Reconcile Stock", "Inventory"),
    PURCHASE_MANAGE("purchase.manage", "Purchases & Receiving", "Purchasing"),
    PARTIES_MANAGE("parties.manage", "Manage Customers & Suppliers", "Parties"),
    ACCOUNTS_MANAGE("accounts.manage", "Wallets & Expenses", "Finance"),
    CASH_REGISTER_MANAGE("register.manage", "Open & Close Cash Register", "Finance"),
    CASH_REGISTER_APPROVE("register.approve", "Approve Cash Register Variance", "Finance"),
    REPORTS_VIEW("reports.view", "View & Export Business Reports", "Reports"),
    STAFF_MANAGE("staff.manage", "Manage Staff & Roles", "Admin"),
    SETTINGS_MANAGE("settings.manage", "Store Settings & Invoice Rules", "Admin"),
    BACKUP_RESTORE("backup.restore", "Backup & Restore Database", "Admin"),
    LAN_MANAGE("lan.manage", "Manage LAN Host & Paired Devices", "Admin");

    companion object {
        val allCodes: Set<String> = entries.map { it.code }.toSet()
    }
}

data class Role(
    val id: String,
    val name: String,
    val permissions: Set<String>
)

data class User(
    val id: String,
    val username: String,
    val fullName: String,
    val roleId: String,
    val roleName: String,
    val permissions: Set<String>,
    val active: Boolean,
    val hasQuickPin: Boolean,
    val createdAt: Long
)

data class AuthenticatedSession(
    val user: User,
    val loginAt: Long,
    val lastActiveAt: Long,
    val viaLan: Boolean = false
) {
    fun hasPermission(permission: PermissionCode): Boolean =
        user.permissions.contains(permission.code)
}

data class PairedDevice(
    val id: String,
    val name: String,
    val status: String,
    val pairedAt: Long,
    val lastSeenAt: Long?
)

data class ActivityLog(
    val id: String,
    val userId: String?,
    val username: String,
    val deviceId: String?,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val metadataJson: String,
    val createdAt: Long
)
