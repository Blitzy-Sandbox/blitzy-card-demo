/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte golden-record parity test for {@code CBSTM03A}
 * (Customer Statement Generator).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CBSTM03A.CBL} &mdash; the
 * {@code PROGRAM-ID CBSTM03A} batch program (924 lines) that generates
 * monthly customer statements in TWO output formats simultaneously:
 * <ul>
 *   <li>{@code STMTFILE} (80-character fixed-width plain-text records,
 *       {@code FD-STMTFILE-REC PIC X(80)} at
 *       {@code app/cbl/CBSTM03A.CBL:L44-L45}) &mdash; one statement per
 *       card composed of the {@code ST-LINE0}..{@code ST-LINE15} working-
 *       storage line constants defined at
 *       {@code app/cbl/CBSTM03A.CBL:L85-L146}.</li>
 *   <li>{@code HTMLFILE} (100-character HTML statements,
 *       {@code FD-HTMLFILE-REC PIC X(100)} at
 *       {@code app/cbl/CBSTM03A.CBL:L46-L47}) &mdash; one statement per
 *       card composed of the {@code HTML-L01}..{@code HTML-L80} fixed
 *       template lines plus interpolated content lines
 *       ({@code HTML-L11}, {@code HTML-L23}, {@code HTML-ADDR-LN},
 *       {@code HTML-BSIC-LN}, {@code HTML-TRAN-LN}) defined at
 *       {@code app/cbl/CBSTM03A.CBL:L148-L223}.</li>
 * </ul>
 *
 * <p><strong>DEVIATION-FLAGGED program</strong> per AAP &sect;0.4.1: this
 * is the only program in the 28-program inventory whose COBOL source uses
 * z/OS-specific constructs that have <em>no direct Java equivalent</em>.
 * Two deviations are unavoidable, both faithfully reproduced where
 * possible per AAP &sect;0.7.1 (Minimal Change Clause) and documented in
 * {@code java/MIGRATION_NOTES.md}:
 *
 * <ol>
 *   <li><strong>z/OS TIOT/TCB/PSA control-block inspection</strong>
 *       ({@code app/cbl/CBSTM03A.CBL:L262-L291}, the
 *       {@code PROCEDURE DIVISION} prologue) &mdash; the COBOL
 *       {@code SET ADDRESS OF PSA-BLOCK TO PSAPTR} dereferences the
 *       z/OS Prefixed Save Area to walk the Task Control Block chain,
 *       then iterates the Task I/O Table to enumerate the runtime's DD
 *       names ({@code DISPLAY 'DD Names from TIOT: '} at
 *       {@code :L275} followed by per-entry {@code DISPLAY ': '
 *       TIOCDDNM ' -- valid UCB'} or {@code ' -- null UCB'} at
 *       {@code :L278-L291}). Java has no analogue for mainframe
 *       environment introspection &mdash; there is no
 *       {@code System.getDdNames()} API and no portable mechanism to
 *       walk an operating-system control block. Per AAP &sect;0.4.1
 *       the translation in
 *       {@link com.blitzy.carddemo.application.statement.CbStm03A}
 *       provides a no-op stub method
 *       ({@code inspectMainframeEnvironment()}) that logs a
 *       {@code DEVIATION} warning to preserve the call site for
 *       traceability without producing observable behavior change.
 *       The captured COBOL stdout WILL contain the original
 *       {@code "Running JCL : ..."} and {@code "DD Names from TIOT:"}
 *       banner lines plus per-DD entries; the Java translation emits
 *       the corresponding {@code DEVIATION} warning lines via SLF4J
 *       which the harness captures into {@code stdout.txt} for
 *       byte-for-byte comparison.</li>
 *   <li><strong>ALTER ... GO TO state machine</strong>
 *       ({@code app/cbl/CBSTM03A.CBL:L296-L314}, the
 *       {@code 0000-START} paragraph) &mdash; the COBOL alters the
 *       runtime target of {@code GO TO 8100-FILE-OPEN} among FIVE
 *       paragraphs ({@code 8100-TRNXFILE-OPEN},
 *       {@code 8200-XREFFILE-OPEN}, {@code 8300-CUSTFILE-OPEN},
 *       {@code 8400-ACCTFILE-OPEN}, {@code 8500-READTRNX-READ}) via
 *       sentences like {@code ALTER 8100-FILE-OPEN TO PROCEED TO
 *       8200-XREFFILE-OPEN} at {@code :L303}. Java has no direct
 *       equivalent because {@code goto} is a reserved word but not an
 *       implemented statement, and self-modifying control flow is
 *       fundamentally incompatible with structured programming.
 *       Per AAP &sect;0.7.5 (Phase 7.5 of the agent prompt) the
 *       translation realizes the ALTER target as a mutable
 *       {@code DispatchState} enum + state-driven loop. The state
 *       ordering matches the original COBOL transition graph
 *       <strong>exactly</strong> &mdash; reordering changes
 *       observable output (the order in which TRNXFILE/XREFFILE/
 *       CUSTFILE/ACCTFILE are opened determines the relative
 *       interleaving of {@code "ERROR OPENING ..."} DISPLAYs on
 *       failure paths) and is FORBIDDEN.</li>
 * </ol>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.statement.CbStm03A}. Per
 * AAP &sect;0.4.1 (program-by-program mapping) CBSTM03A is translated
 * into the {@code application/statement/} subpackage co-located with
 * its sibling translation {@code CbStm03B} (the callable file-services
 * subroutine invoked via {@code CALL 'CBSTM03B' USING WS-M03B-AREA}
 * throughout {@code CBSTM03A.CBL}). The Java translation is
 * instantiated by the harness via a 3-argument constructor
 * {@code CbStm03A(CbStm03B fileServices, Path statementTextOutputPath,
 * Path statementHtmlOutputPath)} which receives the {@code CbStm03B}
 * file-services collaborator (replacing the COBOL static
 * {@code CALL 'CBSTM03B'}) plus the two output file paths translating
 * the COBOL DD assignments {@code SELECT STMT-FILE ASSIGN TO STMTFILE}
 * ({@code :L39}) and {@code SELECT HTML-FILE ASSIGN TO HTMLFILE}
 * ({@code :L40}).</p>
 *
 * <p><strong>Multi-file input composition</strong> per AAP &sect;0.6.11:
 * the statement generator pulls customer/account/card/transaction data
 * from FIVE input files in the COBOL-defined order. The {@code CREASTMT}
 * JCL ({@code app/jcl/CREASTMT.JCL:L83-L86}) wires four of these DDs
 * directly to the corresponding VSAM KSDS clusters:
 * <ol>
 *   <li><strong>{@code custdata.txt}</strong> (primary input fixture,
 *       {@link #inputFile()}) &mdash; customer master records (PIC
 *       X(500) per {@code app/cpy/CVCUS01Y.cpy} or
 *       {@code app/cpy/CUSTREC.cpy} alternate layout). Wired to the
 *       COBOL DD {@code CUSTFILE} via
 *       {@code SELECT ... ASSIGN TO CUSTFILE} (implicit through the
 *       {@code CBSTM03B} file-services subroutine that owns the
 *       {@code FILE-CONTROL} clauses for all four random-access
 *       files). Read by random key {@code XREF-CUST-ID} in
 *       {@code 2000-CUSTFILE-GET} at
 *       {@code app/cbl/CBSTM03A.CBL:L368-L390}.</li>
 *   <li><strong>{@code acctdata.txt}</strong> (auxiliary, 300-byte
 *       account records per {@code app/cpy/CVACT01Y.cpy}). Read by
 *       random key {@code XREF-ACCT-ID} in {@code 3000-ACCTFILE-GET}
 *       at {@code app/cbl/CBSTM03A.CBL:L392-L414}.</li>
 *   <li><strong>{@code carddata.txt}</strong> (auxiliary, 150-byte
 *       card records per {@code app/cpy/CVACT02Y.cpy}). The CBSTM03A
 *       program itself never reads CARDFILE directly &mdash; the card
 *       record is implicit in the XREF entry's
 *       {@code XREF-CARD-NUM} field &mdash; but the fixture is wired
 *       through {@link #auxiliaryInputs()} to support the
 *       composition-root adapters that the harness instantiates for
 *       file-system permission verification (matching the COBOL DD
 *       wiring even when the program does not exercise CARDFILE
 *       directly).</li>
 *   <li><strong>{@code cardxref.txt}</strong> (auxiliary, 50-byte
 *       cross-reference records per {@code app/cpy/CVACT03Y.cpy}).
 *       Read sequentially in {@code 1000-XREFFILE-GET-NEXT} at
 *       {@code app/cbl/CBSTM03A.CBL:L345-L366} &mdash; the OUTER
 *       loop driver: every XREF record produces one statement.</li>
 *   <li><strong>{@code dailytran.txt}</strong> (auxiliary, 350-byte
 *       transaction records per {@code app/cpy/CVTRA06Y.cpy};
 *       represents the {@code TRNXFILE} VSAM KSDS produced by the
 *       SORT step in {@code CREASTMT.JCL:L44-L55} which reformats
 *       the {@code TRANSACT} cluster to put the card number at
 *       offset 1 and the transaction ID at offset 17 to support
 *       sequential reading grouped by card). Bulk-loaded into the
 *       {@code WS-TRNX-TABLE} 2-D buffer (51 cards &times; 10
 *       transactions, {@code app/cbl/CBSTM03A.CBL:L226-L233}) by the
 *       {@code 8500-READTRNX-READ} paragraph at
 *       {@code app/cbl/CBSTM03A.CBL:L818-L853} before the
 *       {@code 1000-MAINLINE} XREF-driven loop begins. Per AAP
 *       &sect;0.6.11 the Java translation uses
 *       {@link java.util.LinkedHashMap} to preserve insertion order
 *       (the COBOL iterates the table in load order; reordering
 *       changes observable output).</li>
 * </ol>
 *
 * <p><strong>Multi-file expected outputs</strong> per AAP &sect;0.6.11:
 * the COBOL CBSTM03A run produces THREE captured outputs &mdash; the
 * SECOND-MOST complex multi-output scenario in the entire harness
 * (after {@code CbTrn02CGoldenTest}'s 5-output scenario):
 *
 * <ol>
 *   <li><strong>{@code stmt_text.txt}</strong>
 *       ({@link #expectedOutputFile()}, the primary output and the
 *       value returned by {@link #expectedOutputFile()} for harness
 *       backward compatibility) &mdash; captured from the COBOL DD
 *       {@code STMTFILE}, 80-character fixed-width plain text. One
 *       statement per XREF record composed of:
 *       {@code ST-LINE0} (asterisk banner with embedded
 *       {@code "START OF STATEMENT"}), {@code ST-LINE1} (customer
 *       full name, 75 chars + 5 trailing spaces), {@code ST-LINE2}
 *       (address line 1), {@code ST-LINE3} (address line 2),
 *       {@code ST-LINE4} (address line 3 = city+state+country+zip),
 *       {@code ST-LINE5} (80 dashes), {@code ST-LINE6} (centered
 *       "Basic Details" header), {@code ST-LINE5} (dashes repeat
 *       per {@code :L494}), {@code ST-LINE7} (Account ID line),
 *       {@code ST-LINE8} (Current Balance, PIC 9(9).99-), {@code
 *       ST-LINE9} (FICO Score), {@code ST-LINE10} (dashes),
 *       {@code ST-LINE11} (centered "TRANSACTION SUMMARY" header),
 *       {@code ST-LINE12} (dashes), {@code ST-LINE13} (column
 *       headers: Tran ID, Tran Details, Tran Amount), {@code
 *       ST-LINE12} (dashes repeat per {@code :L502}), then a
 *       variable number of {@code ST-LINE14} records (one per
 *       transaction emitted by {@code 6000-WRITE-TRANS} at
 *       {@code :L675-L723}), then {@code ST-LINE12} ({@code :L435}),
 *       {@code ST-LINE14A} (Total EXP, PIC Z(9).99-),
 *       {@code ST-LINE15} (asterisk banner with embedded
 *       {@code "END OF STATEMENT"}).</li>
 *   <li><strong>{@code stmt_html.html}</strong> &mdash; captured from
 *       the COBOL DD {@code HTMLFILE}, 100-character HTML statements.
 *       This output requires <strong>literal byte-for-byte
 *       equality</strong> including <em>whitespace, attribute order,
 *       self-closing-tag conventions, line endings, and any quirks
 *       of the COBOL HTML emission</em> per AAP &sect;0.6.5 (File
 *       I/O Exactness). The HTML structure is fixed (one
 *       {@code <html>...</html>} document per card containing a
 *       {@code <table>} with header rows for bank info, customer
 *       name+address, account details, FICO score, transaction
 *       summary header, per-transaction rows, and the end-of-
 *       statement marker). The Java translation must NOT
 *       reformat the HTML, must NOT prettify it, must NOT alter
 *       attribute order, must NOT alter quoting style, and must
 *       NOT trim trailing whitespace &mdash; every byte that exists
 *       in the captured COBOL output must exist in the Java output
 *       at the same offset.</li>
 *   <li><strong>{@code stdout.txt}</strong> &mdash; captured from
 *       SYSOUT, containing the {@code "Running JCL : ..."} and
 *       {@code "DD Names from TIOT:"} banner lines (one DD entry
 *       per active TIOT segment with valid/null UCB suffix), any
 *       {@code "ERROR OPENING/READING/CLOSING ..."} diagnostics
 *       (none expected on the happy path), and any {@code "ABENDING
 *       PROGRAM"} sentinel (none expected on the happy path). Per
 *       the DEVIATION documentation: the Java {@code CbStm03A}
 *       translation emits {@code DEVIATION} warning lines for the
 *       TIOT/PSA/TCB inspection no-op stubs; the captured COBOL
 *       baseline contains the original DD-name enumeration. The
 *       captured baseline must be hand-edited so the {@code stdout
 *       .txt} fixture matches the Java DEVIATION line format
 *       byte-for-byte &mdash; this is documented in
 *       {@code java/MIGRATION_NOTES.md} as part of the CBSTM03A
 *       baseline regeneration procedure.</li>
 * </ol>
 *
 * <p><strong>Statement composition pipeline</strong> per AAP
 * &sect;0.4.1: a complete CBSTM03A run executes the following
 * sequence (this is the documented ordering invariant that the
 * byte-for-byte parity test enforces):
 *
 * <pre>{@code
 *   Phase 1: TIOT/TCB/PSA inspection (NO-OP STUB IN JAVA per DEVIATION #1)
 *            Banner lines emitted to SYSOUT.
 *
 *   Phase 2: TRNXFILE bulk-load
 *            8100-TRNXFILE-OPEN     (CALL CBSTM03B with WS-M03B-DD='TRNXFILE',
 *                                    M03B-OPEN=TRUE; then immediate READ to
 *                                    prime WS-SAVE-CARD)
 *            ALTER 8100-FILE-OPEN TO PROCEED TO 8500-READTRNX-READ
 *            8500-READTRNX-READ     (loop: append each TRNX-RECORD to
 *                                    WS-CARD-TBL/WS-TRAN-TBL grouped by
 *                                    WS-SAVE-CARD; CR-CNT counts cards,
 *                                    TR-CNT counts transactions for current
 *                                    card; final TR-CNT moved to
 *                                    WS-TRCT(CR-CNT) on transition)
 *            8200-XREFFILE-OPEN     (after TRNXFILE EOF on WS-M03B-RC = '10')
 *            8300-CUSTFILE-OPEN
 *            8400-ACCTFILE-OPEN
 *
 *   Phase 3: 1000-MAINLINE
 *            Per XREF record (1000-XREFFILE-GET-NEXT, EVALUATE
 *            WS-M03B-RC):
 *              IF WS-M03B-RC = '00':
 *                2000-CUSTFILE-GET  (random key XREF-CUST-ID)
 *                3000-ACCTFILE-GET  (random key XREF-ACCT-ID)
 *                5000-CREATE-STATEMENT
 *                  - INITIALIZE STATEMENT-LINES
 *                  - WRITE ST-LINE0 (asterisk banner)
 *                  - PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT
 *                    (emit HTML-L01..L08 declarations + table start +
 *                     header row with bank logo styled cell +
 *                     L23 customer-name header)
 *                  - STRING customer-name fields into ST-NAME (DELIMITED
 *                    BY ' ' for each name component then space)
 *                  - MOVE CUST-ADDR-LINE-{1,2} TO ST-ADD{1,2}
 *                  - STRING CUST-ADDR-LINE-3 + state + country + zip
 *                    into ST-ADD3
 *                  - MOVE ACCT-ID TO ST-ACCT-ID, ACCT-CURR-BAL TO
 *                    ST-CURR-BAL, CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE
 *                  - PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT
 *                  - WRITE ST-LINE1..ST-LINE13 + ST-LINE12 repeat
 *                - MOVE 1 TO CR-JMP, ZERO TO WS-TOTAL-AMT
 *                4000-TRNXFILE-GET  (PERFORM VARYING CR-JMP through the
 *                                    in-memory WS-CARD-TBL until a
 *                                    matching XREF-CARD-NUM is found;
 *                                    for each matching card, PERFORM
 *                                    VARYING TR-JMP up to WS-TRCT(CR-JMP)
 *                                    emitting one ST-LINE14 per
 *                                    transaction and adding TRNX-AMT to
 *                                    WS-TOTAL-AMT)
 *                - MOVE WS-TOTAL-AMT TO ST-TOTAL-TRAMT
 *                - WRITE ST-LINE12 + ST-LINE14A + ST-LINE15
 *                - Emit HTML closing rows (LTRS/L10/L75/LTDE/LTRE/L78/
 *                  L79/L80)
 *              IF WS-M03B-RC = '10':
 *                SET END-OF-FILE = 'Y'  (terminate outer loop)
 *              IF WS-M03B-RC = OTHER:
 *                DISPLAY 'ERROR READING XREFFILE' + return code
 *                PERFORM 9999-ABEND-PROGRAM
 *
 *   Phase 4: 9100-TRNXFILE-CLOSE  +  9200-XREFFILE-CLOSE
 *            + 9300-CUSTFILE-CLOSE + 9400-ACCTFILE-CLOSE
 *            (all four CALL CBSTM03B with M03B-CLOSE=TRUE)
 *            CLOSE STMT-FILE HTML-FILE
 *
 *   Phase 5: GOBACK
 * }</pre>
 *
 * <p><strong>Sequential execution preserved verbatim</strong> per AAP
 * &sect;0.6.6 ("virtual threads are NOT a license to reorder records,
 * change sort orders, or break sequencing"). The COBOL processes every
 * XREF record in the order it appears in the file, and each statement
 * is fully composed (text+HTML written, total accumulated) before
 * advancing to the next XREF entry. The HTML output's structure
 * <strong>requires</strong> this strict ordering: every per-card
 * {@code <table>} ... transaction rows ... totals ... {@code </table>}
 * block must be contiguous and uninterrupted. The Java translation
 * therefore executes strictly sequentially &mdash; <em>no virtual
 * threads</em>, no parallel streams, no fan-out per AAP &sect;0.6.6.
 * Byte-for-byte parity of this golden test implicitly verifies the
 * sequential invariant: any non-deterministic reordering would
 * corrupt the HTML document structure and mismatch the captured
 * COBOL output.</p>
 *
 * <p><strong>Monetary fidelity preserved verbatim</strong> per AAP
 * &sect;0.6.1: {@code WS-TOTAL-AMT PIC S9(9)V99 COMP-3} at
 * {@code app/cbl/CBSTM03A.CBL:L64-L65} is translated as
 * {@link java.math.BigDecimal} with explicit
 * {@link java.math.MathContext#DECIMAL128} and explicit
 * {@link java.math.RoundingMode#DOWN} truncation matching the default
 * unrounded behavior of COBOL {@code ADD TRNX-AMT TO WS-TOTAL-AMT}
 * (no {@code ROUNDED} clause). Scale 2 is preserved on every
 * accumulation: a balance of {@code $1.20} MUST NOT be normalized to
 * {@code 1.2} in the rendered text or HTML output. The
 * {@code ST-TOTAL-TRAMT PIC Z(9).99-} field at
 * {@code app/cbl/CBSTM03A.CBL:L142} (the trailing-minus suppressed-
 * leading-zero edit) MUST be encoded byte-for-byte identically to the
 * COBOL implicit MOVE result.</p>
 *
 * <p><strong>HTML byte-for-byte parity</strong> per AAP &sect;0.6.5
 * (File I/O Exactness) and the agent prompt Phase 7.2: the
 * {@code stmt_html.html} output requires <strong>literal byte-for-byte
 * equality</strong> against the COBOL capture. The Java translation
 * must NOT:
 * <ul>
 *   <li>Reformat HTML (no whitespace normalization, no pretty-printing,
 *       no attribute re-ordering)</li>
 *   <li>Substitute equivalent constructs (no entity replacement, no
 *       attribute-quoting style changes, no self-closing-tag
 *       transformations)</li>
 *   <li>Alter line endings (preserve whatever the COBOL emitted &mdash;
 *       no platform-specific {@code System.lineSeparator()}; the
 *       writer MUST emit the same byte sequence at every record
 *       boundary)</li>
 *   <li>Strip trailing whitespace (the COBOL FD record is fixed-width
 *       PIC X(100); trailing spaces are part of the contract)</li>
 *   <li>Mutate quirks (e.g., the COBOL emits
 *       {@code "&lt;p style=\"font-size:16px\"&gt;Bank of XYZ&lt;/p&gt;"}
 *       with double-quoted attributes at line {@code :L168}; the Java
 *       output must use the identical quoting style)</li>
 * </ul>
 *
 * <p>Use the same byte-array comparison as the text output &mdash; do
 * NOT introspect the HTML semantically (no JSoup-style parsing, no
 * canonicalization) per AAP &sect;0.6.11 ("the harness asserts byte
 * equality" as the non-negotiable contract).</p>
 *
 * <p><strong>Verbatim DISPLAY text strings</strong> per AAP &sect;0.7.1:
 * the Java translation emits each of the following stdout messages
 * character-for-character as in the COBOL source (preserving any
 * trailing spaces, sentence punctuation, capitalisation, and embedded
 * literal values):
 * <ul>
 *   <li>{@code "Running JCL : " TIOTNJOB " Step " TIOTJSTP} at
 *       {@code app/cbl/CBSTM03A.CBL:L270} (translated as a DEVIATION
 *       warning per DEVIATION #1)</li>
 *   <li>{@code "DD Names from TIOT: "} at {@code :L275} (translated
 *       as a DEVIATION warning per DEVIATION #1)</li>
 *   <li>{@code ": " TIOCDDNM " -- valid UCB"} /
 *       {@code ": " TIOCDDNM " -- null UCB"} at {@code :L279,L281}
 *       (translated as DEVIATION warnings per DEVIATION #1)</li>
 *   <li>{@code "ERROR OPENING TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE"}
 *       at {@code :L739,L774,L792,L810}</li>
 *   <li>{@code "ERROR READING TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE"}
 *       at {@code :L751,L359,L383,L407,L844}</li>
 *   <li>{@code "ERROR CLOSING TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE"}
 *       at {@code :L865,L882,L898,L914}</li>
 *   <li>{@code "RETURN CODE: " WS-M03B-RC} at every error site</li>
 *   <li>{@code "ABENDING PROGRAM"} at {@code :L922}</li>
 * </ul>
 *
 * <p><strong>Input fixture</strong> ({@link #inputFile()}):
 * {@code app/data/ASCII/custdata.txt}. Read directly from {@code app/}
 * via {@link GoldenRecordTest#resolveAppDataPath(String)} per AAP
 * &sect;0.4.1 and &sect;0.6.11 (the 9 ASCII fixtures are immutable and
 * NOT copied into this module).</p>
 *
 * <p><strong>Auxiliary fixtures</strong>
 * ({@link #auxiliaryInputs()}): 4 supplemental files wired through
 * the harness for the file-based adapter implementations:
 * {@code acctdata.txt} (account master, random by XREF-ACCT-ID),
 * {@code carddata.txt} (card master, opened by composition root for
 * file-system permission verification matching the JCL DD wiring),
 * {@code cardxref.txt} (XREFFILE, the outer-loop driver, sequential
 * read), and {@code dailytran.txt} (the TRNXFILE bulk-load source,
 * sequential read). The TRNXFILE in the COBOL JCL is actually a
 * SORTed copy of TRANSACT.VSAM.KSDS reformatted by the SORT step at
 * {@code app/jcl/CREASTMT.JCL:L44-L55} to put the card number at
 * offset 1 and the transaction ID at offset 17; the Java
 * golden-record fixture uses {@code dailytran.txt} as a stand-in for
 * the same record shape because both follow the 350-byte
 * {@code app/cpy/COSTM01.CPY TRNX-RECORD} layout.</p>
 *
 * <p><strong>Multi-output declaration</strong>: this test overrides
 * {@link #expectedOutputs()} (rather than relying on the base class's
 * single-output default) to declare all THREE expected outputs.
 * {@link #expectedOutputFile()} returns the primary text output
 * {@code stmt_text.txt} for backward compatibility with the abstract
 * base method, but the harness actually iterates the full
 * {@link #expectedOutputs()} list when asserting byte parity. Per
 * AAP &sect;0.6.11 the harness identifies any mismatched output by
 * name in the AssertJ failure message.</p>
 *
 * <p><strong>Always-0 return code preserved verbatim</strong> per AAP
 * &sect;0.7.1: the COBOL {@code GOBACK} at
 * {@code app/cbl/CBSTM03A.CBL:L342} is unconditional with no
 * {@code MOVE} to a return-code register on the happy path; the Java
 * translation therefore reports an effective return code of 0 on
 * successful completion. The {@code 9999-ABEND-PROGRAM} paragraph at
 * {@code :L921-L923} terminates via {@code CALL 'CEE3ABD'} (a
 * Language Environment abend service); the Java translation
 * propagates a typed exception per AAP &sect;0.7.1 ("error codes,
 * return codes, and abend conditions translated to typed exceptions
 * but with identical observable outcomes").</p>
 *
 * <p><strong>Why {@link #byteForByteParity()} is currently
 * {@code @Disabled}:</strong> per AAP &sect;0.6.11 ("Initial test
 * scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), this test class is created with full override
 * wiring so JUnit Platform discovers and reports it on every CI run,
 * but the byte-equality assertion is suppressed until BOTH (a) real
 * captured COBOL output files replace the placeholders currently
 * committed under {@code src/test/resources/golden/cbstm03a/expected/}
 * AND (b) the two DEVIATIONs (TIOT/PSA/TCB inspection &middot;
 * ALTER/GO TO state machine) are documented in
 * {@code java/MIGRATION_NOTES.md} with the specific paragraphs that
 * were stubbed and the Java realization strategy. The
 * {@code @Disabled} annotation will be removed in the same PR that
 * commits the real captures + completes the DEVIATION documentation
 * per the procedure in {@code java/MIGRATION_NOTES.md}.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per
 * AAP &sect;0.6.11 ("These are non-negotiable and run on every
 * PR"). Any change to
 * {@link com.blitzy.carddemo.application.statement.CbStm03A} that
 * alters any byte of any of the three captured outputs will fail
 * this test once the {@code @Disabled} is lifted, blocking the PR
 * until either (a) the Java change is reverted, or (b) the captured
 * COBOL baseline is regenerated to reflect a deliberate behaviour
 * change that the AAP authorises.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.statement.CbStm03A
 * @see com.blitzy.carddemo.application.statement.CbStm03B
 * @see CbStm03BGoldenTest
 * @since 25
 */
@DisplayName("CBSTM03A \u2014 Statement Generator Golden-Record Parity (DEVIATION-flagged, multi-file)")
public class CbStm03AGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CBSTM03A} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared by all 28 per-program golden tests.
     */
    private static final String PROGRAM_DIR = "cbstm03a";

    /**
     * Name of the primary input fixture under {@code app/data/ASCII/}.
     * The {@code custdata.txt} fixture contains customer master
     * records (PIC X(500) per {@code app/cpy/CVCUS01Y.cpy} or the
     * alternate {@code app/cpy/CUSTREC.cpy} legacy layout that the
     * CBSTM03A COBOL {@code COPY CUSTREC} directive at
     * {@code app/cbl/CBSTM03A.CBL:L55} actually pulls in). Wired
     * to the COBOL DD {@code CUSTFILE} via {@code //CUSTFILE DD
     * DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} at
     * {@code app/jcl/CREASTMT.JCL:L86}. Read by random key
     * {@code XREF-CUST-ID} in the {@code 2000-CUSTFILE-GET}
     * paragraph at {@code app/cbl/CBSTM03A.CBL:L368-L390}. Read
     * directly from {@code app/} via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash;
     * NOT copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CUSTDATA_TXT = "custdata.txt";

    /**
     * Name of the account-master auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code ACCTFILE}
     * via {@code //ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}
     * at {@code app/jcl/CREASTMT.JCL:L85}. Read by the
     * {@code 3000-ACCTFILE-GET} paragraph at
     * {@code app/cbl/CBSTM03A.CBL:L392-L414} for random reads keyed by
     * {@code XREF-ACCT-ID} (a 300-byte record per
     * {@code app/cpy/CVACT01Y.cpy}).
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the card-master auxiliary fixture under
     * {@code app/data/ASCII/}. CBSTM03A does not declare a CARDFILE
     * DD in its file-control section nor does {@code CREASTMT.JCL}
     * explicitly wire one; the {@code XREF-CARD-NUM} field is already
     * carried in each XREF record and consumed directly. The fixture
     * is nonetheless included in {@link #auxiliaryInputs()} so the
     * composition-root harness adapter setup mirrors the larger
     * CardDemo file inventory and the test fixture stack remains
     * consistent with sibling tests
     * ({@link CbTrn01CGoldenTest}, {@link CbAct02CGoldenTest}) that
     * exercise the full 4-file account+card+customer+xref tier. The
     * 150-byte record layout per {@code app/cpy/CVACT02Y.cpy} is the
     * authoritative shape.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the card cross-reference auxiliary fixture under
     * {@code app/data/ASCII/}. Wired to the COBOL DD {@code XREFFILE}
     * via {@code //XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}
     * at {@code app/jcl/CREASTMT.JCL:L84}. Read sequentially by the
     * {@code 1000-XREFFILE-GET-NEXT} paragraph at
     * {@code app/cbl/CBSTM03A.CBL:L345-L366} &mdash; the OUTER-loop
     * driver for the statement-generation mainline at
     * {@code app/cbl/CBSTM03A.CBL:L316-L329} ("every XREF record
     * produces one statement"). Each 50-byte record (per
     * {@code app/cpy/CVACT03Y.cpy CARD-XREF-RECORD} layout) carries
     * {@code XREF-CARD-NUM PIC X(16)} + {@code XREF-CUST-ID PIC 9(09)}
     * + {@code XREF-ACCT-ID PIC 9(11)} + 14 bytes of trailing FILLER;
     * the {@code XREF-CUST-ID} drives the
     * {@code 2000-CUSTFILE-GET} keyed read and the
     * {@code XREF-ACCT-ID} drives the
     * {@code 3000-ACCTFILE-GET} keyed read.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the daily-transaction auxiliary fixture under
     * {@code app/data/ASCII/}. Represents the {@code TRNXFILE}
     * VSAM KSDS produced by the SORT step at
     * {@code app/jcl/CREASTMT.JCL:L44-L55} which reformats
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} via
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} +
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} to put
     * the card number ({@code TRAN-CARD-NUM} originally at offset
     * 263) at offset 1 and the transaction ID ({@code TRAN-ID}
     * originally at offset 1) at offset 17 &mdash; producing a record
     * layout that matches the
     * {@code app/cpy/COSTM01.CPY TRNX-RECORD} structure for
     * sequential reading grouped by card.
     *
     * <p>The {@code dailytran.txt} fixture follows the same 350-byte
     * record shape ({@code DALYTRAN-RECORD} per
     * {@code app/cpy/CVTRA06Y.cpy} is structurally compatible with
     * {@code TRNX-RECORD} for the bulk-load purpose) and is used by
     * the {@code 8500-READTRNX-READ} paragraph at
     * {@code app/cbl/CBSTM03A.CBL:L818-L853} to populate the
     * {@code WS-TRNX-TABLE} 2-D in-memory buffer
     * ({@code app/cbl/CBSTM03A.CBL:L226-L233}, OCCURS 51 outer
     * &times; OCCURS 10 inner). The Java translation uses
     * {@link java.util.LinkedHashMap}{@code <String, List<TrnxRecord>>}
     * to preserve insertion order per AAP &sect;0.6.6 (the COBOL
     * iterates the table in load order; reordering changes
     * observable output).
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * Name of the captured COBOL {@code STMTFILE} text output under
     * {@code src/test/resources/golden/cbstm03a/expected/}. Captures
     * the 80-character fixed-width plain-text statement composed
     * from {@code ST-LINE0}..{@code ST-LINE15} per
     * {@code app/cbl/CBSTM03A.CBL:L85-L146} and emitted by
     * {@code 5000-CREATE-STATEMENT} at
     * {@code app/cbl/CBSTM03A.CBL:L458-L504} and
     * {@code 4000-TRNXFILE-GET} at
     * {@code app/cbl/CBSTM03A.CBL:L416-L456}. Currently a placeholder
     * pending COBOL capture per AAP &sect;0.6.11.
     */
    private static final String STMT_TEXT_TXT = "stmt_text.txt";

    /**
     * Name of the captured COBOL {@code HTMLFILE} output under
     * {@code src/test/resources/golden/cbstm03a/expected/}. Captures
     * the 100-character HTML statement composed from
     * {@code HTML-L01}..{@code HTML-L80} per
     * {@code app/cbl/CBSTM03A.CBL:L148-L223} and emitted by
     * {@code 5100-WRITE-HTML-HEADER}
     * ({@code app/cbl/CBSTM03A.CBL:L506-L555}),
     * {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L557-L672}),
     * {@code 6000-WRITE-TRANS} ({@code :L674-L723}), and the
     * closing sequence inside {@code 4000-TRNXFILE-GET}
     * ({@code :L439-L454}). Requires <strong>literal byte-for-byte
     * equality</strong> per AAP &sect;0.6.5 &mdash; preserve
     * whitespace, attribute order, self-closing-tag conventions,
     * and line endings. Currently a placeholder pending COBOL
     * capture per AAP &sect;0.6.11.
     */
    private static final String STMT_HTML_HTML = "stmt_html.html";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cbstm03a/expected/}. Captures
     * the TIOT/TCB/PSA inspection banner lines at
     * {@code app/cbl/CBSTM03A.CBL:L270-L291} (translated as
     * DEVIATION warning lines in the Java implementation per
     * DEVIATION #1; the captured baseline must be hand-edited so
     * the {@code stdout.txt} fixture matches the Java DEVIATION
     * line format byte-for-byte), any
     * {@code "ERROR OPENING/READING/CLOSING ..."} diagnostics on
     * error paths, and any {@code "ABENDING PROGRAM"} sentinel on
     * abend paths. Currently a placeholder pending COBOL capture
     * per AAP &sect;0.6.11.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.statement.CbStm03A}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block remains minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope transitive dependency on
     * {@code carddemo-application} via {@code carddemo-app}
     * (the composition root) in {@code java/carddemo-tests/pom.xml}
     * per AAP &sect;0.4.1.</p>
     *
     * <p>The Java class under test is instantiated by the harness
     * (in a follow-on revision of
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List)}) via the 3-argument constructor
     * {@code CbStm03A(CbStm03B fileServices, Path
     * statementTextOutputPath, Path statementHtmlOutputPath)}: the
     * harness supplies a {@code CbStm03B} instance wired against
     * the file-based adapter implementations for the four
     * auxiliary inputs (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE)
     * plus temporary output paths for STMTFILE and HTMLFILE
     * captured into the harness's actual-output map.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.statement.CbStm03A.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code <repo-root>/app/data/ASCII/custdata.txt} resolved via
     * {@link GoldenRecordTest#resolveAppDataPath(String)}. This is
     * the primary CUSTFILE input read by random key
     * {@code XREF-CUST-ID} in {@code 2000-CUSTFILE-GET} at
     * {@code app/cbl/CBSTM03A.CBL:L368-L390}. The CBSTM03A program
     * does NOT have a single "driving" input file in the strict
     * sequential-loop sense &mdash; the OUTER loop driver is
     * actually {@code cardxref.txt} read sequentially by
     * {@code 1000-XREFFILE-GET-NEXT}, and the bulk-loaded
     * {@code dailytran.txt} provides the transaction body content
     * &mdash; but {@code custdata.txt} is selected as the "primary"
     * input per the file schema's
     * {@code source_files} ordering ({@code custdata.txt} is the
     * first non-COBOL-source entry in the file-schema
     * {@code source_files} list) and per
     * agent prompt Phase 1 ("inputFile() returns
     * resolveAppDataPath('custdata.txt')").</p>
     */
    @Override
    protected Path inputFile() {
        return resolveAppDataPath(CUSTDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * text statement output at
     * {@code src/test/resources/golden/cbstm03a/expected/stmt_text.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is the PRIMARY output identifier returned for
     * backward compatibility with the {@link GoldenRecordTest}
     * abstract base method &mdash; the harness's actual
     * byte-by-byte iteration consumes the full
     * {@link #expectedOutputs()} list (3 entries) per the AAP
     * &sect;0.6.11 multi-output pattern.</p>
     *
     * <p>Byte-for-byte equality is asserted by
     * {@link GoldenRecordTest#byteForByteParity()} once the
     * {@code @Disabled} guard on the override below is removed
     * (after the placeholder content is replaced by a real COBOL
     * capture AND the two DEVIATIONs are documented in
     * {@code java/MIGRATION_NOTES.md} per AAP &sect;0.6.11
     * / &sect;0.4.1).</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STMT_TEXT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 4-element {@link List} of auxiliary
     * input fixture paths reflecting the CBSTM03A multi-file
     * composition (CUSTFILE primary + 4 auxiliaries):
     * <ol>
     *   <li>{@code app/data/ASCII/acctdata.txt} (DD {@code ACCTFILE},
     *       random read by {@code XREF-ACCT-ID} in
     *       {@code 3000-ACCTFILE-GET})</li>
     *   <li>{@code app/data/ASCII/carddata.txt} (DD CARDFILE
     *       composition-root wiring; CBSTM03A does NOT read
     *       CARDFILE directly &mdash; the XREF record carries
     *       {@code XREF-CARD-NUM} &mdash; but the fixture is included
     *       for adapter consistency with sibling tests)</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} (DD {@code XREFFILE},
     *       sequential read by {@code 1000-XREFFILE-GET-NEXT},
     *       the OUTER-loop driver)</li>
     *   <li>{@code app/data/ASCII/dailytran.txt} (DD {@code TRNXFILE},
     *       bulk-loaded by {@code 8500-READTRNX-READ} into the 2-D
     *       {@code WS-TRNX-TABLE} buffer before the mainline loop)</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object, Object,
     * Object)} immutable to preserve deterministic ordering and
     * prevent accidental mutation by the base harness or downstream
     * subclasses. The list is consumed by the
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook (inherited from the base class) to wire up the file-based
     * adapter implementations against the auxiliary input fixtures
     * before invoking the CbStm03A 3-argument constructor and its
     * primary entry method.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(ACCTDATA_TXT),
            resolveAppDataPath(CARDDATA_TXT),
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(DAILYTRAN_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the three byte-for-byte parity targets for CBSTM03A
     * &mdash; the SECOND-MOST complex multi-output scenario in the
     * harness (after {@code CbTrn02CGoldenTest}'s 5-output scenario):
     * <ol>
     *   <li>{@link #STMT_TEXT_TXT} ({@code stmt_text.txt}) &mdash; the
     *       80-character fixed-width plain-text statement captured
     *       from the COBOL {@code STMTFILE} DD, composed from
     *       {@code ST-LINE0}..{@code ST-LINE15} at
     *       {@code app/cbl/CBSTM03A.CBL:L85-L146} and emitted by
     *       {@code 5000-CREATE-STATEMENT} +
     *       {@code 4000-TRNXFILE-GET}.</li>
     *   <li>{@link #STMT_HTML_HTML} ({@code stmt_html.html}) &mdash;
     *       the 100-character HTML statement captured from the COBOL
     *       {@code HTMLFILE} DD, composed from
     *       {@code HTML-L01}..{@code HTML-L80} +
     *       {@code HTML-L11}/{@code HTML-L23}/{@code HTML-ADDR-LN}/
     *       {@code HTML-BSIC-LN}/{@code HTML-TRAN-LN} at
     *       {@code app/cbl/CBSTM03A.CBL:L148-L223} and emitted by
     *       {@code 5100-WRITE-HTML-HEADER},
     *       {@code 5200-WRITE-HTML-NMADBS}, and
     *       {@code 6000-WRITE-TRANS}. Requires <strong>literal
     *       byte-for-byte equality</strong> per AAP &sect;0.6.5
     *       &mdash; whitespace, attribute order, and line endings
     *       must be preserved exactly.</li>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       captured COBOL DISPLAY stream containing
     *       {@code "Running JCL : ..."} +
     *       {@code "DD Names from TIOT: ..."} banner lines from the
     *       TIOT/PSA/TCB inspection prologue at
     *       {@code app/cbl/CBSTM03A.CBL:L262-L291} (translated as
     *       DEVIATION warnings per DEVIATION #1) plus any
     *       error-path {@code "ERROR OPENING/READING/CLOSING ..."}
     *       diagnostics and {@code "ABENDING PROGRAM"} sentinel
     *       (none expected on the happy path).</li>
     * </ol>
     *
     * <p>The base harness
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * in order and asserts byte parity for each entry independently
     * per the AAP &sect;0.6.11 multi-output pattern, identifying any
     * mismatched output by name in the AssertJ failure message.</p>
     *
     * <p>Returned list is {@link List#of(Object, Object, Object)}
     * immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STMT_TEXT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STMT_TEXT_TXT)),
            new ExpectedOutput(STMT_HTML_HTML,
                resolveExpectedOutputPath(PROGRAM_DIR, STMT_HTML_HTML)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CBSTM03A baseline capture AND the DEVIATION
     * documentation completion per AAP &sect;0.6.11 ("Initial test
     * scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available") and AAP
     * &sect;0.4.1 (DEVIATION-flagged program).
     *
     * <p>The {@code @Disabled} annotation embeds the activation
     * checklist below. The annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cbstm03a/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md} AND completes the DEVIATION
     * documentation describing the TIOT/PSA/TCB inspection no-op
     * stubs and the ALTER/GO TO {@code DispatchState} enum state
     * machine. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class (per the AAP &sect;0.6.11 multi-output pattern with
     * structured-record diff hook via
     * {@link GoldenRecordTest#maskedRanges(String)}).</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * {@code dependencyManagement} per AAP &sect;0.5.1), the JUnit
     * Platform's {@code AnnotationSupport.findAnnotation(method,
     * Test.class)} lookup does NOT walk to the parent class
     * declaration when a subclass <em>overrides</em> a
     * {@code @Test}-annotated method &mdash; the override is treated
     * as a fresh method declaration that must carry its own
     * {@code @Test} annotation for JUnit Jupiter to discover it.
     * Without {@code @Test} here, this test class would be silently
     * dropped from the test suite, defeating the AAP &sect;0.6.11
     * PR-gate purpose of the harness skeleton. This pattern matches
     * sibling {@link CbAct01CGoldenTest},
     * {@link CbAct02CGoldenTest}, {@link CbAct03CGoldenTest},
     * {@link CbCus01CGoldenTest}, {@link CbStm03BGoldenTest},
     * {@link CbTrn01CGoldenTest}, {@link CbTrn03CGoldenTest}, and
     * {@link DateValidatorGoldenTest}.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook, or any
     *                   {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL CBSTM03A baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure "
            + "AND for the documented DEVIATIONs from the COBOL source "
            + "(this is a DEVIATION-flagged program per AAP \u00a70.4.1). "
            + "Two deviations must be documented in "
            + "java/MIGRATION_NOTES.md before removing this @Disabled: "
            + "(D1) Legacy z/OS TIOT/TCB/PSA control-block inspection at "
            + "app/cbl/CBSTM03A.CBL:L262-L291 is not translatable -- "
            + "Java has no analogue of mainframe environment "
            + "introspection. The Java translation provides a no-op stub "
            + "(inspectMainframeEnvironment) that logs a DEVIATION "
            + "warning to preserve the call site for traceability without "
            + "producing observable behavior change; the captured COBOL "
            + "stdout fixture must be hand-edited to match the Java "
            + "DEVIATION line format byte-for-byte. "
            + "(D2) The ALTER ... GO TO state machine at "
            + "app/cbl/CBSTM03A.CBL:L296-L314 is not translatable -- "
            + "Java has no implemented goto statement. The Java "
            + "translation realizes the ALTER target as a DispatchState "
            + "enum + state-driven loop; the state ordering matches the "
            + "original COBOL transition graph EXACTLY (TRNXFILE -> "
            + "XREFFILE -> CUSTFILE -> ACCTFILE -> READTRNX -> MAINLINE) "
            + "because reordering changes observable output "
            + "(determines the relative interleaving of ERROR OPENING "
            + "DISPLAYs on failure paths). "
            + "Activation checklist (all 6 must hold before removing "
            + "@Disabled): "
            + "(1) DEVIATION D1 (TIOT/PSA/TCB inspection) is "
            + "documented in java/MIGRATION_NOTES.md citing "
            + "app/cbl/CBSTM03A.CBL:L262-L291 and the Java "
            + "inspectMainframeEnvironment no-op stub strategy; "
            + "(2) DEVIATION D2 (ALTER/GO TO state machine) is "
            + "documented in java/MIGRATION_NOTES.md citing "
            + "app/cbl/CBSTM03A.CBL:L296-L314 and the Java DispatchState "
            + "enum + state-driven loop strategy preserving the COBOL "
            + "transition ordering exactly; "
            + "(3) stmt_text.txt expected output captures the "
            + "80-character fixed-width plain-text statement composed "
            + "from ST-LINE0..ST-LINE15 "
            + "(app/cbl/CBSTM03A.CBL:L85-L146) -- one statement per "
            + "XREF record, with verbatim banner lines, exact "
            + "WRITE-order of ST-LINE5/ST-LINE12 repeats per "
            + "app/cbl/CBSTM03A.CBL:L494,L502,L435, BigDecimal scale 2 "
            + "preserved on ST-CURR-BAL and ST-TRANAMT and "
            + "ST-TOTAL-TRAMT (PIC 9(9).99- and PIC Z(9).99- edits); "
            + "(4) stmt_html.html expected output requires LITERAL "
            + "byte-for-byte parity per AAP \u00a70.6.5 -- preserve "
            + "whitespace, attribute order, self-closing-tag "
            + "conventions, double-quoted attribute style, line "
            + "endings, and any quirks of the COBOL HTML emission "
            + "(no JSoup-style canonicalization; no pretty-printing; "
            + "no entity normalization); "
            + "(5) stdout.txt expected output captures the "
            + "'Running JCL : ...' and 'DD Names from TIOT:' banner "
            + "lines from the TIOT/PSA/TCB inspection prologue "
            + "(translated as DEVIATION warning lines in Java; the "
            + "captured baseline must be hand-edited so the fixture "
            + "matches the Java DEVIATION line format byte-for-byte) "
            + "plus any 'ERROR OPENING/READING/CLOSING ...' "
            + "diagnostics and 'ABENDING PROGRAM' sentinel (none "
            + "expected on the happy path); "
            + "(6) Sequential execution preserved (no parallel "
            + "reordering of XREF records or transaction rows per AAP "
            + "\u00a70.6.6 -- HTML <table> structure requires strict "
            + "ordering); always-0 return code on happy path per AAP "
            + "\u00a70.7.1 (COBOL GOBACK at app/cbl/CBSTM03A.CBL:L342 "
            + "is unconditional with no MOVE to return-code register)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
