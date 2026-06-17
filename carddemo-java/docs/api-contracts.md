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
| `token` | issued JWT | string | Bearer token for subsequent requests; `null` on failure. |
| `userId` | `COSGN00` `USERID` / `CDEMO-USER-ID` | string(8) | Echoed authenticated user; `null` on failure. |
| `userType` | `SEC-USR-TYPE` / `CDEMO-USER-TYPE` | enum | `"ADMIN"` or `"USER"` (serialized enum name); `null` on failure. |
| `errorMessage` | `COSGN00` `ERRMSG` (`PIC X(78)`) | string | Generalized failure text; `null` on success. |

> The response carries exactly these four fields (`SignOnResponse`). There is no
> `tokenType`, `role`, or `expiresIn` field; the JWT expiry is encoded inside the token's
> `exp` claim. The `Authorization: Bearer <token>` scheme for subsequent requests is
> described in §1.2.

**Errors:** `400` `BAD_REQUEST` validation only when `userId` or `password` is blank/too long
(bean-validation on `SignOnRequest`, both `@NotBlank @Size(max = 8)`). **All** authentication
failures after that point — unknown user *and* wrong password alike — return a single generalized
`401` `UNAUTHORIZED` with an identical client-safe message (no user-enumeration signal); the
specific outcome is recorded only in server metrics/logs.

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
| `customerId` | `ACSTNUM` | string | 9 | `PIC 9(09)`. |
| `ssn` | `ACSTSSN` | string | — | Social-Security number (aggregate). |
| `dateOfBirth` | `ACSTDOB` | string (date) | 10 | |
| `ficoScore` | `ACSTFCO` | string | 3 | |
| `firstName` / `middleName` / `lastName` | `ACSFNAM` / `ACSMNAM` / `ACSLNAM` | string | 25 each | |
| `addressLine1` / `addressLine2` | `ACSADL1` / `ACSADL2` | string | 50 each | |
| `city` | `ACSCITY` | string | 50 | |
| `stateCode` | `ACSSTTE` | string | 2 | |
| `zipCode` | `ACSZIPC` | string | 5 | |
| `countryCode` | `ACSCTRY` | string | 3 | |
| `phoneNumber1` / `phoneNumber2` | `ACSPHN1` / `ACSPHN2` | string | — | Aggregate phone numbers. |
| `governmentIssuedId` | `ACSGOVT` | string | 20 | |
| `eftAccountId` | `ACSEFTC` | string | 10 | |
| `primaryCardHolderIndicator` | `ACSPFLG` | string | 1 | |
| `infoMessage` / `errorMessage` | `INFOMSG` / `ERRMSG` | string | — | Operator-message fields (output-only). |
| `version` | (record image) | integer (`Long`) | — | Optimistic-lock token echoed from this read (the Java equivalent of the CICS `READ UPDATE` before-image). Supply it back on `PUT` and re-fetch it to recover from a `409` ([§1.8](#18-optimistic-concurrency)). Carried on this read only; the update response does not echo it (see [§2.3 update](#put-apiaccountsid--account-update-coactup)). |

> Note: the account-**view** response (`AccountViewResponse`) aggregates dates, SSN, and phone
> numbers into single fields (`openDate`, `ssn`, `phoneNumber1`), whereas the account-**update**
> contract below (`AccountUpdateRequest`/`AccountUpdateResponse`) keeps them as split part fields,
> matching the distinct `COACTVW` vs `COACTUP` BMS map layouts.

**Errors:** `404` `RECORD_NOT_FOUND` (account or cross-reference missing); `401`/`403`.

#### `PUT /api/accounts/{id}` — Account update (`COACTUP`)

- **Auth:** USER. **Semantics:** runs in a single **`@Transactional`** boundary that updates
  `ACCTDAT` + `CUSTDAT` atomically (the sole `SYNCPOINT ROLLBACK`); **`@Version`** optimistic
  locking applies (see [§1.8](#18-optimistic-concurrency)).
- **Path/body consistency:** the path variable `{id}` is bound as `Long accountId` and compared
  numerically to the body `accountId`. A mismatch (e.g. `PUT /api/accounts/111` with body
  `accountId = "222"`) is rejected with `400` `BAD_REQUEST` before any update is attempted, so a
  request can never update a record other than the one named in the route.

**Request — `AccountUpdateRequest`.** The COBOL update map presents dates, the SSN, and phone
numbers as **split part fields** (year/month/day; SSN area/group/serial; phone area/prefix/line);
this contract preserves that split layout verbatim. The five monetary fields are `BigDecimal`
(`@Digits(integer = 10, fraction = 2)`); every other field is a string whose maximum length matches
the BMS field length exactly. Component order below matches the JSON wire order.

| JSON field | COBOL field | Type | Max len | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `version` | (record image) | integer (`Long`) | — | **yes** (`@NotNull`) | Optimistic-lock token echoed from the last read; compared to `Account.version`. |
| `accountId` | `ACCTSID` | string | 11 | **yes** (`@NotBlank`, `\d{1,11}`) | Must equal the path `{id}` numerically (see above). |
| `accountStatus` | `ACSTTUS` | string | 1 | no | |
| `openYear` | `OPNYEAR` | string | 4 | no | Account open-date year part. |
| `openMonth` | `OPNMON` | string | 2 | no | Account open-date month part. |
| `openDay` | `OPNDAY` | string | 2 | no | Account open-date day part. |
| `creditLimit` | `ACRDLIM` | BigDecimal | 10,2 | no | Scale 2. |
| `expirationYear` | `EXPYEAR` | string | 4 | no | Card expiration-date year part. |
| `expirationMonth` | `EXPMON` | string | 2 | no | Card expiration-date month part. |
| `expirationDay` | `EXPDAY` | string | 2 | no | Card expiration-date day part. |
| `cashCreditLimit` | `ACSHLIM` | BigDecimal | 10,2 | no | Scale 2. |
| `reissueYear` | `RISYEAR` | string | 4 | no | Card reissue-date year part. |
| `reissueMonth` | `RISMON` | string | 2 | no | Card reissue-date month part. |
| `reissueDay` | `RISDAY` | string | 2 | no | Card reissue-date day part. |
| `currentBalance` | `ACURBAL` | BigDecimal | 10,2 | no | Scale 2. |
| `currentCycleCredit` | `ACRCYCR` | BigDecimal | 10,2 | no | Scale 2. |
| `accountGroupId` | `AADDGRP` | string | 10 | no | |
| `currentCycleDebit` | `ACRCYDB` | BigDecimal | 10,2 | no | Scale 2. |
| `customerId` | `ACSTNUM` | string | 9 | no | |
| `ssnPart1` | `ACTSSN1` | string | 3 | no | SSN area part. |
| `ssnPart2` | `ACTSSN2` | string | 2 | no | SSN group part. |
| `ssnPart3` | `ACTSSN3` | string | 4 | no | SSN serial part. |
| `dobYear` | `DOBYEAR` | string | 4 | no | Date-of-birth year part. |
| `dobMonth` | `DOBMON` | string | 2 | no | Date-of-birth month part. |
| `dobDay` | `DOBDAY` | string | 2 | no | Date-of-birth day part. |
| `ficoScore` | `ACSTFCO` | string | 3 | no | |
| `firstName` | `ACSFNAM` | string | 25 | no | |
| `middleName` | `ACSMNAM` | string | 25 | no | |
| `lastName` | `ACSLNAM` | string | 25 | no | |
| `addressLine1` | `ACSADL1` | string | 50 | no | |
| `stateCode` | `ACSSTTE` | string | 2 | no | |
| `addressLine2` | `ACSADL2` | string | 50 | no | |
| `zipCode` | `ACSZIPC` | string | 5 | no | |
| `city` | `ACSCITY` | string | 50 | no | |
| `countryCode` | `ACSCTRY` | string | 3 | no | |
| `phone1Area` | `ACSPH1A` | string | 3 | no | Primary phone area-code part. |
| `phone1Prefix` | `ACSPH1B` | string | 3 | no | Primary phone prefix part. |
| `phone1Line` | `ACSPH1C` | string | 4 | no | Primary phone line-number part. |
| `governmentIssuedId` | `ACSGOVT` | string | 20 | no | |
| `phone2Area` | `ACSPH2A` | string | 3 | no | Secondary phone area-code part. |
| `phone2Prefix` | `ACSPH2B` | string | 3 | no | Secondary phone prefix part. |
| `phone2Line` | `ACSPH2C` | string | 4 | no | Secondary phone line-number part. |
| `eftAccountId` | `ACSEFTC` | string | 10 | no | |
| `primaryCardHolderIndicator` | `ACSPFLG` | string | 1 | no | |

**Response `200` — `AccountUpdateResponse`.** Carries the same split account/customer field layout
as the request (dates, SSN, and phone numbers kept as discrete parts) and additionally exposes the
two operator-message fields `infoMessage` (confirmation) and `errorMessage` (validation feedback).
The five monetary components are `BigDecimal` (scale 2). It does **not** echo the `version` field.

| JSON field group | Fields |
| :-- | :-- |
| Account | `accountId`, `accountStatus`, `openYear`/`openMonth`/`openDay`, `creditLimit`, `expirationYear`/`expirationMonth`/`expirationDay`, `cashCreditLimit`, `reissueYear`/`reissueMonth`/`reissueDay`, `currentBalance`, `currentCycleCredit`, `accountGroupId`, `currentCycleDebit` |
| Customer | `customerId`, `ssnPart1`/`ssnPart2`/`ssnPart3`, `dobYear`/`dobMonth`/`dobDay`, `ficoScore`, `firstName`/`middleName`/`lastName`, `addressLine1`/`stateCode`/`addressLine2`/`zipCode`/`city`/`countryCode`, `phone1Area`/`phone1Prefix`/`phone1Line`, `governmentIssuedId`, `phone2Area`/`phone2Prefix`/`phone2Line`, `eftAccountId`, `primaryCardHolderIndicator` |
| Messages | `infoMessage`, `errorMessage` |

**Errors:** `400` `BAD_REQUEST` (bean-validation failure, or path/body `accountId` mismatch);
`404` `RECORD_NOT_FOUND` (account or customer missing); `409` `CONFLICT` (`version` does not match
the persisted `Account.version` — optimistic-lock failure, see [§1.8](#18-optimistic-concurrency));
`401`/`403` per auth.

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
| `version` | (record image) | integer (`Long`) | — | Optimistic-lock token echoed from this read (the Java equivalent of the CICS `READ UPDATE` before-image). Supply it on `PUT` and re-fetch it to recover from a `409` ([§1.8](#18-optimistic-concurrency)). Carried on this read only; the update response (`CardUpdateResponse`) does not echo it. |

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

**Response `200`** — `CardUpdateResponse` (the updated card detail). Per
[§1.8](#18-optimistic-concurrency) this update response does **not** echo the `version`
field; clients re-fetch the current version via `GET /api/cards/{cardNum}`.

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
| `transactionIdInput` | `TRNIDIN` | string | 16 | Echoed lookup key from the request. |
| `transactionId` | `TRNID` | string | 16 | Resolved transaction id. |
| `cardNumber` | `CARDNUM` | string | 16 | |
| `typeCode` | `TTYPCD` | string | 2 | |
| `categoryCode` | `TCATCD` | string | 4 | |
| `source` | `TRNSRC` | string | 10 | |
| `description` | `TDESC` | string | 60 | Full description. |
| `amount` | `TRNAMT` | BigDecimal | scale 2 | `TRAN-AMT`. |
| `originDate` | `TORIGDT` | string (date) | 10 | |
| `processDate` | `TPROCDT` | string (date) | 10 | |
| `merchantId` | `MID` | string | 9 | |
| `merchantName` | `MNAME` | string | 30 | |
| `merchantCity` | `MCITY` | string | 25 | |
| `merchantZip` | `MZIP` | string | 10 | |
| `errorMessage` | `ERRMSG` | string | 78 | User-facing error text. |

**Errors:** `404` `RECORD_NOT_FOUND`; `401`/`403`.

#### `POST /api/transactions` — Transaction add (`COTRN02`)

- **Auth:** USER. **Semantics:** the transaction ID is **auto-generated** (browse-to-end +
  increment), reproducing `COTRN02C`; the COBOL confirmation field (`CONFIRMI`) maps to a request
  flag rather than a second screen round-trip.

**Request — `TransactionAddRequest`.** Component order below matches the JSON wire order. The new
transaction id is **not** a request field — it is auto-generated by the service. The monetary
`amount` is `BigDecimal` constrained to nine integer and two fractional digits (`TRAN-AMT`,
`PIC S9(9)V99`); no floating-point type is used.

| JSON field | COBOL field | Type | Length / scale | Required | Notes |
| :-- | :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACTIDIN` | string | 11 | **yes** | `@NotBlank`, `\d{1,11}`. Target account. |
| `cardNumber` | `CARDNIN` | string | 16 | **yes** | `@NotBlank`, `\d{1,16}`. Target card. |
| `typeCode` | `TTYPCD` | string | 2 | **yes** | `@NotBlank`, exactly `\d{2}`. |
| `categoryCode` | `TCATCD` | string | 4 | **yes** | `@NotBlank`, exactly `\d{4}`. |
| `source` | `TRNSRC` | string | 10 | **yes** | `@NotBlank`. |
| `description` | `TDESC` | string | 60 | **yes** | `@NotBlank`. |
| `amount` | `TRNAMT` | BigDecimal | 9,2 | **yes** | `@NotNull` `@Digits(integer = 9, fraction = 2)`; `TRAN-AMT PIC S9(9)V99`. |
| `originDate` | `TORIGDT` | string (date) | 10 | **yes** | `@NotBlank`, `YYYY-MM-DD`. |
| `processDate` | `TPROCDT` | string (date) | 10 | **yes** | `@NotBlank`, `YYYY-MM-DD`. |
| `merchantId` | `MID` | string | 9 | **yes** | `@NotBlank`, `\d{1,9}`. |
| `merchantName` | `MNAME` | string | 30 | **yes** | `@NotBlank`. |
| `merchantCity` | `MCITY` | string | 25 | **yes** | `@NotBlank`. |
| `merchantZip` | `MZIP` | string | 10 | **yes** | `@NotBlank`. |
| `confirm` | `CONFIRM` | string | 1 | no | Optional; one of `Y`/`y`/`N`/`n` when present (`[YyNn]?`). |

**Response `201` `CREATED` — `TransactionAddResponse`.** Carries the service-generated
`transactionId` plus the echoed submitted fields so the client can re-render the confirmed
transaction: `transactionId`, `accountId`, `cardNumber`, `typeCode`, `categoryCode`, `source`,
`description`, `amount` (BigDecimal, scale 2), `originDate`, `processDate`, `merchantId`,
`merchantName`, `merchantCity`, `merchantZip`, `confirm`, `errorMessage`.

**Errors:** `400` `BAD_REQUEST` (bean-validation failure); `404` `RECORD_NOT_FOUND`
(account/card cross-reference missing); `401`/`403` per auth.

---

### 2.6 BillingController — `CB00` / `COBIL00`

Posts a bill payment against an account balance.

#### `POST /api/billing/pay`

- **Auth:** USER. **Source:** `COBIL00.bms` / `COBIL00.CPY` / `COBIL00C`.

**Request — `BillPaymentRequest`**

| JSON field | COBOL field | Type | Length / scale | Required | Notes |
| :-- | :-- | :-- | :-- | :-: | :-- |
| `accountId` | `ACTIDIN` | string | 11 | yes | `@NotBlank`, `\d{1,11}`. Account to pay. |
| `confirm` | `CONFIRM` | string | 1 | no | Optional single-char flag: empty = preview, `Y`/`y` = confirm payment, `N`/`n` = cancel. |

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

**Request — `ReportRequest`.** The BMS `CORPT00` selection layout is preserved verbatim: report
type is **three mutually-exclusive single-character flags** (not collapsed to an enum), and the
custom window is carried as **split month/day/year part fields** (not merged ISO dates). Every
field is a string whose maximum length matches the BMS field length exactly.

| JSON field | COBOL field | Type | Length | Required | Notes |
| :-- | :-- | :-- | :-: | :-: | :-- |
| `monthly` | `MONTHLY` | string | 1 | no | Set (e.g. `"Y"`) to select the monthly report. |
| `yearly` | `YEARLY` | string | 1 | no | Set to select the yearly report. |
| `custom` | `CUSTOM` | string | 1 | no | Set to select a custom date-range report. |
| `startMonth` | `SDTMM` | string | 2 | conditional | Custom-range start month part. |
| `startDay` | `SDTDD` | string | 2 | conditional | Custom-range start day part. |
| `startYear` | `SDTYYYY` | string | 4 | conditional | Custom-range start year part. |
| `endMonth` | `EDTMM` | string | 2 | conditional | Custom-range end month part. |
| `endDay` | `EDTDD` | string | 2 | conditional | Custom-range end day part. |
| `endYear` | `EDTYYYY` | string | 4 | conditional | Custom-range end year part. |
| `confirm` | `CONFIRM` | string | 1 | yes | `Y`/`y` confirms; `N`/`n` cancels. The service requires confirmation before publishing. |

> The three report-type flags are mutually exclusive; the custom date parts are required only when
> `custom` is selected and are validated through `DateValidationService`. The service resolves the
> selection to a `reportType` of `"Monthly"`, `"Yearly"`, or `"Custom"` and a concrete
> `startDate`/`endDate` (`yyyy-MM-dd`) for the SQS payload (see §3).

**Response `200` — `ReportResponse`**

| JSON field | Origin | Type | Notes |
| :-- | :-- | :-- | :-- |
| `confirmationMessage` | service | string | Acknowledgement that the report job was queued (e.g. `"Monthly report submitted for printing ..."`); `null` on failure. |
| `errorMessage` | `CORPT00` `ERRMSG` (`PIC X(78)`) | string | Failure text; `null` on success. |

**Errors:** `400` `BAD_REQUEST` (`ValidationException` — missing confirmation, invalid `confirm`
value, or an invalid/incomplete custom date range); `500` `INTERNAL_SERVER_ERROR`
(`FileAccessException` — the SQS publish failed, mirroring a `WRITEQ TD` failure); `401`/`403`
per auth.

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

**Message body (JSON) — `ReportSubmissionService.ReportJobMessage`** — one message per accepted
report request. The payload carries exactly three fields; the report-type flags and split date
parts from `ReportRequest` are resolved by the service into a single `reportType` value and a
concrete `startDate`/`endDate` range before publishing.

| Field | Type | Origin | Notes |
| :-- | :-- | :-- | :-- |
| `reportType` | string | resolved from `CORPT00` flags | One of `Monthly` \| `Yearly` \| `Custom` (mixed case, as emitted). |
| `startDate` | string (date) | resolved range start | ISO `yyyy-MM-dd`. For `Monthly`/`Yearly` the service derives the period; for `Custom` it is built from `SDTYYYY`/`SDTMM`/`SDTDD`. |
| `endDate` | string (date) | resolved range end | ISO `yyyy-MM-dd`. Derived for `Monthly`/`Yearly`; from `EDTYYYY`/`EDTMM`/`EDTDD` for `Custom`. |

> There is **no** `requestedBy` or `correlationId` field in the message body; the payload is exactly
> the three fields above.

**FIFO attributes**

- `MessageGroupId` = `carddemo-reports` (single ordered group → strict TDQ-equivalent ordering).
- `MessageDeduplicationId` = a fresh `UUID.randomUUID()` per publish (every accepted submission is a
  distinct job; identical selections are intentionally not de-duplicated, matching the COBOL
  `WRITEQ TD` semantics where each submission enqueues a job).

```json
{
  "reportType": "Custom",
  "startDate": "2022-01-01",
  "endDate": "2022-01-31"
}
```

> **SNS (notifications).** A complementary SNS topic is used for alert/notification fan-out
> (provisioned alongside SQS in LocalStack). It carries operational notifications only and is
> not part of the report-submission request/response contract above.

---

## 4. S3 Contract — Batch Staging, Statements & Rejects

The mainframe generation-data-group (GDG) datasets and batch sequential files are re-hosted on
**AWS S3**. GDG generations map to **S3 object versioning**; each GDG base declared in
`DEFGDGB.jcl` carries `LIMIT(5)`, so an **S3 lifecycle policy** (noncurrent-version expiration with
`NewerNoncurrentVersions=4`) retains each object's current version plus the 4 most-recent
noncurrent versions — the **last 5 generations** — matching the GDG retention. Older noncurrent
versions expire after a one-day minimum (the S3 lifecycle floor). Versioning and the lifecycle
policy are provisioned on all three buckets by `localstack-init/init-aws.sh`.

| Bucket | Replaces (GDG / DD) | Contents |
| :-- | :-- | :-- |
| `carddemo-batch-input` | `DALYTRAN` PS input, `AWS.M2.CARDDEMO.TRANSACT.DALY` | Daily transaction input files staged for posting. |
| `carddemo-batch-output` | `AWS.M2.CARDDEMO.SYSTRAN`, `AWS.M2.CARDDEMO.TRANSACT.BKUP`/`.COMBINED`, `AWS.M2.CARDDEMO.TRANREPT`, `DALYREJS(+1)` | Posted-transaction staging, combined/backup transaction files, transaction reports, rejection files. |
| `carddemo-statements` | statement output (`CBSTM03A`/`CBSTM03B`) | Generated account statements (text + HTML). |

**Object key layout.** Objects use **flat keys** named after the source JCL GDG-base / DD names
(no date partitioning); S3 object versioning — not a key prefix — provides the generation history.
Each key is fixed per stage, so a re-run overwrites the same key and adds a new S3 version.

| Purpose | Bucket | Object key | Format |
| :-- | :-- | :-- | :-- |
| Daily transaction input | `carddemo-batch-input` | `dailytran.txt` | Fixed-width, 350-byte records |
| Posted-transaction staging (interest input) | `carddemo-batch-output` | `SYSTRAN` | Fixed-width, 350-byte records |
| Combined transactions — backup input | `carddemo-batch-output` | `TRANSACT.BKUP` | Fixed-width, 350-byte records |
| Combined transactions — output | `carddemo-batch-output` | `TRANSACT.COMBINED` | Fixed-width, 350-byte records |
| Rejections | `carddemo-batch-output` | `DALYREJS` | Fixed-width: 350-byte data + reason code `9(04)` + desc `X(76)` = 430 bytes |
| Transaction report | `carddemo-batch-output` | `TRANREPT` | 133-byte report print-lines |
| Statement (text) | `carddemo-statements` | `STATEMNT.PS` | Plain-text statement |
| Statement (HTML) | `carddemo-statements` | `STATEMNT.HTML` | HTML statement |

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

