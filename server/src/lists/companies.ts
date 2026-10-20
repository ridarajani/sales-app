import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { notFound, optionalPhone } from './common.js';

const codeParams = z.object({ code: z.string().max(10) });
const detailsBody = z.object({
  name: z.string().trim().min(1, 'Please type the company name.').optional(),
  tpin: z.string().trim().regex(/^\d{6,12}$/, 'The TPIN should be numbers only.').nullish().or(z.literal('')),
  vatRate: z.number().min(0, 'The VAT rate cannot be below 0.').max(100, 'The VAT rate cannot be over 100.').nullish(),
  phone: z.string().nullish(),
  email: z.email('That email does not look right.').nullish().or(z.literal('')),
  address: z.string().trim().nullish(),
});

const detailsSelect = `select code, name, facility_code as "facilityCode", tpin, vat_rate as "vatRate", phone, email, address
  from companies`;

export function companyRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);

  // Full details for printing invoices. Any signed-in user may read their own companies.
  app.get('/companies/:code', { onRequest: auth }, async (request) => {
    const { code } = parse(codeParams, request.params);
    assertCompany(request.user, code);
    const { rows } = await db.query(`${detailsSelect} where code = $1`, [code]);
    if (!rows[0]) throw notFound('company');
    return rows[0];
  });

  app.put('/companies/:code', { onRequest: auth, preHandler: requireRole('admin') }, async (request) => {
    const { code } = parse(codeParams, request.params);
    const body = parse(detailsBody, request.body);
    const blank = (v: string | null | undefined) => (v === undefined ? undefined : v === '' || v === null ? null : v);
    const phone = body.phone === undefined ? undefined : optionalPhone(body.phone);
    const sets: string[] = [];
    const params: unknown[] = [code];
    const set = (column: string, value: unknown) => {
      if (value === undefined) return;
      params.push(value);
      sets.push(`${column} = $${params.length}`);
    };
    set('name', body.name);
    set('tpin', blank(body.tpin));
    set('vat_rate', body.vatRate);
    set('phone', phone);
    set('email', blank(body.email));
    set('address', blank(body.address));
    if (sets.length) {
      const { rowCount } = await db.query(`update companies set ${sets.join(', ')} where code = $1`, params);
      if (rowCount === 0) throw notFound('company');
      await audit(db, { userId: request.user.id, action: 'company_updated', details: { company: code, changes: Object.keys(body) } });
    }
    const { rows } = await db.query(`${detailsSelect} where code = $1`, [code]);
    if (!rows[0]) throw notFound('company');
    return rows[0];
  });
}
