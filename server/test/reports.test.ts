import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { REPORTS } from '../src/reports/catalogue.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

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
let sellerId: number;
let otherId: number;
let shop: number;
let milk: number;
let tea: number;
let truck: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const month = () => new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 7);
const put = (token: string, url: string, payload: unknown) => app.inject({ method: 'PUT', url, headers: bearer(token), payload: payload as object });
const report = async (token: string, key: string, query = '') => {
  const r = await get(app, token, `/reports/${key}${query ? `?${query}` : ''}`);
  return { status: r.statusCode, body: r.json() };
};

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
  otherId = await addUser({ name: 'Other', phone: '0996000004', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0996000005', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Cashier', phone: '0996000006', role: 'cashier', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  const driverId = await addUser({ name: 'Driver', phone: '0996000007', role: 'driver', companies: ['LV', 'ZT'] });
  const tokens = await Promise.all(
    ['0996000001', '0996000002', '0996000003', '0996000004', '0996000005', '0996000006', '0996000007'].map(
      async (p) => (await login(app, p, '1111')).token,
    ),
  );
  [manager, kashif, seller, other, store, cashier] = tokens as [string, string, string, string, string, string];
  const driver = tokens[6]!;

  truck = (await post(app, admin, '/vehicles', { plate: 'TT 1', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  milk = (await post(app, admin, '/products', { code: 'LV-MILK', companyCode: 'LV', brand: 'Test', name: 'Milk', packSize: '1L', piecesPerCarton: 12 }))
    .json().id;
  tea = (
    await post(app, admin, '/products', {
      code: 'ZT-TEA',
      companyCode: 'ZT',
      brand: 'Test',
      name: 'Tea',
      packSize: '100g',
      piecesPerCarton: 10,
      lowStockCartons: 10,
    })
  ).json().id;
  shop = (
    await post(app, admin, '/customers', {
      name: 'Shop A',
      channel: 'wholesaler',
      area: 'Area 25',
      companies: [
        { code: 'LV', paymentTermsDays: 30, creditLimit: 100000 },
        { code: 'ZT', paymentTermsDays: 30 },
      ],
    })
  ).json().id;
  await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo: 'M-1', expiryDate: day(300), cartons: 10 }] });
  await post(app, admin, '/stock/receipts', { companyCode: 'ZT', receivedOn: day(0), lines: [{ productId: tea, batchNo: 'T-1', expiryDate: day(300), cartons: 5 }] });

  // Seller's order: 5 cartons loaded, 3 taken.
  const orderId = (
    await post(app, manager, '/orders', {
      customerId: shop,
      invoiceType: 'vat',
      salesmanId: sellerId,
      lines: [{ productId: milk, cartons: 5, pricePerCarton: 24000 }],
    })
  ).json().id;
  const planId = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json().id;
  const line = (await get(app, manager, `/orders/${orderId}`)).json().lines[0];
  await put(manager, `/loading-plans/${planId}/items`, { items: [{ lineId: line.id, pieces: line.pieces }] });
  await post(app, manager, `/loading-plans/${planId}/send`);
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(200);
  const invoiceId = (await post(app, manager, `/loading-plans/${planId}/invoices`)).json().made[0].id;
  await post(app, store, `/loading-plans/${planId}/gate`);
  const item = (await get(app, driver, `/loading-plans/${planId}`)).json().drops[0].lines[0];
  const delivered = await post(app, driver, `/loading-plans/${planId}/deliveries`, {
    customerId: shop,
    receivedBy: 'Mr Banda',
    signature: PNG,
    lines: [{ itemId: item.itemId, cartons: 3, reason: 'No space in the shop' }],
  });
  expect(delivered.statusCode).toBe(201);

  // The other salesman's order waits in the warehouse.
  const pending = await post(app, manager, '/orders', {
    customerId: shop,
    invoiceType: 'vat',
    salesmanId: otherId,
    lines: [{ productId: tea, cartons: 2, pricePerCarton: 50000 }],
  });
  expect(pending.statusCode).toBe(201);

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
  expect((await post(app, seller, '/expenses', { companyCode: 'LV', amount: 5000, description: 'Fuel' })).statusCode).toBe(201);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('each job sees its own list of reports; a salesman never picks a person, and the warehouse sees no money', async () => {
  const groups = async (token: string) => (await get(app, token, '/reports')).json();
  const all = await groups(admin);
  expect(all.groups.map((g: { key: string }) => g.key)).toEqual(['sales', 'orders', 'delivery', 'invoices', 'receivables', 'cash', 'stock', 'trips', 'control']);
  expect(all.groups.flatMap((g: { reports: unknown[] }) => g.reports)).toHaveLength(REPORTS.length);
  expect(all.people.map((p: { name: string }) => p.name)).toContain('Seller');

  const mine = await groups(seller);
  expect(mine.people).toEqual([]);
  expect(mine.groups.map((g: { key: string }) => g.key)).not.toContain('stock');
  for (const r of mine.groups.flatMap((g: { reports: { filters: string[] }[] }) => g.reports)) expect(r.filters).not.toContain('person');

  const warehouse = await groups(store);
  expect(warehouse.groups.map((g: { key: string }) => g.key)).toEqual(['stock', 'trips', 'control']);
  expect(warehouse.groups[1].reports.map((r: { key: string }) => r.key)).toEqual(['trip-load', 'trip-returns']);
  expect((await report(store, 'sales-daily')).status).toBe(403);
  expect((await report(seller, 'stock-available')).status).toBe(403);
  expect((await report(admin, 'nothing')).status).toBe(404);
});

test('every report runs, on screen, in Excel and as a PDF', { timeout: 60_000 }, async () => {
  for (const r of REPORTS) {
    const query = r.key === 'customer-ledger' ? `company=LV&customer=${shop}` : '';
    const started = Date.now();
    const json = await report(admin, r.key, query);
    if (process.env.REPORT_TIMES) console.log(r.key, Date.now() - started);
    expect(json.status, `${r.key}: ${json.body.message}`).toBe(200);
    expect(json.body.columns.map((c: { key: string }) => c.key)).toEqual(r.columns.map((c) => c.key));
    for (const row of json.body.rows) expect(Object.keys(row)).toEqual(r.columns.map((c) => c.key));
  }
  const xlsx = await get(app, admin, '/reports/sales-daily?format=xlsx');
  expect(xlsx.headers['content-type']).toContain('spreadsheetml');
  expect(xlsx.rawPayload.subarray(0, 2).toString()).toBe('PK');
  const pdf = await get(app, admin, '/reports/stock-movements?format=pdf');
  expect(pdf.headers['content-type']).toBe('application/pdf');
  expect(pdf.rawPayload.subarray(0, 4).toString()).toBe('%PDF');
  expect((await report(admin, 'customer-ledger')).body.message).toBe('Please pick the company.');
});

test('sales add up, and a salesman sees only his own whatever he asks for', async () => {
  const daily = (await report(admin, 'sales-daily')).body;
  // 5 cartons at 24,000 plus 17.5% VAT.
  expect(daily.totals).toEqual({ invoices: 1, subtotal: 120000, vat: 21000, total: 141000 });
  expect(daily.picked).toMatch(/to .*Lucky Ventures/);
  const byProduct = (await report(manager, 'sales-product')).body;
  expect(byProduct.rows).toEqual([{ code: 'LV-MILK', product: 'Test Milk 1L', company: 'LV', qty: '5 ctn', amount: 120000 }]);
  expect((await report(manager, 'low-products')).body.rows.map((r: { code: string }) => r.code)).toEqual(['ZT-TEA', 'LV-MILK']);
  expect((await report(manager, 'sales-route')).body.rows[0].route).toBe('Area 25');

  expect((await report(seller, 'sales-salesman')).body.rows).toEqual([expect.objectContaining({ salesman: 'Seller', total: 141000 })]);
  expect((await report(other, 'sales-salesman', `person=${sellerId}`)).body.rows).toEqual([]);
  expect((await report(other, 'orders-pending')).body.rows).toEqual([
    expect.objectContaining({ salesman: 'Other', stage: 'Open', value: 100000, toDeliver: 100000 }),
  ]);
  expect((await report(manager, 'sales-salesman', `person=${otherId}`)).body.picked).toMatch(/Salesman: Other/);
});

test('other companies stay hidden', async () => {
  expect((await report(kashif, 'sales-daily')).body.rows).toEqual([]);
  expect((await report(kashif, 'sales-daily', 'company=LV')).status).toBe(403);
  expect((await report(manager, 'sales-daily', 'company=ZT')).body.rows).toEqual([]);
  expect((await report(manager, 'sales-daily', 'from=2026-10-20&to=2026-10-01')).body.message).toBe('The start date is after the end date.');
});

test('deliveries, returns, money owed, cash and stock', async () => {
  const short = (await report(manager, 'deliveries-partial')).body.rows;
  expect(short).toEqual([expect.objectContaining({ loaded: '5 ctn', delivered: '3 ctn', short: '2 ctn', reason: 'No space in the shop' })]);
  expect((await report(manager, 'deliveries-done')).body.totals).toEqual({ value: 72000 });
  expect((await report(manager, 'returns')).body.rows).toEqual([expect.objectContaining({ kind: 'Not taken at delivery', state: 'Waiting to be booked', total: 56400 })]);

  // 141,000 less 50,000 paid; the return is not booked yet.
  const owed = (await report(cashier, 'receivables-outstanding')).body.rows;
  expect(owed).toEqual([expect.objectContaining({ customer: 'Shop A', company: 'LV', owes: 91000, overdue: 0, creditLimit: 100000 })]);
  expect((await report(manager, 'credit-limits')).body.rows).toEqual([expect.objectContaining({ owes: 91000, percent: 91, overBy: 0 })]);
  expect((await report(manager, 'receivables-aging')).body.rows[0]).toMatchObject({ notDue: 91000, owes: 91000 });
  const ledger = (await report(seller, 'customer-ledger', `company=LV&customer=${shop}`)).body;
  expect(ledger.rows.at(-1).balance).toBe(91000);

  expect((await report(manager, 'cash-in-hand')).body.rows).toEqual([
    { person: 'Seller', company: 'LV', cash: 50000, withdrawals: 0, floats: 0, expenses: 5000, net: 45000 },
  ]);
  expect((await report(cashier, 'cash-daily')).body.totals).toMatchObject({ cash: 50000, paid: 50000, expenses: 5000 });
  expect((await report(manager, 'cash-by-person')).body.rows).toEqual([expect.objectContaining({ person: 'Seller', cash: 50000, expenses: 5000 })]);

  const stock = (await report(store, 'stock-available')).body.rows;
  // Milk: 10 cartons in, 3 delivered, 2 set aside again for the rest of the order; tea: 2 of 5 cartons set aside.
  expect(stock.map((r: { code: string; shelf: string; free: string }) => [r.code, r.shelf, r.free])).toEqual([
    ['LV-MILK', '7 ctn', '5 ctn'],
    ['ZT-TEA', '5 ctn', '3 ctn'],
  ]);
  expect((await report(store, 'stock-low')).body.rows).toEqual([expect.objectContaining({ code: 'ZT-TEA', free: '3 ctn', level: '10 ctn', short: '7 ctn' })]);
  expect((await report(store, 'stock-reserved')).body.rows).toHaveLength(2);
  expect((await report(admin, 'audit-trail', `person=${sellerId}`)).body.rows.map((r: { action: string }) => r.action)).toContain('Expense Recorded');
});

test('trips: load, sales, what came back and the cash', async () => {
  const planned = await post(app, manager, '/trips', {
    kind: 'upcountry',
    facilityCode: 'F1',
    tripDate: day(0),
    route: 'Salima',
    vehicleId: truck,
    leaderId: sellerId,
    lines: [{ productId: milk, cartons: 2 }],
  });
  expect(planned.statusCode).toBe(201);
  const tripId = planned.json().id;
  expect((await post(app, store, `/trips/${tripId}/load`)).statusCode).toBe(200);
  // The loading plan's drop is done, so only the trip is on the road.
  expect((await report(store, 'stock-on-vehicles')).body.rows).toEqual([expect.objectContaining({ document: 'TRP-000001', vehicle: 'TT 1', qty: '2 ctn' })]);
  const sold = await post(app, seller, `/trips/${tripId}/sales`, {
    requestId: 'r-1',
    customerId: shop,
    stop: 'Salima',
    payment: 'cash',
    receivedBy: 'Mr Banda',
    signature: PNG,
    lines: [{ productId: milk, cartons: 1, pricePerCarton: 25000 }],
  });
  expect(sold.statusCode).toBe(201);
  const closed = await post(app, seller, `/trips/${tripId}/close`, { lines: [{ productId: milk, cartons: 1 }], counted: 29375 });
  expect(closed.json().status).toBe('closed');

  expect((await report(other, 'trip-load')).body.rows).toEqual([]);
  expect((await report(seller, 'trip-load')).body.rows).toEqual([expect.objectContaining({ trip: 'TRP-000001', planned: '2 ctn', loaded: '2 ctn' })]);
  expect((await report(manager, 'trip-sales')).body.rows).toEqual([expect.objectContaining({ stop: 'Salima', payment: 'Cash', total: 29375 })]);
  expect((await report(cashier, 'trip-collections')).body.rows).toEqual([expect.objectContaining({ cash: 29375, credit: 0, total: 29375 })]);
  expect((await report(store, 'trip-returns')).body.rows).toEqual([expect.objectContaining({ sold: '1 ctn', returned: '1 ctn', missing: '0 ctn' })]);
  expect((await report(manager, 'trip-settlement')).body.rows).toEqual([
    expect.objectContaining({ cashSales: 29375, expected: 29375, counted: 29375, difference: 0, missing: 0 }),
  ]);
  expect((await report(manager, 'sales-route', 'channel=wholesaler')).body.rows.map((r: { route: string }) => r.route)).toEqual(['Area 25', 'Salima']);
});

test('targets: set by the managers, shown against sales', async () => {
  expect((await put(seller, '/targets', { salesmanId: sellerId, month: month(), amount: 1 })).statusCode).toBe(403);
  expect((await put(kashif, '/targets', { salesmanId: sellerId, month: month(), amount: 1 })).statusCode).toBe(404);
  const set = await put(manager, '/targets', { salesmanId: sellerId, month: month(), amount: 200000 });
  expect(set.json()).toMatchObject({ name: 'Seller', amount: 200000 });
  const list = (await get(app, manager, `/targets?month=${month()}`)).json();
  expect(list.map((t: { name: string; amount: number | null }) => [t.name, t.amount])).toEqual([
    ['Other', null],
    ['Seller', 200000],
  ]);

  const rows = (await report(manager, 'target-achievement')).body.rows;
  expect(rows).toEqual([expect.objectContaining({ salesman: 'Seller', target: 200000, achieved: 145000, percent: 73, short: 55000 })]);
  expect((await report(other, 'target-achievement')).body.rows).toEqual([]);

  await put(manager, '/targets', { salesmanId: sellerId, month: month(), amount: null });
  expect((await get(app, manager, `/targets?month=${month()}`)).json()[1].amount).toBeNull();
});

test('a low-stock level and a credit limit stay when other fields are edited', async () => {
  expect((await put(admin, `/products/${tea}`, { name: 'Black tea' })).json().lowStockCartons).toBe(10);
  expect((await put(admin, `/products/${tea}`, { lowStockCartons: null })).json().lowStockCartons).toBeNull();
  const edited = await put(admin, `/customers/${shop}`, {
    companies: [
      { code: 'LV', paymentTermsDays: 14 },
      { code: 'ZT', paymentTermsDays: 14, creditLimit: 50000 },
    ],
  });
  expect(edited.json().companies).toEqual([
    { code: 'LV', paymentTermsDays: 14, creditLimit: 100000 },
    { code: 'ZT', paymentTermsDays: 14, creditLimit: 50000 },
  ]);
});
