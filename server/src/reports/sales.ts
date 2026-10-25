import { INVOICE_BALANCE } from '../cash/balances.js';
import { PLANNED_SQL } from '../orders/orders.js';
import { TODAY } from '../stock/stock.js';
import { CHANNEL, col, INVOICE_COLUMNS, INVOICE_FILTER, INVOICE_SUMS, INVOICE_TYPE, PRODUCT, SALES_TEAM } from './common.js';
import { DAY, LOCAL, type FilterKey, type Report } from './engine.js';

const SALES_FILTERS: FilterKey[] = ['dates', 'company', 'person', 'customer', 'channel'];
const INVOICES_FROM = `from f cross join invoices iv join customers c on c.id = iv.customer_id`;

// Sales of each product, before VAT, for the top and low lists. Products with no sales count as 0.
const productSales = (order: 'asc' | 'desc', onlySold: boolean, limit: string) => `
  select * from (
    select p.code, ${PRODUCT('p')} as product, p.company_code as company, coalesce(s.pieces, 0) as qty, p.pieces_per_carton as ppc,
      coalesce(s.amount, 0)::float8 as amount
    from f cross join products p
    left join lateral (
      select sum(il.pieces)::int as pieces, sum(il.amount) as amount
      from invoice_lines il join invoices iv on iv.id = il.invoice_id join customers c on c.id = iv.customer_id
      where il.product_id = p.id and ${INVOICE_FILTER}) s on true
    where p.company_code = any(f.companies) and (f.product is null or p.id = f.product)
      and ((p.deleted_at is null and p.active) or s.pieces > 0)) x
  ${onlySold ? 'where qty > 0' : ''}
  order by amount ${order}, code ${limit}`;

const customerSales = (order: 'asc' | 'desc', onlySold: boolean, limit: string) => `
  select * from (
    select c.name as customer, ${CHANNEL('c')} as channel, coalesce(s.invoices, 0) as invoices,
      coalesce(s.subtotal, 0)::float8 as subtotal, coalesce(s.total, 0)::float8 as total
    from f cross join customers c
    left join lateral (
      select count(*)::int as invoices, sum(iv.subtotal) as subtotal, sum(iv.total) as total
      from invoices iv where iv.customer_id = c.id and ${INVOICE_FILTER}) s on true
    where exists (select 1 from customer_companies cc where cc.customer_id = c.id and cc.company_code = any(f.companies))
      and (f.customer is null or c.id = f.customer) and (f.channel is null or c.channel = f.channel)
      and ((c.deleted_at is null and c.active) or s.invoices > 0)) x
  ${onlySold ? 'where invoices > 0' : ''}
  order by subtotal ${order}, customer ${limit}`;

const PRODUCT_COLUMNS = [
  col('code', 'Code'),
  col('product', 'Product'),
  col('company', 'Company'),
  col('qty', 'Sold', 'qty'),
  col('amount', 'Sales before VAT', 'money', true),
];
const CUSTOMER_COLUMNS = [
  col('customer', 'Customer'),
  col('channel', 'Channel'),
  col('invoices', 'Invoices', 'int', true),
  col('subtotal', 'Before VAT', 'money', true),
  col('total', 'Total', 'money', true),
];

const sales: Report[] = [
  {
    key: 'sales-daily',
    group: 'sales',
    title: 'Daily sales',
    description: 'Invoiced sales for each day.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [col('day', 'Day', 'date'), ...INVOICE_COLUMNS],
    sql: `select ${DAY('iv.invoice_date')} as day, ${INVOICE_SUMS} ${INVOICES_FROM}
      where ${INVOICE_FILTER} group by iv.invoice_date order by iv.invoice_date`,
  },
  {
    key: 'sales-monthly',
    group: 'sales',
    title: 'Monthly sales',
    description: 'Invoiced sales for each month.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    defaultFrom: 'year',
    columns: [col('month', 'Month'), ...INVOICE_COLUMNS],
    sql: `select to_char(date_trunc('month', iv.invoice_date), 'Mon YYYY') as month, ${INVOICE_SUMS} ${INVOICES_FROM}
      where ${INVOICE_FILTER} group by date_trunc('month', iv.invoice_date) order by date_trunc('month', iv.invoice_date)`,
  },
  {
    key: 'sales-company',
    group: 'sales',
    title: 'Sales by company',
    description: 'Invoiced sales of each company.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [col('company', 'Company'), ...INVOICE_COLUMNS],
    sql: `select co.name as company, ${INVOICE_SUMS} ${INVOICES_FROM} join companies co on co.code = iv.company_code
      where ${INVOICE_FILTER} group by co.code, co.name, co.sort_order order by co.sort_order`,
  },
  {
    key: 'sales-salesman',
    group: 'sales',
    title: 'Sales by salesman',
    description: 'Invoiced sales of each salesman, and how many customers they sold to.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    personLabel: 'Salesman',
    columns: [col('salesman', 'Salesman'), col('customers', 'Customers', 'int'), ...INVOICE_COLUMNS],
    sql: `select coalesce(u.name, 'No salesman') as salesman, count(distinct iv.customer_id)::int as customers, ${INVOICE_SUMS}
      ${INVOICES_FROM} left join users u on u.id = iv.salesman_id
      where ${INVOICE_FILTER} group by u.id, u.name order by sum(iv.subtotal) desc`,
  },
  {
    key: 'sales-customer',
    group: 'sales',
    title: 'Sales by customer',
    description: 'Invoiced sales to each customer.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: CUSTOMER_COLUMNS,
    sql: customerSales('desc', true, ''),
  },
  {
    key: 'sales-product',
    group: 'sales',
    title: 'Sales by product',
    description: 'What was sold of each product (SKU), before VAT.',
    roles: SALES_TEAM,
    filters: [...SALES_FILTERS, 'product'],
    columns: PRODUCT_COLUMNS,
    sql: productSales('desc', true, ''),
  },
  {
    key: 'sales-channel',
    group: 'sales',
    title: 'Sales by channel',
    description: 'Invoiced sales to supermarkets, wholesalers, minibus and upcountry customers.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [col('channel', 'Channel'), col('customers', 'Customers', 'int'), ...INVOICE_COLUMNS],
    sql: `select ${CHANNEL('c')} as channel, count(distinct iv.customer_id)::int as customers, ${INVOICE_SUMS} ${INVOICES_FROM}
      where ${INVOICE_FILTER} group by c.channel order by sum(iv.subtotal) desc`,
  },
  {
    key: 'sales-route',
    group: 'sales',
    title: 'Sales by route',
    description: 'Invoiced sales of each trip route, or the customer’s area for other sales.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [col('route', 'Route or area'), col('customers', 'Customers', 'int'), ...INVOICE_COLUMNS],
    sql: `select coalesce(nullif(tr.route, ''), nullif(c.area, ''), 'No area') as route, count(distinct iv.customer_id)::int as customers,
        ${INVOICE_SUMS}
      ${INVOICES_FROM} left join trip_sales ts on ts.id = iv.trip_sale_id left join trips tr on tr.id = ts.trip_id
      where ${INVOICE_FILTER} group by 1 order by sum(iv.subtotal) desc`,
  },
  {
    key: 'target-achievement',
    group: 'sales',
    title: 'Target and achievement',
    description: 'Each salesman’s monthly target against his sales before VAT.',
    roles: SALES_TEAM,
    filters: ['dates', 'company', 'person'],
    personLabel: 'Salesman',
    defaultFrom: 'month',
    columns: [
      col('month', 'Month'),
      col('salesman', 'Salesman'),
      col('target', 'Target', 'money', true),
      col('achieved', 'Sales before VAT', 'money', true),
      col('percent', 'Achieved %', 'percent'),
      col('short', 'Still to sell', 'money', true),
    ],
    sql: `with months as (select generate_series(date_trunc('month', f.d_from), date_trunc('month', f.d_to), interval '1 month')::date as m from f),
      people as (
        select u.id, u.name from users u where u.role = 'salesman' and (u.deleted_at is null or exists (select 1 from invoices x where x.salesman_id = u.id))
          and exists (select 1 from user_companies uc, f where uc.user_id = u.id and uc.company_code = any(f.companies)))
      select to_char(months.m, 'Mon YYYY') as month, people.name as salesman, coalesce(t.amount, 0)::float8 as target, s.achieved,
        case when t.amount > 0 then round(s.achieved::numeric * 100 / t.amount)::int end as percent,
        greatest(coalesce(t.amount, 0) - s.achieved, 0)::float8 as short
      from f cross join months cross join people
      left join sales_targets t on t.salesman_id = people.id and t.month = months.m
      cross join lateral (
        select coalesce(sum(iv.subtotal), 0)::float8 as achieved from invoices iv
        where iv.salesman_id = people.id and iv.company_code = any(f.companies)
          and iv.invoice_date between greatest(months.m, f.d_from) and least((months.m + interval '1 month - 1 day')::date, f.d_to)) s
      where (f.person is null or people.id = f.person) and (t.amount is not null or s.achieved > 0)
      order by months.m, people.name`,
  },
  {
    key: 'top-products',
    group: 'sales',
    title: 'Top products',
    description: 'The 20 products that sold the most, before VAT.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: PRODUCT_COLUMNS,
    sql: productSales('desc', true, 'limit 20'),
  },
  {
    key: 'low-products',
    group: 'sales',
    title: 'Low products',
    description: 'The 20 products that sold the least, including those that did not sell.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: PRODUCT_COLUMNS,
    sql: productSales('asc', false, 'limit 20'),
  },
  {
    key: 'top-customers',
    group: 'sales',
    title: 'Top customers',
    description: 'The 20 customers who bought the most, before VAT.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: CUSTOMER_COLUMNS,
    sql: customerSales('desc', true, 'limit 20'),
  },
  {
    key: 'low-customers',
    group: 'sales',
    title: 'Low customers',
    description: 'The 20 customers who bought the least, including those who did not buy.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: CUSTOMER_COLUMNS,
    sql: customerSales('asc', false, 'limit 20'),
  },
];

// Orders and their value before VAT, counting only lines of the companies picked.
const ORDER_FILTER = `(f.person is null or o.salesman_id = f.person) and (f.customer is null or o.customer_id = f.customer)
  and (f.channel is null or c.channel = f.channel)`;
const ORDER_FROM = `from f cross join orders o join customers c on c.id = o.customer_id left join users u on u.id = o.salesman_id
  join lateral (
    select sum(round(l.price_per_carton::numeric * l.pieces / p.pieces_per_carton))::float8 as value,
      sum(round(l.price_per_carton::numeric * l.delivered / p.pieces_per_carton))::float8 as delivered_value,
      sum(round(l.price_per_carton::numeric * (l.pieces - l.delivered) / p.pieces_per_carton))::float8 as left_value,
      sum(l.delivered)::int as delivered, sum(l.on_vehicle)::int as on_vehicle, sum(${PLANNED_SQL('l.id')})::int as planned
    from order_lines l join products p on p.id = l.product_id where l.order_id = o.id and p.company_code = any(f.companies)) v
  on v.value is not null`;
const STAGE = `(case o.status when 'hold' then 'On hold' when 'done' then 'Done' when 'cancelled' then 'Cancelled'
  else case when v.on_vehicle > 0 then 'On a vehicle' when v.planned > 0 then 'Planned' when v.delivered > 0 then 'Part delivered' else 'Open' end end)`;
const ORDER_HEAD = `${DAY(LOCAL('o.created_at'))} as date, o.number, c.name as customer, coalesce(u.name, '') as salesman`;
const ORDER_HEAD_COLUMNS = [col('date', 'Booked', 'date'), col('number', 'Order'), col('customer', 'Customer'), col('salesman', 'Salesman')];
const OPEN_COLUMNS = [
  ...ORDER_HEAD_COLUMNS,
  col('wantedOn', 'Wanted on', 'date'),
  col('stage', 'Stage'),
  col('value', 'Value', 'money', true),
  col('delivered', 'Delivered', 'money', true),
  col('toDeliver', 'Still to deliver', 'money', true),
];

const orders: Report[] = [
  {
    key: 'order-register',
    group: 'orders',
    title: 'Order register',
    description: 'Every order booked, with where it stands. Values before VAT.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [...ORDER_HEAD_COLUMNS, col('stage', 'Stage'), col('value', 'Value', 'money', true), col('delivered', 'Delivered', 'money', true)],
    sql: `select ${ORDER_HEAD}, ${STAGE} as stage, v.value, v.delivered_value as delivered ${ORDER_FROM}
      where ${LOCAL('o.created_at')} between f.d_from and f.d_to and ${ORDER_FILTER} order by o.id`,
  },
  {
    key: 'orders-pending',
    group: 'orders',
    title: 'Pending orders',
    description: 'Open and held orders with something still to deliver, whenever they were booked.',
    roles: SALES_TEAM,
    filters: ['company', 'person', 'customer', 'channel'],
    columns: OPEN_COLUMNS,
    sql: `select ${ORDER_HEAD}, ${DAY('o.wanted_on')} as "wantedOn", ${STAGE} as stage, v.value, v.delivered_value as delivered,
        v.left_value as "toDeliver" ${ORDER_FROM}
      where o.status in ('open', 'hold') and v.left_value > 0 and ${ORDER_FILTER} order by o.wanted_on nulls last, o.id`,
  },
  {
    key: 'orders-partial',
    group: 'orders',
    title: 'Part delivered orders',
    description: 'Open orders that are partly delivered, with what is still to deliver.',
    roles: SALES_TEAM,
    filters: ['company', 'person', 'customer', 'channel'],
    columns: OPEN_COLUMNS,
    sql: `select ${ORDER_HEAD}, ${DAY('o.wanted_on')} as "wantedOn", ${STAGE} as stage, v.value, v.delivered_value as delivered,
        v.left_value as "toDeliver" ${ORDER_FROM}
      where o.status in ('open', 'hold') and v.delivered > 0 and v.left_value > 0 and ${ORDER_FILTER} order by o.id`,
  },
  {
    key: 'orders-cancelled',
    group: 'orders',
    title: 'Cancelled orders',
    description: 'Orders cancelled in the dates picked, and who cancelled them.',
    roles: SALES_TEAM,
    filters: SALES_FILTERS,
    columns: [...ORDER_HEAD_COLUMNS, col('cancelledOn', 'Cancelled', 'date'), col('cancelledBy', 'Cancelled by'), col('value', 'Value', 'money', true)],
    sql: `select ${ORDER_HEAD}, ${DAY(LOCAL('e.created_at'))} as "cancelledOn", coalesce(eu.name, '') as "cancelledBy", v.value ${ORDER_FROM}
      join lateral (select x.created_at, x.user_id from order_events x where x.order_id = o.id and x.title = 'Cancelled'
        order by x.id desc limit 1) e on true
      left join users eu on eu.id = e.user_id
      where o.status = 'cancelled' and ${LOCAL('e.created_at')} between f.d_from and f.d_to and ${ORDER_FILTER} order by e.created_at`,
  },
];

// Loading plan items of the companies picked. Needs the aliases lp, i (items), l (order lines), o, c and p.
const ITEM_FROM = `from f cross join loading_plans lp join loading_plan_items i on i.plan_id = lp.id join order_lines l on l.id = i.order_line_id
  join orders o on o.id = l.order_id join customers c on c.id = o.customer_id join products p on p.id = l.product_id
  left join vehicles v on v.id = lp.vehicle_id left join users d on d.id = lp.driver_id`;
const ITEM_FILTER = `p.company_code = any(f.companies) and ${ORDER_FILTER}`;
const lineValue = (pieces: string) => `round(l.price_per_carton::numeric * ${pieces} / p.pieces_per_carton)`;

const delivery: Report[] = [
  {
    key: 'delivery-notes',
    group: 'delivery',
    title: 'Delivery note register',
    description: 'Every delivery note given at loading, with its vehicle, driver and value before VAT.',
    roles: SALES_TEAM,
    filters: ['dates', 'company', 'person', 'customer', 'channel'],
    columns: [
      col('date', 'Loaded', 'date'),
      col('deliveryNote', 'Delivery note'),
      col('plan', 'Loading plan'),
      col('vehicle', 'Vehicle'),
      col('driver', 'Driver'),
      col('status', 'Status'),
      col('customers', 'Customers', 'int', true),
      col('value', 'Value', 'money', true),
    ],
    sql: `select ${DAY(LOCAL('lp.loaded_at'))} as date, lp.delivery_note_no as "deliveryNote", lp.number as plan, coalesce(v.plate, '') as vehicle,
        coalesce(d.name, '') as driver,
        case lp.status when 'loaded' then 'Loaded' when 'out' then 'On the road' else 'Delivered' end as status,
        count(distinct o.customer_id)::int as customers, sum(${lineValue('i.loaded')})::float8 as value
      ${ITEM_FROM}
      where lp.delivery_note_no is not null and ${LOCAL('lp.loaded_at')} between f.d_from and f.d_to and ${ITEM_FILTER}
      group by lp.id, v.plate, d.name order by lp.loaded_at`,
  },
  {
    key: 'deliveries-pending',
    group: 'delivery',
    title: 'Pending deliveries',
    description: 'Goods on a vehicle that the customer has not yet received, from loading plans and trips.',
    roles: SALES_TEAM,
    filters: ['company', 'person', 'customer', 'channel'],
    columns: [
      col('since', 'Loaded', 'date'),
      col('document', 'Delivery note or trip'),
      col('vehicle', 'Vehicle'),
      col('driver', 'Driver'),
      col('customer', 'Customer'),
      col('order', 'Order'),
      col('product', 'Product'),
      col('qty', 'Quantity', 'qty'),
      col('value', 'Value', 'money', true),
    ],
    sql: `select * from (
        select ${DAY(LOCAL('lp.loaded_at'))} as since, coalesce(lp.delivery_note_no, lp.number) as document, coalesce(v.plate, '') as vehicle,
          coalesce(d.name, '') as driver, c.name as customer, o.number as "order", ${PRODUCT('p')} as product, i.loaded as qty,
          p.pieces_per_carton as ppc, ${lineValue('i.loaded')}::float8 as value, lp.loaded_at as at
        ${ITEM_FROM}
        where lp.status in ('loaded', 'out') and i.delivered is null and i.loaded > 0 and ${ITEM_FILTER}
      union all
        select ${DAY(LOCAL('t.loaded_at'))}, t.number, coalesce(v.plate, ''), coalesce(d.name, ''), c.name, o.number, ${PRODUCT('p')},
          l.on_vehicle, p.pieces_per_carton, ${lineValue('l.on_vehicle')}::float8, t.loaded_at
        from f cross join trips t join trip_orders tro on tro.trip_id = t.id join orders o on o.id = tro.order_id
          join order_lines l on l.order_id = o.id join customers c on c.id = o.customer_id join products p on p.id = l.product_id
          left join vehicles v on v.id = t.vehicle_id left join users d on d.id = t.driver_id
        where t.status = 'out' and l.on_vehicle > 0 and ${ITEM_FILTER}) x
      order by at, document, customer`,
  },
  {
    key: 'deliveries-done',
    group: 'delivery',
    title: 'Delivered orders',
    description: 'Each drop the customer received and signed for, with its value before VAT.',
    roles: SALES_TEAM,
    filters: ['dates', 'company', 'person', 'customer', 'channel'],
    columns: [
      col('date', 'Delivered', 'date'),
      col('document', 'Delivery note or trip'),
      col('customer', 'Customer'),
      col('orders', 'Orders'),
      col('receivedBy', 'Received by'),
      col('value', 'Value', 'money', true),
    ],
    sql: `select * from (
        select ${DAY(LOCAL('dl.confirmed_at'))} as date, coalesce(lp.delivery_note_no, lp.number) as document, c.name as customer,
          string_agg(distinct o.number, ', ') as orders, dl.received_by as "receivedBy", sum(${lineValue('i.delivered')})::float8 as value,
          dl.confirmed_at as at
        ${ITEM_FROM} join deliveries dl on dl.id = i.delivery_id
        where ${LOCAL('dl.confirmed_at')} between f.d_from and f.d_to and i.delivered > 0 and ${ITEM_FILTER}
        group by dl.id, lp.delivery_note_no, lp.number, c.name
      union all
        select ${DAY(LOCAL('ts.sold_at'))}, t.number, c.name, o.number, ts.received_by,
          sum(il.amount)::float8, ts.sold_at
        from f cross join trip_sales ts join trips t on t.id = ts.trip_id join orders o on o.id = ts.order_id
          join customers c on c.id = o.customer_id join invoices iv on iv.trip_sale_id = ts.id
          join invoice_lines il on il.invoice_id = iv.id join products p on p.id = il.product_id
        where ${LOCAL('ts.sold_at')} between f.d_from and f.d_to and ${ITEM_FILTER}
        group by ts.id, t.number, c.name, o.number) x
      order by at`,
  },
  {
    key: 'deliveries-partial',
    group: 'delivery',
    title: 'Part deliveries',
    description: 'Drops where the customer took less than was loaded, and why.',
    roles: SALES_TEAM,
    filters: ['dates', 'company', 'person', 'customer', 'channel'],
    columns: [
      col('date', 'Delivered', 'date'),
      col('document', 'Delivery note'),
      col('customer', 'Customer'),
      col('order', 'Order'),
      col('product', 'Product'),
      col('loaded', 'Loaded', 'qty'),
      col('delivered', 'Taken', 'qty'),
      col('short', 'Not taken', 'qty'),
      col('reason', 'Why'),
    ],
    sql: `select ${DAY(LOCAL('dl.confirmed_at'))} as date, coalesce(lp.delivery_note_no, lp.number) as document, c.name as customer,
        o.number as "order", ${PRODUCT('p')} as product, i.loaded, i.delivered, i.loaded - i.delivered as short, p.pieces_per_carton as ppc,
        coalesce(i.short_reason, '') as reason
      ${ITEM_FROM} join deliveries dl on dl.id = i.delivery_id
      where ${LOCAL('dl.confirmed_at')} between f.d_from and f.d_to and i.delivered < i.loaded and ${ITEM_FILTER}
      order by dl.confirmed_at, i.id`,
  },
];

const INVOICE_HEAD = `${DAY('iv.invoice_date')} as date, iv.number, iv.company_code as company, c.name as customer`;
const INVOICE_HEAD_COLUMNS = [col('date', 'Date', 'date'), col('number', 'Invoice'), col('company', 'Company'), col('customer', 'Customer')];
const BALANCE_COLUMNS = [
  ...INVOICE_HEAD_COLUMNS,
  col('salesman', 'Salesman'),
  col('dueDate', 'Due', 'date'),
  col('daysLate', 'Days late', 'int'),
  col('total', 'Total', 'money', true),
  col('balance', 'Still to pay', 'money', true),
];
// Invoices with something still to pay; the dates are those of the invoice.
const balances = (onlyLate: boolean) => `select * from (
    select ${INVOICE_HEAD}, coalesce(u.name, '') as salesman, ${DAY('iv.due_date')} as "dueDate",
      greatest(${TODAY} - iv.due_date, 0) as "daysLate", iv.total::float8 as total, ${INVOICE_BALANCE('iv')}::float8 as balance, iv.id
    ${INVOICES_FROM} left join users u on u.id = iv.salesman_id
    where ${INVOICE_FILTER} ${onlyLate ? `and iv.due_date < ${TODAY}` : ''}) x
  where balance > 0 order by "dueDate", id`;

const invoices: Report[] = [
  {
    key: 'invoice-register',
    group: 'invoices',
    title: 'Invoice register',
    description: 'Every invoice made, with and without VAT.',
    roles: SALES_TEAM.concat('cashier'),
    filters: SALES_FILTERS,
    columns: [
      ...INVOICE_HEAD_COLUMNS,
      col('type', 'Type'),
      col('salesman', 'Salesman'),
      col('subtotal', 'Before VAT', 'money', true),
      col('vat', 'VAT', 'money', true),
      col('total', 'Total', 'money', true),
    ],
    sql: `select ${INVOICE_HEAD}, ${INVOICE_TYPE('iv')} as type, coalesce(u.name, '') as salesman, iv.subtotal::float8 as subtotal,
        iv.vat::float8 as vat, iv.total::float8 as total
      ${INVOICES_FROM} left join users u on u.id = iv.salesman_id
      where ${INVOICE_FILTER} order by iv.invoice_date, iv.id`,
  },
  {
    key: 'invoices-cancelled',
    group: 'invoices',
    title: 'Cancelled invoices',
    description: 'Invoices taken back in full by a sales return or a credit note.',
    roles: SALES_TEAM.concat('cashier'),
    filters: SALES_FILTERS,
    columns: [
      ...INVOICE_HEAD_COLUMNS,
      col('total', 'Total', 'money', true),
      col('returned', 'Returned', 'money', true),
      col('credited', 'Credit notes', 'money', true),
    ],
    sql: `select * from (
        select ${INVOICE_HEAD}, iv.total::float8 as total,
          coalesce((select sum(r.total) from sales_returns r where r.invoice_id = iv.id and r.booked_at is not null), 0)::float8 as returned,
          coalesce((select sum(n.amount) from invoice_notes n where n.invoice_id = iv.id and n.kind in ('credit_issued', 'debit_received')), 0)::float8
            as credited, iv.id
        ${INVOICES_FROM} where ${INVOICE_FILTER}) x
      where total > 0 and returned + credited >= total order by date, id`,
  },
  {
    key: 'invoices-cash-credit',
    group: 'invoices',
    title: 'Cash and credit invoices',
    description: 'Each invoice marked cash (due at once) or credit (on payment terms).',
    roles: SALES_TEAM.concat('cashier'),
    filters: SALES_FILTERS,
    columns: [...INVOICE_HEAD_COLUMNS, col('terms', 'Cash or credit'), col('dueDate', 'Due', 'date'), col('total', 'Total', 'money', true)],
    sql: `select ${INVOICE_HEAD}, case when iv.due_date <= iv.invoice_date then 'Cash' else 'Credit' end as terms,
        ${DAY('iv.due_date')} as "dueDate", iv.total::float8 as total
      ${INVOICES_FROM} where ${INVOICE_FILTER} order by iv.invoice_date, iv.id`,
  },
  {
    key: 'invoices-outstanding',
    group: 'invoices',
    title: 'Outstanding invoices',
    description: 'Invoices with something still to pay. The dates are those of the invoice.',
    roles: SALES_TEAM.concat('cashier'),
    filters: SALES_FILTERS,
    defaultFrom: 'all',
    columns: BALANCE_COLUMNS,
    sql: balances(false),
  },
  {
    key: 'invoices-overdue',
    group: 'receivables',
    title: 'Overdue invoices',
    description: 'Invoices past their due date with something still to pay.',
    roles: SALES_TEAM.concat('cashier'),
    filters: SALES_FILTERS,
    defaultFrom: 'all',
    columns: BALANCE_COLUMNS,
    sql: balances(true),
  },
];

export const SALES_REPORTS: Report[] = [...sales, ...orders, ...delivery, ...invoices];
