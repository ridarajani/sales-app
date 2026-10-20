import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scryptAsync = promisify(scrypt) as (pin: string, salt: Buffer, length: number) => Promise<Buffer>;
const KEY_LENGTH = 32;

// Stored as scrypt$<salt>$<hash>, both base64. Async so a login does not block other requests.
export async function hashPin(pin: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await scryptAsync(pin, salt, KEY_LENGTH);
  return `scrypt$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export async function verifyPin(pin: string, stored: string): Promise<boolean> {
  const [kind, saltB64, hashB64] = stored.split('$');
  if (kind !== 'scrypt' || !saltB64 || !hashB64) return false;
  const expected = Buffer.from(hashB64, 'base64');
  const actual = await scryptAsync(pin, Buffer.from(saltB64, 'base64'), expected.length);
  return timingSafeEqual(actual, expected);
}
