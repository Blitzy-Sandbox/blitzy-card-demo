# CBTRN01C (Daily Transaction Validator) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is intentionally documentation-only and contains NO fixture data files. CBTRN01C is a **validation-only program** (NOT a posting engine, despite its `CBTRN*` prefix and the misleading Apache header comment at `app/cbl/CBTRN01C.cbl:L1-L21` which hints at posting) translated to Java `com.blitzy.carddemo.application.transaction.CbTrn01C` per AAP §0.4.1. The COBOL source resides at `app/cbl/CBTRN01C.cbl` (491 lines; `PROGRAM-ID. CBTRN01C.` at `app/cbl/CBTRN01C.cbl:L23`; `AUTHOR. AWS.` at `app/cbl/CBTRN01C.cbl:L24`). NO JCL driver exists for CBTRN01C in `app/jcl/` (verified absence via `grep -l 'CBTRN01C' app/jcl/*.jcl` returning empty); the program is callable via direct method invocation but is not scheduled by any z/OS JCL. All five readable ASCII input fixtures are REFERENCED via classpath from `app/data/ASCII/` and are NEVER COPIED into this folder per AAP §0.4.1. The single conceptual output (`stdout.txt`) lives in the sibling `../expected/` folder.

## Why this folder is documentation-only

1. CBTRN01C is a **read-only validation pass** invoked by direct method call (no JCL driver). The program reads only in three paragraphs: `1000-DALYTRAN-GET-NEXT` at `app/cbl/CBTRN01C.cbl:L202` (sequential READ on DALYTRAN-FILE), `2000-LOOKUP-XREF` at `app/cbl/CBTRN01C.cbl:L227` (random READ on XREF-FILE by FD-XREF-CARD-NUM), and `3000-READ-ACCOUNT` at `app/cbl/CBTRN01C.cbl:L241` (random READ on ACCOUNT-FILE by FD-ACCT-ID). CUSTOMER-FILE, CARD-FILE, and TRANSACT-FILE are opened (at `app/cbl/CBTRN01C.cbl:L271`, `app/cbl/CBTRN01C.cbl:L307`, and `app/cbl/CBTRN01C.cbl:L343`) and closed (at `app/cbl/CBTRN01C.cbl:L379`, `app/cbl/CBTRN01C.cbl:L415`, and `app/cbl/CBTRN01C.cbl:L451`) but are NEVER READ — preserved verbatim per AAP §0.7.1 (Minimal Change Clause).

2. The five ASCII input fixtures (DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE) are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED into this folder per AAP §0.4.1. Copying them would (a) double storage footprint, (b) introduce drift risk between the COBOL reference implementation and the Java parity harness, and (c) violate the AAP §0.2.2 immutability mandate for the `app/` tree.

3. The consuming Java test class `com.blitzy.carddemo.tests.golden.CbTrn01CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) resolves all expected-output paths through the base class helper `resolveExpectedOutputPath("cbtrn01c", fileName)` which routes to `src/test/resources/golden/cbtrn01c/expected/`. Input fixture paths route to `app/data/ASCII/` via classpath reference. Therefore NO fixture data file lives in this `input/` folder.

4. The harness convention per AAP §0.3.1 mandates the `<program>/input/` + `<program>/expected/` pairing per program for discoverability and parallel structure across all program test folders. Removing the `input/` folder would break this symmetry; keeping it documented with only this README preserves the convention while making the absence of data files self-documenting.

5. The capture procedure for `../expected/stdout.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until that expected fixture is captured and committed, `CbTrn01CGoldenTest` is `@Disabled` per AAP §0.6.11.

Cross-references in the sibling `../expected/` folder:

- `../expected/README.md` — Authoritative byte-for-byte contract documenting the single output.
- `../expected/stdout.txt` — Captured DISPLAY output sequence (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data referenced from app/data/ASCII/ and ../expected/)

CBTRN01C opens six conceptual input files but only reads three in the main loop. The five readable inputs are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED into this folder per AAP §0.4.1. The single output (`stdout.txt`) lives in the sibling `../expected/` folder. This section documents the conceptual contract; data location is documented in subsection 2.4.

### FILE-CONTROL SELECT clauses + FD record layouts

The six files declared by `app/cbl/CBTRN01C.cbl:L28-L62` are described below; all FD declarations come from `app/cbl/CBTRN01C.cbl:L66-L94`.

| SELECT Name | FD Record Length | ORG / ACCESS | Key Field | ASCII Fixture (REFERENCED only) | Used in Main Loop? |
|---|---|---|---|---|---|
| DALYTRAN-FILE | 350 bytes (FD-TRAN-ID PIC X(16) + FD-CUST-DATA PIC X(334)) | SEQUENTIAL | (none) | `app/data/ASCII/dailytran.txt` (105,300 bytes) | YES |
| CUSTOMER-FILE | 500 bytes (FD-CUST-ID PIC 9(09) + FD-CUST-DATA PIC X(491)) | INDEXED RANDOM | FD-CUST-ID (9 bytes) | `app/data/ASCII/custdata.txt` (25,050 bytes) | NO (opened/closed only) |
| XREF-FILE | 50 bytes (FD-XREF-CARD-NUM PIC X(16) + FD-XREF-DATA PIC X(34)) | INDEXED RANDOM | FD-XREF-CARD-NUM (16 bytes) | `app/data/ASCII/cardxref.txt` (1,850 bytes) | YES |
| CARD-FILE | 150 bytes (FD-CARD-NUM PIC X(16) + FD-CARD-DATA PIC X(134)) | INDEXED RANDOM | FD-CARD-NUM (16 bytes) | `app/data/ASCII/carddata.txt` (7,550 bytes) | NO (opened/closed only) |
| ACCOUNT-FILE | 300 bytes (FD-ACCT-ID PIC 9(11) + FD-ACCT-DATA PIC X(289)) | INDEXED RANDOM | FD-ACCT-ID (11 bytes) | `app/data/ASCII/acctdata.txt` (15,050 bytes) | YES |
| TRANSACT-FILE | 350 bytes (FD-TRANS-ID PIC X(16) + FD-ACCT-DATA PIC X(334)) | INDEXED RANDOM | FD-TRANS-ID (16 bytes) | (no ASCII fixture; never read) | NO (opened/closed only) |

Copybook lineage:

- DALYTRAN-FILE -> `app/cpy/CVTRA06Y.cpy` (DALYTRAN-RECORD, 350 bytes, 14 fields); COPY at `app/cbl/CBTRN01C.cbl:L99`.
- CUSTOMER-FILE -> `app/cpy/CVCUS01Y.cpy` (CUSTOMER-RECORD, 500 bytes); COPY at `app/cbl/CBTRN01C.cbl:L104` (record never parsed in CBTRN01C).
- XREF-FILE -> `app/cpy/CVACT03Y.cpy` (CARD-XREF-RECORD, 50 bytes); COPY at `app/cbl/CBTRN01C.cbl:L109`.
- CARD-FILE -> `app/cpy/CVACT02Y.cpy` (CARD-RECORD, 150 bytes); COPY at `app/cbl/CBTRN01C.cbl:L114` (record never parsed in CBTRN01C).
- ACCOUNT-FILE -> `app/cpy/CVACT01Y.cpy` (ACCOUNT-RECORD, 300 bytes); COPY at `app/cbl/CBTRN01C.cbl:L119`.
- TRANSACT-FILE -> `app/cpy/CVTRA05Y.cpy` (TRAN-RECORD, 350 bytes); COPY at `app/cbl/CBTRN01C.cbl:L124` (record never parsed in CBTRN01C).

### Validation flow paragraphs (15 paragraphs)

The PROCEDURE DIVISION begins at `app/cbl/CBTRN01C.cbl:L154`; the main loop runs at `app/cbl/CBTRN01C.cbl:L164-L186` (`PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'`).

**File-open paragraphs (six, executed before the loop):**

- `0000-DALYTRAN-OPEN` at `app/cbl/CBTRN01C.cbl:L252` — OPEN INPUT DALYTRAN-FILE
- `0100-CUSTFILE-OPEN` at `app/cbl/CBTRN01C.cbl:L271` — OPEN INPUT CUSTOMER-FILE (opened but never read)
- `0200-XREFFILE-OPEN` at `app/cbl/CBTRN01C.cbl:L289` — OPEN INPUT XREF-FILE
- `0300-CARDFILE-OPEN` at `app/cbl/CBTRN01C.cbl:L307` — OPEN INPUT CARD-FILE (opened but never read)
- `0400-ACCTFILE-OPEN` at `app/cbl/CBTRN01C.cbl:L325` — OPEN INPUT ACCOUNT-FILE
- `0500-TRANFILE-OPEN` at `app/cbl/CBTRN01C.cbl:L343` — OPEN INPUT TRANSACT-FILE (opened but never read)

**Per-record paragraphs (three, executed inside the loop):**

- `1000-DALYTRAN-GET-NEXT` at `app/cbl/CBTRN01C.cbl:L202` — READ DALYTRAN-FILE INTO DALYTRAN-RECORD; AT END (status '10') sets END-OF-DAILY-TRANS-FILE='Y'
- `2000-LOOKUP-XREF` at `app/cbl/CBTRN01C.cbl:L227` — MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM; READ XREF-FILE; INVALID KEY sets WS-XREF-READ-STATUS=4 + DISPLAY at `app/cbl/CBTRN01C.cbl:L232`; NOT INVALID KEY emits 4 DISPLAY lines at `app/cbl/CBTRN01C.cbl:L235-L238`
- `3000-READ-ACCOUNT` at `app/cbl/CBTRN01C.cbl:L241` — MOVE ACCT-ID TO FD-ACCT-ID; READ ACCOUNT-FILE; INVALID KEY sets WS-ACCT-READ-STATUS=4 + DISPLAY at `app/cbl/CBTRN01C.cbl:L246`; NOT INVALID KEY emits 1 DISPLAY line at `app/cbl/CBTRN01C.cbl:L249`

**File-close paragraphs (six, executed after the loop):**

- `9000-DALYTRAN-CLOSE` at `app/cbl/CBTRN01C.cbl:L361` — CLOSE DALYTRAN-FILE; CONTAINS COPY-PASTE BUG at `app/cbl/CBTRN01C.cbl:L372-L373` (DISPLAY `'ERROR CLOSING CUSTOMER FILE'` wrong text + MOVE CUSTFILE-STATUS wrong variable)
- `9100-CUSTFILE-CLOSE` at `app/cbl/CBTRN01C.cbl:L379` — CLOSE CUSTOMER-FILE
- `9200-XREFFILE-CLOSE` at `app/cbl/CBTRN01C.cbl:L397` — CLOSE XREF-FILE
- `9300-CARDFILE-CLOSE` at `app/cbl/CBTRN01C.cbl:L415` — CLOSE CARD-FILE
- `9400-ACCTFILE-CLOSE` at `app/cbl/CBTRN01C.cbl:L433` — CLOSE ACCOUNT-FILE
- `9500-TRANFILE-CLOSE` at `app/cbl/CBTRN01C.cbl:L451` — CLOSE TRANSACT-FILE

**Utility paragraphs (lack 4-digit numeric prefix; preserve `Z-` naming per AAP §0.7.1):**

- `Z-ABEND-PROGRAM` at `app/cbl/CBTRN01C.cbl:L469` — DISPLAY `'ABENDING PROGRAM'` at `app/cbl/CBTRN01C.cbl:L470`; MOVE 0 to TIMING; MOVE 999 to ABCODE; CALL `'CEE3ABD'` at `app/cbl/CBTRN01C.cbl:L473`
- `Z-DISPLAY-IO-STATUS` at `app/cbl/CBTRN01C.cbl:L476` — emits `'FILE STATUS IS: NNNN'` IO-STATUS-04 at `app/cbl/CBTRN01C.cbl:L483` and `app/cbl/CBTRN01C.cbl:L487` (both IF/ELSE branches)

CBTRN01C performs NO posting (no `2000-POST-TRANSACTION` paragraph exists), NO writes, NO rewrites, NO inserts. The Java translation MUST faithfully open and close CUSTFILE/CARDFILE/TRANFILE even though they are never accessed in the main loop. Skipping the open/close calls would silently change observable behavior under failure conditions.

### Validation diagnostics (NO reject codes; DISPLAY only)

Unlike CBTRN02C which emits structured 80-byte reject trailers with 5 numeric reject codes (100, 101, 102, 103, 109), CBTRN01C emits ONLY plain-text DISPLAY diagnostics to stdout. There is no reject file, no reject record layout, no validation trailer record, no zero-padded reason code, no description with trailing spaces. There is no per-record reject sink; failed validations are emitted directly to stdout via DISPLAY statements.

| Trigger | DISPLAY Verbatim Bytes | Source Paragraph | Source Line |
|---|---|---|---|
| XREF READ INVALID KEY | `'INVALID CARD NUMBER FOR XREF'` | 2000-LOOKUP-XREF | L232 |
| XREF READ NOT INVALID KEY (4 messages) | `'SUCCESSFUL READ OF XREF'`, `'CARD NUMBER: '` XREF-CARD-NUM, `'ACCOUNT ID : '` XREF-ACCT-ID, `'CUSTOMER ID: '` XREF-CUST-ID | 2000-LOOKUP-XREF | L235-L238 |
| ACCT READ INVALID KEY | `'INVALID ACCOUNT NUMBER FOUND'` | 3000-READ-ACCOUNT | L246 |
| ACCT READ NOT INVALID KEY | `'SUCCESSFUL READ OF ACCOUNT FILE'` | 3000-READ-ACCOUNT | L249 |
| MAIN-PARA ACCT-not-found (after successful XREF) | `'ACCOUNT '` ACCT-ID `' NOT FOUND'` | MAIN-PARA | L178 |
| MAIN-PARA XREF-not-found (skipping transaction) | `'CARD NUMBER '` DALYTRAN-CARD-NUM `' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'` DALYTRAN-ID | MAIN-PARA | L181-L183 |

Critical idiosyncrasies:

- The literal at `app/cbl/CBTRN01C.cbl:L181` is `'CARD NUMBER '` (NO colon, single trailing space); the literal at `app/cbl/CBTRN01C.cbl:L236` is `'CARD NUMBER: '` (HAS colon, trailing space). Both forms are preserved verbatim per AAP §0.7.1.
- The literal at `app/cbl/CBTRN01C.cbl:L237` is `'ACCOUNT ID : '` with a SPACE BEFORE the colon — a deliberate-looking but inconsistent choice.
- The literal at `app/cbl/CBTRN01C.cbl:L238` is `'CUSTOMER ID: '` with NO SPACE before the colon — inconsistent with `app/cbl/CBTRN01C.cbl:L237`. Both forms are preserved verbatim per AAP §0.7.1.
- The MAIN-PARA conditional at `app/cbl/CBTRN01C.cbl:L173-L184` evaluates WS-XREF-READ-STATUS first; if XREF failed (status=4), the PERFORM 3000-READ-ACCOUNT at `app/cbl/CBTRN01C.cbl:L176` is SKIPPED and ONLY the L181-L183 `'CARD NUMBER ... COULD NOT BE VERIFIED'` message is emitted. If XREF succeeded but ACCT failed (WS-ACCT-READ-STATUS=4), the `app/cbl/CBTRN01C.cbl:L178` `'ACCOUNT ... NOT FOUND'` message is emitted instead. The two failure paths are MUTUALLY EXCLUSIVE per record.
- **DALYTRAN-RECORD double-DISPLAY pattern**: when XREF or ACCT lookup fails, the offending DALYTRAN-RECORD is shown TWICE on stdout — once at `app/cbl/CBTRN01C.cbl:L168` (unconditional per-iteration record dump) and again indirectly via the `app/cbl/CBTRN01C.cbl:L181-L183` diagnostic (XREF failure) or `app/cbl/CBTRN01C.cbl:L178` diagnostic (ACCT failure), which include DALYTRAN-CARD-NUM and ACCT-ID substrings.
- **COPY-PASTE BUG at `app/cbl/CBTRN01C.cbl:L372-L373`** in paragraph 9000-DALYTRAN-CLOSE: the DISPLAY emits `'ERROR CLOSING CUSTOMER FILE'` (wrong file name — should say DAILY TRANSACTION) and the MOVE at `app/cbl/CBTRN01C.cbl:L373` references CUSTFILE-STATUS (wrong status variable — should be DALYTRAN-STATUS). PRESERVE verbatim per AAP §0.7.1. Do NOT "fix" this bug.

### Conceptual fixture data (locations and source-of-truth)

The six conceptual inputs and their locations:

| Conceptual fixture | Actual location | Role |
|---|---|---|
| DALYTRAN sequential | `app/data/ASCII/dailytran.txt` (105,300 bytes; REFERENCED via classpath) | Primary input — transaction records to validate |
| CUSTFILE indexed | `app/data/ASCII/custdata.txt` (25,050 bytes; REFERENCED via classpath) | Opened but NEVER read |
| XREFFILE indexed | `app/data/ASCII/cardxref.txt` (1,850 bytes; REFERENCED via classpath) | Card-to-account cross-reference (READ only) |
| CARDFILE indexed | `app/data/ASCII/carddata.txt` (7,550 bytes; REFERENCED via classpath) | Opened but NEVER read |
| ACCTFILE indexed | `app/data/ASCII/acctdata.txt` (15,050 bytes; REFERENCED via classpath) | Account master (READ only) |
| TRANSACT-FILE indexed | (no ASCII fixture in `app/data/ASCII/`) | Opened but NEVER read — Java port adapter for TransactionRepository MUST tolerate empty/absent backing file |

The single conceptual output lives in `../expected/`:

| Output | Sibling location | Role |
|---|---|---|
| Captured DISPLAY | `../expected/stdout.txt` | Sequence of COBOL DISPLAY outputs (CAPTURE PLACEHOLDER) |

Per AAP §0.4.1, no copy of any of these six files is created in this `input/` folder.

## Cross-reference to the Java test class

The Java program under test is `com.blitzy.carddemo.application.transaction.CbTrn01C` (per AAP §0.4.1, the `transaction` subpackage). The consuming Java test class is `com.blitzy.carddemo.tests.golden.CbTrn01CGoldenTest` extending the base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test source path is `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbTrn01CGoldenTest.java`.

Per AAP §0.3.2 hexagonal architecture, CbTrn01C accepts six constructor-injected repository ports — three ACTIVE (read in the main loop) and three UNUSED (opened/closed only, preserved per AAP §0.7.1 Minimal Change Clause):

- `DailyTransactionRepository` — sequential reader bound to `app/data/ASCII/dailytran.txt` (ACTIVE)
- `CustomerRepository` — indexed reader bound to `app/data/ASCII/custdata.txt` (UNUSED — opened/closed only)
- `CardXrefRepository` — indexed reader bound to `app/data/ASCII/cardxref.txt` (ACTIVE)
- `CardRepository` — indexed reader bound to `app/data/ASCII/carddata.txt` (UNUSED — opened/closed only)
- `AccountRepository` — indexed reader bound to `app/data/ASCII/acctdata.txt` (ACTIVE)
- `TransactionRepository` — indexed port (UNUSED — opened/closed only; no ASCII fixture)

Override summary for `CbTrn01CGoldenTest`:

- `programClass()` returns `com.blitzy.carddemo.application.transaction.CbTrn01C.class`
- `inputFile()` returns `resolveAppDataPath("dailytran.txt")` — primary sequential input
- `auxiliaryInputs()` returns `List.of(resolveAppDataPath("cardxref.txt"), resolveAppDataPath("acctdata.txt"), resolveAppDataPath("custdata.txt"), resolveAppDataPath("carddata.txt"))` — three lookups plus two opened-but-unused inputs
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cbtrn01c", "stdout.txt")` — single output under `../expected/stdout.txt`
- NO `expectedOutputs()` override needed (single-element default from `GoldenRecordTest` base class suffices)
- NO `runProgram(...)` override needed (default base-class hook handles 6-port constructor wiring)

All input fixtures route to `app/data/ASCII/`; all expected fixtures route to `../expected/`. **None route to this `input/` folder.** This is the architectural reason the folder is empty.

The test class MUST be annotated:

```text
@Disabled("Awaiting COBOL CBTRN01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md for the regeneration procedure. Verify the double-DISPLAY pattern on validation failures: DALYTRAN-RECORD is emitted twice (once at the error site, once at the diagnostic display).")
```

per AAP §0.6.11 until `../expected/stdout.txt` is captured and committed.

Sequential execution mandate per AAP §0.6.6: virtual threads are FORBIDDEN inside CbTrn01C because DISPLAY output ordering is observable; any reordering of `DISPLAY DALYTRAN-RECORD` emissions or XREF/ACCT diagnostic lines breaks byte-for-byte parity. The Java translation MUST execute the main loop serially in the same DALYTRAN read order.

No-deterministic-clock note: unlike CBTRN02C which calls FUNCTION CURRENT-DATE in Z-GET-DB2-FORMAT-TIMESTAMP, CBTRN01C performs NO clock-reading. NO injected `Clock` is required for reproducible captures.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBTRN01C) for the COBOL build/run path used to capture `../expected/stdout.txt`. Per AAP §0.7.5 the procedure is documented in `MIGRATION_NOTES.md` rather than inline in this README to keep the per-program documentation focused on the conceptual contract.

The capture procedure MUST run the COBOL CBTRN01C program against the same five `app/data/ASCII/*.txt` fixtures that the Java test will reference (TRANSACT-FILE may be empty or absent since it is never read). The capture is naturally deterministic — no clock injection required. Until the capture is performed and committed, `../expected/stdout.txt` holds placeholder content and `CbTrn01CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **Byte-for-byte parity is the contract** (AAP §0.1.1, §0.6.11): every byte of the captured `stdout.txt` MUST equal the corresponding byte of the Java translation's captured stdout.
- **Sequential execution is mandated** (AAP §0.6.6): virtual-thread fan-out is FORBIDDEN for CBTRN01C because DISPLAY output ordering is observable. Any reordering of the per-iteration DALYTRAN record dump or the XREF/ACCT diagnostic lines breaks parity.
- **NO posting, NO writes, NO rewrites** in CBTRN01C: the program is a read-only validation pass. There is no `2000-POST-TRANSACTION` paragraph, no WRITE statement, no REWRITE statement, no DELETE statement.
- **Opened-but-unused boilerplate preserved** (AAP §0.7.1): CUSTOMER-FILE, CARD-FILE, and TRANSACT-FILE are opened (`app/cbl/CBTRN01C.cbl:L271`, `app/cbl/CBTRN01C.cbl:L307`, `app/cbl/CBTRN01C.cbl:L343`) and closed (`app/cbl/CBTRN01C.cbl:L379`, `app/cbl/CBTRN01C.cbl:L415`, `app/cbl/CBTRN01C.cbl:L451`) without being read. The Java translation MUST faithfully execute the open/close calls.
- **Idiosyncratic DISPLAY messages** (AAP §0.7.1): L181 `'CARD NUMBER '` (no colon) vs L236 `'CARD NUMBER: '` (with colon); L237 `'ACCOUNT ID : '` (space before colon) vs L238 `'CUSTOMER ID: '` (no space before colon); all preserved exactly.
- **DALYTRAN-RECORD double-DISPLAY on errors** (AAP §0.7.1): the failing record's DALYTRAN-CARD-NUM and DALYTRAN-ID appear twice in stdout — once via `app/cbl/CBTRN01C.cbl:L168` record dump and once via `app/cbl/CBTRN01C.cbl:L181-L183` diagnostic (XREF failure) or `app/cbl/CBTRN01C.cbl:L178` diagnostic (ACCT failure).
- **COPY-PASTE BUG at `app/cbl/CBTRN01C.cbl:L372-L373` PRESERVED** (AAP §0.7.1): in 9000-DALYTRAN-CLOSE the COBOL emits `'ERROR CLOSING CUSTOMER FILE'` (wrong text) and references CUSTFILE-STATUS (wrong variable). Do NOT "fix" in Java translation.
- **Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS lack 4-digit numeric prefix** (AAP §0.7.1): the `Z-` prefix convention is preserved in the Java translation's paragraph annotations.
- **DAILY vs DALYTRAN spelling inconsistency** (AAP §0.7.1): `app/cbl/CBTRN01C.cbl:L263` emits `'ERROR OPENING DAILY TRANSACTION FILE'` even though the SELECT name is DALYTRAN-FILE.
- **FD-CUST-DATA name reuse** (AAP §0.7.1): both DALYTRAN-FILE FD at `app/cbl/CBTRN01C.cbl:L66-L69` and CUSTOMER-FILE FD at `app/cbl/CBTRN01C.cbl:L71-L74` declare a field named `FD-CUST-DATA`.
- **NO `BigDecimal` arithmetic** in CBTRN01C: DALYTRAN-AMT (PIC S9(09)V99) is parsed/encoded via the `Decimals` utility per AAP §0.6.1 for the L168 record dump but NEVER added, subtracted, multiplied, or divided.
- **`java.time` only for dates and timestamps** (AAP §0.6.4): DALYTRAN-ORIG-TS and DALYTRAN-PROC-TS (PIC X(26)) are stored as String fields since CBTRN01C performs no date comparison or arithmetic; legacy date APIs are FORBIDDEN.
- **`java.nio.file` only for file I/O** (AAP §0.6.5): legacy `java.io.File` is FORBIDDEN. The five referenced fixtures are accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `SeekableByteChannel`.
- **EBCDIC IBM-1047 default codepage** (AAP §0.6.5): default codepage is `Charset.forName("IBM-1047")`; ASCII fixtures use `Charset.forName("US-ASCII")` for test convenience; per-file codepage overrides are configurable via `application.properties`.
- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch run context (run ID, processing date, tenant) flows through `ScopedValue.where(...).run(...)`. NO `ThreadLocal` in new code.
- **`@CobolProgram("CBTRN01C")` annotation required** (AAP §0.7.1): the Java class `CbTrn01C` MUST carry the `@CobolProgram` annotation citing the original PROGRAM-ID, source path `app/cbl/CBTRN01C.cbl`, and translation date.
- **No card PAN logged in full in production sinks** (AAP §0.7.2): production logs MUST mask all but the last 4 digits of DALYTRAN-CARD-NUM and XREF-CARD-NUM. Test fixtures preserve COBOL behavior verbatim via a test-only sink; production logging masks via a separate sink.
- **Pattern-matching switch exhaustiveness** (AAP §0.7.4): any switch on `LookupOutcome { Success, NotFound }` (sealed hierarchy translating WS-XREF-READ-STATUS / WS-ACCT-READ-STATUS) uses pattern-matching with compiler-enforced exhaustiveness; no fall-through clause that hides missing cases.
- **NO Unicode ellipsis** anywhere — only 3 ASCII periods `...` per AAP §0.7.4.

## Source lineage

REFERENCE-only source files (all UNCHANGED per AAP §0.1.1 and §0.2.2):

- `app/cbl/CBTRN01C.cbl` (491 lines) — Validation-only batch COBOL program. PROGRAM-ID `CBTRN01C` at `app/cbl/CBTRN01C.cbl:L23`; AUTHOR `AWS` at `app/cbl/CBTRN01C.cbl:L24`. SELECT clauses at `app/cbl/CBTRN01C.cbl:L28-L62`. FD declarations at `app/cbl/CBTRN01C.cbl:L66-L94`. WORKING-STORAGE COPY directives at `app/cbl/CBTRN01C.cbl:L99`, `app/cbl/CBTRN01C.cbl:L104`, `app/cbl/CBTRN01C.cbl:L109`, `app/cbl/CBTRN01C.cbl:L114`, `app/cbl/CBTRN01C.cbl:L119`, `app/cbl/CBTRN01C.cbl:L124`. PROCEDURE DIVISION at `app/cbl/CBTRN01C.cbl:L154`. Main loop at `app/cbl/CBTRN01C.cbl:L164-L186`. 15 functional paragraphs (1 MAIN-PARA + 6 open + 3 read/lookup + 6 close) plus 2 Z-prefix utility paragraphs.
- `app/cpy/CVTRA06Y.cpy` — DALYTRAN-RECORD 350-byte layout (14 fields). Used in CBTRN01C via COPY at `app/cbl/CBTRN01C.cbl:L99`.
- `app/cpy/CVCUS01Y.cpy` — CUSTOMER-RECORD 500-byte layout. Used only for FD record-length declaration; never parsed.
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD 50-byte layout (XREF-CARD-NUM PIC X(16) key, XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11), FILLER PIC X(14)).
- `app/cpy/CVACT02Y.cpy` — CARD-RECORD 150-byte layout. Used only for FD record-length declaration; never parsed.
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD 300-byte layout.
- `app/cpy/CVTRA05Y.cpy` — TRAN-RECORD 350-byte layout. Used only for FD record-length declaration; never parsed.
- `app/data/ASCII/dailytran.txt` (105,300 bytes) — DALYTRAN input fixture (REFERENCE; read-only).
- `app/data/ASCII/cardxref.txt` (1,850 bytes) — XREFFILE input fixture (REFERENCE; read-only).
- `app/data/ASCII/acctdata.txt` (15,050 bytes) — ACCTFILE input fixture (REFERENCE; read-only).
- `app/data/ASCII/custdata.txt` (25,050 bytes) — CUSTFILE input fixture (REFERENCE; opened but never read).
- `app/data/ASCII/carddata.txt` (7,550 bytes) — CARDFILE input fixture (REFERENCE; opened but never read).

NO JCL driver exists for CBTRN01C in `app/jcl/`. Verified absence via `grep -l 'CBTRN01C' app/jcl/*.jcl` returning empty. The program is callable via direct method invocation by `CbTrn01CGoldenTest` rather than scheduled by z/OS JCL. The Java translation does NOT require a corresponding `carddemo-app` main class.

## Authority references

- AAP §0.1.1 (refactoring objective — byte-for-byte fidelity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: `golden/cbtrn01c/` directory tree under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference implementation)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (hexagonal architecture; constructor-injected ports; records, sealed types)
- AAP §0.4.1 (transformation plan — CBTRN01C -> CbTrn01C in `com.blitzy.carddemo.application.transaction`; ASCII fixtures REFERENCED via classpath and NEVER COPIED)
- AAP §0.6.1 (decimal arithmetic fidelity — `BigDecimal`, `MathContext.DECIMAL128`; CBTRN01C parses/encodes but performs no arithmetic)
- AAP §0.6.4 (date semantics — `java.time` only; never legacy date APIs)
- AAP §0.6.5 (file I/O exactness — `java.nio.file` only; EBCDIC IBM-1047 default; per-file codepage configurable)
- AAP §0.6.6 (batch throughput — sequential execution preserved for CBTRN01C because DISPLAY ordering is observable; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until COBOL captures committed)
- AAP §0.7.1 (Minimal Change Clause; preserve L372-L373 copy-paste bug, opened-but-unused boilerplate, colon spacing inconsistencies, DAILY/DALYTRAN spelling, Z-prefix paragraphs, FD-CUST-DATA name reuse, DALYTRAN-RECORD double-DISPLAY pattern)
- AAP §0.7.2 (no card PAN in production logs; test fixtures preserve COBOL behavior verbatim)
- AAP §0.7.4 (forbidden features — no preview JEPs; pattern-matching switch must use exhaustiveness)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:<locator>]`)
- Sibling pattern reference: `golden/cbtrn02c/input/README.md` uses the same 8-H2 + 4-H3 structure for the FULL POSTING ENGINE with FIVE outputs and FOUR file ports; the CBTRN01C README differs in ONE output (vs FIVE), SIX file ports (vs FOUR), ZERO reject codes (vs FIVE), DISPLAY diagnostics only (vs structured 80-byte reject trailers), no deterministic Clock requirement (vs CBTRN02C's Clock injection), and no BigDecimal arithmetic (vs heavy monetary arithmetic in CBTRN02C). The `app/cbl/CBTRN01C.cbl:L372-L373` copy-paste bug is the marquee idiosyncrasy unique to CBTRN01C.

## DO NOT add files here

This folder MUST contain ONLY the single `README.md` file. Do NOT add:

- Fixture data files (`.bin`, `.txt` copies of `app/data/ASCII/*` files, or any captured input snapshots)
- `.gitkeep` placeholders
- Subfolders
- Captured input snapshots
- Reject files (CBTRN01C produces none)
- Output files other than the sibling `../expected/stdout.txt` (which lives in the sibling `../expected/` folder, not here)

All five conceptual input fixtures (DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE) are sourced from `app/data/ASCII/` via classpath reference and are NEVER COPIED per AAP §0.4.1. The sixth file (TRANSACT-FILE) is opened but never read, so it has no ASCII fixture. The single expected output (`stdout.txt`) lives in the sibling `../expected/` folder. Adding files to this `input/` folder would create duplicate, stale, or unreachable fixtures and would break the byte-for-byte parity guarantee that is the entire point of the golden-record harness.

If a future test scenario requires additional inputs, source them from `app/data/ASCII/` and extend the appropriate `resolveAppDataPath(...)` call in `CbTrn01CGoldenTest.java` — not here.
