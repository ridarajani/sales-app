import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import { hashPin } from '../auth/pin.js';
import { deleteUserSessions } from '../auth/session.js';
import type { Db } from '../db/pool.js';
import { deleteOrMark } from '../lists/common.js';
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
const updateBody = createBody.omit({ pin: true }).partial();
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

type Assignment = { role: (typeof ROLES)[number]; companies?: string[]; managerId?: number | null; companyManagers?: Record<string, number> };

// Works out a user's companies and sales managers from their job. Used when adding and when editing a user.
async function resolveAssignment(db: Db, actor: AuthUser, body: Assignment) {
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
  return { companies, managerId, companyManager };
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

    const { companies, managerId, companyManager } = await resolveAssignment(db, actor, body);

    const client = await db.connect();
    let id: number;
    try {
      await client.query('begin');
      const inserted = await client.query<{ id: number }>(
        `insert into users (name, phone, role, pin_hash, manager_id) values ($1, $2, $3, $4, $5)
         on conflict (phone) where deleted_at is null do nothing returning id`,
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

  // Name and phone, or the job, companies and sales managers. Only what is sent changes.
  app.put('/users/:id', { onRequest: auth, preHandler: requireRole('admin', 'sales_manager') }, async (request) => {
    const actor = request.user;
    const target = await loadManageable(db, actor, request.params);
    const body = parse(updateBody, request.body);
    let phone: string | undefined;
    if (body.phone !== undefined) {
      phone = normalisePhone(body.phone);
      if (!isValidPhone(phone)) throw invalid(PHONE_MESSAGE);
    }

    let assignment: Awaited<ReturnType<typeof resolveAssignment>> | null = null;
    if (body.role !== undefined || body.companies !== undefined || body.managerId !== undefined || body.companyManagers !== undefined) {
      const role = body.role ?? (target.role as Assignment['role']);
      if (target.id === actor.id && role !== target.role) throw invalid('You cannot change your own job.');
      const wasSalesman = target.role === 'salesman';
      const companies = body.companies ?? (role === 'salesman' && body.companyManagers ? undefined : target.companies);
      assignment = await resolveAssignment(db, actor, {
        role,
        companies,
        managerId: role !== 'salesman' ? body.managerId : body.managerId !== undefined ? body.managerId : wasSalesman ? target.managerId : undefined,
        // A salesman keeps the sales manager of each company they still have, unless new ones are given.
        companyManagers:
          role !== 'salesman' || !wasSalesman || body.managerId !== undefined
            ? body.companyManagers
            : body.companyManagers ??
              Object.fromEntries(target.managers.filter((m) => !companies || companies.includes(m.company)).map((m) => [m.company, m.managerId])),
      });
      // A sales manager's salesmen must not be left without one.
      if (target.role === 'sales_manager') {
        const { rows } = await db.query(
          `select 1 from user_companies uc join users u on u.id = uc.user_id
           where uc.manager_id = $1 and u.deleted_at is null and ($2 or not uc.company_code = any($3::text[])) limit 1`,
          [target.id, role !== 'sales_manager', assignment.companies],
        );
        if (rows[0]) throw invalid('Some salesmen work under this sales manager. Move them to another sales manager first.');
      }
    }

    const client = await db.connect();
    try {
      await client.query('begin');
      await client.query('update users set name = coalesce($2, name), phone = coalesce($3, phone), updated_at = now() where id = $1', [
        target.id,
        body.name ?? null,
        phone ?? null,
      ]);
      if (assignment) {
        await client.query('update users set role = $2, manager_id = $3 where id = $1', [target.id, body.role ?? target.role, assignment.managerId]);
        await client.query('delete from user_companies where user_id = $1', [target.id]);
        for (const c of assignment.companies) {
          await client.query('insert into user_companies (user_id, company_code, manager_id) values ($1, $2, $3)', [
            target.id,
            c,
            assignment.companyManager.get(c) ?? null,
          ]);
        }
      }
      await client.query('commit');
    } catch (e) {
      await client.query('rollback');
      if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'phone_taken', 'That phone number already has a login.');
      throw e;
    } finally {
      client.release();
    }
    await audit(db, { userId: actor.id, action: 'user_updated', targetUserId: target.id, details: { changes: Object.keys(body) } });
    return getUser(db, target.id);
  });

  // Their login stops at once. Someone with history stays in old documents under their name.
  app.delete('/users/:id', { onRequest: auth, preHandler: requireRole('admin', 'sales_manager') }, async (request) => {
    const actor = request.user;
    const target = await loadManageable(db, actor, request.params);
    if (target.id === actor.id) throw invalid('You cannot delete your own login.');
    const { rows } = await db.query(
      `select 1 from users u where u.deleted_at is null and u.id <> $1
         and (u.manager_id = $1 or exists (select 1 from user_companies uc where uc.user_id = u.id and uc.manager_id = $1)) limit 1`,
      [target.id],
    );
    if (rows[0]) throw invalid('Some salesmen work under this sales manager. Move them to another sales manager first.');
    await deleteUserSessions(db, target.id);
    await db.query('update vehicles set default_driver_id = null where default_driver_id = $1', [target.id]);
    const keptForHistory = await deleteOrMark(db, 'users', target.id);
    await audit(db, { userId: actor.id, action: 'user_deleted', details: { userId: target.id, name: target.name, keptForHistory } });
    return { deleted: true, keptForHistory };
  });

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
