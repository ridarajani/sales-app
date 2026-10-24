import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { readImage, saveImage } from '../files.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { nextNumber, nextSharedNumber } from '../numbering.js';
import { inTransaction } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';
import { addAirtelExpense } from './airtel.js';

// Those who may spend company money in the field and hand cash to the cashier.
export const EXPENSE_MAKERS = ['admin', 'sales_manager', 'salesman'] as const;
export const CASH_HOLDERS = ['admin', 'sales_manager', 'salesman', 'accounts'] as const;
// The cashier counts handed-over cash; Admin can stand in.
export const CASH_COUNTERS = ['admin', 'cashier'] as const;
// See every handover and expense of their companies; everyone else sees their own.
const CASH_OVERSEERS = ['admin', 'management', 'cashier', 'accounts'];
const PHOTO_MAX = 8 * 1024 * 1024;
const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;

const kwacha = z.number('Please type the amount.').int('Amounts are whole kwacha.').positive('Please type the amount.').max(1_000_000_000);
const expenseBody = z.object({
  companyCode: z.string(),
  amount: kwacha,
  description: z.string('Please say what it was for.').trim().min(1, 'Please say what it was for.').max(300),
  mode: z.enum(['cash', 'airtel'], 'Please pick cash or Airtel Money.').default('cash'),
  photo: z.string().max(12_000_000).optional(),
  // One id per form on the phone, so a retry after a lost answer does not record the expense twice.
  requestId: z.string().trim().min(1).max(64).optional(),
});
const countBody = z.object({
  counted: z.number('Please type the cash you counted.').int('Amounts are whole kwacha.').min(0).max(10_000_000_000),
  note: z.string().trim().max(1000).default(''),
});
const expenseQuery = z.object({
  company: z.string().optional(),
  from: z.iso.date().optional(),
  to: z.iso.date().optional(),
});
const handoverQuery = z.object({ status: z.enum(['handed', 'counted']).optional() });
const expenseSelect = `select e.id, e.number, e.company_code as "companyCode", e.amount::float8 as amount, e.description, e.mode,
   e.photo_file is not null as "hasPhoto", u.name as "spentBy", e.custody, h.number as "handoverNumber", e.created_at as "createdAt"
   from expenses e join users u on u.id = e.spent_by left join handovers h on h.id = e.handover_id`;
const sees = (user: AuthUser) => CASH_OVERSEERS.includes(user.role);

// Expenses a user may see. $1 = their companies, $2 = their id.
const expenseWhere = (user: AuthUser) =>
  sees(user) ? 'e.company_code = any($1::text[]) and $2::int is not null' : 'e.company_code = any($1::text[]) and e.spent_by = $2::int';
// Handovers a user may see: their own, or any that holds money of their companies.
const handoverWhere = (user: AuthUser) =>
  sees(user)
    ? `$2::int is not null and (exists (select 1 from collections c where c.handover_id = h.id and c.company_code = any($1::text[]))
         or exists (select 1 from expenses e where e.handover_id = h.id and e.company_code = any($1::text[]))
         or exists (select 1 from airtel_entries a where a.handover_id = h.id and a.company_code = any($1::text[]))
         or exists (select 1 from trips t where t.float_handover_id = h.id and t.company_code = any($1::text[])))`
    : '$1::text[] is not null and h.handed_by = $2::int';

// Cash a person holds, per company: what they collected, took out of an Airtel wallet and the float of their
// trips, less what they spent, not yet handed over.
async function cashHeld(db: Db, userId: number) {
  const { rows: collections } = await db.query(
    `select c.id, c.number, c.company_code as "companyCode", cu.name as "customerName", c.amount::float8 as amount, c.created_at as "createdAt"
     from collections c join customers cu on cu.id = c.customer_id
     where c.collected_by = $1 and c.custody = 'with' order by c.id`,
    [userId],
  );
  const { rows: expenses } = await db.query(
    `select e.id, e.number, e.company_code as "companyCode", e.description, e.amount::float8 as amount, e.photo_file is not null as "hasPhoto",
       e.created_at as "createdAt"
     from expenses e where e.spent_by = $1 and e.custody = 'with' order by e.id`,
    [userId],
  );
  const { rows: withdrawals } = await db.query(
    `select a.id, a.company_code as "companyCode", a.debit::float8 as amount, a.created_at as "createdAt"
     from airtel_entries a where a.created_by = $1 and a.custody = 'with' order by a.id`,
    [userId],
  );
  const { rows: floats } = await db.query(
    `select t.id, t.number, t.company_code as "companyCode", t.float_amount::float8 as amount, t.loaded_at as "createdAt"
     from trips t where t.leader_id = $1 and t.float_custody = 'with' order by t.id`,
    [userId],
  );
  const codes = [...new Set([...collections, ...expenses, ...withdrawals, ...floats].map((x) => x.companyCode as string))].sort();
  const sum = (list: { amount: number; companyCode: string }[], code?: string) =>
    list.filter((x) => !code || x.companyCode === code).reduce((t, x) => t + x.amount, 0);
  const companies = codes.map((code) => ({
    code,
    cash: sum(collections, code),
    withdrawals: sum(withdrawals, code),
    floats: sum(floats, code),
    expenses: sum(expenses, code),
    net: sum(collections, code) + sum(withdrawals, code) + sum(floats, code) - sum(expenses, code),
  }));
  const cash = sum(collections);
  const taken = sum(withdrawals);
  const floated = sum(floats);
  const spent = sum(expenses);
  return {
    collections,
    withdrawals,
    floats,
    expenses,
    companies,
    cash,
    withdrawalsTotal: taken,
    floatsTotal: floated,
    expensesTotal: spent,
    net: cash + taken + floated - spent,
  };
}

async function handoverDetail(db: Db, user: AuthUser, handoverId: number) {
  const { rows } = await db.query(
    `select h.id, h.number, h.handed_by as "handedById", hb.name as "handedBy", h.cash::float8 as cash, h.withdrawals::float8 as withdrawals,
       h.floats::float8 as floats,
       h.expenses::float8 as expenses, h.net::float8 as net, h.status, h.counted::float8 as counted, h.difference::float8 as difference, h.note,
       cb.name as "countedBy", h.counted_at as "countedAt", h.created_at as "createdAt",
       coalesce((select json_agg(json_build_object('id', c.id, 'number', c.number, 'companyCode', c.company_code, 'customerName', cu.name,
           'amount', c.amount::float8) order by c.id)
         from collections c join customers cu on cu.id = c.customer_id where c.handover_id = h.id), '[]') as collections,
       coalesce((select json_agg(json_build_object('id', e.id, 'number', e.number, 'companyCode', e.company_code, 'description', e.description,
           'amount', e.amount::float8, 'hasPhoto', e.photo_file is not null) order by e.id)
         from expenses e where e.handover_id = h.id), '[]') as "expenseList",
       coalesce((select json_agg(json_build_object('id', a.id, 'companyCode', a.company_code, 'amount', a.debit::float8) order by a.id)
         from airtel_entries a where a.handover_id = h.id), '[]') as "withdrawalList",
       coalesce((select json_agg(json_build_object('id', t.id, 'number', t.number, 'companyCode', t.company_code, 'amount', t.float_amount::float8)
           order by t.id)
         from trips t where t.float_handover_id = h.id), '[]') as "floatList"
     from handovers h join users hb on hb.id = h.handed_by left join users cb on cb.id = h.counted_by
     where h.id = $3 and ${handoverWhere(user)}`,
    [user.companies, user.id, handoverId],
  );
  const h = rows[0];
  if (!h) return null;
  // One total for the cashier; behind it each company's money is kept apart.
  const byCompany = new Map<string, { code: string; cash: number; withdrawals: number; floats: number; expenses: number; net: number }>();
  const row = (code: string) =>
    byCompany.get(code) ?? byCompany.set(code, { code, cash: 0, withdrawals: 0, floats: 0, expenses: 0, net: 0 }).get(code)!;
  for (const c of h.collections) row(c.companyCode).cash += c.amount;
  for (const a of h.withdrawalList) row(a.companyCode).withdrawals += a.amount;
  for (const t of h.floatList) row(t.companyCode).floats += t.amount;
  for (const e of h.expenseList) row(e.companyCode).expenses += e.amount;
  for (const r of byCompany.values()) r.net = r.cash + r.withdrawals + r.floats - r.expenses;
  return {
    ...h,
    companies: [...byCompany.values()].sort((a, b) => a.code.localeCompare(b.code)),
    canCount: CASH_COUNTERS.includes(user.role as (typeof CASH_COUNTERS)[number]) && h.status === 'handed' && h.handedById !== user.id,
  };
}

export function cashRoutes(app: FastifyInstance, db: Db, uploadDir: string) {
  const auth = requireAuth(db);
  const makers = requireRole(...EXPENSE_MAKERS);
  const holders = requireRole(...CASH_HOLDERS);
  const counters = requireRole(...CASH_COUNTERS);
  const viewers = requireRole('admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts');

  // Money spent for a company. Cash expenses come off the next handover.
  app.post('/expenses', { onRequest: auth, preHandler: makers, bodyLimit: 16 * 1024 * 1024 }, async (request, reply) => {
    const body = parse(expenseBody, request.body);
    const user = request.user;
    assertCompany(user, body.companyCode);
    const earlier = async () => {
      if (!body.requestId) return null;
      const { rows } = await db.query(`${expenseSelect} where e.spent_by = $1 and e.request_id = $2`, [user.id, body.requestId]);
      return rows[0] ?? null;
    };
    const repeat = await earlier();
    if (repeat) return reply.code(200).send(repeat);
    const photoFile = body.photo ? await saveImage(uploadDir, body.photo, PHOTO_MAX, 'photo') : null;
    const saving = inTransaction(db, async (client) => {
      const number = await nextNumber(client, body.companyCode, 'EXP');
      const { rows } = await client.query<{ id: number }>(
        `insert into expenses (number, company_code, amount, description, mode, photo_file, spent_by, custody, request_id)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9) returning id`,
        [number, body.companyCode, body.amount, body.description, body.mode, photoFile, user.id, body.mode === 'cash' ? 'with' : 'na',
          body.requestId ?? null],
      );
      if (body.mode === 'airtel') {
        await addAirtelExpense(client, { companyCode: body.companyCode, amount: body.amount, description: body.description, expenseId: rows[0]!.id, user });
      }
      return { id: rows[0]!.id, number };
    });
    let saved: Awaited<typeof saving>;
    try {
      saved = await saving;
    } catch (err) {
      const again = (err as { code?: string }).code === '23505' ? await earlier() : null;
      if (again) return reply.code(200).send(again);
      throw err;
    }
    await audit(db, { userId: user.id, action: 'expense_recorded', details: { expenseId: saved.id, number: saved.number, amount: body.amount } });
    const { rows } = await db.query(`${expenseSelect} where e.id = $1`, [saved.id]);
    return reply.code(201).send(rows[0]);
  });


  app.get('/expenses', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(expenseQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = expenseWhere(user);
    if (query.company) {
      params.push(query.company);
      where += ` and e.company_code = $${params.length}`;
    }
    if (query.from) {
      params.push(query.from);
      where += ` and (e.created_at at time zone 'Africa/Blantyre')::date >= $${params.length}::date`;
    }
    if (query.to) {
      params.push(query.to);
      where += ` and (e.created_at at time zone 'Africa/Blantyre')::date <= $${params.length}::date`;
    }
    const { rows } = await db.query(`${expenseSelect} where ${where} order by e.id desc limit 500`, params);
    return rows;
  });

  app.get('/expenses/:id/photo', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id: expenseId } = parse(idParams, request.params);
    const user = request.user;
    const { rows } = await db.query<{ file: string | null }>(
      `select e.photo_file as file from expenses e where e.id = $3 and ${expenseWhere(user)}`,
      [user.companies, user.id, expenseId],
    );
    const image = rows[0]?.file ? await readImage(uploadDir, rows[0].file) : null;
    if (!image) throw notFound('photo');
    return reply.type(image.type).header('cache-control', 'private, max-age=86400').send(image.bytes);
  });

  // The cash this user holds and has not handed over yet.
  app.get('/cash/mine', { onRequest: auth, preHandler: holders }, async (request) => cashHeld(db, request.user.id));

  // Hand everything held to the cashier: one total, cash collected less cash expenses.
  // The cash stays the collector's responsibility until the cashier counts it.
  app.post('/handovers', { onRequest: auth, preHandler: holders }, async (request, reply) => {
    const user = request.user;
    const saved = await inTransaction(db, async (client) => {
      // Lock this user's open cash, so a payment recorded at the same moment is in this handover or the next, never both.
      const { rows: cols } = await client.query<{ id: number; amount: number }>(
        `select id, amount::float8 as amount from collections where collected_by = $1 and custody = 'with' order by id for update`,
        [user.id],
      );
      const { rows: exps } = await client.query<{ id: number; amount: number }>(
        `select id, amount::float8 as amount from expenses where spent_by = $1 and custody = 'with' order by id for update`,
        [user.id],
      );
      const { rows: wds } = await client.query<{ id: number; amount: number }>(
        `select id, debit::float8 as amount from airtel_entries where created_by = $1 and custody = 'with' order by id for update`,
        [user.id],
      );
      const { rows: fls } = await client.query<{ id: number; amount: number }>(
        `select id, float_amount::float8 as amount from trips where leader_id = $1 and float_custody = 'with' order by id for update`,
        [user.id],
      );
      if (cols.length === 0 && exps.length === 0 && wds.length === 0 && fls.length === 0) {
        throw new HttpError(409, 'nothing_to_hand_over', 'You have no cash to hand over.');
      }
      const cash = cols.reduce((t, c) => t + c.amount, 0);
      const taken = wds.reduce((t, w) => t + w.amount, 0);
      const floated = fls.reduce((t, f) => t + f.amount, 0);
      const spent = exps.reduce((t, e) => t + e.amount, 0);
      const net = cash + taken + floated - spent;
      const number = await nextSharedNumber(client, 'HO');
      const { rows } = await client.query<{ id: number }>(
        `insert into handovers (number, handed_by, cash, withdrawals, floats, expenses, net) values ($1, $2, $3, $4, $5, $6, $7) returning id`,
        [number, user.id, cash, taken, floated, spent, net],
      );
      const handoverId = rows[0]!.id;
      await client.query(`update collections set custody = 'handed', handover_id = $2 where id = any($1)`, [cols.map((c) => c.id), handoverId]);
      await client.query(`update expenses set custody = 'handed', handover_id = $2 where id = any($1)`, [exps.map((e) => e.id), handoverId]);
      await client.query(`update airtel_entries set custody = 'handed', handover_id = $2 where id = any($1)`, [wds.map((w) => w.id), handoverId]);
      await client.query(`update trips set float_custody = 'handed', float_handover_id = $2 where id = any($1)`, [fls.map((f) => f.id), handoverId]);
      return { id: handoverId, number, net };
    });
    await audit(db, { userId: user.id, action: 'cash_handed_over', details: { handoverId: saved.id, number: saved.number, net: saved.net } });
    return reply.code(201).send(await handoverDetail(db, user, saved.id));
  });

  app.get('/handovers', { onRequest: auth, preHandler: viewers }, async (request) => {
    const query = parse(handoverQuery, request.query);
    const user = request.user;
    const params: unknown[] = [user.companies, user.id];
    let where = handoverWhere(user);
    if (query.status) {
      params.push(query.status);
      where += ` and h.status = $${params.length}`;
    }
    const { rows } = await db.query(
      `select h.id, h.number, hb.name as "handedBy", h.cash::float8 as cash, h.withdrawals::float8 as withdrawals, h.floats::float8 as floats,
         h.expenses::float8 as expenses, h.net::float8 as net,
         h.status, h.counted::float8 as counted, h.difference::float8 as difference, h.created_at as "createdAt", h.counted_at as "countedAt"
       from handovers h join users hb on hb.id = h.handed_by
       where ${where} order by h.status = 'handed' desc, h.id desc limit 300`,
      params,
    );
    return rows;
  });

  app.get('/handovers/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: handoverId } = parse(idParams, request.params);
    const handover = await handoverDetail(db, request.user, handoverId);
    if (!handover) throw notFound('handover');
    return handover;
  });

  // The cashier counts the cash and types what was there. A difference is kept and shown, never hidden.
  app.post('/handovers/:id/count', { onRequest: auth, preHandler: counters }, async (request) => {
    const { id: handoverId } = parse(idParams, request.params);
    const body = parse(countBody, request.body);
    const user = request.user;
    const result = await inTransaction(db, async (client) => {
      const { rows } = await client.query<{ id: number; number: string; net: number; status: string; handedBy: number }>(
        `select h.id, h.number, h.net::float8 as net, h.status, h.handed_by as "handedBy" from handovers h
         where h.id = $3 and ${handoverWhere(user)} for update of h`,
        [user.companies, user.id, handoverId],
      );
      const h = rows[0];
      if (!h) throw notFound('handover');
      if (h.status === 'counted') throw new HttpError(409, 'already_counted', 'This cash is already counted.');
      if (h.handedBy === user.id) throw new HttpError(403, 'not_allowed', 'Someone else must count the cash you handed over.');
      const difference = body.counted - h.net;
      if (difference !== 0 && !body.note) {
        throw invalid(`The count is ${money(Math.abs(difference))} ${difference < 0 ? 'short' : 'over'}. Please write a note.`);
      }
      await client.query(
        `update handovers set status = 'counted', counted = $2, difference = $3, note = $4, counted_by = $5, counted_at = now() where id = $1`,
        [h.id, body.counted, difference, body.note, user.id],
      );
      await client.query(`update collections set custody = 'counted' where handover_id = $1`, [h.id]);
      await client.query(`update expenses set custody = 'counted' where handover_id = $1`, [h.id]);
      await client.query(`update airtel_entries set custody = 'counted' where handover_id = $1`, [h.id]);
      await client.query(`update trips set float_custody = 'counted' where float_handover_id = $1`, [h.id]);
      return { number: h.number, difference };
    });
    await audit(db, {
      userId: user.id,
      action: 'cash_counted',
      details: { handoverId, number: result.number, counted: body.counted, difference: result.difference },
    });
    return handoverDetail(db, user, handoverId);
  });
}
