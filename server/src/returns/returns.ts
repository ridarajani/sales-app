import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import { INVOICE_BALANCE, NOTE_SIGN } from '../cash/balances.js';
import { applyOnAccount } from '../cash/collections.js';
import type { Db } from '../db/pool.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { nextNumber } from '../numbering.js';
import { lineAmount, qtyText, vatOn } from '../orders/orders.js';
import { inTransaction, STOCK_KEEPERS } from '../stock/stock.js';
import { parse } from '../users.js';

// Booking returns and notes changes what a customer owes, so only the sales team and Accounts do it (prototype).
export const ADJUSTERS = ['admin', 'management', 'sales_manager', 'accounts'] as const;
// The warehouse sees returns to receive the goods, but never their money.
export const RETURN_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts', 'warehouse'] as const;
export const NOTE_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'] as const;

export const RETURN_REASONS = [
  'Damaged goods',
  'Short-dated or expired',
  'Wrong product',
  'Customer refused delivery',
  'Quality complaint',
  'Commercial return',
  'Other',
] as const;
export const NOTE_REASONS = ['Price difference', 'Wrong price on the invoice', 'Promotion agreed later', 'Other'] as const;
export const NOTE_KINDS = ['credit_issued', 'debit_issued', 'debit_received', 'credit_received'] as const;
const NOTE_PREFIX: Record<(typeof NOTE_KINDS)[number], string> = {
  credit_issued: 'CN',
  debit_issued: 'DN',
  debit_received: 'RDN',
  credit_received: 'RCN',
};
const NOTE_LABEL: Record<(typeof NOTE_KINDS)[number], string> = {
  credit_issued: 'Credit note issued',
  debit_issued: 'Debit note issued',
  debit_received: 'Debit note received from the customer',
  credit_received: 'Credit note received from the customer',
};

const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;
const requestId = z.string().trim().min(1).max(64).optional();

const returnBody = z.object({
  lines: z
    .array(
      z.object({
        invoiceLineId: z.number().int().positive(),
        cartons: z.number().int().min(0).max(100_000).default(0),
        pieces: z.number().int().min(0).max(1_000_000).default(0),
      }),
    )
    .max(100),
  reason: z.enum(RETURN_REASONS, 'Please say why the goods came back.'),
  note: z.string().trim().max(1000).default(''),
  requestId,
});
const receiveBody = z.object({
  // A line left out is good. Damaged goods do not go back on the shelf.
  damaged: z.array(z.number().int().positive()).max(100).default([]),
});
const noteBody = z.object({
  kind: z.enum(NOTE_KINDS, 'Please pick the kind of note.'),
  amount: z.number('Please type the amount.').int('Amounts are whole kwacha.').positive('Please type the amount.').max(10_000_000_000),
  reason: z.enum(NOTE_REASONS, 'Please pick a reason.'),
  theirNumber: z.string().trim().max(60).default(''),
  note: z.string().trim().max(1000).default(''),
  requestId,
});
const returnQuery = z.object({
  status: z.enum(['to_book', 'waiting', 'done', 'all']).default('all'),
  company: z.string().optional(),
  q: z.string().trim().optional(),
});
const noteQuery = z.object({
  company: z.string().optional(),
  kind: z.enum(NOTE_KINDS).optional(),
  q: z.string().trim().optional(),
});

// Which returns and notes a user may see. $1 = their companies, $2 = their id. Salesmen see their own invoices'.
function invoiceWhere(user: AuthUser, alias: string) {
  const mine = `${alias}.company_code = any($1::text[])`;
  if (user.role === 'salesman') return `${mine} and exists (select 1 from invoices w where w.id = ${alias}.invoice_id and w.salesman_id = $2::int)`;
  return `${mine} and $2::int is not null`;
}
const isAdjuster = (user: AuthUser) => (ADJUSTERS as readonly string[]).includes(user.role);
const isKeeper = (user: AuthUser) => (STOCK_KEEPERS as readonly string[]).includes(user.role);

async function returnDetail(db: Db | PoolClient, user: AuthUser, returnId: number) {
  const { rows } = await db.query(
    `select r.id, r.number, r.company_code as "companyCode", r.customer_id as "customerId", cu.name as "customerName",
       r.invoice_id as "invoiceId", iv.number as "invoiceNumber", r.kind, r.reason, r.note,
       r.subtotal::float8 as subtotal, r.vat::float8 as vat, r.total::float8 as total,
       r.booked_at is not null as booked, bb.name as "bookedBy", r.booked_at as "bookedAt",
       r.goods, rb.name as "receivedBy", r.received_at as "receivedAt", cb.name as "createdBy", r.created_at as "createdAt",
       lp.delivery_note_no as "deliveryNoteNo",
       coalesce((select json_agg(json_build_object('id', l.id, 'invoiceLineId', l.invoice_line_id, 'productId', p.id,
           'brand', p.brand, 'name', p.name, 'packSize', p.pack_size, 'piecesPerCarton', p.pieces_per_carton,
           'pieces', l.pieces, 'amount', l.amount::float8, 'condition', l.condition) order by l.id)
         from sales_return_lines l join products p on p.id = l.product_id where l.return_id = r.id), '[]') as lines
     from sales_returns r join customers cu on cu.id = r.customer_id join invoices iv on iv.id = r.invoice_id
       join users cb on cb.id = r.created_by left join users bb on bb.id = r.booked_by left join users rb on rb.id = r.received_by
       left join deliveries d on d.id = r.delivery_id left join loading_plans lp on lp.id = d.plan_id
     where r.id = $3 and ${invoiceWhere(user, 'r')}`,
    [user.companies, user.id, returnId],
  );
  const r = rows[0];
  if (!r) return null;
  const lines = (r.lines as { pieces: number; piecesPerCarton: number; amount: number }[]).map((l) => ({
    ...l,
    quantity: qtyText(l.pieces, l.piecesPerCarton),
  }));
  const shown = {
    ...r,
    lines,
    canBook: isAdjuster(user) && !r.booked,
    canReceive: isKeeper(user) && r.goods === 'waiting',
  };
  return user.role === 'warehouse' ? withoutMoney(shown) : shown;
}

// The warehouse never sees what goods cost.
function withoutMoney<T extends { lines: { amount?: number }[] }>(r: T) {
  const { subtotal: _s, vat: _v, total: _t, ...rest } = r as T & { subtotal?: number; vat?: number; total?: number };
  return { ...rest, lines: r.lines.map(({ amount: _a, ...l }) => l) };
}

async function amounts(
  client: PoolClient,
  invoiceId: number,
  lines: { pieces: number; pricePerCarton: number; piecesPerCarton: number; vatExempt: boolean }[],
) {
  const { rows } = await client.query<{ vatRate: number }>('select vat_rate::float8 as "vatRate" from invoices where id = $1', [invoiceId]);
  const each = lines.map((l) => lineAmount(l.pieces, l.pricePerCarton, l.piecesPerCarton));
  const subtotal = each.reduce((t, a) => t + a, 0);
  const vat = vatOn(lines.map((l, i) => ({ amount: each[i]!, vatExempt: l.vatExempt })), rows[0]!.vatRate);
  return { each, subtotal, vat, total: subtotal + vat };
}

// When a drop is confirmed short, what was not taken becomes a return to book, one per invoice. The goods never
// left the vehicle and are back on the shelf already (Phase 6), so there is nothing for the warehouse to receive.
export async function returnsForShortDelivery(client: PoolClient, deliveryId: number, userId: number) {
  const { rows } = await client.query<{
    invoiceId: number;
    companyCode: string;
    customerId: number;
    invoiceLineId: number;
    productId: number;
    pieces: number;
    pricePerCarton: number;
    piecesPerCarton: number;
    vatExempt: boolean;
    reason: string | null;
  }>(
    `select il.invoice_id as "invoiceId", iv.company_code as "companyCode", iv.customer_id as "customerId", il.id as "invoiceLineId",
       il.product_id as "productId", i.loaded - i.delivered as pieces, il.price_per_carton as "pricePerCarton",
       p.pieces_per_carton as "piecesPerCarton", il.vat_exempt as "vatExempt", i.short_reason as reason
     from loading_plan_items i join invoice_lines il on il.plan_item_id = i.id join invoices iv on iv.id = il.invoice_id
       join products p on p.id = il.product_id
     where i.delivery_id = $1 and i.delivered < i.loaded order by il.invoice_id, il.id`,
    [deliveryId],
  );
  const made: string[] = [];
  for (const invoiceId of new Set(rows.map((r) => r.invoiceId))) {
    const lines = rows.filter((r) => r.invoiceId === invoiceId);
    const first = lines[0]!;
    const sum = await amounts(client, invoiceId, lines);
    const reason = [...new Set(lines.map((l) => l.reason).filter(Boolean))].join(', ') || 'Not taken at delivery';
    const number = await nextNumber(client, first.companyCode, 'SR');
    const { rows: saved } = await client.query<{ id: number }>(
      `insert into sales_returns (number, company_code, customer_id, invoice_id, kind, delivery_id, reason, subtotal, vat, total,
         goods, received_by, received_at, created_by)
       values ($1, $2, $3, $4, 'delivery', $5, $6, $7, $8, $9, 'received', $10, now(), $10) returning id`,
      [number, first.companyCode, first.customerId, invoiceId, deliveryId, reason, sum.subtotal, sum.vat, sum.total, userId],
    );
    for (const [i, l] of lines.entries()) {
      await client.query(
        `insert into sales_return_lines (return_id, invoice_line_id, product_id, pieces, amount, condition) values ($1, $2, $3, $4, $5, 'good')`,
        [saved[0]!.id, l.invoiceLineId, l.productId, l.pieces, sum.each[i]],
      );
    }
    made.push(number);
  }
  return made;
}

// The invoice a return or note is made on, locked, with the checks every adjustment needs.
async function lockInvoice(client: PoolClient, user: AuthUser, invoiceId: number) {
  const { rows } = await client.query<{ id: number; number: string; companyCode: string; customerId: number }>(
    `select iv.id, iv.number, iv.company_code as "companyCode", iv.customer_id as "customerId" from invoices iv
     where iv.id = $3 and iv.company_code = any($1::text[]) and $2::int is not null for update`,
    [user.companies, user.id, invoiceId],
  );
  if (!rows[0]) throw notFound('invoice');
  return rows[0];
}

export function returnRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const adjusters = requireRole(...ADJUSTERS);
  const keepers = requireRole(...STOCK_KEEPERS);
  const viewers = requireRole(...RETURN_VIEWERS);
  const noteViewers = requireRole(...NOTE_VIEWERS);

  // Goods the customer gives back after delivery. Booked at once: the invoice and what they owe go down.
  app.post('/invoices/:id/returns', { onRequest: auth, preHandler: adjusters }, async (request, reply) => {
    const { id: invoiceId } = parse(idParams, request.params);
    const body = parse(returnBody, request.body);
    const user = request.user;
    const earlier = async () => {
      if (!body.requestId) return null;
      const { rows } = await db.query<{ id: number }>('select id from sales_returns where created_by = $1 and request_id = $2', [
        user.id,
        body.requestId,
      ]);
      return rows[0] ? returnDetail(db, user, rows[0].id) : null;
    };
    const repeat = await earlier();
    if (repeat) return reply.code(200).send(repeat);

    const wanted = body.lines.filter((l) => l.cartons > 0 || l.pieces > 0);
    if (wanted.length === 0) throw invalid('Please type what came back.');
    if (new Set(wanted.map((l) => l.invoiceLineId)).size !== wanted.length) throw invalid('A product is listed twice.');

    let saved: { id: number; number: string; total: number; invoiceNumber: string };
    try {
      saved = await inTransaction(db, async (client) => {
        const iv = await lockInvoice(client, user, invoiceId);
        const { rows: lines } = await client.query<{
          id: number;
          productId: number;
          label: string;
          piecesPerCarton: number;
          pricePerCarton: number;
          vatExempt: boolean;
          invoiced: number;
          delivered: number | null;
          returned: number;
        }>(
          `select il.id, il.product_id as "productId", p.brand || ' ' || p.name || ' ' || p.pack_size as label,
             p.pieces_per_carton as "piecesPerCarton", il.price_per_carton as "pricePerCarton", il.vat_exempt as "vatExempt", il.pieces as invoiced,
             case when il.plan_item_id is null then il.pieces else i.delivered end as delivered,
             coalesce((select sum(l.pieces) from sales_return_lines l join sales_returns r on r.id = l.return_id
               where l.invoice_line_id = il.id and r.kind = 'after'), 0)::int as returned
           from invoice_lines il join products p on p.id = il.product_id left join loading_plan_items i on i.id = il.plan_item_id
           where il.invoice_id = $1 order by il.id`,
          [iv.id],
        );
        const picked = wanted.map((w) => {
          const line = lines.find((l) => l.id === w.invoiceLineId);
          if (!line) throw invalid('That product is not on this invoice.');
          if (line.delivered === null) {
            throw new HttpError(409, 'not_delivered', 'This invoice is not delivered yet. Goods not taken are returned when the driver confirms the drop.');
          }
          const pieces = w.cartons * line.piecesPerCarton + w.pieces;
          const most = line.delivered - line.returned;
          if (pieces > most) {
            throw invalid(
              most > 0 ? `${line.label}: at most ${qtyText(most, line.piecesPerCarton)} can come back.` : `${line.label}: nothing more can come back.`,
            );
          }
          return { ...line, pieces };
        });
        const sum = await amounts(client, iv.id, picked);
        const number = await nextNumber(client, iv.companyCode, 'SR');
        const { rows } = await client.query<{ id: number }>(
          `insert into sales_returns (number, company_code, customer_id, invoice_id, kind, reason, note, subtotal, vat, total,
             booked_by, booked_at, goods, created_by, request_id)
           values ($1, $2, $3, $4, 'after', $5, $6, $7, $8, $9, $10, now(), 'waiting', $10, $11) returning id`,
          [number, iv.companyCode, iv.customerId, iv.id, body.reason, body.note, sum.subtotal, sum.vat, sum.total, user.id, body.requestId ?? null],
        );
        for (const [i, l] of picked.entries()) {
          await client.query(
            'insert into sales_return_lines (return_id, invoice_line_id, product_id, pieces, amount) values ($1, $2, $3, $4, $5)',
            [rows[0]!.id, l.id, l.productId, l.pieces, sum.each[i]],
          );
        }
        return { id: rows[0]!.id, number, total: sum.total, invoiceNumber: iv.number };
      });
    } catch (err) {
      const again = (err as { code?: string }).code === '23505' ? await earlier() : null;
      if (again) return reply.code(200).send(again);
      throw err;
    }
    await audit(db, {
      userId: user.id,
      action: 'sales_return_booked',
      details: { returnId: saved.id, number: saved.number, invoice: saved.invoiceNumber, total: saved.total },
    });
    return reply.code(201).send(await returnDetail(db, user, saved.id));
  });

  // Goods not taken at delivery are booked by the sales team: then they come off the invoice.
  app.post('/returns/:id/book', { onRequest: auth, preHandler: adjusters }, async (request) => {
    const { id: returnId } = parse(idParams, request.params);
    const user = request.user;
    const booked = await inTransaction(db, async (client) => {
      const { rows } = await client.query<{ number: string; booked: boolean; total: number }>(
        `select r.number, r.booked_at is not null as booked, r.total::float8 as total from sales_returns r
         where r.id = $3 and ${invoiceWhere(user, 'r')} for update`,
        [user.companies, user.id, returnId],
      );
      if (!rows[0]) throw notFound('return');
      if (rows[0].booked) throw new HttpError(409, 'already_booked', 'This return is already booked.');
      await client.query('update sales_returns set booked_by = $2, booked_at = now() where id = $1', [returnId, user.id]);
      return rows[0];
    });
    await audit(db, { userId: user.id, action: 'sales_return_booked', details: { returnId, number: booked.number, total: booked.total } });
    return returnDetail(db, user, returnId);
  });

  // The warehouse has the goods back. Good goods go on the shelf in the batches they were delivered from; damaged ones do not.
  app.post('/returns/:id/receive', { onRequest: auth, preHandler: keepers }, async (request) => {
    const { id: returnId } = parse(idParams, request.params);
    const body = parse(receiveBody, request.body ?? {});
    const user = request.user;
    const done = await inTransaction(db, async (client) => {
      const { rows } = await client.query<{ number: string; goods: string; invoiceId: number }>(
        `select r.number, r.goods, r.invoice_id as "invoiceId" from sales_returns r
         where r.id = $3 and ${invoiceWhere(user, 'r')} for update`,
        [user.companies, user.id, returnId],
      );
      const ret = rows[0];
      if (!ret) throw notFound('return');
      if (ret.goods !== 'waiting') throw new HttpError(409, 'already_received', 'These goods are already back.');
      const { rows: lines } = await client.query<{ id: number; productId: number; pieces: number; deliveryId: number | null; tripSaleId: number | null }>(
        `select l.id, l.product_id as "productId", l.pieces, i.delivery_id as "deliveryId", iv.trip_sale_id as "tripSaleId"
         from sales_return_lines l join invoice_lines il on il.id = l.invoice_line_id join invoices iv on iv.id = il.invoice_id
           left join loading_plan_items i on i.id = il.plan_item_id
         where l.return_id = $1 order by l.id`,
        [returnId],
      );
      if (body.damaged.some((id) => !lines.some((l) => l.id === id))) throw invalid('That product is not on this return.');
      let good = 0;
      for (const l of lines) {
        const damaged = body.damaged.includes(l.id);
        await client.query('update sales_return_lines set condition = $2 where id = $1', [l.id, damaged ? 'damaged' : 'good']);
        if (damaged) continue;
        // The batches this product left in (on a vehicle's delivery or sold on a trip), newest expiry first, each up to what it gave.
        const { rows: batches } = await client.query<{ batchId: number; pieces: number }>(
          `select m.batch_id as "batchId", -sum(m.change)::int as pieces from stock_movements m join stock_batches b on b.id = m.batch_id
           where (m.delivery_id = $1 or m.trip_sale_id = $3) and m.kind = 'delivered' and b.product_id = $2
           group by m.batch_id, b.expiry_date order by b.expiry_date desc, m.batch_id desc`,
          [l.deliveryId, l.productId, l.tripSaleId],
        );
        await client.query('select id from stock_batches where id = any($1) order by id for update', [batches.map((b) => b.batchId)]);
        if (batches.length === 0) throw invalid('The batch these goods came from was not found. Please receive them as a stock receipt.');
        let left = l.pieces;
        for (const [i, b] of batches.entries()) {
          const put = i === batches.length - 1 ? left : Math.min(left, b.pieces);
          if (put <= 0) continue;
          left -= put;
          await client.query('update stock_batches set on_hand = on_hand + $2 where id = $1', [b.batchId, put]);
          await client.query(
            `insert into stock_movements (batch_id, change, kind, note, return_id, user_id) values ($1, $2, 'returned', $3, $4, $5)`,
            [b.batchId, put, ret.number, returnId, user.id],
          );
        }
        good += l.pieces;
      }
      await client.query(`update sales_returns set goods = 'received', received_by = $2, received_at = now() where id = $1`, [returnId, user.id]);
      return { number: ret.number, good };
    });
    await audit(db, {
      userId: user.id,
      action: 'returned_goods_received',
      details: { returnId, number: done.number, piecesBackOnShelf: done.good, damagedLines: body.damaged.length },
    });
    return returnDetail(db, user, returnId);
  });

  app.get('/returns', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(returnQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = invoiceWhere(user, 'r');
    if (query.status === 'to_book') where += ' and r.booked_at is null';
    if (query.status === 'waiting') where += ` and r.goods = 'waiting'`;
    if (query.status === 'done') where += ` and r.booked_at is not null and r.goods = 'received'`;
    if (query.company) {
      params.push(query.company);
      where += ` and r.company_code = $${params.length}`;
    }
    if (query.q) {
      params.push(`%${query.q}%`);
      where += ` and (r.number ilike $${params.length} or iv.number ilike $${params.length} or cu.name ilike $${params.length})`;
    }
    const { rows } = await db.query(
      `select r.id, r.number, r.company_code as "companyCode", cu.name as "customerName", r.invoice_id as "invoiceId",
         iv.number as "invoiceNumber", r.kind, r.reason, r.total::float8 as total, r.booked_at is not null as booked, r.goods,
         r.created_at as "createdAt",
         (select string_agg(p.brand || ' ' || p.pack_size, ', ' order by l.id) from sales_return_lines l join products p on p.id = l.product_id
          where l.return_id = r.id) as products
       from sales_returns r join customers cu on cu.id = r.customer_id join invoices iv on iv.id = r.invoice_id
       where ${where} order by r.id desc limit 300`,
      params,
    );
    return user.role === 'warehouse' ? rows.map(({ total: _t, ...r }) => r) : rows;
  });

  app.get('/returns/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const r = await returnDetail(db, request.user, id);
    if (!r) throw notFound('return');
    return r;
  });

  // A credit or debit note on an invoice, mostly for a price difference. Stock does not change.
  app.post('/invoices/:id/notes', { onRequest: auth, preHandler: adjusters }, async (request, reply) => {
    const { id: invoiceId } = parse(idParams, request.params);
    const body = parse(noteBody, request.body);
    const user = request.user;
    if (body.kind.endsWith('_received') && !body.theirNumber) throw invalid('Please type the number on the customer’s note.');
    const detail = async (id: number) => (await notes(db, user, 'n.id = $3', [id]))[0];
    const earlier = async () => {
      if (!body.requestId) return null;
      const { rows } = await db.query<{ id: number }>('select id from invoice_notes where created_by = $1 and request_id = $2', [
        user.id,
        body.requestId,
      ]);
      return rows[0] ? detail(rows[0].id) : null;
    };
    const repeat = await earlier();
    if (repeat) return reply.code(200).send(repeat);

    let saved: { id: number; number: string; invoiceNumber: string };
    try {
      saved = await inTransaction(db, async (client) => {
        const iv = await lockInvoice(client, user, invoiceId);
        const number = await nextNumber(client, iv.companyCode, NOTE_PREFIX[body.kind]);
        const { rows } = await client.query<{ id: number }>(
          `insert into invoice_notes (number, company_code, customer_id, invoice_id, kind, amount, reason, their_number, note, created_by, request_id)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) returning id`,
          [number, iv.companyCode, iv.customerId, iv.id, body.kind, body.amount, body.reason, body.theirNumber, body.note, user.id, body.requestId ?? null],
        );
        // A note that makes the customer owe more takes money they paid on account.
        if (body.kind === 'debit_issued' || body.kind === 'credit_received') await applyOnAccount(client, iv.customerId, iv.companyCode);
        return { id: rows[0]!.id, number, invoiceNumber: iv.number };
      });
    } catch (err) {
      const again = (err as { code?: string }).code === '23505' ? await earlier() : null;
      if (again) return reply.code(200).send(again);
      throw err;
    }
    await audit(db, {
      userId: user.id,
      action: 'invoice_note_made',
      details: { noteId: saved.id, number: saved.number, kind: body.kind, invoice: saved.invoiceNumber, amount: body.amount },
    });
    return reply.code(201).send(await detail(saved.id));
  });

  app.get('/notes', { onRequest: auth, preHandler: noteViewers }, async (request) => {
    const query = parse(noteQuery, request.query);
    const extra: string[] = [];
    const params: unknown[] = [];
    const next = () => `$${params.length + 2}`;
    if (query.company) {
      params.push(query.company);
      extra.push(`n.company_code = ${next()}`);
    }
    if (query.kind) {
      params.push(query.kind);
      extra.push(`n.kind = ${next()}`);
    }
    if (query.q) {
      params.push(`%${query.q}%`);
      const p = next();
      extra.push(`(n.number ilike ${p} or iv.number ilike ${p} or cu.name ilike ${p} or n.their_number ilike ${p})`);
    }
    return notes(db, request.user, extra.join(' and ') || 'true', params);
  });
}

// Notes a user may see, newest first. `where` may use $3 onwards for `params`.
async function notes(db: Db, user: AuthUser, where: string, params: unknown[]) {
  const { rows } = await db.query(
    `select n.id, n.number, n.company_code as "companyCode", n.customer_id as "customerId", cu.name as "customerName",
       n.invoice_id as "invoiceId", iv.number as "invoiceNumber", n.kind, n.amount::float8 as amount,
       (${NOTE_SIGN('n')} * n.amount)::float8 as "owesChange", n.reason, n.their_number as "theirNumber", n.note,
       u.name as "createdBy", n.created_at as "createdAt", ${INVOICE_BALANCE('iv')}::float8 as "invoiceBalance"
     from invoice_notes n join customers cu on cu.id = n.customer_id join invoices iv on iv.id = n.invoice_id
       join users u on u.id = n.created_by
     where ${invoiceWhere(user, 'n')} and ${where} order by n.id desc limit 300`,
    [user.companies, user.id, ...params],
  );
  return rows.map((n) => ({ ...n, label: NOTE_LABEL[n.kind as (typeof NOTE_KINDS)[number]] }));
}
