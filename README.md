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

All defaults are for local development only. Supply runtime secrets through the
environment for any packaged deployment.
