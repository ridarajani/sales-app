import type { PoolClient } from 'pg';
import type { Db } from './db/pool.js';

// Next document number for a company, for example nextNumber(client, 'LV', 'SO') -> 'LV-SO-000123'.
// Call it with the client of the transaction that saves the document: the counter row stays locked
// until that transaction ends, so two documents never get the same number, and a rolled back
// document hands its number back instead of leaving a gap.
export async function nextNumber(db: Db | PoolClient, companyCode: string, docType: string): Promise<string> {
  const { rows } = await db.query<{ n: number }>(
    `insert into number_series (company_code, doc_type, last_number) values ($1, $2, 1)
     on conflict (company_code, doc_type) do update set last_number = number_series.last_number + 1
     returning last_number as n`,
    [companyCode, docType],
  );
  return `${companyCode}-${docType}-${String(rows[0]!.n).padStart(6, '0')}`;
}

// Next number of a series shared by all companies, for example nextSharedNumber(client, 'SO') -> 'SO-000123'.
// Same rules as nextNumber: call it inside the transaction that saves the document.
export async function nextSharedNumber(db: Db | PoolClient, docType: string): Promise<string> {
  const { rows } = await db.query<{ n: number }>(
    `insert into shared_number_series (doc_type, last_number) values ($1, 1)
     on conflict (doc_type) do update set last_number = shared_number_series.last_number + 1
     returning last_number as n`,
    [docType],
  );
  return `${docType}-${String(rows[0]!.n).padStart(6, '0')}`;
}
