# Sales and Distribution App

In-house sales and distribution app for Lucky Ventures, Zoona Trading, Zangafee and Greenland.

- `index.html` is the clickable prototype, served on GitHub Pages.
- `server/` is the server and database (Node.js, TypeScript, PostgreSQL).
- `android/` is the phone app (Kotlin, Jetpack Compose).
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

## Run the Android app

Open the `android` folder in Android Studio and press Run. On the emulator the app talks to the server at `http://10.0.2.2:3000`.
