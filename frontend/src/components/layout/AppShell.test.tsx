import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { createQueryClient } from "@/api/query";
import { setLanguage } from "@/i18n";
import { createAppRouter } from "@/router";

async function renderApp(path = "/") {
  const queryClient = createQueryClient();
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }));
  await router.load();
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

afterEach(async () => {
  await setLanguage("bn");
});

describe("app shell", () => {
  it("opens in Bangla with the four tabs", async () => {
    await renderApp();

    const tabBar = screen.getAllByRole("navigation", { name: "প্রধান" })[1] as HTMLElement;
    const tabs = within(tabBar)
      .getAllByRole("link")
      .map((link) => link.textContent);
    expect(tabs).toEqual(["আজ", "অর্ডার", "পণ্য", "আরও"]);
    expect(await screen.findByRole("heading", { name: "আজ" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("bn");
  });

  it("switches to English and back", async () => {
    await renderApp();
    const user = userEvent.setup();

    await user.click(screen.getAllByRole("button", { name: "ইংরেজিতে দেখুন" })[0] as HTMLElement);

    expect(await screen.findByRole("heading", { name: "Today" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
    await user.click(screen.getAllByRole("button", { name: "Show in Bangla" })[0] as HTMLElement);
    expect(await screen.findByRole("heading", { name: "আজ" })).toBeInTheDocument();
  });

  it("marks the open tab", async () => {
    await renderApp("/products");

    const tabBar = screen.getAllByRole("navigation", { name: "প্রধান" })[1] as HTMLElement;
    expect(within(tabBar).getByRole("link", { name: "পণ্য" })).toHaveAttribute("aria-current", "page");
    expect(await screen.findByRole("heading", { name: "পণ্য" })).toBeInTheDocument();
  });

  it("links the + button to a new order", async () => {
    await renderApp();

    expect(screen.getByRole("link", { name: "নতুন অর্ডার" })).toHaveAttribute("href", "/orders/new");
  });

  it("says when a page does not exist", async () => {
    await renderApp("/nowhere");

    expect(await screen.findByText("এই পাতাটি পাওয়া যায়নি।")).toBeInTheDocument();
  });
});
