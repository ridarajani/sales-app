import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let abbas: { id: number; token: string };
let kashif: { id: number; token: string };
let salesman: { id: number; token: string };

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('admin creates a sales manager, a salesman under them, and a warehouse user', async () => {
  const m = await post(app, admin, '/users', { name: 'Manager A', phone: '0991000001', role: 'sales_manager', companies: ['LV', 'ZT'], pin: '1111' });
  expect(m.statusCode).toBe(201);
  expect(m.json()).toMatchObject({ role: 'sales_manager', companies: ['LV', 'ZT'], managerId: null, active: true });
  abbas = { id: m.json().id, token: (await login(app, '0991000001', '1111')).token };

  const k = await post(app, admin, '/users', { name: 'Manager B', phone: '0991000002', role: 'sales_manager', companies: ['ZF', 'GL'], pin: '1111' });
  kashif = { id: k.json().id, token: (await login(app, '0991000002', '1111')).token };

  const s = await post(app, admin, '/users', { name: 'Sam Salesman', phone: '0991000003', role: 'salesman', managerId: abbas.id, companies: ['LV'], pin: '2222' });
  expect(s.statusCode).toBe(201);
  expect(s.json()).toMatchObject({ role: 'salesman', managerId: abbas.id, managerName: 'Manager A', companies: ['LV'] });
  salesman = { id: s.json().id, token: (await login(app, '0991000003', '2222')).token };

  const w = await post(app, admin, '/users', { name: 'Wally Warehouse', phone: '0991000004', role: 'warehouse', companies: ['LV', 'ZT'], pin: '3333' });
  expect(w.statusCode).toBe(201);
  expect(w.json().pin_hash).toBeUndefined();
});

test('admin and management always get all four companies', async () => {
  const r = await post(app, admin, '/users', { name: 'Boss', phone: '0991000005', role: 'management', companies: ['LV'], pin: '4444' });
  expect(r.json().companies).toEqual(['LV', 'ZT', 'ZF', 'GL']);
});

test('salesman without a manager is 400', async () => {
  const r = await post(app, admin, '/users', { name: 'No Boss', phone: '0991000006', role: 'salesman', companies: ['LV'], pin: '1234' });
  expect(r.statusCode).toBe(400);
  expect(r.json().error).toBe('invalid');
});

test('salesman with companies outside the manager is 400', async () => {
  const r = await post(app, admin, '/users', { name: 'Wrong Co', phone: '0991000007', role: 'salesman', managerId: abbas.id, companies: ['ZF'], pin: '1234' });
  expect(r.statusCode).toBe(400);
});

test('only salesmen can have a manager, and other roles need a company', async () => {
  const a = await post(app, admin, '/users', { name: 'X', phone: '0991000008', role: 'driver', managerId: abbas.id, companies: ['LV'], pin: '1234' });
  expect(a.statusCode).toBe(400);
  const b = await post(app, admin, '/users', { name: 'X', phone: '0991000008', role: 'driver', companies: [], pin: '1234' });
  expect(b.statusCode).toBe(400);
});

test('sales manager creates a salesman and gets own companies by default', async () => {
  const r = await post(app, abbas.token, '/users', { name: 'Sally', phone: '0991000009', role: 'salesman', pin: '5555' });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ managerId: abbas.id, companies: ['LV', 'ZT'] });
});

test('sales manager cannot create other roles or salesmen under another manager', async () => {
  const w = await post(app, abbas.token, '/users', { name: 'W', phone: '0991000010', role: 'warehouse', companies: ['LV'], pin: '1234' });
  expect(w.statusCode).toBe(403);
  expect(w.json()).toEqual({ error: 'not_allowed', message: 'You cannot do this.' });
  const s = await post(app, abbas.token, '/users', { name: 'S', phone: '0991000010', role: 'salesman', managerId: kashif.id, pin: '1234' });
  expect(s.statusCode).toBe(403);
  const o = await post(app, abbas.token, '/users', { name: 'S', phone: '0991000010', role: 'salesman', companies: ['ZF'], pin: '1234' });
  expect(o.statusCode).toBe(400);
});

test('sales manager lists only own salesmen and self; admin lists everyone', async () => {
  const mine = await get(app, abbas.token, '/users');
  expect(mine.statusCode).toBe(200);
  expect(mine.json().map((u: { name: string }) => u.name).sort()).toEqual(['Manager A', 'Sally', 'Sam Salesman']);
  const other = await get(app, kashif.token, '/users');
  expect(other.json().map((u: { name: string }) => u.name)).toEqual(['Manager B']);
  const all = await get(app, admin, '/users');
  expect(all.json().length).toBeGreaterThanOrEqual(7);
  expect(all.json()[0]).toHaveProperty('managerName');
});

test('warehouse user and salesman cannot list users', async () => {
  const w = (await login(app, '0991000004', '3333')).token;
  expect((await get(app, w, '/users')).statusCode).toBe(403);
  expect((await get(app, salesman.token, '/users')).statusCode).toBe(403);
});

test('duplicate phone is 409', async () => {
  const r = await post(app, admin, '/users', { name: 'Dup', phone: '+265 99 100 0001', role: 'cashier', companies: ['LV'], pin: '1234' });
  expect(r.statusCode).toBe(409);
  expect(r.json()).toEqual({ error: 'phone_taken', message: 'That phone number already has a login.' });
});

test('switch-off ends sessions and blocks login; switch-on lets them back', async () => {
  expect((await get(app, salesman.token, '/auth/me')).statusCode).toBe(200);
  const off = await post(app, admin, `/users/${salesman.id}/switch-off`);
  expect(off.statusCode).toBe(200);
  expect(off.json().active).toBe(false);
  expect((await get(app, salesman.token, '/auth/me')).statusCode).toBe(401);
  const { res } = await login(app, '0991000003', '2222');
  expect(res.statusCode).toBe(403);
  expect(res.json().error).toBe('switched_off');
  const on = await post(app, abbas.token, `/users/${salesman.id}/switch-on`);
  expect(on.json().active).toBe(true);
  expect((await login(app, '0991000003', '2222')).res.statusCode).toBe(200);
});

test('cannot switch off yourself; sales manager cannot touch others', async () => {
  const adminId = (await get(app, admin, '/auth/me')).json().id;
  expect((await post(app, admin, `/users/${adminId}/switch-off`)).statusCode).toBe(400);
  expect((await post(app, kashif.token, `/users/${salesman.id}/switch-off`)).statusCode).toBe(403);
  expect((await post(app, admin, '/users/99999/switch-off')).statusCode).toBe(404);
});

test('reset-pin changes the PIN and signs the user out', async () => {
  const before = (await login(app, '0991000003', '2222')).token;
  const r = await post(app, abbas.token, `/users/${salesman.id}/reset-pin`, { pin: '7777' });
  expect(r.json()).toEqual({ ok: true });
  expect((await login(app, '0991000003', '2222')).res.statusCode).toBe(401);
  expect((await get(app, before, '/auth/me')).statusCode).toBe(401);
  expect((await login(app, '0991000003', '7777')).res.statusCode).toBe(200);
  expect((await post(app, kashif.token, `/users/${salesman.id}/reset-pin`, { pin: '1212' })).statusCode).toBe(403);
  expect((await post(app, admin, `/users/${salesman.id}/reset-pin`, { pin: 'abcd' })).statusCode).toBe(400);
});

test('a switched off user cannot use an old token on other routes', async () => {
  const m = await post(app, admin, '/users', { name: 'Gone Mgr', phone: '0991000020', role: 'sales_manager', companies: ['LV'], pin: '1111' });
  const token = (await login(app, '0991000020', '1111')).token;
  expect((await get(app, token, '/users')).statusCode).toBe(200);
  await post(app, admin, `/users/${m.json().id}/switch-off`);
  expect((await get(app, token, '/users')).statusCode).toBe(401);
});

test('salesman under a switched off manager or a non-manager is 400', async () => {
  const off = await post(app, admin, '/users', { name: 'Off Mgr', phone: '0991000021', role: 'sales_manager', companies: ['LV'], pin: '1111' });
  await post(app, admin, `/users/${off.json().id}/switch-off`);
  const a = await post(app, admin, '/users', { name: 'S1', phone: '0991000022', role: 'salesman', managerId: off.json().id, companies: ['LV'], pin: '1111' });
  expect(a.statusCode).toBe(400);
  const warehouse = await post(app, admin, '/users', { name: 'WH', phone: '0991000023', role: 'warehouse', companies: ['LV'], pin: '1111' });
  const b = await post(app, admin, '/users', { name: 'S2', phone: '0991000024', role: 'salesman', managerId: warehouse.json().id, companies: ['LV'], pin: '1111' });
  expect(b.statusCode).toBe(400);
  const c = await post(app, admin, '/users', { name: 'S3', phone: '0991000025', role: 'salesman', managerId: 2147483648, companies: ['LV'], pin: '1111' });
  expect(c.statusCode).toBe(400);
});

test('sales manager can give a salesman a subset of own companies', async () => {
  const r = await post(app, abbas.token, '/users', { name: 'Subset', phone: '0991000026', role: 'salesman', companies: ['ZT'], pin: '1111' });
  expect(r.statusCode).toBe(201);
  expect(r.json().companies).toEqual(['ZT']);
});

test('sales manager cannot switch off or reset an admin or another manager', async () => {
  const adminId = (await get(app, admin, '/auth/me')).json().id;
  for (const id of [adminId, kashif.id]) {
    expect((await post(app, abbas.token, `/users/${id}/switch-off`)).statusCode).toBe(403);
    expect((await post(app, abbas.token, `/users/${id}/reset-pin`, { pin: '1212' })).statusCode).toBe(403);
  }
});

test('ids out of range or not numbers are 404', async () => {
  for (const id of ['99999999999', 'abc', '0']) {
    const r = await post(app, admin, `/users/${id}/switch-off`);
    expect(r.statusCode).toBe(404);
    expect(r.json().error).toBe('not_found');
  }
});

test('phone must be a real Malawi number on create', async () => {
  const r = await post(app, admin, '/users', { name: 'Bad', phone: '123456789', role: 'cashier', companies: ['LV'], pin: '1234' });
  expect(r.statusCode).toBe(400);
  expect(r.json().message).toBe('That phone number does not look right.');
  const ok = await post(app, admin, '/users', { name: 'Good', phone: '+265 99 123 4567', role: 'cashier', companies: ['LV'], pin: '1234' });
  expect(ok.statusCode).toBe(201);
  expect(ok.json().phone).toBe('0991234567');
});

test('a bad field is named in plain words', async () => {
  const r = await post(app, admin, '/users', { name: 'X', phone: '0991000030', role: 'salesman', managerId: 'abc', pin: '1234' });
  expect(r.statusCode).toBe(400);
  expect(r.json().message).toContain('sales manager');
});

test('switch-on with a JSON content type and an empty body works', async () => {
  const w = await post(app, admin, '/users', { name: 'Empty', phone: '0991000031', role: 'driver', companies: ['LV'], pin: '1234' });
  const id = w.json().id;
  await post(app, admin, `/users/${id}/switch-off`);
  const res = await app.inject({
    method: 'POST',
    url: `/users/${id}/switch-on`,
    headers: { authorization: `Bearer ${admin}`, 'content-type': 'application/json' },
    payload: '',
  });
  expect(res.statusCode).toBe(200);
  expect(res.json().active).toBe(true);
  const bad = await app.inject({
    method: 'POST',
    url: '/auth/login',
    headers: { 'content-type': 'application/json' },
    payload: '{oops',
  });
  expect(bad.statusCode).toBe(400);
});

test('a salesman can work under two sales managers, one per company', async () => {
  const r = await post(app, admin, '/users', {
    name: 'Rex Both',
    phone: '0991000077',
    role: 'salesman',
    companyManagers: { LV: abbas.id, ZT: abbas.id, ZF: kashif.id, GL: kashif.id },
    pin: '1111',
  });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ companies: ['LV', 'ZT', 'ZF', 'GL'], managerId: abbas.id });
  expect(r.json().managers).toEqual([
    { company: 'LV', managerId: abbas.id, managerName: 'Manager A' },
    { company: 'ZT', managerId: abbas.id, managerName: 'Manager A' },
    { company: 'ZF', managerId: kashif.id, managerName: 'Manager B' },
    { company: 'GL', managerId: kashif.id, managerName: 'Manager B' },
  ]);
  const id = r.json().id;
  // Both managers see him and can reset his PIN.
  for (const m of [abbas, kashif]) {
    expect((await get(app, m.token, '/users')).json().map((u: { id: number }) => u.id)).toContain(id);
    expect((await post(app, m.token, `/users/${id}/reset-pin`, { pin: '2222' })).statusCode).toBe(200);
  }
});

test('per-company managers are checked', async () => {
  const base = { name: 'X', role: 'salesman', pin: '1111' };
  // Kashif does not run LV.
  expect((await post(app, admin, '/users', { ...base, phone: '0991000078', companyManagers: { LV: kashif.id } })).json().message).toMatch(
    /sales manager's companies/,
  );
  expect((await post(app, admin, '/users', { ...base, phone: '0991000079', companies: ['LV'], companyManagers: { ZF: kashif.id } })).statusCode).toBe(400);
  expect((await post(app, admin, '/users', { ...base, role: 'driver', phone: '0991000080', companies: ['LV'], companyManagers: { LV: abbas.id } })).statusCode).toBe(400);
  // A sales manager cannot put a salesman under someone else.
  expect((await post(app, abbas.token, '/users', { ...base, phone: '0991000081', companyManagers: { LV: abbas.id, ZF: kashif.id } })).statusCode).toBe(403);
});
