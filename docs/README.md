# Kodakiko docs

## Using and running it

| Doc | What it is |
|---|---|
| [guide.md](guide.md) | What every screen and setting does |
| [operations.md](operations.md) | Running the server stack: DNS, first run, backups, restore drill, updates, going public, secret rotation |
| [screenshots/](screenshots/README.md) | Current screenshots and how they are made |

## Working on it

| Doc | What it is |
|---|---|
| [architecture.md](architecture.md) | Repository layout, layers, platform adapters, data flows |
| [encryption.md](encryption.md) | What is encrypted, the key hierarchy, where keys live |
| [development.md](development.md) | Toolchain, local stack, Android builds, releases, client conventions |
| [testing.md](testing.md) | Test commands, rules, where flows and fixtures live |
| [engineering-guidelines.md](engineering-guidelines.md) | The engineering rules every change follows |
| [dependencies.md](dependencies.md) | Allowed packages with versions and reasons, pnpm policy |
| [facts.md](facts.md) | Confirmed facts about third-party libraries and services, with sources (F-XXX-NN) |

## Design reference

Written before the build (2026-11-11) and kept as the detailed specification. Where one differs
from the code, the code and the feature specs' implementation notes are right.

| Doc | What it is |
|---|---|
| [design/crypto.md](design/crypto.md) | Keys, primitives, byte formats, flows, test vectors |
| [design/data-model.md](design/data-model.md) | Postgres schema, client RowStore, encrypted payload schemas, jobs |
| [design/api.md](design/api.md) | Every endpoint: schemas, errors, rate limits |
| [design/sync.md](design/sync.md) | Outbox, cursors, conflict merge, retries |
| [design/storage.md](design/storage.md) | Bucket adapter, object keys, uploads and downloads, web video streaming, caches, garbage collection |
| [design/media.md](design/media.md) | Capture, import, thumbnails, EXIF, time and place, link stripping |
| [design/client.md](design/client.md) | Navigation, screens, state, composition root, adapters |
| [design/security.md](design/security.md) | Threat model, key handling, headers, rate limits |
| [design/deployment.md](design/deployment.md) | Compose stack, private and public profiles, VPN, DNS-01 TLS, flags |

## Build history

How the app was planned and built, in order.

| Doc | What it is |
|---|---|
| [features/README.md](features/README.md) | The build order and its rules; one spec per feature (01 … 25), each ending with implementation notes on what changed during the build |
| [features/07c-ui-test-harness.md](features/07c-ui-test-harness.md) | UI test harness and the flow catalog (U-01 …) |
| [features/behaviors.md](features/behaviors.md) | Behavior catalog: Given / When / Then rows mapped to specs and tests |
| [planning/context.md](planning/context.md) | Interview log and every decision (D-NN) |
| `planning/` | Reviews of the plan, bug lists and plans for later versions |
| [adr/](adr/) | Architecture decision records 0001 … 0010 |
