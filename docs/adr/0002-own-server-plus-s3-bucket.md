# 2. Own thin server + any S3-compatible bucket, all content E2EE

Date: 2026-11-11. Status: accepted. Decisions: D-01, D-02.

## Context
Storage must be portable across Apple/Google/home server/paid hosting, encrypted so the provider
is irrelevant. No-server designs (Drive/WebDAV direct) make multi-user sync and sharing much
harder. Ente proves the model (F-ENTE-16).

## Decision
A small Fastify server stores only ciphertext rows, auth material, sharing state and sync
sequences; media ciphertext goes to any S3-compatible bucket through presigned URLs. Devices keep
local encrypted copies (D-02, D-09, D-12).

## Consequences
+ One adapter for Garage/B2/R2/any S3; the server never proxies media (storage.md §8).
+ Server and bucket compromises yield ciphertext (security.md §1).
- A server must run somewhere; compose stack + VPN profile makes it a home appliance
  (deployment.md).
- Traffic metadata (sizes/timing) is visible to both parties (security.md §1.2).
