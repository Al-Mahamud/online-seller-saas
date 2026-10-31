import type { ErrorEvent } from "@sentry/browser";
import { describe, expect, it } from "vitest";
import { scrubEvent } from "./reporting";

const event = (partial: Partial<ErrorEvent>) => scrubEvent({ type: undefined, ...partial } as ErrorEvent);

describe("scrubEvent", () => {
  it("keeps only the page from an address", () => {
    expect(event({ request: { url: "https://mojud.app/orders?q=01712345678" } }).request?.url).toBe(
      "https://mojud.app/orders",
    );
    expect(event({ request: { url: "https://mojud.app/invites#secret-token" } }).request?.url).toBe(
      "https://mojud.app/invites",
    );
  });

  it("takes phone numbers out of messages, written any way", () => {
    const scrubbed = event({
      message: "Couldn't find +8801712345678",
      exception: { values: [{ type: "Error", value: "No customer 01712-345678 here" }] },
    });
    expect(scrubbed.message).toBe("Couldn't find [number]");
    expect(scrubbed.exception?.values?.[0]?.value).toBe("No customer [number] here");
  });

  it("drops the referrer, which is an address too", () => {
    expect(
      event({ request: { headers: { Referer: "https://mojud.app/x?q=1", Accept: "*/*" } } }).request?.headers,
    ).toEqual({
      Accept: "*/*",
    });
  });

  it("leaves ordinary messages alone", () => {
    expect(event({ message: "Cannot read properties of undefined" }).message).toBe(
      "Cannot read properties of undefined",
    );
  });
});
