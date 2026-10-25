import { CHANNEL_LABELS } from '../lists/common.js';
import type { Column, Kind, Role } from './engine.js';

export const col = (key: string, title: string, kind: Kind = 'text', total = false): Column => ({ key, title, kind, total });

// Who sees which reports. A salesman always sees only his own figures; the warehouse never sees money.
export const SALES_TEAM: Role[] = ['admin', 'management', 'sales_manager', 'salesman', 'accounts'];
export const MONEY_TEAM: Role[] = [...SALES_TEAM, 'cashier'];
export const STOCK_TEAM: Role[] = ['admin', 'management', 'warehouse', 'sales_manager'];
export const OFFICE: Role[] = ['admin', 'management', 'sales_manager', 'accounts'];

export const PRODUCT = (p: string) => `(${p}.brand || ' ' || ${p}.name || ' ' || ${p}.pack_size)`;
export const CHANNEL = (c: string) =>
  `(case ${c}.channel ${Object.entries(CHANNEL_LABELS)
    .map(([k, v]) => `when '${k}' then '${v}'`)
    .join(' ')} end)`;
export const MODE = (c: string) => `(case ${c}.mode when 'cash' then 'Cash' when 'bank' then 'Bank transfer' else 'Airtel Money' end)`;
export const INVOICE_TYPE = (iv: string) => `(case ${iv}.invoice_type when 'vat' then 'With VAT' else 'Without VAT' end)`;

// Invoices of the companies and dates picked. Needs the aliases iv (invoices) and c (customers).
export const INVOICE_FILTER = `iv.company_code = any(f.companies) and iv.invoice_date between f.d_from and f.d_to
  and (f.person is null or iv.salesman_id = f.person) and (f.customer is null or iv.customer_id = f.customer)
  and (f.channel is null or c.channel = f.channel)`;

// The three amounts of a group of invoices.
export const INVOICE_SUMS = `count(*)::int as invoices, sum(iv.subtotal)::float8 as subtotal, sum(iv.vat)::float8 as vat,
  sum(iv.total)::float8 as total`;
export const INVOICE_COLUMNS: Column[] = [
  col('invoices', 'Invoices', 'int', true),
  col('subtotal', 'Before VAT', 'money', true),
  col('vat', 'VAT', 'money', true),
  col('total', 'Total', 'money', true),
];
