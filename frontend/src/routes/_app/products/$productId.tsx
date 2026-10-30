import { createFileRoute } from "@tanstack/react-router";
import { ProductPage } from "@/features/catalog/ProductPage";

export const Route = createFileRoute("/_app/products/$productId")({
  component: ProductPage,
});
