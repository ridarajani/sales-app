import { fileURLToPath } from 'node:url';
import { createPool, type Db } from './pool.js';
import { migrate } from './migrate.js';
import { config } from '../config.js';
import { hashPin } from '../auth/pin.js';
import { isValidPhone } from '../users.js';

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

  // The first admin, so someone can sign in and add everyone else. The phone number and PIN
  // are placeholders, not real ones: set real ones in .env before go-live.
  const { rows } = await db.query("select 1 from users where role = 'admin' limit 1");
  if (rows.length === 0) {
    if (!isValidPhone(config.bootstrapAdminPhone) || !/^\d{4}$/.test(config.bootstrapAdminPin)) {
      throw new Error('The first admin needs a 10 digit phone number starting with 0 and a 4 digit PIN. Check BOOTSTRAP_ADMIN_PHONE and BOOTSTRAP_ADMIN_PIN.');
    }
    if (process.env.NODE_ENV === 'production' && (config.bootstrapAdminPin === '1234' || config.bootstrapAdminPhone === '0990000000')) {
      throw new Error('Set your own BOOTSTRAP_ADMIN_PHONE and BOOTSTRAP_ADMIN_PIN before starting for real. The placeholder ones are not allowed.');
    }
    const { rows: made } = await db.query<{ id: number }>(
      `insert into users (name, phone, role, pin_hash) values ('Admin', $1, 'admin', $2)
       on conflict (phone) do nothing returning id`,
      [config.bootstrapAdminPhone, await hashPin(config.bootstrapAdminPin)],
    );
    if (made[0]) {
      await db.query('insert into user_companies (user_id, company_code) select $1, code from companies', [made[0].id]);
    }
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const db = createPool();
  await migrate(db);
  await seed(db);
  console.log('Seeded companies, facilities and the first admin');
  await db.end();
}
