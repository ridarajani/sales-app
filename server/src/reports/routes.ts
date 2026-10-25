import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { assertCompany, notAllowed, requireAuth, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { CHANNEL_LABELS, CHANNELS, invalid, notFound } from '../lists/common.js';
import { dateText } from '../print/pdf.js';
import { INT4_MAX, parse } from '../users.js';
import { REPORTS } from './catalogue.js';
import { ALL_START, canSee, GROUPS, runReport, type Filters, type Report } from './engine.js';
import { reportPdf, reportXlsx } from './output.js';

const id = z.coerce.number().int().positive().max(INT4_MAX).optional();
const reportQuery = z.object({
  from: z.iso.date('Please pick a start date.').optional(),
  to: z.iso.date('Please pick an end date.').optional(),
  company: z.string().optional(),
  person: id,
  customer: id,
  channel: z.enum(CHANNELS).optional(),
  product: id,
  format: z.enum(['json', 'xlsx', 'pdf']).default('json'),
});
const keyParams = z.object({ key: z.string().max(60) });

// Today in Malawi (UTC+2 all year).
const today = () => new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 10);

function defaultFrom(report: Report, to: string) {
  if (report.defaultFrom === 'all') return ALL_START;
  if (report.defaultFrom === 'today') return to;
  if (report.defaultFrom === 'year') return `${to.slice(0, 4)}-01-01`;
  return `${to.slice(0, 7)}-01`;
}

async function name(db: Db, table: 'users' | 'customers' | 'products', id: number) {
  const label = table === 'products' ? `brand || ' ' || name || ' ' || pack_size` : 'name';
  const { rows } = await db.query<{ label: string }>(`select ${label} as label from ${table} where id = $1`, [id]);
  return rows[0]?.label ?? '';
}

export function reportRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);

  // The reports this person may open, by group, with the filters each one takes, and the staff they can pick.
  app.get('/reports', { onRequest: auth }, async (request) => {
    const user = request.user;
    const groups = GROUPS.map((g) => ({
      key: g.key,
      title: g.title,
      reports: REPORTS.filter((r) => r.group === g.key && canSee(r, user)).map((r) => ({
        key: r.key,
        title: r.title,
        description: r.description,
        filters: user.role === 'salesman' ? r.filters.filter((x) => x !== 'person') : r.filters,
        required: r.required ?? [],
        personLabel: r.personLabel ?? 'Person',
        defaultFrom: r.defaultFrom ?? 'month',
      })),
    })).filter((g) => g.reports.length > 0);
    const { rows: people } =
      user.role === 'salesman'
        ? { rows: [] }
        : await db.query(
            `select u.id, u.name, u.role from users u
             where u.deleted_at is null and exists (select 1 from user_companies uc where uc.user_id = u.id and uc.company_code = any($1))
             order by u.name`,
            [user.companies],
          );
    return { groups, people };
  });

  app.get('/reports/:key', { onRequest: auth }, async (request, reply) => {
    const user: AuthUser = request.user;
    const { key } = parse(keyParams, request.params);
    const report = REPORTS.find((r) => r.key === key);
    if (!report) throw notFound('report');
    if (!canSee(report, user)) throw notAllowed();
    const q = parse(reportQuery, request.query);
    const uses = (f: string) => report.filters.includes(f as never);
    for (const r of report.required ?? []) {
      if (q[r as 'company' | 'customer'] == null) throw invalid(`Please pick the ${r}.`);
    }

    const to = (uses('dates') && q.to) || today();
    const from = (uses('dates') && q.from) || defaultFrom(report, to);
    if (from > to) throw invalid('The start date is after the end date.');
    let companies = user.companies;
    if (uses('company') && q.company) {
      assertCompany(user, q.company);
      companies = [q.company];
    }
    const f: Filters = {
      companies,
      from,
      to,
      // A salesman only ever sees himself.
      person: user.role === 'salesman' ? user.id : uses('person') ? (q.person ?? null) : null,
      customer: uses('customer') ? (q.customer ?? null) : null,
      channel: uses('channel') ? (q.channel ?? null) : null,
      product: uses('product') ? (q.product ?? null) : null,
    };
    const result = await runReport(db, report, f, user);

    // What was picked, in words, for the top of the printout.
    const picked: string[] = [];
    if (uses('dates')) picked.push(from === ALL_START ? `Up to ${dateText(to)}` : `${dateText(from)} to ${dateText(to)}`);
    else picked.push(`As of ${dateText(to)}`);
    if (uses('company')) {
      const { rows } = await db.query<{ name: string }>('select name from companies where code = any($1) order by sort_order', [companies]);
      picked.push(rows.map((r) => r.name).join(', '));
    }
    if (f.person && user.role !== 'salesman') picked.push(`${report.personLabel ?? 'Person'}: ${await name(db, 'users', f.person)}`);
    if (f.customer) picked.push(`Customer: ${await name(db, 'customers', f.customer)}`);
    if (f.channel) picked.push(`Channel: ${CHANNEL_LABELS[f.channel as keyof typeof CHANNEL_LABELS]}`);
    if (f.product) picked.push(`Product: ${await name(db, 'products', f.product)}`);
    const out = { title: report.title, picked: picked.join(' · '), columns: report.columns, ...result };

    if (q.format === 'json') {
      return { key: report.key, group: report.group, description: report.description, from, to, ...out };
    }
    const file = `${report.title} ${from === ALL_START ? '' : `${from} to `}${to}`.replace(/[^A-Za-z0-9 _-]/g, '');
    if (q.format === 'xlsx') {
      return reply
        .type('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
        .header('content-disposition', `attachment; filename="${file}.xlsx"`)
        .send(await reportXlsx(out));
    }
    return reply.type('application/pdf').header('content-disposition', `inline; filename="${file}.pdf"`).send(await reportPdf(out));
  });
}
