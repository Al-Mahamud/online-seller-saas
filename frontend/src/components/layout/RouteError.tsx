import { useRouter } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { errorText } from "@/api/query";
import { Button } from "@/components/ui/button";

/** A page could not load, most often because the server cannot be reached. */
export function RouteError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  const router = useRouter();
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 p-6 text-center">
      <p role="alert">{errorText(error)}</p>
      <Button onClick={() => void router.invalidate()}>{t("page.retry")}</Button>
    </div>
  );
}
