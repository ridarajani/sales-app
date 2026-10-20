import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('health check reaches the database', async () => {
  const res = await app.inject({ method: 'GET', url: '/health' });
  expect(res.statusCode).toBe(200);
  expect(res.json()).toEqual({ ok: true });
});

test('lists the four companies with their facility', async () => {
  const res = await app.inject({ method: 'GET', url: '/companies' });
  expect(res.json()).toEqual([
    { code: 'LV', name: 'Lucky Ventures', facilityCode: 'F1' },
    { code: 'ZT', name: 'Zoona Trading', facilityCode: 'F1' },
    { code: 'ZF', name: 'Zangafee', facilityCode: 'F2' },
    { code: 'GL', name: 'Greenland', facilityCode: 'F2' },
  ]);
});
