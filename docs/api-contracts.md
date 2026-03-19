# CardDemo REST API Contracts

> Comprehensive REST API endpoint specifications for the CardDemo application,
> migrated from COBOL/CICS BMS 3270 terminal screens to Java Spring Boot REST endpoints.
> Every field contract, validation rule, and pagination default is derived from the
> original BMS mapset definitions and symbolic map copybooks.

---

## Table of Contents

- [1. General Information](#1-general-information)
  - [1.1 Base URL](#11-base-url)
  - [1.2 Content Type](#12-content-type)
  - [1.3 Authentication](#13-authentication)
  - [1.4 Standard Error Response](#14-standard-error-response)
  - [1.5 Pagination Wrapper](#15-pagination-wrapper)
  - [1.6 Validation Error Detail](#16-validation-error-detail)
- [2. Authentication](#2-authentication)
  - [2.1 Sign In](#21-sign-in---post-apiauthsignin)
- [3. Accounts](#3-accounts)
  - [3.1 View Account](#31-view-account---get-apiaccountsaccountid)
  - [3.2 Update Account](#32-update-account---put-apiaccountsaccountid)
- [4. Cards](#4-cards)
  - [4.1 List Cards](#41-list-cards---get-apicards)
  - [4.2 Cards by Account](#42-cards-by-account---get-apicardsaccountacctid)
  - [4.3 View Card Detail](#43-view-card-detail---get-apicardscardnum)
  - [4.4 Update Card](#44-update-card---put-apicardscardnum)
- [5. Transactions](#5-transactions)
  - [5.1 List Transactions](#51-list-transactions---get-apitransactions)
  - [5.2 View Transaction Detail](#52-view-transaction-detail---get-apitransactionstransactionid)
  - [5.3 Add Transaction](#53-add-transaction---post-apitransactions)
  - [5.4 Copy Transaction](#54-copy-transaction---get-apitransactionscopysourceid)
- [6. Billing](#6-billing)
  - [6.1 Pay Bill](#61-pay-bill---post-apibillingpay)
- [7. Reports](#7-reports)
  - [7.1 Submit Report](#71-submit-report---post-apireportssubmit)
- [8. User Administration](#8-user-administration)
  - [8.1 List Users](#81-list-users---get-apiadminusers)
  - [8.2 Add User](#82-add-user---post-apiadminusers)
  - [8.2.5 View User](#825-view-user---get-apiadminusersuserid)
  - [8.3 Update User](#83-update-user---put-apiadminusersuserid)
  - [8.4 Delete User](#84-delete-user---delete-apiadminusersuserid)
- [9. Menus](#9-menus)
  - [9.1 Main Menu](#91-main-menu---get-apimenumain)
  - [9.2 Admin Menu](#92-admin-menu---get-apimenuadmin)
- [10. Security and Headers](#10-security-and-headers)
- [11. Key Constraints and Validation Rules](#11-key-constraints-and-validation-rules)
- [12. BMS-to-REST Endpoint Mapping](#12-bms-to-rest-endpoint-mapping)

---

## 1. General Information

### 1.1 Base URL

```
http://localhost:8080
```

All API endpoints are prefixed with `/api`. Example: `http://localhost:8080/api/auth/signin`.

### 1.2 Content Type

All requests and responses use JSON:

```
Content-Type: application/json
Accept: application/json
```

### 1.3 Authentication

All endpoints except `POST /api/auth/signin` require HTTP Basic Authentication:

```
Authorization: Basic <base64(userId:password)>
```

Credentials are the same user ID and password used for sign-in. For example, with `curl`:

```bash
curl -u ADMIN001:PASSWORDA http://localhost:8080/api/accounts/00000000013
```

The sign-in endpoint can also be used to validate credentials and retrieve user routing information. See [Section 2.1](#21-sign-in---post-apiauthsignin).

### 1.4 Standard Error Response

All error responses follow this structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Descriptive error message",
  "errorCode": "VAL",
  "fieldErrors": null,
  "timestamp": "2026-03-17T10:30:00.000Z",
  "path": "/api/accounts/12345678901",
  "correlationId": "abc-123-def-456"
}
```

| Field           | Type                       | Description                                          |
|-----------------|----------------------------|------------------------------------------------------|
| `status`        | Integer                    | HTTP status code                                     |
| `error`         | String                     | HTTP status reason phrase                            |
| `message`       | String                     | Human-readable error description                     |
| `errorCode`     | String                     | Application error code (e.g., `RNF`, `DUP`, `VAL`, `CLX`, `EXP`) |
| `fieldErrors`   | Array or null              | List of per-field validation errors (see [1.6](#16-validation-error-detail)), null for non-validation errors |
| `timestamp`     | String                     | ISO 8601 timestamp of the error occurrence           |
| `path`          | String                     | Request URI that produced the error                  |
| `correlationId` | String                     | Request correlation ID for distributed tracing       |

### 1.5 Pagination Wrapper

All paginated list endpoints return Spring `Page<T>` responses:

```json
{
  "content": [ ... ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": { "sorted": false, "empty": true, "unsorted": true },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 100,
  "totalPages": 10,
  "last": false,
  "size": 10,
  "number": 0,
  "sort": { "sorted": false, "empty": true, "unsorted": true },
  "numberOfElements": 10,
  "first": true,
  "empty": false
}
```

| Field              | Type    | Description                                      |
|--------------------|---------|--------------------------------------------------|
| `content`          | Array   | Array of resource objects for the current page    |
| `pageable`         | Object  | Page request details (pageNumber, pageSize, sort) |
| `totalElements`    | Long    | Total number of matching items across all pages   |
| `totalPages`       | Integer | Total number of pages                             |
| `last`             | Boolean | Whether this is the last page                     |
| `size`             | Integer | Requested page size                               |
| `number`           | Integer | Zero-based page index                             |
| `numberOfElements` | Integer | Number of items actually in this page             |
| `first`            | Boolean | Whether this is the first page                    |
| `empty`            | Boolean | Whether the page content is empty                 |

### 1.6 Validation Error Detail

When request validation fails (HTTP 400), the `message` field contains a summary and an additional `errors` array provides field-level detail:

```json
{
  "timestamp": "2026-03-17T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/transactions",
  "errors": [
    {
      "field": "tranCardNum",
      "message": "must not be blank",
      "rejectedValue": ""
    },
    {
      "field": "tranAmt",
      "message": "must be a valid monetary amount",
      "rejectedValue": "abc"
    }
  ]
}
```

| Field           | Type   | Description                                    |
|-----------------|--------|------------------------------------------------|
| `field`         | String | Name of the field that failed validation       |
| `message`       | String | Validation constraint violation description    |
| `rejectedValue` | Any    | The value that was rejected                    |

---

## 2. Authentication

### 2.1 Sign In — `POST /api/auth/signin`

Authenticates a user and returns a session token. Replaces the CICS COSGN00 BMS sign-on screen.

**Source:** `COSGN00.bms` → `AuthController.java`

**Request:**

```http
POST /api/auth/signin HTTP/1.1
Content-Type: application/json

{
  "userId": "USER0001",
  "password": "PASSWORDU"
}
```

**Request Body — `SignOnRequest`:**

| Field      | Type   | Required | Max Length | Description                                     | BMS Source                |
|------------|--------|----------|------------|-------------------------------------------------|---------------------------|
| `userId`   | String | Yes      | 8          | User identifier                                 | `USERID` PIC X(8) UNPROT  |
| `password` | String | Yes      | 8          | User password (transmitted securely via HTTPS)   | `PASSWD` PIC X(8) DRK     |

**Responses:**

| Status | Description                                                                    |
|--------|--------------------------------------------------------------------------------|
| 200    | Authentication successful — returns UUID token, user type, and routing info    |
| 401    | Unauthorized — invalid user ID or password                                     |
| 403    | Forbidden — account is locked or disabled                                      |
| 400    | Bad Request — missing required fields                                          |

**Response 200 — `SignOnResponse`:**

```json
{
  "token": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "ADMIN001",
  "userType": "ADMIN",
  "toTranId": "COADM",
  "toProgram": "COADM01C"
}
```

| Field       | Type   | Description                                                   | Source                     |
|-------------|--------|---------------------------------------------------------------|----------------------------|
| `token`     | String | UUID session token for tracking (authentication uses HTTP Basic) | Generated by `UUID.randomUUID()` |
| `userId`    | String | Echoed user identifier                                        | `SEC-USR-ID` from CSUSR01Y |
| `userType`  | String | User role: `ADMIN` or `USER` (enum value)                     | `SEC-USR-TYPE` from CSUSR01Y |
| `toTranId`  | String | Routing transaction ID for the user's menu screen             | `CDEMO-TO-TRANID` from COCOM01Y |
| `toProgram` | String | Routing program name for the user's menu                      | `CDEMO-TO-PROGRAM` from COCOM01Y |

**Implementation Notes:**
- Password verification uses BCrypt hashing (replaces plaintext comparison per Decision D-002)
- The original BMS `PASSWD` field uses the `DRK` (dark) attribute to hide input on the 3270 terminal; HTTPS provides equivalent transport-level confidentiality
- The `ERRMSG` field (78 chars, RED) maps to the `message` field in error responses
- Failed authentication attempts are logged for audit purposes

---

## 3. Accounts

### 3.1 View Account — `GET /api/accounts/{accountId}`

Retrieves complete account information including associated customer details. Replaces the CICS COACTVW BMS account view screen.

**Source:** `COACTVW.bms` / `COACTVW.CPY` → `AccountController.java`

**Request:**

```http
GET /api/accounts/00000000013 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter   | Type   | Required | Format          | Description                           | BMS Source                            |
|-------------|--------|----------|-----------------|---------------------------------------|---------------------------------------|
| `accountId` | String | Yes      | 11 numeric chars | Account identifier                   | `ACCTSID` PIC 9(11) IC/MUSTFILL      |

**Responses:**

| Status | Description                                                  |
|--------|--------------------------------------------------------------|
| 200    | Account found — returns full account and customer details    |
| 404    | Account not found                                            |
| 401    | Unauthorized — missing or invalid credentials                      |

**Response 200 — `AccountDto`:**

```json
{
  "acctId": "00000000013",
  "acctActiveStatus": "Y",
  "acctOpenDate": "2014-08-15",
  "acctExpDate": "2025-08-15",
  "acctReissueDate": "2022-08-15",
  "acctCreditLimit": 5000.00,
  "acctCashCreditLimit": 1500.00,
  "acctCurrBal": 1234.56,
  "acctCurrCycCredit": 500.00,
  "acctCurrCycDebit": 250.00,
  "acctGroupId": "ADDR00001",
  "custId": "000000013",
  "custFname": "JOHN",
  "custMname": "M",
  "custLname": "DOE",
  "custAddr1": "123 MAIN STREET",
  "custAddr2": "APT 4B",
  "custCity": "NEW YORK",
  "custState": "NY",
  "custZip": "10001",
  "custCountry": "USA",
  "custPhone1": "2125551234",
  "custPhone2": "2125555678",
  "custSsn": "123456789",
  "custDob": "1985-03-15",
  "custFicoScore": "750",
  "custGovtId": "DL123456789",
  "custEftAcct": "ACTIVE",
  "custProfileFlag": "Y",
  "version": 0
}
```

**Account Fields:**

| Field                  | Type       | Description                                           | BMS Source                                   |
|------------------------|------------|-------------------------------------------------------|----------------------------------------------|
| `acctId`               | String     | Account identifier (11-digit numeric)                 | `ACCTSIDI` PIC 9(11)                         |
| `acctActiveStatus`     | String     | Account status code (Y=Active, N=Inactive)            | `ACSTTUSI` PIC X(1)                          |
| `acctOpenDate`         | String     | Account open date (YYYY-MM-DD, LocalDate)             | `ADTOPENI` PIC X(10)                         |
| `acctExpDate`          | String     | Account expiry date (YYYY-MM-DD, LocalDate)           | `AEXPDTI` PIC X(10)                          |
| `acctReissueDate`      | String     | Card reissue date (YYYY-MM-DD, LocalDate)             | `AREISDTI` PIC X(10)                         |
| `acctCreditLimit`      | BigDecimal | Credit limit                                          | `ACRDLIMI` COMP-3 S9(7)V99                  |
| `acctCashCreditLimit`  | BigDecimal | Cash advance limit                                    | `ACSHLIMI` COMP-3 S9(7)V99                  |
| `acctCurrBal`          | BigDecimal | Current balance                                       | `ACURBALI` COMP-3 S9(7)V99                  |
| `acctCurrCycCredit`    | BigDecimal | Cycle credit total                                    | `ACRCYCRI` COMP-3 S9(7)V99                  |
| `acctCurrCycDebit`     | BigDecimal | Cycle debit total                                     | `ACRCYDBI` COMP-3 S9(7)V99                  |
| `acctGroupId`          | String     | Address group identifier                              | `AADDGRPI` PIC X(10)                         |
| `version`              | Integer    | Optimistic lock version (JPA @Version)                | —                                            |

**Customer Fields (flat in AccountDto — not nested):**

| Field              | Type   | Description                               | BMS Source                  |
|--------------------|--------|-------------------------------------------|-----------------------------|
| `custId`           | String | Customer identifier (9-digit numeric)     | `ACSTNUMI` PIC X(9)        |
| `custFname`        | String | Customer first name                       | `ACSFNAMI` PIC X(25)       |
| `custMname`        | String | Customer middle name                      | `ACSMNAMI` PIC X(25)       |
| `custLname`        | String | Customer last name                        | `ACSLNAMI` PIC X(25)       |
| `custAddr1`        | String | Primary address line                      | `ACSADL1I` PIC X(50)       |
| `custAddr2`        | String | Secondary address line                    | `ACSADL2I` PIC X(50)       |
| `custCity`         | String | City name                                 | `ACSCITYI` PIC X(50)       |
| `custState`        | String | US state/territory abbreviation           | `ACSSTTEI` PIC X(2)        |
| `custZip`          | String | ZIP code                                  | `ACSZIPCI` PIC X(5)        |
| `custCountry`      | String | Country code                              | `ACSCTRYI` PIC X(3)        |
| `custPhone1`       | String | Home phone number                         | `ACSPHN1I` PIC X(13)       |
| `custPhone2`       | String | Work phone number                         | `ACSPHN2I` PIC X(13)       |
| `custSsn`          | String | Social Security Number (9 digits, no formatting) | `ACSTSSNI` PIC X(9) |
| `custDob`          | String | Date of birth (YYYY-MM-DD, LocalDate)     | `ACSTDOBI` PIC X(10)       |
| `custFicoScore`    | String | FICO credit score                         | `ACSTFCOI` PIC X(3)        |
| `custGovtId`       | String | Government-issued ID number               | `ACSGOVTI` PIC X(20)       |
| `custEftAcct`      | String | EFT account identifier                    | `ACSEFTCI` PIC X(10)       |
| `custProfileFlag`  | String | Primary card holder flag (Y/N)            | `ACSPFLGI` PIC X(1)        |

**Implementation Notes:**
- This endpoint performs a multi-dataset read joining ACCTDAT (account data), CUSTDAT (customer data), and CXACAIX (card cross-reference alternate index) — the same joins performed by the original COACTVWC.cbl program
- All monetary amounts are returned as string representations of `BigDecimal` values to preserve exact decimal precision per AAP §0.8.2
- The `COACTVW.bms` screen displays all fields as ASKIP (read-only); this endpoint is strictly a read operation

---

### 3.2 Update Account — `PUT /api/accounts/{accountId}`

Updates account and associated customer information. Replaces the CICS COACTUP BMS account update screen.

**Source:** `COACTUP.bms` / `COACTUP.CPY` → `AccountController.java`

**Request:**

```http
PUT /api/accounts/00000000013 HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "acctId": "00000000013",
  "acctActiveStatus": "Y",
  "acctOpenDate": "2014-08-15",
  "acctExpDate": "2025-08-15",
  "acctReissueDate": "2022-08-15",
  "stmtNum": 1,
  "acctCreditLimit": 5000.00,
  "acctCashCreditLimit": 1500.00,
  "acctCurrBal": 1234.56,
  "acctCurrCycCredit": 500.00,
  "acctCurrCycDebit": 250.00,
  "acctGroupId": "ADDR00001",
  "custId": "000000013",
  "custSsn": "123456789",
  "custDob": "1985-03-15",
  "custFicoScore": "750",
  "custFname": "JOHN",
  "custMname": "M",
  "custLname": "DOE",
  "custAddr1": "123 MAIN STREET",
  "custState": "NY",
  "custAddr2": "APT 4B",
  "custZip": "10001",
  "custCity": "NEW YORK",
  "custCountry": "USA",
  "custPhone1": "2125551234",
  "custGovtId": "DL123456789",
  "custPhone2": "2125555678",
  "custEftAcct": "ACTIVE",
  "custProfileFlag": "Y",
  "version": 0
}
```

**Path Parameters:**

| Parameter   | Type   | Required | Format          | Description          |
|-------------|--------|----------|-----------------|----------------------|
| `id`     | String | Yes      | 11 chars        | Account identifier   |

**Request Body — `AccountDto`:**

| Field                  | Type       | Required | Description                                 | BMS Source                           |
|------------------------|------------|----------|---------------------------------------------|--------------------------------------|
| `acctId`               | String     | Yes      | Account identifier (must match path param)  | `ACCTSIDI` PIC X(11) UNPROT         |
| `acctActiveStatus`     | String     | Yes      | Account status (Y/N)                        | `ACSTTUSI` PIC X(1) UNPROT          |
| `acctOpenDate`         | String     | Yes      | Open date (YYYY-MM-DD, LocalDate)           | `OPNYEARI/OPNMONI/OPNDAYI` combined |
| `acctExpDate`          | String     | Yes      | Expiry date (YYYY-MM-DD, LocalDate)         | `EXPYEARI/EXPMONI/EXPDAYI` combined |
| `acctReissueDate`      | String     | Yes      | Reissue date (YYYY-MM-DD, LocalDate)        | `RISYEARI/RISMONI/RISDAYI` combined |
| `acctCreditLimit`      | BigDecimal | Yes      | Credit limit                                | `ACRDLIMI` COMP-3 S9(7)V99          |
| `acctCashCreditLimit`  | BigDecimal | Yes      | Cash advance limit                          | `ACSHLIMI` COMP-3 S9(7)V99          |
| `acctCurrBal`          | BigDecimal | Yes      | Current account balance                     | `ACURBALI` COMP-3 S9(7)V99          |
| `acctCurrCycCredit`    | BigDecimal | Yes      | Cycle credit total                          | `ACRCYCRI` COMP-3 S9(7)V99          |
| `acctCurrCycDebit`     | BigDecimal | Yes      | Cycle debit total                           | `ACRCYDBI` COMP-3 S9(7)V99          |
| `acctGroupId`          | String     | Yes      | Address group identifier                    | `AADDGRPI` PIC X(10) UNPROT         |
| `version`              | Integer    | Yes      | Optimistic lock version                     | JPA @Version                         |

> **Note:** All date fields use ISO 8601 format (`YYYY-MM-DD`) serialized as `LocalDate` in the Java target, replacing the BMS year/month/day subfield pattern. SSN is a flat 9-character string (not an object with subfields).

**Request Body Fields — Customer (flat fields in `AccountDto`):**

The `AccountDto` includes customer fields as flat properties (not a nested object), matching the JPA entity structure.

| Field              | Type      | Required | Max Length | Description                               | BMS Source                           |
|--------------------|-----------|----------|------------|-------------------------------------------|--------------------------------------|
| `custId`           | String    | Yes      | 9          | Customer identifier                       | `ACSTNUMI` PIC X(9) UNPROT          |
| `custSsn`          | String    | Yes      | 9          | Social Security Number (9-digit string)   | `ACTSSN1I/ACTSSN2I/ACTSSN3I` combined |
| `custDob`          | LocalDate | Yes      | —          | Date of birth (YYYY-MM-DD)               | `DOBYEARI/DOBMONI/DOBDAYI` combined |
| `custFicoScore`    | String    | Yes      | 3          | FICO credit score (300-850)               | `ACSTFCOI` PIC X(3) UNPROT          |
| `custFname`        | String    | Yes      | 25         | First name                                | `ACSFNAMI` PIC X(25) UNPROT         |
| `custMname`        | String    | No       | 25         | Middle name                               | `ACSMNAMI` PIC X(25) UNPROT         |
| `custLname`        | String    | Yes      | 25         | Last name                                 | `ACSLNAMI` PIC X(25) UNPROT         |
| `custAddr1`        | String    | Yes      | 50         | Primary address line                      | `ACSADL1I` PIC X(50) UNPROT         |
| `custState`        | String    | Yes      | 2          | US state/territory code                   | `ACSSTTEI` PIC X(2) UNPROT          |
| `custAddr2`        | String    | No       | 50         | Secondary address line                    | `ACSADL2I` PIC X(50) UNPROT         |
| `custZip`          | String    | Yes      | 5          | ZIP code                                  | `ACSZIPCI` PIC X(5) UNPROT          |
| `custCity`         | String    | Yes      | 50         | City name                                 | `ACSCITYI` PIC X(50) UNPROT         |
| `custCountry`      | String    | Yes      | 3          | Country code                              | `ACSCTRYI` PIC X(3) UNPROT          |
| `custPhone1`       | String    | No       | 15         | Home phone number                         | `ACSPHN1I` PIC X(13) UNPROT         |
| `custGovtId`       | String    | No       | 20         | Government-issued ID                      | `ACSGOVTI` PIC X(20) UNPROT         |
| `custPhone2`       | String    | No       | 15         | Work phone number                         | `ACSPHN2I` PIC X(13) UNPROT         |
| `custEftAcct`      | String    | No       | 10         | EFT account reference                     | `ACSEFTCI` PIC X(10) UNPROT         |
| `custProfileFlag`  | String    | No       | 1          | Primary card holder flag (Y/N)            | `ACSPFLGI` PIC X(1) UNPROT          |

**Responses:**

| Status | Description                                                                      |
|--------|----------------------------------------------------------------------------------|
| 200    | Account updated successfully — returns updated `AccountDto`                      |
| 400    | Bad Request — validation error (invalid dates, FICO range, state/ZIP mismatch)   |
| 404    | Account not found                                                                |
| 409    | Conflict — concurrent modification detected (optimistic lock via `@Version`)     |
| 401    | Unauthorized — missing or invalid credentials                                          |

**Implementation Notes:**
- The original COACTUPC.cbl is the most complex program (4,236 lines) with `SYNCPOINT ROLLBACK` for dual-dataset (ACCTDAT + CUSTDAT) transactional integrity. The Java implementation uses `@Transactional` with rollback semantics
- Optimistic locking is enforced via JPA `@Version` annotation, replacing the CICS `READ UPDATE` snapshot comparison pattern
- Date fields are decomposed into year/month/day components in the BMS map (`OPNYEARI`/`OPNMONI`/`OPNDAYI`) — the API uses ISO 8601 `LocalDate` format (YYYY-MM-DD) replacing the subfield pattern
- All monetary fields must be valid `BigDecimal` string representations — no floating-point values accepted
- Validation includes: date format validation (via `DateValidationService` replacing LE CEEDAYS), FICO score range (300-850), state/ZIP combination checking (via `ValidationLookupService` from CSLKPCDY.cpy data)

---

## 4. Cards

### 4.1 List Cards — `GET /api/cards`

Retrieves a paginated list of credit cards with optional filtering. Replaces the CICS COCRDLI BMS card list screen.

**Source:** `COCRDLI.bms` / `COCRDLI.CPY` → `CardController.java`

**Request:**

```http
GET /api/cards?accountId=00000000013&page=0&size=7 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Query Parameters:**

| Parameter    | Type    | Required | Default | Description                                    | BMS Source                    |
|--------------|---------|----------|---------|------------------------------------------------|-------------------------------|
| `accountId`  | String  | No       | —       | Filter by account ID (11 chars)                | `ACCTSID` PIC X(11) UNPROT   |
| `cardNumber` | String  | No       | —       | Filter by card number (16 chars)               | `CARDSID` PIC X(16) UNPROT   |
| `page`       | Integer | No       | 0       | Zero-based page index                          | `PAGENO` PIC X(3)            |
| `size`       | Integer | No       | 7       | Items per page (**7 matches BMS 7-row display**)| 7 data rows in COCRDLI.bms   |

**Responses:**

| Status | Description                                              |
|--------|----------------------------------------------------------|
| 200    | Returns paginated list of cards                          |
| 401    | Unauthorized — missing or invalid credentials                  |

**Response 200 — Paginated `CardDto` list:**

```json
{
  "content": [
    {
      "cardNum": "4444111122223333",
      "cardAcctId": "00000000013",
      "cardEmbossedName": "JOHN M DOE",
      "cardActiveStatus": "Y",
      "cardExpDate": "2025-08-15",
      "cardCvvCd": "123",
      "version": 0
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 7 },
  "totalElements": 15,
  "totalPages": 3,
  "last": false,
  "size": 7,
  "number": 0
}
```

**Card List Item Fields (Spring `Page<CardDto>`):**

| Field              | Type    | Description                                | BMS Source                     |
|--------------------|---------|-------------------------------------------|--------------------------------|
| `cardNum`          | String  | Card number                                | `CRDNUMn` PIC X(16) ASKIP      |
| `cardAcctId`       | String  | Account identifier                         | `ACCTNOn` PIC X(11) ASKIP      |
| `cardEmbossedName` | String  | Name on card                               | `CRDNAME` PIC X(50)            |
| `cardActiveStatus` | String  | Card status (Y=Active, N=Inactive)         | `CRDSTSn` PIC X(1) ASKIP       |
| `cardExpDate`      | String  | Expiry date (YYYY-MM-DD)                   | `EXPMON/EXPYEAR` combined      |
| `cardCvvCd`        | String  | CVV code                                   | —                              |
| `version`          | Integer | Optimistic lock version                    | —                              |

**Implementation Notes:**
- The original COCRDLI.bms displays 7 selectable rows per page with selection codes (U=Update, S=Select, D=Delete)
- Pagination default of 7 preserves the original BMS screen row count
- The `CRDSTPn` hidden fields (DRK attribute) in the BMS map carry internal state for the selection action — this is handled server-side in the REST API

---

### 4.2 Cards by Account — `GET /api/cards/account/{acctId}`

Retrieves all cards associated with a specific account. Provides account-based card lookup.

**Source:** `CardController.java`

**Request:**

```http
GET /api/cards/account/00000000013 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter | Type   | Required | Description                     |
|-----------|--------|----------|---------------------------------|
| `acctId`  | String | Yes      | Account identifier (11 chars)   |

**Responses:**

| Status | Description                                     |
|--------|-------------------------------------------------|
| 200    | Returns list of cards for the account           |
| 404    | Account not found or no cards                   |
| 401    | Unauthorized                                    |

**Response 200 — List of `CardDto`:**

Returns an array of `CardDto` objects (same structure as card list items above).

---

### 4.3 View Card Detail — `GET /api/cards/{cardNumber}`

Retrieves detailed information for a single credit card. Replaces the CICS COCRDSL BMS card detail screen.

**Source:** `COCRDSL.bms` / `COCRDSL.CPY` → `CardController.java`

**Request:**

```http
GET /api/cards/4444111122223333 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter    | Type   | Required | Format   | Description           | BMS Source                     |
|--------------|--------|----------|----------|-----------------------|--------------------------------|
| `cardNumber` | String | Yes      | 16 chars | Credit card number    | `CARDSID` PIC X(16) UNPROT    |

**Responses:**

| Status | Description                                |
|--------|--------------------------------------------|
| 200    | Card found — returns full card details     |
| 404    | Card not found                             |
| 401    | Unauthorized — missing or invalid credentials    |

**Response 200 — `CardDto`:**

```json
{
  "cardNum": "4444111122223333",
  "cardAcctId": "00000000013",
  "cardEmbossedName": "JOHN M DOE",
  "cardActiveStatus": "Y",
  "cardExpDate": "2025-08-15",
  "cardCvvCd": "123",
  "version": 0
}
```

**Card Detail Fields (`CardDto`):**

| Field              | Type    | Description                               | BMS Source                   |
|--------------------|---------|-------------------------------------------|------------------------------|
| `cardNum`          | String  | Card number (16 chars)                    | `CARDSID` PIC X(16)         |
| `cardAcctId`       | String  | Associated account identifier (11 chars)  | `ACCTSID` PIC X(11)         |
| `cardEmbossedName` | String  | Name embossed on card                     | `CRDNAME` PIC X(50) ASKIP   |
| `cardActiveStatus` | String  | Card status (Y=Active, N=Inactive)        | `CRDSTCD` PIC X(1) ASKIP    |
| `cardExpDate`      | String  | Card expiry date (YYYY-MM-DD, LocalDate)  | `EXPMON/EXPYEAR` combined   |
| `cardCvvCd`        | String  | Card CVV code                             | —                           |
| `version`          | Integer | Optimistic lock version (JPA @Version)    | —                           |

---

### 4.4 Update Card — `PUT /api/cards/{cardNumber}`

Updates credit card information. Replaces the CICS COCRDUP BMS card update screen.

**Source:** `COCRDUP.bms` / `COCRDUP.CPY` → `CardController.java`

**Request:**

```http
PUT /api/cards/4444111122223333 HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "cardNum": "4444111122223333",
  "cardAcctId": "00000000013",
  "cardEmbossedName": "JOHN M DOE",
  "cardExpDate": "2027-12-01",
  "cardActiveStatus": "Y",
  "cardCvvCd": "123",
  "version": 0
}
```

**Path Parameters:**

| Parameter | Type   | Required | Format   | Description                   |
|-----------|--------|----------|----------|-------------------------------|
| `cardNum` | String | Yes      | 16 chars | Credit card number            |

**Request Body — `CardDto`:**

| Field              | Type    | Required | Description                           | BMS Source                          |
|--------------------|---------|----------|---------------------------------------|-------------------------------------|
| `cardNum`          | String  | Yes      | Card number (must match path param)   | `CARDSID` PIC X(16)                |
| `cardAcctId`       | String  | Yes      | Account identifier (read-only)        | `ACCTSID` PIC X(11) PROT           |
| `cardEmbossedName` | String  | Yes      | Name printed on card                  | `CRDNAME` PIC X(50) UNPROT         |
| `cardExpDate`      | String  | Yes      | Expiry date (YYYY-MM-DD, LocalDate)   | `EXPMON/EXPYEAR` combined           |
| `cardActiveStatus` | String  | Yes      | Card status (Y/N)                     | `CRDSTCD` PIC X(1) UNPROT          |
| `cardCvvCd`        | String  | No       | CVV code                              | —                                   |
| `version`          | Integer | Yes      | Optimistic lock version               | JPA @Version                        |

> **Note:** The `cardAcctId` field is PROT (protected/read-only) in the COCRDUP.bms screen and cannot be changed via this endpoint. The card's account association is immutable.

**Responses:**

| Status | Description                                                                |
|--------|----------------------------------------------------------------------------|
| 200    | Card updated successfully — returns updated `CardDto`                     |
| 400    | Bad Request — validation error                                            |
| 404    | Card not found                                                            |
| 409    | Conflict — concurrent modification (optimistic lock via `@Version`)       |
| 401    | Unauthorized — missing or invalid credentials                                   |

**Implementation Notes:**
- Optimistic concurrency control via JPA `@Version` replaces the CICS `READ UPDATE` snapshot comparison in COCRDUPC.cbl
- The `EXPDAY` field exists in `COCRDUP.bms` as a DRK/PROT hidden field — it is not exposed in the API but is defaulted to "01" server-side
- Function keys from the BMS map (ENTER=Process, F3=Exit, F5=Save, F12=Cancel) are replaced by HTTP methods and status codes

---

## 5. Transactions

### 5.1 List Transactions — `GET /api/transactions`

Retrieves a paginated list of transactions with optional filtering. Replaces the CICS COTRN00 BMS transaction list screen.

**Source:** `COTRN00.bms` / `COTRN00.CPY` → `TransactionController.java`

**Request:**

```http
GET /api/transactions?transactionId=0000000000000001&page=0&size=10 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Query Parameters:**

| Parameter       | Type    | Required | Default | Description                                       | BMS Source                     |
|-----------------|---------|----------|---------|---------------------------------------------------|--------------------------------|
| `transactionId` | String  | No       | —       | Filter by transaction ID prefix (up to 16 chars)  | `TRNIDIN` PIC X(16) UNPROT    |
| `page`          | Integer | No       | 0       | Zero-based page index                             | `PAGENUM` PIC X(8)            |
| `size`          | Integer | No       | 10      | Items per page (**10 matches BMS 10-row display**)| 10 data rows in COTRN00.bms   |

**Responses:**

| Status | Description                                        |
|--------|----------------------------------------------------|
| 200    | Returns paginated transaction list                 |
| 401    | Unauthorized — missing or invalid credentials            |

**Response 200 — Paginated `TransactionDto` list:**

```json
{
  "content": [
    {
      "tranId": "0000000000000001",
      "tranTypeCd": "01",
      "tranCatCd": "5411",
      "tranSource": "POS TERM",
      "tranDesc": "PURCHASE AT STORE",
      "tranAmt": 125.50,
      "tranCardNum": "4444111122223333",
      "tranOrigTs": "2023-01-15T10:30:00",
      "tranProcTs": "2023-01-16T08:00:00",
      "tranMerchId": "M12345678",
      "tranMerchName": "ACME GROCERY INC",
      "tranMerchCity": "NEW YORK",
      "tranMerchZip": "10001"
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 10 },
  "totalElements": 50,
  "totalPages": 5,
  "last": false,
  "size": 10,
  "number": 0
}
```

**Transaction List Item Fields:**

| Field            | Type       | Description                              | BMS Source                        |
|------------------|------------|------------------------------------------|-----------------------------------|
| `tranId`         | String     | Transaction identifier (16 chars)        | `TRNIDnn` PIC X(16) ASKIP         |
| `tranTypeCd`     | String     | Transaction type code                    | `TTYPCD` PIC X(2)                 |
| `tranCatCd`      | String     | Transaction category code                | `TCATCD` PIC X(4)                 |
| `tranSource`     | String     | Transaction source                       | `TRNSRC` PIC X(10)                |
| `tranDesc`       | String     | Transaction description                  | `TDESCnn` PIC X(26) ASKIP         |
| `tranAmt`        | BigDecimal | Transaction amount                       | `TAMTnnn` COMP-3 S9(7)V99         |
| `tranCardNum`    | String     | Card number                              | `CARDNUM` PIC X(16)               |
| `tranOrigTs`     | String     | Original transaction timestamp           | `TORIGDT` PIC X(10)               |
| `tranProcTs`     | String     | Processing timestamp                     | `TPROCDT` PIC X(10)               |
| `tranMerchId`    | String     | Merchant identifier                      | `MID` PIC X(9)                    |
| `tranMerchName`  | String     | Merchant name                            | `MNAME` PIC X(30)                 |
| `tranMerchCity`  | String     | Merchant city                            | `MCITY` PIC X(25)                 |
| `tranMerchZip`   | String     | Merchant ZIP code                        | `MZIP` PIC X(10)                  |

**Implementation Notes:**
- Pagination default of 10 preserves the original BMS screen row count (10 selectable rows per page)
- The `SELnnnn` selection fields (1 char, UNPROT) from the BMS map are not included in list responses — row selection is handled by subsequent detail/update requests using the transaction ID
- Transaction amounts are returned as strings to preserve `BigDecimal` precision

---

### 5.2 View Transaction Detail — `GET /api/transactions/{transactionId}`

Retrieves complete details for a single transaction. Replaces the CICS COTRN01 BMS transaction view screen.

**Source:** `COTRN01.bms` / `COTRN01.CPY` → `TransactionController.java`

**Request:**

```http
GET /api/transactions/0000000000000001 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter       | Type   | Required | Format   | Description              | BMS Source                      |
|-----------------|--------|----------|----------|--------------------------|---------------------------------|
| `transactionId` | String | Yes      | 16 chars | Transaction identifier   | `TRNIDIN` PIC X(16) IC/UNPROT  |

**Responses:**

| Status | Description                                        |
|--------|----------------------------------------------------|
| 200    | Transaction found — returns full detail            |
| 404    | Transaction not found                              |
| 401    | Unauthorized — missing or invalid credentials            |

**Response 200 — `TransactionDto`:**

```json
{
  "tranId": "0000000000000001",
  "tranCardNum": "4444111122223333",
  "tranTypeCd": "01",
  "tranCatCd": "5411",
  "tranSource": "POS TERM",
  "tranDesc": "PURCHASE AT GROCERY STORE",
  "tranAmt": 125.50,
  "tranOrigTs": "2023-01-15T10:30:00",
  "tranProcTs": "2023-01-16T08:00:00",
  "tranMerchId": "M12345678",
  "tranMerchName": "ACME GROCERY INC",
  "tranMerchCity": "NEW YORK",
  "tranMerchZip": "10001"
}
```

**Transaction Detail Fields (`TransactionDto`):**

| Field            | Type       | Description                                    | BMS Source                    |
|------------------|------------|------------------------------------------------|-------------------------------|
| `tranId`         | String     | Transaction identifier (16 chars)              | `TRNID` PIC X(16) ASKIP      |
| `tranCardNum`    | String     | Associated card number (16 chars)              | `CARDNUM` PIC X(16) ASKIP    |
| `tranTypeCd`     | String     | Transaction type code (2 chars)                | `TTYPCD` PIC X(2) ASKIP      |
| `tranCatCd`      | String     | Transaction category code (4 chars)            | `TCATCD` PIC X(4) ASKIP      |
| `tranSource`     | String     | Transaction source (POS TERM, OPERATOR, etc.)  | `TRNSRC` PIC X(10) ASKIP     |
| `tranDesc`       | String     | Full transaction description                   | `TDESC` PIC X(60) ASKIP      |
| `tranAmt`        | BigDecimal | Transaction amount                             | `TRNAMT` COMP-3 S9(7)V99     |
| `tranOrigTs`     | String     | Original transaction timestamp (ISO datetime)  | `TORIGDT` PIC X(10) ASKIP    |
| `tranProcTs`     | String     | Processing timestamp (ISO datetime)            | `TPROCDT` PIC X(10) ASKIP    |
| `tranMerchId`    | String     | Merchant identifier                            | `MID` PIC X(9) ASKIP         |
| `tranMerchName`  | String     | Merchant name                                  | `MNAME` PIC X(30) ASKIP      |
| `tranMerchCity`  | String     | Merchant city                                  | `MCITY` PIC X(25) ASKIP      |
| `tranMerchZip`   | String     | Merchant ZIP code                              | `MZIP` PIC X(10) ASKIP       |

**Implementation Notes:**
- All fields are display-only (ASKIP) in the original BMS screen — this is a read-only endpoint
- The `TRNIDIN` input field (IC/UNPROT) is used for initial transaction lookup; in the REST API, this becomes the path parameter
- The `merchantZip` field is 10 characters (not 5) to accommodate extended ZIP+4 format

---

### 5.3 Add Transaction — `POST /api/transactions`

Creates a new transaction record with auto-generated transaction ID. Replaces the CICS COTRN02 BMS transaction add screen.

**Source:** `COTRN02.bms` / `COTRN02.CPY` → `TransactionController.java`

**Request:**

```http
POST /api/transactions HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "tranCardNum": "4444111122223333",
  "tranTypeCd": "01",
  "tranCatCd": "5411",
  "tranSource": "POS TERM",
  "tranDesc": "PURCHASE AT GROCERY STORE",
  "tranAmt": "-125.50",
  "tranOrigTs": "2023-01-15T10:30:00",
  "tranProcTs": "2023-01-16T08:00:00",
  "tranMerchId": "M12345678",
  "tranMerchName": "ACME GROCERY INC",
  "tranMerchCity": "NEW YORK",
  "tranMerchZip": "10001"
}
```

**Request Body — `TransactionDto`:**

| Field            | Type           | Required | Max Length | Description                                                                                                  | BMS Source                          |
|------------------|----------------|----------|------------|--------------------------------------------------------------------------------------------------------------|-------------------------------------|
| `tranCardNum`    | String         | Yes      | 16         | Card number — account is resolved automatically via cross-reference lookup (CXACAIX)                          | `CARDNIN` PIC X(16) UNPROT         |
| `tranTypeCd`     | String         | Yes      | 2          | Transaction type code                                                                                        | `TTYPCD` PIC X(2) UNPROT           |
| `tranCatCd`      | String         | Yes      | 4          | Transaction category code                                                                                    | `TCATCD` PIC X(4) UNPROT           |
| `tranSource`     | String         | Yes      | 10         | Transaction source (e.g., "POS TERM", "OPERATOR")                                                            | `TRNSRC` PIC X(10) UNPROT          |
| `tranDesc`       | String         | Yes      | 100        | Transaction description                                                                                      | `TDESC` PIC X(60) UNPROT           |
| `tranAmt`        | String/Decimal | Yes      | —          | Amount as signed decimal string (e.g., "-125.50"); backed by `BigDecimal`                                    | `TRNAMT` PIC X(12) UNPROT          |
| `tranOrigTs`     | String         | Yes      | —          | Original timestamp (ISO 8601: `YYYY-MM-DDTHH:MM:SS`); backed by `LocalDateTime`                             | `TORIGDT` PIC X(10) UNPROT         |
| `tranProcTs`     | String         | No       | —          | Processed timestamp (ISO 8601); auto-set if omitted; backed by `LocalDateTime`                               | `TPROCDT` PIC X(10) UNPROT         |
| `tranMerchId`    | String         | No       | 9          | Merchant identifier                                                                                          | `MID` PIC X(9) UNPROT              |
| `tranMerchName`  | String         | No       | 30         | Merchant name                                                                                                | `MNAME` PIC X(30) UNPROT           |
| `tranMerchCity`  | String         | No       | 25         | Merchant city                                                                                                | `MCITY` PIC X(25) UNPROT           |
| `tranMerchZip`   | String         | No       | 10         | Merchant ZIP code                                                                                            | `MZIP` PIC X(10) UNPROT            |

**Responses:**

| Status | Description                                                                     |
|--------|---------------------------------------------------------------------------------|
| 201    | Transaction created — returns `TransactionDto` with auto-generated ID           |
| 400    | Bad Request — validation error (invalid account, card, type, category, amount)  |
| 401    | Unauthorized — missing or invalid credentials                                         |

**Response 201 — Created `TransactionDto`:**

```json
{
  "tranId": "0000000000000051",
  "tranTypeCd": "01",
  "tranCatCd": "5411",
  "tranSource": "POS TERM",
  "tranDesc": "PURCHASE AT GROCERY STORE",
  "tranAmt": -125.50,
  "tranCardNum": "4444111122223333",
  "tranMerchId": "M12345678",
  "tranMerchName": "ACME GROCERY INC",
  "tranMerchCity": "NEW YORK",
  "tranMerchZip": "10001",
  "tranOrigTs": "2023-01-15T10:30:00",
  "tranProcTs": "2023-01-16T08:00:00"
}
```

**Implementation Notes:**
- The `tranId` is auto-generated using the browse-to-end + increment pattern (finds the maximum existing transaction ID and increments by 1), preserving the original COTRN02C.cbl auto-ID generation logic
- `tranCardNum` is required; the system resolves the associated account via cross-reference lookup (CXACAIX alternate index on account ID)
- All monetary values (`tranAmt`) use `BigDecimal` for exact decimal precision — no floating-point; negative amounts represent debits
- Timestamp fields (`tranOrigTs`, `tranProcTs`) use ISO 8601 `LocalDateTime` format
- The copy endpoint (`GET /api/transactions/copy/{sourceId}`) replaces the BMS F5 "Copy Last Tran." function key, pre-populating a new transaction DTO from an existing record

---



---

### 5.4 Copy Transaction — `GET /api/transactions/copy/{sourceId}`

Creates a new transaction DTO pre-populated from an existing transaction's data for copy/template purposes.

**Source:** `TransactionController.java`

**Request:**

```http
GET /api/transactions/copy/0000000000000001 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter  | Type   | Required | Description                           |
|------------|--------|----------|---------------------------------------|
| `sourceId` | String | Yes      | Source transaction ID to copy from    |

**Responses:**

| Status | Description                                              |
|--------|----------------------------------------------------------|
| 200    | Returns pre-populated `TransactionDto` from source       |
| 404    | Source transaction not found                              |
| 401    | Unauthorized                                             |

**Response 200 — `TransactionDto`:**

Returns a `TransactionDto` with fields copied from the source transaction (same structure as transaction detail above), ready for submission as a new transaction via `POST /api/transactions`.

---

## 6. Billing

### 6.1 Pay Bill — `POST /api/billing/pay`

Processes a bill payment against an account. Replaces the CICS COBIL00 BMS bill payment screen.

**Source:** `COBIL00.bms` / `COBIL00.CPY` → `BillingController.java`

**Request:**

```http
POST /api/billing/pay HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "accountId": "00000000013",
  "confirmIndicator": "Y"
}
```

**Request Body — `BillPaymentRequest`:**

| Field              | Type   | Required | Max Length | Description                                                     | BMS Source                      |
|--------------------|--------|----------|------------|-----------------------------------------------------------------|---------------------------------|
| `accountId`        | String | Yes      | 11         | Account identifier (numeric, up to 11 characters)               | `ACTIDIN` PIC X(11) UNPROT     |
| `confirmIndicator` | String | No       | 1          | Confirmation: "Y" to confirm payment, "N" to cancel; may be null on initial request | `CONFIRMI` PIC X(1) UNPROT     |

**Responses:**

| Status | Description                                                                                   |
|--------|-----------------------------------------------------------------------------------------------|
| 200    | Payment cancelled — `confirmIndicator` was not "Y"; returns text message                       |
| 201    | Payment processed — returns `TransactionDto` for the created bill payment transaction          |
| 400    | Bad Request — account not found, zero/negative balance, or validation error                    |
| 401    | Unauthorized — missing or invalid credentials                                                       |

**Response 200 (cancelled) / 201 (payment processed):**

If `confirmIndicator` is not "Y", the payment is cancelled and a `200 OK` text response is returned:

```
Bill payment cancelled
```

If `confirmIndicator` is "Y", the payment is processed and a `201 Created` `TransactionDto` is returned representing the newly created bill payment transaction:

```json
{
  "tranId": "0000000000000051",
  "tranTypeCd": "01",
  "tranCatCd": "5411",
  "tranSource": "BILL PAY",
  "tranDesc": "BILL PAYMENT",
  "tranAmt": -1234.56,
  "tranCardNum": "4444111122223333",
  "tranMerchId": "",
  "tranMerchName": "",
  "tranMerchCity": "",
  "tranMerchZip": "",
  "tranOrigTs": "2023-01-15T12:00:00",
  "tranProcTs": "2023-01-15T12:00:00"
}
```

| Field            | Type           | Description                                                       |
|------------------|----------------|-------------------------------------------------------------------|
| `tranId`         | String         | Auto-generated 16-digit transaction ID                            |
| `tranTypeCd`     | String         | Transaction type code                                             |
| `tranCatCd`      | String         | Transaction category code                                         |
| `tranSource`     | String         | Transaction source ("BILL PAY" for bill payments)                 |
| `tranDesc`       | String         | Transaction description                                           |
| `tranAmt`        | BigDecimal     | Payment amount (negative, deducted from account balance)          |
| `tranCardNum`    | String         | Card number resolved from account cross-reference                 |
| `tranMerchId`    | String         | Merchant ID (empty for bill payments)                             |
| `tranMerchName`  | String         | Merchant name (empty for bill payments)                           |
| `tranMerchCity`  | String         | Merchant city (empty for bill payments)                           |
| `tranMerchZip`   | String         | Merchant ZIP (empty for bill payments)                            |
| `tranOrigTs`     | LocalDateTime  | Original transaction timestamp                                    |
| `tranProcTs`     | LocalDateTime  | Processed transaction timestamp                                   |

**Implementation Notes:**
- The original COBIL00.bms screen displays the current balance (`CURBAL`, 14 chars, ASKIP) as a read-only field, with only the account ID input and a Y/N confirmation indicator
- `confirmIndicator` must be exactly "Y" (case-insensitive) to proceed with payment; any other value (including null, blank, or "N") cancels the operation
- The account balance update and transaction record creation occur within a single `@Transactional` operation
- The response is a `TransactionDto` representing the newly created bill payment transaction (not a custom payment response)
- All monetary values use `BigDecimal` for exact decimal precision

---

## 7. Reports

### 7.1 Submit Report — `POST /api/reports/submit`

Submits a report generation request to the batch processing queue. Replaces the CICS CORPT00 BMS report selection screen.

**Source:** `CORPT00.bms` / `CORPT00.CPY` → `ReportController.java`

**Request:**

```http
POST /api/reports/submit HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "monthly": false,
  "yearly": false,
  "custom": true,
  "startDate": "2023-01-15",
  "endDate": "2023-12-31",
  "confirm": "Y"
}
```

**Request Body — `ReportRequest`:**

| Field       | Type      | Required | Description                                                                                     | BMS Source                                    |
|-------------|-----------|----------|-------------------------------------------------------------------------------------------------|-----------------------------------------------|
| `monthly`   | boolean   | Yes      | Set `true` to request a monthly report (mutually exclusive with `yearly`/`custom`)              | `MONTHLYI` PIC X(1) (line 60)                |
| `yearly`    | boolean   | Yes      | Set `true` to request a yearly report (mutually exclusive with `monthly`/`custom`)              | `YEARLYI` PIC X(1) (line 66)                 |
| `custom`    | boolean   | Yes      | Set `true` for a custom date range report; requires `startDate` and `endDate`                   | `CUSTOMI` PIC X(1) (line 72)                 |
| `startDate` | LocalDate | Cond.    | Start date (YYYY-MM-DD); required when `custom` is `true`                                       | `SDTMMI`/`SDTDDI`/`SDTYYYYI` combined        |
| `endDate`   | LocalDate | Cond.    | End date (YYYY-MM-DD); required when `custom` is `true`                                         | `EDTMMI`/`EDTDDI`/`EDTYYYYI` combined        |
| `confirm`   | String    | Yes      | Confirmation: `"Y"` to submit, `"N"` or null to cancel (max 1 character)                       | `CONFIRMI` PIC X(1) UNPROT (line 114)         |

**Responses:**

| Status | Description                                                              |
|--------|--------------------------------------------------------------------------|
| 200    | OK — report submission cancelled (`confirm` was not `"Y"`)                |
| 202    | Accepted — report job submitted to SQS queue for batch processing        |
| 400    | Bad Request — no report type selected, or invalid/missing date range     |
| 401    | Unauthorized — missing or invalid credentials                                  |

**Response 200 (cancelled):**

If `confirm` is not `"Y"`, the submission is cancelled and a plain text `200 OK` response is returned:

```
Report submission cancelled
```

**Response 202 (submitted):**

If `confirm` is `"Y"`, the report job is published to SQS and a plain text `202 Accepted` response is returned containing a confirmation message:

```
Custom report submitted for printing ...
```

The response body is a plain `String` (not JSON). The text format mirrors the original COBOL CORPT00C.cbl informational messages.

**Implementation Notes:**
- The original CORPT00C.cbl uses CICS TDQ `WRITEQ` to submit the report job to the JOBS queue for JES batch submission. The Java implementation publishes an SQS message to the `carddemo-report-jobs.fifo` queue (Decision D-004)
- The BMS screen presents three radio-style selection fields (`MONTHLY`, `YEARLY`, `CUSTOM`) — each is a single-character UNPROT field where entering any non-space value selects that option. The Java DTO uses three boolean flags (`monthly`, `yearly`, `custom`) to match this pattern
- Custom date range uses `LocalDate` (`YYYY-MM-DD`) consolidating the BMS MM/DD/YYYY subfields into idiomatic Java date objects
- `confirm` must be `"Y"` to proceed; any other value cancels the submission (matching COBOL's CONFIRMI check)
- Returns HTTP 202 (Accepted) because report generation is asynchronous — the actual report is produced by the batch pipeline and stored in S3
- The response body is a plain text confirmation string (not JSON), mirroring the original COBOL informational messages

---

## 8. User Administration

All user administration endpoints require the `ADMIN` role. Regular users will receive a 403 Forbidden response.

### 8.1 List Users — `GET /api/admin/users`

Retrieves a paginated list of system users. Replaces the CICS COUSR00 BMS user list screen.

**Source:** `COUSR00.bms` / `COUSR00.CPY` → `UserAdminController.java`

**Request:**

```http
GET /api/admin/users?userId=USER&page=0&size=10 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Query Parameters:**

| Parameter | Type    | Required | Default | Description                                  | BMS Source                     |
|-----------|---------|----------|---------|----------------------------------------------|--------------------------------|
| `userId`  | String  | No       | —       | Filter/search by user ID prefix (up to 8)   | `USRIDIN` PIC X(8) UNPROT     |
| `page`    | Integer | No       | 0       | Zero-based page index                        | `PAGENUM` PIC X(8)            |
| `size`    | Integer | No       | 10      | Items per page (matches BMS display rows)    | 10 data rows in COUSR00.bms   |

**Responses:**

| Status | Description                                    |
|--------|------------------------------------------------|
| 200    | Returns paginated user list                    |
| 401    | Unauthorized — missing or invalid credentials        |
| 403    | Forbidden — requires ADMIN role                |

**Response 200 — Paginated `UserSecurityDto` list:**

```json
{
  "content": [
    {
      "secUsrId": "USER0001",
      "secUsrFname": "JOHN",
      "secUsrLname": "DOE",
      "secUsrType": "ADMIN"
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 10 },
  "totalElements": 25,
  "totalPages": 3,
  "last": false,
  "size": 10,
  "number": 0
}
```

**User List Item Fields (`UserSecurityDto`):**

| Field         | Type   | Description                             | BMS Source                      |
|---------------|--------|-----------------------------------------|---------------------------------|
| `secUsrId`    | String | User identifier (8 chars)               | `USRIDnn` PIC X(8) ASKIP        |
| `secUsrFname` | String | First name (20 chars)                   | `FNAMEnn` PIC X(20) ASKIP       |
| `secUsrLname` | String | Last name (20 chars)                    | `LNAMEnn` PIC X(20) ASKIP       |
| `secUsrType`  | Enum   | User type: `ADMIN` (A) or `USER` (U)   | `UTYPEnn` PIC X(1) ASKIP        |

**Implementation Notes:**
- The COUSR00.bms screen displays 10 rows per page with columns: Sel (1), User ID (8), First Name (20), Last Name (20), Type (1)
- Selection codes from the `SELnnnn` fields (U=Update, D=Delete) are handled by separate REST endpoints
- The `USRIDIN` search field allows filtering by user ID prefix

---

### 8.2 Add User — `POST /api/admin/users`

Creates a new user account. Replaces the CICS COUSR01 BMS user add screen.

**Source:** `COUSR01.bms` / `COUSR01.CPY` → `UserAdminController.java`

**Request:**

```http
POST /api/admin/users HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "secUsrId": "JSMITH01",
  "secUsrFname": "JANE",
  "secUsrLname": "SMITH",
  "secUsrPwd": "P@ssw0rd",
  "secUsrType": "USER"
}
```

**Request Body — `UserSecurityDto`:**

| Field         | Type   | Required | Description                             | BMS Source                              |
|---------------|--------|----------|-----------------------------------------|-----------------------------------------|
| `secUsrId`    | String | Yes      | User ID (unique, max 8 chars)           | `USERID` PIC X(8) UNPROT               |
| `secUsrFname` | String | Yes      | First name (max 20 chars)               | `FNAME` PIC X(20) IC/UNPROT            |
| `secUsrLname` | String | Yes      | Last name (max 20 chars)                | `LNAME` PIC X(20) UNPROT               |
| `secUsrPwd`   | String | Yes      | Password (stored as BCrypt hash)        | `PASSWD` PIC X(8) DRK/UNPROT           |
| `secUsrType`  | Enum   | Yes      | User type: `ADMIN` (A) or `USER` (U)   | `USRTYPE` PIC X(1) UNPROT              |

**Responses:**

| Status | Description                                                     |
|--------|-----------------------------------------------------------------|
| 201    | User created — returns `UserSecurityDto` (password excluded)    |
| 400    | Bad Request — validation error (missing fields, invalid type)   |
| 409    | Conflict — user ID already exists                               |
| 401    | Unauthorized — missing or invalid credentials                         |
| 403    | Forbidden — requires ADMIN role                                 |

**Response 201:**

```json
{
  "secUsrId": "JSMITH01",
  "secUsrFname": "JANE",
  "secUsrLname": "SMITH",
  "secUsrType": "USER"
}
```

**Implementation Notes:**
- Password is stored as a BCrypt hash (Decision D-002) — `secUsrPwd` is never returned in responses
- The BMS screen provides hints "(8 Char)" next to both USERID and PASSWD fields; validation enforces this constraint
- The `PASSWD` field uses the `DRK` (dark) BMS attribute to hide input; the API relies on HTTPS for transport security

---

### 8.2.5 View User — `GET /api/admin/users/{id}`

Retrieves a single user's details by ID. Requires ADMIN role.

**Source:** `UserAdminController.java`

**Request:**

```http
GET /api/admin/users/USER0001 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter | Type   | Required | Description                 |
|-----------|--------|----------|-----------------------------|
| `id`      | String | Yes      | User identifier (8 chars)   |

**Responses:**

| Status | Description                                 |
|--------|---------------------------------------------|
| 200    | Returns user details as `UserSecurityDto`   |
| 404    | User not found                              |
| 401    | Unauthorized                                |
| 403    | Forbidden — requires ADMIN role             |

**Response 200 — `UserSecurityDto`:**

Returns a single `UserSecurityDto` object with the same fields as user list items (`secUsrId`, `secUsrFname`, `secUsrLname`, `secUsrType`).

---

### 8.3 Update User — `PUT /api/admin/users/{userId}`

Updates an existing user's information. Replaces the CICS COUSR02 BMS user update screen.

**Source:** `COUSR02.bms` / `COUSR02.CPY` → `UserAdminController.java`

**Request:**

```http
PUT /api/admin/users/JSMITH01 HTTP/1.1
Authorization: Basic <base64(userId:password)>
Content-Type: application/json

{
  "secUsrFname": "JANE",
  "secUsrLname": "SMITH-JONES",
  "secUsrPwd": "NewP@ss1",
  "secUsrType": "A"
}
```

**Path Parameters:**

| Parameter | Type   | Required | Format  | Description                       | BMS Source                      |
|-----------|--------|----------|---------|-----------------------------------|---------------------------------|
| `userId`  | String | Yes      | 8 chars | User identifier to update         | `USRIDIN` PIC X(8) IC/UNPROT   |

**Request Body — `UserSecurityDto`:**

| Field         | Type   | Required | Max Length | Description                            | BMS Source                       |
|---------------|--------|----------|------------|----------------------------------------|----------------------------------|
| `secUsrFname` | String | Yes      | 20         | First name                             | `FNAME` PIC X(20) UNPROT        |
| `secUsrLname` | String | Yes      | 20         | Last name                              | `LNAME` PIC X(20) UNPROT        |
| `secUsrPwd`   | String | No       | 8          | New password (omit to keep current)    | `PASSWD` PIC X(8) DRK/UNPROT    |
| `secUsrType`  | String | Yes      | 1          | User type (A=Admin, U=User)            | `USRTYPE` PIC X(1) UNPROT       |

**Responses:**

| Status | Description                                               |
|--------|-----------------------------------------------------------|
| 200    | User updated — returns updated `UserSecurityDto`          |
| 400    | Bad Request — validation error                            |
| 404    | User not found                                            |
| 401    | Unauthorized — missing or invalid credentials                   |
| 403    | Forbidden — requires ADMIN role                           |

**Implementation Notes:**
- The COUSR02.bms screen has a two-phase interaction: first ENTER fetches the user by ID (`USRIDIN`), then subsequent edits are saved via F3 (Save & Exit) or F5 (Save). The REST API combines these into a single PUT operation
- The `secUsrPwd` field is optional on update — if omitted, the existing password hash is preserved
- The `PASSWD` field uses the `DRK` attribute and "(8 Char)" hint, same as the add screen

---

### 8.4 Delete User — `DELETE /api/admin/users/{userId}`

Deletes a user account. Replaces the CICS COUSR03 BMS user delete screen.

**Source:** `COUSR03.bms` / `COUSR03.CPY` → `UserAdminController.java`

**Request:**

```http
DELETE /api/admin/users/JSMITH01 HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Path Parameters:**

| Parameter | Type   | Required | Format  | Description                        | BMS Source                      |
|-----------|--------|----------|---------|------------------------------------|---------------------------------|
| `userId`  | String | Yes      | 8 chars | User identifier to delete          | `USRIDIN` PIC X(8) IC/UNPROT   |

**Responses:**

| Status | Description                                     |
|--------|-------------------------------------------------|
| 204    | No Content — user deleted successfully           |
| 404    | User not found                                   |
| 401    | Unauthorized — missing or invalid credentials          |
| 403    | Forbidden — requires ADMIN role                  |

**Implementation Notes:**
- The COUSR03.bms screen displays the user details (FNAME, LNAME, USRTYPE all as ASKIP/read-only) before confirming deletion via F5=Delete. The REST DELETE operation is idempotent
- User details are returned as display-only in the BMS map (ASKIP attribute for FNAME, LNAME, USRTYPE) — the REST API requires no request body

---

## 9. Menus

### 9.1 Main Menu — `GET /api/menu/main`

Returns the main menu options available to the current user. Replaces the CICS COMEN01 BMS main menu screen.

**Source:** `COMEN01.bms` / `COMEN02Y.cpy` → `MenuController.java`

**Request:**

```http
GET /api/menu/main HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Responses:**

| Status | Description                                |
|--------|--------------------------------------------|
| 200    | Returns array of available menu options    |
| 401    | Unauthorized — missing or invalid credentials    |

**Response 200:**

```json
[
  { "optionNumber": 1,  "optionName": "Account View",       "cobolProgram": "COACTVWC", "apiEndpoint": "/api/accounts/{id}",           "requiredUserType": "U" },
  { "optionNumber": 2,  "optionName": "Account Update",     "cobolProgram": "COACTUPC", "apiEndpoint": "/api/accounts/{id}",           "requiredUserType": "U" },
  { "optionNumber": 3,  "optionName": "Credit Card List",   "cobolProgram": "COCRDLIC", "apiEndpoint": "/api/cards",                   "requiredUserType": "U" },
  { "optionNumber": 4,  "optionName": "Credit Card View",   "cobolProgram": "COCRDSLC", "apiEndpoint": "/api/cards/{id}",              "requiredUserType": "U" },
  { "optionNumber": 5,  "optionName": "Credit Card Update", "cobolProgram": "COCRDUPC", "apiEndpoint": "/api/cards/{id}",              "requiredUserType": "U" },
  { "optionNumber": 6,  "optionName": "Transaction List",   "cobolProgram": "COTRN00C", "apiEndpoint": "/api/transactions",            "requiredUserType": "U" },
  { "optionNumber": 7,  "optionName": "Transaction View",   "cobolProgram": "COTRN01C", "apiEndpoint": "/api/transactions/{id}",       "requiredUserType": "U" },
  { "optionNumber": 8,  "optionName": "Transaction Add",    "cobolProgram": "COTRN02C", "apiEndpoint": "/api/transactions",            "requiredUserType": "U" },
  { "optionNumber": 9,  "optionName": "Bill Payment",       "cobolProgram": "COBIL00C", "apiEndpoint": "/api/billing/pay",             "requiredUserType": "U" },
  { "optionNumber": 10, "optionName": "Reports",            "cobolProgram": "CORPT00C", "apiEndpoint": "/api/reports/submit",          "requiredUserType": "U" }
]
```

**Menu Option Fields (flat array of `MenuOption` records):**

| Field              | Type    | Description                                          | BMS Source                         |
|--------------------|---------|------------------------------------------------------|------------------------------------|
| `optionNumber`     | Integer | Option number (user enters to select)                | `OPTION` PIC X(2) NUM/UNPROT      |
| `optionName`       | String  | Menu option display label                            | `OPTNnnn` PIC X(40) ASKIP         |
| `cobolProgram`     | String  | Original COBOL program name                          | From COMEN02Y.cpy option table     |
| `apiEndpoint`      | String  | Mapped REST API endpoint path                        | Java controller mapping            |
| `requiredUserType` | String  | Required user type (U=any, A=admin)                  | From COMEN02Y.cpy user type field  |

**Implementation Notes:**
- The COMEN01.bms screen has 12 option display slots (`OPTN001`–`OPTN012`, each 40 chars, ASKIP) and a numeric input field (`OPTION`, 2 chars, NUM/UNPROT with right-justify zero-fill)
- The 10 menu options are populated from the `COMEN02Y.cpy` copybook at runtime — the REST API returns the same options as structured data
- The `transactionId` field maps to REST endpoint paths for client-side routing

---

### 9.2 Admin Menu — `GET /api/menu/admin`

Returns the admin menu options. Requires ADMIN role. Replaces the CICS COADM01 BMS admin menu screen.

**Source:** `COADM01.bms` / `COADM02Y.cpy` → `MenuController.java`

**Request:**

```http
GET /api/menu/admin HTTP/1.1
Authorization: Basic <base64(userId:password)>
```

**Responses:**

| Status | Description                                |
|--------|--------------------------------------------|
| 200    | Returns array of admin menu options        |
| 401    | Unauthorized — missing or invalid credentials    |
| 403    | Forbidden — requires ADMIN role            |

**Response 200:**

```json
[
  { "optionNumber": 1, "optionName": "User List",    "cobolProgram": "COUSR00C", "apiEndpoint": "/api/admin/users",       "requiredUserType": "A" },
  { "optionNumber": 2, "optionName": "User Add",     "cobolProgram": "COUSR01C", "apiEndpoint": "/api/admin/users",       "requiredUserType": "A" },
  { "optionNumber": 3, "optionName": "User Update",  "cobolProgram": "COUSR02C", "apiEndpoint": "/api/admin/users/{id}",  "requiredUserType": "A" },
  { "optionNumber": 4, "optionName": "User Delete",  "cobolProgram": "COUSR03C", "apiEndpoint": "/api/admin/users/{id}",  "requiredUserType": "A" }
]
```

**Implementation Notes:**
- The COADM01.bms screen has the same 12 option display slots as the main menu but is populated from the `COADM02Y.cpy` copybook with 4 admin-specific options
- The `OPTION` input field is identical (2 chars, NUM/UNPROT, right-justify zero-fill)
- Access is restricted to users with `userType = 'A'` (ADMIN role)

---

## 10. Security and Headers

### 10.1 Authentication Header

All endpoints except `POST /api/auth/signin` require HTTP Basic Authentication:

```
Authorization: Basic <base64(userId:password)>
```

Example with `curl -u` (which automatically encodes the Basic header):

```bash
curl -u USER0001:PASSWORDU http://localhost:8080/api/accounts/00000000013
```

Requests without a valid token receive HTTP 401 Unauthorized.

### 10.2 Correlation ID

Every request generates or propagates a unique correlation ID via the `X-Correlation-ID` header:

- **Request:** If the client provides `X-Correlation-ID`, that value is used
- **Response:** The `X-Correlation-ID` header is always included in responses
- **Logging:** The correlation ID is injected into the MDC (Mapped Diagnostic Context) and appears in all structured log entries for the request lifecycle

```
X-Correlation-ID: 550e8400-e29b-41d4-a716-446655440000
```

### 10.3 CORS Configuration

Cross-Origin Resource Sharing headers are configurable via `application.yml`:

| Header                         | Default Value       |
|--------------------------------|---------------------|
| `Access-Control-Allow-Origin`  | `*` (configurable)  |
| `Access-Control-Allow-Methods` | `GET, POST, PUT, DELETE, OPTIONS` |
| `Access-Control-Allow-Headers` | `Authorization, Content-Type, X-Correlation-ID` |
| `Access-Control-Max-Age`       | `3600`              |

### 10.4 Role-Based Access Control

| Endpoint Pattern        | Required Role | Description                           |
|-------------------------|---------------|---------------------------------------|
| `POST /api/auth/signin` | None          | Public authentication endpoint        |
| `/api/admin/**`         | `ADMIN`       | User administration operations        |
| `/api/menu/admin`       | `ADMIN`       | Admin menu access                     |
| All other `/api/**`     | `USER` or `ADMIN` | Standard authenticated access     |

### 10.5 Rate Limiting

Rate limiting is configurable per endpoint group:

| Endpoint Group    | Default Limit           |
|-------------------|-------------------------|
| `/api/auth/**`    | 10 requests/minute/IP   |
| `/api/admin/**`   | 100 requests/minute     |
| `/api/**` (other) | 1000 requests/minute    |

---

## 11. Key Constraints and Validation Rules

### 11.1 Decimal Precision (AAP §0.8.2)

All monetary amounts are represented as **string values** preserving `BigDecimal` precision:

- No `float` or `double` representations — all financial fields originate from COBOL `COMP-3`/`COMP` PIC clauses with exact decimal positions
- Example: `PIC S9(7)V99` → `BigDecimal` with scale 2 → JSON string `"12345.67"`
- Comparison semantics use `BigDecimal.compareTo()` — not `equals()` — to avoid scale-sensitivity issues
- Interest calculations use `(balance × rate) / 1200` with `RoundingMode.HALF_EVEN` (banker's rounding)

### 11.2 Field Length Constraints

All field lengths in request bodies are enforced to match the original BMS field definitions:

| Field Category          | Max Length | Source                     |
|-------------------------|------------|----------------------------|
| User ID                 | 8          | `USERID` PIC X(8)         |
| Password                | 8          | `PASSWD` PIC X(8)         |
| Account ID              | 11         | `ACCTSID` PIC 9(11)       |
| Card Number             | 16         | `CARDSID` PIC X(16)       |
| Transaction ID          | 16         | `TRNID` PIC X(16)         |
| Customer Name (each)    | 25         | `ACSxNAMI` PIC X(25)      |
| Address Line            | 50         | `ACSADLnI` PIC X(50)      |
| City                    | 50         | `ACSCITYI` PIC X(50)      |
| State                   | 2          | `ACSSTTEI` PIC X(2)       |
| ZIP Code                | 5/10       | `ACSZIPCI`/`MZIP`         |
| Country                 | 3          | `ACSCTRYI` PIC X(3)       |
| Phone Number            | 13         | `ACSPHNnI` PIC X(13)      |
| SSN                     | 12         | `ACSTSSNI` PIC X(12)      |
| FICO Score              | 3          | `ACSTFCOI` PIC X(3)       |
| Transaction Description | 60         | `TDESC` PIC X(60)         |
| Merchant Name           | 30         | `MNAME` PIC X(30)         |
| Merchant City           | 25         | `MCITY` PIC X(25)         |
| Card Name               | 50         | `CRDNAME` PIC X(50)       |
| Error Message           | 78         | `ERRMSG` PIC X(78)        |

### 11.3 Pagination Defaults

Pagination defaults match the original BMS screen row counts:

| Endpoint                   | Default Page Size | BMS Source                        |
|----------------------------|-------------------|-----------------------------------|
| `GET /api/cards`           | 7                 | 7 data rows in COCRDLI.bms       |
| `GET /api/transactions`    | 10                | 10 data rows in COTRN00.bms      |
| `GET /api/admin/users`     | 10                | 10 data rows in COUSR00.bms      |

### 11.4 Validation Rules (from COBOL Programs)

The following validation rules are preserved from the original COBOL business logic:

| Rule                    | Description                                          | Source Program     |
|-------------------------|------------------------------------------------------|--------------------|
| NANPA Area Codes        | Phone area codes validated against NANPA lookup table | CSLKPCDY.cpy       |
| US State Codes          | State abbreviations validated against 50 states + territories | CSLKPCDY.cpy |
| State/ZIP Combinations  | ZIP code prefixes validated against expected state ranges | CSLKPCDY.cpy   |
| FICO Score Range        | FICO scores must be in range 300-850                 | COACTUPC.cbl       |
| Date Validation         | All dates validated via `DateValidationService` (replaces LE CEEDAYS) | CSUTLDTC.cbl |
| Account Status          | Account status must be Y (Active) or N (Inactive)   | COACTUPC.cbl       |
| Card Status             | Card status must be Y (Active) or N (Inactive)      | COCRDUPC.cbl       |
| User Type               | User type must be A (Admin) or U (User)              | COUSR01C.cbl       |
| Transaction Amount      | Must be valid signed decimal (range: -99999999.99 to 99999999.99) | COTRN02C.cbl |
| Confirmation Required   | Bill payment and transaction add require explicit Y/N confirmation | COBIL00C.cbl, COTRN02C.cbl |

### 11.5 Batch File I/O Contracts

The following fixed-width file format contracts are used by the batch pipeline for file staging via S3:

| File Type              | Record Length | Key Fields                          | S3 Bucket                  |
|------------------------|---------------|-------------------------------------|----------------------------|
| Daily Transaction      | 350 bytes     | Transaction ID (pos 1-16), Amount (pos 49-60) | `carddemo-batch-input`  |
| Rejection File         | 350+ bytes    | Original record + reject code trailer | `carddemo-batch-output`  |
| Statement Output (Text)| Variable      | Account-grouped, page-formatted     | `carddemo-statements`     |
| Statement Output (HTML)| Variable      | Account-grouped, HTML-formatted     | `carddemo-statements`     |
| Transaction Report     | Variable      | Date-filtered, page/account/grand totals | `carddemo-batch-output` |

---

## 12. BMS-to-REST Endpoint Mapping

Complete mapping of every BMS mapset to its corresponding REST endpoint:

| BMS Mapset      | BMS Copybook     | COBOL Program   | REST Endpoint                         | HTTP Method | Controller              |
|-----------------|------------------|-----------------|---------------------------------------|-------------|-------------------------|
| `COSGN00.bms`   | `COSGN00.CPY`    | `COSGN00C.cbl`  | `/api/auth/signin`                    | POST        | `AuthController`        |
| `COACTVW.bms`   | `COACTVW.CPY`    | `COACTVWC.cbl`  | `/api/accounts/{accountId}`           | GET         | `AccountController`     |
| `COACTUP.bms`   | `COACTUP.CPY`    | `COACTUPC.cbl`  | `/api/accounts/{accountId}`           | PUT         | `AccountController`     |
| `COCRDLI.bms`   | `COCRDLI.CPY`    | `COCRDLIC.cbl`  | `/api/cards`                          | GET         | `CardController`        |
| `COCRDSL.bms`   | `COCRDSL.CPY`    | `COCRDSLC.cbl`  | `/api/cards/{cardNumber}`             | GET         | `CardController`        |
| `COCRDUP.bms`   | `COCRDUP.CPY`    | `COCRDUPC.cbl`  | `/api/cards/{cardNumber}`             | PUT         | `CardController`        |
| `COTRN00.bms`   | `COTRN00.CPY`    | `COTRN00C.cbl`  | `/api/transactions`                   | GET         | `TransactionController` |
| `COTRN01.bms`   | `COTRN01.CPY`    | `COTRN01C.cbl`  | `/api/transactions/{transactionId}`   | GET         | `TransactionController` |
| `COTRN02.bms`   | `COTRN02.CPY`    | `COTRN02C.cbl`  | `/api/transactions`                   | POST        | `TransactionController` |
| `COBIL00.bms`   | `COBIL00.CPY`    | `COBIL00C.cbl`  | `/api/billing/pay`                    | POST        | `BillingController`     |
| `CORPT00.bms`   | `CORPT00.CPY`    | `CORPT00C.cbl`  | `/api/reports/submit`                 | POST        | `ReportController`      |
| `COUSR00.bms`   | `COUSR00.CPY`    | `COUSR00C.cbl`  | `/api/admin/users`                    | GET         | `UserAdminController`   |
| `COUSR01.bms`   | `COUSR01.CPY`    | `COUSR01C.cbl`  | `/api/admin/users`                    | POST        | `UserAdminController`   |
| `COUSR02.bms`   | `COUSR02.CPY`    | `COUSR02C.cbl`  | `/api/admin/users/{userId}`           | PUT         | `UserAdminController`   |
| `COUSR03.bms`   | `COUSR03.CPY`    | `COUSR03C.cbl`  | `/api/admin/users/{userId}`           | DELETE      | `UserAdminController`   |
| `COMEN01.bms`   | `COMEN01.CPY`    | `COMEN01C.cbl`  | `/api/menu/main`                      | GET         | `MenuController`        |
| `COADM01.bms`   | `COADM01.CPY`    | `COADM01C.cbl`  | `/api/menu/admin`                     | GET         | `MenuController`        |

---

> **Document Version:** 1.0
> **Source Repository:** aws-samples/carddemo (commit `27d6c6f`)
> **Target Application:** CardDemo Java — Spring Boot 3.x REST API
> **Generated from:** 17 BMS mapset definitions + 17 symbolic map copybooks
