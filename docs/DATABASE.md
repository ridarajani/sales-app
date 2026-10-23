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
- **Loading** takes stock off the shelves (`stock_movements.kind = 'loaded'`): first from the batches set aside for that order, then from free stock with enough shelf life. What is not on the shelves is not loaded and stays open on the order. `order_lines.on_vehicle` counts pieces on a vehicle and not yet delivered; the order's reservation is worked out again for what is left.
- **Order stage:** orders now show a `stage` worked out from their lines: `open`, `planned`, `on_vehicle` or `part_delivered` (or the order's status when it is on hold, done or cancelled). An order cannot drop below what is planned, loaded or delivered, and cannot be cancelled or closed while part of it is on a vehicle.
- **Audit actions:** `loading_plan_started`, `loading_plan_changed`, `loading_plan_sent`, `loading_plan_changed_by_warehouse`, `loading_plan_loaded`, `loading_plan_out`, `loading_plan_deleted`.
