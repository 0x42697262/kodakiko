# 6. Public links: key-in-fragment, stripped copies

Date: 2026-11-11. Status: accepted. Decisions: D-10, D-48, D-25.

## Context
Share with people who have no account, without the server ever holding a key. 1Password's Psst
design is the proven model (F-1P-01..05). D-25 requires originals to keep EXIF, so links need
stripped derivatives.

## Decision
Link secret (256-bit) lives only in the URL fragment; server stores SHA-256 of a derived access
token. Links serve `link_copy` entries: re-encoded photos without metadata, no location, optional
note, fresh media keys in a dedicated `link` collection. Expiry presets and one-time views as in
1Password. Revoke deletes the collection and its objects.

## Consequences
+ Server compromise cannot decrypt link content it never had keys for; viewers cannot enumerate.
- Already-downloaded content survives revocation (stated in UI, F-1P-05).
- Video in public links is limited in v1 (re-mux or exclude; media.md §6) pending a container
  check.
