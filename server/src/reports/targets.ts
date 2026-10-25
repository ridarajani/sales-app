import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { notFound } from '../lists/common.js';
import { INT4_MAX, parse } from '../users.js';

// Admin, Management and sales managers set each salesman's monthly target (in kwacha, before VAT).
export const TARGET_SETTERS = ['admin', 'management', 'sales_manager'] as const;

const month = z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/, 'Please pick the month.');
const monthQuery = z.object({ month });
const targetBody = z.object({
  salesmanId: z.number().int().positive().max(INT4_MAX),
  month,
  amount: z.number('Please type the target.').int('Amounts are whole kwacha.').min(0).max(100_000_000_000).nullable(),
});

// Salesmen whose target this person may set: in their companies, and for a sales manager only his own.
// $1 = their companies, $2 = their id, $3 = whether they are a sales manager.
const SALESMEN = `select u.id, u.name,
    (select array_agg(uc.company_code order by co.sort_order) from user_companies uc join companies co on co.code = uc.company_code
     where uc.user_id = u.id) as companies
  from users u
  where u.role = 'salesman' and u.deleted_at is null
    and exists (select 1 from user_companies uc where uc.user_id = u.id and uc.company_code = any($1::text[]))
    and (not $3::boolean or u.manager_id = $2::int or exists (select 1 from user_companies x where x.user_id = u.id and x.manager_id = $2::int))`;

const scope = (user: AuthUser) => [user.companies, user.id, user.role === 'sales_manager'];

export function targetRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const setters = requireRole(...TARGET_SETTERS);

  app.get('/targets', { onRequest: auth, preHandler: setters }, async (request) => {
    const q = parse(monthQuery, request.query);
    const { rows } = await db.query(
      `select s.id as "salesmanId", s.name, s.companies, t.amount::float8 as amount
       from (${SALESMEN}) s left join sales_targets t on t.salesman_id = s.id and t.month = ($4 || '-01')::date
       order by s.name`,
      [...scope(request.user), q.month],
    );
    return rows;
  });

  // Sets one salesman's target for a month; no amount takes it away.
  app.put('/targets', { onRequest: auth, preHandler: setters }, async (request) => {
    const body = parse(targetBody, request.body);
    const { rows } = await db.query(`${SALESMEN} and u.id = $4`, [...scope(request.user), body.salesmanId]);
    if (!rows[0]) throw notFound('salesman');
    const first = `${body.month}-01`;
    if (body.amount === null) await db.query('delete from sales_targets where salesman_id = $1 and month = $2', [body.salesmanId, first]);
    else {
      await db.query(
        `insert into sales_targets (salesman_id, month, amount, set_by) values ($1, $2, $3, $4)
         on conflict (salesman_id, month) do update set amount = excluded.amount, set_by = excluded.set_by, set_at = now()`,
        [body.salesmanId, first, body.amount, request.user.id],
      );
    }
    await audit(db, {
      userId: request.user.id,
      action: 'target_set',
      targetUserId: body.salesmanId,
      details: { month: body.month, amount: body.amount },
    });
    return { salesmanId: body.salesmanId, name: rows[0].name, companies: rows[0].companies, month: body.month, amount: body.amount };
  });
}
