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
encodes the user id and the `userType` flag and is the **only** state carried between requests — there is
**no server-side session**, which is the stateless replacement for the legacy pseudo-conversational
COMMAREA (`COCOM01Y`).

**Response body — `SigninResponse`** (exactly three fields — `token`, `userId`, `userType`):

```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9.<claims>.<signature>",
  "userId": "USER0001",
  "userType": "U"
}
```

The `userType` flag (`U` for a standard user, `A` for an administrator) is also encoded as a claim inside
the signed JWT; role authorization is derived from it server-side. The body carries no `role`, `tokenType`,
or `expiresInSeconds` field.

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
| CM00 | `COMEN01C` | `GET /api/menu/main` | — | `MenuDto.MenuResponse` | USER |
| CA00 | `COADM01C` | `GET /api/menu/admin` | — | `MenuDto.MenuResponse` | ADMIN |
| CAVW | `COACTVWC` | `GET /api/accounts/{id}` | — | `AccountDto.ViewResponse` | USER |
| CAUP | `COACTUPC` | `PUT /api/accounts/{id}` | `AccountDto.UpdateRequest` | `AccountDto.ViewResponse` | USER — `@Version` optimistic lock; dual-record (ACCOUNT+CUSTOMER) atomic update |
| CCLI | `COCRDLIC` | `GET /api/cards` | — | `CardDto.ListResponse` | USER — pagination: PF7/PF8 → `page` (no `size`) |
| CCDL | `COCRDSLC` | `GET /api/cards/{cardNum}` | — | `CardDto.Detail` | USER |
| CCUP | `COCRDUPC` | `PUT /api/cards/{cardNum}` | `CardDto.UpdateRequest` | `CardDto.Detail` | USER — `@Version` optimistic lock |
| CT00 | `COTRN00C` | `GET /api/transactions` | — | `TransactionDto.ListResponse` | USER |
| CT01 | `COTRN01C` | `GET /api/transactions/{id}` | — | `TransactionDto.Detail` | USER |
| CT02 | `COTRN02C` | `POST /api/transactions` | `TransactionDto.AddRequest` | `TransactionDto.Detail` | USER — auto-ID generation + confirm flow |
| CB00 | `COBIL00C` | `POST /api/billing/pay` | `BillingDto.PayRequest` | `BillingDto.PayResponse` | USER |
| CR00 | `CORPT00C` | `POST /api/reports/submit` | `ReportDto.SubmitRequest` | `ResponseEntity<Void>` (202, empty body) | USER — publishes to **SQS FIFO** (TDQ `JOBS` bridge) |
| CU00 | `COUSR00C` | `GET /api/admin/users` | — | `UserDto.ListResponse` | ADMIN |
| CU01 | `COUSR01C` | `POST /api/admin/users` | `UserDto.CreateRequest` | `UserDto.UserSummary` | ADMIN — add user |
| CU02 | `COUSR02C` | `PUT /api/admin/users/{userId}` | `UserDto.UpdateRequest` | `UserDto.UserSummary` | ADMIN — update user |
| CU03 | `COUSR03C` | `DELETE /api/admin/users/{userId}` | — | `UserDto.DeleteResponse` (200) | ADMIN — delete user |

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
Common status codes used throughout: `200 OK` (success, including the user-delete endpoint, which returns
the deleted record rather than `204`), `201 Created` (resource created), `202 Accepted` (report submission
queued, empty body), `400 Bad Request` (bean-validation failure), `401 Unauthorized` (missing/invalid JWT),
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

- **Response — `SigninResponse`** (`token`, `userId`, `userType`) — exactly three fields. `userType` is the
  raw legacy flag (`U` for a standard user, `A` for an administrator); role derivation happens server-side
  from this flag. The signed JWT itself carries the `userType` claim, so no separate `role`, `tokenType`,
  or expiry field is returned in the body.
- **Status codes.** `200 OK` (valid credentials), `400 Bad Request` (missing/oversized fields),
  `401 Unauthorized` (unknown user or BCrypt mismatch).

### Menu — `MenuController`

#### `GET /api/menu/main`

- **Purpose.** Return the standard user menu. Replaces the main menu screen (**CM00**, `COMEN01C`); options
  are sourced from `COMEN02Y` (**10 options**, all type `U`).
- **Auth.** USER.
- **Response — `MenuDto.MenuResponse`** — an object `{ title, options }` where `title` is the menu name
  (`"Main Menu"`) and `options` is an ordered array of `{ optionNumber, optionName }` entries (10 entries
  for the main menu). The option label is `optionName`; the response carries no `targetProgram` or
  `requiredRole` field (menu filtering by role is applied server-side before the list is returned).
- **Status codes.** `200 OK`, `401 Unauthorized`.

#### `GET /api/menu/admin`

- **Purpose.** Return the administrator menu. Replaces the admin menu screen (**CA00**, `COADM01C`); options
  are sourced from `COADM02Y`.
- **Auth.** ADMIN.
- **Response — `MenuDto.MenuResponse`** — the same `{ title, options:[{ optionNumber, optionName }] }`
  shape as the main menu, carrying the administrator option set.
- **Status codes.** `200 OK`, `401 Unauthorized`, `403 Forbidden` (non-admin principal).

### Accounts — `AccountController`

#### `GET /api/accounts/{id}`

- **Purpose.** View a single account joined with its owning customer. Replaces account-view screen
  (**CAVW**, `COACTVWC`), which read the ACCOUNT (`CVACT01Y`, 300 B) and CUSTOMER (`CUSTREC`/`CVCUS01Y`,
  500 B) records.
- **Auth.** USER.
- **Path param.** `id` — 11-digit account id (`ACCT-ID PIC 9(11)`), e.g. `00000000001`.
- **Response — `AccountDto.ViewResponse`** — a **flat** object with **30 top-level fields** (the ACCOUNT and
  CUSTOMER records are merged into a single flat DTO; there is **no** nested `customer` object). The fields,
  in serialization order, are:

  | Field | Type | Legacy source |
  |---|---|---|
  | `accountId` | string | `ACCT-ID 9(11)` |
  | `accountStatus` | string (1) | `ACCT-ACTIVE-STATUS X(01)` |
  | `openDate` | string (`CCYY-MM-DD`) | `ACCT-OPEN-DATE X(10)` |
  | `creditLimit` | number (2 dp) | `ACCT-CREDIT-LIMIT S9(10)V99` |
  | `expirationDate` | string (`CCYY-MM-DD`) | `ACCT-EXPIRAION-DATE X(10)` |
  | `cashCreditLimit` | number (2 dp) | `ACCT-CASH-CREDIT-LIMIT S9(10)V99` |
  | `reissueDate` | string (`CCYY-MM-DD`) | `ACCT-REISSUE-DATE X(10)` |
  | `currentBalance` | number (2 dp) | `ACCT-CURR-BAL S9(10)V99` |
  | `currentCycleCredit` | number (2 dp) | `ACCT-CURR-CYC-CREDIT S9(10)V99` |
  | `accountGroupId` | string (10) | `ACCT-GROUP-ID X(10)` |
  | `currentCycleDebit` | number (2 dp) | `ACCT-CURR-CYC-DEBIT S9(10)V99` |
  | `customerId` | string | `CUST-ID 9(09)` |
  | `ssn` | string (9) | `CUST-SSN 9(09)` |
  | `dateOfBirth` | string (`CCYY-MM-DD`) | `CUST-DOB-YYYY-MM-DD X(10)` |
  | `ficoScore` | string | `CUST-FICO-CREDIT-SCORE 9(03)` |
  | `firstName` | string (25) | `CUST-FIRST-NAME X(25)` |
  | `middleName` | string (25) | `CUST-MIDDLE-NAME X(25)` |
  | `lastName` | string (25) | `CUST-LAST-NAME X(25)` |
  | `addressLine1` | string (50) | `CUST-ADDR-LINE-1 X(50)` |
  | `state` | string (2) | `CUST-ADDR-STATE-CD X(02)` |
  | `addressLine2` | string (50) | `CUST-ADDR-LINE-2 X(50)` |
  | `zipCode` | string (5) | `CUST-ADDR-ZIP X(10)` → `ACSZIPCO X(5)` (view truncates to the first 5 chars per `COACTVWC.cbl:515`; see DECISION_LOG D-074) |
  | `city` | string (50) | `CUST-ADDR-LINE-3` (city portion) |
  | `country` | string (3) | `CUST-ADDR-COUNTRY-CD X(03)` |
  | `phone1` | string | `CUST-PHONE-NUM-1 X(15)` |
  | `governmentId` | string | `CUST-GOVT-ISSUED-ID X(20)` |
  | `phone2` | string | `CUST-PHONE-NUM-2 X(15)` |
  | `eftAccountId` | string | `CUST-EFT-ACCOUNT-ID X(10)` |
  | `primaryCardHolder` | string (1) | `CUST-PRI-CARD-HOLDER-IND X(01)` |
  | `version` | number | JPA `@Version` (optimistic-lock token) |

- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found` (no such account).

#### `PUT /api/accounts/{id}`

- **Purpose.** Update an account and its owning customer. Replaces account-update screen (**CAUP**,
  `COACTUPC`, the largest legacy program at 4,236 LOC).
- **Auth.** USER.
- **Path param.** `id` — 11-digit account id.
- **Request — `AccountDto.UpdateRequest`** — a **flat** body carrying the account and customer fields
  (the same flat field set as the view response, **not** a nested `customer` object) plus a `version` field
  for optimistic locking. Monetary fields validate as `@Digits(integer = 10, fraction = 2)`; the `version`
  echoes the value last read.
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
  | `accountId` | long (11 digits), optional | — | filter by owning account |
  | `cardNumber` | string (16 digits), optional | — | filter by card number |

  There is **no** `size` parameter — the page size is fixed at the screen-equivalent page (7 rows),
  matching the legacy `COCRDLIC` browse window.

- **Response — `CardDto.ListResponse`** — a **custom** wrapper (not a Spring `Page<>`):
  `{ pageNumber, accountIdFilter, cardNumberFilter, cards }`, where `cards` is an array of
  `CardSummary` objects `{ accountId, cardNumber, cardStatus }`. The wrapper carries the current
  `pageNumber` and echoes the active filters; it does **not** expose `content`, `size`, `totalElements`,
  or `totalPages`.
- **Status codes.** `200 OK`, `401 Unauthorized`.

#### `GET /api/cards/{cardNum}`

- **Purpose.** Card detail. Replaces card-detail screen (**CCDL**, `COCRDSLC`); reads CARD (`CVACT02Y`,
  150 B).
- **Auth.** USER.
- **Path param.** `cardNum` — 16-character card number (`CARD-NUM X(16)`).
- **Response — `CardDto.Detail`** (8 fields):

  | Field | Type | Legacy source |
  |---|---|---|
  | `accountId` | string (11 digits) | `CARD-ACCT-ID 9(11)` |
  | `cardNumber` | string (16) | `CARD-NUM X(16)` |
  | `cardholderName` | string (50) | `CARD-EMBOSSED-NAME X(50)` |
  | `cardStatus` | string (1) | `CARD-ACTIVE-STATUS X(01)` |
  | `expiryMonth` | string (2) | `CARD-EXPIRAION-DATE` (month segment) |
  | `expiryYear` | string (4) | `CARD-EXPIRAION-DATE` (year segment) |
  | `expiryDay` | string (2) | `CARD-EXPIRAION-DATE` (day segment) |
  | `version` | number | JPA `@Version` (optimistic-lock token) |

- **Expiry segments and version token.** The detail surfaces the expiry as discrete
  `expiryMonth` / `expiryYear` / **`expiryDay`** segments (the BMS `EXPMON` / `EXPYEAR` / `EXPDAY` fields)
  plus the `version` optimistic-lock token, so a client can read every field the card-update contract
  requires and echo it back (a stateless read-modify-write); the legacy `COCRDUPC` pre-filled the day from
  the held VSAM image, which the REST surface exposes on read instead. There is **no** `cvv`,
  `embossedName`, `expirationDate`, or `activeStatus` field on the response — the cardholder name is
  `cardholderName` and the status is `cardStatus`.
- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found`.

#### `PUT /api/cards/{cardNum}`

- **Purpose.** Update a card. Replaces card-update screen (**CCUP**, `COCRDUPC`, 1,560 LOC).
- **Auth.** USER.
- **Path param.** `cardNum` — 16-character card number.
- **Request — `CardDto.UpdateRequest`** — the card fields above (`cardholderName`, `cardStatus`, and the
  `expiryMonth` / `expiryYear` / `expiryDay` segments) plus the `version` field echoed from the read.
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
- **Response — `TransactionDto.ListResponse`** — a **custom** wrapper (not a Spring `Page<>`):
  `{ pageNumber, transactionIdFilter, transactions }`, where `transactions` is an array of
  `TransactionSummary` objects `{ transactionId, date, description, amount }` (the `date` is the
  `MM/DD/YY` display form of the origin date). The wrapper exposes no `content`, `size`,
  `totalElements`, or `totalPages`.
- **Status codes.** `200 OK`, `400 Bad Request` (non-numeric `transactionId`), `401 Unauthorized`.

#### `GET /api/transactions/{id}`

- **Purpose.** Transaction detail. Replaces transaction-detail screen (**CT01**, `COTRN01C`); reads TRAN
  (`CVTRA05Y`, 350 B).
- **Auth.** USER.
- **Path param.** `id` — 16-character transaction id (`TRAN-ID X(16)`).
- **Response — `TransactionDto.Detail`** (field order as serialized):

  | Field | Type | Legacy source |
  |---|---|---|
  | `transactionId` | string (max 16) | `TRAN-ID X(16)` |
  | `cardNumber` | string (max 16) | `TRAN-CARD-NUM X(16)` |
  | `transactionType` | string (2) | `TRAN-TYPE-CD X(02)` |
  | `categoryCode` | string (max 4 digits) | `TRAN-CAT-CD 9(04)` |
  | `source` | string (max 10) | `TRAN-SOURCE X(10)` |
  | `description` | string (max 60) | `TRAN-DESC X(100)` (REST limit 60) |
  | `amount` | number (2 dp) | `TRAN-AMT S9(09)V99` |
  | `originDate` | string (max 10) | `TRAN-ORIG-TS` (date portion) |
  | `processDate` | string (max 10) | `TRAN-PROC-TS` (date portion) |
  | `merchantId` | string (max 9 digits) | `TRAN-MERCHANT-ID 9(09)` |
  | `merchantName` | string (max 30) | `TRAN-MERCHANT-NAME X(50)` (REST limit 30) |
  | `merchantCity` | string (max 25) | `TRAN-MERCHANT-CITY X(50)` (REST limit 25) |
  | `merchantZip` | string (max 10) | `TRAN-MERCHANT-ZIP X(10)` |
  | `confirmationMessage` | string, **omitted when null** (`@JsonInclude(NON_NULL)`) | service confirmation text |

  The status flag field is named `transactionType` (not `typeCode`), and the dates are `originDate` /
  `processDate` as `YYYY-MM-DD` strings — there are **no** `originTimestamp` / `processTimestamp` 26-character
  fields on the response. `confirmationMessage` is populated only on the add-confirmation response and is
  omitted from a plain detail read.
- **Status codes.** `200 OK`, `401 Unauthorized`, `404 Not Found`.

#### `POST /api/transactions`

- **Purpose.** Add a transaction. Replaces transaction-add screen (**CT02**, `COTRN02C`, 1,300 LOC).
- **Auth.** USER.
- **Request — `TransactionDto.AddRequest`** (input fields from the `COTRN02` symbolic map). All listed
  fields participate in the contract — in particular `source`, `originDate`, `processDate`, the four
  `merchant*` fields, and the `confirm` flag are **required by the CT02 flow**; omitting them yields a
  `400 Bad Request`:

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `accountId` | string | `@Size(max = 11)`, `\d{0,11}` | `ACTIDINI X(11)` |
  | `cardNumber` | string | `@Size(max = 16)` | `CARDNINI X(16)` |
  | `typeCode` | string | `@Size(max = 2)` | `TTYPCDI X(2)` → `TRAN-TYPE-CD X(02)` |
  | `categoryCode` | string | `@Size(max = 4)` | `TCATCDI X(4)` → `TRAN-CAT-CD 9(04)` |
  | `source` | string | `@Size(max = 10)` | `TRNSRCI` → `TRAN-SOURCE X(10)` |
  | `description` | string | `@Size(max = 60)` | `TDESCI X(60)` → `TRAN-DESC X(100)` |
  | `amount` | decimal | `@Digits(integer = 10, fraction = 2)` | `TRNAMTI` (formatted) → `TRAN-AMT S9(09)V99` |
  | `originDate` | string | `@Size(max = 10)` (`YYYY-MM-DD`) | `TORIGDTI` → `TRAN-ORIG-TS` (date) |
  | `processDate` | string | `@Size(max = 10)` (`YYYY-MM-DD`) | `TPROCDTI` → `TRAN-PROC-TS` (date) |
  | `merchantId` | string | `@Size(max = 9)` | `MIDI` → `TRAN-MERCHANT-ID 9(09)` |
  | `merchantName` | string | `@Size(max = 30)` | `MNAMEI` → `TRAN-MERCHANT-NAME X(50)` |
  | `merchantCity` | string | `@Size(max = 25)` | `MCITYI` → `TRAN-MERCHANT-CITY X(50)` |
  | `merchantZip` | string | `@Size(max = 10)` | `MZIPI` → `TRAN-MERCHANT-ZIP X(10)` |
  | `confirm` | string | `@Size(max = 1)`, `Y`/`N` | `CONFIRMI X(1)` |

- **Two-step preview → confirm (CT02 semantics).** Like the legacy screen, the add is a confirm flow: a
  request with `confirm = "N"` (or absent) returns **`400 Bad Request`** with `"Confirm to add this
  transaction..."` (the preview gate). Only `confirm = "Y"` posts the transaction.
- **Auto-ID + confirmation.** The transaction id is **server-generated** (the modern successor to the legacy
  auto-numbering), and the response echoes the created resource as confirmation.
- **Response — `TransactionDto.Detail`** — the persisted transaction (the same field set documented for
  `GET /api/transactions/{id}` above), including the generated `transactionId` and a populated
  `confirmationMessage` such as `"Transaction added successfully.  Your Tran ID is 0000000000000001."`.
- **Status codes.** `201 Created` (on `confirm = "Y"`), `400 Bad Request` (validation or unconfirmed
  preview), `401 Unauthorized`, `404 Not Found` (unknown account/card), `422 Unprocessable Entity`
  (business-rule violation, e.g. invalid type/category).

### Billing — `BillingController`

#### `POST /api/billing/pay`

- **Purpose.** Pay the full current balance for an account. Replaces bill-pay screen (**CB00**,
  `COBIL00C`).
- **Auth.** USER.
- **Request — `BillingDto.PayRequest`** (input fields from the `COBIL00` symbolic map):

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `accountId` | string | `@Size(max = 11)`, `\d{0,11}` | `ACTIDINI X(11)` |
  | `confirm` | string | `@Size(max = 1)`, `Y`/`N` | `CONFIRMI X(1)` |

- **Behavior.** When `confirm = "Y"`, the service posts a payment transaction for the account's full current
  balance and updates the balance atomically (`@Transactional`). When `confirm = "N"` (or absent), the
  endpoint returns a preview carrying the account's current balance without applying any payment.
- **Response — `BillingDto.PayResponse`** — `{ accountId, currentBalance, confirm }` on a preview, plus
  `transactionId` and `confirmationMessage` once a payment has been posted (both annotated
  `@JsonInclude(NON_NULL)`, so they are **omitted from the preview response**). There are **no**
  `amountPaid`, `newBalance`, or `confirmed` fields.
- **Status codes.** `200 OK`, `400 Bad Request`, `401 Unauthorized`, `404 Not Found`.

### Reports — `ReportController`

#### `POST /api/reports/submit`

- **Purpose.** Submit an asynchronous transaction report. Replaces report screen (**CR00**, `CORPT00C`),
  which wrote a JCL stream to the CICS Transient Data Queue `JOBS` to trigger a batch report.
- **Auth.** USER.
- **Request — `ReportDto.SubmitRequest`.** The report type is selected with **three mutually-exclusive
  flag fields** (`monthly` / `yearly` / `custom`, each `Y`/blank) — not a single `reportType` enum — and the
  custom window is given as **discrete date segments** (`startMonth`/`startDay`/`startYear`,
  `endMonth`/`endDay`/`endYear`), mirroring the `CORPT00` map. A `confirm` flag gates submission:

  | Field | Type | Validation | Notes |
  |---|---|---|---|
  | `monthly` | string | `@Size(max = 1)`, `[Yy ]?` | select the monthly report |
  | `yearly` | string | `@Size(max = 1)`, `[Yy ]?` | select the yearly report |
  | `custom` | string | `@Size(max = 1)`, `[Yy ]?` | select a custom date-window report |
  | `startMonth` | string | `@Size(max = 2)`, `\d{0,2}` | custom-window start month |
  | `startDay` | string | `@Size(max = 2)`, `\d{0,2}` | custom-window start day |
  | `startYear` | string | `@Size(max = 4)`, `\d{0,4}` | custom-window start year |
  | `endMonth` | string | `@Size(max = 2)`, `\d{0,2}` | custom-window end month |
  | `endDay` | string | `@Size(max = 2)`, `\d{0,2}` | custom-window end day |
  | `endYear` | string | `@Size(max = 4)`, `\d{0,4}` | custom-window end year |
  | `confirm` | string | `@Size(max = 1)`, `Y`/`N` | confirm the submission |

  Submitting an empty body (or unknown fields such as `reportType`/`startDate`/`endDate`, which Jackson
  silently ignores) yields **`400 Bad Request`** with `"Select a report type to print report..."`.

- **Asynchronous bridge (F-011).** The endpoint **publishes a report-request message to the SQS FIFO queue
  `carddemo-report-jobs.fifo`** (with a `MessageGroupId` and `MessageDeduplicationId`) and returns
  immediately. A consumer then triggers the Spring Batch transaction-report job. This preserves the legacy
  TDQ `JOBS` **submit-then-process** semantics: the request is accepted asynchronously and the report is
  produced out of band.
- **Response.** The controller returns `ResponseEntity<Void>` — **`202 Accepted` with an empty body**.
  There is no `ReportSubmitResponse` payload; the queue name and any request identifier are not echoed
  back to the caller.
- **Status codes.** `202 Accepted` (queued), `400 Bad Request`, `401 Unauthorized`.

### Admin Users — `UserController`

User administration replaces the four legacy user screens (**CU00**–**CU03**, programs
`COUSR00C`–`COUSR03C`) and is restricted to **ADMIN**. Records derive from `CSUSR01Y` (SEC-USER-DATA, 80 B).

#### `GET /api/admin/users`

- **Purpose.** List users (**CU00**, `COUSR00C`). **Auth.** ADMIN.
- **Query params.** `page` (0-based, default `0`) and an optional `userId` filter. There is **no** `size`
  parameter.
- **Response — `UserDto.ListResponse`** — a **custom** wrapper (not a Spring `Page<>`):
  `{ pageNumber, userIdFilter, users }`, where `pageNumber` is a string, `userIdFilter` echoes the active
  filter, and `users` is an array of `UserSummary` objects `{ userId, firstName, lastName, userType }`
  (no password is ever returned). The wrapper exposes no `content`, `size`, `totalElements`, or
  `totalPages`.
- **Status codes.** `200 OK`, `401`, `403`.

#### `POST /api/admin/users`

- **Purpose.** Add a user (**CU01**, `COUSR01C`). **Auth.** ADMIN.
- **Request — `UserDto.CreateRequest`** (field order as declared):

  | Field | Type | Validation | Legacy source |
  |---|---|---|---|
  | `firstName` | string | `@Size(max = 20)` | `SEC-USR-FNAME X(20)` |
  | `lastName` | string | `@Size(max = 20)` | `SEC-USR-LNAME X(20)` |
  | `userId` | string | `@Size(max = 8)` | `SEC-USR-ID X(08)` |
  | `password` | string | `@Size(max = 8)` (stored BCrypt-hashed) | `SEC-USR-PWD X(08)` |
  | `userType` | string | `@Size(max = 1)`; `A`/`U` domain enforced in `UserAddService` | `SEC-USR-TYPE X(01)` |

- **Response — `UserDto.UserSummary`** — `{ userId, firstName, lastName, userType }` (never the password
  hash). **Status codes.** `201 Created`, `400`, `401`, `403`, `409 Conflict` (duplicate `userId` — the
  relational successor to VSAM `FILE STATUS 22`).
- **`userType` domain.** The DTO carries only `@Size(max = 1)`; the canonical role domain — `A`
  (administrator) or `U` (standard user), the `COCOM01Y` `88 CDEMO-USRTYP-ADMIN VALUE 'A'` /
  `88 CDEMO-USRTYP-USER VALUE 'U'` condition names — is enforced by `UserAddService` **after** the
  presence check, so a present-but-out-of-domain value (e.g. `Z`, or a lowercase `a`) is rejected with a
  `400` validation problem (`detail` = `"User Type must be A or U..."`) and is never persisted. A DTO
  `@Pattern(regexp = "[AU]")` is deliberately **not** used because it would reject the empty string before
  the service can emit the byte-exact `"User Type can NOT be empty..."` literal; see DECISION_LOG D-072.

#### `PUT /api/admin/users/{userId}`

- **Purpose.** Update a user (**CU02**, `COUSR02C`). **Auth.** ADMIN.
- **Path param.** `userId` (8). **Request — `UserDto.UpdateRequest`** (`userId` `@NotBlank @Size(max = 8)`,
  plus `firstName`/`lastName`/`password`/`userType`; password re-hashed if supplied).
- **`userType` domain.** As on the add path, `UserUpdateService` enforces the `A`/`U` domain after the
  presence check (DECISION_LOG D-072): a present-but-out-of-domain value is rejected with a `400` validation
  problem (`detail` = `"User Type must be A or U..."`) **before** the record is read or rewritten, so a
  rejected update never mutates the stored row.
- **Response — `UserDto.UserSummary`** — `{ userId, firstName, lastName, userType }`.
  **Status codes.** `200 OK`, `400`, `401`, `403`, `404 Not Found`.

#### `DELETE /api/admin/users/{userId}`

- **Purpose.** Delete a user (**CU03**, `COUSR03C`). **Auth.** ADMIN.
- **Path param.** `userId` (8).
- **Response — `UserDto.DeleteResponse`** — **`200 OK`** with the deleted user's identifying fields
  `{ userId, firstName, lastName, userType }` (the endpoint returns the deleted record, **not** `204 No
  Content`). **Status codes.** `200 OK`, `401`, `403`, `404 Not Found`.

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
| `cardNumber` | `CARD-NUM X(16)` / `TRAN-CARD-NUM X(16)` | `String` | `@Size(max = 16)` (`@Pattern(regexp = "\\d{0,16}")` on transaction) |
| `cardholderName` | `CARD-EMBOSSED-NAME X(50)` | `String` | `@Size(max = 50)` |
| `cardStatus` | `CARD-ACTIVE-STATUS X(01)` | `String` | `@Size(max = 1)` |
| `transactionType` / request `typeCode` | `TRAN-TYPE-CD X(02)` | `String` | `@Size(max = 2)` (`@Pattern(regexp = "\\d{2}")` on detail) |
| `categoryCode` | `TRAN-CAT-CD 9(04)` | `String` | `@Size(max = 4) @Pattern(regexp = "\\d{0,4}")` |
| `description` (transaction) | `TRAN-DESC X(100)` | `String` | `@Size(max = 60)` (REST entry limit) |
| `merchantId` | `TRAN-MERCHANT-ID 9(09)` | `String` | `@Size(max = 9) @Pattern(regexp = "\\d{0,9}")` |
| `originDate` / `processDate` | `TRAN-ORIG-TS` / `TRAN-PROC-TS` (date portion) | `String` | `@Size(max = 10)`, format `YYYY-MM-DD` |
| Date fields (`openDate`, `expirationDate`, …) | `X(10)` | `String` | format `CCYY-MM-DD` (10 chars) |
| User `firstName` / `lastName` | `SEC-USR-FNAME X(20)` / `SEC-USR-LNAME X(20)` | `String` | `@Size(max = 20)` |
| User `userType` | `SEC-USR-TYPE X(01)` | `String` | `@Size(max = 1)`; `A`/`U` domain enforced in `UserAddService`/`UserUpdateService` after the presence check (DECISION_LOG D-072) |

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
| `MessageGroupId` | required — the constant FIFO ordering group `report-jobs` |
| `MessageDeduplicationId` | required — a freshly generated `UUID` per submission |
| Producer | `ReportController` / `ReportService` (`POST /api/reports/submit`) |
| Consumer | SQS listener that triggers the Spring Batch transaction-report job |

**Message body schema** — the published payload is the typed `ReportService.ReportRequestMessage`
record with exactly three fields (`reportName`, `startDate`, `endDate`); `startDate`/`endDate` are
`YYYY-MM-DD` strings:

```json
{
  "reportName": "Custom",
  "startDate": "2025-01-01",
  "endDate": "2025-01-31"
}
```

(`reportName` is one of `Monthly`, `Yearly`, or `Custom`.)

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
  "token": "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJVU0VSMDAwMSIsImlzcyI6ImNhcmRkZW1vIiwidXNlclR5cGUiOiJVIn0.s1gn4tur3",
  "userId": "USER0001",
  "userType": "U"
}
```

### (b) Authenticated account view

```bash
curl -s http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJVU0VSMDAwMSIsInJvbGUiOiJVU0VSIn0.s1gn4tur3"
```

Response (`200 OK`) — a **flat** `AccountDto.ViewResponse` (30 top-level fields; the ACCOUNT and CUSTOMER
records are merged into one flat object — there is **no** nested `customer`). Monetary fields serialize as
JSON numbers; `ficoScore` is a string and `version` is the optimistic-lock token:

```json
{
  "accountId": "1",
  "accountStatus": "Y",
  "openDate": "2014-11-20",
  "creditLimit": 2020.0,
  "expirationDate": "2025-05-20",
  "cashCreditLimit": 1020.0,
  "reissueDate": "2025-05-20",
  "currentBalance": 194.0,
  "currentCycleCredit": 0.0,
  "accountGroupId": "",
  "currentCycleDebit": 0.0,
  "customerId": "1",
  "ssn": "020973888",
  "dateOfBirth": "1961-06-08",
  "ficoScore": "274",
  "firstName": "Immanuel",
  "middleName": "Madeline",
  "lastName": "Kessler",
  "addressLine1": "618 Deshaun Route",
  "state": "NC",
  "addressLine2": "Apt. 802",
  "zipCode": "12546",
  "city": "Altenwerthshire",
  "country": "USA",
  "phone1": "(908)119-8310",
  "governmentId": "00000000000049368437",
  "phone2": "(373)693-8684",
  "eftAccountId": "0053581756",
  "primaryCardHolder": "Y",
  "version": 0
}
```

### (c) Add a transaction

Adding a transaction is a **preview → confirm** flow (CT02 semantics). The request body must include every
contract field — `source`, `originDate`, `processDate`, the four `merchant*` fields, and `confirm` are
**required** — and `confirm` must be `"Y"` to actually post (a `"N"` or omitted `confirm` returns
`400 Bad Request` with `"Confirm to add this transaction..."`). Use a `cardNumber` that exists for the
account (e.g. `0500024453765740` on account `00000000050`):

```bash
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
        "accountId": "00000000050",
        "cardNumber": "0500024453765740",
        "typeCode": "01",
        "categoryCode": "0001",
        "source": "POS",
        "description": "GROCERY STORE PURCHASE",
        "amount": "125.50",
        "originDate": "2025-01-15",
        "processDate": "2025-01-15",
        "merchantId": "000000123",
        "merchantName": "ACME GROCERY",
        "merchantCity": "DALLAS",
        "merchantZip": "75001",
        "confirm": "Y"
      }'
```

Response (`201 Created`) — the persisted `TransactionDto.Detail` with the server-generated id and a
`confirmationMessage`. Note the status field is `transactionType` and the dates are `originDate` /
`processDate` (`YYYY-MM-DD`), not 26-character timestamp fields:

```json
{
  "transactionId": "0000000000000001",
  "cardNumber": "0500024453765740",
  "transactionType": "01",
  "categoryCode": "0001",
  "source": "POS",
  "description": "GROCERY STORE PURCHASE",
  "amount": 125.5,
  "originDate": "2025-01-15",
  "processDate": "2025-01-15",
  "merchantId": "000000123",
  "merchantName": "ACME GROCERY",
  "merchantCity": "DALLAS",
  "merchantZip": "75001",
  "confirmationMessage": "Transaction added successfully.  Your Tran ID is 0000000000000001."
}
```

### (d) Submit a report (asynchronous SQS FIFO bridge)

The report type is selected with the `monthly` / `yearly` / `custom` flags and a custom window is given as
discrete date segments, with `confirm: "Y"` to submit (the legacy `CORPT00` field set):

```bash
curl -s -X POST http://localhost:8080/api/reports/submit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
        "custom": "Y",
        "startMonth": "01",
        "startDay": "01",
        "startYear": "2025",
        "endMonth": "01",
        "endDay": "31",
        "endYear": "2025",
        "confirm": "Y"
      }'
```

Response: **`202 Accepted` with an empty body** (`ResponseEntity<Void>`). The request is published to the
SQS FIFO queue `carddemo-report-jobs.fifo` for out-of-band processing; no JSON payload is returned. (Using
the wrong field names such as `reportType`/`startDate`/`endDate` produces `400 Bad Request` with
`"Select a report type to print report..."`, because Jackson ignores the unknown fields and the resulting
all-null request is rejected.)

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
