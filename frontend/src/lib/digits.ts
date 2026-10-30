const BANGLA_ZERO = "০".charCodeAt(0);

/** ০১৭ becomes 017: a Bangla keyboard's digits, for codes and phone numbers the server reads as Latin. */
export function latinDigits(text: string): string {
  return text.replace(/[০-৯]/g, (digit) => String(digit.charCodeAt(0) - BANGLA_ZERO));
}
