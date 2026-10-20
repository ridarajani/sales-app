import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { normalisePhone } from '../src/users.js';
import { freshDb } from './helpers.js';
import { bearer, get, login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db);
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('seeded admin logs in and gets a token and all four companies', async () => {
  const { res } = await login(app, '0990000000', '1234');
  expect(res.statusCode).toBe(200);
  const body = res.json();
  expect(typeof body.token).toBe('string');
  expect(body.user).toMatchObject({ name: 'Admin', phone: '0990000000', role: 'admin', managerId: null });
  expect(body.user.companies).toEqual(['LV', 'ZT', 'ZF', 'GL']);
});

test('wrong PIN is 401 and unknown phone gives the same message', async () => {
  const wrong = await login(app, '0990000000', '9999');
  const unknown = await login(app, '0881112222', '1234');
  expect(wrong.res.statusCode).toBe(401);
  expect(unknown.res.statusCode).toBe(401);
  expect(wrong.res.json()).toEqual({ error: 'wrong_login', message: 'Wrong phone number or PIN.' });
  expect(unknown.res.json()).toEqual(wrong.res.json());
});

test('PIN must be 4 digits', async () => {
  const { res } = await login(app, '0990000000', '12');
  expect(res.statusCode).toBe(400);
  expect(res.json().error).toBe('invalid');
});

test('phone with +265, spaces and dashes is normalised', async () => {
  const { res } = await login(app, '+265 99 000 0000', '1234');
  expect(res.statusCode).toBe(200);
  const dashed = await login(app, '099-000-0000', '1234');
  expect(dashed.res.statusCode).toBe(200);
});

test('me works with a token, fails with a bad token, and fails after logout', async () => {
  const { token } = await login(app, '0990000000', '1234');
  const me = await get(app, token, '/auth/me');
  expect(me.statusCode).toBe(200);
  expect(me.json().role).toBe('admin');
  expect((await get(app, 'not-a-token', '/auth/me')).statusCode).toBe(401);
  expect((await app.inject({ method: 'GET', url: '/auth/me' })).statusCode).toBe(401);
  const out = await post(app, token, '/auth/logout');
  expect(out.json()).toEqual({ ok: true });
  expect((await get(app, token, '/auth/me')).statusCode).toBe(401);
});

test('5 wrong tries lock the account, even for the right PIN', async () => {
  const { token } = await login(app, '0990000000', '1234');
  await post(app, token, '/users', { name: 'Lock Test', phone: '0991110001', role: 'warehouse', companies: ['LV'], pin: '1111' });
  for (let i = 0; i < 4; i++) {
    expect((await login(app, '0991110001', '0000')).res.statusCode).toBe(401);
  }
  const fifth = await login(app, '0991110001', '0000');
  expect(fifth.res.statusCode).toBe(423);
  expect(fifth.res.json().error).toBe('locked');
  const right = await login(app, '0991110001', '1111');
  expect(right.res.statusCode).toBe(423);
  expect(right.res.json().message).toMatch(/Try again in \d+ minutes/);
});

test('a switched off user gets 403 switched_off', async () => {
  const { token } = await login(app, '0990000000', '1234');
  const made = await post(app, token, '/users', { name: 'Off Test', phone: '0991110002', role: 'driver', companies: ['ZF'], pin: '2222' });
  await post(app, token, `/users/${made.json().id}/switch-off`);
  const { res } = await login(app, '0991110002', '2222');
  expect(res.statusCode).toBe(403);
  expect(res.json()).toEqual({ error: 'switched_off', message: 'This account is switched off. Ask the admin.' });
});

test('errors use the same envelope for unknown routes', async () => {
  const res = await app.inject({ method: 'GET', url: '/nothing-here', headers: bearer('x') });
  expect(res.statusCode).toBe(404);
  expect(res.json().error).toBe('not_found');
});

test('phone numbers in different forms all normalise the same way', () => {
  for (const p of ['+265 99 123 4567', '0991234567', '265991234567', '+2650991234567', '00265 99 123 4567', '(099) 123-4567']) {
    expect(normalisePhone(p)).toBe('0991234567');
  }
});

test('a badly formed phone at login is the normal 401', async () => {
  const { res } = await login(app, '123456789', '1234');
  expect(res.statusCode).toBe(401);
  expect(res.json().message).toBe('Wrong phone number or PIN.');
});

async function makeUser(phone: string, pin = '1111') {
  const { token } = await login(app, '0990000000', '1234');
  const r = await post(app, token, '/users', { name: 'T ' + phone, phone, role: 'warehouse', companies: ['LV'], pin });
  expect(r.statusCode).toBe(201);
  return r.json().id as number;
}

test('12 logins at once cannot get round the lock', async () => {
  const id = await makeUser('0994440001');
  const tries = [...Array(11).fill('0000'), '1111'];
  const results = await Promise.all(tries.map((pin) => login(app, '0994440001', pin)));
  const codes = results.map((r) => r.res.statusCode);
  const rightCode = codes[11]!;
  const wrongCodes = codes.slice(0, 11);
  expect(wrongCodes.every((c) => c === 401 || c === 423)).toBe(true);
  expect([200, 423]).toContain(rightCode);
  const n401 = wrongCodes.filter((c) => c === 401).length;
  if (rightCode === 423) expect(n401).toBe(4);
  else expect(n401).toBeLessThanOrEqual(8);
  const { rows } = await db.query('select failed_pin_attempts as f, locked_until as l from users where id = $1', [id]);
  expect(rows[0].f).toBeLessThan(5);
  if (rightCode === 423) expect(rows[0].l).not.toBeNull();
});

test('a lock that has run out lets the user in', async () => {
  const id = await makeUser('0994440002');
  await db.query("update users set locked_until = now() - interval '1 minute', failed_pin_attempts = 0 where id = $1", [id]);
  expect((await login(app, '0994440002', '1111')).res.statusCode).toBe(200);
  await db.query("update users set locked_until = now() + interval '10 minutes' where id = $1", [id]);
  expect((await login(app, '0994440002', '1111')).res.statusCode).toBe(423);
});

test('a good login resets the wrong-try counter', async () => {
  const id = await makeUser('0994440003');
  await login(app, '0994440003', '0000');
  await login(app, '0994440003', '0000');
  const before = await db.query('select failed_pin_attempts as f from users where id = $1', [id]);
  expect(before.rows[0].f).toBe(2);
  expect((await login(app, '0994440003', '1111')).res.statusCode).toBe(200);
  const after = await db.query('select failed_pin_attempts as f, locked_until as l from users where id = $1', [id]);
  expect(after.rows[0]).toEqual({ f: 0, l: null });
});

test('the phone saved for a failed login is cut to 20 characters', async () => {
  await login(app, '9'.repeat(60), '1234');
  const { rows } = await db.query("select details from audit_log where action = 'login_failed' order by id desc limit 1");
  expect(rows[0].details.phone).toHaveLength(20);
});

const checkPhone = (phone: string) => app.inject({ method: 'POST', url: '/auth/check-phone', payload: { phone } });

test('check-phone returns only name and role for an approved phone', async () => {
  const res = await checkPhone('0990000000');
  expect(res.statusCode).toBe(200);
  expect(res.json()).toEqual({ name: 'Admin', role: 'admin' });
  const spaced = await checkPhone('+265 99 000 0000');
  expect(spaced.statusCode).toBe(200);
  expect(spaced.json().name).toBe('Admin');
});

test('check-phone: unknown or garbage phone is 404 with the same message', async () => {
  for (const p of ['0885559999', 'garbage', '123']) {
    const res = await checkPhone(p);
    expect(res.statusCode).toBe(404);
    expect(res.json()).toEqual({ error: 'unknown_phone', message: 'This phone number is not set up yet. Ask the admin to add you.' });
  }
});

test('check-phone: switched off is 403 and locked is 423', async () => {
  const id = await makeUser('0994440010');
  await db.query('update users set active = false where id = $1', [id]);
  const off = await checkPhone('0994440010');
  expect(off.statusCode).toBe(403);
  expect(off.json().error).toBe('switched_off');
  await db.query("update users set active = true, locked_until = now() + interval '10 minutes' where id = $1", [id]);
  const locked = await checkPhone('0994440010');
  expect(locked.statusCode).toBe(423);
  expect(locked.json().message).toMatch(/Try again in \d+ minutes/);
});
