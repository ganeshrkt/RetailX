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
interface PurchaseAndPartyDao {
    @Query("SELECT * FROM customers ORDER BY is_walk_in DESC, name ASC")
    fun observeCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: String): CustomerEntity?

    @Upsert
    suspend fun upsertCustomer(customer: CustomerEntity)

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun observeSuppliers(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    suspend fun getAllSuppliers(): List<SupplierEntity>

    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    suspend fun getSupplierById(id: String): SupplierEntity?

    @Upsert
    suspend fun upsertSupplier(supplier: SupplierEntity)

    @Query("SELECT * FROM purchases ORDER BY created_at DESC")
    fun observePurchases(): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE id = :id LIMIT 1")
    suspend fun getPurchaseById(id: String): PurchaseEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPurchase(purchase: PurchaseEntity)

    @Update
    suspend fun updatePurchase(purchase: PurchaseEntity)

    @Query("SELECT * FROM purchase_items")
    fun observeAllPurchaseItems(): Flow<List<PurchaseItemEntity>>

    @Query("SELECT * FROM purchase_items WHERE purchase_id = :purchaseId")
    suspend fun getItemsForPurchase(purchaseId: String): List<PurchaseItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPurchaseItems(items: List<PurchaseItemEntity>)

    @Query("SELECT * FROM purchase_payments")
    fun observeAllPurchasePayments(): Flow<List<PurchasePaymentEntity>>

    @Query("SELECT * FROM purchase_payments WHERE purchase_id = :purchaseId")
    suspend fun getPaymentsForPurchase(purchaseId: String): List<PurchasePaymentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPurchasePayment(payment: PurchasePaymentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPurchaseReturn(ret: PurchaseReturnEntity)
}
