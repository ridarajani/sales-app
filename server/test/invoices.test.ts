import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

// A 1x1 PNG, used as the signature and as a logo.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let manager: string; // sales manager, LV and ZT
let store: string;
let seller: string;
let driver: string;
let otherDriver: string;
let milk: number; // LV, 12 tins per carton
let tea: number; // ZT, 40 sachets per carton
let shopA: number;
let shopB: number;
let orderA: number; // shop A, VAT, LPO for LV: 3 ctn milk, 1 ctn + 20 pcs tea, salesman Seller
let orderB: number; // shop B, cash only, plain: 2 ctn milk
let planId: number;

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Blantyre' });
const plusDays = (iso: string, n: number) => new Date(Date.parse(iso) + n * 86_400_000).toISOString().slice(0, 10);
const plan = async (token = manager) => (await get(app, token, `/loading-plans/${planId}`)).json();
const order = async (id: number) => (await get(app, manager, `/orders/${id}`)).json();
const itemsOf = async (customerId: number) =>
  (await plan(driver)).drops.find((d: { customerId: number }) => d.customerId === customerId).lines as { itemId: number; code: string; loaded: number }[];
const batches = async () =>
  (await db.query('select batch_no as "batchNo", on_hand as "onHand", in_transit as "inTransit" from stock_batches order by batch_no')).rows;

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}
async function addProduct(code: string, companyCode: string, piecesPerCarton: number) {
  const r = await post(app, admin, '/products', { code, companyCode, brand: 'Test', name: code, packSize: 'x', piecesPerCarton });
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  const brandingDir = mkdtempSync(join(tmpdir(), 'branding-'));
  writeFileSync(join(brandingDir, 'LV.png'), Buffer.from(PNG, 'base64'));
  app = buildApp(db, { uploadDir: mkdtempSync(join(tmpdir(), 'uploads-')), brandingDir });
  admin = (await login(app, '0990000000', '1234')).token;
  const sm = await addUser({ name: 'Manager', phone: '0995000001', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Store', phone: '0995000002', role: 'warehouse', companies: ['LV', 'ZT'] });
  const sellerId = await addUser({ name: 'Seller', phone: '0995000004', role: 'salesman', managerId: sm, companies: ['LV', 'ZT'] });
  const driverId = await addUser({ name: 'Felix', phone: '0995000005', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  await addUser({ name: 'Other', phone: '0995000006', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  [manager, store, seller, driver, otherDriver] = (await Promise.all(
    ['0995000001', '0995000002', '0995000004', '0995000005', '0995000006'].map(async (p) => (await login(app, p, '1111')).token),
  )) as [string, string, string, string, string];
  const truck = (await post(app, admin, '/vehicles', { plate: 'LL 1234', facilityCode: 'F1', defaultDriverId: driverId })).json().id;
  milk = await addProduct('LV-MILK', 'LV', 12);
  tea = await addProduct('ZT-TEA', 'ZT', 40);
  shopA = (
    await post(app, admin, '/customers', {
      name: 'Shop A',
      channel: 'wholesaler',
      companies: [
        { code: 'LV', paymentTermsDays: 30 },
        { code: 'ZT', paymentTermsDays: 45 },
      ],
    })
  ).json().id;
  shopB = (await post(app, admin, '/customers', { name: 'Shop B', channel: 'wholesaler', cashOnly: true, companies: [{ code: 'LV', paymentTermsDays: 0 }] })).json().id;
  for (const [companyCode, productId, batchNo, expiry, cartons] of [
    ['LV', milk, 'M-1', day(100), 4],
    ['LV', milk, 'M-2', day(300), 6],
    ['ZT', tea, 'T-1', day(200), 5],
  ] as const) {
    const r = await post(app, admin, '/stock/receipts', { companyCode, receivedOn: day(0), lines: [{ productId, batchNo, expiryDate: expiry, cartons }] });
    expect(r.statusCode).toBe(201);
  }
  const book = async (body: Record<string, unknown>) => {
    const r = await post(app, manager, '/orders', body);
    expect(r.statusCode).toBe(201);
    return r.json().id as number;
  };
  orderA = await book({
    customerId: shopA,
    invoiceType: 'vat',
    salesmanId: sellerId,
    lpos: { LV: 'LPO-77' },
    lines: [
      { productId: milk, cartons: 3, pricePerCarton: 24000 },
      { productId: tea, cartons: 1, pieces: 20, pricePerCarton: 50000 },
    ],
  });
  orderB = await book({ customerId: shopB, invoiceType: 'plain', lines: [{ productId: milk, cartons: 2, pricePerCarton: 24000 }] });

  planId = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json().id;
  const lines = [...(await order(orderA)).lines, ...(await order(orderB)).lines] as { id: number; pieces: number }[];
  const put = await app.inject({
    method: 'PUT',
    url: `/loading-plans/${planId}/items`,
    headers: bearer(manager),
    payload: { items: lines.map((l) => ({ lineId: l.id, pieces: l.pieces })) },
  });
  expect(put.statusCode).toBe(200);
  expect((await post(app, manager, `/loading-plans/${planId}/send`)).statusCode).toBe(200);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('invoices are made once the vehicle is loaded, one per customer and company, at the typed prices', async () => {
  expect((await post(app, manager, `/loading-plans/${planId}/invoices`)).json().error).toBe('not_loaded');
  expect((await post(app, store, `/loading-plans/${planId}/load`)).statusCode).toBe(200);
  expect(await plan()).toMatchObject({ canMakeInvoices: true, invoices: [] });
  expect((await plan(driver)).drops.map((d: { invoiced: boolean }) => d.invoiced)).toEqual([false, false]);
  expect((await post(app, store, `/loading-plans/${planId}/invoices`)).statusCode).toBe(403);
  expect((await post(app, driver, `/loading-plans/${planId}/invoices`)).statusCode).toBe(403);

  const r = await post(app, manager, `/loading-plans/${planId}/invoices`);
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ canMakeInvoices: false });
  // VAT at 17.5% on the prices typed without VAT; 20 sachets are half a carton of tea.
  expect(r.json().made).toEqual([
    { id: expect.any(Number), number: 'LV-INV-000001', total: 84600 },
    { id: expect.any(Number), number: 'ZT-INV-000001', total: 88125 },
    { id: expect.any(Number), number: 'LV-INV-000002', total: 48000 },
  ]);
  expect((await post(app, manager, `/loading-plans/${planId}/invoices`)).json().error).toBe('already_invoiced');
  expect((await order(orderA)).history.at(-1)).toMatchObject({ title: 'Invoice made', detail: 'ZT-INV-000001 · MWK 88,125' });

  const [lv, zt, cash] = r.json().made as { id: number }[];
  const a = (await get(app, manager, `/invoices/${lv!.id}`)).json();
  expect(a).toMatchObject({
    invoiceType: 'vat',
    lpoNo: 'LPO-77',
    subtotal: 72000,
    vatRate: 17.5,
    vat: 12600,
    total: 84600,
    invoiceDate: today(),
    dueDate: plusDays(today(), 30),
    salesmanName: 'Seller',
    vehiclePlate: 'LL 1234',
    deliveryNoteNo: 'DN-000001',
    customer: { name: 'Shop A' },
    company: { code: 'LV' },
  });
  expect(a.lines).toEqual([expect.objectContaining({ code: 'LV-MILK', pieces: 36, quantity: '3 ctn', pricePerCarton: 24000, amount: 72000 })]);
  expect((await get(app, manager, `/invoices/${zt!.id}`)).json()).toMatchObject({ dueDate: plusDays(today(), 45), lpoNo: '' });
  // Cash customers pay on the day; plain invoices carry no VAT.
  expect((await get(app, manager, `/invoices/${cash!.id}`)).json()).toMatchObject({ invoiceType: 'plain', vatRate: 0, vat: 0, dueDate: today() });
});

test('the invoice register shows each user only their own invoices', async () => {
  const numbers = async (token: string, query = '') =>
    (await get(app, token, `/invoices${query}`)).json().map((i: { number: string }) => i.number);
  expect(await numbers(manager)).toEqual(['LV-INV-000002', 'ZT-INV-000001', 'LV-INV-000001']);
  expect(await numbers(seller)).toEqual(['ZT-INV-000001', 'LV-INV-000001']);
  expect(await numbers(driver)).toHaveLength(3);
  expect(await numbers(otherDriver)).toEqual([]);
  expect((await get(app, store, '/invoices')).statusCode).toBe(403);
  expect(await numbers(manager, '?type=plain')).toEqual(['LV-INV-000002']);
  expect(await numbers(manager, '?q=lpo-77')).toEqual(['LV-INV-000001']);
  expect(await numbers(manager, `?customerId=${shopB}`)).toEqual(['LV-INV-000002']);
  expect(await numbers(manager, '?company=ZT')).toEqual(['ZT-INV-000001']);
  const other = await get(app, seller, `/invoices/${(await get(app, manager, '/invoices?type=plain')).json()[0].id}`);
  expect(other.statusCode).toBe(404);
});

test('invoices print as PDF, with the logo on the VAT invoice', async () => {
  for (const inv of (await get(app, manager, '/invoices')).json()) {
    const pdf = await get(app, manager, `/invoices/${inv.id}/pdf`);
    expect(pdf.statusCode).toBe(200);
    expect(pdf.headers['content-type']).toBe('application/pdf');
    expect(pdf.rawPayload.subarray(0, 5).toString()).toBe('%PDF-');
  }
});

test('what owes is the opening balance plus the invoices', async () => {
  const booking = (await get(app, manager, `/orders/booking/${shopA}`)).json();
  expect(booking.companies.map((c: { code: string; balance: number }) => [c.code, c.balance])).toEqual([
    ['LV', 84600],
    ['ZT', 88125],
  ]);
});

test('the driver confirms a drop with a signature; delivered stock leaves the books', async () => {
  const items = await itemsOf(shopA);
  const milkItem = items.find((i) => i.code === 'LV-MILK')!;
  const teaItem = items.find((i) => i.code === 'ZT-TEA')!;
  const full = {
    customerId: shopA,
    receivedBy: 'Mr Banda',
    signature: `data:image/png;base64,${PNG}`,
    lines: [
      { itemId: milkItem.itemId, cartons: 3 },
      { itemId: teaItem.itemId, cartons: 1, reason: 'Damaged box' },
    ],
  };
  expect((await post(app, driver, `/loading-plans/${planId}/deliveries`, full)).json().error).toBe('not_out');
  expect((await post(app, store, `/loading-plans/${planId}/gate`)).statusCode).toBe(200);
  expect((await plan(driver)).canDeliver).toBe(true);

  expect((await post(app, otherDriver, `/loading-plans/${planId}/deliveries`, full)).statusCode).toBe(404);
  expect((await post(app, manager, `/loading-plans/${planId}/deliveries`, full)).statusCode).toBe(403);
  const fail = async (body: Record<string, unknown>) => (await post(app, driver, `/loading-plans/${planId}/deliveries`, { ...full, ...body })).json().message;
  expect(await fail({ signature: undefined })).toBe('Please ask the customer to sign.');
  expect(await fail({ signature: 'bm90IGFuIGltYWdl' })).toBe('The signature could not be read. Please try again.');
  expect(await fail({ receivedBy: '' })).toBe('Please type the name of the person who received the goods.');
  expect(await fail({ lines: [full.lines[0]] })).toBe('Please confirm every product for this customer.');
  expect(await fail({ lines: [full.lines[0], { itemId: teaItem.itemId, cartons: 2 }] })).toBe('Test ZT-TEA x: only 1 ctn + 20 pcs was loaded.');
  expect(await fail({ lines: [full.lines[0], { itemId: teaItem.itemId, cartons: 1 }] })).toBe('Test ZT-TEA x: please say why less was delivered.');

  const r = await post(app, driver, `/loading-plans/${planId}/deliveries`, full);
  expect(r.statusCode).toBe(201);
  const p = r.json();
  expect(p.status).toBe('out');
  const drop = p.drops.find((d: { customerId: number }) => d.customerId === shopA);
  expect(drop.delivery).toMatchObject({ receivedBy: 'Mr Banda', confirmedBy: 'Felix', hasPhoto: false });
  expect(drop.lines.map((l: { code: string; delivered: number; shortReason: string | null }) => [l.code, l.delivered, l.shortReason])).toEqual([
    ['LV-MILK', 36, null],
    ['ZT-TEA', 40, 'Damaged box'],
  ]);
  expect((await post(app, driver, `/loading-plans/${planId}/deliveries`, full)).json().error).toBe('already_delivered');

  // Milk came off M-1 (shop A's reservation); shop B's milk is still on the vehicle. 20 sachets of tea went back on the shelf.
  expect(await batches()).toEqual([
    { batchNo: 'M-1', onHand: 12, inTransit: 12 },
    { batchNo: 'M-2', onHand: 72, inTransit: 12 },
    { batchNo: 'T-1', onHand: 160, inTransit: 0 },
  ]);
  const a = await order(orderA);
  expect(a).toMatchObject({ status: 'open', stage: 'part_delivered' });
  expect(a.lines.find((l: { productId: number }) => l.productId === tea)).toMatchObject({ delivered: 40, onVehicle: 0, reserved: 20, shortPieces: 0 });
  expect(a.history.at(-1)).toMatchObject({ title: 'Part delivered', detail: 'Received by Mr Banda · Test ZT-TEA x: 20 pcs not delivered (Damaged box)' });

  const t1 = (await get(app, admin, '/stock?q=zt-tea')).json()[0].batches[0];
  const moves = (await get(app, admin, `/stock/batches/${t1.id}/movements`)).json();
  expect(moves[0]).toMatchObject({ kind: 'delivered', change: -40, documentNumber: 'DN-000001' });
});

test('a refused drop needs a reason but no signature; the vehicle is done when every drop is confirmed', async () => {
  const [item] = await itemsOf(shopB);
  const body = { customerId: shopB, lines: [{ itemId: item!.itemId, cartons: 0, reason: 'Shop closed' }] };
  const r = await post(app, admin, `/loading-plans/${planId}/deliveries`, body);
  expect(r.statusCode).toBe(201);
  expect(r.json()).toMatchObject({ status: 'done', canDeliver: false });
  expect(await batches()).toEqual([
    { batchNo: 'M-1', onHand: 12, inTransit: 0 },
    { batchNo: 'M-2', onHand: 72, inTransit: 0 },
    { batchNo: 'T-1', onHand: 160, inTransit: 0 },
  ]);
  const b = await order(orderB);
  expect(b).toMatchObject({ status: 'open', stage: 'open', shortPieces: 0 });
  expect(b.history.at(-1)).toMatchObject({ title: 'Refused' });
  expect((await post(app, driver, `/loading-plans/${planId}/deliveries`, body)).json().error).toBe('plan_done');
});

test('an order is done once all of it is delivered', async () => {
  const truck = (await get(app, admin, '/vehicles')).json()[0].id;
  const p2 = (await post(app, manager, '/loading-plans', { facilityCode: 'F1', vehicleId: truck })).json().id;
  const teaLine = (await order(orderA)).lines.find((l: { productId: number }) => l.productId === tea);
  await app.inject({ method: 'PUT', url: `/loading-plans/${p2}/items`, headers: bearer(manager), payload: { items: [{ lineId: teaLine.id, pieces: 20 }] } });
  await post(app, manager, `/loading-plans/${p2}/send`);
  await post(app, store, `/loading-plans/${p2}/load`);
  // The second vehicle's invoice continues the company's series.
  expect((await post(app, manager, `/loading-plans/${p2}/invoices`)).json().made).toEqual([
    { id: expect.any(Number), number: 'ZT-INV-000002', total: 29375 },
  ]);
  await post(app, store, `/loading-plans/${p2}/gate`);
  const itemId = (await get(app, driver, `/loading-plans/${p2}`)).json().drops[0].lines[0].itemId;
  const r = await post(app, driver, `/loading-plans/${p2}/deliveries`, {
    customerId: shopA,
    receivedBy: 'Mr Banda',
    signature: PNG,
    photo: PNG,
    lines: [{ itemId, pieces: 20 }],
  });
  expect(r.statusCode).toBe(201);
  expect(await order(orderA)).toMatchObject({ status: 'done' });
});

test('signatures and photos are shown to those who can see the vehicle', async () => {
  const drop = (await plan()).drops.find((d: { customerId: number }) => d.customerId === shopA);
  const sig = await get(app, manager, `/deliveries/${drop.delivery.id}/signature`);
  expect(sig.statusCode).toBe(200);
  expect(sig.headers['content-type']).toBe('image/png');
  expect(sig.rawPayload.toString('base64')).toBe(PNG);
  expect((await get(app, manager, `/deliveries/${drop.delivery.id}/photo`)).statusCode).toBe(404);
  expect((await get(app, seller, `/deliveries/${drop.delivery.id}/signature`)).statusCode).toBe(403);
  expect((await get(app, otherDriver, `/deliveries/${drop.delivery.id}/signature`)).statusCode).toBe(404);
});

test('the delivery note and gate pass print as PDF', async () => {
  for (const doc of ['delivery-note', 'gate-pass']) {
    const pdf = await get(app, store, `/loading-plans/${planId}/${doc}.pdf`);
    expect(pdf.statusCode).toBe(200);
    expect(pdf.rawPayload.subarray(0, 5).toString()).toBe('%PDF-');
  }
  const log = (await get(app, admin, '/audit')).json().map((x: { action: string }) => x.action);
  expect(log).toEqual(expect.arrayContaining(['invoices_made', 'delivery_confirmed']));
});
