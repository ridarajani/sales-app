import { createHash, randomBytes } from 'node:crypto';
import type { Db } from '../db/pool.js';

export type SessionUser = {
  id: number;
  name: string;
  phone: string;
  role: string;
  companies: string[];
  managerId: number | null;
  active: boolean;
};

const hashToken = (token: string) => createHash('sha256').update(token).digest('hex');

// Sessions last until the user signs out, so there is no expiry.
export async function createSession(db: Db, userId: number): Promise<string> {
  const token = randomBytes(32).toString('base64url');
  await db.query('insert into sessions (token_hash, user_id) values ($1, $2)', [hashToken(token), userId]);
  return token;
}

export async function findSession(db: Db, token: string): Promise<SessionUser | null> {
  const { rows } = await db.query<SessionUser>(
    `select u.id, u.name, u.phone, u.role, u.manager_id as "managerId", u.active,
       coalesce((select array_agg(uc.company_code order by c.sort_order)
                 from user_companies uc join companies c on c.code = uc.company_code
                 where uc.user_id = u.id), '{}') as companies
     from sessions s join users u on u.id = s.user_id
     where s.token_hash = $1`,
    [hashToken(token)],
  );
  const user = rows[0];
  if (!user) return null;
  await db.query('update sessions set last_used_at = now() where token_hash = $1', [hashToken(token)]);
  return user;
}

export async function deleteSession(db: Db, token: string) {
  await db.query('delete from sessions where token_hash = $1', [hashToken(token)]);
}

export async function deleteUserSessions(db: Db, userId: number) {
  await db.query('delete from sessions where user_id = $1', [userId]);
}
