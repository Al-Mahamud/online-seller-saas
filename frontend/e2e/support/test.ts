import { test as base, expect } from "@playwright/test";

export { expect };

/**
 * Every test fails on a script error or a blocked resource (a Content-Security-Policy too strict, in
 * the deploy drill). Browsers also log refused API calls, which are normal here: a wrong password, or
 * asking who is signed in while signed out.
 */
export const test = base.extend<{ consoleErrors: undefined; expectsNetworkErrors: boolean }>({
  /** Whether the test takes the connection away, when requests failing is the point. */
  expectsNetworkErrors: [false, { option: true }],
  consoleErrors: [
    async ({ page, expectsNetworkErrors }, use) => {
      const errors: string[] = [];
      page.on("console", (message) => {
        const refusedApiCall =
          message.text().startsWith("Failed to load resource") && message.location().url.includes("/api/");
        if (message.type() === "error" && !refusedApiCall) {
          errors.push(message.text());
        }
      });
      page.on("pageerror", (error) => errors.push(error.message));
      await use(undefined);
      expect(
        expectsNetworkErrors
          ? errors.filter((error) => !/ERR_(FAILED|INTERNET_DISCONNECTED)|NetworkError/.test(error))
          : errors,
      ).toEqual([]);
    },
    { auto: true },
  ],
});
