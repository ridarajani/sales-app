import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { parse } from '../users.js';
import { idParams, invalid, notFound } from './common.js';

const vehicleBody = z.object({
  plate: z.string().trim().min(1, 'Please type the number plate.').max(20).transform((s) => s.toUpperCase().replace(/\s+/g, ' ')),
  description: z.string().trim().default(''),
  facilityCode: z.string().nullish(),
  defaultDriverId: z.number().int().positive().max(2147483647).nullish(),
});
const updateBody = vehicleBody.partial().extend({ active: z.boolean().optional() });

type Vehicle = {
  id: number;
  plate: string;
  description: string;
  facilityCode: string | null;
  defaultDriverId: number | null;
  defaultDriverName: string | null;
  active: boolean;
};

const vehicleSelect = `select v.id, v.plate, v.description, v.facility_code as "facilityCode",
    v.default_driver_id as "defaultDriverId", u.name as "defaultDriverName", v.active
  from vehicles v left join users u on u.id = v.default_driver_id`;

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
  const admin = requireRole('admin');

  app.get('/vehicles', { onRequest: auth }, async () => {
    return (await db.query<Vehicle>(`${vehicleSelect} order by v.active desc, v.plate`)).rows;
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
       on conflict (plate) do nothing returning id`,
      [body.plate, body.description, body.facilityCode ?? null, body.defaultDriverId ?? null],
    );
    const id = rows[0]?.id;
    if (id == null) throw plateTaken();
    await audit(db, { userId: request.user.id, action: 'vehicle_created', details: { vehicleId: id, plate: body.plate } });
    return reply.code(201).send((await db.query<Vehicle>(`${vehicleSelect} where v.id = $1`, [id])).rows[0]);
  });

  app.put('/vehicles/:id', { onRequest: auth, preHandler: admin }, async (request) => {
    const { id } = parse(idParams, request.params);
    const body = parse(updateBody, request.body);
    const current = (await db.query<Vehicle>(`${vehicleSelect} where v.id = $1`, [id])).rows[0];
    if (!current) throw notFound('vehicle');
    await checkRefs(db, body.facilityCode, body.defaultDriverId);
    const { rowCount } = await db.query(
      `update vehicles set plate = $2, description = $3, facility_code = $4, default_driver_id = $5, active = $6, updated_at = now()
       where id = $1 and not exists (select 1 from vehicles o where o.plate = $2 and o.id <> $1)`,
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
    return (await db.query<Vehicle>(`${vehicleSelect} where v.id = $1`, [id])).rows[0];
  });
}
