# Kodakiko docs

Kodakiko is a native Android app in Kotlin (`apps/android`), a TypeScript server (`apps/server`)
and a standalone public link viewer (`apps/link-viewer`). There is no web app since v0.4.0.

## Using and running it

| Doc | What it is |
|---|---|
| [guide.md](guide.md) | What every screen and setting does |
| [operations.md](operations.md) | Running the server stack: DNS, first run, backups, restore drill, updates, going public, secret rotation |
| [screenshots/](screenshots/README.md) | Current screenshots of the Android app (Roborazzi baselines) and how they are made |

## Working on it

| Doc | What it is |
|---|---|
| [architecture.md](architecture.md) | Repository layout, the Android app's Gradle modules and layers, ports, data flows |
| [encryption.md](encryption.md) | What is encrypted, the key hierarchy, where keys live |
| [development.md](development.md) | Toolchain (JDK 21, Android SDK, Node), local stack, Android builds (debug, preview `.next`), releases, code conventions |
| [testing.md](testing.md) | `./gradlew verify`, `e2e`, Roborazzi, Compose reports, Maestro, the UI flow catalog |
| [behaviors.md](behaviors.md) | Every user-visible behavior and the test that proves it |
| [engineering-guidelines.md](engineering-guidelines.md) | The engineering rules every change follows |
| [dependencies.md](dependencies.md) | Allowed npm packages and Kotlin libraries with versions and reasons, adapted code and palettes, pnpm policy |
| [facts.md](facts.md) | Confirmed facts about third-party libraries and services, with sources (F-XXX-NN) |

## Design reference

Written before the build (2026-11-11) and kept as the detailed specification. Where one differs
from the code, the code and the feature docs are right. Their client parts describe the Expo app of
v0.1–v0.3 (Android and web); the protocol, crypto, data model, API and server parts still hold.

| Doc | What it is |
|---|---|
| [design/crypto.md](design/crypto.md) | Keys, primitives, byte formats, flows, test vectors |
| [design/data-model.md](design/data-model.md) | Postgres schema, client RowStore, encrypted payload schemas, jobs |
| [design/api.md](design/api.md) | Every endpoint: schemas, errors, rate limits |
| [design/sync.md](design/sync.md) | Outbox, cursors, conflict merge, retries |
| [design/storage.md](design/storage.md) | Bucket adapter, object keys, uploads and downloads, video streaming (the service worker now lives in the link viewer), caches, garbage collection |
| [design/media.md](design/media.md) | Capture, import, thumbnails, EXIF, time and place, link stripping |
| [design/client.md](design/client.md) | Navigation, screens, state, composition root, adapters (Expo client, historical) |
| [design/security.md](design/security.md) | Threat model, key handling, headers, rate limits |
| [design/deployment.md](design/deployment.md) | Compose stack, private and public profiles, VPN, DNS-01 TLS, flags |

## Features

[features/README.md](features/README.md) lists one doc per feature area (capture, edit page,
gallery, viewer, sync, sharing, …), each describing in detail how it works today.
[behaviors.md](behaviors.md) lists every user-visible behavior with the test that proves it.

## History

| Doc | What it is |
|---|---|
| [adr/](adr/) | Architecture decision records 0001 … 0011 (0011: the Kotlin Android app, which supersedes 0001 for the clients) |
