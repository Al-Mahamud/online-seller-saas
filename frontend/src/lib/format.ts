import { useTranslation } from "react-i18next";
import type { Language } from "@/i18n";
import { banglaDigits } from "./digits";

/**
 * Numbers, taka and dates through Intl: Bangla digits and lakh grouping (১২,৩৪,৫৬৭) in Bangla,
 * lakh grouping with Latin digits in English. Times are shown in Dhaka time wherever the phone is.
 */
const LOCALES: Record<Language, string> = { bn: "bn-BD", en: "en-IN" };
const TIME_ZONE = "Asia/Dhaka";

const formatters = new Map<string, Intl.NumberFormat | Intl.DateTimeFormat>();

function numberFormat(language: Language, decimals: number) {
  const key = `n:${language}:${decimals}`;
  let format = formatters.get(key);
  if (!format) {
    format = new Intl.NumberFormat(LOCALES[language], {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    });
    formatters.set(key, format);
  }
  return format as Intl.NumberFormat;
}

function dayFormat(language: Language, options: Intl.DateTimeFormatOptions) {
  const key = `w:${language}:${JSON.stringify(options)}`;
  let format = formatters.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat(LOCALES[language], { ...options, timeZone: TIME_ZONE });
    formatters.set(key, format);
  }
  return format as Intl.DateTimeFormat;
}

function dateFormat(language: Language, withTime: boolean) {
  const key = `d:${language}:${withTime}`;
  let format = formatters.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat(LOCALES[language], {
      dateStyle: "medium",
      timeStyle: withTime ? "short" : undefined,
      timeZone: TIME_ZONE,
    });
    formatters.set(key, format);
  }
  return format as Intl.DateTimeFormat;
}

export function formatNumber(value: number, language: Language): string {
  return numberFormat(language, Number.isInteger(value) ? 0 : 2).format(value);
}

/** An amount from the API, in whole poisha: 120050 is ৳1,200.50, and 150000 is ৳1,500. */
export function formatTaka(poisha: number, language: Language): string {
  const decimals = poisha % 100 === 0 ? 0 : 2;
  const taka = numberFormat(language, decimals).format(Math.abs(poisha) / 100);
  return `${poisha < 0 ? "−" : ""}৳${taka}`;
}

export function formatDate(at: string | Date, language: Language): string {
  return dateFormat(language, false).format(typeof at === "string" ? new Date(at) : at);
}

export function formatDateTime(at: string | Date, language: Language): string {
  const when = typeof at === "string" ? new Date(at) : at;
  return language === "bn" ? `${formatDate(when, "bn")}, ${banglaTime(when)}` : dateFormat(language, true).format(when);
}

/**
 * "বিকাল ৫:১৬": Bangla says the part of the day first, and has no AM or PM (which Intl gives). Worked
 * out here rather than with Intl's dayPeriod, which older phones' browsers don't have.
 */
export function banglaTime(at: Date): string {
  const hour = Number(dayFormat("en", { hour: "numeric", hourCycle: "h23" }).format(at)) % 24;
  // Dhaka is a whole number of hours ahead of UTC, so the minutes are the same.
  const minute = String(at.getUTCMinutes()).padStart(2, "0");
  const part =
    hour >= 4 && hour < 12
      ? "সকাল"
      : hour >= 12 && hour < 16
        ? "দুপুর"
        : hour >= 16 && hour < 18
          ? "বিকাল"
          : hour >= 18 && hour < 20
            ? "সন্ধ্যা"
            : "রাত";
  return `${part} ${banglaDigits(`${hour % 12 || 12}:${minute}`)}`;
}

/** "+8801712345678" as people write it, "01712-345678". */
export function nationalPhone(e164: string): string {
  const national = e164.startsWith("+880") ? `0${e164.slice(4)}` : e164;
  return `${national.slice(0, 5)}-${national.slice(5)}`;
}

/** The formatters above in the language on screen. */
export function useFormat() {
  const { i18n } = useTranslation();
  const language: Language = i18n.resolvedLanguage === "en" ? "en" : "bn";
  return {
    number: (value: number) => formatNumber(value, language),
    taka: (poisha: number) => formatTaka(poisha, language),
    /** "৳১,২০০ – ৳১,৩০০", or one amount when both ends are the same. */
    takaRange: (low: number, high: number) =>
      low === high ? formatTaka(low, language) : `${formatTaka(low, language)} – ${formatTaka(high, language)}`,
    /** "৮৯.৯%": a share, to one decimal place. */
    percent: (value: number) => `${numberFormat(language, 1).format(value)}%`,
    date: (at: string | Date) => formatDate(at, language),
    dateTime: (at: string | Date) => formatDateTime(at, language),
    /** "শনি": a day's name, short, for a chart's axis. */
    weekday: (at: string) => dayFormat(language, { weekday: "short" }).format(new Date(at)),
    dayMonth: (at: string) => dayFormat(language, { day: "numeric", month: "short" }).format(new Date(at)),
    /** "দুপুর ৩:৪২" or "3:42 pm": the time of day in Dhaka. */
    time: (at: string) =>
      language === "bn"
        ? banglaTime(new Date(at))
        : dayFormat(language, { hour: "numeric", minute: "2-digit" }).format(new Date(at)),
    /** "শনিবার, ৩১ অক্টোবর": a day in full. */
    fullDate: (at: string) =>
      dayFormat(language, { weekday: "long", day: "numeric", month: "long" }).format(new Date(at)),
    /** A phone number as the slips print it. Links and searches keep the full form. */
    phone: (e164: string) => (language === "bn" ? banglaDigits(nationalPhone(e164)) : nationalPhone(e164)),
    /** An order's number as the slips print it. */
    orderNumber: (label: string) => (language === "bn" ? banglaDigits(label) : label),
  };
}
