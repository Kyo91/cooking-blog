create extension if not exists pg_trgm;

create table recipes (
  id uuid primary key,
  title text not null,
  description text not null default '',
  primary_photo_id uuid,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  last_made_at timestamptz,
  constraint recipes_title_not_blank check (btrim(title) <> ''),
  constraint recipes_timestamps_ordered check (updated_at >= created_at)
);

create unique index recipes_lower_title_idx
  on recipes (lower(btrim(title)));

create index recipes_title_trgm_idx
  on recipes using gin (title gin_trgm_ops);

create table meals (
  id uuid primary key,
  recipe_id uuid not null references recipes(id) on delete cascade,
  notes text not null default '',
  cooked_at timestamptz not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint meals_timestamps_ordered check (updated_at >= created_at)
);

create index meals_recipe_cooked_at_idx
  on meals (recipe_id, cooked_at desc);

create table photos (
  id uuid primary key,
  meal_id uuid not null references meals(id) on delete cascade,
  storage_key text not null unique,
  original_filename text not null,
  content_type text not null,
  byte_size bigint not null,
  width integer,
  height integer,
  comment text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint photos_storage_key_not_blank check (btrim(storage_key) <> ''),
  constraint photos_filename_not_blank check (btrim(original_filename) <> ''),
  constraint photos_content_type_supported
    check (content_type in ('image/jpeg', 'image/png', 'image/webp')),
  constraint photos_byte_size_valid
    check (byte_size > 0 and byte_size <= 10000000),
  constraint photos_width_valid check (width is null or width > 0),
  constraint photos_height_valid check (height is null or height > 0),
  constraint photos_timestamps_ordered check (updated_at >= created_at)
);

create index photos_meal_created_at_idx
  on photos (meal_id, created_at);

alter table recipes
  add constraint recipes_primary_photo_fk
  foreign key (primary_photo_id) references photos(id) on delete set null;

create table recipe_references (
  id uuid primary key,
  recipe_id uuid not null references recipes(id) on delete cascade,
  kind text not null,
  url text,
  citation text,
  display_name text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint recipe_references_kind_valid check (kind in ('url', 'book')),
  constraint recipe_references_value_valid check (
    (kind = 'url' and url is not null and btrim(url) <> '' and citation is null)
    or
    (kind = 'book' and citation is not null and btrim(citation) <> '' and url is null)
  ),
  constraint recipe_references_timestamps_ordered check (updated_at >= created_at)
);

create unique index recipe_references_normalized_url_idx
  on recipe_references (recipe_id, lower(btrim(url)))
  where kind = 'url';

create table scraped_documents (
  id uuid primary key,
  reference_id uuid not null unique
    references recipe_references(id) on delete cascade,
  source_url text not null,
  resolved_url text,
  title text,
  content_text text not null,
  content_hash text not null,
  http_etag text,
  http_last_modified text,
  scraped_at timestamptz not null,
  updated_at timestamptz not null,
  constraint scraped_documents_source_url_not_blank
    check (btrim(source_url) <> ''),
  constraint scraped_documents_content_hash_not_blank
    check (btrim(content_hash) <> ''),
  constraint scraped_documents_timestamps_ordered
    check (updated_at >= scraped_at)
);

create table scrape_jobs (
  id uuid primary key,
  reference_id uuid not null
    references recipe_references(id) on delete cascade,
  status text not null,
  attempt_count integer not null default 0,
  available_at timestamptz not null,
  claimed_at timestamptz,
  finished_at timestamptz,
  last_error text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint scrape_jobs_status_valid
    check (status in ('pending', 'running', 'succeeded', 'failed')),
  constraint scrape_jobs_attempt_count_valid check (attempt_count >= 0),
  constraint scrape_jobs_timestamps_ordered check (updated_at >= created_at),
  constraint scrape_jobs_state_timestamps_valid check (
    (status = 'pending' and claimed_at is null and finished_at is null)
    or (status = 'running' and claimed_at is not null and finished_at is null)
    or (status in ('succeeded', 'failed') and finished_at is not null)
  )
);

create index scrape_jobs_runnable_idx
  on scrape_jobs (available_at, created_at)
  where status = 'pending';

create table recipe_search_documents (
  recipe_id uuid primary key references recipes(id) on delete cascade,
  plain_text text not null,
  search_vector tsvector not null,
  updated_at timestamptz not null
);

create index recipe_search_documents_vector_idx
  on recipe_search_documents using gin (search_vector);
