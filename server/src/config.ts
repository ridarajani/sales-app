import { readFileSync, existsSync } from 'node:fs';

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
};
