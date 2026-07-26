# Cooking Blog

A Scala 3 cooking blog and recipe directory.

## Prerequisites

- JDK 21 or newer
- sbt
- Docker with Docker Compose

## Local development

Start PostgreSQL:

```sh
docker compose up -d --wait postgres
```

Run the service:

```sh
sbt run
```

Then open <http://localhost:8080/login> and sign in with the development-only
credentials:

- Username: `admin`
- Password: `test`

The PostgreSQL development connection is:

```text
jdbc:postgresql://localhost:5432/cooking_blog
username: cooking_blog
password: cooking_blog_dev
```

Run checks:

```sh
sbt scalafmtCheckAll test
```

The checked-in API contract is in
[`docs/openapi.yaml`](docs/openapi.yaml). API mutations require the CSRF secret
from the `cooking_blog_csrf` cookie in the `X-CSRF-Token` header.

## Search

`GET /api/v1/recipes?q=grilled+chicken` uses PostgreSQL full-text ranking with
title matches weighted above comma-separated recipe keywords, descriptions and
references, then meal notes and imported text. A title trigram fallback handles
partial recollections and misspellings. Results with a query use deterministic
relevance order by default; `sort=title` or `sort=updated` explicitly applies
that alternate order. Recipe create and update requests accept an optional
`keywords` string: entries are split on commas, trimmed, blank entries are
discarded, and duplicates are removed case-insensitively.

Photo uploads use multipart form data with one or more `photo` fields (up to 10
per request) and an optional `comment` field. Each photo must decode as JPEG,
PNG, or WebP and must be no larger than 10,000,000 bytes. The service corrects
camera orientation, strips metadata, and creates display and thumbnail
variants. WebP uploads are normalized to PNG for portable pure-Java processing.

## Photo storage

Photos are stored under `./data/photos` by default. Set `PHOTO_DIRECTORY` to an
absolute path for a different laptop media directory. The directory must remain
writable while the service runs and should live on persistent storage. Database
records contain opaque storage keys; do not rename files within this directory.

The laptop phase deliberately has no automated photo or PostgreSQL backups.
Deleting a photo, meal, or recipe permanently removes its photo files.

## Recipe imports

Creating a URL reference commits a pending scrape job in the same PostgreSQL
transaction and returns immediately. A supervised worker pool claims durable
jobs with PostgreSQL row locking, imports readable recipe text, and rebuilds the
recipe search document. Pending work survives application restarts. Use
`GET /api/v1/recipes/{recipeId}/references/{referenceId}/scrape` to read the
latest status and imported text, and `POST` to the same path to refresh or retry
an import.

The scraper accepts public HTTP(S) destinations only. It resolves and rejects
loopback, private, link-local, metadata-service, documentation, carrier-grade
NAT, multicast, and other non-public addresses before every request and
redirect. Redirect count, response bytes, request time, total job time, global
worker count, and per-host connections are bounded. Recipe JSON-LD is preferred;
readable main content is the fallback, and a discovered print page is used only
when it passes the same checks and yields useful text.

Robots policy for the laptop release: imports are explicit, user-initiated
personal archival requests, so the worker does not fetch or interpret
`robots.txt`. It identifies itself, uses one connection per host by default,
and treats remote `4xx` responses as terminal instead of attempting to bypass
site policy. Revisit this policy before any multi-user or public deployment.

The deterministic suite includes a checked-in Serious Eats-style fixture. To
exercise the optional network test against the original example URL:

```sh
LIVE_SCRAPE_URL=https://www.seriouseats.com/sous-vide-glazed-carrots-recipe \
  sbt "testOnly cookingblog.scraping.LiveScrapeSuite"
```

This test intentionally fails if the remote site rejects automated access; a
remote access policy is not bypassed.

## Scala style

Use classical braces instead of Scala 3 significant-whitespace syntax. A
function or code block containing more than one statement must be enclosed in
braces. The build enables the Scala compiler's `-no-indent` option, so
significant-whitespace syntax fails compilation.

## Configuration

| Environment variable | Development default | Purpose |
| --- | --- | --- |
| `HTTP_HOST` | `127.0.0.1` | HTTP bind address |
| `HTTP_PORT` | `8080` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/cooking_blog` | JDBC URL |
| `DATABASE_USER` | `cooking_blog` | Database username |
| `DATABASE_PASSWORD` | `cooking_blog_dev` | Database password |
| `DATABASE_POOL_SIZE` | `4` | Maximum database connections |
| `AUTH_USERNAME` | `admin` | Dummy development login |
| `AUTH_PASSWORD` | `test` | Dummy development password |
| `AUTH_SESSION_HOURS` | `24` | Absolute session lifetime |
| `AUTH_COOKIE_SECURE` | `false` | Require HTTPS for auth cookies |
| `PHOTO_DIRECTORY` | `./data/photos` | Persistent local photo storage |
| `SCRAPE_WORKERS` | `2` | Concurrent durable job workers |
| `SCRAPE_PER_HOST_CONCURRENCY` | `1` | Maximum connections to one host |
| `SCRAPE_POLL_MILLIS` | `500` | Queue polling interval |
| `SCRAPE_STALE_JOB_MINUTES` | `5` | Time before an abandoned running job is recovered |
| `SCRAPE_REQUEST_SECONDS` | `15` | Per-request/header timeout |
| `SCRAPE_TOTAL_JOB_SECONDS` | `45` | Total timeout for one job attempt |
| `SCRAPE_MAX_RESPONSE_BYTES` | `2000000` | Maximum downloaded HTML bytes |
| `SCRAPE_MAX_REDIRECTS` | `5` | Maximum redirects per fetch |
| `SCRAPE_MAX_ATTEMPTS` | `5` | Attempts before terminal failure |
| `SCRAPE_BASE_RETRY_SECONDS` | `30` | Initial exponential retry delay |
| `SCRAPE_MAX_RETRY_MINUTES` | `60` | Maximum jittered retry delay |
| `SCRAPE_USER_AGENT` | `CookingBlog/0.1 (+personal recipe archive)` | HTTP identification |

All defaults are for local development only. Supply runtime secrets through the
environment for any packaged deployment.
