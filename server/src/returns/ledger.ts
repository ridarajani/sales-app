import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { assertCompany, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import { CUSTOMER_OWES, INVOICE_BALANCE, NOTE_SIGN } from '../cash/balances.js';
import type { Db } from '../db/pool.js';
import { logoPath } from '../files.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { statementPdf } from '../print/pdf.js';
import { TODAY } from '../stock/stock.js';
import { parse } from '../users.js';

// The customer ledger card: every movement with one company, oldest first, with the balance after each.
export const LEDGER_VIEWERS = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'] as const;

const ledgerQuery = z.object({
  company: z.string({ error: 'Please pick the company.' }),
  from: z.iso.date().optional(),
  to: z.iso.date().optional(),
});

const LOCAL_DATE = (ts: string) => `to_char((${ts} at time zone 'Africa/Blantyre')::date, 'YYYY-MM-DD')`;

type Movement = {
  date: string;
  at: string;
  document: string;
  what: string;
  charged: number;
  paid: number;
  invoiceId: number | null;
};

async function ledger(db: Db, user: AuthUser, customerId: number, query: z.infer<typeof ledgerQuery>) {
  assertCompany(user, query.company);
  if (query.from && query.to && query.from > query.to) throw invalid('The start date is after the end date.');
  const { rows: heads } = await db.query(
    `select c.id, c.name, c.area, c.address, c.city, co.code as "companyCode", co.name as "companyName", co.address as "companyAddress",
       co.phone as "companyPhone", co.email as "companyEmail", co.tpin as "companyTpin",
       ${CUSTOMER_OWES('c.id', 'co.code')}::float8 as owes,
       coalesce((select sum(greatest(${INVOICE_BALANCE('iv')}, 0)) from invoices iv
         where iv.customer_id = c.id and iv.company_code = co.code and iv.due_date < ${TODAY}), 0)::float8 as overdue
     from customers c join customer_companies cc on cc.customer_id = c.id join companies co on co.code = cc.company_code
     where c.id = $1 and co.code = $2`,
    [customerId, query.company],
  );
  const head = heads[0];
  if (!head) throw notFound('customer');

  const { rows } = await db.query<Movement>(
    `select * from (
       select to_char(ob.as_of, 'YYYY-MM-DD') as date, ob.entered_at as at, '' as document, 'Owed before the app' as what,
         ob.amount::float8 as charged, 0::float8 as paid, null::int as "invoiceId"
       from opening_balances ob where ob.customer_id = $1 and ob.company_code = $2
     union all
       select to_char(iv.invoice_date, 'YYYY-MM-DD'), iv.created_at, iv.number,
         'Invoice' || case when iv.lpo_no <> '' then ' · LPO ' || iv.lpo_no else '' end
           || coalesce(' · ' || lp.delivery_note_no, ''),
         iv.total::float8, 0, iv.id
       from invoices iv left join loading_plans lp on lp.id = iv.plan_id where iv.customer_id = $1 and iv.company_code = $2
     union all
       select ${LOCAL_DATE('c.created_at')}, c.created_at, c.number,
         'Payment · ' || case c.mode when 'cash' then 'Cash' when 'bank' then 'Bank transfer' else 'Airtel Money' end
           || case when c.reference <> '' then ' · ' || c.reference else '' end,
         0, c.amount::float8, c.invoice_id
       from collections c where c.customer_id = $1 and c.company_code = $2 and c.amount > 0
     union all
       select ${LOCAL_DATE('c.created_at')}, c.created_at, c.number, 'Discount · ' || iv.number, 0, c.discount::float8, c.invoice_id
       from collections c join invoices iv on iv.id = c.invoice_id where c.customer_id = $1 and c.company_code = $2 and c.discount > 0
     union all
       select ${LOCAL_DATE('c.created_at')}, c.created_at, c.number,
         'Withholding tax · ' || iv.number || case when c.wht_certificate <> '' then ' · certificate ' || c.wht_certificate else '' end,
         0, c.wht::float8, c.invoice_id
       from collections c join invoices iv on iv.id = c.invoice_id where c.customer_id = $1 and c.company_code = $2 and c.wht > 0
     union all
       select ${LOCAL_DATE('r.booked_at')}, r.booked_at, r.number,
         'Sales return · ' || iv.number || case when r.kind = 'delivery' then ' · not taken at delivery' else '' end,
         0, r.total::float8, r.invoice_id
       from sales_returns r join invoices iv on iv.id = r.invoice_id
       where r.customer_id = $1 and r.company_code = $2 and r.booked_at is not null
     union all
       select ${LOCAL_DATE('n.created_at')}, n.created_at, n.number,
         case n.kind when 'credit_issued' then 'Credit note' when 'debit_issued' then 'Debit note'
           when 'debit_received' then 'Their debit note' else 'Their credit note' end
           || ' · ' || iv.number || ' · ' || n.reason || case when n.their_number <> '' then ' · ' || n.their_number else '' end,
         case when ${NOTE_SIGN('n')} > 0 then n.amount::float8 else 0 end,
         case when ${NOTE_SIGN('n')} < 0 then n.amount::float8 else 0 end, n.invoice_id
       from invoice_notes n join invoices iv on iv.id = n.invoice_id where n.customer_id = $1 and n.company_code = $2
     ) m order by date, at, document`,
    [customerId, query.company],
  );

  // Movements before the period make up its first line; those after it are left out.
  let balance = 0;
  let opening = 0;
  const shown: (Movement & { balance: number })[] = [];
  for (const m of rows) {
    balance += m.charged - m.paid;
    if (query.to && m.date > query.to) continue;
    if (query.from && m.date < query.from) {
      opening = balance;
      continue;
    }
    shown.push({ ...m, balance });
  }
  const charged = shown.reduce((t, m) => t + m.charged, 0);
  const paid = shown.reduce((t, m) => t + m.paid, 0);
  return {
    customer: { id: head.id, name: head.name, area: head.area, address: head.address, city: head.city },
    company: {
      code: head.companyCode,
      name: head.companyName,
      address: head.companyAddress,
      phone: head.companyPhone,
      email: head.companyEmail,
      tpin: head.companyTpin,
    },
    from: query.from ?? null,
    to: query.to ?? null,
    opening,
    rows: shown.map(({ at: _at, ...m }) => m),
    charged,
    paid,
    closing: opening + charged - paid,
    owes: head.owes,
    overdue: head.overdue,
  };
}

export function ledgerRoutes(app: FastifyInstance, db: Db, brandingDir: string) {
  const auth = requireAuth(db);
  const viewers = requireRole(...LEDGER_VIEWERS);

  app.get('/customers/:id/ledger', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    return ledger(db, request.user, id, parse(ledgerQuery, request.query));
  });

  // The same, printed as a statement to send to the customer.
  app.get('/customers/:id/statement.pdf', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id } = parse(idParams, request.params);
    const l = await ledger(db, request.user, id, parse(ledgerQuery, request.query));
    const pdf = await statementPdf({
      company: { ...l.company, logo: logoPath(brandingDir, l.company.code) },
      customer: l.customer,
      from: l.from,
      to: l.to,
      opening: l.opening,
      rows: l.rows.map((r) => ({ date: r.date, document: r.document, what: r.what, charged: r.charged, paid: r.paid, balance: r.balance })),
      closing: l.closing,
      overdue: l.overdue,
    });
    const name = `Statement ${l.customer.name} ${l.company.code}`.replace(/[^A-Za-z0-9 _-]/g, '');
    return reply.type('application/pdf').header('content-disposition', `inline; filename="${name}.pdf"`).send(pdf);
  });
}
