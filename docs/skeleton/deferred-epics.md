# CardDemo Walking Skeleton — [DEFERRED] Epic Register

This document is part of the **CardDemo modernized walking skeleton** (`F-SKEL`). It records the **canonical identifier scheme** and the authoritative **`[DEFERRED]` epic register**: the legacy functional capabilities that are deliberately deferred with **zero implementation**, kept clearly separated from the small set of **live** skeleton requirements delivered in this run.

The skeleton is a *structural / topology* deliverable — success is defined by the seams holding together and by exactly one vertical tracer slice being live end-to-end, **not** by behavioral parity with the legacy application. Every legacy business capability below is scaffolded only as a typed placeholder (or, for batch programs, an invokable job-stub entrypoint) and carries the `[DEFERRED]` tag. Provenance is drawn exclusively from the verified legacy topology in `app/csd/CARDDEMO.CSD` and the COBOL program roster; **no sources are invented, and no Figma designs apply**.

## Canonical Identifiers

- **Skeleton requirements** are identified with the form **`F-SKEL-####`** (for example, `F-SKEL-0001`).
- **Functional epics are tagged `[DEFERRED]`** with **zero implementation**. The exact tag token is the literal string **`[DEFERRED]`** — it is **not** `[GAF: DEFERRED]` (that alternative form is shown here, this once, only to disambiguate the correct token, and must never be used as an actual tag anywhere in the codebase or docs).
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

The legacy batch programs are **not** implemented as real calculations in this run. They exist **only** as invokable, typed **job-stub entrypoints** hosted in `reporting-svc` (no posting, interest, statement, or report logic is performed):

| Batch program | Legacy purpose | Status |
|---|---|---|
| `CBTRN02C` | Daily transaction posting | `[DEFERRED]` — job-stub entrypoint only (no posting logic) |
| `CBACT04C` | Interest calculation | `[DEFERRED]` — job-stub entrypoint only (no interest logic) |
| `CBSTM03A` / `CBSTM03B` | Statement generation | `[DEFERRED]` — job-stub entrypoint only (no statement output) |
| `CBTRN03C` | Transaction report | `[DEFERRED]` — job-stub entrypoint only (no report output) |

`reporting-svc` is **health-exempt**: as an async job stub it must *build green* and expose an invokable entrypoint, but it is **not** held to the start-and-serve or `docker-compose` `service_healthy` requirement that governs the request-serving services (`auth`, `account`, `card`, `transaction`, `payment`, `useradmin`, and `bff`).

## Live Skeleton Requirements (F-SKEL-####)

The following `F-SKEL` requirements **are implemented** in this run. They are the live skeleton — deliberately kept small and clearly separated from the deferred functional epics above. **None** of these requirements is deferred, and none of them may carry a deferral tag.

| ID | Requirement | Provenance |
|---|---|---|
| `F-SKEL-0001` | Service topology: eight bounded-context services + a thin BFF + a React UI | `[SRC: CARDDEMO.CSD]` |
| `F-SKEL-0002` | Contract-first OpenAPI 3.1 specifications (frozen SSoT); generated Java server interfaces + TypeScript client | — |
| `F-SKEL-0003` | Oracle 23ai data layer via Flyway (`GENERATED BY DEFAULT AS IDENTITY` surrogate keys; `CARD_NUM` natural read key) | `[SRC: CVACT02Y | CARDDAT]` |
| `F-SKEL-0004` | The one live vertical **tracer slice**: UI → BFF → `card-svc` → Oracle `FREEPDB1` seeded row → rendered Card Detail, with real loading / empty / error states | `[SRC: COCRDSLC | CARDDAT]`, `[SRC: COCRDSL.bms | Card Detail fields]` |
| `F-SKEL-0005` | Health checks + `docker-compose` startup gating (`service_healthy`; `reporting-svc` exempt) | — |
| `F-SKEL-0006` | Real correlation-ID hop (UI Axios interceptor → BFF → `card-svc`; `OncePerRequestFilter` + SLF4J MDC) | — |
| `F-SKEL-0007` | Deploy unit: `docker-compose` on a single Linux VM running Docker Engine (no Kubernetes, no serverless) | — |

These identifiers follow the canonical `F-SKEL-####` scheme and represent the entire live scope of this run. They are intentionally kept separate from — and must never be confused with — the deferred functional epics registered above; every `F-SKEL` requirement here is implemented, and none is deferred.

---

[← Index](README.md) · [Architecture](architecture.md) · [Write-Ownership Matrix](write-ownership-matrix.md)
