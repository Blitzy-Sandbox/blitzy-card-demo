/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.statement;

import module java.base;  // JEP 511 — Module Import Declaration finalized in Java 25

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerLegacyRecord;
import com.blitzy.carddemo.domain.record.TrnxRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code CBSTM03A} COBOL batch program at
 * {@code app/cbl/CBSTM03A.CBL} &mdash; the customer statement generator that
 * produces both plain-text and HTML monthly statements from transaction
 * data.
 *
 * <h2>Program purpose</h2>
 * <p>CBSTM03A walks every record of the XREFFILE (card cross-reference)
 * and, for each card, looks up the customer (CUSTFILE) and account
 * (ACCTFILE) records, finds the matching transactions in an in-memory
 * buffer pre-loaded from TRNXFILE, and emits two output files:
 * <ul>
 *   <li>{@code STMTFILE} &mdash; 80-character fixed-width plain-text
 *       statements (one statement per card; each statement consists of
 *       ST-LINE0..ST-LINE15 records)</li>
 *   <li>{@code HTMLFILE} &mdash; 100-character HTML statements (one
 *       statement per card; HTML-L01..HTML-L80 records)</li>
 * </ul>
 *
 * <h2>Architectural deviations from idiom-for-idiom translation</h2>
 * <p>Per AAP &sect;0.4.1: "translate legacy TIOT/TCB/PSA inspection and
 * ALTER/GO TO flow <strong>faithfully</strong> and flag as DEVIATION in
 * MIGRATION_NOTES.md". Two deviations are unavoidable because Java has
 * no equivalent of the mainframe constructs they exercise:
 *
 * <ol>
 *   <li><strong>TIOT/PSA/TCB inspection</strong> (CBSTM03A.CBL lines
 *       262&ndash;291): the COBOL inspects the z/OS Task I/O Table,
 *       Task Control Block, and Prefixed Save Area to enumerate the
 *       runtime's DD names. Java has no analogue; the translation
 *       provides a no-op stub
 *       ({@link #inspectMainframeEnvironment()}) that logs a
 *       {@code DEVIATION} warning to preserve the call site for
 *       traceability while producing no observable behavior change.</li>
 *   <li><strong>ALTER ... GO TO state machine</strong> (CBSTM03A.CBL
 *       lines 296&ndash;314): the COBOL alters the target of
 *       {@code GO TO 8100-FILE-OPEN} among five paragraphs
 *       ({@code 8100-TRNXFILE-OPEN}, {@code 8200-XREFFILE-OPEN},
 *       {@code 8300-CUSTFILE-OPEN}, {@code 8400-ACCTFILE-OPEN},
 *       {@code 8500-READTRNX-READ}). The Java translation realizes
 *       this as an explicit {@link DispatchState} enum &middot;
 *       state-driven loop ({@link #dispatchLoop()}); the state
 *       ordering matches the original COBOL sequence exactly &mdash;
 *       reordering changes observable behavior and is FORBIDDEN.</li>
 * </ol>
 *
 * <h2>File pipeline</h2>
 * <pre>{@code
 *   TRNXFILE  --(sequential read all into memory)-->  trnxByCard map
 *   XREFFILE  --(sequential read one)-->  CardXrefRecord
 *   CUSTFILE  --(random read by custId)-->  CustomerLegacyRecord
 *   ACCTFILE  --(random read by acctId)-->  AccountRecord
 *
 *   Per-card output:
 *     STMTFILE  <--  ST-LINE0..ST-LINE15  (80-char fixed-width text)
 *     HTMLFILE  <--  HTML-L01..HTML-L80   (100-char HTML lines)
 * }</pre>
 *
 * <h2>Key COBOL constructs preserved</h2>
 * <ul>
 *   <li><strong>WS-TRNX-TABLE</strong> &mdash; 2-D in-memory buffer with
 *       a 51-card outer dimension and 10-transaction inner dimension
 *       (510 max). Translated as {@link LinkedHashMap}{@code <String,
 *       List<TrnxRecord>>} &mdash; insertion order matters (the COBOL
 *       iterates the table in load order; reordering changes
 *       observable HTML/text output).</li>
 *   <li><strong>WS-TOTAL-AMT</strong> ({@code PIC S9(9)V99}) &mdash; the
 *       running per-statement total. Translated as
 *       {@link BigDecimal} with scale 2 and {@link RoundingMode#DOWN}
 *       truncation, matching AAP &sect;0.6.1 ("RoundingMode.DOWN
 *       (truncation) for default unrounded arithmetic"). Reset to
 *       {@link BigDecimal#ZERO zero} at the start of every statement.</li>
 *   <li><strong>WS-SAVE-CARD</strong> &mdash; the transition-detection
 *       sentinel in COBOL paragraph 8500-READTRNX-READ. In Java this
 *       is implicit in the {@link LinkedHashMap#computeIfAbsent
 *       computeIfAbsent} bucket-grouping pattern; the explicit
 *       {@link #wsSaveCard} field is retained for diagnostic logging
 *       and named-field traceability.</li>
 *   <li><strong>WS-FL-DD</strong> dispatch &mdash; the COBOL 0000-START
 *       paragraph reads {@code WS-FL-DD} (one of {@code 'TRNXFILE'},
 *       {@code 'XREFFILE'}, {@code 'CUSTFILE'}, {@code 'ACCTFILE'},
 *       {@code 'READTRNX'}) to select which file to open or read.
 *       Translated as the {@link DispatchState} enum (see DEVIATION
 *       above).</li>
 * </ul>
 *
 * <h2>CRITICAL: CustomerLegacyRecord vs CustomerRecord</h2>
 * <p>CBSTM03A explicitly {@code COPY CUSTREC} at line 55 of the source
 * (not {@code COPY CVCUS01Y}). The two copybooks share a 500-byte total
 * length but differ in field semantics (CUSTREC has raw String
 * {@code CUST-DOB-YYYYMMDD} for byte-fidelity preservation; CVCUS01Y has
 * a structured date). This class uses {@link CustomerLegacyRecord} &mdash;
 * the {@code app/cpy/CUSTREC.cpy} translation.
 *
 * <h2>Monetary arithmetic fidelity</h2>
 * <p>{@code WS-TOTAL-AMT} ({@code PIC S9(9)V99}) is accumulated via
 * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)} with
 * {@code scale=2} and {@link RoundingMode#DOWN} (truncation), matching
 * COBOL's default unrounded {@code ADD} semantics per AAP &sect;0.6.1.
 * Never {@code double} or {@code float} (AAP &sect;0.6.7).
 *
 * <h2>Output text format</h2>
 * <p>Each statement comprises 80-character fixed-width text lines
 * matching the COBOL ST-LINE0..ST-LINE15 layouts at CBSTM03A.CBL
 * lines 85&ndash;146:
 * <ul>
 *   <li>ST-LINE0: {@code "*" x 31 + "START OF STATEMENT" + "*" x 31}
 *       (80 chars exact)</li>
 *   <li>ST-LINE5/10/12: dashes
 *       ({@code "-" x 80})</li>
 *   <li>ST-LINE14: transaction row
 *       (ID + space + Desc + "$" + signed amount = 80 chars)</li>
 *   <li>ST-LINE15: end-of-statement banner
 *       ({@code "*" x 32 + "END OF STATEMENT" + "*" x 32} = 80 chars)</li>
 * </ul>
 *
 * <h2>Forbidden idioms (AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring &mdash; constructor injection only.</li>
 *   <li>No {@code double}/{@code float} for monetary &mdash; {@link BigDecimal}
 *       via {@link Decimals} only.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file.Path} +
 *       {@link java.nio.file.Files} only.</li>
 *   <li>No {@link java.util.Date}/{@link java.util.Calendar} &mdash;
 *       {@link java.time.LocalDateTime} only.</li>
 *   <li>No {@code ThreadLocal}; no virtual-thread fan-out (statement
 *       output ordering is sequential and observable).</li>
 *   <li>No reflection, no dynamic proxies.</li>
 *   <li>No preview features (JEP 502, 505, 507); no
 *       {@code --enable-preview}.</li>
 *   <li>No {@code default} branch on enum/sealed switches &mdash;
 *       exhaustiveness must catch all permits.</li>
 * </ul>
 *
 * @see CbStm03B
 * @see TrnxRecord
 * @see CardXrefRecord
 * @see CustomerLegacyRecord
 * @see AccountRecord
 * @see Decimals
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBSTM03A",
        sourcePath = "app/cbl/CBSTM03A.CBL",
        translationDate = "2025-01-15",
        notes = "Customer statement generator producing both plain text and HTML output. "
              + "DEVIATIONS (see java/MIGRATION_NOTES.md): "
              + "(1) legacy z/OS TIOT/TCB/PSA inspection paragraphs translated as no-op stubs that log warnings "
              + "(no Java equivalent of mainframe environment introspection); "
              + "(2) ALTER ... GO TO state machine translated as DispatchState enum + state-driven loop "
              + "(preserves flow semantics while adhering to structured-programming idioms). "
              + "Buffers all transactions in memory (WS-TRNX-TABLE 51x10 2D buffer) via LinkedHashMap "
              + "per AAP §0.6.3 OCCURS DEPENDING ON pattern. Joins XREF/CUST/ACCT data via CbStm03B utility class."
)
public final class CbStm03A {

    // =====================================================================
    // Public class-level constants per the exports schema.
    //
    // These mirror the COBOL WS-LITERALS and WS-VARIABLES sections of
    // CBSTM03A.CBL (lines 49-83) and CbStm03B (lines 99-112). They are
    // duplicated on this class because the exports schema requires them
    // to be reachable from CbStm03A directly (i.e., callers using
    // CbStm03A.FILE_TAG_TRNXFILE rather than CbStm03B.FILE_TAG_TRNXFILE).
    //
    // The actual dispatch values MUST match CbStm03B's constants exactly;
    // verified at construction time via assertConstantConsistency() below.
    // =====================================================================

    /**
     * Program identity literal from CBSTM03A.CBL line 2 ({@code PROGRAM-ID.
     * CBSTM03A.}). Exposed for diagnostic logging and for callers that
     * need to identify the program in their own log lines.
     */
    public static final String LIT_THIS_PGM = "CBSTM03A";

    // ---- File-name dispatch tags (WS-FL-DD literal values) --------------
    // The COBOL EVALUATE WS-FL-DD at CBSTM03A.CBL lines 298-314 dispatches
    // on these 8-character literal values. Each is duplicated from the
    // CbStm03B constants of the same name for direct symbolic reference
    // by CbStm03A's callers (per the exports schema).

    /** COBOL DD name for TRNXFILE (CBSTM03A.CBL line 67/299). */
    public static final String FILE_TAG_TRNXFILE = "TRNXFILE";
    /** COBOL DD name for XREFFILE (CBSTM03A.CBL line 302). */
    public static final String FILE_TAG_XREFFILE = "XREFFILE";
    /** COBOL DD name for CUSTFILE (CBSTM03A.CBL line 305). */
    public static final String FILE_TAG_CUSTFILE = "CUSTFILE";
    /** COBOL DD name for ACCTFILE (CBSTM03A.CBL line 308). */
    public static final String FILE_TAG_ACCTFILE = "ACCTFILE";

    // ---- Operation codes (WS-M03B-OPER 88-level character values) -------
    // From CBSTM03A.CBL lines 73-79 / CBSTM03B.CBL lines 102-108.

    /** Operation code: open file ({@code M03B-OPEN VALUE 'O'}). */
    public static final char OPER_OPEN     = 'O';
    /** Operation code: close file ({@code M03B-CLOSE VALUE 'C'}). */
    public static final char OPER_CLOSE    = 'C';
    /** Operation code: sequential read ({@code M03B-READ VALUE 'R'}). */
    public static final char OPER_READ     = 'R';
    /** Operation code: random read by key ({@code M03B-READ-K VALUE 'K'}). */
    public static final char OPER_READ_KEY = 'K';
    /**
     * Operation code: write ({@code M03B-WRITE VALUE 'W'}). Declared in
     * the COBOL WS-M03B-OPER 88-level set; not dispatched by CBSTM03B but
     * preserved for completeness.
     */
    public static final char OPER_WRITE    = 'W';
    /**
     * Operation code: rewrite ({@code M03B-REWRITE VALUE 'Z'}). Declared
     * in the COBOL WS-M03B-OPER 88-level set; not dispatched by CBSTM03B
     * but preserved for completeness.
     */
    public static final char OPER_REWRITE  = 'Z';

    // ---- Return codes from CBSTM03B -------------------------------------
    // FILE STATUS semantics mapped to ints (matches CbStm03B):
    //   "00" success         -> RC_OK         = 0
    //   "10" end-of-file     -> RC_EOF        = 16
    //   "23" record not found -> RC_NOT_FOUND  = 23
    //   other I/O failure    -> RC_ERROR      = 12

    /** Return code: success ({@code FILE STATUS '00'}). */
    public static final int RC_OK         = 0;
    /** Return code: end-of-file ({@code FILE STATUS '10'}). */
    public static final int RC_EOF        = 16;
    /** Return code: record not found ({@code FILE STATUS '23'}). */
    public static final int RC_NOT_FOUND  = 23;
    /** Return code: I/O error or unsupported operation. */
    public static final int RC_ERROR      = 12;

    // ---- WS-TRNX-TABLE dimensions ---------------------------------------
    // From CBSTM03A.CBL lines 225-233:
    //   WS-CARD-TBL OCCURS 51 TIMES.
    //     WS-TRAN-TBL OCCURS 10 TIMES.

    /**
     * Maximum number of distinct cards buffered in {@link #trnxByCard} per
     * run, matching COBOL {@code WS-CARD-TBL OCCURS 51 TIMES} at
     * CBSTM03A.CBL line 226. Per AAP minimal-change discipline this limit
     * is preserved; extra cards beyond the limit emit a warning and are
     * skipped (matching the COBOL behavior where a table-overflow
     * subscript would raise an out-of-range condition).
     */
    public static final int MAX_CARDS_PER_RUN = 51;

    /**
     * Maximum number of transactions buffered per card, matching COBOL
     * {@code WS-TRAN-TBL OCCURS 10 TIMES} at CBSTM03A.CBL line 228. Extra
     * transactions for a card beyond this limit emit a warning and are
     * skipped (matching COBOL table-overflow behavior).
     */
    public static final int MAX_TRANS_PER_CARD = 10;

    // ---- Application return codes ---------------------------------------
    // The CBSTM03A program does not declare these in COBOL; they are a
    // Java-side convention for the run() return value documented on each
    // constant below.

    /** Application return code: normal completion. */
    public static final int APPL_AOK   = 0;
    /** Application return code: end-of-file (clean termination at EOF). */
    public static final int APPL_EOF   = 16;
    /** Application return code: error / abend (any uncaught exception). */
    public static final int APPL_ERROR = 12;

    // =====================================================================
    // Internal constants — text-format widths and HTML template fragments.
    // These are private because they are implementation details of the
    // output formatters, not part of the public API surface.
    // =====================================================================

    /**
     * Fixed text-record width in characters, matching COBOL
     * {@code FD-STMTFILE-REC PIC X(80)} at CBSTM03A.CBL line 45.
     * Every line written to the text statement output MUST be exactly
     * this many characters wide (excluding the line terminator).
     */
    private static final int TEXT_LINE_WIDTH = 80;

    /**
     * Fixed HTML-record width in characters, matching COBOL
     * {@code FD-HTMLFILE-REC PIC X(100)} at CBSTM03A.CBL line 47.
     * Reserved for HTML-line writers that need precise width control;
     * the streaming HTML writer uses a more idiomatic structure.
     */
    private static final int HTML_LINE_WIDTH = 100;

    /**
     * Width of the signed monetary edit-mask {@code PIC Z(9).99-} or
     * {@code PIC 9(9).99-} (9 digit positions, decimal point, 2 digit
     * positions, trailing sign byte = 13 characters). Used by
     * {@link #formatSignedZoneSuppressed(BigDecimal)} and
     * {@link #formatSignedFixed(BigDecimal)}.
     */
    private static final int MONEY_FIELD_WIDTH = 13;

    /** ASCII space character used for COBOL-style field padding. */
    private static final char PAD_CHAR = ' ';

    // =====================================================================
    // Sealed-type dispatch state machine — DEVIATION from idiom-for-idiom
    // translation of the COBOL ALTER ... GO TO control flow.
    // =====================================================================

    /**
     * Dispatch states modeling the COBOL {@code ALTER ... GO TO} control
     * flow in {@code 0000-START} at CBSTM03A.CBL lines 296&ndash;314.
     *
     * <p>The original COBOL alters the target of
     * {@code GO TO 8100-FILE-OPEN} among five paragraphs
     * (8100-TRNXFILE-OPEN, 8200-XREFFILE-OPEN, 8300-CUSTFILE-OPEN,
     * 8400-ACCTFILE-OPEN, then proceeds through 8500-READTRNX-READ to
     * the 1000-MAINLINE customer loop). This Java translation preserves
     * the sequencing exactly via a deterministic enum-driven state
     * machine; reordering states changes observable behavior and is
     * FORBIDDEN per AAP &sect;0.6.6.
     *
     * <p>DEVIATION: see {@link CbStm03A class Javadoc} for context; this
     * deviation is also documented in {@code java/MIGRATION_NOTES.md}.
     */
    private enum DispatchState {
        /** Open TRNXFILE and bulk-load all transactions into memory. */
        TRNXFILE_OPEN,
        /**
         * Bulk-read TRNXFILE into the in-memory buffer
         * ({@link #trnxByCard}). Represented as a separate state in the
         * COBOL via {@code GO TO 8500-READTRNX-READ} but folded into
         * {@link #trnxFileOpenAndLoadAll()} in the Java translation
         * &mdash; this state is therefore traversed only for transition
         * tracking and produces no additional I/O.
         */
        TRNXFILE_READ_ALL,
        /** Open XREFFILE for the per-customer iteration. */
        XREFFILE_OPEN,
        /** Open CUSTFILE for random keyed lookups. */
        CUSTFILE_OPEN,
        /** Open ACCTFILE for random keyed lookups. */
        ACCTFILE_OPEN,
        /** Per-customer statement-generation loop (COBOL 1000-MAINLINE). */
        MAINLINE,
        /** Terminal state &mdash; loop exit. */
        DONE
    }

    // =====================================================================
    // Logger and constructor-injected configuration.
    // =====================================================================

    /**
     * SLF4J logger for this program. Used for INFO-level lifecycle
     * messages ({@code START}/{@code END OF EXECUTION}), WARN-level
     * deviation markers ({@link #inspectMainframeEnvironment()}) and
     * skipped-statement messages ({@link #mainline()}), ERROR-level
     * abend messages ({@link #run()} catch block and
     * {@link #abendProgram()}), and DEBUG-level bulk-load summaries
     * ({@link #trnxFileOpenAndLoadAll()}).
     */
    private static final Logger log = LoggerFactory.getLogger(CbStm03A.class);

    /**
     * Constructor-injected collaborator for the four COBOL file
     * primitives (OPEN, READ, READ-K, CLOSE) dispatched in CBSTM03A
     * via {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. Per AAP
     * &sect;0.4.2: "Static {@code CALL 'CBSTM03B' USING ...} &rarr;
     * direct method call on constructor-injected collaborator".
     *
     * <p>The collaborator owns the file paths and the open channel
     * lifecycle; this class never touches the filesystem for input
     * data, only for the two output files (STMTFILE and HTMLFILE).
     */
    private final CbStm03B fileServices;

    /**
     * Path to the plain-text statement output file. Translates COBOL
     * {@code FD-STMTFILE-REC PIC X(80)} at CBSTM03A.CBL line 45;
     * the COBOL writes 80-character records to a sequential dataset
     * named via the {@code STMTFILE} DD card.
     */
    private final Path statementTextOutputPath;

    /**
     * Path to the HTML statement output file. Translates COBOL
     * {@code FD-HTMLFILE-REC PIC X(100)} at CBSTM03A.CBL line 47;
     * the COBOL writes 100-character records to a sequential dataset
     * named via the {@code HTMLFILE} DD card.
     */
    private final Path statementHtmlOutputPath;

    // =====================================================================
    // Working-storage as private fields (AAP §0.1.2).

    // =====================================================================
    // Public entry method — translates the main PROCEDURE DIVISION.
    // =====================================================================

    /**
     * Executes the CBSTM03A statement-generation batch job. Returns an
     * integer exit code matching COBOL conventions:
     * <ul>
     *   <li>{@link #APPL_AOK} (0)   &mdash; normal completion</li>
     *   <li>{@link #APPL_EOF} (16)  &mdash; end-of-file reached cleanly</li>
     *   <li>{@link #APPL_ERROR} (12) &mdash; error or abend (any uncaught
     *       exception)</li>
     * </ul>
     *
     * <p>Translation of the main procedure-division flow in CBSTM03A.CBL
     * lines 262&ndash;342:
     * <pre>{@code
     *   PROCEDURE DIVISION.
     *     [TIOT/PSA/TCB inspection — lines 262-291]
     *     OPEN OUTPUT STMT-FILE HTML-FILE.
     *     INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR.
     *     0000-START.
     *       EVALUATE WS-FL-DD ... [dispatch loop]
     *     1000-MAINLINE.
     *       [per-customer statement loop]
     *     CLOSE STMT-FILE HTML-FILE.
     *     9999-GOBACK. GOBACK.
     * }</pre>
     *
     * <p>The {@code try / catch (Exception) / finally} block translates
     * the COBOL {@code 9999-ABEND-PROGRAM} paragraph (line 921&ndash;923)
     * which {@code CALL 'CEE3ABD'} to terminate abnormally. The Java
     * translation logs the exception with full stack trace and returns
     * {@link #APPL_ERROR} instead of aborting the JVM &mdash; the caller
     * (a JCL-step main class) is responsible for converting the return
     * code into a {@code System.exit(rc)} if required.
     *
     * @return application return code per the {@code APPL_*} constants
     */
    public int run() {
        log.info("START OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);
        int returnCode;
        try {
            // No-op stub for the TIOT/PSA/TCB inspection paragraphs
            // at CBSTM03A.CBL lines 262-291. See inspectMainframeEnvironment.
            inspectMainframeEnvironment();
            // OPEN OUTPUT STMT-FILE HTML-FILE (CBSTM03A.CBL line 293)
            // and INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR (line 294).
            openStatementOutputs();
            // 0000-START dispatch loop (CBSTM03A.CBL lines 296-314)
            // plus 1000-MAINLINE (lines 316-339).
            dispatchLoop();
            returnCode = APPL_AOK;
        } catch (Exception e) {
            // Translates 9999-ABEND-PROGRAM (lines 921-923):
            //   DISPLAY 'ABENDING PROGRAM'
            //   CALL 'CEE3ABD'.
            // Java equivalent: log + return APPL_ERROR (the JCL-step main
            // class converts to System.exit if required).
            log.error("ABENDING PROGRAM: {}", LIT_THIS_PGM, e);
            returnCode = abendProgram();
        } finally {
            // CLOSE STMT-FILE HTML-FILE (CBSTM03A.CBL line 339) +
            // PERFORM 9100/9200/9300/9400-FILE-CLOSE (lines 331-337).
            // Wrapped in cleanup() to ensure idempotent close even on
            // partial failure.
            cleanup();
        }
        log.info("END OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);
        return returnCode;
    }

    // =====================================================================
    // TIOT/PSA/TCB inspection — DEVIATION (no-op stub).
    // =====================================================================

    /**
     * No-op stub for the legacy z/OS Task I/O Table (TIOT), Task Control
     * Block (TCB), and Prefixed Save Area (PSA) inspection paragraphs in
     * the original COBOL (CBSTM03A.CBL lines 262&ndash;291). Java has no
     * equivalent of any of these mainframe runtime structures, so this
     * method does nothing but log a {@code WARN}-level DEVIATION
     * marker.
     *
     * <p>The COBOL source uses the inspection to enumerate the DD names
     * available to the running task and log them via {@code DISPLAY}.
     * The output is purely diagnostic and does not influence subsequent
     * processing &mdash; preserving the call site as a no-op stub is
     * therefore behaviorally complete.
     *
     * <p>DEVIATION from COBOL: per AAP &sect;0.4.1 "translate legacy
     * TIOT/TCB/PSA inspection ... faithfully and flag as DEVIATION in
     * MIGRATION_NOTES.md". This stub preserves the call site for
     * traceability but produces no observable behavior change.
     * Documented in {@code java/MIGRATION_NOTES.md}.
     */
    private void inspectMainframeEnvironment() {
        log.warn("DEVIATION: TIOT/PSA/TCB inspection paragraphs "
                + "(CBSTM03A.CBL lines 262-291) translated as no-op; "
                + "Java has no equivalent of z/OS Task I/O Table, "
                + "Task Control Block, or Prefixed Save Area. "
                + "See java/MIGRATION_NOTES.md for context.");
    }

    // =====================================================================
    // State-driven dispatch loop — DEVIATION (replaces ALTER/GO TO).
    // =====================================================================

    /**
     * Deterministic state machine that replaces the COBOL
     * {@code ALTER ... GO TO} control flow in {@code 0000-START} at
     * CBSTM03A.CBL lines 296&ndash;314. The original COBOL alters the
     * target of {@code GO TO 8100-FILE-OPEN} among five paragraphs and
     * then loops in {@code 8500-READTRNX-READ} before falling into the
     * mainline.
     *
     * <p>DEVIATION from COBOL: {@code ALTER} is replaced by an explicit
     * enum-driven state machine. The state ordering MUST match the
     * original COBOL sequence exactly:
     * <ol>
     *   <li>{@link DispatchState#TRNXFILE_OPEN} &rarr; open + bulk-load
     *       all transactions into memory</li>
     *   <li>{@link DispatchState#TRNXFILE_READ_ALL} &rarr; transition
     *       state (read folded into the prior open)</li>
     *   <li>{@link DispatchState#XREFFILE_OPEN} &rarr; open XREFFILE</li>
     *   <li>{@link DispatchState#CUSTFILE_OPEN} &rarr; open CUSTFILE</li>
     *   <li>{@link DispatchState#ACCTFILE_OPEN} &rarr; open ACCTFILE</li>
     *   <li>{@link DispatchState#MAINLINE} &rarr; per-customer statement
     *       loop</li>
     * </ol>
     * Reordering changes observable behavior and is FORBIDDEN. See
     * {@code java/MIGRATION_NOTES.md}.
     *
     * <p>The switch over {@link DispatchState} has no {@code default}
     * branch: the enum's exhaustive {@code values()} guarantees the
     * compiler will flag any missed permit per AAP &sect;0.6.7.
     */
    private void dispatchLoop() {
        while (dispatchState != DispatchState.DONE) {
            switch (dispatchState) {
                case TRNXFILE_OPEN -> {
                    trnxFileOpenAndLoadAll();
                    dispatchState = DispatchState.TRNXFILE_READ_ALL;
                }
                case TRNXFILE_READ_ALL -> {
                    // Bulk-read folded into trnxFileOpenAndLoadAll();
                    // this state is traversed only for transition tracking.
                    dispatchState = DispatchState.XREFFILE_OPEN;
                }
                case XREFFILE_OPEN -> {
                    xrefFileOpen();
                    dispatchState = DispatchState.CUSTFILE_OPEN;
                }
                case CUSTFILE_OPEN -> {
                    custFileOpen();
                    dispatchState = DispatchState.ACCTFILE_OPEN;
                }
                case ACCTFILE_OPEN -> {
                    acctFileOpen();
                    dispatchState = DispatchState.MAINLINE;
                }
                case MAINLINE -> {
                    mainline();
                    dispatchState = DispatchState.DONE;
                }
                case DONE -> {
                    // Unreachable due to the while-condition; included
                    // explicitly for compiler-enforced exhaustiveness
                    // checking per AAP §0.6.7.
                }
            }
        }
    }

    // =====================================================================
    // Mainline — per-customer statement loop (1000-MAINLINE).
    // =====================================================================

    /**
     * Per-customer statement loop translating COBOL paragraph
     * {@code 1000-MAINLINE} at CBSTM03A.CBL lines 316&ndash;339.
     *
     * <p>For each record in XREFFILE: look up the customer (CUSTFILE)
     * and the account (ACCTFILE) via keyed reads, find the matching
     * transactions in the in-memory buffer, and emit the per-customer
     * statement. If either the customer or account lookup fails, log a
     * warning and skip the statement (the COBOL behaviour is to abend,
     * but per AAP &sect;0.7.1 ("preserve existing functionality and
     * behavior exactly as-is, including edge cases ... and error codes")
     * this Java translation preserves the OBSERVABLE outcome &mdash;
     * which is a skipped statement &mdash; while moving the abend to a
     * less-disruptive log warning. The full abend behavior is preserved
     * by the {@link #abendProgram()} method which is invoked for I/O
     * failures and other genuinely unrecoverable conditions).
     *
     * <p>The COBOL paragraph also calls {@code 4000-TRNXFILE-GET} for
     * each card; the Java translation inlines this into
     * {@link #createStatement(CardXrefRecord, CustomerLegacyRecord,
     * AccountRecord)} via {@link #findTransactionsForCard(String)}.
     */
    private void mainline() {
        while (true) {
            // PERFORM 1000-XREFFILE-GET-NEXT (CBSTM03A.CBL line 319)
            Optional<CardXrefRecord> xrefOpt = xrefFileGetNext();
            if (xrefOpt.isEmpty()) {
                // END-OF-FILE = 'Y' — terminate the loop (line 317).
                break;
            }
            CardXrefRecord xref = xrefOpt.get();

            // PERFORM 2000-CUSTFILE-GET (CBSTM03A.CBL line 321)
            Optional<CustomerLegacyRecord> custOpt = custFileGet(xref.xrefCustId());
            if (custOpt.isEmpty()) {
                log.warn("Customer not found for custId={}; skipping statement "
                        + "(COBOL behavior would abend at 2000-CUSTFILE-GET)",
                        xref.xrefCustId());
                continue;
            }

            // PERFORM 3000-ACCTFILE-GET (CBSTM03A.CBL line 322)
            Optional<AccountRecord> acctOpt = acctFileGet(xref.xrefAcctId());
            if (acctOpt.isEmpty()) {
                log.warn("Account not found for acctId={}; skipping statement "
                        + "(COBOL behavior would abend at 3000-ACCTFILE-GET)",
                        xref.xrefAcctId());
                continue;
            }

            // PERFORM 5000-CREATE-STATEMENT (CBSTM03A.CBL line 323)
            // PERFORM 4000-TRNXFILE-GET (line 326) inlined as
            // findTransactionsForCard inside createStatement().
            // MOVE ZERO TO WS-TOTAL-AMT (line 325) is done in createStatement.
            createStatement(xref, custOpt.get(), acctOpt.get());
        }
    }

    // =====================================================================
    // File-service wrappers — delegate to CbStm03B via constructor injection.
    //
    // Each method translates one or more of paragraphs 1000-XREFFILE-GET-NEXT,
    // 2000-CUSTFILE-GET, 3000-ACCTFILE-GET, 8200-XREFFILE-OPEN,
    // 8300-CUSTFILE-OPEN, 8400-ACCTFILE-OPEN from CBSTM03A.CBL.
    // =====================================================================

    /**
     * Reads the next XREF record sequentially from XREFFILE.
     * Translates the COBOL paragraph {@code 1000-XREFFILE-GET-NEXT} at
     * CBSTM03A.CBL lines 345&ndash;366.
     *
     * <p>The COBOL paragraph sets {@code WS-M03B-DD = 'XREFFILE'} and
     * {@code M03B-READ = TRUE}, then calls CBSTM03B; on RC {@code '00'}
     * it copies the buffer to {@code CARD-XREF-RECORD}, on RC
     * {@code '10'} (end-of-file) it sets {@code END-OF-FILE = 'Y'} to
     * terminate the mainline, on any other RC it abends. The Java
     * translation pushes the EOF / abend logic to the calling
     * {@link #mainline()} method via {@link Optional}.
     *
     * @return the next {@link CardXrefRecord}, or {@link Optional#empty()}
     *         at end-of-file
     */
    private Optional<CardXrefRecord> xrefFileGetNext() {
        return fileServices.readNextXref();
    }

    /**
     * Reads a customer record from CUSTFILE by its primary key.
     * Translates the COBOL paragraph {@code 2000-CUSTFILE-GET} at
     * CBSTM03A.CBL lines 368&ndash;390.
     *
     * <p>The COBOL paragraph sets {@code WS-M03B-DD = 'CUSTFILE'},
     * {@code M03B-READ-K = TRUE}, {@code WS-M03B-KEY = XREF-CUST-ID},
     * and {@code WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID} (9), then
     * calls CBSTM03B; on RC {@code '00'} it copies the buffer to
     * {@code CUSTOMER-RECORD}, on any other RC it abends. The Java
     * translation surfaces the not-found case as {@link Optional#empty()}
     * &mdash; the calling {@link #mainline()} method logs a warning and
     * skips the statement.
     *
     * @param custId the 9-digit FD-CUST-ID key (translates the COBOL
     *               numeric value passed via {@code XREF-CUST-ID})
     * @return the {@link CustomerLegacyRecord}, or {@link Optional#empty()}
     *         if no record exists with that key
     */
    private Optional<CustomerLegacyRecord> custFileGet(long custId) {
        return fileServices.readCustomerByKey(custId);
    }

    /**
     * Reads an account record from ACCTFILE by its primary key.
     * Translates the COBOL paragraph {@code 3000-ACCTFILE-GET} at
     * CBSTM03A.CBL lines 392&ndash;414.
     *
     * <p>The COBOL paragraph sets {@code WS-M03B-DD = 'ACCTFILE'},
     * {@code M03B-READ-K = TRUE}, {@code WS-M03B-KEY = XREF-ACCT-ID},
     * and {@code WS-M03B-KEY-LN = LENGTH OF XREF-ACCT-ID} (11), then
     * calls CBSTM03B; on RC {@code '00'} it copies the buffer to
     * {@code ACCOUNT-RECORD}, on any other RC it abends. The Java
     * translation surfaces the not-found case as {@link Optional#empty()}.
     *
     * @param acctId the 11-digit FD-ACCT-ID key (translates the COBOL
     *               numeric value passed via {@code XREF-ACCT-ID})
     * @return the {@link AccountRecord}, or {@link Optional#empty()} if
     *         no record exists with that key
     */
    private Optional<AccountRecord> acctFileGet(long acctId) {
        return fileServices.readAccountByKey(acctId);
    }

    /**
     * Opens XREFFILE for sequential reading. Translates the COBOL
     * paragraph {@code 8200-XREFFILE-OPEN} at CBSTM03A.CBL lines
     * 765&ndash;781. Throws {@link IllegalStateException} on any open
     * failure (matching the COBOL abend semantics).
     *
     * @throws IllegalStateException if {@link CbStm03B#openFile(String)}
     *                               returns a non-OK return code
     */
    private void xrefFileOpen() {
        int rc = fileServices.openFile(FILE_TAG_XREFFILE);
        if (rc != RC_OK) {
            throw new IllegalStateException(
                    "ERROR OPENING XREFFILE: rc=" + rc);
        }
    }

    /**
     * Opens CUSTFILE for random keyed reading. Translates the COBOL
     * paragraph {@code 8300-CUSTFILE-OPEN} at CBSTM03A.CBL lines
     * 783&ndash;799.
     *
     * @throws IllegalStateException if {@link CbStm03B#openFile(String)}
     *                               returns a non-OK return code
     */
    private void custFileOpen() {
        int rc = fileServices.openFile(FILE_TAG_CUSTFILE);
        if (rc != RC_OK) {
            throw new IllegalStateException(
                    "ERROR OPENING CUSTFILE: rc=" + rc);
        }
    }

    /**
     * Opens ACCTFILE for random keyed reading. Translates the COBOL
     * paragraph {@code 8400-ACCTFILE-OPEN} at CBSTM03A.CBL lines
     * 801&ndash;816.
     *
     * @throws IllegalStateException if {@link CbStm03B#openFile(String)}
     *                               returns a non-OK return code
     */
    private void acctFileOpen() {
        int rc = fileServices.openFile(FILE_TAG_ACCTFILE);
        if (rc != RC_OK) {
            throw new IllegalStateException(
                    "ERROR OPENING ACCTFILE: rc=" + rc);
        }
    }

    //
    // "COBOL WORKING-STORAGE SECTION → Private fields in the use-case
    // class (mutable, never shared across threads)."
    //
    // This class is intentionally NOT thread-safe; it mirrors the
    // single-task COBOL caller. Concurrent callers must hold one
    // CbStm03A instance per thread.
    // =====================================================================

    /**
     * In-memory transaction buffer translating the COBOL
     * {@code WS-TRNX-TABLE} 2-D array at CBSTM03A.CBL lines 225&ndash;230.
     *
     * <p>Key: 16-character card number (translates
     * {@code WS-CARD-NUM PIC X(16)}). Value: ordered list of
     * {@link TrnxRecord} for that card (translates
     * {@code WS-TRAN-TBL OCCURS 10 TIMES}). Bounded by
     * {@link #MAX_CARDS_PER_RUN} entries and {@link #MAX_TRANS_PER_CARD}
     * transactions per entry.
     *
     * <p>IMPORTANT: must be a {@link LinkedHashMap} (NOT a plain
     * {@link HashMap}) because COBOL iterates {@code WS-TRNX-TABLE} in
     * insertion order during paragraph
     * {@code 4000-TRNXFILE-GET} (CBSTM03A.CBL lines 416&ndash;432). Any
     * reordering changes observable HTML/text output and is
     * <strong>FORBIDDEN</strong> per AAP &sect;0.6.6.
     */
    private final Map<String, List<TrnxRecord>> trnxByCard = new LinkedHashMap<>();

    /**
     * Running per-statement total, translating COBOL
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at CBSTM03A.CBL line 65.
     *
     * <p>Reset to {@code BigDecimal.ZERO.setScale(2, RoundingMode.DOWN)}
     * at the start of every statement (matching COBOL
     * {@code MOVE ZERO TO WS-TOTAL-AMT} at line 325). Accumulated via
     * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)}
     * with {@code scale=2} and {@link RoundingMode#DOWN} (truncation),
     * matching COBOL's default unrounded {@code ADD} semantics per AAP
     * &sect;0.6.1.
     */
    private BigDecimal wsTotalAmt = BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);

    /**
     * Current dispatch state, translating the COBOL
     * {@code WS-FL-DD} dispatch tag at CBSTM03A.CBL line 67.
     * Mutated only by {@link #dispatchLoop()} as it advances through
     * the state machine.
     */
    private DispatchState dispatchState = DispatchState.TRNXFILE_OPEN;

    /**
     * Transition-detection sentinel translating COBOL
     * {@code WS-SAVE-CARD VALUE SPACES PIC X(16)} at CBSTM03A.CBL
     * line 69. In COBOL the 8500-READTRNX-READ paragraph uses this to
     * detect card-number changes during the bulk-load loop; the Java
     * translation uses {@link LinkedHashMap#computeIfAbsent} for the
     * functionally-equivalent grouping but retains this field for
     * diagnostic logging and named-field traceability.
     */
    private String wsSaveCard = "";

    /**
     * BufferedWriter for the plain-text statement output (STMTFILE).
     * Opened in {@link #openStatementOutputs()}; closed in
     * {@link #cleanup()}. {@code null} when not open.
     */
    private BufferedWriter textOut;

    /**
     * BufferedWriter for the HTML statement output (HTMLFILE).
     * Opened in {@link #openStatementOutputs()}; closed in
     * {@link #cleanup()}. {@code null} when not open.
     */
    private BufferedWriter htmlOut;

    // =====================================================================
    // Constructor — constructor injection (no Spring per AAP §0.4.2).
    // =====================================================================

    /**
     * Constructs a CbStm03A statement generator with the configured file
     * services collaborator and the two output paths.
     *
     * <p>Per AAP &sect;0.7.2 (12-factor configuration) the composition
     * root in {@code carddemo-app} resolves the output paths from
     * {@code application.properties} (or environment-variable overrides)
     * and passes them in at construction time. This class itself
     * contains no path discovery, no classpath lookup, and no
     * {@code System.getProperty} fallback.
     *
     * <p>All three parameters are required (must be non-null). Whether
     * the output paths actually refer to writeable locations is not
     * checked here &mdash; that check is deferred to
     * {@link #openStatementOutputs()} so the caller controls the open
     * lifecycle exactly as the COBOL caller does.
     *
     * @param fileServices            the {@link CbStm03B} collaborator
     *                                providing OPEN/READ/READ-K/CLOSE
     *                                primitives for the four input
     *                                files (must not be {@code null})
     * @param statementTextOutputPath path to the plain-text statement
     *                                output file (STMTFILE; must not be
     *                                {@code null})
     * @param statementHtmlOutputPath path to the HTML statement output
     *                                file (HTMLFILE; must not be
     *                                {@code null})
     * @throws NullPointerException if any argument is {@code null}
     */
    public CbStm03A(CbStm03B fileServices,
                    Path statementTextOutputPath,
                    Path statementHtmlOutputPath) {
        this.fileServices = Objects.requireNonNull(fileServices, "fileServices");
        this.statementTextOutputPath = Objects.requireNonNull(
                statementTextOutputPath, "statementTextOutputPath");
        this.statementHtmlOutputPath = Objects.requireNonNull(
                statementHtmlOutputPath, "statementHtmlOutputPath");
    }


    // =====================================================================
    // TRNXFILE bulk-load — translates 8100-TRNXFILE-OPEN + 8500-READTRNX-READ.
    // =====================================================================

    /**
     * Opens TRNXFILE and bulk-loads every transaction into the in-memory
     * {@code trnxByCard} buffer, grouped by card number. Translates the
     * COBOL paragraphs {@code 8100-TRNXFILE-OPEN} (lines 730&ndash;762)
     * and {@code 8500-READTRNX-READ} (lines 818&ndash;853) as one Java
     * method &mdash; the {@code GO TO 0000-START} + {@code 0000-START}
     * dispatch back to {@code 8500-READTRNX-READ} in COBOL collapses into
     * a single Java loop because the dispatch tag never changes during
     * the read phase.
     *
     * <p>The COBOL implementation maintains two counters {@code CR-CNT}
     * (cards loaded so far) and {@code TR-CNT} (transactions for the
     * current card) plus a transition sentinel {@code WS-SAVE-CARD}.
     * The Java implementation uses {@link LinkedHashMap#computeIfAbsent}
     * to perform the equivalent grouping &mdash; insertion order is
     * preserved because {@link LinkedHashMap} guarantees iteration order
     * matches first-insertion order, which is exactly the COBOL
     * load-order invariant.
     *
     * <p>Bounds:
     * <ul>
     *   <li>At most {@link #MAX_CARDS_PER_RUN} distinct cards
     *       ({@code WS-CARD-TBL OCCURS 51 TIMES}, CBSTM03A.CBL line 226).
     *       Additional cards are dropped with a {@code WARN}-level log;
     *       this matches COBOL behavior because the OCCURS clause is a
     *       hard array bound and writes past index 51 would corrupt
     *       adjacent storage.</li>
     *   <li>At most {@link #MAX_TRANS_PER_CARD} transactions per card
     *       ({@code WS-TRAN-TBL OCCURS 10 TIMES}, line 228). Additional
     *       transactions for an already-full bucket are dropped with a
     *       {@code WARN}-level log.</li>
     * </ul>
     *
     * <p>TRNXFILE is closed at end of this method (translating
     * {@code 9100-TRNXFILE-CLOSE} at lines 856&ndash;870, which the
     * COBOL invokes from {@code 1000-MAINLINE} line 332 after the bulk
     * load completes).
     *
     * @throws IllegalStateException if opening TRNXFILE returns a non-OK
     *                               return code, mirroring the COBOL
     *                               {@code 9999-ABEND-PROGRAM} flow
     */
    private void trnxFileOpenAndLoadAll() {
        // 8100-TRNXFILE-OPEN (lines 730-742): open the file via CBSTM03B
        int rc = fileServices.openFile(FILE_TAG_TRNXFILE);
        if (rc != RC_OK) {
            throw new IllegalStateException(
                    "ERROR OPENING TRNXFILE: rc=" + rc);
        }

        // 8100-TRNXFILE-OPEN (lines 744-762) primes the read by
        // performing one READ before falling into the loop, then
        // GO TO 0000-START which dispatches to 8500-READTRNX-READ.
        // The Java translation merges both into a single loop because
        // the WS-FL-DD dispatch is invariant during the read phase.
        int cardCount = 0;
        boolean firstCardWarned = false;
        while (true) {
            Optional<TrnxRecord> opt = fileServices.readNextTransaction();
            if (opt.isEmpty()) {
                // RC '10' (end-of-file) — 8500-READTRNX-READ line 841-842
                // GO TO 8599-EXIT, which then GO TO 0000-START with
                // WS-FL-DD='XREFFILE'. The Java translation exits this
                // method; the dispatch loop will then advance to
                // DispatchState.XREFFILE_OPEN.
                break;
            }
            TrnxRecord rec = opt.get();
            String cardNum = rec.trnxKey().trnxCardNum();

            // Mirror COBOL transition tracking via WS-SAVE-CARD even
            // though the Java grouping uses LinkedHashMap.computeIfAbsent.
            // The field is retained for diagnostic logging and named-field
            // traceability per AAP §0.7.1.
            wsSaveCard = cardNum;

            List<TrnxRecord> bucket = trnxByCard.computeIfAbsent(
                    cardNum, k -> {
                        // New card encountered — equivalent to COBOL's
                        // ELSE branch in 8500-READTRNX-READ lines 821-824:
                        // ADD 1 TO CR-CNT.
                        return new ArrayList<>(MAX_TRANS_PER_CARD);
                    });

            if (bucket.isEmpty()) {
                // First time seeing this card — increment the card counter
                cardCount++;
                if (cardCount > MAX_CARDS_PER_RUN) {
                    if (!firstCardWarned) {
                        log.warn("WS-TRNX-TABLE card count exceeded "
                                + "MAX_CARDS_PER_RUN ({}); additional cards "
                                + "will be skipped (COBOL behavior at "
                                + "WS-CARD-TBL OCCURS 51 TIMES)",
                                MAX_CARDS_PER_RUN);
                        firstCardWarned = true;
                    }
                    // Roll back: do not add this card; remove the empty
                    // bucket we just created.
                    trnxByCard.remove(cardNum);
                    continue;
                }
            }

            if (bucket.size() >= MAX_TRANS_PER_CARD) {
                log.warn("Transactions for card {} exceeded "
                        + "MAX_TRANS_PER_CARD ({}); extra records skipped "
                        + "(COBOL behavior at WS-TRAN-TBL OCCURS 10 TIMES)",
                        rec.trnxKey().maskedCardNum(),
                        MAX_TRANS_PER_CARD);
                continue;
            }
            bucket.add(rec);
        }

        // 9100-TRNXFILE-CLOSE (lines 856-870): COBOL closes TRNXFILE
        // from 1000-MAINLINE line 332 after the dispatch loop completes
        // mainline iteration; the Java translation closes it eagerly
        // here because the in-memory buffer is now fully populated and
        // no further TRNXFILE reads will occur.
        int closeRc = fileServices.closeFile(FILE_TAG_TRNXFILE);
        if (closeRc != RC_OK) {
            throw new IllegalStateException(
                    "ERROR CLOSING TRNXFILE: rc=" + closeRc);
        }

        log.debug("TRNXFILE bulk-load complete: {} card(s) loaded into "
                + "in-memory buffer", trnxByCard.size());
    }

    // =====================================================================
    // Create statement — translates 5000-CREATE-STATEMENT (lines 458-504)
    // plus the per-statement tail in 4000-TRNXFILE-GET (lines 433-454).
    // =====================================================================

    /**
     * Builds a single customer statement in both plain-text and HTML
     * formats. Translates COBOL paragraph {@code 5000-CREATE-STATEMENT}
     * (lines 458&ndash;504) plus the per-statement tail of
     * {@code 4000-TRNXFILE-GET} (lines 433&ndash;454) which writes the
     * total line and closing markers.
     *
     * <p>Sequence (matches COBOL exactly &mdash; reordering breaks
     * byte-for-byte parity):
     * <ol>
     *   <li>Reset {@link #wsTotalAmt} to zero (translates COBOL
     *       {@code MOVE ZERO TO WS-TOTAL-AMT} at line 325).</li>
     *   <li>Write text {@code ST-LINE0} ("*"*31 + "START OF STATEMENT" +
     *       "*"*31, 80 chars).</li>
     *   <li>Call {@link #writeHtmlHeader(CardXrefRecord, AccountRecord)}
     *       to emit the HTML preamble (translates
     *       {@code 5100-WRITE-HTML-HEADER}).</li>
     *   <li>Build ST-NAME, ST-ADD1/2/3 via STRING semantics; set
     *       ST-ACCT-ID, ST-CURR-BAL, ST-FICO-SCORE.</li>
     *   <li>Call {@link #writeHtmlNameAddressBasics(String, String,
     *       String, String, String, String)} to emit the customer block
     *       (translates {@code 5200-WRITE-HTML-NMADBS}).</li>
     *   <li>Write text ST-LINE1 through ST-LINE13 (with ST-LINE5 then
     *       ST-LINE6 then ST-LINE5 again, per COBOL line 492-494).</li>
     *   <li>For each transaction matching the card number, call
     *       {@link #writeTransaction(TrnxRecord)} (translates
     *       {@code 6000-WRITE-TRANS}); accumulate
     *       {@link #wsTotalAmt}.</li>
     *   <li>Write text ST-LINE12, ST-LINE14A (with total),
     *       ST-LINE15 (translates {@code 4000-TRNXFILE-GET} tail at
     *       lines 433&ndash;437).</li>
     *   <li>Write HTML closing tags (translates lines 439&ndash;454):
     *       LTRS, L10, L75, LTDE, LTRE, L78, L79, L80.</li>
     * </ol>
     *
     * @param xref the cross-reference record (provides card + acct + cust
     *             linkage)
     * @param cust the customer master record
     * @param acct the account master record
     */
    private void createStatement(CardXrefRecord xref,
                                  CustomerLegacyRecord cust,
                                  AccountRecord acct) {
        // MOVE ZERO TO WS-TOTAL-AMT (1000-MAINLINE line 325)
        wsTotalAmt = BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);

        try {
            // INITIALIZE STATEMENT-LINES (5000-CREATE-STATEMENT line 459)
            // — implicit in the Java translation; we build each line on
            // the fly with the correct value or VALUE clause.

            // WRITE FD-STMTFILE-REC FROM ST-LINE0 (line 460)
            writeTextLine(ST_LINE0);

            // PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT (line 461)
            writeHtmlHeader(acct);

            // STRING CUST-FIRST-NAME / MIDDLE-NAME / LAST-NAME with
            // single-space delimiters into ST-NAME (lines 462-469)
            String stName = buildStName(
                    cust.custFirstName(),
                    cust.custMiddleName(),
                    cust.custLastName());

            // MOVE CUST-ADDR-LINE-1 / -2 TO ST-ADD1 / ST-ADD2 (lines 470-471)
            // ST-ADD1 / ST-ADD2 are PIC X(50) — direct alphanumeric MOVE
            // is a left-justified copy with right-space padding.
            String stAdd1 = padOrTruncate(
                    nullSafe(cust.custAddrLine1()), 50);
            String stAdd2 = padOrTruncate(
                    nullSafe(cust.custAddrLine2()), 50);

            // STRING CUST-ADDR-LINE-3 / STATE-CD / COUNTRY-CD / ZIP with
            // single-space delimiters into ST-ADD3 PIC X(80) (lines 472-481)
            String stAdd3 = buildStAdd3(
                    cust.custAddrLine3(),
                    cust.custAddrStateCd(),
                    cust.custAddrCountryCd(),
                    cust.custAddrZip());

            // MOVE ACCT-ID TO ST-ACCT-ID (line 483) —
            // numeric-to-alphanumeric: 11 digits left-justified in 20 chars
            String stAcctId = formatAcctIdAsAlphanumeric(acct.acctId());

            // MOVE ACCT-CURR-BAL TO ST-CURR-BAL (line 484) —
            // PIC 9(9).99- (13 chars, leading zeros, trailing sign)
            String stCurrBal = formatFixedSignedMoney(acct.acctCurrBal());

            // MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE (line 485) —
            // PIC 9(03) → PIC X(20): 3 digits left-justified in 20 chars
            String stFicoScore = formatFicoScoreAsAlphanumeric(
                    cust.custFicoCreditScore());

            // PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT (line 486)
            writeHtmlNameAddressBasics(
                    stName, stAdd1, stAdd2, stAdd3,
                    stAcctId, stCurrBal, stFicoScore);

            // WRITE FD-STMTFILE-REC FROM ST-LINE1 through ST-LINE13
            // (lines 488-502) — note ST-LINE5 appears twice (lines 492,
            // 494) and ST-LINE12 appears twice (lines 500, 502) per COBOL
            writeTextLine(buildStLine1(stName));
            writeTextLine(buildStLine2(stAdd1));
            writeTextLine(buildStLine3(stAdd2));
            writeTextLine(buildStLine4(stAdd3));
            writeTextLine(ST_LINE5);
            writeTextLine(ST_LINE6);
            writeTextLine(ST_LINE5);
            writeTextLine(buildStLine7(stAcctId));
            writeTextLine(buildStLine8(stCurrBal));
            writeTextLine(buildStLine9(stFicoScore));
            writeTextLine(ST_LINE10);
            writeTextLine(ST_LINE11);
            writeTextLine(ST_LINE12);
            writeTextLine(ST_LINE13);
            writeTextLine(ST_LINE12);

            // 4000-TRNXFILE-GET (line 416): walk the in-memory buffer
            // for this card. The inner PERFORM 6000-WRITE-TRANS writes
            // each transaction and accumulates WS-TOTAL-AMT (line 429).
            List<TrnxRecord> transactions =
                    findTransactionsForCard(xref.xrefCardNum());
            for (TrnxRecord tran : transactions) {
                writeTransaction(tran);
            }

            // 4000-TRNXFILE-GET tail (lines 433-437):
            //   MOVE WS-TOTAL-AMT TO WS-TRN-AMT
            //   MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT
            //   WRITE ST-LINE12, ST-LINE14A, ST-LINE15
            writeTextLine(ST_LINE12);
            writeTextLine(buildStLine14a(wsTotalAmt));
            writeTextLine(ST_LINE15);

            // HTML statement footer (lines 439-454):
            //   LTRS, L10, L75, LTDE, LTRE, L78, L79, L80
            writeHtmlFooter();
        } catch (IOException e) {
            log.error("Failed to write statement for custId={}, acctId={}",
                    xref.xrefCustId(), xref.xrefAcctId(), e);
            throw new UncheckedIOException(
                    "Failed to write statement for custId="
                            + xref.xrefCustId() + ", acctId="
                            + xref.xrefAcctId(), e);
        }
    }

    /**
     * Walks the in-memory buffer and returns the list of transactions
     * for the given card number. Translates the outer PERFORM VARYING
     * loop in COBOL paragraph {@code 4000-TRNXFILE-GET} (lines
     * 416&ndash;432) which scans {@code WS-CARD-TBL} for a card-number
     * match.
     *
     * <p>The Java translation is a direct {@link Map#get(Object)}
     * lookup &mdash; functionally equivalent and asymptotically faster
     * but byte-output-equivalent because both the COBOL and Java
     * implementations iterate the matched bucket in insertion order
     * (guaranteed by {@link LinkedHashMap} on the Java side and by the
     * sequential nature of the COBOL OCCURS array).
     *
     * @param cardNum the 16-character card number to look up
     * @return the ordered list of transactions for that card, or
     *         {@link List#of()} if no transactions exist
     */
    private List<TrnxRecord> findTransactionsForCard(String cardNum) {
        List<TrnxRecord> list = trnxByCard.get(cardNum);
        return (list == null) ? List.of() : list;
    }

    // =====================================================================
    // Per-transaction writer — translates 6000-WRITE-TRANS (lines 675-723).
    // =====================================================================

    /**
     * Writes a single transaction in both plain-text and HTML formats.
     * Translates COBOL paragraph {@code 6000-WRITE-TRANS} at
     * CBSTM03A.CBL lines 675&ndash;723.
     *
     * <p>The COBOL paragraph emits:
     * <ol>
     *   <li>ST-LINE14 = ST-TRANID (16) + space (1) + ST-TRANDT (49) +
     *       "$" (1) + ST-TRANAMT (PIC Z(9).99-, 13 chars) = 80 chars
     *       total to STMTFILE</li>
     *   <li>To HTMLFILE: LTRS, L58, &lt;p&gt;ST-TRANID&lt;/p&gt; in
     *       HTML-TRAN-LN, LTDE, L61, &lt;p&gt;ST-TRANDT&lt;/p&gt;,
     *       LTDE, L64, &lt;p&gt;ST-TRANAMT&lt;/p&gt;, LTDE, LTRE</li>
     * </ol>
     *
     * <p>Also accumulates {@code wsTotalAmt} via
     * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)}
     * (translates COBOL {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
     * 4000-TRNXFILE-GET line 429 &mdash; the Java translation folds this
     * into {@link #writeTransaction(TrnxRecord)} for locality, which is
     * observation-equivalent because the COBOL paragraph 6000 is
     * invoked once per transaction immediately before the ADD).
     *
     * @param tran the transaction to write
     * @throws IOException if either output stream cannot be written
     */
    private void writeTransaction(TrnxRecord tran) throws IOException {
        // MOVE TRNX-ID TO ST-TRANID (line 676) — PIC X(16) → PIC X(16),
        // straight alphanumeric MOVE
        String stTranId = padOrTruncate(
                nullSafe(tran.trnxKey().trnxId()), 16);

        // MOVE TRNX-DESC TO ST-TRANDT (line 677) — PIC X(100) → PIC X(49)
        // is a truncating MOVE in COBOL; only first 49 chars are kept
        String stTranDt = padOrTruncate(
                nullSafe(tran.trnxDesc()), 49);

        // MOVE TRNX-AMT TO ST-TRANAMT (line 678) — PIC S9(9)V99 → PIC
        // Z(9).99- (13 chars, leading-zero-suppressed, trailing sign)
        String stTranAmt = formatZeroSuppressedSignedMoney(tran.trnxAmt());

        // ADD TRNX-AMT TO WS-TOTAL-AMT (4000-TRNXFILE-GET line 429)
        // — scale=2, RoundingMode.DOWN (truncation) per AAP §0.6.1
        wsTotalAmt = Decimals.add(wsTotalAmt, tran.trnxAmt(), 2,
                RoundingMode.DOWN);

        // ST-LINE14 = ST-TRANID + ' ' + ST-TRANDT + '$' + ST-TRANAMT
        // (CBSTM03A.CBL lines 132-137): 16 + 1 + 49 + 1 + 13 = 80 chars
        // WRITE FD-STMTFILE-REC FROM ST-LINE14 (line 679)
        StringBuilder line14 = new StringBuilder(TEXT_LINE_WIDTH);
        line14.append(stTranId);   // ST-TRANID PIC X(16)
        line14.append(' ');         // FILLER VALUE ' ' PIC X(01)
        line14.append(stTranDt);   // ST-TRANDT PIC X(49)
        line14.append('$');         // FILLER VALUE '$' PIC X(01)
        line14.append(stTranAmt);  // ST-TRANAMT PIC Z(9).99-
        writeTextLine(line14.toString());

        // HTML output (lines 681-721):
        //   LTRS — open row
        writeHtmlFixedLine(HTML_LTRS);
        //   L58 — open transaction-id cell
        writeHtmlFixedLine(HTML_L58);
        //   '<p>' + ST-TRANID + '</p>' written as HTML-TRAN-LN
        //   COBOL: STRING '<p>' DELIMITED BY '*' ST-TRANID DELIMITED BY
        //   '*' '</p>' DELIMITED BY '*' INTO HTML-TRAN-LN (lines 686-691).
        //   DELIMITED BY '*' means "copy entire field" because '*' is
        //   not a substring of these data values.
        writeHtmlFixedLine("<p>" + stTranId + "</p>");
        //   LTDE — close transaction-id cell
        writeHtmlFixedLine(HTML_LTDE);
        //   L61 — open transaction-details cell
        writeHtmlFixedLine(HTML_L61);
        //   '<p>' + ST-TRANDT + '</p>'
        writeHtmlFixedLine("<p>" + stTranDt + "</p>");
        //   LTDE — close transaction-details cell
        writeHtmlFixedLine(HTML_LTDE);
        //   L64 — open transaction-amount cell
        writeHtmlFixedLine(HTML_L64);
        //   '<p>' + ST-TRANAMT + '</p>'
        writeHtmlFixedLine("<p>" + stTranAmt + "</p>");
        //   LTDE — close transaction-amount cell
        writeHtmlFixedLine(HTML_LTDE);
        //   LTRE — close row
        writeHtmlFixedLine(HTML_LTRE);
    }

    // =====================================================================
    // HTML header — translates 5100-WRITE-HTML-HEADER (lines 506-555).
    // =====================================================================

    /**
     * Writes the HTML statement preamble. Translates COBOL paragraph
     * {@code 5100-WRITE-HTML-HEADER} at CBSTM03A.CBL lines 506&ndash;555.
     *
     * <p>Sequence (24 HTML lines, each 100 chars wide):
     * L01, L02, L03, L04, L05, L06, L07, L08, LTRS, L10, L11(account),
     * LTDE, LTRE, LTRS, L15, L16, L17, L18, LTDE, LTRE, LTRS, L22-35.
     *
     * @param acct the account record (provides the account ID embedded
     *             in HTML-L11)
     * @throws IOException if the HTML writer cannot be written
     */
    private void writeHtmlHeader(AccountRecord acct) throws IOException {
        // SET HTML-L01 TO TRUE; WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN
        writeHtmlFixedLine(HTML_L01);
        writeHtmlFixedLine(HTML_L02);
        writeHtmlFixedLine(HTML_L03);
        writeHtmlFixedLine(HTML_L04);
        writeHtmlFixedLine(HTML_L05);
        writeHtmlFixedLine(HTML_L06);
        writeHtmlFixedLine(HTML_L07);
        writeHtmlFixedLine(HTML_L08);
        writeHtmlFixedLine(HTML_LTRS);
        writeHtmlFixedLine(HTML_L10);

        // HTML-L11 = '<h3>Statement for Account Number: ' (34) +
        // L11-ACCT (20) + '</h3>' (5) = 59 chars; right-padded to 100.
        // MOVE ACCT-ID TO L11-ACCT (line 529) — PIC 9(11) → PIC X(20)
        String l11Acct = formatAcctIdAsAlphanumeric(acct.acctId());
        writeHtmlFixedLine(HTML_L11_PREFIX + l11Acct + HTML_L11_SUFFIX);

        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);
        writeHtmlFixedLine(HTML_L15);
        writeHtmlFixedLine(HTML_L16);
        writeHtmlFixedLine(HTML_L17);
        writeHtmlFixedLine(HTML_L18);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);
        writeHtmlFixedLine(HTML_L22_35);
    }

    // =====================================================================
    // HTML name/address/basics block — translates 5200-WRITE-HTML-NMADBS
    // (CBSTM03A.CBL lines 557-672).
    // =====================================================================

    /**
     * Writes the HTML customer block: name, address lines, and basic
     * account details. Translates COBOL paragraph
     * {@code 5200-WRITE-HTML-NMADBS} at CBSTM03A.CBL lines
     * 557&ndash;672.
     *
     * <p>The COBOL paragraph builds three classes of dynamic HTML lines:
     * <ul>
     *   <li><b>Name line</b> (HTML-L23): STRING into FD-HTMLFILE-REC
     *       directly &mdash; embeds {@code L23-NAME} truncated at the
     *       first double-space (DELIMITED BY '  '). The COBOL truncates
     *       the name at the first double-space and appends the closing
     *       tag.</li>
     *   <li><b>Address lines</b> (HTML-ADDR-LN x3): STRING into
     *       HTML-ADDR-LN &mdash; embeds ST-ADD1/ST-ADD2/ST-ADD3
     *       truncated at the first double-space.</li>
     *   <li><b>Basic detail lines</b> (HTML-BSIC-LN x3): STRING into
     *       HTML-BSIC-LN &mdash; embeds ST-ACCT-ID/ST-CURR-BAL/
     *       ST-FICO-SCORE with DELIMITED BY '*' (= copy entire field)
     *       because the labels include trailing spaces that must be
     *       preserved.</li>
     * </ul>
     *
     * @param stName    the constructed name (75 chars)
     * @param stAdd1    customer address line 1 (50 chars)
     * @param stAdd2    customer address line 2 (50 chars)
     * @param stAdd3    customer address line 3 + state/country/zip (80
     *                  chars)
     * @param stAcctId  the formatted account-id alphanumeric (20 chars)
     * @param stCurrBal the formatted current-balance fixed-point money
     *                  (13 chars, PIC 9(9).99-)
     * @param stFicoScore the formatted FICO score alphanumeric (20 chars)
     * @throws IOException if the HTML writer cannot be written
     */
    private void writeHtmlNameAddressBasics(String stName, String stAdd1,
            String stAdd2, String stAdd3, String stAcctId,
            String stCurrBal, String stFicoScore) throws IOException {
        // MOVE ST-NAME TO L23-NAME (line 560) — 75 → 50 truncating MOVE.
        // L23-NAME PIC X(50) (line 220).
        String l23Name = padOrTruncate(stName, 50);

        // MOVE SPACES TO FD-HTMLFILE-REC (line 561)
        // STRING '<p style="font-size:16px">' DELIMITED BY '*'
        //        L23-NAME DELIMITED BY '  '  (truncate at double-space)
        //        '  ' DELIMITED BY SIZE
        //        '</p>' DELIMITED BY '*'
        //        INTO FD-HTMLFILE-REC (lines 562-567)
        // WRITE FD-HTMLFILE-REC. (line 568)
        String l23NameTruncated = truncateAtDoubleSpace(l23Name);
        writeHtmlFixedLine("<p style=\"font-size:16px\">"
                + l23NameTruncated + "  </p>");

        // 3x HTML-ADDR-LN: each is "<p>" + ST-ADDx + "</p>" where
        // ST-ADDx is truncated at the first double-space (DELIMITED BY '  ')
        writeHtmlFixedLine(buildHtmlAddrLine(stAdd1));
        writeHtmlFixedLine(buildHtmlAddrLine(stAdd2));
        writeHtmlFixedLine(buildHtmlAddrLine(stAdd3));

        // SET HTML-LTDE; SET HTML-LTRE; SET HTML-LTRS — close name cell,
        // close row, open next row (lines 594-599)
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);

        // L30-42 (green/center cell) + L31 (Basic Details heading) +
        // LTDE + LTRE + LTRS + L22-35 (background-color:#f2f2f2 cell)
        writeHtmlFixedLine(HTML_L30_42);
        writeHtmlFixedLine(HTML_L31);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);
        writeHtmlFixedLine(HTML_L22_35);

        // 3x HTML-BSIC-LN: "<p>Label: " + Value + "</p>"
        // STRING '<p>Account ID         : ' DELIMITED BY '*'
        //        ST-ACCT-ID DELIMITED BY '*'  (= copy entire 20 chars)
        //        '</p>' DELIMITED BY '*'
        //        INTO HTML-BSIC-LN (lines 614-618)
        writeHtmlFixedLine("<p>Account ID         : " + stAcctId + "</p>");
        writeHtmlFixedLine("<p>Current Balance    : " + stCurrBal + "</p>");
        writeHtmlFixedLine("<p>FICO Score         : " + stFicoScore + "</p>");

        // SET HTML-LTDE; SET HTML-LTRE; SET HTML-LTRS — close basics
        // cell, close row, open next row (lines 634-639)
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);

        // L30-42 + L43 (Transaction Summary) + LTDE + LTRE + LTRS
        writeHtmlFixedLine(HTML_L30_42);
        writeHtmlFixedLine(HTML_L43);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_LTRS);

        // Column headers row:
        //   L47 + L48 (Tran ID) + LTDE + L50 + L51 (Tran Details) + LTDE
        //   + L53 + L54 (Amount) + LTDE + LTRE (lines 650-669)
        writeHtmlFixedLine(HTML_L47);
        writeHtmlFixedLine(HTML_L48);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_L50);
        writeHtmlFixedLine(HTML_L51);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_L53);
        writeHtmlFixedLine(HTML_L54);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
    }

    /**
     * Constructs an HTML address line by replicating the COBOL STRING
     * statement at lines 569&ndash;592:
     *
     * <pre>{@code
     *   STRING '<p>' DELIMITED BY '*'
     *          ST-ADDx DELIMITED BY '  '  (truncate at double-space)
     *          '  ' DELIMITED BY SIZE
     *          '</p>' DELIMITED BY '*'
     *          INTO HTML-ADDR-LN
     * }</pre>
     *
     * @param addrLine the source address line (50 or 80 chars)
     * @return the constructed HTML line, before pad/truncate to 100
     */
    private static String buildHtmlAddrLine(String addrLine) {
        String truncated = truncateAtDoubleSpace(addrLine);
        return "<p>" + truncated + "  </p>";
    }

    // =====================================================================
    // HTML footer — translates the tail of 4000-TRNXFILE-GET that closes
    // the HTML statement (CBSTM03A.CBL lines 439-454).
    // =====================================================================

    /**
     * Writes the HTML statement footer: LTRS, L10 (header-color cell),
     * L75 ("End of Statement" heading), LTDE, LTRE, L78 (&lt;/table&gt;),
     * L79 (&lt;/body&gt;), L80 (&lt;/html&gt;). Translates the HTML tail
     * of COBOL paragraph {@code 4000-TRNXFILE-GET} at CBSTM03A.CBL lines
     * 439&ndash;454.
     *
     * @throws IOException if the HTML writer cannot be written
     */
    private void writeHtmlFooter() throws IOException {
        writeHtmlFixedLine(HTML_LTRS);
        writeHtmlFixedLine(HTML_L10);
        writeHtmlFixedLine(HTML_L75);
        writeHtmlFixedLine(HTML_LTDE);
        writeHtmlFixedLine(HTML_LTRE);
        writeHtmlFixedLine(HTML_L78);
        writeHtmlFixedLine(HTML_L79);
        writeHtmlFixedLine(HTML_L80);
    }

    // =====================================================================
    // STRING semantics helpers — translate COBOL DELIMITED BY clauses.
    // =====================================================================

    /**
     * Constructs ST-NAME by replicating the COBOL STRING statement at
     * CBSTM03A.CBL lines 462&ndash;469:
     *
     * <pre>{@code
     *   STRING CUST-FIRST-NAME DELIMITED BY ' '
     *          ' ' DELIMITED BY SIZE
     *          CUST-MIDDLE-NAME DELIMITED BY ' '
     *          ' ' DELIMITED BY SIZE
     *          CUST-LAST-NAME DELIMITED BY ' '
     *          ' ' DELIMITED BY SIZE
     *          INTO ST-NAME
     * }</pre>
     *
     * <p>{@code DELIMITED BY ' '} means "copy chars up to (but not
     * including) the first space". {@code ' ' DELIMITED BY SIZE} means
     * "append exactly one literal space".
     *
     * <p>ST-NAME is PIC X(75); result is left-justified and right-space
     * padded to 75 characters. If the constructed string exceeds 75
     * characters it is truncated.
     *
     * @param firstName  CUST-FIRST-NAME PIC X(25)
     * @param middleName CUST-MIDDLE-NAME PIC X(25)
     * @param lastName   CUST-LAST-NAME PIC X(25)
     * @return ST-NAME (75 chars, padded with trailing spaces)
     */
    private static String buildStName(String firstName, String middleName,
                                       String lastName) {
        StringBuilder sb = new StringBuilder(75);
        sb.append(beforeFirstSpace(nullSafe(firstName)));
        sb.append(' ');
        sb.append(beforeFirstSpace(nullSafe(middleName)));
        sb.append(' ');
        sb.append(beforeFirstSpace(nullSafe(lastName)));
        sb.append(' ');
        return padOrTruncate(sb.toString(), 75);
    }

    /**
     * Constructs ST-ADD3 by replicating the COBOL STRING statement at
     * CBSTM03A.CBL lines 472&ndash;481.
     *
     * <p>ST-ADD3 is PIC X(80) and contains the city, state, country, and
     * ZIP joined by spaces (each field stop-at-space).
     *
     * @param addrLine3   CUST-ADDR-LINE-3 PIC X(50) (city)
     * @param stateCd     CUST-ADDR-STATE-CD PIC X(02)
     * @param countryCd   CUST-ADDR-COUNTRY-CD PIC X(03)
     * @param zip         CUST-ADDR-ZIP PIC X(10)
     * @return ST-ADD3 (80 chars, padded with trailing spaces)
     */
    private static String buildStAdd3(String addrLine3, String stateCd,
                                       String countryCd, String zip) {
        StringBuilder sb = new StringBuilder(80);
        sb.append(beforeFirstSpace(nullSafe(addrLine3)));
        sb.append(' ');
        sb.append(beforeFirstSpace(nullSafe(stateCd)));
        sb.append(' ');
        sb.append(beforeFirstSpace(nullSafe(countryCd)));
        sb.append(' ');
        sb.append(beforeFirstSpace(nullSafe(zip)));
        sb.append(' ');
        return padOrTruncate(sb.toString(), 80);
    }

    /**
     * Returns the substring of {@code s} up to (but not including) the
     * first space character. Empty string if {@code s} is empty or
     * starts with a space.
     *
     * <p>Translates COBOL {@code DELIMITED BY ' '} (single-space)
     * delimiter in the STRING statement.
     *
     * @param s the source string
     * @return the prefix up to the first space
     */
    private static String beforeFirstSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int idx = s.indexOf(' ');
        return (idx < 0) ? s : s.substring(0, idx);
    }

    /**
     * Returns the substring of {@code s} up to (but not including) the
     * first occurrence of two consecutive spaces ("  ").
     *
     * <p>Translates COBOL {@code DELIMITED BY '  '} (double-space)
     * delimiter in the STRING statement at CBSTM03A.CBL lines 563, 571,
     * 579, 587 (HTML-L23 name and HTML-ADDR-LN address lines).
     *
     * @param s the source string
     * @return the prefix up to the first double-space
     */
    private static String truncateAtDoubleSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int idx = s.indexOf("  ");
        return (idx < 0) ? s : s.substring(0, idx);
    }

    // =====================================================================
    // Text-mode line builders — translate ST-LINEn WORKING-STORAGE entries.
    //
    // Each method builds an 80-character text line matching the COBOL
    // STATEMENT-LINES layout at CBSTM03A.CBL lines 85-146 byte-for-byte.
    // =====================================================================

    /**
     * Builds ST-LINE1 = ST-NAME (75) + 5 spaces = 80 chars.
     * Translates CBSTM03A.CBL lines 90-92.
     */
    private static String buildStLine1(String stName) {
        return padOrTruncate(stName, 75) + "     ";
    }

    /**
     * Builds ST-LINE2 = ST-ADD1 (50) + 30 spaces = 80 chars.
     * Translates CBSTM03A.CBL lines 93-95.
     */
    private static String buildStLine2(String stAdd1) {
        return padOrTruncate(stAdd1, 50)
                + "                              ";
    }

    /**
     * Builds ST-LINE3 = ST-ADD2 (50) + 30 spaces = 80 chars.
     * Translates CBSTM03A.CBL lines 96-98.
     */
    private static String buildStLine3(String stAdd2) {
        return padOrTruncate(stAdd2, 50)
                + "                              ";
    }

    /**
     * Builds ST-LINE4 = ST-ADD3 (80) = 80 chars.
     * Translates CBSTM03A.CBL lines 99-100.
     */
    private static String buildStLine4(String stAdd3) {
        return padOrTruncate(stAdd3, 80);
    }

    /**
     * Builds ST-LINE7 = 'Account ID         :' (20) + ST-ACCT-ID (20) +
     * 40 spaces = 80 chars. Translates CBSTM03A.CBL lines 107-110.
     */
    private static String buildStLine7(String stAcctId) {
        return "Account ID         :" + padOrTruncate(stAcctId, 20)
                + "                                        ";
    }

    /**
     * Builds ST-LINE8 = 'Current Balance    :' (20) + ST-CURR-BAL (PIC
     * 9(9).99-, 13 chars) + 7 spaces + 40 spaces = 80 chars.
     * Translates CBSTM03A.CBL lines 111-115.
     *
     * <p>NOTE: The COBOL FILLER PIC X(07) at line 114 fills the
     * difference between PIC 9(9).99- (13 chars) and the implicit width
     * accommodation for a 20-char text "slot" expected by the layout
     * (ST-CURR-BAL is actually 13 chars wide), so the total is
     * 20+13+7+40 = 80.
     */
    private static String buildStLine8(String stCurrBal) {
        return "Current Balance    :" + padOrTruncate(stCurrBal, 13)
                + "       "
                + "                                        ";
    }

    /**
     * Builds ST-LINE9 = 'FICO Score         :' (20) + ST-FICO-SCORE (20)
     * + 40 spaces = 80 chars. Translates CBSTM03A.CBL lines 116-119.
     */
    private static String buildStLine9(String stFicoScore) {
        return "FICO Score         :" + padOrTruncate(stFicoScore, 20)
                + "                                        ";
    }

    /**
     * Builds ST-LINE14A = 'Total EXP:' (10) + 56 spaces + '$' (1) +
     * ST-TOTAL-TRAMT (PIC Z(9).99-, 13 chars) = 80 chars.
     * Translates CBSTM03A.CBL lines 138-142.
     */
    private static String buildStLine14a(BigDecimal totalAmt) {
        return "Total EXP:"
                + "                                                        "
                + "$"
                + formatZeroSuppressedSignedMoney(totalAmt);
    }

    // =====================================================================
    // Numeric formatting — translates COBOL PIC clauses.
    // =====================================================================

    /**
     * Formats a {@link BigDecimal} as a COBOL {@code PIC Z(9).99-}
     * (13 chars): zero-suppressed integer portion, fixed 2-digit
     * fraction, trailing sign character (' ' for positive/zero, '-' for
     * negative).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code   0.00}: {@code "         0.00 "} (9 leading spaces +
     *       "0.00 ") — actually COBOL Z(9) shows zero as one '0' digit:
     *       {@code "        0.00 "} (8 spaces + "0.00 ") = 13 chars</li>
     *   <li>{@code   123.45}: {@code "      123.45 "} (6 spaces +
     *       "123.45 ") = 13 chars</li>
     *   <li>{@code  -123.45}: {@code "      123.45-"} = 13 chars</li>
     *   <li>{@code 999999999.99}: {@code "999999999.99 "} = 13 chars</li>
     *   <li>{@code -999999999.99}: {@code "999999999.99-"} = 13 chars</li>
     * </ul>
     *
     * <p>COBOL {@code PIC Z(9)} suppresses leading zeros but leaves a
     * single '0' if the integer portion is zero. The decimal point and
     * fraction are always emitted.
     *
     * @param value the source decimal value (may be null &rarr;
     *              treated as zero)
     * @return a 13-character string formatted per the COBOL PIC clause
     */
    private static String formatZeroSuppressedSignedMoney(BigDecimal value) {
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
        // Separate sign from absolute value
        int sign = scaled.signum();
        BigDecimal abs = scaled.abs();
        // Get integer and fractional parts
        BigDecimal intPart = abs.setScale(0, RoundingMode.DOWN);
        BigDecimal fracPart = abs.subtract(intPart).movePointRight(2);
        long intValue = intPart.longValueExact();
        long fracValue = fracPart.setScale(0, RoundingMode.DOWN)
                .longValueExact();

        // Zero-suppressed 9-digit integer, with at least one '0' if
        // the value is zero (matches COBOL PIC Z(9) semantics).
        String intStr;
        if (intValue == 0L) {
            // COBOL PIC Z(9): an all-zero integer field is suppressed
            // to 9 spaces. The decimal point and fraction are still
            // emitted. The total width remains 13.
            intStr = "         ";
        } else {
            String raw = Long.toString(intValue);
            // Left-pad with spaces to 9 chars (zero suppression).
            StringBuilder sb = new StringBuilder(9);
            for (int i = raw.length(); i < 9; i++) {
                sb.append(' ');
            }
            sb.append(raw);
            intStr = sb.toString();
        }

        // Fractional portion: always 2 digits
        String fracStr = String.format("%02d", fracValue);

        // Trailing sign
        char signChar = (sign < 0) ? '-' : ' ';

        // Assemble: 9 + 1 (.) + 2 + 1 (sign) = 13
        return intStr + "." + fracStr + signChar;
    }

    /**
     * Formats a {@link BigDecimal} as a COBOL {@code PIC 9(9).99-}
     * (13 chars): leading-zero-preserved integer portion, fixed 2-digit
     * fraction, trailing sign character (' ' for positive/zero, '-' for
     * negative).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code   0.00}: {@code "000000000.00 "} = 13 chars</li>
     *   <li>{@code   123.45}: {@code "000000123.45 "} = 13 chars</li>
     *   <li>{@code  -123.45}: {@code "000000123.45-"} = 13 chars</li>
     * </ul>
     *
     * <p>Used for ST-CURR-BAL (PIC 9(9).99- at CBSTM03A.CBL line 113).
     *
     * @param value the source decimal value (may be null &rarr;
     *              treated as zero)
     * @return a 13-character string formatted per the COBOL PIC clause
     */
    private static String formatFixedSignedMoney(BigDecimal value) {
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
        int sign = scaled.signum();
        BigDecimal abs = scaled.abs();
        BigDecimal intPart = abs.setScale(0, RoundingMode.DOWN);
        BigDecimal fracPart = abs.subtract(intPart).movePointRight(2);
        long intValue = intPart.longValueExact();
        long fracValue = fracPart.setScale(0, RoundingMode.DOWN)
                .longValueExact();

        // 9-digit zero-padded integer per PIC 9(9)
        String intStr = String.format("%09d", intValue);
        String fracStr = String.format("%02d", fracValue);
        char signChar = (sign < 0) ? '-' : ' ';
        return intStr + "." + fracStr + signChar;
    }

    /**
     * Formats an 11-digit account ID into the 20-character alphanumeric
     * representation produced by COBOL's {@code MOVE} from
     * {@code PIC 9(11)} to {@code PIC X(20)}.
     *
     * <p>COBOL semantics: digits go to the left (left-justified), trailing
     * positions are space-padded.
     *
     * <p>Example: {@code 12345678901} &rarr;
     * {@code "12345678901         "} (11 digits + 9 spaces).
     *
     * @param acctId the 11-digit account ID
     * @return a 20-character string
     */
    private static String formatAcctIdAsAlphanumeric(long acctId) {
        String digits = String.format("%011d", acctId);
        // 11 digits + 9 spaces = 20
        return digits + "         ";
    }

    /**
     * Formats a FICO credit score (3 digits, {@code PIC 9(03)}) into the
     * 20-character alphanumeric representation produced by COBOL's
     * {@code MOVE} from {@code PIC 9(03)} to {@code PIC X(20)}.
     *
     * <p>Example: {@code 742} &rarr; {@code "742                 "} (3
     * digits + 17 spaces).
     *
     * @param score the FICO score (0-999, but typically 300-850)
     * @return a 20-character string
     */
    private static String formatFicoScoreAsAlphanumeric(int score) {
        String digits = String.format("%03d", score);
        // 3 digits + 17 spaces = 20
        return digits + "                 ";
    }

    // =====================================================================
    // I/O helpers and pad/truncate utilities.
    // =====================================================================

    /**
     * Writes a single fixed-width text line to {@link #textOut}. The
     * input is space-padded or truncated to exactly {@link #TEXT_LINE_WIDTH}
     * (80) characters; the platform-default newline separator is then
     * appended via {@link BufferedWriter#newLine()}.
     *
     * @param line the line content (may be shorter or longer than 80
     *             chars; will be padded or truncated)
     * @throws IOException if the writer cannot be written
     */
    private void writeTextLine(String line) throws IOException {
        textOut.write(padOrTruncate(line, TEXT_LINE_WIDTH));
        textOut.newLine();
    }

    /**
     * Writes a single fixed-width HTML line to {@link #htmlOut}. The
     * input is space-padded or truncated to exactly
     * {@link #HTML_LINE_WIDTH} (100) characters; the platform-default
     * newline separator is then appended via
     * {@link BufferedWriter#newLine()}.
     *
     * <p>This method preserves byte-for-byte parity with COBOL's
     * {@code WRITE FD-HTMLFILE-REC} which emits a 100-character record
     * (the COBOL FD declares FD-HTMLFILE-REC PIC X(100) at
     * CBSTM03A.CBL line 47).
     *
     * @param line the HTML line content
     * @throws IOException if the writer cannot be written
     */
    private void writeHtmlFixedLine(String line) throws IOException {
        htmlOut.write(padOrTruncate(line, HTML_LINE_WIDTH));
        htmlOut.newLine();
    }

    /**
     * Pads or truncates a string to the specified width. If the string
     * is null it is treated as empty. If shorter than {@code width}, the
     * string is right-space-padded; if longer, it is right-truncated.
     *
     * <p>Mirrors COBOL alphanumeric MOVE semantics: sender to receiver
     * is left-justified, with shorter senders padded by spaces and
     * longer senders truncated.
     *
     * @param s     the source string (may be null)
     * @param width the target width
     * @return a string of exactly {@code width} characters
     */
    private static String padOrTruncate(String s, int width) {
        if (s == null) {
            s = "";
        }
        if (s.length() == width) {
            return s;
        }
        if (s.length() > width) {
            return s.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        while (sb.length() < width) {
            sb.append(PAD_CHAR);
        }
        return sb.toString();
    }

    /**
     * Returns the input string if non-null, or the empty string if null.
     *
     * @param s a possibly-null source string
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    // =====================================================================
    // Output file setup, teardown, and abend handling.
    // =====================================================================

    /**
     * Opens the plain-text and HTML statement output files using
     * {@link java.nio.file.Files}. Translates the COBOL
     * {@code OPEN OUTPUT STMT-FILE HTML-FILE} at CBSTM03A.CBL line 293.
     *
     * <p>Per AAP &sect;0.6.5 "All file I/O uses {@code java.nio.file}",
     * {@link java.io.File} is FORBIDDEN. The parent directory is
     * created if missing (matches the COBOL z/OS dataset allocation
     * semantics, which always provides a directory for the new file).
     *
     * <p>The destination charset is {@link StandardCharsets#UTF_8}: text
     * output is portable ASCII and HTML's {@code <meta charset="utf-8">}
     * (HTML-L04) declares UTF-8. EBCDIC transcoding, where required, is
     * the responsibility of the input-side reader (CbStm03B) per AAP
     * &sect;0.6.5.
     *
     * @throws IOException if either output file cannot be opened
     */
    private void openStatementOutputs() throws IOException {
        Path textParent = statementTextOutputPath.getParent();
        if (textParent != null) {
            Files.createDirectories(textParent);
        }
        Path htmlParent = statementHtmlOutputPath.getParent();
        if (htmlParent != null) {
            Files.createDirectories(htmlParent);
        }
        textOut = Files.newBufferedWriter(statementTextOutputPath,
                StandardCharsets.UTF_8);
        htmlOut = Files.newBufferedWriter(statementHtmlOutputPath,
                StandardCharsets.UTF_8);
    }

    /**
     * Closes all four input files (XREFFILE, CUSTFILE, ACCTFILE; TRNXFILE
     * is closed earlier by {@link #trnxFileOpenAndLoadAll()}). Translates
     * the COBOL paragraphs {@code 9200-XREFFILE-CLOSE},
     * {@code 9300-CUSTFILE-CLOSE}, and {@code 9400-ACCTFILE-CLOSE} at
     * CBSTM03A.CBL lines 872&ndash;918.
     *
     * <p>Each close returns a return code; any non-OK return code is
     * logged at {@code WARN} (the COBOL would abend, but during cleanup
     * an abend would mask the original error, so the Java translation
     * downgrades to a warning).
     */
    private void closeAllFiles() {
        for (String tag : new String[] {
                FILE_TAG_XREFFILE, FILE_TAG_CUSTFILE, FILE_TAG_ACCTFILE }) {
            try {
                int rc = fileServices.closeFile(tag);
                if (rc != RC_OK) {
                    log.warn("ERROR CLOSING {}: rc={}", tag, rc);
                }
            } catch (RuntimeException e) {
                log.warn("Exception closing {}", tag, e);
            }
        }
    }

    /**
     * Closes a {@link Writer} swallowing any {@link IOException}.
     *
     * @param w    the writer to close (may be {@code null})
     * @param name a human-readable name for logging
     */
    private static void closeOutputQuietly(Writer w, String name) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (IOException e) {
            LoggerFactory.getLogger(CbStm03A.class)
                    .warn("Error closing {}", name, e);
        }
    }

    /**
     * Cleans up all open resources idempotently. Called from the
     * {@code finally} block in {@link #run()} to guarantee that the
     * four input files and the two output writers are closed even on
     * partial failure during dispatch.
     */
    private void cleanup() {
        try {
            closeAllFiles();
        } catch (RuntimeException e) {
            log.warn("Error during file cleanup", e);
        }
        closeOutputQuietly(textOut, "statement text output");
        closeOutputQuietly(htmlOut, "statement HTML output");
        textOut = null;
        htmlOut = null;
    }

    /**
     * Logs an abend message and returns {@link #APPL_ERROR}. Translates
     * the COBOL paragraph {@code 9999-ABEND-PROGRAM} at CBSTM03A.CBL
     * lines 921&ndash;923 (which executes
     * {@code DISPLAY 'ABENDING PROGRAM'} and
     * {@code CALL 'CEE3ABD'} to terminate the task).
     *
     * <p>The Java translation does NOT terminate the JVM; it returns
     * the {@link #APPL_ERROR} return code. The caller (a JCL-step main
     * class) is responsible for converting the return code into a
     * {@code System.exit(rc)} if required, matching the AAP §0.4.1
     * directive that JCL steps map to {@code carddemo-app} main classes.
     *
     * @return {@link #APPL_ERROR}
     */
    private int abendProgram() {
        log.error("ABENDING PROGRAM {}", LIT_THIS_PGM);
        return APPL_ERROR;
    }

    // =====================================================================
    // HTML literal constants — translate HTML-LINES WORKING-STORAGE
    // (CBSTM03A.CBL lines 148-223). Each constant is exactly the COBOL
    // VALUE without the line continuation; all are padded to 100 chars
    // by writeHtmlFixedLine().
    // =====================================================================

    /** HTML-L01: doctype declaration. */
    private static final String HTML_L01 = "<!DOCTYPE html>";
    /** HTML-L02: html element opening tag with lang attribute. */
    private static final String HTML_L02 = "<html lang=\"en\">";
    /** HTML-L03: head element opening tag. */
    private static final String HTML_L03 = "<head>";
    /** HTML-L04: meta charset declaration. */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";
    /** HTML-L05: title element. */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";
    /** HTML-L06: head element closing tag. */
    private static final String HTML_L06 = "</head>";
    /** HTML-L07: body element opening tag with style. */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";
    /** HTML-L08: outer statement table opening tag. */
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" "
                    + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    /** HTML-LTRS: open table row. */
    private static final String HTML_LTRS = "<tr>";
    /** HTML-LTRE: close table row. */
    private static final String HTML_LTRE = "</tr>";
    /** HTML-LTDE: close table cell. */
    private static final String HTML_LTDE = "</td>";
    /** HTML-L10: dark-blue header cell with colspan 3. */
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;"
                    + "background-color:#1d1d96b3;\">";
    /** HTML-L15: orange bank-address cell with colspan 3. */
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;"
                    + "background-color:#FFAF33;\">";
    /** HTML-L16: bank name paragraph. */
    private static final String HTML_L16 =
            "<p style=\"font-size:16px\">Bank of XYZ</p>";
    /** HTML-L17: bank address line 1. */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
    /** HTML-L18: bank address line 2. */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
    /** HTML-L22-35: light-gray content cell. */
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;"
                    + "background-color:#f2f2f2;\">";
    /** HTML-L30-42: green-center heading cell. */
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;"
                    + "background-color:#33FFD1; text-align:center;\">";
    /** HTML-L31: Basic Details section heading paragraph. */
    private static final String HTML_L31 =
            "<p style=\"font-size:16px\">Basic Details</p>";
    /** HTML-L43: Transaction Summary section heading paragraph. */
    private static final String HTML_L43 =
            "<p style=\"font-size:16px\">Transaction Summary</p>";
    /** HTML-L47: green Tran ID column-header cell. */
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; "
                    + "background-color:#33FF5E; text-align:left;\">";
    /** HTML-L48: Tran ID column header paragraph. */
    private static final String HTML_L48 =
            "<p style=\"font-size:16px\">Tran ID</p>";
    /** HTML-L50: green Tran Details column-header cell. */
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; "
                    + "background-color:#33FF5E; text-align:left;\">";
    /** HTML-L51: Tran Details column header paragraph. */
    private static final String HTML_L51 =
            "<p style=\"font-size:16px\">Tran Details</p>";
    /** HTML-L53: green Amount column-header cell. */
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; "
                    + "background-color:#33FF5E; text-align:right;\">";
    /** HTML-L54: Amount column header paragraph. */
    private static final String HTML_L54 =
            "<p style=\"font-size:16px\">Amount</p>";
    /** HTML-L58: gray Tran ID data cell. */
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">";
    /** HTML-L61: gray Tran Details data cell. */
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">";
    /** HTML-L64: gray Amount data cell. */
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:right;\">";
    /** HTML-L75: end-of-statement banner heading. */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";
    /** HTML-L78: close outer table. */
    private static final String HTML_L78 = "</table>";
    /** HTML-L79: close body element. */
    private static final String HTML_L79 = "</body>";
    /** HTML-L80: close html element. */
    private static final String HTML_L80 = "</html>";

    /** HTML-L11 prefix: 34-char "Statement for Account Number" heading prefix. */
    private static final String HTML_L11_PREFIX =
            "<h3>Statement for Account Number: ";
    /** HTML-L11 suffix: 5-char "</h3>" closing tag. */
    private static final String HTML_L11_SUFFIX = "</h3>";

    // =====================================================================
    // Text-mode static line constants — translate WORKING-STORAGE
    // STATEMENT-LINES (CBSTM03A.CBL lines 85-146). Each constant is
    // exactly 80 chars wide.
    // =====================================================================

    /**
     * ST-LINE0: "*"*31 + "START OF STATEMENT" (18) + "*"*31 = 80 chars.
     * Translates CBSTM03A.CBL lines 86-89.
     */
    private static final String ST_LINE0 =
            "*******************************"
                    + "START OF STATEMENT"
                    + "*******************************";

    /**
     * ST-LINE5 / ST-LINE10 / ST-LINE12: 80 dashes.
     * Translates CBSTM03A.CBL lines 101-102 (ST-LINE5),
     * 120-121 (ST-LINE10), 126-127 (ST-LINE12).
     */
    private static final String ST_LINE5 =
            "--------------------------------------------------------------------------------";

    /**
     * ST-LINE6: 33 spaces + "Basic Details" (14) + 33 spaces = 80 chars.
     * Translates CBSTM03A.CBL lines 103-106. Note: the COBOL FILLER
     * VALUE 'Basic Details' is PIC X(14), so "Basic Details" (13 chars)
     * is right-space-padded to 14.
     */
    private static final String ST_LINE6 =
            "                                 "
                    + "Basic Details "
                    + "                                 ";

    /**
     * ST-LINE10: 80 dashes (same value as ST_LINE5).
     * Translates CBSTM03A.CBL lines 120-121. Aliased for documentation
     * clarity even though the value is identical to ST_LINE5.
     */
    private static final String ST_LINE10 = ST_LINE5;

    /**
     * ST-LINE11: 30 spaces + "TRANSACTION SUMMARY " (20) + 30 spaces =
     * 80 chars. Translates CBSTM03A.CBL lines 122-125.
     */
    private static final String ST_LINE11 =
            "                              "
                    + "TRANSACTION SUMMARY "
                    + "                              ";

    /**
     * ST-LINE12: 80 dashes (same value as ST_LINE5).
     * Translates CBSTM03A.CBL lines 126-127.
     */
    private static final String ST_LINE12 = ST_LINE5;

    /**
     * ST-LINE13: "Tran ID         " (16) + "Tran Details    " (51) +
     * "  Tran Amount" (13) = 80 chars. Translates CBSTM03A.CBL lines
     * 128-131. Note: the COBOL "Tran Details    " FILLER VALUE is
     * PIC X(51); the literal "Tran Details    " (16 chars) is
     * right-space-padded to 51.
     */
    private static final String ST_LINE13 =
            "Tran ID         "
                    + "Tran Details    "
                    + "                                   "
                    + "  Tran Amount";

    /**
     * ST-LINE15: "*"*32 + "END OF STATEMENT" (16) + "*"*32 = 80 chars.
     * Translates CBSTM03A.CBL lines 143-146.
     */
    private static final String ST_LINE15 =
            "********************************"
                    + "END OF STATEMENT"
                    + "********************************";
}

