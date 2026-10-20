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
        int sort_order
    }
    users {
        int id PK
        text name
        text phone UK "digits only"
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
    }
    sessions {
        text token_hash PK "sha256 of the token"
        int user_id FK
        timestamptz last_used_at
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

## Phase 1 notes

- **Phone:** stored as digits only, for example `0991234567`. Must be unique.
- **Roles (`users.role`):** `admin`, `management`, `sales_manager`, `salesman`, `warehouse`, `driver`, `cashier`, `accounts`.
- **Salesmen:** `manager_id` points to the sales manager they work under. It is required for salesmen and empty for everyone else. The rule is checked in code, not by the database.
- **PINs:** stored as scrypt hashes in `pin_hash`, never in plain text.
- **Sessions:** only a sha256 hash of the token is stored in `token_hash`.
- **Audit actions (`audit_log.action`):** `login`, `login_failed`, `logout`, `user_created`, `user_switched_off`, `user_switched_on`, `pin_reset`, `app_version_added`.
- **Failed logins by an unknown phone:** `audit_log.user_id` is empty.
- **App versions (`app_versions`):** one row per platform and version code. Platform is `android` for now.
