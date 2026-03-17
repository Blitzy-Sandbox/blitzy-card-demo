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
  - [4.2 View Card Detail](#42-view-card-detail---get-apicardscardnumber)
  - [4.3 Update Card](#43-update-card---put-apicardscardnumber)
- [5. Transactions](#5-transactions)
  - [5.1 List Transactions](#51-list-transactions---get-apitransactions)
  - [5.2 View Transaction Detail](#52-view-transaction-detail---get-apitransactionstransactionid)
  - [5.3 Add Transaction](#53-add-transaction---post-apitransactions)
- [6. Billing](#6-billing)
  - [6.1 Pay Bill](#61-pay-bill---post-apibillingpay)
- [7. Reports](#7-reports)
  - [7.1 Submit Report](#71-submit-report---post-apireportssubmit)
- [8. User Administration](#8-user-administration)
  - [8.1 List Users](#81-list-users---get-apiadminusers)
  - [8.2 Add User](#82-add-user---post-apiadminusers)
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

All endpoints except `POST /api/auth/signin` require a valid Bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

The token is obtained from the sign-in response. See [Section 2.1](#21-sign-in---post-apiauthsignin).

### 1.4 Standard Error Response

All error responses follow this structure:

```json
{
  "timestamp": "2026-03-17T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Descriptive error message",
  "path": "/api/accounts/12345678901"
}
```

| Field       | Type    | Description                                          |
|-------------|---------|------------------------------------------------------|
| `timestamp` | String  | ISO 8601 timestamp of the error occurrence           |
| `status`    | Integer | HTTP status code                                     |
| `error`     | String  | HTTP status reason phrase                            |
| `message`   | String  | Human-readable error description                     |
| `path`      | String  | Request URI that produced the error                  |

### 1.5 Pagination Wrapper

All paginated list endpoints return responses in this structure:

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 10,
  "totalElements": 100,
  "totalPages": 10
}
```

| Field           | Type    | Description                                      |
|-----------------|---------|--------------------------------------------------|
| `content`       | Array   | Array of resource objects for the current page    |
| `page`          | Integer | Zero-based page index                            |
| `size`          | Integer | Number of items per page                         |
| `totalElements` | Long    | Total number of matching items across all pages  |
| `totalPages`    | Integer | Total number of pages                            |

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
      "field": "cardNumber",
      "message": "must not be blank",
      "rejectedValue": ""
    },
    {
      "field": "amount",
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
  "password": "PASSWORD"
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
| 200    | Authentication successful — returns token, user type, and user details         |
| 401    | Unauthorized — invalid user ID or password                                     |
| 403    | Forbidden — account is locked or disabled                                      |
| 400    | Bad Request — missing required fields                                          |

**Response 200 — `SignOnResponse`:**

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "userType": "ADMIN",
  "firstName": "JOHN",
  "lastName": "DOE"
}
```

| Field       | Type   | Description                                                   | Source                     |
|-------------|--------|---------------------------------------------------------------|----------------------------|
| `token`     | String | Bearer token for subsequent API requests                      | Generated by Spring Security |
| `userType`  | String | User role: `ADMIN` or `USER`                                  | `SEC-USR-TYPE` from CSUSR01Y |
| `firstName` | String | User's first name                                             | `SEC-USR-FNAME` from CSUSR01Y |
| `lastName`  | String | User's last name                                              | `SEC-USR-LNAME` from CSUSR01Y |

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
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token                      |

**Response 200 — `AccountDto`:**

```json
{
  "accountId": "00000000013",
  "status": "Y",
  "openDate": "2014-08-15",
  "expiryDate": "2025-08-15",
  "reissueDate": "2022-08-15",
  "creditLimit": "5000.00",
  "cashLimit": "1500.00",
  "currentBalance": "1234.56",
  "cycleCredit": "500.00",
  "cycleDebit": "250.00",
  "addressGroup": "ADDR00001",
  "customer": {
    "customerId": "000000013",
    "firstName": "JOHN",
    "middleName": "M",
    "lastName": "DOE",
    "addressLine1": "123 MAIN STREET",
    "addressLine2": "APT 4B",
    "city": "NEW YORK",
    "state": "NY",
    "zipCode": "10001",
    "country": "USA",
    "phoneHome": "2125551234",
    "phoneWork": "2125555678",
    "ssn": "123-45-6789",
    "dateOfBirth": "1985-03-15",
    "ficoScore": "750",
    "governmentId": "DL123456789",
    "eftStatus": "ACTIVE",
    "primaryCardHolder": "Y"
  },
  "infoMessage": "",
  "errorMessage": ""
}
```

**Account Fields:**

| Field            | Type   | Max Length | Description                                           | BMS Source                                   |
|------------------|--------|------------|-------------------------------------------------------|----------------------------------------------|
| `accountId`      | String | 11         | Account identifier (numeric)                          | `ACCTSIDI` PIC 9(11)                         |
| `status`         | String | 1          | Account status code (Y=Active, N=Inactive)            | `ACSTTUSI` PIC X(1)                          |
| `openDate`       | String | 10         | Account open date (YYYY-MM-DD)                        | `ADTOPENI` PIC X(10)                         |
| `expiryDate`     | String | 10         | Account expiry date (YYYY-MM-DD)                      | `AEXPDTI` PIC X(10)                          |
| `reissueDate`    | String | 10         | Card reissue date (YYYY-MM-DD)                        | `AREISDTI` PIC X(10)                         |
| `creditLimit`    | String | 15         | Credit limit (formatted: `+ZZZ,ZZZ,ZZZ.99`)          | `ACRDLIMI` PIC X(15) PICOUT='+ZZZ,ZZZ,ZZZ.99' |
| `cashLimit`      | String | 15         | Cash advance limit (formatted)                        | `ACSHLIMI` PIC X(15) PICOUT='+ZZZ,ZZZ,ZZZ.99' |
| `currentBalance` | String | 15         | Current balance (formatted)                           | `ACURBALI` PIC X(15) PICOUT='+ZZZ,ZZZ,ZZZ.99' |
| `cycleCredit`    | String | 15         | Cycle credit total (formatted)                        | `ACRCYCRI` PIC X(15) PICOUT='+ZZZ,ZZZ,ZZZ.99' |
| `cycleDebit`     | String | 15         | Cycle debit total (formatted)                         | `ACRCYDBI` PIC X(15) PICOUT='+ZZZ,ZZZ,ZZZ.99' |
| `addressGroup`   | String | 10         | Address group identifier                              | `AADDGRPI` PIC X(10)                         |

**Customer Fields (nested `customer` object):**

| Field              | Type   | Max Length | Description                               | BMS Source                  |
|--------------------|--------|------------|-------------------------------------------|-----------------------------|
| `customerId`       | String | 9          | Customer identifier (numeric)             | `ACSTNUMI` PIC X(9)        |
| `firstName`        | String | 25         | Customer first name                       | `ACSFNAMI` PIC X(25)       |
| `middleName`       | String | 25         | Customer middle name                      | `ACSMNAMI` PIC X(25)       |
| `lastName`         | String | 25         | Customer last name                        | `ACSLNAMI` PIC X(25)       |
| `addressLine1`     | String | 50         | Primary address line                      | `ACSADL1I` PIC X(50)       |
| `addressLine2`     | String | 50         | Secondary address line                    | `ACSADL2I` PIC X(50)       |
| `city`             | String | 50         | City name                                 | `ACSCITYI` PIC X(50)       |
| `state`            | String | 2          | US state/territory abbreviation           | `ACSSTTEI` PIC X(2)        |
| `zipCode`          | String | 5          | ZIP code                                  | `ACSZIPCI` PIC X(5)        |
| `country`          | String | 3          | ISO 3166-1 alpha-3 country code           | `ACSCTRYI` PIC X(3)        |
| `phoneHome`        | String | 13         | Home phone number                         | `ACSPHN1I` PIC X(13)       |
| `phoneWork`        | String | 13         | Work phone number                         | `ACSPHN2I` PIC X(13)       |
| `ssn`              | String | 12         | Social Security Number (XXX-XX-XXXX)      | `ACSTSSNI` PIC X(12)       |
| `dateOfBirth`      | String | 10         | Date of birth (YYYY-MM-DD)                | `ACSTDOBI` PIC X(10)       |
| `ficoScore`        | String | 3          | FICO credit score (3-digit)               | `ACSTFCOI` PIC X(3)        |
| `governmentId`     | String | 20         | Government-issued ID number               | `ACSGOVTI` PIC X(20)       |
| `eftStatus`        | String | 10         | Electronic Fund Transfer status           | `ACSEFTCI` PIC X(10)       |
| `primaryCardHolder`| String | 1          | Primary card holder flag (Y/N)            | `ACSPFLGI` PIC X(1)        |

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
Authorization: Bearer <token>
Content-Type: application/json

{
  "accountId": "00000000013",
  "status": "Y",
  "openDate": {
    "year": "2014",
    "month": "08",
    "day": "15"
  },
  "expiryDate": {
    "year": "2025",
    "month": "08",
    "day": "15"
  },
  "reissueDate": {
    "year": "2022",
    "month": "08",
    "day": "15"
  },
  "creditLimit": "5000.00",
  "cashLimit": "1500.00",
  "currentBalance": "1234.56",
  "cycleCredit": "500.00",
  "cycleDebit": "250.00",
  "addressGroup": "ADDR00001",
  "customer": {
    "customerId": "000000013",
    "ssn": {
      "part1": "123",
      "part2": "45",
      "part3": "6789"
    },
    "dateOfBirth": {
      "year": "1985",
      "month": "03",
      "day": "15"
    },
    "ficoScore": "750",
    "firstName": "JOHN",
    "middleName": "M",
    "lastName": "DOE",
    "addressLine1": "123 MAIN STREET",
    "state": "NY",
    "addressLine2": "APT 4B",
    "zipCode": "10001",
    "city": "NEW YORK",
    "country": "USA",
    "phoneHome": "2125551234",
    "governmentId": "DL123456789",
    "phoneWork": "2125555678",
    "eftStatus": "ACTIVE",
    "primaryCardHolder": "Y"
  }
}
```

**Path Parameters:**

| Parameter   | Type   | Required | Format          | Description          |
|-------------|--------|----------|-----------------|----------------------|
| `accountId` | String | Yes      | 11 chars        | Account identifier   |

**Request Body Fields — Account:**

| Field            | Type   | Required | Max Length | Description                                 | BMS Source                           |
|------------------|--------|----------|------------|---------------------------------------------|--------------------------------------|
| `accountId`      | String | Yes      | 11         | Account identifier (must match path param)  | `ACCTSIDI` PIC X(11) UNPROT         |
| `status`         | String | Yes      | 1          | Account status (Y/N)                        | `ACSTTUSI` PIC X(1) UNPROT          |
| `openDate`       | Object | Yes      | —          | Open date as {year, month, day}             | `OPNYEARI(4)/OPNMONI(2)/OPNDAYI(2)` |
| `expiryDate`     | Object | Yes      | —          | Expiry date as {year, month, day}           | `EXPYEARI(4)/EXPMONI(2)/EXPDAYI(2)` |
| `reissueDate`    | Object | Yes      | —          | Reissue date as {year, month, day}          | `RISYEARI(4)/RISMONI(2)/RISDAYI(2)` |
| `creditLimit`    | String | Yes      | 15         | Credit limit (decimal string)               | `ACRDLIMI` PIC X(15) UNPROT         |
| `cashLimit`      | String | Yes      | 15         | Cash advance limit (decimal string)         | `ACSHLIMI` PIC X(15) UNPROT         |
| `currentBalance` | String | Yes      | 15         | Current account balance (decimal string)    | `ACURBALI` PIC X(15) UNPROT         |
| `cycleCredit`    | String | Yes      | 15         | Cycle credit total (decimal string)         | `ACRCYCRI` PIC X(15) UNPROT         |
| `cycleDebit`     | String | Yes      | 15         | Cycle debit total (decimal string)          | `ACRCYDBI` PIC X(15) UNPROT         |
| `addressGroup`   | String | Yes      | 10         | Address group identifier                    | `AADDGRPI` PIC X(10) UNPROT         |

**Date Subfield Format (used for openDate, expiryDate, reissueDate):**

| Field   | Type   | Max Length | Description                    | BMS Source                |
|---------|--------|------------|--------------------------------|---------------------------|
| `year`  | String | 4          | Four-digit year (e.g., "2025") | `*YEARI` PIC X(4)        |
| `month` | String | 2          | Two-digit month (01-12)        | `*MONI` PIC X(2)         |
| `day`   | String | 2          | Two-digit day (01-31)          | `*DAYI` PIC X(2)         |

**Request Body Fields — Customer (nested `customer` object):**

| Field              | Type   | Required | Max Length | Description                               | BMS Source                           |
|--------------------|--------|----------|------------|-------------------------------------------|--------------------------------------|
| `customerId`       | String | Yes      | 9          | Customer identifier (read-only context)   | `ACSTNUMI` PIC X(9) UNPROT          |
| `ssn`              | Object | Yes      | —          | SSN as {part1, part2, part3}              | `ACTSSN1I(3)/ACTSSN2I(2)/ACTSSN3I(4)` |
| `dateOfBirth`      | Object | Yes      | —          | DOB as {year, month, day}                 | `DOBYEARI(4)/DOBMONI(2)/DOBDAYI(2)` |
| `ficoScore`        | String | Yes      | 3          | FICO credit score (300-850)               | `ACSTFCOI` PIC X(3) UNPROT          |
| `firstName`        | String | Yes      | 25         | First name                                | `ACSFNAMI` PIC X(25) UNPROT         |
| `middleName`       | String | No       | 25         | Middle name                               | `ACSMNAMI` PIC X(25) UNPROT         |
| `lastName`         | String | Yes      | 25         | Last name                                 | `ACSLNAMI` PIC X(25) UNPROT         |
| `addressLine1`     | String | Yes      | 50         | Primary address line                      | `ACSADL1I` PIC X(50) UNPROT         |
| `state`            | String | Yes      | 2          | US state/territory code                   | `ACSSTTEI` PIC X(2) UNPROT          |
| `addressLine2`     | String | No       | 50         | Secondary address line                    | `ACSADL2I` PIC X(50) UNPROT         |
| `zipCode`          | String | Yes      | 5          | ZIP code                                  | `ACSZIPCI` PIC X(5) UNPROT          |
| `city`             | String | Yes      | 50         | City name                                 | `ACSCITYI` PIC X(50) UNPROT         |
| `country`          | String | Yes      | 3          | Country code                              | `ACSCTRYI` PIC X(3) UNPROT          |
| `phoneHome`        | String | No       | 13         | Home phone number                         | `ACSPHN1I` PIC X(13) UNPROT         |
| `governmentId`     | String | No       | 20         | Government-issued ID                      | `ACSGOVTI` PIC X(20) UNPROT         |
| `phoneWork`        | String | No       | 13         | Work phone number                         | `ACSPHN2I` PIC X(13) UNPROT         |
| `eftStatus`        | String | No       | 10         | EFT status                                | `ACSEFTCI` PIC X(10) UNPROT         |
| `primaryCardHolder`| String | No       | 1          | Primary card holder flag (Y/N)            | `ACSPFLGI` PIC X(1) UNPROT          |

**SSN Subfield Format:**

| Field   | Type   | Max Length | Description           | BMS Source            |
|---------|--------|------------|-----------------------|-----------------------|
| `part1` | String | 3          | Area number (3 digits)| `ACTSSN1I` PIC X(3)  |
| `part2` | String | 2          | Group number (2 digits)| `ACTSSN2I` PIC X(2) |
| `part3` | String | 4          | Serial number (4 digits)| `ACTSSN3I` PIC X(4)|

**Responses:**

| Status | Description                                                                      |
|--------|----------------------------------------------------------------------------------|
| 200    | Account updated successfully — returns updated `AccountDto`                      |
| 400    | Bad Request — validation error (invalid dates, FICO range, state/ZIP mismatch)   |
| 404    | Account not found                                                                |
| 409    | Conflict — concurrent modification detected (optimistic lock via `@Version`)     |
| 401    | Unauthorized — missing or invalid token                                          |

**Implementation Notes:**
- The original COACTUPC.cbl is the most complex program (4,236 lines) with `SYNCPOINT ROLLBACK` for dual-dataset (ACCTDAT + CUSTDAT) transactional integrity. The Java implementation uses `@Transactional` with rollback semantics
- Optimistic locking is enforced via JPA `@Version` annotation, replacing the CICS `READ UPDATE` snapshot comparison pattern
- Date fields are decomposed into year/month/day components in the BMS map (`OPNYEARI`/`OPNMONI`/`OPNDAYI`) — the API preserves this structure while also accepting flat ISO date strings
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
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token                  |

**Response 200 — Paginated `CardDto` list:**

```json
{
  "content": [
    {
      "selected": false,
      "accountId": "00000000013",
      "cardNumber": "4444111122223333",
      "cardStatus": "Y"
    }
  ],
  "page": 0,
  "size": 7,
  "totalElements": 15,
  "totalPages": 3
}
```

**Card List Item Fields:**

| Field        | Type    | Max Length | Description                                | BMS Source                     |
|--------------|---------|------------|--------------------------------------------|--------------------------------|
| `selected`   | Boolean | —          | Selection flag (for UI convenience)        | `CRDSELn` PIC X(1)            |
| `accountId`  | String  | 11         | Account identifier                         | `ACCTNOn` PIC X(11) ASKIP      |
| `cardNumber` | String  | 16         | Card number                                | `CRDNUMn` PIC X(16) ASKIP      |
| `cardStatus` | String  | 1          | Card status (Y=Active, N=Inactive)         | `CRDSTSn` PIC X(1) ASKIP       |

**Implementation Notes:**
- The original COCRDLI.bms displays 7 selectable rows per page with selection codes (U=Update, S=Select, D=Delete)
- Pagination default of 7 preserves the original BMS screen row count
- The `CRDSTPn` hidden fields (DRK attribute) in the BMS map carry internal state for the selection action — this is handled server-side in the REST API

---

### 4.2 View Card Detail — `GET /api/cards/{cardNumber}`

Retrieves detailed information for a single credit card. Replaces the CICS COCRDSL BMS card detail screen.

**Source:** `COCRDSL.bms` / `COCRDSL.CPY` → `CardController.java`

**Request:**

```http
GET /api/cards/4444111122223333 HTTP/1.1
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token    |

**Response 200 — `CardDto`:**

```json
{
  "accountId": "00000000013",
  "cardNumber": "4444111122223333",
  "cardName": "JOHN M DOE",
  "cardStatus": "Y",
  "expiryMonth": "08",
  "expiryYear": "2025"
}
```

**Card Detail Fields:**

| Field         | Type   | Max Length | Description                               | BMS Source                   |
|---------------|--------|------------|-------------------------------------------|------------------------------|
| `accountId`   | String | 11         | Associated account identifier             | `ACCTSID` PIC X(11)         |
| `cardNumber`  | String | 16         | Card number                               | `CARDSID` PIC X(16)         |
| `cardName`    | String | 50         | Name on card                              | `CRDNAME` PIC X(50) ASKIP   |
| `cardStatus`  | String | 1          | Card status (Y=Active, N=Inactive)        | `CRDSTCD` PIC X(1) ASKIP    |
| `expiryMonth` | String | 2          | Card expiry month (01-12)                 | `EXPMON` PIC X(2) ASKIP     |
| `expiryYear`  | String | 4          | Card expiry year (4-digit)                | `EXPYEAR` PIC X(4) ASKIP    |

---

### 4.3 Update Card — `PUT /api/cards/{cardNumber}`

Updates credit card information. Replaces the CICS COCRDUP BMS card update screen.

**Source:** `COCRDUP.bms` / `COCRDUP.CPY` → `CardController.java`

**Request:**

```http
PUT /api/cards/4444111122223333 HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json

{
  "cardName": "JOHN M DOE",
  "cardStatus": "Y",
  "expiryMonth": "12",
  "expiryYear": "2027"
}
```

**Path Parameters:**

| Parameter    | Type   | Required | Format   | Description           |
|--------------|--------|----------|----------|-----------------------|
| `cardNumber` | String | Yes      | 16 chars | Credit card number    |

**Request Body — `CardDto`:**

| Field         | Type   | Required | Max Length | Description                           | BMS Source                          |
|---------------|--------|----------|------------|---------------------------------------|-------------------------------------|
| `cardName`    | String | Yes      | 50         | Name printed on card                  | `CRDNAME` PIC X(50) UNPROT         |
| `cardStatus`  | String | Yes      | 1          | Card status (Y/N)                     | `CRDSTCD` PIC X(1) UNPROT          |
| `expiryMonth` | String | Yes      | 2          | Expiry month (01-12)                  | `EXPMON` PIC X(2) UNPROT           |
| `expiryYear`  | String | Yes      | 4          | Expiry year (4-digit)                 | `EXPYEAR` PIC X(4) UNPROT          |

> **Note:** The `accountId` field is PROT (protected/read-only) in the COCRDUP.bms screen and cannot be changed via this endpoint. The card's account association is immutable.

**Responses:**

| Status | Description                                                                |
|--------|----------------------------------------------------------------------------|
| 200    | Card updated successfully — returns updated `CardDto`                     |
| 400    | Bad Request — validation error                                            |
| 404    | Card not found                                                            |
| 409    | Conflict — concurrent modification (optimistic lock via `@Version`)       |
| 401    | Unauthorized — missing or invalid token                                   |

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
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token            |

**Response 200 — Paginated `TransactionDto` list:**

```json
{
  "content": [
    {
      "transactionId": "0000000000000001",
      "date": "2023-01-15",
      "description": "PURCHASE AT STORE",
      "amount": "125.50"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 50,
  "totalPages": 5
}
```

**Transaction List Item Fields:**

| Field            | Type    | Max Length | Description                              | BMS Source                        |
|------------------|---------|------------|------------------------------------------|-----------------------------------|
| `transactionId`  | String  | 16         | Transaction identifier                   | `TRNIDnn` PIC X(16) ASKIP         |
| `date`           | String  | 8          | Transaction date                         | `TDATEnn` PIC X(8) ASKIP          |
| `description`    | String  | 26         | Transaction description (truncated)      | `TDESCnn` PIC X(26) ASKIP         |
| `amount`         | String  | 12         | Transaction amount (decimal string)      | `TAMTnnn` PIC X(12) ASKIP         |

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
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token            |

**Response 200 — `TransactionDto`:**

```json
{
  "transactionId": "0000000000000001",
  "cardNumber": "4444111122223333",
  "typeCode": "01",
  "categoryCode": "5411",
  "source": "POS TERM",
  "description": "PURCHASE AT GROCERY STORE",
  "amount": "125.50",
  "originalDate": "2023-01-15",
  "processedDate": "2023-01-16",
  "merchantId": "M12345678",
  "merchantName": "ACME GROCERY INC",
  "merchantCity": "NEW YORK",
  "merchantZip": "10001"
}
```

**Transaction Detail Fields:**

| Field            | Type   | Max Length | Description                                    | BMS Source                    |
|------------------|--------|------------|------------------------------------------------|-------------------------------|
| `transactionId`  | String | 16         | Transaction identifier                         | `TRNID` PIC X(16) ASKIP      |
| `cardNumber`     | String | 16         | Associated card number                         | `CARDNUM` PIC X(16) ASKIP    |
| `typeCode`       | String | 2          | Transaction type code                          | `TTYPCD` PIC X(2) ASKIP      |
| `categoryCode`   | String | 4          | Transaction category code                      | `TCATCD` PIC X(4) ASKIP      |
| `source`         | String | 10         | Transaction source (POS TERM, OPERATOR, etc.)  | `TRNSRC` PIC X(10) ASKIP     |
| `description`    | String | 60         | Full transaction description                   | `TDESC` PIC X(60) ASKIP      |
| `amount`         | String | 12         | Transaction amount (decimal string)            | `TRNAMT` PIC X(12) ASKIP     |
| `originalDate`   | String | 10         | Original transaction date (YYYY-MM-DD)         | `TORIGDT` PIC X(10) ASKIP    |
| `processedDate`  | String | 10         | Processing date (YYYY-MM-DD)                   | `TPROCDT` PIC X(10) ASKIP    |
| `merchantId`     | String | 9          | Merchant identifier                            | `MID` PIC X(9) ASKIP         |
| `merchantName`   | String | 30         | Merchant name                                  | `MNAME` PIC X(30) ASKIP      |
| `merchantCity`   | String | 25         | Merchant city                                  | `MCITY` PIC X(25) ASKIP      |
| `merchantZip`    | String | 10         | Merchant ZIP code                              | `MZIP` PIC X(10) ASKIP       |

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
Authorization: Bearer <token>
Content-Type: application/json

{
  "accountId": "00000000013",
  "cardNumber": "4444111122223333",
  "typeCode": "01",
  "categoryCode": "5411",
  "source": "POS TERM",
  "description": "PURCHASE AT GROCERY STORE",
  "amount": "-125.50",
  "originalDate": "2023-01-15",
  "processedDate": "2023-01-16",
  "merchantId": "M12345678",
  "merchantName": "ACME GROCERY INC",
  "merchantCity": "NEW YORK",
  "merchantZip": "10001",
  "confirmed": true
}
```

**Request Body — `TransactionDto`:**

| Field            | Type    | Required | Max Length | Description                                         | BMS Source                          |
|------------------|---------|----------|------------|-----------------------------------------------------|-------------------------------------|
| `accountId`      | String  | Cond.    | 11         | Account ID (**required if `cardNumber` not given**)  | `ACTIDIN` PIC X(11) UNPROT         |
| `cardNumber`     | String  | Cond.    | 16         | Card number (**required if `accountId` not given**)  | `CARDNIN` PIC X(16) UNPROT "(or)"  |
| `typeCode`       | String  | Yes      | 2          | Transaction type code                               | `TTYPCD` PIC X(2) UNPROT           |
| `categoryCode`   | String  | Yes      | 4          | Transaction category code                           | `TCATCD` PIC X(4) UNPROT           |
| `source`         | String  | Yes      | 10         | Transaction source                                  | `TRNSRC` PIC X(10) UNPROT          |
| `description`    | String  | Yes      | 60         | Transaction description                             | `TDESC` PIC X(60) UNPROT           |
| `amount`         | String  | Yes      | 12         | Amount (hint: "-99999999.99", signed decimal)       | `TRNAMT` PIC X(12) UNPROT          |
| `originalDate`   | String  | Yes      | 10         | Original date (YYYY-MM-DD)                          | `TORIGDT` PIC X(10) UNPROT         |
| `processedDate`  | String  | No       | 10         | Processed date (YYYY-MM-DD)                         | `TPROCDT` PIC X(10) UNPROT         |
| `merchantId`     | String  | No       | 9          | Merchant identifier                                 | `MID` PIC X(9) UNPROT              |
| `merchantName`   | String  | No       | 30         | Merchant name                                       | `MNAME` PIC X(30) UNPROT           |
| `merchantCity`   | String  | No       | 25         | Merchant city                                       | `MCITY` PIC X(25) UNPROT           |
| `merchantZip`    | String  | No       | 10         | Merchant ZIP code                                   | `MZIP` PIC X(10) UNPROT            |
| `confirmed`      | Boolean | Yes      | —          | Confirmation flag (mirrors Y/N confirmation flow)   | `CONFIRM` PIC X(1) UNPROT "(Y/N)"  |

**Responses:**

| Status | Description                                                                     |
|--------|---------------------------------------------------------------------------------|
| 201    | Transaction created — returns `TransactionDto` with auto-generated ID           |
| 400    | Bad Request — validation error (invalid account, card, type, category, amount)  |
| 401    | Unauthorized — missing or invalid token                                         |

**Response 201 — Created `TransactionDto`:**

```json
{
  "transactionId": "0000000000000051",
  "accountId": "00000000013",
  "cardNumber": "4444111122223333",
  "typeCode": "01",
  "categoryCode": "5411",
  "source": "POS TERM",
  "description": "PURCHASE AT GROCERY STORE",
  "amount": "-125.50",
  "originalDate": "2023-01-15",
  "processedDate": "2023-01-16",
  "merchantId": "M12345678",
  "merchantName": "ACME GROCERY INC",
  "merchantCity": "NEW YORK",
  "merchantZip": "10001"
}
```

**Implementation Notes:**
- The `transactionId` is auto-generated using the browse-to-end + increment pattern (finds the maximum existing transaction ID and increments by 1), preserving the original COTRN02C.cbl auto-ID generation logic
- Either `accountId` or `cardNumber` must be provided; if only `accountId` is supplied, the system resolves the associated card via cross-reference lookup (CXACAIX)
- The BMS screen label "(or)" next to the card number field indicates that `accountId` and `cardNumber` are alternative identifiers
- The `confirmed` field mirrors the original BMS confirmation flow — the COTRN02.bms screen displays "You are about to add this transaction. Please confirm :" with a Y/N input. Set `confirmed: true` to proceed, or `confirmed: false` for a dry-run validation
- Amount format hint "-99999999.99" indicates support for signed decimal values; negative amounts represent debits
- Function key F5 ("Copy Last Tran.") from the BMS map is not directly represented in the REST API; clients should implement this as a client-side copy operation

---

## 6. Billing

### 6.1 Pay Bill — `POST /api/billing/pay`

Processes a bill payment against an account. Replaces the CICS COBIL00 BMS bill payment screen.

**Source:** `COBIL00.bms` / `COBIL00.CPY` → `BillingController.java`

**Request:**

```http
POST /api/billing/pay HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json

{
  "accountId": "00000000013",
  "confirmed": true
}
```

**Request Body — `BillPaymentRequest`:**

| Field       | Type    | Required | Max Length | Description                                    | BMS Source                      |
|-------------|---------|----------|------------|------------------------------------------------|---------------------------------|
| `accountId` | String  | Yes      | 11         | Account identifier (numeric)                   | `ACTIDIN` PIC X(11) UNPROT     |
| `confirmed` | Boolean | Yes      | —          | Confirmation flag (mirrors Y/N flow)           | `CONFIRM` PIC X(1) UNPROT      |

**Responses:**

| Status | Description                                                                         |
|--------|-------------------------------------------------------------------------------------|
| 200    | Payment processed — returns confirmation with updated balance                       |
| 400    | Bad Request — account not found, invalid state, or confirmation not provided        |
| 401    | Unauthorized — missing or invalid token                                             |

**Response 200:**

```json
{
  "accountId": "00000000013",
  "previousBalance": "1234.56",
  "paymentAmount": "1234.56",
  "newBalance": "0.00",
  "message": "Payment processed successfully"
}
```

| Field             | Type   | Description                                 | BMS Source               |
|-------------------|--------|---------------------------------------------|--------------------------|
| `accountId`       | String | Account identifier                          | `ACTIDIN`                |
| `previousBalance` | String | Balance before payment (decimal string)     | `CURBAL` PIC X(14) ASKIP |
| `paymentAmount`   | String | Amount paid (decimal string)                | Computed                 |
| `newBalance`      | String | Balance after payment (decimal string)      | Computed                 |
| `message`         | String | Confirmation message                        | `ERRMSG`/`INFOMSG`      |

**Implementation Notes:**
- The original COBIL00.bms screen displays the current balance (`CURBAL`, 14 chars, ASKIP) as a read-only field, with only the account ID input and a Y/N confirmation
- The account balance update and transaction record creation occur within a single `@Transactional` operation
- All monetary values use `BigDecimal` string representation

---

## 7. Reports

### 7.1 Submit Report — `POST /api/reports/submit`

Submits a report generation request to the batch processing queue. Replaces the CICS CORPT00 BMS report selection screen.

**Source:** `CORPT00.bms` / `CORPT00.CPY` → `ReportController.java`

**Request:**

```http
POST /api/reports/submit HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json

{
  "reportType": "CUSTOM",
  "startMonth": "01",
  "startDay": "15",
  "startYear": "2023",
  "endMonth": "12",
  "endDay": "31",
  "endYear": "2023",
  "confirmed": true
}
```

**Request Body — `ReportRequest`:**

| Field        | Type    | Required | Max Length | Description                                    | BMS Source                          |
|--------------|---------|----------|------------|------------------------------------------------|-------------------------------------|
| `reportType` | String  | Yes      | —          | Report type: `MONTHLY`, `YEARLY`, or `CUSTOM`  | `MONTHLY(1)/YEARLY(1)/CUSTOM(1)` radio selection |
| `startMonth` | String  | Cond.    | 2          | Start month (required for `CUSTOM`)            | `SDTMM` PIC X(2) NUM               |
| `startDay`   | String  | Cond.    | 2          | Start day (required for `CUSTOM`)              | `SDTDD` PIC X(2) NUM               |
| `startYear`  | String  | Cond.    | 4          | Start year (required for `CUSTOM`)             | `SDTYYYY` PIC X(4) NUM             |
| `endMonth`   | String  | Cond.    | 2          | End month (required for `CUSTOM`)              | `EDTMM` PIC X(2) NUM               |
| `endDay`     | String  | Cond.    | 2          | End day (required for `CUSTOM`)                | `EDTDD` PIC X(2) NUM               |
| `endYear`    | String  | Cond.    | 4          | End year (required for `CUSTOM`)               | `EDTYYYY` PIC X(4) NUM             |
| `confirmed`  | Boolean | Yes      | —          | Submission confirmation (mirrors Y/N flow)     | `CONFIRM` PIC X(1) UNPROT          |

**Responses:**

| Status | Description                                                            |
|--------|------------------------------------------------------------------------|
| 202    | Accepted — report job submitted to SQS queue for batch processing      |
| 400    | Bad Request — invalid report type, missing or invalid date range       |
| 401    | Unauthorized — missing or invalid token                                |

**Response 202:**

```json
{
  "jobId": "rpt-20230115-001",
  "status": "SUBMITTED",
  "reportType": "CUSTOM",
  "message": "Report generation job submitted successfully"
}
```

| Field        | Type   | Description                              |
|--------------|--------|------------------------------------------|
| `jobId`      | String | Unique job identifier for tracking       |
| `status`     | String | Job status (`SUBMITTED`)                 |
| `reportType` | String | Echoed report type                       |
| `message`    | String | Confirmation message                     |

**Implementation Notes:**
- The original CORPT00C.cbl uses CICS TDQ `WRITEQ` to submit the report job to the JOBS queue for JES batch submission. The Java implementation publishes an SQS message to the `carddemo-report-jobs.fifo` queue (Decision D-004)
- The BMS screen presents three radio-style selection fields (`MONTHLY`, `YEARLY`, `CUSTOM`) — each is a single-character UNPROT field where entering any non-space value selects that option. The API enum value replaces this pattern
- Custom date range fields use MM/DD/YYYY format in the original BMS screen; the API accepts separate month/day/year components matching the BMS field decomposition
- Returns HTTP 202 (Accepted) because report generation is asynchronous — the actual report is produced by the batch pipeline and stored in S3

---

## 8. User Administration

All user administration endpoints require the `ADMIN` role. Regular users will receive a 403 Forbidden response.

### 8.1 List Users — `GET /api/admin/users`

Retrieves a paginated list of system users. Replaces the CICS COUSR00 BMS user list screen.

**Source:** `COUSR00.bms` / `COUSR00.CPY` → `UserAdminController.java`

**Request:**

```http
GET /api/admin/users?userId=USER&page=0&size=10 HTTP/1.1
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token        |
| 403    | Forbidden — requires ADMIN role                |

**Response 200 — Paginated `UserSecurityDto` list:**

```json
{
  "content": [
    {
      "userId": "USER0001",
      "firstName": "JOHN",
      "lastName": "DOE",
      "userType": "A"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 25,
  "totalPages": 3
}
```

**User List Item Fields:**

| Field       | Type   | Max Length | Description                        | BMS Source                      |
|-------------|--------|------------|------------------------------------|---------------------------------|
| `userId`    | String | 8          | User identifier                    | `USRIDnn` PIC X(8) ASKIP        |
| `firstName` | String | 20         | First name                         | `FNAMEnn` PIC X(20) ASKIP       |
| `lastName`  | String | 20         | Last name                          | `LNAMEnn` PIC X(20) ASKIP       |
| `userType`  | String | 1          | User type (A=Admin, U=User)        | `UTYPEnn` PIC X(1) ASKIP        |

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
Authorization: Bearer <token>
Content-Type: application/json

{
  "firstName": "JANE",
  "lastName": "SMITH",
  "userId": "JSMITH01",
  "password": "P@ssw0rd",
  "userType": "U"
}
```

**Request Body — `UserSecurityDto`:**

| Field       | Type   | Required | Max Length | Description                        | BMS Source                              |
|-------------|--------|----------|------------|------------------------------------|-----------------------------------------|
| `firstName` | String | Yes      | 20         | First name                         | `FNAME` PIC X(20) IC/UNPROT            |
| `lastName`  | String | Yes      | 20         | Last name                          | `LNAME` PIC X(20) UNPROT               |
| `userId`    | String | Yes      | 8          | User ID (unique, hint: "8 Char")   | `USERID` PIC X(8) UNPROT               |
| `password`  | String | Yes      | 8          | Password (hint: "8 Char")          | `PASSWD` PIC X(8) DRK/UNPROT           |
| `userType`  | String | Yes      | 1          | User type (A=Admin, U=User)        | `USRTYPE` PIC X(1) UNPROT "(A=Admin, U=User)" |

**Responses:**

| Status | Description                                                     |
|--------|-----------------------------------------------------------------|
| 201    | User created — returns `UserSecurityDto` (password excluded)    |
| 400    | Bad Request — validation error (missing fields, invalid type)   |
| 409    | Conflict — user ID already exists                               |
| 401    | Unauthorized — missing or invalid token                         |
| 403    | Forbidden — requires ADMIN role                                 |

**Response 201:**

```json
{
  "userId": "JSMITH01",
  "firstName": "JANE",
  "lastName": "SMITH",
  "userType": "U"
}
```

**Implementation Notes:**
- Password is stored as a BCrypt hash (Decision D-002) — never returned in responses
- The BMS screen provides hints "(8 Char)" next to both USERID and PASSWD fields; validation enforces this constraint
- The `PASSWD` field uses the `DRK` (dark) BMS attribute to hide input; the API relies on HTTPS for transport security

---

### 8.3 Update User — `PUT /api/admin/users/{userId}`

Updates an existing user's information. Replaces the CICS COUSR02 BMS user update screen.

**Source:** `COUSR02.bms` / `COUSR02.CPY` → `UserAdminController.java`

**Request:**

```http
PUT /api/admin/users/JSMITH01 HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json

{
  "firstName": "JANE",
  "lastName": "SMITH-JONES",
  "password": "NewP@ss1",
  "userType": "A"
}
```

**Path Parameters:**

| Parameter | Type   | Required | Format  | Description                       | BMS Source                      |
|-----------|--------|----------|---------|-----------------------------------|---------------------------------|
| `userId`  | String | Yes      | 8 chars | User identifier to update         | `USRIDIN` PIC X(8) IC/UNPROT   |

**Request Body — `UserSecurityDto`:**

| Field       | Type   | Required | Max Length | Description                            | BMS Source                       |
|-------------|--------|----------|------------|----------------------------------------|----------------------------------|
| `firstName` | String | Yes      | 20         | First name                             | `FNAME` PIC X(20) UNPROT        |
| `lastName`  | String | Yes      | 20         | Last name                              | `LNAME` PIC X(20) UNPROT        |
| `password`  | String | No       | 8          | New password (omit to keep current)    | `PASSWD` PIC X(8) DRK/UNPROT    |
| `userType`  | String | Yes      | 1          | User type (A=Admin, U=User)            | `USRTYPE` PIC X(1) UNPROT       |

**Responses:**

| Status | Description                                               |
|--------|-----------------------------------------------------------|
| 200    | User updated — returns updated `UserSecurityDto`          |
| 400    | Bad Request — validation error                            |
| 404    | User not found                                            |
| 401    | Unauthorized — missing or invalid token                   |
| 403    | Forbidden — requires ADMIN role                           |

**Implementation Notes:**
- The COUSR02.bms screen has a two-phase interaction: first ENTER fetches the user by ID (`USRIDIN`), then subsequent edits are saved via F3 (Save & Exit) or F5 (Save). The REST API combines these into a single PUT operation
- The `password` field is optional on update — if omitted, the existing password hash is preserved
- The `PASSWD` field uses the `DRK` attribute and "(8 Char)" hint, same as the add screen

---

### 8.4 Delete User — `DELETE /api/admin/users/{userId}`

Deletes a user account. Replaces the CICS COUSR03 BMS user delete screen.

**Source:** `COUSR03.bms` / `COUSR03.CPY` → `UserAdminController.java`

**Request:**

```http
DELETE /api/admin/users/JSMITH01 HTTP/1.1
Authorization: Bearer <token>
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
| 401    | Unauthorized — missing or invalid token          |
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
Authorization: Bearer <token>
```

**Responses:**

| Status | Description                                |
|--------|--------------------------------------------|
| 200    | Returns array of available menu options    |
| 401    | Unauthorized — missing or invalid token    |

**Response 200:**

```json
{
  "menuTitle": "Main Menu",
  "options": [
    { "number": 1,  "label": "View Account",           "transactionId": "COACTVW" },
    { "number": 2,  "label": "Update Account",          "transactionId": "COACTUP" },
    { "number": 3,  "label": "View Credit Card",        "transactionId": "COCRDSL" },
    { "number": 4,  "label": "Credit Card List",        "transactionId": "COCRDLI" },
    { "number": 5,  "label": "Update Credit Card",      "transactionId": "COCRDUP" },
    { "number": 6,  "label": "View Transaction",        "transactionId": "COTRN01" },
    { "number": 7,  "label": "Transaction List",        "transactionId": "COTRN00" },
    { "number": 8,  "label": "Add Transaction",         "transactionId": "COTRN02" },
    { "number": 9,  "label": "Bill Payment",            "transactionId": "COBIL00" },
    { "number": 10, "label": "Reports",                 "transactionId": "CORPT00" }
  ]
}
```

**Menu Option Fields:**

| Field           | Type    | Description                                          | BMS Source                         |
|-----------------|---------|------------------------------------------------------|------------------------------------|
| `number`        | Integer | Option number (user enters to select)                | `OPTION` PIC X(2) NUM/UNPROT      |
| `label`         | String  | Menu option display label (up to 40 chars)           | `OPTNnnn` PIC X(40) ASKIP         |
| `transactionId` | String  | CICS transaction ID mapped to a REST route           | From COMEN02Y.cpy option table     |

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
Authorization: Bearer <token>
```

**Responses:**

| Status | Description                                |
|--------|--------------------------------------------|
| 200    | Returns array of admin menu options        |
| 401    | Unauthorized — missing or invalid token    |
| 403    | Forbidden — requires ADMIN role            |

**Response 200:**

```json
{
  "menuTitle": "Admin Menu",
  "options": [
    { "number": 1, "label": "User List",    "transactionId": "COUSR00" },
    { "number": 2, "label": "User Add",     "transactionId": "COUSR01" },
    { "number": 3, "label": "User Update",  "transactionId": "COUSR02" },
    { "number": 4, "label": "User Delete",  "transactionId": "COUSR03" }
  ]
}
```

**Implementation Notes:**
- The COADM01.bms screen has the same 12 option display slots as the main menu but is populated from the `COADM02Y.cpy` copybook with 4 admin-specific options
- The `OPTION` input field is identical (2 chars, NUM/UNPROT, right-justify zero-fill)
- Access is restricted to users with `userType = 'A'` (ADMIN role)

---

## 10. Security and Headers

### 10.1 Authentication Header

All endpoints except `POST /api/auth/signin` require the `Authorization` header:

```
Authorization: Bearer <token>
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
