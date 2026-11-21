#!/bin/sh
set -eu

base=http://127.0.0.1:8080
work=$(mktemp -d)
pid=
stop_server() {
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    pid=
  fi
}
trap 'stop_server; rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

http() {
  curl --noproxy '*' --silent --show-error --max-time 6 "$@"
}
start_server() {
  native-spike &
  pid=$!
  http --fail --retry-connrefused --retry 15 --retry-delay 1 --retry-max-time 20 "$base/login" >/dev/null
}
expect() {
  expected=$1
  shift
  actual=$(http --output "$work/body" --write-out '%{http_code}' "$@")
  if [ "$actual" != "$expected" ]; then
    printf 'Expected HTTP %s, got %s for %s\n' "$expected" "$actual" "$1" >&2
    exit 1
  fi
}

start_server
api="$base/api/v1/smoke"
expect 401 "$api"
expect 401 "$base/login" --data-urlencode "username=$SPIKE_AUTH_USER" --data-urlencode 'password='
expect 303 "$base/login" --cookie-jar "$work/cookies" \
  --data-urlencode "username=$SPIKE_AUTH_USER" --data-urlencode "password=$SPIKE_AUTH_PASSWORD"
expect 200 "$api" --cookie "$work/cookies"
test "$(cat "$work/body")" = "postgres=1 principal=$SPIKE_AUTH_USER"

# The original cookie must survive a process restart without re-login.
stop_server
start_server
expect 200 "$api" --cookie "$work/cookies"
expect 403 "$base/logout" --cookie "$work/cookies" --data-urlencode 'csrf_token=incorrect'
csrf=$(awk '$6 == "cooking_blog_csrf" { print $7 }' "$work/cookies")
test -n "$csrf"
expect 303 "$base/logout" --cookie "$work/cookies" --data-urlencode "csrf_token=$csrf"
# Deliberately replay the original cookie, not the cleared cookie from logout.
expect 401 "$api" --cookie "$work/cookies"
printf 'Native smoke passed (%s): postgres=1, login, restart, CSRF, logout\n' "$(uname -sm)"
