import type { FastifyInstance } from 'fastify';
import { requireAuth, type AuthUser } from '../auth/guard.js';
import { CUSTOMER_OWES, INVOICE_BALANCE } from '../cash/balances.js';
import { CASH_COUNTERS } from '../cash/cash.js';
import type { Db } from '../db/pool.js';
import { INVOICE_VIEWERS, visibleWhere as invoiceWhere } from '../invoices/invoices.js';
import { INVOICE_MAKERS, PLANNERS, PLAN_VIEWERS, visibleWhere as planWhere } from '../loading/loading.js';
import { qtyText, visibleWhere as orderWhere } from '../orders/orders.js';
import { REPORTS } from '../reports/catalogue.js';
import { ALL_START, canSee, runReport, today } from '../reports/engine.js';
import { ADJUSTERS, invoiceWhere as returnWhere } from '../returns/returns.js';
import { dateText, moneyText } from '../search/search.js';
import { STOCK_APPROVERS, STOCK_KEEPERS, STOCK_VIEWERS } from '../stock/stock.js';
import { OUTBOX_VIEWERS } from '../trips/outbox.js';
import { visibleWhere as tripWhere } from '../trips/trips.js';

// Alerts: things that need someone's attention now, for the companies they can see. Each says what is wrong in
// plain words and where to go: `screen` (and `id`) for the phone app, `report` for the office website.
// bad: money or stock at risk. warn: work waiting. info: worth knowing.
export type Severity = 'bad' | 'warn' | 'info';
export type Alert = {
  key: string;
  severity: Severity;
  title: string;
  detail: string;
  screen: string;
  id: number | null;
  report: string | null;
};

// Overdue customers shown one by one; the rest are summed up in one more alert.
const MAX_OVERDUE = 15;
const NEAR_EXPIRY_DAYS = 90;
const MONEY_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'];
const WALLET_VIEWERS = ['admin', 'management', 'sales_manager', 'cashier', 'accounts'];
const RANK: Record<Severity, number> = { bad: 0, warn: 1, info: 2 };
const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
const has = (roles: readonly string[], user: AuthUser) => roles.includes(user.role);
// Today in Malawi, as a date the queries compare with.
const TODAY = `(now() at time zone 'Africa/Blantyre')::date`;

export async function alerts(db: Db, user: AuthUser): Promise<Alert[]> {
  const list: Alert[] = [];
  const add = (a: Omit<Alert, 'id' | 'report'> & { id?: number | null; report?: string | null }) =>
    list.push({ id: null, report: null, ...a });
  const ids = [user.companies, user.id];

  // Loading plans: changes the other side made after the plan was sent.
  if (has(PLAN_VIEWERS, user) && (user.role === 'warehouse' || has(PLANNERS, user))) {
    const warehouse = user.role === 'warehouse';
    const { rows } = await db.query<{ id: number; number: string; plate: string | null; changes: number; reasons: string }>(
      `select lp.id, lp.number, v.plate, count(*)::int as changes, string_agg(distinct nullif(ch.reason, ''), '; ') as reasons
       from loading_plans lp left join vehicles v on v.id = lp.vehicle_id join loading_plan_changes ch on ch.plan_id = lp.id
       where ${planWhere(user)} and not ch.seen and ch.by_warehouse = $3 and lp.status <> 'done'
       group by lp.id, lp.number, v.plate order by lp.id`,
      [...ids, !warehouse],
    );
    for (const r of rows) {
      const vehicle = r.plate ?? r.number;
      if (warehouse) {
        add({ key: `plan-changed-${r.id}`, severity: 'warn', title: `The plan for ${vehicle} was changed`, detail: 'After it was sent to you. Check it before loading.', screen: 'loadingPlan', id: r.id });
      } else {
        add({
          key: `plan-warehouse-${r.id}`,
          severity: 'warn',
          title: `The warehouse changed ${plural(r.changes, 'line')} on ${vehicle}`,
          detail: r.reasons ? `Reason: ${r.reasons}` : 'Open the plan to see what changed.',
          screen: 'loadingPlan',
          id: r.id,
        });
      }
    }
  }

  // Loaded vehicles whose invoices are not made yet.
  if (has(INVOICE_MAKERS, user)) {
    const { rows } = await db.query<{ id: number; number: string; plate: string | null; dn: string | null }>(
      `select lp.id, lp.number, v.plate, lp.delivery_note_no as dn from loading_plans lp left join vehicles v on v.id = lp.vehicle_id
       where ${planWhere(user)} and lp.status in ('loaded', 'out')
         and exists (select 1 from loading_plan_items i join order_lines l on l.id = i.order_line_id join products p on p.id = l.product_id
           where i.plan_id = lp.id and i.loaded > 0 and p.company_code = any($1::text[])
           and not exists (select 1 from invoice_lines il where il.plan_item_id = i.id))
       order by lp.id`,
      ids,
    );
    for (const r of rows) {
      add({
        key: `plan-invoices-${r.id}`,
        severity: 'warn',
        title: `Make the invoices for ${r.plate ?? r.number}`,
        detail: `${r.dn ?? r.number} is loaded. The driver waits for the invoices.`,
        screen: 'loadingPlan',
        id: r.id,
      });
    }
  }

  // Vehicles that left before today and whose deliveries are not all confirmed.
  if (has(PLANNERS, user) || user.role === 'driver') {
    const { rows } = await db.query<{ id: number; number: string; plate: string | null; dn: string | null }>(
      `select lp.id, lp.number, v.plate, lp.delivery_note_no as dn from loading_plans lp left join vehicles v on v.id = lp.vehicle_id
       where ${planWhere(user)} and lp.status = 'out' and (lp.out_at at time zone 'Africa/Blantyre')::date < ${TODAY}
       order by lp.id`,
      ids,
    );
    for (const r of rows) {
      add({
        key: `plan-out-${r.id}`,
        severity: 'warn',
        title: `${r.dn ?? r.number}: deliveries not confirmed`,
        detail: `${r.plate ?? 'The vehicle'} left before today.`,
        screen: 'loadingPlan',
        id: r.id,
        report: 'deliveries-pending',
      });
    }
  }

  // Orders booked before today that still wait for a vehicle.
  if (has(PLANNERS, user)) {
    const { rows } = await db.query<{ n: number }>(
      `select count(*)::int as n from orders o
       where ${orderWhere(user)} and o.status = 'open' and (o.created_at at time zone 'Africa/Blantyre')::date < ${TODAY}
         and exists (select 1 from order_lines l where l.order_id = o.id and l.pieces - l.delivered - l.on_vehicle > 0)
         and not exists (select 1 from trip_orders tro join trips t on t.id = tro.trip_id where tro.order_id = o.id and t.status <> 'closed')`,
      ids,
    );
    const n = rows[0]!.n;
    if (n) {
      add({ key: 'orders-waiting', severity: 'warn', title: `${plural(n, 'order')} from before today not on a vehicle`, detail: 'Put them on a loading plan.', screen: 'loading', report: 'orders-pending' });
    }
  }

  // Returns: to book in the office, and goods to receive in the warehouse.
  if (has(ADJUSTERS, user)) {
    const { rows } = await db.query<{ n: number; total: number }>(
      `select count(*)::int as n, coalesce(sum(r.total), 0)::float8 as total from sales_returns r
       where ${returnWhere(user, 'r')} and r.booked_at is null`,
      ids,
    );
    const { n, total } = rows[0]!;
    if (n) add({ key: 'returns-to-book', severity: 'warn', title: `${plural(n, 'sales return')} to book`, detail: `${moneyText(total)} of goods not taken or brought back.`, screen: 'returns', report: 'returns' });
  }
  if (has(STOCK_KEEPERS, user)) {
    const { rows } = await db.query<{ n: number }>(
      `select count(*)::int as n from sales_returns r where ${returnWhere(user, 'r')} and r.goods = 'waiting'`,
      ids,
    );
    const n = rows[0]!.n;
    if (n) add({ key: 'returns-to-receive', severity: 'warn', title: `${plural(n, 'return')} coming back to the warehouse`, detail: 'Tick each one when the goods are back.', screen: 'returns' });
  }

  // Airtel Money payments not yet checked against the wallet. A sales manager sees his own wallet.
  if (has(WALLET_VIEWERS, user)) {
    const own = user.role === 'sales_manager' ? 'and w.holder_id = $2' : 'and $2::int is not null';
    const { rows } = await db.query<{ n: number; total: number }>(
      `select count(*)::int as n, coalesce(sum(e.credit), 0)::float8 as total
       from airtel_entries e join airtel_wallets w on w.id = e.wallet_id
       where e.company_code = any($1) ${own} and e.status = 'waiting'`,
      ids,
    );
    const { n, total } = rows[0]!;
    if (n) add({ key: 'airtel-waiting', severity: 'warn', title: `${plural(n, 'Airtel Money payment')} not yet ticked`, detail: `${moneyText(total)}. Check the wallet and tick them.`, screen: 'airtel' });
  }

  // Customers who are late paying, biggest first. A salesman sees his own invoices only.
  if (has(INVOICE_VIEWERS, user) && has(MONEY_VIEWERS, user)) {
    const { rows } = await db.query<{ customerId: number; customer: string; company: string; late: number; oldest: string }>(
      `select c.id as "customerId", c.name as customer, co.name as company, sum(x.balance)::float8 as late,
         to_char(min(x.due_date), 'YYYY-MM-DD') as oldest
       from (select iv.customer_id, iv.company_code, iv.due_date, ${INVOICE_BALANCE('iv')} as balance
             from invoices iv where ${invoiceWhere(user)} and iv.due_date < ${TODAY}) x
         join customers c on c.id = x.customer_id join companies co on co.code = x.company_code
       where x.balance > 0
       group by c.id, c.name, co.code, co.name order by late desc, c.name`,
      ids,
    );
    for (const r of rows.slice(0, MAX_OVERDUE)) {
      add({
        key: `overdue-${r.customerId}-${r.company}`,
        severity: 'bad',
        title: `${r.customer} owes ${moneyText(r.late)} late`,
        detail: `${r.company} · due since ${dateText(r.oldest)}`,
        screen: 'customer',
        id: r.customerId,
        report: 'invoices-overdue',
      });
    }
    const rest = rows.slice(MAX_OVERDUE);
    if (rest.length) {
      add({
        key: 'overdue-more',
        severity: 'bad',
        title: `${plural(rest.length, 'more customer')} late paying`,
        detail: `${moneyText(rest.reduce((t, r) => t + r.late, 0))} in all. See the overdue invoices report.`,
        screen: 'reports',
        report: 'invoices-overdue',
      });
    }
  }

  // Customers who owe a company more than the limit set for them.
  if (['admin', 'management', 'sales_manager', 'cashier', 'accounts'].includes(user.role)) {
    const { rows } = await db.query<{ customerId: number; customer: string; company: string; owes: number; limit: number }>(
      `select * from (
         select c.id as "customerId", c.name as customer, co.name as company, ${CUSTOMER_OWES('c.id', 'cc.company_code')}::float8 as owes,
           cc.credit_limit::float8 as "limit"
         from customer_companies cc join customers c on c.id = cc.customer_id join companies co on co.code = cc.company_code
         where cc.company_code = any($1) and $2::int is not null and cc.credit_limit is not null and c.deleted_at is null) x
       where x.owes > x."limit" order by x.owes - x."limit" desc, x.customer`,
      ids,
    );
    for (const r of rows) {
      add({
        key: `credit-${r.customerId}-${r.company}`,
        severity: 'warn',
        title: `${r.customer} is over the credit limit`,
        detail: `${r.company} · owes ${moneyText(r.owes)}, limit ${moneyText(r.limit)}`,
        screen: 'customer',
        id: r.customerId,
        report: 'credit-limits',
      });
    }
  }

  // Cash not yet handed to the cashier, from the cash in hand report so the amounts match it.
  const inHand = REPORTS.find((r) => r.key === 'cash-in-hand')!;
  if (canSee(inHand, user)) {
    const { rows } = await runReport(
      db,
      inHand,
      { companies: user.companies, from: ALL_START, to: today(), person: user.role === 'salesman' ? user.id : null, customer: null, channel: null, product: null },
      user,
    );
    const byPerson = new Map<string, number>();
    for (const r of rows) byPerson.set(r.person as string, (byPerson.get(r.person as string) ?? 0) + Number(r.net));
    for (const [person, net] of byPerson) {
      if (net <= 0) continue;
      const self = person === user.name && (user.role === 'salesman' || user.role === 'sales_manager' || user.role === 'accounts');
      add({
        key: `cash-${person}`,
        severity: 'warn',
        title: self ? `You hold ${moneyText(net)} cash` : `${person} holds ${moneyText(net)} cash`,
        detail: self ? 'Hand it to the cashier.' : 'Not yet handed to the cashier.',
        screen: 'cash',
        report: 'cash-in-hand',
      });
    }
  }

  // Handovers the cashier has not counted yet.
  if (has(CASH_COUNTERS, user)) {
    const { rows } = await db.query<{ n: number; total: number }>(
      `select count(*)::int as n, coalesce(sum(h.net), 0)::float8 as total from handovers h
       where h.status = 'handed' and $2::int is not null
         and exists (select 1 from user_companies uc where uc.user_id = h.handed_by and uc.company_code = any($1))`,
      ids,
    );
    const { n, total } = rows[0]!;
    if (n) add({ key: 'handovers-waiting', severity: 'warn', title: `${plural(n, 'cash handover')} to count`, detail: `${moneyText(total)} handed in.`, screen: 'handovers', report: 'cash-handovers' });
  }

  // Stock: below its low-stock level (from the low stock report), expired and close to expiry.
  const low = REPORTS.find((r) => r.key === 'stock-low')!;
  if (canSee(low, user)) {
    const { rows } = await runReport(
      db,
      low,
      { companies: user.companies, from: today(), to: today(), person: null, customer: null, channel: null, product: null },
      user,
    );
    for (const r of rows) {
      add({ key: `low-${r.code}`, severity: 'warn', title: `Low stock: ${r.product}`, detail: `${r.free} free to sell, level ${r.level}.`, screen: 'stock', report: 'stock-low' });
    }
  }
  if (has(STOCK_VIEWERS, user)) {
    const { rows } = await db.query<{ id: number; batch: string; product: string; onHand: number; ppc: number; expiry: string; daysLeft: number }>(
      `select b.id, b.batch_no as batch, p.brand || ' ' || p.name || ' ' || p.pack_size as product, (b.on_hand - b.in_transit)::int as "onHand",
         p.pieces_per_carton as ppc, to_char(b.expiry_date, 'YYYY-MM-DD') as expiry, (b.expiry_date - ${TODAY})::int as "daysLeft"
       from stock_batches b join products p on p.id = b.product_id
       where p.company_code = any($1) and $2::int is not null and b.on_hand - b.in_transit > 0 and b.expiry_date <= ${TODAY} + ${NEAR_EXPIRY_DAYS}
       order by b.expiry_date, p.name`,
      ids,
    );
    for (const r of rows.filter((x) => x.daysLeft < 0)) {
      add({
        key: `expired-${r.id}`,
        severity: 'bad',
        title: `Expired: ${r.product}, batch ${r.batch}`,
        detail: `${qtyText(r.onHand, r.ppc)} still in the warehouse. It cannot be sold; write it off.`,
        screen: 'stock',
      });
    }
    const near = rows.filter((x) => x.daysLeft >= 0);
    if (near.length) {
      const first = near[0]!;
      add({
        key: 'near-expiry',
        severity: 'info',
        title: `${plural(near.length, 'batch', 'batches')} ${near.length === 1 ? 'expires' : 'expire'} within ${NEAR_EXPIRY_DAYS} days`,
        detail: `First: ${first.product}, batch ${first.batch}, on ${dateText(first.expiry)}.`,
        screen: 'shortDated',
      });
    }
  }

  // Stock write-offs waiting for approval.
  if (has(STOCK_APPROVERS, user)) {
    const { rows } = await db.query<{ n: number }>(
      `select count(*)::int as n from stock_adjustment_requests r join stock_batches b on b.id = r.batch_id join products p on p.id = b.product_id
       where p.company_code = any($1) and $2::int is not null and r.status = 'pending'`,
      ids,
    );
    const n = rows[0]!.n;
    if (n) add({ key: 'adjustments-waiting', severity: 'warn', title: `${plural(n, 'stock change')} to approve`, detail: 'Damaged, expired or lost stock waiting for you.', screen: 'approvals', report: 'stock-adjustments' });
  }

  // Trip sales a phone could not read back: Admin looks at them; the trip leader is told the office is on it.
  if (has(OUTBOX_VIEWERS, user)) {
    const { rows } = await db.query<{ id: number; trip: string; route: string; leader: string }>(
      `select p.id, t.number as trip, t.route, l.name as leader from outbox_problems p join trips t on t.id = p.trip_id
         join users l on l.id = t.leader_id
       where t.company_code = any($1) and $2::int is not null and p.cleared_at is null order by p.id`,
      ids,
    );
    for (const r of rows) {
      add({
        key: `outbox-${r.id}`,
        severity: 'bad',
        title: `Sales on ${r.leader}'s phone could not be read`,
        detail: `Trip ${r.trip}, ${r.route}. Look at them, enter what is missing and clear it. The trip cannot close until then.`,
        screen: 'outboxProblem',
        id: r.id,
      });
    }
  } else {
    const { rows } = await db.query<{ id: number; trip: string }>(
      `select distinct t.id, t.number as trip from outbox_problems p join trips t on t.id = p.trip_id
       where ${tripWhere(user)} and t.leader_id = $2 and p.cleared_at is null order by t.id`,
      ids,
    );
    for (const r of rows) {
      add({ key: `outbox-trip-${r.id}`, severity: 'warn', title: `The office is checking sales on trip ${r.trip}`, detail: 'Your phone could not read some sales. You can close the trip once the office has cleared them.', screen: 'trip', id: r.id });
    }
  }

  return list.sort((a, b) => RANK[a.severity] - RANK[b.severity]);
}

export function alertRoutes(app: FastifyInstance, db: Db) {
  app.get('/alerts', { onRequest: requireAuth(db) }, async (request) => {
    const list = await alerts(db, request.user);
    return {
      alerts: list,
      counts: { bad: list.filter((a) => a.severity === 'bad').length, warn: list.filter((a) => a.severity === 'warn').length, info: list.filter((a) => a.severity === 'info').length },
    };
  });
}
