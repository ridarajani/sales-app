import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createPool, type Db } from './pool.js';

const dir = fileURLToPath(new URL('./migrations/', import.meta.url));

// Runs every .sql file in migrations/ once, in name order.
export async function migrate(db: Db): Promise<string[]> {
  await db.query('create table if not exists schema_migrations (name text primary key, run_at timestamptz not null default now())');
  const done = new Set((await db.query<{ name: string }>('select name from schema_migrations')).rows.map((r) => r.name));
  const ran: string[] = [];
  for (const name of readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()) {
    if (done.has(name)) continue;
    const client = await db.connect();
    try {
      await client.query('begin');
      await client.query(readFileSync(dir + name, 'utf8'));
      await client.query('insert into schema_migrations (name) values ($1)', [name]);
      await client.query('commit');
      ran.push(name);
    } catch (e) {
      await client.query('rollback');
      throw new Error(`Migration ${name} failed: ${(e as Error).message}`);
    } finally {
      client.release();
    }
  }
  return ran;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const db = createPool();
  const ran = await migrate(db);
  console.log(ran.length ? `Ran ${ran.join(', ')}` : 'Database is up to date');
  await db.end();
}
