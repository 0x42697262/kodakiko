# 7. Local-first: ciphertext rows + in-memory index, no SQLCipher

Date: 2026-11-11. Status: accepted. Decisions: D-37, D-46.

## Context
Filtering/search must work offline and leak nothing at rest. SQLCipher (expo-sqlite plugin) was
considered; but if every stored value is already ciphertext or sealed-blob JSON, the DB itself
holds no secrets, and plaintext search needs decrypted data in memory anyway.

## Decision
Devices store server rows verbatim (ciphertext blobs) in plain SQLite/IndexedDB; on unlock the
client decrypts metadata into an in-memory index (tags, days, words) built by one pure
implementation in packages/core. An encrypted index snapshot stays reserved until measured unlock
exceeds budget with 20k entries (D-46).

## Consequences
+ One crypto boundary (sealed blobs), no native SQLCipher build risk, identical query code on
  Android and web.
+ At-rest leak yields only what the server itself holds (security.md §1.1).
- Unlock cost scales linearly with entry count (2 AEAD ops/entry); budget-checked, snapshot is the
  escape hatch.
- Android plain thumbnail cache (D-12) is the one plaintext-at-rest exception, opt-out provided.
