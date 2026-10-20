import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Load server/.env if it exists. Values already in the environment win.
const envFile = new URL('../.env', import.meta.url);
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]!] === undefined) process.env[m[1]!] = m[2]!;
  }
}

export const config = {
  databaseUrl: process.env.DATABASE_URL ?? 'postgres://sales:sales@localhost:5432/sales_dev',
  port: Number(process.env.PORT ?? 3000),
  publicUrl: (process.env.PUBLIC_URL ?? 'http://localhost:3000').replace(/\/+$/, ''),
  apkDir: fileURLToPath(new URL(process.env.APK_DIR ?? 'apk', envFile)),
  // Placeholders for the first admin login. Change before go-live.
  bootstrapAdminPhone: process.env.BOOTSTRAP_ADMIN_PHONE ?? '0990000000',
  bootstrapAdminPin: process.env.BOOTSTRAP_ADMIN_PIN ?? '1234',
};
