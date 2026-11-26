#!/bin/bash
# Exercises ci_scripts/configure_ios_cloud_drive_oauth.sh without a real Infisical account.
# Failure output names the case only. It does not print secret values or command logs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${ROOT}/ci_scripts/configure_ios_cloud_drive_oauth.sh"

GOOGLE_ID='1234567890-abc123.apps.googleusercontent.com'
GOOGLE_SCHEME='com.googleusercontent.apps.1234567890-abc123'
MICROSOFT_ID='11111111-2222-3333-4444-555555555555'
CLIENT_SECRET='p+ss/w0rd='
ACCESS_TOKEN='token-do-not-log'

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

assert_absent() {
  local file="$1"
  local needle="$2"
  local message="$3"
  if grep -F -q -e "$needle" "$file"; then
    fail "$message"
  fi
}

assert_present() {
  local file="$1"
  local needle="$2"
  local message="$3"
  if ! grep -F -q -e "$needle" "$file"; then
    fail "$message"
  fi
}

# Wiring that has to stay aligned with the OAuth redirect constants.
assert_present "$ROOT/iosApp/Configuration/Config.xcconfig" \
  '#include? "CloudDriveOAuth.xcconfig"' \
  "Config.xcconfig does not include CloudDriveOAuth.xcconfig"
assert_present "$ROOT/iosApp/Configuration/Config.xcconfig" \
  'WINGMATE_GOOGLE_IOS_URL_SCHEME = com.hojmoseit.wingmate.google-oauth' \
  "Config.xcconfig is missing the placeholder Google URL scheme"
assert_present "$ROOT/.gitignore" 'iosApp/Configuration/CloudDriveOAuth.xcconfig' \
  "CloudDriveOAuth.xcconfig is not gitignored"
assert_present "$ROOT/iosApp/iosApp/Info.plist" 'msauth.com.hojmoseit.wingmate' \
  "Info.plist is missing the Microsoft callback scheme"
assert_present "$ROOT/iosApp/iosApp/Info.plist" '$(WINGMATE_GOOGLE_IOS_URL_SCHEME)' \
  "Info.plist is missing the Google callback scheme setting"
assert_present "$ROOT/core/data/src/commonMain/kotlin/io/github/jdreioe/wingmate/infrastructure/CloudDriveProviders.kt" \
  'msauth.com.hojmoseit.wingmate://auth' \
  "Kotlin Microsoft iOS redirect does not match Info.plist"
assert_present "$ROOT/shared/src/iosMain/kotlin/io/github/jdreioe/wingmate/infrastructure/IosCloudDriveSignIn.kt" \
  'CloudDriveOAuth.MICROSOFT_IOS_REDIRECT_URI' \
  "iOS sign-in does not use the Microsoft iOS redirect"

make_fake_infisical() {
  local bin="$1"
  mkdir -p "$bin"
  cat >"${bin}/infisical" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "${INFISICAL_FAKE_LOG:?}"
if [[ "${1:-}" == "login" ]]; then
  if [[ "${INFISICAL_FAKE_LOGIN_FAIL:-}" == "1" ]]; then
    printf 'login rejected: %s\n' "${INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET:-}" >&2
    exit 1
  fi
  printf '%s\n' 'token-do-not-log'
  exit 0
fi
name=""
if [[ "${1:-}" == "secrets-manager" && "${3:-}" == "get" ]]; then
  if [[ "${INFISICAL_FAKE_LEGACY:-}" == "1" ]]; then
    echo "Error: unknown command" >&2
    exit 1
  fi
  name="${4:-}"
elif [[ "${1:-}" == "secrets" && "${2:-}" == "get" ]]; then
  name="${3:-}"
else
  echo "unexpected infisical invocation" >&2
  exit 1
fi
case "$name" in
  WINGMATE_GOOGLE_IOS_CLIENT_ID)
    if [[ "${INFISICAL_FAKE_GOOGLE:-missing}" == "missing" ]]; then
      printf '%s\n' '*not found*'
    else
      printf '%s\n' "$INFISICAL_FAKE_GOOGLE"
    fi
    ;;
  WINGMATE_MICROSOFT_CLIENT_ID)
    if [[ "${INFISICAL_FAKE_MICROSOFT:-missing}" == "missing" ]]; then
      printf '%s\n' '*not found*'
    else
      printf '%s\n' "$INFISICAL_FAKE_MICROSOFT"
    fi
    ;;
  *)
    printf '%s\n' '*not found*'
    ;;
esac
EOF
  chmod 755 "${bin}/infisical"
}

run_case() {
  local name="$1"
  shift
  local work log status
  work="$(mktemp -d)"
  log="${work}/log"
  set +e
  env -i \
    PATH="${PATH}" \
    HOME="${work}/home" \
    WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
    "$@" \
    bash "$SCRIPT" >"$log" 2>&1
  status=$?
  set -e
  CASE_STATUS="$status"
  CASE_LOG="$log"
  CASE_FILE="${work}/CloudDriveOAuth.xcconfig"
  CASE_WORK="$work"
  echo "case ${name}: exit ${status}"
}

# 1. No credentials: succeed, write nothing, and do not mention a sample secret.
run_case missing-creds
[[ "$CASE_STATUS" -eq 0 ]] || fail "missing credentials should leave the build usable"
[[ ! -f "$CASE_FILE" ]] || fail "missing credentials wrote an xcconfig"
assert_present "$CASE_LOG" "INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, and INFISICAL_PROJECT_ID" \
  "missing credentials did not warn"
assert_absent "$CASE_LOG" "$CLIENT_SECRET" "missing-creds log contained a secret"

# 2. Environment IDs, including bash -x, never reach the log.
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="${PATH}" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  WINGMATE_GOOGLE_IOS_CLIENT_ID="  \"${GOOGLE_ID}\"  " \
  WINGMATE_MICROSOFT_CLIENT_ID="  ${MICROSOFT_ID}  " \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  bash -x "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 0 ]] || fail "environment client IDs should configure the xcconfig"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_GOOGLE_IOS_CLIENT_ID = ${GOOGLE_ID}" \
  "Google client ID was not written"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_GOOGLE_IOS_URL_SCHEME = ${GOOGLE_SCHEME}" \
  "Google URL scheme was not derived"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_MICROSOFT_CLIENT_ID = ${MICROSOFT_ID}" \
  "Microsoft client ID was not written"
assert_absent "$log" "$GOOGLE_ID" "trace or log printed the Google client ID"
assert_absent "$log" "$MICROSOFT_ID" "trace or log printed the Microsoft client ID"
assert_absent "$log" "$GOOGLE_SCHEME" "trace or log printed the Google URL scheme"
assert_absent "$log" "$CLIENT_SECRET" "trace or log printed the Infisical client secret"
assert_present "$log" "Configured Google Drive iOS client ID" "Google configuration was not reported"
assert_present "$log" "Configured OneDrive client ID" "OneDrive configuration was not reported"

# 3. A malformed ID fails the build and is not printed.
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="${PATH}" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  WINGMATE_GOOGLE_IOS_CLIENT_ID='not-a-client-id' \
  WINGMATE_MICROSOFT_CLIENT_ID="$MICROSOFT_ID" \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 1 ]] || fail "a malformed Google client ID should fail the build"
assert_absent "$log" 'not-a-client-id' "malformed client ID was printed"
assert_present "$log" "WINGMATE_GOOGLE_IOS_CLIENT_ID is not a Google iOS OAuth client ID" \
  "malformed Google client ID was not reported"
# The valid Microsoft ID may still be written; the build stops because of the bad one.
assert_absent "${work}/CloudDriveOAuth.xcconfig" 'not-a-client-id' "malformed client ID was written"

# 4. Infisical fills both IDs. Logs omit the token, client secret, and client IDs.
fake_home="$(mktemp -d)"
make_fake_infisical "${fake_home}/bin"
work="$(mktemp -d)"
log="${work}/log"
fake_log="${work}/infisical-args"
set +e
env -i \
  PATH="${fake_home}/bin:/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  INFISICAL_FAKE_LOG="$fake_log" \
  INFISICAL_FAKE_GOOGLE="$GOOGLE_ID" \
  INFISICAL_FAKE_MICROSOFT="$MICROSOFT_ID" \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_ID='proj-test-id' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 0 ]] || fail "Infisical fetch should write both client IDs"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_GOOGLE_IOS_URL_SCHEME = ${GOOGLE_SCHEME}" \
  "Infisical Google URL scheme was not derived"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_MICROSOFT_CLIENT_ID = ${MICROSOFT_ID}" \
  "Infisical Microsoft client ID was not written"
assert_absent "$log" "$GOOGLE_ID" "Infisical log printed the Google client ID"
assert_absent "$log" "$MICROSOFT_ID" "Infisical log printed the Microsoft client ID"
assert_absent "$log" "$CLIENT_SECRET" "Infisical log printed the client secret"
assert_absent "$log" "$ACCESS_TOKEN" "Infisical log printed the access token"
assert_absent "$fake_log" "$CLIENT_SECRET" "client secret was passed on the Infisical command line"
assert_present "$fake_log" "--method=universal-auth" "universal auth was not used"
assert_present "$fake_log" "--domain=https://eu.infisical.com" "EU Infisical domain was not used"
assert_present "$fake_log" "--env=prod" "prod environment was not requested"
assert_present "$fake_log" "--projectId=proj-test-id" "project ID was not passed to the CLI"

# 5. Login failure redacts the client secret and fails because credentials were present.
fake_home="$(mktemp -d)"
make_fake_infisical "${fake_home}/bin"
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="${fake_home}/bin:/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  INFISICAL_FAKE_LOG="${work}/infisical-args" \
  INFISICAL_FAKE_LOGIN_FAIL=1 \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_ID='proj-test-id' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 1 ]] || fail "a failed Infisical login should fail the build"
assert_absent "$log" "$CLIENT_SECRET" "login failure printed the client secret"
assert_present "$log" "[redacted]" "login failure was not redacted"
assert_present "$log" "Infisical login failed" "login failure was not reported"

# 6. Secrets that are not in Infisical yet leave the build usable.
fake_home="$(mktemp -d)"
make_fake_infisical "${fake_home}/bin"
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="${fake_home}/bin:/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  INFISICAL_FAKE_LOG="${work}/infisical-args" \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_ID='proj-test-id' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 0 ]] || fail "missing Infisical secrets should leave the build usable"
[[ ! -f "${work}/CloudDriveOAuth.xcconfig" ]] || fail "missing secrets wrote an xcconfig"
assert_present "$log" "WINGMATE_GOOGLE_IOS_CLIENT_ID is unset in Infisical prod" \
  "missing Google secret was not reported"
assert_absent "$log" "$CLIENT_SECRET" "missing-secret log printed the client secret"

# 7. A project slug is not a project ID. Incomplete credentials stay soft.
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_SLUG='wingmate' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 0 ]] || fail "a missing project ID should leave the build usable"
assert_present "$log" "INFISICAL_PROJECT_ID" "missing project ID was not explained"
assert_absent "$log" "$CLIENT_SECRET" "slug warning printed the client secret"
[[ ! -f "${work}/CloudDriveOAuth.xcconfig" ]] || fail "incomplete credentials wrote an xcconfig"

# 8. Credentials are present but the CLI cannot be installed.
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  WINGMATE_SKIP_INFISICAL_INSTALL=1 \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_ID='proj-test-id' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 1 ]] || fail "a missing Infisical CLI should fail when credentials are set"
assert_present "$log" "Infisical CLI is not on PATH" "missing CLI was not reported"
assert_absent "$log" "$CLIENT_SECRET" "missing-CLI log printed the client secret"

# 9. Older CLI layout: secrets-manager is unknown, secrets get works.
fake_home="$(mktemp -d)"
make_fake_infisical "${fake_home}/bin"
work="$(mktemp -d)"
log="${work}/log"
set +e
env -i \
  PATH="${fake_home}/bin:/usr/bin:/bin" \
  HOME="${work}/home" \
  WINGMATE_OAUTH_XCCONFIG="${work}/CloudDriveOAuth.xcconfig" \
  INFISICAL_FAKE_LOG="${work}/infisical-args" \
  INFISICAL_FAKE_LEGACY=1 \
  INFISICAL_FAKE_GOOGLE="$GOOGLE_ID" \
  INFISICAL_FAKE_MICROSOFT="$MICROSOFT_ID" \
  INFISICAL_CLIENT_ID='machine-client' \
  INFISICAL_CLIENT_SECRET="$CLIENT_SECRET" \
  INFISICAL_PROJECT_ID='proj-test-id' \
  bash "$SCRIPT" >"$log" 2>&1
status=$?
set -e
[[ "$status" -eq 0 ]] || fail "legacy Infisical CLI should still provide client IDs"
assert_present "${work}/CloudDriveOAuth.xcconfig" "WINGMATE_GOOGLE_IOS_CLIENT_ID = ${GOOGLE_ID}" \
  "legacy CLI did not write the Google client ID"
if ! grep -E -q '^secrets get WINGMATE_GOOGLE_IOS_CLIENT_ID( |$)' "${work}/infisical-args"; then
  fail "legacy CLI command was not used"
fi
assert_absent "$log" "$CLIENT_SECRET" "legacy CLI log printed the client secret"
assert_absent "$log" "$ACCESS_TOKEN" "legacy CLI log printed the access token"

echo "iOS cloud drive OAuth configuration tests passed"
