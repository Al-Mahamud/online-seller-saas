import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import type bn from "./bn.json";

/** Bangla first, English one tap away (frontend-plan.md, "Languages"). */
export const LANGUAGES = ["bn", "en"] as const;
export type Language = (typeof LANGUAGES)[number];

declare module "i18next" {
  interface CustomTypeOptions {
    resources: { translation: typeof bn };
  }
}

const STORAGE_KEY = "mojud.language";

/** Each language's texts load only when used: nobody downloads both. */
const LOADERS = { bn: () => import("./bn.json"), en: () => import("./en.json") };

async function loadTexts(language: Language) {
  if (!i18n.hasResourceBundle(language, "translation")) {
    i18n.addResourceBundle(language, "translation", (await LOADERS[language]()).default);
  }
}

/** The language chosen on this device; Bangla when none was chosen or storage is unavailable. */
export function savedLanguage(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === "bn" || saved === "en") {
      return saved;
    }
  } catch {
    // Private windows and blocked storage: fall back to the default.
  }
  return "bn";
}

export function currentLanguage(): Language {
  return i18n.resolvedLanguage === "en" ? "en" : "bn";
}

export async function setLanguage(language: Language) {
  try {
    localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // Still switch for this visit.
  }
  await loadTexts(language);
  await i18n.changeLanguage(language);
}

i18n.on("languageChanged", () => {
  document.documentElement.lang = currentLanguage();
  document.title = i18n.t("app.name");
});

const initial = savedLanguage();
await i18n.use(initReactI18next).init({
  resources: { [initial]: { translation: (await LOADERS[initial]()).default } },
  lng: initial,
  fallbackLng: false,
  interpolation: { escapeValue: false },
});

export default i18n;
