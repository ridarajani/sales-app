# Development plan

Flow chart: https://claude.ai/artifact/XuctvwHNTQw9HU7KCBeG9V

Each phase ends with something Rida can try before the next one starts. In every phase the server, phone screens and tests are built together, and each phase goes through a pull request.

## Roles

| Role | What they do |
|---|---|
| Admin | Adds users, resets PINs, changes settings |
| Management | Sees all four companies, approves, reads every report |
| Sales manager | Books orders, makes loading plans, records collections for their facility (Mr. Abbas: LV and ZT; Mr. Kashif: ZF and GL); adds their salesmen |
| Salesman | Works under a sales manager with their own login; makes sales and collects payments for that manager's companies |
| Warehouse | Receives stock, picks and loads vehicles; one login per facility |
| Driver | Delivers, takes signature and photo, records shortages |
| Cashier | Counts and confirms the cash handed over |
| Accounts | Invoices, credit and debit notes, customer ledger, reports |

Each user only sees their own companies. The client confirmed on 17 October 2026 that cashier and accounts are different people and that salesmen get their own logins.

Other client decisions of 17 October 2026: invoices are made after the vehicle is loaded; prices are typed on each order (no prefill); the customer signs for every delivery and every payment collection.

## How staff get the app

The app runs on Android and iPhone from one codebase (Kotlin Multiplatform). Staff use Android; management uses iPhone (client, 20 October 2026).


- **Android, private download page:** the app stays off the Play Store. Our server hosts a download page, only for staff, where they get the app.
- **iPhone, TestFlight:** management installs the app through TestFlight, Apple's private test channel, so it stays off the public App Store. TestFlight shows each new version as an update.
- **Automatic updates (Android):** each time the app opens, it checks the server for a newer version. If there is one, it downloads it and asks the user to tap Install.
- **Only staff can sign in:** someone the admin hasn't added can install the app, but can't use it.

## Phases

| Phase | What it covers |
|---|---|
| 0. Setup | Server, database and an empty phone app for Android and iPhone |
| 1. Login, users and roles | PIN sign-in for the eight roles; add and switch off users; audit log; private download page (not on Play Store); in-app update check, user taps Install |
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
