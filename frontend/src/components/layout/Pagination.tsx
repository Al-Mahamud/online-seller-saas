import { useNavigate } from "@tanstack/react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { type RefObject, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { PAGE_SIZES, type PageInfo, type Paging, pageNumbers, pagingOf } from "@/lib/paging";

const STEP_BUTTON = "h-12 gap-1 rounded-[10px] border-[1.5px] px-2.5 text-[15px] font-semibold md:h-11";

/**
 * Under a list: how much of it is showing, how many rows a page has, and the way between pages. It
 * says only the total when everything fits on one page, and nothing for an empty list, which has
 * its own message.
 *
 * @param result the server's answer; undefined while the first page loads
 * @param onChange where to go. `replace` is for going somewhere on the list's own account, not the
 *   person's: Back shouldn't return to a page that no longer exists, only to be sent away again.
 * @param scrollTo what to bring into view after changing page; the top of the screen when left out
 */
export function Pagination({
  result,
  onChange,
  scrollTo,
}: {
  result: PageInfo | undefined;
  onChange: (paging: Paging, options: { replace: boolean }) => void;
  scrollTo?: RefObject<HTMLElement | null>;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const size = result?.size;
  const totalPages = result?.totalPages ?? 0;
  const pastTheEnd = totalPages > 0 && (result?.page ?? 0) > totalPages;

  // The list got shorter than the page in the address: its last rows were settled, say, or removed.
  useEffect(() => {
    if (pastTheEnd && size !== undefined) {
      onChange(pagingOf(totalPages, size), { replace: true });
    }
  }, [pastTheEnd, totalPages, size, onChange]);

  if (!result || result.totalItems === 0) {
    return null;
  }
  const { page, totalItems } = result;
  const shown = (number: number) => format.number(number);

  if (totalPages <= 1) {
    return (
      <p role="status" className="px-1 text-[15px] font-semibold">
        {t("pagination.total", { total: shown(totalItems) })}
      </p>
    );
  }

  function go(to: number, withSize = result?.size ?? 0) {
    onChange(pagingOf(to, withSize), { replace: false });
    if (scrollTo?.current) {
      scrollTo.current.scrollIntoView({ block: "start" });
    } else {
      window.scrollTo({ top: 0 });
    }
  }

  return (
    <div className="flex flex-col gap-2.5 rounded-[14px] border bg-card px-3.5 py-3 md:flex-row md:flex-wrap md:items-center md:justify-between md:gap-x-5 md:px-5">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 md:justify-start">
        <p role="status" className="text-[15px] font-semibold">
          {t("pagination.range", {
            total: shown(totalItems),
            from: shown((page - 1) * result.size + 1),
            to: shown(Math.min(page * result.size, totalItems)),
          })}
        </p>
        <label className="flex items-center gap-2 text-sm md:text-[15px]">
          {t("pagination.perPage")}
          <select
            className="h-11 cursor-pointer rounded-[10px] border-[1.5px] border-input bg-card px-2 text-[15px]"
            value={result.size}
            onChange={(event) => go(1, Number(event.target.value))}
          >
            {PAGE_SIZES.map((choice) => (
              <option key={choice} value={choice}>
                {shown(choice)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <nav
        aria-label={t("pagination.label")}
        className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-center gap-2 md:flex md:gap-1"
      >
        <Button variant="outline" className={STEP_BUTTON} disabled={page <= 1} onClick={() => go(page - 1)}>
          <ChevronLeft aria-hidden="true" className="size-4.5" />
          {t("pagination.previous")}
        </Button>
        <span className="px-1.5 text-center font-bold md:hidden">
          {t("pagination.ofPages", { page: shown(page), last: shown(totalPages) })}
        </span>
        <ul className="hidden items-center gap-1 md:flex">
          {pageNumbers(page, totalPages).map((number, index) =>
            number === "gap" ? (
              // Gaps have nothing to tell them apart but where they are, and never move on their own.
              // biome-ignore lint/suspicious/noArrayIndexKey: see above.
              <li key={`gap-${index}`} aria-hidden="true" className="w-8 text-center text-muted-foreground">
                …
              </li>
            ) : (
              <li key={number}>
                <Button
                  variant={number === page ? "default" : "ghost"}
                  className="h-11 min-w-11 rounded-[10px] px-1.5 text-base font-semibold"
                  aria-label={t("pagination.page", { page: shown(number) })}
                  aria-current={number === page ? "page" : undefined}
                  onClick={() => go(number)}
                >
                  {shown(number)}
                </Button>
              </li>
            ),
          )}
        </ul>
        <Button variant="outline" className={STEP_BUTTON} disabled={page >= totalPages} onClick={() => go(page + 1)}>
          {t("pagination.next")}
          <ChevronRight aria-hidden="true" className="size-4.5" />
        </Button>
      </nav>
    </div>
  );
}

/**
 * For a list that is the whole page: its page lives in the address, with its filters, so Back, a
 * reload and a shared link all come back to it. The route adds `pagingSearch` to its `validateSearch`.
 */
export function AddressPagination({ result }: { result: PageInfo | undefined }) {
  const navigate = useNavigate();
  return (
    <Pagination
      result={result}
      onChange={(paging, { replace }) =>
        void navigate({ to: ".", search: (previous: Record<string, unknown>) => ({ ...previous, ...paging }), replace })
      }
    />
  );
}
