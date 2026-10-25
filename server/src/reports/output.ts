import ExcelJS from 'exceljs';
import PDFDocument from 'pdfkit';
import { dateText, money, toBuffer } from '../print/pdf.js';
import type { Column, Row } from './engine.js';

// A report ready to print or save: its title, a line saying what was picked, the table and the totals.
export type ReportOutput = {
  title: string;
  picked: string;
  columns: Column[];
  rows: Row[];
  totals: Record<string, number>;
  truncated: boolean;
};

const isNumber = (c: Column) => c.kind === 'money' || c.kind === 'int' || c.kind === 'percent';

function cellText(c: Column, v: unknown): string {
  if (v == null || v === '') return '';
  if (c.kind === 'money' || c.kind === 'int') return money(Number(v));
  if (c.kind === 'percent') return `${v}%`;
  if (c.kind === 'date') return dateText(String(v));
  return String(v);
}

export async function reportXlsx(r: ReportOutput): Promise<Buffer> {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet(r.title.slice(0, 31));
  ws.addRow([r.title]).font = { bold: true, size: 14 };
  ws.addRow([r.picked]);
  ws.addRow([]);
  const head = ws.addRow(r.columns.map((c) => c.title));
  head.font = { bold: true };
  for (const row of r.rows) {
    ws.addRow(
      r.columns.map((c) => {
        const v = row[c.key];
        if (v == null) return null;
        if (isNumber(c)) return Number(v);
        if (c.kind === 'date') return dateText(String(v));
        return String(v);
      }),
    );
  }
  if (Object.keys(r.totals).length) {
    const total = ws.addRow(r.columns.map((c, i) => (c.key in r.totals ? r.totals[c.key] : i === 0 ? 'Total' : null)));
    total.font = { bold: true };
  }
  if (r.truncated) ws.addRow(['Only the first rows are shown. Pick fewer dates to see the rest.']);
  r.columns.forEach((c, i) => {
    const column = ws.getColumn(i + 1);
    if (c.kind === 'money' || c.kind === 'int') column.numFmt = '#,##0';
    column.width = isNumber(c) || c.kind === 'date' ? 14 : c.kind === 'qty' ? 16 : 24;
  });
  return Buffer.from(await wb.xlsx.writeBuffer());
}

const MARGIN = 30;
const BURGUNDY = '#7a1f3d';
const weight = (c: Column) => (c.kind === 'text' ? 2.2 : c.kind === 'qty' ? 1.4 : c.kind === 'date' ? 1.1 : c.kind === 'percent' ? 0.8 : 1.2);

// A landscape A4 table, with the header repeated on each page.
export function reportPdf(r: ReportOutput): Promise<Buffer> {
  const doc = new PDFDocument({ size: 'A4', layout: 'landscape', margin: MARGIN, info: { Title: r.title } });
  const width = doc.page.width - MARGIN * 2;
  const sum = r.columns.reduce((t, c) => t + weight(c), 0);
  const widths = r.columns.map((c) => (weight(c) / sum) * width);
  const fontSize = r.columns.length > 9 ? 7.5 : 8.5;

  doc.font('Helvetica-Bold').fontSize(16).fillColor(BURGUNDY).text(r.title, MARGIN, MARGIN, { width });
  doc.font('Helvetica').fontSize(9).fillColor('#444444').text(`${r.picked} · Printed ${dateText(new Date().toISOString())}`, { width });
  doc.fillColor('black');
  doc.moveDown(0.6);

  const drawRow = (cells: string[], bold: boolean, header = false) => {
    doc.font(bold ? 'Helvetica-Bold' : 'Helvetica').fontSize(fontSize);
    const height = Math.max(...cells.map((c, i) => doc.heightOfString(c || ' ', { width: widths[i]! - 6 }))) + 6;
    if (doc.y + height > doc.page.height - MARGIN) {
      doc.addPage();
      doc.y = MARGIN;
      if (!header) drawRow(r.columns.map((c) => c.title), true, true);
      doc.font(bold ? 'Helvetica-Bold' : 'Helvetica').fontSize(fontSize);
    }
    const top = doc.y;
    let x = MARGIN;
    cells.forEach((c, i) => {
      const col = r.columns[i]!;
      doc.text(c, x + 3, top + 3, { width: widths[i]! - 6, align: isNumber(col) || col.kind === 'qty' ? 'right' : 'left' });
      x += widths[i]!;
    });
    doc
      .moveTo(MARGIN, top + height)
      .lineTo(MARGIN + width, top + height)
      .lineWidth(header ? 1 : 0.4)
      .strokeColor(header ? BURGUNDY : '#bbbbbb')
      .stroke();
    doc.x = MARGIN;
    doc.y = top + height;
  };

  drawRow(r.columns.map((c) => c.title), true, true);
  for (const row of r.rows) drawRow(r.columns.map((c) => cellText(c, row[c.key])), false);
  if (Object.keys(r.totals).length) {
    drawRow(r.columns.map((c, i) => (c.key in r.totals ? cellText(c, r.totals[c.key]) : i === 0 ? 'Total' : '')), true);
  }
  if (r.rows.length === 0) doc.moveDown().font('Helvetica').fontSize(10).text('Nothing for what was picked.', MARGIN);
  if (r.truncated) doc.moveDown().font('Helvetica').fontSize(9).text('Only the first rows are shown. Pick fewer dates to see the rest.', MARGIN);
  return toBuffer(doc);
}
