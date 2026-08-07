<!--
******************************************************************
Program     : TRACEABILITY_MATRIX.md
Application : CardDemo
Type        : Evidence artefact - bidirectional paragraph-level traceability matrix
Function    : Map every PROCEDURE DIVISION paragraph of all 28 CardDemo COBOL
              programs to its Java target method, test and decision record, and
              carry the derived corpus censuses that Gate 7 reads.
Source      : app/cbl/** (28 programs, 19,254 lines)
              app/cpy/** (28 copybooks, 2,614 lines)
              app/cpy-bms/** (17 symbolic maps, 5,632 lines)
              app/bms/** (17 mapsets, 4,472 lines)
              app/jcl/** (29 members, case-insensitive)
              app/proc/** (2), app/ctl/REPROCT.ctl (1)
              app/csd/CARDDEMO.CSD, app/catlg/LISTCAT.txt
              app/data/ASCII/** (9 fixtures)
              @ 7756d89
******************************************************************
Copyright Amazon.com, Inc. or its affiliates.
All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific
language governing permissions and limitations under the License
******************************************************************
-->

# CardDemo Traceability Matrix

**Bidirectional, paragraph-level, machine-checkable mapping from the frozen COBOL corpus
to the Java 25 / Spring Boot target.**

| | |
|---|---|
| **Source anchor** | `7756d895ffeb65f7ea72aaa609e356d9899afcec` (short `7756d89`) |
| **Corpus** | 28 programs, **19,254** physical lines, `app/cbl/**` |
| **Matrix rows** | **537** = 528 PROCEDURE DIVISION paragraphs + 9 synthetic entry rows |
| **Distinct Java targets** | 534 |
| **Unmapped paragraphs** | 0 |
| **Orphan Java business methods** | 0 |
| **Gate this artefact serves** | [Gate 7 &mdash; Scope coverage](docs/validation-gates.md#gate-7) |
| **Decision register** | [`DECISION_LOG.md`](DECISION_LOG.md) |

> **The corpus under `app/` is frozen and was not modified.** `git diff 7756d89 HEAD -- app/`
> returns empty, which is what makes every `:Lnnn` locator on this page verifiable against the
> working tree even though the anchor is now an ancestor of `HEAD` rather than `HEAD` itself.


### Contents

1. [About this document](#about)
2. [Coverage contract](#contract)
3. [Parser rules](#parser)
4. [Row identity and column legend](#schema)
5. [The 28-program paragraph matrix](#matrix) &mdash; including [the 22-feature census](#feature-census)
6. [Reverse index &mdash; Java target to source paragraph](#reverse)
7. [CICS resource reconciliation](#csd)
8. [Job control, procedure and control-card map](#jcl)
9. [Copybook map &mdash; all 28 members](#copybooks)
10. [VSAM catalogue map](#vsam)
11. [Screen map &mdash; all 17 sourced mapsets](#bms)
12. [Fixture map](#fixtures)
13. [Out of scope and residual risk](#scope)
14. [Gate 7 machine-checkability](#gate7)
15. [Findings register](#findings)
16. [Verification log](#verify)
17. [How this document satisfies *Rule 1: Build Verify*](#rules)

<a id="about"></a>

## 1. About this document

### 1.1 What it does

This file is the coverage evidence for the migration. It answers two questions mechanically,
in both directions:

1. **Forward** &mdash; for every named paragraph in every one of the 28 programs, which Java
   file and which method reproduces it, under which classification, justified by which decision
   record, asserted by which test.
2. **Backward** &mdash; for every Java member that carries legacy behaviour, which source
   paragraph or paragraphs it came from. [Section 6](#reverse) is that index, and it lists each
   target exactly once.

Coverage here is **computed, never asserted**. Every count on this page was derived by parsing
the corpus at the anchor commit; [section 16](#verify) records the commands, the exit codes and
the dates.

### 1.2 How to build, run and verify

```bash
# Full build and the whole test suite, including the Gate 7 assertions
./mvnw -B -ntp -Ddependency-check.skip=true clean verify

# Gate 7 alone
./mvnw -B -ntp -Dit.test=GateVerificationTest verify

# Re-derive the two censuses this page rests on, straight from the frozen corpus
wc -l app/cbl/*                     # expect 28 files and 19,254 total
ls app/jcl | wc -l                  # expect 29, case-insensitively
```

### 1.3 Key configuration and defaults

| Setting | Value | Why it matters here |
|---|---|---|
| Source anchor | `7756d89` | Every `:Lnnn` locator is relative to it |
| Corpus glob | **case-insensitive** | `app/cbl/*.cbl` matches 26 of 28 and under-reports by 1,154 lines; `app/jcl/*.jcl` matches 28 of 29 and drops statement generation entirely |
| Package root | `com.cardemo` | One spelling everywhere; `com.carddemo` is wrong |
| Java target base | ``src/main/java/com/cardemo/`` | Column *Target file* is relative to it |
| Test base | ``src/test/java/com/cardemo/`` | Column *Test* is relative to it |

### 1.4 Common failure modes and troubleshooting

| Symptom | Cause | Remediation |
|---|---|---|
| Corpus reports 26 programs / 18,100 lines | A case-sensitive `*.cbl` glob dropped `CBSTM03A.CBL` and `CBSTM03B.CBL` | Match case-insensitively; the shortfall is exactly 924 + 230 = 1,154 lines and raises no error |
| Statement generation appears to have no source | A `*.jcl` glob dropped `CREASTMT.JCL`, the sole uppercase member of 29 | Match case-insensitively |
| A label census reports 639 | An all-division parse was used: 553 all-division paragraphs + 86 all-division SECTIONs | Restrict the parse to the PROCEDURE DIVISION. **639 is a parser artefact and is never a coverage figure** |
| A label census reports 553 | Non-procedure paragraphs such as `FILE-CONTROL.` and `I-O-CONTROL.` were absorbed &mdash; exactly 25 of them | Restrict to the PROCEDURE DIVISION; the correct figure is 528 |
| A screen-field census reports 460 | A prose figure that is also internally inconsistent with its own per-map table, which sums to 440 | Use the derived census of **441**; see [section 11](#bms) and finding `TM-M-1` |
| `COACTVWC` appears to have a duplicate row | It genuinely has one: `0000-MAIN-EXIT` is defined twice | Correct as published; see `TM-COACTVWC-R003` and `TM-COACTVWC-R004` |

### 1.5 What this document is not

It is not a design document, not a decision register and not a gate report. Mechanism choices
and their justifications live in [`DECISION_LOG.md`](DECISION_LOG.md); gate objectives, evidence
paths and results live in [`docs/validation-gates.md`](docs/validation-gates.md). This page cites
both and duplicates neither.

<a id="contract"></a>

## 2. Coverage contract

Five rules govern what may appear on this page. They are the reason the row count is what it is.

| # | Rule | Consequence |
|---|---|---|
| C1 | Source is enumerated **directly from the 28 files**, never from prior prose or a wildcard assumption | Eight line-range citations carried by the technical specification were found wrong and are corrected in [section 15](#findings) |
| C2 | **Every** named paragraph gets its own row | 528 paragraph rows |
| C3 | Executable logic between the `PROCEDURE DIVISION` header and the first named paragraph gets a synthetic `PROCEDURE-DIVISION-ENTRY` row | 9 such rows, in the 9 programs that have such logic |
| C4 | One paragraph maps to one method. **Paragraphs are never consolidated into one row.** The only permitted many-to-one targets are the three declared in [section 6.1](#reverse-multi) | 534 distinct targets for 537 rows |
| C5 | Empty, reachable, exit-only, fall-through, `ALTER`-target and redundant paragraphs are **kept and marked**, never dropped | 125 exit-only rows, 1 reachable-empty row, 1 `ALTER` target, 1 duplicate label |

### 2.1 Derived censuses

Each figure below was measured, not copied. The command that reproduces it is in [section 16](#verify).

| Artefact set | Members | Lines | Note |
|---|---:|---:|---|
| `app/cbl/**` programs | 28 | 19,254 | 17 online, 10 batch, 1 utility |
| PROCEDURE DIVISION paragraphs | **528** | &mdash; | across all 28 programs |
| PROCEDURE DIVISION `SECTION`s | **0** | &mdash; | no program sections its procedure code; all 86 `SECTION`s are identification, environment or data division |
| Synthetic entry rows | 9 | &mdash; | additive documentation rows; they do **not** inflate the paragraph census |
| `app/cpy/**` copybooks | 28 | 2,614 | `COSTM01.CPY` is the only uppercase name |
| `app/cpy-bms/**` symbolic maps | 17 | 5,632 | 441 derived input fields |
| `app/bms/**` mapsets | 17 | 4,472 | consulted for field attributes, not translated |
| `app/jcl/**` members | 29 | &mdash; | `CREASTMT.JCL` is the only uppercase extension |
| `app/proc/**` | 2 | &mdash; | `REPROC.prc`, `TRANREPT.prc` |
| `app/ctl/**` | 1 | &mdash; | `REPROCT.ctl`, a single `REPRO` control card |
| `app/csd/CARDDEMO.CSD` | 1 | &mdash; | 18 transactions, 18 programs, 17 mapsets, 8 files, 1 queue |
| `app/catlg/LISTCAT.txt` | 1 | 3,956 | 10 clusters, 3 AIX, 3 paths, 7 generation groups, declared total 209 |
| `app/data/ASCII/**` fixtures | 9 | &mdash; | there is no `usrsec.txt` |
| `app/data/EBCDIC/**` | 12 `.PS` | &mdash; | excluded; never parsed by the build |
| Java production classes | 133 | &mdash; | plus 26 `package-info.java`, so 159 files under `src/main/java` |
| Java test classes | 264 | &mdash; | unit, integration and end-to-end, counted as source files; 249 are suite-named and 15 are shared support types, and there is no `package-info.java` anywhere in the test tree |
| Catalogued features | 22 | &mdash; | `F-001` through `F-022`; all 22 are mapped in [section 2.2](#features), and 21 of them &mdash; every one except `F-021` &mdash; also appear against a program in [section 5.0](#matrix) |

The three Java figures in that table are re-measured on every build by
`DocumentationConsistencyTest.JavaCensusIsMeasured`, which counts the tree and fails if this page
disagrees. They were previously authored by hand and one of them had already gone stale, so the
count is now derived rather than asserted.

**Arithmetic that must hold:** 528 paragraphs + 9 synthetic entry rows = **537 rows**. The
paragraph census Gate 7 asserts is **528**, and this page maps 528 of 528. The 9 synthetic rows
are counted separately precisely so they cannot be mistaken for paragraph coverage.

<a id="features"></a>

### 2.2 The 22-feature evidence map

Sections 5 to 12 map **artefacts** &mdash; programs, copybooks, mapsets, JCL members, clusters,
fixtures. That is the right axis for paragraph-level traceability, and it is the wrong axis for the
one question the acceptance contract turns on: *is every catalogued feature accounted for?*
Twenty-one of the twenty-two identifiers can be read straight off the program roster in
[section 5.0](#matrix). The twenty-second cannot, because **`F-021` has no COBOL program at all**
&mdash; its logic is DFSORT and IDCAMS control cards in `app/jcl/COMBTRAN.jcl`, so a program row for
it would have to be invented. An artefact axis therefore under-reports the feature set by exactly
one, silently, and it did: a review of this page found the `F-021` token absent from the entire
repository while every other identifier was present, so twenty-two-of-twenty-two could not be
proved mechanically. This section and check G7.11 in [section 14.2](#gate7) are the answer to it.

One row per catalogued feature. `Legacy source of record` is the frozen artefact the behaviour comes
from. `Java target of record` is relative to `src/main/java/com/cardemo/` and `Proving test` is
relative to `src/test/java/com/cardemo/`, the same convention [section 4.2](#schema) uses. Two rows
name two programs because the catalogue is coarser than the corpus in exactly two places: `F-002`
covers both menus and `F-012` covers both user list and user add.

**This table is parsed, not read.** `GateVerificationTest` extracts all five columns below and then:
requires the identifier column to be exactly `F-001` through `F-022`, generated arithmetically, so
that an omission **and** an invented identifier both fail; resolves every legacy path and every test
path on disk with exact case; loads every Java target; and requires each target's own source to cite
one of its row's legacy artefacts, which is what keeps the edge bidirectional. The harness holds its
own independent copy of this map and asserts the two agree title for title and path for path, so
neither this table nor the harness can drift alone.

| Feature | Title | Legacy source of record | Java target of record | Proving test |
|---|---|---|---|---|
| **F-001** | Sign-on | `app/cbl/COSGN00C.cbl` | `service/auth/AuthenticationService.java` | `unit/service/AuthenticationServiceTest.java` |
| **F-002** | Menu dispatch | `app/cbl/COMEN01C.cbl`, `app/cbl/COADM01C.cbl` | `service/menu/MainMenuService.java`, `service/menu/AdminMenuService.java` | `unit/service/MainMenuServiceTest.java`, `unit/service/AdminMenuServiceTest.java` |
| **F-003** | Account view | `app/cbl/COACTVWC.cbl` | `service/account/AccountViewService.java` | `unit/service/AccountViewServiceTest.java` |
| **F-004** | Account update | `app/cbl/COACTUPC.cbl` | `service/account/AccountUpdateService.java` | `unit/service/AccountUpdateServiceTest.java` |
| **F-005** | Card list | `app/cbl/COCRDLIC.cbl` | `service/card/CardListService.java` | `unit/service/CardListServiceTest.java` |
| **F-006** | Card detail | `app/cbl/COCRDSLC.cbl` | `service/card/CardDetailService.java` | `unit/service/CardDetailServiceTest.java` |
| **F-007** | Card update | `app/cbl/COCRDUPC.cbl` | `service/card/CardUpdateService.java` | `unit/service/CardUpdateServiceTest.java` |
| **F-008** | Transaction list | `app/cbl/COTRN00C.cbl` | `service/transaction/TransactionListService.java` | `unit/service/TransactionListServiceTest.java` |
| **F-009** | Transaction detail | `app/cbl/COTRN01C.cbl` | `service/transaction/TransactionDetailService.java` | `unit/service/TransactionDetailServiceTest.java` |
| **F-010** | Transaction add | `app/cbl/COTRN02C.cbl` | `service/transaction/TransactionAddService.java` | `unit/service/TransactionAddServiceTest.java` |
| **F-011** | Bill payment | `app/cbl/COBIL00C.cbl` | `service/billing/BillPaymentService.java` | `unit/service/BillPaymentServiceTest.java` |
| **F-012** | User list and add | `app/cbl/COUSR00C.cbl`, `app/cbl/COUSR01C.cbl` | `service/admin/UserListService.java`, `service/admin/UserAddService.java` | `unit/service/UserListServiceTest.java`, `unit/service/UserAddServiceTest.java` |
| **F-013** | User update | `app/cbl/COUSR02C.cbl` | `service/admin/UserUpdateService.java` | `unit/service/UserUpdateServiceTest.java` |
| **F-014** | User delete | `app/cbl/COUSR03C.cbl` | `service/admin/UserDeleteService.java` | `unit/service/UserDeleteServiceTest.java` |
| **F-015** | Report submission | `app/cbl/CORPT00C.cbl` | `service/report/ReportSubmissionService.java` | `unit/service/ReportSubmissionServiceTest.java`, `integration/batch/ReportQueueListenerLiveTest.java` |
| **F-016** | Daily transaction posting | `app/cbl/CBTRN02C.cbl`, `app/cbl/CBTRN01C.cbl` | `batch/jobs/DailyTransactionPostingJob.java`, `batch/processors/TransactionPostingProcessor.java`, `batch/writers/TransactionWriter.java`, `batch/writers/RejectWriter.java`, `batch/readers/DailyTransactionReader.java` | `integration/batch/DailyTransactionPostingJobTest.java`, `e2e/BatchPipelineE2ETest.java` |
| **F-017** | Interest calculation | `app/cbl/CBACT04C.cbl` | `batch/jobs/InterestCalculationJob.java`, `batch/processors/InterestCalculationProcessor.java` | `integration/batch/InterestCalculationJobIntegrationTest.java` |
| **F-018** | Transaction report | `app/cbl/CBTRN03C.cbl` | `batch/jobs/TransactionReportJob.java`, `batch/processors/TransactionReportProcessor.java` | `integration/batch/TransactionReportJobTest.java` |
| **F-019** | Statement generation | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL` | `batch/jobs/StatementGenerationJob.java`, `batch/processors/StatementProcessor.java`, `batch/writers/StatementWriter.java`, `service/shared/FileService.java` | `integration/batch/StatementGenerationJobTest.java`, `unit/service/FileServiceTest.java` |
| **F-020** | Dataset verification reads | `app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl`, `app/cbl/CBCUS01C.cbl` | `batch/readers/AccountReader.java`, `batch/readers/CardReader.java`, `batch/readers/CardCrossReferenceReader.java`, `batch/readers/CustomerReader.java` | `integration/batch/DatasetVerificationJobTest.java` |
| **F-021** | Transaction combination | `app/jcl/COMBTRAN.jcl` | `batch/jobs/CombineTransactionsJob.java`, `batch/processors/TransactionCombineProcessor.java` | `integration/batch/CombineTransactionsJobTest.java`, `unit/batch/TransactionCombineProcessorTest.java` |
| **F-022** | Date validation utility | `app/cbl/CSUTLDTC.cbl` | `service/shared/DateValidationService.java` | `unit/service/DateValidationServiceTest.java` |

**`F-021` is the one row whose legacy source is a JCL member rather than a program**, and that is the
whole reason it needed a row of its own. `app/jcl/COMBTRAN.jcl:STEP05R` sorts a concatenated input of
the transaction backup and the interest-generated generation by `TRAN-ID` ascending, and `STEP10`
REPROs the result into the transaction cluster; [section 8](#jcl) row 5 carries the same
identification against the member itself.

**Scope of the identifier assertion, and why it stops at this page.** The exact-set check runs
against this document and no other. This is the traceability artefact of record, so a feature absent
here is a traceability gap by definition. `DECISION_LOG.md` is keyed by decision identifier and
`docs/validation-gates.md` by gate number; neither is required to carry feature identifiers, and
requiring all 22 to appear in them would invent a requirement that a pasted list would satisfy
&mdash; the opposite of evidence. The harness does enforce the converse everywhere on this page:
**no `F-nnn` token outside the catalogued set may appear anywhere in it**, so an invented
twenty-third identifier fails wherever it is written, prose or table. That rule is why no such
identifier is spelled out here even as an illustration: the check is a plain scan of this file, and a
document able to quote its own counterexample would have to exempt itself, which is exactly the kind
of hole that makes a guard report clean. The first run of the check proved the point by failing on an
illustrative token in this very paragraph.

<a id="parser"></a>

## 3. Parser rules

The label census is only trustworthy if the parse is stated. These nine rules are the parse, and
[section 16](#verify) re-runs them.

| # | Rule |
|---|---|
| R1 | Fixed-column reference format: columns 1&ndash;6 sequence, column 7 indicator, columns 8&ndash;11 Area A, columns 12&ndash;72 Area B |
| R2 | A line whose **column 7** holds `*`, `/`, `-`, `D`, `d` or `$` is a comment, page eject, continuation or debug line and can never introduce a label |
| R3 | Only text at or after the `PROCEDURE DIVISION` header is scanned. This is what **structurally** excludes every `FD`, record name, `77`-level and **`88`-level condition name** from the census &mdash; they are all in earlier divisions, so no deny-list is relied on for them |
| R4 | A label must begin in **Area A**: the first non-blank character of the statement area must fall in columns 8&ndash;11 |
| R5 | The token must be a COBOL user-defined word (`[A-Z0-9]` with interior hyphens) followed by `.` or by ` SECTION.` |
| R6 | An explicit deny-list rejects reserved words that may legally sit in Area A: `DECLARATIVES`, `EJECT`, `SKIP1/2/3`, division and section headers, and statement verbs |
| R7 | `SECTION` labels are recorded separately from paragraphs. The result for this corpus is **zero** procedure-division sections |
| R8 | The inclusive end line is the **last line carrying real source text** before the next label; trailing comment separators and blank lines are trimmed. This is the same convention the implemented Javadoc uses, so the two agree line for line |
| R9 | Executable statements between the `PROCEDURE DIVISION` header and the first named label produce a synthetic `PROCEDURE-DIVISION-ENTRY` row |

**Parser self-audit, run in both directions.** *False negatives:* every Area-A token after the
`PROCEDURE DIVISION` header that was **not** taken as a label was re-examined; none was
label-shaped. *False positives:* 25 labels have no `PERFORM`, `GO TO` or `ALTER` reference at all,
and each was inspected rather than dropped &mdash; 14 are program entry paragraphs, 10 are
fall-through or exit-only labels reached by falling through or by `PERFORM … THRU`, and one is a
genuine fall-through paragraph (`COACTUPC` `EDIT-AREA-CODE`, entered by falling out of
`1260-EDIT-US-PHONE-NUM`).

<a id="schema"></a>

## 4. Row identity and column legend

### 4.1 Row IDs

Every row carries a permanent identifier of the form **`TM-<PROGRAM>-R<nnn>`**, where `<nnn>`
is the paragraph's ordinal position in its own program's `PROCEDURE DIVISION`, zero-padded to
three digits and counted from the synthetic entry row where one exists.

IDs are **unique across the whole page** and are the key an automated check joins on. The one
duplicated label in the corpus therefore still yields two distinct IDs
(`TM-COACTVWC-R003` and `TM-COACTVWC-R004`), which is exactly why a duplicate-label defect
cannot silently collapse into a single row.

### 4.2 Columns

| Column | Content |
|---|---|
| **Row ID** | Stable join key, `TM-<PROGRAM>-R<nnn>` |
| **Source** | The program file name **in its exact on-disk case** |
| **Mode** | `online`, `batch` or `utility`, plus the feature the program serves |
| **Paragraph / label** | The label **verbatim as it appears in the source**, misspellings included |
| **Lines** | Inclusive `:Lstart`&ndash;`:Lend` at `7756d89`, per rule R8 |
| **Calls / relationship** | `PERFORM`, `THRU`, `GO TO`, `ALTER`, static `CALL` and `EXEC CICS` verbs found in the paragraph body |
| **COPY / layout** | The copybooks whose *discriminative* field names the paragraph references, derived by field-name ownership rather than guessed |
| **Target file** | Java file, relative to `src/main/java/com/cardemo/` |
| **Target method** | Visibility and name. `&hellip;` in the parentheses means the method takes arguments |
| **Classification** | One of `direct`, `mechanism substitution`, `parity-preserved quirk`, `deviation`, `infrastructure-only` |
| **Decision** | The `DECISION_LOG.md` entry that justifies the classification |
| **Test** | `<file>::<method>` &mdash; the test file relative to `src/test/java/com/cardemo/`, then the **single test method** that asserts this row. Naming the file alone was not enough: a file holds hundreds of methods, so a reader could not tell which of them the row rested on, and a row whose method had been deleted still looked cited. Every one of the 537 methods named here is an executable `@Test`, `@ParameterizedTest` or `@RepeatedTest` &mdash; never a fixture helper, a `setUp` or a `given&hellip;` method, none of which can pass or fail on its own &mdash; and every pair is machine-checked to exist, per [section 16](#verify) |
| **Evidence** | The gate that consumes the row |
| **Status** | Verification standing, per section 4.3 |

A pipe that belongs to a cell's *content* is written `\|`, which the table parser removes before it
parses the cell, so the cell can never break the row &mdash; and because the escape is resolved at that
stage it works **inside a code span** too, which is where the only such pipe on this page occurs, in
the shell pipeline at [section 16.1](#verify). An HTML entity is deliberately *not* used for this:
entities are not decoded inside a code span, so it would render as its own source text.

**Two typographic conventions on this page differ from its sibling registers, deliberately and only
here.** A line range is written as **two separately code-spanned locators joined by an en-dash
entity** &mdash; `` `:L859` ``&ndash;`` `:L1005` `` &mdash; and every typographic dash on the page is
an entity rather than a literal character. `DECISION_LOG.md` and `docs/validation-gates.md` write
ranges as a single hyphenated span, `[app/cbl/CORPT00C.cbl:L223-L234]`, and use literal dashes
throughout. The reason for diverging is specific to this page and does not generalise to them: it
prints **537 COBOL labels, and COBOL labels are hyphen-rich** &mdash; `1500-B-LOOKUP-ACCT`,
`1200-A-GET-DEFAULT-INT-RATE`, `9910-DISPLAY-IO-STATUS`. Beside those, a hyphenated range is
ambiguous to a reader and to a parser, and a literal en-dash is visually indistinguishable from a
hyphen in a monospaced diff. Since the entire value of this page is locator precision, the range
separator is made unmistakable and the choice is applied uniformly: **1,241 dash entities and zero
literal em- or en-dashes**, verified by scan. No other file's formatting is touched, no formatter or linter
configuration exists in this repository to conform to, and the sibling registers keep their own
convention untouched &mdash; which is what makes this a local, justified choice rather than a style
imposed on the tree.

### 4.3 Status values

| Value | Meaning |
|---|---|
| `Target-verified` | The cited Java file exists at the cited path **with that exact case**, and the cited method exists inside it. Machine-checked; see [section 16](#verify) |
| `Not available` | Reserved for a field whose evidence has not been produced, always paired with the prerequisite that would produce it. Never a substitute for `PASS`, a percentage, or a claim that a gate was met. **No field on this page currently carries it**, because every check published here was executed and returned &mdash; see [section 17](#rules) clause F |

**No row on this page claims a gate result.** A row asserts a mapping and its machine-checked
existence. Gate results live in [`docs/validation-gates.md`](docs/validation-gates.md) and are
reproduced in [section 16](#verify) only from actual execution.

<a id="matrix"></a>

## 5. The 28-program paragraph matrix

One sub-section per program, ordered by descending physical line count. Column set is identical
in every table.

### 5.0 Programs in this section

| # | Program | Lines | Mode | Rows | Feature |
|---:|---|---:|---|---:|---|
| 1 | [`COACTUPC.cbl`](#coactupc-cbl) | 4,236 | online | 85 | F-004 Account update |
| 2 | [`COCRDUPC.cbl`](#cocrdupc-cbl) | 1,560 | online | 45 | F-007 Card update |
| 3 | [`COCRDLIC.cbl`](#cocrdlic-cbl) | 1,459 | online | 39 | F-005 Card list |
| 4 | [`COACTVWC.cbl`](#coactvwc-cbl) | 941 | online | 35 | F-003 Account view |
| 5 | [`CBSTM03A.CBL`](#cbstm03a-cbl) | 924 | batch | 26 | F-019 Statement generation |
| 6 | [`COCRDSLC.cbl`](#cocrdslc-cbl) | 887 | online | 34 | F-006 Card detail |
| 7 | [`COTRN02C.cbl`](#cotrn02c-cbl) | 783 | online | 18 | F-010 Transaction add |
| 8 | [`CBTRN02C.cbl`](#cbtrn02c-cbl) | 731 | batch | 27 | F-016 Daily transaction posting |
| 9 | [`COTRN00C.cbl`](#cotrn00c-cbl) | 699 | online | 16 | F-008 Transaction list |
| 10 | [`COUSR00C.cbl`](#cousr00c-cbl) | 695 | online | 16 | F-012 User list |
| 11 | [`CBACT04C.cbl`](#cbact04c-cbl) | 652 | batch | 23 | F-017 Interest calculation |
| 12 | [`CBTRN03C.cbl`](#cbtrn03c-cbl) | 649 | batch | 27 | F-018 Transaction report |
| 13 | [`CORPT00C.cbl`](#corpt00c-cbl) | 649 | online | 10 | F-015 Report submission |
| 14 | [`COBIL00C.cbl`](#cobil00c-cbl) | 572 | online | 16 | F-011 Bill payment |
| 15 | [`CBTRN01C.cbl`](#cbtrn01c-cbl) | 491 | batch | 18 | F-016 Daily posting pre-flight (read-only) |
| 16 | [`COUSR02C.cbl`](#cousr02c-cbl) | 414 | online | 11 | F-013 User update |
| 17 | [`COUSR03C.cbl`](#cousr03c-cbl) | 359 | online | 11 | F-014 User delete |
| 18 | [`COTRN01C.cbl`](#cotrn01c-cbl) | 330 | online | 9 | F-009 Transaction detail |
| 19 | [`COUSR01C.cbl`](#cousr01c-cbl) | 299 | online | 9 | F-012 User add |
| 20 | [`COMEN01C.cbl`](#comen01c-cbl) | 282 | online | 7 | F-002 Main menu |
| 21 | [`COADM01C.cbl`](#coadm01c-cbl) | 268 | online | 7 | F-002 Admin menu |
| 22 | [`COSGN00C.cbl`](#cosgn00c-cbl) | 260 | online | 6 | F-001 Sign-on |
| 23 | [`CBSTM03B.CBL`](#cbstm03b-cbl) | 230 | batch | 14 | F-019 Statement file access subprogram |
| 24 | [`CBACT01C.cbl`](#cbact01c-cbl) | 193 | batch | 7 | F-020 Account file sequential read (read-only) |
| 25 | [`CBACT02C.cbl`](#cbact02c-cbl) | 178 | batch | 6 | F-020 Card file sequential read (read-only) |
| 26 | [`CBACT03C.cbl`](#cbact03c-cbl) | 178 | batch | 6 | F-020 Cross-reference sequential read (read-only) |
| 27 | [`CBCUS01C.cbl`](#cbcus01c-cbl) | 178 | batch | 6 | F-020 Customer file sequential read (read-only) |
| 28 | [`CSUTLDTC.cbl`](#csutldtc-cbl) | 157 | utility | 3 | F-022 Date validation utility |
| | **Total** | **19,254** | | **537** | |

<a id="feature-census"></a>

#### 5.0.1 The 22-feature census, and the one feature with no program row

The **Feature** column above names **21** of the **22** catalogued features. **F-021 is absent from
it, and that absence is a property of the table rather than a gap in the contract** — but it was
left unexplained, so the roster read as though it skipped a number, and nothing anywhere defined
what F-021 was. Both are closed here.

**F-021 is transaction combine, and it has no COBOL program.** `app/jcl/COMBTRAN.jcl` is two steps
and no `EXEC PGM=` naming a COBOL module: `STEP05R` is a DFSORT step over a **concatenated**
`SORTIN` — the transaction backup generation and the interest-generated generation — ordered by
`TRAN-ID` ascending, and `STEP10` is an IDCAMS `REPRO` of the sorted result into the transaction
cluster. **The job control is therefore the source of truth for this feature**, which is precisely
why it cannot appear in a table whose row key is a program.

| Feature | Source of truth | Java target | Why no row above |
|---|---|---|---|
| **F-021 Transaction combine** | `app/jcl/COMBTRAN.jcl` `STEP05R` and `STEP10`; no `EXEC PGM=` names a COBOL module anywhere in the member | `batch/jobs/CombineTransactionsJob.java`, `batch/processors/TransactionCombineProcessor.java`, `batch/readers/CombinedTransactionReader.java` | The matrix in [section 5](#matrix) is keyed on **COBOL program**, and this feature has none. A row would have to invent a program name to carry it |

So the two counts reconcile as follows, and neither is wrong:

| Count | Value | Why |
|---|---:|---|
| Catalogued features | **22** | F-001 through F-022, the parity scope |
| Features with at least one program row above | **21** | every feature except F-021 |
| COBOL programs | **28** | the row key of [section 5](#matrix) |
| Programs with no feature | **0** | every program serves a catalogued feature |
| Features with no program | **1** | F-021, for the reason in the table above |

Two further asymmetries are visible in the column and are deliberate, so they are stated rather
than left to be noticed: **F-002** covers both menu programs, **F-012** covers user list *and* user
add, **F-016** covers the posting job and its read-only pre-flight, **F-019** covers the statement
program and its file-access subprogram, and **F-020** covers all four sequential readers. A feature
is a capability, not a program, so a many-to-one column is the expected shape and not a defect.

**The set is asserted, not counted by eye.** `F-021` is mapped on the feature axis in
[section 2.2](#features) and its member is identified again in [section 8](#jcl) row 5, and check
G7.11 in [section 14.2](#gate7) parses the feature map and requires the identifier column to be
exactly the generated `F-001`&ndash;`F-022` set &mdash; so an omitted feature and an invented
identifier padding the count both fail there rather than being read past here.

<a id="coactupc-cbl"></a>

### 5.1 `COACTUPC.cbl`

**online &middot; F-004 Account update &middot; 4,236 physical lines &middot; 85 rows** (85 paragraphs)

Target files: `service/account/AccountUpdateService.java`, `controller/AccountController.java`.
Tests: `unit/service/AccountUpdateServiceTest.java`, `unit/model/AccountUpdateRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COACTUPC-R001` | `COACTUPC.cbl` | online | `0000-MAIN` | `:L859`&ndash;`:L1005` | PERFORM `1000-PROCESS-INPUTS`, `2000-DECIDE-ACTION`, `3000-SEND-MAP`; THRU `1000-PROCESS-INPUTS-EXIT`, `2000-DECIDE-ACTION-EXIT`; GO TO `COMMON-RETURN`; EXEC CICS HANDLE, SYNCPOINT, XCTL | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CVACT01Y.cpy` | `service/account/AccountUpdateService.java` | `private mainLine0000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theWrittenMarkerIsInterceptedBeforeTheDeciderEverSeesIt` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R002` | `COACTUPC.cbl` | online | `COMMON-RETURN` | `:L1007`&ndash;`:L1020` | EXEC CICS RETURN | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private commonReturn(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theUnexpectedScenarioArmIsUnreachableAndRetainedForParity` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R003` | `COACTUPC.cbl` | online | `0000-MAIN-EXIT` | `:L1021`&ndash;`:L1023` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private mainExit0000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R004` | `COACTUPC.cbl` | online | `1000-PROCESS-INPUTS` | `:L1025`&ndash;`:L1034` | PERFORM `1100-RECEIVE-MAP`, `1200-EDIT-MAP-INPUTS`; THRU `1100-RECEIVE-MAP-EXIT`, `1200-EDIT-MAP-INPUTS-EXIT` | `CVCRD01Y.cpy` | `service/account/AccountUpdateService.java` | `private processInputs1000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theFirstEntryTurnPaintsAnEmptyScreenWithoutReadingAnything` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R005` | `COACTUPC.cbl` | online | `1000-PROCESS-INPUTS-EXIT` | `:L1036`&ndash;`:L1038` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private processInputs1000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theFirstEntryTurnPaintsAnEmptyScreenWithoutReadingAnything` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R006` | `COACTUPC.cbl` | online | `1100-RECEIVE-MAP` | `:L1039`&ndash;`:L1425` | GO TO `1100-RECEIVE-MAP-EXIT`; EXEC CICS RECEIVE | `CVCRD01Y.cpy`, `CVACT01Y.cpy` | `service/account/AccountUpdateService.java` | `private receiveMap1100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theRetrievalEntryPointRefusesABlankFilter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R007` | `COACTUPC.cbl` | online | `1100-RECEIVE-MAP-EXIT` | `:L1426`&ndash;`:L1428` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private receiveMap1100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theRetrievalEntryPointRefusesABlankFilter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R008` | `COACTUPC.cbl` | online | `1200-EDIT-MAP-INPUTS` | `:L1429`&ndash;`:L1676` | PERFORM `1205-COMPARE-OLD-NEW`, `1210-EDIT-ACCOUNT`, `1215-EDIT-MANDATORY`, `1220-EDIT-YESNO` +9; THRU `1205-COMPARE-OLD-NEW-EXIT`, `1210-EDIT-ACCOUNT-EXIT`; GO TO `1200-EDIT-MAP-INPUTS-EXIT` | `CSUTLDWY.cpy`, `CVCUS01Y.cpy` | `service/account/AccountUpdateService.java` | `private editMapInputs1200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R009` | `COACTUPC.cbl` | online | `1200-EDIT-MAP-INPUTS-EXIT` | `:L1678`&ndash;`:L1680` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editMapInputs1200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R010` | `COACTUPC.cbl` | online | `1205-COMPARE-OLD-NEW` | `:L1681`&ndash;`:L1775` | GO TO `1205-COMPARE-OLD-NEW-EXIT` | `CVCUS01Y.cpy`, `CVACT01Y.cpy` | `service/account/AccountUpdateService.java` | `private compareOldNew1205(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::customerLockLiteralIsNotLatchedWhenAMessageIsAlreadyPending` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R011` | `COACTUPC.cbl` | online | `1205-COMPARE-OLD-NEW-EXIT` | `:L1777`&ndash;`:L1779` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private compareOldNew1205Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theTwoComparisonParagraphsRemainDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R012` | `COACTUPC.cbl` | online | `1210-EDIT-ACCOUNT` | `:L1783`&ndash;`:L1818` | GO TO `1210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private editAccount1210(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankAccountFilterOnAnUnfetchedTurnReportsNoInputReceived` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R013` | `COACTUPC.cbl` | online | `1210-EDIT-ACCOUNT-EXIT` | `:L1820`&ndash;`:L1822` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editAccount1210Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankAccountFilterOnAnUnfetchedTurnReportsNoInputReceived` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R014` | `COACTUPC.cbl` | online | `1215-EDIT-MANDATORY` | `:L1824`&ndash;`:L1851` | GO TO `1215-EDIT-MANDATORY-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editMandatory1215(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::anAddressLineOfPunctuationPassesThePresenceOnlyEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R015` | `COACTUPC.cbl` | online | `1215-EDIT-MANDATORY-EXIT` | `:L1852`&ndash;`:L1854` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editMandatory1215Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::anAddressLineOfPunctuationPassesThePresenceOnlyEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R016` | `COACTUPC.cbl` | online | `1220-EDIT-YESNO` | `:L1856`&ndash;`:L1893` | GO TO `1220-EDIT-YESNO-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editYesNo1220(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aZeroFilledAccountStatusIsTreatedAsBlank` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R017` | `COACTUPC.cbl` | online | `1220-EDIT-YESNO-EXIT` | `:L1894`&ndash;`:L1896` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editYesNo1220Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aZeroFilledAccountStatusIsTreatedAsBlank` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R018` | `COACTUPC.cbl` | online | `1225-EDIT-ALPHA-REQD` | `:L1898`&ndash;`:L1950` | GO TO `1225-EDIT-ALPHA-REQD-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphaRequired1225(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankMiddleNameIsAcceptedByTheOptionalEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R019` | `COACTUPC.cbl` | online | `1225-EDIT-ALPHA-REQD-EXIT` | `:L1951`&ndash;`:L1953` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphaRequired1225Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankMiddleNameIsAcceptedByTheOptionalEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R020` | `COACTUPC.cbl` | online | `1230-EDIT-ALPHANUM-REQD` | `:L1955`&ndash;`:L2008` | GO TO `1230-EDIT-ALPHANUM-REQD-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphanumericRequired1230(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theNeverPerformedAlphanumericEditsCanNeverProduceTheirDiagnostic` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R021` | `COACTUPC.cbl` | online | `1230-EDIT-ALPHANUM-REQD-EXIT` | `:L2009`&ndash;`:L2011` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphanumericRequired1230Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theNeverPerformedAlphanumericEditsCanNeverProduceTheirDiagnostic` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R022` | `COACTUPC.cbl` | online | `1235-EDIT-ALPHA-OPT` | `:L2012`&ndash;`:L2056` | GO TO `1235-EDIT-ALPHA-OPT-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphaOptional1235(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankMiddleNameIsAcceptedByTheOptionalEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R023` | `COACTUPC.cbl` | online | `1235-EDIT-ALPHA-OPT-EXIT` | `:L2057`&ndash;`:L2059` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphaOptional1235Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankMiddleNameIsAcceptedByTheOptionalEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R024` | `COACTUPC.cbl` | online | `1240-EDIT-ALPHANUM-OPT` | `:L2061`&ndash;`:L2104` | GO TO `1240-EDIT-ALPHANUM-OPT-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphanumericOptional1240(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theNeverPerformedAlphanumericEditsCanNeverProduceTheirDiagnostic` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R025` | `COACTUPC.cbl` | online | `1240-EDIT-ALPHANUM-OPT-EXIT` | `:L2105`&ndash;`:L2107` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editAlphanumericOptional1240Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theNeverPerformedAlphanumericEditsCanNeverProduceTheirDiagnostic` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R026` | `COACTUPC.cbl` | online | `1245-EDIT-NUM-REQD` | `:L2109`&ndash;`:L2175` | GO TO `1245-EDIT-NUM-REQD-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editNumericRequired1245(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R027` | `COACTUPC.cbl` | online | `1245-EDIT-NUM-REQD-EXIT` | `:L2176`&ndash;`:L2178` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editNumericRequired1245Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R028` | `COACTUPC.cbl` | online | `1250-EDIT-SIGNED-9V2` | `:L2180`&ndash;`:L2219` | GO TO `1250-EDIT-SIGNED-9V2-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editSignedAmount1250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R029` | `COACTUPC.cbl` | online | `1250-EDIT-SIGNED-9V2-EXIT` | `:L2221`&ndash;`:L2223` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editSignedAmount1250Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R030` | `COACTUPC.cbl` | online | `1260-EDIT-US-PHONE-NUM` | `:L2225`&ndash;`:L2245` | GO TO `EDIT-US-PHONE-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editUsPhoneNumber1260(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R031` | `COACTUPC.cbl` | online | `EDIT-AREA-CODE` | `:L2246`&ndash;`:L2315` | GO TO `EDIT-US-PHONE-PREFIX` | `CSLKPCDY.cpy` | `service/account/AccountUpdateService.java` | `private editAreaCode(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankPrefixMustBeSupplied` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R032` | `COACTUPC.cbl` | online | `EDIT-US-PHONE-PREFIX` | `:L2316`&ndash;`:L2368` | GO TO `EDIT-US-PHONE-LINENUM` | &mdash; | `service/account/AccountUpdateService.java` | `private editUsPhonePrefix(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankPrefixMustBeSupplied` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R033` | `COACTUPC.cbl` | online | `EDIT-US-PHONE-LINENUM` | `:L2370`&ndash;`:L2422` | GO TO `EDIT-US-PHONE-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editUsPhoneLineNumber(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankPrefixMustBeSupplied` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R034` | `COACTUPC.cbl` | online | `EDIT-US-PHONE-EXIT` | `:L2424`&ndash;`:L2426` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editUsPhoneExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::anEntirelyAbsentTelephoneNumberIsAcceptedByTheShortCircuit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R035` | `COACTUPC.cbl` | online | `1260-EDIT-US-PHONE-NUM-EXIT` | `:L2427`&ndash;`:L2429` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editUsPhoneNumber1260Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R036` | `COACTUPC.cbl` | online | `1265-EDIT-US-SSN` | `:L2431`&ndash;`:L2488` | PERFORM `1245-EDIT-NUM-REQD`; THRU `1245-EDIT-NUM-REQD-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editUsSsn1265(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankNationalIdentifierFirstMemberStopsTheRemainingMembers` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R037` | `COACTUPC.cbl` | online | `1265-EDIT-US-SSN-EXIT` | `:L2489`&ndash;`:L2491` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editUsSsn1265Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankNationalIdentifierFirstMemberStopsTheRemainingMembers` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R038` | `COACTUPC.cbl` | online | `1270-EDIT-US-STATE-CD` | `:L2493`&ndash;`:L2510` | GO TO `1270-EDIT-US-STATE-CD-EXIT` | `CSLKPCDY.cpy` | `service/account/AccountUpdateService.java` | `private editUsStateCode1270(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R039` | `COACTUPC.cbl` | online | `1270-EDIT-US-STATE-CD-EXIT` | `:L2511`&ndash;`:L2513` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editUsStateCode1270Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R040` | `COACTUPC.cbl` | online | `1275-EDIT-FICO-SCORE` | `:L2514`&ndash;`:L2530` | GO TO `1275-EDIT-FICO-SCORE-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private editFicoScore1275(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankCreditScoreMustBeSupplied` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R041` | `COACTUPC.cbl` | online | `1275-EDIT-FICO-SCORE-EXIT` | `:L2531`&ndash;`:L2533` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editFicoScore1275Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aBlankCreditScoreMustBeSupplied` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R042` | `COACTUPC.cbl` | online | `1280-EDIT-US-STATE-ZIP-CD` | `:L2536`&ndash;`:L2557` | GO TO `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `CSLKPCDY.cpy` | `service/account/AccountUpdateService.java` | `private editUsStateZipCode1280(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R043` | `COACTUPC.cbl` | online | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `:L2558`&ndash;`:L2560` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private editUsStateZipCode1280Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyFieldEditParagraphMapsOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R044` | `COACTUPC.cbl` | online | `2000-DECIDE-ACTION` | `:L2562`&ndash;`:L2642` | PERFORM `9000-READ-ACCT`, `9600-WRITE-PROCESSING`, `ABEND-ROUTINE`; THRU `9000-READ-ACCT-EXIT`, `9600-WRITE-PROCESSING-EXIT` | `CSMSG02Y.cpy`, `COCOM01Y.cpy`, `CVCRD01Y.cpy` | `service/account/AccountUpdateService.java` | `private decideAction2000(&hellip;)` | parity-preserved quirk | [`DL-PP-02`](DECISION_LOG.md#dl-pp-02) | `unit/service/AccountUpdateServiceTest.java::theUnexpectedScenarioArmIsUnreachableAndRetainedForParity` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R045` | `COACTUPC.cbl` | online | `2000-DECIDE-ACTION-EXIT` | `:L2643`&ndash;`:L2645` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private decideAction2000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theUnexpectedScenarioArmIsUnreachableAndRetainedForParity` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R046` | `COACTUPC.cbl` | online | `3000-SEND-MAP` | `:L2649`&ndash;`:L2662` | PERFORM `3100-SCREEN-INIT`, `3200-SETUP-SCREEN-VARS`, `3250-SETUP-INFOMSG`, `3300-SETUP-SCREEN-ATTRS` +2; THRU `3100-SCREEN-INIT-EXIT`, `3200-SETUP-SCREEN-VARS-EXIT` | &mdash; | `service/account/AccountUpdateService.java` | `private sendMap3000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R047` | `COACTUPC.cbl` | online | `3000-SEND-MAP-EXIT` | `:L2664`&ndash;`:L2666` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private sendMap3000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R048` | `COACTUPC.cbl` | online | `3100-SCREEN-INIT` | `:L2668`&ndash;`:L2692` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/account/AccountUpdateService.java` | `private screenInit3100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R049` | `COACTUPC.cbl` | online | `3100-SCREEN-INIT-EXIT` | `:L2694`&ndash;`:L2696` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private screenInit3100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R050` | `COACTUPC.cbl` | online | `3200-SETUP-SCREEN-VARS` | `:L2698`&ndash;`:L2726` | PERFORM `3201-SHOW-INITIAL-VALUES`, `3202-SHOW-ORIGINAL-VALUES`, `3203-SHOW-UPDATED-VALUES`; THRU `3201-SHOW-INITIAL-VALUES-EXIT`, `3202-SHOW-ORIGINAL-VALUES-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private setupScreenVars3200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenPaintersWhenOtherArmIsUnreachable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R051` | `COACTUPC.cbl` | online | `3200-SETUP-SCREEN-VARS-EXIT` | `:L2727`&ndash;`:L2729` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private setupScreenVars3200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenPaintersWhenOtherArmIsUnreachable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R052` | `COACTUPC.cbl` | online | `3201-SHOW-INITIAL-VALUES` | `:L2731`&ndash;`:L2781` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showInitialValues3201(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aZeroFilterPaintsNoDetailFieldAtAll` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R053` | `COACTUPC.cbl` | online | `3201-SHOW-INITIAL-VALUES-EXIT` | `:L2783`&ndash;`:L2785` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showInitialValues3201Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aZeroFilterPaintsNoDetailFieldAtAll` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R054` | `COACTUPC.cbl` | online | `3202-SHOW-ORIGINAL-VALUES` | `:L2787`&ndash;`:L2865` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showOriginalValues3202(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aRefusedWriteRepaintsTheSnapshotRatherThanTheSubmittedText` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R055` | `COACTUPC.cbl` | online | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `:L2867`&ndash;`:L2869` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showOriginalValues3202Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::aRefusedWriteRepaintsTheSnapshotRatherThanTheSubmittedText` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R056` | `COACTUPC.cbl` | online | `3203-SHOW-UPDATED-VALUES` | `:L2870`&ndash;`:L2949` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showUpdatedValues3203(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::anEditTurnWithChangesEchoesWhatTheOperatorSubmitted` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R057` | `COACTUPC.cbl` | online | `3203-SHOW-UPDATED-VALUES-EXIT` | `:L2951`&ndash;`:L2953` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private showUpdatedValues3203Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::anEditTurnWithChangesEchoesWhatTheOperatorSubmitted` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R058` | `COACTUPC.cbl` | online | `3250-SETUP-INFOMSG` | `:L2955`&ndash;`:L2982` | &mdash; | `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private setupInfoMessage3250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::customerLockFailureSetsTheFlagAndIsStillReportedAsSuccess` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R059` | `COACTUPC.cbl` | online | `3250-SETUP-INFOMSG-EXIT` | `:L2983`&ndash;`:L2985` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private setupInfoMessage3250Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyExitLabelSurvivesAsItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R060` | `COACTUPC.cbl` | online | `3300-SETUP-SCREEN-ATTRS` | `:L2986`&ndash;`:L3436` | PERFORM `3310-PROTECT-ALL-ATTRS`, `3320-UNPROTECT-FEW-ATTRS`; THRU `3310-PROTECT-ALL-ATTRS-EXIT`, `3320-UNPROTECT-FEW-ATTRS-EXIT`; GO TO `3300-SETUP-SCREEN-ATTRS-EXIT` | `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private setupScreenAttributes3300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R061` | `COACTUPC.cbl` | online | `3300-SETUP-SCREEN-ATTRS-EXIT` | `:L3437`&ndash;`:L3439` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private setupScreenAttributes3300Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R062` | `COACTUPC.cbl` | online | `3310-PROTECT-ALL-ATTRS` | `:L3441`&ndash;`:L3495` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private protectAllAttributes3310(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenProtectionBranchesDifferByChangeAction` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R063` | `COACTUPC.cbl` | online | `3310-PROTECT-ALL-ATTRS-EXIT` | `:L3496`&ndash;`:L3498` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private protectAllAttributes3310Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenProtectionBranchesDifferByChangeAction` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R064` | `COACTUPC.cbl` | online | `3320-UNPROTECT-FEW-ATTRS` | `:L3500`&ndash;`:L3561` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private unprotectFewAttributes3320(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenProtectionBranchesDifferByChangeAction` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R065` | `COACTUPC.cbl` | online | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `:L3562`&ndash;`:L3564` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private unprotectFewAttributes3320Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theScreenProtectionBranchesDifferByChangeAction` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R066` | `COACTUPC.cbl` | online | `3390-SETUP-INFOMSG-ATTRS` | `:L3566`&ndash;`:L3583` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private setupInfoMessageAttributes3390(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R067` | `COACTUPC.cbl` | online | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `:L3584`&ndash;`:L3586` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private setupInfoMessageAttributes3390Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R068` | `COACTUPC.cbl` | online | `3400-SEND-SCREEN` | `:L3589`&ndash;`:L3602` | EXEC CICS SEND | `CVCRD01Y.cpy` | `service/account/AccountUpdateService.java` | `private sendScreen3400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R069` | `COACTUPC.cbl` | online | `3400-SEND-SCREEN-EXIT` | `:L3603`&ndash;`:L3605` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private sendScreen3400Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theStructuralParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R070` | `COACTUPC.cbl` | online | `9000-READ-ACCT` | `:L3608`&ndash;`:L3644` | PERFORM `9200-GETCARDXREF-BYACCT`, `9300-GETACCTDATA-BYACCT`, `9400-GETCUSTDATA-BYCUST`, `9500-STORE-FETCHED-DATA`; THRU `9200-GETCARDXREF-BYACCT-EXIT`, `9300-GETACCTDATA-BYACCT-EXIT`; GO TO `9000-READ-ACCT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private readAccount9000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::fetchSnapshotForUpdateProducesAGroupTheWriteAccepts` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R071` | `COACTUPC.cbl` | online | `9000-READ-ACCT-EXIT` | `:L3647`&ndash;`:L3649` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private readAccount9000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::fetchSnapshotForUpdateProducesAGroupTheWriteAccepts` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R072` | `COACTUPC.cbl` | online | `9200-GETCARDXREF-BYACCT` | `:L3650`&ndash;`:L3697` | EXEC CICS READ | `CVACT03Y.cpy`, `COCOM01Y.cpy`, `CVACT02Y.cpy` | `service/account/AccountUpdateService.java` | `private getCardXrefByAccount9200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R073` | `COACTUPC.cbl` | online | `9200-GETCARDXREF-BYACCT-EXIT` | `:L3698`&ndash;`:L3700` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private getCardXrefByAccount9200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R074` | `COACTUPC.cbl` | online | `9300-GETACCTDATA-BYACCT` | `:L3701`&ndash;`:L3747` | EXEC CICS READ | `CVACT01Y.cpy` | `service/account/AccountUpdateService.java` | `private getAccountDataByAccount9300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R075` | `COACTUPC.cbl` | online | `9300-GETACCTDATA-BYACCT-EXIT` | `:L3748`&ndash;`:L3750` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private getAccountDataByAccount9300Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R076` | `COACTUPC.cbl` | online | `9400-GETCUSTDATA-BYCUST` | `:L3752`&ndash;`:L3796` | EXEC CICS READ | &mdash; | `service/account/AccountUpdateService.java` | `private getCustomerDataByCustomer9400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R077` | `COACTUPC.cbl` | online | `9400-GETCUSTDATA-BYCUST-EXIT` | `:L3797`&ndash;`:L3799` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private getCustomerDataByCustomer9400Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R078` | `COACTUPC.cbl` | online | `9500-STORE-FETCHED-DATA` | `:L3801`&ndash;`:L3884` | &mdash; | `CVACT01Y.cpy`, `COCOM01Y.cpy`, `CVCUS01Y.cpy` | `service/account/AccountUpdateService.java` | `private storeFetchedData9500(&hellip;)` | mechanism substitution | [`DL-MS-15`](DECISION_LOG.md#dl-ms-15) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R079` | `COACTUPC.cbl` | online | `9500-STORE-FETCHED-DATA-EXIT` | `:L3885`&ndash;`:L3887` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private storeFetchedData9500Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theReadAndWriteParagraphsEachMapToOneMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R080` | `COACTUPC.cbl` | online | `9600-WRITE-PROCESSING` | `:L3888`&ndash;`:L4104` | PERFORM `9700-CHECK-CHANGE-IN-REC`; THRU `9700-CHECK-CHANGE-IN-REC-EXIT`; GO TO `9600-WRITE-PROCESSING-EXIT`; EXEC CICS READ, REWRITE, SYNCPOINT | `CVACT01Y.cpy`, `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountUpdateService.java` | `private writeProcessing9600(&hellip;)` | parity-preserved quirk | [`DL-PP-01`](DECISION_LOG.md#dl-pp-01) | `unit/service/AccountUpdateServiceTest.java::accountRewriteFailureLeavesNeitherRowWritten` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R081` | `COACTUPC.cbl` | online | `9600-WRITE-PROCESSING-EXIT` | `:L4105`&ndash;`:L4107` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private writeProcessing9600Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::accountRewriteFailureLeavesNeitherRowWritten` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R082` | `COACTUPC.cbl` | online | `9700-CHECK-CHANGE-IN-REC` | `:L4109`&ndash;`:L4192` | GO TO `9600-WRITE-PROCESSING-EXIT` | `CVACT01Y.cpy`, `CVCUS01Y.cpy` | `service/account/AccountUpdateService.java` | `private checkChangeInRecord9700(&hellip;)` | parity-preserved quirk | [`DL-PP-02`](DECISION_LOG.md#dl-pp-02) | `unit/service/AccountUpdateServiceTest.java::everyExitLabelSurvivesAsItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R083` | `COACTUPC.cbl` | online | `9700-CHECK-CHANGE-IN-REC-EXIT` | `:L4193`&ndash;`:L4200` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private checkChangeInRecord9700Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyExitLabelSurvivesAsItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R084` | `COACTUPC.cbl` | online | `ABEND-ROUTINE` | `:L4203`&ndash;`:L4225` | EXEC CICS ABEND, HANDLE, SEND | `CSMSG02Y.cpy` | `service/account/AccountUpdateService.java` | `private abendRoutine(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::theDefaultAbendMessageSubstitutionIsATrackedNoOp` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTUPC-R085` | `COACTUPC.cbl` | online | `ABEND-ROUTINE-EXIT` | `:L4226`&ndash;`:L4233` | &mdash; | &mdash; | `service/account/AccountUpdateService.java` | `private abendRoutineExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountUpdateServiceTest.java::everyExitLabelSurvivesAsItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COACTUPC.cbl`.**

- **`TM-COACTUPC-R044` &middot; `2000-DECIDE-ACTION` &middot; `:L2562`&ndash;`:L2642` &middot; parity-preserved quirk &middot; [`DL-PP-02`](DECISION_LOG.md#dl-pp-02).** Five distinguishable outcomes must reach the REST layer separately: account lock failure, customer lock failure, data-changed-before-update, locked-but-update-failed, and the lock-error marker. Collapsing them into one conflict status loses information the legacy screen displayed.
- **`TM-COACTUPC-R078` &middot; `9500-STORE-FETCHED-DATA` &middot; `:L3801`&ndash;`:L3884` &middot; mechanism substitution &middot; [`DL-MS-15`](DECISION_LOG.md#dl-ms-15).** Captures the `ACUP-OLD-DETAILS` snapshot (`:L669`-`:L756`). Statelessness moves the snapshot into the request body, which is why `AccountUpdateRequest` carries both `oldDetails` and `newDetails`.
- **`TM-COACTUPC-R080` &middot; `9600-WRITE-PROCESSING` &middot; `:L3888`&ndash;`:L4104` &middot; parity-preserved quirk &middot; [`DL-PP-01`](DECISION_LOG.md#dl-pp-01).** Seven-step write in fixed order. `EXEC CICS SYNCPOINT ROLLBACK` fires ONLY on the customer-rewrite failure at `:L4098`, not on the account-rewrite failure at `:L4079`; one `@Transactional(rollbackFor = Exception.class)` scope reproduces BOTH branches without conditional logic, because each failure path returns before the commit point. One thing this paragraph's Java counterpart does that the paragraph does not: `requireStorableUpdateImage` screens the twenty values the two update images move, and refuses the write when one is absent, where the source would have moved `LOW-VALUES` and reported success. That refusal is a labelled deviation, [`DL-DV-06`](DECISION_LOG.md#dl-dv-06), not part of the paragraph translation &mdash; it is separated out here so that this row's `parity-preserved quirk` classification is read as covering the seven steps and the rollback asymmetry, and not as covering the added screen.
- **`TM-COACTUPC-R082` &middot; `9700-CHECK-CHANGE-IN-REC` &middot; `:L4109`&ndash;`:L4192` &middot; parity-preserved quirk &middot; [`DL-PP-02`](DECISION_LOG.md#dl-pp-02).** Snapshot comparison, NOT a version counter. Case asymmetry is behaviour: group id compared through `FUNCTION LOWER-CASE`, customer name/address/state/country/government id through `FUNCTION UPPER-CASE`, and postal code, both telephone numbers, SSN, EFT id, primary-holder flag and credit score with NO case function. Dates compared as three substrings. **DOB offsets differ per side** - live record `1/6/9` (dash-separated), snapshot `1/5/7` (unseparated); a whole-string compare would report a change on every request. `@Version` is layered on top but is not parity on its own.

<a id="cocrdupc-cbl"></a>

### 5.2 `COCRDUPC.cbl`

**online &middot; F-007 Card update &middot; 1,560 physical lines &middot; 45 rows** (45 paragraphs)

Target files: `service/card/CardUpdateService.java`, `controller/CardController.java`.
Tests: `unit/service/CardUpdateServiceTest.java`, `unit/model/CardUpdateRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COCRDUPC-R001` | `COCRDUPC.cbl` | online | `0000-MAIN` | `:L367`&ndash;`:L544` | PERFORM `1000-PROCESS-INPUTS`, `2000-DECIDE-ACTION`, `3000-SEND-MAP`, `9000-READ-DATA`; THRU `1000-PROCESS-INPUTS-EXIT`, `2000-DECIDE-ACTION-EXIT`; GO TO `COMMON-RETURN`; EXEC CICS HANDLE, SYNCPOINT, XCTL | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CVACT02Y.cpy` | `service/card/CardUpdateService.java` | `private mainLine0000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::mainlineLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R002` | `COCRDUPC.cbl` | online | `COMMON-RETURN` | `:L546`&ndash;`:L559` | EXEC CICS RETURN | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardUpdateService.java` | `private commonReturn(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::mainlineLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R003` | `COCRDUPC.cbl` | online | `0000-MAIN-EXIT` | `:L560`&ndash;`:L562` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private mainExit0000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::mainlineLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R004` | `COCRDUPC.cbl` | online | `1000-PROCESS-INPUTS` | `:L564`&ndash;`:L573` | PERFORM `1100-RECEIVE-MAP`, `1200-EDIT-MAP-INPUTS`; THRU `1100-RECEIVE-MAP-EXIT`, `1200-EDIT-MAP-INPUTS-EXIT` | `CVCRD01Y.cpy` | `service/card/CardUpdateService.java` | `private processInputs1000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R005` | `COCRDUPC.cbl` | online | `1000-PROCESS-INPUTS-EXIT` | `:L575`&ndash;`:L577` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private processInputsExit1000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R006` | `COCRDUPC.cbl` | online | `1100-RECEIVE-MAP` | `:L578`&ndash;`:L636` | EXEC CICS RECEIVE | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardUpdateService.java` | `private receiveMap1100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R007` | `COCRDUPC.cbl` | online | `1100-RECEIVE-MAP-EXIT` | `:L638`&ndash;`:L640` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private receiveMapExit1100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R008` | `COCRDUPC.cbl` | online | `1200-EDIT-MAP-INPUTS` | `:L641`&ndash;`:L715` | PERFORM `1210-EDIT-ACCOUNT`, `1220-EDIT-CARD`, `1230-EDIT-NAME`, `1240-EDIT-CARDSTATUS` +2; THRU `1210-EDIT-ACCOUNT-EXIT`, `1220-EDIT-CARD-EXIT`; GO TO `1200-EDIT-MAP-INPUTS-EXIT` | `CVACT02Y.cpy`, `COCOM01Y.cpy`, `CVACT01Y.cpy` | `service/card/CardUpdateService.java` | `private editMapInputs1200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R009` | `COCRDUPC.cbl` | online | `1200-EDIT-MAP-INPUTS-EXIT` | `:L717`&ndash;`:L719` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editMapInputsExit1200()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::inputProcessingLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R010` | `COCRDUPC.cbl` | online | `1210-EDIT-ACCOUNT` | `:L721`&ndash;`:L756` | GO TO `1210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardUpdateService.java` | `private editAccount1210(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::accountFilterEditPrecedesTheCardFilterEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R011` | `COCRDUPC.cbl` | online | `1210-EDIT-ACCOUNT-EXIT` | `:L758`&ndash;`:L760` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editAccountExit1210()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R012` | `COCRDUPC.cbl` | online | `1220-EDIT-CARD` | `:L762`&ndash;`:L800` | GO TO `1220-EDIT-CARD-EXIT` | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `COCOM01Y.cpy` | `service/card/CardUpdateService.java` | `private editCard1220(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::accountFilterEditPrecedesTheCardFilterEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R013` | `COCRDUPC.cbl` | online | `1220-EDIT-CARD-EXIT` | `:L802`&ndash;`:L804` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editCardExit1220()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R014` | `COCRDUPC.cbl` | online | `1230-EDIT-NAME` | `:L806`&ndash;`:L840` | GO TO `1230-EDIT-NAME-EXIT` | &mdash; | `service/card/CardUpdateService.java` | `private editName1230(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::nameMessageWinsWhenEveryFieldIsBad` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R015` | `COCRDUPC.cbl` | online | `1230-EDIT-NAME-EXIT` | `:L841`&ndash;`:L843` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editNameExit1230()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R016` | `COCRDUPC.cbl` | online | `1240-EDIT-CARDSTATUS` | `:L845`&ndash;`:L873` | GO TO `1240-EDIT-CARDSTATUS-EXIT` | &mdash; | `service/card/CardUpdateService.java` | `private editCardStatus1240(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::statusMessageWinsOnceTheNameIsValid` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R017` | `COCRDUPC.cbl` | online | `1240-EDIT-CARDSTATUS-EXIT` | `:L874`&ndash;`:L876` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editCardStatusExit1240()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R018` | `COCRDUPC.cbl` | online | `1250-EDIT-EXPIRY-MON` | `:L877`&ndash;`:L908` | GO TO `1250-EDIT-EXPIRY-MON-EXIT` | &mdash; | `service/card/CardUpdateService.java` | `private editExpiryMonth1250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::monthMessageWinsOnceTheStatusIsValid` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R019` | `COCRDUPC.cbl` | online | `1250-EDIT-EXPIRY-MON-EXIT` | `:L910`&ndash;`:L912` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editExpiryMonthExit1250()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R020` | `COCRDUPC.cbl` | online | `1260-EDIT-EXPIRY-YEAR` | `:L913`&ndash;`:L944` | GO TO `1260-EDIT-EXPIRY-YEAR-EXIT` | &mdash; | `service/card/CardUpdateService.java` | `private editExpiryYear1260(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::yearMessageWinsOnceTheMonthIsValid` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R021` | `COCRDUPC.cbl` | online | `1260-EDIT-EXPIRY-YEAR-EXIT` | `:L945`&ndash;`:L947` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private editExpiryYearExit1260()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::allSixFieldEditLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R022` | `COCRDUPC.cbl` | online | `2000-DECIDE-ACTION` | `:L948`&ndash;`:L1028` | PERFORM `9000-READ-DATA`, `9200-WRITE-PROCESSING`, `ABEND-ROUTINE`; THRU `9000-READ-DATA-EXIT`, `9200-WRITE-PROCESSING-EXIT` | `CSMSG02Y.cpy`, `COCOM01Y.cpy`, `CVCRD01Y.cpy` | `service/card/CardUpdateService.java` | `private decideAction2000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R023` | `COCRDUPC.cbl` | online | `2000-DECIDE-ACTION-EXIT` | `:L1029`&ndash;`:L1031` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private decideActionExit2000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R024` | `COCRDUPC.cbl` | online | `3000-SEND-MAP` | `:L1035`&ndash;`:L1046` | PERFORM `3100-SCREEN-INIT`, `3200-SETUP-SCREEN-VARS`, `3250-SETUP-INFOMSG`, `3300-SETUP-SCREEN-ATTRS` +1; THRU `3100-SCREEN-INIT-EXIT`, `3200-SETUP-SCREEN-VARS-EXIT` | &mdash; | `service/card/CardUpdateService.java` | `private sendMap3000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R025` | `COCRDUPC.cbl` | online | `3000-SEND-MAP-EXIT` | `:L1048`&ndash;`:L1050` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private sendMapExit3000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R026` | `COCRDUPC.cbl` | online | `3100-SCREEN-INIT` | `:L1052`&ndash;`:L1076` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/card/CardUpdateService.java` | `private screenInit3100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R027` | `COCRDUPC.cbl` | online | `3100-SCREEN-INIT-EXIT` | `:L1078`&ndash;`:L1080` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private screenInitExit3100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R028` | `COCRDUPC.cbl` | online | `3200-SETUP-SCREEN-VARS` | `:L1082`&ndash;`:L1134` | &mdash; | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardUpdateService.java` | `private setupScreenVars3200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::theSubmittedExpiryDayNeverReachesTheRow` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R029` | `COCRDUPC.cbl` | online | `3200-SETUP-SCREEN-VARS-EXIT` | `:L1135`&ndash;`:L1137` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private setupScreenVarsExit3200()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R030` | `COCRDUPC.cbl` | online | `3250-SETUP-INFOMSG` | `:L1138`&ndash;`:L1164` | &mdash; | `COCOM01Y.cpy` | `service/card/CardUpdateService.java` | `private setupInfoMsg3250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R031` | `COCRDUPC.cbl` | online | `3250-SETUP-INFOMSG-EXIT` | `:L1165`&ndash;`:L1167` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private setupInfoMsgExit3250()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R032` | `COCRDUPC.cbl` | online | `3300-SETUP-SCREEN-ATTRS` | `:L1168`&ndash;`:L1318` | &mdash; | `COCOM01Y.cpy` | `service/card/CardUpdateService.java` | `private setupScreenAttrs3300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R033` | `COCRDUPC.cbl` | online | `3300-SETUP-SCREEN-ATTRS-EXIT` | `:L1319`&ndash;`:L1321` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private setupScreenAttrsExit3300()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R034` | `COCRDUPC.cbl` | online | `3400-SEND-SCREEN` | `:L1324`&ndash;`:L1337` | EXEC CICS SEND | `CVCRD01Y.cpy` | `service/card/CardUpdateService.java` | `private sendScreen3400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R035` | `COCRDUPC.cbl` | online | `3400-SEND-SCREEN-EXIT` | `:L1338`&ndash;`:L1340` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private sendScreenExit3400()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::screenLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R036` | `COCRDUPC.cbl` | online | `9000-READ-DATA` | `:L1343`&ndash;`:L1370` | PERFORM `9100-GETCARD-BYACCTCARD`; THRU `9100-GETCARD-BYACCTCARD-EXIT` | `CVACT02Y.cpy`, `CVCRD01Y.cpy`, `CVACT01Y.cpy` | `service/card/CardUpdateService.java` | `private readData9000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::upperCaseSnapshotAbsorbsACaseOnlyLiveDifference` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R037` | `COCRDUPC.cbl` | online | `9000-READ-DATA-EXIT` | `:L1372`&ndash;`:L1374` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private readDataExit9000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::fileLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R038` | `COCRDUPC.cbl` | online | `9100-GETCARD-BYACCTCARD` | `:L1376`&ndash;`:L1413` | EXEC CICS READ | `CVACT02Y.cpy`, `CVCRD01Y.cpy` | `service/card/CardUpdateService.java` | `private getCardByAcctCard9100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::fetchLegReportsTheNotFoundLiteral` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R039` | `COCRDUPC.cbl` | online | `9100-GETCARD-BYACCTCARD-EXIT` | `:L1415`&ndash;`:L1417` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private getCardByAcctCardExit9100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::fileLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R040` | `COCRDUPC.cbl` | online | `9200-WRITE-PROCESSING` | `:L1420`&ndash;`:L1493` | PERFORM `9300-CHECK-CHANGE-IN-REC`; THRU `9300-CHECK-CHANGE-IN-REC-EXIT`; GO TO `9200-WRITE-PROCESSING-EXIT`; EXEC CICS READ, REWRITE | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardUpdateService.java` | `private writeProcessing9200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::readForUpdateThenDetectThenRewrite` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R041` | `COCRDUPC.cbl` | online | `9200-WRITE-PROCESSING-EXIT` | `:L1494`&ndash;`:L1496` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private writeProcessingExit9200()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::fileLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R042` | `COCRDUPC.cbl` | online | `9300-CHECK-CHANGE-IN-REC` | `:L1498`&ndash;`:L1520` | GO TO `9200-WRITE-PROCESSING-EXIT` | `CVACT02Y.cpy` | `service/card/CardUpdateService.java` | `private checkChangeInRec9300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::fileLabelsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R043` | `COCRDUPC.cbl` | online | `9300-CHECK-CHANGE-IN-REC-EXIT` | `:L1521`&ndash;`:L1529` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private checkChangeInRecExit9300()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::redundantInParagraphExitOf9300IsRetained` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R044` | `COCRDUPC.cbl` | online | `ABEND-ROUTINE` | `:L1531`&ndash;`:L1553` | EXEC CICS ABEND, HANDLE, SEND | `CSMSG02Y.cpy` | `service/card/CardUpdateService.java` | `private abendRoutine(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::unexpectedRuntimeFailureBecomesAFatalOutcome` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDUPC-R045` | `COCRDUPC.cbl` | online | `ABEND-ROUTINE-EXIT` | `:L1554`&ndash;`:L1556` | &mdash; | &mdash; | `service/card/CardUpdateService.java` | `private abendRoutineExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardUpdateServiceTest.java::proceduralCopybookAndAbendRoutineHaveTheirOwnMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cocrdlic-cbl"></a>

### 5.3 `COCRDLIC.cbl`

**online &middot; F-005 Card list &middot; 1,459 physical lines &middot; 39 rows** (39 paragraphs)

Target files: `service/card/CardListService.java`, `controller/CardController.java`.
Tests: `unit/service/CardListServiceTest.java`, `unit/model/PageResponseTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COCRDLIC-R001` | `COCRDLIC.cbl` | online | `0000-MAIN` | `:L298`&ndash;`:L602` | PERFORM `1000-SEND-MAP`, `2000-RECEIVE-MAP`, `9000-READ-FORWARD`, `9100-READ-BACKWARDS`; THRU `1000-SEND-MAP`, `1000-SEND-MAP-EXIT`; GO TO `COMMON-RETURN`; EXEC CICS XCTL | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CVACT02Y.cpy` | `service/card/CardListService.java` | `private main0000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::pagingForwardAdvancesFromTheLastKeyOfThePreviousPage` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R002` | `COCRDLIC.cbl` | online | `COMMON-RETURN` | `:L604`&ndash;`:L620` | EXEC CICS RETURN | `COCOM01Y.cpy` | `service/card/CardListService.java` | `private commonReturn(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R003` | `COCRDLIC.cbl` | online | `0000-MAIN-EXIT` | `:L621`&ndash;`:L623` | &mdash; | &mdash; | `service/card/CardListService.java` | `private main0000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R004` | `COCRDLIC.cbl` | online | `1000-SEND-MAP` | `:L624`&ndash;`:L637` | PERFORM `1100-SCREEN-INIT`, `1200-SCREEN-ARRAY-INIT`, `1250-SETUP-ARRAY-ATTRIBS`, `1300-SETUP-SCREEN-ATTRS` +2; THRU `1100-SCREEN-INIT-EXIT`, `1200-SCREEN-ARRAY-INIT-EXIT` | &mdash; | `service/card/CardListService.java` | `private sendMap1000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R005` | `COCRDLIC.cbl` | online | `1000-SEND-MAP-EXIT` | `:L639`&ndash;`:L641` | &mdash; | &mdash; | `service/card/CardListService.java` | `private sendMap1000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R006` | `COCRDLIC.cbl` | online | `1100-SCREEN-INIT` | `:L642`&ndash;`:L672` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/card/CardListService.java` | `private screenInit1100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R007` | `COCRDLIC.cbl` | online | `1100-SCREEN-INIT-EXIT` | `:L674`&ndash;`:L676` | &mdash; | &mdash; | `service/card/CardListService.java` | `private screenInit1100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R008` | `COCRDLIC.cbl` | online | `1200-SCREEN-ARRAY-INIT` | `:L678`&ndash;`:L743` | &mdash; | `CVACT02Y.cpy` | `service/card/CardListService.java` | `private screenArrayInit1200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::aShortFinalPageIsNotPadded` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R009` | `COCRDLIC.cbl` | online | `1200-SCREEN-ARRAY-INIT-EXIT` | `:L745`&ndash;`:L747` | &mdash; | &mdash; | `service/card/CardListService.java` | `private screenArrayInit1200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R010` | `COCRDLIC.cbl` | online | `1250-SETUP-ARRAY-ATTRIBS` | `:L748`&ndash;`:L832` | &mdash; | &mdash; | `service/card/CardListService.java` | `private setupArrayAttribs1250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theScreenAttributeProjectionsAreFixedWidth` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R011` | `COCRDLIC.cbl` | online | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `:L834`&ndash;`:L836` | &mdash; | &mdash; | `service/card/CardListService.java` | `private setupArrayAttribs1250Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R012` | `COCRDLIC.cbl` | online | `1300-SETUP-SCREEN-ATTRS` | `:L837`&ndash;`:L889` | &mdash; | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CVACT02Y.cpy` | `service/card/CardListService.java` | `private setupScreenAttrs1300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theScreenAttributeProjectionsAreFixedWidth` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R013` | `COCRDLIC.cbl` | online | `1300-SETUP-SCREEN-ATTRS-EXIT` | `:L890`&ndash;`:L892` | &mdash; | &mdash; | `service/card/CardListService.java` | `private setupScreenAttrs1300Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R014` | `COCRDLIC.cbl` | online | `1400-SETUP-MESSAGE` | `:L895`&ndash;`:L932` | &mdash; | `CVCRD01Y.cpy` | `service/card/CardListService.java` | `private setupMessage1400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::pagingPastTheEndWithTheLastPageAlreadyShownYieldsNoMorePages` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R015` | `COCRDLIC.cbl` | online | `1400-SETUP-MESSAGE-EXIT` | `:L933`&ndash;`:L935` | &mdash; | &mdash; | `service/card/CardListService.java` | `private setupMessage1400Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R016` | `COCRDLIC.cbl` | online | `1500-SEND-SCREEN` | `:L938`&ndash;`:L947` | EXEC CICS SEND | &mdash; | `service/card/CardListService.java` | `private sendScreen1500(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R017` | `COCRDLIC.cbl` | online | `1500-SEND-SCREEN-EXIT` | `:L948`&ndash;`:L950` | &mdash; | &mdash; | `service/card/CardListService.java` | `private sendScreen1500Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R018` | `COCRDLIC.cbl` | online | `2000-RECEIVE-MAP` | `:L951`&ndash;`:L957` | PERFORM `2100-RECEIVE-SCREEN`, `2200-EDIT-INPUTS`; THRU `2100-RECEIVE-SCREEN-EXIT`, `2200-EDIT-INPUTS-EXIT` | &mdash; | `service/card/CardListService.java` | `private receiveMap2000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R019` | `COCRDLIC.cbl` | online | `2000-RECEIVE-MAP-EXIT` | `:L959`&ndash;`:L961` | &mdash; | &mdash; | `service/card/CardListService.java` | `private receiveMap2000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R020` | `COCRDLIC.cbl` | online | `2100-RECEIVE-SCREEN` | `:L962`&ndash;`:L979` | EXEC CICS RECEIVE | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardListService.java` | `private receiveScreen2100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R021` | `COCRDLIC.cbl` | online | `2100-RECEIVE-SCREEN-EXIT` | `:L981`&ndash;`:L983` | &mdash; | &mdash; | `service/card/CardListService.java` | `private receiveScreen2100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R022` | `COCRDLIC.cbl` | online | `2200-EDIT-INPUTS` | `:L985`&ndash;`:L997` | PERFORM `2210-EDIT-ACCOUNT`, `2220-EDIT-CARD`, `2250-EDIT-ARRAY`; THRU `2210-EDIT-ACCOUNT-EXIT`, `2220-EDIT-CARD-EXIT` | &mdash; | `service/card/CardListService.java` | `private editInputs2200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theAccountEditRunsBeforeTheCardEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R023` | `COCRDLIC.cbl` | online | `2200-EDIT-INPUTS-EXIT` | `:L999`&ndash;`:L1001` | &mdash; | &mdash; | `service/card/CardListService.java` | `private editInputs2200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R024` | `COCRDLIC.cbl` | online | `2210-EDIT-ACCOUNT` | `:L1003`&ndash;`:L1030` | GO TO `2210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardListService.java` | `private editAccount2210(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theAccountEditRunsBeforeTheCardEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R025` | `COCRDLIC.cbl` | online | `2210-EDIT-ACCOUNT-EXIT` | `:L1032`&ndash;`:L1034` | &mdash; | &mdash; | `service/card/CardListService.java` | `private editAccount2210Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R026` | `COCRDLIC.cbl` | online | `2220-EDIT-CARD` | `:L1036`&ndash;`:L1067` | GO TO `2220-EDIT-CARD-EXIT` | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `COCOM01Y.cpy` | `service/card/CardListService.java` | `private editCard2220(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theAccountEditRunsBeforeTheCardEdit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R027` | `COCRDLIC.cbl` | online | `2220-EDIT-CARD-EXIT` | `:L1069`&ndash;`:L1071` | &mdash; | &mdash; | `service/card/CardListService.java` | `private editCard2220Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R028` | `COCRDLIC.cbl` | online | `2250-EDIT-ARRAY` | `:L1073`&ndash;`:L1117` | GO TO `2250-EDIT-ARRAY-EXIT` | &mdash; | `service/card/CardListService.java` | `private editArray2250(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::twoSelectionsAreReportedInTheResultAndThePageStillReturns` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R029` | `COCRDLIC.cbl` | online | `2250-EDIT-ARRAY-EXIT` | `:L1119`&ndash;`:L1121` | &mdash; | &mdash; | `service/card/CardListService.java` | `private editArray2250Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R030` | `COCRDLIC.cbl` | online | `9000-READ-FORWARD` | `:L1123`&ndash;`:L1260` | PERFORM `9500-FILTER-RECORDS`; THRU `9500-FILTER-RECORDS-EXIT`; EXEC CICS ENDBR, READNEXT, STARTBR | `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardListService.java` | `private readForward9000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::anEmptyFileYieldsNoRowsAndAMessageRatherThanAnException` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R031` | `COCRDLIC.cbl` | online | `9000-READ-FORWARD-EXIT` | `:L1261`&ndash;`:L1263` | &mdash; | &mdash; | `service/card/CardListService.java` | `private readForward9000Exit(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R032` | `COCRDLIC.cbl` | online | `9100-READ-BACKWARDS` | `:L1264`&ndash;`:L1372` | PERFORM `9500-FILTER-RECORDS`; THRU `9500-FILTER-RECORDS-EXIT`; GO TO `9100-READ-BACKWARDS-EXIT`; EXEC CICS READPREV, STARTBR | `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardListService.java` | `private readBackwards9100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::pagingBackwardRetreatsFromTheFirstKeyOfTheCurrentPage` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R033` | `COCRDLIC.cbl` | online | `9100-READ-BACKWARDS-EXIT` | `:L1374`&ndash;`:L1380` | EXEC CICS ENDBR | &mdash; | `service/card/CardListService.java` | `private readBackwards9100Exit(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R034` | `COCRDLIC.cbl` | online | `9500-FILTER-RECORDS` | `:L1382`&ndash;`:L1407` | GO TO `9500-FILTER-RECORDS-EXIT` | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardListService.java` | `private filterRecords9500(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::nothingButAPageableEverReachesTheRepository` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R035` | `COCRDLIC.cbl` | online | `9500-FILTER-RECORDS-EXIT` | `:L1409`&ndash;`:L1417` | &mdash; | &mdash; | `service/card/CardListService.java` | `private filterRecords9500Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::aRecordFailingTheAccountGateIsExcludedBeforeTheCardGateIsReached` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R036` | `COCRDLIC.cbl` | online | `SEND-PLAIN-TEXT` | `:L1422`&ndash;`:L1432` | EXEC CICS RETURN, SEND | &mdash; | `service/card/CardListService.java` | `private sendPlainText()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R037` | `COCRDLIC.cbl` | online | `SEND-PLAIN-TEXT-EXIT` | `:L1433`&ndash;`:L1435` | &mdash; | &mdash; | `service/card/CardListService.java` | `private sendPlainTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R038` | `COCRDLIC.cbl` | online | `SEND-LONG-TEXT` | `:L1441`&ndash;`:L1451` | EXEC CICS RETURN, SEND | &mdash; | `service/card/CardListService.java` | `private sendLongText()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDLIC-R039` | `COCRDLIC.cbl` | online | `SEND-LONG-TEXT-EXIT` | `:L1452`&ndash;`:L1454` | &mdash; | &mdash; | `service/card/CardListService.java` | `private sendLongTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardListServiceTest.java::theBeanDeclaresOnePrivateMethodPerCobolParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="coactvwc-cbl"></a>

### 5.4 `COACTVWC.cbl`

**online &middot; F-003 Account view &middot; 941 physical lines &middot; 35 rows** (35 paragraphs)

Target files: `service/account/AccountViewService.java`, `controller/AccountController.java`.
Tests: `unit/service/AccountViewServiceTest.java`, `unit/model/AccountDtoTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COACTVWC-R001` | `COACTVWC.cbl` | online | `0000-MAIN` | `:L262`&ndash;`:L393` | PERFORM `1000-SEND-MAP`, `2000-PROCESS-INPUTS`, `9000-READ-ACCT`, `SEND-PLAIN-TEXT`; THRU `1000-SEND-MAP-EXIT`, `2000-PROCESS-INPUTS-EXIT`; GO TO `COMMON-RETURN`; EXEC CICS HANDLE, XCTL | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CSMSG02Y.cpy` | `service/account/AccountViewService.java` | `private mainLine0000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::bothDuplicatedMainExitLabelsMapToDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R002` | `COACTVWC.cbl` | online | `COMMON-RETURN` | `:L394`&ndash;`:L407` | EXEC CICS RETURN | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountViewService.java` | `private commonReturn(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R003` | `COACTVWC.cbl` | online | `0000-MAIN-EXIT` | `:L408`&ndash;`:L410` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private mainExit0000AtLine408()` | parity-preserved quirk | [`DL-LD-08`](DECISION_LOG.md#dl-ld-08) | `unit/service/AccountViewServiceTest.java::bothDuplicatedMainExitLabelsMapToDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R004` | `COACTVWC.cbl` | online | `0000-MAIN-EXIT` | `:L411`&ndash;`:L413` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private mainExit0000AtLine411()` | parity-preserved quirk | [`DL-LD-08`](DECISION_LOG.md#dl-ld-08) | `unit/service/AccountViewServiceTest.java::bothDuplicatedMainExitLabelsMapToDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R005` | `COACTVWC.cbl` | online | `1000-SEND-MAP` | `:L416`&ndash;`:L425` | PERFORM `1100-SCREEN-INIT`, `1200-SETUP-SCREEN-VARS`, `1300-SETUP-SCREEN-ATTRS`, `1400-SEND-SCREEN`; THRU `1100-SCREEN-INIT-EXIT`, `1200-SETUP-SCREEN-VARS-EXIT` | &mdash; | `service/account/AccountViewService.java` | `private sendMap1000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R006` | `COACTVWC.cbl` | online | `1000-SEND-MAP-EXIT` | `:L427`&ndash;`:L429` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private sendMap1000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R007` | `COACTVWC.cbl` | online | `1100-SCREEN-INIT` | `:L431`&ndash;`:L455` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/account/AccountViewService.java` | `private screenInit1100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R008` | `COACTVWC.cbl` | online | `1100-SCREEN-INIT-EXIT` | `:L457`&ndash;`:L459` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private screenInit1100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R009` | `COACTVWC.cbl` | online | `1200-SETUP-SCREEN-VARS` | `:L460`&ndash;`:L535` | &mdash; | `CVACT01Y.cpy`, `CVCUS01Y.cpy`, `CVCRD01Y.cpy` | `service/account/AccountViewService.java` | `private setupScreenVars1200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R010` | `COACTVWC.cbl` | online | `1200-SETUP-SCREEN-VARS-EXIT` | `:L537`&ndash;`:L539` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private setupScreenVars1200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R011` | `COACTVWC.cbl` | online | `1300-SETUP-SCREEN-ATTRS` | `:L541`&ndash;`:L572` | &mdash; | `COCOM01Y.cpy` | `service/account/AccountViewService.java` | `private setupScreenAttrs1300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R012` | `COACTVWC.cbl` | online | `1300-SETUP-SCREEN-ATTRS-EXIT` | `:L574`&ndash;`:L576` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private setupScreenAttrs1300Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R013` | `COACTVWC.cbl` | online | `1400-SEND-SCREEN` | `:L577`&ndash;`:L591` | EXEC CICS SEND | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountViewService.java` | `private sendScreen1400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R014` | `COACTVWC.cbl` | online | `1400-SEND-SCREEN-EXIT` | `:L592`&ndash;`:L594` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private sendScreen1400Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R015` | `COACTVWC.cbl` | online | `2000-PROCESS-INPUTS` | `:L596`&ndash;`:L605` | PERFORM `2100-RECEIVE-MAP`, `2200-EDIT-MAP-INPUTS`; THRU `2100-RECEIVE-MAP-EXIT`, `2200-EDIT-MAP-INPUTS-EXIT` | `CVCRD01Y.cpy` | `service/account/AccountViewService.java` | `private processInputs2000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R016` | `COACTVWC.cbl` | online | `2000-PROCESS-INPUTS-EXIT` | `:L607`&ndash;`:L609` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private processInputs2000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R017` | `COACTVWC.cbl` | online | `2100-RECEIVE-MAP` | `:L610`&ndash;`:L617` | EXEC CICS RECEIVE | &mdash; | `service/account/AccountViewService.java` | `private receiveMap2100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R018` | `COACTVWC.cbl` | online | `2100-RECEIVE-MAP-EXIT` | `:L619`&ndash;`:L621` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private receiveMap2100Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R019` | `COACTVWC.cbl` | online | `2200-EDIT-MAP-INPUTS` | `:L622`&ndash;`:L643` | PERFORM `2210-EDIT-ACCOUNT`; THRU `2210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy` | `service/account/AccountViewService.java` | `private editMapInputs2200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R020` | `COACTVWC.cbl` | online | `2200-EDIT-MAP-INPUTS-EXIT` | `:L645`&ndash;`:L647` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private editMapInputs2200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R021` | `COACTVWC.cbl` | online | `2210-EDIT-ACCOUNT` | `:L649`&ndash;`:L681` | GO TO `2210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/account/AccountViewService.java` | `private editAccount2210(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R022` | `COACTVWC.cbl` | online | `2210-EDIT-ACCOUNT-EXIT` | `:L683`&ndash;`:L685` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private editAccount2210Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R023` | `COACTVWC.cbl` | online | `9000-READ-ACCT` | `:L687`&ndash;`:L718` | PERFORM `9200-GETCARDXREF-BYACCT`, `9300-GETACCTDATA-BYACCT`, `9400-GETCUSTDATA-BYCUST`; THRU `9200-GETCARDXREF-BYACCT-EXIT`, `9300-GETACCTDATA-BYACCT-EXIT`; GO TO `9000-READ-ACCT-EXIT` | `COCOM01Y.cpy`, `CVACT01Y.cpy` | `service/account/AccountViewService.java` | `private readAcct9000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R024` | `COACTVWC.cbl` | online | `9000-READ-ACCT-EXIT` | `:L720`&ndash;`:L722` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private readAcct9000Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R025` | `COACTVWC.cbl` | online | `9200-GETCARDXREF-BYACCT` | `:L723`&ndash;`:L770` | EXEC CICS READ | `CVACT03Y.cpy`, `COCOM01Y.cpy`, `CVACT02Y.cpy` | `service/account/AccountViewService.java` | `private getCardXrefByAcct9200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R026` | `COACTVWC.cbl` | online | `9200-GETCARDXREF-BYACCT-EXIT` | `:L771`&ndash;`:L773` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private getCardXrefByAcct9200Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R027` | `COACTVWC.cbl` | online | `9300-GETACCTDATA-BYACCT` | `:L774`&ndash;`:L820` | EXEC CICS READ | `CVACT01Y.cpy` | `service/account/AccountViewService.java` | `private getAcctDataByAcct9300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R028` | `COACTVWC.cbl` | online | `9300-GETACCTDATA-BYACCT-EXIT` | `:L821`&ndash;`:L823` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private getAcctDataByAcct9300Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R029` | `COACTVWC.cbl` | online | `9400-GETCUSTDATA-BYCUST` | `:L825`&ndash;`:L869` | EXEC CICS READ | &mdash; | `service/account/AccountViewService.java` | `private getCustDataByCust9400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R030` | `COACTVWC.cbl` | online | `9400-GETCUSTDATA-BYCUST-EXIT` | `:L870`&ndash;`:L872` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private getCustDataByCust9400Exit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R031` | `COACTVWC.cbl` | online | `SEND-PLAIN-TEXT` | `:L877`&ndash;`:L887` | EXEC CICS RETURN, SEND | &mdash; | `service/account/AccountViewService.java` | `private sendPlainText(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R032` | `COACTVWC.cbl` | online | `SEND-PLAIN-TEXT-EXIT` | `:L888`&ndash;`:L890` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private sendPlainTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R033` | `COACTVWC.cbl` | online | `SEND-LONG-TEXT` | `:L896`&ndash;`:L906` | EXEC CICS RETURN, SEND | &mdash; | `service/account/AccountViewService.java` | `private sendLongText(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::theXrefReadErrorLiteralIsNeverEmitted` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R034` | `COACTVWC.cbl` | online | `SEND-LONG-TEXT-EXIT` | `:L907`&ndash;`:L914` | &mdash; | &mdash; | `service/account/AccountViewService.java` | `private sendLongTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COACTVWC-R035` | `COACTVWC.cbl` | online | `ABEND-ROUTINE` | `:L916`&ndash;`:L937` | EXEC CICS ABEND, HANDLE, SEND | `CSMSG02Y.cpy` | `service/account/AccountViewService.java` | `private abendRoutine(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AccountViewServiceTest.java::everySourceParagraphMapsToItsOwnPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COACTVWC.cbl`.**

- **`TM-COACTVWC-R003` &middot; `0000-MAIN-EXIT` &middot; `:L408`&ndash;`:L410` &middot; parity-preserved quirk &middot; [`DL-LD-08`](DECISION_LOG.md#dl-ld-08).** **DUPLICATE PARAGRAPH LABEL.** `0000-MAIN-EXIT` is defined TWICE, back to back and byte-identical (`EXIT` + `.`), at `:L408`-`:L410` and `:L411`-`:L413`, with ZERO references anywhere in the file. The sibling program `COCRDSLC.cbl` has the same `COMMON-RETURN`/`0000-MAIN-EXIT` layout at the same line numbers but only ONE copy, which identifies this as a copy-paste duplication artefact. Both occurrences keep their own row and their own line-discriminated Java member rather than being merged.
- **`TM-COACTVWC-R004` &middot; `0000-MAIN-EXIT` &middot; `:L411`&ndash;`:L413` &middot; parity-preserved quirk &middot; [`DL-LD-08`](DECISION_LOG.md#dl-ld-08).** **DUPLICATE PARAGRAPH LABEL.** `0000-MAIN-EXIT` is defined TWICE, back to back and byte-identical (`EXIT` + `.`), at `:L408`-`:L410` and `:L411`-`:L413`, with ZERO references anywhere in the file. The sibling program `COCRDSLC.cbl` has the same `COMMON-RETURN`/`0000-MAIN-EXIT` layout at the same line numbers but only ONE copy, which identifies this as a copy-paste duplication artefact. Both occurrences keep their own row and their own line-discriminated Java member rather than being merged.

<a id="cbstm03a-cbl"></a>

### 5.5 `CBSTM03A.CBL`

**batch &middot; F-019 Statement generation &middot; 924 physical lines &middot; 26 rows** (25 paragraphs + 1 synthetic entry row)

Target files: `batch/jobs/StatementGenerationJob.java`, `batch/processors/StatementProcessor.java`, `batch/writers/StatementWriter.java`.
Tests: `unit/batch/StatementProcessorTest.java`, `integration/batch/StatementGenerationJobTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBSTM03A-R001` | `CBSTM03A.CBL` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L262`&ndash;`:L294` | &mdash; | &mdash; | `batch/writers/StatementWriter.java` | `public openStatementOutputs(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/StatementProcessorTest.java::noControlBlockPeekingAnalogueExists` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R002` | `CBSTM03A.CBL` | batch | `0000-START` | `:L296`&ndash;`:L314` | GO TO `8100-FILE-OPEN`, `8500-READTRNX-READ`, `9999-GOBACK`; ALTER `8100-FILE-OPEN`&rarr;`8100-TRNXFILE-OPEN`, `8100-FILE-OPEN`&rarr;`8200-XREFFILE-OPEN`, `8100-FILE-OPEN`&rarr;`8300-CUSTFILE-OPEN`, `8100-FILE-OPEN`&rarr;`8400-ACCTFILE-OPEN` | &mdash; | `batch/jobs/StatementGenerationJob.java` | `private initialiseStatementRun()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theProcessorHasNoDispatchTable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R003` | `CBSTM03A.CBL` | batch | `1000-MAINLINE` | `:L316`&ndash;`:L339` | PERFORM `1000-XREFFILE-GET-NEXT`, `2000-CUSTFILE-GET`, `3000-ACCTFILE-GET`, `4000-TRNXFILE-GET` +5 | &mdash; | `batch/jobs/StatementGenerationJob.java` | `public statementGenerationEmitStep()` | parity-preserved quirk | [`DL-LD-07`](DECISION_LOG.md#dl-ld-07) | `unit/batch/StatementProcessorTest.java::theProcessorHasNoDispatchTable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R004` | `CBSTM03A.CBL` | batch | `9999-GOBACK` | `:L341`&ndash;`:L342` | &mdash; | &mdash; | `batch/jobs/StatementGenerationJob.java` | `private emitStepGoback(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theSubprogramItselfNeverAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R005` | `CBSTM03A.CBL` | batch | `1000-XREFFILE-GET-NEXT` | `:L345`&ndash;`:L366` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | `CVACT03Y.cpy` | `batch/jobs/StatementGenerationJob.java` | `private crossReferenceReader()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theCrossReferenceReadIsSequential` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R006` | `CBSTM03A.CBL` | batch | `2000-CUSTFILE-GET` | `:L368`&ndash;`:L390` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | `CVACT03Y.cpy` | `batch/processors/StatementProcessor.java` | `private readCustomerRecord(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theKeyedReadsPassTheirComputedKeyLengths` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R007` | `CBSTM03A.CBL` | batch | `3000-ACCTFILE-GET` | `:L392`&ndash;`:L414` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | `CVACT01Y.cpy`, `CVACT03Y.cpy` | `batch/processors/StatementProcessor.java` | `private readAccountRecord(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theKeyedReadsPassTheirComputedKeyLengths` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R008` | `CBSTM03A.CBL` | batch | `4000-TRNXFILE-GET` | `:L416`&ndash;`:L456` | PERFORM `6000-WRITE-TRANS` | `COSTM01.CPY`, `CVACT03Y.cpy`, `CVACT02Y.cpy` | `batch/processors/StatementProcessor.java` | `private advanceToCardGroup(&hellip;)` | parity-preserved quirk | [`DL-PP-06`](DECISION_LOG.md#dl-pp-06) | `unit/batch/StatementProcessorTest.java::theRedundantSubscriptResetIsHarmlessButTheTotalResetIsNot` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R009` | `CBSTM03A.CBL` | batch | `5000-CREATE-STATEMENT` | `:L458`&ndash;`:L504` | PERFORM `5100-WRITE-HTML-HEADER`, `5200-WRITE-HTML-NMADBS`; THRU `5100-EXIT`, `5200-EXIT` | `CVACT01Y.cpy` | `batch/processors/StatementProcessor.java` | `private createStatement(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::everyGuardedOpenAcceptsTheSecondaryStatus` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R010` | `CBSTM03A.CBL` | batch | `5100-WRITE-HTML-HEADER` | `:L506`&ndash;`:L552` | &mdash; | `CVACT01Y.cpy` | `batch/processors/StatementProcessor.java` | `private writeHtmlHeader(&hellip;)` | mechanism substitution | [`DL-MS-05`](DECISION_LOG.md#dl-ms-05) | `unit/batch/StatementProcessorTest.java::theDocumentOpensWithTheDeclarationThenTheRoot` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R011` | `CBSTM03A.CBL` | batch | `5100-EXIT` | `:L554`&ndash;`:L555` | &mdash; | &mdash; | `batch/processors/StatementProcessor.java` | `private writeHtmlHeader(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theFragmentTableMatchesTheDeclaration` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R012` | `CBSTM03A.CBL` | batch | `5200-WRITE-HTML-NMADBS` | `:L558`&ndash;`:L669` | &mdash; | `CVACT01Y.cpy` | `batch/processors/StatementProcessor.java` | `private writeHtmlNameAddressBasics(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theHtmlCarriesAParagraphPerField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R013` | `CBSTM03A.CBL` | batch | `5200-EXIT` | `:L671`&ndash;`:L672` | &mdash; | &mdash; | `batch/processors/StatementProcessor.java` | `private writeHtmlNameAddressBasics(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theHtmlCarriesAParagraphPerField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R014` | `CBSTM03A.CBL` | batch | `6000-WRITE-TRANS` | `:L675`&ndash;`:L723` | &mdash; | `COSTM01.CPY` | `batch/processors/StatementProcessor.java` | `private writeTransaction(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theStatementPathCarriesNoAbendCode` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R015` | `CBSTM03A.CBL` | batch | `8100-FILE-OPEN` | `:L726`&ndash;`:L728` | GO TO `8100-TRNXFILE-OPEN` | &mdash; | `batch/jobs/StatementGenerationJob.java` | `private initialiseStatementRun()` | deviation | [`DL-DV-02`](DECISION_LOG.md#dl-dv-02) | `unit/batch/StatementProcessorTest.java::initialiseIsIdempotent` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R016` | `CBSTM03A.CBL` | batch | `8100-TRNXFILE-OPEN` | `:L730`&ndash;`:L762` | PERFORM `9999-ABEND-PROGRAM`; GO TO `0000-START`; CALL `CBSTM03B` | `COSTM01.CPY`, `CVACT02Y.cpy` | `batch/processors/StatementProcessor.java` | `private openAndPrimeTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::allFourDatasetsAreOpenedInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R017` | `CBSTM03A.CBL` | batch | `8200-XREFFILE-OPEN` | `:L765`&ndash;`:L781` | PERFORM `9999-ABEND-PROGRAM`; GO TO `0000-START`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private openCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::allFourDatasetsAreOpenedInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R018` | `CBSTM03A.CBL` | batch | `8300-CUSTFILE-OPEN` | `:L783`&ndash;`:L799` | PERFORM `9999-ABEND-PROGRAM`; GO TO `0000-START`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private openCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::allFourDatasetsAreOpenedInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R019` | `CBSTM03A.CBL` | batch | `8400-ACCTFILE-OPEN` | `:L801`&ndash;`:L816` | PERFORM `9999-ABEND-PROGRAM`; GO TO `1000-MAINLINE`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private openAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::allFourDatasetsAreOpenedInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R020` | `CBSTM03A.CBL` | batch | `8500-READTRNX-READ` | `:L818`&ndash;`:L847` | PERFORM `9999-ABEND-PROGRAM`; GO TO `8500-READTRNX-READ`, `8599-EXIT`; CALL `CBSTM03B` | `COSTM01.CPY`, `CVACT02Y.cpy` | `batch/processors/StatementProcessor.java` | `private readNextCardGroup()` | deviation | [`DL-DV-03`](DECISION_LOG.md#dl-dv-03) | `unit/batch/StatementProcessorTest.java::theCrossReferenceReadRejectsTheSecondaryStatus` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R021` | `CBSTM03A.CBL` | batch | `8599-EXIT` | `:L849`&ndash;`:L853` | GO TO `0000-START` | &mdash; | `batch/processors/StatementProcessor.java` | `private initialiseTransactionStream()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::endOfFileIsNeverThrown` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R022` | `CBSTM03A.CBL` | batch | `9100-TRNXFILE-CLOSE` | `:L856`&ndash;`:L870` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private closeTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::closeClosesAllFourInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R023` | `CBSTM03A.CBL` | batch | `9200-XREFFILE-CLOSE` | `:L873`&ndash;`:L887` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private closeCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::closeClosesAllFourInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R024` | `CBSTM03A.CBL` | batch | `9300-CUSTFILE-CLOSE` | `:L889`&ndash;`:L903` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private closeCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::closeClosesAllFourInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R025` | `CBSTM03A.CBL` | batch | `9400-ACCTFILE-CLOSE` | `:L905`&ndash;`:L919` | PERFORM `9999-ABEND-PROGRAM`; CALL `CBSTM03B` | &mdash; | `batch/processors/StatementProcessor.java` | `private closeAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::closeClosesAllFourInOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03A-R026` | `CBSTM03A.CBL` | batch | `9999-ABEND-PROGRAM` | `:L921`&ndash;`:L923` | CALL `CEE3ABD` | &mdash; | `batch/processors/StatementProcessor.java` | `private abend(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/StatementProcessorTest.java::theStatementPathCarriesNoAbendCode` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CBSTM03A.CBL`.**

- **`TM-CBSTM03A-R003` &middot; `1000-MAINLINE` &middot; `:L316`&ndash;`:L339` &middot; parity-preserved quirk &middot; [`DL-LD-07`](DECISION_LOG.md#dl-ld-07).** The mainline sets the outer index before a varying loop that re-initialises it anyway (`:L324`). The REDUNDANT INDEX ASSIGNMENT is preserved verbatim for control-flow fidelity and is one of exactly three tracked retained no-ops.
- **`TM-CBSTM03A-R008` &middot; `4000-TRNXFILE-GET` &middot; `:L416`&ndash;`:L456` &middot; parity-preserved quirk &middot; [`DL-PP-06`](DECISION_LOG.md#dl-pp-06).** A linear scan whose EARLY EXIT is correct only because the table is ascending by card number, which the upstream sort guarantees. The `CREASTMT.JCL:L79` `OUTREC` projection emits a 16-byte card number, 262 bytes of the record head and 50 bytes from offset 279 - **truncating two bytes of the 26-byte processing timestamp and dropping the 20-byte trailing filler**. The truncation is reproduced exactly, not corrected.
- **`TM-CBSTM03A-R010` &middot; `5100-WRITE-HTML-HEADER` &middot; `:L506`&ndash;`:L552` &middot; mechanism substitution &middot; [`DL-MS-05`](DECISION_LOG.md#dl-ms-05).** Markup is emitted from dozens of condition names over a single 100-character field; the pattern is set-condition-then-write. The 100-character field width independently confirms `LRECL=100` at `app/jcl/CREASTMT.JCL:L94`, which is why the 80-versus-100 mismatch against `:L89` is a legacy defect to log (`DL-LD-02`) and not a signal to change the width.
- **`TM-CBSTM03A-R015` &middot; `8100-FILE-OPEN` &middot; `:L726`&ndash;`:L728` &middot; deviation &middot; [`DL-DV-02`](DECISION_LOG.md#dl-dv-02).** SELF-MODIFYING CODE. The body is exactly `GO TO 8100-TRNXFILE-OPEN`, rewritten by four `ALTER … TO PROCEED TO` statements at `:L300`, `:L303`, `:L306`, `:L309`. The state transitions are hard-coded in each handler's tail, so the machine is DETERMINISTIC and collapses to an ordered five-stage initialisation followed by the mainline. It is NOT a runtime dispatch table; the genuinely varying file-and-operation map lives in `FileService` - see `DL-DV-04`.
- **`TM-CBSTM03A-R020` &middot; `8500-READTRNX-READ` &middot; `:L818`&ndash;`:L847` &middot; deviation &middot; [`DL-DV-03`](DECISION_LOG.md#dl-dv-03).** Self-recursive loop building an in-memory table declared as 51 card entries of 10 transactions each - a HARD CEILING OF 510 transactions per run - and it increments both indices with NO bounds check. Java uses unbounded streaming, which removes a silent truncation and storage-overrun hazard. That is a labelled DEVIATION, not parity; **the historical capacity limit of 510 is recorded here as required by `DL-RR-07`**.

<a id="cocrdslc-cbl"></a>

### 5.6 `COCRDSLC.cbl`

**online &middot; F-006 Card detail &middot; 887 physical lines &middot; 34 rows** (34 paragraphs)

Target files: `service/card/CardDetailService.java`, `controller/CardController.java`.
Tests: `unit/service/CardDetailServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COCRDSLC-R001` | `COCRDSLC.cbl` | online | `0000-MAIN` | `:L248`&ndash;`:L392` | PERFORM `1000-SEND-MAP`, `2000-PROCESS-INPUTS`, `9000-READ-DATA`, `SEND-PLAIN-TEXT`; THRU `1000-SEND-MAP-EXIT`, `2000-PROCESS-INPUTS-EXIT`; GO TO `COMMON-RETURN`; EXEC CICS HANDLE, XCTL | `COCOM01Y.cpy`, `CVCRD01Y.cpy`, `CSMSG02Y.cpy` | `service/card/CardDetailService.java` | `private main0000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R002` | `COCRDSLC.cbl` | online | `COMMON-RETURN` | `:L394`&ndash;`:L407` | EXEC CICS RETURN | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private commonReturn(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R003` | `COCRDSLC.cbl` | online | `0000-MAIN-EXIT` | `:L408`&ndash;`:L410` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private mainExit0000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R004` | `COCRDSLC.cbl` | online | `1000-SEND-MAP` | `:L412`&ndash;`:L421` | PERFORM `1100-SCREEN-INIT`, `1200-SETUP-SCREEN-VARS`, `1300-SETUP-SCREEN-ATTRS`, `1400-SEND-SCREEN`; THRU `1100-SCREEN-INIT-EXIT`, `1200-SETUP-SCREEN-VARS-EXIT` | &mdash; | `service/card/CardDetailService.java` | `private sendMap1000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R005` | `COCRDSLC.cbl` | online | `1000-SEND-MAP-EXIT` | `:L423`&ndash;`:L425` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private sendMapExit1000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R006` | `COCRDSLC.cbl` | online | `1100-SCREEN-INIT` | `:L427`&ndash;`:L451` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/card/CardDetailService.java` | `private screenInit1100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R007` | `COCRDSLC.cbl` | online | `1100-SCREEN-INIT-EXIT` | `:L453`&ndash;`:L455` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private screenInitExit1100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R008` | `COCRDSLC.cbl` | online | `1200-SETUP-SCREEN-VARS` | `:L457`&ndash;`:L497` | &mdash; | `CVACT02Y.cpy`, `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private setupScreenVars1200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R009` | `COCRDSLC.cbl` | online | `1200-SETUP-SCREEN-VARS-EXIT` | `:L499`&ndash;`:L501` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private setupScreenVarsExit1200()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R010` | `COCRDSLC.cbl` | online | `1300-SETUP-SCREEN-ATTRS` | `:L502`&ndash;`:L558` | &mdash; | `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private setupScreenAttrs1300(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R011` | `COCRDSLC.cbl` | online | `1300-SETUP-SCREEN-ATTRS-EXIT` | `:L559`&ndash;`:L560` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private setupScreenAttrsExit1300()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R012` | `COCRDSLC.cbl` | online | `1400-SEND-SCREEN` | `:L563`&ndash;`:L577` | EXEC CICS SEND | `CVCRD01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private sendScreen1400(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R013` | `COCRDSLC.cbl` | online | `1400-SEND-SCREEN-EXIT` | `:L578`&ndash;`:L580` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private sendScreenExit1400()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R014` | `COCRDSLC.cbl` | online | `2000-PROCESS-INPUTS` | `:L582`&ndash;`:L591` | PERFORM `2100-RECEIVE-MAP`, `2200-EDIT-MAP-INPUTS`; THRU `2100-RECEIVE-MAP-EXIT`, `2200-EDIT-MAP-INPUTS-EXIT` | `CVCRD01Y.cpy` | `service/card/CardDetailService.java` | `private processInputs2000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R015` | `COCRDSLC.cbl` | online | `2000-PROCESS-INPUTS-EXIT` | `:L593`&ndash;`:L595` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private processInputsExit2000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R016` | `COCRDSLC.cbl` | online | `2100-RECEIVE-MAP` | `:L596`&ndash;`:L603` | EXEC CICS RECEIVE | &mdash; | `service/card/CardDetailService.java` | `private receiveMap2100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R017` | `COCRDSLC.cbl` | online | `2100-RECEIVE-MAP-EXIT` | `:L605`&ndash;`:L607` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private receiveMapExit2100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R018` | `COCRDSLC.cbl` | online | `2200-EDIT-MAP-INPUTS` | `:L608`&ndash;`:L641` | PERFORM `2210-EDIT-ACCOUNT`, `2220-EDIT-CARD`; THRU `2210-EDIT-ACCOUNT-EXIT`, `2220-EDIT-CARD-EXIT` | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardDetailService.java` | `private editMapInputs2200(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R019` | `COCRDSLC.cbl` | online | `2200-EDIT-MAP-INPUTS-EXIT` | `:L643`&ndash;`:L645` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private editMapInputsExit2200()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R020` | `COCRDSLC.cbl` | online | `2210-EDIT-ACCOUNT` | `:L647`&ndash;`:L679` | GO TO `2210-EDIT-ACCOUNT-EXIT` | `CVCRD01Y.cpy`, `CVACT01Y.cpy`, `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private editAccount2210(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R021` | `COCRDSLC.cbl` | online | `2210-EDIT-ACCOUNT-EXIT` | `:L681`&ndash;`:L683` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private editAccountExit2210()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R022` | `COCRDSLC.cbl` | online | `2220-EDIT-CARD` | `:L685`&ndash;`:L720` | GO TO `2220-EDIT-CARD-EXIT` | `CVCRD01Y.cpy`, `CVACT02Y.cpy`, `COCOM01Y.cpy` | `service/card/CardDetailService.java` | `private editCard2220(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R023` | `COCRDSLC.cbl` | online | `2220-EDIT-CARD-EXIT` | `:L722`&ndash;`:L724` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private editCardExit2220()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R024` | `COCRDSLC.cbl` | online | `9000-READ-DATA` | `:L726`&ndash;`:L730` | PERFORM `9100-GETCARD-BYACCTCARD`; THRU `9100-GETCARD-BYACCTCARD-EXIT` | &mdash; | `service/card/CardDetailService.java` | `private readData9000(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::readDataRangePerformsOnlyTheCardNumberRange` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R025` | `COCRDSLC.cbl` | online | `9000-READ-DATA-EXIT` | `:L732`&ndash;`:L734` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private readDataExit9000()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R026` | `COCRDSLC.cbl` | online | `9100-GETCARD-BYACCTCARD` | `:L736`&ndash;`:L773` | EXEC CICS READ | `CVACT02Y.cpy`, `CVCRD01Y.cpy` | `service/card/CardDetailService.java` | `private getCardByAcctCard9100(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::readDataRangePerformsOnlyTheCardNumberRange` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R027` | `COCRDSLC.cbl` | online | `9100-GETCARD-BYACCTCARD-EXIT` | `:L775`&ndash;`:L777` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private getCardByAcctCardExit9100()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::readDataRangePerformsOnlyTheCardNumberRange` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R028` | `COCRDSLC.cbl` | online | `9150-GETCARD-BYACCT` | `:L779`&ndash;`:L809` | EXEC CICS READ | `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/card/CardDetailService.java` | `private getCardByAcct9150()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::readDataRangePerformsOnlyTheCardNumberRange` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R029` | `COCRDSLC.cbl` | online | `9150-GETCARD-BYACCT-EXIT` | `:L810`&ndash;`:L812` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private getCardByAcctExit9150()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::readDataRangePerformsOnlyTheCardNumberRange` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R030` | `COCRDSLC.cbl` | online | `SEND-LONG-TEXT` | `:L820`&ndash;`:L830` | EXEC CICS RETURN, SEND | &mdash; | `service/card/CardDetailService.java` | `private sendLongText()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R031` | `COCRDSLC.cbl` | online | `SEND-LONG-TEXT-EXIT` | `:L831`&ndash;`:L833` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private sendLongTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R032` | `COCRDSLC.cbl` | online | `SEND-PLAIN-TEXT` | `:L838`&ndash;`:L848` | EXEC CICS RETURN, SEND | &mdash; | `service/card/CardDetailService.java` | `private sendPlainText(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R033` | `COCRDSLC.cbl` | online | `SEND-PLAIN-TEXT-EXIT` | `:L849`&ndash;`:L856` | &mdash; | &mdash; | `service/card/CardDetailService.java` | `private sendPlainTextExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::allThirtySixParagraphMethodsAreDeclaredAndPrivate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COCRDSLC-R034` | `COCRDSLC.cbl` | online | `ABEND-ROUTINE` | `:L857`&ndash;`:L878` | EXEC CICS ABEND, HANDLE, SEND | `CSMSG02Y.cpy` | `service/card/CardDetailService.java` | `private abendRoutine(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/CardDetailServiceTest.java::theRetainedSubstitutionCarriesItsMarker` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cotrn02c-cbl"></a>

### 5.7 `COTRN02C.cbl`

**online &middot; F-010 Transaction add &middot; 783 physical lines &middot; 18 rows** (18 paragraphs)

Target files: `service/transaction/TransactionAddService.java`, `controller/TransactionController.java`.
Tests: `unit/service/TransactionAddServiceTest.java`, `unit/model/TransactionAddRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COTRN02C-R001` | `COTRN02C.cbl` | online | `MAIN-PARA` | `:L107`&ndash;`:L159` | PERFORM `CLEAR-CURRENT-SCREEN`, `COPY-LAST-TRAN-DATA`, `PROCESS-ENTER-KEY`, `RECEIVE-TRNADD-SCREEN` +2; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/transaction/TransactionAddService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R002` | `COTRN02C.cbl` | online | `PROCESS-ENTER-KEY` | `:L164`&ndash;`:L188` | PERFORM `ADD-TRANSACTION`, `SEND-TRNADD-SCREEN`, `VALIDATE-INPUT-DATA-FIELDS`, `VALIDATE-INPUT-KEY-FIELDS` | &mdash; | `service/transaction/TransactionAddService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theTruncationIsDestructive` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R003` | `COTRN02C.cbl` | online | `VALIDATE-INPUT-KEY-FIELDS` | `:L193`&ndash;`:L230` | PERFORM `READ-CCXREF-FILE`, `READ-CXACAIX-FILE`, `SEND-TRNADD-SCREEN` | `CVACT03Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `service/transaction/TransactionAddService.java` | `private validateInputKeyFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R004` | `COTRN02C.cbl` | online | `VALIDATE-INPUT-DATA-FIELDS` | `:L235`&ndash;`:L437` | PERFORM `SEND-TRNADD-SCREEN`; CALL `CSUTLDTC` | `CSUTLDWY.cpy`, `CVTRA05Y.cpy` | `service/transaction/TransactionAddService.java` | `private validateInputDataFields(&hellip;)` | parity-preserved quirk | [`DL-PP-11`](DECISION_LOG.md#dl-pp-11) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R005` | `COTRN02C.cbl` | online | `ADD-TRANSACTION` | `:L442`&ndash;`:L466` | PERFORM `ENDBR-TRANSACT-FILE`, `READPREV-TRANSACT-FILE`, `STARTBR-TRANSACT-FILE`, `WRITE-TRANSACT-FILE` | `CVTRA05Y.cpy`, `CVTRA03Y.cpy`, `CVACT02Y.cpy` | `service/transaction/TransactionAddService.java` | `public addTransaction(&hellip;)` | parity-preserved quirk | [`DL-PP-04`](DECISION_LOG.md#dl-pp-04) | `unit/service/TransactionAddServiceTest.java::theAmountIsParsedASecondTimeFromTheScreenField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R006` | `COTRN02C.cbl` | online | `COPY-LAST-TRAN-DATA` | `:L471`&ndash;`:L495` | PERFORM `ENDBR-TRANSACT-FILE`, `PROCESS-ENTER-KEY`, `READPREV-TRANSACT-FILE`, `STARTBR-TRANSACT-FILE` +1 | `CVTRA05Y.cpy`, `CVTRA03Y.cpy` | `service/transaction/TransactionAddService.java` | `private copyLastTranData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::thePrefilledDatesReachTheRecordAsScreenText` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R007` | `COTRN02C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L500`&ndash;`:L511` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/transaction/TransactionAddService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::pf3RecordsThisProgramAsTheOrigin` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R008` | `COTRN02C.cbl` | online | `SEND-TRNADD-SCREEN` | `:L516`&ndash;`:L534` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS RETURN, SEND | `COCOM01Y.cpy` | `service/transaction/TransactionAddService.java` | `private sendTrnaddScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::openWithoutCommAreaTransfersToTheSignOnProgram` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R009` | `COTRN02C.cbl` | online | `RECEIVE-TRNADD-SCREEN` | `:L539`&ndash;`:L547` | EXEC CICS RECEIVE | &mdash; | `service/transaction/TransactionAddService.java` | `private receiveTrnaddScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::aNullRequestIsAnAllBlankMap` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R010` | `COTRN02C.cbl` | online | `POPULATE-HEADER-INFO` | `:L552`&ndash;`:L571` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/transaction/TransactionAddService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theHeaderIsEchoedFromTheRequestRatherThanFromAClock` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R011` | `COTRN02C.cbl` | online | `READ-CXACAIX-FILE` | `:L576`&ndash;`:L604` | PERFORM `SEND-TRNADD-SCREEN`; EXEC CICS READ | `CVACT03Y.cpy`, `CVACT01Y.cpy` | `service/transaction/TransactionAddService.java` | `private readCxacaixFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theAccountBranchStoresTheRefreshedScreenField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R012` | `COTRN02C.cbl` | online | `READ-CCXREF-FILE` | `:L609`&ndash;`:L637` | PERFORM `SEND-TRNADD-SCREEN`; EXEC CICS READ | `CVACT03Y.cpy`, `CVACT02Y.cpy` | `service/transaction/TransactionAddService.java` | `private readCcxrefFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R013` | `COTRN02C.cbl` | online | `STARTBR-TRANSACT-FILE` | `:L642`&ndash;`:L668` | PERFORM `SEND-TRNADD-SCREEN`; EXEC CICS STARTBR | `CVTRA05Y.cpy` | `service/transaction/TransactionAddService.java` | `private startbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R014` | `COTRN02C.cbl` | online | `READPREV-TRANSACT-FILE` | `:L673`&ndash;`:L697` | PERFORM `SEND-TRNADD-SCREEN`; EXEC CICS READPREV | `CVTRA05Y.cpy` | `service/transaction/TransactionAddService.java` | `private readprevTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::pf5AbandonsThePrefillWhenTheBrowseFails` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R015` | `COTRN02C.cbl` | online | `ENDBR-TRANSACT-FILE` | `:L702`&ndash;`:L706` | EXEC CICS ENDBR | &mdash; | `service/transaction/TransactionAddService.java` | `private endbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theBeanDeclaresOneMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R016` | `COTRN02C.cbl` | online | `WRITE-TRANSACT-FILE` | `:L711`&ndash;`:L749` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-TRNADD-SCREEN`; EXEC CICS WRITE | `CVTRA05Y.cpy` | `service/transaction/TransactionAddService.java` | `private writeTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::aReferentialRefusalIsNotReportedAsADuplicate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R017` | `COTRN02C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L754`&ndash;`:L757` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-TRNADD-SCREEN` | &mdash; | `service/transaction/TransactionAddService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::pf4BlanksEveryFieldAndResendsTheMap` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN02C-R018` | `COTRN02C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L762`&ndash;`:L779` | &mdash; | &mdash; | `service/transaction/TransactionAddService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionAddServiceTest.java::theTotalTransactionAmountIsObservableAfterTheWrite` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COTRN02C.cbl`.**

- **`TM-COTRN02C-R004` &middot; `VALIDATE-INPUT-DATA-FIELDS` &middot; `:L235`&ndash;`:L437` &middot; parity-preserved quirk &middot; [`DL-PP-11`](DECISION_LOG.md#dl-pp-11).** TWO numeric intrinsics are used deliberately: the plain conversion for the account identifier and card number, and the CURRENCY-AWARE conversion for the amount. One parser for both would accept input the source rejects, or reject input it accepts. The display round-trip uses an edited mask with a mandatory sign, exactly eight integer digits and two decimals.
- **`TM-COTRN02C-R005` &middot; `ADD-TRANSACTION` &middot; `:L442`&ndash;`:L466` &middot; parity-preserved quirk &middot; [`DL-PP-04`](DECISION_LOG.md#dl-pp-04).** Identifier generation by descending browse of the maximum key plus one; end of file yields a first identifier of 1. The algorithm is inherently racy exactly as the browse was; the race is RETAINED and a collision surfaces as a duplicate-record exception. No database sequence, because that would change generated values.

<a id="cbtrn02c-cbl"></a>

### 5.8 `CBTRN02C.cbl`

**batch &middot; F-016 Daily transaction posting &middot; 731 physical lines &middot; 27 rows** (26 paragraphs + 1 synthetic entry row)

Target files: `batch/jobs/DailyTransactionPostingJob.java`, `batch/processors/TransactionPostingProcessor.java`, `batch/readers/DailyTransactionReader.java`, `batch/writers/TransactionWriter.java`, `batch/writers/RejectWriter.java`.
Tests: `unit/batch/TransactionPostingProcessorTest.java`, `e2e/BatchPipelineE2ETest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBTRN02C-R001` | `CBTRN02C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L193`&ndash;`:L234` | PERFORM `0000-DALYTRAN-OPEN`, `0100-TRANFILE-OPEN`, `0200-XREFFILE-OPEN`, `0300-DALYREJS-OPEN` +12 | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `public dailyTransactionPostingStep(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/TransactionPostingProcessorTest.java::anAbsentRecordAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R002` | `CBTRN02C.cbl` | batch | `0000-DALYTRAN-OPEN` | `:L236`&ndash;`:L252` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/DailyTransactionReader.java` | `private openDailyTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R003` | `CBTRN02C.cbl` | batch | `0100-TRANFILE-OPEN` | `:L254`&ndash;`:L270` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R004` | `CBTRN02C.cbl` | batch | `0200-XREFFILE-OPEN` | `:L273`&ndash;`:L289` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R005` | `CBTRN02C.cbl` | batch | `0300-DALYREJS-OPEN` | `:L291`&ndash;`:L307` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/writers/RejectWriter.java` | `private openGenerationStream()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R006` | `CBTRN02C.cbl` | batch | `0400-ACCTFILE-OPEN` | `:L309`&ndash;`:L325` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R007` | `CBTRN02C.cbl` | batch | `0500-TCATBALF-OPEN` | `:L327`&ndash;`:L343` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openCategoryBalanceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R008` | `CBTRN02C.cbl` | batch | `1000-DALYTRAN-GET-NEXT` | `:L345`&ndash;`:L369` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/readers/DailyTransactionReader.java` | `private getNextDailyTransaction()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::successAndEndOfFileAreNeverThrown` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R009` | `CBTRN02C.cbl` | batch | `1500-VALIDATE-TRAN` | `:L370`&ndash;`:L378` | PERFORM `1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT` | &mdash; | `batch/processors/TransactionPostingProcessor.java` | `private validateTran(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::theCascadeStopsAtTwoLookups` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R010` | `CBTRN02C.cbl` | batch | `1500-A-LOOKUP-XREF` | `:L380`&ndash;`:L392` | &mdash; | `CVACT03Y.cpy`, `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private lookupXref(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::theCascadeStopsAtTwoLookups` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R011` | `CBTRN02C.cbl` | batch | `1500-B-LOOKUP-ACCT` | `:L393`&ndash;`:L422` | &mdash; | `CVACT01Y.cpy`, `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private lookupAcct(&hellip;)` | parity-preserved quirk | [`DL-LD-04`](DECISION_LOG.md#dl-ld-04) | `unit/batch/TransactionPostingProcessorTest.java::theCascadeStopsAtTwoLookups` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R012` | `CBTRN02C.cbl` | batch | `2000-POST-TRANSACTION` | `:L424`&ndash;`:L444` | PERFORM `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, `2900-WRITE-TRANSACTION-FILE`, `Z-GET-DB2-FORMAT-TIMESTAMP` | `CVTRA06Y.cpy`, `CVTRA05Y.cpy`, `CVTRA03Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private postTransaction(&hellip;)` | deviation | [`DL-DV-01`](DECISION_LOG.md#dl-dv-01) | `unit/batch/TransactionPostingProcessorTest.java::theCascadeStopsAtTwoLookups` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R013` | `CBTRN02C.cbl` | batch | `2500-WRITE-REJECT-REC` | `:L446`&ndash;`:L465` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/writers/RejectWriter.java` | `public writeReject(&hellip;)` | direct | [`DL-MS-10`](DECISION_LOG.md#dl-ms-10) | `unit/batch/RejectWriterTest.java::aNullStepExecutionIsTolerated` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R014` | `CBTRN02C.cbl` | batch | `2700-UPDATE-TCATBAL` | `:L467`&ndash;`:L501` | PERFORM `2700-A-CREATE-TCATBAL-REC`, `2700-B-UPDATE-TCATBAL-REC`, `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA01Y.cpy`, `CVTRA06Y.cpy`, `CVTRA03Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private updateTcatbal(&hellip;)` | parity-preserved quirk | [`DL-MS-11`](DECISION_LOG.md#dl-ms-11) | `unit/batch/TransactionPostingProcessorTest.java::aBlankTypeCodeCannotFormTheKey` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R015` | `CBTRN02C.cbl` | batch | `2700-A-CREATE-TCATBAL-REC` | `:L503`&ndash;`:L524` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA01Y.cpy`, `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private createTcatbalRec(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::theBalanceGuardsKeepTheSourcesOwnAttribution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R016` | `CBTRN02C.cbl` | batch | `2700-B-UPDATE-TCATBAL-REC` | `:L526`&ndash;`:L542` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA01Y.cpy`, `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private updateTcatbalRec(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::theBalanceGuardsKeepTheSourcesOwnAttribution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R017` | `CBTRN02C.cbl` | batch | `2800-UPDATE-ACCOUNT-REC` | `:L545`&ndash;`:L560` | &mdash; | `CVACT01Y.cpy`, `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionPostingProcessor.java` | `private updateAccountRec(&hellip;)` | parity-preserved quirk | [`DL-PP-10`](DECISION_LOG.md#dl-pp-10) | `unit/batch/TransactionPostingProcessorTest.java::anAbsentCurrentBalanceFails` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R018` | `CBTRN02C.cbl` | batch | `2900-WRITE-TRANSACTION-FILE` | `:L562`&ndash;`:L579` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA05Y.cpy` | `batch/writers/TransactionWriter.java` | `private writeTransactionFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionPostingProcessorTest.java::theAccountRewriteAbendDoesNotBorrowTheGuardsAuthority` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R019` | `CBTRN02C.cbl` | batch | `9000-DALYTRAN-CLOSE` | `:L582`&ndash;`:L598` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/DailyTransactionReader.java` | `private closeDailyTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R020` | `CBTRN02C.cbl` | batch | `9100-TRANFILE-CLOSE` | `:L600`&ndash;`:L616` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closeTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R021` | `CBTRN02C.cbl` | batch | `9200-XREFFILE-CLOSE` | `:L619`&ndash;`:L635` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closeCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R022` | `CBTRN02C.cbl` | batch | `9300-DALYREJS-CLOSE` | `:L637`&ndash;`:L653` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/writers/RejectWriter.java` | `private closeRejsFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R023` | `CBTRN02C.cbl` | batch | `9400-ACCTFILE-CLOSE` | `:L655`&ndash;`:L671` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closeAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R024` | `CBTRN02C.cbl` | batch | `9500-TCATBALF-CLOSE` | `:L674`&ndash;`:L690` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closeCategoryBalanceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R025` | `CBTRN02C.cbl` | batch | `Z-GET-DB2-FORMAT-TIMESTAMP` | `:L692`&ndash;`:L705` | &mdash; | &mdash; | `batch/processors/TransactionPostingProcessor.java` | `private getDb2FormatTimestamp()` | parity-preserved quirk | [`DL-PP-12`](DECISION_LOG.md#dl-pp-12) | `unit/batch/TransactionPostingProcessorTest.java::aBlankProcessingTimestampOnTheInputIsOverwritten` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R026` | `CBTRN02C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L707`&ndash;`:L711` | CALL `CEE3ABD` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private abendProgram(&hellip;)` | parity-preserved quirk | [`DL-MS-11`](DECISION_LOG.md#dl-ms-11) | `unit/batch/TransactionPostingProcessorTest.java::anUnexpectedFailureOnTheBalanceWriteAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN02C-R027` | `CBTRN02C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L714`&ndash;`:L727` | &mdash; | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private displayIoStatus(&hellip;)` | parity-preserved quirk | [`DL-MS-11`](DECISION_LOG.md#dl-ms-11) | `unit/batch/TransactionPostingProcessorTest.java::theAccountRewriteAbendDoesNotBorrowTheGuardsAuthority` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CBTRN02C.cbl`.**

- **`TM-CBTRN02C-R011` &middot; `1500-B-LOOKUP-ACCT` &middot; `:L393`&ndash;`:L422` &middot; parity-preserved quirk &middot; [`DL-LD-04`](DECISION_LOG.md#dl-ld-04).** The over-limit and expiry checks are SEQUENTIAL AND UNGUARDED - no `ELSE`, no early exit. When both fail, **code 103 overwrites code 102** and exactly ONE reject record bearing 103 is written. The over-limit temporary balance is transcribed verbatim (cycle credit MINUS cycle debit PLUS transaction amount); the debit accumulator legitimately holds negative values, which is why subtracting it is correct. The expiry test compares the account expiry against the first ten characters of the ORIGINATING timestamp, not the processing timestamp; the copybook field name is misspelled and the misspelling is part of the contract.
- **`TM-CBTRN02C-R012` &middot; `2000-POST-TRANSACTION` &middot; `:L424`&ndash;`:L444` &middot; deviation &middot; [`DL-DV-01`](DECISION_LOG.md#dl-dv-01).** Posting order preserved: category balance, then account, then transaction. The source commits these as THREE independent writes; Java scopes them into ONE transaction, which closes the orphaned-category-balance/orphaned-transaction hazard on the 109 path. That is a genuine improvement, so it is labelled a DEVIATION rather than passed off as parity.
- **`TM-CBTRN02C-R013` &middot; `2500-WRITE-REJECT-REC` &middot; `:L446`&ndash;`:L465` &middot; direct &middot; [`DL-MS-10`](DECISION_LOG.md#dl-ms-10).** 430-byte record = 350-byte transaction image + 80-byte trailer (4-digit reason code + 76-character description). Independently confirmed by `LRECL=430` at `app/jcl/POSTTRAN.jcl:L36`.
- **`TM-CBTRN02C-R014` &middot; `2700-UPDATE-TCATBAL` &middot; `:L467`&ndash;`:L501` &middot; parity-preserved quirk &middot; [`DL-MS-11`](DECISION_LOG.md#dl-ms-11).** Upsert. On an invalid key it accepts EITHER `'00'` OR `'23'` as success before branching to create or rewrite. This is one of only three sites where a not-found status is an accepted control path rather than an error.
- **`TM-CBTRN02C-R017` &middot; `2800-UPDATE-ACCOUNT-REC` &middot; `:L545`&ndash;`:L560` &middot; parity-preserved quirk &middot; [`DL-PP-10`](DECISION_LOG.md#dl-pp-10).** A NEGATIVE transaction amount is added to the current-cycle DEBIT accumulator, so that accumulator holds negative values. **No absolute-value normalisation is permitted.** Reject code 109 is assigned here on the rewrite-failure path but can never be observed as a reject outcome, because the paragraph runs only on the already-validated path - see `DL-PP-03`.
- **`TM-CBTRN02C-R025` &middot; `Z-GET-DB2-FORMAT-TIMESTAMP` &middot; `:L692`&ndash;`:L705` &middot; parity-preserved quirk &middot; [`DL-PP-12`](DECISION_LOG.md#dl-pp-12).** Builds a 26-character timestamp of the exact shape **`yyyy-MM-dd-HH.mm.ss.SS0000`** &mdash; **two** fraction digits, then **four** literal zeros. The geometry is fixed by the redefinition, not inferred: `DB2-FORMAT-TS` is `PIC X(26)` at `:L159` and its `FILLER REDEFINES` layout at `:L160`&ndash;`:L174` ends `DB2-MIL PIC 9(002)` at `:L173` followed by `DB2-REST PIC X(04)` at `:L174`, with `MOVE COB-MIL TO DB2-MIL` at `:L700` drawing from `COB-MIL PIC X(02)` at `:L157` &mdash; the hundredths field `FUNCTION CURRENT-DATE` returns. 4+1+2+1+2+1+2+1+2+1+2+4 = 26 exactly. **Java formats to `yyyy-MM-dd-HH.mm.ss.SS`, which is HUNDREDTHS, then appends `0000`.** An earlier revision of this note said Java *"formats to millisecond precision then appends four zeros"*; that is **withdrawn** &mdash; millisecond precision is three digits and would emit 27 characters into a 26-character field, differing from the baseline on every record, which is the same failure nanosecond precision causes.
- **`TM-CBTRN02C-R026` &middot; `9999-ABEND-PROGRAM` &middot; `:L707`&ndash;`:L711` &middot; parity-preserved quirk &middot; [`DL-MS-11`](DECISION_LOG.md#dl-ms-11).** Abend code 999 via `CALL 'CEE3ABD'`, mapped to `FatalProcessingException` and process return code 12. Return code 4 is set if and ONLY if the reject count exceeds zero - there is no other determinant.
- **`TM-CBTRN02C-R027` &middot; `9910-DISPLAY-IO-STATUS` &middot; `:L714`&ndash;`:L727` &middot; parity-preserved quirk &middot; [`DL-MS-11`](DECISION_LOG.md#dl-ms-11).** Renders the file status as exactly FOUR characters. Non-numeric status or first byte `'9'`: first byte copied, second expanded from a binary field into three digits. Otherwise `'0000'` with the two status characters at positions 3-4. Byte-identical rendering is required because Gate 1 diffs log output.

<a id="cotrn00c-cbl"></a>

### 5.9 `COTRN00C.cbl`

**online &middot; F-008 Transaction list &middot; 699 physical lines &middot; 16 rows** (16 paragraphs)

Target files: `service/transaction/TransactionListService.java`, `controller/TransactionController.java`.
Tests: `unit/service/TransactionListServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COTRN00C-R001` | `COTRN00C.cbl` | online | `MAIN-PARA` | `:L95`&ndash;`:L141` | PERFORM `PROCESS-ENTER-KEY`, `PROCESS-PF7-KEY`, `PROCESS-PF8-KEY`, `RECEIVE-TRNLST-SCREEN` +2; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/transaction/TransactionListService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R002` | `COTRN00C.cbl` | online | `PROCESS-ENTER-KEY` | `:L146`&ndash;`:L229` | PERFORM `PROCESS-PAGE-FORWARD`, `SEND-TRNLST-SCREEN`; EXEC CICS XCTL | `COCOM01Y.cpy`, `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::pageNumberResetsOnEnter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R003` | `COTRN00C.cbl` | online | `PROCESS-PF7-KEY` | `:L234`&ndash;`:L252` | PERFORM `PROCESS-PAGE-BACKWARD`, `SEND-TRNLST-SCREEN` | `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private processPf7Key(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::alreadyAtTopAndAtTopAreDistinct` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R004` | `COTRN00C.cbl` | online | `PROCESS-PF8-KEY` | `:L257`&ndash;`:L274` | PERFORM `PROCESS-PAGE-FORWARD`, `SEND-TRNLST-SCREEN` | `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private processPf8Key(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R005` | `COTRN00C.cbl` | online | `PROCESS-PAGE-FORWARD` | `:L279`&ndash;`:L328` | PERFORM `ENDBR-TRANSACT-FILE`, `INITIALIZE-TRAN-DATA`, `POPULATE-TRAN-DATA`, `READNEXT-TRANSACT-FILE` +2 | &mdash; | `service/transaction/TransactionListService.java` | `private processPageForward(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R006` | `COTRN00C.cbl` | online | `PROCESS-PAGE-BACKWARD` | `:L333`&ndash;`:L376` | PERFORM `ENDBR-TRANSACT-FILE`, `INITIALIZE-TRAN-DATA`, `POPULATE-TRAN-DATA`, `READPREV-TRANSACT-FILE` +2 | &mdash; | `service/transaction/TransactionListService.java` | `private processPageBackward(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::backwardPathDoesNotBlankSearchField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R007` | `COTRN00C.cbl` | online | `POPULATE-TRAN-DATA` | `:L381`&ndash;`:L445` | &mdash; | `CSDAT01Y.cpy`, `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private populateTranData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::partialPageLeavesLastKeyStale` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R008` | `COTRN00C.cbl` | online | `INITIALIZE-TRAN-DATA` | `:L450`&ndash;`:L505` | &mdash; | &mdash; | `service/transaction/TransactionListService.java` | `private initializeTranData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::tenPositionalSlotsAlways` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R009` | `COTRN00C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L510`&ndash;`:L521` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/transaction/TransactionListService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R010` | `COTRN00C.cbl` | online | `SEND-TRNLST-SCREEN` | `:L527`&ndash;`:L549` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/transaction/TransactionListService.java` | `private sendTrnlstScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R011` | `COTRN00C.cbl` | online | `RECEIVE-TRNLST-SCREEN` | `:L554`&ndash;`:L562` | EXEC CICS RECEIVE | &mdash; | `service/transaction/TransactionListService.java` | `private receiveTrnlstScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::overWidthKeyIsBoundedToMapWidth` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R012` | `COTRN00C.cbl` | online | `POPULATE-HEADER-INFO` | `:L567`&ndash;`:L586` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/transaction/TransactionListService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::everyLabelHasAPrivateMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R013` | `COTRN00C.cbl` | online | `STARTBR-TRANSACT-FILE` | `:L591`&ndash;`:L619` | PERFORM `SEND-TRNLST-SCREEN`; EXEC CICS STARTBR | `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private startbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::alreadyAtTopAndAtTopAreDistinct` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R014` | `COTRN00C.cbl` | online | `READNEXT-TRANSACT-FILE` | `:L624`&ndash;`:L653` | PERFORM `SEND-TRNLST-SCREEN`; EXEC CICS READNEXT | `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private readnextTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::readnextFailureIsReachable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R015` | `COTRN00C.cbl` | online | `READPREV-TRANSACT-FILE` | `:L658`&ndash;`:L687` | PERFORM `SEND-TRNLST-SCREEN`; EXEC CICS READPREV | `CVTRA05Y.cpy` | `service/transaction/TransactionListService.java` | `private readprevTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::readprevFailureIsReachable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN00C-R016` | `COTRN00C.cbl` | online | `ENDBR-TRANSACT-FILE` | `:L692`&ndash;`:L696` | EXEC CICS ENDBR | &mdash; | `service/transaction/TransactionListService.java` | `private endbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionListServiceTest.java::browseIsEndedAfterEveryTurn` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cousr00c-cbl"></a>

### 5.10 `COUSR00C.cbl`

**online &middot; F-012 User list &middot; 695 physical lines &middot; 16 rows** (16 paragraphs)

Target files: `service/admin/UserListService.java`, `controller/AdminController.java`.
Tests: `unit/service/UserListServiceTest.java`, `unit/model/UserSecurityDtoTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COUSR00C-R001` | `COUSR00C.cbl` | online | `MAIN-PARA` | `:L98`&ndash;`:L144` | PERFORM `PROCESS-ENTER-KEY`, `PROCESS-PF7-KEY`, `PROCESS-PF8-KEY`, `RECEIVE-USRLST-SCREEN` +2; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/admin/UserListService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theThreePublicEntryPointsCoverTheThreeWaysMainParaIsReached` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R002` | `COUSR00C.cbl` | online | `PROCESS-ENTER-KEY` | `:L149`&ndash;`:L232` | PERFORM `PROCESS-PAGE-FORWARD`; EXEC CICS XCTL | `COCOM01Y.cpy`, `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theSendCountReflectsEverySendMap` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R003` | `COUSR00C.cbl` | online | `PROCESS-PF7-KEY` | `:L237`&ndash;`:L255` | PERFORM `PROCESS-PAGE-BACKWARD`, `SEND-USRLST-SCREEN` | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private processPf7Key(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R004` | `COUSR00C.cbl` | online | `PROCESS-PF8-KEY` | `:L260`&ndash;`:L277` | PERFORM `PROCESS-PAGE-FORWARD`, `SEND-USRLST-SCREEN` | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private processPf8Key(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R005` | `COUSR00C.cbl` | online | `PROCESS-PAGE-FORWARD` | `:L282`&ndash;`:L331` | PERFORM `ENDBR-USER-SEC-FILE`, `INITIALIZE-USER-DATA`, `POPULATE-USER-DATA`, `READNEXT-USER-SEC-FILE` +2 | &mdash; | `service/admin/UserListService.java` | `private processPageForward(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theSendCountReflectsEverySendMap` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R006` | `COUSR00C.cbl` | online | `PROCESS-PAGE-BACKWARD` | `:L336`&ndash;`:L379` | PERFORM `ENDBR-USER-SEC-FILE`, `INITIALIZE-USER-DATA`, `POPULATE-USER-DATA`, `READPREV-USER-SEC-FILE` +2 | &mdash; | `service/admin/UserListService.java` | `private processPageBackward(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R007` | `COUSR00C.cbl` | online | `POPULATE-USER-DATA` | `:L384`&ndash;`:L441` | &mdash; | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private populateUserData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R008` | `COUSR00C.cbl` | online | `INITIALIZE-USER-DATA` | `:L446`&ndash;`:L501` | &mdash; | &mdash; | `service/admin/UserListService.java` | `private initializeUserData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R009` | `COUSR00C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L506`&ndash;`:L517` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/admin/UserListService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R010` | `COUSR00C.cbl` | online | `SEND-USRLST-SCREEN` | `:L522`&ndash;`:L544` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/admin/UserListService.java` | `private sendUsrlstScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R011` | `COUSR00C.cbl` | online | `RECEIVE-USRLST-SCREEN` | `:L549`&ndash;`:L557` | EXEC CICS RECEIVE | &mdash; | `service/admin/UserListService.java` | `private receiveUsrlstScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R012` | `COUSR00C.cbl` | online | `POPULATE-HEADER-INFO` | `:L562`&ndash;`:L581` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/admin/UserListService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::populateHeaderInfoFillsAllSixHeaderFields` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R013` | `COUSR00C.cbl` | online | `STARTBR-USER-SEC-FILE` | `:L586`&ndash;`:L614` | PERFORM `SEND-USRLST-SCREEN`; EXEC CICS STARTBR | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private startbrUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R014` | `COUSR00C.cbl` | online | `READNEXT-USER-SEC-FILE` | `:L619`&ndash;`:L648` | PERFORM `SEND-USRLST-SCREEN`; EXEC CICS READNEXT | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private readnextUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R015` | `COUSR00C.cbl` | online | `READPREV-USER-SEC-FILE` | `:L653`&ndash;`:L682` | PERFORM `SEND-USRLST-SCREEN`; EXEC CICS READPREV | `CSUSR01Y.cpy` | `service/admin/UserListService.java` | `private readprevUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR00C-R016` | `COUSR00C.cbl` | online | `ENDBR-USER-SEC-FILE` | `:L687`&ndash;`:L691` | EXEC CICS ENDBR | &mdash; | `service/admin/UserListService.java` | `private endbrUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserListServiceTest.java::theBeanDeclaresOneMethodPerSourceParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cbact04c-cbl"></a>

### 5.11 `CBACT04C.cbl`

**batch &middot; F-017 Interest calculation &middot; 652 physical lines &middot; 23 rows** (22 paragraphs + 1 synthetic entry row)

Target files: `batch/jobs/InterestCalculationJob.java`, `batch/processors/InterestCalculationProcessor.java`.
Tests: `unit/batch/InterestCalculationProcessorTest.java`, `integration/batch/InterestCalculationJobTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBACT04C-R001` | `CBACT04C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L180`&ndash;`:L232` | PERFORM `0000-TCATBALF-OPEN`, `0100-XREFFILE-OPEN`, `0200-DISCGRP-OPEN`, `0300-ACCTFILE-OPEN` +13 | `CVTRA01Y.cpy`, `CVTRA02Y.cpy`, `CVACT01Y.cpy` | `batch/jobs/InterestCalculationJob.java` | `public interestCalculationStep(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/InterestCalculationProcessorTest.java::theShippedAccountGroupIdIsBlankOnEveryRow` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R002` | `CBACT04C.cbl` | batch | `0000-TCATBALF-OPEN` | `:L234`&ndash;`:L250` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private openTransactionCategoryBalanceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::categoryBalanceProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R003` | `CBACT04C.cbl` | batch | `0100-XREFFILE-OPEN` | `:L252`&ndash;`:L268` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private openCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::crossReferenceProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R004` | `CBACT04C.cbl` | batch | `0200-DISCGRP-OPEN` | `:L270`&ndash;`:L286` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private openDisclosureGroupFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theDiscgrpOpenLiteralNamesTheWrongFileVerbatim` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R005` | `CBACT04C.cbl` | batch | `0300-ACCTFILE-OPEN` | `:L289`&ndash;`:L305` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private openAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theDiscgrpOpenLiteralNamesTheWrongFileVerbatim` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R006` | `CBACT04C.cbl` | batch | `0400-TRANFILE-OPEN` | `:L307`&ndash;`:L323` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private openTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theProcessorReturnsTheTransactionAndPersistsNothing` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R007` | `CBACT04C.cbl` | batch | `1000-TCATBALF-GET-NEXT` | `:L325`&ndash;`:L348` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA01Y.cpy` | `batch/jobs/InterestCalculationJob.java` | `private categoryBalanceReader()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::anAbsentRecordAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R008` | `CBACT04C.cbl` | batch | `1050-UPDATE-ACCOUNT` | `:L350`&ndash;`:L370` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT01Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private updateAccount()` | parity-preserved quirk | [`DL-PP-07`](DECISION_LOG.md#dl-pp-07) | `unit/batch/InterestCalculationProcessorTest.java::theRetainedArmAbendsWithNoAccountLoaded` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R009` | `CBACT04C.cbl` | batch | `1100-GET-ACCT-DATA` | `:L372`&ndash;`:L391` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT01Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private getAccountData(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::anAbsentAccountIdAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R010` | `CBACT04C.cbl` | batch | `1110-GET-XREF-DATA` | `:L393`&ndash;`:L413` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT03Y.cpy`, `CVACT01Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private getCrossReferenceData(&hellip;)` | parity-preserved quirk | [`DL-MS-11`](DECISION_LOG.md#dl-ms-11) | `unit/batch/InterestCalculationProcessorTest.java::theCrossReferenceLookupIsTheAlternateKeyFinder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R011` | `CBACT04C.cbl` | batch | `1200-GET-INTEREST-RATE` | `:L415`&ndash;`:L440` | PERFORM `1200-A-GET-DEFAULT-INT-RATE`, `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA02Y.cpy`, `CVACT01Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private getInterestRate(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theShippedAccountGroupIdIsBlankOnEveryRow` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R012` | `CBACT04C.cbl` | batch | `1200-A-GET-DEFAULT-INT-RATE` | `:L443`&ndash;`:L460` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA02Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private getDefaultInterestRate(&hellip;)` | parity-preserved quirk | [`DL-MS-11`](DECISION_LOG.md#dl-ms-11) | `unit/batch/InterestCalculationProcessorTest.java::aSyntheticAccountReachesTheDirectHit` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R013` | `CBACT04C.cbl` | batch | `1300-COMPUTE-INTEREST` | `:L462`&ndash;`:L470` | PERFORM `1300-B-WRITE-TX` | `CVTRA02Y.cpy`, `CVTRA01Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private computeInterest(&hellip;)` | parity-preserved quirk | [`DL-MS-01`](DECISION_LOG.md#dl-ms-01) | `unit/batch/InterestCalculationProcessorTest.java::aSyntheticZeroRatePairReachesTheSkip` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R014` | `CBACT04C.cbl` | batch | `1300-B-WRITE-TX` | `:L473`&ndash;`:L515` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM`, `Z-GET-DB2-FORMAT-TIMESTAMP` | `CVTRA05Y.cpy`, `CVTRA03Y.cpy`, `CVACT03Y.cpy` | `batch/processors/InterestCalculationProcessor.java` | `private writeTransaction(&hellip;)` | parity-preserved quirk | [`DL-PP-07`](DECISION_LOG.md#dl-pp-07) | `unit/batch/InterestCalculationProcessorTest.java::aZeroRateDoesNotConsumeASuffix` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R015` | `CBACT04C.cbl` | batch | `1400-COMPUTE-FEES` | `:L518`&ndash;`:L520` | &mdash; | &mdash; | `batch/processors/InterestCalculationProcessor.java` | `private computeFees()` | parity-preserved quirk | [`DL-PP-05`](DECISION_LOG.md#dl-pp-05) | `unit/batch/InterestCalculationProcessorTest.java::computeFeesIsDeclaredEmptyAndReachable` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R016` | `CBACT04C.cbl` | batch | `9000-TCATBALF-CLOSE` | `:L522`&ndash;`:L538` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private closeTransactionCategoryBalanceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::categoryBalanceProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R017` | `CBACT04C.cbl` | batch | `9100-XREFFILE-CLOSE` | `:L541`&ndash;`:L557` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private closeCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::crossReferenceProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R018` | `CBACT04C.cbl` | batch | `9200-DISCGRP-CLOSE` | `:L559`&ndash;`:L575` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private closeDisclosureGroupFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::disclosureGroupProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R019` | `CBACT04C.cbl` | batch | `9300-ACCTFILE-CLOSE` | `:L577`&ndash;`:L593` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private closeAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::noPersistCallSiteInSource` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R020` | `CBACT04C.cbl` | batch | `9400-TRANFILE-CLOSE` | `:L595`&ndash;`:L611` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private closeTransactionFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationJobTest.java::transactionProbes` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R021` | `CBACT04C.cbl` | batch | `Z-GET-DB2-FORMAT-TIMESTAMP` | `:L613`&ndash;`:L626` | &mdash; | &mdash; | `batch/processors/InterestCalculationProcessor.java` | `private db2FormatTimestamp()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theTimestampComesFromTheInjectedFixedClock` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R022` | `CBACT04C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L628`&ndash;`:L632` | CALL `CEE3ABD` | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/InterestCalculationProcessorTest.java::theToleratedStatusIsScopedToStageOneOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT04C-R023` | `CBACT04C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L635`&ndash;`:L648` | &mdash; | &mdash; | `batch/jobs/InterestCalculationJob.java` | `private displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/InterestCalculationJobTest.java::removingTheRequiredDefaultRateRowAbendsTheRun` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CBACT04C.cbl`.**

- **`TM-CBACT04C-R008` &middot; `1050-UPDATE-ACCOUNT` &middot; `:L350`&ndash;`:L370` &middot; parity-preserved quirk &middot; [`DL-PP-07`](DECISION_LOG.md#dl-pp-07).** Adds accumulated interest to the current balance and then ZEROES BOTH cycle counters before rewriting. Omitting the reset breaks the over-limit arithmetic on the following posting cycle.
- **`TM-CBACT04C-R008` reachability &middot; THERE IS NO FINAL FLUSH, AND THE LAST ACCOUNT'S INTEREST IS LOST &middot; parity-preserved defect &middot; [`DL-LD-09`](DECISION_LOG.md#dl-ld-09).** An earlier revision of the note above ended *"The end-of-file FINAL FLUSH is modelled separately by `updateAccountAtEndOfFile()`; omitting it silently loses the last account's interest"*, which reads as though a flush executes and is required. **Both halves are withdrawn.** `PERFORM UNTIL END-OF-FILE = 'Y'` at `:L188` tests **before** each iteration, and the `ELSE` at `:L219` pairs with the **outer** `IF END-OF-FILE = 'N'` at `:L189` rather than the inner one at `:L191` &mdash; indentation settles the pairing, the inner `IF` and its `END-IF` at `:L218` standing at column 20 while the outer `IF`, the `ELSE` and the `END-IF` at `:L221` all stand at column 16. So as soon as `1000-TCATBALF-GET-NEXT` sets the flag, the inner test at `:L191` fails, control falls through `:L218`, the `ELSE` is skipped because its own `IF` was true, and the loop re-test at `:L222` ends the browse. **`ELSE PERFORM 1050-UPDATE-ACCOUNT` at `:L219`&ndash;`:L220` is therefore structurally unreachable**, and the account still in progress when the browse ends keeps its pre-run balance and its un-reset cycle counters. That is **preserved, not repaired**: parity with the frozen corpus is the acceptance contract. `InterestCalculationProcessor.updateAccountAtEndOfFile()` exists as the literal translation of `:L220` so the paragraph map stays complete, and it is **never invoked and must never be wired up** &mdash; the method's own Javadoc says so at its declaration. The harness records the finding as `dispositions.parityFinding` in `target/gate-verification/gate-verification-summary.properties`. **Proof by contrast, which is what makes this auditable rather than asserted:** the identical idiom in `CBTRN03C.cbl` has the opposite reachability, because there the `IF END-OF-FILE = 'N'` at `:L179` is the **inner** test sitting after the read within the same iteration, so its `ELSE` at `:L197` **does** execute and does accumulate into the page and account totals at `:L200`&ndash;`:L201`. One idiom, two programs, opposite outcomes, decided purely by which `IF` owns the `ELSE` &mdash; the `CBTRN03C` half is recorded at [`DL-LD-11`](DECISION_LOG.md#dl-ld-11), which is why the pairing has to be read rather than assumed. The specification prose at [§0.7.3.3](docs/technical-specifications.md) describes the flush as executing; the corpus is the authority where the two disagree, and this row is that disclosure.
- **`TM-CBACT04C-R010` &middot; `1110-GET-XREF-DATA` &middot; `:L393`&ndash;`:L413` &middot; parity-preserved quirk &middot; [`DL-MS-11`](DECISION_LOG.md#dl-ms-11).** A missing cross-reference record displays a friendly message and then still hits the standard guard, so **the job abends**. An empty lookup must become a fatal exception, never a skip.
- **`TM-CBACT04C-R012` &middot; `1200-A-GET-DEFAULT-INT-RATE` &middot; `:L443`&ndash;`:L460` &middot; parity-preserved quirk &middot; [`DL-MS-11`](DECISION_LOG.md#dl-ms-11).** The primary lookup accepts EITHER success OR not-found, then substitutes the literal `DEFAULT` group and retries. **The retry accepts only success, so a missing default row abends the job.** On an invalid key the read leaves the previous iteration's record in place until the default read overwrites it, so stale rate state must not carry across iterations.
- **`TM-CBACT04C-R013` &middot; `1300-COMPUTE-INTEREST` &middot; `:L462`&ndash;`:L470` &middot; parity-preserved quirk &middot; [`DL-MS-01`](DECISION_LOG.md#dl-ms-01).** Multiply the category balance by the rate, THEN divide by the literal 1200, with two-decimal HALF_EVEN rounding. Never rewritten as /100 then /12, and never with a decimal multiplier - both change the rounding.
- **`TM-CBACT04C-R014` &middot; `1300-B-WRITE-TX` &middot; `:L473`&ndash;`:L515` &middot; parity-preserved quirk &middot; [`DL-PP-07`](DECISION_LOG.md#dl-pp-07).** The suffix counter is GLOBAL and is not reset per account, so identifiers are run-sequential. The 16-digit identifier is the 10-character date parameter (8 date digits + `00`, not an ISO date) concatenated with a 6-digit suffix. Originating and processing timestamps are set to the SAME generated value. Output goes to a fresh sequential generation, not to the transaction table.
- **`TM-CBACT04C-R015` &middot; `1400-COMPUTE-FEES` &middot; `:L518`&ndash;`:L520` &middot; parity-preserved quirk &middot; [`DL-PP-05`](DECISION_LOG.md#dl-pp-05).** REACHABLE EMPTY PARAGRAPH: the body is a `* To be implemented` comment plus `EXIT.`, and it is performed from `:L216`. Retained as an intentional no-op for control-flow parity. This is the single site where *Rule 1* clause B (no dead code) yields to the parity mandate, resolved in `DL-CR-01`; it is TRACKED here and in the register, so it is not untracked dead code.

<a id="cbtrn03c-cbl"></a>

### 5.12 `CBTRN03C.cbl`

**batch &middot; F-018 Transaction report &middot; 649 physical lines &middot; 27 rows** (26 paragraphs + 1 synthetic entry row)

Target files: `batch/jobs/TransactionReportJob.java`, `batch/processors/TransactionReportProcessor.java`.
Tests: `unit/batch/TransactionReportProcessorTest.java`, `integration/batch/TransactionReportJobTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBTRN03C-R001` | `CBTRN03C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L159`&ndash;`:L217` | PERFORM `0000-TRANFILE-OPEN`, `0100-REPTFILE-OPEN`, `0200-CARDXREF-OPEN`, `0300-TRANTYPE-OPEN` +17 | `CVTRA05Y.cpy`, `CVTRA03Y.cpy`, `CVACT03Y.cpy` | `batch/jobs/TransactionReportJob.java` | `private mainlineProcedureDivision(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/TransactionReportProcessorTest.java::twoCardsOnOneAccountStillBreak` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R002` | `CBTRN03C.cbl` | batch | `0550-DATEPARM-READ` | `:L220`&ndash;`:L243` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CSUTLDWY.cpy` | `batch/processors/TransactionReportProcessor.java` | `private static dateParmRead(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::aWindowWhoseStartIsAfterItsEndIsRefused` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R003` | `CBTRN03C.cbl` | batch | `1000-TRANFILE-GET-NEXT` | `:L248`&ndash;`:L272` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA05Y.cpy` | `batch/readers/TransactionBackupReader.java` | `public read()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::anOutOfWindowRecordDownstreamOfTheSortTerminatesTheReadLoop` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R004` | `CBTRN03C.cbl` | batch | `1100-WRITE-TRANSACTION-REPORT` | `:L274`&ndash;`:L290` | PERFORM `1110-WRITE-PAGE-TOTALS`, `1120-WRITE-DETAIL`, `1120-WRITE-HEADERS` | `CVTRA07Y.cpy`, `CVTRA05Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writeTransactionReport(&hellip;)` | parity-preserved quirk | [`DL-LD-05`](DECISION_LOG.md#dl-ld-05) | `unit/batch/TransactionReportProcessorTest.java::theLastAmountIsCountedTwice` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R005` | `CBTRN03C.cbl` | batch | `1110-WRITE-PAGE-TOTALS` | `:L293`&ndash;`:L304` | PERFORM `1111-WRITE-REPORT-REC` | `CVTRA07Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writePageTotals(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::writePageTotalsEmitsTwoLinesAndRollsUp` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R006` | `CBTRN03C.cbl` | batch | `1120-WRITE-ACCOUNT-TOTALS` | `:L306`&ndash;`:L316` | PERFORM `1111-WRITE-REPORT-REC` | `CVTRA07Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writeAccountTotals(&hellip;)` | parity-preserved quirk | [`DL-LD-05`](DECISION_LOG.md#dl-ld-05) | `unit/batch/TransactionReportProcessorTest.java::finishReportEmitsThreeLines` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R007` | `CBTRN03C.cbl` | batch | `1110-WRITE-GRAND-TOTALS` | `:L318`&ndash;`:L322` | PERFORM `1111-WRITE-REPORT-REC` | `CVTRA07Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writeGrandTotals(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::writeGrandTotalsDoesNotAdvanceTheCounter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R008` | `CBTRN03C.cbl` | batch | `1120-WRITE-HEADERS` | `:L324`&ndash;`:L341` | PERFORM `1111-WRITE-REPORT-REC` | `CVTRA07Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writeHeaders(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::writeHeadersEmitsFourLinesAndFourIncrements` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R009` | `CBTRN03C.cbl` | batch | `1111-WRITE-REPORT-REC` | `:L343`&ndash;`:L359` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private writeReportRec(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::writeGrandTotalsDoesNotAdvanceTheCounter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R010` | `CBTRN03C.cbl` | batch | `1120-WRITE-DETAIL` | `:L361`&ndash;`:L374` | PERFORM `1111-WRITE-REPORT-REC` | `CVTRA07Y.cpy`, `CVTRA05Y.cpy`, `CVTRA03Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private writeDetail(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::writeDetailKeepsBothFillerHyphens` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R011` | `CBTRN03C.cbl` | batch | `0000-TRANFILE-OPEN` | `:L376`&ndash;`:L392` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private tranfileOpen0000()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::tranfileOpenNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R012` | `CBTRN03C.cbl` | batch | `0100-REPTFILE-OPEN` | `:L394`&ndash;`:L410` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private reptfileOpen0100()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::everyEmittedRecordIsExactlyOneHundredThirtyThreeBytesWithNoDelimiter` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R013` | `CBTRN03C.cbl` | batch | `0200-CARDXREF-OPEN` | `:L412`&ndash;`:L428` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private cardxrefOpen0200()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::cardxrefOpenNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R014` | `CBTRN03C.cbl` | batch | `0300-TRANTYPE-OPEN` | `:L430`&ndash;`:L446` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private trantypeOpen0300()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::trantypeOpenNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R015` | `CBTRN03C.cbl` | batch | `0400-TRANCATG-OPEN` | `:L448`&ndash;`:L464` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private trancatgOpen0400()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::trancatgOpenNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R016` | `CBTRN03C.cbl` | batch | `0500-DATEPARM-OPEN` | `:L466`&ndash;`:L482` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private dateparmOpen0500()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::aWindowWhoseStartIsAfterItsEndIsRefused` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R017` | `CBTRN03C.cbl` | batch | `1500-A-LOOKUP-XREF` | `:L484`&ndash;`:L492` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT03Y.cpy`, `CVACT02Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private lookupXref(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::anAbsentCrossReferenceRecordAbendsTheReport` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R018` | `CBTRN03C.cbl` | batch | `1500-B-LOOKUP-TRANTYPE` | `:L494`&ndash;`:L502` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA03Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private lookupTranType(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::theLookupKeysEveryRecordResolvesAgainstAreSeeded` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R019` | `CBTRN03C.cbl` | batch | `1500-C-LOOKUP-TRANCATG` | `:L504`&ndash;`:L512` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVTRA04Y.cpy` | `batch/processors/TransactionReportProcessor.java` | `private lookupTranCatg(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::theLookupKeysEveryRecordResolvesAgainstAreSeeded` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R020` | `CBTRN03C.cbl` | batch | `9000-TRANFILE-CLOSE` | `:L514`&ndash;`:L530` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private tranfileClose9000()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::tranfileCloseNamesItselfAndDiffersFromItsOwnOpen` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R021` | `CBTRN03C.cbl` | batch | `9100-REPTFILE-CLOSE` | `:L532`&ndash;`:L548` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private reptfileClose9100()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::anEmptyInWindowSetStillEmitsTheClosingBlock` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R022` | `CBTRN03C.cbl` | batch | `9200-CARDXREF-CLOSE` | `:L551`&ndash;`:L567` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private cardxrefClose9200()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::cardxrefCloseNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R023` | `CBTRN03C.cbl` | batch | `9300-TRANTYPE-CLOSE` | `:L569`&ndash;`:L585` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private trantypeClose9300()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::trantypeCloseNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R024` | `CBTRN03C.cbl` | batch | `9400-TRANCATG-CLOSE` | `:L587`&ndash;`:L603` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private trancatgClose9400()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::trancatgCloseNamesItself` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R025` | `CBTRN03C.cbl` | batch | `9500-DATEPARM-CLOSE` | `:L605`&ndash;`:L621` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private dateparmClose9500()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/TransactionReportJobTest.java::threeStepsRunOnceEachInProcedureOrder` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R026` | `CBTRN03C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L626`&ndash;`:L630` | CALL `CEE3ABD` | &mdash; | `batch/processors/TransactionReportProcessor.java` | `private static abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportLifecycleTest.java::theAbendCarriesTheLegacyContract` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN03C-R027` | `CBTRN03C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L633`&ndash;`:L646` | &mdash; | &mdash; | `service/shared/FileStatusMapper.java` | `public displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/TransactionReportProcessorTest.java::anUnreachableTransactionFileAbends` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CBTRN03C.cbl`.**

- **`TM-CBTRN03C-R004` &middot; `1100-WRITE-TRANSACTION-REPORT` &middot; `:L274`&ndash;`:L290` &middot; parity-preserved quirk &middot; [`DL-LD-05`](DECISION_LOG.md#dl-ld-05).** Twenty lines per page. The inclusive string date filter applied by the upstream sort is RE-APPLIED in the processor exactly as the source re-applies it.
- **`TM-CBTRN03C-R006` &middot; `1120-WRITE-ACCOUNT-TOTALS` &middot; `:L306`&ndash;`:L316` &middot; parity-preserved quirk &middot; [`DL-LD-05`](DECISION_LOG.md#dl-ld-05).** **The control break triggers on the CARD NUMBER while the emitted label reads `Account Total`.** Preserved exactly; the label is not corrected and the break key is not changed.

<a id="corpt00c-cbl"></a>

### 5.13 `CORPT00C.cbl`

**online &middot; F-015 Report submission &middot; 649 physical lines &middot; 10 rows** (10 paragraphs)

Target files: `service/report/ReportSubmissionService.java`, `controller/ReportController.java`.
Tests: `unit/service/ReportSubmissionServiceTest.java`, `integration/aws/SqsReportQueueIntegrationTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CORPT00C-R001` | `CORPT00C.cbl` | online | `MAIN-PARA` | `:L163`&ndash;`:L202` | PERFORM `PROCESS-ENTER-KEY`, `RECEIVE-TRNRPT-SCREEN`, `RETURN-TO-PREV-SCREEN`, `SEND-TRNRPT-SCREEN`; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/report/ReportSubmissionService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R002` | `CORPT00C.cbl` | online | `PROCESS-ENTER-KEY` | `:L208`&ndash;`:L456` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-TRNRPT-SCREEN`, `SUBMIT-JOB-TO-INTRDR`; CALL `CSUTLDTC` | `CSDAT01Y.cpy`, `CSUTLDWY.cpy` | `service/report/ReportSubmissionService.java` | `private processEnterKey(&hellip;)` | parity-preserved quirk | [`DL-MS-18`](DECISION_LOG.md#dl-ms-18) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R003` | `CORPT00C.cbl` | online | `SUBMIT-JOB-TO-INTRDR` | `:L462`&ndash;`:L510` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-TRNRPT-SCREEN`, `WIRTE-JOBSUB-TDQ` | &mdash; | `service/report/ReportSubmissionService.java` | `private submitJobToIntrdr(&hellip;)` | mechanism substitution | [`DL-MS-18`](DECISION_LOG.md#dl-ms-18) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R004` | `CORPT00C.cbl` | online | `WIRTE-JOBSUB-TDQ` | `:L515`&ndash;`:L535` | PERFORM `SEND-TRNRPT-SCREEN`; EXEC CICS WRITEQ | &mdash; | `service/report/ReportSubmissionService.java` | `private wirteJobsubTdq(&hellip;)` | parity-preserved quirk | [`DL-LD-08`](DECISION_LOG.md#dl-ld-08) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R005` | `CORPT00C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L540`&ndash;`:L551` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/report/ReportSubmissionService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R006` | `CORPT00C.cbl` | online | `SEND-TRNRPT-SCREEN` | `:L556`&ndash;`:L580` | PERFORM `POPULATE-HEADER-INFO`; GO TO `RETURN-TO-CICS`; EXEC CICS SEND | &mdash; | `service/report/ReportSubmissionService.java` | `private sendTrnrptScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R007` | `CORPT00C.cbl` | online | `RETURN-TO-CICS` | `:L585`&ndash;`:L591` | EXEC CICS RETURN | `COCOM01Y.cpy` | `service/report/ReportSubmissionService.java` | `private returnToCics(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R008` | `CORPT00C.cbl` | online | `RECEIVE-TRNRPT-SCREEN` | `:L596`&ndash;`:L604` | EXEC CICS RECEIVE | &mdash; | `service/report/ReportSubmissionService.java` | `private receiveTrnrptScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R009` | `CORPT00C.cbl` | online | `POPULATE-HEADER-INFO` | `:L609`&ndash;`:L628` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/report/ReportSubmissionService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CORPT00C-R010` | `CORPT00C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L633`&ndash;`:L646` | &mdash; | &mdash; | `service/report/ReportSubmissionService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/ReportSubmissionServiceTest.java::allTenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CORPT00C.cbl`.**

- **`TM-CORPT00C-R002` &middot; `PROCESS-ENTER-KEY` &middot; `:L208`&ndash;`:L456` &middot; parity-preserved quirk &middot; [`DL-MS-18`](DECISION_LOG.md#dl-ms-18).** Three report periods. **Monthly is the FULL CURRENT CALENDAR MONTH, first day to last day** &mdash; an earlier revision of this note said *"Monthly is month-to-date, not a full calendar month"*, with the end date taken as the current year, month **and day**, and that is **withdrawn as a misreading of the source**. Start is the current year and month with day `01` at `:L217`&ndash;`:L221`. The end date is then computed, not copied: `:L223` forces the day to 1, `:L224` adds 1 to the month, `:L225`&ndash;`:L228` rolls the year when the month exceeds 12, and `:L229`&ndash;`:L230` evaluates `FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)` &mdash; the first of next month minus one day, which is **the last day of the current month**. The result is moved to the end-date components at `:L232`&ndash;`:L236`. The current day of the month is overwritten at `:L223` and never reaches the end date, which is precisely why the month-to-date reading is wrong. `ReportSubmissionService` computes exactly that as `periodStart.plusMonths(1L).minusDays(1L)`, so the implementation was already right and only the prose was wrong. Yearly spans the first through last day of the current year. Custom validates six discrete date components through the date service against an explicit format string.
- **`TM-CORPT00C-R003` &middot; `SUBMIT-JOB-TO-INTRDR` &middot; `:L462`&ndash;`:L510` &middot; mechanism substitution &middot; [`DL-MS-18`](DECISION_LOG.md#dl-ms-18).** An entire job deck is embedded as 80-byte literal constants redefined as an array. The submission loop writes the TERMINATING card BEFORE the loop exits. The **seventeen** card images collapse to ONE typed FIFO queue message carrying the report name and two dates. **Seventeen, not eighteen** &mdash; an earlier revision of this entry said eighteen, inheriting the figure from specification prose, and that is withdrawn: `02 JOB-DATA-1.` at [`app/cbl/CORPT00C.cbl:L82`] declares seventeen `05`-level items across `:L83`&ndash;`:L125`, of which three are composite groups whose subfields sum to 80 bytes each so the two dates can be injected.
- **`TM-CORPT00C-R004` &middot; `WIRTE-JOBSUB-TDQ` &middot; `:L515`&ndash;`:L535` &middot; parity-preserved quirk &middot; [`DL-LD-08`](DECISION_LOG.md#dl-ld-08).** **The paragraph name is misspelled in the source** (`WIRTE`), and the Java method preserves the misspelling as `wirteJobsubTdq` so the correspondence stays mechanically checkable. Failure to enqueue must reproduce the exact legacy screen message, which is part of the observable contract.

<a id="cobil00c-cbl"></a>

### 5.14 `COBIL00C.cbl`

**online &middot; F-011 Bill payment &middot; 572 physical lines &middot; 16 rows** (16 paragraphs)

Target files: `service/billing/BillPaymentService.java`, `controller/BillingController.java`.
Tests: `unit/service/BillPaymentServiceTest.java`, `unit/model/BillPaymentRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COBIL00C-R001` | `COBIL00C.cbl` | online | `MAIN-PARA` | `:L99`&ndash;`:L149` | PERFORM `CLEAR-CURRENT-SCREEN`, `PROCESS-ENTER-KEY`, `RECEIVE-BILLPAY-SCREEN`, `RETURN-TO-PREV-SCREEN` +1; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/billing/BillPaymentService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R002` | `COBIL00C.cbl` | online | `PROCESS-ENTER-KEY` | `:L154`&ndash;`:L244` | PERFORM `CLEAR-CURRENT-SCREEN`, `ENDBR-TRANSACT-FILE`, `GET-CURRENT-TIMESTAMP`, `READ-ACCTDAT-FILE` +6 | `CVTRA05Y.cpy`, `CVACT03Y.cpy`, `CVACT01Y.cpy` | `service/billing/BillPaymentService.java` | `private processEnterKey(&hellip;)` | parity-preserved quirk | [`DL-PP-04`](DECISION_LOG.md#dl-pp-04) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R003` | `COBIL00C.cbl` | online | `GET-CURRENT-TIMESTAMP` | `:L249`&ndash;`:L267` | EXEC CICS ASKTIME, FORMATTIME | `CSDAT01Y.cpy` | `service/billing/BillPaymentService.java` | `private getCurrentTimestamp(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R004` | `COBIL00C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L273`&ndash;`:L284` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/billing/BillPaymentService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R005` | `COBIL00C.cbl` | online | `SEND-BILLPAY-SCREEN` | `:L289`&ndash;`:L301` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/billing/BillPaymentService.java` | `private sendBillpayScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::payBillReturnsTheClearedMapForTheDeclinedBranch` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R006` | `COBIL00C.cbl` | online | `RECEIVE-BILLPAY-SCREEN` | `:L306`&ndash;`:L314` | EXEC CICS RECEIVE | &mdash; | `service/billing/BillPaymentService.java` | `private receiveBillpayScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R007` | `COBIL00C.cbl` | online | `POPULATE-HEADER-INFO` | `:L319`&ndash;`:L338` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/billing/BillPaymentService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R008` | `COBIL00C.cbl` | online | `READ-ACCTDAT-FILE` | `:L343`&ndash;`:L372` | PERFORM `SEND-BILLPAY-SCREEN`; EXEC CICS READ | `CVACT01Y.cpy` | `service/billing/BillPaymentService.java` | `private readAcctdatFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R009` | `COBIL00C.cbl` | online | `UPDATE-ACCTDAT-FILE` | `:L377`&ndash;`:L403` | PERFORM `SEND-BILLPAY-SCREEN`; EXEC CICS REWRITE | `CVACT01Y.cpy` | `service/billing/BillPaymentService.java` | `private updateAcctdatFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R010` | `COBIL00C.cbl` | online | `READ-CXACAIX-FILE` | `:L408`&ndash;`:L436` | PERFORM `SEND-BILLPAY-SCREEN`; EXEC CICS READ | `CVACT03Y.cpy`, `CVACT01Y.cpy` | `service/billing/BillPaymentService.java` | `private readCxacaixFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R011` | `COBIL00C.cbl` | online | `STARTBR-TRANSACT-FILE` | `:L441`&ndash;`:L467` | PERFORM `SEND-BILLPAY-SCREEN`; EXEC CICS STARTBR | `CVTRA05Y.cpy` | `service/billing/BillPaymentService.java` | `private startbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::theThreeBrowseParagraphsRemainDistinct` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R012` | `COBIL00C.cbl` | online | `READPREV-TRANSACT-FILE` | `:L472`&ndash;`:L496` | PERFORM `SEND-BILLPAY-SCREEN`; EXEC CICS READPREV | `CVTRA05Y.cpy` | `service/billing/BillPaymentService.java` | `private readprevTransactFile(&hellip;)` | parity-preserved quirk | [`DL-PP-04`](DECISION_LOG.md#dl-pp-04) | `unit/service/BillPaymentServiceTest.java::theThreeBrowseParagraphsRemainDistinct` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R013` | `COBIL00C.cbl` | online | `ENDBR-TRANSACT-FILE` | `:L501`&ndash;`:L505` | EXEC CICS ENDBR | &mdash; | `service/billing/BillPaymentService.java` | `private endbrTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::theThreeBrowseParagraphsRemainDistinct` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R014` | `COBIL00C.cbl` | online | `WRITE-TRANSACT-FILE` | `:L510`&ndash;`:L547` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-BILLPAY-SCREEN`; EXEC CICS WRITE | `CVTRA05Y.cpy` | `service/billing/BillPaymentService.java` | `private writeTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R015` | `COBIL00C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L552`&ndash;`:L555` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-BILLPAY-SCREEN` | &mdash; | `service/billing/BillPaymentService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::decliningClearsTheScreen` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COBIL00C-R016` | `COBIL00C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L560`&ndash;`:L566` | &mdash; | &mdash; | `service/billing/BillPaymentService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/BillPaymentServiceTest.java::payBillReturnsTheClearedMapForTheDeclinedBranch` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COBIL00C.cbl`.**

- **`TM-COBIL00C-R002` &middot; `PROCESS-ENTER-KEY` &middot; `:L154`&ndash;`:L244` &middot; parity-preserved quirk &middot; [`DL-PP-04`](DECISION_LOG.md#dl-pp-04).** Rejects when the current balance is at or below zero; pays the ENTIRE current balance - never partial - and drives the balance to exactly zero. A two-phase confirmation gate precedes all of it.
- **`TM-COBIL00C-R012` &middot; `READPREV-TRANSACT-FILE` &middot; `:L472`&ndash;`:L496` &middot; parity-preserved quirk &middot; [`DL-PP-04`](DECISION_LOG.md#dl-pp-04).** The empty-file path is explicit: an end-of-file response moves zeros into the identifier, so the FIRST generated identifier is 1.

<a id="cbtrn01c-cbl"></a>

### 5.15 `CBTRN01C.cbl`

**batch &middot; F-016 Daily posting pre-flight (read-only) &middot; 491 physical lines &middot; 18 rows** (18 paragraphs)

Target files: `batch/jobs/DailyTransactionPostingJob.java`.
Tests: `integration/batch/DailyTransactionPostingJobTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBTRN01C-R001` | `CBTRN01C.cbl` | batch | `MAIN-PARA` | `:L155`&ndash;`:L197` | PERFORM `0000-DALYTRAN-OPEN`, `0100-CUSTFILE-OPEN`, `0200-XREFFILE-OPEN`, `0300-CARDFILE-OPEN` +11 | `CVTRA06Y.cpy`, `CVTRA05Y.cpy`, `CVACT03Y.cpy` | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightMainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::thePreFlightStepRunsFirstAndWritesNoDomainState` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R002` | `CBTRN01C.cbl` | batch | `1000-DALYTRAN-GET-NEXT` | `:L202`&ndash;`:L225` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | `CVTRA06Y.cpy`, `CVTRA05Y.cpy` | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightDalytranGetNext(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `integration/batch/DailyTransactionPostingJobTest.java::thePreFlightStepRunsFirstAndWritesNoDomainState` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R003` | `CBTRN01C.cbl` | batch | `2000-LOOKUP-XREF` | `:L227`&ndash;`:L239` | &mdash; | `CVACT03Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy` | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightLookupXref(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::theCrossReferenceTripleMasksEveryIdentifier` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R004` | `CBTRN01C.cbl` | batch | `3000-READ-ACCOUNT` | `:L241`&ndash;`:L250` | &mdash; | `CVACT01Y.cpy` | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightReadAccount(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::theMissingAccountWarningCarriesNoIdentifier` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R005` | `CBTRN01C.cbl` | batch | `0000-DALYTRAN-OPEN` | `:L252`&ndash;`:L268` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightDailyTransactionFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R006` | `CBTRN01C.cbl` | batch | `0100-CUSTFILE-OPEN` | `:L271`&ndash;`:L287` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R007` | `CBTRN01C.cbl` | batch | `0200-XREFFILE-OPEN` | `:L289`&ndash;`:L305` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R008` | `CBTRN01C.cbl` | batch | `0300-CARDFILE-OPEN` | `:L307`&ndash;`:L323` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightCardFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R009` | `CBTRN01C.cbl` | batch | `0400-ACCTFILE-OPEN` | `:L325`&ndash;`:L341` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R010` | `CBTRN01C.cbl` | batch | `0500-TRANFILE-OPEN` | `:L343`&ndash;`:L359` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private openPreFlightTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulOpenNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R011` | `CBTRN01C.cbl` | batch | `9000-DALYTRAN-CLOSE` | `:L361`&ndash;`:L377` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightDailyTransactionFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R012` | `CBTRN01C.cbl` | batch | `9100-CUSTFILE-CLOSE` | `:L379`&ndash;`:L395` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R013` | `CBTRN01C.cbl` | batch | `9200-XREFFILE-CLOSE` | `:L397`&ndash;`:L413` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R014` | `CBTRN01C.cbl` | batch | `9300-CARDFILE-CLOSE` | `:L415`&ndash;`:L431` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightCardFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R015` | `CBTRN01C.cbl` | batch | `9400-ACCTFILE-CLOSE` | `:L433`&ndash;`:L449` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R016` | `CBTRN01C.cbl` | batch | `9500-TRANFILE-CLOSE` | `:L451`&ndash;`:L467` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private closePreFlightTransactionFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::aSuccessfulCloseNamesTheOperationOnly` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R017` | `CBTRN01C.cbl` | batch | `Z-ABEND-PROGRAM` | `:L469`&ndash;`:L473` | CALL `CEE3ABD` | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightAbendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::theFailureBranchStillCarriesTheReason` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBTRN01C-R018` | `CBTRN01C.cbl` | batch | `Z-DISPLAY-IO-STATUS` | `:L476`&ndash;`:L489` | &mdash; | &mdash; | `batch/jobs/DailyTransactionPostingJob.java` | `private preFlightDisplayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/DailyTransactionPostingJobContextLifecycleTest.java::theFailureBranchStillCarriesTheReason` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cousr02c-cbl"></a>

### 5.16 `COUSR02C.cbl`

**online &middot; F-013 User update &middot; 414 physical lines &middot; 11 rows** (11 paragraphs)

Target files: `service/admin/UserUpdateService.java`, `controller/AdminController.java`.
Tests: `unit/service/UserUpdateServiceTest.java`, `unit/model/UserUpdateRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COUSR02C-R001` | `COUSR02C.cbl` | online | `MAIN-PARA` | `:L82`&ndash;`:L138` | PERFORM `CLEAR-CURRENT-SCREEN`, `PROCESS-ENTER-KEY`, `RECEIVE-USRUPD-SCREEN`, `RETURN-TO-PREV-SCREEN` +2; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/admin/UserUpdateService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R002` | `COUSR02C.cbl` | online | `PROCESS-ENTER-KEY` | `:L143`&ndash;`:L172` | PERFORM `READ-USER-SEC-FILE`, `SEND-USRUPD-SCREEN` | `CSUSR01Y.cpy` | `service/admin/UserUpdateService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::theEnterArmAppliesOnlyTheIdentifierGuard` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R003` | `COUSR02C.cbl` | online | `UPDATE-USER-INFO` | `:L177`&ndash;`:L245` | PERFORM `READ-USER-SEC-FILE`, `SEND-USRUPD-SCREEN`, `UPDATE-USER-SEC-FILE` | `CSUSR01Y.cpy` | `service/admin/UserUpdateService.java` | `private updateUserInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::pf12LeavesWithoutWriting` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R004` | `COUSR02C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L250`&ndash;`:L261` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/admin/UserUpdateService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R005` | `COUSR02C.cbl` | online | `SEND-USRUPD-SCREEN` | `:L266`&ndash;`:L278` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/admin/UserUpdateService.java` | `private sendUsrupdScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::theNonSendingArmEchoesTheSubmittedMessageUnchanged` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R006` | `COUSR02C.cbl` | online | `RECEIVE-USRUPD-SCREEN` | `:L283`&ndash;`:L291` | EXEC CICS RECEIVE | &mdash; | `service/admin/UserUpdateService.java` | `private receiveUsrupdScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R007` | `COUSR02C.cbl` | online | `POPULATE-HEADER-INFO` | `:L296`&ndash;`:L315` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/admin/UserUpdateService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R008` | `COUSR02C.cbl` | online | `READ-USER-SEC-FILE` | `:L320`&ndash;`:L353` | PERFORM `SEND-USRUPD-SCREEN`; EXEC CICS READ | `CSUSR01Y.cpy` | `service/admin/UserUpdateService.java` | `private readUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R009` | `COUSR02C.cbl` | online | `UPDATE-USER-SEC-FILE` | `:L358`&ndash;`:L390` | PERFORM `SEND-USRUPD-SCREEN`; EXEC CICS REWRITE | `CSUSR01Y.cpy` | `service/admin/UserUpdateService.java` | `private updateUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R010` | `COUSR02C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L395`&ndash;`:L398` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-USRUPD-SCREEN` | &mdash; | `service/admin/UserUpdateService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR02C-R011` | `COUSR02C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L403`&ndash;`:L411` | &mdash; | &mdash; | `service/admin/UserUpdateService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserUpdateServiceTest.java::allElevenParagraphsHaveAPrivateCounterpart` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cousr03c-cbl"></a>

### 5.17 `COUSR03C.cbl`

**online &middot; F-014 User delete &middot; 359 physical lines &middot; 11 rows** (11 paragraphs)

Target files: `service/admin/UserDeleteService.java`, `controller/AdminController.java`.
Tests: `unit/service/UserDeleteServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COUSR03C-R001` | `COUSR03C.cbl` | online | `MAIN-PARA` | `:L82`&ndash;`:L137` | PERFORM `CLEAR-CURRENT-SCREEN`, `DELETE-USER-INFO`, `PROCESS-ENTER-KEY`, `RECEIVE-USRDEL-SCREEN` +2; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/admin/UserDeleteService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theElevenParagraphsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R002` | `COUSR03C.cbl` | online | `PROCESS-ENTER-KEY` | `:L142`&ndash;`:L169` | PERFORM `READ-USER-SEC-FILE`, `SEND-USRDEL-SCREEN` | `CSUSR01Y.cpy` | `service/admin/UserDeleteService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::aSelectedIdentifierPerformsTheLookupImmediately` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R003` | `COUSR03C.cbl` | online | `DELETE-USER-INFO` | `:L174`&ndash;`:L192` | PERFORM `DELETE-USER-SEC-FILE`, `READ-USER-SEC-FILE`, `SEND-USRDEL-SCREEN` | `CSUSR01Y.cpy` | `service/admin/UserDeleteService.java` | `private deleteUserInfo(&hellip;)` | parity-preserved quirk | [`DL-LD-06`](DECISION_LOG.md#dl-ld-06) | `unit/service/UserDeleteServiceTest.java::theDestructiveOperationIsPerformedUnderExactlyOneKey` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R004` | `COUSR03C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L197`&ndash;`:L208` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/admin/UserDeleteService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theElevenParagraphsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R005` | `COUSR03C.cbl` | online | `SEND-USRDEL-SCREEN` | `:L213`&ndash;`:L225` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/admin/UserDeleteService.java` | `private sendUsrdelScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::anAbsentCommunicationAreaRoutesToSignOn` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R006` | `COUSR03C.cbl` | online | `RECEIVE-USRDEL-SCREEN` | `:L230`&ndash;`:L238` | EXEC CICS RECEIVE | &mdash; | `service/admin/UserDeleteService.java` | `private receiveUsrdelScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theElevenParagraphsEachHaveTheirOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R007` | `COUSR03C.cbl` | online | `POPULATE-HEADER-INFO` | `:L243`&ndash;`:L262` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/admin/UserDeleteService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::aSuccessfulLookupSendsTheScreenTwice` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R008` | `COUSR03C.cbl` | online | `READ-USER-SEC-FILE` | `:L267`&ndash;`:L300` | PERFORM `SEND-USRDEL-SCREEN`; EXEC CICS READ | `CSUSR01Y.cpy` | `service/admin/UserDeleteService.java` | `private readUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theReadAndTheDeleteSitBackToBack` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R009` | `COUSR03C.cbl` | online | `DELETE-USER-SEC-FILE` | `:L305`&ndash;`:L336` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-USRDEL-SCREEN`; EXEC CICS DELETE | `CSUSR01Y.cpy` | `service/admin/UserDeleteService.java` | `private deleteUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theReadAndTheDeleteSitBackToBack` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R010` | `COUSR03C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L341`&ndash;`:L344` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-USRDEL-SCREEN` | &mdash; | `service/admin/UserDeleteService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theClearScreenParagraphIsRetainedInItsOwnRight` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR03C-R011` | `COUSR03C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L349`&ndash;`:L356` | &mdash; | &mdash; | `service/admin/UserDeleteService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserDeleteServiceTest.java::theClearPrecedesTheCompose` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COUSR03C.cbl`.**

- **`TM-COUSR03C-R003` &middot; `DELETE-USER-INFO` &middot; `:L174`&ndash;`:L192` &middot; parity-preserved quirk &middot; [`DL-LD-06`](DECISION_LOG.md#dl-ld-06).** **No self-delete guard exists in the source** - the target user identifier is never compared against the signed-on identifier - and none was invented. Read-confirm-delete only.

<a id="cotrn01c-cbl"></a>

### 5.18 `COTRN01C.cbl`

**online &middot; F-009 Transaction detail &middot; 330 physical lines &middot; 9 rows** (9 paragraphs)

Target files: `service/transaction/TransactionDetailService.java`, `controller/TransactionController.java`.
Tests: `unit/service/TransactionDetailServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COTRN01C-R001` | `COTRN01C.cbl` | online | `MAIN-PARA` | `:L86`&ndash;`:L139` | PERFORM `CLEAR-CURRENT-SCREEN`, `PROCESS-ENTER-KEY`, `RECEIVE-TRNVIEW-SCREEN`, `RETURN-TO-PREV-SCREEN` +1; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/transaction/TransactionDetailService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R002` | `COTRN01C.cbl` | online | `PROCESS-ENTER-KEY` | `:L144`&ndash;`:L192` | PERFORM `READ-TRANSACT-FILE`, `SEND-TRNVIEW-SCREEN` | `CVTRA05Y.cpy`, `CVTRA03Y.cpy`, `CVACT02Y.cpy` | `service/transaction/TransactionDetailService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R003` | `COTRN01C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L197`&ndash;`:L208` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/transaction/TransactionDetailService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::returnToPrevScreenDefaultsAnUnsetTargetToSignOn` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R004` | `COTRN01C.cbl` | online | `SEND-TRNVIEW-SCREEN` | `:L213`&ndash;`:L225` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/transaction/TransactionDetailService.java` | `private sendTrnviewScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R005` | `COTRN01C.cbl` | online | `RECEIVE-TRNVIEW-SCREEN` | `:L230`&ndash;`:L238` | EXEC CICS RECEIVE | &mdash; | `service/transaction/TransactionDetailService.java` | `private receiveTrnviewScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R006` | `COTRN01C.cbl` | online | `POPULATE-HEADER-INFO` | `:L243`&ndash;`:L262` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/transaction/TransactionDetailService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R007` | `COTRN01C.cbl` | online | `READ-TRANSACT-FILE` | `:L267`&ndash;`:L296` | PERFORM `SEND-TRNVIEW-SCREEN`; EXEC CICS READ | `CVTRA05Y.cpy` | `service/transaction/TransactionDetailService.java` | `private readTransactFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R008` | `COTRN01C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L301`&ndash;`:L304` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-TRNVIEW-SCREEN` | &mdash; | `service/transaction/TransactionDetailService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COTRN01C-R009` | `COTRN01C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L309`&ndash;`:L326` | &mdash; | &mdash; | `service/transaction/TransactionDetailService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/TransactionDetailServiceTest.java::allNineParagraphsArePresentAsPrivateMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cousr01c-cbl"></a>

### 5.19 `COUSR01C.cbl`

**online &middot; F-012 User add &middot; 299 physical lines &middot; 9 rows** (9 paragraphs)

Target files: `service/admin/UserAddService.java`, `controller/AdminController.java`.
Tests: `unit/service/UserAddServiceTest.java`, `unit/model/UserCreateRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COUSR01C-R001` | `COUSR01C.cbl` | online | `MAIN-PARA` | `:L71`&ndash;`:L110` | PERFORM `CLEAR-CURRENT-SCREEN`, `PROCESS-ENTER-KEY`, `RECEIVE-USRADD-SCREEN`, `RETURN-TO-PREV-SCREEN` +1; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/admin/UserAddService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::nineLabelsMapOneToOne` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R002` | `COUSR01C.cbl` | online | `PROCESS-ENTER-KEY` | `:L115`&ndash;`:L160` | PERFORM `SEND-USRADD-SCREEN`, `WRITE-USER-SEC-FILE` | `CSUSR01Y.cpy` | `service/admin/UserAddService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::orderIsFirstNameLeadingNotIdentifierLeading` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R003` | `COUSR01C.cbl` | online | `RETURN-TO-PREV-SCREEN` | `:L165`&ndash;`:L178` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/admin/UserAddService.java` | `private returnToPrevScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::pf3ReturnsToTheAdminMenu` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R004` | `COUSR01C.cbl` | online | `SEND-USRADD-SCREEN` | `:L184`&ndash;`:L196` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/admin/UserAddService.java` | `private sendUsraddScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::clearCurrentScreenIsNotConsolidated` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R005` | `COUSR01C.cbl` | online | `RECEIVE-USRADD-SCREEN` | `:L201`&ndash;`:L209` | EXEC CICS RECEIVE | &mdash; | `service/admin/UserAddService.java` | `private receiveUsraddScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::armsThatReadNothingTolerateAnAbsentRequest` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R006` | `COUSR01C.cbl` | online | `POPULATE-HEADER-INFO` | `:L214`&ndash;`:L233` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/admin/UserAddService.java` | `private populateHeaderInfo(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::theCredentialIsHashedExactlyAsSubmitted` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R007` | `COUSR01C.cbl` | online | `WRITE-USER-SEC-FILE` | `:L238`&ndash;`:L274` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-USRADD-SCREEN`; EXEC CICS WRITE | `CSUSR01Y.cpy` | `service/admin/UserAddService.java` | `private writeUserSecFile(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::unstorableValueIsARequestErrorAndNeverAFalseDuplicate` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R008` | `COUSR01C.cbl` | online | `CLEAR-CURRENT-SCREEN` | `:L279`&ndash;`:L282` | PERFORM `INITIALIZE-ALL-FIELDS`, `SEND-USRADD-SCREEN` | &mdash; | `service/admin/UserAddService.java` | `private clearCurrentScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::clearCurrentScreenIsNotConsolidated` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COUSR01C-R009` | `COUSR01C.cbl` | online | `INITIALIZE-ALL-FIELDS` | `:L287`&ndash;`:L295` | &mdash; | &mdash; | `service/admin/UserAddService.java` | `private initializeAllFields(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/UserAddServiceTest.java::clearCurrentScreenIsNotConsolidated` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="comen01c-cbl"></a>

### 5.20 `COMEN01C.cbl`

**online &middot; F-002 Main menu &middot; 282 physical lines &middot; 7 rows** (7 paragraphs)

Target files: `service/menu/MainMenuService.java`, `controller/MenuController.java`.
Tests: `unit/service/MainMenuServiceTest.java`, `unit/model/MenuResponseTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COMEN01C-R001` | `COMEN01C.cbl` | online | `MAIN-PARA` | `:L75`&ndash;`:L110` | PERFORM `PROCESS-ENTER-KEY`, `RECEIVE-MENU-SCREEN`, `RETURN-TO-SIGNON-SCREEN`, `SEND-MENU-SCREEN`; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/menu/MainMenuService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::mainParaIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R002` | `COMEN01C.cbl` | online | `PROCESS-ENTER-KEY` | `:L115`&ndash;`:L165` | PERFORM `SEND-MENU-SCREEN`; EXEC CICS XCTL | `COMEN02Y.cpy`, `COCOM01Y.cpy` | `service/menu/MainMenuService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::anAbsentFieldIsTreatedAsAnUntouchedScreenField` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R003` | `COMEN01C.cbl` | online | `RETURN-TO-SIGNON-SCREEN` | `:L170`&ndash;`:L177` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/menu/MainMenuService.java` | `private returnToSignonScreen()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::returnToSignonScreenIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R004` | `COMEN01C.cbl` | online | `SEND-MENU-SCREEN` | `:L182`&ndash;`:L194` | PERFORM `BUILD-MENU-OPTIONS`, `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/menu/MainMenuService.java` | `private sendMenuScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::sendMenuScreenIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R005` | `COMEN01C.cbl` | online | `RECEIVE-MENU-SCREEN` | `:L199`&ndash;`:L207` | EXEC CICS RECEIVE | &mdash; | `service/menu/MainMenuService.java` | `private receiveMenuScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::receiveMenuScreenIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R006` | `COMEN01C.cbl` | online | `POPULATE-HEADER-INFO` | `:L212`&ndash;`:L231` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/menu/MainMenuService.java` | `private populateHeaderInfo()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::populateHeaderInfoIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COMEN01C-R007` | `COMEN01C.cbl` | online | `BUILD-MENU-OPTIONS` | `:L236`&ndash;`:L277` | &mdash; | `COMEN02Y.cpy` | `service/menu/MainMenuService.java` | `private buildMenuOptions(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/MainMenuServiceTest.java::buildMenuOptionsIsMapped` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="coadm01c-cbl"></a>

### 5.21 `COADM01C.cbl`

**online &middot; F-002 Admin menu &middot; 268 physical lines &middot; 7 rows** (7 paragraphs)

Target files: `service/menu/AdminMenuService.java`, `controller/MenuController.java`.
Tests: `unit/service/AdminMenuServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COADM01C-R001` | `COADM01C.cbl` | online | `MAIN-PARA` | `:L75`&ndash;`:L110` | PERFORM `PROCESS-ENTER-KEY`, `RECEIVE-MENU-SCREEN`, `RETURN-TO-SIGNON-SCREEN`, `SEND-MENU-SCREEN`; EXEC CICS RETURN | `COCOM01Y.cpy`, `CSMSG01Y.cpy` | `service/menu/AdminMenuService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R002` | `COADM01C.cbl` | online | `PROCESS-ENTER-KEY` | `:L115`&ndash;`:L155` | PERFORM `SEND-MENU-SCREEN`; EXEC CICS XCTL | `COCOM01Y.cpy`, `COADM02Y.cpy` | `service/menu/AdminMenuService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R003` | `COADM01C.cbl` | online | `RETURN-TO-SIGNON-SCREEN` | `:L160`&ndash;`:L167` | EXEC CICS XCTL | `COCOM01Y.cpy` | `service/menu/AdminMenuService.java` | `private returnToSignOnScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R004` | `COADM01C.cbl` | online | `SEND-MENU-SCREEN` | `:L172`&ndash;`:L184` | PERFORM `BUILD-MENU-OPTIONS`, `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/menu/AdminMenuService.java` | `private sendMenuScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theRefusalCarriesTheExactLiteralAndNoCause` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R005` | `COADM01C.cbl` | online | `RECEIVE-MENU-SCREEN` | `:L189`&ndash;`:L197` | EXEC CICS RECEIVE | &mdash; | `service/menu/AdminMenuService.java` | `private receiveMenuScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R006` | `COADM01C.cbl` | online | `POPULATE-HEADER-INFO` | `:L202`&ndash;`:L221` | &mdash; | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/menu/AdminMenuService.java` | `private populateHeaderInfo()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COADM01C-R007` | `COADM01C.cbl` | online | `BUILD-MENU-OPTIONS` | `:L226`&ndash;`:L263` | &mdash; | `COADM02Y.cpy` | `service/menu/AdminMenuService.java` | `private buildMenuOptions()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AdminMenuServiceTest.java::theServiceDeclaresExactlyOnePrivateMethodPerParagraph` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cosgn00c-cbl"></a>

### 5.22 `COSGN00C.cbl`

**online &middot; F-001 Sign-on &middot; 260 physical lines &middot; 6 rows** (6 paragraphs)

Target files: `service/auth/AuthenticationService.java`, `security/JwtTokenProvider.java`.
Tests: `unit/service/AuthenticationServiceTest.java`, `unit/model/SignOnRequestTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-COSGN00C-R001` | `COSGN00C.cbl` | online | `MAIN-PARA` | `:L73`&ndash;`:L102` | PERFORM `PROCESS-ENTER-KEY`, `SEND-PLAIN-TEXT`, `SEND-SIGNON-SCREEN`; EXEC CICS RETURN | `CSMSG01Y.cpy`, `COCOM01Y.cpy` | `service/auth/AuthenticationService.java` | `private mainPara(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AuthenticationServiceTest.java::mainParaMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COSGN00C-R002` | `COSGN00C.cbl` | online | `PROCESS-ENTER-KEY` | `:L108`&ndash;`:L140` | PERFORM `READ-USER-SEC-FILE`, `SEND-SIGNON-SCREEN`; EXEC CICS RECEIVE | `COCOM01Y.cpy` | `service/auth/AuthenticationService.java` | `private processEnterKey(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AuthenticationServiceTest.java::processEnterKeyMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COSGN00C-R003` | `COSGN00C.cbl` | online | `SEND-SIGNON-SCREEN` | `:L145`&ndash;`:L157` | PERFORM `POPULATE-HEADER-INFO`; EXEC CICS SEND | &mdash; | `service/auth/AuthenticationService.java` | `private sendSignonScreen(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AuthenticationServiceTest.java::sendSignonScreenMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COSGN00C-R004` | `COSGN00C.cbl` | online | `SEND-PLAIN-TEXT` | `:L162`&ndash;`:L172` | EXEC CICS RETURN, SEND | &mdash; | `service/auth/AuthenticationService.java` | `private sendPlainText(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AuthenticationServiceTest.java::sendPlainTextMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COSGN00C-R005` | `COSGN00C.cbl` | online | `POPULATE-HEADER-INFO` | `:L177`&ndash;`:L204` | EXEC CICS ASSIGN | `CSDAT01Y.cpy`, `COTTL01Y.cpy` | `service/auth/AuthenticationService.java` | `private populateHeaderInfo()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/AuthenticationServiceTest.java::populateHeaderInfoMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-COSGN00C-R006` | `COSGN00C.cbl` | online | `READ-USER-SEC-FILE` | `:L209`&ndash;`:L257` | PERFORM `SEND-SIGNON-SCREEN`; EXEC CICS READ, XCTL | `COCOM01Y.cpy`, `CSUSR01Y.cpy` | `service/auth/AuthenticationService.java` | `private readUserSecFile(&hellip;)` | parity-preserved quirk | [`DL-PP-09`](DECISION_LOG.md#dl-pp-09) | `unit/service/AuthenticationServiceTest.java::readUserSecFileMaps` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `COSGN00C.cbl`.**

- **`TM-COSGN00C-R006` &middot; `READ-USER-SEC-FILE` &middot; `:L209`&ndash;`:L257` &middot; parity-preserved quirk &middot; [`DL-PP-09`](DECISION_LOG.md#dl-pp-09).** **BOTH** the user identifier AND the password are upper-cased before comparison, not just the identifier. BCrypt verification at strength 10 replaces the plaintext comparison; the ten seeded users are stored only as hashes.

<a id="cbstm03b-cbl"></a>

### 5.23 `CBSTM03B.CBL`

**batch &middot; F-019 Statement file access subprogram &middot; 230 physical lines &middot; 14 rows** (14 paragraphs)

Target files: `service/shared/FileService.java`.
Tests: `unit/service/FileServiceTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBSTM03B-R001` | `CBSTM03B.CBL` | batch | `0000-START` | `:L116`&ndash;`:L128` | PERFORM `1000-TRNXFILE-PROC`, `2000-XREFFILE-PROC`, `3000-CUSTFILE-PROC`, `4000-ACCTFILE-PROC`; THRU `1999-EXIT`, `2999-EXIT`; GO TO `9999-GOBACK` | &mdash; | `service/shared/FileService.java` | `private dispatch(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::anUnknownDdNameReportsSuccess` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R002` | `CBSTM03B.CBL` | batch | `9999-GOBACK` | `:L130`&ndash;`:L131` | &mdash; | &mdash; | `service/shared/FileService.java` | `private goback()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::everyParagraphHasItsOwnMethod` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R003` | `CBSTM03B.CBL` | batch | `1000-TRNXFILE-PROC` | `:L133`&ndash;`:L149` | GO TO `1900-EXIT` | &mdash; | `service/shared/FileService.java` | `private trnxfileProc(&hellip;)` | mechanism substitution | [`DL-DV-04`](DECISION_LOG.md#dl-dv-04) | `unit/service/FileServiceTest.java::theWriteAndRewriteAreImplementedByNoDataset` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R004` | `CBSTM03B.CBL` | batch | `1900-EXIT` | `:L151`&ndash;`:L152` | &mdash; | &mdash; | `service/shared/FileService.java` | `private trnxfileStatusEpilogue(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R005` | `CBSTM03B.CBL` | batch | `1999-EXIT` | `:L154`&ndash;`:L155` | &mdash; | &mdash; | `service/shared/FileService.java` | `private trnxfileTerminator()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R006` | `CBSTM03B.CBL` | batch | `2000-XREFFILE-PROC` | `:L157`&ndash;`:L173` | GO TO `2900-EXIT` | &mdash; | `service/shared/FileService.java` | `private xreffileProc(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::sequentialDatasetsImplementThePlainRead` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R007` | `CBSTM03B.CBL` | batch | `2900-EXIT` | `:L175`&ndash;`:L176` | &mdash; | &mdash; | `service/shared/FileService.java` | `private xreffileStatusEpilogue(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R008` | `CBSTM03B.CBL` | batch | `2999-EXIT` | `:L178`&ndash;`:L179` | &mdash; | &mdash; | `service/shared/FileService.java` | `private xreffileTerminator()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R009` | `CBSTM03B.CBL` | batch | `3000-CUSTFILE-PROC` | `:L181`&ndash;`:L198` | GO TO `3900-EXIT` | &mdash; | `service/shared/FileService.java` | `private custfileProc(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::aBlanketTwentyFiveByteKeySelectsADifferentRecord` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R010` | `CBSTM03B.CBL` | batch | `3900-EXIT` | `:L200`&ndash;`:L201` | &mdash; | &mdash; | `service/shared/FileService.java` | `private custfileStatusEpilogue(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R011` | `CBSTM03B.CBL` | batch | `3999-EXIT` | `:L203`&ndash;`:L204` | &mdash; | &mdash; | `service/shared/FileService.java` | `private custfileTerminator()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R012` | `CBSTM03B.CBL` | batch | `4000-ACCTFILE-PROC` | `:L206`&ndash;`:L223` | GO TO `4900-EXIT` | `CVACT01Y.cpy` | `service/shared/FileService.java` | `private acctfileProc(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::randomDatasetsImplementTheKeyedRead` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R013` | `CBSTM03B.CBL` | batch | `4900-EXIT` | `:L225`&ndash;`:L226` | &mdash; | &mdash; | `service/shared/FileService.java` | `private acctfileStatusEpilogue(&hellip;)` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBSTM03B-R014` | `CBSTM03B.CBL` | batch | `4999-EXIT` | `:L228`&ndash;`:L229` | &mdash; | &mdash; | `service/shared/FileService.java` | `private acctfileTerminator()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/FileServiceTest.java::theEpilogueAndTerminatorPairsAreEightDistinctMethods` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

**Fidelity notes for `CBSTM03B.CBL`.**

- **`TM-CBSTM03B-R003` &middot; `1000-TRNXFILE-PROC` &middot; `:L133`&ndash;`:L149` &middot; mechanism substitution &middot; [`DL-DV-04`](DECISION_LOG.md#dl-dv-04).** The shared call area is a DD name, a one-character operation, a two-character return code, a key, a key length and a 1000-byte payload. Every call site accepts EITHER `'00'` OR `'04'` as success, treats `'10'` as end of file and abends otherwise - the third accepted-control-path exception to the status mapping.

<a id="cbact01c-cbl"></a>

### 5.24 `CBACT01C.cbl`

**batch &middot; F-020 Account file sequential read (read-only) &middot; 193 physical lines &middot; 7 rows** (6 paragraphs + 1 synthetic entry row)

Target files: `batch/readers/AccountReader.java`.
Tests: `unit/batch/AccountReaderTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBACT01C-R001` | `CBACT01C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L70`&ndash;`:L87` | PERFORM `0000-ACCTFILE-OPEN`, `1000-ACCTFILE-GET-NEXT`, `9000-ACCTFILE-CLOSE` | `CVACT01Y.cpy` | `batch/readers/AccountReader.java` | `public open(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/AccountReaderTest.java::theRedundantGuardAnswersNull` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R002` | `CBACT01C.cbl` | batch | `1000-ACCTFILE-GET-NEXT` | `:L92`&ndash;`:L116` | PERFORM `1100-DISPLAY-ACCT-RECORD`, `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT01Y.cpy` | `batch/readers/AccountReader.java` | `private getNextAccountRecord()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::everyRowIsEmittedOnce` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R003` | `CBACT01C.cbl` | batch | `1100-DISPLAY-ACCT-RECORD` | `:L118`&ndash;`:L131` | &mdash; | `CVACT01Y.cpy` | `batch/readers/AccountReader.java` | `private displayAccountRecord(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::theDisplayIsGatedOnDebug` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R004` | `CBACT01C.cbl` | batch | `0000-ACCTFILE-OPEN` | `:L133`&ndash;`:L149` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/AccountReader.java` | `private openAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::openProbesTheRowCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R005` | `CBACT01C.cbl` | batch | `9000-ACCTFILE-CLOSE` | `:L151`&ndash;`:L167` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/AccountReader.java` | `private closeAccountFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::closeAnnouncesEndOfExecution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R006` | `CBACT01C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L169`&ndash;`:L173` | CALL `CEE3ABD` | &mdash; | `batch/readers/AccountReader.java` | `private abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::theAbendReturnCodeIsTwelve` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT01C-R007` | `CBACT01C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L176`&ndash;`:L189` | &mdash; | &mdash; | `batch/readers/AccountReader.java` | `private displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/AccountReaderTest.java::theFileStatusRenderingPrecedesTheAbend` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cbact02c-cbl"></a>

### 5.25 `CBACT02C.cbl`

**batch &middot; F-020 Card file sequential read (read-only) &middot; 178 physical lines &middot; 6 rows** (5 paragraphs + 1 synthetic entry row)

Target files: `batch/readers/CardReader.java`.
Tests: `unit/batch/CardReaderTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBACT02C-R001` | `CBACT02C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L70`&ndash;`:L87` | PERFORM `0000-CARDFILE-OPEN`, `1000-CARDFILE-GET-NEXT`, `9000-CARDFILE-CLOSE` | `CVACT02Y.cpy` | `batch/readers/CardReader.java` | `public open(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/CardReaderTest.java::theRedundantGuardAnswersNull` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT02C-R002` | `CBACT02C.cbl` | batch | `1000-CARDFILE-GET-NEXT` | `:L92`&ndash;`:L116` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT02Y.cpy` | `batch/readers/CardReader.java` | `private getNextCardRecord()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardReaderTest.java::everyRowIsEmittedOnce` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT02C-R003` | `CBACT02C.cbl` | batch | `0000-CARDFILE-OPEN` | `:L118`&ndash;`:L134` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/CardReader.java` | `private openCardFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardReaderTest.java::openProbesTheRowCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT02C-R004` | `CBACT02C.cbl` | batch | `9000-CARDFILE-CLOSE` | `:L136`&ndash;`:L152` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/CardReader.java` | `private closeCardFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardReaderTest.java::closeAnnouncesEndOfExecution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT02C-R005` | `CBACT02C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L154`&ndash;`:L158` | CALL `CEE3ABD` | &mdash; | `batch/readers/CardReader.java` | `private abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardReaderTest.java::theAbendReturnCodeIsTwelve` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT02C-R006` | `CBACT02C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L161`&ndash;`:L174` | &mdash; | &mdash; | `batch/readers/CardReader.java` | `private displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardReaderTest.java::theFileStatusRenderingPrecedesTheAbend` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cbact03c-cbl"></a>

### 5.26 `CBACT03C.cbl`

**batch &middot; F-020 Cross-reference sequential read (read-only) &middot; 178 physical lines &middot; 6 rows** (5 paragraphs + 1 synthetic entry row)

Target files: `batch/readers/CardCrossReferenceReader.java`.
Tests: `unit/batch/CardCrossReferenceReaderTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBACT03C-R001` | `CBACT03C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L70`&ndash;`:L87` | PERFORM `0000-XREFFILE-OPEN`, `1000-XREFFILE-GET-NEXT`, `9000-XREFFILE-CLOSE` | `CVACT03Y.cpy` | `batch/readers/CardCrossReferenceReader.java` | `public open(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/CardCrossReferenceReaderTest.java::theRedundantGuardAnswersNull` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT03C-R002` | `CBACT03C.cbl` | batch | `1000-XREFFILE-GET-NEXT` | `:L92`&ndash;`:L116` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | `CVACT03Y.cpy` | `batch/readers/CardCrossReferenceReader.java` | `private getNextCrossReferenceRecord()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardCrossReferenceReaderTest.java::everyRowIsEmittedOnce` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT03C-R003` | `CBACT03C.cbl` | batch | `0000-XREFFILE-OPEN` | `:L118`&ndash;`:L134` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/CardCrossReferenceReader.java` | `private openCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardCrossReferenceReaderTest.java::openProbesTheRowCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT03C-R004` | `CBACT03C.cbl` | batch | `9000-XREFFILE-CLOSE` | `:L136`&ndash;`:L152` | PERFORM `9910-DISPLAY-IO-STATUS`, `9999-ABEND-PROGRAM` | &mdash; | `batch/readers/CardCrossReferenceReader.java` | `private closeCrossReferenceFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardCrossReferenceReaderTest.java::closeAnnouncesEndOfExecution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT03C-R005` | `CBACT03C.cbl` | batch | `9999-ABEND-PROGRAM` | `:L154`&ndash;`:L158` | CALL `CEE3ABD` | &mdash; | `batch/readers/CardCrossReferenceReader.java` | `private abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardCrossReferenceReaderTest.java::theAbendReturnCodeIsTwelve` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBACT03C-R006` | `CBACT03C.cbl` | batch | `9910-DISPLAY-IO-STATUS` | `:L161`&ndash;`:L174` | &mdash; | &mdash; | `batch/readers/CardCrossReferenceReader.java` | `private displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CardCrossReferenceReaderTest.java::theFileStatusRenderingPrecedesTheAbend` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="cbcus01c-cbl"></a>

### 5.27 `CBCUS01C.cbl`

**batch &middot; F-020 Customer file sequential read (read-only) &middot; 178 physical lines &middot; 6 rows** (5 paragraphs + 1 synthetic entry row)

Target files: `batch/readers/CustomerReader.java`.
Tests: `unit/batch/CustomerReaderTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CBCUS01C-R001` | `CBCUS01C.cbl` | batch | `PROCEDURE-DIVISION-ENTRY` | `:L70`&ndash;`:L87` | PERFORM `0000-CUSTFILE-OPEN`, `1000-CUSTFILE-GET-NEXT`, `9000-CUSTFILE-CLOSE` | &mdash; | `batch/readers/CustomerReader.java` | `public open(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/batch/CustomerReaderTest.java::theRedundantGuardAnswersNull` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBCUS01C-R002` | `CBCUS01C.cbl` | batch | `1000-CUSTFILE-GET-NEXT` | `:L92`&ndash;`:L116` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/readers/CustomerReader.java` | `private getNextCustomerRecord()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CustomerReaderTest.java::everyRowIsEmittedOnce` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBCUS01C-R003` | `CBCUS01C.cbl` | batch | `0000-CUSTFILE-OPEN` | `:L118`&ndash;`:L134` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/readers/CustomerReader.java` | `private openCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CustomerReaderTest.java::openProbesTheRowCount` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBCUS01C-R004` | `CBCUS01C.cbl` | batch | `9000-CUSTFILE-CLOSE` | `:L136`&ndash;`:L152` | PERFORM `Z-ABEND-PROGRAM`, `Z-DISPLAY-IO-STATUS` | &mdash; | `batch/readers/CustomerReader.java` | `private closeCustomerFile()` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CustomerReaderTest.java::closeAnnouncesEndOfExecution` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBCUS01C-R005` | `CBCUS01C.cbl` | batch | `Z-ABEND-PROGRAM` | `:L154`&ndash;`:L158` | CALL `CEE3ABD` | &mdash; | `batch/readers/CustomerReader.java` | `private abendProgram(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CustomerReaderTest.java::theAbendReturnCodeIsTwelve` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CBCUS01C-R006` | `CBCUS01C.cbl` | batch | `Z-DISPLAY-IO-STATUS` | `:L161`&ndash;`:L174` | &mdash; | &mdash; | `batch/readers/CustomerReader.java` | `private displayIoStatus(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/batch/CustomerReaderTest.java::theFileStatusRenderingPrecedesTheAbend` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="csutldtc-cbl"></a>

### 5.28 `CSUTLDTC.cbl`

**utility &middot; F-022 Date validation utility &middot; 157 physical lines &middot; 3 rows** (2 paragraphs + 1 synthetic entry row)

Target files: `service/shared/DateValidationService.java`.
Tests: `unit/validation/DateValidationServiceTest.java`, `unit/validation/LanguageEnvironmentDateContractTest.java`.

| Row ID | Source | Mode | Paragraph / label | Lines | Calls / relationship | COPY / layout | Target file | Target method | Classification | Decision | Test | Evidence | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `TM-CSUTLDTC-R001` | `CSUTLDTC.cbl` | utility | `PROCEDURE-DIVISION-ENTRY` | `:L88`&ndash;`:L102` | PERFORM `A000-MAIN`; THRU `A000-MAIN-EXIT` | `CSUTLDWY.cpy` | `service/shared/DateValidationService.java` | `public validate(&hellip;)` | mechanism substitution | [`DL-MS-07`](DECISION_LOG.md#dl-ms-07) | `unit/validation/DateValidationServiceTest.java::theLiteralsAreCarriedUnpaddedAndPaddedOnlyWhenComposed` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CSUTLDTC-R002` | `CSUTLDTC.cbl` | utility | `A000-MAIN` | `:L103`&ndash;`:L151` | CALL `CEEDAYS` | `CSUTLDWY.cpy` | `service/shared/DateValidationService.java` | `private a000Main(&hellip;)` | direct | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/validation/DateValidationServiceTest.java::theSuccessConditionIsNamedForItsOpposite` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |
| `TM-CSUTLDTC-R003` | `CSUTLDTC.cbl` | utility | `A000-MAIN-EXIT` | `:L152`&ndash;`:L154` | &mdash; | &mdash; | `service/shared/DateValidationService.java` | `private a000MainExit()` | mechanism substitution | [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) | `unit/service/DateValidationServiceTest.java::theBareExitParagraphsAreMappedNotDeleted` | [Gate 7](docs/validation-gates.md#gate-7) | `Target-verified` |

<a id="reverse"></a>

## 6. Reverse index &mdash; Java target to source paragraph

This is the backward half of the bidirectional contract. **Every mapped Java member appears
exactly once below**, with the source row or rows it reproduces. A Java member carrying legacy
behaviour and absent from this index would be an orphan; there are none, and
[section 6.2](#reverse-enumerated) is the enumeration that establishes it rather than assuming it &mdash;
including the twenty-six rows it caught pointing at a trace-only marker instead of at the member that
does the work.

| | |
|---|---|
| Distinct Java targets indexed | **534** |
| Source rows they cover | **537** |
| Targets serving exactly one row | 531 |
| Targets serving more than one row | **3**, all declared in [section 6.1](#reverse-multi) |
| Declared methods in the whole main tree, enumerated and partitioned | **3,037** across 158 files &mdash; [section 6.2](#reverse-enumerated) |
| Orphan Java business methods | **0**, derived from that partition rather than asserted |

<a id="reverse-multi"></a>

### 6.1 The three declared many-to-one targets

Rule C4 forbids consolidating paragraphs. These three targets are the only permitted exceptions,
and each is a consequence of a specific recorded decision rather than a shortcut. All three are
in the statement program, and no other program has any.

| Target | Source rows | Why one member is correct |
|---|---|---|
| `batch/jobs/StatementGenerationJob.java` `initialiseStatementRun()` | `TM-CBSTM03A-R002` `0000-START`, `TM-CBSTM03A-R015` `8100-FILE-OPEN` | The `ALTER … TO PROCEED TO` dispatch and the paragraph it rewrites are **one** deterministic initialisation pipeline once static flow analysis is applied. Modelling them as two members would invent a dispatch step the machine does not have &mdash; [`DL-DV-02`](DECISION_LOG.md#dl-dv-02) |
| `batch/processors/StatementProcessor.java` `writeHtmlHeader(…)` | `TM-CBSTM03A-R010` `5100-WRITE-HTML-HEADER`, `TM-CBSTM03A-R011` `5100-EXIT` | `5100-EXIT` is the `PERFORM … THRU` landing label for the paragraph above it and holds no logic. A Java `return` **is** that label; a separate empty member would be invented structure &mdash; [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) |
| `batch/processors/StatementProcessor.java` `writeHtmlNameAddressBasics(…)` | `TM-CBSTM03A-R012` `5200-WRITE-HTML-NMADBS`, `TM-CBSTM03A-R013` `5200-EXIT` | Same construct as the row above &mdash; [`DL-MS-02`](DECISION_LOG.md#dl-ms-02) |

Note the contrast that makes this principled rather than convenient: **where the implementation
does give an exit label its own member, this page maps it one-to-one** &mdash; `A000-MAIN-EXIT` to
`a000MainExit()`, `9600-WRITE-PROCESSING-EXIT` to `writeProcessing9600Exit()`,
`9700-CHECK-CHANGE-IN-REC-EXIT` to `checkChangeInRecord9700Exit()`, and both occurrences of the
duplicated `0000-MAIN-EXIT` to `mainExit0000AtLine408()` and `mainExit0000AtLine411()`. 122 of the
125 exit-only rows have their own dedicated member.

<a id="reverse-enumerated"></a>

### 6.2 The enumeration behind &ldquo;zero orphans&rdquo;

The claim above &mdash; **0 orphan Java business methods** &mdash; was previously a check on the
*forward* targets: it confirmed that every row's target resolves, which cannot by construction find a
Java member the matrix never mentions. This section replaces that with the enumeration the claim
needs: **every declared method in `src/main/java/com/cardemo/**` is counted and placed in exactly one
bucket.** The partition is disjoint and total, so a member cannot be quietly left out of it.

| | |
|---|---:|
| Java files in the main tree | **158** |
| Declared methods in them (constructors excluded, they map to no paragraph) | **3,037** |
| **A** &mdash; paragraph-mapped: named by a row of this page | **537** |
| **B** &mdash; framework-facing: carries `@Bean`, `@Override`, a mapping or transaction annotation, or implements a framework callback (`afterPropertiesSet`, `doFilterInternal`, `decide`, `read`, `beforeStep`, &hellip;) | **479** |
| **C** &mdash; value-type contract: an accessor or an `Object`/record/enum contract member (`get&hellip;`, `set&hellip;`, `is&hellip;`, `equals`, `hashCode`, `toString`, `compareTo`, `values`, `valueOf`, `builder`) | **554** |
| **D** &mdash; guard, parse and format helper: `require&hellip;`, `validate&hellip;`, `check&hellip;`, `parse&hellip;`, `format&hellip;`, `mask&hellip;`, `resolve&hellip;` and their kin | **450** |
| **E** &mdash; residue: everything the four rules above do not claim | **1,017** |
| **Sum** | **3,037** |

**What bucket E is, and why it is not a list of orphans.** It is the target-native working set:
per-invocation state carriers and their members, DTO projection helpers, private line-assembly and
attribute helpers, log-emission helpers, bean-wiring plumbing and the arithmetic helpers that
several paragraphs share. A member belongs in E precisely because **no source paragraph claims
it** &mdash; the source has no counterpart to claim it with. The proof that E holds no unmapped
*paragraph* is not this table but the per-bean paragraph-correspondence tests, which are the
strongest available check and already exist: each asserts, by reflection over one bean's declared
methods, that its private set contains exactly the paragraph roster for its program &mdash;
`AccountViewServiceTest::everySourceParagraphMapsToItsOwnPrivateMethod`,
`CardListServiceTest::theBeanDeclaresOnePrivateMethodPerCobolParagraph`,
`UserUpdateServiceTest::allElevenParagraphsHaveAPrivateCounterpart` and their siblings across
twenty programs. Those tests fail if a paragraph loses its method; this table shows what else is in
the tree beside them.

**What the enumeration actually found, and it was not nothing.** Twenty-six rows of
[section 5.12](#cbtrn03c-cbl) named a method in `batch/jobs/TransactionReportJob.java` whose entire
body is one `LOG.trace` call. The job holds **25** such paragraph-named markers &mdash;
`dateParmRead0550`, `transactionFileGetNext1000`, `writeTransactionReport1100`, `writePageTotals1110`,
`writeAccountTotals1120`, `writeGrandTotals1110`, `writeHeaders1120`, `writeReportRecord1111`,
`writeDetail1120`, `transactionFileOpen0000`, `reportFileOpen0100`, `cardCrossReferenceOpen0200`,
`transactionTypeOpen0300`, `transactionCategoryOpen0400`, `dateParameterOpen0500`,
`lookupCrossReference1500a`, `lookupTransactionType1500b`, `lookupTransactionCategory1500c`,
`transactionFileClose9000`, `reportFileClose9100`, `cardCrossReferenceClose9200`,
`transactionTypeClose9300`, `transactionCategoryClose9400`, `dateParameterClose9500` and
`abendProgram9999` &mdash; each recording at step level that the paragraph is delegated. **They are
documentation, not behaviour**, and a row pointing at one told a reader that a trace statement
reproduces a report total. Every one of those rows now names the member that performs the work:
the twelve dataset open and close paragraphs and the nine write and lookup paragraphs in
`batch/processors/TransactionReportProcessor.java`, the sequential read in
`batch/readers/TransactionBackupReader.java`, and the four-character status rendering in
`service/shared/FileStatusMapper.java`. The markers stay in the job, because the step-level
delegation is real and worth recording &mdash; they are simply **not the mapped target**, which is
why they sit in bucket E. The distinct-target census is unchanged at 534, because the twenty-six
targets were exchanged one for one.

### 6.3 Full index

Grouped by target file, then by member name.

#### `batch/jobs/DailyTransactionPostingJob.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBTRN02C.cbl` `9999-ABEND-PROGRAM` | `TM-CBTRN02C-R026` |
| `closeAccountFile()` | private | `CBTRN02C.cbl` `9400-ACCTFILE-CLOSE` | `TM-CBTRN02C-R023` |
| `closeCategoryBalanceFile()` | private | `CBTRN02C.cbl` `9500-TCATBALF-CLOSE` | `TM-CBTRN02C-R024` |
| `closeCrossReferenceFile()` | private | `CBTRN02C.cbl` `9200-XREFFILE-CLOSE` | `TM-CBTRN02C-R021` |
| `closePreFlightAccountFile()` | private | `CBTRN01C.cbl` `9400-ACCTFILE-CLOSE` | `TM-CBTRN01C-R015` |
| `closePreFlightCardFile()` | private | `CBTRN01C.cbl` `9300-CARDFILE-CLOSE` | `TM-CBTRN01C-R014` |
| `closePreFlightCrossReferenceFile()` | private | `CBTRN01C.cbl` `9200-XREFFILE-CLOSE` | `TM-CBTRN01C-R013` |
| `closePreFlightCustomerFile()` | private | `CBTRN01C.cbl` `9100-CUSTFILE-CLOSE` | `TM-CBTRN01C-R012` |
| `closePreFlightDailyTransactionFile()` | private | `CBTRN01C.cbl` `9000-DALYTRAN-CLOSE` | `TM-CBTRN01C-R011` |
| `closePreFlightTransactionFile()` | private | `CBTRN01C.cbl` `9500-TRANFILE-CLOSE` | `TM-CBTRN01C-R016` |
| `closeTransactionFile()` | private | `CBTRN02C.cbl` `9100-TRANFILE-CLOSE` | `TM-CBTRN02C-R020` |
| `dailyTransactionPostingStep()` | public | `CBTRN02C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBTRN02C-R001` |
| `displayIoStatus()` | private | `CBTRN02C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBTRN02C-R027` |
| `openAccountFile()` | private | `CBTRN02C.cbl` `0400-ACCTFILE-OPEN` | `TM-CBTRN02C-R006` |
| `openCategoryBalanceFile()` | private | `CBTRN02C.cbl` `0500-TCATBALF-OPEN` | `TM-CBTRN02C-R007` |
| `openCrossReferenceFile()` | private | `CBTRN02C.cbl` `0200-XREFFILE-OPEN` | `TM-CBTRN02C-R004` |
| `openPreFlightAccountFile()` | private | `CBTRN01C.cbl` `0400-ACCTFILE-OPEN` | `TM-CBTRN01C-R009` |
| `openPreFlightCardFile()` | private | `CBTRN01C.cbl` `0300-CARDFILE-OPEN` | `TM-CBTRN01C-R008` |
| `openPreFlightCrossReferenceFile()` | private | `CBTRN01C.cbl` `0200-XREFFILE-OPEN` | `TM-CBTRN01C-R007` |
| `openPreFlightCustomerFile()` | private | `CBTRN01C.cbl` `0100-CUSTFILE-OPEN` | `TM-CBTRN01C-R006` |
| `openPreFlightDailyTransactionFile()` | private | `CBTRN01C.cbl` `0000-DALYTRAN-OPEN` | `TM-CBTRN01C-R005` |
| `openPreFlightTransactionFile()` | private | `CBTRN01C.cbl` `0500-TRANFILE-OPEN` | `TM-CBTRN01C-R010` |
| `openTransactionFile()` | private | `CBTRN02C.cbl` `0100-TRANFILE-OPEN` | `TM-CBTRN02C-R003` |
| `preFlightAbendProgram()` | private | `CBTRN01C.cbl` `Z-ABEND-PROGRAM` | `TM-CBTRN01C-R017` |
| `preFlightDalytranGetNext()` | private | `CBTRN01C.cbl` `1000-DALYTRAN-GET-NEXT` | `TM-CBTRN01C-R002` |
| `preFlightDisplayIoStatus()` | private | `CBTRN01C.cbl` `Z-DISPLAY-IO-STATUS` | `TM-CBTRN01C-R018` |
| `preFlightLookupXref()` | private | `CBTRN01C.cbl` `2000-LOOKUP-XREF` | `TM-CBTRN01C-R003` |
| `preFlightMainPara()` | private | `CBTRN01C.cbl` `MAIN-PARA` | `TM-CBTRN01C-R001` |
| `preFlightReadAccount()` | private | `CBTRN01C.cbl` `3000-READ-ACCOUNT` | `TM-CBTRN01C-R004` |

#### `batch/jobs/InterestCalculationJob.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBACT04C.cbl` `9999-ABEND-PROGRAM` | `TM-CBACT04C-R022` |
| `categoryBalanceReader()` | private | `CBACT04C.cbl` `1000-TCATBALF-GET-NEXT` | `TM-CBACT04C-R007` |
| `closeAccountFile()` | private | `CBACT04C.cbl` `9300-ACCTFILE-CLOSE` | `TM-CBACT04C-R019` |
| `closeCrossReferenceFile()` | private | `CBACT04C.cbl` `9100-XREFFILE-CLOSE` | `TM-CBACT04C-R017` |
| `closeDisclosureGroupFile()` | private | `CBACT04C.cbl` `9200-DISCGRP-CLOSE` | `TM-CBACT04C-R018` |
| `closeTransactionCategoryBalanceFile()` | private | `CBACT04C.cbl` `9000-TCATBALF-CLOSE` | `TM-CBACT04C-R016` |
| `closeTransactionFile()` | private | `CBACT04C.cbl` `9400-TRANFILE-CLOSE` | `TM-CBACT04C-R020` |
| `displayIoStatus()` | private | `CBACT04C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBACT04C-R023` |
| `interestCalculationStep()` | public | `CBACT04C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBACT04C-R001` |
| `openAccountFile()` | private | `CBACT04C.cbl` `0300-ACCTFILE-OPEN` | `TM-CBACT04C-R005` |
| `openCrossReferenceFile()` | private | `CBACT04C.cbl` `0100-XREFFILE-OPEN` | `TM-CBACT04C-R003` |
| `openDisclosureGroupFile()` | private | `CBACT04C.cbl` `0200-DISCGRP-OPEN` | `TM-CBACT04C-R004` |
| `openTransactionCategoryBalanceFile()` | private | `CBACT04C.cbl` `0000-TCATBALF-OPEN` | `TM-CBACT04C-R002` |
| `openTransactionFile()` | private | `CBACT04C.cbl` `0400-TRANFILE-OPEN` | `TM-CBACT04C-R006` |

#### `batch/jobs/StatementGenerationJob.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `crossReferenceReader()` | private | `CBSTM03A.CBL` `1000-XREFFILE-GET-NEXT` | `TM-CBSTM03A-R005` |
| `emitStepGoback()` | private | `CBSTM03A.CBL` `9999-GOBACK` | `TM-CBSTM03A-R004` |
| `initialiseStatementRun()` | private | `CBSTM03A.CBL` `0000-START`, `CBSTM03A.CBL` `8100-FILE-OPEN` | `TM-CBSTM03A-R002`, `TM-CBSTM03A-R015` |
| `statementGenerationEmitStep()` | public | `CBSTM03A.CBL` `1000-MAINLINE` | `TM-CBSTM03A-R003` |

#### `batch/jobs/TransactionReportJob.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram9999()` | private | `CBTRN03C.cbl` `9999-ABEND-PROGRAM` | `TM-CBTRN03C-R026` |
| `cardCrossReferenceClose9200()` | private | `CBTRN03C.cbl` `9200-CARDXREF-CLOSE` | `TM-CBTRN03C-R022` |
| `cardCrossReferenceOpen0200()` | private | `CBTRN03C.cbl` `0200-CARDXREF-OPEN` | `TM-CBTRN03C-R013` |
| `dateParameterClose9500()` | private | `CBTRN03C.cbl` `9500-DATEPARM-CLOSE` | `TM-CBTRN03C-R025` |
| `dateParameterOpen0500()` | private | `CBTRN03C.cbl` `0500-DATEPARM-OPEN` | `TM-CBTRN03C-R016` |
| `dateParmRead0550()` | private | `CBTRN03C.cbl` `0550-DATEPARM-READ` | `TM-CBTRN03C-R002` |
| `displayIoStatus9910()` | private | `CBTRN03C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBTRN03C-R027` |
| `lookupCrossReference1500a()` | private | `CBTRN03C.cbl` `1500-A-LOOKUP-XREF` | `TM-CBTRN03C-R017` |
| `lookupTransactionCategory1500c()` | private | `CBTRN03C.cbl` `1500-C-LOOKUP-TRANCATG` | `TM-CBTRN03C-R019` |
| `lookupTransactionType1500b()` | private | `CBTRN03C.cbl` `1500-B-LOOKUP-TRANTYPE` | `TM-CBTRN03C-R018` |
| `mainlineProcedureDivision()` | private | `CBTRN03C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBTRN03C-R001` |
| `reportFileClose9100()` | private | `CBTRN03C.cbl` `9100-REPTFILE-CLOSE` | `TM-CBTRN03C-R021` |
| `reportFileOpen0100()` | private | `CBTRN03C.cbl` `0100-REPTFILE-OPEN` | `TM-CBTRN03C-R012` |
| `transactionCategoryClose9400()` | private | `CBTRN03C.cbl` `9400-TRANCATG-CLOSE` | `TM-CBTRN03C-R024` |
| `transactionCategoryOpen0400()` | private | `CBTRN03C.cbl` `0400-TRANCATG-OPEN` | `TM-CBTRN03C-R015` |
| `transactionFileClose9000()` | private | `CBTRN03C.cbl` `9000-TRANFILE-CLOSE` | `TM-CBTRN03C-R020` |
| `transactionFileGetNext1000()` | private | `CBTRN03C.cbl` `1000-TRANFILE-GET-NEXT` | `TM-CBTRN03C-R003` |
| `transactionFileOpen0000()` | private | `CBTRN03C.cbl` `0000-TRANFILE-OPEN` | `TM-CBTRN03C-R011` |
| `transactionTypeClose9300()` | private | `CBTRN03C.cbl` `9300-TRANTYPE-CLOSE` | `TM-CBTRN03C-R023` |
| `transactionTypeOpen0300()` | private | `CBTRN03C.cbl` `0300-TRANTYPE-OPEN` | `TM-CBTRN03C-R014` |
| `writeAccountTotals1120()` | private | `CBTRN03C.cbl` `1120-WRITE-ACCOUNT-TOTALS` | `TM-CBTRN03C-R006` |
| `writeDetail1120()` | private | `CBTRN03C.cbl` `1120-WRITE-DETAIL` | `TM-CBTRN03C-R010` |
| `writeGrandTotals1110()` | private | `CBTRN03C.cbl` `1110-WRITE-GRAND-TOTALS` | `TM-CBTRN03C-R007` |
| `writeHeaders1120()` | private | `CBTRN03C.cbl` `1120-WRITE-HEADERS` | `TM-CBTRN03C-R008` |
| `writePageTotals1110()` | private | `CBTRN03C.cbl` `1110-WRITE-PAGE-TOTALS` | `TM-CBTRN03C-R005` |
| `writeReportRecord1111()` | private | `CBTRN03C.cbl` `1111-WRITE-REPORT-REC` | `TM-CBTRN03C-R009` |
| `writeTransactionReport1100()` | private | `CBTRN03C.cbl` `1100-WRITE-TRANSACTION-REPORT` | `TM-CBTRN03C-R004` |

#### `batch/processors/InterestCalculationProcessor.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `computeFees()` | private | `CBACT04C.cbl` `1400-COMPUTE-FEES` | `TM-CBACT04C-R015` |
| `computeInterest()` | private | `CBACT04C.cbl` `1300-COMPUTE-INTEREST` | `TM-CBACT04C-R013` |
| `db2FormatTimestamp()` | private | `CBACT04C.cbl` `Z-GET-DB2-FORMAT-TIMESTAMP` | `TM-CBACT04C-R021` |
| `getAccountData()` | private | `CBACT04C.cbl` `1100-GET-ACCT-DATA` | `TM-CBACT04C-R009` |
| `getCrossReferenceData()` | private | `CBACT04C.cbl` `1110-GET-XREF-DATA` | `TM-CBACT04C-R010` |
| `getDefaultInterestRate()` | private | `CBACT04C.cbl` `1200-A-GET-DEFAULT-INT-RATE` | `TM-CBACT04C-R012` |
| `getInterestRate()` | private | `CBACT04C.cbl` `1200-GET-INTEREST-RATE` | `TM-CBACT04C-R011` |
| `updateAccount()` | private | `CBACT04C.cbl` `1050-UPDATE-ACCOUNT` | `TM-CBACT04C-R008` |
| `writeTransaction()` | private | `CBACT04C.cbl` `1300-B-WRITE-TX` | `TM-CBACT04C-R014` |

#### `batch/processors/StatementProcessor.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abend()` | private | `CBSTM03A.CBL` `9999-ABEND-PROGRAM` | `TM-CBSTM03A-R026` |
| `advanceToCardGroup()` | private | `CBSTM03A.CBL` `4000-TRNXFILE-GET` | `TM-CBSTM03A-R008` |
| `closeAccountFile()` | private | `CBSTM03A.CBL` `9400-ACCTFILE-CLOSE` | `TM-CBSTM03A-R025` |
| `closeCrossReferenceFile()` | private | `CBSTM03A.CBL` `9200-XREFFILE-CLOSE` | `TM-CBSTM03A-R023` |
| `closeCustomerFile()` | private | `CBSTM03A.CBL` `9300-CUSTFILE-CLOSE` | `TM-CBSTM03A-R024` |
| `closeTransactionFile()` | private | `CBSTM03A.CBL` `9100-TRNXFILE-CLOSE` | `TM-CBSTM03A-R022` |
| `createStatement()` | private | `CBSTM03A.CBL` `5000-CREATE-STATEMENT` | `TM-CBSTM03A-R009` |
| `initialiseTransactionStream()` | private | `CBSTM03A.CBL` `8599-EXIT` | `TM-CBSTM03A-R021` |
| `openAccountFile()` | private | `CBSTM03A.CBL` `8400-ACCTFILE-OPEN` | `TM-CBSTM03A-R019` |
| `openAndPrimeTransactionFile()` | private | `CBSTM03A.CBL` `8100-TRNXFILE-OPEN` | `TM-CBSTM03A-R016` |
| `openCrossReferenceFile()` | private | `CBSTM03A.CBL` `8200-XREFFILE-OPEN` | `TM-CBSTM03A-R017` |
| `openCustomerFile()` | private | `CBSTM03A.CBL` `8300-CUSTFILE-OPEN` | `TM-CBSTM03A-R018` |
| `readAccountRecord()` | private | `CBSTM03A.CBL` `3000-ACCTFILE-GET` | `TM-CBSTM03A-R007` |
| `readCustomerRecord()` | private | `CBSTM03A.CBL` `2000-CUSTFILE-GET` | `TM-CBSTM03A-R006` |
| `readNextCardGroup()` | private | `CBSTM03A.CBL` `8500-READTRNX-READ` | `TM-CBSTM03A-R020` |
| `writeHtmlHeader()` | private | `CBSTM03A.CBL` `5100-WRITE-HTML-HEADER`, `CBSTM03A.CBL` `5100-EXIT` | `TM-CBSTM03A-R010`, `TM-CBSTM03A-R011` |
| `writeHtmlNameAddressBasics()` | private | `CBSTM03A.CBL` `5200-WRITE-HTML-NMADBS`, `CBSTM03A.CBL` `5200-EXIT` | `TM-CBSTM03A-R012`, `TM-CBSTM03A-R013` |
| `writeTransaction()` | private | `CBSTM03A.CBL` `6000-WRITE-TRANS` | `TM-CBSTM03A-R014` |

#### `batch/processors/TransactionPostingProcessor.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `createTcatbalRec()` | private | `CBTRN02C.cbl` `2700-A-CREATE-TCATBAL-REC` | `TM-CBTRN02C-R015` |
| `getDb2FormatTimestamp()` | private | `CBTRN02C.cbl` `Z-GET-DB2-FORMAT-TIMESTAMP` | `TM-CBTRN02C-R025` |
| `lookupAcct()` | private | `CBTRN02C.cbl` `1500-B-LOOKUP-ACCT` | `TM-CBTRN02C-R011` |
| `lookupXref()` | private | `CBTRN02C.cbl` `1500-A-LOOKUP-XREF` | `TM-CBTRN02C-R010` |
| `postTransaction()` | private | `CBTRN02C.cbl` `2000-POST-TRANSACTION` | `TM-CBTRN02C-R012` |
| `updateAccountRec()` | private | `CBTRN02C.cbl` `2800-UPDATE-ACCOUNT-REC` | `TM-CBTRN02C-R017` |
| `updateTcatbal()` | private | `CBTRN02C.cbl` `2700-UPDATE-TCATBAL` | `TM-CBTRN02C-R014` |
| `updateTcatbalRec()` | private | `CBTRN02C.cbl` `2700-B-UPDATE-TCATBAL-REC` | `TM-CBTRN02C-R016` |
| `validateTran()` | private | `CBTRN02C.cbl` `1500-VALIDATE-TRAN` | `TM-CBTRN02C-R009` |

#### `batch/readers/AccountReader.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBACT01C.cbl` `9999-ABEND-PROGRAM` | `TM-CBACT01C-R006` |
| `closeAccountFile()` | private | `CBACT01C.cbl` `9000-ACCTFILE-CLOSE` | `TM-CBACT01C-R005` |
| `displayAccountRecord()` | private | `CBACT01C.cbl` `1100-DISPLAY-ACCT-RECORD` | `TM-CBACT01C-R003` |
| `displayIoStatus()` | private | `CBACT01C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBACT01C-R007` |
| `getNextAccountRecord()` | private | `CBACT01C.cbl` `1000-ACCTFILE-GET-NEXT` | `TM-CBACT01C-R002` |
| `open()` | public | `CBACT01C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBACT01C-R001` |
| `openAccountFile()` | private | `CBACT01C.cbl` `0000-ACCTFILE-OPEN` | `TM-CBACT01C-R004` |

#### `batch/readers/CardCrossReferenceReader.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBACT03C.cbl` `9999-ABEND-PROGRAM` | `TM-CBACT03C-R005` |
| `closeCrossReferenceFile()` | private | `CBACT03C.cbl` `9000-XREFFILE-CLOSE` | `TM-CBACT03C-R004` |
| `displayIoStatus()` | private | `CBACT03C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBACT03C-R006` |
| `getNextCrossReferenceRecord()` | private | `CBACT03C.cbl` `1000-XREFFILE-GET-NEXT` | `TM-CBACT03C-R002` |
| `open()` | public | `CBACT03C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBACT03C-R001` |
| `openCrossReferenceFile()` | private | `CBACT03C.cbl` `0000-XREFFILE-OPEN` | `TM-CBACT03C-R003` |

#### `batch/readers/CardReader.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBACT02C.cbl` `9999-ABEND-PROGRAM` | `TM-CBACT02C-R005` |
| `closeCardFile()` | private | `CBACT02C.cbl` `9000-CARDFILE-CLOSE` | `TM-CBACT02C-R004` |
| `displayIoStatus()` | private | `CBACT02C.cbl` `9910-DISPLAY-IO-STATUS` | `TM-CBACT02C-R006` |
| `getNextCardRecord()` | private | `CBACT02C.cbl` `1000-CARDFILE-GET-NEXT` | `TM-CBACT02C-R002` |
| `open()` | public | `CBACT02C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBACT02C-R001` |
| `openCardFile()` | private | `CBACT02C.cbl` `0000-CARDFILE-OPEN` | `TM-CBACT02C-R003` |

#### `batch/readers/CustomerReader.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendProgram()` | private | `CBCUS01C.cbl` `Z-ABEND-PROGRAM` | `TM-CBCUS01C-R005` |
| `closeCustomerFile()` | private | `CBCUS01C.cbl` `9000-CUSTFILE-CLOSE` | `TM-CBCUS01C-R004` |
| `displayIoStatus()` | private | `CBCUS01C.cbl` `Z-DISPLAY-IO-STATUS` | `TM-CBCUS01C-R006` |
| `getNextCustomerRecord()` | private | `CBCUS01C.cbl` `1000-CUSTFILE-GET-NEXT` | `TM-CBCUS01C-R002` |
| `open()` | public | `CBCUS01C.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CBCUS01C-R001` |
| `openCustomerFile()` | private | `CBCUS01C.cbl` `0000-CUSTFILE-OPEN` | `TM-CBCUS01C-R003` |

#### `batch/readers/DailyTransactionReader.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `closeDailyTransactionFile()` | private | `CBTRN02C.cbl` `9000-DALYTRAN-CLOSE` | `TM-CBTRN02C-R019` |
| `getNextDailyTransaction()` | private | `CBTRN02C.cbl` `1000-DALYTRAN-GET-NEXT` | `TM-CBTRN02C-R008` |
| `openDailyTransactionFile()` | private | `CBTRN02C.cbl` `0000-DALYTRAN-OPEN` | `TM-CBTRN02C-R002` |

#### `batch/writers/RejectWriter.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `closeRejsFile()` | private | `CBTRN02C.cbl` `9300-DALYREJS-CLOSE` | `TM-CBTRN02C-R022` |
| `openGenerationStream()` | private | `CBTRN02C.cbl` `0300-DALYREJS-OPEN` | `TM-CBTRN02C-R005` |
| `writeReject()` | public | `CBTRN02C.cbl` `2500-WRITE-REJECT-REC` | `TM-CBTRN02C-R013` |

#### `batch/writers/StatementWriter.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `openStatementOutputs()` | public | `CBSTM03A.CBL` `PROCEDURE-DIVISION-ENTRY` | `TM-CBSTM03A-R001` |

#### `batch/writers/TransactionWriter.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `writeTransactionFile()` | private | `CBTRN02C.cbl` `2900-WRITE-TRANSACTION-FILE` | `TM-CBTRN02C-R018` |

#### `service/account/AccountUpdateService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendRoutine()` | private | `COACTUPC.cbl` `ABEND-ROUTINE` | `TM-COACTUPC-R084` |
| `abendRoutineExit()` | private | `COACTUPC.cbl` `ABEND-ROUTINE-EXIT` | `TM-COACTUPC-R085` |
| `checkChangeInRecord9700()` | private | `COACTUPC.cbl` `9700-CHECK-CHANGE-IN-REC` | `TM-COACTUPC-R082` |
| `checkChangeInRecord9700Exit()` | private | `COACTUPC.cbl` `9700-CHECK-CHANGE-IN-REC-EXIT` | `TM-COACTUPC-R083` |
| `commonReturn()` | private | `COACTUPC.cbl` `COMMON-RETURN` | `TM-COACTUPC-R002` |
| `compareOldNew1205()` | private | `COACTUPC.cbl` `1205-COMPARE-OLD-NEW` | `TM-COACTUPC-R010` |
| `compareOldNew1205Exit()` | private | `COACTUPC.cbl` `1205-COMPARE-OLD-NEW-EXIT` | `TM-COACTUPC-R011` |
| `decideAction2000()` | private | `COACTUPC.cbl` `2000-DECIDE-ACTION` | `TM-COACTUPC-R044` |
| `decideAction2000Exit()` | private | `COACTUPC.cbl` `2000-DECIDE-ACTION-EXIT` | `TM-COACTUPC-R045` |
| `editAccount1210()` | private | `COACTUPC.cbl` `1210-EDIT-ACCOUNT` | `TM-COACTUPC-R012` |
| `editAccount1210Exit()` | private | `COACTUPC.cbl` `1210-EDIT-ACCOUNT-EXIT` | `TM-COACTUPC-R013` |
| `editAlphaOptional1235()` | private | `COACTUPC.cbl` `1235-EDIT-ALPHA-OPT` | `TM-COACTUPC-R022` |
| `editAlphaOptional1235Exit()` | private | `COACTUPC.cbl` `1235-EDIT-ALPHA-OPT-EXIT` | `TM-COACTUPC-R023` |
| `editAlphaRequired1225()` | private | `COACTUPC.cbl` `1225-EDIT-ALPHA-REQD` | `TM-COACTUPC-R018` |
| `editAlphaRequired1225Exit()` | private | `COACTUPC.cbl` `1225-EDIT-ALPHA-REQD-EXIT` | `TM-COACTUPC-R019` |
| `editAlphanumericOptional1240()` | private | `COACTUPC.cbl` `1240-EDIT-ALPHANUM-OPT` | `TM-COACTUPC-R024` |
| `editAlphanumericOptional1240Exit()` | private | `COACTUPC.cbl` `1240-EDIT-ALPHANUM-OPT-EXIT` | `TM-COACTUPC-R025` |
| `editAlphanumericRequired1230()` | private | `COACTUPC.cbl` `1230-EDIT-ALPHANUM-REQD` | `TM-COACTUPC-R020` |
| `editAlphanumericRequired1230Exit()` | private | `COACTUPC.cbl` `1230-EDIT-ALPHANUM-REQD-EXIT` | `TM-COACTUPC-R021` |
| `editAreaCode()` | private | `COACTUPC.cbl` `EDIT-AREA-CODE` | `TM-COACTUPC-R031` |
| `editFicoScore1275()` | private | `COACTUPC.cbl` `1275-EDIT-FICO-SCORE` | `TM-COACTUPC-R040` |
| `editFicoScore1275Exit()` | private | `COACTUPC.cbl` `1275-EDIT-FICO-SCORE-EXIT` | `TM-COACTUPC-R041` |
| `editMandatory1215()` | private | `COACTUPC.cbl` `1215-EDIT-MANDATORY` | `TM-COACTUPC-R014` |
| `editMandatory1215Exit()` | private | `COACTUPC.cbl` `1215-EDIT-MANDATORY-EXIT` | `TM-COACTUPC-R015` |
| `editMapInputs1200()` | private | `COACTUPC.cbl` `1200-EDIT-MAP-INPUTS` | `TM-COACTUPC-R008` |
| `editMapInputs1200Exit()` | private | `COACTUPC.cbl` `1200-EDIT-MAP-INPUTS-EXIT` | `TM-COACTUPC-R009` |
| `editNumericRequired1245()` | private | `COACTUPC.cbl` `1245-EDIT-NUM-REQD` | `TM-COACTUPC-R026` |
| `editNumericRequired1245Exit()` | private | `COACTUPC.cbl` `1245-EDIT-NUM-REQD-EXIT` | `TM-COACTUPC-R027` |
| `editSignedAmount1250()` | private | `COACTUPC.cbl` `1250-EDIT-SIGNED-9V2` | `TM-COACTUPC-R028` |
| `editSignedAmount1250Exit()` | private | `COACTUPC.cbl` `1250-EDIT-SIGNED-9V2-EXIT` | `TM-COACTUPC-R029` |
| `editUsPhoneExit()` | private | `COACTUPC.cbl` `EDIT-US-PHONE-EXIT` | `TM-COACTUPC-R034` |
| `editUsPhoneLineNumber()` | private | `COACTUPC.cbl` `EDIT-US-PHONE-LINENUM` | `TM-COACTUPC-R033` |
| `editUsPhoneNumber1260()` | private | `COACTUPC.cbl` `1260-EDIT-US-PHONE-NUM` | `TM-COACTUPC-R030` |
| `editUsPhoneNumber1260Exit()` | private | `COACTUPC.cbl` `1260-EDIT-US-PHONE-NUM-EXIT` | `TM-COACTUPC-R035` |
| `editUsPhonePrefix()` | private | `COACTUPC.cbl` `EDIT-US-PHONE-PREFIX` | `TM-COACTUPC-R032` |
| `editUsSsn1265()` | private | `COACTUPC.cbl` `1265-EDIT-US-SSN` | `TM-COACTUPC-R036` |
| `editUsSsn1265Exit()` | private | `COACTUPC.cbl` `1265-EDIT-US-SSN-EXIT` | `TM-COACTUPC-R037` |
| `editUsStateCode1270()` | private | `COACTUPC.cbl` `1270-EDIT-US-STATE-CD` | `TM-COACTUPC-R038` |
| `editUsStateCode1270Exit()` | private | `COACTUPC.cbl` `1270-EDIT-US-STATE-CD-EXIT` | `TM-COACTUPC-R039` |
| `editUsStateZipCode1280()` | private | `COACTUPC.cbl` `1280-EDIT-US-STATE-ZIP-CD` | `TM-COACTUPC-R042` |
| `editUsStateZipCode1280Exit()` | private | `COACTUPC.cbl` `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `TM-COACTUPC-R043` |
| `editYesNo1220()` | private | `COACTUPC.cbl` `1220-EDIT-YESNO` | `TM-COACTUPC-R016` |
| `editYesNo1220Exit()` | private | `COACTUPC.cbl` `1220-EDIT-YESNO-EXIT` | `TM-COACTUPC-R017` |
| `getAccountDataByAccount9300()` | private | `COACTUPC.cbl` `9300-GETACCTDATA-BYACCT` | `TM-COACTUPC-R074` |
| `getAccountDataByAccount9300Exit()` | private | `COACTUPC.cbl` `9300-GETACCTDATA-BYACCT-EXIT` | `TM-COACTUPC-R075` |
| `getCardXrefByAccount9200()` | private | `COACTUPC.cbl` `9200-GETCARDXREF-BYACCT` | `TM-COACTUPC-R072` |
| `getCardXrefByAccount9200Exit()` | private | `COACTUPC.cbl` `9200-GETCARDXREF-BYACCT-EXIT` | `TM-COACTUPC-R073` |
| `getCustomerDataByCustomer9400()` | private | `COACTUPC.cbl` `9400-GETCUSTDATA-BYCUST` | `TM-COACTUPC-R076` |
| `getCustomerDataByCustomer9400Exit()` | private | `COACTUPC.cbl` `9400-GETCUSTDATA-BYCUST-EXIT` | `TM-COACTUPC-R077` |
| `mainExit0000()` | private | `COACTUPC.cbl` `0000-MAIN-EXIT` | `TM-COACTUPC-R003` |
| `mainLine0000()` | private | `COACTUPC.cbl` `0000-MAIN` | `TM-COACTUPC-R001` |
| `processInputs1000()` | private | `COACTUPC.cbl` `1000-PROCESS-INPUTS` | `TM-COACTUPC-R004` |
| `processInputs1000Exit()` | private | `COACTUPC.cbl` `1000-PROCESS-INPUTS-EXIT` | `TM-COACTUPC-R005` |
| `protectAllAttributes3310()` | private | `COACTUPC.cbl` `3310-PROTECT-ALL-ATTRS` | `TM-COACTUPC-R062` |
| `protectAllAttributes3310Exit()` | private | `COACTUPC.cbl` `3310-PROTECT-ALL-ATTRS-EXIT` | `TM-COACTUPC-R063` |
| `readAccount9000()` | private | `COACTUPC.cbl` `9000-READ-ACCT` | `TM-COACTUPC-R070` |
| `readAccount9000Exit()` | private | `COACTUPC.cbl` `9000-READ-ACCT-EXIT` | `TM-COACTUPC-R071` |
| `receiveMap1100()` | private | `COACTUPC.cbl` `1100-RECEIVE-MAP` | `TM-COACTUPC-R006` |
| `receiveMap1100Exit()` | private | `COACTUPC.cbl` `1100-RECEIVE-MAP-EXIT` | `TM-COACTUPC-R007` |
| `screenInit3100()` | private | `COACTUPC.cbl` `3100-SCREEN-INIT` | `TM-COACTUPC-R048` |
| `screenInit3100Exit()` | private | `COACTUPC.cbl` `3100-SCREEN-INIT-EXIT` | `TM-COACTUPC-R049` |
| `sendMap3000()` | private | `COACTUPC.cbl` `3000-SEND-MAP` | `TM-COACTUPC-R046` |
| `sendMap3000Exit()` | private | `COACTUPC.cbl` `3000-SEND-MAP-EXIT` | `TM-COACTUPC-R047` |
| `sendScreen3400()` | private | `COACTUPC.cbl` `3400-SEND-SCREEN` | `TM-COACTUPC-R068` |
| `sendScreen3400Exit()` | private | `COACTUPC.cbl` `3400-SEND-SCREEN-EXIT` | `TM-COACTUPC-R069` |
| `setupInfoMessage3250()` | private | `COACTUPC.cbl` `3250-SETUP-INFOMSG` | `TM-COACTUPC-R058` |
| `setupInfoMessage3250Exit()` | private | `COACTUPC.cbl` `3250-SETUP-INFOMSG-EXIT` | `TM-COACTUPC-R059` |
| `setupInfoMessageAttributes3390()` | private | `COACTUPC.cbl` `3390-SETUP-INFOMSG-ATTRS` | `TM-COACTUPC-R066` |
| `setupInfoMessageAttributes3390Exit()` | private | `COACTUPC.cbl` `3390-SETUP-INFOMSG-ATTRS-EXIT` | `TM-COACTUPC-R067` |
| `setupScreenAttributes3300()` | private | `COACTUPC.cbl` `3300-SETUP-SCREEN-ATTRS` | `TM-COACTUPC-R060` |
| `setupScreenAttributes3300Exit()` | private | `COACTUPC.cbl` `3300-SETUP-SCREEN-ATTRS-EXIT` | `TM-COACTUPC-R061` |
| `setupScreenVars3200()` | private | `COACTUPC.cbl` `3200-SETUP-SCREEN-VARS` | `TM-COACTUPC-R050` |
| `setupScreenVars3200Exit()` | private | `COACTUPC.cbl` `3200-SETUP-SCREEN-VARS-EXIT` | `TM-COACTUPC-R051` |
| `showInitialValues3201()` | private | `COACTUPC.cbl` `3201-SHOW-INITIAL-VALUES` | `TM-COACTUPC-R052` |
| `showInitialValues3201Exit()` | private | `COACTUPC.cbl` `3201-SHOW-INITIAL-VALUES-EXIT` | `TM-COACTUPC-R053` |
| `showOriginalValues3202()` | private | `COACTUPC.cbl` `3202-SHOW-ORIGINAL-VALUES` | `TM-COACTUPC-R054` |
| `showOriginalValues3202Exit()` | private | `COACTUPC.cbl` `3202-SHOW-ORIGINAL-VALUES-EXIT` | `TM-COACTUPC-R055` |
| `showUpdatedValues3203()` | private | `COACTUPC.cbl` `3203-SHOW-UPDATED-VALUES` | `TM-COACTUPC-R056` |
| `showUpdatedValues3203Exit()` | private | `COACTUPC.cbl` `3203-SHOW-UPDATED-VALUES-EXIT` | `TM-COACTUPC-R057` |
| `storeFetchedData9500()` | private | `COACTUPC.cbl` `9500-STORE-FETCHED-DATA` | `TM-COACTUPC-R078` |
| `storeFetchedData9500Exit()` | private | `COACTUPC.cbl` `9500-STORE-FETCHED-DATA-EXIT` | `TM-COACTUPC-R079` |
| `unprotectFewAttributes3320()` | private | `COACTUPC.cbl` `3320-UNPROTECT-FEW-ATTRS` | `TM-COACTUPC-R064` |
| `unprotectFewAttributes3320Exit()` | private | `COACTUPC.cbl` `3320-UNPROTECT-FEW-ATTRS-EXIT` | `TM-COACTUPC-R065` |
| `writeProcessing9600()` | private | `COACTUPC.cbl` `9600-WRITE-PROCESSING` | `TM-COACTUPC-R080` |
| `writeProcessing9600Exit()` | private | `COACTUPC.cbl` `9600-WRITE-PROCESSING-EXIT` | `TM-COACTUPC-R081` |

#### `service/account/AccountViewService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendRoutine()` | private | `COACTVWC.cbl` `ABEND-ROUTINE` | `TM-COACTVWC-R035` |
| `commonReturn()` | private | `COACTVWC.cbl` `COMMON-RETURN` | `TM-COACTVWC-R002` |
| `editAccount2210()` | private | `COACTVWC.cbl` `2210-EDIT-ACCOUNT` | `TM-COACTVWC-R021` |
| `editAccount2210Exit()` | private | `COACTVWC.cbl` `2210-EDIT-ACCOUNT-EXIT` | `TM-COACTVWC-R022` |
| `editMapInputs2200()` | private | `COACTVWC.cbl` `2200-EDIT-MAP-INPUTS` | `TM-COACTVWC-R019` |
| `editMapInputs2200Exit()` | private | `COACTVWC.cbl` `2200-EDIT-MAP-INPUTS-EXIT` | `TM-COACTVWC-R020` |
| `getAcctDataByAcct9300()` | private | `COACTVWC.cbl` `9300-GETACCTDATA-BYACCT` | `TM-COACTVWC-R027` |
| `getAcctDataByAcct9300Exit()` | private | `COACTVWC.cbl` `9300-GETACCTDATA-BYACCT-EXIT` | `TM-COACTVWC-R028` |
| `getCardXrefByAcct9200()` | private | `COACTVWC.cbl` `9200-GETCARDXREF-BYACCT` | `TM-COACTVWC-R025` |
| `getCardXrefByAcct9200Exit()` | private | `COACTVWC.cbl` `9200-GETCARDXREF-BYACCT-EXIT` | `TM-COACTVWC-R026` |
| `getCustDataByCust9400()` | private | `COACTVWC.cbl` `9400-GETCUSTDATA-BYCUST` | `TM-COACTVWC-R029` |
| `getCustDataByCust9400Exit()` | private | `COACTVWC.cbl` `9400-GETCUSTDATA-BYCUST-EXIT` | `TM-COACTVWC-R030` |
| `mainExit0000AtLine408()` | private | `COACTVWC.cbl` `0000-MAIN-EXIT` | `TM-COACTVWC-R003` |
| `mainExit0000AtLine411()` | private | `COACTVWC.cbl` `0000-MAIN-EXIT` | `TM-COACTVWC-R004` |
| `mainLine0000()` | private | `COACTVWC.cbl` `0000-MAIN` | `TM-COACTVWC-R001` |
| `processInputs2000()` | private | `COACTVWC.cbl` `2000-PROCESS-INPUTS` | `TM-COACTVWC-R015` |
| `processInputs2000Exit()` | private | `COACTVWC.cbl` `2000-PROCESS-INPUTS-EXIT` | `TM-COACTVWC-R016` |
| `readAcct9000()` | private | `COACTVWC.cbl` `9000-READ-ACCT` | `TM-COACTVWC-R023` |
| `readAcct9000Exit()` | private | `COACTVWC.cbl` `9000-READ-ACCT-EXIT` | `TM-COACTVWC-R024` |
| `receiveMap2100()` | private | `COACTVWC.cbl` `2100-RECEIVE-MAP` | `TM-COACTVWC-R017` |
| `receiveMap2100Exit()` | private | `COACTVWC.cbl` `2100-RECEIVE-MAP-EXIT` | `TM-COACTVWC-R018` |
| `screenInit1100()` | private | `COACTVWC.cbl` `1100-SCREEN-INIT` | `TM-COACTVWC-R007` |
| `screenInit1100Exit()` | private | `COACTVWC.cbl` `1100-SCREEN-INIT-EXIT` | `TM-COACTVWC-R008` |
| `sendLongText()` | private | `COACTVWC.cbl` `SEND-LONG-TEXT` | `TM-COACTVWC-R033` |
| `sendLongTextExit()` | private | `COACTVWC.cbl` `SEND-LONG-TEXT-EXIT` | `TM-COACTVWC-R034` |
| `sendMap1000()` | private | `COACTVWC.cbl` `1000-SEND-MAP` | `TM-COACTVWC-R005` |
| `sendMap1000Exit()` | private | `COACTVWC.cbl` `1000-SEND-MAP-EXIT` | `TM-COACTVWC-R006` |
| `sendPlainText()` | private | `COACTVWC.cbl` `SEND-PLAIN-TEXT` | `TM-COACTVWC-R031` |
| `sendPlainTextExit()` | private | `COACTVWC.cbl` `SEND-PLAIN-TEXT-EXIT` | `TM-COACTVWC-R032` |
| `sendScreen1400()` | private | `COACTVWC.cbl` `1400-SEND-SCREEN` | `TM-COACTVWC-R013` |
| `sendScreen1400Exit()` | private | `COACTVWC.cbl` `1400-SEND-SCREEN-EXIT` | `TM-COACTVWC-R014` |
| `setupScreenAttrs1300()` | private | `COACTVWC.cbl` `1300-SETUP-SCREEN-ATTRS` | `TM-COACTVWC-R011` |
| `setupScreenAttrs1300Exit()` | private | `COACTVWC.cbl` `1300-SETUP-SCREEN-ATTRS-EXIT` | `TM-COACTVWC-R012` |
| `setupScreenVars1200()` | private | `COACTVWC.cbl` `1200-SETUP-SCREEN-VARS` | `TM-COACTVWC-R009` |
| `setupScreenVars1200Exit()` | private | `COACTVWC.cbl` `1200-SETUP-SCREEN-VARS-EXIT` | `TM-COACTVWC-R010` |

#### `service/admin/UserAddService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `clearCurrentScreen()` | private | `COUSR01C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COUSR01C-R008` |
| `initializeAllFields()` | private | `COUSR01C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COUSR01C-R009` |
| `mainPara()` | private | `COUSR01C.cbl` `MAIN-PARA` | `TM-COUSR01C-R001` |
| `populateHeaderInfo()` | private | `COUSR01C.cbl` `POPULATE-HEADER-INFO` | `TM-COUSR01C-R006` |
| `processEnterKey()` | private | `COUSR01C.cbl` `PROCESS-ENTER-KEY` | `TM-COUSR01C-R002` |
| `receiveUsraddScreen()` | private | `COUSR01C.cbl` `RECEIVE-USRADD-SCREEN` | `TM-COUSR01C-R005` |
| `returnToPrevScreen()` | private | `COUSR01C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COUSR01C-R003` |
| `sendUsraddScreen()` | private | `COUSR01C.cbl` `SEND-USRADD-SCREEN` | `TM-COUSR01C-R004` |
| `writeUserSecFile()` | private | `COUSR01C.cbl` `WRITE-USER-SEC-FILE` | `TM-COUSR01C-R007` |

#### `service/admin/UserDeleteService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `clearCurrentScreen()` | private | `COUSR03C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COUSR03C-R010` |
| `deleteUserInfo()` | private | `COUSR03C.cbl` `DELETE-USER-INFO` | `TM-COUSR03C-R003` |
| `deleteUserSecFile()` | private | `COUSR03C.cbl` `DELETE-USER-SEC-FILE` | `TM-COUSR03C-R009` |
| `initializeAllFields()` | private | `COUSR03C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COUSR03C-R011` |
| `mainPara()` | private | `COUSR03C.cbl` `MAIN-PARA` | `TM-COUSR03C-R001` |
| `populateHeaderInfo()` | private | `COUSR03C.cbl` `POPULATE-HEADER-INFO` | `TM-COUSR03C-R007` |
| `processEnterKey()` | private | `COUSR03C.cbl` `PROCESS-ENTER-KEY` | `TM-COUSR03C-R002` |
| `readUserSecFile()` | private | `COUSR03C.cbl` `READ-USER-SEC-FILE` | `TM-COUSR03C-R008` |
| `receiveUsrdelScreen()` | private | `COUSR03C.cbl` `RECEIVE-USRDEL-SCREEN` | `TM-COUSR03C-R006` |
| `returnToPrevScreen()` | private | `COUSR03C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COUSR03C-R004` |
| `sendUsrdelScreen()` | private | `COUSR03C.cbl` `SEND-USRDEL-SCREEN` | `TM-COUSR03C-R005` |

#### `service/admin/UserListService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `endbrUserSecFile()` | private | `COUSR00C.cbl` `ENDBR-USER-SEC-FILE` | `TM-COUSR00C-R016` |
| `initializeUserData()` | private | `COUSR00C.cbl` `INITIALIZE-USER-DATA` | `TM-COUSR00C-R008` |
| `mainPara()` | private | `COUSR00C.cbl` `MAIN-PARA` | `TM-COUSR00C-R001` |
| `populateHeaderInfo()` | private | `COUSR00C.cbl` `POPULATE-HEADER-INFO` | `TM-COUSR00C-R012` |
| `populateUserData()` | private | `COUSR00C.cbl` `POPULATE-USER-DATA` | `TM-COUSR00C-R007` |
| `processEnterKey()` | private | `COUSR00C.cbl` `PROCESS-ENTER-KEY` | `TM-COUSR00C-R002` |
| `processPageBackward()` | private | `COUSR00C.cbl` `PROCESS-PAGE-BACKWARD` | `TM-COUSR00C-R006` |
| `processPageForward()` | private | `COUSR00C.cbl` `PROCESS-PAGE-FORWARD` | `TM-COUSR00C-R005` |
| `processPf7Key()` | private | `COUSR00C.cbl` `PROCESS-PF7-KEY` | `TM-COUSR00C-R003` |
| `processPf8Key()` | private | `COUSR00C.cbl` `PROCESS-PF8-KEY` | `TM-COUSR00C-R004` |
| `readnextUserSecFile()` | private | `COUSR00C.cbl` `READNEXT-USER-SEC-FILE` | `TM-COUSR00C-R014` |
| `readprevUserSecFile()` | private | `COUSR00C.cbl` `READPREV-USER-SEC-FILE` | `TM-COUSR00C-R015` |
| `receiveUsrlstScreen()` | private | `COUSR00C.cbl` `RECEIVE-USRLST-SCREEN` | `TM-COUSR00C-R011` |
| `returnToPrevScreen()` | private | `COUSR00C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COUSR00C-R009` |
| `sendUsrlstScreen()` | private | `COUSR00C.cbl` `SEND-USRLST-SCREEN` | `TM-COUSR00C-R010` |
| `startbrUserSecFile()` | private | `COUSR00C.cbl` `STARTBR-USER-SEC-FILE` | `TM-COUSR00C-R013` |

#### `service/admin/UserUpdateService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `clearCurrentScreen()` | private | `COUSR02C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COUSR02C-R010` |
| `initializeAllFields()` | private | `COUSR02C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COUSR02C-R011` |
| `mainPara()` | private | `COUSR02C.cbl` `MAIN-PARA` | `TM-COUSR02C-R001` |
| `populateHeaderInfo()` | private | `COUSR02C.cbl` `POPULATE-HEADER-INFO` | `TM-COUSR02C-R007` |
| `processEnterKey()` | private | `COUSR02C.cbl` `PROCESS-ENTER-KEY` | `TM-COUSR02C-R002` |
| `readUserSecFile()` | private | `COUSR02C.cbl` `READ-USER-SEC-FILE` | `TM-COUSR02C-R008` |
| `receiveUsrupdScreen()` | private | `COUSR02C.cbl` `RECEIVE-USRUPD-SCREEN` | `TM-COUSR02C-R006` |
| `returnToPrevScreen()` | private | `COUSR02C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COUSR02C-R004` |
| `sendUsrupdScreen()` | private | `COUSR02C.cbl` `SEND-USRUPD-SCREEN` | `TM-COUSR02C-R005` |
| `updateUserInfo()` | private | `COUSR02C.cbl` `UPDATE-USER-INFO` | `TM-COUSR02C-R003` |
| `updateUserSecFile()` | private | `COUSR02C.cbl` `UPDATE-USER-SEC-FILE` | `TM-COUSR02C-R009` |

#### `service/auth/AuthenticationService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `mainPara()` | private | `COSGN00C.cbl` `MAIN-PARA` | `TM-COSGN00C-R001` |
| `populateHeaderInfo()` | private | `COSGN00C.cbl` `POPULATE-HEADER-INFO` | `TM-COSGN00C-R005` |
| `processEnterKey()` | private | `COSGN00C.cbl` `PROCESS-ENTER-KEY` | `TM-COSGN00C-R002` |
| `readUserSecFile()` | private | `COSGN00C.cbl` `READ-USER-SEC-FILE` | `TM-COSGN00C-R006` |
| `sendPlainText()` | private | `COSGN00C.cbl` `SEND-PLAIN-TEXT` | `TM-COSGN00C-R004` |
| `sendSignonScreen()` | private | `COSGN00C.cbl` `SEND-SIGNON-SCREEN` | `TM-COSGN00C-R003` |

#### `service/billing/BillPaymentService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `clearCurrentScreen()` | private | `COBIL00C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COBIL00C-R015` |
| `endbrTransactFile()` | private | `COBIL00C.cbl` `ENDBR-TRANSACT-FILE` | `TM-COBIL00C-R013` |
| `getCurrentTimestamp()` | private | `COBIL00C.cbl` `GET-CURRENT-TIMESTAMP` | `TM-COBIL00C-R003` |
| `initializeAllFields()` | private | `COBIL00C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COBIL00C-R016` |
| `mainPara()` | private | `COBIL00C.cbl` `MAIN-PARA` | `TM-COBIL00C-R001` |
| `populateHeaderInfo()` | private | `COBIL00C.cbl` `POPULATE-HEADER-INFO` | `TM-COBIL00C-R007` |
| `processEnterKey()` | private | `COBIL00C.cbl` `PROCESS-ENTER-KEY` | `TM-COBIL00C-R002` |
| `readAcctdatFile()` | private | `COBIL00C.cbl` `READ-ACCTDAT-FILE` | `TM-COBIL00C-R008` |
| `readCxacaixFile()` | private | `COBIL00C.cbl` `READ-CXACAIX-FILE` | `TM-COBIL00C-R010` |
| `readprevTransactFile()` | private | `COBIL00C.cbl` `READPREV-TRANSACT-FILE` | `TM-COBIL00C-R012` |
| `receiveBillpayScreen()` | private | `COBIL00C.cbl` `RECEIVE-BILLPAY-SCREEN` | `TM-COBIL00C-R006` |
| `returnToPrevScreen()` | private | `COBIL00C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COBIL00C-R004` |
| `sendBillpayScreen()` | private | `COBIL00C.cbl` `SEND-BILLPAY-SCREEN` | `TM-COBIL00C-R005` |
| `startbrTransactFile()` | private | `COBIL00C.cbl` `STARTBR-TRANSACT-FILE` | `TM-COBIL00C-R011` |
| `updateAcctdatFile()` | private | `COBIL00C.cbl` `UPDATE-ACCTDAT-FILE` | `TM-COBIL00C-R009` |
| `writeTransactFile()` | private | `COBIL00C.cbl` `WRITE-TRANSACT-FILE` | `TM-COBIL00C-R014` |

#### `service/card/CardDetailService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendRoutine()` | private | `COCRDSLC.cbl` `ABEND-ROUTINE` | `TM-COCRDSLC-R034` |
| `commonReturn()` | private | `COCRDSLC.cbl` `COMMON-RETURN` | `TM-COCRDSLC-R002` |
| `editAccount2210()` | private | `COCRDSLC.cbl` `2210-EDIT-ACCOUNT` | `TM-COCRDSLC-R020` |
| `editAccountExit2210()` | private | `COCRDSLC.cbl` `2210-EDIT-ACCOUNT-EXIT` | `TM-COCRDSLC-R021` |
| `editCard2220()` | private | `COCRDSLC.cbl` `2220-EDIT-CARD` | `TM-COCRDSLC-R022` |
| `editCardExit2220()` | private | `COCRDSLC.cbl` `2220-EDIT-CARD-EXIT` | `TM-COCRDSLC-R023` |
| `editMapInputs2200()` | private | `COCRDSLC.cbl` `2200-EDIT-MAP-INPUTS` | `TM-COCRDSLC-R018` |
| `editMapInputsExit2200()` | private | `COCRDSLC.cbl` `2200-EDIT-MAP-INPUTS-EXIT` | `TM-COCRDSLC-R019` |
| `getCardByAcct9150()` | private | `COCRDSLC.cbl` `9150-GETCARD-BYACCT` | `TM-COCRDSLC-R028` |
| `getCardByAcctCard9100()` | private | `COCRDSLC.cbl` `9100-GETCARD-BYACCTCARD` | `TM-COCRDSLC-R026` |
| `getCardByAcctCardExit9100()` | private | `COCRDSLC.cbl` `9100-GETCARD-BYACCTCARD-EXIT` | `TM-COCRDSLC-R027` |
| `getCardByAcctExit9150()` | private | `COCRDSLC.cbl` `9150-GETCARD-BYACCT-EXIT` | `TM-COCRDSLC-R029` |
| `main0000()` | private | `COCRDSLC.cbl` `0000-MAIN` | `TM-COCRDSLC-R001` |
| `mainExit0000()` | private | `COCRDSLC.cbl` `0000-MAIN-EXIT` | `TM-COCRDSLC-R003` |
| `processInputs2000()` | private | `COCRDSLC.cbl` `2000-PROCESS-INPUTS` | `TM-COCRDSLC-R014` |
| `processInputsExit2000()` | private | `COCRDSLC.cbl` `2000-PROCESS-INPUTS-EXIT` | `TM-COCRDSLC-R015` |
| `readData9000()` | private | `COCRDSLC.cbl` `9000-READ-DATA` | `TM-COCRDSLC-R024` |
| `readDataExit9000()` | private | `COCRDSLC.cbl` `9000-READ-DATA-EXIT` | `TM-COCRDSLC-R025` |
| `receiveMap2100()` | private | `COCRDSLC.cbl` `2100-RECEIVE-MAP` | `TM-COCRDSLC-R016` |
| `receiveMapExit2100()` | private | `COCRDSLC.cbl` `2100-RECEIVE-MAP-EXIT` | `TM-COCRDSLC-R017` |
| `screenInit1100()` | private | `COCRDSLC.cbl` `1100-SCREEN-INIT` | `TM-COCRDSLC-R006` |
| `screenInitExit1100()` | private | `COCRDSLC.cbl` `1100-SCREEN-INIT-EXIT` | `TM-COCRDSLC-R007` |
| `sendLongText()` | private | `COCRDSLC.cbl` `SEND-LONG-TEXT` | `TM-COCRDSLC-R030` |
| `sendLongTextExit()` | private | `COCRDSLC.cbl` `SEND-LONG-TEXT-EXIT` | `TM-COCRDSLC-R031` |
| `sendMap1000()` | private | `COCRDSLC.cbl` `1000-SEND-MAP` | `TM-COCRDSLC-R004` |
| `sendMapExit1000()` | private | `COCRDSLC.cbl` `1000-SEND-MAP-EXIT` | `TM-COCRDSLC-R005` |
| `sendPlainText()` | private | `COCRDSLC.cbl` `SEND-PLAIN-TEXT` | `TM-COCRDSLC-R032` |
| `sendPlainTextExit()` | private | `COCRDSLC.cbl` `SEND-PLAIN-TEXT-EXIT` | `TM-COCRDSLC-R033` |
| `sendScreen1400()` | private | `COCRDSLC.cbl` `1400-SEND-SCREEN` | `TM-COCRDSLC-R012` |
| `sendScreenExit1400()` | private | `COCRDSLC.cbl` `1400-SEND-SCREEN-EXIT` | `TM-COCRDSLC-R013` |
| `setupScreenAttrs1300()` | private | `COCRDSLC.cbl` `1300-SETUP-SCREEN-ATTRS` | `TM-COCRDSLC-R010` |
| `setupScreenAttrsExit1300()` | private | `COCRDSLC.cbl` `1300-SETUP-SCREEN-ATTRS-EXIT` | `TM-COCRDSLC-R011` |
| `setupScreenVars1200()` | private | `COCRDSLC.cbl` `1200-SETUP-SCREEN-VARS` | `TM-COCRDSLC-R008` |
| `setupScreenVarsExit1200()` | private | `COCRDSLC.cbl` `1200-SETUP-SCREEN-VARS-EXIT` | `TM-COCRDSLC-R009` |

#### `service/card/CardListService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `commonReturn()` | private | `COCRDLIC.cbl` `COMMON-RETURN` | `TM-COCRDLIC-R002` |
| `editAccount2210()` | private | `COCRDLIC.cbl` `2210-EDIT-ACCOUNT` | `TM-COCRDLIC-R024` |
| `editAccount2210Exit()` | private | `COCRDLIC.cbl` `2210-EDIT-ACCOUNT-EXIT` | `TM-COCRDLIC-R025` |
| `editArray2250()` | private | `COCRDLIC.cbl` `2250-EDIT-ARRAY` | `TM-COCRDLIC-R028` |
| `editArray2250Exit()` | private | `COCRDLIC.cbl` `2250-EDIT-ARRAY-EXIT` | `TM-COCRDLIC-R029` |
| `editCard2220()` | private | `COCRDLIC.cbl` `2220-EDIT-CARD` | `TM-COCRDLIC-R026` |
| `editCard2220Exit()` | private | `COCRDLIC.cbl` `2220-EDIT-CARD-EXIT` | `TM-COCRDLIC-R027` |
| `editInputs2200()` | private | `COCRDLIC.cbl` `2200-EDIT-INPUTS` | `TM-COCRDLIC-R022` |
| `editInputs2200Exit()` | private | `COCRDLIC.cbl` `2200-EDIT-INPUTS-EXIT` | `TM-COCRDLIC-R023` |
| `filterRecords9500()` | private | `COCRDLIC.cbl` `9500-FILTER-RECORDS` | `TM-COCRDLIC-R034` |
| `filterRecords9500Exit()` | private | `COCRDLIC.cbl` `9500-FILTER-RECORDS-EXIT` | `TM-COCRDLIC-R035` |
| `main0000()` | private | `COCRDLIC.cbl` `0000-MAIN` | `TM-COCRDLIC-R001` |
| `main0000Exit()` | private | `COCRDLIC.cbl` `0000-MAIN-EXIT` | `TM-COCRDLIC-R003` |
| `readBackwards9100()` | private | `COCRDLIC.cbl` `9100-READ-BACKWARDS` | `TM-COCRDLIC-R032` |
| `readBackwards9100Exit()` | private | `COCRDLIC.cbl` `9100-READ-BACKWARDS-EXIT` | `TM-COCRDLIC-R033` |
| `readForward9000()` | private | `COCRDLIC.cbl` `9000-READ-FORWARD` | `TM-COCRDLIC-R030` |
| `readForward9000Exit()` | private | `COCRDLIC.cbl` `9000-READ-FORWARD-EXIT` | `TM-COCRDLIC-R031` |
| `receiveMap2000()` | private | `COCRDLIC.cbl` `2000-RECEIVE-MAP` | `TM-COCRDLIC-R018` |
| `receiveMap2000Exit()` | private | `COCRDLIC.cbl` `2000-RECEIVE-MAP-EXIT` | `TM-COCRDLIC-R019` |
| `receiveScreen2100()` | private | `COCRDLIC.cbl` `2100-RECEIVE-SCREEN` | `TM-COCRDLIC-R020` |
| `receiveScreen2100Exit()` | private | `COCRDLIC.cbl` `2100-RECEIVE-SCREEN-EXIT` | `TM-COCRDLIC-R021` |
| `screenArrayInit1200()` | private | `COCRDLIC.cbl` `1200-SCREEN-ARRAY-INIT` | `TM-COCRDLIC-R008` |
| `screenArrayInit1200Exit()` | private | `COCRDLIC.cbl` `1200-SCREEN-ARRAY-INIT-EXIT` | `TM-COCRDLIC-R009` |
| `screenInit1100()` | private | `COCRDLIC.cbl` `1100-SCREEN-INIT` | `TM-COCRDLIC-R006` |
| `screenInit1100Exit()` | private | `COCRDLIC.cbl` `1100-SCREEN-INIT-EXIT` | `TM-COCRDLIC-R007` |
| `sendLongText()` | private | `COCRDLIC.cbl` `SEND-LONG-TEXT` | `TM-COCRDLIC-R038` |
| `sendLongTextExit()` | private | `COCRDLIC.cbl` `SEND-LONG-TEXT-EXIT` | `TM-COCRDLIC-R039` |
| `sendMap1000()` | private | `COCRDLIC.cbl` `1000-SEND-MAP` | `TM-COCRDLIC-R004` |
| `sendMap1000Exit()` | private | `COCRDLIC.cbl` `1000-SEND-MAP-EXIT` | `TM-COCRDLIC-R005` |
| `sendPlainText()` | private | `COCRDLIC.cbl` `SEND-PLAIN-TEXT` | `TM-COCRDLIC-R036` |
| `sendPlainTextExit()` | private | `COCRDLIC.cbl` `SEND-PLAIN-TEXT-EXIT` | `TM-COCRDLIC-R037` |
| `sendScreen1500()` | private | `COCRDLIC.cbl` `1500-SEND-SCREEN` | `TM-COCRDLIC-R016` |
| `sendScreen1500Exit()` | private | `COCRDLIC.cbl` `1500-SEND-SCREEN-EXIT` | `TM-COCRDLIC-R017` |
| `setupArrayAttribs1250()` | private | `COCRDLIC.cbl` `1250-SETUP-ARRAY-ATTRIBS` | `TM-COCRDLIC-R010` |
| `setupArrayAttribs1250Exit()` | private | `COCRDLIC.cbl` `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `TM-COCRDLIC-R011` |
| `setupMessage1400()` | private | `COCRDLIC.cbl` `1400-SETUP-MESSAGE` | `TM-COCRDLIC-R014` |
| `setupMessage1400Exit()` | private | `COCRDLIC.cbl` `1400-SETUP-MESSAGE-EXIT` | `TM-COCRDLIC-R015` |
| `setupScreenAttrs1300()` | private | `COCRDLIC.cbl` `1300-SETUP-SCREEN-ATTRS` | `TM-COCRDLIC-R012` |
| `setupScreenAttrs1300Exit()` | private | `COCRDLIC.cbl` `1300-SETUP-SCREEN-ATTRS-EXIT` | `TM-COCRDLIC-R013` |

#### `service/card/CardUpdateService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `abendRoutine()` | private | `COCRDUPC.cbl` `ABEND-ROUTINE` | `TM-COCRDUPC-R044` |
| `abendRoutineExit()` | private | `COCRDUPC.cbl` `ABEND-ROUTINE-EXIT` | `TM-COCRDUPC-R045` |
| `checkChangeInRec9300()` | private | `COCRDUPC.cbl` `9300-CHECK-CHANGE-IN-REC` | `TM-COCRDUPC-R042` |
| `checkChangeInRecExit9300()` | private | `COCRDUPC.cbl` `9300-CHECK-CHANGE-IN-REC-EXIT` | `TM-COCRDUPC-R043` |
| `commonReturn()` | private | `COCRDUPC.cbl` `COMMON-RETURN` | `TM-COCRDUPC-R002` |
| `decideAction2000()` | private | `COCRDUPC.cbl` `2000-DECIDE-ACTION` | `TM-COCRDUPC-R022` |
| `decideActionExit2000()` | private | `COCRDUPC.cbl` `2000-DECIDE-ACTION-EXIT` | `TM-COCRDUPC-R023` |
| `editAccount1210()` | private | `COCRDUPC.cbl` `1210-EDIT-ACCOUNT` | `TM-COCRDUPC-R010` |
| `editAccountExit1210()` | private | `COCRDUPC.cbl` `1210-EDIT-ACCOUNT-EXIT` | `TM-COCRDUPC-R011` |
| `editCard1220()` | private | `COCRDUPC.cbl` `1220-EDIT-CARD` | `TM-COCRDUPC-R012` |
| `editCardExit1220()` | private | `COCRDUPC.cbl` `1220-EDIT-CARD-EXIT` | `TM-COCRDUPC-R013` |
| `editCardStatus1240()` | private | `COCRDUPC.cbl` `1240-EDIT-CARDSTATUS` | `TM-COCRDUPC-R016` |
| `editCardStatusExit1240()` | private | `COCRDUPC.cbl` `1240-EDIT-CARDSTATUS-EXIT` | `TM-COCRDUPC-R017` |
| `editExpiryMonth1250()` | private | `COCRDUPC.cbl` `1250-EDIT-EXPIRY-MON` | `TM-COCRDUPC-R018` |
| `editExpiryMonthExit1250()` | private | `COCRDUPC.cbl` `1250-EDIT-EXPIRY-MON-EXIT` | `TM-COCRDUPC-R019` |
| `editExpiryYear1260()` | private | `COCRDUPC.cbl` `1260-EDIT-EXPIRY-YEAR` | `TM-COCRDUPC-R020` |
| `editExpiryYearExit1260()` | private | `COCRDUPC.cbl` `1260-EDIT-EXPIRY-YEAR-EXIT` | `TM-COCRDUPC-R021` |
| `editMapInputs1200()` | private | `COCRDUPC.cbl` `1200-EDIT-MAP-INPUTS` | `TM-COCRDUPC-R008` |
| `editMapInputsExit1200()` | private | `COCRDUPC.cbl` `1200-EDIT-MAP-INPUTS-EXIT` | `TM-COCRDUPC-R009` |
| `editName1230()` | private | `COCRDUPC.cbl` `1230-EDIT-NAME` | `TM-COCRDUPC-R014` |
| `editNameExit1230()` | private | `COCRDUPC.cbl` `1230-EDIT-NAME-EXIT` | `TM-COCRDUPC-R015` |
| `getCardByAcctCard9100()` | private | `COCRDUPC.cbl` `9100-GETCARD-BYACCTCARD` | `TM-COCRDUPC-R038` |
| `getCardByAcctCardExit9100()` | private | `COCRDUPC.cbl` `9100-GETCARD-BYACCTCARD-EXIT` | `TM-COCRDUPC-R039` |
| `mainExit0000()` | private | `COCRDUPC.cbl` `0000-MAIN-EXIT` | `TM-COCRDUPC-R003` |
| `mainLine0000()` | private | `COCRDUPC.cbl` `0000-MAIN` | `TM-COCRDUPC-R001` |
| `processInputs1000()` | private | `COCRDUPC.cbl` `1000-PROCESS-INPUTS` | `TM-COCRDUPC-R004` |
| `processInputsExit1000()` | private | `COCRDUPC.cbl` `1000-PROCESS-INPUTS-EXIT` | `TM-COCRDUPC-R005` |
| `readData9000()` | private | `COCRDUPC.cbl` `9000-READ-DATA` | `TM-COCRDUPC-R036` |
| `readDataExit9000()` | private | `COCRDUPC.cbl` `9000-READ-DATA-EXIT` | `TM-COCRDUPC-R037` |
| `receiveMap1100()` | private | `COCRDUPC.cbl` `1100-RECEIVE-MAP` | `TM-COCRDUPC-R006` |
| `receiveMapExit1100()` | private | `COCRDUPC.cbl` `1100-RECEIVE-MAP-EXIT` | `TM-COCRDUPC-R007` |
| `screenInit3100()` | private | `COCRDUPC.cbl` `3100-SCREEN-INIT` | `TM-COCRDUPC-R026` |
| `screenInitExit3100()` | private | `COCRDUPC.cbl` `3100-SCREEN-INIT-EXIT` | `TM-COCRDUPC-R027` |
| `sendMap3000()` | private | `COCRDUPC.cbl` `3000-SEND-MAP` | `TM-COCRDUPC-R024` |
| `sendMapExit3000()` | private | `COCRDUPC.cbl` `3000-SEND-MAP-EXIT` | `TM-COCRDUPC-R025` |
| `sendScreen3400()` | private | `COCRDUPC.cbl` `3400-SEND-SCREEN` | `TM-COCRDUPC-R034` |
| `sendScreenExit3400()` | private | `COCRDUPC.cbl` `3400-SEND-SCREEN-EXIT` | `TM-COCRDUPC-R035` |
| `setupInfoMsg3250()` | private | `COCRDUPC.cbl` `3250-SETUP-INFOMSG` | `TM-COCRDUPC-R030` |
| `setupInfoMsgExit3250()` | private | `COCRDUPC.cbl` `3250-SETUP-INFOMSG-EXIT` | `TM-COCRDUPC-R031` |
| `setupScreenAttrs3300()` | private | `COCRDUPC.cbl` `3300-SETUP-SCREEN-ATTRS` | `TM-COCRDUPC-R032` |
| `setupScreenAttrsExit3300()` | private | `COCRDUPC.cbl` `3300-SETUP-SCREEN-ATTRS-EXIT` | `TM-COCRDUPC-R033` |
| `setupScreenVars3200()` | private | `COCRDUPC.cbl` `3200-SETUP-SCREEN-VARS` | `TM-COCRDUPC-R028` |
| `setupScreenVarsExit3200()` | private | `COCRDUPC.cbl` `3200-SETUP-SCREEN-VARS-EXIT` | `TM-COCRDUPC-R029` |
| `writeProcessing9200()` | private | `COCRDUPC.cbl` `9200-WRITE-PROCESSING` | `TM-COCRDUPC-R040` |
| `writeProcessingExit9200()` | private | `COCRDUPC.cbl` `9200-WRITE-PROCESSING-EXIT` | `TM-COCRDUPC-R041` |

#### `service/menu/AdminMenuService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `buildMenuOptions()` | private | `COADM01C.cbl` `BUILD-MENU-OPTIONS` | `TM-COADM01C-R007` |
| `mainPara()` | private | `COADM01C.cbl` `MAIN-PARA` | `TM-COADM01C-R001` |
| `populateHeaderInfo()` | private | `COADM01C.cbl` `POPULATE-HEADER-INFO` | `TM-COADM01C-R006` |
| `processEnterKey()` | private | `COADM01C.cbl` `PROCESS-ENTER-KEY` | `TM-COADM01C-R002` |
| `receiveMenuScreen()` | private | `COADM01C.cbl` `RECEIVE-MENU-SCREEN` | `TM-COADM01C-R005` |
| `returnToSignOnScreen()` | private | `COADM01C.cbl` `RETURN-TO-SIGNON-SCREEN` | `TM-COADM01C-R003` |
| `sendMenuScreen()` | private | `COADM01C.cbl` `SEND-MENU-SCREEN` | `TM-COADM01C-R004` |

#### `service/menu/MainMenuService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `buildMenuOptions()` | private | `COMEN01C.cbl` `BUILD-MENU-OPTIONS` | `TM-COMEN01C-R007` |
| `mainPara()` | private | `COMEN01C.cbl` `MAIN-PARA` | `TM-COMEN01C-R001` |
| `populateHeaderInfo()` | private | `COMEN01C.cbl` `POPULATE-HEADER-INFO` | `TM-COMEN01C-R006` |
| `processEnterKey()` | private | `COMEN01C.cbl` `PROCESS-ENTER-KEY` | `TM-COMEN01C-R002` |
| `receiveMenuScreen()` | private | `COMEN01C.cbl` `RECEIVE-MENU-SCREEN` | `TM-COMEN01C-R005` |
| `returnToSignonScreen()` | private | `COMEN01C.cbl` `RETURN-TO-SIGNON-SCREEN` | `TM-COMEN01C-R003` |
| `sendMenuScreen()` | private | `COMEN01C.cbl` `SEND-MENU-SCREEN` | `TM-COMEN01C-R004` |

#### `service/report/ReportSubmissionService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `initializeAllFields()` | private | `CORPT00C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-CORPT00C-R010` |
| `mainPara()` | private | `CORPT00C.cbl` `MAIN-PARA` | `TM-CORPT00C-R001` |
| `populateHeaderInfo()` | private | `CORPT00C.cbl` `POPULATE-HEADER-INFO` | `TM-CORPT00C-R009` |
| `processEnterKey()` | private | `CORPT00C.cbl` `PROCESS-ENTER-KEY` | `TM-CORPT00C-R002` |
| `receiveTrnrptScreen()` | private | `CORPT00C.cbl` `RECEIVE-TRNRPT-SCREEN` | `TM-CORPT00C-R008` |
| `returnToCics()` | private | `CORPT00C.cbl` `RETURN-TO-CICS` | `TM-CORPT00C-R007` |
| `returnToPrevScreen()` | private | `CORPT00C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-CORPT00C-R005` |
| `sendTrnrptScreen()` | private | `CORPT00C.cbl` `SEND-TRNRPT-SCREEN` | `TM-CORPT00C-R006` |
| `submitJobToIntrdr()` | private | `CORPT00C.cbl` `SUBMIT-JOB-TO-INTRDR` | `TM-CORPT00C-R003` |
| `wirteJobsubTdq()` | private | `CORPT00C.cbl` `WIRTE-JOBSUB-TDQ` | `TM-CORPT00C-R004` |

#### `service/shared/DateValidationService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `a000Main()` | private | `CSUTLDTC.cbl` `A000-MAIN` | `TM-CSUTLDTC-R002` |
| `a000MainExit()` | private | `CSUTLDTC.cbl` `A000-MAIN-EXIT` | `TM-CSUTLDTC-R003` |
| `validate()` | public | `CSUTLDTC.cbl` `PROCEDURE-DIVISION-ENTRY` | `TM-CSUTLDTC-R001` |

#### `service/shared/FileService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `acctfileProc()` | private | `CBSTM03B.CBL` `4000-ACCTFILE-PROC` | `TM-CBSTM03B-R012` |
| `acctfileStatusEpilogue()` | private | `CBSTM03B.CBL` `4900-EXIT` | `TM-CBSTM03B-R013` |
| `acctfileTerminator()` | private | `CBSTM03B.CBL` `4999-EXIT` | `TM-CBSTM03B-R014` |
| `custfileProc()` | private | `CBSTM03B.CBL` `3000-CUSTFILE-PROC` | `TM-CBSTM03B-R009` |
| `custfileStatusEpilogue()` | private | `CBSTM03B.CBL` `3900-EXIT` | `TM-CBSTM03B-R010` |
| `custfileTerminator()` | private | `CBSTM03B.CBL` `3999-EXIT` | `TM-CBSTM03B-R011` |
| `dispatch()` | private | `CBSTM03B.CBL` `0000-START` | `TM-CBSTM03B-R001` |
| `goback()` | private | `CBSTM03B.CBL` `9999-GOBACK` | `TM-CBSTM03B-R002` |
| `trnxfileProc()` | private | `CBSTM03B.CBL` `1000-TRNXFILE-PROC` | `TM-CBSTM03B-R003` |
| `trnxfileStatusEpilogue()` | private | `CBSTM03B.CBL` `1900-EXIT` | `TM-CBSTM03B-R004` |
| `trnxfileTerminator()` | private | `CBSTM03B.CBL` `1999-EXIT` | `TM-CBSTM03B-R005` |
| `xreffileProc()` | private | `CBSTM03B.CBL` `2000-XREFFILE-PROC` | `TM-CBSTM03B-R006` |
| `xreffileStatusEpilogue()` | private | `CBSTM03B.CBL` `2900-EXIT` | `TM-CBSTM03B-R007` |
| `xreffileTerminator()` | private | `CBSTM03B.CBL` `2999-EXIT` | `TM-CBSTM03B-R008` |

#### `service/transaction/TransactionAddService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `addTransaction()` | public | `COTRN02C.cbl` `ADD-TRANSACTION` | `TM-COTRN02C-R005` |
| `clearCurrentScreen()` | private | `COTRN02C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COTRN02C-R017` |
| `copyLastTranData()` | private | `COTRN02C.cbl` `COPY-LAST-TRAN-DATA` | `TM-COTRN02C-R006` |
| `endbrTransactFile()` | private | `COTRN02C.cbl` `ENDBR-TRANSACT-FILE` | `TM-COTRN02C-R015` |
| `initializeAllFields()` | private | `COTRN02C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COTRN02C-R018` |
| `mainPara()` | private | `COTRN02C.cbl` `MAIN-PARA` | `TM-COTRN02C-R001` |
| `populateHeaderInfo()` | private | `COTRN02C.cbl` `POPULATE-HEADER-INFO` | `TM-COTRN02C-R010` |
| `processEnterKey()` | private | `COTRN02C.cbl` `PROCESS-ENTER-KEY` | `TM-COTRN02C-R002` |
| `readCcxrefFile()` | private | `COTRN02C.cbl` `READ-CCXREF-FILE` | `TM-COTRN02C-R012` |
| `readCxacaixFile()` | private | `COTRN02C.cbl` `READ-CXACAIX-FILE` | `TM-COTRN02C-R011` |
| `readprevTransactFile()` | private | `COTRN02C.cbl` `READPREV-TRANSACT-FILE` | `TM-COTRN02C-R014` |
| `receiveTrnaddScreen()` | private | `COTRN02C.cbl` `RECEIVE-TRNADD-SCREEN` | `TM-COTRN02C-R009` |
| `returnToPrevScreen()` | private | `COTRN02C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COTRN02C-R007` |
| `sendTrnaddScreen()` | private | `COTRN02C.cbl` `SEND-TRNADD-SCREEN` | `TM-COTRN02C-R008` |
| `startbrTransactFile()` | private | `COTRN02C.cbl` `STARTBR-TRANSACT-FILE` | `TM-COTRN02C-R013` |
| `validateInputDataFields()` | private | `COTRN02C.cbl` `VALIDATE-INPUT-DATA-FIELDS` | `TM-COTRN02C-R004` |
| `validateInputKeyFields()` | private | `COTRN02C.cbl` `VALIDATE-INPUT-KEY-FIELDS` | `TM-COTRN02C-R003` |
| `writeTransactFile()` | private | `COTRN02C.cbl` `WRITE-TRANSACT-FILE` | `TM-COTRN02C-R016` |

#### `service/transaction/TransactionDetailService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `clearCurrentScreen()` | private | `COTRN01C.cbl` `CLEAR-CURRENT-SCREEN` | `TM-COTRN01C-R008` |
| `initializeAllFields()` | private | `COTRN01C.cbl` `INITIALIZE-ALL-FIELDS` | `TM-COTRN01C-R009` |
| `mainPara()` | private | `COTRN01C.cbl` `MAIN-PARA` | `TM-COTRN01C-R001` |
| `populateHeaderInfo()` | private | `COTRN01C.cbl` `POPULATE-HEADER-INFO` | `TM-COTRN01C-R006` |
| `processEnterKey()` | private | `COTRN01C.cbl` `PROCESS-ENTER-KEY` | `TM-COTRN01C-R002` |
| `readTransactFile()` | private | `COTRN01C.cbl` `READ-TRANSACT-FILE` | `TM-COTRN01C-R007` |
| `receiveTrnviewScreen()` | private | `COTRN01C.cbl` `RECEIVE-TRNVIEW-SCREEN` | `TM-COTRN01C-R005` |
| `returnToPrevScreen()` | private | `COTRN01C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COTRN01C-R003` |
| `sendTrnviewScreen()` | private | `COTRN01C.cbl` `SEND-TRNVIEW-SCREEN` | `TM-COTRN01C-R004` |

#### `service/transaction/TransactionListService.java`

| Java member | Visibility | Source paragraph(s) | Row ID(s) |
|---|---|---|---|
| `endbrTransactFile()` | private | `COTRN00C.cbl` `ENDBR-TRANSACT-FILE` | `TM-COTRN00C-R016` |
| `initializeTranData()` | private | `COTRN00C.cbl` `INITIALIZE-TRAN-DATA` | `TM-COTRN00C-R008` |
| `mainPara()` | private | `COTRN00C.cbl` `MAIN-PARA` | `TM-COTRN00C-R001` |
| `populateHeaderInfo()` | private | `COTRN00C.cbl` `POPULATE-HEADER-INFO` | `TM-COTRN00C-R012` |
| `populateTranData()` | private | `COTRN00C.cbl` `POPULATE-TRAN-DATA` | `TM-COTRN00C-R007` |
| `processEnterKey()` | private | `COTRN00C.cbl` `PROCESS-ENTER-KEY` | `TM-COTRN00C-R002` |
| `processPageBackward()` | private | `COTRN00C.cbl` `PROCESS-PAGE-BACKWARD` | `TM-COTRN00C-R006` |
| `processPageForward()` | private | `COTRN00C.cbl` `PROCESS-PAGE-FORWARD` | `TM-COTRN00C-R005` |
| `processPf7Key()` | private | `COTRN00C.cbl` `PROCESS-PF7-KEY` | `TM-COTRN00C-R003` |
| `processPf8Key()` | private | `COTRN00C.cbl` `PROCESS-PF8-KEY` | `TM-COTRN00C-R004` |
| `readnextTransactFile()` | private | `COTRN00C.cbl` `READNEXT-TRANSACT-FILE` | `TM-COTRN00C-R014` |
| `readprevTransactFile()` | private | `COTRN00C.cbl` `READPREV-TRANSACT-FILE` | `TM-COTRN00C-R015` |
| `receiveTrnlstScreen()` | private | `COTRN00C.cbl` `RECEIVE-TRNLST-SCREEN` | `TM-COTRN00C-R011` |
| `returnToPrevScreen()` | private | `COTRN00C.cbl` `RETURN-TO-PREV-SCREEN` | `TM-COTRN00C-R009` |
| `sendTrnlstScreen()` | private | `COTRN00C.cbl` `SEND-TRNLST-SCREEN` | `TM-COTRN00C-R010` |
| `startbrTransactFile()` | private | `COTRN00C.cbl` `STARTBR-TRANSACT-FILE` | `TM-COTRN00C-R013` |

<a id="csd"></a>

## 7. CICS resource reconciliation &mdash; `app/csd/CARDDEMO.CSD`

Censused with the anchor `^ +DEFINE <KIND>\(`. **The leading whitespace is load-bearing:** every
definition line in the file is indented, so an anchor without it matches nothing and reports every
count as zero &mdash; which reads as a clean absence rather than a broken parser.

| Kind | Count |
|---|---:|
| `DEFINE TRANSACTION` | 18 |
| `DEFINE PROGRAM` | 18 |
| `DEFINE MAPSET` | 17 |
| `DEFINE FILE` | 8 |
| `DEFINE TDQUEUE` | 1 |

### 7.1 The 17-versus-18 reconciliation

**17 sourced screen programs + 1 orphan definition = the 18 CSD entries.** The mapset count of 17
is the tell: the eighteenth program has no mapset because it has no source.

| Transaction | Program | Endpoint | Controller | Standing |
|---|---|---|---|---|
| `CC00` | [`COSGN00C.cbl`](#cosgn00c-cbl) | `POST /api/auth/signon` | `controller/AuthController.java` | sourced |
| `CM00` | [`COMEN01C.cbl`](#comen01c-cbl) | `GET /api/menu/main` | `controller/MenuController.java` | sourced |
| `CA00` | [`COADM01C.cbl`](#coadm01c-cbl) | `GET /api/menu/admin` | `controller/MenuController.java` | sourced |
| `CAVW` | [`COACTVWC.cbl`](#coactvwc-cbl) | `GET /api/accounts/{accountId}` | `controller/AccountController.java` | sourced |
| `CAUP` | [`COACTUPC.cbl`](#coactupc-cbl) | `PUT /api/accounts` | `controller/AccountController.java` | sourced |
| `CCLI` | [`COCRDLIC.cbl`](#cocrdlic-cbl) | `GET /api/cards` | `controller/CardController.java` | sourced |
| `CCDL` | [`COCRDSLC.cbl`](#cocrdslc-cbl) | `GET /api/cards/detail` | `controller/CardController.java` | sourced |
| `CCUP` | [`COCRDUPC.cbl`](#cocrdupc-cbl) | `PUT /api/cards` | `controller/CardController.java` | sourced |
| `CT00` | [`COTRN00C.cbl`](#cotrn00c-cbl) | `GET /api/transactions` | `controller/TransactionController.java` | sourced |
| `CT01` | [`COTRN01C.cbl`](#cotrn01c-cbl) | `GET /api/transactions/detail` | `controller/TransactionController.java` | sourced |
| `CT02` | [`COTRN02C.cbl`](#cotrn02c-cbl) | `POST /api/transactions` | `controller/TransactionController.java` | sourced |
| `CB00` | [`COBIL00C.cbl`](#cobil00c-cbl) | `POST /api/billing/payments` | `controller/BillingController.java` | sourced |
| `CR00` | [`CORPT00C.cbl`](#corpt00c-cbl) | `POST /api/reports` | `controller/ReportController.java` | sourced |
| `CU00` | [`COUSR00C.cbl`](#cousr00c-cbl) | `GET /api/admin/users` | `controller/AdminController.java` | sourced |
| `CU01` | [`COUSR01C.cbl`](#cousr01c-cbl) | `POST /api/admin/users` | `controller/AdminController.java` | sourced |
| `CU02` | [`COUSR02C.cbl`](#cousr02c-cbl) | `PUT /api/admin/users/{userId}` | `controller/AdminController.java` | sourced |
| `CU03` | [`COUSR03C.cbl`](#cousr03c-cbl) | `DELETE /api/admin/users/{userId}` | `controller/AdminController.java` | sourced |

**Four of those routes were published wrongly and are corrected here, because the shape of the
mistake matters more than the four strings.** An earlier revision of this table gave the account
update as `PUT /api/accounts/{accountId}`, card detail and card update as
`GET|PUT /api/cards/{cardNumber}`, and transaction detail as
`GET /api/transactions/{transactionId}` — four path-variable forms that **do not exist**. Every
one was an assumption that a resource-per-identifier URL is the obvious translation of a screen
that takes an identifier, and in each case the implementation deliberately does something else:

* **`PUT /api/accounts`** takes no path variable because the body already carries the identifier
  inside both `oldDetails` and `newDetails`. Putting it in the path as well would create two
  sources of truth for the same value and a mismatch case with no source analogue.
* **`GET /api/cards/detail` and `PUT /api/cards`** take no card number in the path because
  `COCRDSLC` and `COCRDUPC` are reached by **either** an account identifier **or** a card number
  `[app/cbl/COCRDSLC.cbl]`, and a single path variable cannot express that choice. The selector
  travels as a parameter or in the body, exactly as the screen's two input fields did.
* **`GET /api/transactions/detail`** takes no path variable for the same reason as card detail —
  the identifier is a request parameter, so the route is a fixed sub-path.

The routes above are now **derived from the controllers rather than restated from prose**, and the
authoritative census is one `@RequestMapping` base plus one method-level mapping per operation
across the eight controllers, which yields exactly these **17**. Where this table and
[`docs/api-contracts.md`](docs/api-contracts.md) ever disagree again, **the controllers settle
it** and both documents are wrong.
| `CDV1` | `COCRDSEC` | **none &mdash; no endpoint invented** | &mdash; | **OUT OF SCOPE &mdash; source absent** |

**`COCRDSEC` has no source anywhere in the repository.** The name occurs at exactly two places,
both inside the CSD: `DEFINE PROGRAM(COCRDSEC)` at `app/csd/CARDDEMO.CSD:L211`, and
`PROGRAM(COCRDSEC)` at `:L390` beneath `DEFINE TRANSACTION(CDV1)` at `:L388`. There is no `.cbl`
member, no mapset and no copybook. It is a dangling legacy definition, it gets **no matrix row**,
and **no endpoint was invented for it** &mdash; see [`DL-RR-03`](DECISION_LOG.md#dl-rr-03).

Three further programs are absent from the CSD for sound reasons and are not part of the 18:
`CSUTLDTC` is a statically-called subprogram, and `CBSTM03A` / `CBSTM03B` are batch.

### 7.2 The eight CICS file definitions

| CICS file | Target | Note |
|---|---|---|
| `ACCTDAT` | `model/entity/Account.java` via `repository/AccountRepository.java` | key length 11 |
| `CARDDAT` | `model/entity/Card.java` via `repository/CardRepository.java` | key length 16 |
| `CARDAIX` | `repository/CardRepository.java` derived finder | alternate index, `AXRKP 16` |
| `CCXREF` | `model/entity/CardCrossReference.java` | key length 16 |
| `CXACAIX` | `repository/CardCrossReferenceRepository.java` derived finder | alternate index, `AXRKP 25` &mdash; bytes 26&ndash;36, the account id |
| `CUSTDAT` | `model/entity/Customer.java` | key length 9 |
| `TRANSACT` | `model/entity/Transaction.java` | key length 16 |
| `USRSEC` | `model/entity/UserSecurity.java` | key length 8 |

**`TCATBALF`, `DISCGRP`, `TRANCATG` and `TRANTYPE` have no CICS definition at all.** That absence
is the evidence that they are batch-only datasets, and it shapes both the authorisation model and
the integration-test surface.

### 7.3 The transient data queue

`DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)` at `app/csd/CARDDEMO.CSD:L499`, with
`DESCRIPTION(SUBMIT JOBS FROM CICS)` at `:L500`,
`TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)` at `:L501` and
`OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)` at `:L502`.

The declared 80-byte fixed record is what fixes the shape of the one FIFO message that replaces
it &mdash; `carddemo-report-jobs.fifo`, written by
`service/report/ReportSubmissionService.java` `wirteJobsubTdq()` and configured in
`config/AwsConfig.java`. See [`DL-MS-09`](DECISION_LOG.md#dl-ms-09) and
[`DL-MS-18`](DECISION_LOG.md#dl-ms-18).

<a id="jcl"></a>

## 8. Job control, procedure and control-card map

**All 29 `app/jcl` members, matched case-insensitively.** `CREASTMT.JCL` is the sole uppercase
extension; a `*.jcl` glob matches 28 and silently drops the only source of statement generation.

| # | Member | Primary utility / program | Target | Disposition |
|---:|---|---|---|---|
| 1 | `ACCTFILE.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 2 | `CARDFILE.jcl` | IDCAMS + SDSF | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 3 | `CBADMCDJ.jcl` | DFHCSDUP | `src/main/java/com/cardemo/config/SecurityConfig.java` | **no COBOL analogue** &mdash; installs the CSD; superseded by the security configuration |
| 4 | `CLOSEFIL.jcl` | SDSF | `src/main/java/com/cardemo/observability/HealthIndicators.java` | **no COBOL analogue** &mdash; online file availability; superseded by health indicators |
| 5 | `COMBTRAN.jcl` | SORT + IDCAMS | `batch/jobs/CombineTransactionsJob.java`, `batch/processors/TransactionCombineProcessor.java` | **`F-021` transaction combination. JCL-only &mdash; no COBOL program exists.** The JCL itself is the source of truth, which makes this member the only artefact the 22nd feature identifier can attach to &mdash; [section 2.2](#features), and the member's own behaviour is set out in [section 8.3](#f-021) |
| 6 | `CREASTMT.JCL` | CBSTM03A + IDCAMS + IEFBR14 + SORT | `batch/jobs/StatementGenerationJob.java` | 5 steps &rarr; 5 Spring Batch steps |
| 7 | `CUSTFILE.jcl` | IDCAMS + SDSF | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 8 | `DALYREJS.jcl` | IDCAMS | `localstack-init/init-aws.sh` | the 7th generation-group base, `LIMIT(5)` at `:L26` |
| 9 | `DEFCUST.jcl` | IDCAMS | &mdash; | orphan cluster `AWS.CUSTDATA.CLUSTER`, opened by no program |
| 10 | `DEFGDGB.jcl` | IDCAMS | `localstack-init/init-aws.sh` | 6 generation-group bases &rarr; S3 prefixes |
| 11 | `DISCGRP.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 12 | `DUSRSECJ.jcl` | IDCAMS + IEBGENER + IEFBR14 | `db/migration/V3__seed_data.sql` | **the only source of the ten user records**; geometry `KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED` at `:L65`&ndash;`:L68` |
| 13 | `INTCALC.jcl` | CBACT04C | `batch/jobs/InterestCalculationJob.java` | job &rarr; job |
| 14 | `OPENFIL.jcl` | SDSF | `src/main/java/com/cardemo/observability/HealthIndicators.java` | **no COBOL analogue** &mdash; superseded by health indicators |
| 15 | `POSTTRAN.jcl` | CBTRN02C | `batch/jobs/DailyTransactionPostingJob.java` | job &rarr; job; reject `LRECL=430` at `:L36` |
| 16 | `PRTCATBL.jcl` | IEFBR14 + REPROC + SORT | `batch/jobs/TransactionReportJob.java` &mdash; `transactionReportCategoryBalanceStep`, `executePrtcatbl`, `unloadCategoryBalances`, `printCategoryBalances` | batch step, gated by the `printCategoryBalances` parameter the report branch sets; unloads `TCATBALF.BKUP(+1)` at `LRECL=50` (`:L29`&ndash;`:L37`) and prints it at `LRECL=40` (`:L41`&ndash;`:L69`). The `OUTREC` field list totals 41 bytes against a declared `LRECL=40`; the declared length wins and the divergence is logged |
| 17 | `READACCT.jcl` | CBACT01C | `batch/readers/AccountReader.java` | read-only verification step |
| 18 | `READCARD.jcl` | CBACT02C | `batch/readers/CardReader.java` | read-only verification step |
| 19 | `READCUST.jcl` | CBCUS01C | `batch/readers/CustomerReader.java` | read-only verification step |
| 20 | `READXREF.jcl` | CBACT03C | `batch/readers/CardCrossReferenceReader.java` | read-only verification step |
| 21 | `REPTFILE.jcl` | IDCAMS | `localstack-init/init-aws.sh` | `LIMIT(10)` at `:L27` &mdash; **conflicts** with `DEFGDGB.jcl:L37`&ndash;`:L38` |
| 22 | `TCATBALF.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 23 | `TRANBKP.jcl` | IDCAMS + REPROC | `batch/jobs/CombineTransactionsJob.java` &mdash; `combineTransactionsArchiveStep`, `executeTranbkp`, `ArchivePageEnumeration`; the generation is read back by `batch/readers/CombinedTransactionReader.java` | archive-and-reset step, gated by the `archiveAndResetMaster` parameter the pipeline's combine stage sets. `:L23`&ndash;`:L33` unloads the master to `TRANSACT.BKUP(+1)` at `LRECL=350`, then `:L37`&ndash;`:L67` empties it so `COMBTRAN.jcl:L48` loads into an empty target &mdash; **the property that makes the daily stream repeatable** |
| 24 | `TRANCATG.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 25 | `TRANFILE.jcl` | IDCAMS + SDSF | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 26 | `TRANIDX.jcl` | IDCAMS | `db/migration/V2__create_indexes.sql` | alternate indexes &rarr; B-tree indexes |
| 27 | `TRANREPT.jcl` | CBTRN03C + REPROC + SORT | `batch/jobs/TransactionReportJob.java` | job &rarr; job |
| 28 | `TRANTYPE.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |
| 29 | `XREFFILE.jcl` | IDCAMS | `db/migration/V1__create_schema.sql` | provisioning &rarr; DDL |

**Three members have no Java analogue and none was invented:** `CBADMCDJ` installs the CICS
resource definitions and is superseded by `src/main/java/com/cardemo/config/SecurityConfig.java`; `OPENFIL` and `CLOSEFIL`
manage online dataset availability and are superseded by
`src/main/java/com/cardemo/observability/HealthIndicators.java`. Documenting the substitution is the honest treatment &mdash;
fabricating COBOL methods for them would not be.

### 8.1 Procedures and control cards

| Member | Steps | Target | Note |
|---|---|---|---|
| `app/proc/TRANREPT.prc` | `STEP01R` backup, `STEP05R` filtered sort, `STEP10R` report | `batch/jobs/TransactionReportJob.java` | Sort by card number with an inclusive `INCLUDE COND` date range; report `LRECL=133` at `:L76`, work file `LRECL=350` at `:L29` |
| `app/proc/REPROC.prc` | `IDCAMS` | `batch/jobs/BatchPipelineOrchestrator.java` | Invokes the control card below |
| `app/ctl/REPROCT.ctl` | &mdash; | `JdbcTemplate.batchUpdate` | A single card: `REPRO INFILE(FILEIN) OUTFILE(FILEOUT)` &mdash; [`DL-MS-08`](DECISION_LOG.md#dl-ms-08) |

**A preserved legacy quirk in the procedure library:** both procedure members declare
`//REPROC PROC` on their first line, so `TRANREPT.prc`'s *internal* procedure name is `REPROC`
while the member name that `EXEC PROC=TRANREPT` resolves is `TRANREPT`
[`app/proc/TRANREPT.prc:L1`]. Logged, not corrected &mdash;
[`DL-LD-03`](DECISION_LOG.md#dl-ld-03).

### 8.2 Record geometry, which is load-bearing

| Output | Bytes | Declared at | Target |
|---|---:|---|---|
| Reject record | **430** = 350 data + 80 trailer (4-digit reason + 76-character description) | `app/jcl/POSTTRAN.jcl:L36` | `batch/writers/RejectWriter.java` |
| Report line | **133** | `app/proc/TRANREPT.prc:L76` | `batch/jobs/TransactionReportJob.java` |
| Statement text | **80** | `app/jcl/CREASTMT.JCL:L69`, `:L73`, `:L89` | `batch/writers/StatementWriter.java` |
| Statement markup | **100** | `app/jcl/CREASTMT.JCL:L94` | `batch/writers/StatementWriter.java` |
| Transaction image | **350** | `app/jcl/CREASTMT.JCL:L50`, `app/proc/TRANREPT.prc:L29` | `batch/writers/TransactionWriter.java` |

The 80-versus-100 disagreement for the markup output between `:L89` and `:L94` is a **legacy
defect that is logged, not repaired** &mdash; [`DL-LD-02`](DECISION_LOG.md#dl-ld-02). The
100-character field width in `CBSTM03A` independently confirms that 100 is the operative value.

<a id="f-021"></a>

### 8.3 F-021 Combine Transactions &mdash; the one feature with no COBOL program

**Every other feature in the catalogue is anchored by a program**, so the roster in
[section 5.0](#matrix) carries its feature identifier and the paragraph rows carry its behaviour.
`F-021` has no such anchor: `app/jcl/COMBTRAN.jcl` is two steps of DFSORT and IDCAMS control cards
with **no `EXEC PGM=` naming any member of `app/cbl`**, so there is no paragraph to map and no row
in section 5 can carry it. It was consequently absent from this page's feature roster while
`F-001`&hellip;`F-020` and `F-022` were present &mdash; a gap in the *catalogue*, not in the
implementation, which has existed and been tested throughout. This section is its registration, and
**no COBOL program is invented to host it**.

| | |
|---|---|
| Feature | **F-021 Combine Transactions** |
| Source of record | [`app/jcl/COMBTRAN.jcl`](app/jcl/COMBTRAN.jcl) &mdash; `STEP05R` sorts, `STEP10R` loads |
| COBOL program | **None.** Verified: the member's only two `EXEC` statements are `EXEC PGM=SORT` at `:L22` and `EXEC PGM=IDCAMS` at `:L41`, and `grep -c 'EXEC PGM=CB' app/jcl/COMBTRAN.jcl` returns **0** |
| Sort specification | `SORT FIELDS=(TRAN-ID,A)` at `:L30` over the symbol `TRAN-ID,1,16,CH` at `:L28`, applied to a **concatenated** `SORTIN` of `TRANSACT.BKUP(0)` at `:L24` and `SYSTRAN(0)` at `:L26` &mdash; the transaction backup and the interest job's fresh generation |
| Load | `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` at `:L48`, into `TRANSACT.VSAM.KSDS` |
| Java targets | `batch/jobs/CombineTransactionsJob.java`, `batch/processors/TransactionCombineProcessor.java`, `batch/readers/CombinedTransactionReader.java` |
| Decision | [`DL-MS-08`](DECISION_LOG.md#dl-ms-08) for the sort and load substitution; [`DL-PP-07`](DECISION_LOG.md#dl-pp-07) for why the interest job's output only reaches the keyed cluster here |
| Duplicate-key exposure | This job, not the interest job, is where a repeated date parameter surfaces as a duplicate key: the load must raise a duplicate-record failure and a failed exit status, **never a silent upsert** &mdash; [`DL-PP-07`](DECISION_LOG.md#dl-pp-07) |
| Test | `src/test/java/com/cardemo/integration/batch/CombineTransactionsJobTest.java`, `src/test/java/com/cardemo/unit/batch/TransactionCombineProcessorTest.java` |
| Evidence | [Gate 7](docs/validation-gates.md#gate-7) &mdash; its scope-coverage check counts the program-less feature explicitly rather than deriving the feature list from `app/cbl` |

**Why this is a registration and not a new mapping.** The job, the processor, the reader and both
test classes existed before this section did; what was missing was the catalogue line that says the
feature is accounted for and where its source of record lives. Recording the absence of a program is
the substance of the entry, because a reader who expects twenty-two program-anchored features would
otherwise go looking for `COMBTRAN.cbl` and find nothing.

<a id="copybooks"></a>

## 9. Copybook map &mdash; all 28 members

**28 members, 2,614 lines.** `COSTM01.CPY` is the only uppercase file name.

| # | Copybook | Class | Target | Note |
|---:|---|---|---|---|
| 1 | `CVACT01Y.cpy` | record layout | `model/entity/Account.java` | 300 bytes, key 11 |
| 2 | `CVACT02Y.cpy` | record layout | `model/entity/Card.java` | 150 bytes, key 16 |
| 3 | `CVACT03Y.cpy` | record layout | `model/entity/CardCrossReference.java` | 16+9+11 = 36 populated bytes in a 50-byte slot; the 14-byte slack is not modelled |
| 4 | `CVCUS01Y.cpy` | record layout | `model/entity/Customer.java` | 500 bytes, key 9 |
| 5 | `CUSTREC.cpy` | record layout | `model/entity/Customer.java` | **the same layout as `CVCUS01Y`, differing only in the date-of-birth field name** &mdash; one entity serves both |
| 6 | `CVTRA01Y.cpy` | record layout | `model/entity/TransactionCategoryBalance.java` + `model/key/TransactionCategoryBalanceId.java` | 50 bytes, composite key 17. `TRAN-CAT-BAL` is `S9(09)V99` &rarr; `NUMERIC(11,2)`, **not** 12,2 |
| 7 | `CVTRA02Y.cpy` | record layout | `model/entity/DisclosureGroup.java` + `model/key/DisclosureGroupId.java` | 50 bytes, key 16. `DIS-INT-RATE` is `S9(04)V99` &rarr; `NUMERIC(6,2)` |
| 8 | `CVTRA03Y.cpy` | record layout | `model/entity/TransactionType.java` | 60 bytes, key 2 |
| 9 | `CVTRA04Y.cpy` | record layout | `model/entity/TransactionCategory.java` + `model/key/TransactionCategoryId.java` | 60 bytes, composite key 6 |
| 10 | `CVTRA05Y.cpy` | record layout | `model/entity/Transaction.java` | 350 bytes, key 16; the proven offset map |
| 11 | `CVTRA06Y.cpy` | record layout | `model/entity/DailyTransaction.java` | 350-byte staging layout |
| 12 | `CVTRA07Y.cpy` | record layout | `model/dto/StatementTransaction.java` | 133-byte report line |
| 13 | `CSUSR01Y.cpy` | additional layout | `model/entity/UserSecurity.java` | 80 bytes: `ID X(8)` + `FNAME X(20)` + `LNAME X(20)` + `PWD X(8)` + `TYPE X(1)` + filler. The 8-character password becomes a 60-character BCrypt column |
| 14 | `COSTM01.CPY` | additional layout | `model/dto/StatementTransaction.java` | 32-byte `TRNX-KEY` = 16-character card number + 16-character identifier, matching `KEYS(32 0)` on the work cluster; 318-byte remainder for a 350-byte total |
| 15 | `COCOM01Y.cpy` | shared state | `model/dto/CommArea.java`, JWT claims | **split**: `CDEMO-USER-ID` &rarr; subject claim, `CDEMO-USER-TYPE` &rarr; role claim, identifiers &rarr; DTO fields, paging &rarr; request/response metadata; routing and re-entry fields have no counterpart |
| 16 | `CVCRD01Y.cpy` | shared state | `config/WebConfig.java`, controller request mapping | navigation and action-identifier state &rarr; URL routing |
| 17 | `COMEN02Y.cpy` | table | `service/menu/MainMenuService.java` | 10 populated menu slots |
| 18 | `COADM02Y.cpy` | table | `service/menu/AdminMenuService.java` | 4 populated admin slots |
| 19 | `CSLKPCDY.cpy` | table | `resources/validation/nanpa-area-codes.json`, `us-state-codes.json`, `state-zip-prefixes.json` | **collapse rule 3**: five 88-level tables &rarr; one service over three classpath resources, not a generated constants class of over a thousand lines |
| 20 | `CSDAT01Y.cpy` | shared state | date and time header utility | screen header date/time |
| 21 | `COTTL01Y.cpy` | constants | constants holder | screen titles |
| 22 | `CSMSG01Y.cpy` | constants | constants holder | common messages |
| 23 | `CSMSG02Y.cpy` | abend work area | `exception/FatalProcessingException.java` | **internally titled `CABENDD.CPY`** (`:L000800`) and described as *Work areas for abend routine* (`:L001000`). It is **not** a message copybook. Carries `ABEND-CODE X(4)`, `ABEND-CULPRIT X(8)`, `ABEND-REASON X(50)`, `ABEND-MSG X(72)`, which become the fatal exception's payload |
| 24 | `CSUTLDPY.cpy` | procedural | `service/shared/DateValidationService.java` | **collapse rule 1** |
| 25 | `CSUTLDWY.cpy` | work area | `service/shared/DateValidationService.java` | **collapse rule 1** |
| 26 | `CSSTRPFY.cpy` | procedural | controller-level action mapping | **Procedural, not a data layout.** Contains the paragraph `YYYY-STORE-PFKEY`, which evaluates `EIBAID`. It is copied into a `PROCEDURE DIVISION`, so it has **no entity or DTO counterpart** and yields no Java type |
| 27 | `CSSETATY.cpy` | template | `@Valid` annotations + per-field error markers | **A parameterised template resolved via `COPY … REPLACING`.** No importable type; it maps onto a framework mechanism |
| 28 | `UNUSED1Y.cpy` | dead | **none** | **Zero `COPY` references repository-wide** (measured: 0). Dispositioned as documented dead so the census reaches 28 without pretending it is used |

### 9.1 The three collapse rules

| # | Rule | Effect |
|---|---|---|
| 1 | `CALL 'CSUTLDTC'` + `COPY CSUTLDPY` + `COPY CSUTLDWY` &rarr; **one** injected `service/shared/DateValidationService.java` | A static call and two work-area copybooks become a single bean |
| 2 | `CALL 'CBSTM03B' USING WS-M03B-AREA` &rarr; **one** injected `service/shared/FileService.java` | The four-dataset by six-operation call surface becomes one keyed handler map. **The matrix is twelve of twenty-four, not twenty-four**: six operation codes are declared at `CBSTM03B.CBL:L102`&ndash;`:L108` and four DD names dispatched at `:L118`&ndash;`:L128`, but each handler implements only `OPEN`, `CLOSE` and one of the two reads, so `WRITE` and `REWRITE` are implemented by no dataset at all. The twelve unimplemented cells fail **open** in the source &mdash; an unimplemented operation republishes the dataset's stale status and an unknown DD name leaves the return code untouched at the caller's pre-set success &mdash; and the adapter's default entry point refuses both, keeping the byte-exact behaviour under `executeInLegacyParityMode`. See [`DL-DV-09`](DECISION_LOG.md#dl-dv-09) and [`DL-DV-07`](DECISION_LOG.md#dl-dv-07) |
| 3 | `COPY CSLKPCDY` &rarr; **one** service over **three** classpath JSON resources | Exact membership preserved as data instead of generated constants |

See [`DL-MS-03`](DECISION_LOG.md#dl-ms-03).

### 9.2 CICS-supplied copybooks, and names that must never be cited

`COPY DFHAID`, `COPY DFHBMSCA` and `COPY DFHATTR` are supplied by the transaction monitor. They
are **not present in this repository**, they are not part of the 28, and they map onto framework
mechanisms rather than types. **No repository target exists for them and none may be invented.**

These four member names circulate in prose and **do not exist on disk**. Citing any of them is
prohibited, because a reference to a non-existent copybook cannot be verified and cannot compile:
`COSTM01Y.cpy`, `CVACT04Y.cpy`, `CVACT05Y.cpy`, `CVCRD02Y.cpy`. The real members are
`COSTM01.CPY` and `CVCRD01Y.cpy`.

<a id="vsam"></a>

## 10. VSAM catalogue map &mdash; `app/catlg/LISTCAT.txt`

3,956 lines. The derived entry-kind census folds exactly to the file's own declared total at
`:L3937`&ndash;`:L3951`: AIX 3, ALIAS 0, CLUSTER 10, DATA 13, GDG 7, INDEX 13, NONVSAM 160,
PAGESPACE 0, PATH 3, SPACE 0, USERCATALOG 0, TAPELIBRARY 0, TAPEVOLUME 0, **TOTAL 209**.

### 10.1 The ten base clusters

| Cluster | `KEYLEN` | `AVGLRECL` | Entity | Repository | Table |
|---|---:|---:|---|---|---|
| `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | 11 | 300 | `Account` | `AccountRepository` | `account` |
| `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` | 16 | 150 | `Card` | `CardRepository` | `card` |
| `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | 16 | 50 | `CardCrossReference` | `CardCrossReferenceRepository` | `card_cross_reference` |
| `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | 9 | 500 | `Customer` | `CustomerRepository` | `customer` |
| `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS` | 16 | 50 | `DisclosureGroup` | `DisclosureGroupRepository` | `disclosure_group` |
| `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` | 17 | 50 | `TransactionCategoryBalance` | `TransactionCategoryBalanceRepository` | `transaction_category_balance` |
| `AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS` | 6 | 60 | `TransactionCategory` | `TransactionCategoryRepository` | `transaction_category` |
| `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` | 16 | 350 | `Transaction` | `TransactionRepository` | `transaction` |
| `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` | 2 | 60 | `TransactionType` | `TransactionTypeRepository` | `transaction_type` |
| `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS` | 8 | 80 | `UserSecurity` | `UserSecurityRepository` | `user_security` |

**`USRSEC` geometry is corroborated twice, not once.** The catalogue records `KEYLEN 8` /
`AVGLRECL 80`, and `app/jcl/DUSRSECJ.jcl:L65`&ndash;`:L68` independently declares
`KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED`. Both agree; the catalogue is **not** silent on this
cluster. Any statement that `USRSEC` is defined only in job control is wrong &mdash; see finding
`TM-M-2`.

### 10.2 Three alternate indexes, each with a path

| Alternate index | `KEYLEN` | `RKP` | `AXRKP` | What sits there | Target |
|---|---:|---:|---:|---|---|
| `AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX` | 11 | 5 | **16** | the account id at byte 16 of the 150-byte card record | `CardRepository` derived finder + B-tree index |
| `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX` | 11 | 5 | **25** | bytes 26&ndash;36 of the 36-byte cross-reference record, i.e. the account id, which is what the `CXACAIX` path serves | `CardCrossReferenceRepository` derived finder + B-tree index |
| `AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX` | 26 | 5 | **304** | the 26-character processing timestamp | `TransactionRepository` finder + B-tree index |

Each has exactly one matching path: `CARDDATA.VSAM.AIX.PATH`, `CARDXREF.VSAM.AIX.PATH`,
`TRANSACT.VSAM.AIX.PATH`. All three become `db/migration/V2__create_indexes.sql` indexes &mdash;
[`DL-MS-04`](DECISION_LOG.md#dl-ms-04). **There are three, not two**; a matrix carrying two would
be missing a finder and an index.

### 10.3 Seven generation-group bases

| # | Base | Defined at | Declared limit | Record length | S3 target |
|---:|---|---|---|---:|---|
| 1 | `AWS.M2.CARDDEMO.TRANSACT.BKUP` | `app/jcl/DEFGDGB.jcl:L25` | `LIMIT(5)` `:L26` | 350 | prefixed object over a versioned bucket |
| 2 | `AWS.M2.CARDDEMO.TRANSACT.DALY` | `app/jcl/DEFGDGB.jcl:L31` | `LIMIT(5)` `:L32` | 350 | prefixed object over a versioned bucket |
| 3 | `AWS.M2.CARDDEMO.TRANREPT` | `app/jcl/DEFGDGB.jcl:L37` | `LIMIT(5)` `:L38` | 133 | prefixed object over a versioned bucket |
| 4 | `AWS.M2.CARDDEMO.TCATBALF.BKUP` | `app/jcl/DEFGDGB.jcl:L43` | `LIMIT(5)` `:L44` | 50 | prefixed object over a versioned bucket |
| 5 | `AWS.M2.CARDDEMO.SYSTRAN` | `app/jcl/DEFGDGB.jcl:L49` | `LIMIT(5)` `:L50` | 350 | prefixed object over a versioned bucket |
| 6 | `AWS.M2.CARDDEMO.TRANSACT.COMBINED` | `app/jcl/DEFGDGB.jcl:L55` | `LIMIT(5)` `:L56` | 350 | prefixed object over a versioned bucket |
| 7 | `AWS.M2.CARDDEMO.DALYREJS` | **`app/jcl/DALYREJS.jcl:L25`** | `LIMIT(5)` `:L26` | 430 | prefixed object over a versioned bucket |

**There are seven, not six.** `DEFGDGB.jcl` defines only six; the seventh, `DALYREJS`, is defined
in its own member. A map carrying six is missing the reject output prefix entirely.

**The retention conflict is real and is resolved rather than propagated.** `TRANREPT` is declared
`LIMIT(5)` at `app/jcl/DEFGDGB.jcl:L37`&ndash;`:L38` and `LIMIT(10)` at
`app/jcl/REPTFILE.jcl:L27`. Resolved **in favour of 10**, because a single lifecycle value has to
be chosen &mdash; [`DL-CR-02`](DECISION_LOG.md#dl-cr-02). Relative generation references become
deterministic prefixes over a versioned bucket; retention is **documented, not enforced** &mdash;
[`DL-MS-10`](DECISION_LOG.md#dl-ms-10), [`DL-RR-06`](DECISION_LOG.md#dl-rr-06).

One further catalogue finding: `app/jcl/DEFCUST.jcl` defines an **orphan** cluster
`AWS.CUSTDATA.CLUSTER` with `KEYS(10 0) RECORDSIZE(500 500)` that no program opens. It is
recorded rather than migrated.

<a id="bms"></a>

## 11. Screen map &mdash; all 17 sourced mapsets and symbolic maps

17 mapsets (`app/bms/**`, 4,472 lines) with their 17 generated symbolic maps
(`app/cpy-bms/**`, 5,632 lines).

**The derived input-field census is 441.** The generated shape, read off
`app/cpy-bms/COACTUP.CPY:L17`&ndash;`:L24`, is **six declarations per field, five of them at level 02
and exactly one at level 03**: `&hellip;L` a `COMP PIC S9(4)` length field, `&hellip;F` a one-byte
attribute field, an `02 FILLER REDEFINES &hellip;F` group, an `02 FILLER PICTURE X(4)` of reserved
bytes and `&hellip;I` the data field &mdash; with the group's single member, the attribute alias
`&hellip;A PICTURE X`, being the **only level-03 item on the page**.

**Two independent parses agree on 441, which is what makes the figure auditable rather than asserted.**
Counting the level-02 `&hellip;I` picture declarations counts each screen field exactly once:

```shell
for f in app/cpy-bms/*.CPY; do
  printf '%s %s\n' "$(basename "$f")" "$(grep -cE '^ +02 +[A-Z0-9]+I +PIC ' "$f")"
done
```

That reproduces every per-map cell in the table below and sums to **441**, and the independent count
of the `&hellip;L` length items agrees. Counting level-03 declarations counts attribute aliases
instead, and because the generator emits exactly one alias per input field that count *is* the same
census: the level histogram across all seventeen symbolic maps is **34 at level 01, 4,885 at level 02
and 441 at level 03**, and all 441 level-03 items fall inside the seventeen `&hellip;I` input groups
&mdash; the redefining `&hellip;O` output groups, whose members are the `C`, `P`, `H`, `V` and `O`
fields, carry **no level-03 item at all**. The thirty-four level-01 groups are the seventeen input
groups and their seventeen output redefinitions.

**Two earlier revisions of this paragraph are withdrawn.** One described the five level-02 members as
the quintuple that a level-03 count counts, which conflated the alias with the data field. The other
corrected that by asserting a level-03 count returns **0**, which is wrong in the opposite direction
&mdash; it returns 441. The census figure was never in doubt; both stated methods were.

| Symbolic map | Input fields | Mapset | DTO target | Controller |
|---|---:|---|---|---|
| `COACTUP.CPY` | 54 | `coactup.bms` | `model/dto/AccountUpdateRequest.java` | `controller/AccountController.java` |
| `COACTVW.CPY` | 37 | `coactvw.bms` | `model/dto/AccountDto.java` | `controller/AccountController.java` |
| `COADM01.CPY` | 20 | `coadm01.bms` | `model/dto/MenuResponse.java` | `controller/MenuController.java` |
| `COBIL00.CPY` | 10 | `cobil00.bms` | `model/dto/BillPaymentRequest.java` | `controller/BillingController.java` |
| `COCRDLI.CPY` | 45 | `cocrdli.bms` | `model/dto/CardDto.java`, `model/dto/PageResponse.java` | `controller/CardController.java` |
| `COCRDSL.CPY` | 15 | `cocrdsl.bms` | `model/dto/CardDto.java` | `controller/CardController.java` |
| `COCRDUP.CPY` | 17 | `cocrdup.bms` | `model/dto/CardUpdateRequest.java` | `controller/CardController.java` |
| `COMEN01.CPY` | 20 | `comen01.bms` | `model/dto/MenuResponse.java` | `controller/MenuController.java` |
| `CORPT00.CPY` | 17 | `corpt00.bms` | `model/dto/ReportRequest.java` | `controller/ReportController.java` |
| `COSGN00.CPY` | 11 | `cosgn00.bms` | `model/dto/SignOnRequest.java`, `model/dto/SignOnResponse.java` | `controller/AuthController.java` |
| `COTRN00.CPY` | 59 | `cotrn00.bms` | `model/dto/TransactionDto.java`, `model/dto/PageResponse.java` | `controller/TransactionController.java` |
| `COTRN01.CPY` | 21 | `cotrn01.bms` | `model/dto/TransactionDto.java` | `controller/TransactionController.java` |
| `COTRN02.CPY` | 21 | `cotrn02.bms` | `model/dto/TransactionAddRequest.java` | `controller/TransactionController.java` |
| `COUSR00.CPY` | 59 | `cousr00.bms` | `model/dto/UserSecurityDto.java`, `model/dto/PageResponse.java` | `controller/AdminController.java` |
| `COUSR01.CPY` | 12 | `cousr01.bms` | `model/dto/UserCreateRequest.java` | `controller/AdminController.java` |
| `COUSR02.CPY` | 12 | `cousr02.bms` | `model/dto/UserUpdateRequest.java` | `controller/AdminController.java` |
| `COUSR03.CPY` | 11 | `cousr03.bms` | `model/dto/UserSecurityDto.java` | `controller/AdminController.java` |
| **Total, 17 maps** | **441** | | | |

The three largest maps are large because they carry **row arrays**, not richer screens:
`COTRN00` and `COUSR00` carry ten-row tables and `COCRDLI` carries seven, which is exactly the
pagination the services preserve &mdash; page size **7** for the card list, **10** for the
transaction and user lists.

Six header fields recur on all seventeen maps and are preceded by a twelve-byte terminal I/O
area header: `TRNNAME X(4)`, `TITLE01 X(40)`, `CURDATE X(8)`, `PGMNAME X(8)`, `TITLE02 X(40)`,
`CURTIME X(9)`.

**The figure of 460 that circulates in prose is wrong, and is also internally inconsistent with
its own per-map table, which sums to 440.** The single differing cell is `COACTVW`, which carries
**37** fields and not 36. Recorded as finding `TM-M-1` with its remediation.

No user interface is reimplemented. The symbolic maps are consumed as **DTO field contracts** only;
there is no 3270 emulation, no green-screen rendering and no front end &mdash;
[`DL-MS-05`](DECISION_LOG.md#dl-ms-05).

<a id="fixtures"></a>

## 12. Fixture map

### 12.1 The nine ASCII fixtures

| Fixture | Bytes | Rows | Record width | Target | Note |
|---|---:|---:|---:|---|---|
| `app/data/ASCII/acctdata.txt` | 15,050 | 50 | 300 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | corroborates `ACCTDATA` 300 |
| `app/data/ASCII/carddata.txt` | 7,550 | 50 | 150 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | corroborates `CARDDATA` 150 |
| `app/data/ASCII/cardxref.txt` | 1,850 | 50 | 36 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | 36 populated bytes in a 50-byte cluster record |
| `app/data/ASCII/custdata.txt` | 25,050 | 50 | 500 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | corroborates `CUSTDATA` 500 |
| `app/data/ASCII/dailytran.txt` | 105,300 | 300 | 350 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | **the Gate 1 and Gate 4 fixture.** Spelled in full &mdash; the dataset and DD name are `DALYTRAN`, the file is **`dailytran.txt`** |
| `app/data/ASCII/discgrp.txt` | 2,601 | 51 | 50 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | **17 of the 51 rows carry the literal group `DEFAULT`**, including zero-rate combinations, which is what makes both the fallback success path and the abend path testable |
| `app/data/ASCII/tcatbal.txt` | 2,550 | 50 | 50 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | key 11+2+4 = 17 confirms `KEYLEN 17` |
| `app/data/ASCII/trancatg.txt` | 1,098 | 18 | 60 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | corroborates `TRANCATG` 60 |
| `app/data/ASCII/trantype.txt` | 427 | 7 | 60 | `db/migration/V3__seed_data.sql`, `src/test/resources/**` | corroborates `TRANTYPE` 60 |

**There is no `app/data/ASCII/usrsec.txt`** &mdash; verified absent. The ten user records exist
**only** as inline `SYSUT1 DD *` data inside `app/jcl/DUSRSECJ.jcl`, fed through `IEBGENER`: five
administrators of type `A` (`ADMIN001`&hellip;`ADMIN005`) and five standard users of type `U`
(`USER0001`&hellip;`USER0005`), in the 80-byte `CSUSR01Y` layout. Every one carries the same
literal placeholder password in the source, and the seed migration stores **only BCrypt hashes at
strength 10**, under the local and test profiles alone &mdash;
[`DL-MS-14`](DECISION_LOG.md#dl-ms-14). No credential value is reproduced on this page.

**Signed numerics use zoned-decimal trailing-sign overpunch, so a naive text load produces wrong
values.** The decode table is `{`&rarr;+0, `A`&ndash;`I`&rarr;+1&hellip;+9, `}`&rarr;&minus;0,
`J`&ndash;`R`&rarr;&minus;1&hellip;&minus;9. Decoding must be **position-aware from the PIC
clauses**, because the same letters occur legitimately inside text fields such as merchant names
&mdash; [`DL-PP-08`](DECISION_LOG.md#dl-pp-08). `dailytran.txt` contains **both** `{` and `}`, so
it carries genuinely negative amounts and therefore exercises the cycle-debit branch of the
posting logic; it must not be normalised.

**The seed row contract has two totals, and both are correct.** `V3__seed_data.sql` carries
**636** rows as text &mdash; the 626 unconditional reference and fixture rows counted above
(50 + 50 + 50 + 50 + 300 + 51 + 50 + 18 + 7) plus the **10 gated** demonstration principals. The
credential statement is the file's only conditional one, so the **applied** total is **636 where
the gate is open** (the local and test profiles) and **626 where it is closed** (the base profile
default and production). Neither figure supersedes the other, and stating either as unconditional
is the error to avoid. `unit/infrastructure/InventoryCountGateTest` pins the textual 636 and the
626/10 split; `unit/model/DemoUserSeedGateContractTest` pins the gate itself and its per-profile
values.

### 12.1a The one persisted column no fixture and no copybook supplies

`daily_transaction` carries **fourteen** columns where `app/cpy/CVTRA06Y.cpy` declares
**thirteen** fields. The extra one is `ingest_seq`, and it is deliberate: `DALYTRAN` is a
**physical sequential** dataset with no key, so a record's position in the file is its identity,
and `app/cbl/CBTRN02C.cbl` already computes exactly that ordinal in
`WS-TRANSACTION-COUNT` &mdash; declared `PIC 9(09) VALUE 0` at `:L185`, incremented once per
accepted read at `:L206`, reported at `:L227`.

| Column | Source | Target | Decision | Test | Status |
|---|---|---|---|---|---|
| `ingest_seq` `NUMERIC(9)` PK | `CBTRN02C.cbl` `WS-TRANSACTION-COUNT` `:L185`, `:L206`, `:L227` &middot; **no copybook line, no record bytes** | `model/entity/DailyTransaction.java` `ingestSequence`; `db/migration/V1__create_schema.sql` `pk_daily_transaction`; `batch/readers/DailyTransactionReader.java` keyset scan | [`DL-MS-17`](DECISION_LOG.md#dl-ms-17) | `unit/model/StagingKeyParityTest.java::theStagingKeyChangesNoByteOfTheEmittedRecord` | `Target-verified` |

Three properties make this a substitution of mechanism rather than a change of contract, and each
is asserted rather than argued: the ordinal **occupies none of the 350 record bytes** and appears
in no byte-offset map; two staged records differing only in it emit **byte-identical 430-byte
reject images**; and the loader assigns it in read order, so the migration creates **zero**
`GENERATED` clauses, `IDENTITY` columns and sequences. Its name is the marker &mdash; every column
derived from the copybook carries the `dalytran_` prefix and this one deliberately does not.

### 12.2 EBCDIC, excluded

`app/data/EBCDIC/**` holds **12 `.PS` files plus a `.gitkeep`**, retained as byte-level codepage
reference only. **They are never parsed by the build** and no transcoding utility was written.
The set includes `AWS.M2.CARDDEMO.ACCDATA.PS` &mdash; note the missing `T`, a legacy typo
duplicate of `ACCTDATA.PS` &mdash; and `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`.

<a id="scope"></a>

## 13. Out of scope and residual risk

| Item | Standing | Why | Reference |
|---|---|---|---|
| Any edit to `app/**` | **Excluded** | The corpus is the parity oracle, the field-contract source and the traceability anchor simultaneously, and it loses all three roles the moment it is edited. `git diff 7756d89 HEAD -- app/` returns empty | &mdash; |
| `COCRDSEC` | **OUT OF SCOPE &mdash; source absent** | Defined in the CSD at `:L211` and `:L390` as the target of `CDV1` at `:L388`, with no source anywhere. No row, no endpoint, no invention | [`DL-RR-03`](DECISION_LOG.md#dl-rr-03) |
| `samples/**` | **Excluded** | Three z/OS compile templates, three build procedures and two binary emulator runtime bundles. Superseded conceptually by `pom.xml`; nothing is ported | &mdash; |
| `app/data/EBCDIC/**` | **Excluded** | Codepage reference only; the ASCII fixtures are authoritative | &mdash; |
| 3270 / BMS terminal emulation, any front end | **Excluded** | The interface is REST and JSON plus Actuator. All 441 symbolic-map fields are consumed as DTO contracts, not reimplemented as a UI | [`DL-MS-05`](DECISION_LOG.md#dl-ms-05) |
| Microservices, event sourcing, CQRS | **Excluded** | The dual-dataset account write and the three-dataset posting write are transactional invariants; distributing them would require compensating transactions and change failure semantics | [`DL-AR-01`](DECISION_LOG.md#dl-ar-01) |
| Kubernetes, Helm, service mesh | **Excluded** | Container orchestration stops at Docker Compose | &mdash; |
| Live cloud accounts and real credentials | **Excluded** | All interaction targets a local emulator. **Zero live credentials in any file**, and no code path may reach a live endpoint | [`DL-RR-05`](DECISION_LOG.md#dl-rr-05) |
| Rewriting COBOL business rules to be "more correct" | **Excluded** | Parity is the contract. Known quirks are preserved and documented, never repaired | [`DL-CR-01`](DECISION_LOG.md#dl-cr-01) |
| Eight deferred hardening items | **Residual risk** | Table partitioning, read replicas, connection-pool tuning, TLS termination, request rate limiting, URI-based API versioning, generated OpenAPI, encryption at rest for personal data | [`DL-RR-02`](DECISION_LOG.md#dl-rr-02) |
| Generation retention beyond object versioning | **Residual risk** | Relative generation references become deterministic prefixes; retention is documented, not enforced | [`DL-RR-06`](DECISION_LOG.md#dl-rr-06) |
| The retained identifier-generation race | **Residual risk** | Kept deliberately for parity; a collision surfaces as a duplicate-record exception rather than being pre-empted by a sequence | [`DL-RR-04`](DECISION_LOG.md#dl-rr-04) |
| Framework support horizon | **Residual risk** | The pinned line is honoured exactly as instructed rather than unilaterally advanced | [`DL-RR-01`](DECISION_LOG.md#dl-rr-01) |

### 13.1 Legacy defects logged, not repaired

| Defect | Locator | Reference |
|---|---|---|
| A corrupted data-definition line in the statement job | `app/jcl/CREASTMT.JCL` | [`DL-LD-01`](DECISION_LOG.md#dl-ld-01) |
| Markup output record length disagrees between two steps: 80 versus 100 | `app/jcl/CREASTMT.JCL:L89` versus `:L94` | [`DL-LD-02`](DECISION_LOG.md#dl-ld-02) |
| A procedure whose internal name differs from the member name | `app/proc/TRANREPT.prc:L1` | [`DL-LD-03`](DECISION_LOG.md#dl-ld-03) |
| Reject code 103 overwrites 102 | `app/cbl/CBTRN02C.cbl:L393`&ndash;`:L422` | [`DL-LD-04`](DECISION_LOG.md#dl-ld-04) |
| Control break on the card number under an `Account Total` label | `app/cbl/CBTRN03C.cbl:L306`&ndash;`:L317` | [`DL-LD-05`](DECISION_LOG.md#dl-ld-05) |
| No self-delete guard in user deletion | `app/cbl/COUSR03C.cbl:L174`&ndash;`:L192` | [`DL-LD-06`](DECISION_LOG.md#dl-ld-06) |
| A redundant index assignment in the statement program | `app/cbl/CBSTM03A.CBL:L324` | [`DL-LD-07`](DECISION_LOG.md#dl-ld-07) |
| Source misspellings that are field or literal contracts | `WIRTE-JOBSUB-TDQ` at `app/cbl/CORPT00C.cbl:L515`; `ACCT-EXPIRAION-DATE` in `app/cpy/CVACT01Y.cpy` | [`DL-LD-08`](DECISION_LOG.md#dl-ld-08) |
| A duplicate paragraph label | `app/cbl/COACTVWC.cbl:L408` and `:L411` | [`DL-LD-08`](DECISION_LOG.md#dl-ld-08) |

**The only legacy inconsistency actually resolved is the retention-limit conflict**, and only
because a single lifecycle value has to be chosen &mdash;
[`DL-CR-02`](DECISION_LOG.md#dl-cr-02).

### 13.2 The tracked retained no-ops, keyed by identifier

*Rule 1* clause B forbids **untracked** dead code. Tracking is what satisfies it, and the register
below is **keyed by stable identifier and locator rather than by position in a list**, so a row can
be added or removed without renumbering anything and **no total is declared**:

| ID | Retained artefact | Locator | Row | Why it stays | Reference |
|---|---|---|---|---|---|
| `NOOP-CBACT04C-1400` | The empty `1400-COMPUTE-FEES` paragraph | `app/cbl/CBACT04C.cbl:L518`&ndash;`:L520`, performed from `:L216` | `TM-CBACT04C-R015` | It is **reachable**: the source calls it. Deleting the call site would break the paragraph map this page publishes | [`DL-PP-05`](DECISION_LOG.md#dl-pp-05) |
| `NOOP-CBTRN02C-109` | Reject code 109 | `app/cbl/CBTRN02C.cbl:L556`, paragraph `:L545`&ndash;`:L560`, cleared at `:L208` | `TM-CBTRN02C-R018` | Assigned on a reachable path but never observable as a reject outcome, because the paragraph runs only on the already-validated path. The constant must exist because the assignment is real code &mdash; hence exactly **five** reject constants, not four | [`DL-PP-03`](DECISION_LOG.md#dl-pp-03) |
| `NOOP-CBSTM03A-CRJMP` | The redundant index assignment | `app/cbl/CBSTM03A.CBL:L324` | `TM-CBSTM03A-R003` | The mainline sets the outer index before a varying loop that re-initialises it. Preserved verbatim for control-flow fidelity | [`DL-LD-07`](DECISION_LOG.md#dl-ld-07) |

**The census is derived, not declared.** An earlier revision of this sub-section was titled *"The
three tracked retained no-ops"*, opened *"there are exactly three sites"* and closed **"Exactly
three sites hold that status."** **All three of those framings are withdrawn.** The count is
written by the gate harness as `dispositions.justifiedNoOps` into
`target/gate-verification/gate-verification-summary.properties`, from a test that checks each
registered locator against the frozen corpus line by line &mdash; read that property, not this prose,
if the two ever disagree. Three is what the corpus yields today; **the register is open by
construction**, and a subsequently discovered reachable no-op is added as a new identifier row
rather than argued against a closed count.

What has **not** changed is the *criterion* for membership: the artefact must be a genuine no-op or
unreachable value that the frozen source nevertheless reaches. A preserved `CONTINUE`, a preserved
asymmetry and a preserved absent guard are documented source behaviour at their own locators and do
not meet that criterion, so they are not register entries. The identical register, with the same
three identifiers and locators, is published in `DECISION_LOG.md`
[`DL-CR-01`](DECISION_LOG.md#dl-cr-01) and in `docs/validation-gates.md`
[Gate 7](docs/validation-gates.md#gate-7); the three are the same set by construction.

<a id="gate7"></a>

## 14. Gate 7 machine-checkability

Gate 7 asserts that coverage is **computed rather than claimed**. This section states the census
this page publishes, the harness that checks it, and the parser rules the harness must apply to
get the same answer.

### 14.1 Per-program summary counts

`Labels` is the count extracted from the corpus by the rules in [section 3](#parser).
`Rows` is the count published in [section 5](#matrix). `Targets` is the count of cited Java
members that were found to exist. `Unverified` is the number of rows for which any cited
artefact could not be resolved on disk.

| Program | Lines | Extracted labels | Synthetic | Rows | Targets found | Tests linked | Unverified |
|---|---:|---:|---:|---:|---:|---:|---:|
| [`COACTUPC.cbl`](#coactupc-cbl) | 4,236 | 85 | 0 | 85 | 85 | 2 | 0 |
| [`COCRDUPC.cbl`](#cocrdupc-cbl) | 1,560 | 45 | 0 | 45 | 45 | 2 | 0 |
| [`COCRDLIC.cbl`](#cocrdlic-cbl) | 1,459 | 39 | 0 | 39 | 39 | 2 | 0 |
| [`COACTVWC.cbl`](#coactvwc-cbl) | 941 | 35 | 0 | 35 | 35 | 2 | 0 |
| [`CBSTM03A.CBL`](#cbstm03a-cbl) | 924 | 25 | 1 | 26 | 26 | 2 | 0 |
| [`COCRDSLC.cbl`](#cocrdslc-cbl) | 887 | 34 | 0 | 34 | 34 | 1 | 0 |
| [`COTRN02C.cbl`](#cotrn02c-cbl) | 783 | 18 | 0 | 18 | 18 | 2 | 0 |
| [`CBTRN02C.cbl`](#cbtrn02c-cbl) | 731 | 26 | 1 | 27 | 27 | 2 | 0 |
| [`COTRN00C.cbl`](#cotrn00c-cbl) | 699 | 16 | 0 | 16 | 16 | 1 | 0 |
| [`COUSR00C.cbl`](#cousr00c-cbl) | 695 | 16 | 0 | 16 | 16 | 2 | 0 |
| [`CBACT04C.cbl`](#cbact04c-cbl) | 652 | 22 | 1 | 23 | 23 | 2 | 0 |
| [`CBTRN03C.cbl`](#cbtrn03c-cbl) | 649 | 26 | 1 | 27 | 27 | 2 | 0 |
| [`CORPT00C.cbl`](#corpt00c-cbl) | 649 | 10 | 0 | 10 | 10 | 2 | 0 |
| [`COBIL00C.cbl`](#cobil00c-cbl) | 572 | 16 | 0 | 16 | 16 | 2 | 0 |
| [`CBTRN01C.cbl`](#cbtrn01c-cbl) | 491 | 18 | 0 | 18 | 18 | 1 | 0 |
| [`COUSR02C.cbl`](#cousr02c-cbl) | 414 | 11 | 0 | 11 | 11 | 2 | 0 |
| [`COUSR03C.cbl`](#cousr03c-cbl) | 359 | 11 | 0 | 11 | 11 | 1 | 0 |
| [`COTRN01C.cbl`](#cotrn01c-cbl) | 330 | 9 | 0 | 9 | 9 | 1 | 0 |
| [`COUSR01C.cbl`](#cousr01c-cbl) | 299 | 9 | 0 | 9 | 9 | 2 | 0 |
| [`COMEN01C.cbl`](#comen01c-cbl) | 282 | 7 | 0 | 7 | 7 | 2 | 0 |
| [`COADM01C.cbl`](#coadm01c-cbl) | 268 | 7 | 0 | 7 | 7 | 1 | 0 |
| [`COSGN00C.cbl`](#cosgn00c-cbl) | 260 | 6 | 0 | 6 | 6 | 2 | 0 |
| [`CBSTM03B.CBL`](#cbstm03b-cbl) | 230 | 14 | 0 | 14 | 14 | 1 | 0 |
| [`CBACT01C.cbl`](#cbact01c-cbl) | 193 | 6 | 1 | 7 | 7 | 1 | 0 |
| [`CBACT02C.cbl`](#cbact02c-cbl) | 178 | 5 | 1 | 6 | 6 | 1 | 0 |
| [`CBACT03C.cbl`](#cbact03c-cbl) | 178 | 5 | 1 | 6 | 6 | 1 | 0 |
| [`CBCUS01C.cbl`](#cbcus01c-cbl) | 178 | 5 | 1 | 6 | 6 | 1 | 0 |
| [`CSUTLDTC.cbl`](#csutldtc-cbl) | 157 | 2 | 1 | 3 | 3 | 2 | 0 |
| **Total, 28 programs** | **19,254** | **528** | **9** | **537** | **537** | **45** | **0** |

**The paragraph census is 528 and this page maps 528 of 528.** The nine synthetic entry rows are
reported in their own column so they can never be mistaken for paragraph coverage: 528 + 9 = 537
published rows.

### 14.2 What the harness checks

The assertions live in `src/test/java/com/cardemo/e2e/GateVerificationTest.java`, in the methods
whose display names begin *Gate 7*. Command:

```bash
./mvnw -B -ntp -Dit.test=GateVerificationTest verify
```

| # | Check | Failure mode it prevents |
|---|---|---|
| G7.1 | Corpus censuses hold: 28 programs / 19,254 lines, 29 JCL members, 28 copybooks / 2,614 lines, 17 mapsets / 4,472 lines, 17 symbolic maps / 5,632 lines | A case-sensitive glob silently under-reporting coverage |
| G7.2 | The label census is **derived**, not asserted: 528 procedure paragraphs and 0 procedure `SECTION`s | A hand-maintained number drifting from the corpus |
| G7.3 | Per-program label spot checks hold exactly &mdash; `CBSTM03B.CBL` 14, `CSUTLDTC.cbl` 2, `CBSTM03A.CBL` 25, `COACTUPC.cbl` 85 | A parser that gets the total right by compensating errors |
| G7.4 | All 28 programs map **forward** to loadable Java types that cite them **back** | One-directional traceability |
| G7.5 | **Every `app/**` path cited from `src/main/java/**` resolves on disk** | A matrix satisfiable by invention |
| G7.6 | Every source carries the Apache banner, a `Source` line and a doc comment | Provenance loss |
| G7.7 | No untracked `TODO`, `FIXME`, `HACK` or `TBD` marker exists | Untracked deferred work, *Rule 1* clause B |
| G7.8 | The special dispositions are present and justified &mdash; `CBTRN01C` pre-flight, `COMBTRAN` program-less, `UNUSED1Y` dead, `COCRDSEC` source-absent | A gap silently closed by invention |
| G7.9 | `RejectCode` holds exactly **five** constants | Deleting the never-observable 109 and breaking the paragraph map |
| G7.10 | **Every** registered retained no-op is present and marked at its locator, and the census is read from `dispositions.justifiedNoOps` rather than declared | Untracked dead code, its silent deletion, or a hand-maintained tally no build step keeps true |
| G7.11 | The feature map in [section 2.2](#features) is parsed and its identifier column is **exactly** `F-001`&ndash;`F-022`, generated arithmetically; every legacy path and test path in it resolves with exact case; every Java target loads and cites one of its row's legacy artefacts; and no `F-nnn` token outside the set appears anywhere on this page | A feature silently unrepresented &mdash; which is what happened to `F-021` &mdash; or an invented identifier padding the count |

### 14.3 Parser rules the harness must apply

A harness that parses differently will get a different number and the disagreement will look like
a coverage gap. The rules are in [section 3](#parser); three of them are where naive
implementations fail:

- **Comments must be excluded by column 7, not by a leading-asterisk search.** The corpus indents
  every line, so `^\*` matches nothing.
- **88-level condition names and data labels are excluded *structurally*, by scanning only at or
  after the `PROCEDURE DIVISION` header** &mdash; not by a deny-list that could miss one. This
  matters: `CSUTLDTC.cbl` alone declares nine 88-levels in its feedback-code structure, and every
  one would otherwise be counted.
- **The end line is the last line carrying real source text**, with trailing comment separators
  trimmed. Using the next label's line minus one instead inflates ranges by one to four lines and
  puts this page out of agreement with the Javadoc locators already in the tree.

Two derived figures are **parser artefacts and are never coverage**: an all-division parse yields
**553** paragraphs (absorbing exactly 25 non-procedure labels such as `FILE-CONTROL.` and
`I-O-CONTROL.`) and **86** all-division `SECTION`s, and 553 + 86 = **639**. Procedural `COPY`
expansion yields 630 or 638 depending on whether an expanded member is counted once or once per
expansion site, and **neither equals 639**. If a check reports 639, it parsed all four divisions.

### 14.4 Evidence paths

| Artefact | Path |
|---|---|
| This matrix | `TRACEABILITY_MATRIX.md` (repository root, outside the MkDocs `docs_dir`) |
| Census and citation assertions | `target/failsafe-reports/` |
| Recorded censuses, including the paragraph total | `target/gate-verification/gate-verification-summary.properties` |
| Harness evidence | `target/gate-verification/gate-verification-evidence.properties` |
| Unit and integration reports | `target/surefire-reports/`, `target/failsafe-reports/` |

<a id="findings"></a>

## 15. Findings register

*Rule 1* clause F requires findings to be evidence-based, severity-classified and paired with
remediation. Every finding below except the last was discovered by measuring the corpus while
building this page; `TM-L-7` was found afterwards, by a review **of this page**, and is registered
here rather than quietly fixed because a register that only records other artefacts' defects is not
one. Severity is the impact **on a consumer who trusts the wrong figure**, not on the corpus, and it is
drawn from clause F's four levels &mdash; **Blocker**, **High**, **Medium**, **Low** &mdash; with no
fifth level invented, so that nothing can sit outside the scale the clause prescribes.

| ID | Severity | Finding | Evidence | Remediation |
|---|---|---|---|---|
| `TM-M-1` | **Medium** | The screen-field census of **460** that circulates in prose is wrong, and is **also internally inconsistent with its own per-map table, which sums to 440**. The single differing per-map cell is `COACTVW`, which carries **37** fields, not 36. | Derived by counting level-**02** `<FIELD>I` picture declarations rather than level-03 picture declarations across `app/cpy-bms/**`: **441**. Independently reproduced by `GateVerificationTest`. | Cite the derived per-map census in [section 11](#bms). Do not carry 460 or 440 forward. |
| `TM-M-2` | **Medium** | `USRSEC` is described in prose as defined in job control **rather than** catalogued. It is catalogued. | `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS` is one of the ten clusters in `app/catlg/LISTCAT.txt` with `KEYLEN 8` / `AVGLRECL 80`; `app/jcl/DUSRSECJ.jcl:L65`&ndash;`:L68` independently declares `KEYS(8,0) RECORDSIZE(80,80)`. | Treat both sources as corroborating. Section 10.1 records both. |
| `TM-M-3` | **Medium** | Eight paragraph line-range citations carried by the technical specification are wrong. Trusting them yields Javadoc locators that point at the wrong code. | See the table in [section 15.1](#corrections). The most consequential: `9700-CHECK-CHANGE-IN-REC` cited as `COACTUPC.cbl:L669-L756` is in fact the **DATA DIVISION snapshot group** &mdash; that program's `PROCEDURE DIVISION` does not begin until `:L858`, and the paragraph is at `:L4109`&ndash;`:L4192`. | Use the machine-derived ranges published in [section 5](#matrix); re-derive rather than transcribe. |
| `TM-L-1` | **Low** | `app/cbl/COACTVWC.cbl` contains a **duplicate paragraph label**: `0000-MAIN-EXIT` is defined twice, back to back and byte-identical, with zero references. | `:L408`&ndash;`:L410` and `:L411`&ndash;`:L413`. The sibling `COCRDSLC.cbl` has the same `COMMON-RETURN` / `0000-MAIN-EXIT` layout at the same line numbers but only one copy. | Preserved, not merged. Two row IDs and two line-discriminated Java members. Any tool joining on label name alone must join on row ID instead. |
| `TM-L-2` | **Low** | The four simple readers are not uniformly named: three use `9999-ABEND-PROGRAM` / `9910-DISPLAY-IO-STATUS`, while `CBCUS01C` and `CBTRN01C` use `Z-ABEND-PROGRAM` / `Z-DISPLAY-IO-STATUS`. | `app/cbl/CBCUS01C.cbl:L154`, `:L161`; `app/cbl/CBTRN01C.cbl:L469`, `:L476`. | Rows carry the source label verbatim. Do not normalise the label to make the four programs look alike. |
| `TM-L-3` | **Low** | `CBACT01C` has a paragraph the other three simple readers do not: `1100-DISPLAY-ACCT-RECORD`. | `app/cbl/CBACT01C.cbl:L118`&ndash;`:L131`, performed from `:L96`. Its body displays eleven fields, including the misspelled `ACCT-EXPIRAION-DATE`. | Mapped to its own member. Expect 7 rows for `CBACT01C` and 6 for each of the others. |
| `TM-L-4` | **Low** | `CBTRN03C` reuses paragraph-number prefixes, which reads like a duplicate but is not. | `1110-WRITE-PAGE-TOTALS` at `:L293` and `1110-WRITE-GRAND-TOTALS` at `:L318` share `1110`; `1120-WRITE-ACCOUNT-TOTALS` at `:L306`, `1120-WRITE-HEADERS` at `:L324` and `1120-WRITE-DETAIL` at `:L361` share `1120`. | Names are distinct, so the rows are distinct. A matcher keyed on the numeric prefix alone would collide; key on the full label. |
| `TM-L-5` | **Low** | The generation-group base count is six in prose and **seven** in the corpus. | `app/jcl/DEFGDGB.jcl` defines six; `DALYREJS`, the seventh, is defined at `app/jcl/DALYREJS.jcl:L25`. | Section 10.3 publishes all seven. A six-entry map is missing the reject output prefix. |
| `TM-L-6` | **Low** | The synthetic entry rows document mainline logic that the existing in-tree correspondence comments omit, so a consumer reading only those comments misses 33 lines of it. | `CBSTM03A.CBL:L262`&ndash;`:L295` walks z/OS control blocks (`PSA`, `TCB`, `TIOT`), displays the DD names it finds, then performs `OPEN OUTPUT STMT-FILE HTML-FILE` and initialises the resident table &mdash; 33 lines that begin before `0000-START` at `:L296`. | Kept as `TM-CBSTM03A-R001`. The control-block inspection has no Java analogue and is recorded as such; the `OPEN OUTPUT` maps to `StatementWriter.openStatementOutputs()`. |
| `TM-L-7` | **Low** | **This page mapped 21 of the 22 catalogued features, not 22.** Every section mapped artefacts, and the `F-021` token appeared nowhere in the repository, so "behavioural parity across all twenty-two features" was not mechanically provable from the evidence set. Nothing failed, because nothing checked. | Measured: 21 distinct identifiers across this page and zero elsewhere. `F-021` is transaction combination, whose entire source is `app/jcl/COMBTRAN.jcl` &mdash; it has no COBOL program, so no program roster can carry it. | Closed. [Section 2.2](#features) maps all 22 on the feature axis, [section 8](#jcl) row 5 identifies the member, and check G7.11 in [section 14.2](#gate7) asserts the exact generated `F-001`&ndash;`F-022` set so that an omission and an invented identifier both fail. |

**No Blocker or High finding arose from building this page.** The one Blocker-class hazard in the
dependency set and the two High-class credential-hygiene defects are recorded where they belong,
in `docs/validation-gates.md` as `B-1`, `B-2`, `H-1` and `H-2`, and are not restated here.

<a id="corrections"></a>

### 15.1 Corrected line-range citations

Source governs. Each range below was re-derived by reading the lines; the *Published* column is
what [section 5](#matrix) carries.

| Program | Paragraph | Cited in prose | **Published** | What the cited range actually is |
|---|---|---|---|---|
| `COACTUPC.cbl` | `9700-CHECK-CHANGE-IN-REC` | `:L669`&ndash;`:L756` | **`:L4109`&ndash;`:L4192`** | The DATA DIVISION snapshot group: `05 ACUP-OLD-DETAILS.` at `:L669` through `ACUP-OLD-CUST-FICO-SCORE-X` at `:L756`. The `PROCEDURE DIVISION` does not begin until `:L858` |
| `CBTRN02C.cbl` | `9999-ABEND-PROGRAM` | `:L707`&ndash;`:L710` | **`:L707`&ndash;`:L711`** | Omits `CALL 'CEE3ABD'.` on `:L711`, which is the abend itself |
| `CBTRN02C.cbl` | `9910-DISPLAY-IO-STATUS` | `:L714`&ndash;`:L731` | **`:L714`&ndash;`:L727`** | `EXIT.` is on `:L727`; `:L728`&ndash;`:L731` are a blank line and the version trailer comment |
| `CBTRN02C.cbl` | `2000-POST-TRANSACTION` | `:L424`&ndash;`:L465` | **`:L424`&ndash;`:L444`** | Runs past the paragraph end into `2500-WRITE-REJECT-REC`, which begins at `:L446` |
| `CBTRN02C.cbl` | `2700-UPDATE-TCATBAL` | `:L467`&ndash;`:L500` | **`:L467`&ndash;`:L501`** | Stops one line short of the paragraph end |
| `CBACT04C.cbl` | `1200-GET-INTEREST-RATE` | `:L415`&ndash;`:L460` | **`:L415`&ndash;`:L442`** | **Conflates two paragraphs.** `1200-A-GET-DEFAULT-INT-RATE` is a separate paragraph at `:L443`&ndash;`:L460`, and it is the one whose retry abends on a missing default row |
| `CBACT04C.cbl` | `1300-B-WRITE-TX` | `:L473`&ndash;`:L516` | **`:L473`&ndash;`:L515`** | Overruns by one line |
| `CBSTM03A.CBL` | `8500-READTRNX-READ` | `:L818`&ndash;`:L848` | **`:L818`&ndash;`:L847`** | Overruns by one line |

Eleven other ranges spot-checked against prose agreed exactly, including
`1400-COMPUTE-FEES` `:L518`&ndash;`:L520`, `1300-COMPUTE-INTEREST` `:L462`&ndash;`:L470`,
`1500-B-LOOKUP-ACCT` `:L393`&ndash;`:L422` and `8100-FILE-OPEN` `:L726`&ndash;`:L728`. The
disagreements are not systematic, which is precisely why every range here is derived rather than
transcribed.

<a id="verify"></a>

## 16. Verification log

Nothing in this section is asserted from design. Each entry records a command that was executed,
what it returned, and when.

### 16.1 Corpus censuses

| Check | Command | Result | Standing |
|---|---|---|---|
| Program count and line total | `wc -l app/cbl/*` | 28 files, **19,254** lines; every one of the 28 individual counts matches [section 5.0](#matrix) | `Verified` |
| Corpus frozen | `git diff 7756d89 HEAD -- app/` | empty | `Verified` |
| Anchor reachable | `git cat-file -t 7756d895ffeb65f7ea72aaa609e356d9899afcec` | `commit` | `Verified` |
| Job-control member count | `ls app/jcl \| wc -l` | **29**, with `CREASTMT.JCL` the only uppercase extension | `Verified` |
| Copybook count and lines | `wc -l app/cpy/*` | 28 members, **2,614** lines | `Verified` |
| Symbolic maps and mapsets | `wc -l app/cpy-bms/* app/bms/*` | 17 / **5,632** and 17 / **4,472** | `Verified` |
| Screen-field census | level-**02** `<FIELD>I` picture declarations across `app/cpy-bms/**`, cross-checked against the `<FIELD>L` length items | **441** | `Verified` |
| Catalogue entry kinds | derived from `app/catlg/LISTCAT.txt` | 10 clusters, 3 AIX, 3 paths, 7 generation groups; folds to the declared **209** at `:L3937`&ndash;`:L3951` | `Verified` |
| `UNUSED1Y` references | `grep -riE 'COPY +.?UNUSED1Y' app/` | **0** | `Verified` |
| `COCRDSEC` occurrences | `grep -rn COCRDSEC .` | exactly 2, both in `app/csd/CARDDEMO.CSD` (`:L211`, `:L390`) | `Verified` |
| `usrsec.txt` absent | `ls app/data/ASCII/` | 9 fixtures, no `usrsec.txt` | `Verified` |

### 16.2 Matrix self-check

A round-trip audit was run during authoring: it parsed **this rendered document** back, row by
row, and reconciled every field against the frozen corpus and the Java tree. It is reported here
because it is the evidence that the mapping is internally consistent and externally resolvable.

**The durable, repeatable harness is `GateVerificationTest`**, covered in [section 16.3](#verify).
The audit below was authoring-time scaffolding and is deliberately not committed, so no path to it
is cited; each of its 31 assertions is stated in full instead, and every one is reproducible from
the corpus with the commands in [section 16.1](#verify).

| # | Assertion | Result |
|---|---|---|
| V1 | 28 programs and 19,254 physical lines | **PASS** 28 files / 19254 lines |
| V2 | every per-program line count is published in the roster | **PASS** 28/28 |
| V3 | derived census is 528 paragraphs and 0 procedure SECTIONs | **PASS** 528 paragraphs / 0 sections / 9 synthetic |
| V4 | every matrix row parses against the published schema | **PASS** 537 rows parsed, 0 malformed |
| V5 | row IDs are unique across the whole document | **PASS** 537 unique |
| V6 | every extracted label has a row, with the right multiplicity | **PASS** missing=0 unknown=0 wrong-multiplicity=0 |
| V7 | every line range is within its file's physical line count | **PASS** 537 ranges in bounds |
| V8 | published ranges equal the freshly derived ranges | **PASS** 537 ranges agree |
| V9 | every cited Java file exists at its exact-case path | **PASS** 537 citations resolve |
| V10 | every cited Java method exists inside the cited file | **PASS** 537 methods resolve, re-measured after 26 `CBTRN03C` rows were re-pointed from the job's trace-only markers to the members that perform the work |
| V11 | every cited test file exists | **PASS** &mdash; **35** distinct test files resolve |
| V11a | every cited test **method** exists in the file the row names | **PASS** &mdash; 537 rows, **241** distinct `file::method` citations, 0 unresolved |
| V11b | every cited test method is an executable test, never a fixture helper | **PASS** &mdash; all 241 carry `@Test`, `@ParameterizedTest` or `@RepeatedTest`; none names a `setUp` or a `given&hellip;` helper |
| V11c | every declared method in the Java main tree is enumerated and partitioned | **PASS** &mdash; 3,037 methods across 158 files, partitioned 537 + 479 + 554 + 450 + 1,017 = 3,037, with no member outside the partition &mdash; [section 6.2](#reverse-enumerated) |
| V12 | every cited DECISION_LOG id exists as an entry heading | **PASS** re-measured after the eight new entries: 53 ids cited, all 53 resolve |
| V13 | every cited DECISION_LOG anchor exists in that file | **PASS** re-measured: 52 anchors, all resolve |
| V14 | every cited validation-gate anchor exists | **PASS** 1 cited, all resolve |
| V15 | every in-page link targets a declared anchor | **PASS** re-measured after sections 6.2, 6.3 and 8.3 were added: 49 anchors declared, 49 used, 0 unresolved |
| V16 | anchors are unique | **PASS** ok |
| V17 | every concrete repository path cited resolves on disk | **PASS** re-measured: 49 concrete paths, 48 resolve. The one that does not is `app/data/ASCII/usrsec.txt`, cited in section 16.1 precisely **because** it is absent — the fixture directory holds nine files and no user-security fixture, which is why the ten seeded users come from `app/jcl/DUSRSECJ.jcl` inline data instead. A declared absence, not a broken citation |
| V18 | every matrix data row has exactly 14 columns | **PASS** 537/537 |
| V19 | reverse index covers every forward target exactly once | **PASS** missing=0 orphan=0 |
| V20 | reverse index cites every row ID | **PASS** rev=537 fwd=537 |
| V21 | no forbidden or nonexistent identifier appears | **PASS** clean |
| V22 | no false 100% or unearned gate PASS claim | **PASS** clean |
| V23 | the 639 and 553 parser artefacts are explicitly disclaimed | **PASS** disclaimed |
| V24 | no secret pattern appears in the document | **PASS** clean |
| V25 | the seeded users' literal password is not reproduced | **PASS** not reproduced |
| V26 | all published censuses appear in the document | **PASS** ok |
| V27 | COCRDSEC is present as OUT OF SCOPE and has no matrix row | **PASS** orphan documented, no row |
| V28-CBSTM03A | exact on-disk case preserved for CBSTM03A.CBL | **PASS** present |
| V28-CBSTM03B | exact on-disk case preserved for CBSTM03B.CBL | **PASS** present |
| V28-CREASTMT | exact on-disk case preserved for CREASTMT.JCL | **PASS** present |
| V28-COSTM01 | exact on-disk case preserved for COSTM01.CPY | **PASS** present |

| | |
|---|---|
| Assertions | **34**, all passing &mdash; 31 at the first reading plus V11a, V11b and V11c, added when the Test column gained method identifiers |
| Executed | Thursday, 06 August 2026 at 12:08 UTC |
| Working tree under test | `c787f09d` (working tree, matrix added) |
| Exit status | **0** |
| Standing | `Verified` **at authoring time.** This footer dates the one-off audit described above, not the current tree, and it is deliberately left at its original commit rather than restamped &mdash; restamping a run that was never re-executed would be the inaccuracy this page exists to avoid. What carries the claim forward is the durable harness in [section 16.3](#verify), which re-derives the same censuses on every build and last did so at commit `4a4ad1c9` |

### 16.3 Gate 7

| | |
|---|---|
| Command | `./mvnw -B -ntp clean verify` &mdash; **no skips**, so the vulnerability scan ran as part of it |
| Executed | Friday, 07 August 2026, 11:32:25 UTC to 11:41:17 UTC |
| Commit under test | `4a4ad1c9fefc0858eba8bc4d1412eac3f672c0f1` (`4a4ad1c9`), working tree clean at launch |
| Exit code | **0** |
| Report path | `target/failsafe-reports/TEST-com.cardemo.e2e.GateVerificationTest.xml`; censuses in `target/gate-verification/gate-verification-summary.properties`, whose `gate.harness.commit` equals the commit above |
| **Result** | **51 assertions run, 0 failures, 0 errors.** The harness independently derived `gate7.procedureParagraphs=528`, `gate7.procedureSections=0`, `gate7.screenInputFields=441`, `gate7.allDivisionParagraphs=553`, `gate7.allDivisionSections=86`, `gate7.circulatingArtefact=639`, `gate7.forwardMappedPrograms=28`, `gate7.productionClasses=132` and `gate7.distinctCitedLegacyPaths=116` &mdash; every one agreeing with the figures published on this page |
| Supersedes | A run of 06 August 2026 at 12:10 UTC at working-tree commit `c787f09d`, invoked with `-Ddependency-check.skip=true` and an `-Dit.test` narrowing, reporting 41 assertions. It is superseded on three counts: it predated later commits, its narrowing meant the scan never ran, and the assertion total has since grown to 51 |

### 16.4 Test suite

**One run is recorded, not two.** Two were recorded previously because they answered different
questions &mdash; one narrowed the integration tier to the gate harness, the other exercised the whole
module &mdash; but both were invoked with `-Ddependency-check.skip=true`, so neither could speak to the
scan. A single no-skip whole-module run answers both questions and the scan question as well, so it
replaces the pair. It is the same run [section 16.3](#verify) reports on.

| | Exact-HEAD whole-module run |
|---|---|
| Command | `./mvnw -B -ntp clean verify` &mdash; **no skips of any kind** |
| Commit under test | `4a4ad1c9`, working tree clean at launch |
| Executed | Friday, 07 August 2026, 11:32:25 UTC to 11:41:17 UTC, total 08:50 min |
| Unit tier | **14,836** run, 0 failures, 0 errors, 0 skipped, across 208 suites |
| Integration tier | **799** run, 0 failures, 0 errors, 0 skipped, across 35 suites |
| End-to-end tier | **107** run, 0 failures, 0 errors, 0 skipped, across 3 suites &mdash; of which the gate harness is **58** |
| Suite reconciliation | **246** concrete suites in `src/test/java` at that commit, **246** reports collected &mdash; so no suite silently failed to run. The run was taken at this tree's own commit, so the census and the collected reports are one measurement rather than two readings to reconcile |
| Line coverage | **0.9156** &mdash; `missed=2036 covered=22100 total=24136` &mdash; against the 0.80 floor |
| Vulnerability scan | Ran. 166 dependencies, **0** findings at or above CVSS 7 |
| Compiler warnings | **0** &mdash; the zero-warning configuration is intact |
| Outcome | **BUILD SUCCESS**, exit code 0 |
| Report | `target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/`, `target/jacoco.exec`, `target/gate-verification/`, `target/dependency-check/` |
| Superseded figures | The earlier pair reported 14,461 unit and 845 integration cases on 06 August 2026. Both are superseded by the totals above; neither was wrong when taken |

**How to read the unit-tier figure, and why the earlier one is retained.** **14,836 is a count of
`<testcase>` elements** across `target/surefire-reports/TEST-*.xml`, **not** a sum of the per-file
`tests` attribute &mdash; that sum under-counts, because a class whose tests all live in `@Nested`
inner classes reports `tests="0"`. They are test **cases**, not assertions: the assertion count is
strictly larger and is measured nowhere, so no figure for it is published here. The 14,461 of the earlier run that day, and the 14,465 recorded at commit `1363f491`, are each a
**dated reading of an earlier tree** and are retained as
history rather than deleted; a provisioning summary of another date recorded 14,408 and 801, which
is a third tree. **None of the three contradicts the others** &mdash; the commit is what distinguishes
them, which is why every figure in the table above carries its command, its date and its
commit.

**Where this evidence physically lives.** Every path in the *Report* row sits under `target/`, which
is **build output and is not committed** &mdash; `.gitignore` excludes it, and committing a 90 MB JAR
and a JaCoCo bundle would be its own hygiene violation. So a reader cannot open a stored file to
check a figure here, and none is linked, because a link to an uncommitted path would resolve for the
author and 404 for everyone else. What a reader does instead is re-run the command at the commit
named above. That is also why no gate is described here as *passed*: the authoritative statement is
`docs/validation-gates.md`, and this page reports a measurement rather than a verdict.

**One in-scope test had to change, and it is named here rather than absorbed.**
`src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java` asserted that
**this file did not exist** &mdash; it is the guard that stops any document claiming a decision is
"recorded in" a register that is absent &mdash; and its own failure message instructed that the
premise be revised deliberately if the file were ever authored. It was revised in the narrowest way
that keeps the protection: the absent-evidence set is now **empty** and asserted empty, the
forbidden-phrase pattern is *derived* from that set so it cannot outlive it, and **both** registers
are asserted present, so deleting either one fails the guard immediately. Excluding the class,
weakening its pattern and leaving a false premise standing were all rejected, because each removes
the protection instead of updating it. The narrowing is recorded at
[`DL-RR-08`](DECISION_LOG.md#dl-rr-08); the three documentation guard classes run **31 tests, 0
failures** after it, and the whole-module figures above were measured with that change in place.

### 16.5 Honest-status policy

Three statuses are defined on this page and they are not interchangeable. **Two are in use; the
third is defined and deliberately unused**, because every check published here was executed and
returned &mdash; and a disclosure that has no definition cannot be made when it is needed:

- **`Verified`** &mdash; a command was run and returned the stated result. Section 16.1 lists them.
- **`Target-verified`** &mdash; the cited Java file exists at the cited path with that exact case, and
  the cited member exists inside it. This is what every row in [section 5](#matrix) claims, and
  nothing more.
- **`Not available`** &mdash; evidence has not been produced. It is always paired with the
  prerequisite that would produce it, and it is **never** written as `PASS`, as a percentage, or as
  a claim that a gate was met.

**This page does not pronounce on the Gate 7 verdict.** That verdict belongs to
[`docs/validation-gates.md`](docs/validation-gates.md), which owns the gate ledger. What this page
claims is narrower and fully evidenced: the mapping is complete against a **derived** census of 528
paragraphs, every cited artefact resolves on disk, and the harness that decides the gate was
executed at the command and date recorded in [section 16.3](#verify) with exit status 0 and no
failing assertion. The distinction matters &mdash; a harness passing is a fact about an execution;
a gate passing is a judgement recorded in the ledger that owns it.

<a id="rules"></a>

## 17. How this document satisfies *Rule 1: Build Verify*

| Clause | Requirement, in brief | How this page satisfies it |
|---|---|---|
| **A** Engineering principles | Correctness, determinism, explicit behaviour; maintainability; observability; justified tradeoffs | Every figure is **derived** by a stated parse rather than transcribed, so the page is reproducible from the corpus. [Section 3](#parser) publishes the parse; [section 16](#verify) publishes the commands. The one efficiency tradeoff that changes behaviour &mdash; removing the 510-transaction ceiling &mdash; is labelled a **deviation** and justified, not absorbed silently |
| **B** Code quality | No dead code and **no untracked** deferred work; boundary conditions explicit; tests for core logic; documented interfaces | [Section 13.2](#scope) tracks **every** retained no-op by stable identifier, locator, row ID and register entry, which is what makes them tracked rather than dead; the census itself is derived by the gate harness rather than declared in prose. Boundary conditions are called out on the exact rows that hold them &mdash; the empty-file path yielding a first identifier of 1, the **unreachable** end-of-data flush that leaves the last account's interest unposted, the three sites where a not-found status is success, and the unguarded sequential checks that let 103 overwrite 102. Every row names a test |
| **C** Repository hygiene | Follow existing conventions; deterministic; no duplication; consistent structure | The Apache-2.0 banner convention is universal in the corpus and this file opens with the equivalent, naming its originating artefacts. **Exact on-disk case is preserved everywhere** &mdash; `CBSTM03A.CBL`, `CBSTM03B.CBL`, `CREASTMT.JCL`, `COSTM01.CPY` &mdash; and [section 1.4](#about) records what breaks when it is not. No file is invented: every cited path was resolved on disk. Decision rationale is **cited, not duplicated** from `DECISION_LOG.md` |
| **D** Security | No secrets in code, logs, tests or configuration; least privilege | **No credential value appears anywhere on this page.** The ten seeded user records are described by identifier and type only; their literal password is deliberately not reproduced, and [section 12.1](#fixtures) records that the seed migration stores BCrypt hashes at strength 10 under the local and test profiles alone. No token, key or connection string appears |
| **E** Documentation | What it does, how to build/run/test, key configuration and defaults, failure modes and troubleshooting | [Section 1](#about) covers all four in that order, including a six-row troubleshooting table for the failure modes that actually occur in this corpus &mdash; case-sensitive globs, the 639 parser artefact, the 460 field census and the duplicate label |
| **F** Output requirements | Evidence-based with paths and symbols; severity-classified; remediation; honest `Not available` | Every claim carries a `path:Lnnn` locator or a named symbol. [Section 15](#findings) classifies nine findings &mdash; three **Medium** and six **Low** &mdash; on clause F's own four-level scale, with named remediation for each and an explicit statement that no **Blocker** or **High** finding arose here, together with where the ones that exist are recorded. On `Not available` the honest report is that **this page uses it nowhere**, and why: every check it publishes was executed and returned, so all 537 rows read `Target-verified` and every row of [section 16](#verify) reads `Verified` or `PASS`. The status is nonetheless defined at [section 4.3](#schema) and governed at [section 16.5](#verify), because a document that never defines the disclosure has no way to make it. Where information is genuinely not this page's to give &mdash; the verdicts of the seven gates it does not serve &mdash; it **defers** to [`docs/validation-gates.md`](docs/validation-gates.md) by anchor instead of restating a result, which is the same discipline exercised in the other direction |

### 17.1 The one conflict, and its resolution

**Clause B forbids dead code. The parity mandate requires one-to-one control-flow fidelity.** They
collide at exactly one identifiable site: `1400-COMPUTE-FEES` in `app/cbl/CBACT04C.cbl` is empty
apart from a `* To be implemented` comment, yet it is genuinely reachable &mdash; the source
performs it from `:L216`.

**The parity mandate governs, and clause B is satisfied by a different mechanism.** The empty
member is retained, documented, cited at `:L518`&ndash;`:L520`, given row `TM-CBACT04C-R015`, and
registered as [`DL-PP-05`](DECISION_LOG.md#dl-pp-05) with the conflict itself resolved in
[`DL-CR-01`](DECISION_LOG.md#dl-cr-01). Clause B's actual prohibition is on **untracked** dead
code and deferred work without an owner or a tracking reference; this code is tracked, referenced
and justified, so the clause is met rather than waived. Deleting the call site would produce a
system that is marginally cleaner and demonstrably less traceable &mdash; failing a stated
acceptance criterion to satisfy a stylistic one.

The same reasoning, for the same reason, applies to the other two tracked no-ops: the
never-observable reject code 109 and the redundant index assignment in the statement program.

---

*Derived from the frozen CardDemo corpus at `7756d895ffeb65f7ea72aaa609e356d9899afcec`. Every count on this page is reproducible with
the commands in [section 16](#verify).*
