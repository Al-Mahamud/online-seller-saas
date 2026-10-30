import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";

/** Loads the next page as the list's end comes into view; the button is for when that can't work. */
export function LoadMore({
  query,
}: {
  query: { hasNextPage: boolean; isFetchingNextPage: boolean; fetchNextPage: () => unknown };
}) {
  const { t } = useTranslation();
  const end = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query;
  useEffect(() => {
    const element = end.current;
    if (!element || !hasNextPage) {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
        fetchNextPage();
      }
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);
  if (!hasNextPage) {
    return null;
  }
  return (
    <div ref={end} className="flex justify-center py-2">
      <Button variant="outline" disabled={isFetchingNextPage} onClick={() => fetchNextPage()}>
        {t("audit.more")}
      </Button>
    </div>
  );
}
