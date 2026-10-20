import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Db } from '../src/db/pool.js';
import { freshDb } from './helpers.js';
import { login, post } from './session-helpers.js';

let db: Db;
let app: ReturnType<typeof buildApp>;
let admin: string;
const apkDir = mkdtempSync(join(tmpdir(), 'sales-apk-'));

beforeAll(async () => {
  db = await freshDb();
  app = buildApp(db, { apkDir });
  admin = (await login(app, '0990000000', '1234')).token;
  for (const f of ['sales-0.1.0.apk', 'sales-0.2.0.apk']) writeFileSync(join(apkDir, f), 'fake apk bytes');
  writeFileSync(join(apkDir, 'notes.txt'), 'x');
});
afterAll(async () => {
  await app.close();
  await db.end();
});

test('no version yet: 404 and the download page says so', async () => {
  const res = await app.inject({ method: 'GET', url: '/app/version?platform=android' });
  expect(res.statusCode).toBe(404);
  expect(res.json().error).toBe('no_version');
  const page = await app.inject({ method: 'GET', url: '/download' });
  expect(page.statusCode).toBe(200);
  expect(page.headers['content-type']).toContain('text/html');
  expect(page.body).toContain('No version yet.');
});

test('only an admin can register a version', async () => {
  const payload = { platform: 'android', versionCode: 1, versionName: '0.1.0', fileName: 'sales-0.1.0.apk' };
  expect((await app.inject({ method: 'POST', url: '/app/versions', payload })).statusCode).toBe(401);
  const sm = await post(app, admin, '/users', { name: 'W', phone: '0993000001', role: 'warehouse', companies: ['LV'], pin: '1111' });
  expect(sm.statusCode).toBe(201);
  const w = (await login(app, '0993000001', '1111')).token;
  expect((await post(app, w, '/app/versions', payload)).statusCode).toBe(403);
});

test('registered versions: latest is returned with a download link, duplicates are 409', async () => {
  const first = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 1, versionName: '0.1.0', fileName: 'sales-0.1.0.apk' });
  expect(first.statusCode).toBe(201);
  await post(app, admin, '/app/versions', { platform: 'android', versionCode: 2, versionName: '0.2.0', fileName: 'sales-0.2.0.apk', notes: 'Login' });
  const dup = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 2, versionName: 'x', fileName: 'sales-0.1.0.apk' });
  expect(dup.statusCode).toBe(409);
  const bad = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 3, versionName: 'x', fileName: '../x.apk' });
  expect(bad.statusCode).toBe(400);
  const notApk = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 3, versionName: 'x', fileName: 'notes.txt' });
  expect(notApk.statusCode).toBe(400);
  const hidden = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 3, versionName: 'x', fileName: '.hidden.apk' });
  expect(hidden.statusCode).toBe(400);
  const missing = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 3, versionName: 'x', fileName: 'missing.apk' });
  expect(missing.statusCode).toBe(400);
  expect(missing.json().message).toBe('That file is not in the download folder yet.');
  const huge = await post(app, admin, '/app/versions', { platform: 'android', versionCode: 99999999999, versionName: 'x', fileName: 'sales-0.1.0.apk' });
  expect(huge.statusCode).toBe(400);

  const res = await app.inject({ method: 'GET', url: '/app/version?platform=android' });
  expect(res.statusCode).toBe(200);
  expect(res.json()).toEqual({
    platform: 'android',
    versionCode: 2,
    versionName: '0.2.0',
    notes: 'Login',
    downloadUrl: 'http://localhost:3000/download/sales-0.2.0.apk',
    downloadPath: '/download/sales-0.2.0.apk',
  });
});

test('download page shows the version and a button; the file is served', async () => {
  const page = await app.inject({ method: 'GET', url: '/download' });
  expect(page.body).toContain('0.2.0');
  expect(page.body).toContain('Download for Android');
  expect(page.body).toContain('/download/sales-0.2.0.apk');
  expect(page.body).toContain('open the file and tap Install');
  const file = await app.inject({ method: 'GET', url: '/download/sales-0.2.0.apk' });
  expect(file.statusCode).toBe(200);
  expect(file.body).toBe('fake apk bytes');
  const sneaky = await app.inject({ method: 'GET', url: '/download/..%2f.env' });
  expect(sneaky.statusCode).toBe(404);
});

test('the default apk folder is server/apk', async () => {
  const { config } = await import('../src/config.js');
  expect(config.apkDir).toBe(join(process.cwd(), 'apk'));
});
