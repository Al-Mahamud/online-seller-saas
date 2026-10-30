// The JavaScript a first visit downloads, compressed, must stay within 250 KB (frontend-plan.md,
// "Performance budget"): what index.html loads at once, plus the heaviest page opened first, such
// as sign-in. Other pages load their own code when opened. Run after `npm run build`.
import { readFileSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const BUDGET_KB = 250;
const dist = path.resolve(import.meta.dirname, "../dist");
const manifest = JSON.parse(readFileSync(path.join(dist, ".vite/manifest.json"), "utf8"));

/** A chunk and everything it imports at once. */
function closure(key, files = new Set()) {
  const chunk = manifest[key];
  if (chunk.file.endsWith(".js") && !files.has(chunk.file)) {
    files.add(chunk.file);
    for (const imported of chunk.imports ?? []) {
      closure(imported, files);
    }
  }
  return files;
}

const sizes = new Map();
function compressed(file) {
  if (!sizes.has(file)) {
    sizes.set(file, gzipSync(readFileSync(path.join(dist, file))).length);
  }
  return sizes.get(file);
}
const total = (files) => [...files].reduce((sum, file) => sum + compressed(file), 0);
const kb = (bytes) => `${(bytes / 1024).toFixed(1).padStart(7)} KB`;

const entry = closure("index.html");
console.log(`${kb(total(entry))}  loaded by every page`);

// A page under a layout route (_app, _auth) also needs the layout's own code.
const layoutOf = (key) => {
  const layout = key.match(/^src\/routes\/(_\w+)\//)?.[1];
  return layout && Object.keys(manifest).find((candidate) => candidate.startsWith(`src/routes/${layout}.tsx`));
};

let heaviest = { name: "(none)", bytes: total(entry) };
for (const [key, chunk] of Object.entries(manifest)) {
  if (chunk.isDynamicEntry && key.startsWith("src/routes/")) {
    const files = closure(key, new Set(entry));
    const layout = layoutOf(key);
    if (layout) {
      closure(layout, files);
    }
    const bytes = total(files);
    console.log(`${kb(bytes - total(entry))}  ${key.replace("?tsr-split=component", "")}`);
    if (bytes > heaviest.bytes) {
      heaviest = { name: key, bytes };
    }
  }
}
console.log(`${kb(heaviest.bytes)}  heaviest first visit (budget ${BUDGET_KB} KB)`);

if (heaviest.bytes > BUDGET_KB * 1024) {
  console.error(`Over budget by ${((heaviest.bytes - BUDGET_KB * 1024) / 1024).toFixed(1)} KB`);
  process.exit(1);
}
