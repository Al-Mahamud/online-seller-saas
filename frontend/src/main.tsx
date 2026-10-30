import "@fontsource/hind-siliguri/400.css";
import "@fontsource/hind-siliguri/500.css";
import "@fontsource/hind-siliguri/600.css";
import "@fontsource/hind-siliguri/700.css";
import "./styles.css";
import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { toast } from "sonner";
import { businessQuery, resetBusinessData, trackExpectedBusiness, whenBusinessChangesUnderneath } from "./api/business";
import { createQueryClient } from "./api/query";
import { signedOut } from "./api/session";
import { showPlanLimit } from "./components/layout/planLimit";
import i18n from "./i18n";
import { safeRedirect } from "./lib/redirect";
import { createAppRouter } from "./router";

const queryClient = createQueryClient({
  // The session ended while the app was open: to sign-in, and back here afterwards.
  onSignedOut: () => {
    // Several requests can fail together; only the first decides where to return to.
    const { href, pathname } = router.state.location;
    signedOut(queryClient);
    if (pathname !== "/sign-in") {
      void router.navigate({ to: "/sign-in", search: { redirect: safeRedirect(href) }, replace: true });
    }
  },
  onAccessChanged: () => void queryClient.invalidateQueries(businessQuery),
  onPlanLimit: showPlanLimit,
  onSubscriptionRefused: () => void queryClient.invalidateQueries({ queryKey: ["subscription-status"] }),
});
const router = createAppRouter(queryClient);

trackExpectedBusiness(queryClient);
whenBusinessChangesUnderneath((lost) => {
  // Requests running together can each notice, and it's one piece of news.
  toast.warning(i18n.t("business.lost", { name: lost.name }), { id: "business-lost" });
  resetBusinessData(queryClient);
  void router.navigate({ to: "/", replace: true }).then(() => router.invalidate());
});

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
