# RetailX Android --- Android Studio Architecture

**Architecture baseline:** Kotlin, Jetpack Compose, Room/SQLite, MVVM +
Clean Architecture, LAN host/client.

## 1. Architectural decisions

1.  Use a modular Android Gradle project rather than placing all code in
    `app`.
2.  Keep UI, domain rules and data access separate.
3.  Make domain use cases independent of whether data is local or
    accessed through LAN.
4.  Use one authoritative database per store host.
5.  Never synchronize SQLite database files between clients.
6.  Treat sale, return, purchase receipt and financial posting as atomic
    business operations.
7.  Keep PDF generation, backup and network operations off the main
    thread.

## 2. Runtime modes

### Standalone mode

``` text
Compose UI -> ViewModel -> Use Case -> Local Repository -> Room -> SQLite
```

A tablet uses its own local database and can operate without network
access.

### LAN host mode

``` text
Compose UI -> ViewModel -> Use Case -> Local Repository -> Room -> Host SQLite
                                      |
                                      +-> Embedded LAN API server
```

The host tablet serves authenticated requests from client tablets while
using the same domain rules and repositories.

### LAN client mode

``` text
Compose UI -> ViewModel -> Use Case -> Remote Repository -> LAN API -> Host
```

Clients do not write completed financial transactions to a separate
local business database. Local caching may be used for catalogue display
and unfinished cart drafts only.

## 3. Gradle module responsibilities

  -----------------------------------------------------------------------
  Module                              Responsibility
  ----------------------------------- -----------------------------------
  `:app`                              Android application, app startup,
                                      navigation composition and mode
                                      selection

  `:core:common`                      Result types, dispatchers, clocks,
                                      validators and shared utilities

  `:core:model`                       Shared immutable domain/data
                                      transfer models

  `:core:designsystem`                Material 3 theme, typography,
                                      components and responsive
                                      primitives

  `:core:database`                    Room database, entities, DAOs and
                                      migrations

  `:core:datastore`                   User preferences and non-secret
                                      configuration

  `:core:security`                    Password hashing, token handling
                                      and Keystore integration

  `:core:network`                     HTTP client, serialization, TLS and
                                      network error mapping

  `:domain:*`                         Repository contracts and use cases
                                      by business domain

  `:data:*`                           Local and remote repository
                                      implementations

  `:feature:*`                        Feature UI, ViewModels and UI state

  `:lan:api-contract`                 Versioned request/response DTOs

  `:lan:client`                       API client and remote repository
                                      adapters

  `:lan:host`                         Embedded server, routes, auth
                                      middleware and host lifecycle

  `:lan:pairing`                      Device discovery and pairing
                                      workflow

  `:lan:security`                     Device credentials, certificate
                                      trust and authorization
  -----------------------------------------------------------------------

## 4. Dependency direction

``` text
feature modules ---> domain modules ---> core:model / core:common
       |                    ^
       v                    |
core:designsystem           |
                            |
data modules --------------+
   |          |
   v          v
core:database  core:network

app -> feature modules + data modules + lan modules
```

Domain modules must not depend on Android UI, Room, Ktor or concrete
data implementations. The `app` module is the composition root and binds
interfaces to implementations using Hilt.

## 5. Feature package pattern

Example `:feature:pos`:

``` text
feature/pos/
├── src/main/java/com/retailx/feature/pos/
│   ├── PosRoute.kt
│   ├── PosScreen.kt
│   ├── PosViewModel.kt
│   ├── PosUiState.kt
│   ├── PosUiAction.kt
│   ├── components/
│   │   ├── ProductSearchBar.kt
│   │   ├── ProductGrid.kt
│   │   ├── CartPanel.kt
│   │   └── CheckoutSheet.kt
│   └── mapper/
└── src/test/
```

ViewModels expose immutable `StateFlow<PosUiState>` and accept explicit
UI actions. Composables render state and send actions; they must not
contain tax or inventory business logic.

## 6. Domain use cases

Suggested use cases:

-   `AuthenticateUser`
-   `PairDevice`
-   `SearchProducts`
-   `GetProductByBarcode`
-   `CreateHeldBill`
-   `CompleteSale`
-   `CalculateSaleTotals`
-   `ProcessSalesReturn`
-   `CreatePurchase`
-   `ReceivePurchase`
-   `ProcessPurchaseReturn`
-   `AdjustStock`
-   `GetStockLedger`
-   `PostWalletTransaction`
-   `RecordExpense`
-   `OpenCashRegister`
-   `CloseCashRegister`
-   `GenerateInvoicePdf`
-   `CreateBackup`
-   `RestoreBackup`
-   `GetSalesReport`

Use cases validate business rules and coordinate repositories. Financial
and stock use cases must be transaction-aware.

## 7. Data layer contracts

Example repository contracts:

``` kotlin
interface SalesRepository {
    suspend fun completeSale(command: CompleteSaleCommand): SaleResult
    suspend fun getSale(id: String): Sale?
    suspend fun searchSales(query: SalesQuery): Page<SaleSummary>
    suspend fun returnSale(command: ReturnSaleCommand): ReturnResult
}

interface ProductRepository {
    fun observeProducts(query: ProductQuery): Flow<List<Product>>
    suspend fun findByBarcode(barcode: String): Product?
}

interface InventoryRepository {
    suspend fun getStock(productVariantId: String): StockBalance
    suspend fun adjustStock(command: StockAdjustmentCommand): StockMovement
}
```

Use distinct implementations: - `LocalSalesRepository` for
standalone/host mode. - `RemoteSalesRepository` for LAN client mode.

The same domain contract should be used by the ViewModel regardless of
mode.

## 8. Room and transaction strategy

-   Define Room entities and DAOs in `:core:database`.
-   Keep database access behind data repositories.
-   Use `RoomDatabase.withTransaction { ... }` for multi-table
    operations.
-   Enforce foreign keys and unique indices.
-   Use explicit migrations and export Room schemas to version control.
-   Use SQL constraints as a second line of defense, not only UI
    validation.
-   Store money as `Long` paise; never use `Float` or `Double` for
    persisted money.
-   Store transaction item snapshots to preserve historical invoice
    values.

Example transaction flow for a sale:

``` text
Validate command and idempotency key
          |
          v
Begin Room transaction
          |
          v
Check invoice sequence and stock
          |
          v
Create sale header and item snapshots
          |
          v
Create payment allocations
          |
          v
Create stock movement rows and update balances
          |
          v
Create wallet/ledger postings
          |
          v
Advance invoice sequence + audit event
          |
          v
Commit transaction
```

If any step fails, roll back. PDF generation happens after commit; a PDF
rendering failure must not undo a completed sale.

## 9. LAN host design

### Host components

-   Embedded Ktor server or equivalent maintained Kotlin HTTP server.
-   Authentication and authorization middleware.
-   Versioned API routes.
-   Request validation.
-   Idempotency store.
-   Rate limiting.
-   Room-backed application services.
-   Device registry and revocation.
-   Health/status endpoint.
-   Foreground service/lifecycle integration appropriate to Android
    version and policy.

### Host lifecycle

-   User explicitly enables Host mode.
-   Show persistent status while serving.
-   Display LAN address, connected clients and service health.
-   Stop serving when user disables Host mode.
-   Handle Wi-Fi address changes and client reconnection.
-   Do not promise uninterrupted service if Android terminates the
    process or device sleeps.

### Pairing

1.  Admin enables host mode.
2.  Host creates short-lived pairing challenge.
3.  Client scans QR or enters code.
4.  Admin confirms the device.
5.  Client receives device-scoped credentials.
6.  Credentials are stored securely and can be revoked.
7.  Pairing challenge expires and cannot be reused.

### Retry and idempotency

Every write request carries a unique idempotency key. Host stores the
key, operation type and completed result in the same transaction as the
business operation. If a client times out after the host commits, it
retries with the same key or queries operation status. It must not
create a new key and blindly resubmit.

### Concurrency

-   Host database is the serialization authority.
-   Use database transactions and appropriate constraints.
-   Check available stock inside the transaction.
-   Define whether negative stock is prohibited or allowed by store
    setting.
-   Use unique invoice sequence allocation inside the transaction.
-   Return conflict responses for stale updates.

## 10. API response conventions

Use versioned JSON DTOs and stable error codes.

Example success:

``` json
{
  "requestId": "client-generated-request-id",
  "operationId": "host-operation-id",
  "status": "completed",
  "data": {
    "saleId": "sale-id",
    "invoiceNumber": "INV-000123",
    "totalPaise": 125000
  }
}
```

Example error:

``` json
{
  "requestId": "client-generated-request-id",
  "error": {
    "code": "INSUFFICIENT_STOCK",
    "message": "Available stock is lower than requested quantity"
  }
}
```

Never return stack traces, password hashes, pairing secrets or internal
database paths.

## 11. PDF generation

Create a document model from committed transaction snapshots. Use a PDF
renderer behind an interface:

``` kotlin
interface InvoicePdfGenerator {
    suspend fun generate(invoice: InvoiceDocument, format: InvoiceFormat): Uri
}
```

Supported formats: - A4. - Compact receipt-width PDF presets (58mm and
80mm).

Use Android's Storage Access Framework for user-selected save
destinations and Android Sharesheet for sharing. PDF generation must not
be a dependency of the sale commit transaction.

## 12. Backup and restore

Backup package should contain: - SQLite database. - Required app-managed
images/documents. - Manifest with app/schema version and creation
time. - Checksums.

Restore process: 1. Authenticate admin. 2. Validate package and
checksum. 3. Verify supported schema version. 4. Stop new writes and
host API requests. 5. Create pre-restore backup. 6. Restore database and
files. 7. Reopen database and run integrity checks. 8. Restart host
service if enabled. 9. Record restore audit event.

## 13. Testing strategy

-   Domain unit tests for tax, discount, pricing, returns and inventory.
-   Room DAO tests using in-memory database.
-   Migration tests from every released schema version.
-   Repository contract tests for local and remote implementations.
-   API integration tests for auth, authorization, idempotency and
    validation.
-   Concurrent LAN sale tests.
-   Compose UI tests for checkout and key workflows.
-   PDF snapshot/content tests.
-   Backup/restore round-trip tests.
-   Device tests for screen sizes, rotation, process death and network
    loss.

## 14. Build and implementation conventions

-   Use Gradle Kotlin DSL and `libs.versions.toml`.
-   Pin compatible stable dependency versions.
-   Enable Kotlin compiler warnings and lint checks.
-   Use ktfmt or another consistent formatter.
-   Keep secrets out of source control.
-   Use build variants such as `dev`, `staging` and `release`.
-   Add CI for unit tests, lint and APK assembly.
-   Never use destructive Room migration in release builds.
-   Document every schema migration and API contract change.

## 15. Android Studio setup

1.  Install the current stable Android Studio.
2.  Install a compatible Android SDK and platform tools.
3.  Open the `RetailX-Android` root project.
4.  Allow Gradle sync and install required SDK components.
5.  Create an emulator with a tablet profile or connect a physical
    Android tablet with USB debugging enabled.
6.  Run the `app` configuration.
7.  For LAN testing, connect host and client tablets to the same trusted
    Wi-Fi network.
8.  Build a debug APK using Android Studio or
    `./gradlew :app:assembleDebug`.
9.  Test backup/restore before loading real business data.

Exact `minSdk`, `targetSdk`, AGP, Kotlin and library versions must be
selected as a compatible set at implementation kickoff and checked
against current Android distribution and Play requirements.

------------------------------------------------------------------------

**End of architecture document**
