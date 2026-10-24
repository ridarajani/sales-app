import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let seller: string; // salesman on the order
let store: string; // warehouse
let driver: string;
let accounts: string;
let shop: number;
let invoiceId: number;
let invoiceLineId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const tomorrow = () => new Date(Date.now() + 86_400_000).toLocaleDateString('en-CA', { timeZone: 'Africa/Blantyre' });
const invoice = async () => (await get(app, manager, `/invoices/${invoiceId}`)).json();
const batches = async () =>
  (await get(app, admin, '/stock?q=lv-milk')).json()[0].batches.map((b: { batchNo: string; onHand: number }) => [b.batchNo, b.onHand]);

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir: mkdtempSync(join(tmpdir(), 'branding-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0995000002', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Accounts', phone: '0995000003', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  const sellerId = await addUser({ name: 'Seller', phone: '0995000004', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  const driverId = await addUser({ name: 'Felix', phone: '0995000005', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  [manager, store, accounts, seller, driver] = (await Promise.all(
    ['0995000001', '0995000002', '0995000003', '0995000004', '0995000005'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string, string];
  const truck = (await post(app, admin, '/vehicles', { plate: 'LL 1234', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  const milk = (await post(app, admin, '/products', { code: 'LV-MILK', companyCode: 'LV', brand: 'Test', name: 'Milk', packSize: '1L', piecesPerCarton: 12 }))
    .json().id;
  shop = (await post(app, admin, '/customers', { name: 'Shop A', channel: 'wholesaler', companies: [{ code: 'LV', paymentTermsDays: 30 }] })).json().id;
  for (const [batchNo, expiry, cartons] of [['M-1', day(100), 4], ['M-2', day(300), 6]] as const) {
    await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo, expiryDate: expiry, cartons }] });
  }
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
  await app.inject({
    method: 'PUT',
    url: `/loading-plans/${planId}/items`,
    headers: bearer(manager),
    payload: { items: [{ lineId: line.id, pieces: line.pieces }] },
  });
  await post(app, manager, `/loading-plans/${planId}/send`);
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(200);
  invoiceId = (await post(app, manager, `/loading-plans/${planId}/invoices`)).json().made[0].id;
  await post(app, store, `/loading-plans/${planId}/gate`);
  const item = (await get(app, driver, `/loading-plans/${planId}`)).json().drops[0].lines[0];
  // 5 cartons loaded, 3 taken.
  const r = await post(app, driver, `/loading-plans/${planId}/deliveries`, {
    customerId: shop,
    receivedBy: 'Mr Banda',
    signature: PNG,
    lines: [{ itemId: item.itemId, cartons: 3, reason: 'No space in the shop' }],
  });
  expect(r.statusCode).toBe(201);
  invoiceLineId = (await invoice()).lines[0].lineId;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('goods not taken at delivery become a return to book; booking takes them off the invoice', async () => {
  const iv = await invoice();
  expect(iv).toMatchObject({ total: 141000, balance: 141000, canAdjust: true });
  expect(iv.lines[0]).toMatchObject({ pieces: 60, delivered: 36, returned: 0, canReturn: 36 });
  expect(iv.returns).toEqual([expect.objectContaining({ number: 'LV-SR-000001', kind: 'delivery', total: 56400, booked: false, goods: 'received' })]);
  const id = iv.returns[0].id;
  const r = (await get(app, manager, `/returns/${id}`)).json();
  expect(r).toMatchObject({ reason: 'No space in the shop', subtotal: 48000, vat: 8400, canBook: true, canReceive: false, deliveryNoteNo: 'DN-000001' });
  expect(r.lines).toEqual([expect.objectContaining({ pieces: 24, quantity: '2 ctn', amount: 48000, condition: 'good' })]);
  expect((await get(app, manager, '/returns?status=to_book')).json()).toHaveLength(1);

  expect((await post(app, seller, `/returns/${id}/book`)).statusCode).toBe(403);
  expect((await post(app, manager, `/returns/${id}/book`)).json()).toMatchObject({ booked: true, bookedBy: 'Manager', canBook: false });
  expect((await post(app, manager, `/returns/${id}/book`)).json().error).toBe('already_booked');
  expect((await invoice()).balance).toBe(84600);
  expect((await get(app, manager, '/returns?status=to_book')).json()).toHaveLength(0);
});

test('goods given back after delivery are booked at once; the warehouse puts the good ones back on the shelf', async () => {
  const give = (cartons: number, extra: Record<string, unknown> = {}) =>
    post(app, manager, `/invoices/${invoiceId}/returns`, { lines: [{ invoiceLineId, cartons }], reason: 'Damaged goods', ...extra });
  expect((await give(4)).json().message).toBe('Test Milk 1L: at most 3 ctn can come back.');
  expect((await give(0)).json().message).toBe('Please type what came back.');
  expect((await post(app, store, `/invoices/${invoiceId}/returns`, { lines: [{ invoiceLineId, cartons: 1 }], reason: 'Other' })).statusCode).toBe(403);

  const first = await give(1, { requestId: 'form-1' });
  expect(first.statusCode).toBe(201);
  expect(first.json()).toMatchObject({ number: 'LV-SR-000002', kind: 'after', total: 28200, booked: true, goods: 'waiting', canReceive: false });
  const again = await give(1, { requestId: 'form-1' });
  expect(again.statusCode).toBe(200);
  expect(again.json().number).toBe('LV-SR-000002');
  const second = (await give(1)).json();
  expect((await invoice()).balance).toBe(28200);
  expect((await invoice()).lines[0]).toMatchObject({ returned: 24, canReturn: 12 });

  // The warehouse sees what to receive, but not what it cost.
  const list = (await get(app, store, '/returns?status=waiting')).json();
  expect(list.map((x: { number: string }) => x.number)).toEqual(['LV-SR-000003', 'LV-SR-000002']);
  expect(list[0].total).toBeUndefined();
  const seen = (await get(app, store, `/returns/${first.json().id}`)).json();
  expect(seen).toMatchObject({ canReceive: true, canBook: false });
  expect(seen.total).toBeUndefined();
  expect(seen.lines[0].amount).toBeUndefined();

  expect(await batches()).toEqual([['M-1', 12], ['M-2', 72]]);
  expect((await post(app, manager, `/returns/${first.json().id}/receive`)).statusCode).toBe(403);
  expect((await post(app, store, `/returns/${first.json().id}/receive`)).json()).toMatchObject({ goods: 'received', receivedBy: 'Store' });
  expect((await post(app, store, `/returns/${first.json().id}/receive`)).json().error).toBe('already_received');
  const damaged = await post(app, store, `/returns/${second.id}/receive`, { damaged: [second.lines[0].id] });
  expect(damaged.json().lines[0].condition).toBe('damaged');
  // The good carton went back into M-1, where it was delivered from; the damaged one did not.
  expect(await batches()).toEqual([['M-1', 24], ['M-2', 72]]);
  const m1 = (await get(app, admin, '/stock?q=lv-milk')).json()[0].batches[0];
  expect((await get(app, admin, `/stock/batches/${m1.id}/movements`)).json()[0]).toMatchObject({
    kind: 'returned',
    change: 12,
    documentNumber: 'LV-SR-000002',
  });
});

test('credit and debit notes change what the invoice owes, numbered by kind', async () => {
  const note = (body: Record<string, unknown>, token = accounts) => post(app, token, `/invoices/${invoiceId}/notes`, { reason: 'Price difference', ...body });
  const cn = await note({ kind: 'credit_issued', amount: 5000 });
  expect(cn.statusCode).toBe(201);
  expect(cn.json()).toMatchObject({ number: 'LV-CN-000001', owesChange: -5000, invoiceBalance: 23200, label: 'Credit note issued' });
  expect((await note({ kind: 'debit_received', amount: 1000 })).json().message).toBe('Please type the number on the customer’s note.');
  expect((await note({ kind: 'debit_issued', amount: 2000 })).json()).toMatchObject({ number: 'LV-DN-000001', owesChange: 2000, invoiceBalance: 25200 });
  expect((await note({ kind: 'credit_issued', amount: 100 }, seller)).statusCode).toBe(403);
  expect((await note({ kind: 'credit_issued', amount: 0 })).json().message).toBe('Please type the amount.');

  const iv = await invoice();
  expect(iv.notes.map((n: { number: string }) => n.number)).toEqual(['LV-CN-000001', 'LV-DN-000001']);
  expect(iv.balance).toBe(25200);
  expect((await get(app, seller, '/notes')).json()).toHaveLength(2);
  expect((await get(app, store, '/notes')).statusCode).toBe(403);
  expect((await get(app, accounts, '/notes?kind=debit_issued')).json().map((n: { number: string }) => n.number)).toEqual(['LV-DN-000001']);
});

test('the ledger lists every movement with a running balance; the statement prints as PDF', async () => {
  const pay = await post(app, seller, '/collections', {
    customerId: shop,
    companyCode: 'LV',
    invoiceId,
    mode: 'cash',
    amount: 10000,
    receivedBy: 'Mr Banda',
    signature: PNG,
  });
  expect(pay.statusCode).toBe(201);
  const l = (await get(app, manager, `/customers/${shop}/ledger?company=LV`)).json();
  expect(l.rows.map((r: { document: string; charged: number; paid: number; balance: number }) => [r.document, r.charged, r.paid, r.balance])).toEqual([
    ['LV-INV-000001', 141000, 0, 141000],
    ['LV-SR-000001', 0, 56400, 84600],
    ['LV-SR-000002', 0, 28200, 56400],
    ['LV-SR-000003', 0, 28200, 28200],
    ['LV-CN-000001', 0, 5000, 23200],
    ['LV-DN-000001', 2000, 0, 25200],
    ['LV-RCT-000001', 0, 10000, 15200],
  ]);
  expect(l).toMatchObject({ opening: 0, charged: 143000, paid: 127800, closing: 15200, owes: 15200, overdue: 0 });
  expect(l.rows[1].what).toBe('Sales return · LV-INV-000001 · not taken at delivery');

  const later = (await get(app, manager, `/customers/${shop}/ledger?company=LV&from=${tomorrow()}`)).json();
  expect(later).toMatchObject({ opening: 15200, rows: [], closing: 15200 });
  expect((await get(app, manager, `/customers/${shop}/ledger?company=ZF`)).statusCode).toBe(403);
  expect((await get(app, manager, `/customers/${shop}/ledger`)).json().message).toBe('Please pick the company.');
  expect((await get(app, store, `/customers/${shop}/ledger?company=LV`)).statusCode).toBe(403);

  const pdf = await get(app, accounts, `/customers/${shop}/statement.pdf?company=LV`);
  expect(pdf.statusCode).toBe(200);
  expect(pdf.headers['content-type']).toBe('application/pdf');
  expect(pdf.rawPayload.subarray(0, 4).toString()).toBe('%PDF');

  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(expect.arrayContaining(['sales_return_booked', 'returned_goods_received', 'invoice_note_made']));
});
