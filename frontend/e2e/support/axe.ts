import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

/** No WCAG 2.2 A or AA problems on the page as it is now (frontend-plan.md, "Accessibility"). */
export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  const problems = results.violations.map(
    (violation) =>
      `${violation.id}: ${violation.help} (${violation.nodes.map((node) => node.target.join(" ")).join(", ")})`,
  );
  expect(problems).toEqual([]);
}
