# Database

PostgreSQL 16. Tables are created by the numbered files in `server/src/db/migrations`, in order. Update this page in the same pull request as any migration.

## See the data

- **In the browser:** run `docker compose up -d` in the repo folder, open http://localhost:8080 and sign in with System `PostgreSQL`, Server `db`, User `sales`, Password `sales`, Database `sales_dev`.
- **In an app:** TablePlus, Postico, DBeaver or pgAdmin, connecting to `localhost:5432` with user `sales`, password `sales`, database `sales_dev`.
- **In Terminal:** `psql postgres://sales:sales@localhost:5432/sales_dev`, then `\dt` lists the tables.

Run `cd server && npm run seed` once to create the tables and the starting data.

The seed also creates the first admin, if no admin exists yet. Its phone number and PIN come from `BOOTSTRAP_ADMIN_PHONE` and `BOOTSTRAP_ADMIN_PIN` in `server/.env`. The placeholders are `0990000000` and `1234`. Set your own before go-live. In production the seed refuses to run with the placeholders.

## Tables

```mermaid
erDiagram
    facilities ||--o{ companies : "serves"
    companies ||--o{ user_companies : "visible to"
    users ||--o{ user_companies : "sees"
    users ||--o{ sessions : "signed in as"
    users ||--o{ sales_targets : "aims for"
    customers ||--o{ customer_companies : "buys from"
    companies ||--o{ customer_companies : "sells to"
    companies ||--o{ products : "sells"
    facilities ||--o{ vehicles : "based at"
    users ||--o{ vehicles : "usual driver"
    users ||--o{ user_dormant_vehicles : "does not use"
    vehicles ||--o{ user_dormant_vehicles : "dormant for"
    customer_companies ||--o| opening_balances : "starts with"
    companies ||--o{ number_series : "numbers"
    products ||--o{ stock_batches : "kept in"
    stock_batches ||--o{ stock_movements : "changed by"
    stock_receipts ||--o{ stock_movements : "adds"
    stock_counts ||--o{ stock_movements : "corrects"
    stock_batches ||--o{ stock_adjustment_requests : "waits for approval"
    facilities {
        text code PK "F1, F2"
        text name
    }
    companies {
        text code PK "LV, ZT, ZF, GL"
        text name
        text facility_code FK
        text tpin "empty until go-live"
        numeric vat_rate "empty until go-live"
        text phone
        text email
        text address
        int sort_order
    }
    users {
        int id PK
        text name
        text phone UK "0991234567, or +971... from abroad"
        text role "eight roles"
        text pin_hash "scrypt, never plain text"
        int manager_id FK "salesmen only"
        boolean active
        int failed_pin_attempts
        timestamptz locked_until
    }
    user_companies {
        int user_id PK, FK
        text company_code PK, FK
        int manager_id FK "salesmen: sales manager for this company"
    }
    sessions {
        text token_hash PK "sha256 of the token"
        int user_id FK
        timestamptz last_used_at
    }
    customers {
        int id PK
        text name UK "unique, any case"
        text channel "supermarket, wholesaler, minibus, upcountry"
        text area
        text address
        text city
        text tpin
        text reg_no
        text phone
        boolean needs_lpo
        boolean cash_only
        boolean active
    }
    customer_companies {
        int customer_id PK, FK
        text company_code PK, FK
        int payment_terms_days "0 to 365"
        bigint credit_limit "reports only"
    }
    products {
        int id PK
        text code UK
        text company_code FK
        text brand
        text name
        text pack_size
        int pieces_per_carton
        boolean vat_exempt
        int low_stock_cartons
        boolean active
        timestamptz deleted_at
    }
    vehicles {
        int id PK
        text plate UK
        text description
        text facility_code FK
        int default_driver_id FK
        boolean active
        timestamptz deleted_at
    }
    user_dormant_vehicles {
        int user_id PK, FK
        int vehicle_id PK, FK
    }
    opening_balances {
        int customer_id PK, FK
        text company_code PK, FK
        bigint amount "whole kwacha"
        date as_of
        int entered_by FK
    }
    number_series {
        text company_code PK, FK
        text doc_type PK "SO, INV, ..."
        int last_number
    }
    stock_batches {
        int id PK
        int product_id FK
        text batch_no "unique per product"
        date expiry_date
        int on_hand "pieces, never below 0"
    }
    stock_receipts {
        int id PK
        text number UK "LV-GRN-000001"
        text company_code FK
        text supplier
        text reference
        date received_on
        int received_by FK
    }
    stock_counts {
        int id PK
        text number UK "LV-CNT-000001"
        text company_code FK
        int counted_by FK
    }
    stock_movements {
        bigint id PK
        int batch_id FK
        int change "pieces, + or -"
        text kind "received, adjusted, counted"
        text reason "damaged, expired, lost, found, other"
        int receipt_id FK
        int count_id FK
        int request_id FK
        int user_id FK
    }
    stock_adjustment_requests {
        int id PK
        int batch_id FK
        int change "pieces, + or -"
        text reason
        text status "pending, approved, rejected"
        int requested_by FK
        int decided_by FK
    }
    orders ||--|{ order_companies : "for"
    orders ||--|{ order_lines : "has"
    orders ||--o{ order_events : "history"
    order_lines ||--o{ order_allocations : "reserves"
    stock_batches ||--o{ order_allocations : "set aside"
    customers ||--o{ orders : "places"
    orders {
        int id PK
        text number "SO-000123"
        text facility_code FK
        int customer_id FK
        int salesman_id FK
        text invoice_type "vat or plain"
        date wanted_on
        text status "open, hold, done, cancelled"
        int booked_by FK
    }
    order_companies {
        int order_id PK
        text company_code PK
        text lpo_no
    }
    order_lines {
        int id PK
        int order_id FK
        int product_id FK
        int pieces
        int price_per_carton "MWK, without VAT"
        int delivered "pieces"
    }
    order_allocations {
        int line_id PK
        int batch_id PK
        int pieces
    }
    order_events {
        bigint id PK
        int order_id FK
        int user_id FK
        text title
        text detail
    }
    loading_plans ||--o{ loading_plan_items : "carries"
    order_lines ||--o{ loading_plan_items : "planned on"
    loading_plan_items ||--o{ loading_plan_item_batches : "loaded from"
    loading_plans ||--o{ loading_plan_changes : "log"
    vehicles ||--o{ loading_plans : "drives"
    loading_plans ||--o{ invoices : "invoiced"
    customers ||--o{ invoices : "billed"
    invoices ||--o{ invoice_lines : "lists"
    loading_plan_items ||--o| invoice_lines : "billed as"
    loading_plans ||--o{ deliveries : "drops"
    deliveries ||--o{ loading_plan_items : "confirms"
    deliveries ||--o{ stock_movements : "deducts"
    customers ||--o{ collections : "pays"
    invoices ||--o{ collections : "picked"
    collections ||--o{ collection_allocations : "goes on"
    invoices ||--o{ collection_allocations : "paid by"
    handovers ||--o{ collections : "cash in"
    handovers ||--o{ expenses : "spent"
    users ||--o| airtel_wallets : "holds"
    airtel_wallets ||--o{ airtel_wallet_companies : "serves"
    airtel_wallets ||--o{ airtel_entries : "ledger"
    airtel_wallets ||--o{ airtel_checks : "checked"
    collections ||--o| airtel_entries : "paid in"
    expenses ||--o| airtel_entries : "paid out"
    handovers ||--o{ airtel_entries : "withdrawn cash"
    invoices ||--o{ sales_returns : "returned"
    deliveries ||--o{ sales_returns : "not taken"
    sales_returns ||--o{ sales_return_lines : "lists"
    invoice_lines ||--o{ sales_return_lines : "came back"
    sales_returns ||--o{ stock_movements : "back on the shelf"
    invoices ||--o{ invoice_notes : "adjusted by"
    facilities ||--o{ trips : "sends"
    users ||--o{ trips : "leads"
    trips ||--o{ trip_lines : "carries"
    trip_lines ||--o{ trip_line_batches : "from"
    stock_batches ||--o{ trip_line_batches : "loaded"
    trips ||--o{ trip_sales : "sells"
    trips ||--o{ trip_orders : "delivers"
    trips ||--o{ outbox_problems : "unread sales"
    orders ||--o{ trip_orders : "goes out on"
    customers ||--o{ trip_sales : "buys"
    trip_sales ||--o{ invoices : "makes"
    trip_sales ||--o{ collections : "paid by"
    trips ||--o{ expenses : "spends"
    loading_plans {
        int id PK
        text number "LP-000123"
        text facility_code FK
        int vehicle_id FK
        int driver_id FK
        text status "planned, loaded, out, done"
        timestamptz sent_at
        text delivery_note_no "DN-000123"
        text gate_pass_no "GP-000123"
    }
    loading_plan_items {
        int id PK
        int plan_id FK
        int order_line_id FK
        int pieces "planned"
        int loaded
        int delivered
        text short_reason
        int delivery_id FK
    }
    loading_plan_item_batches {
        int item_id PK
        int batch_id PK
        int pieces
    }
    loading_plan_changes {
        bigint id PK
        int plan_id FK
        bool by_warehouse
        int product_id FK
        int before
        int after
        text reason
        bool seen
    }
    invoices {
        int id PK
        text number UK
        text company_code FK
        int customer_id FK
        text invoice_type
        int plan_id FK
        text lpo_no
        date invoice_date
        date due_date
        bigint subtotal
        numeric vat_rate
        bigint vat
        bigint total
        int salesman_id FK
    }
    invoice_lines {
        int id PK
        int invoice_id FK
        int order_line_id FK
        int plan_item_id FK
        int product_id FK
        int pieces
        int price_per_carton
        bigint amount
    }
    deliveries {
        int id PK
        int plan_id FK
        int customer_id FK
        text received_by
        text signature_file
        text photo_file
        int confirmed_by FK
        timestamptz confirmed_at
    }
    collections {
        int id PK
        text number UK "LV-RCT-000001"
        text company_code FK
        int customer_id FK
        int invoice_id FK
        text mode "cash, bank, airtel"
        bigint amount
        bigint discount
        bigint wht
        text reference
        text proof_file
        text signature_file
        int collected_by FK
        text custody "with, handed, counted, na"
        int handover_id FK
    }
    collection_allocations {
        int id PK
        int collection_id FK
        int invoice_id FK "null = opening balance"
        bigint amount
    }
    expenses {
        int id PK
        text number UK "LV-EXP-000001"
        text company_code FK
        bigint amount
        text description
        text mode "cash, airtel"
        text photo_file
        int spent_by FK
        text custody
        int handover_id FK
    }
    handovers {
        int id PK
        text number UK "HO-000001"
        int handed_by FK
        bigint cash
        bigint withdrawals
        bigint expenses
        bigint net
        text status "handed, counted"
        bigint counted
        bigint difference
        int counted_by FK
    }
    airtel_wallets {
        int id PK
        int holder_id FK,UK
        bigint opening_balance
        boolean active
    }
    airtel_wallet_companies {
        int wallet_id FK
        text company_code PK
    }
    airtel_entries {
        int id PK
        int wallet_id FK
        text company_code FK
        text kind "payment, expense, withdrawal, charge"
        bigint credit
        bigint debit
        text reference
        int collection_id FK
        int expense_id FK
        text status "waiting, ticked, ok"
        text custody
        int handover_id FK
        int created_by FK
    }
    sales_returns {
        int id PK
        text number UK "LV-SR-000001"
        int invoice_id FK
        text kind "delivery, after"
        text reason
        bigint subtotal
        bigint vat
        bigint total
        timestamptz booked_at
        text goods "waiting, received"
    }
    sales_return_lines {
        int id PK
        int return_id FK
        int invoice_line_id FK
        int pieces
        bigint amount
        text condition "good, damaged"
    }
    trips {
        int id PK
        text number UK "TRP-000001"
        text kind "minibus, upcountry"
        text facility_code FK
        text company_code FK "float and expenses"
        date trip_date
        text route
        text_array stops
        int vehicle_id FK
        int driver_id FK
        int leader_id FK
        bigint float_amount
        text float_custody "with, handed, counted, na"
        int float_handover_id FK
        text status "planned, out, closed"
        text gate_pass_no UK
        bigint cash_expected
        bigint cash_counted
        bigint cash_difference
    }
    trip_lines {
        int id PK
        int trip_id FK
        int product_id FK
        int planned
        int loaded
        int sold
        int returned
        int missing
    }
    trip_line_batches {
        int line_id PK
        int batch_id PK
        int pieces
        int sold
    }
    trip_orders {
        int trip_id PK, FK
        int order_id PK, FK
    }
    sales_targets {
        int salesman_id PK, FK
        date month PK "first day"
        bigint amount "before VAT"
        int set_by FK
    }
    outbox_problems {
        int id PK
        int trip_id FK
        bytea file "as it was on the phone"
        text request_id "one per phone send"
        int reported_by FK
        int cleared_by FK "Admin"
        text clear_note
    }
    trip_sales {
        int id PK
        int trip_id FK
        int customer_id FK
        text stop
        text payment "cash, airtel, credit"
        bigint total
        text received_by
        text signature_file
        text proof_file
        timestamptz sold_at
        text request_id
    }
    invoice_notes {
        int id PK
        text number UK "LV-CN-000001"
        int invoice_id FK
        text kind "credit_issued, debit_issued, debit_received, credit_received"
        bigint amount
        text reason
        text their_number
    }
    airtel_checks {
        int id PK
        int wallet_id FK
        bigint actual
        bigint ledger
        bigint difference
        text note
        int checked_by FK
    }
```

| Table | Added in | What it holds |
|---|---|---|
| `facilities` | Phase 0 | The two facilities |
| `companies` | Phase 0 | The four companies and which facility serves each |
| `schema_migrations` | Phase 0 | Which migration files have run |
| `users` | Phase 1 | People who can sign in, with their role and PIN |
| `user_companies` | Phase 1 | Which companies each user can see |
| `sessions` | Phase 1 | Signed-in sessions, until the user signs out |
| `audit_log` | Phase 1 | Who did what and when |
| `app_versions` | Phase 1 | Released versions of the phone app |
| `customers` | Phase 2 | Shops and traders we sell to |
| `customer_companies` | Phase 2 | Which companies sell to each customer, and the payment terms |
| `products` | Phase 2 | Products, each belonging to one company |
| `vehicles` | Phase 2 | Trucks and vans, with their usual driver |
| `opening_balances` | Phase 2 | What each customer owed each company on the start date |
| `number_series` | Phase 2 | The last document number used per company and type |
| `stock_batches` | Phase 3 | Stock of each product, per batch and expiry date |
| `stock_receipts` | Phase 3 | Goods received into the warehouse |
| `stock_counts` | Phase 3 | Stock counts |
| `stock_movements` | Phase 3 | Every change to stock: received, adjusted or counted |
| `stock_adjustment_requests` | 22 Oct answers | Damaged-stock write-offs waiting for a manager's approval |
| `shared_number_series` | Phase 4 | The last number of series shared by all companies, such as orders |
| `orders` | Phase 4 | Customer orders, one customer and one facility each |
| `order_companies` | Phase 4 | The companies on an order, with the customer's LPO number for each |
| `order_lines` | Phase 4 | Products on an order: quantity, typed price and how much was delivered |
| `order_allocations` | Phase 4 | Stock set aside for each order line, per batch |
| `order_events` | Phase 4 | The history of each order |
| `loading_plans` | Phase 5 | One vehicle trip from one warehouse, with its delivery note and gate pass numbers |
| `loading_plan_items` | Phase 5 | Order lines on a plan: pieces planned and pieces loaded |
| `loading_plan_item_batches` | Phase 5 | Which batches each loaded item came from |
| `loading_plan_changes` | Phase 5 | Every quantity change on a plan, with the warehouse's reason |
| `invoices` | Phase 6 | Invoices made for a loaded vehicle: one per customer, company, LPO and invoice type |
| `invoice_lines` | Phase 6 | The products on each invoice, with the loaded quantity and typed price |
| `deliveries` | Phase 6 | The driver's confirmation of each drop: who received it, signature and photo |
| `collections` | Phase 7 | Payments from customers: cash, bank or Airtel Money, with discount, withholding tax, proof and signature |
| `collection_allocations` | Phase 7 | Which invoices (or the opening balance) each payment paid |
| `expenses` | Phase 7 | Money spent for a company, with the bill photo |
| `handovers` | Phase 7 | Cash handed to the cashier and what the cashier counted |
| `airtel_wallets` | Phase 7 | Each sales manager's Airtel Money wallet and its opening balance |
| `airtel_wallet_companies` | Phase 7 | Which companies' Airtel payments go into which wallet |
| `airtel_entries` | Phase 7 | Every movement in a wallet: payments in, expenses, withdrawals and their charges |
| `airtel_checks` | Phase 7 | The holder's checks of the real wallet balance against the app |
| `sales_returns` | Phase 8 | Goods that came back, booked against their invoice |
| `sales_return_lines` | Phase 8 | The products on each return, and whether they came back good or damaged |
| `invoice_notes` | Phase 8 | Credit and debit notes on an invoice, issued or received |
| `trips` | Phase 9 | Minibus routes and upcountry trips: vehicle, leader, float, gate pass and the cash counted at close |
| `trip_lines` | Phase 9 | Products on a trip: planned, loaded, sold, brought back and missing |
| `trip_line_batches` | Phase 9 | Which batches each product was loaded from, and how much of each is sold |
| `trip_sales` | Phase 9 | Each sale at a stop: customer, payment, signature and the phone's time |
| `trip_orders` | Phase 9 | Orders booked ahead that a trip delivers |
| `user_dormant_vehicles` | 24 Oct answers | Vehicles a person does not use, kept at the bottom of their list |
| `sales_targets` | Phase 10 | Each salesman's sales target for a month, in kwacha before VAT |

## Phase 1 notes

- **Phone:** stored as digits only, for example `0991234567`. Must be unique.
- **Roles (`users.role`):** `admin`, `management`, `sales_manager`, `salesman`, `warehouse`, `driver`, `cashier`, `accounts`.
- **Salesmen:** `manager_id` points to the sales manager they work under. It is required for salesmen and empty for everyone else. The rule is checked in code, not by the database.
- **PINs:** stored as scrypt hashes in `pin_hash`, never in plain text.
- **Sessions:** only a sha256 hash of the token is stored in `token_hash`.
- **Audit actions (`audit_log.action`):** `login`, `login_failed`, `logout`, `user_created`, `user_switched_off`, `user_switched_on`, `pin_reset`, `app_version_added`.
- **Failed logins by an unknown phone:** `audit_log.user_id` is empty.
- **App versions (`app_versions`):** one row per platform and version code. Platform is `android` for now.

## Phase 2 notes

- **Customers** can buy from several companies. Each user only sees the companies they work for, and only those companies' payment terms.
- **Cash-only customers** always have 0 payment terms.
- **Who edits lists:** Admin and Accounts edit customers and products; a sales manager can add or edit customers for their own companies only. Only Admin edits company details; vehicles are kept by Admin and both sales managers (24 Oct answers).
- **Opening balances** are entered once by Accounts or Admin. After that only Admin can correct one, and both are written to the audit log (`opening_balance_entered`, `opening_balance_corrected`).
- **Document numbers** look like `LV-SO-000123`: company, document type, then a counter. Taken inside the same transaction as the document, so a cancelled save does not use up a number.
- **Excel import:** download a template, upload it to check (nothing is saved), then upload again to save. Either every row is saved or none.

## Phase 3 notes

- **Pieces:** stock is stored in pieces, the unit a product is sold in (tin, box, sachet). The app shows it as cartons plus loose pieces, using `products.pieces_per_carton`.
- **Facility:** a batch belongs to the facility of its product's company, so the Facility 1 warehouse sees Lucky Ventures and Zoona Trading stock only.
- **Who sees stock:** Admin, Management and Warehouse. Salesmen never see stock (client, 7 Oct). Only Admin and Warehouse change it.
- **Receiving** the same batch number again adds to that batch, but only when the expiry date matches. Expired goods cannot be received.
- **`on_hand`** can never go below 0. Every change also writes a `stock_movements` row, so each batch has a full history.
- **Stock count:** the counted amount replaces what the app had; each difference is a `counted` movement.
- **Short-dated:** batches expiring within 90 days (changeable per request) or already expired. Dates use Malawi time.
- **Audit actions:** `stock_received`, `stock_adjusted`, `stock_counted`.

## Client answers of 22 October

- **Phone:** Malawi numbers stay as `0991234567`. A number from abroad keeps its country code, for example `+971501234567` (a manager in the UAE). `991234567` without the 0 is also accepted.
- **Salesmen under two managers:** `user_companies.manager_id` holds the sales manager for each company. Mr. Rajjab works for all four companies, under one manager for LV and ZT and another for ZF and GL. `users.manager_id` stays as the main manager. Either manager sees the salesman and can reset his PIN.
- **Customer invoice details:** `customers.address`, `city`, `tpin` and `reg_no` print on the VAT invoice. They are optional columns at the end of the customer import, so older files still import.
- **Damaged stock:** a warehouse write-off for `damaged` waits in `stock_adjustment_requests` until Admin or Management approves it; stock changes only on approval. An admin's own write-off applies at once. Audit actions: `stock_adjustment_requested`, `stock_adjustment_approved`, `stock_adjustment_rejected`.

## Phase 4 notes

- **One order** is for one customer and the companies of one facility, so it can mix Lucky Ventures and Zoona Trading products, but never products from both warehouses. Invoices (Phase 6) are split per company.
- **Numbers:** orders use one shared series, `SO-000001`, from `shared_number_series` (client, 22 Oct: no separate numbering per company).
- **Quantities** are pieces like stock; the app types cartons plus loose pieces. **Prices** are typed per carton on every order, in whole kwacha, without VAT. A line's amount is price × pieces ÷ pieces per carton, rounded to the kwacha. There is no discount.
- **VAT or plain invoice:** picked on each order (`orders.invoice_type`). A VAT order adds `companies.vat_rate` (17.5% when empty) per company. The choice is saved in `customers.last_invoice_type` and offered next time.
- **LPO:** one number per company on the order. Required when the customer is marked "needs LPO".
- **Stock reservation:** a new order sets stock aside at once, oldest expiry first, in `order_allocations`. Free stock = `on_hand` minus reserved. A batch must have enough shelf life left for the customer's channel: supermarket 90 days, upcountry 60, wholesaler and minibus 30 (from the prototype; still to confirm with the client). If there is not enough, the order is still booked and shows what is short; "reserve again" picks up new stock.
- **Statuses:** `open` (booked, stock reserved), `hold` (stays reserved, cannot be loaded), `cancelled` (stock released), `done` (closed by a manager after part delivery; the rest is released). Phase 5 adds loading states.
- **Who:** a salesman books and sees only their own orders, and can change or cancel them while open. A sales manager or admin books for their companies, can name the salesman, and can change, hold, release, cancel and close any order of their companies. Management, warehouse, cashier and accounts see orders of their companies. Drivers do not see orders.
- **Owes:** the booking screen shows what the customer owes each company. Until invoices and payments exist (Phase 6) this is the opening balance. There is no credit check (client, 7 Oct).
- **Audit actions:** `order_booked`, `order_changed`, `order_held`, `order_released`, `order_cancelled`, `order_closed`.

## Phase 5 notes

- **A loading plan** is one vehicle from one warehouse. It can carry many customers' orders from both companies of that warehouse. Plans are numbered `LP-000001`; loading makes one delivery note `DN-000001` and one gate pass `GP-000001` per plan.
- **Statuses:** `planned` (being made; `sent_at` is set once it goes to the warehouse), `loaded` (stock has left the shelves), `out` (the vehicle left the gate), `done` (every drop confirmed, Phase 6).
- **Who:** Admin, Management and sales managers make and send plans for their warehouse. Admin and Warehouse change quantities, load and let the vehicle out. A driver sees only their own plans once loaded. Salesmen never see plans. Accounts and Cashier can look.
- **Quantities** are pieces. A plan never carries more of an order line than is still open: ordered − delivered − on a vehicle − on other plans. Orders on hold cannot be added. Putting an order on hold, or cancelling or closing it, takes it off plans that are not loaded yet.
- **Warehouse changes** are per product and need a reason. Less comes off the last orders first. More fills the plan's orders up to what they still have open; anything that does not fit is reported back. Changes after sending show as new to the other side until seen (`loading_plan_changes.seen`).
- **Loading** takes stock off the shelves into transit: first from the batches set aside for that order, then from free stock with enough shelf life. What is not on the shelves is not loaded and stays open on the order.
- **Stock is reduced only on delivery** (client, 7 Oct: reserved on order, in transit on loading, deducted on delivery). Loaded pieces stay in `stock_batches.on_hand` and are counted in `stock_batches.in_transit` until Phase 6 confirms the delivery. What is on the shelf is `on_hand - in_transit`; adjustments and stock counts work on the shelf only. `GET /stock` shows `inTransit`, and free = on hand − in transit − reserved.
- **`order_lines.on_vehicle`** counts pieces on a vehicle and not yet delivered; the order's reservation is worked out again for what is left.
- **Order stage:** orders now show a `stage` worked out from their lines: `open`, `planned`, `on_vehicle` or `part_delivered` (or the order's status when it is on hold, done or cancelled). An order cannot drop below what is planned, loaded or delivered, and cannot be cancelled or closed while part of it is on a vehicle.
- **Plans list:** `GET /loading-plans` also gives `cartons` and `loosePieces` for each plan, worked out per product with its own `pieces_per_carton` and then added up (planned pieces while planned, loaded pieces after), so the phone can show cartons when a plan mixes carton sizes.
- **Audit actions:** `loading_plan_started`, `loading_plan_changed`, `loading_plan_sent`, `loading_plan_changed_by_warehouse`, `loading_plan_loaded`, `loading_plan_out`, `loading_plan_deleted`.

## Phase 6 notes

- **Invoices are made after loading, before delivery** (client, 16 Oct), by Admin, sales managers or Accounts, from the plan (`POST /loading-plans/:id/invoices`). There is one invoice per customer, company, LPO number and invoice type, for what actually went on the vehicle, at the prices typed on the order. Each user makes the invoices of their own companies.
- **Numbers** run per company: `LV-INV-000001`. VAT and plain invoices share the series.
- **VAT** is the company's `vat_rate` (17.5% when not set) on the prices typed without VAT, rounded to whole kwacha. A plain invoice has VAT 0.
- **Due date** is the invoice date plus the customer's payment terms for that company; cash-only customers are due the same day.
- **Printing:** `GET /invoices/:id/pdf`. The VAT invoice follows the client's sample: logo, company name, address, phone and TPIN on top, then the customer with Reg No and TPIN, LPO, vehicle and delivery note, the lines, Sub Total, VAT and Total. The plain invoice leaves out the company name, logo and TPINs. Logos are read from the `branding` folder (`BRANDING_DIR`), named by company code (`LV.jpg`); they are kept out of the repo.
- **Delivery note and gate pass:** `GET /loading-plans/:id/delivery-note.pdf` and `/gate-pass.pdf` show the warehouse, vehicle, driver and products only, never company or customer names.
- **Invoice register:** `GET /invoices`, filtered by company, customer, plan, type, dates or a search. Salesmen see their own invoices, drivers those of their vehicles, Warehouse none.
- **Delivery:** once the vehicle is out and the customer's invoices are made, the driver (or Admin) confirms each drop: every product with what was handed over, the receiver's name and their signature, and a photo if wanted. Less than loaded needs a reason. A drop refused completely needs a reason but no signature. Signatures and photos are saved in the `uploads` folder (`UPLOAD_DIR`), never in the database or the repo.
- **Stock on delivery:** what was handed over leaves `on_hand` (oldest batch on the vehicle first, movement kind `delivered`); everything loaded leaves `in_transit`, so what came back is on the shelf again. On the order, `on_vehicle` goes down by what was loaded and `delivered` up by what was handed over; what was not taken is open again and stock is set aside for it. An order is `done` once all of it is delivered, and the plan is `done` once every drop is confirmed.
- **Shortages** do not change the invoice. The shortfall is credited with a sales return in Phase 8.
- **What a customer owes**, shown when booking, is now the opening balance plus their invoices. Payments come off in Phase 7.
- **Audit actions:** `invoices_made`, `delivery_confirmed`.

## Phase 7 notes

- **Payments** (`POST /collections`) are recorded by salesmen and sales managers in the field, and by Accounts, the cashier or Admin at the office. One payment is for one customer and one company, in cash, bank transfer or Airtel Money. Receipts are numbered per company (`LV-RCT-000001`). `GET /collections/customer/:id` gives what the customer owes each company and the invoices still open.
- **Where the money goes:** first on the invoice picked, then on what is left of the opening balance, then on the oldest invoices. Anything over stays on account and shows as a balance below 0. `collection_allocations` keeps the split; an allocation with no invoice is the opening balance.
- **Discount and withholding tax** are typed at collection and come off the invoice picked; they cannot be more than it owes. The WHT certificate number is kept.
- **Proof and signature:** a bank or Airtel payment needs a photo of the proof, and Airtel needs its reference. The customer signs for every payment (client, 17 Oct), except a bank or Airtel payment recorded at the office by Admin, Accounts or the cashier, where the customer is not there.
- **What is owed** is the opening balance plus invoices, less payments, discounts and withholding tax. The invoice register and detail now show each invoice's `balance`, and the booking screen uses the same figure.
- **Expenses** (`POST /expenses`): Admin, sales managers and salesmen record money spent for a company, with an optional bill photo. Numbered per company (`LV-EXP-000001`). Cash expenses come off the next handover; Airtel Money expenses do not.
- **Cash custody:** cash stays the collector's responsibility (`custody = 'with'`) until the cashier counts it. `GET /cash/mine` shows what a person holds per company. `POST /handovers` hands all of it over in one total (cash less cash expenses), numbered `HO-000001` across companies; behind the total each company's money is kept apart. The cashier (or Admin, never the person who handed it over) counts it with `POST /handovers/:id/count`; a difference needs a note and is kept. Cash the cashier takes directly is counted at once.
- **Who sees what:** Admin, Management, the cashier and Accounts see every payment, expense and handover of their companies. Salesmen see their own payments; salesmen and sales managers see their own expenses and handovers. Warehouse and drivers see none.
- **Airtel Money wallets** (migration 019): Admin gives each sales manager one wallet for their companies (`POST /airtel/wallets`, with the opening balance); a company has at most one wallet. An Airtel payment adds a `waiting` entry to its company's wallet, and the holder ticks it (`POST /airtel/entries/:id/tick`) once it shows on their phone. Only the holder can pay an expense from the wallet; anyone else is told to pay in cash. The holder takes money out with `POST /airtel/wallets/:id/withdrawals` (the Airtel charge is recorded as an expense too); that money is cash in their hand and goes into their next handover, so handovers are now cash plus withdrawals less cash expenses. `POST /airtel/wallets/:id/checks` records the real balance against the app's, and keeps any difference. Admin, Management, the cashier and Accounts see the wallets of their companies; a sales manager sees their own.
- **Saving twice** (migration 020): the phone sends a `requestId` with each payment and expense form. If the same person sends the same id again (a retry after the answer was lost on a slow line), the server returns the first record with status 200 instead of making a second receipt. The wallet detail also says `canTakeOut`, true only for the holder, since admin may tick and check a wallet but not take money out of it.
- **Later:** cancelling a wrong payment, credit notes, the customer ledger and putting money on account onto later invoices came in Phase 8.
- **Audit actions:** `payment_collected`, `expense_recorded`, `cash_handed_over`, `cash_counted`, `airtel_wallet_set_up`, `airtel_wallet_changed`, `airtel_payment_ticked`, `airtel_withdrawal`, `airtel_wallet_checked`.

## Phase 8 notes

- **Sales returns** are numbered per company (`LV-SR-000001`), with VAT at the invoice's rate. There are two kinds:
  - **Not taken at delivery:** when the driver confirms a drop short, the server makes one return per invoice for what was not taken. The goods never left the vehicle and are on the shelf again already (Phase 6), so the return shows as received. The sales team books it (`POST /returns/:id/book`) to take it off the invoice; until then the invoice still owes the full amount.
  - **Given back after delivery:** `POST /invoices/:id/returns` with the products, cartons and pieces, and a reason from the prototype's list. At most what was delivered less what already came back. It is booked when it is saved, so the invoice and what the customer owes go down at once. The warehouse then receives the goods (`POST /returns/:id/receive`), marking any damaged lines: good goods go back on the shelf in the batches they were delivered from (movement kind `returned`); damaged ones do not.
- **Credit and debit notes** (`POST /invoices/:id/notes`) are mostly for a price difference; stock does not change. Four kinds, each its own series: credit note issued `LV-CN-` (owes less), debit note issued `LV-DN-` (owes more), debit note received from the customer `LV-RDN-` (owes less) and credit note received `LV-RCN-` (owes more). A received note needs the customer's own number. The amount typed is what the customer owes less or more, VAT included.
- **What is owed** is now the opening balance plus invoices, less payments, discounts, withholding tax and booked returns, plus or minus notes. The invoice detail lists its returns and notes, and per line what can still come back (`canReturn`).
- **Customer ledger** (`GET /customers/:id/ledger?company=LV&from=&to=`): every invoice, payment, discount, withholding tax, booked return and note with that company, oldest first, with the balance after each; movements before `from` make up the opening line. `GET /customers/:id/statement.pdf` prints the same as a statement of account with the company's logo.
- **Who:** Admin, Management, sales managers and Accounts book returns and make notes. The warehouse (and Admin) receives returned goods and sees returns without their money. Salesmen see the returns and notes of their own invoices and the ledgers of their companies' customers, but cannot change them.
- **Saving twice:** returns and notes take a `requestId` like payments do.
- **Cancelling a payment** (migration 022): Accounts or Admin cancel a payment recorded by mistake with `POST /collections/:id/cancel` and a reason. It stays on record but no longer counts: what it paid is open again, its cash leaves the collector's hand, and a waiting Airtel payment leaves the wallet. Once the cash is handed to the cashier, or the Airtel payment is ticked, the money is real and it cannot be cancelled; a debit note corrects the customer instead. Cash the cashier took directly can still be cancelled. The ledger shows the payment and its cancellation.
- **Money on account:** what a customer paid beyond what they owed goes on what they owe later, automatically: when new invoices are made, and when a debit note (or a credit note received from them) makes them owe more. It goes on the opening balance first, then the oldest invoices.
- **Audit actions:** `sales_return_booked`, `returned_goods_received`, `invoice_note_made`, `payment_cancelled`.

## Phase 9 notes

- **Trips** (migration 023) are minibus routes and weekly upcountry trips. A manager plans one with `POST /trips`: minibus or upcountry, the warehouse, date, route and stops, vehicle, driver, the leader (the salesman or sales manager who sells and holds the cash), the float and the cartons of each product to take. Numbered in one series for both companies of the warehouse (`TRP-000001`). It can be changed (`PUT /trips/:id`) or deleted until it is loaded.
- **Loading** (`POST /trips/:id/load`, warehouse): only free stock goes on, never what is set aside for orders, oldest expiry first with enough shelf life for the channel (minibus 30 days, upcountry 60). The warehouse can type less or more than planned; what is not on the shelves is listed as not loaded. The vehicle leaves at once with a gate pass (`GP-`), the stock is in transit until sold, and the float becomes cash the leader holds.
- **Selling at a stop** (`POST /trips/:id/sales`, the leader only): one customer, the cartons and pieces with a typed price per carton, VAT or plain (the customer's last choice unless picked), paid by cash, Airtel Money or on account, and always signed. It makes one invoice per company, dated the day of the sale, due at once unless on account. Cash and Airtel Money are recorded as payments of each invoice (`RCT-`); Airtel goes into the company's wallet waiting to be ticked. Sold stock leaves the vehicle and the books, oldest expiry first. Cash-only customers cannot buy on account; no more can be sold than is left on the vehicle.
- **Trip expenses** (`POST /trips/:id/expenses`, the leader): fuel, lodging and so on, in cash from the float, with a bill photo. They are normal expenses linked to the trip (`EXP-`), so they come off the cash handed over.
- **Offline:** the phone keeps `GET /trips/:id/pack` (the trip, its channel's customers with what they owe and their last prices, and the expense kinds). Every sale and expense carries a `requestId` made on the phone and the sale its `soldAt` time. `POST /trips/:id/sync` sends what was saved offline in one go; each item is saved on its own and its result reported, and anything sent again comes back as a repeat. Invoice numbers are given when a sale reaches the server, so the phone sends everything before closing.
- **Closing** (`POST /trips/:id/close`, the leader or a sales manager or Admin): the cartons that came back of each product and the cash counted. What came back goes on the shelf again; what is neither sold nor back is missing and comes off the books (adjusted, reason lost). The cash expected is cash sales plus the float less cash expenses; a cash difference or missing stock needs a note. The cash, float included, stays with the leader until handed to the cashier, where handovers now show `floats`.
- **Invoices** from a trip have no order behind them (`invoice_lines.order_line_id` is empty); the invoice shows the trip, the stop and the vehicle, and the ledger says "trip TRP-000001". Goods from a trip invoice that are given back later go into the batches they were sold from.
- **Who:** Admin, Management and sales managers plan; the warehouse loads and sees no money; salesmen see only the trips they lead; drivers see their own trips once loaded.
- **Orders on a trip** (Phase 9 part 2): a trip can carry orders booked ahead (`trip_orders`, `orderIds` on `POST`/`PUT /trips`; `GET /trips/open-orders?facility=F1` lists the ones it may take: open, from that warehouse, with something left to deliver and on no loading plan, vehicle or other trip). While the trip is planned their goods count as planned, so no loading plan takes them, and putting an order on hold takes it off. At loading the stock set aside for them is let go and loaded first, oldest expiry first, and goes on `order_lines.on_vehicle`; what did not fit is set aside again. Each trip line shows `forOrders` and `forSale`, and a sale to anyone else can only take `forSale`. The leader delivers an order with a sale that names `orderId`: the order's prices, invoice type, LPO and salesman are used, `invoice_lines.order_line_id` is set and the order is part delivered or done. At close, what an order did not get comes back like the rest of the stock and is set aside for the order again, so it can go out on another vehicle.
- **Audit actions:** `trip_planned`, `trip_changed`, `trip_deleted`, `trip_loaded`, `trip_sale`, `trip_expense`, `trip_closed`.

## Client answers of 24 October

- **VAT exempt products:** `products.vat_exempt`. VAT is worked out only on the lines that are not exempt, rounded once per invoice (`vatOn` in `orders.ts`). Each invoice line keeps `invoice_lines.vat_exempt` as it was when invoiced, so returns use the same rule; a VAT invoice prints "(VAT exempt)" after such a line. The product import has an optional `VAT exempt` column (Yes or No).
- **Delete** (`DELETE /products/:id`, `/customers/:id`, `/vehicles/:id`, `/users/:id`): a row nothing refers to is removed. One with history (an order, invoice, trip, opening balance or audit entry) gets `deleted_at` instead: it is switched off, hidden from every list and screen, and cannot be switched on again, while old documents keep its name. The answer is `{ deleted: true, keptForHistory }`. Codes, customer names, plates and login phones only need to be unique among rows that are not deleted, so they can be used again. Opening balances no longer vanish with their customer.
- **Users:** `PUT /users/:id` changes the name, phone, job, companies or sales managers; only what is sent changes. A sales manager edits and deletes their own salesmen only. Deleting a user ends their login at once and clears them as a vehicle's usual driver. A sales manager with salesmen cannot be deleted, moved to another job or lose a company their salesmen use until the salesmen are moved.
- **Vehicles** are shared by all four companies. Both sales managers, as well as Admin, add, edit and delete them. Each person marks the ones they do not use with `PUT /vehicles/:id/dormant { dormant }`; `GET /vehicles` gives `dormant` for the caller and lists those last.
- **Invoices** carry no bank details. Audit actions: `product_deleted`, `customer_deleted`, `vehicle_deleted`, `user_updated`, `user_deleted`.

## Phase 10 notes

- **Reports** (`src/reports/`): 53 reports in nine groups (sales, orders, deliveries, invoices, what customers owe, cash, stock, trips and checks), from the client's list of 7 October. `GET /reports` gives the ones the person may open, by group, with the filters each takes (`dates`, `company`, `person`, `customer`, `channel`, `product`), those that must be picked, what the person filter is called (salesman, collected by, leader) and where the dates start by default; plus the staff of the person's companies to pick from. `GET /reports/:key?from=&to=&company=&person=&customer=&channel=&product=` runs one: `{ title, picked, columns, rows, totals, truncated }`. Each column has a kind: `text`, `money` (whole kwacha), `int`, `percent`, `date` (YYYY-MM-DD) or `qty` (already written as cartons and pieces). `&format=xlsx` gives an Excel file and `&format=pdf` a landscape A4 printout with the filters picked written at the top. At most 10,000 rows (`truncated`).
- **Who sees what:** every report covers only the person's companies. A salesman sees only his own figures: his sales, orders, customers, collections and trips, whatever he asks for; he picks no person, and sees no stock reports. The warehouse sees the stock reports and the trip loads and returns, never money. Cashiers see invoices, what is owed and cash. User activity is for Admin and Management.
- **What the reports count:** sales are invoices, by invoice date, before VAT unless the column says total. Cancelled invoices are those taken back in full by booked returns and credit notes, as invoices are never deleted. Cash invoices are those due on the day they were made. The route is the trip's route, or the customer's area for other sales. Aging sorts what is still to pay on each invoice by days past its due date. Cash in hand is what each person holds and has not handed over, as on their cash screen. Cancelled payments are left out.
- **Targets** (migration 026): `sales_targets`, one per salesman per month, in kwacha before VAT. `GET /targets?month=2026-10` lists the salesmen the person may set (Admin and Management: all of their companies; a sales manager: his own) with their target; `PUT /targets { salesmanId, month, amount }` sets it, and `amount: null` takes it away. The target report sets each month's target against sales.
- **Low-stock level:** `products.low_stock_cartons`, set with the product (`lowStockCartons`, empty means none). A product with less free to sell shows on the low stock report.
- **Credit limit:** `customer_companies.credit_limit`, per company, set with the customer (`companies[].creditLimit`, empty means none; left out on an edit, it stays). It shows on the reports only and never stops an order.
- **Audit actions:** `target_set`.
- **Office website** (Phase 10 part 2): `server/office/` holds plain HTML, CSS and JavaScript with no build step, served at `/office/` by the same server (so at `PUBLIC_URL/office/`). Staff sign in with their phone and PIN as in the app (`POST /auth/login`); the token stays in that browser until they sign out or it stops working. It shows the dashboard, every report they may open (with Excel and PDF), and the lists of customers, products, vehicles and, for Admin, Management and sales managers, staff. Changes are still made in the phone app. The pages are sent with a strict content security policy: only the server's own files run.
- **Dashboard** (`GET /dashboard`): the numbers on the website's first page, each taken from a report so it matches what that report shows the same person: sales today and this month, orders still to deliver, what customers owe and how much is late, cash not yet handed over, handovers to count, products low on stock, write-offs to approve and returns to book. Each tile names the report and dates it opens. `salesByDay` gives this month's sales for the chart. A job sees only the tiles of the reports it may open.
- **Search** (Phase 10 part 3, `GET /search?q=`, at least 2 letters): one box that finds customers (name, area, town or phone typed any way), orders and invoices (number, customer or LPO number), vehicles and delivery notes (plan, delivery note or gate pass number, plate), trips (number, route, gate pass, plate), returns (number, customer or invoice), products (code or name) and, for Admin, Management and sales managers, staff (name or phone). Each group shows only what the person may already open (a salesman: his own orders and invoices; drivers: no customers), at most 8 items with the full count. Each item has an `id`, a `title` and a one-line `detail` already written in plain words. Products show what is free to sell only to those who see stock.
- **Alerts** (`GET /alerts`): what needs someone's attention now, worst first (`bad`, `warn`, `info`), each with a `title`, a `detail`, where the phone app opens it (`screen` and `id`) and the report the website opens (`report`). By job: plans changed after sending (warehouse) or changed by the warehouse (planners); loaded vehicles without invoices; vehicles out since before today; orders from before today not on a vehicle; returns to book and goods coming back; Airtel Money payments not ticked; customers late paying (each customer and company, biggest first, at most 15 and then one summing up the rest); customers over their credit limit; cash not yet handed over (from the cash in hand report); handovers to count; low stock (from the low stock report); expired batches still in stock; batches expiring within 90 days; stock changes to approve; and unread trip sales.
- **Unread trip sales** (migration 027): when a phone cannot read the trip sales it saved, the trip leader's phone sends the file (`POST /trips/:id/outbox-problems { requestId, file (base64), fileName, note }`; sent twice, it is one problem). Admin and Management see them (`GET /outbox-problems`, `GET /outbox-problems/:id`, the file at `/outbox-problems/:id/file`); Admin enters anything missing by hand and clears it with a note (`POST /outbox-problems/:id/clear { note }`). The trip shows `outboxProblems` and `openOutboxProblems`, and cannot close while one is open (`outbox_problem`). The office website has a page for them.
- **Audit actions:** `outbox_problem_reported`, `outbox_problem_cleared`.
