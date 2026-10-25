import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { buildApp } from '../app.js';
import { config } from '../config.js';
import { createPool } from './pool.js';
import { migrate } from './migrate.js';
import { seed } from './seed.js';

// Made-up data for showing and training: staff for every job, products, shops, and a month of
// orders, deliveries, payments, returns and a trip, all made through the server's own routes so
// every number adds up. Names and phone numbers are invented. It refuses to run on a database that
// already has orders, and never in production.
//
//   npm run demo      then sign in with any phone below and PIN 1111 (Admin uses BOOTSTRAP_ADMIN_*)

const PIN = '1111';
// A 1x1 PNG, standing in for a customer's signature.
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

export const DEMO_STAFF = [
  { key: 'mgmt', name: 'Grace Phiri', phone: '0880000001', role: 'management', companies: ['LV', 'ZT', 'ZF', 'GL'] },
  { key: 'sm1', name: 'Peter Banda', phone: '0880000002', role: 'sales_manager', companies: ['LV', 'ZT'] },
  { key: 'sm2', name: 'Ruth Mwale', phone: '0880000003', role: 'sales_manager', companies: ['ZF', 'GL'] },
  { key: 'sales1', name: 'James Tembo', phone: '0880000004', role: 'salesman', companies: ['LV', 'ZT'], manager: 'sm1' },
  { key: 'sales2', name: 'Mary Chirwa', phone: '0880000005', role: 'salesman', companies: ['ZF', 'GL'], manager: 'sm2' },
  { key: 'wh1', name: 'John Kamanga', phone: '0880000006', role: 'warehouse', companies: ['LV', 'ZT'] },
  { key: 'wh2', name: 'Esther Gondwe', phone: '0880000007', role: 'warehouse', companies: ['ZF', 'GL'] },
  { key: 'driver', name: 'Moses Zulu', phone: '0880000008', role: 'driver', companies: ['LV', 'ZT', 'ZF', 'GL'] },
  { key: 'cashier', name: 'Alice Nkhoma', phone: '0880000009', role: 'cashier', companies: ['LV', 'ZT', 'ZF', 'GL'] },
  { key: 'accounts', name: 'Daniel Mbewe', phone: '0880000010', role: 'accounts', companies: ['LV', 'ZT', 'ZF', 'GL'] },
] as const;

type Key = (typeof DEMO_STAFF)[number]['key'] | 'admin';

const PRODUCTS = [
  { code: 'LV-RICE-5', companyCode: 'LV', brand: 'Sunrise', name: 'Rice', packSize: '5kg', piecesPerCarton: 4, cartons: 120, low: 30 },
  { code: 'LV-OIL-2', companyCode: 'LV', brand: 'Golden', name: 'Cooking oil', packSize: '2L', piecesPerCarton: 6, cartons: 80, low: 20 },
  { code: 'ZT-SOAP-800', companyCode: 'ZT', brand: 'Clean', name: 'Washing soap', packSize: '800g', piecesPerCarton: 12, cartons: 18, low: 25 },
  { code: 'ZT-TEA-100', companyCode: 'ZT', brand: 'Hill', name: 'Tea bags', packSize: '100s', piecesPerCarton: 24, cartons: 60, low: 10 },
  { code: 'ZF-JUICE-1', companyCode: 'ZF', brand: 'Fresh', name: 'Orange juice', packSize: '1L', piecesPerCarton: 12, cartons: 90, low: 20 },
  { code: 'GL-BISC-200', companyCode: 'GL', brand: 'Crunch', name: 'Biscuits', packSize: '200g', piecesPerCarton: 36, cartons: 70, low: 15 },
];

const SHOPS = [
  { name: 'Kamuzu Grocers', channel: 'wholesaler', area: 'Area 25', phone: '0880100001', companies: ['LV', 'ZT'], days: 30, limit: 1_500_000 },
  { name: 'Lilongwe Superstore', channel: 'supermarket', area: 'City Centre', phone: '0880100002', companies: ['LV', 'ZT', 'ZF', 'GL'], days: 30, limit: 5_000_000 },
  { name: 'Mchinji Traders', channel: 'upcountry', area: 'Mchinji', phone: '0880100003', companies: ['LV', 'ZT'], days: 15 },
  { name: 'Area 47 Minimart', channel: 'wholesaler', area: 'Area 47', phone: '0880100004', companies: ['ZF', 'GL'], days: 30, limit: 400_000 },
  { name: 'Old Town Shop', channel: 'wholesaler', area: 'Old Town', phone: '0880100005', companies: ['LV', 'ZT', 'ZF', 'GL'], days: 0 },
  { name: 'Market Stall 12', channel: 'minibus', area: 'Area 2 market', phone: '0880100006', companies: ['LV', 'ZT'], days: 0 },
];

const day = (offset: number) => new Date(Date.now() + 2 * 3_600_000 + offset * 86_400_000).toISOString().slice(0, 10);

export async function demo(databaseUrl = config.databaseUrl) {
  if (process.env.NODE_ENV === 'production') throw new Error('The demo data is never put on the real server.');
  const db = createPool(databaseUrl);
  await migrate(db);
  await seed(db);
  const { rows } = await db.query('select 1 from orders limit 1');
  if (rows.length) {
    await db.end();
    throw new Error('This database already has orders. The demo data only goes into an empty one.');
  }
  for (const dir of [config.uploadDir, config.brandingDir]) mkdirSync(dir, { recursive: true });
  const app = buildApp(db);
  const tokens = new Map<Key, string>();
  const ids = new Map<Key, number>();

  const call = async (who: Key, method: 'GET' | 'POST' | 'PUT', url: string, payload?: unknown) => {
    const r = await app.inject({ method, url, headers: { authorization: `Bearer ${tokens.get(who)}` }, payload: payload as object });
    if (r.statusCode >= 400) throw new Error(`${method} ${url} as ${who}: ${r.statusCode} ${r.body}`);
    return r.json();
  };
  const signIn = async (who: Key, phone: string, pin: string) => {
    const r = await app.inject({ method: 'POST', url: '/auth/login', payload: { phone, pin } });
    if (r.statusCode !== 200) throw new Error(`Sign-in for ${who} failed: ${r.body}`);
    tokens.set(who, r.json().token);
  };

  await signIn('admin', config.bootstrapAdminPhone, config.bootstrapAdminPin);
  for (const s of DEMO_STAFF) {
    const managerId = 'manager' in s ? ids.get(s.manager) : undefined;
    const made = await call('admin', 'POST', '/users', { name: s.name, phone: s.phone, role: s.role, companies: s.companies, pin: PIN, managerId });
    ids.set(s.key, made.id);
    await signIn(s.key, s.phone, PIN);
  }

  const trucks = [
    (await call('admin', 'POST', '/vehicles', { plate: 'DEMO 101', description: '5 tonne truck', facilityCode: 'F1', defaultDriverId: ids.get('driver') })).id,
    (await call('admin', 'POST', '/vehicles', { plate: 'DEMO 202', description: '3 tonne truck', facilityCode: 'F2', defaultDriverId: ids.get('driver') })).id,
  ];
  const product = new Map<string, { id: number; companyCode: string; ppc: number }>();
  for (const p of PRODUCTS) {
    const made = await call('admin', 'POST', '/products', {
      code: p.code, companyCode: p.companyCode, brand: p.brand, name: p.name, packSize: p.packSize,
      piecesPerCarton: p.piecesPerCarton, lowStockCartons: p.low,
    });
    product.set(p.code, { id: made.id, companyCode: p.companyCode, ppc: p.piecesPerCarton });
    const wh = ['LV', 'ZT'].includes(p.companyCode) ? 'wh1' : 'wh2';
    await call(wh, 'POST', '/stock/receipts', {
      companyCode: p.companyCode,
      receivedOn: day(-20),
      supplier: 'Demo supplier',
      lines: [
        { productId: made.id, batchNo: `${p.code.split('-')[1]}-A`, expiryDate: day(60), cartons: Math.ceil(p.cartons / 3) },
        { productId: made.id, batchNo: `${p.code.split('-')[1]}-B`, expiryDate: day(330), cartons: p.cartons - Math.ceil(p.cartons / 3) },
      ],
    });
  }
  const shop = new Map<string, number>();
  for (const s of SHOPS) {
    const made = await call('admin', 'POST', '/customers', {
      name: s.name, channel: s.channel, area: s.area, phone: s.phone,
      companies: s.companies.map((code) => ({ code, paymentTermsDays: s.days, creditLimit: s.limit ?? null })),
    });
    shop.set(s.name, made.id);
  }

  // Orders delivered by truck: booked, planned, loaded, invoiced, out of the gate, delivered and signed.
  type Line = [code: string, cartons: number, price: number];
  const deliver = async (facility: 'F1' | 'F2', orders: { shop: string; lines: Line[]; take?: number }[]) => {
    const [manager, salesman, wh, truck] = facility === 'F1' ? (['sm1', 'sales1', 'wh1', trucks[0]] as const) : (['sm2', 'sales2', 'wh2', trucks[1]] as const);
    const made = [];
    for (const o of orders) {
      made.push(
        await call(salesman, 'POST', '/orders', {
          customerId: shop.get(o.shop),
          invoiceType: 'vat',
          salesmanId: ids.get(salesman),
          lines: o.lines.map(([code, cartons, price]) => ({ productId: product.get(code)!.id, cartons, pricePerCarton: price })),
        }),
      );
    }
    const plan = await call(manager, 'POST', '/loading-plans', { facilityCode: facility, vehicleId: truck });
    const items = [];
    for (const o of made) for (const l of (await call(manager, 'GET', `/orders/${o.id}`)).lines) items.push({ lineId: l.id, pieces: l.pieces });
    await call(manager, 'PUT', `/loading-plans/${plan.id}/items`, { items });
    await call(manager, 'POST', `/loading-plans/${plan.id}/send`);
    await call(wh, 'POST', `/loading-plans/${plan.id}/load`);
    await call(manager, 'POST', `/loading-plans/${plan.id}/invoices`);
    const { rows: invoices } = await db.query<{ id: number; customerId: number; companyCode: string }>(
      'select id, customer_id as "customerId", company_code as "companyCode" from invoices where plan_id = $1 order by id',
      [plan.id],
    );
    await call(wh, 'POST', `/loading-plans/${plan.id}/gate`);
    const drops = (await call('driver', 'GET', `/loading-plans/${plan.id}`)).drops as { customerId: number; lines: { itemId: number; cartons: number }[] }[];
    for (const d of drops) {
      const o = orders.find((x) => shop.get(x.shop) === d.customerId)!;
      await call('driver', 'POST', `/loading-plans/${plan.id}/deliveries`, {
        customerId: d.customerId,
        receivedBy: 'Store keeper',
        signature: PNG,
        lines: d.lines.map((l, i) => ({
          itemId: l.itemId,
          cartons: i === 0 && o.take != null ? o.take : l.cartons,
          ...(i === 0 && o.take != null ? { reason: 'No space in the shop' } : {}),
        })),
      });
    }
    return { plan, invoices };
  };

  const weekAgo = await deliver('F1', [
    { shop: 'Kamuzu Grocers', lines: [['LV-RICE-5', 20, 52000], ['ZT-TEA-100', 5, 48000]] },
    { shop: 'Lilongwe Superstore', lines: [['LV-OIL-2', 15, 61000], ['ZT-SOAP-800', 10, 30000]] },
  ]);
  const thisWeek = await deliver('F1', [
    { shop: 'Mchinji Traders', lines: [['LV-RICE-5', 12, 53000]] },
    { shop: 'Kamuzu Grocers', lines: [['LV-OIL-2', 8, 61000]], take: 6 },
  ]);
  const f2 = await deliver('F2', [
    { shop: 'Area 47 Minimart', lines: [['ZF-JUICE-1', 25, 21000], ['GL-BISC-200', 10, 34000]] },
    { shop: 'Lilongwe Superstore', lines: [['GL-BISC-200', 20, 33000]] },
  ]);

  // Spread the invoices over the month so the chart and the overdue list have something to show.
  const when = async (invoiceIds: number[], offset: number, terms = 30) => {
    for (const id of invoiceIds) {
      await db.query(`update invoices set invoice_date = $2::date, due_date = $2::date + $3::int where id = $1`, [id, day(offset), terms]);
    }
  };
  await when(weekAgo.invoices.map((i) => i.id), -40);
  await when(thisWeek.invoices.map((i) => i.id), -3);
  await when(f2.invoices.map((i) => i.id), -12);

  // Payments: Kamuzu pays part of a late invoice in cash; the salesman spends a little and hands over.
  const kamuzu = weekAgo.invoices.find((i) => i.customerId === shop.get('Kamuzu Grocers') && i.companyCode === 'LV')!;
  await call('sales1', 'POST', '/collections', {
    customerId: shop.get('Kamuzu Grocers'), companyCode: 'LV', invoiceId: kamuzu.id, mode: 'cash', amount: 500000, receivedBy: 'Owner', signature: PNG,
  });
  await call('sales1', 'POST', '/expenses', { companyCode: 'LV', amount: 15000, description: 'Fuel for the motorbike' });
  await call('sales1', 'POST', '/handovers');
  const superstore = f2.invoices.find((i) => i.customerId === shop.get('Lilongwe Superstore') && i.companyCode === 'GL')!;
  await call('sales2', 'POST', '/collections', {
    customerId: shop.get('Lilongwe Superstore'), companyCode: 'GL', invoiceId: superstore.id, mode: 'bank', amount: 300000, reference: 'DEMO-TRF-1', proof: PNG, receivedBy: 'Accounts clerk', signature: PNG,
  });

  // A minibus trip out today, with one cash sale at the market.
  const trip = await call('sm1', 'POST', '/trips', {
    kind: 'minibus', facilityCode: 'F1', tripDate: day(0), route: 'Area 2 and Area 3 markets', stops: ['Area 2 market', 'Area 3 market'],
    vehicleId: trucks[0], leaderId: ids.get('sales1'), staff: 'James Tembo', float: 20000,
    lines: [{ productId: product.get('LV-RICE-5')!.id, cartons: 10 }, { productId: product.get('ZT-TEA-100')!.id, cartons: 4 }],
  });
  await call('wh1', 'POST', `/trips/${trip.id}/load`);
  await call('sales1', 'POST', `/trips/${trip.id}/sales`, {
    requestId: 'demo-sale-1', customerId: shop.get('Market Stall 12'), stop: 'Area 2 market', payment: 'cash', invoiceType: 'plain',
    receivedBy: 'Stall owner', signature: PNG,
    lines: [{ productId: product.get('LV-RICE-5')!.id, cartons: 2, pricePerCarton: 54000 }],
  });

  // Targets for the month, and one order still waiting for a vehicle.
  const month = day(0).slice(0, 7);
  await call('sm1', 'PUT', '/targets', { salesmanId: ids.get('sales1'), month, amount: 3_000_000 });
  await call('sm2', 'PUT', '/targets', { salesmanId: ids.get('sales2'), month, amount: 2_000_000 });
  await call('sales2', 'POST', '/orders', {
    customerId: shop.get('Old Town Shop'), invoiceType: 'plain', salesmanId: ids.get('sales2'),
    lines: [{ productId: product.get('ZF-JUICE-1')!.id, cartons: 6, pricePerCarton: 21500 }],
  });

  await app.close();
  await db.end();
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await demo();
  console.log(`Demo data is in. Sign in with PIN ${PIN}:`);
  for (const s of DEMO_STAFF) console.log(`  ${s.phone}  ${s.name} (${s.role})`);
  console.log(`  Admin: ${config.bootstrapAdminPhone} with the BOOTSTRAP_ADMIN_PIN`);
}
