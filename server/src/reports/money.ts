import { CUSTOMER_OWES, INVOICE_BALANCE, OPENING_LEFT } from '../cash/balances.js';
import { invalid } from '../lists/common.js';
import { ledger } from '../returns/ledger.js';
import { TODAY } from '../stock/stock.js';
import { CHANNEL, col, MODE, MONEY_TEAM } from './common.js';
import { ALL_START, DAY, LOCAL, type FilterKey, type Report } from './engine.js';

const CUSTOMER_FILTERS: FilterKey[] = ['company', 'person', 'customer', 'channel'];
// Each customer with each company picked. A salesman sees the customers he has invoiced.
const CUSTOMER_FROM = `from f cross join customer_companies cc join customers c on c.id = cc.customer_id`;
const CUSTOMER_FILTER = `cc.company_code = any(f.companies) and (f.customer is null or c.id = f.customer)
  and (f.channel is null or c.channel = f.channel)
  and (f.person is null or exists (select 1 from invoices x where x.customer_id = c.id and x.company_code = cc.company_code and x.salesman_id = f.person))`;

// Payments of the companies and dates picked. Needs the aliases p (collections) and c (customers).
const PAYMENT_FROM = `from f cross join collections p join customers c on c.id = p.customer_id join users u on u.id = p.collected_by
  left join invoices iv on iv.id = p.invoice_id`;
const PAYMENT_FILTER = `p.company_code = any(f.companies) and p.cancelled_at is null and ${LOCAL('p.created_at')} between f.d_from and f.d_to
  and (f.person is null or p.collected_by = f.person) and (f.customer is null or p.customer_id = f.customer)
  and (f.channel is null or c.channel = f.channel)`;
const CUSTODY = `(case p.custody when 'with' then 'With ' || u.name when 'handed' then 'Handed to the cashier' when 'counted' then 'Counted'
  else '' end)`;

// Cash each person holds: payments, Airtel withdrawals and trip floats not yet handed over, less what they spent.
const HELD = `held as (
    select collected_by as who, company_code as co, amount as cash, 0 as withdrawals, 0 as floats, 0 as expenses from collections where custody = 'with'
  union all
    select created_by, company_code, 0, debit, 0, 0 from airtel_entries where custody = 'with'
  union all
    select leader_id, company_code, 0, 0, float_amount, 0 from trips where float_custody = 'with'
  union all
    select spent_by, company_code, 0, 0, 0, amount from expenses where custody = 'with')`;

// Money in by way, for the cash summaries. Needs the alias p (collections).
const BY_MODE = `coalesce(sum(p.amount) filter (where p.mode = 'cash'), 0)::float8 as cash,
  coalesce(sum(p.amount) filter (where p.mode = 'bank'), 0)::float8 as bank,
  coalesce(sum(p.amount) filter (where p.mode = 'airtel'), 0)::float8 as airtel,
  coalesce(sum(p.discount), 0)::float8 as discount, coalesce(sum(p.wht), 0)::float8 as wht, coalesce(sum(p.amount), 0)::float8 as paid`;
const BY_MODE_COLUMNS = [
  col('cash', 'Cash', 'money', true),
  col('bank', 'Bank transfer', 'money', true),
  col('airtel', 'Airtel Money', 'money', true),
  col('paid', 'Paid in all', 'money', true),
  col('discount', 'Discount', 'money', true),
  col('wht', 'Withholding tax', 'money', true),
];
// Cash expenses of the companies and dates picked, alias e.
const EXPENSE_FILTER = `e.company_code = any(f.companies) and e.mode = 'cash' and ${LOCAL('e.created_at')} between f.d_from and f.d_to
  and (f.person is null or e.spent_by = f.person)`;
// Handovers that hold money of the companies picked, alias h.
const HANDOVER_FILTER = `(exists (select 1 from collections x where x.handover_id = h.id and x.company_code = any(f.companies))
  or exists (select 1 from expenses x where x.handover_id = h.id and x.company_code = any(f.companies))
  or exists (select 1 from airtel_entries x where x.handover_id = h.id and x.company_code = any(f.companies))
  or exists (select 1 from trips x where x.float_handover_id = h.id and x.company_code = any(f.companies)))`;

const receivables: Report[] = [
  {
    key: 'customer-ledger',
    group: 'receivables',
    title: 'Customer ledger',
    description: 'Every invoice, payment, return and note of one customer with one company, with the balance after each.',
    roles: MONEY_TEAM,
    filters: ['dates', 'company', 'customer'],
    required: ['company', 'customer'],
    defaultFrom: 'all',
    columns: [
      col('date', 'Date', 'date'),
      col('document', 'Document'),
      col('what', 'Details'),
      col('charged', 'Charged', 'money', true),
      col('paid', 'Paid or less', 'money', true),
      col('balance', 'Balance', 'money'),
    ],
    run: async (db, f, user) => {
      if (f.companies.length !== 1 || f.customer == null) throw invalid('Please pick the company and the customer.');
      const l = await ledger(db, user, f.customer, { company: f.companies[0]!, from: f.from, to: f.to });
      return [
        { date: f.from > ALL_START ? f.from : null, document: '', what: 'Balance brought forward', charged: 0, paid: 0, balance: l.opening },
        ...l.rows.map((r) => ({ date: r.date, document: r.document, what: r.what, charged: r.charged, paid: r.paid, balance: r.balance })),
      ];
    },
  },
  {
    key: 'receivables-outstanding',
    group: 'receivables',
    title: 'Outstanding by customer',
    description: 'What each customer owes each company today, and how much of it is late.',
    roles: MONEY_TEAM,
    filters: CUSTOMER_FILTERS,
    columns: [
      col('customer', 'Customer'),
      col('company', 'Company'),
      col('channel', 'Channel'),
      col('owes', 'Owes', 'money', true),
      col('overdue', 'Of which late', 'money', true),
      col('creditLimit', 'Credit limit', 'money'),
    ],
    sql: `select * from (
        select c.name as customer, cc.company_code as company, ${CHANNEL('c')} as channel, ${CUSTOMER_OWES('c.id', 'cc.company_code')}::float8 as owes,
          coalesce((select sum(greatest(${INVOICE_BALANCE('iv')}, 0)) from invoices iv
            where iv.customer_id = c.id and iv.company_code = cc.company_code and iv.due_date < ${TODAY}), 0)::float8 as overdue,
          cc.credit_limit::float8 as "creditLimit"
        ${CUSTOMER_FROM} where ${CUSTOMER_FILTER}) x
      where owes <> 0 order by owes desc, customer`,
  },
  {
    key: 'receivables-aging',
    group: 'receivables',
    title: 'Aging',
    description: 'What each customer owes, by how many days late it is.',
    roles: MONEY_TEAM,
    filters: CUSTOMER_FILTERS,
    columns: [
      col('customer', 'Customer'),
      col('company', 'Company'),
      col('notDue', 'Not due yet', 'money', true),
      col('late30', '1 to 30 days late', 'money', true),
      col('late60', '31 to 60 days', 'money', true),
      col('late90', '61 to 90 days', 'money', true),
      col('lateMore', 'Over 90 days', 'money', true),
      col('beforeApp', 'Owed before the app', 'money', true),
      col('owes', 'Owes in all', 'money', true),
    ],
    sql: `select * from (
        select c.name as customer, cc.company_code as company,
          coalesce(sum(b.amt) filter (where b.late <= 0), 0)::float8 as "notDue",
          coalesce(sum(b.amt) filter (where b.late between 1 and 30), 0)::float8 as late30,
          coalesce(sum(b.amt) filter (where b.late between 31 and 60), 0)::float8 as late60,
          coalesce(sum(b.amt) filter (where b.late between 61 and 90), 0)::float8 as late90,
          coalesce(sum(b.amt) filter (where b.late > 90), 0)::float8 as "lateMore",
          ${OPENING_LEFT('c.id', 'cc.company_code')}::float8 as "beforeApp",
          ${CUSTOMER_OWES('c.id', 'cc.company_code')}::float8 as owes
        ${CUSTOMER_FROM}
        left join lateral (
          select ${INVOICE_BALANCE('iv')} as amt, ${TODAY} - iv.due_date as late from invoices iv
          where iv.customer_id = c.id and iv.company_code = cc.company_code) b on b.amt > 0
        where ${CUSTOMER_FILTER}
        group by c.id, c.name, cc.company_code) x
      where owes <> 0 or "notDue" + late30 + late60 + late90 + "lateMore" > 0
      order by owes desc, customer`,
  },
  {
    key: 'credit-limits',
    group: 'receivables',
    title: 'Credit limit use',
    description: 'Customers with a credit limit: what they owe against it. A limit never stops an order.',
    roles: MONEY_TEAM,
    filters: CUSTOMER_FILTERS,
    columns: [
      col('customer', 'Customer'),
      col('company', 'Company'),
      col('creditLimit', 'Credit limit', 'money', true),
      col('owes', 'Owes', 'money', true),
      col('percent', 'Used %', 'percent'),
      col('overBy', 'Over the limit by', 'money', true),
    ],
    sql: `select customer, company, "creditLimit", owes,
        case when "creditLimit" > 0 then round(owes::numeric * 100 / "creditLimit"::numeric)::int end as percent, greatest(owes - "creditLimit", 0) as "overBy"
      from (
        select c.name as customer, cc.company_code as company, cc.credit_limit::float8 as "creditLimit",
          ${CUSTOMER_OWES('c.id', 'cc.company_code')}::float8 as owes
        ${CUSTOMER_FROM} where cc.credit_limit is not null and ${CUSTOMER_FILTER}) x
      order by case when "creditLimit" > 0 then owes / "creditLimit" end desc nulls first, customer`,
  },
  {
    key: 'collections',
    group: 'receivables',
    title: 'Collections',
    description: 'Every payment received, by cash, bank transfer or Airtel Money. Cancelled payments are left out.',
    roles: MONEY_TEAM,
    filters: ['dates', 'company', 'person', 'customer', 'channel'],
    personLabel: 'Collected by',
    columns: [
      col('date', 'Date', 'date'),
      col('number', 'Receipt'),
      col('company', 'Company'),
      col('customer', 'Customer'),
      col('invoice', 'Invoice'),
      col('mode', 'Paid by'),
      col('reference', 'Reference'),
      col('collectedBy', 'Collected by'),
      col('amount', 'Paid', 'money', true),
      col('discount', 'Discount', 'money', true),
      col('wht', 'Withholding tax', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('p.created_at'))} as date, p.number, p.company_code as company, c.name as customer, coalesce(iv.number, 'On account') as invoice,
        ${MODE('p')} as mode, p.reference, u.name as "collectedBy", p.amount::float8 as amount, p.discount::float8 as discount, p.wht::float8 as wht
      ${PAYMENT_FROM} where ${PAYMENT_FILTER} order by p.created_at`,
  },
];

const cash: Report[] = [
  {
    key: 'cash-collections',
    group: 'cash',
    title: 'Cash collections',
    description: 'Cash payments received, and whether the cash is still with the collector, handed over or counted.',
    roles: MONEY_TEAM,
    filters: ['dates', 'company', 'person', 'customer'],
    personLabel: 'Collected by',
    columns: [
      col('date', 'Date', 'date'),
      col('number', 'Receipt'),
      col('company', 'Company'),
      col('customer', 'Customer'),
      col('collectedBy', 'Collected by'),
      col('custody', 'Where the cash is'),
      col('amount', 'Cash', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('p.created_at'))} as date, p.number, p.company_code as company, c.name as customer, u.name as "collectedBy",
        ${CUSTODY} as custody, p.amount::float8 as amount
      ${PAYMENT_FROM} where p.mode = 'cash' and p.amount > 0 and ${PAYMENT_FILTER} order by p.created_at`,
  },
  {
    key: 'cash-in-hand',
    group: 'cash',
    title: 'Cash in hand',
    description: 'Cash each person holds today and has not handed to the cashier yet.',
    roles: ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'],
    filters: ['company', 'person'],
    columns: [
      col('person', 'Person'),
      col('company', 'Company'),
      col('cash', 'Payments', 'money', true),
      col('withdrawals', 'From Airtel Money', 'money', true),
      col('floats', 'Trip floats', 'money', true),
      col('expenses', 'Spent', 'money', true),
      col('net', 'To hand over', 'money', true),
    ],
    sql: `with ${HELD}
      select u.name as person, h.co as company, sum(h.cash)::float8 as cash, sum(h.withdrawals)::float8 as withdrawals,
        sum(h.floats)::float8 as floats, sum(h.expenses)::float8 as expenses,
        sum(h.cash + h.withdrawals + h.floats - h.expenses)::float8 as net
      from f cross join held h join users u on u.id = h.who
      where h.co = any(f.companies) and (f.person is null or h.who = f.person)
      group by u.id, u.name, h.co order by u.name, h.co`,
  },
  {
    key: 'cash-handovers',
    group: 'cash',
    title: 'Cash handovers',
    description: 'Cash handed to the cashier, what was counted and any difference. A handover can hold money of several companies.',
    roles: ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'],
    filters: ['dates', 'company', 'person'],
    personLabel: 'Handed by',
    columns: [
      col('date', 'Date', 'date'),
      col('number', 'Handover'),
      col('handedBy', 'Handed by'),
      col('cash', 'Payments', 'money', true),
      col('withdrawals', 'From Airtel Money', 'money', true),
      col('floats', 'Trip floats', 'money', true),
      col('expenses', 'Spent', 'money', true),
      col('net', 'Handed over', 'money', true),
      col('status', 'Status'),
      col('counted', 'Counted', 'money', true),
      col('difference', 'Difference', 'money', true),
      col('countedBy', 'Counted by'),
    ],
    sql: `select ${DAY(LOCAL('h.created_at'))} as date, h.number, hb.name as "handedBy", h.cash::float8 as cash, h.withdrawals::float8 as withdrawals,
        h.floats::float8 as floats, h.expenses::float8 as expenses, h.net::float8 as net,
        case h.status when 'counted' then 'Counted' else 'Waiting for the cashier' end as status,
        h.counted::float8 as counted, h.difference::float8 as difference, coalesce(cb.name, '') as "countedBy"
      from f cross join handovers h join users hb on hb.id = h.handed_by left join users cb on cb.id = h.counted_by
      where ${LOCAL('h.created_at')} between f.d_from and f.d_to and (f.person is null or h.handed_by = f.person) and ${HANDOVER_FILTER}
      order by h.created_at`,
  },
  {
    key: 'cash-daily',
    group: 'cash',
    title: 'Daily cash summary',
    description: 'For each day: money received by each way, cash spent, and cash handed over and counted.',
    roles: ['admin', 'management', 'sales_manager', 'cashier', 'accounts'],
    filters: ['dates', 'company'],
    columns: [
      col('day', 'Day', 'date'),
      ...BY_MODE_COLUMNS.filter((c) => c.key !== 'discount' && c.key !== 'wht'),
      col('expenses', 'Cash spent', 'money', true),
      col('handed', 'Handed over', 'money', true),
      col('counted', 'Counted', 'money', true),
      col('difference', 'Difference', 'money', true),
    ],
    sql: `with days as (select generate_series(f.d_from, f.d_to, interval '1 day')::date as d from f)
      select * from (
        select ${DAY('days.d')} as day, m.cash, m.bank, m.airtel, m.paid,
          coalesce((select sum(e.amount) from expenses e where ${EXPENSE_FILTER.replace(/between f\.d_from and f\.d_to/, '= days.d')}), 0)::float8
            as expenses,
          coalesce((select sum(h.net) from handovers h where ${LOCAL('h.created_at')} = days.d and ${HANDOVER_FILTER}), 0)::float8 as handed,
          coalesce((select sum(h.counted) from handovers h where ${LOCAL('h.counted_at')} = days.d and ${HANDOVER_FILTER}), 0)::float8 as counted,
          coalesce((select sum(h.difference) from handovers h where ${LOCAL('h.counted_at')} = days.d and ${HANDOVER_FILTER}), 0)::float8
            as difference
        from f cross join days
        cross join lateral (
          select ${BY_MODE} from collections p
          where p.company_code = any(f.companies) and p.cancelled_at is null and ${LOCAL('p.created_at')} = days.d) m) x
      where paid > 0 or expenses > 0 or handed > 0 or counted > 0
      order by day`,
  },
  {
    key: 'cash-by-person',
    group: 'cash',
    title: 'Collections by salesman',
    description: 'Money each person received, by each way, and the cash they spent.',
    roles: MONEY_TEAM,
    filters: ['dates', 'company', 'person'],
    columns: [col('person', 'Person'), ...BY_MODE_COLUMNS, col('expenses', 'Cash spent', 'money', true)],
    sql: `select * from (
        select u.name as person, ${BY_MODE},
          coalesce((select sum(e.amount) from expenses e where e.spent_by = u.id and ${EXPENSE_FILTER}), 0)::float8 as expenses
        from f cross join users u
        left join collections p on p.collected_by = u.id and p.company_code = any(f.companies) and p.cancelled_at is null
          and ${LOCAL('p.created_at')} between f.d_from and f.d_to
        where (f.person is null or u.id = f.person)
        group by u.id, u.name, f.companies, f.d_from, f.d_to, f.person) x
      where paid + discount + wht > 0 or expenses > 0 order by paid desc, person`,
  },
  {
    key: 'cash-by-company',
    group: 'cash',
    title: 'Collections by company',
    description: 'Money each company received, by each way, and the cash spent for it.',
    roles: ['admin', 'management', 'sales_manager', 'cashier', 'accounts'],
    filters: ['dates', 'company'],
    columns: [col('company', 'Company'), ...BY_MODE_COLUMNS, col('expenses', 'Cash spent', 'money', true)],
    sql: `select co.name as company, ${BY_MODE},
        coalesce((select sum(e.amount) from expenses e where e.company_code = co.code and ${EXPENSE_FILTER}), 0)::float8 as expenses
      from f cross join companies co
      left join collections p on p.company_code = co.code and p.cancelled_at is null and ${LOCAL('p.created_at')} between f.d_from and f.d_to
      where co.code = any(f.companies)
      group by co.code, co.name, co.sort_order, f.companies, f.d_from, f.d_to, f.person order by co.sort_order`,
  },
];

export const MONEY_REPORTS: Report[] = [...receivables, ...cash];
