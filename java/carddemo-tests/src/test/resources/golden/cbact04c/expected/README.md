# golden/cbact04c/expected/ — Documentation-Only Marker

This folder is the captured-output side of the CBACT04C golden-record harness. CBACT04C is the COBOL interest-calculator program driven by the INTCALC JCL job. It reads five files (TCATBALF input, XREFFILE alt-key lookup, ACCTFILE in I-O mode, DISCGRP lookup, TRANSACT output), computes monthly interest per `WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` with TRUNCATION (NO `ROUNDED` clause at `[app/cbl/CBACT04C.cbl:L464]`), accumulates totals into WS-TOTAL-INT per account, REWRITEs the ACCT-CURR-BAL of each account, and writes one TRAN-RECORD per non-zero rate. The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbAct04CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the THREE captured-output data files (`transact.txt`, `acctdata.txt`, `stdout.txt`) are committed to this folder. This folder is therefore currently documentation-only; this README is the SOLE direct child file and its presence preserves the otherwise-empty `expected/` directory in version control without a `.gitkeep` sentinel. Every claim about COBOL behavior in this README cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

## Phase 0: Why this folder is documentation-only

The three captured-output files (`transact.txt`, `acctdata.txt`, `stdout.txt`) will be committed to this folder LATER by the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 (per AAP §0.7.5 resolution of the user-supplied `[TODO — document the COBOL build/run path here]` marker). Until those three files exist on disk, the harness has nothing to assert against, so `CbAct04CGoldenTest` is `@Disabled` per AAP §0.6.11. The Maven Surefire report will skip the test with a "Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6" message.

A README is committed first because it pins down the field-by-field expected values, the REWRITE mutation set, the timestamp masking strategy, and the cross-references to the source-of-truth COBOL paragraphs BEFORE any captured bytes exist. This contract document is the single source of truth used by the capture procedure executor as the validation reference, by the future Java implementer of `com.blitzy.carddemo.application.account.CbAct04C` as the parity specification, and by PR reviewers as the meaning behind the byte-equality assertion. By committing the contract before the bytes, the project preserves the invariant that the bytes are derivable from the contract under the documented capture procedure — not the other way around.

By virtue of this single non-empty markdown file, the otherwise-empty `expected/` directory is preserved in version control without requiring a `.gitkeep` sentinel. This is the standard convention for every `golden/<program>/expected/` subtree in `java/carddemo-tests/src/test/resources/golden/`: each is bootstrapped with a similar README that pins down the program's specific contract before its baseline captures are committed. The convention ensures that a fresh clone of the repository carries the complete contract documentation for every golden-record fixture, even when the captured baselines are pending.

The binding cascade for all content in this README flows through 19 AAP sections:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS
- AAP §0.2.1 — Golden-record fixtures in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` + `<program>/expected/`
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` and Shenandoah generational
- AAP §0.3.6 — Hexagonal architecture; no Spring
- AAP §0.4.1 — CBACT04C -> CbAct04C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCE only, NEVER copied
- AAP §0.6.1 — `Decimals` utility with `MathContext.DECIMAL128`; CBACT04C uses TRUNCATION (no `ROUNDED` clause at L464)
- AAP §0.6.2 — Sealed-type pattern for composite keys (17-byte TCATBAL key)
- AAP §0.6.4 — `java.time` only; never `java.util.Date`/`Calendar`
- AAP §0.6.5 — `java.nio.file`; EBCDIC IBM-1047 default; per-file codepage override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely; sequential execution preserved; `ScopedValue<Clock>` for deterministic timestamps
- AAP §0.6.11 — Golden-record harness PR gate; `@Disabled` until COBOL captures committed; structured-record diff for timestamp masking
- AAP §0.7.1 — Minimal Change Clause; preserve `DALY REJECTS` misnomer at L281, empty 1400-COMPUTE-FEES at L518-L520, missing `Z-` prefix
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits)
- AAP §0.7.4 — No preview features (JEP 502, 505, 507, 512); no `default` branches that hide cases
- AAP §0.7.5 — Capture procedure documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]`

## Phase 1: Output contract overview

CBACT04C declares FIVE files in its FILE-CONTROL paragraph at `[app/cbl/CBACT04C.cbl:L27-L56]`:

- TCATBAL-FILE — `ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-TRAN-CAT-KEY FILE STATUS IS TCATBALF-STATUS` at L28-L32 — INPUT only.
- XREF-FILE — `ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-XREF-CARD-NUM ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID FILE STATUS IS XREFFILE-STATUS` at L34-L39 — INPUT only via the ALTERNATE RECORD KEY for ACCT-ID lookups.
- ACCOUNT-FILE — `ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-ACCT-ID FILE STATUS IS ACCTFILE-STATUS` at L41-L45 — opened I-O (input/output) at L291; updated via REWRITE.
- DISCGRP-FILE — `ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-DISCGRP-KEY FILE STATUS IS DISCGRP-STATUS` at L47-L51 — INPUT only.
- TRANSACT-FILE — `ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS TRANFILE-STATUS` at L53-L56 — OUTPUT only.

CBACT04C produces THREE observable outputs that the golden-record harness validates byte-for-byte against captured COBOL baselines:

1. `transact.txt` — Sequential output file written via `WRITE FD-TRANFILE-REC FROM TRAN-RECORD` at `[app/cbl/CBACT04C.cbl:L500]` inside paragraph `1300-B-WRITE-TX` (L473-L515). JCL DD declares `DCB=(RECFM=F,LRECL=350,BLKSIZE=0)` at `[app/jcl/INTCALC.jcl:L39]` with `DISP=(NEW,CATLG,DELETE)` and `DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)` at `[app/jcl/INTCALC.jcl:L37-L41]`. One TRAN-RECORD is written per non-zero TCATBAL record; the WRITE is gated by the `IF DIS-INT-RATE NOT = 0` guard at `[app/cbl/CBACT04C.cbl:L214]`.
2. `acctdata.txt` — Account master file UPDATED IN PLACE via `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` at `[app/cbl/CBACT04C.cbl:L356]` inside paragraph `1050-UPDATE-ACCOUNT` (L350-L370). ACCOUNT-FILE is opened `OPEN I-O ACCOUNT-FILE` at `[app/cbl/CBACT04C.cbl:L291]` inside paragraph `0300-ACCTFILE-OPEN`. Same 300-byte CVACT01Y layout as the input. The REWRITE is preceded by exactly three mutations: `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` at L352, `MOVE 0 TO ACCT-CURR-CYC-CREDIT` at L353, `MOVE 0 TO ACCT-CURR-CYC-DEBIT` at L354.
3. `stdout.txt` — DISPLAY output capture from PROCEDURE DIVISION. Contains start banner `'START OF EXECUTION OF PROGRAM CBACT04C'` at `[app/cbl/CBACT04C.cbl:L181]`, per-record TRAN-CAT-BAL-RECORD echo `DISPLAY TRAN-CAT-BAL-RECORD` at `[app/cbl/CBACT04C.cbl:L193]` (50-byte image emitted once per TCATBAL read), end banner `'END OF EXECUTION OF PROGRAM CBACT04C'` at `[app/cbl/CBACT04C.cbl:L230]`, and any non-fatal informational DISPLAY messages reached during execution.

File-status convention used throughout CBACT04C: each FILE-CONTROL declaration binds a 2-byte FILE STATUS field (`TCATBALF-STATUS`, `XREFFILE-STATUS`, `ACCTFILE-STATUS`, `DISCGRP-STATUS`, `TRANFILE-STATUS` at `[app/cbl/CBACT04C.cbl:L98-L120]`). Each status field is structured as two PIC X bytes per L99-L100, L104-L105, L109-L110, L114-L115, L119-L120. The standard COBOL file-status codes apply: `'00'` is success, `'10'` is end-of-file, `'23'` is record not found (used in the DISCGRP DEFAULT fallback at L422 and L436), `'35'` is file-not-found, and other 2-byte codes indicate various I/O errors. The Java translation expresses the file-status hierarchy as a sealed `FileStatus` interface with permits for each known code per AAP §0.6.2; any non-tolerant status triggers the corresponding `'ERROR ...'` DISPLAY followed by `9910-DISPLAY-IO-STATUS` and `9999-ABEND-PROGRAM`.

ALL three files MUST be present and validated byte-for-byte against captured baselines. Volatile-field masking applies to `transact.txt` only: the TRAN-ORIG-TS and TRAN-PROC-TS fields at byte offsets 235-260 and 261-286 (zero-indexed; 26 bytes each) are populated from `FUNCTION CURRENT-DATE` at `[app/cbl/CBACT04C.cbl:L614]` and require either a fixed `Clock` via `ScopedValue<Clock>` injection or a field-mask comparison mode per AAP §0.6.11 structured-record diff guidance. Phase 5 documents both strategies. The other two output files (`acctdata.txt` and `stdout.txt`) contain no timestamps and are asserted fully deterministically.

Codepage and encoding assumptions: the canonical capture environment produces ASCII-encoded output to match the test fixtures in `app/data/ASCII/*.txt`. Production z/OS would emit EBCDIC IBM-1047 bytes; the capture procedure either runs on a GnuCOBOL platform that natively emits ASCII or runs on z/OS with a downstream transcoding step. Per AAP §0.6.5 the default codepage for production EBCDIC reads is `Charset.forName("IBM-1047")` with per-file overrides via `application.properties`; the test fixtures themselves are pre-transcoded to ASCII for test convenience. The Java translation's `EbcdicTranscoder` utility (in `carddemo-adapter-file`) handles the transcoding boundary; the golden-record harness operates entirely in ASCII space for byte-for-byte parity simplicity.

A note on line-end conventions in `stdout.txt`: COBOL DISPLAY statements on z/OS emit LF as the record separator when stdout is redirected to a file; on GnuCOBOL on Linux, the same convention applies. The captured `stdout.txt` MUST use LF-only line endings (no CRLF, no CR) to match this README's encoding mandate. The capture procedure in `java/MIGRATION_NOTES.md` §1.6 includes a verification step to assert LF-only line endings on the captured stdout file before commit.

Note on the program's overall execution shape: the PROCEDURE DIVISION at L180 takes a single LINKAGE-SECTION parameter `EXTERNAL-PARMS` (declared at L176-L178) containing a `PARM-LENGTH PIC S9(04) COMP` and a `PARM-DATE PIC X(10)`. The COBOL operating-system convention passes the JCL `PARM=` value into this parameter; the JCL at `[app/jcl/INTCALC.jcl:L22]` supplies `PARM='2022071800'` so PARM-DATE is the 10-byte sequence `2022071800` and PARM-LENGTH is `10`. The PARM-DATE is then used in the STRING at L476-L480 to build the TRAN-ID prefix. The Java equivalent is a `String[]` argv passed to `CbAct04C.main(...)` carrying a single 10-byte argument, validated by the program's entry-paragraph translation before any file I/O.

OPEN sequence at program startup: the main program at L182-L186 performs the five OPEN paragraphs in this exact order: `0000-TCATBALF-OPEN` first (L182), `0100-XREFFILE-OPEN` second (L183), `0200-DISCGRP-OPEN` third (L184), `0300-ACCTFILE-OPEN` fourth (L185, opens I-O), `0400-TRANFILE-OPEN` fifth (L186, opens OUTPUT). If any OPEN fails, the corresponding `'ERROR OPENING ...'` DISPLAY emits and the program ABENDs before reaching the main loop. The Java translation MUST preserve this OPEN order; reordering would change the failure-message order in `stdout.txt` if multiple files are simultaneously missing or inaccessible.

CLOSE sequence at program termination: the main program at L223-L227 performs the five CLOSE paragraphs in the same order as the OPENs: `9000-TCATBALF-CLOSE`, `9100-XREFFILE-CLOSE`, `9200-DISCGRP-CLOSE`, `9300-ACCTFILE-CLOSE`, `9400-TRANFILE-CLOSE`. The CLOSEs happen after the main loop terminates AND after the L219-L220 final-account flush completes. The Java translation MUST close the corresponding file channels in this order via try-with-resources stacked appropriately or explicit close calls; the order matters because the L223-L227 CLOSE-error DISPLAYs would emit in that order if multiple CLOSE failures occurred (rare but observable).

## Phase 2: Output file inventory

This is the ONLY phase containing H3 sub-headings. Exactly four H3 sub-headings follow, one per file documented (`README.md`, `transact.txt`, `acctdata.txt`, `stdout.txt`). No other H3 headings appear anywhere else in this document.

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBACT04C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbAct04CGoldenTest` once the three data files below are captured per AAP §0.7.5. The README is the sole file in this folder until the capture procedure is executed; its presence preserves the empty `expected/` directory in version control without requiring a `.gitkeep` sentinel.

### `transact.txt` — 350-byte FB sequential output (CAPTURE PLACEHOLDER)

- Format: 350-byte fixed-width records. `FD-TRANFILE-REC` = `FD-TRANS-ID PIC X(16)` + `FD-ACCT-DATA PIC X(334)` per `[app/cbl/CBACT04C.cbl:L88-L92]`.
- JCL DCB: `DCB=(RECFM=F,LRECL=350,BLKSIZE=0)` per `[app/jcl/INTCALC.jcl:L39]`; DD declared `DISP=(NEW,CATLG,DELETE)`, `DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)` per `[app/jcl/INTCALC.jcl:L37-L41]`.
- Layout: TRAN-RECORD per `app/cpy/CVTRA05Y.cpy` (350 bytes total, 14 fields):
  - TRAN-ID PIC X(16)
  - TRAN-TYPE-CD PIC X(02)
  - TRAN-CAT-CD PIC 9(04)
  - TRAN-SOURCE PIC X(10)
  - TRAN-DESC PIC X(100)
  - TRAN-AMT PIC S9(09)V99 (BigDecimal scale 2)
  - TRAN-MERCHANT-ID PIC 9(09)
  - TRAN-MERCHANT-NAME PIC X(50)
  - TRAN-MERCHANT-CITY PIC X(50)
  - TRAN-MERCHANT-ZIP PIC X(10)
  - TRAN-CARD-NUM PIC X(16)
  - TRAN-ORIG-TS PIC X(26) (DB2 timestamp — VOLATILE)
  - TRAN-PROC-TS PIC X(26) (DB2 timestamp — VOLATILE)
  - FILLER PIC X(20)
- Field positions: each field's byte position is determined cumulatively from the CVTRA05Y `01 TRAN-RECORD` group definition; the canonical 26-byte VOLATILE timestamp offsets MUST follow Phase 5's mandate. The two timestamp fields are the only fields that vary between runs at the byte level given fixed input fixtures and a fixed Clock.
- Write semantics: one TRAN-RECORD per non-zero TCATBAL record. The WRITE is gated by `IF DIS-INT-RATE NOT = 0` at `[app/cbl/CBACT04C.cbl:L214]`; the WRITE statement is at `[app/cbl/CBACT04C.cbl:L500]` inside `1300-B-WRITE-TX` (L473-L515). The WRITE failure path at L509-L513 emits `'ERROR WRITING TRANSACTION RECORD'`, performs `9910-DISPLAY-IO-STATUS`, and abends via `9999-ABEND-PROGRAM`.
- Sort order: sequential order matching the order TCATBAL records are read by paragraph `1000-TCATBALF-GET-NEXT` at `[app/cbl/CBACT04C.cbl:L325]` — preserved EXACTLY per AAP §0.6.6 (NO virtual threads, NO reordering). The COBOL `SELECT TCATBAL-FILE ASSIGN TO TCATBALF ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL` at `[app/cbl/CBACT04C.cbl:L28-L32]` defines an indexed-sequential read; the Java translation reads via `FileTransactionCategoryBalanceRepository.streamSequential()` (per AAP §0.6.5 file-adapter port) and forwards records in their natural sort order to the in-memory pipeline.
- Byte-for-byte parity rule: `CbAct04C.run(...)` MUST produce, for the deterministic input set under a fixed Clock, a `transact.txt` byte sequence equal to this captured baseline BYTE-for-BYTE (modulo timestamp masking — see Phase 5).
- Status when initially committed: CAPTURE PLACEHOLDER — file does NOT exist yet; `CbAct04CGoldenTest` is `@Disabled` until captured content is committed.

### `acctdata.txt` — 300-byte FB I-O REWRITE (CAPTURE PLACEHOLDER)

- Format: 300-byte fixed-width records. `FD-ACCTFILE-REC` = `FD-ACCT-ID PIC 9(11)` + `FD-ACCT-DATA PIC X(289)` per `[app/cbl/CBACT04C.cbl:L83-L86]`.
- JCL DD: `ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` per `[app/jcl/INTCALC.jcl:L33-L34]`.
- Layout: ACCOUNT-RECORD per `[app/cpy/CVACT01Y.cpy:§ACCOUNT-RECORD]` (300 bytes; 12 fields plus 178-byte FILLER):
  - ACCT-ID PIC 9(11)
  - ACCT-ACTIVE-STATUS PIC X(01)
  - ACCT-CURR-BAL PIC S9(10)V99 (BigDecimal scale 2)
  - ACCT-CREDIT-LIMIT PIC S9(10)V99
  - ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
  - ACCT-OPEN-DATE PIC X(10)
  - ACCT-EXPIRAION-DATE PIC X(10) (note misspelled field name preserved per AAP §0.7.1)
  - ACCT-REISSUE-DATE PIC X(10)
  - ACCT-CURR-CYC-CREDIT PIC S9(10)V99
  - ACCT-CURR-CYC-DEBIT PIC S9(10)V99
  - ACCT-ADDR-ZIP PIC X(10)
  - ACCT-GROUP-ID PIC X(10)
  - FILLER PIC X(178)
- Update semantics: REWRITE of existing records ONLY — no new records ever inserted. REWRITE at `[app/cbl/CBACT04C.cbl:L356]` inside `1050-UPDATE-ACCOUNT` (L350-L370). I-O mode opened at `[app/cbl/CBACT04C.cbl:L291]`.
- Modified fields per `1050-UPDATE-ACCOUNT` (lines 352-354):
  - `ACCT-CURR-BAL`: `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` (accumulated WS-MONTHLY-INT over all TCATBAL records for this account)
  - `ACCT-CURR-CYC-CREDIT`: `MOVE 0 TO ACCT-CURR-CYC-CREDIT`
  - `ACCT-CURR-CYC-DEBIT`: `MOVE 0 TO ACCT-CURR-CYC-DEBIT`
- Unchanged fields: All other 9 ACCOUNT-RECORD fields plus the 178-byte FILLER are preserved unchanged from the input record loaded by `1100-GET-ACCT-DATA`.
- Byte-for-byte parity rule: byte-by-byte match expected against captured baseline (NO timestamp fields — fully deterministic).
- Status when initially committed: CAPTURE PLACEHOLDER.

### `stdout.txt` — DISPLAY capture (CAPTURE PLACEHOLDER)

- Format: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output ending in the platform's newline (LF on captured run).
- Primary content on the happy path:
  - Line 1: `'START OF EXECUTION OF PROGRAM CBACT04C'` at `[app/cbl/CBACT04C.cbl:L181]`
  - Lines 2..N: per-record TRAN-CAT-BAL-RECORD echo at `[app/cbl/CBACT04C.cbl:L193]` — `DISPLAY TRAN-CAT-BAL-RECORD` emits the entire 50-byte TRAN-CAT-BAL-RECORD as a concatenated text image; one stdout line per successful TCATBAL READ
  - Possibly inline: `'DISCLOSURE GROUP RECORD MISSING'` at `[app/cbl/CBACT04C.cbl:L418]` and `'TRY WITH DEFAULT GROUP CODE'` at `[app/cbl/CBACT04C.cbl:L419]` — emitted only when `DISCGRP-STATUS = '23'` triggers the DEFAULT fallback (these are informational, not abend)
  - Final line: `'END OF EXECUTION OF PROGRAM CBACT04C'` at `[app/cbl/CBACT04C.cbl:L230]`
- Variable length: depends on the number of TCATBAL records and the number of DEFAULT-fallback occurrences.
- Byte-for-byte parity rule: the Java test driver's captured stdout MUST equal this baseline BYTE-for-BYTE.
- PAN-masking dichotomy: the L193 DISPLAY emits the 50-byte TRAN-CAT-BAL-RECORD which does NOT contain a card PAN (only ACCT-ID + TYPE-CD + CAT-CD + BAL); however, per AAP §0.7.2 production logs MUST mask all but the last 4 digits of any 16-byte TRAN-CARD-NUM field encountered. The test driver uses an unmasked sink for byte-for-byte parity; the production logger uses a masking sink via a separate path. The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
- Status when initially committed: CAPTURE PLACEHOLDER.

## Phase 3: TRAN-RECORD field-by-field expected values

Each WRITE of a TRAN-RECORD at `[app/cbl/CBACT04C.cbl:L500]` is preceded by the field-by-field MOVE/STRING sequence in `1300-B-WRITE-TX` (L473-L499) that populates each of the 14 TRAN-RECORD fields. The table below documents each field with its exact source-of-value and line citation:

| # | Field | PIC | Bytes | Source-of-Value | Line |
|---|-------|-----|-------|-----------------|------|
| 1 | TRAN-ID | X(16) | 16 | `STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID` — `PARM-DATE` is 10 bytes from JCL `PARM='2022071800'` per `[app/jcl/INTCALC.jcl:L22]`; `WS-TRANID-SUFFIX` PIC 9(06) is a GLOBAL counter (NOT per-account reset), declared with `VALUE 0` at `[app/cbl/CBACT04C.cbl:L173]`, incremented at L474 BEFORE the STRING; first emitted TRAN-ID = `'2022071800000001'`, second = `'2022071800000002'`, etc. | L474-L480 |
| 2 | TRAN-TYPE-CD | X(02) | 2 | `MOVE '01' TO TRAN-TYPE-CD` | L482 |
| 3 | TRAN-CAT-CD | 9(04) | 4 | `MOVE '05' TO TRAN-CAT-CD` (literal `'05'` MOVEd to PIC 9(04) field — COBOL right-justifies and zero-pads to `'0005'`) | L483 |
| 4 | TRAN-SOURCE | X(10) | 10 | `MOVE 'System' TO TRAN-SOURCE` (6 chars literal MOVEd to PIC X(10) — COBOL left-justifies and space-pads to `'System    '`) | L484 |
| 5 | TRAN-DESC | X(100) | 100 | `STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC` (24 chars filled; remainder is whatever was in the field previously OR space-padded depending on COBOL initialization semantics — capture-then-verify) | L485-L489 |
| 6 | TRAN-AMT | S9(09)V99 | 11 (with sign) | `MOVE WS-MONTHLY-INT TO TRAN-AMT` — value is TRUNCATED per AAP §0.6.1 (NO `ROUNDED` clause at L464) | L490 |
| 7 | TRAN-MERCHANT-ID | 9(09) | 9 | `MOVE 0 TO TRAN-MERCHANT-ID` (zero-padded to `'000000000'`) | L491 |
| 8 | TRAN-MERCHANT-NAME | X(50) | 50 | `MOVE SPACES TO TRAN-MERCHANT-NAME` (50 ASCII spaces) | L492 |
| 9 | TRAN-MERCHANT-CITY | X(50) | 50 | `MOVE SPACES TO TRAN-MERCHANT-CITY` | L493 |
| 10 | TRAN-MERCHANT-ZIP | X(10) | 10 | `MOVE SPACES TO TRAN-MERCHANT-ZIP` | L494 |
| 11 | TRAN-CARD-NUM | X(16) | 16 | `MOVE XREF-CARD-NUM TO TRAN-CARD-NUM` (16-byte XREF-CARD-NUM from prior `1110-GET-XREF-DATA` lookup at L205) | L495 |
| 12 | TRAN-ORIG-TS | X(26) | 26 | `MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS` after `PERFORM Z-GET-DB2-FORMAT-TIMESTAMP` at L496 — VOLATILE (sourced from `FUNCTION CURRENT-DATE` at L614) | L497 |
| 13 | TRAN-PROC-TS | X(26) | 26 | `MOVE DB2-FORMAT-TS TO TRAN-PROC-TS` — same DB2-FORMAT-TS value as TRAN-ORIG-TS within the same WRITE iteration — VOLATILE | L498 |
| 14 | FILLER | X(20) | 20 | Unset by `1300-B-WRITE-TX`; carries whatever bytes the WORKING-STORAGE initialization left in the TRAN-RECORD area (typically SPACES per COBOL VALUE clause defaults). Capture-then-verify. | (CVTRA05Y L18) |

Notes:

- The `WS-TRANID-SUFFIX` increments GLOBALLY across all accounts; there is NO per-account reset. The COBOL field is declared at `[app/cbl/CBACT04C.cbl:L173]` as `05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0`. The Java translation MUST mirror this semantics: a single counter for the entire batch run, not a counter that resets at each account boundary.
- The Java translation MUST produce byte-identical TRAN-RECORD bytes for fields 1-11 (deterministic given fixed input and `PARM-DATE`); fields 12-13 require timestamp masking per Phase 5; field 14 is bit-for-bit derived from WORKING-STORAGE initialization defaults and is captured-then-verified.
- `Decimals.encodeSignedPacked(...)` (per AAP §0.6.1) handles the S9(09)V99 sign-nybble encoding for TRAN-AMT (field 6). All monetary arithmetic centralizes in the `Decimals` utility; no business code may construct `MathContext` or `RoundingMode` instances directly.
- The STRING into TRAN-DESC at L485-L489 fills the first 24 bytes with the concatenation of the literal `'Int. for a/c '` (13 bytes) and the 11-byte ACCT-ID (preserving leading zeros for the 11-digit account identifier). The remaining 76 bytes of TRAN-DESC's 100-byte field depend on the WORKING-STORAGE initialization convention of the COBOL runtime: most z/OS COBOL compilers initialize 01-level groups to SPACES by default, so the trailing 76 bytes are typically 76 ASCII spaces. The capture-then-verify discipline confirms the exact byte sequence for the canonical fixtures.
- Each TRAN-ID produced is unique within the batch: the WS-TRANID-SUFFIX is initialized to `0` and incremented BEFORE each STRING, so the first TRAN-ID is `'2022071800000001'`, the second is `'2022071800000002'`, and so on monotonically. The 16-byte concatenation of PARM-DATE (10 bytes) + WS-TRANID-SUFFIX (6 bytes) exactly fills TRAN-ID's PIC X(16) without padding or truncation.
- The TRAN-MERCHANT-ID field 7 receives `MOVE 0 TO TRAN-MERCHANT-ID` at L491; this is a numeric MOVE to a PIC 9(09) field, which COBOL right-justifies and zero-pads to produce the 9-byte sequence `'000000000'`. The Java translation MUST encode the integer 0 as a 9-byte zero-padded ASCII sequence (not as a single byte `0x00` or as ASCII `'0'` followed by 8 spaces).
- The TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY, and TRAN-MERCHANT-ZIP fields (8, 9, 10) receive `MOVE SPACES TO ...` at L492-L494; these are alphanumeric MOVEs that fill the entire field with ASCII space characters (0x20) — 50 spaces, 50 spaces, and 10 spaces respectively. The Java translation MUST produce 50/50/10 ASCII spaces and not any alternative blank-padding character.

## Phase 4: ACCOUNT-RECORD REWRITE field-by-field expected mutations

Each REWRITE of an ACCOUNT-RECORD at `[app/cbl/CBACT04C.cbl:L356]` is preceded by exactly THREE field mutations in `1050-UPDATE-ACCOUNT` (L350-L370). The table below documents each mutation with line citation:

| # | Field | PIC | Mutation | Line | Notes |
|---|-------|-----|----------|------|-------|
| 1 | ACCT-CURR-BAL | S9(10)V99 | `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` | L352 | Accumulated WS-MONTHLY-INT over all TCATBAL records seen for this account before the boundary triggers the flush. BigDecimal scale 2 in Java per AAP §0.6.1; TRUNCATION applied. |
| 2 | ACCT-CURR-CYC-CREDIT | S9(10)V99 | `MOVE 0 TO ACCT-CURR-CYC-CREDIT` | L353 | Reset to zero unconditionally on every REWRITE. BigDecimal scale 2 with value `0.00` (preserve trailing zero per AAP §0.6.1 decimal-scale preservation). |
| 3 | ACCT-CURR-CYC-DEBIT | S9(10)V99 | `MOVE 0 TO ACCT-CURR-CYC-DEBIT` | L354 | Reset to zero unconditionally on every REWRITE. BigDecimal scale 2 with value `0.00`. |

Notes:

- All other 9 ACCOUNT-RECORD fields plus the 178-byte FILLER are preserved unchanged from the input record loaded by `1100-GET-ACCT-DATA` at `[app/cbl/CBACT04C.cbl:L372-L391]`.
- Unchanged fields: ACCT-ID, ACCT-ACTIVE-STATUS, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, ACCT-ADDR-ZIP, ACCT-GROUP-ID, FILLER.
- One REWRITE per account boundary: `1050-UPDATE-ACCOUNT` is invoked at the start of each account boundary detected by `IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM` at `[app/cbl/CBACT04C.cbl:L194]`, but only AFTER the first account (`IF WS-FIRST-TIME NOT = 'Y'` gate at L195). It is ALSO invoked at the END-OF-FILE outer branch at `[app/cbl/CBACT04C.cbl:L219-L220]` for the LAST account in the input (the COBOL programmer's intent for the final flush). See Phase 7 for the full boundary-lifecycle details.
- The REWRITE failure path at L364-L368 emits `'ERROR RE-WRITING ACCOUNT FILE'`, PERFORMs `9910-DISPLAY-IO-STATUS`, and ABENDs via `9999-ABEND-PROGRAM`; this is preserved as an exception-throwing path in the Java translation that abends the batch run with non-zero exit code.

The Java `AccountRecord` and any derived record types declared in `com.blitzy.carddemo.domain.record` use JEP 513 Flexible Constructor Bodies (finalized in Java 25) to validate field ranges before binding. For example, the canonical constructor may verify that `currentBalance` is within the COBOL `S9(10)V99` numeric range and that monetary values carry exactly `scale == 2` before assignment, using a flexible body that performs the validation prior to canonical binding. Per AAP §0.7.4 this MUST NOT use preview-only features; JEP 513 is finalized in Java 25 and is permitted. The Java translation of `1050-UPDATE-ACCOUNT` constructs a new immutable `AccountRecord` representing the post-mutation state by copying the input record's 9 unchanged fields and assigning the 3 mutated fields, then passes the new record to the `AccountRepository.update(...)` port for the REWRITE adapter call.

## Phase 5: Volatile-field masking strategy

The TRAN-RECORD layout contains two volatile timestamp fields in every record written to `transact.txt`:

- TRAN-ORIG-TS at byte offset 235-260 (zero-indexed; 26 bytes) — sourced from DB2-FORMAT-TS at `[app/cbl/CBACT04C.cbl:L497]`
- TRAN-PROC-TS at byte offset 261-286 (zero-indexed; 26 bytes) — sourced from DB2-FORMAT-TS at `[app/cbl/CBACT04C.cbl:L498]`

Both fields are populated within the same iteration of `1300-B-WRITE-TX` (so they carry the SAME 26-byte value within a single record, but DIFFERENT values across records as the clock advances between iterations). The DB2-FORMAT-TS is constructed by `Z-GET-DB2-FORMAT-TIMESTAMP` at `[app/cbl/CBACT04C.cbl:L613-L626]`, which calls `MOVE FUNCTION CURRENT-DATE TO COBOL-TS` at L614 — a hard system-clock dependency that makes the field non-reproducible without explicit Clock injection.

The DB2-FORMAT-TS 26-byte format mirrors the DB2 SQL timestamp layout, assembled from the COBOL-TS components by Z-GET-DB2-FORMAT-TIMESTAMP at L615-L624: `YYYY-MM-DD-HH.MM.SS.MMM0000` where `YYYY` is 4-digit year, `MM` month, `DD` day, then a `-` separator (L623), then `HH.MM.SS.MMM` time with `.` separators (L624), and a 4-byte trailing `'0000'` from `MOVE '0000' TO DB2-REST` at L622. The microseconds portion is encoded as 2 digits from COB-MIL (`PIC 9(002)`) plus the literal `'0000'` to fill the DB2 timestamp's 6-digit microseconds field. The Java equivalent format string for `java.time.format.DateTimeFormatter` is `yyyy-MM-dd-HH.mm.ss.SSSSSS` where the last six characters represent microseconds; a fixed Clock injection per Phase 10 produces a deterministic 26-byte sequence.

The harness adopts ONE of two acceptable masking strategies (documented inline in the test class):

- (a) Field-mask comparison mode: the `GoldenRecordTest` framework provides a field-mask mode that overlays the 52 bytes at offsets 235-286 of each 350-byte TRAN-RECORD with a sentinel byte sequence (e.g., 52 `?` characters) in BOTH the expected and actual buffers before invoking `assertThat(actual).isEqualTo(expected)` from AssertJ. Per AAP §0.6.11 structured-record diff guidance: where fixed-width byte equality is insufficient for report files with embedded timestamps that legitimately vary, the harness also provides a field-by-field comparison mode that masks known-variable fields.
- (b) Fixed-Clock injection: a fixed `java.time.Clock` is injected via `ScopedValue<Clock>` per AAP §0.6.6 ScopedValue propagation. Both the COBOL capture and the Java test run produce identical literal 26-byte timestamps because both source from the same fixed instant. The fixed instant for the canonical capture is documented in `java/MIGRATION_NOTES.md` §1.6.

Recommended strategy: option (b) (`ScopedValue<Clock>` injection) is preferred when the capture environment supports it, because byte-for-byte equality of literal timestamp strings is a stronger parity assertion than masked equality. Option (a) is a fallback for environments where the COBOL capture cannot inject a fixed clock. The CBACT04C harness adopts option (b); the field-mask comparison is documented as a fallback. The chosen strategy MUST be re-confirmed in `java/MIGRATION_NOTES.md` §1.6 alongside the canonical capture command.

The `acctdata.txt` and `stdout.txt` outputs contain NO timestamps and therefore require NO masking. Only the `transact.txt` file's TRAN-ORIG-TS and TRAN-PROC-TS fields are volatile.

Field-mask procedure (option (a)) implementation outline: the harness opens the expected and actual `transact.txt` byte streams; iterates over fixed-width 350-byte record boundaries; for each record, replaces the 52 bytes at offsets 235-286 with a fixed sentinel byte sequence (e.g., 52 ASCII `?` characters, 0x3F repeated) in BOTH buffers before invoking the AssertJ `isEqualTo(...)` comparison. The sentinel masking eliminates the variable timestamp bytes from the comparison while preserving the structural byte equality of every other field. The harness reports field-level mismatches with surrounding context bytes when a difference is detected outside the masked region.

ScopedValue injection procedure (option (b)) implementation outline: the harness constructs a `java.time.Clock.fixed(Instant.parse("..."), ZoneOffset.UTC)` with the canonical seed instant from `java/MIGRATION_NOTES.md` §1.6; binds it via `ScopedValue.where(BatchRunContext.CLOCK, fixedClock).run(...)` per AAP §0.6.6; and runs `CbAct04C.run(args)` inside the scope. The `Z-GET-DB2-FORMAT-TIMESTAMP` Java equivalent reads `BatchRunContext.CLOCK.get().instant()` (returning the bound fixed `Instant`) and formats it via `java.time.format.DateTimeFormatter` into the 26-byte DB2-style sequence. Every TRAN-RECORD's TRAN-ORIG-TS and TRAN-PROC-TS fields then carry the identical fixed-instant bytes, matching the COBOL capture byte-for-byte.

Worked field-mask example for a 350-byte TRAN-RECORD: suppose the WRITE at L500 emits a record whose first 235 bytes encode the deterministic fields 1-11 (TRAN-ID through TRAN-CARD-NUM), bytes 235-260 encode `'2022-07-18-09.42.13.123456'` (TRAN-ORIG-TS at run time T), bytes 261-286 encode the same `'2022-07-18-09.42.13.123456'` (TRAN-PROC-TS), and bytes 287-349 encode the 20-byte FILLER and remainder. A subsequent run at time T' produces an identical first-235-byte sequence and an identical bytes 287-349, but the bytes 235-260 and 261-286 carry `'2022-07-18-09.42.13.987654'` (the new value of T'). The field-mask comparison overlays both buffers' bytes 235-286 with 52 ASCII `?` characters; after masking, both buffers are byte-identical, and `assertThat(actual).isEqualTo(expected)` passes. The non-masked bytes (first 235 and last 64) still detect any divergence in fields 1-11 or FILLER.

Why the mask covers exactly bytes 235-286 and not the surrounding bytes: the cumulative field-offset arithmetic from CVTRA05Y's `01 TRAN-RECORD` 14-field structure places TRAN-ORIG-TS at offset 16+2+4+10+100+11+9+50+50+10+16 = 278 in the COMP-3-encoded form, or 235 if the monetary field uses the signed-zoned-decimal (DISPLAY) encoding where TRAN-AMT occupies 11 bytes including the sign overpunch. The capture procedure documents which encoding the COBOL compiler emits; the canonical mask offsets in this README (235-260 and 261-286) assume the DISPLAY encoding convention. If the COBOL build pipeline selects COMP-3 instead, the offsets shift accordingly and the harness MUST be reconfigured per the capture procedure's documented offsets.

## Phase 6: Decimal arithmetic verification

The central interest formula and the rounding mandate:

- Formula: `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200` at `[app/cbl/CBACT04C.cbl:L464-L465]`
- No `ROUNDED` clause: the COMPUTE at L464 omits the `ROUNDED` keyword; COBOL default for omitted ROUNDED is TRUNCATION toward zero per the COBOL language specification.
- Java mandate: per AAP §0.6.1, the `Decimals` utility's default `RoundingMode` for the omitted-ROUNDED path is `RoundingMode.DOWN` (truncation toward zero). The Java translation MUST use `Decimals.multiplyTruncated(...)` and `Decimals.divideTruncated(...)` (or equivalent with an explicit `RoundingMode.DOWN`); it MUST NOT use `RoundingMode.HALF_EVEN` (banker's rounding) for this formula.
- Operand layouts:
  - TRAN-CAT-BAL: PIC S9(09)V99 from `[app/cpy/CVTRA01Y.cpy:§TRAN-CAT-BAL-RECORD]` (BigDecimal scale 2)
  - DIS-INT-RATE: PIC S9(04)V99 from `[app/cpy/CVTRA02Y.cpy:§DIS-GROUP-RECORD]` (BigDecimal scale 2)
  - 1200: literal integer (Java `BigDecimal.valueOf(1200L)`)
  - WS-MONTHLY-INT: PIC S9(09)V99 from `[app/cbl/CBACT04C.cbl:L168]` (BigDecimal scale 2)
- Accumulation: `ADD WS-MONTHLY-INT TO WS-TOTAL-INT` at `[app/cbl/CBACT04C.cbl:L467]` — accumulator for the current account. WS-TOTAL-INT is declared at `[app/cbl/CBACT04C.cbl:L169]` as PIC S9(09)V99 (BigDecimal scale 2).

Verify-by-example:

- Example 1 — positive truncation: `(1234.56 * 0.0635) / 1200 = 78.39456 / 1200 = 0.065328...` truncated to scale 2 = `0.06`. Banker's HALF_EVEN would round to `0.07` — INCORRECT and MUST NOT be used here.
- Example 2 — negative truncation: `(-100.00 * 0.0500) / 1200 = -5.00 / 1200 = -0.004166...` truncated toward zero = `-0.00` (sign-preserving; `BigDecimal.setScale(2, RoundingMode.DOWN)` produces `-0.00` not `0.00`). The COBOL S9(09)V99 sign nybble preserves the sign even when the magnitude rounds to zero.
- Example 3 — zero-rate skip: `DIS-INT-RATE = 0.00` triggers the `IF DIS-INT-RATE NOT = 0` guard at `[app/cbl/CBACT04C.cbl:L214]` to skip `PERFORM 1300-COMPUTE-INTEREST` entirely; no WRITE to `transact.txt` and no ADD to WS-TOTAL-INT for this TCATBAL record. The L193 DISPLAY of the TRAN-CAT-BAL-RECORD still emits, however, because it precedes the rate guard.
- Example 4 — small-magnitude scale preservation: `(0.01 * 0.0100) / 1200 = 0.0001 / 1200 = 0.00000008333...` truncated to scale 2 = `0.00`. The result is exactly zero at scale 2 but the WRITE still occurs because the gate at L214 only checks `DIS-INT-RATE NOT = 0` (the rate, not the computed interest); the WS-MONTHLY-INT = `0.00` is written into TRAN-AMT as the encoded zero per the COBOL S9(09)V99 convention. The Java translation MUST preserve scale 2 on the zero value via `BigDecimal("0.00")` rather than `BigDecimal.ZERO` to ensure the encoded TRAN-AMT byte sequence matches the COBOL output.
- Example 5 — large-magnitude truncation: `(99999999.99 * 99.99) / 1200 = 9999998999.0001 / 1200 = 8333332.499...` truncated to scale 2 = `8333332.49`. The WS-MONTHLY-INT field's PIC S9(09)V99 range (max integer magnitude 999999999 with 2 fractional digits) easily accommodates this magnitude; the intermediate `MathContext.DECIMAL128` (34 digits) provides ample headroom for the multiply step before the divide-and-truncate. Banker's HALF_EVEN would produce `8333332.50` — wrong by one cent.
- Example 6 — exact-half boundary: `(2.00 * 0.0300) / 1200 = 0.06 / 1200 = 0.00005` truncated to scale 2 = `0.00`. The intermediate value `0.00005` lies exactly at the half-cent boundary, but TRUNCATION discards it without rounding regardless of the magnitude on either side of `0.005`. Banker's HALF_EVEN would round `0.00005` to `0.00` (since the digit-before-the-half is even), coincidentally producing the same result; banker's HALF_UP would round to `0.01` — wrong. The TRUNCATION-vs-HALF_EVEN agreement at exact-half boundaries is coincidental and MUST NOT be relied upon; the Java translation always uses `RoundingMode.DOWN`.

The `Decimals` utility (per AAP §0.6.1) is the single point of control; its 100% line-coverage requirement is enforced by the jqwik property-based tests in `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/property/DecimalsProperties.java`. No business code may construct `MathContext` or `RoundingMode` instances directly outside the `Decimals` utility.

Why TRUNCATION is the correct semantics for L464: COBOL specification states that when the `ROUNDED` phrase is omitted from a COMPUTE statement, the receiving field's excess low-order digits are truncated toward zero rather than rounded. Banker's rounding (HALF_EVEN) is only applied when the `ROUNDED` phrase appears on the receiving identifier. The COMPUTE at L464 reads `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200` with NO `ROUNDED` keyword on `WS-MONTHLY-INT`; therefore the COBOL runtime truncates the intermediate fractional bits at scale 2 (the receiving field's V99 scale). The Java translation MUST produce the identical bit pattern by performing the multiply, the divide, and the scale conversion all with `RoundingMode.DOWN`. The intermediate precision should be `MathContext.DECIMAL128` (34 decimal digits) to ensure that intermediate over- or under-flow does not introduce rounding artifacts before the final truncation step.

Why the multiplication and division order matters: `(a * b) / c` is NOT mathematically equivalent to `a * (b / c)` when intermediate truncation occurs. The COBOL specification dictates a strict left-to-right evaluation respecting parenthesization; the COMPUTE at L464-L465 explicitly parenthesizes `( TRAN-CAT-BAL * DIS-INT-RATE)` to force the multiplication to occur first. The Java translation MUST preserve this evaluation order: `Decimals.divideTruncated(Decimals.multiplyTruncated(tranCatBal, disIntRate, 4), BigDecimal.valueOf(1200), 2)` — with an intermediate scale of 4 (combining the two scale-2 operands without intermediate truncation) and the final scale of 2 (matching the WS-MONTHLY-INT PIC S9(09)V99 receiving field).

Sign-nybble handling for COBOL S9(09)V99 output: the `MOVE WS-MONTHLY-INT TO TRAN-AMT` at L490 transfers a signed-numeric value from the working-storage WS-MONTHLY-INT field into the TRAN-RECORD's TRAN-AMT field. Both fields are declared PIC S9(09)V99 (signed, 9 integer digits + 2 fractional digits). The COBOL representation of the sign nybble depends on the field's USAGE clause: DISPLAY (zoned decimal) places the sign as an overpunch in the trailing byte; COMP-3 (packed decimal) uses a 4-bit nybble at the trailing nybble of the packed-decimal byte sequence with values `C` (positive), `D` (negative), or `F` (unsigned). The capture procedure determines which convention applies and the `Decimals.encodeSignedPacked(...)` Java utility produces a byte-identical encoding under the same convention. The Java decoder `Decimals.parseSignedPacked(...)` reads the byte sequence back into a `BigDecimal` for round-trip verification.

Accumulator zeroing at account boundary: the `MOVE 0 TO WS-TOTAL-INT` at `[app/cbl/CBACT04C.cbl:L200]` resets the WS-TOTAL-INT accumulator EVERY time a new account is detected. The Java translation MUST set the accumulator to `BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)` (or equivalent `Decimals.zero(2)`) — preserving the scale-2 invariant so that the next account's accumulation begins from `0.00` rather than from an integer `0` that would later need a scale conversion. The L467 `ADD WS-MONTHLY-INT TO WS-TOTAL-INT` preserves the scale across additions because both operands have scale 2.

## Phase 7: Account-boundary lifecycle

The account-boundary detection and flushing logic at `[app/cbl/CBACT04C.cbl:L188-L222]` proceeds through these 11 enumerated steps in every iteration of the main loop:

1. Main loop structure at L188-L222: `PERFORM UNTIL END-OF-FILE = 'Y'` with nested IF/ELSE on END-OF-FILE state at L189 and L219.
2. GET-NEXT at L190: `PERFORM 1000-TCATBALF-GET-NEXT` (paragraph at L325) — sequential READ of TCATBAL-FILE. A `TCATBALF-STATUS = '10'` (end-of-file) sets `END-OF-FILE = 'Y'` and exits the inner gate.
3. Inner gate at L191: `IF END-OF-FILE = 'N'` — only process the record if the read succeeded.
4. Per-record counter at L192: `ADD 1 TO WS-RECORD-COUNT` (declared at `[app/cbl/CBACT04C.cbl:L172]` as PIC 9(09) VALUE 0).
5. DISPLAY echo at L193: `DISPLAY TRAN-CAT-BAL-RECORD` — emits the entire 50-byte TRAN-CAT-BAL-RECORD as a text image to stdout.
6. Boundary detection at L194: `IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM` — compare current record's account against the last-seen account.
7. First-time gate at L195-L199:
   - L195: `IF WS-FIRST-TIME NOT = 'Y'` — if NOT first time, flush the PREVIOUS account.
   - L196: `PERFORM 1050-UPDATE-ACCOUNT` — REWRITE the previous account's ACCT-CURR-BAL with accumulated WS-TOTAL-INT.
   - L197-L198: ELSE branch — first time only, set `MOVE 'N' TO WS-FIRST-TIME`.
   - L199: END-IF.
8. Reset and load new account:
   - L200: `MOVE 0 TO WS-TOTAL-INT` — reset accumulator for the new account.
   - L201: `MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM` — save new account ID.
   - L202: `MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID` — key for ACCTFILE lookup.
   - L203: `PERFORM 1100-GET-ACCT-DATA` — load ACCOUNT-RECORD into WORKING-STORAGE (paragraph at L372).
   - L204: `MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID` — key for XREF lookup by ALT KEY.
   - L205: `PERFORM 1110-GET-XREF-DATA` — load CARD-XREF-RECORD (paragraph at L393).
9. DISCGRP key setup at L210-L213: build composite key (10-byte ACCT-GROUP-ID + 2-byte TRANCAT-TYPE-CD + 4-byte TRANCAT-CD via `MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID`, `MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD`, `MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD`), then `PERFORM 1200-GET-INTEREST-RATE`.
10. Interest computation gate at L214-L217: `IF DIS-INT-RATE NOT = 0 / PERFORM 1300-COMPUTE-INTEREST / PERFORM 1400-COMPUTE-FEES / END-IF` — skip the COMPUTE and the WRITE when the rate is zero. The `1400-COMPUTE-FEES` paragraph at L518-L520 is empty (`* To be implemented` followed by `EXIT.`) — preserved as dead code per AAP §0.7.1.
11. Outer ELSE at L219-L220 (END-OF-FILE = 'Y' branch): `PERFORM 1050-UPDATE-ACCOUNT` — the COBOL programmer's intent for the FINAL account flush at end-of-file.

Important note on the L219-L220 ELSE branch: the L219 ELSE is the ELSE of the L189 outer IF (`IF END-OF-FILE = 'N'`). With COBOL's default `PERFORM UNTIL ... WITH TEST BEFORE` semantics, the outer PERFORM terminates BEFORE the body executes when `END-OF-FILE = 'Y'` is detected at the top of an iteration. The Java translation MUST therefore implement the final-account flush explicitly OUTSIDE the main loop (immediately after the loop terminates) to ensure parity with the COBOL programmer's clearly-stated intent. This is preserved per AAP §0.7.1 minimum-change clause (do NOT remove or simplify the L219-L220 ELSE branch; mirror the structure faithfully in Java). Document this as IMPLEMENTATION-DECISION in `java/MIGRATION_NOTES.md` §1.6 alongside the capture procedure.

Trace of the supporting paragraphs invoked by the main loop:

- `0000-TCATBALF-OPEN` at L234-L250 — opens TCATBAL-FILE as INPUT; on failure (status not `'00'`) displays `'ERROR OPENING TRANSACTION CATEGORY BALANCE'` and abends.
- `0100-XREFFILE-OPEN` at L252-L268 — opens XREF-FILE as INPUT; on failure displays `'ERROR OPENING CROSS REF FILE'` with the status code and abends.
- `0200-DISCGRP-OPEN` at L270-L286 — opens DISCGRP-FILE as INPUT; on failure displays `'ERROR OPENING DALY REJECTS FILE'` (preserved misnomer per AAP §0.7.1) and abends.
- `0300-ACCTFILE-OPEN` at L289-L305 — opens ACCOUNT-FILE as I-O (the only file opened I-O in this program); on failure displays `'ERROR OPENING ACCOUNT MASTER FILE'` and abends.
- `0400-TRANFILE-OPEN` at L307-L323 — opens TRANSACT-FILE as OUTPUT; on failure displays `'ERROR OPENING TRANSACTION FILE'` and abends.
- `1000-TCATBALF-GET-NEXT` at L325-L348 — sequential READ of TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD; status `'00'` is APPL-AOK, status `'10'` (end-of-file) sets APPL-EOF and `END-OF-FILE = 'Y'`; other statuses display `'ERROR READING TRANSACTION CATEGORY FILE'` and abend.
- `1050-UPDATE-ACCOUNT` at L350-L370 — applies the three mutations (Phase 4) and REWRITEs the account; documented in Phase 4.
- `1100-GET-ACCT-DATA` at L372-L391 — random READ of ACCOUNT-FILE INTO ACCOUNT-RECORD by FD-ACCT-ID; INVALID KEY at L374-L375 displays `'ACCOUNT NOT FOUND: '` followed by the key. The subsequent status check at L378 covers non-INVALID-KEY failures.
- `1110-GET-XREF-DATA` at L393-L413 — random READ of XREF-FILE INTO CARD-XREF-RECORD by FD-XREF-ACCT-ID (the ALTERNATE RECORD KEY); INVALID KEY at L396-L397 displays `'ACCOUNT NOT FOUND: '` followed by the key — note the identical literal `'ACCOUNT NOT FOUND: '` at L375 and L397, preserved per AAP §0.7.1.
- `1200-GET-INTEREST-RATE` at L415-L440 — random READ of DISCGRP-FILE; tolerant of status `'23'` for the DEFAULT fallback; documented in Phase 8.
- `1200-A-GET-DEFAULT-INT-RATE` at L443-L460 — DEFAULT-key retry of DISCGRP-FILE; fatal on any non-`'00'` status.
- `1300-COMPUTE-INTEREST` at L462-L470 — applies the L464 COMPUTE formula and the L467 ADD; documented in Phase 6.
- `1300-B-WRITE-TX` at L473-L515 — populates the 14 TRAN-RECORD fields and WRITEs; documented in Phase 3.
- `1400-COMPUTE-FEES` at L518-L520 — empty paragraph (`* To be implemented` followed by `EXIT.`); preserved per AAP §0.7.1.
- `9000-TCATBALF-CLOSE` at L522-L538 — `CLOSE TCATBAL-FILE`; on failure (status not `'00'`) displays `'ERROR CLOSING TRANSACTION CATEGORY BALANCE'` and abends.
- `9100-XREFFILE-CLOSE` at L540-L556 — `CLOSE XREF-FILE`; on failure displays `'ERROR CLOSING CROSS REF FILE'` and abends.
- `9200-DISCGRP-CLOSE` at L558-L574 — `CLOSE DISCGRP-FILE`; on failure displays `'ERROR CLOSING DISCLOSURE GROUP FILE'` and abends (the misnomer `'DALY REJECTS'` does NOT appear in the CLOSE path; only the OPEN path at L281 carries the misnomer per AAP §0.7.1).
- `9300-ACCTFILE-CLOSE` at L576-L592 — `CLOSE ACCOUNT-FILE`; on failure displays `'ERROR CLOSING ACCOUNT MASTER FILE'` and abends. Critically, this is the LAST file closed before the program exits; any pending REWRITE not yet flushed to disk is committed here.
- `9400-TRANFILE-CLOSE` at L594-L611 — `CLOSE TRANSACT-FILE`; on failure displays `'ERROR CLOSING TRANSACTION FILE'` and abends. This is where the final `WRITE FD-TRANFILE-REC` from `1300-B-WRITE-TX` is flushed to disk; the Java translation closes the corresponding `SeekableByteChannel` here via try-with-resources or explicit `close()` to guarantee buffer flushing.
- `Z-GET-DB2-FORMAT-TIMESTAMP` at L613-L626 — system-clock dependency; documented in Phase 5 and Phase 10.
- `9910-DISPLAY-IO-STATUS` at L635-L648 — formats the 2-byte FILE STATUS into a printable 4-digit form and DISPLAYs `'FILE STATUS IS: NNNN'` followed by the formatted code. Invoked by every failure-path elsewhere in the program before `9999-ABEND-PROGRAM`.
- `9999-ABEND-PROGRAM` at L628-L632 — DISPLAY `'ABENDING PROGRAM'`, MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL `'CEE3ABD'` — the LE abend service. The Java translation throws an unchecked `AbendException(999)` that the main class catches and exits with a non-zero status code.


## Phase 8: DEFAULT-group fallback verification

The DISCGRP NOTFND fallback at `[app/cbl/CBACT04C.cbl:L415-L460]` provides a controlled recovery path when the primary composite-key lookup misses:

1. Primary key lookup: `1200-GET-INTEREST-RATE` at L415 builds the key from `ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD` (assembled at L210-L212) and performs `READ DISCGRP-FILE INTO DIS-GROUP-RECORD` at L416.
2. Tolerant status check at L422: `IF DISCGRP-STATUS = '00' OR '23'` — both `'00'` (success) and `'23'` (record not found) are treated as APPL-AOK because the program has a controlled fallback for the not-found case.
3. Non-tolerant status branch at L428-L435: if the status is neither `'00'` nor `'23'`, `DISPLAY 'ERROR READING DISCLOSURE GROUP FILE'` at L431, then `PERFORM 9910-DISPLAY-IO-STATUS` and `PERFORM 9999-ABEND-PROGRAM` — fatal abend.
4. NOTFND branch at L436-L439 (inside `1200-GET-INTEREST-RATE`):
   - When `DISCGRP-STATUS = '23'`, the INVALID KEY clause at L417 emitted informational DISPLAYs already:
     - L418: `'DISCLOSURE GROUP RECORD MISSING'`
     - L419: `'TRY WITH DEFAULT GROUP CODE'`
   - L437: `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` — override the lookup key with the literal `'DEFAULT'` (10 bytes left-justified, space-padded).
   - L438: `PERFORM 1200-A-GET-DEFAULT-INT-RATE` — re-attempt the READ with the DEFAULT key.
5. DEFAULT lookup at L443-L460: `READ DISCGRP-FILE INTO DIS-GROUP-RECORD` with the DEFAULT-overridden key. A failure at this step (status not `'00'`) emits `'ERROR READING DEFAULT DISCLOSURE GROUP'` at L455 and ABENDs — there is NO recursive fallback (DEFAULT lookup failure is fatal).
6. Fixture invariant: `app/data/ASCII/discgrp.txt` MUST contain enough rows with `DIS-ACCT-GROUP-ID = 'DEFAULT'` (one per TRANCAT-TYPE-CD * TRANCAT-CD combination that the fixture might encounter without a primary match) so the DEFAULT fallback succeeds for any boundary-coverage record. The canonical fixture's DEFAULT-group rows provide the safety net.

The Java translation expresses this fallback as a sealed `LookupResult` permitting `Found(DisGroup)`, `Default(DisGroup)`, and `Missing` permits, with pattern-matching `switch` enforcing exhaustiveness per AAP §0.7.4 (no `default` branch). The fixture's `tcatbal.txt` MAY include records whose composite DISCGRP key has no primary match in `discgrp.txt`, exercising the DEFAULT fallback path; whether or not such records are present is documented in `../input/README.md`.

Recovery sequence trace when DEFAULT lookup succeeds (happy path for a NOTFND scenario):

- Step A — Primary READ at L416 returns `DISCGRP-STATUS = '23'` (record not found) because the composite key `ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD` does not match any DISCGRP row.
- Step B — The L417 INVALID KEY clause fires, executing the inline statements at L418-L419-L437-L438.
- Step C — L418 DISPLAY emits `'DISCLOSURE GROUP RECORD MISSING'` to stdout (visible in `stdout.txt`).
- Step D — L419 DISPLAY emits `'TRY WITH DEFAULT GROUP CODE'` to stdout (also visible in `stdout.txt`).
- Step E — L437 `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` overrides the first 10 bytes of the composite key with the literal `'DEFAULT'` (space-padded to 10 bytes: `'DEFAULT   '`); the TRANCAT-TYPE-CD and TRANCAT-CD portions of the key remain unchanged from the original lookup attempt.
- Step F — L438 `PERFORM 1200-A-GET-DEFAULT-INT-RATE` enters the secondary lookup paragraph at L443-L460.
- Step G — Inside `1200-A-GET-DEFAULT-INT-RATE`, the L444 `READ DISCGRP-FILE INTO DIS-GROUP-RECORD` retries with the DEFAULT-overridden key. On success (`DISCGRP-STATUS = '00'`), the DIS-INT-RATE field of the matched DEFAULT row is loaded into DIS-INT-RATE working storage.
- Step H — Control returns to `1200-GET-INTEREST-RATE`, which returns control to the main loop.
- Step I — The main loop's L214 `IF DIS-INT-RATE NOT = 0` guard evaluates the DEFAULT row's rate; if non-zero, `PERFORM 1300-COMPUTE-INTEREST` fires; if zero, no WRITE occurs.

The stdout.txt capture therefore contains, for each DEFAULT-fallback occurrence, exactly two informational lines (the `'DISCLOSURE GROUP RECORD MISSING'` and `'TRY WITH DEFAULT GROUP CODE'` DISPLAYs) interleaved between the per-record TRAN-CAT-BAL-RECORD echoes. The byte-for-byte parity assertion for `stdout.txt` therefore depends on the exact occurrence pattern of DEFAULT fallbacks across the canonical input fixtures; the capture procedure documents the expected count in `java/MIGRATION_NOTES.md` §1.6.

A representative sketch of the sealed hierarchy (Java 25 records and sealed interface):

```java
sealed interface LookupResult permits LookupResult.Found,
                                       LookupResult.Default,
                                       LookupResult.Missing {
    record Found(DisGroup group) implements LookupResult {}
    record Default(DisGroup group) implements LookupResult {}
    record Missing() implements LookupResult {}
}

// at the call site:
LookupResult result = discountGroupRepository.lookup(key);
switch (result) {
    case LookupResult.Found(DisGroup g)   -> useRate(g.disIntRate());
    case LookupResult.Default(DisGroup g) -> useRate(g.disIntRate());
    case LookupResult.Missing m           -> abend("ERROR READING DEFAULT DISCLOSURE GROUP");
}
```

The pattern-matching `switch` is exhaustive over the three permits; the compiler rejects the code if a permit is added later but the switch is not updated. No `default` branch hides missing cases per AAP §0.7.4.

## Phase 9: Cross-reference to Java test class

Identity of the consuming test class and its inheritance:

- Test class FQCN: `com.blitzy.carddemo.tests.golden.CbAct04CGoldenTest`
- Base class FQCN: `com.blitzy.carddemo.tests.golden.GoldenRecordTest`
- Class under test FQCN: `com.blitzy.carddemo.application.account.CbAct04C` per AAP §0.4.1
- `@Disabled` mandate: the test class is annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until all three captured-output files are committed.

The override-signature surface the test class implements (no implementation bodies — just method signatures):

```java
// com.blitzy.carddemo.tests.golden.CbAct04CGoldenTest
@Override protected Class<?> programClass();
@Override protected Path inputFile();
@Override protected Map<String, Path> auxiliaryInputs();
@Override protected Path expectedOutputFile();
@Override protected List<ExpectedOutput> expectedOutputs();
```

Expected-output resolver convention:

- `resolveExpectedOutputPath("cbact04c", "transact.txt")` -> classpath-resolved `Path` to `java/carddemo-tests/src/test/resources/golden/cbact04c/expected/transact.txt`
- `resolveExpectedOutputPath("cbact04c", "acctdata.txt")` -> classpath-resolved `Path` to this folder's `acctdata.txt`
- `resolveExpectedOutputPath("cbact04c", "stdout.txt")` -> classpath-resolved `Path` to this folder's `stdout.txt`
- The base class asserts byte-for-byte equality via `assertThat(actual).isEqualTo(expected)` from AssertJ (per AAP §0.5.1 dependency inventory).

Architectural mapping in the Java tree:

- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct04C.java` — Class under test; one class per COBOL PROGRAM-ID per AAP §0.4.1.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java` — ACCOUNT-RECORD translation (300-byte record).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java` — TRAN-RECORD translation (350-byte record).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranCatBalRecord.java` — TRAN-CAT-BAL-RECORD translation (50-byte record).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD translation (50-byte record).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/DisGroupRecord.java` — DIS-GROUP-RECORD translation (50-byte record).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/util/Decimals.java` — Decimal arithmetic facade per AAP §0.6.1.
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileAccountRepository.java` — File-adapter REWRITE backing for ACCOUNT-FILE per AAP §0.6.5.
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/InterestCalculationApp.java` — Shaded-jar main class wiring the file adapters to `CbAct04C` per AAP §0.4.1 JCL-to-main mapping.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbAct04CGoldenTest.java` — The consuming test class for this fixture.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Base class with the shared byte-for-byte assertion logic.

The test class remains `@Disabled` until COBOL captures are committed; the assertion is unreachable until then per AAP §0.6.11.

Test harness execution flow when enabled:

- JUnit Jupiter discovers `CbAct04CGoldenTest` via the standard test-class scanner; the `@Disabled` annotation suppresses execution until removed.
- The base class `GoldenRecordTest` provides a single `@Test` method that invokes `runProgram(programClass(), inputFile())` to launch the class under test, then iterates over `expectedOutputs()` to compare each declared output against its captured baseline.
- `runProgram(...)` performs the test-driver responsibilities documented in Phase 10: snapshots the mutable ACCTFILE to a temp directory, prepares writable paths for TRANSACT-FILE and stdout, and invokes the program with the canonical `PARM='2022071800'` argument.
- After the program returns (or throws an `AbendException` propagated from `9999-ABEND-PROGRAM`'s Java equivalent), the base class reads each captured baseline via `Files.readAllBytes(...)` from the classpath, reads the corresponding actual output from the temp directory, and asserts byte equality via `assertThat(actual).isEqualTo(expected)`.
- For `transact.txt`, the field-mask comparison option (Phase 5 option (a)) replaces the 52 volatile bytes in BOTH buffers before assertion; the ScopedValue Clock injection option (Phase 5 option (b)) makes the masking unnecessary.

Concurrency posture: the base class runs the test single-threaded. `CbAct04C` itself is single-threaded (the COBOL source declares no parallelism). Per AAP §0.6.6, virtual threads are NOT used for the per-record posting work because reordering would change the sequence of TRAN-RECORD writes and break the byte-for-byte parity assertion.

## Phase 10: Capture procedure cross-reference

The canonical COBOL build/run path used to capture this fixture's three output files is documented in `java/MIGRATION_NOTES.md` §1.6. Per AAP §0.7.5 the capture procedure documentation is the resolution of the user's `[TODO — document the COBOL build/run path here]` marker. This README defers all step-by-step details (compiler version, environment variables, DD-to-file mapping, GnuCOBOL Clock injection mechanism) to that document; only the determinism requirements appear here.

Determinism requirements for a reproducible capture:

- Fixed `java.time.Clock` via `ScopedValue<Clock>` for `Z-GET-DB2-FORMAT-TIMESTAMP` (`[app/cbl/CBACT04C.cbl:L613-L626]`) — the COBOL `FUNCTION CURRENT-DATE` at L614 reads the system clock; without injection the captured TRAN-ORIG-TS and TRAN-PROC-TS would not be reproducible. The canonical fixed instant for the capture is documented in `java/MIGRATION_NOTES.md` §1.6.
- Fixed `PARM-DATE` of `'2022071800'` (10 bytes) per `[app/jcl/INTCALC.jcl:L22]` `STEP15 EXEC PGM=CBACT04C,PARM='2022071800'` — drives the TRAN-ID prefix via the STRING at L476-L480.
- Fixed inputs: `app/data/ASCII/tcatbal.txt`, `app/data/ASCII/cardxref.txt`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/discgrp.txt` — committed-and-frozen REFERENCE fixtures (NEVER mutated by the capture or the test; per AAP §0.2.2 the `app/` tree is IMMUTABLE).
- Mutable initial state: ACCTFILE is opened I-O at L291 and REWRITTEN at L356; the test harness MUST snapshot a mutable COPY of `app/data/ASCII/acctdata.txt` (into a temporary directory) BEFORE the program runs, then assert byte equality against this folder's captured `acctdata.txt` AFTER the run. The source `app/data/ASCII/acctdata.txt` MUST remain unchanged per AAP §0.2.2.

Re-capture trigger: if any of the four ASCII fixtures change OR the Clock seed changes OR the COBOL source changes OR the JCL PARM changes, this fixture MUST be re-captured per the procedure in `java/MIGRATION_NOTES.md` §1.6. Do NOT ad-hoc edit any of the three expected-output files; ad-hoc edits invalidate the parity assertion.

Test-driver responsibilities for the canonical run sequence:

- Copy the input ACCTFILE from `app/data/ASCII/acctdata.txt` to a writable temporary path (e.g., under `Files.createTempDirectory(...)`) BEFORE invoking `CbAct04C.run(args)`. The source path under `app/` MUST remain unchanged per AAP §0.2.2 (`app/` is IMMUTABLE).
- Pass the temporary copy's path (not the source path) to the program as the ACCTFILE input/output path so the program's REWRITE writes to the temporary copy, not to the immutable source.
- Provide a writable output path for TRANSACT-FILE (a sibling file under the same temp directory) so the WRITE at L500 succeeds.
- Capture stdout (e.g., via redirection of `System.out` to a buffered sink that records every DISPLAY line) to a third writable path.
- After `CbAct04C.run(...)` returns, the harness reads the three resulting files (TRANSACT-FILE output, ACCTFILE temp copy after REWRITEs, stdout buffer) and asserts each against the corresponding captured baseline in this folder.
- The temporary directory is deleted after the assertion completes (success or failure), preserving the workspace cleanliness invariant.

## Phase 11: Behavioral invariants preserved by this contract

The following byte-level and behavioral invariants are preserved by the Java translation and asserted by the golden-record harness:

1. 350-byte LRECL TRAN-RECORD layout with EXACT field positions per CVTRA05Y (14 fields totaling 350 bytes); no padding adjustments, no field reordering.
2. 300-byte FB ACCOUNT-RECORD layout with EXACT field positions per CVACT01Y (12 fields plus 178-byte FILLER totaling 300 bytes).
3. Truncation arithmetic (`RoundingMode.DOWN`) for monetary computations — banker's HALF_EVEN is FORBIDDEN for the L464 COMPUTE because no `ROUNDED` clause is specified.
4. `WS-TRANID-SUFFIX` as a GLOBAL counter (NOT per-account reset) — declared at L173 with `VALUE 0`; incremented at L474 BEFORE the STRING into TRAN-ID; the suffix monotonically increases across all accounts in the batch.
5. DEFAULT-group fallback semantics at L437 — the literal `'DEFAULT'` is the fixed override for FD-DIS-ACCT-GROUP-ID; no recursive fallback (DEFAULT lookup failure is fatal).
6. First-time skip behavior — no REWRITE on the FIRST detected account boundary (the L195 `IF WS-FIRST-TIME NOT = 'Y'` gate) — there is no previous account to flush on the first iteration.
7. End-of-file flush — the L219-L220 ELSE branch invokes `1050-UPDATE-ACCOUNT` one final time for the LAST account in the input (COBOL programmer's intent; the Java translation must implement an explicit post-loop flush to preserve this behavior per Phase 7's implementation-semantics note).
8. DISPLAY TRAN-CAT-BAL-RECORD per input record at L193 — emits the entire 50-byte TRAN-CAT-BAL-RECORD as a text image; one stdout line per successful TCATBAL READ.
9. DISPLAY `'DISCLOSURE GROUP RECORD MISSING'` at L418 and `'TRY WITH DEFAULT GROUP CODE'` at L419 — informational, emitted when DISCGRP-STATUS='23' triggers the DEFAULT fallback; these are NOT abend messages.
10. COBOL `'DALY REJECTS'` misnomer at L281 — the message `'ERROR OPENING DALY REJECTS FILE'` is emitted when the DISCGRP open fails; the misnomer (the message should logically reference `'DISCLOSURE GROUP FILE'`) is PRESERVED AS-IS per AAP §0.7.1 minimum-change mandate. Flag in `java/MIGRATION_NOTES.md` as "L281 message uses 'DALY REJECTS' instead of 'DISCLOSURE GROUP' — preserved verbatim; not fixed".
11. Empty `1400-COMPUTE-FEES` paragraph at L518-L520 — the entire paragraph body is the comment `* To be implemented` followed by `EXIT.`; the Java translation MUST mirror this as a no-op method with a `// To be implemented` comment carrying a `@CobolParagraph("1400-COMPUTE-FEES")` Javadoc-style annotation. Preserved as dead code per AAP §0.7.1.
12. Missing `Z-` paragraph numeric prefix — `Z-GET-DB2-FORMAT-TIMESTAMP` at L613 lacks a 4-digit numeric prefix (other paragraphs use 0000-, 0100-, 1000-, etc.). PRESERVED per AAP §0.7.1; the Java translation cites the original name via a `@CobolParagraph("Z-GET-DB2-FORMAT-TIMESTAMP")` Javadoc-style annotation on the equivalent method.
13. `ACCT-EXPIRAION-DATE` misspelling — the CVACT01Y field name at `[app/cpy/CVACT01Y.cpy:§ACCOUNT-RECORD]` is misspelled (should be `EXPIRATION`). PRESERVED in the Java record field name as `acctExpiraionDate` per AAP §0.7.1.
14. Commented-out DISPLAYs preserved as code comments at L207-L209 (ACCT-GROUP-ID, TRANCAT-CD, TRANCAT-TYPE-CD) and L625 (DB2-TIMESTAMP) — the Java translation includes equivalent commented-out logging calls to preserve the development-time diagnostic intent.
15. Identical literal `'ACCOUNT NOT FOUND: '` at L375 and L397 — the same message text is used for two different lookup contexts (ACCT lookup at L375 inside `1100-GET-ACCT-DATA`, XREF alt-key lookup at L397 inside `1110-GET-XREF-DATA`). PRESERVED per AAP §0.7.1; the Java translation produces byte-identical strings for both invocations.

NONE of these idiosyncrasies are bugs to be "fixed" in the Java translation. Per AAP §0.7.1 Minimum Change Clause, ALL are observable behaviors of the COBOL baseline that downstream consumers (operators monitoring logs, file consumers, audit log scanners) may depend on. Document the catalog in `java/MIGRATION_NOTES.md` alongside the capture procedure for traceability.

What breaks if each invariant is violated (the failure mode for downstream consumers):

- Invariant 1 (350-byte LRECL TRAN-RECORD) — downstream consumers reading `transact.txt` with fixed-width record parsers misalign every field after the first divergence; account reconciliation totals diverge by the magnitude of the field shift.
- Invariant 2 (300-byte FB ACCOUNT-RECORD) — ACCTFILE consumers (downstream batch jobs, online lookups by ACCT-ID) read incorrect bytes for every field after the first misaligned offset; balance lookups return wrong values.
- Invariant 3 (TRUNCATION arithmetic) — the L464 COMPUTE produces interest amounts that differ by up to one cent per record from the COBOL baseline; over millions of records this can shift per-account totals by hundreds of dollars and cause statement reconciliation failures.
- Invariant 4 (GLOBAL WS-TRANID-SUFFIX) — per-account reset would cause TRAN-IDs to collide across accounts (e.g., account A produces `'2022071800000001'`, account B produces `'2022071800000001'`); downstream transaction ledgers detect duplicate primary keys and reject the batch.
- Invariant 5 (DEFAULT-group fallback semantics) — without the L437 `MOVE 'DEFAULT'`, every TCATBAL record with a missing DISCGRP primary match would abend the batch; the controlled fallback is the SLA contract for graceful handling of incomplete reference data.
- Invariant 6 (First-time skip behavior) — without the L195 gate, the first detected account boundary would invoke `1050-UPDATE-ACCOUNT` against an uninitialized ACCOUNT-RECORD, REWRITING the wrong account (or abending on an unset ACCT-ID); the gate is what makes the iterate-then-flush pattern correct.
- Invariant 7 (End-of-file flush) — without the L219-L220 ELSE branch (or its Java-translation post-loop equivalent), the LAST account in the input would never receive its accumulated WS-TOTAL-INT; that account's ACCT-CURR-BAL would not be incremented in `acctdata.txt`, silently losing interest for one account per batch run.
- Invariant 8 (DISPLAY TRAN-CAT-BAL-RECORD) — operators monitoring batch progress through stdout would see different output structure; automated log scanners detecting "no progress" patterns would false-trigger on the missing per-record echo.
- Invariant 9 (Informational DEFAULT-fallback DISPLAYs) — operations runbooks rely on the L418-L419 messages to identify when reference data is incomplete; removing them silences a known signal that downstream audit jobs depend on.
- Invariant 10 (`'DALY REJECTS'` misnomer) — operators trained to grep for the exact message `'ERROR OPENING DALY REJECTS FILE'` in production logs would miss the alert if the message were "fixed" to say `'DISCLOSURE GROUP FILE'`; operational alerts are silent until runbooks are updated, a separate change-management effort.
- Invariant 11 (Empty `1400-COMPUTE-FEES`) — the placeholder is the intentional extension point for future fee logic; removing it loses the COBOL programmer's documented signal that fees are a planned-but-not-implemented feature.
- Invariant 12 (Missing `Z-` prefix) — code-review tooling that infers paragraph categorization from the numeric prefix would mis-categorize `Z-GET-DB2-FORMAT-TIMESTAMP` if the prefix were added; the missing prefix is a documented convention signaling "utility paragraph, not main flow".
- Invariant 13 (`ACCT-EXPIRAION-DATE` misspelling) — Java identifiers that rename to `acctExpirationDate` create a cross-reference asymmetry between the COBOL source (canonical reference) and the Java code; debuggers and trace tools that grep both codebases for the field name miss either the COBOL or the Java side.
- Invariant 14 (Commented-out DISPLAYs) — removing the commented `* DISPLAY ...` lines at L207-L209 and L625 loses the development-time diagnostic intent; future developers re-enabling them for debugging would have to re-derive the field set from scratch.
- Invariant 15 (Identical `'ACCOUNT NOT FOUND: '` literal at L375 and L397) — fixing one but not the other creates an inconsistency that operators monitoring the message would notice as a context-dependent signal; the COBOL idiom of reusing the same literal across lookup contexts is the intended behavior, preserved verbatim.

Why every invariant is preserved rather than corrected: the AAP §0.7.1 Minimum Change Clause is binding. A Java implementation that "fixes" the `DALY REJECTS` misnomer at L281 would diverge from the captured stdout by producing a different DISPLAY message, failing the byte-for-byte parity assertion for `stdout.txt`. A Java implementation that renames `acctExpiraionDate` to `acctExpirationDate` would still pass byte-for-byte parity (because the field name appears nowhere in the wire format), but would create an inconsistency where the Java identifier differs from the original COBOL identifier — making it harder to cross-reference between the COBOL source and the Java code during operations or debugging. The preservation rule applies regardless of whether the divergence is observable in output bytes; the principle is that the COBOL source remains the canonical reference per AAP §0.2.2, and the Java translation is its faithful idiom-for-idiom equivalent.

How the invariants are enforced: the golden-record harness asserts byte-for-byte equality of every captured output file. Any divergence — including a corrected misnomer, a corrected misspelling expressed in output, a re-ordered DISPLAY sequence, or a different rounding mode applied to the L464 COMPUTE — produces a failure with the byte offset of the first difference and the surrounding 32 bytes from both buffers. The test driver's failure report makes it trivial to identify which invariant was violated and which COBOL line corresponds to the divergence.

Translator-facing escalation path: when a Java translator encounters an apparent COBOL bug or anti-pattern during translation, the AAP §0.7.1 mandate is to translate faithfully and flag the issue in `java/MIGRATION_NOTES.md` for a separate follow-up effort. The translator MUST NOT silently fix the issue in this refactor, even if the fix is obvious. Each preserved idiosyncrasy in Phase 11's catalog corresponds to such a finding; the `java/MIGRATION_NOTES.md` entry for CBACT04C lists every one with its source line, the apparent intent, and the recommended follow-up. Downstream owners may decide in a separate effort whether to remediate the issues (e.g., correcting the misspelling, replacing the misnomer message, adding the missing paragraph number prefix), but such decisions are out of scope for this refactor.

## Phase 12: Source lineage

The following source files are REFERENCE only — they remain UNCHANGED per AAP §0.1.1, §0.2.2, and §0.7.1:

- `app/cbl/CBACT04C.cbl` (652 lines) — Primary COBOL source; `PROGRAM-ID CBACT04C` at L23; `AUTHOR AWS` at L24.
- `app/jcl/INTCALC.jcl` (44 lines) — JCL driver; `STEP15 EXEC PGM=CBACT04C,PARM='2022071800'` at L22; TRANSACT DD output declaration at L37-L41.
- `app/cpy/CVTRA05Y.cpy` (21 lines) — TRAN-RECORD layout for `transact.txt` output (350 bytes; 14 fields including TRAN-ID, TRAN-TYPE-CD, TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC, TRAN-AMT, TRAN-MERCHANT-ID, TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY, TRAN-MERCHANT-ZIP, TRAN-CARD-NUM, TRAN-ORIG-TS, TRAN-PROC-TS, FILLER PIC X(20)).
- `app/cpy/CVACT01Y.cpy` (20 lines) — ACCOUNT-RECORD layout for `acctdata.txt` REWRITE (300 bytes; 12 fields plus 178-byte FILLER; note misspelled `ACCT-EXPIRAION-DATE`).
- `app/cpy/CVTRA01Y.cpy` (13 lines) — TRAN-CAT-BAL-RECORD layout for the L193 DISPLAY (50 bytes; 17-byte composite key TRAN-CAT-KEY = TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) + TRANCAT-CD PIC 9(04); plus TRAN-CAT-BAL PIC S9(09)V99 and 22-byte FILLER).
- `app/cpy/CVACT03Y.cpy` (11 lines) — CARD-XREF-RECORD layout providing TRAN-CARD-NUM source for the L495 MOVE (50 bytes; XREF-CARD-NUM PIC X(16) + XREF-CUST-ID PIC 9(09) + XREF-ACCT-ID PIC 9(11) + 14-byte FILLER).
- `app/cpy/CVTRA02Y.cpy` (13 lines) — DIS-GROUP-RECORD layout providing DIS-INT-RATE source for the L465 COMPUTE (50 bytes; 16-byte composite key DIS-GROUP-KEY = DIS-ACCT-GROUP-ID PIC X(10) + DIS-TRAN-TYPE-CD PIC X(02) + DIS-TRAN-CAT-CD PIC 9(04); plus DIS-INT-RATE PIC S9(04)V99 and 28-byte FILLER).
- `app/data/ASCII/tcatbal.txt` — TCATBALF input fixture (REFERENCE; primary driver of the main loop; one TRAN-CAT-BAL-RECORD per record). Each record is 50 bytes per CVTRA01Y; the 17-byte composite key TRAN-CAT-KEY at offsets 0-16 governs sequential read order via the `ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL` declaration at L28-L32.
- `app/data/ASCII/cardxref.txt` — XREFFILE input fixture (REFERENCE; alt-key lookup by ACCT-ID). Each record is 50 bytes per CVACT03Y; the 16-byte primary key XREF-CARD-NUM at offsets 0-15 and the 11-byte alternate key XREF-ACCT-ID at offsets 25-35 govern lookups via the `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID` declaration at L38.
- `app/data/ASCII/acctdata.txt` — ACCTFILE input fixture (REFERENCE; INITIAL state for I-O mode; the test harness creates a mutable copy in a temp directory and asserts against this folder's captured `acctdata.txt`). Each record is 300 bytes per CVACT01Y; the 11-byte primary key ACCT-ID at offsets 0-10 governs random reads via the `ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM` declaration at L41-L45.
- `app/data/ASCII/discgrp.txt` — DISCGRP input fixture (REFERENCE; provides DIS-INT-RATE per composite key; the fixture MUST contain enough DEFAULT-group rows to support the fallback path). Each record is 50 bytes per CVTRA02Y; the 16-byte composite key DIS-GROUP-KEY at offsets 0-15 governs random reads via the `ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM` declaration at L47-L51.

All ten source artifacts (1 COBOL program + 1 JCL job + 5 copybooks + 4 ASCII fixtures, totaling 11 source files when counting the COBOL program) are read-only references for the Java translation; none is copied into `java/`. The Java code reads the COBOL programs and copybooks only at development time (manual translation); the ASCII fixtures are read at test time via classpath relative paths per AAP §0.4.1.

Lineage map between COBOL sources and Java targets (the source-of-truth references for the Java translation of CBACT04C):

- `app/cbl/CBACT04C.cbl` -> `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct04C.java`. The CBACT04C PROGRAM-ID at L23 becomes the Java class name; each PROCEDURE DIVISION paragraph becomes a private Java method preserving the COBOL paragraph name in a `@CobolParagraph` Javadoc-style annotation.
- `app/jcl/INTCALC.jcl` -> `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/InterestCalculationApp.java`. The JCL EXEC step becomes the Java main class wiring the file adapters and invoking `CbAct04C.run(args)` with the canonical `PARM='2022071800'` argument.
- `app/cpy/CVTRA05Y.cpy` -> `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java`. The 01-level group becomes a Java record with 14 fields matching the COBOL field order.
- `app/cpy/CVACT01Y.cpy` -> `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java`. The 12-field 01-level group plus FILLER becomes a Java record with the misspelled field name preserved.
- `app/cpy/CVTRA01Y.cpy` -> `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranCatBalRecord.java`. The 17-byte composite key becomes a nested `TranCatKey` record.
- `app/cpy/CVACT03Y.cpy` -> `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java`.
- `app/cpy/CVTRA02Y.cpy` -> `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/DisGroupRecord.java`. The 16-byte composite key becomes a nested `DisGroupKey` record.
- `app/data/ASCII/tcatbal.txt`, `cardxref.txt`, `acctdata.txt`, `discgrp.txt` -> `java/carddemo-tests/src/test/resources/golden/cbact04c/input/` (REFERENCE only; resolved via classpath at test time per AAP §0.4.1).

## Phase 13: Authority references

The following AAP sections cited throughout this README form the binding authority cascade for the CBACT04C golden-record fixture. Each section governs a specific aspect of the contract documented above:

- AAP §0.1.1 (refactoring objective)
- AAP §0.2.1 (in-scope: golden-record fixtures)
- AAP §0.2.2 (`app/` IMMUTABLE)
- AAP §0.3.1 (harness directory convention)
- AAP §0.3.2 (records, sealed types, ports)
- AAP §0.3.4 (JVM flags)
- AAP §0.3.6 (hexagonal architecture; no Spring)
- AAP §0.4.1 (CBACT04C -> CbAct04C; ASCII fixtures REFERENCE only)
- AAP §0.6.1 (`Decimals` utility; CBACT04C uses TRUNCATION — no `ROUNDED` clause at L464)
- AAP §0.6.2 (sealed-type pattern for composite keys)
- AAP §0.6.4 (`java.time` only)
- AAP §0.6.5 (`java.nio.file`; EBCDIC IBM-1047 default)
- AAP §0.6.6 (sequential execution; `ScopedValue` replaces `ThreadLocal`; `ScopedValue<Clock>` for Z-GET-DB2-FORMAT-TIMESTAMP determinism)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until captures; structured-record diff for timestamp masking)
- AAP §0.7.1 (Minimum Change Clause; preserve `DALY REJECTS` misnomer at L281, empty 1400-COMPUTE-FEES at L518-L520, missing `Z-` prefix, `ACCT-EXPIRAION-DATE` misspelling)
- AAP §0.7.2 (no PAN in production logs)
- AAP §0.7.4 (forbidden features; no preview JEPs; no `default` in pattern-matching)
- AAP §0.7.5 (capture procedure in MIGRATION_NOTES.md §1.6)
- AAP §0.8.1 (citation discipline)

## DO NOT add files here

This folder MUST contain ONLY:

- `README.md` (this file, the contract document)
- Eventually: `transact.txt`, `acctdata.txt`, `stdout.txt` — captured by the procedure in `java/MIGRATION_NOTES.md` §1.6

Do NOT add to this folder:

- Test code (lives in `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/`)
- Java source code (lives in `java/carddemo-application/`)
- Unmasked-timestamp comparison logic — use the harness field-mask comparison mode per Phase 5
- Any subfolders
- `.gitkeep` sentinel — this README's presence preserves the folder
- Captured fixtures for OTHER programs (each program has its own `golden/<program>/expected/` subtree)

Ad-hoc edits to `transact.txt`, `acctdata.txt`, or `stdout.txt` are FORBIDDEN. Always re-run the capture procedure if any input changes; the parity assertion's integrity depends on the captures being reproducible from a documented procedure rather than from manual edits.

Rationale: if a developer were to ad-hoc edit a captured baseline to "make the test pass" while the Java translation produces a different byte sequence, the parity assertion would silently degrade into a tautology — the test would pass even when the Java translation diverges from the COBOL behavior. The contract is preserved by the discipline that captures are only ever produced by the documented COBOL capture procedure and updated only when an input fixture, the Clock seed, the JCL PARM, or the COBOL source genuinely changes. A change to any of those triggers re-capture; a change to the Java translation does not.

Equivalence with `.gitkeep`: some projects use a `.gitkeep` sentinel file to preserve empty directories in version control. This folder uses the more substantive `README.md` instead — both achieve directory preservation, but the README also serves as the contract document and the consumer-facing reference. A separate `.gitkeep` is therefore unnecessary and explicitly forbidden in this folder to avoid duplicate sentinel files.

## Capture procedure summary

The high-level capture procedure (full step-by-step details in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5):

- Compile CBACT04C with a COBOL compiler (GnuCOBOL on the capture host, or z/OS Enterprise COBOL on the mainframe path) against the four input fixtures and the canonical fixed-Clock seed.
- Redirect the TRANSACT DD to a writable filesystem path; provide a mutable working copy of `acctdata.txt` (because the I-O REWRITE updates records in place); capture stdout to a third path.
- Run with `PARM='2022071800'` to match the JCL at `[app/jcl/INTCALC.jcl:L22]`.
- After completion, copy the three resulting files into this folder as `transact.txt`, `acctdata.txt`, `stdout.txt`.
- Verify all three files are non-empty and have the expected record counts (one TRAN-RECORD per non-zero-rate TCATBAL input record; ACCTFILE record count unchanged from input; stdout contains start banner + per-record echo + end banner).

The detailed step-by-step procedure (compiler version, environment variables, DD-to-file mapping, Clock injection mechanism for the GnuCOBOL runtime, expected record counts derived from the canonical input fixtures) is documented in `java/MIGRATION_NOTES.md` §1.6. The procedure also documents the canonical Clock seed (a fixed `Instant` used by both the COBOL capture and the Java test run), the codepage assumption (the fixtures are pre-transcoded to ASCII to match the Java test environment), and the record-count sanity checks that the capture executor MUST perform before committing the captured baselines.

Validation gates the capture procedure executor MUST perform before committing:

- Verify `transact.txt` size is an exact multiple of 350 bytes (record-boundary integrity).
- Verify `acctdata.txt` size is an exact multiple of 300 bytes (record-boundary integrity) AND equal to the input ACCTFILE size (no records added or removed by REWRITE).
- Verify `stdout.txt` first line equals `START OF EXECUTION OF PROGRAM CBACT04C` (the L181 banner) and the last non-empty line equals `END OF EXECUTION OF PROGRAM CBACT04C` (the L230 banner).
- Verify the per-record DISPLAY count in `stdout.txt` matches the number of records in the input `tcatbal.txt` fixture (each successful TCATBAL READ produces one DISPLAY at L193).
- Verify no `ABENDING PROGRAM` line appears in `stdout.txt` (would indicate the L629 DISPLAY in `9999-ABEND-PROGRAM` was reached).

## Test invocation example

To run the CbAct04CGoldenTest in isolation, invoke Maven Surefire from the `java/` directory:

```sh
# From the java/ directory:
mvn -B test -pl carddemo-tests -Dtest=CbAct04CGoldenTest
```

Until the three captured-output files (`transact.txt`, `acctdata.txt`, `stdout.txt`) are present in this folder, the test class is `@Disabled` per AAP §0.6.11 and Maven Surefire will skip it with a "Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6" message in the test report. Once captures are committed, removing the `@Disabled` annotation activates the byte-for-byte parity assertion.

A successful run with all three captures committed produces a passing `CbAct04CGoldenTest` confirming byte-for-byte parity between the Java `com.blitzy.carddemo.application.account.CbAct04C` translation and the captured COBOL baseline. A failing run indicates either a Java translation drift, a missed COBOL idiom, or a Clock-injection or codepage misconfiguration; the failure message includes the byte offset of the first difference and the surrounding 32 bytes from both the expected and the actual buffers, allowing rapid root-cause analysis against the field-by-field tables in Phases 3 and 4.

For local development, the verify lifecycle phase invokes the same test class as part of the full Maven build:

```sh
# From the java/ directory:
mvn -B clean verify
```

This runs every module's tests including the golden-record harness. The carddemo-tests module's Surefire configuration includes `CbAct04CGoldenTest` in the standard discovery pattern. While the test is `@Disabled` the verify phase still passes (Surefire reports it as skipped); once the captures are committed and the `@Disabled` annotation is removed, the verify phase becomes a binding parity gate.

For CI integration, the JFR-baseline performance fixtures in `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaseline.java` are intended to be invoked alongside the golden-record harness so any regression in throughput (per AAP's 10% performance band guideline) is detected at the same time as functional regressions. The combined test report identifies both categories of regression in a single CI run.

JVM flag recommendations when running `CbAct04CGoldenTest` (mirroring the production shaded-jar invocation per AAP §0.3.4):

- `-XX:+UseCompactObjectHeaders` reduces heap by ~20-30% on small-record-heavy workloads like CBACT04C; per AAP §0.3.4 this is a finalized JEP 519 feature in Java 25 LTS.
- `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` provides low-pause generational GC; per AAP §0.3.4 this is a finalized JEP 521 feature in Java 25 LTS.
- No `--enable-preview` flag — preview features are forbidden per AAP §0.7.4.

These flags MAY be passed to Surefire via the `argLine` Maven property in `java/carddemo-tests/pom.xml`. The test class's behavior MUST be identical with and without these flags; the flags affect performance only, not correctness. The golden-record parity assertion is the binding correctness gate; the JFR baseline is the binding performance gate. Both run in the same CI invocation but assert independent properties of the Java translation.

Final reminder for future contributors: the CBACT04C golden-record fixture is a high-fidelity parity contract. Treat the captured baselines as immutable artifacts produced by a deterministic procedure. The procedure is the source of truth; the bytes are its output. Never edit the bytes; always re-run the procedure. The README is the contract that describes what those bytes mean — but it does not produce them.

