const BANGLA_ZERO = "০".charCodeAt(0);

/** ০১৭ becomes 017: a Bangla keyboard's digits, for codes and phone numbers the server reads as Latin. */
export function latinDigits(text: string): string {
  return text.replace(/[০-৯]/g, (digit) => String(digit.charCodeAt(0) - BANGLA_ZERO));
}

/** Latin digits as Bangla ones, for what mixes them with letters: "#1001" is "#১০০১". */
export function banglaDigits(text: string): string {
  return text.replace(/[0-9]/g, (digit) => "০১২৩৪৫৬৭৮৯"[Number(digit)] ?? digit);
}
