/**
 * A search box's text from the address. The router reads an all-digit value as a number, and a phone
 * number is just that, so it comes back here as the text it was.
 */
export function searchText(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text ? text : undefined;
}

/**
 * Text as search compares it: lower case and NFC. Some keyboards type য়, ড় and ঢ় as one
 * character and others as two; NFC makes both the two-character form, so a search typed either
 * way finds a name written either way. The server compares names the same way.
 */
export function searchable(text: string): string {
  return text.normalize("NFC").toLowerCase();
}

/** Whether {@code text} has {@code search} in it, as search compares them. */
export function containsText(text: string, search: string): boolean {
  return searchable(text).includes(searchable(search));
}
