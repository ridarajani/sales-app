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

- Users are not tech savvy: clear buttons, plain words, few fields, one main action per screen, same look as the prototype (burgundy, gold, warm white).
- Each user only sees their own companies. Roles: Admin, Management, Sales manager, Salesman, Warehouse, Driver, Cashier, Accounts.
- Prices are typed on each order; the customer signs for every delivery and every payment collection.
- Money is whole Malawi kwacha (MWK), shown with thousands separators.
- The app is not on Google Play or the App Store: Android installs from a private download page with an in-app update check; iPhone uses TestFlight.
- This repo is public: never commit client data, TPINs, phone numbers, passwords or keys.

### Screen style (decided by Rida, 20 October 2026)

Future phases must follow these.

- Every screen except Home has one top bar: 56dp, transparent over the warm-white page (no fill, no line below), a small round "<" back button on the left (32dp circle, thin burgundy border, no label, 44dp tap target), and the title centred in 18sp semi-bold.
- Buttons are compact with 12dp rounded corners: main action 48dp burgundy and full width, second choices 44dp with a thin grey outline, dangerous ones (switch off, sign out) 44dp with a red outline.
- People show as a round avatar with their initials; jobs and states show as small pills (11sp, job gold, off red, on green).
- List rows are white cards: 14dp corners, thin border, 14dp padding, avatar on the left; beside it the job pill (and an Off pill) on top, the name under it, then companies as coloured dot plus name.
- Home has no top bar: greeting, first name and job pill on the left, avatar on the right. Tapping the avatar opens a bottom sheet with full name, job pill and Sign out only. Company names never go in the header or the sheet.
- Home tiles are compact white cards (min 104dp) with a small icon chip, a 15sp name and a one-line 12sp sub-line; the first tile is burgundy. Two columns, 10dp gaps.
- Form labels are 13sp grey upper-case; fields are at least 48dp tall with 12dp corners; choice buttons are 44dp with a thin border, and the chosen one gets a burgundy border and soft pink fill.
- On a form, the main action is a PrimaryButton at the bottom.
- Text is never smaller than 12sp except the small pills (11sp): labels 13-14sp, body 15-16sp. Every tap target is at least 44dp.
- PINs are typed with the phone's own number keyboard (no custom keypad); the field opens the keyboard by itself.
- When two actions belong together (e.g. Reset PIN and Switch off) put them side by side in one row, half width each.
