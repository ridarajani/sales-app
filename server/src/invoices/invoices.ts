import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { INVOICE_BALANCE, NOTE_SIGN } from '../cash/balances.js';
import { applyOnAccount } from '../cash/collections.js';
import { HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { logoPath } from '../files.js';
import { idParams, notFound } from '../lists/common.js';
import { INVOICE_MAKERS, lockPlan, planDetail } from '../loading/loading.js';
import { nextNumber } from '../numbering.js';
import { addEvent, DEFAULT_VAT_RATE, lineAmount, qtyText, vatOn } from '../orders/orders.js';
import { invoicePdf } from '../print/pdf.js';
import { ADJUSTERS } from '../returns/returns.js';
import { inTransaction, TODAY } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// Warehouse staff never see invoices; everyone else sees those of their companies (drivers: their own vehicles, salesmen: their own).
export const INVOICE_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'driver', 'cashier', 'accounts'] as const;

const listQuery = z.object({
  company: z.string().optional(),
  customerId: z.coerce.number().int().positive().max(INT4_MAX).optional(),
  planId: z.coerce.number().int().positive().max(INT4_MAX).optional(),
  type: z.enum(['vat', 'plain']).optional(),
  from: z.iso.date().optional(),
  to: z.iso.date().optional(),
  q: z.string().trim().optional(),
});

// Which invoices a user may see. $1 = their companies, $2 = their id; every branch names both.
export function visibleWhere(user: AuthUser) {
  const mine = `iv.company_code = any($1::text[])`;
  if (user.role === 'salesman') return `${mine} and iv.salesman_id = $2::int`;
  if (user.role === 'driver') {
    return `${mine} and (exists (select 1 from loading_plans x where x.id = iv.plan_id and x.driver_id = $2::int)
      or exists (select 1 from trip_sales xs join trips xt on xt.id = xs.trip_id where xs.id = iv.trip_sale_id and xt.driver_id = $2::int))`;
  }
  return `${mine} and $2::int is not null`;
}

type LoadedItem = {
  itemId: number;
  lineId: number;
  orderId: number;
  orderNumber: string;
  customerId: number;
  invoiceType: 'vat' | 'plain';
  salesmanId: number | null;
  companyCode: string;
  lpoNo: string;
  productId: number;
  piecesPerCarton: number;
  vatExempt: boolean;
  pricePerCarton: number;
  loaded: number;
};

async function invoiceDetail(db: Db, user: AuthUser, invoiceId: number) {
  const { rows } = await db.query(
    `select iv.id, iv.number, iv.company_code as "companyCode", iv.invoice_type as "invoiceType", iv.plan_id as "planId",
       iv.lpo_no as "lpoNo", to_char(iv.invoice_date, 'YYYY-MM-DD') as "invoiceDate", to_char(iv.due_date, 'YYYY-MM-DD') as "dueDate",
       iv.subtotal::float8 as subtotal, iv.vat_rate::float8 as "vatRate", iv.vat::float8 as vat, iv.total::float8 as total,
       ${INVOICE_BALANCE('iv')}::float8 as balance,
       coalesce((select json_agg(json_build_object('id', c.id, 'number', c.number, 'mode', c.mode, 'amount', a.amount::float8,
           'at', c.created_at) order by c.id)
         from collection_allocations a join collections c on c.id = a.collection_id where a.invoice_id = iv.id), '[]') as payments,
       coalesce((select sum(c.discount) from collections c where c.invoice_id = iv.id and c.cancelled_at is null), 0)::float8 as discount,
       coalesce((select sum(c.wht) from collections c where c.invoice_id = iv.id and c.cancelled_at is null), 0)::float8 as wht,
       iv.created_at as "createdAt", cb.name as "createdBy", s.name as "salesmanName",
       lp.number as "planNumber", lp.delivery_note_no as "deliveryNoteNo", coalesce(v.plate, tv.plate) as "vehiclePlate",
       tr.id as "tripId", tr.number as "tripNumber", ts.stop as "tripStop",
       coalesce((select d.confirmed_at from deliveries d where d.plan_id = iv.plan_id and d.customer_id = iv.customer_id), ts.sold_at) as "deliveredAt",
       json_build_object('id', c.id, 'name', c.name, 'address', c.address, 'city', c.city, 'area', c.area, 'regNo', c.reg_no,
         'tpin', c.tpin) as customer,
       json_build_object('code', co.code, 'name', co.name, 'address', co.address, 'phone', co.phone, 'email', co.email,
         'tpin', co.tpin) as company,
       coalesce((select json_agg(json_build_object('id', r.id, 'number', r.number, 'kind', r.kind, 'reason', r.reason,
           'total', r.total::float8, 'booked', r.booked_at is not null, 'goods', r.goods, 'at', r.created_at) order by r.id)
         from sales_returns r where r.invoice_id = iv.id), '[]') as returns,
       coalesce((select json_agg(json_build_object('id', n.id, 'number', n.number, 'kind', n.kind, 'reason', n.reason,
           'amount', n.amount::float8, 'owesChange', (${NOTE_SIGN('n')} * n.amount)::float8, 'theirNumber', n.their_number,
           'at', n.created_at) order by n.id)
         from invoice_notes n where n.invoice_id = iv.id), '[]') as notes,
       coalesce((select json_agg(json_build_object('lineId', il.id, 'productId', p.id, 'code', p.code, 'brand', p.brand, 'name', p.name,
           'packSize', p.pack_size, 'piecesPerCarton', p.pieces_per_carton, 'pieces', il.pieces, 'pricePerCarton', il.price_per_carton,
           'amount', il.amount::float8, 'vatExempt', il.vat_exempt, 'orderNumber', o.number,
           'delivered', case when il.plan_item_id is null then il.pieces else pi.delivered end,
           'returned', coalesce((select sum(rl.pieces) from sales_return_lines rl join sales_returns rr on rr.id = rl.return_id
             where rl.invoice_line_id = il.id and rr.kind = 'after'), 0)) order by il.id)
         from invoice_lines il join products p on p.id = il.product_id left join order_lines l on l.id = il.order_line_id
           left join orders o on o.id = l.order_id left join loading_plan_items pi on pi.id = il.plan_item_id
         where il.invoice_id = iv.id), '[]') as lines
     from invoices iv join customers c on c.id = iv.customer_id join companies co on co.code = iv.company_code
       join users cb on cb.id = iv.created_by left join users s on s.id = iv.salesman_id
       left join loading_plans lp on lp.id = iv.plan_id left join vehicles v on v.id = lp.vehicle_id
       left join trip_sales ts on ts.id = iv.trip_sale_id left join trips tr on tr.id = ts.trip_id left join vehicles tv on tv.id = tr.vehicle_id
     where iv.id = $3 and ${visibleWhere(user)}`,
    [user.companies, user.id, invoiceId],
  );
  const iv = rows[0];
  if (!iv) return null;
  return {
    ...iv,
    // The most that can still come back after delivery; null until the drop is confirmed.
    lines: (iv.lines as { pieces: number; piecesPerCarton: number; delivered: number | null; returned: number }[]).map((l) => ({
      ...l,
      quantity: qtyText(l.pieces, l.piecesPerCarton),
      canReturn: l.delivered === null ? null : l.delivered - l.returned,
    })),
    canAdjust: (ADJUSTERS as readonly string[]).includes(user.role),
  };
}

export function invoiceRoutes(app: FastifyInstance, db: Db, brandingDir: string) {
  const auth = requireAuth(db);
  const makers = requireRole(...INVOICE_MAKERS);
  const viewers = requireRole(...INVOICE_VIEWERS);

  // The sales team makes the invoices once the vehicle is loaded, before it delivers (client, 16 Oct): one per customer,
  // company, LPO and invoice type, for what actually went on the vehicle, at the prices typed on the order. Numbered per company.
  // Only the user's own companies are invoiced; another company's manager makes theirs from the same plan.
  app.post('/loading-plans/:id/invoices', { onRequest: auth, preHandler: makers }, async (request, reply) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const made = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status === 'planned') throw new HttpError(409, 'not_loaded', 'Load the vehicle first. Invoices are made for what went on it.');
      if (plan.status === 'done') throw new HttpError(409, 'plan_done', 'This vehicle has finished its deliveries.');
      const { rows: items } = await client.query<LoadedItem>(
        `select i.id as "itemId", l.id as "lineId", o.id as "orderId", o.number as "orderNumber", o.customer_id as "customerId",
           o.invoice_type as "invoiceType", o.salesman_id as "salesmanId", p.company_code as "companyCode",
           coalesce(oc.lpo_no, '') as "lpoNo", p.id as "productId", p.pieces_per_carton as "piecesPerCarton", p.vat_exempt as "vatExempt",
           l.price_per_carton as "pricePerCarton", i.loaded
         from loading_plan_items i join order_lines l on l.id = i.order_line_id join orders o on o.id = l.order_id
           join products p on p.id = l.product_id
           left join order_companies oc on oc.order_id = o.id and oc.company_code = p.company_code
         where i.plan_id = $1 and i.loaded > 0 and p.company_code = any($2)
           and not exists (select 1 from invoice_lines il where il.plan_item_id = i.id)
         order by o.customer_id, p.company_code, o.id, l.id`,
        [plan.id, user.companies],
      );
      if (items.length === 0) throw new HttpError(409, 'already_invoiced', 'The invoices for this vehicle are already made.');

      const groups = new Map<string, LoadedItem[]>();
      for (const it of items) {
        const key = JSON.stringify([it.customerId, it.companyCode, it.lpoNo, it.invoiceType, it.salesmanId]);
        groups.set(key, [...(groups.get(key) ?? []), it]);
      }
      const invoices: { id: number; number: string; total: number }[] = [];
      for (const its of groups.values()) {
        const first = its[0]!;
        const { rows: terms } = await client.query<{ days: number; vatRate: number }>(
          `select case when c.cash_only then 0 else coalesce(cc.payment_terms_days, 0) end as days,
             coalesce(co.vat_rate, ${DEFAULT_VAT_RATE})::float8 as "vatRate"
           from customers c join companies co on co.code = $2
             left join customer_companies cc on cc.customer_id = c.id and cc.company_code = co.code
           where c.id = $1`,
          [first.customerId, first.companyCode],
        );
        const vatRate = first.invoiceType === 'vat' ? terms[0]!.vatRate : 0;
        const amounts = its.map((it) => lineAmount(it.loaded, it.pricePerCarton, it.piecesPerCarton));
        const subtotal = amounts.reduce((t, a) => t + a, 0);
        const vat = vatOn(its.map((it, i) => ({ amount: amounts[i]!, vatExempt: it.vatExempt })), vatRate);
        const number = await nextNumber(client, first.companyCode, 'INV');
        const { rows } = await client.query<{ id: number }>(
          `insert into invoices (number, company_code, customer_id, invoice_type, plan_id, lpo_no, invoice_date, due_date,
             subtotal, vat_rate, vat, total, salesman_id, created_by)
           values ($1, $2, $3, $4, $5, $6, ${TODAY}, ${TODAY} + $7::int, $8, $9, $10, $11, $12, $13) returning id`,
          [number, first.companyCode, first.customerId, first.invoiceType, plan.id, first.lpoNo, terms[0]!.days,
            subtotal, vatRate, vat, subtotal + vat, first.salesmanId, user.id],
        );
        const invoiceId = rows[0]!.id;
        for (const [i, it] of its.entries()) {
          await client.query(
            `insert into invoice_lines (invoice_id, order_line_id, plan_item_id, product_id, pieces, price_per_carton, amount, vat_exempt)
             values ($1, $2, $3, $4, $5, $6, $7, $8)`,
            [invoiceId, it.lineId, it.itemId, it.productId, it.loaded, it.pricePerCarton, amounts[i], it.vatExempt],
          );
        }
        for (const orderId of new Set(its.map((it) => it.orderId))) {
          await addEvent(client, orderId, user.id, 'Invoice made', `${number} · MWK ${(subtotal + vat).toLocaleString('en-US')}`);
        }
        // Money the customer paid on account goes on the new invoice.
        await applyOnAccount(client, first.customerId, first.companyCode);
        invoices.push({ id: invoiceId, number, total: subtotal + vat });
      }
      return { number: plan.number, invoices };
    });
    await audit(db, { userId: user.id, action: 'invoices_made', details: { planId, number: made.number, invoices: made.invoices.map((i) => i.number) } });
    return reply.code(201).send({ ...(await planDetail(db, user, planId)), made: made.invoices });
  });

  // The invoice register, newest first.
  app.get('/invoices', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(listQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = visibleWhere(user);
    const add = (sql: string, value: unknown) => {
      params.push(value);
      where += ` and ${sql.replace('?', `$${params.length}`)}`;
    };
    if (query.company) add('iv.company_code = ?', query.company);
    if (query.customerId) add('iv.customer_id = ?', query.customerId);
    if (query.planId) add('iv.plan_id = ?', query.planId);
    if (query.type) add('iv.invoice_type = ?', query.type);
    if (query.from) add('iv.invoice_date >= ?::date', query.from);
    if (query.to) add('iv.invoice_date <= ?::date', query.to);
    if (query.q) {
      params.push(`%${query.q}%`);
      const n = params.length;
      where += ` and (iv.number ilike $${n} or c.name ilike $${n} or iv.lpo_no ilike $${n})`;
    }
    const { rows } = await db.query(
      `select iv.id, iv.number, iv.company_code as "companyCode", iv.invoice_type as "invoiceType", iv.customer_id as "customerId",
         c.name as "customerName", c.area, iv.lpo_no as "lpoNo", to_char(iv.invoice_date, 'YYYY-MM-DD') as "invoiceDate",
         to_char(iv.due_date, 'YYYY-MM-DD') as "dueDate", iv.subtotal::float8 as subtotal, iv.vat::float8 as vat, iv.total::float8 as total,
         ${INVOICE_BALANCE('iv')}::float8 as balance, s.name as "salesmanName", coalesce(v.plate, tv.plate) as "vehiclePlate",
         tr.number as "tripNumber",
         iv.trip_sale_id is not null or exists (select 1 from deliveries d where d.plan_id = iv.plan_id and d.customer_id = iv.customer_id) as delivered
       from invoices iv join customers c on c.id = iv.customer_id left join users s on s.id = iv.salesman_id
         left join loading_plans lp on lp.id = iv.plan_id left join vehicles v on v.id = lp.vehicle_id
         left join trip_sales ts on ts.id = iv.trip_sale_id left join trips tr on tr.id = ts.trip_id left join vehicles tv on tv.id = tr.vehicle_id
       where ${where} order by iv.id desc limit 500`,
      params,
    );
    return rows;
  });

  app.get('/invoices/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const invoice = await invoiceDetail(db, request.user, id);
    if (!invoice) throw notFound('invoice');
    return invoice;
  });

  // The invoice as a PDF, to print or share by WhatsApp.
  app.get('/invoices/:id/pdf', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id } = parse(idParams, request.params);
    const iv = await invoiceDetail(db, request.user, id);
    if (!iv) throw notFound('invoice');
    const pdf = await invoicePdf({
      number: iv.number,
      invoiceType: iv.invoiceType,
      invoiceDate: iv.invoiceDate,
      dueDate: iv.dueDate,
      lpoNo: iv.lpoNo,
      vehiclePlate: iv.vehiclePlate,
      deliveryNoteNo: iv.deliveryNoteNo,
      company: { ...iv.company, logo: logoPath(brandingDir, iv.companyCode) },
      customer: iv.customer,
      lines: iv.lines.map((l: { brand: string; name: string; packSize: string; quantity: string; pricePerCarton: number; amount: number; vatExempt: boolean }) => ({
        // No VAT is charged on this line, so a VAT invoice says so.
        description: `${l.brand} ${l.name} ${l.packSize}${iv.invoiceType === 'vat' && l.vatExempt ? ' (VAT exempt)' : ''}`,
        quantity: l.quantity,
        unitPrice: l.pricePerCarton,
        amount: l.amount,
      })),
      subtotal: iv.subtotal,
      vatRate: iv.vatRate,
      vat: iv.vat,
      total: iv.total,
      salesman: iv.salesmanName,
    });
    return reply.type('application/pdf').header('content-disposition', `inline; filename="${iv.number}.pdf"`).send(pdf);
  });
}
