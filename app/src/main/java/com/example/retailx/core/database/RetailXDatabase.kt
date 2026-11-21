package com.example.retailx.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.retailx.core.database.dao.*
import com.example.retailx.core.database.entity.*

@Database(
    entities = [
        StoreEntity::class,
        UserEntity::class,
        RoleEntity::class,
        PermissionEntity::class,
        UserRoleEntity::class,
        RolePermissionEntity::class,
        DeviceEntity::class,
        CategoryEntity::class,
        BrandEntity::class,
        UnitEntity::class,
        ProductEntity::class,
        ProductVariantEntity::class,
        StockBalanceEntity::class,
        StockMovementEntity::class,
        CustomerEntity::class,
        SupplierEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        SalePaymentEntity::class,
        SalesReturnEntity::class,
        SalesReturnItemEntity::class,
        PurchaseEntity::class,
        PurchaseItemEntity::class,
        PurchasePaymentEntity::class,
        PurchaseReturnEntity::class,
        WalletEntity::class,
        FinancialTransactionEntity::class,
        ExpenseCategoryEntity::class,
        ExpenseEntity::class,
        CashRegisterEntity::class,
        HeldBillEntity::class,
        InvoiceSequenceEntity::class,
        ActivityLogEntity::class,
        AppSettingEntity::class,
        BackupHistoryEntity::class,
        IdempotencyRecordEntity::class
    ],
    version = RetailXDatabase.SCHEMA_VERSION,
    exportSchema = true
)
abstract class RetailXDatabase : RoomDatabase() {
    abstract fun storeAndAuthDao(): StoreAndAuthDao
    abstract fun catalogDao(): CatalogDao
    abstract fun salesDao(): SalesDao
    abstract fun purchaseAndPartyDao(): PurchaseAndPartyDao
    abstract fun financeAndAuditDao(): FinanceAndAuditDao

    companion object {
        const val DATABASE_NAME = "retailx_pos.db"
        const val SCHEMA_VERSION = 1

        // Explicit migration registry; never use destructive migration in production.
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context, inMemory: Boolean = false): RetailXDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, RetailXDatabase::class.java)
                    .allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context.applicationContext, RetailXDatabase::class.java, DATABASE_NAME)
            }
            return builder
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys=ON;")
                    }
                })
                .build()
        }
    }
}
