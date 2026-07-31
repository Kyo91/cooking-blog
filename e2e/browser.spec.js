const { test, expect } = require("@playwright/test");

const transparentPng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);

async function signIn(page) {
  await page.goto("/login");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill(process.env.AUTH_PASSWORD || "test");
  await page.getByRole("button", { name: "Sign in" }).click();
}

async function createRecipe(page, title) {
  await page.goto("/recipes/new");
  await page.getByLabel("Title").fill(title);
  await page.getByRole("button", { name: "Create recipe" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
}

test("search query carries into the new recipe form", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("heading", { name: "Recipes" })).toBeVisible();

  await page.locator("#recipe-search").fill("browser test recipe");
  await expect(page).toHaveURL(/q=browser%20test%20recipe/);
  await expect(page.getByRole("link", { name: "Add recipe" })).toHaveAttribute("href", /title=browser%20test%20recipe/);
  await page.locator("#new-recipe").click();
  await expect(page.getByLabel("Title")).toHaveValue("browser test recipe");
});

test("recipe form supports a repeatable book source row", async ({ page }) => {
  const title = `Browser source ${Date.now()}`;
  await signIn(page);
  await page.goto("/recipes/new");
  await page.getByLabel("Title").fill(title);
  await page.getByRole("button", { name: "Add source" }).click();
  const source = page.locator(".source-row").last();
  await source.locator("select").selectOption("book");
  await source.getByLabel("Book citation").fill("Example Cookbook, p. 42");
  await page.getByRole("button", { name: "Create recipe" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Example Cookbook, p. 42" })).toBeVisible();
});

test("phone viewport keeps primary controls reachable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  await expect(page.locator("#recipe-search")).toBeVisible();
  await expect(page.getByRole("link", { name: "Add recipe" })).toBeVisible();
  await expect(page.locator("#new-recipe")).toHaveCSS("min-height", "44px");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow).toBeFalsy();
});

test("a recipe card is one mobile tap target", async ({ page }) => {
  const title = `Browser card ${Date.now()}`;
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  await createRecipe(page, title);
  await page.goto("/");

  const card = page.locator(".recipe-card").filter({ hasText: title });
  const link = card.getByRole("link", { name: title });
  await expect(link).toHaveAttribute("href", /\/recipes\//);
  await link.click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
});

test("URL sources show an asynchronous import state", async ({ page }) => {
  const title = `Browser import ${Date.now()}`;
  await signIn(page);
  await createRecipe(page, title);
  await page.getByLabel("Add a source").selectOption("url");
  await page.locator("#reference-url").fill("https://example.com/");
  await page.getByRole("button", { name: "Add source" }).click();
  await expect(page.locator(".reference .status")).toContainText(/queued|pending|running|complete|failed/);
});

test("meal photo upload supports caption, primary selection, and deletion", async ({ page }) => {
  const title = `Browser photo ${Date.now()}`;
  await signIn(page);
  await createRecipe(page, title);
  await page.getByRole("link", { name: "Record meal" }).click();
  await page.getByLabel("Notes").fill("Photo upload browser test");
  await page.locator("#photos").setInputFiles({
    name: "photo.png",
    mimeType: "image/png",
    buffer: transparentPng
  });
  await expect(page.locator("#photo-previews img")).toHaveCount(1);
  await page.getByRole("button", { name: "Save cooking entry" }).click();
  await expect(page.getByLabel("Photo caption")).toBeVisible();

  await page.getByLabel("Photo caption").fill("Dinner photo");
  await page.getByRole("button", { name: "Save caption" }).click();
  await expect(page.getByLabel("Photo caption")).toHaveValue("Dinner photo");

  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "Use as primary" }).click();
  await expect(page.locator(".hero-photo")).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download original photo" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("photo.png");

  page.once("dialog", (dialog) => dialog.accept());
  await page.getByLabel("More photo actions").click();
  await page.getByRole("button", { name: "Delete photo" }).click();
  await expect(page.getByLabel("Photo caption")).toHaveCount(0);
});

test("recipe history groups icon actions with their meal and photo", async ({ page }) => {
  const title = `Browser actions ${Date.now()}`;
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  await createRecipe(page, title);
  await page.getByRole("link", { name: "Record meal" }).click();
  await page.locator("#photos").setInputFiles({
    name: "actions.png",
    mimeType: "image/png",
    buffer: transparentPng
  });
  await page.getByRole("button", { name: "Save cooking entry" }).click();

  const meal = page.locator(".meal");
  await expect(meal.getByRole("link", { name: "Edit cooking entry" })).toBeVisible();
  await expect(meal.getByLabel("More cooking entry actions")).toBeVisible();
  await expect(meal.getByRole("button", { name: "Use as primary photo" })).toBeVisible();
  await expect(meal.getByRole("link", { name: "Download original photo" })).toBeVisible();
  await expect(meal.getByLabel("More photo actions")).toBeVisible();
  await meal.getByLabel("More photo actions").click();
  await expect(meal.getByRole("button", { name: "Delete photo" })).toBeVisible();
  await expect(meal.getByRole("button", { name: "Save caption" })).toHaveCSS("min-height", "44px");
  await expect(meal.getByRole("link", { name: "Download original photo" })).toHaveCSS("min-height", "44px");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow).toBeFalsy();
});
