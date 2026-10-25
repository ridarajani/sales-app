import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let kashif: string; // sales manager, ZF and GL
let seller: string;
let other: string; // a second salesman
let store: string;
let cashier: string;
let driver: string;
let sellerId: number;
let shop: number;
let milk: number;
let truck: number;
let planId: number;
let invoiceId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const find = async (token: string, q: string) => (await get(app, token, `/search?q=${encodeURIComponent(q)}`)).json();
const groupsOf = (body: { groups: { key: string }[] }) => body.groups.map((g) => g.key);
const alertsOf = async (token: string) => (await get(app, token, '/alerts')).json().alerts as { key: string; severity: string; title: string; detail: string; screen: string; id: number | null }[];

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir: mkdtempSync(join(tmpdir(), 'branding-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0996000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Kashif', phone: '0996000002', role: 'sales_manager', companies: ['ZF', 'GL'] });
  sellerId = await addUser({ name: 'Seller', phone: '0996000003', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Other', phone: '0996000004', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0996000005', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Cashier', phone: '0996000006', role: 'cashier', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  const driverId = await addUser({ name: 'Driver', phone: '0996000007', role: 'driver', companies: ['LV', 'ZT'] });
  const tokens = await Promise.all(
    ['0996000001', '0996000002', '0996000003', '0996000004', '0996000005', '0996000006', '0996000007'].map(
      async (p) => (await login(app, p, '1111')).token,
    ),
  );
  [manager, kashif, seller, other, store, cashier, driver] = tokens as [string, string, string, string, string, string, string];

  truck = (await post(app, admin, '/vehicles', { plate: 'TT 1', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  milk = (
    await post(app, admin, '/products', {
      code: 'LV-MILK',
      companyCode: 'LV',
      brand: 'Test',
      name: 'Milk',
      packSize: '1L',
      piecesPerCarton: 12,
      lowStockCartons: 20,
    })
  ).json().id;
  shop = (
    await post(app, admin, '/customers', {
      name: 'Shop A',
      channel: 'wholesaler',
      area: 'Area 25',
      phone: '0991 234 567',
      companies: [{ code: 'LV', paymentTermsDays: 30, creditLimit: 100000 }],
    })
  ).json().id;
  expect((await post(app, admin, '/customers', { name: 'Grocer B', channel: 'supermarket', area: 'Old Town', companies: [{ code: 'ZF', paymentTermsDays: 30 }] })).statusCode).toBe(201);
  await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo: 'M-1', expiryDate: day(300), cartons: 30 }] });

  // Seller's order: 5 cartons loaded, invoiced and delivered, and the invoice is now late.
  const orderId = (
    await post(app, manager, '/orders', {
      customerId: shop,
      invoiceType: 'vat',
      salesmanId: sellerId,
      lines: [{ productId: milk, cartons: 5, pricePerCarton: 24000 }],
    })
  ).json().id;
  planId = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json().id;
  const line = (await get(app, manager, `/orders/${orderId}`)).json().lines[0];
  await app.inject({ method: 'PUT', url: `/loading-plans/${planId}/items`, headers: { authorization: `Bearer ${manager}` }, payload: { items: [{ lineId: line.id, pieces: line.pieces }] } });
  await post(app, manager, `/loading-plans/${planId}/send`);
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(200);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('search needs at least 2 letters and is for signed-in staff', async () => {
  expect((await app.inject({ method: 'GET', url: '/search?q=shop' })).statusCode).toBe(401);
  const short = await get(app, manager, '/search?q=s');
  expect(short.statusCode).toBe(400);
  expect(short.json().message).toBe('Please type at least 2 letters.');
});

test('search finds a customer by name, area or phone typed any way', async () => {
  const byName = await find(manager, 'shop');
  expect(byName.groups[0]).toEqual({
    key: 'customers',
    title: 'Customers',
    count: 1,
    items: [{ id: shop, title: 'Shop A', detail: 'Area 25 · Wholesaler · 0991 234 567' }],
  });
  for (const q of ['area 25', '0991 234', '+265 991 234 567', '991234567']) {
    expect((await find(manager, q)).groups.find((g: { key: string }) => g.key === 'customers')?.items[0]?.id).toBe(shop);
  }
  // Another warehouse's customer is not found by this manager, but is by Kashif.
  expect(groupsOf(await find(manager, 'grocer'))).toEqual([]);
  expect((await find(kashif, 'grocer')).groups[0].items[0].title).toBe('Grocer B');
  // Drivers do not search customers.
  expect(groupsOf(await find(driver, 'shop'))).not.toContain('customers');
});

test('search finds orders, vehicles and products, each for those who may open them', async () => {
  const all = await find(manager, 'SO-0');
  expect(all.groups[0]).toMatchObject({ key: 'orders', count: 1, items: [{ title: 'Shop A' }] });
  expect(all.groups[0].items[0].detail).toMatch(/^SO-000001 · \d+ \w{3} \d{4} · Open$/);

  const plate = await find(manager, 'tt 1');
  expect(plate.groups).toEqual([
    { key: 'loadingPlans', title: 'Vehicles and delivery notes', count: 1, items: [expect.objectContaining({ id: planId, title: 'TT 1' })] },
  ]);
  expect(plate.groups[0].items[0].detail).toContain('DN-000001');
  // Found by its delivery note number too.
  expect((await find(store, 'DN-000001')).groups[0].items[0].id).toBe(planId);

  // The warehouse sees what is free to sell; a salesman sees the product but no stock.
  expect((await find(store, 'milk')).groups.find((g: { key: string }) => g.key === 'products').items[0]).toEqual({
    id: milk,
    title: 'Test Milk 1L',
    detail: 'LV-MILK · Lucky Ventures · 25 ctn free',
  });
  expect((await find(seller, 'milk')).groups.find((g: { key: string }) => g.key === 'products').items[0].detail).toBe('LV-MILK · Lucky Ventures');
  // A salesman sees only his own orders.
  expect((await find(seller, 'SO-0')).groups.map((g: { key: string }) => g.key)).toContain('orders');
  expect(groupsOf(await find(other, 'SO-0'))).toEqual([]);
});

test('search finds staff for managers only', async () => {
  expect((await find(admin, 'seller')).groups).toEqual([
    { key: 'staff', title: 'Staff', count: 1, items: [{ id: sellerId, title: 'Seller', detail: 'Salesman · 0996 000 003' }] },
  ]);
  expect((await find(admin, '0996 000 00')).groups[0].count).toBe(7);
  expect(groupsOf(await find(cashier, 'seller'))).toEqual([]);
  // Kashif's own staff only: not the LV and ZT salesmen.
  expect(groupsOf(await find(kashif, 'seller'))).toEqual([]);
});

test('alerts tell each job what is waiting for them', async () => {
  // Loaded but not invoiced: the sales manager is told to make the invoices; the warehouse is not.
  expect(await alertsOf(manager)).toContainEqual(
    expect.objectContaining({ key: `plan-invoices-${planId}`, severity: 'warn', title: 'Make the invoices for TT 1', screen: 'loadingPlan', id: planId }),
  );
  expect((await alertsOf(store)).map((a) => a.key)).not.toContain(`plan-invoices-${planId}`);

  invoiceId = (await post(app, manager, `/loading-plans/${planId}/invoices`)).json().made[0].id;
  await post(app, store, `/loading-plans/${planId}/gate`);
  const item = (await get(app, driver, `/loading-plans/${planId}`)).json().drops[0].lines[0];
  const delivered = await post(app, driver, `/loading-plans/${planId}/deliveries`, {
    customerId: shop,
    receivedBy: 'Mr Banda',
    signature: PNG,
    lines: [{ itemId: item.itemId, cartons: 5 }],
  });
  expect(delivered.statusCode).toBe(201);
  expect((await alertsOf(manager)).map((a) => a.key)).not.toContain(`plan-invoices-${planId}`);

  // 25 cartons left is above the level of 20; take 10 off the shelf and it is below.
  expect((await alertsOf(store)).map((a) => a.key)).not.toContain('low-LV-MILK');
  await db.query(`update stock_batches set on_hand = on_hand - 120`);
  expect(await alertsOf(store)).toContainEqual(
    expect.objectContaining({ key: 'low-LV-MILK', title: 'Low stock: Test Milk 1L', detail: '15 ctn free to sell, level 20 ctn.' }),
  );
  // A salesman sees no stock alerts.
  expect((await alertsOf(seller)).map((a) => a.key)).not.toContain('low-LV-MILK');
});

test('late payers and credit limits', async () => {
  // Owes 141,000 with VAT, over the 100,000 limit; nothing late yet.
  expect(await alertsOf(cashier)).toContainEqual(
    expect.objectContaining({ key: `credit-${shop}-Lucky Ventures`, title: 'Shop A is over the credit limit', screen: 'customer', id: shop }),
  );
  expect((await alertsOf(seller)).map((a) => a.severity)).not.toContain('bad');

  await db.query(`update invoices set due_date = current_date - 10 where id = $1`, [invoiceId]);
  const late = (await alertsOf(seller)).find((a) => a.key.startsWith('overdue-'));
  expect(late).toMatchObject({ severity: 'bad', title: 'Shop A owes MWK 141,000 late', screen: 'customer', id: shop });
  expect(late!.detail).toMatch(/^Lucky Ventures · due since \d+ \w{3} \d{4}$/);
  // Bad news comes first.
  expect((await alertsOf(seller))[0]!.severity).toBe('bad');
  // The other salesman's customers only.
  expect((await alertsOf(other)).some((a) => a.key.startsWith('overdue-'))).toBe(false);
  // Kashif's companies have nothing late.
  expect((await alertsOf(kashif)).some((a) => a.key.startsWith('overdue-'))).toBe(false);
});

test('cash held and handovers waiting', async () => {
  const paid = await post(app, seller, '/collections', {
    customerId: shop,
    companyCode: 'LV',
    invoiceId,
    mode: 'cash',
    amount: 50000,
    receivedBy: 'Mr Banda',
    signature: PNG,
  });
  expect(paid.statusCode).toBe(201);
  expect(await alertsOf(seller)).toContainEqual(expect.objectContaining({ title: 'You hold MWK 50,000 cash', screen: 'cash' }));
  expect(await alertsOf(cashier)).toContainEqual(expect.objectContaining({ title: 'Seller holds MWK 50,000 cash' }));

  const handed = await post(app, seller, '/handovers', {});
  expect(handed.statusCode).toBe(201);
  expect((await alertsOf(seller)).some((a) => a.screen === 'cash')).toBe(false);
  expect(await alertsOf(cashier)).toContainEqual(
    expect.objectContaining({ key: 'handovers-waiting', title: '1 cash handover to count', detail: 'MWK 50,000 handed in.' }),
  );
});

test('expired and short-dated stock', async () => {
  await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo: 'M-2', expiryDate: day(40), cartons: 2 }] });
  await db.query(`update stock_batches set expiry_date = current_date - 3 where batch_no = 'M-1'`);
  const list = await alertsOf(store);
  expect(list).toContainEqual(
    expect.objectContaining({ severity: 'bad', title: 'Expired: Test Milk 1L, batch M-1', detail: '15 ctn still in the warehouse. It cannot be sold; write it off.' }),
  );
  expect(list).toContainEqual(expect.objectContaining({ key: 'near-expiry', severity: 'info', title: '1 batch expires within 90 days' }));
  await db.query(`update stock_batches set expiry_date = current_date + 300 where batch_no = 'M-1'`);
});

test('sales a phone could not read: the leader sends them, Admin clears them, then the trip can close', async () => {
  const made = await post(app, manager, '/trips', {
    kind: 'minibus',
    facilityCode: 'F1',
    tripDate: day(0),
    route: 'Area 49 market',
    stops: [],
    vehicleId: truck,
    leaderId: sellerId,
    float: 0,
    lines: [{ productId: milk, cartons: 1 }],
  });
  expect(made.statusCode).toBe(201);
  const tripId = made.json().id;
  expect((await post(app, store, `/trips/${tripId}/load`)).statusCode).toBe(200);

  const file = Buffer.from('{"sales":[{"broken').toString('base64');
  const report = { requestId: 'r-1', file, fileName: 'trip-1-outbox-unreadable-1.json' };
  expect((await post(app, other, `/trips/${tripId}/outbox-problems`, report)).statusCode).toBe(404);
  const sent = await post(app, seller, `/trips/${tripId}/outbox-problems`, report);
  expect(sent.statusCode).toBe(201);
  const problemId = sent.json().id;
  // Sent again with no answer the first time: the same problem.
  const again = await post(app, seller, `/trips/${tripId}/outbox-problems`, report);
  expect(again.statusCode).toBe(200);
  expect(again.json()).toEqual({ id: problemId, cleared: false });

  expect((await get(app, seller, `/trips/${tripId}`)).json()).toMatchObject({ openOutboxProblems: 1, outboxProblems: [{ id: problemId, requestId: 'r-1', cleared: false }] });
  expect(await alertsOf(seller)).toContainEqual(expect.objectContaining({ key: `outbox-trip-${tripId}`, screen: 'trip', id: tripId }));
  expect(await alertsOf(admin)).toContainEqual(
    expect.objectContaining({ key: `outbox-${problemId}`, severity: 'bad', title: "Sales on Seller's phone could not be read", screen: 'outboxProblem', id: problemId }),
  );

  const closing = { lines: [{ productId: milk, cartons: 1 }], counted: 0 };
  const blocked = await post(app, seller, `/trips/${tripId}/close`, closing);
  expect(blocked.statusCode).toBe(409);
  expect(blocked.json().error).toBe('outbox_problem');

  // Admin and Management can look; only Admin clears.
  expect((await get(app, manager, '/outbox-problems')).statusCode).toBe(403);
  const listed = (await get(app, admin, '/outbox-problems?open=true')).json();
  expect(listed).toMatchObject([{ id: problemId, tripId, leaderName: 'Seller', reportedBy: 'Seller', cleared: false, fileSize: 18 }]);
  const download = await get(app, admin, `/outbox-problems/${problemId}/file`);
  expect(download.statusCode).toBe(200);
  expect(download.headers['content-disposition']).toBe(`attachment; filename="TRP-000001-unread-sales-${problemId}.json"`);
  expect(download.body).toBe('{"sales":[{"broken');
  expect((await get(app, admin, `/outbox-problems/${problemId}`)).json().canClear).toBe(true);

  expect((await post(app, admin, `/outbox-problems/${problemId}/clear`, { note: '' })).json().message).toBe('Please write what you did about these sales.');
  const cleared = await post(app, admin, `/outbox-problems/${problemId}/clear`, { note: 'Nothing was missing.' });
  expect(cleared.statusCode).toBe(200);
  expect(cleared.json()).toMatchObject({ cleared: true, clearedBy: 'Admin', clearNote: 'Nothing was missing.', canClear: false });
  expect((await post(app, admin, `/outbox-problems/${problemId}/clear`, { note: 'Again' })).json().error).toBe('already_cleared');
  expect((await alertsOf(admin)).some((a) => a.key.startsWith('outbox-'))).toBe(false);
  expect((await get(app, seller, `/trips/${tripId}`)).json()).toMatchObject({ openOutboxProblems: 0, outboxProblems: [{ id: problemId, cleared: true }] });

  expect((await post(app, seller, `/trips/${tripId}/close`, closing)).statusCode).toBe(200);
  // Trips are found by route or number.
  expect((await find(seller, 'area 49')).groups.find((g: { key: string }) => g.key === 'trips').items[0]).toMatchObject({ id: tripId, title: 'Area 49 market' });
});
