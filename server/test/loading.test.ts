import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let store: string; // warehouse, Facility 1
let store2: string; // warehouse, Facility 2
let seller: string;
let driver: string;
let driverId: number;
let truck: number;
let milk: number; // LV, 12 tins per carton
let tea: number; // ZT, 40 sachets per carton
let orderA: number; // shop A: 3 ctn milk, 1 ctn tea
let orderB: number; // shop B: 2 ctn milk
let planId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const send = (method: 'PUT' | 'PATCH' | 'DELETE', token: string, url: string, payload?: unknown) =>
  app.inject({ method, url, headers: bearer(token), payload: payload as object });

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
async function addProduct(code: string, companyCode: string, piecesPerCarton: number) {
  const r = await post(app, admin, '/products', { code, companyCode, brand: 'Test', name: code, packSize: 'x', piecesPerCarton });
  return r.json().id as number;
}
async function addCustomer(name: string, codes: string[]) {
  const r = await post(app, admin, '/customers', { name, channel: 'wholesaler', companies: codes.map((code) => ({ code, paymentTermsDays: 30 })) });
  return r.json().id as number;
}
const book = async (customerId: number, lines: Record<string, unknown>[]) => {
  const r = await post(app, manager, '/orders', { customerId, invoiceType: 'plain', lines });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
};
const plan = async (token = manager) => (await get(app, token, `/loading-plans/${planId}`)).json();
const order = async (id: number) => (await get(app, manager, `/orders/${id}`)).json();
const lineOf = async (orderId: number, productId: number) =>
  (await order(orderId)).lines.find((l: { productId: number }) => l.productId === productId).id as number;
const milkStock = async () => (await get(app, store, '/stock?q=lv-milk&includeEmpty=true')).json()[0];

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0995000002', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store Two', phone: '0995000003', role: 'warehouse', companies: ['ZF', 'GL'] });
  await addUser({ name: 'Seller', phone: '0995000004', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  driverId = await addUser({ name: 'Felix', phone: '0995000005', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  [manager, store, store2, seller, driver] = (await Promise.all(
    ['0995000001', '0995000002', '0995000003', '0995000004', '0995000005'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string, string];
  truck = (await post(app, admin, '/vehicles', { plate: 'LL 1234', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  milk = await addProduct('LV-MILK', 'LV', 12);
  tea = await addProduct('ZT-TEA', 'ZT', 40);
  const shopA = await addCustomer('Shop A', ['LV', 'ZT']);
  const shopB = await addCustomer('Shop B', ['LV']);
  for (const [companyCode, productId, batchNo, expiry, cartons] of [
    ['LV', milk, 'M-1', day(100), 4],
    ['LV', milk, 'M-2', day(300), 6],
    ['ZT', tea, 'T-1', day(200), 5],
  ] as const) {
    const r = await post(app, admin, '/stock/receipts', { companyCode, receivedOn: day(0), lines: [{ productId, batchNo, expiryDate: expiry, cartons }] });
    expect(r.statusCode).toBe(201);
  }
  orderA = await book(shopA, [
    { productId: milk, cartons: 3, pricePerCarton: 24000 },
    { productId: tea, cartons: 1, pricePerCarton: 50000 },
  ]);
  orderB = await book(shopB, [{ productId: milk, cartons: 2, pricePerCarton: 24000 }]);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('a sales manager starts a plan; the vehicle brings its usual driver', async () => {
  const r = await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck });
  expect(r.statusCode).toBe(201);
  planId = r.json().id;
  expect(r.json()).toMatchObject({ number: 'LP-000001', status: 'planned', sent: false, vehiclePlate: 'LL 1234', driverName: 'Felix', canPlan: true });
  expect((await post(app, manager, '/loading-plans', { facilityCode: 'F2' })).statusCode).toBe(403);
  expect((await post(app, store, '/loading-plans', { facilityCode: 'F1' })).statusCode).toBe(403);
  // Salesmen never see plans; the other warehouse does not see it; the driver sees it only once loaded.
  expect((await get(app, seller, '/loading-plans')).statusCode).toBe(403);
  expect((await get(app, store2, `/loading-plans/${planId}`)).statusCode).toBe(404);
  expect((await get(app, driver, '/loading-plans')).json()).toEqual([]);
});

test('open orders show product by product; the plan never carries more than is open', async () => {
  const open = (await get(app, manager, `/loading-plans/${planId}/open-lines`)).json();
  expect(open.map((p: { code: string; open: number }) => [p.code, p.open])).toEqual([
    ['LV-MILK', 60],
    ['ZT-TEA', 40],
  ]);
  expect(open[0].lines.map((l: { customerName: string; open: number }) => [l.customerName, l.open])).toEqual([
    ['Shop A', 36],
    ['Shop B', 24],
  ]);

  const milkA = await lineOf(orderA, milk);
  const teaA = await lineOf(orderA, tea);
  const milkB = await lineOf(orderB, milk);
  const tooMuch = await send('PUT', manager, `/loading-plans/${planId}/items`, { items: [{ lineId: milkB, cartons: 3 }] });
  expect(tooMuch.statusCode).toBe(400);
  expect(tooMuch.json().message).toBe('Shop B, Test LV-MILK x: only 2 ctn is still open.');

  const r = await send('PUT', manager, `/loading-plans/${planId}/items`, {
    items: [
      { lineId: milkA, cartons: 2 },
      { lineId: teaA, cartons: 1 },
      { lineId: milkB, cartons: 2 },
    ],
  });
  expect(r.statusCode).toBe(200);
  const p = r.json();
  expect(p.products.map((x: { code: string; cartons: number }) => [x.code, x.cartons])).toEqual([
    ['LV-MILK', 4],
    ['ZT-TEA', 1],
  ]);
  // Picking guide: the oldest batch first, never more than the plan carries.
  expect(p.products[0].batches.map((b: { batchNo: string; cartons: number }) => [b.batchNo, b.cartons])).toEqual([
    ['M-1', 4],
  ]);
  expect(p.drops.map((d: { customerName: string }) => d.customerName)).toEqual(['Shop A', 'Shop B']);
  expect(p.newChanges).toBe(0);

  const after = (await get(app, manager, `/loading-plans/${planId}/open-lines`)).json();
  expect(after[0].lines[0]).toMatchObject({ customerName: 'Shop A', open: 36, current: 24 });
  expect((await order(orderA)).stage).toBe('planned');
  expect((await get(app, manager, '/orders')).json().find((o: { id: number }) => o.id === orderA).stage).toBe('planned');

  // The order cannot drop below what is planned.
  const less = await send('PATCH', manager, `/orders/${orderA}`, { lines: [{ lineId: milkA, cartons: 1, pricePerCarton: 24000 }] });
  expect(less.json().message).toMatch(/2 ctn is already on a loading plan or vehicle/);
});

test('sending needs a vehicle, a driver and orders; later changes show as new to the warehouse', async () => {
  const empty = (await post(app, manager, '/loading-plans', { facilityCode: 'F1' })).json();
  expect((await post(app, manager, `/loading-plans/${empty.id}/send`)).json().message).toBe('Please pick the vehicle.');
  await send('PUT', manager, `/loading-plans/${empty.id}/items`, { vehicleId: truck });
  expect((await post(app, manager, `/loading-plans/${empty.id}/send`)).json().message).toMatch(/at least one order/);
  expect((await send('DELETE', manager, `/loading-plans/${empty.id}`)).statusCode).toBe(200);

  expect((await plan(store)).canLoad).toBe(false);
  const sent = await post(app, manager, `/loading-plans/${planId}/send`);
  expect(sent.json()).toMatchObject({ sent: true, canSend: false });
  expect((await post(app, manager, `/loading-plans/${planId}/send`)).statusCode).toBe(409);
  expect((await plan(store)).canLoad).toBe(true);

  const teaA = await lineOf(orderA, tea);
  await send('PUT', manager, `/loading-plans/${planId}/items`, { items: [{ lineId: teaA, pieces: 20 }] });
  expect((await plan(store)).newChanges).toBe(1);
  expect((await plan(manager)).newChanges).toBe(0);
  expect((await post(app, store, `/loading-plans/${planId}/seen`)).json().newChanges).toBe(0);
  const list = (await get(app, store, '/loading-plans')).json();
  expect(list[0]).toMatchObject({ number: 'LP-000001', customers: 2, pieces: 68, newChanges: 0 });
});

test('the plans list counts cartons per product, since products have different carton sizes', async () => {
  // Planned: 48 tins of milk (12 per carton) and 20 sachets of tea (40 per carton).
  // Splitting the 68 pieces by one carton size would be wrong; per product it is 4 ctn + 0, and 0 ctn + 20.
  const list = (await get(app, manager, '/loading-plans')).json();
  expect(list.find((x: { id: number }) => x.id === planId)).toMatchObject({ pieces: 68, cartons: 4, loosePieces: 20 });
});

test('the warehouse changes a product with a reason: less comes off the last order, more fills open orders', async () => {
  expect((await send('PATCH', store, `/loading-plans/${planId}/warehouse`, { products: [{ productId: milk, cartons: 3 }] })).json().message).toMatch(
    /say why/,
  );
  const less = await send('PATCH', store, `/loading-plans/${planId}/warehouse`, { products: [{ productId: milk, cartons: 3 }], reason: 'One carton damaged' });
  expect(less.statusCode).toBe(200);
  const items = less.json().products[0].items.map((i: { customerName: string; cartons: number }) => [i.customerName, i.cartons]);
  expect(items).toEqual([
    ['Shop A', 2],
    ['Shop B', 1],
  ]);
  // Tea: 20 sachets planned, the order has 40 open, so 2 cartons only fit 1.
  const more = await send('PATCH', store, `/loading-plans/${planId}/warehouse`, { products: [{ productId: tea, cartons: 2 }], reason: 'Room on the truck' });
  expect(more.json().notAdded).toEqual([{ productId: tea, pieces: 40 }]);
  expect(more.json().products[1]).toMatchObject({ cartons: 1, loosePieces: 0 });
  const p = await plan(manager);
  expect(p.newChanges).toBe(2);
  expect(p.changes.at(-2)).toMatchObject({ byWarehouse: true, by: 'Store', before: 48, after: 36, reason: 'One carton damaged' });
  expect((await send('PATCH', manager, `/loading-plans/${planId}/warehouse`, { products: [{ productId: tea }], reason: 'x' })).statusCode).toBe(403);
});

test('putting an order on hold takes it off the plan', async () => {
  expect((await post(app, manager, `/orders/${orderB}/hold`)).json().history.at(-1).detail).toMatch(/Taken off the loading plan/);
  expect((await plan(store)).drops.map((d: { customerName: string }) => d.customerName)).toEqual(['Shop A']);
  expect((await plan(store)).newChanges).toBe(1);
  const milkB = await lineOf(orderB, milk);
  const onHold = await send('PUT', manager, `/loading-plans/${planId}/items`, { items: [{ lineId: milkB, cartons: 1 }] });
  expect(onHold.json().message).toMatch(/on hold/);
  await post(app, manager, `/orders/${orderB}/release`);
});

test('loading takes stock off the shelves into transit, oldest first, and makes the delivery note and gate pass', async () => {
  expect((await post(app, manager, `/loading-plans/${planId}/load`)).statusCode).toBe(403);
  const r = await post(app, store, `/loading-plans/${planId}/load`);
  expect(r.statusCode).toBe(200);
  const p = r.json();
  expect(p).toMatchObject({ status: 'loaded', deliveryNoteNo: 'DN-000001', gatePassNo: 'GP-000001', loadedBy: 'Store', canGate: true, canPlan: false });
  expect(p.notLoaded).toEqual([]);
  expect(p.products[0]).toMatchObject({ code: 'LV-MILK', loaded: 24 });
  expect(p.products[0].batches.map((b: { batchNo: string; pieces: number }) => [b.batchNo, b.pieces])).toEqual([['M-1', 24]]);
  // Once loaded, the list counts what was loaded: 2 ctn of milk and 1 ctn of tea.
  const listed = (await get(app, store, '/loading-plans')).json().find((x: { id: number }) => x.id === planId);
  expect(listed).toMatchObject({ pieces: 64, cartons: 3, loosePieces: 0 });

  // Stock is only reduced on delivery (client, 7 Oct): the 2 loaded cartons are in transit, still counted.
  // Shop A's third carton and shop B's two stay reserved.
  expect(await milkStock()).toMatchObject({ onHand: 120, inTransit: 24, reserved: 36, free: 60 });
  const a = await order(orderA);
  expect(a.stage).toBe('on_vehicle');
  expect(a.lines.find((l: { productId: number }) => l.productId === milk)).toMatchObject({ onVehicle: 24, reserved: 12, shortPieces: 0 });
  expect(a.history.at(-1)).toMatchObject({ title: 'Loaded', detail: 'On LL 1234 · delivery note DN-000001' });
  const m1 = (await milkStock()).batches.find((b: { batchNo: string }) => b.batchNo === 'M-1');
  expect(m1).toMatchObject({ onHand: 48, inTransit: 24 });
  // In-transit stock is not on the shelf, so it cannot be written off or counted away.
  const lost = await post(app, store, '/stock/adjustments', { batchId: m1.id, reason: 'lost', pieces: 25 });
  expect(lost.json().message).toBe('Only 24 pieces of this batch are in stock.');
  const count = await post(app, store, '/stock/counts', { companyCode: 'LV', lines: [{ batchId: m1.id, pieces: 24 }] });
  expect(count.json().differences).toEqual([]);

  // Nothing about a loaded plan can change, and the order cannot be cancelled while on the vehicle.
  expect((await send('PUT', manager, `/loading-plans/${planId}/items`, { note: 'x' })).statusCode).toBe(409);
  expect((await send('DELETE', manager, `/loading-plans/${planId}`)).statusCode).toBe(409);
  expect((await post(app, manager, `/orders/${orderA}/cancel`)).json().message).toMatch(/on a vehicle/);
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(409);
});

test('the vehicle leaves the gate; the driver now sees the plan with its drops', async () => {
  const out = await post(app, store, `/loading-plans/${planId}/gate`);
  expect(out.json()).toMatchObject({ status: 'out', outBy: 'Store', canGate: false });
  expect((await post(app, store, `/loading-plans/${planId}/gate`)).statusCode).toBe(409);
  expect((await order(orderA)).history.at(-1)).toMatchObject({ title: 'Left the warehouse', detail: 'Gate pass GP-000001' });
  const mine = (await get(app, driver, '/loading-plans')).json();
  expect(mine.map((x: { number: string }) => x.number)).toEqual(['LP-000001']);
  const p = await plan(driver);
  expect(p.drops[0]).toMatchObject({ customerName: 'Shop A', orders: [(await order(orderA)).number] });
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(expect.arrayContaining(['loading_plan_started', 'loading_plan_sent', 'loading_plan_loaded', 'loading_plan_out']));
});

test('what is not on the shelves is not loaded and stays on the order', async () => {
  const p2 = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json();
  const milkB = await lineOf(orderB, milk);
  await send('PUT', manager, `/loading-plans/${p2.id}/items`, { items: [{ lineId: milkB, cartons: 2 }] });
  await post(app, manager, `/loading-plans/${p2.id}/send`);
  // Only 1 carton of milk is left on the shelves after a loss.
  const stock = await milkStock();
  for (const b of stock.batches) {
    const keep = b.batchNo === 'M-2' ? 12 : 0;
    const shelf = b.onHand - b.inTransit;
    if (shelf > keep) expect((await post(app, admin, '/stock/adjustments', { batchId: b.id, reason: 'lost', pieces: shelf - keep })).statusCode).toBe(200);
  }
  const r = (await post(app, store, `/loading-plans/${p2.id}/load`)).json();
  expect(r.notLoaded).toEqual([{ product: 'Test LV-MILK x', pieces: 12, cartons: 1, loosePieces: 0 }]);
  expect(r.products[0]).toMatchObject({ loaded: 12 });
  const b = await order(orderB);
  expect(b.lines[0]).toMatchObject({ onVehicle: 12, reserved: 0, shortPieces: 12 });
  // Nothing left on the shelves; what is on the two vehicles is still counted until delivered.
  expect(await milkStock()).toMatchObject({ onHand: 36, inTransit: 36, free: 0 });
});
