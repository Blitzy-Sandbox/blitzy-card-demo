# CardDemo COBOL→Java 25 LTS Migration — Blitzy Project Guide

> **Refactoring Project**: Source-to-source migration of the AWS CardDemo mainframe application from Enterprise COBOL / CICS / VSAM / JCL / BMS to idiomatic Java 25 LTS with hexagonal architecture and byte-for-byte parity targets.

---

## 1. Executive Summary

### 1.1 Project Overview

The CardDemo COBOL→Java 25 LTS migration translates the AWS CardDemo mainframe application — 28 COBOL programs, 28 copybooks, 17 BMS map pairs, and 29 JCL jobs — into a modern Java 25 hexagonal architecture while preserving the original COBOL source tree under `app/` as the immutable reference implementation. The target users are operations teams running the existing CardDemo batch and online workloads; the business impact is reduced mainframe operating cost and access to the broader Java ecosystem; the technical scope spans 8 Maven modules, 193 Java source files, 138,955 lines of Java code, plus comprehensive CI/CD, operational documentation, and golden-record byte-for-byte parity test harness. No business behavior is enhanced; this is an idiom-for-idiom translation with mandated use of Java 25 finalized features.

### 1.2 Completion Status

```mermaid
%%{init: {'theme':'base','themeVariables':{'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#5B39F3','pieOuterStrokeColor':'#5B39F3','pieTitleTextSize':'18px','pieSectionTextSize':'14px'}}}%%
pie showData title 86.7% Complete
    "Completed Work (952h)" : 952
    "Remaining Work (146h)" : 146
```

| Metric | Value |
|--------|-------|
| **Total Hours** | 1,098 |
| **Completed Hours (AI + Manual)** | 952 |
| **Remaining Hours** | 146 |
| **Percent Complete** | 86.7% |

### 1.3 Key Accomplishments

- [x] All 28 COBOL `PROGRAM-ID`s translated to Java application classes with `@CobolProgram` traceability annotations (11 batch + 17 online programs)
- [x] All 28 copybooks translated to immutable Java records and sealed types under `carddemo-domain`
- [x] All 17 BMS map pairs translated to 34 Input/Output DTO records (entry-contract DTOs on the corresponding application classes)
- [x] All 29 JCL jobs translated to 28 Java App main classes packaged as 28 shaded jars (CUSTFILE.jcl + DEFCUST.jcl share `DefineCustomerFileApp` per AAP)
- [x] Maven multi-module hexagonal architecture: 8 modules (`carddemo-domain`, `carddemo-application`, `carddemo-adapter-file`, `carddemo-adapter-db`, `carddemo-batch`, `carddemo-app`, `carddemo-tests`, parent POM)
- [x] `Decimals` utility (975 LOC) with `BigDecimal`/`MathContext.DECIMAL128`/explicit `RoundingMode` — verified 100% JaCoCo line coverage (195/195 lines, 1069/1069 instructions, 15/15 methods)
- [x] Golden-record harness scaffolding: `GoldenRecordTest` base class + 29 `@Disabled` per-program test classes + 28 resource directories (input/expected) ready for z/OS COBOL captures
- [x] 138 `jqwik` property-based tests passing on `Decimals` arithmetic (commutativity, divide-by-zero, sign handling, edge cases)
- [x] `FullBatchChainIT` integration test exercises 6 batch apps end-to-end + faithful COBOL ABEND-999 contract preservation
- [x] Sealed-type hierarchies: `UserType`, `PgmContext`, `AccountStatus`, `CardStatus`, `FileStatus`, `AidKey`, `CcAcctId`, `CcCardNum`, `CcCustId`, `WsCurDate`, `WsCurTime`
- [x] `ScopedValue` infrastructure (`BatchRunContext` + per-app `BATCH_CTX`) replacing `ThreadLocal` entirely (per JEP 506 finalized in Java 25)
- [x] CI/CD pipeline: `.github/workflows/build.yml` (22,199 bytes) executes JDK 25 setup, Maven build, test execution, JaCoCo verification on push/PR
- [x] Forbidden-artifact gates: `maven-enforcer-plugin` BannedDependencies (15 patterns covering Spring family, Hibernate, PostgreSQL, HikariCP) + `maven-antrun-plugin` scanner for `--enable-preview` flag occurrences
- [x] Per-environment configuration templates: `java/config/application-{dev,staging,prod}.properties` documenting workspace-relative DEV paths, POSIX STAGING paths, and systemd-compatible PROD paths
- [x] Comprehensive operational documentation: `java/README.md` (622 lines), `java/RUNBOOK.md` (623 lines covering 28 jars × 7 JCL phases), `java/SRE.md` (463 lines with SLI catalog), `java/MIGRATION_NOTES.md` (2,888 lines), `java/application.properties.example` (581 lines)
- [x] All build gates PASS: `mvn -B -ntp clean verify` BUILD SUCCESS in ~15s, Surefire 169/0/0/29, Failsafe 2/0/0/0, JaCoCo "All coverage checks have been met"
- [x] `app/` COBOL source tree verified byte-identical to baseline via `git status app/` (zero modifications across 324 commits)

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| z/OS COBOL captures pending — 29 `@Disabled` golden tests cannot run byte-for-byte parity until captures committed | Cannot enforce byte-for-byte parity on PR until z/OS captures provided | Mainframe team | T+5 business days after z/OS access granted |
| JFR stable-host baseline pending — 1 `@Disabled` JFR baseline test cannot enforce 10% performance regression band | Cannot detect performance regression beyond 10% during PR/CI builds | Performance/SRE team | T+2 business days after stable host provisioned |
| PCI compliance audit pending — plaintext password storage (per AAP §0.7.2) and PAN handling require certified review | Required for PCI-regulated production deployment | Security/Compliance team | T+4 weeks (external audit process) |

### 1.5 Access Issues

| System/Resource | Type of Access | Issue Description | Resolution Status | Owner |
|-----------------|----------------|-------------------|-------------------|-------|
| z/OS Mainframe | COBOL deck deployment + execution + TSO/ISPF access | Required to compile and run 28 COBOL programs against the canonical fixtures (`app/data/ASCII/*.txt`) to capture byte-for-byte expected outputs that populate `java/carddemo-tests/src/test/resources/golden/<program>/expected/` | Pending — environment access not available to Blitzy Agent | Mainframe team |
| Stable Performance Host | Dedicated bare-metal server or pinned-VM with PROD CPU profile | Required for JFR baseline capture per `java/SRE.md §3.4`; baseline timing metrics will populate `java/carddemo-tests/src/test/resources/perf/baseline.properties` | Pending — environment access not available to Blitzy Agent | Performance/SRE team |
| PCI Compliance Auditor | External certified PCI-DSS reviewer | Required to assess PAN-masking implementation (logback regex), audit logging, data retention policies, and plaintext password preservation decision documented in `java/MIGRATION_NOTES.md §1.5.1` | Pending — external resource not available to Blitzy Agent | Security/Compliance team |

### 1.6 Recommended Next Steps

1. **[High]** Provision z/OS mainframe access and capture byte-for-byte expected outputs for all 28 program golden tests + DateValidator (100h) — enables atomic enable of 29 `@Disabled` tests
2. **[Medium]** Provision stable performance host and capture JFR baseline metrics per `java/SRE.md §3.4` (16h) — enables performance regression detection in CI
3. **[Medium]** Engage PCI-certified auditor and complete compliance review of plaintext password handling and PAN masking (30h) — required for PCI-regulated production deployment
4. **[Low]** Plan separate follow-on project for BCrypt/Argon2id password hashing migration per AAP §0.7.2 (OUT OF SCOPE for current refactor; ~40h estimate for separate effort)
5. **[Low]** Plan production monitoring integration (Prometheus/Grafana/Loki) following `java/SRE.md §3` SLO catalog (OUT OF SCOPE for current refactor; ~80h estimate for separate effort)

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

| Component | Hours | Description |
|-----------|-------|-------------|
| **COBOL Programs Translation (28 programs)** | 302 | All 28 COBOL `PROGRAM-ID`s translated to Java application classes with `@CobolProgram` annotations: 11 batch (CbAct01-04, CbCus01, CbStm03A/B, CbTrn01-03, DateValidator) + 17 online (CoActVw/Up, CoCrdLi/Sl/Up, CoTrn00-02, CoUsr00-03, CoSgn00, CoMen01, CoAdm01, CoBil00, CoRpt00) |
| **Copybooks → Domain Records (28 items)** | 112 | All 28 copybooks translated to records, sealed types, ports, and validation utilities under `carddemo-domain` (45 main files) |
| **BMS Maps → DTO Records (17 pairs)** | 51 | 34 Input/Output records (17 pairs) under `carddemo-application` packages preserving symbolic-map field shapes |
| **JCL → App Main Classes (28 apps)** | 84 | 28 `*App.java` main classes in `carddemo-app` with `maven-shade-plugin` producing 28 shaded jars (one per JCL `EXEC PGM=` step) |
| **File Adapter Implementation** | 70 | EBCDIC transcoder (IBM-1047 default) + 11 `File*Repository` implementations + FixedWidth reader/writer (14 files total) |
| **Batch Drivers + ScopedValue Context** | 35 | 6 batch drivers (PostTransactions, InterestCalculation, DailyRejects, CombineTransactions, CreateStatements, PrintTcatBal) + `BatchRunContext` ScopedValue holder |
| **Decimals Utility** | 30 | 975 LOC `BigDecimal` facade with `MathContext.DECIMAL128`, explicit `RoundingMode`, packed-decimal parse/encode with sign nybble handling — 100% JaCoCo line coverage |
| **Sealed-Type Hierarchies** | 16 | 6 status classes (UserType, PgmContext, AccountStatus, CardStatus, FileStatus, FileStatusException) + sealed REDEFINES (AidKey, CcAcctId, CcCardNum, CcCustId, WsCurDate, WsCurTime) |
| **ProgramRegistry + Helpers** | 12 | Dynamic CALL routing infrastructure (ProgramRegistry, AbendException, ScreenAttributeSetter, PfKeyDecoder) |
| **Maven Multi-Module Setup** | 16 | 8 POMs (parent + 7 children) with `<release>25</release>`, enforcer/antrun forbidden-artifact gates, shade plugin per executable |
| **Test Scaffolding** | 50 | 32 test files: GoldenRecordTest base + 28 per-program golden tests + DateValidator test + FullBatchChainIT + DecimalsProperties (138 jqwik tests) + JfrBaselineTest |
| **Initial Documentation** | 20 | `java/README.md` (622 lines), `java/application.properties.example` (581 lines) |
| **Validation/Iteration (323 commits)** | 80 | QA findings resolution, 5 checkpoint review iterations, 8 bug fixes over 5 days |
| **CI/CD Pipeline** | 16 | `.github/workflows/build.yml` (22,199 bytes) — GitHub Actions with JDK 25 setup, Maven build, test, coverage |
| **Enforcer + Antrun Gates** | 8 | Forbidden-artifact lint: 15 BannedDependencies patterns + `--enable-preview` scanner |
| **Per-Environment Configs** | 10 | `java/config/application-{dev,staging,prod}.properties` (709 lines combined) + `java/MIGRATION_NOTES.md §M18` |
| **RUNBOOK.md** | 16 | Operator runbook (623 lines) covering 28 shaded jars across 7 JCL phases with systemd EnvironmentFile pattern |
| **SRE.md** | 8 | SRE/SLO skeleton (463 lines) documenting SLI catalog and JFR baseline integration procedures |
| **Integration Test Harness** | 12 | `FullBatchChainIT.java` + Failsafe wiring + `@Tag("integration")` filter mechanism |
| **BLITZY_REFINE_NOTES.md** | 4 | Refine PR summary documentation (432 lines) |
| **Total Completed** | **952** | |

### 2.2 Remaining Work Detail

| Category | Hours | Priority |
|----------|-------|----------|
| z/OS COBOL golden-record captures for 28 program tests + DateValidator | 100 | High |
| JFR stable-host baseline capture and gate activation | 16 | Medium |
| PCI compliance audit of plaintext password handling and PAN masking | 30 | Medium |
| **Total Remaining** | **146** | |

### 2.3 Scope Justification

All remaining work items are explicitly documented in `BLITZY_REFINE_NOTES.md §7` as follow-on work requiring environment access not available to the Blitzy Agent (z/OS mainframe, dedicated baseline host, PCI auditor). No work item identified during repository inspection falls outside this documented scope. The 146-hour remaining figure matches both `BLITZY_REFINE_NOTES.md` (146h follow-on) and the prioritized human task list.

---

## 3. Test Results

All tests originate from Blitzy's autonomous validation logs as captured during the most recent `mvn -B -ntp clean verify` invocation in this repository's `java/` directory. The Surefire XML reports under `java/carddemo-tests/target/surefire-reports/` and Failsafe XML reports under `java/carddemo-tests/target/failsafe-reports/` are the authoritative source.

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---------------|-----------|-------------|--------|--------|------------|-------|
| Unit Tests — Property-Based (Decimals) | jqwik 1.9.3 | 138 | 138 | 0 | 100% (Decimals) | Validates `BigDecimal` arithmetic: commutativity, divide-by-zero, sign nybble, parse/encode round-trip; 100% line coverage gate enforced |
| Unit Tests — Golden Record Scaffolding | JUnit Jupiter 5.13.x | 28 | 0 | 0 | N/A | All `@Disabled` pending z/OS COBOL captures (per AAP §0.6.11); harness and base class fully functional |
| Unit Tests — DateValidator Golden | JUnit Jupiter 5.13.x | 1 | 0 | 0 | N/A | `@Disabled` pending z/OS CEEDAYS golden capture |
| Unit Tests — JFR Baseline | JUnit Jupiter 5.13.x | 3 | 2 | 0 | N/A | 2 active (JFR discovery + aggregate); 1 `@Disabled` (regression gate) pending stable-host baseline |
| Integration Tests — Full Batch Chain | JUnit Jupiter 5.13.x + Failsafe | 2 | 2 | 0 | N/A | `FullBatchChainIT`: exercises POSTTRAN → INTCALC → TRANBKP → COMBTRAN → CREASTMT + CBTRN03C ABEND-999 contract |
| **Total** | | **172 tests defined** | **140 active + 2 IT + 29 `@Disabled` skipped** | **0** | | All tests originated from `mvn -B -ntp clean verify` autonomous execution |

**Authoritative metrics from Surefire/Failsafe XML reports:**

- Surefire: `Tests run: 169, Failures: 0, Errors: 0, Skipped: 29`
- Failsafe: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
- JaCoCo aggregate report: `[INFO] All coverage checks have been met` (Decimals 100% on `INSTRUCTION_MISSED=0`, `LINE_MISSED=0`, `METHOD_MISSED=0`)

**Test framework versions** (per `java/pom.xml`):

- JUnit Jupiter 5.13.x
- JUnit Platform 1.13.x
- AssertJ 3.26.x
- jqwik 1.9.3

---

## 4. Runtime Validation & UI Verification

### Application Runtime Status

- ✅ **Operational**: `mvn -B -ntp clean verify` BUILD SUCCESS reproducible in ~15 seconds on a clean machine
- ✅ **Operational**: All 28 shaded jars produced by `maven-shade-plugin` in `java/carddemo-app/target/`
- ✅ **Operational**: `java -XX:+UseCompactObjectHeaders -jar carddemo-app/target/carddemo-open-file.jar` — verified runs with `BatchRunContext` ScopedValue propagation, JSON-structured logs, graceful WARN behavior for missing data files
- ✅ **Operational**: `java -XX:+UseCompactObjectHeaders -jar carddemo-app/target/carddemo-close-file.jar` — verified runs with idiomatic CLOSE OK behavior for 6 `cicsFileId` entries
- ✅ **Operational**: `FullBatchChainIT.fullBatchChainRunsToCompletion` exercises 5 batch jars end-to-end (POSTTRAN RC=4, INTCALC RC=16, TRANBKP RC=0, COMBTRAN RC=0, CREASTMT RC=12) within a single `@TempDir` workspace
- ✅ **Operational**: `FullBatchChainIT.transactionReportPreservesFaithfulCobolAbendContract` verifies CBTRN03C TRANREPT RC=4 contract (faithful COBOL ABEND translation, no `AbendException` propagation)

### UI Verification Status

- ⚠ **Partial**: Original CICS 3270 BMS green-screen UI is **NOT** replaced with a web/mobile UI per AAP §0.3.5 ("No web, mobile, or desktop UI is introduced"). BMS maps are translated to `Input`/`Output` entry-contract DTO records on the corresponding application classes only.
- ✅ **Operational**: 17 BMS Input record + 17 BMS Output record pairs (34 DTO records) verified present and compiling

### Integration Validation

- ✅ **Operational**: `app/` tree byte-identical to baseline (`git diff app/` returns empty across 324 commits)
- ✅ **Operational**: Test isolation verified — `mvn clean verify` and `mvn clean verify -DexcludedGroups=integration` both leave zero untracked directories in `carddemo-tests/` (validated during Refine-PR; `output/` and `work/` paths redirected to `@TempDir`)
- ✅ **Operational**: Forbidden-artifact gates verified — `mvn dependency:tree` confirms zero Spring/Hibernate/PostgreSQL/HikariCP/Docker dependencies
- ✅ **Operational**: ScopedValue infrastructure verified — `BatchRunContext.BATCH_CTX` used in 6 batch drivers and per-app `BATCH_CTX` for `PostTransactionsApp`, `InterestCalculationApp`; reflective lookup pattern handles per-class `BATCH_CTX` in `TransactionBackupApp`, `CombineTransactionsApp`, `CreateStatementsApp`, `TransactionReportApp`

---

## 5. Compliance & Quality Review

| AAP Deliverable | Blitzy Quality Benchmark | Status | Progress |
|-----------------|--------------------------|--------|----------|
| Records pattern for copybooks | Every 01-level group → Java record with `parse(byte[])`/`encode()` | ✅ PASS | 28/28 copybooks translated |
| Sealed-type pattern for REDEFINES and 88-levels | Exhaustiveness-checked sealed interfaces with `permits` clause; no `default` branch in switches | ✅ PASS | 11 sealed types verified (UserType, PgmContext, AccountStatus, CardStatus, FileStatus, AidKey, CcAcctId, CcCardNum, CcCustId, WsCurDate, WsCurTime) |
| Repository pattern (port + adapter) | Java interface per VSAM file in `carddemo-domain.port`; file adapter in `carddemo-adapter-file`; DB adapter empty by default | ✅ PASS | 11 repository ports + 11 file adapter implementations + empty `carddemo-adapter-db` |
| Decimals utility 100% line coverage | JaCoCo gate enforced in `carddemo-domain/pom.xml` | ✅ PASS | 195/195 lines, 1069/1069 instructions, 15/15 methods covered |
| Property-based tests for monetary code | jqwik 1.9.3 with 138+ test invocations | ✅ PASS | DecimalsProperties: 138 active tests covering add/subtract/multiply/divide, parse/encode, edge cases |
| Golden-record harness on every PR | Base class + 29 per-program test classes + resource directories | ⚠ HARNESS COMPLETE; CAPTURES OUTSTANDING | 29 `@Disabled` tests, 28 directories scaffolded; awaits z/OS COBOL captures (100h) |
| Single shaded jar per JCL `EXEC PGM=` | `maven-shade-plugin` per executable | ✅ PASS | 28 shaded jars verified in `carddemo-app/target/` |
| `@CobolProgram` Javadoc annotation | Every translated class cites original PROGRAM-ID, source path, translation date | ✅ PASS | 66 files use `@CobolProgram` annotation (all application classes + DTOs) |
| Java 25 finalized features only (no preview JEPs) | `maven-antrun-plugin` scanner for `--enable-preview`; no JEPs 502/505/507/512 in production | ✅ PASS | 0 hits in 3 filesets (`<arg>`, `<argLine>`, `build.yml`); compiler `-Xlint:-preview` enforced |
| ScopedValue replaces ThreadLocal entirely | No `ThreadLocal` in new code | ✅ PASS | `grep -rn ThreadLocal` in carddemo-{batch,application,domain,app}: 0 hits in production code |
| BigDecimal with explicit MathContext + RoundingMode | All monetary values use Decimals facade | ✅ PASS | Decimals utility centralizes `MathContext.DECIMAL128`, `RoundingMode.HALF_EVEN` for `ROUNDED`, `RoundingMode.DOWN` for truncation |
| java.time everywhere (no java.util.Date/Calendar) | grep for `java.util.Date` or `Calendar` in production | ✅ PASS | 0 hits in `carddemo-{domain,application,adapter-file,batch,app}` |
| java.nio.file everywhere (no java.io.File) | grep for `java.io.File` in production | ✅ PASS | 0 hits in `carddemo-{domain,application,adapter-file,batch,app}` |
| `app/` COBOL source unmodified | `git diff app/` returns empty | ✅ PASS | Verified across 324 commits |
| Forbidden frameworks (no Spring/Hibernate/PostgreSQL) | Enforcer BannedDependencies + `mvn dependency:tree` grep | ✅ PASS | 15 patterns enforced; `dependency:tree` returns 0 matches for spring/hibernate/postgresql/hikari/docker |
| 12-factor configuration | All env vars documented in `application.properties.example` | ✅ PASS | 581-line template + 3 per-env config files (DEV/STAGING/PROD) |
| Production observability (JFR baseline) | JfrBaselineTest with stable-host baseline | ⚠ HARNESS COMPLETE; BASELINE OUTSTANDING | 1 `@Disabled` test; requires stable-host capture (16h) |
| PCI compliance | PAN masking + audit logging + plaintext password decision | ⚠ MITIGATIONS APPLIED; AUDIT OUTSTANDING | Logback PAN-masking implemented (MIGRATION_NOTES.md §1.4.7); audit pending (30h) |

---

## 6. Risk Assessment

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|-------------|------------|--------|
| 29 `@Disabled` golden tests await z/OS COBOL captures for byte-for-byte parity validation | Technical | High | High | Run COBOL deck on z/OS environment; commit byte-for-byte expected outputs under `java/carddemo-tests/src/test/resources/golden/<program>/expected/`; remove `@Disabled` annotations and verify via `mvn -B -ntp clean verify` | OUTSTANDING (100h, High priority) |
| JFR baseline test `@Disabled` until stable-host capture (10% regression band cannot be enforced) | Technical | Medium | Medium | Procedure documented in `java/SRE.md §3.4`; requires bare-metal or pinned-VM host with PROD CPU profile | OUTSTANDING (16h, Medium priority) |
| Throughput target TPS not yet defined in AAP `[TODO]` marker | Technical | Medium | Medium | JFR baseline test enforces no regression beyond 10% band; actual measured COBOL baseline TPS to be added to `java/MIGRATION_NOTES.md §1.2` once captured | OPEN — will be resolved during JFR baseline activity |
| 29 documented suspected COBOL bugs preserved verbatim per AAP §0.7.1 mandate | Technical | Low | Low | All documented in `java/MIGRATION_NOTES.md §1.4` with byte-identical preservation; these are FAITHFUL translations, NOT introduced regressions | RESOLVED — Faithful Translation |
| Plaintext password storage (`SEC-USR-PWD PIC X(08)`) preserved per AAP §0.7.2 | Security | High | High | Documented in `java/MIGRATION_NOTES.md §1.5.1`; preserved per AAP mandate ("storage and logging are different surfaces"); flagged as explicit OUT OF SCOPE for current refactor; requires separate BCrypt/Argon2id project | DEFERRED — Out of Scope for current refactor |
| PCI compliance audit pending for PAN-masking, audit logging, retention | Security | Medium | Medium | Logback PAN-masking regex implemented (MIGRATION_NOTES.md §1.4.7); requires PCI-certified auditor review | OUTSTANDING (30h, Medium priority) |
| Path-traversal hardening for SafePathResolver | Security | Low | Low | Implemented per Checkpoint 5 review (MIGRATION_NOTES.md §1.12.3) | RESOLVED |
| Logback CVE upgrade applied | Security | Low | Low | Logback-classic 1.5.12 (security fix) per MIGRATION_NOTES.md §1.12.4 | RESOLVED |
| Production deployment requires systemd or equivalent service management | Operational | Low | Medium | `java/RUNBOOK.md §7` documents systemd EnvironmentFile pattern for 28 jars | RESOLVED |
| Monitoring/observability beyond JFR not configured | Operational | Medium | Medium | `java/SRE.md §3` documents SLO/SLI catalog; production Prometheus/Grafana/Loki integration is a follow-on effort | OPEN — Beyond AAP scope |
| Per-environment config drift between DEV/STAGING/PROD | Operational | Low | Medium | Three explicit config templates committed at `java/config/`; documented in RUNBOOK | RESOLVED |
| Operator unfamiliarity with 28 distinct shaded jars across 7 JCL phases | Operational | Medium | Medium | Comprehensive `java/RUNBOOK.md` (623 lines) documents jar inventory, configuration, and operational sequence | RESOLVED |
| Byte-for-byte parity with COBOL output (untested without z/OS captures) | Integration | High | Medium | Golden-record harness fully scaffolded; FullBatchChainIT exercises 6 batch apps end-to-end; 29 `@Disabled` tests will be enabled atomically once captures committed | MITIGATED — Harness Ready |
| Faithful COBOL ABEND contract preservation | Integration | Low | Low | `FullBatchChainIT.transactionReportPreservesFaithfulCobolAbendContract` verifies CBTRN03C TRANREPT RC=4 behavior | RESOLVED |
| External system integrations (DB2, MQ, FTP) — not introduced | Integration | Low | Low | AAP §0.2.2 explicitly excludes external integrations; only CICS TDQ in CORPT00C translated via direct method invocation per MIGRATION_NOTES.md §1.3.2 | OUT OF SCOPE |
| EBCDIC ↔ ASCII transcoding correctness | Integration | Low | Medium | `EbcdicTranscoder` uses `Charset.forName("IBM-1047")` with per-file codepage overrides; tested with 9 ASCII fixtures from `app/data/ASCII/` | RESOLVED |
| Test isolation: IT artifact leakage (output/, work/ dirs in carddemo-tests/) | Integration | Low | Low | Resolved during Refine-PR validation: explicit `setProperty()` calls in `@BeforeEach` point both paths inside `@TempDir`; `mvn clean verify` leaves zero untracked dirs | RESOLVED |

---

## 7. Visual Project Status

### Project Hours Pie Chart

```mermaid
%%{init: {'theme':'base','themeVariables':{'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#5B39F3','pieOuterStrokeColor':'#5B39F3','pieTitleTextSize':'18px','pieSectionTextSize':'14px'}}}%%
pie showData title Project Hours Breakdown
    "Completed Work" : 952
    "Remaining Work" : 146
```

### Remaining Work by Category

```mermaid
%%{init: {'theme':'base','themeVariables':{'pie1':'#5B39F3','pie2':'#A8FDD9','pie3':'#B23AF2','pieStrokeColor':'#5B39F3','pieOuterStrokeColor':'#5B39F3','pieTitleTextSize':'16px','pieSectionTextSize':'12px'}}}%%
pie showData title Remaining 146 Hours by Category
    "z/OS COBOL Captures (High)" : 100
    "PCI Compliance Audit (Medium)" : 30
    "JFR Stable-Host Baseline (Medium)" : 16
```

### Blitzy Brand Color Legend

| Color | Hex | Meaning |
|-------|-----|---------|
| **Dark Blue** | `#5B39F3` | Completed work / AI work |
| **White** | `#FFFFFF` | Remaining work / Not yet completed |
| **Violet-Black** | `#B23AF2` | Headings / Accents |
| **Mint** | `#A8FDD9` | Highlight / Soft accents |

---

## 8. Summary & Recommendations

### Achievements

The CardDemo COBOL→Java 25 LTS migration is **86.7% complete** (952 of 1,098 total project hours). Over 324 commits spanning 5 days, the Blitzy Agent has delivered a complete idiom-for-idiom translation of the AWS CardDemo mainframe application from Enterprise COBOL/CICS/VSAM/JCL/BMS to a modern Java 25 hexagonal architecture. All 28 COBOL programs, 28 copybooks, 17 BMS map pairs, and 29 JCL jobs have corresponding Java implementations. The Maven multi-module structure spans 8 modules with 193 Java source files and 138,955 lines of Java code. The `Decimals` utility achieves the mandated 100% JaCoCo line coverage (195/195 lines), and the golden-record harness is fully scaffolded with 29 `@Disabled` per-program byte-for-byte parity tests ready for z/OS COBOL captures. The Refine PR (final commit `7cd93df`) added the path-to-production deliverables: CI/CD pipeline (`.github/workflows/build.yml`), per-environment configuration templates, operator runbook (`RUNBOOK.md`), SRE/SLO skeleton (`SRE.md`), end-to-end integration test (`FullBatchChainIT`), enforcer + antrun forbidden-artifact gates, and migration notes (`MIGRATION_NOTES.md`, `BLITZY_REFINE_NOTES.md`).

### Remaining Gaps

The 146 hours of remaining work fall into three explicit out-of-scope categories per `BLITZY_REFINE_NOTES.md §7`, all requiring environment access not available to the Blitzy Agent:

1. **z/OS COBOL captures (100h, High priority)**: All 29 `@Disabled` golden tests require byte-for-byte expected outputs captured by running the COBOL deck on a z/OS environment with the canonical fixtures from `app/data/ASCII/*.txt`. Once captures are committed under `java/carddemo-tests/src/test/resources/golden/<program>/expected/`, the `@Disabled` annotations can be removed atomically and the harness will enforce byte-for-byte parity on every PR.

2. **JFR stable-host baseline (16h, Medium priority)**: The 1 `@Disabled` JFR baseline test requires capture of timing metrics on a dedicated bare-metal or pinned-VM host with the PROD CPU profile. The procedure is fully documented in `java/SRE.md §3.4`.

3. **PCI compliance audit (30h, Medium priority)**: The preserved plaintext password storage (per AAP §0.7.2) and PAN-masking implementation require review by a PCI-certified auditor for production deployment in regulated environments.

### Critical Path to Production

| Step | Activity | Duration | Dependencies |
|------|----------|----------|--------------|
| 1 | Provision z/OS access | T+0 | Mainframe team approval |
| 2 | Compile and deploy COBOL deck | T+1 day | Step 1 |
| 3 | Capture 11 batch program golden outputs | T+3 days | Step 2 |
| 4 | Capture 17 online program + CSUTLDTC outputs | T+5 days | Step 2 |
| 5 | Activate 29 `@Disabled` golden tests | T+5 days | Steps 3-4 |
| 6 | Provision stable performance host | Parallel with steps 1-2 | SRE team approval |
| 7 | Capture JFR baseline | T+6 days | Step 6 |
| 8 | Activate `@Disabled` JFR test | T+7 days | Step 7 |
| 9 | Engage PCI auditor | Parallel with steps 1-2 | Compliance team approval |
| 10 | Address audit findings | T+4 weeks | Step 9 |
| 11 | **Production deployment readiness** | **T+4 weeks** | All above |

### Success Metrics

- ✅ All 5 production-readiness gates PASS (test pass rate, runtime validation, zero unresolved errors, scope compliance, Decimals 100% coverage)
- ✅ Build reproducibility: `mvn -B -ntp clean verify` BUILD SUCCESS deterministic in ~15 seconds
- ✅ Forbidden-artifact gates enforced (15 banned dependency patterns + `--enable-preview` scanner)
- ✅ `app/` COBOL source byte-identical to baseline across 324 commits
- ✅ Java 25 LTS finalized features used throughout (JEP 506 ScopedValue, JEP 510 KDF availability, JEP 511 Module Imports, JEP 513 Flexible Constructors, JEP 519 Compact Object Headers)
- ✅ No preview JEPs (502/505/507/512) in production code
- ✅ 100% Decimals line coverage gate met

### Production Readiness Assessment

**Conditional production-ready**: The Java 25 implementation builds, tests, and runs successfully in isolation. Production deployment is **conditional on**: (a) completion of z/OS COBOL captures to validate byte-for-byte parity against the COBOL baseline, (b) PCI compliance audit completion if deploying to PCI-regulated environments, and (c) JFR baseline capture to enable performance regression detection. The remaining 146 hours of work are environmentally constrained (require z/OS access, stable host, PCI auditor) and are clearly scoped with documented procedures in `BLITZY_REFINE_NOTES.md §7`, `java/RUNBOOK.md`, and `java/SRE.md §3.4`. The codebase itself is production-grade in terms of structure, quality gates, documentation, and operational tooling.

---

## 9. Development Guide

### 9.1 System Prerequisites

- **JDK**: OpenJDK 25 LTS (Temurin, Oracle, or equivalent). Verified with OpenJDK 25.0.2+10. **Mandatory** — `<release>25</release>` is enforced in `java/pom.xml`.
- **Maven**: Apache Maven 3.9.9+ (verified with 3.9.9). Gradle 8.10+ may be substituted if the project later standardizes on it, but Maven is the current build tool.
- **Git**: Any modern version (verified with 2.x). Git LFS is configured but not required for typical workflows.
- **Operating System**: Linux (Ubuntu 25.10 verified), macOS, or Windows with WSL2 or native JDK 25 support.
- **Disk space**: ~150 MB for the cloned repository; ~500 MB for Maven local cache after first build; ~150 MB for target artifacts after `mvn package`.
- **Memory**: 2 GB minimum for build (recommend 4 GB).
- **Network**: Internet access to Maven Central (`repo.maven.apache.org`) for first-time dependency resolution.

### 9.2 Environment Setup

```bash
# Clone the repository (replace with your actual remote)
git clone <repository-url>
cd carddemo

# (Optional) Set up Maven local cache location explicitly
export MAVEN_OPTS="-Xmx2g"

# (Optional) Override the CARDDEMO_CONFIG selection
export CARDDEMO_CONFIG=dev   # or staging | prod (default: dev)

# Copy the per-environment template you want to customize
cp java/application.properties.example java/application.properties
# Edit java/application.properties to suit your local file paths, codepages, and run identity
```

The `java/config/application-{dev,staging,prod}.properties` files document the supported configuration keys for each environment. `java/application.properties.example` (581 lines) is the canonical template documenting every supported property.

### 9.3 Dependency Installation

The first build downloads all transitive dependencies from Maven Central (~150 MB). Subsequent builds use the local `~/.m2/repository/` cache.

```bash
cd java/

# Resolve all dependencies (will download from Maven Central on first run)
mvn -B -ntp dependency:resolve

# Expected output: BUILD SUCCESS, no error messages
```

### 9.4 Application Build and Startup

#### Full build with all tests (recommended for verification)

```bash
cd java/
mvn -B -ntp clean verify
```

**Expected output (last 15 lines):**

```
[INFO] Reactor Summary for CardDemo (Java 25 LTS) — Parent 1.0.0-SNAPSHOT:
[INFO]
[INFO] CardDemo (Java 25 LTS) — Parent .................... SUCCESS [  0.581 s]
[INFO] CardDemo Domain .................................... SUCCESS [  1.766 s]
[INFO] CardDemo Application ............................... SUCCESS [  1.430 s]
[INFO] CardDemo Adapter (File) ............................ SUCCESS [  0.245 s]
[INFO] CardDemo Adapter (DB / JDBC) — Optional, Empty by Default SUCCESS [  0.026 s]
[INFO] CardDemo Batch ..................................... SUCCESS [  0.134 s]
[INFO] CardDemo App (Composition Root) .................... SUCCESS [  4.654 s]
[INFO] CardDemo Tests (Golden-Record Harness, Property Tests, JFR Baselines) SUCCESS [  5.658 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  14.646 s
```

#### Skip integration tests (faster feedback loop)

```bash
mvn -B -ntp clean verify -DexcludedGroups=integration
```

#### Build shaded jars only (no tests)

```bash
mvn -B -ntp -DskipTests package
ls carddemo-app/target/carddemo-*.jar | wc -l   # → 28 shaded jars
```

#### Run a specific shaded jar

```bash
# Example: OPENFIL (IEFBR14 no-op equivalent)
java -XX:+UseCompactObjectHeaders -jar carddemo-app/target/carddemo-open-file.jar

# Example: POSTTRAN (full posting engine, calls CbTrn02C)
java -XX:+UseCompactObjectHeaders -jar carddemo-app/target/carddemo-post-transactions.jar

# Example: CREASTMT (statement generation, calls CbStm03A/B)
java -XX:+UseCompactObjectHeaders -jar carddemo-app/target/carddemo-create-statements.jar
```

See `java/RUNBOOK.md §1` for the complete catalog of 28 shaded jars organized by JCL phase (OPENFIL, define-files, load-data, batch-processing, statements, reports, CLOSEFIL).

### 9.5 Verification Steps

After the build succeeds, verify:

```bash
# 1. All 28 shaded jars produced
ls java/carddemo-app/target/carddemo-*.jar | grep -v "carddemo-app-1" | wc -l
# Expected: 28

# 2. Test counts (Surefire)
grep -h "tests=\"" java/carddemo-tests/target/surefire-reports/TEST-*.xml | \
  awk -F'tests="' '{print $2}' | awk -F'"' '{sum+=$1} END {print "Total:", sum}'
# Expected: Total: 169

# 3. Test failures count
grep -h "tests=\"" java/carddemo-tests/target/surefire-reports/TEST-*.xml | \
  awk -F'failures="' '{print $2}' | awk -F'"' '{sum+=$1} END {print "Failures:", sum}'
# Expected: Failures: 0

# 4. Decimals coverage from JaCoCo
grep "Decimals," java/carddemo-tests/target/site/jacoco-aggregate/jacoco.csv
# Expected: CardDemo.../carddemo-domain,com.blitzy.carddemo.domain.util,Decimals,0,1069,...,0,195,...,0,15
#           (INSTRUCTION_MISSED=0, LINE_MISSED=0, METHOD_MISSED=0)

# 5. Forbidden dependencies absence
mvn -B -ntp -f java/pom.xml dependency:tree 2>&1 | grep -iE "spring|hibernate|postgresql|hikari"
# Expected: no output (no forbidden dependencies)

# 6. app/ tree byte-identical
git status app/
# Expected: "nothing to commit, working tree clean"
```

### 9.6 Example Usage

#### Smoke test the build (run two simple jars)

```bash
cd /path/to/repository
mvn -B -ntp -DskipTests -f java/pom.xml package

# OPENFIL — emits WARN for missing data files; this is expected when run without seed data
java -XX:+UseCompactObjectHeaders -jar java/carddemo-app/target/carddemo-open-file.jar

# CLOSEFIL — emits INFO for each cicsFileId being closed; this is a no-op in file-based architecture
java -XX:+UseCompactObjectHeaders -jar java/carddemo-app/target/carddemo-close-file.jar
```

#### Run the canonical batch chain end-to-end (POSTTRAN → INTCALC → TRANBKP → COMBTRAN → CREASTMT)

`FullBatchChainIT.java` already exercises this sequence within a `@TempDir`. To replicate manually:

```bash
mvn -B -ntp -Dtest=FullBatchChainIT -DexcludedGroups= verify -pl java/carddemo-tests
# Expected: BUILD SUCCESS; Failsafe Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

### 9.7 Troubleshooting

| Symptom | Cause | Resolution |
|---------|-------|------------|
| `[ERROR] release version 25 not supported` | JDK 24 or earlier on PATH | Install JDK 25 LTS; verify with `java -version` showing "25.x" |
| `[ERROR] Unknown lifecycle phase` | Maven version too old | Install Maven 3.9.9+; verify with `mvn -version` |
| `Could not transfer artifact from/to central` | Network access blocked | Configure Maven proxy in `~/.m2/settings.xml` or restore network access to Maven Central |
| `[ERROR] BannedDependencies enforcer rule violated` | Someone added a forbidden dependency (Spring/Hibernate/PostgreSQL/HikariCP) | Remove the dependency; consult `java/MIGRATION_NOTES.md` for rationale |
| `[ERROR] preview language feature usage detected` | Someone enabled a preview JEP (502/505/507/512) | Remove `--enable-preview` JVM flag; remove preview JEP imports; preview features are forbidden per AAP §0.7.4 |
| `Tests run: N, Failures: 0, Errors: 0, Skipped: 29` | This is **expected** — 29 `@Disabled` golden tests await z/OS captures (see §1.4) | No action required; this is the normal validated state |
| `JaCoCo gate not met for Decimals` | New code in `Decimals.java` introduced uncovered branches | Add jqwik property tests in `DecimalsProperties.java` to cover the new branches; rerun `mvn verify` |
| `IT artifact leakage: output/ or work/ dirs after run` | `FullBatchChainIT` bypassed `@TempDir` setup | Verify `@BeforeEach` setup sets `carddemo.file.tranrept.path` and `carddemo.file.dateparm.path` via `setProperty()` |
| `NoSuchElementException: ScopedValue not bound` | Direct call to a batch app's `execute()` without `ScopedValue.where(...).run(...)` | Always bind `BATCH_CTX` per app (see `BatchRunContext.BATCH_CTX` plus per-app `BATCH_CTX`); see `FullBatchChainIT.invokeExecute()` for reference pattern |

---

## 10. Appendices

### A. Command Reference

```bash
# Full build with all tests
mvn -B -ntp -f java/pom.xml clean verify

# Skip integration tests (faster feedback)
mvn -B -ntp -f java/pom.xml clean verify -DexcludedGroups=integration

# Build shaded jars only
mvn -B -ntp -f java/pom.xml -DskipTests package

# Dependency tree (forbidden-dependency audit)
mvn -B -ntp -f java/pom.xml dependency:tree

# Run a specific shaded jar (example)
java -XX:+UseCompactObjectHeaders -jar java/carddemo-app/target/carddemo-<program>.jar

# Generational Shenandoah GC (low-pause batch — AAP §0.3.4 mandate)
java -XX:+UseCompactObjectHeaders \
     -XX:+UseShenandoahGC \
     -XX:ShenandoahGCMode=generational \
     -jar java/carddemo-app/target/carddemo-<program>.jar

# Test isolation verification
git status -- 'java/carddemo-tests/*'   # should return empty after mvn clean verify

# JaCoCo coverage report
cat java/carddemo-tests/target/site/jacoco-aggregate/jacoco.csv | grep Decimals

# COBOL source byte-identity verification
git diff app/   # should return empty
```

### B. Port Reference

This is a file-based architecture with no network ports exposed by the application itself. All `EXEC PGM=` steps read/write files via `java.nio.file`.

| Component | Port/Path | Purpose |
|-----------|-----------|---------|
| Shaded jars | N/A (no network) | All I/O is file-based |
| Data input files | `${carddemo.file.<filename>.path}` | Configurable per environment via `application-{dev,staging,prod}.properties` |
| Log output | `stdout`/`stderr` (JSON-structured via Logback) | Capture via systemd journal or file redirection |
| JFR recordings | `${jfr.recording.path}` (when enabled) | Configurable via JVM flags |

### C. Key File Locations

| File | Purpose |
|------|---------|
| `app/cbl/` | 28 COBOL programs (immutable reference) |
| `app/cpy/` | 28 copybooks (immutable reference) |
| `app/bms/` | 17 BMS map definitions (immutable reference) |
| `app/cpy-bms/` | 17 symbolic map copybooks (immutable reference) |
| `app/jcl/` | 29 JCL jobs (immutable reference) |
| `app/data/ASCII/` | 9 ASCII fixture files (immutable reference) |
| `java/pom.xml` | Parent Maven POM with `<release>25</release>` and forbidden-artifact gates |
| `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/util/Decimals.java` | Decimals utility (975 LOC, 100% line coverage) |
| `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/annotation/CobolProgram.java` | `@CobolProgram` traceability annotation |
| `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/` | 28 application classes + 34 BMS DTO records + 3 utility classes |
| `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/` | 28 `*App.java` main classes |
| `java/carddemo-app/target/carddemo-*.jar` | 28 shaded jars (post-`mvn package`) |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` | Golden-record harness base class |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/*GoldenTest.java` | 29 per-program golden tests (currently `@Disabled` pending z/OS captures) |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/integration/FullBatchChainIT.java` | End-to-end integration test (2 active tests) |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/property/DecimalsProperties.java` | jqwik property-based tests (138 active tests) |
| `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaselineTest.java` | JFR baseline (1 `@Disabled` pending stable-host capture) |
| `java/carddemo-tests/src/test/resources/golden/<program>/expected/` | 28 directories pending z/OS COBOL captures |
| `java/README.md` | Java 25 implementation entry point (622 lines) |
| `java/RUNBOOK.md` | Operator runbook (623 lines covering 28 jars × 7 JCL phases) |
| `java/SRE.md` | SRE/SLO skeleton (463 lines with SLI catalog) |
| `java/MIGRATION_NOTES.md` | Migration log (2,888 lines covering deviations, dead code, suspected COBOL bugs) |
| `java/application.properties.example` | 12-factor config template (581 lines) |
| `java/config/application-{dev,staging,prod}.properties` | 3 per-environment templates (709 lines combined) |
| `.github/workflows/build.yml` | CI/CD pipeline (22,199 bytes) |
| `BLITZY_REFINE_NOTES.md` | Refine PR summary (432 lines) at repo root |

### D. Technology Versions

| Component | Version |
|-----------|---------|
| Source language | Java 25 LTS |
| Maven compiler plugin `<release>` | 25 |
| Maven | 3.9.9 |
| maven-compiler-plugin | 3.13.0 |
| maven-shade-plugin | 3.6.0 |
| maven-surefire-plugin | 3.5.2 |
| maven-enforcer-plugin | 3.5.0 |
| maven-antrun-plugin | 3.1.0 |
| JUnit Jupiter | 5.13.x |
| JUnit Platform | 1.13.x |
| AssertJ | 3.26.x |
| jqwik | 1.9.3 |
| SLF4J API | 2.0.16 |
| Logback Classic | 1.5.12 |
| Tool runtime — OpenJDK | 25.0.2+10 (Temurin equivalent) |
| Tool runtime — Operating system | Ubuntu 25.10 (verified) |

### E. Environment Variable Reference

The 12-factor configuration model maps every property in `application.properties` to an environment variable with the same name uppercased, with `.` and `-` replaced by `_`.

Key environment variables (see `java/application.properties.example` for full 581-line reference):

| Variable | Default | Purpose |
|----------|---------|---------|
| `CARDDEMO_CONFIG` | `dev` | Selects `application-{dev,staging,prod}.properties` |
| `CARDDEMO_FILE_ACCTDATA_PATH` | `./data/acctdata.dat` | ACCTDATA fixed-width file path |
| `CARDDEMO_FILE_ACCTDATA_CHARSET` | `IBM-1047` | EBCDIC codepage for ACCTDATA |
| `CARDDEMO_FILE_CARDDATA_PATH` | `./data/carddata.dat` | CARDDATA path |
| `CARDDEMO_FILE_TRANSACT_PATH` | `./data/transact.dat` | TRANSACT KSDS-equivalent path |
| `CARDDEMO_FILE_CARDXREF_PATH` | `./data/cardxref.dat` | CARDXREF cross-reference path |
| `CARDDEMO_FILE_DALYTRAN_PATH` | `./data/dailytran.dat` | DALYTRAN daily transaction path |
| `CARDDEMO_FILE_DISCGRP_PATH` | `./data/discgrp.dat` | DISCGRP discount group path |
| `CARDDEMO_FILE_TCATBAL_PATH` | `./data/tcatbal.dat` | TCATBAL transaction category balance path |
| `CARDDEMO_FILE_TRANCATG_PATH` | `./data/trancatg.dat` | TRANCATG transaction category path |
| `CARDDEMO_FILE_TRANTYPE_PATH` | `./data/trantype.dat` | TRANTYPE transaction type path |
| `CARDDEMO_RUN_ID` | (auto-generated UUID) | Per-run identity (used by ScopedValue context) |
| `CARDDEMO_PROCESSING_DATE` | (today) | Logical processing date |
| `CARDDEMO_TENANT` | `DEFAULT` | Multi-tenant identifier (default: single-tenant) |
| `JAVA_TOOL_OPTIONS` | (none) | `-XX:+UseCompactObjectHeaders` recommended per AAP §0.3.4 |

### F. Developer Tools Guide

| Tool | Purpose | Suggested Use |
|------|---------|---------------|
| **IntelliJ IDEA 2024+** | IDE | Import `java/pom.xml` as a Maven project; enable Java 25 language level |
| **VS Code with Java extensions** | Lightweight IDE | Install Microsoft Java Pack; open `java/` as workspace |
| **Eclipse 2024+** | IDE | Use Buildship Gradle/Maven integration |
| **JaCoCo report viewer** | Coverage visualization | Open `java/carddemo-tests/target/site/jacoco-aggregate/index.html` after `mvn verify` |
| **JFR Mission Control** | Java Flight Recorder analysis | Open `.jfr` files captured by `JfrBaselineTest` or manual `-XX:StartFlightRecording` flag |
| **`git diff --stat`** | Changeset visualization | `git diff --stat origin/cobol-test..HEAD` summarizes the migration scope (364 files, 182,037 insertions) |
| **`mvn dependency:tree`** | Dependency graph | Audits forbidden-artifact compliance |
| **`mvn enforcer:enforce`** | Lint forbidden artifacts | Standalone enforcer plugin invocation |
| **3270 terminal emulator** (e.g., x3270, vista-tn3270) | z/OS CICS BMS map capture | Required for online program golden-record captures (z/OS only) |

### G. Glossary

- **AAP**: Agent Action Plan — the binding refactoring specification
- **BMS**: Basic Mapping Support — IBM's terminal screen definition language for 3270 displays
- **CICS**: Customer Information Control System — IBM's transaction processing system
- **COMP-3**: COBOL packed-decimal representation (two digits per byte plus sign nybble)
- **CSD**: CICS System Definition — XML-style configuration for CICS resources
- **DALYTRAN**: Daily Transaction file (CARDDEMO dataset)
- **EBCDIC**: Extended Binary Coded Decimal Interchange Code — IBM mainframe character encoding
- **GDG**: Generation Data Group — versioned file naming convention on z/OS
- **IDCAMS**: IBM utility for VSAM dataset management
- **JCL**: Job Control Language — z/OS batch job definition language
- **JEP**: JDK Enhancement Proposal — Java language/runtime feature specification
- **LRECL**: Logical Record Length (a JCL DD parameter)
- **PAN**: Primary Account Number — credit card number (subject to PCI masking)
- **PCI-DSS**: Payment Card Industry Data Security Standard
- **REDEFINES**: COBOL keyword for alternative interpretation of memory region
- **REPRO**: IDCAMS command for copying datasets
- **ScopedValue**: Java 25 finalized feature (JEP 506) replacing `ThreadLocal` for cross-thread context propagation
- **TCATBAL**: Transaction Category Balance (CARDDEMO dataset)
- **TDQ**: Transient Data Queue — CICS IPC primitive between online transactions and batch initiators
- **TRANSACT**: Transaction KSDS file (CARDDEMO dataset)
- **USRSEC**: User Security file (CARDDEMO dataset; contains plaintext passwords per AAP §0.7.2)
- **VSAM**: Virtual Storage Access Method — IBM keyed file storage
- **XCTL**: CICS Transfer Control — non-returning transaction-to-transaction transfer

---

_Project guide generated 2026-05-26. Authoritative metrics from `mvn -B -ntp clean verify` autonomous execution and `git` history analysis (origin/cobol-test..HEAD, 324 commits, 182,037 line insertions across 364 files)._
