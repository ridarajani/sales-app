import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { readImage, saveImage } from '../files.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { nextNumber } from '../numbering.js';
import { inTransaction } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';
import { addAirtelPayment } from './airtel.js';
import { CUSTOMER_OWES, INVOICE_BALANCE, OPENING_LEFT } from './balances.js';

// Record payments from customers. Salesmen and sales managers collect in the field; Accounts and the cashier at the office.
export const COLLECTORS = ['admin', 'sales_manager', 'salesman', 'accounts', 'cashier'] as const;
export const COLLECTION_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'] as const;
// At the office a bank transfer or Airtel payment is recorded without the customer there to sign.
const OFFICE = ['admin', 'accounts', 'cashier'];

export const MODES = ['cash', 'bank', 'airtel'] as const;
const IMAGE_MAX = 8 * 1024 * 1024;
const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;

const id = z.number().int().positive().max(INT4_MAX);
const kwacha = (what: string) =>
  z.number(`Please type the ${what}.`).int('Amounts are whole kwacha.').min(0, `The ${what} cannot be below 0.`).max(10_000_000_000);
const collectBody = z.object({
  customerId: id,
  companyCode: z.string(),
  invoiceId: id.nullish(),
  mode: z.enum(MODES, 'Please pick cash, bank transfer or Airtel Money.'),
  amount: kwacha('amount received').default(0),
  discount: kwacha('discount').default(0),
  wht: kwacha('withholding tax').default(0),
  whtCertificate: z.string().trim().max(60).default(''),
  reference: z.string().trim().max(100).default(''),
  note: z.string().trim().max(1000).default(''),
  receivedBy: z.string().trim().max(100).default(''),
  // PNG or JPEG as base64: the customer's signature, and the photo of the bank slip or Airtel message.
  signature: z.string().max(4_000_000).optional(),
  proof: z.string().max(12_000_000).optional(),
  // One id per form on the phone, so a retry after a lost answer does not make a second receipt.
  requestId: z.string().trim().min(1).max(64).optional(),
});
const listQuery = z.object({
  company: z.string().optional(),
  customerId: z.coerce.number().int().positive().max(INT4_MAX).optional(),
  mode: z.enum(MODES).optional(),
  from: z.iso.date().optional(),
  to: z.iso.date().optional(),
  q: z.string().trim().optional(),
});

// Which payments a user may see. $1 = their companies, $2 = their id. Salesmen see only what they collected.
function visibleWhere(user: AuthUser) {
  if (user.role === 'salesman') return `c.company_code = any($1::text[]) and c.collected_by = $2::int`;
  return `c.company_code = any($1::text[]) and $2::int is not null`;
}

async function collectionDetail(db: Db, user: AuthUser, collectionId: number) {
  const { rows } = await db.query(
    `select c.id, c.number, c.company_code as "companyCode", c.customer_id as "customerId", cu.name as "customerName",
       c.invoice_id as "invoiceId", iv.number as "invoiceNumber", c.mode, c.amount::float8 as amount, c.discount::float8 as discount,
       c.wht::float8 as wht, c.wht_certificate as "whtCertificate", c.reference, c.note, c.received_by as "receivedBy",
       c.signature_file is not null as "hasSignature", c.proof_file is not null as "hasProof",
       u.name as "collectedBy", c.custody, h.number as "handoverNumber", c.created_at as "createdAt",
       coalesce((select json_agg(json_build_object('invoiceId', a.invoice_id, 'invoiceNumber', ai.number, 'amount', a.amount::float8) order by a.id)
         from collection_allocations a left join invoices ai on ai.id = a.invoice_id where a.collection_id = c.id), '[]') as allocations,
       (c.amount - coalesce((select sum(a.amount) from collection_allocations a where a.collection_id = c.id), 0))::float8 as "onAccount",
       ${CUSTOMER_OWES('c.customer_id', 'c.company_code')}::float8 as "owesNow"
     from collections c join customers cu on cu.id = c.customer_id join users u on u.id = c.collected_by
       left join invoices iv on iv.id = c.invoice_id left join handovers h on h.id = c.handover_id
     where c.id = $3 and ${visibleWhere(user)}`,
    [user.companies, user.id, collectionId],
  );
  return rows[0] ?? null;
}

export function collectionRoutes(app: FastifyInstance, db: Db, uploadDir: string) {
  const auth = requireAuth(db);
  const collectors = requireRole(...COLLECTORS);
  const viewers = requireRole(...COLLECTION_VIEWERS);

  // What the collect screen needs once a customer is picked: per company, what they owe and the invoices still open.
  app.get('/collections/customer/:id', { onRequest: auth, preHandler: collectors }, async (request) => {
    const { id: customerId } = parse(idParams, request.params);
    const user = request.user;
    const { rows: customers } = await db.query(
      `select c.id, c.name, c.area, c.cash_only as "cashOnly" from customers c
       where c.id = $1 and exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($2))`,
      [customerId, user.companies],
    );
    if (!customers[0]) throw notFound('customer');
    const { rows: companies } = await db.query(
      `select co.code, co.name, ${CUSTOMER_OWES('cc.customer_id', 'cc.company_code')}::float8 as owes,
         ${OPENING_LEFT('cc.customer_id', 'cc.company_code')}::float8 as "openingLeft",
         coalesce((select json_agg(x order by x."invoiceDate", x.id) from (
           select iv.id, iv.number, iv.invoice_type as "invoiceType", iv.lpo_no as "lpoNo", to_char(iv.invoice_date, 'YYYY-MM-DD') as "invoiceDate",
             to_char(iv.due_date, 'YYYY-MM-DD') as "dueDate", iv.total::float8 as total, ${INVOICE_BALANCE('iv')}::float8 as balance,
             iv.due_date < (now() at time zone 'Africa/Blantyre')::date as overdue
           from invoices iv where iv.customer_id = cc.customer_id and iv.company_code = cc.company_code) x where x.balance > 0), '[]') as invoices
       from customer_companies cc join companies co on co.code = cc.company_code
       where cc.customer_id = $1 and cc.company_code = any($2) order by co.sort_order`,
      [customerId, user.companies],
    );
    return { customer: customers[0], companies, signatureNeeded: !OFFICE.includes(user.role) };
  });

  // A payment. The money goes first on the invoice picked, then on the opening balance, then on the oldest invoices;
  // anything over stays on account. A discount or withholding tax comes off the invoice picked.
  app.post('/collections', { onRequest: auth, preHandler: collectors, bodyLimit: 24 * 1024 * 1024 }, async (request, reply) => {
    const body = parse(collectBody, request.body);
    const user = request.user;
    assertCompany(user, body.companyCode);
    const earlier = async () => {
      if (!body.requestId) return null;
      const { rows } = await db.query<{ id: number }>('select id from collections where collected_by = $1 and request_id = $2', [
        user.id,
        body.requestId,
      ]);
      return rows[0] ? collectionDetail(db, user, rows[0].id) : null;
    };
    const repeat = await earlier();
    if (repeat) return reply.code(200).send(repeat);
    if (body.amount + body.discount + body.wht === 0) throw invalid('Please type the amount received.');
    if ((body.discount || body.wht) && !body.invoiceId) throw invalid('Pick the invoice the discount or tax is for.');
    if (body.mode === 'airtel' && body.amount > 0 && !body.reference) throw invalid('Please type the Airtel Money reference.');
    if (body.mode !== 'cash' && body.amount > 0 && !body.proof) throw invalid('Please add a photo of the proof of payment.');
    // The customer signs for every payment (client, 17 Oct), except a transfer recorded at the office.
    const signatureNeeded = body.mode === 'cash' || !OFFICE.includes(user.role);
    if (signatureNeeded && !body.signature) throw invalid('Please ask the customer to sign.');
    if (signatureNeeded && !body.receivedBy) throw invalid('Please type the name of the person who paid.');

    // Files are saved first, so nothing is written to the database if one cannot be read.
    const signatureFile = body.signature ? await saveImage(uploadDir, body.signature, IMAGE_MAX, 'signature') : null;
    const proofFile = body.proof ? await saveImage(uploadDir, body.proof, IMAGE_MAX, 'photo') : null;

    const saving = inTransaction(db, async (client) => {
      // One payment at a time per customer and company, so two never cover the same invoice.
      const { rows: link } = await client.query(
        'select 1 from customer_companies where customer_id = $1 and company_code = $2 for update',
        [body.customerId, body.companyCode],
      );
      if (!link[0]) throw invalid('This customer does not buy from that company.');

      const { rows: open } = await client.query<{ id: number; number: string; balance: number }>(
        `select iv.id, iv.number, ${INVOICE_BALANCE('iv')}::float8 as balance from invoices iv
         where iv.customer_id = $1 and iv.company_code = $2 order by iv.invoice_date, iv.id`,
        [body.customerId, body.companyCode],
      );
      let picked: (typeof open)[number] | undefined;
      if (body.invoiceId) {
        picked = open.find((i) => i.id === body.invoiceId);
        if (!picked) throw invalid('That invoice is not this customer’s for that company.');
        if (picked.balance <= 0) throw new HttpError(409, 'invoice_paid', `Invoice ${picked.number} is already paid.`);
        if (body.discount + body.wht > picked.balance) {
          throw invalid(`The discount and tax are more than invoice ${picked.number} owes (${money(picked.balance)}).`);
        }
        picked.balance -= body.discount + body.wht;
      }
      const { rows: opening } = await client.query<{ left: number }>(
        `select ${OPENING_LEFT('$1::int', '$2::text')}::float8 as left`,
        [body.customerId, body.companyCode],
      );

      const allocations: { invoiceId: number | null; amount: number }[] = [];
      let left = body.amount;
      const put = (invoiceId: number | null, room: number) => {
        const amount = Math.min(left, room);
        if (amount <= 0) return 0;
        allocations.push({ invoiceId, amount });
        left -= amount;
        return amount;
      };
      if (picked) picked.balance -= put(picked.id, picked.balance);
      put(null, opening[0]!.left);
      for (const iv of open) if (iv !== picked) put(iv.id, iv.balance);

      const number = await nextNumber(client, body.companyCode, 'RCT');
      const custody = body.mode !== 'cash' || body.amount === 0 ? 'na' : user.role === 'cashier' ? 'counted' : 'with';
      const { rows } = await client.query<{ id: number }>(
        `insert into collections (number, company_code, customer_id, invoice_id, mode, amount, discount, wht, wht_certificate,
           reference, note, proof_file, signature_file, received_by, collected_by, custody, request_id)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17) returning id`,
        [number, body.companyCode, body.customerId, body.invoiceId ?? null, body.mode, body.amount, body.discount, body.wht,
          body.whtCertificate, body.reference, body.note, proofFile, signatureFile, body.receivedBy, user.id, custody, body.requestId ?? null],
      );
      const collectionId = rows[0]!.id;
      for (const a of allocations) {
        await client.query('insert into collection_allocations (collection_id, invoice_id, amount) values ($1, $2, $3)', [
          collectionId,
          a.invoiceId,
          a.amount,
        ]);
      }
      if (body.mode === 'airtel' && body.amount > 0) {
        const { rows: cu } = await client.query<{ name: string }>('select name from customers where id = $1', [body.customerId]);
        await addAirtelPayment(client, {
          companyCode: body.companyCode,
          amount: body.amount,
          reference: body.reference,
          description: `${cu[0]!.name} · ${number}`,
          collectionId,
          userId: user.id,
        });
      }
      return { id: collectionId, number };
    });
    let saved: Awaited<typeof saving>;
    try {
      saved = await saving;
    } catch (err) {
      // Two tries at the same moment: the second waits on the first and then finds its receipt.
      const again = (err as { code?: string }).code === '23505' ? await earlier() : null;
      if (again) return reply.code(200).send(again);
      throw err;
    }
    await audit(db, {
      userId: user.id,
      action: 'payment_collected',
      details: { collectionId: saved.id, number: saved.number, mode: body.mode, amount: body.amount, discount: body.discount, wht: body.wht },
    });
    return reply.code(201).send(await collectionDetail(db, user, saved.id));
  });

  // Payments, newest first.
  app.get('/collections', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(listQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = visibleWhere(user);
    const add = (sql: string, value: unknown) => {
      params.push(value);
      where += ` and ${sql.replace('?', `$${params.length}`)}`;
    };
    if (query.company) add('c.company_code = ?', query.company);
    if (query.customerId) add('c.customer_id = ?', query.customerId);
    if (query.mode) add('c.mode = ?', query.mode);
    if (query.from) add(`(c.created_at at time zone 'Africa/Blantyre')::date >= ?::date`, query.from);
    if (query.to) add(`(c.created_at at time zone 'Africa/Blantyre')::date <= ?::date`, query.to);
    if (query.q) {
      params.push(`%${query.q}%`);
      const n = params.length;
      where += ` and (c.number ilike $${n} or cu.name ilike $${n} or c.reference ilike $${n} or iv.number ilike $${n})`;
    }
    const { rows } = await db.query(
      `select c.id, c.number, c.company_code as "companyCode", cu.name as "customerName", iv.number as "invoiceNumber", c.mode,
         c.amount::float8 as amount, c.discount::float8 as discount, c.wht::float8 as wht, c.reference, u.name as "collectedBy",
         c.custody, c.created_at as "createdAt"
       from collections c join customers cu on cu.id = c.customer_id join users u on u.id = c.collected_by
         left join invoices iv on iv.id = c.invoice_id
       where ${where} order by c.id desc limit 500`,
      params,
    );
    return rows;
  });

  app.get('/collections/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: collectionId } = parse(idParams, request.params);
    const collection = await collectionDetail(db, request.user, collectionId);
    if (!collection) throw notFound('payment');
    return collection;
  });

  for (const [kind, column] of [
    ['signature', 'signature_file'],
    ['proof', 'proof_file'],
  ] as const) {
    app.get(`/collections/:id/${kind}`, { onRequest: auth, preHandler: viewers }, async (request, reply) => {
      const { id: collectionId } = parse(idParams, request.params);
      const user = request.user;
      const { rows } = await db.query<{ file: string | null }>(
        `select c.${column} as file from collections c where c.id = $3 and ${visibleWhere(user)}`,
        [user.companies, user.id, collectionId],
      );
      const image = rows[0]?.file ? await readImage(uploadDir, rows[0].file) : null;
      if (!image) throw notFound(kind === 'proof' ? 'photo' : 'signature');
      return reply.type(image.type).header('cache-control', 'private, max-age=86400').send(image.bytes);
    });
  }
}
