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
| Root infra (`docker-compose*.yml`, `.env.example`, `.gitignore`, `pom.xml`, `.github/**`) | Platform | Deploy unit + CI; docker-compose is the single-VM deployment unit. `.gitignore` is repo hygiene that keeps build artifacts (`target/`, `node_modules/`, `dist/`) out of version control, directly supporting the clean-checkout reproducible build. |

## Key Rules

- One bounded context per deployable service; each service owns `/services/<svc>/**` exclusively `[SRC: CARDDEMO.CSD]`.
- `/contracts/**` is shared and **frozen** — the single integration guarantee; regenerate, never hand-edit.
- The UI shell owns `/ui/app/**`; shared components live in `/ui/components/**`; feature screens live in `/ui/features/<screen>/**`.
- The BFF owns aggregation only; **the UI binds only to the BFF** (no direct UI-to-domain-service calls).
- Root infra + CI are platform-owned.

## Scope Reconciliation (P4-M12)

The AAP enumerates the in-scope file set in **§0.7.1** using **wildcard write-ownership zones**
(`/services/<svc>/**`, `/ui/app/**`, `/ui/components/**`, `/ui/**`, root infra) plus approximate
counts (for example "≈16 placeholder screens"), **not** an exact file-by-file manifest. A raw file
count therefore reads higher than an approximate enumeration would suggest; every additional file
nonetheless falls **inside** a declared wildcard zone (or is necessary root hygiene) and so is
in-scope by construction. None of the files below weakens the frozen AAP — each is the minimal,
production-appropriate content of a zone the AAP already delegates to its owner.

| File | Owning zone (AAP §0.7.1) | Why it is in-zone / necessary |
| --- | --- | --- |
| `.gitignore` | Root infra (platform) | Repo hygiene; keeps `target/`, `node_modules/`, `dist/` out of VCS — supports the clean-checkout reproducible build. |
| `services/account-svc/.../web/GlobalExceptionHandler.java` | `/services/account-svc/**` | Verified defensive seam: maps bean-validation failures to the RFC 7807 `Error` contract with a **sanitized** `400`, preventing internal-detail/stack leakage and log-forging-shaped input (complements the P4-M04 correlation-normalization hardening). |
| `services/payment-svc/.../web/GlobalExceptionHandler.java` | `/services/payment-svc/**` | Same sanitized `400` handler within the payment service tree. |
| `services/transaction-svc/.../web/GlobalExceptionHandler.java` | `/services/transaction-svc/**` | Same sanitized `400` handler within the transaction service tree. |
| `services/useradmin-svc/.../web/GlobalExceptionHandler.java` | `/services/useradmin-svc/**` | Same sanitized `400` handler within the useradmin service tree. |
| `ui/app/layout/navItems.ts` | `/ui/app/**` (shell, `layout/**`) | Shared navigation model derived from the legacy 10-option main menu `[SRC: COMEN01C \| COMEN02Y]`; single source for the `Drawer` items. |
| `ui/components/DeferredNotice.tsx` | `/ui/components/**` | Shared MUI-composed deferred-state component reused by every typed-placeholder screen. |
| `ui/package-lock.json` | `/ui/**` (companion to `package.json`) | Deterministic dependency lockfile; **required** for the `npm ci` clean installs used by both CI and the UI Dockerfile (P4-M09 determinism). |

The AAP text is treated as **frozen and authoritative** (D1): this reconciliation documents that
the files sit within already-delegated wildcard zones; it does **not** amend, widen, or reinterpret
the AAP's scope.

---

[← Index](README.md) · [Architecture](architecture.md) · [[DEFERRED] Epic Register](deferred-epics.md)
