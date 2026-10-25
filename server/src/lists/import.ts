import ExcelJS from 'exceljs';
import type { FastifyInstance, FastifyReply } from 'fastify';
import { audit } from '../audit.js';
import type { PoolClient } from 'pg';
import { HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { nextNumber } from '../numbering.js';
import { STOCK_KEEPERS, TODAY } from '../stock/stock.js';
import { isValidPhone, normalisePhone } from '../users.js';
import { CHANNEL_LABELS, CHANNELS, LIST_EDITORS, type Channel } from './common.js';
import { insertCustomer, type CleanCustomer } from './customers.js';
import { insertProduct, type NewProduct } from './products.js';

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const MAX_ROWS = 5000;

// Optional columns may be left out of the file, so files made from the older template still import.
type Column = { header: string; width: number; example: string; optional?: boolean };
type Kind = 'customers' | 'products' | 'opening-balances' | 'stock';
const KINDS: Kind[] = ['customers', 'products', 'opening-balances', 'stock'];
const SHEETS: Record<Kind, string> = { customers: 'Customers', products: 'Products', 'opening-balances': 'Opening balances', stock: 'Opening stock' };

const COLUMNS: Record<Kind, Column[]> = {
  customers: [
    { header: 'Name', width: 32, example: 'Area 47 Superstore' },
    { header: 'Channel', width: 26, example: 'Supermarket' },
    { header: 'Area', width: 24, example: 'Area 47, Lilongwe' },
    { header: 'Phone', width: 16, example: '0991 234 567' },
    { header: 'Companies', width: 16, example: 'LV, ZT' },
    { header: 'Payment terms (days)', width: 20, example: '30' },
    { header: 'Needs LPO', width: 12, example: 'Yes' },
    { header: 'Cash only', width: 12, example: 'No' },
    { header: 'Address', width: 28, example: 'Plot 47/3/12, Area 47', optional: true },
    { header: 'City', width: 16, example: 'Lilongwe', optional: true },
    { header: 'TPIN', width: 14, example: '12345678', optional: true },
    { header: 'Reg No', width: 14, example: '', optional: true },
  ],
  products: [
    { header: 'Code', width: 16, example: 'LV-KG-400T' },
    { header: 'Company', width: 12, example: 'LV' },
    { header: 'Brand', width: 18, example: 'Kerrygold' },
    { header: 'Name', width: 28, example: 'Milk powder tin' },
    { header: 'Pack size', width: 16, example: '400g tin' },
    { header: 'Pieces per carton', width: 18, example: '24' },
    { header: 'VAT exempt', width: 12, example: 'No', optional: true },
  ],
  'opening-balances': [
    { header: 'Customer name', width: 32, example: 'Area 47 Superstore' },
    { header: 'Company', width: 12, example: 'LV' },
    { header: 'Amount owed', width: 16, example: '1,250,000' },
    { header: 'As of date', width: 14, example: '31/10/2026' },
  ],
  stock: [
    { header: 'Product code', width: 16, example: 'LV-KG-400T' },
    { header: 'Batch', width: 16, example: 'B2611' },
    { header: 'Expiry date', width: 14, example: '30/06/2027' },
    { header: 'Cartons', width: 10, example: '12' },
    { header: 'Pieces', width: 10, example: '5' },
  ],
};

const HELP: Record<Kind, string[]> = {
  customers: [
    'One customer per row, starting on row 3. Delete the example on row 2.',
    `Channel: ${CHANNELS.map((c) => CHANNEL_LABELS[c]).join(', ')}.`,
    'Companies: codes separated by commas. LV = Lucky Ventures, ZT = Zoona Trading, ZF = Zangafee, GL = Greenland.',
    'Payment terms: days to pay, used for every company on the row. Leave empty or 0 for cash customers.',
    'Needs LPO and Cash only: Yes or No.',
    'Address, City, TPIN and Reg No: printed on VAT invoices. Fill them in for supermarkets and wholesalers.',
  ],
  products: [
    'One product per row, starting on row 3. Delete the example on row 2.',
    'Every pack size is its own product with its own code.',
    'Company: LV, ZT, ZF or GL.',
    'Pieces per carton: a whole number.',
    'VAT exempt: Yes for a product charged no VAT. Leave empty or No for the others.',
  ],
  'opening-balances': [
    'What each customer owed on the day you start using the app. One row per customer and company, starting on row 3. Delete the example on row 2.',
    'Customer name: exactly as on the customer list. Import the customers first.',
    'Company: LV, ZT, ZF or GL. A customer who owes two companies gets two rows.',
    'Amount owed: whole kwacha, for example 1,250,000. Leave out customers who owe nothing.',
    'As of date: the day the amount was counted, for example 31/10/2026.',
    'A balance already entered is not changed here. The admin corrects it on the customer.',
  ],
  stock: [
    'What is in the warehouse on the day you start using the app. One row per product and batch, starting on row 3. Delete the example on row 2.',
    'Product code: exactly as on the product list. Import the products first.',
    'Batch: the batch number on the carton. Expiry date: for example 30/06/2027.',
    'Cartons and Pieces: how many full cartons, and how many loose pieces. Leave Pieces empty if there are none.',
    'Each company gets one goods received note called "Opening stock".',
  ],
};

export type RowReport = { row: number; values: Record<string, string>; errors: string[] };

async function template(kind: Kind): Promise<Buffer> {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet(SHEETS[kind]);
  const cols = COLUMNS[kind];
  ws.columns = cols.map((c) => ({ header: c.header, width: c.width }));
  ws.getRow(1).font = { bold: true, color: { argb: 'FFFFFFFF' } };
  ws.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF7A1F35' } };
  const example = ws.addRow(cols.map((c) => c.example));
  example.font = { italic: true, color: { argb: 'FF8A7A7F' } };
  ws.views = [{ state: 'frozen', ySplit: 1 }];
  const help = wb.addWorksheet('How to fill');
  help.getColumn(1).width = 100;
  HELP[kind].forEach((line) => help.addRow([line]));
  return Buffer.from(await wb.xlsx.writeBuffer());
}

const cellText = (v: ExcelJS.CellValue): string => {
  if (v == null) return '';
  if (typeof v === 'object') {
    if ('richText' in v) return v.richText.map((r) => r.text).join('').trim();
    if ('text' in v) return String(v.text).trim();
    if ('result' in v) return v.result == null ? '' : String(v.result).trim();
    if (v instanceof Date) return v.toISOString().slice(0, 10);
  }
  return String(v).trim();
};

// Reads the first sheet. Row 1 must have the template's headers; the template's example row is skipped.
async function readRows(kind: Kind, file: Buffer): Promise<{ row: number; values: Record<string, string> }[]> {
  const wb = new ExcelJS.Workbook();
  try {
    await wb.xlsx.load(file as unknown as ArrayBuffer);
  } catch {
    throw new HttpError(400, 'invalid', 'This file could not be opened. Please upload the Excel template (.xlsx).');
  }
  const ws = wb.worksheets[0];
  if (!ws) throw new HttpError(400, 'invalid', 'The file has no sheets.');
  const all = COLUMNS[kind];
  const width = Math.max(ws.getRow(1).cellCount, all.filter((c) => !c.optional).length);
  const headers = Array.from({ length: width }, (_, i) => cellText(ws.getRow(1).getCell(i + 1).value).toLowerCase());
  const required = all.filter((c) => !c.optional);
  const cols = all.filter((c, i) => !c.optional || headers[i] === c.header.toLowerCase());
  if (required.some((c, i) => headers[i] !== c.header.toLowerCase()) || cols.some((c, i) => headers[i] !== c.header.toLowerCase())) {
    throw new HttpError(400, 'invalid', `The columns do not match the template. Row 1 should be: ${all.map((c) => c.header).join(', ')}.`);
  }
  const example = cols.map((c) => c.example);
  const rows: { row: number; values: Record<string, string> }[] = [];
  ws.eachRow({ includeEmpty: false }, (r, n) => {
    if (n === 1) return;
    const texts = cols.map((_, i) => cellText(r.getCell(i + 1).value));
    if (texts.every((t) => t === '')) return;
    if (texts.every((t, i) => t === example[i])) return;
    rows.push({ row: n, values: Object.fromEntries(all.map((c) => [c.header, texts[cols.indexOf(c)] ?? ''])) });
  });
  if (rows.length === 0) throw new HttpError(400, 'invalid', 'The file has no rows to import.');
  if (rows.length > MAX_ROWS) throw new HttpError(400, 'invalid', `The file has more than ${MAX_ROWS} rows. Please split it.`);
  return rows;
}

const yesNo = (v: string): boolean | null => {
  const s = v.toLowerCase();
  if (s === '' || s === 'no' || s === 'n') return false;
  if (s === 'yes' || s === 'y') return true;
  return null;
};

function channelOf(v: string): Channel | null {
  const s = v.toLowerCase().trim();
  for (const c of CHANNELS) {
    if (s === c || s === CHANNEL_LABELS[c].toLowerCase()) return c;
  }
  if (s.startsWith('minibus') || s.includes('local market')) return 'minibus';
  if (s.startsWith('upcountry') || s.startsWith('up country')) return 'upcountry';
  return null;
}

const MONTH_WORDS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

// A date as typed in Excel: 2026-10-31, 31/10/2026, 31-10-2026 or 31 Oct 2026 (day first, as in Malawi).
export function dateOf(v: string): string | null {
  let y: number, m: number, d: number;
  let r: RegExpMatchArray | null;
  if ((r = v.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/))) [y, m, d] = [Number(r[1]), Number(r[2]), Number(r[3])];
  else if ((r = v.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/))) [d, m, y] = [Number(r[1]), Number(r[2]), Number(r[3])];
  else if ((r = v.match(/^(\d{1,2})[\s-]+([a-z]{3})[a-z]*[\s-]+(\d{4})$/i)) && MONTH_WORDS.includes(r[2]!.toLowerCase())) {
    [d, m, y] = [Number(r[1]), MONTH_WORDS.indexOf(r[2]!.toLowerCase()) + 1, Number(r[3])];
  } else return null;
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return date.toISOString().slice(0, 10);
}

// A whole number as typed: "1,250,000", "1 250 000" or 1250000. Empty is null.
const wholeOf = (v: string): number | null => {
  const t = v.replace(/[,\s]/g, '').replace(/^MWK/i, '');
  return /^\d+$/.test(t) ? Number(t) : null;
};

async function todayText(db: Db): Promise<string> {
  return (await db.query<{ d: string }>(`select to_char(${TODAY}, 'YYYY-MM-DD') as d`)).rows[0]!.d;
}

async function companyLookup(db: Db): Promise<Map<string, string>> {
  const { rows } = await db.query<{ code: string; name: string }>('select code, name from companies');
  const map = new Map<string, string>();
  for (const r of rows) {
    map.set(r.code.toLowerCase(), r.code);
    map.set(r.name.toLowerCase(), r.code);
  }
  return map;
}

async function checkCustomers(db: Db, rows: { row: number; values: Record<string, string> }[]) {
  const companies = await companyLookup(db);
  const taken = new Set((await db.query<{ n: string }>('select lower(name) as n from customers where deleted_at is null')).rows.map((r) => r.n));
  const seen = new Map<string, number>();
  const reports: RowReport[] = [];
  const good: CleanCustomer[] = [];
  for (const { row, values } of rows) {
    const errors: string[] = [];
    const name = values['Name']!;
    if (!name) errors.push('Name is empty.');
    else if (taken.has(name.toLowerCase())) errors.push('A customer with this name already exists.');
    else if (seen.has(name.toLowerCase())) errors.push(`Same name as row ${seen.get(name.toLowerCase())}.`);
    if (name) seen.set(name.toLowerCase(), row);

    const channel = channelOf(values['Channel']!);
    if (!channel) errors.push(`Channel must be one of: ${CHANNELS.map((c) => CHANNEL_LABELS[c]).join(', ')}.`);

    let phone: string | null = null;
    if (values['Phone']) {
      phone = normalisePhone(values['Phone']);
      if (!isValidPhone(phone)) errors.push('Phone number does not look right.');
    }

    const codes = values['Companies']!.split(/[,;/]/).map((s) => s.trim()).filter(Boolean);
    const resolved = codes.map((c) => companies.get(c.toLowerCase()));
    if (codes.length === 0) errors.push('Companies is empty.');
    else if (resolved.some((c) => !c)) errors.push('Companies has an unknown company. Use LV, ZT, ZF or GL.');

    const termsText = values['Payment terms (days)']!;
    const terms = termsText === '' ? 0 : Number(termsText);
    if (!Number.isInteger(terms) || terms < 0 || terms > 365) errors.push('Payment terms must be a whole number of days from 0 to 365.');

    const needsLpo = yesNo(values['Needs LPO']!);
    if (needsLpo === null) errors.push('Needs LPO must be Yes or No.');
    const cashOnly = yesNo(values['Cash only']!);
    if (cashOnly === null) errors.push('Cash only must be Yes or No.');

    reports.push({ row, values, errors });
    if (errors.length === 0) {
      const uniqueCodes = [...new Set(resolved as string[])];
      good.push({
        name,
        channel: channel!,
        area: values['Area']!,
        address: values['Address']!,
        city: values['City']!,
        tpin: values['TPIN']!,
        regNo: values['Reg No']!,
        phone,
        needsLpo: needsLpo!,
        cashOnly: cashOnly!,
        companies: uniqueCodes.map((code) => ({ code, paymentTermsDays: cashOnly ? 0 : terms })),
      });
    }
  }
  return { reports, good };
}

async function checkProducts(db: Db, rows: { row: number; values: Record<string, string> }[]) {
  const companies = await companyLookup(db);
  const taken = new Set((await db.query<{ code: string }>('select code from products where deleted_at is null')).rows.map((r) => r.code));
  const seen = new Map<string, number>();
  const reports: RowReport[] = [];
  const good: NewProduct[] = [];
  for (const { row, values } of rows) {
    const errors: string[] = [];
    const code = values['Code']!.toUpperCase();
    if (!code) errors.push('Code is empty.');
    else if (taken.has(code)) errors.push('A product with this code already exists.');
    else if (seen.has(code)) errors.push(`Same code as row ${seen.get(code)}.`);
    if (code) seen.set(code, row);
    const company = companies.get(values['Company']!.toLowerCase());
    if (!company) errors.push('Company must be LV, ZT, ZF or GL.');
    for (const field of ['Brand', 'Name', 'Pack size']) if (!values[field]) errors.push(`${field} is empty.`);
    const pieces = Number(values['Pieces per carton']);
    if (!Number.isInteger(pieces) || pieces <= 0) errors.push('Pieces per carton must be a whole number above 0.');
    const vatExempt = values['VAT exempt'] ? yesNo(values['VAT exempt']) : false;
    if (vatExempt === null) errors.push('VAT exempt must be Yes or No.');
    reports.push({ row, values, errors });
    if (errors.length === 0) {
      good.push({
        code,
        companyCode: company!,
        brand: values['Brand']!,
        name: values['Name']!,
        packSize: values['Pack size']!,
        piecesPerCarton: pieces,
        vatExempt: vatExempt!,
        lowStockCartons: null,
      });
    }
  }
  return { reports, good };
}

type Rows = { row: number; values: Record<string, string> }[];
type NewBalance = { customerId: number; companyCode: string; amount: number; asOf: string };
type NewStock = { companyCode: string; productId: number; batchNo: string; expiryDate: string; pieces: number };

async function checkOpeningBalances(db: Db, user: AuthUser, rows: Rows) {
  const companies = await companyLookup(db);
  const today = await todayText(db);
  const { rows: customers } = await db.query<{ id: number; n: string; codes: string[] }>(
    `select c.id, lower(c.name) as n, array(select cc.company_code from customer_companies cc where cc.customer_id = c.id) as codes
     from customers c where c.deleted_at is null`,
  );
  const byName = new Map(customers.map((c) => [c.n, c]));
  const entered = new Set(
    (await db.query<{ k: string }>(`select customer_id || '/' || company_code as k from opening_balances`)).rows.map((r) => r.k),
  );
  const seen = new Map<string, number>();
  const reports: RowReport[] = [];
  const good: NewBalance[] = [];
  for (const { row, values } of rows) {
    const errors: string[] = [];
    const name = values['Customer name']!;
    const customer = byName.get(name.toLowerCase());
    if (!name) errors.push('Customer name is empty.');
    else if (!customer) errors.push('No customer with this name. Check the spelling, or import the customer first.');
    const company = companies.get(values['Company']!.toLowerCase());
    if (!company) errors.push('Company must be LV, ZT, ZF or GL.');
    else if (!user.companies.includes(company)) errors.push('You do not work for this company.');
    else if (customer && !customer.codes.includes(company)) errors.push('This customer does not buy from that company.');
    if (customer && company) {
      const key = `${customer.id}/${company}`;
      if (entered.has(key)) errors.push('The opening balance is already entered. Ask the admin to correct it.');
      else if (seen.has(key)) errors.push(`Same customer and company as row ${seen.get(key)}.`);
      seen.set(key, row);
    }
    const amount = wholeOf(values['Amount owed']!);
    if (amount === null || amount > 1e13) errors.push('Amount owed must be whole kwacha, for example 1,250,000.');
    const asOf = dateOf(values['As of date']!);
    if (!asOf) errors.push('As of date does not look right. Type it like 31/10/2026.');
    else if (asOf > today) errors.push('As of date cannot be after today.');
    reports.push({ row, values, errors });
    if (errors.length === 0) good.push({ customerId: customer!.id, companyCode: company!, amount: amount!, asOf: asOf! });
  }
  return { reports, good };
}

async function checkStock(db: Db, user: AuthUser, rows: Rows) {
  const today = await todayText(db);
  const { rows: products } = await db.query<{ id: number; code: string; companyCode: string; ppc: number }>(
    `select id, upper(code) as code, company_code as "companyCode", pieces_per_carton as ppc from products
     where deleted_at is null and active`,
  );
  const byCode = new Map(products.map((p) => [p.code, p]));
  const inStock = new Set(
    (await db.query<{ k: string }>(`select product_id || '/' || upper(batch_no) as k from stock_batches`)).rows.map((r) => r.k),
  );
  const seen = new Map<string, number>();
  const reports: RowReport[] = [];
  const good: NewStock[] = [];
  for (const { row, values } of rows) {
    const errors: string[] = [];
    const code = values['Product code']!.toUpperCase();
    const product = byCode.get(code);
    if (!code) errors.push('Product code is empty.');
    else if (!product) errors.push('No product with this code. Check the code, or import the product first.');
    else if (!user.companies.includes(product.companyCode)) errors.push('This product belongs to a company you do not work for.');
    const batchNo = values['Batch']!.toUpperCase();
    if (!batchNo) errors.push('Batch is empty.');
    else if (batchNo.length > 60) errors.push('Batch is too long.');
    if (product && batchNo) {
      const key = `${product.id}/${batchNo}`;
      if (inStock.has(key)) errors.push('This batch is already in stock.');
      else if (seen.has(key)) errors.push(`Same product and batch as row ${seen.get(key)}.`);
      seen.set(key, row);
    }
    const expiryDate = dateOf(values['Expiry date']!);
    if (!expiryDate) errors.push('Expiry date does not look right. Type it like 30/06/2027.');
    else if (expiryDate <= today) errors.push('This batch has already expired. Leave it out.');
    const cartons = values['Cartons'] === '' ? 0 : wholeOf(values['Cartons']!);
    const pieces = values['Pieces'] === '' ? 0 : wholeOf(values['Pieces']!);
    if (cartons === null || cartons > 1_000_000) errors.push('Cartons must be a whole number.');
    if (pieces === null || pieces > 1_000_000) errors.push('Pieces must be a whole number.');
    const total = product && cartons !== null && pieces !== null ? cartons * product.ppc + pieces : null;
    if (total === 0) errors.push('Please type how many cartons or pieces.');
    reports.push({ row, values, errors });
    if (errors.length === 0) good.push({ companyCode: product!.companyCode, productId: product!.id, batchNo, expiryDate: expiryDate!, pieces: total! });
  }
  return { reports, good };
}

async function saveOpeningBalances(client: PoolClient, user: AuthUser, good: NewBalance[]) {
  for (const b of good) {
    const { rowCount } = await client.query(
      `insert into opening_balances (customer_id, company_code, amount, as_of, entered_by) values ($1, $2, $3, $4, $5)
       on conflict (customer_id, company_code) do nothing`,
      [b.customerId, b.companyCode, b.amount, b.asOf, user.id],
    );
    if (!rowCount) throw new HttpError(409, 'changed', 'Someone entered one of these balances meanwhile. Check the file again.');
  }
}

// One goods received note per company, called "Opening stock".
async function saveStock(client: PoolClient, user: AuthUser, good: NewStock[]) {
  const today = (await client.query<{ d: string }>(`select to_char(${TODAY}, 'YYYY-MM-DD') as d`)).rows[0]!.d;
  const numbers: string[] = [];
  for (const company of [...new Set(good.map((g) => g.companyCode))].sort()) {
    const number = await nextNumber(client, company, 'GRN');
    numbers.push(number);
    const { rows } = await client.query<{ id: number }>(
      `insert into stock_receipts (number, company_code, supplier, reference, received_on, note, received_by)
       values ($1, $2, 'Opening stock', 'Excel import', $3, 'Stock in the warehouse on the day the app started.', $4) returning id`,
      [number, company, today, user.id],
    );
    for (const l of good.filter((g) => g.companyCode === company)) {
      const { rows: batch } = await client.query<{ id: number }>(
        `insert into stock_batches (product_id, batch_no, expiry_date, on_hand) values ($1, $2, $3, $4)
         on conflict (product_id, batch_no) do nothing returning id`,
        [l.productId, l.batchNo, l.expiryDate, l.pieces],
      );
      if (!batch[0]) throw new HttpError(409, 'changed', `Batch ${l.batchNo} was received meanwhile. Check the file again.`);
      await client.query(
        `insert into stock_movements (batch_id, change, kind, receipt_id, user_id) values ($1, $2, 'received', $3, $4)`,
        [batch[0].id, l.pieces, rows[0]!.id, user.id],
      );
    }
  }
  return numbers;
}

function summary(reports: RowReport[]) {
  const withErrors = reports.filter((r) => r.errors.length > 0).length;
  return { ok: withErrors === 0, rows: reports.length, rowsWithErrors: withErrors, report: reports };
}

export function importRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);

  // The Excel file is sent as the raw request body.
  for (const type of [XLSX, 'application/octet-stream']) {
    app.addContentTypeParser(type, { parseAs: 'buffer', bodyLimit: 10 * 1024 * 1024 }, (_req, body, done) => done(null, body));
  }

  const fileOf = (body: unknown): Buffer => {
    if (!Buffer.isBuffer(body) || body.length === 0) {
      throw new HttpError(400, 'invalid', 'Please upload the filled Excel template.');
    }
    return body;
  };

  for (const kind of KINDS) {
    const allowed = requireRole(...(kind === 'stock' ? STOCK_KEEPERS : LIST_EDITORS));
    app.get(`/import/${kind}/template`, { onRequest: auth, preHandler: allowed }, async (_request, reply: FastifyReply) => {
      reply.header('content-type', XLSX).header('content-disposition', `attachment; filename="${kind}-template.xlsx"`);
      return reply.send(await template(kind));
    });

    const check = async (user: AuthUser, body: unknown): Promise<{ reports: RowReport[]; good: unknown[] }> => {
      const rows = await readRows(kind, fileOf(body));
      if (kind === 'customers') return checkCustomers(db, rows);
      if (kind === 'products') return checkProducts(db, rows);
      if (kind === 'opening-balances') return checkOpeningBalances(db, user, rows);
      return checkStock(db, user, rows);
    };

    // Shows every row and its problems. Nothing is saved.
    app.post(`/import/${kind}/check`, { onRequest: auth, preHandler: allowed }, async (request) => {
      return summary((await check(request.user, request.body)).reports);
    });

    // Saves all rows, or none if any row has a problem.
    app.post(`/import/${kind}/save`, { onRequest: auth, preHandler: allowed }, async (request, reply) => {
      const { reports, good } = await check(request.user, request.body);
      const result = summary(reports);
      if (!result.ok) return reply.code(400).send({ error: 'invalid', message: 'Some rows have problems. Nothing was saved.', ...result });
      let receipts: string[] | undefined;
      const client = await db.connect();
      try {
        await client.query('begin');
        if (kind === 'opening-balances') await saveOpeningBalances(client, request.user, good as NewBalance[]);
        else if (kind === 'stock') receipts = await saveStock(client, request.user, good as NewStock[]);
        else {
          for (const item of good) {
            const id = kind === 'customers' ? await insertCustomer(client, item as CleanCustomer) : await insertProduct(client, item as NewProduct);
            if (id == null) throw new HttpError(409, 'changed', 'Someone added the same name or code meanwhile. Check the file again.');
          }
        }
        await client.query('commit');
      } catch (e) {
        await client.query('rollback');
        throw e;
      } finally {
        client.release();
      }
      const action = kind === 'opening-balances' ? 'opening_balances_imported' : kind === 'stock' ? 'opening_stock_imported' : `${kind}_imported`;
      await audit(db, { userId: request.user.id, action, details: { count: good.length, ...(receipts ? { receipts } : {}) } });
      return receipts ? { saved: good.length, receipts } : { saved: good.length };
    });
  }
}
