/**
 * A search box's text from the address. The router reads an all-digit value as a number, and a phone
 * number is just that, so it comes back here as the text it was.
 */
export function searchText(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text ? text : undefined;
}
