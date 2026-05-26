# Golden-Record Contract — CBSTM03A (Statement Generator; Batch JCL CREASTMT STEP040) — DEVIATION-FLAGGED

This document is the authoritative byte-for-byte contract for the Java translation of the COBOL CBSTM03A statement generator and is consumed by the companion test class `com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest`, which extends `com.blitzy.carddemo.tests.golden.GoldenRecordTest`. The test class is `@Disabled("Awaiting COBOL capture per MIGRATION_NOTES.md section 1.6; DEVIATION documentation pending")` per AAP §0.6.11 until BOTH (a) the three placeholder data files in this folder (`stmt_text.txt`, `stmt_html.html`, `stdout.txt`) are replaced with genuine COBOL captures AND (b) the DEVIATION items enumerated in Phase 6 are fully documented in `java/MIGRATION_NOTES.md` §1.6. CBSTM03A is **DEVIATION-FLAGGED** because it uses z/OS-specific TIOT/TCB/PSA control-block inspection at `[app/cbl/CBSTM03A.CBL:L262-L291]`, ALTER + GO TO control flow at `[app/cbl/CBSTM03A.CBL:L296-L314]` dispatched by 0000-START, and a 2D in-memory table WS-TRNX-TABLE (51 cards x 10 transactions) at `[app/cbl/CBSTM03A.CBL:L225-L230]`. CBSTM03A produces THREE outputs: a fixed-width 80-byte text statement file (STMTFILE), a fixed-width 100-byte HTML statement file (HTMLFILE), and a line-based SYSOUT stream. It depends on the CBSTM03B file-services subroutine called from 13 sites across the program. All citations follow the form `[<path>:Lnnn]` per AAP §0.8.1; the binding cascade flows down through the 19 AAP sections enumerated in Phase 0 below. NONE of the COBOL idiosyncrasies catalogued in Phase 9 are bugs to be fixed: per AAP §0.7.1 (Minimal Change Clause), every observable byte is preserved exactly, even when a discouraged COBOL idiom is in play.

This README is the SINGLE SOURCE OF TRUTH for the CBSTM03A golden-record fixture and is foundational to the entire CardDemo COBOL-to-Java migration's byte-for-byte parity contract. It is structured into 14 numbered phases (Phase 0 through Phase 13) followed by 6 supplementary sections; the phases are arranged to step a reader from the authority cascade down through identity, files, inputs, flow, messages, deviations, layouts, output contracts, idiosyncrasies, mapping invariants, test bindings, capture cross-reference, and scenario inventory in order. The supplementary sections (Contrast Matrix, Verbatim Message Catalog Cross-Reference, Source Lineage, Cross-References, Authority References, and DO NOT Modify the Fixture Data Without Re-Capture) close the document by establishing comparative context with sibling programs, reaffirming the catalog completeness, listing source files, listing cross-referenced Java files, listing AAP authority citations, and stating the immutability of the captured fixture data. Every claim in the body cites either a `[app/...:Lnnn]` source location or an `AAP §0.X.Y` authority reference (or both).

## Phase 0: Authority and Source-of-Truth Cascade

- AAP §0.1.1 — refactoring objective; byte-for-byte file fidelity is non-negotiable
- AAP §0.1.3 — surfaced implicit requirements (decimal scale, plaintext preservation, taxonomies)
- AAP §0.2.1 — in-scope: `java/carddemo-tests/src/test/resources/golden/**/*`
- AAP §0.2.2 — `app/` tree IMMUTABLE; this fixture references but never modifies `app/data/ASCII/*.txt`
- AAP §0.3.1 — refactored structure; `CbStm03A` lives in `application/statement/` subpackage
- AAP §0.3.2 — records pattern, sealed-type pattern, repository ports
- AAP §0.3.4 — JVM flags `-XX:+UseCompactObjectHeaders` (JEP 519) and Shenandoah generational (JEP 521)
- AAP §0.3.6 — hexagonal architecture; no application-framework container; plain constructor injection
- AAP §0.4.1 — file transformation table: CBSTM03A DEVIATION entry; `CbStm03A` class name; legacy z/OS inspection flagged as DEVIATION
- AAP §0.6.1 — decimal arithmetic fidelity: `Decimals` utility, `MathContext.DECIMAL128`, BigDecimal scale 2
- AAP §0.6.2 — sealed hierarchies for `FileDispatchState` (WS-FL-DD) and `Cbstm03bResult` (WS-M03B-RC)
- AAP §0.6.4 — date semantics: `java.time` types only
- AAP §0.6.5 — file I/O exactness: byte-for-byte round-trip; EBCDIC IBM-1047 default
- AAP §0.6.6 — `ScopedValue` replaces `ThreadLocal` entirely; sequential preservation; no virtual-thread reordering
- AAP §0.6.11 — golden-record harness PR gate; `@Disabled` until COBOL capture committed
- AAP §0.7.1 — Minimal Change Clause: translate faithfully even when DEVIATION-flagged
- AAP §0.7.2 — no PAN in production logs; mask all but last 4 digits
- AAP §0.7.4 — forbidden preview features (JEP 502, 505, 507); JEP 512 utilities only; no `default` branches in pattern switches
- AAP §0.7.5 — capture procedure documented in `java/MIGRATION_NOTES.md`
- AAP §0.8.1 — citation discipline `[<path>:Lnnn]`

## Phase 1: Test Identity and Java Mapping Targets

| Attribute | Value | Source |
|-----------|-------|--------|
| COBOL PROGRAM-ID | `CBSTM03A` | `[app/cbl/CBSTM03A.CBL:L2]` |
| COBOL AUTHOR | `AWS` | `[app/cbl/CBSTM03A.CBL:L3]` |
| Source line count | 924 | `app/cbl/CBSTM03A.CBL` (per `wc -l`) |
| Subroutine | `CBSTM03B` | `[app/cbl/CBSTM03B.CBL:L2]` (230 lines; callable file-services) |
| JCL driver | `STEP040 EXEC PGM=CBSTM03A,COND=(0,NE)` | `[app/jcl/CREASTMT.JCL:L79]` |
| JCL job name | `CREASTMT` | `[app/jcl/CREASTMT.JCL:L1]` |
| JCL line count | 97 | `app/jcl/CREASTMT.JCL` (per `wc -l`) |
| JCL DDs (input) | TRNXFILE, XREFFILE, ACCTFILE, CUSTFILE (all KSDS) | `[app/jcl/CREASTMT.JCL:L83-L86]` |
| JCL DDs (output) | STMTFILE (LRECL=80,RECFM=FB), HTMLFILE (LRECL=100,RECFM=FB), SYSOUT | `[app/jcl/CREASTMT.JCL:L82,L87-L96]` |
| STMTFILE LRECL/RECFM | LRECL=80, BLKSIZE=8000, RECFM=FB | `[app/jcl/CREASTMT.JCL:L89]` |
| HTMLFILE LRECL/RECFM | LRECL=100, BLKSIZE=800, RECFM=FB | `[app/jcl/CREASTMT.JCL:L94]` |
| Java FQCN under test | `com.blitzy.carddemo.application.statement.CbStm03A` | AAP §0.4.1 |
| Java test class FQCN | `com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest` | AAP §0.6.11 |
| Java test base class | `com.blitzy.carddemo.tests.golden.GoldenRecordTest` | AAP §0.6.11 |
| CBSTM03B Java translation | `com.blitzy.carddemo.application.statement.CbStm03B` (utility class) | AAP §0.4.1 |

The `@CobolProgram("CBSTM03A")` Javadoc-style annotation MUST cite the original PROGRAM-ID, the source path `app/cbl/CBSTM03A.CBL`, and the translation date per AAP §0.7.1. Each translated COBOL paragraph MUST carry a `@CobolParagraph("<NUMERIC-PREFIX-NAME>")` Javadoc annotation citing the original paragraph name. The full inventory of paragraph names (`0000-START`, `1000-MAINLINE`, `1000-XREFFILE-GET-NEXT`, `2000-CUSTFILE-GET`, `3000-ACCTFILE-GET`, `4000-TRNXFILE-GET`, `5000-CREATE-STATEMENT`, `5100-WRITE-HTML-HEADER`, `5100-EXIT`, `5200-WRITE-HTML-NMADBS`, `5200-EXIT`, `6000-WRITE-TRANS`, `8100-FILE-OPEN`, `8100-TRNXFILE-OPEN`, `8200-XREFFILE-OPEN`, `8300-CUSTFILE-OPEN`, `8400-ACCTFILE-OPEN`, `8500-READTRNX-READ`, `8599-EXIT`, `9100-TRNXFILE-CLOSE`, `9200-XREFFILE-CLOSE`, `9300-CUSTFILE-CLOSE`, `9400-ACCTFILE-CLOSE`, `9999-GOBACK`, `9999-ABEND-PROGRAM`) is enumerated in Phase 4 with exact line citations. The `@Disabled` annotation on the test class is removed ONLY when BOTH (a) all three data file placeholders are replaced with genuine COBOL captures AND (b) the DEVIATION items enumerated in Phase 6 are fully documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.6.11.

## Phase 2: Files in This Folder

### `README.md` (this file)

This document. Authoritative byte-for-byte contract for the CBSTM03A golden-record fixture. Created as part of the initial Java module scaffolding per AAP §0.2.1; consumed by `CbStm03AGoldenTest` once the three data files below are replaced with genuine COBOL captures per AAP §0.7.5.

### `stmt_text.txt` — Captured STMTFILE Output (CAPTURE PLACEHOLDER)

- **DD binding**: `STMTFILE` per `[app/jcl/CREASTMT.JCL:L87]` (DSN=AWS.M2.CARDDEMO.STATEMNT.PS).
- **Record format**: FB, LRECL=80, BLKSIZE=8000 per `[app/jcl/CREASTMT.JCL:L89]`.
- **COBOL FD**: `FD STMT-FILE` with `FD-STMTFILE-REC PIC X(80)` per `[app/cbl/CBSTM03A.CBL:L44-L45]`.
- **Content**: 80-byte fixed-width records written by COBOL `WRITE FD-STMTFILE-REC FROM ST-LINE<n>` calls in paragraphs 5000-CREATE-STATEMENT (`[app/cbl/CBSTM03A.CBL:L458-L504]`), 4000-TRNXFILE-GET (`[app/cbl/CBSTM03A.CBL:L416-L456]`), and 6000-WRITE-TRANS (`[app/cbl/CBSTM03A.CBL:L675-L723]`).
- **Per-statement record sequence** (informational; see Phase 4 for the full WRITE sequence):
  1. ST-LINE0 — asterisks + `START OF STATEMENT` banner (`[app/cbl/CBSTM03A.CBL:L86-L89]`)
  2. ST-LINE1 — ST-NAME 75 chars + 5 spaces (`[app/cbl/CBSTM03A.CBL:L90-L92]`)
  3. ST-LINE2 — ST-ADD1 50 chars + 30 spaces (`[app/cbl/CBSTM03A.CBL:L93-L95]`)
  4. ST-LINE3 — ST-ADD2 50 chars + 30 spaces (`[app/cbl/CBSTM03A.CBL:L96-L98]`)
  5. ST-LINE4 — ST-ADD3 80 chars (`[app/cbl/CBSTM03A.CBL:L99-L100]`)
  6. ST-LINE5 — 80 dashes separator (`[app/cbl/CBSTM03A.CBL:L101-L102]`)
  7. ST-LINE6 — spaces + `Basic Details` + spaces (`[app/cbl/CBSTM03A.CBL:L103-L106]`)
  8. ST-LINE5 — 80 dashes separator (written again at `[app/cbl/CBSTM03A.CBL:L494]`)
  9. ST-LINE7 — `Account ID         :` + ST-ACCT-ID + spaces (`[app/cbl/CBSTM03A.CBL:L107-L110]`)
  10. ST-LINE8 — `Current Balance    :` + ST-CURR-BAL with PIC 9(9).99- trailing-sign format (`[app/cbl/CBSTM03A.CBL:L111-L115]`)
  11. ST-LINE9 — `FICO Score         :` + ST-FICO-SCORE + spaces (`[app/cbl/CBSTM03A.CBL:L116-L119]`)
  12. ST-LINE10 — 80 dashes separator (`[app/cbl/CBSTM03A.CBL:L120-L121]`)
  13. ST-LINE11 — spaces + `TRANSACTION SUMMARY ` + spaces (`[app/cbl/CBSTM03A.CBL:L122-L125]`)
  14. ST-LINE12 — 80 dashes separator (`[app/cbl/CBSTM03A.CBL:L126-L127]`)
  15. ST-LINE13 — column headers `Tran ID` + `Tran Details` + `  Tran Amount` (`[app/cbl/CBSTM03A.CBL:L128-L131]`)
  16. ST-LINE12 — 80 dashes separator (written again at `[app/cbl/CBSTM03A.CBL:L502]`)
  17. ST-LINE14 — repeated per transaction by 6000-WRITE-TRANS (`[app/cbl/CBSTM03A.CBL:L132-L137,L679]`)
  18. ST-LINE12 — 80 dashes separator (written at `[app/cbl/CBSTM03A.CBL:L435]` after transaction loop)
  19. ST-LINE14A — `Total EXP:` + ST-TOTAL-TRAMT with PIC Z(9).99- format (`[app/cbl/CBSTM03A.CBL:L138-L142,L436]`)
  20. ST-LINE15 — asterisks + `END OF STATEMENT` banner (`[app/cbl/CBSTM03A.CBL:L143-L146,L437]`)
- **CRITICAL byte-for-byte parity rules**:
  - Each record is exactly 80 bytes (RECFM=FB,LRECL=80).
  - Trailing FILLER spaces are part of the record bytes — preserve EXACTLY.
  - The `ALL '*'` literal (31+18+31=80 chars asterisk banner for ST-LINE0; 32+16+32=80 for ST-LINE15) and `ALL '-'` literal (80 chars of dashes) produce specific byte sequences per `[app/cbl/CBSTM03A.CBL:L87-L89,L102,L121,L127,L144-L146]`.
  - ST-CURR-BAL uses PIC 9(9).99- (trailing-sign placeholder) per `[app/cbl/CBSTM03A.CBL:L113]`.
  - ST-TRANAMT and ST-TOTAL-TRAMT use PIC Z(9).99- (zero-suppressed leading + trailing-minus placeholder) per `[app/cbl/CBSTM03A.CBL:L137,L142]`.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file exists with comment-style placeholder marker; `CbStm03AGoldenTest` is `@Disabled` until captured content is committed.
- **Capture source**: AWS.M2.CARDDEMO.STATEMNT.PS output from COBOL `STEP040 EXEC PGM=CBSTM03A`.

### `stmt_html.html` — Captured HTMLFILE Output (CAPTURE PLACEHOLDER)

- **DD binding**: `HTMLFILE` per `[app/jcl/CREASTMT.JCL:L92]` (DSN=AWS.M2.CARDDEMO.STATEMNT.HTML).
- **Record format**: FB, LRECL=100, BLKSIZE=800 per `[app/jcl/CREASTMT.JCL:L94]`.
- **COBOL FD**: `FD HTML-FILE` with `FD-HTMLFILE-REC PIC X(100)` per `[app/cbl/CBSTM03A.CBL:L46-L47]`.
- **CRITICAL byte-for-byte HTML parity rules** (BINDING):
  - **Whitespace**: every space and FILLER padding character preserved exactly per `[app/cbl/CBSTM03A.CBL:L148-L223]`.
  - **Attribute order**: exact attribute order such as `style="font-size:16px"`, `width:70%`, `font-family: 'Segoe UI'`, and exact CSS color codes (such as `#1d1d96b3`, `#FFAF33`, `#f2f2f2`, `#33FFD1`, `#33FF5E`) preserved per `[app/cbl/CBSTM03A.CBL:L150-L211]`.
  - **Line endings**: COBOL writes 100-byte fixed-width records — record boundaries become file boundaries; the Java translation MUST write 100-byte records, NOT logical HTML lines.
  - **Self-closing-tag conventions**: COBOL uses paired tags (such as `<p>...</p>`) per `[app/cbl/CBSTM03A.CBL:L168-L211]`; preserve exactly. Do NOT substitute self-closing form.
  - **Bank info constants**: the bank name and address constants from `[app/cbl/CBSTM03A.CBL:L168,L170,L172]` (the COBOL literals appear verbatim in the captured HTML lines) are preserved exactly byte-for-byte.
  - **Embedded variable lines**: HTML-L11 with L11-ACCT (`[app/cbl/CBSTM03A.CBL:L212-L216]`), HTML-L23 with L23-NAME (`[app/cbl/CBSTM03A.CBL:L217-L220]`), HTML-ADDR-LN populated 3 times for ST-ADD1, ST-ADD2, ST-ADD3 (`[app/cbl/CBSTM03A.CBL:L221,L569-L592]`), HTML-BSIC-LN populated 3 times for Account ID, Current Balance, FICO Score (`[app/cbl/CBSTM03A.CBL:L222,L613-L633]`), HTML-TRAN-LN populated 3 times per transaction for TranID, Description, Amount (`[app/cbl/CBSTM03A.CBL:L223,L686-L716]`).
  - **STRING construction with DELIMITED BY '*' and DELIMITED BY '  '** (two spaces): the COBOL STRING statements at `[app/cbl/CBSTM03A.CBL:L560-L718]` build variable-content HTML lines via these idioms; the Java translation MUST preserve the resulting byte sequence and trailing padding exactly.
- **HTML record stream structure** (informational; per `[app/cbl/CBSTM03A.CBL:L506-L723]`):
  - 5100-WRITE-HTML-HEADER paragraph (`[app/cbl/CBSTM03A.CBL:L506-L555]`) writes HTML-L01 through HTML-L08 (DOCTYPE, html, head, meta, title, opening table from `[app/cbl/CBSTM03A.CBL:L150-L158]`), HTML-LTRS, HTML-L10, HTML-L11 (Account ID), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L15, HTML-L16, HTML-L17, HTML-L18 (Bank info), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L22-35.
  - 5200-WRITE-HTML-NMADBS paragraph (`[app/cbl/CBSTM03A.CBL:L558-L672]`) writes HTML-L23 (Name), HTML-ADDR-LN x3 (ST-ADD1, ST-ADD2, ST-ADD3), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L30-42, HTML-L31 (Basic Details header), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L22-35, HTML-BSIC-LN x3 (Account ID, Current Balance, FICO Score), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L30-42, HTML-L43 (Transaction Summary header), HTML-LTDE, HTML-LTRE, HTML-LTRS, HTML-L47, HTML-L48, HTML-LTDE, HTML-L50, HTML-L51, HTML-LTDE, HTML-L53, HTML-L54, HTML-LTDE, HTML-LTRE.
  - 6000-WRITE-TRANS paragraph (`[app/cbl/CBSTM03A.CBL:L675-L723]`) writes per-transaction: HTML-LTRS, HTML-L58, HTML-TRAN-LN (TranID), HTML-LTDE, HTML-L61, HTML-TRAN-LN (Description), HTML-LTDE, HTML-L64, HTML-TRAN-LN (Amount), HTML-LTDE, HTML-LTRE.
  - 4000-TRNXFILE-GET paragraph closing (`[app/cbl/CBSTM03A.CBL:L439-L454]`) writes HTML-LTRS, HTML-L10, HTML-L75 (End of Statement), HTML-LTDE, HTML-LTRE, HTML-L78 (closing table), HTML-L79 (closing body), HTML-L80 (closing html).
- **Status when initially committed**: CAPTURE PLACEHOLDER — file exists with HTML-comment marker; `CbStm03AGoldenTest` is `@Disabled` until captured content is committed.
- **Capture source**: AWS.M2.CARDDEMO.STATEMNT.HTML output from COBOL `STEP040 EXEC PGM=CBSTM03A`.

### `stdout.txt` — Captured DISPLAY (SYSOUT) Output (CAPTURE PLACEHOLDER)

- **DD binding**: `SYSOUT` per `[app/jcl/CREASTMT.JCL:L82]`.
- **Content**: COBOL `DISPLAY` statements from CBSTM03A.
- **Sources of DISPLAY output**:
  - `[app/cbl/CBSTM03A.CBL:L270]` — `` `'Running JCL : '` `` TIOTNJOB `` `' Step '` `` TIOTJSTP — TIOT prologue: emits job name and step name **[DEVIATION: z/OS-specific TIOT inspection; Java translation MUST emit informational equivalent and document in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.4.1]**
  - `[app/cbl/CBSTM03A.CBL:L275]` — `` `'DD Names from TIOT: '` `` — TIOT enumeration header **[DEVIATION]**
  - `[app/cbl/CBSTM03A.CBL:L279,L281,L288,L290]` — `` `': '` `` TIOCDDNM `` `' -- valid UCB'` `` or `` `' --  null UCB'` `` (loop-internal) or `` `' -- null  UCB'` `` (post-loop) per DD entry **[DEVIATION]**
  - `[app/cbl/CBSTM03A.CBL:L359-L361]` — `` `'ERROR READING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L383-L385]` — `` `'ERROR READING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L407-L409]` — `` `'ERROR READING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L739-L741]` — `` `'ERROR OPENING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L751-L753]` — `` `'ERROR READING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path in initial open)
  - `[app/cbl/CBSTM03A.CBL:L774-L776]` — `` `'ERROR OPENING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L792-L794]` — `` `'ERROR OPENING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L810-L812]` — `` `'ERROR OPENING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L844-L846]` — `` `'ERROR READING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L865-L867]` — `` `'ERROR CLOSING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L882-L884]` — `` `'ERROR CLOSING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L898-L900]` — `` `'ERROR CLOSING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L914-L916]` — `` `'ERROR CLOSING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC (error path)
  - `[app/cbl/CBSTM03A.CBL:L922]` — `` `'ABENDING PROGRAM'` `` from 9999-ABEND-PROGRAM (only emitted on failure)
- **Happy-path scenario expectation**: `stdout.txt` contains only the TIOT inspection output (or its Java-equivalent informational lines) — NO error DISPLAYs and NO ABEND line.
- **Status when initially committed**: CAPTURE PLACEHOLDER — file exists with comment-style placeholder marker.
- **Capture source**: SYSOUT from COBOL `STEP040 EXEC PGM=CBSTM03A`.

**Placeholder marker convention**: each of the three data files is initially committed with a comment-style marker that identifies it as a placeholder awaiting capture. For `stdout.txt` (line-based text) the marker is a comment line such as `# PLACEHOLDER: awaiting COBOL capture per MIGRATION_NOTES.md section 1.6`. For `stmt_text.txt` (80-byte fixed-width text) the marker is similarly comment-style but is NOT a valid 80-byte statement record — the test harness MUST detect placeholder state and refuse to assert parity until real captures replace the markers (the `@Disabled` annotation on `CbStm03AGoldenTest` provides this protection). For `stmt_html.html` (100-byte fixed-width HTML records) the marker is an HTML-comment line such as `<!-- PLACEHOLDER: awaiting COBOL capture per MIGRATION_NOTES.md section 1.6 -->`. The placeholder content is DISCARDED in full when real captures arrive; do NOT preserve the placeholder line as the first record of the captured file.

## Phase 3: Conceptual Input Universe

The four input streams that produce the captured baselines are:

- **TRNXFILE** — produced by STEP010 and STEP020 of `app/jcl/CREASTMT.JCL` (`[app/jcl/CREASTMT.JCL:L44-L62]`): a SORT of `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` keyed on CARD-NUM + TRAN-ID per `[app/jcl/CREASTMT.JCL:L53]` (`SORT FIELDS=(263,16,CH,A,1,16,CH,A)`) and rearranged via OUTREC per `[app/jcl/CREASTMT.JCL:L54]` (`OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)`). The output is 350-byte FB records per the TRNX-RECORD layout in `app/cpy/COSTM01.CPY`.
- **XREFFILE** — `app/data/ASCII/cardxref.txt` (50-byte records per `app/cpy/CVACT03Y.cpy`; key is XREF-CARD-NUM PIC X(16); also includes XREF-CUST-ID PIC 9(09) and XREF-ACCT-ID PIC 9(11)).
- **CUSTFILE** — `app/data/ASCII/custdata.txt` (500-byte records per `app/cpy/CUSTREC.cpy`; key is CUST-ID PIC 9(09)).
- **ACCTFILE** — `app/data/ASCII/acctdata.txt` (300-byte records per `app/cpy/CVACT01Y.cpy`; key is ACCT-ID PIC 9(11)).

ASCII fixtures under `app/data/ASCII/` are NEVER copied to this folder per AAP §0.4.1 — they are referenced via classpath relative paths. The TRNXFILE input is produced by a SORT step (STEP010) of CREASTMT.JCL; for the Java test harness, the SORT step is reproduced via an in-memory `Comparator` on the captured TRANSACT input bytes. The input fixture `app/data/ASCII/dailytran.txt` is NOT the right shape — TRNXFILE is keyed on CARD-NUM + TRAN-ID and the body uses the rearranged OUTREC layout — so the test harness MUST produce this synthesized TRNXFILE deterministically before invoking CbStm03A.

CBSTM03A is **read-only** with respect to all input files: TRNXFILE, XREFFILE, CUSTFILE, and ACCTFILE are all opened for INPUT (via the CBSTM03B subroutine; see `M03B-OPEN` set to TRUE in 8100-/8200-/8300-/8400-OPEN paragraphs at `[app/cbl/CBSTM03A.CBL:L732,L767,L785,L803]`) and never written back. Only STMTFILE and HTMLFILE are written, via `OPEN OUTPUT STMT-FILE HTML-FILE` at `[app/cbl/CBSTM03A.CBL:L293]`.

The Java test class resolves classpath fixtures via `this.getClass().getResource("/app/data/ASCII/cardxref.txt")` or an equivalent helper that walks back to the repository-rooted `app/data/ASCII/` tree without copying any bytes. The classpath resolution is configured by a test-only resource provider that maps `/app/data/ASCII/*.txt` to the actual file paths in the `app/data/ASCII/` directory at the repository root, avoiding any need to copy the fixture bytes into `src/test/resources`. This zero-copy resolution preserves AAP §0.2.2 (`app/` tree IMMUTABLE) and AAP §0.4.1 (REFERENCE-only relationship between the Java tests and the ASCII fixtures) simultaneously. The same zero-copy pattern is used by every other golden-record test that consumes the `app/data/ASCII/` fixtures (CBACT01C, CBACT02C, CBACT03C, CBCUS01C, CBTRN01C, CBTRN02C, CBTRN03C); CBSTM03A follows the established pattern.

**Input join graph**: the four input streams form a star-shaped join graph anchored on the cardholder (the XREFFILE entry):

- **XREFFILE** is the iteration spine: 1000-MAINLINE walks it sequentially via 1000-XREFFILE-GET-NEXT.
- **CUSTFILE** is joined to XREFFILE on XREF-CUST-ID (PIC 9(09)) via 2000-CUSTFILE-GET (keyed read).
- **ACCTFILE** is joined to XREFFILE on XREF-ACCT-ID (PIC 9(11)) via 3000-ACCTFILE-GET (keyed read).
- **TRNXFILE** is joined to XREFFILE on XREF-CARD-NUM (PIC X(16)) via 4000-TRNXFILE-GET which searches the pre-buffered WS-TRNX-TABLE for matching rows.

The Java translation MUST preserve these four join semantics exactly: a sequential walk of XREFFILE, keyed lookups against CUSTFILE and ACCTFILE, and a buffered scan of pre-loaded TRNXFILE transactions. The Java repository port interfaces document the read-pattern contract for each file: `streamSequential()` for XREFFILE, `findByCustId(long)` and `findByAcctId(long)` for the keyed reads, and a buffered-list contract for TRNXFILE (since the entire file is read into memory in 8500-READTRNX-READ before any XREF iteration begins).

## Phase 4: Statement Generation Flow (paragraph-by-paragraph)

- **PROCEDURE DIVISION** entry at `[app/cbl/CBSTM03A.CBL:L262]` (header).
- **TIOT/TCB/PSA inspection prologue** at `[app/cbl/CBSTM03A.CBL:L266-L291]` — sets ADDRESS OF PSA-BLOCK to PSAPTR, follows TCB-POINT to TCB-BLOCK, follows TIOT-POINT to TIOT-BLOCK, iterates TIOT entries emitting DISPLAYs **[DEVIATION]**.
- **OPEN OUTPUT STMT-FILE HTML-FILE** at `[app/cbl/CBSTM03A.CBL:L293]`.
- **INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR** at `[app/cbl/CBSTM03A.CBL:L294]`.
- **0000-START dispatcher** at `[app/cbl/CBSTM03A.CBL:L296-L314]` — EVALUATE WS-FL-DD then ALTER + GO TO into the appropriate file-open paragraph (TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE) or readtrnx loop **[DEVIATION: ALTER + GO TO; Java translation MUST express as an explicit state machine OR as sequential method invocations preserving the same TRNXFILE -> XREFFILE -> CUSTFILE -> ACCTFILE -> READTRNX -> MAINLINE ordering]**.
- **1000-MAINLINE** at `[app/cbl/CBSTM03A.CBL:L316-L342]` — PERFORM UNTIL END-OF-FILE='Y': read next XREF record, look up CUSTOMER, look up ACCOUNT, build the statement, write per-card transactions; then close all files.
- **1000-XREFFILE-GET-NEXT** at `[app/cbl/CBSTM03A.CBL:L345-L366]` — sequential READ of XREFFILE via CBSTM03B; EVALUATE WS-M03B-RC: `'00'` continue, `'10'` end-of-file, OTHER abend.
- **2000-CUSTFILE-GET** at `[app/cbl/CBSTM03A.CBL:L368-L390]` — keyed READ of CUSTFILE by XREF-CUST-ID via CBSTM03B.
- **3000-ACCTFILE-GET** at `[app/cbl/CBSTM03A.CBL:L392-L414]` — keyed READ of ACCTFILE by XREF-ACCT-ID via CBSTM03B.
- **4000-TRNXFILE-GET** at `[app/cbl/CBSTM03A.CBL:L416-L456]` — iterate WS-CARD-TBL searching for current XREF-CARD-NUM; if found, iterate WS-TRAN-TBL writing each transaction via 6000-WRITE-TRANS and accumulating WS-TOTAL-AMT; then write closing ST-LINE12, ST-LINE14A, ST-LINE15 (text records at `[app/cbl/CBSTM03A.CBL:L435-L437]`) and HTML close tags (`[app/cbl/CBSTM03A.CBL:L439-L454]`).
- **5000-CREATE-STATEMENT** at `[app/cbl/CBSTM03A.CBL:L458-L504]` — INITIALIZE STATEMENT-LINES, WRITE ST-LINE0, perform 5100-WRITE-HTML-HEADER THRU 5100-EXIT, STRING customer name into ST-NAME (`[app/cbl/CBSTM03A.CBL:L462-L469]`), MOVE address lines (`[app/cbl/CBSTM03A.CBL:L470-L482]`), perform 5200-WRITE-HTML-NMADBS THRU 5200-EXIT, then WRITE statement-line records ST-LINE1 through ST-LINE13 with two ST-LINE12 separators (`[app/cbl/CBSTM03A.CBL:L488-L502]`).
- **5100-WRITE-HTML-HEADER** at `[app/cbl/CBSTM03A.CBL:L506-L555]` and **5100-EXIT** at `[app/cbl/CBSTM03A.CBL:L554-L555]` — WRITE the HTML-L01 through HTML-L22-35 sequence.
- **5200-WRITE-HTML-NMADBS** at `[app/cbl/CBSTM03A.CBL:L558-L669]` and **5200-EXIT** at `[app/cbl/CBSTM03A.CBL:L671-L672]` — STRING construction for HTML-L23 (Name), HTML-ADDR-LN x3 (address lines), HTML-BSIC-LN x3 (basic details), and the column-header sequence.
- **6000-WRITE-TRANS** at `[app/cbl/CBSTM03A.CBL:L675-L723]` — write ST-LINE14 (text) and the full HTML transaction-row sequence per transaction.
- **8100-FILE-OPEN** at `[app/cbl/CBSTM03A.CBL:L726-L728]` — ALTERed paragraph: initial `GO TO 8100-TRNXFILE-OPEN` at `[app/cbl/CBSTM03A.CBL:L727]`; ALTER statements at `[app/cbl/CBSTM03A.CBL:L300,L303,L306,L309]` modify the GO TO target before each call **[DEVIATION]**.
- **8100-TRNXFILE-OPEN** at `[app/cbl/CBSTM03A.CBL:L730-L762]` — CALL CBSTM03B to open TRNXFILE, then read the first record; set CR-CNT=1, TR-CNT=0; set WS-FL-DD='READTRNX'; GO TO 0000-START **[DEVIATION: GO TO]**.
- **8200-XREFFILE-OPEN** at `[app/cbl/CBSTM03A.CBL:L765-L781]` — CALL CBSTM03B to open XREFFILE; set WS-FL-DD='CUSTFILE'; GO TO 0000-START **[DEVIATION]**.
- **8300-CUSTFILE-OPEN** at `[app/cbl/CBSTM03A.CBL:L783-L799]` — CALL CBSTM03B to open CUSTFILE; set WS-FL-DD='ACCTFILE'; GO TO 0000-START **[DEVIATION]**.
- **8400-ACCTFILE-OPEN** at `[app/cbl/CBSTM03A.CBL:L801-L816]` — CALL CBSTM03B to open ACCTFILE; GO TO 1000-MAINLINE **[DEVIATION]**.
- **8500-READTRNX-READ** at `[app/cbl/CBSTM03A.CBL:L818-L847]` — iterative loop: if same CARD as previous record, increment TR-CNT; else save TR-CNT in WS-TRCT(CR-CNT), increment CR-CNT, reset TR-CNT to 1; populate WS-TRNX-TABLE; read the next TRNXFILE record via CBSTM03B; EVALUATE WS-M03B-RC: `'00'` GO TO 8500-READTRNX-READ (recurse), `'10'` GO TO 8599-EXIT, OTHER abend **[DEVIATION]**.
- **8599-EXIT** at `[app/cbl/CBSTM03A.CBL:L849-L853]` — finalize WS-TRCT(CR-CNT), set WS-FL-DD='XREFFILE', GO TO 0000-START **[DEVIATION]**.
- **9100-TRNXFILE-CLOSE** at `[app/cbl/CBSTM03A.CBL:L856-L870]` — CALL CBSTM03B to close TRNXFILE.
- **9200-XREFFILE-CLOSE** at `[app/cbl/CBSTM03A.CBL:L873-L887]` — CALL CBSTM03B to close XREFFILE.
- **9300-CUSTFILE-CLOSE** at `[app/cbl/CBSTM03A.CBL:L889-L903]` — CALL CBSTM03B to close CUSTFILE.
- **9400-ACCTFILE-CLOSE** at `[app/cbl/CBSTM03A.CBL:L905-L919]` — CALL CBSTM03B to close ACCTFILE.
- **CLOSE STMT-FILE HTML-FILE** at `[app/cbl/CBSTM03A.CBL:L339]`.
- **9999-GOBACK** at `[app/cbl/CBSTM03A.CBL:L341-L342]` — final GOBACK.
- **9999-ABEND-PROGRAM** at `[app/cbl/CBSTM03A.CBL:L921-L923]` — DISPLAY `` `'ABENDING PROGRAM'` ``, CALL `` `'CEE3ABD'` ``.

The execution graph has a discoverable shape that the Java translation MUST preserve: the program first runs the TIOT inspection block (a DEVIATION-flagged informational prologue), then opens both output files (`STMT-FILE` and `HTML-FILE`), then opens all four input files sequentially via the dispatched 8100/8200/8300/8400 OPEN paragraphs, then runs the 8500-READTRNX-READ buffering loop to fill WS-TRNX-TABLE, then enters 1000-MAINLINE which iterates XREFFILE and per-iteration looks up CUSTFILE and ACCTFILE by key and synthesizes the statement (via 5000/5100/5200) and writes per-transaction rows (via 6000-WRITE-TRANS), then closes all files (via 9100/9200/9300/9400) and finally executes 9999-GOBACK. This linear chain — input phase, buffer phase, output phase, close phase — is the canonical execution shape that the Java translation expresses without the GO TO and ALTER constructs.

## Phase 5: Verbatim COBOL DISPLAY Message Catalog

| # | Verbatim Bytes | Line | Context |
|---|---|---|---|
| 1 | `` `'Running JCL : '` `` TIOTNJOB `` `' Step '` `` TIOTJSTP | L270 | TIOT prologue: emits job name and step name **[DEVIATION]** |
| 2 | `` `'DD Names from TIOT: '` `` | L275 | TIOT enumeration header **[DEVIATION]** |
| 3 | `` `': '` `` TIOCDDNM `` `' -- valid UCB'` `` | L279 | Per-DD entry (non-null UCB), loop-internal **[DEVIATION]** |
| 4 | `` `': '` `` TIOCDDNM `` `' --  null UCB'` `` | L281 | Per-DD entry (null UCB), loop-internal; note TWO SPACES before `null` **[DEVIATION]** |
| 5 | `` `': '` `` TIOCDDNM `` `' -- valid UCB'` `` | L288 | Post-loop final entry (non-null UCB) **[DEVIATION]** |
| 6 | `` `': '` `` TIOCDDNM `` `' -- null  UCB'` `` | L290 | Post-loop final entry (null UCB); note TWO SPACES between `null` and `UCB` **[DEVIATION]** |
| 7 | `` `'ERROR READING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L359-L360 | Error path in 1000-XREFFILE-GET-NEXT |
| 8 | `` `'ERROR READING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L383-L384 | Error path in 2000-CUSTFILE-GET |
| 9 | `` `'ERROR READING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L407-L408 | Error path in 3000-ACCTFILE-GET |
| 10 | `` `'ERROR OPENING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L739-L740 | Error path in 8100-TRNXFILE-OPEN (open) |
| 11 | `` `'ERROR READING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L751-L752 | Error path in 8100-TRNXFILE-OPEN (first read after open) |
| 12 | `` `'ERROR OPENING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L774-L775 | Error path in 8200-XREFFILE-OPEN |
| 13 | `` `'ERROR OPENING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L792-L793 | Error path in 8300-CUSTFILE-OPEN |
| 14 | `` `'ERROR OPENING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L810-L811 | Error path in 8400-ACCTFILE-OPEN |
| 15 | `` `'ERROR READING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L844-L845 | Error path in 8500-READTRNX-READ |
| 16 | `` `'ERROR CLOSING TRNXFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L865-L866 | Error path in 9100-TRNXFILE-CLOSE |
| 17 | `` `'ERROR CLOSING XREFFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L882-L883 | Error path in 9200-XREFFILE-CLOSE |
| 18 | `` `'ERROR CLOSING CUSTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L898-L899 | Error path in 9300-CUSTFILE-CLOSE |
| 19 | `` `'ERROR CLOSING ACCTFILE'` `` + `` `'RETURN CODE: '` `` WS-M03B-RC | L914-L915 | Error path in 9400-ACCTFILE-CLOSE |
| 20 | `` `'ABENDING PROGRAM'` `` | L922 | Final emission inside 9999-ABEND-PROGRAM (only on failure path) |

All catalog entries preserve EXACT bytes including embedded spaces, colons, comment text, and single quotes. NO Unicode characters appear in any catalogued message. The Java translation MUST produce byte-identical strings for every message that is reproduced (for the TIOT inspection block at entries 1-6, the Java translation emits informational equivalent output per the DEVIATION documented in `java/MIGRATION_NOTES.md` §1.6).

**Spacing idiosyncrasy at L290 versus L281**: entry #4 (`` `' --  null UCB'` `` at L281) has TWO SPACES before `null`. Entry #6 (`` `' -- null  UCB'` `` at L290) has TWO SPACES between `null` and `UCB`. The two messages are NOT identical — they have different space patterns. BOTH are PRESERVED EXACTLY per AAP §0.7.1; the Java translation MUST emit each variant on the corresponding code path (loop-internal vs. post-loop). Conflating the two messages into a single byte sequence is a parity break.

**WS-FL-DD enum values**: `TRNXFILE`, `XREFFILE`, `CUSTFILE`, `ACCTFILE`, `READTRNX` per `[app/cbl/CBSTM03A.CBL:L67,L298-L314]`. These translate to a sealed `FileDispatchState` hierarchy in Java per AAP §0.6.2 (see Phase 10).

**Error-message format convention**: entries 7-19 all follow the format `'ERROR <action> <file>'` (single line) followed by a separate DISPLAY of `'RETURN CODE: '` + WS-M03B-RC value (`PIC X(02)`). The two lines are emitted by two distinct DISPLAY statements; on the captured `stdout.txt` they appear as two consecutive lines. The Java translation MUST preserve this two-line emission pattern — combining the two lines into one log call would change the captured bytes. The `'RETURN CODE: '` literal includes a trailing SPACE before the WS-M03B-RC value (e.g., `RETURN CODE: 23`).

**Final-line ABEND emission**: entry 20 (`'ABENDING PROGRAM'`) is emitted by the 9999-ABEND-PROGRAM paragraph at `[app/cbl/CBSTM03A.CBL:L921-L923]`. This paragraph is invoked via `PERFORM 9999-ABEND-PROGRAM` from the error branches at `[app/cbl/CBSTM03A.CBL:L364,L388,L412,L744,L756,L779,L797,L815,L848,L870,L887,L903,L919]`. Each invocation emits the same single ABEND line, then calls `CEE3ABD` which terminates the program. On the happy path, the ABEND paragraph is never invoked and the ABEND line does NOT appear in `stdout.txt`. The Java translation throws the abend exception in the same control flow positions; the exception unwind triggers the equivalent of `CEE3ABD` termination.

**ABEND-emission interaction with file state**: when 9999-ABEND-PROGRAM is invoked, the program terminates abruptly via `CEE3ABD` WITHOUT executing the 9100-9400 CLOSE paragraphs or the explicit `CLOSE STMT-FILE HTML-FILE` at `[app/cbl/CBSTM03A.CBL:L339]`. This means the COBOL captured outputs may be partial (the OS file system flushes whatever was buffered before the abend, but no explicit CLOSE is performed; this can leave the file in an inconsistent state on z/OS). For the Java translation, the recommendation is to use try-with-resources blocks (or a `finally` block calling `close()` on both writers) so that the output files are properly closed even when an exception unwinds the stack. The Java test harness asserts byte-for-byte parity on the captured output files; if the COBOL capture is partial (truncated) due to an abend, the Java implementation MUST also produce a similarly-truncated output to match — but the goal in practice is to have the happy-path test exercise a successful run, with separate failure-path tests asserting that the abend exception is thrown without asserting on the truncated file contents.

## Phase 6: DEVIATION Items (TIOT/TCB/PSA, ALTER/GO TO, 2D Table)

| Deviation | CBSTM03A source | Java equivalent | Parity impact |
|-----------|-----------------|------------------|---------------|
| TIOT/TCB/PSA control-block inspection | `[app/cbl/CBSTM03A.CBL:L235-L260]` (LINKAGE SECTION declaring PSA-BLOCK, TCB-BLOCK, TIOT-BLOCK, TIOT-ENTRY) and `[app/cbl/CBSTM03A.CBL:L262-L291]` (PROCEDURE DIVISION TIOT inspection) | Informational DISPLAY of configured file paths and DD-equivalent identifiers from `application.properties` | `stdout.txt` content differs in the TIOT section only; documented in `java/MIGRATION_NOTES.md` §1.6; informational only — does NOT affect `stmt_text.txt` or `stmt_html.html` byte parity |
| ALTER + GO TO control flow (0000-START dispatcher) | `[app/cbl/CBSTM03A.CBL:L296-L314]` (0000-START with ALTER) and GO TO statements at `[app/cbl/CBSTM03A.CBL:L727,L761,L780,L798,L815,L840,L852]` | Explicit Java state machine using a sealed `FileDispatchState` interface OR sequential method invocations preserving the same TRNXFILE -> XREFFILE -> CUSTFILE -> ACCTFILE -> READTRNX -> MAINLINE ordering | NONE — Java execution order MUST produce an identical output sequence |
| 2D in-memory table WS-TRNX-TABLE (51 cards x 10 transactions) | `[app/cbl/CBSTM03A.CBL:L225-L230]` (`OCCURS 51 TIMES` at L226 and `OCCURS 10 TIMES` at L228) | Java `record CardTransactionBuffer(String cardNum, List<TrnxRecord> transactions)` constrained to 51 cards max and 10 transactions per card max; OR fixed-size `String[51][10]` with explicit count tracking | NONE — buffering semantics preserved; capacity boundary preserved exactly |
| COMP / COMP-3 arithmetic | `[app/cbl/CBSTM03A.CBL:L59-L65]`: CR-CNT, TR-CNT, CR-JMP, TR-JMP PIC S9(4) COMP; WS-TOTAL-AMT PIC S9(9)V99 COMP-3 | `int` for counters; `BigDecimal` with scale 2 via the `Decimals` utility for the monetary accumulator | NONE — totals MUST match to last cent per AAP §0.6.1 |
| CBSTM03B CALL subroutine | 13 call sites at `[app/cbl/CBSTM03A.CBL:L351,L377,L401,L734,L746,L769,L787,L805,L835,L860,L877,L893,L909]` (covering all OPEN, READ, READ-K, and CLOSE operations) | Direct Java method invocations on a constructor-injected `CbStm03B` collaborator; return codes preserved as String `'00'` / `'04'` / `'10'` / OTHER | NONE — return codes (`'00'` and `'04'` continue, `'10'` EOF, OTHER abend) preserved exactly per AAP §0.7.1 |

All deviations are documented in `java/MIGRATION_NOTES.md` §1.6 per AAP §0.4.1. The deviations do NOT introduce behavior changes — they translate z/OS-specific idioms into equivalent Java idioms while preserving identical observable output bytes for the STMTFILE and HTMLFILE outputs. The `stdout.txt` parity assertion is necessarily relaxed for the TIOT inspection section only, because the contents of that section depend on a z/OS-specific control block tree that does not exist in a Java environment.

**Deviation severity rationale**: each of the five deviations is rated by parity impact in the table above. The first (TIOT inspection) is the only deviation that introduces a relaxed parity assertion, and it is confined to one section of one of the three output files. The remaining four deviations (ALTER + GO TO control flow, 2D in-memory table, COMP / COMP-3 arithmetic, and CBSTM03B CALL subroutine) have NO parity impact — they are pure idiom-for-idiom translations that produce byte-identical output. The DEVIATION-FLAGGED status of CBSTM03A is therefore a precise scope: only the `stdout.txt` TIOT-section relaxation is in play; everything else is byte-exact. PR review SHOULD reject any future patch that broadens the DEVIATION scope (e.g., relaxing parity on `stmt_text.txt` or `stmt_html.html`) without an accompanying update to `java/MIGRATION_NOTES.md` §1.6 and an explicit AAP §0.7.1 justification.


## Phase 7: 80-Byte STMTFILE and 100-Byte HTMLFILE Record Layouts

**STMTFILE (80-byte records)**: per `[app/cbl/CBSTM03A.CBL:L44-L45]` (`FD STMT-FILE` and `01 FD-STMTFILE-REC PIC X(80)`) and `[app/jcl/CREASTMT.JCL:L89]` (`DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)`). The 17 STATEMENT-LINES record templates (ST-LINE0 through ST-LINE15 plus ST-LINE14A) each total exactly 80 bytes:

- ST-LINE0 (`[app/cbl/CBSTM03A.CBL:L86-L89]`): 31 + 18 + 31 = 80 bytes (asterisks + `START OF STATEMENT` literal + asterisks).
- ST-LINE1 (`[app/cbl/CBSTM03A.CBL:L90-L92]`): 75 + 5 = 80 bytes (ST-NAME + FILLER spaces).
- ST-LINE2 (`[app/cbl/CBSTM03A.CBL:L93-L95]`): 50 + 30 = 80 bytes (ST-ADD1 + FILLER spaces).
- ST-LINE3 (`[app/cbl/CBSTM03A.CBL:L96-L98]`): 50 + 30 = 80 bytes (ST-ADD2 + FILLER spaces).
- ST-LINE4 (`[app/cbl/CBSTM03A.CBL:L99-L100]`): 80 bytes (ST-ADD3).
- ST-LINE5 (`[app/cbl/CBSTM03A.CBL:L101-L102]`): 80 bytes (`ALL '-'`).
- ST-LINE6 (`[app/cbl/CBSTM03A.CBL:L103-L106]`): 33 + 14 + 33 = 80 bytes (FILLER spaces + `Basic Details` + FILLER spaces).
- ST-LINE7 (`[app/cbl/CBSTM03A.CBL:L107-L110]`): 20 + 20 + 40 = 80 bytes (`Account ID         :` + ST-ACCT-ID + FILLER spaces).
- ST-LINE8 (`[app/cbl/CBSTM03A.CBL:L111-L115]`): 20 + 13 + 7 + 40 = 80 bytes (`Current Balance    :` + ST-CURR-BAL [PIC 9(9).99-, 13 chars] + FILLER spaces + FILLER spaces).
- ST-LINE9 (`[app/cbl/CBSTM03A.CBL:L116-L119]`): 20 + 20 + 40 = 80 bytes (`FICO Score         :` + ST-FICO-SCORE + FILLER spaces).
- ST-LINE10 (`[app/cbl/CBSTM03A.CBL:L120-L121]`): 80 bytes (`ALL '-'`).
- ST-LINE11 (`[app/cbl/CBSTM03A.CBL:L122-L125]`): 30 + 20 + 30 = 80 bytes (FILLER spaces + `TRANSACTION SUMMARY ` + FILLER spaces).
- ST-LINE12 (`[app/cbl/CBSTM03A.CBL:L126-L127]`): 80 bytes (`ALL '-'`).
- ST-LINE13 (`[app/cbl/CBSTM03A.CBL:L128-L131]`): 16 + 51 + 13 = 80 bytes (`Tran ID         ` + `Tran Details    ` (padded to 51) + `  Tran Amount`).
- ST-LINE14 (`[app/cbl/CBSTM03A.CBL:L132-L137]`): 16 + 1 + 49 + 1 + 13 = 80 bytes (ST-TRANID + literal single space + ST-TRANDT + literal `$` + ST-TRANAMT [PIC Z(9).99-, 13 chars]).
- ST-LINE14A (`[app/cbl/CBSTM03A.CBL:L138-L142]`): 10 + 56 + 1 + 13 = 80 bytes (`Total EXP:` + FILLER spaces + literal `$` + ST-TOTAL-TRAMT [PIC Z(9).99-, 13 chars]).
- ST-LINE15 (`[app/cbl/CBSTM03A.CBL:L143-L146]`): 32 + 16 + 32 = 80 bytes (asterisks + `END OF STATEMENT` literal + asterisks).

**HTMLFILE (100-byte records)**: per `[app/cbl/CBSTM03A.CBL:L46-L47]` (`FD HTML-FILE` and `01 FD-HTMLFILE-REC PIC X(100)`) and `[app/jcl/CREASTMT.JCL:L94]` (`DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)`). HTML content is divided into two patterns:

- **Fixed constants via 88-level conditions**: HTML-FIXED-LN PIC X(100) at `[app/cbl/CBSTM03A.CBL:L149]` is a 100-byte buffer. `SET HTML-Lxx TO TRUE` assigns one of the 88-level named string literals (HTML-L01 through HTML-L80, HTML-LTRS, HTML-LTRE, HTML-LTDS, HTML-LTDE) into that buffer. Each literal occupies the buffer left-justified; the remainder is padded with trailing spaces to total exactly 100 bytes per COBOL VALUE semantics. The COBOL HTML content includes 88-level continuation lines such as the HTML-L08 entry at `[app/cbl/CBSTM03A.CBL:L157-L158]` and the HTML-L47 entry at `[app/cbl/CBSTM03A.CBL:L184-L185]`.
- **Variable-content lines**: HTML-L11 with L11-ACCT (`[app/cbl/CBSTM03A.CBL:L212-L216]`) totals 100 bytes including embedded variable + trailing FILLER padding; HTML-L23 with L23-NAME (`[app/cbl/CBSTM03A.CBL:L217-L220]`) likewise. HTML-ADDR-LN, HTML-BSIC-LN, HTML-TRAN-LN are each PIC X(100) populated via STRING with the DELIMITED BY '*' (never-matching) and DELIMITED BY '  ' (two-space) idioms at `[app/cbl/CBSTM03A.CBL:L560-L718]`.

The Java translation MUST produce EXACT byte-for-byte output including precise trailing-space counts. Use `String.format("%-100s", content)` or an equivalent fixed-width padding utility, or — preferred — pre-fill a `byte[100]` with `0x20` (ASCII space) and copy the literal into the leading bytes. The trailing-space FILLER bytes are part of the record contract; they MUST appear in the captured baseline.

**Record-write invariant** (binding on the Java translation): every `WRITE FD-STMTFILE-REC FROM <source>` in the COBOL source corresponds to exactly one `byte[80]` flush to the STMTFILE output stream; every `WRITE FD-HTMLFILE-REC FROM <source>` corresponds to exactly one `byte[100]` flush to the HTMLFILE output stream. The Java writer MUST NOT batch writes into multi-record buffers in a way that changes the byte order written to disk, and MUST NOT insert any newline, line-feed, or record-terminator byte between consecutive records (RECFM=FB has no inter-record delimiter; records are stored back-to-back). The total byte length of `stmt_text.txt` is exactly `(record_count * 80)`; the total byte length of `stmt_html.html` is exactly `(record_count * 100)`. Any deviation from these byte-length invariants is a parity break.

**Buffered-output ordering invariant**: when the Java translation uses a buffered writer (e.g., a `BufferedOutputStream` wrapping a `SeekableByteChannel`), the buffer flush MUST preserve the order in which `WRITE` operations were submitted. A buffer flush MUST NOT reorder records. Most modern Java NIO writer implementations preserve order naturally, but custom-built batch writers MUST be reviewed for this invariant.

**Trailing-sign placeholder semantics**: COBOL PIC clauses `9(9).99-` and `Z(9).99-` reserve the rightmost byte as a sign placeholder. For positive (or unsigned) values, this byte is rendered as a SPACE (`0x20`). For negative values, this byte is rendered as `-` (`0x2D`). The `Decimals.encodeTrailingSign(BigDecimal value, int integerDigits, int fractionDigits, boolean zeroSuppress)` utility centralizes the rendering rule; the Java translation MUST route ALL ST-CURR-BAL, ST-TRANAMT, and ST-TOTAL-TRAMT rendering through this utility to ensure consistent sign-placeholder semantics. Empirical verification: a positive 1234.56 in a PIC 9(9).99- field renders as the 13-character byte sequence `0001234.56` followed by a single SPACE; a negative -1234.56 renders as `0001234.56` followed by a single `-`. For PIC Z(9).99- (zero-suppressed), the leading zeros are replaced with SPACES instead.

**Encoding nuance for zero**: a value of zero in a PIC 9(9).99- field renders as `0000000.00` followed by SPACE (positive zero); the same value in a PIC Z(9).99- field renders as 9 SPACES followed by `.00` followed by SPACE (the leading zeros are suppressed to a single trailing-most digit, then `.00`, then sign-placeholder SPACE). The `Decimals` utility handles both cases via its `zeroSuppress` parameter; the Java translation MUST NOT special-case zero in calling code.

**Encoding nuance for overflow**: a value with more integer digits than the PIC clause permits (e.g., a value of 12345678901.00 in a PIC 9(9).99- field, where the integer part has 11 digits but the PIC clause permits only 9) triggers COBOL truncation from the high end — the leftmost digits are dropped silently. The Java translation MUST preserve this truncation semantic: the `Decimals` utility's encode method takes the value modulo `10^integerDigits` to match COBOL truncation. The Java translation MUST NOT throw on overflow; throwing would change observable behavior. PR review SHOULD verify that the `Decimals` utility implements modulo-truncation rather than overflow exceptions.

## Phase 8: Three Output Files Byte Contracts

1. **STMTFILE (`stmt_text.txt`)** — 80-byte FB records; FD declared at `[app/cbl/CBSTM03A.CBL:L44-L45]`; WRITE FD-STMTFILE-REC FROM ST-LINE<n> at multiple sites in paragraphs 5000-CREATE-STATEMENT (e.g., `[app/cbl/CBSTM03A.CBL:L460,L488-L502]`), 4000-TRNXFILE-GET (`[app/cbl/CBSTM03A.CBL:L435-L437]`), and 6000-WRITE-TRANS (`[app/cbl/CBSTM03A.CBL:L679]`); OPEN OUTPUT at `[app/cbl/CBSTM03A.CBL:L293]`; sequential append-only ordered by XREF input order (one statement block per cardholder). Byte-for-byte equality is enforced by `GoldenRecordTest.byteForByteParity()`; trailing whitespace and padding direction are preserved exactly; record boundaries are preserved (no newline insertion within fixed-width records — the captured file MAY be read as 80-byte chunks).
2. **HTMLFILE (`stmt_html.html`)** — 100-byte FB records; FD declared at `[app/cbl/CBSTM03A.CBL:L46-L47]`; WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN or HTML-Lxx variable line at multiple sites in paragraphs 5100-WRITE-HTML-HEADER (`[app/cbl/CBSTM03A.CBL:L508-L552]`), 5200-WRITE-HTML-NMADBS (`[app/cbl/CBSTM03A.CBL:L561-L668]`), 6000-WRITE-TRANS (`[app/cbl/CBSTM03A.CBL:L681-L721]`), and 4000-TRNXFILE-GET (`[app/cbl/CBSTM03A.CBL:L439-L454]`); OPEN OUTPUT at `[app/cbl/CBSTM03A.CBL:L293]`; sequential append-only ordered by XREF input order. Byte-for-byte equality is enforced by `GoldenRecordTest.byteForByteParity()`; trailing whitespace and padding direction are preserved exactly; record boundaries are preserved.
3. **SYSOUT (`stdout.txt`)** — line-based ASCII text; DISPLAY statements at the lines enumerated in Phase 5. In the happy-path scenario `stdout.txt` contains only the unconditional TIOT-prologue DISPLAYs at L270 and L275, the TIOT-loop DISPLAYs at L279 or L281 (one per iterated TIOT entry), and the post-loop final-entry DISPLAY at L288 or L290 — total line count varies by TIOT entry count **[DEVIATION: z/OS-specific output; parity for the TIOT section is RELAXED to a structural assertion per Phase 12]**. The error and ABEND DISPLAYs only appear in failure scenarios; they MUST be byte-identical when they do appear.

The three output files form a triplet that MUST be captured together from a single COBOL run — capturing them from different runs (even of identical inputs) MAY introduce divergence because system-dependent fields (e.g., job name in the TIOT prologue) are run-specific. The Java test harness's parity assertion for `stmt_text.txt` and `stmt_html.html` is byte-exact via `Arrays.equals(actualBytes, expectedBytes)` or the AssertJ equivalent; the parity assertion for `stdout.txt` partitions the file into a TIOT-section prefix and a body suffix, comparing the TIOT prefix structurally (line count, line-shape pattern matching) and the body byte-exact. The split point between TIOT prefix and body is heuristic: the TIOT section ends with the post-loop final-entry DISPLAY at L288 / L290; any subsequent line is part of the body and is byte-compared exactly. Failure scenarios produce both prefix and body content; the body content (error messages and ABEND line) MUST match exactly.

## Phase 9: Preserved Behaviors and Idiosyncrasies (DO NOT FIX)

1. **TIOT/TCB/PSA inspection prologue** at `[app/cbl/CBSTM03A.CBL:L262-L291]` is a z/OS-specific debugging aid using LINKAGE SECTION (`[app/cbl/CBSTM03A.CBL:L239-L260]`) and SET ADDRESS OF (`[app/cbl/CBSTM03A.CBL:L266-L269]`) to dereference system control blocks. PRESERVED via an informational equivalent in Java per AAP §0.4.1; deviation fully documented in `java/MIGRATION_NOTES.md` §1.6.
2. **ALTER statement** at `[app/cbl/CBSTM03A.CBL:L300,L303,L306,L309]` modifies the `GO TO` target inside 8100-FILE-OPEN at runtime. This is a discouraged COBOL idiom by modern style standards, but PRESERVED per AAP §0.7.1 — translated to an explicit Java state machine in `CbStm03A` (no Java reflection or bytecode rewriting is required).
3. **GO TO 0000-START loop** at `[app/cbl/CBSTM03A.CBL:L761,L780,L798,L840,L852]` implements a state-machine dispatcher via WS-FL-DD transitions. PRESERVED via sequential method invocations OR a sealed `FileDispatchState` interface in Java.
4. **DISPLAY spacing at L290 versus L281**: the loop-internal null-UCB branch at `[app/cbl/CBSTM03A.CBL:L281]` emits `` `' --  null UCB'` `` with TWO SPACES before `null`. The post-loop null-UCB branch at `[app/cbl/CBSTM03A.CBL:L290]` emits `` `' -- null  UCB'` `` with TWO SPACES between `null` and `UCB`. The two messages are NOT identical — they have different space patterns. PRESERVED EXACTLY per AAP §0.7.1.
5. **2D table fixed capacity**: WS-TRNX-TABLE at `[app/cbl/CBSTM03A.CBL:L225-L230]` is capped at 51 cards x 10 transactions = 510 in-memory transactions. The Java translation MUST enforce the same capacity boundary; exceeding it triggers undefined behavior in the COBOL source. PRESERVED per AAP §0.7.1 (the capacity boundary is a contract, not a tunable parameter).
6. **8100-FILE-OPEN initial GO TO target** at `[app/cbl/CBSTM03A.CBL:L727]` is `GO TO 8100-TRNXFILE-OPEN` — i.e., before any ALTER, the dispatcher goes to TRNXFILE-OPEN by default (matching the initial WS-FL-DD value `'TRNXFILE'` at `[app/cbl/CBSTM03A.CBL:L67]`). The Java translation MUST initialize the state machine accordingly. PRESERVED per AAP §0.7.1.
7. **PERFORM VARYING in 4000-TRNXFILE-GET** at `[app/cbl/CBSTM03A.CBL:L417-L419]` uses two termination conditions: `UNTIL CR-JMP > CR-CNT OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM)`. The second condition exits early if the table is found to be sorted past the current XREF card. PRESERVED per AAP §0.7.1.
8. **WS-M03B-RC handling**: CBSTM03B return codes are interpreted as `'00'` or `'04'` continue (both accepted as success per `[app/cbl/CBSTM03A.CBL:L736,L748,L771,L789,L807,L862,L879,L895,L911]`), `'10'` end-of-file (per `[app/cbl/CBSTM03A.CBL:L356,L841]`), OTHER abend. The Java translation MUST preserve this 4-state semantic via a sealed `Cbstm03bResult` hierarchy per AAP §0.6.2. PRESERVED per AAP §0.7.1.
9. **Sequential ordering by XREF input order**: 1000-MAINLINE (`[app/cbl/CBSTM03A.CBL:L316-L329]`) reads XREFFILE sequentially. The order in which statements appear in `stmt_text.txt` and `stmt_html.html` is DEFINED BY this sequential XREF iteration. Reordering output across cardholders would change observable bytes and is FORBIDDEN per AAP §0.6.6.
10. **Buffered transaction prefetch before XREF iteration**: 8500-READTRNX-READ at `[app/cbl/CBSTM03A.CBL:L818-L847]` reads ALL of TRNXFILE into the 2D WS-TRNX-TABLE BEFORE 1000-MAINLINE begins its XREFFILE walk. This is NOT a streaming pipeline — it is a buffer-then-join pattern. The Java translation MUST preserve this two-phase ordering (prefetch all transactions, then iterate XREF) because the 4000-TRNXFILE-GET inner search at `[app/cbl/CBSTM03A.CBL:L417-L434]` depends on the buffer being fully populated. PRESERVED per AAP §0.7.1.
11. **Transaction-card grouping invariant**: 8500-READTRNX-READ at `[app/cbl/CBSTM03A.CBL:L820-L832]` groups consecutive TRNXFILE records by TRNX-CARD-NUM. The input is sorted by CARD-NUM + TRAN-ID via STEP010 SORT at `[app/jcl/CREASTMT.JCL:L53]` so consecutive same-card records form a group; the grouping detection logic at `[app/cbl/CBSTM03A.CBL:L823-L831]` compares the current card to the previous card and either increments TR-CNT or finalizes the prior group. The Java translation MUST preserve this grouping logic — if the SORT order is violated (records for one card are not contiguous), transactions for the same card will be split across multiple WS-CARD-TBL entries. PRESERVED per AAP §0.7.1.
12. **Empty-statement-block for a cardholder with no transactions**: 4000-TRNXFILE-GET at `[app/cbl/CBSTM03A.CBL:L417-L434]` iterates WS-CARD-TBL searching for a matching XREF-CARD-NUM. If no match is found, the inner WS-TRAN-TBL loop is skipped entirely and the routine proceeds to write only the closing ST-LINE12 + ST-LINE14A + ST-LINE15 sequence at `[app/cbl/CBSTM03A.CBL:L435-L437]`. The resulting statement contains a `Total EXP:` line with zero amount and an `END OF STATEMENT` banner — but NO ST-LINE14 transaction-detail rows. PRESERVED per AAP §0.7.1.
13. **Initialize WS-TOTAL-AMT to zero per cardholder**: 4000-TRNXFILE-GET at `[app/cbl/CBSTM03A.CBL:L422]` initializes WS-TOTAL-AMT to zero before iterating that cardholder's transactions; 4000-TRNXFILE-GET at `[app/cbl/CBSTM03A.CBL:L432]` re-initializes WS-TOTAL-AMT after writing the per-cardholder total. The Java translation MUST reset the BigDecimal accumulator per cardholder; accumulating across multiple cardholders is a parity break. PRESERVED per AAP §0.7.1.
14. **Customer name composition order**: 5000-CREATE-STATEMENT at `[app/cbl/CBSTM03A.CBL:L462-L469]` builds ST-NAME via STRING with the order `CUST-FIRST-NAME -> SPACE -> CUST-MIDDLE-NAME -> SPACE -> CUST-LAST-NAME`, each component DELIMITED BY SPACES (so trailing spaces are trimmed before concatenation). The Java translation MUST preserve this exact composition order and trim semantics. PRESERVED per AAP §0.7.1.
15. **Three-line address composition**: 5000-CREATE-STATEMENT at `[app/cbl/CBSTM03A.CBL:L470-L482]` populates ST-ADD1 from CUST-ADDR-LINE-1, ST-ADD2 from CUST-ADDR-LINE-2, and ST-ADD3 via a STRING composition of CUST-ADDR-LINE-3 + CUST-ADDR-STATE-CD + CUST-ADDR-COUNTRY-CD + CUST-ADDR-ZIP. The Java translation MUST preserve the exact field order and delimiter semantics. PRESERVED per AAP §0.7.1.

NONE of these idiosyncrasies are bugs to be "fixed". They are observable behaviors that downstream consumers may depend on. Per AAP §0.7.1 Minimal Change Clause, ALL of these are preserved verbatim. The complete list above is exhaustive; any future idiosyncrasy discovered during translation MUST be added to this list and to `java/MIGRATION_NOTES.md` §1.6.

## Phase 10: Java Mapping Invariants

- **One class per COBOL PROGRAM-ID**: `CBSTM03A` -> `CbStm03A` in `com.blitzy.carddemo.application.statement` per AAP §0.4.1; `CBSTM03B` -> `CbStm03B` utility class in the same package per AAP §0.4.1.
- **`@CobolProgram("CBSTM03A")` annotation** on the `CbStm03A` class citing the original PROGRAM-ID, the source path `app/cbl/CBSTM03A.CBL`, and the translation date per AAP §0.7.1.
- **`@CobolParagraph` annotations** on each private method translated from a COBOL paragraph (e.g., `@CobolParagraph("5000-CREATE-STATEMENT")` on the method translating 5000-CREATE-STATEMENT).
- **Constructor injection** of repository ports (no application-framework container, no service locator): `TransactionRepository` (TRNXFILE), `CardXrefRepository` (XREFFILE), `CustomerRepository` (CUSTFILE), `AccountRepository` (ACCTFILE), plus output writers for STMTFILE and HTMLFILE; the ports are defined in `com.blitzy.carddemo.domain.port` per AAP §0.3.2 and AAP §0.3.6.
- **`BigDecimal` with `MathContext.DECIMAL128` and scale 2** for all monetary values: TRNX-AMT, WS-TOTAL-AMT, ACCT-CURR-BAL per AAP §0.6.1. Arithmetic is centralized in `com.blitzy.carddemo.domain.util.Decimals`.
- **`String` for 26-byte timestamps**: TRNX-ORIG-TS, TRNX-PROC-TS are stored as `String` to preserve the substring(1:10) semantics needed by the STMTFILE record format; `java.time.LocalDateTime` parsing via `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")` is used only where date arithmetic is required, per AAP §0.6.4.
- **Forbidden legacy date types** — pre-Java-8 mutable date/calendar types and pre-Java-8 thread-unsafe formatter types are NOT used per AAP §0.6.4; only the modern immutable date-and-time API is used.
- **Forbidden file API** — pre-NIO file-handle types are NOT used; all file I/O goes through `java.nio.file.Path`, `Files.newByteChannel`, and `SeekableByteChannel` per AAP §0.6.5.
- **`ScopedValue` instead of `ThreadLocal`** per AAP §0.6.6. CBSTM03A is sequential single-threaded; there is no virtual-thread fan-out because XREF input order determines statement output order and reordering breaks parity per AAP §0.6.6.
- **EBCDIC IBM-1047 default codepage** for production file I/O per AAP §0.6.5; per-file override is supplied via `application.properties` keys (e.g., `carddemo.file.stmtfile.charset`). The ASCII fixtures use `Charset.forName("US-ASCII")` for test runs.
- **Sealed-type pattern** (per AAP §0.6.2) for: a 5-permit `FileDispatchState` (TrnxFile, XrefFile, CustFile, AcctFile, ReadTrnx — modeling WS-FL-DD values from `[app/cbl/CBSTM03A.CBL:L67,L298-L314]`); a 4-permit `Cbstm03bResult` (Ok, Warning, Eof, Error — modeling WS-M03B-RC values `'00'`, `'04'`, `'10'`, OTHER); a 6-permit `Cbstm03bOperation` (Open, Close, Read, ReadK, Write, Rewrite — modeling WS-M03B-OPER 88-level conditions at `[app/cbl/CBSTM03A.CBL:L74-L79]`).
- **Pattern-matching `switch`** for case discrimination (e.g., WS-M03B-RC dispatch). NO `default` branch is permitted per AAP §0.7.4; exhaustiveness is enforced by the Java compiler against the sealed permits list.
- **Records, not POJOs**: `TrnxRecord` (from COSTM01), `CardXrefRecord` (from CVACT03Y), `CustomerLegacyRecord` (from CUSTREC, field-aliased as needed), and `AccountRecord` (from CVACT01Y) are ALL declared as Java `record` per AAP §0.3.2.
- **`parse(byte[])` and `encode()`** static factory and instance method pair on each record per AAP §0.3.2 byte-level contract; the round-trip invariant is `parse(buf).encode()` equals the original `buf` for every valid input.
- **No application framework** — no service container; the composition root in `CreateStatementsApp` wires the chosen adapters into the `CbStm03A` use case at startup per AAP §0.3.6.
- **Sequential execution mandated** — no virtual threads in this program per AAP §0.6.6 (XREF input order determines output order).
- **PIC 9(9).99- / PIC Z(9).99- trailing-sign formatting**: ST-CURR-BAL, ST-TRANAMT, and ST-TOTAL-TRAMT use COBOL trailing-sign PIC patterns at `[app/cbl/CBSTM03A.CBL:L113,L137,L142]`. The Java implementation MUST emit the same byte sequence — for example, a positive value 1234.56 renders with the trailing-sign placeholder occupied by a space (and a negative value renders with the trailing-sign placeholder occupied by `-`). The `Decimals.encodeTrailingSign(...)` utility centralizes this per AAP §0.6.1.
- **STRING with DELIMITED BY '*' idiom**: the COBOL idiom at `[app/cbl/CBSTM03A.CBL:L562-L592,L614-L632,L687-L715]` uses `DELIMITED BY '*'` as a never-matching delimiter (so the entire source is copied) interleaved with literal segments. The Java translation MUST preserve the resulting byte sequence and trailing padding exactly.
- **JVM flags** documented at the test runner level: `-XX:+UseCompactObjectHeaders` (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) per AAP §0.3.4.
- **No preview-features flag** — the `--enable-preview` JVM flag is FORBIDDEN per AAP §0.7.4.
- **PAN masking in production logs**: the production logger applies masking through a separate sink per AAP §0.7.2; the test driver uses an unmasked sink for byte-for-byte parity verification (see Phase 12 PAN-masking dichotomy).
- **CBSTM03B return code String preservation**: the COBOL WS-M03B-RC field is declared `PIC X(02)` at `[app/cbl/CBSTM03A.CBL:L72]` and carries the textual codes `'00'`, `'04'`, `'10'`, etc. The Java translation MUST preserve these codes as 2-character String values when interfacing with `CbStm03B` — NOT as `int` or `enum ordinal()` — because byte-level equivalence in any future trace-log captures depends on the textual representation.
- **WS-M03B-FILE-NAME mapping**: the COBOL WS-M03B-FILE-NAME field at `[app/cbl/CBSTM03A.CBL:L70]` is a `PIC X(08)` identifier passed to CBSTM03B to select which DD to operate on. Permissible values match the DD names: `'TRNXFILE'`, `'XREFFILE'`, `'CUSTFILE'`, `'ACCTFILE'`. The Java `CbStm03B` collaborator MUST accept these exact 8-character String values (or a sealed `Cbstm03bFile` enum-like hierarchy mapping to them) and dispatch to the corresponding adapter port.
- **WS-M03B-OPER 88-level mapping**: WS-M03B-OPER at `[app/cbl/CBSTM03A.CBL:L74-L79]` exposes 88-level conditions `M03B-OPEN`, `M03B-CLOSE`, `M03B-READ`, `M03B-READ-K`, `M03B-WRITE`, `M03B-REWRITE`. Only OPEN, READ, READ-K, and CLOSE are exercised by CBSTM03A (WRITE and REWRITE are unreachable from this caller). The Java `Cbstm03bOperation` sealed hierarchy includes all six permits for completeness even though two are unused — this mirrors the COBOL 88-level structure and avoids surfacing a divergence between the type model and the source.
- **Key-write semantics for keyed reads**: 2000-CUSTFILE-GET at `[app/cbl/CBSTM03A.CBL:L370-L373]` MOVES the XREF-CUST-ID into the CBSTM03B key buffer before invoking the keyed read; 3000-ACCTFILE-GET at `[app/cbl/CBSTM03A.CBL:L394-L397]` MOVES XREF-ACCT-ID. The Java translation MUST set the key buffer (or pass the key as a typed parameter) before each keyed read; reusing a stale key from a prior read is a defect.
- **OPEN OUTPUT sequence for STMTFILE / HTMLFILE**: `[app/cbl/CBSTM03A.CBL:L293]` opens both output files together with `OPEN OUTPUT STMT-FILE HTML-FILE`. The Java translation opens both writers (or both channels) at the start of the run; closing happens together at `[app/cbl/CBSTM03A.CBL:L339]` with `CLOSE STMT-FILE HTML-FILE`. The Java translation MUST close both writers in the same finalization step (using a try-with-resources block on a composite holder, or two `close()` calls inside a `finally` block).
- **No SYNCPOINT, no rollback, no compensating-write logic**: unlike COACTUPC (AAP §0.4.1), CBSTM03A has no transactional rollback. All writes to STMTFILE and HTMLFILE are append-only and forward-only. The Java translation does NOT need transactional boundaries; a simple sequential writer is sufficient.
- **`CALL 'CEE3ABD'` translates to a typed exception**: 9999-ABEND-PROGRAM at `[app/cbl/CBSTM03A.CBL:L921-L923]` performs `DISPLAY 'ABENDING PROGRAM'` followed by `CALL 'CEE3ABD'` to invoke the Language Environment abend service. The Java translation throws a typed `CardDemoAbendException` (or equivalent unchecked exception in the carddemo exception hierarchy); the calling test asserts that the abend exception is thrown along the expected error path. PRESERVED per AAP §0.7.1.
- **Implicit MAIN program contract**: CBSTM03A has no LINKAGE SECTION USING clause (it is a JCL-invoked main program, not a callable subroutine). The Java translation provides a `main(String[] args)` entry point in `CreateStatementsApp` (per AAP §0.4.1 JCL-to-App mapping) that constructs the `CbStm03A` collaborator with file-backed adapters and invokes the use-case method; `CbStm03A` itself has no `main` and is not directly invoked from the command line.
- **Module Import Declarations** (JEP 511, finalized in Java 25 per AAP §0.7.3): files in this fixture's surrounding Java tree MAY begin with `import module java.base;` to reduce import boilerplate when many `java.*` packages are touched. This is permitted but not mandatory.
- **Flexible Constructor Bodies** (JEP 513, finalized in Java 25 per AAP §0.7.3): records modeling COBOL group items MAY use compact canonical constructors with validation logic preceding canonical field bindings. For CBSTM03A this applies to `TrnxRecord`, `CardXrefRecord`, `CustomerLegacyRecord`, and `AccountRecord` parse-time validators.
- **No null permitted for record component values**: all Java records modeling COBOL group items use defensive copies where applicable (e.g., `List.copyOf(...)` for any list components) and reject null component values via compact canonical constructor validation; COBOL has no null concept, so the Java translation MUST NOT introduce null values for fields that COBOL would have populated.
- **String length invariants on captured field values**: each PIC X(n) field maps to a String whose `length()` MUST equal `n`. The parser pads with trailing spaces if the input is shorter and truncates to `n` if the input is longer. The Java translation MUST enforce these length invariants in the record's compact canonical constructor.
- **Long type for ACCT-ID, MERCHANT-ID, CUST-ID**: PIC 9(11) (ACCT-ID) maps to `long` (within the 19-digit `long` range); PIC 9(09) (CUST-ID, TRNX-MERCHANT-ID) also maps to `long` to keep the value-class signed-integer uniform. Negative values are not expected for these identity fields, but the Java translation does not reject negatives — the compact canonical constructor merely validates the digit count.
- **`int` type for COMP counters**: CR-CNT, TR-CNT, CR-JMP, TR-JMP all declared PIC S9(4) COMP per `[app/cbl/CBSTM03A.CBL:L60-L63]`. The Java translation uses `int` (32-bit signed). The COBOL declared maximum is 9999 (4 digits); Java's `int` covers a much wider range, but the Java translation MUST treat any value > 9999 as a defect and MAY assert this invariant in a `Decimals.requireDigitCount(...)` helper.
- **`int` for END-OF-FILE flag**: END-OF-FILE PIC X(01) VALUE 'N' at `[app/cbl/CBSTM03A.CBL:L57]` is a single-character flag with values `'N'` or `'Y'`. The Java translation MAY model this as a `boolean` (with the COBOL semantic `'N'` -> `false`, `'Y'` -> `true`) or as a sealed `EndOfFile` hierarchy. The choice does not affect output bytes; the boolean is simpler.
- **`String` for the LINKAGE / file-control DD-name field**: the WS-FL-DD PIC X(08) at `[app/cbl/CBSTM03A.CBL:L67]` carries an 8-character DD identifier. The Java translation models this as a sealed `FileDispatchState` hierarchy (5 permits: TrnxFile, XrefFile, CustFile, AcctFile, ReadTrnx); the conversion between String and sealed permit happens at the boundary with CBSTM03B.
- **CardDemo-specific exception hierarchy**: error paths (CBSTM03B return code OTHER) translate to typed checked or unchecked exceptions in `com.blitzy.carddemo.application.exception` — typical permits include `CardDemoFileOpenException`, `CardDemoFileReadException`, `CardDemoFileCloseException`, `CardDemoAbendException`. The exact hierarchy is documented in the application module's package-info; this README does not mandate a specific structure beyond "must be typed and must preserve the COBOL observable behavior".
- **Repository ports defined in `carddemo-domain.port`** per AAP §0.3.2: the `CbStm03A` collaborator depends on `TransactionRepository`, `CardXrefRepository`, `CustomerRepository`, and `AccountRepository` interfaces. The file-backed implementations live in `carddemo-adapter-file`; the optional JDBC implementations live in `carddemo-adapter-db` (empty by default per AAP §0.3.6). The composition root in `CreateStatementsApp` wires the file-backed adapters by default; the test driver wires the same file-backed adapters with classpath-resolved test fixtures.


## Phase 11: Test Class Override Map

The companion test class `com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest` extends `GoldenRecordTest` and provides the canonical override methods listed below. The class is annotated `@Disabled` per AAP §0.6.11 until the gating conditions (Phase 12) are satisfied. The override methods establish the program-under-test class, the synthesized TRNXFILE input, the three auxiliary ASCII input fixtures resolved from `app/data/ASCII/`, and the three expected output files resolved from this folder. The `auxiliaryInputs()` override differs from the canonical `GoldenRecordTest.inputFile()` shape because CBSTM03A consumes FOUR input streams (the primary TRNXFILE plus three auxiliary file inputs); the test base class supports this via a `Map<String, Path>` of DD-name to fixture path. The `expectedOutputs()` override likewise differs from the canonical single `expectedOutputFile()` shape because CBSTM03A produces THREE output streams; the test base class supports this via a `List<ExpectedOutput>` of (logical-name, expected-path) tuples and asserts each pair independently.

```java
// com.blitzy.carddemo.tests.golden.CbStm03AGoldenTest
@Disabled("Awaiting COBOL capture per MIGRATION_NOTES.md section 1.6; DEVIATION documentation pending")
public class CbStm03AGoldenTest extends GoldenRecordTest {

    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.statement.CbStm03A.class;
    }

    @Override
    protected Path inputFile() {
        // Synthesized TRNXFILE produced by SORT step (STEP010) of CREASTMT.JCL
        return resolveSynthesizedTrnxFile();
    }

    @Override
    protected Map<String, Path> auxiliaryInputs() {
        return Map.of(
            "XREFFILE", resolveAsciiFixture("cardxref.txt"),
            "CUSTFILE", resolveAsciiFixture("custdata.txt"),
            "ACCTFILE", resolveAsciiFixture("acctdata.txt")
        );
    }

    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath("cbstm03a", "stmt_text.txt");
    }

    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput("stmt_text.txt", resolveExpectedOutputPath("cbstm03a", "stmt_text.txt")),
            new ExpectedOutput("stmt_html.html", resolveExpectedOutputPath("cbstm03a", "stmt_html.html")),
            new ExpectedOutput("stdout.txt", resolveExpectedOutputPath("cbstm03a", "stdout.txt"))
        );
    }
}
```

The `@Disabled` verification checklist (the 12 points an enabled test MUST satisfy before the `@Disabled` annotation is removed):

1. Classpath fixture resolver returns non-null for each of the 3 ASCII inputs (`cardxref.txt`, `custdata.txt`, `acctdata.txt`) and the synthesized TRNXFILE.
2. Expected `stmt_text.txt`, `stmt_html.html`, `stdout.txt` all exist and are non-empty (captured, not placeholder).
3. Expected outputs are committed via the capture procedure in `java/MIGRATION_NOTES.md` §1.6 — NOT hand-edited.
4. The Clock (if injected for any timestamp generation) is fixed via `ScopedValue<Clock>` to a known instant matching the COBOL capture run.
5. Input ASCII fixtures remain BYTE-IDENTICAL after the test run (the test harness MUST NOT mutate the source files per AAP §0.2.2).
6. No production dependency references the standard-output PrintStream for production logging (DISPLAY translations use SLF4J in production; the test driver uses an unmasked test-only sink for byte-for-byte parity).
7. The `Decimals` utility is fully tested at 100% line coverage per AAP §0.6.1.
8. `@CobolProgram("CBSTM03A")` annotation is present on `CbStm03A` class.
9. `@CobolParagraph` annotations are present on each translated paragraph method.
10. DEVIATION items documented in `java/MIGRATION_NOTES.md` §1.6.
11. No `--enable-preview` in the test runner's JVM args; no preview JEP 502 / 505 / 507 / 512 usage in production code per AAP §0.7.4.
12. The `-XX:+UseCompactObjectHeaders` flag (JEP 519) and `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) are documented in the test runner's JVM args per AAP §0.3.4.

Notes on selected verification points:

- Point 3 (capture provenance): the `expected/` files MUST originate from a deterministic COBOL run that itself reads the unmodified `app/data/ASCII/*.txt` fixtures. Any deviation from the documented capture command shape (e.g., manipulating the input fixtures before passing them to the COBOL runtime) breaks the parity contract. The capture script in `java/MIGRATION_NOTES.md` §1.6 is the canonical reference.
- Point 4 (clock determinism): CBSTM03A does not directly query the system clock for its own output; however, the captured TRNX-ORIG-TS / TRNX-PROC-TS values inside the input TRNXFILE are echoed into the statement output via STRING composition in 6000-WRITE-TRANS at `[app/cbl/CBSTM03A.CBL:L687-L715]`. The Java translation simply passes the captured timestamps through; the test does NOT need a fixed Clock unless future code paths introduce one.
- Point 6 (production logging sink): the COBOL DISPLAY statements emit to SYSOUT in production. In the Java translation, the production sink is SLF4J at INFO level for the informational TIOT-equivalent messages and ERROR level for the explicit error-path messages; the test-only sink captures the same bytes (unmasked) for parity assertion. The test-only sink does NOT apply the PAN-masking sink filter.
- Point 11 (preview features): the `--enable-preview` flag is FORBIDDEN per AAP §0.7.4. The forbidden preview JEPs are 502 (Stable Values), 505 (Structured Concurrency), 507 (Primitive Patterns in switch), and 512 (Compact Source Files / Instance Main Methods in production code). The finalized features in Java 25 — JEP 506 (Scoped Values), JEP 510 (Key Derivation Function API; conditional), JEP 511 (Module Import Declarations), JEP 513 (Flexible Constructor Bodies), JEP 519 (Compact Object Headers), and JEP 521 (Generational Shenandoah GC) — ARE permitted and in some cases mandated per AAP §0.7.3.
- Point 12 (JVM flags): the JVM flags are documentation-level rather than enforced at the test level — the test runs successfully without them, but production batch runs SHOULD use them for the memory and pause-time advantages they bring to high-volume statement-generation workloads. The expected outputs are NOT a function of the JVM flag choice.

**Test execution sequence**: when `CbStm03AGoldenTest.byteForByteParity()` is enabled and runs, it: (a) resolves the four input fixture paths via `inputFile()` and `auxiliaryInputs()`, (b) opens the program-under-test class via `programClass()` and constructs an instance with file-backed adapters wired to those paths, (c) invokes the use-case method on `CbStm03A` (typically a `generateStatements()` or `run(BatchRunContext)` entry point), (d) collects the actual outputs into a temporary work directory under `/tmp/carddemo-tests/cbstm03a/run-<uuid>/`, (e) compares each actual output byte-for-byte against the corresponding expected output via `Arrays.equals(...)` or an AssertJ chain, (f) reports a diff in case of failure pointing to the byte offset of first divergence, and (g) cleans up the temporary work directory after assertions complete (regardless of pass / fail). The test does NOT mutate any file under the `expected/` folder; the test does NOT mutate any file under `app/data/ASCII/`; the test consumes the read-only inputs and writes only to its work directory.

**Failure diagnostics**: when byte-for-byte parity fails, the `GoldenRecordTest` base class prints a diagnostic containing: the logical output name (e.g., `stmt_html.html`), the byte offset of first divergence (e.g., `byte 1247`), the expected byte (e.g., `0x20`), the actual byte (e.g., `0x09`), a 32-byte context window around the divergence point (with non-printable bytes rendered as `\xNN`), and a suggested next action (such as "review the COBOL paragraph that emits this record" or "re-capture the fixture per MIGRATION_NOTES.md section 1.6"). This makes parity failures actionable without requiring the developer to hex-edit the captured files.

**Common diagnostic patterns**: a divergence at byte offset N where `N % 80 == 0` (for STMTFILE) or `N % 100 == 0` (for HTMLFILE) typically indicates a record-count mismatch or an inter-record-boundary issue. A divergence at a non-multiple offset typically indicates a content issue within a record — wrong byte, wrong padding, wrong sign, wrong case. A divergence at offset 0 typically indicates that the captured file is a placeholder (and the test should be `@Disabled` until a real capture is committed). A divergence at the last byte of the file typically indicates a missing or extra trailing record (e.g., the closing `</html>` line is missing or duplicated).

## Phase 12: Capture Procedure Cross-Reference

- See `java/MIGRATION_NOTES.md` §1.6 for the canonical COBOL build/run path used to capture this fixture's expected files. The capture procedure exists because the user prompt originally left this as a TODO marker per AAP §0.7.5.
- **Determinism**: the input ASCII fixtures are read-only references; they are not mutated; the SORT step is deterministic given identical input bytes.
- **Capture command shape** (illustrative; the canonical path is documented in `java/MIGRATION_NOTES.md` §1.6): compile CBSTM03A and CBSTM03B with GnuCOBOL or z/OS COBOL -> execute STEP010 SORT and STEP020 IDCAMS REPRO to produce TRNXFILE -> execute STEP040 EXEC PGM=CBSTM03A with DD assignments pointing to the ASCII fixtures (read-only) and the synthesized TRNXFILE -> capture STMTFILE to `stmt_text.txt`, HTMLFILE to `stmt_html.html`, SYSOUT to `stdout.txt`.
- **TIOT inspection DEVIATION impact on stdout.txt parity assertion**: the captured `stdout.txt` will contain z/OS-specific TIOT entries that are NOT byte-reproducible in a Java environment. The Java translation MUST emit informational equivalent output (e.g., DD names from `application.properties`); the parity assertion for `stdout.txt` MUST be relaxed to a structural assertion (substring presence, line count) rather than byte-exact equality for the TIOT section. The byte-exact assertions for `stmt_text.txt` and `stmt_html.html` remain in full force.
- **PAN-masking dichotomy**:
  - COBOL CBSTM03A does NOT explicitly DISPLAY DALYTRAN-RECORD or TRNX-RECORD contents.
  - Captured `stmt_text.txt` and `stmt_html.html` DO contain TRNX-CARD-NUM (PIC X(16)) and ST-ACCT-ID (PIC X(20)) — the statement output contains a full account ID and the transaction-detail rows reference the card.
  - Per AAP §0.7.2 production Java logs MUST mask all but the last 4 digits; the captured `stmt_text.txt` and `stmt_html.html` fixtures preserve COBOL behavior unmasked for parity assertion via a test-only sink.
  - The test driver uses an unmasked sink (test-only); the production logger applies masking via a separate sink.
  - The captured fixture lives only in the test resources tree; it is NOT part of any production deployment artifact.
  - The fixture is committed to a private repository whose access is governed by the team's source-control policies; access to the captured cardholder-data bytes follows the same access policy as access to the rest of the test fixtures.
  - The cardholder data values embedded in the captured outputs are themselves derived from the synthetic test fixtures under `app/data/ASCII/` — they are NOT real cardholders; the fixtures use synthetic construct-test values designed to exercise edge cases, not real-issued account or card identifiers.
  - The cardholder data values embedded in `stmt_text.txt` and `stmt_html.html` are bytes-identical copies of the fields from the synthetic CUSTFILE, XREFFILE, ACCTFILE, and TRNXFILE inputs; the Java translation does not introduce any new cardholder data values, it merely passes through what the COBOL inputs contained.
- **Until capture is performed**: `CbStm03AGoldenTest` is `@Disabled("Awaiting COBOL capture per MIGRATION_NOTES.md section 1.6; DEVIATION documentation pending")`; the parity assertion is unreachable.
- **Re-capture trigger**: if the ASCII fixtures change OR the COBOL source changes, this fixture MUST be re-captured. Do NOT ad-hoc edit any of the three output files; always re-run the capture procedure documented in `java/MIGRATION_NOTES.md` §1.6.
- **Re-capture audit trail**: every capture run SHOULD record the COBOL toolchain version, the OS / kernel version, the timestamp of the run, the input fixture SHA-256 digests, and the output SHA-256 digests in `java/MIGRATION_NOTES.md` §1.6 (or in a sibling `capture_log.txt` file under this folder). The Java test harness MAY verify the input fixture SHA-256 at test-time and fail-fast if a fixture has been mutated since the last capture; this is a defense in depth against silent corruption.
- **Capture-time COBOL compile flags**: the COBOL toolchain (GnuCOBOL or z/OS COBOL) MUST be invoked with arithmetic-mode settings that match the Java `BigDecimal` semantics — specifically, `ARITH(EXTEND)` on z/OS COBOL (31-digit intermediate precision) or `-frelax-syntax` plus an arithmetic-precision setting on GnuCOBOL. Mismatched arithmetic precision between COBOL and Java will surface as a parity break in the per-cardholder Total EXP line. The `java/MIGRATION_NOTES.md` §1.6 capture procedure documents the exact flags.
- **Capture-time codepage**: when running on z/OS the captured outputs are EBCDIC IBM-1047 by default; when running on GnuCOBOL on Linux the captured outputs are ASCII by default. The Java test harness assumes ASCII-encoded fixtures; the EBCDIC->ASCII transcoding must happen at capture time (e.g., via `iconv -f IBM-1047 -t US-ASCII`) and the transcoded bytes are what get committed to `expected/`. Mismatched codepages between capture-time and test-time is the #1 source of false parity failures.
- **Capture-time numeric encoding**: COBOL PIC S9(n)V99 COMP-3 fields are packed-decimal in z/OS COBOL output; the Java test harness expects edited / displayable output for the monetary fields in `stmt_text.txt` and `stmt_html.html` (e.g., `0001234.56 ` for a positive value or `0001234.56-` for negative). Captures performed by emitting COMP-3 raw bytes are NOT comparable to the Java output; the COBOL emitter for STMTFILE / HTMLFILE MUST use the display edit-mask PIC clauses on the ST-* / HTML-* output fields (PIC 9(9).99-, PIC Z(9).99-, etc.) per the existing source `[app/cbl/CBSTM03A.CBL:L113,L137,L142]`, which it does — verify this at capture time by inspecting a few bytes of the captured `stmt_text.txt`.
- **Capture validation step**: after a capture run, the operator SHOULD verify the captured files via `wc -c stmt_text.txt` (expect a multiple of 80), `wc -c stmt_html.html` (expect a multiple of 100), and `file stmt_text.txt stmt_html.html stdout.txt` (expect `ASCII text` or `UTF-8 Unicode text` without `with CRLF`). Any deviation from these invariants indicates a malformed capture; re-run the capture procedure before committing.

## Phase 13: Scenario Inventory

1. **Single cardholder, single transaction** — XREFFILE=1 entry, CUSTFILE keyed read=1, ACCTFILE keyed read=1, TRNXFILE=1 entry; minimal `stmt_text.txt` (one statement with one detail row); minimal `stmt_html.html` (one HTML statement block).
2. **Single cardholder, multiple transactions (less than or equal to 10)** — XREFFILE=1, TRNXFILE=N where 1 < N <= 10; one statement with N detail rows; exercises the WS-TRAN-TBL inner loop in 4000-TRNXFILE-GET.
3. **Single cardholder, transactions at table cap (10)** — XREFFILE=1, TRNXFILE=10; exercises the TR-CNT boundary at `[app/cbl/CBSTM03A.CBL:L228]` (OCCURS 10 TIMES); one statement with 10 detail rows.
4. **Multiple cardholders (less than or equal to 51)** — XREFFILE=M where 1 < M <= 51, CUSTFILE=M, ACCTFILE=M; M statements concatenated in `stmt_text.txt`; M HTML statement blocks concatenated in `stmt_html.html`.
5. **Cardholders at table cap (51 cards)** — XREFFILE=51; exercises the CR-CNT boundary at `[app/cbl/CBSTM03A.CBL:L226]` (OCCURS 51 TIMES); 51 statements.
6. **Zero transactions for a cardholder** — XREFFILE=1, TRNXFILE=0 matching that card; verifies handling when 4000-TRNXFILE-GET finds no matching card; the statement contains an empty transaction section with `Total EXP:` total of zero and the end banner.
7. **TRNXFILE EOF on initial open (WS-M03B-RC='10')** — TRNXFILE=0 entries; END-OF-FILE='Y' is set immediately during the initial read at `[app/cbl/CBSTM03A.CBL:L745-L754]`; no statements are produced; `stmt_text.txt` and `stmt_html.html` contain only header/footer template bytes (if any).
8. **CBSTM03B return code `'04'` on OPEN (warning-acceptable per `[app/cbl/CBSTM03A.CBL:L736,L748,L771,L789,L807]`)** — processing proceeds normally because both `'00'` and `'04'` are treated as success per AAP §0.7.1; this validates the sealed `Cbstm03bResult` hierarchy's Ok/Warning discrimination.
9. **Card-grouping with multiple cards interleaved in TRNXFILE input pre-SORT** — TRANSACT input contains transactions for multiple cards out of card-order; STEP010 SORT at `[app/jcl/CREASTMT.JCL:L53]` re-sorts them into contiguous per-card groups; verifies the SORT step preserves the grouping invariant enumerated in Phase 9 item 11.
10. **Negative monetary balance and amount** — CUSTFILE / ACCTFILE contains a customer whose ACCT-CURR-BAL is negative; TRNXFILE contains a transaction with negative TRNX-AMT (refund). Verifies that ST-LINE8 emits with PIC 9(9).99- trailing `-` placeholder, that ST-LINE14 emits a negative ST-TRANAMT with PIC Z(9).99- trailing `-`, and that ST-LINE14A accumulates correctly across positive and negative amounts; the BigDecimal scale 2 arithmetic in `Decimals.add(...)` MUST produce the identical accumulated total.
11. **Long customer name exceeding 75 chars** — verifies that ST-NAME truncation behavior matches COBOL STRING-with-overflow semantics; the STRING statement in 5000-CREATE-STATEMENT at `[app/cbl/CBSTM03A.CBL:L462-L469]` silently truncates if the composed name exceeds 75 chars. The Java translation MUST emit the same 75-char prefix without throwing.
12. **CBSTM03B return code OTHER (e.g., `'23'` for record-not-found on a keyed read)** — verifies the abend path: an unexpected return code from 2000-CUSTFILE-GET, 3000-ACCTFILE-GET, or 1000-XREFFILE-GET-NEXT triggers the 9999-ABEND-PROGRAM paragraph, which emits `` `'ABENDING PROGRAM'` `` to `stdout.txt` and calls `CEE3ABD`. The Java translation throws a typed abend exception with the same observable side-effects.

Each scenario MUST be reproducible via a deterministic input fixture (and a deterministic synthesized TRNXFILE if applicable). The COBOL capture procedure documented in `java/MIGRATION_NOTES.md` §1.6 produces the expected outputs for the canonical happy-path scenario; additional scenarios may require additional capture runs documented per-scenario. The minimum mandatory coverage is scenarios 1, 2, 4, 6, and 8 from the list above — these collectively exercise: single-card single-tran (1), single-card multi-tran (2), multi-card (4), zero-transactions for a cardholder (6), and warning return-code on OPEN (8). Scenarios 3, 5, 7, 9, 10, 11, 12 are HIGHLY RECOMMENDED for hardening the parity contract under boundary conditions and error paths.

**Scenario coverage rationale**: the 12 scenarios above collectively exercise every reachable execution path through CBSTM03A's PROCEDURE DIVISION. Scenarios 1-2 exercise the simplest happy path (header generation, single-card statement, transaction iteration, footer generation). Scenarios 3 and 5 exercise the OCCURS-clause boundaries (the program does not gracefully handle exceeding the boundaries, so the test does not exercise overflow — overflow is undefined behavior in COBOL). Scenario 4 exercises the multi-card iteration of 1000-MAINLINE and the per-cardholder reset of WS-TOTAL-AMT. Scenario 6 exercises the no-match branch in 4000-TRNXFILE-GET, validating the empty-transaction-section emission. Scenario 7 exercises the early-EOF path through 8500-READTRNX-READ. Scenario 8 exercises the warning-return-code acceptance logic in the OPEN paragraphs. Scenarios 9-11 exercise data-shape variability: card-grouping order, sign handling, and string truncation. Scenario 12 exercises the abend path. The Java implementation passes ONLY when every scenario produces byte-identical output (with the TIOT section of `stdout.txt` relaxed structurally per Phase 12). The test harness MAY run any subset of these scenarios, but PR review SHOULD verify that the minimum mandatory subset (1, 2, 4, 6, 8) was exercised.

**Code-path coverage**: scenario 1 covers paragraphs 0000-START, 1000-MAINLINE, 1000-XREFFILE-GET-NEXT (RC=`'00'` once + RC=`'10'` at EOF), 2000-CUSTFILE-GET (RC=`'00'`), 3000-ACCTFILE-GET (RC=`'00'`), 4000-TRNXFILE-GET (found, single transaction), 5000-CREATE-STATEMENT, 5100-WRITE-HTML-HEADER, 5200-WRITE-HTML-NMADBS, 6000-WRITE-TRANS (once), 8100-FILE-OPEN, 8100-TRNXFILE-OPEN, 8200-XREFFILE-OPEN, 8300-CUSTFILE-OPEN, 8400-ACCTFILE-OPEN, 8500-READTRNX-READ (RC=`'00'` once + RC=`'10'`), 8599-EXIT, 9100-TRNXFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CUSTFILE-CLOSE, 9400-ACCTFILE-CLOSE, 9999-GOBACK — i.e., scenario 1 alone exercises every non-error paragraph in the program. The Java translation MUST translate every one of these paragraphs and the test MUST exercise every translated paragraph at least once via scenario 1; the remaining scenarios harden specific corner cases.

**Coverage of error-path paragraphs**: 9999-ABEND-PROGRAM is exercised by scenarios 12 (explicit RC=OTHER scenario) and indirectly by failure injection in scenarios 7 (premature EOF), 8 (with a hypothetical RC=`'04'` upgraded to a forced error condition for the abend-injection variant). The 13 distinct CBSTM03B call sites in CBSTM03A and the 16+ distinct DISPLAY error messages catalogued in Phase 5 each correspond to a potential abend trigger; the test harness exercises representative samples rather than every single trigger to keep the test suite tractable. PR review SHOULD verify that the abend-injection variant scenarios run successfully and that the abend exception is the expected typed exception with the expected error message content.

Scenarios are independent and idempotent: running scenarios in any order produces the same result for each scenario; no scenario mutates shared state that another scenario observes. The `expected/` files committed to this folder cover only the canonical happy-path scenario (scenario 1 or 2). Additional captured fixtures for scenarios 3-12 SHOULD be committed under sibling folders such as `expected/scenario_4_multi_cardholder/` once the corresponding captures are performed; this README's contract scope is limited to the canonical happy-path capture.


## Contrast Matrix — CBSTM03A vs. sibling programs

| Aspect | CBSTM03A (this) | CBSTM03B | CBTRN02C | CBTRN03C |
|---|---|---|---|---|
| Program-ID prefix | CB (batch) | CB (batch subroutine) | CB (batch) | CB (batch) |
| Role | STATEMENT GENERATOR | File-services SUBROUTINE | Full posting engine | Paginated report writer |
| Source line count | 924 lines | 230 lines | varies | varies |
| Output file count | 3 (stmt_text, stmt_html, stdout) | 0 (subroutine) | 5 (transact, acctdata, tcatbal, dalyrejs, stdout) | 2 (reptfile, stdout) |
| HTML output | YES (100-byte FB) | NO | NO | NO |
| Text output (80-byte FB) | YES | NO | NO | NO |
| z/OS-specific control blocks | YES (TIOT/TCB/PSA inspection) **DEVIATION** | NO | NO | NO |
| ALTER + GO TO flow | YES **DEVIATION** | NO | NO | NO |
| 2D in-memory table | YES (WS-TRNX-TABLE 51x10) **DEVIATION** | NO | NO | NO |
| Subroutine dependency | YES (calls CBSTM03B 13 times) | N/A | NO | NO |
| Trailing-sign PIC formats | YES (PIC 9(9).99- and PIC Z(9).99-) | NO | varies | varies |
| Reject mechanism | NO | NO | YES (DALYREJS) | NO |
| Clock dependency | indirect (via captured timestamps) | NO | YES | NO |
| Virtual threads allowed | NO (sequential mandated) | NO | NO | NO |
| Test fixture pattern | input/README + expected/README + 3 placeholder data files | input/README + expected/README + 2 data files | input/README + expected/README + multiple placeholder data files | input/README + expected/README + 2 placeholder data files |
| Parity criticality | DEVIATION-flagged per AAP §0.4.1 | secondary | THE MOST CRITICAL | secondary |

CBSTM03A's HTML output, z/OS control-block inspection, ALTER/GO TO flow, and 2D table buffering are what make it DEVIATION-flagged. The DEVIATION items are documented in `java/MIGRATION_NOTES.md` §1.6 and do NOT introduce behavior changes — they translate z/OS-specific idioms while preserving identical observable output bytes for the STMTFILE and HTMLFILE outputs. The `stdout.txt` parity assertion is relaxed to a structural assertion ONLY for the TIOT inspection section; all other DISPLAY lines (error paths, ABEND) preserve byte-for-byte parity.

Among the 28 COBOL programs translated under this refactor (AAP §0.4.1), CBSTM03A is one of only two DEVIATION-flagged programs (the other being COACTUPC with its `SYNCPOINT ROLLBACK`). All other 26 programs are translated as idiom-for-idiom equivalents that achieve byte-for-byte parity without structural deviation. The DEVIATION flag on CBSTM03A is a contained, well-documented deviation; it does NOT signal that CBSTM03A is "broken" or "experimental" — it signals that the COBOL source uses three z/OS-specific idioms (TIOT inspection, ALTER + GO TO, 2D in-memory table buffering) that have no exact Java analogue and require translation into idiomatic Java constructs with carefully documented parity behavior. Once translation is complete and the captures are in place, CBSTM03A's golden-record test is just as binding a parity gate as any other golden-record test in the harness.

## Verbatim Message Catalog Cross-Reference

The 20 messages enumerated in the Phase 5 table form the COMPLETE catalog for CBSTM03A. Any future cross-reference in `java/MIGRATION_NOTES.md` will record message-string changes, of which NONE are expected per AAP §0.7.1. The messages preserve idiosyncrasies including the L281 / L290 two-space differences and the verbatim TIOT entry format. The Java translation MUST produce byte-identical strings for each catalogued message where the message itself is reproduced; for the TIOT inspection block (entries 1-6) the Java translation produces informational equivalent output per the DEVIATION documented in `java/MIGRATION_NOTES.md` §1.6, and the corresponding `stdout.txt` parity assertion is relaxed structurally per Phase 12.

A note on the verbatim text encoding: the COBOL single-quoted string literals in the CBSTM03A source file use ASCII printable characters only; the translation does NOT introduce typographic substitutions (no curly quotes, no em-dash inside the message body, no Unicode ellipsis). The captured `stdout.txt` is plain ASCII line-based text. The Java translation's SLF4J or test-sink rendering MUST also use plain ASCII for the message body. The COBOL DISPLAY statement appends a JES SYSOUT record terminator after the message; the captured `stdout.txt` file represents each DISPLAY as one line terminated by an LF byte (`0x0A`). The Java translation's test sink MUST emit one LF terminator per logical DISPLAY call to match this layout. The Java implementation's production logger MAY add additional structure (timestamp, thread, level) when routing to SLF4J; the test-only sink is configured to bypass the production formatter and emit the raw message body only.

## Source Lineage

- `app/cbl/CBSTM03A.CBL` (924 lines) — Primary COBOL source; PROGRAM-ID at L2; AUTHOR `AWS` at L3.
- `app/cbl/CBSTM03B.CBL` (230 lines) — Callable file-services subroutine; PROGRAM-ID at L2; AUTHOR `AWS` at L3.
- `app/jcl/CREASTMT.JCL` (97 lines) — JCL driver; STEP040 EXEC PGM=CBSTM03A at L79.
- `app/cpy/COSTM01.CPY` — TRNX-RECORD layout (350 bytes total; TRNX-KEY = TRNX-CARD-NUM PIC X(16) + TRNX-ID PIC X(16) totaling 32-byte key; TRNX-REST = TRNX-TYPE-CD PIC X(02) + TRNX-CAT-CD PIC 9(04) + TRNX-SOURCE PIC X(10) + TRNX-DESC PIC X(100) + TRNX-AMT PIC S9(09)V99 + TRNX-MERCHANT-ID PIC 9(09) + TRNX-MERCHANT-NAME PIC X(50) + TRNX-MERCHANT-CITY PIC X(50) + TRNX-MERCHANT-ZIP PIC X(10) + TRNX-ORIG-TS PIC X(26) + TRNX-PROC-TS PIC X(26) + FILLER PIC X(20)).
- `app/cpy/CVACT01Y.cpy` — ACCOUNT-RECORD layout (300 bytes; ACCT-ID PIC 9(11), ACCT-CURR-BAL among the monetary fields used by CBSTM03A).
- `app/cpy/CVACT03Y.cpy` — CARD-XREF-RECORD layout (50 bytes; XREF-CARD-NUM PIC X(16) key, XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11)).
- `app/cpy/CUSTREC.cpy` — Customer record layout (500 bytes; CUST-ID PIC 9(09) key, CUST-FIRST-NAME / CUST-MIDDLE-NAME / CUST-LAST-NAME, CUST-ADDR-LINE-1/2/3, CUST-ADDR-STATE-CD, CUST-ADDR-COUNTRY-CD, CUST-ADDR-ZIP, CUST-FICO-CREDIT-SCORE among the fields consumed by CBSTM03A).
- `app/data/ASCII/cardxref.txt` — XREFFILE input fixture (REFERENCE; read-only).
- `app/data/ASCII/custdata.txt` — CUSTFILE input fixture (REFERENCE; read-only).
- `app/data/ASCII/acctdata.txt` — ACCTFILE input fixture (REFERENCE; read-only).
- TRNXFILE — Synthesized at runtime by the SORT step (STEP010) from the TRANSACT input per `[app/jcl/CREASTMT.JCL:L44-L62]`; NOT a standalone committed fixture.

All listed source files are REFERENCE-only inputs to the Java translation; they are UNCHANGED per AAP §0.1.1 / §0.2.2 / §0.7.1. The `app/` tree is the immutable reference implementation and the source of golden-record test fixtures. Any change to the `app/` tree invalidates this fixture and the entire CBSTM03A golden-record chain; such a change is outside the scope of this refactor per AAP §0.2.2.

The source files are not just inputs — they are the definitive specification of the byte-level behavior. The COBOL DISPLAY statements are the specification of `stdout.txt` content; the COBOL `WRITE FD-STMTFILE-REC FROM ...` statements are the specification of `stmt_text.txt` content; the COBOL `WRITE FD-HTMLFILE-REC FROM ...` statements are the specification of `stmt_html.html` content. The COBOL FD declarations are the specification of record sizes and counts. The COBOL copybook 01-level group layouts are the specification of input record parsing. The JCL DD declarations are the specification of the file connections at runtime. When a parity failure occurs, the COBOL source files (not this README, not the AAP, not any external documentation) are the authoritative reference for the expected behavior.

The 924-line CBSTM03A program is the largest of the four CB* batch sources translated under this fixture (CBSTM03A 924, CBSTM03B 230, CBTRN02C and CBTRN03C variable). Its complexity is concentrated in three areas: the TIOT inspection prologue (lines 235-291 LINKAGE + procedural code; ~57 lines), the 24-paragraph state-machine dispatcher (lines 296-852; the GO TO + ALTER structure), and the 5100 / 5200 / 6000 HTML-emission paragraphs (lines 506-723; ~218 lines of mostly verbatim HTML literals + STRING composition). The remaining ~412 lines are working-storage declarations, FD declarations, and the input-side paragraphs (1000 / 2000 / 3000 / 4000 / 8500). A successful Java translation MUST preserve the observable bytes of all three output streams while expressing the state machine in idiomatic Java (sealed interfaces, pattern-matching switches, or sequential method invocations) per AAP §0.7.3.

The COBOL source uses 38 distinct DISPLAY emissions (16 distinct messages in the Phase 5 catalog plus 4 additional duplicates for close errors), 13 distinct CALL invocations of CBSTM03B (3 sequential calls inside 1000-XREFFILE-GET-NEXT / 2000-CUSTFILE-GET / 3000-ACCTFILE-GET plus 10 calls across the 8100/8200/8300/8400 OPEN, 8500-READTRNX-READ READ, and 9100/9200/9300/9400 CLOSE paragraphs), 1 distinct CALL invocation of `CEE3ABD` for abend termination, and 6 distinct ALTER statements (2 per cycle through the dispatcher, executed 4 times during the OPEN sequence). The total external-surface footprint is small enough that a Java translation can faithfully reproduce it without a dependency-injection framework — plain constructor injection of `CbStm03B` plus the four repository ports is sufficient per AAP §0.3.6.

## Cross-References

- `../input/README.md` — Sibling marker explaining the documentation-only role of the `input/` folder.
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CbStm03AGoldenTest.java` — JUnit 5 test class consuming this fixture (`@Disabled` until captures committed).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — Abstract base class with byte-for-byte parity assertion.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/statement/CbStm03A.java` — Class under test.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/statement/CbStm03B.java` — Callable utility class translating the CBSTM03B subroutine.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/TrnxRecord.java` — TRNX-RECORD from COSTM01.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CardXrefRecord.java` — CARD-XREF-RECORD from CVACT03Y.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/CustomerLegacyRecord.java` — Customer record from CUSTREC.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/AccountRecord.java` — ACCOUNT-RECORD from CVACT01Y.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/util/Decimals.java` — `BigDecimal` facade with `MathContext.DECIMAL128` and trailing-sign encoder.
- `java/carddemo-app/src/main/java/com/blitzy/carddemo/app/CreateStatementsApp.java` — Main class wiring the use case (per AAP §0.4.1 JCL-to-App mapping for CREASTMT).
- `java/MIGRATION_NOTES.md` §1.6 — Capture procedure documentation AND DEVIATION items full discussion.

The cross-references above form a complete dependency closure for the CBSTM03A golden-record fixture. The sibling `../input/README.md` is the documentation-only marker for the `input/` folder; the actual input bytes come from the read-only ASCII fixtures under `app/data/ASCII/` and the synthesized TRNXFILE produced by the SORT-equivalent step at test-time. The Java test class `CbStm03AGoldenTest` consumes this `expected/` folder via its override methods (see Phase 11). The abstract base class `GoldenRecordTest` provides the parity-assertion machinery; it reads the configured expected output files, reads the actual output files produced by the test run, and asserts byte-level equality (`assertThat(actual).isEqualTo(expected)`). The `CbStm03A` class under test depends on the constructor-injected `CbStm03B` utility class plus four repository ports; the file-backed adapter implementations in `carddemo-adapter-file` provide the production wiring. The composition root in `CreateStatementsApp` (per AAP §0.4.1) ties all of these together for a real JCL-driven batch invocation.

No cross-reference target is optional: every file listed above MUST exist by the time the test is enabled. The `CbStm03A.java`, `CbStm03B.java`, `TrnxRecord.java`, `CardXrefRecord.java`, `CustomerLegacyRecord.java`, `AccountRecord.java`, `Decimals.java`, `CreateStatementsApp.java`, `GoldenRecordTest.java`, and `CbStm03AGoldenTest.java` files are all part of the binding implementation surface and are independently subject to PR review. Their relationship to this README is bidirectional: this README documents what those files MUST produce, and those files MUST produce what this README documents.

## Authority References

- AAP §0.1.1 (refactoring objective; byte-for-byte file fidelity)
- AAP §0.1.3 (surfaced implicit requirements)
- AAP §0.2.1 (in-scope golden tree)
- AAP §0.2.2 (`app/` tree IMMUTABLE)
- AAP §0.3.1 (refactored structure; CbStm03A in application/statement/ subpackage)
- AAP §0.3.2 (records pattern, sealed-type pattern, repository ports)
- AAP §0.3.4 (JVM flags `-XX:+UseCompactObjectHeaders`, Shenandoah generational)
- AAP §0.3.6 (hexagonal architecture; no service container)
- AAP §0.4.1 (CBSTM03A DEVIATION entry; CbStm03A class name)
- AAP §0.6.1 (`Decimals` utility, BigDecimal scale 2)
- AAP §0.6.2 (sealed-type pattern for FileDispatchState and Cbstm03bResult)
- AAP §0.6.4 (`java.time` only)
- AAP §0.6.5 (`java.nio.file`; EBCDIC IBM-1047 default)
- AAP §0.6.6 (sequential execution; `ScopedValue` replaces `ThreadLocal`)
- AAP §0.6.11 (golden-record harness PR gate; `@Disabled`)
- AAP §0.7.1 (Minimal Change Clause; translate DEVIATION-flagged programs faithfully)
- AAP §0.7.2 (no PAN in production logs)
- AAP §0.7.4 (forbidden preview features; no `default` branches in pattern switches)
- AAP §0.7.5 (capture procedure in `java/MIGRATION_NOTES.md` §1.6)
- AAP §0.8.1 (citation discipline)

The 20 AAP sections listed above are the complete authority cascade for this README. They are listed in the order that they appear in the AAP itself, and each one is referenced at one or more inline citations in the body of this document. Any future modification to this README MUST preserve these authority references; removing an AAP citation without removing the corresponding claim is a defect. New claims about COBOL behavior, Java semantics, byte-level invariants, or test execution behavior MUST be supported by either a `[app/...:Lnnn]` source citation or an `AAP §0.X.Y` authority reference (or both, where the claim derives from a COBOL source AND a normative AAP rule). The citation discipline of AAP §0.8.1 is binding on this README; uncited claims are not acceptable.

## DO NOT Modify the Fixture Data Without Re-Capture

The THREE data files in this folder (`stmt_text.txt`, `stmt_html.html`, `stdout.txt`) are a COUPLED set with the read-only ASCII fixtures under `app/data/ASCII/` (`cardxref.txt`, `custdata.txt`, `acctdata.txt`) and the synthesized TRNXFILE produced from the captured TRANSACT input. If any input changes (ASCII fixture content, Clock seed, or COBOL source), this fixture MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6; ad-hoc edits to expected outputs without re-capture WILL break byte-for-byte parity. Real COBOL captures REPLACE the placeholder files; do NOT append; full byte replacement; the entire placeholder content is discarded. The `@Disabled` annotation on `CbStm03AGoldenTest` is removed ONLY when BOTH (a) real captures are committed AND (b) DEVIATION items are fully documented in `java/MIGRATION_NOTES.md` §1.6. Do NOT "fix" the ALTER + GO TO control flow — PRESERVED per AAP §0.7.1 via an explicit Java state machine. Do NOT "fix" the TIOT/TCB/PSA inspection — PRESERVED per AAP §0.7.1 via informational equivalent output documented in `java/MIGRATION_NOTES.md` §1.6. Do NOT "fix" the 2D table capacity (51 cards x 10 transactions) — PRESERVED per AAP §0.7.1. Do NOT "fix" the L281 / L290 two-space DISPLAY differences — PRESERVED EXACTLY per AAP §0.7.1. Do NOT mask card numbers or account IDs in captured `stmt_text.txt` or `stmt_html.html` — the test driver uses an unmasked sink for parity; production logs apply masking via a separate sink per AAP §0.7.2. This contract document and the captured-output files together constitute the BYTE-FOR-BYTE PARITY GATE per AAP §0.6.11.

Additional anti-patterns that MUST be rejected by PR review:

- Adding a `default` branch to any pattern-matching switch over a sealed `FileDispatchState`, `Cbstm03bResult`, or `Cbstm03bOperation` permit list — AAP §0.7.4 forbids this; the compiler's exhaustiveness check is the safety guarantee.
- Replacing `BigDecimal` with `double` or `float` for any monetary calculation — AAP §0.6.1 forbids this absolutely; the parity break would surface in the per-cardholder Total EXP value.
- Replacing `ScopedValue` with `ThreadLocal` to "simplify" the batch context — AAP §0.6.6 forbids this; `ThreadLocal` is forbidden in new code.
- Adding a `--enable-preview` flag to the JVM args of `CreateStatementsApp` or `CbStm03AGoldenTest` — AAP §0.7.4 forbids this; the preview JEPs (502, 505, 507, 512) are NOT permitted in production code.
- Mutating the contents of the read-only ASCII fixtures under `app/data/ASCII/` to "fix" a parity failure — AAP §0.2.2 forbids this; the `app/` tree is IMMUTABLE; the correct response to a parity failure is to fix the Java translation or re-capture the expected output.
- Adding HTML transformations (minifying, prettifying, re-ordering attributes, switching to self-closing tags) in the Java HTML emitter — AAP §0.7.1 forbids any change to observable bytes; the Java HTML emitter MUST produce byte-identical output to the COBOL HTML emitter.
- Switching from fixed-width 100-byte HTML records to logical HTML lines (variable width) in the Java emitter — AAP §0.6.5 forbids this; the COBOL FD declares RECFM=FB / LRECL=100 and the captured file is exactly `(record_count * 100)` bytes long.
- Skipping the `@Disabled` annotation removal step (and proceeding to enable the test before captures are committed) — the test will fail with "expected file is empty / placeholder" diagnostics; this is correct test behavior preventing premature enablement.
- Re-encoding the captured fixture files with a BOM or with CRLF line endings — the Java test harness expects UTF-8 without BOM and LF line endings; introducing a BOM or CRLF will cause spurious parity failures on the first byte.
- Introducing reflection or dynamic proxies to "abstract" the CBSTM03B subroutine call — AAP §0.7.4 forbids reflection or dynamic proxies beyond faithful COBOL CALL translation; direct method invocation on the constructor-injected collaborator is the mandated pattern.
- Introducing a build-time code generator that synthesizes the HTML emission paragraphs from a template — the verbatim HTML literals in `[app/cbl/CBSTM03A.CBL:L148-L223]` are part of the parity contract; a code generator that emits semantically-equivalent but byte-different HTML would break parity. Translate the literals by hand and verify byte-for-byte.

This README is part of the CardDemo COBOL-to-Java migration's binding contract surface. Treat it with the same care as the COBOL source files it documents. When in doubt, choose preservation over modification; when modification is unavoidable, document the deviation in `java/MIGRATION_NOTES.md` §1.6 with an explicit AAP §0.7.1 justification, and update this README's relevant phase section accordingly.

