import { existsSync } from 'node:fs';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { requireAuth, requireRole } from '../auth/guard.js';
import { config } from '../config.js';
import type { Db } from '../db/pool.js';
import { INT4_MAX, parse } from '../users.js';

const platformQuery = z.object({ platform: z.literal('android').default('android') });
const versionBody = z.object({
  platform: z.literal('android'),
  versionCode: z.number().int().positive().max(INT4_MAX),
  versionName: z.string().trim().min(1, 'Please type the version name.'),
  // Just a file name, never a path.
  fileName: z
    .string()
    .regex(/^[A-Za-z0-9._-]+$/, 'The file name can only have letters, numbers, dots, dashes and underscores.')
    .refine((f) => f.endsWith('.apk') && !f.startsWith('.'), 'The file name must end with .apk.'),
  notes: z.string().optional(),
});

type VersionRow = { platform: string; versionCode: number; versionName: string; notes: string | null; fileName: string };

async function latest(db: Db, platform: string): Promise<VersionRow | undefined> {
  const { rows } = await db.query<VersionRow>(
    `select platform, version_code as "versionCode", version_name as "versionName", notes, file_name as "fileName"
     from app_versions where platform = $1 order by version_code desc limit 1`,
    [platform],
  );
  return rows[0];
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function page(body: string) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sales App</title>
<style>
  body { font-family: system-ui, sans-serif; background: #FBF7F2; color: #222; margin: 0; padding: 32px 20px; text-align: center; }
  h1 { color: #7A1F35; }
  a.button { display: block; max-width: 360px; margin: 24px auto; padding: 22px; background: #7A1F35; color: #fff; font-size: 22px; font-weight: bold; text-decoration: none; border-radius: 14px; }
  p { font-size: 18px; }
</style>
</head>
<body>
<h1>Sales App</h1>
${body}
</body>
</html>`;
}

export function appRoutes(app: FastifyInstance, db: Db, apkDir: string) {
  // No sign-in here: the phone app asks before the user has logged in.
  app.get('/app/version', async (request, reply) => {
    const { platform } = parse(platformQuery, request.query);
    const v = await latest(db, platform);
    if (!v) return reply.code(404).send({ error: 'no_version', message: 'No version yet.' });
    return {
      platform: v.platform,
      versionCode: v.versionCode,
      versionName: v.versionName,
      notes: v.notes,
      downloadUrl: `${config.publicUrl}/download/${v.fileName}`,
      downloadPath: `/download/${v.fileName}`,
    };
  });

  app.post('/app/versions', { onRequest: requireAuth(db), preHandler: requireRole('admin') }, async (request, reply) => {
    const b = parse(versionBody, request.body);
    if (!existsSync(join(apkDir, b.fileName))) {
      return reply.code(400).send({ error: 'invalid', message: 'That file is not in the download folder yet.' });
    }
    const { rows } = await db.query(
      `insert into app_versions (platform, version_code, version_name, file_name, notes) values ($1, $2, $3, $4, $5)
       on conflict (platform, version_code) do nothing
       returning platform, version_code as "versionCode", version_name as "versionName", file_name as "fileName", notes`,
      [b.platform, b.versionCode, b.versionName, b.fileName, b.notes ?? null],
    );
    if (!rows[0]) return reply.code(409).send({ error: 'version_exists', message: 'That version number is already registered.' });
    await audit(db, { userId: request.user.id, action: 'app_version_added', details: { versionCode: b.versionCode } });
    return reply.code(201).send(rows[0]);
  });

  app.get('/download', async (_request, reply) => {
    const v = await latest(db, 'android');
    const body = v
      ? `<p>Version ${escapeHtml(v.versionName)}</p>
<a class="button" href="/download/${encodeURIComponent(v.fileName)}">Download for Android</a>
<p>After it downloads, open the file and tap Install.</p>`
      : '<p>No version yet.</p>';
    return reply.type('text/html; charset=utf-8').send(page(body));
  });
}
