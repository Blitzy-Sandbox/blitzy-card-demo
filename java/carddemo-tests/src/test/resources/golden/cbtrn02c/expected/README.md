# Golden-Record Contract — CBTRN02C (Full Posting Engine; Batch JCL POSTTRAN)

This document is the authoritative byte-for-byte contract for the Java translation of COBOL program CBTRN02C, the central credit-card-transaction posting engine driven by the POSTTRAN JCL job.

The companion JUnit 5 test class `com.blitzy.carddemo.tests.golden.CbTrn02CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest` and consumes the five captured fixture files documented below.

The test class is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until ALL FIVE data files (`transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, `stdout.txt`) are present in this folder with content captured from a COBOL reference run and committed.

Every claim in this README about COBOL behavior cites a specific line range in the form `[<path>:Lnnn]` or `[<path>:Lnnn-Lmmm]` per AAP §0.8.1 citation discipline.

CBTRN02C is **THE MOST CRITICAL parity gate** in the entire golden-record harness because it is the central posting engine with 25+ paragraphs, FIVE distinct output files, BigDecimal arithmetic on nine monetary fields, composite-key updates over a 17-byte key, and is the only program with both file-create and file-update semantics over indexed VSAM data sets opened in I-O mode.

The binding cascade flows down through 19 AAP sections enumerated in Phase 0 below.

## Phase 0: Authority and Source-of-Truth Cascade

The following 19 AAP sections govern every byte and every assertion described in this README:

- AAP §0.1.1 — Refactoring objective: byte-for-byte parity COBOL -> Java 25 LTS; no behavior changes
- AAP §0.2.1 — Golden-record fixtures are in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree (COBOL source) is IMMUTABLE; never modified by the Java refactor
- AAP §0.3.1 — Fixture directory layout: `<program>/input/` (documentation marker) + `<program>/expected/` (this folder)
- AAP §0.3.2 — Records pattern, sealed-type pattern, repository ports define the Java translation shape
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521)
- AAP §0.3.6 — Hexagonal architecture; no Spring container, no application framework
- AAP §0.4.1 — CBTRN02C -> CbTrn02C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCE only, NEVER copied into this folder
- AAP §0.6.1 — `Decimals` utility centralizes monetary arithmetic with `MathContext.DECIMAL128`; BigDecimal scale 2 for monetary fields
- AAP §0.6.2 — Sealed-type pattern for composite keys, REDEFINES, and closed value sets
- AAP §0.6.4 — `java.time` only for date and time; never `java.util.Date` or `Calendar`
- AAP §0.6.5 — `java.nio.file` for all file I/O; EBCDIC IBM-1047 default codepage with per-file override
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; sequential execution preserved for ordered output
- AAP §0.6.11 — Golden-record harness is the PR gate; CBTRN02C is THE MOST CRITICAL parity gate; `@Disabled` until COBOL captures committed
- AAP §0.7.1 — Minimal Change Clause; preserve duplicate code 109 description, EXPIRAION field-name misspelling, DALY vs DAILY inconsistency, exact DISPLAY whitespace, TWO PERIODS before `Creating`
- AAP §0.7.2 — No PAN in production logs (mask all but last 4 digits); test driver uses unmasked sink for parity assertion
- AAP §0.7.4 — No preview features (JEP 502 Stable Values, JEP 505 Structured Concurrency, JEP 507 Primitive Patterns, JEP 512 Compact Source Files in production code); no `default` branches that hide cases in pattern-matching switch
- AAP §0.7.5 — Capture procedure for golden-record fixtures is documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — Citation discipline `[<path>:Lnnn]` for every claim about COBOL behavior

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBTRN02C` | `[app/cbl/CBTRN02C.cbl:L23]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBTRN02C.cbl:L24]` |
| Source line count | 731 | `app/cbl/CBTRN02C.cbl` (per `wc -l`) |
| JCL driver | `STEP15 EXEC PGM=CBTRN02C` | `[app/jcl/POSTTRAN.jcl:L23]` |
| JCL job name | `POSTTRAN` | `[app/jcl/POSTTRAN.jcl:L1]` |
| JCL line count | 45 | `app/jcl/POSTTRAN.jcl` (per `wc -l`) |
| JCL DDs (input) | DALYTRAN (SEQ), XREFFILE (KSDS), ACCTFILE (KSDS, I-O), TCATBALF (KSDS, I-O) | `[app/jcl/POSTTRAN.jcl:L28-L42]` |
| JCL DDs (output) | TRANFILE (KSDS, new OUTPUT), DALYREJS (new sequential LRECL=430) | `[app/jcl/POSTTRAN.jcl:L28-L38]` |
| DALYREJS LRECL | 430 | `[app/jcl/POSTTRAN.jcl:L36]` (`DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`) |
| Java FQCN under test | `com.blitzy.carddemo.application.transaction.CbTrn02C` | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbTrn02CGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |

The `@CobolProgram("CBTRN02C")` Javadoc-style annotation declared on the `CbTrn02C` class MUST cite the original PROGRAM-ID literal value, the source path `app/cbl/CBTRN02C.cbl`, and the date of translation per AAP §0.7.1.

Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation that cites the original COBOL paragraph name verbatim, including its 4-digit numeric prefix and any letter suffix (for example `@CobolParagraph("2700-UPDATE-TCATBAL")`, `@CobolParagraph("2700-A-CREATE-TCATBAL-REC")`, `@CobolParagraph("2700-B-UPDATE-TCATBAL-REC")`, `@CobolParagraph("1500-A-LOOKUP-XREF")`, `@CobolParagraph("1500-B-LOOKUP-ACCT")`).

The `@Disabled` mandate enforced by AAP §0.6.11 requires that `CbTrn02CGoldenTest` remain disabled until ALL FIVE data files (`transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, `stdout.txt`) are present in this folder with captured (not placeholder) content; the test is unblocked only after the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 has been executed and the resulting outputs committed.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBTRN02C golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbTrn02CGoldenTest` once the five data files below are captured per AAP §0.7.5. The file is the SOLE direct child of this folder until the COBOL capture step deposits the five expected-output files.

### `transact.txt` — Captured TRANFILE Output (CAPTURE PLACEHOLDER)

- **Format**: 350-byte fixed-width records — FD-TRANFILE-REC = 16-byte FD-TRANS-ID + 334-byte FD-ACCT-DATA per `[app/cbl/CBTRN02C.cbl:L72-L74]`
- **Layout**: TRAN-RECORD per `app/cpy/CVTRA05Y.cpy` (350 bytes total with 14 fields: TRAN-ID PIC X(16), TRAN-TYPE-CD PIC X(02), TRAN-CAT-CD PIC 9(04), TRAN-SOURCE PIC X(10), TRAN-DESC PIC X(100), TRAN-AMT PIC S9(09)V99 mapped to BigDecimal scale 2, TRAN-MERCHANT-ID PIC 9(09), TRAN-MERCHANT-NAME PIC X(50), TRAN-MERCHANT-CITY PIC X(50), TRAN-MERCHANT-ZIP PIC X(10), TRAN-CARD-NUM PIC X(16), TRAN-ORIG-TS PIC X(26), TRAN-PROC-TS PIC X(26) DB2-formatted timestamp, FILLER PIC X(20))
- **Sort order**: Sequential order matching the order of valid (non-rejected) DALYTRAN records — preserved EXACTLY per AAP §0.6.6 (NO virtual threads, NO reordering)
- **Write semantics**: `WRITE FD-TRANFILE-REC FROM TRAN-RECORD` at `[app/cbl/CBTRN02C.cbl:L564]` inside paragraph `2900-WRITE-TRANSACTION-FILE` (L562-L580); OUTPUT mode opened at `[app/cbl/CBTRN02C.cbl:L257]`
- **Byte-for-byte parity rule**: `CbTrn02C.run(...)` MUST produce, for the deterministic input set under a fixed Clock, a `transact.txt` byte sequence equal to this captured baseline BYTE-for-BYTE
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet; `CbTrn02CGoldenTest` is `@Disabled` until captured content is committed

### `acctdata.txt` — Captured ACCTFILE Output (CAPTURE PLACEHOLDER)

- **Format**: 300-byte fixed-width records per `[app/cbl/CBTRN02C.cbl:L87-L89]` (FD-ACCTFILE-REC = 11-byte FD-ACCT-ID PIC 9(11) + 289-byte FD-ACCT-DATA PIC X(289))
- **Layout**: ACCOUNT-RECORD per `app/cpy/CVACT01Y.cpy` (300 bytes with ACCT-ID, ACCT-ACTIVE-STATUS, ACCT-CURR-BAL BigDecimal scale 2, ACCT-CREDIT-LIMIT BigDecimal scale 2, ACCT-CASH-CREDIT-LIMIT BigDecimal scale 2, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT BigDecimal scale 2, ACCT-CURR-CYC-DEBIT BigDecimal scale 2, ACCT-ADDR-ZIP, ACCT-GROUP-ID, FILLER PIC X(178))
- **Update semantics**: REWRITE of existing records ONLY — no new records ever inserted (no WRITE in CBTRN02C for ACCTFILE; only REWRITE at `[app/cbl/CBTRN02C.cbl:L554]` inside paragraph `2800-UPDATE-ACCOUNT-REC` (L545-L560)); I-O mode opened at `[app/cbl/CBTRN02C.cbl:L312]`
- **Updated fields**:
  - ACCT-CURR-BAL += DALYTRAN-AMT for ALL valid transactions (path at L547)
  - ACCT-CURR-CYC-CREDIT += DALYTRAN-AMT when DALYTRAN-AMT >= 0 (path at L548-L549)
  - ACCT-CURR-CYC-DEBIT += DALYTRAN-AMT when DALYTRAN-AMT < 0 (path at L550-L551)
  - All BigDecimal scale 2 per AAP §0.6.1 via `Decimals` utility
- **Byte-for-byte parity rule**: byte-by-byte match expected against captured baseline; the file represents the ACCTFILE state AFTER the program run, not the initial input state

### `tcatbal.txt` — Captured TCATBALF Output (CAPTURE PLACEHOLDER)

- **Format**: 50-byte fixed-width records per `[app/cbl/CBTRN02C.cbl:L92-L97]` (FD-TRAN-CAT-BAL-RECORD = 17-byte FD-TRAN-CAT-KEY composite + 33-byte FD-FD-TRAN-CAT-DATA PIC X(33))
- **Composite key** (17 bytes total): FD-TRANCAT-ACCT-ID PIC 9(11) + FD-TRANCAT-TYPE-CD PIC X(02) + FD-TRANCAT-CD PIC 9(04) per `[app/cbl/CBTRN02C.cbl:L93-L96]`
- **Layout**: TRAN-CAT-BAL-RECORD per `app/cpy/CVTRA01Y.cpy` (composite key + TRAN-CAT-BAL PIC S9(09)V99 as BigDecimal scale 2 + FILLER PIC X(22))
- **Update semantics**: BOTH new-record WRITE (paragraph `2700-A-CREATE-TCATBAL-REC` at `[app/cbl/CBTRN02C.cbl:L503-L524]`) AND existing-record REWRITE (paragraph `2700-B-UPDATE-TCATBAL-REC` at `[app/cbl/CBTRN02C.cbl:L526-L542]`) per the branch at `[app/cbl/CBTRN02C.cbl:L495-L499]`
- **Trigger**: when INVALID KEY fires on READ at L474-L478, `WS-CREATE-TRANCAT-REC = 'Y'` triggers the CREATE path; otherwise the UPDATE path executes with `TRAN-CAT-BAL += DALYTRAN-AMT`
- **I-O mode**: opened at `[app/cbl/CBTRN02C.cbl:L330]`
- **Byte-for-byte parity rule**: byte-by-byte match expected including newly-created records that did not exist in the initial input; the captured baseline reflects both inserts and rewrites

### `dalyrejs.txt` — Captured DALYREJS Output (CAPTURE PLACEHOLDER) — CRITICAL 430-BYTE LAYOUT

- **Format**: 430-byte fixed-width sequential records per `[app/jcl/POSTTRAN.jcl:L36]` `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` and `[app/cbl/CBTRN02C.cbl:L82-L84]` (350-byte FD-REJECT-RECORD + 80-byte FD-VALIDATION-TRAILER)
- **Layout** per REJECT-RECORD declaration at `[app/cbl/CBTRN02C.cbl:L176-L178]`:
  - Bytes 0-349 (350 bytes): REJECT-TRAN-DATA — the entire original 350-byte DALYTRAN-RECORD verbatim (`MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA` at L447)
  - Bytes 350-353 (4 bytes): WS-VALIDATION-FAIL-REASON PIC 9(04) — zero-padded ASCII numeric (one of `0100`, `0101`, `0102`, `0103`, `0109`)
  - Bytes 354-429 (76 bytes): WS-VALIDATION-FAIL-REASON-DESC PIC X(76) — left-justified description with TRAILING SPACES to pad to 76 bytes
- **Example trailer bytes** (total = 80 bytes each, byte-precise):
  - Code 100: `0100INVALID CARD NUMBER FOUND` + 51 trailing spaces = 80 bytes
  - Code 101: `0101ACCOUNT RECORD NOT FOUND` + 52 trailing spaces = 80 bytes
  - Code 102: `0102OVERLIMIT TRANSACTION` + 55 trailing spaces = 80 bytes
  - Code 103: `0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION` + 34 trailing spaces = 80 bytes
  - Code 109: `0109ACCOUNT RECORD NOT FOUND` + 52 trailing spaces = 80 bytes (intentional duplicate of 101 per AAP §0.7.1)
- **Write semantics**: `WRITE FD-REJS-RECORD FROM REJECT-RECORD` at `[app/cbl/CBTRN02C.cbl:L451]` inside paragraph `2500-WRITE-REJECT-REC` (L446-L465); sequential append-only; OUTPUT mode opened at `[app/cbl/CBTRN02C.cbl:L294]`
- **Byte-for-byte parity rule**: byte-by-byte match expected; trailing-space count per record MUST be exact; the 350-byte prefix MUST equal the original DALYTRAN-RECORD bytes including the unmasked TRAN-CARD-NUM at copybook offset

### `stdout.txt` — Captured DISPLAY Output (CAPTURE PLACEHOLDER)

- **Format**: line-based ASCII text. Each COBOL DISPLAY statement produces ONE line of output terminated by the platform newline (LF on the captured run)
- **CRITICAL whitespace preservation** for the completion-summary messages:
  - Line at L227: `` `'TRANSACTIONS PROCESSED :'` `` WS-TRANSACTION-COUNT — **EXACTLY 1 SPACE** before the `:` character inside the literal
  - Line at L228: `` `'TRANSACTIONS REJECTED  :'` `` WS-REJECT-COUNT — **EXACTLY 2 SPACES** before the `:` character inside the literal (intentional COBOL visual alignment so both `:` characters line up across the two lines given the 8-character difference between `PROCESSED` and `REJECTED `)
- **Byte-for-byte parity rule**: the Java test driver's captured stdout MUST equal this baseline BYTE-for-BYTE, including the precise space alignment, embedded colons, and trailing record count digits
- **PAN-masking dichotomy**: COBOL CBTRN02C does NOT DISPLAY individual record contents — the only DISPLAY of DALYTRAN-RECORD is COMMENTED OUT at L207 and L349. Reject records ARE written to `dalyrejs.txt` with the full DALYTRAN-RECORD including any TRAN-CARD-NUM PAN. Per AAP §0.7.2 the production Java logger MUST mask all but the last 4 digits of any PAN that surfaces in an exceptional log path. The captured `dalyrejs.txt` fixture intentionally preserves the COBOL unmasked behavior for parity assertion via a test-only sink. The two surfaces (production log vs. captured reject file) are different and the dichotomy is explained in Phase 12.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file does NOT exist yet

## Phase 3: Conceptual Input Universe

The captured baselines in this folder are produced from a deterministic test run against four ASCII input fixtures:

- DALYTRAN: `app/data/ASCII/dailytran.txt` (sequential 350-byte records per CVTRA06Y)
- XREFFILE: `app/data/ASCII/cardxref.txt` (50-byte records; 16-byte FD-XREF-CARD-NUM key per CVACT03Y)
- ACCTFILE: `app/data/ASCII/acctdata.txt` (300-byte records; 11-byte FD-ACCT-ID PIC 9(11) key per CVACT01Y)
- TCATBALF: `app/data/ASCII/tcatbal.txt` (50-byte records; 17-byte composite key per CVTRA01Y)

All four ASCII fixtures are NEVER copied to this folder per AAP §0.4.1 — they are referenced via classpath relative path from `app/data/ASCII/`.

The rationale for the no-copy rule is that the canonical bytes live in the original COBOL source tree under `app/`, and duplicating those bytes into the Java test resources tree would create a synchronization hazard. Any drift between the two copies would invalidate the parity assertion. By referencing the originals via classpath, the Java test always reads the byte-identical bytes that the COBOL reference run reads.

ACCTFILE and TCATBALF are mutated by the test run (REWRITE semantics on the initial state). The test harness MUST snapshot the initial bytes from the read-only classpath resource into a temporary working copy on the local filesystem, run the program against that working copy, then assert byte equality of the working copy against the captured updated bytes committed under `expected/acctdata.txt` and `expected/tcatbal.txt`. After assertion, the working copy is discarded.

The original `app/data/ASCII/` files MUST remain UNCHANGED at all times per AAP §0.2.2. Any deviation from this rule is a test-harness defect that MUST be reported and fixed before the harness is enabled.

The Java test class resolves the classpath via `this.getClass().getResource("/app/data/ASCII/dailytran.txt")` (or an equivalent helper that loads the file from the test resources copy of the ASCII tree). The resource path begins with `/app/` because the test build is configured to copy the read-only ASCII tree under the test resources root at build time, preserving the original path structure for direct cross-reference.

The codepage applied to ASCII fixtures during the test run is `Charset.forName("US-ASCII")`. The production EBCDIC IBM-1047 default applies only to production deployments, not to the deterministic test capture. The codepage choice is configurable per file via `application.properties` keys such as `carddemo.file.dalytran.charset`, allowing a future production deployment to override the default on a per-file basis without changing Java source.

TRANFILE is an OUTPUT-only file in CBTRN02C (opened OUTPUT at L257 per `[app/cbl/CBTRN02C.cbl:L257]`); it is NOT an input. The captured `transact.txt` IS the contents of that output stream after the run completes. DALYREJS is also OUTPUT-only (opened OUTPUT at L294 per `[app/cbl/CBTRN02C.cbl:L294]`); the captured `dalyrejs.txt` IS the contents of that output stream. The two output-only files exist only after a successful program run; they have no initial-state contribution from the inputs.

## Phase 4: Full Posting Engine Flow (25+ paragraphs)

Every paragraph in `app/cbl/CBTRN02C.cbl` is enumerated below with its exact line citation. The Java translation MUST honor the same control flow, including the ordering of validation checks, the sequence of file opens, and the unconditional execution of certain checks even when prior checks have already set a reject reason.

- PROCEDURE DIVISION entry at `[app/cbl/CBTRN02C.cbl:L193]` — emits the start banner via DISPLAY at L194, performs the six OPEN paragraphs in order, runs the main loop, performs the six CLOSE paragraphs, emits the completion-summary lines (L227-L232), and falls through to `GOBACK`
- Main loop at `[app/cbl/CBTRN02C.cbl:L202-L219]` — `PERFORM UNTIL END-OF-FILE = 'Y'`; per-iteration: `1000-DALYTRAN-GET-NEXT`, increment `WS-TRANSACTION-COUNT`, clear `WS-VALIDATION-FAIL-REASON`, perform `1500-VALIDATE-TRAN`, then branch to `2000-POST-TRANSACTION` (when reason = 0) or to `2500-WRITE-REJECT-REC` (otherwise) and increment `WS-REJECT-COUNT`
- File OPEN paragraphs:
  - `0000-DALYTRAN-OPEN` at `[app/cbl/CBTRN02C.cbl:L236]` (OPEN INPUT DALYTRAN-FILE)
  - `0100-TRANFILE-OPEN` at `[app/cbl/CBTRN02C.cbl:L254]` (OPEN OUTPUT TRANSACT-FILE)
  - `0200-XREFFILE-OPEN` at `[app/cbl/CBTRN02C.cbl:L273]` (OPEN INPUT XREF-FILE)
  - `0300-DALYREJS-OPEN` at `[app/cbl/CBTRN02C.cbl:L291]` (OPEN OUTPUT DALYREJS-FILE)
  - `0400-ACCTFILE-OPEN` at `[app/cbl/CBTRN02C.cbl:L309]` (OPEN I-O ACCOUNT-FILE)
  - `0500-TCATBALF-OPEN` at `[app/cbl/CBTRN02C.cbl:L327]` (OPEN I-O TCATBAL-FILE)
- Sequential read: `1000-DALYTRAN-GET-NEXT` at `[app/cbl/CBTRN02C.cbl:L345]` — `READ DALYTRAN-FILE INTO DALYTRAN-RECORD`; status `'10'` (end-of-file) sets `END-OF-FILE = 'Y'`; status `'00'` continues processing; any other status DISPLAYs the read-error message and PERFORMs `9999-ABEND-PROGRAM`
- Validation paragraphs:
  - `1500-VALIDATE-TRAN` at `[app/cbl/CBTRN02C.cbl:L370]` — PERFORM `1500-A-LOOKUP-XREF`; if `WS-VALIDATION-FAIL-REASON = 0` then PERFORM `1500-B-LOOKUP-ACCT`; otherwise CONTINUE (short-circuit at the XREF level only)
  - `1500-A-LOOKUP-XREF` at `[app/cbl/CBTRN02C.cbl:L380]` — `READ XREF-FILE` keyed on DALYTRAN-CARD-NUM; INVALID KEY sets `WS-VALIDATION-FAIL-REASON = 100` and `WS-VALIDATION-FAIL-REASON-DESC = 'INVALID CARD NUMBER FOUND'`
  - `1500-B-LOOKUP-ACCT` at `[app/cbl/CBTRN02C.cbl:L393]` — `READ ACCOUNT-FILE` keyed on XREF-ACCT-ID; INVALID KEY sets `WS-VALIDATION-FAIL-REASON = 101` and description `'ACCOUNT RECORD NOT FOUND'`; on success runs the in-line over-limit check (code 102) at L407-L412 and the in-line expiration check (code 103) at L414-L420; BOTH checks execute even when an earlier check failed (the later overwrites the earlier WS-VALIDATION-FAIL-REASON, as documented in Phase 9)
- Posting paragraph: `2000-POST-TRANSACTION` at `[app/cbl/CBTRN02C.cbl:L424]` — copies the 14 DALYTRAN-* fields into the corresponding TRAN-* fields, calls `Z-GET-DB2-FORMAT-TIMESTAMP` to populate `TRAN-PROC-TS` (PERFORM at L437), then performs `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, and `2900-WRITE-TRANSACTION-FILE` in that exact order
- Reject paragraph: `2500-WRITE-REJECT-REC` at `[app/cbl/CBTRN02C.cbl:L446]` — `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA` at L447, `MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER`, `WRITE FD-REJS-RECORD FROM REJECT-RECORD` at L451; on any non-`'00'` DALYREJS-STATUS DISPLAYs `'ERROR WRITING TO REJECTS FILE'` at L460 and ABENDs
- Update paragraphs:
  - `2700-UPDATE-TCATBAL` at `[app/cbl/CBTRN02C.cbl:L467]` — assigns the 17-byte composite key from XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD, sets `WS-CREATE-TRANCAT-REC = 'N'`, `READ TCATBAL-FILE`; on INVALID KEY DISPLAYs `` `'TCATBAL record not found for key : '` `` FD-TRAN-CAT-KEY `` `'.. Creating.'` `` at L476-L477 (note the TWO PERIODS before `Creating`) and sets `WS-CREATE-TRANCAT-REC = 'Y'`; branches to the CREATE path (L496) or UPDATE path (L498) per the flag
  - `2700-A-CREATE-TCATBAL-REC` at `[app/cbl/CBTRN02C.cbl:L503]` — `INITIALIZE TRAN-CAT-BAL-RECORD`, assigns the three composite-key component fields, `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, then `WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD`; on write failure DISPLAYs `'ERROR WRITING TRANSACTION BALANCE FILE'` at L520 and ABENDs
  - `2700-B-UPDATE-TCATBAL-REC` at `[app/cbl/CBTRN02C.cbl:L526]` — `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, then `REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD`; on rewrite failure DISPLAYs `'ERROR REWRITING TRANSACTION BALANCE FILE'` at L538 and ABENDs
  - `2800-UPDATE-ACCOUNT-REC` at `[app/cbl/CBTRN02C.cbl:L545]` — `ADD DALYTRAN-AMT TO ACCT-CURR-BAL` (always), conditionally `ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT` (when amount >= 0) or `ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT` (when amount < 0), then `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` at L554; on INVALID KEY sets `WS-VALIDATION-FAIL-REASON = 109` and description `'ACCOUNT RECORD NOT FOUND'` (intentional duplicate of code 101 text)
  - `2900-WRITE-TRANSACTION-FILE` at `[app/cbl/CBTRN02C.cbl:L562]` — `WRITE FD-TRANFILE-REC FROM TRAN-RECORD` at L564; on write failure DISPLAYs `'ERROR WRITING TO TRANSACTION FILE'` at L574 and ABENDs
- File CLOSE paragraphs:
  - `9000-DALYTRAN-CLOSE` at `[app/cbl/CBTRN02C.cbl:L582]`
  - `9100-TRANFILE-CLOSE` at `[app/cbl/CBTRN02C.cbl:L600]`
  - `9200-XREFFILE-CLOSE` at `[app/cbl/CBTRN02C.cbl:L619]`
  - `9300-DALYREJS-CLOSE` at `[app/cbl/CBTRN02C.cbl:L637]`
  - `9400-ACCTFILE-CLOSE` at `[app/cbl/CBTRN02C.cbl:L655]`
  - `9500-TCATBALF-CLOSE` at `[app/cbl/CBTRN02C.cbl:L674]`
- Helper paragraphs:
  - `Z-GET-DB2-FORMAT-TIMESTAMP` at `[app/cbl/CBTRN02C.cbl:L692]` — `MOVE FUNCTION CURRENT-DATE TO COBOL-TS`, then component-by-component MOVEs into DB2-FORMAT-TS overlaid by the redefining group; assembles a 26-byte timestamp in the literal format `YYYY-MM-DD HH.MM.SS.MM0000`. **Note**: this paragraph lacks a 4-digit numeric prefix that the other paragraphs use (0000-, 0100-, 1000-, 2700-A-, etc.). The `Z-` prefix is an unusual but legacy z/OS convention for utility paragraphs and is PRESERVED per AAP §0.7.1.
  - `9999-ABEND-PROGRAM` at `[app/cbl/CBTRN02C.cbl:L707]` — DISPLAYs `'ABENDING PROGRAM'` at L708, MOVEs 999 TO ABCODE, CALLs `'CEE3ABD'`
  - `9910-DISPLAY-IO-STATUS` at `[app/cbl/CBTRN02C.cbl:L714]` — emits `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 from BOTH branches of the IF/ELSE (numeric-vs-non-numeric/9 status); the literal text is identical across both branches and the trailing IO-STATUS-04 value is the only varying portion

After completion of the main loop, lines L227-L231 emit the final counts via DISPLAY and conditionally set `RETURN-CODE = 4` when `WS-REJECT-COUNT > 0`; line L232 then emits the unconditional end banner `'END OF EXECUTION OF PROGRAM CBTRN02C'`.

The control-flow ordering between paragraphs is fixed and observable. For every valid transaction, the sequence is `1000-DALYTRAN-GET-NEXT` -> `1500-VALIDATE-TRAN` -> `2000-POST-TRANSACTION` -> (`2700-UPDATE-TCATBAL` -> `2800-UPDATE-ACCOUNT-REC` -> `2900-WRITE-TRANSACTION-FILE`).

For every rejected transaction, the sequence is `1000-DALYTRAN-GET-NEXT` -> `1500-VALIDATE-TRAN` -> `2500-WRITE-REJECT-REC`.

The TCATBAL update precedes the ACCOUNT update which precedes the TRANFILE write. Reordering these three operations would be observable to downstream consumers because a TCATBAL write failure aborts before the ACCOUNT update, and an ACCOUNT update failure aborts before the TRANFILE write. The Java translation MUST preserve this exact ordering — no reordering and no parallelization across these three operations within a single transaction.

The validation paragraphs `1500-A-LOOKUP-XREF` and `1500-B-LOOKUP-ACCT` are also ordered: the XREF lookup runs first, and the ACCOUNT lookup runs only when the XREF lookup succeeded. This short-circuit is implemented at the XREF level only — when XREF fails the ACCOUNT lookup is skipped, but when ACCOUNT lookup succeeds the over-limit check at L407-L412 and the expiration check at L414-L420 BOTH run unconditionally as described in Phase 9.

## Phase 5: Verbatim COBOL DISPLAY Message Catalog

The complete catalog of 25 verbatim DISPLAY messages emitted by CBTRN02C is enumerated below. The Java translation MUST produce byte-identical strings for each entry, including embedded spaces, embedded colons, embedded single quotes (none in this catalog), and trailing literals.

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'START OF EXECUTION OF PROGRAM CBTRN02C'` `` | L194 | Unconditional start banner |
| 2 | `` `'TRANSACTIONS PROCESSED :'` `` WS-TRANSACTION-COUNT | L227 | EXACTLY 1 SPACE before `:` inside literal |
| 3 | `` `'TRANSACTIONS REJECTED  :'` `` WS-REJECT-COUNT | L228 | EXACTLY 2 SPACES before `:` inside literal |
| 4 | `` `'END OF EXECUTION OF PROGRAM CBTRN02C'` `` | L232 | Unconditional end banner |
| 5 | `` `'ERROR OPENING DALYTRAN'` `` | L247 | DALYTRAN open failure |
| 6 | `` `'ERROR OPENING TRANSACTION FILE'` `` | L265 | TRANFILE open failure |
| 7 | `` `'ERROR OPENING CROSS REF FILE'` `` | L284 | XREF open failure |
| 8 | `` `'ERROR OPENING DALY REJECTS FILE'` `` | L302 | DALYREJS open failure (note source spelling `DALY` not `DAILY`) |
| 9 | `` `'ERROR OPENING ACCOUNT MASTER FILE'` `` | L320 | ACCTFILE open failure |
| 10 | `` `'ERROR OPENING TRANSACTION BALANCE FILE'` `` | L338 | TCATBALF open failure |
| 11 | `` `'ERROR READING DALYTRAN FILE'` `` | L363 | DALYTRAN read failure |
| 12 | `` `'TCATBAL record not found for key : '` `` FD-TRAN-CAT-KEY `` `'.. Creating.'` `` | L476-L477 | New TCATBAL record creation notice — preserve TWO PERIODS before `Creating` |
| 13 | `` `'ERROR READING TRANSACTION BALANCE FILE'` `` | L489 | TCATBALF read failure on non-INVALID-KEY error |
| 14 | `` `'ERROR WRITING TRANSACTION BALANCE FILE'` `` | L520 | TCATBALF write failure (new record path) |
| 15 | `` `'ERROR REWRITING TRANSACTION BALANCE FILE'` `` | L538 | TCATBALF rewrite failure (existing record path) |
| 16 | `` `'ERROR WRITING TO REJECTS FILE'` `` | L460 | DALYREJS write failure |
| 17 | `` `'ERROR WRITING TO TRANSACTION FILE'` `` | L574 | TRANFILE write failure |
| 18 | `` `'ERROR CLOSING DALYTRAN FILE'` `` | L593 | DALYTRAN close failure |
| 19 | `` `'ERROR CLOSING TRANSACTION FILE'` `` | L611 | TRANFILE close failure |
| 20 | `` `'ERROR CLOSING CROSS REF FILE'` `` | L630 | XREF close failure |
| 21 | `` `'ERROR CLOSING DAILY REJECTS FILE'` `` | L648 | DALYREJS close failure — note source spelling `DAILY` not `DALY` (different from L302) |
| 22 | `` `'ERROR CLOSING ACCOUNT FILE'` `` | L666 | ACCTFILE close failure |
| 23 | `` `'ERROR CLOSING TRANSACTION BALANCE FILE'` `` | L685 | TCATBALF close failure |
| 24 | `` `'ABENDING PROGRAM'` `` | L708 | Before `CALL 'CEE3ABD'` in `9999-ABEND-PROGRAM` |
| 25 | `` `'FILE STATUS IS: NNNN'` `` IO-STATUS-04 | L721, L725 | After file errors; both branches of `9910-DISPLAY-IO-STATUS` emit the identical literal |

ALL messages above preserve EXACT bytes including embedded SPACES, embedded COLONS, the TWO PERIODS before `Creating` at L476-L477, and the spelling inconsistencies between L302 (`DALY`) and L648 (`DAILY`). NO Unicode characters appear in any message. The Java translation MUST produce byte-identical strings for each catalogued message — these strings are observed by operators monitoring `stdout.txt` and downstream tooling parsing FILE STATUS codes.

## Phase 6: Five Reject Codes (PRESERVED EXACTLY)

The COBOL program emits one of five reject codes when validation fails. The codes, their descriptions, the lines that assign them, the originating paragraphs, and the triggering condition are catalogued below:

| Code | Description | Code Line | Desc Line | Paragraph | Trigger |
|---|---|---|---|---|---|
| `0100` | `INVALID CARD NUMBER FOUND` | L385 | L386 | `1500-A-LOOKUP-XREF` | XREF READ INVALID KEY |
| `0101` | `ACCOUNT RECORD NOT FOUND` | L397 | L398 | `1500-B-LOOKUP-ACCT` | ACCTFILE READ INVALID KEY |
| `0102` | `OVERLIMIT TRANSACTION` | L410 | L411 | `1500-B-LOOKUP-ACCT` | ACCT-CREDIT-LIMIT < WS-TEMP-BAL where WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT (computation at L403-L405) |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | L417 | L418 | `1500-B-LOOKUP-ACCT` | ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS (1:10) — first-10-chars substring comparison at L414 (note source spells `EXPIRAION`) |
| `0109` | `ACCOUNT RECORD NOT FOUND` | L556 | L557 | `2800-UPDATE-ACCOUNT-REC` | REWRITE INVALID KEY — intentional duplicate description of code 101 per AAP §0.7.1 |

Three CRITICAL preservation rules apply to these five codes:

- Per AAP §0.7.1 the **duplicate description** for codes 101 (L398) and 109 (L557) — both literally `ACCOUNT RECORD NOT FOUND` — is PRESERVED EXACTLY. The COBOL source intentionally uses identical text strings for two different scenarios (lookup failure on initial READ vs. rewrite failure after the record was previously located). The Java translation MUST emit byte-identical text strings; do NOT differentiate code 101 from code 109 by their description bytes.
- The misspelling `EXPIRAION` (should be `EXPIRATION`) appears in the COBOL FIELD NAME `ACCT-EXPIRAION-DATE` at L414 of the program and at L11 of `app/cpy/CVACT01Y.cpy`. The DESCRIPTION literal `'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'` at L418 IS correctly spelled — only the field name is misspelled. The Java translation preserves the field-name misspelling (e.g., the Java record field is `acctExpiraionDate`) per AAP §0.7.1.
- Codes 102 and 103 can BOTH be set on the same transaction. The over-limit check (L407-L412) and the expiration check (L414-L420) are sequential IF/ELSE blocks, NOT chained with `EVALUATE` or `IF/ELSE-IF`. When over-limit fires first and sets code 102, the expiration check still executes. If the expiration condition is also out-of-range, code 103 overwrites code 102 in `WS-VALIDATION-FAIL-REASON` because the MOVE at L417 unconditionally replaces the prior MOVE at L410. The Java translation MUST execute both checks unconditionally and let the later overwrite the earlier — do NOT introduce short-circuit logic that skips the expiration check when over-limit has fired.

The five reject codes form a closed taxonomy modelled in Java as a `sealed interface RejectReason permits RejectReason.Code100, RejectReason.Code101, RejectReason.Code102, RejectReason.Code103, RejectReason.Code109` per AAP §0.6.2.

The reject-reason discriminator at every site that maps a code to its trailer description uses a pattern-matching switch with the five permits enumerated explicitly — NO `default` branch is permitted per AAP §0.7.4. If a sixth reject code is ever introduced (which is out of scope for this refactor per AAP §0.7.1), the compiler will refuse to compile every switch site until the new permit is added. This is the safety guarantee that the closed taxonomy provides.

## Phase 7: 430-Byte DALYREJS Record Layout

The DALYREJS output is the most layout-sensitive of the five output files because each record carries a fixed 430-byte payload composed of two concatenated structural regions. Java code that writes this file MUST produce EXACT byte-for-byte trailer content including precise trailing-space counts.

- **Total record size**: 430 bytes = 350 bytes original DALYTRAN data + 80 bytes validation trailer (per `[app/cbl/CBTRN02C.cbl:L82-L84]` `FD-REJS-RECORD` = `FD-REJECT-RECORD PIC X(350)` + `FD-VALIDATION-TRAILER PIC X(80)`)
- **Validation trailer breakdown** (80 bytes):
  - 4 bytes: WS-VALIDATION-FAIL-REASON PIC 9(04) — zero-padded numeric code
  - 76 bytes: WS-VALIDATION-FAIL-REASON-DESC PIC X(76) — left-justified description with TRAILING SPACE padding to a fixed 76-byte width
- **Source code reference**: WS-VALIDATION-TRAILER declared at `[app/cbl/CBTRN02C.cbl:L180-L182]`:

```cobol
01 WS-VALIDATION-TRAILER.
   05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
   05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
```

- **Example trailer bytes** appearing in the first 4 bytes of the trailer (per code value): `0100`, `0101`, `0102`, `0103`, `0109`
- **Example descriptions** in trailer bytes 4-79 (left-justified, padded with trailing spaces to total exactly 76 bytes each):
  - Code 100: `INVALID CARD NUMBER FOUND` (25 chars) + 51 trailing spaces = 76 bytes
  - Code 101: `ACCOUNT RECORD NOT FOUND` (24 chars) + 52 trailing spaces = 76 bytes
  - Code 102: `OVERLIMIT TRANSACTION` (21 chars) + 55 trailing spaces = 76 bytes
  - Code 103: `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` (42 chars) + 34 trailing spaces = 76 bytes
  - Code 109: `ACCOUNT RECORD NOT FOUND` (24 chars) + 52 trailing spaces = 76 bytes (same as 101 per AAP §0.7.1)
- **Total 80-byte trailer examples** (4-byte code + 76-byte description):
  - Code 100: `0100INVALID CARD NUMBER FOUND` + 51 trailing spaces = 80 bytes
  - Code 101: `0101ACCOUNT RECORD NOT FOUND` + 52 trailing spaces = 80 bytes
  - Code 102: `0102OVERLIMIT TRANSACTION` + 55 trailing spaces = 80 bytes
  - Code 103: `0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION` + 34 trailing spaces = 80 bytes
  - Code 109: `0109ACCOUNT RECORD NOT FOUND` + 52 trailing spaces = 80 bytes

The Java translation MUST produce EXACT byte-for-byte trailer content including the precise trailing-space counts enumerated above. Use a zero-padding utility equivalent to `Decimals.encodeNumeric(reasonCode, 4)` for the 4-byte code (always zero-padded to width 4 in ASCII numeric form). Use a left-justified space-padded formatter equivalent to `String.format("%-76s", description)` for the 76-byte description.

The 350-byte prefix MUST contain the original DALYTRAN-RECORD bytes unchanged from the input read at `1000-DALYTRAN-GET-NEXT`. This includes any unmasked TRAN-CARD-NUM (a 16-byte PAN at the DALYTRAN-CARD-NUM offset within CVTRA06Y), preserved verbatim for parity assertion against the COBOL baseline. The unmasked PAN is a parity-only artifact in the captured fixture file; production code applies masking via a separate sink as documented in Phase 12.

The trailer construction sequence in COBOL is three-stage:

1. The validation paragraphs (`1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT`) populate `WS-VALIDATION-FAIL-REASON` (numeric) and `WS-VALIDATION-FAIL-REASON-DESC` (alphanumeric) via separate MOVE statements as soon as a validation failure is detected.
2. The reject paragraph `2500-WRITE-REJECT-REC` performs `MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER` at L448 which copies BOTH the 4-byte code AND the 76-byte description in a single group MOVE into the output record area.
3. `WRITE FD-REJS-RECORD FROM REJECT-RECORD` at L451 emits the 350 + 80 = 430 bytes to the DALYREJS file.

The Java translation MUST follow the same three-stage construction order. Doing so ensures that any partial-update scenario (e.g., an exception thrown mid-MOVE during validation, or an I/O failure during the WRITE) produces an observable intermediate state that matches the COBOL baseline.

The reason-description MOVE in COBOL uses the COBOL alphanumeric MOVE semantics: a source literal shorter than the target field is left-justified and padded with spaces on the right to fill the target field. The Java translation MUST replicate this exactly using `String.format("%-76s", description)` or an equivalent left-padding-with-spaces formatter.

The padding rules are strict: NEVER right-justify the description; NEVER use a padding character other than ASCII space (0x20); NEVER omit the padding bytes when the description is shorter than 76 characters; and NEVER truncate a description that exceeds 76 characters (none of the five descriptions exceed 76 chars, but the rule is enforced at the formatter level so future additions cannot silently corrupt the trailer).

The 80-byte trailer byte-offset reference (zero-indexed offsets within the 430-byte record, where bytes 0-349 are the DALYTRAN payload prefix and bytes 350-429 are the trailer):

| Offset (zero-indexed) | Length | Field | COBOL Type | Population Source |
|---|---|---|---|---|
| 0 | 350 | REJECT-TRAN-DATA | PIC X(350) | MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA at L447 |
| 350 | 4 | WS-VALIDATION-FAIL-REASON | PIC 9(04) | One of `0100`, `0101`, `0102`, `0103`, `0109` |
| 354 | 76 | WS-VALIDATION-FAIL-REASON-DESC | PIC X(76) | Left-justified literal, space-padded to 76 bytes |

The trailer composition is opaque to any consumer that reads the record from offset 0: the consumer sees a single 430-byte record. The internal partition between the 350-byte payload and the 80-byte trailer is documented by the FD declaration and is the only legal interpretation of the bytes; any consumer that parses the file MUST use these offsets.

## Phase 8: Five Output Files Byte Contracts

For each of the five outputs, the byte-sequence rules and parity expectation are catalogued below. Each entry cites the exact COBOL source line for the FD declaration and the WRITE/REWRITE statement, plus the OPEN mode.

1. **TRANFILE (`transact.txt`)**: 350-byte records; FD declared at `[app/cbl/CBTRN02C.cbl:L72-L74]`; `WRITE FD-TRANFILE-REC FROM TRAN-RECORD` at `[app/cbl/CBTRN02C.cbl:L564]` inside `2900-WRITE-TRANSACTION-FILE`; `OPEN OUTPUT TRANSACT-FILE` at `[app/cbl/CBTRN02C.cbl:L257]`; sequential append-only; record order MUST match the order of valid (non-rejected) DALYTRAN records — preserving DALYTRAN input order exactly per AAP §0.6.6
2. **ACCTFILE (`acctdata.txt`)**: 300-byte records; FD declared at `[app/cbl/CBTRN02C.cbl:L87-L89]`; `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` at `[app/cbl/CBTRN02C.cbl:L554]` inside `2800-UPDATE-ACCOUNT-REC`; `OPEN I-O ACCOUNT-FILE` at `[app/cbl/CBTRN02C.cbl:L312]`; existing-record update only — no new inserts; the final captured file reflects the cumulative REWRITE state after all valid transactions have been applied
3. **TCATBALF (`tcatbal.txt`)**: 50-byte records; FD declared at `[app/cbl/CBTRN02C.cbl:L92-L97]` with the 17-byte composite key `FD-TRAN-CAT-KEY` declared at `[app/cbl/CBTRN02C.cbl:L93-L96]`; `WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD` at `[app/cbl/CBTRN02C.cbl:L511]` inside `2700-A-CREATE-TCATBAL-REC` for new records, AND `REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD` at `[app/cbl/CBTRN02C.cbl:L529]` inside `2700-B-UPDATE-TCATBAL-REC` for existing records; `OPEN I-O TCATBAL-FILE` at `[app/cbl/CBTRN02C.cbl:L330]`; mixed insert/update semantics with composite-key keyed access
4. **DALYREJS (`dalyrejs.txt`)**: 430-byte records (350 + 80); FD declared at `[app/cbl/CBTRN02C.cbl:L82-L84]`; `WRITE FD-REJS-RECORD FROM REJECT-RECORD` at `[app/cbl/CBTRN02C.cbl:L451]` inside `2500-WRITE-REJECT-REC`; `OPEN OUTPUT DALYREJS-FILE` at `[app/cbl/CBTRN02C.cbl:L294]`; sequential append-only; record order MUST match the order of reject occurrences in DALYTRAN input order
5. **STDOUT (`stdout.txt`)**: line-based ASCII text; DISPLAY statements at the lines enumerated in Phase 5; the final four lines per L227-L232 are emitted unconditionally in sequence after the main loop terminates; intermediate DISPLAYs occur on file-error conditions (each followed by `CALL 'CEE3ABD'` ABEND) and on new-TCATBAL-record creation events (L476-L477)

For all five outputs, byte-for-byte equality is enforced by `GoldenRecordTest.byteForByteParity()`. Trailing whitespace and padding direction are preserved exactly (no left-padding where the COBOL source specifies right-padding, and vice versa).

Sign-nybble representation for any COMP-3 values (none in the externally-encoded ASCII fixtures here, but applicable when EBCDIC encoding is used in a future production deployment) is preserved per AAP §0.6.1.

Record boundaries are preserved per the file's LRECL (350, 300, 50, 430, or line-delimited for stdout). The Java writer MUST emit exactly LRECL bytes per record without inserting record separators that are not present in the COBOL baseline.

The line-based stdout file is the only output of the five that uses a record separator at all. The COBOL DISPLAY statement implicitly appends the platform newline (LF on the captured Linux run) at the end of each emitted line. The Java translation MUST emit `\n` (LF, byte 0x0A) and NOT `\r\n` (CRLF) — any CRLF emission would be a parity defect because the captured baseline is byte-identical to the LF-only COBOL output.

The four fixed-width binary outputs (transact, acctdata, tcatbal, dalyrejs) carry no inter-record separator. The Java writer issues exactly LRECL bytes per record back-to-back, with no newline, no NUL byte, and no padding between records. A consumer that reads the file MUST seek by LRECL multiples to access record N; there is no record-delimiter scan possible because the inter-record bytes simply do not exist.

## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

The following eight COBOL idiosyncrasies are intentionally preserved by the Java translation per the AAP §0.7.1 Minimal Change Clause. NONE of these are bugs to be "fixed"; they are observable behaviors that downstream consumers (file consumers, operators monitoring logs, monitoring tools parsing FILE STATUS codes) may depend on.

1. **Intentional duplicate description** for reject codes 101 (L398) and 109 (L557): both use the literal string `ACCOUNT RECORD NOT FOUND`. Code 101 fires on lookup failure (READ INVALID KEY in `1500-B-LOOKUP-ACCT`); code 109 fires on rewrite failure (REWRITE INVALID KEY in `2800-UPDATE-ACCOUNT-REC`). The scenarios differ but the observable text is identical. PRESERVED per AAP §0.7.1; do NOT differentiate the two codes by their description bytes.
2. **Misspelling `ACCT-EXPIRAION-DATE`** (should be `EXPIRATION`) in the COBOL field name at L414 (`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`) and originating from copybook `app/cpy/CVACT01Y.cpy:L11`. The reject DESCRIPTION literal `'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'` IS correctly spelled — only the FIELD NAME is misspelled. The Java translation preserves the field-name spelling (the Java record field is named `acctExpiraionDate`) per AAP §0.7.1.
3. **Spelling inconsistency `DALY` vs `DAILY`**: the OPEN-error DISPLAY at L302 reads `'ERROR OPENING DALY REJECTS FILE'` (note `DALY`), while the CLOSE-error DISPLAY at L648 reads `'ERROR CLOSING DAILY REJECTS FILE'` (note `DAILY`). The SAME file is described with inconsistent open/close spelling. PRESERVED EXACTLY per AAP §0.7.1; both literal strings must appear byte-identical in `stdout.txt`.
4. **DISPLAY spacing**: 1 space before `:` at L227 (`'TRANSACTIONS PROCESSED :'`), 2 spaces before `:` at L228 (`'TRANSACTIONS REJECTED  :'`) — intentional COBOL visual alignment to make both `:` characters line up vertically (the source paragraphs are `PROCESSED` at 9 chars and `REJECTED` at 8 chars, requiring 1 extra space before the colon on the REJECTED line). PRESERVED EXACTLY.
5. **TCATBAL message at L476-L477** contains TWO PERIODS before `Creating`: the literal is `'.. Creating.'` (two leading periods, one trailing period). PRESERVED EXACTLY; the Java translation MUST emit the two-period sequence and the trailing period verbatim.
6. **`Z-GET-DB2-FORMAT-TIMESTAMP` paragraph at L692-L706 lacks a 4-digit numeric prefix**. Other paragraphs use 4-digit prefixes (`0000-`, `0100-`, `1000-`, `2700-A-`, etc.). The `Z-` prefix is an unusual but legacy z/OS convention for "Z" utility paragraphs. PRESERVED per AAP §0.7.1; the `@CobolParagraph` annotation on the corresponding Java method MUST cite the exact original name `"Z-GET-DB2-FORMAT-TIMESTAMP"`.
7. **Codes 102 and 103 can BOTH be set on the same transaction**. The over-limit check at L407-L412 and the expiration check at L414-L420 are sequential IF/ELSE blocks (not chained with `EVALUATE` or `IF/ELSE-IF`). When over-limit fires first and assigns code 102 to `WS-VALIDATION-FAIL-REASON`, the expiration check still executes. If the expiration condition is also out-of-range, code 103 overwrites code 102 because the MOVE at L417 unconditionally replaces the prior MOVE at L410. PRESERVED per literal source ordering. The Java translation MUST execute both checks unconditionally and let the later overwrite the earlier; do NOT introduce short-circuit logic that skips the expiration check when over-limit has already fired. The reject record written to `dalyrejs.txt` for such a transaction will carry the LATER code (103) in its trailer, not the earlier code (102), and the captured fixture preserves this ordering.
8. **RETURN-CODE 4 only when WS-REJECT-COUNT > 0** at L229-L231 (`IF WS-REJECT-COUNT > 0 / MOVE 4 TO RETURN-CODE / END-IF`). The Java translation MUST set the JVM exit code to 4 when any rejects occurred during the run, and 0 otherwise. The condition is strict greater-than zero; a count of exactly zero produces RETURN-CODE 0. PRESERVED per AAP §0.7.1.

NONE of these idiosyncrasies are bugs to be "fixed". They are observable behaviors that downstream consumers may legitimately depend on:

- File consumers reading `dalyrejs.txt` may parse the 4-byte reject code and the description bytes to route rejected transactions
- Operators tailing `stdout.txt` may grep for the literal strings to monitor batch progress
- Monitoring tools parsing the `FILE STATUS IS: NNNN` lines may interpret the trailing four-character status value to trigger alerts
- Shell scripts inspecting the JCL step return code may branch on the exit value 4 vs. 0

Per the AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim — any "improvement" constitutes a behavior change outside the migration scope and is therefore FORBIDDEN by this refactor.

## Phase 10: Java Mapping Invariants

The following invariants govern the Java translation of CBTRN02C. Each invariant cites its governing AAP section.

- **One class per COBOL PROGRAM-ID**: `CBTRN02C` maps to `CbTrn02C` in `com.blitzy.carddemo.application.transaction` per AAP §0.4.1. There is no consolidation of paragraphs across programs and no helper "service" class that absorbs business logic away from the single use-case class.
- **`@CobolProgram("CBTRN02C")` annotation** declared on the `CbTrn02C` class citing the original PROGRAM-ID literal, the source path `app/cbl/CBTRN02C.cbl`, and the translation date per AAP §0.7.1. The annotation is read by traceability tooling and is the formal contract that this Java class is the canonical translation of that COBOL program.
- **`@CobolParagraph` annotations** on each private method that translates a COBOL paragraph. The annotation value is the verbatim COBOL paragraph name with its numeric prefix and any letter suffix preserved (for example `@CobolParagraph("2700-UPDATE-TCATBAL")`, `@CobolParagraph("2700-A-CREATE-TCATBAL-REC")`, `@CobolParagraph("2700-B-UPDATE-TCATBAL-REC")`, `@CobolParagraph("1500-A-LOOKUP-XREF")`, `@CobolParagraph("1500-B-LOOKUP-ACCT")`, `@CobolParagraph("2800-UPDATE-ACCOUNT-REC")`, `@CobolParagraph("Z-GET-DB2-FORMAT-TIMESTAMP")`).
- **Constructor injection** of repository ports (NO Spring, NO container, NO reflection): the `CbTrn02C` constructor accepts six dependencies — `DailyTransactionRepository`, `CardXrefRepository`, `AccountRepository`, `TransactionRepository`, `TransactionCategoryBalanceRepository`, and a reject-sink port. All six ports are declared as Java interfaces in `com.blitzy.carddemo.domain.port` per AAP §0.3.2 and AAP §0.3.6.
- **`BigDecimal` with `MathContext.DECIMAL128` and scale 2** for ALL monetary values: ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, DALYTRAN-AMT, TRAN-AMT, TRAN-CAT-BAL, WS-TEMP-BAL — nine fields total — per AAP §0.6.1. ALL arithmetic on these fields is centralized in the `com.blitzy.carddemo.domain.util.Decimals` facade; no inline BigDecimal arithmetic appears anywhere in the use-case class.
- **`String` for all 26-byte timestamps**: TRAN-ORIG-TS, TRAN-PROC-TS, DALYTRAN-ORIG-TS, DALYTRAN-PROC-TS — stored as Java `String` to preserve the COBOL `(1:10)` substring semantics at L414 (`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`). If a date computation is required elsewhere, the Java code converts to `java.time.LocalDateTime` via `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")` per AAP §0.6.4 — never `java.util.Date` and never `java.util.Calendar`.
- **`Clock` injected via `ScopedValue<Clock>`** for the deterministic `Z-GET-DB2-FORMAT-TIMESTAMP` translation per AAP §0.6.6. The COBOL `FUNCTION CURRENT-DATE` at L693 reads the system clock — without a fixed-Clock injection the captured timestamps would not be reproducible across runs. The Java translation reads `ScopedValue<Clock>` (bound at the outer composition root in `carddemo-app`) and applies it everywhere a wall-clock value is required.
- **NO `java.util.Date`, NO `java.util.Calendar`, NO `java.text.SimpleDateFormat`** anywhere in the translation per AAP §0.6.4.
- **NO `java.io.File`** anywhere in the translation per AAP §0.6.5; all file I/O uses `java.nio.file.Path`, `Files.newByteChannel`, and `SeekableByteChannel`.
- **NO `ThreadLocal`** anywhere in the translation per AAP §0.6.6; `ScopedValue` carries any cross-method context (batch run id, processing date, tenant, Clock). CBTRN02C is single-threaded sequentially; there is no virtual-thread fan-out because sort order of TRANFILE and DALYREJS is observable to downstream consumers and would be broken by reordering.
- **EBCDIC IBM-1047 default codepage** per AAP §0.6.5 for production runs; per-file override via `application.properties` keys such as `carddemo.file.dalytran.charset`. The ASCII fixtures under `app/data/ASCII/` use `Charset.forName("US-ASCII")` (also accepted via the `US-ASCII` charset name) for the deterministic test run.
- **Sealed-type pattern** per AAP §0.6.2 applied to: the 17-byte composite TCATBAL key (modelled as a sealed interface with a single record permit `TranCatKey` carrying the three component fields, leaving room for future variants); the 5-permit reject-reason hierarchy (`sealed interface RejectReason permits RejectReason.Code100, RejectReason.Code101, RejectReason.Code102, RejectReason.Code103, RejectReason.Code109`); the FILE STATUS code hierarchy (`sealed interface FileStatus permits FileStatus.Ok, FileStatus.EndOfFile, FileStatus.NotFound, FileStatus.IoError`).
- **Pattern-matching switch with exhaustiveness** for any case discrimination (e.g., mapping a FILE STATUS code to a typed exception, mapping a `RejectReason` permit to its trailer description). NO `default` branch is permitted per AAP §0.7.4 — the compiler enforces exhaustiveness so adding a new permit forces an explicit case at every switch site.
- **Records, not POJOs**: `TranRecord` (from CVTRA05Y), `DalyTranRecord` (from CVTRA06Y), `AccountRecord` (from CVACT01Y), `CardXrefRecord` (from CVACT03Y), `TranCatBalRecord` (from CVTRA01Y) are ALL declared as Java `record` types per AAP §0.3.2. They are immutable, have canonical constructors that may validate field ranges via JEP 513 Flexible Constructor Bodies, and are passed by value across all method boundaries.
- **`parse(byte[])` static factory and `encode()` instance method** on each record per AAP §0.3.2. The byte-level contract requires that for every valid 350-byte (or 300-byte, 50-byte, 430-byte) buffer `buf`, the invariant `parse(buf).encode()` MUST equal `buf` byte-for-byte. The round-trip is the formal contract with external file consumers and is asserted by the golden-record harness on every PR.
- **NO Spring, NO Hibernate, NO Spring Batch** anywhere in the Java tree per AAP §0.3.6. The use case is composed by plain constructor injection at the `carddemo-app` composition root; the batch driver in `carddemo-batch` is a small main-loop class without any framework.
- **Sequential execution mandated** per AAP §0.6.6 — NO virtual-thread fan-out is permitted in CBTRN02C because the output sort order is observable to downstream consumers and would be broken by reordering. The single-threaded sequential loop in `2000-POST-TRANSACTION` translates to a single-threaded Java loop that processes one DALYTRAN record at a time end-to-end through the validate-and-post sequence before reading the next record.
- **JVM flags** documented for the shaded jar runtime: `-XX:+UseCompactObjectHeaders` (JEP 519, finalized in Java 25) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521, finalized in Java 25) per AAP §0.3.4.
- **NO `--enable-preview`** anywhere in the runtime or in any Maven configuration per AAP §0.7.4; no preview JEP (502, 505, 507, 512) is used in production code.

In addition to these structural invariants, the Java translation MUST honor the following per-paragraph translation conventions when implementing `CbTrn02C`:

- Each translated paragraph becomes a `private` method on `CbTrn02C` with a void return type when the original paragraph terminates by falling through, OR a typed return value when the original paragraph sets a working-storage flag that the caller branches on (e.g., `WS-CREATE-TRANCAT-REC` returned as a `boolean` from the TCATBAL lookup).
- Each translated paragraph that reads or writes a file dispatches through the constructor-injected repository port; the use-case class itself contains no direct file I/O calls. The file-name-to-port mapping is fixed: DALYTRAN -> `DailyTransactionRepository`; XREFFILE -> `CardXrefRepository`; ACCTFILE -> `AccountRepository`; TCATBALF -> `TransactionCategoryBalanceRepository`; TRANFILE -> `TransactionRepository`; DALYREJS -> the reject-sink port.
- COBOL `INVALID KEY` clauses translate to typed exception handling on the corresponding repository method, using the sealed `FileStatus` hierarchy as the discriminator. Each `INVALID KEY` site in the COBOL source maps to a try/catch where the caught exception's `FileStatus` permit determines the next action (reject vs. retry vs. ABEND).
- COBOL `MOVE` of one record into another translates to a record-with-modified-fields copy using the canonical constructor on the target record, NOT a mutable setter call (records have no setters by definition).
- COBOL group MOVE (e.g., `MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER` at L448) translates to a wholesale byte-array copy at the appropriate offset within the in-memory output buffer; the Java writer holds the in-memory buffer and emits it via `Files.write` or `SeekableByteChannel.write` when the WRITE paragraph executes.

## Phase 11: Test Class Override Map

The test class `CbTrn02CGoldenTest` extends the abstract `GoldenRecordTest` base and provides the canonical override methods that wire the COBOL fixture coordinates into the byte-for-byte parity assertion. The override methods return classpath-resolved paths to the read-only ASCII inputs and to the captured expected-output files in this folder.

```java
// com.blitzy.carddemo.tests.golden.CbTrn02CGoldenTest
@Override
protected Class<?> programClass() {
    return com.blitzy.carddemo.application.transaction.CbTrn02C.class;
}

@Override
protected Path inputFile() {
    return resolveAsciiFixture("dailytran.txt");
}

@Override
protected Map<String, Path> auxiliaryInputs() {
    return Map.of(
        "XREFFILE", resolveAsciiFixture("cardxref.txt"),
        "ACCTFILE", resolveAsciiFixture("acctdata.txt"),
        "TCATBALF", resolveAsciiFixture("tcatbal.txt")
    );
}

@Override
protected Path expectedOutputFile() {
    return resolveExpectedOutputPath("cbtrn02c", "stdout.txt");
}

@Override
protected List<ExpectedOutput> expectedOutputs() {
    return List.of(
        new ExpectedOutput("stdout.txt", resolveExpectedOutputPath("cbtrn02c", "stdout.txt")),
        new ExpectedOutput("transact.txt", resolveExpectedOutputPath("cbtrn02c", "transact.txt")),
        new ExpectedOutput("acctdata.txt", resolveExpectedOutputPath("cbtrn02c", "acctdata.txt")),
        new ExpectedOutput("tcatbal.txt", resolveExpectedOutputPath("cbtrn02c", "tcatbal.txt")),
        new ExpectedOutput("dalyrejs.txt", resolveExpectedOutputPath("cbtrn02c", "dalyrejs.txt"))
    );
}
```

When the `@Disabled` annotation is removed (after the five expected-output files are captured and committed per AAP §0.7.5), the enabled test MUST verify all twelve of the following conditions:

1. Classpath fixture resolver returns non-null for each of the 4 ASCII inputs (dailytran.txt, cardxref.txt, acctdata.txt, tcatbal.txt) on the test classpath.
2. Expected `transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, and `stdout.txt` all exist and are non-empty (captured content, NOT placeholder content).
3. Expected outputs are committed via the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited by any agent or human.
4. The Clock is fixed via `ScopedValue<Clock>` to a known instant matching the COBOL capture run; without a deterministic Clock the `Z-GET-DB2-FORMAT-TIMESTAMP` paragraph would emit non-reproducible timestamps in TRAN-PROC-TS.
5. ACCTFILE and TCATBALF initial-state snapshots equal the read-only `app/data/ASCII/acctdata.txt` and `app/data/ASCII/tcatbal.txt` bytes BEFORE execution begins; the test harness loads the initial state from the classpath resource and operates on a mutable copy.
6. Original `app/data/ASCII/` files remain BYTE-IDENTICAL after the test run completes (test harness MUST NOT mutate the source files per AAP §0.2.2).
7. No production code dependency references `System.out`. The DISPLAY translations in production use SLF4J for log emission; the test driver attaches an unmasked test-only sink to capture the stdout-equivalent bytes for byte-for-byte parity assertion.
8. The `Decimals` utility is exercised at 100% line coverage per AAP §0.6.1 by the jqwik property tests under `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/property/`.
9. The `@CobolProgram("CBTRN02C")` annotation is present on the `CbTrn02C` class with the literal PROGRAM-ID value, the source path `app/cbl/CBTRN02C.cbl`, and the translation date.
10. `@CobolParagraph` annotations are present on each translated paragraph method, citing the full original COBOL paragraph name with its numeric prefix and any letter suffix (e.g., `2700-UPDATE-TCATBAL`, `1500-A-LOOKUP-XREF`, `2700-A-CREATE-TCATBAL-REC`, `2700-B-UPDATE-TCATBAL-REC`, `2800-UPDATE-ACCOUNT-REC`, `Z-GET-DB2-FORMAT-TIMESTAMP`).
11. No `--enable-preview` flag appears in the test runner's JVM args, and no preview JEP (502, 505, 507, 512) usage appears in the production or test code.
12. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and the `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` flags (JEP 521) are documented in the test runner's JVM args (as required by AAP §0.3.4) and applied to representative load-test invocations.

The `GoldenRecordTest` base class executes the parity assertion via a fixed sequence of steps. First, the harness resolves the input fixture and the auxiliary inputs through `inputFile()` and `auxiliaryInputs()`. Second, the harness binds a fixed `Clock` via `ScopedValue.where(ClockHolder.CLOCK, Clock.fixed(...))` so that any wall-clock call inside the use case observes a deterministic instant. Third, the harness instantiates the program-under-test class via reflection on `programClass()` and the constructor signature documented in `CbTrn02C`. Fourth, the harness runs the program and captures the stdout-equivalent byte stream into an in-memory buffer for comparison with `expectedOutputFile()`. Fifth, for each entry in `expectedOutputs()`, the harness asserts byte-for-byte equality between the actual output bytes (produced by the test run) and the expected output bytes (committed in this folder).

The byte-for-byte comparison uses a direct `Arrays.equals(byte[], byte[])` call after loading both arrays via `Files.readAllBytes`. There is no field-by-field comparison, no record-level masking, and no tolerance for whitespace differences. The harness reports the first diverging byte offset on failure to accelerate triage, but the assertion itself is an all-or-nothing byte equality check.

## Phase 12: Capture Procedure Cross-Reference

- See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build-and-run path used to capture this fixture's expected files. The capture procedure exists because the user prompt left this step as a `TODO` marker per AAP §0.7.5; the procedure is the single source of truth for how to regenerate the five expected-output files from a COBOL reference run.
- **Determinism**: three concrete preconditions ensure that re-running the COBOL capture produces byte-identical outputs:
  - Clock fixed via `ScopedValue<Clock>` for `Z-GET-DB2-FORMAT-TIMESTAMP` (L692-L706). The COBOL `FUNCTION CURRENT-DATE` at L693 returns the system clock; without injection the captured TRAN-PROC-TS timestamps would not be reproducible.
  - ACCTFILE and TCATBALF initial states are the read-only `app/data/ASCII/acctdata.txt` and `app/data/ASCII/tcatbal.txt` snapshots committed to the repository; the capture procedure operates on mutable copies and leaves the originals unchanged.
  - Input DALYTRAN (`app/data/ASCII/dailytran.txt`) and XREFFILE (`app/data/ASCII/cardxref.txt`) are read-only references; the COBOL program never writes to them, and the test harness opens them in INPUT mode only.
- **Capture command shape** (illustrative; the canonical path is documented in `java/MIGRATION_NOTES.md` §1.6):
  - Compile CBTRN02C with GnuCOBOL or z/OS Enterprise COBOL using the same options as the production build.
  - Execute the resulting binary with DD-name-to-file-path assignments pointing to the four ASCII fixtures (with mutable working copies of ACCTFILE and TCATBALF for the I-O semantics).
  - Capture stdout into `stdout.txt`.
  - Capture the OUTPUT TRANFILE bytes into `transact.txt`.
  - Capture the final REWRITE state of the mutable ACCTFILE copy into `acctdata.txt`.
  - Capture the final state of the mutable TCATBALF copy (including any newly created records) into `tcatbal.txt`.
  - Capture the OUTPUT DALYREJS bytes into `dalyrejs.txt`.
  - Commit all five files into this folder.
  - After the commit, remove the `@Disabled` annotation from `CbTrn02CGoldenTest` and verify that `mvn -B clean verify` from `java/` runs the parity assertion with all five outputs matching byte-for-byte.
- **PAN-masking dichotomy** (CRITICAL):
  - COBOL CBTRN02C does NOT DISPLAY DALYTRAN-RECORD — the only DISPLAY statements that would have logged the record contents are COMMENTED OUT at L207 and L349. As a result `stdout.txt` does NOT contain any unmasked PAN value.
  - COBOL DOES WRITE the entire 350-byte DALYTRAN-RECORD into `dalyrejs.txt` via the chain `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA` at L447 followed by `WRITE FD-REJS-RECORD FROM REJECT-RECORD` at L451. The unmasked TRAN-CARD-NUM (16-byte PAN per CVTRA06Y DALYTRAN-CARD-NUM PIC X(16)) appears in the 350-byte prefix at the copybook offset.
  - Per AAP §0.7.2, production Java logs MUST mask all but the last 4 digits of any PAN that surfaces in an exceptional log path. The captured `dalyrejs.txt` fixture preserves the COBOL unmasked behavior for parity assertion via a test-only sink that does not apply masking. The production-bound logger applies masking via a separate sink that is NOT the path used by the parity assertion.
  - The captured fixture lives only in the test resources tree under `java/carddemo-tests/src/test/resources/golden/cbtrn02c/expected/`; it is NOT part of any production deployment artifact, NOT copied into the shaded jar, and NOT transmitted to any external system.
- **Until capture is performed**: `CbTrn02CGoldenTest` is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")`. The parity assertion is unreachable while the test is disabled; no false-positive pass is possible because the JUnit Jupiter platform skips the test method entirely.
- **Re-capture trigger**: if the ASCII fixtures change, OR the Clock seed changes, OR the COBOL source changes for any reason (including a corrected upstream backport from the COBOL tree, though AAP §0.2.2 forbids that for this refactor), this fixture MUST be re-captured. Do NOT ad-hoc edit any of the five output files; always re-run the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.

## Phase 13: Scenario Inventory

The fixture MUST exercise at least the following 17 scenarios in the combined test run. The capture procedure produces one set of outputs for the entire combined input file; the individual scenarios are described here to document the coverage that the captured baseline encodes.

Each scenario corresponds to one or more DALYTRAN input records in `app/data/ASCII/dailytran.txt`. The total record count of the combined input is whatever the existing ASCII fixture provides; scenarios listed below describe the categorical coverage that the captured baseline encodes.

1. **Happy path** — valid card, in-limit, in-expiration: records appended to TRANFILE, ACCT balances updated (ACCT-CURR-BAL += DALYTRAN-AMT; ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT updated by sign), TCATBAL composite-key record updated (or created if the key did not previously exist), no rejects emitted.
2. **Reject code 100** (invalid card) — DALYTRAN-CARD-NUM has no matching XREF record; INVALID KEY fires on READ XREF-FILE at L383; record appended to DALYREJS with trailer `0100INVALID CARD NUMBER FOUND` + 51 trailing spaces (80 bytes total).
3. **Reject code 101** (account not found) — XREF-ACCT-ID has no matching ACCOUNT-FILE record; INVALID KEY fires on READ ACCOUNT-FILE at L396; trailer `0101ACCOUNT RECORD NOT FOUND` + 52 trailing spaces.
4. **Reject code 102** (over-limit) — ACCT-CREDIT-LIMIT < ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT; trailer `0102OVERLIMIT TRANSACTION` + 55 trailing spaces.
5. **Reject code 103** (after expiration) — ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10); trailer `0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION` + 34 trailing spaces.
6. **Reject code 109** (account rewrite failed) — REWRITE INVALID KEY at L555-L558 in `2800-UPDATE-ACCOUNT-REC`; trailer `0109ACCOUNT RECORD NOT FOUND` + 52 trailing spaces (intentional duplicate of 101's text per AAP §0.7.1).
7. **Combined 102+103** — both conditions fire on the same transaction; the later check (code 103) wins because it executes second and overwrites `WS-VALIDATION-FAIL-REASON` per L417-L420; the Java translation MUST execute both checks unconditionally and let the later overwrite the earlier.
8. **New TCATBAL record** (composite key not found) — INVALID KEY fires on READ TCATBAL-FILE at L474; DISPLAY `` `'TCATBAL record not found for key : '` `` FD-TRAN-CAT-KEY `` `'.. Creating.'` `` at L476-L477 emits a one-line notice on stdout; `WS-CREATE-TRANCAT-REC = 'Y'` triggers the CREATE branch; the new record is WRITTEN via `2700-A-CREATE-TCATBAL-REC` at L503-L524.
9. **Existing TCATBAL record** (composite key found) — record located on READ; `WS-CREATE-TRANCAT-REC` stays `'N'`; record REWRITTEN with `TRAN-CAT-BAL += DALYTRAN-AMT` via `2700-B-UPDATE-TCATBAL-REC` at L526-L542.
10. **Negative DALYTRAN-AMT** — sign-aware ACCT update path exercises the L550-L551 branch: ACCT-CURR-BAL += amount (always), ACCT-CURR-CYC-DEBIT += amount (only when amount < 0).
11. **Non-negative DALYTRAN-AMT** — sign-aware ACCT update path exercises the L548-L549 branch: ACCT-CURR-BAL += amount (always), ACCT-CURR-CYC-CREDIT += amount (only when amount >= 0).
12. **Empty DALYTRAN input** — main loop terminates immediately on the first GET-NEXT (END-OF-FILE='Y' on `'10'` status); DISPLAYs emit `` `'TRANSACTIONS PROCESSED :'` `` with 9 zero digits and `` `'TRANSACTIONS REJECTED  :'` `` with 9 zero digits; RETURN-CODE remains 0 (no rejects).
13. **All-reject input** — every DALYTRAN record fails validation; RETURN-CODE 4 is set per L229-L231; all records appear in `dalyrejs.txt`; `transact.txt` is empty (zero bytes) because no record satisfies the success path.
14. **Mixed input (some posted, some rejected)** — RETURN-CODE 4 is set (any reject sets the code per the strict `> 0` condition); `transact.txt` contains only the posted records; `dalyrejs.txt` contains only the rejected records; ACCT and TCATBAL are updated only for the posted records.
15. **Sequential execution verification** — NO virtual threads, NO reordering. The test verifies identical output bytes across multiple runs with the same inputs and the same Clock binding; the ordering of TRANFILE and DALYREJS records MUST match the DALYTRAN input order exactly.
16. **Boundary BigDecimal value at credit-limit edge** — DALYTRAN-AMT exactly equals (ACCT-CREDIT-LIMIT - ACCT-CURR-CYC-CREDIT + ACCT-CURR-CYC-DEBIT); the comparison at L407 is strict less-than (`ACCT-CREDIT-LIMIT < WS-TEMP-BAL`), so an exact equality posts successfully without triggering code 102; the captured fixture verifies this boundary semantics.
17. **Zero DALYTRAN-AMT** — neither the CYC-CREDIT branch (L548-L549, condition `>= 0`) nor the CYC-DEBIT branch (L550-L551, condition `< 0`) is exclusively exercised because zero satisfies the `>= 0` condition; ACCT-CURR-CYC-CREDIT is updated by adding zero (a no-op observably, but the REWRITE still occurs); the captured fixture verifies that zero-amount transactions still produce a TRANFILE write and an ACCT REWRITE without rejection.

## Contrast Matrix — CBTRN02C vs. sibling programs

The following matrix contrasts CBTRN02C with three sibling batch programs to highlight what makes CBTRN02C the most critical parity gate.

| Aspect | CBTRN02C (this) | CBTRN01C | CBTRN03C | CBACT04C |
|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch) | CB (batch) | CB (batch) |
| Role | FULL POSTING ENGINE | Daily transaction loader/validator | Paginated report writer | Interest calculator |
| Source line count | 731 lines | varies | 649 lines | varies |
| Output file count | 5 (transact, acctdata, tcatbal, dalyrejs, stdout) | varies | 2 (reptfile, stdout) | varies |
| I-O mode files | ACCTFILE + TCATBALF | none | none | TCATBALF + ACCTFILE |
| REWRITE semantics | YES (ACCT at L554, TCATBAL at L529) | NO | NO | YES |
| WRITE new records | YES (TRANFILE at L564, DALYREJS at L451, new TCATBAL at L511) | partial | NO | YES |
| Reject mechanism | 430-byte DALYREJS records (350+80) | partial | N/A | N/A |
| Reject codes | 5 (100, 101, 102, 103, 109) | N/A | 999 abend only | N/A |
| Clock dependency | YES (`Z-GET-DB2-FORMAT-TIMESTAMP` for TRAN-PROC-TS at L437) | N/A | NO | YES |
| Virtual threads allowed | NO (sequential mandated) | NO | NO | NO |
| Test fixture pattern | input/README + expected/README + 5 placeholder data files | input/README + expected/README | input/README + expected/README + 2 placeholder data files | input/README + expected/README |
| Parity criticality | THE MOST CRITICAL per AAP §0.6.11 | secondary | secondary | secondary |

CBTRN02C's combination of five output files, dual I-O-mode file updates (ACCTFILE and TCATBALF in I-O), composite-key insert-or-update semantics on TCATBALF, BigDecimal-arithmetic-heavy posting logic across nine monetary fields, and Clock-dependent timestamp generation is what makes it THE MOST CRITICAL parity gate in the harness per AAP §0.6.11. A parity failure on any single one of the five output files signals a translation defect that no other golden fixture in the harness would catch.

## Verbatim Message Catalog Cross-Reference

The 25 messages enumerated in Phase 5 are the COMPLETE catalog of DISPLAY statements emitted by CBTRN02C. Any future change to a CBTRN02C DISPLAY string would be recorded as a deviation in `java/MIGRATION_NOTES.md`, but NONE are expected because the AAP §0.7.1 Minimal Change Clause forbids modifying message strings during this migration.

The catalog deliberately preserves the following observable idiosyncrasies:

- The `DALY` (L302) vs `DAILY` (L648) open/close inconsistency for the same DALYREJS file
- The 1-vs-2 space alignment between `'TRANSACTIONS PROCESSED :'` (L227, 1 space) and `'TRANSACTIONS REJECTED  :'` (L228, 2 spaces)
- The TWO PERIODS before `Creating` at L476-L477 in the TCATBAL creation notice

The Java translation MUST emit byte-identical strings for each of the 25 catalogued messages — these strings are the production observable contract with operators and monitoring systems.

## Source Lineage

The following source files are REFERENCE only and remain UNCHANGED by this refactor per AAP §0.1.1, AAP §0.2.2, and AAP §0.7.1:

- `app/cbl/CBTRN02C.cbl` (731 lines) — primary COBOL source; PROGRAM-ID `CBTRN02C` at L23; AUTHOR `AWS` at L24
- `app/jcl/POSTTRAN.jcl` (45 lines) — JCL driver; STEP15 EXEC PGM=CBTRN02C at L23; DALYREJS DCB LRECL=430 at L36
- `app/cpy/CVTRA06Y.cpy` — DALYTRAN-RECORD layout (350 bytes; 14 fields including DALYTRAN-ID PIC X(16), DALYTRAN-TYPE-CD PIC X(02), DALYTRAN-CAT-CD PIC 9(04), DALYTRAN-SOURCE PIC X(10), DALYTRAN-DESC PIC X(100), DALYTRAN-AMT PIC S9(09)V99, DALYTRAN-MERCHANT-ID PIC 9(09), DALYTRAN-MERCHANT-NAME PIC X(50), DALYTRAN-MERCHANT-CITY PIC X(50), DALYTRAN-MERCHANT-ZIP PIC X(10), DALYTRAN-CARD-NUM PIC X(16), DALYTRAN-ORIG-TS PIC X(26), DALYTRAN-PROC-TS PIC X(26), FILLER PIC X(20))
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD layout (350 bytes; same field structure as DALYTRAN-RECORD but with TRAN- prefix; written to TRANFILE)
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD layout (50 bytes; XREF-CARD-NUM PIC X(16) key, XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11), FILLER PIC X(14))
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD layout (300 bytes; ACCT-ID PIC 9(11), ACCT-ACTIVE-STATUS PIC X(01), ACCT-CURR-BAL PIC S9(10)V99, ACCT-CREDIT-LIMIT PIC S9(10)V99, ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99, ACCT-OPEN-DATE PIC X(10), ACCT-EXPIRAION-DATE PIC X(10) note misspelled field name, ACCT-REISSUE-DATE PIC X(10), ACCT-CURR-CYC-CREDIT PIC S9(10)V99, ACCT-CURR-CYC-DEBIT PIC S9(10)V99, ACCT-ADDR-ZIP PIC X(10), ACCT-GROUP-ID PIC X(10), FILLER PIC X(178))
- `app/cpy/CVTRA01Y.cpy` — TRAN-CAT-BAL-RECORD layout (50 bytes; 17-byte composite key TRAN-CAT-KEY = TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) + TRANCAT-CD PIC 9(04); TRAN-CAT-BAL PIC S9(09)V99; FILLER PIC X(22))
- `app/data/ASCII/dailytran.txt` — DALYTRAN input fixture (REFERENCE; read-only; sequential 350-byte ASCII records)
- `app/data/ASCII/cardxref.txt` — XREFFILE input fixture (REFERENCE; read-only; 50-byte ASCII records keyed on XREF-CARD-NUM)
- `app/data/ASCII/acctdata.txt` — ACCTFILE input fixture (REFERENCE; initial state for I-O mode; NOT mutated by the test run on the source classpath copy)
- `app/data/ASCII/tcatbal.txt` — TCATBALF input fixture (REFERENCE; initial state for I-O mode; NOT mutated by the test run on the source classpath copy)

The JCL job `POSTTRAN.jcl` defines six DD names that map to the six file SELECT clauses in the COBOL program. The Java translation does NOT honor the literal DD names at runtime (DD names are a z/OS construct without a JVM equivalent), but the mapping is preserved as documentation so that operators familiar with the JCL can correlate Java file paths back to the original mainframe DDs. The mapping is:

- DALYTRAN DD -> COBOL SELECT `DALYTRAN-FILE` -> Java `DailyTransactionRepository` -> ASCII path `app/data/ASCII/dailytran.txt`
- XREFFILE DD -> COBOL SELECT `XREF-FILE` -> Java `CardXrefRepository` -> ASCII path `app/data/ASCII/cardxref.txt`
- ACCTFILE DD -> COBOL SELECT `ACCOUNT-FILE` -> Java `AccountRepository` -> ASCII path `app/data/ASCII/acctdata.txt`
- TCATBALF DD -> COBOL SELECT `TCATBAL-FILE` -> Java `TransactionCategoryBalanceRepository` -> ASCII path `app/data/ASCII/tcatbal.txt`
- TRANFILE DD -> COBOL SELECT `TRANSACT-FILE` -> Java `TransactionRepository` -> captured output `transact.txt`
- DALYREJS DD -> COBOL SELECT `DALYREJS-FILE` -> Java reject-sink port -> captured output `dalyrejs.txt`

## Cross-References

The following related files in the Java tree consume or contribute to this fixture:

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder; lists the four ASCII inputs resolved by classpath
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn02CGoldenTest.java` — JUnit 5 test class consuming this fixture; `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` until the five expected-output files are committed
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class implementing `byteForByteParity()` over the override methods documented in Phase 11
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CbTrn02C.java` — Class under test; the canonical Java translation of CBTRN02C
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranRecord.java` — TRAN-RECORD from CVTRA05Y (350 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/DalyTranRecord.java` — DALYTRAN-RECORD from CVTRA06Y (350 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java` — ACCOUNT-RECORD from CVACT01Y (300 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD from CVACT03Y (50 bytes)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TranCatBalRecord.java` — TRAN-CAT-BAL-RECORD from CVTRA01Y with 17-byte composite key (50 bytes total)
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/util/Decimals.java` — `BigDecimal` facade with `MathContext.DECIMAL128` and `RoundingMode` selection
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/PostTransactionsApp.java` — Main class wiring the use case per AAP §0.4.1 JCL-to-App mapping for POSTTRAN
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation; the canonical COBOL build-and-run path used to regenerate the five expected-output files

## Authority References

The following AAP sections govern this fixture and are cited throughout this README. Each is the authoritative reference for the corresponding mandate.

- AAP §0.1.1 — refactoring objective (byte-for-byte parity COBOL -> Java 25 LTS; no behavior changes)
- AAP §0.2.1 — golden-record fixtures are in scope under `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree is IMMUTABLE
- AAP §0.3.1 — harness directory layout `<program>/input/` plus `<program>/expected/`
- AAP §0.3.2 — records pattern, sealed-type pattern, repository ports
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` and Shenandoah generational
- AAP §0.3.6 — hexagonal architecture; no Spring container
- AAP §0.4.1 — CBTRN02C -> CbTrn02C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCE only
- AAP §0.6.1 — `Decimals` utility, BigDecimal scale 2 for monetary
- AAP §0.6.2 — sealed-type pattern for composite keys, REDEFINES, closed value sets
- AAP §0.6.4 — `java.time` only; never `java.util.Date` or `Calendar`
- AAP §0.6.5 — `java.nio.file`; EBCDIC IBM-1047 default codepage
- AAP §0.6.6 — sequential execution; `ScopedValue` replaces `ThreadLocal`
- AAP §0.6.11 — golden-record harness PR gate; CBTRN02C is THE MOST CRITICAL parity gate
- AAP §0.7.1 — Minimal Change Clause; preserve duplicate code 109 description, EXPIRAION misspelling, DALY vs DAILY inconsistency, exact DISPLAY whitespace, TWO PERIODS before `Creating`
- AAP §0.7.2 — no PAN in production logs (mask all but last 4 digits)
- AAP §0.7.4 — forbidden features (no preview JEP 502, 505, 507, 512; no `default` branches that hide cases)
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md` §1.6
- AAP §0.8.1 — citation discipline `[<path>:Lnnn]`

## DO NOT Modify the Fixture Data Without Re-Capture

The FIVE data files (`transact.txt`, `acctdata.txt`, `tcatbal.txt`, `dalyrejs.txt`, `stdout.txt`) are a COUPLED set with the read-only ASCII fixtures under `app/data/ASCII/` and the injected Clock binding. Each file is meaningful only in the context of the other four plus the deterministic inputs and the fixed Clock.

If any input changes — ASCII fixture content, Clock seed, COBOL source, or any other capture parameter — this fixture MUST be re-captured per the canonical procedure in `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits to expected outputs without a fresh re-capture WILL break byte-for-byte parity and WILL produce false negatives in the harness when the parity assertion next executes.

The following preservation directives are binding on every future commit to this folder:

- Do NOT "fix" the duplicate description for codes 101 and 109 — both literal `ACCOUNT RECORD NOT FOUND` — PRESERVED per AAP §0.7.1.
- Do NOT "fix" the `EXPIRAION` field-name misspelling (the field `ACCT-EXPIRAION-DATE` should be `EXPIRATION` but the source spells it `EXPIRAION`) — PRESERVED per AAP §0.7.1.
- Do NOT "fix" the `DALY` vs `DAILY` inconsistency between L302 (`'ERROR OPENING DALY REJECTS FILE'`) and L648 (`'ERROR CLOSING DAILY REJECTS FILE'`) — PRESERVED per AAP §0.7.1.
- Do NOT "fix" the TWO PERIODS before `Creating` at L476-L477 in the TCATBAL creation notice — PRESERVED per AAP §0.7.1.
- Do NOT "fix" the 1-vs-2 space alignment between `'TRANSACTIONS PROCESSED :'` (L227, 1 space) and `'TRANSACTIONS REJECTED  :'` (L228, 2 spaces) — PRESERVED per AAP §0.7.1.
- Do NOT mask the PAN in the captured `dalyrejs.txt` bytes — the test driver uses an unmasked sink for parity assertion, and the production logger applies masking via a separate sink path per AAP §0.7.2.
- Do NOT add a `default` clause to any pattern-matching switch over `RejectReason`, `FileStatus`, or any other sealed hierarchy — exhaustiveness is enforced by the compiler per AAP §0.7.4.
- Do NOT introduce virtual-thread fan-out across DALYTRAN records — the sequential ordering of TRANFILE and DALYREJS records is observable and parity-critical per AAP §0.6.6.
- Do NOT change the FIVE-output coupling: removing any one captured file from this folder OR introducing a sixth captured output breaks the parity contract and MUST be accompanied by a new capture-procedure entry in `java/MIGRATION_NOTES.md` §1.6.
- Do NOT change the JVM flag baseline documented in Phase 10 without first updating `java/MIGRATION_NOTES.md` and re-running the capture under the new flags — JVM flag changes can affect timestamp formatting and decimal arithmetic at the bit level.
- Do NOT replace any DISPLAY message with a "logger-friendly" equivalent (e.g., adding structured metadata, JSON-formatting, severity prefixes) — the 25 messages are byte-level contracts with downstream operators.
- Do NOT introduce a Spring `ApplicationContext`, a Jakarta CDI container, or any other dependency-injection framework — plain constructor injection at the `carddemo-app` composition root is the only permitted wiring per AAP §0.3.6.

Any agent or human who needs to alter this fixture MUST first execute the canonical capture procedure documented in `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits are not a substitute for a fresh COBOL reference run, and the parity assertion that consumes this fixture is the PR gate enforced by AAP §0.6.11.
