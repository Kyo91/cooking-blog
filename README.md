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

All defaults are for local development only. Supply runtime secrets through the
environment for any packaged deployment.
