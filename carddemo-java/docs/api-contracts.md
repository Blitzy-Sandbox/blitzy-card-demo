# CardDemo — API, Messaging, and Storage Contracts

**Document type:** External interface contract reference
**Subject system:** AWS CardDemo credit-card management application (`CardDemo_v1.0-15-g27d6c6f-68`)
**Migration target:** `carddemo-java` — Java 25 LTS + Spring Boot 3.x
**Source traceability:** COBOL/CICS/BMS/JCL sources are **not** copied into this repository; every contract below is traced to its originating mainframe artifact at source commit **`27d6c6f`**.

---

## 1. Purpose and Scope

This document is the single source of truth for the **external interface contracts** exposed by the migrated CardDemo application. It specifies, with byte-level fidelity to the mainframe source, three contract families:

1. **REST / JSON contracts** — the synchronous HTTP API that replaces the 17 CICS pseudo-conversational BMS 3270 screens. Eight Spring MVC controllers expose exactly the behavior of the 17 online COBOL programs.
2. **SQS contract** — the asynchronous report-submission queue that replaces the CICS transient-data-queue (TDQ) `WRITEQ TD` online-to-batch bridge.
3. **S3 contract** — the object-storage layout that replaces the z/OS generation-data-group (GDG) and sequential batch staging datasets.

These contracts are **frozen**: field layouts, record lengths, decimal precision, and message/file schemas are preserved exactly so that the migrated system achieves 100% behavioral parity with the COBOL baseline (no behavioral regression, no feature expansion).

### 1.1 What this document does *not* cover

- It does **not** introduce any endpoint, verb, entity, or business rule beyond the closed feature set migrated from the source (no feature expansion).
- It does **not** describe machine-generated OpenAPI/Swagger specifications. Auto-generated API description documents are an explicitly deferred, out-of-scope future enhancement; this Markdown contract is hand-authored from the BMS symbolic maps.
- It does **not** reference any Google Cloud Platform service (GCS, Pub/Sub, Firestore). The authoritative cloud target is AWS, exercised locally against LocalStack with zero live AWS dependencies.
- It contains **no credentials or secrets**. The JWT signing secret and all AWS/datasource credentials resolve at runtime from environment variables or a secrets manager; none are embedded here.

### 1.2 How the contracts were derived (BMS symbolic map → DTO)

Each CICS screen is backed by a BMS *mapset* (`app/bms/<MAP>.bms`) and a *symbolic map* copybook (`app/cpy-bms/<MAP>.CPY`). A symbolic map declares two redefining records:

- `01 <map>I` — the **input** record (terminal → program), and
- `01 <map>O REDEFINES <map>I` — the **output** record (program → terminal).

For every screen field, BMS generates several helper subfields that exist purely for 3270 screen control:

| Suffix | COBOL picture | Meaning | Contract relevance |
| :----- | :------------ | :------ | :----------------- |
| `*L`   | `COMP PIC S9(4)` | field input length | dropped (HTTP has no field-length cursor) |
| `*F` / `*A` | `PIC X` | attribute / flag byte | dropped (3270 attribute handling) |
| `*I`   | `PIC X(n)` / `9(n)` | **input data value** | **mapped → request DTO field** |
| `*C` `*P` `*H` `*V` | `PIC X` | colour / highlight / validation attributes | dropped (presentation only) |
| `*O`   | `PIC X(n)` / `9(n)` | **output data value** | **mapped → response DTO field** |

**Only the `*I` (request) and `*O` (response) data values are contract-relevant.** They are mapped to JSON properties using lowerCamelCase names. The COBOL screen-control helpers and the static screen chrome (`TRNNAME`, `TITLE01`, `TITLE02`, `CURDATE`, `CURTIME`, `PGMNAME`, `APPLID`, `SYSID`) carry no business data and are not part of any DTO; the date/time/program identifiers are server-derived and surfaced, when needed, through standard response metadata rather than request input.

---

## 2. Table of Contents

- [3. Common Conventions](#3-common-conventions)
- [4. Status, Error, and Reject-Code Mapping](#4-status-error-and-reject-code-mapping)
- [5. REST API Contracts](#5-rest-api-contracts)
  - [5.1 AuthController — Sign-on](#51-authcontroller--sign-on)
  - [5.2 AccountController — Account View / Update](#52-accountcontroller--account-view--update)
  - [5.3 CardController — Card List / Detail / Update](#53-cardcontroller--card-list--detail--update)
  - [5.4 TransactionController — List / Detail / Add](#54-transactioncontroller--list--detail--add)
  - [5.5 BillingController — Bill Payment](#55-billingcontroller--bill-payment)
  - [5.6 ReportController — Report Submission](#56-reportcontroller--report-submission)
  - [5.7 UserAdminController — User CRUD](#57-useradmincontroller--user-crud)
  - [5.8 MenuController — Main / Admin Menus](#58-menucontroller--main--admin-menus)
- [6. SQS Contract — Report Submission Queue](#6-sqs-contract--report-submission-queue)
- [7. S3 Contract — Batch Staging, Statements, and Rejections](#7-s3-contract--batch-staging-statements-and-rejections)
- [8. Appendix A — Screen-to-Endpoint Traceability Matrix](#8-appendix-a--screen-to-endpoint-traceability-matrix)
- [9. Appendix B — CARDDEMO-COMMAREA Field Mapping](#9-appendix-b--carddemo-commarea-field-mapping)

---

## 3. Common Conventions

### 3.1 Base URL and media type

- All REST endpoints are rooted at `/api`.
- Request and response bodies use `application/json` with UTF-8 encoding. The `Content-Type` and `Accept` headers must be `application/json` for all bodies.
- Path and query parameters are URL-encoded; numeric identifiers are transmitted as JSON strings or numbers per the field tables (account and card identifiers preserve their fixed COBOL widths as strings to retain leading zeros).

### 3.2 Authentication and authorization

- Authentication is **stateless** and based on a **JSON Web Token (JWT)** issued by `POST /api/auth/signin`.
- Every endpoint except `POST /api/auth/signin` requires the header:

  ```
  Authorization: Bearer <jwt>
  ```

- The JWT carries the caller's identity and role as claims (see §3.3). The signing secret is supplied to the runtime through an environment variable / secrets manager and is never hardcoded.
- Role mapping reproduces the COBOL `CDEMO-USER-TYPE` flag from `CSUSR01Y` / `COCOM01Y`:

  | COBOL value (`CDEMO-USER-TYPE`) | 88-level condition | JWT role |
  | :------------------------------ | :----------------- | :------- |
  | `A`                             | `CDEMO-USRTYP-ADMIN` | `ADMIN` |
  | `U`                             | `CDEMO-USRTYP-USER`  | `USER`  |

- Administrative endpoints (`/api/admin/**`, `GET /api/menu/admin`) require the `ADMIN` role; all other authenticated endpoints accept either role. This mirrors the COBOL admin-menu (`COADM01C`) gate that only admin users could reach.

### 3.3 Stateless session model (CICS COMMAREA → JWT + DTOs)

The CICS programs passed conversational state between pseudo-conversational turns through the `CARDDEMO-COMMAREA` structure (`app/cpy/COCOM01Y.cpy`). The Java migration holds **no server-side conversational state**. The COMMAREA decomposes as follows:

- **Identity / authorization** (`CDEMO-USER-ID`, `CDEMO-USER-TYPE`) → **JWT claims**.
- **Navigation context** (`CDEMO-FROM-PROGRAM`, `CDEMO-TO-PROGRAM`, `CDEMO-FROM-TRANID`, `CDEMO-TO-TRANID`, `CDEMO-PGM-CONTEXT`, `CDEMO-LAST-MAP`, `CDEMO-LAST-MAPSET`) → **REST routing** (the client navigates by calling the next endpoint; no carry-over field is needed).
- **Selection context** (`CDEMO-ACCT-ID`, `CDEMO-CARD-NUM`, `CDEMO-CUST-ID`, and the customer-name / account-status echo fields) → **explicit request path/body parameters and response payloads**.

The full field-by-field mapping is tabulated in [Appendix B](#9-appendix-b--carddemo-commarea-field-mapping).

### 3.4 Standard error envelope (RFC 7807 Problem Detail)

All non-2xx responses that carry a body use the **RFC 7807 `application/problem+json`**
shape produced by Spring's `ProblemDetail` (see `GlobalExceptionHandler`). The body has
exactly these fields:

```json
{
  "type": "about:blank",
  "title": "Record Not Found",
  "status": 404,
  "detail": "Account cross-reference not found for key: 99999999999",
  "instance": "/api/accounts/99999999999"
}
```

- `type` — a URI reference for the problem type; the application uses the default
  `"about:blank"` for every error.
- `title` — a short, human-readable summary of the problem class (see the title table in
  §4.1). It is stable per exception type.
- `status` — the HTTP status code (mirrors the response status line).
- `detail` — a human-readable explanation specific to this occurrence; never leaks
  credentials, SQL, or stack traces.
- `instance` — the request path that produced the error.

Notes that differ from a conventional custom envelope:

- **Content type** is `application/problem+json` (not `application/json`).
- There is **no** `code`, `timestamp`, `error`, `message`, or `path` field. Machine-readable
  discrimination is done via `status` + `title` (see §4.1).
- The **correlation id is not in the body for 4xx errors**. It is always emitted on the
  `X-Correlation-Id` response header (by `CorrelationIdFilter`) and in structured logs. For
  `5xx` server errors the handler additionally adds a `correlationId` member to the body.
- **`401 Unauthorized` and `403 Forbidden` return an empty body** (handled by the Spring
  Security entry point / access-denied handler); only the status line and the
  `X-Correlation-Id` header are present.

**Bean Validation `@Valid` failures** return `400 Bad Request` with `title: "Validation
Error"`. There is **no** `fieldErrors[]` array — the individual field violations are
concatenated (separated by `; `) into the single `detail` string:

```json
{
  "type": "about:blank",
  "title": "Validation Error",
  "status": 400,
  "detail": "amount must not be null; originDate must not be blank; source must not be blank",
  "instance": "/api/transactions"
}
```

### 3.5 Pagination

List endpoints reproduce the fixed page sizes wired into the originating BMS screens. The page size is a **contract constant**, not a tunable parameter, because the COBOL browse logic was built around the screen geometry:

| Endpoint | Source screen | Rows per page |
| :------- | :------------ | :------------ |
| `GET /api/cards` | `COCRDLI` (Card List) | **7** |
| `GET /api/transactions` | `COTRN00` (Transaction List) | **10** |
| `GET /api/admin/users` | `COUSR00` (List Users) | **10** |

Common pagination parameters:

- `page` — 0-based page index (query parameter, default `0`).
- Each list response echoes a **`pageNumber`** (a string, e.g. `"1"`) and the active filter
  value (e.g. `userIdFilter`, `transactionIdFilter`), the page of rows, and a nullable
  `errorMessage`. The responses do **not** include `pageSize`, `hasNext`, or `hasPrevious`
  fields — the fixed page size from the table above is an internal contract constant, and
  forward/backward browsing matches the COBOL `STARTBR` / `READNEXT` / `READPREV` paradigm
  via the `page` index. Any client-supplied page-size override is ignored. See the
  per-endpoint response shapes in §5.4 (transactions) and §5.7 (users).

### 3.6 Data types and decimal precision

COBOL `COMP-3` / signed-decimal `PIC` fields map to `java.math.BigDecimal` with the **scale taken from the PIC clause** — never to `float` or `double`. Numeric comparisons use `compareTo()` (scale-insensitive), never `equals()`.

| COBOL picture | Source field example | JSON type | Scale | Notes |
| :------------ | :------------------- | :-------- | :---- | :---- |
| `PIC S9(10)V99` | `ACCT-CURR-BAL` (`CVACT01Y`) | number | **2** | 10 integer + 2 fraction digits |
| `PIC S9(09)V99` | `TRAN-AMT` (`CVTRA05Y`) | number | **2** | 9 integer + 2 fraction digits |
| `PIC 9(11)` | `ACCT-ID` (`CVACT01Y`) | string | n/a | 11-digit account id; leading zeros preserved |
| `PIC X(16)` | `CARD-NUM` (`CVACT02Y`) | string | n/a | 16-char card number; preserved verbatim |

Monetary values are backed by `java.math.BigDecimal` (scale 2) and are serialized by Jackson
as **JSON numbers with two decimal places preserved** (e.g. `492.00`, `6169.00`) — not as
quoted strings. The `BigDecimal` scale guarantees the exact two-place precision survives JSON
round-tripping without binary floating-point drift; the trailing-zero scale (`.00`) is
emitted by Jackson's default `BigDecimal` serialization.

---

## 4. Status, Error, and Reject-Code Mapping

### 4.1 COBOL FILE STATUS → HTTP status

The migrated services translate VSAM FILE STATUS outcomes (as seen across the online programs and the batch posting program `CBTRN02C`) into HTTP responses. The error body carries no machine-readable `code`; instead, each exception type sets a stable RFC 7807 **`title`** (see §3.4). The mapping is:

| FILE STATUS | COBOL meaning | Java exception | HTTP status | Problem `title` |
| :---------- | :------------ | :------------- | :---------- | :-------------- |
| `00` | Successful I/O | — | `200` / `201` / `204` | — |
| `23` | Record not found / end-of-file on keyed read | `RecordNotFoundException` | `404 Not Found` | `Record Not Found` |
| `22` | Duplicate key on write | `DuplicateRecordException` | `409 Conflict` | `Duplicate Record` |
| `2x`/`3x`/`9x` (other I/O errors) | Logic / permanent I/O error | `FileAccessException` | `500 Internal Server Error` | `File Access Error` |

Two additional cross-cutting mappings preserve the CICS update semantics of `COACTUPC` and `COCRDUPC`:

| Condition | COBOL origin | Java exception | HTTP status | Problem `title` |
| :-------- | :----------- | :------------- | :---------- | :-------------- |
| Concurrent modification (before/after image mismatch) | `READ UPDATE` image compare | `ConcurrencyException` (JPA `@Version`) | `409 Conflict` | `Concurrent Update Conflict` |
| Dual-record update rollback | sole `SYNCPOINT ROLLBACK` in `COACTUPC` (account + customer) | rollback via `@Transactional` | `409` / `500` (per cause) | per cause (e.g. `Data Conflict`, `Processing Error`) |

The complete set of problem `title` values emitted by `GlobalExceptionHandler`, keyed by the
exception (or Spring MVC condition) that produces it:

| Java exception / condition | HTTP status | Problem `title` |
| :------------------------- | :---------- | :-------------- |
| `RecordNotFoundException` | `404` | `Record Not Found` |
| `DuplicateRecordException` | `409` | `Duplicate Record` |
| `ConcurrencyException` | `409` | `Concurrent Update Conflict` |
| `ValidationException` | `400` | `Validation Error` |
| `MethodArgumentNotValidException` (`@Valid`) | `400` | `Validation Error` |
| `DataIntegrityViolationException` | `409` | `Data Conflict` |
| `TransactionPostingException` | `422` | `Transaction Posting Rejected` |
| `FileAccessException` | `500` | `File Access Error` |
| `CardDemoException` (base / uncategorized) | `500` | `Processing Error` |
| `MethodArgumentTypeMismatchException` | `400` | `Invalid Request Parameter` |
| `HttpMessageNotReadableException` | `400` | `Malformed Request` |
| `HttpRequestMethodNotSupportedException` | `405` | `Method Not Allowed` |
| `HttpMediaTypeNotSupportedException` | `415` | `Unsupported Media Type` |
| `NoResourceFoundException` | `404` | `Resource Not Found` |

> `401 Unauthorized` (missing/invalid JWT) and `403 Forbidden` (insufficient role) are produced by the Spring Security filter chain — **not** by `GlobalExceptionHandler` — and return an empty body with only the status line and the `X-Correlation-Id` header.

### 4.2 Batch reject reason codes (NOT HTTP)

The daily-posting program `CBTRN02C` validates each incoming transaction and, on failure, writes a 350-byte reject record to the rejects file (`DALYREJS`) carrying a numeric reason code (`WS-VALIDATION-FAIL-REASON`, range **100–109**) and a 76-byte description. These codes are an artifact of the **batch** pipeline and surface in the S3 rejection objects (see §7), **never** as HTTP status codes:

| Reject code | Description (verbatim from source) | Trigger |
| :---------- | :--------------------------------- | :------ |
| `100` | `INVALID CARD NUMBER FOUND` | Card cross-reference lookup failed |
| `101` | `ACCOUNT RECORD NOT FOUND` | Account master read failed |
| `102` | `OVERLIMIT TRANSACTION` | Transaction would exceed the account credit limit |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Processing date is past account expiry |
| `109` | `ACCOUNT RECORD NOT FOUND` | Account read failed on the category-balance update path |

> Codes `104`–`108` are reserved within the `100`–`109` band for future validation rules in the COBOL source and are carried forward unused to preserve the numbering contract.

The reject record layout (`REJECT-RECORD`) is preserved byte-for-byte: `X(350)` original transaction data + `9(04)` reason code + `X(76)` reason description.

---

## 5. REST API Contracts

Eight controllers expose the 17 online screens. Each endpoint below documents its HTTP method and path, the originating BMS map and COBOL program, the request and response DTO field tables (derived from the symbolic-map `*I`/`*O` fields), the auth requirement, and the error responses it can return.

> **Field table legend.** *COBOL field* is the symbolic-map data field; *COBOL pic* is its picture clause; *JSON field* is the lowerCamelCase DTO property; *Type* is the JSON/Java type; *Len/scale* is the preserved width (characters) or `BigDecimal` scale; *Req* marks request-required fields.

### 5.1 AuthController — Sign-on

Source: BMS map `COSGN00` · program `COSGN00C` · CICS transaction `CC00`.

#### `POST /api/auth/signin`

Authenticates a user against the migrated user-security store and issues a JWT. Replaces the COBOL sign-on flow; the plaintext `SEC-USR-PWD` of `CSUSR01Y` is upgraded to a BCrypt hash while preserving the login semantics (constraint C-003). **Auth:** none (this is the token-issuing endpoint).

**Request DTO — `SignOnRequest`**

| COBOL field | COBOL pic | JSON field | Type | Len/scale | Req |
| :---------- | :-------- | :--------- | :--- | :-------- | :-- |
| `USERIDI` | `X(8)` | `userId` | string | 8 | yes |
| `PASSWDI` | `X(8)` | `password` | string | 8 | yes |

**Response DTO — `SignOnResponse`** (HTTP `200`)

| JSON field | Type | Notes |
| :--------- | :--- | :---- |
| `token` | string | Signed JWT (Bearer). |
| `userId` | string(8) | Authenticated user id. |
| `userType` | string(1) | `A` or `U` (echoes `CDEMO-USER-TYPE`). |
| `role` | string | `ADMIN` or `USER` (derived; see §3.2). |
| `expiresAt` | string (ISO-8601) | Token expiry timestamp. |

**Errors** (RFC 7807 problem body, see §3.4)

| Condition | HTTP | Problem `title` | `detail` |
| :-------- | :--- | :-------------- | :------- |
| Unknown user id (`USRSEC` read FILE STATUS `23` → `RecordNotFoundException`) | `404 Not Found` | `Record Not Found` | `User not found. Try again ...` |
| Password mismatch (`ValidationException`) | `400 Bad Request` | `Validation Error` | `Wrong Password. Try again ...` |
| Blank `userId`/`password`, or `userId` longer than 8 chars (`@Valid`) | `400 Bad Request` | `Validation Error` | e.g. `userId must not be blank`, `userId size must be between 0 and 8` |

> The `ERRMSGO X(78)` output field of `COSGN00` (e.g. *"User not found. Try again ..."*,
> *"Wrong Password. Try again ..."*) maps to the problem `detail`. The migrated flow preserves
> the COBOL behavior of distinguishing the two failure modes: an **unknown user returns `404`**
> while a **wrong password returns `400`**, so the responses do reveal which credential failed.
> (The original `COSGN00C` likewise issued distinct messages.) Clients must not assume a
> uniform `401`.

### 5.2 AccountController — Account View / Update

Source: BMS maps `COACTVW` / `COACTUP` · programs `COACTVWC` / `COACTUPC` · CICS transactions `CAVW` / `CAUP`. **Auth:** `USER` or `ADMIN`.

The account view (`COACTVWC`) reads and joins three datasets — account master (`ACCTDAT`), the card cross-reference (`CXACAIX` alternate index), and customer master (`CUSTDAT`). The account update (`COACTUPC`) is the **only** program in CardDemo that issues a `SYNCPOINT ROLLBACK`: it updates the account and customer records together under one unit of work, mapped to `@Transactional` with rollback-on-exception, and uses an optimistic before/after image check mapped to JPA `@Version`.

#### `GET /api/accounts/{accountId}`

Path parameter `accountId` — `PIC 9(11)`, 11-digit string (leading zeros significant).

**Response DTO — `AccountViewResponse`** (HTTP `200`). The five monetary fields are `BigDecimal` scale 2 (`PIC S9(10)V99`).

| COBOL field | COBOL pic | JSON field | Type | Len/scale |
| :---------- | :-------- | :--------- | :--- | :-------- |
| `ACCTSID` | `9(11)` | `accountId` | string | 11 |
| `ACSTTUS` | `X(1)` | `accountStatus` | string | 1 |
| `ADTOPEN` | `X(10)` | `openDate` | string (date) | 10 |
| `AEXPDT` | `X(10)` | `expirationDate` | string (date) | 10 |
| `AREISDT` | `X(10)` | `reissueDate` | string (date) | 10 |
| `ACRDLIM` | `S9(10)V99` | `creditLimit` | BigDecimal | scale 2 |
| `ACSHLIM` | `S9(10)V99` | `cashCreditLimit` | BigDecimal | scale 2 |
| `ACURBAL` | `S9(10)V99` | `currentBalance` | BigDecimal | scale 2 |
| `ACRCYCR` | `S9(10)V99` | `currentCycleCredit` | BigDecimal | scale 2 |
| `ACRCYDB` | `S9(10)V99` | `currentCycleDebit` | BigDecimal | scale 2 |
| `AADDGRP` | `X(10)` | `accountGroupId` | string | 10 |
| `ACSTNUM` | `X(9)` | `customerId` | string | 9 |
| `ACSTSSN` | `X(12)` | `ssn` | string | 12 |
| `ACSTDOB` | `X(10)` | `dateOfBirth` | string (date) | 10 |
| `ACSTFCO` | `X(3)` | `ficoScore` | string | 3 |
| `ACSFNAM` | `X(25)` | `firstName` | string | 25 |
| `ACSMNAM` | `X(25)` | `middleName` | string | 25 |
| `ACSLNAM` | `X(25)` | `lastName` | string | 25 |
| `ACSADL1` | `X(50)` | `addressLine1` | string | 50 |
| `ACSADL2` | `X(50)` | `addressLine2` | string | 50 |
| `ACSCITY` | `X(50)` | `city` | string | 50 |
| `ACSSTTE` | `X(2)` | `stateCode` | string | 2 |
| `ACSZIPC` | `X(5)` | `zipCode` | string | 5 |
| `ACSCTRY` | `X(3)` | `countryCode` | string | 3 |
| `ACSPHN1` | `X(13)` | `phoneNumber1` | string | 13 |
| `ACSPHN2` | `X(13)` | `phoneNumber2` | string | 13 |
| `ACSGOVT` | `X(20)` | `governmentIssuedId` | string | 20 |
| `ACSEFTC` | `X(10)` | `eftAccountId` | string | 10 |
| `ACSPFLG` | `X(1)` | `primaryCardHolderIndicator` | string | 1 |
| (none) | — | `version` | integer | — |

> `version` is the JPA `@Version` optimistic-lock token; it has no BMS-map field. Clients echo it on the subsequent `PUT /api/accounts/{accountId}` so the update service can detect a concurrent modification (`409 Concurrent Update Conflict`).

**Errors:** `404 Record Not Found` when the account, cross-reference, or customer record is absent (FILE STATUS `23`).

#### `PUT /api/accounts/{accountId}`

Updates the account and its associated customer record atomically. The COBOL update map (`COACTUP`) splits dates into year/month/day components (`OPNYEAR`/`OPNMON`/`OPNDAY`, `EXPYEAR`/`EXPMON`/`EXPDAY`, `RISYEAR`/`RISMON`/`RISDAY`, `DOBYEAR`/`DOBMON`/`DOBDAY`), the SSN into three parts (`ACTSSN1`/`ACTSSN2`/`ACTSSN3`), and each phone into three parts (`ACSPH1A/B/C`, `ACSPH2A/B/C`). That split-field shape is the binding external interface contract, so the `AccountUpdateRequest` JSON preserves it component-for-component rather than assembling the parts: it carries `openYear`/`openMonth`/`openDay`, `expirationYear`/`expirationMonth`/`expirationDay`, `reissueYear`/`reissueMonth`/`reissueDay`, `dobYear`/`dobMonth`/`dobDay`, `ssnPart1`/`ssnPart2`/`ssnPart3`, `phone1Area`/`phone1Prefix`/`phone1Line`, and `phone2Area`/`phone2Prefix`/`phone2Line`, each preserving the underlying field width.

**Request DTO — `AccountUpdateRequest`**: the writable account/customer fields in the split-field shape described above (`accountStatus`; the split open/expiration/reissue/date-of-birth parts; the five `BigDecimal` balances including `creditLimit` and `cashCreditLimit`; the split SSN parts; customer name, address, and split phone parts; `ficoScore`; `governmentIssuedId`; `eftAccountId`; `primaryCardHolderIndicator`), together with `accountId`/`customerId` and the required `version` optimistic-lock token.

| Concurrency control | JSON field | Type | Notes |
| :------------------ | :--------- | :--- | :---- |
| Optimistic lock token | `version` | integer | JPA `@Version`, **required** (`@NotNull`); the client echoes the value last read from the view/update response, and it must match the current row or `409 Concurrent Update Conflict` is returned. |

**Response:** `200` with an `AccountUpdateResponse` — the refreshed split-field account/customer view, including the new `version`.

**Errors:** `404 Record Not Found` (account/customer missing); `409 Concurrent Update Conflict` (version mismatch); `409 Data Conflict` / `500 Processing Error` when the dual-record transaction rolls back; `400 Validation Error` (field validation).

### 5.3 CardController — Card List / Detail / Update

Source: BMS maps `COCRDLI` / `COCRDSL` / `COCRDUP` · programs `COCRDLIC` / `COCRDSLC` / `COCRDUPC` · CICS transactions `CCLI` / `CCDL` / `CCUP`. **Auth:** `USER` or `ADMIN`.

#### `GET /api/cards`

Paginated card browse — **7 rows per page** (the COCRDLI screen displays 7 card lines). Optional filters mirror the screen's filter fields.

**Query parameters**

| COBOL field | COBOL pic | Query param | Type | Notes |
| :---------- | :-------- | :---------- | :--- | :---- |
| `ACCTSID` | `X(11)` | `accountId` | string(11) | optional account filter |
| `CARDSID` | `X(16)` | `cardNumber` | string(16) | optional card filter |
| `PAGENO` | `X(3)` | `page` | integer | 0-based page index |

**Response DTO — `CardListResponse`**: `pageNumber`, `accountIdFilter`, `cardNumberFilter`, `infoMessage`, `errorMessage`, and `cards[]` where each element is a `CardListItem` (the COCRDLI screen displays **7 rows per page**):

| COBOL field (row *n*) | COBOL pic | JSON field | Type | Len |
| :-------------------- | :-------- | :--------- | :--- | :-- |
| `CRDSELn` | `X(1)` | `selectionFlag` | string | 1 |
| `ACCTNOn` | `X(11)` | `accountId` | string | 11 |
| `CRDNUMn` | `X(16)` | `cardNumber` | string | 16 |
| `CRDSTSn` | `X(1)` | `cardStatus` | string | 1 |

> `selectionFlag` mirrors the 3270 line-selection control (`CRDSELn`); a REST client may leave it unset and select a card directly via `GET /api/cards/{cardNumber}`.

#### `GET /api/cards/{cardNumber}`

Single keyed read (`COCRDSLC`). Path parameter `cardNumber` — `PIC X(16)`.

**Required query parameter — `accountId`:** the migrated `CardDetailService` (mirroring
`COCRDSLC`, which keys on the account/card pair) requires the owning account id. It is declared
as `@RequestParam(required = false)` on the controller, but the service rejects a missing or
blank value, so in practice it is **mandatory**.

| Query parameter | COBOL pic | Type | Req | Notes |
| :-------------- | :-------- | :--- | :-- | :---- |
| `accountId` | `X(11)` | string | yes | Owning account id; omitting it returns `400` (see Errors). |

Example: `GET /api/cards/0500024453765740?accountId=00000000050`.

**Response DTO — `CardDetailResponse`** (HTTP `200`)

| COBOL field | COBOL pic | JSON field | Type | Len |
| :---------- | :-------- | :--------- | :--- | :-- |
| `ACCTSID` | `X(11)` | `accountId` | string | 11 |
| `CARDSID` | `X(16)` | `cardNumber` | string | 16 |
| `CRDNAME` | `X(50)` | `nameOnCard` | string | 50 |
| `CRDSTCD` | `X(1)` | `cardStatus` | string | 1 |
| `EXPMON` | `X(2)` | `expirationMonth` | string | 2 |
| `EXPYEAR` | `X(4)` | `expirationYear` | string | 4 |
| (none) | — | `version` | integer | — |

> `version` is the JPA `@Version` optimistic-lock token (no BMS field); clients echo it on the subsequent `PUT /api/cards/{cardNumber}`.

**Errors:** `400 Validation Error` with `detail: "Account number not provided"` when the
`accountId` query parameter is missing or blank; `404 Record Not Found` when no card matches
the account/card pair.

#### `PUT /api/cards/{cardNumber}`

Optimistic update (`COCRDUPC`) of an existing card. **Request DTO — `CardUpdateRequest`**:

| COBOL field | COBOL pic | JSON field | Type | Len/scale | Req |
| :---------- | :-------- | :--------- | :--- | :-------- | :-- |
| `ACCTSID` | `X(11)` | `accountId` | string | 11 | yes |
| `CARDSID` | `X(16)` | `cardNumber` | string | 16 | yes |
| `CRDNAME` | `X(50)` | `nameOnCard` | string | 50 | yes |
| `CRDSTCD` | `X(1)` | `cardStatus` | string | 1 | yes |
| `EXPMON` | `X(2)` | `expirationMonth` | string | 2 | yes |
| `EXPDAY` | `X(2)` | `expirationDay` | string | 2 | no |
| `EXPYEAR` | `X(4)` | `expirationYear` | string | 4 | yes |
| (lock) | — | `version` | integer | — | yes |

> The body **must** include both `accountId` (`@NotBlank`) and `cardNumber` (`@NotBlank`); the
> controller additionally validates that the body `cardNumber` equals the `{cardNumber}` path
> variable. Omitting `accountId` fails with `400` `detail: "accountId must not be blank"`.
> Field-edit semantics mirror `COCRDUPC`: `nameOnCard` (required, letters/spaces only, `1230-EDIT-NAME`), `cardStatus` (required, `Y`/`N`, `1240-EDIT-CARDSTATUS`), `expirationMonth` (required, `1`–`12`, `1250-EDIT-EXPIRY-MON`), and `expirationYear` (required, `1950`–`2099`, `1260-EDIT-EXPIRY-YEAR`). `expirationDay` is length-bounded only because `COCRDUPC` has no day edit paragraph, so it is **not** required. `version` is the required JPA `@Version` optimistic-lock token.

**Response:** `200` with a `CardUpdateResponse` — the refreshed card view including the new `version`.
**Errors:** `404 Record Not Found`; `409 Concurrent Update Conflict` (`@Version` mismatch); `400 Validation Error` (missing/invalid fields, including a blank `accountId`).


### 5.4 TransactionController — List / Detail / Add

Source: BMS maps `COTRN00` / `COTRN01` / `COTRN02` · programs `COTRN00C` / `COTRN01C` / `COTRN02C` · CICS transactions `CT00` / `CT01` / `CT02`. **Auth:** `USER` or `ADMIN`.

#### `GET /api/transactions`

Paginated transaction browse — **10 rows per page** (the COTRN00 screen displays 10 transaction lines).

**Query parameters**

| COBOL field | COBOL pic | Query param | Type | Notes |
| :---------- | :-------- | :---------- | :--- | :---- |
| `TRNIDIN` | `X(16)` | `transactionId` | string(16) | optional start-at filter |
| `PAGENUM` | `X(8)` | `page` | integer | 0-based page index |

**Response DTO — `TransactionListResponse`**: `pageNumber`, `transactionIdFilter`, `errorMessage`, and `transactions[]` where each element is a `TransactionListItem` (the COTRN00 screen displays **10 rows per page**):

| COBOL field (row *n*) | COBOL pic | JSON field | Type | Len/scale |
| :-------------------- | :-------- | :--------- | :--- | :-------- |
| `SELn` | `X(1)` | `selectionFlag` | string | 1 |
| `TRNIDn` | `X(16)` | `transactionId` | string | 16 |
| `TDATEn` | `X(8)` | `date` | string (date) | 8 |
| `TDESCn` | `X(26)` | `description` | string | 26 |
| `TAMTn` | `X(12)` (← `S9(09)V99`) | `amount` | BigDecimal | scale 2 |

#### `GET /api/transactions/{transactionId}`

Keyed detail read (`COTRN01C`). Path parameter `transactionId` — `PIC X(16)`.

**Response DTO — `TransactionDetailResponse`** (HTTP `200`)

| COBOL field | COBOL pic | JSON field | Type | Len/scale |
| :---------- | :-------- | :--------- | :--- | :-------- |
| `TRNID` | `X(16)` | `transactionId` | string | 16 |
| `CARDNUM` | `X(16)` | `cardNumber` | string | 16 |
| `TTYPCD` | `X(2)` | `typeCode` | string | 2 |
| `TCATCD` | `X(4)` | `categoryCode` | string | 4 |
| `TRNSRC` | `X(10)` | `source` | string | 10 |
| `TDESC` | `X(60)` | `description` | string | 60 |
| `TRNAMT` | `X(12)` (← `S9(09)V99`) | `amount` | BigDecimal | scale 2 |
| `TORIGDT` | `X(10)` | `originDate` | string (date) | 10 |
| `TPROCDT` | `X(10)` | `processDate` | string (date) | 10 |
| `MID` | `X(9)` | `merchantId` | string | 9 |
| `MNAME` | `X(30)` | `merchantName` | string | 30 |
| `MCITY` | `X(25)` | `merchantCity` | string | 25 |
| `MZIP` | `X(10)` | `merchantZip` | string | 10 |

**Errors:** `404 Record Not Found`.

#### `POST /api/transactions`

Adds a transaction (`COTRN02C`). The transaction id is **auto-generated** server-side by browsing the transaction store to the highest existing id and incrementing it (the COBOL browse-to-end + increment pattern); clients do **not** supply `transactionId`.

**Request DTO — `TransactionAddRequest`**

| COBOL field | COBOL pic | JSON field | Type | Len/scale | Req |
| :---------- | :-------- | :--------- | :--- | :-------- | :-- |
| `ACTIDIN` | `X(11)` | `accountId` | string | 11 | yes |
| `CARDNIN` | `X(16)` | `cardNumber` | string | 16 | yes |
| `TTYPCD` | `X(2)` | `typeCode` | string | 2 | yes |
| `TCATCD` | `X(4)` | `categoryCode` | string | 4 | yes |
| `TRNSRC` | `X(10)` | `source` | string | 10 | yes |
| `TDESC` | `X(60)` | `description` | string | 60 | yes |
| `TRNAMT` | `X(12)` (← `S9(09)V99`) | `amount` | BigDecimal | scale 2 | yes |
| `TORIGDT` | `X(10)` | `originDate` | string (date) | 10 | yes |
| `TPROCDT` | `X(10)` | `processDate` | string (date) | 10 | yes |
| `MID` | `X(9)` | `merchantId` | string | 9 | yes |
| `MNAME` | `X(30)` | `merchantName` | string | 30 | yes |
| `MCITY` | `X(25)` | `merchantCity` | string | 25 | yes |
| `MZIP` | `X(10)` | `merchantZip` | string | 10 | yes |
| `CONFIRM` | `X(1)` | `confirm` | string | 1 | no |

Required/format edits mirror `COTRN02C VALIDATE-INPUT-DATA-FIELDS`: `typeCode`, `categoryCode`, and `merchantId` are required and numeric; `source`, `description`, `merchantName`, and `merchantCity` are required; `originDate`/`processDate` are required and formatted `YYYY-MM-DD`; `merchantZip` is required (no numeric edit in source).

The `confirm` field reproduces the COBOL two-step confirmation flow (`COTRN02C`). To
**persist** the transaction, `confirm` must be `Y` (or `y`). Any other value is rejected with
`400 Validation Error` — the record is **not** returned for review and nothing is persisted:

- `confirm` = `Y` / `y` → the transaction is validated and committed (`201 Created`).
- `confirm` = blank, missing, `N`, or `n` → `400` with `detail: "Confirm to add this transaction..."`.
- `confirm` = any other value → `400` with `detail: "Invalid value. Valid values are (Y/N)..."`.

(The COBOL confirmation prompt is enforced server-side; there is no non-committing
"validation pass" response — a non-`Y` value always yields a `400` prompt.)

**Response:** `201 Created` with `TransactionAddResponse` (the assembled record including the
server-generated `transactionId`, plus a nullable `errorMessage`).
**Errors:** `404 Record Not Found` (account or card cross-reference missing); `400 Validation Error` (field edits, or a non-`Y` `confirm` value as described above).

### 5.5 BillingController — Bill Payment

Source: BMS map `COBIL00` · program `COBIL00C` · CICS transaction `CB00`. **Auth:** `USER` or `ADMIN`.

#### `POST /api/billing/pay`

Posts a full balance bill payment against an account (`COBIL00C`). The screen displays the current balance (`CURBAL`) and requires a `CONFIRM` before posting a payment transaction that zeroes the balance.

**Request DTO — `BillPaymentRequest`**

| COBOL field | COBOL pic | JSON field | Type | Len/scale | Req |
| :---------- | :-------- | :--------- | :--- | :-------- | :-- |
| `ACTIDIN` | `X(11)` | `accountId` | string | 11 | yes |
| `CONFIRM` | `X(1)` | `confirm` | string | 1 | yes |

**Response DTO — `BillPaymentResponse`** (HTTP `200`)

| COBOL field | COBOL pic | JSON field | Type | Len/scale |
| :---------- | :-------- | :--------- | :--- | :-------- |
| `ACTIDIN` | `X(11)` | `accountId` | string | 11 |
| `CURBAL` | `X(14)` (← `S9(10)V99`) | `currentBalance` | BigDecimal | scale 2 |
| `CONFIRM` | `X(1)` | `confirm` | string | 1 |
| `ERRMSG` | `X(78)` | `errorMessage` | string | 78 |

When `confirm` ≠ `Y`, the endpoint returns the current balance (and echoes `confirm`) for review without posting, mirroring the COBOL confirmation gate; the posted-payment transaction id is not part of the CP1 `BillPaymentResponse` contract.
**Errors:** `404 Record Not Found` (account missing); `400 Validation Error`.

### 5.6 ReportController — Report Submission

Source: BMS map `CORPT00` · program `CORPT00C` · CICS transaction `CR00`. **Auth:** `USER` or `ADMIN`.

#### `POST /api/reports/submit`

Submits a transaction-report request. In COBOL this screen wrote a JCL record to the CICS extrapartition TDQ `JOBS` (`EXEC CICS WRITEQ TD QUEUE('JOBS')`), which the JES reader picked up to run the report batch job. The Java migration replaces the TDQ write with a publish to the **SQS FIFO queue `carddemo-report-jobs.fifo`** (full message contract in §6); the endpoint returns immediately (asynchronous submission) and the `TransactionReportJob` consumes the message to produce the report.

**Request DTO — `ReportRequest`**

The migrated `ReportRequest` preserves the `CORPT00` screen shape verbatim: the three report
kinds are **separate single-character flag fields** (not a collapsed enum), and the custom
date range is carried as **split month/day/year components** (not assembled date strings).

| COBOL field | COBOL pic | JSON field | Type | Len | Notes |
| :---------- | :-------- | :--------- | :--- | :-- | :---- |
| `MONTHLY` | `X(1)` | `monthly` | string | 1 | set to `"Y"` to request the monthly report |
| `YEARLY` | `X(1)` | `yearly` | string | 1 | set to `"Y"` to request the yearly report |
| `CUSTOM` | `X(1)` | `custom` | string | 1 | set to `"Y"` to request a custom-range report |
| `SDTMM` | `X(2)` | `startMonth` | string | 2 | custom range: start month |
| `SDTDD` | `X(2)` | `startDay` | string | 2 | custom range: start day |
| `SDTYYYY` | `X(4)` | `startYear` | string | 4 | custom range: start year |
| `EDTMM` | `X(2)` | `endMonth` | string | 2 | custom range: end month |
| `EDTDD` | `X(2)` | `endDay` | string | 2 | custom range: end day |
| `EDTYYYY` | `X(4)` | `endYear` | string | 4 | custom range: end year |
| `CONFIRM` | `X(1)` | `confirm` | string | 1 | confirmation gate — must be `"Y"` to enqueue |

Exactly one of `monthly` / `yearly` / `custom` must be `"Y"`. For `monthly` and `yearly` the
date range is derived server-side; for `custom` the split start/end components are validated and
assembled into the range. The `confirm` field reproduces the COBOL two-step confirmation gate.
There is **no** `reportType` enum and **no** assembled `startDate`/`endDate` request field.

**Response DTO — `ReportResponse`** (HTTP `202 Accepted`)

| JSON field | Type | Notes |
| :--------- | :--- | :---- |
| `confirmationMessage` | string | Success prompt, e.g. *"Monthly report submitted for printing ..."*; `null` on the validation/non-confirmed path. |
| `errorMessage` | string | Nullable error/prompt text; `null` on success. |

There is **no** `jobReference`, `reportType`, `startDate`/`endDate`, or `correlationId` field
in the response.

**Errors:** `400 Validation Error` — e.g. `detail: "Select a report type to print report..."`
when no flag is set, or an invalid custom range; the publish path throws `FileAccessException`,
which maps to **`500` `File Access Error`** (the COBOL *"Unable to Write TDQ (JOBS)"* path) if
the SQS publish fails.


### 5.7 UserAdminController — User CRUD

Source: BMS maps `COUSR00` / `COUSR01` / `COUSR02` / `COUSR03` · programs `COUSR00C` / `COUSR01C` / `COUSR02C` / `COUSR03C` · CICS transactions `CU00` / `CU01` / `CU02` / `CU03`. **Auth:** `ADMIN` only (reachable from the admin menu in the source).

User records originate from the security copybook `CSUSR01Y` (80-byte `USRSEC`). The plaintext `SEC-USR-PWD X(08)` is stored as a BCrypt hash; the hash is **never** returned in any response.

#### `GET /api/admin/users`

Paginated user browse — **10 rows per page** (the COUSR00 screen displays 10 user lines).

**Query parameters**

| COBOL field | COBOL pic | Query param | Type | Notes |
| :---------- | :-------- | :---------- | :--- | :---- |
| `USRIDIN` | `X(8)` | `userId` | string(8) | optional start-at filter |
| `PAGENUM` | `X(8)` | `page` | integer | 1-based page index (page 1 is the first page; a value below 1 is rejected with HTTP 400) |

**Response DTO — `UserListResponse`**: `pageNumber` (string, e.g. `"1"`), `userIdFilter`
(nullable; echoes the start-at filter), `users[]` (each element a `UserListItem`), and a
nullable `errorMessage`. It does **not** carry `pageSize`, `hasNext`, or `hasPrevious`. Each
`UserListItem` is:

| COBOL field (row *n*) | COBOL pic | JSON field | Type | Len | Notes |
| :-------------------- | :-------- | :--------- | :--- | :-- | :---- |
| `SELn` | `X(1)` | `selectionFlag` | string | 1 | row selection marker (nullable; `null` unless a row is flagged) |
| `USRIDn` | `X(8)` | `userId` | string | 8 | |
| `FNAMEn` | `X(20)` | `firstName` | string | 20 | |
| `LNAMEn` | `X(20)` | `lastName` | string | 20 | |
| `UTYPEn` | `X(1)` | `userType` | string | — | enum **name** `ADMIN` / `USER` (see the note under `POST`) |

#### `GET /api/admin/users/{userId}`

Single keyed read of one user (`COUSR02C` / `COUSR03C` load step). Path parameter `userId` —
`PIC X(8)`. Used by the update/delete screens to pre-load the selected user before editing.

**Response DTO — `UserResponse`** (HTTP `200`)

| JSON field | Type | Notes |
| :--------- | :--- | :---- |
| `userId` | string(8) | The user id. |
| `firstName` | string(20) | |
| `lastName` | string(20) | |
| `userType` | string (enum) | enum name `ADMIN` / `USER`. |
| `message` | string | Screen prompt (e.g. *"Press PF5 key to save your updates ..."*); nullable. |
| `errorMessage` | string | Nullable. |

> No password field is ever returned.

**Errors:** `404 Record Not Found` (no such user id).

#### `POST /api/admin/users`

Adds a user (`COUSR01C`). **Request DTO — `UserAddRequest`**:

| COBOL field | COBOL pic | JSON field | Type | Len | Req |
| :---------- | :-------- | :--------- | :--- | :-- | :-- |
| `USERIDI` | `X(8)` | `userId` | string | 8 | yes |
| `FNAMEI` | `X(20)` | `firstName` | string | 20 | yes |
| `LNAMEI` | `X(20)` | `lastName` | string | 20 | yes |
| `PASSWDI` | `X(8)` | `password` | string | 8 | yes (write-only; BCrypt-hashed at rest) |
| `USRTYPEI` | `X(1)` | `userType` | string (enum) | — | yes — JSON value `"ADMIN"` or `"USER"` |

> **`userType` JSON value:** the `UserType` enum has no `@JsonValue`/`@JsonCreator`, so Jackson
> (de)serializes it by its **enum name** — the request body must send `"ADMIN"` or `"USER"`.
> The single-character COBOL codes `"A"` / `"U"` are **rejected** with `400` `detail:
> "Request body is missing or malformed"` (a Jackson deserialization failure). Responses
> likewise emit the enum name (`"ADMIN"` / `"USER"`), not `A` / `U`.

**Response:** `201 Created` with `UserResponse` (`userId`, `firstName`, `lastName`, `userType`, plus a `message` and nullable `errorMessage`; **no** password field).
**Errors:** `409 Duplicate Record` (user id already exists, FILE STATUS `22`); `400 Validation Error`.

#### `PUT /api/admin/users/{userId}`

Updates a user (`COUSR02C`). Path parameter `userId` — `PIC X(8)`. **Request DTO — `UserUpdateRequest`**:

| COBOL field | COBOL pic | JSON field | Type | Len | Req |
| :---------- | :-------- | :--------- | :--- | :-- | :-- |
| `USRIDIN` | `X(8)` | `userId` | string | 8 | yes |
| `FNAMEI` | `X(20)` | `firstName` | string | 20 | yes |
| `LNAMEI` | `X(20)` | `lastName` | string | 20 | yes |
| `PASSWDI` | `X(8)` | `password` | string | 8 | yes (write-only; BCrypt-hashed, re-encoded only when changed) |
| `USRTYPEI` | `X(1)` | `userType` | string (enum) | — | yes — JSON value `"ADMIN"` or `"USER"` (see the `POST` note) |

> The body **must** include `userId` (`@NotBlank`), and the controller validates that it equals
> the `{userId}` path variable.

**Response:** `200` with `UserResponse`.
**Errors:** `404 Record Not Found`; `400 Validation Error`.

#### `DELETE /api/admin/users/{userId}`

Deletes a user (`COUSR03C`). Path parameter `userId` — `PIC X(8)`. The COBOL screen first displays the user (`FNAME`, `LNAME`, `USRTYPE`) for confirmation; the REST contract returns the deleted user's summary in the response body.

**Response:** `200` with the `UserResponse` of the deleted user (or `204 No Content` when the client does not request an echo).
**Errors:** `404 Record Not Found`.

### 5.8 MenuController — Main / Admin Menus

Source: BMS maps `COMEN01` / `COADM01` · programs `COMEN01C` / `COADM01C` · CICS transactions `CM00` / `CA00`. The menu option tables come from copybooks `COMEN02Y` (main) and `COADM02Y` (admin).

The COBOL menu programs rendered a fixed list of option labels (`OPTN001`–`OPTN012`, `X(40)` each) and accepted a 2-character `OPTION` selection that routed (`XCTL`) to the target program. In REST the menu is a **read-only catalog** of navigable options; routing is performed by the client calling the corresponding endpoint, so there is no server-side `OPTION` dispatch.

#### `GET /api/menu/main`

**Auth:** `USER` or `ADMIN`. Returns the **10** active main-menu options.

**Response DTO — `MenuResponse`**: `menuType` = `MAIN`, and `options[]` where each element is a `MenuOption`:

| COBOL field | COBOL pic | JSON field | Type | Notes |
| :---------- | :-------- | :--------- | :--- | :---- |
| (table index) | — | `optionNumber` | integer | 2-char `OPTION` selector value |
| `OPTN0nn` | `X(40)` | `label` | string(40) | menu line text |
| (derived) | — | `target` | string | REST path the option navigates to |

#### `GET /api/menu/admin`

**Auth:** `ADMIN` only. Returns the **4** active admin-menu options (user list / add / update / delete). Same `MenuResponse` / `MenuOption` shape as above, with `menuType` = `ADMIN`.

**Errors (both):** `401 Unauthorized` (missing/invalid token — empty body, see §3.4); `403 Forbidden` (non-admin calling `/api/menu/admin` — empty body).


---

## 6. SQS Contract — Report Submission Queue

This contract replaces the CICS transient-data-queue (TDQ) `WRITEQ TD` online-to-batch bridge of `CORPT00C`. Where the COBOL program wrote a JCL submission record to the extrapartition TDQ named `JOBS`, the migrated `ReportSubmissionService` publishes a JSON message to an AWS SQS **FIFO** queue. A FIFO queue is chosen (decision D-004) to preserve the **point-to-point, strictly-ordered** delivery semantics of the TDQ.

### 6.1 Queue

| Property | Value |
| :------- | :---- |
| Queue name | `carddemo-report-jobs.fifo` |
| Type | FIFO (`.fifo` suffix is mandatory) |
| Producer | `ReportSubmissionService` (← `CORPT00C` `WRITEQ TD QUEUE('JOBS')`) |
| Consumer | `TransactionReportJob` trigger (Spring Batch) |
| Message group id | `carddemo-reports` (single group preserves global ordering, mirroring the single TDQ; constant `REPORT_MESSAGE_GROUP_ID`, see DECISION_LOG D-020) |
| Content-based dedup | disabled (each message carries a per-message random UUID deduplication id; see DECISION_LOG D-020) |
| Local endpoint | `http://localhost:4566` (LocalStack); no live AWS dependency |

### 6.2 Message schema (`ReportJobMessage`)

`ReportJobMessage` is a three-field Java `record`; the message body is UTF-8 JSON with exactly
those three fields:

```json
{
  "reportType": "Custom",
  "startDate": "2026-01-01",
  "endDate": "2026-01-31"
}
```

| JSON field | Type | Source | Notes |
| :--------- | :--- | :----- | :---- |
| `reportType` | string | `MONTHLY`/`YEARLY`/`CUSTOM` screen flags | the report-type **literal** — `"Monthly"`, `"Yearly"`, or `"Custom"` (not an enum value) |
| `startDate` | string (date) | server-derived, or `SDTYYYY`/`SDTMM`/`SDTDD` for custom | inclusive range start (`YYYY-MM-DD`) |
| `endDate` | string (date) | server-derived, or `EDTYYYY`/`EDTMM`/`EDTDD` for custom | inclusive range end (`YYYY-MM-DD`) |

There are **no** `requestedBy`, `correlationId`, or `submittedAt` fields on the message
(`public record ReportJobMessage(String reportType, String startDate, String endDate)`).

### 6.3 Ordering, delivery, and parity notes

- **Ordering parity:** messages in the single `carddemo-reports` group are delivered in submission order, matching the FIFO read order of the TDQ `JOBS`.
- **Repeat-submission parity (D-020):** content-based deduplication is *disabled* and each submission carries a per-message random UUID deduplication id, so legitimately repeated report requests are each enqueued and processed — preserving the CICS `WRITEQ TD` behavior where every submission enqueued a distinct job. FIFO *ordering* (not deduplication) is the property retained.
- **Throughput:** FIFO queues cap at 300 messages/second without batching — far above the interactive report-submission rate, so the cap is not a constraint (D-004).
- **Failure behavior:** a publish failure throws `FileAccessException`, which maps to `500 File Access Error` for the caller (see §5.6), analogous to the COBOL *"Unable to Write TDQ (JOBS)"* error path; no message is enqueued.

---

## 7. S3 Contract — Batch Staging, Statements, and Rejections

This contract replaces the z/OS generation-data-group (GDG) datasets and sequential batch staging files. The GDG bases defined in `app/jcl/DEFGDGB.jcl` (each `LIMIT(5) SCRATCH`) map to **versioned S3 objects**: object versioning provides the "keep N generations" behavior, with a lifecycle rule retaining the **5** most recent non-current versions to mirror `LIMIT(5)`.

### 7.1 Buckets

| Bucket | Role | COBOL/JCL origin |
| :----- | :--- | :--------------- |
| `carddemo-batch-input` | Inbound batch staging: daily transaction file and other batch inputs | `AWS.M2.CARDDEMO.DALYTRAN.PS`, GDG input generations |
| `carddemo-batch-output` | Outbound batch artifacts: combined/sorted transactions, transaction reports, rejection files | `AWS.M2.CARDDEMO.TRANSACT.COMBINED`, `.TRANREPT`, `DALYREJS` |
| `carddemo-statements` | Generated account statements (text + HTML) | `CREASTMT` / `CBSTM03A` statement output |

All three buckets have versioning enabled and are created locally by the LocalStack init hook; no live AWS resources are used.

### 7.2 Object key layout

Object keys are **fixed GDG base names** — the DD / GDG-base names carried over from `app/jcl/DEFGDGB.jcl` and the batch job streams — **not** date- or run-partitioned prefixes. Successive batch runs write the **same** key, and the GDG "keep N generations" behavior is preserved entirely by **S3 object versioning** (see §7.1): each run produces a new object version under the same key rather than a new dated path. The keys are externalized as Spring properties whose defaults are the fixed names below:

| Purpose | Bucket | Object key (default) | Config property | Format |
| :------ | :----- | :------------------- | :-------------- | :----- |
| Daily transaction input | `carddemo-batch-input` | `dailytran.txt` | `carddemo.batch.daily-transaction.input-location` | fixed-width, 350-byte records |
| Posted-transaction staging (backup) | `carddemo-batch-output` | `TRANSACT.BKUP` | `carddemo.batch.combine.bkup-key` | fixed-width, 350-byte records |
| Interest staging | `carddemo-batch-output` | `SYSTRAN` | `carddemo.batch.interest.systran-key` / `carddemo.batch.combine.systran-key` | fixed-width, 350-byte records |
| Combined transactions | `carddemo-batch-output` | `TRANSACT.COMBINED` | `carddemo.batch.combine.combined-key` | fixed-width, 350-byte records |
| Transaction report | `carddemo-batch-output` | `TRANREPT` | `carddemo.batch.report.report-key` | text report, 133-column |
| Rejection file | `carddemo-batch-output` | `DALYREJS` | _(fixed)_ | fixed-width, 430-byte records |
| Statement (text) | `carddemo-statements` | `STATEMNT.PS` | _(fixed)_ | text |
| Statement (HTML) | `carddemo-statements` | `STATEMNT.HTML` | _(fixed)_ | HTML |

### 7.3 Fixed-width record layouts (preserved byte-for-byte)

External file layouts are byte-identical to the COBOL copybooks (Gate 5 — contract verification). Record lengths are retained exactly:

| Object | Copybook | Record length (bytes) |
| :----- | :------- | :-------------------- |
| Daily / combined transaction records | `CVTRA06Y` / `CVTRA05Y` | **350** |
| Rejection record | `CBTRN02C` `REJECT-RECORD` | **430** (`X(350)` data + `9(04)` reason + `X(76)` description) |
| Account master (statement source) | `CVACT01Y` | **300** |
| Card master (statement source) | `CVACT02Y` | **150** |
| Customer master (statement source) | `CVCUS01Y` | **500** |

The rejection objects carry the same `100`–`109` reason codes documented in §4.2; they are the batch-side surface of validation failures and are never returned through the REST API.

---

## 8. Appendix A — Screen-to-Endpoint Traceability Matrix

Every one of the 17 online screens (CICS transaction, BMS mapset, and COBOL program) maps to exactly one REST endpoint or endpoint group. This matrix supports the 100% bidirectional traceability requirement and references source commit `27d6c6f`.

| CICS Tran | BMS Map | COBOL Program | Function | Controller | HTTP Endpoint(s) |
| :-------- | :------ | :------------ | :------- | :--------- | :--------------- |
| `CC00` | `COSGN00` | `COSGN00C` | Sign-on | `AuthController` | `POST /api/auth/signin` |
| `CM00` | `COMEN01` | `COMEN01C` | Main Menu | `MenuController` | `GET /api/menu/main` |
| `CA00` | `COADM01` | `COADM01C` | Admin Menu | `MenuController` | `GET /api/menu/admin` |
| `CAVW` | `COACTVW` | `COACTVWC` | Account View | `AccountController` | `GET /api/accounts/{id}` |
| `CAUP` | `COACTUP` | `COACTUPC` | Account Update | `AccountController` | `PUT /api/accounts/{id}` |
| `CCLI` | `COCRDLI` | `COCRDLIC` | Card List | `CardController` | `GET /api/cards` |
| `CCDL` | `COCRDSL` | `COCRDSLC` | Card View | `CardController` | `GET /api/cards/{cardNum}` |
| `CCUP` | `COCRDUP` | `COCRDUPC` | Card Update | `CardController` | `PUT /api/cards/{cardNum}` |
| `CT00` | `COTRN00` | `COTRN00C` | Transaction List | `TransactionController` | `GET /api/transactions` |
| `CT01` | `COTRN01` | `COTRN01C` | Transaction View | `TransactionController` | `GET /api/transactions/{id}` |
| `CT02` | `COTRN02` | `COTRN02C` | Transaction Add | `TransactionController` | `POST /api/transactions` |
| `CB00` | `COBIL00` | `COBIL00C` | Bill Payment | `BillingController` | `POST /api/billing/pay` |
| `CR00` | `CORPT00` | `CORPT00C` | Transaction Reports | `ReportController` | `POST /api/reports/submit` |
| `CU00` | `COUSR00` | `COUSR00C` | List Users | `UserAdminController` | `GET /api/admin/users` |
| `CU01` | `COUSR01` | `COUSR01C` | Add User | `UserAdminController` | `POST /api/admin/users` |
| `CU02` | `COUSR02` | `COUSR02C` | Update User | `UserAdminController` | `GET /api/admin/users/{id}`, `PUT /api/admin/users/{id}` |
| `CU03` | `COUSR03` | `COUSR03C` | Delete User | `UserAdminController` | `GET /api/admin/users/{id}`, `DELETE /api/admin/users/{id}` |

**Coverage:** 17 screens · 8 controllers · 18 REST endpoints. The single-user read
`GET /api/admin/users/{id}` is shared by the Update-User and Delete-User screens (each
pre-loads the selected user before editing or confirming the delete), which is why the screen
count (17) is one fewer than the endpoint count (18). Beyond these business endpoints the only
additional routes are the Spring Boot Actuator management endpoints (e.g.
`/actuator/health`, `/actuator/prometheus`); no other business endpoint, verb, or field exists
(no feature expansion beyond the closed F-001…F-022 set).

---

## 9. Appendix B — CARDDEMO-COMMAREA Field Mapping

The conversational `CARDDEMO-COMMAREA` (`app/cpy/COCOM01Y.cpy`) is fully decomposed; **no server-side session is retained**. Each field is realized as a JWT claim, a REST routing concern, or an explicit request/response parameter.

| COMMAREA field | COBOL pic | Realized as |
| :------------- | :-------- | :---------- |
| `CDEMO-USER-ID` | `X(08)` | JWT claim `sub` / `userId` |
| `CDEMO-USER-TYPE` (`A`/`U`) | `X(01)` | JWT claim `role` (`ADMIN`/`USER`) |
| `CDEMO-PGM-CONTEXT` (`0`/`1`) | `9(01)` | not needed — REST has no pseudo-conversational ENTER/RE-ENTER state |
| `CDEMO-FROM-TRANID` / `CDEMO-TO-TRANID` | `X(04)` | REST routing (client calls the next endpoint) |
| `CDEMO-FROM-PROGRAM` / `CDEMO-TO-PROGRAM` | `X(08)` | REST routing (client calls the next endpoint) |
| `CDEMO-LAST-MAP` / `CDEMO-LAST-MAPSET` | `X(07)` | REST routing (no carry-over) |
| `CDEMO-CUST-ID` | `9(09)` | request path/body parameter / response field `customerId` |
| `CDEMO-CUST-FNAME` / `MNAME` / `LNAME` | `X(25)` | response fields (account/customer payloads) |
| `CDEMO-ACCT-ID` | `9(11)` | request path parameter / response field `accountId` |
| `CDEMO-ACCT-STATUS` | `X(01)` | response field `accountStatus` |
| `CDEMO-CARD-NUM` | `9(16)` | request path parameter / response field `cardNumber` |

---

*End of contract reference. Traced to AWS CardDemo source commit `27d6c6f`. No COBOL source is copied into this repository; all contracts are re-expressed as Java/Spring interfaces. No credentials are embedded; secrets resolve from environment variables or a secrets manager at runtime.*

