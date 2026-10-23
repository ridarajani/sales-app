import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let manager2: string; // sales manager, ZF and GL
let seller: string; // salesman, LV and ZT
let seller2: string; // salesman, LV only
let store: string; // warehouse, Facility 1
let accounts: string;
let driver: string;
let sellerId: number;
let seller2Id: number;
let milk: number; // LV, 12 tins per carton
let tea: number; // ZT, 40 sachets per carton
let cookies: number; // GL, 10 packs per carton
let wholesaler: number; // buys from LV and ZT
let supermarket: number; // buys from LV, needs an LPO
let glShop: number; // buys from GL only

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const patch = (token: string, url: string, payload: unknown) =>
  app.inject({ method: 'PATCH', url, headers: bearer(token), payload: payload as object });

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
async function addCustomer(name: string, channel: string, codes: string[], needsLpo = false) {
  const r = await post(app, admin, '/customers', { name, channel, needsLpo, companies: codes.map((code) => ({ code, paymentTermsDays: 30 })) });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
const receive = (companyCode: string, productId: number, batchNo: string, expiryDate: string, cartons: number) =>
  post(app, admin, '/stock/receipts', { companyCode, receivedOn: day(0), lines: [{ productId, batchNo, expiryDate, cartons }] });
const batchOf = async (code: string, batchNo: string) =>
  (await get(app, admin, `/stock?q=${code}`)).json()[0].batches.find((b: { batchNo: string }) => b.batchNo === batchNo);

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0994000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Manager Two', phone: '0994000002', role: 'sales_manager', companies: ['ZF', 'GL'] });
  sellerId = await addUser({ name: 'Seller', phone: '0994000003', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  seller2Id = await addUser({ name: 'Seller Two', phone: '0994000004', role: 'salesman', managerId: sm, companies: ['LV'] });
  await addUser({ name: 'Store', phone: '0994000005', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Books', phone: '0994000006', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  await addUser({ name: 'Driver', phone: '0994000007', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  [manager, manager2, seller, seller2, store, accounts, driver] = (await Promise.all(
    ['0994000001', '0994000002', '0994000003', '0994000004', '0994000005', '0994000006', '0994000007'].map(
      async (phone) => (await login(app, phone, '1111')).token,
    ),
  )) as [string, string, string, string, string, string, string];
  milk = await addProduct('LV-MILK', 'LV', 12);
  tea = await addProduct('ZT-TEA', 'ZT', 40);
  cookies = await addProduct('GL-COOK', 'GL', 10);
  wholesaler = await addCustomer('Wholesale Shop', 'wholesaler', ['LV', 'ZT']);
  supermarket = await addCustomer('Big Supermarket', 'supermarket', ['LV'], true);
  glShop = await addCustomer('Cookie Corner', 'minibus', ['GL']);
  expect((await app.inject({ method: 'PUT', url: `/customers/${wholesaler}/opening-balance`, headers: bearer(admin), payload: { companyCode: 'LV', amount: 250000, asOf: day(0) } })).statusCode).toBe(200);
  // Milk: an old batch (40 days left) and a fresh one. Tea: 2 cartons.
  expect((await receive('LV', milk, 'M-OLD', day(40), 5)).statusCode).toBe(201);
  expect((await receive('LV', milk, 'M-NEW', day(300), 10)).statusCode).toBe(201);
  expect((await receive('ZT', tea, 'T-1', day(400), 2)).statusCode).toBe(201);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('a salesman books one order for both companies of the facility, with VAT worked out per company', async () => {
  const r = await post(app, seller, '/orders', {
    customerId: wholesaler,
    invoiceType: 'vat',
    wantedOn: day(1),
    lines: [
      { productId: milk, cartons: 3, pieces: 6, pricePerCarton: 24000 },
      { productId: tea, cartons: 1, pricePerCarton: 50000 },
    ],
  });
  expect(r.statusCode).toBe(201);
  const o = r.json();
  expect(o).toMatchObject({ number: 'SO-000001', status: 'open', salesmanName: 'Seller', bookedBy: 'Seller', customerName: 'Wholesale Shop' });
  // 3.5 cartons x 24,000 = 84,000; VAT 17.5% is 14,700.
  expect(o.companies).toEqual([
    { code: 'LV', name: expect.any(String), lpoNo: '', subtotal: 84000, vatRate: 17.5, vat: 14700, total: 98700 },
    { code: 'ZT', name: expect.any(String), lpoNo: '', subtotal: 50000, vatRate: 17.5, vat: 8750, total: 58750 },
  ]);
  expect(o).toMatchObject({ subtotal: 134000, vat: 23450, total: 157450, shortPieces: 0, canChange: true, canManage: false });
  expect(o.lines[0]).toMatchObject({ pieces: 42, cartons: 3, loosePieces: 6, reserved: 42, amount: 84000 });
  expect(o.history.map((h: { title: string }) => h.title)).toEqual(['Order booked', 'Stock reserved']);

  // Oldest expiry first: a wholesaler takes the 40-day batch.
  expect(await batchOf('lv-milk', 'M-OLD')).toMatchObject({ onHand: 60, reserved: 42, free: 18 });
  const stock = (await get(app, store, '/stock?q=lv-milk')).json()[0];
  expect(stock).toMatchObject({ onHand: 180, reserved: 42, free: 138 });

  // Next time the booking screen offers VAT, the last prices and what the customer owes.
  const booking = (await get(app, seller, `/orders/booking/${wholesaler}`)).json();
  expect(booking.customer).toMatchObject({ name: 'Wholesale Shop', lastInvoiceType: 'vat' });
  expect(booking.companies.map((c: { code: string; balance: number }) => [c.code, c.balance])).toEqual([
    ['LV', 250000],
    ['ZT', 0],
  ]);
  expect(booking.lastPrices).toEqual(
    expect.arrayContaining([
      { productId: milk, pricePerCarton: 24000 },
      { productId: tea, pricePerCarton: 50000 },
    ]),
  );
  expect(booking.minShelfDays).toBe(30);
});

test('a supermarket needs more shelf life, so it skips the old batch; a plain invoice has no VAT', async () => {
  const noLpo = await post(app, manager, '/orders', {
    customerId: supermarket,
    invoiceType: 'plain',
    lines: [{ productId: milk, cartons: 1, pricePerCarton: 25000 }],
  });
  expect(noLpo.statusCode).toBe(400);
  expect(noLpo.json().message).toMatch(/LPO number for/);

  const r = await post(app, manager, '/orders', {
    customerId: supermarket,
    invoiceType: 'plain',
    salesmanId: seller2Id,
    lpos: { LV: 'LPO-77' },
    lines: [{ productId: milk, cartons: 1, pricePerCarton: 25000 }],
  });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ number: 'SO-000002', salesmanName: 'Seller Two', bookedBy: 'Manager', vat: 0, total: 25000 });
  expect(r.json().companies[0]).toMatchObject({ lpoNo: 'LPO-77', vatRate: 0 });
  expect(await batchOf('lv-milk', 'M-NEW')).toMatchObject({ reserved: 12 });
  expect(await batchOf('lv-milk', 'M-OLD')).toMatchObject({ reserved: 42 });
});

test('short stock still books the order; trying again after new stock reserves the rest', async () => {
  const r = await post(app, seller, '/orders', {
    customerId: wholesaler,
    invoiceType: 'plain',
    lines: [{ productId: tea, cartons: 3, pricePerCarton: 50000 }],
  });
  expect(r.statusCode).toBe(201);
  // 2 cartons in stock, 1 already reserved: 1 carton set aside, 2 short.
  expect(r.json()).toMatchObject({ shortPieces: 80 });
  expect(r.json().history[1].detail).toMatch(/Not all of it is in stock/);
  const list = (await get(app, seller, '/orders?status=open')).json();
  expect(list.find((o: { id: number }) => o.id === r.json().id)).toMatchObject({ shortPieces: 80, total: 150000 });

  await receive('ZT', tea, 'T-2', day(500), 5);
  const again = await post(app, seller, `/orders/${r.json().id}/reserve`);
  expect(again.statusCode).toBe(200);
  expect(again.json()).toMatchObject({ shortPieces: 0 });
});

test('booking refuses mistakes in plain words', async () => {
  const base = { customerId: wholesaler, invoiceType: 'vat' };
  const cases: [Record<string, unknown>, RegExp][] = [
    [{ ...base, lines: [] }, /at least one product/],
    [{ ...base, lines: [{ productId: milk, pricePerCarton: 100 }] }, /how many/],
    [{ ...base, lines: [{ productId: milk, cartons: 1 }] }, /type the price/],
    [{ ...base, lines: [{ productId: milk, cartons: 1, pricePerCarton: 1 }, { productId: milk, cartons: 2, pricePerCarton: 1 }] }, /twice/],
    [{ ...base, lines: [{ productId: milk, cartons: 1, pricePerCarton: 1 }], wantedOn: day(-2) }, /in the past/],
    [{ ...base, invoiceType: undefined, lines: [{ productId: milk, cartons: 1, pricePerCarton: 1 }] }, /VAT invoice or plain/],
    [{ ...base, customerId: supermarket, lpos: { LV: 'x' }, lines: [{ productId: tea, cartons: 1, pricePerCarton: 1 }] }, /does not buy from/],
  ];
  for (const [body, message] of cases) {
    const r = await post(app, manager, '/orders', body);
    expect(r.statusCode).toBe(400);
    expect(r.json().message).toMatch(message);
  }
  // Products from two warehouses, as admin who has all companies.
  const mixed = await post(app, admin, '/orders', { ...base, lines: [{ productId: milk, cartons: 1, pricePerCarton: 1 }, { productId: cookies, cartons: 1, pricePerCarton: 1 }] });
  expect(mixed.json().message).toMatch(/one warehouse/);
  // A salesman cannot sell a company they do not have, and a manager cannot name a salesman from another company.
  expect((await post(app, seller2, '/orders', { ...base, lines: [{ productId: tea, cartons: 1, pricePerCarton: 1 }] })).statusCode).toBe(403);
  const wrongSeller = await post(app, manager, '/orders', { ...base, salesmanId: seller2Id, lines: [{ productId: tea, cartons: 1, pricePerCarton: 1 }] });
  expect(wrongSeller.json().message).toMatch(/does not sell for these companies/);
  // Facility 2 cannot book for a Facility 1 customer.
  expect((await post(app, manager2, '/orders', { ...base, lines: [{ productId: cookies, cartons: 1, pricePerCarton: 1 }] })).statusCode).toBe(404);
  expect((await post(app, manager2, '/orders', { customerId: glShop, invoiceType: 'vat', lines: [{ productId: cookies, cartons: 1, pricePerCarton: 1 }] })).statusCode).toBe(201);
  expect((await post(app, store, '/orders', { ...base, lines: [{ productId: milk, cartons: 1, pricePerCarton: 1 }] })).statusCode).toBe(403);
});

test('each person sees only their own orders or their companies', async () => {
  const numbers = async (token: string) => (await get(app, token, '/orders')).json().map((o: { number: string }) => o.number);
  expect(await numbers(seller)).toEqual(['SO-000003', 'SO-000001']);
  expect(await numbers(seller2)).toEqual(['SO-000002']);
  expect(await numbers(manager)).toEqual(['SO-000003', 'SO-000002', 'SO-000001']);
  expect(await numbers(manager2)).toEqual(['SO-000004']);
  expect(await numbers(accounts)).toHaveLength(4);
  expect(await numbers(store)).toHaveLength(3);
  expect((await get(app, driver, '/orders')).statusCode).toBe(403);
  expect((await get(app, accounts, '/orders?q=supermarket')).json().map((o: { number: string }) => o.number)).toEqual(['SO-000002']);

  const first = (await get(app, manager, '/orders?q=SO-000001')).json()[0];
  expect((await get(app, seller2, `/orders/${first.id}`)).statusCode).toBe(404);
  expect((await get(app, manager2, `/orders/${first.id}`)).statusCode).toBe(404);
  expect((await get(app, accounts, `/orders/${first.id}`)).json().lines).toHaveLength(2);
});

test('changing an order: quantities and prices, with history, and the reservation follows', async () => {
  const first = (await get(app, manager, '/orders?q=SO-000001')).json()[0];
  const o = (await get(app, seller, `/orders/${first.id}`)).json();
  const milkLine = o.lines.find((l: { productId: number }) => l.productId === milk);
  const teaLine = o.lines.find((l: { productId: number }) => l.productId === tea);
  const r = await patch(seller, `/orders/${o.id}`, {
    lines: [{ lineId: milkLine.id, cartons: 2, pricePerCarton: 23000 }],
    invoiceType: 'plain',
  });
  expect(r.statusCode).toBe(200);
  expect(r.json().lines[0]).toMatchObject({ pieces: 24, reserved: 24, pricePerCarton: 23000, amount: 46000 });
  expect(r.json()).toMatchObject({ vat: 0, total: 96000 });
  expect(r.json().history.at(-1)).toMatchObject({ title: 'Changed', by: 'Seller' });
  expect(r.json().history.at(-1).detail).toMatch(/3 ctn \+ 6 pcs → 2 ctn; .*price MWK 24,000 → MWK 23,000; Invoice: VAT → plain/);
  expect(await batchOf('lv-milk', 'M-OLD')).toMatchObject({ reserved: 24 });

  const allZero = await patch(seller, `/orders/${o.id}`, {
    lines: [
      { lineId: milkLine.id, pricePerCarton: 1 },
      { lineId: teaLine.id, pricePerCarton: 1 },
    ],
  });
  expect(allZero.json().message).toMatch(/cancel it/);
  expect((await patch(seller2, `/orders/${o.id}`, { note: 'x' })).statusCode).toBe(404);
});

test('hold, release, cancel and close follow the rules', async () => {
  const first = (await get(app, manager, '/orders?q=SO-000001')).json()[0];
  const url = `/orders/${first.id}`;
  expect((await post(app, seller, `${url}/hold`)).statusCode).toBe(403);
  const held = await post(app, manager, `${url}/hold`, { note: 'Customer asked to wait' });
  expect(held.json()).toMatchObject({ status: 'hold', canChange: true, canManage: true });
  expect((await get(app, seller, url)).json()).toMatchObject({ canChange: false, canManage: false });
  expect(held.json().history.at(-1).detail).toMatch(/Stock stays reserved. Customer asked to wait/);
  expect(await batchOf('lv-milk', 'M-OLD')).toMatchObject({ reserved: 24 });
  // A salesman cannot change an order on hold; the manager can.
  expect((await patch(seller, url, { note: 'x' })).statusCode).toBe(403);
  expect((await post(app, manager, `${url}/hold`)).statusCode).toBe(409);
  expect((await post(app, manager, `${url}/release`)).json()).toMatchObject({ status: 'open' });

  // Nothing delivered yet: close is refused, cancel releases the stock.
  expect((await post(app, manager, `${url}/close`)).json().message).toMatch(/Cancel the order instead/);
  const cancelled = await post(app, seller, `${url}/cancel`);
  expect(cancelled.json()).toMatchObject({ status: 'cancelled', canChange: false, shortPieces: 0 });
  expect(await batchOf('lv-milk', 'M-OLD')).toMatchObject({ reserved: 0 });
  expect((await post(app, manager, `${url}/cancel`)).statusCode).toBe(409);
  expect((await patch(manager, url, { note: 'x' })).statusCode).toBe(409);

  // Part delivered (Phase 5 will record this): cannot cancel, cannot go below it, can close.
  const second = (await get(app, manager, '/orders?q=SO-000002')).json()[0];
  const line = (await get(app, manager, `/orders/${second.id}`)).json().lines[0];
  await db.query('update order_lines set delivered = 6 where id = $1', [line.id]);
  expect((await post(app, manager, `/orders/${second.id}/cancel`)).json().message).toMatch(/Close it instead/);
  const tooLow = await patch(manager, `/orders/${second.id}`, { lines: [{ lineId: line.id, pieces: 5, pricePerCarton: 25000 }] });
  expect(tooLow.json().message).toMatch(/6 pcs has already been delivered/);
  const closed = await post(app, manager, `/orders/${second.id}/close`);
  expect(closed.json()).toMatchObject({ status: 'done' });
  expect(await batchOf('lv-milk', 'M-NEW')).toMatchObject({ reserved: 0 });
  const log = (await get(app, admin, '/audit')).json().map((a: { action: string }) => a.action);
  expect(log).toEqual(expect.arrayContaining(['order_booked', 'order_changed', 'order_held', 'order_released', 'order_cancelled', 'order_closed']));
});

test('two orders at once cannot reserve the same stock', async () => {
  await receive('GL', cookies, 'C-1', day(200), 3);
  const book = () =>
    post(app, manager2, '/orders', { customerId: glShop, invoiceType: 'plain', lines: [{ productId: cookies, cartons: 2, pricePerCarton: 9000 }] });
  const results = await Promise.all([book(), book(), book()]);
  expect(results.map((r) => r.statusCode)).toEqual([201, 201, 201]);
  // 30 packs in stock for 60 asked: together they are short by exactly 30.
  const short = results.reduce((t, r) => t + r.json().shortPieces, 0);
  expect(short).toBe(30);
  expect(await batchOf('gl-cook', 'C-1')).toMatchObject({ onHand: 30, reserved: 30, free: 0 });
});
