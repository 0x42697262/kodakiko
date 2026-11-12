# 4. Seekable STREAM media format (age-style), not secretstream

Date: 2026-11-11. Status: accepted. Decision: D-38. Supersedes D-30.

## Context
libsodium secretstream chains chunk nonces, so random access is impossible (F-CRY-01); Ente
therefore decrypts whole videos in memory on web (F-ENTE-15) or builds separate HLS previews.
D-19 (no length cap) + D-32 (seeking) forbid that.

## Decision
Media objects use the STREAM construction from age's spec (F-CRY-02) on
crypto_aead_xchacha20poly1305_ietf: 1 MiB chunks, nonce = 16-byte random prefix || 7-byte
counter || final flag, AD = 24-byte header (crypto.md §7). The AEAD function is exposed natively
by react-native-libsodium (F-LIB-01), so no patch/fork is needed (D-30 void).

## Consequences
+ Constant-memory encrypt/decrypt; true random access for web video via service worker
  (storage.md §5); Android and web produce byte-identical objects (test vectors, crypto.md §10).
+ Small objects: sealed blobs (metadata) still use one-shot AEAD; only media uses STREAM.
- A bespoke container ("KDKS"): mitigated by following age's published construction exactly and
  by known-answer vectors; format is versioned and migrate-on-read (guidelines 4.4).
