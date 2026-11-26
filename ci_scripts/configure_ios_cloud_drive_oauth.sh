#!/bin/bash
# Fetch public Google Drive / OneDrive client IDs and write a gitignored xcconfig.
#
# Xcode Cloud runs this from ci_post_clone.sh. Compatible with macOS /bin/bash 3.2.
# Secret values are never printed. `set +x` disables shell tracing before any
# secret is read, including when the script is started with bash -x.

set +x
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${script_dir}/.." && pwd)"
if [[ -n "${CI_WORKSPACE_PATH:-}" ]]; then
  REPO_ROOT="$CI_WORKSPACE_PATH"
elif [[ -n "${CI_WORKSPACE:-}" ]]; then
  REPO_ROOT="$CI_WORKSPACE"
fi

if [[ -n "${HOME:-}" && -d "${HOME}/.local/bin" ]]; then
  PATH="${HOME}/.local/bin:${PATH}"
  export PATH
fi

cleanup() {
  unset INFISICAL_TOKEN INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET INFISICAL_UNIVERSAL_AUTH_CLIENT_ID || true
}
trap cleanup EXIT

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  value="${value%$'\r'}"
  local len="${#value}"
  if [[ "$len" -ge 2 ]]; then
    local first last
    first="${value%"${value#?}"}"
    last="${value#"${value%?}"}"
    if [[ "$first" == '"' && "$last" == '"' ]]; then
      value="${value#\"}"
      value="${value%\"}"
    fi
  fi
  printf '%s' "$value"
}

is_google_client_id() {
  [[ "$1" =~ ^[0-9]+-[A-Za-z0-9]+\.apps\.googleusercontent\.com$ ]]
}

is_microsoft_client_id() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

# Must match CloudDriveOAuth.googleIosCallbackScheme.
google_ios_url_scheme() {
  local prefix="${1%.apps.googleusercontent.com}"
  printf 'com.googleusercontent.apps.%s' "$prefix"
}

infisical_configured() {
  [[ -n "${INFISICAL_CLIENT_ID:-}" && -n "${INFISICAL_CLIENT_SECRET:-}" && -n "${INFISICAL_PROJECT_ID:-}" ]]
}

emit_redacted() {
  local file="$1"
  if [[ ! -s "$file" ]]; then
    return 0
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    echo "warning: command output omitted so a secret is not printed."
    return 0
  fi
  python3 - "$file" <<'PY' || echo "warning: could not redact command output; details omitted."
import os, sys
text = open(sys.argv[1], "r", errors="replace").read()
for key in (
    "INFISICAL_CLIENT_ID",
    "INFISICAL_CLIENT_SECRET",
    "INFISICAL_UNIVERSAL_AUTH_CLIENT_ID",
    "INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET",
    "INFISICAL_TOKEN",
    "WINGMATE_GOOGLE_IOS_CLIENT_ID",
    "WINGMATE_MICROSOFT_CLIENT_ID",
):
    secret = os.environ.get(key, "")
    if secret:
        text = text.replace(secret, "[redacted]")
sys.stdout.write(text)
if text and not text.endswith("\n"):
    sys.stdout.write("\n")
PY
}

install_infisical_release() {
  local arch_suffix api_json url archive extract member dest
  case "$(uname -s)" in
    Darwin) ;;
    *)
      echo "error: The Infisical CLI release fallback supports macOS. Install infisical on PATH or with Homebrew."
      return 1
      ;;
  esac
  case "$(uname -m)" in
    arm64) arch_suffix='darwin_arm64.tar.gz' ;;
    x86_64) arch_suffix='darwin_amd64.tar.gz' ;;
    *)
      echo "error: Unsupported CPU for the Infisical CLI: $(uname -m)"
      return 1
      ;;
  esac
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is required to install the Infisical CLI from GitHub."
    return 1
  fi
  api_json="$(mktemp)"
  archive="$(mktemp)"
  extract="$(mktemp -d)"
  if ! curl -fsSL 'https://api.github.com/repos/Infisical/cli/releases/latest' -o "$api_json"; then
    echo "error: Could not look up the Infisical CLI release."
    rm -rf "$api_json" "$archive" "$extract"
    return 1
  fi
  url="$(python3 - "$api_json" "$arch_suffix" <<'PY'
import json, sys
path, suffix = sys.argv[1], sys.argv[2]
data = json.load(open(path))
for asset in data.get("assets", []):
    name = asset.get("name", "")
    if name.startswith("cli_") and name.endswith(suffix):
        print(asset["browser_download_url"])
        break
PY
)"
  if [[ -z "$url" ]]; then
    echo "error: The Infisical CLI release has no macOS archive for this CPU."
    rm -rf "$api_json" "$archive" "$extract"
    return 1
  fi
  if ! curl -fsSL "$url" -o "$archive"; then
    echo "error: Could not download the Infisical CLI."
    rm -rf "$api_json" "$archive" "$extract"
    return 1
  fi
  if ! tar -xzf "$archive" -C "$extract"; then
    echo "error: Could not unpack the Infisical CLI."
    rm -rf "$api_json" "$archive" "$extract"
    return 1
  fi
  member="$(find "$extract" -type f -name infisical | head -n 1)"
  if [[ -z "$member" ]]; then
    echo "error: The Infisical CLI archive did not contain the infisical binary."
    rm -rf "$api_json" "$archive" "$extract"
    return 1
  fi
  dest="${HOME}/.local/bin"
  mkdir -p "$dest"
  mv "$member" "${dest}/infisical"
  chmod 755 "${dest}/infisical"
  export PATH="${dest}:${PATH}"
  hash -r || true
  rm -rf "$api_json" "$archive" "$extract"
  echo "Installed Infisical CLI from the GitHub release."
  return 0
}

install_infisical() {
  if command -v infisical >/dev/null 2>&1; then
    return 0
  fi
  if [[ "${WINGMATE_SKIP_INFISICAL_INSTALL:-}" == "1" ]]; then
    echo "error: Infisical CLI is not on PATH."
    return 1
  fi
  if command -v brew >/dev/null 2>&1; then
    echo "Installing Infisical CLI with Homebrew..."
    if NONINTERACTIVE=1 HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK=1 \
        brew install infisical/get-cli/infisical; then
      hash -r || true
      return 0
    fi
    echo "warning: Homebrew install of the Infisical CLI failed. Trying the official release archive."
  fi
  install_infisical_release
}

login_infisical() {
  local err token status
  err="$(mktemp)"
  export INFISICAL_UNIVERSAL_AUTH_CLIENT_ID="$INFISICAL_CLIENT_ID"
  export INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET="$INFISICAL_CLIENT_SECRET"
  set +e
  token="$(infisical login \
    --method=universal-auth \
    --domain="$INFISICAL_DOMAIN" \
    --silent \
    --plain \
    2>"$err")"
  status=$?
  set -e
  if [[ "$status" -ne 0 || -z "$token" || "$token" == *$'\n'* ]]; then
    echo "error: Infisical login failed. Cloud drive client IDs were not fetched."
    emit_redacted "$err"
    rm -f "$err"
    unset INFISICAL_TOKEN || true
    return 1
  fi
  rm -f "$err"
  INFISICAL_TOKEN="$token"
  export INFISICAL_TOKEN
  return 0
}

# Prints the secret value on stdout. Returns 0 when the secret is missing.
# Returns 1 when the CLI itself fails.
fetch_secret_value() {
  local name="$1"
  local err value status
  err="$(mktemp)"
  set +e
  value="$(infisical secrets-manager secrets get "$name" \
    --env="$INFISICAL_ENV" \
    --projectId="$INFISICAL_PROJECT_ID" \
    --path="$INFISICAL_SECRET_PATH" \
    --domain="$INFISICAL_DOMAIN" \
    --plain --silent \
    2>"$err")"
  status=$?
  if [[ "$status" -ne 0 ]] && grep -q -e 'unknown command' -e 'unknown flag' "$err"; then
    value="$(infisical secrets get "$name" \
      --env="$INFISICAL_ENV" \
      --projectId="$INFISICAL_PROJECT_ID" \
      --path="$INFISICAL_SECRET_PATH" \
      --domain="$INFISICAL_DOMAIN" \
      --plain --silent \
      2>"$err")"
    status=$?
  fi
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "error: Failed to read ${name} from Infisical." >&2
    emit_redacted "$err" >&2
    rm -f "$err"
    return 1
  fi
  rm -f "$err"
  value="$(trim "$value")"
  value="${value%%$'\n'*}"
  if [[ -z "$value" || "$value" == "*not found*" || "$value" == "not found" ]]; then
    return 0
  fi
  printf '%s' "$value"
  return 0
}

assign_fetched_secret() {
  local name="$1"
  local value status
  set +e
  value="$(fetch_secret_value "$name")"
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    return 1
  fi
  if [[ -z "$value" ]]; then
    echo "warning: ${name} is unset in Infisical ${INFISICAL_ENV}. That storage option stays hidden."
    return 0
  fi
  case "$name" in
    WINGMATE_GOOGLE_IOS_CLIENT_ID)
      google="$value"
      export WINGMATE_GOOGLE_IOS_CLIENT_ID="$value"
      ;;
    WINGMATE_MICROSOFT_CLIENT_ID)
      microsoft="$value"
      export WINGMATE_MICROSOFT_CLIENT_ID="$value"
      ;;
  esac
  return 0
}

fetch_from_infisical() {
  export INFISICAL_DOMAIN="${INFISICAL_DOMAIN:-https://eu.infisical.com}"
  export INFISICAL_ENV="${INFISICAL_ENV:-prod}"
  export INFISICAL_SECRET_PATH="${INFISICAL_SECRET_PATH:-/}"
  if ! install_infisical; then
    echo "error: Infisical CLI is not available. Cloud drive client IDs were not fetched."
    return 1
  fi
  if ! login_infisical; then
    return 1
  fi
  local failed=0
  if [[ -z "$google" ]]; then
    if ! assign_fetched_secret WINGMATE_GOOGLE_IOS_CLIENT_ID; then
      failed=1
    fi
  fi
  if [[ -z "$microsoft" ]]; then
    if ! assign_fetched_secret WINGMATE_MICROSOFT_CLIENT_ID; then
      failed=1
    fi
  fi
  unset INFISICAL_TOKEN || true
  return "$failed"
}

warn_missing_creds() {
  echo "warning: INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, and INFISICAL_PROJECT_ID are not all set. Unset cloud drive client IDs stay empty."
  if [[ -n "${INFISICAL_PROJECT_SLUG:-}" && -z "${INFISICAL_PROJECT_ID:-}" ]]; then
    echo "warning: INFISICAL_PROJECT_SLUG is set. The Infisical CLI reads INFISICAL_PROJECT_ID, the project ID from Project Settings."
  fi
}

validate_ids() {
  if [[ -n "$google" ]] && ! is_google_client_id "$google"; then
    echo "error: WINGMATE_GOOGLE_IOS_CLIENT_ID is not a Google iOS OAuth client ID. It was left empty."
    google=""
    unset WINGMATE_GOOGLE_IOS_CLIENT_ID || true
    hard_failure=1
  fi
  if [[ -n "$microsoft" ]] && ! is_microsoft_client_id "$microsoft"; then
    echo "error: WINGMATE_MICROSOFT_CLIENT_ID is not a Microsoft Entra application ID. It was left empty."
    microsoft=""
    unset WINGMATE_MICROSOFT_CLIENT_ID || true
    hard_failure=1
  fi
}

write_xcconfig() {
  local dest dir tmp scheme
  dest="${WINGMATE_OAUTH_XCCONFIG:-${REPO_ROOT}/iosApp/Configuration/CloudDriveOAuth.xcconfig}"
  dir="$(dirname "$dest")"
  if [[ ! -d "$dir" ]]; then
    echo "error: OAuth xcconfig directory does not exist."
    return 1
  fi
  if [[ -z "$google" && -z "$microsoft" ]]; then
    return 0
  fi
  tmp="$(mktemp)"
  {
    printf '%s\n' '// Generated by ci_scripts/configure_ios_cloud_drive_oauth.sh. Do not commit.'
    if [[ -n "$google" ]]; then
      scheme="$(google_ios_url_scheme "$google")"
      printf 'WINGMATE_GOOGLE_IOS_CLIENT_ID = %s\n' "$google"
      printf 'WINGMATE_GOOGLE_IOS_URL_SCHEME = %s\n' "$scheme"
    fi
    if [[ -n "$microsoft" ]]; then
      printf 'WINGMATE_MICROSOFT_CLIENT_ID = %s\n' "$microsoft"
    fi
  } >"$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$dest"
  chmod 600 "$dest"
}

main() {
  google="$(trim "${WINGMATE_GOOGLE_IOS_CLIENT_ID:-}")"
  microsoft="$(trim "${WINGMATE_MICROSOFT_CLIENT_ID:-}")"
  hard_failure=0

  if [[ -z "$google" || -z "$microsoft" ]]; then
    if infisical_configured; then
      if ! fetch_from_infisical; then
        hard_failure=1
      fi
    else
      warn_missing_creds
    fi
  fi

  validate_ids

  if [[ -n "$google" || -n "$microsoft" ]]; then
    if ! write_xcconfig; then
      hard_failure=1
    else
      if [[ -n "$google" ]]; then
        echo "Configured Google Drive iOS client ID"
      fi
      if [[ -n "$microsoft" ]]; then
        echo "Configured OneDrive client ID"
      fi
    fi
  fi

  if [[ "$hard_failure" -ne 0 ]]; then
    exit 1
  fi
}

main "$@"
