import type { FastifyInstance } from 'fastify';
import { requireAuth, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { REPORTS } from './catalogue.js';
import { ALL_START, canSee, runReport, today, type Filters } from './engine.js';

type Tile = { key: string; title: string; value: number; kind: 'money' | 'int'; report: string; from: string; to: string };

// The numbers on the office website's first page. Each comes from a report, so it shows exactly what that
// report would show this person, and opening the tile opens the report with the same dates.
export async function dashboard(db: Db, user: AuthUser) {
  const to = today();
  const monthStart = `${to.slice(0, 7)}-01`;
  const base: Filters = {
    companies: user.companies,
    from: monthStart,
    to,
    person: user.role === 'salesman' ? user.id : null,
    customer: null,
    channel: null,
    product: null,
  };
  const run = async (key: string, from: string) => {
    const report = REPORTS.find((r) => r.key === key)!;
    if (!canSee(report, user)) return null;
    return runReport(db, report, { ...base, from }, user);
  };

  const tiles: Tile[] = [];
  const add = (key: string, title: string, value: number, kind: Tile['kind'], report: string, from: string) =>
    tiles.push({ key, title, value, kind, report, from, to });

  const daily = await run('sales-daily', monthStart);
  if (daily) {
    const todayRow = daily.rows.find((r) => r.day === to);
    add('salesToday', 'Sales today, before VAT', Number(todayRow?.subtotal ?? 0), 'money', 'sales-daily', to);
    add('salesMonth', 'Sales this month, before VAT', daily.totals.subtotal ?? 0, 'money', 'sales-daily', monthStart);
  }
  const pending = await run('orders-pending', monthStart);
  if (pending) add('ordersPending', 'Orders still to deliver', pending.rows.length, 'int', 'orders-pending', monthStart);
  const owed = await run('receivables-outstanding', monthStart);
  if (owed) {
    add('owed', 'Customers owe', owed.totals.owes ?? 0, 'money', 'receivables-outstanding', monthStart);
    add('overdue', 'Of which late', owed.totals.overdue ?? 0, 'money', 'invoices-overdue', ALL_START);
  }
  const held = await run('cash-in-hand', monthStart);
  if (held) add('cashInHand', 'Cash not yet handed over', held.totals.net ?? 0, 'money', 'cash-in-hand', monthStart);
  const handovers = await run('cash-handovers', ALL_START);
  if (handovers) {
    const waiting = handovers.rows.filter((r) => r.status === 'Waiting for the cashier').length;
    add('handoversWaiting', 'Handovers to count', waiting, 'int', 'cash-handovers', ALL_START);
  }
  const low = await run('stock-low', monthStart);
  if (low) add('lowStock', 'Products low on stock', low.rows.length, 'int', 'stock-low', monthStart);
  const adjustments = await run('stock-adjustments', ALL_START);
  if (adjustments && (user.role === 'admin' || user.role === 'management')) {
    const waiting = adjustments.rows.filter((r) => r.state === 'Waiting for approval').length;
    add('adjustmentsWaiting', 'Write-offs to approve', waiting, 'int', 'stock-adjustments', ALL_START);
  }
  const returns = await run('returns', ALL_START);
  if (returns && user.role !== 'salesman') {
    const waiting = returns.rows.filter((r) => r.state === 'Waiting to be booked').length;
    add('returnsToBook', 'Returns to book', waiting, 'int', 'returns', ALL_START);
  }

  return {
    tiles,
    // Each day of this month, for the chart.
    salesByDay: daily ? daily.rows.map((r) => ({ day: r.day as string, subtotal: Number(r.subtotal) })) : null,
  };
}

export function dashboardRoutes(app: FastifyInstance, db: Db) {
  app.get('/dashboard', { onRequest: requireAuth(db) }, async (request) => dashboard(db, request.user));
}
