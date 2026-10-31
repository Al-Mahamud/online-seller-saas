import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { type Business, businessQuery, initials } from "@/api/business";
import { createQueryClient } from "@/api/query";
import { meQuery } from "@/api/session";
import { setLanguage } from "@/i18n";
import { createAppRouter } from "@/router";

const me = {
  id: "01a18877-ca42-75fd-abc5-98a57110a442",
  userId: "test_seller",
  name: "Test Seller",
  phone: "+8801712345678",
  email: null,
  phoneVerified: true,
  emailVerified: false,
  locale: "bn",
  platformAdmin: false,
  twoFactorEnabled: false,
};

const business: Business = {
  id: "01a18877-ca42-75fd-abc5-98a57110a443",
  name: "রহিমার কুর্তি হাউস",
  phone: null,
  address: null,
  logo: null,
  role: "OWNER",
  deletionDueAt: null,
};

async function renderApp(path = "/", account: typeof me | null = me) {
  const queryClient = createQueryClient();
  queryClient.setQueryData(meQuery.queryKey, account);
  queryClient.setQueryData(businessQuery.queryKey, business);
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
  vi.unstubAllGlobals();
  await setLanguage("bn");
});

describe("initials", () => {
  it("takes the first letter of two words, keeping Bangla letters whole", () => {
    expect(initials("Rahima's Kurti House")).toBe("RK");
    expect(initials("রহিমার কুর্তি হাউস")).toBe("রকু");
    expect(initials("ক্ষুদ্র দোকান")).toBe("ক্ষুদো");
    expect(initials("Mojud")).toBe("M");
  });
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
    const saved: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        // biome-ignore lint/suspicious/noDocumentCookie: stands in for the server's Set-Cookie.
        document.cookie = "XSRF-TOKEN=token; path=/";
        if (request.method === "PATCH") {
          saved.push(await request.clone().json());
        }
        return new URL(request.url).pathname === "/api/v1/me"
          ? new Response(JSON.stringify(me), { headers: { "Content-Type": "application/json" } })
          : new Response(null, { status: 404 });
      }),
    );
    await renderApp();
    const user = userEvent.setup();

    await user.click(screen.getAllByRole("button", { name: "English" })[0] as HTMLElement);

    expect(await screen.findByRole("heading", { name: "Today" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
    await vi.waitFor(() => expect(saved).toEqual([{ locale: "en" }]));
    await user.click(screen.getAllByRole("button", { name: "বাংলা" })[0] as HTMLElement);
    expect(await screen.findByRole("heading", { name: "আজ" })).toBeInTheDocument();
  });

  it("shows the business in the switcher", async () => {
    await renderApp();

    expect(screen.getAllByRole("button", { name: /রহিমার কুর্তি হাউস/ })).toHaveLength(2);
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

  it("sends someone signed out to sign-in, remembering the page", async () => {
    const router = await renderApp("/products", null);

    expect(router.state.location.pathname).toBe("/sign-in");
    expect(router.state.location.search).toEqual({ redirect: "/products" });
    expect(await screen.findByRole("heading", { name: "আবার স্বাগতম" })).toBeInTheDocument();
  });

  it("says when a page does not exist", async () => {
    await renderApp("/nowhere");

    expect(await screen.findByText("এই পাতাটি পাওয়া যায়নি।")).toBeInTheDocument();
  });
});
