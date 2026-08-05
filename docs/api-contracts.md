<!--
================================================================================
  Document    : api-contracts.md
  Application : CardDemo
  Type        : Documentation - REST API contract of record
  Function    : Publishes the HTTP/JSON surface of the Java 25 / Spring Boot
                3.5.11 migration target: 17 operations across 8 controllers,
                derived field-by-field from the CICS CSD, the 17 BMS symbolic
                maps and the 17 online COBOL programs of the frozen app/ tree.
  Derived from: app/csd/CARDDEMO.CSD, app/cpy-bms/**, app/cbl/CO*.cbl,
                app/cpy/COCOM01Y.cpy, app/cpy/CSUSR01Y.cpy,
                app/cpy/CSMSG02Y.cpy, app/cpy/COMEN02Y.cpy,
                app/cpy/COADM02Y.cpy
================================================================================
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.

  Licensed under the Apache License, Version 2.0 (the "License").
  You may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  License for the specific language governing permissions and limitations
  under the License.
================================================================================
-->

# API Contracts

## 1. About this document

This document is the **REST API contract of record** for the Java 25 / Spring Boot 3.5.11
migration of the AWS CardDemo credit-card management application. It describes **17 HTTP
operations across 8 controllers**, each replacing exactly one sourced CICS transaction.

> **This contract is manually authored. It is not generated OpenAPI.**
>
> Generated OpenAPI / Swagger tooling is **explicitly out of scope** for this migration, so
> this hand-written document is the authoritative description of the HTTP surface. The
> absence of a machine-readable specification is recorded as a deferred residual risk in
> [validation-gates.md](validation-gates.md); see also finding **L-2** in
> [§18, Findings](#18-findings-severity-classified). No OpenAPI document, Swagger UI,
> Postman collection or other machine-readable schema artefact exists in this repository,
> and none should be inferred from this page.

The legacy corpus under `app/` is **frozen** and is licensed under the Apache License 2.0,
copyright Amazon.com, Inc. or its affiliates — see the repository `LICENSE` and `NOTICE`.
Every field name, PIC clause, field width, validation order and message literal published
below was read directly out of that corpus at anchor commit
`7756d895ffeb65f7ea72aaa609e356d9899afcec` (short `7756d89`) and carries an inline
`[<path>:<locator>]` citation. Where a figure in this document disagrees with narrative
prose elsewhere, **the COBOL source governs** and the discrepancy is recorded in
[§18](#18-findings-severity-classified).

### 1.1 What this document is not

| Not covered here | Where it lives |
|---|---|
| The eight validation gates, their evidence and the residual-risk register | [validation-gates.md](validation-gates.md) |
| Legacy-versus-target architecture and diagrams | [architecture-before-after.md](architecture-before-after.md) |
| Developer setup, how to build, run and exercise the API locally | [onboarding-guide.md](onboarding-guide.md) |
| The complete transformation plan, scope boundaries and mapping tables | [technical-specifications.md](technical-specifications.md) |
| Stakeholder summary | [executive-presentation.html](executive-presentation.html) |
| Documentation entry point | [index.md](index.md) |
| Every mechanism substitution and preserved legacy quirk | `../DECISION_LOG.md` (repository-root artefact, outside the MkDocs `docs_dir`) |
| Paragraph-level COBOL-to-Java mapping for all 28 programs | `../TRACEABILITY_MATRIX.md` (repository-root artefact, outside the MkDocs `docs_dir`) |

Those documents are **linked, never duplicated**. This page confines itself to the HTTP
contract: purpose, inputs, outputs, side effects and error modes per operation.

`docs/project-guide.md` is retained unchanged as **prior-run evidence**. Its completion,
test-count, coverage and gate-pass statements describe an earlier attempt and are **not
evidence for the current implementation**; they are not cited anywhere in this document.

### 1.2 How to exercise this API

Build, run and local-stack instructions are **not repeated here** — see
[onboarding-guide.md](onboarding-guide.md). Only the caller-facing facts belong on this
page:

| Concern | Value |
|---|---|
| Transport | HTTP/1.1, request and response bodies `application/json` |
| Error bodies | `application/problem+json` (RFC 9457 / RFC 7807), see [§8](#8-error-response-envelope) |
| Base path | `/api` — every operation sits beneath it |
| Implementing package root | `com.cardemo` — controllers in `com.cardemo.controller`, the exception hierarchy named in [§8.2](#82-global-failure-and-status-mapping) in `com.cardemo.exception`. Note the spelling: the application is **CardDemo** but the package root carries a single `d` |
| Authentication | `Authorization: Bearer <token>` on every operation except sign-on |
| Session state | **None.** No cookie, no server-side session, no screen state — see [§5](#5-stateless-identity-the-commarea-has-no-server-side-successor) |
| Correlation | Send or receive `X-Correlation-Id`; it is echoed into every error body and every log line |
| Token signing key | Resolved from an environment variable in **every** Spring profile, with **no committed default**; the application fails fast when it is absent |
| Operational endpoints | Spring Boot Actuator exposes `health`, `info` and `prometheus` only; the metrics scrape endpoint requires its own dedicated authority and is not part of the 17 business operations — see [§2.2](#22-operational-endpoints-and-the-scrape-credential) |

There is **no user interface** in scope. The target surface is REST/JSON plus Actuator.
The 3270 terminal and its BMS mapsets are consumed **as field contracts only** — no
green-screen rendering, no terminal emulation, no single-page application, no component
library and no design system.

### 1.3 Authoring-host environment

As of **4 August 2026** the authoring host has JDK 25.0.3, Apache Maven 3.9.11, Docker Engine
29.7.0 with `docker compose` v5.3.1, AWS CLI 1.45.62 and LocalStack CLI 4.14.0 all present and
invocable, and this page's rendering was verified with MkDocs 1.6.1
([§18.5](#185-documentation-build-verification)) — full provisioning detail belongs to
[onboarding-guide.md](onboarding-guide.md).

---

## 2. Operation inventory: why there are exactly 17

`app/csd/CARDDEMO.CSD` defines **18 transactions and 18 programs but only 17 mapsets**.
The eighteenth transaction-and-program pair is `CDV1 → COCRDSEC`, described in the CSD as
`DESCRIPTION(DEVELOPER TRANSACTION - 1)`
[`app/csd/CARDDEMO.CSD:L388-L390`]. **`COCRDSEC` has no source file anywhere in this
repository.** Its only two occurrences repository-wide are the two CSD definitions
themselves — `DEFINE PROGRAM(COCRDSEC) GROUP(CARDDEMO)`
[`app/csd/CARDDEMO.CSD:L211`] and `PROGRAM(COCRDSEC) TWASIZE(0) PROFILE(DFHCICST)
STATUS(ENABLED)` [`:L390`].

It is a dangling legacy definition with nothing to translate. **No route, no endpoint and
no placeholder is published for it**, and none should be requested. Independent
corroboration: the *Online* application-inventory table at `README.md:L213-L231` lists
exactly 17 rows and contains no row for that transaction.

So: **17 sourced screen programs + 1 orphan CSD definition = the 18 CSD entries.**

### 2.1 The whole surface at a glance

Eight controllers, seventeen operations, distributed **Auth 1 · Menu 2 · Account 2 ·
Card 3 · Transaction 3 · Billing 1 · Report 1 · Admin 4**.

| # | Operation | Method | Path | CSD | COBOL program (lines) | Symbolic map (input fields) | Role | Success |
|--:|---|---|---|---|---|---|---|--:|
| 1 | [Sign on](#91-sign-on) | `POST` | `/api/auth/signon` | `CC00` | `COSGN00C` (260) | `COSGN00` (11) | *none* | `200` |
| 2 | [Main menu](#101-main-menu) | `GET` | `/api/menu/main` | `CM00` | `COMEN01C` (282) | `COMEN01` (20) | USER or ADMIN | `200` |
| 3 | [Admin menu](#102-admin-menu) | `GET` | `/api/menu/admin` | `CA00` | `COADM01C` (268) | `COADM01` (20) | **ADMIN** | `200` |
| 4 | [View account](#111-view-account) | `GET` | `/api/accounts/{accountId}` | `CAVW` | `COACTVWC` (941) | `COACTVW` (37) | USER or ADMIN | `200` |
| 5 | [Update account](#112-update-account) | `PUT` | `/api/accounts` | `CAUP` | `COACTUPC` (4,236) | `COACTUP` (54) | USER or ADMIN | `200` |
| 6 | [List cards](#121-list-cards) | `GET` | `/api/cards` | `CCLI` | `COCRDLIC` (1,459) | `COCRDLI` (45) | USER or ADMIN | `200` |
| 7 | [View card](#122-view-card) | `GET` | `/api/cards/detail` | `CCDL` | `COCRDSLC` (887) | `COCRDSL` (15) | USER or ADMIN | `200` |
| 8 | [Update card](#123-update-card) | `PUT` | `/api/cards` | `CCUP` | `COCRDUPC` (1,560) | `COCRDUP` (17) | USER or ADMIN | `200` |
| 9 | [List transactions](#131-list-transactions) | `GET` | `/api/transactions` | `CT00` | `COTRN00C` (699) | `COTRN00` (59) | USER or ADMIN | `200` |
| 10 | [View transaction](#132-view-transaction) | `GET` | `/api/transactions/detail` | `CT01` | `COTRN01C` (330) | `COTRN01` (21) | USER or ADMIN | `200` |
| 11 | [Add transaction](#133-add-transaction) | `POST` | `/api/transactions` | `CT02` | `COTRN02C` (783) | `COTRN02` (21) | USER or ADMIN | `201` |
| 12 | [Pay bill](#141-bill-payment) | `POST` | `/api/billing/payments` | `CB00` | `COBIL00C` (572) | `COBIL00` (10) | USER or ADMIN | `201` |
| 13 | [Submit report](#151-submit-transaction-report) | `POST` | `/api/reports` | `CR00` | `CORPT00C` (649) | `CORPT00` (17) | USER or ADMIN | `202` |
| 14 | [List users](#161-list-users) | `GET` | `/api/admin/users` | `CU00` | `COUSR00C` (695) | `COUSR00` (59) | **ADMIN** | `200` |
| 15 | [Add user](#162-add-user) | `POST` | `/api/admin/users` | `CU01` | `COUSR01C` (299) | `COUSR01` (12) | **ADMIN** | `201` |
| 16 | [Update user](#163-update-user) | `PUT` | `/api/admin/users/{userId}` | `CU02` | `COUSR02C` (414) | `COUSR02` (12) | **ADMIN** | `200` |
| 17 | [Delete user](#164-delete-user) | `DELETE` | `/api/admin/users/{userId}` | `CU03` | `COUSR03C` (359) | `COUSR03` (11) | **ADMIN** | `200` |

Program line counts were verified with `wc -l` at `7756d89`. Symbolic-map field counts were
verified by machine count of the `<name>I` data items in each `app/cpy-bms/*.CPY` member.

---

### 2.2 Operational endpoints, and the scrape credential

Three Actuator endpoints are exposed and no others. Everything else — `env`, `beans`,
`metrics`, `loggers`, `threaddump`, `heapdump`, `mappings`, `configprops`, `shutdown` — is
unreachable, anonymously or with any credential this service issues.

| Endpoint | Anonymous | Notes |
|---|:--:|---|
| `GET /actuator/health` (and `/health/liveness`, `/health/readiness`) | **yes** | Deliberately anonymous, so a container probe works before any credential exists |
| `GET /actuator/info` | **yes** | |
| `GET /actuator/prometheus` | **no** | HTTP Basic, carrying its own private authority. `GET` only |

**`/actuator/prometheus` answers `401` to every caller until a scrape credential is
configured, and that is the intended behaviour, not a fault.** The credential is two
environment variables, `METRICS_SCRAPE_USERNAME` and `METRICS_SCRAPE_PASSWORD`, and **both**
must be non-blank. With either half blank the endpoint holds **zero** principals and refuses
everyone — so a deployment that forgets the credential loses metrics *visibly*, within one
scrape interval, instead of publishing them anonymously. `.env.example` carries both variables
with the credential deliberately empty, and the reasoning, at the point of definition.

Two consequences are worth stating because each has been mistaken for a defect:

* **A bearer token does not work here, not even an administrator's.** This path has its own
  filter chain, its own authentication manager and its own principal, entirely isolated from
  business identity. Conversely the scrape credential buys nothing on `/api/*`.
* **The user name is a cross-file contract.** `observability/prometheus.yml` carries it as a
  literal, because Prometheus performs no environment substitution on its configuration file.
  Change it in one place and it must change in the other.

Verify a configured deployment with:

```bash
curl -u "$METRICS_SCRAPE_USERNAME:$METRICS_SCRAPE_PASSWORD" \
     http://localhost:8080/actuator/prometheus | grep '^carddemo_'
```

A refusal carries the same envelope as every other refusal on this service —
`application/problem+json`, `CARDDEMO-AUTHENTICATION-REQUIRED`, a `correlationId` — plus
`WWW-Authenticate: Basic realm="carddemo-metrics-scrape"`. The body deliberately does **not**
say whether a credential is configured; that is deployment state, and an anonymous caller has
no claim on it. The **log** says so plainly, naming both configuration keys when neither is
set, which is where an operator should look.

---

## 3. Read this before integrating

Five behaviours account for almost every integration failure against this API. Each is
source behaviour faithfully carried across, not a target-side choice, and each is
counter-intuitive enough that a client written to the obvious assumption will fail.

| # | Trap | The assumption that fails | Section |
|--:|---|---|---|
| T1 | The as-displayed snapshot for `PUT /api/accounts` is **server-issued and sealed**, travels in `If-Match`, and its date of birth is held **compact `YYYYMMDD`** | "I can build the old-values group myself and send it in the body, using `YYYY-MM-DD` like every other date" | [§11.2](#112-update-account) |
| T2 | Page sizes are **fixed at 7, 10 and 10** by screen geometry and are **not** client-configurable | "`?size=50` will page bigger" | [§7](#7-pagination-contract) |
| T3 | Bill payment always pays the **entire** balance; there is **no amount field** | "I can make a partial payment" | [§14.1](#141-bill-payment) |
| T4 | The monthly report period is the **full current calendar month**, first day to **last** day | "Monthly means month-to-date" | [§15.1](#151-submit-transaction-report) |
| T5 | Identifiers and card numbers use a **strict digits-only** parser; amounts use a **currency-tolerant** one | "One numeric format works everywhere" | [§6](#6-two-numeric-parsers-used-deliberately) |

[§17, Troubleshooting](#17-troubleshooting) gives the observable symptom and the fix for
each.

---

## 4. Field-contract provenance

Every request and response field on this page derives from a field of a BMS symbolic map.
The seventeen symbolic maps in `app/cpy-bms/` carry **441 input fields in total**, verified
by machine count two independent ways, distributed as:

| Symbolic map | Fields | Symbolic map | Fields | Symbolic map | Fields |
|---|--:|---|--:|---|--:|
| `COACTUP.CPY` | 54 | `COCRDSL.CPY` | 15 | `COTRN02.CPY` | 21 |
| `COACTVW.CPY` | 37 | `COCRDUP.CPY` | 17 | `COUSR00.CPY` | 59 |
| `COADM01.CPY` | 20 | `COMEN01.CPY` | 20 | `COUSR01.CPY` | 12 |
| `COBIL00.CPY` | 10 | `CORPT00.CPY` | 17 | `COUSR02.CPY` | 12 |
| `COCRDLI.CPY` | 45 | `COSGN00.CPY` | 11 | `COUSR03.CPY` | 11 |
| `COTRN00.CPY` | 59 | `COTRN01.CPY` | 21 | **Total** | **441** |

The three highest counts are explained by row arrays rather than richer screens: `COTRN00`
and `COUSR00` carry ten-row tables and `COCRDLI` carries seven.

### 4.1 How to verify a width against the source

Each screen field is generated by the BMS macro as a **quintuple**, preceded once per map
by a twelve-byte terminal-I/O header
[`app/cpy-bms/COACTUP.CPY:L17-L24`]:

```cobol
       01  CACTUPAI.
           02  FILLER PIC X(12).            *> terminal I/O area header, once per map
           02  TRNNAMEL    COMP  PIC  S9(4). *> field length
           02  TRNNAMEF    PICTURE X.        *> attribute byte
           02  FILLER REDEFINES TRNNAMEF.
             03 TRNNAMEA    PICTURE X.       *> attribute alias
           02  FILLER   PICTURE X(4).        *> four reserved bytes
           02  TRNNAMEI  PIC X(4).           *> the data field - THIS is what we publish
```

Only the `<name>I` data items are published in this document. The `L`, `F`, `A` and
`FILLER` members are terminal-plumbing artefacts with no REST counterpart.

### 4.2 Presentation chrome excluded from every JSON contract

Six header fields recur on all seventeen maps and are **excluded from the JSON contract in
every operation below** — they are terminal-rendering artefacts:

| Field | Width | Why excluded |
|---|---|---|
| `TRNNAMEI` | `X(4)` | The CICS transaction identifier; routing is URL-based |
| `TITLE01I` | `X(40)` | Screen title line 1 |
| `CURDATEI` | `X(8)` | Server-rendered current date |
| `PGMNAMEI` | `X(8)` | The COBOL program name that painted the screen |
| `TITLE02I` | `X(40)` | Screen title line 2 |
| `CURTIMEI` | `X(8)`, **but `X(9)` on `COSGN00.CPY`** | Server-rendered current time |

`COSGN00.CPY` is the **only** map on which `CURTIME` is nine characters, and the only one
carrying two further chrome fields, `APPLIDI X(8)` and `SYSIDI X(8)` — the CICS APPLID and
system identifier, which likewise have no REST counterpart.

Function-key chrome is excluded on the same grounds wherever a map declares it:
`FKEYSI`, `FKEY05I`, `FKEY12I` on `COACTUP`; `FKEYSI X(75)` on `COCRDSL`; `FKEYSI X(21)`
and `FKEYSCI X(18)` on `COCRDUP`.

**`ERRMSGI` and `INFOMSGI` are the exception.** They do have a counterpart, because they
carry the exact observable message literals the 3270 screen displayed. Those literals are
published verbatim throughout this document and are relayed byte-for-byte in response
bodies, because a client that matched on the legacy text must keep matching on it.

### 4.3 Field-width conventions used below

* A width written `X(n)` is the declared COBOL character width of the symbolic-map field.
  Bean validation enforces it as a maximum, so a longer value is refused rather than
  silently truncated.
* Widths are **never** rounded, widened or normalised. Where two maps declare different
  widths for what looks like the same value — `TDESC` is `X(26)` on the transaction list
  map and `X(60)` on the transaction detail map — both are published as they are, and the
  difference is called out.
* Persisted width may legitimately differ from input width. The clearest case is the
  password: `PASSWDI X(8)` on input, stored as a 60-character BCrypt hash.
* **Significant leading and trailing spaces in a legacy literal are stated numerically, in
  words, rather than embedded inside a code span.** Several `ERRMSG` literals carry
  meaningful edge padding — `'   Displaying requested details'` has three leading spaces
  and `'PF03 pressed.Exiting              '` has fourteen trailing ones
  [`app/cbl/COCRDSLC.cbl:L130`, `:L137`]. A Markdown code span cannot carry them: the
  renderer strips leading and trailing spaces from `` `…` `` content, so a literal quoted
  with its padding would render without it and the padding claim would be false as
  published. Counting invisible spaces on a rendered page is impossible for a reader in any
  case. The padding is therefore always described — "plus one trailing space", "preceded by
  three leading spaces" — with the total literal length given where it disambiguates. Do
  not re-introduce edge whitespace into a code span to represent it.

---

## 5. Stateless identity: the COMMAREA has no server-side successor

This is the single largest behavioural reshaping in the online layer, and it is worth
stating plainly because it changes how a client is written.

The legacy programs passed one `CARDDEMO-COMMAREA` structure [`app/cpy/COCOM01Y.cpy`] from screen to screen across
`EXEC CICS XCTL` transfers. It carried three different kinds of thing at once: **who the
user was**, **where in the screen flow they were**, and **what the last screen showed**.
In the target, only the first survives, and it moves into the token.

| COMMAREA field | Declared as | Target |
|---|---|---|
| `CDEMO-USER-ID` | `PIC X(08)` | JWT **subject** claim |
| `CDEMO-USER-TYPE` | `PIC X(01)`, `88` levels `'A'` / `'U'` | JWT **`role`** claim, mapped to a Spring Security authority |
| `CDEMO-ACCT-ID` | `PIC 9(11)` | Request / response DTO field |
| `CDEMO-CARD-NUM` | `PIC 9(16)` | Request / response DTO field |
| `CDEMO-CUST-ID` | `PIC 9(09)` | Response DTO field |
| `CDEMO-CUST-FNAME` / `-MNAME` / `-LNAME` | `PIC X(25)` each | Response DTO fields |
| `CDEMO-ACCT-STATUS` | `PIC X(01)` | Response DTO field |
| Page number and next-page flag | per-program, e.g. `CDEMO-CT00-PAGE-NUM PIC 9(08)`, `CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)` [`app/cbl/COTRN00C.cbl:L62-L69`] | Request query parameters plus response metadata — see [§7](#7-pagination-contract) |
| `CDEMO-FROM-TRANID` | `PIC X(04)` | **No equivalent** |
| `CDEMO-TO-TRANID` | `PIC X(04)` | **No equivalent** |
| `CDEMO-FROM-PROGRAM` | `PIC X(08)` | **No equivalent** |
| `CDEMO-TO-PROGRAM` | `PIC X(08)` | **No equivalent** |
| `CDEMO-PGM-CONTEXT` | `PIC 9(01)`, `88` levels enter `0` / re-enter `1` | **No equivalent** |
| `CDEMO-LAST-MAP` | `PIC X(7)` | **No equivalent** |
| `CDEMO-LAST-MAPSET` | `PIC X(7)` | **No equivalent** |

The seven fields marked *no equivalent* are the navigation-and-screen-state half of the
structure. **Routing is URL-based**, so naming a "from" and "to" program has nothing to
address; and the pseudo-conversational **enter-versus-re-enter flag collapses entirely into
stateless request handling** — every request is self-describing, so there is no first-visit
versus revisit distinction for the server to remember.

Consequences a client must design for:

* **No server-side session and no screen state.** Two consecutive requests share nothing
  except what the caller sends. Anything the server needs must be in the URL, the query
  string, a header or the body.
* **Paging position is a request parameter, not a remembered cursor** ([§7](#7-pagination-contract)).
* **The as-displayed snapshot for the two update operations cannot live on the server**
  between the read and the write. That is precisely why those operations use a sealed
  precondition token ([§11.2](#112-update-account), [§12.3](#123-update-card)).

### 5.1 Role model

`app/cpy/COCOM01Y.cpy` declares the user-type condition names
`88 CDEMO-USRTYP-ADMIN VALUE 'A'` and `88 CDEMO-USRTYP-USER VALUE 'U'`. These become the
`UserType` enum and two Spring Security authorities:

| Code | Meaning | Authority |
|---|---|---|
| `A` | Administrator | `ROLE_ADMIN` |
| `U` | Standard user | `ROLE_USER` |

Authorisation, stated once and true for every operation:

* **Sign-on is the only unauthenticated operation.** `POST /api/auth/signon` is permitted
  to all; every other path requires an authenticated principal.
* **`/api/admin/**` requires `ROLE_ADMIN`.** All four user-administration operations sit
  beneath it. So does `GET /api/menu/admin`, which is separately restricted to
  `ROLE_ADMIN`.
* **Every remaining operation accepts either role**, exactly as the legacy main menu made
  all ten of its options available to both user classes (see the note in
  [§10.1](#101-main-menu)).
* A request with no usable credential is answered `401` with an **empty body**. A request
  whose token carries neither recognised authority is answered `403` with an empty body.
  Neither answer describes the resource.

This is the least-privilege position: the administrator-only surface is separated by path
prefix so that a single rule governs it, rather than by a per-operation check that a future
operation could forget.

---

## 6. Two numeric parsers, used deliberately

This is a correctness requirement, not a stylistic note. The legacy transaction-add program
uses **two different numeric intrinsics on the same screen**, and reproducing only one of
them either accepts input the system should reject or rejects input it should accept.

| Input | Intrinsic used by the source | Locator | Target parser |
|---|---|---|---|
| Account identifier | `FUNCTION NUMVAL` | [`app/cbl/COTRN02C.cbl:L204`] | **Strict digits-only** |
| Card number | `FUNCTION NUMVAL` | [`:L218`] | **Strict digits-only** |
| Transaction amount | `FUNCTION NUMVAL-C` | [`:L383`], [`:L456`] | **Currency-tolerant** |

`FUNCTION NUMVAL-C` is the currency-aware form: it additionally tolerates a currency symbol
and thousands separators. `FUNCTION NUMVAL` does not.

Before the account identifier and the card number are parsed at all, the source tests them
with `IF … IS NOT NUMERIC` and rejects a non-numeric value outright with
`'Account ID must be Numeric...'` [`:L199`] or `'Card Number must be Numeric...'`
[`:L213`]. So for identifiers the contract is: **digits only, nothing else**.

### 6.1 The amount echo mask

The parsed amount is round-tripped through an **edited** field and back into the screen
field [`app/cbl/COTRN02C.cbl:L385-L386`]:

```cobol
         05 WS-TRAN-AMT-N              PIC S9(9)V99 VALUE ZERO.      *> :L58
         05 WS-TRAN-AMT-E              PIC +99999999.99 VALUE ZEROS. *> :L59
```

* The underlying numeric is **signed, nine integer digits, two decimals**
  [`:L58`].
* The edited mask carries a **mandatory sign, exactly eight integer digits and two
  decimals** [`:L59`].

The response therefore echoes the amount on the edited mask: always signed, always two
decimals. Note the consequence, which is source behaviour and is preserved: the mask holds
only eight integer digits while the numeric holds nine, so a nine-integer-digit amount is
**truncated in the echoed value** even though the stored value is not. Clients should read
the stored amount from the resource, not from the echo, when the magnitude approaches that
boundary.

### 6.2 Monetary representation

* Every monetary and rate value is `java.math.BigDecimal` in Java, at the scale its COBOL
  PIC clause dictates.
* **There is zero `float` and zero `double` in any financial field**, anywhere.
* Monetary values are serialised as JSON strings or exact decimals — **never** as IEEE
  floating point. A client must parse them into a decimal type, not a binary float, or it
  will reintroduce the error the target exists to avoid.
* Equality is `compareTo()`, never `equals()`. Rounding is `RoundingMode.HALF_EVEN`.

---

## 7. Pagination contract

Three operations page. **Page size is fixed by the screen geometry of the originating BMS
map and is not client-configurable.**

| Operation | Page size | Fixed by | Proof in the program |
|---|--:|---|---|
| [List cards](#121-list-cards) | **7** | `COCRDLI` declares seven row groups | `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7` [`app/cbl/COCRDLIC.cbl:L177-L178`] |
| [List transactions](#131-list-transactions) | **10** | `COTRN00` declares ten row groups | loop bounds `UNTIL WS-IDX > 10` [`app/cbl/COTRN00C.cbl:L290`] and `UNTIL WS-IDX >= 11` [`:L297`] |
| [List users](#161-list-users) | **10** | `COUSR00` declares ten row groups | `02 USER-REC OCCURS 10 TIMES` [`app/cbl/COUSR00C.cbl:L57`] |

**A client-supplied page size is ignored, not rejected.** No `size`, `limit` or `perPage`
parameter is declared on any operation, so such a parameter is simply an unbound query
parameter that never reaches the handler and changes nothing. Stating which of the two
behaviours applies matters: a caller sending `?size=50` receives a normal, successful,
**seven-** or **ten-row** page rather than a `400`, and must not read that success as
confirmation that the size took effect.

### 7.1 Paging parameters

Forward and backward paging are both supported, mirroring the legacy `READNEXT` /
`READPREV` browse. The positioning state that lived in the COMMAREA becomes explicit
parameters:

| Parameter | Meaning | Accepted values |
|---|---|---|
| `action` | Which way to move, replacing the attention-identifier arms of the source | Exactly `SUBMIT`, `PAGE_BACKWARD` or `PAGE_FORWARD`; no alias, no padding and no other case |
| `page` | Current page number, replacing the COMMAREA page-number field | **Digits only**, within the domain of the operation's own counter (below) |
| `firstKey` | Key at the top of the page just shown, for backward paging | Opaque, echoed from the previous response |
| `lastKey` | Key at the bottom of the page just shown, for forward paging | Opaque, echoed from the previous response |
| `nextPageAvailable` | The next-page flag from the previous response | Exactly `true` or `false` |

**Control tokens are matched exactly, never converted.** The framework's default converters
normalise — they trim an enum token, accept a signed integer and treat six spellings as a
boolean (`on`, `off`, `yes`, `no`, `1`, `0`). A normalising converter on a control token
would silently accept instructions the operation never declared, so every control token on
every paging operation is matched literally instead. A boolean parameter therefore accepts
**only** `true` and `false`; `page` accepts **only** digits. Anything else is a `400`.

**One vocabulary, three operations.** All three paging operations accept the same three
tokens, spelled the same way. `GET /api/admin/users` formerly declared `submit`,
`page-backward` and `page-forward` while the other two declared the upper-case forms, so one
API published two vocabularies for one navigation and each rejected the other's spelling; the
admin operation now declares the same three tokens as its siblings (finding M-13). An absent
`action` is the same as `SUBMIT` on every one of the three.

Response metadata carries the page number and a next-page flag, derived from the
per-program COMMAREA page fields plus the map's own `PAGENUM X(8)` (`COTRN00`, `COUSR00`)
or `PAGENO X(3)` (`COCRDLI`) field. Page numbering is bounded by **the COMMAREA counter the
program maintains**, which is narrower than the map slot on the card list, and a page number
outside that domain is refused with `400`:

| Operation | Accepted `page` | Bounded by | Locator |
|---|---|---|---|
| [List cards](#121-list-cards) | `0`–`9` | `WS-CA-SCREEN-NUM PIC 9(1)` — the map slot `PAGENO` is `X(3)`, but the counter written into it is one digit | [`app/cbl/COCRDLIC.cbl:L237`] |
| [List transactions](#131-list-transactions) | `0`–`99999999` | `CDEMO-CT00-PAGE-NUM PIC 9(08)` | [`app/cbl/COTRN00C.cbl:L65`] |
| [List users](#161-list-users) | `0`–`99999999` | `CDEMO-CU00-PAGE-NUM PIC 9(08)` | [`app/cbl/COUSR00C.cbl`] |

Zero is accepted on all three: it is what an unstarted browse reports, so a client echoing
back the metadata it was given is not refused for it.

**The positioning state is indivisible: echo all of it, or none of it.** In the source these
values were fields of one COMMAREA record, written together by a single send and read back
together by a single receive — a page number could not arrive without its key, because there
was no way to send one without the other. Statelessness puts them on the wire as separate
parameters, and separate parameters can arrive apart. They still mean nothing apart:

* Send `action` with `page`, `firstKey`, `lastKey` and `nextPageAvailable` **exactly as the
  previous response published them** (`firstCursor` → `firstKey`, `lastCursor` → `lastKey`,
  `pageNumber` → `page`). A first request sends `action` alone, or nothing at all.
* Omitting `nextPageAvailable` on a forward step is not an error and not a `400`. It means
  what it says — *no next page is known to exist* — so the operation re-reads from the top of
  the current page instead of advancing. A forward walk that appears not to progress is
  almost always this.
* **Asking to page backward from beyond the first page without `firstKey` is refused with
  `400` naming `firstKey`**, and asking to page backward with no `page` at all is refused
  with `400` naming `page`. This is a genuine precondition rather than a courtesy check:
  `COCRDLIC`'s backward browse has **no end-of-data arm** — its response `EVALUATE`
  [`app/cbl/COCRDLIC.cbl:L1304-L1318`] carries only `NORMAL`, `DUPREC` and `WHEN OTHER` — so
  a backward read from an unpositioned browse is an I/O failure *by construction*. In the
  source that state was unreachable, because the key and the page number are **fields of one
  record**: `WS-CA-FIRST-CARDKEY` [`:L232-L234`] and `WS-CA-SCREEN-NUM` [`:L237`] both sit in
  `WS-THIS-PROGCOMMAREA` [`:L228`], so there was no way to return one without the other. On
  the wire they are separable, so the invariant the COMMAREA enforced by its shape becomes a
  precondition that has to be checked — and it is refused as the malformed request it is
  rather than reported as a store failure.
* **Naming a page past the first without the cursor that addresses it is refused with `400`
  naming that cursor** — `lastKey` on a forward step, `firstKey` on any other. This is the
  same COMMAREA-invariant-becomes-precondition shape as the bullet above, and it is checked
  for the same reason: **the page number is a display counter, not an address.** What
  positions a browse is the saved key the `DFHPF7` / `DFHPF8` arms move into the record key
  before restarting it; the counter only feeds the heading. With no key the space-filled field
  positions at the start of the file, so `GET /api/cards?action=PAGE_FORWARD&page=8` used to
  answer `200` reporting `pageNumber=8` while serving **page one's seven rows**, and the two
  ten-row operations answered `200` reporting `pageNumber=5` with an **empty** row array
  (finding M-14). Either way the response reported a page it had not served, which a client
  cannot act on and cannot distinguish from an exhausted browse. The first page, and a request
  naming no page at all, are admitted with no cursor.
* `SUBMIT` on the two ten-row operations always answers the **first** page whatever `page`
  says, and is therefore never refused for a missing cursor: the enter-key paragraph forces
  the counter to zero [`app/cbl/COUSR00C.cbl:L227`] and re-reads from the search key. On the
  card list `SUBMIT` redisplays the page it is given, so there it does need `firstKey`. A
  `page` value **outside the declared domain is still refused on that arm**, even though the
  arm does not use it: a control token the operation declares is either accepted or refused,
  never silently discarded, which is the same rule that stops a token being converted.
* Backward paging **from the first page** is not refused: it is the ordinary top-of-file arm
  and answers `200` with the top-of-page literal.
* A cursor is **sealed to the page it was minted for**. Presenting page 1's cursor as page
  2's is refused with `400`, not silently honoured — an accepted mismatch would return a page
  whose number and contents disagree.

**A browse filter and a record key are validated differently, and deliberately.**
`GET /api/cards?accountFilter=…` accepts an all-zero value and a value wider than eleven
digits, while `GET /api/accounts/00000000000` and `…/000000000011` are both refused. The two
are not the same kind of input. The filter is the source's own browse filter: `COCRDLIC`
treats an all-zero or space-filled filter as *no filter* and starts the browse at the top of
the file, so refusing it would remove the unfiltered browse. The path variable is an exact
record key read with `READ … EQUAL`, where a value the key cannot hold has no matching record
and is a client error. Both behaviours are faithful; the asymmetry is in the source, not in
the translation.

### 7.2 Boundary behaviour

The legacy programs distinguish four paging edges, each with its own message, and all four
are preserved as observable outcomes rather than errors:

| Condition | Literal (transaction list) | Locator |
|---|---|---|
| Already at the first page, asked to go back | `'You are already at the top of the page...'` | [`app/cbl/COTRN00C.cbl:L248`] |
| Already at the last page, asked to go forward | `'You are already at the bottom of the page...'` | [`:L270`] |
| Reached the first page while paging back | `'You are at the top of the page...'` | [`:L608`] |
| Reached the last page while paging forward | `'You have reached the bottom of the page...'` | [`:L642`] |

The user list carries the same four messages verbatim
[`app/cbl/COUSR00C.cbl:L251`, `:L273`, `:L603`, `:L637`]. An empty result set is a
successful `200` with an empty row array, **not** a `404` — end-of-data is a control
outcome, never an error (see [§8.2](#82-global-failure-and-status-mapping)).

---

## 8. Error-response envelope

One shape, used by every operation **and by every refusal decided before an operation is
reached**. Error bodies are **RFC 9457 / RFC 7807 problem details** served as
`application/problem+json`. There is no path through this application — not an unreadable
`Content-Type`, not an unsatisfiable `Accept`, not a wrong method, not a request the servlet
container refuses while it is still parsing the request line — that answers with a different
body shape, a different media type, or no body at all. [§8.5](#85-refusals-decided-before-an-operation-is-reached)
names the four boundaries that make that true and what each one can and cannot carry.

```json
{
  "type": "about:blank",
  "title": "Account request rejected",
  "status": 400,
  "detail": "Credit Limit must be supplied",
  "instance": "/api/accounts",
  "errorCode": "CARDDEMO-VALIDATION-REJECTED",
  "correlationId": "8f14e45f-ea0c-4b9d-9f2a-6c1b7d3e5a90",
  "field": "creditLimit",
  "failureKind": "BLANK"
}
```

| Member | Always present | Meaning |
|---|---|---|
| `type` | yes | Problem type URI |
| `title` | yes | Short, stable, human-readable summary of the problem class |
| `status` | yes | The HTTP status code, repeated in the body |
| `detail` | when a message exists | **The exact legacy message literal**, relayed byte for byte, whenever the failure carries one. When it does not, the member is omitted rather than invented |
| `instance` | yes | The request path |
| `errorCode` | **yes** | Stable machine-readable outcome code — match on this, not on `title` or `detail` |
| `correlationId` | **yes** | The correlation identifier for this request, from the `X-Correlation-Id` header when the caller supplied one and generated otherwise. The literal `unavailable` appears when no identifier could be resolved |
| `field` | field-level failures | The **name** of the offending input. Never its value |
| `failureKind` | validation failures | Two-state discriminator distinguishing an input left **blank** from one supplied and **invalid** |
| `outcome` | conflict failures | Which concurrency outcome occurred |
| `changeAction` | account update | The source's own outcome vocabulary for the write attempt |

**What never appears in a response body:** no stack trace, no SQL, no internal file or class
path, no dataset or host name, no credential, no password, no password hash, no token and no
social security number. Message literals are relayed because they are screen captions that
disclose none of those things; anything carrying a key or an identifier value is withheld and
recorded in the log at `DEBUG` instead, where an operator must opt in to see it.

**No exception is swallowed and no root cause is lost.** Every failure is a typed exception
carrying its originating condition, and every mapper both answers the caller and logs the
cause chain. The `correlationId` in the body is the join key between the two: quote it in a
support request and the corresponding log records can be found.

### 8.1 Stable error codes

Match on `errorCode`. These **nineteen** values are the complete set, and the table is
exhaustive by construction — every `CARDDEMO-` literal that exists anywhere in
`src/main/java` appears in exactly one row below.

They fall into three groups, and the group tells you where the refusal was decided, which
in turn tells you which optional members the body can carry.

**Group A — an operation refused the request.** The controller that owns the operation
answered. These bodies carry `instance`, and the validation ones additionally carry `field`
and `failureKind`.

| `errorCode` | Typical status | Raised when |
|---|--:|---|
| `CARDDEMO-VALIDATION-REJECTED` | `400` | An input was blank, malformed, over its declared width or otherwise refused by the source's own validation |
| `CARDDEMO-AUTHENTICATION-FAILED` | `401` | A credential was refused at sign-on |
| `CARDDEMO-RECORD-NOT-FOUND` | `404` | A record the operation required was absent |
| `CARDDEMO-DUPLICATE-RECORD` | `409` | A key already exists |
| `CARDDEMO-UPDATE-CONFLICT` | `409`, `412`, `423`, `428` | A concurrency precondition failed — see [§11.2.6](#1126-outcome-markers-and-status-mapping) |
| `CARDDEMO-CONSTRAINT-REFUSED` | `409` / `422` | A referential, check or not-null constraint refused the write — see [§8.2.1](#821-a-vsam-write-had-one-failure-mode-a-table-has-four) |
| `CARDDEMO-RESOURCE-UNAVAILABLE` | `503` | A required store or queue could not be opened |
| `CARDDEMO-IO-FAILURE` | `502` | The store was reachable but the read or write failed |
| `CARDDEMO-PROCESSING-ABEND` | `500` | The operation ended the way the legacy abend routine ended the task |
| `CARDDEMO-INTERNAL-FAILURE` | `500`, `501` | A typed failure no other mapper on the controller claims, or a container-level failure at or above `500` |

**Group B — the request never got as far as an operation's own logic.** The body was
rejected, or the caller was not entitled to the operation at all. These carry `instance`
but never `field` or `failureKind`, because no field was reached.

| `errorCode` | Typical status | Raised when |
|---|--:|---|
| `CARDDEMO-AUTHENTICATION-REQUIRED` | `401` | No usable bearer token accompanied a request to a protected operation |
| `CARDDEMO-AUTHORIZATION-DENIED` | `403` | A token was valid but its role does not admit the path and method — **including every path this API does not publish**, which is denied rather than reported as absent |
| `CARDDEMO-REQUEST-BODY-UNREADABLE` | `400` | The body could not be deserialised: malformed JSON, or a value past one of the JSON parser bounds (nesting depth, string, number, name and document length) that are applied before deserialisation |
| `CARDDEMO-REQUEST-BODY-TOO-LARGE` | `413` | The body declared or streamed more than 16 384 bytes, refused before authentication |

**Group C — refused at the framework or protocol boundary**, before any operation was
selected. These carry neither `instance`, `field` nor `failureKind`; see
[§8.5](#85-refusals-decided-before-an-operation-is-reached) for why, and for the precise
boundary each one comes from.

| `errorCode` | Typical status | Raised when |
|---|--:|---|
| `CARDDEMO-UNSUPPORTED-MEDIA-TYPE` | `415` | The request declared a `Content-Type` this application cannot read — a concrete type it does not accept, a **non-concrete** one such as `*/*` or `application/*+json`, an unparseable one, or none at all on an operation that needs a body. An `Accept` **response** header names the one type that would have worked |
| `CARDDEMO-NOT-ACCEPTABLE` | `406` | No `Accept` value the caller offered can be satisfied. This application produces `application/json` and `application/problem+json` only |
| `CARDDEMO-METHOD-NOT-ALLOWED` | `405`, `501` | The path is published but not for that method (`405`, with an `Allow` header), or the method is not implemented by the connector at all (`501`, `CONNECT` being the reachable case) |
| `CARDDEMO-RESOURCE-NOT-FOUND` | `404` | Nothing is mapped to the path. Largely unreachable: an unpublished path is denied as `CARDDEMO-AUTHORIZATION-DENIED` first, which is the fail-closed choice and is deliberate |
| `CARDDEMO-REQUEST-REJECTED` | `400` | The request was refused at the protocol boundary — an illegal character in the request target, or a method outside the HTTP firewall's allowed set such as `TRACE`. **The body deliberately says nothing about which**, for the reason given in [§8.5](#85-refusals-decided-before-an-operation-is-reached) |

### 8.2 Global failure and status mapping

This table is published **once** and referenced from each operation rather than repeated.
It is derived from the legacy `FILE STATUS` and `DFHRESP` taxonomy that every COBOL I/O path
in the corpus uses.

| Legacy condition | Java exception | HTTP | Notes |
|---|---|--:|---|
| `FILE STATUS '00'` / `DFHRESP(NORMAL)` | — | `200` / `201` / `202` | Success |
| `FILE STATUS '10'` / `DFHRESP(ENDFILE)` | — | `200` | **End of data is a control outcome, never an error.** It terminates a browse loop and, at the identifier-generation sites, is an accepted path that yields a first identifier of `1` |
| `FILE STATUS '23'` / `DFHRESP(NOTFND)` | `RecordNotFoundException` | `404` | The general rule. **Two batch sites are exceptions** where a not-found status is an accepted *create* path rather than an error; both are in the batch tier and neither is reachable from any operation on this page |
| `FILE STATUS '22'` / `DFHRESP(DUPREC)` | `DuplicateRecordException` | `409` | A key collision, and **only** a key collision — see [§8.2.1](#821-a-vsam-write-had-one-failure-mode-a-table-has-four) |
| `FILE STATUS '35'` / `DFHRESP(NOTOPEN)` | `FileUnavailableException` | `503` | Store or queue unavailable; safe to retry |
| `FILE STATUS '9x'` | `FileAccessException` | `502` | Physical or logical I/O failure. The exception carries the **four-character expanded status** the legacy `9910-DISPLAY-IO-STATUS` renderer produced; that rendering is logged, not published |
| Snapshot comparison detected a change | `ConcurrentUpdateException` | `409` / `412` / `423` / `428` | Which of the four depends on the outcome — [§11.2.6](#1126-outcome-markers-and-status-mapping) |
| A constraint other than a unique key refused the write | `DataIntegrityException` | `409` | A foreign key, a check constraint or a `NOT NULL` column refused the row. **Not retryable as submitted** — see [§8.2.1](#821-a-vsam-write-had-one-failure-mode-a-table-has-four) |
| A submitted value the column cannot represent | `ValidationException` | `400` | Reported against the field the source's own cursor names on its `WHEN OTHER` arm — see [§8.2.1](#821-a-vsam-write-had-one-failure-mode-a-table-has-four) |
| Field validation | `ValidationException` | `400` | Plus `401` at sign-on for a refused credential |
| Anything unexpected | `FatalProcessingException` | `500` | See below |

#### 8.2.1 A VSAM write had one failure mode; a table has four

A VSAM KSDS could refuse a keyed write for exactly one reason a COBOL program cared to name —
the key was already present, reported as `DFHRESP(DUPKEY)` or `DFHRESP(DUPREC)`. Everything
else fell into each program's single `WHEN OTHER` arm. That is why `WRITE-USER-SEC-FILE`
[`app/cbl/COUSR01C.cbl:250-274`], `WRITE-TRANSACT-FILE` [`app/cbl/COTRN02C.cbl:722-748`] and
its bill-payment counterpart [`app/cbl/COBIL00C.cbl:512-548`] each evaluate exactly three
arms and no more.

`V1__create_schema.sql` declares ten foreign keys and five check constraints that VSAM did
not have, and PostgreSQL additionally refuses values a fixed-width COBOL field held happily —
a `NUL` byte inside a name, for instance, because a PostgreSQL text column forbids `0x00`.
Those conditions are *not* key collisions, and reporting them as one stated something false
about the key and advised a retry that could never succeed.

The condition is therefore read from the **driver's SQLSTATE**, not from the exception type
the framework chose, because the framework maps all of the following onto one type:

| SQLSTATE | Condition | Java exception | HTTP | Retryable |
|---|---|---|--:|---|
| `23505` | Unique or primary-key violation | `DuplicateRecordException` | `409` | Yes — the identifier-generation race is designed to be retried |
| `23503`, `23502`, `23514`, other class `23` | A foreign key, `NOT NULL` or check constraint refused the row | `DataIntegrityException` | `409` | **No** — correct the request or the stored data |
| class `22` | A submitted value cannot be represented in its column | `ValidationException` | `400` | **No** — correct the value |
| anything else | The store could not be interrogated | `FileAccessException` | `502` | Yes |

**The source-visible outcome is unchanged by this distinction.** Each program's `WHEN OTHER`
arm still sets its error flag, moves its own literal — `'Unable to Add User...'`
[`app/cbl/COUSR01C.cbl:270-271`], `'Unable to Add Transaction...'`
[`app/cbl/COTRN02C.cbl:745-746`], `'Unable to Add Bill pay Transaction...'`
[`app/cbl/COBIL00C.cbl:543-544`] — and parks its own cursor. What the SQLSTATE selects is only
which typed failure carries that unchanged outcome outwards, and therefore which status the
caller sees. The constraint name, the relation and the offending value are logged and never
returned: together the first two are a map of the store, and the third may be a card number.

The same reading applies to the one *read* that can be refused this way. Sign-on folds an
unknown identifier and a wrong password into one indistinguishable outcome by design
[`app/cbl/COSGN00C.cbl:241-251`]. `sec_usr_id` is `CHAR(8)`, so an identifier carrying a
`NUL` is a value the key column cannot hold and therefore a key no row can have: the read the
source would have performed is provably empty, which is `DFHRESP(NOTFND)`, the `WHEN 13` arm.
It is answered `401` with the same body as every other refused credential, not `503` — which
would have told an unauthenticated caller that the store was broken, and would have made the
outcome distinguishable from a wrong password.

`FatalProcessingException` is the abend path. Its payload is the four abend work-area fields
of `app/cpy/CSMSG02Y.cpy` — which is internally titled `CABENDD.CPY` and is the **abend**
copybook, not a message copybook:

| Field | Declared | Locator |
|---|---|---|
| `ABEND-CODE` | `PIC X(4)` | [`app/cpy/CSMSG02Y.cpy:001300-001400`] |
| `ABEND-CULPRIT` | `PIC X(8)` | [`:001500-001600`] |
| `ABEND-REASON` | `PIC X(50)` | [`:001700-001800`] |
| `ABEND-MSG` | `PIC X(72)` | [`:001900-002000`] |

Those four values are logged with the cause chain. The response carries a fixed detail and
the `correlationId`, and nothing else.

Status selection happens **in each controller**, in its own `@ExceptionHandler` methods.
There is no shared advice class, because the same typed failure legitimately means
different things to different operations: an absent record is a `404` on account view and a
`401` on sign-on. Where an operation departs from this table, its own section says so and
says why.

### 8.3 Batch reject codes are business outcomes, not HTTP errors

The five reject codes of the daily transaction-posting job — **100, 101, 102, 103 and
109** [`app/cbl/CBTRN02C.cbl`] — are **business outcomes of the batch tier**. They are
surfaced through the batch job's exit status and through 430-byte reject records, they are
modelled as an enum, and they are **never thrown as exceptions**.

**No operation on this page returns a reject code, and none should be expected in any error
body.** They appear in this document only to make that boundary explicit.

Where an *online* operation produces a business rejection — bill payment against a
non-positive balance being the clearest case — the response carries the **exact legacy
message literal** with an appropriate `4xx` status and a machine-readable `errorCode`. That
is a handled, documented outcome, not an unhandled exception.

### 8.4 Credentials and secrets

* **`PASSWDI X(8)` is write-only.** It appears on three maps — sign-on
  [`app/cpy-bms/COSGN00.CPY`], user add [`app/cpy-bms/COUSR01.CPY`] and user update
  [`app/cpy-bms/COUSR02.CPY`]. It is accepted on input, is **never** returned in any
  response body, is **never** logged (masked by `logback-spring.xml`), and is stored only as
  a **BCrypt strength-10 hash**.
* No password value appears anywhere in this document. Examples use
  `"password": "<redacted>"`.
* As a source fact: the ten seeded user records carry a plaintext password literal inline in
  `app/jcl/DUSRSECJ.jcl:L35-L44`. It is named here only to record that the seed migration
  BCrypt-hashes those values rather than storing them as given. **It is not a usable
  credential, not a default and not an example**, and it is not reproduced.
* The token signing key is resolved from an environment variable in every profile, with no
  committed default, and the application fails fast when it is absent.
* All AWS interaction targets **LocalStack**. There are zero live AWS credentials in this
  repository and no code path reaches a real AWS endpoint.

### 8.5 Refusals decided before an operation is reached

Seventeen operations answer every refusal they own from an `@ExceptionHandler` declared on the
controller that owns them. That covers everything a controller method can be *reached* to
refuse — and it is deliberately where those refusals live, so that status selection is
reviewable next to the paragraph it reproduces.

It cannot cover a refusal decided **before** a controller method is selected, and four such
boundaries exist. Each one now renders the same envelope; each is listed with what it can and
cannot carry, because the differences are contractual rather than accidental.

| # | Boundary | Refuses | Publishes | Cannot publish |
|--:|---|---|---|---|
| 1 | **`Content-Type` screen** — runs after a handler is mapped, before the body is read | A declared `Content-Type` that is not a concrete type and subtype: `*/*`, `application/*`, `application/*+json`, `*/json` | `415`, `CARDDEMO-UNSUPPORTED-MEDIA-TYPE`, an `Accept` response header, and the request's own `correlationId` | `instance`, `field`, `failureKind` — no field was bound |
| 2 | **Framework exception resolver** — consulted when the mapping itself refused, so no controller exists to ask | A concrete `Content-Type` the operation does not accept; an unsatisfiable `Accept`; a method the path does not publish; an unmapped path | `415` / `406` / `405` / `404` with the matching code, an `Allow` header on a `405`, and the request's own `correlationId` | `instance`, `field`, `failureKind` |
| 3 | **HTTP-firewall rejection handler** | A method outside Spring Security's allowed set — `TRACE`, and any non-standard verb | `400`, `CARDDEMO-REQUEST-REJECTED`, any `Allow` header an earlier layer added, and the request's own `correlationId` | **Why** it was rejected — see below |
| 4 | **Container error report** — the only layer reached when the connector refuses a request while still parsing it | An illegal character in the request target, such as `%00`; a method the connector does not implement | The matching code and a **minted** `correlationId`, written to the log against the status and target | The request's own `correlationId` — no filter ran, so none exists; and `instance` |

Three properties of this design are worth stating explicitly, because each is a deliberate
trade rather than an oversight.

**A wildcard `Content-Type` is a `415`, not a `500`.** This is not the mechanism a reader
expects. A wildcard *request* type **satisfies** a `consumes` condition, because media-type
compatibility is asked in the direction "does the mapping's type include the request's",
so the handler is selected and the failure surfaces one layer further in, as an
`IllegalArgumentException` from the header parser. Mapping that exception type to a status
globally would turn every programming error in the application into a `4xx`, so the screen
happens where the fact is unambiguous instead: a declared type that is not concrete. The
wildcard case and the `text/plain` case consequently answer **identically**, which is the
point — they are the same condition.

**A firewall rejection says nothing about why.** `TRACE` is refused twice over: the connector
refuses it because `allowTrace` is false, and the HTTP firewall refuses the resulting error
dispatch as well. The published body names neither the method nor the offending character,
because that value is supplied by the caller and echoing it would both reflect input and
describe the firewall's rules to whoever is probing them. The reason is written to the log,
against a `correlationId` the caller also receives — so the refusal is diagnosable without
being self-documenting to an attacker.

**An unpublished path is a `403`, not a `404`.** `GET /api/nosuchthing` and
`GET /api/accounts/00000000001/extra` answer `403` with `CARDDEMO-AUTHORIZATION-DENIED`. That
is fail-closed by design: the authorization rules enumerate the seventeen published
path-and-method pairs and deny everything else, so an unknown path is refused before anything
can discover whether it exists. `CARDDEMO-RESOURCE-NOT-FOUND` therefore exists but is
largely unreachable, and it is claimed so that a future permitted path cannot open a gap.

---

## 9. AuthController — 1 operation

Base path `/api/auth`.

### 9.1 Sign on

**Legacy source.** CSD transaction `CC00` [`app/csd/CARDDEMO.CSD:L378`] → program
`COSGN00C` (260 lines) → mapset `COSGN00`, symbolic map `app/cpy-bms/COSGN00.CPY`
(11 input fields).

**Purpose.** Verifies a credential against the user security store and issues a bearer
token. This is the operation that replaces `EXEC CICS XCTL` to `COADM01C` or `COMEN01C`
with a token the caller then presents on every subsequent request.

```http
POST /api/auth/signon HTTP/1.1
Content-Type: application/json
```

**Role.** *None* — this is the **only unauthenticated operation** in the API.

**Request body.**

| JSON member | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| `userId` | `USERIDI` | `X(8)` | **yes** | Blank or absent is refused. Upper-cased before comparison |
| `password` | `PASSWDI` | `X(8)` | **yes** | **Write-only.** Upper-cased before comparison. Never echoed, never logged |
| `applicationId` | `APPLIDI` | `X(8)` | no | Accepted for map fidelity; has no REST counterpart and is not acted on |
| `systemId` | `SYSIDI` | `X(8)` | no | As above |
| `errorMessage` | `ERRMSGI` | `X(78)` | no | Accepted at its declared width so the contract cannot be read as rejecting input the source accepts |

The six chrome fields of [§4.2](#42-presentation-chrome-excluded-from-every-json-contract)
are accepted at their declared widths and ignored — note that `currentTime` is `X(9)` here,
the one map where it is nine characters.

```json
{
  "userId": "USER0001",
  "password": "<redacted>"
}
```

**Response — `200 OK`.**

| JSON member | Width | Derived from | Notes |
|---|---|---|---|
| `token` | — | *new* | The bearer credential. Present it as `Authorization: Bearer <token>` |
| `userId` | `X(8)` | `CDEMO-USER-ID` [`app/cpy/COCOM01Y.cpy:L25`] | The resolved, upper-cased identifier |
| `userType` | `X(1)` | `CDEMO-USER-TYPE` [`:L26`] | `A` or `U` |

The token **replaces the COMMAREA**: `userId` becomes the subject claim and `userType` the
`role` claim, and no other identity state crosses the wire. `userType` is what tells the
client which menu to fetch next — `A` → [`GET /api/menu/admin`](#102-admin-menu), `U` →
[`GET /api/menu/main`](#101-main-menu) — replacing the legacy branch at
[`app/cbl/COSGN00C.cbl:L230-L239`], which issued `XCTL PROGRAM('COADM01C')` for an
administrator and `XCTL PROGRAM('COMEN01C')` otherwise.

**Side effects.** None on business data. No row is written, no queue message is published.
An authentication-attempt counter is incremented for observability.

**Validation order.** The source evaluates in exactly this order
[`app/cbl/COSGN00C.cbl:L117-L130`], and order determines which message a caller sees first:

1. `USERIDI = SPACES OR LOW-VALUES` → refuse [`:L118-L122`]
2. `PASSWDI = SPACES OR LOW-VALUES` → refuse [`:L123-L127`]
3. **Both** values are then upper-cased [`:L133-L138`]:
   `MOVE FUNCTION UPPER-CASE(USERIDI …) TO WS-USER-ID CDEMO-USER-ID` and
   `MOVE FUNCTION UPPER-CASE(PASSWDI …) TO WS-USER-PWD`
4. Read the user security record
5. Compare the credential

**Both the identifier and the password are upper-cased — not just the identifier.** This is
observable behaviour a client depends on: `user0001` and `USER0001` are the same identifier,
and a lower-case password is accepted where its upper-case form would be. BCrypt
verification against the stored strength-10 hash replaces the legacy plaintext comparison.

**Exact observable literals.**

| Condition | Literal | Locator |
|---|---|---|
| Identifier blank | `'Please enter User ID ...'` | [`app/cbl/COSGN00C.cbl:L120`] |
| Password blank | `'Please enter Password ...'` | [`:L125`] |
| Password mismatch | `'Wrong Password. Try again ...'` | [`:L242-L243`] |
| User not found | `'User not found. Try again ...'` | [`:L249`] |
| Any other read outcome | `'Unable to verify the User ...'` | [`:L254`] |

**Failure and status mapping.**

| Outcome | Status | `errorCode` | `detail` |
|---|--:|---|---|
| Identifier or password blank | `400` | `CARDDEMO-VALIDATION-REJECTED` | The matching legacy literal above, plus `field` and `failureKind` |
| Credential refused — **either** unknown identifier **or** wrong password | `401` | `CARDDEMO-AUTHENTICATION-FAILED` | A **fixed** detail, `The user identifier or the password is incorrect.` No `field`, no `failureKind` |
| Security store could not be opened | `503` | `CARDDEMO-RESOURCE-UNAVAILABLE` | A fixed detail naming no dataset and no host |
| Store reachable but unreadable | `502` | `CARDDEMO-IO-FAILURE` | A fixed detail, deliberately the same shape as the `503` |
| Abend or any unclaimed typed failure | `500` | `CARDDEMO-PROCESSING-ABEND` / `CARDDEMO-INTERNAL-FAILURE` | A fixed detail plus the `correlationId` |

#### 9.1.1 Labelled deviation: the two refusal outcomes are made indistinguishable

**The source distinguishes them; this API deliberately does not.** `COSGN00C` publishes
`'Wrong Password. Try again ...'` for a bad password [`:L242-L243`] and
`'User not found. Try again ...'` for an unknown identifier [`:L249`] — two different
screen captions, which together form a **user-enumeration oracle**: an unauthenticated
caller can discover which identifiers exist by reading the message.

The target answers both with the **same status, the same title, the same fixed detail and
the same error code**, byte-identically, so the two cannot be told apart from outside. The
distinction is preserved only in the cause chain logged at `DEBUG`, where an operator must
opt in to see it.

This is a **labelled, deliberate divergence from strict parity** rather than an oversight,
and it is the remediation of finding **L-1** in
[§18](#18-findings-severity-classified). It is recorded rather than silent because a
reader comparing the two systems side by side would otherwise conclude a message was lost.

---

## 10. MenuController — 2 operations

Base path `/api/menu`. Both operations are **reads**: they return the option list for the
caller's user class and change nothing.

### 10.0 Preamble (not an operation): option dispatch does not survive into the target

The legacy `OPTIONI X(2)` field selected an `XCTL` target
[`app/cbl/COMEN01C.cbl:L152-L156`]. **There is no server-side dispatch endpoint in this
API, and none should be looked for.** A REST client navigates by URL.

The option numbering is preserved in the response so that it remains traceable to the
source, and the client-side navigation table below is the mapping a caller applies. Both
maps declare **twelve** option slots `OPTN001I` … `OPTN012I`, each `X(40)`, plus
`OPTIONI X(2)` and `ERRMSGI X(78)` — but the two option tables populate **ten** and
**four** respectively, so a response is bounded by its **count field**, never by the twelve
declared slots.

| Option | Main-menu caption `[app/cpy/COMEN02Y.cpy]` | Legacy program | Navigate to |
|--:|---|---|---|
| 1 | `Account View` | `COACTVWC` | [`GET /api/accounts/{accountId}`](#111-view-account) |
| 2 | `Account Update` | `COACTUPC` | [`PUT /api/accounts`](#112-update-account) |
| 3 | `Credit Card List` | `COCRDLIC` | [`GET /api/cards`](#121-list-cards) |
| 4 | `Credit Card View` | `COCRDSLC` | [`GET /api/cards/detail`](#122-view-card) |
| 5 | `Credit Card Update` | `COCRDUPC` | [`PUT /api/cards`](#123-update-card) |
| 6 | `Transaction List` | `COTRN00C` | [`GET /api/transactions`](#131-list-transactions) |
| 7 | `Transaction View` | `COTRN01C` | [`GET /api/transactions/detail`](#132-view-transaction) |
| 8 | `Transaction Add` | `COTRN02C` | [`POST /api/transactions`](#133-add-transaction) |
| 9 | `Transaction Reports` | `CORPT00C` | [`POST /api/reports`](#151-submit-transaction-report) |
| 10 | `Bill Payment` | `COBIL00C` | [`POST /api/billing/payments`](#141-bill-payment) |

| Option | Admin-menu caption `[app/cpy/COADM02Y.cpy]` | Legacy program | Navigate to |
|--:|---|---|---|
| 1 | `User List (Security)` | `COUSR00C` | [`GET /api/admin/users`](#161-list-users) |
| 2 | `User Add (Security)` | `COUSR01C` | [`POST /api/admin/users`](#162-add-user) |
| 3 | `User Update (Security)` | `COUSR02C` | [`PUT /api/admin/users/{userId}`](#163-update-user) |
| 4 | `User Delete (Security)` | `COUSR03C` | [`DELETE /api/admin/users/{userId}`](#164-delete-user) |

Captions are `PIC X(35)` in the option tables while the map slots are `X(40)`; both widths
are as declared and neither is normalised.

### 10.1 Main menu

**Legacy source.** CSD transaction `CM00` [`app/csd/CARDDEMO.CSD:L399`] → program
`COMEN01C` (282 lines) → `app/cpy-bms/COMEN01.CPY` (20 input fields), plus the option table
`app/cpy/COMEN02Y.cpy`.

**Purpose.** Returns the ten main-menu options available to the caller's user class, so a
client can render the same choices the 3270 main menu offered. It reads nothing else and
changes nothing.

```http
GET /api/menu/main HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request.** No path variables, no query parameters, no body. The caller's user class is
read from the token's `role` claim — the stateless replacement for `CDEMO-USER-TYPE`.

**Response — `200 OK`.** The option list bounded by the count field, plus the count itself:

| JSON member | Derived from | Notes |
|---|---|---|
| `menuType` | Which of the two option tables answered | `MAIN` here, `ADMIN` on [§10.2](#102-admin-menu). A response discriminator, not a screen field — one type serves both menus, and this is how a client tells them apart |
| `optionCount` | `CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10` [`app/cpy/COMEN02Y.cpy:L21`] | **10.** The bound on the array |
| `options[].optionNumber` | `PIC 9(02)` per row | 1 … 10 |
| `options[].optionName` | `PIC X(35)` per row | The caption, e.g. `Account View`, **space-padded to the declared 35** |
| `options[].userTypeCode` | `CDEMO-MENU-OPT-USRTYPE PIC X(01)` [`app/cpy/COMEN02Y.cpy:L92`] | The eligibility byte the gate below reads. `U` on all ten in the shipped table — see finding L-3 |

**`options[].programName` is not published**, although the option table declares it and the
record transcribes it. It is the operand of `EXEC CICS XCTL PROGRAM(...)`, the dispatch
mechanism [§10.0](#100-preamble-not-an-operation-option-dispatch-does-not-survive-into-the-target)
replaces with URL navigation, and it was reaching the wire as `COACTVWC`, `COUSR00C` and
their siblings (finding M-15). Navigate with the table in §10.0 instead; it maps each option
number to the URL that replaces its program.

Option rows beyond the count are **not** returned even though the map declares twelve slots
and the table declares `OCCURS 12 TIMES` [`app/cpy/COMEN02Y.cpy:L88`].

**Side effects.** None.

**Validation order and gates.** Three source behaviours are preserved:

1. **Bounds check** [`app/cbl/COMEN01C.cbl:L127-L134`]:
   `IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS`
   → `'Please enter a valid option number...'` [`:L131`]. Non-numeric, zero and
   above-count are all refused, and blank is normalised to zero first by
   `INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'` [`:L123`] — so a blank option is an
   out-of-range option, not a separate case.
2. **User-type gate** [`:L136-L143`]:
   `IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` →
   `'No access - Admin Only option... '` (note the **trailing space**). Administrator-only
   options are filtered from a standard user's list.
3. **Placeholder-program guard** [`:L146`]:
   `IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'`. An option whose target
   program name begins `DUMMY` is not dispatched; instead the source composes
   `'This option '` + the option caption + `'is coming soon ...'` [`:L159-L162`].

> **Source fact worth knowing (finding L-3).** The user-type gate is preserved, but with the
> **shipped** option table it is unreachable: all ten user-type bytes in
> `app/cpy/COMEN02Y.cpy` are `'U'` and none is `'A'`, so no option can satisfy
> `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'`. Relatedly, the caption
> `'Transaction Add (Admin Only)'` is **commented out** at
> [`app/cpy/COMEN02Y.cpy:L69`] and the active caption is `'Transaction Add'`. The
> consequence for a caller is that **every one of the ten main-menu options is available to
> a standard user**, which is why [§5.1](#51-role-model) grants both roles to all
> non-admin operations. The gate is retained for parity because a future option table could
> carry `'A'`.

**Exact observable literals.**

| Condition | Literal | Locator |
|---|---|---|
| Option non-numeric, zero, or above the count | `Please enter a valid option number...` | [`app/cbl/COMEN01C.cbl:L131`] |
| Standard user selected an administrator-only option | `No access - Admin Only option...` plus **one trailing space** in the source literal | [`:L140`] |
| Selected option targets a placeholder program | `This option` plus **one trailing space**, then the option caption truncated at its first space (`DELIMITED BY SPACE`), then `is coming soon ...` — which has **no leading space**, so the caption abuts `is` | [`:L159-L162`] |

**Failure and status mapping.** Per [§8.2](#82-global-failure-and-status-mapping), plus:

| Outcome | Status | Body |
|---|--:|---|
| No authenticated principal | `401` | Empty |
| Token carries neither recognised authority, so no user class could be resolved | `403` | Empty |
| Abend or unclaimed typed failure | `500` | `CARDDEMO-PROCESSING-ABEND` / `CARDDEMO-INTERNAL-FAILURE` |

**There is no `400` on either menu operation, and the three gates above cannot produce one.**
This table used to publish an "option validation refused → `400`" row; no request can reach
it (finding M-16). Both operations take **no input at all** — no path variable, no query
parameter, no body — because
[§10.0](#100-preamble-not-an-operation-option-dispatch-does-not-survive-into-the-target)
removes option dispatch: there is nothing for a caller to get wrong. The gates and their
literals are documented above because they are the source behaviour these two programs
contain and the traceability contract requires them to be transcribed and cited, not because
a client can trigger them. Two of the three could not be exercised even by a dispatch
endpoint with the **shipped** data: no user-type byte is `'A'` (finding L-3), and neither
option table contains a program name beginning `DUMMY`, so the placeholder arm has no
reachable input either.

### 10.2 Admin menu

**Legacy source.** CSD transaction `CA00` [`app/csd/CARDDEMO.CSD:L327`] → program
`COADM01C` (268 lines) → `app/cpy-bms/COADM01.CPY` (20 input fields), plus the option table
`app/cpy/COADM02Y.cpy`.

**Purpose.** Returns the four user-administration options, so an administrator client can
render the same choices the 3270 admin menu offered. It reads nothing else and changes
nothing.

```http
GET /api/menu/admin HTTP/1.1
Authorization: Bearer <token>
```

**Role. ADMIN only.** A standard user receives `403` with an empty body — the four options
target the user-administration programs, so the whole list is administrator-only.

**Request.** No path variables, no query parameters, no body.

**Response — `200 OK`.**

| JSON member | Derived from | Notes |
|---|---|---|
| `menuType` | Which of the two option tables answered | `ADMIN` here |
| `optionCount` | `CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4` [`app/cpy/COADM02Y.cpy:L20`] | **4** |
| `options[].optionNumber` | `PIC 9(02)` per row | 1 … 4 |
| `options[].optionName` | `PIC X(35)` per row | e.g. `User List (Security)`, space-padded to 35 |

An admin option row carries **no `userTypeCode`**, while a main-menu row does. That is the
source's own asymmetry, not an omission: `app/cpy/COADM02Y.cpy:L45-L48` declares three
sub-fields per row and no user-type byte, because the whole menu is administrator-only.
`options[].programName` is not published here either, for the reason given in
[§10.1](#101-main-menu).

The admin option table declares `OCCURS 9 TIMES` [`app/cpy/COADM02Y.cpy:L45`] while the map
declares twelve slots and the count field says four. **Four is the bound.** Unlike the main
menu table, this one carries no user-type byte at all, which is consistent with the whole
menu being administrator-only.

**Side effects.** None.

**Validation order and gates.**

1. **Bounds check** [`app/cbl/COADM01C.cbl:L124-L131`], identical in form to the main menu
   and with the identical literal `'Please enter a valid option number...'` [`:L131`],
   tested against `CDEMO-ADMIN-OPT-COUNT`.
2. **Placeholder-program guard** [`:L138`]:
   `IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'`, with the same
   `'This option '` … `'is coming soon ...'` composition [`:L146-L148`].

There is **no** user-type gate here, because reaching this operation at all already
required `ROLE_ADMIN`.

**Exact observable literals.**

| Condition | Literal | Locator |
|---|---|---|
| Option non-numeric, zero, or above the count | `Please enter a valid option number...` | [`app/cbl/COADM01C.cbl:L131`] |
| Selected option targets a placeholder program | `This option` plus **one trailing space**, then the option caption truncated at its first space (`DELIMITED BY SPACE`), then `is coming soon ...` — which has **no leading space**, so the caption abuts `is` | [`:L146-L148`] |

**Failure and status mapping.** As [§10.1](#101-main-menu), except that a non-administrator
principal is `403` rather than being served a filtered list.

---

## 11. AccountController — 2 operations

Base path `/api/accounts`.

### 11.1 View account

**Legacy source.** CSD transaction `CAVW` [`app/csd/CARDDEMO.CSD:L317`] → program
`COACTVWC` (941 lines) → `app/cpy-bms/COACTVW.CPY` (**37** input fields).

**Purpose.** Returns one account together with its owning customer, following the same
three-dataset lookup chain the source follows. It is also **the only way to obtain the
precondition token** that [§11.2](#112-update-account) requires.

```http
GET /api/accounts/{accountId} HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request.**

| Kind | Name | Map field | PIC | Required |
|---|---|---|---|:--:|
| Path variable | `accountId` | `ACCTSIDI` | **`PIC 99999999999`** | **yes** |

> **`ACCTSIDI` is the only numeric-picture input field across all seventeen symbolic maps.**
> Every other input field in the corpus is `PIC X(n)`; this one is declared
> `PIC 99999999999` — eleven **digits**, not `X(11)`
> [`app/cpy-bms/COACTVW.CPY`]. It is called out because it is genuinely exceptional, and
> because the account-update map declares the same logical value as `ACCTSIDI X(11)`
> ([§11.2](#112-update-account)) — the two maps disagree, and both are published as
> declared. For a caller the practical effect is the same: **digits only**, eleven of them,
> and the source additionally refuses zero with
> `'Account number must be a non zero 11 digit number'`.

**Response — `200 OK`.** Thirty-five business fields, all as declared in
`app/cpy-bms/COACTVW.CPY`:

| Map field | PIC | Meaning |
|---|---|---|
| `ACCTSIDI` | `PIC 99999999999` | Account identifier |
| `ACSTTUSI` | `X(1)` | Account status |
| `ADTOPENI` | `X(10)` | Open date |
| `ACRDLIMI` | `X(15)` | Credit limit |
| `AEXPDTI` | `X(10)` | Expiry date |
| `ACSHLIMI` | `X(15)` | Cash credit limit |
| `AREISDTI` | `X(10)` | Reissue date |
| `ACURBALI` | `X(15)` | Current balance |
| `ACRCYCRI` | `X(15)` | Current cycle credit |
| `AADDGRPI` | `X(10)` | Account group identifier |
| `ACRCYDBI` | `X(15)` | Current cycle debit |
| `ACSTNUMI` | `X(9)` | Customer identifier |
| `ACSTSSNI` | `X(12)` | Customer social security number |
| `ACSTDOBI` | `X(10)` | Customer date of birth |
| `ACSTFCOI` | `X(3)` | FICO credit score |
| `ACSFNAMI` | `X(25)` | Customer first name |
| `ACSMNAMI` | `X(25)` | Customer middle name |
| `ACSLNAMI` | `X(25)` | Customer last name |
| `ACSADL1I` | `X(50)` | Address line 1 |
| `ACSSTTEI` | `X(2)` | State code |
| `ACSADL2I` | `X(50)` | Address line 2 |
| `ACSZIPCI` | `X(5)` | Postal code |
| `ACSCITYI` | `X(50)` | City (address line 3) |
| `ACSCTRYI` | `X(3)` | Country code |
| `ACSPHN1I` | `X(13)` | Telephone 1 — **one whole field on this map** |
| `ACSGOVTI` | `X(20)` | Government-issued identifier |
| `ACSPHN2I` | `X(13)` | Telephone 2 — **one whole field on this map** |
| `ACSEFTCI` | `X(10)` | Electronic funds transfer account identifier |
| `ACSPFLGI` | `X(1)` | Primary card-holder indicator |
| `INFOMSGI` | `X(45)` | Informational message |
| `ERRMSGI` | `X(78)` | Error message |

The five money fields (`ACRDLIMI`, `ACSHLIMI`, `ACURBALI`, `ACRCYCRI`, `ACRCYDBI`) are
`BigDecimal`-backed decimals per [§6.2](#62-monetary-representation).

Two further response members exist that have no map field, because they carry the write
precondition:

| Member | Notes |
|---|---|
| `snapshotToken` | The **sealed, opaque, server-issued as-displayed snapshot**. Required by [§11.2](#112-update-account). Treat it as an opaque string: do not parse it, do not construct it, do not modify it |
| `ETag` (response **header**) | The same token, quoted, so a caller may use the standard conditional-request idiom instead of reading it out of the body |

Note the asymmetry in what is *published*: `ACSTSSNI`, `ACSTDOBI`, `ACSPHN1I`, `ACSPHN2I`,
`ACSGOVTI` and `ACSEFTCI` are on this map and are therefore part of the view contract, but
they are **also** members of the snapshot group — which is exactly why the snapshot is
sealed rather than returned in readable form ([§11.2.3](#1123-why-the-snapshot-is-sealed-and-server-issued)).

**Side effects.** None on business data. The operation performs one additional read to mint
the snapshot token; that read is not a write.

**Validation and lookup order.** Preserved from the source:

1. **Input validation.** No input at all → `'No input received'`. Zero or non-numeric
   account identifier → `'Account number must be a non zero 11 digit number'`
   [`app/cbl/COACTVWC.cbl:L125-L128`] — both the zeroes and the not-numeric condition names
   carry that **same** literal.
2. **Cross-reference lookup** (card cross-reference by account).
3. **Account master lookup.**
4. **Customer master lookup.**

Typed exceptions replace the response-code branching; the order is what determines which
message a caller sees first.

**Exact observable literals.** These belong to `COACTVWC` and are **not** the same strings
as the account-update program's — the two programs word the same conditions differently, and
each is published where it actually occurs:

| Condition name | Literal | Locator |
|---|---|---|
| — | `'No input received'` | [`app/cbl/COACTVWC.cbl:L124`] |
| `SEARCHED-ACCT-ZEROES` | `'Account number must be a non zero 11 digit number'` | [`:L125-L126`] |
| `SEARCHED-ACCT-NOT-NUMERIC` | `'Account number must be a non zero 11 digit number'` | [`:L127-L128`] |
| `DID-NOT-FIND-ACCT-IN-CARDXREF` | `'Did not find this account in account card xref file'` | [`:L129-L130`] |
| `DID-NOT-FIND-ACCT-IN-ACCTDAT` | `'Did not find this account in account master file'` | [`:L131-L132`] |
| `DID-NOT-FIND-CUST-IN-CUSTDAT` | `'Did not find associated customer in master file'` | [`:L133-L134`] |
| `XREF-READ-ERROR` | `'Error reading account card xref File'` | [`:L135-L136`] |

**Failure and status mapping.**

| Outcome | Status | `errorCode` |
|---|--:|---|
| No authenticated principal | `401` (empty body) | — |
| Blank, zero or non-numeric account identifier | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| Cross-reference, account or customer record absent | `404` | `CARDDEMO-RECORD-NOT-FOUND` |
| A required store could not be opened | `503` | `CARDDEMO-RESOURCE-UNAVAILABLE` |
| Read failure | `502` | `CARDDEMO-IO-FAILURE` |
| Abend or unclaimed typed failure | `500` | `CARDDEMO-PROCESSING-ABEND` / `CARDDEMO-INTERNAL-FAILURE` |

### 11.2 Update account

**Legacy source.** CSD transaction `CAUP` [`app/csd/CARDDEMO.CSD:L306`] → program
`COACTUPC` — at **4,236 lines the largest program in the corpus** — over mapset
`COACTUP`, symbolic map `app/cpy-bms/COACTUP.CPY` (54 input fields).

**Purpose.** Applies an edited account **together with** its customer record, as one atomic
unit, but only if the record still matches what the caller was shown. This is the most
contract-heavy operation in the API and the one most likely to be integrated wrongly, so it
is documented at length.

```http
PUT /api/accounts?confirm=true HTTP/1.1
Authorization: Bearer <token>
If-Match: "<snapshotToken from GET /api/accounts/{accountId}>"
Content-Type: application/json
```

**Role.** USER or ADMIN.

**This is a full replacement of the detail set, not a partial update.** There is no `PATCH`
semantic and no partial-update mode: the source moves every edited field into the update
image before rewriting, so the body carries the complete set. Omitting a field does not mean
"leave it alone".

**Request.** Three parts — a query parameter, a header and a body — set out in
[§11.2.1](#1121-request-in-three-parts).

**Validation order.** Confirmation gate first, then the body-carried-snapshot refusal, then
field validation, then the seven-step write sequence of
[§11.2.8](#1128-atomicity-and-why-the-rollback-has-not-been-lost) — whose first three steps
are the account lock, the customer lock and the snapshot comparison, in that order. The first
failure wins.

**Exact observable literals and failure mapping.** All six outcome literals, each with its
own distinct HTTP status, are tabulated in
[§11.2.6](#1126-outcome-markers-and-status-mapping). Everything else follows
[§8.2](#82-global-failure-and-status-mapping).

#### 11.2.1 Request in three parts

**(a) Query parameter — the confirmation gate.**

| Parameter | Required | Accepted values |
|---|:--:|---|
| `confirm` | effectively **yes** | Exactly `true` or `false`. **No alias and no other spelling** |

This is the PF5 gate of [`app/cbl/COACTUPC.cbl:L2602-L2603`] — the single decision that
separates validating a payload from writing two datasets. The framework's default boolean
binding would additionally accept `on`, `off`, `yes`, `no`, `1` and `0`; that is deliberately
**not** allowed here, because admitting six spellings of an instruction the operation never
declared is least defensible on precisely the parameter where it matters most.

* Absent → treated as *not confirmed*: `428 Precondition Required`, **nothing is written**,
  and the response carries the source's own prompt `'Changes validated.Press F5 to save'`.
* Present but empty → `400`, `failureKind` blank.
* Present and neither exact token → `400`, `failureKind` invalid.

**(b) Header — the as-displayed snapshot.**

| Header | Required | Value |
|---|:--:|---|
| `If-Match` | **yes** | The `snapshotToken` from [`GET /api/accounts/{accountId}`](#111-view-account), quoted or bare; a `W/` weak-validator prefix is tolerated and stripped |

**(c) Body — the 54 map fields.** Every field is as declared in `app/cpy-bms/COACTUP.CPY`:

| Map field | PIC | Map field | PIC | Map field | PIC |
|---|---|---|---|---|---|
| `ACCTSIDI` | `X(11)` | `ACSTNUMI` | `X(9)` | `ACSADL2I` | `X(50)` |
| `ACSTTUSI` | `X(1)` | `ACTSSN1I` | `X(3)` | `ACSZIPCI` | `X(5)` |
| `OPNYEARI` | `X(4)` | `ACTSSN2I` | `X(2)` | `ACSCITYI` | `X(50)` |
| `OPNMONI` | `X(2)` | `ACTSSN3I` | `X(4)` | `ACSCTRYI` | `X(3)` |
| `OPNDAYI` | `X(2)` | `DOBYEARI` | `X(4)` | `ACSPH1AI` | `X(3)` |
| `ACRDLIMI` | `X(15)` | `DOBMONI` | `X(2)` | `ACSPH1BI` | `X(3)` |
| `EXPYEARI` | `X(4)` | `DOBDAYI` | `X(2)` | `ACSPH1CI` | `X(4)` |
| `EXPMONI` | `X(2)` | `ACSTFCOI` | `X(3)` | `ACSGOVTI` | `X(20)` |
| `EXPDAYI` | `X(2)` | `ACSFNAMI` | `X(25)` | `ACSPH2AI` | `X(3)` |
| `ACSHLIMI` | `X(15)` | `ACSMNAMI` | `X(25)` | `ACSPH2BI` | `X(3)` |
| `RISYEARI` | `X(4)` | `ACSLNAMI` | `X(25)` | `ACSPH2CI` | `X(4)` |
| `RISMONI` | `X(2)` | `ACSADL1I` | `X(50)` | `ACSEFTCI` | `X(10)` |
| `RISDAYI` | `X(2)` | `ACSSTTEI` | `X(2)` | `ACSPFLGI` | `X(1)` |
| `ACURBALI` | `X(15)` | `INFOMSGI` | `X(45)` | `ERRMSGI` | `X(78)` |
| `ACRCYCRI` | `X(15)` | `AADDGRPI` | `X(10)` | `ACRCYDBI` | `X(15)` |

Plus the six chrome header fields and the three function-key chrome fields
`FKEYSI X(21)`, `FKEY05I X(7)`, `FKEY12I X(10)`, all accepted at their declared widths and
ignored — 54 fields in total.

**These flat screen fields are the authoritative edited values.** A caller may additionally
send a `newDetails` group, which transcribes `ACUP-NEW-DETAILS`
[`app/cbl/COACTUPC.cbl:L757`] — but **its edited values are deliberately not trusted**. The
source opens `1100-RECEIVE-MAP` with `INITIALIZE ACUP-NEW-DETAILS`
[`app/cbl/COACTUPC.cbl:L1047`], clearing the group before re-deriving every value from the
received screen fields, so an echoed group from a previous turn is discarded. The target does
the same. Exactly one member of `newDetails` is consulted: `customerId`, cross-checked against
the customer the cross-reference binds to the account — a disagreement refuses the update with
`Record changed by some one else. Please review`, on the same reasoning as any other snapshot
mismatch. Sending `newDetails` is therefore optional and never a substitute for the flat
fields; sending `oldDetails` is refused outright
([§11.2.3](#1123-why-the-snapshot-is-sealed-and-server-issued)).

**A single asterisk means "not supplied", but a run of asterisks is data.** The source applies
one uniform normalisation to each of the fifty-four fields — `IF field = '*' OR field = SPACES`
→ `MOVE LOW-VALUES`, `ELSE MOVE field` — an idiom that occurs **43 times** in the program, the
first instance at [`app/cbl/COACTUPC.cbl:L1051-L1058`]. So `"*"` and `""` both mean *this field
was cleared*, whereas `"***"` is carried through as a literal three-character value. A caller
must not use a single asterisk as filler.

#### 11.2.2 Component decomposition — do not collapse these

The update map decomposes three kinds of value that the **view** map carries whole. The
request must honour the decomposition, because **the legacy comparison operates on the
components**:

| Value | On the view map | On the update map |
|---|---|---|
| Open date | `ADTOPENI X(10)` | `OPNYEARI X(4)` + `OPNMONI X(2)` + `OPNDAYI X(2)` |
| Expiry date | `AEXPDTI X(10)` | `EXPYEARI X(4)` + `EXPMONI X(2)` + `EXPDAYI X(2)` |
| Reissue date | `AREISDTI X(10)` | `RISYEARI X(4)` + `RISMONI X(2)` + `RISDAYI X(2)` |
| Date of birth | `ACSTDOBI X(10)` | `DOBYEARI X(4)` + `DOBMONI X(2)` + `DOBDAYI X(2)` |
| Social security number | `ACSTSSNI X(12)` | `ACTSSN1I X(3)` + `ACTSSN2I X(2)` + `ACTSSN3I X(4)` |
| Telephone 1 | `ACSPHN1I X(13)` | `ACSPH1AI X(3)` + `ACSPH1BI X(3)` + `ACSPH1CI X(4)` |
| Telephone 2 | `ACSPHN2I X(13)` | `ACSPH2AI X(3)` + `ACSPH2BI X(3)` + `ACSPH2CI X(4)` |

Sending a concatenated `"1985-03-17"` where three components are expected, or a single
`"123456789"` where a three-part social security number is expected, is a contract error.

#### 11.2.3 Why the snapshot is sealed and server-issued

The source compares the freshly read record against a **snapshot captured when the screen
was first displayed**, held in two working-storage groups: `ACUP-OLD-DETAILS`
[`app/cbl/COACTUPC.cbl:L669`] and `ACUP-NEW-DETAILS` [`:L757`]. A write is accepted only
when the live record still matches the old group field by field.

Two things follow, and both are load-bearing.

**First, a version counter is not enough.** A JPA `@Version` column detects that *some*
concurrent write occurred. The legacy check detects that *specific business field values*
differ from what the user was shown. These are different guarantees: a concurrent write that
set a field back to its original value **passes** the legacy check and **fails** a version
check. **Both layers are therefore required and neither substitutes for the other** — the
version column guards the store, the field comparison guards the business decision.

**Second, a stateless server cannot hold the snapshot between the read and the write**
([§5](#5-stateless-identity-the-commarea-has-no-server-side-successor)), so it has to travel
with the request. The way it travels is the part a client must get right:

> **The snapshot is issued by the server, sealed, and returned in `If-Match`. It is not
> built by the client and it is not sent in the request body.**
>
> `GET /api/accounts/{accountId}` returns it as `snapshotToken` and as the `ETag`.
> `PUT /api/accounts` reads it from `If-Match` **and from nowhere else**.
>
> **A request body carrying an `oldDetails` group is refused with `400`**, not ignored.
> Refusing states the contract at the one moment the caller can act on it; ignoring it would
> leave a caller believing it controlled the write precondition when it did not — a failure
> that is silent and shows up only under concurrency.

The reason the token is **sealed** rather than published in readable form is disclosure: the
snapshot group carries the date of birth, the social security number, the government-issued
identifier, both telephone numbers and the electronic funds account identifier. Publishing
those in a form a client can hold and replay is not acceptable, and a caller-supplied
precondition is in any case not a precondition.

#### 11.2.4 Snapshot comparison semantics

A client never formats the snapshot itself, because it is sealed — but the semantics below
are what the sealed token encodes, and they are published for three reasons: they explain
**which** concurrent changes trigger a conflict, they are the contract the target must keep,
and they explain the one trap that would otherwise be invisible.

`9700-CHECK-CHANGE-IN-REC` [`app/cbl/COACTUPC.cbl:L4109`] is **two** `IF` statements. The
account arm carries **sixteen comparison terms over ten fields** [`:L4115-L4140`]; the
customer arm carries **nineteen terms over seventeen fields** [`:L4152-L4186`]. Any mismatch
sets `DATA-WAS-CHANGED-BEFORE-UPDATE` and abandons the write.

**(a) Dates are compared as three separate substrings, never as whole strings:**

```cobol
           AND ACCT-OPEN-DATE(1:4)     EQUAL ACUP-OLD-OPEN-YEAR
           AND ACCT-OPEN-DATE(6:2)     EQUAL ACUP-OLD-OPEN-MON
           AND ACCT-OPEN-DATE(9:2)     EQUAL ACUP-OLD-OPEN-DAY
           AND ACCT-EXPIRAION-DATE(1:4)EQUAL ACUP-OLD-EXP-YEAR
           AND ACCT-EXPIRAION-DATE(6:2)EQUAL ACUP-OLD-EXP-MON
           AND ACCT-EXPIRAION-DATE(9:2)EQUAL ACUP-OLD-EXP-DAY
           AND ACCT-REISSUE-DATE(1:4)  EQUAL ACUP-OLD-REISSUE-YEAR
           AND ACCT-REISSUE-DATE(6:2)  EQUAL ACUP-OLD-REISSUE-MON
           AND ACCT-REISSUE-DATE(9:2)  EQUAL ACUP-OLD-REISSUE-DAY
```

**Note the source's misspelling of "EXPIRATION" as `ACCT-EXPIRAION-DATE`.** It is part of
the field contract, it appears throughout the corpus, and it is preserved rather than
corrected.

**(b) Case handling is deliberately asymmetric — three regimes, not one.**

| Regime | Fields | Locator |
|---|---|---|
| `FUNCTION LOWER-CASE` on **both** sides | Account group identifier — **one field** | [`:L4139-L4140`] |
| `FUNCTION UPPER-CASE` on **both** sides | Customer first, middle and last name; address lines 1, 2 and 3; state code; country code — [`:L4152-L4167`] — and the government-issued identifier — [`:L4172-L4173`]. **Nine fields** | [`:L4152-L4167`, `:L4172-L4173`] |
| **No case function at all** | Postal code, telephone 1, telephone 2 and social security number — [`:L4168-L4171`] — plus the electronic funds account identifier, the primary card-holder indicator and the FICO credit score — [`:L4181-L4186`]. **Seven fields** | [`:L4168-L4171`, `:L4181-L4186`] |

Normalising this in either direction changes which updates are accepted. It is not noise;
it is the behaviour.

**(c) The date of birth is compared at *different offsets on each side*.**

> **This is the single most important detail on this page for anyone building or debugging
> a snapshot, and it is why the token is opaque rather than caller-constructed.**

The live customer record holds a **dash-separated** date, so its components sit at offsets
1, 6 and 9. The snapshot holds the **compact** form, so its components sit at offsets 1, 5
and 7. The source compares **1↔1, 6↔5, 9↔7** [`app/cbl/COACTUPC.cbl:L4174-L4179`]:

```cobol
           AND CUST-DOB-YYYY-MM-DD (1:4)                       EQUAL
               ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)
           AND CUST-DOB-YYYY-MM-DD (6:2)                       EQUAL
               ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)
           AND CUST-DOB-YYYY-MM-DD (9:2)                       EQUAL
               ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)
```

**The snapshot date of birth is therefore held compact `YYYYMMDD`, never dash-separated
`YYYY-MM-DD`.** A whole-string comparison of the two forms would never match, so **any
implementation or client that treated the snapshot date as dash-separated would return a
conflict on every single request**, making the operation permanently unusable. The
server-issued sealed token stores it compact, which is why obtaining the token from the view
operation — rather than assembling one — is not merely convenient but the only correct
route.

#### 11.2.5 Response and side effects

**Response — `200 OK`.**

| JSON member | Derived from | Meaning |
|---|---|---|
| `accountId` | `ACCTSIDI` | Echo of the submitted identifier |
| `changeAction` | The source's `ACUP-CHANGE-ACTION` outcome vocabulary | Which arm of [`app/cbl/COACTUPC.cbl:L2606-L2615`] was taken |
| `applied` | — | `true` for exactly one change action, `CHANGES_OKAYED_AND_DONE` |
| `informationMessage` | `INFOMSGI X(45)` | The source's own screen notice |
| `errorMessage` | `ERRMSGI X(78)` | The source's own screen caption when one was set |

`applied` is **not** derived from the absence of an error message, because of the trap in
[§11.2.7](#1127-preserved-legacy-trap-an-unreported-customer-lock-failure); the error
message is reported alongside it so a caller can see both.

Three things are deliberately **withheld** from the response: the submitted map plus the
snapshot group (fifty-four fields including the protected six); the navigation members, which
describe a screen flow that does not exist here; and the field-attribute and cursor-position
members, which are 3270 presentation instructions with no meaning to an HTTP client.

**Side effects.** Two rows are written — one account and one customer — **inside a single
transaction**. No queue message is published and no object is emitted.

#### 11.2.6 Outcome markers and status mapping

The source declares four outcome flags [`app/cbl/COACTUPC.cbl:L517-L524`] plus two related
lookup markers. **Each maps to a distinguishable HTTP status.** Collapsing them into a
single conflict status would lose information the legacy screen displayed, so they are kept
apart:

| Legacy condition name | Exact literal | Meaning | Status | `errorCode` |
|---|---|---|--:|---|
| `COULD-NOT-LOCK-ACCT-FOR-UPDATE` | `Could not lock account record for update` | Account read-for-update failed | `423` | `CARDDEMO-UPDATE-CONFLICT` |
| `COULD-NOT-LOCK-CUST-FOR-UPDATE` | `Could not lock customer record for update` | Customer read-for-update failed | `423` | `CARDDEMO-UPDATE-CONFLICT` |
| `DATA-WAS-CHANGED-BEFORE-UPDATE` | `Record changed by some one else. Please review` | The snapshot comparison detected a change | `412` | `CARDDEMO-UPDATE-CONFLICT` |
| `LOCKED-BUT-UPDATE-FAILED` | `Update of record failed` | A rewrite failed after locks were held | `409` | `CARDDEMO-UPDATE-CONFLICT` |
| `DID-NOT-FIND-ACCT-IN-CARDXREF` | `Did not find this account in cards database` | Cross-reference lookup found nothing | `404` | `CARDDEMO-RECORD-NOT-FOUND` |
| `XREF-READ-ERROR` | `Error reading Card Data File` | Cross-reference read failed | `502` | `CARDDEMO-IO-FAILURE` |
| *(no legacy flag — the confirmation gate)* | `Changes validated.Press F5 to save` | `confirm` not asserted, or no snapshot presented | `428` | `CARDDEMO-UPDATE-CONFLICT` |

**Spelling and spacing are preserved exactly**, including `some one` as two words in the
data-changed literal [`app/cbl/COACTUPC.cbl:L521-L522`]. A client matching on that text must
match it as written.

Locators: [`:L513-L514`], [`:L517-L518`], [`:L519-L520`], [`:L521-L522`],
[`:L523-L524`], [`:L525-L526`].

Field validation failures answer `400` with the offending field's own literal — for example
`'Credit Limit must be supplied'` [`:L505-L506`] or `'Credit Limit is not valid'`
[`:L507-L508`]. The general table in [§8.2](#82-global-failure-and-status-mapping) covers
everything else.

#### 11.2.7 Preserved legacy trap: an unreported customer-lock failure

The outcome `EVALUATE` at [`app/cbl/COACTUPC.cbl:L2606-L2615`] tests
`COULD-NOT-LOCK-ACCT-FOR-UPDATE`, `LOCKED-BUT-UPDATE-FAILED` and
`DATA-WAS-CHANGED-BEFORE-UPDATE` — but **never** `COULD-NOT-LOCK-CUST-FOR-UPDATE`. That
condition therefore falls through `WHEN OTHER` [`:L2613-L2614`] to
`SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE`: the source **reports success** for a customer
lock it failed to take.

The behaviour is preserved — the status stays `200` — because parity is the contract. It is
made visible instead: the condition is logged at `WARN`, and the response reports
`changeAction` and `errorMessage` alongside `applied`, so a caller has the evidence the
legacy screen did not surface. Recorded as finding **M-3** in
[§18](#18-findings-severity-classified).

#### 11.2.8 Atomicity, and why the rollback has not been lost

The seven-step write sequence of `9600-WRITE-PROCESSING` [`app/cbl/COACTUPC.cbl:L3888`] is
preserved in order: read the account for update [`:L3894-L3906`], whose failure sets
`COULD-NOT-LOCK-ACCT-FOR-UPDATE` [`:L3912`] and branches to the exit [`:L3914`]; read the
customer for update [`:L3921-L3932`], whose failure sets a **distinct**
`COULD-NOT-LOCK-CUST-FOR-UPDATE` [`:L3939`] and likewise branches [`:L3941`]; run the
change-detection comparison [`:L3947-L3951`]; initialise the update images and move the new
values; rewrite the account [`:L4065-L4071`]; rewrite the customer [`:L4085-L4091`]; exit
[`:L4105`].

The source issues an explicit backout on **only one** of the two rewrite-failure paths:

* Account rewrite fails [`:L4079-L4080`] → set `LOCKED-BUT-UPDATE-FAILED`, branch to exit,
  **no rollback**.
* Customer rewrite fails [`:L4098-L4102`] → set the same flag, `EXEC CICS SYNCPOINT
  ROLLBACK`, then branch to exit.

**That asymmetry is correct, not a defect.** At the earlier failure point nothing has yet
been written inside the unit of work, so the locks are released at task end with no explicit
action; at the later point the account rewrite has already happened inside the same unit of
work, so an explicit backout is the only way to avoid a half-applied update.

A single `@Transactional(rollbackFor = Exception.class)` method spanning both writes
**reproduces both branches automatically**, because each failure path returns or throws
before the commit point. This is stated explicitly so that a reader comparing the two
sources side by side does not see a `SYNCPOINT ROLLBACK` with no Java counterpart and
conclude something was lost. It is a mechanism substitution, not a behaviour change.

---

## 12. CardController — 3 operations

Base path `/api/cards`.

### 12.1 List cards

**Legacy source.** CSD transaction `CCLI` [`app/csd/CARDDEMO.CSD:L357`] → program
`COCRDLIC` (1,459 lines) → `app/cpy-bms/COCRDLI.CPY` (45 input fields). **Page size 7.**

**Purpose.** Returns a page of cards, optionally narrowed by account or by card number.

```http
GET /api/cards?accountFilter=00000000011&page=1 HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request — query parameters.**

| Parameter | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| `accountFilter` | `ACCTSIDI` | `X(11)` | no | Filter by account. Refused when supplied and not an eleven-digit non-zero number |
| `cardFilter` | `CARDSIDI` | `X(16)` | no | Filter by card number. Refused when supplied and not a sixteen-digit number |
| `page` | `PAGENOI` | `X(3)` | no | Digits only. Bounded by the three-character screen field |
| `action`, `firstKey`, `lastKey`, `nextPageAvailable` | — | — | no | Paging control, see [§7.1](#71-paging-parameters) |

**Three filter states, not two.** A filter that is **absent**, one that is **blank** and one
that is **supplied and wrong** are three distinguishable conditions, and the source keeps
them apart — which is why the error envelope carries `failureKind` to separate a blank input
from an invalid one. Both filters absent is legitimate: it lists all cards.

The account filter is served by a derived finder standing in for the card alternate index
`CARDDATA.VSAM.AIX`, whose alternate key sits at **byte 16** of the base record.

**Response — `200 OK`.** Seven row groups maximum. Per row:

| Map field (row *n*) | PIC | Meaning | Published as |
|---|---|---|---|
| `ACCTNOnI` | `X(11)` | Account number | in full |
| `CRDNUMnI` | `X(16)` | Card number | **masked** — see [§12.1.1](#1211-labelled-deviation-a-list-row-masks-the-card-number-and-carries-a-handle-instead) |
| `CRDSTSnI` | `X(1)` | Card status | in full |

Each row additionally carries one member that is **not** a map field:

| Row member | Notes |
|---|---|
| `cardKey` | An opaque, sealed, server-issued reference to *that row's* card, accepted in place of the two filters by [`GET /api/cards/detail`](#122-view-card) and [`PUT /api/cards`](#123-update-card). See [§12.1.1](#1211-labelled-deviation-a-list-row-masks-the-card-number-and-carries-a-handle-instead) |

Plus paging metadata per [§7](#7-pagination-contract), and the two message fields
`INFOMSGI X(45)` and `ERRMSGI X(78)`.

> **Source asymmetry worth knowing.** Row 1 declares only four fields — `CRDSEL1I X(1)`,
> `ACCTNO1I X(11)`, `CRDNUM1I X(16)`, `CRDSTS1I X(1)` — while rows 2 through 7 declare
> **five**, adding `CRDSTPnI X(1)`. **There is no `CRDSTP1` field on the map**
> [`app/cpy-bms/COCRDLI.CPY`]. The row shape is therefore not uniform in the source. It is
> uniform in the JSON, because neither of the two extra columns is part of the JSON contract
> — see immediately below — so the asymmetry has no observable effect here. It is recorded
> because anyone verifying field counts against the copybook will meet it.

**`CRDSEL` and `CRDSTP` are terminal interaction artefacts and are not published.**
`CRDSELnI` was the selection column into which a 3270 operator typed `S` to view or `U` to
update a row — the source prompts exactly that with
`'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD'` [`app/cbl/COCRDLIC.cbl:L115-L116`].
`CRDSTPnI` was a row-type marker driving screen attributes. In REST the equivalent of
selecting a row is **following a reference**: a client takes the row's `cardKey` and calls
[`GET /api/cards/detail`](#122-view-card) or [`PUT /api/cards`](#123-update-card) with it.
Neither column appears in a request or a response, and **no bulk-selection endpoint exists**,
because the source has none: it refuses multiple selections outright with
`'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE'` [`:L123-L124`].

#### 12.1.1 Labelled deviation: a list row masks the card number and carries a handle instead

The 3270 list showed the full sixteen-digit card number in `CRDNUMnI`, and an operator
selected a row by typing next to it. This API **does not publish the full number on a list
row** — it publishes a masked rendering — which is a deliberate divergence from strict parity
and is recorded here rather than left for a reader to discover.

**Why.** A list is a bulk disclosure: one authorised read returns seven card numbers, and
walking the pages returns every card the caller may see. The primary account number is
regulated data, and the target's own log configuration masks it
([§8.4](#84-credentials-and-secrets)); publishing in a response body what is scrubbed from a
log line would be incoherent. The detail operation, which discloses one card the caller has
already named, publishes it in full.

**What replaces it.** Masking on its own would break the one thing the column existed for:
without the number, none of the values a row discloses is accepted by any other card
operation, so a client reading only this API could reach a row and then go no further. Each
row therefore carries `cardKey` — the account number and the card number sealed together by
the same authenticated construction that seals the snapshot token
([§11.2.3](#1123-why-the-snapshot-is-sealed-and-server-issued)) — and both
`GET /api/cards/detail` and `PUT /api/cards` accept it in place of the two filters.

**What the handle is and is not.**

* It is **opaque**: it carries no readable card number, and it is not a URL, a row index or a
  database identifier.
* It is **scoped**: it is sealed under its own purpose label, so a paging cursor cannot be
  presented as a card reference and a card reference cannot be presented as a paging cursor.
  Either substitution is refused with `400` naming `cardKey`.
* It is **unambiguous**: presenting `cardKey` *together with* `accountFilter` or `cardFilter`
  is refused with `400` naming `cardKey`, because two different statements of which card is
  meant is not a request the operation can honour.
* It is **not a precondition**. A handle says which card; the snapshot token
  ([§12.2](#122-view-card)) says what the caller was shown. An update needs both.
* It is **not durable**: treat it as valid for the conversation in which it was issued. A
  client that has kept one across a restart re-reads the list.

**What is unchanged.** The two filters continue to work exactly as the source specifies, in
the source's own order — `2210-EDIT-ACCOUNT` then `2220-EDIT-CARD` — because a `cardKey` is
resolved into those two values *before* the operation runs. Nothing downstream of the
resolution can tell which form the caller used.

**Side effects.** None.

**Validation order.** Filter validation first — account filter, then card filter — then the
browse. An invalid action code is refused before any read.

**Exact observable literals.**

| Condition name | Literal | Locator |
|---|---|---|
| `WS-INFORM-REC-ACTIONS` | `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD` | [`app/cbl/COCRDLIC.cbl:L115-L116`] |
| `WS-EXIT-MESSAGE` | `PF03 PRESSED.EXITING` | [`:L119-L120`] |
| `WS-NO-RECORDS-FOUND` | `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` | [`:L121-L122`] |
| `WS-MORE-THAN-1-ACTION` | `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE` | [`:L123-L124`] |
| `WS-INVALID-ACTION-CODE` | `INVALID ACTION CODE` | [`:L125-L126`] |

This program also composes an I/O diagnostic from the fragments `'File Error:'`, `' on '`,
`' returned RESP '` and `',RESP2 '` [`:L153-L171`]. That composition names an operation, a
dataset and two response codes, so it is **logged, not published** — the caller receives the
status and `errorCode` of [§8.2](#82-global-failure-and-status-mapping) instead.

**Failure and status mapping.** Per [§8.2](#82-global-failure-and-status-mapping). Note that
`NO RECORDS FOUND FOR THIS SEARCH CONDITION.` is a **successful `200` with an empty row
array**, not a `404`: an empty browse is end-of-data, which is a control outcome.

### 12.2 View card

**Legacy source.** CSD transaction `CCDL` [`app/csd/CARDDEMO.CSD:L347`] → program
`COCRDSLC` (887 lines) → `app/cpy-bms/COCRDSL.CPY` (15 input fields). `README.md:L216`
labels this transaction **"Credit Card View"**.

**Purpose.** Returns one card. It is also **the only way to obtain the precondition token**
that [§12.3](#123-update-card) requires.

```http
GET /api/cards/detail?accountFilter=00000000011&cardFilter=4111111111111111 HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request — query parameters.**

| Parameter | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| `accountFilter` | `ACCTSIDI` | `X(11)` | see below | Eleven-digit non-zero number when supplied |
| `cardFilter` | `CARDSIDI` | `X(16)` | see below | Sixteen-digit number when supplied |
| `cardKey` | — | — | see below | A row reference from [`GET /api/cards`](#121-list-cards), accepted **instead of** the two filters |

At least one filter must be supplied: with neither, the source answers
`'No input received'` [`app/cbl/COCRDSLC.cbl:L143`].

**Or one `cardKey`, which is the same request said differently.** A `cardKey` from a list row
([§12.1.1](#1211-labelled-deviation-a-list-row-masks-the-card-number-and-carries-a-handle-instead))
is resolved into the two filters before the operation runs, so it satisfies the
at-least-one-filter rule and every edit below still executes in the source's order. Supplying
`cardKey` **together with** either filter is refused with `400` naming `cardKey`; a reference
that does not verify, or that was sealed for another purpose, is refused the same way.

**Response — `200 OK`.**

| Map field | PIC | Meaning |
|---|---|---|
| `ACCTSIDI` | `X(11)` | Account number |
| `CARDSIDI` | `X(16)` | Card number |
| `CRDNAMEI` | `X(50)` | Embossed name |
| `CRDSTCDI` | `X(1)` | Active status |
| `EXPMONI` | `X(2)` | Expiry month |
| `EXPYEARI` | `X(4)` | Expiry year |
| `INFOMSGI` | `X(40)` | Informational message — **`X(40)` here, not `X(45)`** |
| `ERRMSGI` | `X(80)` | Error message — **`X(80)` here, not `X(78)`** |

> **The expiry has a month and a year but no day on this map.** `COCRDSL.CPY` declares
> `EXPMONI X(2)` and `EXPYEARI X(4)` and **no `EXPDAYI`**, whereas the card-update map does
> declare `EXPDAYI X(2)` ([§12.3](#123-update-card)). The two maps genuinely differ and both
> are published as declared.
>
> Note also that this map's two message widths differ from the widths used on most other
> maps: `INFOMSGI` is `X(40)` and `ERRMSGI` is `X(80)`, against `X(45)` and `X(78)`
> elsewhere. Neither is normalised.

Plus, as on account view, the write precondition:

| Member | Notes |
|---|---|
| `snapshotToken` | The sealed, opaque, server-issued as-displayed snapshot required by [§12.3](#123-update-card) |
| `ETag` (response **header**) | The same token, quoted |
| `cardKey` | A fresh row reference to this same card, so a client that arrived by filter can continue by reference. It says *which* card; `snapshotToken` says *what was shown*. They are not interchangeable |

`FKEYSI X(75)` is function-key chrome and is excluded.

**Side effects.** None on business data; one additional read mints the snapshot token.

**Validation order.** The source's order is preserved: account filter, then card filter, then
the lookup.

**Exact observable literals.**

| Literal | Locator |
|---|---|
| `Displaying requested details` preceded by **three leading spaces** in the source literal (literal length 31) | [`app/cbl/COCRDSLC.cbl:L130`] |
| `Please enter Account and Card Number` | [`:L132`] |
| `PF03 pressed.Exiting` followed by **fourteen trailing spaces** (literal length 34, held in `PIC X(75)`) | [`:L137`] |
| `Account number not provided` | [`:L139`] |
| `Card number not provided` | [`:L141`] |
| `No input received` | [`:L143`] |
| `Account number must be a non zero 11 digit number` | [`:L145`], and again at [`:L147`] |
| `Card number if supplied must be a 16 digit number` | [`:L149`] |
| `Did not find this account in cards database` | [`:L152`] |
| `Did not find cards for this search condition` | [`:L154`] |
| `Error reading Card Data File` | [`:L156`] |

**Failure and status mapping.** Per [§8.2](#82-global-failure-and-status-mapping): filter
validation `400`; card or account absent `404`; store unavailable `503`; read failure `502`.

### 12.3 Update card

**Legacy source.** CSD transaction `CCUP` [`app/csd/CARDDEMO.CSD:L367`] → program
`COCRDUPC` (1,560 lines) → `app/cpy-bms/COCRDUP.CPY` (17 input fields).

**Purpose.** Applies an edited card, but only if the record still matches what the caller was
shown. **It mirrors the account-update pattern**, including the sealed precondition.

```http
PUT /api/cards HTTP/1.1
Authorization: Bearer <token>
If-Match: "<snapshotToken from GET /api/cards/detail>"
Content-Type: application/json
```

**Role.** USER or ADMIN.

**A snapshot is required — this was derived from the source, not assumed.** `COCRDUPC`
declares `CCUP-OLD-DETAILS` [`app/cbl/COCRDUPC.cbl:L291-L301`] and `CCUP-NEW-DETAILS`
[`:L303-L313`], exactly parallel to the account program's two groups, and
`9300-CHECK-CHANGE-IN-REC` [`:L1498`] compares the live record against the old group before
permitting the rewrite. The snapshot group is:

| Snapshot field | PIC | Compared at |
|---|---|---|
| `CCUP-OLD-ACCTID` | `X(11)` | key, not compared |
| `CCUP-OLD-CARDID` | `X(16)` | key, not compared |
| `CCUP-OLD-CVV-CD` | `X(3)` | [`:L1503`] |
| `CCUP-OLD-CRDNAME` | `X(50)` | [`:L1504`] |
| `CCUP-OLD-EXPIRAION-DATE` → `CCUP-OLD-EXPYEAR` `X(4)`, `CCUP-OLD-EXPMON` `X(2)`, `CCUP-OLD-EXPDAY` `X(2)` | | [`:L1505-L1507`], as **three substrings** `(1:4)`, `(6:2)`, `(9:2)` |
| `CCUP-OLD-CRDSTCD` | `X(1)` | [`:L1508`] |

The same `EXPIRAION` misspelling appears here and is preserved.

> **The snapshot carries the card verification value**, `CCUP-OLD-CVV-CD` [`:L294`]. That is
> decisive: a snapshot containing a CVV must never reach a client in readable form. So, as
> with account update, the snapshot is **server-issued, sealed and carried in `If-Match`**,
> obtained from [`GET /api/cards/detail`](#122-view-card), and **a request body carrying an
> as-displayed snapshot group is refused with `400`** rather than ignored.

**Card-specific case asymmetry.** The card comparison differs from the account comparison in
a way worth stating, because it is the opposite shape. `9300-CHECK-CHANGE-IN-REC` opens with

```cobol
       9300-CHECK-CHANGE-IN-REC.
           INSPECT CARD-EMBOSSED-NAME
           CONVERTING LIT-LOWER
                   TO LIT-UPPER
```

[`app/cbl/COCRDUPC.cbl:L1499-L1501`] — which upper-cases the **live** record's embossed name
**in place**, and leaves the snapshot side untouched. Compare the account program, which
wraps `FUNCTION UPPER-CASE` around **both** sides
([§11.2.4](#1124-snapshot-comparison-semantics)). Same intent, different mechanism, and the
card version mutates the record it is comparing. Both are preserved as written.

**Request body — the 17 map fields.**

| Map field | PIC | Required | Notes |
|---|---|:--:|---|
| `ACCTSIDI` | `X(11)` | **yes**, or a `cardKey` | Eleven-digit non-zero number |
| `CARDSIDI` | `X(16)` | **yes**, or a `cardKey` | Sixteen-digit number |
| `CRDNAMEI` | `X(50)` | **yes** | Alphabetic and spaces only |
| `CRDSTCDI` | `X(1)` | **yes** | `Y` or `N` |
| `EXPMONI` | `X(2)` | **yes** | 1 – 12 |
| `EXPYEARI` | `X(4)` | **yes** | |
| `EXPDAYI` | `X(2)` | no | **Present on this map, absent from the view map** - and **echoed, never applied**; see [§12.3.1](#1231-the-expiry-day-is-not-a-changeable-field) |
| `INFOMSGI` | `X(40)` | no | |
| `ERRMSGI` | `X(80)` | no | |

Plus the six chrome header fields and the function-key chrome `FKEYSI X(21)` and
`FKEYSCI X(18)`.

**Request — query parameter.**

| Parameter | Required | Notes |
|---|:--:|---|
| `cardKey` | no | A row reference from [`GET /api/cards`](#121-list-cards) or [`GET /api/cards/detail`](#122-view-card). When supplied it **supplies the two identifiers**, overriding whatever the body carries for them; every other body field is used as sent |

The reference travels as a query parameter and **not** as a body member, deliberately: the
body is fixed at the seventeen map fields plus the two snapshot groups, and a routing
reference is not a map field. Nothing about the identifier edits changes — a `cardKey` is
resolved into `ACCTSIDI` and `CARDSIDI` before the operation runs, so the same edits see the
same two values.

**Identifiers are edited before the precondition is judged.** The source's
`CCUP-DETAILS-NOT-FETCHED` arm runs `1210-EDIT-ACCOUNT` and then `1220-EDIT-CARD`
[`app/cbl/COCRDUPC.cbl:L645-L661`] before anything is read, so a request naming no card is
refused for naming no card. That order is preserved even when an `If-Match` is present: a
request with an unusable identifier answers `400` naming the field — `'No input received'`
when both are absent — rather than `412`. Answering `412` there would be a true statement
about the token (a snapshot sealed against a card number cannot verify when there is no card
number to seal against) and a misleading one about the request.

**Response — `200 OK`.** The updated card in the same shape as
[§12.2](#122-view-card), less the snapshot token and less the row reference: a successful
write consumes the precondition, so a caller intending a further update re-reads, and the
re-read issues both afresh.

#### 12.3.1 The expiry day is not a changeable field

`EXPDAYI` is declared on this map, is accepted on the request and is echoed on the response —
and **never reaches the stored record**. The day written is the one the snapshot carries.
This is not a restriction added in the target; it is what the source does, and the source
says so in as many words.

`3200-SETUP-SCREEN-VARS` writes `CCUP-OLD-EXPDAY` into `EXPDAYO` on **every** arm — the
show-details arm at [`app/cbl/COCRDUPC.cbl:L1110`], the changes-made arm at [`:L1123`] and the
fall-through arm at [`:L1127`]. On the changes-made arm the new-value move exists but is
**commented out**, immediately beneath the banner:

```cobol
*               MOVE OLD VALUES TO NON-DISPLAY FIELDS
*               THAT WE ARE NOT ALLOWING USER TO CHANGE(FOR NOW)
*               MOVE CCUP-NEW-EXPDAY     TO EXPDAYO  OF CCRDUPAO
                MOVE CCUP-OLD-EXPDAY     TO EXPDAYO  OF CCRDUPAO
```

[`:L1119-L1123`]. And [`:L1285`] sets `DFHBMDAR` on `EXPDAYC`, which renders the field dark.
A 3270 returns what was sent, so `CCUP-NEW-EXPDAY` at [`:L621`] can only ever hold the old
day — which is precisely why [`:L1471`] may write it into the rewrite image safely, and why
`COCRDSL.CPY` declares no `EXPDAYI` for the read map to publish
([§12.2](#122-view-card)).

**The screen was the carrier of that value.** There is no screen here, so the sealed snapshot
is the carrier instead. The consequence for a caller is simple: send the day or omit it, it
makes no difference; to change a card's expiry, change the **month** and the **year**.

**Side effects.** One card row is written, inside a transaction.

**Validation order** — the source validates in this order, and the first failure wins:

1. Account number provided, non-zero, eleven digits
2. Card number provided, sixteen digits
3. Card name provided, alphabetic and spaces only
4. Card active status `Y` or `N`
5. Expiry month 1 – 12
6. Expiry year valid
7. Record read, then the change comparison

**Exact observable literals.**

| Literal | Locator |
|---|---|
| `Details of selected card shown above` | [`app/cbl/COCRDUPC.cbl:L161`] |
| `Please enter Account and Card Number` | [`:L163`] |
| `Update card details presented above.` | [`:L165`] |
| `Changes validated.Press F5 to save` | [`:L167`] |
| `Changes committed to database` | [`:L169`] |
| `Changes unsuccessful. Please try again` | [`:L171`] |
| `PF03 pressed.Exiting` followed by **fourteen trailing spaces** (literal length 34) | [`:L176`] |
| `Account number not provided` | [`:L178`] |
| `Card number not provided` | [`:L180`] |
| `Card name not provided` | [`:L182`] |
| `Card name can only contain alphabets and spaces` | [`:L184`] |
| `No input received` | [`:L186`] |
| `No change detected with respect to values fetched.` | [`:L188`] |
| `Account number must be a non zero 11 digit number` | [`:L190`], and again at [`:L192`] |
| `Card number if supplied must be a 16 digit number` | [`:L194`] |
| `Card Active Status must be Y or N` | [`:L196`] |
| `Card expiry month must be between 1 and 12` | [`:L198`] |
| `Invalid card expiry year` | [`:L200`] |
| `Did not find this account in cards database` | [`:L202`] |
| `Did not find cards for this search condition` | [`:L204`] |
| `Could not lock record for update` | [`:L206`] |
| `Record changed by some one else. Please review` | [`:L208`] |
| `Update of record failed` | [`:L210`] |
| `Error reading Card Data File` | [`:L212`] |

Note `Could not lock record for update` — a **single** lock message, because this operation
writes one dataset. The account operation has two, one per dataset
([§11.2.6](#1126-outcome-markers-and-status-mapping)). And `some one` is again two words.

**Failure and status mapping.**

| Outcome | Status | `errorCode` |
|---|--:|---|
| Field validation refused | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| Request body carried an as-displayed snapshot group | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| No identifier supplied — neither body identifiers nor `cardKey`, so `'No input received'` | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| `cardKey` supplied alongside a filter, or one that does not verify | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| Card or account absent | `404` | `CARDDEMO-RECORD-NOT-FOUND` |
| `If-Match` absent, so no precondition was presented | `428` | `CARDDEMO-UPDATE-CONFLICT` |
| Presented snapshot does not verify — `Record changed by some one else. Please review` | `412` | `CARDDEMO-UPDATE-CONFLICT` |
| `Could not lock record for update` | `423` | `CARDDEMO-UPDATE-CONFLICT` |
| `Update of record failed` | `409` | `CARDDEMO-UPDATE-CONFLICT` |
| Store unavailable / read failure / abend | `503` / `502` / `500` | per [§8.2](#82-global-failure-and-status-mapping) |

`No change detected with respect to values fetched.` is **not** an error: the record already
matched the submission, so nothing needed writing. It is a `200` carrying that literal, in
`errorMessage`, because `WS-RETURN-MSG` is moved to `CCARD-ERROR-MSG`
[`app/cbl/COCRDUPC.cbl:L547`, `:L569`] — the field the source uses, not the severity it
implies. **No row is written and no version is incremented**; the outcome is a redisplay,
which is why `:L972-L976` leaves the state machine in `CCUP-SHOW-DETAILS` and `:L1212-L1214`
places the cursor with the informational arms rather than with the `FLG-*-NOT-OK` ones.

One presentational consequence follows from the source and is not a defect: the redisplayed
embossed name is **upper-cased**, because `9300-CHECK-CHANGE-IN-REC` folds it in place
[`:L1499-L1501`] and the redisplay renders the folded work area. The stored row keeps its own
casing, as a re-read shows.

---

## 13. TransactionController — 3 operations

Base path `/api/transactions`.

### 13.1 List transactions

**Legacy source.** CSD transaction `CT00` [`app/csd/CARDDEMO.CSD:L419`] → program
`COTRN00C` (699 lines) → `app/cpy-bms/COTRN00.CPY` (59 input fields). **Page size 10.**

**Purpose.** Returns a page of transactions in key order, optionally positioned at a given
transaction identifier, reproducing the legacy forward and backward browse.

```http
GET /api/transactions?page=1 HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request — query parameters.**

| Parameter | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| `transactionId` | `TRNIDINI` | `X(16)` | no | Positioning filter: start the browse at this key |
| `page` | `PAGENUMI` | `X(8)` | no | Digits only, bounded by the eight-character screen field |
| `action`, `firstKey`, `lastKey`, `nextPageAvailable` | — | — | no | See [§7.1](#71-paging-parameters) |

**Response — `200 OK`.** Ten row groups maximum. Per row:

| Map field (row *n*) | PIC | Meaning |
|---|---|---|
| `TRNIDnnI` | `X(16)` | Transaction identifier |
| `TDATEnnI` | `X(8)` | Transaction date |
| `TDESCnnI` | **`X(26)`** | Description — **truncated to 26 characters on the list** |
| `TAMT00nI` | `X(12)` | Amount, on the edited mask of [§6.1](#61-the-amount-echo-mask) |

Plus paging metadata and `ERRMSGI X(78)`.

> **The list truncates the description and this document publishes the truncated width.**
> `TDESCnnI` is `X(26)` on `COTRN00.CPY` while `TDESCI` is `X(60)` on `COTRN01.CPY`
> ([§13.2](#132-view-transaction)). The list rows carry **26** characters — that is the
> width published here, because it is the width the screen field declares and therefore the
> width the row projection produces. A client needing the full sixty-character description
> must fetch the detail resource; the list is not a substitute for it.

`SEL000nI X(1)` is the 3270 selection column and is **not** published, for the same reason
as the card list's ([§12.1](#121-list-cards)): row selection becomes a URL.

**Side effects.** None.

**Validation order.** The positioning filter is validated, then the browse runs.
Forward and backward paging are both preserved, with the four boundary messages of
[§7.2](#72-boundary-behaviour) at [`app/cbl/COTRN00C.cbl:L248`], [`:L270`], [`:L608`] and
[`:L642`], plus `'You have reached the top of the page...'` [`:L676`].

**Exact observable literals.** The four paging messages above, plus
`'Unable to lookup transaction...'` [`:L615`, `:L649`, `:L683`] on a read failure.

**Failure and status mapping.** Per [§8.2](#82-global-failure-and-status-mapping). An empty
page is `200` with an empty array.

### 13.2 View transaction

**Legacy source.** CSD transaction `CT01` [`app/csd/CARDDEMO.CSD:L429`] → program
`COTRN01C` (330 lines) → `app/cpy-bms/COTRN01.CPY` (21 input fields).

**Purpose.** Returns the full detail of one transaction, including the fields the list
projection truncates or omits.

```http
GET /api/transactions/detail?transactionId=0000000000000001 HTTP/1.1
Authorization: Bearer <token>
```

**Role.** USER or ADMIN.

**Request — query parameters.**

| Parameter | Map field | PIC | Required |
|---|---|---|:--:|
| `transactionId` | `TRNIDINI` | `X(16)` | **yes** |

Blank or absent is refused with `'Tran ID can NOT be empty...'`
[`app/cbl/COTRN01C.cbl:L149`].

**Response — `200 OK`.** The full detail projection:

| Map field | PIC | Meaning |
|---|---|---|
| `TRNIDI` | `X(16)` | Transaction identifier |
| `CARDNUMI` | `X(16)` | Card number |
| `TTYPCDI` | `X(2)` | Type code |
| `TCATCDI` | `X(4)` | Category code |
| `TRNSRCI` | `X(10)` | Source |
| `TDESCI` | **`X(60)`** | Description — the **full** width, unlike the list's `X(26)` |
| `TRNAMTI` | `X(12)` | Amount |
| `TORIGDTI` | `X(10)` | Originating date |
| `TPROCDTI` | `X(10)` | Processing date |
| `MIDI` | `X(9)` | Merchant identifier |
| `MNAMEI` | `X(30)` | Merchant name |
| `MCITYI` | `X(25)` | Merchant city |
| `MZIPI` | `X(10)` | Merchant postal code |
| `ERRMSGI` | `X(78)` | Error message |

**The amount is rendered on the legacy display mask** `+99999999.99` — mandatory sign,
exactly eight integer digits, two decimals — per [§6.1](#61-the-amount-echo-mask). It is a
decimal value, never a float.

**Side effects.** None.

**Validation order.** Identifier not blank, then the read.

**Exact observable literals.**

| Literal | Locator |
|---|---|
| `Tran ID can NOT be empty...` | [`app/cbl/COTRN01C.cbl:L149`] |
| `Transaction ID NOT found...` | [`:L285`] |
| `Unable to lookup Transaction...` | [`:L292`] |

**Failure and status mapping.** Blank identifier `400`; not found `404`; read failure `502`;
store unavailable `503`; abend `500`.

### 13.3 Add transaction

**Legacy source.** CSD transaction `CT02` [`app/csd/CARDDEMO.CSD:L439`] → program
`COTRN02C` (783 lines) → `app/cpy-bms/COTRN02.CPY` (21 input fields).

**Purpose.** Creates one transaction against an account or a card, generating its identifier
server-side. This is the only operation that inserts a transaction from the online surface.

```http
POST /api/transactions HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role.** USER or ADMIN.

**Request body.**

| JSON field | Map field | PIC | Required | Parser |
|---|---|---|:--:|---|
| account identifier | `ACTIDINI` | `X(11)` | one of the two | **Strict digits-only** [`app/cbl/COTRN02C.cbl:L204`] |
| card number | `CARDNINI` | `X(16)` | one of the two | **Strict digits-only** [`:L218`] |
| type code | `TTYPCDI` | `X(2)` | **yes** | Numeric |
| category code | `TCATCDI` | `X(4)` | **yes** | Numeric |
| source | `TRNSRCI` | `X(10)` | **yes** | |
| description | `TDESCI` | `X(60)` | **yes** | |
| amount | `TRNAMTI` | `X(12)` | **yes** | **Currency-tolerant** [`:L383`, `:L456`] |
| originating date | `TORIGDTI` | `X(10)` | **yes** | Validated as a date, format `YYYY-MM-DD` [`:L60`] |
| processing date | `TPROCDTI` | `X(10)` | **yes** | As above |
| merchant identifier | `MIDI` | `X(9)` | **yes** | Numeric |
| merchant name | `MNAMEI` | `X(30)` | **yes** | |
| merchant city | `MCITYI` | `X(25)` | **yes** | |
| merchant postal code | `MZIPI` | `X(10)` | **yes** | |
| confirmation | `CONFIRMI` | `X(1)` | **yes**, to write | See [§13.3.2](#1332-the-two-phase-confirmation-gate) |

Either the account identifier **or** the card number must be supplied: with neither, the
source answers `'Account or Card Number must be entered...'` [`:L226`]. Supplying the
account identifier causes the card number to be resolved from the cross-reference and echoed
back.

**The transaction identifier is not an input.** It is generated server-side — see
[§13.3.1](#1331-identifier-generation-a-known-and-accepted-race).

**Response.**

| Outcome | Status | Body | Headers |
|---|--:|---|---|
| Written | **`201 Created`** | The created transaction in the shape of [§13.2](#132-view-transaction) | `Location` addressing the new resource |
| Confirmation required, or the caller declined, or the screen was merely displayed or cleared | `200 OK` | The same shape, carrying the source's message; **nothing was written** | — |

The distinction matters: only the written arm claims `201`. The four no-write terminations
were understood and answered, so none of them is a failure — but none of them wrote, so none
may claim `201` either.

**Side effects.** On the written arm: one transaction row is inserted. No queue message, no
object emission.

#### 13.3.1 Identifier generation: a known and accepted race

The source generates the identifier by a **descending browse of the maximum key plus one**
[`app/cbl/COTRN02C.cbl:L444-L451`]:

```cobol
           MOVE HIGH-VALUES TO TRAN-ID
           PERFORM STARTBR-TRANSACT-FILE
           PERFORM READPREV-TRANSACT-FILE
           PERFORM ENDBR-TRANSACT-FILE
           MOVE TRAN-ID     TO WS-TRAN-ID-N
           ADD 1 TO WS-TRAN-ID-N
```

The empty-file case is explicit: `DFHRESP(ENDFILE)` moves zeros into the identifier, so
**the first generated identifier is `1`**. In the target this becomes a top-one
descending-ordered query, defaulted to zero when empty, incremented, then zero-padded to
sixteen characters.

**This algorithm is inherently racy under concurrency — exactly as the browse was.** Two
concurrent requests can compute the same next identifier. When that happens the primary-key
constraint refuses the second, and the caller receives:

| Outcome | Status | `errorCode` | `detail` |
|---|--:|---|---|
| Identifier collision | `409` | `CARDDEMO-DUPLICATE-RECORD` | `Tran ID already exist...` [`:L738`] |

**A database sequence is deliberately not used.** A sequence would change the generated
values and break comparison against the legacy baseline, which is the parity contract. This
is therefore a **known, accepted characteristic with a documented rationale**, not a defect
to be fixed here, and callers should treat `409` on this operation as retryable. Recorded as
finding **M-2** in [§18](#18-findings-severity-classified).

#### 13.3.2 The two-phase confirmation gate

`CONFIRMI X(1)` is a two-phase gate. **It is mapped explicitly as a request field**, with the
source's four arms preserved, rather than being replaced by an idempotency key or a
two-request protocol — the source's arms carry distinct observable messages and collapsing
them would lose them.

The `EVALUATE` at [`app/cbl/COTRN02C.cbl:L169-L188`] has exactly four arms:

| Submitted value | Behaviour | Literal | Status |
|---|---|---|--:|
| `Y` or `y` | The transaction is written | — | `201` |
| `N` or `n`, **blank or absent** | Not written; the caller is prompted | `Confirm to add this transaction...` [`:L178`] | `200` |
| Anything else | Not written; the value is refused | `Invalid value. Valid values are (Y/N)...` [`:L184`] | `400` |

> **Note the source folds `N` in with blank on this operation.** `'N'`, `'n'`, `SPACES` and
> `LOW-VALUES` are **one** arm [`:L173-L176`], all producing the same prompt. Bill payment
> does **not** do this — there, `N` is a distinct arm that clears the screen
> ([§14.1](#141-bill-payment)). The two programs differ, and each is documented as it is.

**Validation order.** Preserved exactly, because order determines which message a caller sees
first. Key fields, then the presence sweep, then the numeric and date checks:

1. `'Account ID must be Numeric...'` [`:L199`]
2. `'Card Number must be Numeric...'` [`:L213`]
3. `'Account or Card Number must be entered...'` [`:L226`]
4. `'Type CD can NOT be empty...'` [`:L254`]
5. `'Category CD can NOT be empty...'` [`:L260`]
6. `'Source can NOT be empty...'` [`:L266`]
7. `'Description can NOT be empty...'` [`:L272`]
8. `'Amount can NOT be empty...'` [`:L278`]
9. `'Orig Date can NOT be empty...'` [`:L284`]
10. `'Proc Date can NOT be empty...'` [`:L290`]
11. `'Merchant ID can NOT be empty...'` [`:L296`]
12. `'Merchant Name can NOT be empty...'` [`:L302`]
13. `'Merchant City can NOT be empty...'` [`:L308`]
14. `'Merchant Zip can NOT be empty...'` [`:L314`]
15. `'Type CD must be Numeric...'` [`:L325`]
16. `'Category CD must be Numeric...'` [`:L331`]
17. `'Orig Date - Not a valid date...'` [`:L401`]
18. `'Proc Date - Not a valid date...'` [`:L421`]
19. `'Merchant ID must be Numeric...'` [`:L432`]

**Every field is checked for blank before any is checked for shape.** A caller submitting two
bad fields is told about the earlier one first.

**Exact observable literals.** The validation-order literals above, plus:

| Literal | Locator |
|---|---|
| `Account ID NOT found...` | [`:L593`] |
| `Unable to lookup Acct in XREF AIX file...` | [`:L600`] |
| `Card Number NOT found...` | [`:L626`] |
| `Unable to lookup Card # in XREF file...` | [`:L633`] |
| `Transaction ID NOT found...` | [`:L657`] |
| `Unable to lookup Transaction...` | [`:L664`, `:L693`] |
| `Tran ID already exist...` | [`:L738`] |
| `Unable to Add Transaction...` | [`:L745`] |

**Failure and status mapping.** Validation `400`; account or card not found `404`; identifier
collision `409`; store unavailable `503`; read or write failure `502`; abend `500`.

---

## 14. BillingController — 1 operation

Base path `/api/billing`.

### 14.1 Bill payment

**Legacy source.** CSD transaction `CB00` [`app/csd/CARDDEMO.CSD:L337`] → program
`COBIL00C` (572 lines) → `app/cpy-bms/COBIL00.CPY` (10 input fields).

**Purpose.** Settles an account **in full** by writing a payment transaction for the entire
current balance and driving that balance to zero.

```http
POST /api/billing/payments HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role.** USER or ADMIN.

#### 14.1.1 The payment is always the full balance

> **There is no amount field in this request, and there is no partial payment.** The map
> declares ten fields and **not one of them is a payment amount**
> [`app/cpy-bms/COBIL00.CPY`]. A client expecting to specify how much to pay is expecting
> behaviour this system does not have.

The source proves it in four steps:

1. Capture the balance: `MOVE ACCT-CURR-BAL TO WS-CURR-BAL` then into the screen field
   [`app/cbl/COBIL00C.cbl:L193-L194`].
2. **Refuse a non-positive balance:**
   `IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI NOT = SPACES AND LOW-VALUES` →
   `'You have nothing to pay...'` [`:L198-L201`]. Note the condition is `<= ZEROS`, so a
   zero balance is refused too, not only a negative one.
3. Move the **entire** balance into the transaction amount:
   `MOVE ACCT-CURR-BAL TO TRAN-AMT` [`:L224`].
4. Subtract it, driving the balance to exactly zero:
   `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` [`:L234`].

**Request body.**

| JSON field | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| account identifier | `ACTIDINI` | `X(11)` | **yes** | Blank is refused with `'Acct ID can NOT be empty...'` [`:L161`] |
| confirmation | `CONFIRMI` | `X(1)` | **yes**, to settle | See below |

`CURBALI X(14)` is a **response** field — the current balance as displayed. Note its width:
**`X(14)`**, narrower than the `X(15)` money fields on the account maps. It is published as
declared.

**Response.**

| Outcome | Status | Meaning |
|---|--:|---|
| Settled in full | **`201 Created`** | A payment transaction was written and the balance is now zero |
| Confirmation required | `200 OK` | Nothing was paid; the caller is prompted |
| Caller declined | `200 OK` | Nothing was paid; the source cleared the screen |
| Non-positive balance | `400` | Nothing to pay |

Body members: the account identifier, the current balance (`CURBALI X(14)`), the outcome, the
generated transaction identifier on the settled arm, and the source's message
(`ERRMSGI X(78)`).

**Side effects.** On the settled arm, two writes inside one transaction: **one transaction
row inserted and one account row updated**. The synthetic payment transaction is built from
fixed literals the source supplies [`:L219-L229`] — type code `'02'`, category code `2`,
source `'POS TERM'`, description `'BILL PAYMENT - ONLINE'`, merchant identifier `999999999`,
merchant name `'BILL PAYMENT'`, merchant city `'N/A'`, merchant postal code `'N/A'` — with
the amount taken from the balance and the card number from the cross-reference.

**The confirmation gate.** `EVALUATE CONFIRMI` [`:L173-L191`] has four arms, and **they are
not the same four as the transaction-add gate**:

| Submitted value | Behaviour | Literal | Status |
|---|---|---|--:|
| `Y` or `y` | Settle in full | — | `201` |
| `N` or `n` | **Distinct arm:** clear the screen, write nothing, emit no message | *(none)* | `200` |
| Blank or absent | Read the account and prompt | `Confirm to make a bill payment...` [`:L237`] | `200` |
| Anything else | Refuse the value | `Invalid value. Valid values are (Y/N)...` [`:L187-L188`] | `400` |

**Identifier generation** uses the same descending-browse idiom as transaction add
[`:L212-L219`], with the explicit empty-file path at [`:L472-L496`]: on
`DFHRESP(ENDFILE)` the identifier is set to zeros [`:L488`], so the first generated
identifier is `1`. The same accepted race applies, and a collision answers `409` with
`'Tran ID already exist...'` [`:L536`].

**Validation order.** Account identifier not blank → confirmation arm → read the account →
non-positive-balance check → cross-reference read → identifier generation → write → account
update.

**Exact observable literals.**

| Literal | Locator |
|---|---|
| `Acct ID can NOT be empty...` | [`app/cbl/COBIL00C.cbl:L161`] |
| `Invalid value. Valid values are (Y/N)...` | [`:L187-L188`] |
| `You have nothing to pay...` | [`:L201`] |
| `Confirm to make a bill payment...` | [`:L237`] |
| `Account ID NOT found...` | [`:L361`], [`:L392`], [`:L425`] |
| `Unable to lookup Account...` | [`:L368`] |
| `Unable to Update Account...` | [`:L399`] |
| `Unable to lookup XREF AIX file...` | [`:L432`] |
| `Transaction ID NOT found...` | [`:L456`] |
| `Unable to lookup Transaction...` | [`:L463`], [`:L492`] |
| `Tran ID already exist...` | [`:L536`] |
| `Unable to Add Bill pay Transaction...` | [`:L543`] |

**Failure and status mapping.** Validation and non-positive balance `400`; account or
cross-reference absent `404`; identifier collision `409`; store unavailable `503`; read or
write failure `502`; abend `500`.

---

## 15. ReportController — 1 operation

Base path `/api/reports`.

### 15.1 Submit transaction report

**Legacy source.** CSD transaction `CR00` [`app/csd/CARDDEMO.CSD:L409`] → program
`CORPT00C` (649 lines) → `app/cpy-bms/CORPT00.CPY` (17 input fields). `README.md:L221`
labels this transaction **"Transaction Reports"**.

**Purpose.** Submits a transaction-report job for **asynchronous** batch processing.

```http
POST /api/reports HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role.** USER or ADMIN.

#### 15.1.1 This endpoint accepts a job; it does not return a report

The legacy program wrote an **eighteen-card, eighty-byte job deck** to the transient data
queue `JOBS`, defined as
`DEFINE TDQUEUE(JOBS) … TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)
RECORDFORMAT(FIXED) DISPOSITION(MOD)` [`app/csd/CARDDEMO.CSD:L499-L505`]. The submission
loop iterated up to a thousand card images, terminating on `'/*EOF'`, spaces or low values —
and **wrote the terminating card before exiting the loop** [`app/cbl/CORPT00C.cbl:L498-L508`].

In the target that whole deck collapses into **one typed JSON message published to the SQS
FIFO queue `carddemo-report-jobs`**, carrying the report name and the two dates; a listener
maps it onto Spring Batch job parameters.

> **The response is therefore an acceptance, not a report.** `202 Accepted` means *the job
> was queued*, not *the report is ready*. No report body is ever returned by this endpoint,
> and no report content is available from it.

To correlate the submission with the resulting job run, a caller uses:

* the **`correlationId`** — send `X-Correlation-Id` on the request, or read the value the
  server generated back out of the response; it is carried into the queue message and into
  every batch log record for the run, and
* the **`jobInstanceId`**, once the listener has created the Spring Batch job instance; the
  batch tier propagates it in its own logging context alongside the correlation identifier.

**Request body.**

| JSON field | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| monthly selector | `MONTHLYI` | `X(1)` | one of three | Any non-blank value selects it |
| yearly selector | `YEARLYI` | `X(1)` | one of three | |
| custom selector | `CUSTOMI` | `X(1)` | one of three | Requires the six date components below |
| start month | `SDTMMI` | `X(2)` | custom only | |
| start day | `SDTDDI` | `X(2)` | custom only | |
| start year | `SDTYYYYI` | `X(4)` | custom only | |
| end month | `EDTMMI` | `X(2)` | custom only | |
| end day | `EDTDDI` | `X(2)` | custom only | |
| end year | `EDTYYYYI` | `X(4)` | custom only | |
| confirmation | `CONFIRMI` | `X(1)` | **yes**, to publish | Four arms, below |

With no selector at all the source answers
`'Select a report type to print report...'` [`app/cbl/CORPT00C.cbl:L438`].

#### 15.1.2 Three periods and one that is not what it looks like

> ### The monthly period is the FULL current calendar month
>
> **It runs from the first day of the current month to the *last* day of the current month.
> It is not month-to-date.** A client that assumes month-to-date will mis-state the period it
> asked for.

The source proves it in six steps [`app/cbl/CORPT00C.cbl:L213-L238`]:

1. `:L217-L219` build the start date from the current year, the current month and the literal
   `'01'`.
2. `:L223` executes `MOVE 1 TO WS-CURDATE-DAY`, which **discards today's day of month** and
   forces it to 1.
3. `:L224` executes `ADD 1 TO WS-CURDATE-MONTH`, advancing the month by one.
4. `:L225-L228` roll the year and reset the month to 1 when the month exceeds 12.
5. `:L229-L230` compute
   `FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)` — the first of the
   **next** month, minus one day, which is the **last day of the current month**.
6. `:L232-L234` read the end year, month and day back out of the shared date group **after
   step 5 has already mutated all three**.

Step 6 is where a casual reading goes wrong: the end date is read from the *mutated* group,
not from today's date. This correction is recorded as finding **H-1** in
[§18](#18-findings-severity-classified).

| Period | Start | End | Locator |
|---|---|---|---|
| **Monthly** | First day of the current month | **Last** day of the current month | [`:L213-L238`] |
| **Yearly** | 1 January of the current year | 31 December of the current year | [`:L239-L255`] |
| **Custom** | The three start components | The three end components | [`:L256`], [`:L381-L410`] |

The yearly arm needs no arithmetic: `:L243-L244` move the current year into both the start
and the end year, `:L245-L246` move `'01'` into the start month and day, and the end month
and day are the literals `'12'` and `'31'`.

**Custom-range date validation.** Each composite date is assembled with **dash separators** — the two
work areas interleave `FILLER PIC X(01) VALUE '-'` between their components [`:L60-L71`] — and
validated through the date service against an explicit format string `'YYYY-MM-DD'`
[`:L72`]. The service returns the legacy parameter-block shape — a severity code, filler
and a message number [`:L129-L135`]:

| Validation outcome | Meaning | Client-visible result |
|---|---|---|
| Severity code `'0000'` | Valid | Accepted |
| Non-zero severity, message number **`'2513'`** | **Tolerated by the source** — the value passes | Accepted |
| Non-zero severity, any other message number | Rejected | `400` with `'End Date - Not a valid date...'` [`:L420`] |

That second row is not an editorial simplification: the source explicitly tests
`IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'` before rejecting [`:L399`] and [`:L419`], so one particular
non-zero severity is accepted. A client must not assume that any non-zero severity means
rejection.

**The confirmation gate** [`:L464-L494`] — four arms, and the invalid arm **quotes the
offending value back**:

| Submitted value | Behaviour | Literal | Status |
|---|---|---|--:|
| `Y` or `y` | Publish the job | `<name> report submitted for printing ...` — composed as the report name plus that suffix [`:L449-L452`] | `202` |
| `N` or `n` | Initialise all fields, publish nothing, emit **no** message | *(none)* | `200` |
| Blank or absent | Prompt | `Please confirm to print the <name> report...` — composed from the fixed prefix, the report name and the suffix [`:L465-L470`] | `200` |
| Anything else | Refuse | `"<value>" is not a valid value to confirm...` — **the offending value is quoted back inside double quotes** [`:L485-L490`] | `400` |

The report name substituted into those messages is one of the three literals `'Monthly'`,
`'Yearly'` or `'Custom'` [`:L214`, `:L240`, `:L433`].

**Validation order.** Period selector first — with no selector at all the request is refused
before anything else — then, for a custom period, the two composite dates through the date
service, then the confirmation gate, then the publish.

**Exact observable literals.**

| Condition | Literal | Locator |
|---|---|---|
| No period selector supplied | `Select a report type to print report...` | [`app/cbl/CORPT00C.cbl:L438`] |
| Custom end date invalid | `End Date - Not a valid date...` | [`:L420`] |
| Published | `<name> report submitted for printing ...` | [`:L449-L452`] |
| Confirmation blank or absent | `Please confirm to print the <name> report...` | [`:L465-L470`] |
| Confirmation neither `Y` nor `N` | `"<value>" is not a valid value to confirm...` | [`:L485-L490`] |
| Queue write failed | `Unable to Write TDQ (JOBS)...` | [`:L531-L532`] |

**Response.**

| Outcome | Status | Meaning |
|---|--:|---|
| Job published | **`202 Accepted`** | The message is on the queue; the batch tier will produce the report |
| Declined at the confirmation gate, or a prompt is required | `200 OK` | **Nothing was published** |
| Validation refused | `400` | Nothing was published |

**Side effects.** On the accepted arm: **one message published to the SQS FIFO queue
`carddemo-report-jobs`**. No business row is written by this operation. The report object
itself is produced later, by the batch tier.

**Enqueue failure.** The queue write lives in the paragraph `WIRTE-JOBSUB-TDQ` — **the
paragraph name is misspelled in the source**, "WIRTE" for "WRITE", at
[`app/cbl/CORPT00C.cbl:L515`]; the misspelling is a source curiosity, recorded because
anyone searching the corpus for `WRITE-JOBSUB-TDQ` will not find it. Its failure arm emits an
exact literal that is part of the observable contract and is reproduced verbatim:

| Condition | Literal | Locator | Status | `errorCode` |
|---|---|---|--:|---|
| Queue write failed | `Unable to Write TDQ (JOBS)...` | [`:L531-L532`] | `502` | `CARDDEMO-IO-FAILURE` |
| Queue unavailable | *(fixed detail)* | — | `503` | `CARDDEMO-RESOURCE-UNAVAILABLE` |

The literal names the legacy queue rather than the SQS queue, and that is deliberate: the
string is part of the contract a legacy-aware client may already match on, so it is relayed
as written rather than modernised.

**Failure and status mapping.** Date validation refusals `400`; queue unavailable `503`;
queue write failure `502`; abend `500`. **Of the confirmation gate's four arms only one is a
`400`** — the arm that carries a value the one-byte field cannot accept. The prompt arm and
the decline arm are both `200` with nothing published, matching
[bill payment](#141-bill-payment) and [transaction add](#133-add-transaction), whose
confirmation gates answer `200` on the same condition. The prompt arm used to answer `400`
(finding M-17), which made one API contradict itself on its own handshake: three operations
share the gate, and asking a caller to confirm is the operation working, not the caller
failing. `CORPT00C` grades none of the three non-publishing arms differently — each sets
`WS-ERR-FLG` and performs `SEND-TRNRPT-SCREEN` [`:L464-L493`].

---

## 16. AdminController — 4 operations

Base path `/api/admin/users`. **Every operation in this section requires `ROLE_ADMIN`**, by
the single `/api/admin/**` rule of [§5.1](#51-role-model). A standard user receives `403`.

The persisted record behind all four is `app/cpy/CSUSR01Y.cpy`:
`ID X(8)` + `FNAME X(20)` + `LNAME X(20)` + `PWD X(8)` + `TYPE X(1)` + `FILLER` = **80
bytes**, key length **8**. The eight-character password field becomes a **sixty-character
BCrypt hash column**, so the persisted width differs from the input width **by design**.

### 16.1 List users

**Legacy source.** CSD transaction `CU00` [`app/csd/CARDDEMO.CSD:L449`] → program
`COUSR00C` (695 lines) → `app/cpy-bms/COUSR00.CPY` (59 input fields). **Page size 10.**

**Purpose.** Returns a page of user-security records in key order, optionally positioned at a
given user identifier. No credential material is included.

```http
GET /api/admin/users?page=1 HTTP/1.1
Authorization: Bearer <token>
```

**Role. ADMIN only.**

**Request — query parameters.**

| Parameter | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| `userId` | `USRIDINI` | `X(8)` | no | Positioning filter |
| `page` | `PAGENUMI` | `X(8)` | no | Digits only |
| `action`, `firstKey`, `lastKey`, `nextPageAvailable`, `rowCount` | — | — | no | See [§7.1](#71-paging-parameters) |

**Response — `200 OK`.** Ten row groups maximum. Per row:

| Map field (row *n*) | PIC | Meaning |
|---|---|---|
| `USRIDnnI` | `X(8)` | User identifier |
| `FNAMEnnI` | `X(20)` | First name |
| `LNAMEnnI` | `X(20)` | Last name |
| `UTYPEnnI` | `X(1)` | User type, `A` or `U` |

Plus paging metadata and `ERRMSGI X(78)`.

> **No password field appears on this map, and none appears in the response.**
> `COUSR00.CPY` declares 59 input fields and **not one of them is a password**. Neither the
> plaintext nor the hash is ever returned by this or any other operation.

`SEL000nI X(1)` is the 3270 selection column and is not published.

**Side effects.** None.

**Validation order.** Positioning filter, then the browse.

**Exact observable literals.** The four paging messages, worded identically to the
transaction list [`app/cbl/COUSR00C.cbl:L251`, `:L273`, `:L603`, `:L637`], plus
`'You have reached the top of the page...'` [`:L671`] and `'Unable to lookup User...'`
[`:L610`, `:L644`, `:L678`].

**Failure and status mapping.** Per [§8.2](#82-global-failure-and-status-mapping); a
non-administrator is `403`.

### 16.2 Add user

**Legacy source.** CSD transaction `CU01` [`app/csd/CARDDEMO.CSD:L459`] → program
`COUSR01C` (299 lines) → `app/cpy-bms/COUSR01.CPY` (12 input fields).

**Purpose.** Creates one user-security record, hashing the supplied password before it is
stored.

```http
POST /api/admin/users HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role. ADMIN only.**

**Request body.**

| JSON field | Map field | PIC | Required | Notes |
|---|---|---|:--:|---|
| first name | `FNAMEI` | `X(20)` | **yes** | |
| last name | `LNAMEI` | `X(20)` | **yes** | |
| user identifier | `USERIDI` | `X(8)` | **yes** | Becomes the key |
| password | `PASSWDI` | `X(8)` | **yes** | **Write-only.** BCrypt strength 10. Never returned, never logged |
| user type | `USRTYPEI` | `X(1)` | **yes** | `A` or `U` |

```json
{
  "firstName": "EXAMPLE",
  "lastName": "USER",
  "userId": "EXAMPL01",
  "password": "<redacted>",
  "userType": "U"
}
```

**Response — `201 Created`.** The created user **without any password member**: identifier,
first name, last name, user type, and the source's message
`'User <id> has been added ...'` [`app/cbl/COUSR01C.cbl:L255-L257`].

**Side effects.** One user row inserted, with the password stored **only** as a BCrypt
strength-10 hash.

> **A password is stored exactly as submitted, and sign-on folds what it is given — so a
> password must equal its own upper-case form to be usable.** Both halves are the source's.
> The write moves the field onto the record unchanged — `MOVE PASSWDI OF COUSR1AI TO
> SEC-USR-PWD` [`app/cbl/COUSR01C.cbl:L157`], and `MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD`
> [`app/cbl/COUSR02C.cbl:L228`] — while sign-on upper-cases the presented value before
> comparing it, `MOVE FUNCTION UPPER-CASE(PASSWDI …) TO WS-USER-PWD`
> [`app/cbl/COSGN00C.cbl:L135-L136`], and compares at [`:L223`]. Setting `Pw1234xy` therefore
> stores `Pw1234xy` and sign-on will only ever offer `PW1234XY`, which does not match: the
> account is created or updated successfully and **can never be signed on to**. Use a password
> with no lower-case letters. See [§18.2](#182-medium), finding M-11.

**Validation order — preserved exactly, because order determines which message the client
sees first** [`app/cbl/COUSR01C.cbl:L118-L146`]:

1. `'First Name can NOT be empty...'` [`:L120`]
2. `'Last Name can NOT be empty...'` [`:L126`]
3. `'User ID can NOT be empty...'` [`:L132`]
4. `'Password can NOT be empty...'` [`:L138`]
5. `'User Type can NOT be empty...'` [`:L144`]

A caller who omits both the first name and the password is told about the **first name**.

#### 16.2.1 Labelled deviation: `userType` is restricted to `A` and `U`

This API accepts **only** `A` or `U` in `userType`, on this operation and on
[§16.3](#163-update-user). Anything else is refused with `400`,
`CARDDEMO-VALIDATION-REJECTED`, `field: "userType"` and the detail
`User Type must be A for an administrator or U for a regular user`. The comparison is
**case-sensitive**: `a` and `u` are refused.

**This is an added guard, and the source has none.** `COUSR01C`'s only test on the field is
`WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES` [`app/cbl/COUSR01C.cbl:L142`]; every other
value, including a lower-case letter or a digit, is moved straight onto the record by
[`:L158`]. The literal `'User Type must be…'` appears nowhere in the corpus. Under
§0.8.3 of the migration plan an absent guard is preserved unless its removal is explicitly
labelled as a deviation, so it is labelled here rather than left for a reader to discover.

**Why it is nonetheless kept, in three parts.** First, the byte is not data in the target — it
is an **authorisation input**. `CDEMO-USER-TYPE` declares exactly two condition names, `'A'`
and `'U'` [`app/cpy/COCOM01Y.cpy:L26-L28`], and the migration plan maps them onto the role
model ([§5.1](#51-role-model)). A third value produces a principal with **no** role, which
fails closed but silently, so a user could be created who can sign on and reach nothing.

Second, the restriction already exists one layer down and cannot be avoided: the schema
declares `CONSTRAINT ck_user_security_type CHECK (sec_usr_type IN ('A', 'U'))`, itself derived
from those same two condition names. Removing the application guard would not admit a third
value — it would convert a `400` that **names the field** into a `409`
`CARDDEMO-CONSTRAINT-REFUSED` that names nothing, which is worse for the caller and no more
faithful.

Third, it changes no outcome the source could reach in practice: the seeded population is five
`A` and five `U` rows and nothing else [`app/jcl/DUSRSECJ.jcl`], so no legacy behaviour depends
on a third value being storable.

**What is *not* deviated from.** The blank rejection is the source's own, in the source's own
order and wording — `'User Type can NOT be empty...'` [`:L144`] — and it still fires **before**
the domain check, so a caller omitting the field is told it is empty rather than told about the
domain. Absent, empty and all-blank are all that condition.

**Exact observable literals.** The five above, plus:

| Literal | Locator |
|---|---|
| `User ID already exist...` | [`:L263`] |
| `Unable to Add User...` | [`:L270`] |
| `User <id> has been added ...` | [`:L255-L257`] |

One literal published by this operation has **no source locator**, and that is the whole point
of [§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u):
`User Type must be A for an administrator or U for a regular user`.

**Failure and status mapping.**

| Outcome | Status | `errorCode` |
|---|--:|---|
| Any field blank | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| `userType` present but neither `A` nor `U` — **added guard**, see [§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u) | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| Identifier already taken — `User ID already exist...` | **`409`** | `CARDDEMO-DUPLICATE-RECORD` |
| Data refused by a constraint other than the key — `Unable to Add User...` | `409` | `CARDDEMO-CONSTRAINT-REFUSED` |
| Store unavailable / write failure / abend | `503` / `502` / `500` | per [§8.2](#82-global-failure-and-status-mapping) |

### 16.3 Update user

**Legacy source.** CSD transaction `CU02` [`app/csd/CARDDEMO.CSD:L469`] → program
`COUSR02C` (414 lines) → `app/cpy-bms/COUSR02.CPY` (12 input fields).

**Purpose.** Rewrites the four mutable fields of one user-security record, but only when at
least one of them actually differs from what is stored.

```http
PUT /api/admin/users/{userId} HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role. ADMIN only.**

**Request.**

| Kind | Name | Map field | PIC | Required |
|---|---|---|---|:--:|
| Path variable | `userId` | `USRIDINI` | `X(8)` | **yes** — the lookup key |
| Body | user id | `USRIDINI` | `X(8)` | no — and if present it **must equal the path** |
| Body | first name | `FNAMEI` | `X(20)` | **yes** |
| Body | last name | `LNAMEI` | `X(20)` | **yes** |
| Body | password | `PASSWDI` | `X(8)` | **yes** — see below |
| Body | user type | `USRTYPEI` | `X(1)` | **yes** — `A` or `U` only, see [§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u) |

**The identifier comes from the path. The body need not repeat it, and must not contradict
it.** `USRIDINI` is a declared member of the map, so it is accepted in the body — but it is
optional there, and the path supplies it when the body omits it. This is the source's own
arrangement rather than a REST convenience: on first entry `COUSR02C` does not wait for an
operator to type the identifier, it tests `CDEMO-CU02-USR-SELECTED` — the value the user-list
screen left in the commarea — moves it into `USRIDINI` and only then runs its edit
[`app/cbl/COUSR02C.cbl:L100-L107`]. The screen field was a *carrier* of the navigation context,
not its origin, and here the path segment is that context.

A body that names a **different** user is refused with `400` and `field: "userId"`: the path
addresses the record and the body declares the same field, so preferring either one would
rewrite a record the caller did not address. The comparison is exact — nothing is trimmed and
nothing is case-folded, because `COUSR02C` folds neither.

> **An empty password does *not* mean "leave it unchanged". It is rejected.** This was
> derived from the source rather than assumed: `COUSR02C` applies the same blank check to the
> password as to every other field and answers `'Password can NOT be empty...'`
> [`app/cbl/COUSR02C.cbl:L200`]. **The password must be supplied on every update**, and the
> stored hash is therefore always rewritten from it when it differs.

> **A password is stored exactly as submitted, and sign-on folds what it is given — so a
> password must equal its own upper-case form to be usable.** Both halves are the source's.
> The write moves the field onto the record unchanged — `MOVE PASSWDI OF COUSR1AI TO
> SEC-USR-PWD` [`app/cbl/COUSR01C.cbl:L157`], and `MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD`
> [`app/cbl/COUSR02C.cbl:L228`] — while sign-on upper-cases the presented value before
> comparing it, `MOVE FUNCTION UPPER-CASE(PASSWDI …) TO WS-USER-PWD`
> [`app/cbl/COSGN00C.cbl:L135-L136`], and compares at [`:L223`]. Setting `Pw1234xy` therefore
> stores `Pw1234xy` and sign-on will only ever offer `PW1234XY`, which does not match: the
> account is created or updated successfully and **can never be signed on to**. Use a password
> with no lower-case letters. See [§18.2](#182-medium), finding M-11.

**Response — `200 OK`.** The updated user **without any password member**, plus the source's
message.

**Side effects.** One user row updated — **but only when at least one field actually
differs**, see below.

**Validation order** [`app/cbl/COUSR02C.cbl:L180-L212`]:

1. `'User ID can NOT be empty...'` [`:L182`], also checked earlier at [`:L148`]
2. `'First Name can NOT be empty...'` [`:L188`]
3. `'Last Name can NOT be empty...'` [`:L194`]
4. `'Password can NOT be empty...'` [`:L200`]
5. `'User Type can NOT be empty...'` [`:L206`]

Then, and only then, the added domain check of
[§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u) — so a caller who omits
`userType` is told it is empty, not told about the domain.

**Read-modify-write with change detection.** After the record is read
[`:L217`, with the `EXEC CICS READ` itself at `:L322-L331`], the source compares **four**
fields with a plain `NOT =` — **no case function, unlike the account and card
comparisons** [`:L219-L234`]:

```cobol
               IF FNAMEI  OF COUSR2AI NOT = SEC-USR-FNAME
               IF LNAMEI  OF COUSR2AI NOT = SEC-USR-LNAME
               IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD
               IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE
```

Each differing field is moved into the record and sets a modified flag. **If no field
differs, no rewrite occurs** and the source answers
`'Please modify to update ...'` [`:L239`] — a `200`, not an error, because nothing was
wrong with the request.

Note that **this operation needs no `If-Match` and no snapshot token.** The comparison here
is *submitted-versus-stored*, which is a change-detection test answerable from the request
alone; it is not the *as-displayed-versus-stored* concurrency test that the account and card
operations perform, so there is no snapshot to carry. The difference is in the source, not in
the target.

**Exact observable literals.** The five blank messages above, plus:

| Literal | Locator |
|---|---|
| `Please modify to update ...` | [`:L239`] |
| `User ID NOT found...` | [`:L342`], [`:L379`] |
| `Unable to lookup User...` | [`:L349`] |
| `Unable to Update User...` | [`:L386`] |
| `User <id> has been updated ...` | [`:L372-L374`] |

**Failure and status mapping.** Validation `400`; user not found `404`; store unavailable
`503`; read or write failure `502`; abend `500`.

### 16.4 Delete user

**Legacy source.** CSD transaction `CU03` [`app/csd/CARDDEMO.CSD:L479`] → program
`COUSR03C` (359 lines) → `app/cpy-bms/COUSR03.CPY` (11 input fields).

**Purpose.** Destroys one user-security record. The deletion is permanent and, as the source
did, it is **not** guarded against an administrator deleting their own account.

```http
DELETE /api/admin/users/{userId}?confirmed=... HTTP/1.1
Authorization: Bearer <token>
```

**Role. ADMIN only.**

**Request.**

| Kind | Name | Map field | PIC | Required |
|---|---|---|---|:--:|
| Path variable | `userId` | `USRIDINI` | `X(8)` | **yes** |
| Query parameter | `confirmed` | *(the PF5 gate)* | — | **yes**, to destroy the record |

The confirmation replaces the source's `WHEN DFHPF5` arm, which performs `DELETE-USER-INFO`
[`app/cbl/COUSR03C.cbl:L121-L122`, the paragraph itself at `:L174`]. Without
it the source prompts `'Press PF5 key to delete this user ...'` [`:L283`] and **nothing is
destroyed**.

**Response — `200 OK`.** Exactly five members: the identifier, first name, last name, user
type and the source's message `'User <id> has been deleted ...'` [`:L318-L320`].

| JSON member | Map field | PIC |
|---|---|---|
| `userIdInput` | `USRIDINI` | `X(8)` |
| `firstName` | `FNAMEI` | `X(20)` |
| `lastName` | `LNAMEI` | `X(20)` |
| `userType` | `USRTYPEI` | `X(1)` |
| `errorMessage` | `ERRMSGI` | `X(78)` — carries the success message on this arm, as the source does |

This operation used to serialize all eleven map fields, so it was the only one of the
seventeen that put the six chrome header fields on the wire — including `programName`, which
is the `XCTL` operand (finding M-15). They are excluded now, exactly as
[§4.2](#42-presentation-chrome-excluded-from-every-json-contract) states for every operation.
The components remain on the record type, because the map declares eleven fields and the
field contract is what that type exists to hold; what changed is only the body.

> **No password field appears on this map either.** `COUSR03.CPY` declares 11 input fields:
> the six chrome header fields plus `USRIDINI X(8)`, `FNAMEI X(20)`, `LNAMEI X(20)`,
> `USRTYPEI X(1)` and `ERRMSGI X(78)`. There is no `PASSWDI`.

**Side effects.** On the confirmed arm, **one user row is destroyed**. The deletion is
permanent; there is no soft-delete flag in the record layout.

**Validation order.** Identifier not blank [`:L147`, `:L179`] → read the record
[`:L269-L284`] → confirmation → delete [`:L307-L312`].

> ### ⚠ There is no self-delete guard: an administrator can delete their own account
>
> **`COUSR03C` never compares the target user identifier against the signed-on identifier.**
> The proof is a census rather than an impression: the field `CDEMO-USER-ID` occurs **zero
> times** in all 359 lines of `app/cbl/COUSR03C.cbl`. Deleting the acting administrator's own
> record therefore **succeeds**, and the caller's token continues to work until it expires
> even though the account behind it no longer exists.
>
> **This is preserved deliberately.** Parity is the contract, so no comparison against the
> token subject appears anywhere in this operation, no `403` is returned for a self-delete,
> and no confirmation beyond the one the source itself required is demanded. It is **not a
> bug to be fixed here**: adding a guard would be a behaviour change, and a behaviour change
> requires explicit, separately approved authorisation rather than a silent code edit.
>
> **API consumers must not rely on the server to prevent this.** A client offering
> administrator self-service should apply its own guard. Classified **M-1** in
> [§18](#18-findings-severity-classified) and justified in `../DECISION_LOG.md`.

**Exact observable literals.**

| Literal | Locator |
|---|---|
| `User ID can NOT be empty...` | [`:L147`], [`:L179`] |
| `Press PF5 key to delete this user ...` | [`:L283`] |
| `User ID NOT found...` | [`:L289`], [`:L325`] |
| `Unable to lookup User...` | [`:L296`] |
| `Unable to Update User...` — **the delete-failure arm says "Update", not "Delete"** | [`:L332`] |
| `User <id> has been deleted ...` | [`:L318-L320`] |

That last-but-one literal is a source wording quirk: the failure path of the **delete**
operation reports `'Unable to Update User...'`. It is relayed as written, because a client
matching on the text must keep matching on it.

**Failure and status mapping.** Blank identifier `400`; confirmation absent `400` with the
PF5 prompt; user not found `404`; store unavailable `503`; delete failure `502`; abend
`500`.

---

## 17. Troubleshooting

The five entries below are the integration mistakes this contract makes most likely, because
in each case the intuitive assumption is wrong. Each gives the symptom a caller actually
observes and the fix.

### 17.1 Every account update returns `412`, or `400` on `oldDetails`

**Symptom.** `PUT /api/accounts` answers `412 Precondition Failed` with
`Record changed by some one else. Please review` on every attempt, even against an account
nobody else is touching. Or it answers `400` naming the field `oldDetails`.

**Cause.** The as-displayed snapshot is being **constructed by the client** instead of being
carried through from the server. Two distinct errors produce this:

* Sending an `oldDetails` group **in the request body**. That is refused outright — the body
  is not where the precondition lives.
* Sending a dash-separated date of birth in a hand-built snapshot. The comparison reads the
  live record at offsets 1, 6, 9 and the snapshot at offsets 1, **5**, **7**
  [`app/cbl/COACTUPC.cbl:L4174-L4179`], so a `YYYY-MM-DD` snapshot **can never match** and
  the conflict is unconditional.

**Fix.** Do not build a snapshot. Call [`GET /api/accounts/{accountId}`](#111-view-account),
take the `snapshotToken` from the body or the `ETag` from the response header, and send it
back **verbatim** in `If-Match` on the `PUT`. Treat it as opaque: do not parse, reformat,
truncate or re-encode it. Then add `?confirm=true`, without which the operation answers `428`
and writes nothing. The same applies to `PUT /api/cards` with
[`GET /api/cards/detail`](#122-view-card).

### 17.2 A list returns 7 or 10 rows when more were requested

**Symptom.** `GET /api/cards?size=50` returns seven rows and reports success.
`GET /api/transactions?limit=100` returns ten.

**Cause.** **Page size is fixed by the originating screen geometry and is not
configurable** ([§7](#7-pagination-contract)). No size parameter is declared on any
operation, so an unrecognised one is **ignored rather than rejected** — the request succeeds,
which is exactly why the mistake is easy to miss.

**Fix.** Page. Use `action`, `page`, `firstKey`, `lastKey` and `nextPageAvailable`, and read
the next-page flag from the response metadata to decide whether to continue. Do not treat a
`200` as confirmation that a size parameter took effect. Remember also that the paging
booleans accept **only** `true` and `false` — `1`, `yes` and `on` are `400`.

### 17.3 A partial bill payment is rejected, or pays the whole balance

**Symptom.** An `amount` sent to `POST /api/billing/payments` has no effect and the entire
balance is settled; or the operation answers `400` with
`You have nothing to pay...` when the caller expected to pay part of a balance.

**Cause.** **The payment is always the full balance.** The map declares no amount field at
all [`app/cpy-bms/COBIL00.CPY`]: the source moves the whole current balance into the
transaction amount [`app/cbl/COBIL00C.cbl:L224`] and subtracts it to zero [`:L234`]. It also
refuses a balance that is `<= ZEROS` [`:L198-L201`], so a zero balance is refused as well as
a negative one.

**Fix.** Do not send an amount; it is not part of the contract. Read the balance from
[`GET /api/accounts/{accountId}`](#111-view-account) first if the caller needs to know what
will be paid. There is no partial-payment operation in this API. Send
`confirmation` = `Y` to settle; blank prompts, `N` cancels, anything else is `400`.

### 17.4 A monthly report covers more days than expected

**Symptom.** A monthly report submitted mid-month returns data for the whole month, including
dates after today.

**Cause.** **The monthly period is the full current calendar month, first day to last day —
not month-to-date** ([§15.1.2](#1512-three-periods-and-one-that-is-not-what-it-looks-like)).
The source discards today's day of month at [`app/cbl/CORPT00C.cbl:L223`] and computes the
end date as the first of the next month minus one day [`:L229-L230`].

**Fix.** If month-to-date is genuinely wanted, use the **custom** period with an explicit
start and end date rather than the monthly selector. Remember that this endpoint answers
`202 Accepted` and returns no report body; correlate the run through the `correlationId`.

### 17.5 A numeric value is accepted on one field and rejected on another

**Symptom.** `"$1,234.56"` is accepted as a transaction amount but `"00,000,000,011"` is
rejected as an account identifier with `Account ID must be Numeric...`.

**Cause.** **Two different parsers, used deliberately** ([§6](#6-two-numeric-parsers-used-deliberately)).
Identifiers and card numbers go through the strict form
[`app/cbl/COTRN02C.cbl:L204`, `:L218`]; amounts go through the currency-aware form
[`:L383`, `:L456`], which tolerates a currency symbol and thousands separators.

**Fix.** Send identifiers and card numbers as **digits only**, at their declared widths.
Send amounts either plain or with currency formatting. Read the echoed amount back on the
mask `+99999999.99` and note the eight-integer-digit limit of that mask
([§6.1](#61-the-amount-echo-mask)).

### 17.6 Other quick answers

| Symptom | Cause and fix |
|---|---|
| `401` with an empty body on any operation but sign-on | No usable `Authorization: Bearer` credential. Sign on first; the credential pattern accepts only `Bearer` followed by whitespace and an unpadded token |
| `403` with an empty body on `/api/admin/**` or `GET /api/menu/admin` | The token's `role` claim is not the administrator code. These paths require `ROLE_ADMIN` ([§5.1](#51-role-model)) |
| `403` on an operation the caller is entitled to | The token carries neither recognised authority, so no user class could be resolved |
| An update reports success but a field did not change | Read `changeAction` and `errorMessage`, not only `applied` — see the preserved trap at [§11.2.7](#1127-preserved-legacy-trap-an-unreported-customer-lock-failure) |
| `409` on `POST /api/transactions` or a bill payment | An identifier collision from the accepted generation race ([§13.3.1](#1331-identifier-generation-a-known-and-accepted-race)). **Retry the request** |
| A monetary value loses precision | It was parsed into a binary float. Every monetary value is an exact decimal; parse it as one ([§6.2](#62-monetary-representation)) |
| A description is shorter than expected | The transaction **list** publishes `TDESC` at `X(26)`; fetch the detail resource for the full `X(60)` ([§13.1](#131-list-transactions)) |
| `200` with an empty row array where a `404` was expected | An empty browse is end-of-data, which is a control outcome and never an error ([§8.2](#82-global-failure-and-status-mapping)) |
| A user update answers `200` with `Please modify to update ...` | Nothing differed from the stored record, so no rewrite was needed. Not an error ([§16.3](#163-update-user)) |
| A user update is rejected for a blank password | The password must be supplied on **every** update; blank does not mean "unchanged" ([§16.3](#163-update-user)) |
| A support request needs to be traced | Quote the `correlationId` from the error body. It joins the response to the server-side log records, which hold the cause chain the body deliberately omits |

---

## 18. Findings, severity-classified

Findings are classified **Blocker / High / Medium / Low**. Each carries its evidence
locator, its current status and its remediation. Items marked *preserved deliberately* are
faithful reproductions of source behaviour, not defects introduced by the migration —
changing any of them is a behaviour change and requires explicit, separately approved
authorisation rather than a silent code edit. Every one is also recorded in
`../DECISION_LOG.md`.

**No Blocker-severity finding is open against this contract.**

### 18.1 High

**H-1 — The monthly report period was documented as month-to-date; it is a full calendar
month.**

* **Severity: High.** A caller or an implementer acting on the wrong reading requests, or
  produces, the wrong data range — and the error is silent, because both readings yield a
  plausible report.
* **Locator.** [`app/cbl/CORPT00C.cbl:L213-L238`]. The decisive steps are `:L223`, which
  discards today's day of month, and `:L229-L230`, which compute the first of the next month
  minus one day.
* **Status.** Corrected in this document at
  [§15.1.2](#1512-three-periods-and-one-that-is-not-what-it-looks-like), with the
  six-step proof published so the reading can be re-verified against the source.
* **Remediation.** None outstanding for this document. Any other artefact that describes the
  monthly period as month-to-date should be corrected to match the source; this document
  treats the COBOL as authoritative and records the divergence rather than propagating it.

### 18.2 Medium

**M-1 — There is no self-delete guard on user deletion; an administrator can delete their own
account.**

* **Severity: Medium.** An administrator can remove their own credential and, because the
  already-issued token keeps validating until it expires, will not discover it immediately.
  In a single-administrator deployment this can lock the administrative surface out
  permanently.
* **Locator.** `app/cbl/COUSR03C.cbl`. The proof is a census, not an impression:
  `CDEMO-USER-ID` occurs **zero times** in all 359 lines, so no comparison against the
  signed-on identifier exists anywhere in the program.
* **Status. Preserved deliberately.** Parity is the contract, so no comparison against the
  token subject was added, no `403` is returned for a self-delete, and no extra confirmation
  is demanded. Documented at [§16.4](#164-delete-user).
* **Remediation.** Treat a guard as an **explicit, separately approved deviation** with its
  own decision-log entry — not a silent code change. Until then, an API consumer offering
  administrator self-service should apply the guard **client-side**, and deployments should
  keep more than one administrator account.

**M-2 — Transaction identifier generation is inherently racy under concurrency.**

* **Severity: Medium.** Concurrent adds can compute the same next identifier, so one of them
  fails.
* **Locator.** [`app/cbl/COTRN02C.cbl:L444-L451`] and, for bill payment,
  [`app/cbl/COBIL00C.cbl:L212-L219`]. Both use a descending browse of the maximum key plus
  one, with the empty-file case yielding a first identifier of `1`
  [`app/cbl/COBIL00C.cbl:L488`].
* **Status. Preserved deliberately.** A database sequence was **not** substituted, because
  that would change the generated values and break comparison against the legacy baseline —
  which is the parity contract. The collision surfaces cleanly as `409` with
  `CARDDEMO-DUPLICATE-RECORD` and the literal `Tran ID already exist...` rather than as
  corruption or a silent overwrite. Documented at
  [§13.3.1](#1331-identifier-generation-a-known-and-accepted-race).
* **Remediation.** Clients should **retry** on `409` for these two operations. Any move to a
  sequence or to an application-level lock is a deviation requiring approval, because it
  changes generated values.

**M-3 — A failed customer-record lock is reported as success by the account update.**

* **Severity: Medium.** The operation answers `200` and `changeAction`
  `CHANGES_OKAYED_AND_DONE` on a path where a lock was not taken, so a caller reading only
  the status believes both records were written.
* **Locator.** [`app/cbl/COACTUPC.cbl:L2606-L2615`]. The outcome `EVALUATE` tests three
  conditions and **never** tests `COULD-NOT-LOCK-CUST-FOR-UPDATE`, which therefore falls
  through `WHEN OTHER` [`:L2613-L2614`] to `SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE`.
* **Status. Preserved deliberately**, and made observable: the condition is logged at `WARN`,
  and the response reports `changeAction` and `errorMessage` alongside `applied` so the
  evidence the legacy screen withheld is available. Documented at
  [§11.2.7](#1127-preserved-legacy-trap-an-unreported-customer-lock-failure).
* **Remediation.** Clients must not infer success from the status alone on this operation:
  check `changeAction` and `errorMessage`. Adding the missing `WHEN` arm is a behaviour
  change requiring approval.

**M-4 — A wildcard request `Content-Type` produced `500 Internal Server Error` on every
body-binding operation, unauthenticated.**

* **Severity: Medium.** A caller needed no credential: `POST /api/auth/signon` with
  `Content-Type: application/*+json` answered `500` with Spring's default error body, and so
  did the other six write operations. A `500` on a caller-side mistake both misreports whose
  fault it is and gives an unauthenticated caller a cheap way to fill the error log.
* **Locator.** Not a source finding — this condition has no COBOL counterpart, because a
  3270 terminal cannot declare a media type. The mechanism is in the framework: a wildcard
  *request* type **satisfies** a `consumes` condition, so the handler is selected and the
  header parser then rejects the wildcard as an `IllegalArgumentException`, which is a `500`.
* **Status. Remediated.** A `Content-Type` screen runs after the handler is mapped and before
  the body is read, and raises the condition the request actually is. All seven operations now
  answer `415` with `CARDDEMO-UNSUPPORTED-MEDIA-TYPE`, an `Accept` response header and the
  full envelope — **byte-identically** to the `text/plain` case, because they are the same
  condition. Documented at
  [§8.5](#85-refusals-decided-before-an-operation-is-reached).
* **Remediation.** Complete. `IllegalArgumentException` was deliberately **not** mapped to a
  status globally: that would turn every programming error in the application into a `4xx`.

**M-5 — Four boundaries answered outside the error envelope, one of them with an HTML page
naming the container.**

* **Severity: Medium.** A client cannot rely on one error shape if some refusals arrive
  without `errorCode`, without `correlationId`, or without a body at all — the `406` had no
  body **and** no `Content-Type`, and `GET /api/accounts/000000000%00` returned a
  `text/html` page disclosing the servlet container and its version.
* **Locator.** Not a source finding. Four distinct boundaries, enumerated with what each can
  carry at [§8.5](#85-refusals-decided-before-an-operation-is-reached).
* **Status. Remediated.** Every boundary now renders the same envelope as
  `application/problem+json`, and the container's HTML report is replaced rather than merely
  silenced — turning it off would have removed the disclosure but left an empty body, which
  is the inconsistency being closed. `§8.1` was also corrected: it claimed ten error codes
  were the complete set while nineteen were published.
* **Remediation.** Complete. Three constraints were honoured rather than worked around: no
  global advice was introduced, no ninth class was added to the controller package, and
  neither the HTTP firewall's allowed-method set nor the connector's `allowTrace` was widened
  to make a refusal prettier. The one residual asymmetry is contractual and documented: a
  refusal decided before any filter ran carries a **minted** `correlationId`, written to the
  log, because the request's own identifier does not yet exist at that point.

**M-6 — A card list row disclosed no value any other card operation would accept, so
`list -> detail -> update` was not traversable by an API-only client.**

* **Severity: Medium.** Every one of the four values a row published — `rowNumber`,
  `accountNumber`, `maskedCardNumber`, `statusCode` — was refused by
  `GET /api/cards/detail`, which requires an eleven-digit account **and** a full sixteen-digit
  card number. A client could enumerate the whole list and still not reach a single card, and
  `PUT /api/cards` with no identifier answered `CARDDEMO-REQUEST-BODY-UNREADABLE`, which
  named neither the field nor the reason.
* **Locator.** Not a source finding: the 3270 row published the full card number and an
  operator selected it in place [`app/cbl/COCRDLIC.cbl:L115-L116`]. The gap is a consequence
  of masking that column in the target — a deliberate divergence — without supplying anything
  in its place.
* **Status. Remediated.** Each row now carries an opaque, sealed, purpose-scoped `cardKey`
  accepted by both the detail and the update operation in place of the two filters, and a
  request naming no card is refused with `400` naming `accountId` and the source's own
  `'No input received'`. The masking itself is **kept and now labelled** rather than reverted.
  Documented at
  [§12.1.1](#1211-labelled-deviation-a-list-row-masks-the-card-number-and-carries-a-handle-instead),
  [§12.2](#122-view-card) and [§12.3](#123-update-card).
* **Remediation.** Complete. The reference is a **query parameter, not a body member**: the
  update body is fixed at the seventeen map fields plus the two snapshot groups, and widening
  it would have broken that contract to solve a routing problem.

**M-7 — Paging backward without the positioning state answered `502` with
`CARDDEMO-IO-FAILURE`.**

* **Severity: Medium.** `GET /api/cards?action=PAGE_BACKWARD` with no `firstKey` reported a
  store failure for what was a malformed request, so a caller was told to retry or escalate
  when the fix was to send the parameter. It also made a caller-side omission look like an
  infrastructure incident in the log.
* **Locator.** `9100-READ-BACKWARDS` [`app/cbl/COCRDLIC.cbl:L1264`] has **no end-of-data
  arm**: its `EVALUATE WS-RESP-CD` [`:L1304-L1318`] carries `NORMAL`, `DUPREC` and
  `WHEN OTHER` only, so an unpositioned backward read is a file error by construction. That
  state is unreachable in the source because the key and the page number are fields of one
  COMMAREA record [`:L228-L237`]; putting them on the wire separated them.
* **Status. Remediated at the boundary, with the browse left transcribed exactly.** The
  precondition is checked before the read: an absent `page` is `400` naming `page`, an absent
  `firstKey` beyond the first page is `400` naming `firstKey`, and a cursor minted for another
  page is refused rather than honoured. Backward paging **from** the first page is unchanged
  and still answers `200` with the top-of-page literal. `9100-READ-BACKWARDS` itself was not
  edited and no end-of-data arm was invented. Documented at
  [§7.1](#71-paging-parameters).
* **Remediation.** Complete. Clients must echo `action`, `page`, `firstKey`, `lastKey` and
  `nextPageAvailable` together, as [§7.1](#71-paging-parameters) now sets out.

**M-8 — The expiry day was taken from the request, so a read-modify-write round trip erased
it.**

* **Severity: Medium.** `GET /api/cards/detail` cannot publish an expiry day — `COCRDSL.CPY`
  declares no `EXPDAYI` — so a client echoing a detail read back to `PUT /api/cards`
  submitted none, and the `STRING` at [`app/cbl/COCRDUPC.cbl:L1467-L1474`] composed an expiry
  with the day blank. The stored value was silently erased, and the affected row still looked
  plausible: a ten-character column holding `2023-03-` reads as a date until it is measured.
  Two seeded rows were found in that state and were restored from
  `app/data/ASCII/carddata.txt`.
* **Locator.** The day is a field the source refuses to let a user change, and says so:
  `3200-SETUP-SCREEN-VARS` echoes `CCUP-OLD-EXPDAY` on every arm [`:L1110`, `:L1123`,
  `:L1127`], the new-value move at [`:L1120-L1122`] is **commented out** beneath the banner
  `'MOVE OLD VALUES TO NON-DISPLAY FIELDS THAT WE ARE NOT ALLOWING USER TO CHANGE(FOR NOW)'`,
  and [`:L1285`] renders the field dark with `DFHBMDAR`. A terminal returns what was sent, so
  `CCUP-NEW-EXPDAY` at [`:L621`] can only ever be the old day.
* **Status. Remediated.** The screen was the carrier of that value; statelessly the sealed
  snapshot is. The submitted component is still accepted and still echoed, exactly as the
  source echoes `EXPDAYO`, and simply never reaches the rewrite image. Documented at
  [§12.3.1](#1231-the-expiry-day-is-not-a-changeable-field).
* **Remediation.** Complete. The field was **not** removed from the request: `COCRDUP.CPY`
  declares it and the DTO mirrors the map. To change an expiry, change the month and the year.

**M-9 — `PUT /api/admin/users/{userId}` could not succeed with its documented body.**

* **Severity: Medium.** The documented shape — the identifier in the path only — was refused
  with `400`, `field: "userId"` and `'User ID can NOT be empty...'`. The operation was reachable
  **only** by duplicating the identifier inside the body, which nothing documented, so a client
  written from this contract could not update a user at all.
* **Locator.** The controller validated path-and-body agreement and then relayed the **body**
  to the service, so a body that named no identifier reached the source's empty-identifier
  rejection. The source does not work that way: on first entry `COUSR02C` tests
  `CDEMO-CU02-USR-SELECTED` and moves it into `USRIDINI` before running any edit
  [`app/cbl/COUSR02C.cbl:L100-L107`]. The screen field carried the navigation context; it did
  not originate it.
* **Status. Remediated.** The path identifier is supplied when the body omits it, reproducing
  that MOVE and nothing else — the other eleven members travel exactly as sent. The agreement
  check is unchanged, so a body naming a different user is still refused, and the source's
  empty-identifier rejection at [`:L146-L151`] is untouched and still reachable. Documented at
  [§16.3](#163-update-user).
* **Remediation.** Complete. Either form now works: omit the body identifier, or send one that
  agrees.

**M-10 — The restriction of `userType` to `A` and `U` was an added guard, unlabelled.**

* **Severity: Medium.** Not a functional defect — the guard is correct and is retained — but an
  unlabelled divergence from strict parity is exactly what §0.8.3 of the migration plan forbids,
  and a reader comparing the two systems would find a published literal with no source locator
  and no explanation.
* **Locator.** `COUSR01C`'s only test on the field is
  `WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES` [`app/cbl/COUSR01C.cbl:L142`]; every other
  value is moved straight onto the record by [`:L158`]. The literal
  `User Type must be A for an administrator or U for a regular user` appears nowhere in the
  corpus.
* **Status. Retained and now labelled**, with the reasoning published rather than implied: the
  byte is an authorisation input mapped from the two condition names of
  [`app/cpy/COCOM01Y.cpy:L26-L28`], the same domain is already enforced by
  `ck_user_security_type`, and removing the application guard would turn a `400` naming the
  field into a `409` naming nothing. Documented at
  [§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u), referenced from
  [§16.2](#162-add-user) and [§16.3](#163-update-user).
* **Remediation.** Complete as a documentation obligation. Relaxing the guard is a behaviour
  change requiring approval, and would not admit a third value anyway — the schema constraint
  would refuse it less informatively.

**M-11 — A password containing a lower-case letter can be set but never used.**

* **Severity: Medium.** An administrator can create or update a user with, say, `Pw1234xy`, be
  told `User <id> has been added ...` or `... has been updated ...`, and have produced an
  account that **cannot be signed on to**. Nothing in either response indicates it, and the
  failure surfaces later as an ordinary `401` indistinguishable from a typo.
* **Locator.** The asymmetry is entirely the source's. The write stores the field unchanged —
  `MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD` [`app/cbl/COUSR01C.cbl:L157`] and
  `MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD` [`app/cbl/COUSR02C.cbl:L228`] — while sign-on
  upper-cases what it is given before comparing,
  `MOVE FUNCTION UPPER-CASE(PASSWDI …) TO WS-USER-PWD` [`app/cbl/COSGN00C.cbl:L135-L136`],
  comparing at [`:L223`]. The two never agree unless the stored value already equals its own
  upper-case form. Note that `app/csd/CARDDEMO.CSD` declares **no** `UCTRAN`, so the terminal
  did not fold the input either — the quirk is live in the source, not masked by the transaction
  monitor. It goes unnoticed because all ten seeded passwords are the literal `PASSWORD`
  [`app/jcl/DUSRSECJ.jcl`], which is already upper-case.
* **Status. Preserved deliberately**, and now disclosed. Folding on the write, or refusing a
  lower-case password, would each change which credentials the system accepts, and parity is the
  contract. What is corrected is the silence: both write operations now carry the warning
  ([§16.2](#162-add-user), [§16.3](#163-update-user)) and the sign-on section already stated its
  half ([§9.1](#91-sign-on)).
* **Remediation.** Clients should supply passwords with no lower-case letters. Folding the
  password on the write would make every stored credential usable and is the obvious repair, but
  it is a behaviour change requiring approval — it would also silently widen the accepted
  credential set for existing rows.

**M-12 — `/actuator/prometheus` refused every caller with a body that named no cause.**

* **Severity: Medium.** The refusal was correct — the endpoint fails closed until a scrape
  credential is configured — but it was unreadable: Spring's default body
  (`timestamp`, `status`, `error`, `path`) under `Content-Type: application/json`, with no
  `errorCode`, no `correlationId` and a detail that said nothing. A missing credential and a
  missing route were indistinguishable from outside, and a review concluded the endpoint had no
  matcher declared at all. It has one, and a credentialled scrape returns `200` with the four
  named business counters.
* **Locator.** Not a source finding; the corpus has no instrumentation. The mechanism is
  mechanical and recurs: `BasicAuthenticationEntryPoint` refuses via
  `HttpServletResponse.sendError`, which forwards to the registered error page, and
  `BasicErrorController` renders the body — so anything written afterwards is discarded. Its
  bearer counterpart sets the status directly, which is why the business chain's envelope worked
  and this one's did not. The same trap produced the zero-length request-rejection body in M-5.
* **Status. Remediated**, in three parts. The challenge now sets the status and the
  `WWW-Authenticate` header directly and writes the standard envelope, so a refusal carries
  `CARDDEMO-AUTHENTICATION-REQUIRED` and a `correlationId`. Its detail describes **this** chain —
  HTTP Basic, not a bearer token — because the shared detail directed a scraper to the sign-on
  operation, which this chain refuses outright. And the realm names the endpoint
  (`carddemo-metrics-scrape`) instead of the framework's literal `Realm`. A non-`GET` reaches the
  chain's deny-all and now answers `403` in the envelope **without** advertising
  `WWW-Authenticate: Bearer`, which a Basic-only endpoint had no business sending. Documented at
  [§2.2](#22-operational-endpoints-and-the-scrape-credential).
* **Remediation.** Complete. **No security relaxation:** the endpoint is still credentialled,
  still `GET`-only, still refuses bearer tokens, still fails closed when unconfigured, and no
  other Actuator path became reachable. The body still declines to say whether a credential is
  configured — that fact goes to the log, naming both configuration keys.

**M-13 — One API published two action-token vocabularies for one navigation.**

* **Severity: Medium.** `GET /api/cards` and `GET /api/transactions` accepted
  `SUBMIT | PAGE_BACKWARD | PAGE_FORWARD` and refused any other spelling, while
  `GET /api/admin/users` accepted `submit | page-backward | page-forward` and refused *those*
  three. Each list operation therefore rejected the vocabulary its siblings required, and a
  client that learned one had to unlearn it for the third — with a `400` naming a token it had
  just been told to use.
* **Locator.** Not a source finding. The tokens are an API invention: the source dispatches on
  `EIBAID` attention identifiers (`DFHENTER`, `DFHPF7`, `DFHPF8`), which have no spelling to
  inherit, so nothing in the corpus favoured either form.
* **Status. Remediated.** The admin operation now declares the same three tokens as its two
  siblings, so the whole surface has one vocabulary. Matching stays exact on all three — no
  alias, no padding, no case fold — because a normalising converter on a control token accepts
  instructions the operation never declared. The former spellings are now refused there, which
  is the point: two vocabularies were the defect. Documented at
  [§7.1](#71-paging-parameters).
* **Remediation.** Complete.

**M-14 — A page past the first was served without the cursor that addresses it, and the
response reported a page it had not served.**

* **Severity: Medium.** `GET /api/cards?action=PAGE_FORWARD&page=8` with no cursor answered
  `200` reporting `pageNumber=8` while serving **page one's seven rows**;
  `action=SUBMIT&page=8` did the same. The two ten-row operations answered `200` reporting
  `pageNumber=5` with an **empty** row array. A client could not tell an advance from a
  re-serve, nor an exhausted browse from an unpositioned one, and a client keying its next
  request off the reported number could not terminate.
* **Locator.** The page number is a display counter, not an address. What positions a browse is
  the saved key the `DFHPF7` / `DFHPF8` arms move into the record key before the `STARTBR`;
  `WS-CA-SCREEN-NUM` [`app/cbl/COCRDLIC.cbl:L237`] and its two siblings only feed the heading.
  With no key the space-filled field positions at the start of the file. The source cannot
  exhibit the disagreement because the counter and both keys are fields of one COMMAREA record
  written by a single send, so a task holding a counter past the first necessarily held the key
  that addresses it — the same invariant M-7 turned into a precondition, on the other direction
  of travel.
* **Status. Remediated.** A request naming a page past the first must carry the cursor that
  addresses it — `lastKey` forward, `firstKey` otherwise — or it is refused with `400` naming
  that cursor. The first page and an unnamed page are admitted with no cursor, and the
  enter-key arm of the two ten-row operations is exempt because it always answers the first
  page [`app/cbl/COUSR00C.cbl:L227`]. No browse changed. Documented at
  [§7.1](#71-paging-parameters).
* **Remediation.** Complete.

**M-15 — Two operations published legacy screen chrome, including the `XCTL` operand.**

* **Severity: Medium.** Both menu operations put `options[].programName` on the wire —
  `COACTVWC`, `COUSR00C` and their siblings — and the delete-user confirmation serialized all
  eleven of its map fields, so it was the only one of the seventeen operations to publish
  `transactionName`, `title01`, `title02`, `currentDate`, `currentTime` and `programName`. Both
  contradicted [§4.2](#42-presentation-chrome-excluded-from-every-json-contract), which states
  the six chrome fields are excluded from every operation, and both handed a client the one
  piece of state Transformation Rule 7 exists to remove.
* **Locator.** The option tables do declare a program name
  [`app/cpy/COMEN02Y.cpy:L91`, `app/cpy/COADM02Y.cpy:L48`] and `COUSR03.CPY` does declare
  eleven fields, so the components belong on the record types. What does not belong on the wire
  is what the program name is *for*: it is the operand of `EXEC CICS XCTL PROGRAM(...)`
  [`app/cbl/COMEN01C.cbl:L152-L156`], and `app/cpy/COCOM01Y.cpy`'s routing members are mapped
  to "no equivalent — routing is URL-based" for exactly that reason.
* **Status. Remediated.** The excluded members are suppressed from JSON only. Every component,
  its width check and its copybook citation stay on the record, so the field contract and the
  460-field budget are untouched; the delete response is now the five members
  [§16.4](#164-delete-user) documents, and a menu response carries option-table data with no
  routing state. Navigate with the table in
  [§10.0](#100-preamble-not-an-operation-option-dispatch-does-not-survive-into-the-target).
* **Remediation.** Complete.

**M-16 — Both menu operations published a `400` outcome no request could reach.**

* **Severity: Medium.** [§10.1](#101-main-menu) and [§10.2](#102-admin-menu) each carried an
  "option validation refused → `400 CARDDEMO-VALIDATION-REJECTED`" row. Neither operation
  accepts any input — no path variable, no query parameter, no body — so there is nothing a
  caller can get wrong and the row could never occur. A published status a client must handle
  but can never observe is worse than silence: it invites dead error-handling code.
* **Locator.** The option-selection paragraphs exist and are transcribed
  [`app/cbl/COMEN01C.cbl:L127-L162`, `app/cbl/COADM01C.cbl:L124-L148`], but option dispatch has
  no target operation by design
  ([§10.0](#100-preamble-not-an-operation-option-dispatch-does-not-survive-into-the-target)),
  and the operation inventory is fixed at seventeen
  ([§2](#2-operation-inventory-why-there-are-exactly-17)), so no route may be added to reach
  them. Two of the three gates could not be exercised even then with the shipped data: no
  user-type byte is `'A'` (L-3) and neither option table contains a program name beginning
  `DUMMY`.
* **Status. Remediated.** The rows are removed and the reason is stated where they were, with
  the gates retained above them as the transcribed source behaviour the traceability contract
  requires. No route was added and no behaviour changed.
* **Remediation.** Complete.

**M-17 — The report confirmation prompt answered `400`, contradicting the API's own two other
confirmation gates.**

* **Severity: Medium.** `POST /api/reports` with a period selected and no `confirmation`
  answered `400`, although [§15.1](#151-submit-transaction-report) documents `200` with
  `'Please confirm to print the <name> report...'`, and although
  [bill payment](#141-bill-payment) and [transaction add](#133-add-transaction) both answer
  `200` on the identical condition. One API graded the same handshake two different ways.
* **Locator.** `CORPT00C`'s confirmation gate has four arms and grades none of the three
  non-publishing ones differently: the blank arm [`:L464-L474`], the `N` arm [`:L480-L483`] and
  the `WHEN OTHER` arm [`:L484-L493`] each set `WS-ERR-FLG` and perform
  `SEND-TRNRPT-SCREEN`. Only the fourth carries a value the one-byte `CONFIRMI` field cannot
  accept, which is the only one of the three that is a malformed request.
* **Status. Remediated.** The prompt arm returns the screen with the source's composed literal
  and publishes nothing, as the decline arm already did. Two details of the source are kept
  that distinguish it from the decline arm: the form is **not** cleared — [`:L464`] has no
  `PERFORM INITIALIZE-ALL-FIELDS`, so a caller may simply add its confirmation and resend —
  and the cursor **is** placed on the confirmation field [`:L470`]. The `WHEN OTHER` arm
  remains `400`.
* **Remediation.** Complete.

### 18.3 Low

**L-1 — The source's sign-on messages distinguish an unknown identifier from a wrong
password.**

* **Severity: Low.** The two distinct captions form a **user-enumeration oracle** readable by
  an unauthenticated caller. It is Low rather than higher because it discloses only the
  existence of an identifier, not any credential.
* **Locator.** `'Wrong Password. Try again ...'` [`app/cbl/COSGN00C.cbl:L242-L243`] versus
  `'User not found. Try again ...'` [`:L249`].
* **Status. Remediated in the target, and the remediation is labelled rather than silent.**
  Both outcomes answer `401` with the same title, the same fixed detail
  `The user identifier or the password is incorrect.`, the same error code, no `field` and no
  `failureKind` — byte-identical, so they cannot be told apart from outside. The distinction
  survives only in a cause chain logged at `DEBUG`.
* **Remediation.** Complete. Recorded here because it is a **deliberate divergence from
  strict parity**: a reader comparing the two systems would otherwise find two source
  literals with no counterpart and conclude they were lost. Documented at
  [§9.1.1](#911-labelled-deviation-the-two-refusal-outcomes-are-made-indistinguishable).

**L-2 — No generated OpenAPI / Swagger specification exists.**

* **Severity: Low.** Consumers have no machine-readable schema, so no client can be generated
  and no automated contract test can be driven from a specification.
* **Locator.** Not applicable — this is an absence, and it is by design. Generated
  specification tooling is out of scope for the migration.
* **Status. Deferred residual risk**, recorded in
  [validation-gates.md](validation-gates.md). This hand-written document is the mitigating
  control and is the contract of record.
* **Remediation.** Introducing specification tooling is a **separate, scoped change**: it
  adds a dependency and a build step, and both need approval. Until then, treat this document
  as authoritative and keep it updated alongside any controller change.

**L-3 — The main menu's administrator-only option gate is unreachable with the shipped
option table.**

* **Severity: Low.** No functional impact today; it is a latent inconsistency between a guard
  and the data that would trigger it.
* **Locator.** The gate is [`app/cbl/COMEN01C.cbl:L136-L143`], which tests
  `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'`. But **all ten user-type bytes in
  `app/cpy/COMEN02Y.cpy` are `'U'` and none is `'A'`**, so the condition cannot be satisfied.
  Relatedly, the caption `'Transaction Add (Admin Only)'` is **commented out** at
  [`app/cpy/COMEN02Y.cpy:L69`], the active caption being `'Transaction Add'`.
* **Status. Preserved deliberately.** The gate and its literal
  `'No access - Admin Only option... '` are retained, because a future option table could
  carry `'A'` and because deleting the gate would break paragraph-level traceability.
* **Remediation.** None required. The consequence for callers — that **all ten main-menu
  options are available to a standard user** — is documented at
  [§10.1](#101-main-menu) and is why [§5.1](#51-role-model) grants both roles to every
  non-administrator operation.

**L-4 — The card update's no-change arm was unreachable, so an identical resubmission wrote
the row again.**

* **Severity: Low.** No data was corrupted — the row was rewritten with the values it already
  held — but the optimistic-locking version was incremented on every resubmission, which
  invalidates any snapshot another caller holds and answers `200 'Changes committed to
  database'` for a request that committed nothing. It also contradicted this document, which
  already specified the `200` and the literal.
* **Locator.** The source arm is real and reachable: `NO-CHANGES-DETECTED`
  [`app/cbl/COCRDUPC.cbl:L188`], grouped with the informational cursor arms at
  [`:L1212-L1214`] and leaving the state machine in `CCUP-SHOW-DETAILS` at [`:L972-L976`].
  The target restored its comparison baseline from the *request body's* snapshot group, which
  is refused outright on this operation and so was always absent, leaving the comparison to
  run against an empty group and never match.
* **Status. Remediated.** The baseline is taken from the snapshot the server itself opened, so
  the arm fires: an identical resubmission answers `200` with
  `'No change detected with respect to values fetched.'` in `errorMessage`, writes no row and
  leaves the version untouched. Documented at [§12.3](#123-update-card).
* **Remediation.** Complete. The redisplayed embossed name is upper-cased, which is the
  source's own in-place fold [`:L1499-L1501`] and not a change to stored data.

**L-5 — The account-update abend diagnostic reported `code=null` while the response reported
`9999`.**

* **Severity: Low.** Diagnostic only; no request outcome was affected. The log line read
  `CAUP abend: code=null culprit=COACTUPC` for an abend whose response body carried abend code
  `9999`, so an operator correlating the two had no way to see they were the same event, and a
  null where a four-character code belongs reads like a second, separate defect.
* **Locator.** `ABEND-ROUTINE` moves a value into `ABEND-CODE` on only some paths; the terminal
  code comes from `EXEC CICS ABEND ABCODE('9999')` [`app/cbl/COACTUPC.cbl:L4220-L4222`], which
  is a different field. The exception payload already substituted the terminal code for a blank
  work-area field — the diagnostic was simply written *before* that substitution and printed the
  raw field.
* **Status. Remediated.** The substitution is resolved first and the diagnostic reports the code
  the caller is told. Nothing is lost: the only value the raw field can hold at that point is
  the blank the substitution replaces. Pinned by a log-content assertion.
* **Remediation.** Complete.

**L-6 — A whitespace-only transaction identifier was reported as an invalid value rather than a
blank one.**

* **Severity: Low.** `GET /api/transactions/detail` with no `transactionId` reported failure
  kind `BLANK`, while `transactionId=` and `transactionId=%20%20` reported `INVALID` — although
  all three carry the same message, `'Tran ID can NOT be empty...'`. Telling a client its value
  was invalid when what was detected is that there was no value in it inverts the meaning of the
  two kinds, and it disagreed with the account-view identifier, which reports `BLANK` for a
  whitespace-only value.
* **Locator.** The source draws no distinction and cannot: `:147` is a single arm,
  `WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES` — the field a terminal left untouched and
  the field a terminal filled with blanks are tested together, produce one message and set one
  cursor [`app/cbl/COTRN01C.cbl:L146-L152`].
* **Status. Remediated.** Both arms report `BLANK`. `INVALID` is reserved for what it names: a
  value that was supplied and does not conform — which on this operation is not refused at all,
  because the program has no numeric edit and a non-conforming identifier flows into the read
  and returns `404`.
* **Remediation.** Complete.

### 18.4 Not available

The following cannot be established from the sources available and are recorded as
**Not available** with what would be needed, rather than guessed at.

| Item | Status | What is needed |
|---|---|---|
| Any statement that this API's implementation is complete, that its tests pass, or that any coverage, latency or throughput figure has been achieved | **Not available — implementation/evidence not yet generated.** No such evidence exists, and none is claimed anywhere in this document | Execution of the validation gates and publication of their evidence in [validation-gates.md](validation-gates.md) |
| Pass or fail status of any of the eight validation gates | **Not available — implementation/evidence not yet generated** | As above. Note that several gates additionally require a running container topology |
| Service-level objectives — target latency, throughput or availability per operation | **Not available.** The legacy system publishes no service-level objective anywhere in the corpus, so none may be invented | A stakeholder-agreed objective. Until then the performance gate records a **measured baseline**, not a target |
| A `mkdocs build --strict` result for this page | **Available.** With this page registered in the `nav` and the documents it links to present, `mkdocs build --strict` completes with **zero warnings and zero errors** (MkDocs 1.6.1, `techdocs-core` 1.7.0, `mermaid2`). See [§18.5](#185-documentation-build-verification) for the two warning classes the build reports while this batch is still in progress | Nothing outstanding |
| A REST contract for CICS transaction `CDV1` | **Not available, and correctly so.** Its program has no source anywhere in the repository ([§2](#2-operation-inventory-why-there-are-exactly-17)) | Nothing. There is nothing to translate, and no endpoint is invented for it |
| Interpretation of the `CRDSTP` row-type marker as a JSON value | **Not available.** It drove 3270 screen attributes, and no non-presentational meaning for it can be established from the source | Nothing. It is excluded as presentation chrome ([§12.1](#121-list-cards)) |

**There are no open `TODO` items in this document.** Every gap above is an explicit
*Not available* with a stated prerequisite, and every preserved behaviour has a tracked
finding identifier and a decision-log entry.

### 18.5 Documentation build verification

This page was verified against the repository's own MkDocs configuration — `techdocs-core`
and `mermaid2`, with `pymdownx.superfences` registering the Mermaid custom fence — rather
than assumed to render.

**Result: `mkdocs build --strict` completes with exit status 0, zero warnings and zero
errors**, once this page is registered in the `nav` and the documents it links to are
present. The rendered page carries 83 tables and 54 highlighted code blocks, every one
of its 81 in-page anchors resolves to a heading identifier, and **no code block produces a
syntax highlighting error token**.

Two classes of warning are expected while this batch is still in progress, and both are
resolved by documents outside this page:

| Warning | Cause | Resolution |
|---|---|---|
| `api-contracts.md` is not included in the `nav` configuration | The `nav` entry `API Contracts: api-contracts.md` is added by the `mkdocs.yml` update, which is a separate change | Lands with that update. **Until it does, this page does not publish at all** — see [§19](#19-contract-change-control) |
| Links to `validation-gates.md`, `architecture-before-after.md`, `onboarding-guide.md` and `executive-presentation.html` have no target | Those four documents are created alongside this one and are not present yet | Lands with those documents. The link filenames are the ones the `nav` will carry, so they must not be changed to work around the warning |

Because `--strict` promotes warnings to errors, neither of the above may be left outstanding
once the batch completes: a nav omission means the page silently never appears, and a dangling
link means a reader cannot reach the document that owns the detail this page deliberately does
not duplicate.

---

## 19. Contract change control

This document is the contract of record, which places two obligations on anyone changing the
HTTP surface.

* **A controller change is a contract change.** Adding, removing or altering an operation,
  a field, a width, a status, an error code or a message literal requires this page to be
  updated in the same change. There is no generated specification to fall back on
  (finding **L-2**), so a divergence here is a divergence with no other source of truth.
* **New documents must be registered in the navigation.** `mkdocs.yml` publishes through
  `techdocs-core`, and `catalog-info.yaml` sets `backstage.io/techdocs-ref: dir:.`, so a
  document absent from the `nav` block **never appears** — with no error and no warning.
  This page is registered as `API Contracts: api-contracts.md`; a change of filename must be
  matched there.

Field widths, validation order and message literals are **not editorial choices**. They are
read from the frozen corpus under `app/`, which is the parity oracle, the field-contract
source and the traceability anchor simultaneously. Where this document and that corpus
disagree, **the corpus governs** and the discrepancy belongs in
[§18](#18-findings-severity-classified).
