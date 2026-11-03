import { createFileRoute, redirect } from "@tanstack/react-router";

/** The old address, which the low-stock emails link to: the product list with the running-low window open. */
export const Route = createFileRoute("/_app/products/low-stock")({
  beforeLoad: () => {
    throw redirect({ to: "/products", search: { lowStock: true }, replace: true });
  },
});
