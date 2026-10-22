import { z } from 'zod';
import { HttpError } from './auth/guard.js';
import type { Db } from './db/pool.js';

export const ROLES = ['admin', 'management', 'sales_manager', 'salesman', 'warehouse', 'driver', 'cashier', 'accounts'] as const;

// Malawi numbers are kept as digits only, starting with 0: +265 99 123 4567 becomes 0991234567.
// Numbers from other countries (a manager abroad) keep their country code: 00971 50 123 4567 becomes +971501234567.
export function normalisePhone(input: string): string {
  const p = input.replace(/[\s\-().]/g, '');
  const malawi = p.match(/^(?:\+265|00265|265)(.*)$/);
  if (malawi) {
    const rest = malawi[1]!;
    return rest.startsWith('0') ? rest : '0' + rest;
  }
  if (p.startsWith('+')) return p;
  if (p.startsWith('00')) return '+' + p.slice(2);
  // A Malawi mobile typed without the 0, for example 991234567.
  if (/^[389]\d{8}$/.test(p)) return '0' + p;
  return p;
}

export const isValidPhone = (phone: string) => /^0\d{9}$/.test(phone) || /^\+[1-9]\d{7,14}$/.test(phone);

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
  // Salesmen only: the sales manager for each company.
  managers: { company: string; managerId: number; managerName: string }[];
  active: boolean;
};

const userSelect = `select u.id, u.name, u.phone, u.role, u.manager_id as "managerId", m.name as "managerName", u.active,
    coalesce((select array_agg(uc.company_code order by c.sort_order)
              from user_companies uc join companies c on c.code = uc.company_code
              where uc.user_id = u.id), '{}') as companies,
    coalesce((select json_agg(json_build_object('company', uc.company_code, 'managerId', uc.manager_id, 'managerName', mm.name)
                order by c.sort_order)
              from user_companies uc join companies c on c.code = uc.company_code join users mm on mm.id = uc.manager_id
              where uc.user_id = u.id), '[]') as managers
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
    await db.query<UserDto>(`${userSelect} where u.id = $1 or (u.role = 'salesman' and (u.manager_id = $1
         or exists (select 1 from user_companies x where x.user_id = u.id and x.manager_id = $1))) order by u.active desc, u.name`, [
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
