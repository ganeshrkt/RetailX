package com.example.retailx.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [Index(value = ["mobile"])]
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mobile: String,
    val email: String,
    val address: String,
    @ColumnInfo(name = "state_code") val stateCode: String,
    val gstin: String,
    @ColumnInfo(name = "customer_type") val customerType: String,
    @ColumnInfo(name = "opening_balance_paise") val openingBalancePaise: Long,
    @ColumnInfo(name = "current_balance_paise") val currentBalancePaise: Long,
    @ColumnInfo(name = "credit_limit_paise") val creditLimitPaise: Long,
    @ColumnInfo(name = "default_discount_bps") val defaultDiscountBps: Int,
    @ColumnInfo(name = "is_walk_in") val isWalkIn: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "suppliers")
data class SupplierEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "contact_person") val contactPerson: String,
    val mobile: String,
    val email: String,
    val address: String,
    @ColumnInfo(name = "state_code") val stateCode: String,
    val gstin: String,
    val notes: String,
    @ColumnInfo(name = "opening_balance_paise") val openingBalancePaise: Long,
    @ColumnInfo(name = "current_payable_paise") val currentPayablePaise: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["invoice_no"], unique = true),
        Index(value = ["customer_id"]),
        Index(value = ["cashier_id"]),
        Index(value = ["created_at"])
    ]
)
data class SaleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_no") val invoiceNo: String,
    @ColumnInfo(name = "customer_id") val customerId: String?,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_gstin") val customerGstin: String,
    @ColumnInfo(name = "place_of_supply_state_code") val placeOfSupplyStateCode: String,
    @ColumnInfo(name = "is_inter_state") val isInterState: Boolean,
    @ColumnInfo(name = "cashier_id") val cashierId: String,
    @ColumnInfo(name = "cashier_name") val cashierName: String,
    val status: String,
    @ColumnInfo(name = "subtotal_paise") val subtotalPaise: Long,
    @ColumnInfo(name = "discount_paise") val discountPaise: Long,
    @ColumnInfo(name = "taxable_paise") val taxablePaise: Long,
    @ColumnInfo(name = "cgst_paise") val cgstPaise: Long,
    @ColumnInfo(name = "sgst_paise") val sgstPaise: Long,
    @ColumnInfo(name = "igst_paise") val igstPaise: Long,
    @ColumnInfo(name = "tax_paise") val taxPaise: Long,
    @ColumnInfo(name = "round_off_paise") val roundOffPaise: Long,
    @ColumnInfo(name = "total_paise") val totalPaise: Long,
    @ColumnInfo(name = "paid_paise") val paidPaise: Long,
    @ColumnInfo(name = "balance_due_paise") val balanceDuePaise: Long,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["sale_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["sale_id"]), Index(value = ["product_variant_id"])]
)
data class SaleItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "product_variant_id") val productVariantId: String,
    @ColumnInfo(name = "description_snapshot") val descriptionSnapshot: String,
    @ColumnInfo(name = "sku_snapshot") val skuSnapshot: String,
    @ColumnInfo(name = "hsn_snapshot") val hsnSnapshot: String,
    @ColumnInfo(name = "unit_snapshot") val unitSnapshot: String,
    @ColumnInfo(name = "qty_scaled") val qtyScaled: Long,
    @ColumnInfo(name = "returned_qty_scaled") val returnedQtyScaled: Long,
    @ColumnInfo(name = "rate_paise") val ratePaise: Long,
    @ColumnInfo(name = "cost_price_snapshot_paise") val costPriceSnapshotPaise: Long,
    @ColumnInfo(name = "discount_paise") val discountPaise: Long,
    @ColumnInfo(name = "taxable_paise") val taxablePaise: Long,
    @ColumnInfo(name = "gst_rate_bps") val gstRateBps: Int,
    @ColumnInfo(name = "tax_category") val taxCategory: String,
    @ColumnInfo(name = "cgst_paise") val cgstPaise: Long,
    @ColumnInfo(name = "sgst_paise") val sgstPaise: Long,
    @ColumnInfo(name = "igst_paise") val igstPaise: Long,
    @ColumnInfo(name = "tax_paise") val taxPaise: Long,
    @ColumnInfo(name = "total_paise") val totalPaise: Long
)

@Entity(
    tableName = "sale_payments",
    foreignKeys = [
        ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["sale_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["sale_id"]), Index(value = ["wallet_id"])]
)
data class SalePaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "wallet_id") val walletId: String?,
    @ColumnInfo(name = "wallet_name") val walletName: String,
    val method: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val reference: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "sales_returns",
    foreignKeys = [
        ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["sale_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["return_no"], unique = true), Index(value = ["sale_id"])]
)
data class SalesReturnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "return_no") val returnNo: String,
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "invoice_no") val invoiceNo: String,
    val reason: String,
    @ColumnInfo(name = "refund_method") val refundMethod: String,
    @ColumnInfo(name = "total_refund_paise") val totalRefundPaise: Long,
    @ColumnInfo(name = "actor_name") val actorName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "sales_return_items",
    foreignKeys = [
        ForeignKey(entity = SalesReturnEntity::class, parentColumns = ["id"], childColumns = ["return_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["return_id"]), Index(value = ["sale_item_id"])]
)
data class SalesReturnItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "return_id") val returnId: String,
    @ColumnInfo(name = "sale_item_id") val saleItemId: String,
    @ColumnInfo(name = "product_variant_id") val productVariantId: String,
    val description: String,
    @ColumnInfo(name = "qty_scaled") val qtyScaled: Long,
    @ColumnInfo(name = "refund_paise") val refundPaise: Long,
    val restock: Boolean
)

@Entity(
    tableName = "purchases",
    foreignKeys = [
        ForeignKey(entity = SupplierEntity::class, parentColumns = ["id"], childColumns = ["supplier_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["purchase_no"], unique = true), Index(value = ["supplier_id"])]
)
data class PurchaseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "purchase_no") val purchaseNo: String,
    @ColumnInfo(name = "supplier_id") val supplierId: String,
    @ColumnInfo(name = "supplier_name") val supplierName: String,
    @ColumnInfo(name = "supplier_invoice_no") val supplierInvoiceNo: String,
    @ColumnInfo(name = "supplier_invoice_date") val supplierInvoiceDate: Long,
    @ColumnInfo(name = "is_inter_state") val isInterState: Boolean,
    val status: String,
    @ColumnInfo(name = "subtotal_paise") val subtotalPaise: Long,
    @ColumnInfo(name = "discount_paise") val discountPaise: Long,
    @ColumnInfo(name = "taxable_paise") val taxablePaise: Long,
    @ColumnInfo(name = "cgst_paise") val cgstPaise: Long,
    @ColumnInfo(name = "sgst_paise") val sgstPaise: Long,
    @ColumnInfo(name = "igst_paise") val igstPaise: Long,
    @ColumnInfo(name = "tax_paise") val taxPaise: Long,
    @ColumnInfo(name = "additional_charges_paise") val additionalChargesPaise: Long,
    @ColumnInfo(name = "round_off_paise") val roundOffPaise: Long,
    @ColumnInfo(name = "total_paise") val totalPaise: Long,
    @ColumnInfo(name = "paid_paise") val paidPaise: Long,
    @ColumnInfo(name = "balance_payable_paise") val balancePayablePaise: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long?
)

@Entity(
    tableName = "purchase_items",
    foreignKeys = [
        ForeignKey(entity = PurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchase_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["purchase_id"]), Index(value = ["product_variant_id"])]
)
data class PurchaseItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "purchase_id") val purchaseId: String,
    @ColumnInfo(name = "product_variant_id") val productVariantId: String,
    @ColumnInfo(name = "description_snapshot") val descriptionSnapshot: String,
    @ColumnInfo(name = "sku_snapshot") val skuSnapshot: String,
    @ColumnInfo(name = "hsn_snapshot") val hsnSnapshot: String,
    @ColumnInfo(name = "unit_snapshot") val unitSnapshot: String,
    @ColumnInfo(name = "qty_scaled") val qtyScaled: Long,
    @ColumnInfo(name = "returned_qty_scaled") val returnedQtyScaled: Long,
    @ColumnInfo(name = "rate_paise") val ratePaise: Long,
    @ColumnInfo(name = "discount_paise") val discountPaise: Long,
    @ColumnInfo(name = "taxable_paise") val taxablePaise: Long,
    @ColumnInfo(name = "gst_rate_bps") val gstRateBps: Int,
    @ColumnInfo(name = "cgst_paise") val cgstPaise: Long,
    @ColumnInfo(name = "sgst_paise") val sgstPaise: Long,
    @ColumnInfo(name = "igst_paise") val igstPaise: Long,
    @ColumnInfo(name = "tax_paise") val taxPaise: Long,
    @ColumnInfo(name = "total_paise") val totalPaise: Long
)

@Entity(
    tableName = "purchase_payments",
    foreignKeys = [
        ForeignKey(entity = PurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchase_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["purchase_id"])]
)
data class PurchasePaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "purchase_id") val purchaseId: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    @ColumnInfo(name = "wallet_name") val walletName: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val reference: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "purchase_returns",
    foreignKeys = [
        ForeignKey(entity = PurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchase_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["return_no"], unique = true), Index(value = ["purchase_id"])]
)
data class PurchaseReturnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "return_no") val returnNo: String,
    @ColumnInfo(name = "purchase_id") val purchaseId: String,
    val reason: String,
    @ColumnInfo(name = "total_paise") val totalPaise: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
