# Railway public test-deploy runbook

This runbook deploys one public application replica with Railway PostgreSQL and
a private Railway Bucket. The application proxies authenticated media; the
bucket remains private. This is a test deployment, not yet a backup-and-restore
qualified production release.

## Prerequisites

- Push this repository, including `Dockerfile` and `railway.json`, to the
  branch Railway will deploy.
- Create a distinct Railway project and an environment named `staging` (or use
  an otherwise empty `production` environment exclusively for this test).
- Select the same region for the application, PostgreSQL, and bucket.
- Set a conservative Railway monthly spend limit before enabling the public
  domain.

## Provision resources

Create these resources in the same Railway project and environment:

1. An application service from this GitHub repository. Railway detects the
   root `Dockerfile` and runs the packaged Scala application as a non-root user.
2. A PostgreSQL service named `Postgres`.
3. A private Bucket named `photos` in the selected region. Do not make photo
   objects public and do not attach a local volume to the application.
4. A generated Railway domain for the application. Copy its `https://...`
   address for `PUBLIC_ORIGIN` below.

The `/login` route is deliberately the Railway deployment health check: it is
the only public application route and returns `200` only after process startup.
Authenticated `/health/ready` additionally checks PostgreSQL and bucket access
after login. Do not configure `/health/ready` as Railway's public probe because
the application intentionally protects it.

## Application variables

Set these variables on the application service. The `${{...}}` values are
Railway variable references, not literal text. Use the actual PostgreSQL
service name if it differs from `Postgres`.

```dotenv
APP_ENV=production
DEPLOYMENT_TARGET=cloud
HTTP_HOST=0.0.0.0
HTTP_PORT=${{PORT}}
HTTP_MAX_REQUEST_BYTES=105000000

DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DATABASE_USER=${{Postgres.PGUSER}}
DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}
DATABASE_POOL_SIZE=4

AUTH_USERNAME=admin
AUTH_PASSWORD=<generate-a-unique-secret-of-at-least-16-characters>
AUTH_SESSION_HOURS=24
AUTH_COOKIE_SECURE=true
PUBLIC_ORIGIN=https://<generated-or-custom-railway-domain>

PHOTO_BACKEND=s3
PHOTO_S3_BUCKET=${{photos.BUCKET}}
PHOTO_S3_PREFIX=cooking-blog/photos
PHOTO_S3_REGION=${{photos.REGION}}
PHOTO_S3_ENDPOINT=${{photos.ENDPOINT}}
PHOTO_S3_PATH_STYLE=false
PHOTO_S3_CREDENTIALS_MODE=static
PHOTO_S3_ACCESS_KEY_ID=${{photos.ACCESS_KEY_ID}}
PHOTO_S3_SECRET_ACCESS_KEY=${{photos.SECRET_ACCESS_KEY}}
PHOTO_S3_MAX_CONCURRENCY=4
PHOTO_S3_CONNECTION_TIMEOUT_SECONDS=5
PHOTO_S3_REQUEST_TIMEOUT_SECONDS=30

SCRAPE_ENABLED=true
SCRAPE_WORKERS=2
SCRAPE_PER_HOST_CONCURRENCY=1
SCRAPE_POLL_MILLIS=500
SCRAPE_STALE_JOB_MINUTES=5
SCRAPE_REQUEST_SECONDS=15
SCRAPE_TOTAL_JOB_SECONDS=45
SCRAPE_MAX_RESPONSE_BYTES=2000000
SCRAPE_MAX_REDIRECTS=5
SCRAPE_MAX_ATTEMPTS=5
SCRAPE_BASE_RETRY_SECONDS=30
SCRAPE_MAX_RETRY_MINUTES=60
SCRAPE_USER_AGENT=CookingBlog/0.1 (+personal recipe archive)
```

Enter `AUTH_PASSWORD` through Railway's secret variable UI; never commit it,
place it in a `.env` file, or paste it into deployment logs. Railway Bucket
credentials are supplied by references and rotate with the bucket credentials.

## Deploy and verify

Deploy the application service, then wait for Railway to report a successful
deployment. A successful `/login` health check proves only that the process has
started. Complete this authenticated smoke test before treating the deployment
as usable:

1. Log in and verify `/health/ready` and `/metrics` are reachable only while
   authenticated.
2. Create a recipe, create a meal, upload a JPEG or PNG, view the media, and
   delete the photo. This verifies Railway Bucket credentials, write/read/delete
   behavior, and application-proxied media.
3. Add a URL reference; observe it transition from pending to a terminal scrape
   status. If imports are not wanted during the first public test, set
   `SCRAPE_ENABLED=false` before deployment and verify queued-job behavior.
4. Restart the application service. Verify the database-backed session, recipe,
   search result, and uploaded photo still work without a local photo volume.
5. Check application logs for Flyway, PostgreSQL, and S3 errors. Confirm no
   password, cookie, or imported content appears in logs.

## Recovery and limits

Railway's PostgreSQL service is not, by itself, a tested recovery strategy for
this application. Before importing real laptop data, define an external
PostgreSQL export/retention policy and rehearse restoring the database and
bucket contents together in an isolated Railway environment. Keep the laptop
deployment and its photo copy intact until that rehearsal succeeds.

Use one application replica for this test. The durable scrape-worker and
background cleanup loops are currently singleton-oriented. Add monitoring for
application availability, failed scrape jobs, bucket availability, database
availability, and the Railway spend limit before extending the test duration or
audience.
