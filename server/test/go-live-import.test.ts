import ExcelJS from 'exceljs';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { dateOf } from '../src/lists/import.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let accounts: string; // LV and ZT only
let warehouse: string; // LV and ZT only
let shopId: number;

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  for (const [phone, role] of [['0993000001', 'accounts'], ['0993000002', 'warehouse']]) {
    const r = await post(app, admin, '/users', { name: role, phone, role, pin: '1111', companies: ['LV', 'ZT'] });
    expect(r.statusCode).toBe(201);
  }
  accounts = (await login(app, '0993000001', '1111')).token;
  warehouse = (await login(app, '0993000002', '1111')).token;
  const shop = await post(app, admin, '/customers', {
    name: 'Kanengo Store', channel: 'wholesaler', area: 'Kanengo', needsLpo: false, cashOnly: false,
    companies: [{ code: 'LV', paymentTermsDays: 30 }, { code: 'ZF', paymentTermsDays: 30 }],
  });
  expect(shop.statusCode).toBe(201);
  shopId = shop.json().id;
  for (const [code, company] of [['LV-A-1', 'LV'], ['ZT-B-2', 'ZT'], ['ZF-C-3', 'ZF']]) {
    const r = await post(app, admin, '/products', { code, companyCode: company, brand: 'Test', name: code, packSize: '1kg', piecesPerCarton: 10 });
    expect(r.statusCode).toBe(201);
  }
});
afterAll(async () => {
  await app.close();
  await db.end();
});

async function workbook(token: string, kind: string, rows: (string | number | Date)[][]) {
  const template = await app.inject({ method: 'GET', url: `/import/${kind}/template`, headers: bearer(token) });
  expect(template.statusCode).toBe(200);
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(template.rawPayload as unknown as ArrayBuffer);
  for (const r of rows) wb.worksheets[0]!.addRow(r);
  return Buffer.from(await wb.xlsx.writeBuffer());
}

const upload = (token: string, url: string, file: Buffer) =>
  app.inject({ method: 'POST', url, headers: { ...bearer(token), 'content-type': 'application/octet-stream' }, payload: file });

test('dates are read day first, in the ways people type them', () => {
  expect(dateOf('31/10/2026')).toBe('2026-10-31');
  expect(dateOf('1-2-2027')).toBe('2027-02-01');
  expect(dateOf('2026-10-31')).toBe('2026-10-31');
  expect(dateOf('31 Oct 2026')).toBe('2026-10-31');
  expect(dateOf('5 October 2026')).toBe('2026-10-05');
  expect(dateOf('31/02/2026')).toBeNull();
  expect(dateOf('10/31/2026')).toBeNull();
  expect(dateOf('soon')).toBeNull();
});

test('opening balances import: checks every row, then saves them all once', async () => {
  const bad = await workbook(accounts, 'opening-balances', [
    ['Kanengo Store', 'LV', '1,250,000', '20/10/2026'],
    ['Nobody Shop', 'LV', '100', '20/10/2026'],
    ['Kanengo Store', 'ZT', 'lots', '31/02/2026'],
    ['kanengo store', 'ZF', '500', '20/10/2026'],
    ['Kanengo Store', 'LV', '1', '1/1/2099'],
  ]);
  const check = (await upload(accounts, '/import/opening-balances/check', bad)).json();
  expect(check).toMatchObject({ ok: false, rows: 5, rowsWithErrors: 4 });
  expect(check.report.map((r: { errors: string[] }) => r.errors)).toEqual([
    [],
    [expect.stringMatching(/^No customer with this name/)],
    ['This customer does not buy from that company.', expect.stringMatching(/^Amount owed/), expect.stringMatching(/^As of date does not/)],
    ['You do not work for this company.'],
    ['Same customer and company as row 3.', 'As of date cannot be after today.'],
  ]);
  expect((await upload(accounts, '/import/opening-balances/save', bad)).statusCode).toBe(400);

  const good = await workbook(accounts, 'opening-balances', [['Kanengo Store', 'Lucky Ventures', 1250000, new Date(Date.UTC(2026, 9, 20))]]);
  expect((await upload(accounts, '/import/opening-balances/save', good)).json()).toEqual({ saved: 1 });
  expect((await get(app, accounts, '/opening-balances')).json()).toEqual([
    expect.objectContaining({ customerId: shopId, companyCode: 'LV', amount: 1250000, asOf: '2026-10-20' }),
  ]);
  const again = (await upload(admin, '/import/opening-balances/check', good)).json();
  expect(again.report[0].errors).toEqual(['The opening balance is already entered. Ask the admin to correct it.']);
  expect((await upload(warehouse, '/import/opening-balances/check', good)).statusCode).toBe(403);
});

test('opening stock import: one "Opening stock" note per company, only for the warehouse and admin', async () => {
  const bad = await workbook(warehouse, 'stock', [
    ['LV-A-1', 'b1', '30/06/2027', 12, 5],
    ['LV-A-1', 'B1', '30/06/2027', 1, ''],
    ['NOPE', 'B9', '30/06/2027', 1, 0],
    ['ZF-C-3', 'B3', '30/06/2027', 1, 0],
    ['ZT-B-2', 'B2', '01/01/2020', 0, 0],
  ]);
  const check = (await upload(warehouse, '/import/stock/check', bad)).json();
  expect(check.report.map((r: { errors: string[] }) => r.errors)).toEqual([
    [],
    ['Same product and batch as row 3.'],
    [expect.stringMatching(/^No product with this code/)],
    ['This product belongs to a company you do not work for.'],
    ['This batch has already expired. Leave it out.', 'Please type how many cartons or pieces.'],
  ]);

  const good = await workbook(warehouse, 'stock', [
    ['lv-a-1', 'b1', '30/06/2027', 12, 5],
    ['ZT-B-2', 'B2', '1 Jan 2028', '', 7],
    ['LV-A-1', 'B4', '2027-12-31', 2, ''],
  ]);
  expect((await upload(accounts, '/import/stock/save', good)).statusCode).toBe(403);
  const saved = (await upload(warehouse, '/import/stock/save', good)).json();
  expect(saved).toMatchObject({ saved: 3, receipts: [expect.stringMatching(/GRN/), expect.stringMatching(/GRN/)] });
  const { rows } = await db.query(
    `select r.company_code as company, r.supplier, p.code, b.batch_no as batch, to_char(b.expiry_date, 'YYYY-MM-DD') as expiry, b.on_hand
     from stock_movements m join stock_batches b on b.id = m.batch_id join products p on p.id = b.product_id
     join stock_receipts r on r.id = m.receipt_id order by p.code, b.batch_no`,
  );
  expect(rows).toEqual([
    { company: 'LV', supplier: 'Opening stock', code: 'LV-A-1', batch: 'B1', expiry: '2027-06-30', on_hand: 125 },
    { company: 'LV', supplier: 'Opening stock', code: 'LV-A-1', batch: 'B4', expiry: '2027-12-31', on_hand: 20 },
    { company: 'ZT', supplier: 'Opening stock', code: 'ZT-B-2', batch: 'B2', expiry: '2028-01-01', on_hand: 7 },
  ]);
  const again = (await upload(admin, '/import/stock/check', good)).json();
  expect(again.report[0].errors).toEqual(['This batch is already in stock.']);
});
