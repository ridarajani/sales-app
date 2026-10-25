import type { Report } from './engine.js';
import { MONEY_REPORTS } from './money.js';
import { SALES_REPORTS } from './sales.js';
import { STOCK_REPORTS } from './stock.js';

export const REPORTS: Report[] = [...SALES_REPORTS, ...MONEY_REPORTS, ...STOCK_REPORTS];

// A salesman must only ever see his own figures: every report he can open is cut down to him by the person
// filter. The customer ledger is the one exception, as he may already see the ledger of any of his companies' customers.
for (const r of REPORTS) {
  if (r.roles.includes('salesman') && !r.filters.includes('person') && r.key !== 'customer-ledger') {
    throw new Error(`Report ${r.key} is open to salesmen but has no person filter.`);
  }
  if (REPORTS.filter((x) => x.key === r.key).length > 1) throw new Error(`Report ${r.key} is listed twice.`);
}
