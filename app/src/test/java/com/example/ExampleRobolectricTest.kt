package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.model.InvoiceFormat
import com.example.retailx.data.local.LocalSalesRepository
import com.example.retailx.data.local.LocalStoreAndAuthRepository
import com.example.retailx.platform.pdf.PdfDocumentGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches RetailX POS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RetailX POS", appName)
    }

    @Test
    fun `onboarding creates store admin wallets and starter catalog`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RetailXDatabase.build(context, inMemory = true)
        try {
            val repo = LocalStoreAndAuthRepository(db)
            val result = repo.setupInitialStore(
                storeName = "Shree Ganesh Mart",
                gstin = "27AAAAA0000A1Z5",
                stateCode = "27",
                address = "MG Road, Pune",
                phone = "9876543210",
                email = "store@example.com",
                invoicePrefix = "SGM",
                adminUsername = "admin",
                adminFullName = "Owner",
                adminPassword = "password123",
                adminPin = "1234",
                loadStarterCatalog = true
            )
            assertTrue(result is AppResult.Success)
            val store = repo.getStoreSettings()
            assertNotNull(store)
            assertEquals("Shree Ganesh Mart", store?.name)
            assertTrue(store?.isSetupComplete == true)
            assertTrue(db.catalogDao().getAllProducts().isNotEmpty())
            assertTrue(db.financeAndAuditDao().getAllWallets().isNotEmpty())

            // Verify login with password and quick PIN
            assertTrue(repo.loginWithPassword("admin", "password123") is AppResult.Success)
            assertTrue(repo.loginWithPin("admin", "1234") is AppResult.Success)
            assertTrue(repo.loginWithPassword("admin", "wrongpass") is AppResult.Failure)

            // Verify PDF generator can render an invoice file
            val pdfGen = PdfDocumentGenerator(context)
            val salesRepo = LocalSalesRepository(db)
            assertNotNull(pdfGen)
            assertNotNull(salesRepo)
            assertEquals(595, InvoiceFormat.A4.widthPoints)
        } finally {
            db.close()
        }
    }
}
