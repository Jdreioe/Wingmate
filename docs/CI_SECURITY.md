# CI and supply-chain checks

Pull requests and pushes to `main` run independent jobs for each supported
client and security boundary. The jobs are separate so a failure identifies the
affected surface directly.

| Job | What it exercises | Failure policy |
| --- | --- | --- |
| Android verification | Debug compilation, lint, JVM/unit tests, and focused emulator UI tests | Any failure blocks the check |
| Linux Kotlin verification | Shared/Linux JVM tests and the canonical `packageLinux` fat-JAR task | Any failure blocks the check |
| Linux Rust verification | rustfmt, Clippy, tests, and the RustSec advisory database | Formatting differences, any Clippy warning, test failure, or non-ignored vulnerability blocks the check |
| iOS verification | Kotlin/Native simulator framework linking, Swift app compilation, and Swift unit tests | Any failure blocks the check |
| OpenSymbols Worker verification | Clean npm install, high-or-critical npm audit, Vitest, and TypeScript checking | High-or-critical vulnerabilities or build/test failures block the check |
| Dependency vulnerability review | Dependency changes reported by GitHub for Gradle, Cargo, npm, and workflow actions | Newly introduced high-or-critical vulnerabilities block the check |
| Repository secret scan | Gitleaks over changed commits on PRs/pushes and the full fetched history weekly, with reports and PR comments disabled | High-confidence committed credentials block the check |
| Client secret boundary | Project-specific checks for credentials in Android, iOS, Linux, and shared client code | Forbidden client-side credential handling blocks the check |

The Gradle dependency graph is submitted to GitHub after changes reach `main`.
Cargo and npm lockfiles are committed, and Dependabot opens weekly reviewed
updates for Gradle, Cargo, the Worker, and SHA-pinned GitHub Actions. Dependency
metadata is submitted to GitHub; source files and AAC user data are not sent to
third-party scanning services. Gitleaks result artifacts are deliberately
disabled because a finding could itself contain sensitive material.

The Gitleaks configuration has narrow rule-specific exceptions for fixed local
storage identifiers, Open Board Format `library_key` fields in retired public
starter fixtures, and Firebase client project identifiers. It does not ignore
whole commits or disable generic credential rules for source directories.

The first full-history baseline scan also found a legacy subscription key and
keystore password in the retired Java client history. The Azure credential was
confirmed invalid and the old keystore password does not match either active
signing password. Their exact finding fingerprints are recorded in the narrow
`.gitleaksignore`; new credentials and the same values in any other location
still fail the scan.

## Required repository rule

The `main` branch ruleset must require pull requests and every pull-request job
in `.github/workflows/security.yml` and `.github/workflows/secret-scan.yml`
before merge. Workflow files can define and fail checks, but GitHub repository
rules are what make those checks mandatory.

## Dependency advisories

The Linux client resolves app-runtime Netty to patched 4.2.17.Final via
`libs.netty.bom` (linuxApp). Gradle dependencies also resolve patched transitive
builds for BouncyCastle (1.85.x), jose4j (0.9.6), jdom2 (2.0.6.1), and
commons-lang3 (3.20.0) through `resolutionStrategy` forces applied to both the
build/plugin classpath and every project configuration, so project classpaths do
not fall back to the still-vulnerable commons-lang3 3.16.0 pulled in by
`usb4java`/`commons-compress` (linuxApp) and Compose tooling (androidApp).

The dependency-submission workflow excludes `detachedConfiguration.*` from the
submitted graph. AGP tooling (lint, UTP emulator control → grpc-netty → old
Netty 4.1.x) and Kotlin toolchain pieces (swift-export-embeddable →
opentelemetry-api 1.41.0) resolve build-time-only dependencies in detached
configurations that ignore `resolutionStrategy` forces. None of these are
packaged into any shipped client artifact, so they are excluded from the graph
Dependabot evaluates instead of being forced or individually dismissed.

One remaining advisory is an accepted risk because it is pinned by the Kotlin
toolchain itself and has no safe upstream fix short of moving to a Kotlin beta:

- `kotlin-gradle-plugin` GHSA-r937-wjx7-w2jp (medium): unsafe deserialization in
  the Kotlin build cache; patched in Kotlin `2.4.20-Beta1`, while the project
  builds on stable Kotlin 2.4.10. Revisit on the next stable Kotlin release.

The `Dependency vulnerability review` job rejects newly introduced high-or-critical
advisories, so these two cannot silently regrow past the accepted list above.

### Rust (libcosmic transitive dependencies)

The Linux client uses `libcosmic` (COSMIC desktop framework) as a `git` dependency.
Its transitive dependencies currently include two unpatched `unsound` advisories
and three `unmaintained`/`yanked` warnings that cannot be resolved without forking
`libcosmic` or waiting for its maintainers to update:

- `lru` 0.16.4 — RUSTSEC-2026-0253 (unsound, use-after-free in `LruCache::pop()`);
  fixed in 0.18.2 but `cryoglyph` (libcosmic dep) requires `^0.16`
- `memmap2` 0.8.0 — RUSTSEC-2026-0186 (unsound, unchecked pointer offset);
  fixed in 0.9.11 but `xkbcommon` (libcosmic dep) requires `^0.8.0`
- `paste` 1.0.15 — RUSTSEC-2024-0436 (unmaintained)
- `rustybuzz` 0.20.1 — RUSTSEC-2026-0206 (unmaintained)
- `ttf-parser` 0.25.1 — RUSTSEC-2026-0192 (unmaintained)
- `zune-core` 0.5.2 — yanked; pinned to patched 0.5.3 as a direct dependency

These are accepted risks because `libcosmic` is at its latest upstream commit
(`1f8d878`). The attack surface requires malicious input to the affected parsers
(fonts, images) which the AAC app does not process from untrusted sources.
Monitor `libcosmic` releases and re-evaluate when transitive deps are updated.

## Deliberate hardware exclusions

CI does not exercise physical gaze trackers, USB/FTDI partner-window hardware,
real microphones or speakers, Android devices, iPhones/iPads, signing, store
submission, or live Azure/OpenSymbols credentials. Those paths need dedicated
hardware or release validation; CI covers their compile-time boundaries and
software-only tests without accessing user communication data.
