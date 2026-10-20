import type { FastifyInstance } from 'fastify';

export async function login(app: FastifyInstance, phone: string, pin: string) {
  const res = await app.inject({ method: 'POST', url: '/auth/login', payload: { phone, pin } });
  return { res, token: res.statusCode === 200 ? (res.json().token as string) : '' };
}

export const bearer = (token: string) => ({ authorization: `Bearer ${token}` });

export async function post(app: FastifyInstance, token: string, url: string, payload?: unknown) {
  return app.inject({ method: 'POST', url, headers: bearer(token), payload: payload as object });
}

export async function get(app: FastifyInstance, token: string, url: string) {
  return app.inject({ method: 'GET', url, headers: bearer(token) });
}
