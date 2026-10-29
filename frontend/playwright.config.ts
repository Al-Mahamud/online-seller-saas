import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests (frontend-plan.md, F0b): the built app in a real browser, at phone and laptop
 * sizes, against the real backend with its throwaway database (spring-boot:test-run, which also
 * serves the test-only /dev/otp). Nothing the API decides is mocked.
 *
 * Locally, a backend and preview already running on 8080 and 4173 are reused; it must be the test
 * backend (TestMojudApplication), not MojudApplication.
 */
const ci = !!process.env.CI;

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  reporter: ci ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: "http://localhost:4173",
    locale: "bn-BD",
    timezoneId: "Asia/Dhaka",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "phone", use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: "laptop", use: { viewport: { width: 1280, height: 800 } } },
  ],
  webServer: [
    {
      command: "./mvnw -B -q spring-boot:test-run",
      cwd: "../backend",
      url: "http://localhost:8080/healthz",
      timeout: 300_000,
      reuseExistingServer: !ci,
    },
    {
      // The production build, served with the same /api proxy as the dev server.
      command: "npm run build && npx vite preview --port 4173 --strictPort",
      url: "http://localhost:4173",
      timeout: 120_000,
      reuseExistingServer: !ci,
    },
  ],
});
