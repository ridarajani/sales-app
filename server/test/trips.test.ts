import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature and the proof.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string;
let seller: string;
let sellerId: number;
let store: string;
let cashier: string;
let truck: number;
let milk: number; // LV, 12 pieces a carton
let tea: number; // ZT, 10 pieces a carton
let shop: number;
let kiosk: number;
let tripId: number;
let cashInvoice: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const trip = async (token = manager) => (await get(app, token, `/trips/${tripId}`)).json();
const sell = (body: Record<string, unknown>, token = seller) =>
  post(app, token, `/trips/${tripId}/sales`, { customerId: shop, stop: 'Salima market', payment: 'cash', receivedBy: 'Mr Banda', signature: PNG, ...body });
const stockOf = async (productId: number) =>
  (await get(app, admin, '/stock?includeEmpty=true')).json().find((p: { productId: number }) => p.productId === productId);

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir: mkdtempSync(join(tmpdir(), 'branding-')) });
  admin = (await login(app, '0990000000', '1234')).token;
  const managerId = await addUser({ name: 'Manager', phone: '0996000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  sellerId = await addUser({ name: 'Seller', phone: '0996000002', role: 'salesman', managerId, companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0996000003', role: 'warehouse', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Cashier', phone: '0996000004', role: 'cashier', companies: ['LV', 'ZT'] });
  const driverId = await addUser({ name: 'Felix', phone: '0996000005', role: 'driver', companies: ['LV', 'ZT'] });
  [manager, seller, store, cashier] = (await Promise.all(
    ['0996000001', '0996000002', '0996000003', '0996000004'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string];
  truck = (await post(app, admin, '/vehicles', { plate: 'LL 5678', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  milk = (await post(app, admin, '/products', { code: 'LV-MILK', companyCode: 'LV', brand: 'Test', name: 'Milk', packSize: '1L', piecesPerCarton: 12 })).json().id;
  tea = (await post(app, admin, '/products', { code: 'ZT-TEA', companyCode: 'ZT', brand: 'Test', name: 'Tea', packSize: '100g', piecesPerCarton: 10 })).json().id;
  shop = (
    await post(app, admin, '/customers', {
      name: 'Salima Shop',
      channel: 'upcountry',
      companies: [{ code: 'LV', paymentTermsDays: 14 }, { code: 'ZT', paymentTermsDays: 14 }],
    })
  ).json().id;
  kiosk = (await post(app, admin, '/customers', { name: 'Cash Kiosk', channel: 'upcountry', cashOnly: true, companies: [{ code: 'LV', paymentTermsDays: 0 }] })).json().id;
  const market = (await post(app, admin, '/customers', { name: 'Supermarket', channel: 'supermarket', companies: [{ code: 'LV', paymentTermsDays: 30 }] }))
    .json().id;
  await post(app, admin, '/stock/receipts', {
    companyCode: 'LV',
    receivedOn: day(0),
    lines: [
      { productId: milk, batchNo: 'M-1', expiryDate: day(200), cartons: 10 },
      // Too close to expiry for an upcountry customer (60 days).
      { productId: milk, batchNo: 'M-0', expiryDate: day(40), cartons: 5 },
    ],
  });
  await post(app, admin, '/stock/receipts', { companyCode: 'ZT', receivedOn: day(0), lines: [{ productId: tea, batchNo: 'T-1', expiryDate: day(300), cartons: 5 }] });
  // An order sets 2 cartons of the good milk aside; a trip never takes them.
  const order = await post(app, manager, '/orders', { customerId: market, invoiceType: 'vat', lines: [{ productId: milk, cartons: 2, pricePerCarton: 24000 }] });
  expect(order.statusCode).toBe(201);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('a manager plans a trip and the warehouse loads what is free, with enough shelf life', async () => {
  const plan = {
    kind: 'upcountry',
    facilityCode: 'F1',
    tripDate: day(0),
    route: 'Salima and Nkhotakota',
    stops: ['Salima market', 'Nkhotakota'],
    vehicleId: truck,
    leaderId: sellerId,
    staff: 'Seller, T. Mwale',
    float: 100000,
    lines: [
      { productId: milk, cartons: 10 },
      { productId: tea, cartons: 3 },
    ],
  };
  expect((await post(app, seller, '/trips', plan)).statusCode).toBe(403);
  expect((await post(app, manager, '/trips', { ...plan, leaderId: 1 })).json().message).toBe('Please pick the salesman or sales manager who leads the trip.');
  const made = await post(app, manager, '/trips', plan);
  expect(made.statusCode).toBe(201);
  expect(made.json()).toMatchObject({ number: 'TRP-000001', status: 'planned', companyCode: 'LV', float: 100000, canEdit: true, canSell: false });
  tripId = made.json().id;
  expect((await post(app, seller, `/trips/${tripId}/sales`, {})).statusCode).toBe(400);

  const loaded = await post(app, store, `/trips/${tripId}/load`);
  expect(loaded.statusCode).toBe(200);
  expect(loaded.json()).toMatchObject({ status: 'out', gatePassNo: 'GP-000001', notLoaded: [{ product: 'Test Milk 1L', cartons: 2, loosePieces: 0 }] });
  expect(loaded.json().lines.map((l: { onVehicle: number }) => l.onVehicle)).toEqual([96, 30]);
  expect(loaded.json().lines[0].batches).toEqual([expect.objectContaining({ batchNo: 'M-1', pieces: 96, sold: 0 })]);
  // The warehouse never sees the money.
  expect(loaded.json().float).toBeUndefined();
  expect((await post(app, store, `/trips/${tripId}/load`)).json().error).toBe('already_loaded');
  expect(await stockOf(milk)).toMatchObject({ onHand: 180, inTransit: 96 });

  // The salesman sees only the trips they lead; the float is cash they hold.
  expect((await get(app, seller, '/trips')).json()).toMatchObject([{ id: tripId, status: 'out' }]);
  expect((await trip(seller)).canSell).toBe(true);
  expect((await get(app, seller, '/cash/mine')).json()).toMatchObject({ floatsTotal: 100000, net: 100000 });
});

test('a cash sale at a stop makes one invoice per company, paid and signed', async () => {
  const r = await sell({
    requestId: 'sale-1',
    invoiceType: 'vat',
    lines: [
      { productId: milk, cartons: 2, pricePerCarton: 24000 },
      { productId: tea, cartons: 1, pricePerCarton: 15000 },
    ],
  });
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({
    tripNumber: 'TRP-000001',
    customerName: 'Salima Shop',
    stop: 'Salima market',
    total: 74025,
    invoices: [
      { number: 'LV-INV-000001', total: 56400 },
      { number: 'ZT-INV-000001', total: 17625 },
    ],
    payments: [
      { number: 'LV-RCT-000001', mode: 'cash', amount: 56400 },
      { number: 'ZT-RCT-000001', mode: 'cash', amount: 17625 },
    ],
  });
  cashInvoice = r.json().invoices[0].id;
  // Sent again after a lost answer: the same sale comes back.
  const again = await sell({ requestId: 'sale-1', lines: [{ productId: milk, cartons: 2, pricePerCarton: 24000 }] });
  expect(again.statusCode).toBe(200);
  expect(again.json().id).toBe(r.json().id);

  const iv = (await get(app, manager, `/invoices/${cashInvoice}`)).json();
  expect(iv).toMatchObject({ tripNumber: 'TRP-000001', tripStop: 'Salima market', vehiclePlate: 'LL 5678', balance: 0 });
  expect(iv.lines[0]).toMatchObject({ pieces: 24, delivered: 24, canReturn: 24, orderNumber: null });
  expect(await stockOf(milk)).toMatchObject({ onHand: 156, inTransit: 72 });
  expect((await get(app, seller, '/cash/mine')).json()).toMatchObject({ cash: 74025, floatsTotal: 100000, net: 174025 });
  expect((await trip()).lines.map((l: { onVehicle: number }) => l.onVehicle)).toEqual([72, 20]);
});

test('a sale cannot take more than is on the vehicle, credit for a cash-only customer, or be made by someone else', async () => {
  const over = await sell({ requestId: 'sale-x', lines: [{ productId: tea, cartons: 3, pricePerCarton: 15000 }] });
  expect(over.json()).toMatchObject({ error: 'not_on_vehicle', message: 'Test Tea 100g: only 2 ctn is left on the vehicle.' });
  const credit = await sell({ requestId: 'sale-y', customerId: kiosk, payment: 'credit', lines: [{ productId: milk, cartons: 1, pricePerCarton: 24000 }] });
  expect(credit.json().message).toBe('Cash Kiosk pays cash only. Please take cash or Airtel Money.');
  const noSign = await sell({ requestId: 'sale-z', signature: undefined, lines: [{ productId: milk, cartons: 1, pricePerCarton: 24000 }] });
  expect(noSign.json().message).toBe('Please ask the customer to sign.');
  const wrongCompany = await sell({ requestId: 'sale-w', customerId: kiosk, lines: [{ productId: tea, cartons: 1, pricePerCarton: 15000 }] });
  expect(wrongCompany.json().message).toBe('Cash Kiosk does not buy from ZT. Please add the company to the customer first.');
  expect((await sell({ requestId: 'sale-m', lines: [{ productId: milk, cartons: 1, pricePerCarton: 24000 }] }, manager)).statusCode).toBe(403);
  // Nothing of the refused sales was kept.
  expect((await trip()).sales).toHaveLength(1);
  expect(await stockOf(milk)).toMatchObject({ onHand: 156, inTransit: 72 });
});

test('sales and expenses saved offline are sent in one go; sending again saves nothing twice', async () => {
  const batch = {
    sales: [
      {
        requestId: 'off-1',
        customerId: shop,
        stop: 'Nkhotakota',
        payment: 'credit',
        invoiceType: 'plain',
        receivedBy: 'Mrs Phiri',
        signature: PNG,
        soldAt: new Date(Date.now() - 60_000).toISOString(),
        lines: [{ productId: milk, cartons: 1, pricePerCarton: 25000 }],
      },
      { requestId: 'off-2', customerId: shop, payment: 'cash', receivedBy: 'Mrs Phiri', signature: PNG, lines: [{ productId: tea, cartons: 5, pricePerCarton: 15000 }] },
      { customerId: shop },
    ],
    expenses: [{ requestId: 'exp-1', amount: 30000, description: 'Fuel', photo: PNG }],
  };
  const r = await post(app, seller, `/trips/${tripId}/sync`, batch);
  expect(r.statusCode).toBe(200);
  const { sales, expenses } = r.json();
  expect(sales[0]).toMatchObject({ requestId: 'off-1', ok: true, repeat: false, result: { payment: 'credit', invoices: [{ number: 'LV-INV-000002', total: 25000 }] } });
  expect(sales[1]).toMatchObject({ requestId: 'off-2', ok: false, error: 'not_on_vehicle' });
  expect(sales[2]).toMatchObject({ requestId: null, ok: false, error: 'invalid', message: 'This phone app is too old. Please update it.' });
  expect(expenses[0]).toMatchObject({ requestId: 'exp-1', ok: true, result: { number: 'LV-EXP-000001', amount: 30000, description: 'Fuel · trip TRP-000001' } });

  const credit = (await get(app, manager, `/invoices/${sales[0].result.invoices[0].id}`)).json();
  expect(credit).toMatchObject({ invoiceType: 'plain', vat: 0, balance: 25000, dueDate: day(14) });

  const again = (await post(app, seller, `/trips/${tripId}/sync`, { sales: batch.sales.slice(0, 1), expenses: batch.expenses })).json();
  expect(again.sales[0]).toMatchObject({ ok: true, repeat: true });
  expect(again.expenses[0]).toMatchObject({ ok: true, repeat: true });
  expect(again.trip).toMatchObject({ salesTotal: 99025, expensesTotal: 30000, cashSales: 74025, cashExpenses: 30000, cashNow: 144025 });

  // The phone's pack for selling offline: the trip's customers, what they owe and their last prices.
  const pack = (await get(app, seller, `/trips/${tripId}/pack`)).json();
  expect(pack.customers.map((c: { name: string }) => c.name)).toEqual(['Cash Kiosk', 'Salima Shop']);
  expect(pack.customers[1]).toMatchObject({
    lastInvoiceType: 'plain',
    companies: [{ code: 'LV', owes: 25000 }, { code: 'ZT', owes: 0 }],
    lastPrices: expect.arrayContaining([{ productId: milk, pricePerCarton: 25000 }, { productId: tea, pricePerCarton: 15000 }]),
  });
});

test('closing the trip: what came back goes on the shelf, what is missing comes off, the cash is counted', async () => {
  const close = (body: Record<string, unknown>) => post(app, seller, `/trips/${tripId}/close`, body);
  const back = [
    { productId: milk, cartons: 4 },
    { productId: tea, cartons: 2 },
  ];
  expect((await close({ lines: [{ productId: milk, cartons: 4 }], counted: 144025 })).json().message).toBe('Test Tea 100g: please count what came back.');
  expect((await close({ lines: [{ productId: milk, cartons: 6 }, back[1]], counted: 144025 })).json().message).toBe(
    'Test Milk 1L: only 5 ctn should come back. Please count again.',
  );
  expect((await close({ lines: back, counted: 144000 })).json().message).toBe(
    'The cash is MWK 25 short. Missing: Test Milk 1L 1 ctn. Please write a note.',
  );

  const r = await close({ lines: back, counted: 144000, note: 'One carton fell off at Salima' });
  expect(r.statusCode).toBe(200);
  expect(r.json()).toMatchObject({ status: 'closed', cashExpected: 144025, cashCounted: 144000, cashDifference: -25, canClose: false, canSell: false });
  expect(r.json().lines.map((l: { returned: number; missing: number }) => [l.returned, l.missing])).toEqual([[48, 12], [20, 0]]);
  expect(await stockOf(milk)).toMatchObject({ onHand: 132, inTransit: 0 });
  expect(await stockOf(tea)).toMatchObject({ onHand: 40, inTransit: 0 });
  expect((await sell({ requestId: 'late', lines: [{ productId: milk, cartons: 1, pricePerCarton: 24000 }] })).json().error).toBe('trip_closed');

  // The leader hands everything to the cashier: cash sales and the float, less the fuel.
  const h = await post(app, seller, '/handovers');
  expect(h.json()).toMatchObject({ cash: 74025, floats: 100000, expenses: 30000, net: 144025 });
  expect(h.json().companies).toEqual([
    { code: 'LV', cash: 56400, withdrawals: 0, floats: 100000, expenses: 30000, net: 126400 },
    { code: 'ZT', cash: 17625, withdrawals: 0, floats: 0, expenses: 0, net: 17625 },
  ]);
  expect((await post(app, cashier, `/handovers/${h.json().id}/count`, { counted: 144025 })).json().status).toBe('counted');
  expect((await get(app, seller, '/cash/mine')).json().net).toBe(0);

  const ledger = (await get(app, manager, `/customers/${shop}/ledger?company=LV`)).json();
  expect(ledger.rows.map((x: { what: string }) => x.what)).toContain('Invoice · trip TRP-000001');
});

test('goods sold on a trip and given back later go back into the batch they came from', async () => {
  const iv = (await get(app, manager, `/invoices/${cashInvoice}`)).json();
  const ret = await post(app, manager, `/invoices/${cashInvoice}/returns`, { lines: [{ invoiceLineId: iv.lines[0].lineId, cartons: 1 }], reason: 'Damaged goods' });
  expect(ret.statusCode).toBe(201);
  expect((await post(app, store, `/returns/${ret.json().id}/receive`, { damaged: [] })).statusCode).toBe(200);
  const batch = (await stockOf(milk)).batches.find((b: { batchNo: string }) => b.batchNo === 'M-1');
  expect(batch.onHand).toBe(120 - 36 - 12 + 12);
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(expect.arrayContaining(['trip_planned', 'trip_loaded', 'trip_sale', 'trip_expense', 'trip_closed']));
});
