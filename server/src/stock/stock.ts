import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, HttpError, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { invalid, notFound, idParams } from '../lists/common.js';
import { checkProductCompany } from '../lists/products.js';
import { nextNumber } from '../numbering.js';
import { INT4_MAX, parse } from '../users.js';

// The client asked that salesmen never see stock. Only these roles may.
export const STOCK_VIEWERS = ['admin', 'management', 'warehouse'] as const;
export const STOCK_KEEPERS = ['admin', 'warehouse'] as const;
// Approve damaged-stock write-offs. An admin's own write-off needs no approval.
export const STOCK_APPROVERS = ['admin', 'management'] as const;
const NEEDS_APPROVAL = new Set(['damaged']);

export const ADJUST_REASONS = ['damaged', 'expired', 'lost', 'found', 'other'] as const;
const REMOVING = new Set(['damaged', 'expired', 'lost']);

// Malawi time, so "today" does not depend on where the server runs.
export const TODAY = `(now() at time zone 'Africa/Blantyre')::date`;
const DEFAULT_SHORT_DATED_DAYS = 90;

const quantity = {
  cartons: z.number().int().min(0, 'Cartons cannot be below 0.').max(1_000_000).default(0),
  pieces: z.number().int().min(0, 'Pieces cannot be below 0.').max(INT4_MAX).default(0),
};

const receiptBody = z.object({
  companyCode: z.string(),
  supplier: z.string().trim().max(200).default(''),
  reference: z.string().trim().max(100).default(''),
  receivedOn: z.iso.date('Please pick the date the goods arrived.'),
  note: z.string().trim().max(1000).default(''),
  lines: z
    .array(
      z.object({
        productId: z.number().int().positive().max(INT4_MAX),
        batchNo: z.string().trim().min(1, 'Please type the batch number.').max(60).transform((s) => s.toUpperCase()),
        expiryDate: z.iso.date('Please pick the expiry date.'),
        ...quantity,
      }),
    )
    .min(1, 'Add at least one product.')
    .max(500),
});

const adjustBody = z.object({
  batchId: z.number().int().positive().max(INT4_MAX),
  reason: z.enum(ADJUST_REASONS, 'Please pick a reason.'),
  // Taken off for damaged, expired and lost; added for found; "other" needs a direction.
  direction: z.enum(['add', 'remove']).optional(),
  ...quantity,
  note: z.string().trim().max(1000).default(''),
});

const decisionBody = z.object({ note: z.string().trim().max(1000).default('') });

const countBody = z.object({
  companyCode: z.string(),
  note: z.string().trim().max(1000).default(''),
  lines: z
    .array(z.object({ batchId: z.number().int().positive().max(INT4_MAX), ...quantity }))
    .min(1, 'Count at least one batch.')
    .max(5000),
});

const stockQuery = z.object({
  company: z.string().optional(),
  q: z.string().trim().optional(),
  includeEmpty: z.coerce.boolean().optional(),
});
const shortDatedQuery = z.object({
  company: z.string().optional(),
  days: z.coerce.number().int().min(1).max(3650).default(DEFAULT_SHORT_DATED_DAYS),
});

type BatchRow = {
  id: number;
  productId: number;
  companyCode: string;
  batchNo: string;
  expiryDate: string;
  onHand: number;
  inTransit: number;
  piecesPerCarton: number;
};

const batchSelect = `select b.id, b.product_id as "productId", p.company_code as "companyCode", b.batch_no as "batchNo",
    to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate", b.on_hand as "onHand", b.in_transit as "inTransit",
    p.pieces_per_carton as "piecesPerCarton"
  from stock_batches b join products p on p.id = b.product_id`;

const totalPieces = (q: { cartons: number; pieces: number }, piecesPerCarton: number) => q.cartons * piecesPerCarton + q.pieces;

// Shown as "3 cartons + 4 pieces" in the app.
export const split = (pieces: number, piecesPerCarton: number) => ({
  pieces,
  cartons: Math.floor(pieces / piecesPerCarton),
  loosePieces: pieces % piecesPerCarton,
});

async function lockBatch(client: PoolClient, user: { companies: string[] }, batchId: number): Promise<BatchRow> {
  const { rows } = await client.query<BatchRow>(`${batchSelect} where b.id = $1 and p.company_code = any($2) for update of b`, [
    batchId,
    user.companies,
  ]);
  if (!rows[0]) throw notFound('batch');
  return rows[0];
}

// Loaded stock is still counted until it is delivered, but it is not on the shelf to take off.
const onShelf = (b: { onHand: number; inTransit: number }) => b.onHand - b.inTransit;
const notEnough = (shelf: number) => new HttpError(409, 'not_enough_stock', `Only ${shelf} pieces of this batch are in stock.`);

async function applyChange(
  client: PoolClient,
  batchId: number,
  change: number,
  reason: string,
  note: string,
  userId: number,
  requestId: number | null,
) {
  await client.query('update stock_batches set on_hand = on_hand + $2 where id = $1', [batchId, change]);
  await client.query(
    `insert into stock_movements (batch_id, change, kind, reason, note, user_id, request_id) values ($1, $2, 'adjusted', $3, $4, $5, $6)`,
    [batchId, change, reason, note, userId, requestId],
  );
}

export async function inTransaction<T>(db: Db, work: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await db.connect();
  try {
    await client.query('begin');
    const result = await work(client);
    await client.query('commit');
    return result;
  } catch (err) {
    await client.query('rollback');
    throw err;
  } finally {
    client.release();
  }
}

async function receiptDetail(db: Db | PoolClient, id: number, companies: string[]) {
  const { rows } = await db.query(
    `select r.id, r.number, r.company_code as "companyCode", r.supplier, r.reference, to_char(r.received_on, 'YYYY-MM-DD') as "receivedOn",
       r.note, u.name as "receivedBy", r.created_at as "createdAt",
       coalesce((select json_agg(json_build_object('productId', p.id, 'productCode', p.code, 'productName', p.name, 'packSize', p.pack_size,
           'batchNo', b.batch_no, 'expiryDate', to_char(b.expiry_date, 'YYYY-MM-DD'), 'pieces', m.change,
           'piecesPerCarton', p.pieces_per_carton) order by m.id)
         from stock_movements m join stock_batches b on b.id = m.batch_id join products p on p.id = b.product_id
         where m.receipt_id = r.id), '[]') as lines
     from stock_receipts r join users u on u.id = r.received_by
     where r.id = $1 and r.company_code = any($2)`,
    [id, companies],
  );
  return rows[0] ?? null;
}

export function stockRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const viewers = requireRole(...STOCK_VIEWERS);
  const keepers = requireRole(...STOCK_KEEPERS);
  const approvers = requireRole(...STOCK_APPROVERS);

  // Stock per product, with its batches oldest expiry first.
  app.get('/stock', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { company, q, includeEmpty } = parse(stockQuery, request.query);
    const companies = company ? request.user.companies.filter((c) => c === company) : request.user.companies;
    const params: unknown[] = [companies];
    let where = 'where p.company_code = any($1) and p.active';
    if (q) {
      params.push(`%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`);
      where += ` and (p.code ilike $2 or p.brand ilike $2 or p.name ilike $2)`;
    }
    const { rows } = await db.query<{
      productId: number;
      code: string;
      companyCode: string;
      brand: string;
      name: string;
      packSize: string;
      piecesPerCarton: number;
      onHand: number;
      nearestExpiry: string | null;
      reserved: number;
      inTransit: number;
      batches: { id: number; batchNo: string; expiryDate: string; onHand: number; inTransit: number; reserved: number }[];
    }>(
      `select p.id as "productId", p.code, p.company_code as "companyCode", p.brand, p.name, p.pack_size as "packSize",
         p.pieces_per_carton as "piecesPerCarton",
         coalesce((select sum(b.on_hand) from stock_batches b where b.product_id = p.id), 0)::int as "onHand",
         coalesce((select sum(b.in_transit) from stock_batches b where b.product_id = p.id), 0)::int as "inTransit",
         (select to_char(min(b.expiry_date), 'YYYY-MM-DD') from stock_batches b where b.product_id = p.id and b.on_hand > 0) as "nearestExpiry",
         coalesce((select sum(a.pieces) from order_allocations a join stock_batches b on b.id = a.batch_id where b.product_id = p.id), 0)::int
           as reserved,
         coalesce((select json_agg(json_build_object('id', b.id, 'batchNo', b.batch_no, 'expiryDate', to_char(b.expiry_date, 'YYYY-MM-DD'),
             'onHand', b.on_hand, 'inTransit', b.in_transit, 'reserved', coalesce((select sum(a.pieces) from order_allocations a where a.batch_id = b.id), 0))
             order by b.expiry_date, b.id)
           from stock_batches b where b.product_id = p.id and b.on_hand > 0), '[]') as batches
       from products p join companies co on co.code = p.company_code
       ${where}
       order by co.sort_order, p.brand, p.name, p.pack_size limit 2000`,
      params,
    );
    return rows
      .filter((r) => includeEmpty || r.onHand > 0)
      .map((r) => ({
        ...r,
        ...split(r.onHand, r.piecesPerCarton),
        // onHand counts stock out on vehicles until it is delivered. Reserved is set aside for open orders.
        // Free is what is on the shelf and can still be sold.
        free: Math.max(0, r.onHand - r.inTransit - r.reserved),
        batches: r.batches.map((b) => ({
          ...b,
          ...split(b.onHand, r.piecesPerCarton),
          free: Math.max(0, b.onHand - b.inTransit - b.reserved),
        })),
      }));
  });

  // Batches that expire within `days` (90 unless asked), or have expired, and still have stock.
  app.get('/stock/short-dated', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { company, days } = parse(shortDatedQuery, request.query);
    const companies = company ? request.user.companies.filter((c) => c === company) : request.user.companies;
    const { rows } = await db.query<BatchRow & { code: string; name: string; packSize: string; daysLeft: number }>(
      `${batchSelect.replace('select ', `select p.code, p.name, p.pack_size as "packSize", (b.expiry_date - ${TODAY}) as "daysLeft", `)}
       where p.company_code = any($1) and b.on_hand > 0 and b.expiry_date <= ${TODAY} + $2::int
       order by b.expiry_date, p.name`,
      [companies, days],
    );
    return rows.map((r) => ({ ...r, expired: r.daysLeft < 0, ...split(r.onHand, r.piecesPerCarton) }));
  });

  // History of one batch, newest first.
  app.get('/stock/batches/:id/movements', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const { rows: batch } = await db.query(`${batchSelect} where b.id = $1 and p.company_code = any($2)`, [id, request.user.companies]);
    if (!batch[0]) throw notFound('batch');
    const { rows } = await db.query(
      `select m.id, m.change, m.kind, m.reason, m.note, u.name as "userName", m.created_at as "createdAt",
         coalesce(r.number, c.number) as "documentNumber"
       from stock_movements m join users u on u.id = m.user_id
         left join stock_receipts r on r.id = m.receipt_id left join stock_counts c on c.id = m.count_id
       where m.batch_id = $1 order by m.id desc limit 500`,
      [id],
    );
    return rows;
  });

  // Goods arriving at the warehouse. A batch number seen before adds to that batch, if the expiry date matches.
  app.post('/stock/receipts', { onRequest: auth, preHandler: keepers }, async (request, reply) => {
    const body = parse(receiptBody, request.body);
    await checkProductCompany(db, body.companyCode);
    assertCompany(request.user, body.companyCode);

    const productIds = [...new Set(body.lines.map((l) => l.productId))];
    const { rows: products } = await db.query<{ id: number; companyCode: string; piecesPerCarton: number; name: string }>(
      `select id, company_code as "companyCode", pieces_per_carton as "piecesPerCarton", name from products where id = any($1) and active`,
      [productIds],
    );
    const byId = new Map(products.map((p) => [p.id, p]));
    const { rows: today } = await db.query<{ d: string }>(`select to_char(${TODAY}, 'YYYY-MM-DD') as d`);
    const seen = new Set<string>();
    body.lines.forEach((l, i) => {
      const p = byId.get(l.productId);
      const at = `Line ${i + 1}: `;
      if (!p || p.companyCode !== body.companyCode) throw invalid(`${at}that product is not one of this company's products.`);
      if (totalPieces(l, p.piecesPerCarton) === 0) throw invalid(`${at}please type how many arrived.`);
      if (l.expiryDate <= today[0]!.d) throw invalid(`${at}this batch has already expired. Do not receive it.`);
      const key = `${l.productId}/${l.batchNo}`;
      if (seen.has(key)) throw invalid(`${at}the same product and batch is on the list twice.`);
      seen.add(key);
    });

    const id = await inTransaction(db, async (client) => {
      const number = await nextNumber(client, body.companyCode, 'GRN');
      const { rows } = await client.query<{ id: number }>(
        `insert into stock_receipts (number, company_code, supplier, reference, received_on, note, received_by)
         values ($1, $2, $3, $4, $5, $6, $7) returning id`,
        [number, body.companyCode, body.supplier, body.reference, body.receivedOn, body.note, request.user.id],
      );
      const receiptId = rows[0]!.id;
      for (const [i, l] of body.lines.entries()) {
        const p = byId.get(l.productId)!;
        const pieces = totalPieces(l, p.piecesPerCarton);
        const { rows: batch } = await client.query<{ id: number; expiryDate: string }>(
          `insert into stock_batches (product_id, batch_no, expiry_date, on_hand) values ($1, $2, $3, $4)
           on conflict (product_id, batch_no) do update set on_hand = stock_batches.on_hand + excluded.on_hand
           returning id, to_char(expiry_date, 'YYYY-MM-DD') as "expiryDate"`,
          [l.productId, l.batchNo, l.expiryDate, pieces],
        );
        if (batch[0]!.expiryDate !== l.expiryDate) {
          throw invalid(`Line ${i + 1}: batch ${l.batchNo} is already in stock with expiry ${batch[0]!.expiryDate}. Please check the date.`);
        }
        await client.query(
          `insert into stock_movements (batch_id, change, kind, receipt_id, user_id) values ($1, $2, 'received', $3, $4)`,
          [batch[0]!.id, pieces, receiptId, request.user.id],
        );
      }
      return receiptId;
    });
    const receipt = await receiptDetail(db, id, request.user.companies);
    await audit(db, { userId: request.user.id, action: 'stock_received', details: { receiptId: id, number: receipt.number } });
    return reply.code(201).send(receipt);
  });

  app.get('/stock/receipts', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { rows } = await db.query(
      `select r.id, r.number, r.company_code as "companyCode", r.supplier, r.reference, to_char(r.received_on, 'YYYY-MM-DD') as "receivedOn",
         u.name as "receivedBy", (select count(*)::int from stock_movements m where m.receipt_id = r.id) as "lineCount"
       from stock_receipts r join users u on u.id = r.received_by
       where r.company_code = any($1) order by r.id desc limit 200`,
      [request.user.companies],
    );
    return rows;
  });

  app.get('/stock/receipts/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const receipt = await receiptDetail(db, id, request.user.companies);
    if (!receipt) throw notFound('receipt');
    return receipt;
  });

  // Damaged, expired, lost or found stock. Never takes a batch below 0.
  // Damaged stock waits for a manager's approval unless an admin writes it off.
  app.post('/stock/adjustments', { onRequest: auth, preHandler: keepers }, async (request, reply) => {
    const body = parse(adjustBody, request.body);
    const direction = body.reason === 'other' ? body.direction : REMOVING.has(body.reason) ? 'remove' : 'add';
    if (!direction) throw invalid('Please say whether stock is added or taken off.');
    if (body.reason === 'other' && body.note === '') throw invalid('Please write what happened.');
    const needsApproval = NEEDS_APPROVAL.has(body.reason) && request.user.role !== 'admin';

    const result = await inTransaction(db, async (client) => {
      const batch = await lockBatch(client, request.user, body.batchId);
      const pieces = totalPieces(body, batch.piecesPerCarton);
      if (pieces === 0) throw invalid('Please type how much.');
      const change = direction === 'remove' ? -pieces : pieces;
      if (onShelf(batch) + change < 0) throw notEnough(onShelf(batch));
      if (needsApproval) {
        const { rows } = await client.query<{ id: number }>(
          `insert into stock_adjustment_requests (batch_id, change, reason, note, requested_by) values ($1, $2, $3, $4, $5) returning id`,
          [batch.id, change, body.reason, body.note, request.user.id],
        );
        return { requestId: rows[0]!.id, batchId: batch.id, change, onHand: batch.onHand, piecesPerCarton: batch.piecesPerCarton };
      }
      await applyChange(client, batch.id, change, body.reason, body.note, request.user.id, null);
      return { requestId: null, batchId: batch.id, change, onHand: batch.onHand + change, piecesPerCarton: batch.piecesPerCarton };
    });
    await audit(db, {
      userId: request.user.id,
      action: result.requestId ? 'stock_adjustment_requested' : 'stock_adjusted',
      details: { batchId: result.batchId, change: result.change, reason: body.reason, requestId: result.requestId },
    });
    const out = { batchId: result.batchId, change: result.change, ...split(result.onHand, result.piecesPerCarton) };
    if (result.requestId) return reply.code(202).send({ ...out, waitingForApproval: true, requestId: result.requestId });
    return { ...out, waitingForApproval: false };
  });

  // Write-offs waiting for approval, oldest first.
  app.get('/stock/adjustments/pending', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { rows } = await db.query(
      `select r.id, r.batch_id as "batchId", b.batch_no as "batchNo", to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate",
         p.id as "productId", p.code, p.name, p.pack_size as "packSize", p.company_code as "companyCode",
         p.pieces_per_carton as "piecesPerCarton", r.change, r.reason, r.note, u.name as "requestedBy", r.requested_at as "requestedAt"
       from stock_adjustment_requests r join stock_batches b on b.id = r.batch_id join products p on p.id = b.product_id
         join users u on u.id = r.requested_by
       where r.status = 'pending' and p.company_code = any($1) order by r.requested_at, r.id`,
      [request.user.companies],
    );
    return rows;
  });

  for (const decision of ['approve', 'reject'] as const) {
    app.post(`/stock/adjustments/:id/${decision}`, { onRequest: auth, preHandler: approvers }, async (request) => {
      const { id } = parse(idParams, request.params);
      const { note } = parse(decisionBody, request.body ?? {});
      const result = await inTransaction(db, async (client) => {
        const { rows } = await client.query<{ id: number; batchId: number; change: number; reason: string; note: string; status: string; requestedBy: number }>(
          `select r.id, r.batch_id as "batchId", r.change, r.reason, r.note, r.status, r.requested_by as "requestedBy"
           from stock_adjustment_requests r join stock_batches b on b.id = r.batch_id join products p on p.id = b.product_id
           where r.id = $1 and p.company_code = any($2) for update of r`,
          [id, request.user.companies],
        );
        const req = rows[0];
        if (!req) throw notFound('request');
        if (req.status !== 'pending') throw new HttpError(409, 'already_decided', 'This request has already been decided.');
        const batch = await lockBatch(client, request.user, req.batchId);
        if (decision === 'approve') {
          if (onShelf(batch) + req.change < 0) throw notEnough(onShelf(batch));
          await applyChange(client, batch.id, req.change, req.reason, req.note, req.requestedBy, req.id);
        }
        await client.query(
          `update stock_adjustment_requests set status = $2, decided_by = $3, decided_at = now(), decision_note = $4 where id = $1`,
          [req.id, decision === 'approve' ? 'approved' : 'rejected', request.user.id, note],
        );
        const onHand = decision === 'approve' ? batch.onHand + req.change : batch.onHand;
        return { requestId: req.id, batchId: batch.id, change: req.change, onHand, piecesPerCarton: batch.piecesPerCarton };
      });
      await audit(db, {
        userId: request.user.id,
        action: decision === 'approve' ? 'stock_adjustment_approved' : 'stock_adjustment_rejected',
        details: { requestId: result.requestId, batchId: result.batchId, change: result.change },
      });
      return {
        requestId: result.requestId,
        status: decision === 'approve' ? 'approved' : 'rejected',
        batchId: result.batchId,
        change: result.change,
        ...split(result.onHand, result.piecesPerCarton),
      };
    });
  }

  // A stock count: the counted amount replaces what the app had, and each difference is recorded.
  app.post('/stock/counts', { onRequest: auth, preHandler: keepers }, async (request, reply) => {
    const body = parse(countBody, request.body);
    await checkProductCompany(db, body.companyCode);
    assertCompany(request.user, body.companyCode);
    if (new Set(body.lines.map((l) => l.batchId)).size !== body.lines.length) throw invalid('A batch is on the list twice.');

    const result = await inTransaction(db, async (client) => {
      const number = await nextNumber(client, body.companyCode, 'CNT');
      const { rows } = await client.query<{ id: number }>(
        'insert into stock_counts (number, company_code, note, counted_by) values ($1, $2, $3, $4) returning id',
        [number, body.companyCode, body.note, request.user.id],
      );
      const countId = rows[0]!.id;
      const differences = [];
      // Lock in id order so two counts at once cannot deadlock.
      const lines = [...body.lines].sort((a, b) => a.batchId - b.batchId);
      for (const l of lines) {
        const batch = await lockBatch(client, request.user, l.batchId);
        if (batch.companyCode !== body.companyCode) throw invalid(`Batch ${batch.batchNo} belongs to another company.`);
        const counted = totalPieces(l, batch.piecesPerCarton);
        // The count is of the shelf; stock out on vehicles stays as it is.
        const change = counted - onShelf(batch);
        if (change === 0) continue;
        await client.query('update stock_batches set on_hand = $2 where id = $1', [batch.id, counted + batch.inTransit]);
        await client.query(
          `insert into stock_movements (batch_id, change, kind, count_id, user_id) values ($1, $2, 'counted', $3, $4)`,
          [batch.id, change, countId, request.user.id],
        );
        differences.push({ batchId: batch.id, batchNo: batch.batchNo, before: onShelf(batch), counted, change });
      }
      return { id: countId, number, differences };
    });
    await audit(db, {
      userId: request.user.id,
      action: 'stock_counted',
      details: { countId: result.id, number: result.number, differences: result.differences.length },
    });
    return reply.code(201).send({ ...result, linesCounted: body.lines.length });
  });
}
