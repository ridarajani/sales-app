import { buildApp } from './app.js';
import { config } from './config.js';
import { createPool } from './db/pool.js';
import { migrate } from './db/migrate.js';

const db = createPool();
await migrate(db);
const app = buildApp(db);
await app.listen({ port: config.port, host: '0.0.0.0' });
