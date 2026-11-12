# 5. Sharing is read-only

Date: 2026-11-11. Status: accepted. Decision: D-39 (replaces read/write in D-04).

## Context
Programmer: Instagram-story model — recipients view and read, never write. Removes collaborator
roles, write-permission checks and cross-user write conflicts from the whole system.

## Decision
Shares grant view/download only. Roles: owner and viewer. Entries have exactly one writer (their
owner); sync conflicts only occur between the same user's devices (sync.md §5).

## Consequences
+ Simpler server (no write ACLs), simpler merge logic, no multi-writer crypto concerns.
+ Removing a recipient does not rotate keys (D-31, like Ente F-ENTE-13); the UI states that
  already-seen content may have been kept.
- Collaborative journals are permanently out of scope unless a future ADR changes this.
