import type { Db } from './db/pool.js';

export async function audit(
  db: Db,
  entry: { userId: number | null; action: string; targetUserId?: number | null; details?: Record<string, unknown> },
) {
  await db.query('insert into audit_log (user_id, action, target_user_id, details) values ($1, $2, $3, $4)', [
    entry.userId,
    entry.action,
    entry.targetUserId ?? null,
    entry.details ? JSON.stringify(entry.details) : null,
  ]);
}
