# 10. Dependency allowlist and supply-chain gates

Date: 2026-11-11. Status: accepted. Decision: D-11.

## Context
Programmer: libraries and deps must be audited for security issues. Crypto must come only from
audited libsodium (F-LIB-04).

## Decision
docs/dependencies.md is the allowlist (exact versions, purpose, review date). pnpm-workspace.yaml
carries minimumReleaseAge, allowBuilds (no unapproved install scripts), saveExact. CI: pnpm audit
+ OSV-Scanner fail builds, license check, pnpm sbom per release. Renovate with delay. Sonnet may
not add dependencies not on the list; that is an Opus/programmer decision (AGENTS.md).

## Consequences
+ Supply-chain surface is explicit and reviewed; crypto wrappers proven equivalent by shared test
  vectors (crypto.md §10).
- New releases wait out the delay window; security patches use minimumReleaseAgeExclude
  deliberately.
