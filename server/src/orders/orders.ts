import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { CUSTOMER_OWES } from '../cash/balances.js';
import { assertCompany, HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { idParams, invalid, notFound, type Channel } from '../lists/common.js';
import { nextSharedNumber } from '../numbering.js';
import { inTransaction, split, TODAY } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// Book orders. A salesman books for themselves; a sales manager or admin may name the salesman.
export const ORDER_BOOKERS = ['admin', 'sales_manager', 'salesman'] as const;
// Hold, cancel and close any order of their companies.
export const ORDER_MANAGERS = ['admin', 'sales_manager'] as const;
// Everyone except drivers can see orders of their companies; a salesman sees only their own.
export const ORDER_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'warehouse', 'cashier', 'accounts'] as const;

// Least shelf life a batch must have left to be reserved for each kind of customer.
// Taken from the prototype's settings; still to confirm with the client.
export const MIN_SHELF_DAYS: Record<Channel, number> = { supermarket: 90, wholesaler: 30, minibus: 30, upcountry: 60 };
export const DEFAULT_VAT_RATE = 17.5;

// VAT on the lines that are not VAT exempt, rounded once for the whole invoice.
export function vatOn(lines: { amount: number; vatExempt: boolean }[], rate: number) {
  return Math.round((lines.reduce((t, l) => t + (l.vatExempt ? 0 : l.amount), 0) * rate) / 100);
}

export const INVOICE_TYPES = ['vat', 'plain'] as const;
const LIVE = ['open', 'hold'];

const id = z.number().int().positive().max(INT4_MAX);
const quantity = {
  cartons: z.number().int().min(0, 'Cartons cannot be below 0.').max(1_000_000).default(0),
  pieces: z.number().int().min(0, 'Pieces cannot be below 0.').max(INT4_MAX).default(0),
};
const price = z
  .number('Please type the price.')
  .int('Prices are whole kwacha.')
  .positive('Please type the price.')
  .max(100_000_000, 'That price looks too high. Please check it.');
const lpos = z.record(z.string(), z.string().trim().max(60));
const note = z.string().trim().max(1000);

const createBody = z.object({
  customerId: id,
  invoiceType: z.enum(INVOICE_TYPES, 'Please pick VAT invoice or plain invoice.'),
  salesmanId: id.optional(),
  wantedOn: z.iso.date('Please pick the delivery date.').optional(),
  note: note.default(''),
  lpos: lpos.default({}),
  lines: z
    .array(z.object({ productId: id, ...quantity, pricePerCarton: price }))
    .min(1, 'Add at least one product.')
    .max(300),
});

const editBody = z.object({
  invoiceType: z.enum(INVOICE_TYPES, 'Please pick VAT invoice or plain invoice.').optional(),
  wantedOn: z.iso.date('Please pick the delivery date.').nullable().optional(),
  note: note.optional(),
  lpos: lpos.optional(),
  lines: z.array(z.object({ lineId: id, ...quantity, pricePerCarton: price })).max(300).optional(),
});

const reasonBody = z.object({ note: note.default('') });

const listQuery = z.object({
  status: z.enum(['open', 'hold', 'done', 'cancelled']).optional(),
  customerId: z.coerce.number().int().positive().max(INT4_MAX).optional(),
  company: z.string().optional(),
  q: z.string().trim().optional(),
});

const totalPieces = (q: { cartons: number; pieces: number }, piecesPerCarton: number) => q.cartons * piecesPerCarton + q.pieces;
const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;
// "10 ctn", "10 ctn + 4 pcs" or "4 pcs".
export function qtyText(pieces: number, piecesPerCarton: number) {
  const { cartons, loosePieces } = split(pieces, piecesPerCarton);
  if (loosePieces === 0) return `${cartons} ctn`;
  return cartons ? `${cartons} ctn + ${loosePieces} pcs` : `${loosePieces} pcs`;
}
// Price is per carton, so loose pieces cost their share of a carton.
export const lineAmount = (pieces: number, pricePerCarton: number, piecesPerCarton: number) =>
  Math.round((pricePerCarton * pieces) / piecesPerCarton);

const isManager = (user: AuthUser) => (ORDER_MANAGERS as readonly string[]).includes(user.role);

// Which orders a user may see. $1 = their companies, $2 = their id.
function visibleWhere(user: AuthUser) {
  // Both branches name $1 and $2, so Postgres knows their types either way.
  if (user.role === 'salesman') return '$1::text[] is not null and (o.salesman_id = $2::int or o.booked_by = $2::int)';
  return '$2::int is not null and exists (select 1 from order_companies x where x.order_id = o.id and x.company_code = any($1::text[]))';
}

type OrderHead = {
  id: number;
  number: string;
  status: string;
  facilityCode: string;
  customerId: number;
  channel: Channel;
  salesmanId: number | null;
  bookedBy: number;
  invoiceType: string;
};

async function lockOrder(client: PoolClient, user: AuthUser, orderId: number): Promise<OrderHead> {
  const { rows } = await client.query<OrderHead>(
    `select o.id, o.number, o.status, o.facility_code as "facilityCode", o.customer_id as "customerId", c.channel,
       o.salesman_id as "salesmanId", o.booked_by as "bookedBy", o.invoice_type as "invoiceType"
     from orders o join customers c on c.id = o.customer_id
     where o.id = $3 and ${visibleWhere(user)} for update of o`,
    [user.companies, user.id, orderId],
  );
  if (!rows[0]) throw notFound('order');
  return rows[0];
}

// Managers may change any order of their companies; a salesman only their own order while it is open.
function assertCanChange(user: AuthUser, order: OrderHead) {
  if (isManager(user)) return;
  if (user.role === 'salesman' && (order.salesmanId === user.id || order.bookedBy === user.id) && order.status === 'open') return;
  throw notAllowed();
}

const notLive = (status: string) =>
  new HttpError(409, 'order_closed', status === 'cancelled' ? 'This order was cancelled.' : 'This order is already closed.');

export async function addEvent(client: PoolClient, orderId: number, userId: number, title: string, detail = '') {
  await client.query('insert into order_events (order_id, user_id, title, detail) values ($1, $2, $3, $4)', [orderId, userId, title, detail]);
  await client.query('update orders set updated_at = now() where id = $1', [orderId]);
}

async function releaseStock(client: PoolClient, orderId: number) {
  await client.query('delete from order_allocations where line_id in (select id from order_lines where order_id = $1)', [orderId]);
}

// Pieces of each line on loading plans that have not been loaded yet, or on a trip that has not left yet
// (a trip carries all of what is still to deliver).
export const PLANNED_SQL = (line: string) =>
  `(coalesce((select sum(i.pieces) from loading_plan_items i join loading_plans lp on lp.id = i.plan_id
     where i.order_line_id = ${line} and lp.status = 'planned'), 0)
   + coalesce((select max(tl.pieces - tl.delivered - tl.on_vehicle) from order_lines tl join trip_orders tro on tro.order_id = tl.order_id
     join trips tt on tt.id = tro.trip_id where tl.id = ${line} and tt.status = 'planned'), 0))::int`;

// Takes an order off every loading plan and trip that is not loaded yet (on hold, cancel or close).
// A plan already sent to the warehouse shows the change as new. The caller holds the order's lock; plan code
// takes order locks too before changing items, so plans and orders never wait on each other the other way round.
export async function dropFromPlans(client: PoolClient, orderId: number, userId: number) {
  const { rows } = await client.query<{ itemId: number; planId: number; lineId: number; productId: number; pieces: number; sent: boolean }>(
    `select i.id as "itemId", i.plan_id as "planId", l.id as "lineId", l.product_id as "productId", i.pieces, lp.sent_at is not null as sent
     from loading_plan_items i join loading_plans lp on lp.id = i.plan_id join order_lines l on l.id = i.order_line_id
     where l.order_id = $1 and lp.status = 'planned' order by i.plan_id`,
    [orderId],
  );
  for (const r of rows) {
    await client.query('delete from loading_plan_items where id = $1', [r.itemId]);
    await client.query(
      `insert into loading_plan_changes (plan_id, user_id, product_id, order_line_id, before, after, reason, seen)
       values ($1, $2, $3, $4, $5, 0, 'Order taken off the plan', $6)`,
      [r.planId, userId, r.productId, r.lineId, r.pieces, !r.sent],
    );
  }
  // A trip that has not left yet simply stops carrying it.
  const offTrips = await client.query(
    `delete from trip_orders where order_id = $1 and trip_id in (select id from trips where status = 'planned')`,
    [orderId],
  );
  return rows.length > 0 || (offTrips.rowCount ?? 0) > 0;
}

// Where an open order stands, worked out from its lines.
export function stageOf(status: string, t: { onVehicle: number; planned: number; delivered: number }) {
  if (status !== 'open') return status;
  if (t.onVehicle > 0) return 'on_vehicle';
  if (t.planned > 0) return 'planned';
  if (t.delivered > 0) return 'part_delivered';
  return 'open';
}

// Sets stock aside for every line still to deliver, oldest expiry first, skipping batches with too little shelf life left.
// Starts again from nothing each time, so a line that was short picks up stock received since.
// Returns how many pieces could not be reserved.
export async function reserveStock(client: PoolClient, orderId: number, channel: Channel): Promise<number> {
  await releaseStock(client, orderId);
  const { rows: lines } = await client.query<{ id: number; productId: number; need: number }>(
    `select id, product_id as "productId", pieces - delivered - on_vehicle as need from order_lines
     where order_id = $1 and pieces > delivered + on_vehicle order by id`,
    [orderId],
  );
  if (lines.length === 0) return 0;
  // Lock every batch of these products in id order, so two orders at once cannot deadlock or reserve the same pieces.
  const { rows: batches } = await client.query<{ id: number; productId: number; expiryDate: string; free: number }>(
    `select b.id, b.product_id as "productId", to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate",
       b.on_hand - b.in_transit - coalesce((select sum(a.pieces) from order_allocations a where a.batch_id = b.id), 0)::int as free
     from stock_batches b
     where b.product_id = any($1) and b.on_hand > b.in_transit and b.expiry_date >= ${TODAY} + $2::int
     order by b.id for update of b`,
    [[...new Set(lines.map((l) => l.productId))], MIN_SHELF_DAYS[channel]],
  );
  const oldestFirst = [...batches].sort((a, b) => a.expiryDate.localeCompare(b.expiryDate) || a.id - b.id);
  let short = 0;
  for (const line of lines) {
    let need = line.need;
    for (const b of oldestFirst) {
      if (need === 0) break;
      if (b.productId !== line.productId || b.free <= 0) continue;
      const take = Math.min(need, b.free);
      await client.query('insert into order_allocations (line_id, batch_id, pieces) values ($1, $2, $3)', [line.id, b.id, take]);
      b.free -= take;
      need -= take;
    }
    short += need;
  }
  return short;
}

type LineRow = {
  id: number;
  productId: number;
  code: string;
  brand: string;
  name: string;
  packSize: string;
  companyCode: string;
  piecesPerCarton: number;
  vatExempt: boolean;
  pieces: number;
  pricePerCarton: number;
  delivered: number;
  onVehicle: number;
  planned: number;
  reserved: number;
};
type CompanyRow = { code: string; name: string; lpoNo: string; vatRate: number };

// One order as the app shows it. Lines and totals cover only the companies the user can see.
export async function orderDetail(db: Db | PoolClient, user: AuthUser, orderId: number) {
  const { rows } = await db.query(
    `select o.id, o.number, o.status, o.facility_code as "facilityCode", o.invoice_type as "invoiceType",
       to_char(o.wanted_on, 'YYYY-MM-DD') as "wantedOn", o.note, o.created_at as "createdAt", o.updated_at as "updatedAt",
       o.customer_id as "customerId", c.name as "customerName", c.channel, c.area, c.needs_lpo as "needsLpo",
       o.salesman_id as "salesmanId", s.name as "salesmanName", o.booked_by as "bookedById", b.name as "bookedBy",
       coalesce((select json_agg(json_build_object('code', oc.company_code, 'name', co.name, 'lpoNo', oc.lpo_no,
           'vatRate', coalesce(co.vat_rate, ${DEFAULT_VAT_RATE})) order by co.sort_order)
         from order_companies oc join companies co on co.code = oc.company_code
         where oc.order_id = o.id and oc.company_code = any($1)), '[]') as companies,
       coalesce((select json_agg(json_build_object('id', l.id, 'productId', p.id, 'code', p.code, 'brand', p.brand, 'name', p.name,
           'packSize', p.pack_size, 'companyCode', p.company_code, 'piecesPerCarton', p.pieces_per_carton, 'vatExempt', p.vat_exempt, 'pieces', l.pieces,
           'pricePerCarton', l.price_per_carton, 'delivered', l.delivered, 'onVehicle', l.on_vehicle, 'planned', ${PLANNED_SQL('l.id')},
           'reserved', coalesce((select sum(a.pieces) from order_allocations a where a.line_id = l.id), 0)) order by l.id)
         from order_lines l join products p on p.id = l.product_id
         where l.order_id = o.id and p.company_code = any($1)), '[]') as lines,
       coalesce((select json_agg(json_build_object('title', e.title, 'detail', e.detail, 'by', u.name, 'at', e.created_at) order by e.id)
         from order_events e join users u on u.id = e.user_id where e.order_id = o.id), '[]') as history
     from orders o join customers c on c.id = o.customer_id join users b on b.id = o.booked_by left join users s on s.id = o.salesman_id
     where o.id = $3 and ${visibleWhere(user)}`,
    [user.companies, user.id, orderId],
  );
  const o = rows[0];
  if (!o) return null;
  const live = LIVE.includes(o.status);
  const lines = (o.lines as LineRow[]).map((l) => ({
    ...l,
    ...split(l.pieces, l.piecesPerCarton),
    amount: lineAmount(l.pieces, l.pricePerCarton, l.piecesPerCarton),
    // Pieces still to deliver that are neither on a vehicle nor set aside in the warehouse.
    shortPieces: live ? Math.max(0, l.pieces - l.delivered - l.onVehicle - l.reserved) : 0,
  }));
  const companies = (o.companies as CompanyRow[]).map((c) => {
    const its = lines.filter((l) => l.companyCode === c.code);
    const subtotal = its.reduce((t, l) => t + l.amount, 0);
    const vat = o.invoiceType === 'vat' ? vatOn(its, Number(c.vatRate)) : 0;
    return { code: c.code, name: c.name, lpoNo: c.lpoNo, subtotal, vatRate: o.invoiceType === 'vat' ? Number(c.vatRate) : 0, vat, total: subtotal + vat };
  });
  const sum = (k: 'subtotal' | 'vat' | 'total') => companies.reduce((t, c) => t + c[k], 0);
  const canChange = (() => {
    try {
      assertCanChange(user, { ...o, bookedBy: o.bookedById });
      return live;
    } catch {
      return false;
    }
  })();
  const total = (k: 'onVehicle' | 'planned' | 'delivered') => lines.reduce((t, l) => t + l[k], 0);
  return {
    ...o,
    stage: stageOf(o.status, { onVehicle: total('onVehicle'), planned: total('planned'), delivered: total('delivered') }),
    companies,
    lines,
    subtotal: sum('subtotal'),
    vat: sum('vat'),
    total: sum('total'),
    shortPieces: lines.reduce((t, l) => t + l.shortPieces, 0),
    canChange,
    canManage: isManager(user) && live,
  };
}

async function checkSalesman(db: Db, salesmanId: number, companies: string[]) {
  const { rows } = await db.query<{ ok: boolean }>(
    `select u.role = 'salesman' and u.active
       and (select count(*) from user_companies uc where uc.user_id = u.id and uc.company_code = any($2)) = cardinality($2::text[]) as ok
     from users u where u.id = $1`,
    [salesmanId, companies],
  );
  if (!rows[0]?.ok) throw invalid('That salesman does not sell for these companies.');
}

export function orderRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const bookers = requireRole(...ORDER_BOOKERS);
  const managers = requireRole(...ORDER_MANAGERS);
  const viewers = requireRole(...ORDER_VIEWERS);

  // What the booking screen needs once a customer is picked: their companies with what they owe,
  // the last invoice type and the last price typed for each product.
  app.get('/orders/booking/:id', { onRequest: auth, preHandler: bookers }, async (request) => {
    const { id: customerId } = parse(idParams, request.params);
    const { rows } = await db.query(
      `select c.id, c.name, c.channel, c.area, c.needs_lpo as "needsLpo", c.cash_only as "cashOnly", c.last_invoice_type as "lastInvoiceType"
       from customers c where c.id = $1 and c.active
         and exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($2))`,
      [customerId, request.user.companies],
    );
    if (!rows[0]) throw notFound('customer');
    // What a customer owes: their opening balance plus their invoices, less what they paid.
    const { rows: companies } = await db.query(
      `select co.code, co.name, co.facility_code as "facilityCode", cc.payment_terms_days as "paymentTermsDays",
         ${CUSTOMER_OWES('cc.customer_id', 'cc.company_code')}::float8 as balance
       from customer_companies cc join companies co on co.code = cc.company_code
       where cc.customer_id = $1 and cc.company_code = any($2) order by co.sort_order`,
      [customerId, request.user.companies],
    );
    const { rows: lastPrices } = await db.query(
      `select distinct on (l.product_id) l.product_id as "productId", l.price_per_carton as "pricePerCarton"
       from order_lines l join orders o on o.id = l.order_id join products p on p.id = l.product_id
       where o.customer_id = $1 and o.status <> 'cancelled' and p.company_code = any($2)
       order by l.product_id, o.id desc`,
      [customerId, request.user.companies],
    );
    return { customer: rows[0], companies, lastPrices, minShelfDays: MIN_SHELF_DAYS[rows[0].channel as Channel] };
  });

  app.post('/orders', { onRequest: auth, preHandler: bookers }, async (request, reply) => {
    const body = parse(createBody, request.body);
    const user = request.user;

    const productIds = body.lines.map((l) => l.productId);
    if (new Set(productIds).size !== productIds.length) throw invalid('The same product is on the order twice.');
    const { rows: products } = await db.query<{ id: number; companyCode: string; facilityCode: string; piecesPerCarton: number; name: string }>(
      `select p.id, p.company_code as "companyCode", co.facility_code as "facilityCode", p.pieces_per_carton as "piecesPerCarton", p.name
       from products p join companies co on co.code = p.company_code where p.id = any($1) and p.active`,
      [productIds],
    );
    const byId = new Map(products.map((p) => [p.id, p]));
    body.lines.forEach((l, i) => {
      const p = byId.get(l.productId);
      if (!p) throw invalid(`Line ${i + 1}: that product was not found.`);
      if (totalPieces(l, p.piecesPerCarton) === 0) throw invalid(`Line ${i + 1}: please type how many.`);
    });
    const facilities = new Set(products.map((p) => p.facilityCode));
    if (facilities.size > 1) throw invalid('One order can only have products from one warehouse. Please make a second order.');
    const facilityCode = products[0]!.facilityCode;
    const { rows: coRows } = await db.query<{ code: string; name: string }>(
      'select code, name from companies where code = any($1) order by sort_order',
      [[...new Set(products.map((p) => p.companyCode))]],
    );
    for (const co of coRows) assertCompany(user, co.code);

    const { rows: customers } = await db.query<{ id: number; name: string; channel: Channel; needsLpo: boolean; companies: string[] }>(
      `select c.id, c.name, c.channel, c.needs_lpo as "needsLpo",
         coalesce((select array_agg(cc.company_code) from customer_companies cc where cc.customer_id = c.id), '{}') as companies
       from customers c where c.id = $1 and c.active`,
      [body.customerId],
    );
    const customer = customers[0];
    if (!customer || !customer.companies.some((c) => user.companies.includes(c))) throw notFound('customer');
    for (const co of coRows) {
      if (!customer.companies.includes(co.code)) throw invalid(`${customer.name} does not buy from ${co.name}. Please remove those products.`);
      if (customer.needsLpo && !body.lpos[co.code]) throw invalid(`Please type the LPO number for ${co.name}.`);
    }

    let salesmanId: number | null = null;
    if (user.role === 'salesman') salesmanId = user.id;
    else if (body.salesmanId) {
      await checkSalesman(db, body.salesmanId, coRows.map((c) => c.code));
      salesmanId = body.salesmanId;
    }
    if (body.wantedOn) {
      const { rows: today } = await db.query<{ d: string }>(`select to_char(${TODAY}, 'YYYY-MM-DD') as d`);
      if (body.wantedOn < today[0]!.d) throw invalid('The delivery date cannot be in the past.');
    }

    const result = await inTransaction(db, async (client) => {
      const number = await nextSharedNumber(client, 'SO');
      const { rows } = await client.query<{ id: number }>(
        `insert into orders (number, facility_code, customer_id, salesman_id, invoice_type, wanted_on, note, booked_by)
         values ($1, $2, $3, $4, $5, $6, $7, $8) returning id`,
        [number, facilityCode, customer.id, salesmanId, body.invoiceType, body.wantedOn ?? null, body.note, user.id],
      );
      const orderId = rows[0]!.id;
      for (const co of coRows) {
        await client.query('insert into order_companies (order_id, company_code, lpo_no) values ($1, $2, $3)', [
          orderId,
          co.code,
          body.lpos[co.code] ?? '',
        ]);
      }
      let amount = 0;
      for (const l of body.lines) {
        const p = byId.get(l.productId)!;
        const pieces = totalPieces(l, p.piecesPerCarton);
        amount += lineAmount(pieces, l.pricePerCarton, p.piecesPerCarton);
        await client.query('insert into order_lines (order_id, product_id, pieces, price_per_carton) values ($1, $2, $3, $4)', [
          orderId,
          l.productId,
          pieces,
          l.pricePerCarton,
        ]);
      }
      const short = await reserveStock(client, orderId, customer.channel);
      await client.query('update customers set last_invoice_type = $2 where id = $1', [customer.id, body.invoiceType]);
      const lpoText = coRows.filter((c) => body.lpos[c.code]).map((c) => `LPO ${body.lpos[c.code]}`);
      await addEvent(
        client,
        orderId,
        user.id,
        'Order booked',
        [`${body.lines.length} ${body.lines.length === 1 ? 'product' : 'products'}`, `${money(amount)} before VAT`, ...lpoText].join(' · '),
      );
      await addEvent(
        client,
        orderId,
        user.id,
        'Stock reserved',
        short ? 'Not all of it is in stock. The rest waits for new stock.' : 'All of it, oldest expiry first.',
      );
      return { orderId, number };
    });
    await audit(db, { userId: user.id, action: 'order_booked', details: { orderId: result.orderId, number: result.number } });
    return reply.code(201).send(await orderDetail(db, user, result.orderId));
  });

  app.get('/orders', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(listQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = visibleWhere(user);
    if (query.status) {
      params.push(query.status);
      where += ` and o.status = $${params.length}`;
    }
    if (query.customerId) {
      params.push(query.customerId);
      where += ` and o.customer_id = $${params.length}`;
    }
    if (query.company) {
      params.push(query.company);
      where += ` and exists (select 1 from order_companies y where y.order_id = o.id and y.company_code = $${params.length})`;
    }
    if (query.q) {
      params.push(`%${query.q.replace(/[\\%_]/g, (m) => '\\' + m)}%`);
      where += ` and (o.number ilike $${params.length} or c.name ilike $${params.length})`;
    }
    const { rows } = await db.query<{
      id: number;
      number: string;
      status: string;
      invoiceType: string;
      companies: { code: string; vatRate: number; lpoNo: string; subtotal: number; taxable: number }[];
      shortPieces: number;
      onVehicle: number;
      planned: number;
      delivered: number;
    }>(
      `select o.id, o.number, o.status, o.invoice_type as "invoiceType", to_char(o.wanted_on, 'YYYY-MM-DD') as "wantedOn",
         o.created_at as "createdAt", o.customer_id as "customerId", c.name as "customerName", c.area, s.name as "salesmanName",
         coalesce((select json_agg(json_build_object('code', oc.company_code, 'lpoNo', oc.lpo_no,
             'vatRate', coalesce(co.vat_rate, ${DEFAULT_VAT_RATE}),
             'subtotal', (select coalesce(sum(round(l.price_per_carton::numeric * l.pieces / p.pieces_per_carton)), 0)
                          from order_lines l join products p on p.id = l.product_id
                          where l.order_id = o.id and p.company_code = oc.company_code),
             'taxable', (select coalesce(sum(round(l.price_per_carton::numeric * l.pieces / p.pieces_per_carton)), 0)
                          from order_lines l join products p on p.id = l.product_id
                          where l.order_id = o.id and p.company_code = oc.company_code and not p.vat_exempt)) order by co.sort_order)
           from order_companies oc join companies co on co.code = oc.company_code
           where oc.order_id = o.id and oc.company_code = any($1)), '[]') as companies,
         case when o.status in ('open', 'hold') then
           (select coalesce(sum(l.pieces - l.delivered - l.on_vehicle), 0) from order_lines l where l.order_id = o.id)
           - (select coalesce(sum(a.pieces), 0) from order_allocations a join order_lines l on l.id = a.line_id where l.order_id = o.id)
         else 0 end::int as "shortPieces",
         (select coalesce(sum(l.on_vehicle), 0) from order_lines l where l.order_id = o.id)::int as "onVehicle",
         (select coalesce(sum(l.delivered), 0) from order_lines l where l.order_id = o.id)::int as delivered,
         (select coalesce(sum(${PLANNED_SQL('l.id')}), 0) from order_lines l where l.order_id = o.id)::int as planned
       from orders o join customers c on c.id = o.customer_id left join users s on s.id = o.salesman_id
       where ${where}
       order by o.id desc limit 300`,
      params,
    );
    return rows.map(({ companies, onVehicle, planned, delivered, ...o }) => {
      const total = companies.reduce((t, c) => {
        const subtotal = Number(c.subtotal);
        return t + subtotal + (o.invoiceType === 'vat' ? Math.round((Number(c.taxable) * Number(c.vatRate)) / 100) : 0);
      }, 0);
      return { ...o, stage: stageOf(o.status, { onVehicle, planned, delivered }), companies: companies.map((c) => ({ code: c.code, lpoNo: c.lpoNo })), total };
    });
  });

  app.get('/orders/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const order = await orderDetail(db, request.user, id);
    if (!order) throw notFound('order');
    return order;
  });

  // Change quantities, prices, LPO numbers, invoice type, delivery date or note. Never below what was delivered.
  app.patch('/orders/:id', { onRequest: auth, preHandler: bookers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(editBody, request.body);
    const user = request.user;
    const changed = await inTransaction(db, async (client) => {
      const order = await lockOrder(client, user, id);
      if (!LIVE.includes(order.status)) throw notLive(order.status);
      assertCanChange(user, order);
      const changes: string[] = [];

      if (body.lines) {
        const { rows: lines } = await client.query<{
          id: number;
          pieces: number;
          delivered: number;
          taken: number;
          pricePerCarton: number;
          piecesPerCarton: number;
          companyCode: string;
          label: string;
        }>(
          `select l.id, l.pieces, l.delivered, l.delivered + l.on_vehicle + ${PLANNED_SQL('l.id')} as taken, l.price_per_carton as "pricePerCarton", p.pieces_per_carton as "piecesPerCarton",
             p.company_code as "companyCode", p.brand || ' ' || p.name || ' ' || p.pack_size as label
           from order_lines l join products p on p.id = l.product_id where l.order_id = $1`,
          [order.id],
        );
        const byLine = new Map(lines.map((l) => [l.id, l]));
        if (new Set(body.lines.map((l) => l.lineId)).size !== body.lines.length) throw invalid('A product is on the list twice.');
        const after = new Map(lines.map((l) => [l.id, l.pieces]));
        for (const e of body.lines) {
          const line = byLine.get(e.lineId);
          if (!line || !user.companies.includes(line.companyCode)) throw invalid('That product is not on this order.');
          const pieces = totalPieces(e, line.piecesPerCarton);
          if (pieces < line.delivered) {
            throw invalid(`${line.label}: ${qtyText(line.delivered, line.piecesPerCarton)} has already been delivered. The order cannot be less.`);
          }
          if (pieces < line.taken) {
            throw invalid(`${line.label}: ${qtyText(line.taken, line.piecesPerCarton)} is already on a loading plan or vehicle. The order cannot be less.`);
          }
          after.set(line.id, pieces);
          if (pieces !== line.pieces) {
            changes.push(`${line.label}: ${qtyText(line.pieces, line.piecesPerCarton)} → ${qtyText(pieces, line.piecesPerCarton)}`);
          }
          if (e.pricePerCarton !== line.pricePerCarton) {
            changes.push(`${line.label}: price ${money(line.pricePerCarton)} → ${money(e.pricePerCarton)}`);
          }
          await client.query('update order_lines set pieces = $2, price_per_carton = $3 where id = $1', [line.id, pieces, e.pricePerCarton]);
        }
        if ([...after.values()].every((p) => p === 0)) throw invalid('An order needs at least one product. To stop the whole order, cancel it.');
      }

      if (body.lpos) {
        const { rows: cos } = await client.query<{ code: string; name: string; lpoNo: string; needsLpo: boolean }>(
          `select oc.company_code as code, co.name, oc.lpo_no as "lpoNo", c.needs_lpo as "needsLpo"
           from order_companies oc join companies co on co.code = oc.company_code join orders o on o.id = oc.order_id
             join customers c on c.id = o.customer_id
           where oc.order_id = $1 and oc.company_code = any($2)`,
          [order.id, user.companies],
        );
        for (const co of cos) {
          const lpo = body.lpos[co.code];
          if (lpo === undefined || lpo === co.lpoNo) continue;
          if (co.needsLpo && !lpo) throw invalid(`Please type the LPO number for ${co.name}.`);
          await client.query('update order_companies set lpo_no = $3 where order_id = $1 and company_code = $2', [order.id, co.code, lpo]);
          changes.push(`${co.name} LPO: ${co.lpoNo || 'none'} → ${lpo || 'none'}`);
        }
      }

      if (body.invoiceType && body.invoiceType !== order.invoiceType) {
        await client.query('update orders set invoice_type = $2 where id = $1', [order.id, body.invoiceType]);
        await client.query('update customers set last_invoice_type = $2 where id = $1', [order.customerId, body.invoiceType]);
        changes.push(`Invoice: ${body.invoiceType === 'vat' ? 'plain → VAT' : 'VAT → plain'}`);
      }
      if (body.wantedOn) {
        const { rows: today } = await client.query<{ d: string }>(`select to_char(${TODAY}, 'YYYY-MM-DD') as d`);
        if (body.wantedOn < today[0]!.d) throw invalid('The delivery date cannot be in the past.');
      }
      if (body.wantedOn !== undefined) {
        const { rowCount } = await client.query(
          `update orders set wanted_on = $2 where id = $1 and wanted_on is distinct from $2::date`,
          [order.id, body.wantedOn],
        );
        if (rowCount) changes.push(`Delivery date: ${body.wantedOn ?? 'none'}`);
      }
      if (body.note !== undefined) {
        const { rowCount } = await client.query('update orders set note = $2 where id = $1 and note <> $2', [order.id, body.note]);
        if (rowCount) changes.push('Note changed');
      }

      if (changes.length === 0) return null;
      if (body.lines) await reserveStock(client, order.id, order.channel);
      await addEvent(client, order.id, user.id, 'Changed', changes.join('; '));
      return { number: order.number, changes };
    });
    if (changed) await audit(db, { userId: user.id, action: 'order_changed', details: { orderId: id, number: changed.number, changes: changed.changes } });
    return orderDetail(db, user, id);
  });

  // Try again to set stock aside, for example after new stock arrived.
  app.post('/orders/:id/reserve', { onRequest: auth, preHandler: bookers }, async (request) => {
    const { id } = parse(idParams, request.params);
    await inTransaction(db, async (client) => {
      const order = await lockOrder(client, request.user, id);
      if (!LIVE.includes(order.status)) throw notLive(order.status);
      assertCanChange(request.user, order);
      const short = await reserveStock(client, order.id, order.channel);
      await addEvent(client, order.id, request.user.id, 'Stock reserved again', short ? 'Still not all in stock.' : 'All of it is now reserved.');
    });
    return orderDetail(db, request.user, id);
  });

  // Hold: stock stays reserved but the order cannot be loaded. Release: it can be loaded again.
  for (const action of ['hold', 'release', 'cancel', 'close'] as const) {
    app.post(`/orders/:id/${action}`, { onRequest: auth, preHandler: action === 'cancel' ? bookers : managers }, async (request) => {
      const { id } = parse(idParams, request.params);
      const { note: why } = parse(reasonBody, request.body ?? {});
      const user = request.user;
      const number = await inTransaction(db, async (client) => {
        const order = await lockOrder(client, user, id);
        if (!LIVE.includes(order.status)) throw notLive(order.status);
        assertCanChange(user, order);
        const { rows } = await client.query<{ delivered: number; onVehicle: number }>(
          `select coalesce(sum(delivered), 0)::int as delivered, coalesce(sum(on_vehicle), 0)::int as "onVehicle" from order_lines where order_id = $1`,
          [order.id],
        );
        const { delivered, onVehicle } = rows[0]!;
        const onVehicleError = () =>
          new HttpError(409, 'on_vehicle', 'Part of this order is on a vehicle. Wait until the delivery is confirmed.');
        let status = order.status;
        let title: string;
        let detail: string;
        if (action === 'hold') {
          if (order.status === 'hold') throw new HttpError(409, 'already_on_hold', 'This order is already on hold.');
          status = 'hold';
          title = 'Put on hold';
          const offPlans = await dropFromPlans(client, order.id, user.id);
          detail = offPlans
            ? 'Taken off the loading plan. Cannot be loaded until released. Stock stays reserved.'
            : 'Cannot be loaded until released. Stock stays reserved.';
        } else if (action === 'release') {
          if (order.status !== 'hold') throw new HttpError(409, 'not_on_hold', 'This order is not on hold.');
          status = 'open';
          title = 'Hold released';
          const short = await reserveStock(client, order.id, order.channel);
          detail = short ? 'Can be loaded again. Not all of it is in stock.' : 'Can be loaded again.';
        } else if (action === 'cancel') {
          if (delivered > 0) throw new HttpError(409, 'part_delivered', 'Part of this order has been delivered. Close it instead of cancelling.');
          if (onVehicle > 0) throw onVehicleError();
          status = 'cancelled';
          title = 'Cancelled';
          await dropFromPlans(client, order.id, user.id);
          await releaseStock(client, order.id);
          detail = 'Reserved stock released.';
        } else {
          if (delivered === 0) throw new HttpError(409, 'nothing_delivered', 'Nothing has been delivered yet. Cancel the order instead.');
          if (onVehicle > 0) throw onVehicleError();
          status = 'done';
          title = 'Closed';
          await dropFromPlans(client, order.id, user.id);
          await releaseStock(client, order.id);
          detail = 'The rest of the order will not be delivered. Its reserved stock was released.';
        }
        await client.query('update orders set status = $2 where id = $1', [order.id, status]);
        await addEvent(client, order.id, user.id, title, why ? `${detail} ${why}` : detail);
        return order.number;
      });
      const actions = { hold: 'order_held', release: 'order_released', cancel: 'order_cancelled', close: 'order_closed' };
      await audit(db, { userId: user.id, action: actions[action], details: { orderId: id, number, note: why } });
      return orderDetail(db, user, id);
    });
  }
}
