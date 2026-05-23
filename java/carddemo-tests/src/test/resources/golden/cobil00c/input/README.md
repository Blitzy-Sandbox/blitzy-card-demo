# COBIL00C (Bill Payment, CB00) -- Golden-Record `input/` Folder (Documentation-Only)

This folder is documentation-only; it contains exactly ONE file, this README, and no fixture data of any kind. COBIL00C is the Bill Payment online CICS program (transaction `CB00`, source `app/cbl/COBIL00C.cbl`) translated to Java `com.blitzy.carddemo.application.billpay.CoBil00C`. The deterministic test scenario file (`input_scenario.txt`) and ALL auxiliary fixture artifacts live in the sibling `../expected/` folder, because the consuming Java test class `com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest` resolves every fixture path through the base class helper `resolveExpectedOutputPath("cobil00c", fileName)` -- which mechanically routes every path to `src/test/resources/golden/cobil00c/expected/`. COBIL00C is uniquely characterized among CardDemo online programs by performing TWO file mutations atomically within the same transaction boundary: `EXEC CICS WRITE TRANSACT` (appending a 350-byte TRAN-RECORD) AND `EXEC CICS REWRITE ACCTDAT` (replacing a 300-byte ACCOUNT-RECORD in place). This `input/` directory is preserved only to maintain the per-program `<program>/input/` + `<program>/expected/` symmetry mandated by AAP Sec 0.3.1 while making the absence of fixture data files explicit and self-documenting.

## Why this folder is documentation-only

COBIL00C is an online CICS pseudo-conversational program (transaction `CB00`, source `app/cbl/COBIL00C.cbl`, 572 lines). Its user-facing input arrives via the BMS map `COBIL0A` defined in `app/bms/COBIL00.bms` (141 lines) with only 2 user-input fields (ACTIDIN, CONFIRM), 1 display-only output field (CURBAL), and 1 error message field (ERRMSG). The map is the narrowest among CardDemo online programs except COSGN00C and CSUTLDTC.

The source COBOL working-storage at `app/cbl/COBIL00C.cbl:L37-L42` declares the program identifiers and external file names consumed by the four `EXEC CICS` file commands:

```cobol
WS-PGMNAME       PIC X(08) VALUE 'COBIL00C'.
WS-TRANID        PIC X(04) VALUE 'CB00'.
WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'.
WS-ACCTDAT-FILE  PIC X(08) VALUE 'ACCTDAT '.
WS-CXACAIX-FILE  PIC X(08) VALUE 'CXACAIX '.
```

Note: the trailing space in `'ACCTDAT '` and `'CXACAIX '` pads each value to PIC X(08); preserving these padding spaces is byte-significant when the literal is passed as the `DATASET` operand of `EXEC CICS READ/REWRITE/STARTBR/READPREV/ENDBR/WRITE` calls.

Input is NOT solely file-based fixtures from `app/data/ASCII/*.txt`. The ASCII fixtures `acctdata.txt` and `cardxref.txt` are referenced as INITIAL STATE for the file-based adapter but are NEVER copied into this directory per AAP Sec 0.4.1; they are loaded by the Java adapter via classpath relative path. The scenario-driving BMS keystroke sequence is test-owned scaffolding authored in `../expected/input_scenario.txt`. Keeping an empty `input/` folder with only this README preserves the established `<program>/input/` + `<program>/expected/` symmetry from AAP Sec 0.3.1 across all CardDemo program test fixtures, while making the absence of data files self-documenting.

The sibling `../expected/` folder contains:

- `../expected/README.md` -- 13-phase authoritative fixture contract for the COBIL00C golden-record test
- `../expected/input_scenario.txt` -- Synthesized 14-scenario CICS pseudo-conversation driver script (TEST-OWNED)
- `../expected/transact.txt` -- Captured post-WRITE TRANSACT state (CAPTURE PLACEHOLDER; +350 bytes on success)
- `../expected/acctdata.txt` -- Captured post-REWRITE ACCTDAT state (CAPTURE PLACEHOLDER; in-place, byte length unchanged)
- `../expected/stdout.txt` -- Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER; 6 DISPLAY statements in error paths)
- `../expected/bms_output.txt` -- Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER)

## Conceptual input contract (documented; data lives in `../expected/`)

### BMS map COBIL0A fields

The COBIL0A map is unusually narrow with only 4 active user-or-output fields (2 unprotected input, 1 display-only output, 1 dynamic error message) plus the standard header and footer fields populated by the program:

```text
ACTIDIN  (6,21)  len=11  ATTRB=(FSET,IC,NORM,UNPROT)  GREEN UNDERLINE   <- initial cursor; Account ID
CURBAL   (11,32) len=14  ATTRB=(ASKIP,FSET,NORM)      BLUE              <- display-only; balance after lookup
CONFIRM  (15,60) len=1   ATTRB=(FSET,NORM,UNPROT)     GREEN UNDERLINE   <- Y/N confirmation
ERRMSG   (23,1)  len=78  ATTRB=(ASKIP,BRT,FSET)       RED (default)     <- dynamic color: DFHGREEN on success
```

In addition to these 4 active fields, the program populates the standard CardDemo header and footer: TRNNAME at (1,7) = `'CB00'`, PGMNAME at (2,7) = `'COBIL00C'`, TITLE01 / TITLE02 at row 1-2 col 21, CURDATE at (1,71), CURTIME at (2,71), and the footer at (24,1) = exactly `'ENTER=Continue  F3=Back  F4=Clear'` (33 chars with TWO spaces between each pair) per `app/bms/COBIL00.bms:L131-L135`. The footer accurately reflects the THREE handled named AID keys; there is NO PF12, NO PF5, NO PF7/PF8.

### EIBAID dispatch (3 named keys + WHEN OTHER)

The `EVALUATE EIBAID` at `app/cbl/COBIL00C.cbl:L125-L142` dispatches on exactly three named AID keys plus the catch-all `WHEN OTHER` branch:

```text
DFHENTER   -> PERFORM PROCESS-ENTER-KEY           [L126-L127]
DFHPF3     -> PERFORM RETURN-TO-PREV-SCREEN       [L128-L135]
DFHPF4     -> PERFORM CLEAR-CURRENT-SCREEN        [L136-L137]
WHEN OTHER -> MOVE CCDA-MSG-INVALID-KEY           [L138-L141]
```

COBIL00C handles EXACTLY 3 named AID keys plus WHEN OTHER. PF3 RETURN-TO-PREV-SCREEN at L130 defaults to `'COMEN01C'` when CDEMO-FROM-PROGRAM is blank; or to `'COSGN00C'` when called from MAIN-PARA via the EIBCALEN=0 path at L107-L109.

### Two-pass conversation + multi-file atomic mutation

The pseudo-conversational flow proceeds in two passes. Pass 1: ENTER with ACTIDIN populated -> PROCESS-ENTER-KEY (L154-L244) -> READ-ACCTDAT-FILE with UPDATE intent (L343-L372) -> populate CURBAL (display-only balance) -> if CONFIRMI is blank, SEND-BILLPAY-SCREEN with `'Confirm to make a bill payment...'`. Pass 2 happy path: ENTER with CONFIRMI=`'Y'` -> CONF-PAY-YES branch at L210 -> READ-CXACAIX-FILE for XREF-CARD-NUM (L408-L436) -> STARTBR/READPREV/ENDBR on TRANSACT to find max TRAN-ID (L213-L215) -> increment to next TRAN-ID -> INITIALIZE TRAN-RECORD with fixed bill-payment metadata (L218-L232) -> WRITE-TRANSACT-FILE (L233) -> COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (L234) -> UPDATE-ACCTDAT-FILE REWRITE (L235) -> SEND success message in DFHGREEN.

The multi-file atomic mutation invariant: a successful execution performs BOTH `EXEC CICS WRITE TRANSACT` (+350 bytes appended) AND `EXEC CICS REWRITE ACCTDAT` (in-place; byte length unchanged) within the same transaction boundary. COBIL00C is unique among CardDemo online programs in this respect; sibling programs perform at most one file mutation per successful invocation (COCRDUPC REWRITEs CARDDAT only; COTRN02C WRITEs TRANSACT only).

### Auto-trigger from CDEMO-CB00-TRN-SELECTED

COBIL00C supports a commarea-driven auto-trigger that allows a calling program to drive the bill-payment workflow without keyboard input. The relevant code at `app/cbl/COBIL00C.cbl:L116-L121` reads:

```cobol
IF CDEMO-CB00-TRN-SELECTED NOT =
                            SPACES AND LOW-VALUES
    MOVE CDEMO-CB00-TRN-SELECTED TO
         ACTIDINI OF COBIL0AI
    PERFORM PROCESS-ENTER-KEY
END-IF
```

A calling program (e.g., COCRDLIC card list) can populate `CDEMO-CB00-TRN-SELECTED PIC X(16)` in the commarea before XCTL to COBIL00C. The first-pass logic detects this populated field, MOVEs it to `ACTIDINI`, and immediately PERFORMs `PROCESS-ENTER-KEY` before the normal `SEND-BILLPAY-SCREEN` -- effectively driving a partial workflow without keyboard input. The `CDEMO-CB00-INFO` sub-record is defined at `app/cpy/COCOM01Y.cpy` (within `CARDDEMO-COMMAREA`) and extended at `app/cbl/COBIL00C.cbl:L64-L72`.

### Verbatim COBOL messages (preserve byte-for-byte)

The following 14 distinct verbatim strings (13 from COBIL00C plus 1 from CSMSG01Y; 17 source-line occurrences total because three messages reuse identical text across multiple sites) MUST be preserved exactly by the Java translation per AAP Sec 0.7.1 (Minimal Change Clause). Preserve the trailing 3-ASCII-period ellipses (NEVER Unicode); preserve the DOUBLE-SPACE in the success message; preserve all trailing spaces in CCDA-MSG-INVALID-KEY (50 chars total).

```text
PROCESS-ENTER-KEY validation:
  'Acct ID can NOT be empty...'                  [app/cbl/COBIL00C.cbl:L161]
  'Invalid value. Valid values are (Y/N)...'     [app/cbl/COBIL00C.cbl:L187]
  'You have nothing to pay...'                   [app/cbl/COBIL00C.cbl:L201]
  'Confirm to make a bill payment...'            [app/cbl/COBIL00C.cbl:L237]

READ-ACCTDAT-FILE (L343-L372):
  'Account ID NOT found...'                      [app/cbl/COBIL00C.cbl:L361]
  'Unable to lookup Account...'                  [app/cbl/COBIL00C.cbl:L368]

UPDATE-ACCTDAT-FILE REWRITE (L377-L403):
  'Account ID NOT found...'                      [app/cbl/COBIL00C.cbl:L392]
  'Unable to Update Account...'                  [app/cbl/COBIL00C.cbl:L399]

READ-CXACAIX-FILE (L408-L436):
  'Account ID NOT found...'                      [app/cbl/COBIL00C.cbl:L425]
  'Unable to lookup XREF AIX file...'            [app/cbl/COBIL00C.cbl:L432]

STARTBR-TRANSACT-FILE (L441-L467):
  'Transaction ID NOT found...'                  [app/cbl/COBIL00C.cbl:L456]
  'Unable to lookup Transaction...'              [app/cbl/COBIL00C.cbl:L463]

READPREV-TRANSACT-FILE (L472-L496):
  'Unable to lookup Transaction...'              [app/cbl/COBIL00C.cbl:L492]

WRITE-TRANSACT-FILE (L510-L547):
  'Tran ID already exist...'                     [app/cbl/COBIL00C.cbl:L536]
  'Unable to Add Bill pay Transaction...'        [app/cbl/COBIL00C.cbl:L543]
  'Payment successful.  Your Transaction ID is <id>.'  [app/cbl/COBIL00C.cbl:L527-L531]

Invalid-AID-key constant (PIC X(50); text + trailing spaces padded to 50 chars):
  'Invalid key pressed. Please see below...         '   [app/cpy/CSMSG01Y.cpy:L20-L21]
```

The success message DOUBLE-SPACE arises from STRING composition at L527-L531: `'Payment successful. '` (trailing 1 space; 20 chars) + `' Your Transaction ID is '` (leading 1 space; 24 chars) = `'Payment successful.  Your Transaction ID is '` with TWO spaces between `successful.` and `Your`. This is byte-significant per AAP Sec 0.7.1. The success message is rendered in DFHGREEN per L526; all error messages default to DFHRED.

The CCDA-MSG-INVALID-KEY constant is referenced via `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE` at `app/cbl/COBIL00C.cbl:L140` (WHEN OTHER branch of EIBAID dispatch). The constant is exactly 50 chars: text content `'Invalid key pressed. Please see below...'` padded with trailing spaces to PIC X(50). All 50 chars MUST be preserved when MOVEd to WS-MESSAGE (PIC X(80)).

Message `'Account ID NOT found...'` appears at THREE sites (L361 READ-ACCTDAT NOTFND; L392 UPDATE-ACCTDAT NOTFND; L425 READ-CXACAIX NOTFND) with identical verbatim text. Message `'Unable to lookup Transaction...'` appears at TWO sites (L463 STARTBR OTHER; L492 READPREV OTHER) with identical verbatim text. Total distinct strings: 14 (13 from COBIL00C + 1 from CSMSG01Y); total source-line occurrences: 17.

### Monetary handling (BigDecimal scale=2)

ACCT-CURR-BAL is PIC S9(10)V99 per `app/cpy/CVACT01Y.cpy`; TRAN-AMT is PIC S9(09)V99 per `app/cpy/CVTRA05Y.cpy`. Three monetary-handling sites in COBIL00C are byte-significant:

- L194: `MOVE WS-CURR-BAL TO CURBALI OF COBIL0AI` formats the balance for BMS display via edit pattern `PIC +9999999999.99`.
- L224: `MOVE ACCT-CURR-BAL TO TRAN-AMT` sets the full balance as the transaction amount (no partial payments are supported).
- L234: `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` substituting L224's assignment yields `ACCT-CURR-BAL_after = 0.00`.

The full-balance payment invariant: `ACCT-CURR-BAL_after = 0.00` always for successful payments. BigDecimal scale=2 preserved per AAP Sec 0.6.1 -- `0.00` MUST NOT normalize to `0` or `0.0`. The Java translation uses `Decimals.subtract(...)` with `MathContext.DECIMAL128` per the central facade.

### Test scenarios (14 binding)

The complete 14-scenario binding list is encoded in `../expected/input_scenario.txt`:

```text
 1. EIBCALEN_ZERO_XCTL_TO_COSGN00C        -- first-time entry; XCTL to COSGN00C (L107-L109)
 2. INITIAL_DISPLAY_FIRST_PASS            -- CDEMO-PGM-REENTER not set; blank map; cursor on ACTIDIN
 3. PF3_BACK_XCTL_TO_FROM_PROGRAM         -- PF3 -> RETURN-TO-PREV-SCREEN -> XCTL CDEMO-FROM-PROGRAM
 4. PF4_CLEAR_CURRENT_SCREEN              -- PF4 -> INITIALIZE-ALL-FIELDS (L560-L566)
 5. INVALID_AID_PF12                      -- WHEN OTHER -> CCDA-MSG-INVALID-KEY (50 chars)
 6. ACTIDIN_EMPTY                         -- 'Acct ID can NOT be empty...' (L161)
 7. ACCTDAT_NOTFND                        -- 'Account ID NOT found...' (L361)
 8. ACCT_CURR_BAL_ZERO                    -- 'You have nothing to pay...' (L201)
 9. CONFIRM_BLANK_PROMPT                  -- 'Confirm to make a bill payment...' (L237)
10. CONFIRM_INVALID_X                     -- 'Invalid value. Valid values are (Y/N)...' (L187)
11. CONFIRM_NO_DECLINE                    -- CLEAR-CURRENT-SCREEN; no payment (L178-L181)
12. CONFIRM_YES_HAPPY_PATH_PAYMENT_OK     -- WRITE TRANSACT + REWRITE ACCTDAT; success msg DFHGREEN
13. CXACAIX_NOTFND_ON_YPATH               -- 'Account ID NOT found...' (L425)
14. AUTO_TRIGGER_CDEMO_CB00_TRN_SELECTED  -- L116-L121 auto-PERFORM PROCESS-ENTER-KEY
```

Scenario 12 is the multi-file mutation happy path and exercises the DOUBLE-SPACE invariant in the success message.

## Cross-reference to the Java test class

The consuming Java test class is `com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoBil00CGoldenTest.java`. The class under test is `com.blitzy.carddemo.application.billpay.CoBil00C` at `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/billpay/CoBil00C.java`, with paired DTO records `CoBil00Input.java` and `CoBil00Output.java` in the same package per AAP Sec 0.4.1.

The 5 base-class override methods route every fixture path to the sibling `../expected/` folder or to `app/data/ASCII/`:

- `programClass()` -> `CoBil00C.class`
- `inputFile()` -> `resolveExpectedOutputPath("cobil00c", "input_scenario.txt")` -- routes to `../expected/input_scenario.txt`
- `expectedOutputFile()` -> `resolveExpectedOutputPath("cobil00c", "stdout.txt")` -- routes to `../expected/stdout.txt`
- `auxiliaryInputs()` -> references `app/data/ASCII/acctdata.txt` AND `app/data/ASCII/cardxref.txt` via classpath relative path (NOT copied)
- `expectedOutputs()` -> four entries: `stdout.txt`, `bms_output.txt`, `transact.txt` (post-WRITE; +350 bytes), `acctdata.txt` (post-REWRITE; SAME byte length as initial)

**None of the overrides route to this `input/` folder. This is the architectural reason the folder is empty.** The test class MUST be annotated `@Disabled("Pending COBOL baseline capture -- see MIGRATION_NOTES.md Sec 1.6")` per AAP Sec 0.6.11 until the COBOL-captured artifacts replace the placeholder content in `../expected/`.

## Capture procedure cross-reference

The COBOL baseline capture procedure for `transact.txt`, `acctdata.txt`, `stdout.txt`, and `bms_output.txt` is documented in `java/MIGRATION_NOTES.md` Sec 1.6 per AAP Sec 0.7.5. Until those captures are committed to the sibling `../expected/` folder, `CoBil00CGoldenTest` remains `@Disabled` per AAP Sec 0.6.11.

## Behavioral invariants preserved by this fixture

- **Verbatim messages**: All 14 distinct strings (13 from COBIL00C + 1 from CSMSG01Y) MUST be preserved character-for-character (AAP Sec 0.7.1).
- **Trailing-ellipsis discipline**: Use 3 ASCII periods (`...`) only -- NEVER Unicode ellipsis. All COBIL00C messages end with `...` (no exceptions in COBIL00C).
- **DOUBLE-SPACE in success message**: The string `'Payment successful.  Your Transaction ID is <id>.'` contains TWO ASCII spaces between `successful.` and `Your`, arising from STRING composition at COBIL00C.cbl L527-L531. Do NOT collapse to one space.
- **CCDA-MSG-INVALID-KEY trailing spaces**: The 50-char constant pads text content to PIC X(50) with trailing spaces. All 50 chars MUST be preserved.
- **Multi-file atomic mutation**: WRITE TRANSACT (+350 bytes) AND REWRITE ACCTDAT (in-place; byte length unchanged) occur atomically within the same successful execution (L233 + L235). COBIL00C is unique among CardDemo online programs in this respect.
- **Full-balance payment semantics**: `ACCT-CURR-BAL_after = 0.00` always for successful payments (L224 + L234). BigDecimal scale=2 preserved per AAP Sec 0.6.1 -- `0.00` NEVER normalized to `0` or `0.0`.
- **TRAN-ID auto-increment**: Computed via STARTBR with `RIDFLD = HIGH-VALUES`, READPREV, ADD 1 (L212-L217). Empty-file fallback at L487-L488 sets TRAN-ID to ZEROS, yielding `0000000000000001` as the first record's TRAN-ID after ADD 1.
- **Fixed TRAN-RECORD metadata signature**: every TRAN-RECORD produced by COBIL00C has TRAN-TYPE-CD=`'02'`, TRAN-CAT-CD=2, TRAN-SOURCE=`'POS TERM'`, TRAN-DESC=`'BILL PAYMENT - ONLINE'`, TRAN-MERCHANT-ID=999999999, TRAN-MERCHANT-NAME=`'BILL PAYMENT'`, TRAN-MERCHANT-CITY=`'N/A'`, TRAN-MERCHANT-ZIP=`'N/A'` (L218-L229).
- **Deterministic CURDATE/CURTIME/timestamps**: COBIL00C calls `FUNCTION CURRENT-DATE` at L321 (POPULATE-HEADER-INFO) and `EXEC CICS ASKTIME/FORMATTIME` at L249-L267 (GET-CURRENT-TIMESTAMP). Java translation MUST inject `ScopedValue<Clock>` per AAP Sec 0.6.6 so CURDATE, CURTIME, TRAN-ORIG-TS, and TRAN-PROC-TS are deterministic for byte-for-byte parity.
- **PAN masking discipline**: Java logs MUST mask all but the last 4 digits of any card-number value (TRAN-CARD-NUM from XREF) per AAP Sec 0.7.2. BMS screen captures are NOT log surfaces and preserve full PAN.
- **Sort orders preserved**: ACCT-ID ascending (VSAM KSDS REWRITE in-place, no resort); TRAN-ID ascending (16-digit numeric string padded with leading zeros sorts equal to numeric order).
- **`@CobolProgram` annotation**: The Java translation MUST carry an `@CobolProgram("COBIL00C")` Javadoc annotation citing the original PROGRAM-ID, source path `app/cbl/COBIL00C.cbl`, and translation date per AAP Sec 0.7.1.

## Source lineage

The `app/` tree is preserved unmodified per AAP Sec 0.1.1 and Sec 0.2.2. The following files are REFERENCE-only inputs to the Java translation and the golden-record harness:

- `app/cbl/COBIL00C.cbl` (572 lines) -- COBOL CICS source program; PROGRAM-ID at L23-L25; transaction `'CB00'`; MAIN-PARA dispatch at L99-L149; PROCESS-ENTER-KEY at L154-L244; READ-ACCTDAT-FILE at L343-L372; UPDATE-ACCTDAT-FILE at L377-L403; READ-CXACAIX-FILE at L408-L436; STARTBR/READPREV/ENDBR at L441-L505; WRITE-TRANSACT-FILE at L510-L547 (DOUBLE-SPACE success at L527-L531); GET-CURRENT-TIMESTAMP at L249-L267; SEND-BILLPAY-SCREEN at L289-L301; CLEAR-CURRENT-SCREEN at L552-L555; INITIALIZE-ALL-FIELDS at L560-L566.
- `app/bms/COBIL00.bms` (141 lines) -- BMS map definition; MAPSET `COBIL00`, MAP `COBIL0A`, 24x80 screen with 2 unprotected fields (ACTIDIN, CONFIRM), 1 display-only field (CURBAL), 1 error field (ERRMSG); IC marker on ACTIDIN at L85-L89.
- `app/cpy-bms/COBIL00.CPY` (140 lines) -- Symbolic map copybook with `01 COBIL0AI` input record and `01 COBIL0AO REDEFINES COBIL0AI` output record.
- `app/cpy/CVACT01Y.cpy` -- 300-byte ACCOUNT-RECORD layout (ACCT-CURR-BAL at bytes 13-24; BigDecimal scale=2).
- `app/cpy/CVACT03Y.cpy` -- 50-byte CARD-XREF-RECORD (XREF-CARD-NUM, XREF-CUST-ID, XREF-ACCT-ID).
- `app/cpy/CVTRA05Y.cpy` -- 350-byte TRAN-RECORD layout (TRAN-ID, TRAN-AMT BigDecimal scale=2, TRAN-ORIG-TS/TRAN-PROC-TS LocalDateTime, fixed metadata fields).
- `app/cpy/COCOM01Y.cpy` -- CARDDEMO-COMMAREA with embedded `CDEMO-CB00-INFO` sub-record (`CDEMO-CB00-TRN-SELECTED` auto-trigger field).
- `app/cpy/CSMSG01Y.cpy` -- `CCDA-MSG-INVALID-KEY` constant (50 chars) referenced at COBIL00C.cbl L140.
- `app/cpy/COTTL01Y.cpy` -- Screen titles (CCDA-TITLE01, CCDA-TITLE02).
- `app/cpy/CSDAT01Y.cpy` -- WS-CURDATE / WS-CURTIME / WS-TIMESTAMP working storage.
- `app/data/ASCII/acctdata.txt` -- Read-only ACCTDATA fixture (REFERENCE; consumed via classpath relative path; NOT copied per AAP Sec 0.4.1).
- `app/data/ASCII/cardxref.txt` -- Read-only CARDXREF fixture (REFERENCE; consumed via classpath relative path; NOT copied per AAP Sec 0.4.1).

## Authority references

- AAP Sec 0.1.1 (`app/` tree preservation; immutable reference implementation)
- AAP Sec 0.2.1 (in-scope: `java/carddemo-tests/src/test/resources/golden/**`)
- AAP Sec 0.2.2 (out-of-scope: `app/` tree never modified)
- AAP Sec 0.3.1 (golden-record harness structure: `<program>/input/` + `<program>/expected/`)
- AAP Sec 0.4.1 (one Java class per COBOL PROGRAM-ID; BMS map -> DTO records; ASCII fixtures via classpath, NEVER copied)
- AAP Sec 0.6.1 (BigDecimal scale=2 for ACCT-CURR-BAL and TRAN-AMT; MathContext.DECIMAL128; banker's rounding)
- AAP Sec 0.6.4 (`java.time` mandate; LocalDateTime for TRAN-ORIG-TS / TRAN-PROC-TS)
- AAP Sec 0.6.5 (`java.nio.file` mandate)
- AAP Sec 0.6.6 (`ScopedValue` replaces `ThreadLocal`; fixed-Clock injection for deterministic CURDATE/CURTIME/timestamps)
- AAP Sec 0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern)
- AAP Sec 0.6.12 (architectural override: no Spring/PostgreSQL/Hibernate/Docker/AWS)
- AAP Sec 0.7.1 (Minimal Change Clause; verbatim messages with exact 3-ASCII-period ellipses; preserve DOUBLE-SPACE)
- AAP Sec 0.7.2 (PAN masking last-4 in logs; BMS screen exempt)
- AAP Sec 0.7.4 (no JEP preview features; no `default` branches in sealed-type switch)
- AAP Sec 0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` Sec 1.6)

## DO NOT add files here

```text
This folder is documentation-only. Adding ANY of the following is forbidden:

- Any fixture data file (.txt, .jsonl, .bin, .csv, .json, .xml)
- A .gitkeep placeholder (the README itself preserves the folder in git)
- Any subfolder
- Any file other than this README.md
- Any captured COBOL artifact (those live in the sibling ../expected/ folder)
- Any classpath-relative reference file (those live in app/data/ASCII/ and are
  read via classpath relative path per AAP Sec 0.4.1; they are never copied)

Rationale: The Java test class
com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest resolves ALL fixture paths
through GoldenRecordTest.resolveExpectedOutputPath("cobil00c", fileName), which
mechanically routes every path to src/test/resources/golden/cobil00c/expected/.
Therefore no file placed in golden/cobil00c/input/ would be loaded by the
harness at runtime. Any data file added here would be dead code.

If a future fixture scenario genuinely requires a data file:
1. Add the file to the sibling ../expected/ folder (NOT here).
2. Update ../expected/README.md to document the new fixture.
3. Update CoBil00CGoldenTest to consume the new file via resolveExpectedOutputPath.
4. Update java/MIGRATION_NOTES.md Sec 1.6 if capture procedure changes.
```
