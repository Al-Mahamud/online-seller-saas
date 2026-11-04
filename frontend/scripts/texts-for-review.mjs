// Every text in the app, English beside Bangla, for reading through in a spreadsheet:
// `npm run texts:review` writes docs/bangla-review.csv. The admin screens' words are in
// src/features/admin/texts.ts. Change the Bangla in src/i18n/bn.json, or note it in the last column.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const read = (language) => JSON.parse(readFileSync(path.join(root, `src/i18n/${language}.json`), "utf8"));

function flatten(value, prefix = "") {
  return Object.entries(value).flatMap(([key, inner]) =>
    typeof inner === "object" ? flatten(inner, `${prefix}${key}.`) : [[`${prefix}${key}`, inner]],
  );
}

// Texts that load with one page: src/i18n/pages/<page>.<language>.json, under <page>.
const pagesDir = path.join(root, "src/i18n/pages");
const pages = readdirSync(pagesDir)
  .filter((file) => file.endsWith(".bn.json"))
  .map((file) => file.slice(0, -8));
const readPage = (page, language) => JSON.parse(readFileSync(path.join(pagesDir, `${page}.${language}.json`), "utf8"));
const all = (language) => [
  ...flatten(read(language)),
  ...pages.flatMap((page) => flatten(readPage(page, language), `${page}.`)),
];

const quote = (text) => `"${String(text).replaceAll('"', '""')}"`;
const english = new Map(all("en"));
const rows = all("bn").map(([key, bangla]) => [key, english.get(key), bangla, ""].map(quote).join(","));
// The mark at the start tells Excel this is UTF-8, so Bangla shows properly.
writeFileSync(
  path.join(root, "../docs/bangla-review.csv"),
  `﻿${["Where,English,Bangla,Your change"].concat(rows).join("\r\n")}\r\n`,
);
console.log(`${rows.length} texts in docs/bangla-review.csv`);
