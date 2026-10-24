// What customers owe, worked out from invoices, payments and the opening balance. Every amount is whole kwacha.
// The subqueries use their own aliases (b_...), so they never hide a table of the query they are put in.

// What is still to pay on one invoice: its total, less the payments put on it, less any discount or
// withholding tax given on it at collection. `iv` is the alias of the invoices table.
export const INVOICE_BALANCE = (iv: string) => `(${iv}.total
  - coalesce((select sum(b_a.amount) from collection_allocations b_a where b_a.invoice_id = ${iv}.id), 0)
  - coalesce((select sum(b_c.discount + b_c.wht) from collections b_c where b_c.invoice_id = ${iv}.id), 0))`;

// What a customer owes one company: the opening balance plus every invoice, less everything paid or given off.
// Money paid on account (more than was owed) makes it go below 0.
export const CUSTOMER_OWES = (customer: string, company: string) => `(
  coalesce((select b_ob.amount from opening_balances b_ob where b_ob.customer_id = ${customer} and b_ob.company_code = ${company}), 0)
  + coalesce((select sum(b_iv.total) from invoices b_iv where b_iv.customer_id = ${customer} and b_iv.company_code = ${company}), 0)
  - coalesce((select sum(b_c.amount + b_c.discount + b_c.wht) from collections b_c
      where b_c.customer_id = ${customer} and b_c.company_code = ${company}), 0))`;

// What is left of the opening balance after the payments put on it.
export const OPENING_LEFT = (customer: string, company: string) => `greatest(0,
  coalesce((select b_ob.amount from opening_balances b_ob where b_ob.customer_id = ${customer} and b_ob.company_code = ${company}), 0)
  - coalesce((select sum(b_a.amount) from collection_allocations b_a join collections b_c on b_c.id = b_a.collection_id
      where b_a.invoice_id is null and b_c.customer_id = ${customer} and b_c.company_code = ${company}), 0))`;
