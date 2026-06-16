# CardDemo — API, Messaging & Storage Contracts

**External interface contracts for the CardDemo Java migration (REST · SQS · S3).**

| | |
| :-- | :-- |
| **Document** | `docs/api-contracts.md` |
| **Application** | `carddemo-java` (Java 25 LTS + Spring Boot 3.x) |
| **Source baseline** | AWS CardDemo COBOL/CICS, commit SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`) |
| **Scope** | 8 REST controllers / 17 online screens, 1 SQS FIFO queue, 3 S3 buckets |
| **Cloud target** | AWS via LocalStack (`http://localhost:4566`) — zero live AWS dependency |

This document is the authoritative description of every **external interface contract** exposed
by the migrated application. It is derived from the original mainframe interface assets by
reference only — **no COBOL source is copied** into the Java project; traceability is preserved
through the source commit SHA `27d6c6f` and the per-endpoint mapping tables below.

The contracts here are governed by the migration's invariants:

- **Behavioral parity & contract preservation** — field layouts, record lengths, decimal
  precision, and message/file schemas are preserved exactly. Fixed-point COBOL `PIC` decimals
  map to `java.math.BigDecimal` with identical scale; no floating-point substitution occurs.
- **No feature expansion** — exactly the endpoints, queue, and buckets enumerated below are
  exposed. There are no extra routes, verbs, entities, or business rules beyond the closed
  feature set migrated from the COBOL baseline.
- **No hardcoded credentials** — the JWT signing secret and all AWS settings resolve from
  environment variables / configuration; nothing sensitive is embedded in code or this document.
- **AWS only** — Google Cloud Platform (GCS, Pub/Sub, Firestore) is out of scope and absent from
  every contract here.

> **Note on API documentation tooling.** This file is hand-authored Markdown. Generated
> OpenAPI / Swagger specifications are intentionally **not** part of this migration (they are a
> listed out-of-scope future enhancement); this document is the contract of record.

---

## Table of Contents

1. [Common Conventions](#1-common-conventions)
   - [1.1 Base path & media type](#11-base-path--media-type)
   - [1.2 Authentication & authorization](#12-authentication--authorization)
   - [1.3 BMS symbolic map → DTO derivation](#13-bms-symbolic-map--dto-derivation)
   - [1.4 Standard error envelope](#14-standard-error-envelope)
   - [1.5 Pagination](#15-pagination)
   - [1.6 Decimal precision](#16-decimal-precision)
   - [1.7 FILE STATUS → HTTP status mapping](#17-file-status--http-status-mapping)
   - [1.8 Optimistic concurrency](#18-optimistic-concurrency)
   - [1.9 Batch reject reason codes](#19-batch-reject-reason-codes)
2. [REST API Contracts](#2-rest-api-contracts)
   - [2.1 AuthController](#21-authcontroller--cc00--cosgn00)
   - [2.2 MenuController](#22-menucontroller--cm00--ca00--comen01--coadm01)
   - [2.3 AccountController](#23-accountcontroller--cavw--caup--coactvw--coactup)
   - [2.4 CardController](#24-cardcontroller--ccli--ccdl--ccup--cocrdli--cocrdsl--cocrdup)
   - [2.5 TransactionController](#25-transactioncontroller--ct00--ct01--ct02--cotrn00--cotrn01--cotrn02)
   - [2.6 BillingController](#26-billingcontroller--cb00--cobil00)
   - [2.7 ReportController](#27-reportcontroller--cr00--corpt00)
   - [2.8 UserAdminController](#28-useradmincontroller--cu00cu03--cousr00cousr03)
   - [2.9 Session state (CommArea) note](#29-session-state-commarea-note)
3. [SQS Contract — Report Submission Queue](#3-sqs-contract--report-submission-queue)
4. [S3 Contract — Batch Staging, Statements & Rejects](#4-s3-contract--batch-staging-statements--rejects)
5. [Traceability & References](#5-traceability--references)

---

## 1. Common Conventions

### 1.1 Base path & media type

- All REST endpoints are served under the context path **`/api`** on port **`8080`**.
- Request and response bodies use **`application/json`** (`Content-Type` and `Accept`:
  `application/json`). Character encoding is UTF-8.
- Field names use **camelCase**. They are derived from the COBOL BMS symbolic-map field names
  (see [§1.3](#13-bms-symbolic-map--dto-derivation)); the original COBOL field name is shown in
  every DTO table for traceability.
- Dates use the ISO-8601 form `yyyy-MM-dd` in JSON; the COBOL screens carried split or
  `mm/dd/yy` text fields, which are normalized at the contract boundary while preserving the
  underlying stored value.

### 1.2 Authentication & authorization

- Authentication is **stateless** and based on **JWT bearer tokens** (Spring Security resource
  server). There is no server-side conversational session — the CICS `CARDDEMO-COMMAREA` is
  replaced by JWT claims plus request/response DTOs (see [§2.9](#29-session-state-commarea-note)).
- A client obtains a token from `POST /api/auth/signin` and presents it on every subsequent
  request via the header:

  ```http
  Authorization: Bearer <jwt>
  ```

- The signing secret resolves from an environment variable / configuration property
  (e.g. `CARDDEMO_JWT_SECRET`); it is **never** hardcoded.
- Roles derive from the COBOL `CDEMO-USER-TYPE` flag (copybook `CSUSR01Y` / `COCOM01Y`):
  `'A'` → **`ADMIN`**, `'U'` → **`USER`**. Admin-only endpoints (`/api/admin/**`) require the
  `ADMIN` role; all other business endpoints require an authenticated `USER` or `ADMIN`.
- Passwords are verified with **BCrypt**. The COBOL plaintext `SEC-USR-PWD PIC X(08)` is
  upgraded to a BCrypt hash while preserving the sign-on flow; the password is accepted on input
  but is **never** returned in any response.

| Auth requirement | Meaning |
| :-- | :-- |
| **None** | Public endpoint (`POST /api/auth/signin` only). |
| **USER** | Valid JWT with role `USER` or `ADMIN`. |
| **ADMIN** | Valid JWT with role `ADMIN`. |

### 1.3 BMS symbolic map → DTO derivation

Each online screen was defined by a BMS mapset (`app/bms/<MAP>.bms`) plus a generated symbolic
map copybook (`app/cpy-bms/<MAP>.CPY`). Every symbolic map contains two `01`-level views over the
same terminal buffer:

- `01 <map>AI` — the **input** (receive) layout; data fields are suffixed **`I`**.
- `01 <map>AO REDEFINES <map>AI` — the **output** (send) layout; data fields are suffixed **`O`**.

For every screen field BMS also generates screen-control helper subfields that are **not**
part of the API contract and are dropped during migration:

| Suffix | COBOL type | Purpose | Migrated as |
| :-- | :-- | :-- | :-- |
| `…L` | `COMP PIC S9(4)` | Field length | dropped (HTTP handles length) |
| `…F` / `…A` | `PIC X` | Input attribute / flag | dropped |
| `…C` `…P` `…H` `…V` | `PIC X` | Output color/protect/highlight/validation | dropped |
| **`…I`** | `PIC X(n)` / `9(n)` | **Input data value** | **request DTO field** |
| **`…O`** | `PIC X(n)` | **Output data value** | **response DTO field** |

Only the **`…I`** (request) and **`…O`** (response) data fields are contract-relevant and appear
in the DTO tables below. Common header chrome present on every map — `TRNNAME` (X4), `TITLE01`
(X40), `CURDATE` (X8), `PGMNAME` (X8), `TITLE02` (X40), `CURTIME` (X8/X9) — is screen decoration,
not business data, and is omitted from request/response DTOs. The error line `ERRMSG`
(X78 / X80) maps to the standard envelope `errorMessage`, and `INFOMSG` maps to `infoMessage`.
PF-key legend fields (`FKEYS…`) and AID handling are replaced by REST routing.

### 1.4 Standard error envelope

All non-2xx responses share one envelope (errors never leak stack traces or credentials):

```json
{
  "timestamp": "2026-06-15T12:34:56Z",
  "status": 404,
  "error": "Not Found",
  "code": "RECORD_NOT_FOUND",
  "message": "Account 00000000001 was not found.",
  "path": "/api/accounts/00000000001",
  "correlationId": "b3f1c2a4-5d6e-4f70-8a91-0c2d4e6f8a1b"
}
```

- `code` is a stable, machine-readable symbol mapped from the originating COBOL FILE STATUS or
  validation condition (see [§1.7](#17-file-status--http-status-mapping)).
- `correlationId` echoes the per-request correlation ID (also emitted in structured logs and
  traces) for end-to-end diagnosis.
- Field validation failures (`400`) include a `fieldErrors` array of `{ field, message }`.

### 1.5 Pagination

Browse (list) endpoints are paginated. Page size **defaults preserve the COBOL screen geometry**:

| Endpoint | COBOL rows/screen | Default `size` |
| :-- | :-- | :-- |
| `GET /api/cards` (← `COCRDLI`) | 7 | **7** |
| `GET /api/transactions` (← `COTRN00`) | 10 | **10** |
| `GET /api/admin/users` (← `COUSR00`) | 10 | **10** |

Query parameters: `page` (0-based, default `0`) and `size` (default as above). Responses use a
paged envelope in which `content` is the array of page items (the row DTO is endpoint-specific —
e.g. `CardSummary` for `GET /api/cards`, shown here empty for brevity):

```json
{
  "content": [],
  "page": 0,
  "size": 7,
  "totalElements": 23,
  "totalPages": 4,
  "hasNext": true
}
```

### 1.6 Decimal precision

Every monetary / rate field that originated from a COBOL `PIC` clause with decimal positions is
represented as **`BigDecimal`** with the **exact scale** of the source clause — `float`/`double`
are prohibited for these fields.

| COBOL clause | Example field | JSON type | Scale |
| :-- | :-- | :-- | :-- |
| `PIC S9(10)V99` | account balances, transaction amount | `BigDecimal` (string in JSON) | **2** |
| `PIC S9(4)V99` | (rates, where applicable) | `BigDecimal` | 2 |

BigDecimal values are serialized as JSON strings to avoid IEEE-754 rounding (e.g. `"1234.56"`).
Numeric comparisons in the application use `compareTo()`, never scale-sensitive `equals()`.

### 1.7 FILE STATUS → HTTP status mapping

The COBOL programs signal outcomes through two-character **FILE STATUS** codes (e.g. the six
`FILE STATUS` clauses in `CBTRN02C`). These map to a custom exception hierarchy and, for the
online REST surface, to HTTP status codes:

| FILE STATUS | Condition | Java exception | HTTP | `code` |
| :-- | :-- | :-- | :-- | :-- |
| `00` | Successful operation | — | `200` / `201` | — |
| `23` | Record not found / no record | `RecordNotFoundException` | `404` | `RECORD_NOT_FOUND` |
| `22` | Duplicate key on write | `DuplicateRecordException` | `409` | `DUPLICATE_RECORD` |
| `35` `37` `39` | File unavailable / open / attribute mismatch | `FileAccessException` | `503` | `STORAGE_UNAVAILABLE` |
| `92` / other `9x` | Logic / unexpected I/O error | `CardDemoException` | `500` | `INTERNAL_ERROR` |
| (validation) | Bean validation (`@Valid`) failure | `MethodArgumentNotValidException` | `400` | `VALIDATION_FAILED` |
| (concurrency) | Optimistic-lock conflict (`@Version`) | `OptimisticLockException` | `409` | `CONCURRENT_MODIFICATION` |
| (authz) | Missing / insufficient role | — | `401` / `403` | `UNAUTHORIZED` / `FORBIDDEN` |

### 1.8 Optimistic concurrency

The COBOL account- and card-update programs (`COACTUPC`, `COCRDUPC`) perform a before/after
record-image comparison under `READ … UPDATE`. This maps to JPA **`@Version`** optimistic
locking. Update requests (`PUT`) echo the entity `version`; if the stored version has advanced,
the server responds **`409 Conflict`** (`code: CONCURRENT_MODIFICATION`) and the client must
re-fetch and retry. The dual `ACCTDAT` + `CUSTDAT` update in `COACTUPC` — the sole
`SYNCPOINT ROLLBACK` in the system — runs inside a single `@Transactional` boundary that rolls
back atomically on any exception.

### 1.9 Batch reject reason codes

Reject reason codes are a **batch-only** contract (daily posting program `CBTRN02C`) and are
**not** HTTP status codes. When a daily transaction fails validation, the original 350-byte
record is written to a rejection record carrying a 4-digit reason code (`PIC 9(04)`) and a
76-character description (`PIC X(76)`). Reason codes occupy the band **100–109**:

| Code | Description (preserved verbatim) | Validation stage |
| :-- | :-- | :-- |
| `100` | `INVALID CARD NUMBER FOUND` | Cross-reference lookup (card → account) |
| `101` | `ACCOUNT RECORD NOT FOUND` | Account master read |
| `102` | `OVERLIMIT TRANSACTION` | Credit-limit check |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Account expiration check |
| `104`–`108` | *(reserved within the 100–109 band)* | — |
| `109` | `ACCOUNT RECORD NOT FOUND` | Account re-read on balance update |

These codes are surfaced in S3 rejection objects (see
[§4](#4-s3-contract--batch-staging-statements--rejects)), never in REST responses.

---

## 2. REST API Contracts

The table below is the binding endpoint inventory. Every row maps a CICS transaction and BMS
screen to exactly one HTTP method + path. There are **8 controllers** covering **17 screens**;
no additional routes are introduced.

| Controller | Tran | BMS map / program | Method & path | Auth |
| :-- | :-- | :-- | :-- | :-- |
| AuthController | `CC00` | `COSGN00` / `COSGN00C` | `POST /api/auth/signin` | None |
| MenuController | `CM00` | `COMEN01` / `COMEN01C` | `GET /api/menu/main` | USER |
| MenuController | `CA00` | `COADM01` / `COADM01C` | `GET /api/menu/admin` | ADMIN |
| AccountController | `CAVW` | `COACTVW` / `COACTVWC` | `GET /api/accounts/{id}` | USER |
| AccountController | `CAUP` | `COACTUP` / `COACTUPC` | `PUT /api/accounts/{id}` | USER |
| CardController | `CCLI` | `COCRDLI` / `COCRDLIC` | `GET /api/cards` | USER |
| CardController | `CCDL` | `COCRDSL` / `COCRDSLC` | `GET /api/cards/{cardNum}` | USER |
| CardController | `CCUP` | `COCRDUP` / `COCRDUPC` | `PUT /api/cards/{cardNum}` | USER |
| TransactionController | `CT00` | `COTRN00` / `COTRN00C` | `GET /api/transactions` | USER |
| TransactionController | `CT01` | `COTRN01` / `COTRN01C` | `GET /api/transactions/{id}` | USER |
| TransactionController | `CT02` | `COTRN02` / `COTRN02C` | `POST /api/transactions` | USER |
| BillingController | `CB00` | `COBIL00` / `COBIL00C` | `POST /api/billing/pay` | USER |
| ReportController | `CR00` | `CORPT00` / `CORPT00C` | `POST /api/reports/submit` | USER |
| UserAdminController | `CU00` | `COUSR00` / `COUSR00C` | `GET /api/admin/users` | ADMIN |
| UserAdminController | `CU01` | `COUSR01` / `COUSR01C` | `POST /api/admin/users` | ADMIN |
| UserAdminController | `CU02` | `COUSR02` / `COUSR02C` | `PUT /api/admin/users/{id}` | ADMIN |
| UserAdminController | `CU03` | `COUSR03` / `COUSR03C` | `DELETE /api/admin/users/{id}` | ADMIN |

---

### 2.1 AuthController — `CC00` / `COSGN00`

Replaces the CICS sign-on screen. Verifies the user against the security store with BCrypt and
issues a JWT whose claims carry the user identity and role.

#### `POST /api/auth/signin`

- **Auth:** None (public).
- **Source:** `COSGN00.bms` / `COSGN00.CPY` / program `COSGN00C`.

**Request — `SignOnRequest`**

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `userId` | `USERIDI` | string | 8 | yes | User ID; trimmed, case-insensitive match. |
| `password` | `PASSWDI` | string | 8 | yes | Verified with BCrypt; never echoed. |

**Response `200` — `SignOnResponse`**

| JSON field | Origin | Type | Notes |
| :-- | :-- | :-- | :-- |
| `token` | issued JWT | string | Bearer token for subsequent requests. |
| `tokenType` | constant | string | `"Bearer"`. |
| `userId` | `CDEMO-USER-ID` | string(8) | Authenticated user. |
| `userType` | `CDEMO-USER-TYPE` | string(1) | `A` (admin) or `U` (user). |
| `role` | derived | string | `ADMIN` or `USER`. |
| `expiresIn` | token TTL | integer | Seconds until expiry. |

**Errors:** `400` validation (missing user/password); `401` `UNAUTHORIZED` (unknown user or bad
password — message generalized via `ERRMSG`).

---

### 2.2 MenuController — `CM00` + `CA00` / `COMEN01` + `COADM01`

Returns the routing menus. The COBOL screens carry 12 fixed option lines (`OPTN001I`–`OPTN012I`,
`PIC X(40)`) and a 2-character selection field (`OPTIONI`); the main menu populates **10**
options and the admin menu **4**. Selection routing (`OPTION` → next transaction) is replaced by
the client navigating to the corresponding REST endpoint.

#### `GET /api/menu/main`

- **Auth:** USER. **Source:** `COMEN01.bms` / `COMEN01.CPY` / `COMEN01C`.

#### `GET /api/menu/admin`

- **Auth:** ADMIN. **Source:** `COADM01.bms` / `COADM01.CPY` / `COADM01C`.

**Response `200` — `MenuResponse`**

| JSON field | COBOL field | Type | Notes |
| :-- | :-- | :-- | :-- |
| `menuType` | program | string | `MAIN` (10 options) or `ADMIN` (4 options). |
| `options` | `OPTN001O`–`OPTN012O` | array of `MenuOption` | Active options only. |
| `options[].optionNumber` | line index | string(2) | Two-digit option key (`OPTIONI` selector). |
| `options[].label` | `OPTNnnnO` | string(40) | Display text. |
| `options[].targetTransaction` | menu table | string(4) | Originating CICS tran (e.g. `CAVW`). |
| `options[].targetPath` | derived | string | REST path the option routes to. |

**Errors:** `401` / `403` per role.

---

### 2.3 AccountController — `CAVW` + `CAUP` / `COACTVW` + `COACTUP`

The account screens read across `ACCTDAT` + `CUSTDAT` joined by the `CXACAIX` card
cross-reference. The five balance/limit fields are `PIC S9(10)V99` → **`BigDecimal` scale 2**.

#### `GET /api/accounts/{id}` — Account view (`COACTVW`)

- **Auth:** USER. **Path var:** `id` = account ID, `PIC 9(11)` (`ACCTSIDI`, 11 digits).

**Response `200` — `AccountViewResponse`** (selected fields; account + customer composite)

| JSON field | COBOL field | Type | Length / scale | Notes |
| :-- | :-- | :-- | :-- | :-- |
| `accountId` | `ACCTSIDI` | string | 11 | Zero-padded numeric key. |
| `accountStatus` | `ACSTTUSI` | string | 1 | `Y`/`N` active flag. |
| `openDate` | `ADTOPENI` | string (date) | 10 | ISO `yyyy-MM-dd`. |
| `expirationDate` | `AEXPDTI` | string (date) | 10 | |
| `reissueDate` | `AREISDTI` | string (date) | 10 | |
| `creditLimit` | `ACRDLIMI` | BigDecimal | scale 2 | `PIC S9(10)V99`. |
| `cashCreditLimit` | `ACSHLIMI` | BigDecimal | scale 2 | |
| `currentBalance` | `ACURBALI` | BigDecimal | scale 2 | |
| `currentCycleCredit` | `ACRCYCRI` | BigDecimal | scale 2 | |
| `currentCycleDebit` | `ACRCYDBI` | BigDecimal | scale 2 | |
| `accountGroupId` | `AADDGRPI` | string | 10 | Disclosure / add-on group. |
| `customerId` | `ACSTNUMI` | string | 9 | `PIC 9(09)`. |
| `customerSsn` | `ACSTSSNI` | string | 12 | Returned masked. |
| `dateOfBirth` | `ACSTDOBI` | string (date) | 10 | |
| `ficoScore` | `ACSTFCOI` | string | 3 | |
| `firstName` / `middleName` / `lastName` | `ACSFNAMI` / `ACSMNAMI` / `ACSLNAMI` | string | 25 each | |
| `addressLine1` / `addressLine2` | `ACSADL1I` / `ACSADL2I` | string | 50 each | |
| `city` | `ACSCITYI` | string | 50 | |
| `state` | `ACSSTTEI` | string | 2 | |
| `zipCode` | `ACSZIPCI` | string | 5 | |
| `country` | `ACSCTRYI` | string | 3 | |
| `phone1` / `phone2` | `ACSPHN1I` / `ACSPHN2I` | string | 13 each | |
| `governmentId` | `ACSGOVTI` | string | 20 | |
| `eftAccountId` | `ACSEFTCI` | string | 10 | |
| `primaryCardholderFlag` | `ACSPFLGI` | string | 1 | |

**Errors:** `404` `RECORD_NOT_FOUND` (account or cross-reference missing); `401`/`403`.

#### `PUT /api/accounts/{id}` — Account update (`COACTUP`)

- **Auth:** USER. **Semantics:** runs in a single **`@Transactional`** boundary that updates
  `ACCTDAT` + `CUSTDAT` atomically (the sole `SYNCPOINT ROLLBACK`); **`@Version`** optimistic
  locking applies (see [§1.8](#18-optimistic-concurrency)).

**Request — `AccountUpdateRequest`** mirrors the view fields above as **inputs** and additionally
exposes the segmented entry fields that the COBOL update screen splits out:

| JSON field | COBOL field(s) | Type | Notes |
| :-- | :-- | :-- | :-- |
| `version` | (record image) | integer | Optimistic-lock token; required. |
| `accountStatus` | `ACSTTUSI` | string(1) | |
| `openDate` | `OPNYEARI`+`OPNMONI`+`OPNDAYI` | string (date) | Split `YYYY`/`MM`/`DD` → ISO date. |
| `expirationDate` | `EXPYEARI`+`EXPMONI`+`EXPDAYI` | string (date) | |
| `reissueDate` | `RISYEARI`+`RISMONI`+`RISDAYI` | string (date) | |
| `creditLimit` | `ACRDLIMI` | BigDecimal scale 2 | |
| `cashCreditLimit` | `ACSHLIMI` | BigDecimal scale 2 | |
| `currentBalance` | `ACURBALI` | BigDecimal scale 2 | |
| `currentCycleCredit` | `ACRCYCRI` | BigDecimal scale 2 | |
| `currentCycleDebit` | `ACRCYDBI` | BigDecimal scale 2 | |
| `accountGroupId` | `AADDGRPI` | string(10) | |
| `customerId` | `ACSTNUMI` | string(9) | |
| `ssn` | `ACTSSN1I`+`ACTSSN2I`+`ACTSSN3I` | string | Split `3`/`2`/`4` → 9-digit SSN. |
| `dateOfBirth` | `DOBYEARI`+`DOBMONI`+`DOBDAYI` | string (date) | |
| `ficoScore` | `ACSTFCOI` | string(3) | |
| `firstName`/`middleName`/`lastName` | `ACSFNAMI`/`ACSMNAMI`/`ACSLNAMI` | string(25) | |
| `addressLine1`/`addressLine2` | `ACSADL1I`/`ACSADL2I` | string(50) | |
| `city`/`state`/`zipCode`/`country` | `ACSCITYI`/`ACSSTTEI`/`ACSZIPCI`/`ACSCTRYI` | string | 50/2/5/3 |
| `phone1` | `ACSPH1AI`+`ACSPH1BI`+`ACSPH1CI` | string | Split area/prefix/line. |
| `phone2` | `ACSPH2AI`+`ACSPH2BI`+`ACSPH2CI` | string | |
| `governmentId` | `ACSGOVTI` | string(20) | |
| `eftAccountId` | `ACSEFTCI` | string(10) | |
| `primaryCardholderFlag` | `ACSPFLGI` | string(1) | |

**Response `200`** — updated `AccountViewResponse` (incl. incremented `version`).

**Errors:** `400` validation; `404` `RECORD_NOT_FOUND`; `409` `CONCURRENT_MODIFICATION`
(stale `version`); `401`/`403`.

---

### 2.4 CardController — `CCLI` + `CCDL` + `CCUP` / `COCRDLI` + `COCRDSL` + `COCRDUP`

#### `GET /api/cards` — Card list (`COCRDLI`, 7 rows/page)

- **Auth:** USER. **Query:** `accountId` (`ACCTSIDI`, opt. filter), `cardNumber`
  (`CARDSIDI`, opt. filter), `page`, `size` (default **7** — preserves the screen's 7 rows).

**Response `200` — paged `CardSummary`** (one entry per `COCRDLI` row group)

| JSON field | COBOL field | Type | Length | Notes |
| :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACCTNOnI` | string | 11 | Row account number. |
| `cardNumber` | `CRDNUMnI` | string | 16 | Row card number. |
| `cardStatus` | `CRDSTSnI` | string | 1 | Active flag. |

Pagination envelope per [§1.5](#15-pagination); `pageNumber` corresponds to `PAGENOI` (X3).

#### `GET /api/cards/{cardNum}` — Card detail (`COCRDSL`)

- **Auth:** USER. **Path var:** `cardNum` = `CARDSIDI`, `PIC X(16)`.

**Response `200` — `CardDetailResponse`**

| JSON field | COBOL field | Type | Length | Notes |
| :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACCTSIDI` | string | 11 | Owning account. |
| `cardNumber` | `CARDSIDI` | string | 16 | |
| `cardholderName` | `CRDNAMEI` | string | 50 | Embossed name. |
| `cardStatus` | `CRDSTCDI` | string | 1 | |
| `expiryMonth` | `EXPMONI` | string | 2 | |
| `expiryYear` | `EXPYEARI` | string | 4 | |

**Errors:** `404` `RECORD_NOT_FOUND`; `401`/`403`.

#### `PUT /api/cards/{cardNum}` — Card update (`COCRDUP`)

- **Auth:** USER. **Semantics:** **`@Version`** optimistic locking
  (see [§1.8](#18-optimistic-concurrency)).

**Request — `CardUpdateRequest`** (detail fields as inputs; `COCRDUP` adds expiry day)

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `version` | (record image) | integer | — | yes | Optimistic-lock token. |
| `cardholderName` | `CRDNAMEI` | string | 50 | yes | |
| `cardStatus` | `CRDSTCDI` | string | 1 | yes | |
| `expiryMonth` | `EXPMONI` | string | 2 | yes | |
| `expiryYear` | `EXPYEARI` | string | 4 | yes | |
| `expiryDay` | `EXPDAYI` | string | 2 | yes | Present only on the update screen. |

**Response `200`** — updated `CardDetailResponse`.

**Errors:** `400` validation; `404` `RECORD_NOT_FOUND`; `409` `CONCURRENT_MODIFICATION`;
`401`/`403`.

---

### 2.5 TransactionController — `CT00` + `CT01` + `CT02` / `COTRN00` + `COTRN01` + `COTRN02`

#### `GET /api/transactions` — Transaction list (`COTRN00`, 10 rows/page)

- **Auth:** USER. **Query:** `transactionId` (`TRNIDINI`, opt. start key), `page`,
  `size` (default **10** — preserves the screen's 10 rows).

**Response `200` — paged `TransactionSummary`** (one entry per `COTRN00` row)

| JSON field | COBOL field | Type | Length | Notes |
| :-- | :-- | :-- | :-: | :-- |
| `transactionId` | `TRNIDnnI` | string | 16 | |
| `transactionDate` | `TDATEnnI` | string (date) | 8 | |
| `description` | `TDESCnnI` | string | 26 | Row-truncated description. |
| `amount` | `TAMTnnnI` | BigDecimal | scale 2 | Display width 12. |

#### `GET /api/transactions/{id}` — Transaction detail (`COTRN01`)

- **Auth:** USER. **Path var:** `id` = `TRNIDINI`/`TRNIDI`, `PIC X(16)`.

**Response `200` — `TransactionDetailResponse`**

| JSON field | COBOL field | Type | Length / scale | Notes |
| :-- | :-- | :-- | :-- | :-- |
| `transactionId` | `TRNIDI` | string | 16 | |
| `cardNumber` | `CARDNUMI` | string | 16 | |
| `transactionType` | `TTYPCDI` | string | 2 | |
| `transactionCategory` | `TCATCDI` | string | 4 | |
| `transactionSource` | `TRNSRCI` | string | 10 | |
| `description` | `TDESCI` | string | 60 | Full description. |
| `amount` | `TRNAMTI` | BigDecimal | scale 2 | `TRAN-AMT`. |
| `originalDate` | `TORIGDTI` | string (date) | 10 | |
| `processedDate` | `TPROCDTI` | string (date) | 10 | |
| `merchantId` | `MIDI` | string | 9 | |
| `merchantName` | `MNAMEI` | string | 30 | |
| `merchantCity` | `MCITYI` | string | 25 | |
| `merchantZip` | `MZIPI` | string | 10 | |

**Errors:** `404` `RECORD_NOT_FOUND`; `401`/`403`.

#### `POST /api/transactions` — Transaction add (`COTRN02`)

- **Auth:** USER. **Semantics:** the transaction ID is **auto-generated** (browse-to-end +
  increment), reproducing `COTRN02C`; the COBOL confirmation field (`CONFIRMI`) maps to a request
  flag rather than a second screen round-trip.

**Request — `TransactionAddRequest`**

| JSON field | COBOL field | Type | Length / scale | Required | Notes |
| :-- | :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACTIDINI` | string | 11 | yes | Target account. |
| `cardNumber` | `CARDNINI` | string | 16 | yes | Target card. |
| `transactionType` | `TTYPCDI` | string | 2 | yes | |
| `transactionCategory` | `TCATCDI` | string | 4 | yes | |
| `transactionSource` | `TRNSRCI` | string | 10 | yes | |
| `description` | `TDESCI` | string | 60 | yes | |
| `amount` | `TRNAMTI` | BigDecimal | scale 2 | yes | `TRAN-AMT`, `PIC S9(10)V99`. |
| `originalDate` | `TORIGDTI` | string (date) | 10 | yes | |
| `processedDate` | `TPROCDTI` | string (date) | 10 | yes | |
| `merchantId` | `MIDI` | string | 9 | yes | |
| `merchantName` | `MNAMEI` | string | 30 | yes | |
| `merchantCity` | `MCITYI` | string | 25 | yes | |
| `merchantZip` | `MZIPI` | string | 10 | yes | |
| `confirm` | `CONFIRMI` | boolean | 1 | yes | `Y`/`N` confirmation. |

**Response `201` — `TransactionDetailResponse`** (incl. the generated `transactionId`).

**Errors:** `400` validation; `404` `RECORD_NOT_FOUND` (account/card); `401`/`403`.

---

### 2.6 BillingController — `CB00` / `COBIL00`

Posts a bill payment against an account balance.

#### `POST /api/billing/pay`

- **Auth:** USER. **Source:** `COBIL00.bms` / `COBIL00.CPY` / `COBIL00C`.

**Request — `BillPaymentRequest`**

| JSON field | COBOL field | Type | Length / scale | Required | Notes |
| :-- | :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACTIDINI` | string | 11 | yes | Account to pay. |
| `confirm` | `CONFIRMI` | boolean | 1 | yes | `Y`/`N` confirmation. |

> The current balance (`CURBALI`, display width 14 → `BigDecimal` scale 2) is read by the server
> from the account and returned in the response; it is **not** a client-supplied amount, matching
> the COBOL "pay full balance" semantics.

**Response `200` — `BillPaymentResponse`**

| JSON field | COBOL field | Type | Scale | Notes |
| :-- | :-- | :-- | :-- | :-- |
| `accountId` | `ACTIDIN` | string(11) | — | Entered/echoed account identifier; `null` on cancel. |
| `currentBalance` | `CURBAL` | BigDecimal | 2 | Balance redisplayed on the screen: the read balance on a preview, the post-payment balance on a confirmed payment; `null` on cancel. |
| `confirm` | `CONFIRM` | string(1) | — | Confirmation flag echoed back (`Y`/`N`/empty); `null` on cancel. |
| `errorMessage` | `ERRMSG` | string | — | Operator-message line: the confirmation prompt on a preview, the success message (including the generated transaction id) on a confirmed payment; `null` on cancel. |

> This response is the JSON projection of the `COBIL00` output map's four operator-facing fields,
> faithfully preserving the `COBIL00C` pseudo-conversational flow rather than a creation-style
> payload. The single endpoint returns one of three redisplays of the same map: **(1) cancel**
> (`confirm = N`) clears the screen and returns all-`null` fields; **(2) preview** (`confirm`
> empty) reads the account and returns the current balance with a confirmation prompt in
> `errorMessage`; **(3) payment** (`confirm = Y`) posts the balance-clearing transaction and
> returns the post-payment balance with a success message (carrying the generated transaction id)
> in `errorMessage`. The generated transaction id is therefore surfaced inside the `errorMessage`
> text exactly as the COBOL program renders it, not as a discrete field. See `DECISION_LOG.md`
> (**D-024**) for the contract-parity rationale.

**Errors:** `400` validation (`accountId` empty, invalid `confirm`, or non-positive balance);
`404` `RECORD_NOT_FOUND`; `401`/`403`.

---

### 2.7 ReportController — `CR00` / `CORPT00`

Submits a transaction-report request. In CICS, `CORPT00C` writes a job-submission record to the
transient-data queue (`EXEC CICS WRITEQ TD QUEUE('JOBS')`). In the migration this becomes a
**publish to an SQS FIFO queue** that triggers the batch report job
(see [§3](#3-sqs-contract--report-submission-queue)).

#### `POST /api/reports/submit`

- **Auth:** USER. **Source:** `CORPT00.bms` / `CORPT00.CPY` / `CORPT00C`.

**Request — `ReportSubmissionRequest`**

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `reportType` | `MONTHLYI` / `YEARLYI` / `CUSTOMI` | enum | 1 each | yes | One of `MONTHLY`, `YEARLY`, `CUSTOM` (the three mutually exclusive screen flags collapse to one enum). |
| `startDate` | `SDTYYYYI`+`SDTMMI`+`SDTDDI` | string (date) | 4/2/2 | conditional | Required for `CUSTOM`; split `YYYY`/`MM`/`DD` → ISO date. |
| `endDate` | `EDTYYYYI`+`EDTMMI`+`EDTDDI` | string (date) | 4/2/2 | conditional | Required for `CUSTOM`. |
| `confirm` | `CONFIRMI` | boolean | 1 | yes | `Y`/`N` confirmation. |

**Response `202 Accepted` — `ReportSubmissionResponse`**

| JSON field | Origin | Type | Notes |
| :-- | :-- | :-- | :-- |
| `accepted` | — | boolean | `true` when enqueued. |
| `correlationId` | request | string | Correlates to the SQS message and resulting report. |
| `queue` | constant | string | `carddemo-report-jobs.fifo`. |

**Errors:** `400` validation (missing custom date range); `401`/`403`; `503`
`STORAGE_UNAVAILABLE` if the queue is unreachable.

---

### 2.8 UserAdminController — `CU00`–`CU03` / `COUSR00`–`COUSR03`

Administrative user CRUD. **All endpoints require role `ADMIN`.** The `userId` path variable is
`PIC X(08)`. Passwords are accepted on input, stored as BCrypt hashes, and **never** returned.

#### `GET /api/admin/users` — List users (`COUSR00`, 10 rows/page)

- **Query:** `userId` (`USRIDINI`, opt. start key), `page`, `size` (default **10**).

**Response `200` — paged `UserSummary`**

| JSON field | COBOL field | Type | Length | Notes |
| :-- | :-- | :-- | :-: | :-- |
| `userId` | `USRIDnnI` | string | 8 | |
| `firstName` | `FNAMEnnI` | string | 20 | |
| `lastName` | `LNAMEnnI` | string | 20 | |
| `userType` | `UTYPEnnI` | string | 1 | `A`/`U`. |

#### `POST /api/admin/users` — Add user (`COUSR01`)

**Request — `UserCreateRequest`**

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `firstName` | `FNAMEI` | string | 20 | yes | |
| `lastName` | `LNAMEI` | string | 20 | yes | |
| `userId` | `USERIDI` | string | 8 | yes | Unique; `409` if duplicate. |
| `password` | `PASSWDI` | string | 8 | yes | BCrypt-hashed at rest; never returned. |
| `userType` | `USRTYPEI` | string | 1 | yes | `A` or `U`. |

**Response `201` — `UserResponse`** (`userId`, `firstName`, `lastName`, `userType` — no password).

#### `PUT /api/admin/users/{id}` — Update user (`COUSR02`)

**Request — `UserUpdateRequest`** (keyed by path `id` = `USRIDINI`)

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `firstName` | `FNAMEI` | string | 20 | yes | |
| `lastName` | `LNAMEI` | string | 20 | yes | |
| `password` | `PASSWDI` | string | 8 | no | Re-hashed if supplied. |
| `userType` | `USRTYPEI` | string | 1 | yes | |

**Response `200` — `UserResponse`.**

#### `DELETE /api/admin/users/{id}` — Delete user (`COUSR03`)

- **Path var:** `id` = `USRIDINI` (`PIC X(08)`). The COBOL delete screen displays
  `FNAMEI`/`LNAMEI`/`USRTYPEI` for confirmation (no password entry).

**Response `204 No Content`.**

**Errors (all user-admin endpoints):** `400` validation; `404` `RECORD_NOT_FOUND`;
`409` `DUPLICATE_RECORD` (add with existing ID); `401`/`403`.

---

### 2.9 Session state (CommArea) note

The CICS programs share conversational state through `CARDDEMO-COMMAREA` (copybook
`COCOM01Y.cpy`). The migration is **stateless**: there is no server-held session. The COMMAREA
fields are redistributed to **JWT claims** (identity/role) and **request/response DTOs**
(the navigational selections), so no equivalent of the COMMAREA crosses the wire as a single
blob.

| COMMAREA field | COBOL type | Replaced by |
| :-- | :-- | :-- |
| `CDEMO-USER-ID` | `PIC X(08)` | JWT `sub` / `userId` claim |
| `CDEMO-USER-TYPE` (`88 …-ADMIN 'A'` / `…-USER 'U'`) | `PIC X(01)` | JWT `role` claim (`ADMIN`/`USER`) |
| `CDEMO-CUST-ID` | `PIC 9(09)` | Path/body field (`customerId`) where relevant |
| `CDEMO-ACCT-ID` | `PIC 9(11)` | Path variable `{id}` on account endpoints |
| `CDEMO-ACCT-STATUS` | `PIC X(01)` | `accountStatus` response field |
| `CDEMO-CARD-NUM` | `PIC 9(16)` | Path variable `{cardNum}` on card endpoints |
| `CDEMO-FROM/TO-TRANID`, `CDEMO-FROM/TO-PROGRAM` | `PIC X(04)`/`X(08)` | Client-side routing (no server state) |

---

## 3. SQS Contract — Report Submission Queue

The CICS transient-data queue (TDQ) used by `CORPT00C` to submit a report job
(`EXEC CICS WRITEQ TD QUEUE('JOBS')`) is replaced by an **AWS SQS FIFO queue**. This preserves
the point-to-point, ordered, exactly-once delivery semantics of the TDQ (decision **D-004**).

| Property | Value |
| :-- | :-- |
| **Queue name** | `carddemo-report-jobs.fifo` |
| **Type** | FIFO (ordered, deduplicated) |
| **Producer** | `ReportSubmissionService` (← `CORPT00C` `WRITEQ TD`), invoked by `POST /api/reports/submit` |
| **Consumer** | `TransactionReportJob` trigger (Spring Batch) |
| **Endpoint (local)** | LocalStack `http://localhost:4566` |
| **Provisioning** | `localstack-init/init-aws.sh` (and `docker-compose.yml`); tests self-provision and tear down |

**Message body (JSON)** — one message per accepted report request:

| Field | Type | Origin | Notes |
| :-- | :-- | :-- | :-- |
| `reportType` | string enum | `CORPT00` flags | `MONTHLY` \| `YEARLY` \| `CUSTOM`. |
| `startDate` | string (date) | `SDTYYYY`/`SDTMM`/`SDTDD` | ISO `yyyy-MM-dd`; required for `CUSTOM`. |
| `endDate` | string (date) | `EDTYYYY`/`EDTMM`/`EDTDD` | ISO `yyyy-MM-dd`; required for `CUSTOM`. |
| `requestedBy` | string(8) | JWT `userId` | Submitting user. |
| `correlationId` | string (UUID) | request | Ties message → REST response → generated report. |

**FIFO attributes**

- `MessageGroupId` = `report-jobs` (single ordered group → strict TDQ-equivalent ordering).
- `MessageDeduplicationId` = `correlationId` (content-stable de-dup; rejects accidental
  re-submission within the dedup window).

```json
{
  "reportType": "CUSTOM",
  "startDate": "2022-01-01",
  "endDate": "2022-01-31",
  "requestedBy": "ADMIN001",
  "correlationId": "b3f1c2a4-5d6e-4f70-8a91-0c2d4e6f8a1b"
}
```

> **SNS (notifications).** A complementary SNS topic is used for alert/notification fan-out
> (provisioned alongside SQS in LocalStack). It carries operational notifications only and is
> not part of the report-submission request/response contract above.

---

## 4. S3 Contract — Batch Staging, Statements & Rejects

The mainframe generation-data-group (GDG) datasets and batch sequential files are re-hosted on
**AWS S3**. GDG generations map to **S3 object versioning**; each GDG base declared in
`DEFGDGB.jcl` carries `LIMIT(5)`, so the buckets retain the **last 5 versions** of an object
(lifecycle-enforced), exactly matching the GDG retention.

| Bucket | Replaces (GDG / DD) | Contents |
| :-- | :-- | :-- |
| `carddemo-batch-input` | `DALYTRAN` PS input, `AWS.M2.CARDDEMO.TRANSACT.DALY` | Daily transaction input files staged for posting. |
| `carddemo-batch-output` | `AWS.M2.CARDDEMO.TRANSACT.BKUP`, `AWS.M2.CARDDEMO.TRANREPT`, `DALYREJS(+1)` | Combined/backup transaction files, transaction reports, rejection files. |
| `carddemo-statements` | statement output (`CBSTM03A`/`CBSTM03B`) | Generated account statements (text + HTML). |

**Object key layout** (date-partitioned prefixes; `{yyyyMMdd}` is the business run date):

| Purpose | Key prefix / pattern | Format |
| :-- | :-- | :-- |
| Daily transaction input | `daily/{yyyyMMdd}/dailytran.txt` | Fixed-width, 350-byte records |
| Combined transactions / backup | `combined/{yyyyMMdd}/transact.bkup` | Fixed-width, 350-byte records |
| Rejections | `rejects/{yyyyMMdd}/dalyrejs.txt` | Fixed-width: 350-byte data + reason code `9(04)` + desc `X(76)` |
| Transaction report | `reports/{yyyyMMdd}/tranrept.txt` | Report print-line layout |
| Statement (text) | `statements/{yyyyMMdd}/{accountId}.txt` | Plain-text statement |
| Statement (HTML) | `statements/{yyyyMMdd}/{accountId}.html` | HTML statement |

**Contract preservation (Gate 5).** All staged/produced records keep their original
**fixed-width layouts byte-for-byte** — record lengths, field offsets, and decimal scales are
identical to the COBOL `FD`/copybook definitions (e.g. transaction records are 350 bytes;
rejection records append the `100`–`109` reason code from [§1.9](#19-batch-reject-reason-codes)).
Object encoding is configured for path-style access against LocalStack; no live AWS account is
used.

---

## 5. Traceability & References

- **Source baseline:** AWS CardDemo, commit SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`).
  COBOL/CICS/BMS/JCL sources are **referenced, not copied** into `carddemo-java`.
- **REST source assets:** 17 BMS mapsets (`app/bms/*.bms`) and 17 symbolic maps
  (`app/cpy-bms/*.CPY`); session model from `app/cpy/COCOM01Y.cpy`.
- **Error / reject model:** `app/cbl/CBTRN02C.cbl` (FILE STATUS clauses + reason codes 100–109).
- **SQS source:** `app/cbl/CORPT00C.cbl` (`WRITEQ TD QUEUE('JOBS')`).
- **S3 source:** `app/jcl/DEFGDGB.jcl` (GDG bases, `LIMIT(5)`); batch DD names in
  `app/jcl/POSTTRAN.jcl`, `TRANREPT.jcl`, `COMBTRAN.jcl`, `CREASTMT.JCL`.

**Contract invariants enforced by this document**

- External interface contracts (REST DTO field layouts, record lengths, decimal precision,
  SQS message schema, S3 object layouts) are preserved exactly versus the COBOL baseline.
- No feature expansion: exactly 8 controllers / 17 endpoints, 1 SQS FIFO queue, and 3 S3 buckets
  — nothing beyond the migrated feature set.
- No hardcoded credentials: the JWT secret and AWS configuration resolve from the environment.
- AWS-only: no Google Cloud Platform (GCS / Pub/Sub / Firestore) interfaces are exposed.

*Generated as part of the CardDemo COBOL-to-Java migration. This contract is authoritative for
the application's external interfaces; generated OpenAPI/Swagger specifications are out of scope.*

