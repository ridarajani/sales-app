import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { assertCompany, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { companyCodes, idParams, invalid, LIST_EDITORS, notFound } from './common.js';

export type Product = {
  id: number;
  code: string;
  companyCode: string;
  brand: string;
  name: string;
  packSize: string;
  piecesPerCarton: number;
  active: boolean;
};

const productBody = z.object({
  code: z.string().trim().min(1, 'Please type the product code.').max(40).transform((s) => s.toUpperCase()),
  companyCode: z.string(),
  brand: z.string().trim().min(1, 'Please type the brand.'),
  name: z.string().trim().min(1, 'Please type the product name.'),
  packSize: z.string().trim().min(1, 'Please type the pack size, for example 400g tin.'),
  piecesPerCarton: z.number().int().positive('Pieces per carton must be more than 0.').max(100000),
});
const updateBody = productBody.partial().extend({ active: z.boolean().optional() });
const listQuery = z.object({
  q: z.string().trim().optional(),
  company: z.string().optional(),
  includeInactive: z.coerce.boolean().optional(),
});

export type NewProduct = z.infer<typeof productBody>;

const productSelect = `select p.id, p.code, p.company_code as "companyCode", p.brand, p.name, p.pack_size as "packSize",
    p.pieces_per_carton as "piecesPerCarton", p.active
  from products p join companies co on co.code = p.company_code`;

export async function checkProductCompany(db: Db, code: string) {
  if (!(await companyCodes(db)).includes(code)) throw invalid('That company is not known.');
}

export async function insertProduct(client: PoolClient | Db, p: NewProduct): Promise<number | null> {
  const { rows } = await client.query<{ id: number }>(
    `insert into products (code, company_code, brand, name, pack_size, pieces_per_carton) values ($1, $2, $3, $4, $5, $6)
     on conflict (code) do nothing returning id`,
    [p.code, p.companyCode, p.brand, p.name, p.packSize, p.piecesPerCarton],
  );
  return rows[0]?.id ?? null;
}

const codeTaken = () => invalid('A product with this code already exists.');

export function productRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const editors = requireRole(...LIST_EDITORS);

  // Grouped by company in the app, so sorted by company first.
  app.get('/products', { onRequest: auth }, async (request) => {
    const { q, company, includeInactive } = parse(listQuery, request.query);
    const companies = company ? request.user.companies.filter((c) => c === company) : request.user.companies;
    const params: unknown[] = [companies];
    let where = ' where p.company_code = any($1)';
    if (!includeInactive) where += ' and p.active';
    if (q) {
      params.push(`%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`);
      const n = params.length;
      where += ` and (p.code ilike $${n} or p.brand ilike $${n} or p.name ilike $${n} or p.pack_size ilike $${n})`;
    }
    const { rows } = await db.query<Product>(`${productSelect}${where} order by co.sort_order, p.brand, p.name, p.pack_size limit 1000`, params);
    return rows;
  });

  app.get('/products/:id', { onRequest: auth }, async (request) => {
    const { id } = parse(idParams, request.params);
    const { rows } = await db.query<Product>(`${productSelect} where p.id = $1 and p.company_code = any($2)`, [id, request.user.companies]);
    if (!rows[0]) throw notFound('product');
    return rows[0];
  });

  app.post('/products', { onRequest: auth, preHandler: editors }, async (request, reply) => {
    const body = parse(productBody, request.body);
    await checkProductCompany(db, body.companyCode);
    assertCompany(request.user, body.companyCode);
    const id = await insertProduct(db, body);
    if (id == null) throw codeTaken();
    await audit(db, { userId: request.user.id, action: 'product_created', details: { productId: id, code: body.code } });
    const { rows } = await db.query<Product>(`${productSelect} where p.id = $1`, [id]);
    return reply.code(201).send(rows[0]);
  });

  app.put('/products/:id', { onRequest: auth, preHandler: editors }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(updateBody, request.body);
    const { rows: found } = await db.query<Product>(`${productSelect} where p.id = $1 and p.company_code = any($2)`, [id, request.user.companies]);
    const current = found[0];
    if (!current) throw notFound('product');
    if (body.companyCode !== undefined) {
      await checkProductCompany(db, body.companyCode);
      assertCompany(request.user, body.companyCode);
    }
    const { rowCount } = await db.query(
      `update products set code = $2, company_code = $3, brand = $4, name = $5, pack_size = $6, pieces_per_carton = $7,
         active = $8, updated_at = now()
       where id = $1 and not exists (select 1 from products o where o.code = $2 and o.id <> $1)`,
      [
        id,
        body.code ?? current.code,
        body.companyCode ?? current.companyCode,
        body.brand ?? current.brand,
        body.name ?? current.name,
        body.packSize ?? current.packSize,
        body.piecesPerCarton ?? current.piecesPerCarton,
        body.active ?? current.active,
      ],
    );
    if (rowCount === 0) throw codeTaken();
    await audit(db, { userId: request.user.id, action: 'product_updated', details: { productId: id, changes: Object.keys(body) } });
    const { rows } = await db.query<Product>(`${productSelect} where p.id = $1`, [id]);
    return rows[0];
  });
}
