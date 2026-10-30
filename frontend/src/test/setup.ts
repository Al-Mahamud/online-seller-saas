import "@testing-library/jest-dom/vitest";
import "@/i18n";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom has no scrolling; the router's scroll restoration calls it.
window.scrollTo = () => {};

afterEach(() => {
  cleanup();
});
