import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { deleteOrMark, idParams, invalid, notFound } from './common.js';

const vehicleFields = z.object({
  plate: z.string().trim().min(1, 'Please type the number plate.').max(20).transform((s) => s.toUpperCase().replace(/\s+/g, ' ')),
  description: z.string().trim(),
  facilityCode: z.string().nullish(),
  defaultDriverId: z.number().int().positive().max(2147483647).nullish(),
});
// Defaults apply only when creating. An update leaves out fields it does not send.
const vehicleBody = vehicleFields.extend({ description: z.string().trim().default('') });
const updateBody = vehicleFields.partial().extend({ active: z.boolean().optional() });

type Vehicle = {
  id: number;
  plate: string;
  description: string;
  facilityCode: string | null;
  defaultDriverId: number | null;
  defaultDriverName: string | null;
  active: boolean;
  // Set by this user: a vehicle they do not use, kept at the bottom of their list.
  dormant: boolean;
};

// $1 is the user, for their own dormant mark.
const vehicleSelect = `select v.id, v.plate, v.description, v.facility_code as "facilityCode",
    v.default_driver_id as "defaultDriverId", u.name as "defaultDriverName", v.active,
    exists (select 1 from user_dormant_vehicles d where d.user_id = $1 and d.vehicle_id = v.id) as dormant
  from vehicles v left join users u on u.id = v.default_driver_id
  where v.deleted_at is null`;
const dormantBody = z.object({ dormant: z.boolean() });

async function checkRefs(db: Db, facilityCode: string | null | undefined, driverId: number | null | undefined) {
  if (facilityCode) {
    const { rows } = await db.query('select 1 from facilities where code = $1', [facilityCode]);
    if (!rows[0]) throw invalid('That facility is not known.');
  }
  if (driverId != null) {
    const { rows } = await db.query("select 1 from users where id = $1 and role = 'driver' and active", [driverId]);
    if (!rows[0]) throw invalid('That driver was not found.');
  }
}

const plateTaken = () => invalid('A vehicle with this number plate already exists.');

export function vehicleRoutes(app: FastifyInstance, db: Db) {
  const auth = requireAuth(db);
  // Vehicles are shared by all four companies, so both sales managers keep the list.
  const admin = requireRole('admin', 'sales_manager');
  const one = async (userId: number, id: number) => (await db.query<Vehicle>(`${vehicleSelect} and v.id = $2`, [userId, id])).rows[0];

  // The ones this user marked dormant come after the ones they use.
  app.get('/vehicles', { onRequest: auth }, async (request) => {
    return (await db.query<Vehicle>(`${vehicleSelect} order by v.active desc, dormant, v.plate`, [request.user.id])).rows;
  });

  // Drivers are users with the driver role; listed here for picking one in the vehicle and loading plan screens.
  app.get('/drivers', { onRequest: auth }, async () => {
    return (await db.query("select id, name, phone from users where role = 'driver' and active order by name")).rows;
  });

  app.post('/vehicles', { onRequest: auth, preHandler: admin }, async (request, reply) => {
    const body = parse(vehicleBody, request.body);
    await checkRefs(db, body.facilityCode, body.defaultDriverId);
    const { rows } = await db.query<{ id: number }>(
      `insert into vehicles (plate, description, facility_code, default_driver_id) values ($1, $2, $3, $4)
       on conflict (plate) where deleted_at is null do nothing returning id`,
      [body.plate, body.description, body.facilityCode ?? null, body.defaultDriverId ?? null],
    );
    const id = rows[0]?.id;
    if (id == null) throw plateTaken();
    await audit(db, { userId: request.user.id, action: 'vehicle_created', details: { vehicleId: id, plate: body.plate } });
    return reply.code(201).send(await one(request.user.id, id));
  });

  app.put('/vehicles/:id', { onRequest: auth, preHandler: admin }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(updateBody, request.body);
    const current = await one(request.user.id, id);
    if (!current) throw notFound('vehicle');
    await checkRefs(db, body.facilityCode, body.defaultDriverId);
    const { rowCount } = await db.query(
      `update vehicles set plate = $2, description = $3, facility_code = $4, default_driver_id = $5, active = $6, updated_at = now()
       where id = $1 and not exists (select 1 from vehicles o where o.plate = $2 and o.id <> $1 and o.deleted_at is null)`,
      [
        id,
        body.plate ?? current.plate,
        body.description ?? current.description,
        body.facilityCode === undefined ? current.facilityCode : body.facilityCode,
        body.defaultDriverId === undefined ? current.defaultDriverId : body.defaultDriverId,
        body.active ?? current.active,
      ],
    );
    if (rowCount === 0) throw plateTaken();
    await audit(db, { userId: request.user.id, action: 'vehicle_updated', details: { vehicleId: id, changes: Object.keys(body) } });
    return one(request.user.id, id);
  });

  app.delete('/vehicles/:id', { onRequest: auth, preHandler: admin }, async (request) => {
    const { id } = parse(idParams, request.params);
    const current = await one(request.user.id, id);
    if (!current) throw notFound('vehicle');
    const keptForHistory = await deleteOrMark(db, 'vehicles', id);
    await audit(db, { userId: request.user.id, action: 'vehicle_deleted', details: { vehicleId: id, plate: current.plate, keptForHistory } });
    return { deleted: true, keptForHistory };
  });

  // Anyone who picks vehicles can mark one dormant for themselves, or back in use.
  app.put('/vehicles/:id/dormant', { onRequest: auth }, async (request) => {
    const { id } = parse(idParams, request.params);
    const { dormant } = parse(dormantBody, request.body);
    if (!(await one(request.user.id, id))) throw notFound('vehicle');
    await db.query(
      dormant
        ? 'insert into user_dormant_vehicles (user_id, vehicle_id) values ($1, $2) on conflict do nothing'
        : 'delete from user_dormant_vehicles where user_id = $1 and vehicle_id = $2',
      [request.user.id, id],
    );
    return one(request.user.id, id);
  });
}
