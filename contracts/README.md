# CardDemo Contracts - Frozen OpenAPI 3.1 Single Source of Truth (SSoT)

This `/contracts` directory holds the contract-first, versioned, and frozen OpenAPI 3.1
specifications that are the single source of truth (SSoT) for the entire CardDemo walking
skeleton (F-SKEL). The Java server interfaces (one per backend service) and the UI
TypeScript client are **generated** from these specifications and are **never hand-edited**.
Editing generated code by hand is prohibited; change the contract instead and regenerate.

This document is the human-facing companion to `contracts/VERSION` and defines the
**freeze policy** plus the **regeneration (code-generation) instructions** every downstream
agent must follow.

## 2. Contract Inventory

Ten files live in this folder: eight OpenAPI 3.1 specifications, the frozen version pin, and
this README. Each specification carries `[SRC: <program> | <file/map>]` provenance tokens
tracing it back to the legacy CardDemo topology.

| File | Description | Provenance |
|------|-------------|------------|
| `auth-svc.openapi.yaml` | Permissive sign-on / token issuance contract. | [SRC: COSGN00C \| COSGN00.bms] |
| `account-svc.openapi.yaml` | Account view / update contract. | [SRC: COACTVWC/COACTUPC \| ACCTDAT] |
| `card-svc.openapi.yaml` | Card domain contract, including the **live tracer** `GET /cards/{cardNumber}`. | [SRC: COCRDSLC \| CARDDAT] |
| `transaction-svc.openapi.yaml` | Transaction list / view / add contract. | [SRC: COTRN00C/COTRN01C/COTRN02C \| TRANSACT] |
| `payment-svc.openapi.yaml` | Bill payment contract. | [SRC: COBIL00C \| ACCTDAT] |
| `useradmin-svc.openapi.yaml` | User admin (list/add/update/delete) + admin menu contract. | [SRC: COUSR00C-03C, COADM01C \| USRSEC] |
| `reporting-svc.openapi.yaml` | Reporting / statement async job contract (health-exempt). | [SRC: CORPT00C, CBSTM03A/B \| TRANSACT] |
| `bff.openapi.yaml` | BFF aggregation contract consumed by the UI TypeScript client; includes tracer aggregation `GET /api/cards/{cardNumber}`. | [SRC: COMEN01C \| COMEN02Y] |
| `VERSION` | Frozen version pin: `1.0.0`. | (platform) |
| `README.md` | This file: freeze policy and regeneration instructions. | (platform) |

Note: `reporting-svc` is an async job stub and is deliberately **exempt** from the health path
and from the container start-and-serve requirement (see convention 2 below).

## 3. Freeze Policy

- **Versioned and frozen after this run.** The contracts are frozen once initial authoring
  completes. The frozen version is pinned in `contracts/VERSION` and equals `1.0.0`, and every
  specification's `info.version` field equals `1.0.0`. The `VERSION` pin and all `info.version`
  values must always agree.
- **No post-authoring writes.** No service agent and no UI agent writes to `/contracts` after
  initial authoring. This folder is shared and is owned by the platform / contract layer under
  the per-service write-ownership matrix; the domain services own `/services/<svc>/**` and the
  UI owns `/ui/**`, but neither owns `/contracts`.
- **A contract mismatch must fail the build, not surface at runtime.** The Java server interfaces
  are generated from these specifications at build time (Maven `generate-sources`), so any
  Java-side drift is caught by code generation and compilation and fails the Maven build. The UI
  TypeScript client is generated out-of-band and committed (see §5.2); the UI build
  (`tsc --noEmit && vite build`) type-checks the committed client against the UI code, so consumer
  drift is caught at build time rather than appearing as a runtime error.
- **Evolving a contract (future, non-frozen runs only).** To change a contract in a future run,
  bump `contracts/VERSION` following SemVer, update the matching `info.version`, and regenerate
  all consumers (Java interfaces and the UI client). Never silently hand-edit generated code to
  work around a stale contract.

## 4. Cross-Cutting Conventions

These eight conventions apply to every specification in this folder. Downstream agents must
honor them exactly so that generated servers and the generated client stay compatible.

1. **OpenAPI 3.1.** Every specification is authored against OpenAPI 3.1.
2. **Health path.** Every request-serving specification (`auth`, `account`, `card`,
   `transaction`, `payment`, `useradmin`, and `bff`) declares `GET /actuator/health` with tag
   `Health`, `operationId` `healthCheck`, unsecured, returning a `HealthStatus{status}` schema.
   The generated Health API interface is **intentionally left UNIMPLEMENTED** - Spring Boot
   Actuator serves `/actuator/health` at runtime. `reporting-svc` **OMITS** the health path
   because it is a health-exempt async job stub.
3. **Error model.** A single RFC 7807 ProblemDetail-aligned `Error` schema (media type
   `application/problem+json`) with fields `type`, `title`, `status`, `detail`, `instance`, and
   `correlationId` is used for `400`, `404`, and `500` responses.
4. **Correlation ID.** Every operation documents an optional request header `X-Correlation-ID`
   (`string`, `format: uuid`) via a reusable parameter. The correlation ID propagates
   UI -> BFF -> card-svc and is logged via SLF4J MDC in each service.
5. **Security.** A `bearerAuth` HTTP bearer (JWT) security scheme is defined. Only
   `auth-svc POST /auth/login` and `bff POST /api/auth/login` are unauthenticated; all other
   business operations carry `bearerAuth`. This is a permissive stub - the token hop is real,
   but token validation is not implemented in this skeleton.
6. **Version pin.** `1.0.0` is used across every `info.version` and in `contracts/VERSION`.
7. **Path style.** Domain services use bare resource paths (`/cards`, `/accounts`,
   `/transactions`, `/payments`, `/users`, `/reports`); the BFF uses the `/api/**` prefix. The
   UI binds **only** to the BFF and never calls a domain service directly.
8. **operationId.** All operation identifiers are camelCase (for example `getCardByNumber`,
   `login`, `getCardDetail`).

## 5. Regeneration Instructions (the generator contract)

Both consumers are regenerated from the frozen specifications at build time. Run code
generation as part of the build; never commit hand-edits to generated code.

### 5.1 Java server interfaces (per service)

Each `/services/<svc>/pom.xml` configures `org.openapitools:openapi-generator-maven-plugin`
(version `7.23.0`) with `generatorName=spring`, `interfaceOnly=true`, `useSpringBoot3=true`,
`useJakartaEe=true`, `useTags=true`, and `documentationProvider=none` (the lean profile emits
only API interfaces and models — no runtime documentation surface). The input specification is
the matching `/contracts/<svc>.openapi.yaml`.
The `apiPackage` is `com.carddemo.<short>.api` and the `modelPackage` is
`com.carddemo.<short>.model`, where `<short>` is the service name with the `-svc` suffix
removed (for example `card-svc` -> `card`; `bff` has no suffix so `<short>` -> `bff`).
Each controller `implements` the generated API interface and returns typed placeholder
responses, except the live tracer in `card-svc`, which performs a real Oracle read.

```xml
<!-- Excerpt from /services/card-svc/pom.xml (each service follows this pattern) -->
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <version>7.23.0</version>
  <executions>
    <execution>
      <goals>
        <goal>generate</goal>
      </goals>
      <configuration>
        <!-- Input spec: the matching /contracts/<svc>.openapi.yaml -->
        <inputSpec>${project.basedir}/../../contracts/card-svc.openapi.yaml</inputSpec>
        <generatorName>spring</generatorName>
        <!-- <short> = service name without the -svc suffix (card-svc -> card) -->
        <apiPackage>com.carddemo.card.api</apiPackage>
        <modelPackage>com.carddemo.card.model</modelPackage>
        <configOptions>
          <interfaceOnly>true</interfaceOnly>
          <useSpringBoot3>true</useSpringBoot3>
          <useJakartaEe>true</useJakartaEe>
          <useTags>true</useTags>
          <documentationProvider>none</documentationProvider>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Per-service package mapping (illustrates the `<short>` rule):

| Input spec | Service directory | apiPackage | modelPackage |
|------------|-------------------|------------|--------------|
| `auth-svc.openapi.yaml` | `services/auth-svc` | `com.carddemo.auth.api` | `com.carddemo.auth.model` |
| `account-svc.openapi.yaml` | `services/account-svc` | `com.carddemo.account.api` | `com.carddemo.account.model` |
| `card-svc.openapi.yaml` | `services/card-svc` | `com.carddemo.card.api` | `com.carddemo.card.model` |
| `transaction-svc.openapi.yaml` | `services/transaction-svc` | `com.carddemo.transaction.api` | `com.carddemo.transaction.model` |
| `payment-svc.openapi.yaml` | `services/payment-svc` | `com.carddemo.payment.api` | `com.carddemo.payment.model` |
| `useradmin-svc.openapi.yaml` | `services/useradmin-svc` | `com.carddemo.useradmin.api` | `com.carddemo.useradmin.model` |
| `reporting-svc.openapi.yaml` | `services/reporting-svc` | `com.carddemo.reporting.api` | `com.carddemo.reporting.model` |
| `bff.openapi.yaml` | `services/bff` | `com.carddemo.bff.api` | `com.carddemo.bff.model` |

### 5.2 UI TypeScript client

The UI client is generated with `@openapitools/openapi-generator-cli` (which wraps generator
`7.23.0`) using the `typescript-axios` generator, sourced from `contracts/bff.openapi.yaml`
**ONLY**, and emitted into the UI at `ui/app/api/generated` (configured in `ui/openapitools.json`).
Unlike the Java interfaces, the UI client is generated **out-of-band** — via the `npm run generate:api`
script, **not** during the UI build — and the generated output is **committed** to the repository.
The UI build (`tsc --noEmit && vite build`) consumes the committed client and does not regenerate it.
The UI imports the generated client and binds **only** to the BFF; the generated client is never
hand-edited.

```jsonc
// ui/openapitools.json
{
  "$schema": "./node_modules/@openapitools/openapi-generator-cli/config.schema.json",
  "spaces": 2,
  "generator-cli": {
    "version": "7.23.0",
    "generators": {
      "bff-client": {
        "generatorName": "typescript-axios",
        "glob": "../contracts/bff.openapi.yaml",
        "output": "app/api/generated",
        "additionalProperties": {
          "supportsES6": true,
          "withSeparateModelsAndApi": true,
          "apiPackage": "apis",
          "modelPackage": "models",
          "useSingleRequestParameter": true,
          "enumPropertyNaming": "original"
        }
      }
    }
  }
}
```

```bash
# Run from the /ui directory. This reads ui/openapitools.json (bff.openapi.yaml is the ONLY
# input; output is ui/app/api/generated). Run it out-of-band and COMMIT the regenerated client;
# the UI build (tsc --noEmit && vite build) does NOT regenerate.
npm run generate:api
```

## 6. Provenance

The specification schemas mirror the legacy VSAM record layouts under `app/cpy/*` (for example
the `CARD` layout in `CVACT02Y`) and the green-screen field shapes under `app/bms/*` (for
example the Card Detail map `COCRDSL.bms` and the Sign-On map `COSGN00.bms`). The service
topology - which program groups into which bounded context - derives from the CICS system
definition `app/csd/CARDDEMO.CSD`. Every specification therefore carries
`[SRC: <program> | <file/map>]` provenance tokens (see the inventory table above), keeping each
modern contract traceable to its legacy origin.

All functional epics beyond the single Card Detail tracer are `[DEFERRED]` with zero
implementation; the contracts define the full target surface, but only the tracer slice is
wired live in this run.

---

Frozen at version `1.0.0` (see `contracts/VERSION`). Contracts are the SSoT: generate, do not
hand-edit.
