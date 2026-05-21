# Baseline Parity Runbook

This runbook is the operating procedure for the **byte-identical baseline parity** layer of the CardDemo migration test suite. It documents how to capture, refresh, consume, and triage the COBOL reference outputs that every Spring Batch job in the migrated Java 17 LTS / Spring Boot 3.x codebase is judged against.

It is the companion to [`test-strategy.md`](test-strategy.md), which defines the broader four-layer test pyramid. Read this file when you need to capture a fresh COBOL reference output, when a `*BaselineParityIT` test class fails and you have to diagnose the divergence, or when you are wiring a new batch-job parity test into the suite.

## 1. The Parity Contract

The parity contract is the immutable boundary between the original AWS CardDemo COBOL/JCL mainframe application and its migrated Java implementation. The Agent Action Plan (AAP §0.1.2) restates the contract verbatim:

> - Input and output file formats and record layouts MUST remain identical
> - All financial calculation results MUST match COBOL baseline output exactly
> - External interfaces consumed by downstream systems MUST NOT change
> - Baseline comparison test: feed identical input files to COBOL baseline and Java implementation; diff output files — zero delta required

The implication is unambiguous: the migrated Java implementation of each Spring Batch job is judged against a captured COBOL reference output. Any single byte difference — including a trailing whitespace character, a sign-overpunch character mismatch, a different line-ending convention, or a one-digit drift caused by `RoundingMode.HALF_UP` instead of `RoundingMode.HALF_EVEN` — causes the corresponding `*BaselineParityIT` class to fail. This strictness is intentional. Financial-system parity tolerates no drift, and the captured COBOL output is the source of truth.

The parity contract spans all **5 Spring Batch jobs** migrated from the source repository's JCL inventory (`POSTTRAN.jcl`, `INTCALC.jcl`, `COMBTRAN.jcl`, `CREASTMT.JCL`, `TRANREPT.jcl`). Every job has exactly one paired `[JobName]BaselineParityIT.java` class, exactly one input fixture set, and exactly one captured expected-output file (two outputs for the statement job, which writes both text and HTML).

## 2. Test Layer Overview

Parity verification lives at the **Baseline Parity Integration Test** layer — the third layer of the four-layer test pyramid documented in [`test-strategy.md`](test-strategy.md#2-the-four-layer-test-pyramid). It sits above unit tests (which mock the file boundary) and below end-to-end tests (which orchestrate multiple jobs in sequence). Each batch job has exactly one paired `*BaselineParityIT` class that wires the parity contract into a runnable JUnit 5 + Spring Batch integration test.

The canonical baseline-parity test pattern is:

- Test class lives at `src/test/java/com/aws/carddemo/batch/[JobName]BaselineParityIT.java`.
- Test extends `AbstractBatchIT` (shared Testcontainers PostgreSQL 16 container, Spring Batch test wiring).
- Test method launches the Spring Batch job via `jobLauncherTestUtils.launchJob(jobParameters)`.
- After job completion, the produced output file is located at a deterministic temp path (e.g., `target/test-output/posted.txt`).
- Test calls `BaselineDiffUtil.assertByteEqual(actualPath, expectedPath)` where `expectedPath` points to `src/test/resources/baseline/expected/posted.txt`.

An annotated example, showing the call sequence end-to-end:

```java
@Test
void postingJob_producesOutputIdenticalToCobolBaseline() throws Exception {
    // Stage canonical input fixtures to the batch job's input directory
    Files.copy(FixtureLoader.loadAsBytes("/baseline/input/dailytran.txt"), inputPath);

    JobExecution execution = jobLauncherTestUtils.launchJob(jobParameters);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    BaselineDiffUtil.assertByteEqual(
        outputDir.resolve("posted.txt"),
        Path.of("src/test/resources/baseline/expected/posted.txt"));
}
```

Three assertions are layered: that the Spring Batch `JobExecution` completed cleanly, that the produced output file is on disk where the job's `ItemWriter` was configured to place it, and — the strict assertion — that the produced bytes match the captured COBOL reference output exactly.

## 3. Directory Layout

All baseline-parity artefacts live under `src/test/resources/` in a tree that mirrors AAP §0.4.4. Inputs are canonical golden datasets (one-for-one copies of the source repository's `app/data/ASCII/*.txt`); expected outputs are captured COBOL reference files; edge-case CSVs drive `@ParameterizedTest` cases that exercise specific code paths without requiring a full batch run.

```
src/test/resources/
├── baseline/
│   ├── input/                          # Canonical golden inputs (copies of app/data/ASCII/*.txt)
│   │   ├── acctdata.txt                # 50 records, 300 chars each (15 KB)
│   │   ├── carddata.txt                # 50 records, 150 chars each (7.5 KB)
│   │   ├── cardxref.txt                # 50 records, 36 chars each (1.8 KB)
│   │   ├── custdata.txt                # 50 records, 500 chars each (25 KB)
│   │   ├── dailytran.txt               # 300 records, 350 chars each (105 KB)
│   │   ├── discgrp.txt                 # 51 records including DEFAULT and ZEROAPR (2.6 KB)
│   │   ├── tcatbal.txt                 # 50 records, 50 chars each (2.5 KB)
│   │   ├── trancatg.txt                # 18 categories, 60 chars each (1.1 KB)
│   │   └── trantype.txt                # 7 types, 60 chars each (~0.4 KB)
│   └── expected/                       # Captured COBOL reference outputs (right-hand operand of BaselineDiffUtil)
│       ├── posted.txt                  # CBTRN02C posting output  → POSTTRAN.jcl
│       ├── tcatbal_after_interest.txt  # CBACT04C interest output → INTCALC.jcl
│       ├── combined.txt                # DFSORT combined transactions → COMBTRAN.jcl
│       ├── statements_text.txt         # CBSTM03A statements (text)  → CREASTMT.JCL
│       ├── statements_html.txt         # CBSTM03A statements (HTML)  → CREASTMT.JCL
│       └── transaction_report.txt      # CBTRN03C report output      → TRANREPT.jcl
└── fixtures/
    └── edge/                           # Curated CSVs for @ParameterizedTest @CsvFileSource
        ├── interest_zero_balance.csv
        ├── interest_zeroapr_skip.csv
        ├── interest_default_fallback.csv
        ├── interest_halfeven_boundary.csv
        ├── posting_reject_codes.csv
        ├── overflow_boundary.csv
        ├── lookup_invalid_keys.csv
        ├── date_validation_variants.csv
        ├── status_code_mappings.csv
        └── eof_boundary.csv
```

Important notes on each subtree:

- **`baseline/input/`** is a one-for-one copy of `app/data/ASCII/*.txt`. The copy decouples test execution from the source-of-truth COBOL repository (the COBOL artefacts may move or change format over the migration's lifetime; the test fixture remains stable). Tests must read from `baseline/input/`, never from `app/data/`.
- **`baseline/expected/`** is captured **once per migration cycle**. Refresh occurs only when input fixtures change or when the COBOL source itself changes (rare — the migration is unidirectional from COBOL into Java). The capture procedure is detailed in [Section 5](#5-capturing-or-refreshing-a-baseline).
- **`fixtures/edge/`** is curated independent of the COBOL baseline. Edge-case rows are designed to exercise specific code paths (zero balance, ZEROAPR skip, HALF_EVEN boundary, reject codes 100–103, overflow, EOF, null/empty input, date format variants) without requiring a full batch run. These CSVs are consumed by `@ParameterizedTest @CsvFileSource` annotated test methods in service-layer and processor-layer unit tests.

## 4. Per-Job Golden Output Mapping

The authoritative mapping between each Spring Batch job, its COBOL provenance, the canonical input fixtures, and the captured expected-output file is:

| Spring Batch Job (Java) | JCL Source | COBOL Source | Test Class | Input Fixture(s) | Expected Output File |
| ----------------------- | ---------- | ------------ | ---------- | ---------------- | -------------------- |
| `TransactionPostingJob` | `POSTTRAN.jcl` | `CBTRN02C.cbl` | `TransactionPostingBaselineParityIT` | `dailytran.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt` (seeded via Flyway) | `posted.txt` |
| `InterestCalculationJob` | `INTCALC.jcl` | `CBACT04C.cbl` | `InterestCalculationBaselineParityIT` | `tcatbal.txt`, `discgrp.txt`, `acctdata.txt` (seeded via Flyway) | `tcatbal_after_interest.txt` |
| `CombineTransactionsJob` | `COMBTRAN.jcl` | (DFSORT, no COBOL program) | `CombineTransactionsBaselineParityIT` | `dailytran.txt` + interest output (chained) | `combined.txt` |
| `StatementGenerationJob` | `CREASTMT.JCL` | `CBSTM03A.CBL`, `CBSTM03B.CBL` | `StatementGenerationBaselineParityIT` | `acctdata.txt`, `custdata.txt`, `carddata.txt`, `cardxref.txt`, transaction history | `statements_text.txt` + `statements_html.txt` |
| `TransactionReportJob` | `TRANREPT.jcl` | `CBTRN03C.cbl` | `TransactionReportBaselineParityIT` | `dailytran.txt`, `trancatg.txt`, `trantype.txt` | `transaction_report.txt` |

Several special cases require explicit attention:

- The `StatementGenerationJob` test asserts byte-identity against **both** the text output (`statements_text.txt`) and the HTML output (`statements_html.txt`). Both files are captured from a single `CREASTMT.JCL` execution; the test calls `BaselineDiffUtil.assertByteEqual` twice — once per output.
- The `CombineTransactionsJob` is the only job whose source has no COBOL program. It is a pure DFSORT/IDCAMS step migrated to a Spring Batch `CombineTransactionsProcessor` that uses a Java `Comparator` to reproduce the SORT FIELDS ordering documented in `COMBTRAN.jcl`. The expected output (`combined.txt`) is captured by running the DFSORT step against the canonical inputs.
- Date stamps baked into output records — for example, `2022-07-18` derived from the `INTCALC.jcl` `PARM='2022071800'` — must be deterministic. Tests pin the JCL PARM through Spring Batch `JobParameters` and inject a fixed `Clock` so the produced output is reproducible bit-for-bit. The migrated Java code must never call `LocalDate.now()` directly; it must derive the run date from the injected `Clock` or from a job parameter passed in by the test.
- The `TransactionReportJob` parses two date PARMs from the `DATEPARM` DD card (`PARM-START-DATE`, `PARM-END-DATE` in `TRANREPT.jcl`); tests pin these via `JobParameters` exactly as the COBOL job would have received them at submission time.
- The `TransactionPostingJob` consumes four input fixtures simultaneously (one PS file plus three VSAM KSDS files); Flyway test migrations seed the KSDS-equivalent tables before the test runs.

## 5. Capturing or Refreshing a Baseline

This section is the core runbook for capturing a fresh COBOL reference output. Follow these steps verbatim when:

- The input fixtures under `baseline/input/` change (e.g., new transaction records are added).
- The COBOL source itself changes (rare — the migration is unidirectional, but the source repository may receive corrections).
- A new batch job is added to the migration scope.
- The captured reference output becomes corrupted or is lost.

### 5.1 Prerequisites

Before starting the capture procedure, confirm you have:

- Access to a z/OS mainframe environment (TSO/ISPF or equivalent) where the original COBOL programs can be compiled and JCL jobs can be submitted, OR a faithful COBOL emulator (e.g., GnuCOBOL with VSAM emulation, or a containerised z/OS environment).
- Read access to the source repository's `app/cbl/`, `app/jcl/`, `app/cpy/`, and `app/data/ASCII/` directories.
- Write access to a working directory on the mainframe (or emulator) where output datasets can be allocated.
- Optional but recommended: `iconv` or an equivalent tool for EBCDIC↔ASCII conversion if mainframe-native output is produced in EBCDIC.
- Familiarity with the source repository's JCL conventions (PROCs, datasets, parameter cards, the `JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')` directive in `TRANREPT.jcl`).
- A working installation of Java 17 LTS and Maven 3.8+ on the workstation where the captured file will be validated via `mvn -Dit.test='*BaselineParityIT' verify`.

### 5.2 Capture Procedure

Capture one batch job at a time. The procedure below applies to each of the 5 jobs (`POSTTRAN`, `INTCALC`, `COMBTRAN`, `CREASTMT`, `TRANREPT`); repeat all six numbered steps per job.

1. **Stage the canonical input fixtures.** Copy `app/data/ASCII/*.txt` to the mainframe (or emulator) datasets named per the JCL job's `DD` statements. For `POSTTRAN.jcl`, this means staging `dailytran.txt` to the `DALYTRAN` dataset, `acctdata.txt` to the `ACCTDATA` dataset (the JCL references `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`), `carddata.txt` to the equivalent card-master VSAM cluster, and `cardxref.txt` to the `XREFFILE` dataset. If the input is EBCDIC-native, convert from ASCII via `iconv -f UTF-8 -t IBM-1047` and reapply COBOL fixed-width record padding so each record is exactly the `LRECL` declared by the originating copybook.
2. **Compile the COBOL program(s).** Use the source repository's standard compile JCL (typically under `samples/jcl/`) to compile the COBOL program(s) referenced by the job. For `POSTTRAN.jcl`, compile `app/cbl/CBTRN02C.cbl`; for `INTCALC.jcl`, compile `app/cbl/CBACT04C.cbl`; for `CREASTMT.JCL`, compile both `app/cbl/CBSTM03A.CBL` and `app/cbl/CBSTM03B.CBL`; for `TRANREPT.jcl`, compile `app/cbl/CBTRN03C.cbl`. Verify compile-time `RC=0` and confirm the load module landed in the expected library (`AWS.M2.CARDDEMO.LOADLIB` per the `STEPLIB` DD).
3. **Submit the JCL job.** Submit the relevant `app/jcl/*.jcl` (or `*.JCL`) file via TSO `SUBMIT` or its equivalent. For `INTCALC.jcl`, the `PARM='2022071800'` literal on the `EXEC PGM=CBACT04C` card pins the run date — do not change it unless the test fixture is also changing. For `TRANREPT.jcl`, the `SYMNAMES` DD pins `PARM-START-DATE` and `PARM-END-DATE` (e.g., `C'2022-01-01'` and `C'2022-07-06'`). Confirm `RC=0` across all job steps and inspect SYSOUT for any warnings.
4. **Extract the output dataset.** The job writes its output to one or more `DD` datasets. For `POSTTRAN.jcl` the output dataset is `AWS.M2.CARDDEMO.DALYREJS(+1)` (the `DALYREJS` DD); for `INTCALC.jcl` the output is `AWS.M2.CARDDEMO.SYSTRAN(+1)` (the `TRANSACT` DD); for `COMBTRAN.jcl` the output is `AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)` (the `SORTOUT` DD); for `CREASTMT.JCL` the outputs are `AWS.M2.CARDDEMO.STATEMNT.PS` and `AWS.M2.CARDDEMO.STATEMNT.HTML`; for `TRANREPT.jcl` the output is `AWS.M2.CARDDEMO.TRANREPT(+1)`. Allocate a sequential PS dataset, REPRO the VSAM contents into it if necessary, and FTP/SCP the result to a workstation. If the dataset is EBCDIC-native, convert to ASCII via `iconv -f IBM-1047 -t UTF-8` (record-by-record to preserve fixed-width alignment).
5. **Normalise line endings.** Mainframe FTP transfers can introduce `0x0D 0x0A` (CR-LF) line endings; the migrated Java output typically uses LF only (`0x0A`). Convert via `dos2unix` or `tr -d '\r'` so the byte-comparison aligns. (Alternatively, ensure the migrated Java code emits CR-LF — the parity contract is that they match, regardless of which convention is used. Whatever the COBOL baseline emits is canonical.)
6. **Save to `src/test/resources/baseline/expected/`.** Place the captured file at the canonical path documented in [Section 4](#4-per-job-golden-output-mapping). Example: `src/test/resources/baseline/expected/posted.txt`. Replace any pre-existing placeholder file (see [Section 7](#7-placeholder-recognition)) outright; do not merge or append.

Repeat steps 1–6 for each of the 5 batch jobs. For `CREASTMT.JCL`, steps 4–6 are repeated twice (once for the text output, once for the HTML output) within a single JCL execution.

Per-job quick reference for the capture procedure:

| Job | JCL PARMs to pin | Input DDs to stage | Output DD(s) to extract |
| --- | ---------------- | ------------------ | ----------------------- |
| `POSTTRAN.jcl` | (none — STEP15 has no PARM) | `DALYTRAN`, `ACCTFILE`, `TCATBALF`, `XREFFILE`, `TRANFILE` | `DALYREJS` (the rejected-transactions output; `LRECL=430`) |
| `INTCALC.jcl` | `PARM='2022071800'` on `EXEC PGM=CBACT04C` | `TCATBALF`, `XREFFILE`, `XREFFIL1`, `ACCTFILE`, `DISCGRP` | `TRANSACT` (`LRECL=350`) |
| `COMBTRAN.jcl` | (none — SORT step uses inline `SORT FIELDS=`) | `SORTIN` (concatenated `TRANSACT.BKUP` + `SYSTRAN`) | `SORTOUT` (`TRANSACT.COMBINED`) |
| `CREASTMT.JCL` | (none for STEP040; STEP010 uses inline `SORT FIELDS=`) | `TRNXFILE`, `XREFFILE`, `ACCTFILE`, `CUSTFILE` | `STMTFILE` (text, `LRECL=80`) + `HTMLFILE` (HTML, `LRECL=100`) |
| `TRANREPT.jcl` | `SYMNAMES` PARMs `PARM-START-DATE=C'2022-01-01'`, `PARM-END-DATE=C'2022-07-06'` (on the SORT step) | `TRANFILE`, `CARDXREF`, `TRANTYPE`, `TRANCATG`, `DATEPARM` | `TRANREPT` (`LRECL=133`) |

Each PARM listed above MUST be propagated through the corresponding Spring Batch `JobParameters` so the migrated Java code receives the same date or numeric input the COBOL job received. If you change a PARM in the capture, you have changed the test scenario and you must refresh every expected output that depends on it.

### 5.3 Checksum and Review

After capture but before committing, run a brief verification pass:

1. **Compute SHA-256 checksums** of every file under `baseline/expected/` and record them in a sidecar file (optional but recommended) for change tracking:
   ```bash
   cd src/test/resources/baseline/expected
   sha256sum *.txt > .checksums.sha256
   ```
2. **Visual inspection.** Open each file in a hex viewer (e.g., `xxd <file> | head -20` for the header, `xxd <file> | tail -20` for the trailer) and confirm:
   - **Record count** matches the COBOL job's expected output (e.g., 300 records for `posted.txt` if 300 daily transactions were posted).
   - **Record width** matches the COBOL `RECLN` declared by the originating copybook (e.g., `LRECL=430` for `DALYREJS`, `LRECL=350` for `TRANSACT`, `LRECL=133` for `TRANREPT`, `LRECL=80` for `STMTFILE`, `LRECL=100` for `HTMLFILE`).
   - **No unexpected binary bytes.** Every byte should be `0x00`–`0x7F` ASCII unless sign-overpunch characters are present at the rightmost digit position of signed numeric fields (`{` for +0, `A`–`I` for +1..+9, `}` for -0, `J`–`R` for -1..-9).
   - **Trailing whitespace or padding** matches the COBOL convention (typically space-padded right; no trailing blank lines unless the COBOL emits them).
3. **Run the matching `*BaselineParityIT` locally** to confirm the captured baseline aligns with the current migrated Java code:
   ```bash
   mvn -Dit.test=TransactionPostingBaselineParityIT verify
   ```
   If the IT fails on the freshly captured baseline, the discrepancy points to a remaining migration defect in the Java code; investigate before committing — never modify the freshly captured baseline to make the test pass (see [Section 8](#8-when-tests-fail) step 6).

### 5.4 Committing the New Baseline

1. **Stage the new file(s).** `git add src/test/resources/baseline/expected/<filename>`.
2. **Commit message convention.** Use the prefix `test(baseline):` per the project's commit convention. Example: `test(baseline): refresh posted.txt after dailytran fixture update`.
3. **Include input-fixture changes in the same commit** when an input change drives the baseline refresh, so the input/expected pair stays atomic. Reviewers can verify the input ↔ output relationship in a single diff.
4. **Update the optional `.checksums.sha256` sidecar** in the same commit if you maintain one.
5. **Run the full parity IT suite** locally before opening the PR to confirm no other baseline was inadvertently affected:
   ```bash
   mvn -Dit.test='*BaselineParityIT' verify
   ```
   All five baseline-parity ITs must pass before the PR is opened.

## 6. The `BaselineDiffUtil` Contract

The `BaselineDiffUtil` test utility is the single assertion entry point for every baseline-parity comparison in the suite. Its contract is intentionally minimal: it answers one question (are these two files byte-identical?) with one method (`assertByteEqual`).

- **Location:** `src/test/java/com/aws/carddemo/testsupport/BaselineDiffUtil.java` (created by the migration's test-flavor work).
- **Primary signature:** `public static void assertByteEqual(Path actual, Path expected)`.
- **Convenience overload:** `public static void assertByteEqual(String actual, String expected)` — delegates to the `Path` form via `Path.of(...)`.

The utility's documented behaviour:

- Reads both files via `Files.readAllBytes(Path)`. The largest reference output (`transaction_report.txt`) is well under 1 MB, so in-memory comparison is acceptable.
- Compares byte arrays via `Arrays.equals(byte[], byte[])`. No charset decoding occurs during the comparison — this preserves single-byte differences such as a sign-overpunch character mismatch (`A` for +1 vs `J` for -1) that would otherwise be normalised by a `String` comparison.
- **On byte-equal:** returns normally. The assertion passes silently; no logging, no side effects.
- **On byte-inequal:** throws `AssertionError` with a structured message that includes:
  - Both file paths (absolute).
  - Both file sizes in bytes.
  - The first mismatching byte offset (zero-based).
  - Up to 20 differing lines in unified-diff style (`-` for expected, `+` for actual).
- **On unreadable file:** throws `UncheckedIOException` wrapping the underlying `IOException` (e.g., file not found, permission denied).
- **On placeholder-marker detection:** throws `AssertionError` with a clear remediation message that points to this document. See [Section 7](#7-placeholder-recognition) for the placeholder convention and [Section 8](#8-when-tests-fail) for the triage workflow when a placeholder is detected.

The contract verbs are deliberate:

- *Asserts* — the method is an assertion, not a Boolean predicate. There is no `boolean isByteEqual(...)` variant; tests must use the assertion. The asymmetry forces every consumer to participate in the parity contract on failure (a thrown `AssertionError`) rather than silently routing around it.
- *Byte-identical* — no normalisation of line endings, no charset decoding, no whitespace trimming. The bytes on disk are the bytes compared.
- *Path-based* — takes `java.nio.file.Path`, not `File` or `String` directly. The `String` overload exists only for ergonomic call sites such as inline test fixtures; both delegate to the same `Path`-based implementation.

Why byte comparison and not String comparison? COBOL output records frequently include fixed-width records with sign-overpunch encoding (`{` = +0, `A`–`I` = +1..+9, `}` = -0, `J`–`R` = -1..-9). A naive `String` comparison after default-charset decoding could normalise line endings (CR/LF on Windows vs LF on Linux) and would mask a single-byte sign-overpunch mismatch that violates the financial-precision requirement. Byte comparison preserves the exact COBOL output byte-for-byte and surfaces the kind of single-character drift that a `String` comparison would silently elide.

A typical failure message looks like the following — the exact format is determined by the utility's implementation, but the listed fields are the contract:

```text
AssertionError: Baseline parity check failed.
  expected: /workspace/src/test/resources/baseline/expected/posted.txt (12300 bytes)
  actual:   /workspace/target/test-output/posted.txt                   (12300 bytes)
  first differing byte offset: 4127
  unified diff (truncated to 20 lines):
  - 0000000023  4111111111111199  2022-07-18  0000001500A
  + 0000000023  4111111111111199  2022-07-18  0000001500{
                                                          ^
                                                          sign-overpunch at column 47
```

The unified-diff snippet is the most actionable field for the developer. The example above shows a sign-overpunch mismatch (`A` for +1 vs `{` for +0): the COBOL baseline emitted `A` (the rightmost digit of the value `00000015001` after applying overpunch for +1), while the migrated Java code emitted `{` (the +0 overpunch encoding). The remediation in this hypothetical case is to fix the migrated Java code's sign-overpunch table — never to alter the captured baseline file.

## 7. Placeholder Recognition

During the migration's early phases, the captured COBOL reference outputs may not yet be available — the mainframe environment may not be set up, the COBOL programs may not have been compiled, or the source repository may have introduced a new job whose baseline has not yet been captured. To prevent silent parity-IT pass-through in those situations, the suite uses an explicit **placeholder convention**.

The convention:

- A placeholder expected file contains the literal substring `BASELINE_CAPTURE_PENDING_<JOB_TAG>` within the **first 2 KB** of the file. The fixed prefix `BASELINE_CAPTURE_PENDING_` is what `BaselineDiffUtil` searches for; the `<JOB_TAG>` suffix is a project convention that aids human readers but is not part of the marker check. Examples:
  - `BASELINE_CAPTURE_PENDING_POSTING` for `posted.txt`
  - `BASELINE_CAPTURE_PENDING_INTEREST` for `tcatbal_after_interest.txt`
  - `BASELINE_CAPTURE_PENDING_COMBINE` for `combined.txt`
  - `BASELINE_CAPTURE_PENDING_STATEMENTS` for `statements_text.txt` and `statements_html.txt`
  - `BASELINE_CAPTURE_PENDING_REPORT` for `transaction_report.txt`
- A minimal placeholder file body — write something like the following into the file:
  ```text
  # BASELINE_CAPTURE_PENDING_POSTING
  # Capture the COBOL reference output by running POSTTRAN.jcl against the canonical
  # dailytran.txt input fixture and place the result in this file.
  # See docs/testing/baseline-parity.md for the capture procedure.
  ```

What happens when `BaselineDiffUtil.assertByteEqual` encounters a placeholder file as the `expected` operand:

- The placeholder marker (the literal `BASELINE_CAPTURE_PENDING_` prefix) is detected by scanning the first 2 KB of the expected file.
- The utility throws `AssertionError` with a diagnostic that:
  - Names the expected file path (so the developer knows which baseline is missing).
  - States that the COBOL reference output has not yet been captured.
  - Instructs the developer to capture the baseline per [Section 5](#5-capturing-or-refreshing-a-baseline) of this document.

This behaviour is intentional. The parity IT MUST fail loudly when the baseline is missing rather than silently passing. A silent-pass behaviour would create a false sense of parity during migration phases when expected outputs are stubs — exactly the situation in which a regression would be most likely to slip through unnoticed.

## 8. When Tests Fail

When a `*BaselineParityIT` test class fails, work through the triage steps below in order. The goal is to distinguish the categories of failure (placeholder, size mismatch, content mismatch, localised hunk) quickly so you can apply the right remediation.

1. **Read the `AssertionError` message.** `BaselineDiffUtil` produces a structured message with the two file paths, both byte sizes, the first differing byte offset, and a unified-diff snippet (up to 20 lines). Read all four — they almost always pinpoint the failure category.
2. **Distinguish placeholder vs. true diff.** If the message mentions `BASELINE_CAPTURE_PENDING_`, the expected file is still a placeholder — follow [Section 5](#5-capturing-or-refreshing-a-baseline) to capture the real baseline and re-run the IT. Do not interpret a placeholder failure as a code defect.
3. **If the byte sizes differ**, the migrated Java code is emitting either too many or too few records, or a different record width. Likely causes:
   - A record skipped that shouldn't have been (e.g., off-by-one in the EOF check on a `FlatFileItemReader`).
   - A header or footer that should not be in the output (or vice versa — the COBOL job emits a footer the Java job has not yet emulated).
   - A different padding convention (e.g., space-padded vs zero-padded numeric field; the COBOL `PICTURE 9(7)` zero-pads, an idiomatic Java `String.format` may space-pad).
4. **If the byte sizes match but content differs**, examine the first differing byte offset. The most common root causes, in order of frequency:
   - **Monetary value mismatch** (HALF_UP vs HALF_EVEN, scale drift). Verify `BigDecimal.scale()` and the rounding mode at the relevant computation site. Confirm no implicit `double`/`float` promotion anywhere in the call chain (AAP §0.10.3).
   - **Sign-overpunch encoding mismatch.** The COBOL output uses single-character sign overpunch (`{`/`}`/`A`–`R`); the migrated Java code may be emitting `+`/`-` or a different encoding. Inspect the rightmost digit of the affected field.
   - **Date format mismatch.** Verify the JCL PARM was passed through `JobParameters` and the migrated code uses the same `DateTimeFormatter` pattern as the COBOL `MOVE`/`STRING` formatting. Beware of `LocalDate.now()` calls slipping into production code — they must be replaced with reads from an injected `Clock`.
   - **Field padding mismatch.** Verify the migrated Java code right-pads alphanumeric fields with spaces (`PIC X(n)`) and left-pads numeric fields with zeroes (`PIC 9(n)`) to the exact `PICTURE`-clause width.
5. **If the diff shows a localised hunk** (e.g., a single record differs, the surrounding records match), inspect that input record in the corresponding fixture. The defect is likely deterministic and reproducible from that input alone — write a smaller `@Test` (unit) that loads just that record and asserts on the per-record output. This narrows the defect site and is faster to debug than re-running the full job IT.
6. **Never modify the expected file to make the test pass.** The expected file is the immutable COBOL baseline. The migrated Java code must change to match the baseline, not the reverse. The only legitimate reasons to refresh the baseline are: the input fixtures changed, or the COBOL source changed — both are explicit migration events, not test debugging. If you find yourself reaching for a hex editor to "fix" `baseline/expected/<file>`, stop and re-read [Section 1](#1-the-parity-contract).

When the cause is unclear, dump both files to hex and visually inspect the byte at the reported offset:

```bash
# Inspect the expected file around the first differing offset
xxd -s 4100 -l 80 src/test/resources/baseline/expected/posted.txt
# Inspect the actual file at the same offset
xxd -s 4100 -l 80 target/test-output/posted.txt
```

The two hex dumps make sign-overpunch, padding, and line-ending divergences immediately visible. A `0x41` byte (`A`) where you expect a `0x7B` byte (`{`) is a sign-overpunch table issue. A `0x20` byte (` `) where you expect a `0x30` byte (`0`) is a left-pad-with-zero issue. A `0x0D` byte (`\r`) where you expect nothing is a CRLF normalisation issue.

## 9. Common Failure Modes and Remediation

The table below enumerates the failure modes the team is most likely to encounter, the diagnostic signature each produces, and the remediation pattern. Use it as a quick-lookup guide alongside the triage workflow in [Section 8](#8-when-tests-fail).

| Failure Mode | Symptom | Remediation |
| ------------ | ------- | ----------- |
| Placeholder expected file | `AssertionError: Baseline reference output has not yet been captured.` | Follow Section 5 capture procedure. |
| File-size mismatch | `Files share a common prefix but differ in length.` | Verify record count and per-record width. Likely off-by-one or padding bug. |
| Sign-overpunch mismatch | First differing byte at the rightmost digit position of a monetary field; expected `A`, actual `1`. | Verify the migrated Java code uses the documented sign-overpunch encoding for signed numerics. |
| Rounding mismatch | `BigDecimal` value mismatches at the second decimal place by 0.01 on a `.5` boundary. | Verify `RoundingMode.HALF_EVEN` is applied at every `BigDecimal.divide` / `BigDecimal.setScale` call (AAP §0.10.3). |
| Trailing-whitespace mismatch | First differing byte at the end of a line. | Verify the COBOL `INSPECT...REPLACING` or `DISPLAY` convention for trailing whitespace; align Java code accordingly. |
| Line-ending mismatch | First differing byte is `0x0D` (CR) vs `0x0A` (LF). | Normalise during capture (Section 5.2 step 5) OR ensure the Java writer emits the same convention as the COBOL baseline. |
| Date-stamp mismatch | First differing bytes in a date field; values look "close" but wrong (e.g., `2022-07-18` vs `2022-07-19`). | Verify the JCL PARM is passed through Spring Batch `JobParameters` and the migrated code does NOT call `LocalDate.now()` — it must derive the date from the injected `Clock` or the JCL PARM. |
| Localised-hunk diff | A few records differ, others match. | Identify the input records that produce the differing output records; write a narrower unit test against those records. |
| `UncheckedIOException` on read | File-system error before comparison. | Check the file path, permissions, and that the expected file exists in `baseline/expected/`. |
| Character-encoding mismatch | First differing byte is in the `0x80`–`0xFF` range; values look "garbled". | Verify the captured baseline was converted from EBCDIC (`IBM-1047`) to ASCII (`UTF-8`) record-by-record without introducing multi-byte UTF-8 sequences where the COBOL emitted single-byte `0x00`–`0x7F` characters. |
| Off-by-one record ordering | The output is correct overall but a pair of adjacent records is swapped. | Verify the migrated `Comparator` (typically for `CombineTransactionsJob`) matches the JCL `SORT FIELDS=` column ordering and tie-breaker convention. |
| Missing or extra blank trailer line | Last byte of expected is `0x0A` (LF) while actual is EOF, or vice versa. | Verify the migrated `FlatFileItemWriter` configuration of `setShouldDeleteIfEmpty` and any `LineAggregator` newline behaviour matches the COBOL `WRITE`/`CLOSE` convention. |
| Job parameter not pinned | Date or numeric fields drift between runs of the same test. | Verify Spring Batch `JobParameters` include every PARM the JCL pins (`INTCALC.jcl` `PARM='2022071800'`; `TRANREPT.jcl` `PARM-START-DATE`/`PARM-END-DATE`) and that the migrated code never reads `LocalDate.now()` or `System.currentTimeMillis()` directly. |

## 10. Cross-References

The authoritative sources and adjacent artefacts referenced by this runbook are:

- **Companion document:** [`test-strategy.md`](test-strategy.md) — overall four-layer test pyramid, coverage targets, framework constraints, the Require Test Coverage rule, financial-precision rules, and execution commands.
- **Anchor blueprint:** [`../technical-specifications.md`](../technical-specifications.md) — the broader migration blueprint that this runbook complements.
- **Top-level project README:** [`../../README.md`](../../README.md) — repository overview, getting-started commands, and the "Testing" section that links here.
- **Production code under test** (created by the REFACTOR-flavor migration in its own assignment slice):
  - `com.aws.carddemo.batch.TransactionPostingJob` ← `app/jcl/POSTTRAN.jcl` ← `app/cbl/CBTRN02C.cbl`
  - `com.aws.carddemo.batch.InterestCalculationJob` ← `app/jcl/INTCALC.jcl` ← `app/cbl/CBACT04C.cbl`
  - `com.aws.carddemo.batch.CombineTransactionsJob` ← `app/jcl/COMBTRAN.jcl` (DFSORT — no COBOL program)
  - `com.aws.carddemo.batch.StatementGenerationJob` ← `app/jcl/CREASTMT.JCL` ← `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`
  - `com.aws.carddemo.batch.TransactionReportJob` ← `app/jcl/TRANREPT.jcl` ← `app/cbl/CBTRN03C.cbl`
- **Test utility:** `src/test/java/com/aws/carddemo/testsupport/BaselineDiffUtil.java` — implementation of the contract documented in [Section 6](#6-the-baselinediffutil-contract).
- **Test fixtures:** `app/data/ASCII/*.txt` (source) ↔ `src/test/resources/baseline/input/*.txt` (copies); `src/test/resources/baseline/expected/*.txt` (captured COBOL reference outputs); `src/test/resources/fixtures/edge/*.csv` (curated edge-case CSVs for `@ParameterizedTest`).
