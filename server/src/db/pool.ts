import pg from 'pg';
import { config } from '../config.js';

// Postgres returns bigint and numeric as strings; amounts are whole kwacha, so read them as numbers.
pg.types.setTypeParser(20, (v) => Number(v));
pg.types.setTypeParser(1700, (v) => Number(v));

export type Db = pg.Pool;

export function createPool(url = config.databaseUrl): Db {
  return new pg.Pool({ connectionString: url });
}
