import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Db } from '../db/pool.js';
import { findSession } from './session.js';

export type AuthUser = {
  id: number;
  name: string;
  phone: string;
  role: string;
  companies: string[];
  managerId: number | null;
};

declare module 'fastify' {
  interface FastifyRequest {
    user: AuthUser;
    token: string;
  }
}

export class HttpError extends Error {
  constructor(
    public statusCode: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

export const notAllowed = () => new HttpError(403, 'not_allowed', 'You cannot do this.');

// Use as an onRequest hook on routes that need a signed-in user.
export function requireAuth(db: Db) {
  return async (request: FastifyRequest, _reply: FastifyReply) => {
    const header = request.headers.authorization ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
    const found = token ? await findSession(db, token) : null;
    if (!found || !found.active) throw new HttpError(401, 'not_signed_in', 'Please sign in again.');
    const { active: _active, ...user } = found;
    request.user = user;
    request.token = token;
  };
}

// Use as a preHandler, after requireAuth.
export function requireRole(...roles: string[]) {
  return async (request: FastifyRequest, _reply: FastifyReply) => {
    if (!roles.includes(request.user.role)) throw notAllowed();
  };
}

// Every query on company data must be limited to the user's companies:
//   where company_code = any($1)  with  [request.user.companies]
// and any single company named by the client must pass assertCompany first.
export function assertCompany(user: AuthUser, companyCode: string) {
  if (!user.companies.includes(companyCode)) throw notAllowed();
}
