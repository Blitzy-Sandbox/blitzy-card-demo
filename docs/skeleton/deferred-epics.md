# CardDemo Walking Skeleton — [DEFERRED] Epic Register

This document is part of the **CardDemo modernized walking skeleton** (`F-SKEL`). It records the **canonical identifier scheme** and the authoritative **`[DEFERRED]` epic register**: the legacy functional capabilities that are deliberately deferred with **zero implementation**, kept clearly separated from the small set of **live** skeleton requirements delivered in this run.

The skeleton is a *structural / topology* deliverable — success is defined by the seams holding together and by exactly one vertical tracer slice being live end-to-end, **not** by behavioral parity with the legacy application. Every legacy business capability below is scaffolded only as a typed placeholder (or, for batch programs, an invokable job-stub entrypoint) and carries the `[DEFERRED]` tag. Provenance is drawn exclusively from the verified legacy topology in `app/csd/CARDDEMO.CSD` and the COBOL program roster; **no sources are invented, and no Figma designs apply**.

## Canonical Identifiers

- **Skeleton requirements** are identified with the form **`F-SKEL-####`** (for example, `F-SKEL-0001`).
- **Functional epics are tagged `[DEFERRED]`** with **zero implementation**. The exact and only valid tag token is the literal string **`[DEFERRED]`** — no alternative, prefixed, or namespaced variant of the deferral tag may ever be used as an actual tag anywhere in the codebase or docs.
- Each `F-SKEL` requirement carries a **provenance token** of the form **`[SRC: <program> | <file/map>]`** (User Example: `[SRC: COCRDSLC | CARDDAT]`). Provenance is drawn only from the verified legacy program → file mapping below; there are **no invented sources**.
- **No requirement is GAF-complete.** Do **not** implement functional epics — build only what the skeleton needs to *build green* and to run the *one* live tracer slice.

## The [DEFERRED] Epic Register

Every legacy functional capability is deferred with zero implementation and tagged `[DEFERRED]`. The provenance column maps each capability to its legacy CICS program(s) and VSAM file, sourced from `[SRC: CARDDEMO.CSD]` and the program roster.

| Capability | Legacy provenance | Status |
|---|---|---|
| Sign-on / authentication | `[SRC: COSGN00C | USRSEC]` | `[DEFERRED]` — permissive stub only; real credential validation, password hashing, RBAC, and session management are deferred |
| Account view / update | `[SRC: COACTVWC | ACCTDAT]`, `[SRC: COACTUPC | ACCTDAT]` | `[DEFERRED]` |
| Card list / update | `[SRC: COCRDLIC | CARDDAT]`, `[SRC: COCRDUPC | CARDDAT]` | `[DEFERRED]` |
| Card view (whole feature) | `[SRC: COCRDSLC | CARDDAT]` | `[DEFERRED]` — **except** the minimal Card Detail read-render tracer, which is the **only** live behavior in this run |
| Transaction list / view / add | `[SRC: COTRN00C | TRANSACT]`, `[SRC: COTRN01C | TRANSACT]`, `[SRC: COTRN02C | TRANSACT]` | `[DEFERRED]` |
| Bill payment | `[SRC: COBIL00C | ACCTDAT]` | `[DEFERRED]` |
| User admin (list / add / update / delete) + admin menu | `[SRC: COUSR00C | USRSEC]`, `[SRC: COUSR01C | USRSEC]`, `[SRC: COUSR02C | USRSEC]`, `[SRC: COUSR03C | USRSEC]`, `[SRC: COADM01C | USRSEC]` | `[DEFERRED]` |
| Reporting / statements | `[SRC: CORPT00C | TRANSACT]`, `[SRC: CBSTM03A | TRANSACT]` | `[DEFERRED]` |

All functional epics **F-001…F-022** are deferred with **zero implementation** in this run. Even the **Card View feature as a whole is `[DEFERRED]`** — only the minimal Card Detail read-render tracer (an authenticated request flowing UI → BFF → `card-svc` → Oracle `FREEPDB1` seeded row → a rendered Card Detail screen, with real loading, empty, and error states) is live. No other endpoint performs persistence or business logic beyond that single seeded card read.

## Batch Programs (job-stub entrypoints only)

The legacy batch programs are **not** implemented as real calculations in this run. They exist **only** as invokable, typed **job-stub entrypoints** hosted in `reporting-svc` (no posting, interest, statement, or report logic is performed). Each batch program is backed by a dedicated `@Component` handler bean under `services/reporting-svc/src/main/java/com/carddemo/reporting/jobs/`; every handler returns a typed `JobAcknowledgement` placeholder with status `ACCEPTED` and the nil-UUID sentinel `jobId` (`00000000-0000-0000-0000-000000000000`) — it never fabricates a random tracking id or a `COMPLETED` status for work that never happened:

| Batch program | Legacy purpose | Handler bean (reporting-svc `jobs/`) | Status |
|---|---|---|---|
| `CBTRN02C` | Daily transaction posting | `DailyPostingJobHandler` | `[DEFERRED]` — job-stub entrypoint only (no posting logic) |
| `CBACT04C` | Interest calculation | `InterestCalculationJobHandler` | `[DEFERRED]` — job-stub entrypoint only (no interest logic) |
| `CBSTM03A` / `CBSTM03B` | Statement generation | `StatementJobHandler` | `[DEFERRED]` — job-stub entrypoint only (no statement output) |
| `CBTRN03C` | Transaction report (batch) | `TransactionReportJobHandler` | `[DEFERRED]` — job-stub entrypoint only (no report output) |

The online Transaction Reports program `CORPT00C` (distinct from the batch `CBTRN03C`) is backed by `ReportJobHandler` and exposed through the `POST /reports` contract operation; the batch job roster above is exposed through the `POST /jobs/daily-posting`, `/jobs/interest-calculation`, and `/jobs/transaction-report` contract operations. All are frozen typed stubs.

**Invocation (non-host-serving).** Because `reporting-svc` exposes no HTTP controller, the job stubs are triggered by `runner/JobInvocationRunner` — a Spring Boot `ApplicationRunner` gated by `carddemo.reporting.invoke-jobs-on-startup=true` (`@ConditionalOnProperty`). When enabled it invokes every job-stub handler once at startup and logs each typed acknowledgement, so final acceptance can invoke the batch stubs without any host port, scheduler, or queue. The flag is **off by default**, so a plain start (and the build-green context-load test) has no side effects.

`reporting-svc` is **health-exempt**: as an async job stub it must *build green* and expose an invokable entrypoint, but it is **not** held to the start-and-serve or `docker-compose` `service_healthy` requirement that governs the request-serving services (`auth`, `account`, `card`, `transaction`, `payment`, `useradmin`, and `bff`).

## Deferred Write / Job Semantics — typed placeholders, no fabricated work

The deferred write, update, delete, and job operations across the domain services return the
**contract-declared typed placeholder** response — a `200`/`201`/`204` with a typed body whose
identifiers are **deterministic** (either a compile-time constant or the id echoed from the
request path), never a synthetic random value. This is the **correct** outcome for the skeleton,
mandated by the frozen AAP:

- **§0.1.2 / §0.8** — "*a stub returning a typed placeholder is the correct outcome; a
  hallucinated (invented) implementation is a failure*"; every service "*exposes its contract and
  returns typed placeholder responses.*"
- **§0.7.2** — non-tracer endpoints "*return typed placeholders only, with no persistence or
  business rules beyond the single card read.*"
- The OpenAPI contracts are **frozen** (`contracts/VERSION` = `1.0.0`) and declare the `2xx`
  responses those operations return; the contract is the single source of truth and is not
  re-opened in this run.

**Conflict note (D1 precedence).** The acceptance review (finding **P4-M06**) suggested returning
explicit `DEFERRED`/`PENDING`/**`NOT_IMPLEMENTED` (`501`)** outcomes for these operations. That
suggestion **conflicts** with the frozen AAP above, which requires typed `2xx` placeholders and
freezes the contracts that declare them. Under the precedence rule (an explicit AAP requirement
outranks a finding's suggested resolution), the AAP wins: the placeholder controllers **retain**
their deterministic typed `2xx` responses and the frozen contracts are left unchanged. Adopting
`501`/`NOT_IMPLEMENTED` would have required re-opening frozen contracts and contradicting the
"typed placeholder is correct" rule — neither is permitted in this run.

**What P4-M06 *did* correct — fabricated completion.** The one genuine defect the finding
identified was in the reporting job handler, which previously returned a **terminal `COMPLETED`
status** with populated `submittedAt`/`completedAt` timestamps and a **random-UUID** tracking id —
asserting that batch work had *finished* when nothing ran. That is a *hallucinated* outcome (the
AAP failure mode), not a typed placeholder. It was corrected to an honest, non-terminal
**`ACCEPTED`** acknowledgement with **null** timestamps and the nil-UUID sentinel
`00000000-0000-0000-0000-000000000000` (never a random id), truthfully signalling "*accepted,
deferred, nothing executed*". Deterministic `2xx` create/update/delete placeholders that echo a
constant or the path id were **not** fabricating work and were therefore retained as-is.

## Deferred Production-Hardening Concerns

The acceptance review raised several **production-hardening** items that the AAP explicitly places
**out of scope** for this structural skeleton run ("*build only what is needed for the skeleton to
build green and run the one tracer slice*", §0.7.2). They are catalogued here with their AAP
grounding so the deferral is explicit and auditable. Where a finding had a *minimal in-scope
portion* that keeps the skeleton honest, that portion **was** implemented in this run (right-hand
column); the production-grade remainder is `[DEFERRED]`.

| Concern (review finding) | AAP grounding for deferral | Minimal in-scope portion delivered this run |
|---|---|---|
| Server-side bearer-token **validation** + `401` enforcement (**P4-C01**) | §0.7.2 — "*`auth-svc` is a permissive stub (the token and correlation-ID hop are real, validation is not)*" | The token hop was made real: `auth-svc` is the sole issuer and the BFF calls it via a bounded `RestClient` (**P4-M02**); correlation propagation + UI route-guard presence checks are live. Only production token *validation* is deferred. |
| DB **least-privilege** principals (separate migration owner vs runtime users) (**P7-M17**) | §0.7.2 production banking readiness; "*build only what is needed*" | Single app schema user for the skeleton; no committed usable password default (fail-fast, **P4-M11**). |
| Compose **secrets** tree / externalized secret store (**P7-M18**) | §0.6.1 designates `.env.example` / env templates as the config approach | `.env.example` template; empty fail-fast datasource password across all services (**P4-M11**). |
| **SBOM / image signing (cosign) / digest-pinned actions** (**P7-M19**) | §0.7.2 supply-chain hardening | Flyway image pinned to an immutable `@sha256` digest in CI + compose (**P4-M08/M09**); UI image bases digest-pinned. |
| **TLS / HSTS** transport hardening (**P7-M20**) | §0.7.2; AAP deploys HTTP `docker-compose` on a single VM | Domain-service, BFF, and Oracle ports internalized; only the UI ingress is published in the base compose (**P4-M14**). |
| Browser token storage → **HttpOnly cookie** redesign (**P4-m03**) | Finding itself labels it "*deferred production work*"; §0.7.2 session-management hardening | Token flows over the real hop; storage-model redesign deferred. |
| Local override auto-loads **DEBUG** logging (**P7-m06**) | `docker-compose.override.yml` is the AAP-designated (§0.6.1) local overlay, auto-loaded only for local `docker compose up` (CI uses the base file via `-f`) | Base compose carries conservative, minimal-exposure defaults (**P4-M14**); the override is local-dev-only. |
| **Full acceptance E2E** CI (topology start, health-gate wait, authenticated end-to-end HTTP tracer, browser E2E) (**P4-M09**, **P4-M10**) | §0.6.1 scopes CI to "*build + codegen + `gvenzl/setup-oracle-free@v1` + green assert*" | Deterministic pipeline delivered: `npm ci`, pinned Flyway digest, codegen-diff gate, Vitest, self-contained `docker compose build`, and the Oracle-backed tracer **seed** assertion (**P4-M09**). Full runtime E2E remains deferred. |

None of the deferrals above weakens the frozen AAP; each cites the specific AAP section that
places the production-grade work out of scope for this structural run.

## Live Skeleton Requirements (F-SKEL-####)

The following `F-SKEL` requirements **are implemented** in this run. They are the live skeleton — deliberately kept small and clearly separated from the deferred functional epics above. **None** of these requirements is deferred, and none of them may carry a deferral tag.

| ID | Requirement | Provenance |
|---|---|---|
| `F-SKEL-0001` | Service topology: seven bounded-context domain services + one thin BFF (eight Spring services total) + a React UI | `[SRC: CARDDEMO.CSD]` |
| `F-SKEL-0002` | Contract-first OpenAPI 3.1 specifications (frozen SSoT); generated Java server interfaces + TypeScript client | `[SRC: CARDDEMO.CSD]` |
| `F-SKEL-0003` | Oracle 23ai data layer via Flyway (`GENERATED BY DEFAULT AS IDENTITY` surrogate keys; `CARD_NUM` natural read key) | `[SRC: CVACT02Y | CARDDAT]` |
| `F-SKEL-0004` | The one live vertical **tracer slice**: UI → BFF → `card-svc` → Oracle `FREEPDB1` seeded row → rendered Card Detail, with real loading / empty / error states | `[SRC: COCRDSLC | CARDDAT]`, `[SRC: COCRDSLC | COCRDSL.bms]` |
| `F-SKEL-0005` | Health checks + `docker-compose` startup gating (`service_healthy`; `reporting-svc` exempt) | `[SRC: CARDDEMO.CSD]` |
| `F-SKEL-0006` | Real correlation-ID hop (UI Axios interceptor → BFF → `card-svc`; `OncePerRequestFilter` + SLF4J MDC) | `[SRC: COCRDSLC | CARDDAT]` |
| `F-SKEL-0007` | Deploy unit: `docker-compose` on a single Linux VM running Docker Engine (no Kubernetes, no serverless) | `[SRC: CARDDEMO.CSD]` |

These identifiers follow the canonical `F-SKEL-####` scheme and represent the entire live scope of this run. They are intentionally kept separate from — and must never be confused with — the deferred functional epics registered above; every `F-SKEL` requirement here is implemented, and none is deferred.

---

[← Index](README.md) · [Architecture](architecture.md) · [Write-Ownership Matrix](write-ownership-matrix.md)
