import fastifyStatic from '@fastify/static';
import Fastify from 'fastify';
import { HttpError } from './auth/guard.js';
import { config } from './config.js';
import type { Db } from './db/pool.js';
import { appRoutes } from './routes/app.js';
import { authRoutes } from './routes/auth.js';
import { userRoutes } from './routes/users.js';
import { companyRoutes } from './lists/companies.js';
import { customerRoutes } from './lists/customers.js';
import { importRoutes } from './lists/import.js';
import { openingBalanceRoutes } from './lists/openingBalances.js';
import { productRoutes } from './lists/products.js';
import { vehicleRoutes } from './lists/vehicles.js';
import { stockRoutes } from './stock/stock.js';
import { orderRoutes } from './orders/orders.js';
import { loadingRoutes } from './loading/loading.js';
import { invoiceRoutes } from './invoices/invoices.js';
import { deliveryRoutes } from './delivery/delivery.js';
import { collectionRoutes } from './cash/collections.js';
import { cashRoutes } from './cash/cash.js';
import { airtelRoutes } from './cash/airtel.js';
import { returnRoutes } from './returns/returns.js';
import { ledgerRoutes } from './returns/ledger.js';
import { tripRoutes } from './trips/trips.js';
import { reportRoutes } from './reports/routes.js';
import { targetRoutes } from './reports/targets.js';

export function buildApp(db: Db, options: { apkDir?: string; uploadDir?: string; brandingDir?: string } = {}) {
  const app = Fastify({ logger: process.env.NODE_ENV !== 'test' });
  const apkDir = options.apkDir ?? config.apkDir;

  // The phone app may send a JSON content type with no body (logout, switch on and off). Treat that as {}.
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (_request, body, done) => {
    if (body === '' || body === undefined) return done(null, {});
    try {
      done(null, JSON.parse(body as string));
    } catch {
      done(Object.assign(new Error('bad json'), { statusCode: 400 }), undefined);
    }
  });

  // Every error goes out as { error, message } in plain words.
  app.setErrorHandler((err: Error & { statusCode?: number }, request, reply) => {
    if (err instanceof HttpError) return reply.code(err.statusCode).send({ error: err.code, message: err.message });
    const status = err.statusCode ?? 500;
    if (status >= 500) {
      request.log.error(err);
      return reply.code(500).send({ error: 'server_error', message: 'Something went wrong. Please try again.' });
    }
    return reply.code(status).send({ error: 'invalid', message: 'Something is wrong. Please check and try again.' });
  });
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ error: 'not_found', message: 'That was not found.' }));

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

  // The Android app files are copied into this folder by hand or by CI.
  app.register(fastifyStatic, { root: apkDir, prefix: '/download/', decorateReply: false });

  authRoutes(app, db);
  userRoutes(app, db);
  appRoutes(app, db, apkDir);
  companyRoutes(app, db);
  customerRoutes(app, db);
  productRoutes(app, db);
  vehicleRoutes(app, db);
  openingBalanceRoutes(app, db);
  importRoutes(app, db);
  stockRoutes(app, db);
  orderRoutes(app, db);
  loadingRoutes(app, db);
  invoiceRoutes(app, db, options.brandingDir ?? config.brandingDir);
  deliveryRoutes(app, db, options.uploadDir ?? config.uploadDir);
  collectionRoutes(app, db, options.uploadDir ?? config.uploadDir);
  cashRoutes(app, db, options.uploadDir ?? config.uploadDir);
  airtelRoutes(app, db);
  returnRoutes(app, db);
  ledgerRoutes(app, db, options.brandingDir ?? config.brandingDir);
  tripRoutes(app, db, options.uploadDir ?? config.uploadDir);
  reportRoutes(app, db);
  targetRoutes(app, db);

  return app;
}
