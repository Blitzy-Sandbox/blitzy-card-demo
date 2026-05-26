# Golden-Record Contract — CBACT01C (Account File Sequential Reader; JCL Driver: READACCT.jcl; DOUBLE-DISPLAY Pattern)

This folder is the authoritative byte-for-byte golden-record contract for the Java translation of the COBOL
program `CBACT01C` `[app/cbl/CBACT01C.cbl:L23]`. It is consumed exclusively by the test class
`com.blitzy.carddemo.tests.golden.CbAct01CGoldenTest`, which extends
`com.blitzy.carddemo.tests.golden.GoldenRecordTest` and remains annotated `@Disabled` until the captured
COBOL-side `stdout.txt` is committed alongside this README per AAP §0.6.11. Every line citation below uses
the form `[<path>:Lnnn]` mandated by AAP §0.8.1, and every COBOL literal is reproduced verbatim inside
backticks and single quotes (for example `` `'ERROR OPENING ACCTFILE'` ``) so reviewers can audit
preservation of original spacing and spelling. The marquee distinguishing characteristic of CBACT01C — and
the single most important fact for any agent maintaining this contract — is the DOUBLE-DISPLAY pattern:
each successful ACCTFILE read emits the account record TWICE to stdout, first field-by-field via
`1100-DISPLAY-ACCT-RECORD` at L96 inside `1000-ACCTFILE-GET-NEXT`, then again as a full 300-byte record
dump via `DISPLAY ACCOUNT-RECORD` in the main loop at L78, producing exactly 13 stdout lines per
successful read.

## Authority cascade

The constraints documented here are derived from, and cite as authority, the following Agent Action Plan
sections (full enumeration; do not modify any constraint without first revisiting the cited AAP section):

- AAP §0.1.1 — core refactoring objective: byte-for-byte file fidelity, idiom-for-idiom translation
- AAP §0.2.1 — exhaustively in-scope wildcards including `golden/**/*` fixtures
- AAP §0.2.2 — `app/` tree IMMUTABLE; reference implementation status preserved
- AAP §0.3.1 — refactored structure planning; `<program>/input/` plus `<program>/expected/` convention
- AAP §0.3.2 — design pattern applications; `parse(byte[])` plus `encode()` byte-level contract
- AAP §0.3.4 — JVM tuning baseline: `-XX:+UseCompactObjectHeaders`, Shenandoah generational
- AAP §0.3.6 — hexagonal architecture diagram; ports and adapters separation
- AAP §0.4.1 — file-by-file transformation plan; CBACT01C maps to `CbAct01C`
- AAP §0.6.1 — decimal arithmetic fidelity via `Decimals` utility
- AAP §0.6.2 — REDEFINES sealed-type translation strategy
- AAP §0.6.4 — date semantics; `java.time` only
- AAP §0.6.5 — file I/O exactness; `java.nio.file` only
- AAP §0.6.6 — batch throughput strategy; virtual threads gated on safe reordering
- AAP §0.6.8 — program-by-program COBOL-to-Java class mapping checklist
- AAP §0.6.11 — golden-record harness design; non-negotiable PR gate
- AAP §0.7.1 — refactor discipline; minimal-change clause; preserve sic spellings
- AAP §0.7.2 — security; plaintext password preservation; PAN masking applies elsewhere
- AAP §0.7.4 — explicitly forbidden features; no `default` switch branches; no preview JEPs
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md`
- AAP §0.8.1 — citation discipline `[<path>:Lnnn]`

## Test identity and Java mapping targets

| Attribute | Value |
|---|---|
| COBOL `PROGRAM-ID` | `CBACT01C` `[app/cbl/CBACT01C.cbl:L23]` |
| COBOL `AUTHOR` | `AWS` `[app/cbl/CBACT01C.cbl:L24]` |
| COBOL source line count | 193 lines `[app/cbl/CBACT01C.cbl:L1-L193]` |
| COBOL source path | `app/cbl/CBACT01C.cbl` |
| JCL driver | `app/jcl/READACCT.jcl` — IDCAMS PRINT job (not a direct invocation of `CBACT01C`) |
| FILE-CONTROL SELECTs | Single SELECT — `ACCTFILE-FILE` `[app/cbl/CBACT01C.cbl:L29-L33]` |
| FD declaration | `FD ACCTFILE-FILE` plus `01 FD-ACCTFILE-REC` `[app/cbl/CBACT01C.cbl:L37-L40]` (300 bytes) |
| Active port count | 1 — ACCTFILE-FILE only (single-port profile) |
| Opened-but-unused files | NONE |
| Java FQCN under test | `com.blitzy.carddemo.application.account.CbAct01C` |
| Test class FQCN | `com.blitzy.carddemo.tests.golden.CbAct01CGoldenTest` |
| Test base class FQCN | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` |
| `@CobolProgram` value | `"CBACT01C"` |
| `@CobolParagraph` mandate | NUMERIC 4-digit prefixes (`0000-`, `1000-`, `1100-`, `9000-`, `9999-`, `9910-`) preserved verbatim per AAP §0.7.1 |
| Domain record FQCN | `com.blitzy.carddemo.domain.record.AccountRecord` |
| Repository port FQCN | `com.blitzy.carddemo.domain.port.AccountRepository` |
| File adapter FQCN | `com.blitzy.carddemo.adapter.file.FileAccountRepository` |
| JCL-derived main class | `com.blitzy.carddemo.app.ReadAccountDumpApp` |

## Files in this folder

### `README.md` (this file)

This file is the authoritative specification of the golden-record contract for CBACT01C. It is committed
unconditionally as the contract specification and is consulted by every agent that touches the
`CbAct01CGoldenTest` test class, the `CbAct01C` use-case class, or the `AccountRecord` domain record.

### `stdout.txt` (capture placeholder)

The captured COBOL-side `stdout.txt` is NOT yet committed; it is produced by the capture procedure
documented authoritatively in `java/MIGRATION_NOTES.md` §1.6. Until that capture is performed and the
resulting bytes are committed alongside this README, `CbAct01CGoldenTest` remains `@Disabled` per
AAP §0.6.11. The captured file must total exactly 652 lines (1 START banner plus 50 records times 13
emission-lines per record plus 1 END banner) and must use LF line endings only with UTF-8 encoding
(content is ASCII-only on disk because the EBCDIC fixtures in `app/data/ASCII/` are pre-transcoded by the
upstream capture pipeline). The byte-for-byte parity rule is absolute: the Java implementation's stdout
must equal the captured COBOL `stdout.txt` byte-for-byte; any divergence fails the test.

## Conceptual input universe

CBACT01C operates on a single fixed-width input file: `app/data/ASCII/acctdata.txt`. The fixture is 15,050
bytes on disk and contains 50 records; each record on disk occupies 301 bytes (the 300-byte
`ACCOUNT-RECORD` payload defined by `[app/cpy/CVACT01Y.cpy:L4-L17]` plus one trailing LF byte). The
fixture is REFERENCED from the Java test harness via classpath relative path; it is NEVER copied into the
`java/` tree per AAP §0.4.1, and any change to the fixture under `app/data/ASCII/` triggers a re-capture
per AAP §0.6.11. The base class `com.blitzy.carddemo.tests.golden.GoldenRecordTest` resolves the path
through its `resolveClasspath(...)` helper so individual test subclasses never hard-code filesystem
paths.

### ACCOUNT-RECORD 300-byte layout (per CVACT01Y)

The 300-byte ACCOUNT-RECORD is the canonical fixed-width payload that the COBOL `READ ACCTFILE-FILE INTO
ACCOUNT-RECORD` at L93 loads into working storage. Every byte offset below MUST be honored verbatim by
the `AccountRecord.parse(byte[])` factory and the `AccountRecord.encode()` instance method per AAP §0.3.2.

| # | Field name | PIC clause | Bytes | Offset | Java type | Displayed at |
|---|---|---|---|---|---|---|
| 1 | ACCT-ID | `9(11)` | 11 | 0 | `long` | L119 |
| 2 | ACCT-ACTIVE-STATUS | `X(01)` | 1 | 11 | `char` | L120 |
| 3 | ACCT-CURR-BAL | `S9(10)V99` | 12 | 12 | `BigDecimal` scale 2 | L121 |
| 4 | ACCT-CREDIT-LIMIT | `S9(10)V99` | 12 | 24 | `BigDecimal` scale 2 | L122 |
| 5 | ACCT-CASH-CREDIT-LIMIT | `S9(10)V99` | 12 | 36 | `BigDecimal` scale 2 | L123 |
| 6 | ACCT-OPEN-DATE | `X(10)` | 10 | 48 | `LocalDate` | L124 |
| 7 | ACCT-EXPIRAION-DATE | `X(10)` | 10 | 58 | `LocalDate` | L125 (sic spelling preserved) |
| 8 | ACCT-REISSUE-DATE | `X(10)` | 10 | 68 | `LocalDate` | L126 |
| 9 | ACCT-CURR-CYC-CREDIT | `S9(10)V99` | 12 | 78 | `BigDecimal` scale 2 | L127 |
| 10 | ACCT-CURR-CYC-DEBIT | `S9(10)V99` | 12 | 90 | `BigDecimal` scale 2 | L128 |
| 11 | ACCT-ADDR-ZIP | `X(10)` | 10 | 102 | `String` | NOT displayed (omitted from 1100 paragraph) |
| 12 | ACCT-GROUP-ID | `X(10)` | 10 | 112 | `String` | L129 |
| -- | FILLER | `X(178)` | 178 | 122 | `byte[178]` | NOT displayed individually |

Total bytes: 11 + 1 + (12 * 5) + (10 * 3) + (10 * 2) + 178 = 11 + 1 + 60 + 30 + 20 + 178 = 300 bytes per
`[app/cpy/CVACT01Y.cpy:L4-L17]`. The `byte[178]` filler is preserved verbatim across `parse(byte[])` and
`encode()` round-trips so that any positional bytes in the upstream EBCDIC source survive intact through
the Java translation; AAP §0.6.5 explicitly forbids stripping or normalizing filler bytes.

### Byte-for-byte round-trip invariant

For every valid 300-byte buffer `b` read from `app/data/ASCII/acctdata.txt`, the equality
`Arrays.equals(b, AccountRecord.parse(b).encode())` MUST hold. This invariant is enforced by the
`AccountRecord` record's canonical constructor (JEP 513 flexible constructor body) and is asserted by the
golden-record harness on every PR per AAP §0.6.11.

## Sequential read flow (6 paragraphs plus PROCEDURE DIVISION entry)

The PROCEDURE DIVISION entry plus six numbered paragraphs collectively define every stdout-emitting site
in CBACT01C. Each is documented below with exact line citations from `app/cbl/CBACT01C.cbl`.

### `PROCEDURE DIVISION` entry `[app/cbl/CBACT01C.cbl:L70-L87]`

The program entry sequence is fixed and deterministic: unconditional START banner DISPLAY at L71,
unconditional `PERFORM 0000-ACCTFILE-OPEN` at L72, main loop `PERFORM UNTIL END-OF-FILE = 'Y'` at L74-L81,
unconditional `PERFORM 9000-ACCTFILE-CLOSE` at L83, unconditional END banner DISPLAY at L85, and
unconditional `GOBACK` at L87. Inside the main loop, the inner guard `IF END-OF-FILE = 'N'` at L75 and L77
ensures that on the iteration that detects EOF, the L78 `DISPLAY ACCOUNT-RECORD` is suppressed. The L78
`DISPLAY ACCOUNT-RECORD` is the FULL 300-byte record dump that pairs with the field-by-field dump emitted
inside `1000-ACCTFILE-GET-NEXT` (through its call to `1100-DISPLAY-ACCT-RECORD` at L96), producing the
DOUBLE-DISPLAY pattern. The Java translation MUST preserve this exact ordering so that the field-by-field
emission precedes the full-record emission per successful read.

### `1000-ACCTFILE-GET-NEXT` `[app/cbl/CBACT01C.cbl:L92-L116]`

This paragraph performs the actual VSAM-style `READ ACCTFILE-FILE INTO ACCOUNT-RECORD` at L93 and
dispatches on `ACCTFILE-STATUS`. Status `'00'` at L94 (success) moves 0 to `APPL-RESULT` and performs
`1100-DISPLAY-ACCT-RECORD` at L96, emitting the field-by-field stdout block. Status `'10'` at L98 (EOF)
moves 16 to `APPL-RESULT`, which causes the downstream `IF APPL-EOF` at L107 to fire and `MOVE 'Y' TO
END-OF-FILE` at L108 to terminate the main loop on the next iteration. Any other status moves 12 to
`APPL-RESULT` at L101 and falls through to the error chain at L110-L113: DISPLAY the L110 literal
`'ERROR READING ACCOUNT FILE'` (two-word `'ACCOUNT FILE'`), `MOVE ACCTFILE-STATUS TO IO-STATUS`, perform
`9910-DISPLAY-IO-STATUS`, perform `9999-ABEND-PROGRAM`. The paragraph terminates with `EXIT.` at L116.

### `1100-DISPLAY-ACCT-RECORD` `[app/cbl/CBACT01C.cbl:L118-L131]`

This is the paragraph that distinguishes CBACT01C from its sibling sequential readers. It emits EXACTLY
12 stdout lines per call: 11 field-level DISPLAYs at L119-L129 (one DISPLAY per named field, in copybook
declaration order with one IMPORTANT exception noted below), plus 1 separator DISPLAY at L130. The field
DISPLAY order is: ACCT-ID (L119), ACCT-ACTIVE-STATUS (L120), ACCT-CURR-BAL (L121), ACCT-CREDIT-LIMIT
(L122), ACCT-CASH-CREDIT-LIMIT (L123), ACCT-OPEN-DATE (L124), ACCT-EXPIRAION-DATE (L125 — COBOL sic
spelling `EXPIRAION` preserved per AAP §0.7.1), ACCT-REISSUE-DATE (L126), ACCT-CURR-CYC-CREDIT (L127),
ACCT-CURR-CYC-DEBIT (L128), and ACCT-GROUP-ID (L129). The 12th copybook field, ACCT-ADDR-ZIP, is OMITTED
from this paragraph — only 11 of the 12 named fields defined in `[app/cpy/CVACT01Y.cpy:L4-L17]` are
displayed. The separator at L130 is a literal string of exactly 49 hyphen characters and must be
preserved verbatim, exact width. The paragraph terminates with `EXIT.` at L131. There is NO equivalent
paragraph in the sibling reader CBACT02C, which makes the DOUBLE-DISPLAY pattern unique to CBACT01C.

### `0000-ACCTFILE-OPEN` `[app/cbl/CBACT01C.cbl:L133-L149]`

Opens ACCTFILE as INPUT at L135. Status `'00'` at L136 moves 0 to `APPL-RESULT` (success); any other
status moves 12 to `APPL-RESULT` at L139 and dispatches the error chain at L144-L147: DISPLAY the L144
literal `'ERROR OPENING ACCTFILE'` (single-word `'ACCTFILE'` — this is the unique deviation in the 3-way
error message inconsistency), `MOVE ACCTFILE-STATUS TO IO-STATUS`, perform `9910-DISPLAY-IO-STATUS`,
perform `9999-ABEND-PROGRAM`. The paragraph terminates with `EXIT.` at L149.

### `9000-ACCTFILE-CLOSE` `[app/cbl/CBACT01C.cbl:L151-L167]`

Closes ACCTFILE at L153. Status `'00'` at L154 subtracts `APPL-RESULT` from itself (success); any other
status adds 12 to zero giving `APPL-RESULT` at L157 and dispatches the error chain at L162-L165: DISPLAY
the L162 literal `'ERROR CLOSING ACCOUNT FILE'` (two-word `'ACCOUNT FILE'`, matching L110 read-error
shape but contrasting with the L144 open-error shape), `MOVE ACCTFILE-STATUS TO IO-STATUS`, perform
`9910-DISPLAY-IO-STATUS`, perform `9999-ABEND-PROGRAM`. The paragraph terminates with `EXIT.` at L167.

### `9999-ABEND-PROGRAM` `[app/cbl/CBACT01C.cbl:L169-L173]`

Emits the DISPLAY `'ABENDING PROGRAM'` literal at L170, moves 0 to `TIMING` at L171, moves 999 to
`ABCODE` at L172, then calls the Language Environment abend service via `CALL 'CEE3ABD'` at L173. The
abend code `999` is hard-coded and must be preserved in the Java translation as the exit code of the
sealed exception hierarchy or the `System.exit(999)` site, depending on whether the program is invoked
in-process or as a shaded jar per AAP §0.7.2. Note the NUMERIC 4-digit prefix `9999-` — preserved
verbatim in the corresponding Java `@CobolParagraph` annotation value.

### `9910-DISPLAY-IO-STATUS` `[app/cbl/CBACT01C.cbl:L176-L189]`

Decodes the two-byte `IO-STATUS` value into the four-character `IO-STATUS-04` display field. The IF
branch at L177-L183 handles the case where `IO-STATUS` is non-numeric OR `IO-STAT1 = '9'`: it copies
`IO-STAT1` to `IO-STATUS-04(1:1)`, zeroes `TWO-BYTES-BINARY`, copies `IO-STAT2` to `TWO-BYTES-RIGHT`,
moves the binary value back to `IO-STATUS-0403`, and DISPLAYs the L183 literal `'FILE STATUS IS: NNNN'`
followed by `IO-STATUS-04`. The ELSE branch at L184-L187 handles the normal numeric case: it moves
`'0000'` to `IO-STATUS-04`, overlays `IO-STATUS` into positions 3-4, and DISPLAYs the L187 literal
`'FILE STATUS IS: NNNN'` followed by `IO-STATUS-04`. The literal prefix is IDENTICAL in both branches —
the differentiation is purely in how `IO-STATUS-04` is formatted. The paragraph terminates with `EXIT.`
at L189.

## Verbatim DISPLAY message catalog

The complete inventory of stdout-emitting DISPLAY sites in `CBACT01C` is enumerated below with verbatim
literals. The Java translation must emit each of these in byte-for-byte identical form for the golden
test to pass.

1. L71 program START banner: `` `'START OF EXECUTION OF PROGRAM CBACT01C'` ``
2. L78 main-loop full-record dump: `DISPLAY ACCOUNT-RECORD` — emits the full 300-byte ACCOUNT-RECORD verbatim (one stdout line per record; this is the SECOND half of the DOUBLE-DISPLAY pair)
3. L85 program END banner: `` `'END OF EXECUTION OF PROGRAM CBACT01C'` ``
4. L119 field 1/11: `` `'ACCT-ID                 :'   ACCT-ID` `` (17 trailing spaces before colon)
5. L120 field 2/11: `` `'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS` ``
6. L121 field 3/11: `` `'ACCT-CURR-BAL           :'   ACCT-CURR-BAL` ``
7. L122 field 4/11: `` `'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT` ``
8. L123 field 5/11: `` `'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT` ``
9. L124 field 6/11: `` `'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE` ``
10. L125 field 7/11: `` `'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE` `` (COBOL sic spelling `EXPIRAION` preserved per AAP §0.7.1)
11. L126 field 8/11: `` `'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE` ``
12. L127 field 9/11: `` `'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT` ``
13. L128 field 10/11: `` `'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT` ``
14. L129 field 11/11: `` `'ACCT-GROUP-ID           :'   ACCT-GROUP-ID` ``
15. L130 separator: a literal string of exactly 49 hyphen characters, no padding around the run
16. L110 read error: `` `'ERROR READING ACCOUNT FILE'` `` (two-word `'ACCOUNT FILE'`)
17. L144 open error: `` `'ERROR OPENING ACCTFILE'` `` (single-word `'ACCTFILE'` — this is the deviation in the 3-way inconsistency)
18. L162 close error: `` `'ERROR CLOSING ACCOUNT FILE'` `` (two-word `'ACCOUNT FILE'`)
19. L170 abend banner: `` `'ABENDING PROGRAM'` ``
20. L183 file status (IF branch of `9910-DISPLAY-IO-STATUS`): `` `'FILE STATUS IS: NNNN'` `` followed by the decoded `IO-STATUS-04`
21. L187 file status (ELSE branch of `9910-DISPLAY-IO-STATUS`): identical literal `` `'FILE STATUS IS: NNNN'` `` followed by `IO-STATUS-04` — paired emission with L183

### 3-way error message inconsistency (CBACT01C-specific)

CBACT01C is the only CardDemo sequential reader whose three error-path DISPLAYs use a 2-vs-1 split on
the FILE word: L110 (read-error) and L162 (close-error) both use the two-word literal `'ACCOUNT FILE'`,
while L144 (open-error) uses the single-word literal `'ACCTFILE'`. AAP §0.7.1 mandates that this
inconsistency be preserved exactly as-is in the Java translation; any code that normalizes the three
error literals to a common form fails the golden-record contract. The Java sealed exception hierarchy
that translates the COBOL `FILE STATUS` codes must emit each of these three distinct literals at the
corresponding translated paragraph methods.

## Read-outcome branches

The dispatch in `1000-ACCTFILE-GET-NEXT` produces three observable outcomes per `READ`:

- `ACCTFILE-STATUS = '00'` `[app/cbl/CBACT01C.cbl:L94]` -> success -> `MOVE 0 TO APPL-RESULT` at L95 -> `PERFORM 1100-DISPLAY-ACCT-RECORD` at L96 (emits 12 stdout lines) -> control returns to the main loop at L77 which then emits `DISPLAY ACCOUNT-RECORD` at L78 (1 stdout line) for a total of 13 stdout lines per successful read.
- `ACCTFILE-STATUS = '10'` `[app/cbl/CBACT01C.cbl:L98]` -> EOF -> `MOVE 16 TO APPL-RESULT` at L99 -> `IF APPL-EOF` at L107 succeeds -> `MOVE 'Y' TO END-OF-FILE` at L108 -> main loop terminates on next iteration; NO stdout emission from this branch.
- Any other status -> error -> `MOVE 12 TO APPL-RESULT` at L101 -> falls through to L110-L113: DISPLAY `'ERROR READING ACCOUNT FILE'`, MOVE `ACCTFILE-STATUS` to `IO-STATUS`, PERFORM `9910-DISPLAY-IO-STATUS` (emits `'FILE STATUS IS: NNNN'` plus decoded code), PERFORM `9999-ABEND-PROGRAM` (emits `'ABENDING PROGRAM'`, then `CALL 'CEE3ABD'` at L173 terminates the process).
- NO reject codes: CBACT01C does NOT write reject records. There is no reject-file port in the SELECT inventory at L29-L33; the only port is ACCTFILE-FILE, used INPUT-only.

## Single-output stdout.txt byte contract

The golden contract has exactly one output stream: stdout, captured to `stdout.txt`. The expected byte
layout for the happy-path 50-record fixture is:

- 1 line: L71 START banner
- 50 records, each emitting 13 stdout lines: 12 from `1100-DISPLAY-ACCT-RECORD` (11 field DISPLAYs L119-L129 plus 1 separator L130) plus 1 from L78 `DISPLAY ACCOUNT-RECORD`
- 1 line: L85 END banner

Total: 1 + 50 * 13 + 1 = 652 lines.

Line endings are LF only; no CRLF. The file encoding is UTF-8 but the bytes on disk are 7-bit ASCII
because the EBCDIC source bytes are transcoded to ASCII by the upstream capture pipeline (the fixture in
`app/data/ASCII/` is already pre-transcoded; see AAP §0.6.5 for the codepage-handling contract). The
byte-for-byte parity rule is absolute: the Java implementation's stdout must equal the captured COBOL
`stdout.txt` byte-for-byte; any divergence — including extra trailing whitespace, missing trailing
newline, or substituted Unicode characters — fails the test.

NO deterministic Clock is required to reproduce this stdout. `CBACT01C` does NOT invoke
`FUNCTION CURRENT-DATE` anywhere in its 193-line source; the output is deterministic purely as a function
of the input bytes. This contrasts with programs such as `CBSTM03A` or `CBTRN02C` which DO invoke
`FUNCTION CURRENT-DATE` and therefore require a deterministic `java.time.Clock` injection for golden
reproducibility.

Re-capture trigger: any change to `app/cbl/CBACT01C.cbl`, `app/cpy/CVACT01Y.cpy`, or
`app/data/ASCII/acctdata.txt` invalidates the previously captured `stdout.txt` and requires a re-capture
per the procedure in `java/MIGRATION_NOTES.md` §1.6.

### Canonical per-record 13-line emission block

For each successfully-read account record, stdout emits the following 13 lines in this exact order. Line
numbering below is relative to the start of the per-record block, not absolute within `stdout.txt`.

- Line 1 of 13: L119 `'ACCT-ID                 :'   ACCT-ID`
- Line 2 of 13: L120 `'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS`
- Line 3 of 13: L121 `'ACCT-CURR-BAL           :'   ACCT-CURR-BAL`
- Line 4 of 13: L122 `'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT`
- Line 5 of 13: L123 `'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT`
- Line 6 of 13: L124 `'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE`
- Line 7 of 13: L125 `'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE` (sic spelling preserved)
- Line 8 of 13: L126 `'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE`
- Line 9 of 13: L127 `'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT`
- Line 10 of 13: L128 `'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT`
- Line 11 of 13: L129 `'ACCT-GROUP-ID           :'   ACCT-GROUP-ID`
- Line 12 of 13: L130 separator: 49 hyphen characters
- Line 13 of 13: L78 `DISPLAY ACCOUNT-RECORD` — full 300-byte record dump (the SECOND half of DOUBLE-DISPLAY)

Lines 1-12 of the block originate from `1100-DISPLAY-ACCT-RECORD` invoked at L96 inside
`1000-ACCTFILE-GET-NEXT`; line 13 originates from L78 in the main loop, which executes only after L77's
`IF END-OF-FILE = 'N'` guard confirms the record is valid. The block multiplied 50 times accounts for
650 of the 652 total stdout lines; the remaining 2 lines are the START banner at the head of `stdout.txt`
and the END banner at the tail.

### Whitespace and padding requirements

The captured `stdout.txt` preserves COBOL's exact stdout-write semantics. Each line is terminated by a
single LF byte; no CRLF, no trailing whitespace except as the COBOL DISPLAY statement itself emits.
Numeric fields with `S9(10)V99` PIC declarations are formatted with COBOL sign overpunch on the trailing
digit in production EBCDIC output; in the pre-transcoded ASCII fixture the COBOL display form is already
the byte sequence that the Java translation must emit. The Java translation MUST NOT rewrite numeric
output to use Java-style negative signs (`-`) or decimal points; it MUST emit the exact COBOL display
bytes that the L78 and L121-L128 DISPLAY sites would produce on the mainframe.

## No opened-but-unused files

CBACT01C operates with a strict single-port profile: the only file declared in the FILE-CONTROL section
at `[app/cbl/CBACT01C.cbl:L28-L33]` is `ACCTFILE-FILE`, and it is the only file the program opens, reads,
and closes. There are no opened-but-unused files, no auxiliary input files, no reject output files, and
no report output files. This single-port profile is a useful contrast with the multi-port batch
posting programs: `CBTRN01C` has a 6-port profile and `CBTRN02C` has an even larger port count covering
input transactions, cross-reference lookups, account I/O, transaction-category-balance I/O, daily-reject
writes, and the master transaction file. The Java translation of CBACT01C therefore injects exactly one
constructor parameter — an `AccountRepository` port — and no other adapters.

## Preserved behaviors and idiosyncrasies (DO NOT FIX)

The COBOL source contains several idiosyncrasies that look like defects but MUST be preserved verbatim
per the minimal-change clause in AAP §0.7.1. The Java translation MUST NOT "fix" any of the following:

1. DOUBLE-DISPLAY pattern: each successful read emits the account record TWICE — first field-by-field via `1100-DISPLAY-ACCT-RECORD` at L96 (12 stdout lines: 11 fields plus 1 separator), then again as a full 300-byte record dump via `DISPLAY ACCOUNT-RECORD` at L78 (1 stdout line). Total 13 stdout lines per successful read. The translation MUST preserve both emissions and their ordering.
2. 3-way error message inconsistency: L110 read-error and L162 close-error use the two-word literal `'ACCOUNT FILE'`; L144 open-error uses the single-word literal `'ACCTFILE'`. The translation MUST emit each of these three distinct literals verbatim.
3. NUMERIC 4-digit paragraph prefixes: `0000-ACCTFILE-OPEN`, `1000-ACCTFILE-GET-NEXT`, `1100-DISPLAY-ACCT-RECORD`, `9000-ACCTFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`. These prefixes MUST be reflected verbatim in the `@CobolParagraph` annotation values on the corresponding Java methods. This contrasts with `CBCUS01C` which uses Z-prefix paragraphs; do NOT mix the conventions.
4. `ACCT-EXPIRAION-DATE` COBOL sic spelling: both the field name in `[app/cpy/CVACT01Y.cpy:L11]` and the DISPLAY literal at `[app/cbl/CBACT01C.cbl:L125]` use the misspelling `EXPIRAION` (missing the second `T`). The translation MUST preserve this misspelling in the Java field name, the DISPLAY label, and any test assertions; do NOT correct it to `EXPIRATION`.
5. 11 field DISPLAYs plus 1 separator in `1100-DISPLAY-ACCT-RECORD`: the paragraph emits exactly 12 stdout lines per call, not 13. The translation MUST emit exactly these 12 lines per `1100` invocation.
6. 49-hyphen separator at L130: the separator is a literal string of exactly 49 hyphen characters. The translation MUST emit this separator at the same width.
7. `ACCT-ADDR-ZIP` omitted from `1100-DISPLAY-ACCT-RECORD`: although the copybook at `[app/cpy/CVACT01Y.cpy:L15]` defines 12 named fields, the 1100 paragraph DISPLAYs only 11 of them — ACCT-ADDR-ZIP at offset 102 is OMITTED. The translation MUST preserve this omission; do NOT add a DISPLAY for ACCT-ADDR-ZIP "to be complete".
8. `MOVE 999 TO ABCODE` before `CALL 'CEE3ABD'` at L172-L173: the hard-coded abend code `999` is preserved verbatim. The translation MUST exit with code 999 (or raise an exception with abend code 999) when the abend path fires.
9. `APPL-RESULT` 88-level conditions: `APPL-AOK VALUE 0` at L62 and `APPL-EOF VALUE 16` at L63 partition the integer outcome space. Per AAP §0.6.10, this becomes a sealed-type hierarchy `AppResult { Ok, Eof, Error }` with permitted records carrying the original integer code.
10. `TWO-BYTES-BINARY` plus `TWO-BYTES-ALPHA REDEFINES` at L53-L56: the COBOL `REDEFINES` construct overlays a numeric BINARY interpretation with a two-byte character interpretation of the same memory region. Per AAP §0.6.2, this becomes a Java `sealed interface` with two record permits and a discriminator-based selector.
11. `9910-DISPLAY-IO-STATUS` emits the IDENTICAL literal `'FILE STATUS IS: NNNN'` in both the IF branch (L183) and the ELSE branch (L187). The translation MUST emit the same literal in both branches; the differentiation is purely in how the trailing `IO-STATUS-04` value is formatted.
12. `EXIT.` paragraph terminators at L116 (1000), L131 (1100), L149 (0000), L167 (9000), L189 (9910): each terminator is a no-op marker. The translation MUST treat each paragraph as a self-contained method whose end is the corresponding `EXIT.` statement; do NOT splice paragraphs together.
13. `MOVE 8 TO APPL-RESULT.` at L134 and `ADD 8 TO ZERO GIVING APPL-RESULT.` at L152: these are explicit "before-check" initializations that look redundant but MUST be preserved as faithful translations of the COBOL site (a constructor-initialized `int applResult = 8;` local variable, NOT a class field).
14. `IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'` at L177-L178: the disjunctive guard MUST be preserved as-is — both subconditions must be testable in the Java translation, with the same short-circuit semantics (Java `||` matches COBOL `OR` evaluation order).

## Java mapping invariants

The Java translation of CBACT01C MUST satisfy each of the following invariants. Any test or code review
that detects a violation MUST fail the change.

1. One Java class per COBOL `PROGRAM-ID`: `com.blitzy.carddemo.application.account.CbAct01C` is the sole translation target; no helper service classes are introduced per AAP §0.4.1.
2. `@CobolProgram("CBACT01C")` annotation on the class declaration, citing the original program ID, source path, and translation date per AAP §0.7.1.
3. `@CobolParagraph` annotations on each translated paragraph method, with annotation values preserving the original NUMERIC 4-digit prefixes (`0000-`, `1000-`, `1100-`, `9000-`, `9999-`, `9910-`) verbatim.
4. Constructor injection of a single `com.blitzy.carddemo.domain.port.AccountRepository` port — no Spring container, no dependency-injection framework, no service-locator pattern per AAP §0.3.6.
5. `AccountRecord` is declared as a Java `record` (not a POJO with getters and setters) per AAP §0.3.2; its 12 named fields plus `byte[178]` filler are immutable.
6. `AccountRecord` exposes `static AccountRecord parse(byte[] buffer)` and `byte[] encode()` factories that together form the byte-level contract with `app/data/ASCII/acctdata.txt`; `parse(encode(r)).equals(r)` must hold for every valid record per AAP §0.3.2.
7. The 5 `BigDecimal` monetary fields (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT) use the `Decimals` utility per AAP §0.6.1 for parse and encode conversion only — CBACT01C performs NO arithmetic on monetary values, so no `MathContext` or `RoundingMode` arithmetic invocation occurs in this translation.
8. The 3 `LocalDate` fields (ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE) use `java.time.LocalDate` per AAP §0.6.4; neither `java.util.Date` nor `java.util.Calendar` nor `java.text.SimpleDateFormat` may appear in the translation.
9. All file I/O uses `java.nio.file` (channels, `Files.newByteChannel`, `Files.readAllBytes`) per AAP §0.6.5; `java.io.File` is forbidden.
10. Batch-run context (when CBACT01C is composed into a batch pipeline) flows via `ScopedValue` per AAP §0.6.6; `ThreadLocal` is forbidden in the translation.
11. NO virtual threads in the CBACT01C translation. The sequential DISPLAY ordering at L78 (full-record dump) following L96 (field-by-field dump) is observable; any concurrent fan-out reorders stdout and breaks the byte-for-byte contract. AAP §0.6.6 explicitly gates virtual threads on safe reordering; that safety does NOT hold here.
12. EBCDIC `IBM-1047` is the default codepage for the file adapter per AAP §0.6.5; per-file overrides (such as `carddemo.file.acctdata.charset`) are supplied via `application.properties`. The test fixture is pre-transcoded ASCII, but the production adapter must support EBCDIC.
13. The `APPL-RESULT` 88-level conditions, the `ACCTFILE-STATUS` outcomes, and the `TWO-BYTES-BINARY / TWO-BYTES-ALPHA` REDEFINES are all translated into sealed-type hierarchies per AAP §0.6.2 and AAP §0.6.10 — closed permits, no enum-only translations.
14. Pattern-matching `switch` is used to dispatch on sealed types with compiler-enforced exhaustiveness — NO `default` branch per AAP §0.7.4. Any missing case fails compilation.
15. The shaded jar produced for `com.blitzy.carddemo.app.ReadAccountDumpApp` runs under `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4.
16. The build uses Java 25 LTS with `--release 25` and NO `--enable-preview` per AAP §0.7.4; preview JEPs (502, 505, 507) are excluded.
17. The source and target language level is Java 25 LTS exclusively; no back-port to earlier versions is permitted.
18. No reflection, no dynamic proxies, no bytecode generation in the translation; the only dynamic dispatch site permitted is `ProgramRegistry` for COBOL `CALL` with a variable program name, and CBACT01C has no such site (it has only `CALL 'CEE3ABD'` at L173 — a static call).
19. `parse(byte[])` validation runs in record canonical constructors using JEP 513 flexible constructor bodies per AAP §0.6.3 — input validation precedes field binding.
20. The L78 `DISPLAY ACCOUNT-RECORD` site emits the encoded form of the record (the result of `AccountRecord.encode()`) so that the stdout bytes match the COBOL output exactly; the L119-L129 field DISPLAYs emit the individual field values formatted in their original COBOL display form (numeric fields with COBOL sign overpunch, dates as the original 10-byte strings).
21. Stdout writes go to standard out via a dedicated `PrintStream` wrapper that flushes after every DISPLAY translation; the wrapper is the only sanctioned write path so the L71 START banner, the L78 full-record dump, the L85 END banner, and every L119-L130 field DISPLAY arrive in the byte-correct order even under JVM buffering defaults.
22. The translation MUST NOT use `System.out.println` directly for emissions that participate in the golden contract; instead it routes through a `DisplayEmitter` collaborator that mirrors the COBOL DISPLAY semantics (including the implicit single-line LF terminator). This wrapper is the same logging surface flagged in `java/MIGRATION_NOTES.md` for any future structured-logging migration.
23. The `CbAct01C` class is package-private to `com.blitzy.carddemo.application.account` if no external caller is needed; if invoked from the `carddemo-app` shaded jar's main class, it is exposed as public with a single public entry method `run()` returning `void` (which throws the sealed exception hierarchy on abend conditions translated from `CEE3ABD`).
24. The translation MUST cite, in a Javadoc header on the class declaration, the original PROGRAM-ID, the source path `app/cbl/CBACT01C.cbl`, and the translation date per AAP §0.7.1. The Javadoc MUST also note the DOUBLE-DISPLAY behavior so subsequent maintainers do not "optimize away" the apparently redundant L78 emission.

## Test class override map

`CbAct01CGoldenTest` extends `GoldenRecordTest` and supplies the following overrides. The test class
remains `@Disabled` until the captured `stdout.txt` is committed per AAP §0.6.11.

```java
@Override protected Class<?> programClass() {
    return com.blitzy.carddemo.application.account.CbAct01C.class;
}
@Override protected Path inputFile() {
    return resolveClasspath("golden/cbact01c/input/acctdata.txt");
}
@Override protected List<Path> auxiliaryInputs() {
    return List.of();
}
@Override protected Path expectedOutputFile() {
    return resolveClasspath("golden/cbact01c/expected/stdout.txt");
}
```

The `auxiliaryInputs()` override returns an empty list because CBACT01C has a single-port profile (no
auxiliary inputs beyond `acctdata.txt`). The classpath paths above are resolved by the base class
helper `resolveClasspath(String)` which maps the relative resource path to the appropriate filesystem
location at test runtime, including REFERENCE-only paths into `app/data/ASCII/` per AAP §0.4.1.

### Activation procedure (removing @Disabled)

Once the captured COBOL-side `stdout.txt` is committed to this folder, the `@Disabled` annotation on
`CbAct01CGoldenTest` is removed in the same commit. The activation criteria are: (a) `stdout.txt` is
present in `golden/cbact01c/expected/`, (b) its byte count equals the expected value (52 lines for the
empty-input fixture or 652 lines for the 50-record fixture, with the relevant fixture pointer set on
`inputFile()`), (c) the `CbAct01C` translation compiles and the local `mvn -B -ntp test` invocation
passes for the `carddemo-tests` module under Java 25 LTS. Once those criteria are met, the test runs on
every PR as part of the non-negotiable PR gate per AAP §0.6.11.

## Capture procedure cross-reference

- The authoritative capture procedure for `stdout.txt` lives in `java/MIGRATION_NOTES.md` §1.6, which documents the exact COBOL build, run, and stdout-redirect commands used to produce the expected output bytes per AAP §0.7.5.
- Determinism: no deterministic `Clock` is required because `CBACT01C` does NOT invoke `FUNCTION CURRENT-DATE`. The COBOL run is purely a function of the input bytes; multiple runs against the same `acctdata.txt` produce byte-identical `stdout.txt`.
- Re-capture trigger: any change to `app/cbl/CBACT01C.cbl`, `app/cpy/CVACT01Y.cpy`, or `app/data/ASCII/acctdata.txt` invalidates the previously captured `stdout.txt` and requires a re-capture. The capture procedure in `java/MIGRATION_NOTES.md` §1.6 is to be followed verbatim each time.
- PAN and PII exposure: the `ACCT-ID` field at `[app/cpy/CVACT01Y.cpy:L5]` is an 11-digit account identifier, NOT a 16-digit card PAN. The PAN-masking requirements in AAP §0.7.2 do NOT apply to ACCT-ID; PAN-masking applies to `CARD-NUM` in `CBACT02C` and to `XREF-CARD-NUM` in `CBACT03C`. The plaintext `ACCT-ID` may appear unmasked in `stdout.txt` and in test logs.

## Scenario inventory

The following observable scenarios are covered by the golden-record test contract for CBACT01C. The
happy-path scenario (#1) is the primary contract; the others document required behaviors that the
captured `stdout.txt` and supplementary fixture variants must also support.

1. Happy-path 50-record sequential read producing the canonical 652-line `stdout.txt`: 1 START banner plus 50 records times 13 emission-lines per record plus 1 END banner.
2. DOUBLE-DISPLAY pattern verification: each of the 50 records emits exactly 13 stdout lines (12 from `1100-DISPLAY-ACCT-RECORD` at L96 followed by 1 from `DISPLAY ACCOUNT-RECORD` at L78), and the field-by-field block always precedes the full-record dump in stdout order.
3. Field-by-field dump emission order verification: the 11 field DISPLAYs in `1100-DISPLAY-ACCT-RECORD` emit in exactly this order: ACCT-ID, ACCT-ACTIVE-STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, ACCT-GROUP-ID (sequence preserved from L119-L129).
4. 49-hyphen separator preservation: at the end of each `1100-DISPLAY-ACCT-RECORD` invocation the separator at L130 emits exactly 49 hyphen characters as its own stdout line.
5. ACCT-EXPIRAION-DATE label sic-spelling preservation: the L125 DISPLAY label uses `'ACCT-EXPIRAION-DATE     :'` verbatim (missing the second `T` in `EXPIRATION`), and the corresponding Java field, getter, and Javadoc must mirror this spelling per AAP §0.7.1.
6. EOF detection via `ACCTFILE-STATUS = '10'`: when the READ at L93 returns status `'10'`, `APPL-EOF` fires at L107, `END-OF-FILE` is set to `'Y'` at L108, and the main loop terminates without emitting `DISPLAY ACCOUNT-RECORD` for the (non-existent) record; the END banner at L85 emits next.
7. Open error path (negative scenario fixture): when the file cannot be opened, L144 emits the single-word literal `'ERROR OPENING ACCTFILE'`, `9910-DISPLAY-IO-STATUS` emits `'FILE STATUS IS: NNNN'` plus the decoded code, and `9999-ABEND-PROGRAM` emits `'ABENDING PROGRAM'` followed by `CALL 'CEE3ABD'` (process exits with abend code 999).
8. Read error path (negative scenario fixture): when a READ at L93 returns a non-`'00'`/non-`'10'` status, L110 emits the two-word literal `'ERROR READING ACCOUNT FILE'`, then `9910-DISPLAY-IO-STATUS` plus `9999-ABEND-PROGRAM` fire as above.
9. Close error path (negative scenario fixture): when CLOSE at L153 returns a non-`'00'` status, L162 emits the two-word literal `'ERROR CLOSING ACCOUNT FILE'`, then `9910-DISPLAY-IO-STATUS` plus `9999-ABEND-PROGRAM` fire as above.
10. ABEND path emits `'ABENDING PROGRAM'` at L170 then `9910-DISPLAY-IO-STATUS` at L183/L187: the sequence of stdout lines in an abend scenario is (paragraph entry) -> respective DISPLAY at L110/L144/L162 -> `'FILE STATUS IS: NNNN' IO-STATUS-04` -> `'ABENDING PROGRAM'` -> process exit. The Java translation MUST preserve this ordering.
11. Determinism re-run: two consecutive runs against the same `acctdata.txt` produce byte-identical `stdout.txt`; no Clock injection required.
12. Empty-input fixture (zero records): the START banner emits, the main loop's first READ returns status `'10'` (EOF), the loop terminates without emitting any record, the END banner emits; total stdout is exactly 2 lines.
13. Single-record fixture (one record): the START banner emits, one record is read and emits its 13-line block (12 from `1100-DISPLAY-ACCT-RECORD` plus 1 from L78), the next READ returns status `'10'` (EOF), the END banner emits; total stdout is exactly 15 lines (1 plus 13 plus 1).
14. Sign-overpunch fidelity: any `S9(10)V99` field with a negative value in `acctdata.txt` emits stdout bytes that encode the COBOL sign overpunch on the trailing digit (for example `J` for `-1`, `K` for `-2`, through `R` for `-9`); the Java translation MUST NOT substitute Java-style leading minus signs.
15. FILLER preservation: any non-space bytes in the 178-byte FILLER region survive intact through the L78 `DISPLAY ACCOUNT-RECORD` emission; the Java `AccountRecord.encode()` method MUST emit the exact 178 filler bytes that `parse(byte[])` ingested.

## Contrast matrix

CBACT01C is one of five simple sequential reader programs in the CardDemo COBOL inventory. The matrix
below highlights its distinctive features versus its closest siblings; understanding these differences
is essential for any agent translating multiple readers in parallel.

| Aspect | CBACT01C | CBACT02C | CBACT03C | CBCUS01C | CBTRN01C |
|---|---|---|---|---|---|
| Display pattern | DOUBLE-DISPLAY (1100 field-by-field plus L78 full-record) | SINGLE-DISPLAY (full-record dump only) | DOUBLE-DISPLAY (no 1100; uses an inline second emission) | DOUBLE-DISPLAY (Z-prefix paragraphs) | (varies; multi-port batch loader) |
| Paragraph prefix | NUMERIC 4-digit (`0000-`, `1000-`, `1100-`, `9000-`, `9999-`, `9910-`) | NUMERIC 4-digit | NUMERIC 4-digit | Z-prefix (Z-CUST-OPEN, Z-CUST-GET-NEXT, etc.) | NUMERIC 4-digit |
| Error message style | 3-way: `'ACCOUNT FILE'` (L110, L162) plus `'ACCTFILE'` (L144) | CONSISTENT: `'CARDFILE'` three times | (per source) | CUSTFILE-vs-CUSTOMER-FILE 2-vs-1 split | (per source) |
| Active port count | 1 (ACCTFILE-FILE only) | 1 (CARDFILE only) | 1 (XREFFILE only) | 1 (CUSTFILE only) | 6 (multi-port batch) |
| PII fields requiring masking | NONE (ACCT-ID is an account identifier, not a PAN) | CARD-NUM (16-digit PAN — mask all but last 4) | XREF-CARD-NUM (16-digit PAN — mask all but last 4) | CUST-SSN, CUST-GOVT-ISSUED-ID, CUST-DOB | (varies) |
| `FUNCTION CURRENT-DATE` invocation | NO | NO | NO | NO | varies |
| Deterministic Clock required | NO | NO | NO | NO | varies |
| Stdout total (50-record fixture) | 652 lines (1 plus 50*13 plus 1) | varies (typically 1 plus 50*1 plus 1) | varies | varies | varies |

## Source lineage

The following source files are REFERENCE-only inputs to this contract. They are NEVER copied into the
`java/` tree per AAP §0.4.1 and they are NEVER modified per AAP §0.2.2.

- `app/cbl/CBACT01C.cbl` (193 lines) — `PROGRAM-ID CBACT01C` at L23, `AUTHOR AWS` at L24, single SELECT for `ACCTFILE-FILE` at L29-L33, FD declaration at L37-L40, `COPY CVACT01Y` at L45, PROCEDURE DIVISION at L70, main loop at L74-L81, DOUBLE-DISPLAY at L78 plus L96 -> 1100 paragraph; 6 numbered paragraphs total (`0000-ACCTFILE-OPEN`, `1000-ACCTFILE-GET-NEXT`, `1100-DISPLAY-ACCT-RECORD`, `9000-ACCTFILE-CLOSE`, `9999-ABEND-PROGRAM`, `9910-DISPLAY-IO-STATUS`).
- `app/cpy/CVACT01Y.cpy` (20 lines) — 300-byte `ACCOUNT-RECORD` layout: 12 named fields plus 178-byte FILLER; field offsets and PIC clauses match the `AccountRecord` Java record declaration.
- `app/data/ASCII/acctdata.txt` (15,050 bytes; 50 records of 301 bytes per line) — ACCTFILE input fixture; pre-transcoded from EBCDIC to ASCII; referenced from `golden/cbact01c/input/` via classpath relative path; read-only.
- `app/jcl/READACCT.jcl` — IDCAMS PRINT job; translated to `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadAccountDumpApp.java` per AAP §0.4.1; the test class bypasses the JCL-derived main class and invokes `CbAct01C` directly via the constructor-injected `AccountRepository` port.

## Cross-references

- Sibling input contract: `../input/README.md` — documents the input side of the same `cbact01c/` fixture pair.
- Test class file: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbAct01CGoldenTest.java`.
- Test base class: `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java`.
- Class under test: `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct01C.java`.
- Domain record: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java`.
- Repository port: `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/AccountRepository.java`.
- File adapter: `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FileAccountRepository.java`.
- JCL-derived main class: `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/ReadAccountDumpApp.java`.
- Capture procedure: `java/MIGRATION_NOTES.md` §1.6.
- Sibling fixture READMEs: `../../cbact02c/expected/README.md`, `../../cbact03c/expected/README.md`, `../../cbcus01c/expected/README.md`.

## Authority references

Each AAP section cited above is summarized below for quick orientation; consult the cited section in the
Agent Action Plan document for the full normative text.

- AAP §0.1.1 — Core refactoring objective: byte-for-byte file fidelity in the COBOL -> Java 25 LTS migration with idiom-for-idiom translation discipline.
- AAP §0.2.1 — Exhaustively in-scope wildcards including `golden/**/*` fixtures under `java/carddemo-tests/src/test/resources/`.
- AAP §0.2.2 — `app/` tree IMMUTABLE; reference implementation status preserved; no modifications permitted.
- AAP §0.3.1 — Refactored structure planning; `<program>/input/` plus `<program>/expected/` directory pair convention.
- AAP §0.3.2 — Design pattern applications: records pattern, sealed-type pattern, `parse(byte[])` plus `encode()` byte-level contract.
- AAP §0.3.4 — JVM tuning baseline: `-XX:+UseCompactObjectHeaders` (JEP 519) and Shenandoah generational mode (JEP 521).
- AAP §0.3.6 — Hexagonal architecture diagram; ports and adapters separation; domain pure, adapters peripheral.
- AAP §0.4.1 — File-by-file transformation plan; CBACT01C maps to `CbAct01C` in `com.blitzy.carddemo.application.account`.
- AAP §0.6.1 — Decimal arithmetic fidelity via `Decimals` utility; `MathContext.DECIMAL128`, `RoundingMode.HALF_EVEN` for ROUNDED, `RoundingMode.DOWN` otherwise.
- AAP §0.6.2 — REDEFINES sealed-type translation; one sealed interface with one record permit per alternative interpretation.
- AAP §0.6.3 — Flexible constructor bodies (JEP 513) for canonical-constructor input validation before field binding.
- AAP §0.6.4 — Date semantics: `java.time` types (`LocalDate`, `LocalDateTime`, `LocalTime`, `Period`, `Duration`); `java.util.Date` and `Calendar` forbidden.
- AAP §0.6.5 — File I/O exactness via `java.nio.file`; `java.io.File` forbidden; default codepage `IBM-1047`.
- AAP §0.6.6 — Batch throughput strategy: virtual threads only where reordering is safe; `ScopedValue` replaces `ThreadLocal`.
- AAP §0.6.8 — Program-by-program COBOL-to-Java class mapping checklist enumerating all 28 program translations.
- AAP §0.6.10 — Sealed-type hierarchies introduced for closed business taxonomies and 88-level partitions.
- AAP §0.6.11 — Golden-record harness design; non-negotiable PR gate; tests `@Disabled` until COBOL captures available.
- AAP §0.7.1 — Refactor discipline: minimal-change clause; preserve sic spellings, error codes, padding, sort orders.
- AAP §0.7.2 — Security: PAN masking (last-4 only in logs), plaintext password preservation (separate effort for hashing).
- AAP §0.7.4 — Explicitly forbidden features: no `default` switch branches, no preview JEPs (502, 505, 507), no `--enable-preview`.
- AAP §0.7.5 — Capture procedure documentation; carried-forward TODO markers tracked in `java/MIGRATION_NOTES.md`.
- AAP §0.8.1 — Citation discipline: every claim about existing system carries an inline `[<path>:<locator>]` citation.

## DO NOT modify supplementary

This README is the contract specification; the sibling `stdout.txt` (when committed) is the captured
byte-for-byte expected output. Neither file may be edited ad-hoc to "fix" a perceived inconsistency in
the COBOL source — any such edit would silently mask divergence between the Java implementation and the
COBOL baseline. The 3-way error message inconsistency at L110, L144, and L162 MUST be preserved
verbatim; the NUMERIC 4-digit paragraph prefixes MUST appear verbatim in `@CobolParagraph` annotation
values; the COBOL sic spelling `ACCT-EXPIRAION-DATE` MUST be preserved everywhere it appears (field
name, DISPLAY label, Java field, getter, Javadoc, test assertions). Any change to
`app/cbl/CBACT01C.cbl`, `app/cpy/CVACT01Y.cpy`, or `app/data/ASCII/acctdata.txt` invalidates the
captured `stdout.txt` and requires a re-capture per `java/MIGRATION_NOTES.md` §1.6; edits to this
README or to the captured `stdout.txt` outside of that procedure are explicitly forbidden.
