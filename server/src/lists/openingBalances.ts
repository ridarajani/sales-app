import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { idParams, invalid, notFound } from './common.js';

const entryBody = z.object({
  companyCode: z.string(),
  amount: z.number().int('Amounts are whole kwacha.').min(0, 'The amount cannot be below 0.').max(1e13),
  asOf: z.iso.date('Please pick the start date.'),
});

export function openingBalanceRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);

  app.get('/opening-balances', { onRequest: auth, preHandler: requireRole('admin', 'accounts', 'management') }, async (request) => {
    const { rows } = await db.query(
      `select ob.customer_id as "customerId", c.name as "customerName", ob.company_code as "companyCode", ob.amount,
         to_char(ob.as_of, 'YYYY-MM-DD') as "asOf", u.name as "enteredBy", ob.entered_at as "enteredAt"
       from opening_balances ob join customers c on c.id = ob.customer_id join users u on u.id = ob.entered_by
       where ob.company_code = any($1) order by lower(c.name), ob.company_code`,
      [request.user.companies],
    );
    return rows;
  });

  // Entered once by Accounts. After that only the admin can correct it, and every change is in the audit log.
  app.put('/customers/:id/opening-balance', { onRequest: auth, preHandler: requireRole('admin', 'accounts') }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(entryBody, request.body);
    assertCompany(request.user, body.companyCode);
    const { rows: link } = await db.query(
      'select 1 from customer_companies where customer_id = $1 and company_code = $2',
      [id, body.companyCode],
    );
    if (!link[0]) {
      const { rows: exists } = await db.query('select 1 from customers where id = $1', [id]);
      if (!exists[0]) throw notFound('customer');
      throw invalid('This customer does not buy from that company.');
    }
    const { rows: before } = await db.query<{ amount: number }>(
      'select amount from opening_balances where customer_id = $1 and company_code = $2',
      [id, body.companyCode],
    );
    if (before[0] && request.user.role !== 'admin') {
      throw invalid('The opening balance is already entered. Ask the admin to correct it.');
    }
    await db.query(
      `insert into opening_balances (customer_id, company_code, amount, as_of, entered_by) values ($1, $2, $3, $4, $5)
       on conflict (customer_id, company_code) do update set amount = $3, as_of = $4, entered_by = $5, entered_at = now()`,
      [id, body.companyCode, body.amount, body.asOf, request.user.id],
    );
    await audit(db, {
      userId: request.user.id,
      action: before[0] ? 'opening_balance_corrected' : 'opening_balance_entered',
      details: { customerId: id, company: body.companyCode, amount: body.amount, before: before[0]?.amount ?? null },
    });
    return { customerId: id, companyCode: body.companyCode, amount: body.amount, asOf: body.asOf };
  });
}
