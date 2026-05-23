# COCRDLIC (Card List Pagination, CCLI) -- Golden-Record `expected/` Fixture Contract

## Phase 0 -- Header and Authority

This README is the source-of-truth for COCRDLIC behavioral invariants. It binds the descendant `CoCrdLiCGoldenTest` Java test class to a specific byte-for-byte contract. Every claim below is grounded in a line-level citation against the immutable COBOL source tree under `app/`.

Authority cascade (binding). AAP Sec 0.4.1 fixes the one-class-per-program rule and the file inventory in scope for CREATE. AAP Sec 0.6.11 mandates byte-for-byte golden-record parity as the non-negotiable PR gate and authorizes the `@Disabled` annotation on the descendant Java test class until captured artifacts are committed. AAP Sec 0.7.1 (the Minimal Change Clause) governs faithful translation including the preservation of every observable quirk and the verbatim COBOL message catalog. AAP Sec 0.7.2 mandates PAN masking (last 4 digits visible only) in any captured surface. AAP Sec 0.6.4 mandates `java.time` for all date and time values. AAP Sec 0.6.5 mandates `java.nio.file` for all file I/O and the byte-for-byte fidelity contract enforced by `Arrays.equals(byte[],byte[])`. AAP Sec 0.6.6 mandates `ScopedValue` for cross-method context propagation. AAP Sec 0.6.12 records the architectural override against any Spring, PostgreSQL, Hibernate, JPA, or container-based target proposed by historical drafts of `docs/technical-specifications.md`.

Program identification. COBOL `PROGRAM-ID. COCRDLIC` at `app/cbl/COCRDLIC.cbl:L26-L27`. CICS transaction `CCLI` via `LIT-THISTRANID PIC X(4) VALUE 'CCLI'` at `app/cbl/COCRDLIC.cbl:L181-L182`. BMS mapset `COCRDLI` via `LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'` at `app/cbl/COCRDLIC.cbl:L183-L184`. BMS map `CCRDLIA` via `LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'` at `app/cbl/COCRDLIC.cbl:L185-L186`. VSAM file `CARDDAT` via `LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '` at `app/cbl/COCRDLIC.cbl:L213-L214` (READ-only access; never opened for UPDATE, WRITE, REWRITE, or DELETE). VSAM AIX `CARDAIX` via `LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '` at `app/cbl/COCRDLIC.cbl:L215-L217`. Source file total: 1459 lines. Function: list paginated credit cards from a CICS pseudo-conversational session with optional ACCTSID and CARDSID filters.

Java translation target. The COCRDLIC program maps to `com.blitzy.carddemo.application.card.CoCrdLiC` per AAP Sec 0.4.1 (card subpackage of the application module). The companion BMS DTOs are `com.blitzy.carddemo.application.card.CoCrdLiInput` (the input record reflecting the CCRDLIAI symbolic copybook) and `com.blitzy.carddemo.application.card.CoCrdLiOutput` (the output record reflecting the CCRDLIAO output overlay). The class carries a `@CobolProgram("COCRDLIC")` Javadoc-style annotation citing the original PROGRAM-ID, the source path `app/cbl/COCRDLIC.cbl`, and the translation date per AAP Sec 0.7.1.

Java test class. `com.blitzy.carddemo.tests.golden.CoCrdLiCGoldenTest` extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class MUST carry `@Disabled("Pending COBOL baseline capture -- see MIGRATION_NOTES.md Sec 1.6")` per AAP Sec 0.6.11 until captured artifacts replace the placeholders in this directory.

File inventory bound by this README. This directory contains exactly 4 files: `README.md` (this document, the authoritative contract), `input_scenario.txt` (the test-owned 14-scenario CICS pseudo-conversation driver), `stdout.txt` (capture placeholder, expected EMPTY because COCRDLIC issues no DISPLAY statements), and `bms_output.txt` (capture placeholder for serialized BMS SEND MAP frames). Source fixture files `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt`, and `app/data/ASCII/acctdata.txt` are consumed REFERENCE-only via classpath relative path per AAP Sec 0.4.1; they are never copied into this folder.

ASCII-only discipline. This file contains only ASCII codepoints `0x20-0x7E` plus the line-feed `0x0A`. The two-hyphen sequence `--` substitutes for em dash and en dash; straight quotes substitute for curly quotes; three periods substitute for the Unicode ellipsis; the literal letters `Sec` substitute for the section sign; `->` substitutes for the Unicode arrow. No HTML tags, no emoji, no YAML front-matter. Lists use `-` bullets only.

## Phase 1 -- PROGRAM-ID and WS-VARIABLES

The COBOL source file is 1459 lines. The IDENTIFICATION DIVISION begins at L25; the PROGRAM-ID declaration appears at L26-L27. The WORKING-STORAGE SECTION (L38 through L291) declares the program's state variables, the literal constants in `WS-CONSTANTS` (L176-L217), the COPY directives that resolve to shared record layouts and constant catalogues (L221-L290), and the LINKAGE SECTION DFHCOMMAREA at L292-L295.

The `WS-CONSTANTS` 01-level group at L176 contains the program's literal constants. The block below reproduces the verbatim COBOL source, preserving the COBOL `PIC X(n)` allocation and the quoted VALUE clause:

```cobol
       01 WS-CONSTANTS.
         05  WS-MAX-SCREEN-LINES                    PIC S9(4) COMP
                                                    VALUE 7.
         05  LIT-THISPGM                            PIC X(8)
             VALUE 'COCRDLIC'.
         05  LIT-THISTRANID                         PIC X(4)
             VALUE 'CCLI'.
         05  LIT-THISMAPSET                         PIC X(7)
             VALUE 'COCRDLI'.
         05  LIT-THISMAP                            PIC X(7)
             VALUE 'CCRDLIA'.
         05  LIT-MENUPGM                            PIC X(8)
             VALUE 'COMEN01C'.
         05  LIT-CARDDTLPGM                         PIC X(8)
             VALUE 'COCRDSLC'.
         05  LIT-CARDUPDPGM                         PIC X(8)
             VALUE 'COCRDUPC'.
         05  LIT-CARD-FILE                          PIC X(8)
                                                   VALUE 'CARDDAT '.
         05  LIT-CARD-FILE-ACCT-PATH                PIC X(8)
                                                   VALUE 'CARDAIX '.
```

The constant `WS-MAX-SCREEN-LINES VALUE 7` at L177-L178 fixes the pagination height at exactly 7 rows per page; this value is used by 9000-READ-FORWARD and 9100-READ-BACKWARDS to control the READNEXT/READPREV loop boundary and by 1200-SCREEN-ARRAY-INIT to drive the row population loop. The descendant Java test class MUST observe this 7-row constraint when constructing input fixtures and expected output frames.

The serialization buffer `WS-COMMAREA PIC X(2000)` at L262 is the 2000-byte payload that holds the concatenation of `CARDDEMO-COMMAREA` followed by `WS-THIS-PROGCOMMAREA`, used as the `COMMAREA` operand on `EXEC CICS RETURN` (see Phase 7).

The two message buffer fields drive the BMS map's two message rows: `WS-INFO-MSG PIC X(45)` at L112 is the 45-byte informational message buffer with 88-level conditions `WS-NO-INFO-MESSAGE` (L113-L114, blank/low-values) and `WS-INFORM-REC-ACTIONS` (L115-L116, the default action prompt). `WS-ERROR-MSG PIC X(75)` at L117 is the 75-byte error message buffer with 88-level conditions `WS-ERROR-MSG-OFF` (L118, spaces), `WS-EXIT-MESSAGE` (L119-L120, the PF3 exit message), `WS-NO-RECORDS-FOUND` (L121-L122, the empty-result message including its trailing period), `WS-MORE-THAN-1-ACTION` (L123-L124, the multi-select error), and `WS-INVALID-ACTION-CODE` (L125-L126, the invalid-action-code error). Although the buffer is 75 bytes wide, the BMS map's ERRMSG field is 78 bytes wide; the 3-byte difference is right-padded with spaces by the COBOL implicit MOVE truncation rule.

The browse-state file-handling area is at L137-L171. `WS-CARD-RID` at L137 carries the composite key for STARTBR positioning, with sub-fields `WS-CARD-RID-CARDNUM PIC X(16)` (L138) and `WS-CARD-RID-ACCT-ID PIC 9(11)` (L139). The 7-element file data array `WS-SCREEN-DATA` at L252 occupies 196 bytes (28 bytes per row times 7 rows) and is REDEFINEd as `WS-SCREEN-ROWS OCCURS 7 TIMES` (L255-L260) with sub-fields `WS-ROW-ACCTNO PIC X(11)`, `WS-ROW-CARD-NUM PIC X(16)`, and `WS-ROW-CARD-STATUS PIC X(1)`. The COBOL alias `WS-EACH-CARD` (L257) is the per-row group that, when set to LOW-VALUES, signals an unpopulated slot to 1200-SCREEN-ARRAY-INIT.

## Phase 2 -- Commarea Layout

The LINKAGE SECTION at `app/cbl/COCRDLIC.cbl:L292-L295` declares an opaque `DFHCOMMAREA` with FILLER OCCURS 1 TO 32767 TIMES DEPENDING ON `EIBCALEN`. The PROCEDURE DIVISION reslices this opaque payload into two complementary structures using the COBOL `MOVE ... (offset:length)` reference modification at L327-L331:

1. `CARDDEMO-COMMAREA` from `COPY COCOM01Y.` at `app/cbl/COCRDLIC.cbl:L227`, defined in `app/cpy/COCOM01Y.cpy`. This is the standard communication area shared across the whole Card Demo program family. It contains nested groups: `CDEMO-GENERAL-INFO` (transaction routing, user identity, pgm-context including the 88-level taxonomies `CDEMO-USRTYP-USER`/`CDEMO-USRTYP-ADMIN` and `CDEMO-PGM-ENTER`/`CDEMO-PGM-REENTER`), `CDEMO-CUSTOMER-INFO`, `CDEMO-ACCOUNT-INFO`, `CDEMO-CARD-INFO`, and `CDEMO-MORE-INFO` (CDEMO-LAST-MAP, CDEMO-LAST-MAPSET).
2. `WS-THIS-PROGCOMMAREA` declared INLINE at `app/cbl/COCRDLIC.cbl:L229-L248` (not in any copybook). It carries the COCRDLIC-specific browse cursor state across pseudo-conversational interactions.

The COCRDLIC-specific extension `WS-THIS-PROGCOMMAREA` carries these fields:

- `WS-CA-LAST-CARDKEY` at L230 -- composite key for the LAST card on the current page, with `WS-CA-LAST-CARD-NUM PIC X(16)` at L231 and `WS-CA-LAST-CARD-ACCT-ID PIC 9(11)` at L232 (used by PF8 to position STARTBR for the next page).
- `WS-CA-FIRST-CARDKEY` at L233 -- composite key for the FIRST card on the current page, with `WS-CA-FIRST-CARD-NUM PIC X(16)` at L234 and `WS-CA-FIRST-CARD-ACCT-ID PIC 9(11)` at L235 (used by PF7 to position STARTBR for the previous page and by self-reentry to refresh the current page).
- `WS-CA-SCREEN-NUM PIC 9(1)` at L237 -- current page number (1-9), with 88-level condition `CA-FIRST-PAGE VALUE 1` at L238.
- `WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)` at L239 -- sticky flag for last-page-already-shown discipline, with 88-level conditions `CA-LAST-PAGE-SHOWN VALUE 0` at L240 and `CA-LAST-PAGE-NOT-SHOWN VALUE 9` at L241.
- `WS-CA-NEXT-PAGE-IND PIC X(1)` at L242 -- next-page existence indicator, with 88-level conditions `CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES` at L243 and `CA-NEXT-PAGE-EXISTS VALUE 'Y'` at L244.

The total `WS-COMMAREA` is exactly 2000 bytes (declared at `app/cbl/COCRDLIC.cbl:L262`). The first portion holds `CARDDEMO-COMMAREA`; the remainder, starting at byte `LENGTH OF CARDDEMO-COMMAREA + 1`, holds `WS-THIS-PROGCOMMAREA`. The Java translation MUST preserve this exact serialization order so the round-trip commarea passed across `EXEC CICS RETURN` ... `EXEC CICS XCTL` boundaries decodes identically on the next invocation.

## Phase 3 -- 0000-MAIN Dispatch

The PROCEDURE DIVISION begins at `app/cbl/COCRDLIC.cbl:L297` with paragraph `0000-MAIN` at L298. The dispatch logic comprises three phases: first-time entry detection at L315-L325, self-reentry detection at L326-L332, and auto-return from a child program at L336-L343, followed by the EVALUATE TRUE dispatch table at L418-L583.

### 3.1 First-Time Entry

When `EIBCALEN = 0` (L315), COCRDLIC executes the first-time-entry initialization block at L316-L325:

- `INITIALIZE CARDDEMO-COMMAREA` and `INITIALIZE WS-THIS-PROGCOMMAREA` (L316-L317) zero out both commarea structures.
- `MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID` (L318) sets the commarea's from-tranid to `'CCLI'`.
- `MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM` (L319) sets the from-program to `'COCRDLIC'`.
- `SET CDEMO-USRTYP-USER TO TRUE` (L320) marks the session as a normal-user session (sealed UserType permit User in the Java port).
- `SET CDEMO-PGM-ENTER TO TRUE` (L321) marks pgm-context as ENTER (sealed PgmContext permit Enter in the Java port).
- `MOVE LIT-THISMAP TO CDEMO-LAST-MAP` (L322) and `MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET` (L323) seed the commarea's last-map fields.
- `SET CA-FIRST-PAGE TO TRUE` (L324) marks the current page as page 1.
- `SET CA-LAST-PAGE-NOT-SHOWN TO TRUE` (L325) clears the last-page-already-shown sticky flag.

Following initialization, control flows through the dispatch EVALUATE TRUE at L418 where, with `INPUT-OK` (default), `CCARD-AID-ENTER` (default after the PFK remap at L378-L380), and no row selection, the WHEN OTHER fall-through at L572-L582 fires. WHEN OTHER moves LOW-VALUES into `WS-CARD-RID-CARDNUM` (via `WS-CA-FIRST-CARD-NUM` which is LOW-VALUES on first entry), performs `9000-READ-FORWARD` to browse from the lowest key, performs `1000-SEND-MAP` to render the screen, and goes to `COMMON-RETURN`.

### 3.2 Reentry from COCRDLIC (Self-Reentry)

When `EIBCALEN > 0 AND CDEMO-FROM-PROGRAM EQUAL LIT-THISPGM` (L357-L358), COCRDLIC executes `2000-RECEIVE-MAP` (L359-L360) which in turn performs `2100-RECEIVE-SCREEN` (CICS RECEIVE MAP at L952-L953) then `2200-EDIT-INPUTS` (the validation pipeline at L955-L956). After validation completes, control flows through the AID-key remap at L370-L380 (defaulting invalid PFKs to ENTER) and into the dispatch EVALUATE TRUE at L418.

If validation produced `INPUT-ERROR` (L419), the EVALUATE WHEN branch at L419-L438 fires: it copies `WS-ERROR-MSG` into `CCARD-ERROR-MSG` (the commarea error field at L423), restores the program-id and map metadata (L424-L430), then -- only if neither filter is in NOT-OK state -- performs `9000-READ-FORWARD` (L431-L435) to refresh the current page before rendering. This is the path that produces the L1022 or L1058 verbatim error messages (see Phase 4.1).

If validation passed and a row selector is set, the WHEN branches at L517-L541 (view, selector = 'S') or L545-L569 (update, selector = 'U') fire; these branches XCTL to COCRDSLC or COCRDUPC respectively (see Phase 4.3).

### 3.3 Auto-Return From COCRDSLC/COCRDUPC

When `EIBCALEN > 0 AND CDEMO-FROM-PROGRAM NOT EQUAL LIT-THISPGM AND CDEMO-PGM-ENTER` (L336-L337), COCRDLIC executes the auto-return refresh block at L336-L343:

- `INITIALIZE WS-THIS-PROGCOMMAREA` (L338) clears the local browse state because the caller may have advanced data.
- `SET CDEMO-PGM-ENTER TO TRUE` (L339) preserves the ENTER pgm-context.
- `MOVE LIT-THISMAP TO CDEMO-LAST-MAP` (L340) re-seeds the last-map metadata.
- `SET CA-FIRST-PAGE TO TRUE` (L341) returns to page 1 on auto-return.
- `SET CA-LAST-PAGE-NOT-SHOWN TO TRUE` (L342) clears the sticky last-page flag.

Following the auto-return refresh, the dispatch EVALUATE TRUE at L418 with `INPUT-OK` and `CCARD-AID-ENTER` defaults to the WHEN OTHER catch-all at L572-L582 which performs `9000-READ-FORWARD` from `WS-CA-FIRST-CARD-NUM` (now LOW-VALUES after the INITIALIZE at L338) and `1000-SEND-MAP`.

The full EIBAID dispatch table is:

```text
EIBAID Dispatch Table (COCRDLIC.cbl L418-L583 EVALUATE TRUE):

  INPUT-ERROR                       -> L419-L438: re-send map with error message
  CCARD-AID-PFK07 + CA-FIRST-PAGE   -> L444-L454: refresh first page (L903 message
                                       via 1400-SETUP-MESSAGE at L901-L904)
  CCARD-AID-PFK03 (PF3) + self      -> L384-L406 XCTL LIT-MENUPGM ('COMEN01C')
                                       with WS-EXIT-MESSAGE 'PF03 PRESSED.EXITING'
  CDEMO-PGM-REENTER + foreign caller-> L458-L482: initialize commarea then refresh
  CCARD-AID-PFK08 + CA-NEXT-EXISTS  -> L486-L497: page forward from
                                       WS-CA-LAST-CARD-NUM; increment screen num
  CCARD-AID-PFK07 + NOT CA-FIRST    -> L501-L513: page backward from
                                       WS-CA-FIRST-CARD-NUM; decrement screen num
  CCARD-AID-ENTER + VIEW-REQ + self -> L517-L541: XCTL COCRDSLC (view selection)
  CCARD-AID-ENTER + UPDATE-REQ+self -> L545-L569: XCTL COCRDUPC (update selection)
  WHEN OTHER                        -> L572-L582: refresh first page from
                                       WS-CA-FIRST-CARD-NUM (catch-all)
```

The Java port translates this EVALUATE TRUE into a pattern-matching switch on the `AidKey` sealed hierarchy (Enter, Clear, Pa1, Pa2, PfKey01-PfKey12) from `app/cpy/CVCRD01Y.cpy` 88-levels. Per AAP Sec 0.7.4, the Java switch MUST NOT include a `default` branch -- it MUST list every permit explicitly so exhaustiveness checking detects missing cases at compile time.

## Phase 4 -- 2000-PROCESS-INPUTS

The 2000-RECEIVE-MAP paragraph at L951-L957 invokes 2100-RECEIVE-SCREEN (the CICS RECEIVE MAP at L952-L953) followed by 2200-EDIT-INPUTS (the validation chain at L955-L956). The validation chain performs three sub-paragraphs in order: 2210-EDIT-ACCOUNT (L1003-L1034), 2220-EDIT-CARD (L1036-L1071), and 2250-EDIT-ARRAY (L1073 onward). The order matters because the first-message-wins discipline (Phase 4.1) gates L1058 behind a `WS-ERROR-MSG-OFF` check.

### 4.1 Filter Validation

The `2210-EDIT-ACCOUNT` paragraph at L1003-L1034 validates the ACCTSID filter `CC-ACCT-ID PIC X(11)` (from `CVCRD01Y` copybook). The branches are:

- L1007-L1013: if `CC-ACCT-ID EQUAL LOW-VALUES` OR `CC-ACCT-ID EQUAL SPACES` OR `CC-ACCT-ID-N EQUAL ZEROS` (i.e., the filter is blank), the paragraph sets `FLG-ACCTFILTER-BLANK TO TRUE` (L1010), MOVEs `ZEROES TO CDEMO-ACCT-ID` (L1011), and goes to the exit (L1012).
- L1017-L1025: if `CC-ACCT-ID IS NOT NUMERIC` (i.e., the filter contains non-digit characters), the paragraph sets `INPUT-ERROR TO TRUE` (L1018), `FLG-ACCTFILTER-NOT-OK TO TRUE` (L1019), `FLG-PROTECT-SELECT-ROWS-YES TO TRUE` (L1020, which forces all row selectors to PROT on the next SEND MAP), and MOVEs the verbatim error message at L1022 into `WS-ERROR-MSG`:

```text
ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER
```

- L1026-L1029: otherwise (valid 11-digit numeric filter), MOVE `CC-ACCT-ID TO CDEMO-ACCT-ID` (L1027), SET `FLG-ACCTFILTER-ISVALID TO TRUE` (L1028).

The `2220-EDIT-CARD` paragraph at L1036-L1071 mirrors the structure of 2210-EDIT-ACCOUNT for the CARDSID filter `CC-CARD-NUM PIC X(16)`:

- L1042-L1048: blank-filter branch (sets FLG-CARDFILTER-BLANK).
- L1052-L1062: not-numeric branch sets INPUT-ERROR (L1053), FLG-CARDFILTER-NOT-OK (L1054), FLG-PROTECT-SELECT-ROWS-YES (L1055), and -- gated by `IF WS-ERROR-MSG-OFF` at L1056 -- MOVEs the verbatim error message at L1058 into `WS-ERROR-MSG`:

```text
CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER
```

Both L1022 and L1058 contain LITERAL COMMA WITHOUT SPACE between `FILTER` and `IF`; preserved byte-for-byte.

- L1063-L1066: otherwise (valid 16-digit numeric filter), MOVE `CC-CARD-NUM-N TO CDEMO-CARD-NUM` (L1064), SET `FLG-CARDFILTER-ISVALID TO TRUE` (L1065).

First-message-wins discipline. The `IF WS-ERROR-MSG-OFF` guard at L1056 is the key behavioral invariant: when BOTH the ACCTSID and CARDSID filters are invalid, the ACCTSID error message (L1022) is moved first by 2210-EDIT-ACCOUNT; when 2220-EDIT-CARD then attempts to move L1058, the `WS-ERROR-MSG-OFF` 88-level is now FALSE (because L1022 left non-spaces in WS-ERROR-MSG), so the L1058 MOVE is skipped. The user sees only the L1022 message. The Java translation MUST preserve this exact ordering: the test fixture for the combined-invalid scenario asserts the L1022 message, NOT the L1058 message.

### 4.2 Selection Validation

The `2250-EDIT-ARRAY` paragraph at L1073 scans the 7-element `WS-EDIT-SELECT` array `PIC X(1) OCCURS 7 TIMES` (L75-L76, REDEFINES `WS-EDIT-SELECT-FLAGS` at L74-L75). Each element accepts the values defined by the 88-level conditions at L77-L82:

- `SELECT-OK VALUES 'S', 'U'` at L77 -- valid selector.
- `VIEW-REQUESTED-ON VALUE 'S'` at L78 -- view selection (XCTL to COCRDSLC on dispatch).
- `UPDATE-REQUESTED-ON VALUE 'U'` at L79 -- update selection (XCTL to COCRDUPC on dispatch).
- `SELECT-BLANK VALUES ' ', LOW-VALUES` at L80-L82 -- empty/no selection.

The counter `WS-EDIT-SELECT-COUNTER PIC S9(04) COMP-3` at L69-L71 (initialized to 0) accumulates the count of non-blank selections. The validation results in three exit states:

- Counter = 0 (no selection): proceeds to WHEN OTHER fall-through; re-renders the page (allows the user to either select a row or press a pagination key).
- Counter = 1 (single valid selection): records the selected row index in `I-SELECTED PIC S9(4) COMP` (L92-L93) for dispatch by the WHEN branches at L517-L569.
- Counter > 1 (multiple selections): MOVEs the verbatim error message at L124 (88-level `WS-MORE-THAN-1-ACTION` VALUE) into `WS-ERROR-MSG`:

```text
PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE
```

- Invalid value (not blank, not 'S', not 'U'): MOVEs the verbatim error message at L126 (88-level `WS-INVALID-ACTION-CODE` VALUE) into `WS-ERROR-MSG` and sets `WS-ROW-SELECT-ERROR` (L88) on the offending row, which 1250-SETUP-ARRAY-ATTRIBS uses to flag the row in RED color (L755-L770):

```text
INVALID ACTION CODE
```

### 4.3 XCTL Dispatch

When validation passes and exactly one row selector is set, the EVALUATE TRUE dispatch table at L418-L583 fires one of two WHEN branches:

For selection value `'S'` (VIEW-REQUESTED-ON), the L517-L541 branch:

- Updates the commarea metadata (L520-L525): MOVE `LIT-THISTRANID` ('CCLI'), `LIT-THISPGM` ('COCRDLIC'), and SET `CDEMO-USRTYP-USER` and `CDEMO-PGM-ENTER` to TRUE.
- MOVEs `LIT-CARDDTLPGM` ('COCRDSLC') to `CCARD-NEXT-PROG` (L526) and the matching mapset/map literals to `CCARD-NEXT-MAPSET`/`CCARD-NEXT-MAP` (L528-L529).
- MOVEs the selected row's `WS-ROW-ACCTNO(I-SELECTED)` to `CDEMO-ACCT-ID` (L531-L532) and `WS-ROW-CARD-NUM(I-SELECTED)` to `CDEMO-CARD-NUM` (L533-L534).
- Issues `EXEC CICS XCTL PROGRAM(CCARD-NEXT-PROG) COMMAREA(CARDDEMO-COMMAREA)` (L538-L541).

For `'U'` (UPDATE-REQUESTED-ON), the L545-L569 branch is identical except `LIT-CARDUPDPGM` ('COCRDUPC') replaces `LIT-CARDDTLPGM` (L554-L557).

### 4.4 Default Path

When the EVALUATE TRUE at L418 reaches the WHEN OTHER catch-all at L572-L582 (no error, no PFK match, no row selection), the catch-all:

- MOVEs `WS-CA-FIRST-CARD-NUM` to `WS-CARD-RID-CARDNUM` (L574-L575) -- on first-time entry this is LOW-VALUES, positioning STARTBR at the lowest key.
- Performs `9000-READ-FORWARD THRU 9000-READ-FORWARD-EXIT` (L578-L579) to fetch the next 7 rows.
- Performs `1000-SEND-MAP THRU 1000-SEND-MAP` (L580-L581) to render the screen.
- Goes to `COMMON-RETURN` (L582).

After EVALUATE: if `INPUT-ERROR` is set (L586), L586-L598 re-sends the map without re-reading. Otherwise L600-L601 sets `CCARD-NEXT-PROG` to LIT-THISPGM and falls through to COMMON-RETURN.

## Phase 5 -- 9000-READ-FORWARD / 9100-READ-BACKWARDS / 9500-FILTER-RECORDS

The data-access layer is bounded by three paragraphs: 9000-READ-FORWARD (forward browse), 9100-READ-BACKWARDS (backward browse), and 9500-FILTER-RECORDS (predicate applied to each fetched record). Each invocation follows the CICS pseudo-conversational discipline: STARTBR + READNEXT/READPREV loop bounded by `WS-MAX-SCREEN-LINES` = 7 + ENDBR.

### 5.1 STARTBR Initial Positioning

Both 9000-READ-FORWARD and 9100-READ-BACKWARDS open the browse with:

```text
EXEC CICS STARTBR
     DATASET(LIT-CARD-FILE)             ('CARDDAT ')
     RIDFLD(WS-CARD-RID-CARDNUM)
     KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     GTEQ
     RESP(WS-RESP-CD)
     RESP2(WS-REAS-CD)
END-EXEC
```

The GTEQ qualifier (cited at `app/cbl/COCRDLIC.cbl:L1277` in the 9100-READ-BACKWARDS paragraph and identically in 9000-READ-FORWARD) positions the browse cursor at the first record whose key is GREATER-THAN-OR-EQUAL to the supplied `WS-CARD-RID-CARDNUM`. When `WS-CARD-RID-CARDNUM = LOW-VALUES` (the first-time entry case from the WHEN OTHER catch-all), the cursor positions at the lowest-key record in the file. When `WS-CARD-RID-CARDNUM = WS-CA-LAST-CARD-NUM` (the PF8 case from L488-L489), the cursor positions at the boundary between the prior page's last record and the next page's first record.

### 5.2 READNEXT Forward Browse

The 9000-READ-FORWARD paragraph performs READNEXT in a loop bounded by `WS-MAX-SCREEN-LINES = 7`. Each iteration fetches one record into `CARD-RECORD` (from `CVACT02Y.cpy`) and runs the record through 9500-FILTER-RECORDS to test the active filter predicates.

When the row counter reaches `WS-MAX-SCREEN-LINES` (i.e., 7 records have been collected and passed the filter), the loop performs one EXTRA READNEXT (L1197-L1205) to probe whether a next page exists:

- If RESP = DFHRESP(NORMAL) or DFHRESP(DUPREC) (L1208-L1214): sets `CA-NEXT-PAGE-EXISTS TO TRUE` and records the probed key as `WS-CA-LAST-CARD-NUM` (so that PF8 on the next invocation can resume from this position).
- If RESP = DFHRESP(ENDFILE) (L1215-L1221): sets `CA-NEXT-PAGE-NOT-EXISTS TO TRUE` and -- gated by `IF WS-ERROR-MSG-OFF` at L1218 -- MOVEs the verbatim L1219 message `NO MORE RECORDS TO SHOW` into `WS-ERROR-MSG`.
- WHEN OTHER (L1222-L1230): treats the RESP as a file error; populates `WS-FILE-ERROR-MESSAGE` (the structured error template at L153-L171) and MOVEs it into `WS-ERROR-MSG`.

After loop exit, the paragraph issues `EXEC CICS ENDBR FILE(LIT-CARD-FILE)` at L1258-L1259 to release the browse cursor; pseudo-conversational discipline requires that no CICS resource handles persist across `EXEC CICS RETURN`.

### 5.3 READPREV Backward Browse

The 9100-READ-BACKWARDS paragraph at L1264 onward mirrors the 9000-READ-FORWARD structure but uses READPREV instead of READNEXT. The browse direction is reversed; the row counter is initialized to `WS-MAX-SCREEN-LINES + 1` (L1284-L1286) so that the loop iterates one extra time to fill the page from bottom to top, then the 7 collected rows are reversed before rendering.

End-of-file behavior produces the L903 verbatim message `NO PREVIOUS PAGES TO DISPLAY` when 1400-SETUP-MESSAGE detects `CCARD-AID-PFK07 AND CA-FIRST-PAGE` at L901-L904.

### 5.4 9500-FILTER-RECORDS

The 9500-FILTER-RECORDS paragraph applies the filter predicate to each fetched `CARD-RECORD`:

- If `FLG-ACCTFILTER-ISVALID` (the ACCTSID filter is set and valid): `CARD-ACCT-ID` (from the CARD-RECORD copybook field) must equal the 11-digit filter value in `CDEMO-ACCT-ID`. If not, set `WS-EXCLUDE-THIS-RECORD TO TRUE` (88-level at L148 VALUE '0').
- If `FLG-CARDFILTER-ISVALID` (the CARDSID filter is set and valid): `CARD-NUM` must equal the 16-digit filter value in `CDEMO-CARD-NUM`. If not, set `WS-EXCLUDE-THIS-RECORD TO TRUE`.
- Both filters are combined with logical AND. A record passes the filter only if BOTH active filter constraints match.
- When `WS-EXCLUDE-THIS-RECORD` is TRUE, the loop counter is NOT advanced; the loop continues to the next READNEXT (or READPREV) without populating a row in `WS-EACH-ROW`.

### 5.5 Browse Exhaustion Handling

The browse-exhaustion error handling produces two distinct verbatim messages depending on the entry path:

- First-page entry path: when STARTBR or READNEXT returns DFHRESP(ENDFILE) AND `WS-CA-SCREEN-NUM = 1` AND `WS-SCRN-COUNTER = 0` (L1241-L1245), the paragraph sets `WS-NO-RECORDS-FOUND TO TRUE` (L1244), which activates the 88-level condition at L121-L122 producing the verbatim message including its trailing period:

```text
NO RECORDS FOUND FOR THIS SEARCH CONDITION.
```

- Subsequent pagination path: when ENDFILE is reached after rows have been collected on a non-first page (i.e., `WS-CA-SCREEN-NUM > 1` OR `WS-SCRN-COUNTER > 0`), the paragraph -- gated by `IF WS-ERROR-MSG-OFF` at L1218 and L1238 -- MOVEs the verbatim message at L1219 (extra-probe path) or L1239 (loop-exit path) into `WS-ERROR-MSG`:

```text
NO MORE RECORDS TO SHOW
```

Both L1219 and L1239 contain the identical 23-byte string. The Java port may use a single string constant referenced from both sites.


## Phase 6 -- Screen Build

The screen-build chain is orchestrated by 1000-SEND-MAP (L624-L640) which performs six sub-paragraphs in sequence: 1100-SCREEN-INIT, 1200-SCREEN-ARRAY-INIT, 1250-SETUP-ARRAY-ATTRIBS, 1300-SETUP-SCREEN-ATTRS, 1400-SETUP-MESSAGE, 1500-SEND-SCREEN. The descendant Java test class MUST observe the exact MOVE sequence in each sub-paragraph because BMS frames are sensitive to field ordering and padding.

### 6.1 Header Fields

The 1100-SCREEN-INIT paragraph at L642-L672 initializes the CCRDLIAO output overlay (the symbolic-map output struct from `app/cpy-bms/COCRDLI.CPY`) as follows:

- L643: `MOVE LOW-VALUES TO CCRDLIAO` -- clears the entire output overlay.
- L645: `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` -- captures the current timestamp into the `CSDAT01Y` work area. Tests inject `ScopedValue<java.time.Clock>` per AAP Sec 0.6.6 to make this deterministic.
- L647: `MOVE CCDA-TITLE01 TO TITLE01O OF CCRDLIAO` -- screen title row 1 from `COTTL01Y.cpy`.
- L648: `MOVE CCDA-TITLE02 TO TITLE02O OF CCRDLIAO` -- screen title row 2.
- L649: `MOVE LIT-THISTRANID TO TRNNAMEO OF CCRDLIAO` -- 'CCLI' (4 chars).
- L650: `MOVE LIT-THISPGM TO PGMNAMEO OF CCRDLIAO` -- 'COCRDLIC' (8 chars).
- L654-L658: extract month/day/2-digit-year from `WS-CURDATE-DATA` and assemble into `WS-CURDATE-MM-DD-YY` (8-char `mm/dd/yy` format), then MOVE to `CURDATEO OF CCRDLIAO`.
- L660-L664: extract hours/minutes/seconds from `WS-CURDATE-DATA` and assemble into `WS-CURTIME-HH-MM-SS` (8-char `hh:mm:ss` format), then MOVE to `CURTIMEO OF CCRDLIAO`.
- L667: `MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO` -- 1-digit page number (1-9 per the WS-CA-SCREEN-NUM PIC 9(1) declaration; the BMS field PAGENO is 3 bytes wide so the value is right-aligned with leading spaces, e.g., '  1').
- L669: `SET WS-NO-INFO-MESSAGE TO TRUE` -- clears the info message buffer.
- L670: `MOVE WS-INFO-MSG TO INFOMSGO OF CCRDLIAO` -- propagates the (now blank) info message.
- L671: `MOVE DFHBMDAR TO INFOMSGC OF CCRDLIAO` -- sets the info-message color attribute to the default-attribute-reset value.

### 6.2 Row Population

The 1200-SCREEN-ARRAY-INIT paragraph at L678-L743 populates the 7 data rows by iterating WS-EACH-CARD(1) through WS-EACH-CARD(7). For each row N, the paragraph checks `IF WS-EACH-CARD(N) EQUAL LOW-VALUES` (L680, L689, L698, L707, L716, L726, L735): if true, the row is left as LOW-VALUES (interpreted by BMS as an unmodified blank row); if false, the paragraph MOVEs four fields:

- `WS-EDIT-SELECT(N) TO CRDSEL<N>O OF CCRDLIAO` -- the user's last-typed selector value, echoed back (L683, L692, L701, L710, L719, L729, L738).
- `WS-ROW-ACCTNO(N) TO ACCTNO<N>O OF CCRDLIAO` -- 11-char account ID display (L684, L693, L702, L711, L720, L730, L739).
- `WS-ROW-CARD-NUM(N) TO CRDNUM<N>O OF CCRDLIAO` -- 16-char card number display (L685, L694, L703, L712, L721, L731, L740). **MASKED** in test capture per AAP Sec 0.7.2: the raw 16-digit PAN is replaced with `'************' + last4` at capture time before the bytes are committed to `bms_output.txt`.
- `WS-ROW-CARD-STATUS(N) TO CRDSTS<N>O OF CCRDLIAO` -- 1-char status indicator (typically 'Y' for active, 'N' for inactive) (L686, L695, L704, L713, L722, L732, L741).

BMS row asymmetry note. The BMS map at `app/bms/COCRDLI.bms` defines Row 1 (line 11 on the 24-row screen) with 4 fields: CRDSEL1 (L140-L144), ACCTNO1 (L147-L151), CRDNUM1 (L152-L156), CRDSTS1 (L157-L161). Rows 2-7 (lines 12-17 on screen) each have 5 fields: CRDSEL<n>, CRDSTP<n>, ACCTNO<n>, CRDNUM<n>, CRDSTS<n>. The CRDSTP<n> fields (CRDSTP2 at L169-L173, CRDSTP3 at L196-L200, etc.) are 1-byte stopper/protect attribute holders with `ATTRB=(ASKIP,DRK,FSET)` -- they exist between the selector field and the account-number field to enforce cursor protection on the BMS form, and they are NOT populated by 1200-SCREEN-ARRAY-INIT.

### 6.3 Message Fields

The 1400-SETUP-MESSAGE paragraph at L895-L932 selects the info/error message to display based on a second EVALUATE TRUE (L897-L922):

- L898-L900 `WHEN FLG-ACCTFILTER-NOT-OK` or `WHEN FLG-CARDFILTER-NOT-OK`: CONTINUE -- the error message was set by 2210/2220 already.
- L901-L904 `WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE`: MOVE `'NO PREVIOUS PAGES TO DISPLAY'` to WS-ERROR-MSG (the L903 verbatim message).
- L905-L909 `WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS AND CA-LAST-PAGE-SHOWN`: MOVE `'NO MORE PAGES TO DISPLAY'` to WS-ERROR-MSG (the L908 verbatim message).
- L910-L916 `WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS`: SET `WS-INFORM-REC-ACTIONS TO TRUE` (the L116 info-message); flip the sticky `CA-LAST-PAGE-SHOWN` flag if not already shown.
- L917-L918 `WHEN WS-NO-INFO-MESSAGE` or `WHEN CA-NEXT-PAGE-EXISTS`: SET `WS-INFORM-REC-ACTIONS TO TRUE`.
- L920-L921 `WHEN OTHER`: SET `WS-NO-INFO-MESSAGE TO TRUE`.

After the EVALUATE, L924 `MOVE WS-ERROR-MSG TO ERRMSGO OF CCRDLIAO` writes the (75-byte) error message into the (78-byte) ERRMSG BMS field. The COBOL implicit MOVE truncation/padding rule right-pads the 75-byte source with 3 bytes of spaces to fill the 78-byte target.

L926-L930: if neither `WS-NO-INFO-MESSAGE` nor `WS-NO-RECORDS-FOUND` is true, MOVE `WS-INFO-MSG` to `INFOMSGO OF CCRDLIAO` (the 45-byte info field) and MOVE `DFHNEUTR` (the neutral color attribute) to `INFOMSGC OF CCRDLIAO`. The default info message is the L116 verbatim:

```text
TYPE S FOR DETAIL, U TO UPDATE ANY RECORD
```

The 1300-SETUP-SCREEN-ATTRS paragraph sets the cursor position to ACCTSIDL on the input field when `INPUT-OK` (L884-L886). The 1250-SETUP-ARRAY-ATTRIBS paragraph at L748-L880 sets per-row color/protect attributes including the RED color for rows flagged with `WS-ROW-CRDSELECT-ERROR(N) = '1'` (L755-L770).

## Phase 7 -- COMMON-RETURN

The COMMON-RETURN paragraph at L604-L620 is the single exit point for the program. It serializes the commarea state into the 2000-byte `WS-COMMAREA` buffer and issues `EXEC CICS RETURN`:

```cobol
       COMMON-RETURN.
           MOVE  LIT-THISTRANID TO CDEMO-FROM-TRANID
           MOVE  LIT-THISPGM     TO CDEMO-FROM-PROGRAM
           MOVE  LIT-THISMAPSET  TO CDEMO-LAST-MAPSET
           MOVE  LIT-THISMAP     TO CDEMO-LAST-MAP
           MOVE  CARDDEMO-COMMAREA    TO WS-COMMAREA
           MOVE  WS-THIS-PROGCOMMAREA TO
                  WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1:
                               LENGTH OF WS-THIS-PROGCOMMAREA )
           EXEC CICS RETURN
                TRANSID (LIT-THISTRANID)
                COMMAREA (WS-COMMAREA)
                LENGTH(LENGTH OF WS-COMMAREA)
           END-EXEC
```

The reference modification `WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)` (L611-L612) writes `WS-THIS-PROGCOMMAREA` at the byte position after CARDDEMO-COMMAREA. Total `WS-COMMAREA` is exactly 2000 bytes (L262). This serialization preserves the COCRDLIC browse cursor state (WS-CA-SCREEN-NUM, WS-CA-FIRST-CARD-NUM, WS-CA-LAST-CARD-NUM, WS-CA-NEXT-PAGE-IND, CA-LAST-PAGE-SHOWN) across pseudo-conversational interactions; on next invocation L327-L331 deserializes by reading the same offsets. The Java port MUST preserve byte offsets exactly.

## Phase 8 -- 1500-SEND-SCREEN

The 1500-SEND-SCREEN paragraph at L938-L946 issues the CICS SEND MAP that emits the constructed CCRDLIAO output overlay as a 24x80 BMS frame:

```cobol
       1500-SEND-SCREEN.
           EXEC CICS SEND MAP(LIT-THISMAP)
                          MAPSET(LIT-THISMAPSET)
                          FROM(CCRDLIAO)
                          CURSOR
                          ERASE
                          RESP(WS-RESP-CD)
                          FREEKB
           END-EXEC
```

SEND MAP options: `MAP(LIT-THISMAP)`='CCRDLIA'; `MAPSET(LIT-THISMAPSET)`='COCRDLI'; `FROM(CCRDLIAO)` the populated output overlay; `CURSOR` positions at the BMS field whose L attribute is -1 (ACCTSIDL on INPUT-OK; CRDSEL<N>L on row-selector error per L770, L782, L794); `ERASE` clears the prior 3270 frame; `FREEKB` unlocks the keyboard. The umbrella 1000-SEND-MAP at L624-L640 orchestrates the chain: 1100-SCREEN-INIT, 1200-SCREEN-ARRAY-INIT, 1250-SETUP-ARRAY-ATTRIBS, 1300-SETUP-SCREEN-ATTRS, 1400-SETUP-MESSAGE, 1500-SEND-SCREEN.

## Phase 9 -- ABEND-ROUTINE

The COCRDLIC source does not include a dedicated ABEND-ROUTINE paragraph because the program relies on the WS-FILE-ERROR-MESSAGE template (L153-L171) to format unexpected CICS RESP errors and dispatch them through the standard error path. The template is:

```cobol
       05  WS-FILE-ERROR-MESSAGE.
         10  FILLER                              PIC X(12)
                                                 VALUE 'File Error:'.
         10  ERROR-OPNAME                        PIC X(8)
                                                 VALUE SPACES.
         10  FILLER                              PIC X(4)
                                                 VALUE ' on '.
         10  ERROR-FILE                          PIC X(9)
                                                 VALUE SPACES.
         10  FILLER                              PIC X(15)
                                                 VALUE
                                                 ' returned RESP '.
         10  ERROR-RESP                          PIC X(10)
                                                 VALUE SPACES.
         10  FILLER                              PIC X(7)
                                                 VALUE ',RESP2 '.
         10  ERROR-RESP2                         PIC X(10)
                                                 VALUE SPACES.
        10  FILLER                               PIC X(5).
```

When 9000-READ-FORWARD or 9100-READ-BACKWARDS encounters a WHEN OTHER RESP from STARTBR or READNEXT/READPREV (L1222-L1230, L1246-L1254), it MOVEs the operation name ('READ') to ERROR-OPNAME, the file name (LIT-CARD-FILE = 'CARDDAT ') to ERROR-FILE, the RESP and RESP2 values to ERROR-RESP and ERROR-RESP2 respectively, and then MOVEs the assembled WS-FILE-ERROR-MESSAGE into WS-ERROR-MSG for display via 1400-SETUP-MESSAGE. The user sees a single-line file error in the ERRMSG row of the BMS frame; the program continues into COMMON-RETURN rather than abending.

The Java port translates these CICS RESP error paths into the `FileStatus` sealed exception hierarchy per AAP Sec 0.6.5.

## Phase 10 -- BMS Map 24x80

The BMS map `CCRDLIA` in mapset `COCRDLI` at `app/bms/COCRDLI.bms:L20-L341` defines the 24-row by 80-column 3270 screen. The map declares `CTRL=(FREEKB)`, `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`, `MAPATTS=(COLOR,HILIGHT,PS,VALIDN)`, and `SIZE=(24,80)` (L25-L28). Total visible field area is 1920 bytes (24 rows times 80 columns).

### 10.1 Header Region (rows 1-4)

```text
Row 1: 'Tran:' (col 1, len 5, ASKIP NORM BLUE; bms L29-L33),
       TRNNAME (col 7, len 4, ASKIP FSET NORM BLUE; bms L34-L37),
       TITLE01 (col 21, len 40, ASKIP NORM YELLOW; bms L38-L41),
       'Date:' (col 65, len 5, ASKIP NORM BLUE; bms L42-L46),
       CURDATE (col 71, len 8, ASKIP NORM BLUE, INITIAL='mm/dd/yy'; bms L47-L51)
Row 2: 'Prog:' (col 1, len 5, ASKIP NORM BLUE; bms L52-L56),
       PGMNAME (col 7, len 8, ASKIP NORM BLUE; bms L57-L60),
       TITLE02 (col 21, len 40, ASKIP NORM YELLOW; bms L61-L64),
       'Time:' (col 65, len 5, ASKIP NORM BLUE; bms L65-L69),
       CURTIME (col 71, len 8, ASKIP NORM BLUE, INITIAL='hh:mm:ss'; bms L70-L74)
Row 3: blank (no fields defined)
Row 4: 'List Credit Cards' (col 31, len 17, NEUTRAL; bms L75-L78),
       'Page ' (col 70, len 5, INITIAL='Page '; bms L79-L81),
       PAGENO (col 76, len 3; bms L82-L83)
```

### 10.2 Filter Region (rows 6-7)

```text
Row 6: 'Account Number    :' (col 22, len 19, ASKIP NORM TURQUOISE; bms L84-L88),
       ACCTSID input (col 44, len 11, FSET IC NORM UNPROT GREEN UNDERLINE; bms L89-L93)
       -- cursor positioned here on initial entry (IC attribute)
       stopper field (col 56, len 0; bms L94-L95)
Row 7: 'Credit Card Number:' (col 22, len 19, ASKIP NORM TURQUOISE; bms L96-L100),
       CARDSID input (col 44, len 16, FSET NORM UNPROT GREEN UNDERLINE; bms L101-L105)
       stopper field (col 61, len 0; bms L106-L107)
```

### 10.3 Data Table Region (rows 9-17)

```text
Row 9: column headers (NEUTRAL):
       'Select    ' (col 10, len 10; bms L108-L111),
       'Account Number' (col 21, len 14; bms L112-L115),
       ' Card Number ' (col 45, len 13; bms L116-L119),
       'Active ' (col 66, len 7; bms L120-L123)
Row 10: underscore separators (NEUTRAL, INITIAL='------' style):
       (col 10, len 6; bms L124-L127),
       (col 20, len 15; bms L128-L131),
       (col 43, len 15; bms L132-L135),
       (col 65, len 8; bms L136-L139)

Rows 11-17: 7 data rows. Each row has:
       CRDSEL<n>  (col 12, len 1, FSET NORM PROT DEFAULT UNDERLINE; bms L140-L144 for row 1)
                  -- Row 1 has only this selector at col 12 with no CRDSTP stopper
                  -- Rows 2-7 add CRDSTP<n> (col 14, len 1, ASKIP DRK FSET)
                     between CRDSEL<n> and ACCTNO<n>
       ACCTNO<n>  (col 22, len 11, NORM PROT DEFAULT; bms L147-L151 for row 1)
       CRDNUM<n>  (col 43, len 16, NORM PROT DEFAULT; bms L152-L156 for row 1)
                  -- MASKED in test capture to '************<last4>' per AAP Sec 0.7.2
       CRDSTS<n>  (col 67, len 1, NORM PROT DEFAULT; bms L157-L161 for row 1)
```

The symbolic-map output overlay `CCRDLIAO` is defined in `app/cpy-bms/COCRDLI.CPY:L73-L276`. The CCRDLIAI input overlay and CCRDLIAO output overlay REDEFINE the same byte region. The Java port models these as two distinct records (`CoCrdLiInput` and `CoCrdLiOutput`); byte-level parity is preserved by the `parse(byte[])` and `encode()` methods.

### 10.4 Footer Region (rows 20-24)

```text
Row 20: INFOMSG (col 19, len 45, PROT NEUTRAL; bms L324-L328)
        stopper field (col 65, len 0; bms L329-L330)
Row 23: ERRMSG (col 1, len 78, ASKIP BRT FSET RED; bms L331-L334)
Row 24: footer (col 1, len 78, ASKIP NORM TURQUOISE,
        INITIAL='  F3=Exit F7=Backward  F8=Forward'; bms L335-L339)
```

Two leading spaces and the double space between `Backward` and `F8=Forward` are intentional per the L339 INITIAL clause; the Java port MUST emit these spaces byte-identical.

## Phase 11 -- Required Test Scenarios

Each of the 14 scenarios below corresponds to one or more captured BMS frames in `bms_output.txt` after COBOL baseline capture per AAP Sec 0.7.5.

### 11.1 Scenario List

| # | Name | COMMAREA-IN State | AID Key Pressed | Filter / Row-Selector Values | Expected Outcome |
|---|------|-------------------|-----------------|------------------------------|------------------|
| 1 | First-time entry, fresh session | EIBCALEN=0 | none (auto ENTER) | both filters blank | render page 1 from lowest key; default info message; INFOMSGO='TYPE S FOR DETAIL, U TO UPDATE ANY RECORD' |
| 2 | Self-reentry, no input | EIBCALEN=2000, CDEMO-FROM-PROGRAM='COCRDLIC' | DFHENTER | both filters blank | refresh page 1; same as scenario 1 outcome |
| 3 | Valid ACCTSID filter | EIBCALEN=2000, fresh | DFHENTER | ACCTSID='00000000001', CARDSID blank | render page 1 of cards matching CARD-ACCT-ID=1 |
| 4 | Valid CARDSID filter | EIBCALEN=2000, fresh | DFHENTER | ACCTSID blank, CARDSID='4111111111111111' | render single matching card row |
| 5 | Combined ACCTSID + CARDSID | EIBCALEN=2000, fresh | DFHENTER | ACCTSID='00000000001', CARDSID='4111111111111111' | render rows matching BOTH (AND) |
| 6 | PF8 forward from page 1 | EIBCALEN=2000, WS-CA-SCREEN-NUM=1, CA-NEXT-PAGE-EXISTS | DFHPF8 | filters preserved from prior | render page 2 starting from WS-CA-LAST-CARD-NUM; WS-CA-SCREEN-NUM=2 |
| 7 | PF7 backward from page 2 | EIBCALEN=2000, WS-CA-SCREEN-NUM=2 | DFHPF7 | filters preserved | render page 1 via 9100-READ-BACKWARDS; WS-CA-SCREEN-NUM=1 |
| 8 | PF7 at first page (L903) | EIBCALEN=2000, WS-CA-SCREEN-NUM=1, CA-FIRST-PAGE | DFHPF7 | filters preserved | refresh page 1; ERRMSG='NO PREVIOUS PAGES TO DISPLAY' (L903) |
| 9 | PF8 at last page (L908) | EIBCALEN=2000, CA-NEXT-PAGE-NOT-EXISTS, CA-LAST-PAGE-SHOWN | DFHPF8 | filters preserved | re-render same page; ERRMSG='NO MORE PAGES TO DISPLAY' (L908) |
| 10 | Non-numeric ACCTSID 'ABC' (L1022) | EIBCALEN=2000, fresh | DFHENTER | ACCTSID='ABC        ', CARDSID blank | re-render with ERRMSG='ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'; row selectors protected (FLG-PROTECT-SELECT-ROWS-YES) |
| 11 | Non-numeric CARDSID 'XYZ' (L1058) | EIBCALEN=2000, fresh | DFHENTER | ACCTSID blank, CARDSID='XYZ             ' | re-render with ERRMSG='CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'; row selectors protected |
| 12 | Valid filter, no matches (L122) | EIBCALEN=2000, fresh | DFHENTER | ACCTSID='99999999999' (no records) | render empty grid; ERRMSG='NO RECORDS FOUND FOR THIS SEARCH CONDITION.' (with trailing period) |
| 13 | CRDSEL1='S' XCTL to COCRDSLC | EIBCALEN=2000, prior page 1 rendered | DFHENTER | CRDSEL1='S', row 1 has WS-ROW-CARD-NUM populated | XCTL COCRDSLC with CDEMO-ACCT-ID and CDEMO-CARD-NUM populated from row 1; no SEND MAP from COCRDLIC |
| 14 | CRDSEL3='U' XCTL to COCRDUPC | EIBCALEN=2000, prior page 1 rendered | DFHENTER | CRDSEL3='U', row 3 populated | XCTL COCRDUPC with CDEMO-ACCT-ID and CDEMO-CARD-NUM populated from row 3 |

### 11.2 Coverage Map

This table maps each of the 11 verbatim COBOL messages (per Supplementary 1) to the scenario(s) that exercise it.

| # | COBOL Source Line | Message | Exercised By Scenario(s) |
|---|-------------------|---------|--------------------------|
| 1 | COCRDLIC.cbl L116 | `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD` | 1, 2, 3, 4, 5 (default info on successful render) |
| 2 | COCRDLIC.cbl L120 | `PF03 PRESSED.EXITING` | (PF3-exit; not in the 14 list above because the XCTL to COMEN01C does not produce a COCRDLIC BMS frame; the message is set on the commarea for the receiving program. A coordinated test in COMEN01C asserts receipt.) |
| 3 | COCRDLIC.cbl L122 | `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` | 12 |
| 4 | COCRDLIC.cbl L124 | `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE` | (multi-select; covered by a coordinated extension scenario reserved for future expansion) |
| 5 | COCRDLIC.cbl L126 | `INVALID ACTION CODE` | (invalid row-selector value; covered by a coordinated extension scenario reserved for future expansion) |
| 6 | COCRDLIC.cbl L903 | `NO PREVIOUS PAGES TO DISPLAY` | 8 |
| 7 | COCRDLIC.cbl L908 | `NO MORE PAGES TO DISPLAY` | 9 |
| 8 | COCRDLIC.cbl L1022 | `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER` | 10 |
| 9 | COCRDLIC.cbl L1058 | `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER` | 11 |
| 10 | COCRDLIC.cbl L1219/L1239 | `NO MORE RECORDS TO SHOW` | (browse-exhaustion mid-pagination; covered by a coordinated extension scenario reserved for future expansion) |
| 11 | COCRDLIC.cbl L153-L171 | `File Error:` + OP-NAME + ` on ` + FILE-NAME + ` returned RESP ` + RESP-VAL + `,RESP2 ` + RESP2-VAL | (CICS RESP error path; covered by a coordinated extension scenario reserved for future expansion using fault injection) |

Messages #2, #4, #5, #10, #11 are reserved for coordinated extension scenarios; their byte-for-byte preservation remains in source-line citation scope, with captured-byte equality asserted when those scenarios are added.

## Phase 12 -- Files in Directory

| Filename | Size Constraint | Purpose | Encoding |
|----------|-----------------|---------|----------|
| `README.md` | 500-700 lines | Authoritative per-program contract (this file) | UTF-8 ASCII |
| `input_scenario.txt` | 200-280 lines, 14 scenarios | Synthesized BMS keystroke driver | UTF-8 ASCII |
| `stdout.txt` | EXACTLY 8 lines | Placeholder for captured DISPLAY output (empty in COCRDLIC -- zero DISPLAY verbs in source) | UTF-8 ASCII |
| `bms_output.txt` | EXACTLY 13 lines | Placeholder for captured BMS SEND MAP frame dumps | UTF-8 ASCII |

No other files are permitted in this directory; `app/data/ASCII/` fixtures are REFERENCE-only via classpath relative path and MUST NOT be copied here.

## Phase 13 -- Structural Invariants

### 13.1 Byte-for-Byte Equality

Captured outputs MUST match the COBOL baseline byte-for-byte (AAP Sec 0.6.5). The Java test class uses `Files.readAllBytes(Path)` from `java.nio.file` to load both expected and actual outputs, then `Arrays.equals(byte[], byte[])` for the equality assertion. The test fails on any single-byte difference: differing whitespace, differing line endings, differing PAN masking, or differing date/time render. The descendant `CoCrdLiCGoldenTest` extends `GoldenRecordTest` which provides the canonical comparison framework.

### 13.2 BMS Frame Determinism

Each captured frame in `bms_output.txt` is a 1920-byte (24x80) dump produced from the CCRDLIAO symbolic-map output overlay after BMS rendering. The frame layout follows the field positions enumerated in Phase 10. The Java port serializes the rendered frame by walking the CCRDLIAO record fields in BMS-defined positional order and emitting each field's bytes at the declared POS row/column.

Fixed Clock injection per AAP Sec 0.6.6 makes the CURDATEO (row 1 col 71, 8 chars `mm/dd/yy`) and CURTIMEO (row 2 col 71, 8 chars `hh:mm:ss`) fields deterministic. Without fixed clock injection, the L645 `MOVE FUNCTION CURRENT-DATE` would produce non-reproducible bytes; the descendant test class MUST use the ScopedValue pattern shown in 13.5.

### 13.3 PAN Masking

Every captured BMS frame and stdout line that would otherwise show a 16-digit CARD-NUM displays only the last 4 digits in clear; the first 12 are masked to ASCII `'*'`. Example: `'************0001'` (12 asterisks + 4 last digits). Surfaces affected: CRDNUM<N>O fields in `bms_output.txt` (rows 11-17 col 43, 16 chars each); DISPLAY output (none in COCRDLIC -- zero DISPLAY verbs); test scaffolding logs (handled via SLF4J/Logback masking filter in `logback-test.xml` under `java/carddemo-tests/src/test/resources/`).

The Java port applies the PAN mask at the boundary between the in-memory `CardRecord` and the rendered output bytes; masking MUST NOT alter the in-memory representation so that round-trip parse/encode of the underlying record still yields original raw bytes.

### 13.4 Verbatim Message Preservation

The 11 COCRDLIC verbatim messages cataloged in Supplementary 1 MUST appear byte-for-byte in captured frames; no whitespace insertion, no comma-space normalization, no Unicode substitution. Critical preservation rules:

- L1022 contains a LITERAL COMMA WITHOUT SPACE between `FILTER` and `IF`: the 52-byte string is `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER` (notice the comma directly abuts `IF` with no intervening space).
- L1058 contains a LITERAL COMMA WITHOUT SPACE in the same position: the 52-byte string is `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`.
- L122 INCLUDES a trailing period: the 43-byte string is `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` (the period is byte 43, not whitespace).

The Java port MUST source these strings from a single immutable constant per message and reuse the constant at every emission site. The descendant test class asserts the byte sequence at the documented frame offset (ERRMSG at row 23 col 1, 78 bytes total, with the verbatim message left-aligned and right-padded with spaces).

### 13.5 Fixed Clock Determinism

`ScopedValue<java.time.Clock>` injected at test runtime fixes CURDATEO and CURTIMEO to deterministic values. The recommended fixed instant is `2024-01-15T10:00:00Z` rendered in UTC. The pattern per AAP Sec 0.6.6 is:

```text
ScopedValue.where(TestClock, Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneOffset.UTC))
           .run(() -> coCrdLiC.invoke(commarea, eibaid, screenInput));
```

The Java port routes the L645 `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` through `Clock.instant()` rather than `Instant.now()`, where `Clock` is resolved from the `TestClock` ScopedValue inside the test scope and from `Clock.systemUTC()` outside it. This pattern is enforced by AAP Sec 0.6.6 which forbids `ThreadLocal` in new code. The descendant test class MUST establish the ScopedValue binding at the start of each scenario; failure to do so produces non-deterministic CURDATEO/CURTIMEO bytes and breaks byte-for-byte parity.


## Supplementary 1 -- Verbatim COBOL Message Catalog

The 11 verbatim messages emitted by COCRDLIC are cataloged below with exact source-line citations. Every captured byte sequence in `bms_output.txt` and any future stdout/log capture MUST preserve these strings byte-identical.

| # | Line | Length | Verbatim Bytes |
|---|------|--------|----------------|
| 1 | L116 | 41 chars | `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD` |
| 2 | L120 | 20 chars | `PF03 PRESSED.EXITING` |
| 3 | L122 | 43 chars | `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` |
| 4 | L124 | 47 chars | `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE` |
| 5 | L126 | 19 chars | `INVALID ACTION CODE` |
| 6 | L903 | 28 chars | `NO PREVIOUS PAGES TO DISPLAY` |
| 7 | L908 | 24 chars | `NO MORE PAGES TO DISPLAY` |
| 8 | L1022 | 52 chars | `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER` (COMMA WITHOUT SPACE) |
| 9 | L1058 | 52 chars | `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER` (COMMA WITHOUT SPACE) |
| 10 | L1219/L1239 | 23 chars | `NO MORE RECORDS TO SHOW` |
| 11 | L153-L171 | variable | `File Error:` + OP-NAME + ` on ` + FILE-NAME + ` returned RESP ` + RESP-VAL + `,RESP2 ` + RESP2-VAL |

## Supplementary 2 -- Capture Procedure Cross-Reference

The canonical COBOL baseline capture procedure is documented in `java/MIGRATION_NOTES.md` Sec 1.6 per AAP Sec 0.7.5; that single procedure governs all per-program golden-record fixtures and is NOT duplicated here to prevent drift. Once captures are committed, the descendant test author replaces placeholder `stdout.txt`/`bms_output.txt` contents with captured bytes, removes the `@Disabled` annotation on `CoCrdLiCGoldenTest`, and the PR re-runs the golden-record harness demonstrating byte-for-byte parity.

## Supplementary 3 -- Source Lineage

Every fact in this README is derived by direct citation from the source files below; `app/` is the immutable reference per AAP Sec 0.2.2.

| Source File | Path | Role |
|-------------|------|------|
| COCRDLIC.cbl | `app/cbl/COCRDLIC.cbl` (1459 lines) | Primary program source |
| COCRDLI.bms | `app/bms/COCRDLI.bms` (344 lines) | BMS map definition |
| COCRDLI.CPY | `app/cpy-bms/COCRDLI.CPY` (560 lines) | Symbolic map copybook |
| CVACT02Y.cpy | `app/cpy/CVACT02Y.cpy` | CARD-RECORD layout (150 bytes) |
| CVACT03Y.cpy | `app/cpy/CVACT03Y.cpy` | CARD-XREF layout |
| COCOM01Y.cpy | `app/cpy/COCOM01Y.cpy` | CARDDEMO-COMMAREA base (200 bytes) |
| CVCRD01Y.cpy | `app/cpy/CVCRD01Y.cpy` | CC-WORK-AREAS (AID-key 88-levels, REDEFINES) |
| CSSTRPFY.cpy | `app/cpy/CSSTRPFY.cpy` | YYYY-STORE-PFKEY procedure template |
| CSMSG01Y.cpy | `app/cpy/CSMSG01Y.cpy` | Common message constants |
| COTTL01Y.cpy | `app/cpy/COTTL01Y.cpy` | Screen titles |
| CSDAT01Y.cpy | `app/cpy/CSDAT01Y.cpy` | Date/time constants |
| CSUSR01Y.cpy | `app/cpy/CSUSR01Y.cpy` | Signed-on user data |
| carddata.txt | `app/data/ASCII/carddata.txt` | CARDDAT fixture (REFERENCE; consumed via classpath) |
| cardxref.txt | `app/data/ASCII/cardxref.txt` | CARDXREF fixture (REFERENCE) |
| acctdata.txt | `app/data/ASCII/acctdata.txt` | ACCTDATA fixture (REFERENCE) |

## Supplementary 4 -- Cross-References

Related Java files in the refactor tree:

- `../input/README.md` -- navigational marker for input fixtures
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/card/CoCrdLiC.java` -- Java class under test
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoCrdLiCGoldenTest.java` -- per-program test class
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` -- abstract base test class
- `java/MIGRATION_NOTES.md` Sec 1.6 -- capture procedure
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/text/CcWorkAreas.java` -- AidKey sealed hierarchy (Enter, Clear, Pa1, Pa2, PfKey01-PfKey12) and CcCardNum/CcAcctId sealed REDEFINES

## Supplementary 5 -- Authority References

AAP sections governing this fixture set:

- AAP Sec 0.2.1 (file inventory in scope)
- AAP Sec 0.2.2 (out-of-scope: `app/` unchanged)
- AAP Sec 0.3.1 (target tree structure)
- AAP Sec 0.4.1 (file-by-file transformation -- CoCrdLiC mapping)
- AAP Sec 0.6.4 (`java.time` -- no `java.util.Date`)
- AAP Sec 0.6.5 (`java.nio.file` + byte fidelity)
- AAP Sec 0.6.6 (`ScopedValue` -- no `ThreadLocal`)
- AAP Sec 0.6.11 (golden-record PR gate + `@Disabled` until capture)
- AAP Sec 0.6.12 (no framework override of `docs/technical-specifications.md`)
- AAP Sec 0.7.1 (Minimal Change + verbatim COBOL messages)
- AAP Sec 0.7.2 (PAN masking discipline)
- AAP Sec 0.7.4 (forbidden: `--enable-preview`, JEP 502/505/507, `ThreadLocal`, default branches)
- AAP Sec 0.7.5 (capture procedure cross-reference in `MIGRATION_NOTES.md`)

## Supplementary 6 -- Contrast Matrix (COCRDLIC vs COCRDSLC vs COCRDUPC)

| Aspect | COCRDLIC | COCRDSLC | COCRDUPC |
|--------|----------|----------|----------|
| Function | List paginated cards | View single card | Update single card |
| CICS Transaction | CCLI | CCDL | CCUP |
| BMS Mapset/Map | COCRDLI / CCRDLIA | COCRDSL / CCRDSLA | COCRDUP / CCRDUPA |
| CARDDAT Operation | STARTBR + READNEXT + READPREV + ENDBR | Plain READ | READ for UPDATE + REWRITE |
| AID Keys Supported | ENTER, PF3, PF7, PF8 | ENTER, PF3 | ENTER, PF3, PF5 (commit), PF12 (cancel) |
| Row Selectors | 7x CRDSEL/CRDSTP groups | none | none |
| XCTL Targets | COCRDSLC ('S'), COCRDUPC ('U'), COMEN01C (PF3) | (returns to caller) | (returns to caller) |
| Pagination | Yes (7 rows/page, WS-MAX-SCREEN-LINES) | No | No |
| Verbatim Message Count | 11 | 16 | 14 |
| Source Line Count | 1459 | 887 | 1100+ |
| Java Class | CoCrdLiC | CoCrdSlC | CoCrdUpC |
| File State | READ-only (EXPECT_CARDDAT_UNCHANGED) | READ-only | READ + REWRITE (state changes) |

## Supplementary 7 -- DO NOT Modify Without Re-Capture

```text
DO NOT modify the following without first re-capturing the COBOL baseline:
  - stdout.txt (replace ONLY with captured COBOL DISPLAY output)
  - bms_output.txt (replace ONLY with captured BMS SEND MAP byte dump)

DO modify the following as the test contract evolves:
  - input_scenario.txt (add or refine scenarios; each scenario MUST be exercisable
    by the descendant Java test class)
  - README.md (improve documentation; preserve all source-line citations)

NEVER:
  - Copy app/data/ASCII/*.txt into this folder
  - Embed unmasked PAN values anywhere
  - Use Unicode characters (em dash, en dash, smart quotes, ellipsis)
  - Use HTML in the README
  - Add files beyond the 4 enumerated in Phase 12
  - Use java.io.File (use java.nio.file)
  - Use java.util.Date or java.util.Calendar (use java.time)
  - Use ThreadLocal (use ScopedValue per AAP Sec 0.6.6)
  - Reference Spring, Spring Boot, Hibernate, JPA, PostgreSQL, Docker, AWS
  - Use --enable-preview, JEP 502, JEP 505, JEP 507
  - Add default branches to pattern-matching switches (use sealed exhaustiveness)
```
