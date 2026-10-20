import { z } from 'zod';
import { HttpError, notAllowed, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { isValidPhone, normalisePhone, PHONE_MESSAGE } from '../users.js';

export const CHANNELS = ['supermarket', 'wholesaler', 'minibus', 'upcountry'] as const;
export type Channel = (typeof CHANNELS)[number];

export const CHANNEL_LABELS: Record<Channel, string> = {
  supermarket: 'Supermarket',
  wholesaler: 'Wholesaler',
  minibus: 'Minibus or local market',
  upcountry: 'Upcountry',
};

// Roles that may change customers, products and the other lists.
export const LIST_EDITORS = ['admin', 'accounts'] as const;

export const invalid = (message: string) => new HttpError(400, 'invalid', message);
export const notFound = (what: string) => new HttpError(404, 'not_found', `That ${what} was not found.`);

export const idParams = z.object({ id: z.coerce.number().int().positive().max(2147483647) });

// An optional phone: empty means none, anything else must be a valid number.
export function optionalPhone(input: string | null | undefined): string | null {
  if (input == null || input.trim() === '') return null;
  const phone = normalisePhone(input);
  if (!isValidPhone(phone)) throw invalid(PHONE_MESSAGE);
  return phone;
}

export async function companyCodes(db: Db): Promise<string[]> {
  return (await db.query<{ code: string }>('select code from companies order by sort_order')).rows.map((r) => r.code);
}

// Sales managers may edit only customers of their own companies; admin and accounts may edit any.
export function assertCanEditCompanies(user: AuthUser, companies: string[]) {
  if (user.role === 'admin' || user.role === 'accounts') return;
  if (user.role === 'sales_manager' && companies.every((c) => user.companies.includes(c))) return;
  throw notAllowed();
}
