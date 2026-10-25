import { buildApp } from './app.js';
import { config } from './config.js';
import { createPool } from './db/pool.js';
import { migrate } from './db/migrate.js';
import { seed } from './db/seed.js';

// On the live server phones must talk to it over HTTPS: PINs and signatures go over this link.
if (process.env.NODE_ENV === 'production' && !config.publicUrl.startsWith('https://')) {
  throw new Error(`PUBLIC_URL must start with https:// on the live server. It is ${config.publicUrl}.`);
}

const db = createPool();
await migrate(db);
// The four companies and the first Admin, the first time only. Refuses the placeholder Admin in production.
await seed(db);
const app = buildApp(db);
await app.listen({ port: config.port, host: '0.0.0.0' });
