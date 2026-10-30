import { createHmac } from "node:crypto";

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function decodeBase32(text: string): Buffer {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const character of text) {
    buffer = ((buffer << 5) | BASE32.indexOf(character)) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      bytes.push((buffer >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

/**
 * An authenticator app, for tests: the code for a base32 secret (RFC 6238), `stepsAhead` 30-second steps
 * from now. The server accepts one step either side, and each step only once and in order: a test using
 * several codes takes -1, then 0, then +1.
 */
export function totp(secret: string, stepsAhead = 0): string {
  const step = Math.floor(Date.now() / 30_000) + stepsAhead;
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac("sha1", decodeBase32(secret)).update(counter).digest();
  const offset = (hash[19] as number) & 0xf;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}
