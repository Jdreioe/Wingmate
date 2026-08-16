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

## Deliberate hardware exclusions

CI does not exercise physical gaze trackers, USB/FTDI partner-window hardware,
real microphones or speakers, Android devices, iPhones/iPads, signing, store
submission, or live Azure/OpenSymbols credentials. Those paths need dedicated
hardware or release validation; CI covers their compile-time boundaries and
software-only tests without accessing user communication data.
