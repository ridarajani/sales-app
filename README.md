# Sales and Distribution App

In-house sales and distribution app for Lucky Ventures, Zoona Trading, Zangafee and Greenland.

- `index.html` is the clickable prototype, served on GitHub Pages.
- `server/` is the server and database (Node.js, TypeScript, PostgreSQL).
- `mobile/` is the phone app for Android and iPhone (Kotlin Multiplatform, Compose Multiplatform). The screens live in `mobile/shared`.
- `docs/PLAN.md` is the development plan, phase by phase.

## Run the server

Needs Node.js 22 and PostgreSQL 16.

```sh
cd server
cp .env.example .env      # then set DATABASE_URL
npm install
npm run seed              # creates the tables and the four companies
npm run dev               # http://localhost:3000/health
npm test                  # uses the sales_test database
```

Seeding also creates the first admin login. Its placeholder phone is `0990000000` and its PIN is `1234`. These are not real values. Before go-live, put your real phone and PIN in `.env` as `BOOTSTRAP_ADMIN_PHONE` and `BOOTSTRAP_ADMIN_PIN`. The server refuses the placeholder values in production.

Two more keys in `.env`:

- `PUBLIC_URL` is the address phones use to reach the server, for example `http://192.168.1.10:3000`. The download links use it.
- `APK_DIR` is the folder with the Android app files (`.apk`). The default is `server/apk`.

## Run the phone app

- **Android:** open the `mobile` folder in Android Studio and run `androidApp`.
- **iPhone:** on a Mac, open `mobile/iosApp/iosApp.xcodeproj` in Xcode and press Run. To install on a real iPhone or upload to TestFlight, set your Apple team ID in `mobile/iosApp/Configuration/Config.xcconfig`.

The app is not published on Google Play or the App Store. Android phones install it from a private download page; iPhones install it through TestFlight.

Server address:

- The Android emulator reaches the server at `http://10.0.2.2:3000`.
- The iPhone simulator reaches the server at `http://localhost:3000`.
- On a real phone, tap **Server address** on the login screen and type the Mac's address, for example `http://192.168.1.10:3000`.

Plain HTTP is allowed for now. HTTPS comes with go-live.

## Sign in

The app signs in with a phone number and a 4-digit PIN. There are eight roles: Admin, Management, Sales manager, Salesman, Warehouse, Driver, Cashier and Accounts.

- After 5 wrong PINs, the login is locked for 15 minutes.
- An admin adds users, switches them off or on, and resets PINs.
- A sales manager can add their own salesmen.
- Every change is written to the audit log.

## Give staff a new Android version

1. Copy the new `.apk` file into `server/apk/`.
2. Register it as an admin. First sign in with `POST /auth/login` (phone and PIN). The reply has a `token`. Then run this, replacing the placeholders in angle brackets:

   ```sh
   curl -X POST http://localhost:3000/app/versions \
     -H "Authorization: Bearer <admin-token>" \
     -H "Content-Type: application/json" \
     -d '{"platform":"android","versionCode":<number>,"versionName":"<version name>","fileName":"<file-name>.apk"}'
   ```

3. Done. `GET /download` shows the download page, and the app offers the update when it opens.
