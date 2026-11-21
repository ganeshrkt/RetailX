# RetailX Android POS --- Product Requirements Document

**Version:** 1.0\
**Status:** Finalized baseline\
**Platform:** Android tablets\
**Market:** India\
**Application type:** Native, offline-first retail POS with optional
local-network multi-device operation\
**Database:** SQLite via Room\
**UI:** Dark and light themes\
**Invoice output:** PDF (A4 and thermal-width PDF layouts)\
**Development scope:** Complete v1.0 application

------------------------------------------------------------------------

## 1. Executive summary

RetailX Android is a professional retail point-of-sale and
store-management application designed primarily for Android tablets. It
provides billing, product and inventory control, purchasing, customer
and supplier records, accounts, expenses, reports, staff permissions,
PDF invoices, and backup/restore.

The application must remain usable without internet access. A single
tablet can operate independently. When LAN mode is configured, multiple
authorized devices on the same local network can work against one
authoritative store database through a local RetailX service. The system
must not attempt to synchronize independent SQLite files by copying
database files between devices.

## 2. Confirmed product decisions

  -----------------------------------------------------------------------
  Area                                Final decision
  ----------------------------------- -----------------------------------
  UI                                  Both dark and light themes

  Printer/output                      PDF only in v1.0

  Development scope                   Complete application

  Device connectivity                 LAN multi-device support

  Local database                      SQLite + Room

  Currency                            INR (₹)

  Tax context                         India; configurable GST

  Store model                         Single store per
                                      installation/server

  Internet                            Not required for core operation

  Android implementation              Kotlin + Jetpack Compose

  Distribution                        APK for direct installation; AAB
                                      optional for Play distribution
  -----------------------------------------------------------------------

**PDF-only clarification:** Version 1.0 generates and shares PDF
invoices/receipts. Direct Bluetooth, USB, and Wi-Fi printer drivers are
out of scope. A user may print a generated PDF using Android's system
print/share facilities if the device has a compatible print service, but
RetailX will not implement printer-specific protocols in v1.0.

## 3. Goals

1.  Complete retail billing with fast product search and barcode input.
2.  Maintain accurate stock and purchase records.
3.  Calculate configurable GST and generate professional invoices.
4.  Track cash, UPI, card, bank and split payments.
5.  Maintain customer, supplier, wallet and expense ledgers.
6.  Provide useful sales, stock, tax and financial reports.
7.  Operate offline on one tablet.
8.  Support multiple Android clients on a local Wi-Fi/LAN using a
    central store service.
9.  Protect business data through authentication, permissions, audit
    logs and backups.
10. Provide an adaptable tablet-first interface in both themes.

## 4. Non-goals for v1.0

-   Cloud hosting or cloud synchronization.
-   Multi-branch / multi-store consolidation.
-   E-commerce storefront or online ordering.
-   Payment gateway integration or automatic UPI settlement
    verification.
-   Direct thermal printer SDK integration.
-   Automatic GST portal filing or e-invoice / e-way bill submission.
-   Payroll, full statutory accounting, or bank-feed integration.
-   iOS or desktop client.
-   Conflict-free synchronization between independent offline databases.

These may be evaluated for later releases.

## 5. Users and roles

### 5.1 Roles

-   **Owner/Admin:** Full access, store configuration, users, backup and
    restore.
-   **Manager:** Operational management, purchasing, inventory,
    customers, suppliers, reports and permitted accounts functions.
-   **Cashier:** POS, customer lookup/create, own sales and permitted
    returns.
-   **Accountant (optional configurable role):** Wallets, expenses,
    payments and financial reports.

Permissions must be granular and configurable by the administrator. The
role names are defaults, not hard-coded authorization rules.

### 5.2 Authentication

-   Local username/PIN or password sign-in.
-   Secure password hashing using a modern password KDF (Argon2id where
    supported, otherwise PBKDF2-HMAC-SHA256 with suitable parameters and
    per-user salt).
-   Session timeout and optional quick cashier PIN.
-   Admin-only user creation, disablement and permission changes.
-   Failed-login throttling and audit logging.
-   Do not store plaintext passwords, PINs or authentication tokens.

## 6. Functional requirements

### 6.1 Store setup and settings

-   Store name, logo, address, phone, email and GSTIN.
-   State and state code, default place of supply and tax configuration.
-   Currency (default INR), decimal and date formats.
-   Invoice prefixes, numbering sequence and footer text.
-   Default payment methods and default tax-inclusive/exclusive price
    behavior.
-   Theme selection: System / Light / Dark.
-   LAN server/client setup, pairing and connection status.
-   Backup destination and retention settings.

### 6.2 Dashboard

Display: - Today's gross sales, net sales, discounts and returns. -
Number of completed bills. - Gross profit estimate and expenses, with
clear calculation basis. - Low-stock and out-of-stock products. - Recent
sales and payment summary. - Top-selling products. -
Daily/weekly/monthly trend charts.

Dashboard figures must be derived from persisted transactions and
respect user permissions. Report date ranges and store timezone must be
explicit.

### 6.3 Point of Sale (POS)

-   Search by product name, SKU or barcode.
-   Accept input from camera scanning or keyboard-wedge Bluetooth/USB
    scanners where Android exposes them as keyboard input.
-   Category filters and touch-friendly product tiles.
-   Add, remove and change item quantity.
-   Apply permitted line-level and bill-level discounts.
-   Select customer or use a walk-in customer.
-   Support tax-inclusive and tax-exclusive pricing.
-   Calculate taxable value, GST components and final total.
-   Hold, resume and cancel draft bills.
-   Support cash, UPI, card, bank transfer, credit and split tender.
-   Record amount tendered, change due and payment references.
-   Complete sale atomically with invoice number, sale items, payment
    rows, stock movements and ledger entries.
-   Reprint/re-export a saved invoice as PDF.
-   Restrict price overrides, discounts, voids and returns by
    permission.
-   Prevent duplicate submission on double tap or retry using an
    idempotency key.

**Sale transaction invariants:** 1. A completed sale, its items, payment
allocations, stock movements and financial postings are committed in one
database transaction. 2. If any required step fails, the transaction
rolls back completely. 3. Invoice numbers are unique within the store.
4. A completed financial transaction is not hard-deleted; it is
voided/reversed with reason and audit trail. 5. Draft/held bills do not
reduce stock unless an explicit reservation feature is introduced later.

### 6.4 Sales and returns

-   Sales register with invoice search, date, customer, cashier and
    payment filters.
-   Sale detail with items, tax breakdown, payment allocations and
    status.
-   Full and partial sales returns.
-   Return reason, returned quantity and refund method.
-   Return must not exceed the remaining returnable quantity.
-   Returned stock is restored only when item condition/restock setting
    permits.
-   Credit note/return document PDF.
-   Void/cancel workflow with permission, reason and reversal entries.

### 6.5 Product catalogue

-   Product name, description, SKU, barcode, category, brand and unit.
-   Purchase cost, selling price and optional MRP.
-   HSN code, GST rate and tax category.
-   Tax-inclusive/exclusive selling-price flag.
-   Reorder level and active/inactive state.
-   Product image stored as an app-managed file reference, not a large
    database blob by default.
-   Optional variants (size, color, pack, etc.) with distinct
    SKU/barcode and stock.
-   Duplicate SKU/barcode validation.
-   Bulk import/export using CSV with preview and validation.

### 6.6 Inventory

-   Opening stock.
-   Stock on purchase receipt.
-   Stock reduction on completed sale.
-   Stock restoration on eligible sale return.
-   Stock reduction on purchase return.
-   Manual stock adjustment with reason and approval permission.
-   Stock ledger and movement history.
-   Low-stock and out-of-stock alerts.
-   Inventory valuation using weighted-average cost.
-   Stock count/reconciliation workflow.
-   All stock changes recorded as immutable movement rows; current
    quantity may be maintained as a transactionally updated
    balance/cache.

### 6.7 Purchase management

-   Supplier purchase entry with supplier invoice number/date.
-   Item rows, quantity, purchase rate, discount, taxable value, HSN and
    tax.
-   CGST/SGST for intra-state and IGST for inter-state transactions.
-   Additional charges and rounding adjustment.
-   Draft, received and cancelled statuses.
-   Stock increases only when purchase is marked received.
-   Supplier payments, partial payments and outstanding balance.
-   Purchase return with stock reduction and supplier debit/credit
    adjustment.
-   Purchase PDF document.

### 6.8 Customers

-   Name, mobile, email, address, state, GSTIN and customer type.
-   Search and duplicate-mobile warning.
-   Purchase history and customer ledger.
-   Opening balance and credit limit (if enabled).
-   Outstanding balance and payment collection.
-   Customer-specific discount rules (optional, permission-controlled).

### 6.9 Suppliers

-   Supplier name, contact, address, state, GSTIN and notes.
-   Purchase history and supplier ledger.
-   Opening balance, payments and outstanding payable.
-   Supplier return and adjustment history.

### 6.10 Accounts, wallets and expenses

-   Wallet/account types: cash, bank, UPI, card clearing and other.
-   Opening balance.
-   Income and expense categories.
-   Expense voucher with date, wallet, category, payee, amount, note and
    attachment reference.
-   Cash deposit/withdrawal and wallet-to-wallet transfer.
-   Sale payment posting and purchase/expense payment posting.
-   Wallet ledger and balance summary.
-   Reconciliation report.

Every financial posting must have a source reference and a reversal
mechanism. Avoid recording the same payment both as a wallet transaction
and as a separate unlinked cash movement.

### 6.11 Cash register

-   Open/close cashier shift.
-   Opening cash amount.
-   Cash sales, refunds, cash expenses and cash movements during shift.
-   Expected closing cash and counted closing cash.
-   Variance and explanation.
-   Shift report PDF.
-   Optional manager approval for variance above configured threshold.

### 6.12 Reports

-   Sales summary and sales register.
-   Item-wise and category-wise sales.
-   Profit and cost-of-goods-sold report.
-   Purchase and supplier outstanding.
-   Customer outstanding and ledger.
-   Stock on hand, low stock, stock valuation and movement.
-   Sales return and purchase return.
-   GST summary by rate and tax component.
-   Wallet ledger, expenses and cash register.
-   Payment method summary.
-   User/cashier performance.
-   PDF and CSV export with date range and filters.

Reports must state whether totals include/exclude tax, discounts and
returns. Profit is an estimate based on the configured weighted-average
cost method.

### 6.13 PDF documents

Generate: - A4 tax invoice. - Compact receipt-style PDF layout
(58mm/80mm page width presets). - Sales return / credit note. - Purchase
document. - Payment receipt. - Cash register close report. - Selected
business reports.

PDF requirements: - Store branding and logo. - Invoice/document number
and date. - Seller and customer details. - Product description,
quantity, unit, rate, discount, HSN and tax. - Taxable value and
CGST/SGST/IGST breakdown. - Grand total, amount paid, balance and
amount-in-words where practical. - Clear cancellation/duplicate markings
where applicable. - Share through Android Sharesheet and save through
Storage Access Framework. - Recreate PDFs from saved transaction data;
do not treat a PDF as the source of truth.

### 6.14 Staff and activity log

-   User list, roles and permissions.
-   Enable/disable users.
-   Audit important actions: login, sale completion, discount override,
    void, return, stock adjustment, purchase receipt, expense, settings
    change, backup and restore.
-   Log actor, timestamp, action, entity type/id and concise
    before/after metadata where appropriate.
-   Do not log passwords, PINs, secrets or full payment credentials.

### 6.15 Backup and restore

-   Manual backup.
-   Scheduled backup using WorkManager when the app/server device is
    available.
-   Backup to user-selected local folder/USB through Android Storage
    Access Framework.
-   Include database, required app-managed files, schema version and
    manifest/checksum.
-   Validate backup integrity before restore.
-   Require admin authentication and explicit confirmation before
    restore.
-   Create a pre-restore backup.
-   Restore only while database writes are stopped.
-   Provide backup history and clear success/failure status.

## 7. LAN multi-device architecture and behavior

### 7.1 Selected model

RetailX uses a **central-authority LAN model**:

-   One Android tablet is configured as the **Store Host**.
-   The host runs the local RetailX service and owns the authoritative
    SQLite database.
-   Other tablets are **LAN Clients**.
-   Clients access business operations through an authenticated
    local-network API.
-   Client devices may cache non-sensitive catalogue data for
    responsiveness, but the host remains the source of truth for stock,
    invoices, users and financial records.

This is not peer-to-peer database synchronization.

### 7.2 Network requirements

-   Host and clients must be on the same trusted Wi-Fi/LAN.
-   Pairing by QR code or one-time pairing code.
-   Host address discovery using QR/manual IP; optional mDNS discovery.
-   TLS for LAN API traffic using a pairing/trust flow suitable for a
    private local service.
-   Per-device credentials, revocation and session expiry.
-   Network timeout, retry and visible connection state.
-   Idempotency keys for sale/return/payment requests.
-   Host must reject duplicate invoice/payment submissions.
-   Host database transactions serialize conflicting stock updates.

### 7.3 Offline behavior

-   If configured as standalone, a tablet works fully offline with its
    own database.
-   In LAN-client mode, clients require access to the host for
    authoritative writes.
-   If the host becomes unavailable, clients must clearly enter
    read-only/disconnected mode; they must not silently create local
    invoices that later collide.
-   A client may preserve an unfinished cart locally as a draft, but it
    cannot mark it as a completed sale until the host confirms.
-   The host itself continues operating if the internet is unavailable.
-   Host device sleep, Android battery optimization and Wi-Fi changes
    must be considered; provide a host status screen and guidance to
    keep the host powered while serving clients.

### 7.4 LAN API domains

-   Authentication and device pairing.
-   Store configuration.
-   Products, categories and barcode lookup.
-   Customers and suppliers.
-   Sales, held bills, returns and payments.
-   Purchases and receiving.
-   Inventory and adjustments.
-   Wallets, expenses and cash register.
-   Reports and PDF generation/download.
-   Backup administration (admin only).

### 7.5 LAN security

-   No open unauthenticated API.
-   Pairing secret shown only during setup and rotated/revoked by admin.
-   Per-device authorization and role checks on every operation.
-   TLS preferred; never transmit passwords or long-lived bearer secrets
    over plain HTTP.
-   Rate limiting and audit logging.
-   Host firewall/network guidance.
-   Do not expose the service to the public internet in v1.0.

**Implementation note:** Android does not guarantee that a background
service will remain continuously available indefinitely. The host
service must use an appropriate foreground-service model where required
by the target Android version and Play policy, with a persistent
notification and explicit user control. Validate current Android
foreground-service type and permission requirements during
implementation.

## 8. UX and responsive design

### 8.1 Themes

-   Light and dark themes, plus optional System setting.
-   Persist user selection.
-   Meet readable contrast and accessibility requirements.
-   Semantic colors for success, warning, error and information.

### 8.2 Tablet layout

-   Primary POS layout optimized for landscape.
-   Management screens adapt to portrait and landscape.
-   Navigation rail/sidebar on wide layouts; compact navigation on
    narrow layouts.
-   Touch targets generally at least 48dp.
-   Keyboard and barcode-scanner input support.
-   Responsive two/three-pane layouts where useful.
-   Loading, empty, validation, offline and error states for every
    feature.

### 8.3 POS layout

-   Left: navigation/categories.
-   Center: search and product grid.
-   Right: cart, totals and checkout.
-   Bottom/action area: hold, clear, customer, discount and checkout.
-   Checkout should minimize steps and avoid hidden totals.

## 9. Technical stack

  -----------------------------------------------------------------------
  Layer                               Technology
  ----------------------------------- -----------------------------------
  Language                            Kotlin

  UI                                  Jetpack Compose + Material 3

  Architecture                        MVVM + Clean Architecture

  Local database                      SQLite via Room

  Async                               Coroutines + Flow

  Dependency injection                Hilt

  Preferences                         DataStore

  Background tasks                    WorkManager

  API (LAN)                           Ktor server/client or equivalent
                                      embedded Kotlin HTTP stack

  Serialization                       Kotlinx Serialization

  PDF                                 Android PdfDocument or a maintained
                                      PDF library

  Charts                              Compose-compatible chart library or
                                      custom Compose charts

  Barcode camera                      CameraX + ML Kit Barcode Scanning

  CSV                                 Kotlin CSV parser/writer

  Testing                             JUnit, Kotlin coroutines test,
                                      Compose UI test, Room tests

  Build                               Gradle Kotlin DSL
  -----------------------------------------------------------------------

Use current stable compatible versions at implementation time and pin
dependency versions through a version catalog. Avoid depending on alpha
libraries for core data integrity.

## 10. Architecture overview

``` text
                 ANDROID TABLET CLIENTS
       +----------------+   +----------------+
       | Compose UI     |   | Compose UI     |
       | ViewModels     |   | ViewModels     |
       +-------+--------+   +-------+--------+
               |                    |
       +-------v--------+   +-------v--------+
       | Domain/use cases|   | Domain/use cases|
       +-------+--------+   +-------+--------+
               |                    |
       +-------v--------+   +-------v--------+
       | LAN API client |   | LAN API client |
       +-------+--------+   +-------+--------+
               |                    |
               +---------+----------+
                         |
                    Trusted LAN
                         |
               +---------v----------+
               | ANDROID HOST TABLET|
               | Ktor/Local API     |
               | Auth + permissions |
               | Application rules |
               | Room repositories  |
               +---------+----------+
                         |
                   +-----v-----+
                   | SQLite DB |
                   | Room      |
                   +-----------+
```

Standalone mode uses the same domain/use-case and repository contracts,
with Room as the local repository implementation. LAN-client mode uses
remote repository implementations for authoritative operations.

## 11. Suggested Android Studio project structure

``` text
RetailX-Android/
├── app/                         # Android application and composition root
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/retailx/
│       │   │   ├── RetailXApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── di/
│       │   │   ├── navigation/
│       │   │   └── platform/
│       │   │       ├── pdf/
│       │   │       ├── scanner/
│       │   │       ├── storage/
│       │   │       └── connectivity/
│       │   └── res/
│       ├── test/
│       └── androidTest/
│
├── core/
│   ├── common/
│   ├── model/
│   ├── designsystem/
│   ├── database/
│   │   ├── entity/
│   │   ├── dao/
│   │   ├── migration/
│   │   └── RetailXDatabase.kt
│   ├── datastore/
│   ├── security/
│   ├── network/
│   └── testing/
│
├── domain/
│   ├── auth/
│   ├── store/
│   ├── catalog/
│   ├── sales/
│   ├── inventory/
│   ├── purchases/
│   ├── parties/
│   ├── finance/
│   ├── reports/
│   └── backup/
│
├── data/
│   ├── auth/
│   ├── store/
│   ├── catalog/
│   ├── sales/
│   ├── inventory/
│   ├── purchases/
│   ├── parties/
│   ├── finance/
│   ├── reports/
│   └── backup/
│
├── feature/
│   ├── onboarding/
│   ├── login/
│   ├── dashboard/
│   ├── pos/
│   ├── sales/
│   ├── products/
│   ├── inventory/
│   ├── purchases/
│   ├── customers/
│   ├── suppliers/
│   ├── accounts/
│   ├── reports/
│   ├── staff/
│   ├── activity/
│   └── settings/
│
├── lan/
│   ├── api-contract/
│   ├── client/
│   ├── host/
│   ├── pairing/
│   └── security/
│
├── build-logic/                  # Optional convention plugins
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── docs/
    ├── PRD.md
    ├── ARCHITECTURE.md
    ├── DATABASE.md
    ├── LAN_PROTOCOL.md
    └── TEST_PLAN.md
```

For a maintainable production project, feature modules should own their
UI and ViewModels; domain modules should hold use cases and repository
contracts; data modules should implement those contracts. Avoid a single
giant `app` package.

## 12. Core database model

Use Room entities with stable primary keys (UUID strings or generated
Long IDs), foreign keys, indexes and explicit schema migrations. Store
timestamps in UTC epoch milliseconds and format them in the store's
configured timezone. Store money as integer paise.

### 12.1 Main tables

  -----------------------------------------------------------------------
  Table                               Key fields / purpose
  ----------------------------------- -----------------------------------
  stores                              id, name, GSTIN, state_code,
                                      address, settings

  users                               id, username, password_hash, salt,
                                      status

  roles                               id, name

  permissions                         id, code

  user_roles                          user_id, role_id

  role_permissions                    role_id, permission_id

  devices                             id, name, device_key_hash, status,
                                      paired_at

  categories                          id, name, parent_id

  brands                              id, name

  units                               id, name, short_name

  products                            id, name, sku, barcode,
                                      category_id, brand_id, hsn,
                                      gst_rate, cost_price_paise,
                                      selling_price_paise, reorder_level

  product_variants                    id, product_id, sku, barcode,
                                      attributes_json

  stock_balances                      product_variant_id, location_id,
                                      quantity_scaled

  stock_movements                     id, product_variant_id,
                                      movement_type,
                                      quantity_delta_scaled,
                                      unit_cost_paise, source_type,
                                      source_id, created_at

  customers                           id, name, mobile, GSTIN,
                                      state_code, address

  suppliers                           id, name, mobile, GSTIN,
                                      state_code, address

  sales                               id, invoice_no, customer_id,
                                      cashier_id, status, subtotal_paise,
                                      discount_paise, taxable_paise,
                                      tax_paise, total_paise, created_at

  sale_items                          id, sale_id, product_variant_id,
                                      description_snapshot, qty_scaled,
                                      rate_paise, discount_paise,
                                      taxable_paise, tax_rate, tax_paise,
                                      total_paise

  sale_payments                       id, sale_id, wallet_id, method,
                                      amount_paise, reference

  sales_returns                       id, return_no, sale_id, reason,
                                      total_paise, status, created_at

  sales_return_items                  id, return_id, sale_item_id,
                                      qty_scaled, refund_paise, restock

  purchases                           id, supplier_id,
                                      supplier_invoice_no, purchase_no,
                                      status, totals, created_at

  purchase_items                      id, purchase_id,
                                      product_variant_id, qty_scaled,
                                      rate_paise, tax fields

  purchase_payments                   id, purchase_id, wallet_id,
                                      amount_paise, reference

  purchase_returns                    id, return_no, purchase_id, reason,
                                      total_paise

  wallets                             id, name, type,
                                      opening_balance_paise, active

  financial_transactions              id, wallet_id, direction,
                                      amount_paise, source_type,
                                      source_id, reversal_of_id,
                                      created_at

  expenses                            id, category_id, wallet_id,
                                      amount_paise, note, created_at

  expense_categories                  id, name

  cash_registers                      id, user_id, opened_at,
                                      opening_cash_paise, closed_at,
                                      counted_cash_paise, variance_paise

  held_bills                          id, user_id, cart_json, created_at,
                                      updated_at

  invoice_sequences                   id, document_type, prefix,
                                      next_number, financial_year

  activity_logs                       id, user_id, device_id, action,
                                      entity_type, entity_id,
                                      metadata_json, created_at

  app_settings                        key, value

  backup_history                      id, uri, status, checksum,
                                      created_at
  -----------------------------------------------------------------------

The table list is a logical baseline; exact normalized columns and
indices must be documented in DATABASE.md before implementation.

### 12.2 Data integrity rules

-   Use integer paise for all monetary values.
-   Use a documented fixed-precision representation for quantities (for
    example, thousandths) if fractional units are supported.
-   Enable SQLite foreign keys.
-   Use Room migrations; never use destructive migration in production.
-   Use unique constraints for SKU, barcode where present, and invoice
    numbers within their scope.
-   Save product name, HSN, tax rate and unit snapshots in transaction
    items so historic invoices remain unchanged when the catalogue
    changes.
-   Use database transactions for every multi-table business operation.
-   Use append-only stock movement and financial transaction records
    with reversals.
-   Maintain current stock and wallet balances transactionally, with
    reconciliation jobs/reports.
-   Store images and generated documents in app-managed files; store
    references and metadata in SQLite.

## 13. API and LAN contract

Use versioned endpoints, e.g. `/api/v1/...`.

Representative routes:

``` text
POST   /api/v1/pairing/start
POST   /api/v1/pairing/complete
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
GET    /api/v1/store
GET    /api/v1/products
GET    /api/v1/products/barcode/{code}
POST   /api/v1/sales
GET    /api/v1/sales/{id}
POST   /api/v1/sales/{id}/returns
POST   /api/v1/purchases
POST   /api/v1/purchases/{id}/receive
GET    /api/v1/inventory/summary
POST   /api/v1/inventory/adjustments
GET    /api/v1/reports/sales
POST   /api/v1/backups
```

Every write endpoint must: - Authenticate the device and user. -
Authorize the requested operation. - Validate all input on the host. -
Accept an idempotency key for retry-safe financial operations. - Execute
business changes in a database transaction. - Return a stable operation
ID and authoritative result. - Record an audit event.

API payloads must have explicit versioning and must not expose internal
database entities directly.

## 14. GST and financial calculation rules

-   Support intra-state CGST + SGST and inter-state IGST.
-   Determine tax treatment from configured supplier/store/customer
    place-of-supply rules and transaction context; do not infer it from
    a phone number.
-   Support configurable tax rates and HSN/SAC.
-   Support inclusive and exclusive pricing.
-   Define rounding at line and invoice level and use the same rules in
    UI, stored records, reports and PDFs.
-   Preserve tax snapshots on completed documents.
-   Support exempt/nil-rated/non-GST categories where configured.
-   Validate tax rates, invoice fields and statutory document wording
    against current Indian rules with a qualified tax professional
    before production use.

RetailX is billing software, not a tax filing service. It must not claim
GST portal compliance or automated return filing unless separately
implemented and validated.

## 15. Non-functional requirements

### Performance

-   Product search should feel immediate for a typical small/medium
    store catalogue.
-   Use pagination for large registers and reports.
-   Avoid blocking the main thread for database, PDF, image or network
    operations.
-   Use database indices based on query plans and measured usage.

### Reliability

-   Atomic sale/purchase/return operations.
-   Recovery after process termination.
-   Database migration tests.
-   Backup integrity verification.
-   No silent data loss on LAN disconnect.

### Security and privacy

-   Least-privilege permissions.
-   Encrypted transport on LAN.
-   Secure credential storage using Android Keystore-backed mechanisms
    where appropriate.
-   No sensitive information in logs.
-   User-visible backup/export controls.
-   Clear data-retention and restore behavior.

### Accessibility

-   Scalable text.
-   Content descriptions for controls.
-   Adequate contrast in both themes.
-   Keyboard navigation where feasible.
-   Large touch targets.

### Compatibility

-   Target Android tablet layouts.
-   Define minSdk and targetSdk using current Android requirements at
    release time; Android 10 is the intended minimum compatibility
    baseline, subject to library and Play policy support.
-   Test on multiple screen sizes and Android versions.

## 16. Testing and acceptance criteria

### POS and transaction tests

-   Complete a sale with cash, UPI, card and split tender.
-   Double-tap/retry must not create duplicate sales.
-   Failed sale must leave stock and wallet balances unchanged.
-   Invoice number uniqueness must hold under concurrent LAN clients.
-   Discount and tax calculations must match approved test cases.
-   Full/partial return quantity and refund limits must be enforced.

### Inventory and purchasing tests

-   Purchase receipt increases stock once only.
-   Sale reduces stock once only.
-   Eligible return restores stock once only.
-   Purchase return reduces stock once only.
-   Stock adjustment requires permission and reason.
-   Stock ledger must reconcile with current balance.

### LAN tests

-   Pair and revoke devices.
-   Two clients sell the last available unit concurrently; host must
    prevent overselling according to configured stock policy.
-   Host disconnect during sale; client must show unknown/pending status
    and safely query by idempotency key before retry.
-   Client cannot complete a sale while disconnected.
-   Unauthorized devices and users are rejected.
-   Host restart preserves transactions and device registrations.

### Backup and PDF tests

-   Backup and restore preserve database relationships and app-managed
    files.
-   Corrupt backup is rejected.
-   Pre-restore backup is created.
-   A4 and compact PDF layouts render long names, tax rows and large
    invoices.
-   PDF sharing and saving work through Android system flows.

### Release acceptance

-   All critical business flows pass automated tests.
-   No destructive database migration.
-   No unresolved critical/high severity data-integrity or security
    defects.
-   Tested on at least one 8-inch and one 10--12-inch tablet.
-   Both themes verified.
-   LAN mode tested with one host and at least two clients.
-   User guide and backup/restore instructions included.

## 17. Delivery plan

1.  Foundation: Gradle setup, design system, navigation, Room schema,
    migrations and dependency injection.
2.  Store setup, authentication, roles and permissions.
3.  Catalogue, barcode input and inventory ledger.
4.  POS cart, held bills, pricing, tax engine and atomic sales.
5.  PDF invoices, sales register, returns and payment records.
6.  Purchases, suppliers, purchase returns and customer accounts.
7.  Wallets, expenses, cash register and financial ledger.
8.  Reports, exports, dashboard and activity log.
9.  LAN host/client, pairing, API authorization and idempotent writes.
10. Backup/restore, hardening, tablet QA and release packaging.

## 18. Release definition

Version 1.0 is complete when: - All functional modules in this PRD are
implemented. - Standalone offline mode works. - LAN host/client mode
works on a trusted local network. - Dark and light themes are
available. - PDF invoice and report export works. - Data integrity,
backup/restore and role permissions pass acceptance tests. - Android
Studio project, source code, APK and setup/user documentation are
delivered.

------------------------------------------------------------------------

**End of PRD**
