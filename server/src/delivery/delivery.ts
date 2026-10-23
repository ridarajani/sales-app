import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { audit } from '../audit.js';
import { HttpError, notAllowed, requireAuth, requireRole } from '../auth/guard.js';
import type { Db } from '../db/pool.js';
import { readImage, saveImage } from '../files.js';
import type { Channel } from '../lists/common.js';
import { idParams, invalid, notFound } from '../lists/common.js';
import { lockPlan, PLAN_VIEWERS, planDetail, visibleWhere } from '../loading/loading.js';
import { addEvent, qtyText, reserveStock } from '../orders/orders.js';
import { vehicleDocPdf } from '../print/pdf.js';
import { inTransaction } from '../stock/stock.js';
import { INT4_MAX, parse } from '../users.js';

// The driver of the vehicle confirms each drop; an admin can do it for them.
export const DELIVERERS = ['admin', 'driver'] as const;

const SIGNATURE_MAX = 2 * 1024 * 1024;
const PHOTO_MAX = 8 * 1024 * 1024;

const id = z.number().int().positive().max(INT4_MAX);
const deliveryBody = z.object({
  customerId: id,
  receivedBy: z.string().trim().max(100).default(''),
  // PNG or JPEG as base64, from the signature pad and the camera.
  signature: z.string().max(4_000_000).optional(),
  photo: z.string().max(12_000_000).optional(),
  note: z.string().trim().max(1000).default(''),
  lines: z
    .array(
      z.object({
        itemId: id,
        cartons: z.number().int().min(0, 'Cartons cannot be below 0.').max(1_000_000).default(0),
        pieces: z.number().int().min(0, 'Pieces cannot be below 0.').max(INT4_MAX).default(0),
        reason: z.string().trim().max(500).default(''),
      }),
    )
    .min(1, 'Please confirm the products.')
    .max(500),
});

type DropItem = {
  itemId: number;
  lineId: number;
  orderId: number;
  orderStatus: string;
  channel: Channel;
  piecesPerCarton: number;
  label: string;
  loaded: number;
  invoiced: boolean;
};

export function deliveryRoutes(app: FastifyInstance, db: Db, uploadDir: string) {
  const auth = requireAuth(db);
  const deliverers = requireRole(...DELIVERERS);
  const viewers = requireRole(...PLAN_VIEWERS);

  // The driver confirms one customer's drop: what was handed over, who received it and their signature.
  // Delivered stock leaves the books now (client, 7 Oct); what the customer did not take goes back on the shelf
  // and stays open on the order. The invoice is not changed: the shortfall is credited by a sales return (Phase 8).
  app.post(
    '/loading-plans/:id/deliveries',
    { onRequest: auth, preHandler: deliverers, bodyLimit: 16 * 1024 * 1024 },
    async (request, reply) => {
      const { id: planId } = parse(idParams, request.params);
      const body = parse(deliveryBody, request.body);
      const user = request.user;

      // Check what can be checked before saving any files.
      const first = await planDetail(db, user, planId);
      if (!first) throw notFound('loading plan');
      if (user.role === 'driver' && first.driverId !== user.id) throw notAllowed();
      const seen = new Set<number>();
      for (const l of body.lines) {
        if (seen.has(l.itemId)) throw invalid('A product is listed twice.');
        seen.add(l.itemId);
      }

      // Files are saved first, so nothing is written to the database if one cannot be read.
      const anything = body.lines.some((l) => l.cartons > 0 || l.pieces > 0);
      if (anything && !body.receivedBy) throw invalid('Please type the name of the person who received the goods.');
      if (anything && !body.signature) throw invalid('Please ask the customer to sign.');
      const signatureFile = body.signature ? await saveImage(uploadDir, body.signature, SIGNATURE_MAX, 'signature') : null;
      const photoFile = body.photo ? await saveImage(uploadDir, body.photo, PHOTO_MAX, 'photo') : null;

      const result = await inTransaction(db, async (client) => {
        const plan = await lockPlan(client, user, planId);
        if (user.role === 'driver' && plan.driverId !== user.id) throw notAllowed();
        if (plan.status === 'loaded') throw new HttpError(409, 'not_out', 'The vehicle has not left the warehouse yet.');
        if (plan.status === 'done') throw new HttpError(409, 'plan_done', 'Every delivery on this vehicle is already confirmed.');
        if (plan.status !== 'out') throw new HttpError(409, 'not_loaded', 'This vehicle has not been loaded yet.');
        const { rows: done } = await client.query('select 1 from deliveries where plan_id = $1 and customer_id = $2', [plan.id, body.customerId]);
        if (done[0]) throw new HttpError(409, 'already_delivered', 'This delivery is already confirmed.');

        const { rows: items } = await client.query<DropItem>(
          `select i.id as "itemId", l.id as "lineId", o.id as "orderId", o.status as "orderStatus", c.channel,
             p.pieces_per_carton as "piecesPerCarton", p.brand || ' ' || p.name || ' ' || p.pack_size as label, i.loaded,
             exists (select 1 from invoice_lines il where il.plan_item_id = i.id) as invoiced
           from loading_plan_items i join order_lines l on l.id = i.order_line_id join orders o on o.id = l.order_id
             join customers c on c.id = o.customer_id join products p on p.id = l.product_id
           where i.plan_id = $1 and o.customer_id = $2 and i.loaded > 0 order by o.id, l.id`,
          [plan.id, body.customerId],
        );
        if (items.length === 0) throw invalid('That customer has nothing on this vehicle.');
        if (items.some((i) => !i.invoiced)) {
          throw new HttpError(409, 'not_invoiced', 'The invoices for this customer are not made yet. Ask the sales manager to make them.');
        }
        const byItem = new Map(body.lines.map((l) => [l.itemId, l]));
        if (body.lines.some((l) => !items.some((i) => i.itemId === l.itemId))) throw invalid('That product is not on this delivery.');
        if (items.some((i) => !byItem.has(i.itemId))) throw invalid('Please confirm every product for this customer.');
        const delivered = items.map((it) => {
          const l = byItem.get(it.itemId)!;
          const pieces = l.cartons * it.piecesPerCarton + l.pieces;
          if (pieces > it.loaded) throw invalid(`${it.label}: only ${qtyText(it.loaded, it.piecesPerCarton)} was loaded.`);
          if (pieces < it.loaded && !l.reason) throw invalid(`${it.label}: please say why less was delivered.`);
          return { ...it, delivered: pieces, reason: pieces < it.loaded ? l.reason : null };
        });

        // Orders, then batches, in id order, like loading does.
        const orderIds = [...new Set(items.map((i) => i.orderId))].sort((a, b) => a - b);
        await client.query('select id from orders where id = any($1) order by id for update', [orderIds]);
        const itemIds = items.map((i) => i.itemId);
        await client.query(
          `select id from stock_batches where id in (select batch_id from loading_plan_item_batches where item_id = any($1)) order by id for update`,
          [itemIds],
        );

        const { rows: saved } = await client.query<{ id: number }>(
          `insert into deliveries (plan_id, customer_id, received_by, signature_file, photo_file, note, confirmed_by)
           values ($1, $2, $3, $4, $5, $6, $7) returning id`,
          [plan.id, body.customerId, body.receivedBy || 'Refused', signatureFile ?? '', photoFile, body.note, user.id],
        );
        const deliveryId = saved[0]!.id;

        for (const it of delivered) {
          // What was handed over comes off the oldest batches on the vehicle; the rest goes back on the shelf.
          const { rows: batches } = await client.query<{ batchId: number; pieces: number }>(
            `select ib.batch_id as "batchId", ib.pieces from loading_plan_item_batches ib join stock_batches b on b.id = ib.batch_id
             where ib.item_id = $1 order by b.expiry_date, b.id`,
            [it.itemId],
          );
          let left = it.delivered;
          for (const b of batches) {
            const take = Math.min(left, b.pieces);
            left -= take;
            await client.query('update stock_batches set on_hand = on_hand - $2, in_transit = in_transit - $3 where id = $1', [
              b.batchId,
              take,
              b.pieces,
            ]);
            if (take > 0) {
              await client.query(
                `insert into stock_movements (batch_id, change, kind, delivery_id, user_id) values ($1, $2, 'delivered', $3, $4)`,
                [b.batchId, -take, deliveryId, user.id],
              );
            }
          }
          await client.query('update loading_plan_items set delivered = $2, short_reason = $3, delivery_id = $4 where id = $1', [
            it.itemId,
            it.delivered,
            it.reason,
            deliveryId,
          ]);
          await client.query('update order_lines set on_vehicle = on_vehicle - $2, delivered = delivered + $3 where id = $1', [
            it.lineId,
            it.loaded,
            it.delivered,
          ]);
        }

        for (const orderId of orderIds) {
          const its = delivered.filter((d) => d.orderId === orderId);
          const short = its.filter((d) => d.delivered < d.loaded);
          const title = its.every((d) => d.delivered === 0) ? 'Refused' : short.length ? 'Part delivered' : 'Delivered';
          const detail = [
            body.receivedBy && `Received by ${body.receivedBy}`,
            ...short.map((d) => `${d.label}: ${qtyText(d.loaded - d.delivered, d.piecesPerCarton)} not delivered (${d.reason})`),
          ]
            .filter(Boolean)
            .join(' · ');
          await addEvent(client, orderId, user.id, title, detail);
          const { rows: o } = await client.query<{ left: number }>(
            'select coalesce(sum(pieces - delivered), 0)::int as left from order_lines where order_id = $1',
            [orderId],
          );
          const first = its[0]!;
          if (o[0]!.left === 0) {
            await client.query(`update orders set status = 'done' where id = $1 and status in ('open', 'hold')`, [orderId]);
          } else if (['open', 'hold'].includes(first.orderStatus)) {
            // What the customer did not take is open again: set stock aside for it.
            await reserveStock(client, orderId, first.channel);
          }
        }

        // The vehicle is done when every customer on it is confirmed.
        const { rows: open } = await client.query(
          `select 1 from loading_plan_items i where i.plan_id = $1 and i.loaded > 0 and i.delivery_id is null limit 1`,
          [plan.id],
        );
        if (!open[0]) await client.query(`update loading_plans set status = 'done', updated_at = now() where id = $1`, [plan.id]);
        else await client.query('update loading_plans set updated_at = now() where id = $1', [plan.id]);
        return { number: plan.number, deliveryId, short: delivered.some((d) => d.delivered < d.loaded) };
      });
      await audit(db, {
        userId: user.id,
        action: 'delivery_confirmed',
        details: { planId, number: result.number, deliveryId: result.deliveryId, customerId: body.customerId, short: result.short },
      });
      return reply.code(201).send(await planDetail(db, user, planId));
    },
  );

  // The signature and photo of a delivery, for anyone who can see its vehicle.
  for (const kind of ['signature', 'photo'] as const) {
    app.get(`/deliveries/:id/${kind}`, { onRequest: auth, preHandler: viewers }, async (request, reply) => {
      const { id: deliveryId } = parse(idParams, request.params);
      const user = request.user;
      const { rows } = await db.query<{ file: string | null }>(
        `select d.${kind}_file as file from deliveries d join loading_plans lp on lp.id = d.plan_id
         where d.id = $3 and ${visibleWhere(user)}`,
        [user.companies, user.id, deliveryId],
      );
      const image = rows[0]?.file ? await readImage(uploadDir, rows[0].file) : null;
      if (!image) throw notFound(kind);
      return reply.type(image.type).header('cache-control', 'private, max-age=86400').send(image.bytes);
    });
  }

  // The delivery note and the gate pass: warehouse, vehicle and products only.
  for (const [path, title] of [
    ['delivery-note', 'DELIVERY NOTE'],
    ['gate-pass', 'GATE PASS'],
  ] as const) {
    app.get(`/loading-plans/:id/${path}.pdf`, { onRequest: auth, preHandler: viewers }, async (request, reply) => {
      const { id: planId } = parse(idParams, request.params);
      const plan = await planDetail(db, request.user, planId);
      if (!plan) throw notFound('loading plan');
      const number = path === 'delivery-note' ? plan.deliveryNoteNo : plan.gatePassNo;
      if (!number) throw new HttpError(409, 'not_loaded', 'Load the vehicle first.');
      const pdf = await vehicleDocPdf({
        title,
        number,
        date: new Date(plan.loadedAt).toLocaleDateString('en-CA', { timeZone: 'Africa/Blantyre' }),
        facilityName: plan.facilityName,
        vehiclePlate: plan.vehiclePlate,
        driverName: plan.driverName,
        lines: plan.products
          .filter((p: { loaded: number }) => p.loaded > 0)
          .map((p: { code: string; brand: string; name: string; packSize: string; loaded: number; piecesPerCarton: number }) => ({ code: p.code, description: `${p.brand} ${p.name} ${p.packSize}`, quantity: qtyText(p.loaded, p.piecesPerCarton) })),
      });
      return reply.type('application/pdf').header('content-disposition', `inline; filename="${number}.pdf"`).send(pdf);
    });
  }
}
