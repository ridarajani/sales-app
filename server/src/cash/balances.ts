// What customers owe, worked out from invoices, payments, returns, notes and the opening balance. Every amount is whole kwacha.
// The subqueries use their own aliases (b_...), so they never hide a table of the query they are put in.

// A note's effect on what the customer owes: credit notes we issue and debit notes they send lower it.
export const NOTE_SIGN = (n: string) => `(case when ${n}.kind in ('debit_issued', 'credit_received') then 1 else -1 end)`;

// What is still to pay on one invoice: its total, less the payments put on it, less any discount or
// withholding tax given on it at collection, less booked returns, plus or minus notes. `iv` is the alias of the invoices table.
export const INVOICE_BALANCE = (iv: string) => `(${iv}.total
  - coalesce((select sum(b_a.amount) from collection_allocations b_a where b_a.invoice_id = ${iv}.id), 0)
  - coalesce((select sum(b_c.discount + b_c.wht) from collections b_c where b_c.invoice_id = ${iv}.id and b_c.cancelled_at is null), 0)
  - coalesce((select sum(b_r.total) from sales_returns b_r where b_r.invoice_id = ${iv}.id and b_r.booked_at is not null), 0)
  + coalesce((select sum(${NOTE_SIGN('b_n')} * b_n.amount) from invoice_notes b_n where b_n.invoice_id = ${iv}.id), 0))`;

// What a customer owes one company: the opening balance plus every invoice, less everything paid, given off or
// returned, plus or minus notes. Cancelled payments do not count.
// Money paid on account (more than was owed) makes it go below 0.
export const CUSTOMER_OWES = (customer: string, company: string) => `(
  coalesce((select b_ob.amount from opening_balances b_ob where b_ob.customer_id = ${customer} and b_ob.company_code = ${company}), 0)
  + coalesce((select sum(b_iv.total) from invoices b_iv where b_iv.customer_id = ${customer} and b_iv.company_code = ${company}), 0)
  - coalesce((select sum(b_c.amount + b_c.discount + b_c.wht) from collections b_c
      where b_c.customer_id = ${customer} and b_c.company_code = ${company} and b_c.cancelled_at is null), 0)
  - coalesce((select sum(b_r.total) from sales_returns b_r
      where b_r.customer_id = ${customer} and b_r.company_code = ${company} and b_r.booked_at is not null), 0)
  + coalesce((select sum(${NOTE_SIGN('b_n')} * b_n.amount) from invoice_notes b_n
      where b_n.customer_id = ${customer} and b_n.company_code = ${company}), 0))`;

// What is left of the opening balance after the payments put on it.
export const OPENING_LEFT = (customer: string, company: string) => `greatest(0,
  coalesce((select b_ob.amount from opening_balances b_ob where b_ob.customer_id = ${customer} and b_ob.company_code = ${company}), 0)
  - coalesce((select sum(b_a.amount) from collection_allocations b_a join collections b_c on b_c.id = b_a.collection_id
      where b_a.invoice_id is null and b_c.customer_id = ${customer} and b_c.company_code = ${company}), 0))`;
