import { useRouter } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, NetworkError, requestIdOf } from "@/api/errors";
import { errorText } from "@/api/query";
import { Button } from "@/components/ui/button";
import { reportError } from "@/lib/reporting";

/** A page could not load, most often because the server cannot be reached. */
export function RouteError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  const router = useRouter();
  const id = requestIdOf(error);
  useEffect(() => {
    if (!(error instanceof ApiError) && !(error instanceof NetworkError)) {
      reportError(error);
    }
  }, [error]);
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 p-6 text-center">
      <p role="alert">{errorText(error)}</p>
      {id && <p className="text-sm text-muted-foreground select-all">{t("error.code", { code: id })}</p>}
      <Button onClick={() => void router.invalidate()}>{t("page.retry")}</Button>
    </div>
  );
}
