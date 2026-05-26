# Golden-Record Contract — CORPT00C (Transaction Reports Submission; CICS CR00)

This document is the AUTHORITATIVE byte-for-byte contract for the Java
translation of the COBOL program `app/cbl/CORPT00C.cbl` (CICS Transaction
Reports submission; transaction id `CR00`). It captures every fact a
downstream implementation of `com.blitzy.carddemo.application.report.CoRpt00C`
must honor to produce identical observable outputs to the COBOL baseline
when driven by the same input scenarios. The companion test class is
`com.blitzy.carddemo.tests.golden.CoRpt00CGoldenTest`, which extends
`com.blitzy.carddemo.tests.golden.GoldenRecordTest` and is annotated
`@Disabled("Awaiting COBOL capture per java/MIGRATION_NOTES.md §1.6")`
until `stdout.txt` and `bms_output.txt` in this folder are replaced with
content captured from a live COBOL execution of CORPT00C. All citations
follow the form `[<path>:Lnnn]` or `[<path>:§<section>]` per AAP §0.8.1.
The 14 AAP sections enumerated in the Authority Cascade below bind every
claim in this document; any contract change requires a corresponding AAP
update and a fresh golden-record capture per AAP §0.7.5.

## Authority Cascade

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25
- AAP §0.2.1 — Golden-record fixtures explicitly in scope under `java/carddemo-tests/src/test/resources/golden/**`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE
- AAP §0.3.1 — Fixture directory layout: `input/` + `expected/` per program
- AAP §0.4.1 — IMPLEMENTATION DECISION: CICS TDQ `JOBS` queue is translated to direct method invocation; the observable confirmation message must remain identical to COBOL
- AAP §0.6.4 — `java.time` only; `ScopedValue<Clock>` for deterministic CURRENT-DATE
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely
- AAP §0.6.11 — Golden-record harness is the non-negotiable PR gate
- AAP §0.6.12 — Architectural override: no Spring, no PostgreSQL, no Spring Batch
- AAP §0.7.1 — Minimal Change Clause: preserve verbatim COBOL messages exactly (ASCII bytes, trailing spaces)
- AAP §0.7.2 — Security: no PAN logged (N/A for CORPT00C — no card data on screen); no `System.out`/`System.err` in production
- AAP §0.7.4 — No preview features (JEP 502, 505, 507, 512); no `default` branches in pattern-matching switches that hide cases
- AAP §0.7.5 — Capture procedure for COBOL fixtures cross-referenced to `java/MIGRATION_NOTES.md §1.6`
- AAP §0.8.1 — Citation discipline: every claim cited `[<path>:Lnnn]` or `[<path>:§<section>]`

## Phase 0 — Overview

### Program Identity

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CORPT00C` | `app/cbl/CORPT00C.cbl:L24` |
| CICS Transaction ID | `CR00` | `app/cbl/CORPT00C.cbl:L38` (WS-TRANID) |
| Java FQN | `com.blitzy.carddemo.application.report.CoRpt00C` | AAP §0.4.1 |
| Java package | `com.blitzy.carddemo.application.report` | AAP §0.3.1 |
| BMS map | `CORPT0A` | `app/bms/CORPT00.bms:L26` |
| BMS mapset | `CORPT00` | `app/bms/CORPT00.bms:L19` |
| Symbolic copy | `CORPT0AI` / `CORPT0AO` | `app/cpy-bms/CORPT00.CPY:L17,L121` |
| Default destination (PF3) | `COMEN01C` | `app/cbl/CORPT00C.cbl:L187-L189` |
| First-entry destination | `COSGN00C` | `app/cbl/CORPT00C.cbl:L172-L174` |
| Test class | `com.blitzy.carddemo.tests.golden.CoRpt00CGoldenTest` | AAP §0.6.11 |

### Online-to-Batch Bridge Semantics

CORPT00C is NOT a read-only browse (unlike COTRN00C), NOT a sign-on form
(unlike COSGN00C), and NOT a CRUD form. It is a one-shot submission form
that gathers a report-mode radio selector, an optional manual date range,
and a Y/N confirmation; builds a 17-line JCL skeleton with substituted
date values; and writes the JCL lines to the CICS Transient Data Queue
named `JOBS`. In z/OS the JES initiator then picks up the TDQ contents
and starts the TRANREPT batch job (cataloged at `app/jcl/TRANREPT.jcl`).

The COBOL source declares but never actively uses the TRANSACT VSAM file:
`WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'` at L40 and `COPY CVTRA05Y`
at L146 are vestigial — no `EXEC CICS READ`, `WRITE`, or `STARTBR` ever
targets TRANSACT within CORPT00C. Translate faithfully per AAP §0.7.1
and flag in `java/MIGRATION_NOTES.md`.

IMPLEMENTATION DECISION (AAP §0.4.1): the Java translation routes the 17
substituted JCL lines through a `JclSkeletonSink` port instead of writing
to CICS TDQ; the observable confirmation message and the 17 substituted
JCL lines must remain identical to COBOL. The port's `submit(byte[] line80)`
contract replaces `EXEC CICS WRITEQ TD QUEUE('JOBS')` at L517-L523.

No record update, pagination, row selection, or security check is
performed by CORPT00C; the program trusts the commarea `CDEMO-USER-*`
fields populated by upstream programs (typically COMEN01C or COSGN00C).

### Java Translation Target

```java
@CobolProgram("CORPT00C")
public final class CoRpt00C {
    // Constructor injection of:
    //   - JclSkeletonSink (replaces EXEC CICS WRITEQ TD QUEUE('JOBS'))
    //   - DateValidator   (replaces CALL 'CSUTLDTC')
    //   - Clock           (replaces FUNCTION CURRENT-DATE; via ScopedValue<Clock>)
    //   - ProgramRegistry (replaces EXEC CICS XCTL)
    // Public entry: accepts CardDemoCommarea + CoRpt00Input; returns
    // CoRpt00Output + updated commarea + optional XCTL target program-id.
}
```

The `@CobolProgram` annotation (AAP §0.3.1) is the Javadoc-style
traceability tag at `com.blitzy.carddemo.domain.annotation.CobolProgram`.

## Phase 1 — PROGRAM-ID and WORKING-STORAGE Verbatim

This section captures the COBOL working-storage constants that the Java
translation must reproduce. Field semantics — sizes, initial values,
88-level conditions — bind the Java translation; any deviation is a
defect.

```cobol
*[L23-L24] Identification Division
IDENTIFICATION DIVISION.
PROGRAM-ID. CORPT00C.

*[L36-L58] Working-Storage constants
WORKING-STORAGE SECTION.
01 WS-VARIABLES.
  05 WS-PGMNAME                 PIC X(08) VALUE 'CORPT00C'.       *[L37]
  05 WS-TRANID                  PIC X(04) VALUE 'CR00'.           *[L38]
  05 WS-MESSAGE                 PIC X(80) VALUE SPACES.           *[L39]
  05 WS-TRANSACT-FILE           PIC X(08) VALUE 'TRANSACT'.       *[L40]
  05 WS-ERR-FLG                 PIC X(01) VALUE 'N'.              *[L41]
    88 ERR-FLG-ON                         VALUE 'Y'.              *[L42]
    88 ERR-FLG-OFF                        VALUE 'N'.              *[L43]
  05 WS-TRANSACT-EOF            PIC X(01) VALUE 'N'.              *[L44]
    88 TRANSACT-EOF                       VALUE 'Y'.              *[L45]
    88 TRANSACT-NOT-EOF                   VALUE 'N'.              *[L46]
  05 WS-SEND-ERASE-FLG          PIC X(01) VALUE 'Y'.              *[L47]
    88 SEND-ERASE-YES                     VALUE 'Y'.              *[L48]
    88 SEND-ERASE-NO                      VALUE 'N'.              *[L49]
  05 WS-END-LOOP                PIC X(01) VALUE 'N'.              *[L50]
    88 END-LOOP-YES                       VALUE 'Y'.              *[L51]
    88 END-LOOP-NO                        VALUE 'N'.              *[L52]
  05 WS-RESP-CD                 PIC S9(09) COMP VALUE ZEROS.      *[L54]
  05 WS-REAS-CD                 PIC S9(09) COMP VALUE ZEROS.      *[L55]
  05 WS-REC-COUNT               PIC S9(04) COMP VALUE ZEROS.      *[L56]
  05 WS-IDX                     PIC S9(04) COMP VALUE ZEROS.      *[L57]
  05 WS-REPORT-NAME             PIC X(10) VALUE SPACES.           *[L58]
```

WS-REPORT-NAME at L58 holds one of three string values at runtime
(10-byte field, left-justified, padded with trailing spaces to 10 bytes):

- `'Monthly'` (7 chars text + 3 trailing spaces) — assigned at
  `app/cbl/CORPT00C.cbl:L214`
- `'Yearly'` (6 chars text + 4 trailing spaces) — assigned at
  `app/cbl/CORPT00C.cbl:L240`
- `'Custom'` (6 chars text + 4 trailing spaces) — assigned at
  `app/cbl/CORPT00C.cbl:L433`

The composite WS-START-DATE / WS-END-DATE structures at L60-L71 hold the
report-window dates in `YYYY-MM-DD` format with hyphen separators stored
as FILLER bytes:

```cobol
*[L60-L72] Date structures
  05 WS-START-DATE.                                                *[L60]
     10 WS-START-DATE-YYYY      PIC X(04) VALUE SPACES.            *[L61]
     10 FILLER                  PIC X(01) VALUE '-'.               *[L62]
     10 WS-START-DATE-MM        PIC X(02) VALUE SPACES.            *[L63]
     10 FILLER                  PIC X(01) VALUE '-'.               *[L64]
     10 WS-START-DATE-DD        PIC X(02) VALUE SPACES.            *[L65]
  05 WS-END-DATE.                                                  *[L66]
     10 WS-END-DATE-YYYY        PIC X(04) VALUE SPACES.            *[L67]
     10 FILLER                  PIC X(01) VALUE '-'.               *[L68]
     10 WS-END-DATE-MM          PIC X(02) VALUE SPACES.            *[L69]
     10 FILLER                  PIC X(01) VALUE '-'.               *[L70]
     10 WS-END-DATE-DD          PIC X(02) VALUE SPACES.            *[L71]
  05 WS-DATE-FORMAT             PIC X(10) VALUE 'YYYY-MM-DD'.      *[L72]
  05 WS-NUM-99                  PIC 99   VALUE 0.                  *[L74]
  05 WS-NUM-9999                PIC 9999 VALUE 0.                  *[L75]
  05 JCL-RECORD                 PIC X(80) VALUE ' '.               *[L79]
```

The CSUTLDTC-PARM passed to the external date-validation subprogram at
`app/cbl/CORPT00C.cbl:L129-L136`:

```cobol
*[L129-L136] CSUTLDTC parameters
01 CSUTLDTC-PARM.
  05 CSUTLDTC-DATE                   PIC X(10).                    *[L130]
  05 CSUTLDTC-DATE-FORMAT            PIC X(10).                    *[L131]
  05 CSUTLDTC-RESULT.                                              *[L132]
     10 CSUTLDTC-RESULT-SEV-CD       PIC X(04).                    *[L133]
     10 FILLER                       PIC X(11).                    *[L134]
     10 CSUTLDTC-RESULT-MSG-NUM      PIC X(04).                    *[L135]
     10 CSUTLDTC-RESULT-MSG          PIC X(61).                    *[L136]
```

Soft-error special case: `CSUTLDTC-RESULT-SEV-CD != '0000' AND
CSUTLDTC-RESULT-MSG-NUM = '2513'` is treated as continue-anyway per the
check at `app/cbl/CORPT00C.cbl:L396-L406` (start-date) and L416-L426
(end-date). MSG-NUM `'2513'` is a known soft-error code that the COBOL
program suppresses. Hard errors (any other non-zero SEV-CD) produce the
verbatim `'Start Date - Not a valid date...'` or `'End Date - Not a
valid date...'` message and short-circuit the validation cascade.

## Phase 2 — JCL Skeleton Inline Structure

JOB-DATA is the 17-line JCL skeleton hard-coded in WORKING-STORAGE at
`app/cbl/CORPT00C.cbl:L81-L125`. It is a fixed-width literal block (17
lines x 80 bytes = 1360 bytes) with four date-substitution sites that
get patched at runtime from `WS-START-DATE` and `WS-END-DATE`. A
REDEFINES clause re-overlays the block as an OCCURS array so the loop
at L498-L508 can index lines by ordinal.

```cobol
*[L81-L127] JOB-DATA structure
01 JOB-DATA.
 02 JOB-DATA-1.
  05 FILLER PIC X(80) VALUE
   "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,".              *[L83-L84]
  05 FILLER PIC X(80) VALUE
   "// NOTIFY=&SYSUID".                                             *[L85-L86]
  05 FILLER PIC X(80) VALUE "//*".                                  *[L87-L88]
  05 FILLER PIC X(80) VALUE
   "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')".                *[L89-L90]
  05 FILLER PIC X(80) VALUE "//*".                                  *[L91-L92]
  05 FILLER PIC X(80) VALUE
   "//STEP10 EXEC PROC=TRANREPT".                                   *[L93-L94]
  05 FILLER PIC X(80) VALUE "//*".                                  *[L95-L96]
  05 FILLER PIC X(80) VALUE
   "//STEP05R.SYMNAMES DD *".                                       *[L97-L98]
  05 FILLER PIC X(80) VALUE
   "TRAN-CARD-NUM,263,16,ZD".                                       *[L99-L100]
  05 FILLER PIC X(80) VALUE
   "TRAN-PROC-DT,305,10,CH".                                        *[L101-L102]
  05 FILLER-1.                                                      *[L103]
     10 FILLER PIC X(18) VALUE "PARM-START-DATE,C'".                *[L104-L105]
     10 PARM-START-DATE-1       PIC X(10) VALUE SPACES.             *[L106]
     10 FILLER PIC X(52) VALUE "'".                                 *[L107]
  05 FILLER-2.                                                      *[L108]
     10 FILLER PIC X(16) VALUE "PARM-END-DATE,C'".                  *[L109-L110]
     10 PARM-END-DATE-1         PIC X(10) VALUE SPACES.             *[L111]
     10 FILLER PIC X(54) VALUE "'".                                 *[L112]
  05 FILLER PIC X(80) VALUE "/*".                                   *[L113-L114]
  05 FILLER PIC X(80) VALUE
   "//STEP10R.DATEPARM DD *".                                       *[L115-L116]
  05 FILLER-3.                                                      *[L117]
     10 PARM-START-DATE-2       PIC X(10) VALUE SPACES.             *[L118]
     10 FILLER                  PIC X    VALUE SPACE.               *[L119]
     10 PARM-END-DATE-2         PIC X(10) VALUE SPACES.             *[L120]
     10 FILLER                  PIC X(59) VALUE SPACES.             *[L121]
  05 FILLER PIC X(80) VALUE "/*".                                   *[L122-L123]
  05 FILLER PIC X(80) VALUE "/*EOF".                                *[L124-L125]
 02 JOB-DATA-2 REDEFINES JOB-DATA-1.                                *[L126]
  05 JOB-LINES OCCURS 1000 TIMES PIC X(80).                         *[L127]
```

### Byte-Exact Substitution Sites

The four date-substitution sites are PIC X(10) ASCII placeholders, each
holding a `YYYY-MM-DD` date string at runtime. Their byte offsets within
the affected JCL line (1-based within the 80-byte line) are:

| JOB-LINES Index | Field Affected | Byte Range | Source Line |
|-----------------|----------------|------------|-------------|
| 11 | PARM-START-DATE-1 | bytes 19-28 (after 18-byte FILLER `"PARM-START-DATE,C'"`) | L106 |
| 12 | PARM-END-DATE-1 | bytes 17-26 (after 16-byte FILLER `"PARM-END-DATE,C'"`) | L111 |
| 15 | PARM-START-DATE-2 | bytes 1-10 (start of DATEPARM line) | L118 |
| 15 | PARM-END-DATE-2 | bytes 12-21 (after 1 SPACE separator) | L120 |

### Date Format Contract

All four substitution sites carry the same 10-byte ASCII format
`YYYY-MM-DD`. The Java translation MUST emit exactly 10 bytes for each
site; any deviation (zero-padding, hyphen position, byte ordering)
breaks byte-for-byte parity.

- Monthly mode: `<yyyy>-<mm>-01` (start) / `<yyyy>-<mm>-<last-day>` (end);
  computed via `DATE-OF-INTEGER` / `INTEGER-OF-DATE` arithmetic at
  `app/cbl/CORPT00C.cbl:L223-L230`. The "last day of month" is derived
  by adding 1 to month, rolling year if month > 12, setting day to 1,
  then subtracting 1 day via Lilian-date integer arithmetic.
- Yearly mode: `<yyyy>-01-01` (start) / `<yyyy>-12-31` (end); literal
  strings `'01'` and `'12'` / `'31'` MOVEd directly at L243-L253.
- Custom mode: user-supplied SDTMM / SDTDD / SDTYYYY and EDTMM / EDTDD /
  EDTYYYY, assembled into `<sdtyyyy>-<sdtmm>-<sdtdd>` and
  `<edtyyyy>-<edtmm>-<edtdd>` at L381-L386.

## Phase 3 — MAIN-PARA Dispatch

MAIN-PARA at `app/cbl/CORPT00C.cbl:L163-L202` is the entry paragraph
invoked by every CICS transaction CR00 dispatch. It branches on
`EIBCALEN` (first-time vs reentry), then on the commarea's
`CDEMO-PGM-CONTEXT` (initial display vs subsequent), and finally on
`EIBAID` (which AID key the user pressed).

### First-Time Entry (EIBCALEN = 0)

- Condition: `EIBCALEN = 0` at `app/cbl/CORPT00C.cbl:L172`
- Action: `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM` at L173, then
  `PERFORM RETURN-TO-PREV-SCREEN` at L174, which executes
  `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)`
  at L548-L551 — control transfers to the sign-on program. CORPT00C is
  never reached directly from the CICS terminal; all direct-entry
  attempts are routed to signon.

### Initial Display (CDEMO-PGM-ENTER)

- Condition: `EIBCALEN > 0 AND NOT CDEMO-PGM-REENTER` at
  `app/cbl/CORPT00C.cbl:L177`
- Action: `SET CDEMO-PGM-REENTER TO TRUE` at L178, `MOVE LOW-VALUES TO
  CORPT0AO` at L179, `MOVE -1 TO MONTHLYL OF CORPT0AI` at L180 (cursor
  lands on the MONTHLY field due to the BMS `IC` attribute combined with
  the explicit length-field cursor request), then `PERFORM
  SEND-TRNRPT-SCREEN` at L181.
- Note: `WS-MESSAGE` is SPACES on initial display (reset at L169); ERRMSGO
  is blank.

### EIBAID Dispatch

- Condition: `EIBCALEN > 0 AND CDEMO-PGM-REENTER` (i.e., user has been
  shown the screen and submitted a response)
- First: `PERFORM RECEIVE-TRNRPT-SCREEN` at `app/cbl/CORPT00C.cbl:L183`
  to pull the 3270 input into CORPT0AI
- Then: `EVALUATE EIBAID` with these 3 WHEN branches:

| WHEN | Action | Source |
|------|--------|--------|
| `DFHENTER` | `PERFORM PROCESS-ENTER-KEY` | `[L185-L186]` |
| `DFHPF3` | `MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM` then `PERFORM RETURN-TO-PREV-SCREEN` | `[L187-L189]` |
| OTHER | `MOVE 'Y' TO WS-ERR-FLG`, `MOVE -1 TO MONTHLYL OF CORPT0AI`, `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE`, `PERFORM SEND-TRNRPT-SCREEN` | `[L190-L194]` |

### Final RETURN

After every dispatch path that does NOT reach `RETURN-TO-PREV-SCREEN`,
control reaches the `EXEC CICS RETURN TRANSID(WS-TRANID)
COMMAREA(CARDDEMO-COMMAREA)` at `app/cbl/CORPT00C.cbl:L199-L202`. This
binds `TRANSID('CR00')` so the next user input on the same terminal
re-enters CORPT00C; the commarea carries the updated `CDEMO-PGM-REENTER`
and `CDEMO-FROM-*` fields. Note that SEND-TRNRPT-SCREEN itself ends with
`GO TO RETURN-TO-CICS` at L580, which dispatches to the separate
RETURN-TO-CICS paragraph at L585-L591 issuing an identical RETURN. Both
sites produce the same observable behavior.

## Phase 4 — PROCESS-ENTER-KEY (Mode Selection)

PROCESS-ENTER-KEY at `app/cbl/CORPT00C.cbl:L208-L456` emits the
unconditional `DISPLAY 'PROCESS ENTER KEY'` at L210 BEFORE the outer
EVALUATE; this DISPLAY is the first observable side effect of every
ENTER-key dispatch and is captured to `stdout.txt`.

### Outer EVALUATE TRUE (4 branches)

| WHEN | Predicate | Action | Source |
|------|-----------|--------|--------|
| Monthly | `MONTHLYI NOT = SPACES AND LOW-VALUES` | Compute current month range, PERFORM SUBMIT-JOB-TO-INTRDR | `[L213-L238]` |
| Yearly | `YEARLYI NOT = SPACES AND LOW-VALUES` | Compute current year range, PERFORM SUBMIT-JOB-TO-INTRDR | `[L239-L255]` |
| Custom | `CUSTOMI NOT = SPACES AND LOW-VALUES` | Inner EVALUATE with 6 empty checks + NUMVAL-C normalization + 6 format checks + 2 CSUTLDTC calls, then PERFORM SUBMIT-JOB-TO-INTRDR | `[L256-L436]` |
| OTHER | All radios blank/LOW-VALUES | Emit `'Select a report type to print report...'`, set ERR-FLG, cursor MONTHLYL, PERFORM SEND-TRNRPT-SCREEN | `[L437-L442]` |

### Monthly Branch Date Computation

At `app/cbl/CORPT00C.cbl:L213-L238` the COBOL builds the current-month
window using `FUNCTION CURRENT-DATE` and integer-of-date arithmetic:

```cobol
MOVE 'Monthly'   TO WS-REPORT-NAME                                  *[L214]
MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                      *[L215]
MOVE WS-CURDATE-YEAR     TO WS-START-DATE-YYYY                      *[L217]
MOVE WS-CURDATE-MONTH    TO WS-START-DATE-MM                        *[L218]
MOVE '01'                TO WS-START-DATE-DD                        *[L219]
MOVE WS-START-DATE       TO PARM-START-DATE-1                       *[L220]
                            PARM-START-DATE-2                       *[L221]
MOVE 1              TO WS-CURDATE-DAY                               *[L223]
ADD 1               TO WS-CURDATE-MONTH                             *[L224]
IF WS-CURDATE-MONTH > 12                                            *[L225]
    ADD 1           TO WS-CURDATE-YEAR                              *[L226]
    MOVE 1          TO WS-CURDATE-MONTH                             *[L227]
END-IF                                                              *[L228]
COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(                    *[L229]
        FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)                 *[L230]
MOVE WS-CURDATE-YEAR     TO WS-END-DATE-YYYY                        *[L232]
MOVE WS-CURDATE-MONTH    TO WS-END-DATE-MM                          *[L233]
MOVE WS-CURDATE-DAY      TO WS-END-DATE-DD                          *[L234]
MOVE WS-END-DATE         TO PARM-END-DATE-1                         *[L235]
                            PARM-END-DATE-2                         *[L236]
PERFORM SUBMIT-JOB-TO-INTRDR                                        *[L238]
```

`FUNCTION CURRENT-DATE` invocation requires a deterministic `Clock` in
the Java translation per AAP §0.6.4. Captured scenarios that exercise
the Monthly branch MUST bind a `ScopedValue<Clock>` to a fixed value
(per AAP §0.6.6) so the captured BMS bytes, the substituted JCL skeleton,
and the success message are deterministic.

### Yearly Branch Date Computation

At `app/cbl/CORPT00C.cbl:L239-L255` the COBOL builds the current-year
window using literal `'01'` and `'12'` / `'31'` MOVEs:

```cobol
MOVE 'Yearly'   TO WS-REPORT-NAME                                   *[L240]
MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA                      *[L241]
MOVE WS-CURDATE-YEAR     TO WS-START-DATE-YYYY                      *[L243]
                            WS-END-DATE-YYYY                        *[L244]
MOVE '01'                TO WS-START-DATE-MM                        *[L245]
                            WS-START-DATE-DD                        *[L246]
MOVE WS-START-DATE       TO PARM-START-DATE-1                       *[L247]
                            PARM-START-DATE-2                       *[L248]
MOVE '12'                TO WS-END-DATE-MM                          *[L250]
MOVE '31'                TO WS-END-DATE-DD                          *[L251]
MOVE WS-END-DATE         TO PARM-END-DATE-1                         *[L252]
                            PARM-END-DATE-2                         *[L253]
PERFORM SUBMIT-JOB-TO-INTRDR                                        *[L255]
```

The Yearly branch also requires a deterministic `Clock` to fix the
current year.

### Custom Branch Validation Cascade

The Custom branch at `app/cbl/CORPT00C.cbl:L256-L436` performs 14
validations in strict order. Each validation sets ERR-FLG, sets the
appropriate cursor field (MOVE -1 TO `<field>L`), and PERFORMs
SEND-TRNRPT-SCREEN. Validation short-circuits at the first failure
because SEND-TRNRPT-SCREEN ends with `GO TO RETURN-TO-CICS` at L580.

| # | Predicate | Error Message | Source |
|---|-----------|---------------|--------|
| 1 | SDTMMI = SPACES OR LOW-VALUES | `'Start Date - Month can NOT be empty...'` | L261-L262 |
| 2 | SDTDDI = SPACES OR LOW-VALUES | `'Start Date - Day can NOT be empty...'` | L268-L269 |
| 3 | SDTYYYYI = SPACES OR LOW-VALUES | `'Start Date - Year can NOT be empty...'` | L275-L276 |
| 4 | EDTMMI = SPACES OR LOW-VALUES | `'End Date - Month can NOT be empty...'` | L282-L283 |
| 5 | EDTDDI = SPACES OR LOW-VALUES | `'End Date - Day can NOT be empty...'` | L289-L290 |
| 6 | EDTYYYYI = SPACES OR LOW-VALUES | `'End Date - Year can NOT be empty...'` | L296-L297 |
| 7 | SDTMMI not numeric OR > '12' | `'Start Date - Not a valid Month...'` | L331-L332 |
| 8 | SDTDDI not numeric OR > '31' | `'Start Date - Not a valid Day...'` | L340-L341 |
| 9 | SDTYYYYI not numeric | `'Start Date - Not a valid Year...'` | L348-L349 |
| 10 | EDTMMI not numeric OR > '12' | `'End Date - Not a valid Month...'` | L357-L358 |
| 11 | EDTDDI not numeric OR > '31' | `'End Date - Not a valid Day...'` | L366-L367 |
| 12 | EDTYYYYI not numeric | `'End Date - Not a valid Year...'` | L374-L375 |
| 13 | CSUTLDTC(start-date) fails (SEV-CD != '0000' AND MSG-NUM != '2513') | `'Start Date - Not a valid date...'` | L400-L401 |
| 14 | CSUTLDTC(end-date) fails (SEV-CD != '0000' AND MSG-NUM != '2513') | `'End Date - Not a valid date...'` | L420-L421 |

Validations 1-6 are an inner EVALUATE TRUE at L258-L303. Validations
7-12 are six sequential IF blocks at L329-L379 that operate on the
NUMVAL-C-normalized field values from L305-L327. Validations 13-14 are
the two `CALL 'CSUTLDTC'` checks at L392-L406 (start) and L412-L426
(end).

## Phase 5 — CSUTLDTC Date Validation

### CSUTLDTC-PARM Structure

Documented in Phase 1 above; see `app/cbl/CORPT00C.cbl:L129-L136`.

### Call Sites

Two CALL sites exist in the Custom branch, both invoking the external
date-validation subprogram `CSUTLDTC` (whose source lives at
`app/cbl/CSUTLDTC.cbl`):

- Start-date validation at `app/cbl/CORPT00C.cbl:L392-L394`:

```cobol
CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                        CSUTLDTC-DATE-FORMAT
                        CSUTLDTC-RESULT
```

  Preceded by `MOVE WS-START-DATE TO CSUTLDTC-DATE` at L388, `MOVE
  WS-DATE-FORMAT TO CSUTLDTC-DATE-FORMAT` at L389, and `MOVE SPACES TO
  CSUTLDTC-RESULT` at L390.

- End-date validation at `app/cbl/CORPT00C.cbl:L412-L414`: identical
  three-parameter signature. Preceded by `MOVE WS-END-DATE TO
  CSUTLDTC-DATE` at L408, `MOVE WS-DATE-FORMAT TO CSUTLDTC-DATE-FORMAT`
  at L409, and `MOVE SPACES TO CSUTLDTC-RESULT` at L410.

### SEV-CD / MSG-NUM Logic (Soft-Error Special Case)

The post-call check at `app/cbl/CORPT00C.cbl:L396-L406` (start-date) and
L416-L426 (end-date) handles three cases:

| Case | SEV-CD | MSG-NUM | Action |
|------|--------|---------|--------|
| Success | `'0000'` | (any) | CONTINUE |
| Soft-error pass-through | != `'0000'` | `'2513'` | CONTINUE (treat as valid) |
| Hard error | != `'0000'` | != `'2513'` | Emit `'Start Date - Not a valid date...'` or `'End Date - Not a valid date...'` |

Java translation: a `DateValidator` port (AAP §0.4.1 `CSUTLDTC ->
DateValidator`) replaces the LE service call with `LocalDate.parse(...)`
using `ResolverStyle.STRICT`. The SAME SEV-CD / MSG-NUM contract MUST
be preserved on the port's return value so the soft-error pass-through
and hard-error paths produce byte-identical observable behavior. The
port's return type is recommended as a sealed result hierarchy
(permits Success, SoftError, HardError); the use case switches with
exhaustive pattern matching.

## Phase 6 — SUBMIT-JOB-TO-INTRDR (JCL Loop)

SUBMIT-JOB-TO-INTRDR at `app/cbl/CORPT00C.cbl:L462-L510` is invoked from
all three non-OTHER outer EVALUATE branches (Monthly, Yearly, Custom).
It performs confirm-prompt validation, Y/N normalization, and the JCL
emission loop.

### Empty CONFIRM Check

At `app/cbl/CORPT00C.cbl:L464-L474`:

```cobol
IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES                      *[L464]
    STRING                                                           *[L465]
      'Please confirm to print the '                                *[L466]
                        DELIMITED BY SIZE
      WS-REPORT-NAME    DELIMITED BY SPACE                          *[L468]
      ' report...'      DELIMITED BY SIZE                           *[L469]
      INTO WS-MESSAGE                                                *[L470]
    MOVE 'Y'     TO WS-ERR-FLG                                       *[L471]
    MOVE -1       TO CONFIRML OF CORPT0AI                            *[L472]
    PERFORM SEND-TRNRPT-SCREEN                                       *[L473]
END-IF                                                               *[L474]
```

The composed message takes shape based on WS-REPORT-NAME: for example,
`'Please confirm to print the Monthly report...'` when WS-REPORT-NAME =
`'Monthly'`. The `DELIMITED BY SPACE` clause on WS-REPORT-NAME strips
the trailing padding from the 10-byte field.

### Y/N Normalization

At `app/cbl/CORPT00C.cbl:L476-L494`:

| WHEN | Action | Source |
|------|--------|--------|
| `CONFIRMI = 'Y' OR 'y'` | CONTINUE (proceed to JCL loop) | L478-L479 |
| `CONFIRMI = 'N' OR 'n'` | PERFORM INITIALIZE-ALL-FIELDS, set ERR-FLG, PERFORM SEND-TRNRPT-SCREEN (no TDQ writes) | L480-L483 |
| OTHER | Build message (`'"' + CONFIRMI + '" is not a valid value to confirm...'`), set ERR-FLG, cursor CONFIRML, PERFORM SEND-TRNRPT-SCREEN | L484-L493 |

The N/n abort branch at L480-L483 sets the err-flag to 'Y' so the
caller's `IF NOT ERR-FLG-ON` guard at L434 / L445 suppresses the success
message — the screen redisplays with a blank ERRMSGO and the cursor on
MONTHLY (per INITIALIZE-ALL-FIELDS at L633).

### VARYING WS-IDX Loop

At `app/cbl/CORPT00C.cbl:L498-L508`:

```cobol
PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000 OR            *[L498]
                       END-LOOP-YES  OR ERR-FLG-ON                  *[L499]
    MOVE JOB-LINES(WS-IDX) TO JCL-RECORD                             *[L501]
    IF JCL-RECORD = '/*EOF' OR                                       *[L502]
       JCL-RECORD = SPACES OR LOW-VALUES                             *[L503]
        SET END-LOOP-YES TO TRUE                                     *[L504]
    END-IF                                                           *[L505]
    PERFORM WIRTE-JOBSUB-TDQ                                         *[L507]
END-PERFORM                                                          *[L508]
```

Terminator detection: `'/*EOF'` (5 ASCII bytes) followed by SPACES to
fill 80 bytes. The terminator IS written to TDQ BEFORE the loop exits
because the `PERFORM WIRTE-JOBSUB-TDQ` at L507 executes before the loop
condition is re-evaluated. For nominal scenarios this produces exactly
17 TDQ writes total: lines 1-16 of JOB-DATA-1 plus the `/*EOF` marker
at line 17.

### Success Message Emission

After END-EVALUATE in PROCESS-ENTER-KEY (the outer EVALUATE at L212),
the success block at `app/cbl/CORPT00C.cbl:L445-L455`:

```cobol
IF NOT ERR-FLG-ON                                                    *[L445]
    PERFORM INITIALIZE-ALL-FIELDS                                    *[L447]
    MOVE DFHGREEN           TO ERRMSGC  OF CORPT0AO                  *[L448]
    STRING WS-REPORT-NAME   DELIMITED BY SPACE                       *[L449]
      ' report submitted for printing ...'                           *[L450]
                            DELIMITED BY SIZE
      INTO WS-MESSAGE                                                *[L452]
    MOVE -1       TO MONTHLYL OF CORPT0AI                            *[L453]
    PERFORM SEND-TRNRPT-SCREEN                                       *[L454]
END-IF.                                                              *[L456]
```

Note the SPACE character in `' report submitted for printing ...'`
BEFORE the `...` — this is verified from `app/cbl/CORPT00C.cbl:L450`
and is the ONLY message in CORPT00C with that whitespace. The
`MOVE DFHGREEN TO ERRMSGC OF CORPT0AO` at L448 sets the ERRMSG field
color to green for success (contrast: error messages render in default
red via the BMS COLOR=RED attribute on ERRMSG at `app/bms/CORPT00.bms:L219`).

## Phase 7 — WIRTE-JOBSUB-TDQ (preserve typo)

The paragraph name is `WIRTE-JOBSUB-TDQ` (note the `WIRTE` typo — letters
WIRTE, NOT WRITE) at `app/cbl/CORPT00C.cbl:L515`. Per AAP §0.7.1 Minimal
Change Clause, this typo MUST be preserved verbatim in all citations and
in Javadoc comments that document the COBOL paragraph correspondence.
Java method names that reflect this paragraph SHOULD use a normalized
spelling (e.g., `writeJobSubTdq`) accompanied by a Javadoc note `// preserves
COBOL paragraph WIRTE-JOBSUB-TDQ typo`.

### EXEC CICS WRITEQ TD QUEUE('JOBS')

At `app/cbl/CORPT00C.cbl:L517-L523`:

```cobol
EXEC CICS WRITEQ TD                                                  *[L517]
  QUEUE ('JOBS')                                                     *[L518]
  FROM (JCL-RECORD)                                                  *[L519]
  LENGTH (LENGTH OF JCL-RECORD)                                      *[L520]
  RESP(WS-RESP-CD)                                                   *[L521]
  RESP2(WS-REAS-CD)                                                  *[L522]
END-EXEC.                                                            *[L523]
```

In Java: this is translated to direct method invocation on a
`JclSkeletonSink` port (per AAP §0.4.1 IMPLEMENTATION DECISION). Each
WRITEQ TD becomes one observable port call. The port's `submit(byte[]
line80)` method takes the 80-byte JCL line; the port's contract is that
submission cannot fail except via a typed exception that maps back to a
non-NORMAL CICS response code for fixture verification of the
simulate-fault scenario (Scenario 15).

### Response Code EVALUATE

At `app/cbl/CORPT00C.cbl:L525-L535`:

| WHEN | Action | Source |
|------|--------|--------|
| `DFHRESP(NORMAL)` | CONTINUE | L526-L527 |
| OTHER | `DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` ; set ERR-FLG; `MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE`; cursor MONTHLYL; PERFORM SEND-TRNRPT-SCREEN | L528-L534 |

Java translation: the port throws a typed `JclSubmissionException`
carrying the simulated RESP / REAS codes; the use case catches, logs via
SLF4J (NOT `System.out`), emits the verbatim message
`'Unable to Write TDQ (JOBS)...'` to ERRMSGO, and re-renders the screen.
The `DISPLAY 'RESP:' ... 'REAS:' ...` from L529 becomes an SLF4J entry
of the form `LOGGER.error("RESP:{} REAS:{}", respCode, reasCode)` per
AAP §0.7.2 (production code never writes to `System.out` or
`System.err`).

## Phase 8 — SEND/RECEIVE/POPULATE-HEADER-INFO/RETURN-TO-PREV-SCREEN

### SEND-TRNRPT-SCREEN

At `app/cbl/CORPT00C.cbl:L556-L580`:

- L558: PERFORM POPULATE-HEADER-INFO
- L560: `MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO`
- L562: dual branch on the SEND-ERASE flag:
  - `IF SEND-ERASE-YES` (initial display) -> `EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00') FROM(CORPT0AO) ERASE CURSOR` at L563-L569
  - ELSE (subsequent renders) -> identical SEND but WITHOUT `ERASE` (the keyword is commented out at L575) at L571-L577
- L580: unconditional `GO TO RETURN-TO-CICS` (COBOL `GO TO`, not PERFORM); control transfers into RETURN-TO-CICS at L585-L591 which issues `EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)`.

### RECEIVE-TRNRPT-SCREEN

At `app/cbl/CORPT00C.cbl:L596-L604`:

```cobol
EXEC CICS RECEIVE                                                    *[L598]
          MAP('CORPT0A')                                             *[L599]
          MAPSET('CORPT00')                                          *[L600]
          INTO(CORPT0AI)                                             *[L601]
          RESP(WS-RESP-CD)                                           *[L602]
          RESP2(WS-REAS-CD)                                          *[L603]
END-EXEC.                                                            *[L604]
```

Note: RESP code handling on RECEIVE is implicit — the COBOL never
examines WS-RESP-CD after RECEIVE; non-NORMAL receive is not handled.
The program proceeds and uses whatever bytes are in CORPT0AI. The Java
translation MUST preserve this — do not introduce an exception path
that the COBOL does not produce.

### POPULATE-HEADER-INFO

At `app/cbl/CORPT00C.cbl:L609-L628`:

- L611: `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA`
- L613: MOVE CCDA-TITLE01 (`'      AWS Mainframe Modernization       '`, 40 bytes — 6 leading + 7 trailing spaces) TO TITLE01O — from `app/cpy/COTTL01Y.cpy:L18-L19`
- L614: MOVE CCDA-TITLE02 (`'              CardDemo                  '`, 40 bytes — 14 leading + 18 trailing spaces) TO TITLE02O — from `app/cpy/COTTL01Y.cpy:L20-L22`
- L615: MOVE WS-TRANID (`'CR00'`) TO TRNNAMEO
- L616: MOVE WS-PGMNAME (`'CORPT00C'`) TO PGMNAMEO
- L618-L622: build CURDATEO as `MM/DD/YY` (8 bytes) from WS-CURDATE-MM-DD-YY (`app/cpy/CSDAT01Y.cpy:L30-L35`)
- L624-L628: build CURTIMEO as `HH:MM:SS` (8 bytes) from WS-CURTIME-HH-MM-SS (`app/cpy/CSDAT01Y.cpy:L36-L41`)

`FUNCTION CURRENT-DATE` at L611 requires a deterministic `Clock` via
`ScopedValue<Clock>` per AAP §0.6.4 / §0.6.6 — without it, captured
CURDATEO/CURTIMEO bytes vary between runs and golden-record comparison
fails spuriously.

### RETURN-TO-PREV-SCREEN

At `app/cbl/CORPT00C.cbl:L540-L551`:

```cobol
IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES                           *[L542]
    MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM                              *[L543]
END-IF                                                               *[L544]
MOVE WS-TRANID    TO CDEMO-FROM-TRANID                               *[L545]
MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM                              *[L546]
MOVE ZEROS        TO CDEMO-PGM-CONTEXT                               *[L547]
EXEC CICS                                                            *[L548]
    XCTL PROGRAM(CDEMO-TO-PROGRAM)                                   *[L549]
    COMMAREA(CARDDEMO-COMMAREA)                                      *[L550]
END-EXEC.                                                            *[L551]
```

`MOVE ZEROS TO CDEMO-PGM-CONTEXT` at L547 sets the context to 0 — the
88-level condition `CDEMO-PGM-ENTER VALUE 0` (from
`app/cpy/COCOM01Y.cpy:L30`) becomes true on the receiving program, so
the destination treats the call as a first-time entry.

## Phase 9 — INITIALIZE-ALL-FIELDS

At `app/cbl/CORPT00C.cbl:L633-L646`:

- `MOVE -1 TO MONTHLYL OF CORPT0AI` at L635 — cursor lands on MONTHLY
- `INITIALIZE` at L636-L646 resets these 11 fields to their default
  values (SPACES for PIC X(n) fields; the COBOL `INITIALIZE` verb fills
  each field with its standard default per the field's PICTURE):
  - MONTHLYI, YEARLYI, CUSTOMI (3 radios; PIC X(1))
  - SDTMMI, SDTDDI, SDTYYYYI (3 start-date components; PIC X(2), X(2), X(4))
  - EDTMMI, EDTDDI, EDTYYYYI (3 end-date components; PIC X(2), X(2), X(4))
  - CONFIRMI (Y/N; PIC X(1))
  - WS-MESSAGE (working-storage error/success message; PIC X(80))

This paragraph is invoked from two sites: SUBMIT-JOB-TO-INTRDR at L481
(when the user types N/n to abort) and the success block at L447 (after
a successful JCL submit), so the screen is cleared for the next
operation in both cases.

## Phase 10 — BMS Map CORPT0A Layout (24x80)

### Screen Geometry

- 24 rows x 80 columns
- Mapset name: `CORPT00` (declared by `CORPT00 DFHMSD` at
  `app/bms/CORPT00.bms:L19`)
- Map name: `CORPT0A` (declared by `CORPT0A DFHMDI` at
  `app/bms/CORPT00.bms:L26`; `SIZE=(24,80)` at L28)
- BMS source: `app/bms/CORPT00.bms` (231 lines)
- Symbolic copy: `app/cpy-bms/CORPT00.CPY` (224 lines); CORPT0AI starts
  at L17; CORPT0AO REDEFINES CORPT0AI starts at L121

### Editable Fields (10 user-modifiable + 1 ERRMSG output)

| Symbolic Name | Position (row, col) | Length | Attribute | Notes |
|---------------|--------------------:|-------:|-----------|-------|
| MONTHLY | (7, 10) | 1 | FSET,IC,NORM,UNPROT, GREEN, UNDERLINE | Monthly radio; IC = initial cursor |
| YEARLY | (9, 10) | 1 | FSET,NORM,UNPROT, GREEN, UNDERLINE | Yearly radio |
| CUSTOM | (11, 10) | 1 | FSET,NORM,UNPROT, GREEN, UNDERLINE | Custom radio |
| SDTMM | (13, 29) | 2 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | Start Date Month MM |
| SDTDD | (13, 34) | 2 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | Start Date Day DD |
| SDTYYYY | (13, 39) | 4 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | Start Date Year YYYY |
| EDTMM | (14, 29) | 2 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | End Date Month MM |
| EDTDD | (14, 34) | 2 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | End Date Day DD |
| EDTYYYY | (14, 39) | 4 | FSET,NORM,NUM,UNPROT, GREEN, UNDERLINE | End Date Year YYYY |
| CONFIRM | (19, 66) | 1 | FSET,NORM,UNPROT, GREEN, UNDERLINE | Confirm Y/N |
| ERRMSG | (23, 1) | 78 | ASKIP,BRT,FSET, RED | Error message line; color set dynamically by ERRMSGC at runtime |

Field positions verified from `app/bms/CORPT00.bms` `DFHMDF` directives
at L80-L84 (MONTHLY), L94-L99 (YEARLY), L108-L113 (CUSTOM), L127-L132
(SDTMM), L138-L143 (SDTDD), L149-L154 (SDTYYYY), L166-L171 (EDTMM),
L177-L182 (EDTDD), L188-L193 (EDTYYYY), L206-L210 (CONFIRM), L218-L221
(ERRMSG). If a row/col here differs from BMS source, the BMS source
wins.

### Color/Attribute Map

- Editable input fields: COLOR=GREEN, HILIGHT=UNDERLINE, ATTRB=(UNPROT,FSET)
- Static text labels (e.g., 'Monthly (Current Month)' at (7,15)): COLOR=TURQUOISE, ATTRB=(ASKIP,BRT)
- Titles TITLE01 (1,21) / TITLE02 (2,21): COLOR=YELLOW, ATTRB=(ASKIP,FSET,NORM)
- Header labels and value fields (TRNNAME, PGMNAME, CURDATE, CURTIME) rows 1-2: COLOR=BLUE
- Section banner 'Transaction Reports' at (4,30): COLOR=NEUTRAL, ATTRB=(ASKIP,BRT)
- Date separators `/` at rows 13-14 cols 32/37 and format hint '(MM/DD/YYYY)' at (13-14,46): COLOR=BLUE
- Confirm prompt at (19,6): COLOR=TURQUOISE; '(Y/N)' hint at (19,69): COLOR=NEUTRAL
- ERRMSG at (23,1): BMS COLOR=RED default; the application overrides at runtime via ERRMSGC — `MOVE DFHGREEN TO ERRMSGC OF CORPT0AO` at L448 sets ERRMSG GREEN for success
- Footer at (24,1): `'ENTER=Continue  F3=Back'` COLOR=YELLOW at `app/bms/CORPT00.bms:L222-L226`

## Phase 11 — Required Test Scenarios

The 15 scenarios are documented in detail in `input_scenario.txt` (same
folder); this section provides the high-level binding inventory. Each
scenario is keyed by a snake_case name that the Java test class uses to
look up its expected outputs.

| # | Scenario Name | EIBAID / AID | Key Fields | Expected Outcome |
|---|---------------|--------------|------------|------------------|
| 1 | `first_time_entry` | (none, EIBCALEN=0) | — | XCTL COSGN00C |
| 2 | `initial_display` | DFHENTER | (CDEMO-PGM-ENTER) | LOW-VALUES SEND, cursor MONTHLYL |
| 3 | `pf3_to_main_menu` | DFHPF3 | — | XCTL COMEN01C |
| 4 | `invalid_aid_pf12` | DFHPF12 | — | `'Invalid key pressed. Please see below...         '` (50-byte CCDA-MSG-INVALID-KEY) |
| 5 | `no_report_selected` | DFHENTER | radios SPACES | `'Select a report type to print report...'` |
| 6 | `monthly_no_confirm` | DFHENTER | MONTHLY=Y, CONFIRM=SPACES | `'Please confirm to print the Monthly report...'` |
| 7 | `monthly_confirm_y` | DFHENTER | MONTHLY=Y, CONFIRM=Y, CLOCK=2024-03-15 | 17 TDQ writes + `'Monthly report submitted for printing ...'` |
| 8 | `yearly_confirm_y` | DFHENTER | YEARLY=Y, CONFIRM=Y, CLOCK=2024-06-10 | 17 TDQ writes + `'Yearly report submitted for printing ...'` |
| 9 | `custom_valid_dates_confirm_y` | DFHENTER | CUSTOM=Y, valid dates, CONFIRM=Y | 17 TDQ writes + `'Custom report submitted for printing ...'` |
| 10 | `custom_empty_sdtmm` | DFHENTER | CUSTOM=Y, SDTMM=SPACES | `'Start Date - Month can NOT be empty...'` |
| 11 | `custom_invalid_sdtmm_13` | DFHENTER | CUSTOM=Y, SDTMM=13 | `'Start Date - Not a valid Month...'` |
| 12 | `custom_invalid_date_feb_30` | DFHENTER | CUSTOM=Y, SDTMM=02 SDTDD=30 SDTYYYY=2024 | `'Start Date - Not a valid date...'` |
| 13 | `confirm_invalid_x` | DFHENTER | MONTHLY=Y, CONFIRM=X | `'"X" is not a valid value to confirm...'` |
| 14 | `confirm_n_aborts` | DFHENTER | MONTHLY=Y, CONFIRM=N | INITIALIZE-ALL-FIELDS, no TDQ writes |
| 15 | `tdq_write_fault` | DFHENTER | MONTHLY=Y, CONFIRM=Y, SIMULATE_TDQ_FAULT QIDERR | `'Unable to Write TDQ (JOBS)...'` |

### Determinism Invariant

Every scenario that exercises Monthly mode, Yearly mode, or
POPULATE-HEADER-INFO (which is every scenario that invokes
SEND-TRNRPT-SCREEN) MUST set `CLOCK_FIXED` via a directive in
`input_scenario.txt` (per AAP §0.6.4 / §0.6.6). Without a fixed Clock,
captured BMS bytes and JCL substitutions are non-deterministic and the
golden-record comparison fails spuriously. The Java test harness binds
the Clock through `ScopedValue.where(Clocks.SCOPED, fixedClock).run(()
-> useCase.execute(...))` and the use case retrieves the Clock via
`Clocks.SCOPED.get()`.

## Phase 12 — Files in This Directory

| File | Purpose | Format | Status |
|------|---------|--------|--------|
| `README.md` | THIS file. Authoritative byte-for-byte contract per AAP §0.6.11 | UTF-8 no-BOM Markdown, LF-only | CREATED (this PR) |
| `input_scenario.txt` | CICS pseudo-conversation driver script (15 scenarios) consumed by `CoRpt00CGoldenTest` | ASCII LF-only directive grammar | CREATED (this PR) |
| `stdout.txt` | Placeholder for captured COBOL DISPLAY output (`'PROCESS ENTER KEY'` + RESP/REAS); replaced by canonical capture per `java/MIGRATION_NOTES.md §1.6` | ASCII LF-only placeholder | CREATED (this PR; placeholder) |
| `bms_output.txt` | Placeholder for captured BMS SEND MAP frame bytes (multiple frames across 15 scenarios); replaced by canonical capture per `java/MIGRATION_NOTES.md §1.6` | ASCII LF-only placeholder | CREATED (this PR; placeholder) |

Note: 4 files only — JCL skeleton assertions are encoded as
`EXPECT_TDQ_LINE` directives inside `input_scenario.txt`, NOT as a
separate `jcl_skeleton.txt` file. This aligns with AAP §0.2.1 explicit
enumeration of 4 fixture files for CORPT00C.

## Phase 13 — Structural Invariants

### Online-to-Batch Bridge Semantics

CORPT00C is a submission form, not a CRUD or browse form. The only
persistent observable effect is the sequence of TDQ writes (translated
to JclSkeletonSink port invocations). No VSAM file is opened, written
to, deleted from, or browsed. The COPY of CVTRA05Y at
`app/cbl/CORPT00C.cbl:L146` and the WS-TRANSACT-FILE literal at L40 are
vestigial declarations — translate faithfully and flag in
`java/MIGRATION_NOTES.md`.

### Mutually-Exclusive Report Modes

At most ONE of MONTHLYI / YEARLYI / CUSTOMI is non-blank in any single
ENTER dispatch. The COBOL outer EVALUATE TRUE at L212-L443 selects the
FIRST non-blank branch (Monthly precedes Yearly precedes Custom). The
BMS map does NOT enforce exclusivity, but COBOL's first-match semantic
wins. The Java translation MUST preserve this precedence — a
pattern-matching switch on a sealed `ReportMode` is acceptable ONLY IF
upstream construction collapses ambiguous input to the first-non-blank
radio; otherwise the use case MUST test the three radios in source order.

### JCL Skeleton Byte Format

Exactly 17 lines x 80 bytes = 1360 bytes per submission. Substitution
sites at JOB-LINES indices 11, 12, and 15. Loop terminator at index 17
(`'/*EOF'` + 75 trailing SPACES). Java translation MUST preserve
byte-exact substitution: each PIC X(10) date placeholder is overwritten
in place; surrounding FILLER bytes (the `"PARM-START-DATE,C'"` and
`"PARM-END-DATE,C'"` prefixes, the trailing apostrophe literals, and
the SPACE separator on the DATEPARM line) MUST be preserved verbatim.

### CSUTLDTC Soft-Error Handling

MSG-NUM `'2513'` is a soft-error code that COBOL treats as
continue-anyway. This special case MUST be preserved in the Java
`DateValidator` port contract per AAP §0.7.1. The Java
`com.blitzy.carddemo.application.util.DateValidator` (per AAP §0.4.1
`CSUTLDTC -> DateValidator`) returns a result whose discriminator carries
the SEV-CD and MSG-NUM equivalents; the use case's pattern-matching
switch handles success, soft-error, and hard-error permits exhaustively.

### WIRTE-JOBSUB-TDQ Typo Preservation

The paragraph name at `app/cbl/CORPT00C.cbl:L515` is `WIRTE-JOBSUB-TDQ`
(NOT `WRITE-`). Per AAP §0.7.1 Minimal Change Clause, this typo MUST be
preserved in COBOL-source citations and comments. Recommended Java
method name: `writeJobSubTdq` with Javadoc note `// preserves COBOL
paragraph WIRTE-JOBSUB-TDQ typo` for traceability.

## Supplementary 1 — Contrast Matrix

This table contrasts CORPT00C against the two sibling fixtures already
documented in this repository (COSGN00C and COTRN00C), so the
distinguishing structural features of CORPT00C are visible at a glance.

| Property | COSGN00C | COTRN00C | CORPT00C |
|----------|----------|----------|----------|
| CICS Transaction | CC00 | CT00 | CR00 |
| Primary Function | Sign-On | Transaction List (read-only browse) | Report Submission (online-to-batch bridge) |
| VSAM File Access | READ USRSEC | STARTBR/READNEXT/READPREV/ENDBR TRANSACT | NONE (CVTRA05Y COPYed but unused — dead code) |
| TDQ Write | None | None | YES (17 lines to TDQ JOBS) |
| External CALL | None | None | CALL 'CSUTLDTC' twice |
| Pagination | N/A | YES (10 rows/page, PF7/PF8) | NO |
| Editable Fields | USERIDI, PASSWDI (PII) | TRNIDIN + row selectors | MONTHLYI/YEARLYI/CUSTOMI + 6 date components + CONFIRMI |
| AID Keys Handled | ENTER, PF3 | ENTER, PF3, PF7, PF8 | ENTER, PF3 |
| PF3 Destination | (session exit) | COMEN01C | COMEN01C |
| First-Entry Destination | (immediate display) | (immediate display) | COSGN00C |
| Security Concern | Password masking (DRK) | N/A | N/A (no PII on screen) |
| 16-Digit PAN | N/A | YES (masked) | N/A |
| FUNCTION CURRENT-DATE | YES (header only) | YES (header only) | YES (header + Monthly/Yearly date computation — Clock-critical) |
| WORKING-STORAGE EVALUATE | Minimal | EIBAID 4-branch | EIBAID 3-branch + outer 4-branch report-mode + inner 14-branch custom-validation |
| Deterministic Clock Required | YES (header) | YES (header) | YES (header + Monthly + Yearly) |

## Supplementary 2 — Verbatim COBOL Message Catalog

This is the complete catalog of all messages emitted by CORPT00C, with
EXACT spelling, byte length, and source line citation. The Java
translation MUST emit each message byte-for-byte identical to the
COBOL literal; any deviation (Unicode ellipsis, missing trailing space,
period instead of three periods, etc.) is a defect.

| # | Verbatim Bytes (inside backticks + single quotes) | Byte Length | Source Line | Emission Context |
|---|---------------------------------------------------|-------------|-------------|-----------------|
| 1 | `` `'Invalid key pressed. Please see below...         '` `` | 49 (literal) | `app/cpy/CSMSG01Y.cpy:L20-L21` (CCDA-MSG-INVALID-KEY) | MAIN-PARA WHEN OTHER AID |
| 2 | `` `'Start Date - Month can NOT be empty...'` `` | 38 | L261-L262 | Custom: SDTMMI empty |
| 3 | `` `'Start Date - Day can NOT be empty...'` `` | 36 | L268-L269 | Custom: SDTDDI empty |
| 4 | `` `'Start Date - Year can NOT be empty...'` `` | 37 | L275-L276 | Custom: SDTYYYYI empty |
| 5 | `` `'End Date - Month can NOT be empty...'` `` | 36 | L282-L283 | Custom: EDTMMI empty |
| 6 | `` `'End Date - Day can NOT be empty...'` `` | 34 | L289-L290 | Custom: EDTDDI empty |
| 7 | `` `'End Date - Year can NOT be empty...'` `` | 35 | L296-L297 | Custom: EDTYYYYI empty |
| 8 | `` `'Start Date - Not a valid Month...'` `` | 33 | L331-L332 | Custom: SDTMMI not in 1-12 |
| 9 | `` `'Start Date - Not a valid Day...'` `` | 31 | L340-L341 | Custom: SDTDDI not in 1-31 |
| 10 | `` `'Start Date - Not a valid Year...'` `` | 32 | L348-L349 | Custom: SDTYYYYI not numeric |
| 11 | `` `'End Date - Not a valid Month...'` `` | 31 | L357-L358 | Custom: EDTMMI not in 1-12 |
| 12 | `` `'End Date - Not a valid Day...'` `` | 29 | L366-L367 | Custom: EDTDDI not in 1-31 |
| 13 | `` `'End Date - Not a valid Year...'` `` | 30 | L374-L375 | Custom: EDTYYYYI not numeric |
| 14 | `` `'Start Date - Not a valid date...'` `` | 32 | L400-L401 | Custom: CSUTLDTC(start) fails, MSG-NUM != '2513' |
| 15 | `` `'End Date - Not a valid date...'` `` | 30 | L420-L421 | Custom: CSUTLDTC(end) fails, MSG-NUM != '2513' |
| 16 | `` `'Select a report type to print report...'` `` | 39 | L438-L439 | Outer EVALUATE WHEN OTHER |
| 17 | `` `'Please confirm to print the ' + WS-REPORT-NAME + ' report...'` `` | dynamic (STRING) | L466-L469 | CONFIRMI empty |
| 18 | `` `WS-REPORT-NAME + ' report submitted for printing ...'` `` | dynamic (STRING) | L449-L450 | Success after JCL submit (note SPACE before `...`) |
| 19 | `` `'"' + CONFIRMI + '" is not a valid value to confirm...'` `` | dynamic (STRING) | L486-L488 | CONFIRMI not Y/y/N/n |
| 20 | `` `'Unable to Write TDQ (JOBS)...'` `` | 29 | L531-L532 | WIRTE-JOBSUB-TDQ non-NORMAL RESP-CD |

Two DISPLAY (COBOL DISPLAY analog — captured to `stdout.txt`, NOT to
production `System.out` per AAP §0.7.2) sites:

| # | Verbatim Bytes | Source Line | Emission Context |
|---|----------------|-------------|------------------|
| D1 | `` `'PROCESS ENTER KEY'` `` (17 bytes) | L210 | Unconditional at start of PROCESS-ENTER-KEY |
| D2 | `` `'RESP:' + WS-RESP-CD + 'REAS:' + WS-REAS-CD` `` | L529 | Only on WIRTE-JOBSUB-TDQ non-NORMAL |

Distinguishing notes:

- The message at index 18 has a SPACE before the `...`
  (`' report submitted for printing ...'`). The other 17 application
  messages have no space before `...`. Verified from
  `app/cbl/CORPT00C.cbl:L450`.
- CCDA-MSG-INVALID-KEY literal has 9 trailing spaces (40 text + 9
  spaces = 49 bytes between quotes); stored in PIC X(50) it pads to 50
  bytes total. Source: `app/cpy/CSMSG01Y.cpy:L21`.
- All ellipsis sequences are 3 ASCII periods (`...`), never the Unicode
  U+2026 codepoint.
- Messages 17, 18, and 19 are composed via COBOL `STRING` statements;
  the Java translation builds them via `String` concatenation with the
  EXACT same byte sequence. `DELIMITED BY SPACE` (L449, L468) strips
  trailing padding from WS-REPORT-NAME; `DELIMITED BY SIZE` preserves
  literal fragments verbatim.

## Supplementary 3 — Source Lineage

| File | Lines | Purpose |
|------|-------|---------|
| `app/cbl/CORPT00C.cbl` | 649 | Primary COBOL source — 10 paragraphs (MAIN-PARA, PROCESS-ENTER-KEY, SUBMIT-JOB-TO-INTRDR, WIRTE-JOBSUB-TDQ, RETURN-TO-PREV-SCREEN, SEND-TRNRPT-SCREEN, RETURN-TO-CICS, RECEIVE-TRNRPT-SCREEN, POPULATE-HEADER-INFO, INITIALIZE-ALL-FIELDS) |
| `app/bms/CORPT00.bms` | 231 | BMS map definition (mapset CORPT00, map CORPT0A, 24x80) |
| `app/cpy-bms/CORPT00.CPY` | 224 | Symbolic map copybook (CORPT0AI input layout + CORPT0AO output REDEFINES) |
| `app/cpy/COCOM01Y.cpy` | 47 | CARDDEMO-COMMAREA structure (CDEMO-FROM/TO/USER/PGM-CONTEXT + CUSTOMER/ACCOUNT/CARD/MORE info) |
| `app/cpy/CSMSG01Y.cpy` | 24 | CCDA-COMMON-MESSAGES (CCDA-MSG-INVALID-KEY 50-byte literal at L20-L21) |
| `app/cpy/COTTL01Y.cpy` | 27 | CCDA-SCREEN-TITLE (CCDA-TITLE01, CCDA-TITLE02 — 40-byte literals each) |
| `app/cpy/CSDAT01Y.cpy` | 58 | WS-DATE-TIME structures (WS-CURDATE-DATA, WS-CURDATE-MM-DD-YY, WS-CURTIME-HH-MM-SS, WS-TIMESTAMP) |
| `app/cpy/CVTRA05Y.cpy` | 21 | TRAN-RECORD (350-byte; COPYed by CORPT00C but unused — flag as vestigial in MIGRATION_NOTES.md) |
| `app/cbl/CSUTLDTC.cbl` | (external) | Date validation subprogram called twice from PROCESS-ENTER-KEY Custom branch |

## Supplementary 4 — Capture Procedure Cross-Reference

A 7-step procedure (cross-referenced to `java/MIGRATION_NOTES.md §1.6`
for the canonical capture procedure applied to all golden-record test
programs):

1. Build the COBOL program on z/OS or a Micro Focus mainframe-emulation
   environment using the standard CARDDEMO compile JCL
   (`samples/jcl/CICCMP.jcl` for online programs; `samples/jcl/BMSCMP.jcl`
   for the CORPT00 mapset).
2. Set up a CICS region with the CORPT00 mapset defined in CSD (see
   `app/csd/CARDDEMO.CSD`) and the JOBS Transient Data Queue defined as
   an extra-partition or intra-partition TDQ.
3. For each of the 15 scenarios listed in Phase 11 (and detailed in
   `input_scenario.txt`), drive a CICS pseudo-conversation:
   - Set EIBCALEN, EIBAID, and COMMAREA per the scenario directives
   - Bind a fixed system clock matching the `CLOCK_FIXED` directive
     (e.g., via a CICS test tool that overrides `EIBDATE` / `EIBTIME`
     for the duration of the test)
   - Inject input field values per FIELD directives (MONTHLY, YEARLY,
     CUSTOM, SDTMM, SDTDD, SDTYYYY, EDTMM, EDTDD, EDTYYYY, CONFIRM)
4. Capture COBOL DISPLAY output to a CICS DEST queue or DDname-redirected
   stdout; concatenate per-scenario DISPLAY output (with scenario-name
   separators) into a single `stdout.txt` file.
5. Capture BMS SEND MAP buffer bytes (the raw 3270 data stream sent by
   `EXEC CICS SEND MAP`); serialize per-scenario frames into
   `bms_output.txt` in the format documented in that file's header.
6. Capture TDQ JOBS queue contents after each scenario; verify the 17
   byte-exact JCL lines per the `EXPECT_TDQ_LINE` directives in
   `input_scenario.txt`.
7. Replace the placeholder files (`stdout.txt` and `bms_output.txt`)
   with the captured content; remove the `@Disabled` annotation from
   `com.blitzy.carddemo.tests.golden.CoRpt00CGoldenTest`.

See `java/MIGRATION_NOTES.md §1.6` for the canonical capture procedure
that applies to all golden-record test programs (CORPT00C is one of
many).

## Supplementary 5 — Cross-References

Java FQNs:

- `com.blitzy.carddemo.application.report.CoRpt00C` — the use case class
- `com.blitzy.carddemo.application.report.CoRpt00Input` — BMS input record (CORPT0AI translation)
- `com.blitzy.carddemo.application.report.CoRpt00Output` — BMS output record (CORPT0AO translation)
- `com.blitzy.carddemo.application.util.DateValidator` — CSUTLDTC translation per AAP §0.4.1
- `com.blitzy.carddemo.application.menu.CoMen01C` — PF3 destination
- `com.blitzy.carddemo.application.signon.CoSgn00C` — first-entry default destination
- `com.blitzy.carddemo.domain.commarea.CardDemoCommarea` — commarea record
- `com.blitzy.carddemo.tests.golden.CoRpt00CGoldenTest` — golden-record test class
- `com.blitzy.carddemo.tests.golden.GoldenRecordTest` — test base class per AAP §0.6.11

Sibling fixture folders:

- `java/carddemo-tests/src/test/resources/golden/cosgn00c/expected/` — COSGN00C sign-on contract
- `java/carddemo-tests/src/test/resources/golden/cotrn00c/expected/` — COTRN00C transaction-list browse contract

JCL skeleton consumer:

- `app/jcl/TRANREPT.jcl` — cataloged procedure invoked by the submitted JCL skeleton (the receiver of the TRANREPT batch job in z/OS)
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/TransactionReportApp.java` (AAP §0.4.1) — the Java main class that JCL would invoke; bypassed in Java via direct port invocation

Related Migration Notes references:

- `java/MIGRATION_NOTES.md §1.6` — golden-record capture procedure (AAP §0.7.5)
- `java/MIGRATION_NOTES.md` — flag CVTRA05Y as vestigial COPY in CORPT00C
- `java/MIGRATION_NOTES.md` — document the `WIRTE-JOBSUB-TDQ` typo preservation
- `java/MIGRATION_NOTES.md` — IMPLEMENTATION DECISION: CICS TDQ `JOBS` -> `JclSkeletonSink` port (AAP §0.4.1)

## Supplementary 6 — Authority References

The 14 AAP sections enumerated in the Authority Cascade at the top of
this README, restated here for completeness:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25
- AAP §0.2.1 — Golden-record fixtures explicitly in scope
- AAP §0.2.2 — `app/` tree is IMMUTABLE
- AAP §0.3.1 — Fixture directory layout: input/ + expected/
- AAP §0.4.1 — IMPLEMENTATION DECISION: CICS TDQ -> direct method invocation
- AAP §0.6.4 — java.time + ScopedValue<Clock>
- AAP §0.6.6 — ScopedValue replaces ThreadLocal entirely
- AAP §0.6.11 — Golden-record harness is non-negotiable PR gate
- AAP §0.6.12 — Architectural override: no Spring, no PostgreSQL
- AAP §0.7.1 — Minimal Change Clause
- AAP §0.7.2 — Security: no PAN, no System.out
- AAP §0.7.4 — No preview features (JEP 502, 505, 507, 512)
- AAP §0.7.5 — Capture procedure cross-reference
- AAP §0.8.1 — Citation discipline

## Supplementary 7 — DO NOT Modify Without Re-Capture

This README.md, `input_scenario.txt`, `stdout.txt`, and `bms_output.txt`
together form the byte-for-byte contract for the Java translation of
CORPT00C. Any change to these files MUST be accompanied by (1) a
corresponding update to the AAP section that authorizes the change and
(2) a fresh golden-record capture per `java/MIGRATION_NOTES.md §1.6` to
refresh `stdout.txt` and `bms_output.txt`. Do NOT hand-edit
`stdout.txt` or `bms_output.txt` to make tests pass — that defeats the
purpose of golden-record parity. Instead, fix the Java implementation.
If the COBOL baseline itself is found to have a bug (for example, a
truly-stale message text or an off-by-one in date arithmetic), document
the bug in `java/MIGRATION_NOTES.md` per AAP §0.7.1 and translate it
faithfully — do NOT fix in this refactor. The `app/` tree is IMMUTABLE
per AAP §0.2.2.
