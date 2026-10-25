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
let manager: string;
let seller: string;
let sellerId: number;
let store: string;
let truck: number;
let milk: number; // LV, 12 pieces a carton
let tea: number; // ZT, 10 pieces a carton
let shop: number; // upcountry
let wholesaler: number;
let orderA: number; // shop: 3 ctn milk, 1 ctn tea
let orderB: number; // wholesaler: 2 ctn milk
let held: number; // on hold
let tripId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const put = (token: string, url: string, payload: unknown) =>
  app.inject({ method: 'PUT', url, headers: { authorization: `Bearer ${token}` }, payload: payload as object });
const trip = async (token = manager) => (await get(app, token, `/trips/${tripId}`)).json();
const order = async (id: number) => (await get(app, manager, `/orders/${id}`)).json();
const sell = (body: Record<string, unknown>) =>
  post(app, seller, `/trips/${tripId}/sales`, { stop: 'Salima', payment: 'cash', receivedBy: 'Mr Banda', signature: PNG, ...body });

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

const plan = () => ({
  kind: 'upcountry',
  facilityCode: 'F1',
  tripDate: day(0),
  route: 'Salima',
  vehicleId: truck,
  leaderId: sellerId,
  lines: [{ productId: milk, cartons: 5 }],
  orderIds: [orderA, orderB],
});

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir: mkdtempSync(join(tmpdir(), 'branding-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  const managerId = await addUser({ name: 'Manager', phone: '0997000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  sellerId = await addUser({ name: 'Seller', phone: '0997000002', role: 'salesman', managerId, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0997000003', role: 'warehouse', companies: ['LV', 'ZT'] });
  [manager, seller, store] = (await Promise.all(
    ['0997000001', '0997000002', '0997000003'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string];
  truck = (await post(app, admin, '/vehicles', { plate: 'LL 9000', facilityCode: 'F1' })).json().id;
  milk = (await post(app, admin, '/products', { code: 'LV-MILK', companyCode: 'LV', brand: 'Test', name: 'Milk', packSize: '1L', piecesPerCarton: 12 })).json().id;
  tea = (await post(app, admin, '/products', { code: 'ZT-TEA', companyCode: 'ZT', brand: 'Test', name: 'Tea', packSize: '100g', piecesPerCarton: 10 })).json().id;
  const both = [{ code: 'LV', paymentTermsDays: 14 }, { code: 'ZT', paymentTermsDays: 14 }];
  shop = (await post(app, admin, '/customers', { name: 'Salima Shop', channel: 'upcountry', companies: both })).json().id;
  wholesaler = (await post(app, admin, '/customers', { name: 'Big Wholesale', channel: 'wholesaler', companies: both })).json().id;
  await post(app, admin, '/stock/receipts', { companyCode: 'LV', receivedOn: day(0), lines: [{ productId: milk, batchNo: 'M-1', expiryDate: day(200), cartons: 20 }] });
  await post(app, admin, '/stock/receipts', { companyCode: 'ZT', receivedOn: day(0), lines: [{ productId: tea, batchNo: 'T-1', expiryDate: day(300), cartons: 5 }] });
  const book = async (customerId: number, lines: unknown[]) => {
    const r = await post(app, manager, '/orders', { customerId, invoiceType: 'vat', lines });
    expect(r.statusCode).toBe(201);
    return r.json().id as number;
  };
  orderA = await book(shop, [
    { productId: milk, cartons: 3, pricePerCarton: 24000 },
    { productId: tea, cartons: 1, pricePerCarton: 50000 },
  ]);
  orderB = await book(wholesaler, [{ productId: milk, cartons: 2, pricePerCarton: 23000 }]);
  held = await book(shop, [{ productId: milk, cartons: 1, pricePerCarton: 24000 }]);
  expect((await post(app, manager, `/orders/${held}/hold`)).statusCode).toBe(200);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('a trip is planned with orders booked ahead, which no loading plan or other trip can then take', async () => {
  const open = (await get(app, manager, '/trips/open-orders?facility=F1')).json();
  expect(open.map((o: { id: number }) => o.id)).toEqual([orderA, orderB]);

  expect((await post(app, manager, '/trips', { ...plan(), orderIds: [held] })).json().message).toMatch(/on hold/);
  const made = await post(app, manager, '/trips', plan());
  expect(made.statusCode).toBe(201);
  tripId = made.json().id;
  const t = made.json();
  expect(t.orders.map((o: { number: string; customerName: string }) => o.customerName)).toEqual(['Salima Shop', 'Big Wholesale']);
  expect(t.orders[0].lines.map((l: { toDeliver: number }) => l.toDeliver)).toEqual([36, 10]);
  // Tea is only on an order, but has its own line so it is loaded and counted back.
  expect(t.lines.map((l: { productId: number; planned: number; forOrders: number }) => [l.productId, l.planned, l.forOrders])).toEqual([
    [milk, 60, 60],
    [tea, 0, 10],
  ]);
  expect((await order(orderA)).stage).toBe('planned');
  expect((await order(orderA)).history.at(-1).title).toBe('On a trip');

  // Taken: not offered again, and a second trip cannot have it.
  expect((await get(app, manager, '/trips/open-orders?facility=F1')).json()).toEqual([]);
  expect((await get(app, manager, `/trips/open-orders?facility=F1&tripId=${tripId}`)).json()).toHaveLength(2);
  expect((await post(app, manager, '/trips', { ...plan(), orderIds: [orderA] })).json().message).toMatch(/already on trip TRP-000001/);
  // The trip can be saved again with its own orders.
  expect((await put(manager, `/trips/${tripId}`, plan())).statusCode).toBe(200);
});

test('loading puts the orders on the vehicle first, and their goods are not for sale to others', async () => {
  const loaded = await post(app, store, `/trips/${tripId}/load`);
  expect(loaded.statusCode).toBe(200);
  // 5 cartons to sell plus 5 for the orders; 1 carton of tea.
  expect(loaded.json().lines.map((l: { loaded: number }) => l.loaded)).toEqual([120, 10]);
  expect(loaded.json().orders[0].lines[0].pricePerCarton).toBeUndefined();
  const t = await trip();
  expect(t.lines.map((l: { forOrders: number; forSale: number }) => [l.forOrders, l.forSale])).toEqual([
    [60, 60],
    [10, 0],
  ]);
  const a = await order(orderA);
  expect(a.stage).toBe('on_vehicle');
  expect(a.lines.map((l: { onVehicle: number; reserved: number }) => [l.onVehicle, l.reserved])).toEqual([
    [36, 0],
    [10, 0],
  ]);

  // Six cartons is more than the five that are free to sell.
  const tooMuch = await sell({ requestId: 'free-0', customerId: shop, lines: [{ productId: milk, cartons: 6, pricePerCarton: 25000 }] });
  expect(tooMuch.statusCode).toBe(409);
  expect(tooMuch.json().message).toBe('Test Milk 1L: only 5 ctn is left on the vehicle.');
  expect((await sell({ requestId: 'free-1', customerId: shop, lines: [{ productId: milk, cartons: 1, pricePerCarton: 25000 }] })).statusCode).toBe(201);
});

test('an order is delivered at its stop at the order’s prices, and part of it can be left for later', async () => {
  const wrong = await sell({ requestId: 'x', orderId: orderA, customerId: wholesaler, payment: 'credit', lines: [{ productId: milk, cartons: 1 }] });
  expect(wrong.json().message).toBe(`Order ${(await order(orderA)).number} is for another customer.`);
  const over = await sell({ requestId: 'y', orderId: orderA, customerId: shop, payment: 'credit', lines: [{ productId: milk, cartons: 4 }] });
  expect(over.statusCode).toBe(409);

  const r = await sell({
    requestId: 'order-a',
    orderId: orderA,
    customerId: shop,
    payment: 'credit',
    lines: [
      // A typed price is ignored: the order's price applies.
      { productId: milk, cartons: 2, pricePerCarton: 1 },
      { productId: tea, cartons: 1 },
    ],
  });
  expect(r.statusCode).toBe(201);
  // LV: 48,000 + 17.5% = 56,400. ZT: 50,000 + 8,750 = 58,750.
  expect(r.json().invoices.map((i: { companyCode: string; total: number }) => [i.companyCode, i.total])).toEqual([
    ['LV', 56400],
    ['ZT', 58750],
  ]);
  const invoice = (await get(app, manager, `/invoices/${r.json().invoices[0].id}`)).json();
  expect(invoice.lines[0]).toMatchObject({ orderNumber: (await order(orderA)).number, pricePerCarton: 24000 });

  const a = await order(orderA);
  // Still partly on the vehicle.
  expect(a.stage).toBe('on_vehicle');
  expect(a.lines.map((l: { delivered: number; onVehicle: number }) => [l.delivered, l.onVehicle])).toEqual([
    [24, 12],
    [10, 0],
  ]);
  expect(a.history.at(-1)).toMatchObject({ title: 'Part delivered', detail: 'From trip TRP-000001 · at Salima · received by Mr Banda' });
  // The rest of the trip's milk for orders is still held.
  expect((await trip()).lines[0]).toMatchObject({ forOrders: 36, forSale: 48 });
});

test('at close, what an order did not get is back on the shelf and set aside for it again', async () => {
  // 120 loaded; 12 sold freely and 24 delivered; 84 back. All tea delivered. Cash: one carton at 25,000 plus VAT.
  const closed = await post(app, seller, `/trips/${tripId}/close`, { lines: [{ productId: milk, cartons: 7 }, { productId: tea, cartons: 0 }], counted: 29375 });
  expect(closed.json().message).toBeUndefined();
  expect(closed.json().status).toBe('closed');

  const a = await order(orderA);
  expect(a.lines[0]).toMatchObject({ onVehicle: 0, reserved: 12 });
  expect(a.history.at(-1).title).toBe('Back from the trip');
  const b = await order(orderB);
  expect(b).toMatchObject({ stage: 'open' });
  expect(b.lines[0]).toMatchObject({ onVehicle: 0, delivered: 0, reserved: 24 });
  // Both can go out again.
  expect((await get(app, manager, '/trips/open-orders?facility=F1')).json().map((o: { id: number }) => o.id)).toEqual([orderA, orderB]);
});

test('a trip with only orders is fine, and an order put on hold leaves a planned trip', async () => {
  const r = await post(app, manager, '/trips', { ...plan(), lines: [], orderIds: [orderB] });
  expect(r.json().message).toBeUndefined();
  expect((await post(app, manager, '/trips', { ...plan(), lines: [], orderIds: [] })).statusCode).toBe(400);
  expect((await post(app, manager, `/orders/${orderB}/hold`)).statusCode).toBe(200);
  expect((await get(app, manager, `/trips/${r.json().id}`)).json().orders).toEqual([]);
});
