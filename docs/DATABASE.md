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
    customers ||--o{ customer_companies : "buys from"
    companies ||--o{ customer_companies : "sells to"
    companies ||--o{ products : "sells"
    facilities ||--o{ vehicles : "based at"
    users ||--o{ vehicles : "usual driver"
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
    }
    products {
        int id PK
        text code UK
        text company_code FK
        text brand
        text name
        text pack_size
        int pieces_per_carton
        boolean active
    }
    vehicles {
        int id PK
        text plate UK
        text description
        text facility_code FK
        int default_driver_id FK
        boolean active
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
- **Who edits lists:** Admin and Accounts edit customers and products; a sales manager can add or edit customers for their own companies only. Only Admin edits vehicles and company details.
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
- **Not yet:** cancelling a wrong payment, credit notes and the customer ledger card come in Phase 8; money on account is not put on later invoices automatically until then.
- **Audit actions:** `payment_collected`, `expense_recorded`, `cash_handed_over`, `cash_counted`, `airtel_wallet_set_up`, `airtel_wallet_changed`, `airtel_payment_ticked`, `airtel_withdrawal`, `airtel_wallet_checked`.
