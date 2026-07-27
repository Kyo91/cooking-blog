# Laptop release runbook

This release is for a single trusted laptop. Compose publishes the application
only on `127.0.0.1` and does not publish PostgreSQL. Production wiring uses the
configured single-user authenticator and bounded failed-login backoff, but this
profile deliberately runs plain HTTP with non-secure cookies and therefore
must not be exposed through a LAN or public reverse proxy.

The laptop release has **no backup or restore guarantee**. The named PostgreSQL
and photo volumes survive container replacement, but they are not backups.
Deletion is permanent. Before any cloud or untrusted deployment, replace the
dummy authenticator, move photos to object storage, configure managed database
backups, and rehearse a restore.

## Build and start version 0.1.0

Create untracked secret files:

```sh
mkdir -p secrets
openssl rand -out secrets/postgres_password -base64 32
openssl rand -out secrets/auth_password -base64 32
chmod 600 secrets/postgres_password secrets/auth_password
cp .env.release.example .env.release
```

The login username remains `admin`; `secrets/auth_password` is its release
password. Production startup rejects the development database password,
`admin` password `test`, login passwords shorter than 16 characters, relative
local photo paths, and unsafe request/scraper/pool limits. The release profile
sets `DEPLOYMENT_TARGET=laptop`, `PHOTO_BACKEND=local`, and
`SCRAPE_ENABLED=true` explicitly.

Build the pinned image and start the stack:

```sh
./bin/build-image
./bin/run-local
```

Open <http://127.0.0.1:8080/login>. The first startup applies Flyway migrations.
Runtime secrets are mounted read-only under `/run/secrets`; neither secret is
baked into the image or expanded into the Compose configuration.

The application runs as UID 1001 with a read-only root filesystem, a bounded
temporary filesystem, no Linux capabilities, and `no-new-privileges`. The
`cooking_blog_release_postgres_data` and `cooking_blog_release_photos` named
volumes are deliberately separate from development data.

## Release scripts

The scripts can be run from any working directory:

| Script | Operation |
| --- | --- |
| `./bin/build-image` | Build the version configured by sbt as a local Docker image |
| `./bin/run-local` | Start the release stack and wait for health checks |
| `./bin/check-status` | Show release containers, ports, and health |
| `./bin/show-logs` | Show recent application logs |
| `./bin/restart-local` | Restart only the application container |
| `./bin/upgrade-local` | Build and replace the application container |
| `./bin/stop-local` | Stop the stack without deleting persistent volumes |

They use `.env.release` by default. Set `COOKING_BLOG_ENV_FILE` to use another
release environment file.

## Observe and diagnose

Inspect container health and operational logs:

```sh
./bin/check-status
./bin/show-logs
```

`show-logs` displays the latest 200 application lines by default. Set
`LOG_TAIL`, for example `LOG_TAIL=500 ./bin/show-logs`, to change that limit.

The container health check exercises the public login endpoint. After signing
in, `/health/ready` verifies PostgreSQL and writable photo storage, while
`/health/live` reports process liveness. Both operational endpoints remain
authenticated, preserving the default-deny route boundary.

Authenticated `/metrics` uses Prometheus text format and exposes:

- completed HTTP request counts and latency by method/status;
- scrape attempt counts and duration by outcome;
- current pending, running, succeeded, and failed durable scrape jobs;
- photo-processing failure counts by bounded reason.

Terminal scrape failures are alertable through
`cooking_blog_scrape_jobs{status="failed"}` and are logged as
`Scrape worker ... failed job ...`. Request completion logs contain the request
ID, method, path without query text, status, and duration; imported content,
passwords, cookies, and URL query strings are not logged.

## Restart, upgrade, and rollback

A normal restart preserves database-backed sessions, recipes, jobs, and photos:

```sh
./bin/restart-local
```

For an upgrade, set `COOKING_BLOG_VERSION` in `.env.release` to the newly built
version, build it, then replace the application container:

```sh
./bin/upgrade-local
```

Verify login, `/health/ready`, `/metrics`, search, and a photo before removing
the prior image. To roll back application code, restore the previous
`COOKING_BLOG_VERSION` and run `./bin/upgrade-local` again. Database rollback
is not automatic: never run an older image after an incompatible migration.
Migrations must remain backward compatible across the intended rollback
window.

Changing the PostgreSQL secret file does not change the password inside an
already initialized database. Rotate it with a coordinated PostgreSQL
`ALTER ROLE`, secret-file update, and stack restart. Rotating the login secret
affects new logins; existing database sessions remain valid until logout,
invalidation, or their absolute 24-hour expiry.

## Stop without deleting data

```sh
./bin/stop-local
```

Do not add `--volumes` unless permanent deletion of the release database and
all release photos is explicitly intended.
