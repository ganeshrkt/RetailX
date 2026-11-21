package com.example.retailx.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.retailx.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceAndAuditDao {
    @Query("SELECT * FROM wallets ORDER BY name ASC")
    fun observeWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY name ASC")
    suspend fun getAllWallets(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id LIMIT 1")
    suspend fun getWalletById(id: String): WalletEntity?

    @Query("SELECT * FROM wallets WHERE type = :type AND active = 1 LIMIT 1")
    suspend fun getFirstWalletByType(type: String): WalletEntity?

    @Upsert
    suspend fun upsertWallet(wallet: WalletEntity)

    @Query("SELECT * FROM financial_transactions ORDER BY created_at DESC LIMIT :limit")
    fun observeFinancialTransactions(limit: Int): Flow<List<FinancialTransactionEntity>>

    @Query("SELECT * FROM financial_transactions WHERE created_at BETWEEN :fromMillis AND :toMillis")
    suspend fun getTransactionsInRange(fromMillis: Long, toMillis: Long): List<FinancialTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFinancialTransaction(tx: FinancialTransactionEntity)

    @Query("SELECT * FROM expense_categories ORDER BY name ASC")
    fun observeExpenseCategories(): Flow<List<ExpenseCategoryEntity>>

    @Query("SELECT * FROM expense_categories WHERE id = :id LIMIT 1")
    suspend fun getExpenseCategoryById(id: String): ExpenseCategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpenseCategories(categories: List<ExpenseCategoryEntity>)

    @Query("SELECT * FROM expenses ORDER BY created_at DESC LIMIT :limit")
    fun observeExpenses(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE created_at BETWEEN :fromMillis AND :toMillis")
    suspend fun getExpensesInRange(fromMillis: Long, toMillis: Long): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExpense(expense: ExpenseEntity)

    @Query("SELECT * FROM cash_registers ORDER BY opened_at DESC")
    fun observeCashRegisters(): Flow<List<CashRegisterEntity>>

    @Query("SELECT * FROM cash_registers WHERE closed_at IS NULL ORDER BY opened_at DESC LIMIT 1")
    fun observeOpenCashRegister(): Flow<CashRegisterEntity?>

    @Query("SELECT * FROM cash_registers WHERE closed_at IS NULL ORDER BY opened_at DESC LIMIT 1")
    suspend fun getOpenCashRegister(): CashRegisterEntity?

    @Query("SELECT * FROM cash_registers WHERE id = :id LIMIT 1")
    suspend fun getCashRegisterById(id: String): CashRegisterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCashRegister(shift: CashRegisterEntity)

    @Update
    suspend fun updateCashRegister(shift: CashRegisterEntity)

    @Query("SELECT * FROM activity_logs ORDER BY created_at DESC LIMIT :limit")
    fun observeActivityLogs(limit: Int): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActivityLog(log: ActivityLogEntity)

    @Query("SELECT * FROM backup_history ORDER BY created_at DESC")
    fun observeBackupHistory(): Flow<List<BackupHistoryEntity>>

    @Upsert
    suspend fun insertBackupHistory(record: BackupHistoryEntity)
}
