import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature and the proof.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV; holds the Airtel wallet
let managerId: number;
let seller: string;
let accounts: string;
let cashier: string;
let shop: number;
let invoiceId: number;
let onAccountPayment: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const balance = async () => (await get(app, manager, `/invoices/${invoiceId}`)).json().balance;
const payment = async (id: number) => (await get(app, accounts, `/collections/${id}`)).json();
const collect = (token: string, body: Record<string, unknown>) =>
  post(app, token, '/collections', { customerId: shop, companyCode: 'LV', mode: 'cash', receivedBy: 'Mr Phiri', signature: PNG, ...body });
const cancel = (token: string, id: number, reason?: string) => post(app, token, `/collections/${id}/cancel`, reason === undefined ? {} : { reason });

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir: mkdtempSync(join(tmpdir(), 'branding-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  managerId = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV'] });
  await addUser({ name: 'Store', phone: '0995000002', role: 'warehouse', companies: ['LV'] });
  await addUser({ name: 'Accounts', phone: '0995000003', role: 'accounts', companies: ['LV'] });
  const sellerId = await addUser({ name: 'Seller', phone: '0995000004', role: 'salesman', managerId, companies: ['LV'] });
  await addUser({ name: 'Cashier', phone: '0995000005', role: 'cashier', companies: ['LV'] });
  const driverId = await addUser({ name: 'Felix', phone: '0995000006', role: 'driver', companies: ['LV'] });
  let store: string;
  [manager, store, accounts, seller, cashier] = (await Promise.all(
    ['0995000001', '0995000002', '0995000003', '0995000004', '0995000005'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string, string];
  const truck = (await post(app, admin, '/vehicles', { plate: 'LL 1234', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  const milk = (await post(app, admin, '/products', { code: 'LV-MILK', companyCode: 'LV', brand: 'Test', name: 'Milk', packSize: '1L', piecesPerCarton: 12 }))
    .json().id;
  shop = (await post(app, admin, '/customers', { name: 'Shop A', channel: 'wholesaler', companies: [{ code: 'LV', paymentTermsDays: 30 }] })).json().id;
  await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo: 'M-1', expiryDate: day(200), cartons: 5 }] });
  const orderId = (
    await post(app, manager, '/orders', { customerId: shop, invoiceType: 'vat', salesmanId: sellerId, lines: [{ productId: milk, cartons: 2, pricePerCarton: 24000 }] })
  ).json().id;

  // The shop pays before anything is invoiced: it all stays on account.
  const paid = await collect(seller, { amount: 60000 });
  expect(paid.json()).toMatchObject({ onAccount: 60000, allocations: [] });
  onAccountPayment = paid.json().id;

  const planId = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json().id;
  const line = (await get(app, manager, `/orders/${orderId}`)).json().lines[0];
  await app.inject({ method: 'PUT', url: `/loading-plans/${planId}/items`, headers: bearer(manager), payload: { items: [{ lineId: line.id, pieces: line.pieces }] } });
  await post(app, manager, `/loading-plans/${planId}/send`);
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(200);
  invoiceId = (await post(app, manager, `/loading-plans/${planId}/invoices`)).json().made[0].id;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('money paid on account goes on the next invoice', async () => {
  expect(await balance()).toBe(0);
  expect(await payment(onAccountPayment)).toMatchObject({ onAccount: 3600, allocations: [{ invoiceId, amount: 56400 }] });
});

test('a debit note takes money on account', async () => {
  const dn = await post(app, accounts, `/invoices/${invoiceId}/notes`, { kind: 'debit_issued', amount: 2000, reason: 'Price difference' });
  expect(dn.json()).toMatchObject({ number: 'LV-DN-000001', invoiceBalance: 0 });
  expect((await payment(onAccountPayment)).onAccount).toBe(1600);
});

test('Accounts cancels a payment made by mistake while its cash is still with the collector', async () => {
  expect((await payment(onAccountPayment)).canCancel).toBe(true);
  expect((await get(app, seller, `/collections/${onAccountPayment}`)).json().canCancel).toBe(false);
  expect((await cancel(seller, onAccountPayment, 'Wrong shop')).statusCode).toBe(403);
  expect((await cancel(accounts, onAccountPayment)).json().message).toBe('Please say why the payment is cancelled.');
  expect((await get(app, seller, '/cash/mine')).json().net).toBe(60000);

  const r = await cancel(accounts, onAccountPayment, 'Typed for the wrong shop');
  expect(r.statusCode).toBe(200);
  expect(r.json()).toMatchObject({ cancelled: true, cancelledBy: 'Accounts', cancelReason: 'Typed for the wrong shop', custody: 'na', onAccount: 0, allocations: [], canCancel: false });
  expect((await cancel(accounts, onAccountPayment, 'Again')).json().error).toBe('already_cancelled');
  expect(await balance()).toBe(58400);
  expect((await get(app, seller, '/cash/mine')).json()).toMatchObject({ net: 0, collections: [] });
  expect((await get(app, accounts, '/collections')).json()[0]).toMatchObject({ id: onAccountPayment, cancelled: true });

  const l = (await get(app, accounts, `/customers/${shop}/ledger?company=LV`)).json();
  expect(l.rows.map((x: { document: string; what: string; charged: number; paid: number }) => [x.document, x.what, x.charged, x.paid])).toEqual([
    ['LV-RCT-000001', 'Payment · Cash', 0, 60000],
    ['LV-INV-000001', 'Invoice · delivery note DN-000001', 56400, 0],
    ['LV-DN-000001', 'Debit note · LV-INV-000001 · Price difference', 2000, 0],
    ['LV-RCT-000001', 'Payment cancelled · Typed for the wrong shop', 60000, 0],
  ]);
  expect(l).toMatchObject({ closing: 58400, owes: 58400 });
});

test('once the cash is with the cashier, a payment cannot be cancelled; cash the cashier took can', async () => {
  const handed = (await collect(seller, { amount: 1000 })).json();
  expect((await post(app, seller, '/handovers')).statusCode).toBe(201);
  expect((await payment(handed.id)).canCancel).toBe(false);
  expect((await cancel(accounts, handed.id, 'Mistake')).json().error).toBe('handed_over');

  const atOffice = (await collect(cashier, { amount: 500 })).json();
  expect(atOffice).toMatchObject({ custody: 'counted', canCancel: false });
  expect((await payment(atOffice.id)).canCancel).toBe(true);
  expect((await cancel(admin, atOffice.id, 'Typed twice')).json()).toMatchObject({ cancelled: true, custody: 'counted' });
});

test('a waiting Airtel payment leaves the wallet when cancelled; a ticked one cannot be cancelled', async () => {
  const wallet = (await post(app, admin, '/airtel/wallets', { holderId: managerId, companies: ['LV'] })).json().id;
  const airtel = (amount: number) => collect(seller, { mode: 'airtel', amount, reference: `AM${amount}`, proof: PNG });
  const first = (await airtel(5000)).json();
  expect((await get(app, manager, `/airtel/wallets/${wallet}`)).json()).toMatchObject({ balance: 5000, waiting: 1 });
  expect((await cancel(accounts, first.id, 'Reference was for another shop')).json().cancelled).toBe(true);
  expect((await get(app, manager, `/airtel/wallets/${wallet}`)).json()).toMatchObject({ balance: 0, waiting: 0, entries: [] });

  const second = (await airtel(3000)).json();
  const entry = (await get(app, manager, `/airtel/wallets/${wallet}`)).json().entries[0].id;
  await post(app, manager, `/airtel/entries/${entry}/tick`);
  expect((await cancel(accounts, second.id, 'Mistake')).json().error).toBe('airtel_ticked');
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toContain('payment_cancelled');
});
