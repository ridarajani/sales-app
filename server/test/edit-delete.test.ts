import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { vatOn } from '../src/orders/orders.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let abbas: string; // sales manager for LV and ZT
let kashif: string; // sales manager for ZF and GL
let seller: string; // under Abbas
let abbasId: number;
let sellerId: number;
let driverId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const put = (token: string, url: string, payload: unknown) =>
  app.inject({ method: 'PUT', url, headers: bearer(token), payload: payload as object });
const del = (token: string, url: string) => app.inject({ method: 'DELETE', url, headers: bearer(token) });

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
async function addProduct(code: string, companyCode: string, extra: Record<string, unknown> = {}) {
  const r = await post(app, admin, '/products', { code, companyCode, brand: 'Test', name: code, packSize: 'x', piecesPerCarton: 10, ...extra });
  expect(r.statusCode).toBe(201);
  return r.json();
}
async function addCustomer(name: string, codes: string[]) {
  const r = await post(app, admin, '/customers', { name, channel: 'wholesaler', companies: codes.map((code) => ({ code, paymentTermsDays: 30 })) });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  abbasId = await addUser({ name: 'Abbas', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Kashif', phone: '0995000002', role: 'sales_manager', companies: ['ZF', 'GL'] });
  sellerId = await addUser({ name: 'Seller', phone: '0995000003', role: 'salesman', managerId: abbasId, companies: ['LV'] });
  driverId = await addUser({ name: 'Banda', phone: '0995000004', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  abbas = (await login(app, '0995000001', '1111')).token;
  kashif = (await login(app, '0995000002', '1111')).token;
  seller = (await login(app, '0995000003', '1111')).token;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('VAT is charged only on the lines that are not VAT exempt', async () => {
  expect(vatOn([{ amount: 10000, vatExempt: false }, { amount: 5000, vatExempt: true }], 17.5)).toBe(1750);
  const milk = await addProduct('LV-VAT', 'LV');
  const rice = await addProduct('LV-EXEMPT', 'LV', { vatExempt: true });
  expect(milk.vatExempt).toBe(false);
  expect(rice.vatExempt).toBe(true);
  // An edit that does not mention it keeps it.
  expect((await put(admin, `/products/${rice.id}`, { name: 'Rice' })).json().vatExempt).toBe(true);

  const customer = await addCustomer('Exempt Shop', ['LV']);
  const r = await post(app, seller, '/orders', {
    customerId: customer,
    invoiceType: 'vat',
    wantedOn: day(1),
    lines: [
      { productId: milk.id, cartons: 1, pricePerCarton: 10000 },
      { productId: rice.id, cartons: 2, pricePerCarton: 20000 },
    ],
  });
  expect(r.statusCode).toBe(201);
  // 10,000 is charged VAT; the 40,000 of rice is not.
  expect(r.json().companies[0]).toMatchObject({ subtotal: 50000, vat: 1750, total: 51750 });
  expect(r.json().lines.map((l: { vatExempt: boolean }) => l.vatExempt)).toEqual([false, true]);
  const listed = (await get(app, seller, '/orders')).json().find((o: { id: number }) => o.id === r.json().id);
  expect(listed.total).toBe(51750);
});

test('a product never used is deleted; one with history is kept but hidden, and its code is free again', async () => {
  const unused = await addProduct('LV-GONE', 'LV');
  const d = await del(admin, `/products/${unused.id}`);
  expect(d.statusCode).toBe(200);
  expect(d.json()).toEqual({ deleted: true, keptForHistory: false });
  expect((await get(app, admin, `/products/${unused.id}`)).statusCode).toBe(404);

  const used = (await get(app, admin, '/products?q=LV-VAT')).json()[0];
  expect((await del(abbas, `/products/${used.id}`)).statusCode).toBe(403);
  expect((await del(admin, `/products/${used.id}`)).json()).toEqual({ deleted: true, keptForHistory: true });
  expect((await get(app, admin, '/products?includeInactive=true')).json().some((p: { id: number }) => p.id === used.id)).toBe(false);
  expect((await put(admin, `/products/${used.id}`, { active: true })).statusCode).toBe(404);
  // The order still shows it.
  const order = (await get(app, seller, '/orders')).json()[0];
  expect((await get(app, seller, `/orders/${order.id}`)).json().lines[0].code).toBe('LV-VAT');
  await addProduct('LV-VAT', 'LV');
});

test('customers: deleted when unused, kept when they owe an opening balance, and only by those who run all their companies', async () => {
  const unused = await addCustomer('Never Bought', ['LV']);
  expect((await del(abbas, `/customers/${unused}`)).json()).toEqual({ deleted: true, keptForHistory: false });

  const owes = await addCustomer('Owes Money', ['LV']);
  expect((await put(admin, `/customers/${owes}/opening-balance`, { companyCode: 'LV', amount: 5000, asOf: day(0) })).statusCode).toBe(200);
  expect((await del(admin, `/customers/${owes}`)).json().keptForHistory).toBe(true);
  expect((await get(app, admin, `/customers/${owes}`)).statusCode).toBe(404);
  await addCustomer('Owes Money', ['LV']);

  const shared = await addCustomer('Shared Shop', ['LV', 'ZF']);
  expect((await del(abbas, `/customers/${shared}`)).statusCode).toBe(403);
  expect((await del(kashif, `/customers/${shared}`)).statusCode).toBe(403);
});

test('vehicles: both sales managers keep the shared list, and each person marks their own dormant ones', async () => {
  const a = await post(app, abbas, '/vehicles', { plate: 'AA 1', description: 'Truck', defaultDriverId: driverId });
  const b = await post(app, kashif, '/vehicles', { plate: 'BB 2', description: 'Van' });
  expect(a.statusCode).toBe(201);
  expect(b.statusCode).toBe(201);
  expect((await post(app, seller, '/vehicles', { plate: 'CC 3' })).statusCode).toBe(403);

  const marked = await put(kashif, `/vehicles/${a.json().id}/dormant`, { dormant: true });
  expect(marked.json().dormant).toBe(true);
  // Kashif's dormant truck goes to the bottom of his list; Abbas still uses it.
  expect((await get(app, kashif, '/vehicles')).json().map((v: { plate: string; dormant: boolean }) => [v.plate, v.dormant])).toEqual([
    ['BB 2', false],
    ['AA 1', true],
  ]);
  expect((await get(app, abbas, '/vehicles')).json().map((v: { plate: string; dormant: boolean }) => [v.plate, v.dormant])).toEqual([
    ['AA 1', false],
    ['BB 2', false],
  ]);
  expect((await put(kashif, `/vehicles/${a.json().id}/dormant`, { dormant: false })).json().dormant).toBe(false);

  expect((await del(abbas, `/vehicles/${b.json().id}`)).json()).toEqual({ deleted: true, keptForHistory: false });
  expect((await get(app, kashif, '/vehicles')).json()).toHaveLength(1);
  expect((await post(app, kashif, '/vehicles', { plate: 'BB 2' })).statusCode).toBe(201);
});

test('users: edit name, phone, job and companies', async () => {
  const r = await put(abbas, `/users/${sellerId}`, { name: 'Seller Phiri', phone: '0995 000 013', companies: ['LV', 'ZT'] });
  expect(r.statusCode).toBe(200);
  expect(r.json()).toMatchObject({ name: 'Seller Phiri', phone: '0995000013', companies: ['LV', 'ZT'], managerId: abbasId });
  expect((await login(app, '0995000013', '1111')).res.statusCode).toBe(200);
  expect((await put(abbas, `/users/${sellerId}`, { phone: '0995000002' })).statusCode).toBe(409);
  expect((await put(abbas, `/users/${sellerId}`, { role: 'warehouse' })).statusCode).toBe(403);
  expect((await put(abbas, `/users/${sellerId}`, { companies: ['ZF'] })).statusCode).toBe(400);
  expect((await put(kashif, `/users/${sellerId}`, { name: 'x' })).statusCode).toBe(403);

  const store = await addUser({ name: 'Store', phone: '0995000005', role: 'warehouse', companies: ['LV'] });
  const moved = await put(admin, `/users/${store}`, { role: 'cashier', companies: ['LV', 'ZT'] });
  expect(moved.json()).toMatchObject({ role: 'cashier', companies: ['LV', 'ZT'], managerId: null });
  // Abbas's salesman would lose his sales manager.
  expect((await put(admin, `/users/${abbasId}`, { companies: ['ZT'] })).statusCode).toBe(400);
  expect((await put(admin, `/users/${abbasId}`, { role: 'accounts' })).statusCode).toBe(400);
  expect((await put(abbas, `/users/${abbasId}`, { name: 'Mr. Abbas' })).statusCode).toBe(403);
});

test('users: a deleted login stops at once, leaves the lists, and the phone can be used again', async () => {
  expect((await del(admin, `/users/${abbasId}`)).statusCode).toBe(400);
  expect((await del(abbas, `/users/${driverId}`)).statusCode).toBe(403);

  const driverToken = (await login(app, '0995000004', '1111')).token;
  const d = await del(admin, `/users/${driverId}`);
  expect(d.json()).toEqual({ deleted: true, keptForHistory: true });
  expect((await get(app, driverToken, '/vehicles')).statusCode).toBe(401);
  expect((await login(app, '0995000004', '1111')).res.statusCode).not.toBe(200);
  expect((await get(app, admin, '/users')).json().some((u: { id: number }) => u.id === driverId)).toBe(false);
  expect((await get(app, admin, '/drivers')).json()).toEqual([]);
  expect((await get(app, abbas, '/vehicles')).json()[0].defaultDriverId).toBeNull();
  expect((await post(app, admin, `/users/${driverId}/switch-on`)).statusCode).toBe(404);

  await addUser({ name: 'New Driver', phone: '0995000004', role: 'driver', companies: ['LV'] });
  expect((await del(seller, `/users/${sellerId}`)).statusCode).toBe(403);
});
