import Fastify from 'fastify';
import type { Db } from './db/pool.js';

export function buildApp(db: Db) {
  const app = Fastify({ logger: process.env.NODE_ENV !== 'test' });

  app.get('/health', async () => {
    await db.query('select 1');
    return { ok: true };
  });

  app.get('/companies', async () => {
    const { rows } = await db.query(
      'select code, name, facility_code as "facilityCode" from companies order by sort_order',
    );
    return rows;
  });

  return app;
}
