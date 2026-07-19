# CardDemo Walking Skeleton — Write-Ownership Matrix

This document defines the **per-zone write-ownership matrix** for the CardDemo modernized walking skeleton. The matrix enforces two structural invariants that keep the skeleton's seams crisp so it **builds green**: (1) **clean bounded-context ownership** — exactly one deployable service per bounded context, derived from the legacy CICS program groupings registered in the CardDemo system definition `[SRC: CARDDEMO.CSD]`; and (2) the **frozen-contract seam** — `/contracts/**` is a shared, frozen single source of truth (OpenAPI 3.1) consumed via code generation and **never hand-edited**. Every zone below has **exactly one writer**, and **cross-zone writes are prohibited**: a service never writes another service's tree, the UI never writes a service tree, and no code hand-edits the frozen contracts.

The following table catalogs each write-ownership zone, its sole owner, and the rules that govern it.

## Write-Ownership Matrix

| Zone / Path | Owner | Rules / Notes |
| --- | --- | --- |
| `/services/<svc>/**` | The individual service | Owned **exclusively** by that service. **Seven bounded-context domain services** — auth, account, card, transaction, payment, useradmin, reporting — each own their tree (one bounded context per deployable service `[SRC: CARDDEMO.CSD]`); the **`bff`** is a thin aggregation service (not a bounded context), for **eight Spring services in total**. |
| `/contracts/**` | Shared (platform) | **FROZEN single source of truth** (OpenAPI 3.1); versioned; frozen after this run. Java server interfaces + UI TypeScript client are generated from these; never hand-edited. |
| `/ui/app/**` | UI shell | Routing, theme (`theme.ts`), layout, auth (AuthProvider/RouteGuard), and generated API client output. |
| `/ui/components/**` | UI shell (shared) | Shared, reusable MUI-composed components (PageContainer, DataTable, StatusChip, Loading/Empty/Error). |
| `/ui/features/<screen>/**` | Feature-screen owner | One owner per screen; the Card Detail feature is the live tracer, all others are typed placeholders. |
| `/db/migration/**` | Platform | Flyway migrations (`V1__baseline.sql`, `V2__seed_tracer.sql`); Oracle schema + single-row tracer seed. |
| `bff` (aggregation) | BFF service | **Aggregation only — no domain logic.** The UI binds only to the BFF. |
| Root infra (`docker-compose*.yml`, `.env.example`, `pom.xml`, `.github/**`) | Platform | Deploy unit + CI; docker-compose is the single-VM deployment unit. |

## Key Rules

- One bounded context per deployable service; each service owns `/services/<svc>/**` exclusively `[SRC: CARDDEMO.CSD]`.
- `/contracts/**` is shared and **frozen** — the single integration guarantee; regenerate, never hand-edit.
- The UI shell owns `/ui/app/**`; shared components live in `/ui/components/**`; feature screens live in `/ui/features/<screen>/**`.
- The BFF owns aggregation only; **the UI binds only to the BFF** (no direct UI-to-domain-service calls).
- Root infra + CI are platform-owned.

---

[← Index](README.md) · [Architecture](architecture.md) · [[DEFERRED] Epic Register](deferred-epics.md)
