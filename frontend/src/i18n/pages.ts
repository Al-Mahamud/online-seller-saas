import i18n from "i18next";
import { useTranslation } from "react-i18next";
import { currentLanguage, type Language } from ".";

/**
 * Texts only one page uses, loaded with that page rather than with every page: they live in
 * src/i18n/pages/<page>.<language>.json and sit under <page> in the translations. A route's
 * loader asks for them; a language picked later gets them too.
 */
const PAGES = {
  activity: { bn: () => import("./pages/activity.bn.json"), en: () => import("./pages/activity.en.json") },
  team: { bn: () => import("./pages/team.bn.json"), en: () => import("./pages/team.en.json") },
  pay: { bn: () => import("./pages/pay.bn.json"), en: () => import("./pages/pay.en.json") },
};
type Page = keyof typeof PAGES;

const wanted = new Set<Page>();

async function add(page: Page, language: Language) {
  if (
    !i18n.hasResourceBundle(language, "translation") ||
    i18n.getResource(language, "translation", page) === undefined
  ) {
    i18n.addResourceBundle(language, "translation", { [page]: (await PAGES[page][language]()).default }, true, true);
  }
}

export async function loadPageTexts(...pages: Page[]) {
  for (const page of pages) {
    wanted.add(page);
  }
  await Promise.all(pages.map((page) => add(page, currentLanguage())));
}

/**
 * The translate function, loosely typed, for pages whose texts load with them: those keys are
 * outside the typed main file, and some are built from what the server sends.
 */
export type LooseT = (key: string, options?: Record<string, unknown>) => string;

export function useLooseT(): LooseT {
  return useTranslation().t as unknown as LooseT;
}

i18n.on("languageChanged", () => {
  for (const page of wanted) {
    void add(page, currentLanguage());
  }
});
