# COACTUPC (Account Update, CAUP) — Golden-Record `input/` Folder (Documentation-Only)

This folder is the per-program **input fixture directory** for the COACTUPC (Account Update; CICS transaction `CAUP`) golden-record parity test. COACTUPC is the COBOL program that updates the account-master record AND the customer-master record via **2 simultaneous CICS REWRITE** operations bound by a single SYNCPOINT boundary. Source: `app/cbl/COACTUPC.cbl` (4,236 lines); `PROGRAM-ID. COACTUPC.` at L22; transaction literal `LIT-THISTRANID PIC X(4) VALUE 'CAUP'.` at L535-L536; mapset literal `LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '.` at L537-L538 (8 chars, trailing space); map literal `LIT-THISMAP PIC X(7) VALUE 'CACTUPA'.` at L539-L540.

This folder contains ONLY this `README.md`. The COBOL ASCII fixtures (`app/data/ASCII/acctdata.txt`, `custdata.txt`, `cardxref.txt`) are loaded by the test harness via classpath relative path and are **NOT copied** into this folder per AAP §0.4.1. The synthesized CICS pseudo-conversation driver script `input_scenario.txt` lives in the sibling `../expected/` subdirectory per the established harness convention from AAP §0.3.1 (visible at `java/carddemo-tests/src/test/resources/golden/coactupc/expected/input_scenario.txt`).

The consumer of this fixture is `com.blitzy.carddemo.tests.golden.CoActUpCGoldenTest` (which extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`). That test class is `@Disabled` until all captured artifacts under the sibling `expected/` folder are committed per AAP §0.6.11.

## Why this folder is documentation-only

AAP §0.4.1 explicitly designates `app/data/ASCII/*.txt` as REFERENCE fixtures that are read directly from `app/` via classpath relative path. They are NOT copied into the `java/` tree. Copying them would introduce drift risk between the source-of-truth files under `app/` and any test-tree duplicates — a violation of the byte-for-byte parity mandate in AAP §0.1.1.

The harness base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` exposes a helper method `resolveExpectedOutputPath(String programDir, String fileName)` that resolves to `src/test/resources/golden/<programDir>/expected/<fileName>` — NEVER to `input/`. Therefore the `input/` folder has no consumer for any data file; its sole purpose is conceptual documentation of the COACTUPC input contract.

AAP §0.3.1 mandates the `<program>/input/` + `<program>/expected/` directory convention for golden-record fixtures. The `input/` folder exists to satisfy that convention even when (as is the case for COACTUPC) all physical inputs live elsewhere. Keeping the `input/` folder present with only this README preserves the universal `<program>/input/` + `<program>/expected/` symmetry while making the absence of data files self-documenting.

Reference AAP §0.2.1 (in-scope: this README under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard) and AAP §0.2.2 (out-of-scope: `app/` tree NOT modified; ASCII fixtures NOT copied or duplicated under any circumstances).

## Conceptual input contract

### Initial-state files (3 ASCII fixtures from `app/data/ASCII/`)

The 3 input fixtures (loaded via classpath; NOT copied here):

- `app/data/ASCII/acctdata.txt` — **15,050 bytes total** (50 ACCOUNT-RECORD records × 300-byte RECLN + 50 LF byte-terminators = 50 × 301). Layout defined in `app/cpy/CVACT01Y.cpy` (13 fields: ACCT-ID 9(11), ACCT-ACTIVE-STATUS X(1), 5 BigDecimal monetary fields S9(10)V99 — ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT — 3 date fields X(10) including the misspelled `ACCT-EXPIRAION-DATE` at L11, ACCT-ADDR-ZIP X(10), ACCT-GROUP-ID X(10), plus FILLER X(178)).
- `app/data/ASCII/custdata.txt` — **25,050 bytes total** (50 CUSTOMER-RECORD records × 500-byte RECLN + 50 LF byte-terminators = 50 × 501). Layout defined in `app/cpy/CVCUS01Y.cpy` (18 fields: CUST-ID 9(9), 3 name fields X(25), 3 address-line fields X(50), state X(2), country X(3), zip X(10), 2 phone fields X(15), SSN 9(9), govt-issued-id X(20), DOB X(10), EFT-account-id X(10), pri-card-holder-ind X(1), FICO 9(3), plus FILLER X(168)).
- `app/data/ASCII/cardxref.txt` — **1,850 bytes total** (50 lines × 37 bytes per line: 36 data bytes + 1 LF). COBOL RECLN 50 per `app/cpy/CVACT03Y.cpy` (XREF-CARD-NUM X(16), XREF-CUST-ID 9(9), XREF-ACCT-ID 9(11), FILLER X(14)); the FILLER X(14) is elided in the ASCII transcription, leaving 36 data bytes + LF terminator per line.

These fixtures load via classpath relative paths and are accessed read-only by the harness. The harness MUST NOT mutate the fixture bytes under `app/data/ASCII/` per AAP §0.2.2; instead, the harness writes a per-scenario in-memory image, executes the Java `CoActUpC` translation against the image, and compares the resulting bytes against the captures under `../expected/`.

### Synthesized scenario file (lives in sibling `expected/` subdirectory)

The CICS pseudo-conversation driver script — `input_scenario.txt` — lists ~11 test scenarios for the harness to execute. It lives in the sibling `../expected/` folder per the established convention, NOT in `input/`. The canonical reference is `java/carddemo-tests/src/test/resources/golden/coactupc/expected/input_scenario.txt`.

The harness reads each scenario block from there, runs each through the Java `CoActUpC` translation, and asserts byte-for-byte equality of resulting `acctdata.txt` (post-state), `custdata.txt` (post-state), `stdout.txt` (DISPLAY capture), and `bms_output.txt` (serialized SEND MAP buffer) against the sibling `expected/` captures.

### CICS pseudo-conversation driver grammar

The driver grammar (defined in `expected/input_scenario.txt`) recognizes these directives:

- `SCENARIO: <id>` — begins a scenario block
- `DESCRIPTION: <free text>` — human-readable scenario description
- `STATE: <commarea-state-id>` — pre-condition commarea state (`entry`, `reentry_show_details`, `reentry_changes_ok_not_confirmed`, `reentry_changes_okayed_and_done`, `reentry_changes_okayed_lock_error`, `reentry_changes_okayed_but_failed`, `reentry_changes_not_ok`)
- `EIBCALEN: <integer>` — CICS-supplied commarea length (0 for fresh entry)
- `COMMAREA_HEX: <hex>` — inbound commarea bytes
- `MAP_INPUT: <FIELD> <VALUE>` — one per editable BMS field on the CACTUPA map (ACCTSID, ACSTTUS, OPNYEAR/OPNMON/OPNDAY, ACRDLIM, EXPYEAR/EXPMON/EXPDAY, ACSHLIM, RISYEAR/RISMON/RISDAY, ACURBAL, ACRCYCR, AADDGRP, ACRCYDB, ACSTNUM, ACTSSN1/ACTSSN2/ACTSSN3, DOBYEAR/DOBMON/DOBDAY, ACSTFCO, ACSFNAM, ACSMNAM, ACSLNAM, ACSADL1, ACSSTTE, ACSADL2, ACSZIPC, ACSCITY, ACSCTRY, ACSPH1A/ACSPH1B/ACSPH1C, ACSGOVT, ACSPH2A/ACSPH2B/ACSPH2C, ACSEFTC, ACSPFLG)
- `AID: <key>` — terminal AID key sent (ENTER, PF1-PF24, CLEAR, PA1, PA2, PA3)
- `MOCK_READ: <file> <key> <resp> <resp2>` — inject CICS READ result
- `MOCK_REWRITE: <file> <resp> <resp2>` — inject CICS REWRITE result (used to simulate the L4100 ROLLBACK path)
- `EXPECT_XCTL: <program>` — assert `EXEC CICS XCTL` was issued
- `EXPECT_SEND_MAP: <map> <mapset>` — assert `EXEC CICS SEND MAP` was issued
- `EXPECT_CURSOR: <field>` — assert cursor placement on output
- `EXPECT_FIELD: <FIELD> <VALUE> <ATTR>` — assert output field value + attribute
- `EXPECT_MSG: INFOMSG|ERRMSG <text>` — assert dynamic message (verbatim)
- `EXPECT_ABCODE: <4-char>` — assert `EXEC CICS ABEND` with code
- `EXPECT_READ: <file> <count>` — assert N CICS READ calls (no UPDATE)
- `EXPECT_READ_UPDATE: <file> <count>` — assert N CICS READ UPDATE calls
- `EXPECT_REWRITE: <file> <count>` — assert N CICS REWRITE calls
- `EXPECT_SYNCPOINT: COMMIT|ROLLBACK <count>` — assert N SYNCPOINT operations
- `EXPECT_ACCTDAT_UNCHANGED` — assert post-state `acctdata.txt` byte-equal to initial fixture
- `EXPECT_CUSTDAT_UNCHANGED` — assert post-state `custdata.txt` byte-equal to initial fixture
- `EXPECT_ACCTDAT_CHANGED <recno> <offset> <length> <hex>` — assert specific record-slot mutation
- `EXPECT_CUSTDAT_CHANGED <recno> <offset> <length> <hex>` — assert specific record-slot mutation
- `MASKED_PAN_LAST4: <last4>` — assert any PAN logging uses the `'************<last4>'` mask format (12 asterisks + 4 digits)

### Valid AIDs for COACTUPC

Per `app/cbl/COACTUPC.cbl:L905-L916`, four AID keys are accepted:

- **ENTER** (always valid) — submits the form; first press triggers the READ chain; subsequent press with edits triggers the validation pass
- **PF3** (always valid) — exits to the caller via `EXEC CICS XCTL` to `LIT-MENUPGM` (`'COMEN01C'`); preceded by `EXEC CICS SYNCPOINT` at L953 (normal commit, NOT rollback)
- **PF5** (valid ONLY when `ACUP-CHANGES-OK-NOT-CONFIRMED`) — saves changes via the `9600-WRITE-PROCESSING` paragraph; triggers both REWRITEs
- **PF12** (valid ONLY when NOT `ACUP-DETAILS-NOT-FETCHED`) — cancels current edits and reloads original data via `9000-READ-ACCT`

ALL OTHER AIDs (PF1, PF2, PF4, PF6-PF11, PF13-PF24, CLEAR, PA1, PA2, PA3) are silently coerced to ENTER per `app/cbl/COACTUPC.cbl:L914-L916` (the `IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE END-IF` remap). Scenarios that probe these AIDs MUST assert behavior identical to the ENTER scenario.

### Multi-pass READ + REWRITE flow with SYNCPOINT ROLLBACK compensation

COACTUPC operates as a 3-pass CICS pseudo-conversation. Each pass is one round-trip (RECEIVE MAP -> EVALUATE -> SEND MAP):

- **Pass 1** (initial READ): ENTER with `ACCTSID` (search key) -> `9000-READ-ACCT` orchestrates the 3-step READ chain. `9200-GETCARDXREF-BYACCT` reads `LIT-CARDXREFNAME-ACCT-PATH` (`'CXACAIX '` at L581-L582 — the alternate index over CARDXREF by account-id) yielding XREF-CUST-ID and XREF-CARD-NUM. `9300-GETACCTDATA-BYACCT` reads `LIT-ACCTFILENAME` (`'ACCTDAT '` at L573-L574) by account-id. `9400-GETCUSTDATA-BYCUST` reads `LIT-CUSTFILENAME` (`'CUSTDAT '` at L575-L576) by the customer-id retrieved from the xref. `9500-STORE-FETCHED-DATA` captures the ACUP-OLD-* snapshot for optimistic-concurrency comparison. State transitions to `ACUP-SHOW-DETAILS` (`'S'`); INFOMSG = `'Details of selected account shown above'`. NO `UPDATE` clause is used on any of the 3 reads in this pass.
- **Pass 2** (validation): ENTER with edited account and customer fields -> `1100-RECEIVE-MAP` (~387 lines) captures the user inputs. `1200-EDIT-MAP-INPUTS` runs ~25 field validations across all editable fields (account status, dates, monetary, group-id, FICO, SSN, names, phones, ZIP, country, EFT, primary-card-holder-ind). `1205-COMPARE-OLD-NEW` detects field differences vs. the ACUP-OLD-* snapshot. If all validations pass and at least one field differs, state transitions to `ACUP-CHANGES-OK-NOT-CONFIRMED` (`'N'`); INFOMSG = `'Changes validated.Press F5 to save'`.
- **Pass 3** (commit): PF5 -> `9600-WRITE-PROCESSING` (~217 lines) acquires write locks via `EXEC CICS READ UPDATE` on `LIT-ACCTFILENAME` followed by `EXEC CICS READ UPDATE` on `LIT-CUSTFILENAME`. `9700-CHECK-CHANGE-IN-REC` compares 15 ACCOUNT fields + 18 CUSTOMER fields against the ACUP-OLD-* snapshot for optimistic-concurrency detection. If unchanged, `EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) FROM(ACCT-UPDATE-RECORD)` at L4066 commits the account update; then `EXEC CICS REWRITE FILE(LIT-CUSTFILENAME) FROM(CUST-UPDATE-RECORD)` at L4086 commits the customer update. If the CUSTDAT REWRITE returns non-NORMAL, `SET LOCKED-BUT-UPDATE-FAILED TO TRUE` at L4099 and `EXEC CICS SYNCPOINT ROLLBACK` at L4100 reverses the L4066 ACCTDAT REWRITE within the transaction boundary, state transitions to `ACUP-CHANGES-OKAYED-BUT-FAILED` (`'F'`); INFOMSG = `'Changes unsuccessful. Please try again'`. On full success, state transitions to `ACUP-CHANGES-OKAYED-AND-DONE` (`'C'`); INFOMSG = `'Changes committed to database'`.

**CRITICAL UNIQUE BEHAVIOR**: `EXEC CICS SYNCPOINT ROLLBACK` at `app/cbl/COACTUPC.cbl:L4100` is the **SOLE occurrence** of `SYNCPOINT ROLLBACK` in the entire 28-program COBOL source tree under `app/cbl/` (verified by `grep -rn "SYNCPOINT ROLLBACK" app/cbl/`). The Java translation MUST implement this via try/finally with compensating writes per AAP §0.4.1, flagged as IMPLEMENTATION DECISION in `java/MIGRATION_NOTES.md` §1.6.

## Cross-reference to the Java test class

The consuming Java test class is `com.blitzy.carddemo.tests.golden.CoActUpCGoldenTest`, located at `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoActUpCGoldenTest.java`. It extends the abstract base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`.

The Java class under test is `com.blitzy.carddemo.application.account.CoActUpC` — located in the `account` subpackage of `carddemo-application`, NOT the `card` subpackage. Despite COACTUPC sharing a 4-letter prefix with the card-update program (COCRDUPC at `app/cbl/COCRDUPC.cbl`), COACTUPC manipulates ACCT-RECORD + CUST-RECORD (account-master + customer-master), so it belongs in `application.account` per AAP §0.4.1.

`CoActUpC` is annotated with `@CobolProgram("COACTUPC")` per the traceability mandate in AAP §0.3.1, citing the original PROGRAM-ID, source-file path (`app/cbl/COACTUPC.cbl`), and translation date.

The test class is `@Disabled` until ALL `expected/` artifacts (`README.md`, `input_scenario.txt`, `acctdata.txt`, `custdata.txt`, `stdout.txt`, `bms_output.txt`) are committed with real captured bytes per AAP §0.6.11.

The base-class helper `resolveExpectedOutputPath(String programDir, String fileName)` always routes to `src/test/resources/golden/<programDir>/expected/<fileName>` — NEVER to `input/`. This is why the harness has no consumer for any file under `input/` and why this folder is documentation-only.

## Capture procedure cross-reference

Per AAP §0.7.5, the capture procedure for COACTUPC fixtures is documented in `java/MIGRATION_NOTES.md` §1.6. Highlights:

- The capture procedure runs the COBOL reference implementation through a CICS region (or equivalent harness) using the COACTUPC.cbl + COACTUP.bms + COACTUP.CPY assets compiled and executed against the `app/data/ASCII/{acctdata,custdata,cardxref}.txt` initial-state fixtures, with fixed-clock injection for CURDATE / CURTIME determinism (the fixed-clock instant is documented in `MIGRATION_NOTES.md` §1.6).
- The capture procedure documents the SYNCPOINT ROLLBACK simulation harness — a CUSTDAT lock-injection technique that forces the second REWRITE to return non-NORMAL, exercising the L4099-L4100 rollback path. This is the ONLY way to deterministically reproduce the L4100 behavior because the COBOL source itself never spontaneously triggers it.
- The capture procedure documents the exact `bms_output.txt` serialization format: one per-scenario block per scenario in `input_scenario.txt`; each block contains the 1,920-byte CACTUPAO buffer (24 rows × 80 cols) plus a per-field emission line in the format `<FIELD> <ROW,COL> <LENGTH> <ATTR> <VALUE-hex>`.
- Until the capture procedure has been performed, all `expected/` data files (`acctdata.txt`, `custdata.txt`, `stdout.txt`, `bms_output.txt`) remain placeholders (single-line `# ` comment blocks documenting the expected layout). Until placeholders are replaced with real captured bytes, `CoActUpCGoldenTest` remains `@Disabled`.

## Behavioral invariants for COACTUPC

- **SOLE SYNCPOINT ROLLBACK in entire COBOL source tree** at `app/cbl/COACTUPC.cbl:L4100`. The Java translation MUST implement this via try/finally with compensating writes per AAP §0.4.1: snapshot ACCT-RECORD bytes immediately before the L4066 REWRITE; if the L4086 CUSTDAT REWRITE throws, issue a compensating REWRITE on ACCTDAT to restore the pre-update bytes; re-throw the original exception. Flag as IMPLEMENTATION DECISION in `java/MIGRATION_NOTES.md` §1.6.
- **Preserve typo `acctExpiraionDate`** — the Java field name reflects the COBOL `ACCT-EXPIRAION-DATE PIC X(10).` misspelling at `app/cpy/CVACT01Y.cpy:L11` per AAP §0.7.1 (Minimal Change Clause). Do NOT auto-correct to `acctExpirationDate`. Flag in `java/MIGRATION_NOTES.md` §1.6.
- **FICO range 300-850 (inclusive)** per `app/cbl/COACTUPC.cbl:L848-L849`: `88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850.` Values outside this range trigger ERRMSG = `'<field>: should be between 300 and 850'`.
- **SSN Part1 invalid values {0, 666, 900-999}** per `app/cbl/COACTUPC.cbl:L121-L123`: `88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999.` Values in this set trigger ERRMSG = `'<field>: should not be 000, 666, or between 900 and 999'`.
- **Duplicate 88-level names `DID-NOT-FIND-ACCT-IN-CARDXREF`** at `app/cbl/COACTUPC.cbl:L497-L498` AND L513-L514 (same condition name, different VALUE text — first occurrence text `'Did not find this account in account card xref file'`; second occurrence text `'Did not find this account in cards database'`). Per AAP §0.7.1, preserve verbatim — the COBOL compiler permits both declarations because they share the same parent group; the **last-encountered VALUE wins** at SET-time semantics. The Java translation MUST document the chosen disambiguation strategy in `java/MIGRATION_NOTES.md` §1.6 (either two distinct constants with disambiguating suffixes such as `_PRIMARY` and `_CARDS`, or a single constant with the L513 text winning).
- **Multi-file update**: both ACCTDAT REWRITE (L4066) and CUSTDAT REWRITE (L4086) MUST be issued atomically with rollback compensation. The Java translation snapshots ACCT-RECORD bytes before the first REWRITE so that a CUSTDAT REWRITE failure can compensate. CUSTDAT itself is never compensated because if its REWRITE fails, no CUSTDAT bytes have been written.
- **In-place REWRITE semantics**: file lengths NEVER change. Post-update `acctdata.txt` MUST be exactly 15,050 bytes; post-update `custdata.txt` MUST be exactly 25,050 bytes. Only the affected 300-byte (ACCT) or 500-byte (CUST) slots differ from the input fixture.
- **WS-EXIT-MESSAGE 14 trailing spaces** at `app/cbl/COACTUPC.cbl:L481-L482`: `88 WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '.` — the 14 trailing spaces inside the literal are part of the 75-byte WS-RETURN-MSG content and MUST be preserved verbatim in any captured `stdout.txt` or `bms_output.txt` per AAP §0.7.1.
- **`'Looks Good.... so far'` dead code** at `app/cbl/COACTUPC.cbl:L527-L528` (88-level `CODING-TO-BE-DONE`) — never SET by any paragraph but present in the WS-RETURN-MSG 88-level group. The four ASCII periods between `Good` and ` so far` are part of the literal — preserve byte-for-byte (NEVER replace with a Unicode ellipsis). Translate faithfully and flag in `java/MIGRATION_NOTES.md` §1.6.
- **Optimistic concurrency** per `9700-CHECK-CHANGE-IN-REC` at approximately L4109-L4192: compares **15 ACCOUNT fields** + **18 CUSTOMER fields** against the ACUP-OLD-* snapshot before issuing REWRITEs. If any field differs, SET `DATA-WAS-CHANGED-BEFORE-UPDATE`; abort REWRITEs; ERRMSG = `'Record changed by some one else. Please review'`.
- **PAN masking** per AAP §0.7.2: any logging of `XREF-CARD-NUM` to `stdout.txt` MUST mask all but the last 4 digits using the format `'************<last4>'` (12 asterisks + 4 digits). The COACTUP map itself does NOT display PAN (only ACCT-ID, SSN, phones, ZIP, names, etc.), so PAN appears only in the cross-reference chain and never on this screen; `bms_output.txt` therefore contains no PAN bytes.
- **`@CobolProgram("COACTUPC")` annotation** is required on the Java class under test per AAP §0.3.1.
- **Fixed-clock injection** via `ScopedValue<Clock>` per AAP §0.6.6 so that CURDATE / CURTIME render reproducibly. The fixed-clock value used for captures is documented in `java/MIGRATION_NOTES.md` §1.6.

## Source lineage

The following 10 source files are REFERENCE only — they are NOT modified by this refactor (AAP §0.2.2):

- `app/cbl/COACTUPC.cbl` (4,236 lines) — COBOL source. `PROGRAM-ID. COACTUPC.` at L22; `LIT-THISTRANID 'CAUP'` at L535-L536; `LIT-THISMAPSET 'COACTUP '` at L537-L538; `LIT-THISMAP 'CACTUPA'` at L539-L540; `LIT-ACCTFILENAME 'ACCTDAT '` at L573-L574; `LIT-CUSTFILENAME 'CUSTDAT '` at L575-L576; `LIT-CARDXREFNAME-ACCT-PATH 'CXACAIX '` at L581-L582; ~30 verbatim error/info messages in the WS-RETURN-MSG 88-level group at L463-L528; ACUP-CHANGE-ACTION 7-state machine at L654-L668; AID-key validation at L905-L916; 9000-READ-ACCT chain in the 9200/9300/9400/9500 paragraph cluster; 9600-WRITE-PROCESSING with `EXEC CICS REWRITE FILE(LIT-ACCTFILENAME)` at L4066, `EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)` at L4086, and `EXEC CICS SYNCPOINT ROLLBACK` at L4100; 9700-CHECK-CHANGE-IN-REC for optimistic-concurrency detection.
- `app/bms/COACTUP.bms` (512 lines) — BMS map source; MAPSET `COACTUP`, MAP `CACTUPA`, 24x80 with `CTRL=(FREEKB)` declared at L26-L29; 128 `DFHMDF` entries (~42 named fields + literal labels); `ACCTSID DFHMDF ATTRB=(IC,UNPROT) POS=(5,38) LENGTH=11` at L84-L86 (the IC attribute marks the initial cursor position).
- `app/cpy-bms/COACTUP.CPY` — symbolic map copybook; defines `CACTUPAI` (input record) and `CACTUPAO REDEFINES CACTUPAI` (output record).
- `app/cpy/CVACT01Y.cpy` (20 lines) — 300-byte ACCOUNT-RECORD layout (13 fields including 5 BigDecimal S9(10)V99 + 3 date X(10) + `ACCT-EXPIRAION-DATE` typo at L11 + FILLER X(178)).
- `app/cpy/CVCUS01Y.cpy` (26 lines) — 500-byte CUSTOMER-RECORD layout (18 fields + FILLER X(168)).
- `app/cpy/CVACT03Y.cpy` (11 lines) — 50-byte CARD-XREF-RECORD layout (3 fields + FILLER X(14)).
- `app/cpy/COCOM01Y.cpy` — CARDDEMO-COMMAREA structure (general / customer / account / card / more sub-records; 88-level state machines for UserType, PgmContext).
- `app/cpy/CVCRD01Y.cpy` — CC-WORK-AREAS (AID-key 88-conditions for CCARD-AID-ENTER, CCARD-AID-CLEAR, CCARD-AID-PA1, CCARD-AID-PA2, CCARD-AID-PFK01..PFK12; REDEFINES on CC-ACCT-ID, CC-CARD-NUM, CC-CUST-ID).
- `app/data/ASCII/acctdata.txt` — 15,050 bytes; ACCTDAT initial-state fixture (REFERENCE; loaded via classpath; NOT copied here per AAP §0.4.1).
- `app/data/ASCII/custdata.txt` — 25,050 bytes; CUSTDAT initial-state fixture (REFERENCE; classpath).
- `app/data/ASCII/cardxref.txt` — 1,850 bytes; CARDXREF/CXACAIX initial-state fixture (REFERENCE; classpath).

## Authority references

- AAP §0.1.1 — byte-for-byte parity mandate; COBOL source tree under `app/` is UNCHANGED.
- AAP §0.2.1 — in-scope: golden-record fixtures (this README under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard).
- AAP §0.2.2 — out-of-scope: `app/` tree NOT modified; ASCII fixtures NOT copied.
- AAP §0.3.1 — harness directory convention `<program>/input/` + `<program>/expected/`; `@CobolProgram` annotation mandate.
- AAP §0.4.1 — COACTUPC -> CoActUpC in `application.account` subpackage; ASCII fixtures read via classpath, NOT copied; SYNCPOINT ROLLBACK IMPLEMENTATION DECISION; typo `acctExpiraionDate` preservation.
- AAP §0.6.4 — `java.time` mandate for all date / time values.
- AAP §0.6.5 — `java.nio.file` mandate; byte-for-byte round-trip parse / encode invariant.
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely in new code; fixed-clock injection.
- AAP §0.6.11 — non-negotiable PR gate; `@Disabled` scaffolding pattern until COBOL captures are committed.
- AAP §0.6.12 — architectural override: no Spring, no PostgreSQL, no Hibernate, no Spring Batch, no Flyway, no Spring Security.
- AAP §0.7.1 — Minimal Change Clause; preserve verbatim messages; preserve `acctExpiraionDate` typo; preserve duplicate `DID-NOT-FIND-ACCT-IN-CARDXREF` 88-level names; preserve WS-EXIT-MESSAGE 14 trailing spaces; preserve `'Looks Good.... so far'` dead code.
- AAP §0.7.2 — PAN masking (last 4 digits only); no card PAN in logs.
- AAP §0.7.4 — no JEP preview features (502, 505, 507); no `--enable-preview`; JEP 512 utilities-only; no `default` branches in pattern-matching switches.
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.

## DO NOT add files here

- **NO `.cbl` files** — the COBOL source remains exclusively under `app/cbl/`. This README is documentation about COACTUPC, not a copy of it.
- **NO copied ASCII fixtures** (`acctdata.txt`, `custdata.txt`, `cardxref.txt`) — these are loaded via classpath from `app/data/ASCII/` per AAP §0.4.1. Copying creates drift risk between the source-of-truth and the test-tree duplicates.
- **NO `input_scenario.txt`** — the synthesized CICS pseudo-conversation driver script lives in `../expected/input_scenario.txt` per the established harness convention. The base class `resolveExpectedOutputPath(...)` helper resolves to `expected/`, never `input/`.
- **NO `.bin` or pre-built binary artifacts** — golden-record fixtures are plain-text byte sequences captured during reference COBOL execution; never pre-built or compressed.
- **NO Spring / Spring Boot / Spring Batch / Spring Security / Hibernate / Flyway / Liquibase configuration** — these frameworks are architecturally forbidden by AAP §0.6.12. Any Spring `@Configuration`, `application.yml`, or related file is a defect.
- **NO PostgreSQL / MySQL / Oracle / HikariCP / JDBC pool configuration** — file-based default per AAP §0.6.12 (the `carddemo-adapter-db` module is empty by default).
- **NO Docker / Helm / Terraform / Kubernetes manifests** — deployment orchestration is out of scope per AAP §0.2.2.
- **NO AWS / Azure / GCP service configuration** (S3, SQS, SNS, Lambda, RDS, etc.) — cloud services are out of scope.
- **NO Apache 2.0 license boilerplate** copied from COBOL source files — only the `app/` tree retains the license header.
- **NO COBOL source footer version banner** — these footer banners appear at the end of each `.cbl` and `.cpy` file under `app/` and MUST NOT be reproduced anywhere in this folder.
- **NO unmasked 16-digit PANs anywhere** — even in documentation comments. Use the `'************<last4>'` mask format per AAP §0.7.2.
- **NO references to `java.util.Date`, `java.util.Calendar`, `java.text.SimpleDateFormat`** — use `java.time` per AAP §0.6.4.
- **NO references to `java.io.File`** — use `java.nio.file` per AAP §0.6.5.
- **NO references to `double` / `float` for monetary values** — use `java.math.BigDecimal` with `MathContext.DECIMAL128` per AAP §0.7.4.
- **NO references to `ThreadLocal`** — use `ScopedValue` per AAP §0.6.6.
- **NO `--enable-preview` references** — JEP 502 / 505 / 507 preview features are forbidden per AAP §0.7.4.

## Coordination with sibling `expected/` folder

The sibling `java/carddemo-tests/src/test/resources/golden/coactupc/expected/README.md` is the **authoritative** per-program contract for COACTUPC golden-record parity. THIS `input/README.md` is a navigational pointer to that authoritative contract — it explains the documentation-only nature of the `input/` folder and references the sibling for the full fixture specification.

Cross-folder consistency requirements:

- All AAP citations and source line-number citations in THIS file MUST agree with the sibling `../expected/README.md` and `../expected/input_scenario.txt`.
- All verbatim COBOL message texts (e.g., `'Details of selected account shown above'`, `'Changes validated.Press F5 to save'`, `'Changes committed to database'`, `'Changes unsuccessful. Please try again'`, `'PF03 pressed.Exiting              '` with its 14 trailing spaces, `'Looks Good.... so far'` with its four ASCII periods) MUST be byte-identical across both folders.
- The scenario count and IDs enumerated in `../expected/input_scenario.txt` MUST match the `EXPECT_*` assertions paired with each scenario's captured `../expected/{acctdata,custdata,stdout,bms_output}.txt`.
- Any update to the COACTUPC fixture contract MUST be applied to BOTH folders atomically — divergence between `input/README.md` and `expected/README.md` violates AAP §0.1.1 byte-for-byte parity expectations.
