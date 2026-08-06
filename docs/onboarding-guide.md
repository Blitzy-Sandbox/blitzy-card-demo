<!--
  Application : CardDemo
  Type        : Documentation - developer onboarding and continued-development guide
  Function    : Orient a new contributor to the frozen COBOL corpus in app/ and the
                Java 25 / Spring Boot 3.5.11 implementation in src/, and teach the
                fidelity rules that govern every change to either half.
  Derived from: CONTRIBUTING.md, README.md, app/csd/CARDDEMO.CSD, app/catlg/LISTCAT.txt,
                app/cbl/**, app/cpy/**, app/cpy-bms/**, app/jcl/**, app/proc/**,
                app/data/ASCII/**

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
-->

# Onboarding Guide

**CardDemo** &middot; Licensed under the Apache License, Version 2.0 &middot; Copyright
Amazon.com, Inc. or its affiliates. All Rights Reserved. See `../LICENSE` and
`../NOTICE`. This page carries the Markdown equivalent of the source banner that
opens every member of the frozen corpus &mdash; a convention verified across `app/cbl`
(28 of 28), `app/cpy-bms` (17 of 17), `app/bms` (17 of 17), `app/jcl` (28 of 29) and
`app/cpy` (12 of 28), and extended to every file this migration adds.

---

## 0. How to read this page

This is the guide for a developer who has just been handed the repository and has to make a
correct change to it. It is written to the four bullets that **Rule 1: Build Verify**,
clause E, requires of every component's documentation. Each bullet has one unmistakable
home:

| Rule 1 clause E requires | Where it is satisfied here |
|---|---|
| **What it does** | [§1 What this is](#1-what-this-is), [§2 The frozen corpus](#2-the-frozen-corpus-and-how-to-navigate-it), [§6 Package and module tour](#6-package-and-module-tour) |
| **How to run, build and test** | [§3 Prerequisites](#3-prerequisites-and-the-recorded-environment), [§5 Build, test and run](#5-build-test-and-run), [§7 Tests and validation gates](#7-test-taxonomy-and-the-validation-gates) |
| **Key configs and defaults** | [§4 Configuration and the four profiles](#4-configuration-and-the-four-profiles) |
| **Common failure modes and troubleshooting** | [§10 Troubleshooting](#10-troubleshooting) &mdash; with [§8 Fidelity traps](#8-the-fidelity-traps) as the upstream cause of most of them |

Two conventions apply throughout, both from Rule 1 clause F. Every factual claim carries a
`[path:locator]` citation to the artefact that proves it, so you can check any statement
here against the source rather than trusting it. And where something genuinely is not known
or has not been produced, this page says **"Not available"** and names what is needed
&mdash; it never fills the gap with an assumption. [§13](#13-findings-severity-classified)
collects the known issues with a severity and a remediation for each; [§14](#14-status-not-available-register-and-cross-references)
collects the outstanding items.

**Read [§8 The fidelity traps](#8-the-fidelity-traps) before you write any code.** It is the
highest-value section on this page. Every trap in it is a mistake a competent Java developer
makes *by default*, because the idiomatic Java choice and the behaviour-preserving choice
diverge.

---

## 1. What this is

**CardDemo is a credit-card management application.** It was originally implemented in IBM
Enterprise COBOL running under CICS, VSAM, JCL and BMS on z/OS. This repository holds two
things at once:

* **`app/` &mdash; the original COBOL corpus, frozen.** 28 programs totalling 19,254 lines,
  28 copybooks, 17 BMS mapsets with their 17 generated symbolic maps, 29 job-control
  members, 2 procedures, 1 control card, the CICS resource definitions, the VSAM catalogue
  listing and 9 ASCII data fixtures.
* **`src/` &mdash; a Java 25 and Spring Boot 3.5.11 reimplementation**, laid out in the
  Maven standard directory structure under the package root `com.cardemo`.

**Both live in the same repository, side by side.** There is no separate repository and no
wrapper sub-directory: `src/` sits at the repository root next to `app/`, `docs/`,
`diagrams/` and `samples/`. The migration is **purely additive** &mdash; nothing under
`app/` is edited, and [§2.1](#21-app-is-read-only) explains why that is not negotiable.

The target of the migration is **behavioural parity**, not improvement. That single sentence
explains most of what is otherwise surprising about this codebase: preserved quirks,
one-to-one paragraph correspondence, byte-exact record lengths and formulas transcribed in a
shape a reviewer would want to simplify. Where a deviation from the legacy behaviour is
unavoidable or genuinely beneficial, it is **labelled a deviation, justified and logged** in
`../DECISION_LOG.md` &mdash; never absorbed silently.

### 1.1 The domain, in the order you need it

Learn these ten record types before reading any code. Every entity, DTO and table in the
Java tree traces back to one of them, and the field names are recognisably the COBOL names.

| Business object | What it is | Legacy layout |
|---|---|---|
| **Account** | The credit-card account: balances, credit and cash limits, the current-cycle credit and debit accumulators, open, expiry and reissue dates, and the group identifier that selects an interest rate | [`app/cpy/CVACT01Y.cpy`] &mdash; 300 bytes, key length 11 |
| **Card** | A plastic card issued against an account, keyed by the 16-digit card number | [`app/cpy/CVACT02Y.cpy`] &mdash; 150 bytes, key length 16 |
| **Card cross-reference** | The card-number-to-account-and-customer join. Nearly every read path starts here | [`app/cpy/CVACT03Y.cpy`] &mdash; 36 populated bytes in a 50-byte slot |
| **Customer** | The person: names, three address lines, state and country, postal code, two telephone numbers, government-issued identifier, date of birth, FICO score | [`app/cpy/CVCUS01Y.cpy`] &mdash; 500 bytes, key length 9 |
| **Transaction** | A posted transaction: identifier, type, category, source, description, amount, merchant details, card number and two timestamps | [`app/cpy/CVTRA05Y.cpy`] &mdash; 350 bytes, key length 16 |
| **Daily transaction** | The staging record read by the posting job before validation. Same 350-byte geometry | [`app/cpy/CVTRA06Y.cpy`] |
| **Transaction type** and **category** | The two-character type code and the six-character type-plus-category code that classify a transaction | [`app/cpy/CVTRA03Y.cpy`], [`app/cpy/CVTRA04Y.cpy`] &mdash; 60 bytes each |
| **Transaction category balance** | Balance accumulated per account, per type, per category. The interest job walks this file in key order | [`app/cpy/CVTRA01Y.cpy`] &mdash; 50 bytes, composite key length 17 |
| **Disclosure group** | The interest rate for a group, type and category triple, with a literal `DEFAULT` group as the fallback | [`app/cpy/CVTRA02Y.cpy`] &mdash; 50 bytes, composite key length 16 |
| **User security** | The sign-on record: identifier, first and last name, password and a one-character type, `A` for administrator or `U` for standard user | [`app/cpy/CSUSR01Y.cpy`] &mdash; 80 bytes, key length 8 |

**The application has two halves, and they behave very differently.**

**The online half** is 17 screen transactions: sign on, two menus, account view and update,
card list, detail and update, transaction list, detail and add, bill payment, report
submission, and four user-administration screens. On the mainframe each was a
pseudo-conversational CICS program exchanging a BMS screen with a 3270 terminal and carrying
its state in the COMMAREA. In Java each is a stateless REST operation. Field-level contracts
are in [api-contracts.md](api-contracts.md).

**The batch half** is a JCL job stream. Two cycles matter most:

* **Daily transaction posting** reads the staging file, validates each record against the
  cross-reference and the account, and either posts it &mdash; updating the category
  balance, the account and the transaction file together &mdash; or writes a reject record
  with a reason code [`app/jcl/POSTTRAN.jcl`], [`app/cbl/CBTRN02C.cbl`].
* **Interest calculation** walks the category-balance file, looks up a rate per group, type
  and category, computes monthly interest, generates a synthetic transaction per category
  and updates the account [`app/jcl/INTCALC.jcl`], [`app/cbl/CBACT04C.cbl`].

Two output streams follow from them: **customer statements**, produced in a plain-text and
an HTML form [`app/jcl/CREASTMT.JCL`], [`app/cbl/CBSTM03A.CBL`]; and a **transaction
report** over a date range, produced from a sorted backup
[`app/proc/TRANREPT.prc`], [`app/cbl/CBTRN03C.cbl`]. A fifth job combines the transaction
backup with the interest job's output [`app/jcl/COMBTRAN.jcl`].

For the architectural before-and-after &mdash; including the entity-relationship diagram and
the mechanism-substitution table &mdash; read
[architecture-before-after.md](architecture-before-after.md). It is not repeated here.

---

## 2. The frozen corpus, and how to navigate it

### 2.1 `app/` is read-only

**Nothing under `app/` may be edited, reformatted, renamed, moved or deleted.** This is not
tidiness; the directory holds three roles simultaneously, and an edit destroys all three at
once:

1. **The parity oracle.** Output is compared against legacy behaviour. Change the source and
   there is nothing left to compare against.
2. **The field-contract source.** Every entity column width, every DTO field name, type and
   length is derived from a copybook or a symbolic map. Change a layout and the Java shape
   silently stops matching the contract it was derived from.
3. **The traceability anchor.** `../TRACEABILITY_MATRIX.md` cites
   paragraph line ranges against a fixed commit SHA,
   `7756d895ffeb65f7ea72aaa609e356d9899afcec` (short `7756d89`). Inserting or removing a
   single line invalidates every citation after it.

The corpus being frozen is verifiable rather than merely asserted: `git diff 7756d89 HEAD --
app/` returns empty, and that check is recorded in the matrix's own verification log.

### 2.2 Directory tour, with verified counts

Counts below were produced by machine at the anchor commit. Where a naive glob gets the
wrong answer, the warning is part of the row &mdash; these are not hypothetical.

| Path | Contents |
|---|---|
| `app/cbl/` | **28 programs, 19,254 lines.** :warning: `CBSTM03A.CBL` (924 lines) and `CBSTM03B.CBL` (230) carry an **uppercase `.CBL` extension**. A `*.cbl` glob finds 26 files and 18,100 lines &mdash; it silently drops the entire statement-generation program. **Always match case-insensitively.** |
| `app/cpy/` | 28 copybooks, 2,614 lines. Includes the uppercase `COSTM01.CPY` and `UNUSED1Y.cpy`, which has **zero `COPY` references repository-wide** and is dispositioned as documented dead code rather than deleted. |
| `app/bms/` | 17 mapsets, 4,472 lines &mdash; the screen definitions. |
| `app/cpy-bms/` | 17 generated symbolic maps, 5,632 lines, **441 input fields** in total. These are the DTO field contracts: names, types and lengths come from here, not from judgement. |
| `app/jcl/` | **29 members** &mdash; 28 with a lowercase `.jcl` extension plus **`CREASTMT.JCL`, uppercase**. That one member is the sole source for statement generation, and a `*.jcl` glob drops it without complaint. |
| `app/proc/` | 2 procedures &mdash; the report and REPRO procedures, carrying sort specifications and symbol definitions. |
| `app/ctl/` | 1 IDCAMS control card. |
| `app/csd/CARDDEMO.CSD` | The CICS resource definitions: transactions, programs, mapsets, **8 files** and **1 transient data queue**. This is the endpoint and authorisation inventory. |
| `app/catlg/LISTCAT.txt` | 3,956 lines of authoritative physical specification: **10 base clusters, 3 alternate indexes, 3 paths, 7 generation data group bases.** |
| `app/data/ASCII/` | 9 seed and test fixtures. These are the authoritative seed and test input. |
| `app/data/EBCDIC/` | **12 `.PS` files plus a `.gitkeep`** &mdash; byte-level codepage reference **only**. **Never parsed by the build**, and no transcoding utility exists or should be written. |
| `diagrams/` | 6 legacy architecture illustrations, referenced by path and not embedded. |
| `samples/` | 8 z/OS build artefacts &mdash; three compile templates, three build procedures and two binary emulator runtime bundles. **Out of scope.** Nothing is ported from them; `pom.xml` supersedes the set conceptually. |

For the authoritative legacy transaction, program and job-control inventory tables, read
`../README.md` under *Application Inventory*. They are **linked, not restated**.

### 2.3 The 17-versus-18 reconciliation

You will notice a discrepancy in the CICS definitions within your first hour, and the wrong
conclusion is to invent an endpoint. Here is the resolution.

`app/csd/CARDDEMO.CSD` defines **18 transactions and 18 programs but only 17 mapsets**.
Seventeen transaction-and-program pairs have both a source file and a mapset. The eighteenth
pair is `CDV1` to `COCRDSEC`, described in the CSD as a developer transaction
[`app/csd/CARDDEMO.CSD:L388-L390`].

**`COCRDSEC` has no source file anywhere in this repository.** Its only two occurrences
repository-wide are the two CSD definitions themselves
[`app/csd/CARDDEMO.CSD:L211`] and [`:L390`]. There is nothing to translate. **No route, no
operation and no placeholder is published for it, and none should be added.**

So: **17 sourced screen programs + 1 orphan CSD definition = the 18 CSD entries, which
yields 17 REST operations.** Independent corroboration: the *Online* inventory table at
[`README.md:L213-L231`] lists exactly 17 rows and contains no row for that transaction.

Three further programs are absent from the CSD for good reasons, which is worth knowing
before you go looking: `CSUTLDTC` is a statically-called date utility rather than a
transaction, and `CBSTM03A` and `CBSTM03B` are batch programs.

### 2.4 Two clusters of files that surprise people

* **Four batch-only datasets have no CICS definition at all.** The eight file names in the
  CSD are `ACCTDAT`, `CARDAIX`, `CARDDAT`, `CCXREF`, `CUSTDAT`, `CXACAIX`, `TRANSACT` and
  `USRSEC`. `TCATBALF`, `DISCGRP`, `TRANCATG` and `TRANTYPE` are missing from that list
  because they are reached only from batch. That absence is evidence, not an oversight, and
  it shapes both the authorisation model and the integration-test surface.
* **Three copybooks are not data layouts**, so they have no entity or DTO counterpart.
  `CSMSG02Y` is internally titled `CABENDD.CPY` and holds abend work areas, which become the
  field set of the fatal exception type. `CSSTRPFY` is procedural &mdash; it is copied into
  the `PROCEDURE DIVISION` and evaluates the attention identifier. `CSSETATY` is a
  parameterised template resolved through `COPY … REPLACING`. Each maps onto a framework
  mechanism instead of a type.

---

## 3. Prerequisites and the recorded environment

### 3.1 What you need on the host

| Requirement | Version | Why exactly this |
|---|---|---|
| **JDK** | **25** | `maven.compiler.release` is `25`, and the Maven Enforcer plugin asserts a floor of `[25,)`. The build refuses to run on an older toolchain rather than silently producing different bytecode. The provisioned reference is OpenJDK 25.0.3. |
| **Apache Maven** | **3.9.11** | Pinned by `.mvn/wrapper/maven-wrapper.properties` and asserted by Enforcer as `[3.9.11,)`. **Use the checked-in wrapper `./mvnw`** (or `mvnw.cmd` on Windows), which downloads exactly that version &mdash; a pre-installed Maven is not required. |
| **Docker Engine with Compose v2 or later** | any working daemon | Needed for `docker compose` and for the Testcontainers-backed test tiers. Confirm the daemon answers with `docker info` before building. |
| **Git** | any recent version | |

**Nothing else is required on the host.** In particular the **LocalStack and AWS
command-line tools are not needed**: LocalStack runs as a compose service and the
application reaches it through the AWS SDK, never through a CLI. Their absence blocks no
documented workflow.

**Do not "helpfully" upgrade Maven.** Maven 4 exists, but the plugin ecosystem this build
depends on is validated against the 3.9 line, so **3.9.11 is pinned deliberately**. The
`maven-enforcer-plugin` **3.5.0** asserts both the Java 25 and the Maven 3.9.11 floors
precisely so that the build cannot silently run on a wrong toolchain and produce a result
nobody can reproduce.

### 3.2 Two dated readings of the authoring host

Host state decides which workflows are runnable, so it is published as **dated
measurements** rather than as a property of the project. Two readings exist. The second
supersedes the first where they differ, and the differences are stated rather than quietly
folded away. This mirrors the convention already established in
[validation-gates.md §2](validation-gates.md#env-first), which is the authoritative ledger
for host evidence.

**First reading &mdash; Thursday, 30 July 2026 at 06:56 UTC.**

| Tool | Result |
|---|---|
| `docker --version` | **Docker version 29.6.2, build dfc4efb** |
| `docker compose version` | **Docker Compose version v5.3.1** |
| `docker info --format '{{.ServerVersion}}'` | **29.6.2** &mdash; daemon reachable |
| `java`, `javac`, `mvn` | **NOT FOUND** |
| `localstack`, `aws` | **NOT FOUND** |
| `mkdocs` | **NOT FOUND** |

**What that reading meant.** **A container runtime was available**, so the compose topology
could be brought up and the container-dependent test tiers were feasible. What was missing
was the **host JDK and Maven**, so a host-native `./mvnw` could not run at all; the
documented remediation was &mdash; and remains &mdash; the **pinned-container build** in
[§5.2](#52-building-when-the-host-has-no-jdk). Note carefully which component was *not* the
gap: Docker Engine and `docker compose` were the parts that worked.

> **Any statement that Docker or a container runtime is unavailable for this project is
> false.** The claim appears in an earlier generation of
> [technical-specifications.md](technical-specifications.md) and is withdrawn. It must not be
> repeated, and no gate may be marked blocked on that basis.

**Second reading &mdash; Thursday, 6 August 2026 at 14:50 UTC**, taken while authoring this
page, by invoking each tool and copying its own output.

| Tool | Result | Change since 30 July |
|---|---|---|
| `docker --version` | Docker version 29.7.0, build c1eba93 | patch release |
| `docker compose version` | Docker Compose version v5.3.1 | unchanged |
| `java -version` | openjdk 25.0.3 2026-04-21 LTS, Temurin-25.0.3+9 | **has since been provisioned** |
| `javac -version` | javac 25.0.3 | **has since been provisioned** |
| `mvn -v` | Apache Maven 3.9.11 | **has since been provisioned** |
| `localstack --version` | LocalStack CLI 4.14.0 | **has since been provisioned**; required by nothing |
| `aws --version` | aws-cli 1.46.0 | **has since been provisioned**; required by nothing |
| `mkdocs --version` | mkdocs 1.6.1 | **has since been provisioned** |

**What the second reading means, and what it does not.** The host-native path is now
available as well as the container path, and documentation rendering can now be verified
&mdash; [§7.5](#75-documentation-rendering) records what an actual `mkdocs build --strict`
run reported, including its failure. **Neither reading is evidence that anything passed.**
Provisioning a toolchain and running a gate are different acts, and only the second produces
an artefact. Gate results live in [validation-gates.md](validation-gates.md) and nowhere
else.

---

## 4. Configuration and the four profiles

### 4.1 Checkout and branch

Work against the latest source on the **`main`** branch [`CONTRIBUTING.md:L26`], and check
open and recently merged pull requests before starting so effort is not duplicated
[`:L27`]. Open an issue to discuss anything significant first [`:L28`].

### 4.2 The signing key, and how secrets are handled

**The JWT signing key is resolved from an environment variable in every one of the four
profiles, with fail-fast on absence and no committed default.** An unset variable aborts
application start-up. That is deliberate: a default would be a credential in the repository,
and a silent fallback would be worse than a crash because it would look like it worked.

The variable is `JWT_SIGNING_KEY`. To configure a local run:

```bash
cp .env.example .env      # then fill in the empty values locally
```

* **`.env.example` at the repository root documents the variable names and nothing else.**
  Every value in it is **blank or an obvious placeholder &mdash; never a real secret.** It is
  a contract about names, and a test asserts that it stays that way.
* **`.env` is git-ignored and must never be committed.** Populate it locally.
* **No key material, token or password value appears anywhere on this page**, and none
  should appear in any file you add. See [§11.4](#114-never-commit-a-secret).

A hardcoded signing key was a **High**-severity defect in the prior implementation
[`docs/project-guide.md:L52`], [`:L215`]. Environment indirection is precisely what closes
it, and it is tracked as finding `H-1` in
[validation-gates.md §12](validation-gates.md#findings).

### 4.3 The four profiles

| Profile | File | What differs |
|---|---|---|
| **base** | `application.yml` | Common configuration. Publishes the signing key as an environment reference with no default. |
| **local** | `application-local.yml` | LocalStack endpoint override and the compose PostgreSQL instance. **This is the profile the compose stack activates by default.** |
| **test** | `application-test.yml` | Testcontainers-backed database and cloud services, for the integration and end-to-end tiers. |
| **prod** | `application-prod.yml` | A **least-privilege production profile with every secret externalised.** It exists specifically to satisfy Rule 1 clause D; its absence was a **High**-severity gap in the prior implementation [`docs/project-guide.md:L51`], tracked as `H-2`. |

Demo users are seeded **only** under the `local` and `test` profiles &mdash; see
[§7.3](#73-the-user-records-and-why-you-cannot-sign-in-with-a-plaintext-password). For the
full profile-by-profile breakdown read
[architecture-before-after.md §6.2](architecture-before-after.md#62-configuration-four-profiles-and-what-differs);
it is not duplicated here.

---

## 5. Build, test and run

**Always use the wrapper, never a bare `mvn`.** The wrapper is what pins Maven 3.9.11, and
`.mvn/jvm.config` is what passes the flag that stops JDK 25 emitting `sun.misc.Unsafe`
deprecation warnings from inside the build tool itself against a tree that is otherwise
warning-free. A bare `mvn` bypasses both.

### 5.1 The commands

```bash
./mvnw clean verify                       # full build: compile, all test tiers, coverage, scan
./mvnw test                               # unit tier only (Surefire 3.5.4)
./mvnw verify -DskipUnitTests             # integration and end-to-end tiers (Failsafe 3.5.4)
./mvnw -B -ntp -Ddependency-check.skip=true clean verify   # fast local iteration
```

The last form skips the vulnerability scan. **Treat it as an iteration shortcut, not as a
pass** &mdash; a complete `verify` includes the scan.

**What `verify` enforces.** Every item below **fails** the build rather than warning:

| Gate in the build | Tool and version | Where the evidence lands |
|---|---|---|
| **Warnings are errors** | `maven-compiler-plugin` **3.14.1** with `-Xlint:all` and `-Werror` [`pom.xml:L1232-L1233`] | Compiler output |
| **Undocumented public surface is an error** | `maven-javadoc-plugin` **3.11.2** with `doclint` at `all` and `failOnWarnings` | Javadoc output |
| **Toolchain floor** | `maven-enforcer-plugin` **3.5.0**, Java `[25,)` and Maven `[3.9.11,)` | Enforcer output |
| **Unit tier** | `maven-surefire-plugin` **3.5.4** | `target/surefire-reports/` |
| **Integration and end-to-end tiers** | `maven-failsafe-plugin` **3.5.4** | `target/failsafe-reports/` |
| **Coverage** | JaCoCo **0.8.12**, minimum **0.80 LINE** | **`target/site/jacoco/index.html`** |
| **Vulnerabilities** | OWASP dependency-check **12.1.0**, fails at CVSS 7.0 or above &mdash; zero High and zero Critical | **`target/dependency-check-report.html`** |

**`-Werror` is intentional and is not to be worked around.** A warning is a real signal;
silence it by fixing the cause, not by adding a blanket `@SuppressWarnings` at class or
package scope. A narrowly scoped suppression with a comment explaining why the warning is
wrong is a different thing and is acceptable.

### 5.2 Building when the host has no JDK

This is the remediation for the first reading in [§3.2](#32-two-dated-readings-of-the-authoring-host), and it needs only a
container runtime. The image is the same one the `Dockerfile` build stage pins &mdash; by tag
**and** digest, so it is a verifiable reference rather than a floating one:

```bash
docker run --rm \
  -v "$PWD":/workspace -w /workspace \
  -v "$HOME/.m2":/root/.m2 \
  maven:3.9.11-eclipse-temurin-25 \
  mvn -B -ntp clean verify
```

Mount the working tree and a **persistent Maven cache** &mdash; without the second mount
every run re-downloads the whole dependency set. That container already carries Maven 3.9.11
on JDK 25, so `mvn` is invoked directly rather than through the wrapper. The
Testcontainers-backed tiers additionally need a Docker daemon reachable from *inside* the
container; expect them to fail fast without one. For the digest-pinned form of this command
see `../README.md` under *Build, run and verify*.

### 5.3 Running the full topology

```bash
docker compose up --build          # foreground
docker compose up -d --wait        # detached; returns when every healthcheck is healthy
```

That starts **six services**. Every image is pinned to a tag **and** a digest.

| Service | Host port | Role |
|---|--:|---|
| `app` | 8080 | The single CardDemo JAR &mdash; REST surface, batch jobs and Actuator |
| `postgres` | 5432 | PostgreSQL 16, the VSAM replacement |
| `localstack` | 4566 | S3, SQS FIFO and SNS |
| `jaeger` | 16686 UI, 4318 OTLP/HTTP | Trace collection and search |
| `prometheus` | 9090 | Scrapes `/actuator/prometheus` |
| `grafana` | 3000 | Dashboards, provisioned from `observability/grafana/` |

`localstack-init/init-aws.sh` provisions **exactly three S3 buckets** &mdash;
`carddemo-batch-input`, `carddemo-batch-output` (**versioning enabled on the output bucket
only**) and `carddemo-statements` &mdash; the FIFO queue **`carddemo-report-jobs`** (created
as `carddemo-report-jobs.fifo`, since the suffix is mandatory for a FIFO queue) and **one**
SNS topic, `carddemo-notifications`. An earlier revision also created an alerts topic; it was
removed because least privilege forbids provisioning a delivery surface with no consumer.

**The script is idempotent**, so repeated `docker compose up` cycles **converge** rather than
failing on resources that already exist. If you see a bucket-already-exists error, that is a
bug in the script, not expected behaviour.

Reaching the running stack:

```bash
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:8080/actuator/health/liveness
curl -fsS http://localhost:8080/actuator/info
docker compose ps
docker compose logs -f app
```

Jaeger's UI is on port 16686 and Grafana's on 3000. **Three Actuator endpoints are exposed
and no others** &mdash; `health` (with its `liveness` and `readiness` groups) and `info` are
anonymous so a container probe works before any credential exists; `prometheus` requires
HTTP Basic and answers `401` until `METRICS_SCRAPE_USERNAME` and `METRICS_SCRAPE_PASSWORD`
are both non-blank. **That refusal is the intended behaviour, not a fault** &mdash; a
deployment that forgets the credential loses metrics visibly instead of publishing them
anonymously. Details are in
[api-contracts.md §2.2](api-contracts.md#22-operational-endpoints-and-the-scrape-credential).

**Four deliberate security properties of the stack, not to be relaxed for convenience:**
there is **no Docker socket mount**, **no privileged container**, **no host networking**
(published ports bind to `127.0.0.1` by default) and **no `latest` image tag** anywhere.
Each one is a decision; loosening any of them to make something work locally is a change that
needs justification, not a shortcut.

### 5.4 Flyway and the database

Flyway applies three migrations on start-up, in prefix order:
`V1__create_schema.sql`, then `V2__create_indexes.sql`, then `V3__seed_data.sql`. Ordering
depends on the `V1__` / `V2__` / `V3__` prefixes, not on the descriptive tails, so shorter
aliases you may see in prose are harmless &mdash; recorded as finding `M-2`.

`V3__seed_data.sql` carries two obligations worth knowing before you touch it: it decodes
zoned-decimal overpunch signs **position-aware from the PIC clauses**
([§8.4](#84-zoned-decimal-overpunch-signs)) and it stores the seeded user passwords **only**
as BCrypt hashes ([§7.3](#73-the-user-records-and-why-you-cannot-sign-in-with-a-plaintext-password)).

### 5.5 Running a batch job

**Jobs do not auto-launch on start-up.** `spring.batch.job.enabled` is `false` deliberately,
because the framework default would run every job on every boot. Launching is explicit and
goes through the orchestrator, which reads one property:

```bash
java -jar target/carddemo-*.jar \
  --carddemo.batch.launch=dailyTransactionPostingJob

java -jar target/carddemo-*.jar \
  --carddemo.batch.launch=interestCalculationJob \
  --carddemo.batch.launch.parm-date=<ten-character date>

java -jar target/carddemo-*.jar \
  --carddemo.batch.launch=transactionReportJob \
  --carddemo.batch.launch.start-date=<date> \
  --carddemo.batch.launch.end-date=<date>
```

`carddemo.batch.launch` has **no default**, deliberately: an absent value must mean "this is
a server", not "run something". Six bean names are launchable &mdash; the whole stream or any
single stage. The date parameters correspond to the legacy job parameters at
[`app/jcl/INTCALC.jcl:L22`] and [`app/proc/TRANREPT.prc:L41-L42`]; note from
[§8.6](#86-formula-shape-not-just-value) that the interest date is **not** an ISO date.

**The report path is queue-driven.** `POST /api/reports` publishes a job-submission message
to the FIFO queue, which is the direct replacement for `EXEC CICS WRITEQ TD QUEUE('JOBS')` in
`CORPT00C`. A listener drains that queue and launches the job, replacing the JES2 internal
reader: there is exactly one `@SqsListener` in the tree, `drainReportJobQueue` at
[`src/main/java/com/cardemo/config/BatchConfig.java:L1231`], made idempotent through the
message deduplication identifier. (A remark in `../README.md` that no listener
exists predates it; see finding `L-3` in [§13](#13-findings-severity-classified).)

### 5.6 All cloud interaction targets LocalStack

**Every S3, SQS and SNS call goes to LocalStack. There are zero live AWS credentials
anywhere in this repository, and no code path may reach a real AWS endpoint.** If you find
yourself needing a real account to run something, that is a defect in what you are building,
not a missing prerequisite.

---

## 6. Package and module tour

Fourteen **functional** packages sit under `com.cardemo`. They are realised as **26 physical
packages**, because `service` splits into eight domain sub-packages and `batch` into four,
and **every physical package carries its own `package-info.java`** naming the COBOL artefacts
it derives from. Read those files first when you enter an unfamiliar package &mdash; they are
the same orientation this section gives, in code, next to the thing being described.

| Package | Classes | What it does, and what it came from |
|---|--:|---|
| `config` | 6 | Security, batch, AWS, JPA, observability and web configuration. Derived from the CSD file-control table and the JCL job topology. |
| `security` | 4 | Token issue and validation, the authentication filter, user details loading. Replaces COMMAREA propagation across `EXEC CICS XCTL`. Derived from `COSGN00C` and `CSUSR01Y`. |
| `model.entity` | 11 | One entity per VSAM base cluster, from the 11 record-layout copybooks. |
| `model.key` | 3 | `@EmbeddedId` composite keys for the three composite-key clusters, **preserving COBOL field order** so key-order-sensitive browses behave identically. |
| `model.enums` | 4 | User type, file status, transaction source, and `RejectCode` with **exactly five** constants. |
| `model.dto` | 29 | Request, response, row, page and masking types. Field names, types and lengths come from the **441** symbolic-map input fields. |
| `repository` | 11 | Spring Data JPA interfaces replacing the VSAM access verbs. Three derived finders correspond to the **three** alternate indexes. |
| `service` | 21 | One bean per online program plus four shared services &mdash; see [§6.1](#61-why-there-are-21-services). |
| `controller` | 8 | The 17 REST operations, from the 17 CSD transactions. |
| `batch.jobs` | 6 | Five jobs plus the orchestrator &mdash; see [§6.3](#63-the-six-batch-jobs). |
| `batch.processors` | 5 | The per-record bodies of the five batch programs. |
| `batch.readers` | 7 | Five dataset readers plus the backup and concatenated readers. |
| `batch.writers` | 3 | The transaction, reject and statement writers, each at a fixed record length. |
| `exception` | 9 | A base type plus the `FILE STATUS` and response-code translations, including the fatal type carrying the four abend fields from `CSMSG02Y`. |
| `observability` | 4 | Correlation filter, metrics registration, health indicators and a templated-URI observation convention. |

Three of those counts exceed the originally planned figure, and the reason is recorded rather
than left as an apparent inconsistency: `model.dto` was planned as 16 **named DTO types** and
is delivered as 29 classes because request and response types are separated per operation;
`security` gained a snapshot-token service that supports the stateless account-update
snapshot contract ([§8.9](#89-the-date-of-birth-offset-asymmetry)); and `observability`
gained the templated-URI convention that keeps metric cardinality bounded. The full ledger is
[architecture-before-after.md §8.1](architecture-before-after.md#81-figure-verification-ledger).

### 6.1 Why there are 21 services

The figure looks arbitrary until it is decomposed, so here it is explicitly:

* **17 online-program services** &mdash; one per sourced screen program: account view and
  update; card list, detail and update; transaction list, detail and add; bill payment;
  report submission; sign-on; main and admin menus; and the four user-administration
  services.
* **`DateValidationService`** &larr; `CSUTLDTC` together with its two work-area copybooks
  `CSUTLDPY` and `CSUTLDWY`. One injected bean subsumes all three.
* **`ValidationLookupService`** &larr; the `CSLKPCDY` 88-level tables, externalised as
  **three classpath JSON resources** rather than generating over a thousand Java constants.
* **`FileStatusMapper`** &larr; the universal `FILE STATUS` guard idiom, centralising the
  status-to-exception translation including the sites where a not-found status is an accepted
  control path ([§8.11](#811-not-found-is-sometimes-success)).

That is 20. The twenty-first is **`FileService`**, and it exists because `CBSTM03A` reaches
all five of its datasets through a DD-name-selected `CALL 'CBSTM03B'` indirection
[`app/cbl/CBSTM03A.CBL`]. That indirection has no home in any of the other twenty, so it gets
its own bean with a keyed handler map. **Total: 21.**

### 6.2 The 17 REST operations

Eight controllers, seventeen operations, distributed **Auth 1 &middot; Menu 2 &middot;
Account 2 &middot; Card 3 &middot; Transaction 3 &middot; Billing 1 &middot; Report 1
&middot; Admin 4**.

The legacy `CDEMO-USER-TYPE` values `'A'` and `'U'` become the **ADMIN** and **USER** roles;
`/api/admin/**` requires ADMIN. Session creation policy is **stateless** on every chain, so
pagination state travels in request parameters and response metadata rather than in server
memory.

**The field-level contract &mdash; every path, method, request and response field, role and
status code &mdash; is in [api-contracts.md §2.1](api-contracts.md#21-the-whole-surface-at-a-glance).
It is deliberately not restated here.**

### 6.3 The six batch jobs

| Job | Source | Note |
|---|---|---|
| `DailyTransactionPostingJob` | [`app/jcl/POSTTRAN.jcl`] + `CBTRN02C` | **`CBTRN01C` is folded in as a labelled read-only pre-flight step.** It has no distinct JCL job and its verb inventory contains no write operation, so a standalone job would be an invention. |
| `InterestCalculationJob` | [`app/jcl/INTCALC.jcl`] + `CBACT04C` | Writes a fresh sequential generation, **not** the transaction table. |
| `CombineTransactionsJob` | [`app/jcl/COMBTRAN.jcl`] | **No COBOL program exists for this job.** Its logic is entirely sort and load control cards, so **the JCL member is itself the source of truth.** |
| `StatementGenerationJob` | [`app/jcl/CREASTMT.JCL`] + `CBSTM03A` + `CBSTM03B` | Note the uppercase extension. Five steps, including the projection sort of [§8.2](#82-the-statement-projection-truncates-two-bytes-reproduce-it-do-not-fix-it). |
| `TransactionReportJob` | [`app/jcl/TRANREPT.jcl`] + [`app/proc/TRANREPT.prc`] + `CBTRN03C` | Backup, filtered sort, then report generation. |
| `BatchPipelineOrchestrator` | The overall JCL job stream | Posting, then interest, then combine, then statements and report as **parallel branches of a split**. |

**`JobExecutionDecider` replaces JCL `COND=(0,NE)` gating.** Return codes map as **0**
completed, **4** completed with rejects, **8** failed, **12** abend. And the one rule to
remember: **return code 4 is set if and only if the reject count exceeds zero**
[`app/cbl/CBTRN02C.cbl:L229-L230`], within the per-record loop at [`:L196-L234`]. There is no
other determinant &mdash; not the presence of an error, not a partial failure.

### 6.4 The data layer

Eleven entities over **PostgreSQL 16** with eleven repositories. The schema is established by
the three Flyway migrations and carries: **3 `@EmbeddedId` composite keys** in COBOL field
order, **3 B-tree indexes** for the three alternate indexes, **10 foreign keys**, **5 check
constraints**, **NOT NULL on every column**, and `@Version` columns for optimistic locking.

Optimistic locking is only half the concurrency story on the account-update path &mdash;
[§8.9](#89-the-date-of-birth-offset-asymmetry) explains why a version column alone does not
reproduce the legacy behaviour.

**The entity-relationship diagram is in
[architecture-before-after.md §3.5](architecture-before-after.md#35-data-layer-11-entities-11-repositories-3-composite-keys-3-migrations).
It is not duplicated here.**

### 6.5 Observability &mdash; entirely new capability

The legacy corpus has **no instrumentation whatsoever** beyond `DISPLAY` statements and a
four-character file-status renderer [`app/cbl/CBTRN02C.cbl:L714-L727`]. Everything in this
package is therefore new capability rather than a translation, and it is shipped **with** the
implementation rather than deferred:

* **Structured JSON logging** with `traceId`, `spanId` and `correlationId` carried in MDC,
  plus the **job-instance identifier** in batch steps &mdash; which is what makes per-run
  object prefixes and per-run logs correlatable.
* **`CorrelationIdFilter`** as the replacement thread of identity for the CICS `EIBTRNID`,
  which was the only per-request identity the legacy system had. It generates or accepts a
  correlation identifier, places it in the logging context, attaches it to spans and
  propagates it on outbound cloud-service calls.
* **Masking** of credentials, password hashes and social security numbers in log output.
  Not optional: the customer layout carries a nine-digit government identifier and the user
  layout carries a password field.
* **Micrometer tracing bridged to OpenTelemetry** and exported to Jaeger.
* **A Prometheus scrape endpoint** and a **composite health endpoint** over database, object
  storage and queue, with separate liveness and readiness groups &mdash; replacing the legacy
  file-open and file-close jobs whose purpose was to make datasets available.
* **Four named counters**: records processed, **records rejected tagged by reject code**,
  authentication attempts, and total transaction amount. The reject-code tag is what turns
  the five `RejectCode` constants into an operable signal instead of a single opaque number.

---

## 7. Test taxonomy and the validation gates

### 7.1 The three tiers

| Tier | Location | What lives there |
|---|---|---|
| **Unit** | `src/test/java/com/cardemo/unit/**` | Service, processor, model, DTO, enum, controller, security, exception, repository and validation tests. At least one class per service bean, so at least 21; at least one per batch processor, so at least 5 &mdash; **including the assertion that reject code 103 overwrites 102** when both the over-limit and the expiry check fail. |
| **Integration** | `src/test/java/com/cardemo/integration/**` | `repository/` &mdash; at least one class per repository, so at least 11, against a **Testcontainers PostgreSQL 16**. `batch/` &mdash; job and step tests **including decider outcomes for return codes 0, 4, 8 and 12**. `aws/` &mdash; S3 and SQS behaviour against a **Testcontainers LocalStack**. |
| **End to end** | `src/test/java/com/cardemo/e2e/**` | `BatchPipelineE2ETest`, `OnlineTransactionE2ETest` and `GateVerificationTest`. |

Coverage target is **&ge; 80 % LINE**, enforced by the build rather than aspired to. Treat it
as the threshold the build imposes, **not as a measured result** &mdash; a coverage figure
becomes meaningful only once `verify` has actually run in your environment and produced
`target/site/jacoco/index.html`.

> **Do not relocate or rename `src/test/java/com/cardemo/e2e/GateVerificationTest.java`.**
> Moving it removes it from the include patterns of both test plugins **with no error**: every
> gate silently stops being verified while the build still reports success. This is tracked as
> Blocker finding `B-2`. Keep the class at that exact path, in that package, with the `Test`
> suffix.

### 7.2 Fixtures are copied, never edited in place

Test fixtures are **copied into `src/test/resources/` under their actual names**, and the
originals in `app/data/ASCII/` are never modified. All nine are present.

**The daily-transaction fixture is `dailytran.txt`, with the word spelled in full** &mdash;
105,300 bytes, **300 records of 350 bytes each**, verified by machine. The mainframe DD name
and dataset are `DALYTRAN`, abbreviated; **a path built from the DD name will not resolve.**
This catches people repeatedly, so check the spelling against the directory listing rather
than against the JCL.

That fixture also **carries genuinely negative amounts** &mdash; it contains both the `{` and
the `}` overpunch characters &mdash; so it exercises the cycle-debit branch of the posting
logic. **It must not be normalised.** See [§8.4](#84-zoned-decimal-overpunch-signs) and
[§8.5](#85-sign-semantics-no-absolute-value-ever).

### 7.3 The user records, and why you cannot sign in with a plaintext password

**There is no standalone ASCII fixture for the user records.** They exist only as inline
`SYSUT1 DD *` data fed through IEBGENER at [`app/jcl/DUSRSECJ.jcl:L34-L45`], in the
`CSUSR01Y` 80-byte layout `ID X(8) + FNAME X(20) + LNAME X(20) + PWD X(8) + TYPE X(1) +
FILLER`. Ten records: **five administrators of type `A` and five standard users of type `U`.**

The source records carry a **literal plaintext password**, and **`V3__seed_data.sql` stores
those passwords only as BCrypt strength-10 hashes.** The plaintext value is never persisted,
never logged and **is deliberately not reproduced anywhere on this page** &mdash; if you need
it, read the locator above. Demo users are seeded only under the `local` and `test` profiles.

The consequence for testing: **sign-on verifies a BCrypt hash, so do not write a test or a
fixture that expects a plaintext comparison.** Note also that sign-on **upper-cases both the
identifier and the password** before comparison, exactly as `COSGN00C` does &mdash; not just
the identifier.

### 7.4 The Testcontainers 2.0.3 blocker

**This is the one dependency detail that will stop your build outright if taken at face
value.** It is tracked as Blocker finding `B-1`.

The Testcontainers 2.x line **renamed every module artefact.** The bare `localstack`,
`postgresql` and `junit-jupiter` artefacts under the `org.testcontainers` group **do not exist
at version 2.0.3** &mdash; resolution against them fails outright. Compounding it, Spring Boot
3.5.11 already manages a version from the 1.x line and imports the Testcontainers
bill of materials itself, so adding a competing import produces an ordering-dependent
resolution that may silently select 1.x.

**Both remedies are required together:**

1. **Override the managed version through the version property** &mdash; set the
   Testcontainers version property to `2.0.3`. **Do not import a second bill of materials**;
   the property is what switches the parent's own import.
2. **Use only the prefixed module coordinates**: `org.testcontainers:testcontainers`,
   `:testcontainers-localstack`, `:testcontainers-postgresql` and
   `:testcontainers-junit-jupiter`.

**Either half alone still fails.** Overriding without renaming resolves non-existent
artefacts; renaming without overriding resolves the wrong version. Both are applied in
`pom.xml` today &mdash; the only bill-of-materials import in the file is the cloud-services
one &mdash; so the failure mode you are most likely to meet is *re-introducing* the problem
while editing test dependencies.

### 7.5 Documentation rendering

Documentation is published through MkDocs and Backstage TechDocs. `mkdocs.yml` declares the
`techdocs-core` and `mermaid2` plugins, and `catalog-info.yaml` sets
`backstage.io/techdocs-ref: dir:.` [`catalog-info.yaml:L22`], so **TechDocs renders straight
from the `nav` block.**

**A page omitted from that `nav` never appears in the published site, and nothing reports a
problem.** The build succeeds, the file sits in the repository, and the reader who needs it
cannot reach it. Worse, and measured rather than assumed: **`mkdocs build --strict` does not
catch it either**, because MkDocs defaults `validation.nav.omitted_files` to `info` and
`mkdocs.yml` declares no `validation` block that would raise it. Making it detectable takes an
explicit setting:

```yaml
validation:
  nav:
    omitted_files: warn
```

Until that is set, **the only reliable detection is a human reading the `nav`.** This is
tracked as High finding `H-5`. **Add the `nav` entry in the same change that adds the page.**
This page's own entry is `Onboarding Guide: onboarding-guide.md`, and its filename is fixed by
that entry &mdash; **it must not be changed.**

The outcome of an actual documentation build is recorded in
[validation-gates.md §2.5](validation-gates.md#env-mkdocs), and in
[§14.2](#142-verification-performed-for-this-page) for this page specifically. Note that
documentation rendering is **not** one of the eight gates.

### 7.6 The eight validation gates

| # | Gate | Objective in one line | Container runtime |
|---|---|---|---|
| [1](validation-gates.md#gate-1) | End-to-end boundary parity | Field-level and byte-level output parity for the daily posting pipeline | Required |
| [2](validation-gates.md#gate-2) | Zero-warning build | A repeatable, deterministic, warning-free build with a clean dependency scan | Partly |
| [3](validation-gates.md#gate-3) | Performance baseline | A **measured baseline**, recorded rather than compared against a target | Partly |
| [4](validation-gates.md#gate-4) | Named fixture validation | All nine fixtures and the ten user records load correctly, with overpunch decoding and BCrypt hashing | Required |
| [5](validation-gates.md#gate-5) | API contract verification | All 17 operations exercised against a real application context | Required |
| [6](validation-gates.md#gate-6) | Security audit | The security invariants hold across the whole tree | Not required |
| [7](validation-gates.md#gate-7) | Scope coverage | All 28 programs mapped at paragraph level through a machine-checkable matrix | Not required |
| [8](validation-gates.md#gate-8) | Integration sign-off | The full runtime topology stands up and is healthy | Required |

**[validation-gates.md](validation-gates.md) is the authoritative ledger and the only place a
gate result may be read. The ledger is not duplicated here, and no result is asserted here.**

Two things about the current position matter to a newcomer, and both are easy to get wrong in
either direction:

* **Seven of the eight results currently read "Not available"**, each naming the specific
  artefact it lacks. **Gate 7 is the exception**: it records an executed harness &mdash; 41
  assertions, exit code 0, against `../TRACEABILITY_MATRIX.md`. So
  it is wrong to say every gate is unexecuted, and equally wrong to read any of the other
  seven as passing.
* **Gates 1, 4, 5 and 8 are pending implementation and execution &mdash; they are not
  container-blocked**, because a container runtime is available
  ([§3.2](#32-two-dated-readings-of-the-authoring-host)). "Not available" is a statement about
  published evidence, not a claim that a gate would fail. An absence is closed by producing
  the evidence, never by softening the sentence.

---

## 8. The fidelity traps

**This is the section to read twice.** Each trap below is a place where the idiomatic Java
choice and the parity-preserving choice diverge, so writing good Java by instinct produces
wrong behaviour. Every one carries its source locator; open the source and read it before you
change anything nearby.

### 8.1 Fixed-width record geometry is load-bearing

Record lengths are a contract at the object-storage boundary, because parity comparison is
byte-level. **Emitting any other length breaks the comparison.**

| Output | Length | Evidence |
|---|--:|---|
| Reject record | **430** | `REJECT-TRAN-DATA PIC X(350)` plus `VALIDATION-TRAILER PIC X(80)`, the trailer being `PIC 9(04)` reason plus `PIC X(76)` description [`app/cbl/CBTRN02C.cbl:L176-L182`]. Independently confirmed by `LRECL=430` at [`app/jcl/POSTTRAN.jcl:L36`]. |
| Report line | **133** | [`app/proc/TRANREPT.prc`] |
| Statement, plain text | **80** | [`app/jcl/CREASTMT.JCL:L89`] |
| Statement, HTML | **100** | [`app/jcl/CREASTMT.JCL:L94`] |
| Transaction image | **350** | [`app/cpy/CVTRA05Y.cpy`] |

The 350-byte transaction offset map, proven consistent against both sets of sort symbol
definitions, is: identifier 1&ndash;16, type 17&ndash;18, category 19&ndash;22, source
23&ndash;32, description 33&ndash;132, amount 133&ndash;143, merchant identifier
144&ndash;152, merchant name 153&ndash;202, merchant city 203&ndash;252, merchant postal code
253&ndash;262, card number 263&ndash;278, originating timestamp 279&ndash;304, processing
timestamp 305&ndash;330, filler 331&ndash;350.

Note that **430 = 350 + 80 exactly**, and the trailer decomposes exactly into a four-digit
code and a 76-character description. When a length looks arbitrary, it usually resolves like
this; check before adjusting one.

### 8.2 The statement projection truncates two bytes &mdash; reproduce it, do not fix it

The statement sort carries a record projection:

```text
OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)
```

[`app/jcl/CREASTMT.JCL:L54`]. That copies **fifty bytes starting at offset 279**, which is the
full 26-byte originating timestamp plus only the **first 24 of the 26 processing-timestamp
bytes**, and it **drops the 20-byte trailing filler entirely**. The projected processing
timestamp therefore arrives as a **24-character value padded to 26**.

**Java's in-job projection must reproduce that truncation exactly.** If you "correct" it,
statement output differs from the baseline in a way that looks like a Java bug and is not
&mdash; and the person debugging it will start from the Java code, not from a 1990s sort card.

### 8.3 PIC clauses, `BigDecimal`, and three precisions that differ

Every monetary and rate field is a **`BigDecimal`** whose scale comes from its PIC clause,
mapped to a `NUMERIC(p,2)` column. **There is zero `float` and zero `double` in any financial
field** &mdash; Gate 6 asserts it across the whole tree.

* Rounding is **`RoundingMode.HALF_EVEN`**.
* Equality is **`compareTo()`**, **never `equals()`** &mdash; `BigDecimal.equals` compares
  scale as well as value, so `2.0` and `2.00` are unequal by it. This is the single most
  common source of a mysteriously failing assertion in this codebase.

Three precisions differ from the common case and are easy to flatten by accident:

| Field | PIC | Column | Locator |
|---|---|---|---|
| Account money fields | `S9(10)V99` | `NUMERIC(12,2)` | [`app/cpy/CVACT01Y.cpy:L7-L8`] |
| `TRAN-CAT-BAL` | `S9(09)V99` | **`NUMERIC(11,2)`** | [`app/cpy/CVTRA01Y.cpy:L9`] |
| `DIS-INT-RATE` | `S9(04)V99` | **`NUMERIC(6,2)`** | [`app/cpy/CVTRA02Y.cpy:L9`] |

### 8.4 Zoned-decimal overpunch signs

Signed numerics in the ASCII fixtures carry a **trailing-sign overpunch**: the last character
of the field encodes both the final digit and the sign.

| Character | Value | Character | Value |
|---|---|---|---|
| `{` | +0 | `}` | &minus;0 |
| `A` … `I` | +1 … +9 | `J` … `R` | &minus;1 … &minus;9 |

Evidence you can check directly: [`app/data/ASCII/acctdata.txt:L1`] begins
`00000000001 Y 00000001940{ 00000020200{ 00000010200{ …`, whose three money fields decode to
**+194.00, +2020.00 and +1020.00**; [`app/data/ASCII/tcatbal.txt:L1`] ends `0000000000{` for
an eleven-character `S9(09)V99`; and [`app/data/ASCII/discgrp.txt:L18`] reads
`DEFAULT   01000100150{`, a six-character `S9(04)V99` decoding to **+15.00**.

**Decoding must be position-aware, driven by the PIC clauses**, because the same letters occur
legitimately inside text fields such as merchant names. **A naive character scan over a whole
record corrupts data** &mdash; and it corrupts it plausibly, which is worse, because merchant
names full of `A` through `R` will not look obviously wrong.

### 8.5 Sign semantics &mdash; no absolute value, ever

`2800-UPDATE-ACCOUNT-REC` adds the transaction amount to the current balance, then adds it to
the current-cycle **credit** if it is non-negative and to the current-cycle **debit**
otherwise [`app/cbl/CBTRN02C.cbl:L547-L552`]:

```text
ADD DALYTRAN-AMT  TO ACCT-CURR-BAL
IF DALYTRAN-AMT >= 0
   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
ELSE
   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
END-IF
```

Read what that means: **a negative amount is added to the debit accumulator**, so the debit
accumulator legitimately **holds negative values**. That is exactly why the over-limit formula
in [§8.6](#86-formula-shape-not-just-value) *subtracts* it.

**Any `Math.abs()` anywhere in this path is a defect.** The instinct to normalise a
"debit total" to a positive number is strong and it is wrong here.

### 8.6 Formula shape, not just value

Two formulas must be transcribed **as written**, because an algebraically equivalent rewrite
changes the rounding or the sign handling.

**Interest** [`app/cbl/CBACT04C.cbl:L462-L470`]:

```text
COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

Multiply **first**, then divide by the literal **1200** with two-decimal `HALF_EVEN` rounding.
**Never** rewrite it as a division by 100 followed by a division by 12, and never substitute a
decimal multiplier such as 0.0008333 &mdash; both change the result.

**The over-limit temporary balance** [`app/cbl/CBTRN02C.cbl:L393-L422`] is
`ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`, and the reject fires when
`ACCT-CREDIT-LIMIT` is below it. The subtraction is correct precisely because of
[§8.5](#85-sign-semantics-no-absolute-value-ever).

**The interest date parameter is not an ISO date.** `CBACT04C` receives a linkage group of a
binary length field plus a ten-character date, and the value supplied on the execute statement
is **eight date digits followed by two zeros** &mdash; ten numeric characters, no separators
[`app/jcl/INTCALC.jcl:L22`]. It matters because that value is concatenated into generated
transaction identifiers, so treating it as `YYYY-MM-DD` produces wrong identifiers rather than
a parse error.

### 8.7 Timestamp format

Generated timestamps are **26 characters whose final four digits are always zeros**. The source
says so in its own layout comment &mdash; `T I M E S T A M P   D B 2  X(26)
EEEE-MM-DD-UU.MM.SS.HH0000` [`app/cbl/CBTRN02C.cbl:L149`], with the field declared
`PIC X(26)` at [`:L159`] and built by `Z-GET-DB2-FORMAT-TIMESTAMP` at [`:L692`], performed
during posting from [`:L437`].

**Format to millisecond precision and then append four zeros &mdash; never nanosecond
precision.** Otherwise every generated timestamp differs from the baseline, and every parity
comparison fails on every record for a reason that has nothing to do with the logic under test.

### 8.8 Case-handling asymmetry

In the account-update snapshot comparison [`app/cbl/COACTUPC.cbl:L4109-L4192`], the case
handling is **deliberately inconsistent**, and normalising it in either direction changes which
updates are accepted:

| Compared through | Fields |
|---|---|
| `FUNCTION LOWER-CASE` on both sides | The account group identifier |
| `FUNCTION UPPER-CASE` on both sides | Customer first, middle and last name; address lines 1&ndash;3; state code; country code; government-issued identifier |
| **No case function at all** | Postal code; both telephone numbers; the government identifier number; the electronic-funds account identifier; the primary-card-holder indicator; the FICO score |

This is not noise left by a careless author &mdash; it is the behaviour, and the comparison is
what decides whether an update proceeds.

Separately, and on a different code path: **sign-on upper-cases both the identifier and the
password**, not just the identifier.

### 8.9 The date-of-birth offset asymmetry

**This is the trap that fails every single request**, so it is worth stating precisely.

The account-update endpoint is stateless, so the request body carries both the old and the new
detail groups &mdash; the snapshot the user was shown, and the values they want written. The
service compares them field by field, reproducing
`9700-CHECK-CHANGE-IN-REC` [`app/cbl/COACTUPC.cbl:L4109-L4192`], which is performed from
[`:L3947-L3948`].

Now the trap. The **live customer record** holds a dash-separated date, so its components sit
at offsets **1, 6 and 9**. The **snapshot** holds the same date **without separators**, so its
components sit at offsets **1, 5 and 7**. The source compares **offset 1 against 1, 6 against
5, and 9 against 7** &mdash; six lines you can read for yourself at
[`app/cbl/COACTUPC.cbl:L4174-L4179`]:

```text
AND CUST-DOB-YYYY-MM-DD (1:4)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)
AND CUST-DOB-YYYY-MM-DD (6:2)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)
AND CUST-DOB-YYYY-MM-DD (9:2)  EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)
```

**A whole-string comparison of the two reports a change on every request**, so the endpoint
returns a conflict every time and is permanently unusable. Compare **components**, and store
the snapshot date in its **compact `YYYYMMDD` form**.

More generally: **dates in this comparison are compared as three separate substrings, never as
whole strings.**

**And a version column alone is not sufficient here.** A JPA `@Version` detects that *some*
concurrent write occurred; the legacy program detects that *specific business field values*
differ from what the user was shown. Those are different guarantees &mdash; a concurrent write
that set a field back to its original value passes the legacy check and fails a version check.
**Both layers are required**: the version column for the store-level guard, and the explicit
field-by-field comparison for the business-level guard.

### 8.10 Two distinct numeric parsers

The transaction-add program uses **two different numeric intrinsics deliberately**, and using
one parser for both means accepting input the legacy system rejects, or rejecting input it
accepts.

| Input | Intrinsic | Locators |
|---|---|---|
| Account identifier, card number | `FUNCTION NUMVAL` &mdash; plain, digits only | [`app/cbl/COTRN02C.cbl:L204`], [`:L218`] |
| Transaction amount | `FUNCTION NUMVAL-C` &mdash; **currency-aware**, tolerating currency symbols and thousands separators | [`:L383`], [`:L456`] |

The response echo is equally specific: the parsed amount round-trips through an edited field
declared `PIC +99999999.99` [`:L59`] &mdash; a **mandatory sign, exactly eight integer digits
and two decimals** &mdash; while the numeric field itself is `PIC S9(9)V99` [`:L58`]. So a
formatter matching that mask is needed as well as the two parsers.

### 8.11 Not-found is sometimes success

A blanket "not-found maps to an exception" rule **abends three legitimate control paths**. The
status mapper must know about all three:

| Site | Accepts | Locator |
|---|---|---|
| The category-balance **upsert** | `'00'` **or** `'23'` | [`app/cbl/CBTRN02C.cbl:L481`], in `2700-UPDATE-TCATBAL` [`:L467-L501`] |
| The interest **rate lookup**, before retrying with the literal `DEFAULT` group | `'00'` **or** `'23'` | [`app/cbl/CBACT04C.cbl:L422`], in `1200-GET-INTEREST-RATE` [`:L415-L442`] |
| The **file-service** open and read sites in the statement program | `'00'` **or** `'04'` | Nine sites: [`app/cbl/CBSTM03A.CBL:L736`], [`:L748`], [`:L771`], [`:L789`], [`:L807`], [`:L862`], [`:L879`], [`:L895`], [`:L911`] |

Two refinements worth having right, because both are commonly overstated:

* The **retry** for a missing rate is a **separate paragraph**,
  `1200-A-GET-DEFAULT-INT-RATE` [`app/cbl/CBACT04C.cbl:L443-L460`], and it accepts **only**
  success &mdash; so **a missing `DEFAULT` row abends the job.** Both outcomes are testable:
  [`app/data/ASCII/discgrp.txt`] contains rows for the literal `DEFAULT` group, including
  zero-rate combinations.
* In the statement program the `'00'` or `'04'` acceptance belongs to the **file-open and read
  paragraphs**. The initialisation-chain read sites are stricter &mdash; they accept `'00'`,
  treat `'10'` as end of file and abend otherwise [`app/cbl/CBSTM03A.CBL:L353-L362`]. Do not
  flatten the two into one rule.

Everywhere else, a not-found status **is** an error.

### 8.12 Boundary paths that are easy to omit

Each of these is a single statement in the source that is easy to read past, and each loses
data silently if omitted.

* **The empty-file identifier path yields a first identifier of 1.** An end-of-file response
  moves zeros into the identifier, which is then incremented
  [`app/cbl/COBIL00C.cbl:L487-L488`], in `READPREV-TRANSACT-FILE` [`:L472-L496`].
* **The interest job performs the account update one final time** when the loop detects end of
  file [`app/cbl/CBACT04C.cbl:L188-L222`]. **Omitting that flush silently loses the last
  account's interest** &mdash; and only the last account's, so a small test may well pass.
* **The interest job's account update zeroes both cycle counters** before rewriting
  [`app/cbl/CBACT04C.cbl:L350-L370`]. **Omitting that reset breaks the over-limit arithmetic
  on the *next* posting cycle** &mdash; a defect that surfaces only on a second batch run,
  which is exactly the kind of latent divergence the parity gates exist to catch.
* **A zero rate produces no interest transaction and no accumulation** at all
  [`app/cbl/CBACT04C.cbl:L214`].

### 8.13 Pagination sizes are fixed by screen geometry

| List | Page size | Locator |
|---|--:|---|
| Card list | **7** | `WS-MAX-SCREEN-LINES … VALUE 7` [`app/cbl/COCRDLIC.cbl:L177-L178`], with the row array at [`:L76`] |
| Transaction list | **10** | The loop bound `UNTIL WS-IDX > 10` [`app/cbl/COTRN00C.cbl:L290`], [`:L344`] |
| User list | **10** | `USER-REC OCCURS 10 TIMES` [`app/cbl/COUSR00C.cbl:L57`] |

These come from the row arrays in the BMS symbolic maps, which is also why those maps carry the
highest field counts. **They are not client-configurable**, and exposing a page-size parameter
would be a behaviour change.

### 8.14 Exit codes and the status rendering

* **Return code 4 if and only if the reject count exceeds zero**
  [`app/cbl/CBTRN02C.cbl:L229-L230`].
* **Abend code 999 with process return code 12** on an unexpected status
  [`app/cbl/CBTRN02C.cbl:L707-L711`].
* The four-character status rendering is **a contract**, because logs are compared. When the
  status is non-numeric or its first byte is `'9'`, the first byte is copied through and the
  second is expanded from a binary field into three digits; otherwise the field is four zeros
  with the two status characters at positions 3 and 4. It emits the literal prefix
  `FILE STATUS IS: NNNN` [`app/cbl/CBTRN02C.cbl:L714-L727`]. **Java must emit the identical
  rendering.**

### 8.15 Preserved quirks you must not "fix"

Every item here looks like a bug, is a bug, and is **deliberately retained** because parity is
the contract. Each is cited in `../TRACEABILITY_MATRIX.md` and
justified in `../DECISION_LOG.md`.

* **Reject code 103 overwrites 102.** The over-limit check and the expiry check are
  **sequential and unguarded** &mdash; there is no alternative branch and no early exit
  between them [`app/cbl/CBTRN02C.cbl:L393-L422`]. When both conditions fail, the second
  assignment overwrites the first and **a single reject record bearing 103 is written**. An
  implementation that guards the second check, or that emits two reject records, diverges.
* **The account expiry field is misspelled `ACCT-EXPIRAION-DATE` in the copybook**, and the
  misspelling is **part of the field contract**. The comparison is also a string comparison
  against the first ten characters of the **originating** timestamp, not the processing one.
* **Reject code 109 is assigned but never consumed.** It is set on the account-rewrite failure
  path [`app/cbl/CBTRN02C.cbl:L545-L560`], but that paragraph runs only on the
  already-validated path, so no reject record is written and the value is cleared on the next
  iteration. It must **still exist** as one of **exactly five** `RejectCode` constants, because
  the assignment is real code on a reachable path.
* **The report control break triggers on the card number while the emitted label reads
  "Account Total"** &mdash; `CBTRN03C`.
* **User deletion has no self-delete guard.** `COUSR03C` never compares the target identifier
  against the signed-on identifier. **Do not add one.**
* **`1400-COMPUTE-FEES` is a reachable empty paragraph.** Its body is a comment reading that
  it is to be implemented, plus an exit [`app/cbl/CBACT04C.cbl:L518-L520`], and it **is
  performed**, from [`:L216`]. It is retained as a **documented intentional no-op** &mdash;
  see [§12.2](#122-clause-b-code-quality) for how that squares with the no-dead-code rule.
* **Legacy job-control defects are logged, not repaired**: the corrupted dataset statement in
  the statement job's execution step, and the 80-versus-100 record-length mismatch for the
  HTML output between [`app/jcl/CREASTMT.JCL:L69`] and [`:L94`]. One legacy inconsistency
  *is* resolved &mdash; the conflicting retention limits for the report generation group,
  resolved to **10**, because a single object-lifecycle value must be chosen (finding `M-1`).
  It is the only one.

---

## 9. The source-locator workflow

This is how every change in this codebase is justified. It is not optional ceremony &mdash;
Gate 7 makes paragraph coverage a pass-or-fail condition, so a change that cannot be traced
cannot be accepted.

1. **Start from `../TRACEABILITY_MATRIX.md`** to find the COBOL
   paragraph behind the Java method you are about to touch. (It is a **repository-root
   artefact, outside the MkDocs `docs_dir`**, so it is linked as `../TRACEABILITY_MATRIX.md`
   rather than as a sibling page.)
2. **Open the cited paragraph in `app/` and read it in full, including the surrounding control
   flow.** This step is where most defects are prevented. The fall-through in
   [§8.15](#815-preserved-quirks-you-must-not-fix), the missing guards, the final flush in
   [§8.12](#812-boundary-paths-that-are-easy-to-omit) &mdash; **none of them is visible when
   you read a paragraph in isolation.** They are properties of what surrounds it.
3. **Check `../DECISION_LOG.md`** (also a repository-root artefact) for an
   existing decision covering the mechanism. Most surprising choices already have one, with
   the reasoning and the source locator. If your change contradicts a logged decision, that is
   a conversation, not a commit.
4. **Check [api-contracts.md](api-contracts.md)** for the observable contract you must not
   break, and [validation-gates.md](validation-gates.md) for the evidence obligation your
   change creates.
5. **Carry the citation forward into the code.** Every new or changed private method carries a
   **Javadoc citation naming its source paragraph label**, and every new file opens with the
   **Apache-2.0 header naming the originating COBOL program, copybook or job-control member**
   &mdash; the convention verified across `app/cbl` (28 of 28), `app/cpy-bms` (17 of 17),
   `app/bms` (17 of 17), `app/jcl` (28 of 29) and `app/cpy` (12 of 28).

### 9.1 Why paragraph correspondence is preserved

Industry guidance on legacy modernisation consistently warns against literal transliteration
&mdash; reproducing `GO TO` and `PERFORM` structure in Java, the pattern sometimes called
"JOBOL" &mdash; and recommends restructuring into idiomatic object-oriented code. **That
guidance conflicts with this project's mandate, and the conflict is resolved in favour of
parity**, for two concrete reasons: behavioural parity is the contract of the engagement, and
Gate 7 makes paragraph coverage mechanically provable or not at all.

The legitimate readability concern behind the guidance is answered **by two compensating
mechanisms rather than by restructuring**: every private method carries a Javadoc citation
naming its source paragraph, and the traceability matrix makes the correspondence navigable in
both directions.

Where the guidance *can* be honoured without touching control flow, it **is**: naming is
idiomatic Java rather than transliterated COBOL, `BigDecimal` replaces packed decimal, and
framework mechanisms replace static linkage &mdash; dependency injection for `EXEC CICS XCTL`
dispatch, a repository for the VSAM verbs, a filter chain for the COMMAREA. So the codebase is
one-to-one in *structure* and idiomatic in *expression*, and that combination is deliberate.

---

## 10. Troubleshooting

Symptom, cause, fix. These are the failures you are most likely to hit, in roughly the order
you will hit them.

### Build and toolchain

| Symptom | Cause | Fix |
|---|---|---|
| Build fails resolving `org.testcontainers:localstack`, `:postgresql` or `:junit-jupiter` | Those artefacts **do not exist at 2.0.3** &mdash; the 2.x line renamed every module coordinate | Apply **both** remedies from [§7.4](#74-the-testcontainers-203-blocker): the version property set to `2.0.3` with **no** competing bill-of-materials import, **and** only the prefixed coordinates. Either alone still fails |
| Build resolves an unexpected Testcontainers **1.x** version | The parent's own bill of materials won the resolution ordering | **Set the version property**; do not import a second bill of materials to try to outrank it |
| `./mvnw` fails with a toolchain error | The Enforcer floor rejected the JDK or Maven version &mdash; it asserts Java `[25,)` and Maven `[3.9.11,)` | Provision **JDK 25** and let the wrapper supply Maven 3.9.11, or use the pinned-container path in [§5.2](#52-building-when-the-host-has-no-jdk). **Do not lower the floor** |
| `java` or `mvn` **not found** | Expected on a host in the state of the first reading of 30 July 2026 ([§3.2](#32-two-dated-readings-of-the-authoring-host)) | Use the **pinned-container build path** ([§5.2](#52-building-when-the-host-has-no-jdk)); it needs only the container runtime, which is available |
| Compilation fails on a **warning** | `-Xlint:all -Werror` is configured in `maven-compiler-plugin` 3.14.1 [`pom.xml:L1232-L1233`] &mdash; this is intentional | **Fix the cause.** Do not add a blanket suppression at class or package scope; a narrowly scoped one with a justifying comment is acceptable |
| Build fails on **Javadoc** | The `doclint` gate runs at `all` with `failOnWarnings`, so undocumented public surface fails | Document the public member: purpose, parameters, return, side effects and error modes |
| **Coverage gate** fails | JaCoCo's minimum is **0.80 LINE** | Open **`target/site/jacoco/index.html`**, find the uncovered branches and test them. Do not lower the threshold |
| **OWASP scan** reports HIGH or CRITICAL | dependency-check fails at CVSS 7.0 or above | Open **`target/dependency-check-report.html`**. **Do not suppress silently** &mdash; record the finding with its severity and remediation, and treat a suppression as a decision needing an entry in `../DECISION_LOG.md` |

### Startup and runtime

| Symptom | Cause | Fix |
|---|---|---|
| Application fails to start with a **missing-property** error naming the signing key | `JWT_SIGNING_KEY` is unset. **Fail-fast is intentional and there is deliberately no default** | Copy `.env.example` to `.env` and populate it locally ([§4.2](#42-the-signing-key-and-how-secrets-are-handled)). Never add a fallback value |
| **Flyway** fails on startup | A prior schema exists, or migrations were applied out of order, or a checksum changed because a migration file was edited after being applied | For a local database, reset the volume and re-apply from clean: `docker compose down -v` then `docker compose up -d --wait`. **Never edit an already-applied migration** &mdash; add a new one |
| Integration tests fail on **missing S3 buckets or the FIFO queue** | LocalStack initialisation had not completed when the test ran | Wait for health (`docker compose up -d --wait`) and re-run. **The init script is idempotent**, so re-running the stack converges rather than failing |
| `/actuator/prometheus` returns **401** | `METRICS_SCRAPE_USERNAME` and `METRICS_SCRAPE_PASSWORD` are not both non-blank. **This is intended behaviour, not a fault** | Set both. A bearer token will not work here &mdash; that path has its own filter chain and principal ([§5.3](#53-running-the-full-topology)) |
| A published report message is not drained | The queue listener is `drainReportJobQueue` at [`src/main/java/com/cardemo/config/BatchConfig.java:L1231`]; if it is not running, check the queue name property resolves and the stack is healthy | Confirm `carddemo-report-jobs` exists in LocalStack and the profile activated the listener |

### Behaviour that looks like a Java bug

| Symptom | Cause | Fix |
|---|---|---|
| **Account update returns a conflict on every request** | The date-of-birth snapshot was sent **dash-separated**. The comparison uses different offsets on each side, so a whole-string comparison always reports a change | Send the snapshot date in **compact `YYYYMMDD`** form and compare components ([§8.9](#89-the-date-of-birth-offset-asymmetry)) |
| **Amounts are off by a rounding unit** | Either a `double` crept into the path, or the interest formula was algebraically rewritten | Use `BigDecimal` with `HALF_EVEN` throughout, and transcribe the formula as `(balance * rate) / 1200` ([§8.3](#83-pic-clauses-bigdecimal-and-three-precisions-that-differ), [§8.6](#86-formula-shape-not-just-value)) |
| A `BigDecimal` assertion fails even though the values look identical | `equals()` compares scale as well as value | Compare with **`compareTo()`** |
| **Seeded users cannot sign in** | The seed stores **BCrypt hashes only** | Do not expect a plaintext comparison. Note also that both identifier and password are upper-cased before comparison ([§7.3](#73-the-user-records-and-why-you-cannot-sign-in-with-a-plaintext-password)) |
| A debit total is positive when the baseline shows it negative | An `Math.abs()` or a sign normalisation was introduced | Remove it &mdash; the debit accumulator legitimately holds negative values ([§8.5](#85-sign-semantics-no-absolute-value-ever)) |
| Statement output differs from the baseline in the processing timestamp | The two-byte projection truncation was "fixed" | Reproduce the truncation exactly ([§8.2](#82-the-statement-projection-truncates-two-bytes-reproduce-it-do-not-fix-it)) |
| The last account's interest is missing from a run | The end-of-data final flush was omitted | Restore it ([§8.12](#812-boundary-paths-that-are-easy-to-omit)) |

### Paths, files and documentation

| Symptom | Cause | Fix |
|---|---|---|
| **A COBOL path does not resolve** | **Extension case.** `CBSTM03A.CBL`, `CBSTM03B.CBL`, `CREASTMT.JCL` and `COSTM01.CPY` are **uppercase** | Match case-insensitively, and check the directory listing rather than assuming a lowercase extension ([§2.2](#22-directory-tour-with-verified-counts)) |
| A fixture path does not resolve | The daily-transaction fixture spells the word **in full**, unlike the `DALYTRAN` DD name | Use the name from the directory listing ([§7.2](#72-fixtures-are-copied-never-edited-in-place)) |
| **A new documentation page does not appear in the published site** | It is **missing from the `mkdocs.yml` `nav`**. `catalog-info.yaml:L22` sets `backstage.io/techdocs-ref: dir:.`, so TechDocs renders straight from the nav. **An omitted page silently never publishes &mdash; no error and no output**, and `--strict` does not catch it either because the omission is reported at INFO level. **High**-severity, finding `H-5` | **Add the nav entry in the same change as the page**, and consider setting `validation.nav.omitted_files: warn` in `mkdocs.yml` so a strict build can fail on it ([§7.5](#75-documentation-rendering)) |
| Gates appear to have stopped being verified, but the build is green | `GateVerificationTest` was moved or renamed, removing it from both test plugins' include patterns **with no error**. Blocker finding `B-2` | Restore it to `src/test/java/com/cardemo/e2e/GateVerificationTest.java`, and confirm gate execution from the written evidence artefact rather than from a green build |

---

## 11. Safe extension boundaries

Each item below is a **hard prohibition** with the reason it exists. They are not preferences,
and none of them is negotiable for convenience.

### 11.1 Never touch the frozen corpus

**Never edit, reformat, rename, move or delete anything under `app/`.** It is the parity
oracle, the field-contract source and the traceability anchor at once
([§2.1](#21-app-is-read-only)), and an edit destroys all three roles simultaneously.

### 11.2 Never parse the EBCDIC directory

**Never parse `app/data/EBCDIC/`.** Its **12 `.PS` files plus a `.gitkeep`** are byte-level
codepage reference **only**. The ASCII fixtures are the authoritative seed and test input, and
**no transcoding utility is built** &mdash; if you find yourself writing one, the requirement
is wrong.

### 11.3 Never reformat globally

`CONTRIBUTING.md` asks contributors to "focus on the specific change you are contributing" and
warns that reformatting all the code makes the change hard to review [`CONTRIBUTING.md:L33`],
and requires that local tests pass [`:L34`]. So keep diffs to the change at hand.

A nuance worth understanding rather than guessing at: **no formatter, linter or style-tool
configuration existed in this repository before the migration.** So `.editorconfig` and the
pinned build configuration **establish** conventions rather than overriding an existing style
&mdash; which is a different thing from fighting one, and is why they are additions rather than
edits. Rule 1 clause C's "if present" condition simply was not triggered.

### 11.4 Never commit a secret

**Never commit a secret, a token, a key or a live credential** &mdash; not in code, not in
configuration, not in tests, not in logs. `.env` is git-ignored and stays that way;
`.env.example` carries **names with blank or placeholder values only**.

All cloud interaction targets LocalStack. **There are zero live AWS credentials in this
repository and no code path may reach a real endpoint** ([§5.6](#56-all-cloud-interaction-targets-localstack)).

### 11.5 Never replace the legacy identifier generation with a database sequence

Identifier generation is a **descending browse for the maximum key, plus one**. It is
inherently racy &mdash; exactly as the CICS browse was &mdash; and a collision surfaces as a
duplicate-record conflict from the primary-key constraint. **That is the parity-preserving
choice.** A database sequence would change the generated values and break baseline comparison,
so the raciness is retained deliberately and logged.

### 11.6 Never add a guard the source does not have

**No self-delete guard in user deletion**, because none exists. **No bounds check where the
source has none**, unless removing the resulting hazard is **explicitly labelled a deviation
and logged**. Adding a "sensible" guard changes which requests succeed, which is a behaviour
change however reasonable it looks in isolation.

### 11.7 Never perform an untracked parity cleanup

Removing an apparently-dead paragraph, "fixing" a fall-through, correcting a control-break
label, normalising a case function, repairing a legacy job-control defect &mdash; **all of
these are behaviour changes**, not tidying. If a change is genuinely warranted it must be a
**labelled, justified deviation with an entry in `../DECISION_LOG.md`**.
Never a silent tidy-up.

### 11.8 Do not build a user interface

The target surface is **REST and JSON plus Actuator**. There is **no single-page application,
no web or mobile front end, no 3270 emulation, no component library and no design system.** The
BMS symbolic maps are consumed as **field contracts only** &mdash; all 441 input fields &mdash;
and are not reimplemented as a user interface. `docs/executive-presentation.html`
([executive-presentation.html](executive-presentation.html)) is a **static stakeholder
document**, not an application interface.

### 11.9 Do not change the deployment shape

**No microservices, no event sourcing, no CQRS, no Kubernetes, no Helm, no service mesh.**
Orchestration stops at Docker Compose. The reason is transactional, not stylistic: the
**dual-dataset account write** and the **three-dataset posting write** are atomicity invariants,
and distributing them across service boundaries would require compensating transactions and
would change failure semantics &mdash; which is a behaviour change and therefore forbidden. A
**single deployable modular monolith** is mandatory. The evidence is set out in
[architecture-before-after.md §4](architecture-before-after.md#4-why-a-modular-monolith-is-mandatory).

### 11.10 Do not port anything from `samples/`

Its three z/OS compile templates, three build procedures and two binary emulator runtime
bundles are **out of scope** and are superseded conceptually by `pom.xml`.

### 11.11 Do not unpin or unilaterally advance a dependency

Every plugin and every non-managed dependency is pinned to an **exact version** &mdash; no
ranges, no `LATEST`, no `RELEASE`. Where an external source suggests a different version, **the
pin governs** and the divergence is **recorded in the decision log** rather than resolved
unilaterally. Two live examples: the coverage plugin is pinned at 0.8.12 while another artefact
cites 0.8.14 (finding `M-3`), and the framework's open-source support horizon is recorded as a
residual risk rather than absorbed by advancing the version.

### 11.12 Do not modify existing repository files beyond the three permitted

Exactly **three** pre-existing files are modified by this migration: root
`../README.md`, root `mkdocs.yml` and
[`docs/technical-specifications.md`](technical-specifications.md). In particular
[`docs/index.md`](index.md), [`docs/project-guide.md`](project-guide.md),
`catalog-info.yaml`, `../CONTRIBUTING.md`,
`../CODE_OF_CONDUCT.md`, `../LICENSE` and
`../NOTICE` remain **byte-for-byte unchanged**. That is also why the inaccurate
service-catalogue metadata noted in [§13](#13-findings-severity-classified) is **reported but
not corrected** here.

### 11.13 Process

* **Pay attention to automated CI failures** on your pull request and stay involved in the
  conversation [`CONTRIBUTING.md:L37`].
* **Security issues do not go in a public issue.** Notify AWS/Amazon Security through the
  vulnerability-reporting process; **do not create a public GitHub issue**
  [`CONTRIBUTING.md:L54`].
* This project has adopted the Amazon Open Source Code of Conduct
  [`CONTRIBUTING.md:L48`]; see `../CODE_OF_CONDUCT.md`.
* Contributions are licensed against the repository `../LICENSE` (Apache-2.0), and
  you will be asked to confirm the licensing of your contribution
  [`CONTRIBUTING.md:L59`].

---

## 12. Rule 1: Build Verify &mdash; what you are held to

**Exactly one user-specified rule governs this project: "Rule 1: Build Verify"**, headed
*GLOBAL CODING &amp; DESIGN STANDARDS (Apply to all projects)*. It has six lettered clauses.
This section states what each requires of a contributor and how this codebase satisfies it.
The rule's full text lives in the project's rules document and is summarised, not transcribed,
here.

### 12.1 Clause A &mdash; Engineering principles

Correctness, determinism and explicit behaviour ahead of cleverness; security by default with
untrusted input; maintainability through readable naming, modular design, minimal complexity
and clear separation of concerns; observability through structured logs, meaningful errors and
measurable behaviour; and avoidance of obvious inefficiency with tradeoffs justified rather
than assumed.

**"Correctness first" has a very concrete meaning in this codebase: it is
[§8 The fidelity traps](#8-the-fidelity-traps).** Determinism is the organising principle of
the whole migration &mdash; the target reproduces the source's behaviour exactly, and every
place an outcome could differ is either preserved verbatim or labelled a deviation. Separation
of concerns is the package layering of [§6](#6-package-and-module-tour). Observability is
[§6.5](#65-observability-entirely-new-capability), shipped with the implementation rather
than deferred. Measurable behaviour is Gate 3, deliberately framed as a **measurement** rather
than a target, because **no service-level objective exists anywhere in the source** to be
reproduced and none may be invented.

The efficiency clause resolves one real tension. The legacy statement program holds its working
set in a fixed table with a hard capacity ceiling and **no bounds check**. Streaming is both
faster and safer, so the ceiling is removed &mdash; but because that changes behaviour at
scale, **the tradeoff is justified in writing in the decision log** rather than taken silently.

### 12.2 Clause B &mdash; Code quality

No dead code, no unused imports, and **no TODOs without an owner or a tracking reference**;
validate all inputs and boundary conditions and handle null and empty cases explicitly; avoid
global mutable state and prefer dependency injection; clear error handling with no swallowed
exceptions, wrapping with context and preserving the root cause; tests for core logic and any
non-trivial bug fix; and documented public APIs covering purpose, inputs, outputs, side effects
and error modes.

How the codebase meets it: global mutable state is eliminated wholesale &mdash; the COMMAREA,
the working-storage flags and the self-modifying dispatch field all become request-scoped or
injected state. Error handling is the nine-class exception hierarchy, in which every legacy
file status and response code becomes a **typed** exception carrying its originating status;
nothing is swallowed, and the fatal type preserves the abend code, culprit program, reason and
message. Boundary conditions are handled explicitly **at the exact points the source handles
them** &mdash; [§8.11](#811-not-found-is-sometimes-success) and
[§8.12](#812-boundary-paths-that-are-easy-to-omit) are that clause applied.

**The one sanctioned exception, and why it is not a violation.** `1400-COMPUTE-FEES` is a
reachable empty paragraph [`app/cbl/CBACT04C.cbl:L518-L520`], performed from [`:L216`]. Under a
literal reading of "no dead code" it should be deleted; under the parity mandate it must be
retained, because deleting its call site breaks the paragraph map Gate 7 verifies. **The
resolution is that the parity mandate governs and clause B is satisfied by a different
mechanism**: the empty method is retained with documentation citing its source lines and an
explicit marker stating the no-op is intentional. That satisfies the clause's **actual** intent
&mdash; it forbids *untracked* dead code and deferred work without an owner, and this code is
tracked, referenced and justified in `../DECISION_LOG.md`. It is not
abandoned residue; it is a documented faithful reproduction of a reachable no-op in the system
of record. The same reasoning covers the never-consumed reject code and the redundant index
assignment in the statement program.

### 12.3 Clause C &mdash; Repository hygiene

Follow repository conventions &mdash; formatters, linters, tests &mdash; **where present**, and
never fight existing style; keep builds deterministic and free of environment-specific
assumptions; use a consistent directory structure and **avoid duplication**.

Conventions here were **established by inspection, not assumed**: the universal source-header
convention ([§9](#9-the-source-locator-workflow), step 5) is extended to every new file, and
the absence of any pre-existing formatter configuration is why `.editorconfig` establishes
rather than overrides ([§11.3](#113-never-reformat-globally)). Determinism is total dependency
and plugin pinning, a version-pinned build wrapper, an Enforcer-asserted toolchain floor, and a
compose stack whose every image is pinned by tag and digest.

**The duplication clause binds this page too.** That is why the 17-operation field contract
lives only in [api-contracts.md](api-contracts.md), the entity-relationship diagram only in
[architecture-before-after.md](architecture-before-after.md), the gate ledger only in
[validation-gates.md](validation-gates.md), and the legacy inventory tables only in
`../README.md` &mdash; each **linked, never restated**. The repository-root
evidence artefacts are likewise **linked, never copied into `docs/`**.

### 12.4 Clause D &mdash; Security standards

No secrets in code, logs, tests or configuration; pin dependencies where possible and flag
known risky patterns; least privilege for tokens, credentials and configuration.

Applied: the signing key is environment-indirected in **every** profile with fail-fast and no
committed default ([§4.2](#42-the-signing-key-and-how-secrets-are-handled)); log output masks
credentials, password hashes and government identifiers; tests carry no secret material and the
seeded passwords exist only as BCrypt hashes; dependency pinning is total and the vulnerability
scan is what makes it verifiable rather than merely stated. Least privilege is why
`application-prod.yml` exists, why the compose stack mounts no Docker socket and runs no
privileged container, why published ports bind to loopback, and why the alerts topic with no
consumer was removed rather than left provisioned.

**This page contains no secret, no key, no token, no password value and no live endpoint.** The
legacy plaintext password is referred to **only by locator**
([§7.3](#73-the-user-records-and-why-you-cannot-sign-in-with-a-plaintext-password)).

### 12.5 Clause E &mdash; Documentation standards

Every module or component must carry documentation explaining what it does, how to run, build
and test it, its key configurations and defaults, and its common failure modes and
troubleshooting. **This is the dominant clause for this page**, and the mapping is in
[§0](#0-how-to-read-this-page).

At module granularity the same clause is satisfied in code by a `package-info.java` in every
physical package, each naming the COBOL artefacts its package derives from
([§6](#6-package-and-module-tour)). Failure modes are documented in three complementary places:
the exception hierarchy documents them in code, [api-contracts.md](api-contracts.md) documents
them per operation, and [validation-gates.md](validation-gates.md) documents them at gate
level.

### 12.6 Clause F &mdash; Output requirements

Be evidence-based, citing file paths, symbols and examples; classify findings by severity as
Blocker, High, Medium or Low; provide clear remediation; and where information is missing,
**state "Not available" and list what is needed.**

Applied on this page as: a `[path:locator]` citation on every factual claim; the
severity-classified table in [§13](#13-findings-severity-classified) with remediation for each
entry; and the explicit register in
[§14](#14-status-not-available-register-and-cross-references) using that exact wording rather
than a softer paraphrase.

---

## 13. Findings, severity-classified

The issues a newcomer needs to know about, each with a severity and a remediation, as Rule 1
clause F requires. Identifiers match the residual-risk register in
[validation-gates.md §12](validation-gates.md#findings), which is the authoritative
register &mdash; **it is referenced here, not duplicated.**

### Blocker

| ID | Finding | Locator | Remediation |
|---|---|---|---|
| `B-1` | The container-library 2.x line renamed every module coordinate, so the bare 1.x identifiers do not resolve, and the framework parent already manages a 1.x version and imports the bill of materials itself | `pom.xml` Testcontainers version property and the `org.testcontainers` module coordinates | Apply **both** halves &mdash; override through the version property, **never** a second bill-of-materials import, **and** use only the four prefixed coordinates. Either half alone still fails ([§7.4](#74-the-testcontainers-203-blocker)) |
| `B-2` | Relocating or renaming the gate harness removes it from both test plugins **with no error**, so every gate silently stops being verified while the build still reports success | `src/test/java/com/cardemo/e2e/GateVerificationTest.java` and the Failsafe includes in `pom.xml` | Keep the class at that exact path, in that package, with the `Test` suffix. Confirm gate execution from the written evidence artefact, **never** from a green build alone |

### High

| ID | Finding | Locator | Status and remediation |
|---|---|---|---|
| `H-1` | The token signing key was hardcoded in a prior implementation | [`docs/project-guide.md:L52`], [`:L215`] | **Closed by this work.** Resolved from the environment with no committed default, so an unset variable aborts startup |
| `H-2` | No production profile existed in a prior implementation | [`docs/project-guide.md:L51`] | **Closed by this work** &mdash; `application-prod.yml`, every secret externalised |
| `H-3` | No continuous-integration workflow existed in a prior implementation | [`docs/project-guide.md:L49`] | **Closed by this work** &mdash; `.github/workflows/`, pinned to the enforced toolchain |
| `H-4` | The vulnerability scan was never executed in a prior implementation | [`docs/project-guide.md:L50`] | **Closed by this work** &mdash; bound to `verify`, with a skip reported honestly when taken |
| `H-5` | A page omitted from the `mkdocs.yml` `nav` silently never publishes, and **a strict build does not catch it either**, because the omission is reported at INFO level by default | `mkdocs.yml` `nav` and its absent `validation` block; [`catalog-info.yaml:L22`] | Add the `nav` entry in the same change as the page, **and** set `validation.nav.omitted_files: warn` so a strict build can fail on it ([§7.5](#75-documentation-rendering)) |
| `H-6` | **Absent host JDK and Maven** on a host in the state of the first reading, which blocks a host-native `./mvnw` entirely | [§3.2](#32-two-dated-readings-of-the-authoring-host), first reading | Provision JDK 25 and let the wrapper supply Maven 3.9.11, **or** use the pinned-container build path, which needs only the container runtime. **Remediated on the second reading**, where both are present |

### Medium

| ID | Finding | Locator | Remediation |
|---|---|---|---|
| `M-1` | The report generation group carries **two conflicting retention limits** | [`app/jcl/DEFGDGB.jcl:L37-L38`] declares one value; [`app/jcl/REPTFILE.jcl:L26-L27`] declares another for the same group | **Resolved to 10**, the larger, because a single object-lifecycle value must be chosen. **This is the only legacy inconsistency this migration resolves** |
| `M-2` | Migration filenames are aliased between prose and the authored files | `src/main/resources/db/migration/V1__create_schema.sql` and its two siblings | Record the alias. **Ordering is unaffected**, because Flyway keys on the `V1__` / `V2__` / `V3__` prefix, not the descriptive tail |
| `M-3` | The coverage plugin is pinned **below** the version cited elsewhere | `pom.xml` coverage-plugin version property &mdash; 0.8.12 pinned, 0.8.14 cited | **The pinned 0.8.12 governs.** Record the divergence rather than advancing the pin unilaterally |
| `M-4` | The screen-field census circulating in earlier prose is wrong and internally inconsistent | `app/cpy-bms/**`, in particular the account-view map | Cite the derived census of **441** input fields, with the account-view map at **37** rather than 36 |
| `M-5` | The procedural-label total circulating in earlier prose matches neither defensible expansion total | `app/cbl/**`, `app/cpy/CSUTLDPY.cpy`, `app/cpy/CSSTRPFY.cpy` | Cite the derived base and state the expansion convention beside it, rather than reconciling the figures by force |
| `M-6` | **User deletion has no self-delete guard**, so a signed-on administrator can delete their own record | `app/cbl/COUSR03C.cbl` &mdash; no comparison of target against signed-on identifier | **Preserved deliberately**, not corrected ([§11.6](#116-never-add-a-guard-the-source-does-not-have)). Adding a guard is a behaviour change requiring a labelled deviation |
| `M-7` | **Identifier generation is inherently racy** &mdash; a descending browse for the maximum key plus one | The browse idiom in the transaction-add and bill-payment paths | **Retained for parity.** A collision surfaces as a duplicate-record conflict. A database sequence would change generated values and break baseline comparison ([§11.5](#115-never-replace-the-legacy-identifier-generation-with-a-database-sequence)) |

### Low

| ID | Finding | Locator | Remediation |
|---|---|---|---|
| `L-1` | A batch job misspells its own job name | [`app/jcl/OPENFIL.jcl:L1`] | Report it. **Preserved, not corrected** &mdash; the corpus is frozen |
| `L-2` | The service-catalogue entry carries four inaccurate metadata declarations: the component type at [`catalog-info.yaml:L35`], the owning system at [`:L38`], the tag set at [`:L7-L18`] and a documentation link at [`:L31`] | `catalog-info.yaml` | Correct them in **separate work**. **Explicitly out of scope here**, because that file must remain unchanged by this migration ([§11.12](#1112-do-not-modify-existing-repository-files-beyond-the-three-permitted)) |
| `L-3` | A remark in `../README.md` states that no queue listener exists in `src/main/java`. **Verified stale**: exactly one is present | `drainReportJobQueue` at [`src/main/java/com/cardemo/config/BatchConfig.java:L1231`]; confirm with `grep -rn "@SqsListener" src/main/java`, which finds one declaration plus comment references | Refresh the remark when `README.md` is next revised. Cosmetic &mdash; it understates what is implemented rather than overstating it, so it cannot cause a wrong change |

---

## 14. Status, "Not available" register and cross-references

### 14.1 What is not available

Rule 1 clause F requires that missing information be named rather than papered over. These are
the outstanding items, in that exact wording.

| Item | Status | What is needed to close it |
|---|---|---|
| Gate results 1, 2, 3, 4, 5, 6 and 8 | **Not available** &mdash; implementation and evidence not yet published | Execute each gate and publish the artefact a reviewer would open. See [validation-gates.md](validation-gates.md), which names the specific missing artefact per gate |
| A legacy output baseline to diff against for Gate 1 | **Not available** | A captured legacy run, or an agreed synthetic baseline, against which byte-level comparison can be performed |
| Any coverage, throughput or latency figure | **Not available** | A completed `./mvnw clean verify` in a given environment, producing `target/site/jacoco/index.html`, plus a recorded measurement run for Gate 3 |
| A service-level objective for Gate 3 to compare against | **Not available** and **cannot be supplied from the source** &mdash; the COBOL publishes none, and none may be invented | Nothing. Gate 3 is deliberately a **measured baseline**, not a target |
| The program behind CICS transaction `CDV1` | **Not available** &mdash; `COCRDSEC` has no source file anywhere in the repository | Nothing. It is a dangling legacy definition; **no operation is published for it** ([§2.3](#23-the-17-versus-18-reconciliation)) |

**Nothing on this page claims the implementation is complete, that tests pass, or that any
coverage, performance or gate result has been achieved.** Gate results may be read from
[validation-gates.md](validation-gates.md) and nowhere else.

**A specific caution about [`docs/project-guide.md`](project-guide.md).** It is retained
**unchanged** as prior-run evidence, and it is a useful record of what a previous attempt
reported. **Its completion percentage, effort figures, test counts, coverage figures and
gate-pass rows are not evidence for the current implementation.** A concrete demonstration:
[`docs/project-guide.md:L213`] records an onboarding guide as passing, for a file that did not
exist at the anchor commit &mdash; this page is that file, created now. **Use it for exactly one
thing:** the four open defects it discloses that this migration closes &mdash; no CI/CD
pipeline [`:L49`], the vulnerability scan not executed [`:L50`], the absent production Spring
profile [`:L51`], and the hardcoded JWT secret [`:L52`], [`:L215`].

### 14.2 Verification performed for this page

| Check | Result |
|---|---|
| Host tooling | Verified by invocation on **6 August 2026 at 14:50 UTC** &mdash; see the second reading in [§3.2](#32-two-dated-readings-of-the-authoring-host) |
| Corpus counts, field census and every source locator cited here | Verified by machine at the anchor commit; the corrected line ranges match `../TRACEABILITY_MATRIX.md` §15.1 |
| `mkdocs build --strict` | Run on this host. The outcome, including its exit status and the reason for it, is recorded in [§14.3](#143-documentation-build-outcome) |
| Files created or modified by this page's authoring | **Exactly one**: `docs/onboarding-guide.md`. [`docs/index.md`](index.md) and [`docs/project-guide.md`](project-guide.md) are byte-for-byte unchanged |

### 14.3 Documentation build outcome

Recorded from a real invocation rather than asserted. Documentation rendering is **not** one of
the eight gates; this is recorded because clause F requires evidence to be cited when it exists.

| Item | Value |
|---|---|
| Tooling | MkDocs **1.6.1** with the `techdocs-core` and `mermaid2` plugins that `mkdocs.yml` declares |
| Command | `mkdocs build --strict` with the site directory written **outside the repository**, so no build output is committed |
| Exit status | **1 &mdash; aborted with 9 warnings in strict mode** |
| Warnings attributable to **this page** | **2**, both of them a link to `executive-presentation.html`, a document created in the same batch |
| Warnings attributable to other pages | 7, every one of the same kind, from `api-contracts.md` (1), `architecture-before-after.md` (3) and `validation-gates.md` (3) |
| Warnings of any other kind | **0** |
| This page's rendered output | Produced successfully: **32 tables**, **11 fenced code blocks**, **81 heading anchors**, **all 260 in-page links resolving to a real element id**, and **no syntax-highlighting error token** |

> **What that failure means, and what it does not.** The build fails **only** because one
> document this page links to has not landed yet. The link filename is already the one the
> `nav` will carry, so it resolves when that document arrives, and **rewriting it to silence an
> interim warning would leave a wrong link behind after the warning disappeared.** **No warning
> concerns this page's own structure, tables, code fences or anchors.**
>
> Note also what this page's arrival *fixed*: the earlier run recorded at
> [validation-gates.md §2.5](validation-gates.md#env-mkdocs) reported 20 warnings, several of
> which were links to this very page from its siblings. Those are now resolved, which is why
> the count is 9.
>
> **This is not a pass for any of the eight gates** &mdash; documentation rendering is not one
> of them. And note from [§7.5](#75-documentation-rendering) that a **clean** strict build would
> still not prove the `nav` is complete, because an omitted page is reported only at INFO level.

### 14.4 Cross-references

Everything this page deliberately does not duplicate.

| Document | What it owns |
|---|---|
| [Home](index.md) | The documentation entry point |
| [Project Guide](project-guide.md) | **Prior-run evidence, retained unchanged.** Read [§14.1](#141-what-is-not-available) before relying on any figure in it |
| [Technical Specifications](technical-specifications.md) | The full specification, including the Agent Action Plan, scope boundaries and transformation mapping |
| [API Contracts](api-contracts.md) | The **field-level contract** for all 17 operations: paths, methods, request and response fields, roles, status codes, the error envelope, pagination and the two numeric parsers |
| [Architecture Before and After](architecture-before-after.md) | The **before-and-after architecture**, the entity-relationship diagram, the package graph, the mechanism-substitution table, the deviations and the preserved quirks |
| [Validation Gates](validation-gates.md) | The **authoritative gate ledger**, the dated host-environment evidence and the residual-risk register |
| [Executive Presentation](executive-presentation.html) | A static stakeholder summary &mdash; not an application interface |
| `../README.md` | The repository front door: the **authoritative legacy transaction, program and job-control inventory tables**, and the canonical build, run and verify command reference |
| `../CONTRIBUTING.md` | Contribution process, branch expectations, CI engagement and security reporting |
| `../CODE_OF_CONDUCT.md` | The adopted code of conduct |
| `../DECISION_LOG.md` | Every mechanism substitution, labelled deviation and preserved quirk, each citing its source locator. **A repository-root evidence artefact, outside the MkDocs `docs_dir`** |
| `../TRACEABILITY_MATRIX.md` | Paragraph-level mapping from all 28 programs to their Java methods, and the corrected line-range citations. **A repository-root evidence artefact, outside the MkDocs `docs_dir`** |

---

*Anchor commit `7756d895ffeb65f7ea72aaa609e356d9899afcec`. Package root `com.cardemo`.
Licensed under the Apache License, Version 2.0. Copyright Amazon.com, Inc. or its affiliates.
All Rights Reserved.*
