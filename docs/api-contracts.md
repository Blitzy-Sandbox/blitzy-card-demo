# CardDemo REST API & Interface Contracts

> **Document role.** This is the authoritative, human-readable **API & Interface Contract** for the
> CardDemo Java migration. It enumerates **every external REST interface** exposed by the modernized
> Spring Boot application **and** the **preserved non-REST contracts** (fixed-width file record layouts
> and the SQS FIFO messaging bridge). It is the **Explainability** deliverable that directly supports
> **Gate 5 — API/Interface Contract Verification**: each contract documented here is exercised by a local
> integration test against the real contract (no self-certification, no mocked I/O).

| Attribute | Value |
|---|---|
| Application | CardDemo (AWS mainframe credit-card demo) — Java 25 LTS + Spring Boot 3.5.11 migration |
| Base Java package | `com.carddemo` |
| Base URL (local profile) | `http://localhost:8080` |
| API root path | `/api` |
| Media type | `application/json` (JSON over HTTP) |
| Authentication | Stateless JWT (`Authorization: Bearer <token>`) |
| Legacy traceability anchor | COBOL source commit SHA **`27d6c6f`** (`CardDemo_v1.0-15-g27d6c6f-68`) |
| AWS services | **LocalStack only** — S3, SQS FIFO, SNS (zero live-AWS dependencies) |
| Supporting gate | **Gate 5** — API/Interface Contract Verification |

## Overview

The legacy system presented **17 CICS online screens** driven by BMS mapsets (`app/bms/*.bms`) and their
symbolic-map copybooks (`app/cpy-bms/*.CPY`), navigated pseudo-conversationally with state carried across
screens in the COMMAREA (`COCOM01Y`). The migrated system replaces all of that terminal interaction with a
**headless REST API** — there is **no web or browser UI**.

Key transformation principles captured by this contract:

- **17 screens → 8 REST controllers.** The online programs collapse into eight `@RestController` classes:
  `AuthController`, `MenuController`, `AccountController`, `CardController`, `TransactionController`,
  `BillingController`, `ReportController`, and `UserController`.
- **Pseudo-conversational COMMAREA → stateless JWT.** No server-side session is retained between requests.
  Cross-screen navigation state that the COMMAREA used to carry is replaced by a signed JWT presented on
  every request via the `Authorization: Bearer <token>` header.
- **Terminal AID / PF-keys → REST request parameters and resource paths.** Attention identifiers (Enter,
  PF3, PF7, PF8, …) captured by `CSSTRPFY` do **not** survive as terminal codes; they become explicit HTTP
  verbs, query parameters (e.g. `page`/`size`), and navigable resource paths.
- **Byte-accurate field contracts.** Every REST DTO field preserves the exact `PIC` length and type of its
  originating BMS symbolic-map / copybook field. Field layouts, record lengths, and delimiters are
  **preserved exactly** — this contract preservation is non-negotiable for Gate 5.
- **External (non-REST) interfaces preserved.** Fixed-width batch record layouts and the asynchronous
  report-submission bridge keep their legacy shapes; only the *transport* is modernized (VSAM → PostgreSQL,
  CICS TDQ → SQS FIFO, GDG generations → S3 objects).

> **Reference boundary.** The COBOL corpus under `app/` and the build JCL under `samples/` are **frozen
> REFERENCE** specifications anchored to commit SHA `27d6c6f`. They are never modified or copied into the
> Java target; they are cited here only to document the contract lineage.

---

## Authentication (JWT)

Authentication replaces the legacy signon screen `COSGN00` (transaction **CC00**, program `COSGN00C`),
which read the VSAM `USRSEC` file and compared a plaintext password. The modernized flow is stateless and
token-based.

### Sign-in

| Item | Value |
|---|---|
| Endpoint | `POST /api/auth/signin` |
| Legacy transaction | **CC00** (`COSGN00C`) |
| Authentication required | No (this endpoint *issues* the token) |
| Request body | `SigninRequest` |
| Success response | `200 OK` with `SigninResponse` |

**Request body — `SigninRequest`:**

```json
{
  "userId": "USER0001",
  "password": "PASSWORD"
}
```

- `userId` — required, **max length 8** (legacy `USERIDI PIC X(8)` / `SEC-USR-ID X(08)`).
- `password` — required, **max length 8** (legacy `PASSWDI PIC X(8)` / `SEC-USR-PWD X(08)`).

**Behavior.** The server looks up the user in the `users` table (the relational successor to the VSAM
`USRSEC` file, seeded from `CSUSR01Y` records) and **BCrypt-verifies** the supplied password against the
stored hash. On success it returns a **signed JWT**; on failure it returns `401 Unauthorized`. The JWT
encodes the user id and role and is the **only** state carried between requests — there is **no
server-side session**, which is the stateless replacement for the legacy pseudo-conversational COMMAREA
(`COCOM01Y`).

**Response body — `SigninResponse`:**

```json
{
  "userId": "USER0001",
  "userType": "U",
  "role": "USER",
  "token": "eyJhbGciOiJIUzI1NiJ9.<claims>.<signature>",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600
}
```

### Presenting the token

Every subsequent request must present the token in the standard bearer scheme:

```
Authorization: Bearer <token>
```

Requests without a valid, unexpired token to a protected endpoint receive `401 Unauthorized`.

### Role model and authorization

The legacy `SEC-USR-TYPE X(01)` field (`A` = administrator, `U` = standard user) maps to two Spring
Security roles:

| `SEC-USR-TYPE` | Role | Access |
|---|---|---|
| `A` | **ADMIN** | All endpoints, including the user-administration surface `/api/admin/**` |
| `U` | **USER** | Account, card, transaction, billing, menu, and report endpoints (an authenticated user) |

Authorization rules:

- `/api/admin/**` — requires role **ADMIN**.
- `/api/accounts/**`, `/api/cards/**`, `/api/transactions/**`, `/api/billing/**`, `/api/reports/**`,
  `/api/menu/**` — require an authenticated principal (role **USER** or **ADMIN**).
- `POST /api/auth/signin` — open (issues the token).

### Credential hardening (constraint C-003)

The legacy system stored passwords in **plaintext** in `USRSEC`. Constraint **C-003** explicitly permits —
and this migration requires — upgrading stored credentials to **BCrypt** hashes. This is the single
sanctioned deviation from byte-for-byte data preservation, recorded in the decision log. **No credentials
are hardcoded**; the JWT signing secret and all other secrets are supplied through environment variables or
a vault reference, never committed to source.

---

## REST Endpoint Catalog

The table below maps every legacy CICS transaction / online program to its modernized REST endpoint, the
request/response DTOs (under package `com.carddemo.dto`), and the required authorization. DTO names are the
canonical Java types the controllers bind; collection responses are returned as paginated or list wrappers
as noted in the per-endpoint sections.

| Legacy Txn ID | Legacy Program | HTTP Method + Path | Request DTO | Response DTO | Auth / Role |
|---|---|---|---|---|---|
| CC00 | `COSGN00C` | `POST /api/auth/signin` | `SigninRequest` | `SigninResponse` | Public (issues JWT) |
| CM00 | `COMEN01C` | `GET /api/menu/main` | — | `MenuDto` | USER |
| CA00 | `COADM01C` | `GET /api/menu/admin` | — | `MenuDto` | ADMIN |
| CAVW | `COACTVWC` | `GET /api/accounts/{id}` | — | `AccountDto` | USER |
| CAUP | `COACTUPC` | `PUT /api/accounts/{id}` | `AccountDto` | `AccountDto` | USER — `@Version` optimistic lock; dual-record (ACCOUNT+CUSTOMER) atomic update |
| CCLI | `COCRDLIC` | `GET /api/cards` | — | `Page<CardDto>` | USER — pagination: PF7/PF8 → `page`/`size` |
| CCDL | `COCRDSLC` | `GET /api/cards/{cardNum}` | — | `CardDto` | USER |
| CCUP | `COCRDUPC` | `PUT /api/cards/{cardNum}` | `CardDto` | `CardDto` | USER — `@Version` optimistic lock |
| CT00 | `COTRN00C` | `GET /api/transactions` | — | `Page<TransactionDto>` | USER |
| CT01 | `COTRN01C` | `GET /api/transactions/{id}` | — | `TransactionDto` | USER |
| CT02 | `COTRN02C` | `POST /api/transactions` | `TransactionDto` | `TransactionDto` | USER — auto-ID generation + confirmation |
| CB00 | `COBIL00C` | `POST /api/billing/pay` | `BillPaymentRequest` | `BillPaymentResponse` | USER |
| CR00 | `CORPT00C` | `POST /api/reports/submit` | `ReportSubmitRequest` | `ReportSubmitResponse` | USER — publishes to **SQS FIFO** (TDQ `JOBS` bridge) |
| CU00 | `COUSR00C` | `GET /api/admin/users` | — | `Page<UserDto>` | ADMIN |
| CU01 | `COUSR01C` | `POST /api/admin/users` | `UserDto` | `UserDto` | ADMIN — add user |
| CU02 | `COUSR02C` | `PUT /api/admin/users/{userId}` | `UserDto` | `UserDto` | ADMIN — update user |
| CU03 | `COUSR03C` | `DELETE /api/admin/users/{userId}` | — | `204 No Content` | ADMIN — delete user |

**Controller inventory (8 total).** The 17 online programs collapse into eight controllers:

| Controller | Endpoints owned | Legacy programs |
|---|---|---|
| `AuthController` | `POST /api/auth/signin` | `COSGN00C` |
| `MenuController` | `GET /api/menu/main`, `GET /api/menu/admin` | `COMEN01C`, `COADM01C` |
| `AccountController` | `GET/PUT /api/accounts/{id}` | `COACTVWC`, `COACTUPC` |
| `CardController` | `GET /api/cards`, `GET/PUT /api/cards/{cardNum}` | `COCRDLIC`, `COCRDSLC`, `COCRDUPC` |
| `TransactionController` | `GET /api/transactions`, `GET /api/transactions/{id}`, `POST /api/transactions` | `COTRN00C`, `COTRN01C`, `COTRN02C` |
| `BillingController` | `POST /api/billing/pay` | `COBIL00C` |
| `ReportController` | `POST /api/reports/submit` | `CORPT00C` |
| `UserController` | `/api/admin/users` CRUD | `COUSR00C`–`COUSR03C` |

> Rows annotated `@Version` use JPA optimistic locking to reproduce the legacy
> `9300-CHECK-CHANGE-IN-REC` re-read-and-compare guard; a concurrent modification surfaces as
> `409 Conflict`. The report row performs an asynchronous **SQS FIFO** publish in place of the legacy CICS
> Transient Data Queue (`JOBS`) write.

---

## Endpoint Details

Each functional group below documents purpose, method/path, parameters, request fields with validation,
response shape, the HTTP status codes returned, and the originating COBOL program and transaction id.
Common status codes used throughout: `200 OK` (success), `201 Created` (resource created), `204 No Content`
(successful delete), `400 Bad Request` (bean-validation failure), `401 Unauthorized` (missing/invalid JWT),
`403 Forbidden` (authenticated but insufficient role), `404 Not Found` (resource absent — the relational
successor to VSAM `FILE STATUS 23`), `409 Conflict` (optimistic-lock collision), and `422 Unprocessable
Entity` (well-formed request that violates a business rule).

### Auth — `AuthController`

#### `POST /api/auth/signin`

- **Purpose.** Authenticate a user and issue a JWT. Replaces signon screen `COSGN00` (**CC00**,
  `COSGN00C`).
- **Auth.** Public — this endpoint issues the token.
- **Request — `SigninRequest`.**

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `userId` | string | required, `@Size(max = 8)` | `USERIDI PIC X(8)` / `SEC-USR-ID X(08)` |
  | `password` | string | required, `@Size(max = 8)` | `PASSWDI PIC X(8)` / `SEC-USR-PWD X(08)` |

- **Response — `SigninResponse`** (`userId`, `userType`, `role`, `token`, `tokenType`, `expiresInSeconds`).
- **Status codes.** `200 OK` (valid credentials), `400 Bad Request` (missing/oversized fields),
  `401 Unauthorized` (unknown user or BCrypt mismatch).

### Menu — `MenuController`

#### `GET /api/menu/main`

- **Purpose.** Return the standard user menu. Replaces the main menu screen (**CM00**, `COMEN01C`); options
  are sourced from `COMEN02Y` (**10 options**, all type `U`).
- **Auth.** USER.
- **Response — `MenuDto`** — an ordered list of `{ optionNumber, label, targetProgram, requiredRole }`
  entries (10 entries for the main menu).
- **Status codes.** `200 OK`, `401 Unauthorized`.

#### `GET /api/menu/admin`

- **Purpose.** Return the administrator menu. Replaces the admin menu screen (**CA00**, `COADM01C`); options
  are sourced from `COADM02Y`.
- **Auth.** ADMIN.
- **Response — `MenuDto`** — ordered admin option list.
- **Status codes.** `200 OK`, `401 Unauthorized`, `403 Forbidden` (non-admin principal).

### Accounts — `AccountController`

#### `GET /api/accounts/{id}`

- **Purpose.** View a single account joined with its owning customer. Replaces account-view screen
  (**CAVW**, `COACTVWC`), which read the ACCOUNT (`CVACT01Y`, 300 B) and CUSTOMER (`CUSTREC`/`CVCUS01Y`,
  500 B) records.
- **Auth.** USER.
- **Path param.** `id` — 11-digit account id (`ACCT-ID PIC 9(11)`), e.g. `00000000001`.
- **Response — `AccountDto`** (representative fields):

  | Field | Type | Legacy source |
  |---|---|---|
  | `accountId` | string (11 digits) | `ACCT-ID 9(11)` |
  | `activeStatus` | string (1) | `ACCT-ACTIVE-STATUS X(01)` |
  | `currentBalance` | decimal (scale 2) | `ACCT-CURR-BAL S9(10)V99` |
  | `creditLimit` | decimal (scale 2) | `ACCT-CREDIT-LIMIT S9(10)V99` |
  | `cashCreditLimit` | decimal (scale 2) | `ACCT-CASH-CREDIT-LIMIT S9(10)V99` |
  | `currentCycleCredit` | decimal (scale 2) | `ACCT-CURR-CYC-CREDIT S9(10)V99` |
  | `currentCycleDebit` | decimal (scale 2) | `ACCT-CURR-CYC-DEBIT S9(10)V99` |
  | `openDate` / `expirationDate` / `reissueDate` | string (`CCYY-MM-DD`) | `X(10)` date fields |
  | `groupId` | string (10) | `ACCT-GROUP-ID X(10)` |
  | `customer` | embedded customer view | `CUSTOMER-RECORD` (500 B) |

- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found` (no such account).

#### `PUT /api/accounts/{id}`

- **Purpose.** Update an account and its owning customer. Replaces account-update screen (**CAUP**,
  `COACTUPC`, the largest legacy program at 4,236 LOC).
- **Auth.** USER.
- **Path param.** `id` — 11-digit account id.
- **Request — `AccountDto`** — the account fields above plus the embedded customer fields and a `version`
  field for optimistic locking. Monetary fields validate as `@Digits(integer = 10, fraction = 2)`; the
  `version` echoes the value last read.
- **Dual-record atomic update.** The legacy program rewrites **both** the ACCOUNT record and the CUSTOMER
  record. The Java implementation performs both updates inside a single
  `@Transactional(rollbackFor = Exception.class)` boundary, reproducing the CICS `SYNCPOINT` unit of work:
  either both rows commit or neither does.
- **Optimistic locking.** JPA `@Version` reproduces the legacy `9300-CHECK-CHANGE-IN-REC` re-read-and-compare
  guard. If the persisted version no longer matches the `version` the client read, the server responds
  **`409 Conflict`** with the byte-exact message **"Record changed by some one else. Please review"** — the
  exact outcome the COBOL paragraph produced when the record had changed underneath the user.
- **Status codes.** `200 OK`, `400 Bad Request` (validation), `401 Unauthorized`, `404 Not Found`,
  `409 Conflict` (optimistic-lock collision), `422 Unprocessable Entity` (cross-field business-rule
  violation).

### Cards — `CardController`

#### `GET /api/cards`

- **Purpose.** List cards with pagination. Replaces card-list screen (**CCLI**, `COCRDLIC`), whose browse
  paged through VSAM with PF7/PF8.
- **Auth.** USER.
- **Query params.**

  | Param | Type | Default | Legacy mapping |
  |---|---|---|---|
  | `page` | int (0-based) | `0` | **PF7** (page up) / **PF8** (page down) browse navigation |
  | `size` | int | `10` | screen page size |
  | `accountId` | string (11 digits), optional | — | filter by owning account |

- **Response — `Page<CardDto>`** — a page wrapper `{ content: [CardDto…], page, size, totalElements,
  totalPages }`.
- **Status codes.** `200 OK`, `401 Unauthorized`.

#### `GET /api/cards/{cardNum}`

- **Purpose.** Card detail. Replaces card-detail screen (**CCDL**, `COCRDSLC`); reads CARD (`CVACT02Y`,
  150 B).
- **Auth.** USER.
- **Path param.** `cardNum` — 16-character card number (`CARD-NUM X(16)`).
- **Response — `CardDto`.**

  | Field | Type | Legacy source |
  |---|---|---|
  | `cardNumber` | string (16) | `CARD-NUM X(16)` |
  | `accountId` | string (11 digits) | `CARD-ACCT-ID 9(11)` |
  | `cvv` | string (3 digits) | `CARD-CVV-CD 9(03)` |
  | `embossedName` | string (50) | `CARD-EMBOSSED-NAME X(50)` |
  | `expirationDate` | string (`CCYY-MM-DD`) | `CARD-EXPIRAION-DATE X(10)` |
  | `activeStatus` | string (1) | `CARD-ACTIVE-STATUS X(01)` |

- **Expiry segments and version token.** Alongside the assembled `expirationDate`, the detail surfaces the
  expiry as discrete `expiryMonth` / `expiryYear` / **`expiryDay`** segments (the BMS `EXPMON` / `EXPYEAR` /
  `EXPDAY` fields) plus the `version` optimistic-lock token, so a client can read every field the
  card-update contract requires and echo it back (a stateless read-modify-write); the legacy `COCRDUPC`
  pre-filled the day from the held VSAM image, which the REST surface exposes on read instead.
- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found`.

#### `PUT /api/cards/{cardNum}`

- **Purpose.** Update a card. Replaces card-update screen (**CCUP**, `COCRDUPC`, 1,560 LOC).
- **Auth.** USER.
- **Path param.** `cardNum` — 16-character card number.
- **Request — `CardDto`** — the card fields above plus a `version` field.
- **Optimistic locking.** As with account update, `@Version` reproduces `9300-CHECK-CHANGE-IN-REC`; a stale
  version yields **`409 Conflict`** ("Record changed by some one else. Please review").
- **Status codes.** `200 OK`, `400 Bad Request`, `401 Unauthorized`, `404 Not Found`, `409 Conflict`.

### Transactions — `TransactionController`

#### `GET /api/transactions`

- **Purpose.** List transactions. Replaces transaction-list screen (**CT00**, `COTRN00C`).
- **Auth.** USER.
- **Query params.** `page` (0-based, default `0`; the page size is fixed at the ten-row `COTRN00`
  display array inside the service) and an optional `transactionId` filter (`TRNIDIN`). A blank
  `transactionId` browses from the start; a numeric value positions the browse at the first
  transaction id greater than or equal to it (`MOVE TRNIDINI TO TRAN-ID`, `STARTBR … GTEQ`); a
  non-numeric value is rejected with `400 Bad Request` and the message `Tran ID must be Numeric ...`
  (`COTRN00C` line 214).
- **Response — `Page<TransactionDto>`.**
- **Status codes.** `200 OK`, `400 Bad Request` (non-numeric `transactionId`), `401 Unauthorized`.

#### `GET /api/transactions/{id}`

- **Purpose.** Transaction detail. Replaces transaction-detail screen (**CT01**, `COTRN01C`); reads TRAN
  (`CVTRA05Y`, 350 B).
- **Auth.** USER.
- **Path param.** `id` — 16-character transaction id (`TRAN-ID X(16)`).
- **Response — `TransactionDto`.**

  | Field | Type | Legacy source |
  |---|---|---|
  | `transactionId` | string (16) | `TRAN-ID X(16)` |
  | `typeCode` | string (2) | `TRAN-TYPE-CD X(02)` |
  | `categoryCode` | string (4 digits) | `TRAN-CAT-CD 9(04)` |
  | `source` | string (10) | `TRAN-SOURCE X(10)` |
  | `description` | string (100) | `TRAN-DESC X(100)` |
  | `amount` | decimal (scale 2) | `TRAN-AMT S9(09)V99` |
  | `merchantId` | string (9 digits) | `TRAN-MERCHANT-ID 9(09)` |
  | `merchantName` | string (50) | `TRAN-MERCHANT-NAME X(50)` |
  | `merchantCity` | string (50) | `TRAN-MERCHANT-CITY X(50)` |
  | `merchantZip` | string (10) | `TRAN-MERCHANT-ZIP X(10)` |
  | `cardNumber` | string (16) | `TRAN-CARD-NUM X(16)` |
  | `originTimestamp` | string (26, `YYYY-MM-DD HH:MM:SS.mmmmmm`) | `TRAN-ORIG-TS X(26)` |
  | `processTimestamp` | string (26, `YYYY-MM-DD HH:MM:SS.mmmmmm`) | `TRAN-PROC-TS X(26)` |

- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found`.

#### `POST /api/transactions`

- **Purpose.** Add a transaction. Replaces transaction-add screen (**CT02**, `COTRN02C`, 1,300 LOC).
- **Auth.** USER.
- **Request — `TransactionDto`** (input fields from the `COTRN02` symbolic map):

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `accountId` | string | required, 11 digits | `ACTIDINI X(11)` |
  | `cardNumber` | string | required, length 16 | `CARDNINI X(16)` |
  | `typeCode` | string | required, length 2 | `TTYPCDI X(2)` → `TRAN-TYPE-CD X(02)` |
  | `categoryCode` | string | required, 4 digits | `TCATCDI X(4)` → `TRAN-CAT-CD 9(04)` |
  | `description` | string | `@Size(max = 100)` (screen entry capped at 60) | `TDESCI X(60)` → `TRAN-DESC X(100)` |
  | `amount` | decimal | required, `@Digits(integer = 9, fraction = 2)` | `TRNAMTI` (formatted) → `TRAN-AMT S9(09)V99` |

- **Auto-ID + confirmation.** The transaction id is **server-generated** (the modern successor to the legacy
  auto-numbering), and the response echoes the created resource as confirmation.
- **Response — `TransactionDto`** (the persisted transaction, including the generated `transactionId`).
- **Status codes.** `201 Created`, `400 Bad Request` (validation), `401 Unauthorized`, `404 Not Found`
  (unknown account/card), `422 Unprocessable Entity` (business-rule violation, e.g. invalid type/category).

### Billing — `BillingController`

#### `POST /api/billing/pay`

- **Purpose.** Pay the full current balance for an account. Replaces bill-pay screen (**CB00**,
  `COBIL00C`).
- **Auth.** USER.
- **Request — `BillPaymentRequest`** (input fields from the `COBIL00` symbolic map):

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `accountId` | string | required, 11 digits | `ACTIDINI X(11)` |
  | `confirm` | string | required, `Y`/`N` | `CONFIRMI X(1)` |

- **Behavior.** When `confirm = "Y"`, the service posts a payment transaction for the account's full current
  balance and updates the balance atomically (`@Transactional`). When `confirm = "N"` (or absent), the
  endpoint returns the amount that *would* be paid without applying it.
- **Response — `BillPaymentResponse`** — `{ accountId, amountPaid (scale 2), newBalance (scale 2),
  transactionId, confirmed }`.
- **Status codes.** `200 OK`, `400 Bad Request`, `401 Unauthorized`, `404 Not Found`.

### Reports — `ReportController`

#### `POST /api/reports/submit`

- **Purpose.** Submit an asynchronous transaction report. Replaces report screen (**CR00**, `CORPT00C`),
  which wrote a JCL stream to the CICS Transient Data Queue `JOBS` to trigger a batch report.
- **Auth.** USER.
- **Request — `ReportSubmitRequest`.**

  | Field | Type | Validation | Notes |
  |---|---|---|---|
  | `reportType` | string | required (`MONTHLY` / `YEARLY` / `CUSTOM`) | report selection from the screen |
  | `startDate` | string (`CCYY-MM-DD`) | required for `CUSTOM` | date-window start |
  | `endDate` | string (`CCYY-MM-DD`) | required for `CUSTOM` | date-window end |

- **Asynchronous bridge (F-011).** The endpoint **publishes a report-request message to the SQS FIFO queue
  `carddemo-report-jobs.fifo`** (with a `MessageGroupId` and `MessageDeduplicationId`) and returns
  immediately. A consumer then triggers the Spring Batch transaction-report job. This preserves the legacy
  TDQ `JOBS` **submit-then-process** semantics: the request is accepted asynchronously and the report is
  produced out of band.
- **Response — `ReportSubmitResponse`** — `{ requestId, status: "SUBMITTED", queue:
  "carddemo-report-jobs.fifo" }`.
- **Status codes.** `202 Accepted` (queued), `400 Bad Request`, `401 Unauthorized`.

### Admin Users — `UserController`

User administration replaces the four legacy user screens (**CU00**–**CU03**, programs
`COUSR00C`–`COUSR03C`) and is restricted to **ADMIN**. Records derive from `CSUSR01Y` (SEC-USER-DATA, 80 B).

#### `GET /api/admin/users`

- **Purpose.** List users (**CU00**, `COUSR00C`). **Auth.** ADMIN.
- **Query params.** `page` (default `0`), `size` (default `10`).
- **Response — `Page<UserDto>`.** **Status codes.** `200 OK`, `401`, `403`.

#### `POST /api/admin/users`

- **Purpose.** Add a user (**CU01**, `COUSR01C`). **Auth.** ADMIN.
- **Request — `UserDto`.**

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `userId` | string | required, `@Size(max = 8)` | `SEC-USR-ID X(08)` |
  | `firstName` | string | required, `@Size(max = 20)` | `SEC-USR-FNAME X(20)` |
  | `lastName` | string | required, `@Size(max = 20)` | `SEC-USR-LNAME X(20)` |
  | `password` | string | required, `@Size(max = 8)` (stored BCrypt-hashed) | `SEC-USR-PWD X(08)` |
  | `userType` | string | required, `A` or `U` | `SEC-USR-TYPE X(01)` |

- **Response — `UserDto`** (without the password hash). **Status codes.** `201 Created`, `400`, `401`,
  `403`, `409 Conflict` (duplicate `userId` — the relational successor to VSAM `FILE STATUS 22`).

#### `PUT /api/admin/users/{userId}`

- **Purpose.** Update a user (**CU02**, `COUSR02C`). **Auth.** ADMIN.
- **Path param.** `userId` (8). **Request — `UserDto`** (same fields; password optional — re-hashed if
  supplied). **Status codes.** `200 OK`, `400`, `401`, `403`, `404 Not Found`.

#### `DELETE /api/admin/users/{userId}`

- **Purpose.** Delete a user (**CU03**, `COUSR03C`). **Auth.** ADMIN.
- **Path param.** `userId` (8). **Response.** `204 No Content`. **Status codes.** `204`, `401`, `403`,
  `404 Not Found`.

---

## Field Validation Contracts

Every DTO field preserves the exact length and type of its originating BMS symbolic-map field or copybook
`PIC` clause. The legacy fixed-width `PIC` definitions become **Jakarta Bean Validation** constraints
(`jakarta.validation.constraints.*`). Monetary `COMP-3` / `PIC S9(n)V99` fields map to
`java.math.BigDecimal` with **scale 2** and `RoundingMode.HALF_EVEN` — `double`/`float` are categorically
prohibited.

| Representative field | Legacy PIC source | Java type | Bean-validation constraint |
|---|---|---|---|
| Sign-in `userId` | `USERIDI PIC X(8)` / `SEC-USR-ID X(08)` | `String` | `@NotBlank @Size(max = 8)` |
| Sign-in `password` | `PASSWDI PIC X(8)` / `SEC-USR-PWD X(08)` | `String` | `@NotBlank @Size(max = 8)` |
| `accountId` | `ACCT-ID PIC 9(11)` | `String` | `@NotBlank @Pattern(regexp = "\\d{11}")` |
| Monetary (balance, limits, amount) | `PIC S9(10)V99` (account) / `PIC S9(09)V99` (transaction) | `BigDecimal` (scale 2) | `@Digits(integer = 10, fraction = 2)` / `@Digits(integer = 9, fraction = 2)` |
| `cardNumber` | `CARD-NUM X(16)` / `TRAN-CARD-NUM X(16)` | `String` | `@NotBlank @Size(min = 16, max = 16)` |
| `cvv` | `CARD-CVV-CD 9(03)` | `String` | `@Pattern(regexp = "\\d{3}")` |
| `embossedName` | `CARD-EMBOSSED-NAME X(50)` | `String` | `@Size(max = 50)` |
| `typeCode` | `TRAN-TYPE-CD X(02)` | `String` | `@Size(min = 2, max = 2)` |
| `categoryCode` | `TRAN-CAT-CD 9(04)` | `String` | `@Pattern(regexp = "\\d{4}")` |
| `description` | `TRAN-DESC X(100)` | `String` | `@Size(max = 100)` |
| `merchantId` | `TRAN-MERCHANT-ID 9(09)` | `String` | `@Pattern(regexp = "\\d{9}")` |
| Timestamps (`originTimestamp`, `processTimestamp`) | `TRAN-ORIG-TS` / `TRAN-PROC-TS X(26)` | `String` | format `YYYY-MM-DD HH:MM:SS.mmmmmm` (26 chars) |
| Date fields (`openDate`, `expirationDate`, …) | `X(10)` | `String` | format `CCYY-MM-DD` (10 chars) |
| User `firstName` / `lastName` | `SEC-USR-FNAME X(20)` / `SEC-USR-LNAME X(20)` | `String` | `@Size(max = 20)` |
| User `userType` | `SEC-USR-TYPE X(01)` | `String` | `@Pattern(regexp = "[AU]")` |

> **Transaction type codes** (`TRAN-TYPE-CD`, from `trantype.txt`) are a closed set: `01` Purchase,
> `02` Payment, `03` Credit, `04` Authorization, `05` Refund, `06` Reversal, `07` Adjustment. Submissions
> outside this set are rejected with `422 Unprocessable Entity`.

### Invalid-field highlighting → structured error responses

On the legacy screens, `CSSETATY` highlighted invalid fields in red (`DFHRED`) and marked blank-but-required
fields with an asterisk. In the REST API this presentation concern becomes a **structured error response**:
a validation failure returns **`400 Bad Request`** with a consistent error body that names each offending
field and its message. The body is produced centrally by the `@ControllerAdvice` `GlobalExceptionHandler`,
so every endpoint returns the same error shape.

```json
{
  "timestamp": "2025-05-20T14:32:10.123456",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/transactions",
  "fieldErrors": [
    { "field": "amount", "message": "numeric value out of bounds (<9 digits>.<2 digits> expected)" },
    { "field": "cardNumber", "message": "size must be 16" }
  ]
}
```

The same handler maps the typed exception hierarchy (the seven custom exceptions derived from VSAM
`FILE STATUS` codes) to HTTP status: not-found → `404`, duplicate-key → `409`, optimistic-lock collision →
`409`, and business-rule violations → `422`.

---

## PF-Key → REST Mapping

The legacy screens captured terminal attention identifiers (AIDs) — Enter and the PF (program-function)
keys — and stored them via `CSSTRPFY`'s `EVALUATE` over `EIBAID`. **No terminal AID code survives the
migration.** Each meaningful key becomes an explicit HTTP verb, query parameter, or client-side navigation
decision; there is no server-side notion of "which key was pressed."

| Legacy key | `CSSTRPFY` AID | Legacy meaning | REST equivalent |
|---|---|---|---|
| **Enter** | `DFHENTER` | Submit / confirm the current screen | The HTTP request itself — `POST`/`PUT`/`GET` to the resource (e.g. submit `POST /api/transactions`) |
| **PF3** | `DFHPF3` | Exit / go back to the previous screen | **Client-side navigation** — the client requests the parent/previous resource path; no server state is retained |
| **PF7** | `DFHPF7` | Page up (previous page of a browse) | `GET …?page={n-1}&size={s}` on a list endpoint |
| **PF8** | `DFHPF8` | Page down (next page of a browse) | `GET …?page={n+1}&size={s}` on a list endpoint |

Notes:

- **Pagination.** PF7/PF8 browse navigation is realized by the `page` (0-based) and `size` query parameters
  on the list endpoints `GET /api/cards`, `GET /api/transactions`, and `GET /api/admin/users`. Paging up at
  `page = 0` simply returns the first page; paging down past the last page returns an empty `content` array.
- **Back / exit.** PF3 has no server effect because the API is stateless — "going back" is the client
  choosing to call a different (e.g. parent) resource path. There is no conversational stack to pop.
- **Other PF keys.** Screen-specific function keys that had no data-bearing effect are not exposed as API
  surface; their behavior is subsumed by the REST verb + resource-path model.

---

## Preserved File & Messaging Contracts

Gate 5 covers **every external interface**, not just the REST surface. The batch file layouts and the
asynchronous report bridge are **external interface contracts** and are **preserved exactly** — field
layouts, record lengths, and delimiters are unchanged, with **no field-boundary shifts**. Only the storage
and transport mechanics are modernized (VSAM KSDS → PostgreSQL, CICS TDQ → SQS FIFO, GDG generations → S3
objects).

### Fixed-width record lengths (preserved exactly)

| Record | Copybook (REFERENCE @ `27d6c6f`) | Length (bytes) |
|---|---|---|
| ACCOUNT | `CVACT01Y` | **300** |
| CARD | `CVACT02Y` | **150** |
| CARD-XREF | `CVACT03Y` | **50** |
| CUSTOMER | `CUSTREC` / `CVCUS01Y` | **500** |
| TRANSACTION | `CVTRA05Y` | **350** |
| SEC-USER | `CSUSR01Y` | **80** |
| Transaction report (F-018) | report layout | **430** |
| Statement (F-021) | statement layout | **80 / 100** |
| Report line (F-022) | report layout | **133** |

These widths are honored by the fixed-width readers/writers in the Spring Batch pipeline. Parsing uses exact
column offsets and lengths; no trimming or re-packing that would shift a field boundary is permitted.
Timestamp and date formats (e.g. the `YYYY-MM-DD HH:MM:SS.mmmmmm` 26-character transaction timestamp) are
emitted byte-for-byte to satisfy the interface-contract preservation requirement.

### SQS FIFO report-submission contract

The report-submission bridge (legacy `CORPT00C` → CICS TDQ `JOBS`) is realized as an **SQS FIFO** message.

| Attribute | Value |
|---|---|
| Queue name | **`carddemo-report-jobs.fifo`** |
| Message body | JSON report request |
| `MessageGroupId` | required (FIFO ordering group, e.g. `report-jobs`) |
| `MessageDeduplicationId` | required (FIFO dedupe key, e.g. the request id) |
| Producer | `ReportController` / `ReportService` (`POST /api/reports/submit`) |
| Consumer | SQS listener that triggers the Spring Batch transaction-report job |

**Message body schema:**

```json
{
  "requestId": "b1c2d3e4-0000-4a5b-8c6d-000000000001",
  "reportType": "CUSTOM",
  "startDate": "2025-01-01",
  "endDate": "2025-01-31",
  "requestedBy": "USER0001"
}
```

The `.fifo` suffix and the required `MessageGroupId` + `MessageDeduplicationId` attributes are mandatory for
an Amazon SQS FIFO queue; they guarantee ordered, exactly-once delivery that mirrors the legacy
submit-then-process sequencing of the TDQ.

### S3 object storage

GDG generation datasets become versioned **S3** objects. Three buckets separate the batch pipeline stages:

| Bucket | Role |
|---|---|
| `carddemo-batch-input` | Inbound batch input files (e.g. daily transactions) |
| `carddemo-batch-output` | Generated reports and rejects |
| `carddemo-statements` | Generated customer statements (text + HTML) |

### Local verification (no live AWS)

All of the above are **verified locally against LocalStack** with **zero live-AWS dependencies**. Integration
tests provision their own S3 buckets and SQS FIFO queue (via Testcontainers LocalStack or the
`localstack-init/init-aws.sh` provisioning hook), exercise the real contract end-to-end, and clean up after
themselves. Per Gate 5, **mocked I/O does not satisfy the contract** — the buckets, queue, and fixed-width
files must be exercised for real.

---

## Examples

All examples target the local profile (`http://localhost:8080`) and use the seed users **`USER0001`**
(role USER) and **`ADMIN001`** (role ADMIN), both with the initial password **`PASSWORD`** (stored
BCrypt-hashed in the V3 seed), and the sample account **`00000000001`**. Monetary values carry exactly two
decimal places.

### (a) Sign in and obtain a JWT

```bash
curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}'
```

Response (`200 OK`):

```json
{
  "userId": "USER0001",
  "userType": "U",
  "role": "USER",
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJVU0VSMDAwMSIsInJvbGUiOiJVU0VSIn0.s1gn4tur3",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600
}
```

### (b) Authenticated account view

```bash
curl -s http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJVU0VSMDAwMSIsInJvbGUiOiJVU0VSIn0.s1gn4tur3"
```

Response (`200 OK`) — an `AccountDto` with the ACCOUNT + CUSTOMER join:

```json
{
  "accountId": "00000000001",
  "activeStatus": "Y",
  "currentBalance": "1019.40",
  "creditLimit": "20200.00",
  "cashCreditLimit": "10200.00",
  "currentCycleCredit": "0.00",
  "currentCycleDebit": "0.00",
  "openDate": "2014-11-20",
  "expirationDate": "2025-05-20",
  "reissueDate": "2025-05-20",
  "groupId": "A000000000",
  "version": 0,
  "customer": {
    "customerId": "000000001",
    "firstName": "JOHN",
    "lastName": "DOE",
    "addressStateCode": "TX",
    "addressZip": "75001",
    "ficoCreditScore": 750
  }
}
```

### (c) Add a transaction

```bash
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
        "accountId": "00000000001",
        "cardNumber": "4111111111111111",
        "typeCode": "01",
        "categoryCode": "0001",
        "description": "GROCERY STORE PURCHASE",
        "amount": "125.50"
      }'
```

Response (`201 Created`) — the persisted `TransactionDto` with the server-generated id as confirmation:

```json
{
  "transactionId": "0000000000000017",
  "typeCode": "01",
  "categoryCode": "0001",
  "source": "POS",
  "description": "GROCERY STORE PURCHASE",
  "amount": "125.50",
  "merchantId": "000000123",
  "merchantName": "ACME GROCERY",
  "merchantCity": "DALLAS",
  "merchantZip": "75001",
  "cardNumber": "4111111111111111",
  "originTimestamp": "2025-05-20 14:32:10.123456",
  "processTimestamp": "2025-05-20 14:32:10.654321"
}
```

### (d) Submit a report (asynchronous SQS FIFO bridge)

```bash
curl -s -X POST http://localhost:8080/api/reports/submit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
        "reportType": "CUSTOM",
        "startDate": "2025-01-01",
        "endDate": "2025-01-31"
      }'
```

Response (`202 Accepted`) — the request is queued to `carddemo-report-jobs.fifo`:

```json
{
  "requestId": "b1c2d3e4-0000-4a5b-8c6d-000000000001",
  "status": "SUBMITTED",
  "queue": "carddemo-report-jobs.fifo"
}
```

### Optimistic-lock conflict (account update)

A `PUT /api/accounts/00000000001` carrying a stale `version` returns `409 Conflict`:

```json
{
  "timestamp": "2025-05-20T14:40:02.001000",
  "status": 409,
  "error": "Conflict",
  "message": "Record changed by some one else. Please review",
  "path": "/api/accounts/00000000001"
}
```

---

## Related Documentation

| Document | Location | Purpose |
|---|---|---|
| Before/After Architecture | [`./architecture-before-after.md`](./architecture-before-after.md) | Mermaid before/after views of the mainframe → Spring migration |
| Validation Gates | [`./validation-gates.md`](./validation-gates.md) | Evidence for Gates 1–8, including the Gate 5 contract tests that verify this document |
| Onboarding Guide | [`./onboarding-guide.md`](./onboarding-guide.md) | Clean-machine → running application, domain context, and next tasks |
| Technical Specifications | [`./technical-specifications.md`](./technical-specifications.md) | Authoritative migration blueprint (architecture, scope, constraints, components) |
| Traceability Matrix | [`../TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | Bidirectional COBOL paragraph → Java method mapping (100% coverage), at repository root |

> Legacy artifacts referenced throughout this document (`app/cbl/*`, `app/cpy/*`, `app/cpy-bms/*`,
> `app/bms/*`, `app/data/ASCII/*`) are frozen REFERENCE specifications anchored to commit SHA `27d6c6f`; the
> traceability matrix records the full paragraph-to-method correspondence.
