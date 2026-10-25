import type { AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import type { ROLES } from '../users.js';
import { qtyText } from '../orders/orders.js';

// A report is a table: its columns, the filters it takes, who may see it and how its rows are found.

export type Role = (typeof ROLES)[number];
// money: whole kwacha; int: a count; qty: pieces, shown as cartons and pieces using the row's `ppc`;
// percent: a whole number; date: YYYY-MM-DD.
export type Kind = 'text' | 'money' | 'int' | 'qty' | 'percent' | 'date';
export type Column = { key: string; title: string; kind: Kind; total?: boolean };
// dates: from and to. person: a member of staff (forced to themselves for a salesman).
export type FilterKey = 'dates' | 'company' | 'person' | 'customer' | 'channel' | 'product';
export type Row = Record<string, unknown>;

export type Filters = {
  companies: string[];
  from: string;
  to: string;
  person: number | null;
  customer: number | null;
  channel: string | null;
  product: number | null;
};

export type Report = {
  key: string;
  group: string;
  title: string;
  description: string;
  roles: readonly Role[];
  filters: FilterKey[];
  // Filters that must be picked before the report runs.
  required?: FilterKey[];
  // What the person filter is called on the form.
  personLabel?: string;
  // Where the dates start when none are picked: this month (the default), this year, today, or the very start.
  defaultFrom?: 'month' | 'year' | 'today' | 'all';
  columns: Column[];
  // SQL that may use f.companies, f.d_from, f.d_to, f.person, f.customer, f.channel and f.product.
  // It may start with its own "with" list.
  sql?: string;
  run?: (db: Db, f: Filters, user: AuthUser) => Promise<Row[]>;
};

export const GROUPS: { key: string; title: string }[] = [
  { key: 'sales', title: 'Sales' },
  { key: 'orders', title: 'Orders' },
  { key: 'delivery', title: 'Deliveries' },
  { key: 'invoices', title: 'Invoices' },
  { key: 'receivables', title: 'What customers owe' },
  { key: 'cash', title: 'Cash' },
  { key: 'stock', title: 'Stock' },
  { key: 'trips', title: 'Trips' },
  { key: 'control', title: 'Checks' },
];

// The most rows a report gives; more means the dates or filters should be narrowed.
export const MAX_ROWS = 10_000;

// Today in Malawi (UTC+2 all year).
export const today = () => new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 10);

// The start of the dates for a report that covers everything.
export const ALL_START = '2000-01-01';

// A timestamp's day in Malawi.
export const LOCAL = (ts: string) => `(${ts} at time zone 'Africa/Blantyre')::date`;
export const DAY = (d: string) => `to_char(${d}, 'YYYY-MM-DD')`;

const FILTER_CTE = `with f as (select $1::text[] as companies, $2::date as d_from, $3::date as d_to, $4::int as person,
  $5::int as customer, $6::text as channel, $7::int as product)`;

export function canSee(report: Report, user: AuthUser) {
  return report.roles.includes(user.role as Role);
}

export async function runReport(db: Db, report: Report, f: Filters, user: AuthUser) {
  let rows: Row[];
  if (report.run) rows = await report.run(db, f, user);
  else {
    const sql = report.sql!.trimStart();
    const text = sql.startsWith('with ') ? `${FILTER_CTE}, ${sql.slice(5)}` : `${FILTER_CTE} ${sql}`;
    // Small tables give large cost guesses for these queries, and compiling them (JIT) takes far longer than running them.
    const client = await db.connect();
    try {
      await client.query('begin read only');
      await client.query('set local jit = off');
      rows = (await client.query(text, [f.companies, f.from, f.to, f.person, f.customer, f.channel, f.product])).rows;
      await client.query('commit');
    } catch (e) {
      await client.query('rollback');
      throw e;
    } finally {
      client.release();
    }
  }
  const truncated = rows.length > MAX_ROWS;
  if (truncated) rows = rows.slice(0, MAX_ROWS);
  const totals: Record<string, number> = {};
  for (const c of report.columns) {
    if (c.total) totals[c.key] = rows.reduce((t, r) => t + Number(r[c.key] ?? 0), 0);
  }
  // Only the columns go out; quantities as cartons and pieces.
  const shaped = rows.map((r) => {
    const out: Row = {};
    for (const c of report.columns) {
      const v = r[c.key];
      out[c.key] = c.kind === 'qty' && v != null ? qtyText(Number(v), Number(r.ppc ?? 1)) : (v ?? null);
    }
    return out;
  });
  return { rows: shaped, totals, truncated };
}
