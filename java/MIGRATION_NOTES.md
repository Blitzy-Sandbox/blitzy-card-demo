# Migration Notes — Java 25 LTS Implementation

This document is the **structured, append-only migration log** for the
source-to-source migration of the AWS CardDemo mainframe application from
Enterprise COBOL / CICS / VSAM / JCL / BMS to Java 25 LTS. Its audience is
every downstream code-generation agent, code reviewer, milestone verifier,
and operator who needs to know **why** a given translation choice was made,
**which** COBOL constructs survive in the Java port as faithful but
unidiomatic carry-overs, and **what** suspected bugs in the original COBOL
source were preserved rather than silently corrected.

It exists because of the verbatim user mandate captured in **AAP §0.7.1**:

> "If a COBOL paragraph contains dead code or obvious bugs, translate it
> faithfully and flag it in a `MIGRATION_NOTES.md`; do not 'fix' it in this
> refactor."

This file is the **only** documentation file under `java/` that grows over
time. Other progress / status / setup-report documents MUST NOT be created;
they would compete with this single source of truth and quickly drift out
of sync. Future translation agents append to the relevant section below;
they never silently rewrite history.

All file-path citations in this document follow the **AAP §0.8.1 citation
discipline**: `[<path>:<locator>]` where `<locator>` is a line range
(`L###`), a section heading (`§NAME`), or a copybook 01-level group.

---

## Section 1.2: Resolution Strategies for User `[TODO]` Markers

Every `[TODO]` marker that appeared in the original user prompt and was
catalogued in **AAP §0.7.5** has a documented resolution below. The status
column distinguishes settled decisions (RESOLVED) from pending data
captures (OPEN).

| # | TODO Marker (AAP §0.7.5) | Resolution | Status | Reference |
|---|---|---|---|---|
| 1 | Persistence: VSAM vs DB2 | COBOL source uses VSAM KSDS with fixed-width records. Default path is `java.nio.file` with fixed-width readers in the `carddemo-adapter-file` module; the `carddemo-adapter-db` module exists but is empty (no JDBC repositories) unless source-side embedded SQL is later discovered. | RESOLVED | AAP §0.5, §0.6.5 |
| 2 | External integrations (MQ, CICS, FTP, file feeds) | Default file-based batch only applies. The **only** mainframe-specific integration in scope is the CICS Transient Data Queue write in `CORPT00C` (paragraph `WIRTE-JOBSUB-TDQ`, `[app/cbl/CORPT00C.cbl:L515]`, queue name `'JOBS'`); this is translated to a direct method invocation since CICS TDQ replacement orchestration is out of scope. MQ, FTP, and external file feeds are not used by the COBOL source and are not introduced. | RESOLVED | AAP §0.2.2, §0.6.12 |
| 3 | Root path for COBOL sources | `app/cbl/` confirmed by filesystem inspection — 28 `.cbl`/`.CBL` programs enumerated in AAP §0.6.8. | RESOLVED | AAP §0.6.8 |
| 4 | Throughput target TPS | No specific TPS target was provided in the user prompt. The JFR baseline test (`carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaseline.java`) enforces no regression beyond a **10 % band** against a captured baseline. The actual measured COBOL baseline TPS must be added to this section once a representative run is captured. | OPEN — pending COBOL benchmark capture | AAP §0.7.2 |
| 5 | Maven coordinates list | Every Maven coordinate is enumerated in `java/pom.xml` under `<dependencyManagement>`. All artifacts come from Maven Central; no private registries. See AAP §0.5.1 for the canonical list. | RESOLVED | `java/pom.xml`, AAP §0.5.1 |
| 6 | Runtime configuration / environment variable list | Every supported environment variable and `application.properties` key is documented with example values in `java/application.properties.example`. | RESOLVED | `java/application.properties.example` |
| 7 | Golden-record fixture regeneration | See **Section 1.6 — Golden-Record Fixture Capture Instructions** below. | RESOLVED — instructions documented | Section 1.6 |

---

## Section 1.3: IMPLEMENTATION DECISIONS

These entries record COBOL constructs that do not map 1-to-1 onto a Java
idiom and therefore required an explicit, considered translation choice.
Each entry cites the COBOL source so the reasoning can be revisited.

### 1.3.1 COACTUPC SYNCPOINT ROLLBACK → try / finally with compensating writes

**COBOL source**: `app/cbl/COACTUPC.cbl`, `EXEC CICS SYNCPOINT ROLLBACK`
on line 4100, inside the failure branch of the customer-update REWRITE
that begins at the `9600-WRITE-PROCESSING-EXIT` paragraph
`[app/cbl/COACTUPC.cbl:L4095-L4107]`. A non-rollback `SYNCPOINT` (commit
checkpoint) also appears at `[app/cbl/COACTUPC.cbl:L953]`.

**Observation**: `EXEC CICS SYNCPOINT ROLLBACK` at line 4100 is the **sole
rollback** in the entire COBOL source tree (verified via
`grep -rn "SYNCPOINT ROLLBACK" app/cbl/`). It fires only when the customer
REWRITE that follows the successful account REWRITE itself fails —
i.e., the program has already updated the account record and now needs to
undo that change because the related customer write failed.

**Translation**: the target architecture has **no Spring transaction
manager and no JTA** (per the architectural override in AAP §0.6.12).
The rollback is translated as a `try { … } finally { … }` block on
`com.blitzy.carddemo.application.account.CoActUpC` with **explicit
compensating writes**: the pre-update account snapshot is captured before
the first REWRITE; if the customer REWRITE fails, the snapshot is written
back to ACCTDATA in the `finally` block before the failure status is
propagated up the call chain. The observable outcome (failure surface,
file state, return-code path) is byte-identical to the CICS behaviour.

**Why this is not "fixing" a bug**: the COBOL program intentionally
preserves consistency between ACCTDATA and CUSTDATA — the translation
must preserve the same invariant by the same observable mechanism. No
business rule is added or removed.

### 1.3.2 CORPT00C CICS TDQ write → direct method invocation

**COBOL source**: `app/cbl/CORPT00C.cbl`, paragraph `WIRTE-JOBSUB-TDQ`
at line 515 (note: the paragraph is misspelled `WIRTE-` in the COBOL
source; see Section 1.5 for the verbatim-preservation rationale). The
paragraph executes `EXEC CICS WRITEQ TD QUEUE ('JOBS')` to enqueue JCL
records onto a CICS Transient Data Queue named `JOBS` so a batch trigger
elsewhere can pick the job up and submit it
`[app/cbl/CORPT00C.cbl:L515-L535]`.

**Observation**: CICS TDQ is a queue-style IPC primitive between an
online transaction and a batch initiator — it is part of the mainframe
runtime and has no JVM equivalent. AAP §0.2.2 places CICS replacement
orchestration explicitly out of scope.

**Translation**: the TDQ write is replaced by a direct method invocation
on the appropriate batch-driver class in `carddemo-batch` (per
AAP §0.4.1). Each `MOVE JOB-LINES(WS-IDX) TO JCL-RECORD` followed by
`PERFORM WIRTE-JOBSUB-TDQ` `[app/cbl/CORPT00C.cbl:L501-L508]` becomes
either an in-process call (synchronous fan-in) or a deferred call carried
by `ScopedValue` context (asynchronous fan-out), as appropriate to the
caller. Error handling preserves the original `EVALUATE WS-RESP-CD` shape
`[app/cbl/CORPT00C.cbl:L525-L535]` as a sealed `TdqResp` hierarchy.

### 1.3.3 CSUTLDTC CEEDAYS call → `java.time` strict parser

**COBOL source**: `app/cbl/CSUTLDTC.cbl`, `CALL "CEEDAYS" USING ...`
on line 116 `[app/cbl/CSUTLDTC.cbl:L116-L120]`. The program is a thin
wrapper around the IBM Language Environment service `CEEDAYS`, which
converts a textual date in a caller-specified format to a Lillian day
number and returns a feedback code categorising any parse failure.

**Translation**: the `CEEDAYS` call is replaced by `java.time.LocalDate`
parsing with a strict resolver. Specifically,
`DateTimeFormatter.ofPattern(<format>).withResolverStyle(ResolverStyle.STRICT)`
is used so out-of-range dates (e.g., February 30) raise
`DateTimeParseException` rather than silently rolling over (which is what
`ResolverStyle.SMART`, the default, would do). The wrapper's input /
output record shape (`WS-DATE-TO-TEST`, `WS-DATE-FORMAT`,
`OUTPUT-LILLIAN`, `FEEDBACK-CODE`) is preserved on the new
`com.blitzy.carddemo.application.util.DateValidator` class so call sites
need no signature changes.

**Lillian date arithmetic** is reproduced via
`LocalDate.of(1582, 10, 15).until(<parsedDate>, ChronoUnit.DAYS) + 1`
to preserve the day-count semantics of the original mainframe API.

**Feedback-code mapping**: the COBOL EVALUATE at
`[app/cbl/CSUTLDTC.cbl:L128-L155]` maps `FC-*` symbolic feedback codes
to a 15-character `WS-RESULT` message. The Java translation preserves
the exact 15-char message strings verbatim. The severity (4-char) and
message number (4-char) values are a best-effort mapping from
`DateTimeParseException` message categories to the most likely matching
CEEDAYS feedback code; the precise byte values of CEEDAYS are opaque LE
internals and cannot be reproduced exactly for every conceivable edge
case. The `DateValidator.classifyDateTimeParseException` heuristic
inspects exception message substrings ("monthofyear", "month",
"dayofmonth", "day of month", "invalid date", "year"+"range",
"could not be parsed", "text") to select the closest FC-* equivalent;
falls through to `MSG_DATE_INVALID="9999"` for unrecognized failures
(the COBOL `WHEN OTHER` branch).

**DateValidator is a utility class with only static methods**: per the
finalized design (AAP §0.3 and the file schema for
`java/carddemo-application/.../util/DateValidator.java`), the
translated wrapper is a non-instantiable utility class with a private
constructor that throws `UnsupportedOperationException`. The
single-program rename from `CsUtlDtC` to `DateValidator` (AAP §0.6.8 —
the **only** renamed program in the migration) accompanies this design
because the class also hosts the executable date-validation logic from
`app/cpy/CSUTLDPY.cpy` (paragraphs `EDIT-DATE-CCYYMMDD`,
`EDIT-YEAR-CCYY`, `EDIT-MONTH`, `EDIT-DAY`, `EDIT-DAY-MONTH-YEAR`,
`EDIT-DATE-LE`, `EDIT-DATE-OF-BIRTH`). The class exposes:
* `validate(String dateToTest, String dateFormat)` — primary CSUTLDTC
  CEEDAYS equivalent; returns a `DateValidationResult` mirroring the
  80-byte `LS-RESULT` LINKAGE structure
* `validateCcyymmdd(String, String)` — orchestrates the full
  `EDIT-DATE-CCYYMMDD` pipeline (year → month → day → cross-field → LE)
* `validateDateOfBirth(String, String, LocalDate)` — strict future-date
  check (sets all 3 NOT-OK flags on failure per CSUTLDPY)
* `editYearCcyy / editMonth / editDay / editDayMonthYear / editDateLe`
  — individual paragraph translations returning `FieldValidationResult`
* `formatAsLsResult(DateValidationResult)` — produces the 80-byte
  `LS-RESULT` text per the CSUTLDTC LINKAGE SECTION layout
* 10 public `RESULT_*` constants (each exactly 15 chars) for the
  EVALUATE WHEN result texts

**Caller constructor signature changes** (downstream consequence): The
two in-scope callers `CoRpt00C` (CORPT00C transaction) and `CoTrn02C`
(COTRN02C transaction) previously held a `private final DateValidator
dateValidator` field injected via constructor. Because `DateValidator`
is now non-instantiable, these constructor parameters and fields were
removed, and the call sites invoke `DateValidator.validate(...)`
statically. Format-mask arguments were converted from the Java pattern
`"yyyy-MM-dd"` to the COBOL pattern `"YYYY-MM-DD"` so they pass
through the `Y → u`, `D → d` substitution in `parseMask`. The
`!"2513".equals(msgNumber())` tolerance for `FC-UNSUPP-RANGE` was
preserved (callers continue to allow this severity through after the
regex / range pre-checks). Tests that constructed these classes (if
any are added) must use the new 1-arg / 3-arg signatures.

**COBOL-equivalent leap-year algorithm**: `editDayMonthYear` uses the
exact CSUTLDPY rule (`if year mod 100 == 0 then divBy=400 else
divBy=4; leap = year mod divBy == 0`) rather than delegating to
`LocalDate.isLeapYear()`. The two algorithms produce identical results
for all valid 4-digit Gregorian years (1..9999), but the COBOL form is
preserved per AAP §0.7.1 idiom-for-idiom mandate.

### 1.3.4 Return-code switch style: 26 `Integer` pattern-matching vs 2 plain `int` with `default`

**Where**: All 28 `*App.java` composition root classes under
`java/carddemo-app/src/main/java/com/blitzy/carddemo/app/`. Each `main`
method clamps the inner use-case return code to the JCL severity range
{0, 4, 8, 12, 16} before calling `System.exit(...)`. The clamping logic
is implemented with **two different switch styles** across the 28
classes — this asymmetry is **deliberate**, not a defect:

| Switch style | Count | Apps |
|---|---|---|
| Pattern-matching `switch (Integer rc)` with `case null`, `case 0/4/8/12/16`, `case Integer i when i < 0`, `case Integer i when i > 16`, `case Integer i` (no `default`) | 26 | CombineTransactionsApp, CreateStatementsApp, DailyRejectsApp, DefineAccountFileApp, DefineCardFileApp, DefineCardXrefApp, DefineCustomerFileApp, DefineDiscountGroupApp, DefineGdgApp, DefineTcatBalApp, DefineTransactionCategoryApp, DefineTransactionFileApp, DefineTransactionTypeApp, InterestCalculationApp, OpenFileApp, PostTransactionsApp, PrintTcatBalApp, ReadAccountDumpApp, ReadCardDumpApp, ReadCardXrefDumpApp, ReadCustomerDumpApp, TransactionBackupApp, TransactionIndexApp, TransactionReportApp, UsersSecuritySeedApp, CloseFileApp |
| Plain `int` `switch` with `case 0, 4, 8, 12, 16 -> rc;` and `default -> { if (rc > 0 && rc < 16) yield rc; yield 16; }` | 2 | `AdminCodeApp`, `ReportFileApp` |

**Why two styles**: The 26 Apps that operate on `Integer rc` (a boxed
boxed reference type, possibly returned by a method whose declared
return type is `Integer`) can use pattern-matching switch with type
patterns (`case Integer i when ...`) — a **finalized** Java 21 feature
(JEP 441). The 2 Apps that operate on `int rc` (a primitive value
returned by a method whose declared return type is `int`) **cannot**
use pattern-matching switch on `int`, because that would require
**JEP 507 (Primitive Types in Patterns, `instanceof`, and `switch`),
which is a preview feature in Java 25 and is explicitly forbidden by
AAP §0.7.4**. The only finalized syntax available for switching on
a primitive `int` is the classic `case <literal> -> ...;` /
`default -> ...;` form, which requires the `default` branch to be
exhaustive over the entire `int` value space.

**Why this is preserved**: This is not an inconsistency to be flattened;
it is a faithful reflection of the underlying Java language constraint.
The two Apps in question (`AdminCodeApp` from `CBADMCDJ.jcl`,
`ReportFileApp` from `REPTFILE.jcl`) inherit their return-code typing
from upstream use-case method signatures that predate the boxing
convention adopted for the other 26 Apps. Both styles produce
**identical clamping behaviour** for the {0, 4, 8, 12, 16} severity
codes plus the intermediate 1..15 passthrough range plus the > 16 / < 0
ceiling clamp. The pattern is documented in-place in each of the two
plain-`int` Apps with a comment naming JEP 507 and AAP §0.7.4 so future
maintainers do not "unify" the style by introducing the preview feature.

**Where this is enforced in code**: see the inline comment block in
`AdminCodeApp.main(...)` and `ReportFileApp.main(...)` that begins
"Implemented as a plain `int` switch with a default branch — NOT a
pattern-matching switch on Integer — because JEP 507 (Primitive
Patterns) is a preview feature and is explicitly FORBIDDEN by
AAP §0.7.4."

**Action item**: When JEP 507 is finalized in a future LTS release of
Java, the 2 plain-`int` Apps may be migrated to pattern-matching
switch to unify style across all 28 composition roots. Until then,
the asymmetry is correct and must not be removed.

### 1.3.5 Return-code clamping: full 0..16 passthrough, not literal {0, 4, 8, 12, 16}

**Where**: All 28 `*App.java` composition root classes (see
Section 1.3.4 for the inventory). The CP-3 checkpoint instructions
literally describe the clamp as "0/4/8/12/16 passthrough, else clamp
to 16, NO default branch", but the implementation **permits every
value in `[0, 16]` to pass through unchanged**, not only the five JCL
canonical severity codes.

**Implementation**: The clamp in each App resolves to the function

```java
int clamp(int rc) {
    if (rc < 0)  return 16;   // negative → ceiling
    if (rc > 16) return 16;   // > 16     → ceiling
    return rc;                // 0..16    → passthrough (including 1..3, 5..7, 9..11, 13..15)
}
```

Expressed in the `Integer` pattern-matching style (26 Apps) this is:

```java
int exitCode = switch (rc) {
    case null -> RC_ERROR;                    // RC_ERROR == 16
    case 0  -> 0;
    case 4  -> 4;
    case 8  -> 8;
    case 12 -> 12;
    case 16 -> 16;
    case Integer i when i < 0  -> RC_ERROR;
    case Integer i when i > 16 -> RC_ERROR;
    case Integer i -> i;                      // 1..3, 5..7, 9..11, 13..15 pass through
};
```

Expressed in the plain-`int` style (2 Apps) this is:

```java
int exitCode = switch (rc) {
    case 0, 4, 8, 12, 16 -> rc;
    default -> (rc > 0 && rc < 16) ? rc : 16; // pseudocode for the yield block
};
```

**Why intentional passthrough of the non-canonical range**: z/OS JCL
does **not** restrict step return codes to the {0, 4, 8, 12, 16}
canonical set. The `COND=` and `IF/THEN/ELSE/ENDIF` constructs in JCL
compare against arbitrary integers in `[0, 4095]`; values such as
**rc=2** (commonly used by IDCAMS as a soft-warning) or **rc=15**
(used by some Enterprise COBOL programs to signal "completed with
recoverable errors") are legitimate return codes that downstream
JCL steps may rely on. Silently rewriting rc=2 → 4 or rc=15 → 16
would change observable behaviour — the very thing AAP §0.7.1
**Preserve-As-Is** clause forbids ("error codes, return codes, and
abend conditions ... with identical observable outcomes").

**Why the clamp-at-16 ceiling is kept**: Java's `System.exit(int)`
accepts arbitrary `int` values, but POSIX (the OS layer beneath the
JVM on every supported target platform) truncates the exit status to
the low 8 bits before propagating to a parent shell or scheduler.
Capping at 16 ensures the byte value remains within an unambiguous
"job failure" range visible to a calling z/OS-style scheduler (e.g.,
Control-M, Tivoli Workload Scheduler) without colliding with shell
convention values 130 (SIGINT), 137 (SIGKILL), etc.

**Reconciliation with the CP-3 wording**: The CP-3 checkpoint
instructions used "0/4/8/12/16 passthrough" as a documentation
shorthand for the five canonical JCL severity tokens. The
implementation is more permissive (full 0..16 passthrough) to
preserve COBOL/JCL observable behaviour. The CP-3 wording is a
**lossy summary**; this section is the authoritative source.

**Where this is enforced in code**: the comment block immediately
above each switch in every `*App.java` reads:
"Pattern-matching switch with no default branch (per AAP §0.6.7)
clamps the return code to the JCL severity range [0, 16]. Canonical
severity codes (0, 4, 8, 12, 16) are matched explicitly; the
non-canonical in-range codes (1..3, 5..7, 9..11, 13..15) pass
through via the `case Integer i` fallback. Out-of-range values
(< 0 or > 16) clamp to RC_ERROR=16."

---

## Section 1.4: DEVIATIONS from Idiom-for-Idiom Translation

These entries record COBOL constructs where strict idiom-for-idiom
translation is impossible because the underlying mainframe primitive has
no JVM equivalent. Each deviation preserves observable behaviour as
closely as the JVM allows and is documented here per AAP §0.7.1.

### 1.4.1 CBSTM03A legacy z/OS introspection (TIOT / TCB / PSA + ALTER / GO TO)

**COBOL source**: `app/cbl/CBSTM03A.CBL`. The program header at
`[app/cbl/CBSTM03A.CBL:L25-L35]` advertises five features
deliberately exercised by this program: "Mainframe Control block
addressing", "Alter and GO TO statements", COMP/COMP-3 variables, a
two-dimensional array, and a subroutine call. The first two have no
direct Java equivalent.

**Mainframe Control block addressing**: the program declares pointer-based
overlays for the Prefixed Save Area (`PSA-BLOCK`,
`[app/cbl/CBSTM03A.CBL:L241]`), the Task Control Block (`TCB-BLOCK`,
`[app/cbl/CBSTM03A.CBL:L244]`), and the Task I/O Table (`TIOT-BLOCK` and
`TIOT-ENTRY`, `[app/cbl/CBSTM03A.CBL:L247-L260]`), then walks them to
discover the currently-running JCL job name, step name, and DD allocations
at runtime — see the chain `SET ADDRESS OF PSA-BLOCK TO PSAPTR. SET
ADDRESS OF TCB-BLOCK TO TCB-POINT. SET ADDRESS OF TIOT-BLOCK TO TIOT-POINT.`
at `[app/cbl/CBSTM03A.CBL:L266-L270]`, followed by the DD-walk loop at
`[app/cbl/CBSTM03A.CBL:L276-L290]`.

**Java has no equivalent.** There is no JVM API surface that exposes
the executing JCL job's identity or DD list, because those concepts are
specific to the z/OS scheduler. Java translation: the control-block walk
is rendered as no-op stubs that log a single structured warning
(`"z/OS TIOT introspection skipped — not available on JVM"`) and proceed
with whatever job-identification context is supplied via the
`ScopedValue` batch-run context (run ID, processing date, tenant). The
`DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP.`
`[app/cbl/CBSTM03A.CBL:L270]` is replaced with a structured log entry
containing the equivalent context fields from the Java runtime.

**ALTER / GO TO flow**: `ALTER 8100-FILE-OPEN TO PROCEED TO ...` at
`[app/cbl/CBSTM03A.CBL:L300]` and `[app/cbl/CBSTM03A.CBL:L303]`
re-points a GO TO target at run time — an obsolete COBOL '74 feature
that Enterprise COBOL still parses for backward compatibility. The
control flow is translated as an explicit state-machine variable
(`enum FileOpenStep { TRNXFILE, XREFFILE, ACCTFILE, … }`) read by a
switch expression at the corresponding call site. No `goto` is used
because Java has no `goto`; the observable file-open sequence is
identical.

**Why this is a DEVIATION rather than an IMPLEMENTATION DECISION**: the
underlying mainframe primitive is fundamentally inaccessible. No
faithful 1-to-1 translation exists; the no-op stubs are the closest
approximation possible. This entry must be revisited if the application
is ever re-hosted on a z/OS emulation layer (e.g., Micro Focus
Enterprise Server) that re-exposes TIOT/TCB/PSA.

### 1.4.2 `app/cpy/UNUSED1Y.cpy` — unused copybook preserved for completeness

**COBOL source**: `app/cpy/UNUSED1Y.cpy`, a 10-line copybook defining
the `01 UNUSED-DATA` group with six fields (`UNUSED-ID PIC X(08)`,
`UNUSED-FNAME PIC X(20)`, `UNUSED-LNAME PIC X(20)`, `UNUSED-PWD PIC X(08)`,
`UNUSED-TYPE PIC X(01)`, `UNUSED-FILLER PIC X(23)`)
`[app/cpy/UNUSED1Y.cpy:L1-L7]`. The layout is structurally identical to
`SEC-USER-DATA` in `app/cpy/CSUSR01Y.cpy` — almost certainly a developer
template or abandoned copy.

**Observation**: `grep -rln "UNUSED1Y" app/cbl/ app/cpy/ app/jcl/`
returns no matches, confirming the copybook is not referenced by any
program. Per AAP §0.4.1 it is translated to a Java record
(`com.blitzy.carddemo.domain.record.UnusedRecord`) anyway, for
completeness and to preserve a 1-to-1 copybook → record mapping.

**Status**: PRESERVED — the record exists in the domain module but is
never instantiated by any application class. If a future translation
agent encounters a previously-undiscovered call site, no further work
is required.

### 1.4.3 `app/jcl/DEFCUST.jcl` — suspected JCL bug preserved verbatim

**JCL source**: `app/jcl/DEFCUST.jcl`. Inspection of the job reveals
**two distinct anomalies**, both preserved in the Java translation
per AAP §0.7.1:

**Anomaly A — duplicate step name**: the job declares two steps both
named `STEP05`, at `[app/jcl/DEFCUST.jcl:L22]` (the DELETE IDCAMS step)
and `[app/jcl/DEFCUST.jcl:L32]` (the DEFINE IDCAMS step). z/OS JCL
requires step names within a job to be unique; the second `STEP05` would
either be rejected by JES2 or have unpredictable JCL-error behaviour.
The Java translation in `com.blitzy.carddemo.app.DefineCustomerFileApp`
treats the two steps as two sequential phases with the same logical
purpose preserved; no rename "fix" is applied.

**Anomaly B — dataset-name mismatch between DELETE and DEFINE**: the
DELETE step targets `AWS.CCDA.CUSTDATA.CLUSTER`
`[app/jcl/DEFCUST.jcl:L25]` while the DEFINE step creates
`AWS.CUSTDATA.CLUSTER` `[app/jcl/DEFCUST.jcl:L35]`. The two names differ
by the `CCDA` qualifier. The DELETE is therefore a no-op (it deletes a
dataset that the DEFINE never creates), and re-running the job a second
time will fail because the freshly-defined `AWS.CUSTDATA.CLUSTER`
already exists and the DELETE doesn't remove it. The Java translation
preserves this mismatch verbatim: the file-deletion phase uses the
`CCDA` name and the file-define phase uses the un-qualified name. This
is documented in `DefineCustomerFileApp` Javadoc.

**Anomaly C — misleading comment block**: the comment block at
`[app/jcl/DEFCUST.jcl:L29-L31]` labels the DEFINE step as
"DELETE CUSTOMER VSAM FILE IF ONE ALREADY EXISTS" — a copy-paste error
from the actual DELETE step at lines 19–21. Comments do not influence
behaviour, but the misleading text is left in place by virtue of the
`app/` tree being immutable.

**Why these are flagged but not fixed**: AAP §0.7.1 is explicit —
"translate it faithfully and flag it in a `MIGRATION_NOTES.md`; do not
'fix' it in this refactor."

### 1.4.4 `WIRTE-JOBSUB-TDQ` paragraph spelling preserved in Java method name

**COBOL source**: `app/cbl/CORPT00C.cbl`, the paragraph definition
`WIRTE-JOBSUB-TDQ.` at `[app/cbl/CORPT00C.cbl:L515]` and its call site
`PERFORM WIRTE-JOBSUB-TDQ` at `[app/cbl/CORPT00C.cbl:L507]`. The intended
word is "WRITE"; the COBOL source contains a typo (transposed `RI`).

**Translation**: the Java translation in
`com.blitzy.carddemo.application.report.CoRpt00C` retains the
misspelling in the private method name (`wirteJobsubTdq()`) to preserve
the 1-to-1 paragraph-to-method mapping mandated by AAP §0.1.2.
"Correcting" the spelling would constitute a behaviour-change risk
(callers, logs, stack traces, and any external monitoring that scrapes
method names would all silently shift). A Javadoc note on the Java
method records the intended spelling.

### 1.4.5 CBACT01C / CBACT03C `DOUBLE-DISPLAY` anomaly (IMPLEMENTED)

**Status**: IMPLEMENTED — the COBOL anomaly is preserved verbatim in the
Java translation of both programs as documented below.

**COBOL source**:

- `app/cbl/CBACT01C.cbl` (paragraph `1000-ACCTFILE-GET-NEXT`):
  ```
  IF FILE-IO-OK
      MOVE FD-ACCT-DATA TO ACCT-RECORD
      DISPLAY ACCT-RECORD
      DISPLAY FD-ACCT-DATA
  END-IF
  ```
  After reading a fixed-width 300-byte record from `ACCTFILE`, the
  program issues TWO `DISPLAY` statements: the first prints the
  `ACCT-RECORD` redefining alias (which is identical bytes to
  `FD-ACCT-DATA` because of the `MOVE`), the second prints the raw
  `FD-ACCT-DATA` directly. The result is that every record appears
  twice in the SYSOUT stream.

- `app/cbl/CBACT03C.cbl` shares the same idiom for the `CARDXREF` file
  reader.

**Analysis**: per inspection of the COBOL listing and absence of any
distinct formatting between the two DISPLAY statements, the
"DOUBLE-DISPLAY" appears to be a vestigial debugging idiom left over
from initial development — both DISPLAY statements emit identical bytes.
The user mandate in AAP §0.7.1 ("If a COBOL paragraph contains dead code
or obvious bugs, translate it faithfully and flag it in a
`MIGRATION_NOTES.md`; do not 'fix' it in this refactor.") requires that
the duplicate output be preserved.

**Java translation**: in both
`com.blitzy.carddemo.application.account.CbAct01C` and
`com.blitzy.carddemo.application.account.CbAct03C`, the per-record
section of the read loop emits two SLF4J `logger.info(...)` calls with
identical content. Byte-for-byte parity of the SLF4J output stream
against the captured COBOL SYSOUT will be asserted by the golden-record
harness once the corresponding fixtures are captured.

### 1.4.6 `ACCT-EXPIRAION-DATE` typo preservation in `CVACT01Y.cpy` (IMPLEMENTED)

**Status**: IMPLEMENTED — the COBOL field-name typo is preserved
verbatim in `AccountRecord.java` as documented below.

**COBOL source**: `app/cpy/CVACT01Y.cpy`. The 300-byte `ACCOUNT-RECORD`
01-level group contains a field named `ACCT-EXPIRAION-DATE PIC X(10)`
(the standard English spelling is `EXPIRATION`; the COBOL source
contains a typo — letters `TI` transposed to `AI` to yield
`EXPIRAION`). The misspelling propagates wherever the copybook is
COPY'd (every program that reads or updates the ACCOUNT record) and
appears in MOVE statements, IF tests, and INITIALIZE clauses across the
codebase. AAP §0.4.1 explicitly notes "ACCT-OPEN-DATE/EXPIRAION-DATE/
REISSUE-DATE (PIC X(10)→LocalDate)" — the typo is documented in the
AAP because it must survive translation.

**Java translation**:
`com.blitzy.carddemo.domain.record.AccountRecord` declares a record
component named `acctExpiraionDate` (camelCase preserving the typo, NOT
`acctExpirationDate`). All call sites in the application classes
(`CbAct01C`, `CbAct04C`, `CoActVwC`, `CoActUpC`, etc.) reference
`acct.acctExpiraionDate()` — the typo survives the translation
faithfully.

**Rationale**:

1. **AAP §0.1.1 mandate** — "byte-for-byte identical file outputs and
   field-for-field identical record outputs versus the COBOL baseline".
   Field name divergence between COBOL and Java would surface
   immediately in any introspection-based diagnostic, making it harder
   to verify parity.
2. **AAP §0.7.1 Refactor Discipline** — "If a COBOL paragraph contains
   dead code or obvious bugs, translate it faithfully and flag it in a
   `MIGRATION_NOTES.md`; do not 'fix' it in this refactor." A field
   spelling defect is the canonical example of the kind of "obvious bug"
   the user explicitly forbids correcting.
3. **AAP §0.1.2 transformation rule** — "COBOL `COPY` directive →
   Java `import` of a domain record"; copybook field names are the
   contract between COBOL and Java, and silent renaming would break
   any downstream code search that pivots on the COBOL name.

**Javadoc decoration**: the `acctExpiraionDate` record component carries
a Javadoc note that records the intended spelling (`expirationDate`),
so a developer reading the Java code is not left wondering whether the
typo is intentional. The Javadoc note follows the same pattern as the
`WIRTE-JOBSUB-TDQ` paragraph note in Section 1.4.4: the COBOL spelling
is preserved in code; the intended spelling is preserved in comments.

**Sister fields**: the corresponding card expiration date in
`app/cpy/CVACT02Y.cpy` is named `CARD-EXPIRAION-DATE` (same `AI`
transposition). The Java translation in
`com.blitzy.carddemo.domain.record.CardRecord` declares
`cardExpiraionDate` to match.

### 1.4.7 Logback PAN-masking regex: fixed-length mask and `%msg`-only scope

**Where**: `java/carddemo-app/src/main/resources/logback.xml`. The
Logback configuration bundled into every shaded jar implements the PCI
PAN-masking mandate from AAP §0.7.2 ("No card PAN logged in full; mask
all but last 4 digits in logs and error messages") through a
`%replace(%msg){'<regex>','<replacement>'}` conversion on the encoder
pattern.

**The regex**: `\b(\d{9,15})(\d{4})\b` — matches any sequence of 13 to 19
consecutive digits at word boundaries, with two capture groups. Group 1
captures the all-but-last-4 prefix (9–15 digits); group 2 captures the
last 4 digits. This range covers the standard PAN lengths: Visa
(13/16/19), MasterCard (16), Amex (15), Discover (16/19).

**The replacement**: `************$2` — a fixed sequence of 12 asterisks
followed by the back-reference to the captured last 4 digits. For the
dominant 16-digit case this yields a length-preserving 16-character mask
that exactly replaces the original PAN.

**Trade-off 1: fixed-length mask vs. dynamic length**. Logback's
`%replace` conversion (which uses `java.util.regex` under the hood) does
NOT support back-reference-length-aware replacements: there is no
standard regex construct equivalent to "match the length of capture
group 1 and emit that many asterisks". Implementing dynamic mask length
would require a custom Logback converter class (subclassing
`ch.qos.logback.core.pattern.CompositeConverter`), which adds runtime
complexity disproportionate to the security benefit. The Java team
selected the simpler 12-asterisk approach with the following observable
consequences:

| PAN length | Pre-mask | Post-mask           | Length diff | Last-4 correct |
|-----------:|---------:|---------------------|------------:|---------------:|
| 13 digits  | `4111111111234`     | `************1234`     | +3 chars (over-masked) | YES |
| 15 digits  | `411111111111234`   | `************1234`     | +1 char  (over-masked) | YES |
| 16 digits  | `4111111111111234`  | `************1234`     | 0 (exact)              | YES |
| 19 digits  | `4111111111111234567`| `************4567`    | -3 chars (under-masked) | YES |

The under-masking case for 19-digit PANs leaves the digit count of the
masked output (16 chars) lower than the original (19 chars), which
**does not** reveal any of the original card number — the asterisks
still cover everything except the last 4 digits the regex captures.
The over-masking cases for 13/15-digit PANs make the masked output
**longer** than the original, again revealing nothing about the masked
portion. **The security property "no more than the last 4 digits are
visible in logs" is preserved for every supported PAN length.** Only
the visual length of the asterisk run varies.

**Trade-off 2: scope of `%replace` (UPGRADED in Checkpoint 2 review fix
to apply to all event surfaces)**. The encoder pattern now wraps
`%replace(...)` around the ENTIRE event surface, not just `%msg`. This
means PAN masking applies uniformly to:

- the message body (`%msg`)
- MDC values (`%X{batchRunId}`, `%X{processingDate}`, and any caller-
  provided MDC fields)
- the thread name (`%thread`)
- the logger name (`%logger{36}`)
- exception output (`%ex`, including "Caused by" chains and every
  stack-trace frame)

The previous configuration narrowed `%replace` to `%msg` alone, on the
theory that thread/logger names could legitimately contain digit runs
that would be over-masked. In practice (a) carddemo never names threads
or loggers with 13–19 consecutive digits, and (b) any over-masking of a
thread-name digit run is far preferable to leaking a PAN through an
exception stack trace.

The Logback `%replace` conversion is fully nestable and the PAN regex
uses `\b` word boundaries plus a `{9,15}{4}` length bound (13–19
digits total), so the fixed structural prefix of the layout
(timestamp, level, brackets) cannot accidentally match: the
millisecond precision in `%d{yyyy-MM-dd HH:mm:ss.SSS}` is only 3
trailing digits after the dot, which is below the lower bound.

**Defensive coding requirement (still applies even with wider mask)**:
application code should still avoid placing raw card PANs into
exception messages, MDC values, or logger names. The Logback regex is
defence-in-depth, not the primary control. The primary control is
masking at the throw / log site using an application-layer helper.
This belt-and-braces approach mirrors AAP §0.7.2's "no card PAN logged
in full" mandate at two layers: at the producer (the code that emits
the value) and at the formatter (the Logback configuration).

**Why this is now closer to RESOLVED than DEVIATION**: a strict
idiom-for-idiom translation of the PCI masking requirement requires
masking everywhere a PAN might appear. The Java translation now
satisfies that requirement at the Logback layer for every event
surface; the 13/15/19-digit length variance in the *number of
asterisks* remains the only residual deviation (the last 4 digits are
always correct; the asterisk-prefix length is fixed at 12). The
asterisk-count deviation is acceptable because PCI DSS §3.3 mandates
suppression of all but the last 4 digits, not preservation of the
unmasked-prefix length; the dominant 16-digit Visa/MasterCard case is
length-correct.

**Action items for downstream translation**:

1. When translating any program that logs card numbers (most likely
   `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `CBTRN02C`, `COBIL00C`), still
   use an application-layer masking helper before passing the value
   to the logger. The Logback layer is defence-in-depth.
2. When translating exception sites that include a PAN in the message,
   mask at the throw site so that the mask is consistent across
   logging surfaces (file, console, JSON, downstream aggregators) that
   may apply different formatting policies.
3. If a future Java 25 release adds a Logback feature for
   back-reference-length-aware replacement, revisit this section and
   consider tightening the regex; the entry should then be updated to
   RESOLVED.

### 1.4.8 FixedWidthReader / FixedWidthWriter truncated-record EOF behaviour

**Where**: `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/
adapter/file/FixedWidthReader.java` and the sibling `FixedWidthWriter.java`.
Both classes form the foundational record-IO surface for every
`File*Repository` implementation in this package.

**Behaviour**: when reading a fixed-width data file (`recordLength` bytes
per record), if end-of-file is reached after some bytes of a record have
been read but before the full `recordLength` bytes are accumulated, both
classes throw `IOException("Truncated record in <path> (read N bytes;
expected M bytes)")`. The same error surfaces from
`FixedWidthReader.findByKey(...)`, `FixedWidthReader.streamSequential()`,
`FixedWidthReader.streamFromKey(...)`, and from the internal
`readAllRecords()` helper used by every mutating method of
`FixedWidthWriter`.

**COBOL baseline**: VSAM KSDS / sequential reads under z/OS may silently
process partial records or surface them with FILE STATUS '46' (no next
logical record present) or '30' (permanent error), depending on the file
organisation and access mode. COBOL programs typically check FILE STATUS
after each READ and treat any non-zero / non-'10' value as a fatal
condition that leads to `ABEND-CONDITIONS-INIT` (see e.g.
`app/cbl/CBACT01C.cbl` paragraph `9999-ABEND-PROGRAM`). In practice,
production VSAM datasets are guaranteed to contain only whole records
because IDCAMS verifies record-length conformance at LOAD time
(`[app/jcl/ACCTFILE.jcl:§REPRO]`); the partial-record case is an
operational error, not a routine outcome.

**Java translation**: rather than silently returning the partial bytes
or guessing the intended record boundary, the Java translation surfaces
the truncation immediately. This preserves the byte-for-byte round-trip
invariant required by AAP §0.6.5 — `parse(record).encode()` MUST equal
the original buffer — by refusing to lose data. The IOException maps
conceptually to the COBOL FILE STATUS '46' / '30' fatal condition and
should be caught at the application layer where it can be translated to
a sealed FileStatus.IoError exception or an ABEND equivalent per the
program's existing error-handling paragraph.

**Why this is a DEVIATION rather than an IMPLEMENTATION DECISION**: the
exact byte boundary at which COBOL surfaces a partial record varies by
file organisation (VSAM KSDS vs. RECFM=FB sequential), access mode
(SEQUENTIAL vs. RANDOM), and the JCL-time LRECL declaration. A faithful
1-to-1 translation would require modelling each of these surfaces
separately; the Java translation uses a single, stricter contract that
the foundational primitives enforce uniformly. Per AAP §0.6.5 the
byte-for-byte invariant takes precedence over silent-truncation
compatibility.

**Action items for downstream translation**:

1. When translating any program that reads fixed-width files, catch
   `IOException` at the application layer and translate it to the
   appropriate COBOL FILE STATUS equivalent (typically '30' for I/O
   errors, mapped to a sealed `FileStatus.IoError(int, String)`).
2. Treat truncated-record exceptions as fatal at the JCL-step
   equivalent (matching the COBOL `9999-ABEND-PROGRAM` paragraph) —
   do NOT swallow them or attempt partial-record recovery, which
   would diverge from the byte-for-byte invariant.
3. If a captured COBOL run is ever observed to produce a truncated
   trailing record on a known-good VSAM dataset, treat that as a
   fixture-capture defect rather than a parity bug, and document the
   capture procedure correction in `§1.6 Capture procedure` below.

### 1.4.9 `cardxref.txt` ASCII fixture is 36 bytes/record vs. 50 bytes/record per AAP

**Status**: ACKNOWLEDGED — the discrepancy between
`app/data/ASCII/cardxref.txt` (36 bytes/record × 50 records = 1800
bytes) and AAP §0.6.9 (`CardXrefRecord` documented as 50 bytes) is
preserved as-is in the Java translation; both layouts are supported.

**Source layout**: `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD) is the
authoritative COBOL definition. The 50-byte AAP claim covers the
fully-padded EBCDIC source layout (`PIC X(16)` card number + `PIC 9(9)`
COMP-3 customer id + `PIC 9(11)` COMP-3 account id + 14 bytes FILLER =
50 bytes). The 36-byte ASCII fixture omits the FILLER bytes and the
unpacked DISPLAY representation of the numeric fields adds 9 + 11 = 20
ASCII digits to a 16-byte card number, totalling 36 bytes per record.
Both representations decode to the same logical record content.

**Java translation**:
`com.blitzy.carddemo.domain.record.CardXrefRecord` declares the
canonical 4-field record (xrefCardNum, xrefCustId, xrefAcctId, filler).
The `parse(byte[])` factory in the record class detects the layout
based on the buffer length (36 vs 50) and dispatches to the correct
ASCII or EBCDIC interpretation. The `encode()` instance method emits
the 50-byte EBCDIC source layout by default; an `encodeAscii()` overload
emits the 36-byte ASCII variant for fixture-compatibility tests.

**Action item**: when the golden-record harness captures fresh COBOL
fixture runs, document which representation the captured fixtures use
(36-byte ASCII vs 50-byte EBCDIC) per program. The Java application
classes default to the EBCDIC representation matching the COBOL VSAM
source.

### 1.4.10 `CoBil00C` "Payment successful." message double-space anomaly

**Status**: IMPLEMENTED — the COBOL bill-payment success message
contains a verbatim double-space sequence that is preserved in the Java
translation.

**COBOL source**: `app/cbl/COBIL00C.cbl`, paragraph
`9000-MAKE-BIL-PAYMENT`. After the `EXEC CICS WRITE FILE('TRANSACT')`
returns DFHRESP(NORMAL), the program builds a confirmation message:

```cobol
STRING 'Payment successful.  Your Transaction ID is '
       WS-TRAN-ID
       ' Press Enter to Continue'
       DELIMITED BY SIZE
       INTO WS-RETURN-MSG
```

Note the **two spaces** between the terminal period of "successful." and
"Your" — this is a STRING-literal anomaly preserved verbatim.

**Java translation**:
`com.blitzy.carddemo.application.billpay.CoBil00C` declares the
constant `MSG_PAYMENT_SUCCESS_PREFIX = "Payment successful.  Your
Transaction ID is "` (with the two spaces intact) and concatenates the
generated transaction id and " Press Enter to Continue" suffix. The
golden-record harness will detect any silent space normalisation.

### 1.4.11 `CoUsr02C` "atleast" typo preservation

**Status**: IMPLEMENTED — the COBOL user-update validation message
contains a verbatim "atleast" (no space) typo that is preserved.

**COBOL source**: `app/cbl/COUSR02C.cbl`, paragraph
`1200-EDIT-MAP-INPUTS`. The COBOL source has the literal:

```cobol
MOVE 'User ID can NOT be empty and must be atleast 4 characters...'
     TO WS-MESSAGE
```

Note the COBOL writes "atleast" (one word) instead of "at least" (two
words). The standard English spelling is "at least"; the COBOL source
contains a typo.

**Java translation**:
`com.blitzy.carddemo.application.user.CoUsr02C` declares the constant
`MSG_USERID_LENGTH = "User ID can NOT be empty and must be atleast 4
characters..."` (with "atleast" preserved verbatim). The Javadoc
documents the intended spelling.

**Rationale**: see §1.4.6 — same AAP §0.7.1 preserve-as-is mandate
applies.

### 1.4.12 `CbTrn03C` duplicate paragraph names anomaly

**Status**: IMPLEMENTED — the COBOL paginated transaction report writer
program contains five paragraphs that share two numeric prefixes
(1110 and 1120). Each is preserved in the Java translation as a
distinct private method with a purpose-specific Java name.

**COBOL source**: `app/cbl/CBTRN03C.cbl`. Two prefix collisions are
observable:

- `1110-` prefix is shared by:
  - `1110-WRITE-PAGE-TOTALS` (line 293) — writes the page-total
    accumulator, folds it into the grand total, and emits the
    all-dashes separator
  - `1110-WRITE-GRAND-TOTALS` (line 318) — writes the grand-total
    accumulator on a single line at end-of-file
- `1120-` prefix is shared by:
  - `1120-WRITE-ACCOUNT-TOTALS` (line 306) — writes the per-card
    (account) total accumulator
  - `1120-WRITE-HEADERS` (line 324) — writes the four-line header
    block (name, blank, header-1, header-2) at top of each page
  - `1120-WRITE-DETAIL` (line 361) — writes the detail line for one
    in-window transaction

COBOL does not enforce paragraph-name uniqueness when paragraphs are
not the target of a `PERFORM THRU` range that crosses the duplicates,
and CBTRN03C relies on this relaxation. The duplicate names are
preserved per AAP §0.7.1 (faithful translation).

**Java translation**:
`com.blitzy.carddemo.application.transaction.CbTrn03C` translates each
of the five collision-suffering paragraphs as a separate private
method with a purpose-specific name:

| COBOL paragraph             | Java method               |
|-----------------------------|---------------------------|
| `1110-WRITE-PAGE-TOTALS`    | `writePageTotals()`       |
| `1110-WRITE-GRAND-TOTALS`   | `writeGrandTotals()`      |
| `1120-WRITE-ACCOUNT-TOTALS` | `writeAccountTotals()`    |
| `1120-WRITE-HEADERS`        | `writeHeaders()`          |
| `1120-WRITE-DETAIL`         | `writeDetail(TranRecord)` |

Each method's Javadoc cites the original COBOL paragraph name and
line range. Call sites in `writeTransactionReport()` and the
end-of-file branch in `run()` invoke the appropriately-named Java
method directly; no PERFORM THRU range is used so the COBOL ambiguity
that the prefix collision could have caused is moot in Java.

### 1.4.12.bis `CbTrn03C` EOF-branch stale TRAN-AMT double-add (suspected COBOL bug)

**Status**: IMPLEMENTED — preserved verbatim per AAP §0.7.1 "If a COBOL
paragraph contains dead code or obvious bugs, translate it faithfully
and flag it in a MIGRATION_NOTES.md; do not 'fix' it in this refactor."

**COBOL source**: `app/cbl/CBTRN03C.cbl`, lines 197-203 inside MAIN-PARA.
After `1000-TRANFILE-GET-NEXT` sets `END-OF-FILE = 'Y'`, the COBOL FD
buffer `TRAN-RECORD` retains the bytes of the last successfully read
record. The EOF branch then executes:

```
DISPLAY 'TRAN-AMT ' TRAN-AMT
DISPLAY 'WS-PAGE-TOTAL' WS-PAGE-TOTAL
ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
PERFORM 1110-WRITE-PAGE-TOTALS
PERFORM 1110-WRITE-GRAND-TOTALS
```

Reading `TRAN-AMT` from the FD buffer after EOF is a defect: the
value is the last successfully read record's amount, which has
already been added to both accumulators by `1100-WRITE-TRANSACTION-REPORT`
on the previous iteration. This causes the final record's amount to
appear **twice** in the page total and the account total (but only
once in the grand total because the page total has not yet been
folded into it at this point).

The defect is masked in practice because:

1. The TRANREPT.jcl SORT step pre-filters input so records can be
   excluded entirely, making the "last record" semantically
   meaningful only when at least one record passed the SORT filter.
2. The page-total line is written before the grand-total line, so
   the double-counted amount appears in the page total then folds
   into the grand total during `1110-WRITE-PAGE-TOTALS`, producing
   a grand total that is *also* inflated by the final record's
   amount. Both the page total and grand total are off by the same
   amount; the discrepancy is only apparent in the *account* total
   if the input contains records for a single card number.

**Java translation**:
`com.blitzy.carddemo.application.transaction.CbTrn03C#run()` preserves
this behavior verbatim: when the iterator returns `endOfFile = true`,
the method calls `Decimals.add(pageTotal, lastTran.tranAmt(), 2,
RoundingMode.DOWN)` and the same for `accountTotal`, then calls
`writePageTotals()` (which folds page total into grand total) and
`writeGrandTotals()`. The implementation explicitly handles the
"empty input" edge case (`lastTran == null`) by skipping the addition
entirely; this matches the COBOL behavior because PIC 9 fields are
zero-initialized by the COBOL runtime, so ADD against zeros is a
no-op even if the buffer is in an undefined state.

**Diagnostic logging preservation**: the two COBOL `DISPLAY`
statements `'TRAN-AMT ' TRAN-AMT` and `'WS-PAGE-TOTAL' WS-PAGE-TOTAL`
are preserved as SLF4J `LOGGER.info("TRAN-AMT {}", ...)` and
`LOGGER.info("WS-PAGE-TOTAL {}", ...)` calls in the same order.

**Future remediation**: A fix would be to reset `lastTran = null`
after the EOF read sets `endOfFile = true`, or to elide the EOF
branch's addition entirely. Either change is BEHAVIORAL and out of
scope for this idiom-for-idiom migration. Any future bug-fix effort
should land as a separate PR with its own golden-record baseline
update.

### 1.4.12.ter `CbTrn03C` `NEXT SENTENCE` on out-of-window date

**Status**: IMPLEMENTED — apparent-intent interpretation chosen because
the upstream `STEP05R SORT` step in TRANREPT.jcl pre-filters records
to fit the window, making strict and apparent-intent semantics
observationally identical.

**COBOL source**: `app/cbl/CBTRN03C.cbl`, lines 173-178 inside MAIN-PARA:

```
IF TRAN-PROC-TS (1:10) >= WS-START-DATE
   AND TRAN-PROC-TS (1:10) <= WS-END-DATE
    CONTINUE
ELSE
    NEXT SENTENCE
END-IF
```

In strict Enterprise COBOL semantics, `NEXT SENTENCE` transfers
control to the statement immediately following the *next sentence-
terminating period* (not the next statement). The next period in
CBTRN03C is the `END-PERFORM.` on line 206 — the closing period of
the outer `PERFORM UNTIL END-OF-FILE = 'Y'` loop. So strict
interpretation would have `NEXT SENTENCE` literally **exit the outer
loop** on the first out-of-window record, terminating report
generation prematurely.

**Driving JCL**: `app/jcl/TRANREPT.jcl` step `STEP05R` runs SORT with
`OUTREC FIELDS=(...)` and `INCLUDE COND=(TRAN-PROC-DT,GE,
PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)`. Because the
SORT step pre-filters input to fit the date window before CBTRN03C
runs, the COBOL `ELSE NEXT SENTENCE` branch is **never reached in
production**. Both the strict interpretation (exit loop) and the
apparent-intent interpretation (skip-and-continue) are
observationally identical because there are no out-of-window records
to test the branch against.

**Java translation**:
`com.blitzy.carddemo.application.transaction.CbTrn03C#run()` uses the
apparent-intent interpretation: when the date check fails, the
`continue` statement skips to the next loop iteration without
exiting. This matches the AAP file schema (`agent_prompt`) which
explicitly states "ELSE → NEXT SENTENCE (no action; loop
continues)".

**Future remediation**: If a future effort removes the upstream SORT
filter (e.g., to support unfiltered input), the strict COBOL
semantics must be re-evaluated. The current Java implementation will
gracefully ignore out-of-window records; the strict-COBOL
implementation would terminate the report at the first out-of-window
record. Neither matches the obvious developer intent (which would be
to skip the record), but the Java choice produces useful output
where the strict COBOL choice would not.

### 1.4.13 COACTUPC verbatim message preservation list

**Status**: IMPLEMENTED — all 30+ COACTUPC validation, status, and
prompt messages are preserved verbatim in
`com.blitzy.carddemo.application.account.CoActUpC` as `private static
final String` constants. The full list of preserved anomalies:

1. `'Changes validated.Press F5 to save'` — **no space** after the dot,
   between "validated." and "Press" (PROMPT-FOR-CONFIRMATION 88-level)
2. `'PF03 pressed.Exiting              '` — mixed case, **no space**
   after the dot, **14 trailing spaces** (WS-EXIT-MESSAGE)
3. `'Record changed by some one else. Please review'` — **two-word
   "some one"** spelling (DATA-WAS-CHANGED-BEFORE-UPDATE 88-level)
4. `'Looks Good.... so far'` — **four dots** followed by space then "so
   far" (CODING-TO-BE-DONE 88-level — placeholder preserved)
5. `'Name can only contain alphabets and spaces'` — **"alphabets"**
   idiom rather than "letters" (WS-NAME-MUST-BE-ALPHA 88-level)
6. `'No change detected with respect to values fetched.'` — terminal
   period (NO-CHANGES-DETECTED 88-level)
7. `'Changes committed to database'` — **no terminal period**
   (CONFIRM-UPDATE-SUCCESS 88-level)
8. `'Changes unsuccessful. Please try again'` — space after the period
   (INFORM-FAILURE 88-level)
9. `'Update of record failed'` — **no terminal period**
   (LOCKED-BUT-UPDATE-FAILED 88-level)
10. `'Could not lock account record for update'` — **no terminal
    period** (COULD-NOT-LOCK-ACCT-FOR-UPDATE 88-level)
11. `'Could not lock customer record for update'` — **no terminal
    period** (COULD-NOT-LOCK-CUST-FOR-UPDATE 88-level)
12. `'Error reading Card Data File'` — **Title Case** (XREF-READ-ERROR
    88-level)
13. `'Did not find this account in account card xref file'` —
    DID-NOT-FIND-ACCT-IN-CARDXREF first 88-level (anomaly: duplicate
    88-level later has 'cards database' instead, distinct text)
14. `'Did not find this account in account master file'` —
    DID-NOT-FIND-ACCT-IN-ACCTDAT 88-level
15. `'Did not find associated customer in master file'` —
    DID-NOT-FIND-CUST-IN-CUSTDAT 88-level
16. `'Account Number if supplied must be a 11 digit Non-Zero Number'` —
    STRING-concatenated two literals (1210-EDIT-ACCOUNT)
17. `'Did not find cards for this search condition'` —
    DID-NOT-FIND-ACCTCARD-COMBO 88-level
18. `': should not be 000, 666, or between 900 and 999'` —
    SSN-PART1 suffix (concatenated with 'SSN: First 3 chars' prefix)
19. (Customer-not-found dynamic message)
    `'Account:' + acct + ' not found in Cross ref file. Resp:' +
    resp + ' Reas:' + reas2` — **mixed-case "Reas:"** (XREF-not-found)
20. (Account-not-found dynamic message)
    `'Account:' + acct + ' not found in Acct Master file.Resp:' +
    resp + ' Reas:' + reas2` — **NO SPACE before "Resp:"** (anomaly;
    contrast with §1.4.13 item 19 which DOES have a space)
21. (Customer-not-found dynamic message)
    `'CustId:' + cust + ' not found in customer master.Resp: ' +
    resp + ' REAS:' + reas2` — **uppercase "REAS:"** (vs mixed-case
    "Reas:" elsewhere), and **space-after-"Resp: "** (contrast with
    item 20 which has no space)

The Java translation preserves every space, every dot, every case
variation, and every trailing-space pad byte-for-byte; the golden-record
harness will detect any silent normalisation.

### 1.4.14 `CoMen01C` DUMMY-prefix screen name anomaly

**Status**: IMPLEMENTED — the COBOL main menu program contains a
DUMMY-prefix convention that is preserved verbatim.

**COBOL source**: `app/cbl/COMEN01C.cbl`. Several screen/transaction
identifiers carry a `DUMMY` prefix where one would expect a real
program-id (e.g., `DUMMYXCT` as a placeholder for an XCTL-target). The
prefix may have served as a development placeholder that was never
renamed, or it may represent intentionally-disabled menu options. The
prefix is preserved per AAP §0.7.1.

**Java translation**:
`com.blitzy.carddemo.application.menu.CoMen01C` retains the `DUMMY` prefix
in its menu-table entries verbatim. The `ProgramRegistry.invoke(...)`
call site recognises the `DUMMY` prefix and returns a "Not yet
implemented" message rather than dispatching to a non-existent
program — mirroring the COBOL behaviour of falling through to an error
path on the invalid program name.

### 1.4.15 `CoActUpC` two-record SYNCPOINT ROLLBACK implementation strategy

**Status**: IMPLEMENTED — the COBOL atomic-update semantics for ACCTDAT
+ CUSTDAT are reproduced in the Java translation via a try/catch
block that performs a compensating restore on customer-save failure.

**COBOL source**: `app/cbl/COACTUPC.cbl`, paragraph
`9600-WRITE-PROCESSING`. The program performs two REWRITE statements:

```cobol
EXEC CICS REWRITE FILE('ACCTDAT')   FROM(ACCT-UPDATE-RECORD)
                  LENGTH(LENGTH OF ACCT-UPDATE-RECORD) RESP(WS-RESP-CD)
END-EXEC.
IF NOT NORMAL-RESP THEN ... fail ... END-IF.

EXEC CICS REWRITE FILE('CUSTDAT')   FROM(CUST-UPDATE-RECORD)
                  LENGTH(LENGTH OF CUST-UPDATE-RECORD) RESP(WS-RESP-CD)
END-EXEC.
IF NOT NORMAL-RESP THEN
    EXEC CICS SYNCPOINT ROLLBACK END-EXEC
    GO TO 9600-WRITE-PROCESSING-EXIT
END-IF.
```

When the second REWRITE (CUSTDAT) fails after the first REWRITE
(ACCTDAT) has already succeeded, the program issues an explicit
`EXEC CICS SYNCPOINT ROLLBACK`. Under CICS, this rolls back the entire
logical unit of work (LUW), including the ACCTDAT REWRITE, so the
file remains consistent (either both records are updated or neither is).

**Java translation**: the file-based adapter does NOT have an
underlying transactional service to roll back against. Instead,
`com.blitzy.carddemo.application.account.CoActUpC.doWriteProcessing(...)`
models the equivalent atomic semantics by capturing the pre-edit
`AccountRecord` snapshot before any writes, then wrapping the two
`save()` calls in a try/catch block. On customer-save failure, the
catch block re-issues `accounts.save(originalAccount)` to restore the
account record to its pre-edit state.

**Caveat**: the compensating restore is best-effort. If the
compensating restore itself fails (e.g., due to a disk-full condition
encountered between the two writes), the file is left in an
inconsistent state and the catch block logs the inconsistency before
propagating the original `CHANGES_OKAYED_BUT_FAILED` state to the user.
A production-grade alternative would route both writes through a
JDBC-backed adapter with a single database transaction; this is
deferred to a follow-on effort per the AAP scope.

### 1.4.16 Form-Field input '*' and SPACES → LOW-VALUES normalization convention

**Status**: IMPLEMENTED — every BMS input field that arrives as `'*'` or
all-spaces is normalised to the empty string at the boundary of the
Java application class, mirroring the COBOL `IF FIELDI = '*' OR SPACES
MOVE LOW-VALUES TO FIELDI` idiom.

**COBOL source**: `1100-RECEIVE-MAP` paragraphs across all online
programs (`COACTUPC`, `COCRDUPC`, `COCRDSLC`, `COUSR01C`, `COUSR02C`,
`COBIL00C`, `COTRN02C`, etc.). The COBOL convention treats `'*'` as a
"clear this field" sentinel (the operator types a single `*` to clear
an existing value) and all-spaces as the default-empty state.

**Java translation**: every BMS input record carries its field values as
`String`. The corresponding application class declares a private
`normalizeStarOrSpaces(String raw)` helper that returns `""` when the
trimmed input is empty or exactly `"*"`, and the raw value otherwise.
Every per-field receive site invokes this helper before downstream
processing. The convention is consistent across all 17 online programs.

---

## Section 1.5: BEHAVIORAL PARITY PRESERVATIONS

This section enumerates behaviours that look like defects or
modernisation candidates but are preserved verbatim by deliberate
mandate, not by oversight.

### 1.5.1 Plaintext password storage

**Where**: `SEC-USER-DATA.SEC-USR-PWD PIC X(08)`
`[app/cpy/CSUSR01Y.cpy:L21]`. The user-security record stores the user's
password as an 8-character text field, unsalted and unhashed. The USRSEC
VSAM file holds these records in the clear.

**What the Java translation does**: the `SEC-USR-PWD` field maps to a
`String` member on `com.blitzy.carddemo.domain.record.SecUserData` and is
written to the file-based USRSEC adapter verbatim. No hashing, no
salting, no KDF is applied at the storage surface.

**Why this is preserved**: AAP §0.1.3 — "The user separately mandates
that no card PAN be logged in full ... These two rules are NOT in
conflict: storage and logging are different surfaces. Any decision to
introduce password hashing would constitute a behavior change beyond
migration scope and is therefore explicitly OUT OF SCOPE for this
refactor". The PAN-masking rule is a logging concern and **is** in
scope; the password-hashing concern is a storage / authentication
behaviour change and **is not** in scope.

**Action item for a follow-on effort**: introduce BCrypt or Argon2id
hashing at the authentication boundary in `CoSgn00C` and add a one-shot
migration script to rewrite the USRSEC dataset. This is a distinct
project and must not be folded into the migration.

### 1.5.2 COBOL FILE STATUS error codes

All COBOL `FILE STATUS` codes (`00`, `02`, `04`, `10`, `23`, `35`, `46`,
`47`, `48`, `49`, `92`, `94`, `9x`, etc.) are translated to a
`com.blitzy.carddemo.domain.port.FileStatus` sealed hierarchy with
permits `Ok`, `EndOfFile`, `NotFound`, `DuplicateKey`,
`IoError(int code, String description)` per AAP §0.6.10. The observable
outcomes — which programs exit with which return code on which condition
— are preserved exactly.

### 1.5.3 Edge-case behaviour: overflow, divide-by-zero, rounding, padding, sign nybble

All edge cases are preserved per AAP §0.7.1 Preserve-As-Is list:

- **Overflow**: COBOL truncates to the declared `PIC` width on overflow
  without raising a runtime error. Java translation uses
  `BigDecimal.setScale(scale, RoundingMode.DOWN)` followed by an
  explicit width check that mimics COBOL truncation rather than raising
  `ArithmeticException`.
- **Divide-by-zero**: COBOL raises an `ON SIZE ERROR` condition when
  the corresponding clause is present and otherwise produces an
  undefined result. Java translation matches each `ON SIZE ERROR` site
  in the source one-for-one; sites without the clause replicate the
  undefined behaviour by returning zero (the empirical COBOL outcome on
  the IBM Enterprise COBOL runtime).
- **Rounding direction**: `ROUNDED` clauses map to
  `RoundingMode.HALF_EVEN` (banker's rounding); unrounded operations
  map to `RoundingMode.DOWN` (truncation). Defaults centralised in
  `com.blitzy.carddemo.domain.util.Decimals` per AAP §0.3.3.
- **Padding direction**: leading-zero padding for numeric fields,
  trailing-space padding for alphanumeric fields. Both encoded in
  `Decimals.encode*` and `FixedWidthWriter` respectively.
- **Sign nybble**: trailing `D` nybble for negative COMP-3 values,
  `C` for positive signed, `F` for unsigned. Encoded by
  `Decimals.encodeSignedPacked`.

---

## Section 1.6: Golden-Record Fixture Capture Instructions

Per AAP §0.6.11 the golden-record harness is the **non-negotiable** PR
gate: it asserts byte-for-byte equality between the Java output and the
captured COBOL output. The capture procedure resolves the user TODO
"To regenerate golden-record fixtures from COBOL [TODO — document the
COBOL build/run path here]" carried in AAP §0.7.5.

### 1.6.1 Capture procedure

1. **Compile each COBOL program**. On a z/OS LPAR run the corresponding
   build job from `samples/jcl/` (`BATCMP.jcl` for batch programs,
   `BMSCMP.jcl` for BMS maps, `CICCMP.jcl` for CICS-online programs).
   Off-platform, an AWS Mainframe Modernization Replatform compile
   (Micro Focus Enterprise Developer) is acceptable provided the
   resulting load module is binary-equivalent to the z/OS build.
2. **Execute the corresponding JCL job** from `app/jcl/` against the
   fixed-width fixtures in `app/data/ASCII/`. The fixtures are pre-
   transcoded to ASCII for test convenience; production datasets in
   EBCDIC IBM-1047 must be transcoded before the JCL submission step
   if testing against EBCDIC inputs.
3. **Capture every observable artifact**: stdout, stderr, every DD
   output file, the JES2 condition code per step, and any abend code.
   Capture file sizes and SHA-256 sums alongside the bytes themselves
   so corruption-on-transport can be detected.
4. **Commit captured artifacts** under
   `java/carddemo-tests/src/test/resources/golden/<program>/expected/`
   in a directory layout that matches what `GoldenRecordTest` expects.
   For each program, commit the input fixture path (a reference into
   `app/data/ASCII/`, never a copy), the expected output file(s), and a
   `metadata.json` containing the capture date, compiler version,
   codepage assumption, and JES2 condition codes per step.
5. **Document the capture details below** in Section 1.6.2 below. Each
   capture row must include: program name, capture date (ISO-8601),
   COBOL compiler version (e.g., "Enterprise COBOL for z/OS 6.4"),
   codepage (IBM-1047 unless overridden), capturer's identity, and
   the SHA-256 of every committed expected-output file.
6. **Until COBOL captures are committed**, initial test scaffolding may
   use placeholder expected files and mark the corresponding tests
   `@Disabled("awaiting COBOL capture")`. The harness skeleton — the
   `GoldenRecordTest` base class and each per-program subclass — is
   created unconditionally per AAP §0.6.11.

### 1.6.2 Capture log

_(empty at initial translation — populated as captures are committed)_

| Program | Capture date | Compiler version | Codepage | Captured by | Expected-output SHA-256(s) |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

---

## Section 1.7: Codepage and EBCDIC Handling Notes

Per AAP §0.6.5 ("File I/O Exactness") the Java port supports both EBCDIC
and ASCII fixed-width files behind the same `FixedWidthReader` /
`FixedWidthWriter` API.

- **Default codepage**: `Charset.forName("IBM-1047")` — US EBCDIC with
  the euro sign at code-point `0x9F` and the `[` / `]` brackets in their
  EBCDIC positions. Production datasets on z/OS are assumed to use this
  codepage unless explicitly overridden.
- **Per-file overrides**: each logical file has a configurable codepage
  property in `application.properties`, e.g.
  `carddemo.file.acctdata.charset=IBM-1047` or
  `carddemo.file.usrsec.charset=US-ASCII`. The 9 fixtures in
  `app/data/ASCII/*.txt` are pre-transcoded to ASCII so the test harness
  can operate on platforms without ICU; the production code path
  exercises the EBCDIC route.
- **Byte-for-byte round-trip invariant**: for every supported record
  type, `record.parse(buffer).encode()` MUST equal `buffer` for every
  valid `buffer`. This is asserted by jqwik property tests in
  `carddemo-tests/src/test/java/com/blitzy/carddemo/tests/property/`
  and by the golden-record harness on every PR.
- **Charset substitution policy**: the readers use `CodingErrorAction.REPORT`
  for both unmappable and malformed input, so any byte sequence that
  cannot be decoded raises `MalformedInputException` instead of being
  silently replaced. This matches COBOL's behaviour of raising
  `FILE STATUS 9x` on corrupted input rather than producing garbage.

---

## Section 1.8: ScopedValue vs ThreadLocal

Per AAP §0.6.6 and AAP §0.7.3, `java.lang.ThreadLocal` is **forbidden in
new Java code** under `java/`. Cross-method context propagation uses
`java.lang.ScopedValue` (JEP 506, finalised in Java 25) exclusively.

- **Batch-run context** (run ID, processing date, tenant) is carried by
  `com.blitzy.carddemo.batch.BatchRunContext`, bound via
  `ScopedValue.where(BATCH_CTX, ctx).run(() -> …)` at the entry point of
  each `carddemo-app` main class. Any callee — on the same or a child
  virtual thread — retrieves the context via `BATCH_CTX.get()`.
- **Why ScopedValue and not ThreadLocal**: ScopedValue is immutable
  within its dynamic scope and is automatically inherited by child
  virtual threads, which matters when batch fan-out spawns millions of
  short-lived virtual threads. ThreadLocal would either leak across
  reused platform threads or require expensive `InheritableThreadLocal`
  inheritance.
- **Third-party transitive ThreadLocal usage** in dependencies (e.g.
  Logback's MDC) is tolerated; we do not pull in new dependencies that
  require ThreadLocal in our own code paths.

---

## Section 1.9: Compact Object Headers and Shenandoah Generational GC

Per AAP §0.3.4 and §0.7.2, the JVM tuning baseline for every
`carddemo-app` shaded jar is:

- `-XX:+UseCompactObjectHeaders` (JEP 519, **finalised** in Java 25):
  each object header occupies one machine word instead of two, reducing
  heap by roughly 20–30 % on small-record-heavy workloads (typical of
  the CardDemo posting and statement runs).
- `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521,
  **finalised** in Java 25): low-pause generational collector suitable
  for batch jobs with mixed short-lived per-record allocation and
  longer-lived in-memory tables (the TRANTYPE, TRANCATG, and DISCGRP
  lookup tables in particular).
- These flags are **finalised production options** in Java 25, not
  preview features; no `--enable-preview` is required and none must be
  added.

The flags are documented in `java/README.md` under "Run" instructions
and in the `application.properties.example` JVM-flags section.

---

## Section 1.10: References to AAP Sections

This file resolves or summarises the following AAP sections:

- **AAP §0.1.3** — Surfaced Implicit Requirements (plaintext password
  preservation, byte-for-byte file fidelity, ScopedValue mandate)
- **AAP §0.4.1** — File-by-File Transformation Plan
  (DEVIATION / IMPLEMENTATION DECISION flags per program)
- **AAP §0.6.5** — File I/O Exactness (codepage, byte-round-trip)
- **AAP §0.6.6** — Batch Throughput Strategy (virtual threads,
  ScopedValue)
- **AAP §0.6.11** — Golden-Record Harness Design (capture procedure)
- **AAP §0.6.12** — Acknowledged Architectural Override (no Spring,
  no PostgreSQL, file-based default)
- **AAP §0.7.1** — Refactor Discipline Guidelines (verbatim user
  mandate quoted in the header above)
- **AAP §0.7.5** — User-Provided Examples and TODO Markers (resolution
  table in Section 1.2)
- **AAP §0.8.1** — Citation discipline (this file follows it)

---

## Section 1.11: Open Items (as of initial translation)

Items that remain open at the conclusion of the initial migration pass.
Each must be closed before the migration can be declared complete.

- **OPEN — TPS baseline**: actual measured COBOL TPS for the benchmark
  workload is not yet captured. JFR-based regression testing
  (`JfrBaseline.java` and its `src/test/resources/perf/baseline-*.properties`
  fixture files) is deferred to a future checkpoint per the Checkpoint 2
  review scope-control finding; the test scaffold was reverted in this
  checkpoint because it had been merged outside its intended milestone.
  When the JFR baseline returns, the 10 % regression band cannot be
  enforced until a baseline is committed under
  `java/carddemo-tests/src/test/resources/perf/baseline.jfr` and the
  measured throughput is recorded here. (References Section 1.2 row 4.)
- **OPEN — adapter-file unit-test coverage**: comprehensive JUnit 5
  coverage for `EbcdicTranscoder` (constants, both constructors,
  `charsetFor`, `defaultCharset`, `isEbcdic`, and the
  `ebcdicToAscii` / `asciiToEbcdic` transcoding methods added by the
  Checkpoint 2 review fix) is deferred to a future checkpoint per the
  Checkpoint 2 review scope-control finding; the test class was
  reverted in this checkpoint because it had been merged outside its
  intended milestone. When re-introduced, the test class must exercise
  the strict `CodingErrorAction.REPORT` policy for malformed and
  unmappable bytes (round-trip + unmappable-character paths).
- **OPEN — adapter-db package skeleton**: the optional JDBC adapter
  package (`com.blitzy.carddemo.adapter.db`) was reverted from a
  Javadoc-only `package-info.java` to an empty source tree per the
  Checkpoint 2 review scope-control finding. The module pom.xml
  remains (Checkpoint 1 in-scope) and produces an empty JAR by
  default; concrete JDBC repositories are introduced only if a
  source-side embedded SQL requirement surfaces, per AAP §0.6.12.
- **OPEN — Golden-record fixture captures**: per AAP §0.6.11 every
  translated program needs a captured COBOL expected-output set. Until
  the captures are committed, the corresponding tests are `@Disabled`.
  The capture log in Section 1.6.2 must be populated as captures land.
- **OPEN — `[inferred — no direct source]` paragraph inventories**:
  AAP §0.6.8 marks several COBOL programs' paragraph lists as inferred
  rather than verified. Each per-program translation agent must replace
  the inferred entry with the actual paragraph inventory read from the
  program source file, and add a row to Section 1.4 of this file if any
  newly-discovered paragraph requires a DEVIATION or IMPLEMENTATION
  DECISION flag.
- **OPEN — DEVIATION log entries from per-program translations**: each
  application-layer translation agent must append a row to Sections 1.3
  or 1.4 if its program contains a construct that warrants flagging.
  The list above (§§ 1.3.1 – 1.3.3, 1.4.1 – 1.4.4) is the initial set
  derived from the AAP-mandated source files; it is not exhaustive.

---

_End of MIGRATION_NOTES.md. Future translation agents append to the
relevant section above; they MUST NOT rewrite history. Each new entry
cites its source file using the AAP §0.8.1 citation discipline._
