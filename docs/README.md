# Kodakiko docs

| Doc | What it is |
|---|---|
| `planning/context.md` | Interview log, every decision (D-NN), open questions, next step. Start here |
| `architecture.md` | System map, repository layout, layers, ports, main data flows, document map |
| `design/crypto.md` | Normative crypto spec: keys, primitives, byte formats, flows, test vectors |
| `design/data-model.md` | Postgres schema, client RowStore, encrypted payload schemas, jobs |
| `design/api.md` | Every endpoint: contract schemas, errors, rate limits |
| `design/sync.md` | Outbox, cursors, conflict merge, retries, triggers |
| `design/storage.md` | Bucket adapter, object keys, upload/download, web video streaming, caches, GC |
| `design/media.md` | Capture, import, thumbnails, EXIF/time/location, link stripping |
| `design/client.md` | Navigation, screens, state, composition root, platform adapters |
| `design/security.md` | Threat model, key handling, headers, rate limits, verification checklist |
| `design/deployment.md` | Compose stack, profiles private/public, VPN, DNS-01 TLS, backups, flags registry |
| `adr/` | One ADR per major decision (0001..0010) |
| `dependencies.md` | Dependency allowlist, pnpm policy, CI gates (D-11) |
| `operations.md` | Production operations: first run, DNS, firewall, backups, restore drill, updates, going public (written in spec 21) |
| `release-checklist.md` | Release checklist (written in spec 21) |
| `development.md` | Local HTTPS development stack, Android device setup (written in spec 07b) |
| `facts.md` | Confirmed facts about third parties with sources (F-XXX-NN) |
| `engineering-guidelines.md` | The programmer's engineering rules; apply to all work |
| `features/07c-ui-test-harness.md` | UI test harness and flow catalog (D-74) |
| `features/behaviors.md` | Behavior catalog: Given/When/Then rows mapped to specs and tests (D-72) |
| `features/` | Build specs for Sonnet, one per feature (not started) |
