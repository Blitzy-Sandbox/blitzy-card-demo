# COCRDLIC (Card List Pagination, CCLI) -- Golden-Record `input/` Folder (Documentation-Only)

This folder is documentation-only and contains EXACTLY ONE file: this `README.md`. NO fixture binaries, NO `*.txt` data files, NO copies of `app/data/ASCII/*.txt`, NO captured outputs, NO Java sources, and NO embedded COBOL source belong here. The actual CARDDAT, CARDXREF, and ACCTDATA input bytes consumed by the COCRDLIC golden-record harness live at `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt`, and `app/data/ASCII/acctdata.txt` respectively, and are read via classpath relative path per AAP Sec 0.4.1 -- they are NEVER copied into the Java tree. The synthesized BMS keystroke driver that exercises the paginated browse scenarios lives in the sibling `../expected/input_scenario.txt`. COCRDLIC is a READ-ONLY paginated browse over the CARDDAT VSAM KSDS using STARTBR, READNEXT, READPREV, and ENDBR (no WRITE, REWRITE, or DELETE verb anywhere in the 1459-line COBOL source). COCRDLIC supports a dual-action selection model: a row selector value of `'S'` dispatches XCTL to COCRDSLC (card view) and a value of `'U'` dispatches XCTL to COCRDUPC (card update). The sibling `../expected/README.md` is the authoritative per-program contract; THIS file is a navigational pointer to that authoritative contract.

## 1. Why This Folder Is Documentation-Only

- Per AAP Sec 0.4.1: ASCII fixtures at `app/data/ASCII/*.txt` are read via classpath relative path; they are NOT COPIED into the Java tree.
- Per AAP Sec 0.2.2: the `app/` tree is immutable; no fixture mirrors anywhere in `java/`.
- Per AAP Sec 0.7.2: no full PAN may be embedded in any test artifact; this folder having no fixture files trivially satisfies that constraint.
- `app/cbl/COCRDLIC.cbl:L213-L217` declares `LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '` (primary KSDS) and `LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '` (alternate index for account-based access); the `CARDAIX` constant is DECLARED BUT UNUSED in the COCRDLIC procedure division -- the runtime browse uses only the primary `CARDDAT` dataset. Per AAP Sec 0.7.1 (Minimal Change Clause), the unused constant declaration is translated faithfully and flagged in `java/MIGRATION_NOTES.md` as dead code preserved for completeness.

**Fixture file homes for COCRDLIC**:

1. `app/data/ASCII/carddata.txt` -- primary CARDDAT KSDS image (REFERENCE, NOT COPIED)
2. `app/data/ASCII/cardxref.txt` -- CARDXREF cross-reference (REFERENCE, optional/auxiliary)
3. `app/data/ASCII/acctdata.txt` -- ACCTDATA reference (REFERENCE, auxiliary for filter validation)
4. `../expected/input_scenario.txt` -- synthesized BMS keystroke driver (14 scenarios)
5. `../expected/stdout.txt` -- captured DISPLAY output placeholder (COCRDLIC has zero DISPLAY verbs, so this is expected empty)
6. `../expected/bms_output.txt` -- captured BMS SEND MAP frames placeholder (approximately 14 frames once captured)

## 2. Conceptual Input Contract

### 2.1 BMS Map Inputs

- `ACCTSID` PIC X(11) at BMS position (6,44) (row 6 column 44) -- 11-digit account ID filter (optional; blank means "no filter") -- cite `app/bms/COCRDLI.bms:L89-L93` for the BMS DFHMDF definition and `app/cpy-bms/COCRDLI.CPY:L61-L66` for the symbolic input field group (ACCTSIDL, ACCTSIDF, ACCTSIDA, ACCTSIDI).
- `CARDSID` PIC X(16) at BMS position (7,44) (row 7 column 44) -- 16-digit card number filter (optional) -- cite `app/bms/COCRDLI.bms:L101-L105` for the BMS DFHMDF definition and `app/cpy-bms/COCRDLI.CPY:L67-L72` for the symbolic input field group (CARDSIDL, CARDSIDF, CARDSIDA, CARDSIDI).
- `CRDSEL1` through `CRDSEL7` PIC X(1) at BMS positions (11,12) through (17,12) (rows 11-17 column 12) -- single-character row selector accepting `'S'`, `'U'`, or space; one per displayed card row -- cite `app/bms/COCRDLI.bms:L140-L144` (CRDSEL1) as the representative example and `app/cpy-bms/COCRDLI.CPY:L73-L78` (CRDSEL1I) for the symbolic input field group.
- **Validation rules** (cited from `app/cbl/COCRDLIC.cbl`): per paragraph `2210-EDIT-ACCOUNT` (L1003-L1034) and `2220-EDIT-CARD` (L1036-L1064), filters must be either blank/LOW-VALUES/ZEROS or fully numeric in their declared digit width. Otherwise the verbatim error messages at L1022 (`ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`) and L1058 (`CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`) fire. Both messages contain a LITERAL COMMA WITHOUT SPACE between `FILTER` and `IF SUPPLIED` -- this is verbatim byte preservation per AAP Sec 0.7.1.
- **First-message-wins discipline**: when BOTH filters are non-numeric, only L1022 is displayed because L1058's MOVE is gated by `IF WS-ERROR-MSG-OFF` at L1056 (first-message-wins).

### 2.2 CICS Dispatch Context

```cobol
*  COCRDLIC.cbl L315-L343 -- first-time-entry vs reentry dispatch
   IF EIBCALEN = 0
      INITIALIZE CARDDEMO-COMMAREA
                 WS-THIS-PROGCOMMAREA
      MOVE LIT-THISTRANID        TO CDEMO-FROM-TRANID
      MOVE LIT-THISPGM           TO CDEMO-FROM-PROGRAM
      SET CDEMO-USRTYP-USER      TO TRUE
      SET CDEMO-PGM-ENTER        TO TRUE
      MOVE LIT-THISMAP           TO CDEMO-LAST-MAP
      MOVE LIT-THISMAPSET        TO CDEMO-LAST-MAPSET
      SET CA-FIRST-PAGE          TO TRUE
      SET CA-LAST-PAGE-NOT-SHOWN TO TRUE
   ELSE
      MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO
                        CARDDEMO-COMMAREA
      MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
                       LENGTH OF WS-THIS-PROGCOMMAREA )TO
                        WS-THIS-PROGCOMMAREA
   END-IF
```

- `EIBCALEN = 0` indicates first-time entry from a CICS START command or terminal-initiated transaction; commarea is initialized fresh.
- `EIBCALEN > 0` indicates pseudo-conversational reentry; the prior 2000-byte WS-COMMAREA is split into the leading CARDDEMO-COMMAREA (200 bytes) and trailing WS-THIS-PROGCOMMAREA (browse cursor state).
- Auto-return-from-detail-program detection: a follow-on `IF` test compares `CDEMO-FROM-PROGRAM` to `LIT-CARDDTLPGM` (`'COCRDSLC'`) or `LIT-CARDUPDPGM` (`'COCRDUPC'`) to detect returning from card detail/update; when detected, the browse cursor state is preserved across the round trip.
- `CardDemoCommarea` (`app/cpy/COCOM01Y.cpy`, translated to `com.blitzy.carddemo.domain.commarea.CardDemoCommarea`) carries the following fields used by COCRDLIC:
    - `CDEMO-FROM-TRANID` (PIC X(04)) -- transid of the calling program
    - `CDEMO-FROM-PROGRAM` (PIC X(08)) -- program-id of the calling program (e.g., `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COMEN01C`)
    - `CDEMO-USER-ID` (PIC X(08)) -- signed-on user id
    - `CDEMO-USER-TYPE` (PIC X(01)) -- 'A' for Admin, 'U' for User (sealed UserType per AAP Sec 0.6.3)
    - `CDEMO-PGM-CONTEXT` (PIC 9(01)) -- 0 for Enter, 1 for Reenter (sealed PgmContext per AAP Sec 0.6.3)

### 2.3 STARTBR/READNEXT/READPREV/ENDBR Browse Flow

```cobol
*  COCRDLIC.cbl L1129-L1136 -- STARTBR in 9000-READ-FORWARD
   EXEC CICS STARTBR
        DATASET(LIT-CARD-FILE)
        RIDFLD(WS-CARD-RID-CARDNUM)
        KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
        GTEQ
        RESP(WS-RESP-CD)
        RESP2(WS-REAS-CD)
   END-EXEC
```

- NO `UPDATE` clause on STARTBR, READNEXT, or READPREV anywhere in COCRDLIC -- this is a READ-ONLY browse (per AAP Sec 0.7.1 minimal change discipline). The Java port MUST preserve READ-ONLY semantics; the FileCardRepository adapter MUST not invoke any write operation during COCRDLIC execution.
- 7 records fetched per page (`WS-MAX-SCREEN-LINES VALUE 7` at COCRDLIC.cbl L177-L178); pagination height is exactly 7 rows.
- **One-extra-READNEXT probe pattern** (COCRDLIC.cbl L1197-L1231 within 9000-READ-FORWARD): after the 7-record fetch, an additional READNEXT is performed to detect whether a next page exists. If RESP=NORMAL or DUPREC the "next page exists" indicator `CA-NEXT-PAGE-EXISTS` is set and `WS-CA-LAST-CARD-NUM` is updated to the probed key. If RESP=ENDFILE the "no more pages" state `CA-NEXT-PAGE-NOT-EXISTS` is set.
- **9500-FILTER-RECORDS** (COCRDLIC.cbl L1382-L1409) applies the ACCTSID/CARDSID predicate to each fetched record: if filter is set, CARD-ACCT-ID must equal the 11-digit filter AND CARD-NUM must equal the 16-digit filter. Records not matching are excluded via WS-EXCLUDE-THIS-RECORD; the row counter does not advance for non-matching records, so the loop continues to the next READNEXT.
- **9100-READ-BACKWARDS** (COCRDLIC.cbl L1264+) is the mirror of 9000-READ-FORWARD using READPREV for PF7 (backward pagination).
- **ENDBR**: every browse path ends with `EXEC CICS ENDBR FILE(LIT-CARD-FILE)` (COCRDLIC.cbl L1258-L1259 in 9000-READ-FORWARD; symmetric ENDBR in 9100-READ-BACKWARDS) to release the browse cursor in accordance with CICS pseudo-conversational discipline.

### 2.4 Fixture Table

| Fixture Path | Role | Read By |
|--------------|------|---------|
| `app/data/ASCII/carddata.txt` | Primary CARDDAT KSDS image | `FileCardRepository` (via classpath) |
| `app/data/ASCII/cardxref.txt` | CARDXREF cross-reference | `FileCardXrefRepository` (optional/auxiliary for COCRDLIC) |
| `app/data/ASCII/acctdata.txt` | ACCTDATA reference (filter validation auxiliary) | `FileAccountRepository` (auxiliary) |

All three fixtures are pre-transcoded ASCII (no EBCDIC at this site); production paths support EBCDIC IBM-1047 per AAP Sec 0.6.5.

### 2.5 Required Test Scenarios Enumeration

The sibling `../expected/input_scenario.txt` synthesizes EXACTLY 14 scenarios that exercise the COCRDLIC dispatch and validation paths. The 14 binding scenarios consolidate the conceptual scenarios enumerated below; descendant test agents MUST keep the 14-scenario count in `../expected/input_scenario.txt` and the enumerated cases here in mutual sync.

1. **First-time entry (EIBCALEN=0), empty filters**: tests COCRDLIC.cbl L315-L325 (EIBCALEN=0 initialization path) -- displays first 7 cards from CARDDAT in default browse order.
2. **Both filters blank, ENTER pressed on reentry**: tests COCRDLIC.cbl L326-L332 (EIBCALEN>0 reentry) -- default browse continues.
3. **Valid ACCTSID filter (11 digits)**: tests 2210-EDIT-ACCOUNT success path (L1003-L1034) -- displays first 7 matching cards.
4. **Valid CARDSID filter (16 digits)**: tests 2220-EDIT-CARD success path (L1036-L1064) -- displays the one matching card (primary-key match).
5. **Both filters supplied (account AND card)**: tests 9500-FILTER-RECORDS AND-combination predicate (L1382-L1409).
6. **PF8 forward pagination**: tests L486-L497 (CCARD-AID-PFK08 + CA-NEXT-PAGE-EXISTS) -- advances to next 7 records.
7. **PF7 backward pagination from page 2**: tests L501-L513 (CCARD-AID-PFK07 + NOT CA-FIRST-PAGE) -- returns to page 1.
8. **PF7 at first page**: tests L901-L904 -- triggers `NO PREVIOUS PAGES TO DISPLAY` info message (L903, 28 chars).
9. **PF8 at last page**: tests L905-L909 -- triggers `NO MORE PAGES TO DISPLAY` info message (L908, 24 chars).
10. **Non-numeric ACCTSID**: tests L1017-L1029 -- triggers `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER` (L1022, 52 chars, COMMA WITHOUT SPACE).
11. **Non-numeric CARDSID**: tests L1052-L1064 -- triggers `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER` (L1058, 52 chars, COMMA WITHOUT SPACE).
12. **Filter matches nothing**: tests L121-L122 -- triggers `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` (L122, 43 chars, INCLUDES trailing period).
13. **CRDSEL1='S' with valid row data**: tests L517-L541 (CCARD-AID-ENTER + VIEW-REQUESTED-ON) -- populates CDEMO-CARD-NUM and XCTLs to LIT-CARDDTLPGM (`'COCRDSLC'`).
14. **CRDSEL3='U' with valid row data**: tests L545-L569 (CCARD-AID-ENTER + UPDATE-REQUESTED-ON) -- populates CDEMO-CARD-NUM and XCTLs to LIT-CARDUPDPGM (`'COCRDUPC'`).

Additional conceptual edge cases NOT directly enumerated as separate `input_scenario.txt` scenarios but exercised by the verbatim message catalog include: multiple CRDSEL values set (triggers L124 `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE`, 47 chars), invalid CRDSEL value (triggers L126 `INVALID ACTION CODE`, 19 chars), PF3 from any state (triggers L120 `PF03 PRESSED.EXITING`, 20 chars, and XCTLs to LIT-MENUPGM `'COMEN01C'`), and browse exhaustion mid-page (triggers L1219 or L1239 `NO MORE RECORDS TO SHOW`, 23 chars). These auxiliary cases are documented for completeness in `../expected/README.md` Supplementary 1 verbatim message catalog.

## 3. Cross-Reference to Java Test Class

- Test class FQCN: `com.blitzy.carddemo.tests.golden.CoCrdLiCGoldenTest`
- Extends: `com.blitzy.carddemo.tests.golden.GoldenRecordTest` (the base class per AAP Sec 0.6.11)
- `programClass()` returns `com.blitzy.carddemo.application.card.CoCrdLiC.class`
- `inputFile()` returns `Path.of("app/data/ASCII/carddata.txt")` (resolved via classpath relative path; NOT copied to the Java tree)
- `expectedOutputFile()` returns the captured BMS output path under `expected/bms_output.txt`
- `auxiliaryInputs()` returns the list `[Path.of("app/data/ASCII/cardxref.txt"), Path.of("app/data/ASCII/acctdata.txt")]` (if utilized by the filter validation path)
- `expectedOutputs()` returns the multi-file list `[Path.of("expected/stdout.txt"), Path.of("expected/bms_output.txt")]`
- Test methods initially marked `@Disabled("Pending COBOL baseline capture -- see MIGRATION_NOTES.md Sec 1.6")` until baselines are committed (AAP Sec 0.6.11)
- Fixed clock injection via `ScopedValue<Clock>` at test runtime makes CURDATEO and CURTIMEO deterministic (per AAP Sec 0.6.6)

## 4. Capture Procedure Cross-Reference

The procedure to capture the authoritative COCRDLIC golden-record baseline outputs from the COBOL program is documented in `java/MIGRATION_NOTES.md` Section 1.6 per AAP Sec 0.7.5 user TODO resolution. THIS file does NOT duplicate the procedure; it references the canonical source. Until baseline captures are committed, the `CoCrdLiCGoldenTest` Java test class MUST remain `@Disabled` per AAP Sec 0.6.11 and the placeholder `../expected/stdout.txt` and `../expected/bms_output.txt` files document the format and expected content of the captured outputs. Descendant agents replacing the placeholders MUST regenerate captures using the exact procedure in `java/MIGRATION_NOTES.md` Section 1.6 to guarantee byte-for-byte parity between Java output and the COBOL baseline.

## 5. Behavioral Invariants

- Byte-for-byte equality of the 1920-byte (24x80) BMS frame dump per AAP Sec 0.6.5; the Java test uses `Files.readAllBytes(Path)` from `java.nio.file` and `Arrays.equals(byte[], byte[])` for comparison.
- ASCII fixtures are read via classpath from `app/data/ASCII/`; they are NEVER copied into the Java tree (AAP Sec 0.4.1).
- PAN masking: each rendered `CRDNUM<N>O` field in any captured output displays only the last 4 digits in clear, with the first 12 digits masked to `'*'` (12 asterisks); applies to both captured `bms_output.txt` and any captured stdout, and to any test scaffolding logs (AAP Sec 0.7.2).
- All 11 verbatim COCRDLIC messages (L116, L120, L122, L124, L126, L903, L908, L1022, L1058, L1219, L1239) MUST be preserved byte-for-byte: no whitespace normalization, no comma-space insertion, no message reflow, no Unicode substitution (AAP Sec 0.7.1).
- The `java.time` clock used by the Java port is fixed to a deterministic value (e.g., `Instant.parse("2024-01-15T10:00:00Z")` at UTC) via `ScopedValue<Clock>` injection per AAP Sec 0.6.6; CURDATEO and CURTIMEO BMS output fields are therefore deterministic.
- `java.nio.file` is used exclusively for all file I/O in the Java port and test scaffolding (no `java.io.File`) per AAP Sec 0.6.5.
- No `ThreadLocal` is used anywhere in test scaffolding or the Java port; `ScopedValue` is used per AAP Sec 0.6.6.
- No Spring, Spring Boot, Hibernate, JPA, or PostgreSQL is introduced anywhere per AAP Sec 0.6.12 (architectural override of the previously documented Spring Boot target).
- No `--enable-preview`; no JEP 502 (Stable Values, preview), no JEP 505 (Structured Concurrency, preview), no JEP 507 (Primitive Patterns, preview); no `default` branches in pattern-matching switches (sealed exhaustiveness is the safety guarantee) per AAP Sec 0.7.4.
- No card PAN value is stored anywhere in this folder; this folder is documentation-only and contains zero fixture bytes.
- Pagination order is exactly the CARDDAT KSDS browse order; the Java implementation MUST NOT reorder records or apply virtual-thread fan-out within pagination (per AAP Sec 0.1.3: virtual-thread fan-out only where COBOL was serial but per-record work is independent; pagination IS sequential by primary key).
- Reflexivity invariant: for every byte buffer `b` from `app/data/ASCII/carddata.txt` representing a valid CARD-RECORD, `CardRecord.parse(b).encode()` equals `b` byte-for-byte (per AAP Sec 0.6.5).

## 6. Source Lineage

| Source Path | Role | Status |
|-------------|------|--------|
| `app/cbl/COCRDLIC.cbl` | COBOL source (1459 lines; PROGRAM-ID at L26-L27) | UNCHANGED reference |
| `app/bms/COCRDLI.bms` | BMS map definition (344 lines, mapset `COCRDLI`, map `CCRDLIA`) | UNCHANGED reference |
| `app/cpy-bms/COCRDLI.CPY` | Symbolic input/output map copybook (560 lines, CCRDLIAI/CCRDLIAO) | UNCHANGED reference |
| `app/cpy/CVACT02Y.cpy` | CARD-RECORD layout (150 bytes, 14 fields) | UNCHANGED reference |
| `app/cpy/CVACT03Y.cpy` | CARD-XREF layout (50 bytes) | UNCHANGED reference |
| `app/cpy/COCOM01Y.cpy` | CARDDEMO-COMMAREA base | UNCHANGED reference |
| `app/cpy/CVCRD01Y.cpy` | CC-WORK-AREAS (AID-key 88-levels, sealed REDEFINES sources) | UNCHANGED reference |
| `app/cpy/CSSTRPFY.cpy` | YYYY-STORE-PFKEY procedure template (PF key decode) | UNCHANGED reference |
| `app/cpy/CSMSG01Y.cpy` | Common message constants | UNCHANGED reference |
| `app/data/ASCII/carddata.txt` | Primary CARDDAT fixture | REFERENCE (read via classpath, NOT copied) |
| `app/data/ASCII/cardxref.txt` | CARDXREF cross-reference fixture | REFERENCE (optional/auxiliary) |
| `app/data/ASCII/acctdata.txt` | ACCTDATA reference fixture | REFERENCE (auxiliary) |

## 7. Authority References

- AAP Sec 0.1.1 -- `app/` tree is immutable
- AAP Sec 0.2.1 -- Golden directory tree in scope (CREATE)
- AAP Sec 0.2.2 -- `app/` tree explicitly out of scope for modification
- AAP Sec 0.3.1 -- Hexagonal architecture / `@CobolProgram` annotation
- AAP Sec 0.4.1 -- Java class naming; ASCII fixtures read via classpath (REFERENCE, NOT COPIED)
- AAP Sec 0.6.4 -- `java.time` mandate (no `java.util.Date`/`Calendar`)
- AAP Sec 0.6.5 -- `java.nio.file` mandate; byte-for-byte file fidelity
- AAP Sec 0.6.6 -- Batch throughput: `ScopedValue` replaces `ThreadLocal`
- AAP Sec 0.6.11 -- Golden-record PR gate; `@Disabled` until capture committed
- AAP Sec 0.6.12 -- Architectural override: no Spring, no PostgreSQL, no Hibernate
- AAP Sec 0.7.1 -- Minimal Change Clause; verbatim COBOL message byte preservation
- AAP Sec 0.7.2 -- PAN masking (last 4 digits visible only)
- AAP Sec 0.7.4 -- No JEP preview features; no `default` branches in pattern switches
- AAP Sec 0.7.5 -- Capture procedure cross-reference in `java/MIGRATION_NOTES.md`

## 8. DO NOT Add Files Here

```text
DO NOT add any of the following files to this folder:
  - Any *.txt fixture data file
  - Any *.bin binary file
  - Any copy of app/data/ASCII/*.txt (per AAP Sec 0.4.1: ASCII fixtures
    live ONLY at app/data/ASCII/ and are read via classpath; they are
    NEVER copied into the Java tree)
  - Any captured COBOL output (those belong in ../expected/)
  - Any input_scenario.txt (that belongs in ../expected/)
  - Any Java source, test, or scaffolding file
  - Any unmasked PAN value, any plaintext password, any PII

The ONLY file permitted in this folder is this README.md.
```

## 9. Coordination With Sibling Folder

- `../expected/README.md` is the authoritative source-of-truth on COCRDLIC per-program behavior; THIS `input/README.md` is a navigational pointer.
- Scenario enumerations in Section 2.5 of THIS file MUST match the 14-scenario set defined in `../expected/input_scenario.txt`.
- All AAP citations and source line-number citations MUST be consistent across `input/README.md` (this file), `../expected/README.md`, `../expected/input_scenario.txt`, `../expected/stdout.txt`, and `../expected/bms_output.txt`.
- All verbatim COCRDLIC message texts MUST be byte-identical across both folders -- particularly the LITERAL COMMA WITHOUT SPACE in L1022 (`ACCOUNT FILTER,IF SUPPLIED`) and L1058 (`CARD ID FILTER,IF SUPPLIED`), and the TRAILING PERIOD in L122 (`NO RECORDS FOUND FOR THIS SEARCH CONDITION.`).
