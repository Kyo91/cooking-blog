create table recipe_keywords (
  id uuid primary key,
  recipe_id uuid not null references recipes(id) on delete cascade,
  keyword text not null,
  constraint recipe_keywords_keyword_not_blank check (btrim(keyword) <> '')
);

create unique index recipe_keywords_recipe_lower_keyword_idx
  on recipe_keywords (recipe_id, lower(keyword));

update recipe_search_documents document
set plain_text = rebuilt.plain_text,
    search_vector = rebuilt.search_vector
from (
  select
    recipe.id,
    concat_ws(
      E'\n',
      recipe.title,
      nullif(recipe.description, ''),
      keyword_values.keyword_text,
      reference_values.reference_text,
      meals.meal_text,
      scraped.scraped_text
    ) as plain_text,
    setweight(to_tsvector('english', recipe.title), 'A') ||
      setweight(to_tsvector('english', coalesce(keyword_values.keyword_text, '')), 'B') ||
      setweight(to_tsvector('english', concat_ws(E'\n', nullif(recipe.description, ''), reference_values.reference_text)), 'C') ||
      setweight(to_tsvector('english', concat_ws(E'\n', meals.meal_text, scraped.scraped_text)), 'D') as search_vector
  from recipes recipe
  left join lateral (
    select string_agg(keyword.keyword, E'\n' order by lower(keyword.keyword), keyword.id) as keyword_text
    from recipe_keywords keyword
    where keyword.recipe_id = recipe.id
  ) keyword_values on true
  left join lateral (
    select string_agg(
      concat_ws(' ', reference.display_name, reference.url, reference.citation),
      E'\n' order by reference.created_at, reference.id
    ) as reference_text
    from recipe_references reference
    where reference.recipe_id = recipe.id
  ) reference_values on true
  left join lateral (
    select string_agg(meal.notes, E'\n' order by meal.cooked_at, meal.id) as meal_text
    from meals meal
    where meal.recipe_id = recipe.id
  ) meals on true
  left join lateral (
    select string_agg(
      concat_ws(' ', document.title, document.content_text),
      E'\n' order by document.scraped_at, document.id
    ) as scraped_text
    from recipe_references reference
    join scraped_documents document on document.reference_id = reference.id
    where reference.recipe_id = recipe.id
  ) scraped on true
) rebuilt
where document.recipe_id = rebuilt.id;
