import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The backend during development: ./mvnw spring-boot:test-run (or MojudApplication) on port 8080.
// API calls go through this dev server, so the app and the API share one origin, as on the
// server behind Caddy (frontend-plan.md, "How the app talks to the API").
const backend = process.env.MOJUD_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [tanstackRouter({ target: "react", autoCodeSplitting: true }), react(), tailwindcss()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "src") },
  },
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
