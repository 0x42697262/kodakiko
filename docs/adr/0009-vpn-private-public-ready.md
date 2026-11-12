# 9. VPN-only by default, public-ready by construction

Date: 2026-11-11. Status: accepted. Decisions: D-36, D-40, D-41.

## Context
Programmer hosts at home, reachable through Tailscale/NetBird/Headscale; wants the option to open
publicly later without rework.

## Decision
Apps only know HTTPS base URLs; DNS-01 certificates via the operator's domain; nothing public in
the private profile. Anti-abuse controls (rate limits, quotas, no username enumeration, password
floor, headers) are built from day one and active in both profiles; going public is an ingress +
config checklist (deployment.md §6).

## Consequences
+ Offline-first already covers VPN-off (sync.md §8).
+ Public links keep working in the private profile for VPN members; for true public viewers a
  ciphertext proxy route exists (PUBLIC_LINK_PROXY).
- Operator must run a VPN client on every device; documented, not automated.
