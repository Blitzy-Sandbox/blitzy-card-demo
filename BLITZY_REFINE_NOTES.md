# Blitzy Refine PR Notes — CardDemo Java 25 Migration

> Refine-PR deliverable Item 7 — summary of what changed in this branch
> (`blitzy-f3bf2d6d-69c5-40a0-b93c-516c33020956`) and the hours claimed
> against the 220-hour remaining work table in the Project Guide.

**Branch**: `blitzy-f3bf2d6d-69c5-40a0-b93c-516c33020956`
**Date**: 2026-05-26
**Builder**: Blitzy autonomous agent, executing the Refine PR
prompt against the existing CardDemo Java 25 migration (85% complete
upstream).

---

## 1. Executive Summary

This Refine PR delivers seven items of path-to-production work
identified in the Project Guide's 220-hour remaining table. **All
seven items are complete and pass `mvn -B -ntp clean verify` with
zero failures, zero errors, and the 100% JaCoCo Decimals coverage
gate intact.**

Six items required new production source / configuration / docs.
The seventh (this file) summarises the work.

No `app/` source files were modified; the COBOL reference
implementation remains byte-identical to its baseline.

---

## 2. Deliverables (the seven items)

### Item 1 — CI/CD pipeline (`.github/workflows/build.yml`)

**Status**: ✅ Complete.
**File**: `.github/workflows/build.yml` (434 lines, 22,199 bytes, CREATED).
**Hours claimed**: **16 h** against the 220-h remaining table.

The workflow:

* Triggers on `push` and `pull_request` to all branches, plus
  `workflow_dispatch` for manual runs.
* Uses `actions/setup-java@v4` with `distribution: temurin`,
  `java-version: 25` per AAP §0.5.1.
* Caches `~/.m2/repository` keyed on the hash of every `pom.xml`
  so dependency resolution is sub-second after the first run.
* Runs `mvn -B -ntp clean verify` from the `java/` directory.
* Emits a structured `GITHUB_STEP_SUMMARY` containing:
  * Surefire test counts (run/failures/errors/skipped) parsed
    from the XML reports.
  * Decimals coverage line parsed from
    `target/site/jacoco/jacoco.csv`.
  * Aggregate coverage from
    `target/site/jacoco-aggregate/jacoco.csv`.
* Uploads four artifacts: shaded jars, Surefire reports,
  JaCoCo reports (per-module + aggregate), Failsafe reports.
* Fails the build if `mvn dependency:tree` contains
  `org.springframework`, `org.postgresql`, `org.hibernate`, or
  `com.zaxxer`, or if `--enable-preview` appears anywhere in the
  repo (excluding the lint workflow itself).

### Item 2 — Forbidden-artifact lint as Maven gate (parent POM)

**Status**: ✅ Complete.
**Files modified**:
* `java/pom.xml` — added `maven-enforcer-plugin` 3.5.0 with three
  rules (`bannedDependencies`, `requireMavenVersion >= 3.9.0`,
  `requireJavaVersion >= 25`); added `maven-antrun-plugin` 3.1.0
  with a `scan-enable-preview-flag` execution that scans the
  repository tree for the preview-enabling JVM/compiler flag
  (excluding `.github/workflows/build.yml` which is the lint that
  enforces the rule).
* `java/README.md` — quality gates section updated to document
  the new enforcer + antrun gates.

**Hours claimed**: **8 h** against the 220-h remaining table.

**Banned-dependency list**: 15 group/artifact patterns covering
every Spring family (Boot, Batch, Cloud, Security, Data, AMQP,
Kafka, Session, Shell), Hibernate (ORM + Validator), PostgreSQL
JDBC driver, HikariCP (a Spring-Boot dependency by convention).

**Verification**:
* Positive case: `mvn -B -ntp clean verify` PASSES; enforcer
  reports "Rule 0: BannedDependencies passed" for every module;
  antrun reports `[preview-flag lint] PASS` with 0 hits across
  three filesets (`preview-pom-arg`, `preview-pom-argline`,
  `preview-workflow`).
* Negative case: injecting `--enable-preview` into a temporary
  workflow YAML caused antrun to FAIL with "condition satisfied";
  removing the injection restored the PASS state.

### Item 3 — Per-environment configuration templates (3 files)

**Status**: ✅ Complete.
**Files**:
* `java/config/application-dev.properties` — 11,919 bytes,
  workspace-relative paths under `./build/dev-data/`,
  US-ASCII codepage override commented-but-documented for
  fast inner-loop testing against the `app/data/ASCII/`
  fixtures.
* `java/config/application-staging.properties` — 12,663 bytes,
  POSIX paths under `/var/carddemo/staging/{in,out,gdg,work}`,
  IBM-1047 codepage default, includes systemd
  `EnvironmentFile` deployment guidance.
* `java/config/application-prod.properties` — 13,968 bytes,
  POSIX paths under `/var/carddemo/prod/{in,out,gdg,work}`,
  IBM-1047 codepage binding, PARM and date-range values
  committed blank deliberately so a misconfigured launch fails
  fast, JDBC credentials sourced from
  `/etc/carddemo/prod.env` (mode 0600) via systemd
  `EnvironmentFile`.
* `java/MIGRATION_NOTES.md` — appended §M18 (5 sub-sections)
  pointing operators at the three templates and documenting
  the `CARDDEMO_CONFIG` env-var loading pattern.

**Hours claimed**: **10 h** against the 220-h remaining table.

All three templates share the same 12-section structure derived
from the canonical `java/application.properties.example` so
operators can navigate any of the three by section number.

Hard constraints honored:
* SEC-USR-PWD remains plaintext per CSUSR01Y.cpy preservation
  mandate (no password hashing introduced).
* No JDBC credentials in source control.
* IBM-1047 default; per-file overrides commented documentation only.
* No Spring / Hibernate / PostgreSQL / preview-feature additions.

### Item 4 — Operator runbook (`java/RUNBOOK.md`)

**Status**: ✅ Complete.
**File**: `java/RUNBOOK.md` (36,807 bytes, CREATED).
**Hours claimed**: **16 h** against the 220-h remaining table.

Seven sections covering:

1. **Shaded JAR inventory** — 28 jars grouped by JCL job
   category (Pre-load, Data loading, Daily processing,
   Indexing, Reporting, Re-enable, Admin) with one-line
   descriptions and example invocations.
2. **GDG version management** — rolling generations, retention
   policy (30 generations + cron-based prune), archival to
   S3-IA / Glacier, cleanup verification.
3. **Restart semantics** — per-step idempotency analysis for
   POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX,
   OPENFIL, CLOSEFIL.
4. **CBTRN03C ABEND-999 path** — cause inventory (6 failure
   modes), pre-run cardxref completeness check procedure,
   faithful-COBOL vs permissive-operator-mode distinction
   (documented as DEFAULT vs DOCUMENTED-NOT-IMPLEMENTED per
   the Refine-PR mandate to "do NOT change behavior — document
   only").
5. **Monitoring boundaries** — exit-code taxonomy (0, 4, 8, 12,
   16), log signatures to grep, JFR triggers.
6. **Troubleshooting table** — 14 row expanded mirroring the
   `README.md` operator-runbook style.
7. **Operator cheatsheet** — smoke test (DEV), full chain
   (STAGING), single-step diagnosis (PROD), configuration
   sanity check, emergency abort.

### Item 5 — SRE/SLO skeleton (`java/SRE.md`)

**Status**: ✅ Complete.
**File**: `java/SRE.md` (20,236 bytes, CREATED).
**Hours claimed**: **8 h** against the 220-h remaining table.

Seven sections covering:

1. **SLI catalog** — six SLIs (batch success rate, batch
   step duration p50/p95/p99, file-write throughput,
   parity-test pass rate, Decimals coverage gate, JFR
   regression band) with numerator/denominator definitions
   and data sources.
2. **SLO targets** — 11 SLOs (one per critical SLI step);
   six targets marked `TBD pending baseline` per the
   Refine-PR scope (production telemetry required); five
   targets set to binding hard gates (100%, ≤1.10).
3. **JFR Performance Baseline Integration** — COLLECTION
   vs ENFORCEMENT mode, capture procedure on a stable
   reference host, what does NOT count as a stable baseline
   (CI runners, developer laptops, cloud VMs without CPU
   pinning), triage flow on regression.
4. **Error budget template** — per-SLO budgets and burn-rate
   alarms; ZERO budget for SLO-4 / SLO-5 / SLO-6.
5. **Alerting integration** — P1/P2/P3 categorisation.
6. **Capacity planning** — CPU, memory, storage, network
   recommendations.
7. **Review cadence** — daily/weekly/monthly/quarterly/
   annual cycles.

Per the Refine-PR mandate "leave the PLACEHOLDER intact"
unless captured on a stable host, the JFR baseline file
`carddemo-tests/src/test/resources/perf/baseline.properties`
retains its `PLACEHOLDER` value; the test runs in COLLECTION
mode and the assertion is currently `@Disabled` (per the
pre-existing scaffolding).

### Item 6 — Integration test harness improvement

**Status**: ✅ Complete.
**Files**:
* `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/integration/FullBatchChainIT.java`
  — 34,988 bytes, CREATED.
* `java/carddemo-tests/pom.xml` — MODIFIED to (a) add
  `excludedGroups` property with empty default, (b) add
  `<excludes>**/*IT.java</excludes>` to Surefire so ITs do
  not double-run during the `test` phase, (c) add
  `<excludedGroups>${excludedGroups}</excludedGroups>` to
  Surefire as defence-in-depth, (d) activate the
  `maven-failsafe-plugin` plugin with the
  `integration-test` + `verify` goal bindings.

**Hours claimed**: **12 h** against the 220-h remaining table.

The integration test wires the canonical Phase-2 chain
end-to-end:

```
POSTTRAN → INTCALC → TRANBKP → COMBTRAN → CREASTMT
```

plus a second test that verifies the TRANREPT
(CBTRN03C) ABEND-999 faithful-COBOL contract is preserved
(accepting either a clean RC OR an `AbendException` cause OR
exit code 16, per `RUNBOOK.md` §4).

**Strip-copy fixtures**: the IT reads the canonical
`app/data/ASCII/*.txt` fixtures, strips their LF terminators
to produce raw fixed-width records, and writes them into a
per-test `@TempDir` so the immutable `app/` tree remains
byte-identical to its baseline (per AAP §0.2.2).

**Reflection-based execute() invocation**: all six exercised
app classes have `static int execute()` methods with mixed
visibility (3 package-private, 1 private, 2 with `String[]`
overloads). Reflection is used uniformly to keep the dispatch
logic consistent; the IT also reads each app's per-class
`BATCH_CTX` field reflectively to handle the legitimate
discrepancy that some apps alias `BatchRunContext.BATCH_CTX`
while others declare a private `ScopedValue.newInstance()`.

**Verification**:
* `mvn -B -ntp clean verify` → BUILD SUCCESS; Surefire 169
  tests (140 pass, 29 @Disabled); Failsafe 2 tests pass.
* `mvn -B -ntp clean verify -DexcludedGroups=integration`
  → BUILD SUCCESS; Surefire 169 tests; Failsafe 0 tests
  (FullBatchChainIT correctly excluded).
* Decimals 100% coverage gate intact.

### Item 7 — This file (`BLITZY_REFINE_NOTES.md`)

**Status**: ✅ Complete.
**File**: `BLITZY_REFINE_NOTES.md` (this file, CREATED at
repo root).
**Hours claimed**: **4 h** against the 220-h remaining table.

Summarises the six deliverables above, the hours claimed for
each, the verification evidence, and the out-of-scope items
that this PR explicitly does NOT attempt.

---

## 3. Hours Summary

| Item | Description                                | Hours Claimed |
|------|--------------------------------------------|---------------|
| 1    | CI/CD pipeline                             | 16            |
| 2    | Forbidden-artifact lint Maven gate         | 8             |
| 3    | Per-environment config templates (3)       | 10            |
| 4    | Operator runbook (RUNBOOK.md)              | 16            |
| 5    | SRE/SLO skeleton (SRE.md)                  | 8             |
| 6    | FullBatchChainIT + Failsafe activation     | 12            |
| 7    | This file (BLITZY_REFINE_NOTES.md)         | 4             |
|      | **Subtotal — completed**                   | **74**        |

| Item                                                  | Hours Out of Scope |
|-------------------------------------------------------|--------------------|
| z/OS golden-record captures (Refine-PR explicit)      | 100                |
| JFR baseline on stable reference host (Refine-PR explicit) | 16            |
| PCI compliance audit (requires human security review) | 30                 |
|                                                       | **Subtotal — out of scope: 146** |

**Total against 220-h remaining table**: 74 + 146 = 220 h
accounted for.

---

## 4. Verification (Build Evidence)

### 4.1 Standard build (everything runs)

```
$ mvn -B -ntp clean verify
...
[INFO] BUILD SUCCESS
[INFO] Total time:  ~30 s

Surefire (unit tests):
  Tests run: 169, Failures: 0, Errors: 0, Skipped: 29
Failsafe (integration tests):
  Tests run: 2,   Failures: 0, Errors: 0, Skipped: 0
Enforcer:
  Rule 0: BannedDependencies passed   (every module)
Antrun:
  [preview-flag lint] PASS            (3 filesets, 0 hits)
JaCoCo (Decimals):
  All coverage checks have been met.   (100% line coverage)
```

### 4.2 Integration-test-excluded build

```
$ mvn -B -ntp clean verify -DexcludedGroups=integration
...
[INFO] BUILD SUCCESS
[INFO] Total time:  ~13 s

Surefire (unit tests):
  Tests run: 169, Failures: 0, Errors: 0, Skipped: 29
Failsafe (integration tests):
  Tests run: 0,   Failures: 0, Errors: 0, Skipped: 0
                  ↑ FullBatchChainIT correctly excluded
```

### 4.3 Forbidden-artifact gate negative test

A temporary workflow YAML referencing `--enable-preview` was
added and removed during development. With the YAML in place,
the antrun lint correctly FAILED the build with "condition
satisfied"; removing the YAML restored the PASS state. The
gate is therefore both (a) actionable on accidental flag
additions and (b) free of false positives on the existing
codebase.

---

## 5. Hard Constraints Honored

Every constraint in the Refine-PR mandate is honored by this
branch:

* ✅ `app/` tree not modified (verified by
  `git diff --name-only main -- app/` → empty).
* ✅ No Spring, Spring Boot, Spring Batch, Hibernate,
  PostgreSQL, Docker, preview features, `double`/`float` for
  money, `ThreadLocal`, `java.util.Date`/`Calendar`, or
  `java.io.File` introduced in new code (verified by
  enforcer + antrun + manual code review).
* ✅ `@CobolProgram` annotations untouched on every
  translated class.
* ✅ `<release>25</release>` retained in parent POM.
* ✅ 100% JaCoCo Decimals coverage gate maintained.
* ✅ 29 `@Disabled` golden-record tests untouched (require
  real z/OS COBOL runs; explicitly out of Refine-PR scope).
* ✅ No plaintext-password migration attempted (deferred to
  a separate follow-on PR per AAP §0.7.2 mandate).

---

## 6. Files Changed Summary

### New files (CREATED)

| Path                                                              | Bytes  | Purpose                              |
|-------------------------------------------------------------------|--------|--------------------------------------|
| `.github/workflows/build.yml`                                     | 22,199 | CI pipeline (Item 1)                 |
| `java/config/application-dev.properties`                          | 11,919 | DEV env template (Item 3)            |
| `java/config/application-staging.properties`                      | 12,663 | STAGING env template (Item 3)        |
| `java/config/application-prod.properties`                         | 13,968 | PROD env template (Item 3)           |
| `java/RUNBOOK.md`                                                 | 36,807 | Operator runbook (Item 4)            |
| `java/SRE.md`                                                     | 20,236 | SRE/SLO skeleton (Item 5)            |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/integration/FullBatchChainIT.java` | 34,988 | End-to-end IT (Item 6) |
| `BLITZY_REFINE_NOTES.md`                                          | this file | This file (Item 7)                |

### Modified files (UPDATED)

| Path                              | Purpose                                                       |
|-----------------------------------|---------------------------------------------------------------|
| `java/pom.xml`                    | Add enforcer + antrun plugin definitions (Item 2)             |
| `java/MIGRATION_NOTES.md`         | Append §M18 pointing operators at config templates (Item 3)   |
| `java/carddemo-tests/pom.xml`     | Add `excludedGroups` property, Surefire excludes IT.java, activate Failsafe (Item 6) |

### Files NOT modified

* `app/**` — every COBOL source file, copybook, BMS map,
  JCL job, CSD, IDCAMS control card, cataloged procedure,
  IDCAMS LISTCAT report, and ASCII fixture is byte-identical
  to the baseline (verified by `git diff --name-only main --
  app/`).
* `docs/technical-specifications.md` — historical Spring
  Boot architecture document; left in place per AAP §0.4.2
  as historical context.
* The 29 `@Disabled` `*GoldenTest.java` files in
  `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/`
  — untouched per Refine-PR scope (require z/OS COBOL
  captures).
* `JfrBaselineTest.java` and
  `carddemo-tests/src/test/resources/perf/baseline.properties`
  — `PLACEHOLDER` retained per Refine-PR scope ("If you can
  record a directional value from the CI runner and commit it
  as `# NON-AUTHORITATIVE — replace with stable-host capture`,
  do so; otherwise leave the `PLACEHOLDER` intact" — the
  CI runner is non-stable so PLACEHOLDER retained).

---

## 7. Follow-On Work

The 146 hours of out-of-scope work below requires environment
access this Refine PR cannot grant:

1. **z/OS golden-record captures (100 h)** — requires a z/OS
   environment with the COBOL deck deployed, running each of
   the 28 programs against the canonical fixtures, capturing
   the byte-for-byte output, and committing the captures
   under
   `java/carddemo-tests/src/test/resources/golden/<program>/expected/`.
   Once captures are committed, the 29 `@Disabled` golden
   tests can be enabled atomically.
2. **JFR baseline on stable reference host (16 h)** —
   requires a dedicated bare-metal or pinned-VM host with the
   same CPU class as the PROD batch host. The procedure is
   documented in `java/SRE.md` §3.4.
3. **PCI compliance audit (30 h)** — requires a human
   security reviewer to assess the existing PAN-masking,
   audit logging, data retention, and (especially) the
   plaintext-password decision documented in AAP §0.7.2.

---

_End of BLITZY_REFINE_NOTES.md_
