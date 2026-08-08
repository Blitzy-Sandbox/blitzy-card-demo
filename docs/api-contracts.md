<!--
  ******************************************************************
  * Program     : api-contracts.md
  * Application : CardDemo
  * Type        : Documentation - REST API contract of record
  * Function    : Publishes the HTTP/JSON surface of the Java 25 / Spring Boot
  *               3.5.11 migration target: 17 operations across 8 controllers,
  *               derived field-by-field from the CICS CSD, the 17 BMS symbolic
  *               maps and the 17 online COBOL programs of the frozen app/ tree.
  * Source      : app/csd/CARDDEMO.CSD, app/cpy-bms/**, app/cbl/CO*.cbl,
  *               app/cpy/COCOM01Y.cpy, app/cpy/CSUSR01Y.cpy,
  *               app/cpy/CSMSG02Y.cpy, app/cpy/COMEN02Y.cpy,
  *               app/cpy/COADM02Y.cpy @ 7756d89
  ******************************************************************
  * Copyright Amazon.com, Inc. or its affiliates.
  * All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License").
  * You may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  * either express or implied. See the License for the specific
  * language governing permissions and limitations under the License
  ******************************************************************
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
([§18.6](#186-documentation-build-verification)) — full provisioning detail belongs to
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
corroboration: the *Online* application-inventory table under `README.md`'s
`#### **Online**` heading lists exactly 17 rows and contains no row for that transaction.

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
| T1 | The as-displayed snapshot for `PUT /api/accounts` **travels in the request body as the opaque string `snapshot`, echoed back verbatim from the read**. It is sealed, so it cannot be read, assembled or edited by a client — and a body naming the old `oldDetails` object is refused with `400` | "I can assemble the old-values group myself from the displayed fields" | [§11.2](#112-update-account) |
| T2 | Page sizes are **fixed at 7, 10 and 10** by screen geometry and are **not** client-configurable | "`?size=50` will page bigger" | [§7](#7-pagination-contract) |
| T3 | Bill payment always pays the **entire** balance; there is **no amount field** | "I can make a partial payment" | [§14.1](#141-bill-payment) |
| T4 | The monthly report period is the **full current calendar month**, first day to **last** day | "Monthly means month-to-date" | [§15.1](#151-submit-transaction-report) |
| T5 | Identifiers and card numbers use a **strict digits-only** parser; amounts use a **currency-tolerant** one | "One numeric format works everywhere" | [§6](#6-two-numeric-parsers-used-deliberately) |

[§17, Troubleshooting](#17-troubleshooting) gives the observable symptom and the fix for
each.

---

## 4. Field-contract provenance

Every request and response field on this page derives from a field of a BMS symbolic map.
The seventeen symbolic maps in `app/cpy-bms/` carry **441 input fields in total**, distributed
as:

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

**How the census is derived, and why not 460.** The figure is counted, not transcribed. A BMS
symbolic map names its input field `<field>I` at level `02`, so every row above and the total
are reproduced by:

```shell
for f in app/cpy-bms/*.CPY; do
  printf '%s %s\n' "$(basename "$f")" "$(grep -cE '^ +02 +[A-Z0-9]+I +PIC ' "$f")"
done
```

`GateVerificationTest` asserts the same census independently, against
`EXPECTED_BMS_INPUT_FIELDS = 441`, and so do `OnlineTransactionE2ETest`, `AccountDtoTest` and
`TransactionDtoTest`.

**A figure of 460 circulates in project prose and is wrong.** It is also internally
inconsistent with the per-map table it accompanies, which sums to 440 — the one differing cell
being `COACTVW`, published as 36 where the map declares **37**. Neither 460 nor 440 may be
carried forward. The distinction is not cosmetic: this census is the DTO
field budget, so an unverified total means unverified DTOs.

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

### 4.2 Presentation chrome is **accepted** on request, and absent from every response

The six recurring header fields are **not** "excluded from the JSON contract in every operation
below", and reading them that way is the error that breaks a client: the request DTOs declare and
deserialize every one of them, so a caller that omits them is fine and a caller that sends them is
*also* fine. A caller who concluded the fields were rejected would be wrong.

The contract has two halves, and they differ:

* **On request** — accepted and ignored. Every request type carries the six as optional
  `String` members. `SignOnRequest`, `BillPaymentRequest`, `ReportRequest`,
  `TransactionAddRequest`, `UserUpdateRequest` and `CardUpdateRequest` declare them as
  record components; `AccountUpdateRequest` and `UserCreateRequest` declare them as
  `@JsonProperty`-annotated fields. Nothing reads them: no service consults a chrome member,
  and none is persisted. They exist so that a payload captured from the legacy screen
  round-trips without a deserialization failure, which matters because
  `spring.jackson.deserialization.fail-on-unknown-properties` is **enabled** — with the
  fields absent from the DTO, sending them would be a `400`.
* **On response** — withheld from **every** response body, with no exception. No account,
  card, transaction, billing, report, sign-on, menu or user response type puts a chrome
  member on the wire, and `AccountViewResponse`, `UserCreateResponse`, `UserListResponse`
  and `UserUpdateResponse` each publish a `WITHHELD_COMPONENTS` list naming the omitted
  members explicitly, so the omission is asserted rather than incidental.

  One nested type needs stating so it is not mistaken for an exception.
  `MenuResponse.MenuScreen` is a record whose twenty components do begin with all six chrome
  fields — it transcribes the twenty input fields of `app/cpy-bms/COMEN01.CPY` and
  `app/cpy-bms/COADM01.CPY` as a field contract. **It is never serialized.** The two menu
  operations return `MenuResponse<MainMenuOption>` and `MenuResponse<AdminMenuOption>`
  [`MenuController.java`], whose serialized members are exactly `menuType`,
  `options` and `optionCount`; no code path anywhere places a `MenuScreen` in a response
  body. The option records additionally suppress `programName` with
  `@JsonIgnoreProperties("programName")` on both option records [`MenuResponse.java`], so the one piece
  of legacy dispatch state the map carries never reaches a client either.

  Verify with
  `grep -n transactionName src/main/java/com/cardemo/model/dto/*Response.java`: every match
  outside `MenuResponse` sits inside a withheld-components list, and the `MenuResponse`
  matches sit on the unserialized `MenuScreen` record.

| Field | Width | JSON property | Status |
|---|---|---|---|
| `TRNNAMEI` | `X(4)` | `transactionName` | Accepted, ignored, never returned |
| `TITLE01I` | `X(40)` | `title01` | Accepted, ignored, never returned |
| `CURDATEI` | `X(8)` | `currentDate` | Accepted, ignored, never returned |
| `PGMNAMEI` | `X(8)` | `programName` | Accepted, ignored, never returned |
| `TITLE02I` | `X(40)` | `title02` | Accepted, ignored, never returned |
| `CURTIMEI` | `X(8)`, **but `X(9)` on `COSGN00.CPY`** | `currentTime` | Accepted, ignored, never returned |

`COSGN00.CPY` is the **only** map on which `CURTIME` is nine characters, and the only one
carrying two further chrome fields, `APPLIDI X(8)` and `SYSIDI X(8)` — the CICS APPLID and
system identifier. `SignOnRequest` accepts these two as `applicationId` and `systemId`, on
the same terms.

Function-key chrome is likewise **accepted and ignored** wherever a map declares it, not
rejected: `AccountUpdateRequest` accepts `functionKeys`, `functionKey05` and `functionKey12`
(`FKEYSI`, `FKEY05I`, `FKEY12I` on `COACTUP`), and `CardUpdateRequest` accepts
`functionKeys` and `functionKeysContinued` (`FKEYSI X(21)` and `FKEYSCI X(18)` on
`COCRDUP`; `FKEYSI X(75)` on `COCRDSL`).

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
  between the read and the write. That is precisely why the read projects it and the write
  carries it back in its request body ([§11.2](#112-update-account), [§12.3](#123-update-card)).

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
* A request with no usable credential is answered `401`, and one whose token carries neither
  recognised authority is answered `403`. **Both carry a problem document, not an empty
  body.** The boundary shape is narrower than the controller shape and is documented at
  [§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance). Neither answer
  describes the resource.

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
| `firstKey` | Key at the top of the page just shown, for backward paging | Echoed from the previous response — **from a different member on each operation, and opaque on only one of the three**; see [§7.1.1](#711-the-cursor-is-not-the-same-object-on-all-three-operations) |
| `lastKey` | Key at the bottom of the page just shown, for forward paging | Echoed from the previous response — same caveat, see [§7.1.1](#711-the-cursor-is-not-the-same-object-on-all-three-operations) |
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

#### 7.1.1 The cursor is not the same object on all three operations

**The request parameters are uniform; the response members and the nature of the value are
not.** All three operations read the cursor from `firstKey` and `lastKey`, so a client that
knows the parameter names knows them everywhere. What differs is **which response member
carries the value to echo**, and **whether that value is opaque or is a record key the client
can read**. An earlier revision of this section described every cursor as sealed and
page-bound and told every client to echo `firstCursor`/`lastCursor`; that was true of the card
list only, and on the user list it named two members that **do not exist in the response at
all**, so a client following it would have echoed `null` (finding M-30).

| Operation | Echo into `firstKey` | Echo into `lastKey` | What the value is | Sealed | Bound to its page |
|---|---|---|---|---|:-:|
| [List cards](#121-list-cards) | `firstCursor` | `lastCursor` | An **opaque sealed handle**. Not a card number, not parseable, and not usable on any other operation | **Yes** | **Yes** |
| [List transactions](#131-list-transactions) | `firstCursor` | `lastCursor` | The **raw 16-digit transaction identifier** of that row. Readable, and identical to the `transactionId` the row publishes | No | No |
| [List users](#161-list-users) | **`firstUserId`** | **`lastUserId`** | The **raw 8-character user identifier** of that row | No | No |

`pageNumber` echoes into `page` on all three.

**Only the card list refuses a cursor presented against the wrong page.** Its cursors are
sealed under a kind that carries the page number — `card-list-cursor-page-<n>` — so page one's
cursor simply does not open as page two's and the request is refused with `400`. That check
exists because an accepted mismatch returns a page whose number and contents disagree, which
a client cannot detect. **The other two operations cannot make that check**, because their
cursors are plain record keys with nothing in them that names a page: presenting the wrong
one positions the browse at that key and answers `200` with a page that starts there. Reading
the card list's behaviour as the general rule is the specific mistake this table exists to
prevent.

**What is uniform across all three** is the *presence* requirement rather than the *contents*
check: naming a page past the first without the cursor that addresses it is refused with `400`
naming that cursor, on every one of the three, for the reason given in the bullets below.

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
  previous response published them**, taking the two cursor values from the members that
  operation actually publishes — `firstCursor`/`lastCursor` on the card and transaction lists,
  **`firstUserId`/`lastUserId` on the user list**, and `pageNumber` → `page` on all three. The
  per-operation mapping is [§7.1.1](#711-the-cursor-is-not-the-same-object-on-all-three-operations);
  it is not the same on all three, and assuming it is yields a null cursor on the user list. A
  first request sends `action` alone, or nothing at all.
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
* **On the card list only**, a cursor is **sealed to the page it was minted for**. Presenting
  page 1's cursor as page 2's is refused with `400`, not silently honoured — an accepted
  mismatch would return a page whose number and contents disagree. The transaction and user
  lists carry raw record keys with nothing in them that names a page, so they cannot make that
  check and do not attempt it: they position at the key given and answer `200`. See
  [§7.1.1](#711-the-cursor-is-not-the-same-object-on-all-three-operations).

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

**One media type, and never an empty body.** Error bodies are **RFC 9457 / RFC 7807 problem
details** served as `application/problem+json`, with no `charset` parameter. There is no path
through this application — not an unreadable `Content-Type`, not an unsatisfiable `Accept`, not
a wrong method, not a refused credential, not a request the servlet container refuses while it
is still parsing the request line — that answers with a different media type or with no body at
all.

**Two shapes, one a strict subset of the other.** There is not "one shape, used by every
operation and by every refusal decided before an operation is reached"; reading it that way
overstates the uniformity in the direction that breaks a client.
The document below is the **controller shape**, published by the `@ExceptionHandler` that owns
each operation, and it is the widest. Every refusal decided *before* a controller method is
selected publishes the narrower **pre-operation shape** of exactly six members —
`type`, `title`, `status`, `detail`, `errorCode`, `correlationId` — and no `instance`, `field`
or `failureKind`, because none of those facts exists yet at that point. That shape is set out in
[§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance), and
[§8.5](#85-refusals-decided-before-an-operation-is-reached) names the pre-operation boundaries
and what each one can and cannot carry.

Match on `errorCode` and read `correlationId`, and the difference does not affect you: those two
members, plus `type`, `title` and `status`, are present on **every** error body this API emits.

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
| `instance` | **controller-decided refusals only** | The request path. **Absent at the security boundary** — see [§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance) |
| `errorCode` | **yes** | Stable machine-readable outcome code — match on this, not on `title` or `detail` |
| `correlationId` | **yes** | The correlation identifier for this request, from the `X-Correlation-Id` header when the caller supplied one and generated otherwise. The literal `unavailable` appears when no identifier could be resolved |
| `field` | field-level failures | The **name** of the offending input. Never its value |
| `failureKind` | validation failures | Two-state discriminator distinguishing an input left **blank** from one supplied and **invalid** |
| `outcome` | conflict failures | Which concurrency outcome occurred |
| `changeAction` | account update | The source's own outcome vocabulary for the write attempt |
| `code` | **two account-resource refusals only** | An **additional** code carried alongside `errorCode`, on `AccountController` and on no other controller. Exactly two values, on exactly two statuses — see below |

**The `code` member is an account-resource extension, and it is not a substitute for
`errorCode`.** It appears on two refusals of the account operations and nowhere else in the API:

| Where | Status | `errorCode` | `code` |
|---|--:|---|---|
| A record the account operations required was absent — the cross-reference, the account master or the customer master | `404` | `CARDDEMO-RECORD-NOT-FOUND` | `ACCOUNT_RECORD_NOT_FOUND` |
| A referential, check or not-null constraint refused an account or customer write | `409` | `CARDDEMO-CONSTRAINT-REFUSED` | `ACCOUNT_WRITE_REFUSED` |

Both bodies carry `errorCode` as well, with its usual value, so **a client matching on
`errorCode` needs no special case for these two and should continue to match on it**: `code` is
narrower vocabulary for the account resource, useful for distinguishing *which* of the three
absent records or *which* class of write refusal was met without parsing `detail`. It was
previously emitted but undocumented, which is the worse of the two failure modes — an undeclared
member is one a client cannot rely on and cannot safely ignore either, since a strict
deserialiser may refuse it. It is documented rather than removed because the two values are
already in the responses and removing a member from a live surface is the breaking change
(finding M-20).

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

Match on `errorCode`. These **twenty** values are the complete set, and
`CARDDEMO-MALFORMED-REQUEST-BODY` is the one most easily missed from a list of them — a code a
caller can actually receive, so omitting it leaves an integrator with an unmatchable outcome.

The set is exhaustive by construction, and it is derivable rather than asserted:

```shell
grep -rhoE '"CARDDEMO-[A-Z-]+"' src/main/java | sort -u
```

That reports **21** literals. Exactly one of them, `CARDDEMO-PIPELINE`, is **not** an error
code — it is `DEFAULT_JOB_NAME` in `com.cardemo.batch.jobs.BatchPipelineOrchestrator`, a
Spring Batch job name that never reaches an HTTP body. Twenty-one literals minus that one is
the twenty rows below, each appearing in exactly one group.

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
| `CARDDEMO-UPDATE-CONFLICT` | `409`, `412`, `423`, `428`, `500` | A concurrency precondition failed. Which status depends on the outcome **and on which operation answered** — see [§11.2.6](#1126-outcome-markers-and-status-mapping) for the account mapping and [§12.3](#123-update-card) for the card mapping, which differ |
| `CARDDEMO-CONSTRAINT-REFUSED` | `409` | A referential, check or not-null constraint refused the write — see [§8.2.1](#821-a-vsam-write-had-one-failure-mode-a-table-has-four) |
| `CARDDEMO-RESOURCE-UNAVAILABLE` | `503` | A required store or queue could not be opened |
| `CARDDEMO-IO-FAILURE` | `502`, **and `503` on sign-on alone** | The store was reachable but the read or write failed. [Sign on](#91-sign-on) is the one operation that answers `503` here — see the note below |
| `CARDDEMO-PROCESSING-ABEND` | `500` | The operation ended the way the legacy abend routine ended the task |
| `CARDDEMO-INTERNAL-FAILURE` | `500`, `501` | A typed failure no other mapper on the controller claims, or a container-level failure at or above `500` |

**`CARDDEMO-CONSTRAINT-REFUSED` is `409` and only `409`.** An earlier revision of that row
published `409` / `422`. **No executable path in this application returns `422`** — the status
appears in no controller, no handler and no test, which is reproducible with
`grep -rn 'UNPROCESSABLE_ENTITY' src/main/java` returning nothing. Publishing a status the
code cannot produce is worse than publishing none, because a client may branch on it and that
branch is dead on arrival. The row is corrected rather than the code changed: a constraint
refusal *is* a conflict with the stored state, which is what `409` means, and inventing a
`422` arm to match the documentation would be a behaviour change made to protect a sentence
(finding M-31).

**Sign on answers `503` where every other operation answers `502`, and that is deliberate.**
`AuthController` maps **both** `FileUnavailableException` *and* `FileAccessException` to
`503 Service Unavailable`; the other six controllers that declare the handler map
`FileAccessException` to `502 Bad Gateway`. The reason is that sign on is the **only
unauthenticated operation on the surface**: distinguishing "the credential store could not be
opened" from "the credential store was read and the read failed" tells an anonymous caller
something about the deployment's internal state, so the two are deliberately collapsed into
one retryable, topology-free answer. Everywhere else the caller is already authenticated and
the finer distinction is useful rather than disclosing. This is documented rather than
"aligned to the common contract", because aligning it would *add* that disclosure to the one
operation that must not carry it (finding M-32).

**Group B — the request never got as far as an operation's own logic.** The body was
rejected, or the caller was not entitled to the operation at all. None of these carries
`field` or `failureKind`, because no field was ever bound.

**Four of these five rows do not carry `instance`.** Only
`CARDDEMO-REQUEST-BODY-UNREADABLE` is raised by a controller's own `@ExceptionHandler` and
therefore carries it; the other four are written by the security filter chain, which emits the
six-member shape of
[§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance) and no `instance` at
all. A client that dereferences `instance` on a `401` or a `403` fails on a missing member.

| `errorCode` | Typical status | Raised when |
|---|--:|---|
| `CARDDEMO-AUTHENTICATION-REQUIRED` | `401` | No usable bearer token accompanied a request to a protected operation |
| `CARDDEMO-AUTHORIZATION-DENIED` | `403` | A token was valid but its role does not admit the path and method — **including every path this API does not publish**, which is denied rather than reported as absent |
| `CARDDEMO-REQUEST-BODY-UNREADABLE` | `400` | The body could not be deserialised: malformed JSON, or a value past one of the JSON parser bounds (nesting depth, string, number, name and document length) that are applied before deserialisation |
| `CARDDEMO-REQUEST-BODY-TOO-LARGE` | `413` | The body declared or streamed more than 16 384 bytes, refused **before authorization and MVC binding** — the bound is applied after the bearer filter has populated the security context, so an oversized body is refused without ever being bound to a parameter |
| `CARDDEMO-MALFORMED-REQUEST-BODY` | `400` | The body could not be **read from the connection** at all — a truncated or aborted upload, a broken chunked framing, a client that disconnected mid-body. Distinct from `CARDDEMO-REQUEST-BODY-UNREADABLE`, which means the bytes arrived and would not parse. Raised by the body-screening filter (`SecurityConfig`), so it carries the boundary shape of [§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance) and never quotes the fragment it stopped on |

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

#### 8.1.1 The security boundary shape: six members and no instance

Four of the twenty codes — `CARDDEMO-AUTHENTICATION-REQUIRED`,
`CARDDEMO-AUTHORIZATION-DENIED`, `CARDDEMO-REQUEST-BODY-TOO-LARGE` and
`CARDDEMO-MALFORMED-REQUEST-BODY`, plus `CARDDEMO-UNSUPPORTED-MEDIA-TYPE` when it is the
security chain rather than the mapping layer that screens the header — are written inside the
Spring Security filter chain, by
`com.cardemo.config.SecurityConfig`'s private `writeProblemDetail`. That runs **before** any
`@ExceptionHandler` and before a handler method has been selected, so it cannot reach the
members a controller-owned refusal can. It publishes exactly six, and never more:

```json
{
  "type": "about:blank",
  "title": "Authentication required",
  "status": 401,
  "detail": "The request did not carry a usable bearer token. Obtain one from the sign-on operation and present it in the Authorization header.",
  "errorCode": "CARDDEMO-AUTHENTICATION-REQUIRED",
  "correlationId": "8f14e45f-ea0c-4b9d-9f2a-6c1b7d3e5a90"
}
```

| Member | Present at this boundary | Note |
|---|---|---|
| `type` | yes | Always the literal `about:blank` |
| `title` | yes | One of five fixed titles, listed below |
| `status` | yes | Read back off the response rather than assumed, so the body and the status line cannot disagree |
| `detail` | yes | A fixed sentence per outcome. It never quotes the request — no header value, no body fragment, no offending method or character |
| `errorCode` | yes | The value to match on |
| `correlationId` | yes | Always present. The literal `unavailable` is published when no identifier is in scope |
| `instance` | **no** | No handler was selected, so there is no mapping-relative request path to report |
| `field` | **no** | No argument was bound |
| `failureKind` | **no** | No validation ran |
| `outcome`, `changeAction` | **no** | Concurrency and write-outcome vocabulary belongs to the account and card operations only |

**Neither a `401` nor a `403` carries an empty body.** Both statuses carry a full problem
document of the shape above, and the distinction matters in the direction that breaks a client:
an integrator who codes for an empty body has no `errorCode` to branch on and discards the
`correlationId` that is the only join key back to the server-side log record.

**The five fixed titles and details at this boundary**, each a compile-time constant:

| Status | `title` | `errorCode` | Reached when |
|--:|---|---|---|
| `401` | `Authentication required` | `CARDDEMO-AUTHENTICATION-REQUIRED` | No usable bearer token on a protected operation |
| `403` | `Authorization denied` | `CARDDEMO-AUTHORIZATION-DENIED` | A valid token whose authority does not admit the path and method |
| `413` | `Request body too large` | `CARDDEMO-REQUEST-BODY-TOO-LARGE` | A body declared or streamed past the 16 384-byte bound |
| `400` | `Request body could not be read` | `CARDDEMO-MALFORMED-REQUEST-BODY` | A length-less, transfer-encoded body whose framing could not be decoded |
| `415` | `Unsupported media type` | `CARDDEMO-UNSUPPORTED-MEDIA-TYPE` | A `Content-Type` the seventeen operations cannot read, screened in the chain |

The `401` and `403` rows each have **two** detail sentences rather than one, because the
metrics scrape endpoint authenticates with HTTP Basic rather than a bearer token and says so:
its `401` explains that a bearer token is not accepted there, and its `403` that the presented
credentials lack the scrape authority. Title and `errorCode` are identical in both cases, so a
client matching on `errorCode` needs no special case.

**Media type.** `application/problem+json`, with **no `charset` parameter**. That is
deliberate and it is a contract, not an accident: it is byte-for-byte what Spring emits for a
controller's `ResponseEntity<ProblemDetail>`, and if the two boundaries disagreed on the header
then their bodies would not be interchangeable to a client that negotiates on it. The bytes are
UTF-8, which JSON is by specification.

**The other pre-operation boundaries agree.** The four boundaries of
[§8.5](#85-refusals-decided-before-an-operation-is-reached) are rendered by a second
constant-only assembler, `WebConfig`'s private `renderProblemEnvelope`, which emits the same
six members in the same order for the same reason. So there are exactly **two** shapes on this
API: the controller shape of [§8](#8-error-response-envelope), which adds `instance` and the
situational members, and this six-member pre-operation shape. Both are
`application/problem+json`; neither is ever empty.

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
| Snapshot comparison detected a change | `ConcurrentUpdateException` | `409` / `412` / `423` / `428` / `500` | Which of the five depends on the outcome and on the answering operation — [§11.2.6](#1126-outcome-markers-and-status-mapping) |
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

It cannot cover a refusal decided **before** a controller method is selected. **Five** such
boundaries exist. The fifth is the Spring Security filter chain, and because it is where every
credential and body-size refusal is decided it has its own sub-section: see
[§8.1.1](#811-the-security-boundary-shape-six-members-and-no-instance). The four framework and
protocol boundaries are tabulated here.

All five render the same six-member pre-operation envelope — `type`, `title`, `status`,
`detail`, `errorCode`, `correlationId` — at `application/problem+json`. What differs between
them is only which `correlationId` they can reach, and that difference is contractual rather
than accidental, so it is stated per row.

| # | Boundary | Refuses | Publishes | Cannot publish |
|--:|---|---|---|---|
| 1 | **`Content-Type` screen** — a security-chain filter, so it runs before authorization, before MVC binding and before any handler is mapped, and before the body is read | A declared `Content-Type` that is not a concrete type and subtype: `*/*`, `application/*`, `application/*+json`, `*/json` | `415`, `CARDDEMO-UNSUPPORTED-MEDIA-TYPE`, an `Accept` response header, and the request's own `correlationId` | `instance`, `field`, `failureKind` — no field was bound |
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

The six chrome fields of [§4.2](#42-presentation-chrome-is-accepted-on-request-and-absent-from-every-response)
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
| Store reachable but unreadable | **`503`** | `CARDDEMO-IO-FAILURE` | A fixed detail, deliberately the same shape as the row above — and, on this operation alone, deliberately the **same status** too |
| Abend or any unclaimed typed failure | `500` | `CARDDEMO-PROCESSING-ABEND` / `CARDDEMO-INTERNAL-FAILURE` | A fixed detail plus the `correlationId` |

**The two store-failure rows answer the same status on this operation, and only on this one.** An
earlier revision published the unreadable-store row as `502`, which is what the other six
controllers answer but not what this one does: `AuthController` maps **both**
`FileUnavailableException` and `FileAccessException` to `503`. The indistinguishability is the
point, and it extends to the status rather than stopping at the detail text — sign on is the
only unauthenticated operation, so telling an anonymous caller *which* way the credential store
failed would disclose internal state to someone who has not yet proved anything. See the note
under [§8.1](#81-stable-error-codes) (finding M-32).

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
| No authenticated principal | `401` | The **six-member problem document** of [§8.5](#85-refusals-decided-before-an-operation-is-reached), `CARDDEMO-AUTHENTICATION-REQUIRED`. Emitted by the security chain; this operation's own empty-body branch is unreachable — see the note below |
| Token carries neither recognised authority, so no user class could be resolved | `403` | The same problem document, `CARDDEMO-AUTHORIZATION-DENIED`. Also emitted by the chain, for the same reason |
| Abend or unclaimed typed failure | `500` | `CARDDEMO-PROCESSING-ABEND` / `CARDDEMO-INTERNAL-FAILURE` |

**Why those two rows name a problem body where the controller writes none — and why that is
the honest reading.** Both handlers do contain a branch that returns a bodyless `401` or `403`,
and an earlier revision of this table published `Empty` because it described *that code*.
**Neither branch is reachable.** `GET /api/menu/main` is guarded by
`hasAnyAuthority(ROLE_ADMIN, ROLE_USER)` and `GET /api/menu/admin` by
`hasAuthority(ROLE_ADMIN)`, so a request with no credential, or with a token carrying neither
authority, is refused by the `AuthorizationFilter` **before the handler is entered at all** —
and the chain's entry point and access-denied handler both write the full document. The
controller branches are retained as defence in depth, so that the operation still refuses
rather than proceeds if a future matcher change were to admit an unauthenticated request; they
are **internal** paths, not published behaviour. What a client observes is the problem
document, which is what this table now publishes (finding M-18). The same correction applies to
every operation table in this document that named an empty body on `401` or `403`.

**There is no `400` on either menu operation, and the three gates above cannot produce one.**
An "option validation refused → `400`" row would be unreachable: no request can arrive
carrying an option. Both operations take **no input at all** — no path variable, no query
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

**Role. ADMIN only.** A standard user receives `403` carrying the
[§8.5](#85-refusals-decided-before-an-operation-is-reached) problem document with
`CARDDEMO-AUTHORIZATION-DENIED`, not an empty body: the refusal comes from
`hasAuthority(ROLE_ADMIN)` in the chain, ahead of the handler's own user-class gate — the four options
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
three-dataset lookup chain the source follows. It is also **the only way to obtain the sealed
snapshot** that [§11.2](#112-update-account) requires as its update precondition.

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

**Response — `200 OK`.** `AccountViewResponse` declares **exactly 23 record components**,
and the table below is that component list — **not** the map's field list, and the difference is a
privacy contract rather than a counting convention. Publishing "thirty-five business fields, all
as declared in `app/cpy-bms/COACTVW.CPY`" would tell an integrator to expect a social security
number, a date of birth, three name parts, two telephone numbers, a government identifier and an
electronic funds account identifier in a response body that carries none of them.

| JSON property | Map field | PIC | Meaning |
|---|---|---|---|
| `accountId` | `ACCTSIDI` | `PIC 99999999999` | Account identifier |
| `accountStatus` | `ACSTTUSI` | `X(1)` | Account status |
| `openDate` | `ADTOPENI` | `X(10)` | Open date |
| `expiryDate` | `AEXPDTI` | `X(10)` | Expiry date |
| `reissueDate` | `AREISDTI` | `X(10)` | Reissue date |
| `creditLimit` | `ACRDLIMI` | `X(15)` | Credit limit |
| `cashCreditLimit` | `ACSHLIMI` | `X(15)` | Cash credit limit |
| `currentBalance` | `ACURBALI` | `X(15)` | Current balance |
| `currentCycleCredit` | `ACRCYCRI` | `X(15)` | Current cycle credit |
| `currentCycleDebit` | `ACRCYDBI` | `X(15)` | Current cycle debit |
| `accountGroupId` | `AADDGRPI` | `X(10)` | Account group identifier |
| `customerId` | `ACSTNUMI` | `X(9)` | Customer identifier |
| `customerFicoScore` | `ACSTFCOI` | `X(3)` | FICO credit score |
| `addressLine1` | `ACSADL1I` | `X(50)` | Address line 1 |
| `addressLine2` | `ACSADL2I` | `X(50)` | Address line 2 |
| `addressCity` | `ACSCITYI` | `X(50)` | City (address line 3) |
| `addressStateCode` | `ACSSTTEI` | `X(2)` | State code |
| `addressZip` | `ACSZIPCI` | `X(5)` | Postal code |
| `addressCountryCode` | `ACSCTRYI` | `X(3)` | Country code |
| `primaryCardHolderIndicator` | `ACSPFLGI` | `X(1)` | Primary card-holder indicator |
| `informationMessage` | `INFOMSGI` | `X(45)` | Informational message |
| `errorMessage` | `ERRMSGI` | `X(78)` | Error message |
| `snapshot` | — | `String` | The **as-displayed snapshot**, issued as a single **opaque sealed string**. Required by [§11.2](#112-update-account): copy this string into the update's request body **verbatim**. No `ETag` is issued and no `If-Match` is read |

That is 23 rows. The five money fields (`creditLimit`, `cashCreditLimit`, `currentBalance`,
`currentCycleCredit`, `currentCycleDebit`) are `BigDecimal`-backed decimals per
[§6.2](#62-monetary-representation).

The last of those rows carries the snapshot, and it warrants a note of its own:

| Member | Notes |
|---|---|
| `snapshot` | The **as-displayed snapshot**, `ACUP-OLD-DETAILS` of [`app/cbl/COACTUPC.cbl:L669`] as this read projected it, **sealed into one opaque string**. Required by [§11.2](#112-update-account): copy it into the update's request body **verbatim**. It is **not** a readable object, it is not parseable, and nothing in it is a member a client names or edits. No `ETag` is issued and no `If-Match` is read; there is no server-side state between the two requests |

**This member replaced a readable `oldDetails` object, and the change is visible to every
client.** An earlier revision of this operation published the snapshot as a JSON object whose
members the caller could read and re-send. Two consequences made that untenable, and both are
closed by sealing it (findings B-1 and B-2):

* **It disclosed the nine withheld components.** They were withheld as *display* members while
  the very same response carried them inside `oldDetails` — so the social security number, the
  date of birth, both telephone numbers, the government-issued identifier and the funds-account
  identifier were on the wire regardless. The withholding was nominal. It is now real: the
  sealed string is ciphertext, so **no personal data leaves the server on this operation at
  all**.
* **It made the comparison's own operand caller-editable.** The write compares the stored record
  against this snapshot to decide whether anything changed since the screen was drawn. A client
  that could author the snapshot could assert whatever "as displayed" state it liked and defeat
  the check the comparison exists to perform.

The seal binds the value to **this operation, this account, this authenticated principal and a
short expiry**, so a snapshot that opens on the write was demonstrably issued by this server,
for this account, to this caller, recently. What a client does with it is unchanged and is
simpler than before: **echo the string, do not read it**.

#### Withheld fields — on the map, deliberately not in the response

`AccountViewResponse` publishes the list itself, as `WITHHELD_COMPONENTS`, so this is an
asserted contract rather than an observation about the current code. **Nine members** of
`app/cpy-bms/COACTVW.CPY` are read by the service, sealed inside the `snapshot` string, and
never serialised in readable form anywhere in the response:

| Map field | PIC | Withheld component | Why |
|---|---|---|---|
| `ACSTSSNI` | `X(12)` | `customerSsn` | Social security number |
| `ACSTDOBI` | `X(10)` | `customerDateOfBirth` | Date of birth |
| `ACSFNAMI` | `X(25)` | `customerFirstName` | Name part |
| `ACSMNAMI` | `X(25)` | `customerMiddleName` | Name part |
| `ACSLNAMI` | `X(25)` | `customerLastName` | Name part |
| `ACSPHN1I` | `X(13)` | `phoneNumber1` | Telephone — **one whole field on this map** |
| `ACSPHN2I` | `X(13)` | `phoneNumber2` | Telephone — **one whole field on this map** |
| `ACSGOVTI` | `X(20)` | `governmentIssuedId` | Government-issued identifier |
| `ACSEFTCI` | `X(10)` | `eftAccountId` | Electronic funds transfer account identifier |

This is why the update operation needs the as-displayed values of all nine and why they travel
**sealed inside the `snapshot` string** rather than as display components
([§11.2.3](#1123-how-the-snapshot-travels)): one carrier reproduces the source's
field-by-field comparison without putting the nine on the wire at all. A caller that needs a
customer's personal data does not get it from this endpoint in any member, readable or
otherwise, and there is no query parameter, header or role that turns these nine on.

**The word "withheld" now means what it says.** While the snapshot was a readable object this
list described where the nine were *not* published rather than whether they were published, and
they were — inside `oldDetails`, in the same response. Sealing the snapshot is what turned this
section from a statement about member placement into a statement about disclosure.

Three further response types publish their own withheld list on the same principle —
`UserCreateResponse`, `UserListResponse` and `UserUpdateResponse` — so the pattern is
consistent across the tree: where a screen field is deliberately not serialised, the DTO
says so in code rather than leaving the omission to be inferred.

**Side effects.** None on business data. The operation performs one additional read to project
the snapshot group; that read is not a write.

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
| No authenticated principal | `401` | `CARDDEMO-AUTHENTICATION-REQUIRED` — the [§8.5](#85-refusals-decided-before-an-operation-is-reached) problem document from the security chain, **not** an empty body; the handler's own bodyless branch is unreachable defence in depth |
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
Content-Type: application/json

{ "accountId": "...", "...": "...",
  "snapshot": "<the snapshot string from GET /api/accounts/{accountId}, verbatim>",
  "newDetails": { "customerId": "..." } }
```

**Role.** USER or ADMIN.

**This is a full replacement of the detail set, not a partial update.** There is no `PATCH`
semantic and no partial-update mode: the source moves every edited field into the update
image before rewriting, so the body carries the complete set. Omitting a field does not mean
"leave it alone".

**Request.** Three parts — a query parameter, a header and a body — set out in
[§11.2.1](#1121-request-in-three-parts).

**Validation order.** Confirmation gate first, then the sealed-snapshot refusals of
[§11.2.3](#1123-how-the-snapshot-travels) — absent is `428`, present but unopenable is `412` —
then field validation, then the seven-step write sequence of
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

**(b) Headers.** None beyond `Authorization` and `Content-Type`. This operation reads no
request header of its own: the as-displayed snapshot is a body member, described below.

| Body member | Required | Value |
|---|:--:|---|
| `snapshot` | **yes** | The **opaque `snapshot` string** from [`GET /api/accounts/{accountId}`](#111-view-account), copied **verbatim**. Absent, empty or blank → **`428`**; present but it does not open — tampered with, issued for another account, issued to another principal, or expired → **`412`**; opens but no longer matches the stored record → **`412`**. A body member named `oldDetails` is **refused with `400`**, because unknown members are not ignored ([§8.5](#85-refusals-decided-before-an-operation-is-reached)) |

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
fields; sending the opaque `snapshot` string is **required**
([§11.2.3](#1123-how-the-snapshot-travels)).

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

#### 11.2.3 How the snapshot travels

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

> **The snapshot is projected by the server and echoed back by the client, in the request
> body, as the opaque string `snapshot`. It is not assembled by the client, it is not
> readable by the client, and it is not carried in a header.**
>
> `GET /api/accounts/{accountId}` returns it as the `snapshot` string.
> `PUT /api/accounts` reads it from the body's `snapshot` **and from nowhere else** — never
> from the row it is about to write, because that would make the comparison tautologically
> true and the guard worthless.
>
> **A request with no `snapshot`, or an empty or blank one, is refused with `428`** — never
> treated as "nothing changed". **A `snapshot` that does not open is refused with `412`.**
> Skipping the comparison would forfeit the guarantee the source provides, silently, and only
> under concurrency.

**Echo the string; you cannot rebuild it, and that is deliberate.** The comparison is
representation-sensitive in a way a caller could not reconstruct anyway — most sharply for the
date of birth, whose snapshot form carries **no separators** while the live record is
dash-separated (see §11.2.4(c)) — but the stronger reason is that a client-authored snapshot
would defeat the check. The comparison exists to detect that the stored record no longer
matches **what this caller was shown**; if the caller supplies both sides of that comparison,
it can assert agreement at will. Sealing the value removes the possibility rather than
documenting against it.

**What the seal binds, and what each binding buys.** The string is authenticated encryption
over the projected group, tied to four things:

| Bound to | What it prevents |
|---|---|
| **This operation** | A snapshot minted for one purpose being replayed into another that also takes one |
| **This account** | A snapshot read for account A being presented on a write to account B |
| **This authenticated principal** | One caller using a snapshot issued to another, even inside its validity window |
| **A short expiry** | An indefinitely replayable "as displayed" assertion about a screen drawn long ago |

So a snapshot that opens on the write was demonstrably issued **by this server, for this
account, to this caller, recently**. Anything else fails to open and is refused with `412`
rather than being partially trusted.

**What it costs, stated rather than hidden — and it now costs less than it did.** An earlier
revision of this section disclosed that the readable group carried the date of birth, the
social security number, the government-issued identifier, both telephone numbers and the
electronic funds account identifier, because [`:L4109-L4192`] compares all twenty-nine of its
values — which meant personal data on both the response and the request, honestly disclosed but
present. **Sealing the group removed it from the wire in readable form entirely**, so the nine
withheld components are now withheld in fact and not merely in placement. What remains: the
ciphertext is still a value worth protecting for its short life, transport security remains the
deployment's concern, the logging configuration masks the social security number and credentials
in every log event, and encryption at rest for personally identifiable data is recorded as
deferred hardening rather than claimed.

#### 11.2.4 Snapshot comparison semantics

These are the semantics the group encodes. They are published for three reasons: they explain
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
> a snapshot, and it is why the snapshot is sealed and opaque rather than caller-constructed.**

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
conflict on every single request**, making the operation permanently unusable. The sealed
`snapshot` the view operation returns holds it compact, which is why echoing that string —
rather than assembling anything — is not merely convenient but the only available route: the
representation is settled inside the seal, where no client can get it wrong.

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

The statuses below are the ones `AccountController.statusFor` actually returns
[`AccountController.java`], read off the `switch` rather than inferred. **Two rows are easy
to get wrong by analogy and are worth checking against the `switch`**: the customer-lock outcome
answers `409`, not `423`, and the locked-but-update-failed outcome answers `500`, not `409`.
Either mistake sends a client's retry logic down the wrong branch — a `500` in particular is not
a conflict to be resolved by re-reading and resubmitting.

| Legacy condition name | Outcome | Exact literal | `changeAction` | Status | `errorCode` |
|---|---|---|:-:|--:|---|
| `COULD-NOT-LOCK-ACCT-FOR-UPDATE` | `COULD_NOT_LOCK_ACCOUNT` | `Could not lock account record for update` | `L` | `423` | `CARDDEMO-UPDATE-CONFLICT` |
| `COULD-NOT-LOCK-CUST-FOR-UPDATE` | `COULD_NOT_LOCK_CUSTOMER` | `Could not lock customer record for update` | `C` | `409` | `CARDDEMO-UPDATE-CONFLICT` |
| `DATA-WAS-CHANGED-BEFORE-UPDATE` | `DATA_CHANGED_BEFORE_UPDATE` | `Record changed by some one else. Please review` | `S` | `412` | `CARDDEMO-UPDATE-CONFLICT` |
| `LOCKED-BUT-UPDATE-FAILED` | `LOCKED_BUT_UPDATE_FAILED` | `Update of record failed` | `F` | `500` | `CARDDEMO-UPDATE-CONFLICT` |
| *(no legacy flag — the confirmation gate)* | `CHANGES_NOT_CONFIRMED` | *(empty — the screen carried `Changes validated.Press F5 to save` instead)* | `N` | `428` | `CARDDEMO-UPDATE-CONFLICT` |
| *(outcome absent)* | *(none — message-only construction)* | *(the constructor's own message)* | *(omitted)* | `409` | `CARDDEMO-UPDATE-CONFLICT` |

Two further conditions are **not** concurrency outcomes and are answered by different
handlers, so they are listed separately rather than mixed into the table above:

| Legacy condition name | Exact literal | Meaning | Status | `errorCode` |
|---|---|---|--:|---|
| `DID-NOT-FIND-ACCT-IN-CARDXREF` | `Did not find this account in cards database` | Cross-reference lookup found nothing | `404` | `CARDDEMO-RECORD-NOT-FOUND` |
| `XREF-READ-ERROR` | `Error reading Card Data File` | Cross-reference read failed | `502` | `CARDDEMO-IO-FAILURE` |

**Read `outcome`, not the status, to identify the condition.** Every conflict body carries an
`outcome` member naming one of the five constants above and a `changeAction` member carrying
the one-character `ACUP-CHANGE-ACTION` marker [`app/cbl/COACTUPC.cbl:L654-L668`] the failed
turn would have left on the screen. Both members are omitted only when the exception was built
without an outcome, which is the `409` last row. Because two outcomes now share `409` and one
answers `500`, the status alone no longer identifies the condition — the `outcome` member
does, and it is why it is published.

**`CHANGES_NOT_CONFIRMED` carries an empty literal**, not the `Changes validated.Press F5 to
save` text it is easily attributed. The enum constant's message is
the empty string, on the `CHANGES_NOT_CONFIRMED` constant of
[`ConcurrentUpdateException.java`]; the `Press F5` caption is what the
legacy screen painted on the *successful* validation turn, which in REST terms is the request
the client is being asked to repeat with `confirm` asserted.

**Spelling and spacing are preserved exactly**, including `some one` as two words in the
data-changed literal [`app/cbl/COACTUPC.cbl:L521-L522`]. A client matching on that text must
match it as written.

Locators: [`:L513-L514`], [`:L517-L518`], [`:L519-L520`], [`:L521-L522`],
[`:L523-L524`], [`:L525-L526`].

Field validation failures answer `400` with the offending field's own literal — for example
`'Credit Limit must be supplied'` [`:L505-L506`] or `'Credit Limit is not valid'`
[`:L507-L508`]. The general table in [§8.2](#82-global-failure-and-status-mapping) covers
everything else.

##### Which input produces which of those statuses

The table above maps *outcomes* to statuses. Two different inputs reach `428` and two reach
`412`, so the mapping from what a client sent to what it receives is set out separately here, in
**evaluation order** — the first match wins and nothing after it runs:

| # | What the request carried | Status | `outcome` | `detail` |
|--:|---|--:|---|---|
| 1 | `confirm` absent, or `confirm=false` | `428` | `CHANGES_NOT_CONFIRMED` | `Changes validated.Press F5 to save` |
| 2 | `confirm` present but empty | `400` | — | Names `confirm`, `failureKind` blank |
| 3 | `confirm` present and neither exact token | `400` | — | Names `confirm`, `failureKind` invalid |
| 4 | `confirm=true`, `snapshot` absent, empty or blank | `428` | `CHANGES_NOT_CONFIRMED` | `Changes validated.Press F5 to save` — **the same text as row 1**, see below |
| 5 | `confirm=true`, `snapshot` present but does not open — altered, issued for another account, issued to another principal, or expired | `412` | `DATA_CHANGED_BEFORE_UPDATE` | `Record changed by some one else. Please review` — **the same text as row 6**, see below |
| 6 | `snapshot` opens, and the field-by-field comparison finds the record changed | `412` | `DATA_CHANGED_BEFORE_UPDATE` | `Record changed by some one else. Please review` |

**An earlier revision of this section collapsed rows 4, 5 and 6 into a single `400` on
`oldDetails`.** That was wrong in the direction that strands a client: `400` says *fix your
request*, whereas rows 5 and 6 say *read the record again* and row 4 says *obtain a snapshot
first*. Three different remedies were published as one, under a status that suggested none of
them (finding M-19). The remedies genuinely differ, and the **status** is what separates them:
an absent snapshot is `428`, an unopenable one is `412`, and neither is quietly treated as
"nothing changed".

> **Branch on the status and the `outcome`, not on `detail` — this operation has two literals,
> not six.** `AccountController` composes `detail` from the **outcome** rather than from the
> failure that raised it: `CHANGES_NOT_CONFIRMED` always renders
> `Changes validated.Press F5 to save`, which is `88 PROMPT-FOR-CONFIRMATION` at
> [`app/cbl/COACTUPC.cbl:L472-L473`] reproduced exactly, and `DATA_CHANGED_BEFORE_UPDATE` always
> renders its `WS-RETURN-MSG` literal from [`:L479`]. Rows 1 and 4 are therefore
> **indistinguishable from the response body alone**, and so are rows 5 and 6. The client already
> knows which of each pair applies, because it knows whether it sent `confirm` and whether it sent
> `snapshot`.

**This is where card update genuinely differs, and the difference is in the controller rather
than in the guard.** `CardController` relays the raised exception's own message as `detail`, so on
[`PUT /api/cards`](#123-update-card) the two snapshot conditions are self-describing on the wire:
an absent snapshot answers `428` with `A sealed snapshot is required for this request. Read the
record first and send back the snapshot value that read returned.`, and one that will not open
answers `412` with `The sealed snapshot could not be verified for this request. Read the record
again and send back the snapshot value that read returns.` The same two sentences exist behind
the account operation — they are the messages `SnapshotTokenService` raises — but they are
**internal there**, reaching the log and not the response. Neither controller is wrong: the
account resource publishes one literal per outcome so that its six outcome literals have exactly
one definition each, and the card resource publishes the raised message so that its extra
hollow-snapshot arm can say what it means. A client integrating both must read `detail` as
resource-specific.

**One further difference, in the guard this time rather than in the wording.** Every one of the
six rows above has a counterpart on [`PUT /api/cards`](#123-update-card) at the same status. What
card update has in addition is a **seventh** case the account operation does not: a snapshot that
**opens correctly but carries nothing** — which is what the server seals when the read it came from
found no card. Card update answers that with `428` and `Please enter Account and Card Number`,
because the legacy program is in `CCUP-DETAILS-NOT-FETCHED` at that point and **cannot reach its
write at all**. Account update has no such state, so a hollow-but-authentic snapshot simply enters
the comparison and is answered as row 6. Both are faithful to their own program; neither
generalises to the other.

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
| `accountFilter` | `ACCTSIDI` | `X(11)` | no | Filter by account. **Absent, blank or all-zero means *no filter*** and is accepted silently; a value that is not all digits across the eleven-byte field is refused; a longer value is **truncated to eleven** and then judged. See the note below |
| `cardFilter` | `CARDSIDI` | `X(16)` | no | Filter by card number. **Same three-state rule**: absent, blank or all-zero means *no filter*; not all digits across the sixteen-byte field is refused; longer is truncated to sixteen |
| `page` | `PAGENOI` | `X(3)` | no | Digits only. Bounded by the three-character screen field |
| `action`, `firstKey`, `lastKey`, `nextPageAvailable` | — | — | no | Paging control, see [§7.1](#71-paging-parameters) |

**Three filter states, not two.** A filter that is **absent**, one that is **blank** and one
that is **supplied and wrong** are three distinguishable conditions, and the source keeps
them apart — which is why the error envelope carries `failureKind` to separate a blank input
from an invalid one. Both filters absent is legitimate: it lists all cards.

**And "blank" includes all-zero, which is the part that surprises.** An earlier revision of
both rows above said the filter is "refused when supplied and not an eleven-digit non-zero
number", which reads as though `00000000000` were an error. It is not. `COCRDLIC` tests
`LOW-VALUES`, `SPACES` **or `ZEROS`** in one condition and, on any of the three, sets the
filter-blank flag, moves zeros to the browse key and **exits with no error and no message**
[`app/cbl/COCRDLIC.cbl:L1007-L1013`] — the card filter does the same at [`:L1041-L1047`]. So an
all-zero filter is not a refused filter, it is **no filter**, and refusing it would remove the
unfiltered browse this operation exists to offer. This is the same statement [§7](#7-pagination-contract)
already made for the filter-versus-record-key asymmetry; these two rows contradicted it, and the
rows were the ones that were wrong (finding M-21).

**What *is* refused is a value the fixed-width field cannot hold as digits.** The test is
`IS NOT NUMERIC` over the whole `X(11)` or `X(16)` field [`:L1017-L1025`], [`:L1051-L1059`], so:

* A value **shorter** than the field is space-padded on the right by the fixed-width move, and
  the padding makes the field non-numeric → refused with
  `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER` or
  `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`, byte for byte including the missing
  space after each comma.
* A value **longer** than the field is **truncated** to the field width and then judged, rather
  than being refused for its length. That is the fixed-width move's own behaviour, preserved.
* On refusal the source also protects the row-selection fields
  [`FLG-PROTECT-SELECT-ROWS-YES`], so the refused page carries no selectable rows.

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
log line would be incoherent. **The detail operation does not publish it in full either**:
`CardResponse` declares `maskedCardNumber` and no raw-number component at all, so there is no endpoint,
parameter, header or role anywhere in this API that returns a primary account number in
readable form. The masking is applied identically on the list row and on the detail
response; what the detail operation adds is not the number but the sealed `snapshot` string
that the update operation requires.

**What replaces it.** Masking on its own would break the one thing the column existed for:
without the number, none of the values a row discloses is accepted by any other card
operation, so a client reading only this API could reach a row and then go no further. Each
row therefore carries `cardKey` — the account number and the card number sealed together by
an authenticated construction, under its own purpose label — and both
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
* It is **not a precondition**. A handle says which card; the sealed `snapshot`
  ([§12.2](#122-view-card)) says what the caller was shown. An update needs both. The two are
  sealed under **different** purpose labels for exactly that reason, so neither can stand in
  for the other.
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
`COCRDSLC` (887 lines) → `app/cpy-bms/COCRDSL.CPY` (15 input fields). The `CCDL` row of
`README.md`'s *Online* inventory table labels this transaction **"Credit Card View"**.

**Purpose.** Returns one card. It is also **the only way to obtain the sealed snapshot**
that [§12.3](#123-update-card) requires as its update precondition.

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

**Response — `200 OK`.** `CardResponse` declares **exactly 10 record components**, and the
table below is that list. Note the second row: **there is no raw card-number component.** An
earlier revision published `CARDSIDI X(16)` as "Card number" without qualification, implying
the full sixteen digits were returned here; that is withdrawn.

| JSON property | Map field | PIC | Meaning |
|---|---|---|---|
| `accountId` | `ACCTSIDI` | `X(11)` | Account number, in full |
| `maskedCardNumber` | `CARDSIDI` | `X(16)` | Card number, **masked** — twelve `*` then the last four digits, length preserved. See the masking rule below |
| `cardholderName` | `CRDNAMEI` | `X(50)` | Embossed name |
| `cardStatusCode` | `CRDSTCDI` | `X(1)` | Active status |
| `expiryMonth` | `EXPMONI` | `X(2)` | Expiry month |
| `expiryYear` | `EXPYEARI` | `X(4)` | Expiry year |
| `informationMessage` | `INFOMSGI` | `X(40)` | Informational message — **`X(40)` here, not `X(45)`** |
| `errorMessage` | `ERRMSGI` | `X(80)` | Error message — **`X(80)` here, not `X(78)`** |
| `snapshot` | — | `String` | The as-displayed snapshot, issued as a single **opaque sealed string**, required by [§12.3](#123-update-card) |
| `cardKey` | — | — | A fresh row reference to this same card |

**The masking rule, stated once and applied everywhere a card number is serialised.** It
lives in `com.cardemo.model.dto.ApiMasking.maskCardNumber`, and it is the same function that
masks a list row and a transaction detail:

* A value longer than **4** characters keeps its **last 4** and every earlier character
  becomes `*`. **Length is preserved**, so `**` `**********` `1234` is sixteen characters wide
  and a malformed value of a different length stays distinguishable from a well-formed one.
* A value of 4 characters or fewer is masked **in full** — showing the last four of a
  four-character value would show all of it.
* `null` is returned unchanged, so "no card reference" stays distinct from "a masked one".
* A **blank** value is returned unchanged, because the seven-row card list is padded to its
  table depth with blanks and turning a filler row into a run of asterisks would invent a card
  the source never displayed.
* Trailing blanks are significant and preserved: the mask is applied to the trimmed extent and
  the fixed-width padding is kept, because the symbolic maps declare fixed-width fields.

> **The expiry has a month and a year but no day on this map.** `COCRDSL.CPY` declares
> `EXPMONI X(2)` and `EXPYEARI X(4)` and **no `EXPDAYI`**, whereas the card-update map does
> declare `EXPDAYI X(2)` ([§12.3](#123-update-card)). The two maps genuinely differ and both
> are published as declared.
>
> Note also that this map's two message widths differ from the widths used on most other
> maps: `INFOMSGI` is `X(40)` and `ERRMSGI` is `X(80)`, against `X(45)` and `X(78)`
> elsewhere. Neither is normalised.

Two of those members carry references rather than field values, and they are not
interchangeable:

| Member | Notes |
|---|---|
| `snapshot` | The **as-displayed snapshot**, `CCUP-OLD-DETAILS` of [`app/cbl/COCRDUPC.cbl:L291-L301`] as this read projected it, **sealed into one opaque string**. Required by [§12.3](#123-update-card): echo it into the update's request body **verbatim**. Sealing is what carries the expiry **day** across: `app/cpy-bms/COCRDSL.CPY` declares no expiry-day field, so the value [`:L1507`] compares exists nowhere in the readable response and no client could supply it — the seal is the only route by which it reaches the write. The card number is deliberately **not** inside the payload: [`:L1347`] sources that member from the *received* map field, so it is redundant with the request's own identity, and including it would have put the digits `maskedCardNumber` withholds back onto the wire under a different name. No `ETag` is issued and no `If-Match` is read |
| `cardKey` | A fresh row reference to this same card, so a client that arrived by filter can continue by reference. It says *which* card; `snapshot` says *what was shown*. They are sealed under different purpose labels and are **not** interchangeable |

`FKEYSI X(75)` is function-key chrome and is excluded.

**Side effects.** None on business data; one additional read projects the sealed `snapshot`.

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
shown. **It mirrors the account-update pattern**, including the body-carried precondition.

```http
PUT /api/cards HTTP/1.1
Authorization: Bearer <token>
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

> **The legacy snapshot carried the card verification value**, `CCUP-OLD-CVV-CD` [`:L294`].
> This one does not, and the reason is worth stating precisely, because the obvious inference
> from its absence is wrong. The value **is** stored — the `card_cvv_cd CHAR(3) NOT NULL`
> column of `src/main/resources/db/migration/V1__create_schema.sql`, mapped on the entity and seeded
> by `V3__seed_data.sql`'s `card` insert. What is withheld is the **read path**: the entity field is
> write-once with no getter of any visibility. And `app/cpy-bms/COCRDUP.CPY` declares no
> verification field among its seventeen inputs, so the operator never typed one and no client
> could echo one.
>
> Both operands of the `:L1503` predicate were therefore **server-side** — a display-time read
> compared against a write-time re-read — so the only question it asked was whether the row
> changed between them, and the `@Version` column answers exactly that, for every column of the
> row. The predicate and the snapshot component are dropped **together**; neither may be
> reinstated alone.
>
> As with account update, the snapshot is **projected by the read and echoed back in the
> request body** as the opaque sealed string `snapshot`, obtained from
> [`GET /api/cards/detail`](#122-view-card). It is sealed to this operation, this card, this
> principal and a short expiry, so it cannot be read, edited or authored by a client, and a body
> naming the former readable `oldDetails` group is refused with `400`.

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
| `cardKey` | no | A row reference from [`GET /api/cards`](#121-list-cards) or [`GET /api/cards/detail`](#122-view-card). When supplied it **is the only permitted statement of which card is meant**: it supplies both identifiers, and the body **must not** also carry `accountId` or `cardNumber`. Every other body field is used as sent |

**`cardKey` and the body identifiers are mutually exclusive, not overridden.** An earlier revision of
that row said the reference overrides whatever the body carries, which would make a body identifier
harmless to leave in place. It is not: the reference is opened with the **body's** two identifiers as
arguments, and if either is **non-null** the request is refused with `400` naming `cardKey`, with the
detail `cardKey supplies both the account number and the card number, so accountFilter and cardFilter
must not accompany it. Send the reference alone, or send the two filters without it`.

**The test is `!= null`, not "is meaningful", and that is the part that catches clients.** A member
present and **blank** — `"accountId": ""` or a string of spaces — is still non-null, so it is still a
second statement of identity and is still refused. A client that builds one request object and fills in
whichever fields it has must therefore **omit** the two identifier members entirely when it sends a
reference, rather than blanking them (finding M-22). Two statements of which card is meant is not a
request this operation can honour, and choosing one silently would be the worse answer: it would write
to a card the caller did not name.

The reference travels as a query parameter and **not** as a body member, deliberately: the
body is fixed at the seventeen map fields plus the sealed snapshot, and a routing
reference is not a map field. Nothing about the identifier edits changes — a `cardKey` is
resolved into `ACCTSIDI` and `CARDSIDI` before the operation runs, so the same edits see the
same two values.

**Identifiers are edited before the precondition is judged.** The source's
`CCUP-DETAILS-NOT-FETCHED` arm runs `1210-EDIT-ACCOUNT` and then `1220-EDIT-CARD`
[`app/cbl/COCRDUPC.cbl:L645-L661`] before anything is read, so a request naming no card is
refused for naming no card. That order is preserved even when a `snapshot` is
present: a request with an unusable identifier answers `400` naming the field —
`'No input received'` when both are absent — rather than `412`. Answering `412` there would
be a misleading statement about the request, which never got as far as a comparison.

**Response — `200 OK`.** The updated card in the same shape as
[§12.2](#122-view-card), less the `snapshot` and less the row reference: a successful
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

**The screen was the carrier of that value.** There is no screen here, so the sealed `snapshot`
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
| No identifier supplied — neither body identifiers nor `cardKey`, so `'No input received'` | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| `cardKey` supplied alongside a filter, or one that does not verify | `400` | `CARDDEMO-VALIDATION-REJECTED` |
| Card or account absent | `404` | `CARDDEMO-RECORD-NOT-FOUND` |
| `snapshot` absent, empty or blank, so no precondition was presented — `A sealed snapshot is required for this request...` | `428` | `CARDDEMO-UPDATE-CONFLICT` |
| `snapshot` opens correctly but carries **nothing**, which is what a read that found no card seals — `Please enter Account and Card Number` | `428` | `CARDDEMO-UPDATE-CONFLICT` |
| `snapshot` does **not** open — altered, issued for another card, issued to another principal, or expired — `The sealed snapshot could not be verified for this request...` | `412` | `CARDDEMO-UPDATE-CONFLICT` |
| Snapshot opens and the comparison finds the record changed — `Record changed by some one else. Please review` | `412` | `CARDDEMO-UPDATE-CONFLICT` |
| `Could not lock record for update` | `409` | `CARDDEMO-UPDATE-CONFLICT` |
| `Update of record failed` | `409` | `CARDDEMO-UPDATE-CONFLICT` |
| Store unavailable / read failure / abend | `503` / `502` / `500` | per [§8.2](#82-global-failure-and-status-mapping) |

**There is no `423` on the card path**, however natural the analogy with the account operation
looks. `CardController.statusFor` [`CardController.java`] answers `428` for
`CHANGES_NOT_CONFIRMED`, `412` for `DATA_CHANGED_BEFORE_UPDATE`, and `409` for all three of
`COULD_NOT_LOCK_ACCOUNT`, `COULD_NOT_LOCK_CUSTOMER` and `LOCKED_BUT_UPDATE_FAILED` — a single
arm covering the three. An absent outcome also answers `409`. The two operations therefore map
the **same** five outcomes onto **different** statuses, and that divergence is deliberate: the
card operation writes one dataset, so it has one lock to report and no reason to distinguish a
held-lock failure from a failed rewrite, whereas the account operation writes two and the
source keeps its two lock conditions and its rewrite failure apart
([§11.2.6](#1126-outcome-markers-and-status-mapping)).

Match on `errorCode` plus the `outcome` member, never on the status alone, if your client
handles both operations through one code path.
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

**Response — `200 OK`.** `TransactionResponse` declares **exactly 14 record components**, and
the table below is that list. The card number is **masked**, exactly as on the card
operations. Publishing `CARDNUMI X(16)` as "Card number" without qualification would be wrong:
no component of this response carries a readable primary account number.

| JSON property | Map field | PIC | Meaning |
|---|---|---|---|
| `transactionId` | `TRNIDI` | `X(16)` | Transaction identifier |
| `maskedCardNumber` | `CARDNUMI` | `X(16)` | Card number, **masked** by the same `ApiMasking.maskCardNumber` rule stated at [§12.2](#122-view-card) — twelve `*` then the last four digits, length preserved |
| `typeCode` | `TTYPCDI` | `X(2)` | Type code |
| `categoryCode` | `TCATCDI` | `X(4)` | Category code |
| `source` | `TRNSRCI` | `X(10)` | Source |
| `description` | `TDESCI` | **`X(60)`** | Description — the **full** width, unlike the list's `X(26)` |
| `amount` | `TRNAMTI` | `X(12)` | Amount |
| `originatingDate` | `TORIGDTI` | `X(10)` | Originating date |
| `processingDate` | `TPROCDTI` | `X(10)` | Processing date |
| `merchantId` | `MIDI` | `X(9)` | Merchant identifier |
| `merchantName` | `MNAMEI` | `X(30)` | Merchant name |
| `merchantCity` | `MCITYI` | `X(25)` | Merchant city |
| `merchantZip` | `MZIPI` | `X(10)` | Merchant postal code |
| `statusMessage` | `ERRMSGI` | `X(78)` | Error or status message |

That is 14 rows. Note the last property's spelling: the component is `statusMessage`, not
`errorMessage` — it carries an informational message as readily as a failure one, which is why
it is not named for the failure case.

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

**Supplying both is accepted, and the account branch wins.** "One of the two" states what is
*required*, not what is *permitted*, and an earlier revision of this section left that to be
inferred as mutual exclusivity. It is not: a request naming **both** an account identifier and a
card number is answered normally, the account branch runs, and **the card number the caller sent
is replaced** by the one the cross-reference binds to that account. So a client that sends a
mismatched pair is not refused — it gets a transaction written against the account's own card
(finding M-23). This is the source's own order of evaluation and is preserved rather than
tightened: adding a mutual-exclusivity refusal would refuse a request the legacy screen accepted.

**And the `201` will not tell you which card was used**, because `maskedCardNumber` is one of the
members cleared before the response is composed — see the table below. The substitution is
therefore silent on the success arm: to learn which card the row was written against, fetch
[`GET /api/transactions/detail`](#132-view-transaction) with the returned identifier. Send one key,
not two, if that matters to you.

**The transaction identifier is not an input.** It is generated server-side — see
[§13.3.1](#1331-identifier-generation-a-known-and-accepted-race).

**Response.**

| Outcome | Status | Body | Headers |
|---|--:|---|---|
| Written | **`201 Created`** | The shape of [§13.2](#132-view-transaction) with **the input fields cleared** — see immediately below | `Location` addressing the new resource |
| Confirmation required, or the caller declined, or the screen was merely displayed or cleared | `200 OK` | The same shape, carrying the source's message; **nothing was written** | — |

The distinction matters: only the written arm claims `201`. The four no-write terminations
were understood and answered, so none of them is a failure — but none of them wrote, so none
may claim `201` either.

**The `201` body does not echo the transaction that was written, and this is the single most
likely thing to catch a client.** An earlier revision described it as returning the created
transaction's details, including the edited amount. It does not. On
`WHEN DFHRESP(NORMAL)` the source performs **`INITIALIZE-ALL-FIELDS` first** and only then
composes the message [`app/cbl/COTRN02C.cbl:L724-L734`], so every input field — the identifiers,
the amount, the description, the merchant fields, the dates — is **blank** in the success
response. The amount in particular comes back empty, not as the value just posted.

| On the `201` arm | Value |
|---|---|
| `maskedCardNumber`, `typeCode`, `categoryCode`, `source`, `description`, `amount`, `originatingDate`, `processingDate`, `merchantId`, `merchantName`, `merchantCity`, `merchantZip` | **Blank** — every one of the twelve, as the **empty string** here rather than space-filled. They were cleared before the response was composed |
| `transactionId` | The generated identifier, and the only structured member that carries it |
| `statusMessage` | The success notice, rendered green: `Transaction added successfully.  Your Tran ID is <id>.` — note the **two** spaces, which are the source's own `STRING` operands. Spelled `statusMessage`, not `errorMessage`; see [§13.2](#132-view-transaction) |
| `Location` | Addresses the new resource |

**So read the identifier from `transactionId`, from `Location`, or from the message, and fetch
[`GET /api/transactions/detail`](#132-view-transaction) if you need the stored row.** Treating a
blank amount in a `201` as a failed write, or as a zero amount, is the mistake this note exists
to prevent — the write succeeded, and the source's own screen was cleared for the next entry
(finding M-24). This is parity, not an omission: it is preserved deliberately rather than
"improved" into an echo the legacy program never produced.

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

**On the settled arm the account identifier and the balance come back blank.** They are ordinary
values on every *other* arm, which is what made an earlier revision of the paragraph above read
as though they were ordinary values on all of them. They are not: `WHEN DFHRESP(NORMAL)` performs
**`INITIALIZE-ALL-FIELDS` before** composing the message [`app/cbl/COBIL00C.cbl:L524-L527`], and
that paragraph moves `SPACES` over `ACTIDINI`, `CURBALI` and `CONFIRMI` [`:L560-L566`].

The response carries **five members on every arm**, and the blanking is what distinguishes them.
Note the spelling: the message member is `message`, not `errorMessage`, and there is no
`confirmation` member on the response at all — `CONFIRMI` is an input.

| Member | Prompt arm, `200` | Decline arm, `200` | Settled arm, `201` |
|---|---|---|---|
| `outcome` | `CONFIRMATION_REQUIRED` | `CANCELLED` | `SETTLED` |
| `accountId` | The account, as sent | **Blank** — eleven spaces, its `ACTIDINI X(11)` width | **Blank** — eleven spaces |
| `currentBalance` | The balance as read, e.g. `+0000000147.00` | **Blank** — fourteen spaces, its `CURBALI X(14)` width | **Blank** — *not* the zero the payment drove it to, and not the balance that was paid |
| `transactionId` | `null` | `null` | The generated identifier |
| `message` | `Confirm to make a bill payment...` | **Empty** — the decline arm publishes nothing | `Payment successful.  Your Transaction ID is <id>.` — two spaces, one from each `STRING` operand |

**Blank here means space-filled to the map field's width, not the empty string** — the source moves
`SPACES` over a fixed-width field, and the API relays what that field then holds. A client testing
for `""` will not match; test for blank.

A caller needing the paid amount or the resulting balance reads the account again; the amount
paid was the whole balance as it stood at the read ([§14.1](#141-bill-payment) above), and the
balance afterwards is zero by construction. **A blank `currentBalance` in a `201` means the
payment succeeded**, not that the balance is unknown or that the field failed to serialise
(finding M-25). `BillPaymentResponse` documents the same blanking in code, so the two agree.

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
| Blank, absent, **or all-NUL `LOW-VALUES`** | Read the account and prompt | `Confirm to make a bill payment...` [`:L237`] | `200` |
| Anything else | Refuse the value | `Invalid value. Valid values are (Y/N)...` [`:L187-L188`] | `400` |

**`LOW-VALUES` shares the prompt arm, and it is a fourth spelling of "not answered" rather than
an invalid value.** The `EVALUATE` names `WHEN SPACES` and `WHEN LOW-VALUES` as two arms reaching
the same `PERFORM READ-ACCTDAT-FILE` [`:L182-L184`], so a confirmation field of NUL bytes — which
is what an untouched BMS field carries, and what a client sends as `"\u0000"` — is **prompted,
not refused**. An earlier revision of this row named only blank and absent, which would have led
a client to expect `400` for that input (finding M-26). The distinction is worth keeping straight
because it is the opposite of the admin user-identifier rule, where NUL bytes *are* refused: there
the field is an identifier the source explicitly tests for `SPACES OR LOW-VALUES` and rejects,
whereas here it is a confirmation whose unanswered state is exactly what those two values mean.

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
`CORPT00C` (649 lines) → `app/cpy-bms/CORPT00.CPY` (17 input fields). The `CR00` row of
`README.md`'s *Online* inventory table labels this transaction **"Transaction Reports"**.

**Purpose.** Submits a transaction-report job for **asynchronous** batch processing.

```http
POST /api/reports HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
```

**Role.** USER or ADMIN.

#### 15.1.1 This endpoint accepts a job; it does not return a report

The legacy program wrote a **seventeen-card, eighty-byte job deck** to the transient data
queue `JOBS`, defined as
`DEFINE TDQUEUE(JOBS) … TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)
RECORDFORMAT(FIXED) DISPOSITION(MOD)` [`app/csd/CARDDEMO.CSD:L499-L505`]. The submission
loop iterated up to a thousand card images, terminating on `'/*EOF'`, spaces or low values —
and **wrote the terminating card before exiting the loop** [`app/cbl/CORPT00C.cbl:L498-L508`].

**Seventeen, not eighteen.** `JOB-DATA-1` [`app/cbl/CORPT00C.cbl:L82-L125`] declares fourteen literal
`05 FILLER PIC X(80)` cards plus three composed ones — `FILLER-1` and `FILLER-2`, which inject
the two `SYMNAMES` date parameters, and `FILLER-3`, which is the `DATEPARM` card carrying both
dates. Fourteen plus three is seventeen. The thousand is unrelated: it is the `OCCURS 1000
TIMES` bound on the `JOB-DATA-2 REDEFINES` view [`:L126-L127`] that the loop walks, of which
only the first seventeen entries are ever populated.

The sixth card is `//STEP10 EXEC PROC=TRANREPT` [`:L93-L94`], which is what the deck exists to
start — so the deck launches **the transaction report job and nothing else**.

In the target that whole deck collapses into **one typed JSON message published to an SQS FIFO
queue**, carrying the report name and the two dates; a listener maps it onto Spring Batch job
parameters and launches the same job the deck named.

**The queue name has two spellings and both matter.** The logical name configured for the
application is `carddemo-report-jobs`, the `report-queue-logical-name` key of
[`src/main/resources/application.yml`], and the same value is the FIFO message group id under
that file's `report-message-group-id` key. The **physical
queue is `carddemo-report-jobs.fifo`** — SQS requires the `.fifo` suffix on a FIFO queue — and
that is the name the local profile and the compose topology provision and address, under the
`report-queue` key of [`src/main/resources/application-local.yml`] and the
`CARDDEMO_SQS_REPORT_QUEUE` variable of [`docker-compose.yml`]. Address the physical name
when you inspect the queue directly with the AWS CLI; a request for `carddemo-report-jobs`
against LocalStack will not find it.

> **The response is therefore an acceptance, not a report.** `202 Accepted` means *the job
> was queued*, not *the report is ready*. No report body is ever returned by this endpoint,
> and no report content is available from it.

**Correlating a submission with the job run it caused — what actually works.** An earlier
revision of this section promised that the correlation identifier is "carried into the queue
message and into every batch log record for the run", and that a `jobInstanceId` is available
to the caller. **Both are withdrawn.** The identifier does reach the queue, as a bounded
message header named by `CorrelationIdFilter.CORRELATION_ID_HEADER`, but
`BatchConfig.ReportJobQueueListener` builds its job parameters from the report name, the two
dates and the SQS **deduplication identifier** only: it neither reads that header nor restores
it into the diagnostic context. No `jobInstanceId` is returned on the `202` either.

So the correlation is a two-hop join rather than one identifier end to end:

| Hop | Join key | Where to read it |
|---|---|---|
| HTTP request → publish | `correlationId` | Send `X-Correlation-Id`, or read it back from the response body and the response header. It appears on the submitting request's own log records and on the publish record |
| publish → job launch | the SQS message deduplication identifier | The listener logs it against the launched job name and the Spring Batch execution id on every launch, refusal and failure arm |

A caller therefore quotes its `correlationId` in a support request, and an operator follows it
to the publish record and from there to the launch record by the deduplication identifier.
Closing that gap — propagating the correlation identifier into the batch run's diagnostic
context — is real work that has not been done, and it is recorded as such rather than implied
to be in place.

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

**"One of three" is what is required, not what is permitted — more than one is accepted, and the
order is fixed.** `PROCESS-ENTER-KEY` is an `EVALUATE TRUE` whose three `WHEN` conditions are
tested **in written order**, and the first one that holds runs and submits:

| Order | Selector | Condition | Locator |
|--:|---|---|---|
| 1 | monthly | `MONTHLYI NOT = SPACES AND LOW-VALUES` | [`:L213`] |
| 2 | yearly | `YEARLYI NOT = SPACES AND LOW-VALUES` | [`:L239`] |
| 3 | custom | `CUSTOMI NOT = SPACES AND LOW-VALUES` | [`:L256`] |

**There is no mutual-exclusivity check anywhere in the program.** A request populating two or
three selectors is therefore accepted and **is not refused**: precedence decides, so monthly beats
yearly, and yearly beats custom. A client that populates monthly *and* custom, expecting a `400`
or expecting its custom dates to be honoured, gets a **monthly** report instead — and, because the
custom arm never ran, its six date components are never even validated (finding M-27). An earlier
revision of this section said "one of Monthly / Yearly / Custom" and left the multi-selector case
undescribed, which reads as exclusivity.

Precedence is preserved rather than replaced with a refusal: adding one would reject a screen state
the legacy operator could produce, and it would change which report an existing caller receives.
**To choose a period unambiguously, populate exactly one selector and leave the other two blank.**

Note that the same `NOT = SPACES AND LOW-VALUES` test governs each selector, so an all-NUL selector
value does **not** select — it is one of the two spellings of "not chosen".

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
| Blank, absent, **or all-NUL `LOW-VALUES`** | Prompt | `Please confirm to print the <name> report...` — composed from the fixed prefix, the report name and the suffix [`:L465-L470`] | `200` |
| Anything else | Refuse | `"<value>" is not a valid value to confirm...` — **the offending value is quoted back inside double quotes** [`:L485-L490`] | `400` |

The report name substituted into those messages is one of the three literals `'Monthly'`,
`'Yearly'` or `'Custom'` [`:L214`, `:L240`, `:L433`].

**The prompt arm is reached by `LOW-VALUES` as well as by blank**, because the gate tests
`IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES` in one condition [`:L464`]. So a confirmation of
NUL bytes is **prompted with `200`, not refused with `400`** — it is a third spelling of "not
answered", exactly as on [bill payment](#141-bill-payment), and an earlier revision of these two
rows named only blank and absent (finding M-26).

**Validation order.** Period selector first — with no selector at all the request is refused
before anything else — then, for a custom period, the two composite dates through the date
service, then the confirmation gate, then the publish.

**Exact observable literals.**

| Condition | Literal | Locator |
|---|---|---|
| No period selector supplied | `Select a report type to print report...` | [`app/cbl/CORPT00C.cbl:L438`] |
| Custom end date invalid | `End Date - Not a valid date...` | [`:L420`] |
| Published | `<name> report submitted for printing ...` | [`:L449-L452`] |
| Confirmation blank, absent or all-NUL `LOW-VALUES` | `Please confirm to print the <name> report...` | [`:L465-L470`] |
| Confirmation neither `Y` nor `N` | `"<value>" is not a valid value to confirm...` | [`:L485-L490`] |
| Queue write failed | `Unable to Write TDQ (JOBS)...` | [`:L531-L532`] |

**Response.**

| Outcome | Status | Meaning |
|---|--:|---|
| Job published | **`202 Accepted`** | The message is on the queue; the batch tier will produce the report |
| Declined at the confirmation gate, or a prompt is required | `200 OK` | **Nothing was published** |
| Validation refused | `400` | Nothing was published |

**Side effects.** On the accepted arm: **one message published to the SQS FIFO queue whose
logical name is `carddemo-report-jobs` and whose physical name is
`carddemo-report-jobs.fifo`** ([§15.1.1](#1511-this-endpoint-accepts-a-job-it-does-not-return-a-report)).
No business row is written by this operation. The report object itself is produced later, by
the batch tier.

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

**Response — `201 Created`.** The created user **without any password member**, and with the four
descriptive members **blank**: the only member that names the user just created is the message
`'User <id> has been added ...'` [`app/cbl/COUSR01C.cbl:L255-L257`].

| On the `201` arm | Value |
|---|---|
| `userId`, `firstName`, `lastName`, `userType` | **Blank** |
| password, in any member | **Absent entirely**, on every arm |
| `errorMessage` | `User <id> has been added ...`, rendered green |

**Why they are blank.** `WHEN DFHRESP(NORMAL)` performs **`INITIALIZE-ALL-FIELDS` before**
composing the message [`:L251-L257`], clearing the entry screen for the next user. An earlier
revision of this paragraph said the `201` returns a populated identifier, name and type; it does
not, and a client reading `userId` from this response gets an empty string rather than the
identifier it just created (finding M-28). **Read the identifier from the request you sent**, or
from the message — the write is keyed on the identifier you supplied, so there is no server-assigned
value to discover. The blanking is parity and is preserved rather than "improved" into an echo.

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
> with no lower-case letters. See [§18.3](#183-medium), finding M-11.

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
| Body | user id | `USRIDINI` | `X(8)` | no — **omit** it, or send it equal to the path; present-but-empty is refused |
| Body | first name | `FNAMEI` | `X(20)` | **yes** |
| Body | last name | `LNAMEI` | `X(20)` | **yes** |
| Body | password | `PASSWDI` | `X(8)` | **yes** — see below |
| Body | user type | `USRTYPEI` | `X(1)` | **yes** — `A` or `U` only, see [§16.2.1](#1621-labelled-deviation-usertype-is-restricted-to-a-and-u) |

**The identifier comes from the path. The body need not repeat it, and must not contradict
it.** `USRIDINI` is a declared member of the map, so it is accepted in the body — but it is
optional there in the sense that it may be **left out entirely**, and the path supplies it when it
is. Sending it *present and empty* is a different thing and is refused rather than back-filled;
see the three-state table below. This is the source's own arrangement rather than a REST
convenience: on first entry `COUSR02C` does not wait for an operator to type the identifier, it
tests `CDEMO-CU02-USR-SELECTED` — the value the user-list screen left in the commarea — moves it
into `USRIDINI` and only then runs its edit [`app/cbl/COUSR02C.cbl:L99-L103`]. The screen field was
a *carrier* of the navigation context, not its origin, and here the path segment is that context.

A body that names a **different** user is refused with `400` and `field: "userId"`: the path
addresses the record and the body declares the same field, so preferring either one would
rewrite a record the caller did not address. The comparison is exact — nothing is trimmed and
nothing is case-folded, because `COUSR02C` folds neither.

**Three states, not two: omitted, empty, and named.** "Optional in the body" means the member may
be **left out**; it does not mean it may be sent empty. The distinction is observable and it
changed:

| Body `userId` | Behaviour | Status |
|---|---|--:|
| **Member absent** | The path supplies the identifier | `200` |
| **Member present and empty** — `""`, spaces, or NUL bytes | Refused with the source's own literal `User ID can NOT be empty...` | **`400`** |
| Member present and equal to the path | Accepted | `200` |
| Member present and different | Refused, `field: "userId"`, must match the path | `400` |

**An earlier build answered `200` for the second row, and that was a defect rather than a
convenience.** The path identifier was substituted whenever the body member was absent *or blank*,
which made the source's own guard unreachable: `COUSR02C` tests
`USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES` and refuses with that literal at both
[`app/cbl/COUSR02C.cbl:L146-L151`] and [`:L179-L185`], so an empty identifier is something the
legacy program refuses twice and the API accepted. It is now refused, matching the source
(finding H-3).

**Why the distinction is the source's and not an invention.** First entry into `COUSR02C` back-fills
the identifier from the navigation context, and **only** when that context is
`NOT = SPACES AND LOW-VALUES` [`:L95-L104`]; the re-entry leg — which is what a write corresponds
to — performs `RECEIVE-USRUPD-SCREEN` and back-fills **nothing** [`:L106-L111`]. An absent member is
therefore the first-entry case, where substitution is correct; a present-but-empty member is a
received field the operator left blank, which is exactly what the guard exists to catch.

**NUL bytes count as empty here.** The check mirrors `SPACES OR LOW-VALUES` — whitespace **and**
ISO control characters — and is deliberately byte-identical to the service's own predicate, so the
controller and the service cannot classify the same value differently. A NUL-filled identifier
previously escaped the blank test and was reported as a *mismatch*: the right status by accident,
with the wrong reason and the wrong message.

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
> with no lower-case letters. See [§18.3](#183-medium), finding M-11.

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

Note that **this operation needs no snapshot at all.** The comparison here
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

**Response — `200 OK`.** Exactly five members — and on the **deleted** arm the first four are
**blank**, leaving the message as the only member naming the user that was removed:
`'User <id> has been deleted ...'` [`:L318-L320`].

**Why they are blank.** The same idiom as user add: `WHEN DFHRESP(NORMAL)` performs
**`INITIALIZE-ALL-FIELDS` before** composing the message [`app/cbl/COUSR03C.cbl:L313-L322`]. An
earlier revision said the success arm returns the identifier, names and type; those are ordinary
values on the *fetch* arm — the one that displays a user for confirmation — and blank on the
*deleted* arm (finding M-29). The distinction matters here more than on user add, because both arms
answer `200` with the same five members, so **the presence of a populated name is what tells the two
apart**, together with the message text.

| JSON member | Map field | PIC |
|---|---|---|
| `userIdInput` | `USRIDINI` | `X(8)` |
| `firstName` | `FNAMEI` | `X(20)` |
| `lastName` | `LNAMEI` | `X(20)` |
| `userType` | `USRTYPEI` | `X(1)` |
| `errorMessage` | `ERRMSGI` | `X(78)` — carries the success message on this arm, as the source does |

This operation used to serialize all eleven map fields, so it was the only one of the
seventeen that put the six chrome header fields on the wire — including `programName`, which
is the `XCTL` operand (finding M-15). They are withheld from the response now, exactly as
[§4.2](#42-presentation-chrome-is-accepted-on-request-and-absent-from-every-response) states
for every response body on this API.
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

### 17.1 An account update returns `412`, or `428`, or `400` naming the body

**Symptom.** `PUT /api/accounts` answers one of three things, on every attempt, even against
an account nobody else is touching:

* `412 Precondition Failed`, with either `The sealed snapshot could not be verified for this
  request...` or `Record changed by some one else. Please review`.
* `428 Precondition Required`.
* `400`, naming a body member.

**Cause.** All three come from the same mistake: **the snapshot is not being echoed through
from the server unaltered.** Which of the three you get identifies what happened:

| What you see | What it means |
|---|---|
| `400` naming `oldDetails` | The body carries the **old readable `oldDetails` object**. That member no longer exists on this API, and unknown members are refused rather than ignored ([§8.5](#85-refusals-decided-before-an-operation-is-reached)) |
| `428` | No `snapshot` was sent, or it was empty or blank. Nothing was read and nothing was written |
| `412` with `could not be verified` | A `snapshot` was sent but it does not open: it was **edited or re-encoded**, or it was issued for a **different account**, or to a **different principal**, or it has **expired** |
| `412` with `Record changed by some one else` | The snapshot opened and is genuine, and the stored record really has changed since the read. Re-read and re-apply |

**The commonest cause of the third row is a client trying to be helpful with the string.**
It is opaque and it is authenticated, so any transformation breaks it — re-serialising it,
trimming it, changing its case, URL-decoding it, storing it through something that normalises
Unicode, or splitting and rejoining it. There is nothing inside it a client needs.

**A note for anyone who integrated against an earlier revision of this document.** That
revision told you to build or copy an `oldDetails` **object**, and its own troubleshooting
entry simultaneously said sending `oldDetails` in the body was refused outright — advice that
contradicted itself, so no client could satisfy both halves (finding H-2). The contradiction is
resolved in the only direction that leaves the guard intact: the snapshot **does** travel in the
body, it is **not** the readable group, and it is **not** something a client can author. The
date-of-birth trap that made a hand-built group impossible to get right — the live record at
offsets 1, 6, 9 against the snapshot at offsets 1, **5**, **7**
[`app/cbl/COACTUPC.cbl:L4174-L4179`] — is now settled inside the seal, where no client can meet
it.

**Fix.** Call [`GET /api/accounts/{accountId}`](#111-view-account), take the `snapshot` **string**
from the response, and send it back **verbatim** as the `snapshot` member of the `PUT` body.
Do not send `oldDetails`. Then add `?confirm=true`, without which the operation answers `428`
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
| `401` with `CARDDEMO-AUTHENTICATION-REQUIRED` on any operation but sign-on | No usable `Authorization: Bearer` credential. Sign on first; the credential pattern accepts only `Bearer` followed by whitespace and an unpadded token. The body is the six-member problem document of [§8.5](#85-refusals-decided-before-an-operation-is-reached), never empty |
| `403` with `CARDDEMO-AUTHORIZATION-DENIED` on `/api/admin/**` or `GET /api/menu/admin` | The token's `role` claim is not the administrator code. These paths require `ROLE_ADMIN` ([§5.1](#51-role-model)). Same problem document; a `403` observed with a genuinely empty body would mean the chain was bypassed and is worth reporting |
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
authorisation rather than a silent code edit.

**Where a finding changed behaviour, the decision that changed it is recorded in
`../DECISION_LOG.md`** under its own `DL-` anchor, and the entry below names that anchor.
Findings that corrected a claim in *this* document without touching behaviour are recorded here
only: the decision log records decisions about the system, not the history of a sentence.

**No Blocker-severity finding remains open against this contract.** Two were raised and both are
closed; they are recorded rather than dropped, because a client that integrated against the
earlier revision has work to do.

### 18.1 Blocker

**B-1 — The as-displayed snapshot an account or card update compares against was caller-editable,
so the change-detection guard could be defeated by the request it was meant to police.**

* **Severity: Blocker.** The snapshot arrived as a readable `oldDetails` group and was trusted as
  authentic. A caller could therefore copy the *live* values into it — or simply echo back
  whatever it wanted the server to believe had been displayed — and the field-by-field comparison
  would agree with itself and admit the write. That is the whole of Transformation Rule 16's
  business-level guard neutralised by input the guard does not authenticate (CWE-345, and
  CWE-602 for the client-side-enforcement shape of it). The version column still caught a
  genuinely concurrent write, but the two guards answer different questions and only one of them
  was standing.
* **Locator.** The source's guard is `9700-CHECK-CHANGE-IN-REC`
  [`app/cbl/COACTUPC.cbl:L4109-L4192`] comparing the live records against `ACUP-OLD-DETAILS`
  [`:L669-L756`]. In `COACTUPC` that snapshot is **not** caller-supplied: it lives in the program's
  own working storage between the send and the receive, and a 3270 operator cannot reach it. The
  target is stateless by Transformation Rule 7, so the snapshot has to travel in the request —
  which is what makes authenticating it, rather than merely carrying it, the substantive
  requirement.
* **Status. Remediated.** The read now returns the snapshot as one opaque `snapshot` string,
  sealed by `SnapshotTokenService` and bound to four facts — the operation kind, the record
  identity, the authenticated subject and an expiry. The write opens and verifies it before the
  comparison runs, so the values compared are the ones the server itself projected at read time.
  A tampered, re-subjected, re-targeted or expired string is refused. `@Version` is unchanged and
  still runs: both layers, as entry `DL-PP-02` of `../DECISION_LOG.md` requires.
* **Remediation.** Complete in the code. Client-visible: a caller must now echo the `snapshot`
  string from the preceding read instead of composing an `oldDetails` object. See
  [§11.2.3](#1123-how-the-snapshot-travels) and finding **H-2**, which records the documentation
  half of the same change.

**B-2 — The account read serialized the nine components the same document says are withheld,
inside the snapshot group.**

* **Severity: Blocker.** `oldDetails` carried the customer's date of birth, government-issued
  identifier, both telephone numbers, the electronic-funds account identifier and the primary-card
  indicator among them — the exact members
  [§11.1](#111-view-account) states are withheld from the response. Withholding a component from
  the display projection while shipping it inside the snapshot projection discloses it just the
  same (CWE-200); the second copy was the one nobody read.
* **Locator.** `CVCUS01Y` declares the components and `COACTVWC` reads the customer record, so
  they legitimately exist on the record type. What made the disclosure a defect is that this
  document published them as withheld while the wire carried them: the contract and the payload
  disagreed, and the payload wins.
* **Status. Remediated.** Sealing the snapshot removed the readable copy from the wire as a side
  effect: the string is ciphertext, so no component of it is legible to a client, a proxy log or a
  browser cache. The withholding claim in [§11.1](#111-view-account) is now true of the whole
  response rather than of one projection within it, and a test asserts the response body contains
  none of the nine literal values.
* **Remediation.** Complete. No client action beyond **B-1**'s.

### 18.2 High

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

**H-2 — The account update's snapshot instruction contradicted its own troubleshooting entry, so
no client could satisfy both halves.**

* **Severity: High.** [§11.2](#112-update-account) told a caller to build or copy an `oldDetails`
  object into the request body, while [§17.1](#171-an-account-update-returns-412-or-428-or-400-naming-the-body)
  simultaneously listed "sending `oldDetails` in the body" as a cause of refusal. Following either
  half made the other false. This is worse than a wrong instruction, because a reader who notices
  the conflict has no way to tell which half is the current one, and a reader who does not notice
  it implements the half they happened to read first.
* **Locator.** `docs/api-contracts.md` §11.2.3 and §17.1 as they read before this revision. The
  underlying behaviour is `AccountUpdateRequest` and `AccountUpdateService.updateAccount`; the
  contradiction was between two sections of this document, not between the document and the code.
* **Status. Remediated.** Both halves are rewritten against the delivered code and now say one
  thing: the snapshot **does** travel in the body, it is the opaque `snapshot` string the preceding
  read returned, and it is not something a client composes. §17.1 was retitled at the same time,
  because its old title named a status the operation no longer answers on that condition; no
  inbound link targeted the old anchor.
* **Remediation.** Complete. A client that implemented the `oldDetails` object must switch to
  echoing `snapshot`; the request will otherwise be refused at binding, because
  `fail-on-unknown-properties` is enabled. Recorded together with findings **B-1** and **B-2**,
  which are the behavioural half of the same change.

**H-3 — `PUT /api/admin/users/{userId}` accepted an explicitly empty body identifier and answered
`200`, making the source's own guard unreachable.**

* **Severity: High.** The path identifier was substituted into the request whenever the body
  member was absent **or blank**. Absent is correct — the path is the navigation context. Blank is
  not: `COUSR02C` refuses an empty `USRIDINI` twice, and collapsing the two states meant a caller
  that sent `"userId": ""` silently updated the record the path named instead of being told its
  body was wrong. A NUL-filled value was worse still: `Character.isWhitespace('\u0000')` is
  `false`, so it escaped the blank test entirely and was reported as an identifier *mismatch* —
  the right status reached for the wrong reason, with a message that named the wrong problem.
* **Locator.** [`app/cbl/COUSR02C.cbl:L146-L151`] and [`:L179-L185`] both test
  `USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES` and refuse with `User ID can NOT be empty...`.
  The substitution is legitimate only on the first-entry leg, which back-fills from
  `CDEMO-CU02-USR-SELECTED` and only when that context is `NOT = SPACES AND LOW-VALUES`
  [`:L95-L104`]; the re-entry leg back-fills nothing [`:L106-L111`].
* **Status. Remediated.** The controller substitutes the path identifier only when the member is
  genuinely absent, and its blank predicate is byte-identical to the service's — whitespace **and**
  ISO control characters, so `LOW-VALUES` is covered and the two layers cannot classify the same
  value differently.
* **Remediation.** Complete. **Client-visible behaviour change:** a body that sends `userId` as an
  empty string, spaces or NUL bytes now answers `400` where it previously answered `200`. Omitting
  the member is unchanged. Documented at [§16.3](#163-update-user).

### 18.3 Medium

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
* **Status. Remediated.** A `Content-Type` screen runs in the security chain — before
  authorization, before MVC binding and before the body is read, so it never depends on a handler
  having been selected — and raises the condition the request actually is. All seven operations now
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
  `snapshot` is. The submitted component is still accepted and still echoed, exactly as the
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
  monitor. It goes unnoticed because all ten seeded credentials carry one fixed
  password that is already wholly upper-case. **That value is not reproduced here** — it is at
  [`app/jcl/DUSRSECJ.jcl:L34-L45`], in the inline `SYSUT1 DD *` stream, and quoting a working
  credential in published documentation would make this page a place to look one up. It is
  seeded only under the `local` and `test` profiles, and only as a BCrypt hash.
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
  contradicted [§4.2](#42-presentation-chrome-is-accepted-on-request-and-absent-from-every-response),
  which states the six chrome fields are withheld from every response body, and both handed a
  client the one piece of state Transformation Rule 7 exists to remove.
* **Locator.** The option tables do declare a program name
  [`app/cpy/COMEN02Y.cpy:L91`, `app/cpy/COADM02Y.cpy:L48`] and `COUSR03.CPY` does declare
  eleven fields, so the components belong on the record types. What does not belong on the wire
  is what the program name is *for*: it is the operand of `EXEC CICS XCTL PROGRAM(...)`
  [`app/cbl/COMEN01C.cbl:L152-L156`], and `app/cpy/COCOM01Y.cpy`'s routing members are mapped
  to "no equivalent — routing is URL-based" for exactly that reason.
* **Status. Remediated.** The excluded members are suppressed from JSON only. Every component,
  its width check and its copybook citation stay on the record, so the field contract and the
  441-field budget of [§4](#4-field-contract-provenance) are untouched; the delete response is
  now the five members
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

**M-18 — Every `401` and `403` row published an empty body, describing an unreachable branch
instead of the observed response.**

* **Severity: Medium.** The operation tables named `Empty` for both statuses, so a client had no
  reason to parse a body it was told did not exist — and would have discarded the six-member
  problem document it actually receives, including the `errorCode` it is supposed to match on. The
  claim was not invented: both handlers *do* contain a bodyless branch. It is simply not the branch
  that runs.
* **Locator.** `SecurityConfig`'s matcher chain guards every operation with `hasAuthority` or
  `hasAnyAuthority`, so the `AuthorizationFilter` refuses before any handler is entered, and the
  chain's entry point and access-denied handler write the full document. The controller branches
  are retained as defence in depth and are internal paths, not published behaviour.
* **Status. Remediated.** Every `401` and `403` row now names the problem document, keyed on
  `CARDDEMO-AUTHENTICATION-REQUIRED` and `CARDDEMO-AUTHORIZATION-DENIED`, and the reason the code
  reads differently is stated where the rows are. [§10.1](#101-main-menu) and
  [§10.2](#102-admin-menu) carry the canonical explanation.
* **Remediation.** Complete. A genuinely empty `403` would mean the chain was bypassed and is
  worth reporting.

**M-19 — Three different snapshot failures were published as one `400`, under a status that
suggested none of their remedies.**

* **Severity: Medium.** An absent snapshot, an unopenable one and a comparison that finds the
  record changed need three different client responses: obtain a snapshot, read the record again,
  and read the record again. Publishing all three as `400 oldDetails` told a caller to fix its
  request, which is the one thing that will not help in any of the three cases. A client
  implementing the documented behaviour retries the same body and fails identically.
* **Locator.** `AccountController.updateAccount` evaluates in a fixed order — authorisation, then
  the confirmation token, then the snapshot, then the comparison — and
  `AccountController.statusFor` maps `CHANGES_NOT_CONFIRMED` to `428` and
  `DATA_CHANGED_BEFORE_UPDATE` to `412`. Neither outcome has ever produced a `400`.
* **Status. Remediated.** [§11.2.6](#1126-outcome-markers-and-status-mapping) now carries a
  six-row evaluation-order table giving the exact status, `outcome` marker and `detail` literal for
  each input, and contrasts it with card update, which has one arm — an authentic but hollow
  snapshot — that the account operation does not.
* **Remediation.** Complete.

**M-20 — The account operations emitted a `code` member on two problem bodies and documented
none of it.**

* **Severity: Medium.** `AccountController` adds a `code` property to the `404` and `409` problem
  documents, carrying `ACCOUNT_RECORD_NOT_FOUND` and `ACCOUNT_WRITE_REFUSED`. It appears on no
  other resource. An undeclared member is the worse of the two documentation failures: a client
  cannot rely on it, and cannot safely ignore it either, because a strict deserialiser may refuse
  an unknown property.
* **Locator.** `AccountController` defines the property name once and sets it in its
  record-not-found and data-integrity handlers. The member is real, live and only there.
* **Status. Remediated.** [§8](#8-error-response-envelope)'s exhaustive member table carries the
  member, with a sub-table giving both values against the two statuses they appear on, and states
  that `errorCode` remains the member to match on. It is documented rather than removed because
  removing a member from a live surface is the breaking change.
* **Remediation.** Complete.

**M-21 — The card list filters were documented as refusing an all-zero value; an all-zero filter
is no filter at all.**

* **Severity: Medium.** Both filter rows said the value is refused unless it is a non-zero number
  of the declared width, which reads as `00000000000` being an error. A client implementing that
  reading rejects, client-side, the exact input that asks for the unfiltered browse this operation
  exists to offer — and it contradicted [§7](#7-pagination-contract), which already stated the
  asymmetry correctly.
* **Locator.** `COCRDLIC` tests `LOW-VALUES`, `SPACES` **or `ZEROS`** in one condition and, on any
  of the three, sets the filter-blank flag, moves zeros to the browse key and exits with no error
  and no message [`app/cbl/COCRDLIC.cbl:L1007-L1013`]; the card filter does the same at
  [`:L1041-L1047`]. What *is* refused is `IS NOT NUMERIC` over the whole fixed-width field
  [`:L1017-L1025`, `:L1051-L1059`], which also protects the row set with
  `FLG-PROTECT-SELECT-ROWS-YES`.
* **Status. Remediated.** Both rows in [§12.1](#121-list-cards) publish the three-state rule —
  blank, all-zero and absent are the same instruction — with the short-value padding and
  long-value truncation behaviour stated and the source locators cited.
* **Remediation.** Complete.

**M-22 — `cardKey` was documented as overriding the body identifiers; it is mutually exclusive
with them, and the test is `!= null`.**

* **Severity: Medium.** "Overrides" makes a leftover body identifier harmless, so a client that
  reuses one request object and fills whatever fields it has would leave them in place — and be
  refused with a `400` naming a member it did not think it was using. The trap is sharper than the
  wording suggests: a member present and *blank* is still non-null, so blanking the identifiers
  does not clear them.
* **Locator.** `CardController` opens the reference with the body's account identifier and card
  number as arguments and refuses when either is non-null, with a detail that names both filters.
  Presence, not meaningfulness, is the test.
* **Status. Remediated.** The `cardKey` row in [§12.3](#123-update-card) publishes the mutual
  exclusivity with the exact refusal detail quoted, and a dedicated paragraph states the
  present-but-blank case and instructs clients to **omit** the two members rather than blank them.
* **Remediation.** Complete.

**M-23 — Transaction add's dual-key case was left to be inferred as mutual exclusivity; the
account branch wins and silently replaces the caller's card number.**

* **Severity: Medium.** "One of the two must be supplied" states what is required, not what is
  permitted. A request naming both is accepted, the account branch runs, and the card number the
  caller sent is **replaced** by the one the cross-reference binds to that account. A client that
  sends a mismatched pair expecting a refusal instead gets a transaction written against a
  different card than the one it named, and will believe otherwise unless it re-reads the echoed
  value.
* **Locator.** `COTRN02C` evaluates the account identifier first and resolves the card number from
  the cross-reference; there is no mutual-exclusivity test in the program. The requirement literal
  for supplying neither is `'Account or Card Number must be entered...'` [`:L226`].
* **Status. Remediated.** [§13.3](#133-add-transaction) publishes the precedence, the substitution
  and the instruction to read the echoed card number rather than assuming the sent one survived.
  Preserved deliberately: adding a refusal would refuse a request the legacy screen accepted.
* **Remediation.** Complete.

**M-24 — Transaction add's `201` was documented as echoing the created transaction; every input
field comes back blank.**

* **Severity: Medium.** `INITIALIZE-ALL-FIELDS` runs **before** the success message is composed, so
  the amount in particular returns empty rather than as the value just posted. A client treating a
  blank amount in a `201` as a failed write, or as a zero amount, draws the wrong conclusion from a
  successful write.
* **Locator.** [`app/cbl/COTRN02C.cbl:L724-L734`] clears the screen for the next entry and then
  composes `'Transaction added successfully.  Your Tran ID is <id>.'` — two spaces, because the
  first `STRING` operand ends with one and the second begins with one — which is the only member that
  names the created transaction.
* **Status. Remediated.** [§13.3](#133-add-transaction) carries a cleared-fields table for the
  `201` arm and directs the caller to read the identifier from `Location` or from the message.
  Preserved deliberately rather than "improved" into an echo the source never produced.
* **Remediation.** Complete.

**M-25 — Bill payment's settled arm was documented as returning the balance and the account; three
of its five members are blank.**

* **Severity: Medium.** Both `accountId` and `currentBalance` come back blank on the `201`,
  space-filled to their map widths. A blank `currentBalance` in a `201` means the payment
  succeeded — not that the balance is unknown, and not that a field failed to serialise.
  Documented the other way round, it reads as a server-side fault on the one arm where nothing
  went wrong.
* **Locator.** [`app/cbl/COBIL00C.cbl:L524-L527`] performs `INITIALIZE-ALL-FIELDS` before composing
  `'Payment successful. '`, and [`:L560-L566`] moves `SPACES` over the three input members.
* **Status. Remediated.** [§14.1](#141-bill-payment) carries a settled-arm blanking table naming
  each member, states that the amount paid was the whole balance as it stood at the read and that
  the balance afterwards is zero by construction, and notes that `BillPaymentResponse` documents the
  same blanking in code so the two agree.
* **Remediation.** Complete.

**M-26 — The bill-payment and report confirmation gates were documented as prompting on blank and
absent only; all-NUL `LOW-VALUES` reaches the same prompt arm.**

* **Severity: Medium.** An untouched BMS field carries NUL bytes, and a client sending `"\u0000"`
  is stating "not answered". Both gates test `SPACES` **or** `LOW-VALUES` in one condition, so both
  prompt with `200`. Documented as blank-and-absent only, a client would expect `400` for that
  input and would treat a working handshake as a rejected request.
* **Locator.** Bill payment: `WHEN SPACES` and `WHEN LOW-VALUES` are two arms reaching the same
  `PERFORM READ-ACCTDAT-FILE` [`app/cbl/COBIL00C.cbl:L182-L184`]. Report:
  `IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES` [`app/cbl/CORPT00C.cbl:L464`].
* **Status. Remediated.** Both confirmation tables name `LOW-VALUES` on the prompt row, and both
  carry the contrast that makes the rule memorable: on the admin user identifier NUL bytes *are*
  refused, because there the source explicitly tests an identifier for `SPACES OR LOW-VALUES` and
  rejects, whereas a confirmation's unanswered state is exactly what those two values mean.
* **Remediation.** Complete.

**M-27 — The report period selectors were documented as "one of three"; there is no
mutual-exclusivity check and precedence decides silently.**

* **Severity: Medium.** A request populating two or three selectors is accepted. Monthly beats
  yearly, and yearly beats custom. A client populating monthly *and* custom, expecting either a
  refusal or its custom dates to be honoured, receives a **monthly** report — and because the custom
  arm never ran, its six date components are never validated, so a malformed custom range is
  reported as success.
* **Locator.** `PROCESS-ENTER-KEY` is an `EVALUATE TRUE` with three ordered arms, each testing
  `NOT = SPACES AND LOW-VALUES`: monthly [`app/cbl/CORPT00C.cbl:L213`], yearly [`:L239`], custom
  [`:L256`]. Nothing in the program tests for more than one being present.
* **Status. Remediated.** [§15.1](#151-submit-transaction-report) carries the precedence table with
  the three locators, states outright that there is no exclusivity check, and names the
  monthly-plus-custom outcome including the un-validated dates.
* **Remediation.** Complete.

**M-28 — User add's `201` was documented as returning a populated identifier, name and type; all
four are blank.**

* **Severity: Medium.** A client reading `userId` from the `201` gets an empty string rather than
  the identifier it just created. There is no server-assigned value to discover — the write is keyed
  on the identifier the caller supplied — so the correct instruction is to read it from the request,
  or from the message.
* **Locator.** `WHEN DFHRESP(NORMAL)` performs `INITIALIZE-ALL-FIELDS` **before** composing
  `'User <id> has been added ...'` [`app/cbl/COUSR01C.cbl:L251-L257`], clearing the entry screen for
  the next user. No password member appears on any arm.
* **Status. Remediated.** [§16.2](#162-add-user) carries a cleared-member table for the `201` arm
  with the source citation. The blanking is parity and is preserved rather than "improved" into an
  echo.
* **Remediation.** Complete.

**M-29 — User delete's success arm was documented as returning the identifier, names and type;
those are the *fetch* arm's values and are blank on the deleted arm.**

* **Severity: Medium.** Both arms answer `200` with the same five members, so the presence of a
  populated name is what distinguishes "here is the user, confirm the delete" from "the user is
  gone". Documented the other way round, a client cannot tell the two apart and may report a
  completed deletion as a pending confirmation, or the reverse.
* **Locator.** The same idiom as user add: `WHEN DFHRESP(NORMAL)` performs `INITIALIZE-ALL-FIELDS`
  before composing `'User <id> has been deleted ...'` [`app/cbl/COUSR03C.cbl:L313-L322`].
* **Status. Remediated.** [§16.4](#164-delete-user) carries a cleared-member table for the deleted
  arm and states explicitly that the populated name is the discriminator between the two `200`
  arms, together with the message text.
* **Remediation.** Complete.

**M-30 — Every paging cursor was described as sealed and page-bound, and on one operation the two
members named do not exist in the response at all.**

* **Severity: Medium.** The three paging operations do share one parameter vocabulary, which is what
  made the over-generalisation plausible. What differs is which response member carries the value to
  echo, and whether that value is opaque or a readable record key. A client following the old text on
  `GET /api/admin/users` would have read `firstCursor`/`lastCursor`, found neither, and echoed
  `null` — losing its position on every page turn with no error to explain it.
* **Locator.** `CardListResponse` and `TransactionListResponse` publish `firstCursor`/`lastCursor`;
  `UserListResponse` publishes `firstUserId`/`lastUserId`. Only `CardController` consults
  `SnapshotTokenService`, sealing each cursor to the page it was minted for; the transaction and user
  cursors are the raw record keys the source's `DFHPF7`/`DFHPF8` arms save, and are neither sealed
  nor page-bound.
* **Status. Remediated.** [§7.1.1](#711-the-cursor-is-not-the-same-object-on-all-three-operations)
  carries a per-operation table giving the member to echo, what the value is, and whether it is
  sealed and page-bound; the parameter rows in [§7.1](#71-paging-parameters) point at it rather than
  restating one rule for three different objects.
* **Remediation.** Complete.

**M-31 — A `422` arm was published on the constraint-refusal error code that no code path can
produce.**

* **Severity: Medium.** The `CARDDEMO-CONSTRAINT-REFUSED` row named `409` or `422`. A published
  status a client must handle but can never observe invites dead error-handling code, and dead
  error handling is untested error handling.
* **Locator.** `grep -rn 'UNPROCESSABLE_ENTITY' src/main/java` returns nothing: there is no `422`
  anywhere in the application. A constraint refusal *is* a conflict with the stored state, which is
  what `409` means.
* **Status. Remediated.** The row publishes `409` only. The documentation was corrected rather than
  the code, because adding a `422` arm to match a sentence would be a behaviour change made to
  protect the sentence.
* **Remediation.** Complete.

**M-32 — Sign on answers `503` where every other operation answers `502`, and the contract
published `502` for it.**

* **Severity: Medium.** A client branching on `502` for a store failure on sign on never matches,
  and a client that treats an undocumented `503` as "not my case" reports the wrong cause. The
  divergence is deliberate, so the defect was the documentation rather than the status.
* **Locator.** `AuthController` maps **both** `FileUnavailableException` and `FileAccessException`
  to `503`; the other six controllers map `FileAccessException` to `502`, and `MenuController` maps
  neither. Sign on is the only unauthenticated operation, so distinguishing "the credential store
  could not be opened" from "the credential store was read and the read failed" would tell an
  anonymous caller something about the deployment's internal state.
* **Status. Remediated.** [§8.1](#81-stable-error-codes) records the exception on the row, and
  [§9.1](#91-sign-on)'s operation table publishes `503` with the rationale beside it. Documented
  rather than aligned to the common contract, because aligning it would *add* that disclosure to the
  one operation that must not carry it.
* **Remediation.** Complete.

### 18.4 Low

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

### 18.5 Not available

The following cannot be established from the sources available and are recorded as
**Not available** with what would be needed, rather than guessed at.

Two things are kept separate here, because running them together is what makes such a section
wrong. **The implementation is available and static evidence about it is available**; what is
unavailable is a *specific* class of runtime evidence, namely a comparison against a captured run
of the legacy system. Recording the former as unavailable understates the delivered state as badly
as recording the latter as available would overstate it.

| Item | Status | What is needed |
|---|---|---|
| That the operations this document describes are implemented | **Available.** All 17 operations exist across the eight controllers, in a tree of 159 main sources holding 133 types. Reproduce with `find src/main/java -name '*.java' \| wc -l` and the operation census in [§2](#2-operation-inventory-why-there-are-exactly-17) | Nothing outstanding |
| Any statement that this API's implementation is **complete** | **Not available, and it is a different kind of claim from the rows around it.** Passing tests evidence the behaviour the tests assert, not the absence of behaviour nobody wrote a test for, so no volume of green runs establishes completeness | A stakeholder-agreed definition of complete, which no artefact in this repository supplies |
| That the test tiers covering them pass | **Available.** The recorded run, its exact command, its test counts and its merged line coverage against the enforced 0.80 floor are **owned by** [validation-gates.md](validation-gates.md) under [Gate 2](validation-gates.md#gate-2) and are cited from here rather than restated, so this page cannot drift from the ledger. The reports are written under `target/`, which is build output rather than a committed file, so the reading is **reproducible by that command rather than retained in the repository** | Nothing outstanding. To retain it, attach the `target/surefire-reports`, `target/failsafe-reports` and `target/site/jacoco` trees to a build record |
| Field-by-field agreement between this API's output and a run of the legacy system | **Available against two independent in-repository expectations; a captured z/OS run is Not available.** `src/test/resources/parity/gate1/` holds the frozen program's own captured output and `src/test/resources/expected/posttran/` holds a source-derived expectation, and the posting run is diffed against both. What remains outstanding is a run on the real runtime | A captured execution of the frozen COBOL on z/OS or an emulator, which would **corroborate** rather than replace either expectation. See [Gate 1](validation-gates.md#gate-1) |
| Pass or fail status of each of the eight validation gates | **Available.** All eight are executed by `src/test/java/com/cardemo/e2e/GateVerificationTest.java`, which writes its census values to `target/gate-verification/gate-verification-summary.properties`. The per-gate standing is **owned by** [validation-gates.md](validation-gates.md), which is the single ledger for it; no verdict is restated here | Per-gate detail, including what closes each remaining absence, is in [validation-gates.md](validation-gates.md) |
| Service-level objectives — target latency, throughput or availability per operation | **Not available.** The legacy system publishes no service-level objective anywhere in the corpus, so none may be invented | A stakeholder-agreed objective. Until then the performance gate records a **measured baseline**, not a target |
| A `mkdocs build --strict` result for this page | **Available, measured 6 August 2026.** `mkdocs build --strict` exits **0 with zero warnings and zero errors** against the real tree — MkDocs 1.6.1 with `techdocs-core` and `mermaid2` — with this page registered in the `nav` and every document it links to present. See [§18.6](#186-documentation-build-verification) | Nothing outstanding |
| A REST contract for CICS transaction `CDV1` | **Not available, and correctly so.** Its program is named twice in the resource definitions — `app/csd/CARDDEMO.CSD:L211` and `:L390` — and has no source anywhere in the repository ([§2](#2-operation-inventory-why-there-are-exactly-17)) | Nothing. There is nothing to translate, and no endpoint is invented for it |
| Interpretation of the `CRDSTP` row-type marker as a JSON value | **Not available.** It drove 3270 screen attributes, and no non-presentational meaning for it can be established from the source | Nothing. It is excluded as presentation chrome ([§12.1](#121-list-cards)) |

**There are no open `TODO` items in this document.** Every gap above is an explicit
*Not available* with a stated prerequisite, and every preserved behaviour has a tracked
finding identifier and a decision-log entry.

### 18.6 Documentation build verification

This page was verified against the repository's own MkDocs configuration — `techdocs-core`
and `mermaid2`, with `pymdownx.superfences` registering the Mermaid custom fence — rather
than assumed to render.

**Result: `mkdocs build --strict` completes with exit status 0, zero warnings and zero
errors.** Both preconditions that qualified this statement have been met - this page is
registered in the `nav`, and every document it links to is present - so the reading is now
unconditional, measured with MkDocs 1.6.1 on Friday 7 August 2026. That run publishes eight
pages - `index.md`, `project-guide.md`, `technical-specifications.md`, this page,
`architecture-before-after.md`, `onboarding-guide.md`, `validation-gates.md` and the static
`executive-presentation.html` - and every internal link in every one of them resolves.
Reproduce it with `mkdocs build --strict` from the repository root, MkDocs 1.6.1 with
`techdocs-core` and `mermaid2`.

**The rendered page carries 107 tables and 32 fenced code blocks, and all 92 of its distinct
in-page anchors resolve to a heading.** The page publishes 92 heading identifiers, so the two
figures being equal is itself a fact rather than a coincidence: every anchor resolves **and**
every heading is reachable from at least one link in the body.

*Three earlier readings are withdrawn, and the last of them is withdrawn for a reason worth
recording rather than a stale one.* The first published 83 tables with 81 anchors and the second
121 tables; both were taken before later sections were added. The third published **134 tables, 64
code blocks and 91 anchors**, and its two table-and-block figures were wrong on the day they were
taken - not out of date, but **measured against the wrong elements**. Counting every `<table`
element yields 139 on this page, of which **32 are the wrappers Pygments emits around fenced
blocks**, not content tables; and counting `<pre` elements yields 64 because a highlighted block
emits two, one for the line numbers and one for the code. The arithmetic is its own proof:
107 + 32 = 139, and 32 × 2 = 64. The figures above therefore count content tables and fenced
blocks, which is what a reader means by both words.

**Syntax-highlighting error tokens: 0.** This figure has now been three different values, and
the sequence is worth keeping because each reading was true when it was taken. An early
revision claimed **zero**; a later one corrected that to **14**, all in one block, because the
account-update example in [§11.2](#112-update-account) is fenced as an HTTP request and carried
the placeholder `{ <the oldDetails object from GET /api/accounts/{accountId}, verbatim> }` in
its body - an object the caller copied from the view response, which is not parseable JSON. That
revision declined to change a correct example to make a metric look better, which was the right
call at the time.

**It is zero again, and not because the example was rewritten to flatter the count.** The
as-displayed snapshot is now a single **opaque string** rather than a readable object
([§11.2.3](#1123-how-the-snapshot-travels)), so the placeholder is a quoted JSON string -
`"snapshot": "<the snapshot string from GET /api/accounts/{accountId}, verbatim>"` - which is
valid JSON and highlights cleanly. The metric moved as a **side effect** of a security fix, and
it is recorded that way rather than presented as a tidying-up: the example still shows exactly
what a caller must send, and it now happens to parse.

Two classes of warning would make this page fail a strict build, and both are **closed**. They
are kept here with their closure rather than deleted, because each names a condition a future
change could reintroduce:

| Condition | Why it mattered | State |
|---|---|---|
| `api-contracts.md` was not included in the `nav` configuration | The `nav` entry was added by the `mkdocs.yml` update, which was a separate change | **CLOSED, 7 August 2026.** `API Contracts: api-contracts.md` is in the `nav`, so this page publishes; and `validation.nav.omitted_files: warn` is set, so a future omission fails a strict build instead of passing at INFO level — see [§19](#19-contract-change-control) |
| Links to `validation-gates.md`, `architecture-before-after.md`, `onboarding-guide.md` and `executive-presentation.html` had no target | **CLOSED, 7 August 2026.** All four documents now exist alongside this one, so all four links resolve and `mkdocs build --strict` exits 0 with **0 warnings** - down from 20, of which 4 were this page's | Nothing outstanding. The link filenames were always the ones the `nav` carries, which is exactly why they were correct while still broken and were not changed to work around the warning |

Because `--strict` promotes warnings to errors, neither of the above could be left
outstanding, and neither is: a nav omission means the page silently never appears, and a
dangling link means a reader cannot reach the document that owns the detail this page
deliberately does not duplicate. **One correction to the reasoning above, learned by
measurement:** a nav omission was *not* in fact caught by `--strict`, because MkDocs reports
it at INFO level by default. `mkdocs.yml` now sets `validation.nav.omitted_files: warn`,
which is what makes that half detectable at all - verified by mutation, since removing the
setting returns the same omission to a silent exit 0.

One convention this page relies on and that is easy to break: a root-level document such as
`../DECISION_LOG.md` is cited in a **code span** and never as a Markdown link, because MkDocs
cannot resolve a link target outside `docs_dir` and `--strict` would fail the build. Links
inside `docs/` are ordinary Markdown links.

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
