package com.example.retailx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.model.*
import com.example.retailx.data.local.*
import com.example.retailx.domain.repository.*
import com.example.retailx.lan.client.LanApiClient
import com.example.retailx.lan.host.LanHostServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SalesAndInventoryRobolectricTest {

    private suspend fun bootstrapStore(db: RetailXDatabase): User {
        val repo = LocalStoreAndAuthRepository(db)
        val res = repo.setupInitialStore(
            storeName = "Test Mart",
            gstin = "27AAAAA0000A1Z5",
            stateCode = "27",
            address = "Pune",
            phone = "9999999999",
            email = "test@mart.in",
            invoicePrefix = "TM",
            adminUsername = "admin",
            adminFullName = "Admin User",
            adminPassword = "secret",
            adminPin = "4321",
            loadStarterCatalog = true
        )
        return (res as AppResult.Success).data
    }

    @Test
    fun `atomic checkout deduplicates idempotency key and enforces stock`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RetailXDatabase.build(context, inMemory = true)
        try {
            val admin = bootstrapStore(db)
            val catalogRepo = LocalCatalogRepository(db)
            val salesRepo = LocalSalesRepository(db)
            val financeRepo = LocalFinanceAndReportsRepository(db)

            val prod = catalogRepo.upsertProduct(
                id = null,
                name = "Basmati Rice 5kg",
                description = "Premium",
                sku = "RICE-5K",
                barcode = "8901234567890",
                categoryId = null,
                brandId = null,
                unitId = "unit_pcs",
                hsnCode = "1006",
                gstRateBps = 500,
                taxCategory = TaxCategory.TAXABLE,
                taxInclusive = true,
                costPricePaise = 40000L,
                sellingPricePaise = 52500L,
                mrpPaise = 55000L,
                reorderLevelScaled = 2000L,
                openingStockScaled = QuantityScale.fromWhole(10),
                imageUri = null,
                active = true,
                actorUserId = admin.id,
                actorName = admin.fullName
            ).let { (it as AppResult.Success).data }

            val cashWallet = db.financeAndAuditDao().getAllWallets().first { it.type == WalletType.CASH.name }
            val startCash = cashWallet.currentBalancePaise

            val cmd = CompleteSaleCommand(
                idempotencyKey = "idem-sale-001",
                customerId = null,
                placeOfSupplyStateCode = "27",
                billDiscountPaise = 0L,
                items = listOf(
                    CartItemInput(
                        lineId = "line1",
                        productId = prod.id,
                        productVariantId = prod.defaultVariantId,
                        name = prod.name,
                        sku = prod.sku,
                        hsnCode = prod.hsnCode,
                        unitShortName = prod.unitShortName,
                        qtyScaled = QuantityScale.fromWhole(3),
                        unitPricePaise = 52500L,
                        costPricePaise = 40000L,
                        lineDiscountPaise = 0L,
                        gstRateBps = 500,
                        taxCategory = TaxCategory.TAXABLE,
                        taxInclusive = true,
                        maxAvailableScaled = prod.stockScaled
                    )
                ),
                payments = listOf(
                    CompleteSalePaymentInput(
                        walletId = cashWallet.id,
                        method = PaymentMethod.CASH,
                        amountPaise = 157500L,
                        reference = ""
                    )
                ),
                actorUserId = admin.id,
                actorName = admin.fullName
            )

            val sale1 = (salesRepo.completeSale(cmd) as AppResult.Success).data
            val sale2 = (salesRepo.completeSale(cmd) as AppResult.Success).data
            assertEquals(sale1.id, sale2.id)
            assertEquals(sale1.invoiceNo, sale2.invoiceNo)

            // Stock must have dropped by 3 only ONCE -> 7 remaining
            val afterStock = db.catalogDao().getStockBalance(prod.defaultVariantId)?.quantityScaled
            assertEquals(QuantityScale.fromWhole(7), afterStock)

            // Insufficient stock must fail cleanly
            val failRes = salesRepo.completeSale(
                cmd.copy(
                    idempotencyKey = "idem-sale-002",
                    items = cmd.items.map { it.copy(qtyScaled = QuantityScale.fromWhole(99)) },
                    payments = listOf(cmd.payments.first().copy(amountPaise = 99 * 52500L))
                )
            )
            assertTrue(failRes is AppResult.Failure && failRes.error is AppError.InsufficientStock)

            // Partial return of 1 unit with restock
            val saleWithItems = salesRepo.getSaleById(sale1.id)!!
            val retRes = salesRepo.processSalesReturn(
                ProcessSalesReturnCommand(
                    idempotencyKey = "idem-ret-001",
                    saleId = sale1.id,
                    reason = "Damaged pack",
                    refundMethod = PaymentMethod.CASH,
                    refundWalletId = cashWallet.id,
                    lines = listOf(
                        ReturnLineInput(
                            saleItemId = saleWithItems.items.first().id,
                            qtyScaled = QuantityScale.fromWhole(1),
                            restock = true
                        )
                    ),
                    actorUserId = admin.id,
                    actorName = admin.fullName
                )
            )
            assertTrue(retRes is AppResult.Success)
            assertEquals(QuantityScale.fromWhole(8), db.catalogDao().getStockBalance(prod.defaultVariantId)?.quantityScaled)
            assertEquals(SaleStatus.PARTIALLY_RETURNED, salesRepo.getSaleById(sale1.id)?.status)
            val endCash = db.financeAndAuditDao().getWalletById(cashWallet.id)!!.currentBalancePaise
            assertEquals(startCash + 105000L, endCash)
            assertNotNull(financeRepo.getDashboardSummary())
        } finally {
            db.close()
        }
    }

    @Test
    fun `purchase receiving updates weighted average cost and lan host pairs and bills`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RetailXDatabase.build(context, inMemory = true)
        val storeAuthRepo = LocalStoreAndAuthRepository(db)
        val catalogRepo = LocalCatalogRepository(db)
        val salesRepo = LocalSalesRepository(db)
        val partyRepo = LocalPartiesAndPurchasesRepository(db)
        val financeRepo = LocalFinanceAndReportsRepository(db)

        val host = LanHostServer(
            dbProvider = { db },
            storeAuthRepoProvider = { storeAuthRepo },
            catalogRepoProvider = { catalogRepo },
            salesRepoProvider = { salesRepo },
            partyRepoProvider = { partyRepo },
            financeRepoProvider = { financeRepo }
        )

        try {
            val admin = bootstrapStore(db)
            val prod = (catalogRepo.upsertProduct(
                id = null,
                name = "Sugar 1kg",
                description = "",
                sku = "SUG-1K",
                barcode = "890999900001",
                categoryId = null,
                brandId = null,
                unitId = "unit_pcs",
                hsnCode = "1701",
                gstRateBps = 500,
                taxCategory = TaxCategory.TAXABLE,
                taxInclusive = true,
                costPricePaise = 4000L,
                sellingPricePaise = 4800L,
                mrpPaise = 5000L,
                reorderLevelScaled = 2000L,
                openingStockScaled = QuantityScale.fromWhole(10),
                imageUri = null,
                active = true,
                actorUserId = admin.id,
                actorName = admin.fullName
            ) as AppResult.Success).data

            val supplier = (partyRepo.upsertSupplier(
                Supplier(
                    id = "",
                    name = "ABC Distributors",
                    contactPerson = "Ramesh",
                    mobile = "9876500000",
                    email = "",
                    address = "Pune",
                    stateCode = "27",
                    gstin = "",
                    notes = "",
                    openingBalancePaise = 0L,
                    currentPayablePaise = 0L,
                    createdAt = 0L
                ),
                admin.id,
                admin.fullName
            ) as AppResult.Success).data

            // Buy 10 more units @ Rs 60.00 ex-tax -> avg cost = (10*4000 + 10*6000)/20 = 5000 paise
            val purchaseRes = partyRepo.createPurchase(
                CreatePurchaseCommand(
                    supplierId = supplier.id,
                    supplierInvoiceNo = "SUP-101",
                    supplierInvoiceDate = System.currentTimeMillis(),
                    isInterState = false,
                    additionalChargesPaise = 0L,
                    roundOffPaise = 0L,
                    receiveImmediately = true,
                    lines = listOf(
                        PurchaseLineDraft(
                            productVariantId = prod.defaultVariantId,
                            description = prod.name,
                            sku = prod.sku,
                            hsnCode = prod.hsnCode,
                            unitShortName = prod.unitShortName,
                            qtyScaled = QuantityScale.fromWhole(10),
                            ratePaise = 6000L,
                            discountPaise = 0L,
                            gstRateBps = 0
                        )
                    ),
                    actorUserId = admin.id,
                    actorName = admin.fullName
                )
            )
            assertTrue(purchaseRes is AppResult.Success)
            val updatedProd = db.catalogDao().getProductById(prod.id)!!
            assertEquals(5000L, updatedProd.costPricePaise)
            assertEquals(QuantityScale.fromWhole(20), db.catalogDao().getStockBalance(prod.defaultVariantId)?.quantityScaled)

            // Test LAN Host Server & LanApiClient loopback pairing + login + remote sale
            host.start(0)
            val port = host.status.value.port
            val baseUrl = "http://127.0.0.1:$port"
            val client = LanApiClient()

            val health = client.checkHealth(baseUrl)
            assertTrue(health is AppResult.Success)

            val challenge = host.generatePairingChallenge()
            val paired = (client.completePairing(baseUrl, challenge.code, "Counter 2") as AppResult.Success).data

            val lanUser = (client.loginOnHost(baseUrl, paired.deviceId, paired.deviceToken, "admin", "secret", false) as AppResult.Success).data
            assertEquals("admin", lanUser.username)

            val remoteProducts = (client.fetchProductsFromHost(baseUrl, paired.deviceId, paired.deviceToken) as AppResult.Success).data
            assertTrue(remoteProducts.any { it.sku == "SUG-1K" })

            val cashWallet = db.financeAndAuditDao().getAllWallets().first { it.type == WalletType.CASH.name }
            val remoteSale = client.completeSaleOnHost(
                baseUrl = baseUrl,
                deviceId = paired.deviceId,
                deviceToken = paired.deviceToken,
                command = CompleteSaleCommand(
                    idempotencyKey = "lan-sale-1",
                    customerId = null,
                    placeOfSupplyStateCode = "27",
                    billDiscountPaise = 0L,
                    items = listOf(
                        CartItemInput(
                            lineId = "l1",
                            productId = prod.id,
                            productVariantId = prod.defaultVariantId,
                            name = prod.name,
                            sku = prod.sku,
                            hsnCode = prod.hsnCode,
                            unitShortName = prod.unitShortName,
                            qtyScaled = QuantityScale.fromWhole(2),
                            unitPricePaise = 4800L,
                            costPricePaise = 5000L,
                            lineDiscountPaise = 0L,
                            gstRateBps = 500,
                            taxCategory = TaxCategory.TAXABLE,
                            taxInclusive = true,
                            maxAvailableScaled = QuantityScale.fromWhole(20)
                        )
                    ),
                    payments = listOf(CompleteSalePaymentInput(cashWallet.id, PaymentMethod.CASH, 9600L, "")),
                    actorUserId = lanUser.id,
                    actorName = lanUser.fullName
                )
            )
            assertTrue(remoteSale is AppResult.Success)
            assertEquals(QuantityScale.fromWhole(18), db.catalogDao().getStockBalance(prod.defaultVariantId)?.quantityScaled)
        } finally {
            host.stop()
            db.close()
        }
    }
}
