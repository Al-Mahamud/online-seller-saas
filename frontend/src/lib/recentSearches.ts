import { useCallback, useState } from "react";

const MOST = 4;
const key = (list: string) => `mojud.recent-searches.${list}`;

function read(list: string): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem(key(list)) ?? "[]");
    return Array.isArray(stored)
      ? stored.filter((item): item is string => typeof item === "string").slice(0, MOST)
      : [];
  } catch {
    return [];
  }
}

/**
 * The last few searches of one list, newest first, kept in this browser only: a convenience, gone
 * in a private window or when site data is cleared.
 */
export function useRecentSearches(list: string) {
  const [recent, setRecent] = useState(() => read(list));
  const remember = useCallback(
    (text: string) => {
      if (!text) {
        return;
      }
      const next = [text, ...read(list).filter((item) => item !== text)].slice(0, MOST);
      setRecent(next);
      try {
        localStorage.setItem(key(list), JSON.stringify(next));
      } catch {
        // Storage refused: the list still works, without remembering.
      }
    },
    [list],
  );
  return { recent, remember };
}
