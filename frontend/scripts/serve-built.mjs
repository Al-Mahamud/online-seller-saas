// The built app served the way Caddy serves it, compressed, with /api and /dev sent to the backend:
// what a phone really downloads, for `npm run perf`. `vite preview` sends files uncompressed.
import { createReadStream, existsSync, statSync } from "node:fs";
import http from "node:http";
import path from "node:path";
import { createGzip } from "node:zlib";

const dist = path.resolve(import.meta.dirname, "../dist");
const backend = new URL(process.env.MOJUD_API ?? "http://localhost:8080");
const port = Number(process.env.PORT ?? 4174);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".webmanifest": "application/manifest+json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".woff2": "font/woff2",
};
const COMPRESSIBLE = new Set([".html", ".js", ".css", ".json", ".webmanifest", ".svg"]);

http
  .createServer((request, response) => {
    const url = new URL(request.url, "http://localhost");
    if (url.pathname.startsWith("/api") || url.pathname.startsWith("/dev")) {
      const upstream = http.request(
        {
          host: backend.hostname,
          port: backend.port,
          path: request.url,
          method: request.method,
          headers: request.headers,
        },
        (answer) => {
          response.writeHead(answer.statusCode, answer.headers);
          answer.pipe(response);
        },
      );
      upstream.on("error", () => response.writeHead(502).end());
      request.pipe(upstream);
      return;
    }
    const wanted = path.join(dist, url.pathname);
    const file =
      wanted.startsWith(dist) && existsSync(wanted) && statSync(wanted).isFile()
        ? wanted
        : path.join(dist, "index.html");
    const extension = path.extname(file);
    const headers = {
      "Content-Type": TYPES[extension] ?? "application/octet-stream",
      "Cache-Control": url.pathname.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache",
    };
    if (COMPRESSIBLE.has(extension)) {
      response.writeHead(200, { ...headers, "Content-Encoding": "gzip" });
      createReadStream(file).pipe(createGzip()).pipe(response);
    } else {
      response.writeHead(200, headers);
      createReadStream(file).pipe(response);
    }
  })
  .listen(port, () => console.log(`Serving dist on http://localhost:${port}`));
