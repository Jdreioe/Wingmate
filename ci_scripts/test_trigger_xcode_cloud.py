#!/usr/bin/env python3
"""Unit tests for ci_scripts/trigger_xcode_cloud.py."""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trigger_xcode_cloud as txc  # noqa: E402


def _b64url_decode(segment: str) -> bytes:
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + padding)


def _p1363_to_der(sig: bytes) -> bytes:
    """Convert raw 64-byte ES256 (r || s) back to DER to verify with openssl."""
    assert len(sig) == 64

    def encode_int(val: bytes) -> bytes:
        trimmed = val.lstrip(b"\x00") or b"\x00"
        if trimmed[0] & 0x80:
            trimmed = b"\x00" + trimmed
        return bytes([0x02, len(trimmed)]) + trimmed

    body = encode_int(sig[:32]) + encode_int(sig[32:])
    return bytes([0x30, len(body)]) + body


class FakeClient:
    def __init__(self, responses: dict[tuple[str, str], list[dict]]):
        self.responses = {k: list(v) for k, v in responses.items()}
        self.calls: list[tuple[str, str, dict | None]] = []

    def request_json(self, method: str, path_or_url: str, payload=None):
        self.calls.append((method, path_or_url, payload))
        queue = self.responses.get((method, path_or_url))
        if not queue:
            raise AssertionError(f"Unexpected call: {method} {path_or_url}")
        return queue.pop(0)

    def paginate(self, path: str, max_pages: int = 20):
        items = []
        next_url = path
        pages = 0
        while next_url and pages < max_pages:
            body = self.request_json("GET", next_url)
            items.extend(body.get("data") or [])
            next_url = (body.get("links") or {}).get("next")
            pages += 1
        return items


class TriggerXcodeCloudTest(unittest.TestCase):
    def test_parse_release_tag(self):
        self.assertEqual(txc.parse_release_tag("staging/1.9.0"), ("staging", "1.9.0"))
        self.assertEqual(txc.parse_release_tag("prod/2.0.1"), ("prod", "2.0.1"))
        for bad in ("v1.9.0", "staging/1.9", "beta/1.9.0", "main", "staging/v1.9.0"):
            with self.assertRaises(txc.TriggerError):
                txc.parse_release_tag(bad)

    def test_normalize_private_key_formats(self):
        pem_header = "-----BEGIN PRIVATE KEY-----"
        pem_footer = "-----END PRIVATE KEY-----"
        raw = f"{pem_header}\nFAKEBODY\n{pem_footer}\n"
        escaped = f"{pem_header}\\nFAKEBODY\\n{pem_footer}"
        encoded = base64.b64encode(raw.encode("utf-8")).decode("ascii")

        self.assertEqual(txc.normalize_private_key(raw), raw)
        self.assertEqual(txc.normalize_private_key(escaped), raw)
        self.assertEqual(txc.normalize_private_key(encoded), raw)

        with self.assertRaises(txc.TriggerError):
            txc.normalize_private_key("   ")
        with self.assertRaises(txc.TriggerError):
            txc.normalize_private_key("not-valid-base64!!!")

    def test_generate_asc_jwt_produces_verifiable_es256_signature(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            priv_path = os.path.join(tmpdir, "priv.pem")
            pub_path = os.path.join(tmpdir, "pub.pem")
            sig_path = os.path.join(tmpdir, "sig.der")
            msg_path = os.path.join(tmpdir, "msg.txt")

            subprocess.run(
                [
                    "openssl",
                    "ecparam",
                    "-name",
                    "prime256v1",
                    "-genkey",
                    "-noout",
                    "-out",
                    priv_path,
                ],
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["openssl", "pkey", "-in", priv_path, "-pubout", "-out", pub_path],
                check=True,
                capture_output=True,
            )
            with open(priv_path, "r", encoding="utf-8") as handle:
                pem = handle.read()

            token = txc.generate_asc_jwt(
                key_id="KEY1234567",
                issuer_id="11111111-2222-3333-4444-555555555555",
                private_key_pem=pem,
                now=1_700_000_000,
                ttl_seconds=600,
            )

            parts = token.split(".")
            self.assertEqual(len(parts), 3)
            header = json.loads(_b64url_decode(parts[0]))
            payload = json.loads(_b64url_decode(parts[1]))
            sig_raw = _b64url_decode(parts[2])

            self.assertEqual(header, {"alg": "ES256", "kid": "KEY1234567", "typ": "JWT"})
            self.assertEqual(
                payload,
                {
                    "iss": "11111111-2222-3333-4444-555555555555",
                    "iat": 1_700_000_000,
                    "exp": 1_700_000_600,
                    "aud": "appstoreconnect-v1",
                },
            )
            self.assertEqual(len(sig_raw), 64)

            with open(msg_path, "wb") as handle:
                handle.write(f"{parts[0]}.{parts[1]}".encode("ascii"))
            with open(sig_path, "wb") as handle:
                handle.write(_p1363_to_der(sig_raw))

            verify = subprocess.run(
                [
                    "openssl",
                    "dgst",
                    "-sha256",
                    "-verify",
                    pub_path,
                    "-signature",
                    sig_path,
                    msg_path,
                ],
                capture_output=True,
                check=False,
            )
            self.assertEqual(verify.returncode, 0, verify.stderr.decode("utf-8"))

    def test_select_workflow_config_keeps_prod_isolated(self):
        env = {
            "ASC_APP_ID": "6743526254",
            "ASC_XCODE_CLOUD_WORKFLOW_ID": "wf-staging-generic",
            "ASC_XCODE_CLOUD_STAGING_WORKFLOW_NAME": "Default",
        }
        self.assertEqual(
            txc.select_workflow_config("staging", env),
            ("wf-staging-generic", "6743526254", "Default"),
        )
        # prod must never accidentally fall back to staging workflow config
        self.assertEqual(
            txc.select_workflow_config("prod", env),
            (None, "6743526254", None),
        )

    def test_resolve_workflow_by_name(self):
        client = FakeClient(
            {
                ("GET", "/v1/apps/6743526254/ciProduct"): [
                    {"data": {"id": "prod-1", "type": "ciProducts"}}
                ],
                ("GET", "/v1/ciProducts/prod-1/workflows?limit=200"): [
                    {
                        "data": [
                            {"id": "wf-1", "attributes": {"name": "Other"}},
                            {"id": "wf-2", "attributes": {"name": "Default"}},
                        ],
                        "links": {},
                    }
                ],
            }
        )
        self.assertEqual(
            txc.resolve_workflow(
                client,
                channel="staging",
                workflow_id=None,
                app_id="6743526254",
                workflow_name="Default",
            ),
            ("wf-2", "Default"),
        )

    def test_find_git_reference_id_polls_and_paginates(self):
        next_page = "https://api.appstoreconnect.apple.com/v1/scmRepositories/repo-1/gitReferences?cursor=abc"
        client = FakeClient(
            {
                ("GET", "/v1/scmRepositories/repo-1/gitReferences?limit=200"): [
                    # First poll: tag not synced yet (deleted match should be ignored)
                    {
                        "data": [
                            {
                                "id": "ref-deleted",
                                "attributes": {
                                    "canonicalName": "refs/tags/staging/1.9.0",
                                    "isDeleted": True,
                                },
                            }
                        ],
                        "links": {},
                    },
                    # Second poll: found on second page
                    {
                        "data": [
                            {
                                "id": "ref-main",
                                "attributes": {
                                    "canonicalName": "refs/heads/main",
                                    "isDeleted": False,
                                },
                            }
                        ],
                        "links": {"next": next_page},
                    },
                ],
                ("GET", next_page): [
                    {
                        "data": [
                            {
                                "id": "ref-tag-190",
                                "attributes": {
                                    "canonicalName": "refs/tags/staging/1.9.0",
                                    "isDeleted": False,
                                },
                            }
                        ],
                        "links": {},
                    }
                ],
            }
        )
        now = [0.0]

        def fake_monotonic():
            return now[0]

        def fake_sleep(seconds):
            now[0] += seconds

        ref_id = txc.find_git_reference_id(
            client,
            repository_id="repo-1",
            tag="staging/1.9.0",
            poll_timeout_seconds=30,
            poll_interval_seconds=5,
            sleep_fn=fake_sleep,
            monotonic_fn=fake_monotonic,
        )
        self.assertEqual(ref_id, "ref-tag-190")
        self.assertEqual(now[0], 5.0)

    def test_find_active_build_run_detects_running_and_ignores_complete(self):
        client = FakeClient(
            {
                ("GET", "/v1/ciWorkflows/wf-1/buildRuns?limit=20"): [
                    {
                        "data": [
                            {
                                "id": "run-old",
                                "attributes": {"executionProgress": "COMPLETE", "number": 10},
                                "relationships": {
                                    "sourceBranchOrTag": {"data": {"id": "ref-1"}}
                                },
                            },
                            {
                                "id": "run-live",
                                "attributes": {"executionProgress": "RUNNING", "number": 11},
                                "relationships": {
                                    "sourceBranchOrTag": {"data": {"id": "ref-1"}}
                                },
                            },
                        ]
                    }
                ]
            }
        )
        active = txc.find_active_build_run(client, workflow_id="wf-1", git_ref_id="ref-1")
        self.assertIsNotNone(active)
        self.assertEqual(active["id"], "run-live")

    def test_start_build_run_payload(self):
        client = FakeClient(
            {
                ("POST", "/v1/ciBuildRuns"): [
                    {
                        "data": {
                            "id": "run-99",
                            "attributes": {"number": 42, "executionProgress": "PENDING"},
                        }
                    },
                    {
                        "data": {
                            "id": "run-100",
                            "attributes": {"number": 43, "executionProgress": "PENDING"},
                        }
                    },
                ]
            }
        )
        txc.start_build_run(client, workflow_id="wf-1", git_ref_id="ref-1", clean=False)
        txc.start_build_run(client, workflow_id="wf-1", git_ref_id="ref-1", clean=True)

        self.assertEqual(
            client.calls[0][2],
            {
                "data": {
                    "type": "ciBuildRuns",
                    "relationships": {
                        "workflow": {"data": {"type": "ciWorkflows", "id": "wf-1"}},
                        "sourceBranchOrTag": {
                            "data": {"type": "scmGitReferences", "id": "ref-1"}
                        },
                    },
                }
            },
        )
        self.assertEqual(client.calls[1][2]["data"]["attributes"], {"clean": True})

    def test_validate_api_url_blocks_non_apple_hosts(self):
        self.assertEqual(
            txc._validate_api_url("https://api.appstoreconnect.apple.com/v1/ciBuildRuns"),
            "https://api.appstoreconnect.apple.com/v1/ciBuildRuns",
        )
        for bad in (
            "http://api.appstoreconnect.apple.com/v1/ciBuildRuns",
            "https://evil.example.com/v1/ciBuildRuns",
        ):
            with self.assertRaises(txc.TriggerError):
                txc._validate_api_url(bad)


if __name__ == "__main__":
    unittest.main()
