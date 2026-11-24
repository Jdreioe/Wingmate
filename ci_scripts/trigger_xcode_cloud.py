#!/usr/bin/env python3
"""Trigger an Xcode Cloud build via the App Store Connect API for a release tag.

Uses only the Python standard library and the local `openssl` binary so the
script can run in GitHub Actions without installing third-party packages.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

API_BASE_URL = "https://api.appstoreconnect.apple.com"
ALLOWED_API_HOST = "api.appstoreconnect.apple.com"
RELEASE_TAG_RE = re.compile(r"^(staging|prod)/([0-9]+\.[0-9]+\.[0-9]+)$")
ACTIVE_PROGRESS_STATES = {"PENDING", "RUNNING"}


class TriggerError(RuntimeError):
    """Raised when Xcode Cloud cannot be triggered."""


def parse_release_tag(tag: str) -> tuple[str, str]:
    """Validate and split a release tag such as `staging/1.9.0`."""
    match = RELEASE_TAG_RE.match(tag.strip())
    if not match:
        raise TriggerError(
            f"Unsupported release tag {tag!r}. Expected staging/X.Y.Z or prod/X.Y.Z."
        )
    return match.group(1), match.group(2)


def normalize_private_key(raw_key: str) -> str:
    """Accept raw PEM, escaped-newline PEM, or base64-encoded PEM."""
    cleaned = raw_key.strip()
    if not cleaned:
        raise TriggerError("ASC_PRIVATE_KEY is empty.")

    if "-----BEGIN " in cleaned:
        pem = cleaned.replace("\\r\\n", "\n").replace("\\n", "\n")
    else:
        try:
            decoded = base64.b64decode(cleaned, validate=True).decode("utf-8").strip()
        except Exception as exc:
            raise TriggerError(
                "ASC_PRIVATE_KEY is neither a PEM block nor valid base64-encoded PEM."
            ) from exc
        if "-----BEGIN " not in decoded:
            raise TriggerError(
                "Decoded ASC_PRIVATE_KEY does not contain a PEM private key header."
            )
        pem = decoded

    return pem if pem.endswith("\n") else pem + "\n"


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _read_der_length(der: bytes, offset: int) -> tuple[int, int]:
    if offset >= len(der):
        raise TriggerError("Truncated DER signature length.")
    first = der[offset]
    offset += 1
    if first < 0x80:
        return first, offset
    num_bytes = first & 0x7F
    if num_bytes == 0 or offset + num_bytes > len(der):
        raise TriggerError("Invalid DER signature length encoding.")
    length = int.from_bytes(der[offset : offset + num_bytes], "big")
    return length, offset + num_bytes


def der_to_p1363_es256(der: bytes) -> bytes:
    """Convert an ASN.1 DER ECDSA signature into raw 64-byte IEEE P1363 (r || s)."""
    if not der or der[0] != 0x30:
        raise TriggerError("Expected ASN.1 SEQUENCE in ECDSA signature.")
    seq_len, offset = _read_der_length(der, 1)
    if offset + seq_len != len(der):
        raise TriggerError("ECDSA DER sequence length mismatch.")

    parts: list[bytes] = []
    for _ in range(2):
        if offset >= len(der) or der[offset] != 0x02:
            raise TriggerError("Expected ASN.1 INTEGER in ECDSA signature.")
        int_len, offset = _read_der_length(der, offset + 1)
        if offset + int_len > len(der) or int_len == 0:
            raise TriggerError("Truncated ASN.1 INTEGER in ECDSA signature.")
        raw_int = der[offset : offset + int_len]
        offset += int_len

        stripped = raw_int.lstrip(b"\x00")
        if len(stripped) > 32:
            raise TriggerError("ECDSA integer component exceeds 32 bytes for P-256.")
        parts.append(stripped.rjust(32, b"\x00"))

    if offset != len(der):
        raise TriggerError("Unexpected trailing bytes in ECDSA DER signature.")
    return parts[0] + parts[1]


def generate_asc_jwt(
    *,
    key_id: str,
    issuer_id: str,
    private_key_pem: str,
    now: int | None = None,
    ttl_seconds: int = 600,
) -> str:
    """Mint a short-lived ES256 App Store Connect API JWT using `openssl`."""
    issued_at = int(time.time()) if now is None else now
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    payload = {
        "iss": issuer_id,
        "iat": issued_at,
        "exp": issued_at + ttl_seconds,
        "aud": "appstoreconnect-v1",
    }
    signing_input = (
        f"{_b64url(json.dumps(header, separators=(',', ':')).encode('utf-8'))}."
        f"{_b64url(json.dumps(payload, separators=(',', ':')).encode('utf-8'))}"
    ).encode("ascii")

    with tempfile.TemporaryDirectory(prefix="asc-jwt-") as tmpdir:
        key_path = os.path.join(tmpdir, "key.p8")
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(private_key_pem)
        except Exception:
            try:
                os.close(fd)
            except OSError:
                pass
            raise

        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input,
            capture_output=True,
            check=False,
        )

    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise TriggerError(f"openssl failed to sign App Store Connect JWT: {detail}")

    signature = der_to_p1363_es256(result.stdout)
    return f"{signing_input.decode('ascii')}.{_b64url(signature)}"


def _validate_api_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != ALLOWED_API_HOST:
        raise TriggerError(f"Refusing to send App Store Connect credentials to {url!r}.")
    return url


def _format_api_error(status: int, body: bytes) -> str:
    try:
        parsed = json.loads(body.decode("utf-8", errors="replace"))
        errors = parsed.get("errors")
        if isinstance(errors, list) and errors:
            messages = []
            for err in errors:
                title = err.get("title", "")
                detail = err.get("detail", "")
                code = err.get("code", "")
                part = ": ".join(x for x in (code, title, detail) if x)
                if part:
                    messages.append(part)
            if messages:
                return f"HTTP {status}: " + " | ".join(messages)
    except Exception:
        pass
    snippet = body.decode("utf-8", errors="replace").strip()[:400]
    return f"HTTP {status}: {snippet or 'empty error response'}"


class AppStoreConnectClient:
    """Minimal App Store Connect API client for Xcode Cloud build triggers."""

    def __init__(self, token: str, timeout_seconds: int = 30) -> None:
        self._token = token
        self._timeout = timeout_seconds

    def request_json(
        self,
        method: str,
        path_or_url: str,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
            url = _validate_api_url(path_or_url)
        else:
            url = _validate_api_url(f"{API_BASE_URL}{path_or_url}")

        data = None
        headers = {
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/json",
        }
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            body = exc.read()
            raise TriggerError(
                f"App Store Connect API {method} {urllib.parse.urlparse(url).path} failed: "
                f"{_format_api_error(exc.code, body)}"
            ) from exc
        except urllib.error.URLError as exc:
            raise TriggerError(
                f"Network error calling App Store Connect API: {exc.reason}"
            ) from exc

        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def paginate(self, path: str, max_pages: int = 20) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        next_url: str | None = path
        pages = 0
        while next_url and pages < max_pages:
            body = self.request_json("GET", next_url)
            data = body.get("data")
            if isinstance(data, list):
                items.extend(data)
            next_url = (body.get("links") or {}).get("next")
            pages += 1
        return items


def resolve_workflow(
    client: AppStoreConnectClient,
    *,
    channel: str,
    workflow_id: str | None,
    app_id: str | None,
    workflow_name: str | None,
) -> tuple[str, str]:
    """Return `(workflow_id, workflow_name)` for the target channel."""
    if workflow_id:
        safe_id = urllib.parse.quote(workflow_id, safe="")
        resp = client.request_json("GET", f"/v1/ciWorkflows/{safe_id}")
        data = resp.get("data") or {}
        resolved_id = data.get("id") or workflow_id
        resolved_name = (data.get("attributes") or {}).get("name") or workflow_id
        return resolved_id, resolved_name

    if not app_id or not workflow_name:
        raise TriggerError(
            f"No Xcode Cloud workflow configured for channel {channel!r}. "
            f"Set ASC_XCODE_CLOUD_{channel.upper()}_WORKFLOW_ID, or set both "
            f"ASC_APP_ID and ASC_XCODE_CLOUD_{channel.upper()}_WORKFLOW_NAME."
        )

    safe_app_id = urllib.parse.quote(app_id, safe="")
    product_resp = client.request_json("GET", f"/v1/apps/{safe_app_id}/ciProduct")
    product_id = (product_resp.get("data") or {}).get("id")
    if not product_id:
        raise TriggerError(f"App {app_id!r} has no Xcode Cloud product configured.")

    safe_product_id = urllib.parse.quote(product_id, safe="")
    workflows = client.paginate(f"/v1/ciProducts/{safe_product_id}/workflows?limit=200")
    available: list[str] = []
    for wf in workflows:
        name = (wf.get("attributes") or {}).get("name") or ""
        available.append(name)
        if name == workflow_name:
            return wf["id"], name

    raise TriggerError(
        f"Xcode Cloud workflow {workflow_name!r} not found for app {app_id!r}. "
        f"Available workflows: {', '.join(sorted(available)) or '(none)'}"
    )


def find_git_reference_id(
    client: AppStoreConnectClient,
    *,
    repository_id: str,
    tag: str,
    poll_timeout_seconds: int,
    poll_interval_seconds: int,
    sleep_fn=time.sleep,
    monotonic_fn=time.monotonic,
) -> str:
    """Wait until App Store Connect indexes `refs/tags/<tag>` and return its ID."""
    canonical = f"refs/tags/{tag}"
    safe_repo_id = urllib.parse.quote(repository_id, safe="")
    deadline = monotonic_fn() + max(0, poll_timeout_seconds)
    attempt = 0

    while True:
        attempt += 1
        refs = client.paginate(
            f"/v1/scmRepositories/{safe_repo_id}/gitReferences?limit=200"
        )
        for ref in refs:
            attrs = ref.get("attributes") or {}
            if attrs.get("isDeleted"):
                continue
            if attrs.get("canonicalName") == canonical:
                return ref["id"]

        now = monotonic_fn()
        if now >= deadline:
            raise TriggerError(
                f"Timed out after {attempt} attempt(s) waiting for App Store Connect "
                f"to index Git tag {canonical!r} in repository {repository_id!r}."
            )
        wait_seconds = min(poll_interval_seconds, max(0.0, deadline - now))
        print(
            f"Tag {canonical} not yet indexed by App Store Connect "
            f"(attempt {attempt}); retrying in {wait_seconds:.0f}s...",
            flush=True,
        )
        sleep_fn(wait_seconds)


def find_active_build_run(
    client: AppStoreConnectClient,
    *,
    workflow_id: str,
    git_ref_id: str,
) -> dict[str, Any] | None:
    """Return an existing PENDING/RUNNING build run for the same tag, if any."""
    safe_wf_id = urllib.parse.quote(workflow_id, safe="")
    resp = client.request_json("GET", f"/v1/ciWorkflows/{safe_wf_id}/buildRuns?limit=20")
    for run in resp.get("data") or []:
        attrs = run.get("attributes") or {}
        progress = attrs.get("executionProgress")
        if progress not in ACTIVE_PROGRESS_STATES:
            continue
        rel_ref = (
            ((run.get("relationships") or {}).get("sourceBranchOrTag") or {}).get("data")
            or {}
        )
        if rel_ref.get("id") == git_ref_id:
            return run
    return None


def start_build_run(
    client: AppStoreConnectClient,
    *,
    workflow_id: str,
    git_ref_id: str,
    clean: bool = False,
) -> dict[str, Any]:
    """Call POST /v1/ciBuildRuns to start an Xcode Cloud build for `git_ref_id`."""
    data: dict[str, Any] = {
        "type": "ciBuildRuns",
        "relationships": {
            "workflow": {
                "data": {"type": "ciWorkflows", "id": workflow_id},
            },
            "sourceBranchOrTag": {
                "data": {"type": "scmGitReferences", "id": git_ref_id},
            },
        },
    }
    if clean:
        data["attributes"] = {"clean": True}

    resp = client.request_json("POST", "/v1/ciBuildRuns", payload={"data": data})
    return resp.get("data") or {}


def select_workflow_config(
    channel: str, env: dict[str, str]
) -> tuple[str | None, str | None, str | None]:
    """Select `(workflow_id, app_id, workflow_name)` for `staging` or `prod`."""
    app_id = (env.get("ASC_APP_ID") or "").strip() or None
    if channel == "staging":
        workflow_id = (
            (env.get("ASC_XCODE_CLOUD_STAGING_WORKFLOW_ID") or "").strip()
            or (env.get("ASC_XCODE_CLOUD_WORKFLOW_ID") or "").strip()
            or None
        )
        workflow_name = (
            (env.get("ASC_XCODE_CLOUD_STAGING_WORKFLOW_NAME") or "").strip()
            or (env.get("ASC_XCODE_CLOUD_WORKFLOW_NAME") or "").strip()
            or None
        )
        return workflow_id, app_id, workflow_name

    if channel == "prod":
        workflow_id = (
            (env.get("ASC_XCODE_CLOUD_PROD_WORKFLOW_ID") or "").strip() or None
        )
        workflow_name = (
            (env.get("ASC_XCODE_CLOUD_PROD_WORKFLOW_NAME") or "").strip() or None
        )
        return workflow_id, app_id, workflow_name

    raise TriggerError(f"Unsupported channel {channel!r}.")


def _write_step_summary(lines: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Trigger an Xcode Cloud build run via the App Store Connect API."
    )
    parser.add_argument("--tag", required=True, help="Release tag (e.g. staging/1.9.0)")
    parser.add_argument(
        "--skip-if-active",
        action="store_true",
        help="Do not create a duplicate run if an active build for this tag already exists.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Request a clean Xcode Cloud build.",
    )
    parser.add_argument(
        "--poll-timeout",
        type=int,
        default=120,
        help="Seconds to wait for App Store Connect to index the Git tag (default: 120).",
    )
    parser.add_argument(
        "--poll-interval",
        type=int,
        default=5,
        help="Seconds between gitReferences polls (default: 5).",
    )
    args = parser.parse_args(argv)

    env = dict(os.environ)
    channel, version = parse_release_tag(args.tag)

    key_id = (env.get("ASC_KEY_ID") or "").strip()
    issuer_id = (env.get("ASC_ISSUER_ID") or "").strip()
    raw_private_key = env.get("ASC_PRIVATE_KEY") or ""

    missing = [
        name
        for name, value in (
            ("ASC_KEY_ID", key_id),
            ("ASC_ISSUER_ID", issuer_id),
            ("ASC_PRIVATE_KEY", raw_private_key.strip()),
        )
        if not value
    ]
    if missing:
        raise TriggerError(
            "Missing required App Store Connect API secret(s): " + ", ".join(missing)
        )

    private_key_pem = normalize_private_key(raw_private_key)
    token = generate_asc_jwt(
        key_id=key_id,
        issuer_id=issuer_id,
        private_key_pem=private_key_pem,
    )
    client = AppStoreConnectClient(token)

    wf_id_cfg, app_id, wf_name_cfg = select_workflow_config(channel, env)
    workflow_id, workflow_name = resolve_workflow(
        client,
        channel=channel,
        workflow_id=wf_id_cfg,
        app_id=app_id,
        workflow_name=wf_name_cfg,
    )
    print(f"Resolved Xcode Cloud workflow {workflow_name!r} ({workflow_id}) for {args.tag}")

    safe_wf_id = urllib.parse.quote(workflow_id, safe="")
    repo_resp = client.request_json("GET", f"/v1/ciWorkflows/{safe_wf_id}/repository")
    repository_id = (repo_resp.get("data") or {}).get("id")
    if not repository_id:
        raise TriggerError(
            f"Xcode Cloud workflow {workflow_id!r} has no linked SCM repository."
        )

    git_ref_id = find_git_reference_id(
        client,
        repository_id=repository_id,
        tag=args.tag,
        poll_timeout_seconds=args.poll_timeout,
        poll_interval_seconds=args.poll_interval,
    )
    print(f"Found App Store Connect git reference {git_ref_id} for refs/tags/{args.tag}")

    if args.skip_if_active:
        existing = find_active_build_run(
            client, workflow_id=workflow_id, git_ref_id=git_ref_id
        )
        if existing:
            attrs = existing.get("attributes") or {}
            run_id = existing.get("id", "unknown")
            number = attrs.get("number", "?")
            progress = attrs.get("executionProgress", "UNKNOWN")
            print(
                f"Xcode Cloud build #{number} ({run_id}) is already {progress} for {args.tag}; "
                "skipping duplicate trigger."
            )
            _write_step_summary(
                [
                    f"### Xcode Cloud ({args.tag})",
                    f"- Workflow: **{workflow_name}** (`{workflow_id}`)",
                    f"- Status: already **{progress}** (build `#{number}`, id `{run_id}`) — skipped duplicate trigger.",
                ]
            )
            return 0

    build_run = start_build_run(
        client,
        workflow_id=workflow_id,
        git_ref_id=git_ref_id,
        clean=args.clean,
    )
    attrs = build_run.get("attributes") or {}
    run_id = build_run.get("id", "unknown")
    number = attrs.get("number", "?")
    progress = attrs.get("executionProgress", "PENDING")

    print(
        f"Started Xcode Cloud build #{number} ({run_id}) for {args.tag} "
        f"(version {version}, state: {progress})."
    )
    _write_step_summary(
        [
            f"### Xcode Cloud ({args.tag})",
            f"- Workflow: **{workflow_name}** (`{workflow_id}`)",
            f"- Build run: `#{number}` (`{run_id}`)",
            f"- State: **{progress}**",
        ]
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except TriggerError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
