import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import bn from "./bn.json";
import en from "./en.json";

/** Bangla first, English one tap away (frontend-plan.md, "Languages"). */
export const LANGUAGES = ["bn", "en"] as const;
export type Language = (typeof LANGUAGES)[number];

declare module "i18next" {
  interface CustomTypeOptions {
    resources: { translation: typeof bn };
  }
}

const STORAGE_KEY = "mojud.language";

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
  await i18n.changeLanguage(language);
}

i18n.on("languageChanged", () => {
  document.documentElement.lang = currentLanguage();
  document.title = i18n.t("app.name");
});

void i18n.use(initReactI18next).init({
  resources: { bn: { translation: bn }, en: { translation: en } },
  lng: savedLanguage(),
  fallbackLng: "bn",
  interpolation: { escapeValue: false },
});

export default i18n;
