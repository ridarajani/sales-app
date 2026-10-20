import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let store1: string; // warehouse, Facility 1 (LV and ZT)
let store2: string; // warehouse, Facility 2 (ZF and GL)
let manager: string;
let salesman: string;
let accounts: string;
let milk: number; // LV, 12 tins per carton
let tea: number; // ZT, 40 sachets per carton
let cookies: number; // GL

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
async function addProduct(code: string, companyCode: string, piecesPerCarton: number) {
  const r = await post(app, admin, '/products', { code, companyCode, brand: 'Test', name: code, packSize: 'x', piecesPerCarton });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
const receive = (token: string, companyCode: string, lines: Record<string, unknown>[], extra: Record<string, unknown> = {}) =>
  post(app, token, '/stock/receipts', { companyCode, receivedOn: day(0), supplier: 'Factory', lines, ...extra });

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  await addUser({ name: 'Store One', phone: '0993000001', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store Two', phone: '0993000002', role: 'warehouse', companies: ['ZF', 'GL'] });
  await addUser({ name: 'Boss', phone: '0993000003', role: 'management', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  const sm = await addUser({ name: 'Manager', phone: '0993000004', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Seller', phone: '0993000005', role: 'salesman', managerId: sm, companies: ['LV'] });
  await addUser({ name: 'Books', phone: '0993000006', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  store1 = (await login(app, '0993000001', '1111')).token;
  store2 = (await login(app, '0993000002', '1111')).token;
  manager = (await login(app, '0993000003', '1111')).token;
  salesman = (await login(app, '0993000005', '1111')).token;
  accounts = (await login(app, '0993000006', '1111')).token;
  milk = await addProduct('LV-MILK', 'LV', 12);
  tea = await addProduct('ZT-TEA', 'ZT', 40);
  cookies = await addProduct('GL-COOK', 'GL', 10);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('warehouse receives goods in cartons and pieces, numbered per company', async () => {
  const r = await receive(store1, 'LV', [
    { productId: milk, batchNo: 'b-100', expiryDate: day(300), cartons: 10, pieces: 4 },
    { productId: milk, batchNo: 'B-200', expiryDate: day(60), cartons: 2 },
  ], { reference: 'DN 5521' });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ number: 'LV-GRN-000001', reference: 'DN 5521', receivedBy: 'Store One' });
  expect(r.json().lines.map((l: { batchNo: string; pieces: number }) => [l.batchNo, l.pieces])).toEqual([
    ['B-100', 124],
    ['B-200', 24],
  ]);
  expect((await receive(store1, 'ZT', [{ productId: tea, batchNo: 'T1', expiryDate: day(400), cartons: 5 }])).json().number).toBe('ZT-GRN-000001');

  const stock = (await get(app, store1, '/stock')).json();
  const m = stock.find((s: { code: string }) => s.code === 'LV-MILK');
  expect(m).toMatchObject({ onHand: 148, cartons: 12, loosePieces: 4, nearestExpiry: day(60) });
  expect(m.batches.map((b: { batchNo: string }) => b.batchNo)).toEqual(['B-200', 'B-100']);
});

test('the same batch again adds to it, but only with the same expiry date', async () => {
  const again = await receive(store1, 'LV', [{ productId: milk, batchNo: 'B-100', expiryDate: day(300), pieces: 6 }]);
  expect(again.statusCode).toBe(201);
  const wrong = await receive(store1, 'LV', [{ productId: milk, batchNo: 'B-100', expiryDate: day(301), pieces: 1 }]);
  expect(wrong.statusCode).toBe(400);
  expect(wrong.json().message).toMatch(/already in stock with expiry/);
  // The failed receipt saved nothing and did not use up a number.
  const ok = await receive(store1, 'LV', [{ productId: milk, batchNo: 'B-300', expiryDate: day(500), pieces: 1 }]);
  expect(ok.json().number).toBe('LV-GRN-000003');
  const m = (await get(app, store1, '/stock?q=lv-milk')).json()[0];
  expect(m.onHand).toBe(155);
});

test('receiving refuses mistakes in plain words', async () => {
  const cases: [Record<string, unknown>[], RegExp][] = [
    [[{ productId: milk, batchNo: 'X', expiryDate: day(-1), cartons: 1 }], /already expired/],
    [[{ productId: milk, batchNo: 'X', expiryDate: day(100) }], /how many arrived/],
    [[{ productId: tea, batchNo: 'X', expiryDate: day(100), cartons: 1 }], /not one of this company/],
    [[{ productId: milk, batchNo: 'X', expiryDate: day(100), cartons: 1 }, { productId: milk, batchNo: 'x', expiryDate: day(100), cartons: 1 }], /twice/],
    [[], /at least one product/],
  ];
  for (const [lines, message] of cases) {
    const r = await receive(store1, 'LV', lines);
    expect(r.statusCode).toBe(400);
    expect(r.json().message).toMatch(message);
  }
});

test('only warehouse and admin change stock; salesmen and accounts never see it', async () => {
  expect((await receive(store2, 'LV', [{ productId: milk, batchNo: 'Z', expiryDate: day(100), cartons: 1 }])).statusCode).toBe(403);
  expect((await receive(manager, 'LV', [{ productId: milk, batchNo: 'Z', expiryDate: day(100), cartons: 1 }])).statusCode).toBe(403);
  expect((await get(app, salesman, '/stock')).statusCode).toBe(403);
  expect((await get(app, accounts, '/stock')).statusCode).toBe(403);
  expect((await get(app, salesman, '/stock/short-dated')).statusCode).toBe(403);
  // Facility 2 does not see Facility 1 stock; management sees everything.
  expect((await get(app, store2, '/stock')).json()).toEqual([]);
  expect((await get(app, manager, '/stock')).json().map((s: { code: string }) => s.code)).toEqual(['LV-MILK', 'ZT-TEA']);
  expect((await get(app, store2, '/stock?includeEmpty=true')).json().map((s: { code: string }) => s.code)).toEqual(['GL-COOK']);
});

test('adjustments: damaged takes off, found adds, never below 0, other needs a note', async () => {
  const batch = (await get(app, store1, '/stock?q=lv-milk')).json()[0].batches.find((b: { batchNo: string }) => b.batchNo === 'B-200');
  const damaged = await post(app, store1, '/stock/adjustments', { batchId: batch.id, reason: 'damaged', pieces: 3 });
  expect(damaged.json()).toMatchObject({ change: -3, pieces: 21, cartons: 1, loosePieces: 9 });
  expect((await post(app, store1, '/stock/adjustments', { batchId: batch.id, reason: 'found', cartons: 1 })).json().pieces).toBe(33);

  const tooMuch = await post(app, store1, '/stock/adjustments', { batchId: batch.id, reason: 'lost', cartons: 3 });
  expect(tooMuch.statusCode).toBe(409);
  expect(tooMuch.json().message).toBe('Only 33 pieces of this batch are in stock.');
  expect((await post(app, store1, '/stock/adjustments', { batchId: batch.id, reason: 'other', direction: 'remove', pieces: 1 })).json().message).toMatch(
    /write what happened/,
  );
  expect((await post(app, store1, '/stock/adjustments', { batchId: batch.id, reason: 'other', pieces: 1, note: 'x' })).json().message).toMatch(
    /added or taken off/,
  );
  expect((await post(app, store2, '/stock/adjustments', { batchId: batch.id, reason: 'found', pieces: 1 })).statusCode).toBe(404);

  const history = (await get(app, manager, `/stock/batches/${batch.id}/movements`)).json();
  expect(history.map((h: { kind: string; change: number }) => [h.kind, h.change])).toEqual([
    ['adjusted', 12],
    ['adjusted', -3],
    ['received', 24],
  ]);
  expect(history[2].documentNumber).toBe('LV-GRN-000001');
});

test('two adjustments at once cannot take a batch below 0', async () => {
  const r = await receive(store2, 'GL', [{ productId: cookies, batchNo: 'C1', expiryDate: day(200), pieces: 5 }]);
  const batchId = (await get(app, store2, '/stock')).json()[0].batches[0].id;
  expect(r.statusCode).toBe(201);
  const results = await Promise.all(
    Array.from({ length: 4 }, () => post(app, store2, '/stock/adjustments', { batchId, reason: 'damaged', pieces: 2 })),
  );
  expect(results.map((x) => x.statusCode).sort()).toEqual([200, 200, 409, 409]);
  expect((await get(app, store2, '/stock')).json()[0].onHand).toBe(1);
});

test('stock count sets what was counted and records each difference', async () => {
  const batches = (await get(app, store1, '/stock?q=lv-milk')).json()[0].batches as { id: number; batchNo: string; onHand: number }[];
  const b100 = batches.find((b) => b.batchNo === 'B-100')!;
  const b300 = batches.find((b) => b.batchNo === 'B-300')!;
  const r = await post(app, store1, '/stock/counts', {
    companyCode: 'LV',
    lines: [
      { batchId: b100.id, cartons: 10 },
      { batchId: b300.id, pieces: 1 },
    ],
  });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ number: 'LV-CNT-000001', linesCounted: 2, differences: [{ batchNo: 'B-100', before: 130, counted: 120, change: -10 }] });

  const teaBatch = (await get(app, store1, '/stock?q=zt-tea')).json()[0].batches[0].id;
  expect((await post(app, store1, '/stock/counts', { companyCode: 'LV', lines: [{ batchId: teaBatch, pieces: 1 }] })).json().message).toMatch(
    /another company/,
  );
  expect((await post(app, store2, '/stock/counts', { companyCode: 'LV', lines: [{ batchId: b100.id }] })).statusCode).toBe(403);
  const log = (await get(app, admin, '/audit')).json().map((a: { action: string }) => a.action);
  expect(log).toEqual(expect.arrayContaining(['stock_received', 'stock_adjusted', 'stock_counted']));
});

test('short-dated list shows batches expiring soon, oldest first', async () => {
  await receive(store1, 'ZT', [{ productId: tea, batchNo: 'T-SOON', expiryDate: day(20), cartons: 1 }]);
  const soon = (await get(app, manager, '/stock/short-dated')).json();
  expect(soon.map((s: { batchNo: string }) => s.batchNo)).toEqual(['T-SOON', 'B-200']);
  expect(soon[0]).toMatchObject({ code: 'ZT-TEA', expired: false, cartons: 1 });
  expect(soon[0].daysLeft).toBeGreaterThanOrEqual(19);
  expect((await get(app, manager, '/stock/short-dated?days=30')).json()).toHaveLength(1);
  expect((await get(app, manager, '/stock/short-dated?company=LV')).json().map((s: { batchNo: string }) => s.batchNo)).toEqual(['B-200']);

  // A batch that has passed its date shows as expired.
  await db.query(`update stock_batches set expiry_date = current_date - 3 where batch_no = 'T-SOON'`);
  expect((await get(app, manager, '/stock/short-dated')).json()[0]).toMatchObject({ batchNo: 'T-SOON', expired: true });
});
