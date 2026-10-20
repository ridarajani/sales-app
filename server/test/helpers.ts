import { createPool } from '../src/db/pool.js';
import { migrate } from '../src/db/migrate.js';
import { seed } from '../src/db/seed.js';

const testUrl = process.env.TEST_DATABASE_URL ?? 'postgres://sales:sales@localhost:5432/sales_test';

// A clean, migrated and seeded test database.
export async function freshDb() {
  const db = createPool(testUrl);
  await db.query('drop schema public cascade; create schema public');
  await migrate(db);
  await seed(db);
  return db;
}
