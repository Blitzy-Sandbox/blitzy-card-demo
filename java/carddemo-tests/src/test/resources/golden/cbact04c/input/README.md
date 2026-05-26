# golden/cbact04c/input/ — Documentation-Only Marker

This folder is intentionally documentation-only and contains NO fixture data files. CBACT04C is the interest-calculator batch program translated to Java `com.blitzy.carddemo.application.account.CbAct04C` per AAP §0.4.1; the `account` subpackage placement reflects the fact that CBACT04C opens ACCTFILE in I-O mode and REWRITEs balances at every account boundary. The COBOL source resides at `app/cbl/CBACT04C.cbl` (652 lines; `PROGRAM-ID. CBACT04C.` at `app/cbl/CBACT04C.cbl:L23`; `AUTHOR. AWS.` at `app/cbl/CBACT04C.cbl:L24`; function described at `app/cbl/CBACT04C.cbl:L5` as the interest calculator program). It is driven by JCL `app/jcl/INTCALC.jcl` (44 lines) at STEP15 `EXEC PGM=CBACT04C,PARM='2022071800'` at `app/jcl/INTCALC.jcl:L22`. The program sequentially reads TCATBALF, computes monthly interest per `WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` at `app/cbl/CBACT04C.cbl:L464-L465` with TRUNCATION (NO `ROUNDED` clause), accumulates per-account totals into WS-TOTAL-INT, REWRITEs ACCT-CURR-BAL at each account boundary via `1050-UPDATE-ACCOUNT`, and WRITES one TRAN-RECORD per non-zero rate. All four conceptual input fixtures (TCATBALF, XREFFILE, ACCTFILE, DISCGRP) are REFERENCED via classpath from `app/data/ASCII/` and are NEVER COPIED into this folder per AAP §0.4.1; the three conceptual outputs (`transact.txt`, `acctdata.txt`, `stdout.txt`) all live in the sibling `../expected/` folder.

## Why this folder is documentation-only

1. CBACT04C is a **batch-only program** invoked by JCL `app/jcl/INTCALC.jcl` (44 lines). Its four input files are bound to JCL DD statements at `app/jcl/INTCALC.jcl:L27-L36`: TCATBALF (indexed input, `app/jcl/INTCALC.jcl:L27-L28`), XREFFILE plus the XREFFIL1 ALT-KEY path (indexed input, `app/jcl/INTCALC.jcl:L29-L32`), ACCTFILE (indexed I-O, `app/jcl/INTCALC.jcl:L33-L34`), and DISCGRP (indexed input, `app/jcl/INTCALC.jcl:L35-L36`). The single sequential output TRANSACT is bound at `app/jcl/INTCALC.jcl:L37-L41` with `DCB=(RECFM=F,LRECL=350,BLKSIZE=0)`. The three outputs (`transact.txt`, `acctdata.txt`, `stdout.txt`) are documented in the sibling `../expected/README.md`.

2. The four ASCII input fixtures are sourced from `app/data/ASCII/` via classpath reference and are **NEVER COPIED** into this folder per AAP §0.4.1. Copying them would (a) double storage footprint, (b) introduce drift risk between the COBOL reference implementation and the Java parity harness, and (c) violate the AAP §0.2.2 immutability mandate for the `app/` tree.

3. The consuming Java test class `com.blitzy.carddemo.tests.golden.CbAct04CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) resolves all expected-output paths through the base-class helper `resolveExpectedOutputPath("cbact04c", fileName)` which routes to `src/test/resources/golden/cbact04c/expected/`. Input fixture paths route to `app/data/ASCII/` via classpath reference. The DATEPARM-equivalent here is the 10-byte PARM-DATE literal `'2022071800'` from `app/jcl/INTCALC.jcl:L22` — the test class supplies this directly as a method parameter without any DATEPARM file. Therefore NO fixture data file lives in this `input/` folder.

4. The harness convention from AAP §0.3.1 and §0.6.11 mandates the `<program>/input/` + `<program>/expected/` pairing per program for discoverability and parallel structure across all 28 program test folders. Removing the `input/` folder would break this symmetry; keeping it empty with only this README preserves the convention while making the absence of data files self-documenting.

5. The capture procedure for the three `../expected/*.txt` files is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until those expected fixtures are captured and committed, `CbAct04CGoldenTest` is `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11.

Cross-references in the sibling `../expected/` folder:

- `../expected/README.md` — Authoritative fixture contract documenting all 3 outputs.
- `../expected/transact.txt` — 350-byte fixed-width TRAN-RECORD entries written sequentially by `1300-B-WRITE-TX` at `app/cbl/CBACT04C.cbl:L500` (CAPTURE PLACEHOLDER).
- `../expected/acctdata.txt` — 300-byte fixed-width ACCOUNT-RECORD entries with updated ACCT-CURR-BAL and zeroed ACCT-CURR-CYC-CREDIT/DEBIT; REWRITE side effect of `1050-UPDATE-ACCOUNT` at `app/cbl/CBACT04C.cbl:L356` (CAPTURE PLACEHOLDER).
- `../expected/stdout.txt` — Captured DISPLAY output sequence including start banner at `app/cbl/CBACT04C.cbl:L181`, per-record TRAN-CAT-BAL-RECORD echo at `app/cbl/CBACT04C.cbl:L193`, optional DEFAULT-fallback DISPLAYs at `app/cbl/CBACT04C.cbl:L418-L419`, and end banner at `app/cbl/CBACT04C.cbl:L230` (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data referenced from app/data/ASCII/ and ../expected/)

CBACT04C consumes one LINKAGE-SECTION parameter (PARM-DATE from the JCL `EXEC PGM=` PARM clause) and four conceptual input files bound to JCL DD statements; it produces three conceptual outputs. The four input files are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED into this folder per AAP §0.4.1. The three outputs live in the sibling `../expected/` folder. This section documents the conceptual contract per input artifact.

### PARM-DATE (LINKAGE EXTERNAL-PARMS)

The LINKAGE SECTION layout at `app/cbl/CBACT04C.cbl:L175-L178` is:

```cobol
LINKAGE SECTION.
01  EXTERNAL-PARMS.
    05  PARM-LENGTH         PIC S9(04) COMP.
    05  PARM-DATE           PIC X(10).
```

PARM-LENGTH is the standard z/OS PARM-passing convention (2-byte binary length field) — the Java translation accepts the 10-byte PARM-DATE directly as a constructor argument and synthesizes PARM-LENGTH internally if a faithful LINKAGE replica is required for byte-parity testing. The canonical value is `'2022071800'` (10 bytes ASCII) from `app/jcl/INTCALC.jcl:L22` `EXEC PGM=CBACT04C,PARM='2022071800'`. At `app/cbl/CBACT04C.cbl:L476-L480` (STRING PARM-DATE + WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID) the 10-byte PARM-DATE is the prefix of each emitted 16-byte TRAN-ID; the 6-byte WS-TRANID-SUFFIX is the monotonically incrementing tail. The first emitted TRAN-ID is `'2022071800000001'`, the second is `'2022071800000002'`, and so on. The test class supplies PARM-DATE directly via constructor argument or method parameter — there is no DATEPARM file equivalent.

### TCATBALF — Transaction Category Balance File

| Attribute | Value |
|---|---|
| DD name | TCATBALF |
| JCL line | `app/jcl/INTCALC.jcl:L27-L28` |
| Source DSN | `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` |
| Disposition | `DISP=SHR` |
| ORG / ACCESS | INDEXED / SEQUENTIAL |
| Record key | FD-TRAN-CAT-KEY (17 bytes) = FD-TRANCAT-ACCT-ID PIC 9(11) + FD-TRANCAT-TYPE-CD PIC X(02) + FD-TRANCAT-CD PIC 9(04) |
| LRECL | 50 bytes |
| Copybook | `app/cpy/CVTRA01Y.cpy:§TRAN-CAT-BAL-RECORD` |
| FD declaration | `app/cbl/CBACT04C.cbl:L61-L67` |
| ASCII fixture (REFERENCED only) | `app/data/ASCII/tcatbal.txt` (2,550 bytes; 50 records; 50-byte LRECL + 1 LF) |

TCATBALF is the PRIMARY ITERATION FILE driving the main loop. Each iteration of `PERFORM UNTIL END-OF-FILE = 'Y'` at `app/cbl/CBACT04C.cbl:L188-L222` performs one sequential READ via `1000-TCATBALF-GET-NEXT` at `app/cbl/CBACT04C.cbl:L325-L348`. The READ INTO target is TRAN-CAT-BAL-RECORD per `app/cpy/CVTRA01Y.cpy`, which is also the source of the per-record DISPLAY echo at `app/cbl/CBACT04C.cbl:L193`.

### XREFFILE — Card Cross-Reference File

| Attribute | Value |
|---|---|
| DD name | XREFFILE (with ALT-KEY path XREFFIL1) |
| JCL line | `app/jcl/INTCALC.jcl:L29-L32` |
| Source DSN | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` + AIX path `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH` |
| Disposition | `DISP=SHR` |
| ORG / ACCESS | INDEXED / RANDOM |
| Primary key | FD-XREF-CARD-NUM (PIC X(16)) |
| Alternate key | FD-XREF-ACCT-ID (PIC 9(11)) — used for lookup at `app/cbl/CBACT04C.cbl:L394-L395` |
| LRECL | 50 bytes |
| Copybook | `app/cpy/CVACT03Y.cpy:§CARD-XREF-RECORD` |
| FD declaration | `app/cbl/CBACT04C.cbl:L69-L74` |
| ASCII fixture (REFERENCED only) | `app/data/ASCII/cardxref.txt` (1,850 bytes; 50 records; 36-byte LRECL + 1 LF) |

XREFFILE is accessed by ALT-KEY (ACCT-ID) per `app/cbl/CBACT04C.cbl:L38` `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID`. At each account boundary, `1110-GET-XREF-DATA` at `app/cbl/CBACT04C.cbl:L393-L413` performs `READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID` to retrieve the XREF-CARD-NUM that becomes TRAN-CARD-NUM in the output TRAN-RECORD at `app/cbl/CBACT04C.cbl:L495`.

### ACCTFILE — Account Master File

| Attribute | Value |
|---|---|
| DD name | ACCTFILE |
| JCL line | `app/jcl/INTCALC.jcl:L33-L34` |
| Source DSN | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` |
| Disposition | `DISP=SHR` |
| ORG / ACCESS | INDEXED / RANDOM |
| Record key | FD-ACCT-ID (PIC 9(11)) |
| LRECL | 300 bytes |
| Copybook | `app/cpy/CVACT01Y.cpy:§ACCOUNT-RECORD` |
| FD declaration | `app/cbl/CBACT04C.cbl:L84-L87` |
| ASCII fixture (REFERENCED only) | `app/data/ASCII/acctdata.txt` (15,050 bytes; 50 records; 300-byte LRECL + 1 LF) |

ACCTFILE is opened I-O at `app/cbl/CBACT04C.cbl:L291` `OPEN I-O ACCOUNT-FILE`. The test harness MUST snapshot a mutable WORKING COPY of `app/data/ASCII/acctdata.txt` into a temporary directory before each test run, because the program REWRITEs records via `1050-UPDATE-ACCOUNT` at `app/cbl/CBACT04C.cbl:L356`. The source file in `app/data/ASCII/` MUST remain UNCHANGED per AAP §0.2.2. The Java port `AccountRepository` exposes both READ-by-key and REWRITE operations to support this I-O contract.

The 12-field 300-byte ACCOUNT-RECORD layout from `app/cpy/CVACT01Y.cpy` is:

- ACCT-ID PIC 9(11) (key)
- ACCT-ACTIVE-STATUS PIC X(01)
- ACCT-CURR-BAL PIC S9(10)V99 (BigDecimal scale 2; MUTATED by `app/cbl/CBACT04C.cbl:L352`)
- ACCT-CREDIT-LIMIT PIC S9(10)V99
- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
- ACCT-OPEN-DATE PIC X(10)
- ACCT-EXPIRAION-DATE PIC X(10) (misspelled — preserved per AAP §0.7.1)
- ACCT-REISSUE-DATE PIC X(10)
- ACCT-CURR-CYC-CREDIT PIC S9(10)V99 (ZEROED by `app/cbl/CBACT04C.cbl:L353`)
- ACCT-CURR-CYC-DEBIT PIC S9(10)V99 (ZEROED by `app/cbl/CBACT04C.cbl:L354`)
- ACCT-ADDR-ZIP PIC X(10)
- ACCT-GROUP-ID PIC X(10) (used as primary DISCGRP lookup key at `app/cbl/CBACT04C.cbl:L210`)
- FILLER PIC X(178)

### DISCGRP — Disclosure Group File

| Attribute | Value |
|---|---|
| DD name | DISCGRP |
| JCL line | `app/jcl/INTCALC.jcl:L35-L36` |
| Source DSN | `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS` |
| Disposition | `DISP=SHR` |
| ORG / ACCESS | INDEXED / RANDOM |
| Record key | FD-DISCGRP-KEY (16 bytes) = FD-DIS-ACCT-GROUP-ID PIC X(10) + FD-DIS-TRAN-TYPE-CD PIC X(02) + FD-DIS-TRAN-CAT-CD PIC 9(04) |
| LRECL | 50 bytes |
| Copybook | `app/cpy/CVTRA02Y.cpy:§DIS-GROUP-RECORD` |
| FD declaration | `app/cbl/CBACT04C.cbl:L76-L82` |
| ASCII fixture (REFERENCED only) | `app/data/ASCII/discgrp.txt` (2,601 bytes; 51 records; 3 groups × 17 each: `A000000000`, `DEFAULT   `, `ZEROAPR   `) |

DISCGRP is accessed RANDOM by the 16-byte composite key built from ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD at `app/cbl/CBACT04C.cbl:L210-L212`. The DIS-INT-RATE field (PIC S9(04)V99 from `app/cpy/CVTRA02Y.cpy`) is the interest-rate operand in the L464 COMPUTE. Status `'23'` (NOTFND) triggers the DEFAULT-group fallback: `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` at `app/cbl/CBACT04C.cbl:L437` then PERFORM `1200-A-GET-DEFAULT-INT-RATE` at `app/cbl/CBACT04C.cbl:L438` which re-reads with the overridden key. The 17 DEFAULT-group rows in the fixture (one per TRANCAT-TYPE-CD × TRANCAT-CD combination encountered) ensure the fallback succeeds; if the DEFAULT lookup itself fails, `1200-A-GET-DEFAULT-INT-RATE` at `app/cbl/CBACT04C.cbl:L443-L460` emits `'ERROR READING DEFAULT DISCLOSURE GROUP'` and ABENDs with CALL `'CEE3ABD'` (fatal — no recursive fallback).

## Cross-reference to the Java test class

The Java FQCN under test is `com.blitzy.carddemo.application.account.CbAct04C`, placed in the `account` subpackage per AAP §0.4.1 because CBACT04C reads ACCTDATA in I-O mode and REWRITEs balances. The Java test class is `com.blitzy.carddemo.tests.golden.CbAct04CGoldenTest`, which extends the base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test source file lives at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbAct04CGoldenTest.java`.

The five constructor-injected dependencies per AAP §0.3.2 hexagonal architecture are:

- `TransactionCategoryBalanceRepository` — indexed sequential reader bound to `app/data/ASCII/tcatbal.txt`
- `CardXrefRepository` — indexed ALT-KEY reader bound to `app/data/ASCII/cardxref.txt`
- `AccountRepository` — indexed I-O port bound to a mutable copy of `app/data/ASCII/acctdata.txt` in a temp directory (READ and REWRITE)
- `DiscountGroupRepository` — indexed random-access reader bound to `app/data/ASCII/discgrp.txt`
- `TransactionRepository` — sequential writer producing `transact.txt`

Base-class override summary:

- `programClass()` returns `com.blitzy.carddemo.application.account.CbAct04C.class`
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cbact04c", "stdout.txt")` — routes to `../expected/stdout.txt`
- `expectedOutputs()` returns ExpectedOutput entries for `transact.txt`, `acctdata.txt`, and `stdout.txt` (all under `../expected/`)
- Input fixture paths are obtained via `resolveAppFixturePath("tcatbal.txt")`, `resolveAppFixturePath("cardxref.txt")`, `resolveAppFixturePath("acctdata.txt")`, and `resolveAppFixturePath("discgrp.txt")` which route to `app/data/ASCII/`
- PARM-DATE is supplied directly as a constructor argument with the canonical value `"2022071800"` from `app/jcl/INTCALC.jcl:L22`

All input fixtures route to `app/data/ASCII/`; all expected fixtures route to `../expected/`. **None route to this `input/` folder.** This is the architectural reason the folder is empty. The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the three `../expected/*.txt` files are captured and committed.

A deterministic `Clock` MUST be injected via `ScopedValue<Clock>` per AAP §0.6.6 so that `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBACT04C.cbl:L613-L626` produces a fixed `YYYY-MM-DD-HH.MM.SS.MM0000` value for byte-identical TRAN-ORIG-TS and TRAN-PROC-TS in `../expected/transact.txt`. Without a fixed clock, the captured fixture would drift on every run. `ThreadLocal` is FORBIDDEN per AAP §0.6.6; `ScopedValue` is the only permitted mechanism.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBACT04C) for the COBOL build/run path used to capture all three `../expected/*.txt` files. Per AAP §0.7.5 the procedure is documented in `MIGRATION_NOTES.md` rather than inline in this README to keep the per-program documentation focused on the conceptual contract.

The capture procedure MUST run the COBOL CBACT04C program against the same four `app/data/ASCII/*.txt` fixtures that the Java test will reference. The capture environment MUST inject a fixed clock (matching the Java `ScopedValue<Clock>` value) so the TRAN-ORIG-TS and TRAN-PROC-TS columns produced by `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBACT04C.cbl:L613-L626` are deterministic and reproducible. The capture MUST also pass `PARM='2022071800'` to match `app/jcl/INTCALC.jcl:L22` exactly. The input fixtures themselves are stable references — only the EXPECTED outputs need re-capture when the COBOL baseline changes. If any of the four ASCII fixtures change OR the Clock seed changes OR the COBOL source changes OR the PARM-DATE changes, the three `../expected/*.txt` files MUST be re-captured per the procedure in `MIGRATION_NOTES.md` §1.6. Until the capture is performed and committed, the three `../expected/*.txt` files hold placeholder content and `CbAct04CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **Byte-for-byte parity is the contract** (AAP §0.1.1, §0.6.11): every byte of every output file produced by Java MUST equal the corresponding byte of the captured COBOL output (modulo the volatile-timestamp masking strategy for `transact.txt`).

- **Truncation arithmetic** (AAP §0.6.1): the COMPUTE at `app/cbl/CBACT04C.cbl:L464-L465` `WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` has NO `ROUNDED` clause; therefore `Decimals` MUST use `RoundingMode.DOWN` (truncation toward zero). Verify-by-example: `(1234.56 * 0.0635) / 1200 = 0.06` (NOT 0.07 banker's rounding). Banker's `RoundingMode.HALF_EVEN` is FORBIDDEN for this specific formula.

- **Sequential execution is mandated** (AAP §0.6.6): virtual-thread fan-out is FORBIDDEN inside CBACT04C because per-record work mutates SHARED state — WS-TOTAL-INT, WS-LAST-ACCT-NUM, WS-FIRST-TIME, and WS-TRANID-SUFFIX. Reordering would change account boundary detection, change emitted TRAN-ID values, and change the REWRITE order on ACCTFILE. The Java translation MUST execute the main loop serially in the same TCATBALF sequential-read order.

- **I-O mode on ACCTFILE** at `app/cbl/CBACT04C.cbl:L291` `OPEN I-O ACCOUNT-FILE`: the file is read AND rewritten in place. The Java `AccountRepository` port MUST expose both READ-by-key and REWRITE operations. The test harness snapshots a mutable copy of `acctdata.txt` to a temp directory before each test run; the source `app/data/ASCII/acctdata.txt` remains UNCHANGED per AAP §0.2.2.

- **Account-boundary REWRITE** at `app/cbl/CBACT04C.cbl:L194` `IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM`: on account change, `1050-UPDATE-ACCOUNT` (`app/cbl/CBACT04C.cbl:L350-L370`) REWRITEs the PREVIOUS account with `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` (`app/cbl/CBACT04C.cbl:L352`), `MOVE 0 TO ACCT-CURR-CYC-CREDIT` (`app/cbl/CBACT04C.cbl:L353`), and `MOVE 0 TO ACCT-CURR-CYC-DEBIT` (`app/cbl/CBACT04C.cbl:L354`). All other 9 ACCOUNT-RECORD fields plus the 178-byte FILLER are preserved unchanged.

- **EOF boundary final flush**: the outer IF/ELSE at `app/cbl/CBACT04C.cbl:L188-L221` ensures that when END-OF-FILE='Y' is set, the LAST account is still REWRITTEN via `ELSE PERFORM 1050-UPDATE-ACCOUNT` at `app/cbl/CBACT04C.cbl:L219-L220`. The Java translation MUST implement an explicit post-loop flush because Java `while` loops do not have a direct equivalent of COBOL's PERFORM UNTIL ELSE semantics.

- **First-time skip**: WS-FIRST-TIME='Y' (initialized at `app/cbl/CBACT04C.cbl:L170`) prevents REWRITE on the very first record (`app/cbl/CBACT04C.cbl:L195-L198`). The first detected account boundary sets WS-FIRST-TIME='N' instead of performing the REWRITE because there is no previous account to flush.

- **DEFAULT-group fallback** at `app/cbl/CBACT04C.cbl:L436-L439`: when `DISCGRP-STATUS = '23'` (NOTFND), `1200-A-GET-DEFAULT-INT-RATE` re-reads with `FD-DIS-ACCT-GROUP-ID = 'DEFAULT'` (literal 7 chars + 3 trailing spaces to fill PIC X(10)). DEFAULT-lookup failure is FATAL (no recursive fallback; ABENDs with `'ERROR READING DEFAULT DISCLOSURE GROUP'` at `app/cbl/CBACT04C.cbl:L455`).

- **Transaction ID composition**: TRAN-ID = PARM-DATE(10) + WS-TRANID-SUFFIX(6) via STRING at `app/cbl/CBACT04C.cbl:L476-L480` — 16 bytes total matching the CVTRA05Y TRAN-ID layout. WS-TRANID-SUFFIX is a GLOBAL counter declared at `app/cbl/CBACT04C.cbl:L173` with `VALUE 0`, incremented at `app/cbl/CBACT04C.cbl:L474` BEFORE the STRING; it is NOT reset per account. First emitted TRAN-ID = `'2022071800000001'`, second = `'2022071800000002'`, and so on.

- **Transaction-type defaults**: `MOVE '01' TO TRAN-TYPE-CD` at `app/cbl/CBACT04C.cbl:L482`, `MOVE '05' TO TRAN-CAT-CD` at `app/cbl/CBACT04C.cbl:L483`, `MOVE 'System' TO TRAN-SOURCE` at `app/cbl/CBACT04C.cbl:L484`. The literal `'05'` MOVEd to PIC 9(04) becomes `'0005'` (COBOL right-justifies and zero-pads); `'System'` MOVEd to PIC X(10) becomes `'System    '` (left-justified, space-padded).

- **Transaction description**: STRING `'Int. for a/c '` (13 chars) + ACCT-ID (PIC 9(11), 11 chars) into TRAN-DESC at `app/cbl/CBACT04C.cbl:L485-L489` — 24 chars total filled into the 100-byte field.

- **Merchant zero-fill**: `MOVE 0 TO TRAN-MERCHANT-ID` at `app/cbl/CBACT04C.cbl:L491` produces `'000000000'` (9 zero bytes); `MOVE SPACES TO TRAN-MERCHANT-NAME/CITY/ZIP` at `app/cbl/CBACT04C.cbl:L492-L494` produces all-space byte ranges.

- **TRAN-CARD-NUM source**: `MOVE XREF-CARD-NUM TO TRAN-CARD-NUM` at `app/cbl/CBACT04C.cbl:L495` copies the 16-byte XREF-CARD-NUM loaded at `app/cbl/CBACT04C.cbl:L205` from the prior `1110-GET-XREF-DATA` ALT-KEY lookup.

- **DB2 timestamp 26-byte format** at `app/cbl/CBACT04C.cbl:L150-L165` and `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBACT04C.cbl:L613-L626`: `YYYY-MM-DD-HH.MM.SS.MM0000` byte layout — 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26 bytes. The Java translation MUST format `LocalDateTime` to exactly this 26-byte pattern via the deterministic `Clock` from `ScopedValue<Clock>` per AAP §0.6.6.

- **Per-record DISPLAY**: TRAN-CAT-BAL-RECORD is echoed to stdout at `app/cbl/CBACT04C.cbl:L193` — emits the entire 50-byte record as a concatenated text image; one line per TCATBAL READ. For the canonical 50-record `tcatbal.txt` fixture, the stdout contains exactly 50 such lines plus start banner + end banner + any DEFAULT-fallback DISPLAYs.

- **`BigDecimal` arithmetic only** (AAP §0.6.1, §0.7.4): every monetary value in CBACT04C is PIC S9(09)V99 or PIC S9(10)V99 (WS-MONTHLY-INT, WS-TOTAL-INT, TRAN-CAT-BAL, DIS-INT-RATE, TRAN-AMT, ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT). The Java translation MUST use `java.math.BigDecimal` with explicit `MathContext.DECIMAL128` and `RoundingMode.DOWN` via the `Decimals` utility per AAP §0.6.1. Trailing zeros MUST be preserved via `setScale(2, RoundingMode.DOWN)`.

- **`java.time` only for dates and timestamps** (AAP §0.6.4): ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE (PIC X(10), `YYYY-MM-DD`) -> `LocalDate`; TRAN-ORIG-TS and TRAN-PROC-TS (PIC X(26), `YYYY-MM-DD-HH.MM.SS.MM0000`) -> `LocalDateTime` with a deterministic `Clock`. No `java.util.Date`, `java.util.Calendar`, or `java.text.SimpleDateFormat` in the Java translation.

- **`java.nio.file` only for file I/O** (AAP §0.6.5): no `java.io.File`. The four input fixtures and three output fixtures are accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `Files.write`.

- **EBCDIC default with per-file codepage override** (AAP §0.6.5): the default codepage is `Charset.forName("IBM-1047")`; the four ASCII fixtures in `app/data/ASCII/*.txt` are pre-transcoded for test convenience but per-file codepage overrides MUST be configurable via `application.properties`.

- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch run context (run ID, processing date, tenant, deterministic Clock, PARM-DATE) flows through `ScopedValue.where(...).run(...)`. No `ThreadLocal` in new code.

- **`@CobolProgram("CBACT04C")` annotation required** (AAP §0.7.1): the Java class `CbAct04C` MUST carry the `@CobolProgram` Javadoc-style annotation citing the original PROGRAM-ID `CBACT04C`, source path `app/cbl/CBACT04C.cbl`, and translation date.

- **Five idiosyncrasies preserved verbatim** (AAP §0.7.1):
  - `'ERROR OPENING DALY REJECTS FILE'` misnomer at `app/cbl/CBACT04C.cbl:L281` — preserve verbatim (should be 'DISCLOSURE GROUP FILE')
  - Empty `1400-COMPUTE-FEES` at `app/cbl/CBACT04C.cbl:L518-L520` — preserve as no-op
  - Missing `Z-` numeric prefix on `Z-GET-DB2-FORMAT-TIMESTAMP` at `app/cbl/CBACT04C.cbl:L613` — preserve via `@CobolParagraph` annotation
  - `ACCT-EXPIRAION-DATE` field-name misspelling in `app/cpy/CVACT01Y.cpy` — preserve in Java record field name
  - Identical `'ACCOUNT NOT FOUND: '` literal at `app/cbl/CBACT04C.cbl:L375` and `app/cbl/CBACT04C.cbl:L397` — preserve as byte-identical strings

- **NO Unicode ellipsis** anywhere — only 3 ASCII periods `...` per AAP §0.7.4 forbidden-content guidance.

- **No card PAN logged in full** (AAP §0.7.2): production logging MUST mask all but the last 4 digits of XREF-CARD-NUM and TRAN-CARD-NUM. Test fixtures, being captured records, preserve the COBOL representation verbatim — production logging masks; fixtures do not.

- **Pattern-matching switch exhaustiveness** (AAP §0.7.4): the DISCGRP fallback dispatch and any account-status dispatch translate to Java pattern-matching switches with sealed-type permits; NO `default` branch is permitted.

## Source lineage

All source files below are REFERENCE-only and UNCHANGED per AAP §0.1.1 and §0.2.2:

- `app/cbl/CBACT04C.cbl` (652 lines) — Interest calculator batch program. PROGRAM-ID `CBACT04C` at L23, AUTHOR `AWS` at L24. FILE-CONTROL SELECT clauses at L27-L56 (5 files). FD declarations at L61-L92. PROCEDURE DIVISION at L180. Main loop at L188-L222. Sixteen paragraphs enumerated in this README.
- `app/jcl/INTCALC.jcl` (44 lines) — JCL driver. Job at L1-L2. STEP15 `EXEC PGM=CBACT04C,PARM='2022071800'` at L22. DD bindings at L23-L41. TRANSACT output GDG `AWS.M2.CARDDEMO.SYSTRAN(+1)` at L37-L41 with `DCB=(RECFM=F,LRECL=350,BLKSIZE=0)`.
- `app/cpy/CVTRA01Y.cpy` — TRAN-CAT-BAL-RECORD 50-byte layout; 17-byte composite key + S9(09)V99 balance + 22-byte FILLER; COPY at `app/cbl/CBACT04C.cbl:L97`.
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD 50-byte layout; X(16) card + 9(09) cust + 9(11) acct + 14-byte FILLER; COPY at `app/cbl/CBACT04C.cbl:L102`.
- `app/cpy/CVTRA02Y.cpy` — DIS-GROUP-RECORD 50-byte layout; 16-byte composite key + S9(04)V99 rate + 28-byte FILLER; COPY at `app/cbl/CBACT04C.cbl:L107`.
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD 300-byte layout; 12 fields + 178-byte FILLER; COPY at `app/cbl/CBACT04C.cbl:L112`; note misspelled `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy:L11` preserved per AAP §0.7.1.
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD 350-byte layout (output); 14 fields; COPY at `app/cbl/CBACT04C.cbl:L117`.
- `app/data/ASCII/tcatbal.txt` (2,550 bytes) — TCATBALF sequential transaction-category-balance fixture; REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/cardxref.txt` (1,850 bytes) — XREFFILE indexed cross-reference fixture (ALT-KEY by ACCT-ID); REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/acctdata.txt` (15,050 bytes) — ACCTFILE indexed account-master fixture; INITIAL state for I-O mode; REFERENCE-only per AAP §0.4.1.
- `app/data/ASCII/discgrp.txt` (2,601 bytes; 51 records; 3 groups × 17 each: `A000000000`, `DEFAULT   `, `ZEROAPR   `) — DISCGRP indexed disclosure-group fixture; the 17 DEFAULT-group rows support the `app/cbl/CBACT04C.cbl:L437` fallback; REFERENCE-only per AAP §0.4.1.

## Authority references

- AAP §0.1.1 (refactoring objective — preserve byte-for-byte fidelity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: `golden/cbact04c/` directory tree under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference implementation)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (hexagonal architecture; constructor-injected ports)
- AAP §0.4.1 (transformation plan — CBACT04C -> CbAct04C in `com.blitzy.carddemo.application.account`; ASCII fixtures REFERENCED via classpath and NEVER COPIED)
- AAP §0.6.1 (decimal arithmetic fidelity — `BigDecimal`, `MathContext.DECIMAL128`; CBACT04C uses TRUNCATION via `RoundingMode.DOWN` because no `ROUNDED` clause at L464; scale preservation)
- AAP §0.6.2 (sealed-type pattern for composite keys — 17-byte TCATBAL key, 16-byte DISCGRP key)
- AAP §0.6.4 (date semantics — `java.time` only; never legacy date APIs)
- AAP §0.6.5 (file I/O exactness — `java.nio.file` only; EBCDIC IBM-1047 default; per-file codepage configurable)
- AAP §0.6.6 (batch throughput — sequential execution preserved for CBACT04C because per-record work mutates shared state; `ScopedValue<Clock>` for deterministic timestamps; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until COBOL captures committed; structured-record diff for timestamp masking)
- AAP §0.7.1 (Minimal Change Clause; preserve five idiosyncrasies — `DALY REJECTS` misnomer at L281, empty 1400-COMPUTE-FEES at L518-L520, missing `Z-` prefix at L613, `ACCT-EXPIRAION-DATE` misspelling in CVACT01Y, identical `'ACCOUNT NOT FOUND: '` at L375/L397)
- AAP §0.7.2 (no card PAN in production logs; test fixtures preserve COBOL behavior verbatim)
- AAP §0.7.4 (forbidden features — no preview JEPs, no `default` branches)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:<locator>]`)
- Sibling pattern reference: `golden/cbtrn02c/input/README.md` and `golden/cbtrn03c/input/README.md` (same H2 + H3 documentation-only marker pattern adapted for batch-program semantics; CBACT04C has THREE outputs vs CBTRN02C's FIVE and CBTRN03C's TWO; FOUR input ports vs CBTRN02C's FOUR and CBTRN03C's FIVE; ZERO reject codes vs CBTRN02C's FIVE; INTEREST-CALCULATION focus vs CBTRN02C's POSTING focus vs CBTRN03C's REPORTING focus).

## DO NOT add files here

This folder MUST contain ONLY the single `README.md` file. Do NOT add:

- Fixture data files (`.bin`, `.txt` copies of `app/data/ASCII/*` files, or any captured input snapshots)
- `.gitkeep` placeholders
- Subfolders
- Captured input snapshots
- A literal DATEPARM file (CBACT04C uses LINKAGE PARM-DATE, not a DATEPARM DD — the PARM-DATE is supplied directly as a constructor argument)
- Embedded COBOL source
- Database, Spring, or Liquibase configuration files
- AWS service references (S3 manifests, SQS queue names, SNS topic names, Kinesis stream names, SES templates)
- Any of the forbidden constructs from AAP §0.7.4 (preview features, default switch branches, etc.)

All four conceptual input fixtures (TCATBALF, XREFFILE, ACCTFILE, DISCGRP) are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED per AAP §0.4.1. The PARM-DATE input is supplied directly as a constructor/method argument. All three expected output fixtures (`transact.txt`, `acctdata.txt`, `stdout.txt`) live in the sibling `../expected/` folder. Adding files to this `input/` folder would create duplicate, stale, or unreachable fixtures and would break the byte-for-byte parity guarantee that is the entire point of the golden-record harness.

If a future test scenario requires additional inputs, source them from `app/data/ASCII/` and extend the appropriate `resolveAppFixturePath(...)` call in `CbAct04CGoldenTest.java` — not here.
