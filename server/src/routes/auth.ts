import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { requireAuth } from '../auth/guard.js';
import { hashPin, verifyPin } from '../auth/pin.js';
import { createSession, deleteSession } from '../auth/session.js';
import type { Db } from '../db/pool.js';
import { getUser, isValidPhone, normalisePhone, parse, publicUser } from '../users.js';

const MAX_TRIES = 5;
const LOCK_MINUTES = 15;

const loginBody = z.object({ phone: z.string().min(1), pin: z.string().regex(/^\d{4}$/, 'The PIN must be 4 numbers.') });

// Used so an unknown phone takes as long as a wrong PIN.
const dummyHash = await hashPin('0000');

type LoginRow = { id: number; pin_hash: string; active: boolean; minutesLeft: number | null };
type Outcome =
  | { kind: 'unknown' }
  | { kind: 'locked'; minutes: number; userId: number; newlyLocked: boolean }
  | { kind: 'wrong'; userId: number }
  | { kind: 'off' }
  | { kind: 'ok'; userId: number };

export function authRoutes(app: FastifyInstance, db: Db) {
  app.post('/auth/login', async (request, reply) => {
    const body = parse(loginBody, request.body);
    const phone = normalisePhone(body.phone);
    const shownPhone = phone.slice(0, 20);
    const wrongLogin = () => reply.code(401).send({ error: 'wrong_login', message: 'Wrong phone number or PIN.' });

    if (!isValidPhone(phone)) {
      await verifyPin(body.pin, dummyHash);
      await audit(db, { userId: null, action: 'login_failed', details: { phone: shownPhone } });
      return wrongLogin();
    }

    // The user row stays locked until we have decided, so many tries at once cannot slip past the lock.
    const client = await db.connect();
    let outcome: Outcome;
    try {
      await client.query('begin');
      const { rows } = await client.query<LoginRow>(
        `select id, pin_hash, active,
           case when locked_until > now() then ceil(extract(epoch from (locked_until - now())) / 60)::int end as "minutesLeft"
         from users where phone = $1 for update`,
        [phone],
      );
      const row = rows[0];
      if (!row) {
        outcome = { kind: 'unknown' };
      } else if (row.minutesLeft) {
        outcome = { kind: 'locked', minutes: row.minutesLeft, userId: row.id, newlyLocked: false };
      } else if (!(await verifyPin(body.pin, row.pin_hash))) {
        const { rows: updated } = await client.query<{ locked: boolean }>(
          `update users set
             failed_pin_attempts = case when failed_pin_attempts + 1 >= $2 then 0 else failed_pin_attempts + 1 end,
             locked_until = case when failed_pin_attempts + 1 >= $2 then now() + make_interval(mins => $3) else locked_until end
           where id = $1 returning coalesce(locked_until > now(), false) as locked`,
          [row.id, MAX_TRIES, LOCK_MINUTES],
        );
        outcome = updated[0]?.locked
          ? { kind: 'locked', minutes: LOCK_MINUTES, userId: row.id, newlyLocked: true }
          : { kind: 'wrong', userId: row.id };
      } else if (!row.active) {
        outcome = { kind: 'off' };
      } else {
        await client.query('update users set failed_pin_attempts = 0, locked_until = null where id = $1', [row.id]);
        outcome = { kind: 'ok', userId: row.id };
      }
      await client.query('commit');
    } catch (e) {
      await client.query('rollback');
      throw e;
    } finally {
      client.release();
    }

    switch (outcome.kind) {
      case 'unknown':
        await verifyPin(body.pin, dummyHash);
        await audit(db, { userId: null, action: 'login_failed', details: { phone: shownPhone } });
        return wrongLogin();
      case 'locked':
        if (outcome.newlyLocked) {
          await audit(db, { userId: outcome.userId, action: 'login_failed', details: { phone: shownPhone, locked: true } });
        }
        return reply.code(423).send({
          error: 'locked',
          message: `Too many wrong tries. Try again in ${outcome.minutes} minutes.`,
        });
      case 'wrong':
        await audit(db, { userId: outcome.userId, action: 'login_failed', details: { phone: shownPhone, locked: false } });
        return wrongLogin();
      case 'off':
        return reply.code(403).send({ error: 'switched_off', message: 'This account is switched off. Ask the admin.' });
      case 'ok': {
        const token = await createSession(db, outcome.userId);
        await audit(db, { userId: outcome.userId, action: 'login' });
        const user = await getUser(db, outcome.userId);
        return { token, user: publicUser(user!) };
      }
    }
  });

  // Step 1 of the phone app login: is this phone number approved? Not audited, it would be noisy.
  app.post('/auth/check-phone', async (request, reply) => {
    const body = parse(z.object({ phone: z.string().min(1) }), request.body);
    const phone = normalisePhone(body.phone);
    const { rows } = isValidPhone(phone)
      ? await db.query<{ name: string; role: string; active: boolean; minutesLeft: number | null }>(
          `select name, role, active,
             case when locked_until > now() then ceil(extract(epoch from (locked_until - now())) / 60)::int end as "minutesLeft"
           from users where phone = $1`,
          [phone],
        )
      : { rows: [] };
    const row = rows[0];
    if (!row) {
      return reply.code(404).send({ error: 'unknown_phone', message: 'This phone number is not set up yet. Ask the admin to add you.' });
    }
    if (!row.active) {
      return reply.code(403).send({ error: 'switched_off', message: 'This account is switched off. Ask the admin.' });
    }
    if (row.minutesLeft) {
      return reply.code(423).send({ error: 'locked', message: `Too many wrong tries. Try again in ${row.minutesLeft} minutes.` });
    }
    return { name: row.name, role: row.role };
  });

  app.post('/auth/logout', { onRequest: requireAuth(db) }, async (request) => {
    await deleteSession(db, request.token);
    await audit(db, { userId: request.user.id, action: 'logout' });
    return { ok: true };
  });

  app.get('/auth/me', { onRequest: requireAuth(db) }, async (request) => {
    const user = await getUser(db, request.user.id);
    return publicUser(user!);
  });
}
