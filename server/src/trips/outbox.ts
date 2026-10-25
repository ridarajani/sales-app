import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { parse } from '../users.js';
import { LEADER_ROLES, visibleWhere } from './trips.js';

// Trip sales a phone saved but could not read back. The phone sends the file here; Admin looks at it, enters
// anything missing by hand and clears it. Until then the trip cannot be closed.
export const OUTBOX_VIEWERS = ['admin', 'management'] as const;
const OUTBOX_CLEARERS = ['admin'] as const;
const FILE_MAX = 64 * 1024 * 1024;

const reportBody = z.object({
  requestId: z.string('This phone app is too old. Please update it.').trim().min(1).max(64),
  // The file as base64.
  file: z.string().min(1, 'The file is empty.'),
  fileName: z.string().trim().max(200).default(''),
  note: z.string().trim().max(2000).default(''),
});
const listQuery = z.object({ open: z.enum(['true', 'false']).optional() });
const clearBody = z.object({ note: z.string().trim().min(1, 'Please write what you did about these sales.').max(2000) });

// Problems of the trips of the user's companies.
const problemSelect = `select p.id, p.trip_id as "tripId", t.number as "tripNumber", t.company_code as "companyCode",
    t.status as "tripStatus", t.route, l.name as "leaderName", p.file_name as "fileName", length(p.file)::int as "fileSize",
    p.note, rb.name as "reportedBy", p.reported_at as "reportedAt", p.cleared_at is not null as cleared,
    cb.name as "clearedBy", p.cleared_at as "clearedAt", p.clear_note as "clearNote"
  from outbox_problems p join trips t on t.id = p.trip_id join users l on l.id = t.leader_id join users rb on rb.id = p.reported_by
    left join users cb on cb.id = p.cleared_by
  where t.company_code = any($1)`;

export function outboxRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  const viewers = requireRole(...OUTBOX_VIEWERS);
  const clearers = requireRole(...OUTBOX_CLEARERS);

  app.post(
    '/trips/:id/outbox-problems',
    { onRequest: auth, preHandler: requireRole(...LEADER_ROLES), bodyLimit: 96 * 1024 * 1024 },
    async (request, reply) => {
      const { id: tripId } = parse(idParams, request.params);
      const body = parse(reportBody, request.body);
      const user = request.user;
      const { rows: trips } = await db.query<{ id: number; number: string; leaderId: number }>(
        `select t.id, t.number, t.leader_id as "leaderId" from trips t where t.id = $3 and ${visibleWhere(user)}`,
        [user.companies, user.id, tripId],
      );
      const trip = trips[0];
      if (!trip) throw notFound('trip');
      if (trip.leaderId !== user.id) throw notAllowed();
      const bytes = Buffer.from(body.file, 'base64');
      if (!bytes.length) throw invalid('The file is empty.');
      if (bytes.length > FILE_MAX) throw invalid('The file is too big.');
      const { rows } = await db.query<{ id: number }>(
        `insert into outbox_problems (trip_id, file, file_name, note, request_id, reported_by) values ($1, $2, $3, $4, $5, $6)
         on conflict (reported_by, request_id) do nothing returning id`,
        [trip.id, bytes, body.fileName, body.note, body.requestId, user.id],
      );
      if (!rows[0]) {
        // Sent before: answer as the first time did.
        const { rows: before } = await db.query<{ id: number; tripId: number; cleared: boolean }>(
          `select id, trip_id as "tripId", cleared_at is not null as cleared from outbox_problems where reported_by = $1 and request_id = $2`,
          [user.id, body.requestId],
        );
        if (before[0]!.tripId !== trip.id) throw new HttpError(409, 'request_used', 'This was already sent for another trip.');
        return { id: before[0]!.id, cleared: before[0]!.cleared };
      }
      await audit(db, { userId: user.id, action: 'outbox_problem_reported', details: { tripId: trip.id, number: trip.number, problemId: rows[0].id } });
      return reply.code(201).send({ id: rows[0].id, cleared: false });
    },
  );

  app.get('/outbox-problems', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { open } = parse(listQuery, request.query);
    const where = open === 'true' ? ' and p.cleared_at is null' : open === 'false' ? ' and p.cleared_at is not null' : '';
    const { rows } = await db.query(`${problemSelect}${where} order by p.cleared_at is not null, p.id desc limit 300`, [request.user.companies]);
    return rows;
  });

  app.get('/outbox-problems/:id', { onRequest: auth, preHandler: viewers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const { rows } = await db.query(`${problemSelect} and p.id = $2`, [request.user.companies, id]);
    if (!rows[0]) throw notFound('problem');
    return { ...rows[0], canClear: (OUTBOX_CLEARERS as readonly string[]).includes(request.user.role) && !rows[0].cleared };
  });

  // The file the phone sent, to open on a computer.
  app.get('/outbox-problems/:id/file', { onRequest: auth, preHandler: viewers }, async (request, reply) => {
    const { id } = parse(idParams, request.params);
    const { rows } = await db.query<{ file: Buffer; tripNumber: string }>(
      `select p.file, t.number as "tripNumber" from outbox_problems p join trips t on t.id = p.trip_id
       where t.company_code = any($1) and p.id = $2`,
      [request.user.companies, id],
    );
    if (!rows[0]) throw notFound('problem');
    return reply
      .type('application/octet-stream')
      .header('content-disposition', `attachment; filename="${rows[0].tripNumber}-unread-sales-${id}.json"`)
      .send(rows[0].file);
  });

  app.post('/outbox-problems/:id/clear', { onRequest: auth, preHandler: clearers }, async (request) => {
    const { id } = parse(idParams, request.params);
    const { note } = parse(clearBody, request.body);
    const user = request.user;
    const { rows } = await db.query<{ id: number; tripId: number }>(
      `update outbox_problems p set cleared_by = $3, cleared_at = now(), clear_note = $4
       from trips t where t.id = p.trip_id and t.company_code = any($1) and p.id = $2 and p.cleared_at is null
       returning p.id, p.trip_id as "tripId"`,
      [user.companies, id, user.id, note],
    );
    if (!rows[0]) {
      const { rows: seen } = await db.query(`${problemSelect} and p.id = $2`, [user.companies, id]);
      if (!seen[0]) throw notFound('problem');
      throw new HttpError(409, 'already_cleared', 'This was already cleared.');
    }
    await audit(db, { userId: user.id, action: 'outbox_problem_cleared', details: { problemId: id, tripId: rows[0].tripId } });
    const { rows: after } = await db.query(`${problemSelect} and p.id = $2`, [user.companies, id]);
    return { ...after[0], canClear: false };
  });
}
