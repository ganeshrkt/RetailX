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
interface SalesDao {
    @Query("SELECT * FROM sales ORDER BY created_at DESC")
    fun observeSales(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE created_at BETWEEN :fromMillis AND :toMillis ORDER BY created_at DESC")
    suspend fun getSalesInRange(fromMillis: Long, toMillis: Long): List<SaleEntity>

    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1")
    suspend fun getSaleById(id: String): SaleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Update
    suspend fun updateSale(sale: SaleEntity)

    @Query("SELECT * FROM sale_items")
    fun observeAllSaleItems(): Flow<List<SaleItemEntity>>

    @Query("SELECT * FROM sale_items WHERE sale_id = :saleId")
    suspend fun getItemsForSale(saleId: String): List<SaleItemEntity>

    @Query("SELECT * FROM sale_items WHERE id = :id LIMIT 1")
    suspend fun getSaleItemById(id: String): SaleItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleItems(items: List<SaleItemEntity>)

    @Update
    suspend fun updateSaleItem(item: SaleItemEntity)

    @Query("SELECT * FROM sale_payments")
    fun observeAllSalePayments(): Flow<List<SalePaymentEntity>>

    @Query("SELECT * FROM sale_payments WHERE sale_id = :saleId")
    suspend fun getPaymentsForSale(saleId: String): List<SalePaymentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSalePayments(payments: List<SalePaymentEntity>)

    @Query("SELECT * FROM held_bills ORDER BY updated_at DESC")
    fun observeHeldBills(): Flow<List<HeldBillEntity>>

    @Upsert
    suspend fun upsertHeldBill(bill: HeldBillEntity)

    @Query("DELETE FROM held_bills WHERE id = :id")
    suspend fun deleteHeldBill(id: String)

    @Query("SELECT * FROM sales_returns ORDER BY created_at DESC")
    fun observeSalesReturns(): Flow<List<SalesReturnEntity>>

    @Query("SELECT * FROM sales_returns WHERE created_at BETWEEN :fromMillis AND :toMillis")
    suspend fun getSalesReturnsInRange(fromMillis: Long, toMillis: Long): List<SalesReturnEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSalesReturn(ret: SalesReturnEntity)

    @Query("SELECT * FROM sales_return_items")
    fun observeAllSalesReturnItems(): Flow<List<SalesReturnItemEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSalesReturnItems(items: List<SalesReturnItemEntity>)

    @Query("SELECT * FROM invoice_sequences WHERE document_type = :docType AND financial_year = :fy LIMIT 1")
    suspend fun getSequence(docType: String, fy: String): InvoiceSequenceEntity?

    @Upsert
    suspend fun upsertSequence(seq: InvoiceSequenceEntity)

    @Query("SELECT * FROM idempotency_records WHERE idempotency_key = :key LIMIT 1")
    suspend fun getIdempotencyRecord(key: String): IdempotencyRecordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIdempotencyRecord(record: IdempotencyRecordEntity)
}
