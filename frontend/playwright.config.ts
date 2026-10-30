import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests (frontend-plan.md, F0b): the built app in a real browser, at phone and laptop
 * sizes, against the real backend with its throwaway database (spring-boot:test-run, which also
 * serves the test-only /dev/otp). Nothing the API decides is mocked.
 *
 * Locally, a backend and preview already running on 8080 and 4173 are reused; it must be the test
 * backend (TestMojudApplication), not MojudApplication.
 *
 * With E2E_BASE_URL set, the tests run against a server that is already up instead, such as the
 * deploy drill's (https://localhost, behind Caddy with its real headers); only tests that need no
 * test backend apply there (e2e/public.spec.ts).
 */
const ci = !!process.env.CI;
const external = process.env.E2E_BASE_URL;

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  // Password hashing is slow on purpose, and a test may sign up several people.
  timeout: 60_000,
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  reporter: ci ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: external ?? "http://localhost:4173",
    // The drill's Caddy uses its own local certificate authority for localhost.
    ignoreHTTPSErrors: !!external,
    locale: "bn-BD",
    timezoneId: "Asia/Dhaka",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "phone", use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: "laptop", use: { viewport: { width: 1280, height: 800 } } },
  ],
  webServer: external
    ? undefined
    : [
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
