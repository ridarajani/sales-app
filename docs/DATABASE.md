# Database

PostgreSQL 16. Tables are created by the numbered files in `server/src/db/migrations`, in order. Update this page in the same pull request as any migration.

## See the data

- **In the browser:** run `docker compose up -d` in the repo folder, open http://localhost:8080 and sign in with System `PostgreSQL`, Server `db`, User `sales`, Password `sales`, Database `sales_dev`.
- **In an app:** TablePlus, Postico, DBeaver or pgAdmin, connecting to `localhost:5432` with user `sales`, password `sales`, database `sales_dev`.
- **In Terminal:** `psql postgres://sales:sales@localhost:5432/sales_dev`, then `\dt` lists the tables.

Run `cd server && npm run seed` once to create the tables and the starting data.

## Tables

```mermaid
erDiagram
    facilities ||--o{ companies : "serves"
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
```

| Table | Added in | What it holds |
|---|---|---|
| `facilities` | Phase 0 | The two facilities |
| `companies` | Phase 0 | The four companies and which facility serves each |
| `schema_migrations` | Phase 0 | Which migration files have run |
