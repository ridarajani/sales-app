import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { invalid } from './lists/common.js';

const TYPES = [
  { ext: 'png', type: 'image/png', magic: [0x89, 0x50, 0x4e, 0x47] },
  { ext: 'jpg', type: 'image/jpeg', magic: [0xff, 0xd8, 0xff] },
];

// Saves a PNG or JPEG sent as base64 (with or without a data: prefix) and returns its file name.
export async function saveImage(dir: string, data: string, maxBytes: number, what: string): Promise<string> {
  const base64 = data.replace(/^data:image\/[a-z]+;base64,/, '');
  const bytes = Buffer.from(base64, 'base64');
  const kind = TYPES.find((t) => t.magic.every((b, i) => bytes[i] === b));
  if (!kind || bytes.length < 16) throw invalid(`The ${what} could not be read. Please try again.`);
  if (bytes.length > maxBytes) throw invalid(`The ${what} is too big.`);
  await mkdir(dir, { recursive: true });
  const name = `${randomUUID()}.${kind.ext}`;
  await writeFile(join(dir, name), bytes);
  return name;
}

export async function readImage(dir: string, name: string): Promise<{ bytes: Buffer; type: string } | null> {
  // Names are made by saveImage; anything else is refused so no other file can be read.
  if (!/^[0-9a-f-]{36}\.(png|jpg)$/.test(name)) return null;
  const path = join(dir, name);
  if (!existsSync(path)) return null;
  return { bytes: await readFile(path), type: name.endsWith('.png') ? 'image/png' : 'image/jpeg' };
}

// The company's logo for invoices, if one was put in the branding folder.
export function logoPath(dir: string, companyCode: string): string | null {
  for (const ext of ['png', 'jpg', 'jpeg']) {
    const path = join(dir, `${companyCode}.${ext}`);
    if (existsSync(path)) return path;
  }
  return null;
}
