# CBCUS01C (Customer File Sequential Reader) — Golden-Record `input/` Folder (Documentation-Only Marker)

This folder is intentionally documentation-only and contains NO fixture data files. CBCUS01C is a **sequential CUSTFILE reader** translated to Java `com.blitzy.carddemo.application.customer.CbCus01C` per AAP §0.4.1 (NOTE: `customer/` subpackage — distinct from the sibling sequential readers `CBACT01C`/`CBACT02C`/`CBACT03C` which live in `com.blitzy.carddemo.application.account`). The COBOL source resides at `app/cbl/CBCUS01C.cbl` (178 lines; `PROGRAM-ID. CBCUS01C.` at `app/cbl/CBCUS01C.cbl:L23`; `AUTHOR. AWS.` at `app/cbl/CBCUS01C.cbl:L24`).

The JCL driver `app/jcl/READCUST.jcl` EXISTS (invokes `EXEC PGM=CBCUS01C` at `app/jcl/READCUST.jcl:L6`); per AAP §0.4.1 it is translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCustomerDumpApp.java`, but the Java test class bypasses that main class and invokes `CbCus01C` directly via constructor-injected `CustomerRepository`. The single conceptual ASCII input fixture (`app/data/ASCII/custdata.txt`) is REFERENCED via classpath and is NEVER COPIED into this folder per AAP §0.4.1. The single conceptual output (`stdout.txt`) lives in the sibling `../expected/` folder.

## Why this folder is documentation-only

1. CBCUS01C is a **read-only sequential display utility** invoked by direct method call (no posting, no writes, no rewrites, no updates). The program reads only in `1000-CUSTFILE-GET-NEXT` at `app/cbl/CBCUS01C.cbl:L92`. The sole CUSTFILE port is opened at `app/cbl/CBCUS01C.cbl:L120`, closed at `app/cbl/CBCUS01C.cbl:L138`, and actively read in the main loop at `app/cbl/CBCUS01C.cbl:L74-L81` — there are NO opened-but-unused files in CBCUS01C (CONTRAST with `CBTRN01C` which has 3 opened-but-unused files).

2. The single ASCII input fixture (CUSTFILE) is sourced from `app/data/ASCII/custdata.txt` via classpath reference and is **NEVER COPIED** into this folder per AAP §0.4.1. Copying it would (a) double storage footprint, (b) introduce drift risk between the COBOL reference implementation and the Java parity harness, and (c) violate the AAP §0.2.2 immutability mandate for the `app/` tree.

3. The consuming Java test class `com.blitzy.carddemo.tests.golden.CbCus01CGoldenTest` (extending `com.blitzy.carddemo.tests.golden.GoldenRecordTest`) resolves the expected-output path through the base class helper `resolveExpectedOutputPath("cbcus01c", "stdout.txt")` which routes to `src/test/resources/golden/cbcus01c/expected/`. The input fixture path routes to `app/data/ASCII/` via the base class helper `resolveAppDataPath("custdata.txt")`. Therefore NO fixture data file lives in this `input/` folder.

4. The harness convention per AAP §0.3.1 mandates the `<program>/input/` + `<program>/expected/` pairing per program for discoverability and parallel structure across all program test folders. Removing the `input/` folder would break this symmetry; keeping it documented with only this README preserves the convention while making the absence of data files self-documenting.

5. The capture procedure for `../expected/stdout.txt` is documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.7.5. Until that expected fixture is captured and committed, `CbCus01CGoldenTest` is `@Disabled` per AAP §0.6.11.

Cross-references in the sibling `../expected/` folder:

- `../expected/README.md` — Authoritative byte-for-byte contract documenting the single output and the DOUBLE-DISPLAY pattern.
- `../expected/stdout.txt` — Captured DISPLAY output sequence (CAPTURE PLACEHOLDER).

## Conceptual input contract (documented; data referenced from app/data/ASCII/)

CBCUS01C opens EXACTLY ONE file (CUSTFILE-FILE) and reads it sequentially. The single input is sourced from `app/data/ASCII/custdata.txt` via classpath reference and is NEVER COPIED into this folder per AAP §0.4.1. The single output (`stdout.txt`) lives in the sibling `../expected/` folder. This section documents the conceptual contract; data location is documented in subsection 2.4.

### FILE-CONTROL SELECT clause + FD record layout

The single file declared by `app/cbl/CBCUS01C.cbl:L29-L33` is described below; the FD declaration comes from `app/cbl/CBCUS01C.cbl:L37-L40`.

| SELECT Name | FD Record Length | ORG / ACCESS | Key Field | ASCII Fixture (REFERENCED only) | Used in Main Loop? |
|---|---|---|---|---|---|
| CUSTFILE-FILE | 500 bytes (FD-CUST-ID PIC 9(09) + FD-CUST-DATA PIC X(491)) | INDEXED SEQUENTIAL | FD-CUST-ID (9 bytes) | `app/data/ASCII/custdata.txt` (25,050 bytes; 50 records) | YES (ACTIVE — sole port) |

Copybook lineage:

- CUSTFILE-FILE -> `app/cpy/CVCUS01Y.cpy` (CUSTOMER-RECORD, 500 bytes, 18 named fields + 168-byte FILLER); `COPY CVCUS01Y.` at `app/cbl/CBCUS01C.cbl:L45`.

### Sequential read flow paragraphs (5 paragraphs)

The PROCEDURE DIVISION begins at `app/cbl/CBCUS01C.cbl:L70`; the main loop is `PERFORM UNTIL END-OF-FILE = 'Y'` at `app/cbl/CBCUS01C.cbl:L74-L81`.

- `0000-CUSTFILE-OPEN` at `app/cbl/CBCUS01C.cbl:L118` — `OPEN INPUT CUSTFILE-FILE` at `app/cbl/CBCUS01C.cbl:L120`; success path `MOVE 0 TO APPL-RESULT`; failure path emits `'ERROR OPENING CUSTFILE'` at `app/cbl/CBCUS01C.cbl:L129` (no space between CUST and FILE — preserved verbatim per AAP §0.7.1), `MOVE CUSTFILE-STATUS TO IO-STATUS`, `PERFORM Z-DISPLAY-IO-STATUS`, `PERFORM Z-ABEND-PROGRAM`; EXIT at `app/cbl/CBCUS01C.cbl:L134`.
- `1000-CUSTFILE-GET-NEXT` at `app/cbl/CBCUS01C.cbl:L92` — `READ CUSTFILE-FILE INTO CUSTOMER-RECORD` at `app/cbl/CBCUS01C.cbl:L93`; on `CUSTFILE-STATUS = '00'` success: `MOVE 0 TO APPL-RESULT` and `DISPLAY CUSTOMER-RECORD` at `app/cbl/CBCUS01C.cbl:L96` (FIRST half of DOUBLE-DISPLAY pattern); on `CUSTFILE-STATUS = '10'` EOF: `MOVE 16 TO APPL-RESULT` and ultimately `MOVE 'Y' TO END-OF-FILE` at `app/cbl/CBCUS01C.cbl:L108`; on other status: `MOVE 12 TO APPL-RESULT` and emits `'ERROR READING CUSTOMER FILE'` at `app/cbl/CBCUS01C.cbl:L110`, `MOVE CUSTFILE-STATUS TO IO-STATUS`, `PERFORM Z-DISPLAY-IO-STATUS`, `PERFORM Z-ABEND-PROGRAM`; EXIT at `app/cbl/CBCUS01C.cbl:L116`.
- `9000-CUSTFILE-CLOSE` at `app/cbl/CBCUS01C.cbl:L136` — `CLOSE CUSTFILE-FILE` at `app/cbl/CBCUS01C.cbl:L138`; success path sets APPL-RESULT=0 via `SUBTRACT APPL-RESULT FROM APPL-RESULT`; failure path emits `'ERROR CLOSING CUSTOMER FILE'` at `app/cbl/CBCUS01C.cbl:L147`, `MOVE CUSTFILE-STATUS TO IO-STATUS`, `PERFORM Z-DISPLAY-IO-STATUS`, `PERFORM Z-ABEND-PROGRAM`; EXIT at `app/cbl/CBCUS01C.cbl:L152`.
- `Z-ABEND-PROGRAM` at `app/cbl/CBCUS01C.cbl:L154` (lacks 4-digit numeric prefix — preserved per AAP §0.7.1) — `DISPLAY 'ABENDING PROGRAM'` at `app/cbl/CBCUS01C.cbl:L155`; `MOVE 0 TO TIMING` at `app/cbl/CBCUS01C.cbl:L156`; `MOVE 999 TO ABCODE` at `app/cbl/CBCUS01C.cbl:L157`; `CALL 'CEE3ABD'` at `app/cbl/CBCUS01C.cbl:L158` (Language Environment service abend).
- `Z-DISPLAY-IO-STATUS` at `app/cbl/CBCUS01C.cbl:L161` (also lacks 4-digit numeric prefix) — both IF (L162-L168) and ELSE (L169-L172) branches emit `'FILE STATUS IS: NNNN' IO-STATUS-04` (identical literal in both branches); EXIT at `app/cbl/CBCUS01C.cbl:L174`.

NO writes, NO rewrites, NO updates, NO posting. CBCUS01C is a read-only sequential display utility. The Java translation MUST faithfully execute the open/read/close calls and produce the DOUBLE-DISPLAY pattern (L96 FIRST then L78 SECOND per record) for each successful read.

### Verbatim DISPLAY message catalog (7 entries)

All DISPLAY messages are preserved verbatim per AAP §0.7.1. The L78 and L96 emissions of `DISPLAY CUSTOMER-RECORD` constitute the DOUBLE-DISPLAY pattern. The L168 and L172 emissions both produce the identical `'FILE STATUS IS: NNNN' IO-STATUS-04` literal in different branches and are catalogued together as entry #7.

| # | Verbatim Bytes | Line(s) | Context |
|---|---|---|---|
| 1 | `'START OF EXECUTION OF PROGRAM CBCUS01C'` | L71 | Unconditional start banner |
| 2 | `DISPLAY CUSTOMER-RECORD` (full 500-byte record) | L78 AND L96 | DOUBLE-DISPLAY pattern — L96 inside `1000-CUSTFILE-GET-NEXT` on success path (FIRST), L78 in main loop after PERFORM returns (SECOND) |
| 3 | `'END OF EXECUTION OF PROGRAM CBCUS01C'` | L85 | Unconditional end banner |
| 4 | `'ERROR READING CUSTOMER FILE'` | L110 | `1000-CUSTFILE-GET-NEXT` non-EOF error path (WITH SPACE) |
| 5 | `'ERROR OPENING CUSTFILE'` | L129 | `0000-CUSTFILE-OPEN` failure (NO SPACE — distinct from L110/L147 — PRESERVED per AAP §0.7.1) |
| 6 | `'ERROR CLOSING CUSTOMER FILE'` | L147 | `9000-CUSTFILE-CLOSE` failure path (WITH SPACE) |
| 7 | `'ABENDING PROGRAM'` then `'FILE STATUS IS: NNNN' IO-STATUS-04` | L155, L168, L172 | `Z-ABEND-PROGRAM` (L155) and `Z-DISPLAY-IO-STATUS` (L168 IF / L172 ELSE — both branches emit identical literal) |

Note the L129 `'ERROR OPENING CUSTFILE'` (NO SPACE) vs L110 `'ERROR READING CUSTOMER FILE'` and L147 `'ERROR CLOSING CUSTOMER FILE'` (WITH SPACE) inconsistency. This three-message inconsistency is PRESERVED verbatim per AAP §0.7.1 Minimal Change Clause — the Java translation MUST emit byte-identical strings including the embedded space variations.

### Fixture data location (source-of-truth)

| Conceptual fixture | Actual location | Role |
|---|---|---|
| CUSTFILE sequential | `app/data/ASCII/custdata.txt` (25,050 bytes; 50 records of 500 bytes + 1 LF each) | Primary input — REFERENCED via classpath; NEVER copied per AAP §0.4.1 |

Per AAP §0.4.1, no copy of `custdata.txt` is created in this `input/` folder. The Java test class resolves the classpath via the `resolveAppDataPath("custdata.txt")` helper from the `GoldenRecordTest` base class. The fixture records are sequential 9-digit customer IDs `000000001` through `000000050`.

The fixture file is plain ASCII (not EBCDIC) per the canonical convention of `app/data/ASCII/`. Each record on disk occupies 501 bytes: 500 bytes of fixed-width CUSTOMER-RECORD payload followed by 1 byte of LF (0x0A) terminator. The file-backed `FileCustomerRepository` adapter strips the LF terminator when reading and emits the 500-byte payload directly to the consumer; the Java `CustomerRecord.parse(byte[])` factory consumes exactly 500 bytes per call.

## Cross-reference to the Java test class

Java FQCN under test: `com.blitzy.carddemo.application.customer.CbCus01C` (NOTE: `customer/` subpackage per AAP §0.4.1 — distinct from the sibling sequential readers `CBACT01C`/`CBACT02C`/`CBACT03C` which live in `com.blitzy.carddemo.application.account`). The Java test class FQCN is `com.blitzy.carddemo.tests.golden.CbCus01CGoldenTest`; its base class FQCN is `com.blitzy.carddemo.tests.golden.GoldenRecordTest`; the test source path is `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbCus01CGoldenTest.java`.

CBCUS01C has a single constructor-injected port (file-backed per AAP §0.3.2 hexagonal architecture):

- `CustomerRepository` from `com.blitzy.carddemo.domain.port` (sole port — file-backed via `FileCustomerRepository`)

The `CbCus01CGoldenTest` overrides on `GoldenRecordTest` resolve as follows:

- `programClass()` returns `com.blitzy.carddemo.application.customer.CbCus01C.class`
- `inputFile()` returns `resolveAppDataPath("custdata.txt")`
- `auxiliaryInputs()` returns `List.of()` (no auxiliaries — CBCUS01C reads ONE file)
- `expectedOutputFile()` returns `resolveExpectedOutputPath("cbcus01c", "stdout.txt")`
- NO `runProgram(...)` override needed (default base-class hook handles single-port wiring)

The input fixture routes to `app/data/ASCII/`; the expected fixture routes to `../expected/`. **Neither routes to this `input/` folder.** This is the architectural reason the folder is empty.

The test class MUST be annotated:

```text
@Disabled("Awaiting COBOL CBCUS01C baseline capture per AAP §0.6.11. See java/MIGRATION_NOTES.md §1.6 for the regeneration procedure. Verify the double-DISPLAY pattern: each successful read emits CUSTOMER-RECORD twice on stdout (once at L96, once at L78), yielding 50 records x 2 = 100 record-display lines bracketed by START and END banners.")
```

per AAP §0.6.11 until `../expected/stdout.txt` is captured and committed.

Sequential execution mandate per AAP §0.6.6: virtual threads are FORBIDDEN inside `CbCus01C` because DISPLAY output ordering is observable; any reordering of `DISPLAY CUSTOMER-RECORD` emissions or the L96-then-L78 DOUBLE-DISPLAY interleaving per record breaks byte-for-byte parity. The Java translation MUST execute the main loop serially in the same CUSTFILE read order.

No-deterministic-clock note: CBCUS01C does NOT invoke `FUNCTION CURRENT-DATE`; no timestamp generation occurs. NO injected `Clock` is required for reproducible captures. This significantly simplifies the test fixture compared to CBTRN02C which requires deterministic clock injection.

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for CBCUS01C) for the COBOL build/run path used to capture `../expected/stdout.txt`. Per AAP §0.7.5 the procedure is documented in `MIGRATION_NOTES.md` rather than inline in this README to keep the per-program documentation focused on the conceptual contract.

The capture procedure MUST run the COBOL CBCUS01C program against the SAME `app/data/ASCII/custdata.txt` fixture that the Java test will reference. The capture is naturally deterministic — no clock injection required. Until the capture is performed and committed, `../expected/stdout.txt` holds placeholder content and `CbCus01CGoldenTest` is `@Disabled` per AAP §0.6.11.

## Behavioral invariants preserved by this fixture

- **Byte-for-byte parity is the contract** (AAP §0.1.1, §0.6.11): every byte of the captured `stdout.txt` MUST equal the corresponding byte of the Java translation's captured stdout.
- **Sequential execution is mandated** (AAP §0.6.6): virtual-thread fan-out is FORBIDDEN for CBCUS01C because DISPLAY output ordering is observable. The L96-then-L78 DOUBLE-DISPLAY interleaving per record breaks under any reordering.
- **NO posting, NO writes, NO rewrites** in CBCUS01C: the program is a read-only display utility. There is no posting paragraph, no WRITE statement, no REWRITE statement, no DELETE statement.
- **DOUBLE-DISPLAY CUSTOMER-RECORD pattern preserved** (AAP §0.7.1): the marquee behavior unique to the CBCUS01C/CBACT01C/CBACT02C/CBACT03C sequential-reader family. Each successful read emits the 500-byte CUSTOMER-RECORD TWICE per iteration — once at `app/cbl/CBCUS01C.cbl:L96` (inside `1000-CUSTFILE-GET-NEXT` on `CUSTFILE-STATUS = '00'`) and once at `app/cbl/CBCUS01C.cbl:L78` (in main loop after PERFORM returns with `END-OF-FILE = 'N'`). For 50 records the body produces 100 record-display lines bracketed by 1 START banner and 1 END banner, totaling 102 stdout lines.
- **L129 'CUSTFILE' (no space) vs L110/L147 'CUSTOMER FILE' (with space) inconsistency preserved** (AAP §0.7.1): the open-error literal differs from the read-error and close-error literals in embedded space placement. The Java translation MUST emit byte-identical strings including this idiosyncrasy.
- **Z-ABEND-PROGRAM and Z-DISPLAY-IO-STATUS lack 4-digit numeric prefix preserved** (AAP §0.7.1): other paragraphs use `0000-`/`1000-`/`9000-` numeric prefixes; the Z-prefix utility paragraphs are preserved verbatim in the `@CobolParagraph` annotation values.
- **ABCODE=999 and CALL 'CEE3ABD' preserved** (AAP §0.7.1): `Z-ABEND-PROGRAM` sets `ABCODE` to 999 at `app/cbl/CBCUS01C.cbl:L157` before calling the Language Environment abend service at `app/cbl/CBCUS01C.cbl:L158`. The Java translation MUST raise an abend exception carrying equivalent ABCODE=999 metadata.
- **No `BigDecimal` arithmetic** in CBCUS01C: the program performs no math. CUST-FICO-CREDIT-SCORE (PIC 9(03)) is parsed as int; APPL-RESULT (PIC S9(9) COMP) is used as an enum-like discriminator; ABCODE/TIMING (PIC S9(9) BINARY) are used for the CEE3ABD abend signal. The `Decimals` utility is NOT called by CBCUS01C.
- **`java.time.LocalDate` for CUST-DOB-YYYY-MM-DD** (AAP §0.6.4): the 10-byte PIC X(10) field is parsed as `LocalDate` in the `CustomerRecord` record; the encode() side renders LocalDate as ISO `yyyy-MM-dd` format which matches the COBOL byte layout. `java.util.Date`, `java.util.Calendar`, and `java.text.SimpleDateFormat` are FORBIDDEN.
- **`java.nio.file` only** (AAP §0.6.5): no `java.io.File`. The single referenced fixture is accessed via `Files.newByteChannel`, `Files.readAllBytes`, or `SeekableByteChannel`.
- **EBCDIC IBM-1047 default codepage** (AAP §0.6.5): default codepage is `Charset.forName("IBM-1047")`; ASCII fixtures use `Charset.forName("US-ASCII")` for test convenience; per-file codepage overrides are configurable via `application.properties` (e.g., `carddemo.file.custdata.charset`).
- **`ScopedValue` replaces `ThreadLocal`** (AAP §0.6.6): batch run context (run ID, processing date, tenant) flows through `ScopedValue.where(...).run(...)`. NO `ThreadLocal` in new code.
- **`@CobolProgram("CBCUS01C")` annotation required** (AAP §0.7.1): the Java class `CbCus01C` MUST carry the `@CobolProgram` annotation citing the original PROGRAM-ID, source path `app/cbl/CBCUS01C.cbl`, and translation date.
- **CUST-SSN and CUST-GOVT-ISSUED-ID must be masked in production logs** (AAP §0.7.2): sensitive PII fields (CUST-SSN PIC 9(09) and CUST-GOVT-ISSUED-ID PIC X(20)) are unmasked in the captured test fixture (preserve COBOL behavior via test-only sink for byte-for-byte parity assertion) but masked in production via a separate logging sink. The two sinks are distinct surfaces.
- **Pattern-matching switch with no `default` branch** (AAP §0.7.4): any switch on a `FileStatus` sealed hierarchy uses pattern-matching with compiler-enforced exhaustiveness; NO branch hides cases.
- **CUSTFILE-FILE INDEXED SEQUENTIAL access preserved** (AAP §0.7.1): the COBOL `ACCESS MODE IS SEQUENTIAL` clause at `app/cbl/CBCUS01C.cbl:L31` combined with `ORGANIZATION IS INDEXED` at `app/cbl/CBCUS01C.cbl:L30` and `RECORD KEY IS FD-CUST-ID` at `app/cbl/CBCUS01C.cbl:L32` mandates key-order traversal. The Java `CustomerRepository` port's sequential-read method MUST iterate records ordered by `CUST-ID` ascending; the file-backed adapter sorts by the key field before yielding records to maintain VSAM KSDS parity.
- **168-byte FILLER preservation** (AAP §0.6.5): the trailing 168-byte FILLER region of the CUSTOMER-RECORD copybook (`app/cpy/CVCUS01Y.cpy` final field) is preserved verbatim as `byte[168]` in the Java `CustomerRecord` record. The `parse(byte[])` factory copies bytes 332-499 unchanged and the `encode()` instance method writes them back unchanged, ensuring byte-for-byte round-trip equality for every record buffer.
- **APPL-RESULT 88-level taxonomy mirrored** (AAP §0.6.10): the COBOL 88-level conditions `APPL-AOK VALUE 0` at `app/cbl/CBCUS01C.cbl:L62` and `APPL-EOF VALUE 16` at `app/cbl/CBCUS01C.cbl:L63` partition the APPL-RESULT value space; the Java translation expresses this as a sealed hierarchy (e.g., `sealed interface ApplResult permits Aok, Eof, IoError`) with compiler-enforced exhaustiveness at every consumer site.
- **JCL DD-to-port resolution preserved** (AAP §0.4.1): the COBOL `SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE` clause at `app/cbl/CBCUS01C.cbl:L29` couples the in-program logical name `CUSTFILE-FILE` to the JCL DD name `CUSTFILE` at `app/jcl/READCUST.jcl:L9-L10`. The Java translation maps this coupling to the `application.properties` key `carddemo.file.custdata.path` (resolved by the file-backed adapter at startup); the test class bypasses property resolution by passing the fixture path directly via `resolveAppDataPath("custdata.txt")`.
- **NO Unicode ellipsis** anywhere — only 3 ASCII periods `...` per AAP §0.7.4.

## Source lineage

All sources listed below are REFERENCE-only and remain UNCHANGED per AAP §0.1.1 and §0.2.2.

- `app/cbl/CBCUS01C.cbl` (178 lines) — Sequential customer-file reader. `PROGRAM-ID. CBCUS01C.` at L23; `AUTHOR. AWS.` at L24. SELECT clause at L29-L33. FD declaration at L37-L40. `COPY CVCUS01Y.` at L45. PROCEDURE DIVISION at L70. Main loop at L74-L81. Paragraphs: `1000-CUSTFILE-GET-NEXT` at L92, `0000-CUSTFILE-OPEN` at L118, `9000-CUSTFILE-CLOSE` at L136, `Z-ABEND-PROGRAM` at L154, `Z-DISPLAY-IO-STATUS` at L161.
- `app/cpy/CVCUS01Y.cpy` — CUSTOMER-RECORD 500-byte layout. 18 named fields + 168-byte FILLER: CUST-ID PIC 9(09), CUST-FIRST-NAME PIC X(25), CUST-MIDDLE-NAME PIC X(25), CUST-LAST-NAME PIC X(25), CUST-ADDR-LINE-1 PIC X(50), CUST-ADDR-LINE-2 PIC X(50), CUST-ADDR-LINE-3 PIC X(50), CUST-ADDR-STATE-CD PIC X(02), CUST-ADDR-COUNTRY-CD PIC X(03), CUST-ADDR-ZIP PIC X(10), CUST-PHONE-NUM-1 PIC X(15), CUST-PHONE-NUM-2 PIC X(15), CUST-SSN PIC 9(09), CUST-GOVT-ISSUED-ID PIC X(20), CUST-DOB-YYYY-MM-DD PIC X(10), CUST-EFT-ACCOUNT-ID PIC X(10), CUST-PRI-CARD-HOLDER-IND PIC X(01), CUST-FICO-CREDIT-SCORE PIC 9(03), FILLER PIC X(168). Total = 9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168 = 500 bytes.
- `app/data/ASCII/custdata.txt` (25,050 bytes; 50 records of 500 bytes + 1 LF each) — CUSTFILE input fixture (REFERENCE only via classpath; sequential 9-digit customer IDs `000000001` through `000000050`).
- `app/jcl/READCUST.jcl` — JCL driver (REFERENCE only; not invoked by test). Invokes `EXEC PGM=CBCUS01C` at STEP05 (`app/jcl/READCUST.jcl:L6`); CUSTFILE DD points to `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` at `app/jcl/READCUST.jcl:L9-L10`. Translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadCustomerDumpApp.java` per AAP §0.4.1. The Java test class bypasses this main class and invokes `CbCus01C` directly via constructor injection.

## Authority references

- AAP §0.1.1 (refactoring objective — byte-for-byte fidelity COBOL -> Java 25 LTS)
- AAP §0.2.1 (in-scope: `golden/cbcus01c/input/` directory under the `java/carddemo-tests/src/test/resources/golden/**/*` wildcard)
- AAP §0.2.2 (COBOL source tree under `app/` is UNCHANGED and reserved as the reference implementation)
- AAP §0.3.1 (harness directory convention: `<program>/input/` + `<program>/expected/`)
- AAP §0.3.2 (hexagonal architecture; constructor-injected ports; records, sealed types)
- AAP §0.4.1 (transformation plan — CBCUS01C -> CbCus01C in `com.blitzy.carddemo.application.customer`; ASCII fixtures REFERENCED via classpath and NEVER COPIED)
- AAP §0.6.1 (decimal arithmetic fidelity — `BigDecimal`, `MathContext.DECIMAL128`; NOT applicable to CBCUS01C — no arithmetic sites)
- AAP §0.6.4 (date semantics — `java.time.LocalDate` for CUST-DOB-YYYY-MM-DD; never legacy date APIs)
- AAP §0.6.5 (file I/O exactness — `java.nio.file` only; EBCDIC IBM-1047 default; per-file codepage configurable)
- AAP §0.6.6 (batch throughput — sequential execution preserved for CBCUS01C because DISPLAY ordering is observable; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.8 (program-by-program mapping checklist entry for CBCUS01C)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled` until COBOL captures committed)
- AAP §0.7.1 (Minimal Change Clause; preserve DOUBLE-DISPLAY pattern, L129 spacing inconsistency, Z-prefix paragraphs, ABCODE=999, CEE3ABD call)
- AAP §0.7.2 (no card PAN in production logs — applies to CUST-SSN and CUST-GOVT-ISSUED-ID via separate masking sink in production; test fixtures preserve COBOL behavior verbatim)
- AAP §0.7.4 (forbidden features — no preview JEPs; no exhaustiveness-evasion branches in pattern-matching switches)
- AAP §0.7.5 (capture procedure documented in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline `[<path>:<locator>]`)
- Sibling pattern reference: `golden/cbtrn01c/input/README.md` uses the same 8-H2 + 4-H3 structure for a validation-only program with SIX file ports (three opened-but-unused). The CBCUS01C README differs in: ONE file port (vs SIX), ZERO opened-but-unused files (vs THREE), DOUBLE-DISPLAY pattern (vs SINGLE-DISPLAY in CBTRN01C), JCL driver EXISTS (vs CBTRN01C which has none), and no reject codes (same as CBTRN01C). The DOUBLE-DISPLAY pattern unique to the CBCUS01C/CBACT01C/CBACT02C/CBACT03C family is the marquee idiosyncrasy.

## DO NOT add files here

This folder MUST contain ONLY the single `README.md` file. Do NOT add:

- Copies of `custdata.txt` or any captured input snapshots
- `.gitkeep` placeholders
- Subfolders
- Output files (those live in the sibling `../expected/` folder)
- COBOL source mirrors from `app/cbl/`
- Database, Spring, or Liquibase configuration files

The single conceptual input fixture (`app/data/ASCII/custdata.txt`) is sourced from `app/data/ASCII/` via classpath reference and is NEVER COPIED per AAP §0.4.1. The single expected output (`stdout.txt`) lives in the sibling `../expected/` folder. Adding files to this `input/` folder would create duplicate, stale, or unreachable fixtures and would break the byte-for-byte parity guarantee that is the entire point of the golden-record harness.

If a future test scenario requires additional inputs, source them from `app/data/ASCII/` and extend the appropriate `resolveAppDataPath(...)` call in `CbCus01CGoldenTest.java` — not here.

This README is a permanent fixture of the CardDemo repository. It will not need to be regenerated when the COBOL baseline capture occurs; only the sibling `../expected/stdout.txt` will be replaced. Therefore this README's content is fully self-contained and references the pending capture-time placeholder only abstractly per AAP §0.6.11 and AAP §0.7.5.
