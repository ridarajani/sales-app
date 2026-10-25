import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { requireAuth, type AuthUser } from '../auth/guard.js';
import { INVOICE_BALANCE } from '../cash/balances.js';
import type { Db } from '../db/pool.js';
import { CHANNEL_LABELS } from '../lists/common.js';
import { PLAN_VIEWERS, visibleWhere as planWhere } from '../loading/loading.js';
import { ORDER_VIEWERS, qtyText, visibleWhere as orderWhere } from '../orders/orders.js';
import { INVOICE_VIEWERS, visibleWhere as invoiceWhere } from '../invoices/invoices.js';
import { RETURN_VIEWERS, invoiceWhere as returnWhere } from '../returns/returns.js';
import { STOCK_VIEWERS } from '../stock/stock.js';
import { TRIP_VIEWERS, visibleWhere as tripWhere } from '../trips/trips.js';
import { parse } from '../users.js';

// Search everywhere: one box that finds customers, orders, invoices, vehicles and delivery notes, trips,
// returns, products and staff. Each group shows what that person may already open, and at most PER_GROUP items.
const PER_GROUP = 8;
const STAFF_VIEWERS = ['admin', 'management', 'sales_manager'];

const searchQuery = z.object({
  q: z.string().trim().min(2, 'Please type at least 2 letters.').max(100),
});

type Item = { id: number; title: string; detail: string };
type Group = { key: string; title: string; count: number; items: Item[] };

const ROLE_WORDS: Record<string, string> = {
  admin: 'Admin',
  management: 'Management',
  sales_manager: 'Sales manager',
  salesman: 'Salesman',
  warehouse: 'Warehouse',
  driver: 'Driver',
  cashier: 'Cashier',
  accounts: 'Accounts',
};
const ORDER_WORDS: Record<string, string> = { open: 'Open', hold: 'On hold', done: 'Done', cancelled: 'Cancelled' };
const PLAN_WORDS: Record<string, string> = { planned: 'Planned', loaded: 'Loaded', out: 'Out for delivery', done: 'Delivered' };
const TRIP_WORDS: Record<string, string> = { planned: 'Planned', out: 'Out', closed: 'Closed' };

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
// 2026-10-25 → 25 Oct 2026.
export const dateText = (d: string) => `${Number(d.slice(8, 10))} ${MONTHS[Number(d.slice(5, 7)) - 1]} ${d.slice(0, 4)}`;
export const moneyText = (n: number) => `MWK ${Math.round(n).toLocaleString('en-US')}`;
// 0991234567 → 0991 234 567; a number from abroad stays as typed.
export const phoneText = (p: string) => (/^0\d{9}$/.test(p) ? `${p.slice(0, 4)} ${p.slice(4, 7)} ${p.slice(7)}` : p);
const join = (...parts: (string | null | undefined)[]) => parts.filter((p) => p).join(' · ');
const has = (roles: readonly string[], user: AuthUser) => roles.includes(user.role);

// What was typed as a phone number, as it is stored: digits, a Malawi number from its 0.
function phoneDigits(q: string): string | null {
  if (!/^[\d\s+\-()]+$/.test(q)) return null;
  let d = q.replace(/\D/g, '');
  if (d.startsWith('00265')) d = d.slice(5);
  else if (d.startsWith('265') && d.length > 3) d = d.slice(3);
  return d.length >= 3 ? d : null;
}

export async function search(db: Db, user: AuthUser, q: string) {
  const like = `%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`;
  const digits = phoneDigits(q);
  const phoneLike = digits ? `%${digits}%` : null;
  // $1 companies, $2 the user's id, $3 the words; $4 the phone digits (or null) where a group looks at phone numbers.
  const groups: Group[] = [];
  const run = async <R extends { id: number; n: number }>(key: string, title: string, sql: string, item: (r: R) => Item) => {
    const params = sql.includes('$4') ? [user.companies, user.id, like, phoneLike] : [user.companies, user.id, like];
    const { rows } = await db.query<R>(sql, params);
    if (rows.length) groups.push({ key, title, count: rows[0]!.n, items: rows.map(item) });
  };

  if (user.role !== 'driver') {
    await run<{ id: number; n: number; name: string; area: string; channel: keyof typeof CHANNEL_LABELS; phone: string | null }>(
      'customers',
      'Customers',
      `select c.id, c.name, c.area, c.channel, c.phone, count(*) over ()::int as n from customers c
       where c.deleted_at is null and $2::int is not null
         and exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($1))
         and (c.name ilike $3 or c.area ilike $3 or c.city ilike $3 or c.phone like $4)
       order by c.name ilike $3 desc, lower(c.name) limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.name, detail: join(r.area, CHANNEL_LABELS[r.channel], r.phone ? phoneText(r.phone) : null) }),
    );
  }

  if (has(ORDER_VIEWERS, user)) {
    await run<{ id: number; n: number; number: string; customer: string; day: string; status: string }>(
      'orders',
      'Orders',
      `select o.id, o.number, c.name as customer, to_char(o.created_at at time zone 'Africa/Blantyre', 'YYYY-MM-DD') as day, o.status,
         count(*) over ()::int as n
       from orders o join customers c on c.id = o.customer_id
       where ${orderWhere(user)}
         and (o.number ilike $3 or c.name ilike $3
           or exists (select 1 from order_companies y where y.order_id = o.id and y.company_code = any($1) and y.lpo_no ilike $3))
       order by o.id desc limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.customer, detail: join(r.number, dateText(r.day), ORDER_WORDS[r.status]) }),
    );
  }

  if (has(INVOICE_VIEWERS, user)) {
    await run<{ id: number; n: number; number: string; customer: string; day: string; total: number; balance: number }>(
      'invoices',
      'Invoices',
      `select iv.id, iv.number, c.name as customer, to_char(iv.invoice_date, 'YYYY-MM-DD') as day, iv.total::float8 as total,
         ${INVOICE_BALANCE('iv')}::float8 as balance, count(*) over ()::int as n
       from invoices iv join customers c on c.id = iv.customer_id
       where ${invoiceWhere(user)} and (iv.number ilike $3 or c.name ilike $3 or iv.lpo_no ilike $3)
       order by iv.id desc limit ${PER_GROUP}`,
      (r) => ({
        id: r.id,
        title: r.customer,
        detail: join(r.number, dateText(r.day), moneyText(r.total), r.balance > 0 ? `${moneyText(r.balance)} still to pay` : 'Paid'),
      }),
    );
  }

  if (has(PLAN_VIEWERS, user)) {
    await run<{ id: number; n: number; number: string; plate: string | null; dn: string | null; day: string; status: string }>(
      'loadingPlans',
      'Vehicles and delivery notes',
      `select lp.id, lp.number, v.plate, lp.delivery_note_no as dn, to_char(lp.created_at at time zone 'Africa/Blantyre', 'YYYY-MM-DD') as day,
         lp.status, count(*) over ()::int as n
       from loading_plans lp left join vehicles v on v.id = lp.vehicle_id
       where ${planWhere(user)}
         and (lp.number ilike $3 or lp.delivery_note_no ilike $3 or lp.gate_pass_no ilike $3 or v.plate ilike $3)
       order by lp.id desc limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.plate ?? 'No vehicle yet', detail: join(r.number, r.dn, dateText(r.day), PLAN_WORDS[r.status]) }),
    );
  }

  if (has(TRIP_VIEWERS, user)) {
    await run<{ id: number; n: number; number: string; route: string; plate: string | null; day: string; status: string }>(
      'trips',
      'Trips',
      `select t.id, t.number, t.route, v.plate, to_char(t.trip_date, 'YYYY-MM-DD') as day, t.status, count(*) over ()::int as n
       from trips t left join vehicles v on v.id = t.vehicle_id
       where ${tripWhere(user)}
         and (t.number ilike $3 or t.route ilike $3 or t.gate_pass_no ilike $3 or v.plate ilike $3)
       order by t.id desc limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.route, detail: join(r.number, r.plate, dateText(r.day), TRIP_WORDS[r.status]) }),
    );
  }

  if (has(RETURN_VIEWERS, user)) {
    const money = user.role !== 'warehouse';
    await run<{ id: number; n: number; number: string; customer: string; invoice: string; day: string; total: number }>(
      'returns',
      'Returns',
      `select r.id, r.number, c.name as customer, iv.number as invoice,
         to_char(r.created_at at time zone 'Africa/Blantyre', 'YYYY-MM-DD') as day, r.total::float8 as total, count(*) over ()::int as n
       from sales_returns r join customers c on c.id = r.customer_id join invoices iv on iv.id = r.invoice_id
       where ${returnWhere(user, 'r')} and (r.number ilike $3 or c.name ilike $3 or iv.number ilike $3)
       order by r.id desc limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.customer, detail: join(r.number, `for ${r.invoice}`, dateText(r.day), money ? moneyText(r.total) : null) }),
    );
  }

  // Everyone may look up a product; only those who see stock see what is free to sell.
  const stock = has(STOCK_VIEWERS, user);
  await run<{ id: number; n: number; code: string; label: string; company: string; free: number; ppc: number }>(
    'products',
    'Products',
    `select p.id, p.code, p.brand || ' ' || p.name || ' ' || p.pack_size as label, co.name as company, p.pieces_per_carton as ppc,
       (select coalesce(sum(b.on_hand - b.in_transit), 0) - coalesce(sum((select sum(a.pieces) from order_allocations a where a.batch_id = b.id)), 0)
        from stock_batches b where b.product_id = p.id)::int as free,
       count(*) over ()::int as n
     from products p join companies co on co.code = p.company_code
     where p.company_code = any($1) and p.deleted_at is null and p.active and $2::int is not null
       and (p.code ilike $3 or p.brand ilike $3 or p.name ilike $3 or p.pack_size ilike $3
         or (p.brand || ' ' || p.name || ' ' || p.pack_size) ilike $3)
     order by co.sort_order, p.brand, p.name, p.pack_size limit ${PER_GROUP}`,
    (r) => ({ id: r.id, title: r.label, detail: join(r.code, r.company, stock ? `${qtyText(Math.max(0, r.free), r.ppc)} free` : null) }),
  );

  if (STAFF_VIEWERS.includes(user.role)) {
    // A sales manager finds only his own salesmen, as on his staff list.
    const mine = user.role === 'sales_manager' ? 'and (u.manager_id = $2 or u.id = $2)' : 'and $2::int is not null';
    await run<{ id: number; n: number; name: string; role: string; phone: string; active: boolean }>(
      'staff',
      'Staff',
      `select u.id, u.name, u.role, u.phone, u.active, count(*) over ()::int as n from users u
       where u.deleted_at is null ${mine}
         and exists (select 1 from user_companies uc where uc.user_id = u.id and uc.company_code = any($1))
         and (u.name ilike $3 or u.phone like $4)
       order by lower(u.name) limit ${PER_GROUP}`,
      (r) => ({ id: r.id, title: r.name, detail: join(ROLE_WORDS[r.role], phoneText(r.phone), r.active ? null : 'Switched off') }),
    );
  }

  return { q, groups };
}

export function searchRoutes(app: FastifyInstance, db: Db) {
  app.get('/search', { onRequest: requireAuth(db) }, async (request) => {
    const { q } = parse(searchQuery, request.query);
    return search(db, request.user, q);
  });
}
