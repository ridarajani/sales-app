import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature and the photos.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let seller: string; // salesman, LV and ZT
let accounts: string;
let cashier: string;
let store: string;
let shop: number; // buys from LV and ZT; owes LV 10,000 from before the app
let inv1: number; // LV, 50,000, older
let inv2: number; // LV, 30,000, newer
let inv3: number; // ZT, 100,000

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
async function invoice(number: string, companyCode: string, total: number, date: string) {
  const { rows } = await db.query<{ id: number }>(
    `insert into invoices (number, company_code, customer_id, invoice_type, invoice_date, due_date, subtotal, vat, total, created_by)
     values ($1, $2, $3, 'plain', $4, $4::date + 30, $5, 0, $5, (select id from users where role = 'admin' limit 1)) returning id`,
    [number, companyCode, shop, date, total],
  );
  return rows[0]!.id;
}
const collect = (token: string, body: Record<string, unknown>) =>
  post(app, token, '/collections', { customerId: shop, companyCode: 'LV', mode: 'cash', receivedBy: 'Mr Phiri', signature: PNG, ...body });
const balanceOf = async (id: number) => (await get(app, manager, `/invoices/${id}`)).json().balance;

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Seller', phone: '0995000002', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Accounts', phone: '0995000003', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  await addUser({ name: 'Cashier', phone: '0995000004', role: 'cashier', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  await addUser({ name: 'Store', phone: '0995000005', role: 'warehouse', companies: ['LV', 'ZT'] });
  [manager, seller, accounts, cashier, store] = (await Promise.all(
    ['0995000001', '0995000002', '0995000003', '0995000004', '0995000005'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string, string];
  shop = (
    await post(app, admin, '/customers', {
      name: 'Shop A',
      channel: 'wholesaler',
      companies: [
        { code: 'LV', paymentTermsDays: 30 },
        { code: 'ZT', paymentTermsDays: 30 },
      ],
    })
  ).json().id;
  const ob = await app.inject({
    method: 'PUT',
    url: `/customers/${shop}/opening-balance`,
    headers: { authorization: `Bearer ${accounts}` },
    payload: { companyCode: 'LV', amount: 10000, asOf: '2026-10-01' },
  });
  expect(ob.statusCode).toBe(200);
  inv1 = await invoice('LV-INV-000001', 'LV', 50000, '2026-10-10');
  inv2 = await invoice('LV-INV-000002', 'LV', 30000, '2026-10-20');
  inv3 = await invoice('ZT-INV-000001', 'ZT', 100000, '2026-10-20');
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('the collect screen shows what the customer owes each company and the invoices still open', async () => {
  const r = await get(app, seller, `/collections/customer/${shop}`);
  expect(r.statusCode).toBe(200);
  const lv = r.json().companies.find((c: { code: string }) => c.code === 'LV');
  expect(lv).toMatchObject({ owes: 90000, openingLeft: 10000 });
  expect(lv.invoices.map((i: { number: string; balance: number }) => [i.number, i.balance])).toEqual([
    ['LV-INV-000001', 50000],
    ['LV-INV-000002', 30000],
  ]);
  expect(r.json().signatureNeeded).toBe(true);
  expect((await get(app, accounts, `/collections/customer/${shop}`)).json().signatureNeeded).toBe(false);
  expect((await get(app, store, `/collections/customer/${shop}`)).statusCode).toBe(403);
});

test('a payment needs the customer’s signature, and proof for a transfer', async () => {
  const fail = async (token: string, body: Record<string, unknown>) => (await collect(token, body)).json().message;
  expect(await fail(seller, { amount: 1000, signature: undefined })).toBe('Please ask the customer to sign.');
  expect(await fail(seller, { amount: 1000, receivedBy: '' })).toBe('Please type the name of the person who paid.');
  expect(await fail(seller, { amount: 0 })).toBe('Please type the amount received.');
  expect(await fail(seller, { amount: 1000, mode: 'airtel', proof: PNG })).toBe('Please type the Airtel Money reference.');
  expect(await fail(seller, { amount: 1000, mode: 'bank' })).toBe('Please add a photo of the proof of payment.');
  expect(await fail(seller, { amount: 1000, discount: 500 })).toBe('Pick the invoice the discount or tax is for.');
  expect(await fail(seller, { amount: 1000, invoiceId: inv2, discount: 30001 })).toBe(
    'The discount and tax are more than invoice LV-INV-000002 owes (MWK 30,000).',
  );
  expect(await fail(seller, { amount: 1000, invoiceId: inv3 })).toBe('That invoice is not this customer’s for that company.');
  expect((await collect(seller, { amount: 1000, companyCode: 'ZF' })).statusCode).toBe(403);
});

test('a payment goes on the invoice picked, then the opening balance, then the oldest invoice', async () => {
  const r = await collect(seller, { amount: 55000, invoiceId: inv2, discount: 2000 });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({
    number: 'LV-RCT-000001',
    custody: 'with',
    collectedBy: 'Seller',
    receivedBy: 'Mr Phiri',
    hasSignature: true,
    onAccount: 0,
    owesNow: 33000,
  });
  expect(r.json().allocations).toEqual([
    { invoiceId: inv2, invoiceNumber: 'LV-INV-000002', amount: 28000 },
    { invoiceId: null, invoiceNumber: null, amount: 10000 },
    { invoiceId: inv1, invoiceNumber: 'LV-INV-000001', amount: 17000 },
  ]);
  expect(await balanceOf(inv2)).toBe(0);
  expect(await balanceOf(inv1)).toBe(33000);
  expect((await get(app, manager, `/invoices/${inv2}`)).json()).toMatchObject({ discount: 2000, payments: [expect.objectContaining({ amount: 28000 })] });
  expect((await collect(seller, { amount: 100, invoiceId: inv2 })).json().error).toBe('invoice_paid');

  const sig = await get(app, manager, `/collections/${r.json().id}/signature`);
  expect(sig.headers['content-type']).toBe('image/png');
  expect((await get(app, manager, `/collections/${r.json().id}/proof`)).statusCode).toBe(404);
});

test('Accounts records a bank transfer without a signature; money over what is owed stays on account', async () => {
  const r = await collect(accounts, { amount: 40000, mode: 'bank', reference: 'NBM 5521', proof: PNG, signature: undefined, receivedBy: '' });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ number: 'LV-RCT-000002', custody: 'na', hasProof: true, onAccount: 7000, owesNow: -7000 });
  expect(await balanceOf(inv1)).toBe(0);
  // Withholding tax on the ZT invoice, with the certificate number; ZT has its own receipt series.
  const z = await collect(manager, { companyCode: 'ZT', invoiceId: inv3, amount: 97000, wht: 3000, whtCertificate: 'WHT-77' });
  expect(z.json()).toMatchObject({ number: 'ZT-RCT-000001', wht: 3000, whtCertificate: 'WHT-77', owesNow: 0 });
  const booking = (await get(app, manager, `/orders/booking/${shop}`)).json();
  expect(booking.companies.map((c: { code: string; balance: number }) => [c.code, c.balance])).toEqual([
    ['LV', -7000],
    ['ZT', 0],
  ]);
});

test('salesmen see only the payments they collected', async () => {
  const numbers = async (token: string, query = '') =>
    (await get(app, token, `/collections${query}`)).json().map((c: { number: string }) => c.number);
  expect(await numbers(seller)).toEqual(['LV-RCT-000001']);
  expect(await numbers(accounts)).toEqual(['ZT-RCT-000001', 'LV-RCT-000002', 'LV-RCT-000001']);
  expect(await numbers(accounts, '?mode=bank')).toEqual(['LV-RCT-000002']);
  expect(await numbers(accounts, '?q=nbm')).toEqual(['LV-RCT-000002']);
  expect((await get(app, store, '/collections')).statusCode).toBe(403);
});

test('cash expenses come off the cash handed to the cashier', async () => {
  const e = await post(app, seller, '/expenses', { companyCode: 'LV', amount: 5000, description: 'Fuel for the van', photo: PNG });
  expect(e.statusCode).toBe(201);
  expect(e.json()).toMatchObject({ number: 'LV-EXP-000001', mode: 'cash', custody: 'with', hasPhoto: true, spentBy: 'Seller' });
  const airtel = await post(app, seller, '/expenses', { companyCode: 'ZT', amount: 1000, description: 'Airtime', mode: 'airtel' });
  expect(airtel.json().message).toBe('This company has no Airtel Money wallet yet. Please pay in cash.');
  expect((await post(app, seller, '/expenses', { companyCode: 'LV', amount: 100, description: ' ' })).json().message).toBe('Please say what it was for.');
  expect((await post(app, cashier, '/expenses', { companyCode: 'LV', amount: 100, description: 'x' })).statusCode).toBe(403);

  const mine = (await get(app, seller, '/cash/mine')).json();
  expect(mine).toMatchObject({ cash: 55000, expensesTotal: 5000, net: 50000, companies: [{ code: 'LV', cash: 55000, expenses: 5000, net: 50000 }] });
  expect((await get(app, seller, '/expenses')).json()).toHaveLength(1);
  expect((await get(app, manager, '/expenses')).json()).toHaveLength(0);
  expect((await get(app, cashier, '/expenses')).json()).toHaveLength(1);
});

test('the salesman hands over one total; the cashier counts it and any difference is kept', async () => {
  const h = await post(app, seller, '/handovers');
  expect(h.statusCode).toBe(201);
  expect(h.json()).toMatchObject({ number: 'HO-000001', cash: 55000, expenses: 5000, net: 50000, status: 'handed', canCount: false });
  expect(h.json().companies).toEqual([{ code: 'LV', cash: 55000, withdrawals: 0, floats: 0, expenses: 5000, net: 50000 }]);
  expect((await post(app, seller, '/handovers')).json().error).toBe('nothing_to_hand_over');
  expect((await get(app, seller, '/cash/mine')).json()).toMatchObject({ net: 0, collections: [] });

  const id = h.json().id;
  expect((await get(app, cashier, '/handovers?status=handed')).json().map((x: { number: string }) => x.number)).toEqual(['HO-000001']);
  expect((await get(app, cashier, `/handovers/${id}`)).json().canCount).toBe(true);
  expect((await post(app, seller, `/handovers/${id}/count`, { counted: 50000 })).statusCode).toBe(403);
  expect((await post(app, cashier, `/handovers/${id}/count`, { counted: 49000 })).json().message).toBe(
    'The count is MWK 1,000 short. Please write a note.',
  );
  const counted = await post(app, cashier, `/handovers/${id}/count`, { counted: 49000, note: 'One note missing' });
  expect(counted.json()).toMatchObject({ status: 'counted', counted: 49000, difference: -1000, countedBy: 'Cashier' });
  expect((await post(app, cashier, `/handovers/${id}/count`, { counted: 49000 })).json().error).toBe('already_counted');
  const col = (await get(app, accounts, '/collections?q=LV-RCT-000001')).json()[0];
  expect(col.custody).toBe('counted');
  expect((await get(app, manager, '/handovers')).json()).toEqual([]);
});

test('cash taken by the cashier is counted at once', async () => {
  const r = await post(app, cashier, '/collections', {
    customerId: shop,
    companyCode: 'ZT',
    mode: 'cash',
    amount: 5000,
    receivedBy: 'Mr Phiri',
    signature: PNG,
  });
  expect(r.json()).toMatchObject({ custody: 'counted', onAccount: 5000 });
  expect((await get(app, cashier, '/cash/mine')).statusCode).toBe(403);
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(expect.arrayContaining(['payment_collected', 'expense_recorded', 'cash_handed_over', 'cash_counted']));
});

test('a payment or expense sent twice with the same request id is saved once', async () => {
  const body = { companyCode: 'ZT', amount: 2000, requestId: 'form-1' };
  const first = await collect(seller, body);
  expect(first.statusCode).toBe(201);
  const again = await collect(seller, body);
  expect(again.statusCode).toBe(200);
  expect(again.json().number).toBe(first.json().number);
  const both = await Promise.all([collect(seller, { ...body, requestId: 'form-2' }), collect(seller, { ...body, requestId: 'form-2' })]);
  expect(new Set(both.map((r) => r.json().number)).size).toBe(1);
  expect(both.map((r) => r.statusCode).sort()).toEqual([200, 201]);

  const expense = { companyCode: 'LV', amount: 300, description: 'Water', requestId: 'form-3' };
  const e1 = await post(app, seller, '/expenses', expense);
  const e2 = await post(app, seller, '/expenses', expense);
  expect([e1.statusCode, e2.statusCode]).toEqual([201, 200]);
  expect(e2.json().number).toBe(e1.json().number);
});
