# Development plan

Flow chart: https://claude.ai/artifact/XuctvwHNTQw9HU7KCBeG9V

Each phase ends with something Rida can try before the next one starts. In every phase the server, phone screens and tests are built together, and each phase goes through a pull request.

## Roles

| Role | What they do |
|---|---|
| Admin | Adds users, resets PINs, changes settings |
| Management | Sees all four companies, approves, reads every report |
| Sales manager | Books orders, makes loading plans, records collections for their facility (Mr. Abbas: LV and ZT; Mr. Kashif: ZF and GL) |
| Warehouse | Receives stock, picks and loads vehicles; one login per facility |
| Driver | Delivers, takes signature and photo, records shortages |
| Cashier | Counts and confirms the cash handed over |
| Accounts | Invoices, credit and debit notes, customer ledger, reports |

Each user only sees their own companies. Still to confirm with the client: whether cashier and accounts are one person, and whether salesmen under the sales managers need their own logins.

## Phases

| Phase | What it covers |
|---|---|
| 0. Setup | Server, database and an empty Android app |
| 1. Login, users and roles | PIN sign-in for the seven roles; add and switch off users; audit log; private download page (not on Play Store); in-app update check, user taps Install |
| 2. Basic lists | Companies (TPIN, VAT), customers, products, vehicles, drivers; Excel templates and import; opening balances; document numbering |
| 3. Stock | Receive stock with batch and expiry; stock per facility; short-dated alerts; adjustments and stock count |
| 4. Orders | Typed prices, one LPO per company, both companies of a facility on one order, stock held oldest expiry first, change or cancel |
| 5. Loading plans and warehouse | Plan the vehicle, send to warehouse, pick, load, delivery note, gate pass |
| 6. Invoices and delivery | Invoices per company and LPO from the loading plan; driver delivery with signature, photo, refusal reason; shortage starts a sales return; printing |
| 7. Collections and cashier | Cash, bank, Airtel Money with proof photo; discount at collection; WHT; expenses off the handover; cashier confirms |
| 8. Returns, notes and ledger | Sales returns, credit and debit notes, customer ledger and statements |
| 9. Trips | Minibus and upcountry: trip loading, cash float, sales at stops, offline with sync, expenses, trip close |
| 10. Reports and office website | Reports with Excel and PDF export, website for management and accounts, search, alerts |
| 11. Go-live | Server online with daily backups, client's real lists and opening balances, client testing, training, staff install from the download page |

The agreed behaviour is the clickable prototype (`index.html`) plus the client's brief of 16 October 2026.
