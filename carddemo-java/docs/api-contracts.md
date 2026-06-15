# CardDemo REST API Contracts

**Authoritative REST endpoint specification for the migrated AWS CardDemo application.**

This document is the single source of truth for the REST API surface produced by the
COBOL → Java 25 + Spring Boot 3.x migration. It translates the legacy presentation tier —
**17 BMS 24×80 3270 mapsets** (`app/bms/*.bms`) and their **17 symbolic-map copybooks**
(`app/cpy-bms/*.CPY`) — into REST request/response contracts that **preserve the original
field names, lengths, types, and validation rules**. It also maps the legacy **CICS
transaction identifiers** onto the Spring MVC controller route table.

The endpoint paths, controller groupings, and field contracts documented here are the
contract that the generated `com.cardemo.controller.*` and `com.cardemo.model.dto.*`
classes must satisfy. The same contract is consumed by the executive-presentation mapping
slide and is cross-checked by the API/interface contract verification gate.

---

## 1. Scope, traceability, and conventions

### 1.1 Scope

- **REST-only target.** The migration retires the 3270 terminal user interface entirely.
  There is **no web, browser, or graphical UI** in scope; every screen contract becomes a
  REST request/response payload instead (`docs/technical-specifications.md` §0.3, REST-only
  scope boundary). Rendering, cursor positioning, and 3270 attribute bytes are **not**
  reproduced — only the *field contract* (name, length, type, validation, input/output
  direction) carries forward.
- **No feature expansion.** Only the 22 documented features (F-001–F-022) are exposed.
  No new endpoints, fields, business rules, or entities are introduced beyond a faithful
  translation of the screen contracts (Minimal Change Clause).
- **Single permitted behavioral change.** Plaintext `USRSEC` password storage is upgraded
  to **BCrypt** verification on sign-on and BCrypt hashing on user create/update. The login
  *flow* and field contract are otherwise unchanged. This is the only intentional behavioral
  deviation from the COBOL baseline.

### 1.2 Traceability

The COBOL/BMS sources are **not** copied into this greenfield repository. All traceability
references the original AWS CardDemo COBOL repository at commit SHA **`27d6c6f`**. Each
endpoint section below cites the BMS mapset and symbolic-map copybook it derives from so the
field contract can be audited byte-for-byte against the frozen baseline. Paragraph-level
COBOL → Java mapping lives in `TRACEABILITY_MATRIX.md`; decision rationale lives in
`DECISION_LOG.md`.

### 1.3 Base URL, package, and runtime

| Property | Value |
|---|---|
| Base package | `com.cardemo` |
| Application port | `8080` (`http://localhost:8080`) |
| API base path prefix | `/api` |
| Actuator base path | `/actuator` (health at `/actuator/health`, Prometheus at `/actuator/prometheus`) |
| Content type | `application/json` for all request and response bodies |
| Authentication | Bearer token (JWT) in the `Authorization` header, except `POST /api/auth/signin` |

All endpoint paths in this document are written **relative to the host**, i.e. they already
include the `/api` prefix (for example `POST /api/auth/signin`).

### 1.4 How BMS screens map to REST contracts

Each BMS field is defined by a `DFHMDF` macro carrying a name, a `LENGTH=n`, an attribute
list `ATTRB=(...)`, and a screen position `POS=`. The paired symbolic-map copybook defines,
per field, an **input view** (`...AI` 01-level: `L` length halfword, `F`/`A` attribute flag,
`I` input value) and an **output view** (`...AO REDEFINES`: `C/P/H/V/O` color/protect/
highlight/validation/output). The `PIC` clause of the `I`/`O` data item gives each field's
length and type. The migration applies these deterministic rules:

| BMS / symbolic-map construct | REST contract mapping |
|---|---|
| `ATTRB=(...,UNPROT)` — unprotected (operator-enterable) field | **Request DTO input** with Jakarta Validation derived from the `PIC` clause (`@Size(max=len)`, `@NotBlank`, `@Pattern`, `@Digits`) |
| `ATTRB=(...,PROT)` / `ATTRB=(...,ASKIP)` — protected / autoskip field | **Response DTO output** (display-only) |
| `ATTRB=(...,DRK)` — dark (non-displaying) field, e.g. password | **Write-only** request field — accepted on input, **never echoed** in any response |
| `PIC X(n)` | Java `String`, `@Size(max=n)` |
| `PIC 9(n)` / `PIC S9(n)` (whole number) | Java `Integer`/`Long` or `String` of digits, `@Digits(integer=n, fraction=0)` |
| `PIC S9(n)V99` (decimal, from the backing record copybook) | Java **`BigDecimal`** with the exact scale — **never** `float`/`double` |
| Header / chrome fields (`TRNNAME`, `PGMNAME`, `TITLE01`, `TITLE02`, `CURDATE`, `CURTIME`, `APPLID`, `SYSID`) | **Excluded** from the business payload — they are screen chrome supplied by the framework, not domain data |
| `ERRMSG` (bright/`BRT` error line) | Mapped to the **standardized error response** (`§3`), not a per-DTO field |
| Pseudo-conversational COMMAREA (`COCOM01Y`) | **Stateless** REST — navigation/user context is carried by the bearer token, not server-side session |
| AID / PF keys (`DFHAID`: ENTER, PF3=Exit, PF7=page-up, PF8=page-down) | Distinct **endpoints** or **query parameters** (e.g. the **1-based** `page` query parameter at the screen's fixed row capacity; PF7/PF8 = request `page-1`/`page+1`), not imported code |

> **Field-name preservation.** REST DTO field names are derived directly from the
> symbolic-map field names / COBOL data names, and the documented length/type matches the
> `PIC` clause exactly. Where the original screen split a logical value across several
> physical fields (for example a date as `…YEAR`/`…MON`/`…DAY`, an SSN as
> `ACTSSN1`/`ACTSSN2`/`ACTSSN3`, or a phone as `…PH1A`/`…PH1B`/`…PH1C`), the DTO
> **consolidates** those components into a single canonical value — a `LocalDate` for each
> date, one `ssn` string, one `phoneNumber1`/`phoneNumber2` string — and the service layer
> composes/decomposes the persisted component layout internally. No data is lost; the API
> exposes the consolidated value rather than the screen's physical sub-fields.

> **Decimal precision.** Monetary and decimal fields are displayed on the 3270 screen as
> formatted `PIC X(n)` strings, but the *value contract* is the backing record copybook's
> `PIC S9(n)V99`. This document specifies the **`BigDecimal`** type and exact scale for every
> such field; `float` and `double` are prohibited for any value originating from a COBOL
> `PIC` clause with decimal positions.

---

## 2. Table of contents

- [1. Scope, traceability, and conventions](#1-scope-traceability-and-conventions)
- [2. Table of contents](#2-table-of-contents)
- [3. Common error contract](#3-common-error-contract)
- [4. Transaction-ID route table](#4-transaction-id-route-table)
- [5. Endpoint specifications](#5-endpoint-specifications)
  - [5.1 AuthController](#51-authcontroller-apiauth)
  - [5.2 MenuController](#52-menucontroller-apimenu)
  - [5.3 AccountController](#53-accountcontroller-apiaccounts)
  - [5.4 CardController](#54-cardcontroller-apicards)
  - [5.5 TransactionController](#55-transactioncontroller-apitransactions)
  - [5.6 BillingController](#56-billingcontroller-apibilling)
  - [5.7 ReportController](#57-reportcontroller-apireports)
  - [5.8 UserAdminController](#58-useradmincontroller-apiadminusers)
- [6. Cross-cutting contract notes](#6-cross-cutting-contract-notes)

---

## 3. Common error contract

Every error raised **once a request reaches the application's controllers or services** is
returned using a single, standardized JSON envelope, rendered by the `@RestControllerAdvice`
handler. This replaces the 3270 `ERRMSG` line (the bright/`BRT` field present on every screen)
and the COBOL `FILE STATUS` / reject-code handling. The HTTP status is derived from the COBOL
failure semantics as follows.

> **Scope of the envelope.** The standardized envelope covers every advice-handled status
> (`400`/`401`/`403`/`404`/`409`/`415`/`422`/`500`). The **two Spring Security filter-chain
> responses listed below are the documented exception**, because they are produced *before*
> request dispatch reaches the advice and therefore carry a different shape (see
> "Security-layer responses" after the table).

**Error response body:**

```json
{
  "timestamp": "2024-05-21T14:33:02.512Z",
  "status": 404,
  "error": "Not Found",
  "code": "RECORD_NOT_FOUND",
  "message": "Account 00000000011 was not found.",
  "path": "/api/accounts/00000000011",
  "correlationId": "b3a1c2d4-5e6f-7081-9a2b-3c4d5e6f7081",
  "fieldErrors": [
    { "field": "accountId", "message": "must be 11 digits" }
  ]
}
```

- `correlationId` echoes the request correlation ID injected by `CorrelationIdFilter` so an
  error can be traced through the structured logs.
- `fieldErrors` is present only for `400 Bad Request` validation failures and lists one
  entry per failed field, keyed by the request DTO field name.

**Status mapping (COBOL `FILE STATUS` / reject semantics → HTTP):**

| COBOL condition | `FILE STATUS` / reject | HTTP status | `code` | Java exception |
|---|---|---|---|---|
| Record not found on keyed read | `23` (INVALID KEY / not found) | `404 Not Found` | `RECORD_NOT_FOUND` | `RecordNotFoundException` |
| Duplicate key on write | `22` (DUPKEY / DUPREC) | `409 Conflict` | `DUPLICATE_RECORD` | `DuplicateRecordException` |
| Optimistic-lock snapshot mismatch | before/after image mismatch | `409 Conflict` | `CONCURRENT_MODIFICATION` | `ConcurrentModificationException` |
| Field validation failure | screen edit / `EVALUATE` reject | `400 Bad Request` | `VALIDATION_FAILED` | `ValidationException` (+ Jakarta `MethodArgumentNotValidException`) |
| Credit limit exceeded | reject code `102` | `422 Unprocessable Entity` | `CREDIT_LIMIT_EXCEEDED` | `CreditLimitExceededException` |
| Expired card | reject code `103` | `422 Unprocessable Entity` | `CARD_EXPIRED` | `ExpiredCardException` |
| Sign-on: unknown user | `COSGN00C` `READ-USER-SEC-FILE` `WHEN 13` (NOTFND) | `404 Not Found` | `RECORD_NOT_FOUND` | `RecordNotFoundException` |
| Sign-on: wrong password | `COSGN00C` `SEC-USR-PWD NOT = WS-USER-PWD` | `400 Bad Request` | `VALIDATION_FAILED` | `ValidationException` |
| Authorization failure (non-admin on `/api/admin/**`) | n/a (new gate) | `403 Forbidden` | `ACCESS_DENIED` | (Spring Security) |
| Unmapped I/O / system error | other `FILE STATUS` | `500 Internal Server Error` | `INTERNAL_ERROR` | `CardDemoException` (base) |

All typed exceptions extend the `com.cardemo.exception.CardDemoException` base class and are
rendered to the envelope above by a single `@RestControllerAdvice` handler.

**Security-layer responses (the documented exception to the envelope).** Spring Security's
filter chain rejects unauthenticated and unauthorized requests **before** they are dispatched to
a controller, so these two responses do **not** pass through the `@RestControllerAdvice` and do
**not** carry the `code`/`correlationId` envelope:

| Condition | HTTP status | Body shape |
|---|---|---|
| No / invalid / expired bearer token on any protected route (filter-chain `HttpStatusEntryPoint`) | `401 Unauthorized` | **empty body** (status line only) |
| Authenticated non-admin caller on `/api/admin/**` (filter-chain `hasRole` rule) | `403 Forbidden` | **bare Spring shape** `{ "timestamp", "status", "error", "path" }` — no `code`/`correlationId` |

The advice still produces the **enveloped** `401 AUTHENTICATION_FAILED` and `403 ACCESS_DENIED`
for the *post-dispatch* equivalents that reach it — a failed sign-on credential check on the
open `/api/auth/**` route (a business outcome of `COSGN00C`, not a filter rejection) and a
method-level `@PreAuthorize` denial, respectively. The `403` row in the status-mapping table
above refers to that post-dispatch, enveloped case; the filter-chain `403` uses the bare shape
documented here.

---

## 4. Transaction-ID route table

The legacy application is entered through CICS transaction identifiers; each transaction
starts a COBOL program that owns one BMS mapset. In the Java target the CICS transaction
registry (installed by `CBADMCDJ.jcl`'s `DFHCSDUP DEFINE`) has no CSD analogue — it is
replaced by Spring component scanning and the `@RequestMapping` route table below.

| CICS Txn ID | Legacy program | BMS mapset | Controller | HTTP method + path |
|---|---|---|---|---|
| `CC00` | `COSGN00C` | `COSGN00` | `AuthController` | `POST /api/auth/signin` |
| `CM00` | `COMEN01C` | `COMEN01` | `MenuController` | `GET /api/menu/main` |
| `CA00` | `COADM01C` | `COADM01` | `MenuController` | `GET /api/menu/admin` |
| `CAVW` | `COACTVWC` | `COACTVW` | `AccountController` | `GET /api/accounts/{id}` |
| `CAUP` | `COACTUPC` | `COACTUP` | `AccountController` | `PUT /api/accounts/{id}` |
| `CCLI` | `COCRDLIC` | `COCRDLI` | `CardController` | `GET /api/cards` |
| `CCDL` | `COCRDSLC` | `COCRDSL` | `CardController` | `GET /api/cards/{cardNumber}` |
| `CCUP` | `COCRDUPC` | `COCRDUP` | `CardController` | `PUT /api/cards/{cardNumber}` |
| `CT00` | `COTRN00C` | `COTRN00` | `TransactionController` | `GET /api/transactions` |
| `CT01` | `COTRN01C` | `COTRN01` | `TransactionController` | `GET /api/transactions/{id}` |
| `CT02` | `COTRN02C` | `COTRN02` | `TransactionController` | `POST /api/transactions` |
| `CB00` | `COBIL00C` | `COBIL00` | `BillingController` | `POST /api/billing/pay` |
| `CR00` | `CORPT00C` | `CORPT00` | `ReportController` | `POST /api/reports/submit` |
| `CU00` | `COUSR00C` | `COUSR00` | `UserAdminController` | `GET /api/admin/users` |
| `CU01` | `COUSR01C` | `COUSR01` | `UserAdminController` | `POST /api/admin/users` |
| `CU02` | `COUSR02C` | `COUSR02` | `UserAdminController` | `PUT /api/admin/users/{id}` |
| `CU03` | `COUSR03C` | `COUSR03` | `UserAdminController` | `DELETE /api/admin/users/{id}` |

**Transaction-count reconciliation (authoritative escalation).** The migration blueprint
headlines *"18 interactive CICS online programs"* (`docs/technical-specifications.md` L13,
L209). The frozen baseline at commit `27d6c6f` contains exactly **17** online `CO*.cbl`
members, **17** BMS mapsets, and the **17** distinct transaction IDs enumerated above
(3 entry points — sign-on `CC00`, main menu `CM00`, admin menu `CA00` — plus the 10
main-menu functions and the 4 admin user-CRUD functions). This 17-row table is therefore the
**authoritative** route contract and is intentionally **not** padded to an eighteenth row.

The headline "18" is a known internal inconsistency in the blueprint's program-count tally
(AAP §0.1.3 explicitly records the blueprint's internal inconsistencies). **Resolution:** no
eighteenth online program or mapset exists in the source tree at `27d6c6f`, so none is
invented here — fabricating an extra endpoint solely to match the headline count would
violate the Minimal Change Clause (§0.7.1, "make only the changes absolutely necessary").
The discrepancy is **escalated as a documentation defect in the blueprint headline** (the
"18" should read "17"); it is **not** a missing deliverable in this contract. The stale CSD
in `CBADMCDJ.jcl` additionally binds non-migrated **test** transactions (`CCT1`–`CCT4` →
`COTSTP1C`–`COTSTP4C`) and an admin transaction `CCDM` → `COADM00C`; these belong to an
earlier CardDemo revision, are out of scope, and are intentionally excluded from the route
table above.

---

## 5. Endpoint specifications

Each section documents, per endpoint: HTTP method + path, the source BMS mapset(s), the
purpose, the request DTO (field name, type, length/precision, required?, validation rule),
the response DTO, the HTTP status codes, and notes on technology substitution. Field names
and lengths trace directly to the cited BMS mapset and symbolic-map copybook at commit
`27d6c6f`.

### 5.1 AuthController (`/api/auth`)

Source: `app/bms/COSGN00.bms` + `app/cpy-bms/COSGN00.CPY` (CICS transaction `CC00`,
program `COSGN00C`). Backing entity: `UserSecurity` (`app/cpy/CSUSR01Y.cpy`,
`USRSEC` dataset).

#### `POST /api/auth/signin` — sign on

Authenticates a user against the `USRSEC` store and issues a bearer token. Replaces the
3270 sign-on screen and the CICS pseudo-conversational `RETURN TRANSID COMMAREA` flow.

**Request — `SignOnRequest`:**

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `userId` | `USERID` (`UNPROT`) | `String` | 8 | yes | `@NotBlank`, `@Size(max=8)` |
| `password` | `PASSWD` (`DRK`, `UNPROT`) | `String` | 8 | yes | `@NotBlank`, `@Size(max=8)` — **write-only**; never returned in any response |

**Response — `SignOnResponse`** (`200 OK`):

| Field | Source | Type | Length | Notes |
|---|---|---|---|---|
| `userId` | `SEC-USR-ID` | `String` | 8 | echoes the authenticated user id |
| `userType` | `SEC-USR-TYPE` → `UserType` | enum | 1 | `ADMIN` (COBOL `'A'`) or `USER` (COBOL `'U'`) |
| `token` | (new) | `String` | — | JWT bearer token to send on all subsequent calls |
| `toTranId` | `CDEMO-TO-TRANID` | `String` | 4 | next CICS transaction id the client should route to — `CA00` (admin menu) when `userType=ADMIN`, else `CM00` (main menu); mirrors the COBOL navigation set before the `XCTL` to `COADM01C` vs `COMEN01C` after a successful sign-on |
| `toProgram` | `CDEMO-TO-PROGRAM` | `String` | 8 | the target COBOL program name for that route — `COADM01C` when `userType=ADMIN`, else `COMEN01C` |

**Status codes:** `200 OK` (authenticated); `400 Bad Request` — a missing user id or password
(verbatim `"Please enter User ID ..."` / `"Please enter Password ..."`), a user id beyond the
`PIC X(08)` width (verbatim `"User ID can NOT be longer than 8 characters ..."`), **or** a wrong
password (verbatim `"Wrong Password. Try again ..."`, `COSGN00C` L242); `404 Not Found` — an unknown
user id (verbatim `"User not found. Try again ..."`, `COSGN00C` L249). The two distinct
credential-failure outcomes (unknown user → `404`, wrong password → `400`) reproduce `COSGN00C`
behavioral parity exactly (AAP §0.7.2); each carries its verbatim COBOL message.

**Notes — technology substitution.** Password verification uses **BCrypt**
(`PasswordEncoder.matches`) instead of the COBOL plaintext comparison — the single permitted
behavioral change. The `PASSWD` field is `DRK` (non-displaying) on the 3270 screen; the REST
contract preserves that semantic by treating `password` as write-only and never serializing
it on any response. The COMMAREA user context that the COBOL program forwarded on `XCTL` is
replaced by the stateless JWT.

---

### 5.2 MenuController (`/api/menu`)

Source: `app/bms/COMEN01.bms` + `app/cpy-bms/COMEN01.CPY` (main menu, transaction `CM00`,
program `COMEN01C`) and `app/bms/COADM01.bms` + `app/cpy-bms/COADM01.CPY` (admin menu,
transaction `CA00`, program `COADM01C`). Option routing metadata derives from
`app/cpy/COMEN02Y.cpy` (main, 10 active options) and `app/cpy/COADM02Y.cpy`
(admin, 4 active options).

#### `GET /api/menu/{type}` — retrieve a menu

Returns the option list that the COBOL menu program would have rendered into the `OPTN001`–
`OPTN012` display lines. Both source screens expose 12 physical 40-character option slots
(`OPTNxxx`, `PIC X(40)`) and a 2-character `OPTION` input field (`PIC X(2)`, numeric); the
main menu populates 10 of those slots and the admin menu populates 4.

**Path parameter:**

| Parameter | Type | Allowed values | Notes |
|---|---|---|---|
| `type` | `String` | `main`, `admin` | `main` ← `COMEN01`; `admin` ← `COADM01`. `admin` requires `userType=ADMIN`. |

**Response — `MenuResponse`** (`200 OK`):

| Field | Source | Type | Notes |
|---|---|---|---|
| `menuType` | path | `String` | `main` or `admin` |
| `options` | `OPTN001`–`OPTN012` (`PIC X(40)`) | array of `MenuOption` | one entry per active option |
| `options[].optionNumber` | menu row index | `Integer` | the value the operator typed into `OPTION` to select this row |
| `options[].label` | `OPTNxxx` text | `String(40)` | the 40-character option label as shown on the screen |
| `options[].targetRoute` | `COMEN02Y`/`COADM02Y` program name | `String` | the controller route the option navigates to (resolved from the COBOL target program, e.g. `COACTVWC` → `GET /api/accounts/{id}`) |

**Status codes:** `200 OK`; `400 Bad Request` (`type` not in `{main, admin}`);
`403 Forbidden` (`admin` requested without an `ADMIN` token).

**Notes — technology substitution.** Menu selection on the 3270 screen used the `OPTION`
field plus ENTER to drive an `XCTL` to the chosen program. The REST contract returns the
option list with explicit `targetRoute` values so the client navigates directly to the next
endpoint; there is no server-held conversational state. The 12 vs. 10/4 slot difference is
preserved exactly — only the active options are returned.

---

### 5.3 AccountController (`/api/accounts`)

Source: `app/bms/COACTVW.bms` + `app/cpy-bms/COACTVW.CPY` (account view, transaction `CAVW`,
program `COACTVWC`) and `app/bms/COACTUP.bms` + `app/cpy-bms/COACTUP.CPY` (account update,
transaction `CAUP`, program `COACTUPC`). Backing entities: `Account` (`app/cpy/CVACT01Y.cpy`,
`ACCTDAT`) and `Customer` (`app/cpy/CVCUS01Y.cpy` / `CUSTREC.cpy`, `CUSTDAT`), joined through
the cross-reference (`CXACAIX`).

Monetary fields are displayed on the screen as formatted `PIC X(15)` strings, but the value
contract is the `Account` record's `PIC S9(10)V99` → **`BigDecimal`** with **scale 2**
(precision 12). The account id is an 11-digit number (`ACCT-ID PIC 9(11)`; the view screen
declares `ACCTSID` as `PIC 99999999999`).

#### `GET /api/accounts/{id}` — view account

Read-only retrieval of an account and its associated customer. On the source screen every
data field is protected/display (output only); the sole input is the `ACCTSID` key.

**Path parameter:**

| Parameter | BMS field | Type | Length | Validation |
|---|---|---|---|---|
| `id` | `ACCTSID` (`UNPROT`, numeric) | `String` (digits) | 11 | `@Digits(integer=11, fraction=0)` |

**Response — `AccountDto`** (`200 OK`) — account fields (all output / display on `COACTVW`):

| Field | BMS field | Type | Length / precision | Notes |
|---|---|---|---|---|
| `accountId` | `ACCTSID` | `String` (digits) | 11 | account key |
| `accountStatus` | `ACSTTUS` | `String` | 1 | active flag (`Y`/`N`) |
| `openDate` | `ADTOPEN` | `String` (date) | 10 | `yyyy-mm-dd` |
| `expirationDate` | `AEXPDT` | `String` (date) | 10 | |
| `reissueDate` | `AREISDT` | `String` (date) | 10 | |
| `creditLimit` | `ACRDLIM` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `cashCreditLimit` | `ACSHLIM` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentBalance` | `ACURBAL` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentCycleCredit` | `ACRCYCR` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentCycleDebit` | `ACRCYDB` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `accountGroupId` | `AADDGRP` | `String` | 10 | disclosure group id |
| `version` | — (no BMS field) | `Integer` | — | optimistic-lock token mirrored from the `Account` `@Version`. The COBOL `COACTUPC` read-before-update snapshot has no on-screen field; this token carries that snapshot. Echo it back unchanged on `PUT` for stale-form detection. |

**Response — embedded customer fields** (from `COACTVW`, output / display):

| Field | BMS field | Type | Length | Notes |
|---|---|---|---|---|
| `customerId` | `ACSTNUM` | `String` (digits) | 9 | |
| `ssn` | `ACSTSSN` | `String` | 12 | formatted SSN (display) |
| `dateOfBirth` | `ACSTDOB` | `String` (date) | 10 | |
| `ficoScore` | `ACSTFCO` | `Integer` | 3 | |
| `firstName` | `ACSFNAM` | `String` | 25 | |
| `middleName` | `ACSMNAM` | `String` | 25 | |
| `lastName` | `ACSLNAM` | `String` | 25 | |
| `addressLine1` | `ACSADL1` | `String` | 50 | |
| `addressLine2` | `ACSADL2` | `String` | 50 | |
| `city` | `ACSCITY` | `String` | 50 | |
| `state` | `ACSSTTE` | `String` | 2 | US state code |
| `zipCode` | `ACSZIPC` | `String` | 5 | |
| `countryCode` | `ACSCTRY` | `String` | 3 | |
| `phoneNumber1` | `ACSPHN1` | `String` | 13 | formatted phone (display) |
| `phoneNumber2` | `ACSPHN2` | `String` | 13 | formatted phone (display) |
| `governmentIssuedId` | `ACSGOVT` | `String` | 20 | |
| `eftAccountId` | `ACSEFTC` | `String` | 10 | |
| `primaryCardHolderIndicator` | `ACSPFLG` | `String` | 1 | |

**Status codes:** `200 OK`; `400 Bad Request` (`id` non-numeric or more than 11 digits — `@Digits(integer=11, fraction=0)`; note a shorter all-digit id such as `1` is accepted and resolves normally); `404 Not Found`
(account or cross-reference not found — COBOL `FILE STATUS 23`).

#### `PUT /api/accounts/{id}` — update account

Updates the account and its customer in a single transaction. On `COACTUP` every data field
is unprotected (operator-enterable). The legacy 3270 screen split dates, the SSN and the phone
numbers across component sub-fields (year/month/day, three SSN parts, area/prefix/line); the
migrated request DTO **consolidates** each of these into a single canonical value — one
`LocalDate` per date, one `ssn` string, and one `phoneNumber1`/`phoneNumber2` string. The
service layer composes/decomposes the legacy component pieces internally (replacing the LE
`CEEDAYS` date edit), so the API client works exclusively with the consolidated values.

**Path parameter:** `id` ← `ACCTSID` (`String`, 11 digits).

**Request body — `AccountDto`.** The update request binds the **same consolidated
`AccountDto`** documented under the `GET` response above (it is the one canonical account shape;
there is no separate split-field request model). The controller binds it as `@RequestBody`
**without** `@Valid`: input validation is performed by `AccountUpdateService` as the ordered
`COACTUPC` field-edit cascade, which returns the verbatim COBOL operator messages (preserving
3270 message parity, §0.7.2). The "Validation (service-enforced)" column below therefore
describes the rule the **service** applies, not a controller-level Jakarta constraint. Fields
not present in the JSON are treated as absent by the service edit cascade.

| Field | BMS field(s) | Type | Length / precision | Validation (service-enforced) |
|---|---|---|---|---|
| `accountStatus` | `ACSTTUS` | `String` | 1 | active flag, `Y`/`N` |
| `openDate` | `ADTOPEN` (screen `OPNYEAR`+`OPNMON`+`OPNDAY`) | `LocalDate` | `yyyy-mm-dd` | valid calendar date (strict, no normalization) |
| `expirationDate` | `AEXPDT` (screen `EXPYEAR`+`EXPMON`+`EXPDAY`) | `LocalDate` | `yyyy-mm-dd` | valid calendar date |
| `reissueDate` | `AREISDT` (screen `RISYEAR`+`RISMON`+`RISDAY`) | `LocalDate` | `yyyy-mm-dd` | valid calendar date |
| `creditLimit` | `ACRDLIM` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `cashCreditLimit` | `ACSHLIM` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentBalance` | `ACURBAL` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentCycleCredit` | `ACRCYCR` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `currentCycleDebit` | `ACRCYDB` | `BigDecimal` | scale 2 | `S9(10)V99` |
| `accountGroupId` | `AADDGRP` | `String` | 10 | disclosure group id |
| `customerId` | `ACSTNUM` | `String` (digits) | 9 | numeric customer key |
| `ssn` | `ACTSSN1`+`ACTSSN2`+`ACTSSN3` (consolidated) | `String` | up to 12 | single SSN value (the three screen parts consolidated) |
| `dateOfBirth` | `ACSTDOB` (screen `DOBYEAR`+`DOBMON`+`DOBDAY`) | `LocalDate` | `yyyy-mm-dd` | valid calendar date |
| `ficoScore` | `ACSTFCO` | `String` (digits) | 3 | numeric |
| `firstName` | `ACSFNAM` | `String` | 25 | |
| `middleName` | `ACSMNAM` | `String` | 25 | |
| `lastName` | `ACSLNAM` | `String` | 25 | |
| `addressLine1` | `ACSADL1` | `String` | 50 | |
| `addressLine2` | `ACSADL2` | `String` | 50 | |
| `city` | `ACSCITY` | `String` | 50 | |
| `state` | `ACSSTTE` | `String` | 2 | US-state lookup |
| `zipCode` | `ACSZIPC` | `String` | 5 | state/ZIP-prefix lookup |
| `countryCode` | `ACSCTRY` | `String` | 3 | |
| `phoneNumber1` | `ACSPH1A`+`ACSPH1B`+`ACSPH1C` (consolidated) | `String` | 13 | single phone value; NANPA area-code lookup |
| `phoneNumber2` | `ACSPH2A`+`ACSPH2B`+`ACSPH2C` (consolidated) | `String` | 13 | single phone value; NANPA area-code lookup |
| `governmentIssuedId` | `ACSGOVT` | `String` | 20 | |
| `eftAccountId` | `ACSEFTC` | `String` | 10 | |
| `primaryCardHolderIndicator` | `ACSPFLG` | `String` | 1 | |
| `version` | — (no BMS field) | `Long` | — | the optimistic-lock token from the `GET` response. **Enforced when present:** if it does not equal the account's current stored version the update is rejected with `409 Conflict` (`CONCURRENT_MODIFICATION`) before any write. When omitted, server-side `@Version` remains the safety net. |

**Response — `AccountDto`** (`200 OK`): the refreshed account view (same shape as the
`GET` response above), including the new `version` after a successful update.

**Status codes:** `200 OK`; `400 Bad Request` (field validation failures, returned as
`fieldErrors`); `404 Not Found`; `409 Conflict` (optimistic-lock mismatch).

**Notes — technology substitution.** The COBOL `COACTUPC` updates `ACCTDAT` and `CUSTDAT`
together and is the system's only `SYNCPOINT ROLLBACK` site; the endpoint maps this to a
Spring `@Transactional(rollbackFor = …)` method so the dual update is atomic. The program's
before/after record-image comparison (optimistic concurrency) maps to JPA `@Version` on the
`Account` entity — a stale update returns `409 Conflict` (`CONCURRENT_MODIFICATION`). To
faithfully reproduce the COBOL check (which compared the image read at *display* time against
the record re-read at *update* time), the `GET` response carries a `version` token that the
client echoes back on `PUT`; the service compares it against the current stored version **before
applying any change** and returns `409 Conflict` on a mismatch, so a form loaded before another
user's completed update is rejected even though server-side `@Version` alone would not detect it.
The `version` check is enforced only when the client supplies it; the entity `@Version` is the
fallback safety net otherwise. The API request carries each date as a single
`java.time.LocalDate` (the legacy screen's year/month/day, three-part SSN and area/prefix/line
components are already consolidated in `AccountDto`); the service layer performs strict calendar
validation on those dates (replacing the LE `CEEDAYS` date validation) and reassembles the
legacy component layouts internally where the persisted record demands them.

---

### 5.4 CardController (`/api/cards`)

Source: `app/bms/COCRDLI.bms` + `app/cpy-bms/COCRDLI.CPY` (card list, transaction `CCLI`,
program `COCRDLIC`), `app/bms/COCRDSL.bms` + `app/cpy-bms/COCRDSL.CPY` (card detail,
transaction `CCDL`, program `COCRDSLC`), and `app/bms/COCRDUP.bms` +
`app/cpy-bms/COCRDUP.CPY` (card update, transaction `CCUP`, program `COCRDUPC`). Backing
entity: `Card` (`app/cpy/CVACT02Y.cpy`, `CARDDAT`). Card number is `CARD-NUM PIC X(16)`;
account id is `CARD-ACCT-ID PIC 9(11)`.

#### `GET /api/cards` — list cards (paginated)

Paginated browse of cards, optionally filtered by account id and/or card number. The
`COCRDLI` screen renders **7 rows per page** (`CRDSEL1`–`CRDSEL7`, `ACCTNO1`–`ACCTNO7`,
`CRDNUM1`–`CRDNUM7`, `CRDSTS1`–`CRDSTS7`).

**Query parameters:**

| Parameter | BMS field | Type | Length | Required | Notes |
|---|---|---|---|---|---|
| `accountId` | `ACCTSID` (`UNPROT`) | `String` (digits) | 11 | no | filter by owning account |
| `cardNumber` | `CARDSID` (`UNPROT`) | `String` | 16 | no | filter by card number |
| `page` | (PF7/PF8) | `Integer` | — | no | **1-based** page index; default `1`. The page size is **fixed at 7 rows** (`CardDto.ROWS_PER_PAGE`, the `COCRDLI` screen capacity); there is **no `size` parameter** (BMS-parity fixed-row browse). Out-of-range values are normalized by the service. |

**Response — `CardDto`** (`200 OK`): the list-mode projection of the shared `CardDto`. The browse page is returned as the `cards[]` array (at most **7** rows = `CardDto.ROWS_PER_PAGE`), and `pageNumber` echoes the current 1-based page. This preserves the `COCRDLI` fixed-row screen contract exactly; it is **not** a generic offset/size paged envelope, so there is no `totalElements`/`hasNext`/`hasPrevious` metadata (navigation is the client requesting `page-1` for PF7 / `page+1` for PF8).

| Field | Source | Type | Length | Notes |
|---|---|---|---|---|
| `cards[].selectionFlag` | `CRDSELn` | `String` | 1 | row selection flag (`S`/blank) |
| `cards[].accountId` | `ACCTNOn` | `String` (digits) | 11 | |
| `cards[].cardNumber` | `CRDNUMn` | `String` | 16 | |
| `cards[].activeStatus` | `CRDSTSn` | `String` | 1 | `Y`/`N` |
| `pageNumber` | `PAGENO` | `String` | 3 | current **1-based** page (`PAGENO PIC X(03)`) |

#### `GET /api/cards/{cardNumber}` — card detail

Single keyed read of one card (`COCRDSL`). Inputs are the account id and card number; all
other fields are display.

**Path parameter:** `cardNumber` ← `CARDSID` (`String`, 16, `@Size(max=16)`).

**Query parameter (required):** `accountId` ← `ACCTSID` (`String`, 11 digits) — supplied alongside
the card-number path key. It is bound as `@RequestParam(required = false)` (so the framework itself
does not reject its absence), but `CardDetailService` enforces it exactly as the `COCRDSLC` edit does:
a missing or blank `accountId` raises the verbatim COBOL message **"Account number not provided"** and
returns `400 Bad Request`. A successful read therefore **requires** the account id alongside the card
number (the combined `COCRDSL` key).

**Response — `CardDto`** (`200 OK`):

| Field | BMS field | Type | Length | Notes |
|---|---|---|---|---|
| `accountId` | `ACCTSID` | `String` (digits) | 11 | |
| `cardNumber` | `CARDSID` | `String` | 16 | |
| `embossedName` | `CRDNAME` | `String` | 50 | `CARD-EMBOSSED-NAME` |
| `activeStatus` | `CRDSTCD` | `String` | 1 | `Y`/`N` |
| `expiryMonth` | `EXPMON` | `String` (digits) | 2 | |
| `expiryYear` | `EXPYEAR` | `String` (digits) | 4 | |
| `version` | — (no BMS field) | `Integer` | — | optimistic-lock token mirrored from the `Card` `@Version`; echo it back unchanged on `PUT` for stale-form detection |

**Status codes:** `200 OK`; `400 Bad Request`; `404 Not Found` (`FILE STATUS 23`).

#### `PUT /api/cards/{cardNumber}` — update card

Updates a card with optimistic concurrency (`COCRDUP`). The account id is a protected
display key on the screen; the editable fields are the embossed name, status, and expiry.

**Path parameter:** `cardNumber` ← `CARDSID` (`String`, 16, `@Size(max=16)`).

**Request — `CardUpdateRequest`** (fields `UNPROT` on `COCRDUP`):

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `embossedName` | `CRDNAME` | `String` | 50 | yes | `@Size(max=50)` |
| `activeStatus` | `CRDSTCD` | `String` | 1 | yes | `@Pattern("[YN]")` |
| `expiryMonth` | `EXPMON` | `String` (digits) | 2 | yes | `@Digits(integer=2)`, `01`–`12` |
| `expiryYear` | `EXPYEAR` | `String` (digits) | 4 | yes | `@Digits(integer=4)` |
| `expiryDay` | `EXPDAY` (`DRK`) | `String` (digits) | 2 | no | hidden working field; `@Digits(integer=2)` |
| `version` | — (no BMS field) | `Integer` | — | no | the optimistic-lock token returned by the `GET` detail; **enforced when present** — if it does not equal the card's current stored version the update is rejected with `409 Conflict` (`CONCURRENT_MODIFICATION`) before any write. When omitted, server-side `@Version` remains the safety net. |

**Response — `CardDto`** (`200 OK`): the refreshed card detail.

**Status codes:** `200 OK`; `400 Bad Request`; `404 Not Found`; `409 Conflict`
(optimistic-lock mismatch).

**Notes — technology substitution.** Like account update, `COCRDUPC` performs a before/after
record-image comparison to detect changes made by another user between the read and the
rewrite. The endpoint reproduces this with a two-layer optimistic-concurrency strategy: the
`GET` detail carries the `Card` `@Version` token, the client echoes that `version` back on the
`PUT`, and the service compares the client-supplied `version` against the entity's current
stored version **before any write** — a mismatch is rejected with `409 Conflict`
(`CONCURRENT_MODIFICATION`), which detects a form loaded before another completed update (the
stale-client case). The client-version check is enforced only when `version` is supplied; when
omitted, the server-side JPA `@Version` on the `Card` entity remains the safety net and still
yields `409 Conflict` on a concurrent change detected after the read. The `EXPDAY` field is
`DRK` (a hidden working field on the screen) and is therefore optional and never displayed.

---

### 5.5 TransactionController (`/api/transactions`)

Source: `app/bms/COTRN00.bms` + `app/cpy-bms/COTRN00.CPY` (transaction list, transaction
`CT00`, program `COTRN00C`), `app/bms/COTRN01.bms` + `app/cpy-bms/COTRN01.CPY` (transaction
detail, transaction `CT01`, program `COTRN01C`), and `app/bms/COTRN02.bms` +
`app/cpy-bms/COTRN02.CPY` (transaction add, transaction `CT02`, program `COTRN02C`). Backing
entity: `Transaction` (`app/cpy/CVTRA05Y.cpy`, `TRANSACT`). Transaction id is
`TRAN-ID PIC X(16)`; amount is `TRAN-AMT PIC S9(09)V99` → **`BigDecimal`** with **scale 2**
(precision 11).

#### `GET /api/transactions` — list transactions (paginated)

Paginated browse, optionally filtered by transaction id. The `COTRN00` screen renders
**10 rows per page** (`SEL0001`–`SEL0010`, `TRNID01`–`TRNID10`, `TDATE01`–`TDATE10`,
`TDESC01`–`TDESC10`, `TAMT001`–`TAMT010`).

**Query parameters:**

| Parameter | BMS field | Type | Length | Required | Notes |
|---|---|---|---|---|---|
| `transactionId` | `TRNIDIN` (`UNPROT`) | `String` | 16 | no | starting/filter transaction id |
| `page` | (PF7/PF8) | `Integer` | — | no | **1-based** page index; default `1`. The page size is **fixed at 10 rows** (`TransactionDto.ROWS_PER_PAGE`, the `COTRN00` screen capacity); there is **no `size` parameter** (BMS-parity fixed-row browse). The service converts the 1-based page to its 0-based browse index and normalizes out-of-range values. |

**Response — `TransactionDto`** (`200 OK`): the list-mode projection of the shared `TransactionDto`. The browse page is returned as the `transactions[]` array (at most **10** rows = `TransactionDto.ROWS_PER_PAGE`), and `pageNumber` echoes the current 1-based page. This preserves the `COTRN00` fixed-row screen contract exactly; it is **not** a generic offset/size paged envelope, so there is no `totalElements`/`hasNext`/`hasPrevious` metadata (navigation is the client requesting `page-1` for PF7 / `page+1` for PF8).

| Field | BMS field | Type | Length / precision | Notes |
|---|---|---|---|---|
| `transactions[].selectionFlag` | `SEL00nn` | `String` | 1 | row selection flag |
| `transactions[].transactionId` | `TRNIDnn` | `String` | 16 | |
| `transactions[].date` | `TDATEnn` | `String` (date) | 8 | display date (`TDATE0n PIC X(8)`) |
| `transactions[].description` | `TDESCnn` | `String` | 26 | truncated description (list view) |
| `transactions[].amount` | `TAMTnnn` | `BigDecimal` | scale 2 | `S9(09)V99` |
| `pageNumber` | `PAGENUM` | `String` | 8 | current **1-based** page (`PAGENUM PIC X(08)`) |

#### `GET /api/transactions/{id}` — transaction detail

Single keyed read (`COTRN01`). All fields are display except the `TRNIDIN` key.

**Path parameter:** `id` ← `TRNIDIN` (`String`, 16, `@Size(max=16)`).

**Response — `TransactionDto`** (`200 OK`):

| Field | BMS field | Type | Length / precision | Notes |
|---|---|---|---|---|
| `transactionId` | `TRNID` | `String` | 16 | |
| `cardNumber` | `CARDNUM` | `String` | 16 | |
| `typeCode` | `TTYPCD` | `String` | 2 | |
| `categoryCode` | `TCATCD` | `String` (digits) | 4 | `TRAN-CAT-CD PIC 9(04)` |
| `source` | `TRNSRC` | `String` | 10 | |
| `description` | `TDESC` | `String` | 60 | screen field; backing record holds `PIC X(100)` |
| `amount` | `TRNAMT` | `BigDecimal` | scale 2 | `S9(09)V99` |
| `originationDate` | `TORIGDT` | `String` (date) | 10 | |
| `processingDate` | `TPROCDT` | `String` (date) | 10 | |
| `merchantId` | `MID` | `String` (digits) | 9 | `TRAN-MERCHANT-ID PIC 9(09)` |
| `merchantName` | `MNAME` | `String` | 30 | screen field; backing record holds `PIC X(50)` |
| `merchantCity` | `MCITY` | `String` | 25 | screen field; backing record holds `PIC X(50)` |
| `merchantZip` | `MZIP` | `String` | 10 | |

**Status codes:** `200 OK`; `400 Bad Request`; `404 Not Found` (`FILE STATUS 23`).

#### `POST /api/transactions` — add transaction

Creates a transaction (`COTRN02`). All fields are unprotected input; the transaction id is
auto-generated. The screen carries a `CONFIRM` (Y/N) field used as a two-step confirmation.

**Request — `TransactionAddRequest`** (fields `UNPROT` on `COTRN02`):

| Field | BMS field | Type | Length / precision | Required | Validation |
|---|---|---|---|---|---|
| `accountId` | `ACTIDIN` | `String` (digits) | 11 | yes | `@Digits(integer=11)`; resolves the card cross-reference |
| `cardNumber` | `CARDNIN` | `String` | 16 | yes | `@Size(max=16)` |
| `typeCode` | `TTYPCD` | `String` | 2 | yes | `@Size(max=2)` |
| `categoryCode` | `TCATCD` | `String` (digits) | 4 | yes | `@Digits(integer=4)` |
| `source` | `TRNSRC` | `String` | 10 | yes | `@Size(max=10)` |
| `description` | `TDESC` | `String` | 60 | yes | `@Size(max=60)` |
| `amount` | `TRNAMT` | `BigDecimal` | scale 2 | yes | `@Digits(integer=9, fraction=2)` |
| `originationDate` | `TORIGDT` | `String` (date) | 10 | yes | valid `yyyy-mm-dd` |
| `processingDate` | `TPROCDT` | `String` (date) | 10 | yes | valid `yyyy-mm-dd` |
| `merchantId` | `MID` | `String` (digits) | 9 | yes | `@Digits(integer=9)` |
| `merchantName` | `MNAME` | `String` | 30 | yes | `@Size(max=30)` |
| `merchantCity` | `MCITY` | `String` | 25 | yes | `@Size(max=25)` |
| `merchantZip` | `MZIP` | `String` | 10 | yes | `@Size(max=10)` |
| `confirm` | `CONFIRM` | `String` | 1 | no | `@Size(max=1)` — `Y`/`N`, case-insensitive. A blank or otherwise-invalid value triggers a service-level **re-prompt** (it is not rejected with a `400`); the accept/clear/re-prompt decision is conversational logic in `COTRN02C` (accepts `'Y'`/`'y'`/`'N'`/`'n'`), not a DTO field-format rule |

**Response — `TransactionDto`** (`201 Created`): the created transaction including the
auto-generated `transactionId`. The generated id is carried in the **response body** (the
`transactionId` field); **no `Location` header is emitted** — the legacy `COTRN02C` flow had no
such concept and none is added (Minimal Change Clause, §0.7.1).

**Status codes:** `201 Created`; `400 Bad Request` (validation failure, **including an
unresolved account id / card cross-reference**). When `confirm` is not `Y`, `COTRN02C`
re-prompts for confirmation rather than committing — this conversational outcome is handled in
service logic, not as a field-format rejection.

> **Why an unresolved account/cross-reference here is `400`, not `404` (behavioral parity,
> §0.7.2).** In `COTRN02C` the account/card cross-reference is resolved by the `CXACAIX`
> alternate-index lookup *inside* the `VALIDATE-INPUT-KEY-FIELDS` edit cascade; a miss sets the
> error flag and re-displays the add screen with an edit message — i.e. it is a **field-validation
> reject**, so `TransactionAddService` raises `ValidationException` → `400 VALIDATION_FAILED`.
> This is deliberately different from `POST /api/billing/pay`, whose `COBIL00C` performs a
> *direct keyed read* of `ACCTDAT` and treats a miss as a **record-not-found** condition →
> `RecordNotFoundException` → `404`. The two endpoints mirror their distinct COBOL sources
> exactly and are intentionally **not** homogenized.

**Notes — technology substitution.** `COTRN02C` auto-generates the transaction id by
browsing the `TRANSACT` file to the end and incrementing the highest id; the Java target
reproduces this with a sequence factory (`TransactionRepository.findMaxTransactionId` +
increment) inside the same transaction that performs the insert, preserving the exact id
allocation behavior. The two-step `CONFIRM` prompt is preserved as the `confirm` field.

---

### 5.6 BillingController (`/api/billing`)

Source: `app/bms/COBIL00.bms` + `app/cpy-bms/COBIL00.CPY` (bill payment, transaction `CB00`,
program `COBIL00C`). Backing entities: `Account` (`ACCTDAT`) and `Transaction` (`TRANSACT`).

#### `POST /api/billing/pay` — pay account balance

Pays off the displayed current balance for an account: it zeroes the balance and writes a
balancing payment transaction, in a single transaction. The screen displays the current
balance and prompts for a Y/N confirmation before committing.

**Request — `BillPaymentRequest`:**

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `accountId` | `ACTIDIN` (`UNPROT`) | `String` (digits) | 11 | yes | `@Digits(integer=11)` |
| `confirm` | `CONFIRM` (`UNPROT`) | `String` | 1 | no | `@Size(max=1)` — `Y`/`N`, case-insensitive; the accept/re-prompt decision is conversational logic in `COBIL00C` / `BillPaymentService`, not a DTO field-format rule |

**Response — `BillPaymentRequest.Response`** (`200 OK`) — the nested `Response` type declared on `BillPaymentRequest`:

| Field | BMS field | Type | Length / precision | Notes |
|---|---|---|---|---|
| `currentBalance` | `CURBAL` | `BigDecimal` | scale 2 | balance presented for confirmation (`ACCT-CURR-BAL`, `S9(10)V99`) |
| `transactionId` | generated | `String` | 16 | id of the balancing payment transaction created |
| `newBalance` | computed | `BigDecimal` | scale 2 | balance after payment (zero on success) |
| `message` | `ERRMSG` | `String` | 78 | result / prompt / validation message line (`ERRMSG PIC X(78)`) |
| `confirmationNumber` | generated | `String` | 16 | confirmation reference (the generated transaction id) for the completed payment |

**Status codes:** `200 OK`; `400 Bad Request` (validation failure);
`404 Not Found` (account not found). When `confirm` is not `Y`, `COBIL00C` /
`BillPaymentService` re-prompts for confirmation rather than committing the payment —
this conversational outcome is handled in service logic, not as a field-format rejection.

**Notes — technology substitution.** The balance update and the payment-transaction insert
are performed in one Spring `@Transactional` method so they commit or roll back together. The
`CURBAL` field is display-only (`PIC X(14)` on the screen) and carries the `BigDecimal`
balance value; `confirm` preserves the screen's Y/N safety prompt.

---

### 5.7 ReportController (`/api/reports`)

Source: `app/bms/CORPT00.bms` + `app/cpy-bms/CORPT00.CPY` (transaction reports, transaction
`CR00`, program `CORPT00C`). This is the single online-to-batch bridge in the system.

#### `POST /api/reports/submit` — submit a transaction report request

Submits a request to generate a transaction report. The screen offers three mutually
exclusive report types (monthly / yearly / custom) and a custom date range, plus a Y/N
confirmation.

**Request — `ReportRequest`** (fields `UNPROT` on `CORPT00`):

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `monthly` | `MONTHLY` | `String` | 1 | conditional | one of `monthly`/`yearly`/`custom` must be selected (`Y`) |
| `yearly` | `YEARLY` | `String` | 1 | conditional | mutually exclusive with the others |
| `custom` | `CUSTOM` | `String` | 1 | conditional | when `Y`, the date range is required |
| `startMonth` | `SDTMM` | `String` (digits) | 2 | conditional | `@Size(max=2)`, `@Pattern("\d{0,2}")` — structural digit/width only; calendar validity and range (`01`–`12`) are checked by `DateValidationService` (`CSUTLDTC`) for custom reports |
| `startDay` | `SDTDD` | `String` (digits) | 2 | conditional | `@Size(max=2)`, `@Pattern("\d{0,2}")` — structural only; day validity/range via `DateValidationService` |
| `startYear` | `SDTYYYY` | `String` (digits) | 4 | conditional | `@Size(max=4)`, `@Pattern("\d{0,4}")` — structural only; year validity via `DateValidationService` |
| `endMonth` | `EDTMM` | `String` (digits) | 2 | conditional | `@Size(max=2)`, `@Pattern("\d{0,2}")` — structural only; month validity/range via `DateValidationService` |
| `endDay` | `EDTDD` | `String` (digits) | 2 | conditional | `@Size(max=2)`, `@Pattern("\d{0,2}")` — structural only; day validity/range via `DateValidationService` |
| `endYear` | `EDTYYYY` | `String` (digits) | 4 | conditional | `@Size(max=4)`, `@Pattern("\d{0,4}")` — structural only; year validity via `DateValidationService` |
| `confirm` | `CONFIRM` | `String` | 1 | no | `@Size(max=1)` — `Y`/`N`, case-insensitive; the submit/re-prompt decision is conversational logic in `CORPT00C` / `ReportSubmissionService`, not a DTO field-format rule |

**Response — `ReportSubmissionResponse`** (`202 Accepted`):

| Field | Source | Type | Notes |
|---|---|---|---|
| `jobId` | generated | `String` | identifier of the queued report job |
| `reportType` | request | `String` | `monthly`, `yearly`, or `custom` |
| `status` | — | `String` | `SUBMITTED` |

**Status codes:** `202 Accepted` (request queued); `400 Bad Request` (no report type
selected, an invalid/inconsistent date range, or a field exceeding its BMS/PIC width). When
`confirm` is not `Y`, `CORPT00C` / `ReportSubmissionService` re-prompts for confirmation rather
than submitting — this conversational outcome is handled in service logic, not as a
field-format rejection.

**Cancellation response (`200 OK`).** This note is *additive* and does **not** alter the
`202 Accepted` submit contract documented in the response table above. When `confirm` is
`N`/`n` the operator cancelled and nothing is queued; the endpoint returns the **same**
`ReportSubmissionResponse` shape with `status` = `CANCELLED` and a `null` `jobId`, at
`200 OK`. This is the explicit representation of the conversational "not `Y`" outcome
referenced under **Status codes** — a confirmed (`Y`) submission still returns
`202 Accepted` with a generated `jobId` and `status` = `SUBMITTED` exactly as specified.

**Input-width enforcement (boundary parity).** The BMS/PIC widths in the request table
(`@Size`) are enforced before any report logic runs, reproducing the physical 3270 field
lengths. An over-width field is rejected with a value-free `400 Bad Request` message that
names only the field and its maximum width — the offending value is never echoed back.

**Notes — technology substitution.** `CORPT00C` wrote the report request to the CICS
Transient Data Queue `JOBS`, which triggered JES batch submission — the only online↔batch
coupling in the system. The endpoint preserves this by **publishing the request to AWS SQS**
(`carddemo-report-jobs.fifo`) via Spring Cloud AWS, which triggers the corresponding Spring
Batch report job. Because the work is performed asynchronously by the batch job, the endpoint
returns `202 Accepted` with a `jobId` rather than the finished report.

---

### 5.8 UserAdminController (`/api/admin/users`)

Source: `app/bms/COUSR00.bms` + `app/cpy-bms/COUSR00.CPY` (user list, transaction `CU00`,
program `COUSR00C`), `app/bms/COUSR01.bms` + `app/cpy-bms/COUSR01.CPY` (user add, transaction
`CU01`, program `COUSR01C`), `app/bms/COUSR02.bms` + `app/cpy-bms/COUSR02.CPY` (user update,
transaction `CU02`, program `COUSR02C`), and `app/bms/COUSR03.bms` +
`app/cpy-bms/COUSR03.CPY` (user delete, transaction `CU03`, program `COUSR03C`). Backing
entity: `UserSecurity` (`app/cpy/CSUSR01Y.cpy`, `USRSEC`).

> **Authorization.** Every endpoint in this section is **admin-only**: it requires a bearer
> token whose `userType` is `ADMIN`. A non-admin caller receives `403 Forbidden`
> (`ACCESS_DENIED`). This realizes the legacy reachability rule — the user-administration
> screens were only reachable from the admin menu (`COADM01`), which the sign-on flow routed
> to exclusively for `'A'`-type users.

> **Binding type and operation-scoped validation.** All four operations bind the single
> `UserSecurityDto` (the consolidation of the four `COUSR0x` symbolic maps); there is no
> separate create/update/summary DTO. The field-level edits the COBOL programs enforce differ
> by operation, so the DTO carries two Jakarta Bean Validation group tokens — `OnAdd` and
> `OnUpdate` — and the endpoints activate the matching one (`@Validated(UserSecurityDto.OnAdd.class)`
> on add, `@Validated(UserSecurityDto.OnUpdate.class)` on update). The "Validation" column in
> the request tables below states the constraint **and the group it fires under**: `userId` is
> `@NotBlank` only `OnAdd` (update/delete take the id from the path); `firstName`, `lastName`,
> `password` (`@NotBlank`) and `userType` (`@NotNull`) fire on **both** `OnAdd` and `OnUpdate`,
> reproducing the COUSR01C (add) and COUSR02C (update) empty-field edits. The always-on
> `@Size(max=n)` width guards apply to every operation.

#### `GET /api/admin/users` — list users (paginated)

Paginated browse of the user store, optionally filtered by user id. The `COUSR00` screen
renders **10 rows per page** (`SEL0001`–`SEL0010`, `USRID01`–`USRID10`, `FNAME01`–`FNAME10`,
`LNAME01`–`LNAME10`, `UTYPE01`–`UTYPE10`).

**Query parameters:**

| Parameter | BMS field | Type | Length | Required | Notes |
|---|---|---|---|---|---|
| `userId` | `USRIDIN` (`UNPROT`) | `String` | 8 | no | starting/filter user id |
| `page` | (PF7/PF8) | `Integer` | — | no | **1-based** page index; default `1`. The page size is **fixed at 10 rows** (`UserSecurityDto.ROWS_PER_PAGE`, the `COUSR00` screen capacity); there is **no `size` parameter** (BMS-parity fixed-row browse). A value below one is normalized to one by the service. |

**Response — `UserSecurityDto`** (`200 OK`): the list-mode projection of the shared `UserSecurityDto`. The browse page is returned as the `users[]` array (at most **10** rows = `UserSecurityDto.ROWS_PER_PAGE`), and `pageNumber` echoes the current 1-based page. This preserves the `COUSR00` fixed-row screen contract exactly; it is **not** a generic offset/size paged envelope, so there is no `totalElements`/`hasNext`/`hasPrevious` metadata (navigation is the client requesting `page-1` for PF7 / `page+1` for PF8). The password is never present on list rows.

| Field | BMS field | Type | Length | Notes |
|---|---|---|---|---|
| `users[].selectionFlag` | `SEL00nn` | `String` | 1 | row selection flag (`U`pdate / `D`elete / blank) |
| `users[].userId` | `USRIDnn` | `String` | 8 | |
| `users[].firstName` | `FNAMEnn` | `String` | 20 | |
| `users[].lastName` | `LNAMEnn` | `String` | 20 | |
| `users[].userType` | `UTYPEnn` → `UserType` | enum | 1 | `ADMIN` (`'A'`) / `USER` (`'U'`) |
| `pageNumber` | `PAGENUM` | `String` | — | current **1-based** page |

#### `GET /api/admin/users/{id}` — get user by id

Single keyed read of one user — the canonical `UserUpdateService.loadUser` used by the update/delete
load step (`COUSR02C`/`COUSR03C` `PROCESS-ENTER-KEY`). The `{id}` path variable is the user id; the
credential is never returned.

**Path parameter:** `id` ← `USRIDIN` (`String`, 8) — the user id to read. A blank `id` raises the
verbatim COBOL edit **"User ID can NOT be empty..."** (`400`).

**Response — `UserSecurityDto`** (`200 OK`): the single-user projection (`userId`, `firstName`,
`lastName`, `userType`), populated field-for-field from the keyed read (`MOVE SEC-USR-* TO` map
fields). The `password` field is **write-only** (`@JsonProperty(access = WRITE_ONLY)`) and is **never**
present in the response (a documented C-003 consequence — the stored value is a one-way BCrypt hash).

| Field | BMS field | Type | Length | Notes |
|---|---|---|---|---|
| `userId` | `USRIDIN` → `SEC-USR-ID` | `String` | 8 | the keyed user id |
| `firstName` | `FNAME` → `SEC-USR-FNAME` | `String` | 20 | |
| `lastName` | `LNAME` → `SEC-USR-LNAME` | `String` | 20 | |
| `userType` | `USRTYPE` → `UserType` | enum | 1 | `ADMIN` (`'A'`) / `USER` (`'U'`) |

**Status codes:** `200 OK`; `400 Bad Request` (blank `id`); `403 Forbidden` (non-admin); `404 Not Found` (no such user — "User ID NOT found...", `FILE STATUS 23`).

#### `POST /api/admin/users` — add user

Creates a user (`COUSR01`). All fields are unprotected input; the password is `DRK`
(non-displaying) and is hashed with **BCrypt** on create.

**Request — `UserSecurityDto`, validated `@Validated(UserSecurityDto.OnAdd.class)`** (fields
`UNPROT` on `COUSR01`):

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `userId` | `USERID` | `String` | 8 | yes | `@NotBlank(OnAdd)`, `@Size(max=8)` |
| `firstName` | `FNAME` | `String` | 20 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=20)` |
| `lastName` | `LNAME` | `String` | 20 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=20)` |
| `password` | `PASSWD` (`DRK`) | `String` | 8 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=8)` — **write-only**; BCrypt-hashed; never echoed |
| `userType` | `USRTYPE` → `UserType` | enum | 1 | yes | `@NotNull(OnAdd,OnUpdate)`; JSON value `ADMIN` or `USER` (the `UserType` enum constant names). The byte-exact COBOL codes `'A'`/`'U'` are preserved internally via `UserType.getCode()`; an unrecognized value is rejected at JSON binding (`400`). |

**Response — `UserSecurityDto`** (`201 Created`): the created user **without** the password.
The created `userId` is carried in the **response body**; **no `Location` header is emitted**
(the legacy `COUSR01` flow had no such concept and none is added — Minimal Change Clause,
§0.7.1).

**Status codes:** `201 Created`; `400 Bad Request` (validation); `403 Forbidden` (non-admin);
`409 Conflict` (user id already exists — `FILE STATUS 22`).

#### `PUT /api/admin/users/{id}` — update user

Updates a user (`COUSR02`). The user id is the key; first name, last name, password, and
type are editable.

**Path parameter:** `id` ← `USRIDIN` (`String`, 8, `@Size(max=8)`).

**Request — `UserSecurityDto`, validated `@Validated(UserSecurityDto.OnUpdate.class)`** (fields
`UNPROT` on `COUSR02`):

| Field | BMS field | Type | Length | Required | Validation |
|---|---|---|---|---|---|
| `firstName` | `FNAME` | `String` | 20 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=20)` |
| `lastName` | `LNAME` | `String` | 20 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=20)` |
| `password` | `PASSWD` (`DRK`) | `String` | 8 | yes | `@NotBlank(OnAdd,OnUpdate)`, `@Size(max=8)` — **write-only**; BCrypt-hashed; never echoed. Required on update: `COUSR02C` rejects an empty password (`'Password can NOT be empty...'`). |
| `userType` | `USRTYPE` → `UserType` | enum | 1 | yes | `@NotNull(OnAdd,OnUpdate)`; JSON value `ADMIN` or `USER` (the `UserType` enum constant names); byte-exact `'A'`/`'U'` preserved via `UserType.getCode()`. |

**Response — `UserSecurityDto`** (`200 OK`): the refreshed user without the password.

**Status codes:** `200 OK`; `400 Bad Request`; `403 Forbidden`; `404 Not Found`.

#### `DELETE /api/admin/users/{id}` — delete user

Deletes a user (`COUSR03`). The screen displays the user's details (read-only) before the
delete is confirmed; the REST contract takes only the key.

**Path parameter:** `id` ← `USRIDIN` (`String`, 8, `@Size(max=8)`).

**Response — `UserSecurityDto`** (`200 OK`): a confirmation body echoing the deleted user's
`userId`, `firstName`, `lastName`, and `userType` (never the password) — the REST analogue of
the `COUSR03C` &ldquo;User &lt;id&gt; ... has been deleted&rdquo; confirmation screen. The
endpoint deliberately returns **`200 OK` with a body**, not `204 No Content`, to preserve that
COBOL confirmation-screen parity.

**Status codes:** `200 OK`; `403 Forbidden`; `404 Not Found`.

**Notes — technology substitution.** `USRSEC` plaintext passwords are upgraded to BCrypt
hashes (`SEC-USR-PWD PIC X(08)` → hashed column). The `PASSWD` field is `DRK` on every user
screen; the REST contract preserves that as a write-only field that is accepted on input but
never serialized on any response. The COBOL `SEC-USR-TYPE` value (`'A'`/`'U'`) maps to the
`UserType` enum (`ADMIN`/`USER`).

---

## 6. Cross-cutting contract notes

### 6.1 Field-name and type preservation

REST DTO field names are derived directly from the BMS symbolic-map field names / COBOL data
names, and the documented length and type match the originating `PIC` clause exactly. The
camelCase JSON name is a deterministic transliteration of the COBOL/BMS name (for example
`ACCTSID` → `accountId`, `ACSFNAM` → `firstName` where the screen label disambiguates, `TRNAMT`
→ `amount`); the mapping is recorded per field in the tables above so it can be audited
against the frozen sources at commit `27d6c6f`. Where the screen split a logical value across
component fields (dates as year/month/day, SSN as three parts, phone as area/prefix/line), the
DTO **consolidates** those components into a single canonical value (a `LocalDate` for each
date, one `ssn` string, one `phoneNumber1`/`phoneNumber2` string), and the service layer
composes/decomposes the persisted component layout internally — no data is lost. Apart from
this transliteration and consolidation, no field is added, removed, renamed, or re-typed
relative to the screen contract.

### 6.2 Decimal precision

Every monetary and decimal field is typed as **`java.math.BigDecimal`** with the exact scale
of its backing COBOL `PIC S9(n)V99` clause (scale 2 throughout this application: account
balances and limits from `S9(10)V99`, transaction amounts from `S9(09)V99`). `float` and
`double` are never used for any value originating from a COBOL `PIC` clause with decimal
positions. Numeric comparisons in the service layer use `BigDecimal.compareTo`, never
`equals` (which is scale-sensitive).

### 6.3 Pagination

List endpoints (`GET /api/cards`, `GET /api/transactions`, `GET /api/admin/users`) use
**1-based, fixed-row** pagination via a single `page` query parameter (default `1`), mirroring
the COBOL pseudo-conversational browse driven by PF7 (page up) and PF8 (page down). The page
size is **fixed at each screen's row capacity** (7 for cards, 10 for transactions and users),
encoded as the screen DTO's `ROWS_PER_PAGE` constant; there is **no `size` query parameter**.
Each response is the screen's shared DTO carrying the page rows as a fixed-capacity array
(`cards[]` / `transactions[]` / `users[]`) plus a `pageNumber` field echoing the current
1-based page — it is **not** a generic offset/size paged envelope and carries no
`totalElements` or `hasNext`/`hasPrevious` metadata. The client reproduces PF7/PF8 navigation
by requesting `page-1` / `page+1`; the service normalizes out-of-range page numbers. This
preserves the fixed-row BMS screen contract exactly (`COCRDLI`, `COTRN00`, `COUSR00`). A
cursor/keyset-based pagination scheme (closer to the original VSAM browse semantics for very
large result sets) is a recognized **future enhancement and is explicitly out of scope** for
this migration.

### 6.4 Authentication and authorization

- **Stateless, token-based.** After `POST /api/auth/signin`, the client sends the issued JWT
  bearer token in the `Authorization: Bearer <token>` header on every subsequent request.
  There is no server-side conversational session — the token carries the user identity and
  type, replacing the pseudo-conversational `COCOM01Y` COMMAREA.
- **Role gating.** All `/api/admin/**` endpoints (user administration) require an `ADMIN`
  token; non-admin callers receive `403 Forbidden`. This preserves the legacy rule that the
  user-administration screens were reachable only from the admin menu (`COADM01`), which the
  sign-on flow routed to exclusively for `'A'`-type users. All other business endpoints
  require any authenticated user.
- **`POST /api/auth/signin`** is the only endpoint that does not require a token.

### 6.5 Ports, paths, and operational endpoints

| Concern | Value |
|---|---|
| Application base URL | `http://localhost:8080` |
| API path prefix | `/api` (all endpoints above include it) |
| Content type | `application/json` (request and response bodies) |
| Health probe | `GET /actuator/health` |
| Metrics (Prometheus) | `GET /actuator/prometheus` |
| Correlation id | every request/response and error envelope carries a `correlationId` injected by `CorrelationIdFilter` |

These values are consistent with the project's `docker-compose.yml`, `prometheus.yml`
(scrapes `app:8080` at `/actuator/prometheus`), and the `application*.yml` profiles.

---

*This contract is derived from the AWS CardDemo COBOL/BMS sources at commit `27d6c6f`. No
COBOL or BMS source is reproduced in this repository; field definitions are translated from
the 17 BMS mapsets and their 17 symbolic-map copybooks under `app/bms/` and `app/cpy-bms/`.
It is the contract that `com.cardemo.controller.*` and `com.cardemo.model.dto.*` must
satisfy and is verified by the API/interface contract verification gate.*
