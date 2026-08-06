<!--
================================================================================
  Document    : validation-gates.md
  Application : CardDemo
  Type        : Documentation - validation-gate evidence ledger
  Function    : Defines the eight validation gates of the COBOL to Java 25 /
                Spring Boot 3.5.11 migration, records the evidence each gate
                must produce, states the current result of every gate honestly,
                and carries the severity-classified residual-risk register.
  Derived from: app/data/ASCII/** (nine fixtures), app/jcl/DUSRSECJ.jcl,
                app/jcl/POSTTRAN.jcl, app/jcl/CREASTMT.JCL, app/jcl/COMBTRAN.jcl,
                app/jcl/INTCALC.jcl, app/jcl/DEFGDGB.jcl, app/jcl/REPTFILE.jcl,
                app/jcl/DEFCUST.jcl, app/jcl/OPENFIL.jcl, app/proc/TRANREPT.prc,
                app/catlg/LISTCAT.txt, app/csd/CARDDEMO.CSD,
                app/cbl/CBTRN02C.cbl, app/cbl/CBACT04C.cbl,
                app/cbl/CBTRN03C.cbl, app/cbl/COACTUPC.cbl,
                app/cbl/COUSR03C.cbl, app/cbl/CBSTM03A.CBL,
                app/cpy/CVTRA07Y.cpy
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

# Validation Gates

<a id="about"></a>

## 1. About this document

This is the **evidence ledger** for the eight validation gates of the AWS CardDemo
migration from IBM Enterprise COBOL under CICS, VSAM, JCL and BMS to Java 25 with
Spring Boot 3.5.11. It defines each gate, states what must exist before the gate can
run, gives the literal command that runs it, enumerates the assertions that decide
pass or fail, names the artefact a reviewer opens, and — for every gate — records the
**current result**.

The legacy corpus under `app/` is **frozen** and is licensed under the Apache License
2.0, copyright Amazon.com, Inc. or its affiliates — see the repository `LICENSE` and
`NOTICE`. Every figure, locator and literal published below was measured directly out
of that corpus and carries an inline `[<path>:<locator>]` citation. The anchor commit
for those citations is `7756d895ffeb65f7ea72aaa609e356d9899afcec` (short `7756d89`).

> **No gate in this ledger is reported as passed, and none may be.**
>
> Every **Result** field below begins with the words **Not available**, because no
> gate has been executed with its evidence captured, published and linked here. That
> wording is not hedging: it is the disclosure the project's single user-specified
> rule, *Rule 1: Build Verify*, requires under clause F — "If information is missing,
> state 'Not available' and list what's needed". Each Result therefore also states
> **what is needed** to close the gate.

### 1.1 Stable anchors

`../DECISION_LOG.md` and `../TRACEABILITY_MATRIX.md` cite gate sections by number, so
each gate section carries an explicit, permanent anchor in addition to the slug its
heading generates:

| Gate | Stable anchor | Gate | Stable anchor |
|---|---|---|---|
| 1 — End-to-end boundary parity | `#gate-1` | 5 — API contract verification | `#gate-5` |
| 2 — Zero-warning build | `#gate-2` | 6 — Security audit | `#gate-6` |
| 3 — Performance baseline | `#gate-3` | 7 — Scope coverage | `#gate-7` |
| 4 — Named fixture validation | `#gate-4` | 8 — Integration sign-off | `#gate-8` |

Those eight identifiers are a published contract. Renaming a gate heading is
permitted; removing its anchor is not, because it silently breaks every inbound
citation.

Every in-page link on this page targets an **explicit `<a id="…">` anchor** rather than
a generated heading slug. That is deliberate: a slug depends on how the renderer treats
punctuation in a heading, so a heading carrying an em dash or a comma can slugify two
different ways under two different plugin sets — and a link built on the wrong guess
fails silently. Explicit anchors are renderer-independent, so an in-page link either
resolves everywhere or nowhere.

### 1.2 What this document is not

| Not covered here | Where it lives |
|---|---|
| The REST contract — routes, request and response fields, status codes, error bodies | [api-contracts.md](api-contracts.md) |
| Legacy-versus-target architecture, topology and diagrams | [architecture-before-after.md](architecture-before-after.md) |
| Developer setup, how to provision the toolchain, build and run locally | [onboarding-guide.md](onboarding-guide.md) |
| The transformation plan, scope boundaries and mapping tables | [technical-specifications.md](technical-specifications.md) |
| Stakeholder summary | [executive-presentation.html](executive-presentation.html) |
| Documentation entry point | [index.md](index.md) |
| Every mechanism substitution and every preserved legacy quirk | `../DECISION_LOG.md` (repository-root artefact, outside the MkDocs `docs_dir`) |
| Paragraph-level COBOL-to-Java mapping for all 28 programs | `../TRACEABILITY_MATRIX.md` (repository-root artefact, outside the MkDocs `docs_dir`) |

Those documents are **linked, never duplicated**. This page confines itself to gates,
evidence and residual risk. Where a gate needs a fact that belongs to another page —
a field width, a route, a setup step — it links rather than restates, so the two
cannot drift apart.

<a id="how-executed"></a>

### 1.3 How a gate is executed

Four command shapes cover all eight gates. Every Maven invocation uses the **pinned
wrapper** `./mvnw`, never a bare `mvn`: the wrapper resolves Maven 3.9.11 from
`.mvn/wrapper/maven-wrapper.properties` and verifies its distribution checksum, which
is what makes the build reproducible on a machine that has no Maven at all.

| # | Command | Covers |
|---|---|---|
| C1 | `./mvnw -B -ntp clean verify` | Compile, unit tier, integration tier, end-to-end tier, coverage floor and the dependency-vulnerability scan — the whole of [Gate 2](#gate-2), and the execution of the assertions belonging to Gates 1, 3, 4, 5, 6 and 7 |
| C2 | `./mvnw -B -ntp -Dit.test=GateVerificationTest verify` | The gate harness alone: `src/test/java/com/cardemo/e2e/GateVerificationTest.java` |
| C3 | `./mvnw -B -ntp test -Dtest=<ClassName> -Djacoco.skip=true` | One named unit-tier class, for a focused re-check |
| C4 | `docker compose up -d --wait` followed by health, migration and object-store verification | [Gate 8](#gate-8) |

Two qualifications on C1 matter, and neither is cosmetic:

* **`-Ddependency-check.skip=true` is a documented convenience, not a shortcut through
  a gate.** The scan is bound to `verify` by default, so a routine offline build that
  passes the skip flag has **not** executed the scan half of [Gate 2](#gate-2) and
  cannot be cited for it. `.github/workflows/build.yml` runs the build with that flag
  and reports the scan separately, so the skip is visible rather than silent.
* **The end-to-end tier is bound to Failsafe, not Surefire.** `pom.xml` gives Failsafe
  the includes `**/integration/**/*Test.java` and `**/e2e/**/*Test.java`, so a `test`
  goal alone never runs the harness. A gate claim resting on `./mvnw test` is
  therefore a claim about the unit tier only.

### 1.4 How evidence is recorded

Every gate section below carries the **same eleven fields, in the same order, with
none omitted**:

| # | Field | What it holds |
|---|---|---|
| 1 | **Objective** | What the gate proves, in one or two sentences |
| 2 | **Prerequisites** | Everything that must exist or be provisioned first |
| 3 | **Exact command or test** | The literal command line, or the fully-qualified test class and method |
| 4 | **Input** | The exact fixture paths, row counts and record widths, or the endpoint set |
| 5 | **Expected assertions** | Enumerated and machine-checkable, specific enough that two engineers agree on the verdict |
| 6 | **Evidence and report paths** | The artefacts a reviewer opens |
| 7 | **Severity on failure** | Blocker / High / Medium / Low, per Rule 1 clause F |
| 8 | **Remediation** | The concrete steps to take when the gate fails |
| 9 | **Execution date, tool versions, exit code** | Populated only from a real run |
| 10 | **Result** | The gate's standing, always leading with **Not available** until evidence exists |
| 11 | **Residual risks** | What the gate does not prove even when it passes |

Report artefacts land under `target/`, which is build output and is excluded from
version control. **That is deliberate**: an evidence file inside the repository would
be indistinguishable from an evidence file that was authored rather than produced. The
consequence is that evidence must be re-generated to be re-read, and the command that
generates it is published in field 3 of every gate for exactly that reason.

<a id="never-a-pass"></a>

### 1.5 Design is never a pass

A gate is closed by a **generated artefact**, not by a description of how it would be
closed. Three specific substitutions are forbidden anywhere in this ledger:

1. **A specification is not a measurement.** That a coverage floor of 80 % line
   coverage is configured — `pom.xml` sets `<jacoco.line.coverage.minimum>0.80` and
   `<haltOnFailure>true` — says what the build will refuse. It does not say what the
   tree achieves. Only a generated report says that.
2. **An implementation's own output is not a parity oracle.** Comparing the Java
   posting run against a golden file produced by the same Java posting run is
   circular. See [Gate 1](#gate-1), where this is the governing constraint rather
   than a caveat.
3. **A prior run's numbers are not this run's numbers.** See [§1.6](#prior-run).

<a id="prior-run"></a>

### 1.6 project-guide.md is prior-run evidence, and is not gate evidence

`docs/project-guide.md` is retained byte-for-byte unchanged. It describes an **earlier
migration attempt**. Its completion percentage, its hour counts, its test counts, its
coverage figures, its compliance table and its gate-pass rows — including every pass
marker in that document — **are not evidence for the current implementation and must not
be cited as gate results anywhere.** Any figure from that page reproduced here would be
a prior run's figure wearing this run's label.

It is nevertheless load-bearing in one respect: it discloses four open defects, and
this migration closes all four. They are the only content of that page cited in this
ledger.

| Prior-run open defect | Locator | Closed by |
|---|---|---|
| No CI/CD pipeline | [`docs/project-guide.md:L49`] | `.github/workflows/build.yml` — see [Gate 2](#gate-2) |
| OWASP dependency scan not executed | [`docs/project-guide.md:L50`] | `dependency-check-maven` 12.1.0 bound to `verify` with `failBuildOnCVSS` 7 — see [Gate 2](#gate-2) and [Gate 6](#gate-6) |
| No production Spring profile | [`docs/project-guide.md:L51`] | `application-prod.yml`, with every secret externalised — see [Gate 6](#gate-6) |
| JWT secret hardcoded in config | [`docs/project-guide.md:L52`], [`:L215`] | Environment indirection with no committed default in all four profiles — see [Gate 6](#gate-6) |

<a id="frozen-app"></a>

### 1.7 No gate may touch `app/`

`app/**` is the **parity oracle, the field-contract source and the traceability
anchor** simultaneously, and it loses all three roles the moment it is edited. No
gate, no test and no remediation step in this ledger modifies, moves, renames,
reformats or normalises anything beneath it. Where a gate needs fixture data it reads
`app/data/ASCII/**` in place, or works from a copy under `src/test/resources/`
**carried across under the fixture's own name**. `.github/workflows/build.yml` opens
with a *Frozen corpus guard (`app/` must be unmodified)* job, so a change under `app/`
fails continuous integration before any gate is reached.

---

## 2. Authoring-host environment evidence

Four of the eight gates need a container runtime and seven need a JDK, so the state of
the authoring host is not background detail — it decides which gates are runnable at
all. This section publishes **two dated, machine-verified readings** of that host. The
second supersedes the first where they differ, and the differences are stated rather
than quietly folded away.

<a id="env-first"></a>

### 2.1 First reading — Thursday, 30 July 2026 at 06:56 UTC

| Tool | Result |
|---|---|
| `date -u` | Thu Jul 30 06:56:54 UTC 2026 |
| `docker --version` | Docker version 29.6.2, build dfc4efb |
| `docker compose version` | Docker Compose version v5.3.1 |
| `docker info --format '{{.ServerVersion}}'` | 29.6.2 — daemon reachable |
| `java` | NOT FOUND |
| `javac` | NOT FOUND |
| `mvn` | NOT FOUND |
| `localstack` | NOT FOUND |
| `aws` | NOT FOUND |
| `mkdocs` | NOT FOUND |

<a id="env-second"></a>

### 2.2 Second reading — Thursday, 6 August 2026 at 09:34 UTC

Every row below was produced by invoking the tool on the authoring host and copying
its own output. Nothing here is inferred from a manifest or a setup script.

| Tool | Result | Change since 30 July |
|---|---|---|
| `date -u` | Thu Aug  6 09:34:34 UTC 2026 | — |
| `docker --version` | Docker version 29.7.0, build c1eba93 | patch release |
| `docker compose version` | Docker Compose version v5.3.1 | unchanged |
| `docker info --format '{{.ServerVersion}}'` | 29.7.0 — daemon reachable | patch release; still reachable |
| `java -version` | openjdk version 25.0.3 2026-04-21 LTS, Temurin-25.0.3+9 | **has since been provisioned** |
| `javac -version` | javac 25.0.3 | **has since been provisioned** |
| `mvn -v` | Apache Maven 3.9.11 | **has since been provisioned** |
| `./mvnw -v` | Apache Maven 3.9.11, resolved into `~/.m2/wrapper/dists` | wrapper resolves the pinned version |
| `localstack --version` | LocalStack CLI 4.14.0 | **has since been provisioned** |
| `aws --version` | aws-cli/1.46.0 Python/3.13.7 | **has since been provisioned** |
| `mkdocs --version` | command not found; no `mkdocs` Python module either | unchanged at 09:34. **Provisioned later the same day and then run — see [§2.5](#env-mkdocs)** |

<a id="env-consequences"></a>

### 2.3 What the two readings mean, gate by gate

**A container runtime is available.** Docker Engine 29.7.0 with `docker compose`
v5.3.1 is installed and its daemon answers, on both readings. Any statement that
Docker is unreachable or that this project cannot run containers is false and must not
be repeated: the claim appears in an earlier generation of
[technical-specifications.md](technical-specifications.md) and is withdrawn here.
Consequently **Gates 1, 4, 5 and 8 are pending implementation and execution, not
intrinsically container-blocked.** As re-measured on 6 August 2026, the Java
implementation is present on this branch as well, so what remains outstanding for
those four gates is **execution and the publication of evidence** — nothing about the
host prevents it.

**The host toolchain prerequisite for [Gate 2](#gate-2) was unmet on the first reading
and is met on the second.** On 30 July a host-native `./mvnw clean verify` could not
run at all, because `java`, `javac` and `mvn` were all missing; the documented
remediation was, and remains, a **pinned-container execution path** — a container
image pinned to JDK 25 carrying Maven 3.9.11, with the repository and the Maven cache
mounted into it. That path needs only the runtime proven present above, so it was
feasible even on the first reading. On the second reading JDK 25.0.3 and Maven 3.9.11
are both on the host, so the host-native path is available too. **Neither reading is
evidence that the gate passed**: provisioning a toolchain and running a gate are
different acts, and only the second produces an artefact.

**The absent host `localstack` and `aws` CLIs do not block anything.** LocalStack runs
as a **compose service** and the integration tiers start it through Testcontainers, so
the cloud-service gates depend on the container runtime, never on a host CLI. The
distinction matters because the two are easily conflated: a host without those CLIs
can still execute Gates 4, 5 and 8 in full, while a host without a container runtime
cannot, whatever CLIs it has. Both binaries are present on the second reading in any
case, which is convenient for manual inspection and required by nothing.

**`mkdocs` was missing at 09:34, so documentation rendering could not be verified at
that point.** The prerequisite was then met rather than left standing: MkDocs 1.6.1 was
provisioned with the two plugins `mkdocs.yml` declares, `techdocs-core` and `mermaid2`,
and `mkdocs build --strict` was run. [§2.5](#env-mkdocs) records what that run actually
reported, including its non-zero exit status and the reason for it. Nothing in this
ledger claims a documentation build that was not run, and nothing claims a clean one.

<a id="env-nav"></a>

### 2.4 Documentation publication is a silent-failure path

This deserves its own statement because it is the one failure in this ledger that
produces **no error and no output**.

`mkdocs.yml` carries an explicit `nav` block, and `catalog-info.yaml` sets
`backstage.io/techdocs-ref: dir:.` [`catalog-info.yaml:L22`]. Backstage TechDocs
therefore renders straight from that `nav`. **A page omitted from the `nav` never
appears in the published site, and nothing anywhere reports a problem.** The build
succeeds, the file sits in the repository, and the reader who needs it cannot reach
it.

| Item | Value |
|---|---|
| Severity | **High** |
| Locator | `mkdocs.yml` `nav`; [`catalog-info.yaml:L22`] |
| Required entry for this page | `Validation Gates: validation-gates.md` |
| Detection | **Weaker than assumed — measured, see below** |
| Remediation | Add the `nav` entry in the same change that adds the page, **and** raise the omission to a warning so a build can catch it. Five entries are required in total: `api-contracts.md`, `architecture-before-after.md`, `onboarding-guide.md`, `validation-gates.md` and `executive-presentation.html` |

> **A claim corrected here, from a measurement rather than from an assumption.**
>
> It is natural to assume `mkdocs build --strict` catches this, because `--strict`
> promotes warnings to errors. **It does not**, under this configuration. The run
> recorded in [§2.5](#env-mkdocs) reports the omitted pages at **INFO** level, because
> MkDocs defaults `validation.nav.omitted_files` to `info` and `mkdocs.yml` declares no
> `validation` block that would raise it. `--strict` acts on warnings, so an omitted page
> passes a strict build **silently**.
>
> That makes the finding **more** serious than first stated, not less: the failure is not
> merely error-free, it is warning-free by default. Making it detectable takes an explicit
> setting in `mkdocs.yml`:
>
> ```yaml
> validation:
>   nav:
>     omitted_files: warn
> ```
>
> Until that is set, the only reliable detection is a human reading the `nav`.

Two consequences follow for this page specifically. Its filename is fixed by the `nav`
entry above and **must not be changed**. And its links to
[onboarding-guide.md](onboarding-guide.md) and
[executive-presentation.html](executive-presentation.html) resolve only once those two
documents land alongside it; the link targets are already the final filenames the
`nav` will carry, so they are correct now and must not be rewritten to silence an
interim `--strict` warning.

<a id="env-mkdocs"></a>

### 2.5 Documentation build verification, as actually run

Recorded from a real invocation on this host on **6 August 2026**, after MkDocs was
provisioned. This is the one command in this ledger whose outcome is reported from
execution rather than left outstanding, and it is reported **including its failure**.

| Item | Value |
|---|---|
| Tooling | MkDocs 1.6.1, with the `techdocs-core` and `mermaid2` plugins that `mkdocs.yml` declares |
| Command | `mkdocs build --strict --site-dir <path outside the repository>` |
| Exit status | **1 — aborted with 20 warnings in strict mode** |
| Warnings attributable to **this page** | **7**, every one of them a link to a document created in the same batch: 4 to `onboarding-guide.md` and 3 to `executive-presentation.html` |
| Warnings attributable to other pages | 13, of the same two kinds, from `api-contracts.md` and `architecture-before-after.md` |
| Warnings of any other kind | **0** |
| This page's rendered output | Produced successfully: **67 tables** and **11 fenced code blocks**, **all 8 `#gate-N` anchors present in the rendered HTML**, **every one of its in-page links resolving to a real element id**, and **no syntax-highlighting error token** |
| Site directory | Written outside the repository, so no build output is committed |

**What the failure means, and what it does not.** The build fails **only** because two
documents this page links to have not landed yet. The link filenames are already the ones
the `nav` will carry, so they resolve when those documents arrive, and rewriting them to
silence the warning would leave a wrong link behind after the warning disappeared. **No
warning concerns this page's own structure, tables, code blocks or anchors.**

**What this is not.** It is **not** a pass for any of the eight gates: documentation
rendering is not one of them. It is recorded here because Rule 1 clause F requires
evidence to be cited when it exists, and because the alternative — leaving the
prerequisite marked outstanding after having met it — would be its own inaccuracy.

---

<a id="summary"></a>

## 3. Gate summary

Eight gates. **Every result below is the same, and it is the honest one.**

| # | Gate | Objective in one line | Container runtime | Current result |
|---|---|---|---|---|
| [1](#gate-1) | End-to-end boundary parity | Field-level and byte-level output parity for the daily posting pipeline against legacy behaviour | Required | **Not available** — no legacy baseline exists to diff against; see [Gate 1](#gate-1) |
| [2](#gate-2) | Zero-warning build | A repeatable, deterministic, warning-free build with a clean dependency-vulnerability scan | Partly — not for compile and scan; required for the integration and end-to-end tiers bound into `verify` | **Not available** — no captured build or scan artefact is published here |
| [3](#gate-3) | Performance baseline | A **measured baseline**, recorded rather than compared against a target | Partly — the database and cloud-service dependencies run in containers | **Not available** — no measurement has been captured, and no service-level objective exists to compare one with |
| [4](#gate-4) | Named fixture validation | All nine ASCII fixtures and the ten inline user records load correctly, with position-aware overpunch decoding and BCrypt hashing | Required | **Not available** — no seeded-load evidence is published here |
| [5](#gate-5) | API contract verification | All 17 operations exercised against a real Spring application context, with role enforcement, statelessness and failure mapping asserted | Required | **Not available** — no captured integration-tier report is published here |
| [6](#gate-6) | Security audit | The security invariants hold across the whole tree | Not required | **Not available** — no captured audit or scan artefact is published here |
| [7](#gate-7) | Scope coverage | All 28 COBOL programs mapped at paragraph level through a machine-checkable matrix | Not required | **Not available** — the matrix the gate reads is owed at `../TRACEABILITY_MATRIX.md` |
| [8](#gate-8) | Integration sign-off | The full runtime topology stands up and is healthy | Required — **and it is available** | **Not available** — the stack has not been brought up and verified for this ledger |

**How to read that column.** "Not available" is a statement about *published, linked
evidence in this ledger*, which is the only thing a gate result may rest on. It is not
a claim that the implementation is missing, and it is not a claim that a gate would
fail. It says precisely this: the artefact a reviewer would open does not exist yet,
and until it does, no verdict may be entered.

---

<a id="gate-1"></a>

## 4. Gate 1 — End-to-end boundary parity

**Objective.** Prove **field-level and byte-level output parity** for the daily
transaction posting pipeline: that driving the legacy input fixture through the Java
posting job produces the same posted transactions, the same account and
category-balance mutations, and the same reject records — byte for byte at the record
boundary — as the COBOL program does.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | The posting pipeline, its reader, processor and both writers | Present on this branch |
| 2 | The input fixture at its actual name, `app/data/ASCII/dailytran.txt` | Present, frozen, read in place |
| 3 | A container runtime, for the Testcontainers PostgreSQL and LocalStack tiers | Present — Docker Engine 29.7.0, verified [§2.2](#env-second) |
| 4 | JDK 25 and Maven 3.9.11, or the pinned-container execution path | Present on the host as of the second reading |
| 5 | **An approved expected-output baseline, captured from the legacy system or agreed as a golden file** | **Not available.** This is the gate's binding prerequisite and it is unmet — see the constraint below |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

The boundary assertions live in
`src/test/java/com/cardemo/e2e/GateVerificationTest.java`; the method that reports this
gate's standing is `gateOneBoundaryBaselineIsReportedAsNotAvailable`, and the
end-to-end pipeline exercise is `src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java`.
Both sit under `**/e2e/**`, which `pom.xml` binds to `maven-failsafe-plugin` and
excludes from `maven-surefire-plugin`, so **`./mvnw test` never runs either of them**.

**Input.**

| Property | Value |
|---|---|
| Fixture | `app/data/ASCII/dailytran.txt` |
| Size | **105,300 bytes** |
| Records | **exactly 300** |
| Record width | **350 bytes** |
| Arithmetic | 300 × (350 + 1 newline) = 105,300 — the fixture is complete, with no partial trailing record |
| Sign census at the amount field, column 143 | **250 positive, 50 negative** |
| Originating timestamp, columns 279-304 | one single instant across all 300 rows, `2022-06-10T19:27:53` |

**The filename is `dailytran.txt`.** The mainframe DD name and dataset spell it
`DALYTRAN`, but the ASCII fixture spells the word in full, and a test resource path
built from the mainframe spelling resolves to nothing. Every path in this ledger and
every test resource uses the fixture's own name.

**The fixture carries genuinely negative amounts and must not be normalised.** It
contains both the `{` and the `}` overpunch characters — 25 rows carry `{` and 6 carry
`}` — which is what makes it exercise the cycle-debit branch of
`CBTRN02C.2800-UPDATE-ACCOUNT-REC` [`app/cbl/CBTRN02C.cbl:L547-L552`]. That branch adds
a negative amount **to the debit accumulator**, so the accumulator legitimately holds
negative values, with no absolute-value normalisation anywhere on the path. This is
also why the over-limit expression at [`app/cbl/CBTRN02C.cbl:L403-L405`] *subtracts*
that accumulator and must be transcribed rather than algebraically rewritten.

**Expected assertions.**

1. The 300 input records produce a **deterministic** set of posted transactions,
   account updates and transaction-category-balance upserts: the same input at the same
   starting state yields the same output on every run.
2. Reject records are emitted at **exactly 430 bytes** — a 350-byte transaction image
   plus an 80-byte trailer, the trailer being a four-digit reason code plus a
   76-character description [`app/cbl/CBTRN02C.cbl:L176-L182`] — independently
   confirmed by `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` on the reject dataset
   [`app/jcl/POSTTRAN.jcl:L36`].
3. Reject codes are drawn from **exactly five** constants: 100, 101, 102, 103 and 109.
4. **When both the over-limit and the expiry check fail, a single reject record bearing
   103 is written — 103 overwrites 102.** The two checks are sequential and unguarded,
   with no alternative branch and no early exit between them
   [`app/cbl/CBTRN02C.cbl:L393-L422`]; an implementation that guards the second check,
   or that emits two reject records, diverges.
5. The expiry comparison is a **string** comparison of the account expiry field against
   the first ten characters of the transaction's **originating** timestamp — not the
   processing timestamp — at [`app/cbl/CBTRN02C.cbl:L414`], where the source's
   misspelling of the field name is part of the field contract.
6. Generated timestamps are **26 characters ending in four zeros**, formatted at
   millisecond precision rather than nanosecond precision.
7. The run sets **return code 4 if and only if the reject count exceeds zero**
   [`app/cbl/CBTRN02C.cbl:L229-L231`]. There is no other determinant.
8. The four-character file-status rendering matches the legacy format exactly,
   including the literal `FILE STATUS IS: NNNN` prefix and the expansion of a
   non-numeric or `9`-leading status into one status byte plus three digits
   [`app/cbl/CBTRN02C.cbl:L714-L731`].
9. The three writes the source commits independently — the category-balance upsert
   [`:L467-L500`], the account update [`:L545-L560`] and the transaction insert
   [`:L424-L465`] — are one atomic unit in Java. This is a **labelled deviation, not
   parity**; see [§12.5](#deviations).

**Evidence and report paths.**

| Artefact | Path | What a reviewer reads |
|---|---|---|
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` | Proof the harness actually executed, with the runtime that ran it. Its **absence after a completed build is itself proof of a non-run** |
| Harness summary | `target/gate-verification/gate-verification-summary.properties` | The recorded figures, keyed by gate |
| End-to-end tier report | `target/failsafe-reports/` | Per-test outcome and the failure text |
| Parity comparison report | `target/gate-verification/gate1-boundary-parity.txt` | The field-level diff, **once a baseline exists to diff against** |

**Severity on failure.** **Blocker.** Parity is the acceptance contract of the engagement.
A boundary divergence means the target computes different money from the source, which
no other gate compensates for.

**Remediation.**

* **A record-geometry failure** — anything other than 430 bytes, or a trailer that does
  not decompose as 4 + 76 — is a writer defect. Fix the writer to the declared length;
  do not adjust the assertion.
* **A reject-code failure** where both 102 and 103 conditions hold and two records are
  written, or where 102 survives, means a guard was added between the two checks.
  Remove the guard and restore the sequential form.
* **A timestamp mismatch** in the trailing four digits means nanosecond precision
  leaked in. Format to milliseconds and append four zeros.
* **A sign failure** — a debit accumulator that never goes negative — means an
  absolute-value normalisation was introduced. Remove it.
* **A missing baseline** is not remediated by generating one from the Java side. See
  immediately below.

**The constraint that governs this gate: there is no legacy baseline in this
repository.**

`app/data/ASCII/` holds **input fixtures only**. An exhaustive search for captured
expected output — across `expected`, `baseline` and `golden` names, SYSOUT captures,
and the reject, report and statement dataset names — returns only three dataset
*definition* members, `app/jcl/DALYREJS.jcl`, `app/jcl/TRANREPT.jcl` and
`app/proc/TRANREPT.prc`, and **zero captured data**. There is no legacy posting output,
no reject-file snapshot and no report snapshot anywhere under `app/`.

Three routes to a substitute are each unavailable rather than merely unattractive:

1. **Fabricating expected bytes invents evidence.** It would produce a file that looks
   like a measurement and is not one. `src/test/resources/expected`,
   `src/test/resources/baseline` and `src/test/resources/golden` are therefore asserted
   **not** to be present, so a baseline cannot be introduced quietly.
2. **Asserting against the implementation's own output is circular.** A golden file
   produced by the Java posting job proves only that the job is deterministic. Where
   such a file is used, it must be labelled a **regression net**, which is a real and
   useful thing, and must never be called a parity oracle.
3. **Hand-simulating an expected total is model-sensitive, so it is not an oracle
   either.** The posting program re-reads the account for every transaction
   [`app/cbl/CBTRN02C.cbl:L395`] while mutating that same account's cycle accumulators
   [`:L545-L560`], so a stateless single-pass model and a stateful model of the same
   source disagree over these exact fixtures. A figure two faithful readings of the
   source disagree about cannot arbitrate between them.

What the fixtures alone **do** prove is asserted rather than deferred: the 430-byte
reject geometry, the 250-to-50 sign census at column 143, and the reachability of each
reject code — over these fixtures only code 102 is reachable, so a run over them ends
with the completed-with-rejects return code of [`app/cbl/CBTRN02C.cbl:L229-L231`].

**Execution date, tool versions, exit code.** **Not available** — this gate has not
been executed as a parity diff, because the oracle it diffs against does not form part
of this repository.

**Result.** **Not available** — the end-to-end boundary baseline is missing, so the
gate cannot be executed as a true parity diff. **What is needed:** a captured DALYREJS
430-byte reject dataset plus the resulting TRANSACT, ACCTDATA and TCATBALF images from
a real POSTTRAN execution at a known input state — that is, one legacy run of
`app/jcl/POSTTRAN.jcl` over `app/data/ASCII/dailytran.txt` with its outputs preserved,
or a written agreement that a named Java-produced file is adopted as a **golden
regression file** with its non-oracle status recorded. Until one of those two exists,
the deterministic-output, record-geometry, reject-code and return-code assertions above
are executable and the parity diff is not.

**Residual risks.** Even with a baseline and a clean diff, this gate proves parity
**over these 300 fixture rows at one starting state**. It does not prove parity over
inputs the fixture does not contain: it never exercises reject codes 100, 101 or 103,
because only 102 is reachable over this data; it fixes one originating timestamp, so
date-boundary behaviour around expiry is exercised at a single point; and it says
nothing about concurrency, since the batch path is single-threaded by construction.

---

<a id="gate-2"></a>

## 5. Gate 2 — Zero-warning build

**Objective.** Prove that the build is **repeatable, deterministic and warning-free**,
that its test tiers pass, that measured line coverage meets the configured floor, and
that the dependency-vulnerability scan reports nothing at or above the configured
severity threshold.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | Implementation code and both test tiers | Present on this branch |
| 2 | **JDK 25 and Maven 3.9.11** | Absent on the first host reading; present on the second — JDK 25.0.3 (Temurin-25.0.3+9) and Maven 3.9.11, [§2.2](#env-second) |
| 3 | A container runtime, for the integration and end-to-end tiers bound into `verify` | Present |
| 4 | A vulnerability feed reachable by `dependency-check-maven`, or an explicit, reported skip | Feed reachability is environment-dependent and is **not** asserted here |
| 5 | A continuous-integration harness, so the run is repeatable rather than a one-off | `.github/workflows/build.yml` is present |

The second prerequisite is the one to watch. On a host without a JDK the gate does not
fail — it cannot start. The documented alternative is a **pinned-container execution
path**: a container image pinned to JDK 25 carrying Maven 3.9.11, with the repository
and the Maven cache mounted, which needs only the container runtime that
[§2.3](#env-consequences) proves present. It was available
even on the first reading, and it remains the reproducible way to run this gate on an
unprovisioned machine.

**Exact command or test.**

```bash
./mvnw -B -ntp clean verify
```

Use the wrapper. **A bare `mvn` invocation is not the documented command**, because it
runs whatever Maven the machine happens to have, which is exactly the
environment-specific assumption this gate exists to rule out. `-Xlint:all` and
`-Werror` are **configured inside the build**, at `pom.xml:L1232-L1233` within
`maven-compiler-plugin` 3.14.1 with `maven.compiler.release` 25 — they are not passed
ad hoc on the command line, so the gate cannot be weakened by omitting a flag.

The static half of the gate is additionally asserted by the harness methods
`GateVerificationTest` declares for it: the release level, lint and floor
configuration; the Failsafe and Surefire binding; and the dependency-coordinate
Blocker described below.

**Input.** The whole source tree — `src/main/java/**` (132 production classes),
`src/main/resources/**`, `src/test/java/**` and `pom.xml` — together with the resolved
dependency graph that `dependency-check-maven` scans.

**Expected assertions.**

1. Compilation emits **zero warnings** and the build exits `0`. With `-Werror` in
   force, one warning is one build failure.
2. `maven-enforcer-plugin` **3.5.0** asserts the **Java 25** and **Maven 3.9.11**
   floors, so the build refuses to run on a toolchain that would silently produce
   different bytecode.
3. All unit tests pass under `maven-surefire-plugin` **3.5.4**, and all integration and
   end-to-end tests pass under `maven-failsafe-plugin` **3.5.4** with the includes
   `**/integration/**/*Test.java` and `**/e2e/**/*Test.java`.
4. **JaCoCo `0.8.12`** reports at least **80 % LINE coverage** and **fails the build
   below it**: `pom.xml` sets `<jacoco.line.coverage.minimum>0.80` with
   `<haltOnFailure>true`, so a shortfall is a hard stop rather than a warning.
5. **OWASP `dependency-check-maven` 12.1.0** reports **no finding at CVSS 7 or above** —
   `<failBuildOnCVSS>7` — which is the configured expression of "zero HIGH and zero
   CRITICAL".
6. Every plugin and every non-BOM dependency is pinned to an **exact** version: no
   ranges, no `LATEST`, no `RELEASE`.
7. The gate harness writes its evidence artefact while it runs, so a **silent
   non-collection cannot masquerade as success**. A completed build with no artefact
   at `target/gate-verification/gate-verification-evidence.properties` proves the
   harness never ran, whatever the build said.

**Two findings this gate carries, both of which must be read before running it.**

***Blocker — the container library's 2.x line renamed every module coordinate.*** The
bare artefacts `localstack`, `postgresql` and `junit-jupiter` under the
`org.testcontainers` group **do not resolve at version 2.0.3**; only the prefixed
coordinates exist. Compounding it, Spring Boot 3.5.11 already manages a Testcontainers
version from the 1.x line and imports the Testcontainers bill of materials itself, so
adding a competing import produces an ordering-dependent resolution that may silently
select the managed 1.x version.

> **The remedy is twofold and both halves are required.**
>
> 1. Override the managed version through the **version property** —
>    `<testcontainers.version>2.0.3</testcontainers.version>` — and **do not import a
>    second bill of materials**.
> 2. Use **only** the prefixed coordinates: `org.testcontainers:testcontainers`,
>    `:testcontainers-localstack`, `:testcontainers-postgresql` and
>    `:testcontainers-junit-jupiter`.
>
> Overriding without renaming resolves artefacts that do not exist. Renaming without
> overriding resolves the wrong version. Either half alone still fails the build.

***Medium — coverage-plugin version drift.*** The prior implementation record cites
JaCoCo `0.8.14`. **The pinned requirement `0.8.12` governs**, and the divergence is
owed an entry in the planned `../DECISION_LOG.md` rather than resolved by advancing the
pin unilaterally. Any artefact naming `0.8.14` as the governing version is wrong; the
authoritative value is the `<jacoco-maven-plugin.version>` property in `pom.xml`.

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| Coverage report | `target/site/jacoco/index.html` |
| Unit-tier report | `target/surefire-reports/` |
| Integration and end-to-end tier report | `target/failsafe-reports/` |
| Vulnerability scan report | `target/dependency-check/dependency-check-report.html`, plus the JSON and SARIF renderings beside it |
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` |
| Continuous-integration run | The `build` workflow's uploaded `test-reports` and `jacoco-report` artefacts |

Those paths are what a reviewer opens. A build log is corroboration, not the evidence.

**Severity on failure.** **Blocker.** A build that does not compile clean, or that ships a
HIGH or CRITICAL dependency finding, blocks every other gate: nothing downstream can be
measured on a tree that will not build deterministically.

**Remediation.**

* **A compiler warning** is fixed at the source, never by removing `-Werror` or by
  narrowing `-Xlint`. Weakening the compiler configuration converts a failing gate into
  an unmeasured one.
* **An unresolvable Testcontainers coordinate** is the Blocker above. Apply **both**
  halves of the remedy.
* **A coverage shortfall** is fixed by adding tests to the uncovered lines the report
  names. Lowering `<jacoco.line.coverage.minimum>` is not remediation.
* **A finding at CVSS 7 or above** is fixed in this order: pin the affected dependency
  *forward* to a fixed version; failing that, record an evidence-based suppression with
  an explicit expiry so the finding returns rather than disappearing. Lowering
  `<failBuildOnCVSS>` or adding a blanket skip is not remediation.
* **An unreachable vulnerability feed** produces an empty report rather than a clean
  one. Report the skip explicitly — as `.github/workflows/build.yml` does — rather than
  treating an empty report as a pass.

**Execution date, tool versions, exit code.** **Not available** — no build has been
executed and captured for this ledger. The toolchain a run would use is recorded in
[§2.2](#env-second); toolchain presence is
not a build result.

**Result.** **Not available** — no captured build artefact is published or linked here.
**What is needed:** one `./mvnw -B -ntp clean verify` run to completion with its exit
status recorded, together with the four report paths above preserved and referenced
from this field, and — for the scan half — that same run performed **without**
`-Ddependency-check.skip=true` against a reachable vulnerability feed.

**Residual risks.** A clean build proves the tree compiles without warnings, that the
suites pass and that measured line coverage clears the floor. It does not prove the
tests are *good*: line coverage counts lines executed, not assertions made, so 80 %
line coverage is compatible with weak assertions. It does not prove branch or mutation
adequacy. And a dependency scan is only as current as the feed it read, so a clean scan
ages from the moment it is taken.

---

<a id="gate-3"></a>

## 6. Gate 3 — Performance baseline

**Objective.** **Record a measured baseline. Do not prove an improvement.**

> **No service-level objective exists anywhere in the legacy corpus.** The 28 COBOL
> programs, the 29 JCL members, the CICS resource definitions and the VSAM catalogue
> publish no throughput figure, no latency figure and no response-time commitment of
> any kind. There is consequently **nothing to reproduce and nothing to beat**, and no
> target may be invented to give this gate the appearance of a threshold.
>
> **This gate therefore has no pass condition. It has a measurement obligation.** It is
> discharged by publishing numbers with the conditions under which they were taken. It
> fails only by the absence of those numbers, which is a documentation gap rather than
> a correctness failure.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | Implementation code, running under a real application context | Present on this branch |
| 2 | A running database and the cloud-service emulator | Provided by containers, which are available |
| 3 | A **documented** load-generation approach — sample count, warm-up policy, whether the run is cold or warm | Owed: it must be written down, because a measurement whose method is unstated cannot be reproduced or compared |
| 4 | A recorded machine class — core count, memory, whether the host is shared | Owed at measurement time |
| 5 | A stated JVM version and heap configuration | The runtime is recorded in [§2.2](#env-second); the heap settings of the measured run are owed |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

The measurement methods are the two the harness declares inside its
`ExecutionDependentGates` tier — one recording batch throughput and peak heap over the
boundary fixture, one recording query latency at the ninety-fifth percentile against a
real containerised database over a fixed sample count of 40. Both are annotated with a
fixed clock and pinned container images, which is what makes two measurements
comparable at all.

**Input.**

| Measurement | Input |
|---|---|
| Batch throughput | `app/data/ASCII/dailytran.txt` — 300 records of 350 bytes, 105,300 bytes |
| Endpoint latency | The 17 operations across 8 controllers enumerated in [api-contracts.md](api-contracts.md) |
| Peak heap | One full posting run over the same 300-record fixture |

**Expected assertions.** None are thresholds. Each item below is a **required
recording**, and it is incomplete unless it carries its conditions.

1. **Batch throughput** in records per second for the posting job over the 300-record
   fixture, recorded together with the JVM version, the machine class and the dataset
   size. A throughput figure without those three is not interpretable and does not
   discharge the obligation.
2. **Per-endpoint latency at the 95th percentile** across all 17 operations, recorded
   with the sample count and whether the samples were taken warm or cold.
3. **Peak heap** observed during a full batch run, recorded with the heap configuration
   in force.
4. Each figure is stamped with the **date** it was taken and the **commit** it was taken
   at, so a later measurement can be compared with it rather than merely replacing it.
5. The record states explicitly that **no objective exists** to compare the figures
   with, so a reader cannot mistake a baseline for a met target.

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| Recorded measurements | `target/gate-verification/gate-verification-summary.properties` |
| Harness evidence, including the runtime that produced the figures | `target/gate-verification/gate-verification-evidence.properties` |
| Tier report | `target/failsafe-reports/` |

**Severity on failure.** **Medium.** A missing measurement is a documentation gap, not a
correctness failure: no user-visible behaviour depends on it. It is not Low, because
without a baseline no later change can be shown to have made anything slower.

**Remediation.** Take the measurements and publish them with their conditions. If a
figure looks poor, that is a finding to record and investigate — **not** a reason to
withhold the number or to re-run until a better one appears. Selective reporting is the
one failure mode that would make this gate worse than having no gate.

**Execution date, tool versions, exit code.** **Not available** — no measurement run
has been performed and captured for this ledger.

**Result.** **Not available** — no baseline figures have been measured and published
here. **What is needed:** one execution of the harness tier above with the throughput,
95th-percentile latency and peak-heap figures written into this field, each carrying its
JVM version, machine class, dataset size, sample count and date. Separately, and
independently of any measurement: a stated objective from the business, if this gate is
ever to acquire a threshold. Until one exists, none may be written.

**Residual risks.** Connection-pool tuning is explicitly out of scope, so the figures
describe the shipped defaults rather than a tuned system. Table partitioning and read
replicas are deferred, so the figures describe a single unpartitioned instance. The
measurement is taken over a 300-record fixture and 40 latency samples on a single
machine — **it is a baseline, not a capacity plan**, and it must not be extrapolated to
production sizing.

---

<a id="gate-4"></a>

## 7. Gate 4 — Named fixture validation

**Objective.** Prove that **all nine ASCII fixtures** and **the ten inline user
records** load correctly through `V3__seed_data.sql`, with position-aware
zoned-decimal overpunch decoding and with every password stored only as a BCrypt hash.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | The three Flyway migrations, applied in order | Present on this branch |
| 2 | The nine fixtures at their actual names under `app/data/ASCII/` | Present, frozen, read in place or copied under the same names into `src/test/resources/` |
| 3 | A container runtime, for the Testcontainers PostgreSQL tier | Present |
| 4 | An empty target schema, or one at a known migration state | Provisioned per test class by the container |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

The two harness methods inside `ExecutionDependentGates` that discharge this gate are
the nine-fixture seeding check, which asserts position-aware overpunch decoding, and
the ten-user check, which asserts BCrypt cost-10 storage. The repository tier under
`src/test/java/com/cardemo/integration/repository/` covers the same tables row by row.

**Input.** Every figure below was measured on the files themselves.

| Fixture | Bytes | Rows | Record width |
|---|---:|---:|---:|
| `app/data/ASCII/acctdata.txt` | 15,050 | **50** | 300 |
| `app/data/ASCII/carddata.txt` | 7,550 | 50 | 150 |
| `app/data/ASCII/cardxref.txt` | 1,850 | 50 | 36 |
| `app/data/ASCII/custdata.txt` | 25,050 | 50 | 500 |
| `app/data/ASCII/dailytran.txt` | 105,300 | **300** | 350 |
| `app/data/ASCII/discgrp.txt` | 2,601 | 51 | 50 |
| `app/data/ASCII/tcatbal.txt` | 2,550 | 50 | 50 |
| `app/data/ASCII/trancatg.txt` | 1,098 | 18 | 60 |
| `app/data/ASCII/trantype.txt` | 427 | 7 | 60 |

Two rows correct figures that circulate elsewhere: **`acctdata.txt` has 50 rows**, not
9, and **`dailytran.txt` has 300**, not 20. Every row satisfies
`bytes = rows × (width + 1)`, the extra byte being the newline, which is the arithmetic
that makes the table self-checking — `2,601 = 51 × 51` for `discgrp.txt` and
`427 = 7 × 61` for `trantype.txt`.

**Expected assertions.**

1. Every fixture's **row count and record width** match the table above exactly.
2. **Overpunch decoding is position-aware, driven by the PIC clauses**, using this
   table:

   | Character | Sign and final digit | Character | Sign and final digit |
   |---|---|---|---|
   | `{` | +0 | `}` | −0 |
   | `A` … `I` | +1 … +9 | `J` … `R` | −1 … −9 |

3. Three corroborating decodes hold, each read from the file rather than from prose.
   The excerpts below are the **actual bytes**; the fixtures carry no separators
   between fields, so the field boundaries are given as positions.

   | Check | Verified excerpt | Field | Decodes to |
   |---|---|---|---|
   | `app/data/ASCII/acctdata.txt:L1` | `00000000001Y00000001940{00000020200{00000010200{` | three consecutive 12-character `S9(10)V99` money fields at positions 13-24, 25-36 and 37-48 | +194.00, +2020.00 and +1020.00 |
   | `app/data/ASCII/tcatbal.txt:L1` | ends the key-plus-balance run with `0000000000{` | one 11-character `S9(09)V99` field at positions 18-28, after the 17-byte composite key | +0.00 |
   | `app/data/ASCII/discgrp.txt:L18` | `DEFAULT   01000100150{` | one 6-character `S9(04)V99` rate field at positions 17-22 | +15.00 |

4. **Decoding must be position-aware, because the same letters occur legitimately
   inside text fields** such as merchant names. A naive character scan that treats every
   `A`-to-`R` as an overpunch corrupts data silently, and it corrupts it in the fields a
   reader is least likely to check.
5. **Negative values survive the load.** `dailytran.txt` carries `}` as well as `{` — 6
   rows and 25 rows respectively — and the sign census at the amount field, column 143,
   is 250 positive to 50 negative. A load that yields 300 non-negative amounts has
   normalised the data and has broken the cycle-debit path described in
   [Gate 1](#gate-1).
6. **`discgrp.txt` contains exactly 17 rows whose group identifier is the literal
   `DEFAULT`, out of 51 total**, and that set includes zero-rate combinations. This is
   precisely what makes the interest program's default-rate fallback succeed for those
   type-and-category pairs and abend for any other, so **the test must cover both
   outcomes**: the fallback that finds a default row, and the missing-default case that
   ends the job. A test that covers only the success path leaves the abend path
   unexercised, which is the path that fails in production.
7. **All ten user records load, and every password column holds a BCrypt hash at
   strength 10 — 60 characters, no plaintext anywhere.** The source records carry a
   single plaintext literal, at [`app/jcl/DUSRSECJ.jcl:L35-L44`]. **That value is not
   reproduced in this document and is not offered as a default, an example or a usable
   credential.** It is referred to by locator only, and the assertion is that it does
   **not** survive the load in that form.

**The ten inline users, and where they come from.**

There is **no `usrsec.txt` anywhere in this repository.** A case-insensitive search for
the name returns exactly two paths, neither of which is a text fixture:
`app/jcl/DUSRSECJ.jcl` and `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`. The ten records
exist only as inline `SYSUT1 DD *` data fed through IEBGENER, at
[`app/jcl/DUSRSECJ.jcl:L34-L45`] — the DD statement at `:L34`, the ten records at
`:L35-L44`, the delimiter at `:L45`. Their layout is the 80-byte `CSUSR01Y` record,
`ID X(8) + FNAME X(20) + LNAME X(20) + PWD X(8) + TYPE X(1) + FILLER`, corroborated by
the cluster definition `KEYS(8,0)` and `RECORDSIZE(80,80)` at
[`app/jcl/DUSRSECJ.jcl:L65-L66`].

| Identifier | Forename | Surname | Type |
|---|---|---|---|
| `ADMIN001` | MARGARET | GOLD | `A` |
| `ADMIN002` | RUSSELL | RUSSELL | `A` |
| `ADMIN003` | RAYMOND | WHITMORE | `A` |
| `ADMIN004` | EMMANUEL | CASGRAIN | `A` |
| `ADMIN005` | GRANVILLE | LACHAPELLE | `A` |
| `USER0001` | LAWRENCE | THOMAS | `U` |
| `USER0002` | AJITH | KUMAR | `U` |
| `USER0003` | LAURITZ | ALME | `U` |
| `USER0004` | AVERARDO | MAZZI | `U` |
| `USER0005` | LEE | TING | `U` |

Five administrators and five standard users: the split that makes the role assertions of
[Gate 5](#gate-5) provable against real seeded rows rather than against fabricated ones.

**The EBCDIC directory is reference material and is never parsed by the build.**
`app/data/EBCDIC/` holds **12 dataset files** — eleven with a `.PS` suffix and one,
`AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, with a `.PS.INIT` suffix — **plus a `.gitkeep`**,
which is 13 directory entries. It is retained as byte-level codepage reference only. No
transcoding is performed, no codepage conversion utility is built, and the ASCII
fixtures are the sole authoritative seed and test input.

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| Fixture and seeding assertions | `target/failsafe-reports/` |
| Recorded fixture censuses | `target/gate-verification/gate-verification-summary.properties` |
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` |
| Repository-tier report | `target/failsafe-reports/`, for the per-table row checks |

**Severity on failure.** **Blocker.** A fixture that loads with the wrong sign, the wrong
scale or the wrong row count poisons every downstream gate: the parity comparison, the
interest calculation and the reporting tiers all read these tables, and a wrong seed
produces plausible wrong answers rather than an error.

**Remediation.**

* **A row-count or width mismatch** means the reader's record geometry is wrong, or a
  fixture was edited. Fixtures under `app/` are frozen — restore the geometry in the
  reader, and never "fix" the fixture.
* **A sign error** means overpunch decoding is not position-aware, or an
  absolute-value normalisation was applied. Drive the decode from the PIC clause
  positions.
* **A plaintext password in any column** is a Blocker in [Gate 6](#gate-6) as well.
  Hash at BCrypt strength 10 in the migration itself, so no plaintext is ever written.
* **A missing default disclosure-group row** must surface as the job-ending path the
  source has, not as a silent skip.

**Execution date, tool versions, exit code.** **Not available** — no seeded-load run has
been executed and captured for this ledger.

**Result.** **Not available** — no fixture-load evidence is published or linked here.
**What is needed:** one run of the harness tier above against a containerised
PostgreSQL 16, with the resulting report preserved and referenced from this field, and
with the row counts, the three decode corroborations, the 250-to-50 sign census, the
17-of-51 `DEFAULT` census and the ten BCrypt-hashed users each recorded as measured
rather than expected.

**Residual risks.** This gate proves that the fixtures load faithfully. It does not
prove the fixtures are *representative*: they are 50-row samples for eight of the nine
tables, and they exercise one reject code out of five. It does not exercise the EBCDIC
datasets at all, by design. And it proves nothing about data volumes, since the largest
fixture is 300 rows.

---

<a id="gate-5"></a>

## 8. Gate 5 — API contract verification

**Objective.** Prove that **every one of the 17 sourced operations** is exercised by an
integration test against a **real Spring application context** — not a mock, not a
standalone controller slice — with role enforcement, statelessness and failure mapping
asserted rather than assumed.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | The 8 controllers and the service beans beneath them | Present on this branch |
| 2 | The published contract the assertions are read against | [api-contracts.md](api-contracts.md) is present |
| 3 | A container runtime, for the database and cloud-service dependencies the context needs | Present |
| 4 | Seeded reference data, so authorisation and paging are asserted against real rows | Supplied by [Gate 4](#gate-4)'s seeding path |
| 5 | A token signing key supplied to the test context from the environment, with no committed default | Satisfied by the test tier generating an ephemeral key per run |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

The mapping census lives in the harness method inside `ExecutionDependentGates` that
asserts 17 operations across 8 controllers in a real application context; the
behavioural exercise lives in
`src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java`, which drives sign-on
through transaction add across the whole surface.

**Input.** The endpoint set — seventeen operations, grouped by controller. Field-level
request and response detail is **not restated here**; it belongs to
[api-contracts.md](api-contracts.md).

| Controller | Operations | Paths |
|---|---:|---|
| `AuthController` | 1 | `POST /api/auth/signon` |
| `MenuController` | 2 | `GET /api/menu/main`, `GET /api/menu/admin` |
| `AccountController` | 2 | `GET /api/accounts/{accountId}`, `PUT /api/accounts` |
| `CardController` | 3 | `GET /api/cards`, `GET /api/cards/detail`, `PUT /api/cards` |
| `TransactionController` | 3 | `GET /api/transactions`, `GET /api/transactions/detail`, `POST /api/transactions` |
| `BillingController` | 1 | `POST /api/billing/payments` |
| `ReportController` | 1 | `POST /api/reports` |
| `AdminController` | 4 | `GET /api/admin/users`, `POST /api/admin/users`, `PUT /api/admin/users/{userId}`, `DELETE /api/admin/users/{userId}` |
| **Total** | **17** | across **8** controllers |

**Why the count is 17 and not 18.** `app/csd/CARDDEMO.CSD` defines 18 transactions and
18 programs but only 17 mapsets. The eighteenth pair is transaction `CDV1` naming
program `COCRDSEC`, and **`COCRDSEC` has no source file anywhere in this repository**:
its only two occurrences repository-wide are the two CSD definitions themselves, at
[`app/csd/CARDDEMO.CSD:L211`] and [`:L390`]. It is a dangling legacy definition with
nothing to translate. **No route, no endpoint and no placeholder is published for it,
and none may be requested of this gate.** So: 17 sourced screen programs plus one
orphan definition accounts for all 18 CSD entries.

**Expected assertions.**

1. **All 17 operations respond as documented** in [api-contracts.md](api-contracts.md),
   against a real application context with the database and emulator running.
2. **`/api/admin/*` returns 403 for a non-administrator principal.** Asserted with a
   token whose role claim derives from a seeded `U`-type user, not with a hand-built
   principal.
3. **No server-side session is created.** Asserted two ways, because either alone is
   weak: the session-creation policy is stateless, *and* no session cookie appears in
   any response. The COMMAREA has no server-side successor, and a session cookie would
   be exactly that successor arriving by accident.
4. **The fixed page sizes 7, 10 and 10 are enforced and are not client-overridable** —
   7 for the card list, 10 for the transaction list, 10 for the user list. A request
   asking for more returns the fixed size, not the requested one.
5. **The five distinguishable account-update outcomes each produce a distinct response**
   rather than being collapsed into a single conflict status: the account-lock failure,
   the customer-lock failure, the data-changed-before-update outcome, the
   locked-but-update-failed outcome, and the account-lock marker set separately from
   them. The source declares them separately [`app/cbl/COACTUPC.cbl:L517-L523`,
   `:L667`], and collapsing them discards information the legacy screen displayed.
6. **The as-displayed snapshot round-trips.** The snapshot is server-issued and sealed,
   travels on the update request as documented in
   [api-contracts.md](api-contracts.md), and holds the date of birth in **compact
   `YYYYMMDD`** form. A snapshot carrying a dash-separated date of birth is rejected as
   a conflict rather than silently accepted. This is not a stylistic choice: the source
   compares the live record's dash-separated date at offsets 1, 6 and 9 against a
   snapshot at offsets 1, 5 and 7 [`app/cbl/COACTUPC.cbl:L669-L756`], so a whole-string
   comparison of the two forms would report a change on **every** request and make the
   operation permanently unusable.
7. **The two-parser asymmetry holds.** Identifiers and card numbers parse strictly, as
   digits only; amounts parse currency-tolerantly. The source uses two different numeric
   intrinsics deliberately — the plain conversion for the account identifier and card
   number [`app/cbl/COTRN02C.cbl:L204`, `:L218`] and the currency-aware conversion for
   the amount [`:L383`, `:L456`] — so a single parser for both either accepts input the
   source rejects or rejects input it accepts.
8. **Passwords never appear in any response body**, on any operation, including the user
   list and the user-update response.
9. **The exact legacy message literals are returned**, byte for byte. These strings are
   part of the observable contract, not decoration, and a reworded message is a contract
   break.
10. **Failure mapping is complete and nothing is swallowed**: every typed exception in
    the hierarchy maps to a documented status and a stable error code, and an unpublished
    path is refused rather than answered.

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| Integration and end-to-end tier report | `target/failsafe-reports/` |
| Recorded operation and controller census | `target/gate-verification/gate-verification-summary.properties` |
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` |

**Severity on failure.** **Blocker.** The REST surface is the whole of the online
replacement. An operation that is absent, that leaks a session, that returns a password
or that collapses distinct outcomes is a contract break visible to every caller.

**Remediation.**

* **A missing operation** is authored, not documented away. The census is 17 and the
  contract names each one.
* **A 403 that should be a 200, or the reverse**, is a role-mapping defect: check that
  the user-type byte maps to the role claim the security configuration expects.
* **A session cookie in a response** means a component created an HTTP session. Find it
  and remove it; do not filter the cookie out downstream.
* **A client-overridable page size** is fixed at the service, not by validating the
  request parameter away, so the fixed size holds however the caller asks.
* **A collapsed account-update outcome** is separated back into its five distinct
  responses.
* **A rejected valid snapshot** almost always means the date of birth is being compared
  whole rather than by component, or is being carried dash-separated. Compare by
  component and carry it compact.

**Execution date, tool versions, exit code.** **Not available** — no contract
verification run has been executed and captured for this ledger.

**Result.** **Not available** — no captured integration-tier report is published or
linked here. **What is needed:** one run of the harness and end-to-end tiers above
against a real application context with containerised dependencies, with
`target/failsafe-reports/` preserved and referenced from this field, and with the
17-operation census, the 403 assertion, the statelessness assertion, the 7/10/10 page
sizes, the five distinct account-update outcomes and the snapshot round-trip each
recorded as measured.

**Residual risks.** This gate proves the contract holds for the requests the tests
make. It does not prove the absence of an unpublished route, only that unpublished paths
are refused. It exercises a single-caller sequence, so it says nothing about concurrent
callers — the deliberately racy identifier generation described in
[§12.4](#quirks) is outside what it
covers. And rate limiting, transport security and URI-based versioning are deferred, so
none of the three is asserted anywhere.

---

<a id="gate-6"></a>

## 9. Gate 6 — Security audit

**Objective.** Prove that the security invariants hold **across the whole tree** —
decimal-only money, hashed-only passwords, no committed secret, environment-indirected
signing key, masked log output, and no reachable path to a live cloud endpoint.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | The full source tree, all four configuration profiles and the DDL | Present on this branch |
| 2 | A vulnerability feed reachable by `dependency-check-maven`, for the scan clause | Environment-dependent; not asserted here |
| 3 | No container runtime | Not needed — every clause is a static scan or a migration-content check |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

Four harness methods carry this gate: the schema scan that rejects floating-point money
and asserts the three `NUMERIC` precisions; the seed scan that asserts ten users, BCrypt
cost 10 only and no plaintext; the configuration scan that asserts an
environment-indirected signing key with no default and no literal secret anywhere; and
the risky-pattern scan that asserts no `exec`, no script engine, no unsafe
deserialisation and no concatenated shell or SQL.

**Input.** `src/main/java/**`, `src/main/resources/**` including all four
`application*.yml` profiles and `logback-spring.xml`, the three migrations under
`src/main/resources/db/migration/`, `pom.xml`, `Dockerfile`, `docker-compose.yml`,
`.github/workflows/build.yml`, `localstack-init/init-aws.sh`, `.env.example`, and the
whole of `src/test/**` including test resources.

**Expected assertions.** Each is machine-checkable.

1. **Zero `float` and zero `double` in any financial field.** Asserted by static scan
   across entities, DTOs, service signatures and SQL column types. Every monetary or
   rate column is `NUMERIC(p,2)` at the precision its PIC clause dictates, including the
   three that differ:

   | Column class | Precision | Source PIC |
   |---|---|---|
   | Account money fields | `NUMERIC(12,2)` | `S9(10)V99` |
   | Transaction-category balance | `NUMERIC(11,2)` | `S9(09)V99` |
   | Disclosure-group interest rate | `NUMERIC(6,2)` | `S9(04)V99` |

   The second and third are the ones a uniform-precision assumption gets wrong, and
   getting them wrong changes rounding rather than raising an error.
2. **Every stored password is a BCrypt hash at strength 10**, 60 characters wide, and
   **no plaintext password column exists anywhere** in the schema or the seed data.
3. **No literal secret, key, token or credential appears in any file** — not in the four
   profiles, not in `docker-compose.yml`, not in the `Dockerfile`, not in
   `.github/workflows/build.yml`, not in tests and not in test resources.
4. **The JWT signing key is resolved from an environment variable in every one of the
   four profiles**, with **fail-fast on absence and no committed default**: an unset
   variable must abort startup rather than fall back to something that works.
5. **`logback-spring.xml` masks credentials, password hashes and social security
   numbers.** The customer layout carries a nine-digit government identifier and the
   user layout carries a password field, so an unmasked log is a disclosure rather than
   a theoretical risk.
6. **Zero live cloud credentials, and no code path that reaches a real cloud endpoint.**
   All object-store, queue and topic interaction targets the local emulator.
7. **Least privilege is documented** for tokens, credentials and configuration, and the
   production profile externalises every secret.
8. **The dependency-vulnerability scan reports no finding at CVSS 7 or above**, and
   **every dependency is pinned** to an exact version.
9. **No risky pattern is present**: no dynamic `exec` or script engine, no unsafe
   deserialisation, and no shell or SQL assembled by string concatenation.

**Two High-severity prior-run defects this gate closes.**

| Prior defect | Locator | Closed by |
|---|---|---|
| The JWT secret was hardcoded in configuration | [`docs/project-guide.md:L52`], [`:L215`] | Environment indirection with no committed default in all four profiles, plus `.env.example` as the documented variable list |
| No production profile existed | [`docs/project-guide.md:L51`] | `application-prod.yml`, with every secret externalised and no development convenience enabled |

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| Vulnerability scan report | `target/dependency-check/dependency-check-report.html`, with JSON and SARIF beside it |
| Static-invariant assertions | `target/failsafe-reports/` |
| Recorded security censuses | `target/gate-verification/gate-verification-summary.properties` |
| Suppression register, if any suppression is in force | `owasp-suppressions.xml` — each entry must carry its evidence and an expiry |

**Severity on failure.**

| Failure | Severity |
|---|---|
| A floating-point type in any financial field | **Blocker** |
| A plaintext password anywhere | **Blocker** |
| A committed secret, key, token or credential | **Blocker** |
| A missing log mask | **High** |
| An unpinned dependency | **High** |
| A signing key with a committed default | **Blocker** — it is a committed secret |

**Remediation.**

* **A floating-point financial field** is changed to `BigDecimal` with the scale the PIC
  clause dictates, and its column to `NUMERIC(p,2)`. Equality is compared with
  `compareTo`, never with `equals`, and rounding is half-even.
* **A plaintext password** is hashed in the migration that writes it, so no plaintext is
  ever persisted, not even transiently.
* **A committed secret** is removed, the variable is externalised, **and the exposed
  value is rotated.** Deleting the literal without rotating leaves the secret exposed in
  history.
* **A signing key with a default** has the default removed so that an unset variable
  aborts startup. A default that "works in development" is the exact shape of the prior
  run's High-severity defect.
* **A missing mask** is added to `logback-spring.xml` and verified by asserting the
  masked output, not by inspecting the configuration.
* **An unpinned dependency** is pinned to an exact version. Ranges are not permitted.

**Execution date, tool versions, exit code.** **Not available** — no security audit run
has been executed and captured for this ledger.

**Result.** **Not available** — no captured audit or scan artefact is published or
linked here. **What is needed:** one run of the harness above with
`target/failsafe-reports/` preserved, plus one `dependency-check-maven` execution
against a reachable feed — that is, a `verify` run **without**
`-Ddependency-check.skip=true` — with `target/dependency-check/dependency-check-report.html`
preserved and referenced from this field.

**Residual risks.** A static scan proves the absence of the patterns it looks for, not
the absence of every vulnerability. Encryption at rest for personally identifiable data
is deferred, so the nine-digit government identifier and the customer address are stored
unencrypted at the storage layer. Transport security terminates outside the
application, so nothing here asserts it. And a dependency scan is a point-in-time
reading of a moving feed: a clean scan today is not a clean scan next month, which is
why the continuous-integration harness re-runs it rather than trusting a stored verdict.

---

<a id="gate-7"></a>

## 10. Gate 7 — Scope coverage

**Objective.** Prove that **all 28 COBOL programs** are mapped at **paragraph level**
through a **machine-checkable** matrix, so that coverage is *computed* rather than
asserted.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | The repository-root traceability matrix the gate reads | **Owed at `../TRACEABILITY_MATRIX.md`** — it is not on this branch yet, and this gate cannot be computed without it |
| 2 | The frozen corpus, matched **case-insensitively** | Present; the case-insensitivity is itself a prerequisite, see below |
| 3 | The Java tree the matrix maps onto | Present — 132 production classes |
| 4 | No container runtime | Not needed; every clause is a census over files |

**Exact command or test.**

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

The corpus censuses, the label census, the forward-and-back citation check and the
special dispositions are asserted by the `GateVerificationTest` methods whose display
names begin *Gate 7*. Two of them are worth naming because they are what makes the
coverage claim machine-checkable in both directions: one asserts that all 28 programs
map **forward** to loadable Java types that cite them **back**, and one asserts that
**every `app/**` path cited from `src/main/java/**` resolves on disk**, so an invented
citation fails the gate instead of decorating it.

**Input.** The 28 programs and their verified line counts.

| Program | Lines | Program | Lines |
|---|---:|---|---:|
| `COACTUPC.cbl` | 4,236 | `CBTRN01C.cbl` | 491 |
| `COCRDUPC.cbl` | 1,560 | `COUSR02C.cbl` | 414 |
| `COCRDLIC.cbl` | 1,459 | `COUSR03C.cbl` | 359 |
| `COACTVWC.cbl` | 941 | `COTRN01C.cbl` | 330 |
| **`CBSTM03A.CBL`** | **924** | `COUSR01C.cbl` | 299 |
| `COCRDSLC.cbl` | 887 | `COMEN01C.cbl` | 282 |
| `COTRN02C.cbl` | 783 | `COADM01C.cbl` | 268 |
| `CBTRN02C.cbl` | 731 | **`COSGN00C.cbl`** | **260** |
| `COTRN00C.cbl` | 699 | **`CBSTM03B.CBL`** | **230** |
| `COUSR00C.cbl` | 695 | `CBACT01C.cbl` | 193 |
| `CBACT04C.cbl` | 652 | `CBCUS01C.cbl` | 178 |
| `CBTRN03C.cbl` | 649 | `CBACT03C.cbl` | 178 |
| `CORPT00C.cbl` | 649 | `CBACT02C.cbl` | 178 |
| `COBIL00C.cbl` | 572 | `CSUTLDTC.cbl` | 157 |
| | | **Total, 28 programs** | **19,254** |

**`CBSTM03A.CBL` and `CBSTM03B.CBL` carry an uppercase `.CBL` extension.** A glob of
`app/cbl/*.cbl` therefore matches only 26 members and totals **18,100** lines, silently
under-reporting coverage by **1,154** lines — the 924 and the 230 above. **Case-insensitive
matching is a prerequisite of this gate**, not a nicety: the shortfall produces no error,
just a smaller number that looks plausible. The same hazard applies to `app/jcl/`, where
`CREASTMT.JCL` is the single uppercase member of 29 and is the sole source for statement
generation.

`COSGN00C.cbl` is **260** lines. Any artefact recording it as 1,100 is wrong; the figure
above was counted on the file.

**Expected assertions.**

1. **All 28 programs appear** in the matrix, each with the line count above.
2. **Every paragraph or section has a named Java target method**, and the matrix is
   **machine-parseable**, so paragraph coverage can be computed. The derived census over
   the corpus is **528 procedure-division paragraphs and zero procedure-division
   sections**; a matrix that maps fewer is incomplete by exactly the difference.
3. **The corpus censuses hold**: 28 programs / 19,254 lines, 29 JCL members, 28
   copybooks / 2,614 lines, 17 mapsets / 4,472 lines and 17 symbolic maps / 5,632 lines.
4. **Every `app/**` path cited from the Java tree resolves.** An unresolvable citation
   fails the gate, which is what stops the matrix from being satisfiable by invention.
5. **The special dispositions are present and justified:**

   | Artefact | Disposition |
   |---|---|
   | `CBTRN01C` (491 lines) | **No distinct target job.** Folded into `DailyTransactionPostingJob` as a labelled **read-only pre-flight step**. Its verb inventory over code lines is `OPEN`, `READ`, `CLOSE` and `DISPLAY` only — no `WRITE`, `REWRITE` or `DELETE` anywhere — so a standalone job would be an invention |
   | `app/jcl/COMBTRAN.jcl` | **No COBOL program exists.** Its logic is entirely sort and load-utility control cards, so the JCL itself is the source of truth |
   | `app/cpy/UNUSED1Y.cpy` | **Zero `COPY` references repository-wide.** Dispositioned as documented dead, so the copybook census reaches 28 without pretending it is used |
   | `COCRDSEC` | **No source anywhere.** Documented as a dangling CSD definition [`app/csd/CARDDEMO.CSD:L211`, `:L390`] rather than given a matrix row |
   | `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` | Map to **read-only verification steps**; their verb inventory is `OPEN`, `READ` and `CLOSE` only |
   | `CSUTLDTC` | Maps to `DateValidationService`, subsuming the static call and both work-area copybooks |
   | `CBSTM03B` | Maps to `FileService`, which is where the file-and-operation matrix genuinely varies |

6. **The three intentionally retained no-ops are present and marked**, because Rule 1
   clause B forbids **untracked** dead code, and tracking is what satisfies it:

   | Retained artefact | Locator | Why it stays |
   |---|---|---|
   | The empty `1400-COMPUTE-FEES` paragraph | [`app/cbl/CBACT04C.cbl:L518-L520`], performed from [`:L216`] | It is **reachable**: the source calls it. Deleting the call site would break the paragraph map this gate computes, failing a stated acceptance criterion to satisfy a stylistic one |
   | Reject code 109 | [`app/cbl/CBTRN02C.cbl:L545-L560`] | Assigned on a reachable path but never consumed as a reject outcome, because the paragraph runs only on the already-validated path. The constant must exist because the assignment is real code — hence exactly five constants, not four |
   | The redundant index assignment in the statement program | [`app/cbl/CBSTM03A.CBL:L324`] | The mainline sets the outer index before a varying loop that re-initialises it. Preserved verbatim for control-flow fidelity |

   Each of the three is tracked here and is owed an entry in the planned
   `../DECISION_LOG.md`. **Exactly three sites hold that retained-for-parity status**; a
   preserved `CONTINUE`, a preserved asymmetry and a preserved absent guard are
   documented source behaviour at their own locators, not additional register entries.

**Evidence and report paths.**

| Artefact | Path |
|---|---|
| The matrix itself | `../TRACEABILITY_MATRIX.md` (repository-root artefact, outside the MkDocs `docs_dir`) |
| Census and citation assertions | `target/failsafe-reports/` |
| Recorded censuses, including the paragraph total | `target/gate-verification/gate-verification-summary.properties` |
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` |

**Severity on failure.** **High.** An unmapped program is undelivered scope that no other
gate detects: the build compiles, the tests pass, and a whole COBOL program has no
counterpart. It is High rather than Blocker because it does not break running
behaviour — it breaks the ability to *know* what was delivered.

**Remediation.**

* **A missing program row** is added to the matrix with its paragraph mapping. If the
  program genuinely has no target, it gets a **disposition** with evidence, as the seven
  above do — never silence.
* **A paragraph-count shortfall** means paragraphs were consolidated. The rule is
  one-to-one, so split them back apart; the Javadoc citation on each method is what makes
  the correspondence checkable.
* **An unresolvable `app/**` citation** is corrected against the file. A citation that
  cannot be resolved is worse than no citation, because it looks like evidence.
* **A count taken with a case-sensitive glob** is re-taken case-insensitively, and the
  1,154-line shortfall above is the diagnostic that says which mistake was made.

**Execution date, tool versions, exit code.** **Not available** — the gate has not been
computed, because the matrix it reads is owed.

**Result.** **Not available** — the machine-checkable matrix this gate consumes is owed
at `../TRACEABILITY_MATRIX.md`, which is a repository-root artefact scheduled after this
ledger. **What is needed:** that matrix, holding all 28 programs with the line counts
published above, a named Java target method for every paragraph, the seven dispositions
and the three retained no-ops — in a parseable form, so coverage is computed from it
rather than asserted about it. The corpus censuses, the citation-resolution check and the
disposition checks above are executable today and are independent of the matrix.

**Residual risks.** Paragraph-level mapping proves that every unit of source has a named
target. It does **not** prove the target is behaviourally equivalent — that is
[Gate 1](#gate-1)'s job, and Gate 1 is the gate without an oracle. Nor does it prove the
mapping is *well chosen*: a paragraph mapped to a method that does the wrong thing counts
as covered here.

---

<a id="gate-8"></a>

## 11. Gate 8 — Integration sign-off

**Objective.** Prove that the **full runtime topology stands up and is healthy**: every
service reaches a healthy state, the schema migrates cleanly, the object store and queue
are provisioned, and the observability stack is populated without manual configuration.

**Prerequisites.**

| # | Prerequisite | Standing |
|---|---|---|
| 1 | A container runtime with `compose` | **Present** — Docker Engine 29.7.0, `compose` v5.3.1, daemon reachable, verified [§2.2](#env-second) |
| 2 | The compose definition, the image, the emulator initialisation script and the observability provisioning files | Present on this branch |
| 3 | The environment variables the stack needs, supplied from a local, uncommitted file derived from `.env.example` | The template is present; the values are supplied per host and are never committed |
| 4 | The application image built from the `Dockerfile` | Built by the compose build or supplied to it |
| 5 | Ports free on the host, or the documented per-clone overrides applied | Host-dependent |

**Exact command or test.**

```bash
docker compose up -d --wait
```

`--wait` is what makes this a gate rather than a launch: it blocks until every service
with a health check reports healthy, and it exits non-zero if one does not. Follow it
with health, migration and object-store verification, then **bring the stack down and up
again** to prove the second assertion below. The equivalent assertions are also made
against ephemeral containers by the `GateVerificationTest` methods whose display names
begin *Gate 8*, which cover the three applied migrations, the 11 domain tables and the
live health registry.

**Input.** The six-service compose topology, the three migrations, and the emulator
initialisation script — all read from this branch, with no manual step between `up` and
verification.

**Expected assertions.**

1. **All six compose services reach a healthy state**: the application, PostgreSQL 16,
   the LocalStack emulator, Jaeger, Prometheus and Grafana.
2. **The composite health endpoint reports UP** for the database, the object store and
   the queue, with **liveness and readiness groups distinguishable** — a single
   aggregate that answers UP is not sufficient, because it cannot say what is ready.
3. **All three Flyway migrations apply cleanly and in order** —
   `V1__create_schema.sql`, then `V2__create_indexes.sql`, then `V3__seed_data.sql` —
   producing:

   | Object | Count |
   |---|---:|
   | Domain tables | **11** |
   | B-tree indexes for the three alternate indexes | **3** |
   | Foreign keys | **10** |
   | Check constraints | **5** |
   | `NOT NULL` | on **every** column |

   Framework-owned batch metadata tables are created by Spring Batch and are counted
   separately from the 11 domain tables, so a total that mixes the two is not a
   comparable figure.
4. **`localstack-init/init-aws.sh` provisions exactly**:

   | Resource | Count | Detail |
   |---|---:|---|
   | S3 buckets | **3** | input, output and statements — **versioning on the output bucket only** |
   | SQS FIFO queue | **1** | `carddemo-report-jobs.fifo`, with `FifoQueue=true` and `ContentBasedDeduplication=false` |
   | SNS topic | **1** | notifications. **Exactly one** — an earlier revision also provisioned an alerts topic; nothing declares it and nothing consumes it, and least privilege forbids provisioning a delivery surface with no consumer |
   | SNS subscriptions | **0** | deliberately none |
   | S3 lifecycle rules | **0** | deliberately none — generation-group retention is documented, not enforced; see [§12.3](#legacy-defects) |

5. **The initialisation is idempotent.** A second `docker compose up` **converges**
   rather than failing on already-existing resources, and this is asserted **by restarting
   the stack**, not by reading the script. Idempotence claimed from source is a claim;
   idempotence observed across a restart is evidence.
6. **The Prometheus scrape target is up**, and the **provisioned Grafana datasource and
   dashboard load with no manual configuration** — a dashboard a reviewer has to import
   by hand has not been provisioned.
7. **The topology is not privileged.** No Docker socket mount, no privileged container,
   no host networking, and **no `latest` image tag** — every image is pinned by tag and
   digest.

**Evidence and report paths.**

| Artefact | Path or command output to preserve |
|---|---|
| Service health | `docker compose ps` showing all six healthy, plus the composite health response body |
| Migration state | The Flyway schema-history rows, showing three successful migrations in order |
| Object-store and queue state | The emulator's bucket, queue and topic listings after the first `up` **and** after the restart |
| Ephemeral-container equivalent | `target/failsafe-reports/` and `target/gate-verification/gate-verification-summary.properties` |

**Severity on failure.** **High.** A topology that does not stand up blocks every
container-dependent gate and every manual review, but it does not by itself mean the
application computes anything incorrectly — which is what separates it from a Blocker.

**Remediation.**

* **A service that never becomes healthy** is diagnosed from its own logs first; a
  health check that is too aggressive is a configuration defect, not a reason to remove
  the check.
* **A migration that fails on a pre-existing schema** is the most common failure here —
  see [§13.2](#ts-migrations).
* **A non-idempotent initialisation** is fixed in the script so that a create becomes a
  create-if-absent. Deleting resources before each run is not idempotence; it is
  destruction with a convenient side effect.
* **An empty Grafana dashboard** usually means the datasource provisioning file did not
  load, not that no metrics exist. Check the datasource before the panels.
* **A `latest` tag, a socket mount, a privileged flag or host networking** is removed. A
  digest pin is what makes this gate's result mean the same thing tomorrow.

**Execution date, tool versions, exit code.** **Not available** — the stack has not been
brought up and verified for this ledger.

**Result.** **Not available** — no health, migration or provisioning evidence is
published or linked here. This gate is **pending execution, not container-blocked**: the
runtime it needs is present and reachable, as recorded in
[§2.2](#env-second). **What is needed:** one
`docker compose up -d --wait` cycle with the six-service health output, the composite
health response, the Flyway history and the emulator listings preserved and referenced
from this field, followed by a **second** `up` to demonstrate convergence.

**Residual risks.** A healthy stack proves the topology assembles and answers. It does
not prove the deployment is production-ready: container orchestration stops at compose,
so nothing here covers scheduling, rolling deployment or service discovery. Transport
security terminates outside this stack. The emulator is not the cloud, so a behaviour
that differs between the emulator and a real provider is invisible to this gate — which
is the accepted cost of the zero-live-credentials constraint, not an oversight.

---

<a id="register"></a>

## 12. Residual-risk register

Rule 1 clause B forbids deferred work without an owner or a tracking reference, and
clause F requires missing information to be stated rather than omitted. **This section
is that tracking reference.** Nothing below is dropped, softened or left implicit: every
entry carries a severity, a locator, the impact of leaving it unaddressed, the trigger
that would force it into scope, and its remediation.

<a id="register-how"></a>

### 12.1 How to read this register

Severities are the four Rule 1 clause F bands — **Blocker / High / Medium / Low** — plus
one explicit band the standard implies but does not name: **Not available**, for an item
that is neither passing nor failing because the information needed to judge it has not
been produced. Separating that band out is the point. An item folded into "Low" because
nobody measured it is a hidden gap; an item filed as **Not available** with a statement
of what is needed is a disclosed one.

The register is deliberately partitioned into six kinds of entry, because they call for
different actions:

| Section | Kind of entry | What a reader should do with it |
|---|---|---|
| [12.2](#findings) | Severity-classified findings | Act on the remediation |
| [12.3](#legacy-defects) | Legacy defects, reported and never repaired | **Do not fix.** Repairing them changes behaviour the parity comparison is measured against |
| [12.4](#quirks) | Preserved behavioural quirks | **Do not fix.** Recognise them as faithful reproduction, not as bugs introduced here |
| [12.5](#deviations) | Labelled deviations | Understand that behaviour differs from the source **on purpose**, and why |
| [12.6](#deferred) | Deferred hardening | Schedule it, or accept the stated risk knowingly |
| [12.7](#unavailable) | Evidence gaps | Produce the evidence named as needed |

<a id="findings"></a>

### 12.2 Severity-classified findings

**Blocker — 2.**

| # | Finding | Locator | Impact if unaddressed | Trigger | Remediation |
|---|---|---|---|---|---|
| B-1 | The container library's 2.x line renamed every module coordinate, so the bare 1.x identifiers do not resolve, and the framework parent already manages a 1.x version | `pom.xml` `<testcontainers.version>`; the `org.testcontainers` module coordinates | The build does not resolve, or resolves the wrong version silently | Already in scope; it is a precondition of every container-dependent gate | Apply **both** halves: override through the version property — never a second bill-of-materials import — **and** use only the four prefixed coordinates. Either half alone still fails. See [Gate 2](#gate-2) |
| B-2 | Relocating or renaming the gate harness removes it from both test plugins **with no error** | `src/test/java/com/cardemo/e2e/GateVerificationTest.java`; the `maven-failsafe-plugin` includes in `pom.xml` | Every gate silently stops being verified while the build still reports success | Any refactor that moves test packages | Keep the class at that exact path, in that package, with the `Test` suffix. Confirm a run from the written evidence artefact, **never** from a green build alone |

**High — 4.** All four are prior-run open defects that this work closes; each is listed
here so the closure is auditable rather than assumed.

| # | Finding | Locator | Impact if unaddressed | Trigger | Remediation |
|---|---|---|---|---|---|
| H-1 | The token signing key was hardcoded in a prior implementation | [`docs/project-guide.md:L52`], [`:L215`]; the signing-key property in `application.yml` | Anyone with repository access can mint valid tokens | Closed by this work | Resolve from the environment with **no** committed default, so an unset variable aborts startup |
| H-2 | No production profile existed in a prior implementation | [`docs/project-guide.md:L51`]; `application-prod.yml` | No least-privilege deployment configuration; development conveniences reach production | Closed by this work | Ship the profile with every secret externalised |
| H-3 | No continuous-integration workflow existed in a prior implementation | [`docs/project-guide.md:L49`]; `.github/workflows/build.yml` | The zero-warning build is unrepeatable, so [Gate 2](#gate-2) rests on one person's machine | Closed by this work | Pin the workflow to the enforced toolchain — JDK 25 and Maven 3.9.11 — and run the same `verify` the developer runs |
| H-4 | The vulnerability scan was never executed in a prior implementation | [`docs/project-guide.md:L50`]; `dependency-check-maven` in `pom.xml` | Dependency risk is unquantified while appearing to be covered | Closed by this work | Bind the scan to `verify` and **report the skip honestly** when it is skipped, rather than defaulting to skip |
| H-5 | A page omitted from the `mkdocs.yml` `nav` silently never publishes — and, as measured, **a strict build does not catch it either**, because the omission is reported at INFO level by default | `mkdocs.yml` `nav` and its absent `validation` block; [`catalog-info.yaml:L22`] | Documentation exists in the repository and is unreachable in the published site; **no error and no warning** signals it | Every new document, including this one | Add the `nav` entry in the same change, **and** set `validation.nav.omitted_files: warn` in `mkdocs.yml` so a strict build can fail on it. See [§2.4](#env-nav) and the measured run in [§2.5](#env-mkdocs) |

**Medium — 5.**

| # | Finding | Locator | Impact if unaddressed | Trigger | Remediation |
|---|---|---|---|---|---|
| M-1 | The report generation group carries two conflicting retention limits | [`app/jcl/DEFGDGB.jcl:L37-L38`] declares `LIMIT(5)`; [`app/jcl/REPTFILE.jcl:L26-L27`] declares `LIMIT(10)` for the same group | Two defensible retention values, and an object-lifecycle rule must pick one | Already resolved | **Resolved to 10**, the larger value, because a single lifecycle value must be chosen. This is the **only** legacy inconsistency this migration resolves; every other one is reported and preserved |
| M-2 | Migration filenames are aliased between prose and the authored files | `src/main/resources/db/migration/V1__create_schema.sql` and its two siblings | A reader looking for a short-form name finds nothing | Documentation review | Record the alias. **Ordering is unaffected**, because the migration tool keys on the `V1__` / `V2__` / `V3__` version prefix, not on the descriptive tail |
| M-3 | The coverage plugin is pinned below the version cited by the prior record | `pom.xml` `<jacoco-maven-plugin.version>` — `0.8.12` pinned, `0.8.14` cited elsewhere | Two artefacts name different governing versions | Any coverage-tooling change | **The pinned `0.8.12` governs.** Record the divergence rather than advancing the pin unilaterally |
| M-4 | The screen-field census circulating in prose is wrong and internally inconsistent | `app/cpy-bms/**`; `app/cpy-bms/COACTVW.CPY` | A DTO built to a wrong field budget under- or over-shoots the contract | Any DTO field review | Cite the **derived census of 441 input fields**, with the account-view map at **37** rather than 36 |
| M-5 | The procedural-label total circulating in prose matches neither defensible expansion total | `app/cbl/**`; `app/cpy/CSUTLDPY.cpy`; `app/cpy/CSSTRPFY.cpy` | A paragraph-coverage denominator that cannot be reproduced | [Gate 7](#gate-7) review | Cite the derived base of **614** and state the expansion convention beside it, rather than reconciling the figures by force |

**Low — 2.**

| # | Finding | Locator | Impact if unaddressed | Trigger | Remediation |
|---|---|---|---|---|---|
| L-1 | A batch job misspells its own job name | [`app/jcl/OPENFIL.jcl:L1`] reads `//OEPNFIL JOB` | Cosmetic; the member name and the job name disagree | None. The corpus is frozen | Report it. **Preserved, not corrected** |
| L-2 | The service-catalogue entry carries four inaccurate metadata declarations | `catalog-info.yaml` — see the breakdown below | A catalogue reader is told this is a website in a TypeScript system | Separate catalogue work | Correct the declarations in separate work. **Explicitly out of scope here**, because that file must remain unchanged by this migration |

L-2 in detail, each verified against the file:

| Declaration | Locator | Why it is inaccurate |
|---|---|---|
| `spec.type: website` | [`catalog-info.yaml:L35`] | The component is a Spring Boot modular-monolith service exposing a JSON API, not a website |
| `spec.system: blitzy-typescript` | [`catalog-info.yaml:L38`] | There is no TypeScript in this repository |
| `metadata.tags` include `python`, `typescript` and `web-app` | [`catalog-info.yaml:L7-L18`], specifically `:L14`, `:L17` and `:L18` | None of the three describes this component |
| A documentation link points at a path that is not in this repository | [`catalog-info.yaml:L31`] | The target directory is not present on this branch, so the link resolves to nothing |

**One further metadata fact, and it is the opposite of a defect.**
[`catalog-info.yaml:L22`] sets `backstage.io/techdocs-ref: dir:.`, which is correct and
is what makes [§2.4](#env-nav) load-bearing. It must not be "tidied".

<a id="legacy-defects"></a>

### 12.3 Legacy defects, reported and deliberately not repaired

Each defect below exists in the frozen corpus. **None is repaired**, because repairing
any of them changes behaviour the parity comparison is measured against, and because
`app/**` is read-only. They are recorded so that a reviewer meeting one recognises it as
a known legacy artefact rather than a migration error.

| # | Defect | Locator | Why it is not repaired |
|---|---|---|---|
| D-1 | A corrupted data-definition line carrying fragments of three different clauses | [`app/jcl/CREASTMT.JCL:L90`] — `SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS` | It is in the frozen corpus. The Java statement job reproduces the step's **effective** behaviour and reports the corruption rather than silently repairing it |
| D-2 | The markup output's record length disagrees between the pre-delete step and the execution step: 80 versus 100 | [`app/jcl/CREASTMT.JCL:L69`] declares `LRECL=80` in the pre-delete step; [`:L94`] declares `LRECL=100` in the execution step | The writer follows the **execution** step's value, because that is the length the program actually writes — and the 100-character emission field in [`app/cbl/CBSTM03A.CBL`] independently confirms it. The mismatch is logged, not reconciled |
| D-3 | A procedure whose internal name differs from the member name the calling statement resolves | [`app/proc/TRANREPT.prc:L1`] declares `//REPROC PROC`, while `EXEC PROC=TRANREPT` resolves the member `TRANREPT` | Renaming either half would edit the corpus. Reported as a naming hazard for anyone reading the job stream |
| D-4 | A sort field declared zoned decimal over a character field | [`app/proc/TRANREPT.prc:L39`] declares `TRAN-CARD-NUM,263,16,ZD` | The sort tolerates it and the resulting order is the source's order. The Java comparator reproduces that order rather than the "correct" one |
| D-5 | An orphan cluster definition that no program opens | [`app/jcl/DEFCUST.jcl:L35`] defines `AWS.CUSTDATA.CLUSTER` with `KEYS(10 0)` [`:L37`] and `RECORDSIZE(500 500)` [`:L38`] | Nothing reads or writes it, so it has no target. Recorded so the dataset census is complete rather than quietly shorter than the JCL implies |
| D-6 | A batch job misspells its own job name | [`app/jcl/OPENFIL.jcl:L1`] | Same finding as **L-1** above, listed here so the defect register is complete in one place |

<a id="quirks"></a>

### 12.4 Preserved behavioural quirks — faithful, not defective

These are reproductions of source behaviour that read like bugs. **They are the
behaviour.** Each is preserved deliberately, and each would require an explicit,
separately approved deviation to change.

| # | Quirk | Locator | Severity of the underlying behaviour | Why it is preserved |
|---|---|---|---|---|
| Q-1 | **Reject code 103 overwrites 102.** When a transaction is both over limit and past account expiry, one reject record is written, and it carries 103 | [`app/cbl/CBTRN02C.cbl:L393-L422`] — the two checks are sequential and unguarded, with no alternative branch between them | Medium | Guarding the second check, or emitting two records, changes which rejects a downstream reader sees. Asserted by [Gate 1](#gate-1) |
| Q-2 | **The transaction report breaks control on the card number while the label it emits reads "Account Total".** | The break is on the card number at [`app/cbl/CBTRN03C.cbl:L181`]; the label lives in the copybook, at [`app/cpy/CVTRA07Y.cpy:L58`] | Medium | Both halves are the behaviour. Note where the mismatch lives: the label is in the copybook, not in the program that emits it, so "fixing" the program would not even address it |
| Q-3 | **There is no self-delete guard on user deletion**: an administrator can delete their own account | `app/cbl/COUSR03C.cbl`. The proof is a census rather than an impression — the signed-on identifier appears **zero** times in all 359 lines, so no comparison exists anywhere | Medium | Parity is the contract, so no comparison against the token subject was added. Mitigate operationally: keep more than one administrator account, and apply the guard client-side if a caller needs one |
| Q-4 | **Identifier generation is deliberately racy.** A descending browse of the maximum key plus one, so concurrent adds can compute the same identifier | [`app/cbl/COTRN02C.cbl:L444-L451`] and, for bill payment, [`app/cbl/COBIL00C.cbl:L212-L219`]; the empty-file case yields a first identifier of 1 | Medium | Substituting a database sequence would change the generated values and break comparison against the baseline. The collision surfaces cleanly as a duplicate-record refusal rather than as corruption. Callers retry |
| Q-5 | **The account-update snapshot comparison is deliberately case-asymmetric.** The group identifier is compared lower-cased on both sides; the customer name, address, state, country and government identifier are compared upper-cased; the postal code, both telephone numbers, the government identifier's numeric form, the funds-account identifier, the primary-holder indicator and the credit score are compared with no case function at all | [`app/cbl/COACTUPC.cbl:L669-L756`] | Medium | Normalising in either direction changes **which updates are accepted**. The date-of-birth offset asymmetry in the same paragraph is the sharper case: comparing whole strings there would reject every request. Asserted by [Gate 5](#gate-5) |

<a id="deviations"></a>

### 12.5 Labelled deviations — improvements, not parity

Six deviations from source behaviour are labelled rather than absorbed. Two of them
change observable outcomes and are therefore stated here in full; all six are owed an
entry in the planned `../DECISION_LOG.md`, which is the artefact that carries them
together with their reasoning.

| # | Deviation | Locator | What changes | Why it is a deviation and not equivalence |
|---|---|---|---|---|
| V-1 | **One transaction boundary replaces three independent commits**, closing the orphan-write hazard behind reject code 109 | The three source writes are the category-balance upsert [`app/cbl/CBTRN02C.cbl:L467-L500`], the account update [`:L545-L560`] and the transaction insert [`:L424-L465`] | In the source, a failure on the account rewrite leaves an orphaned category-balance row **and** an orphaned transaction row, because the three writes commit independently. In Java they succeed or fail together | It is a **genuine behavioural improvement**, so it is labelled as a deviation rather than presented as equivalence. Calling it parity would be false |
| V-2 | **The hard 510-transaction ceiling is removed** by streaming | The statement program's table is `OCCURS 51 TIMES` [`app/cbl/CBSTM03A.CBL:L226`] each holding `OCCURS 10 TIMES` [`:L228`], with a parallel counter table at [`:L232`] — and **neither** index is bounds-checked anywhere | The source silently overruns its table beyond 510 transactions per run. Java uses unbounded collections, so the overrun cannot occur | Removing a silent-corruption hazard changes behaviour at scale. **510 is recorded as the historical capacity limit**; pretending the ceiling was preserved would be false, and pretending its removal is invisible would be worse |

The remaining four labelled deviations concern the elimination of self-modifying dispatch
by static flow analysis with observable order preserved, the placement of the
file-and-operation strategy map at the file-service layer where the variability actually
exists, the resolution of the retention conflict recorded as **M-1**, and the addition of
a user-type guard the source does not have — that last one being published in
[api-contracts.md](api-contracts.md) as well as labelled in the services that enforce it,
so a caller meets the reasoning and not just the refusal.

<a id="deferred"></a>

### 12.6 Deferred hardening

Eight items are deliberately out of scope. **None is dropped**; each is accepted
knowingly, with the trigger that would bring it into scope.

| # | Deferred item | Why deferred | Severity | Impact if left unaddressed | Trigger that forces it into scope |
|---|---|---|---|---|---|
| R-1 | **Table partitioning** | The transaction table's growth profile is unknown, and partitioning a table whose access pattern has not been measured optimises blind | Medium | Query and maintenance times degrade as the transaction table grows | A measured [Gate 3](#gate-3) baseline showing degradation, or a production row-count projection |
| R-2 | **Read replicas** | The read-to-write ratio is unmeasured, and the source system offers no comparable figure | Medium | Read load and write load contend on one instance | A measured read-heavy profile, or a stated availability requirement |
| R-3 | **Connection-pool (HikariCP) tuning** | Pool sizing is workload-specific; the shipped defaults are honest defaults rather than tuned values | Medium | Pool exhaustion under concurrency, or idle connections wasting database capacity | A measured concurrency profile from [Gate 3](#gate-3), or an observed pool-exhaustion incident |
| R-4 | **TLS termination** | Terminating transport security is a deployment concern, and container orchestration stops at compose | High | Credentials and tokens traverse the network unprotected outside a trusted boundary | Any deployment beyond a trusted local network |
| R-5 | **Request rate limiting** | No source construct corresponds to it, and a limit chosen without a traffic profile either throttles legitimate use or fails to protect | Medium | The API is exposed to resource exhaustion from a single caller | Public exposure of the API, or an observed abuse pattern |
| R-6 | **URI-based API versioning** | There is exactly one version of this contract, and versioning a single version adds a path segment and no information | Low | A future breaking change has no compatible migration path | The first breaking contract change |
| R-7 | **Generated OpenAPI documentation** | Generated specification tooling is out of scope, **which is precisely why [api-contracts.md](api-contracts.md) is a manually authored contract** | Medium | No machine-readable schema for client generation or contract testing; the hand-written contract can drift from the code | A consumer needing generated clients, or contract drift found in review |
| R-8 | **Encryption at rest for personally identifiable data** | The storage layer's encryption posture is a deployment decision | High | The nine-digit government identifier and customer addresses are stored unencrypted at the storage layer | Any deployment holding real customer data |
| R-9 | **The framework line's support horizon.** Spring Boot **3.5.11** is pinned by requirement and is **not** advanced unilaterally | Advancing a pinned version is a scope decision, not a maintenance chore, and the pin is honoured exactly as instructed | Medium | A framework line eventually stops receiving open-source patches, after which a dependency finding may have no fixed version to move to | A stated need to move lines, or a finding with no fix available on 3.5.x |

**On R-9, one thing is deliberately not said.** This ledger makes **no present-tense
claim about the support status of that line**, in either direction. No authoritative
current source was consulted while authoring this page, so asserting a status here — that
it is supported, or that it is not — would be exactly the kind of unevidenced statement
Rule 1 clause F forbids. What is recorded is the pin, the reason it stands, and the fact
that the horizon is finite. Anyone deciding on the strength of it should check the
project's own published support policy at the time of the decision.

<a id="unavailable"></a>

### 12.7 Evidence gaps, stated as unavailable

Three items are neither passing nor failing. The information needed to judge them has
not been produced, and each is disclosed with exactly what would close it.

| # | Gap | Locator | Severity | What is needed |
|---|---|---|---|---|
| N-1 | **The end-to-end boundary baseline is not part of this repository.** This is the highest-impact evidence gap in the ledger, because it is the one that stops parity — the acceptance contract — from being demonstrable | `app/jcl/DALYREJS.jcl`, `app/jcl/TRANREPT.jcl` and `app/proc/TRANREPT.prc` are dataset **definitions** only; `app/data/ASCII/**` holds input fixtures only | **Not available** — with a Blocker impact on [Gate 1](#gate-1) | A captured DALYREJS 430-byte reject dataset plus the resulting TRANSACT, ACCTDATA and TCATBALF images from a real POSTTRAN execution at a known input state. Failing that, a written decision adopting a named Java-produced file as a **golden regression file**, recorded as a regression net and never as a parity oracle |
| N-2 | **The program behind one CICS transaction has no source anywhere in the repository** | [`app/csd/CARDDEMO.CSD:L211`] and [`:L390`] are its only two occurrences | **Not available** — Low impact, because nothing depends on it | The missing program source. Until it exists, **no endpoint and no matrix row is invented for it**, and the operation count stays at 17 |
| N-3 | **No service-level objective exists for the performance gate** | `app/cbl/**` publishes no throughput, latency or response-time target anywhere | **Not available** — Medium impact on [Gate 3](#gate-3) | A stated objective from the business. Until one exists, the gate records a measured baseline and applies **no** threshold |

**Two further standing gaps concern this authoring host rather than the codebase**, and
both are recorded here so no reader mistakes a host condition for a code condition:

| # | Gap | Standing | Severity | What is needed |
|---|---|---|---|---|
| N-4 | Host JDK 25 and Maven 3.9.11 were both missing on the first reading, which blocked a host-native [Gate 2](#gate-2) run | **Superseded.** Both have since been provisioned — JDK 25.0.3 and Maven 3.9.11, [§2.2](#env-second). The gap is recorded because it recurs on any unprovisioned machine | Medium | Nothing on this host. Elsewhere: provision JDK 25 and Maven 3.9.11, **or** use the pinned-container execution path — a container image pinned to JDK 25 carrying Maven 3.9.11, with the repository and the Maven cache mounted |
| N-5 | `mkdocs` was missing at the second reading, leaving TechDocs rendering unverified | **Closed: the tooling has since been provisioned and the build was run.** MkDocs 1.6.1 with both declared plugins, `mkdocs build --strict`, exit status 1 — see [§2.5](#env-mkdocs) for the full outcome and the reason | **High** for the underlying `nav` risk, which the measured run showed a strict build does **not** detect | Nothing further on this host. Elsewhere: the same tooling and one run. Separately, `validation.nav.omitted_files: warn` in `mkdocs.yml`, without which the `nav` omission stays undetectable by any build |

---

<a id="troubleshooting"></a>

## 13. Common failure modes and troubleshooting

Five failures account for most of the time lost running these gates. Each entry gives the
symptom as it actually appears, the cause, and the fix.

<a id="ts-testcontainers"></a>

### 13.1 The build fails on unresolvable container-library coordinates

**Symptom.** Dependency resolution fails naming an artefact under the
`org.testcontainers` group; or the build resolves, and then the container tiers fail at
runtime with a class or package that cannot be found.

**Cause.** Two distinct causes producing similar-looking failures. The 2.x line renamed
every module artefact, so the bare `localstack`, `postgresql` and `junit-jupiter`
identifiers **do not exist** at 2.0.3. Separately, the framework parent already manages a
1.x version and imports the library's bill of materials itself, so adding a competing
import resolves in an ordering-dependent way and can silently select 1.x.

**Fix.** Both halves, always:

```xml
<properties>
  <testcontainers.version>2.0.3</testcontainers.version>
</properties>
```

…and, in the test scope, **only** the prefixed coordinates
`org.testcontainers:testcontainers`, `:testcontainers-localstack`,
`:testcontainers-postgresql` and `:testcontainers-junit-jupiter`. **Do not import a second
bill of materials.** Diagnostic: if resolution fails, the rename half is missing; if
resolution succeeds and the runtime fails, the override half is missing.

<a id="ts-migrations"></a>

### 13.2 A migration fails because a schema already exists

**Symptom.** The migration tool reports a checksum mismatch, or a `CREATE TABLE` fails
because the relation is already there, or the schema-history table records a state the
tree does not match.

**Cause.** The target database is not empty. Typically a container volume survived a
previous run, or the local stack was pointed at a database that a different branch had
already migrated.

**Fix.** Recreate the database rather than editing a migration. Applied migrations are
immutable by design: editing one to match an existing schema breaks every other
environment. For the compose stack, take it down **with its volumes** and bring it back
up; the integration tiers avoid the problem entirely by using a fresh container per class.

**What not to do.** Do not add a repair step to make the mismatch go away, and do not
renumber a migration. Both convert a loud, local failure into a quiet, portable one.

<a id="ts-localstack"></a>

### 13.3 A test fails because emulator initialisation had not finished

**Symptom.** A bucket, queue or topic is reported as missing on the first run after the
stack starts, and the same test passes when re-run immediately afterwards.

**Cause.** A race. The emulator's readiness and the completion of
`localstack-init/init-aws.sh` are different events, and a test that starts on the first
can observe a resource the second has not created yet.

**Fix.** Wait for the resource, not for the container: bring the stack up with
`docker compose up -d --wait` so health checks gate readiness, and have the test assert
the resource's presence before using it. The initialisation script is idempotent, so
re-running it is safe and is the quickest way to confirm the diagnosis — if the resource
appears after a re-run, the race was the cause.

**Related.** An "intermittent" failure of this shape is almost never intermittent. It is
deterministic on a cold stack and invisible on a warm one, which is exactly why it
survives a re-run and reappears in continuous integration.

<a id="ts-jwt"></a>

### 13.4 The application refuses to start because the signing-key variable is unset

**Symptom.** Startup aborts while resolving configuration, naming the signing-key
environment variable. Or, at run time, token creation fails complaining the key is too
short.

**Cause.** **This is the intended behaviour, not a defect.** The signing key is resolved
from the environment with **no committed default**, precisely so that an unset variable
cannot be papered over by a value that happens to work. It is the closure of prior-run
finding **H-1**.

**Fix.** Supply the variable from the local environment — the required variable names are
listed in `.env.example`, which carries **names only and no values**. Two rules apply and
neither is negotiable: the key must be long enough for the signing algorithm, and **the
local file holding the real values is never committed**.

**What not to do.** Do not add a default to any profile "for development". That default
is a committed secret, and it is a Blocker under [Gate 6](#gate-6).

<a id="ts-mkdocs"></a>

### 13.5 `mkdocs build --strict` fails on a nav omission or a broken link

**Symptom.** With `--strict`, a warning becomes an error. Two shapes recur: *not included
in the "nav" configuration*, and a link whose target cannot be found.

**Cause.** The `nav` block in `mkdocs.yml` is the published site's table of contents, and
`catalog-info.yaml` renders TechDocs straight from it [`catalog-info.yaml:L22`]. A page
outside the `nav` is invisible in the site. A link to a document that has not landed yet
resolves to nothing.

**The two are reported at different levels, and that is the practical trap.** As measured
in [§2.5](#env-mkdocs): a **broken link** is a WARNING, so `--strict` fails on it; an
**omitted page** is only INFO, so `--strict` passes over it. Reading a green strict build
as proof that every page is in the `nav` is therefore wrong.

**Fix.**

* **For a nav omission**, add the entry — for this page, `Validation Gates:
  validation-gates.md`. Do **not** rename the page to match an existing entry. To make a
  build catch the next one, add to `mkdocs.yml`:

  ```yaml
  validation:
    nav:
      omitted_files: warn
  ```

* **For a broken link to a document created in the same batch as this one**, the link
  target is already the final filename the `nav` will carry. It resolves when that
  document lands. **Do not rewrite the link to silence the warning**, because the rewrite
  outlives the warning and then points somewhere wrong.

**Reading the output.** Attribute each warning to the page named in it. A run whose only
warnings name documents from the same batch is the expected interim state; a run whose
warnings name a page's own tables, anchors or code blocks is a defect in that page.

---

<a id="rule-1"></a>

## 14. Rule 1: Build Verify — clause-by-clause compliance

Exactly **one** user-specified rule governs this project: **Rule 1: Build Verify**,
headed *GLOBAL CODING & DESIGN STANDARDS (Apply to all projects)*, organised into six
lettered clauses. Its full text lives in the project's rules document and is
deliberately not transcribed here. Each clause is summarised in this ledger's own words,
with how this document honours it.

| Clause | What it requires | How this ledger honours it |
|---|---|---|
| **A — Engineering principles** | Correctness, determinism and explicit behaviour ahead of cleverness; security-conscious defaults; maintainability; observability through measurable behaviour; no obvious inefficiency, with tradeoffs justified | Every gate states an unambiguous pass condition, and where a condition cannot exist it says so instead of inventing one — [Gate 3](#gate-3) is a **measurement obligation** with no fabricated threshold, and [Gate 8](#gate-8) asserts the observability stack rather than describing it. The one efficiency tradeoff that changes behaviour, the removal of the 510-row ceiling, is justified in writing at **V-2** rather than taken silently |
| **B — Code quality** | No dead code and no deferred work without an owner or a tracking reference; inputs and boundary conditions validated, including null and empty cases; no global mutable state; no swallowed exceptions; tests for core logic; a documented public surface | **This document is that tracking reference.** The three retained no-ops are enumerated with locators in [Gate 7](#gate-7), and every deferred item is registered in [§12.6](#deferred) with a trigger. Boundary conditions are asserted where the source has them: negative amounts and the sign census in Gates [1](#gate-1) and [4](#gate-4), the empty-file first-identifier-of-1 path at **Q-4**, the accepted not-found control paths, and the end-of-data final flush that would otherwise lose the last account's interest. **Global mutable state** is asserted absent by [Gate 5](#gate-5), which requires that no server-side session is created — the COMMAREA has no successor, and a session cookie would be exactly that successor arriving by accident. Failure mapping — nothing swallowed — is asserted by the same gate; test coverage of core logic by Gates [2](#gate-2), [5](#gate-5) and [7](#gate-7); and the **documented public surface**, with its purpose, inputs, outputs, side effects and error modes per operation, is owned by [api-contracts.md](api-contracts.md), which this page links rather than restates |
| **C — Repository hygiene** | Follow existing conventions where present, never fight existing style; deterministic builds with no environment-specific assumptions; consistent structure, avoid duplication | [Gate 2](#gate-2) asserts determinism through the pinned wrapper, the enforcer floor and total version pinning — which is why every command in this ledger is `./mvnw` and never a bare `mvn`. Duplication is avoided by **linking**: the API field tables stay in [api-contracts.md](api-contracts.md), the diagrams in [architecture-before-after.md](architecture-before-after.md), the setup procedure in [onboarding-guide.md](onboarding-guide.md) and the transformation plan in [technical-specifications.md](technical-specifications.md). The two repository-root evidence artefacts stay at the repository root and are **not** copied into `docs/`. This page adopts the existing documentation banner and section conventions rather than introducing new ones |
| **D — Security standards** | No secrets in code, logs, tests or configuration; dependencies pinned, risky patterns flagged; least privilege for tokens, credentials and configuration | [Gate 6](#gate-6) is the assertion, and **this document itself carries no secret, no key, no token, no password value and no live endpoint.** The legacy plaintext password is referred to **only by locator** at [`app/jcl/DUSRSECJ.jcl:L35-L44`] and is never reproduced, never presented as a default and never offered as an example credential. Pinning is asserted by Gate 2, and the risky-pattern scan by Gate 6. Least privilege appears three times over: the production profile, the single provisioned notification topic, and the removal of an unconsumed topic recorded in [Gate 8](#gate-8) |
| **E — Documentation standards** | Every component carries documentation covering what it does, how to run, build and test it, its key configuration and defaults, and its common failure modes with troubleshooting | [§1](#about) states what this document is; [§1.3](#how-executed) gives the four commands that run every gate; field 3 of each gate gives its literal command and field 2 its configuration and defaults, including the coverage floor of 0.80, the CVSS threshold of 7 and the fixed page sizes; and [§13](#troubleshooting) is the required troubleshooting section, covering five real failure modes with symptom, cause and fix |
| **F — Output requirements** | Be evidence-based, citing file paths, symbols and examples; classify findings by severity as Blocker / High / Medium / Low; provide clear remediation; **where information is missing, state "Not available" and list what is needed** | Clause F is **dominant for this file.** Every figure carries an inline `[<path>:<locator>]` citation and every one was measured on the file rather than inherited from prose — which is how three locators cited elsewhere were corrected here. Every gate carries a severity-on-failure and every register entry carries a severity. Every gate carries remediation. And **every one of the eight Result fields begins with "Not available" and lists what is needed**, which is the literal disclosure the clause requires |

### 14.1 The one conflict, and its resolution

**The conflict.** Clause B forbids dead code. The migration mandate requires control flow
to be preserved one-to-one so that paragraph-level traceability is provable. They collide
at one identifiable site: the fee-computation paragraph is empty apart from a comment
saying it was never implemented, yet it is genuinely reachable and genuinely performed
[`app/cbl/CBACT04C.cbl:L518-L520`], called from [`:L216`].

**The resolution: the parity mandate governs, and clause B is satisfied by a different
mechanism.** The empty method is retained, documented with its source lines, and marked
as an intentional no-op preserved for control-flow parity. That satisfies the clause's
actual target, which is **untracked** dead code and deferred work with no owner — this
code is tracked, cited and justified, here and in the planned `../DECISION_LOG.md`.

**Why that direction.** Behavioural parity is the contract of the engagement, and
[Gate 7](#gate-7) makes paragraph coverage a pass-or-fail condition. Deleting the
paragraph would produce a tree that is marginally cleaner and demonstrably less
traceable — failing a stated acceptance criterion to satisfy a stylistic one. The same
reasoning applies, for the same reason, to reject code 109 and to the redundant index
assignment in the statement program. All three are preserved, marked and registered, and
**exactly three sites hold that status.**

**No other clause of Rule 1 conflicts with the migration requirements.** Each is either
directly satisfied by this ledger or satisfied by a named artefact it cites.

---

<a id="change-control"></a>

## 15. Change control and cross-links

### 15.1 What may change on this page, and what may not

| Change | Permitted |
|---|---|
| Filling a **Result** field from a real, captured, linked run | **Required**, and it is the whole purpose of the page |
| Adding a gate, or adding a register entry | Yes, keeping the eleven-field structure and the severity bands |
| Renaming a gate heading | Yes — the stable `#gate-N` anchors survive it |
| Removing a `#gate-N` anchor | **No.** It silently breaks every inbound citation |
| Renaming this file | **No.** The `mkdocs.yml` `nav` entry names it, and a mismatch means the page never publishes |
| Recording a pass without a linked artefact | **No.** See [§1.5](#never-a-pass) |
| Reproducing the legacy plaintext password, or any secret, key or token | **No**, under Rule 1 clause D and [Gate 6](#gate-6) |
| Editing anything under `app/**` to make a gate pass | **No.** See [§1.7](#frozen-app) |

### 15.2 Cross-links

| Document | Path | What it owns |
|---|---|---|
| API contracts | [api-contracts.md](api-contracts.md) | The 17 operations, their fields, statuses and error bodies |
| Architecture, before and after | [architecture-before-after.md](architecture-before-after.md) | Legacy and target topology, and the diagrams |
| Onboarding guide | [onboarding-guide.md](onboarding-guide.md) | Toolchain provisioning, build and run |
| Technical specifications | [technical-specifications.md](technical-specifications.md) | The transformation plan, scope boundaries and mapping tables |
| Executive presentation | [executive-presentation.html](executive-presentation.html) | The stakeholder summary |
| Documentation home | [index.md](index.md) | The entry point |
| Decision log | `../DECISION_LOG.md` | Every mechanism substitution and every preserved quirk (repository-root artefact, outside the MkDocs `docs_dir`) |
| Traceability matrix | `../TRACEABILITY_MATRIX.md` | Paragraph-level mapping for all 28 programs (repository-root artefact, outside the MkDocs `docs_dir`) |

The last two are cited with a `../` prefix because paths on this page are relative to
`docs/`: a leading `../` means the repository root, and an unprefixed name means a
sibling inside `docs/`. Both are scheduled after this ledger and declare it as an input,
which is why the eight `#gate-N` anchors are a published contract rather than an
implementation detail.

### 15.3 Provenance

Every figure, locator, literal and census on this page was measured directly against the
frozen corpus under `app/` at anchor commit
`7756d895ffeb65f7ea72aaa609e356d9899afcec` (short `7756d89`), and against the build,
configuration and infrastructure files on this branch. Where a figure disagrees with
narrative prose elsewhere in the repository, **the source governs**, and the correction is
recorded rather than propagated — three JCL locators cited elsewhere were corrected on
that basis while this page was authored, and the corrected values are the ones published
above.
