import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { assertCanEditCompanies, CHANNELS, companyCodes, idParams, invalid, notFound, optionalPhone } from './common.js';

export type CustomerCompany = { code: string; paymentTermsDays: number };
export type Customer = {
  id: number;
  name: string;
  channel: string;
  area: string;
  phone: string | null;
  needsLpo: boolean;
  cashOnly: boolean;
  active: boolean;
  companies: CustomerCompany[];
};

const companyEntry = z.object({
  code: z.string(),
  paymentTermsDays: z.number().int().min(0, 'Payment terms cannot be below 0 days.').max(365, 'Payment terms cannot be over 365 days.'),
});

const customerBody = z.object({
  name: z.string().trim().min(1, 'Please type the customer name.'),
  channel: z.enum(CHANNELS, 'Please pick a channel.'),
  area: z.string().trim().default(''),
  phone: z.string().nullish(),
  needsLpo: z.boolean().default(false),
  cashOnly: z.boolean().default(false),
  companies: z.array(companyEntry).min(1, 'Pick at least one company.'),
});
const updateBody = customerBody.partial().extend({ active: z.boolean().optional() });
const listQuery = z.object({
  q: z.string().trim().optional(),
  company: z.string().optional(),
  includeInactive: z.coerce.boolean().optional(),
});

// The user sees a customer if they share a company, and only those companies' terms.
const customerSelect = `select c.id, c.name, c.channel, c.area, c.phone, c.needs_lpo as "needsLpo", c.cash_only as "cashOnly", c.active,
    coalesce((select json_agg(json_build_object('code', cc.company_code, 'paymentTermsDays', cc.payment_terms_days) order by co.sort_order)
              from customer_companies cc join companies co on co.code = cc.company_code
              where cc.customer_id = c.id and cc.company_code = any($1)), '[]') as companies
  from customers c
  where exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any($1))`;

export async function getCustomer(db: Db | PoolClient, user: AuthUser, id: number): Promise<Customer | null> {
  const { rows } = await db.query<Customer>(`${customerSelect} and c.id = $2`, [user.companies, id]);
  return rows[0] ?? null;
}

// All companies of a customer, whatever the user may see. Used to check edit rights.
async function allCompaniesOf(db: Db, id: number): Promise<string[]> {
  return (await db.query<{ code: string }>('select company_code as code from customer_companies where customer_id = $1', [id])).rows.map((r) => r.code);
}

export type NewCustomer = z.infer<typeof customerBody>;
export type CleanCustomer = NewCustomer & { phone: string | null };

// Checks shared by the form and the Excel import. Returns the cleaned customer or throws.
export async function cleanCustomer(db: Db, input: NewCustomer): Promise<CleanCustomer> {
  const known = await companyCodes(db);
  const codes = input.companies.map((c) => c.code);
  if (codes.some((c) => !known.includes(c))) throw invalid('One of the companies is not known.');
  if (new Set(codes).size !== codes.length) throw invalid('A company is listed twice.');
  const companies = input.cashOnly ? input.companies.map((c) => ({ ...c, paymentTermsDays: 0 })) : input.companies;
  return { ...input, companies, phone: optionalPhone(input.phone) };
}

export async function insertCustomer(client: PoolClient, c: CleanCustomer): Promise<number | null> {
  const { rows } = await client.query<{ id: number }>(
    `insert into customers (name, channel, area, phone, needs_lpo, cash_only) values ($1, $2, $3, $4, $5, $6)
     on conflict ((lower(name))) do nothing returning id`,
    [c.name, c.channel, c.area, c.phone, c.needsLpo, c.cashOnly],
  );
  const id = rows[0]?.id;
  if (id == null) return null;
  for (const co of c.companies) {
    await client.query('insert into customer_companies (customer_id, company_code, payment_terms_days) values ($1, $2, $3)', [
      id,
      co.code,
      co.paymentTermsDays,
    ]);
  }
  return id;
}

const nameTaken = () => invalid('A customer with this name already exists.');

export function customerRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const editors = requireRole('admin', 'accounts', 'sales_manager');

  app.get('/customers', { onRequest: auth }, async (request) => {
    const { q, company, includeInactive } = parse(listQuery, request.query);
    const companies = company ? request.user.companies.filter((c) => c === company) : request.user.companies;
    const params: unknown[] = [companies];
    let where = '';
    if (!includeInactive) where += ' and c.active';
    if (q) {
      params.push(`%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`);
      where += ` and (c.name ilike $${params.length} or c.area ilike $${params.length} or c.phone like $${params.length})`;
    }
    const { rows } = await db.query<Customer>(`${customerSelect}${where} order by lower(c.name) limit 500`, params);
    return rows;
  });

  app.get('/customers/:id', { onRequest: auth }, async (request) => {
    const { id } = parse(idParams, request.params);
    const customer = await getCustomer(db, request.user, id);
    if (!customer) throw notFound('customer');
    return customer;
  });

  app.post('/customers', { onRequest: auth, preHandler: editors }, async (request, reply) => {
    const input = await cleanCustomer(db, parse(customerBody, request.body));
    assertCanEditCompanies(request.user, input.companies.map((c) => c.code));
    const client = await db.connect();
    let id: number | null;
    try {
      await client.query('begin');
      id = await insertCustomer(client, input);
      await client.query(id == null ? 'rollback' : 'commit');
    } catch (e) {
      await client.query('rollback');
      throw e;
    } finally {
      client.release();
    }
    if (id == null) throw nameTaken();
    await audit(db, { userId: request.user.id, action: 'customer_created', details: { customerId: id, name: input.name } });
    return reply.code(201).send(await getCustomer(db, request.user, id));
  });

  app.put('/customers/:id', { onRequest: auth, preHandler: editors }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(updateBody, request.body);
    const current = await getCustomer(db, request.user, id);
    if (!current) throw notFound('customer');

    // A sales manager must not touch a customer that also buys from a company they do not run.
    const existing = await allCompaniesOf(db, id);
    assertCanEditCompanies(request.user, existing);
    const merged = await cleanCustomer(db, {
      name: body.name ?? current.name,
      channel: body.channel ?? (current.channel as NewCustomer['channel']),
      area: body.area ?? current.area,
      phone: body.phone === undefined ? current.phone : body.phone,
      needsLpo: body.needsLpo ?? current.needsLpo,
      cashOnly: body.cashOnly ?? current.cashOnly,
      companies: body.companies ?? current.companies,
    });
    assertCanEditCompanies(request.user, merged.companies.map((c) => c.code));

    const client = await db.connect();
    try {
      await client.query('begin');
      const updated = await client.query(
        `update customers set name = $2, channel = $3, area = $4, phone = $5, needs_lpo = $6, cash_only = $7,
           active = coalesce($8, active), updated_at = now()
         where id = $1 and not exists (select 1 from customers o where lower(o.name) = lower($2) and o.id <> $1)`,
        [id, merged.name, merged.channel, merged.area, merged.phone, merged.needsLpo, merged.cashOnly, body.active ?? null],
      );
      if (updated.rowCount === 0) {
        await client.query('rollback');
        throw nameTaken();
      }
      await client.query('delete from customer_companies where customer_id = $1', [id]);
      for (const co of merged.companies) {
        await client.query('insert into customer_companies (customer_id, company_code, payment_terms_days) values ($1, $2, $3)', [
          id,
          co.code,
          co.paymentTermsDays,
        ]);
      }
      await client.query('commit');
    } catch (e) {
      if (!(e instanceof HttpError)) await client.query('rollback');
      throw e;
    } finally {
      client.release();
    }
    await audit(db, { userId: request.user.id, action: 'customer_updated', details: { customerId: id, changes: Object.keys(body) } });
    return getCustomer(db, request.user, id);
  });
}
