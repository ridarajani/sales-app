import ExcelJS from 'exceljs';
import type { FastifyInstance, FastifyReply } from 'fastify';
import { audit } from '../audit.js';
import { HttpError, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { isValidPhone, normalisePhone } from '../users.js';
import { CHANNEL_LABELS, CHANNELS, LIST_EDITORS, type Channel } from './common.js';
import { insertCustomer, type CleanCustomer } from './customers.js';
import { insertProduct, type NewProduct } from './products.js';

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const MAX_ROWS = 5000;

// Optional columns may be left out of the file, so files made from the older template still import.
type Column = { header: string; width: number; example: string; optional?: boolean };
type Kind = 'customers' | 'products';

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
};

export type RowReport = { row: number; values: Record<string, string>; errors: string[] };

async function template(kind: Kind): Promise<Buffer> {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet(kind === 'customers' ? 'Customers' : 'Products');
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
      });
    }
  }
  return { reports, good };
}

function summary(reports: RowReport[]) {
  const withErrors = reports.filter((r) => r.errors.length > 0).length;
  return { ok: withErrors === 0, rows: reports.length, rowsWithErrors: withErrors, report: reports };
}

export function importRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const editors = requireRole(...LIST_EDITORS);

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

  for (const kind of ['customers', 'products'] as const) {
    app.get(`/import/${kind}/template`, { onRequest: auth, preHandler: editors }, async (_request, reply: FastifyReply) => {
      reply.header('content-type', XLSX).header('content-disposition', `attachment; filename="${kind}-template.xlsx"`);
      return reply.send(await template(kind));
    });

    const check = async (body: unknown) => {
      const rows = await readRows(kind, fileOf(body));
      return kind === 'customers' ? checkCustomers(db, rows) : checkProducts(db, rows);
    };

    // Shows every row and its problems. Nothing is saved.
    app.post(`/import/${kind}/check`, { onRequest: auth, preHandler: editors }, async (request) => {
      return summary((await check(request.body)).reports);
    });

    // Saves all rows, or none if any row has a problem.
    app.post(`/import/${kind}/save`, { onRequest: auth, preHandler: editors }, async (request, reply) => {
      const { reports, good } = await check(request.body);
      const result = summary(reports);
      if (!result.ok) return reply.code(400).send({ error: 'invalid', message: 'Some rows have problems. Nothing was saved.', ...result });
      const client = await db.connect();
      try {
        await client.query('begin');
        for (const item of good) {
          const id = kind === 'customers' ? await insertCustomer(client, item as CleanCustomer) : await insertProduct(client, item as NewProduct);
          if (id == null) throw new HttpError(409, 'changed', 'Someone added the same name or code meanwhile. Check the file again.');
        }
        await client.query('commit');
      } catch (e) {
        await client.query('rollback');
        throw e;
      } finally {
        client.release();
      }
      await audit(db, { userId: request.user.id, action: `${kind}_imported`, details: { count: good.length } });
      return { saved: good.length };
    });
  }
}
