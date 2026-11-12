# Engineering guidelines (project-agnostic)

Written 2026-11-11 for new sessions (any LLM or human) working in **any** repository for this
programmer. It merges two sources:

- **[P] the programmer's "Architecture and design principles"** (layers, hard rules, DDIA principles,
  client/server contract, lifecycle, testing, documentation, working rules, stack mappings);
- **[C] practice distilled from a TypeScript codebase whose design the programmer endorses**
  (a compiler with a CLI, a static web page and a website client).

Nothing here depends on a specific project, language or domain. Stack examples are in section 13.
Where the two sources disagreed, the resolution is stated in place and every conflict is listed in
section 14. Rules marked **(hard rule)** are non-negotiable; the rest is strong guidance.

How to use this file:

1. Read the target repository's own instruction files first (`AGENTS.md`, `CLAUDE.md`,
   `CONTRIBUTING.md`, README). **Repository rules win over this file**, including over hard rules
   here (a repository may be stricter or have a recorded reason to differ).
2. Apply sections 1–5 always; the others when the task touches their area.
3. Run the checklist in section 15 before saying a task is done.

---

## 1. Working with the programmer

- **Act when you have enough information.** Ask only when a decision is genuinely theirs (scope, risk,
  cost, product behavior) and cannot be read from the code or a sensible default. Batch questions;
  put the recommended option first.
- **Discuss before building** when the programmer writes "discuss", or when a request hits a blocking
  technical constraint: state the constraint plainly, give the options with their costs, recommend
  one, and stop.
- **Say what you will do** in one or two sentences before starting; give a one-line update when a
  major phase starts or the plan changes.
- **Report faithfully:** what was done, what was verified and how, what was not, and what was left
  behind (files, remote state, created resources). Never claim a test passed that was not run. Mark
  unverified claims "UNVERIFIED".
- **Earlier answers are binding.** If the programmer answers "what I said earlier", find their earlier
  statement and apply exactly that; do not ask again.
- **Approval for a destructive action covers only the named target** ("test on resource X"). Never
  extend it to other targets or to a later context.
- **Never print secrets** (credentials, tokens, password hashes, session files, network logs). Pass
  them through environment variables without echoing; keep them out of output, commits, docs and
  fixtures.
- **Commit only when asked**, one commit per logical change; do not push unless asked. No
  `Co-Authored-By` or other attribution lines unless the repository asks for them.
- **Check the actual source or docs** of a library, vendor or third-party site before relying on a
  behavior, and record it as a confirmed fact (section 11) [P].

---

## 2. Core idea [P]

The application is a set of plain business rules surrounded by thin layers that talk to the outside
world (HTTP, database, queues, third-party APIs, the browser, the terminal, the file system). Keep the
rules independent of frameworks and I/O, keep the boundaries thin and explicit, and let data flow one
way:

    request/event -> entry (route/controller/CLI command/listener/job) -> service
                  -> domain rules (pure) -> adapters (DB, APIs, mail, queue, files)

- Entry points receive input and validate it.
- Services orchestrate a use case and own transactions.
- Domain rules decide.
- Adapters talk to the outside world.

---

## 3. Layers [P + C]

Each module or class has one job. Keep these layers apart.

| Layer | Job | May depend on | Must not |
|---|---|---|---|
| Entry (routes, controllers, CLI commands, queue listeners, cron jobs, webhooks) | Parse and validate input, call one service method, shape the response or output and exit code | Services, DTOs, validation, text catalog | Contain business rules, query the DB or call vendors directly |
| Services / use cases | Run one use case end to end: load data, call rules, persist, emit events, own the transaction, order the checks (3.2) | Domain rules, repositories, gateways | Know about HTTP, request objects, argv, terminals or views |
| Domain rules | Pure logic: pricing, eligibility, state transitions, ownership, schedules, calculations | Other domain rules, value objects, settings | Do I/O, read the clock or env, import framework code |
| Adapters (repositories, gateways, clients) | Talk to the DB, payment providers, email, storage, external APIs and websites. Translate to and from plain domain objects | DB/ORM, SDKs, HTTP clients | Make business decisions |
| Protocol helpers (inside an adapter) | Pure functions that build requests (headers, cookies, bodies) and parse responses of one external system | Keys/constants | Do I/O |
| Config | Typed, validated settings loaded once at boot | Environment | Be read ad hoc with `env()` / `process.env` deep in the code |
| Text / i18n | Every user-facing string, email copy, error message and log message | Nothing | Be inlined in logic |
| Frontend | Presentation and interaction | The API contract | Be the authority on prices, permissions or state |

Rules of thumb:

- **Thin entry points.** A controller or CLI command is about 5 to 15 lines: validate, call a
  service, return a resource/DTO or print and return an exit code [P]. (Resolves conflict C-8: in
  [C] the CLI `main` also ordered the checks of a use case; that belongs in a service.)
- **Pure domain rules.** Pass `now`, IDs and settings in as arguments [P].
- **Adapters hide vendors.** Code depends on `PaymentGateway` / `SiteClient`, not on the vendor SDK or
  the site's HTML. Swapping a vendor touches one adapter [P]. A client factory takes its transport as
  a parameter (`createClient(account, fetcher = fetch)`) so tests and other runtimes can swap it [C].
- **One wiring place.** A composition root (or a framework container) builds the object graph and
  passes it down; nothing else `new`s up infrastructure [P]. For a CLI, the entry file builds a
  `context` (output sinks, settings, prompts or `null` without a terminal, transport, clock) and
  passes it to `main(argv, context)`; tests call the same `main` with fakes, never module mocks [C].
- **Portable logic.** Keep runtime-specific imports (crypto, fs) out of pure modules so they can be
  bundled for a browser later [C].
- **Small pure functions, frozen lookup tables** (`Object.freeze({...})` or the language's equivalent)
  and dispatch maps instead of long `if` chains [C, P 6.3 evolvability].
- **Typed boundaries:** read-only types for data that crosses modules; parse untrusted input once at
  the boundary with a type guard or schema [C, P 7.1].
- **Errors are small named classes** (`class SessionError extends Error {}`), one per failure family;
  the entry maps each to a message (from the error catalog) and an exit/HTTP code. Unexpected errors
  are re-thrown, never swallowed [C].

### 3.1 Read-only versus writable resources [C]

When users may read resources owned by others but write only their own, enforce "never write
others'" in the **domain rule and service** (local ownership rule **and** a check against the system
of record, e.g. the user's own list), and on the **server** when there is one; a frontend check is
only a convenience (section 8). One plain refusal message says the resource is read-only.

### 3.2 Order of checks in a use case [C]

Cheap and local first, remote last, destructive only after every check passed:

1. input validation (usage errors);
2. permission/ownership rules decidable locally;
3. input file/payload validation, **before any outbound request**;
4. remote preconditions (the target exists and belongs to the user);
5. optional safety step (backup) — **only if the user asked**;
6. the destructive operation;
7. read back and compare; a mismatch is a **warning**, not a failure.

### 3.3 Denying access [C]

When a resource cannot be read (missing, private, forbidden), return **one** message that does not
reveal which case it is ("isn't yours and isn't shared, or doesn't exist"). Do not auto-retry a denial
(retries are for transient failures, section 7.1).

---

## 4. Hard rules

### 4.1 No explanatory comments in code (hard rule) [P, C]

Code explains *what* it does through names, small functions and small types. The *why* (business
reasons, constraints, rejected alternatives, third-party quirks) goes in `docs/` (one file per topic)
or an ADR. Comments rot; docs get reviewed.

Allowed exceptions [P]: annotations tools need (PHPDoc generics, JSDoc types where TypeScript isn't
used), lint-disable directives whose reason is in the docs, license headers. (Conflict C-1: [C]
allowed none. A repository whose linter bans all comments keeps banning them.)

### 4.2 No magic values (hard rule) [P, C]

Every number, duration, limit, status, role, route name, queue name, cache key and string lives in
exactly one named place:

| Kind | Where |
|---|---|
| Values an operator may tune per environment (limits, timeouts, rates, feature flags) | Typed config (`config/*.php`, `src/config/index.ts`), sourced from env, with defaults |
| Fixed internal constants (page sizes, retry counts, cache TTLs, status codes, file modes, schema versions) | A `constants` / `Tuning` module per area |
| Statuses, roles, types | Enums (PHP 8.1 enums, TypeScript union types/enums) |
| User-facing text and emails | i18n / lang files (`lang/en/*.php`, `locales/en.json`), or a per-area `text` module where the project has no i18n |
| Error codes and messages returned by an API | An error catalog (`ErrorCode` enum + messages) |
| Log messages and event names | A log/event catalog module (`events`) |
| Cache keys, queue names, channel names, protocol strings, DOM ids, CLI flags, env var names, URL builders, regular expressions | One `keys` module per area |
| File system locations | A `paths` module |

Plain `0`, `1`, `null`, `true` and the empty string are fine where obvious [P]. (Conflict C-2: [C]
also allowed `-1`; follow the repository's linter, otherwise name it, e.g. `NOT_FOUND = -1`.)
Parameterized messages are functions in the catalog: ``cannotRead: (id) => `Cannot open ${id}: …` ``.
Enforce placement with a lint rule where possible ("no literal strings outside these modules") [C].

### 4.3 Config is validated at boot (hard rule) [P]

- Read env vars in **one** place, validate them with a schema (Zod/Joi/envalid in Node, typed config
  with validation in Laravel), and fail fast on startup if anything is missing or invalid.
- The rest of the app reads a typed, immutable settings object, never raw env.
- Derived settings ("percent to ratio", "feature A forces feature B") resolve there once.
- A cap or interval of 0 conventionally means "off" or "no limit"; document it.
- For a CLI, "boot" is the entry file: it validates the environment and puts the typed settings
  into the context. (Conflict C-3: [C] passed the raw environment in the context and parsed it inside
  the session module; parse it at the entry instead.) Credentials from env that are optional (only
  needed by some commands) are validated at boot when present and reported when a command needs them
  and they are absent.

### 4.4 Everything that persists is versioned (hard rule) [P]

- **Database:** schema changes only through migrations. Never edit an applied migration. Use
  expand-and-contract for breaking changes (add the new column, backfill, switch reads, drop the old).
- **API:** additive changes are fine. Breaking changes get a new version or a new field, with a
  deprecation window.
- **Stored JSON, settings blobs, cached payloads, local session files:** carry a version number
  (`{ "schemaVersion": 1, ... }`). Readers **accept old versions or migrate them on read**. Unknown
  newer versions or malformed files are a clear error with the fix ("sign in again"). (Conflict C-4:
  [C] rejected any version other than the current one.)
- **Defaults** persisted per user or tenant: changing a default needs a migration or backfill, or old
  records keep the old value forever.

### 4.5 Feature flags (hard rule for risky or unfinished behavior) [P, C]

- Optional, risky or unfinished behavior sits behind a named flag, **off by default** where the
  programmer says so [C].
- Flags that should be switchable without a deploy live in typed config (4.2/4.3) [P]. Build-time
  constants are acceptable only where there is no runtime config (e.g. a static page with no backend);
  say so in the docs. (Conflict C-5.)
- Each flag has an **owner and a removal date** [P], recorded where the flag is documented.
- Unfinished features the programmer wants visible are shown **disabled with a hint** ("Coming soon";
  "for now use …") — never as client-only behavior that pretends to work (section 8) [P, C].

---

## 5. Code style [C]

- **No single-letter names** (loop counters included: `index`, `position`, `entry`); `_` only for
  ignored values.
- **Match the surrounding code:** naming, idioms, file layout. Read neighbors before writing.
- **One language variant** consistently (American English unless told otherwise) in code, UI copy and
  docs [P, C].
- **Simplicity [P]:** the smallest design that works; no speculative abstraction; no interface with
  one implementation "for later" except at true vendor boundaries (an injected transport counts as a
  vendor boundary). One module, one job; small functions with intention-revealing names.

---

## 6. Request, job and lifecycle model [P]

- **Request lifecycle:** middleware (auth, tenant, rate limit, request ID) -> validation (Form
  Requests / Zod) -> controller -> service -> resource/DTO.
- **Jobs and events:** a service emits domain events (`OrderPlaced`); listeners and jobs handle side
  effects (email, analytics, webhooks). Features communicate through events or services, not by
  reaching into each other's tables or models.
- **Every background job is idempotent, has a timeout, a retry policy and a dead-letter or
  failed-jobs path.**

---

## 7. DDIA principles, applied [P]

Based on *Designing Data-Intensive Applications* (Kleppmann): reliability, scalability,
maintainability, and one source of truth.

### 7.1 Reliability: fail safe and recover

- **Validate at every boundary** (request, webhook, queue message, external response, scraped HTML).
  Trust nothing from the client or a third party.
- **Timeouts on every outbound call.** No call waits forever. (Gap in [C]: its website client had no
  timeouts; add them through config.)
- **Retries with exponential backoff, jitter and a cap**, only for safe or idempotent operations
  (GET, list, download). Never hot-loop. Never auto-retry a non-idempotent write to a third party
  (e.g. replacing or creating a resource) unless it takes an idempotency key; never retry a denial or
  a bot-protection challenge.
- **Idempotency:** retryable writes take an idempotency key, or check-then-act inside a transaction or
  unique constraint. Running a job twice must be safe.
- **Transactions around multi-step writes.** Use the outbox pattern when a DB write and a message must
  both happen.
- **Graceful degradation:** if a non-critical dependency is down, serve the core feature and log it.
- **Every side effect has an undo path** or compensating action (refund, release lock, revert flag,
  restore from backup). Feature flags can be turned off without a deploy (4.5).
- **Clean shutdown:** finish in-flight requests and jobs, release locks; a CLI restores the terminal
  (raw mode off) on cancel.

### 7.2 Scalability: bounded work

- **Bound every query:** pagination on every list endpoint, a hard max page size, no unbounded
  `find()` / `all()`.
- **No N+1 queries:** eager-load and watch query counts in development.
- **Index for your access patterns**; review slow-query logs.
- **Move slow work off the request path** (emails, exports, image processing, third-party webhooks) to
  a queue (Laravel Queues with Horizon, BullMQ in Node).
- **Cache derived or read-heavy data** with explicit keys, TTLs and invalidation on write. Never cache
  the only copy of anything.
- **Stateless app servers:** sessions and state go in a DB or Redis, so you can run N instances. For
  credentials of a **third-party** system, prefer storing nothing server-side at all (section 9.3).
- **Rate limit and throttle** public endpoints and expensive operations; scale limits by plan or
  tenant, not guesswork. Also throttle your own calls to third parties.
- **Throttle periodic work** (cron, polling, reconciliation) to fixed intervals with locks so only one
  instance runs it.

### 7.3 Maintainability (the main focus)

**Operability**
- Structured logs (JSON) with a request/correlation ID, user or tenant ID and an event name from the
  log catalog.
- Log decisions with their inputs ("order 812 priced: subtotal=40.00 discount=0.10 tax=3.24"), so one
  line explains a result after the fact — **with secrets redacted**: never log passwords, password
  hashes, tokens, cookies, auth headers or raw request bodies that may contain them. (Conflict C-6:
  [C] said "never log bodies or headers"; both hold: log the decision inputs, redact the secrets.)
- Throttle or dedupe noisy errors, and send them to an error tracker (Sentry or similar).
- Health and readiness endpoints, plus metrics for latency, error rate, queue depth, job failures.
- Admin or CLI commands for safe operational actions (re-run a job, resync a record), behind
  permissions. Debug tools are off in production by default.

**Simplicity** — see section 5. Prefer **reconciliation** for state that mirrors something external:
a scheduled job compares desired with actual state and fixes the difference (subscription status vs
the payment provider, files vs DB rows). This self-heals after missed webhooks and partial failures.

**Evolvability**
- New variants (plans, roles, discount types, channels, commands) are added through data, config,
  enums and strategy/dispatch maps, not new `if` chains.
- Domain rules take settings objects, so new knobs don't change call sites.
- Reuse small value objects (`Money`, `DateRange`, `Percentage`, `Cadence`) across features.

### 7.4 One source of truth

- The database (or the system of record for that data) is the record. Don't keep a second copy that
  can drift; derive it, or read it when needed.
- Denormalized copies and caches are derived; each has one documented way to rebuild it.
- External systems are the source of truth for their own data (the payment provider for payment
  status, a website for the maps it hosts). Mirror it with webhooks plus reconciliation, or read it
  back after writing (3.2 step 7) — never by guessing.

---

## 8. Client/server contract (frontend vs backend mismatches) [P]

- **The server is authoritative.** Prices, totals, permissions (including ownership/read-only
  rules), limits, stock and state transitions are computed and enforced on the server. The client may
  preview; the server recomputes and wins.
- **Same rules, one implementation:** if the client must show a computed value, fetch it from the
  server or share the exact rule (a shared TypeScript package; for PHP backends, computed fields in
  the API resource). Never re-implement business rules in the frontend by hand.
- **Typed contract:** define request/response schemas once (OpenAPI, Zod schemas shared in a monorepo,
  Laravel API Resources plus generated TS types). Validate on the server; generate or share types for
  the client.
- **Optimistic UI must reconcile** with the server's response and roll back on error.
- **Audit for mismatches:** for each value the UI shows, know whether it comes from the server or is
  computed locally, and from which inputs. Anything local that drives a decision is a bug waiting.
- **Remove features the server can't back.** Don't ship client-only behavior that pretends to be real.
  A disabled "Coming soon" item does not pretend and is fine (4.5).

---

## 9. Credentials, sessions and third-party websites [C]

### 9.1 Secrets

- Secrets only in env or a secret manager, never in the repo. `.env` is gitignored; `.env.example`
  lists every key [P].
- Prefer not to store secrets. If a session must persist locally: store the minimum (identifier +
  derived credential, never the plain password); use the platform config folder (`$XDG_CONFIG_HOME`,
  `%APPDATA%`, `~/.config`) under an app folder; file mode `0600`, folder `0700`; versioned schema
  (4.4); a `logout` that deletes it.
- Environment credentials win over the stored session and save nothing (scripts and CI); they are read
  through the validated config (4.3).
- Verify a sign-in with a real authenticated request before saving it; save nothing on refusal.
- Hidden password prompts: raw mode, no echo, Ctrl+C / Ctrl+D cancel cleanly, terminal restored. No
  terminal and no environment credentials → an error naming the variables to set.
- A derived value the remote system accepts as the password (e.g. an unsalted hash) is
  **password-equivalent**; say so in the docs and protect it like a password.
- In browsers: `sessionStorage` (tab only) by default; persistent storage only by explicit choice of
  the programmer or user ("Keep me signed in").

### 9.2 Integrating with websites that have no API

- Reproduce the site's own requests (form fields, cookies, error markup) with a plain HTTP client and
  record each finding as a confirmed fact with its source (section 11).
- Record: how authentication is carried (cookies, headers, body), cookie `SameSite`, CORS headers,
  `X-Frame-Options`/CSP, bot protection per path, error markup, length limits, side effects of saving
  (flags reset, settings overwritten).
- When re-submitting a site's form, **resend every field exactly as the page had it** (serialize like a
  browser: skip buttons/files, radio/checkbox only when checked, textarea contents, the selected or
  first option, decoded entities), or settings are silently lost.
- Detect bot-protection challenges explicitly and report them; **never build a bypass**.
- Timeouts and throttling apply (7.1, 7.2); scraped HTML is untrusted input (7.1).

### 9.3 Can a static page talk to such a site directly?

1. Does the site send `Access-Control-Allow-Origin` for your origin with credentials allowed? If not,
   a cross-origin `fetch` cannot read responses; `no-cors` only sends blind.
2. Is authentication in cookies? `SameSite=Lax`/`Strict` cookies are not sent on cross-site `fetch`,
   iframes or cross-site top-level POST (Lax rides only top-level GET navigations). A page cannot set
   cookies for another domain.
3. Does the site accept credentials in the body/URL/header instead? If not, a custom request cannot
   carry the sign-in.
4. An iframe of the site is cross-origin: unreadable, unscriptable, and usually signed out.

If 1–3 fail, the request must come from:

- **A server you run** (container, VM, serverless). Costs: hosting and operations; users trust you
  with their credentials; all users share your IP (rate limits, bans; ask the site owner); bot
  protection is often stricter for datacenter IPs — **test the exact write requests from the target
  host before building**. Design: stateless toward the third-party credential (the browser keeps it
  and sends it per request in a header, not a cookie → no CSRF), exact CORS allow-list, HTTPS only,
  redacted logs (7.3), size limits, rate limits, timeouts, server-side ownership checks (8), local
  backups done in the browser so the server stores nothing.
- **Code in the site's origin or with extension privileges** (userscript, extension). No server, no
  password handling, uses the user's session; costs a one-time install. The bridge validates
  `event.origin` strictly and exposes only fixed operations (never arbitrary URLs). Prototype first;
  cookie rules for extension/userscript requests vary by browser.

Write this analysis into the feature doc ("why the page cannot do it directly") so it is not redone.

---

## 10. UI and UX (Apple design philosophy) [C]

- **Clarity, deference, depth:** content first; controls recede; one obvious primary action per view.
- Put actions where users already look (an existing menu) instead of new pages; **sheets/popovers**
  for short tasks; Escape closes, Enter triggers the primary button, focus returns to the opener.
- **Plain language**, sentence case, verbs on buttons ("Sign In", "Save to <name>", "New Map…"); an
  ellipsis when an item opens a further step.
- Sensible defaults; optional safety features (backups) off by default when the programmer says so; no
  confirmation dialogs the programmer has ruled out.
- Unavailable features: visible but **disabled** with a one-line hint, optionally a "Coming soon"
  badge (4.5, 8).
- Status feedback in an existing unobtrusive status line; warnings do not block.
- Light and dark themes through color tokens, both checked; keyboard and screen-reader roles
  (`role="menu"`, `menuitem`, `aria-disabled`, `aria-expanded`).
- UI state only in the frontend; server state through a query cache keyed by API resource and
  invalidated on mutation [P 13.1].
- All UI strings in the text/i18n catalog; element ids in `keys`.

---

## 11. Documentation as the design record [P + C]

- `README.md`: what it is, setup, run, test, deploy; a short section per user-facing feature.
- `docs/README.md`: table of contents, one line per topic [P].
- `docs/<area>/<topic>.md` (or `docs/<topic>.md` in small repos): how a feature actually works — the
  flow, the numbers, the edge cases, the log/event names to look for, the rejected alternatives, and
  "why X is not possible" when a limitation exists.
- `docs/architecture.md`: the module/pipeline map as a table (stage → code → job) [C].
- `docs/adr/NNNN-title.md`: one short ADR per significant decision (context, decision,
  consequences) [P].
- `AGENTS.md` / `CONTRIBUTING.md` [P]:
  - conventions and working rules (short);
  - **confirmed facts** about third parties and the framework, each with where it was verified. In a
    repository with many such facts, keep them in one table file (e.g. `docs/facts.md`: ID, fact,
    source, consequence) and link it from `AGENTS.md`. (Conflict C-7: [C] kept facts in a docs table,
    [P] in `AGENTS.md`; follow the repository, default to [P] for few facts.) Facts come from primary
    sources (code, live requests, vendor docs), not community lore;
  - **verification checklist:** one concrete check per behavior change (what to do, and the response,
    log line or UI result to expect).
- **Docs, ADRs and checklist entries are updated in the same PR as the code** [P], **in their own
  commits** [C]. (C-9: these combine: same PR, separate commits.)

---

## 12. Testing [P + C]

- **Domain rules:** fast unit tests, many cases, no mocks (they are pure).
- **Services and commands:** tests with fake adapters (in-memory repositories, fake gateways, a fake
  `fetch` that inspects method, path, query, cookies/headers and body and keeps state), temporary
  folders and a fixed clock, run through the real entry (`main(argv, context)`).
- **Adapters:** integration tests against real dependencies in containers (DB, Redis) or the vendor's
  sandbox. For third parties without a sandbox, live tests only on targets the programmer named,
  always backup → change → verify → restore → verify byte equality and unchanged settings; report
  every remote resource created and leave deletion to the programmer.
- **API:** contract/feature tests per endpoint (Laravel feature tests, Supertest/Vitest in Node),
  including auth and validation failures.
- **Frontend:** component tests for logic, and a few end-to-end tests (Playwright/Cypress) for critical
  flows; ad-hoc checks can drive a headless browser over the DevTools protocol against a local build,
  capturing console errors and light/dark screenshots.
- Cover: success, refused credentials, read-only refusal, unreadable resource, invalid input caught
  before any request (assert the fake saw no request), remote error markup, usage errors, stored file
  permissions.
- **A bug fix starts with a failing test that reproduces it** [P]. If the bug cannot be reproduced,
  say so with the scenarios tried and the untested hypotheses, ask for exact steps, and do not ship a
  speculative fix [C].
- Local-only scripts stay out of version control: in a session scratch folder, or under a gitignored
  `scripts/local/` [P].

---

## 13. Stack mappings [P]

### 13.1 MERN (MongoDB, Express, React, Node) with TypeScript

    server/
      src/
        app.ts                   composition root: build deps, mount routes
        config/                  env schema (Zod), typed settings, constants
        http/
          routes/                route definitions only
          controllers/           thin: validate -> service -> respond
          middleware/            auth, requestId, rateLimit, errorHandler
          schemas/               Zod request/response schemas (shared)
        services/                use cases, transactions (Mongo sessions)
        domain/                  pure rules, value objects, enums
        adapters/
          db/                    Mongoose models + repositories
          payments/              gateway interface + Stripe implementation
          mail/                  mailer interface + provider implementation
          queue/                 BullMQ producers
        jobs/                    BullMQ workers (idempotent)
        events/                  domain events + listeners
        text/                    error catalog, log events, email copy keys
        observability/           logger (pino), metrics, tracing
      test/                      unit (domain), service, integration, api
    client/
      src/
        api/                     typed client generated from shared schemas
        features/<feature>/      components, hooks, state per feature
        components/              shared presentational components
        i18n/                    locale files
        config/                  public runtime config
    packages/shared/             shared Zod schemas, types, pure rules
    docs/, docs/adr/, README.md, AGENTS.md

- MongoDB has no enforced schema. Mongoose schemas plus migrations (migrate-mongo) are the schema.
  Version embedded documents.
- Use multi-document transactions (sessions) for multi-step writes. Unique indexes for idempotency
  keys.
- React holds UI state only. Server state goes through a query cache (TanStack Query) keyed by API
  resource, invalidated on mutation.

### 13.2 PHP / Laravel

    app/
      Http/
        Controllers/             thin: FormRequest -> Action/Service -> Resource
        Requests/                validation (Form Requests)
        Resources/               API Resources (the response contract)
        Middleware/
      Actions/ or Services/      use cases, DB::transaction()
      Domain/<Area>/             pure rules, value objects, enums (no Eloquent)
      Models/                    Eloquent models (persistence only, thin)
      Repositories/              optional: only where queries get complex
      Gateways/                  interfaces + vendor implementations
      Jobs/                      queued jobs (ShouldBeUnique, idempotent)
      Events/ Listeners/         domain events and side effects
      Console/Commands/          operational commands
      Enums/                     statuses, roles, types
      Support/                   constants, cache keys, small helpers
    config/                      typed config files reading env() ONLY here
    database/migrations/         every schema change
    lang/en/                     user-facing text, validation messages
    routes/api.php, web.php      route definitions only
    tests/Unit (domain), tests/Feature (HTTP), tests/Integration (gateways)
    docs/, docs/adr/, README.md, AGENTS.md

- Call `env()` only inside `config/*.php`; use `config()` everywhere else, so `config:cache` works.
- Keep Eloquent models thin: relationships, casts, scopes. Business rules live in `Domain/` and
  Actions.
- Prevent N+1 queries with `Model::preventLazyLoading()` outside production.
- Queues with Horizon, `ShouldBeUnique` / `WithoutOverlapping` for idempotency, `tries` / `backoff` /
  `timeout` on every job, and a monitored `failed_jobs` table.
- With Inertia/Vue/React, generate TS types from API Resources or Data objects
  (spatie/laravel-data + typescript-transformer) so client and server share one contract.

### 13.3 TypeScript CLI and tool repositories (no server) [C]

    src/<area>/
      cli.ts          entry: validate env into settings, build the context, call main, set exit code
      main.ts         argv parsing + dispatch map of commands (thin, 3.x)
      service.ts      use cases with the ordered checks (3.2)   [split from main.ts per C-8]
      protocol.ts     pure request building / response parsing
      client.ts       adapter: createClient(account, fetcher = fetch)
      session.ts      versioned local state
      keys.ts constants.ts text.ts events.ts paths.ts
      *.test.ts       next to the code; fakes through the context
    docs/, README.md, AGENTS.md

- Node ≥ 22.18 runs `.ts` directly; `node:test` for tests; esbuild for browser bundles; one verify
  script (lint with zero warnings, `tsc --noEmit`, tests).

### 13.4 Other stacks

The same mapping applies: entry (controllers, handlers) -> services/use cases -> pure domain ->
adapters, with typed config, migrations, an i18n catalog and a docs/ADR folder. Django: views ->
services -> domain -> repositories/ORM. Rails: controllers -> service objects -> POROs ->
models/clients. NestJS: controllers -> providers -> domain -> repositories.

---

## 14. Conflicts between the two sources, and how they were resolved

| # | Topic | [P] says | [C] said | Resolution |
|---|---|---|---|---|
| C-1 | Comments | No explanatory comments; tool annotations, lint directives with documented reason, license headers allowed | No comments at all (lint-enforced) | [P]'s exceptions apply, unless the repository's linter bans all comments |
| C-2 | Allowed bare values | `0`, `1`, `null`, `true`, `''` | `-1`, `0`, `1` | [P]; `-1` gets a name unless the repository's linter allows it |
| C-3 | Environment | Read env in one place, schema-validated at boot, typed immutable settings | Raw environment passed in the context, parsed inside a feature module | [P] (hard rule): validate at the entry/boot; pass typed settings in the context |
| C-4 | Persisted versions | Readers accept old versions or migrate on read | Reject any version but the current one | [P] (hard rule): migrate on read; error only on unknown newer or malformed data |
| C-5 | Feature flags | In typed config, switchable without a deploy, owner and removal date | Named constants, off by default | [P]; build-time constants only where no runtime config exists (static pages), documented with owner and removal date |
| C-6 | Logging | Log decisions with their inputs | Never log bodies or auth headers | Both: log decision inputs, redact secrets |
| C-7 | Where confirmed facts live | `AGENTS.md` / `CONTRIBUTING.md` | A docs facts table with ID/source/consequence | Follow the repository; [P] for few facts, a linked table when there are many |
| C-8 | Command/entry thickness | Entry is 5–15 lines, calls one service | CLI `main` held the ordered checks of each use case | [P]: move use-case orchestration into services |
| C-9 | Docs timing | Same PR as the code | Separate commits | Compatible: same PR, separate commits |
| C-10 | Timeouts and retries | Timeouts on every outbound call; retries with backoff for idempotent ops | No timeouts; no retries | [P]; additionally never auto-retry non-idempotent third-party writes, denials or challenges |
| C-11 | Server state | Sessions in DB/Redis so servers are stateless | Store no third-party credential on the server | Compatible: the server is stateless either way; third-party credentials are not stored at all |
| C-12 | Unfinished features | Remove features the server can't back | Show them disabled as "Coming soon" | Compatible: disabled items don't pretend to work; anything that pretends is removed |

---

## 15. Before saying "done"

- [ ] Repository rules read and followed (they override this file).
- [ ] Lint (zero warnings), type check, static analysis and tests pass; output checked.
- [ ] Entry points thin; business rules pure; adapters hide vendors; one wiring place.
- [ ] No explanatory comments, no magic values, strings in their catalogs, no single-letter names.
- [ ] Config validated at boot; no raw env reads elsewhere.
- [ ] Persisted data versioned; migrations for schema and default changes.
- [ ] Outbound calls have timeouts; retries only where idempotent.
- [ ] Optional/risky behavior behind a flag with owner and removal date; off by default where required.
- [ ] Server enforces permissions; the UI only previews.
- [ ] Docs, ADRs, confirmed facts and the verification checklist updated in the same PR, in their own
      commits.
- [ ] Bug fixes start from a failing test (or the report says why no repro exists).
- [ ] No secrets printed, committed, logged or written into fixtures.
- [ ] Remote state left behind is reported; modified targets restored.
- [ ] Unverified claims marked as such.
- [ ] Commits only if asked; one per logical change; no attribution lines unless the repo asks.
