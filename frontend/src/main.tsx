import "@fontsource/hind-siliguri/400.css";
import "@fontsource/hind-siliguri/500.css";
import "@fontsource/hind-siliguri/600.css";
import "@fontsource/hind-siliguri/700.css";
import "./styles.css";
import "./i18n";
import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "./api/query";
import { signedOut } from "./api/session";
import { safeRedirect } from "./lib/redirect";
import { createAppRouter } from "./router";

const queryClient = createQueryClient({
  // The session ended while the app was open: to sign-in, and back here afterwards.
  onSignedOut: () => {
    const redirect = router.state.location.href;
    signedOut(queryClient);
    void router.navigate({ to: "/sign-in", search: { redirect: safeRedirect(redirect) }, replace: true });
  },
});
const router = createAppRouter(queryClient);

const root = document.getElementById("root");
if (!root) {
  throw new Error("index.html has no #root");
}
createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
