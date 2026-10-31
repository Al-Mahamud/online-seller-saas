import { defineConfig } from "@playwright/test";

/** A first visit on the target phone and connection (e2e/performance): `npm run perf`. */
export default defineConfig({
  testDir: "e2e/performance",
  testMatch: "*.perf.ts",
  // One at a time: the timings mean nothing while tests compete for the machine.
  workers: 1,
  timeout: 90_000,
  reporter: [["list"]],
  use: { baseURL: "http://localhost:4174", locale: "bn-BD", timezoneId: "Asia/Dhaka" },
  webServer: [
    {
      command: "./mvnw -B -q spring-boot:test-run",
      cwd: "../backend",
      url: "http://localhost:8080/healthz",
      timeout: 300_000,
      reuseExistingServer: true,
    },
    {
      command: "node scripts/serve-built.mjs",
      url: "http://localhost:4174",
      reuseExistingServer: true,
    },
  ],
});
