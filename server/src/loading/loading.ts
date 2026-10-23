import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { idParams, invalid, notFound, type Channel } from '../lists/common.js';
import { nextSharedNumber } from '../numbering.js';
import { addEvent, MIN_SHELF_DAYS, PLANNED_SQL, reserveStock } from '../orders/orders.js';
import { inTransaction, split, TODAY } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// Make loading plans: choose the vehicle, driver and the order lines that go on it.
export const PLANNERS = ['admin', 'management', 'sales_manager'] as const;
// The warehouse changes quantities with a reason, loads the vehicle and lets it out of the gate.
export const LOADERS = ['admin', 'warehouse'] as const;
// Drivers see only their own plans once loaded. Salesmen never see plans (they show stock).
export const PLAN_VIEWERS = ['admin', 'management', 'sales_manager', 'warehouse', 'driver', 'accounts', 'cashier'] as const;

const id = z.number().int().positive().max(INT4_MAX);
const quantity = {
  cartons: z.number().int().min(0, 'Cartons cannot be below 0.').max(1_000_000).default(0),
  pieces: z.number().int().min(0, 'Pieces cannot be below 0.').max(INT4_MAX).default(0),
};
const note = z.string().trim().max(1000);

const createBody = z.object({
  facilityCode: z.string(),
  vehicleId: id.nullish(),
  driverId: id.nullish(),
  note: note.default(''),
});
const itemsBody = z.object({
  vehicleId: id.nullish(),
  driverId: id.nullish(),
  note: note.optional(),
  items: z.array(z.object({ lineId: id, ...quantity })).max(2000).optional(),
});
const warehouseBody = z.object({
  products: z.array(z.object({ productId: id, ...quantity })).min(1, 'Change at least one product.').max(500),
  reason: z.string('Please say why the quantity changed.').trim().min(1, 'Please say why the quantity changed.').max(500),
});
const listQuery = z.object({
  status: z.enum(['planned', 'loaded', 'out', 'done']).optional(),
  facility: z.string().optional(),
});

const totalPieces = (q: { cartons: number; pieces: number }, piecesPerCarton: number) => q.cartons * piecesPerCarton + q.pieces;
function qtyText(pieces: number, piecesPerCarton: number) {
  const { cartons, loosePieces } = split(pieces, piecesPerCarton);
  if (loosePieces === 0) return `${cartons} ctn`;
  return cartons ? `${cartons} ctn + ${loosePieces} pcs` : `${loosePieces} pcs`;
}
const has = (roles: readonly string[], user: AuthUser) => roles.includes(user.role);

// Which plans a user may see. $1 = their companies, $2 = their id. Both branches name both, so Postgres knows their types.
function visibleWhere(user: AuthUser) {
  if (user.role === 'driver') return `$1::text[] is not null and lp.driver_id = $2::int and lp.status <> 'planned'`;
  return `$2::int is not null and exists (select 1 from companies x where x.facility_code = lp.facility_code and x.code = any($1::text[]))`;
}

type PlanHead = { id: number; number: string; facilityCode: string; status: string; sent: boolean; vehicleId: number | null; driverId: number | null };

async function lockPlan(client: PoolClient, user: AuthUser, planId: number): Promise<PlanHead> {
  const { rows } = await client.query<PlanHead>(
    `select lp.id, lp.number, lp.facility_code as "facilityCode", lp.status, lp.sent_at is not null as sent,
       lp.vehicle_id as "vehicleId", lp.driver_id as "driverId"
     from loading_plans lp where lp.id = $3 and ${visibleWhere(user)} for update of lp`,
    [user.companies, user.id, planId],
  );
  if (!rows[0]) throw notFound('loading plan');
  return rows[0];
}

const notPlanned = () => new HttpError(409, 'already_loaded', 'This vehicle has already been loaded. The plan cannot change now.');

async function checkVehicle(client: PoolClient | Db, vehicleId: number, facilityCode: string) {
  const { rows } = await client.query<{ ok: boolean; driverId: number | null }>(
    `select v.active and (v.facility_code is null or v.facility_code = $2) as ok, v.default_driver_id as "driverId" from vehicles v where v.id = $1`,
    [vehicleId, facilityCode],
  );
  if (!rows[0]?.ok) throw invalid('That vehicle cannot be used for this warehouse.');
  return rows[0].driverId;
}

async function checkDriver(client: PoolClient | Db, driverId: number) {
  const { rows } = await client.query("select 1 from users where id = $1 and role = 'driver' and active", [driverId]);
  if (!rows[0]) throw invalid('Please pick a driver.');
}

// Locks the orders of these lines in id order. Order changes lock the order first too, so the two never deadlock.
async function lockOrdersOfLines(client: PoolClient, lineIds: number[]) {
  if (lineIds.length === 0) return;
  await client.query(
    `select o.id from orders o where o.id in (select order_id from order_lines where id = any($1)) order by o.id for update`,
    [lineIds],
  );
}

type OpenLine = {
  lineId: number;
  orderId: number;
  orderNumber: string;
  orderStatus: string;
  facilityCode: string;
  customerName: string;
  productId: number;
  companyCode: string;
  piecesPerCarton: number;
  label: string;
  open: number; // what this plan may carry, including what it already carries
  current: number;
};

// Order lines and how much of each this plan may still carry.
async function openLines(client: PoolClient | Db, planId: number, lineIds: number[] | null) {
  const { rows } = await client.query<OpenLine>(
    `select l.id as "lineId", o.id as "orderId", o.number as "orderNumber", o.status as "orderStatus", o.facility_code as "facilityCode",
       c.name as "customerName", p.id as "productId", p.company_code as "companyCode", p.pieces_per_carton as "piecesPerCarton",
       p.brand || ' ' || p.name || ' ' || p.pack_size as label,
       (l.pieces - l.delivered - l.on_vehicle - ${PLANNED_SQL('l.id')} + coalesce(cur.pieces, 0))::int as open,
       coalesce(cur.pieces, 0)::int as current
     from order_lines l join orders o on o.id = l.order_id join customers c on c.id = o.customer_id join products p on p.id = l.product_id
       left join loading_plan_items cur on cur.plan_id = $1 and cur.order_line_id = l.id
     where l.id = any($2)`,
    [planId, lineIds],
  );
  return rows;
}

async function logChange(
  client: PoolClient,
  plan: PlanHead,
  user: AuthUser,
  byWarehouse: boolean,
  productId: number,
  lineId: number | null,
  before: number,
  after: number,
  reason = '',
) {
  await client.query(
    `insert into loading_plan_changes (plan_id, user_id, by_warehouse, product_id, order_line_id, before, after, reason, seen)
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
    // Before sending, nobody else is looking yet, so nothing is new.
    [plan.id, user.id, byWarehouse, productId, lineId, before, after, reason, !plan.sent],
  );
}

async function setItem(client: PoolClient, planId: number, lineId: number, pieces: number) {
  if (pieces === 0) await client.query('delete from loading_plan_items where plan_id = $1 and order_line_id = $2', [planId, lineId]);
  else {
    await client.query(
      `insert into loading_plan_items (plan_id, order_line_id, pieces) values ($1, $2, $3)
       on conflict (plan_id, order_line_id) do update set pieces = excluded.pieces`,
      [planId, lineId, pieces],
    );
  }
}

type ItemRow = {
  itemId: number;
  lineId: number;
  orderId: number;
  orderNumber: string;
  customerId: number;
  customerName: string;
  area: string;
  lpoNo: string;
  productId: number;
  code: string;
  brand: string;
  name: string;
  packSize: string;
  companyCode: string;
  piecesPerCarton: number;
  pieces: number;
  loaded: number;
};

// One plan as the app shows it: products with the customers under each (the warehouse view),
// the drops per customer (the driver's view), batches to pick or loaded, and the change log.
export async function planDetail(db: Db | PoolClient, user: AuthUser, planId: number) {
  const { rows } = await db.query(
    `select lp.id, lp.number, lp.facility_code as "facilityCode", f.name as "facilityName", lp.status, lp.sent_at is not null as sent,
       lp.sent_at as "sentAt", lp.vehicle_id as "vehicleId", v.plate as "vehiclePlate", v.description as "vehicleDescription",
       lp.driver_id as "driverId", d.name as "driverName", lp.note, lp.delivery_note_no as "deliveryNoteNo", lp.gate_pass_no as "gatePassNo",
       lp.loaded_at as "loadedAt", lb.name as "loadedBy", lp.out_at as "outAt", ob.name as "outBy",
       cb.name as "createdBy", lp.created_at as "createdAt", lp.updated_at as "updatedAt"
     from loading_plans lp join facilities f on f.code = lp.facility_code join users cb on cb.id = lp.created_by
       left join vehicles v on v.id = lp.vehicle_id left join users d on d.id = lp.driver_id
       left join users lb on lb.id = lp.loaded_by left join users ob on ob.id = lp.out_by
     where lp.id = $3 and ${visibleWhere(user)}`,
    [user.companies, user.id, planId],
  );
  const plan = rows[0];
  if (!plan) return null;

  const { rows: items } = await db.query<ItemRow>(
    `select i.id as "itemId", l.id as "lineId", o.id as "orderId", o.number as "orderNumber", c.id as "customerId", c.name as "customerName",
       c.area, coalesce(oc.lpo_no, '') as "lpoNo", p.id as "productId", p.code, p.brand, p.name, p.pack_size as "packSize",
       p.company_code as "companyCode", p.pieces_per_carton as "piecesPerCarton", i.pieces, i.loaded
     from loading_plan_items i join order_lines l on l.id = i.order_line_id join orders o on o.id = l.order_id
       join customers c on c.id = o.customer_id join products p on p.id = l.product_id
       left join order_companies oc on oc.order_id = o.id and oc.company_code = p.company_code
     where i.plan_id = $1 order by p.company_code, p.brand, p.name, p.pack_size, o.id`,
    [planId],
  );

  // Before loading: the batches set aside for these lines, oldest first, as a picking guide.
  // After loading: the batches that actually went on the vehicle.
  const { rows: batchRows } = await db.query<{ productId: number; batchId: number; batchNo: string; expiryDate: string; pieces: number }>(
    plan.status === 'planned'
      ? `select b.product_id as "productId", b.id as "batchId", b.batch_no as "batchNo", to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate",
           sum(a.pieces)::int as pieces
         from order_allocations a join stock_batches b on b.id = a.batch_id
         where a.line_id in (select order_line_id from loading_plan_items where plan_id = $1)
         group by b.id order by b.expiry_date, b.id`
      : `select b.product_id as "productId", b.id as "batchId", b.batch_no as "batchNo", to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate",
           sum(ib.pieces)::int as pieces
         from loading_plan_item_batches ib join loading_plan_items i on i.id = ib.item_id join stock_batches b on b.id = ib.batch_id
         where i.plan_id = $1 group by b.id order by b.expiry_date, b.id`,
    [planId],
  );

  type Product = Pick<ItemRow, 'productId' | 'code' | 'brand' | 'name' | 'packSize' | 'companyCode' | 'piecesPerCarton'> & {
    pieces: number;
    loaded: number;
    items: ItemRow[];
  };
  const products = new Map<number, Product>();
  for (const it of items) {
    const p = products.get(it.productId) ?? {
      productId: it.productId,
      code: it.code,
      brand: it.brand,
      name: it.name,
      packSize: it.packSize,
      companyCode: it.companyCode,
      piecesPerCarton: it.piecesPerCarton,
      pieces: 0,
      loaded: 0,
      items: [],
    };
    p.pieces += it.pieces;
    p.loaded += it.loaded;
    p.items.push(it);
    products.set(it.productId, p);
  }
  const productList = [...products.values()].map((p) => {
    // A picking guide never asks for more than the plan carries.
    let left = plan.status === 'planned' ? p.pieces : Infinity;
    const batches = [];
    for (const b of batchRows.filter((x) => x.productId === p.productId)) {
      if (left <= 0) break;
      const pieces = Math.min(b.pieces, left);
      left -= pieces;
      batches.push({ batchId: b.batchId, batchNo: b.batchNo, expiryDate: b.expiryDate, ...split(pieces, p.piecesPerCarton) });
    }
    return {
      ...p,
      ...split(p.pieces, p.piecesPerCarton),
      loadedCartons: split(p.loaded, p.piecesPerCarton),
      items: p.items.map((i) => ({
        itemId: i.itemId,
        lineId: i.lineId,
        orderId: i.orderId,
        orderNumber: i.orderNumber,
        customerId: i.customerId,
        customerName: i.customerName,
        area: i.area,
        lpoNo: i.lpoNo,
        loaded: i.loaded,
        ...split(i.pieces, i.piecesPerCarton),
      })),
      batches,
    };
  });

  // One drop per customer, in the order they were added.
  const drops = new Map<number, { customerId: number; customerName: string; area: string; orders: string[]; lines: unknown[] }>();
  for (const it of [...items].sort((a, b) => a.orderId - b.orderId || a.itemId - b.itemId)) {
    const d = drops.get(it.customerId) ?? { customerId: it.customerId, customerName: it.customerName, area: it.area, orders: [], lines: [] };
    if (!d.orders.includes(it.orderNumber)) d.orders.push(it.orderNumber);
    d.lines.push({
      orderNumber: it.orderNumber,
      companyCode: it.companyCode,
      lpoNo: it.lpoNo,
      productId: it.productId,
      code: it.code,
      brand: it.brand,
      name: it.name,
      packSize: it.packSize,
      ...split(plan.status === 'planned' ? it.pieces : it.loaded, it.piecesPerCarton),
    });
    drops.set(it.customerId, d);
  }

  const { rows: changes } = await db.query(
    `select ch.id, ch.by_warehouse as "byWarehouse", u.name as by, ch.created_at as at, ch.product_id as "productId",
       p.brand || ' ' || p.name || ' ' || p.pack_size as product, p.pieces_per_carton as "piecesPerCarton", o.number as "orderNumber",
       c.name as "customerName", ch.before, ch.after, ch.reason, ch.seen
     from loading_plan_changes ch join users u on u.id = ch.user_id join products p on p.id = ch.product_id
       left join order_lines l on l.id = ch.order_line_id left join orders o on o.id = l.order_id left join customers c on c.id = o.customer_id
     where ch.plan_id = $1 order by ch.id`,
    [planId],
  );
  // New for this user: the warehouse sees what planners changed after sending, and planners see what the warehouse changed.
  const isNewFor = (byWarehouse: boolean) => (user.role === 'warehouse' ? !byWarehouse : user.role === 'admin' ? true : byWarehouse);
  const planned = plan.status === 'planned';
  return {
    ...plan,
    products: productList,
    drops: [...drops.values()],
    changes,
    newChanges: changes.filter((c) => !c.seen && isNewFor(c.byWarehouse)).length,
    canPlan: has(PLANNERS, user) && planned,
    canSend: has(PLANNERS, user) && planned && !plan.sent,
    canWarehouseChange: has(LOADERS, user) && planned && plan.sent,
    canLoad: has(LOADERS, user) && planned && plan.sent,
    canGate: has(LOADERS, user) && plan.status === 'loaded',
    canDelete: has(PLANNERS, user) && planned,
  };
}

export function loadingRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const planners = requireRole(...PLANNERS);
  const loaders = requireRole(...LOADERS);
  const viewers = requireRole(...PLAN_VIEWERS);

  app.get('/loading-plans', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(listQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = visibleWhere(user);
    if (query.status) {
      params.push(query.status);
      where += ` and lp.status = $${params.length}`;
    }
    if (query.facility) {
      params.push(query.facility);
      where += ` and lp.facility_code = $${params.length}`;
    }
    const newFor = user.role === 'warehouse' ? 'not ch.by_warehouse' : user.role === 'admin' ? 'true' : 'ch.by_warehouse';
    const { rows } = await db.query(
      `select lp.id, lp.number, lp.facility_code as "facilityCode", lp.status, lp.sent_at is not null as sent,
         v.plate as "vehiclePlate", d.name as "driverName", lp.delivery_note_no as "deliveryNoteNo", lp.gate_pass_no as "gatePassNo",
         lp.created_at as "createdAt",
         (select count(distinct o.customer_id) from loading_plan_items i join order_lines l on l.id = i.order_line_id
            join orders o on o.id = l.order_id where i.plan_id = lp.id)::int as customers,
         (select coalesce(sum(case when lp.status = 'planned' then i.pieces else i.loaded end), 0) from loading_plan_items i where i.plan_id = lp.id)::int as pieces,
         (select count(*) from loading_plan_changes ch where ch.plan_id = lp.id and not ch.seen and ${newFor})::int as "newChanges"
       from loading_plans lp left join vehicles v on v.id = lp.vehicle_id left join users d on d.id = lp.driver_id
       where ${where} order by lp.id desc limit 200`,
      params,
    );
    return rows;
  });

  app.get('/loading-plans/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const plan = await planDetail(db, request.user, planId);
    if (!plan) throw notFound('loading plan');
    return plan;
  });

  app.post('/loading-plans', { onRequest: auth, preHandler: planners }, async (request, reply) => {
    const body = parse(createBody, request.body);
    const user = request.user;
    const { rows: cos } = await db.query<{ code: string }>('select code from companies where facility_code = $1', [body.facilityCode]);
    if (cos.length === 0) throw invalid('That warehouse is not known.');
    if (!cos.some((c) => user.companies.includes(c.code))) throw notAllowed();
    let driverId = body.driverId ?? null;
    if (body.vehicleId) {
      const usual = await checkVehicle(db, body.vehicleId, body.facilityCode);
      driverId ??= usual;
    }
    if (driverId) await checkDriver(db, driverId);
    const planId = await inTransaction(db, async (client) => {
      const number = await nextSharedNumber(client, 'LP');
      const { rows } = await client.query<{ id: number }>(
        `insert into loading_plans (number, facility_code, vehicle_id, driver_id, note, created_by) values ($1, $2, $3, $4, $5, $6) returning id`,
        [number, body.facilityCode, body.vehicleId ?? null, driverId, body.note, user.id],
      );
      return rows[0]!.id;
    });
    const plan = await planDetail(db, user, planId);
    await audit(db, { userId: user.id, action: 'loading_plan_started', details: { planId, number: plan!.number } });
    return reply.code(201).send(plan);
  });

  // Open order lines of this plan's warehouse, product by product, with how much more each can put on this vehicle.
  app.get('/loading-plans/:id/open-lines', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const { rows: plans } = await db.query<{ facilityCode: string }>(
      `select lp.facility_code as "facilityCode" from loading_plans lp where lp.id = $3 and ${visibleWhere(user)}`,
      [user.companies, user.id, planId],
    );
    if (!plans[0]) throw notFound('loading plan');
    const { rows } = await db.query(
      `select * from (
         select l.id as "lineId", o.id as "orderId", o.number as "orderNumber", to_char(o.wanted_on, 'YYYY-MM-DD') as "wantedOn",
           c.id as "customerId", c.name as "customerName", c.area, c.channel, coalesce(oc.lpo_no, '') as "lpoNo",
           p.id as "productId", p.code, p.brand, p.name, p.pack_size as "packSize", p.company_code as "companyCode",
           p.pieces_per_carton as "piecesPerCarton",
           (l.pieces - l.delivered - l.on_vehicle - ${PLANNED_SQL('l.id')} + coalesce(cur.pieces, 0))::int as open,
           coalesce(cur.pieces, 0)::int as current
         from order_lines l join orders o on o.id = l.order_id join customers c on c.id = o.customer_id
           join products p on p.id = l.product_id
           left join order_companies oc on oc.order_id = o.id and oc.company_code = p.company_code
           left join loading_plan_items cur on cur.plan_id = $1 and cur.order_line_id = l.id
         where o.status = 'open' and o.facility_code = $2 and p.company_code = any($3)
       ) x where x.open > 0
       order by x."companyCode", x.brand, x.name, x."packSize", x."orderId"`,
      [planId, plans[0].facilityCode, user.companies],
    );
    const products = new Map<number, { productId: number; code: string; brand: string; name: string; packSize: string; companyCode: string; piecesPerCarton: number; open: number; current: number; lines: unknown[] }>();
    for (const r of rows) {
      const p = products.get(r.productId) ?? {
        productId: r.productId,
        code: r.code,
        brand: r.brand,
        name: r.name,
        packSize: r.packSize,
        companyCode: r.companyCode,
        piecesPerCarton: r.piecesPerCarton,
        open: 0,
        current: 0,
        lines: [] as unknown[],
      };
      p.open += r.open;
      p.current += r.current;
      p.lines.push({
        lineId: r.lineId,
        orderId: r.orderId,
        orderNumber: r.orderNumber,
        wantedOn: r.wantedOn,
        customerId: r.customerId,
        customerName: r.customerName,
        area: r.area,
        channel: r.channel,
        lpoNo: r.lpoNo,
        open: r.open,
        current: r.current,
        openCartons: split(r.open, r.piecesPerCarton),
        currentCartons: split(r.current, r.piecesPerCarton),
      });
      products.set(r.productId, p);
    }
    return [...products.values()];
  });

  // Vehicle, driver, note and the quantity of each order line on this plan (0 takes it off).
  app.put('/loading-plans/:id/items', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const body = parse(itemsBody, request.body);
    const user = request.user;
    const changed = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status !== 'planned') throw notPlanned();
      let count = 0;
      if (body.vehicleId !== undefined && body.vehicleId !== plan.vehicleId) {
        const usual = body.vehicleId ? await checkVehicle(client, body.vehicleId, plan.facilityCode) : null;
        await client.query('update loading_plans set vehicle_id = $2 where id = $1', [plan.id, body.vehicleId]);
        // A new vehicle brings its usual driver unless one is picked too.
        if (body.driverId === undefined && usual) {
          await checkDriver(client, usual);
          await client.query('update loading_plans set driver_id = $2 where id = $1', [plan.id, usual]);
        }
        count++;
      }
      if (body.driverId !== undefined && body.driverId !== plan.driverId) {
        if (body.driverId) await checkDriver(client, body.driverId);
        await client.query('update loading_plans set driver_id = $2 where id = $1', [plan.id, body.driverId]);
        count++;
      }
      if (body.note !== undefined) {
        const { rowCount } = await client.query('update loading_plans set note = $2 where id = $1 and note <> $2', [plan.id, body.note]);
        count += rowCount ?? 0;
      }
      if (body.items?.length) {
        const lineIds = body.items.map((i) => i.lineId);
        if (new Set(lineIds).size !== lineIds.length) throw invalid('An order line is on the list twice.');
        await lockOrdersOfLines(client, lineIds);
        const lines = new Map((await openLines(client, plan.id, lineIds)).map((l) => [l.lineId, l]));
        for (const item of body.items) {
          const line = lines.get(item.lineId);
          if (!line || line.facilityCode !== plan.facilityCode || !user.companies.includes(line.companyCode)) {
            throw invalid('That order is not one of this warehouse’s open orders.');
          }
          const pieces = totalPieces(item, line.piecesPerCarton);
          if (pieces === line.current) continue;
          if (pieces > 0 && line.orderStatus !== 'open') {
            throw invalid(`${line.orderNumber} is ${line.orderStatus === 'hold' ? 'on hold' : 'closed'}. It cannot be loaded.`);
          }
          if (pieces > line.open) {
            throw invalid(`${line.customerName}, ${line.label}: only ${qtyText(line.open, line.piecesPerCarton)} is still open.`);
          }
          await setItem(client, plan.id, line.lineId, pieces);
          await logChange(client, plan, user, false, line.productId, line.lineId, line.current, pieces);
          count++;
        }
      }
      if (count) await client.query('update loading_plans set updated_at = now() where id = $1', [plan.id]);
      return { count, number: plan.number };
    });
    if (changed.count) await audit(db, { userId: user.id, action: 'loading_plan_changed', details: { planId, number: changed.number } });
    return planDetail(db, user, planId);
  });

  app.post('/loading-plans/:id/send', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const number = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status !== 'planned') throw notPlanned();
      if (plan.sent) throw new HttpError(409, 'already_sent', 'This plan is already with the warehouse.');
      if (!plan.vehicleId) throw invalid('Please pick the vehicle.');
      if (!plan.driverId) throw invalid('Please pick the driver.');
      const { rows } = await client.query('select 1 from loading_plan_items where plan_id = $1 limit 1', [plan.id]);
      if (!rows[0]) throw invalid('Add at least one order to the plan first.');
      await client.query('update loading_plans set sent_at = now(), updated_at = now() where id = $1', [plan.id]);
      return plan.number;
    });
    await audit(db, { userId: user.id, action: 'loading_plan_sent', details: { planId, number } });
    return planDetail(db, user, planId);
  });

  // The user has seen the other side's changes.
  app.post('/loading-plans/:id/seen', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const plan = await planDetail(db, user, planId);
    if (!plan) throw notFound('loading plan');
    const which = user.role === 'warehouse' ? 'not by_warehouse' : user.role === 'admin' ? 'true' : 'by_warehouse';
    await db.query(`update loading_plan_changes set seen = true where plan_id = $1 and not seen and ${which}`, [planId]);
    return planDetail(db, user, planId);
  });

  // The warehouse changes how much of a product goes on the vehicle, with a reason the planner sees.
  // Less is taken off the last orders first; more fills the orders on the plan up to what they still have open.
  app.patch('/loading-plans/:id/warehouse', { onRequest: auth, preHandler: loaders }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const body = parse(warehouseBody, request.body);
    const user = request.user;
    const result = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status !== 'planned') throw notPlanned();
      if (!plan.sent) throw new HttpError(409, 'not_sent', 'This plan has not been sent to the warehouse yet.');
      const { rows: itemLines } = await client.query<{ lineId: number }>(
        'select order_line_id as "lineId" from loading_plan_items where plan_id = $1',
        [plan.id],
      );
      await lockOrdersOfLines(client, itemLines.map((i) => i.lineId));
      const lines = await openLines(client, plan.id, itemLines.map((i) => i.lineId));
      const notAdded: { productId: number; pieces: number }[] = [];
      for (const p of body.products) {
        const its = lines.filter((l) => l.productId === p.productId).sort((a, b) => a.orderId - b.orderId || a.lineId - b.lineId);
        if (its.length === 0) throw invalid('That product is not on this plan.');
        const before = its.reduce((t, l) => t + l.current, 0);
        const target = totalPieces(p, its[0]!.piecesPerCarton);
        let diff = target - before;
        if (diff === 0) continue;
        if (diff < 0) {
          for (const l of [...its].reverse()) {
            if (diff === 0) break;
            const take = Math.min(l.current, -diff);
            l.current -= take;
            diff += take;
            await setItem(client, plan.id, l.lineId, l.current);
          }
        } else {
          for (const l of its) {
            if (diff === 0) break;
            const room = l.orderStatus === 'open' ? l.open - l.current : 0;
            const add = Math.min(room, diff);
            if (add <= 0) continue;
            l.current += add;
            diff -= add;
            await setItem(client, plan.id, l.lineId, l.current);
          }
          if (diff > 0) notAdded.push({ productId: p.productId, pieces: diff });
        }
        const after = its.reduce((t, l) => t + l.current, 0);
        await logChange(client, plan, user, true, p.productId, null, before, after, body.reason);
      }
      await client.query('update loading_plans set updated_at = now() where id = $1', [plan.id]);
      return { number: plan.number, notAdded };
    });
    await audit(db, { userId: user.id, action: 'loading_plan_changed_by_warehouse', details: { planId, number: result.number, reason: body.reason } });
    return { ...(await planDetail(db, user, planId)), notAdded: result.notAdded };
  });

  // Loading: stock leaves the shelves, oldest expiry first, from what each order had set aside, and the
  // delivery note and gate pass get their numbers. What cannot be found on the shelves stays on the order.
  // The client wants stock reduced only on delivery (7 Oct), so loaded stock moves to in transit; Phase 6 deducts it.
  app.post('/loading-plans/:id/load', { onRequest: auth, preHandler: loaders }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const result = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status !== 'planned') throw notPlanned();
      if (!plan.sent) throw new HttpError(409, 'not_sent', 'This plan has not been sent to the warehouse yet.');
      const { rows: before } = await client.query<{ lineId: number }>(
        'select order_line_id as "lineId" from loading_plan_items where plan_id = $1',
        [plan.id],
      );
      await lockOrdersOfLines(client, before.map((i) => i.lineId));
      // Read again under the order locks: a hold may have just taken an order off.
      const { rows: items } = await client.query<{
        itemId: number;
        lineId: number;
        orderId: number;
        channel: Channel;
        productId: number;
        piecesPerCarton: number;
        label: string;
        pieces: number;
      }>(
        `select i.id as "itemId", l.id as "lineId", o.id as "orderId", c.channel, l.product_id as "productId",
           p.pieces_per_carton as "piecesPerCarton", p.brand || ' ' || p.name || ' ' || p.pack_size as label, i.pieces
         from loading_plan_items i join order_lines l on l.id = i.order_line_id join orders o on o.id = l.order_id
           join customers c on c.id = o.customer_id join products p on p.id = l.product_id
         where i.plan_id = $1 order by o.id, l.id`,
        [plan.id],
      );
      if (items.length === 0) throw invalid('Nothing to load. The plan is empty.');

      // Lock every batch of these products in id order, like reserving does.
      const { rows: batches } = await client.query<{ id: number; productId: number; expiryDate: string; shelf: number; daysLeft: number; reserved: number }>(
        `select b.id, b.product_id as "productId", to_char(b.expiry_date, 'YYYY-MM-DD') as "expiryDate", b.on_hand - b.in_transit as shelf,
           (b.expiry_date - ${TODAY}) as "daysLeft",
           coalesce((select sum(a.pieces) from order_allocations a where a.batch_id = b.id), 0)::int as reserved
         from stock_batches b where b.product_id = any($1) and b.on_hand > b.in_transit order by b.id for update of b`,
        [[...new Set(items.map((i) => i.productId))]],
      );
      const byBatch = new Map(batches.map((b) => [b.id, b]));
      const oldestFirst = [...batches].sort((a, b) => a.expiryDate.localeCompare(b.expiryDate) || a.id - b.id);
      const short: { label: string; pieces: number; piecesPerCarton: number }[] = [];
      const loadedByOrder = new Map<number, number>();

      for (const it of items) {
        let need = it.pieces;
        const taken = new Map<number, number>();
        // First what was set aside for this line.
        const { rows: allocs } = await client.query<{ batchId: number; pieces: number }>(
          `select a.batch_id as "batchId", a.pieces from order_allocations a join stock_batches b on b.id = a.batch_id
           where a.line_id = $1 order by b.expiry_date, b.id`,
          [it.lineId],
        );
        for (const a of allocs) {
          if (need === 0) break;
          const b = byBatch.get(a.batchId);
          if (!b) continue;
          const take = Math.min(need, a.pieces, b.shelf);
          if (take <= 0) continue;
          if (take === a.pieces) await client.query('delete from order_allocations where line_id = $1 and batch_id = $2', [it.lineId, a.batchId]);
          else {
            await client.query('update order_allocations set pieces = pieces - $3 where line_id = $1 and batch_id = $2', [it.lineId, a.batchId, take]);
          }
          b.shelf -= take;
          b.reserved -= take;
          taken.set(b.id, (taken.get(b.id) ?? 0) + take);
          need -= take;
        }
        // Then free stock with enough shelf life left for this customer.
        for (const b of oldestFirst) {
          if (need === 0) break;
          if (b.productId !== it.productId || b.daysLeft < MIN_SHELF_DAYS[it.channel]) continue;
          const take = Math.min(need, b.shelf - Math.max(0, b.reserved));
          if (take <= 0) continue;
          b.shelf -= take;
          taken.set(b.id, (taken.get(b.id) ?? 0) + take);
          need -= take;
        }
        const loaded = it.pieces - need;
        for (const [batchId, pieces] of taken) {
          await client.query('update stock_batches set in_transit = in_transit + $2 where id = $1', [batchId, pieces]);
          await client.query('insert into loading_plan_item_batches (item_id, batch_id, pieces) values ($1, $2, $3)', [it.itemId, batchId, pieces]);
        }
        if (loaded === 0) await client.query('delete from loading_plan_items where id = $1', [it.itemId]);
        else {
          await client.query('update loading_plan_items set loaded = $2 where id = $1', [it.itemId, loaded]);
          await client.query('update order_lines set on_vehicle = on_vehicle + $2 where id = $1', [it.lineId, loaded]);
        }
        if (need > 0) short.push({ label: it.label, pieces: need, piecesPerCarton: it.piecesPerCarton });
        loadedByOrder.set(it.orderId, (loadedByOrder.get(it.orderId) ?? 0) + loaded);
      }
      if (![...loadedByOrder.values()].some((n) => n > 0)) throw invalid('Nothing could be loaded: none of it is on the shelves.');

      const deliveryNoteNo = await nextSharedNumber(client, 'DN');
      const gatePassNo = await nextSharedNumber(client, 'GP');
      await client.query(
        `update loading_plans set status = 'loaded', delivery_note_no = $2, gate_pass_no = $3, loaded_at = now(), loaded_by = $4, updated_at = now()
         where id = $1`,
        [plan.id, deliveryNoteNo, gatePassNo, user.id],
      );
      const { rows: vehicle } = await client.query<{ plate: string }>('select plate from vehicles where id = $1', [plan.vehicleId]);
      for (const [orderId, loaded] of loadedByOrder) {
        const channel = items.find((i) => i.orderId === orderId)!.channel;
        // What was set aside but not loaded goes back to the order's reservation.
        await reserveStock(client, orderId, channel);
        if (loaded > 0) {
          await addEvent(client, orderId, user.id, 'Loaded', `On ${vehicle[0]?.plate ?? 'the vehicle'} · delivery note ${deliveryNoteNo}`);
        }
      }
      return { number: plan.number, deliveryNoteNo, gatePassNo, short };
    });
    await audit(db, {
      userId: user.id,
      action: 'loading_plan_loaded',
      details: { planId, number: result.number, deliveryNoteNo: result.deliveryNoteNo, gatePassNo: result.gatePassNo },
    });
    return {
      ...(await planDetail(db, user, planId)),
      notLoaded: result.short.map((s) => ({ product: s.label, ...split(s.pieces, s.piecesPerCarton) })),
    };
  });

  // The vehicle leaves through the gate with its gate pass.
  app.post('/loading-plans/:id/gate', { onRequest: auth, preHandler: loaders }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const number = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status === 'planned') throw new HttpError(409, 'not_loaded', 'Load the vehicle first.');
      if (plan.status !== 'loaded') throw new HttpError(409, 'already_out', 'This vehicle has already left.');
      await client.query(`update loading_plans set status = 'out', out_at = now(), out_by = $2, updated_at = now() where id = $1`, [plan.id, user.id]);
      const { rows } = await client.query<{ orderId: number; gatePassNo: string }>(
        `select distinct l.order_id as "orderId", lp.gate_pass_no as "gatePassNo" from loading_plan_items i
           join order_lines l on l.id = i.order_line_id join loading_plans lp on lp.id = i.plan_id where i.plan_id = $1 order by 1`,
        [plan.id],
      );
      for (const r of rows) await addEvent(client, r.orderId, user.id, 'Left the warehouse', `Gate pass ${r.gatePassNo}`);
      return plan.number;
    });
    await audit(db, { userId: user.id, action: 'loading_plan_out', details: { planId, number } });
    return planDetail(db, user, planId);
  });

  // A plan can be thrown away until the vehicle is loaded.
  app.delete('/loading-plans/:id', { onRequest: auth, preHandler: planners }, async (request) => {
    const { id: planId } = parse(idParams, request.params);
    const user = request.user;
    const number = await inTransaction(db, async (client) => {
      const plan = await lockPlan(client, user, planId);
      if (plan.status !== 'planned') throw notPlanned();
      await client.query('delete from loading_plans where id = $1', [plan.id]);
      return plan.number;
    });
    await audit(db, { userId: user.id, action: 'loading_plan_deleted', details: { planId, number } });
    return { deleted: true };
  });
}
