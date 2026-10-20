import { z } from 'zod';
import { HttpError } from './auth/guard.js';
import type { Db } from './db/pool.js';

export const ROLES = ['admin', 'management', 'sales_manager', 'salesman', 'warehouse', 'driver', 'cashier', 'accounts'] as const;

// Phone numbers are kept as digits only, starting with 0: +265 99 123 4567 becomes 0991234567.
export function normalisePhone(input: string): string {
  const p = input.replace(/[\s\-().]/g, '');
  const m = p.match(/^(?:\+265|00265|265)(.*)$/);
  if (!m) return p;
  const rest = m[1]!;
  return rest.startsWith('0') ? rest : '0' + rest;
}

export const isValidPhone = (phone: string) => /^0\d{9}$/.test(phone);

export const PHONE_MESSAGE = 'That phone number does not look right.';

export const INT4_MAX = 2147483647;

const FIELD_WORDS: Record<string, string> = {
  managerId: 'sales manager',
  pin: 'PIN',
  companies: 'companies',
  phone: 'phone number',
  name: 'name',
  versionCode: 'version number',
  fileName: 'file name',
};

export const pinSchema = z.string().regex(/^\d{4}$/, 'The PIN must be 4 numbers.');

// Turns a zod failure into a 400 with plain words.
export function parse<T extends z.ZodType>(schema: T, data: unknown): z.infer<T> {
  const result = schema.safeParse(data);
  if (result.success) return result.data;
  const issue = result.error.issues[0];
  const custom = issue?.message && !/^(Invalid|Required|Too|Expected)/i.test(issue.message) ? issue.message : null;
  const word = FIELD_WORDS[String(issue?.path[0] ?? '')];
  const field = word ? ` Check the ${word}.` : '';
  throw new HttpError(400, 'invalid', custom ?? `Some details are missing or wrong.${field}`);
}

export type UserDto = {
  id: number;
  name: string;
  phone: string;
  role: string;
  companies: string[];
  managerId: number | null;
  managerName: string | null;
  active: boolean;
};

const userSelect = `select u.id, u.name, u.phone, u.role, u.manager_id as "managerId", m.name as "managerName", u.active,
    coalesce((select array_agg(uc.company_code order by c.sort_order)
              from user_companies uc join companies c on c.code = uc.company_code
              where uc.user_id = u.id), '{}') as companies
  from users u left join users m on m.id = u.manager_id`;

export async function getUser(db: Db, id: number): Promise<UserDto | null> {
  const { rows } = await db.query<UserDto>(`${userSelect} where u.id = $1`, [id]);
  return rows[0] ?? null;
}

export async function listUsers(db: Db, onlyManagerId?: number): Promise<UserDto[]> {
  if (onlyManagerId === undefined) {
    return (await db.query<UserDto>(`${userSelect} order by u.active desc, u.name`)).rows;
  }
  return (
    await db.query<UserDto>(`${userSelect} where u.id = $1 or (u.role = 'salesman' and u.manager_id = $1) order by u.active desc, u.name`, [
      onlyManagerId,
    ])
  ).rows;
}

// The shape sent after login and from /auth/me.
export const publicUser = (u: UserDto) => ({
  id: u.id,
  name: u.name,
  phone: u.phone,
  role: u.role,
  companies: u.companies,
  managerId: u.managerId,
});
