import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature and the photos.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT, holds the wallet
let managerId: number;
let seller: string; // salesman, LV and ZT
let cashier: string;
let other: string; // sales manager, ZF and GL
let shop: number;
let walletId: number;

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
const put = (token: string, url: string, payload: Record<string, unknown>) =>
  app.inject({ method: 'PUT', url, headers: bearer(token), payload });
const wallet = async (token = manager) => (await get(app, token, `/airtel/wallets/${walletId}`)).json();

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  managerId = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Seller', phone: '0995000002', role: 'salesman', managerId, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Cashier', phone: '0995000004', role: 'cashier', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  await addUser({ name: 'Other', phone: '0995000006', role: 'sales_manager', companies: ['ZF', 'GL'] });
  [manager, seller, cashier, other] = (await Promise.all(
    ['0995000001', '0995000002', '0995000004', '0995000006'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string];
  shop = (
    await post(app, admin, '/customers', {
      name: 'Shop A',
      channel: 'wholesaler',
      companies: [{ code: 'LV', paymentTermsDays: 0 }, { code: 'ZT', paymentTermsDays: 0 }],
    })
  ).json().id;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('the admin gives a sales manager one wallet for their companies', async () => {
  const body = { holderId: managerId, openingBalance: 20000, companies: ['LV', 'ZT'] };
  expect((await post(app, manager, '/airtel/wallets', body)).statusCode).toBe(403);
  const r = await post(app, admin, '/airtel/wallets', body);
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ holderName: 'Manager', balance: 20000, companies: ['LV', 'ZT'], waiting: 0, entries: [] });
  walletId = r.json().id;
  expect((await post(app, admin, '/airtel/wallets', body)).json().message).toBe('LV already has an Airtel Money wallet.');
  expect((await post(app, admin, '/airtel/wallets', { ...body, companies: ['ZF'] })).json().message).toBe(
    'That person already holds a wallet.',
  );
  expect((await get(app, other, '/airtel/wallets')).json()).toEqual([]);
  expect((await get(app, cashier, '/airtel/wallets')).json()).toHaveLength(1);
  expect((await get(app, seller, '/airtel/wallets')).statusCode).toBe(403);
  expect((await put(admin, `/airtel/wallets/${walletId}`, { ...body, openingBalance: 25000 })).json().balance).toBe(25000);
});

test('an Airtel payment waits in the wallet until the holder ticks it', async () => {
  const r = await post(app, seller, '/collections', {
    customerId: shop,
    companyCode: 'LV',
    mode: 'airtel',
    amount: 15000,
    reference: 'AM123456',
    proof: PNG,
    receivedBy: 'Mr Phiri',
    signature: PNG,
  });
  expect(r.statusCode).toBe(201);
  const w = await wallet();
  expect(w).toMatchObject({ balance: 40000, waiting: 1, canAct: true });
  expect(w.entries[0]).toMatchObject({
    kind: 'payment',
    credit: 15000,
    status: 'waiting',
    reference: 'AM123456',
    description: `Shop A · ${r.json().number}`,
    balanceAfter: 40000,
  });
  const entry = w.entries[0].id;
  expect((await wallet(cashier)).canAct).toBe(false);
  expect(w.canTakeOut).toBe(true);
  expect((await wallet(admin)).canTakeOut).toBe(false);
  expect((await post(app, cashier, `/airtel/entries/${entry}/tick`)).statusCode).toBe(403);
  expect((await post(app, manager, `/airtel/entries/${entry}/tick`)).statusCode).toBe(200);
  expect((await post(app, manager, `/airtel/entries/${entry}/tick`)).json().error).toBe('already_ticked');
  expect((await wallet()).waiting).toBe(0);
});

test('only the holder pays expenses from the wallet', async () => {
  const body = { companyCode: 'ZT', amount: 1000, description: 'Airtime', mode: 'airtel' };
  expect((await post(app, seller, '/expenses', body)).json().message).toBe('Only Manager can pay from this Airtel Money wallet. Please pay in cash.');
  const e = await post(app, manager, '/expenses', body);
  expect(e.json()).toMatchObject({ mode: 'airtel', custody: 'na' });
  expect((await wallet()).entries[0]).toMatchObject({ kind: 'expense', debit: 1000, status: 'ok', companyCode: 'ZT', balanceAfter: 39000 });
});

test('money taken out of the wallet is cash the holder hands to the cashier', async () => {
  const take = (token: string, amount: number, charge = 0) =>
    post(app, token, `/airtel/wallets/${walletId}/withdrawals`, { companyCode: 'LV', amount, charge });
  expect((await take(admin, 1000)).statusCode).toBe(403);
  expect((await take(manager, 39000, 500)).json().message).toBe('The wallet only has MWK 39,000.');
  const r = await take(manager, 30000, 400);
  expect(r.statusCode).toBe(201);
  expect(r.json().balance).toBe(8600);
  expect(r.json().entries.slice(0, 2).map((x: { kind: string }) => x.kind).sort()).toEqual(['charge', 'withdrawal']);

  const mine = (await get(app, manager, '/cash/mine')).json();
  expect(mine).toMatchObject({ cash: 0, withdrawalsTotal: 30000, expensesTotal: 0, net: 30000 });
  const h = await post(app, manager, '/handovers');
  expect(h.statusCode).toBe(201);
  expect(h.json()).toMatchObject({ withdrawals: 30000, net: 30000 });
  expect(h.json().companies).toEqual([{ code: 'LV', cash: 0, withdrawals: 30000, expenses: 0, net: 30000 }]);
  expect((await get(app, other, '/handovers')).json()).toEqual([]);
  expect((await post(app, cashier, `/handovers/${h.json().id}/count`, { counted: 30000 })).json().status).toBe('counted');
  const w = await wallet();
  expect(w.entries.find((x: { kind: string }) => x.kind === 'withdrawal').custody).toBe('counted');
});

test('the holder checks the real balance and any difference is kept', async () => {
  const r = await post(app, manager, `/airtel/wallets/${walletId}/checks`, { actual: 8000, note: 'Fee not recorded' });
  expect(r.statusCode).toBe(201);
  expect(r.json().checks[0]).toMatchObject({ actual: 8000, ledger: 8600, difference: -600, by: 'Manager' });
  expect((await post(app, cashier, `/airtel/wallets/${walletId}/checks`, { actual: 8000 })).statusCode).toBe(403);
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(
    expect.arrayContaining(['airtel_wallet_set_up', 'airtel_wallet_changed', 'airtel_payment_ticked', 'airtel_withdrawal', 'airtel_wallet_checked']),
  );
});
