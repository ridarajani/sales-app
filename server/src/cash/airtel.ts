import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole, type AuthUser } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { nextNumber } from '../numbering.js';
import { inTransaction } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// Admin sets up who holds which wallet. The holder ticks payments, records withdrawals and checks the balance.
// Management, Accounts and the cashier can look at every wallet of their companies.
const WALLET_VIEWERS = ['admin', 'management', 'sales_manager', 'cashier', 'accounts'] as const;
const OVERSEERS = ['admin', 'management', 'cashier', 'accounts'];
const money = (n: number) => `MWK ${n.toLocaleString('en-US')}`;

const id = z.number().int().positive().max(INT4_MAX);
const kwacha = (what: string) => z.number(`Please type the ${what}.`).int('Amounts are whole kwacha.').min(0).max(10_000_000_000);
const walletBody = z.object({
  holderId: id,
  openingBalance: kwacha('opening balance').default(0),
  companies: z.array(z.string()).min(1, 'Pick the companies that use this wallet.').max(4),
  active: z.boolean().default(true),
});
const withdrawBody = z.object({
  companyCode: z.string(),
  amount: kwacha('amount taken out').positive('Please type the amount taken out.'),
  charge: kwacha('Airtel charge').default(0),
});
const checkBody = z.object({ actual: kwacha('real balance'), note: z.string().trim().max(1000).default('') });

// Wallets a user may see. $1 = their companies, $2 = their id.
const walletWhere = (user: AuthUser) =>
  OVERSEERS.includes(user.role)
    ? `$2::int is not null and exists (select 1 from airtel_wallet_companies x where x.wallet_id = w.id and x.company_code = any($1::text[]))`
    : `$1::text[] is not null and w.holder_id = $2::int`;

const BALANCE = (w: string) =>
  `(${w}.opening_balance + coalesce((select sum(e.credit - e.debit) from airtel_entries e where e.wallet_id = ${w}.id), 0))`;

// The wallet that takes a company's Airtel Money, if one is set up.
export async function walletOf(client: PoolClient | Db, companyCode: string) {
  const { rows } = await client.query<{ id: number; holderId: number; holderName: string }>(
    `select w.id, w.holder_id as "holderId", u.name as "holderName" from airtel_wallets w
       join airtel_wallet_companies wc on wc.wallet_id = w.id join users u on u.id = w.holder_id
     where wc.company_code = $1 and w.active`,
    [companyCode],
  );
  return rows[0] ?? null;
}

// A customer's Airtel payment goes into the company's wallet, waiting until the holder ticks it.
export async function addAirtelPayment(
  client: PoolClient,
  p: { companyCode: string; amount: number; reference: string; description: string; collectionId: number; userId: number },
) {
  const wallet = await walletOf(client, p.companyCode);
  if (!wallet) return;
  await client.query(
    `insert into airtel_entries (wallet_id, company_code, kind, credit, description, reference, collection_id, status, created_by)
     values ($1, $2, 'payment', $3, $4, $5, $6, 'waiting', $7)`,
    [wallet.id, p.companyCode, p.amount, p.description, p.reference, p.collectionId, p.userId],
  );
}

// An expense paid by Airtel Money comes out of the company's wallet. Only its holder can pay from it.
export async function addAirtelExpense(
  client: PoolClient,
  p: { companyCode: string; amount: number; description: string; expenseId: number; user: AuthUser },
) {
  const wallet = await walletOf(client, p.companyCode);
  if (!wallet) throw invalid('This company has no Airtel Money wallet yet. Please pay in cash.');
  if (wallet.holderId !== p.user.id) throw invalid(`Only ${wallet.holderName} can pay from this Airtel Money wallet. Please pay in cash.`);
  await client.query(
    `insert into airtel_entries (wallet_id, company_code, kind, debit, description, expense_id, status, created_by)
     values ($1, $2, 'expense', $3, $4, $5, 'ok', $6)`,
    [wallet.id, p.companyCode, p.amount, p.description, p.expenseId, p.user.id],
  );
}

async function lockWallet(client: PoolClient, user: AuthUser, walletId: number) {
  const { rows } = await client.query<{ id: number; holderId: number; balance: number; companies: string[] }>(
    `select w.id, w.holder_id as "holderId", ${BALANCE('w')}::float8 as balance,
       array(select company_code from airtel_wallet_companies where wallet_id = w.id order by company_code) as companies
     from airtel_wallets w where w.id = $3 and ${walletWhere(user)} for update of w`,
    [user.companies, user.id, walletId],
  );
  if (!rows[0]) throw notFound('wallet');
  return rows[0];
}
const assertHolder = (user: AuthUser, w: { holderId: number }) => {
  if (w.holderId !== user.id && user.role !== 'admin') throw notAllowed();
};

async function walletDetail(db: Db, user: AuthUser, walletId: number) {
  const { rows } = await db.query(
    `select w.id, w.holder_id as "holderId", u.name as "holderName", w.opening_balance::float8 as "openingBalance", w.active,
       ${BALANCE('w')}::float8 as balance,
       array(select company_code from airtel_wallet_companies where wallet_id = w.id order by company_code) as companies,
       (select count(*) from airtel_entries e where e.wallet_id = w.id and e.status = 'waiting')::int as waiting,
       coalesce((select json_agg(json_build_object('id', c.id, 'actual', c.actual::float8, 'ledger', c.ledger::float8,
           'difference', c.difference::float8, 'note', c.note, 'by', cu.name, 'at', c.checked_at) order by c.id desc)
         from airtel_checks c join users cu on cu.id = c.checked_by where c.wallet_id = w.id), '[]') as checks
     from airtel_wallets w join users u on u.id = w.holder_id where w.id = $3 and ${walletWhere(user)}`,
    [user.companies, user.id, walletId],
  );
  const w = rows[0];
  if (!w) return null;
  // Newest first, each with the wallet balance after it.
  const { rows: entries } = await db.query(
    `select e.id, e.company_code as "companyCode", e.kind, e.credit::float8 as credit, e.debit::float8 as debit, e.description, e.reference,
       e.status, e.ticked_at as "tickedAt", e.custody, c.number as "collectionNumber", x.number as "expenseNumber",
       u.name as "by", e.created_at as "createdAt",
       ($2::bigint + sum(e.credit - e.debit) over (order by e.id))::float8 as "balanceAfter"
     from airtel_entries e join users u on u.id = e.created_by
       left join collections c on c.id = e.collection_id left join expenses x on x.id = e.expense_id
     where e.wallet_id = $1 order by e.id desc limit 300`,
    [walletId, w.openingBalance],
  );
  const holder = w.holderId === user.id;
  return { ...w, entries, canAct: holder || user.role === 'admin' };
}

export function airtelRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const admins = requireRole('admin');
  const viewers = requireRole(...WALLET_VIEWERS);

  async function saveWallet(client: PoolClient, body: z.infer<typeof walletBody>, walletId: number | null) {
    const { rows: holder } = await client.query("select 1 from users where id = $1 and role in ('sales_manager', 'admin') and active", [body.holderId]);
    if (!holder[0]) throw invalid('The wallet holder must be a sales manager.');
    const { rows: known } = await client.query<{ code: string }>('select code from companies where code = any($1)', [body.companies]);
    if (known.length !== new Set(body.companies).size) throw invalid('That company is not known.');
    const { rows: taken } = await client.query<{ code: string }>(
      'select company_code as code from airtel_wallet_companies where company_code = any($1) and wallet_id is distinct from $2',
      [body.companies, walletId],
    );
    if (taken[0]) throw invalid(`${taken[0].code} already has an Airtel Money wallet.`);
    try {
      if (walletId === null) {
        const { rows } = await client.query<{ id: number }>(
          'insert into airtel_wallets (holder_id, opening_balance, active) values ($1, $2, $3) returning id',
          [body.holderId, body.openingBalance, body.active],
        );
        walletId = rows[0]!.id;
      } else {
        await client.query('update airtel_wallets set holder_id = $2, opening_balance = $3, active = $4 where id = $1', [
          walletId,
          body.holderId,
          body.openingBalance,
          body.active,
        ]);
        await client.query('delete from airtel_wallet_companies where wallet_id = $1', [walletId]);
      }
    } catch (err) {
      if ((err as { code?: string }).code === '23505') throw invalid('That person already holds a wallet.');
      throw err;
    }
    for (const code of new Set(body.companies)) {
      await client.query('insert into airtel_wallet_companies (wallet_id, company_code) values ($1, $2)', [walletId, code]);
    }
    return walletId;
  }

  app.post('/airtel/wallets', { onRequest: auth, preHandler: admins }, async (request, reply) => {
    const body = parse(walletBody, request.body);
    const walletId = await inTransaction(db, (client) => saveWallet(client, body, null));
    await audit(db, { userId: request.user.id, action: 'airtel_wallet_set_up', details: { walletId, ...body } });
    return reply.code(201).send(await walletDetail(db, request.user, walletId));
  });

  app.put('/airtel/wallets/:id', { onRequest: auth, preHandler: admins }, async (request) => {
    const { id: walletId } = parse(idParams, request.params);
    const body = parse(walletBody, request.body);
    await inTransaction(db, async (client) => {
      const { rows } = await client.query('select 1 from airtel_wallets where id = $1 for update', [walletId]);
      if (!rows[0]) throw notFound('wallet');
      await saveWallet(client, body, walletId);
    });
    await audit(db, { userId: request.user.id, action: 'airtel_wallet_changed', details: { walletId, ...body } });
    return walletDetail(db, request.user, walletId);
  });

  app.get('/airtel/wallets', { onRequest: auth, preHandler: viewers }, async (request) => {
    const user = request.user;
    const { rows } = await db.query(
      `select w.id, u.name as "holderName", w.holder_id as "holderId", w.active, ${BALANCE('w')}::float8 as balance,
         array(select company_code from airtel_wallet_companies where wallet_id = w.id order by company_code) as companies,
         (select count(*) from airtel_entries e where e.wallet_id = w.id and e.status = 'waiting')::int as waiting
       from airtel_wallets w join users u on u.id = w.holder_id where ${walletWhere(user)} order by w.id`,
      [user.companies, user.id],
    );
    return rows;
  });

  app.get('/airtel/wallets/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: walletId } = parse(idParams, request.params);
    const wallet = await walletDetail(db, request.user, walletId);
    if (!wallet) throw notFound('wallet');
    return wallet;
  });

  // The holder sees the payment on their phone and ticks it.
  app.post('/airtel/entries/:id/tick', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id: entryId } = parse(idParams, request.params);
    const user = request.user;
    const walletId = await inTransaction(db, async (client) => {
      const { rows } = await client.query<{ walletId: number; status: string }>(
        'select wallet_id as "walletId", status from airtel_entries where id = $1 for update',
        [entryId],
      );
      if (!rows[0]) throw notFound('payment');
      const wallet = await lockWallet(client, user, rows[0].walletId);
      assertHolder(user, wallet);
      if (rows[0].status !== 'waiting') throw new HttpError(409, 'already_ticked', 'This payment is already ticked.');
      await client.query(`update airtel_entries set status = 'ticked', ticked_at = now() where id = $1`, [entryId]);
      return wallet.id;
    });
    await audit(db, { userId: user.id, action: 'airtel_payment_ticked', details: { entryId, walletId } });
    return walletDetail(db, user, walletId);
  });

  // Money taken out of the wallet becomes cash in the holder's hand and goes to the cashier with their next handover.
  // Airtel's charge for it is an expense of the company.
  app.post('/airtel/wallets/:id/withdrawals', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id: walletId } = parse(idParams, request.params);
    const body = parse(withdrawBody, request.body);
    const user = request.user;
    await inTransaction(db, async (client) => {
      const wallet = await lockWallet(client, user, walletId);
      if (wallet.holderId !== user.id) throw new HttpError(403, 'not_allowed', 'Only the wallet holder can take money out.');
      if (!wallet.companies.includes(body.companyCode)) throw invalid('That company does not use this wallet.');
      if (body.amount + body.charge > wallet.balance) throw invalid(`The wallet only has ${money(wallet.balance)}.`);
      await client.query(
        `insert into airtel_entries (wallet_id, company_code, kind, debit, description, status, custody, created_by)
         values ($1, $2, 'withdrawal', $3, 'Taken out as cash', 'ok', 'with', $4)`,
        [wallet.id, body.companyCode, body.amount, user.id],
      );
      if (body.charge > 0) {
        const number = await nextNumber(client, body.companyCode, 'EXP');
        const { rows } = await client.query<{ id: number }>(
          `insert into expenses (number, company_code, amount, description, mode, spent_by, custody)
           values ($1, $2, $3, 'Airtel withdrawal charge', 'airtel', $4, 'na') returning id`,
          [number, body.companyCode, body.charge, user.id],
        );
        await client.query(
          `insert into airtel_entries (wallet_id, company_code, kind, debit, description, expense_id, status, created_by)
           values ($1, $2, 'charge', $3, 'Airtel withdrawal charge', $4, 'ok', $5)`,
          [wallet.id, body.companyCode, body.charge, rows[0]!.id, user.id],
        );
      }
    });
    await audit(db, { userId: user.id, action: 'airtel_withdrawal', details: { walletId, ...body } });
    return reply.code(201).send(await walletDetail(db, user, walletId));
  });

  // The holder types the real balance from their phone; any difference with the ledger is kept.
  app.post('/airtel/wallets/:id/checks', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id: walletId } = parse(idParams, request.params);
    const body = parse(checkBody, request.body);
    const user = request.user;
    const difference = await inTransaction(db, async (client) => {
      const wallet = await lockWallet(client, user, walletId);
      assertHolder(user, wallet);
      const diff = body.actual - wallet.balance;
      await client.query(
        'insert into airtel_checks (wallet_id, actual, ledger, difference, note, checked_by) values ($1, $2, $3, $4, $5, $6)',
        [wallet.id, body.actual, wallet.balance, diff, body.note, user.id],
      );
      return diff;
    });
    await audit(db, { userId: user.id, action: 'airtel_wallet_checked', details: { walletId, actual: body.actual, difference } });
    return reply.code(201).send(await walletDetail(db, user, walletId));
  });
}
