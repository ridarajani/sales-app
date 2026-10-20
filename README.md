# Sales and Distribution App

In-house sales and distribution app for Lucky Ventures, Zoona Trading, Zangafee and Greenland.

- `index.html` is the clickable prototype, served on GitHub Pages.
- `server/` is the server and database (Node.js, TypeScript, PostgreSQL).
- `mobile/` is the phone app for Android and iPhone (Kotlin Multiplatform, Compose Multiplatform). The screens live in `mobile/shared`.
- `docs/PLAN.md` is the development plan, phase by phase.

## Run the server

Needs Node.js 22 and either Docker or PostgreSQL 16. With Docker, `docker compose up -d` in the repo folder starts the database and a browser view of it at http://localhost:8080 (see `docs/DATABASE.md`).

```sh
cd server
cp .env.example .env      # then set DATABASE_URL
npm install
npm run seed              # creates the tables and the four companies
npm run dev               # http://localhost:3000/health
npm test                  # uses the sales_test database
```

## Run the phone app

- **Android:** open the `mobile` folder in Android Studio and run `androidApp`.
- **iPhone:** on a Mac, open `mobile/iosApp/iosApp.xcodeproj` in Xcode and press Run. To install on a real iPhone or upload to TestFlight, set your Apple team ID in `mobile/iosApp/Configuration/Config.xcconfig`.

The app is not published on Google Play or the App Store. Android phones install it from a private download page; iPhones install it through TestFlight.
