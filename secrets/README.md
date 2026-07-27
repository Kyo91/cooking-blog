# Release secret files

Create these two untracked files before starting the release stack:

- `postgres_password`: a randomly generated PostgreSQL password.
- `auth_password`: a unique password of at least 16 characters for the temporary
  dummy `admin` authenticator.

Use restrictive permissions:

```sh
mkdir -p secrets
openssl rand -base64 32 > secrets/postgres_password
openssl rand -base64 32 > secrets/auth_password
chmod 600 secrets/postgres_password secrets/auth_password
```

Compose mounts them read-only under `/run/secrets`; the application and
PostgreSQL read them from files rather than placing their values in the Compose
configuration or image.
