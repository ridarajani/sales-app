import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

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

test('audit log records the main actions with names, and PINs are never logged', async () => {
  const admin = (await login(app, '0990000000', '1234')).token;
  await login(app, '0990000000', '0000');
  const made = await post(app, admin, '/users', { name: 'Mgr', phone: '0992000001', role: 'management', pin: '4321' });
  const id = made.json().id;
  await post(app, admin, `/users/${id}/reset-pin`, { pin: '8765' });
  await post(app, admin, `/users/${id}/switch-off`);
  await post(app, admin, `/users/${id}/switch-on`);

  const res = await get(app, admin, '/audit');
  expect(res.statusCode).toBe(200);
  const rows = res.json() as { action: string; userName: string | null; targetUserName: string | null; details: unknown }[];
  const actions = rows.map((r) => r.action);
  for (const a of ['login', 'login_failed', 'user_created', 'pin_reset', 'user_switched_off', 'user_switched_on']) {
    expect(actions).toContain(a);
  }
  const created = rows.find((r) => r.action === 'user_created')!;
  expect(created).toMatchObject({ userName: 'Admin', targetUserName: 'Mgr' });
  expect(rows.find((r) => r.action === 'login_failed')!.details).toMatchObject({ phone: '0990000000' });
  expect(JSON.stringify(rows)).not.toMatch(/4321|8765/);
  expect(rows[0]).toHaveProperty('at');

  const limited = await get(app, admin, '/audit?limit=2');
  expect(limited.json()).toHaveLength(2);
});

test('failed login by an unknown phone is logged with no user', async () => {
  const admin = (await login(app, '0990000000', '1234')).token;
  await login(app, '0885550000', '1234');
  const rows = (await get(app, admin, '/audit')).json() as { action: string; userId: number | null; details: { phone?: string } }[];
  expect(rows.some((r) => r.action === 'login_failed' && r.userId === null && r.details.phone === '0885550000')).toBe(true);
});

test('management can read the audit log; a salesman cannot; no token is 401', async () => {
  const admin = (await login(app, '0990000000', '1234')).token;
  await post(app, admin, '/users', { name: 'Boss', phone: '0992000002', role: 'management', pin: '1111' });
  const mgr = await post(app, admin, '/users', { name: 'SM', phone: '0992000003', role: 'sales_manager', companies: ['LV'], pin: '1111' });
  await post(app, admin, '/users', { name: 'Sal', phone: '0992000004', role: 'salesman', managerId: mgr.json().id, pin: '1111' });
  const boss = (await login(app, '0992000002', '1111')).token;
  const sal = (await login(app, '0992000004', '1111')).token;
  expect((await get(app, boss, '/audit')).statusCode).toBe(200);
  expect((await get(app, sal, '/audit')).statusCode).toBe(403);
  expect((await app.inject({ method: 'GET', url: '/audit' })).statusCode).toBe(401);
});
