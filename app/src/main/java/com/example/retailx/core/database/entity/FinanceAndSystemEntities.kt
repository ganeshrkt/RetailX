package com.example.retailx.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // CASH, UPI, BANK, CARD_CLEARING, OTHER
    @ColumnInfo(name = "opening_balance_paise") val openingBalancePaise: Long,
    @ColumnInfo(name = "current_balance_paise") val currentBalancePaise: Long,
    val active: Boolean
)

@Entity(
    tableName = "financial_transactions",
    foreignKeys = [
        ForeignKey(entity = WalletEntity::class, parentColumns = ["id"], childColumns = ["wallet_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["wallet_id"]), Index(value = ["created_at"])]
)
data class FinancialTransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    @ColumnInfo(name = "wallet_name") val walletName: String,
    val direction: String, // IN, OUT
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "reversal_of_id") val reversalOfId: String?,
    val description: String,
    val reference: String,
    @ColumnInfo(name = "actor_name") val actorName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "expense_categories")
data class ExpenseCategoryEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(entity = ExpenseCategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = WalletEntity::class, parentColumns = ["id"], childColumns = ["wallet_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["category_id"]), Index(value = ["wallet_id"]), Index(value = ["created_at"])]
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    @ColumnInfo(name = "wallet_name") val walletName: String,
    val payee: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val note: String,
    @ColumnInfo(name = "attachment_uri") val attachmentUri: String?,
    @ColumnInfo(name = "actor_name") val actorName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "cash_registers")
data class CashRegisterEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "user_name") val userName: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "opening_cash_paise") val openingCashPaise: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
    @ColumnInfo(name = "expected_cash_paise") val expectedCashPaise: Long,
    @ColumnInfo(name = "counted_cash_paise") val countedCashPaise: Long?,
    @ColumnInfo(name = "variance_paise") val variancePaise: Long?,
    @ColumnInfo(name = "variance_reason") val varianceReason: String?,
    @ColumnInfo(name = "approved_by") val approvedBy: String?
)

@Entity(tableName = "held_bills")
data class HeldBillEntity(
    @PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "user_name") val userName: String,
    @ColumnInfo(name = "customer_id") val customerId: String?,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "place_of_supply_state_code") val placeOfSupplyStateCode: String,
    @ColumnInfo(name = "bill_discount_paise") val billDiscountPaise: Long,
    @ColumnInfo(name = "cart_json") val cartJson: String,
    @ColumnInfo(name = "total_preview_paise") val totalPreviewPaise: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "invoice_sequences",
    indices = [Index(value = ["document_type", "financial_year"], unique = true)]
)
data class InvoiceSequenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_type") val documentType: String, // SALE, RETURN, PURCHASE, PURCHASE_RETURN
    val prefix: String,
    @ColumnInfo(name = "next_number") val nextNumber: Long,
    @ColumnInfo(name = "financial_year") val financialYear: String
)

@Entity(
    tableName = "activity_logs",
    indices = [Index(value = ["created_at"]), Index(value = ["action"])]
)
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String?,
    val username: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    val action: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "backup_history")
data class BackupHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val uri: String,
    val status: String,
    val checksum: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    val message: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "idempotency_records",
    indices = [Index(value = ["idempotency_key"], unique = true)]
)
data class IdempotencyRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "result_entity_id") val resultEntityId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
