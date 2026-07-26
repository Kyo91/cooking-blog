const { test, expect } = require("@playwright/test");

test("search query carries into the new recipe form", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill("test");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Recipes" })).toBeVisible();

  await page.locator("#recipe-search").fill("browser test recipe");
  await expect(page).toHaveURL(/q=browser%20test%20recipe/);
  await page.locator("#new-recipe").click();
  await expect(page.getByLabel("Title")).toHaveValue("browser test recipe");
});
