package com.example.retailx.app

import android.content.Context
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.datastore.AppPreferencesStore
import com.example.retailx.data.local.*
import com.example.retailx.lan.client.LanApiClient
import com.example.retailx.lan.host.LanHostServer
import com.example.retailx.platform.backup.BackupManager
import com.example.retailx.platform.pdf.PdfDocumentGenerator

class AppContainer(private val appContext: Context) {

    @Volatile
    private var databaseInstance: RetailXDatabase = RetailXDatabase.build(appContext)

    val database: RetailXDatabase
        get() = databaseInstance

    val preferencesStore = AppPreferencesStore(appContext)
    val sessionManager = SessionManager()
    val pdfGenerator = PdfDocumentGenerator(appContext)
    val lanApiClient = LanApiClient()

    val storeAndAuthRepository: LocalStoreAndAuthRepository
        get() = LocalStoreAndAuthRepository(databaseInstance)

    val catalogRepository: LocalCatalogRepository
        get() = LocalCatalogRepository(databaseInstance)

    val salesRepository: LocalSalesRepository
        get() = LocalSalesRepository(databaseInstance)

    val partiesAndPurchasesRepository: LocalPartiesAndPurchasesRepository
        get() = LocalPartiesAndPurchasesRepository(databaseInstance)

    val financeAndReportsRepository: LocalFinanceAndReportsRepository
        get() = LocalFinanceAndReportsRepository(databaseInstance)

    val lanHostServer: LanHostServer = LanHostServer(
        dbProvider = { databaseInstance },
        storeAuthRepoProvider = { storeAndAuthRepository },
        catalogRepoProvider = { catalogRepository },
        salesRepoProvider = { salesRepository },
        partyRepoProvider = { partiesAndPurchasesRepository },
        financeRepoProvider = { financeAndReportsRepository }
    )

    val backupManager: BackupManager = BackupManager(
        context = appContext,
        dbProvider = { databaseInstance },
        onDatabaseRestored = {
            databaseInstance = RetailXDatabase.build(appContext)
        }
    )
}
