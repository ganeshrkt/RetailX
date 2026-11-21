package com.example.retailx.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tagline: String,
    val gstin: String,
    @ColumnInfo(name = "state_code") val stateCode: String,
    val address: String,
    val phone: String,
    val email: String,
    val timezone: String,
    @ColumnInfo(name = "invoice_prefix") val invoicePrefix: String,
    @ColumnInfo(name = "return_prefix") val returnPrefix: String,
    @ColumnInfo(name = "purchase_prefix") val purchasePrefix: String,
    @ColumnInfo(name = "invoice_footer") val invoiceFooter: String,
    @ColumnInfo(name = "default_tax_inclusive") val defaultTaxInclusive: Boolean,
    @ColumnInfo(name = "allow_negative_stock") val allowNegativeStock: Boolean,
    @ColumnInfo(name = "auto_round_invoice") val autoRoundInvoice: Boolean,
    @ColumnInfo(name = "default_invoice_format") val defaultInvoiceFormat: String,
    @ColumnInfo(name = "variance_approval_threshold_paise") val varianceApprovalThresholdPaise: Long,
    @ColumnInfo(name = "logo_uri") val logoUri: String?,
    @ColumnInfo(name = "is_setup_complete") val isSetupComplete: Boolean
)

@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)]
)
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "password_hash") val passwordHash: String,
    @ColumnInfo(name = "password_salt") val passwordSalt: String,
    @ColumnInfo(name = "pin_hash") val pinHash: String?,
    @ColumnInfo(name = "pin_salt") val pinSalt: String?,
    val status: String, // ACTIVE, DISABLED
    @ColumnInfo(name = "failed_attempts") val failedAttempts: Int = 0,
    @ColumnInfo(name = "locked_until") val lockedUntil: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "roles",
    indices = [Index(value = ["name"], unique = true)]
)
data class RoleEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "permissions",
    indices = [Index(value = ["code"], unique = true)]
)
data class PermissionEntity(
    @PrimaryKey val id: String,
    val code: String
)

@Entity(
    tableName = "user_roles",
    primaryKeys = ["user_id", "role_id"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RoleEntity::class, parentColumns = ["id"], childColumns = ["role_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("user_id"), Index("role_id")]
)
data class UserRoleEntity(
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "role_id") val roleId: String
)

@Entity(
    tableName = "role_permissions",
    primaryKeys = ["role_id", "permission_id"],
    foreignKeys = [
        ForeignKey(entity = RoleEntity::class, parentColumns = ["id"], childColumns = ["role_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PermissionEntity::class, parentColumns = ["id"], childColumns = ["permission_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("role_id"), Index("permission_id")]
)
data class RolePermissionEntity(
    @ColumnInfo(name = "role_id") val roleId: String,
    @ColumnInfo(name = "permission_id") val permissionId: String
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "device_key_hash") val deviceKeyHash: String,
    val status: String, // ACTIVE, REVOKED
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?
)
