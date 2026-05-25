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
package com.blitzy.carddemo.application.account;

// JEP 511 (finalized in Java 25) — single declaration imports every
// package exported by the java.base module. Admits Objects (used by
// the constructor injection guard), Iterator (used by the read loop),
// and Stream (used as the lazy AutoCloseable iteration over XREFFILE).
// Per AAP §0.6.7 this is the mandated idiom for files that touch many
// java.* packages.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the COBOL batch program {@code CBACT03C}
 * ({@code app/cbl/CBACT03C.cbl}) &mdash; "Read and print account cross
 * reference data file."
 *
 * <p>The COBOL program walks the {@code XREFFILE} (CARDXREF) VSAM KSDS
 * sequentially and dumps every record to STDOUT. This Java class preserves
 * the structure of the COBOL {@code PROCEDURE DIVISION} verbatim: each
 * named paragraph is translated to a private method on this class, each
 * {@code WORKING-STORAGE} item becomes a private field or a public
 * {@code static final} constant (when the COBOL declaration is a literal
 * value), and the {@code SELECT}/{@code FD} access path becomes a
 * constructor-injected {@link CardXrefRepository} port.
 *
 * <h2>Paragraph-to-method mapping (AAP &sect;0.1.2)</h2>
 * <table>
 *   <caption>COBOL paragraph &harr; Java method</caption>
 *   <tr><th>COBOL paragraph</th>                            <th>Java method</th>                                <th>Source range</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main flow</td>       <td>{@link #run()}</td>                             <td>L70-L87</td></tr>
 *   <tr><td>{@code 0000-XREFFILE-OPEN}</td>                 <td>{@link #xrefFileOpen()}</td>                    <td>L118-L134</td></tr>
 *   <tr><td>{@code 1000-XREFFILE-GET-NEXT}</td>             <td>{@link #xrefFileReadLoop(Stream)}</td>          <td>L92-L116</td></tr>
 *   <tr><td>{@code 9000-XREFFILE-CLOSE}</td>                <td>{@link #xrefFileClose(Stream)}</td>             <td>L136-L152</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td>             <td>{@link #displayIoStatus(String)}</td>           <td>L161-L174</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM}</td>                 <td>{@link #zAbendProgram()}</td>                   <td>L154-L158</td></tr>
 * </table>
 *
 * <h2>DOUBLE-DISPLAY anomaly preserved (AAP &sect;0.7.1)</h2>
 * Unlike the sibling sequential dump program CBACT02C &mdash; which
 * displays each record exactly once because the inner DISPLAY at
 * {@code 1000-CARDFILE-GET-NEXT} is commented out &mdash; CBACT03C
 * displays each successfully-read record <strong>TWICE</strong>:
 * <ul>
 *   <li>Once from inside the {@code 1000-XREFFILE-GET-NEXT} paragraph
 *       at line 96 of the COBOL source:
 *       <pre>{@code
 *           IF  XREFFILE-STATUS = '00'
 *               MOVE 0 TO APPL-RESULT
 *               DISPLAY CARD-XREF-RECORD          <-- emission #1
 *           ELSE ...
 *       }</pre>
 *   </li>
 *   <li>Once from the main loop at line 78, AFTER the GET-NEXT returns
 *       without setting END-OF-FILE:
 *       <pre>{@code
 *           IF  END-OF-FILE = 'N'
 *               PERFORM 1000-XREFFILE-GET-NEXT
 *               IF  END-OF-FILE = 'N'
 *                   DISPLAY CARD-XREF-RECORD       <-- emission #2
 *               END-IF
 *           END-IF
 *       }</pre>
 *   </li>
 * </ul>
 * The Java translation in {@link #xrefFileReadLoop(Stream)} preserves
 * this DOUBLE-DISPLAY pattern verbatim per the Refactor Discipline
 * Guidelines (AAP &sect;0.7.1) &mdash; <em>"Preserve existing
 * functionality and behavior exactly as-is, including edge cases ..."</em>.
 * This deliberate-emission-doubled pattern is also documented in
 * {@code java/MIGRATION_NOTES.md}. The same anomaly is present in
 * CBACT01C and is preserved in {@code CbAct01C} by the same convention.
 *
 * <h2>PAN handling (AAP &sect;0.7.2)</h2>
 * The {@link CardXrefRecord#toString()} override applies PAN masking
 * automatically (all but the last 4 digits of {@code XREF-CARD-NUM} are
 * replaced with {@code '*'}). The {@link #xrefFileReadLoop(Stream)}
 * method emits the record via SLF4J's parameterised logging &mdash;
 * {@code log.info("XREF-RECORD: {}", record)} &mdash; so the operator's
 * view through the logger NEVER sees the cleartext PAN. The cleartext
 * bytes never leave the {@code CardXrefRecord.xrefCardNum()} component;
 * per AAP &sect;0.7.2 the cleartext PAN MUST NOT appear in any log line.
 *
 * <h2>Return code semantics (corresponds to COBOL {@code APPL-RESULT} 88-levels)</h2>
 * The COBOL program returns control to the OS via {@code GOBACK} without
 * a return code, but it sets the {@code APPL-RESULT} cell to one of three
 * values along the way: 0 (success), 16 (end-of-file), or 12 (error).
 * The Java translation surfaces these values to the caller via
 * {@link #run()}'s {@code int} return type so that the composition root
 * (a {@code main} method in {@code carddemo-app}) can map them to a
 * process exit code per AAP &sect;0.4.1. {@link #APPL_AOK} (0) is
 * returned on normal completion; {@link #APPL_ERROR} (12) is returned on
 * any I/O failure that would have invoked {@code 9999-ABEND-PROGRAM} in
 * COBOL.
 *
 * <h2>Constructor injection (AAP &sect;0.6.12)</h2>
 * This class accepts its sole collaborator &mdash; the
 * {@link CardXrefRepository} port &mdash; through constructor injection.
 * No Spring container, no {@code @Autowired}, no service locator. The
 * composition root in {@code carddemo-app} (specifically
 * {@code ReadCardXrefDumpApp}, translated from {@code app/jcl/READXREF.jcl})
 * wires the chosen adapter implementation (file-based by default per
 * AAP &sect;0.5.1) at startup.
 *
 * <h2>Thread safety</h2>
 * Instances of this class are NOT safe for concurrent use from multiple
 * threads &mdash; mirroring the COBOL program's single-threaded
 * {@code WORKING-STORAGE} semantics. The class is {@code final} to
 * discourage subclasses that might erroneously assume otherwise.
 *
 * <h2>No virtual-thread fan-out</h2>
 * Per AAP &sect;0.6.6 (<em>"Any reordering changes observable output and
 * is FORBIDDEN"</em>) the {@link #xrefFileReadLoop(Stream)} method
 * iterates sequentially in primary-key order. The DOUBLE-DISPLAY pattern
 * requires deterministic interleaving of emission #1 (from paragraph
 * 1000) and emission #2 (from the main loop) per record; virtual-thread
 * fan-out would scramble that interleaving and break byte-for-byte
 * parity with the COBOL baseline.
 *
 * @see CardXrefRepository#streamSequential()
 * @see CardXrefRecord
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT03C",
        sourcePath = "app/cbl/CBACT03C.cbl",
        translationDate = "2025-01-15",
        notes = "Sequential CARDXREF reader. Preserves DOUBLE-DISPLAY anomaly (record displayed twice per read; "
              + "same as CBACT01C)."
)
public final class CbAct03C {

    // ------------------------------------------------------------------
    // WORKING-STORAGE literals translated to public class constants.
    // Names mirror the COBOL items so that text searches for a COBOL
    // identifier will surface both the original source and the Java
    // translation site.
    // ------------------------------------------------------------------

    /**
     * COBOL {@code PROGRAM-ID} of the source program. Used in the
     * START / END trace lines emitted by {@link #run()}, matching the
     * literal text of the COBOL {@code DISPLAY} statements at
     * {@code app/cbl/CBACT03C.cbl} lines 71 and 85.
     */
    public static final String LIT_THIS_PGM = "CBACT03C";

    /**
     * Symbolic name of the input dataset, mirroring the
     * {@code ASSIGN TO XREFFILE} clause of the COBOL {@code SELECT}
     * statement at {@code app/cbl/CBACT03C.cbl} line 29. Used in the
     * "ERROR OPENING/READING/CLOSING XREFFILE" log lines so that the
     * dataset identity is preserved in operator output.
     */
    public static final String LIT_FILE_NAME = "XREFFILE";

    /**
     * COBOL FILE STATUS "successful operation" code. Corresponds to
     * the {@code XREFFILE-STATUS = '00'} test at lines 94, 121, and 139
     * of {@code app/cbl/CBACT03C.cbl}. Preserved as a String constant
     * because FILE STATUS in COBOL is a two-byte alphanumeric field
     * (PIC XX), not a numeric.
     */
    public static final String STATUS_OK = "00";

    /**
     * COBOL FILE STATUS "end-of-file" code. Corresponds to the
     * {@code XREFFILE-STATUS = '10'} test at line 98 of
     * {@code app/cbl/CBACT03C.cbl}.
     */
    public static final String STATUS_EOF = "10";

    /**
     * Application return code "all OK" &mdash; corresponds to the
     * COBOL {@code 88 APPL-AOK VALUE 0} declaration at line 62 of
     * {@code app/cbl/CBACT03C.cbl}. Returned by {@link #run()} on
     * successful end-to-end execution.
     */
    public static final int APPL_AOK = 0;

    /**
     * Application return code "end-of-file" &mdash; corresponds to the
     * COBOL {@code 88 APPL-EOF VALUE 16} declaration at line 63 of
     * {@code app/cbl/CBACT03C.cbl}. Not returned by {@link #run()} in
     * the Java translation (the stream completes naturally at EOF and
     * {@link #run()} returns {@link #APPL_AOK} (0)); the constant is
     * exposed for completeness and for callers that inspect the EOF
     * condition explicitly.
     */
    public static final int APPL_EOF = 16;

    /**
     * Application return code "error" &mdash; corresponds to the COBOL
     * {@code MOVE 12 TO APPL-RESULT} branches at lines 101, 124, and 142
     * of {@code app/cbl/CBACT03C.cbl}. Returned by {@link #run()} when
     * an I/O failure causes the COBOL {@code 9999-ABEND-PROGRAM}
     * paragraph to be invoked.
     */
    public static final int APPL_ERROR = 12;

    /**
     * Timing parameter passed to the LE service {@code CEE3ABD}.
     * Corresponds to the COBOL {@code MOVE 0 TO TIMING} statement at
     * line 156 of {@code app/cbl/CBACT03C.cbl}. A value of 0 requests
     * immediate (non-deferred) abend in the LE convention. The Java
     * translation does not invoke {@code CEE3ABD}; the constant is
     * exposed so that downstream operators can correlate logs with the
     * original COBOL abend parameters.
     */
    public static final int CEE3ABD_TIMING = 0;

    /**
     * Abend code passed to the LE service {@code CEE3ABD}. Corresponds
     * to the COBOL {@code MOVE 999 TO ABCODE} statement at line 157 of
     * {@code app/cbl/CBACT03C.cbl}. The Java translation does not
     * invoke {@code CEE3ABD}; the constant is exposed so that downstream
     * operators can correlate logs with the original COBOL abend code.
     */
    public static final int CEE3ABD_ABCODE = 999;

    // ------------------------------------------------------------------
    // Static logger and instance fields.
    // ------------------------------------------------------------------

    /**
     * SLF4J logger used as the destination for the translated COBOL
     * {@code DISPLAY} statements. Backed by the runtime implementation
     * supplied by the composition root (logback-classic per AAP
     * &sect;0.5.1).
     */
    private static final Logger log = LoggerFactory.getLogger(CbAct03C.class);

    /**
     * The XREFFILE access port. Provided via constructor injection; the
     * adapter implementation is supplied by the composition root.
     */
    private final CardXrefRepository cardXrefRepository;

    // ------------------------------------------------------------------
    // Constructor (constructor injection per AAP §0.6.12).
    // ------------------------------------------------------------------

    /**
     * Creates a new {@code CbAct03C} program instance bound to the
     * supplied {@link CardXrefRepository} port. The port supplies the
     * sequential view over XREFFILE used by the COBOL
     * {@code 0000-XREFFILE-OPEN} / {@code 1000-XREFFILE-GET-NEXT} /
     * {@code 9000-XREFFILE-CLOSE} paragraphs.
     *
     * <p>Per AAP &sect;0.6.12 this is the only injection point: there
     * is no Spring container, no {@code @Autowired}, no service locator.
     * The composition root (in {@code carddemo-app}) selects and wires
     * the appropriate adapter implementation at startup.
     *
     * @param cardXrefRepository the port providing sequential XREFFILE
     *                           access; never {@code null}
     * @throws NullPointerException if {@code cardXrefRepository} is
     *                              {@code null}
     */
    public CbAct03C(CardXrefRepository cardXrefRepository) {
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "cardXrefRepository");
    }

    // ------------------------------------------------------------------
    // PROCEDURE DIVISION main flow — translated as the public run()
    // method. Corresponds to lines 70-87 of CBACT03C.cbl.
    // ------------------------------------------------------------------

    /**
     * Drives the program as the COBOL {@code PROCEDURE DIVISION} does:
     * opens XREFFILE, walks every record sequentially while emitting a
     * <strong>DOUBLE</strong>-DISPLAY per record (preserving the COBOL
     * anomaly), closes XREFFILE, and returns the appropriate
     * {@code APPL-RESULT} value.
     *
     * <p>Translates the COBOL flow at lines 70-87:
     * <pre>{@code
     *   DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
     *   PERFORM 0000-XREFFILE-OPEN.
     *   PERFORM UNTIL END-OF-FILE = 'Y'
     *       IF  END-OF-FILE = 'N'
     *           PERFORM 1000-XREFFILE-GET-NEXT
     *           IF  END-OF-FILE = 'N'
     *               DISPLAY CARD-XREF-RECORD
     *           END-IF
     *       END-IF
     *   END-PERFORM.
     *   PERFORM 9000-XREFFILE-CLOSE.
     *   DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
     *   GOBACK.
     * }</pre>
     *
     * <h3>Error handling (COBOL {@code 9999-ABEND-PROGRAM} translation)</h3>
     * Any {@link Exception} thrown from
     * {@link CardXrefRepository#streamSequential()} (the open) or from
     * the stream's iteration (a read failure) is logged and surfaced as
     * a return code of {@link #APPL_ERROR} (12) via
     * {@link #zAbendProgram()}. The COBOL program invokes
     * {@code CALL 'CEE3ABD'} which never returns; the Java translation
     * returns the error code so the caller (a {@code main} method in
     * {@code carddemo-app}) can map it to a non-zero process exit code
     * per AAP &sect;0.4.1. {@link #xrefFileClose(Stream)} is invoked
     * unconditionally in the {@code finally} block to mirror COBOL's
     * guarantee that {@code 9000-XREFFILE-CLOSE} runs even when the
     * read loop is short-circuited.
     *
     * @return {@link #APPL_AOK} (0) on success; {@link #APPL_ERROR}
     *         (12) when an I/O failure occurs and the equivalent of
     *         {@code 9999-ABEND-PROGRAM} is reached
     */
    public int run() {
        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
        log.info("START OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);

        int returnCode;
        Stream<CardXrefRecord> stream = null;
        try {
            // PERFORM 0000-XREFFILE-OPEN.
            stream = xrefFileOpen();
            // PERFORM UNTIL END-OF-FILE = 'Y' ... END-PERFORM.
            // The Stream API surfaces end-of-file as Iterator.hasNext()
            // returning false, which terminates the loop naturally —
            // there is no separate END-OF-FILE flag in the translation.
            xrefFileReadLoop(stream);
            returnCode = APPL_AOK;
        } catch (Exception e) {
            // Falls through to the equivalent of 9999-ABEND-PROGRAM —
            // the COBOL program would have logged 'ERROR ...' and
            // CALL 'CEE3ABD'; the Java translation logs the error and
            // returns APPL_ERROR (12) so the caller can map it to a
            // non-zero process exit code.
            log.error("ERROR PROCESSING XREF FILE", e);
            returnCode = zAbendProgram();
        } finally {
            // PERFORM 9000-XREFFILE-CLOSE — runs unconditionally,
            // matching the COBOL guarantee that the CLOSE paragraph
            // executes even on the abend path (CEE3ABD is a fall-through,
            // not a return).
            xrefFileClose(stream);
        }

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
        log.info("END OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);

        // GOBACK — implicit Java return.
        return returnCode;
    }

    // ------------------------------------------------------------------
    // 0000-XREFFILE-OPEN  (CBACT03C.cbl L118-L134)
    // ------------------------------------------------------------------

    /**
     * Mirrors the COBOL {@code 0000-XREFFILE-OPEN} paragraph
     * ({@code app/cbl/CBACT03C.cbl} lines 118-134):
     * <pre>{@code
     *   0000-XREFFILE-OPEN.
     *       MOVE 8 TO APPL-RESULT.
     *       OPEN INPUT XREFFILE-FILE
     *       IF  XREFFILE-STATUS = '00'
     *           MOVE 0 TO APPL-RESULT
     *       ELSE
     *           MOVE 12 TO APPL-RESULT
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           DISPLAY 'ERROR OPENING XREFFILE'
     *           MOVE XREFFILE-STATUS TO IO-STATUS
     *           PERFORM 9910-DISPLAY-IO-STATUS
     *           PERFORM 9999-ABEND-PROGRAM
     *       END-IF
     *       EXIT.
     * }</pre>
     *
     * <p>The COBOL paragraph maps to a single call to
     * {@link CardXrefRepository#streamSequential()}: any failure to open
     * the underlying file is surfaced as a {@link RuntimeException}
     * which we log (mirroring the {@code DISPLAY 'ERROR OPENING
     * XREFFILE'}) and rethrow. The COBOL FILE STATUS field is mirrored
     * by the exception type / message; {@link #displayIoStatus(String)}
     * formats the four-byte status string for diagnostic logging. The
     * caller's {@code try/catch} in {@link #run()} surfaces the abend.
     *
     * @return a closeable {@link Stream} over every {@link CardXrefRecord}
     *         in the dataset, in ascending {@code XREF-CARD-NUM} order
     * @throws RuntimeException if the underlying file cannot be opened
     */
    private Stream<CardXrefRecord> xrefFileOpen() {
        try {
            return cardXrefRepository.streamSequential();
        } catch (RuntimeException e) {
            log.error("ERROR OPENING {}", LIT_FILE_NAME, e);
            displayIoStatus(APPL_ERROR_STATUS_PLACEHOLDER);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // PERFORM UNTIL END-OF-FILE  +  1000-XREFFILE-GET-NEXT
    // (CBACT03C.cbl L74-L81 and L92-L116)
    // ------------------------------------------------------------------

    /**
     * Mirrors the COBOL {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop
     * ({@code app/cbl/CBACT03C.cbl} lines 74-81) which repeatedly
     * invokes {@code 1000-XREFFILE-GET-NEXT} and, on successful read,
     * displays the {@code CARD-XREF-RECORD}.
     *
     * <p><strong>DOUBLE-DISPLAY pattern (AAP &sect;0.7.1):</strong>
     * The COBOL source contains TWO active {@code DISPLAY CARD-XREF-RECORD}
     * statements per record &mdash; one inside the GET-NEXT paragraph
     * at line 96, and one inside the main loop at line 78. The Java
     * translation emits the record TWICE per iteration to preserve this
     * pattern verbatim. This matches the CBACT01C anomaly and contrasts
     * with CBACT02C (where the inner DISPLAY is commented out and only
     * the outer DISPLAY fires).
     *
     * <p>The {@link Iterator} drives the iteration explicitly so that
     * the loop body remains a faithful translation of the COBOL
     * {@code PERFORM UNTIL} (a {@code Stream.forEach} would obscure the
     * end-of-file condition and the per-record DISPLAY behind a lambda;
     * see AAP &sect;0.1.2: "Avoid streams when they obscure
     * translation").
     *
     * <p><strong>PAN safety (AAP &sect;0.7.2):</strong> The DISPLAY uses
     * the parameterised {@code log.info("XREF-RECORD: {}", record)}
     * idiom which dispatches to {@link CardXrefRecord#toString()}. That
     * override masks the PAN (all but the trailing 4 digits replaced
     * with {@code '*'}) so the operator's logger view never sees the
     * cleartext value.
     *
     * @param stream the open XREFFILE stream from
     *               {@link #xrefFileOpen()}; never {@code null}
     * @throws RuntimeException if a read failure occurs while iterating
     *                          the stream (corresponds to FILE STATUS
     *                          not 00 / not 10 in the COBOL source)
     */
    private void xrefFileReadLoop(Stream<CardXrefRecord> stream) {
        // Use the explicit Iterator pattern instead of forEach so the
        // translation of PERFORM UNTIL END-OF-FILE remains line-for-line
        // faithful to the COBOL source.
        Iterator<CardXrefRecord> iterator = stream.iterator();
        try {
            while (iterator.hasNext()) {
                // IF END-OF-FILE = 'N' PERFORM 1000-XREFFILE-GET-NEXT.
                CardXrefRecord record = iterator.next();
                // DISPLAY #1: emitted inside 1000-XREFFILE-GET-NEXT at
                // line 96 of CBACT03C.cbl, after a successful READ.
                log.info("XREF-RECORD: {}", record);
                // DISPLAY #2: emitted at line 78 inside the main
                // PERFORM UNTIL, AFTER the GET-NEXT returns without
                // setting END-OF-FILE. The DOUBLE-DISPLAY anomaly is
                // preserved here per AAP §0.7.1.
                log.info("XREF-RECORD: {}", record);
            }
        } catch (RuntimeException e) {
            // Read failure — corresponds to the FILE STATUS != '00' and
            // FILE STATUS != '10' branch of 1000-XREFFILE-GET-NEXT
            // (lines 109-114 of CBACT03C.cbl).
            log.error("ERROR READING {}", LIT_FILE_NAME, e);
            displayIoStatus(APPL_ERROR_STATUS_PLACEHOLDER);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // 9000-XREFFILE-CLOSE  (CBACT03C.cbl L136-L152)
    // ------------------------------------------------------------------

    /**
     * Mirrors the COBOL {@code 9000-XREFFILE-CLOSE} paragraph
     * ({@code app/cbl/CBACT03C.cbl} lines 136-152):
     * <pre>{@code
     *   9000-XREFFILE-CLOSE.
     *       ADD 8 TO ZERO GIVING APPL-RESULT.
     *       CLOSE XREFFILE-FILE
     *       IF  XREFFILE-STATUS = '00'
     *           SUBTRACT APPL-RESULT FROM APPL-RESULT
     *       ELSE
     *           ADD 12 TO ZERO GIVING APPL-RESULT
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           DISPLAY 'ERROR CLOSING XREFFILE'
     *           MOVE XREFFILE-STATUS TO IO-STATUS
     *           PERFORM 9910-DISPLAY-IO-STATUS
     *           PERFORM 9999-ABEND-PROGRAM
     *       END-IF
     *       EXIT.
     * }</pre>
     *
     * <p>The Java translation closes the open stream &mdash; which in
     * turn closes the underlying file channel or DB cursor &mdash; and
     * logs any failure without re-throwing. The COBOL paragraph would
     * call {@code 9999-ABEND-PROGRAM} on close failure, which from
     * within the {@code finally} block of {@link #run()} would mask the
     * original exception. The Java translation logs the close failure
     * and swallows it so the caller still observes the original (read
     * or open) abend code.
     *
     * <p>The {@code null} guard handles the case where
     * {@link #xrefFileOpen()} threw before returning a usable stream;
     * the finally block in {@link #run()} still calls this method.
     *
     * @param stream the XREFFILE stream to close, or {@code null} if
     *               the open failed and no stream was obtained
     */
    private void xrefFileClose(Stream<CardXrefRecord> stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException e) {
            // Equivalent of XREFFILE-STATUS != '00' branch at lines
            // 147-150 of CBACT03C.cbl. We do NOT escalate to the
            // abend path here because we are already on the close path
            // (possibly itself an abend path), and re-throwing would
            // mask the original cause.
            log.error("ERROR CLOSING {}", LIT_FILE_NAME, e);
            displayIoStatus(APPL_ERROR_STATUS_PLACEHOLDER);
        }
    }

    // ------------------------------------------------------------------
    // 9910-DISPLAY-IO-STATUS  (CBACT03C.cbl L161-L174)
    // ------------------------------------------------------------------

    /**
     * Mirrors the COBOL {@code 9910-DISPLAY-IO-STATUS} paragraph
     * ({@code app/cbl/CBACT03C.cbl} lines 161-174). The COBOL paragraph
     * decodes the two-byte {@code XREFFILE-STATUS} alphanumeric field
     * into a four-digit display string:
     * <pre>{@code
     *   9910-DISPLAY-IO-STATUS.
     *       IF  IO-STATUS NOT NUMERIC
     *       OR  IO-STAT1 = '9'
     *           MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     *           MOVE 0        TO TWO-BYTES-BINARY
     *           MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     *           MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     *           DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *       ELSE
     *           MOVE '0000' TO IO-STATUS-04
     *           MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *           DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *       END-IF
     *       EXIT.
     * }</pre>
     *
     * <p>The Java translation reproduces the four-character output
     * format while preserving the COBOL branch logic:
     * <ul>
     *   <li>If {@code ioStatus} is null/blank/under two characters, emit
     *       {@code "0000"} (the all-zeros fallback used by the COBOL
     *       paragraph when IO-STATUS is not numeric).</li>
     *   <li>If the first byte is the digit {@code '9'} or the two-byte
     *       string is non-numeric, emit the byte itself in position 1
     *       and the numeric value of byte 2 in positions 3-4 (the
     *       COBOL-99-status format).</li>
     *   <li>Otherwise emit {@code "00" + ioStatus} (the normal
     *       four-character form).</li>
     * </ul>
     *
     * @param ioStatus the two-byte XREFFILE-STATUS code, e.g.
     *                 {@code "00"} (success), {@code "10"} (EOF), or
     *                 {@code "12"} (error placeholder); may be
     *                 {@code null}
     */
    private void displayIoStatus(String ioStatus) {
        String formatted;
        if (ioStatus == null || ioStatus.length() < 2) {
            // ELSE branch with the fallback default — COBOL's
            // MOVE '0000' TO IO-STATUS-04 followed by an empty/blank
            // IO-STATUS overlay.
            formatted = "0000";
        } else {
            char stat1 = ioStatus.charAt(0);
            char stat2 = ioStatus.charAt(1);
            boolean nonNumeric = !isAsciiDigit(stat1) || !isAsciiDigit(stat2);
            if (nonNumeric || stat1 == '9') {
                // IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9' branch.
                // Emit byte 1 verbatim in position 1, byte 2's numeric
                // value in positions 3-4 (the COBOL-99-status format).
                // For non-numeric bytes we fall back to byte 2's ordinal
                // value modulo 1000 so the four-byte slot is always
                // filled.
                int rightValue = ((int) stat2) & 0xFF;
                formatted = stat1 + "0" + String.format("%03d", rightValue % 1000);
            } else {
                // ELSE branch: '0000' overlaid with the two-byte status
                // in positions 3-4.
                formatted = "00" + ioStatus;
            }
        }
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    // ------------------------------------------------------------------
    // 9999-ABEND-PROGRAM  (CBACT03C.cbl L154-L158)
    // ------------------------------------------------------------------

    /**
     * Mirrors the COBOL {@code 9999-ABEND-PROGRAM} paragraph
     * ({@code app/cbl/CBACT03C.cbl} lines 154-158):
     * <pre>{@code
     *   9999-ABEND-PROGRAM.
     *       DISPLAY 'ABENDING PROGRAM'
     *       MOVE 0 TO TIMING
     *       MOVE 999 TO ABCODE
     *       CALL 'CEE3ABD'.
     * }</pre>
     *
     * <p>The COBOL paragraph invokes the LE service {@code CEE3ABD},
     * which terminates the task with the supplied abend code. The Java
     * translation does NOT call the LE service (it does not exist
     * outside z/OS); instead it logs the equivalent message and returns
     * {@link #APPL_ERROR} (12) so the caller can map the abend to a
     * non-zero process exit code per AAP &sect;0.4.1. The
     * {@link #CEE3ABD_TIMING} and {@link #CEE3ABD_ABCODE} constants are
     * logged so that downstream operators can correlate the Java log
     * line with the original COBOL abend parameters.
     *
     * @return {@link #APPL_ERROR} (12) so the caller can map the abend
     *         to a process exit code
     */
    private int zAbendProgram() {
        log.error("ABENDING PROGRAM; ABCODE={}, TIMING={}",
                CEE3ABD_ABCODE, CEE3ABD_TIMING);
        return APPL_ERROR;
    }

    // ------------------------------------------------------------------
    // Internal helpers.
    // ------------------------------------------------------------------

    /**
     * Two-character placeholder used as the {@code ioStatus} argument to
     * {@link #displayIoStatus(String)} when a Java {@link RuntimeException}
     * is the abend trigger rather than a COBOL FILE STATUS code.
     * Corresponds to the {@code APPL-RESULT = 12} state in the COBOL
     * source; the leading "12" surfaces the application-level error
     * even when no underlying FILE STATUS is available from the
     * adapter.
     */
    private static final String APPL_ERROR_STATUS_PLACEHOLDER = "12";

    /**
     * Returns {@code true} if the supplied character is an ASCII decimal
     * digit (0-9). Used by {@link #displayIoStatus(String)} to reproduce
     * the COBOL {@code IF IO-STATUS NOT NUMERIC} test.
     *
     * <p>Defined locally rather than delegating to
     * {@link Character#isDigit(char)} because {@code isDigit} returns
     * {@code true} for non-ASCII digit code points (e.g., Arabic-Indic
     * digits), which is wider than the COBOL paragraph's intent.
     *
     * @param c the character to test
     * @return {@code true} if {@code c} is in the range {@code '0'..'9'}
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
