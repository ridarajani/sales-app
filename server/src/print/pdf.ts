import PDFDocument from 'pdfkit';

// A4 documents for printing and sharing by WhatsApp. Built-in Helvetica, so no font files are needed.

const MARGIN = 40;
const WIDTH = 595.28 - MARGIN * 2;
const BURGUNDY = '#7a1f3d';
const GREY = '#666666';

export const money = (n: number) => n.toLocaleString('en-US');
export const dateText = (iso: string) => {
  const [y, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}/${y}`;
};

export function toBuffer(doc: PDFKit.PDFDocument): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const parts: Buffer[] = [];
    doc.on('data', (b: Buffer) => parts.push(b));
    doc.on('end', () => resolve(Buffer.concat(parts)));
    doc.on('error', reject);
    doc.end();
  });
}

type Column = { title: string; width: number; align?: 'left' | 'right' | 'center' };

// A simple ruled table that starts a new page when it runs out of room.
function table(doc: PDFKit.PDFDocument, columns: Column[], rows: string[][]) {
  const drawRow = (cells: string[], bold: boolean) => {
    doc.font(bold ? 'Helvetica-Bold' : 'Helvetica').fontSize(10);
    const height = Math.max(...cells.map((c, i) => doc.heightOfString(c, { width: columns[i]!.width - 8 }))) + 8;
    if (doc.y + height > doc.page.height - MARGIN - 60) doc.addPage();
    const top = doc.y;
    let x = MARGIN;
    cells.forEach((c, i) => {
      const col = columns[i]!;
      doc.text(c, x + 4, top + 4, { width: col.width - 8, align: col.align ?? 'left' });
      x += col.width;
    });
    doc.moveTo(MARGIN, top + height).lineTo(MARGIN + WIDTH, top + height).lineWidth(0.5).strokeColor('#bbbbbb').stroke();
    doc.x = MARGIN;
    doc.y = top + height;
  };
  drawRow(columns.map((c) => c.title), true);
  for (const r of rows) drawRow(r, false);
}

// Label and value pairs in two columns, for the header boxes.
function pairs(doc: PDFKit.PDFDocument, x: number, y: number, width: number, items: [string, string][]) {
  let cy = y;
  for (const [label, value] of items) {
    if (!value) continue;
    doc.font('Helvetica-Bold').fontSize(9).fillColor(GREY).text(label, x, cy, { width: 80 });
    doc.font('Helvetica').fontSize(10).fillColor('black').text(value, x + 82, cy, { width: width - 82 });
    cy = Math.max(doc.y, cy + 13) + 2;
  }
  return cy;
}

export type InvoicePrint = {
  number: string;
  invoiceType: 'vat' | 'plain';
  invoiceDate: string;
  dueDate: string;
  lpoNo: string;
  vehiclePlate: string | null;
  deliveryNoteNo: string | null;
  company: { name: string; address: string | null; phone: string | null; email: string | null; tpin: string | null; logo: string | null };
  customer: { name: string; address: string; city: string; area: string; regNo: string; tpin: string };
  lines: { description: string; quantity: string; unitPrice: number; amount: number }[];
  subtotal: number;
  vatRate: number;
  vat: number;
  total: number;
  salesman: string | null;
};

// The VAT invoice follows the client's sample: company details on top, customer and LPO, the lines, then
// Sub Total, VAT and Total. The plain invoice leaves out the company name, logo and TPINs (client, 22 Oct).
export function invoicePdf(inv: InvoicePrint): Promise<Buffer> {
  const doc = new PDFDocument({ size: 'A4', margin: MARGIN, info: { Title: `Invoice ${inv.number}` } });
  const vat = inv.invoiceType === 'vat';
  let y = MARGIN;

  if (vat) {
    let textX = MARGIN;
    if (inv.company.logo) {
      try {
        doc.image(inv.company.logo, MARGIN, y, { fit: [90, 70] });
        textX = MARGIN + 100;
      } catch {
        // A logo that cannot be read is left out rather than stopping the invoice.
      }
    }
    doc.font('Helvetica-Bold').fontSize(18).fillColor(BURGUNDY).text(inv.company.name, textX, y, { width: WIDTH - (textX - MARGIN) });
    doc.font('Helvetica').fontSize(10).fillColor('black');
    for (const line of [inv.company.address, inv.company.phone && `Tel: ${inv.company.phone}`, inv.company.email]) {
      if (line) doc.text(line, textX, doc.y, { width: WIDTH - (textX - MARGIN) });
    }
    y = Math.max(doc.y, y + 72) + 10;
  }

  doc.font('Helvetica-Bold').fontSize(16).fillColor(BURGUNDY).text(vat ? 'VAT INVOICE' : 'INVOICE', MARGIN, y, { width: WIDTH, align: 'center' });
  y = doc.y + 10;
  doc.fillColor('black');

  const half = WIDTH / 2 - 10;
  const left = pairs(doc, MARGIN, y, half, [
    ['Customer', inv.customer.name],
    ['Address', inv.customer.address],
    ['City', inv.customer.city || inv.customer.area],
    ...(vat ? ([['Reg No', inv.customer.regNo], ['TPIN', inv.customer.tpin]] as [string, string][]) : []),
  ]);
  const right = pairs(doc, MARGIN + half + 20, y, half, [
    ['Invoice No', inv.number],
    ['Date', dateText(inv.invoiceDate)],
    ['Due date', dateText(inv.dueDate)],
    ['LPO No', inv.lpoNo],
    ['Vehicle No', inv.vehiclePlate ?? ''],
    ['Delivery note', inv.deliveryNoteNo ?? ''],
    ['Salesman', inv.salesman ?? ''],
  ]);
  doc.x = MARGIN;
  doc.y = Math.max(left, right) + 10;

  table(
    doc,
    [
      { title: 'S.No', width: 40, align: 'center' },
      { title: 'Qty', width: 90 },
      { title: 'Description', width: WIDTH - 40 - 90 - 90 - 95 },
      { title: 'Unit price (ctn)', width: 90, align: 'right' },
      { title: 'Amount', width: 95, align: 'right' },
    ],
    inv.lines.map((l, i) => [String(i + 1), l.quantity, l.description, money(l.unitPrice), money(l.amount)]),
  );

  const totals: [string, number][] = vat
    ? [['Sub Total', inv.subtotal], [`VAT ${inv.vatRate}%`, inv.vat], ['Total MWK', inv.total]]
    : [['Total MWK', inv.total]];
  doc.moveDown(0.5);
  for (const [label, value] of totals) {
    const top = doc.y;
    const bold = label.startsWith('Total');
    doc.font(bold ? 'Helvetica-Bold' : 'Helvetica').fontSize(bold ? 12 : 10);
    doc.text(label, MARGIN + WIDTH - 250, top, { width: 150, align: 'right' });
    doc.text(money(value), MARGIN + WIDTH - 95, top, { width: 91, align: 'right' });
    doc.x = MARGIN;
    doc.y = top + (bold ? 18 : 15);
  }

  doc.moveDown(2);
  doc.font('Helvetica').fontSize(10).fillColor('black');
  doc.text('Received in good order and condition:', MARGIN, doc.y, { width: WIDTH });
  doc.moveDown(2);
  doc.text('Name: ____________________    Signature: ____________________    Date: __________', { width: WIDTH });
  doc.moveDown(2);
  doc.font('Helvetica-Oblique').fontSize(9).fillColor(GREY).text('No claims will be entertained after delivery.', { width: WIDTH, align: 'center' });
  if (vat && inv.company.tpin) doc.text(`TPIN: ${inv.company.tpin}`, { width: WIDTH, align: 'center' });
  return toBuffer(doc);
}

export type VehicleDocPrint = {
  title: 'DELIVERY NOTE' | 'GATE PASS';
  number: string;
  date: string;
  facilityName: string;
  vehiclePlate: string | null;
  driverName: string | null;
  lines: { code: string; description: string; quantity: string }[];
};

// Delivery note and gate pass: the warehouse, the vehicle and the products only, never company or customer names (client, 16 Oct).
export function vehicleDocPdf(d: VehicleDocPrint): Promise<Buffer> {
  const doc = new PDFDocument({ size: 'A4', margin: MARGIN, info: { Title: `${d.title} ${d.number}` } });
  doc.font('Helvetica-Bold').fontSize(18).fillColor(BURGUNDY).text(d.title, MARGIN, MARGIN, { width: WIDTH, align: 'center' });
  doc.fillColor('black');
  const y = doc.y + 12;
  const half = WIDTH / 2 - 10;
  const left = pairs(doc, MARGIN, y, half, [
    ['Number', d.number],
    ['Date', dateText(d.date)],
    ['Warehouse', d.facilityName],
  ]);
  const right = pairs(doc, MARGIN + half + 20, y, half, [
    ['Vehicle No', d.vehiclePlate ?? ''],
    ['Driver', d.driverName ?? ''],
  ]);
  doc.x = MARGIN;
  doc.y = Math.max(left, right) + 10;
  table(
    doc,
    [
      { title: 'S.No', width: 40, align: 'center' },
      { title: 'Code', width: 80 },
      { title: 'Product', width: WIDTH - 40 - 80 - 120 },
      { title: 'Quantity', width: 120, align: 'right' },
    ],
    d.lines.map((l, i) => [String(i + 1), l.code, l.description, l.quantity]),
  );
  doc.moveDown(3);
  doc.font('Helvetica').fontSize(10);
  const signers = d.title === 'GATE PASS' ? ['Warehouse', 'Security'] : ['Warehouse', 'Driver'];
  doc.text(signers.map((s) => `${s}: ____________________`).join('        '), MARGIN, doc.y, { width: WIDTH });
  return toBuffer(doc);
}

export type StatementPrint = {
  company: { name: string; address: string | null; phone: string | null; email: string | null; tpin: string | null; logo: string | null };
  customer: { name: string; address: string | null; city: string | null; area: string | null };
  from: string | null;
  to: string | null;
  opening: number;
  rows: { date: string; document: string; what: string; charged: number; paid: number; balance: number }[];
  closing: number;
  overdue: number;
};

// A statement of account for one customer and one company: the ledger card, to print or send by WhatsApp.
export function statementPdf(s: StatementPrint): Promise<Buffer> {
  const doc = new PDFDocument({ size: 'A4', margin: MARGIN, info: { Title: `Statement ${s.customer.name}` } });
  let y = MARGIN;
  let textX = MARGIN;
  if (s.company.logo) {
    try {
      doc.image(s.company.logo, MARGIN, y, { fit: [90, 70] });
      textX = MARGIN + 100;
    } catch {
      // A logo that cannot be read is left out rather than stopping the statement.
    }
  }
  doc.font('Helvetica-Bold').fontSize(18).fillColor(BURGUNDY).text(s.company.name, textX, y, { width: WIDTH - (textX - MARGIN) });
  doc.font('Helvetica').fontSize(10).fillColor('black');
  for (const line of [s.company.address, s.company.phone && `Tel: ${s.company.phone}`, s.company.email]) {
    if (line) doc.text(line, textX, doc.y, { width: WIDTH - (textX - MARGIN) });
  }
  y = Math.max(doc.y, y + 72) + 10;

  doc.font('Helvetica-Bold').fontSize(16).fillColor(BURGUNDY).text('STATEMENT OF ACCOUNT', MARGIN, y, { width: WIDTH, align: 'center' });
  y = doc.y + 10;
  doc.fillColor('black');
  const period = s.from || s.to ? `${s.from ? dateText(s.from) : 'Start'} to ${s.to ? dateText(s.to) : 'today'}` : 'All';
  const half = WIDTH / 2 - 10;
  const left = pairs(doc, MARGIN, y, half, [
    ['Customer', s.customer.name],
    ['Address', s.customer.address ?? ''],
    ['City', s.customer.city || s.customer.area || ''],
  ]);
  const right = pairs(doc, MARGIN + half + 20, y, half, [
    ['Period', period],
    ['Printed', dateText(new Date().toISOString())],
  ]);
  doc.x = MARGIN;
  doc.y = Math.max(left, right) + 10;

  const blank = (n: number) => (n ? money(n) : '');
  table(
    doc,
    [
      { title: 'Date', width: 62 },
      { title: 'Document', width: 92 },
      { title: 'Details', width: WIDTH - 62 - 92 - 75 - 75 - 85 },
      { title: 'Charged', width: 75, align: 'right' },
      { title: 'Paid or less', width: 75, align: 'right' },
      { title: 'Balance', width: 85, align: 'right' },
    ],
    [
      [s.from ? dateText(s.from) : '', '', 'Balance brought forward', '', '', money(s.opening)],
      ...s.rows.map((r) => [dateText(r.date), r.document, r.what, blank(r.charged), blank(r.paid), money(r.balance)]),
    ],
  );

  doc.moveDown(0.5);
  const totals: [string, number][] = [['Amount due MWK', s.closing], ...(s.overdue > 0 ? ([['Of which late', s.overdue]] as [string, number][]) : [])];
  for (const [label, value] of totals) {
    const top = doc.y;
    const bold = label.startsWith('Amount');
    doc.font(bold ? 'Helvetica-Bold' : 'Helvetica').fontSize(bold ? 12 : 10);
    doc.text(label, MARGIN + WIDTH - 260, top, { width: 160, align: 'right' });
    doc.text(money(value), MARGIN + WIDTH - 95, top, { width: 91, align: 'right' });
    doc.x = MARGIN;
    doc.y = top + (bold ? 18 : 15);
  }
  doc.moveDown(2);
  doc.font('Helvetica-Oblique').fontSize(9).fillColor(GREY);
  doc.text('A balance below 0 is money on account in your favour.', MARGIN, doc.y, { width: WIDTH, align: 'center' });
  if (s.company.tpin) doc.text(`TPIN: ${s.company.tpin}`, { width: WIDTH, align: 'center' });
  return toBuffer(doc);
}
