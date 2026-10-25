import { col, OFFICE, PRODUCT, SALES_TEAM, STOCK_TEAM } from './common.js';
import { DAY, LOCAL, type Report, type Role } from './engine.js';

// Pieces of each batch set aside for orders.
const RESERVED = (b: string) => `coalesce((select sum(a.pieces) from order_allocations a where a.batch_id = ${b}.id), 0)`;
const PRODUCT_FILTER = `p.company_code = any(f.companies) and (f.product is null or p.id = f.product)`;
const PRODUCT_HEAD = `p.code, ${PRODUCT('p')} as product, p.company_code as company, p.pieces_per_carton as ppc`;
const PRODUCT_COLUMNS = [col('code', 'Code'), col('product', 'Product'), col('company', 'Company')];
// Stock of each product: on the shelf (in the warehouse), set aside for orders, free to sell, and on vehicles.
const LEVELS = `from f cross join products p
  cross join lateral (
    select coalesce(sum(b.on_hand - b.in_transit), 0)::int as shelf, coalesce(sum(b.in_transit), 0)::int as transit,
      coalesce(sum(${RESERVED('b')}), 0)::int as reserved
    from stock_batches b where b.product_id = p.id) s`;
const MOVEMENT_KIND = `(case m.kind when 'received' then 'Received' when 'adjusted' then 'Adjusted' when 'counted' then 'Stock count'
  when 'delivered' then 'Delivered' else 'Returned' end)`;
const REASON = (r: string) =>
  `(case ${r} when 'damaged' then 'Damaged' when 'expired' then 'Expired' when 'lost' then 'Lost' when 'found' then 'Found'
    when 'other' then 'Other' else coalesce(${r}, '') end)`;

const stock: Report[] = [
  {
    key: 'stock-available',
    group: 'stock',
    title: 'Available stock',
    description: 'Stock of each product today: on the shelf, set aside for orders, free to sell and on vehicles.',
    roles: STOCK_TEAM,
    filters: ['company', 'product'],
    columns: [
      ...PRODUCT_COLUMNS,
      col('shelf', 'On the shelf', 'qty'),
      col('reserved', 'Set aside for orders', 'qty'),
      col('free', 'Free to sell', 'qty'),
      col('transit', 'On vehicles', 'qty'),
    ],
    sql: `select ${PRODUCT_HEAD}, s.shelf, s.reserved, s.shelf - s.reserved as free, s.transit ${LEVELS}
      where ${PRODUCT_FILTER} and (p.deleted_at is null or s.shelf + s.transit > 0) order by p.company_code, p.code`,
  },
  {
    key: 'stock-reserved',
    group: 'stock',
    title: 'Reserved stock',
    description: 'Stock set aside for each order still to deliver.',
    roles: STOCK_TEAM,
    filters: ['company', 'product', 'customer'],
    columns: [
      col('date', 'Booked', 'date'),
      col('order', 'Order'),
      col('customer', 'Customer'),
      col('wantedOn', 'Wanted on', 'date'),
      ...PRODUCT_COLUMNS.slice(1),
      col('batch', 'Batch'),
      col('expiry', 'Expires', 'date'),
      col('qty', 'Set aside', 'qty'),
    ],
    sql: `select ${DAY(LOCAL('o.created_at'))} as date, o.number as "order", c.name as customer, ${DAY('o.wanted_on')} as "wantedOn",
        ${PRODUCT_HEAD}, b.batch_no as batch, ${DAY('b.expiry_date')} as expiry, a.pieces as qty
      from f cross join order_allocations a join order_lines l on l.id = a.line_id join orders o on o.id = l.order_id
        join customers c on c.id = o.customer_id join products p on p.id = l.product_id join stock_batches b on b.id = a.batch_id
      where ${PRODUCT_FILTER} and (f.customer is null or o.customer_id = f.customer)
      order by o.wanted_on nulls last, o.id, p.code`,
  },
  {
    key: 'stock-on-vehicles',
    group: 'stock',
    title: 'Loaded stock in transit',
    description: 'Goods loaded on a vehicle and not yet delivered, sold or back on the shelf.',
    roles: STOCK_TEAM,
    filters: ['company', 'product'],
    columns: [
      col('since', 'Loaded', 'date'),
      col('document', 'Delivery note or trip'),
      col('vehicle', 'Vehicle'),
      col('driver', 'Driver'),
      ...PRODUCT_COLUMNS.slice(1),
      col('qty', 'Quantity', 'qty'),
    ],
    sql: `select * from (
        select ${DAY(LOCAL('lp.loaded_at'))} as since, coalesce(lp.delivery_note_no, lp.number) as document, coalesce(v.plate, '') as vehicle,
          coalesce(d.name, '') as driver, ${PRODUCT_HEAD}, sum(i.loaded)::int as qty, lp.loaded_at as at
        from f cross join loading_plans lp join loading_plan_items i on i.plan_id = lp.id join order_lines l on l.id = i.order_line_id
          join products p on p.id = l.product_id left join vehicles v on v.id = lp.vehicle_id left join users d on d.id = lp.driver_id
        where lp.status in ('loaded', 'out') and i.delivered is null and i.loaded > 0 and ${PRODUCT_FILTER}
        group by lp.id, v.plate, d.name, p.id
      union all
        select ${DAY(LOCAL('t.loaded_at'))}, t.number, coalesce(v.plate, ''), coalesce(d.name, ''), ${PRODUCT_HEAD}, tl.loaded - tl.sold, t.loaded_at
        from f cross join trips t join trip_lines tl on tl.trip_id = t.id join products p on p.id = tl.product_id
          left join vehicles v on v.id = t.vehicle_id left join users d on d.id = t.driver_id
        where t.status = 'out' and tl.loaded > tl.sold and ${PRODUCT_FILTER}) x
      order by at, document, code`,
  },
  {
    key: 'stock-returned',
    group: 'stock',
    title: 'Returned stock',
    description: 'Goods customers gave back that reached the warehouse, good or damaged.',
    roles: STOCK_TEAM,
    filters: ['dates', 'company', 'product', 'customer'],
    columns: [
      col('date', 'Received', 'date'),
      col('number', 'Return'),
      col('customer', 'Customer'),
      ...PRODUCT_COLUMNS.slice(1),
      col('qty', 'Quantity', 'qty'),
      col('condition', 'Condition'),
      col('reason', 'Why'),
    ],
    sql: `select ${DAY(LOCAL('r.received_at'))} as date, r.number, c.name as customer, ${PRODUCT_HEAD}, rl.pieces as qty,
        case rl.condition when 'damaged' then 'Damaged' else 'Good' end as condition, r.reason
      from f cross join sales_returns r join sales_return_lines rl on rl.return_id = r.id join customers c on c.id = r.customer_id
        join products p on p.id = rl.product_id
      where r.received_at is not null and ${LOCAL('r.received_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}
        and (f.customer is null or r.customer_id = f.customer)
      order by r.received_at, rl.id`,
  },
  {
    key: 'stock-damaged',
    group: 'stock',
    title: 'Damaged and held stock',
    description: 'Damaged goods given back, damaged stock written off or waiting for approval, and stock held for orders on hold.',
    roles: STOCK_TEAM,
    filters: ['dates', 'company', 'product'],
    columns: [
      col('date', 'Date', 'date'),
      col('kind', 'What'),
      col('document', 'Document'),
      ...PRODUCT_COLUMNS.slice(1),
      col('qty', 'Quantity', 'qty'),
      col('state', 'State'),
    ],
    sql: `select * from (
        select ${DAY(LOCAL('r.received_at'))} as date, 'Damaged return' as kind, r.number as document, ${PRODUCT_HEAD}, rl.pieces as qty,
          'Not on the shelf' as state, r.received_at as at
        from f cross join sales_return_lines rl join sales_returns r on r.id = rl.return_id join products p on p.id = rl.product_id
        where rl.condition = 'damaged' and ${LOCAL('r.received_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}
      union all
        select ${DAY(LOCAL('q.requested_at'))}, 'Damaged write-off', b.batch_no, ${PRODUCT_HEAD}, abs(q.change),
          case q.status when 'pending' then 'Waiting for approval' when 'approved' then 'Written off' else 'Not approved' end, q.requested_at
        from f cross join stock_adjustment_requests q join stock_batches b on b.id = q.batch_id join products p on p.id = b.product_id
        where q.reason = 'damaged' and ${LOCAL('q.requested_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}
      union all
        select ${DAY(LOCAL('o.updated_at'))}, 'Order on hold', o.number || ' · ' || c.name, ${PRODUCT_HEAD}, sum(a.pieces)::int,
          'Set aside', o.updated_at
        from f cross join orders o join customers c on c.id = o.customer_id join order_lines l on l.order_id = o.id
          join order_allocations a on a.line_id = l.id join products p on p.id = l.product_id
        where o.status = 'hold' and ${PRODUCT_FILTER}
        group by o.id, c.name, p.id) x
      order by at, document`,
  },
  {
    key: 'stock-movements',
    group: 'stock',
    title: 'Stock movements',
    description: 'Every change to the stock: received, delivered, returned, counted and adjusted.',
    roles: STOCK_TEAM,
    filters: ['dates', 'company', 'product'],
    columns: [
      col('date', 'Date', 'date'),
      ...PRODUCT_COLUMNS,
      col('batch', 'Batch'),
      col('kind', 'What'),
      col('qtyIn', 'In', 'qty'),
      col('qtyOut', 'Out', 'qty'),
      col('why', 'Why'),
      col('by', 'By'),
    ],
    sql: `select ${DAY(LOCAL('m.created_at'))} as date, ${PRODUCT_HEAD}, b.batch_no as batch, ${MOVEMENT_KIND} as kind,
        case when m.change > 0 then m.change end as "qtyIn", case when m.change < 0 then -m.change end as "qtyOut",
        trim(both ' ·' from ${REASON('m.reason')} || ' · ' || coalesce(m.note, '')) as why, coalesce(u.name, '') as by
      from f cross join stock_movements m join stock_batches b on b.id = m.batch_id join products p on p.id = b.product_id
        left join users u on u.id = m.user_id
      where ${LOCAL('m.created_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}
      order by m.id`,
  },
  {
    key: 'stock-low',
    group: 'stock',
    title: 'Low stock',
    description: 'Products whose stock free to sell is below their low-stock level.',
    roles: STOCK_TEAM,
    filters: ['company', 'product'],
    columns: [...PRODUCT_COLUMNS, col('free', 'Free to sell', 'qty'), col('level', 'Low-stock level', 'qty'), col('short', 'Short by', 'qty')],
    sql: `select ${PRODUCT_HEAD}, s.shelf - s.reserved as free, p.low_stock_cartons * p.pieces_per_carton as level,
        p.low_stock_cartons * p.pieces_per_carton - (s.shelf - s.reserved) as short ${LEVELS}
      where ${PRODUCT_FILTER} and p.deleted_at is null and p.active and p.low_stock_cartons is not null
        and s.shelf - s.reserved < p.low_stock_cartons * p.pieces_per_carton
      order by p.company_code, p.code`,
  },
];

// Trips of the warehouses of the companies picked. A salesman sees the trips he leads.
const TRIP_FROM = `from f cross join trips t left join vehicles v on v.id = t.vehicle_id join users ld on ld.id = t.leader_id`;
const TRIP_FILTER = `t.trip_date between f.d_from and f.d_to and (f.person is null or t.leader_id = f.person)
  and exists (select 1 from companies co where co.facility_code = t.facility_code and co.code = any(f.companies))`;
const TRIP_HEAD = `${DAY('t.trip_date')} as date, t.number as trip, case t.kind when 'minibus' then 'Minibus' else 'Upcountry' end as kind,
  t.route, coalesce(v.plate, '') as vehicle, ld.name as leader`;
const TRIP_COLUMNS = [
  col('date', 'Date', 'date'),
  col('trip', 'Trip'),
  col('kind', 'Kind'),
  col('route', 'Route'),
  col('vehicle', 'Vehicle'),
  col('leader', 'Leader'),
];
const TRIP_STOCK_TEAM: Role[] = ['admin', 'management', 'sales_manager', 'salesman', 'warehouse'];
const TRIP_MONEY_TEAM: Role[] = ['admin', 'management', 'sales_manager', 'salesman', 'cashier', 'accounts'];
const PAYMENT = `(case ts.payment when 'cash' then 'Cash' when 'airtel' then 'Airtel Money' else 'On account' end)`;

const trips: Report[] = [
  {
    key: 'trip-load',
    group: 'trips',
    title: 'Trip loads',
    description: 'What was planned and loaded on each minibus and upcountry trip.',
    roles: TRIP_STOCK_TEAM,
    filters: ['dates', 'company', 'person', 'product'],
    personLabel: 'Leader',
    columns: [...TRIP_COLUMNS, col('product', 'Product'), col('planned', 'Planned', 'qty'), col('loaded', 'Loaded', 'qty')],
    sql: `select ${TRIP_HEAD}, ${PRODUCT('p')} as product, p.pieces_per_carton as ppc, tl.planned, tl.loaded
      ${TRIP_FROM} join trip_lines tl on tl.trip_id = t.id join products p on p.id = tl.product_id
      where ${TRIP_FILTER} and ${PRODUCT_FILTER} order by t.trip_date, t.id, p.code`,
  },
  {
    key: 'trip-sales',
    group: 'trips',
    title: 'Trip sales',
    description: 'Each sale made at a stop, and how it was paid.',
    roles: TRIP_MONEY_TEAM,
    filters: ['dates', 'company', 'person', 'customer'],
    personLabel: 'Leader',
    columns: [
      col('soldOn', 'Sold', 'date'),
      col('trip', 'Trip'),
      col('route', 'Route'),
      col('stop', 'Stop'),
      col('customer', 'Customer'),
      col('payment', 'Paid by'),
      col('total', 'Total', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('ts.sold_at'))} as "soldOn", t.number as trip, t.route, ts.stop, c.name as customer, ${PAYMENT} as payment,
        coalesce((select sum(iv.total) from invoices iv where iv.trip_sale_id = ts.id and iv.company_code = any(f.companies)), 0)::float8 as total
      ${TRIP_FROM} join trip_sales ts on ts.trip_id = t.id join customers c on c.id = ts.customer_id
      where ${TRIP_FILTER} and (f.customer is null or ts.customer_id = f.customer) order by ts.sold_at`,
  },
  {
    key: 'trip-collections',
    group: 'trips',
    title: 'Trip collections',
    description: 'What each trip sold, by cash, Airtel Money and on account.',
    roles: TRIP_MONEY_TEAM,
    filters: ['dates', 'company', 'person'],
    personLabel: 'Leader',
    columns: [
      ...TRIP_COLUMNS,
      col('cash', 'Cash', 'money', true),
      col('airtel', 'Airtel Money', 'money', true),
      col('credit', 'On account', 'money', true),
      col('total', 'Total', 'money', true),
    ],
    sql: `select ${TRIP_HEAD}, s.cash, s.airtel, s.credit, s.cash + s.airtel + s.credit as total
      ${TRIP_FROM}
      cross join lateral (
        select coalesce(sum(iv.total) filter (where ts.payment = 'cash'), 0)::float8 as cash,
          coalesce(sum(iv.total) filter (where ts.payment = 'airtel'), 0)::float8 as airtel,
          coalesce(sum(iv.total) filter (where ts.payment = 'credit'), 0)::float8 as credit
        from trip_sales ts join invoices iv on iv.trip_sale_id = ts.id where ts.trip_id = t.id and iv.company_code = any(f.companies)) s
      where ${TRIP_FILTER} and t.status <> 'planned' order by t.trip_date, t.id`,
  },
  {
    key: 'trip-returns',
    group: 'trips',
    title: 'Trip returns',
    description: 'For each closed trip: what was loaded, sold, brought back and missing.',
    roles: TRIP_STOCK_TEAM,
    filters: ['dates', 'company', 'person', 'product'],
    personLabel: 'Leader',
    columns: [
      ...TRIP_COLUMNS,
      col('product', 'Product'),
      col('loaded', 'Loaded', 'qty'),
      col('sold', 'Sold', 'qty'),
      col('returned', 'Back', 'qty'),
      col('missing', 'Missing', 'qty'),
    ],
    sql: `select ${TRIP_HEAD}, ${PRODUCT('p')} as product, p.pieces_per_carton as ppc, tl.loaded, tl.sold, tl.returned, tl.missing
      ${TRIP_FROM} join trip_lines tl on tl.trip_id = t.id join products p on p.id = tl.product_id
      where t.status = 'closed' and tl.loaded > 0 and ${TRIP_FILTER} and ${PRODUCT_FILTER} order by t.trip_date, t.id, p.code`,
  },
  {
    key: 'trip-settlement',
    group: 'trips',
    title: 'Trip settlement',
    description: 'For each closed trip: the float, cash sales, cash spent, the cash expected and what was counted.',
    roles: TRIP_MONEY_TEAM,
    filters: ['dates', 'company', 'person'],
    personLabel: 'Leader',
    columns: [
      ...TRIP_COLUMNS,
      col('float', 'Float', 'money', true),
      col('cashSales', 'Cash sales', 'money', true),
      col('expenses', 'Cash spent', 'money', true),
      col('expected', 'Cash expected', 'money', true),
      col('counted', 'Counted', 'money', true),
      col('difference', 'Difference', 'money', true),
      col('missing', 'Missing stock lines', 'int', true),
      col('note', 'Note'),
    ],
    sql: `select ${TRIP_HEAD}, t.float_amount::float8 as float,
        coalesce((select sum(ts.total) from trip_sales ts where ts.trip_id = t.id and ts.payment = 'cash'), 0)::float8 as "cashSales",
        coalesce((select sum(e.amount) from expenses e where e.trip_id = t.id and e.mode = 'cash'), 0)::float8 as expenses,
        t.cash_expected::float8 as expected, t.cash_counted::float8 as counted, t.cash_difference::float8 as difference,
        (select count(*) from trip_lines tl where tl.trip_id = t.id and tl.missing > 0)::int as missing, coalesce(t.close_note, '') as note
      ${TRIP_FROM} where t.status = 'closed' and ${TRIP_FILTER} order by t.trip_date, t.id`,
  },
];

const control: Report[] = [
  {
    key: 'discounts',
    group: 'control',
    title: 'Discounts given',
    description: 'Every discount and withholding tax taken off an invoice when it was paid.',
    roles: OFFICE,
    filters: ['dates', 'company', 'person', 'customer'],
    personLabel: 'Given by',
    columns: [
      col('date', 'Date', 'date'),
      col('number', 'Receipt'),
      col('company', 'Company'),
      col('customer', 'Customer'),
      col('invoice', 'Invoice'),
      col('by', 'Given by'),
      col('invoiceTotal', 'Invoice total', 'money', true),
      col('discount', 'Discount', 'money', true),
      col('wht', 'Withholding tax', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('p.created_at'))} as date, p.number, p.company_code as company, c.name as customer, iv.number as invoice,
        u.name as by, iv.total::float8 as "invoiceTotal", p.discount::float8 as discount, p.wht::float8 as wht
      from f cross join collections p join customers c on c.id = p.customer_id join users u on u.id = p.collected_by
        join invoices iv on iv.id = p.invoice_id
      where (p.discount > 0 or p.wht > 0) and p.cancelled_at is null and p.company_code = any(f.companies)
        and ${LOCAL('p.created_at')} between f.d_from and f.d_to and (f.person is null or p.collected_by = f.person)
        and (f.customer is null or p.customer_id = f.customer)
      order by p.created_at`,
  },
  {
    key: 'returns',
    group: 'control',
    title: 'Sales returns',
    description: 'Every sales return: not taken at delivery or given back later, and whether it is booked.',
    roles: SALES_TEAM,
    filters: ['dates', 'company', 'person', 'customer'],
    personLabel: 'Salesman',
    columns: [
      col('date', 'Date', 'date'),
      col('number', 'Return'),
      col('company', 'Company'),
      col('customer', 'Customer'),
      col('invoice', 'Invoice'),
      col('kind', 'What'),
      col('reason', 'Why'),
      col('state', 'State'),
      col('total', 'Total', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('r.created_at'))} as date, r.number, r.company_code as company, c.name as customer, iv.number as invoice,
        case r.kind when 'delivery' then 'Not taken at delivery' else 'Given back' end as kind, r.reason,
        case when r.booked_at is null then 'Waiting to be booked' else 'Booked' end as state, r.total::float8 as total
      from f cross join sales_returns r join customers c on c.id = r.customer_id join invoices iv on iv.id = r.invoice_id
      where r.company_code = any(f.companies) and ${LOCAL('r.created_at')} between f.d_from and f.d_to
        and (f.person is null or iv.salesman_id = f.person) and (f.customer is null or r.customer_id = f.customer)
      order by r.created_at`,
  },
  {
    key: 'stock-adjustments',
    group: 'control',
    title: 'Stock adjustments',
    description: 'Stock taken off or added by hand, who asked and who approved.',
    roles: ['admin', 'management', 'warehouse', 'accounts'],
    filters: ['dates', 'company', 'product'],
    columns: [
      col('date', 'Date', 'date'),
      ...PRODUCT_COLUMNS,
      col('batch', 'Batch'),
      col('qtyIn', 'Added', 'qty'),
      col('qtyOut', 'Taken off', 'qty'),
      col('reason', 'Why'),
      col('note', 'Note'),
      col('state', 'State'),
      col('by', 'Asked by'),
      col('decidedBy', 'Decided by'),
    ],
    sql: `select * from (
        select ${DAY(LOCAL('q.requested_at'))} as date, ${PRODUCT_HEAD}, b.batch_no as batch,
          case when q.change > 0 then q.change end as "qtyIn", case when q.change < 0 then -q.change end as "qtyOut",
          ${REASON('q.reason')} as reason, coalesce(q.note, '') as note,
          case q.status when 'pending' then 'Waiting for approval' when 'approved' then 'Approved' else 'Not approved' end as state,
          u.name as by, coalesce(dd.name, '') as "decidedBy", q.requested_at as at
        from f cross join stock_adjustment_requests q join stock_batches b on b.id = q.batch_id join products p on p.id = b.product_id
          join users u on u.id = q.requested_by left join users dd on dd.id = q.decided_by
        where ${LOCAL('q.requested_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}
      union all
        select ${DAY(LOCAL('m.created_at'))}, ${PRODUCT_HEAD}, b.batch_no,
          case when m.change > 0 then m.change end, case when m.change < 0 then -m.change end,
          ${REASON('m.reason')}, coalesce(m.note, ''), case when m.trip_id is not null then 'Missing after a trip' else 'Done' end,
          coalesce(u.name, ''), '', m.created_at
        from f cross join stock_movements m join stock_batches b on b.id = m.batch_id join products p on p.id = b.product_id
          left join users u on u.id = m.user_id
        where m.kind = 'adjusted' and m.request_id is null and ${LOCAL('m.created_at')} between f.d_from and f.d_to and ${PRODUCT_FILTER}) x
      order by at`,
  },
  {
    key: 'audit-trail',
    group: 'control',
    title: 'User activity',
    description: 'Everything people did in the app: sign-ins, changes and money, with the time and who.',
    roles: ['admin', 'management'],
    filters: ['dates', 'person'],
    personLabel: 'Person',
    columns: [col('at', 'Time'), col('person', 'Person'), col('action', 'What'), col('about', 'About'), col('details', 'Details')],
    sql: `select to_char(a.at at time zone 'Africa/Blantyre', 'YYYY-MM-DD HH24:MI') as at, coalesce(u.name, '') as person,
        initcap(replace(a.action, '_', ' ')) as action, coalesce(t.name, '') as about, coalesce(a.details::text, '') as details
      from f cross join audit_log a left join users u on u.id = a.user_id left join users t on t.id = a.target_user_id
      where ${LOCAL('a.at')} between f.d_from and f.d_to and (f.person is null or a.user_id = f.person)
      order by a.id`,
  },
];

export const STOCK_REPORTS: Report[] = [...stock, ...trips, ...control];
