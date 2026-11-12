# 3. Ente-style key hierarchy on libsodium, login key instead of SRP

Date: 2026-11-11. Status: accepted. Decisions: D-24, D-05.

## Context
Reuse an audited design (Ente, F-ENTE-01..12) without adding unaudited crypto dependencies
(D-11). SRP would require an extra library; our login secret is a 256-bit derived key, not a
low-entropy password.

## Decision
Argon2id -> KEK -> kdf subkeys; MK wrapped by KEK and by a 24-word recovery key; collection keys
under MK; entry keys under collection keys; media keys under entry keys. Login sends the
`kdklogin` subkey; the server stores SHA-256 of it (F-TOOL-09 permits fast hashes for 256-bit
secrets). AEAD wraps bind context + row id as additional data (crypto.md §6).

## Consequences
+ Only libsodium primitives; identical crypto on both clients proven by shared test vectors.
+ No password ever reaches the server; recovery key equals full access (security.md §1.2).
- A DB leak allows offline guessing only at Argon2id cost; weak passwords remain the main risk,
  so a client-side strength floor is mandatory (crypto.md §5.1).
- Deliberate departure from Ente (no SRP): recorded in crypto.md §11.
