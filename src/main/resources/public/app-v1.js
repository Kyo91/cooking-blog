(() => {
  "use strict";

  const firstInvalid = (root) => root.querySelector("[aria-invalid='true'], :invalid");
  const announce = (message) => {
    const status = document.querySelector("#global-status");
    if (status) status.textContent = message;
  };
  const updateNewRecipeLink = () => {
    const search = document.querySelector("#recipe-search");
    const link = document.querySelector("#new-recipe");
    if (search && link) link.href = `/recipes/new?title=${encodeURIComponent(search.value)}`;
  };

  document.addEventListener("input", (event) => {
    if (event.target.matches("#recipe-search")) updateNewRecipeLink();
    if (event.target.matches("input, textarea, select")) event.target.setAttribute("aria-invalid", "false");
  });

  document.addEventListener("invalid", (event) => {
    if (!event.target.matches("input, textarea, select")) return;
    event.target.setAttribute("aria-invalid", "true");
    const output = event.target.closest("form")?.querySelector("[role='alert'], .form-error");
    if (output && !output.textContent) output.textContent = "Please correct the highlighted field.";
  }, true);

  document.body.addEventListener("htmx:afterRequest", (event) => {
    if (!event.target.matches("#recipe-search, #recipe-sort") || !event.detail.successful) return;
    const query = document.querySelector("#recipe-search")?.value || "";
    const sort = document.querySelector("#recipe-sort")?.value || "recent";
    window.history.replaceState(null, "", `/?q=${encodeURIComponent(query)}&sort=${encodeURIComponent(sort)}`);
  });

  const sourceRow = () => {
    const row = document.createElement("div");
    row.className = "source-row";
    row.innerHTML = `<select name="source_kind" aria-label="Source type"><option value="url">Recipe URL</option><option value="book">Book citation</option></select><input name="source_url" type="url" placeholder="https://example.com/recipe" aria-label="Recipe URL"><input name="source_citation" placeholder="Book title, author, page" aria-label="Book citation"><button class="remove-source" type="button">Remove source</button>`;
    return row;
  };

  const syncSourceRow = (row) => {
    const book = row.querySelector("[name='source_kind']").value === "book";
    const url = row.querySelector("[name='source_url']");
    const citation = row.querySelector("[name='source_citation']");
    url.hidden = book;
    citation.hidden = !book;
    if (book) url.value = "";
    else citation.value = "";
  };

  document.querySelectorAll(".source-row").forEach(syncSourceRow);

  document.addEventListener("click", (event) => {
    if (event.target.matches("#add-recipe-source")) {
      const container = document.querySelector("#recipe-sources");
      const row = sourceRow();
      syncSourceRow(row);
      container.insertBefore(row, event.target);
    }
    if (event.target.matches(".remove-source")) event.target.closest(".source-row").remove();
  });

  document.addEventListener("change", (event) => {
    if (event.target.matches("[name='source_kind']")) syncSourceRow(event.target.closest(".source-row"));
    if (event.target.matches("#reference-kind")) {
      const book = event.target.value === "book";
      const url = document.querySelector("#reference-url");
      const citation = document.querySelector("#reference-citation");
      url.hidden = book;
      citation.hidden = !book;
      if (book) url.value = "";
      else citation.value = "";
    }
  });

  document.addEventListener("submit", (event) => {
    const form = event.target.closest(".confirmation-form");
    if (form && !window.confirm(form.dataset.confirm)) {
      event.preventDefault();
      form.closest(".overflow-menu")?.removeAttribute("open");
    }
  });

  document.addEventListener("click", (event) => {
    const current = event.target.closest(".overflow-menu");
    document.querySelectorAll(".overflow-menu[open]").forEach((menu) => {
      if (menu !== current) menu.removeAttribute("open");
    });
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const menu = event.target.closest(".overflow-menu") || document.querySelector(".overflow-menu[open]");
    if (!menu?.open) return;
    menu.removeAttribute("open");
    menu.querySelector("summary")?.focus();
  });

  document.addEventListener("submit", async (event) => {
    const form = event.target.closest("[data-html-form]");
    if (!form) return;
    event.preventDefault();
    const error = form.querySelector("[role='alert'], .form-error");
    try {
      const response = await fetch(form.action, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(new FormData(form))
      });
      if (response.ok) {
        announce("Saved. Loading the updated page.");
        window.location.assign(response.url);
        return;
      }
      if (!error) return;
      error.innerHTML = await response.text();
      const invalid = form.querySelector("input, textarea, select");
      if (invalid) {
        invalid.setAttribute("aria-invalid", "true");
        invalid.focus();
      } else {
        error.tabIndex = -1;
        error.focus();
      }
    } catch (_) {
      if (error) {
        error.textContent = "Unable to save changes. Please try again.";
        error.tabIndex = -1;
        error.focus();
      }
    }
  });

  const previews = document.querySelector("#photos");
  if (previews) {
    previews.addEventListener("change", () => {
      const output = document.querySelector("#photo-previews");
      output.replaceChildren();
      [...previews.files].forEach((file) => {
        const image = document.createElement("img");
        image.alt = file.name;
        image.src = URL.createObjectURL(file);
        output.append(image);
      });
      output.setAttribute("aria-label", `${previews.files.length} photo preview${previews.files.length === 1 ? "" : "s"}`);
    });
  }

  document.body.addEventListener("htmx:afterSwap", (event) => {
    const invalid = firstInvalid(event.target);
    if (invalid) invalid.focus();
    const alert = event.target.querySelector("[role='alert']");
    if (alert) alert.focus();
  });

  const csrf = () => document.cookie.split("; ").find((cookie) => cookie.startsWith("cooking_blog_csrf="))?.split("=").slice(1).join("=") || "";
  const api = async (url, method, body) => {
    const response = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf() },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || "Unable to save changes.");
    }
    return response.status === 204 ? null : response.json();
  };

  const mealForm = document.querySelector("[data-meal-form]");
  if (mealForm) {
    mealForm.addEventListener("submit", async (event) => {
      const files = document.querySelector("#photos")?.files;
      if (!files?.length) return;
      event.preventDefault();
      const progress = document.querySelector("#upload-progress");
      try {
        progress.textContent = "Saving cooking entry…";
        announce("Saving cooking entry.");
        const payload = Object.fromEntries(new FormData(mealForm).entries());
        payload.cookedAt = new Date(payload.cookedAt).toISOString();
        const mealId = mealForm.dataset.mealId;
        const endpoint = mealId
          ? `/api/v1/recipes/${mealForm.dataset.recipeId}/meals/${mealId}`
          : `/api/v1/recipes/${mealForm.dataset.recipeId}/meals`;
        const meal = await api(endpoint, mealId ? "PATCH" : "POST", payload);
        const upload = new FormData();
        [...files].forEach((file) => upload.append("photo", file));
        progress.textContent = "Uploading photos…";
        announce("Uploading photos.");
        const response = await fetch(`/api/v1/recipes/${mealForm.dataset.recipeId}/meals/${mealId || meal.id}/photos`, {
          method: "POST",
          headers: { "X-CSRF-Token": csrf() },
          body: upload
        });
        if (!response.ok) throw new Error("The cooking entry was saved, but the photo upload failed.");
        announce("Photos uploaded. Loading the recipe.");
        window.location.href = `/recipes/${mealForm.dataset.recipeId}`;
      } catch (error) {
        progress.textContent = "";
        const output = mealForm.querySelector(".form-error");
        output.textContent = error.message;
        output.focus();
      }
    });
  }

  if (document.querySelector("[data-import-active]")) {
    announce("A recipe import is in progress.");
    window.setTimeout(() => window.location.reload(), 3000);
  }

})();
