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
both the codes and the exact message strings byte-for-byte, including
the truncations dictated by `WS-RESULT IS 15 CHARACTERS`
`[app/cbl/CSUTLDTC.cbl:L126]`.

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

### 1.4.5 CBACT01C / CBACT03C `DOUBLE-DISPLAY` anomaly (RESERVED — translation pending)

**Status**: RESERVED — placeholder section for an anomaly that will be
documented in full once the affected programs are translated. This entry
is added now (pre-translation) so that future translation agents have a
single, stable place to attach the analysis when the actual COBOL
inspection is performed.

**Anticipated COBOL source**: `app/cbl/CBACT01C.cbl` and
`app/cbl/CBACT03C.cbl`. The "DOUBLE-DISPLAY" anomaly refers to a
sequential-reader idiom in which each fetched record is emitted to
`SYSOUT` via a pair of `DISPLAY` statements — typically one that prints
a labelled field summary and a second that prints the raw record image.
Whether this is intentional (an audit trail useful in mainframe
operations) or a vestigial duplicate left over from earlier development
will be confirmed during the actual translation pass. The user mandate
in AAP §0.7.1 ("If a COBOL paragraph contains dead code or obvious bugs,
translate it faithfully and flag it in a `MIGRATION_NOTES.md`; do not
'fix' it in this refactor.") applies either way.

**Anticipated Java translation**: the Java port in
`com.blitzy.carddemo.application.account.CbAct01C` and
`com.blitzy.carddemo.application.account.CbAct03C` will preserve the
double output verbatim. Two `System.out.println(...)` (or, equivalently,
two SLF4J `logger.info(...)` calls) will be emitted at each record
boundary, in the same order and with the same content as the COBOL
DISPLAY pair. Byte-for-byte parity of the `SYSOUT`-equivalent stream is
required by the golden-record harness (AAP §0.6.11).

**Action items for the CBACT01C / CBACT03C translation pass**:

1. Re-read both program sources end-to-end and confirm the exact
   DISPLAY locations, formats, and surrounding paragraph context.
2. Replace this RESERVED placeholder with the concrete analysis: cite
   the line numbers (`[app/cbl/CBACT01C.cbl:L###-L###]` /
   `[app/cbl/CBACT03C.cbl:L###-L###]`), record the verbatim DISPLAY
   statements, and document the Java methods that translate them.
3. Add a regression assertion to the golden-record harness that
   compares the captured COBOL `SYSOUT` against the Java
   `System.out` / log output for both programs.
4. If the anomaly turns out to be absent in the actual source (only
   anticipated, not confirmed), update this section to record the
   investigation outcome rather than deleting it — so the audit trail
   stays intact.

**Why this section exists now (pre-translation)**: per the checkpoint
review feedback, the migration notes must reserve a placeholder for
every known or anticipated translation peculiarity before the
corresponding code is produced, so that downstream agents do not have to
re-discover the concern. Sections 1.4.1 through 1.4.4 already follow
this convention.

### 1.4.6 `ACCT-EXPIRAION-DATE` typo preservation in `CVACT01Y.cpy` (RESERVED — translation pending)

**Status**: RESERVED — placeholder section for a known field-name typo
that will be preserved verbatim in the Java translation. This entry is
added now (pre-translation of the `AccountRecord` Java class) so that
future translation agents have a single, stable place to attach the
analysis when the copybook is parsed into a record.

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

**Anticipated Java translation**: when
`com.blitzy.carddemo.domain.record.AccountRecord` is produced, the field
name will be `expiraionDate` (camelCase preserving the typo), not
`expirationDate`. The choice is justified by:

1. **AAP §0.1.1 mandate** — "byte-for-byte identical file outputs and
   field-for-field identical record outputs versus the COBOL baseline".
   Field name divergence between COBOL and Java would surface
   immediately in any introspection-based diagnostic (e.g., a generic
   record-formatting helper that walks accessors and labels each value
   with the accessor name), making it harder to verify parity.
2. **AAP §0.7.1 Refactor Discipline** — "If a COBOL paragraph contains
   dead code or obvious bugs, translate it faithfully and flag it in a
   `MIGRATION_NOTES.md`; do not 'fix' it in this refactor." A field
   spelling defect is the canonical example of the kind of "obvious bug"
   the user explicitly forbids correcting.
3. **AAP §0.1.2 transformation rule** — "COBOL `COPY` directive →
   Java `import` of a domain record"; copybook field names are the
   contract between COBOL and Java, and silent renaming would break
   any downstream code search that pivots on the COBOL name.

**Required Javadoc decoration**: the typo'd field's accessor and any
related helper will carry a Javadoc note that records the intended
spelling (`expirationDate`), so a developer reading the Java code is not
left wondering whether the typo is intentional. The Javadoc note
follows the same pattern as the `WIRTE-JOBSUB-TDQ` paragraph note in
Section 1.4.4: the COBOL spelling is preserved in code; the intended
spelling is preserved in comments.

**Action items for the `AccountRecord` translation pass**:

1. Re-read `app/cpy/CVACT01Y.cpy` end-to-end and confirm the typo's
   exact spelling and byte offset within the 300-byte fixed-width
   layout.
2. Replace this RESERVED placeholder with the concrete analysis: cite
   the line number (`[app/cpy/CVACT01Y.cpy:L###]`), the verbatim COBOL
   declaration, and the resulting Java record component.
3. Document any sister fields in other copybooks that share the same
   typo (e.g., card expiration date in `CVACT02Y.cpy` — the spelling
   may or may not also be typo'd; verify case-by-case).
4. Add an assertion to the property-based test suite confirming that
   the Java accessor name matches the COBOL field name modulo the
   hyphen-to-camelCase conversion (i.e., that the typo survived
   translation).

**Why this section exists now (pre-translation)**: per the checkpoint
review feedback, the migration notes must reserve a placeholder for
every known typo or spelling anomaly before the corresponding code is
produced, so that downstream agents do not "helpfully" correct the
spelling and break parity. Sections 1.4.1 through 1.4.5 already follow
this convention.

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

**Trade-off 2: `%msg`-only scope**. The encoder pattern applies
`%replace(...)` only to the `%msg` conversion word (the message body
passed to `Logger.info(...)`, `Logger.error(...)`, etc.). Stack traces
(`%ex`), MDC values (`%X{...}`), logger names, and thread names are NOT
filtered. This is a deliberate scope choice with two implications:

1. **Defensive coding requirement**: application code MUST NOT place
   raw card PANs into exception messages, MDC values, or logger names.
   Code that throws an exception with a PAN in the message
   (`throw new IllegalArgumentException("Bad card: " + cardNumber)`)
   would leak the PAN through the `%ex` rendering. The convention
   for the Java translation is to mask before throwing: callers must
   use `Decimals.maskPan(cardNumber)` (or a future equivalent helper
   on the domain layer) before constructing exception messages or MDC
   entries. Any deviation discovered in downstream translation passes
   must be flagged as a defect and fixed at the call site, not by
   widening the Logback regex.

2. **Alternative considered and rejected**: wrapping the entire
   pattern in `%replace(...)` was considered:
   `%replace(<full layout including %ex>){'<regex>','<replacement>'}`.
   This was rejected because it would also mask digit sequences in
   timestamps, thread names, and logger names that happen to fall in
   the 13–19 digit range (e.g., a thread named `pool-2-thread-1234567890123`
   would have its trailing digits partially masked). The narrower
   `%msg` scope provides cleaner output for non-PAN diagnostics at the
   cost of requiring discipline at exception-message construction
   sites.

**Why this is a DEVIATION rather than an IMPLEMENTATION DECISION**: a
strict idiom-for-idiom translation of the PCI masking requirement would
yield a mask whose asterisk count equals the original PAN's length, and
would apply to every surface where a PAN might appear (message body,
exception trace, MDC). The Java translation provides this guarantee
fully only for the 16-digit message-body case. The remaining cases
(13/15/19-digit PANs in the message body, and any PAN length in stack
traces / MDC) are handled by best-effort regex masking + a coding
convention rather than by Logback alone. The deviation is documented
here so downstream code-generation agents (a) do not "fix" the regex
under the assumption it is buggy, and (b) treat the masking helper at
the application layer as the primary defence, not the logging layer.

**Action items for downstream translation**:

1. When translating any program that logs card numbers (most likely
   `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `CBTRN02C`, `COBIL00C`), use the
   application-layer masking helper before passing the value to the
   logger. Do not rely on Logback alone.
2. When translating exception sites that include a PAN in the message,
   mask at the throw site, not at the catch site.
3. If a future Java 25 release adds a Logback feature for
   back-reference-length-aware replacement, revisit this section and
   consider tightening the regex; the entry should then be updated
   from DEVIATION to RESOLVED.

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
  workload is not yet captured. The JFR-based regression test
  `JfrBaseline.java` cannot enforce its 10 % regression band until a
  baseline is committed under
  `java/carddemo-tests/src/test/resources/perf/baseline.jfr` and the
  measured throughput is recorded here. (References Section 1.2 row 4.)
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
