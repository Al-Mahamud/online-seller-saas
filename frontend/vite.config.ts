import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

// The backend during development: ./mvnw spring-boot:test-run (or MojudApplication) on port 8080.
// API calls go through this dev server, so the app and the API share one origin, as on the
// server behind Caddy (frontend-plan.md, "How the app talks to the API").
const backend = process.env.MOJUD_API ?? "http://localhost:8080";

/**
 * The Bangla texts are needed before the first render, but the browser only learns that once the main
 * script has run. Naming them in the page lets it fetch them alongside, one round of waiting less.
 */
function preloadTexts(): Plugin {
  return {
    name: "preload-texts",
    apply: "build",
    transformIndexHtml: {
      order: "post",
      handler: (_html, context) => {
        const texts = Object.values(context.bundle ?? {}).find(
          (file) => file.type === "chunk" && file.facadeModuleId?.endsWith("i18n/bn.json"),
        );
        return texts
          ? [{ tag: "link", attrs: { rel: "modulepreload", href: `/${texts.fileName}` }, injectTo: "head" }]
          : [];
      },
    },
  };
}

export default defineConfig({
  plugins: [tanstackRouter({ target: "react", autoCodeSplitting: true }), react(), tailwindcss(), preloadTexts()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "src") },
  },
  // Lists which files each page needs, for the size check (scripts/check-size.mjs).
  build: { manifest: true },
  server: {
    port: 5173,
    proxy: {
      "/api": backend,
      // Test-only sign-up codes (spring-boot:test-run).
      "/dev": backend,
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
