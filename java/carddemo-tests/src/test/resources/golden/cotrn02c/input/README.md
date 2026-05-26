# COTRN02C (Add Transaction, CT02) — Golden-Record `input/` Folder (Documentation-Only)

This folder is a documentation-only marker; it contains exactly ONE file, this README. The consuming Java test class `com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` resolves every fixture path through the base class helper `resolveExpectedOutputPath("cotrn02c", fileName)`, which mechanically routes every conceptual input fixture to the sibling `../expected/` folder. COTRN02C is the most complex online program in CardDemo — a 783-line COBOL source at `app/cbl/COTRN02C.cbl` with 13 unprotected BMS input fields plus one confirmation field, dual `CSUTLDTC` date-validity calls (one each for origination and processing dates), a two-path `CARDXREF` AIX cross-reference lookup, and a byte-significant double-space in the success message. The presence of this `input/` folder preserves the per-program `<program>/input/` + `<program>/expected/` symmetry mandated by AAP §0.3.1 while making the absence of fixture data files explicit and self-documenting.

## Why this folder contains no fixture data

The Java test class `com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The base class helper `resolveExpectedOutputPath(programDir, fileName)` mechanically routes every fixture path to `src/test/resources/golden/<programDir>/expected/`. Therefore ALL input fixtures (the scenario driver script, the seed-state TRANSACT records, the captured stdout, the serialized BMS output) live in the sibling `../expected/` folder.

COTRN02C is a CICS pseudo-conversational online program (transaction `CT02`, source `app/cbl/COTRN02C.cbl`, Java translation `com.blitzy.carddemo.application.transaction.CoTrn02C`). Its user-facing input arrives via the BMS map `COTRN2A` defined in `app/bms/COTRN02.bms`. Input is NOT file-based fixtures sourced from `app/data/ASCII/*.txt`; instead, the deterministic test scenario is encoded as a synthesized scenario script plus references to the read-only ASCII fixtures `app/data/ASCII/cardxref.txt`, `app/data/ASCII/acctdata.txt`, and `app/data/ASCII/dailytran.txt`, which are referenced via classpath relative path per AAP §0.4.1 and NEVER copied into `golden/cotrn02c/`.

Keeping an empty `input/` folder with only this README preserves the established `<program>/input/` + `<program>/expected/` symmetry from AAP §0.3.1 across all 28+ program test fixtures, while making the absence of data files self-documenting.

The sibling `../expected/` folder contains:

- `../expected/README.md` — 13-phase authoritative fixture contract for the COTRN02C golden-record test.
- `../expected/input_scenario.txt` — Multi-scenario CICS pseudo-conversation driver script (TEST-OWNED).
- `../expected/transact.txt` — TRANSACT VSAM KSDS seed-and-post-WRITE state fixture (CAPTURE PLACEHOLDER).
- `../expected/stdout.txt` — Captured COBOL DISPLAY output (CAPTURE PLACEHOLDER).
- `../expected/bms_output.txt` — Serialized BMS SEND MAP output across scenarios (CAPTURE PLACEHOLDER).

## Conceptual input contract

### BMS map COTRN2A fields (15 total)

```text
ACTIDIN  (6,21)  len=11  ATTRB=(FSET,IC,NORM,UNPROT)  ← initial cursor; Account number
CARDNIN  (6,55)  len=16  ATTRB=(FSET,NORM,UNPROT)     ← Card number (alternative to Account)
TTYPCD   (10,15) len=2   ATTRB=(FSET,NORM,UNPROT)     ← Type code
TCATCD   (10,36) len=4   ATTRB=(FSET,NORM,UNPROT)     ← Category code
TRNSRC   (10,54) len=10  ATTRB=(FSET,NORM,UNPROT)     ← Source
TDESC    (12,19) len=60  ATTRB=(FSET,NORM,UNPROT)     ← Description
TRNAMT   (14,14) len=12  ATTRB=(FSET,NORM,UNPROT)     ← Amount (regex ^[+-][0-9]{8}\.[0-9]{2}$)
TORIGDT  (14,42) len=10  ATTRB=(FSET,NORM,UNPROT)     ← Origination date (YYYY-MM-DD)
TPROCDT  (14,68) len=10  ATTRB=(FSET,NORM,UNPROT)     ← Processing date (YYYY-MM-DD)
MID      (16,19) len=9   ATTRB=(FSET,NORM,UNPROT)     ← Merchant ID
MNAME    (16,48) len=30  ATTRB=(FSET,NORM,UNPROT)     ← Merchant name
MCITY    (18,21) len=25  ATTRB=(FSET,NORM,UNPROT)     ← Merchant city
MZIP     (18,67) len=10  ATTRB=(FSET,NORM,UNPROT)     ← Merchant ZIP
CONFIRM  (21,63) len=1   ATTRB=(FSET,NORM,UNPROT)     ← Y/N confirmation
ERRMSG   (23,1)  len=78  ATTRB=(ASKIP,BRT,FSET)  COLOR=RED  ← dynamic color (DFHGREEN on success)
```

The map exposes 13 unprotected editable fields plus CONFIRM = 14 user-editable positions; the 15th field is ERRMSG (ASKIP — display-only with dynamic color). The IC marker on ACTIDIN is the initial cursor position at startup per `app/bms/COTRN02.bms:L88`. After validation errors, the cursor is repositioned via `MOVE -1 TO <field>L` to the failing field's length sub-field.

### EIBAID dispatch (4 named keys + WHEN OTHER)

```text
DFHENTER → PERFORM PROCESS-ENTER-KEY           [L134-L135]
DFHPF3   → PERFORM RETURN-TO-PREV-SCREEN       [L136-L143]
DFHPF4   → PERFORM CLEAR-CURRENT-SCREEN        [L144-L145]
DFHPF5   → PERFORM COPY-LAST-TRAN-DATA         [L146-L147]
WHEN OTHER → MOVE CCDA-MSG-INVALID-KEY         [L148-L151]
```

COTRN02C handles EXACTLY 4 named AID keys plus WHEN OTHER. There is NO PF7/PF8 (no pagination — contrast with COUSR00C) and NO PF12 (no footer-vs-code mismatch — the footer accurately reflects the 4 handled keys).

### Two-pass conversation flow

The first ENTER pass triggers `PERFORM VALIDATE-INPUT-KEY-FIELDS` and `PERFORM VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl:L166-L167`. After validation succeeds, if `CONFIRMI = 'Y' OR 'y'` then `PERFORM ADD-TRANSACTION` fires at `app/cbl/COTRN02C.cbl:L170-L172`; if `CONFIRMI` is blank, low-values, `'N'`, or `'n'`, the screen is re-prompted with `'Confirm to add this transaction...'` at `app/cbl/COTRN02C.cbl:L178`; for any other value, `'Invalid value. Valid values are (Y/N)...'` fires at `app/cbl/COTRN02C.cbl:L184`. The `ADD-TRANSACTION` paragraph executes `EXEC CICS STARTBR/READPREV/ENDBR` on TRANSACT to find the highest existing TRAN-ID, increments it by 1, populates the 13 fields of TRAN-RECORD, then issues `EXEC CICS WRITE` to TRANSACT at `app/cbl/COTRN02C.cbl:L442-L466`.

### Auto-trigger from COTRN00C row selection

When a previous invocation of COTRN00C populates `CDEMO-CT02-TRN-SELECTED` in the commarea (typically from row 'A' selection on the transaction-list screen), COTRN02C's first-pass logic at `app/cbl/COTRN02C.cbl:L124-L129` auto-populates `CARDNINI` from this commarea field and auto-PERFORMs `PROCESS-ENTER-KEY` before the normal `SEND-TRNADD-SCREEN`:

```cobol
IF CDEMO-CT02-TRN-SELECTED NOT =
                            SPACES AND LOW-VALUES
    MOVE CDEMO-CT02-TRN-SELECTED TO
         CARDNINI OF COTRN2AI
    PERFORM PROCESS-ENTER-KEY
END-IF
```

This auto-trigger distinguishes COTRN02C from sibling user-management programs (COUSR01C, COUSR02C, COUSR03C) which do not have an analogous commarea-driven auto-PERFORM at first-pass entry.

### Verbatim COBOL error messages (preserve byte-for-byte)

The following 33 verbatim message strings MUST be preserved exactly by the Java translation per AAP §0.7.1 (Minimal Change Clause). Preserve the trailing 3-ASCII-period ellipsis `...` (NEVER the Unicode horizontal-ellipsis character at codepoint U+2026); preserve the absence of trailing ellipsis on the three format messages; preserve the double-space in the success message verbatim.

```text
Key-field validation (3):
  'Account ID must be Numeric...'                       [app/cbl/COTRN02C.cbl:L199]
  'Card Number must be Numeric...'                      [app/cbl/COTRN02C.cbl:L213]
  'Account or Card Number must be entered...'           [app/cbl/COTRN02C.cbl:L226]

Empty-field validation (11; FIRST-empty-wins order):
  'Type CD can NOT be empty...'                         [app/cbl/COTRN02C.cbl:L254]
  'Category CD can NOT be empty...'                     [app/cbl/COTRN02C.cbl:L260]
  'Source can NOT be empty...'                          [app/cbl/COTRN02C.cbl:L266]
  'Description can NOT be empty...'                     [app/cbl/COTRN02C.cbl:L272]
  'Amount can NOT be empty...'                          [app/cbl/COTRN02C.cbl:L278]
  'Orig Date can NOT be empty...'                       [app/cbl/COTRN02C.cbl:L284]
  'Proc Date can NOT be empty...'                       [app/cbl/COTRN02C.cbl:L290]
  'Merchant ID can NOT be empty...'                     [app/cbl/COTRN02C.cbl:L296]
  'Merchant Name can NOT be empty...'                   [app/cbl/COTRN02C.cbl:L302]
  'Merchant City can NOT be empty...'                   [app/cbl/COTRN02C.cbl:L308]
  'Merchant Zip can NOT be empty...'                    [app/cbl/COTRN02C.cbl:L314]

Numeric-content validation (3):
  'Type CD must be Numeric...'                          [app/cbl/COTRN02C.cbl:L325]
  'Category CD must be Numeric...'                      [app/cbl/COTRN02C.cbl:L331]
  'Merchant ID must be Numeric...'                      [app/cbl/COTRN02C.cbl:L432]

Format validation (3; NOTE: NO trailing ellipsis on these three):
  'Amount should be in format -99999999.99'             [app/cbl/COTRN02C.cbl:L345]
  'Orig Date should be in format YYYY-MM-DD'            [app/cbl/COTRN02C.cbl:L360]
  'Proc Date should be in format YYYY-MM-DD'            [app/cbl/COTRN02C.cbl:L375]

Date-validity (dual CSUTLDTC) (2):
  'Orig Date - Not a valid date...'                     [app/cbl/COTRN02C.cbl:L401]
  'Proc Date - Not a valid date...'                     [app/cbl/COTRN02C.cbl:L421]

Confirm dispatch (2):
  'Confirm to add this transaction...'                  [app/cbl/COTRN02C.cbl:L178]
  'Invalid value. Valid values are (Y/N)...'            [app/cbl/COTRN02C.cbl:L184]

File-IO outcomes (8):
  'Account ID NOT found...'                             [app/cbl/COTRN02C.cbl:L593]
  'Unable to lookup Acct in XREF AIX file...'           [app/cbl/COTRN02C.cbl:L600]
  'Card Number NOT found...'                            [app/cbl/COTRN02C.cbl:L626]
  'Unable to lookup Card # in XREF file...'             [app/cbl/COTRN02C.cbl:L633]
  'Transaction ID NOT found...'                         [app/cbl/COTRN02C.cbl:L657]
  'Unable to lookup Transaction...'                     [app/cbl/COTRN02C.cbl:L664, L693]
  'Tran ID already exist...'                            [app/cbl/COTRN02C.cbl:L738]
  'Unable to Add Transaction...'                        [app/cbl/COTRN02C.cbl:L745]

Success message (1; DOUBLE SPACE preserved):
  'Transaction added successfully.  Your Tran ID is <16-char>.'   [app/cbl/COTRN02C.cbl:L728-L732]
```

The success message double-space arises from STRING composition at L728-L732: `'Transaction added successfully. '` (trailing 1 space, 32 chars) + `' Your Tran ID is '` (leading 1 space, 17 chars) = `'Transaction added successfully.  Your Tran ID is '` with TWO spaces between `successfully.` and `Your`. This is byte-significant per AAP §0.7.1. The message `'Unable to lookup Transaction...'` appears at TWO sites (L664 from STARTBR WHEN OTHER, L693 from READPREV WHEN OTHER) with identical verbatim text. The total distinct message count is 33 (3 + 11 + 3 + 3 + 2 + 2 + 8 + 1 = 33). The CCDA-MSG-INVALID-KEY constant from `app/cpy/CSMSG01Y.cpy:L20-L21` is referenced via `MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE` at `app/cbl/COTRN02C.cbl:L150` and is documented in the sibling `../expected/README.md`.

### Dual CSUTLDTC date-validity calls

COTRN02C is the only program in CardDemo that calls CSUTLDTC TWICE in sequence (once for `TORIGDT` at `app/cbl/COTRN02C.cbl:L389-L407`, once for `TPROCDT` at `app/cbl/COTRN02C.cbl:L409-L427`). The shared parameter structure is defined at `app/cbl/COTRN02C.cbl:L62-L69`:

```text
01 CSUTLDTC-PARM.
   05 CSUTLDTC-DATE                   PIC X(10).
   05 CSUTLDTC-DATE-FORMAT            PIC X(10).
   05 CSUTLDTC-RESULT.
      10 CSUTLDTC-RESULT-SEV-CD       PIC X(04).   ← '0000' = success
      10 FILLER                       PIC X(11).
      10 CSUTLDTC-RESULT-MSG-NUM      PIC X(04).   ← '2513' = soft-fail; continue
      10 CSUTLDTC-RESULT-MSG          PIC X(61).
```

The Java translation lives in `com.blitzy.carddemo.application.util.DateValidator` per AAP §0.4.1 and uses `LocalDate.parse` with `ResolverStyle.STRICT` per AAP §0.6.4. The success criterion is `CSUTLDTC-RESULT-SEV-CD = '0000'`. The soft-fail criterion is `SEV-CD != '0000'` AND `MSG-NUM = '2513'` (continue without emitting error). The hard-fail criterion is `SEV-CD != '0000'` AND `MSG-NUM != '2513'` (emit verbatim error and re-prompt).

### CARDXREF AIX lookup (CXACAIX + CCXREF)

Two cross-reference paths are documented at `app/cbl/COTRN02C.cbl:L576-L637`: when `ACTIDINI` is populated, `READ CXACAIX` (alternate index by account-ID) at L576-L604 moves `XREF-CARD-NUM` into `CARDNINI`; when `CARDNINI` is populated, `READ CCXREF` (primary index by card-number) at L609-L637 moves `XREF-ACCT-ID` into `ACTIDINI`. When both are empty, the program emits `'Account or Card Number must be entered...'` at L226. The Java translation port is `com.blitzy.carddemo.domain.port.CardXrefRepository` with two methods (`findByAccountId(long)` and `findByCardNumber(String)`) per AAP §0.4.1.

## Cross-reference to the Java test class

The consuming Java test class is `com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoTrn02CGoldenTest.java`. The test class MUST be annotated `@Disabled("Pending COBOL baseline capture — see MIGRATION_NOTES.md §1.6")` per AAP §0.6.11 until the COBOL-captured artifacts (`transact.txt`, `stdout.txt`, `bms_output.txt`) replace the placeholder content in the sibling `../expected/` folder.

The class under test is `com.blitzy.carddemo.application.transaction.CoTrn02C` at `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CoTrn02C.java`, with paired DTO records `CoTrn02Input.java` and `CoTrn02Output.java` in the same package per AAP §0.4.1. The base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` exposes a method `resolveExpectedOutputPath(programDir, fileName)` that maps EVERY fixture (whether conceptually "input" or "expected") to `src/test/resources/golden/<programDir>/expected/<fileName>`. This is the technical reason this `input/` folder contains no data files.

## Capture procedure cross-reference

The COBOL baseline capture procedure for `transact.txt`, `stdout.txt`, and `bms_output.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until those captures are committed to the sibling `../expected/` folder, `CoTrn02CGoldenTest` remains `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **Verbatim error messages**: All 33 distinct strings in the catalog above MUST be preserved character-for-character by the Java translation (AAP §0.7.1).
- **Trailing-ellipsis discipline**: Use 3 ASCII periods (`...`) only — NEVER the Unicode horizontal-ellipsis character at codepoint U+2026. The three format messages (Amount/Orig Date/Proc Date format) deliberately have NO trailing ellipsis — preserve that absence.
- **Double-space in success message**: The string `'Transaction added successfully.  Your Tran ID is <id>.'` contains TWO ASCII spaces between `successfully.` and `Your`, arising from the STRING composition at L728-L732. Do NOT collapse to one space.
- **Amount regex**: `^[+-][0-9]{8}\.[0-9]{2}$` — sign is REQUIRED (no implicit positive), 8 digits before decimal, exactly 2 digits after. Per `app/cbl/COTRN02C.cbl:L339-L351`.
- **FIRST-empty-wins ordering**: The 11 empty-field checks at L251-L320 fire in EXACT source order (Type CD → Category CD → Source → Description → Amount → Orig Date → Proc Date → Merchant ID → Merchant Name → Merchant City → Merchant Zip). The Java translation MUST preserve this ordering.
- **Sort order**: TRANSACT VSAM KSDS is sorted ascending by TRAN-ID (16-byte key). After a successful WRITE, file byte length grows by exactly 350 bytes per `app/cpy/CVTRA05Y.cpy`. The new record is appended at the highest TRAN-ID position via auto-increment.
- **Auto-increment TRAN-ID**: Computed via `STARTBR` with `RIDFLD = HIGH-VALUES`, `READPREV`, `ADD 1` at `app/cbl/COTRN02C.cbl:L444-L449`. Empty-file fallback at L688-L689 sets TRAN-ID to ZEROS, yielding `0000000000000001` as the first record's TRAN-ID after `ADD 1`.
- **Deterministic CURDATE/CURTIME**: The Java translation MUST inject `ScopedValue<Clock>` per AAP §0.6.6 so the BMS frame's header timestamp is deterministic for byte-for-byte golden-record parity.
- **PAN masking discipline**: Java logs MUST mask all but the last 4 digits of any card-number value per AAP §0.7.2. Fixture files may contain test-controlled card-number values (NOT real customer PANs), but log output never carries an unmasked PAN.
- **Validation pipeline isolation**: `VALIDATE-INPUT-KEY-FIELDS` at L193-L230 executes BEFORE `VALIDATE-INPUT-DATA-FIELDS` at L235-L437. The PERFORM order at L166-L167 is byte-significant for error-message priority.
- **`@CobolProgram` annotation**: The Java translation MUST carry an `@CobolProgram("COTRN02C")` Javadoc annotation citing the original PROGRAM-ID, source path `app/cbl/COTRN02C.cbl`, and translation date per AAP §0.7.1.

## Source lineage

- `app/cbl/COTRN02C.cbl` (783 lines) — COBOL CICS source program; transaction ID `'CT02'` at L37; PROGRAM-ID at L23; MAIN-PARA dispatch at L107-L159; PROCESS-ENTER-KEY at L164-L188; validation paragraphs at L193-L437; ADD-TRANSACTION at L442-L466; CSUTLDTC dual-call at L389-L427; READ-CXACAIX-FILE at L576-L604; READ-CCXREF-FILE at L609-L637; STARTBR/READPREV/ENDBR at L642-L706; WRITE-TRANSACT-FILE at L711-L749 (double-space success at L724-L734).
- `app/cbl/CSUTLDTC.cbl` — CEEDAYS date-validity wrapper called twice from COTRN02C (ORIG and PROC dates).
- `app/bms/COTRN02.bms` (307 lines) — BMS map definition; MAPSET `COTRN02`, MAP `COTRN2A`, 24×80 screen with 13 unprotected fields + CONFIRM + ERRMSG; IC marker on ACTIDIN at L85-L90; footer at L297-L302.
- `app/cpy-bms/COTRN02.CPY` (272 lines) — Symbolic map copybook with `01 COTRN2AI` input record and `01 COTRN2AO REDEFINES COTRN2AI` output record; 15 fields each × 4-5 sub-fields.
- `app/cpy/CVTRA05Y.cpy` — 350-byte TRAN-RECORD layout (REFERENCE for TRANSACT VSAM KSDS WRITEs).
- `app/cpy/COCOM01Y.cpy` — CARDDEMO-COMMAREA with embedded `CDEMO-CT02-INFO` sub-record at L72-L80 of COTRN02C (auto-trigger payload via `CDEMO-CT02-TRN-SELECTED`).
- `app/cpy/CSMSG01Y.cpy` — `CCDA-MSG-INVALID-KEY` constant referenced at COTRN02C L150.
- `app/data/ASCII/cardxref.txt` — Read-only CARDXREF fixture (REFERENCE only; consumed via classpath relative path).
- `app/data/ASCII/acctdata.txt` — Read-only ACCTDATA fixture (REFERENCE only).
- `app/data/ASCII/dailytran.txt` — Read-only DALYTRAN fixture (REFERENCE only).

## Authority references

- AAP §0.1.1 (app/ tree preservation; immutable reference implementation)
- AAP §0.2.1 (in-scope: `golden/cotrn02c/` directory tree)
- AAP §0.2.2 (out-of-scope: app/ tree never modified)
- AAP §0.3.1 (golden-record harness structure: `<program>/input/` + `<program>/expected/`)
- AAP §0.4.1 (COTRN02C → `com.blitzy.carddemo.application.transaction.CoTrn02C`; BMS map → DTO records; ASCII fixtures referenced via classpath, NOT copied)
- AAP §0.6.1 (Decimal arithmetic fidelity; BigDecimal scale 2 for TRAN-AMT)
- AAP §0.6.4 (`java.time` mandate; CSUTLDTC → `DateValidator` using `LocalDate.parse` strict resolver)
- AAP §0.6.5 (`java.nio.file` mandate; NO `java.io.File`)
- AAP §0.6.6 (`ScopedValue` replaces `ThreadLocal`; fixed-clock injection for deterministic CURDATE/CURTIME)
- AAP §0.6.11 (golden-record harness as non-negotiable PR gate; `@Disabled` scaffolding pattern)
- AAP §0.6.12 (architectural override: no Spring, no PostgreSQL, no Spring Batch, no Hibernate, no Flyway, no Spring Security)
- AAP §0.7.1 (Minimal Change Clause; preserve verbatim error messages with exact 3-ASCII-period ellipses; preserve double-space in success message; preserve amount regex; preserve sort orders; preserve FIRST-empty-wins ordering)
- AAP §0.7.2 (PCI controls; no PAN logged in full — mask all but last 4 digits)
- AAP §0.7.4 (no JEP preview features)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)

## DO NOT add files here

```text
This folder is documentation-only. Adding ANY of the following is forbidden:

- Any fixture data file (.txt, .jsonl, .bin, .csv, .json, .xml)
- A .gitkeep placeholder (the README itself preserves the folder in git)
- Any subfolder
- Any file other than this README.md
- Any captured COBOL artifact (those live in the sibling ../expected/ folder)
- Any classpath-relative reference file (those live in app/data/ASCII/ and are
  read via classpath relative path per AAP section 0.4.1; they are never copied)

Rationale: The Java test class
com.blitzy.carddemo.tests.golden.CoTrn02CGoldenTest resolves ALL fixture paths
through GoldenRecordTest.resolveExpectedOutputPath("cotrn02c", fileName), which
mechanically routes every path to src/test/resources/golden/cotrn02c/expected/.
Therefore no file placed in golden/cotrn02c/input/ would be loaded by the
harness at runtime. Any data file added here would be dead code.

If a future fixture scenario genuinely requires a data file:
1. Add the file to the sibling ../expected/ folder (NOT here).
2. Update ../expected/README.md to document the new fixture.
3. Update CoTrn02CGoldenTest to consume the new file via resolveExpectedOutputPath.
4. Update java/MIGRATION_NOTES.md if capture procedure changes.
```
