// The first screen's JavaScript, compressed, must stay within 250 KB (frontend-plan.md,
// "Performance budget"): what index.html loads at once, not the screens that load when opened.
// Run after `npm run build`.
import { readFileSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const BUDGET_KB = 250;
const dist = path.resolve(import.meta.dirname, "../dist");
const html = readFileSync(path.join(dist, "index.html"), "utf8");

const scripts = [
  ...html.matchAll(/<script[^>]+src="([^"]+)"/g),
  ...html.matchAll(/<link[^>]+rel="modulepreload"[^>]+href="([^"]+)"/g),
].map((match) => match[1]);

let total = 0;
for (const src of new Set(scripts)) {
  const size = gzipSync(readFileSync(path.join(dist, src))).length;
  total += size;
  console.log(`${(size / 1024).toFixed(1).padStart(7)} KB  ${src}`);
}
console.log(`${(total / 1024).toFixed(1).padStart(7)} KB  first screen, compressed (budget ${BUDGET_KB} KB)`);

if (total > BUDGET_KB * 1024) {
  console.error(`Over budget by ${((total - BUDGET_KB * 1024) / 1024).toFixed(1)} KB`);
  process.exit(1);
}
