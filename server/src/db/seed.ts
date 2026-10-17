import { fileURLToPath } from 'node:url';
import { createPool, type Db } from './pool.js';
import { migrate } from './migrate.js';

// Fixed reference data. TPIN and VAT stay empty until the client sends them.
export async function seed(db: Db) {
  await db.query(`insert into facilities (code, name) values
    ('F1', 'Facility 1'), ('F2', 'Facility 2')
    on conflict (code) do nothing`);
  await db.query(`insert into companies (code, name, facility_code, sort_order) values
    ('LV', 'Lucky Ventures', 'F1', 1),
    ('ZT', 'Zoona Trading', 'F1', 2),
    ('ZF', 'Zangafee', 'F2', 3),
    ('GL', 'Greenland', 'F2', 4)
    on conflict (code) do nothing`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const db = createPool();
  await migrate(db);
  await seed(db);
  console.log('Seeded companies and facilities');
  await db.end();
}
