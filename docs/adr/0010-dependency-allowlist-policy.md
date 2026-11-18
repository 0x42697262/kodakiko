# 10. Dependency allowlist and supply-chain gates

Date: 2026-11-11. Status: accepted. Decision: D-11.

## Context
Programmer: libraries and deps must be audited for security issues. Crypto must come only from
audited libsodium (F-LIB-04).

## Decision
docs/dependencies.md is the allowlist (exact versions, purpose, review date). pnpm-workspace.yaml
carries minimumReleaseAge, allowBuilds (no unapproved install scripts), saveExact. CI: pnpm audit
+ OSV-Scanner fail builds, license check, pnpm sbom per release. Renovate with delay. Nobody adds
a dependency that is not on the list without the programmer's decision (AGENTS.md). Since v0.4.0
the same rule covers the Android app's Gradle catalog (`apps/android/gradle/libs.versions.toml`,
checked against the list by `CatalogAllowlistTest`).

## Consequences
+ Supply-chain surface is explicit and reviewed; crypto wrappers proven equivalent by shared test
  vectors (crypto.md §10).
- New releases wait out the delay window; security patches use minimumReleaseAgeExclude
  deliberately.
