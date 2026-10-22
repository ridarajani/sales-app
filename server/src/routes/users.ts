import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import { hashPin } from '../auth/pin.js';
import { deleteUserSessions } from '../auth/session.js';
import type { Db } from '../db/pool.js';
import { getUser, INT4_MAX, isValidPhone, listUsers, normalisePhone, parse, PHONE_MESSAGE, pinSchema, ROLES, type UserDto } from '../users.js';

const createBody = z.object({
  name: z.string().trim().min(1, 'Please type a name.'),
  phone: z.string().min(1, 'Please type a phone number.'),
  role: z.enum(ROLES, 'Please pick a role.'),
  companies: z.array(z.string()).optional(),
  managerId: z.number().int().max(INT4_MAX).nullish(),
  // Salesmen working under more than one sales manager: { LV: 5, ZT: 5, ZF: 8, GL: 8 }. Unlisted companies use managerId.
  companyManagers: z.record(z.string(), z.number().int().positive().max(INT4_MAX)).optional(),
  pin: pinSchema,
});
const idParams = z.object({ id: z.coerce.number().int().positive().max(INT4_MAX) });
const resetBody = z.object({ pin: pinSchema });
const limitQuery = z.object({ limit: z.coerce.number().int().min(1).max(1000).optional() });

const invalid = (message: string) => new HttpError(400, 'invalid', message);

// Admin may act on anyone. A sales manager may only act on their own salesmen.
async function loadManageable(db: Db, actor: AuthUser, rawId: unknown): Promise<UserDto> {
  const ids = idParams.safeParse(rawId);
  const target = ids.success ? await getUser(db, ids.data.id) : null;
  if (!target) throw new HttpError(404, 'not_found', 'That user was not found.');
  const isTheirs = target.managerId === actor.id || target.managers.some((m) => m.managerId === actor.id);
  if (actor.role !== 'admin' && !(target.role === 'salesman' && isTheirs)) throw notAllowed();
  return target;
}

export function userRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);

  app.get('/users', { onRequest: auth, preHandler: requireRole('admin', 'management', 'sales_manager') }, async (request) => {
    const { user } = request;
    return listUsers(db, user.role === 'sales_manager' ? user.id : undefined);
  });

  app.post('/users', { onRequest: auth, preHandler: requireRole('admin', 'sales_manager') }, async (request, reply) => {
    const actor = request.user;
    const body = parse(createBody, request.body);
    const phone = normalisePhone(body.phone);
    if (!isValidPhone(phone)) throw invalid(PHONE_MESSAGE);

    if (actor.role === 'sales_manager') {
      if (body.role !== 'salesman') throw notAllowed();
      if (body.managerId != null && body.managerId !== actor.id) throw notAllowed();
      if (body.companyManagers && Object.values(body.companyManagers).some((m) => m !== actor.id)) throw notAllowed();
    }
    if (body.companyManagers && body.role !== 'salesman') throw invalid('Only salesmen have a sales manager.');

    const allCompanies = (await db.query<{ code: string }>('select code from companies order by sort_order')).rows.map((r) => r.code);
    if (body.companies?.some((c) => !allCompanies.includes(c))) throw invalid('One of the companies is not known.');

    let managerId: number | null = null;
    let companies: string[];
    const companyManager = new Map<string, number>();
    if (body.role === 'admin' || body.role === 'management') {
      if (body.managerId != null) throw invalid('Only salesmen have a sales manager.');
      companies = allCompanies;
    } else if (body.role === 'salesman') {
      const perCompany = body.companyManagers ?? {};
      const fallback = body.managerId ?? (actor.role === 'sales_manager' ? actor.id : null);
      const ids = [...new Set([...Object.values(perCompany), ...(fallback == null ? [] : [fallback])])];
      const found = new Map<number, UserDto>();
      for (const mid of ids) {
        const manager = await getUser(db, mid);
        if (!manager || manager.role !== 'sales_manager' || !manager.active) throw invalid('That sales manager was not found.');
        found.set(mid, manager);
      }
      companies = body.companies ?? (Object.keys(perCompany).length ? Object.keys(perCompany) : fallback != null ? found.get(fallback)!.companies : []);
      if (Object.keys(perCompany).some((c) => !companies.includes(c))) throw invalid('A sales manager is given for a company the salesman does not have.');
      for (const c of companies) {
        const mid = perCompany[c] ?? fallback;
        if (mid == null) throw invalid('A salesman needs a sales manager.');
        if (!found.get(mid)!.companies.includes(c)) throw invalid("A salesman can only have the sales manager's companies.");
        companyManager.set(c, mid);
      }
      managerId = fallback ?? (companies[0] ? companyManager.get(companies[0])! : null);
      if (managerId == null) throw invalid('A salesman needs a sales manager.');
    } else {
      if (body.managerId != null) throw invalid('Only salesmen have a sales manager.');
      companies = body.companies ?? [];
    }
    companies = [...new Set(companies)];
    if (companies.length === 0) throw invalid('Pick at least one company.');

    const client = await db.connect();
    let id: number;
    try {
      await client.query('begin');
      const inserted = await client.query<{ id: number }>(
        `insert into users (name, phone, role, pin_hash, manager_id) values ($1, $2, $3, $4, $5)
         on conflict (phone) do nothing returning id`,
        [body.name, phone, body.role, await hashPin(body.pin), managerId],
      );
      if (!inserted.rows[0]) {
        await client.query('rollback');
        throw new HttpError(409, 'phone_taken', 'That phone number already has a login.');
      }
      id = inserted.rows[0].id;
      for (const c of companies) {
        await client.query('insert into user_companies (user_id, company_code, manager_id) values ($1, $2, $3)', [id, c, companyManager.get(c) ?? null]);
      }
      await client.query('commit');
    } catch (e) {
      if (!(e instanceof HttpError)) await client.query('rollback');
      throw e;
    } finally {
      client.release();
    }
    await audit(db, { userId: actor.id, action: 'user_created', targetUserId: id, details: { role: body.role, companies } });
    return reply.code(201).send(await getUser(db, id));
  });

  for (const [path, active, action] of [
    ['switch-off', false, 'user_switched_off'],
    ['switch-on', true, 'user_switched_on'],
  ] as const) {
    app.post(`/users/:id/${path}`, { onRequest: auth, preHandler: requireRole('admin', 'sales_manager') }, async (request) => {
      const target = await loadManageable(db, request.user, request.params);
      if (!active && target.id === request.user.id) throw invalid('You cannot switch off your own login.');
      await db.query('update users set active = $2, updated_at = now() where id = $1', [target.id, active]);
      if (!active) await deleteUserSessions(db, target.id);
      await audit(db, { userId: request.user.id, action, targetUserId: target.id });
      return getUser(db, target.id);
    });
  }

  app.post('/users/:id/reset-pin', { onRequest: auth, preHandler: requireRole('admin', 'sales_manager') }, async (request) => {
    const target = await loadManageable(db, request.user, request.params);
    const body = parse(resetBody, request.body);
    await db.query(
      'update users set pin_hash = $2, failed_pin_attempts = 0, locked_until = null, updated_at = now() where id = $1',
      [target.id, await hashPin(body.pin)],
    );
    await deleteUserSessions(db, target.id);
    await audit(db, { userId: request.user.id, action: 'pin_reset', targetUserId: target.id });
    return { ok: true };
  });

  app.get('/audit', { onRequest: auth, preHandler: requireRole('admin', 'management') }, async (request) => {
    const { limit } = parse(limitQuery, request.query);
    const { rows } = await db.query(
      `select a.id, a.at, a.user_id as "userId", u.name as "userName", a.action,
         a.target_user_id as "targetUserId", t.name as "targetUserName", a.details
       from audit_log a
       left join users u on u.id = a.user_id
       left join users t on t.id = a.target_user_id
       order by a.at desc, a.id desc limit $1`,
      [limit ?? 200],
    );
    return rows;
  });
}
