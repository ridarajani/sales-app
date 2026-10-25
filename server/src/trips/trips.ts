import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import { addAirtelPayment } from '../cash/airtel.js';
import { CUSTOMER_OWES } from '../cash/balances.js';
import { applyOnAccount } from '../cash/collections.js';
import type { Db } from '../db/pool.js';
import { readImage, saveImage } from '../files.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { LOADERS } from '../loading/loading.js';
import { nextNumber, nextSharedNumber } from '../numbering.js';
import { DEFAULT_VAT_RATE, INVOICE_TYPES, lineAmount, MIN_SHELF_DAYS, qtyText, vatOn } from '../orders/orders.js';
import { inTransaction, split } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// Minibus routes and upcountry trips: plan, load, sell at each stop with an invoice, spend from the float, close.
export const TRIP_PLANNERS = ['admin', 'management', 'sales_manager'] as const;
export const TRIP_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'warehouse', 'driver', 'cashier', 'accounts'] as const;
// Who may lead a trip: they sell, hold the cash and close it.
const LEADER_ROLES = ['salesman', 'sales_manager'];
// Besides the leader, these may close a trip, for example when the leader's phone is lost.
const CLOSERS = ['admin', 'sales_manager'];
export const TRIP_EXPENSE_KINDS = ['Fuel', 'Lodging', 'Food allowance', 'Loading labour', 'Vehicle repair', 'Other'] as const;
const IMAGE_MAX = 8 * 1024 * 1024;
const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;

const id = z.number().int().positive().max(INT4_MAX);
const quantity = {
  cartons: z.number().int().min(0, 'Cartons cannot be below 0.').max(1_000_000).default(0),
  pieces: z.number().int().min(0, 'Pieces cannot be below 0.').max(INT4_MAX).default(0),
};
const kwacha = (what: string) =>
  z.number(`Please type the ${what}.`).int('Amounts are whole kwacha.').min(0, `The ${what} cannot be below 0.`).max(1_000_000_000);
const productLines = z.array(z.object({ productId: id, ...quantity })).max(500);
// Every sale and expense made on the phone carries its own id, so sending it again (a retry, or a sync after
// being offline) never saves it twice.
const requestId = z.string('This phone app is too old. Please update it.').trim().min(1).max(64);

const planBody = z.object({
  kind: z.enum(['minibus', 'upcountry'], 'Please pick minibus route or upcountry trip.'),
  facilityCode: z.string(),
  companyCode: z.string().optional(),
  tripDate: z.iso.date('Please pick the date.'),
  route: z.string('Please type the route.').trim().min(1, 'Please type the route.').max(200),
  stops: z.array(z.string().trim().min(1).max(100)).max(40).default([]),
  vehicleId: id.nullish(),
  driverId: id.nullish(),
  leaderId: id,
  staff: z.string().trim().max(300).default(''),
  note: z.string().trim().max(1000).default(''),
  float: kwacha('float').default(0),
  lines: productLines.min(1, 'Add at least one product to load.'),
});
const loadBody = z.object({ vehicleId: id.nullish(), lines: productLines.default([]) });
const saleBody = z.object({
  requestId,
  customerId: id,
  stop: z.string().trim().max(100).default(''),
  invoiceType: z.enum(INVOICE_TYPES).optional(),
  payment: z.enum(['cash', 'airtel', 'credit'], 'Please pick how the customer paid.'),
  reference: z.string().trim().max(100).default(''),
  receivedBy: z.string().trim().max(100).default(''),
  // PNG or JPEG as base64.
  signature: z.string().max(4_000_000).optional(),
  proof: z.string().max(12_000_000).optional(),
  // When the sale was made on the phone; it may reach the server later.
  soldAt: z.iso.datetime({ offset: true }).optional(),
  lines: z
    .array(
      z.object({
        productId: id,
        ...quantity,
        pricePerCarton: z.number('Please type the price per carton.').int('Prices are whole kwacha.').positive('Please type the price per carton.').max(INT4_MAX),
      }),
    )
    .max(200),
});
const expenseBody = z.object({
  requestId,
  companyCode: z.string().optional(),
  amount: kwacha('amount').positive('Please type the amount.'),
  description: z.string('Please say what it was for.').trim().min(1, 'Please say what it was for.').max(300),
  photo: z.string().max(12_000_000).optional(),
});
const closeBody = z.object({
  lines: productLines,
  counted: kwacha('cash you counted'),
  note: z.string().trim().max(1000).default(''),
});
const syncBody = z.object({ sales: z.array(z.unknown()).max(300).default([]), expenses: z.array(z.unknown()).max(300).default([]) });
const listQuery = z.object({
  status: z.enum(['planned', 'out', 'closed']).optional(),
  kind: z.enum(['minibus', 'upcountry']).optional(),
  facility: z.string().optional(),
});

const totalPieces = (q: { cartons: number; pieces: number }, piecesPerCarton: number) => q.cartons * piecesPerCarton + q.pieces;

// Which trips a user may see. $1 = their companies, $2 = their id; every branch names both.
function visibleWhere(user: AuthUser) {
  if (user.role === 'salesman') return `$1::text[] is not null and t.leader_id = $2::int`;
  if (user.role === 'driver') return `$1::text[] is not null and t.driver_id = $2::int and t.status <> 'planned'`;
  return `$2::int is not null and exists (select 1 from companies x where x.facility_code = t.facility_code and x.code = any($1::text[]))`;
}

type TripHead = {
  id: number;
  number: string;
  kind: 'minibus' | 'upcountry';
  facilityCode: string;
  companyCode: string;
  status: 'planned' | 'out' | 'closed';
  vehicleId: number | null;
  leaderId: number;
  floatAmount: number;
};

async function lockTrip(client: PoolClient, user: AuthUser, tripId: number): Promise<TripHead> {
  const { rows } = await client.query<TripHead>(
    `select t.id, t.number, t.kind, t.facility_code as "facilityCode", t.company_code as "companyCode", t.status,
       t.vehicle_id as "vehicleId", t.leader_id as "leaderId", t.float_amount::float8 as "floatAmount"
     from trips t where t.id = $3 and ${visibleWhere(user)} for update of t`,
    [user.companies, user.id, tripId],
  );
  if (!rows[0]) throw notFound('trip');
  return rows[0];
}

const notOut = (trip: TripHead) =>
  trip.status === 'planned'
    ? new HttpError(409, 'not_loaded', 'This vehicle has not been loaded yet.')
    : new HttpError(409, 'trip_closed', `Trip ${trip.number} is already closed.`);

function mustLead(user: AuthUser, trip: TripHead) {
  if (trip.leaderId !== user.id) throw new HttpError(403, 'not_allowed', 'Only the trip leader can record sales and expenses on this trip.');
}

// Companies of a facility, in their usual order.
async function facilityCompanies(client: PoolClient | Db, facilityCode: string) {
  const { rows } = await client.query<{ code: string }>('select code from companies where facility_code = $1 order by sort_order', [facilityCode]);
  return rows.map((r) => r.code);
}

// Cash the leader should bring back: cash sales, plus the float, less expenses paid in cash.
const CASH_SALES = (t: string) => `coalesce((select sum(c.amount) from collections c join trip_sales s on s.id = c.trip_sale_id
  where s.trip_id = ${t}.id and c.mode = 'cash' and c.cancelled_at is null), 0)`;
const CASH_SPENT = (t: string) => `coalesce((select sum(e.amount) from expenses e where e.trip_id = ${t}.id and e.mode = 'cash'), 0)`;

export async function tripDetail(db: Db | PoolClient, user: AuthUser, tripId: number) {
  const { rows } = await db.query(
    `select t.id, t.number, t.kind, t.facility_code as "facilityCode", f.name as "facilityName", t.company_code as "companyCode",
       to_char(t.trip_date, 'YYYY-MM-DD') as "tripDate", t.route, t.stops, t.vehicle_id as "vehicleId", v.plate as "vehiclePlate",
       t.driver_id as "driverId", d.name as "driverName", t.leader_id as "leaderId", l.name as "leaderName", t.staff, t.note,
       t.float_amount::float8 as float, t.float_custody as "floatCustody", t.status, t.gate_pass_no as "gatePassNo",
       t.loaded_at as "loadedAt", lb.name as "loadedBy", t.closed_at as "closedAt", cb.name as "closedBy", t.close_note as "closeNote",
       ${CASH_SALES('t')}::float8 as "cashSales", ${CASH_SPENT('t')}::float8 as "cashExpenses",
       t.cash_expected::float8 as "cashExpected", t.cash_counted::float8 as "cashCounted", t.cash_difference::float8 as "cashDifference",
       mb.name as "createdBy", t.created_at as "createdAt"
     from trips t join facilities f on f.code = t.facility_code join users l on l.id = t.leader_id join users mb on mb.id = t.created_by
       left join vehicles v on v.id = t.vehicle_id left join users d on d.id = t.driver_id
       left join users lb on lb.id = t.loaded_by left join users cb on cb.id = t.closed_by
     where t.id = $3 and ${visibleWhere(user)}`,
    [user.companies, user.id, tripId],
  );
  const trip = rows[0];
  if (!trip) return null;

  const { rows: lines } = await db.query<{
    productId: number;
    piecesPerCarton: number;
    planned: number;
    loaded: number;
    sold: number;
    returned: number | null;
    missing: number | null;
  }>(
    `select p.id as "productId", p.code, p.brand, p.name, p.pack_size as "packSize", p.company_code as "companyCode",
       p.pieces_per_carton as "piecesPerCarton", tl.planned, tl.loaded, tl.sold, tl.returned, tl.missing,
       coalesce((select json_agg(json_build_object('batchId', b.id, 'batchNo', b.batch_no, 'expiryDate', to_char(b.expiry_date, 'YYYY-MM-DD'),
           'pieces', tb.pieces, 'sold', tb.sold) order by b.expiry_date, b.id)
         from trip_line_batches tb join stock_batches b on b.id = tb.batch_id where tb.line_id = tl.id), '[]') as batches
     from trip_lines tl join products p on p.id = tl.product_id join companies co on co.code = p.company_code
     where tl.trip_id = $1 order by co.sort_order, p.brand, p.name, p.pack_size`,
    [tripId],
  );
  const { rows: sales } = await db.query(
    `select s.id, s.customer_id as "customerId", c.name as "customerName", s.stop, s.payment, s.total::float8 as total, s.sold_at as "soldAt",
       s.received_by as "receivedBy", u.name as "soldBy",
       coalesce((select json_agg(json_build_object('id', iv.id, 'number', iv.number, 'companyCode', iv.company_code,
           'invoiceType', iv.invoice_type, 'total', iv.total::float8) order by iv.id)
         from invoices iv where iv.trip_sale_id = s.id and iv.company_code = any($2)), '[]') as invoices
     from trip_sales s join customers c on c.id = s.customer_id join users u on u.id = s.created_by
     where s.trip_id = $1 order by s.sold_at, s.id`,
    [tripId, user.companies],
  );
  const { rows: expenses } = await db.query(
    `select e.id, e.number, e.company_code as "companyCode", e.amount::float8 as amount, e.description, e.mode,
       e.photo_file is not null as "hasPhoto", e.created_at as "createdAt"
     from expenses e where e.trip_id = $1 order by e.id`,
    [tripId],
  );

  const isLeader = trip.leaderId === user.id;
  const out = trip.status === 'out';
  const detail = {
    ...trip,
    lines: lines.map((l) => {
      const onVehicle = trip.status === 'closed' ? 0 : l.loaded - l.sold;
      return {
        ...l,
        plannedQty: split(l.planned, l.piecesPerCarton),
        loadedQty: split(l.loaded, l.piecesPerCarton),
        soldQty: split(l.sold, l.piecesPerCarton),
        onVehicle,
        onVehicleQty: split(onVehicle, l.piecesPerCarton),
        quantity: qtyText(trip.status === 'planned' ? l.planned : l.loaded, l.piecesPerCarton),
      };
    }),
    sales,
    expenses,
    salesTotal: sales.reduce((t, s) => t + s.total, 0),
    expensesTotal: expenses.reduce((t, e) => t + e.amount, 0),
    // While the trip is out: what the leader should have in hand now.
    cashNow: trip.cashSales + trip.float - trip.cashExpenses,
    canEdit: (TRIP_PLANNERS as readonly string[]).includes(user.role) && trip.status === 'planned',
    canLoad: (LOADERS as readonly string[]).includes(user.role) && trip.status === 'planned',
    canSell: isLeader && out,
    canClose: out && (isLeader || CLOSERS.includes(user.role)),
  };
  // The warehouse loads and sees what came back, never the money.
  if (user.role === 'warehouse') {
    const { sales: _s, expenses: _e, salesTotal: _t, expensesTotal: _x, cashNow: _n, float: _f, cashSales: _c, cashExpenses: _ce,
      cashExpected: _ex, cashCounted: _cc, cashDifference: _cd, ...rest } = detail;
    return rest;
  }
  return detail;
}

async function checkPlan(client: PoolClient, user: AuthUser, body: z.infer<typeof planBody>) {
  const companies = await facilityCompanies(client, body.facilityCode);
  if (companies.length === 0) throw invalid('That warehouse is not known.');
  const mine = companies.filter((c) => user.companies.includes(c));
  if (mine.length === 0) throw notAllowed();
  const companyCode = body.companyCode ?? mine[0]!;
  if (!mine.includes(companyCode)) throw invalid('Pick one of this warehouse’s companies for the float and expenses.');
  if (body.vehicleId) {
    const { rows } = await client.query(
      'select 1 from vehicles where id = $1 and active and (facility_code is null or facility_code = $2)',
      [body.vehicleId, body.facilityCode],
    );
    if (!rows[0]) throw invalid('That vehicle cannot be used for this warehouse.');
  }
  if (body.driverId) {
    const { rows } = await client.query("select 1 from users where id = $1 and role = 'driver' and active", [body.driverId]);
    if (!rows[0]) throw invalid('Please pick a driver.');
  }
  const { rows: leader } = await client.query(
    `select 1 from users u where u.id = $1 and u.active and u.role = any($2)
       and exists (select 1 from user_companies uc where uc.user_id = u.id and uc.company_code = any($3))`,
    [body.leaderId, LEADER_ROLES, companies],
  );
  if (!leader[0]) throw invalid('Please pick the salesman or sales manager who leads the trip.');
  const ids = body.lines.map((l) => l.productId);
  if (new Set(ids).size !== ids.length) throw invalid('A product is on the list twice.');
  const { rows: products } = await client.query<{ id: number; piecesPerCarton: number }>(
    `select id, pieces_per_carton as "piecesPerCarton" from products where id = any($1) and active and company_code = any($2)`,
    [ids, mine],
  );
  const lines = body.lines.map((l, i) => {
    const p = products.find((x) => x.id === l.productId);
    if (!p) throw invalid(`Line ${i + 1}: that product is not one of this warehouse’s products.`);
    const pieces = totalPieces(l, p.piecesPerCarton);
    if (pieces === 0) throw invalid(`Line ${i + 1}: please type how much to load.`);
    return { productId: l.productId, pieces };
  });
  return { companyCode, lines, stops: [...new Set(body.stops)] };
}

async function saveLines(client: PoolClient, tripId: number, lines: { productId: number; pieces: number }[]) {
  await client.query('delete from trip_lines where trip_id = $1', [tripId]);
  for (const l of lines) {
    await client.query('insert into trip_lines (trip_id, product_id, planned) values ($1, $2, $3)', [tripId, l.productId, l.pieces]);
  }
}

type SaleInput = z.infer<typeof saleBody>;
type ExpenseInput = z.infer<typeof expenseBody>;
type Saved = { status: 200 | 201; body: unknown };

async function saleDetail(db: Db | PoolClient, saleId: number, companies: string[]) {
  const { rows } = await db.query(
    `select s.id, s.request_id as "requestId", s.trip_id as "tripId", t.number as "tripNumber", s.customer_id as "customerId",
       c.name as "customerName", s.stop, s.payment, s.reference, s.total::float8 as total, s.received_by as "receivedBy",
       s.sold_at as "soldAt", s.created_at as "savedAt",
       coalesce((select json_agg(json_build_object('id', iv.id, 'number', iv.number, 'companyCode', iv.company_code,
           'invoiceType', iv.invoice_type, 'total', iv.total::float8) order by iv.id)
         from invoices iv where iv.trip_sale_id = s.id and iv.company_code = any($2)), '[]') as invoices,
       coalesce((select json_agg(json_build_object('id', x.id, 'number', x.number, 'mode', x.mode, 'amount', x.amount::float8) order by x.id)
         from collections x where x.trip_sale_id = s.id and x.company_code = any($2)), '[]') as payments
     from trip_sales s join trips t on t.id = s.trip_id join customers c on c.id = s.customer_id where s.id = $1`,
    [saleId, companies],
  );
  return rows[0];
}

const duplicate = (err: unknown) => (err as { code?: string }).code === '23505';

// One sale at a stop: one invoice per company, the stock leaves the vehicle, and cash or Airtel Money is recorded
// as a payment of each invoice. On account, money the customer paid before goes on the new invoices.
async function recordSale(db: Db, user: AuthUser, tripId: number, body: SaleInput, uploadDir: string): Promise<Saved> {
  const earlier = async () => {
    const { rows } = await db.query<{ id: number }>('select id from trip_sales where created_by = $1 and request_id = $2', [user.id, body.requestId]);
    return rows[0] ? { status: 200 as const, body: await saleDetail(db, rows[0].id, user.companies) } : null;
  };
  const repeat = await earlier();
  if (repeat) return repeat;
  const wanted = body.lines.filter((l) => l.cartons > 0 || l.pieces > 0);
  if (wanted.length === 0) throw invalid('Please type the cartons sold.');
  if (new Set(wanted.map((l) => l.productId)).size !== wanted.length) throw invalid('A product is on the sale twice.');
  // The customer signs for every delivery and payment (client, 17 Oct).
  if (!body.signature) throw invalid('Please ask the customer to sign.');
  if (!body.receivedBy) throw invalid('Please type the name of the person who received the goods.');
  if (body.payment === 'airtel' && !body.reference) throw invalid('Please type the Airtel Money reference.');
  if (body.payment === 'airtel' && !body.proof) throw invalid('Please add a photo of the Airtel Money message.');
  const signatureFile = await saveImage(uploadDir, body.signature, IMAGE_MAX, 'signature');
  const proofFile = body.proof ? await saveImage(uploadDir, body.proof, IMAGE_MAX, 'photo') : null;

  try {
    const saleId = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'out') throw notOut(trip);
      mustLead(user, trip);
      const { rows: customers } = await client.query<{ id: number; name: string; cashOnly: boolean; lastInvoiceType: 'vat' | 'plain' | null }>(
        `select id, name, cash_only as "cashOnly", last_invoice_type as "lastInvoiceType" from customers where id = $1 and active`,
        [body.customerId],
      );
      const customer = customers[0];
      if (!customer) throw notFound('customer');
      if (customer.cashOnly && body.payment === 'credit') throw invalid(`${customer.name} pays cash only. Please take cash or Airtel Money.`);
      const invoiceType = body.invoiceType ?? customer.lastInvoiceType ?? 'vat';

      const { rows: van } = await client.query<{
        lineId: number;
        productId: number;
        companyCode: string;
        piecesPerCarton: number;
        vatExempt: boolean;
        label: string;
        left: number;
      }>(
        `select tl.id as "lineId", p.id as "productId", p.company_code as "companyCode", p.pieces_per_carton as "piecesPerCarton",
           p.vat_exempt as "vatExempt", p.brand || ' ' || p.name || ' ' || p.pack_size as label, tl.loaded - tl.sold as left
         from trip_lines tl join products p on p.id = tl.product_id where tl.trip_id = $1 order by tl.id for update of tl`,
        [trip.id],
      );
      const lines = wanted.map((w) => {
        const line = van.find((v) => v.productId === w.productId);
        if (!line) throw invalid('That product is not on this vehicle.');
        const pieces = totalPieces(w, line.piecesPerCarton);
        if (pieces > line.left) {
          throw new HttpError(409, 'not_on_vehicle',
            line.left > 0 ? `${line.label}: only ${qtyText(line.left, line.piecesPerCarton)} is left on the vehicle.` : `${line.label}: none is left on the vehicle.`);
        }
        return { ...line, pieces, pricePerCarton: w.pricePerCarton, amount: lineAmount(pieces, w.pricePerCarton, line.piecesPerCarton) };
      });

      const { rows: sale } = await client.query<{ id: number; soldAt: string; day: string }>(
        `insert into trip_sales (trip_id, customer_id, stop, payment, reference, total, received_by, signature_file, proof_file, sold_at,
           created_by, request_id)
         select $1, $2, $3, $4, $5, 0, $6, $7, $8, least(greatest(coalesce($9::timestamptz, now()), t.loaded_at), now()), $10, $11
         from trips t where t.id = $1
         returning id, sold_at as "soldAt", to_char((sold_at at time zone 'Africa/Blantyre')::date, 'YYYY-MM-DD') as day`,
        [trip.id, customer.id, body.stop, body.payment, body.reference, body.receivedBy, signatureFile, proofFile, body.soldAt ?? null,
          user.id, body.requestId],
      );
      const saleId = sale[0]!.id;
      const day = sale[0]!.day;

      let total = 0;
      const companies = [...new Set(lines.map((l) => l.companyCode))].sort();
      for (const companyCode of companies) {
        const its = lines.filter((l) => l.companyCode === companyCode);
        const { rows: terms } = await client.query<{ linked: boolean; days: number; vatRate: number }>(
          `select cc.customer_id is not null as linked, coalesce(cc.payment_terms_days, 0) as days,
             coalesce(co.vat_rate, ${DEFAULT_VAT_RATE})::float8 as "vatRate"
           from companies co left join customer_companies cc on cc.customer_id = $1 and cc.company_code = co.code where co.code = $2`,
          [customer.id, companyCode],
        );
        if (!terms[0]!.linked) throw invalid(`${customer.name} does not buy from ${companyCode}. Please add the company to the customer first.`);
        const vatRate = invoiceType === 'vat' ? terms[0]!.vatRate : 0;
        const subtotal = its.reduce((t, l) => t + l.amount, 0);
        const vat = vatOn(its, vatRate);
        const days = body.payment === 'credit' ? terms[0]!.days : 0;
        const number = await nextNumber(client, companyCode, 'INV');
        const { rows: inv } = await client.query<{ id: number }>(
          `insert into invoices (number, company_code, customer_id, invoice_type, trip_sale_id, invoice_date, due_date,
             subtotal, vat_rate, vat, total, salesman_id, created_by)
           values ($1, $2, $3, $4, $5, $6::date, $6::date + $7::int, $8, $9, $10, $11, $12, $12) returning id`,
          [number, companyCode, customer.id, invoiceType, saleId, day, days, subtotal, vatRate, vat, subtotal + vat, user.id],
        );
        const invoiceId = inv[0]!.id;
        for (const l of its) {
          await client.query(
            `insert into invoice_lines (invoice_id, product_id, pieces, price_per_carton, amount, vat_exempt) values ($1, $2, $3, $4, $5, $6)`,
            [invoiceId, l.productId, l.pieces, l.pricePerCarton, l.amount, l.vatExempt],
          );
          await takeFromVehicle(client, trip.id, saleId, l.lineId, l.pieces, number, user.id);
        }
        total += subtotal + vat;

        if (body.payment === 'credit') {
          await applyOnAccount(client, customer.id, companyCode);
        } else if (subtotal + vat > 0) {
          const receipt = await nextNumber(client, companyCode, 'RCT');
          const { rows: c } = await client.query<{ id: number }>(
            `insert into collections (number, company_code, customer_id, invoice_id, mode, amount, reference, proof_file, signature_file,
               received_by, collected_by, custody, trip_sale_id, created_at)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) returning id`,
            [receipt, companyCode, customer.id, invoiceId, body.payment, subtotal + vat, body.reference, proofFile, signatureFile,
              body.receivedBy, user.id, body.payment === 'cash' ? 'with' : 'na', saleId, sale[0]!.soldAt],
          );
          await client.query('insert into collection_allocations (collection_id, invoice_id, amount) values ($1, $2, $3)', [
            c[0]!.id,
            invoiceId,
            subtotal + vat,
          ]);
          if (body.payment === 'airtel') {
            await addAirtelPayment(client, {
              companyCode,
              amount: subtotal + vat,
              reference: body.reference,
              description: `${customer.name} · ${receipt} · trip ${trip.number}`,
              collectionId: c[0]!.id,
              userId: user.id,
            });
          }
        }
      }
      await client.query('update trip_sales set total = $2 where id = $1', [saleId, total]);
      if (customer.lastInvoiceType !== invoiceType) {
        await client.query('update customers set last_invoice_type = $2 where id = $1', [customer.id, invoiceType]);
      }
      await client.query('update trips set updated_at = now() where id = $1', [trip.id]);
      return saleId;
    });
    const detail = await saleDetail(db, saleId, user.companies);
    await audit(db, {
      userId: user.id,
      action: 'trip_sale',
      details: { tripId, saleId, payment: body.payment, total: detail.total, invoices: detail.invoices.map((i: { number: string }) => i.number) },
    });
    return { status: 201, body: detail };
  } catch (err) {
    // Sent twice at the same moment: the second waits for the first and then finds it.
    const again = duplicate(err) ? await earlier() : null;
    if (again) return again;
    throw err;
  }
}

// Sold stock leaves the vehicle and the books, oldest expiry first.
async function takeFromVehicle(client: PoolClient, tripId: number, saleId: number, lineId: number, pieces: number, invoiceNo: string, userId: number) {
  const { rows: batches } = await client.query<{ batchId: number; left: number }>(
    `select tb.batch_id as "batchId", tb.pieces - tb.sold as left from trip_line_batches tb join stock_batches b on b.id = tb.batch_id
     where tb.line_id = $1 and tb.pieces > tb.sold order by b.expiry_date, b.id`,
    [lineId],
  );
  await client.query('select id from stock_batches where id = any($1) order by id for update', [batches.map((b) => b.batchId)]);
  let need = pieces;
  for (const b of batches) {
    if (need === 0) break;
    const n = Math.min(need, b.left);
    need -= n;
    await client.query('update trip_line_batches set sold = sold + $3 where line_id = $1 and batch_id = $2', [lineId, b.batchId, n]);
    await client.query('update stock_batches set on_hand = on_hand - $2, in_transit = in_transit - $2 where id = $1', [b.batchId, n]);
    await client.query(
      `insert into stock_movements (batch_id, change, kind, note, trip_id, trip_sale_id, user_id) values ($1, $2, 'delivered', $3, $4, $5, $6)`,
      [b.batchId, -n, invoiceNo, tripId, saleId, userId],
    );
  }
  if (need > 0) throw new HttpError(409, 'not_on_vehicle', 'That is more than is left on the vehicle.');
  await client.query('update trip_lines set sold = sold + $2 where id = $1', [lineId, pieces]);
}

const expenseSelect = `select e.id, e.request_id as "requestId", e.number, e.company_code as "companyCode", e.amount::float8 as amount,
   e.description, e.mode, e.photo_file is not null as "hasPhoto", e.trip_id as "tripId", e.created_at as "createdAt" from expenses e`;

// Money spent on the trip, in cash from the float. It comes off the cash the leader hands over.
async function recordExpense(db: Db, user: AuthUser, tripId: number, body: ExpenseInput, uploadDir: string): Promise<Saved> {
  const earlier = async () => {
    const { rows } = await db.query(`${expenseSelect} where e.spent_by = $1 and e.request_id = $2`, [user.id, body.requestId]);
    return rows[0] ? { status: 200 as const, body: rows[0] } : null;
  };
  const repeat = await earlier();
  if (repeat) return repeat;
  const photoFile = body.photo ? await saveImage(uploadDir, body.photo, IMAGE_MAX, 'photo') : null;
  try {
    const expenseId = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'out') throw notOut(trip);
      mustLead(user, trip);
      const companyCode = body.companyCode ?? trip.companyCode;
      if (!(await facilityCompanies(client, trip.facilityCode)).includes(companyCode) || !user.companies.includes(companyCode)) {
        throw invalid('Pick one of this trip’s companies.');
      }
      const number = await nextNumber(client, companyCode, 'EXP');
      const { rows } = await client.query<{ id: number }>(
        `insert into expenses (number, company_code, amount, description, mode, photo_file, spent_by, custody, request_id, trip_id)
         values ($1, $2, $3, $4, 'cash', $5, $6, 'with', $7, $8) returning id`,
        [number, companyCode, body.amount, `${body.description} · trip ${trip.number}`, photoFile, user.id, body.requestId, trip.id],
      );
      return rows[0]!.id;
    });
    const { rows } = await db.query(`${expenseSelect} where e.id = $1`, [expenseId]);
    await audit(db, { userId: user.id, action: 'trip_expense', details: { tripId, expenseId, number: rows[0].number, amount: body.amount } });
    return { status: 201, body: rows[0] };
  } catch (err) {
    const again = duplicate(err) ? await earlier() : null;
    if (again) return again;
    throw err;
  }
}

export function tripRoutes(app: FastifyInstance, db: Db, uploadDir: string) {
  const auth = requireAuth(db);
  const planners = requireRole(...TRIP_PLANNERS);
  const loaders = requireRole(...LOADERS);
  const viewers = requireRole(...TRIP_VIEWERS);
  const sellers = requireRole(...LEADER_ROLES);
  const closers = requireRole(...new Set([...LEADER_ROLES, ...CLOSERS]));

  app.get('/trips', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(listQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = visibleWhere(user);
    const add = (sql: string, value: unknown) => {
      params.push(value);
      where += ` and ${sql.replace('?', `$${params.length}`)}`;
    };
    if (query.status) add('t.status = ?', query.status);
    if (query.kind) add('t.kind = ?', query.kind);
    if (query.facility) add('t.facility_code = ?', query.facility);
    const { rows } = await db.query(
      `select t.id, t.number, t.kind, t.facility_code as "facilityCode", to_char(t.trip_date, 'YYYY-MM-DD') as "tripDate", t.route,
         t.status, v.plate as "vehiclePlate", l.name as "leaderName", t.staff,
         ctn.cartons, ctn."loosePieces", ctn."soldCartons",
         (select coalesce(sum(s.total), 0) from trip_sales s where s.trip_id = t.id)::float8 as "salesTotal",
         (select count(*) from trip_sales s where s.trip_id = t.id)::int as "salesCount"
       from trips t join users l on l.id = t.leader_id left join vehicles v on v.id = t.vehicle_id
         left join lateral (
           select coalesce(sum(x.pieces / x.ppc), 0)::int as cartons, coalesce(sum(x.pieces % x.ppc), 0)::int as "loosePieces",
             coalesce(sum(x.sold / x.ppc), 0)::int as "soldCartons"
           from (select p.pieces_per_carton as ppc, case when t.status = 'planned' then tl.planned else tl.loaded end as pieces, tl.sold
                 from trip_lines tl join products p on p.id = tl.product_id where tl.trip_id = t.id) x
         ) ctn on true
       where ${where}
       order by case t.status when 'out' then 0 when 'planned' then 1 else 2 end, t.trip_date desc, t.id desc limit 200`,
      params,
    );
    return user.role === 'warehouse' ? rows.map(({ salesTotal: _s, ...r }) => r) : rows;
  });

  app.get('/trips/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const trip = await tripDetail(db, request.user, tripId);
    if (!trip) throw notFound('trip');
    return trip;
  });

  // Everything the leader's phone keeps for selling offline: the trip, its customers with what they owe, and the
  // last price each customer paid for each product.
  app.get('/trips/:id/pack', { onRequest: auth, preHandler: sellers }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const user = request.user;
    const trip = await tripDetail(db, user, tripId);
    if (!trip) throw notFound('trip');
    const companies = (await facilityCompanies(db, trip.facilityCode)).filter((c) => user.companies.includes(c));
    const productIds = trip.lines.map((l: { productId: number }) => l.productId);
    const { rows: customers } = await db.query(
      `select c.id, c.name, c.area, c.channel, c.cash_only as "cashOnly", c.last_invoice_type as "lastInvoiceType",
         (select json_agg(json_build_object('code', cc.company_code, 'owes', ${CUSTOMER_OWES('c.id', 'cc.company_code')}::float8)
            order by cc.company_code)
          from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($1)) as companies,
         coalesce((select json_agg(json_build_object('productId', x.product_id, 'pricePerCarton', x.price_per_carton))
           from (select distinct on (il.product_id) il.product_id, il.price_per_carton
                 from invoice_lines il join invoices iv on iv.id = il.invoice_id
                 where iv.customer_id = c.id and il.product_id = any($3) order by il.product_id, il.id desc) x), '[]') as "lastPrices"
       from customers c
       where c.active and c.channel = $2 and exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($1))
       order by c.name limit 3000`,
      [companies, trip.kind, productIds],
    );
    return { trip, customers, expenseKinds: TRIP_EXPENSE_KINDS };
  });

  app.post('/trips', { onRequest: auth, preHandler: planners }, async (request, reply) => {
    const body = parse(planBody, request.body);
    const user = request.user;
    const tripId = await inTransaction(db, async (client) => {
      const plan = await checkPlan(client, user, body);
      const number = await nextSharedNumber(client, 'TRP');
      const { rows } = await client.query<{ id: number }>(
        `insert into trips (number, kind, facility_code, company_code, trip_date, route, stops, vehicle_id, driver_id, leader_id, staff, note,
           float_amount, created_by)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) returning id`,
        [number, body.kind, body.facilityCode, plan.companyCode, body.tripDate, body.route, plan.stops, body.vehicleId ?? null,
          body.driverId ?? null, body.leaderId, body.staff, body.note, body.float, user.id],
      );
      await saveLines(client, rows[0]!.id, plan.lines);
      return rows[0]!.id;
    });
    const trip = await tripDetail(db, user, tripId);
    await audit(db, { userId: user.id, action: 'trip_planned', details: { tripId, number: trip!.number } });
    return reply.code(201).send(trip);
  });

  // The whole plan again, until the vehicle is loaded.
  app.put('/trips/:id', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const body = parse(planBody, request.body);
    const user = request.user;
    const number = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'planned') throw new HttpError(409, 'already_loaded', 'This vehicle has already been loaded. The plan cannot change now.');
      if (body.facilityCode !== trip.facilityCode) throw invalid('The warehouse of a trip cannot change. Please plan a new trip.');
      const plan = await checkPlan(client, user, body);
      await client.query(
        `update trips set kind = $2, company_code = $3, trip_date = $4, route = $5, stops = $6, vehicle_id = $7, driver_id = $8, leader_id = $9,
           staff = $10, note = $11, float_amount = $12, updated_at = now() where id = $1`,
        [trip.id, body.kind, plan.companyCode, body.tripDate, body.route, plan.stops, body.vehicleId ?? null, body.driverId ?? null,
          body.leaderId, body.staff, body.note, body.float],
      );
      await saveLines(client, trip.id, plan.lines);
      return trip.number;
    });
    await audit(db, { userId: user.id, action: 'trip_changed', details: { tripId, number } });
    return tripDetail(db, user, tripId);
  });

  app.delete('/trips/:id', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const user = request.user;
    const number = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'planned') throw new HttpError(409, 'already_loaded', 'This vehicle has already been loaded. The trip cannot be deleted.');
      await client.query('delete from trips where id = $1', [trip.id]);
      return trip.number;
    });
    await audit(db, { userId: user.id, action: 'trip_deleted', details: { tripId, number } });
    return { deleted: true };
  });

  // The warehouse loads what is on the shelves and not set aside for orders, oldest expiry first with enough shelf
  // life for these customers, and the vehicle leaves with its gate pass. The stock is in transit until sold.
  app.post('/trips/:id/load', { onRequest: auth, preHandler: loaders }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const body = parse(loadBody, request.body ?? {});
    const user = request.user;
    const result = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'planned') throw new HttpError(409, 'already_loaded', 'This vehicle has already been loaded.');
      const vehicleId = body.vehicleId ?? trip.vehicleId;
      if (!vehicleId) throw invalid('Please pick the vehicle.');
      if (body.vehicleId) {
        const { rows } = await client.query(
          'select 1 from vehicles where id = $1 and active and (facility_code is null or facility_code = $2)',
          [body.vehicleId, trip.facilityCode],
        );
        if (!rows[0]) throw invalid('That vehicle cannot be used for this warehouse.');
      }
      const { rows: lines } = await client.query<{ id: number; productId: number; planned: number; piecesPerCarton: number; label: string }>(
        `select tl.id, tl.product_id as "productId", tl.planned, p.pieces_per_carton as "piecesPerCarton",
           p.brand || ' ' || p.name || ' ' || p.pack_size as label
         from trip_lines tl join products p on p.id = tl.product_id where tl.trip_id = $1 order by tl.id`,
        [trip.id],
      );
      for (const l of body.lines) if (!lines.some((x) => x.productId === l.productId)) throw invalid('That product is not on this trip.');
      const { rows: batches } = await client.query<{ id: number; productId: number; free: number; daysLeft: number }>(
        `select b.id, b.product_id as "productId",
           b.on_hand - b.in_transit - coalesce((select sum(a.pieces) from order_allocations a where a.batch_id = b.id), 0)::int as free,
           (b.expiry_date - (now() at time zone 'Africa/Blantyre')::date) as "daysLeft"
         from stock_batches b where b.product_id = any($1) and b.on_hand > b.in_transit order by b.id for update of b`,
        [lines.map((l) => l.productId)],
      );
      const { rows: order } = await client.query<{ id: number }>(
        'select id from stock_batches where id = any($1) order by expiry_date, id',
        [batches.map((b) => b.id)],
      );
      const oldestFirst = order.map((o) => batches.find((b) => b.id === o.id)!);
      const short: { product: string; cartons: number; pieces: number; loosePieces: number }[] = [];
      let total = 0;
      for (const l of lines) {
        const typed = body.lines.find((x) => x.productId === l.productId);
        let need = typed ? totalPieces(typed, l.piecesPerCarton) : l.planned;
        const want = need;
        for (const b of oldestFirst) {
          if (need === 0) break;
          if (b.productId !== l.productId || b.daysLeft < MIN_SHELF_DAYS[trip.kind] || b.free <= 0) continue;
          const take = Math.min(need, b.free);
          b.free -= take;
          need -= take;
          await client.query('update stock_batches set in_transit = in_transit + $2 where id = $1', [b.id, take]);
          await client.query('insert into trip_line_batches (line_id, batch_id, pieces) values ($1, $2, $3)', [l.id, b.id, take]);
        }
        await client.query('update trip_lines set loaded = $2 where id = $1', [l.id, want - need]);
        total += want - need;
        if (need > 0) short.push({ product: l.label, ...split(need, l.piecesPerCarton) });
      }
      if (total === 0) throw invalid('Nothing could be loaded: none of it is free on the shelves.');
      const gatePassNo = await nextSharedNumber(client, 'GP');
      await client.query(
        `update trips set status = 'out', vehicle_id = $2, gate_pass_no = $3, loaded_at = now(), loaded_by = $4,
           float_custody = case when float_amount > 0 then 'with' else 'na' end, updated_at = now()
         where id = $1`,
        [trip.id, vehicleId, gatePassNo, user.id],
      );
      return { number: trip.number, gatePassNo, short };
    });
    await audit(db, { userId: user.id, action: 'trip_loaded', details: { tripId, number: result.number, gatePassNo: result.gatePassNo } });
    return { ...(await tripDetail(db, user, tripId)), notLoaded: result.short };
  });

  app.post('/trips/:id/sales', { onRequest: auth, preHandler: sellers, bodyLimit: 24 * 1024 * 1024 }, async (request, reply) => {
    const { id: tripId } = parse(idParams, request.params);
    const saved = await recordSale(db, request.user, tripId, parse(saleBody, request.body), uploadDir);
    return reply.code(saved.status).send(saved.body);
  });

  app.post('/trips/:id/expenses', { onRequest: auth, preHandler: sellers, bodyLimit: 16 * 1024 * 1024 }, async (request, reply) => {
    const { id: tripId } = parse(idParams, request.params);
    const saved = await recordExpense(db, request.user, tripId, parse(expenseBody, request.body), uploadDir);
    return reply.code(saved.status).send(saved.body);
  });

  // What the phone saved while offline, sent in one go when the network is back. Each item is saved on its own,
  // in the order sent; one that cannot be saved is reported with its reason and does not stop the others.
  app.post('/trips/:id/sync', { onRequest: auth, preHandler: sellers, bodyLimit: 128 * 1024 * 1024 }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const body = parse(syncBody, request.body);
    const user = request.user;
    const run = async <T>(item: unknown, schema: z.ZodType<T>, save: (input: T) => Promise<Saved>) => {
      const ref = (item as { requestId?: unknown } | null)?.requestId ?? null;
      try {
        const saved = await save(parse(schema, item));
        return { requestId: ref, ok: true, repeat: saved.status === 200, result: saved.body };
      } catch (err) {
        if (!(err instanceof HttpError)) throw err;
        return { requestId: ref, ok: false, error: err.code, message: err.message };
      }
    };
    const sales = [];
    for (const s of body.sales) sales.push(await run(s, saleBody, (input) => recordSale(db, user, tripId, input, uploadDir)));
    const expenses = [];
    for (const e of body.expenses) expenses.push(await run(e, expenseBody, (input) => recordExpense(db, user, tripId, input, uploadDir)));
    const trip = await tripDetail(db, user, tripId);
    if (!trip) throw notFound('trip');
    return { sales, expenses, trip };
  });

  // End of the day or trip: count what came back and the cash. What came back goes on the shelf again; what is
  // missing comes off the books. The cash stays with the leader until handed to the cashier.
  app.post('/trips/:id/close', { onRequest: auth, preHandler: closers }, async (request) => {
    const { id: tripId } = parse(idParams, request.params);
    const body = parse(closeBody, request.body);
    const user = request.user;
    const result = await inTransaction(db, async (client) => {
      const trip = await lockTrip(client, user, tripId);
      if (trip.status !== 'out') throw notOut(trip);
      if (trip.leaderId !== user.id && !CLOSERS.includes(user.role)) throw notAllowed();
      const { rows: lines } = await client.query<{ id: number; productId: number; left: number; piecesPerCarton: number; label: string }>(
        `select tl.id, tl.product_id as "productId", tl.loaded - tl.sold as left, p.pieces_per_carton as "piecesPerCarton",
           p.brand || ' ' || p.name || ' ' || p.pack_size as label
         from trip_lines tl join products p on p.id = tl.product_id where tl.trip_id = $1 order by tl.id for update of tl`,
        [trip.id],
      );
      for (const l of body.lines) if (!lines.some((x) => x.productId === l.productId)) throw invalid('That product is not on this trip.');
      const counted = lines.map((l) => {
        const typed = body.lines.find((x) => x.productId === l.productId);
        if (!typed && l.left > 0) throw invalid(`${l.label}: please count what came back.`);
        const back = typed ? totalPieces(typed, l.piecesPerCarton) : 0;
        if (back > l.left) throw invalid(`${l.label}: only ${qtyText(l.left, l.piecesPerCarton)} should come back. Please count again.`);
        return { ...l, back, missing: l.left - back };
      });
      const { rows: cash } = await client.query<{ expected: number }>(
        `select (${CASH_SALES('t')} + t.float_amount - ${CASH_SPENT('t')})::float8 as expected from trips t where t.id = $1`,
        [trip.id],
      );
      const expected = cash[0]!.expected;
      const difference = body.counted - expected;
      const missing = counted.filter((l) => l.missing > 0);
      if ((difference !== 0 || missing.length) && !body.note) {
        const parts: string[] = [];
        if (difference !== 0) parts.push(`The cash is ${money(Math.abs(difference))} ${difference < 0 ? 'short' : 'over'}.`);
        if (missing.length) parts.push(`Missing: ${missing.map((l) => `${l.label} ${qtyText(l.missing, l.piecesPerCarton)}`).join(', ')}.`);
        throw invalid(`${parts.join(' ')} Please write a note.`);
      }

      for (const l of counted) {
        const { rows: batches } = await client.query<{ batchId: number; left: number }>(
          `select tb.batch_id as "batchId", tb.pieces - tb.sold as left from trip_line_batches tb join stock_batches b on b.id = tb.batch_id
           where tb.line_id = $1 and tb.pieces > tb.sold order by b.expiry_date, b.id`,
          [l.id],
        );
        await client.query('select id from stock_batches where id = any($1) order by id for update', [batches.map((b) => b.batchId)]);
        let lost = l.missing;
        for (const b of batches) {
          // Everything still on the vehicle leaves in transit; what is missing also leaves the books.
          const gone = Math.min(lost, b.left);
          lost -= gone;
          await client.query('update stock_batches set in_transit = in_transit - $2, on_hand = on_hand - $3 where id = $1', [b.batchId, b.left, gone]);
          if (gone > 0) {
            await client.query(
              `insert into stock_movements (batch_id, change, kind, reason, note, trip_id, user_id) values ($1, $2, 'adjusted', 'lost', $3, $4, $5)`,
              [b.batchId, -gone, `Missing after trip ${trip.number}. ${body.note}`.trim(), trip.id, user.id],
            );
          }
        }
        await client.query('update trip_lines set returned = $2, missing = $3 where id = $1', [l.id, l.back, l.missing]);
      }
      await client.query(
        `update trips set status = 'closed', cash_expected = $2, cash_counted = $3, cash_difference = $4, close_note = $5,
           closed_at = now(), closed_by = $6, updated_at = now() where id = $1`,
        [trip.id, expected, body.counted, difference, body.note, user.id],
      );
      return { number: trip.number, expected, difference, missing: missing.reduce((t, l) => t + l.missing, 0) };
    });
    await audit(db, {
      userId: user.id,
      action: 'trip_closed',
      details: { tripId, number: result.number, cashExpected: result.expected, cashCounted: body.counted, cashDifference: result.difference, piecesMissing: result.missing },
    });
    return tripDetail(db, user, tripId);
  });

  for (const [kind, column] of [
    ['signature', 'signature_file'],
    ['proof', 'proof_file'],
  ] as const) {
    app.get(`/trip-sales/:id/${kind}`, { onRequest: auth, preHandler: viewers }, async (request, reply) => {
      const { id: saleId } = parse(idParams, request.params);
      const user = request.user;
      if (user.role === 'warehouse') throw notAllowed();
      const { rows } = await db.query<{ file: string | null }>(
        `select s.${column} as file from trip_sales s join trips t on t.id = s.trip_id where s.id = $3 and ${visibleWhere(user)}`,
        [user.companies, user.id, saleId],
      );
      const image = rows[0]?.file ? await readImage(uploadDir, rows[0].file) : null;
      if (!image) throw notFound(kind === 'proof' ? 'photo' : 'signature');
      return reply.type(image.type).header('cache-control', 'private, max-age=86400').send(image.bytes);
    });
  }
}
