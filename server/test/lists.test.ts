import ExcelJS from 'exceljs';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { nextNumber } from '../src/numbering.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
let accounts: string;
let abbas: string; // sales manager for LV and ZT
let kashif: string; // sales manager for ZF and GL
let salesman: string; // under Abbas, LV only
let driverId: number;

const put = (token: string, url: string, payload: unknown) =>
  app.inject({ method: 'PUT', url, headers: bearer(token), payload: payload as object });

async function addUser(body: Record<string, unknown>) {
  const r = await post(app, admin, '/users', { pin: '1111', ...body });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
  admin = (await login(app, '0990000000', '1234')).token;
  await addUser({ name: 'Accounts', phone: '0992000001', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] });
  const abbasId = await addUser({ name: 'Abbas', phone: '0992000002', role: 'sales_manager', companies: ['LV', 'ZT'] });
  await addUser({ name: 'Kashif', phone: '0992000003', role: 'sales_manager', companies: ['ZF', 'GL'] });
  await addUser({ name: 'Sam', phone: '0992000004', role: 'salesman', managerId: abbasId, companies: ['LV'] });
  driverId = await addUser({ name: 'Banda', phone: '0992000005', role: 'driver', companies: ['LV', 'ZT'] });
  accounts = (await login(app, '0992000001', '1111')).token;
  abbas = (await login(app, '0992000002', '1111')).token;
  kashif = (await login(app, '0992000003', '1111')).token;
  salesman = (await login(app, '0992000004', '1111')).token;
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('admin fills in company details; others can read only their own companies', async () => {
  const r = await put(admin, '/companies/LV', { tpin: '12345678', vatRate: 16.5, phone: '0991 111 222', email: 'lv@example.com', address: 'Area 3' });
  expect(r.statusCode).toBe(200);
  expect(r.json()).toMatchObject({ code: 'LV', tpin: '12345678', vatRate: 16.5, phone: '0991111222', address: 'Area 3' });
  expect((await get(app, abbas, '/companies/LV')).json().tpin).toBe('12345678');
  expect((await get(app, kashif, '/companies/LV')).statusCode).toBe(403);
  expect((await put(accounts, '/companies/LV', { address: 'x' })).statusCode).toBe(403);
  expect((await put(admin, '/companies/LV', { email: 'not an email' })).statusCode).toBe(400);
});

test('customers: sales managers add for their own companies only, and see only their companies', async () => {
  const shared = await post(app, admin, '/customers', {
    name: 'City Centre Mart',
    channel: 'supermarket',
    area: 'City Centre',
    phone: '+265 888 220 114',
    needsLpo: true,
    companies: [
      { code: 'LV', paymentTermsDays: 45 },
      { code: 'ZF', paymentTermsDays: 30 },
    ],
  });
  expect(shared.statusCode).toBe(201);
  expect(shared.json()).toMatchObject({ phone: '0888220114', needsLpo: true, companies: [{ code: 'LV' }, { code: 'ZF' }] });
  const sharedId = shared.json().id;

  const own = await post(app, abbas, '/customers', { name: 'Area 47 Superstore', channel: 'supermarket', companies: [{ code: 'LV', paymentTermsDays: 30 }] });
  expect(own.statusCode).toBe(201);
  expect((await post(app, abbas, '/customers', { name: 'Not Mine', channel: 'wholesaler', companies: [{ code: 'GL', paymentTermsDays: 30 }] })).statusCode).toBe(403);
  expect((await post(app, salesman, '/customers', { name: 'Nope', channel: 'wholesaler', companies: [{ code: 'LV', paymentTermsDays: 0 }] })).statusCode).toBe(403);

  // Abbas sees the shared customer with only the LV terms; Kashif sees only the ZF terms.
  const forAbbas = (await get(app, abbas, `/customers/${sharedId}`)).json();
  expect(forAbbas.companies).toEqual([{ code: 'LV', paymentTermsDays: 45, creditLimit: null }]);
  const forKashif = (await get(app, kashif, '/customers')).json();
  expect(forKashif.map((c: { name: string }) => c.name)).toEqual(['City Centre Mart']);
  expect(forKashif[0].companies).toEqual([{ code: 'ZF', paymentTermsDays: 30, creditLimit: null }]);

  // A sales manager cannot change a customer shared with a company they do not run.
  expect((await put(abbas, `/customers/${sharedId}`, { area: 'Moved' })).statusCode).toBe(403);
  expect((await put(abbas, `/customers/${own.json().id}`, { area: 'Area 47' })).json().area).toBe('Area 47');
});

test('customer names are unique, cash-only customers get 0 days, search works', async () => {
  const dup = await post(app, admin, '/customers', { name: 'city centre mart', channel: 'supermarket', companies: [{ code: 'LV', paymentTermsDays: 0 }] });
  expect(dup.statusCode).toBe(400);
  expect(dup.json().message).toMatch(/already exists/);

  const cash = await post(app, admin, '/customers', {
    name: 'Kauma Market Traders',
    channel: 'minibus',
    cashOnly: true,
    companies: [{ code: 'ZT', paymentTermsDays: 30 }],
  });
  expect(cash.json().companies).toEqual([{ code: 'ZT', paymentTermsDays: 0, creditLimit: null }]);

  const found = (await get(app, admin, '/customers?q=kauma')).json();
  expect(found.map((c: { name: string }) => c.name)).toEqual(['Kauma Market Traders']);
  expect((await get(app, admin, '/customers?q=%25')).json()).toEqual([]);
});

test('switched off customers are hidden unless asked for', async () => {
  const c = (await post(app, admin, '/customers', { name: 'Old Shop', channel: 'wholesaler', companies: [{ code: 'GL', paymentTermsDays: 30 }] })).json();
  await put(admin, `/customers/${c.id}`, { active: false });
  expect((await get(app, admin, '/customers?q=old')).json()).toEqual([]);
  expect((await get(app, admin, '/customers?q=old&includeInactive=true')).json()).toHaveLength(1);
});

test('products: admin and accounts edit, sales roles only see their companies', async () => {
  const p = await post(app, accounts, '/products', {
    code: 'lv-kg-400t',
    companyCode: 'LV',
    brand: 'Kerrygold',
    name: 'Milk powder tin',
    packSize: '400g tin',
    piecesPerCarton: 24,
  });
  expect(p.statusCode).toBe(201);
  expect(p.json().code).toBe('LV-KG-400T');
  await post(app, admin, '/products', { code: 'GL-PN-050', companyCode: 'GL', brand: 'Greenland', name: 'Peanuts', packSize: '50g', piecesPerCarton: 100 });
  await post(app, admin, '/products', { code: 'ZT-MG-050', companyCode: 'ZT', brand: 'Mygold', name: 'Tea, box', packSize: '50g', piecesPerCarton: 100 });

  expect((await post(app, abbas, '/products', { code: 'X', companyCode: 'LV', brand: 'x', name: 'x', packSize: 'x', piecesPerCarton: 1 })).statusCode).toBe(403);
  expect((await post(app, admin, '/products', { code: 'LV-KG-400T', companyCode: 'LV', brand: 'x', name: 'x', packSize: 'x', piecesPerCarton: 1 })).statusCode).toBe(400);

  const forSalesman = (await get(app, salesman, '/products')).json();
  expect(forSalesman.map((x: { code: string }) => x.code)).toEqual(['LV-KG-400T']);
  const forAbbas = (await get(app, abbas, '/products')).json();
  expect(forAbbas.map((x: { companyCode: string }) => x.companyCode)).toEqual(['LV', 'ZT']);
  expect((await get(app, kashif, `/products/${p.json().id}`)).statusCode).toBe(404);

  const edited = await put(admin, `/products/${p.json().id}`, { piecesPerCarton: 12 });
  expect(edited.json().piecesPerCarton).toBe(12);
});

test('vehicles: admin adds them with a usual driver; everyone can list them', async () => {
  const v = await post(app, admin, '/vehicles', { plate: 'mz 4421', description: '3 ton truck', facilityCode: 'F1', defaultDriverId: driverId });
  expect(v.statusCode).toBe(201);
  expect(v.json()).toMatchObject({ plate: 'MZ 4421', defaultDriverName: 'Banda' });
  expect((await post(app, admin, '/vehicles', { plate: 'MZ 4421' })).statusCode).toBe(400);
  expect((await post(app, admin, '/vehicles', { plate: 'LL 1', defaultDriverId: 1 })).statusCode).toBe(400);
  expect((await post(app, salesman, '/vehicles', { plate: 'LL 2' })).statusCode).toBe(403);
  expect((await get(app, salesman, '/vehicles')).json()).toHaveLength(1);
  expect((await get(app, abbas, '/drivers')).json().map((d: { name: string }) => d.name)).toEqual(['Banda']);
});

test('opening balance: Accounts enters it once, only the admin can correct it', async () => {
  const c = (await get(app, admin, '/customers?q=area 47')).json()[0];
  const first = await put(accounts, `/customers/${c.id}/opening-balance`, { companyCode: 'LV', amount: 1250000, asOf: '2026-11-01' });
  expect(first.statusCode).toBe(200);
  const again = await put(accounts, `/customers/${c.id}/opening-balance`, { companyCode: 'LV', amount: 1, asOf: '2026-11-01' });
  expect(again.statusCode).toBe(400);
  expect((await put(admin, `/customers/${c.id}/opening-balance`, { companyCode: 'LV', amount: 1300000, asOf: '2026-11-01' })).statusCode).toBe(200);
  expect((await put(accounts, `/customers/${c.id}/opening-balance`, { companyCode: 'GL', amount: 5, asOf: '2026-11-01' })).statusCode).toBe(400);
  expect((await put(abbas, `/customers/${c.id}/opening-balance`, { companyCode: 'LV', amount: 5, asOf: '2026-11-01' })).statusCode).toBe(403);

  const list = (await get(app, accounts, '/opening-balances')).json();
  expect(list).toEqual([expect.objectContaining({ customerName: 'Area 47 Superstore', companyCode: 'LV', amount: 1300000, asOf: '2026-11-01' })]);
  const log = (await get(app, admin, '/audit')).json().map((a: { action: string }) => a.action);
  expect(log).toContain('opening_balance_entered');
  expect(log).toContain('opening_balance_corrected');
});

test('document numbers run per company and type, never repeat, and roll back with their document', async () => {
  expect(await nextNumber(db, 'LV', 'SO')).toBe('LV-SO-000001');
  expect(await nextNumber(db, 'LV', 'SO')).toBe('LV-SO-000002');
  expect(await nextNumber(db, 'ZT', 'SO')).toBe('ZT-SO-000001');
  expect(await nextNumber(db, 'LV', 'INV')).toBe('LV-INV-000001');

  const client = await db.connect();
  await client.query('begin');
  expect(await nextNumber(client, 'LV', 'SO')).toBe('LV-SO-000003');
  await client.query('rollback');
  client.release();

  const many = await Promise.all(Array.from({ length: 20 }, () => nextNumber(db, 'GL', 'SO')));
  expect(new Set(many).size).toBe(20);
  expect(await nextNumber(db, 'LV', 'SO')).toBe('LV-SO-000003');
});

async function workbook(kind: 'customers' | 'products', rows: (string | number)[][]) {
  const template = await app.inject({ method: 'GET', url: `/import/${kind}/template`, headers: bearer(admin) });
  expect(template.statusCode).toBe(200);
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(template.rawPayload as unknown as ArrayBuffer);
  const ws = wb.worksheets[0]!;
  for (const r of rows) ws.addRow(r);
  return Buffer.from(await wb.xlsx.writeBuffer());
}

const upload = (token: string, url: string, file: Buffer) =>
  app.inject({ method: 'POST', url, headers: { ...bearer(token), 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }, payload: file });

test('customer import: shows every problem and saves nothing until the file is clean', async () => {
  const bad = await workbook('customers', [
    ['Bwaila Cash & Carry', 'Wholesaler', 'Area 2', '0995 501 340', 'LV, ZT', 30, 'Yes', 'No'],
    ['City Centre Mart', 'Shop', '', '12', 'XX', 'soon', 'Maybe', 'No'],
    ['Bwaila cash & carry', 'Wholesaler', '', '', 'LV', 30, 'No', 'No'],
  ]);
  const check = (await upload(admin, '/import/customers/check', bad)).json();
  expect(check).toMatchObject({ ok: false, rows: 3, rowsWithErrors: 2 });
  expect(check.report[0].errors).toEqual([]);
  expect(check.report[1].row).toBe(4);
  expect(check.report[1].errors).toEqual(
    expect.arrayContaining([
      'A customer with this name already exists.',
      expect.stringMatching(/^Channel must be/),
      'Phone number does not look right.',
      expect.stringMatching(/unknown company/),
      expect.stringMatching(/^Payment terms/),
      'Needs LPO must be Yes or No.',
    ]),
  );
  expect(check.report[2].errors).toEqual(['Same name as row 3.']);

  const refused = await upload(admin, '/import/customers/save', bad);
  expect(refused.statusCode).toBe(400);
  expect((await get(app, admin, '/customers?q=bwaila')).json()).toEqual([]);

  const good = await workbook('customers', [
    ['Bwaila Cash & Carry', 'Wholesaler', 'Area 2', '0995 501 340', 'LV, ZT', 30, 'Yes', 'No'],
    ['Salima Boma Shops', 'Upcountry', 'Salima', '', 'Greenland', '', 'No', 'Yes'],
  ]);
  expect((await upload(accounts, '/import/customers/save', good)).json()).toEqual({ saved: 2 });
  const bwaila = (await get(app, admin, '/customers?q=bwaila')).json()[0];
  expect(bwaila).toMatchObject({ channel: 'wholesaler', phone: '0995501340', needsLpo: true, companies: [{ code: 'LV', paymentTermsDays: 30 }, { code: 'ZT', paymentTermsDays: 30 }] });
  expect((await get(app, admin, '/customers?q=salima')).json()[0].companies).toEqual([{ code: 'GL', paymentTermsDays: 0, creditLimit: null }]);
});

test('product import, and who may use the import', async () => {
  const file = await workbook('products', [
    ['ZF-BC-400', 'ZF', 'Big Cow', 'Milk powder', '400g', 24],
    ['ZF-TT-100', 'Zangafee', 'Tasty', 'Tea powder', '100g', 48],
  ]);
  expect((await upload(abbas, '/import/products/save', file)).statusCode).toBe(403);
  expect((await upload(admin, '/import/products/save', file)).json()).toEqual({ saved: 2 });
  const again = (await upload(admin, '/import/products/check', file)).json();
  expect(again.report.map((r: { errors: string[] }) => r.errors)).toEqual([['A product with this code already exists.'], ['A product with this code already exists.']]);

  const wrongColumns = new ExcelJS.Workbook();
  wrongColumns.addWorksheet('x').addRow(['Something', 'Else']);
  const r = await upload(admin, '/import/products/check', Buffer.from(await wrongColumns.xlsx.writeBuffer()));
  expect(r.statusCode).toBe(400);
  expect(r.json().message).toMatch(/do not match the template/);
  expect((await upload(admin, '/import/products/check', Buffer.from('not excel'))).json().message).toMatch(/could not be opened/);
});

test('a partial update keeps the fields it does not send', async () => {
  const v = await post(app, admin, '/vehicles', { plate: 'BT 9001', description: 'Isuzu 7 ton', defaultDriverId: null });
  expect(v.statusCode).toBe(201);
  const upd = await put(admin, `/vehicles/${v.json().id}`, { defaultDriverId: driverId });
  expect(upd.statusCode).toBe(200);
  expect(upd.json()).toMatchObject({ description: 'Isuzu 7 ton', defaultDriverId: driverId });

  const c = await post(app, admin, '/customers', {
    name: 'Keep Me Stores',
    channel: 'wholesaler',
    area: 'Area 25',
    needsLpo: true,
    cashOnly: false,
    companies: [{ code: 'LV', paymentTermsDays: 14 }, { code: 'ZF', paymentTermsDays: 30 }],
  });
  expect(c.statusCode).toBe(201);
  const off = await put(admin, `/customers/${c.json().id}`, { active: false });
  expect(off.statusCode).toBe(200);
  expect(off.json()).toMatchObject({ active: false, area: 'Area 25', needsLpo: true, cashOnly: false });
  expect(off.json().companies).toEqual([{ code: 'LV', paymentTermsDays: 14, creditLimit: null }, { code: 'ZF', paymentTermsDays: 30, creditLimit: null }]);
});

test('an accounts user with fewer companies cannot delete the ones they cannot see', async () => {
  const limited = await addUser({ name: 'Accounts LV', phone: '0992000010', role: 'accounts', companies: ['LV'] });
  expect(limited).toBeGreaterThan(0);
  const token = (await login(app, '0992000010', '1111')).token;
  const c = await post(app, admin, '/customers', {
    name: 'Two Company Shop',
    channel: 'supermarket',
    companies: [{ code: 'LV', paymentTermsDays: 7 }, { code: 'ZT', paymentTermsDays: 21 }],
  });
  const id = c.json().id;
  const r = await put(token, `/customers/${id}`, { companies: [{ code: 'LV', paymentTermsDays: 10 }] });
  expect(r.statusCode).toBe(200);
  expect(r.json().companies).toEqual([{ code: 'LV', paymentTermsDays: 10, creditLimit: null }]);
  expect((await get(app, admin, `/customers/${id}`)).json().companies).toEqual([
    { code: 'LV', paymentTermsDays: 10, creditLimit: null },
    { code: 'ZT', paymentTermsDays: 21, creditLimit: null },
  ]);
  const name = await put(token, `/customers/${id}`, { area: 'New area' });
  expect(name.statusCode).toBe(200);
  expect((await get(app, admin, `/customers/${id}`)).json().companies).toHaveLength(2);
});

test('canEdit says whether the caller may change the customer', async () => {
  const mixed = await post(app, admin, '/customers', {
    name: 'ZF And LV Shop',
    channel: 'wholesaler',
    companies: [{ code: 'ZF', paymentTermsDays: 0 }, { code: 'LV', paymentTermsDays: 0 }],
  });
  const only = await post(app, admin, '/customers', { name: 'ZF Only Shop', channel: 'wholesaler', companies: [{ code: 'ZF', paymentTermsDays: 0 }] });
  expect(mixed.json().canEdit).toBe(true);
  const list = (await get(app, kashif, '/customers')).json() as { name: string; canEdit: boolean }[];
  expect(list.find((c) => c.name === 'ZF And LV Shop')!.canEdit).toBe(false);
  expect(list.find((c) => c.name === 'ZF Only Shop')!.canEdit).toBe(true);
  expect((await get(app, kashif, `/customers/${mixed.json().id}`)).json().canEdit).toBe(false);
  expect((await get(app, kashif, `/customers/${only.json().id}`)).json().canEdit).toBe(true);
  expect((await get(app, accounts, `/customers/${mixed.json().id}`)).json().canEdit).toBe(true);
  expect((await get(app, salesman, '/customers')).json().every((c: { canEdit: boolean }) => c.canEdit === false)).toBe(true);
});

test('customers keep the details printed on VAT invoices', async () => {
  const c = await post(app, admin, '/customers', {
    name: 'Invoice Details Mart',
    channel: 'supermarket',
    address: 'Plot 12, Area 3',
    city: 'Lilongwe',
    tpin: '11112222',
    regNo: 'R-77',
    companies: [{ code: 'LV', paymentTermsDays: 30 }],
  });
  expect(c.statusCode).toBe(201);
  expect(c.json()).toMatchObject({ address: 'Plot 12, Area 3', city: 'Lilongwe', tpin: '11112222', regNo: 'R-77' });
  const edited = await put(admin, `/customers/${c.json().id}`, { city: 'Blantyre' });
  expect(edited.json()).toMatchObject({ address: 'Plot 12, Area 3', city: 'Blantyre', tpin: '11112222' });
  expect((await get(app, admin, '/customers?q=blantyre')).json().map((x: { name: string }) => x.name)).toEqual(['Invoice Details Mart']);
});

test('customer import reads the new invoice columns, and older files without them', async () => {
  const withDetails = await workbook('customers', [['Detail Shop', 'Wholesaler', 'Area 2', '', 'LV', 30, 'No', 'No', 'Box 5', 'Mzuzu', '33334444', 'R-1']]);
  expect((await upload(admin, '/import/customers/save', withDetails)).json()).toEqual({ saved: 1 });
  expect((await get(app, admin, '/customers?q=detail shop')).json()[0]).toMatchObject({ address: 'Box 5', city: 'Mzuzu', tpin: '33334444', regNo: 'R-1' });

  // An older file: only the first 8 columns, plus a column of our own notes.
  const old = new ExcelJS.Workbook();
  const ws = old.addWorksheet('Customers');
  ws.addRow(['Name', 'Channel', 'Area', 'Phone', 'Companies', 'Payment terms (days)', 'Needs LPO', 'Cash only', 'Check']);
  ws.addRow(['Old Format Shop', 'Upcountry', 'Salima', '991 234 500', 'GL', '', 'No', 'Yes', 'fill Channel']);
  const r = await upload(admin, '/import/customers/save', Buffer.from(await old.xlsx.writeBuffer()));
  expect(r.json()).toEqual({ saved: 1 });
  expect((await get(app, admin, '/customers?q=old format')).json()[0]).toMatchObject({ phone: '0991234500', address: '', city: '' });
});
