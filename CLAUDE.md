# Sales and Distribution App

In-house sales and distribution app for four companies in Lilongwe, Malawi: Lucky Ventures (LV) and Zoona Trading (ZT) share Facility 1; Zangafee (ZF) and Greenland (GL) share Facility 2.

## Layout

- `index.html`: the clickable prototype, served on GitHub Pages. Never change or move it. It is the agreed behaviour, together with the client's brief of 16 October 2026.
- `server/`: Node.js 22, TypeScript, Fastify, PostgreSQL. Database changes are numbered SQL files in `server/src/db/migrations`, each run once.
- `mobile/`: one phone app for Android and iPhone (Kotlin Multiplatform, Compose Multiplatform). Screens live in `mobile/shared/src/commonMain`; `androidApp` and `iosApp` are thin shells.
- `docs/PLAN.md`: phases 0 to 11, the eight roles, and how staff get the app. Work one phase at a time, each in its own pull request.

## Commands

- Server: `cd server && npm install && npm run seed && npm test` (needs PostgreSQL with `sales_dev` and `sales_test` databases).
- Android: `cd mobile && ./gradlew :androidApp:assembleDebug`
- iPhone: open `mobile/iosApp/iosApp.xcodeproj` in Xcode; the Apple team ID goes in `mobile/iosApp/Configuration/Config.xcconfig`.

## Rules

- Users are not tech savvy: big buttons, plain words, few fields, one main action per screen, same look as the prototype (burgundy, gold, warm white).
- Each user only sees their own companies. Roles: Admin, Management, Sales manager, Salesman, Warehouse, Driver, Cashier, Accounts.
- Prices are typed on each order; the customer signs for every delivery and every payment collection.
- Money is whole Malawi kwacha (MWK), shown with thousands separators.
- The app is not on Google Play or the App Store: Android installs from a private download page with an in-app update check; iPhone uses TestFlight.
- This repo is public: never commit client data, TPINs, phone numbers, passwords or keys.
