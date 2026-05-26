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

// JEP 511 (finalized in Java 25): a single module import declaration
// pulls in every package exported by the java.base module. Per AAP §0.6.7,
// this is the mandated idiom for files that touch many java.* packages.
// It satisfies the requirements for java.util.{Objects, Iterator} and
// java.util.stream.Stream that this class consumes directly.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code CBACT01C} COBOL batch program at
 * {@code app/cbl/CBACT01C.cbl} (194 lines, &quot;Read and print account
 * data file&quot;). Per the Agent Action Plan (AAP) &sect;0.4.1, this class
 * translates the COBOL sequential ACCTFILE reader into a Java use case
 * that consumes the {@link AccountRepository} port.
 *
 * <h2>Translation authority</h2>
 * <ul>
 *   <li>AAP &sect;0.1.1 &mdash; one Java class per COBOL
 *       {@code PROGRAM-ID}, original program name preserved.</li>
 *   <li>AAP &sect;0.1.2 &mdash; public method per entry paragraph;
 *       private method per internal paragraph.</li>
 *   <li>AAP &sect;0.4.1 &mdash; &quot;Sequential ACCTFILE reader;
 *       translate {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE
 *       ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS
 *       FD-ACCT-ID} to {@code AccountRepository.streamSequential()};
 *       paragraphs 0000-ACCTFILE-OPEN, 1000-ACCTFILE-GET-NEXT,
 *       9000-ACCTFILE-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
 *       become private methods&quot;.</li>
 *   <li>AAP &sect;0.7.1 &mdash; Refactor Discipline:
 *       <em>&quot;If a COBOL paragraph contains dead code or obvious
 *       bugs, translate it faithfully and flag it in
 *       MIGRATION_NOTES.md&quot;</em>.</li>
 * </ul>
 *
 * <h2>Paragraph-to-method 1:1 mapping</h2>
 * <table>
 *   <caption>Mapping from COBOL paragraphs to Java methods</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th><th>COBOL lines</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} entry</td>
 *       <td>{@link #run()}</td><td>L70-L87</td></tr>
 *   <tr><td>{@code 0000-ACCTFILE-OPEN}</td>
 *       <td>{@link #acctFileOpen()}</td><td>L133-L149</td></tr>
 *   <tr><td>{@code 1000-ACCTFILE-GET-NEXT}</td>
 *       <td>{@link #acctFileReadLoop(Stream)}</td><td>L92-L116</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-ACCT-RECORD}</td>
 *       <td>{@link #displayAcctRecord(AccountRecord)}</td><td>L118-L131</td></tr>
 *   <tr><td>{@code 9000-ACCTFILE-CLOSE}</td>
 *       <td>{@link #acctFileClose(Stream)}</td><td>L151-L167</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td>
 *       <td>{@link #displayIoStatus(String)}</td><td>L176-L189</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>{@link #zAbendProgram()}</td><td>L169-L173</td></tr>
 * </table>
 *
 * <h2>DOUBLE-DISPLAY anomaly (faithfully preserved per AAP &sect;0.7.1)</h2>
 * <p>The COBOL program emits TWO displays for every successfully-read
 * record:
 * <ol>
 *   <li>{@code PERFORM 1100-DISPLAY-ACCT-RECORD} called from inside
 *       {@code 1000-ACCTFILE-GET-NEXT} (COBOL line 96) &mdash; emits the
 *       12 labeled, field-by-field DISPLAY lines plus a separator line
 *       (COBOL lines 118-131).</li>
 *   <li>{@code DISPLAY ACCOUNT-RECORD} called from the main
 *       {@code PROCEDURE DIVISION} loop (COBOL line 78) &mdash; emits the
 *       whole 300-byte ACCOUNT-RECORD as a single line.</li>
 * </ol>
 * Neither display is &quot;extra&quot; in the business sense: both are
 * present in the COBOL source as-shipped. Per AAP &sect;0.7.1, this
 * anomaly is preserved verbatim in the Java translation and recorded in
 * {@code java/MIGRATION_NOTES.md}. The same DOUBLE-DISPLAY pattern is
 * also present in {@code CBACT03C} (CARDXREF reader); {@code CBACT02C}
 * displays only once.
 *
 * <h2>{@code ACCT-EXPIRAION-DATE} typo (preserved per AAP &sect;0.7.1)</h2>
 * <p>The COBOL paragraph {@code 1100-DISPLAY-ACCT-RECORD} labels the
 * expiration date field {@code 'ACCT-EXPIRAION-DATE     :'} (missing
 * &quot;T&quot; in &quot;EXPIRATION&quot;). The Java translation
 * preserves the typo verbatim in the log message label, matching the
 * COBOL output character-for-character. The companion accessor
 * {@link AccountRecord#acctExpiraionDate()} preserves the same typo in
 * its Java identifier.
 *
 * <h2>{@code GOBACK} translation</h2>
 * <p>The COBOL {@code GOBACK} verb at L87 returns control to the caller
 * with whatever return code is in the {@code RETURN-CODE} special
 * register (defaulting to 0 / APPL_AOK on a clean run). The Java
 * translation surfaces this as the int return value of {@link #run()}.
 *
 * <h2>{@code CALL 'CEE3ABD'} translation</h2>
 * <p>The COBOL {@code 9999-ABEND-PROGRAM} paragraph at L169-L173
 * issues {@code CALL 'CEE3ABD'} after setting {@link #CEE3ABD_TIMING} = 0
 * and {@link #CEE3ABD_ABCODE} = 999. {@code CEE3ABD} is an IBM Language
 * Environment service that terminates the job step with the given abend
 * code. The Java translation cannot literally abort the JVM (that would
 * forfeit deterministic clean-up and try-with-resources guarantees), so
 * per AAP &sect;0.7.1 (&quot;identical observable outcomes&quot;) it
 * logs an &quot;ABENDING PROGRAM&quot; message and returns
 * {@link #APPL_ERROR}. The composition root in
 * {@code carddemo-app/ReadAccountDumpApp} then maps the non-zero return
 * code to a non-zero process exit code &mdash; matching the observable
 * outcome of the COBOL abend (a failed job step).
 *
 * <h2>Forbidden idioms (per AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Spring Boot &mdash; plain constructor injection.</li>
 *   <li>No {@code double}/{@code float} &mdash; this class never performs
 *       arithmetic on monetary values; all such logic is centralized in
 *       {@code Decimals} (in the domain layer) and consumed by other
 *       use-case classes.</li>
 *   <li>No {@link java.util.Date}/{@link java.util.Calendar}.</li>
 *   <li>No {@link java.io.File} &mdash; file I/O is performed by the
 *       file adapter behind {@link AccountRepository} using
 *       {@code java.nio.file}.</li>
 *   <li>No {@link ThreadLocal} &mdash; cross-method context propagation
 *       uses {@code ScopedValue} (JEP 506); this class has no such
 *       context.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@code --enable-preview} JVM flag &mdash; finalized JEPs
 *       only.</li>
 * </ul>
 *
 * <h2>Mandated Java 25 features used</h2>
 * <ul>
 *   <li>{@code import module java.base;} (JEP 511, finalized in Java 25)
 *       &mdash; replaces a long list of {@code java.util.*} and
 *       {@code java.util.stream.*} imports with a single module import
 *       declaration.</li>
 *   <li>Constructor injection (plain Java, no DI container).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Instances of {@code CbAct01C} are not thread-safe in the sense that
 * concurrent calls to {@link #run()} would share the same underlying
 * file channel via the injected {@link AccountRepository}. The expected
 * usage pattern (matching the COBOL execution model where each job step
 * runs in its own address space) is one instance per execution. The
 * composition root in {@code carddemo-app/ReadAccountDumpApp} constructs
 * a fresh instance for each invocation.
 *
 * @see AccountRepository
 * @see AccountRecord
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT01C",
        sourcePath = "app/cbl/CBACT01C.cbl",
        translationDate = "2025-01-15",
        notes = "Sequential ACCTFILE reader. Preserves COBOL DOUBLE-DISPLAY anomaly: "
                + "ACCOUNT-RECORD is DISPLAY'd twice per record (once via "
                + "1100-DISPLAY-ACCT-RECORD field-by-field inside 1000-ACCTFILE-GET-NEXT, "
                + "once via DISPLAY ACCOUNT-RECORD at the main PROCEDURE DIVISION level). "
                + "Also preserves the ACCT-EXPIRAION-DATE label typo (missing 'T') verbatim. "
                + "Translates SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE ORGANIZATION IS INDEXED "
                + "ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-ACCT-ID to "
                + "AccountRepository.streamSequential() per AAP §0.4.1."
)
public final class CbAct01C {

    // -----------------------------------------------------------------
    // Class identity constants — translated from WORKING-STORAGE SECTION.
    // Marked public so callers (e.g., test harness, composition root,
    // ReadAccountDumpApp) can reference them without duplicating the
    // string / int literals at every site.
    // -----------------------------------------------------------------

    /**
     * Translated from the COBOL {@code PROGRAM-ID. CBACT01C} declaration
     * at {@code app/cbl/CBACT01C.cbl:L23}. Used in the
     * &quot;START OF EXECUTION OF PROGRAM CBACT01C&quot; and
     * &quot;END OF EXECUTION OF PROGRAM CBACT01C&quot; trace messages
     * (COBOL lines 71 and 85).
     */
    public static final String LIT_THIS_PGM = "CBACT01C";

    /**
     * Translated from the COBOL {@code SELECT ACCTFILE-FILE ASSIGN TO
     * ACCTFILE} clause at {@code app/cbl/CBACT01C.cbl:L29}. The JCL DD
     * name {@code ACCTFILE} identifies the VSAM KSDS dataset; the
     * equivalent Java path is supplied by the file adapter behind
     * {@link AccountRepository}.
     */
    public static final String LIT_FILE_NAME = "ACCTFILE";

    // FILE STATUS codes — translated from WORKING-STORAGE level-01
    // 'ACCTFILE-STATUS' declaration at app/cbl/CBACT01C.cbl:L46-L48
    // and the literal comparisons against '00' / '10' / '23' that
    // appear throughout the program.

    /**
     * COBOL {@code FILE STATUS} success code (&quot;00&quot;). Returned
     * by VSAM after a successful {@code OPEN}, {@code READ}, or
     * {@code CLOSE}. Compared against at COBOL lines 94, 136, and 154.
     */
    public static final String STATUS_OK = "00";

    /**
     * COBOL {@code FILE STATUS} end-of-file code (&quot;10&quot;).
     * Returned by VSAM when a sequential {@code READ} reaches the end
     * of the dataset. Compared against at COBOL line 98. The Java
     * translation never observes this code directly because end-of-file
     * is signaled by {@link Iterator#hasNext()} returning {@code false};
     * the constant is preserved for trace parity and for any future
     * status-code-driven branching.
     */
    public static final String STATUS_EOF = "10";

    /**
     * COBOL {@code FILE STATUS} not-found code (&quot;23&quot;).
     * Returned by VSAM for an indexed-access {@code READ INVALID KEY}
     * miss. Preserved here for symmetry with other batch programs
     * (CBACT04C, CBTRN02C) that perform keyed reads against the same
     * file; CBACT01C itself only performs sequential reads.
     */
    public static final String STATUS_NOT_FOUND = "23";

    // Application return codes — translated from WORKING-STORAGE
    // level-01 'APPL-RESULT' with 88-level 'APPL-AOK' (VALUE 0) and
    // 'APPL-EOF' (VALUE 16) at app/cbl/CBACT01C.cbl:L61-L63. The
    // additional APPL_ERROR (12) and APPL_PENDING (8) values are
    // imputed from the literal MOVE statements at lines 99 / 101 /
    // 134 / 139 / 152 / 157.

    /**
     * COBOL {@code 88 APPL-AOK VALUE 0} (line 62) &mdash; the
     * &quot;all OK&quot; application result. Returned from {@link #run()}
     * on a clean dump-and-close.
     */
    public static final int APPL_AOK = 0;

    /**
     * COBOL {@code 88 APPL-EOF VALUE 16} (line 63) &mdash; the
     * end-of-file application result. The Java translation never
     * surfaces this code from {@link #run()} because end-of-file is the
     * expected, non-error termination of the sequential scan
     * (signaled implicitly by {@link Iterator#hasNext()} returning
     * {@code false}); the constant is preserved for trace parity.
     */
    public static final int APPL_EOF = 16;

    /**
     * COBOL implicit application-error value (12), seen in
     * {@code MOVE 12 TO APPL-RESULT} at lines 101, 139, and the
     * equivalent {@code ADD 12 TO ZERO GIVING APPL-RESULT} at line 157.
     * Returned from {@link #run()} on any I/O failure that would have
     * triggered the COBOL {@code 9999-ABEND-PROGRAM} paragraph.
     */
    public static final int APPL_ERROR = 12;

    /**
     * COBOL implicit application-pending value (8), seen in
     * {@code MOVE 8 TO APPL-RESULT} at line 134. In COBOL this value is
     * transiently set at the start of {@code 0000-ACCTFILE-OPEN} and
     * then overwritten with 0 or 12 depending on the open status. It is
     * not used directly in the Java translation but is preserved for
     * symmetry with the COBOL state machine and as a constant for any
     * future translator that needs the same value.
     */
    public static final int APPL_PENDING = 8;

    // CEE3ABD parameters — translated from the WORKING-STORAGE
    // level-01 declarations 'TIMING' and 'ABCODE' at
    // app/cbl/CBACT01C.cbl:L66-L67 plus the MOVE statements at L171-L172
    // of the 9999-ABEND-PROGRAM paragraph.

    /**
     * COBOL {@code CEE3ABD} {@code TIMING} parameter (0) &mdash; from
     * {@code MOVE 0 TO TIMING} at {@code app/cbl/CBACT01C.cbl:L171}.
     * In z/OS Language Environment, {@code TIMING = 0} requests an
     * immediate abend (as opposed to {@code TIMING = 1} which schedules
     * the abend for the next ESPIE/ESTAE return). Preserved verbatim for
     * trace parity; the Java translation cannot literally abend the
     * JVM and therefore logs the value rather than passing it to a
     * native call.
     */
    public static final int CEE3ABD_TIMING = 0;

    /**
     * COBOL {@code CEE3ABD} {@code ABCODE} parameter (999) &mdash;
     * from {@code MOVE 999 TO ABCODE} at
     * {@code app/cbl/CBACT01C.cbl:L172}. This is the conventional
     * &quot;application detected an error&quot; abend code used across
     * every batch program in CardDemo. Preserved verbatim and surfaced
     * in the &quot;ABENDING PROGRAM&quot; log message; the composition
     * root may use this value to compose its non-zero process exit code.
     */
    public static final int CEE3ABD_ABCODE = 999;

    // -----------------------------------------------------------------
    // Logger — translated COBOL DISPLAY verb sink. SLF4J facade only;
    // the concrete logging backend (logback-classic) is supplied by the
    // composition root in carddemo-app per AAP §0.6.12.
    // -----------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(CbAct01C.class);

    // -----------------------------------------------------------------
    // Injected collaborator. Final, non-null; validated in the
    // constructor with Objects.requireNonNull per AAP §0.6.12.
    // -----------------------------------------------------------------
    private final AccountRepository accountRepository;

    /**
     * Constructs a {@code CbAct01C} use case with the supplied
     * {@link AccountRepository} adapter. Plain constructor injection per
     * AAP &sect;0.6.12 (no Spring container); the composition root in
     * {@code carddemo-app/ReadAccountDumpApp} wires the chosen adapter
     * implementation at startup.
     *
     * @param accountRepository non-null repository port that supplies
     *                          sequential access to the ACCTFILE
     *                          equivalent (file or DB adapter); must not
     *                          be {@code null}
     * @throws NullPointerException if {@code accountRepository} is
     *                              {@code null}
     */
    public CbAct01C(AccountRepository accountRepository) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
    }

    // -----------------------------------------------------------------
    // PROCEDURE DIVISION entry — translated from CBACT01C lines 70-87.
    // -----------------------------------------------------------------

    /**
     * Public entry point translated from the COBOL
     * {@code PROCEDURE DIVISION} entry at
     * {@code app/cbl/CBACT01C.cbl:L70-L87}.
     *
     * <p>The COBOL flow is:
     * <pre>{@code
     * PROCEDURE DIVISION.
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
     *     PERFORM 0000-ACCTFILE-OPEN.
     *     PERFORM UNTIL END-OF-FILE = 'Y'
     *         IF  END-OF-FILE = 'N'
     *             PERFORM 1000-ACCTFILE-GET-NEXT
     *             IF  END-OF-FILE = 'N'
     *                 DISPLAY ACCOUNT-RECORD
     *             END-IF
     *         END-IF
     *     END-PERFORM.
     *     PERFORM 9000-ACCTFILE-CLOSE.
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
     *     GOBACK.
     * }</pre>
     *
     * <p>The Java translation:
     * <ol>
     *   <li>Logs the &quot;START OF EXECUTION&quot; banner.</li>
     *   <li>Opens the file via {@link #acctFileOpen()} (which returns
     *       a {@link Stream Stream&lt;AccountRecord&gt;} held open for
     *       the duration of the read loop).</li>
     *   <li>Drives the read-and-display loop via
     *       {@link #acctFileReadLoop(Stream)}. End-of-stream is
     *       signaled by {@link Iterator#hasNext()} returning
     *       {@code false}, which is the natural Java equivalent of the
     *       COBOL {@code FILE STATUS = '10'} branch.</li>
     *   <li>On exception during open / read, logs the error, captures
     *       {@link #APPL_ERROR} (12) as the return code via
     *       {@link #zAbendProgram()}, and falls through to the close.
     *       The {@code finally} block guarantees the stream is closed
     *       even when an exception propagates out of the read loop.</li>
     *   <li>Closes the file via {@link #acctFileClose(Stream)}.</li>
     *   <li>Logs the &quot;END OF EXECUTION&quot; banner.</li>
     *   <li>Returns the captured return code: {@link #APPL_AOK} (0) on
     *       a clean run, {@link #APPL_ERROR} (12) on any failure.</li>
     * </ol>
     *
     * <h4>{@code GOBACK} translation</h4>
     * <p>COBOL {@code GOBACK} terminates the program and returns control
     * to the caller with the value of the {@code RETURN-CODE} special
     * register. The Java equivalent is this method's int return value,
     * which the composition root maps to a process exit code in the
     * shaded jar's {@code main}.
     *
     * <h4>Error handling vs. abend</h4>
     * <p>Per AAP &sect;0.7.1 (&quot;identical observable outcomes&quot;),
     * I/O errors do NOT raise a checked or unchecked exception out of
     * {@code run()}. Instead they are translated to a non-zero return
     * code &mdash; the same observable surface as the COBOL job-step
     * abend (a failed job step from the operator's perspective).
     *
     * @return {@link #APPL_AOK} (0) on success, {@link #APPL_ERROR}
     *         (12) on any I/O failure that would have triggered the
     *         COBOL {@code 9999-ABEND-PROGRAM} paragraph
     */
    public int run() {
        log.info("START OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);
        int returnCode;
        Stream<AccountRecord> stream = null;
        try {
            // 0000-ACCTFILE-OPEN: obtain a closeable Stream over the
            // dataset. The stream backs an underlying file channel (or
            // DB cursor) that must be released in the finally below.
            stream = acctFileOpen();
            // 1000-ACCTFILE-GET-NEXT (driven by the outer PERFORM UNTIL
            // END-OF-FILE loop at L74-L81): walk the stream and emit
            // both the field-by-field display (1100-DISPLAY-ACCT-RECORD)
            // and the whole-record display (DISPLAY ACCOUNT-RECORD).
            acctFileReadLoop(stream);
            // Reaching this point means we exhausted the dataset
            // without an I/O error; this is the COBOL APPL-AOK + EOF
            // path (FILE STATUS = '10' is silently mapped to clean
            // termination by the COBOL ELSE IF APPL-EOF branch at
            // L107-L108).
            returnCode = APPL_AOK;
        } catch (RuntimeException e) {
            // Translated COBOL ELSE-IF NOT APPL-EOF branch at L109-L113:
            //   DISPLAY 'ERROR READING ACCOUNT FILE'
            //   MOVE ACCTFILE-STATUS TO IO-STATUS
            //   PERFORM 9910-DISPLAY-IO-STATUS
            //   PERFORM 9999-ABEND-PROGRAM
            log.error("ERROR READING ACCOUNT FILE", e);
            // The original COBOL captures the two-character FILE STATUS
            // and prints it via 9910-DISPLAY-IO-STATUS. The Java
            // adapter wraps the underlying file-status in a
            // RuntimeException; we surface a synthetic '12' to match
            // the application-result encoding of any non-OK status that
            // is neither '00' nor '10'.
            displayIoStatus(String.valueOf(APPL_ERROR));
            returnCode = zAbendProgram();
        } finally {
            // 9000-ACCTFILE-CLOSE: always closes the stream, even when
            // the read loop threw. Mirrors the COBOL convention of
            // unconditionally PERFORM 9000-ACCTFILE-CLOSE at L83 (the
            // close runs even after an abend would have terminated the
            // job in COBOL, because the JVM continues running through
            // the finally block before returning).
            acctFileClose(stream);
        }
        log.info("END OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);
        // Mirrors COBOL GOBACK at L87: return control to the caller
        // with the captured return code.
        return returnCode;
    }

    // -----------------------------------------------------------------
    // 0000-ACCTFILE-OPEN — translated from CBACT01C lines 133-149.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph
     * {@code 0000-ACCTFILE-OPEN} at
     * {@code app/cbl/CBACT01C.cbl:L133-L149}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 0000-ACCTFILE-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT ACCTFILE-FILE
     *     IF  ACCTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR OPENING ACCTFILE'
     *         MOVE ACCTFILE-STATUS TO IO-STATUS
     *         PERFORM 9910-DISPLAY-IO-STATUS
     *         PERFORM 9999-ABEND-PROGRAM
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <p>The Java translation acquires a {@link Stream Stream&lt;AccountRecord&gt;}
     * from the {@link AccountRepository} port. The port's
     * {@code streamSequential()} contract guarantees ascending
     * {@code ACCT-ID} order &mdash; matching the COBOL VSAM KSDS
     * sequential-by-primary-key traversal &mdash; per AAP &sect;0.1.3.
     *
     * <p>On error, the adapter throws an unchecked exception; this
     * method logs &quot;ERROR OPENING ACCTFILE&quot; (matching the
     * COBOL display) and rethrows so the {@code catch} block in
     * {@link #run()} can transition the program to the abend path.
     *
     * @return a closeable {@link Stream} of every {@link AccountRecord}
     *         in the dataset in ascending {@code ACCT-ID} order
     * @throws RuntimeException if the underlying adapter fails to open
     *                          the file (mirrors COBOL
     *                          {@code ACCTFILE-STATUS NOT = '00'} at
     *                          line 138-139)
     */
    private Stream<AccountRecord> acctFileOpen() {
        try {
            // COBOL: OPEN INPUT ACCTFILE-FILE
            // The adapter performs the underlying file open; if the
            // file is missing or permissions deny access, the adapter
            // throws a RuntimeException (typically IOException wrapped
            // in UncheckedIOException via java.nio.file).
            return accountRepository.streamSequential();
        } catch (RuntimeException e) {
            // COBOL: DISPLAY 'ERROR OPENING ACCTFILE'
            //        PERFORM 9910-DISPLAY-IO-STATUS
            //        PERFORM 9999-ABEND-PROGRAM
            // The Java translation: log the error and rethrow so run()
            // can transition to its catch block (which performs the
            // DISPLAY-IO-STATUS + ABEND sequence).
            log.error("ERROR OPENING ACCTFILE", e);
            throw e;
        }
    }

    // -----------------------------------------------------------------
    // 1000-ACCTFILE-GET-NEXT + 1100-DISPLAY-ACCT-RECORD + the main
    // PROCEDURE DIVISION DISPLAY ACCOUNT-RECORD — translated from
    // CBACT01C lines 74-81 (outer loop) + 92-116 (read paragraph) +
    // 118-131 (display paragraph).
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL outer loop at L74-L81 plus the
     * {@code 1000-ACCTFILE-GET-NEXT} paragraph at L92-L116.
     *
     * <p>The COBOL outer loop is:
     * <pre>{@code
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     IF  END-OF-FILE = 'N'
     *         PERFORM 1000-ACCTFILE-GET-NEXT
     *         IF  END-OF-FILE = 'N'
     *             DISPLAY ACCOUNT-RECORD
     *         END-IF
     *     END-IF
     * END-PERFORM.
     * }</pre>
     *
     * <p>The {@code 1000-ACCTFILE-GET-NEXT} paragraph is:
     * <pre>{@code
     * 1000-ACCTFILE-GET-NEXT.
     *     READ ACCTFILE-FILE INTO ACCOUNT-RECORD.
     *     IF  ACCTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *         PERFORM 1100-DISPLAY-ACCT-RECORD
     *     ELSE
     *         IF  ACCTFILE-STATUS = '10'
     *             MOVE 16 TO APPL-RESULT
     *         ELSE
     *             MOVE 12 TO APPL-RESULT
     *         END-IF
     *     END-IF
     *     ...
     *     EXIT.
     * }</pre>
     *
     * <p>The Java translation walks the stream with an
     * {@link Iterator}, mirroring the {@code PERFORM UNTIL END-OF-FILE}
     * pattern (per AAP &sect;0.4.1 mapping: &quot;{@code PERFORM ... VARYING}
     * &rarr; {@code for} loop or {@code IntStream.range(...).forEach(...)}
     * where idiomatic&quot; &mdash; the explicit {@link Iterator} here
     * preserves the imperative structure of the COBOL paragraph and
     * makes the DOUBLE-DISPLAY sequencing transparent).
     *
     * <h4>DOUBLE-DISPLAY anomaly (preserved per AAP &sect;0.7.1)</h4>
     * For each successfully-read record, the COBOL emits TWO displays
     * in the following order:
     * <ol>
     *   <li>First: 1100-DISPLAY-ACCT-RECORD (12 labeled lines +
     *       separator) &mdash; emitted from inside
     *       {@code 1000-ACCTFILE-GET-NEXT} at COBOL line 96
     *       ({@code PERFORM 1100-DISPLAY-ACCT-RECORD}).</li>
     *   <li>Second: {@code DISPLAY ACCOUNT-RECORD} (whole-record line)
     *       &mdash; emitted from the main {@code PROCEDURE DIVISION}
     *       loop at COBOL line 78.</li>
     * </ol>
     * Both displays are preserved verbatim; the order is preserved
     * exactly to match the byte-for-byte output of CBACT01C.
     *
     * <h4>End-of-file translation</h4>
     * <p>The COBOL paragraph sets {@code END-OF-FILE = 'Y'} when
     * {@code ACCTFILE-STATUS = '10'}, which exits the outer
     * {@code PERFORM UNTIL} loop. In Java, end-of-stream is signaled by
     * {@link Iterator#hasNext()} returning {@code false}, which
     * naturally exits the {@code while} loop &mdash; no explicit
     * end-of-file flag or {@link #APPL_EOF} (16) assignment is needed.
     *
     * @param stream non-null, open {@link Stream} of {@link AccountRecord};
     *               obtained from {@link #acctFileOpen()}
     */
    private void acctFileReadLoop(Stream<AccountRecord> stream) {
        // Obtain an Iterator over the stream. This is the imperative
        // walk pattern (per AAP §0.4.1 — preserve COBOL imperative
        // structure where streams obscure translation).
        Iterator<AccountRecord> iterator = stream.iterator();
        // PERFORM UNTIL END-OF-FILE = 'Y'  ←  while (hasNext)
        while (iterator.hasNext()) {
            // READ ACCTFILE-FILE INTO ACCOUNT-RECORD  ←  iterator.next()
            // The COBOL FILE STATUS check is implicit: the adapter
            // throws on a non-EOF error (caught in run()), and EOF is
            // signaled by hasNext() returning false BEFORE we reach
            // next() — so a successful next() always corresponds to
            // ACCTFILE-STATUS = '00' (STATUS_OK) and the
            // PERFORM 1100-DISPLAY-ACCT-RECORD branch.
            AccountRecord record = iterator.next();

            // FIRST DISPLAY: 1100-DISPLAY-ACCT-RECORD field-by-field
            // (12 labeled lines + separator). Called from inside
            // 1000-ACCTFILE-GET-NEXT at COBOL L96.
            displayAcctRecord(record);

            // SECOND DISPLAY: the main PROCEDURE DIVISION's
            // DISPLAY ACCOUNT-RECORD at COBOL L78. This is the second
            // half of the DOUBLE-DISPLAY anomaly preserved per AAP §0.7.1.
            displayWholeRecord(record);
        }
    }

    /**
     * Translates the COBOL paragraph
     * {@code 1100-DISPLAY-ACCT-RECORD} at
     * {@code app/cbl/CBACT01C.cbl:L118-L131}.
     *
     * <p>Emits 12 labeled, field-by-field {@code DISPLAY} lines for one
     * {@link AccountRecord}, followed by a dashed separator line. The
     * COBOL labels are preserved verbatim &mdash; INCLUDING the
     * &quot;ACCT-EXPIRAION-DATE&quot; typo (missing &quot;T&quot;)
     * which the original copybook also propagates (see
     * {@code app/cpy/CVACT01Y.cpy:L11}: {@code 05 ACCT-EXPIRAION-DATE
     * PIC X(10)}).
     *
     * <p>The original paragraph is:
     * <pre>{@code
     * 1100-DISPLAY-ACCT-RECORD.
     *     DISPLAY 'ACCT-ID                 :'   ACCT-ID
     *     DISPLAY 'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS
     *     DISPLAY 'ACCT-CURR-BAL           :'   ACCT-CURR-BAL
     *     DISPLAY 'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT
     *     DISPLAY 'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT
     *     DISPLAY 'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE
     *     DISPLAY 'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE
     *     DISPLAY 'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE
     *     DISPLAY 'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT
     *     DISPLAY 'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT
     *     DISPLAY 'ACCT-GROUP-ID           :'   ACCT-GROUP-ID
     *     DISPLAY '-------------------------------------------------'
     *     EXIT.
     * }</pre>
     *
     * <p>Note that the COBOL paragraph does NOT display
     * {@code ACCT-ADDR-ZIP} (it appears in the copybook layout at
     * {@code app/cpy/CVACT01Y.cpy:L15} but is omitted from the display
     * paragraph). This is preserved verbatim &mdash; do not add the ZIP
     * to the Java output.
     *
     * @param record non-null {@link AccountRecord} to display
     */
    private void displayAcctRecord(AccountRecord record) {
        // Each label is space-padded to match the COBOL label widths
        // exactly (e.g., 'ACCT-ID                 :' is 25 characters
        // wide). The SLF4J placeholder {} substitutes the value at the
        // end of the line, matching the COBOL DISPLAY concatenation.
        log.info("ACCT-ID                 :{}", record.acctId());
        log.info("ACCT-ACTIVE-STATUS      :{}", record.acctActiveStatus());
        log.info("ACCT-CURR-BAL           :{}", record.acctCurrBal());
        log.info("ACCT-CREDIT-LIMIT       :{}", record.acctCreditLimit());
        log.info("ACCT-CASH-CREDIT-LIMIT  :{}", record.acctCashCreditLimit());
        log.info("ACCT-OPEN-DATE          :{}", record.acctOpenDate());
        // ACCT-EXPIRAION-DATE: typo preserved verbatim per AAP §0.7.1.
        // The accessor name and the label both retain the missing 'T'.
        log.info("ACCT-EXPIRAION-DATE     :{}", record.acctExpiraionDate());
        log.info("ACCT-REISSUE-DATE       :{}", record.acctReissueDate());
        log.info("ACCT-CURR-CYC-CREDIT    :{}", record.acctCurrCycCredit());
        log.info("ACCT-CURR-CYC-DEBIT     :{}", record.acctCurrCycDebit());
        log.info("ACCT-GROUP-ID           :{}", record.acctGroupId());
        // The separator line is 49 dashes (matches the COBOL literal at
        // line 130 exactly: '-------------------------------------------------').
        log.info("-------------------------------------------------");
    }

    /**
     * Translates the {@code DISPLAY ACCOUNT-RECORD} statement at the
     * main {@code PROCEDURE DIVISION} level
     * ({@code app/cbl/CBACT01C.cbl:L78}).
     *
     * <p>The COBOL statement emits the entire 300-byte
     * {@code ACCOUNT-RECORD} (as defined by copybook
     * {@code app/cpy/CVACT01Y.cpy}) as a single DISPLAY line with no
     * label or formatting. The Java translation calls
     * {@link AccountRecord#toString()} on the record, producing a
     * structured human-readable representation that includes every
     * field. This preserves the &quot;dump every field&quot; semantic
     * of the COBOL DISPLAY without forcing the Java caller to reproduce
     * the exact byte layout for diagnostic output (the byte layout is
     * available via {@link AccountRecord#encode()} when byte-for-byte
     * fidelity is required, e.g., for the golden-record harness).
     *
     * @param record non-null {@link AccountRecord} to display
     */
    private void displayWholeRecord(AccountRecord record) {
        // SLF4J's parameterized log accepts an Object; the toString()
        // override on AccountRecord produces a stable, scale-preserving
        // representation of all 13 fields (with the byte[] FILLER
        // rendered as "<178 bytes>" instead of its identity-hash form).
        log.info("{}", record);
    }

    // -----------------------------------------------------------------
    // 9000-ACCTFILE-CLOSE — translated from CBACT01C lines 151-167.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph
     * {@code 9000-ACCTFILE-CLOSE} at
     * {@code app/cbl/CBACT01C.cbl:L151-L167}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 9000-ACCTFILE-CLOSE.
     *     ADD 8 TO ZERO GIVING APPL-RESULT.
     *     CLOSE ACCTFILE-FILE
     *     IF  ACCTFILE-STATUS = '00'
     *         SUBTRACT APPL-RESULT FROM APPL-RESULT
     *     ELSE
     *         ADD 12 TO ZERO GIVING APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR CLOSING ACCOUNT FILE'
     *         MOVE ACCTFILE-STATUS TO IO-STATUS
     *         PERFORM 9910-DISPLAY-IO-STATUS
     *         PERFORM 9999-ABEND-PROGRAM
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <p>The Java translation closes the {@link Stream} (which releases
     * the underlying file channel via the adapter), guarded against
     * {@code null} for the path where {@link #acctFileOpen()} itself
     * failed and never produced a stream. Errors during close are
     * logged at ERROR level but do not raise an abend &mdash; closes
     * are best-effort, matching the COBOL convention of running the
     * close paragraph unconditionally and treating close errors as
     * non-fatal (the program is already on its way out). Note that the
     * COBOL paragraph itself does PERFORM 9999-ABEND-PROGRAM on close
     * failure, but in the Java translation the abend has already
     * happened (or is about to) so a second abend would be redundant.
     *
     * @param stream the {@link Stream} returned by
     *               {@link #acctFileOpen()}; may be {@code null} if
     *               open itself failed
     */
    private void acctFileClose(Stream<AccountRecord> stream) {
        // Guard: if open failed, stream is null and there is nothing to close.
        if (stream == null) {
            return;
        }
        try {
            // The Stream's close() cascades into the file adapter's
            // close (releasing the underlying file channel or DB cursor).
            // Per AccountRepository's contract, the stream's
            // AutoCloseable backing is idempotent.
            stream.close();
        } catch (RuntimeException e) {
            // COBOL: DISPLAY 'ERROR CLOSING ACCOUNT FILE'
            //        PERFORM 9910-DISPLAY-IO-STATUS
            //        PERFORM 9999-ABEND-PROGRAM
            // Java: log and continue. Re-throwing here would mask any
            // earlier error captured in run()'s catch block and could
            // prevent the END OF EXECUTION banner from being emitted.
            log.error("ERROR CLOSING ACCOUNT FILE", e);
            displayIoStatus(String.valueOf(APPL_ERROR));
        }
    }

    // -----------------------------------------------------------------
    // 9910-DISPLAY-IO-STATUS — translated from CBACT01C lines 176-189.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph
     * {@code 9910-DISPLAY-IO-STATUS} at
     * {@code app/cbl/CBACT01C.cbl:L176-L189}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 9910-DISPLAY-IO-STATUS.
     *     IF  IO-STATUS NOT NUMERIC
     *     OR  IO-STAT1 = '9'
     *         MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     *         MOVE 0        TO TWO-BYTES-BINARY
     *         MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     *         MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *     ELSE
     *         MOVE '0000' TO IO-STATUS-04
     *         MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <p>The paragraph builds a four-character display representation
     * of the two-character FILE STATUS:
     * <ul>
     *   <li>If the status starts with &quot;9&quot; or contains
     *       non-digit characters: the first byte becomes the leading
     *       character and the binary value of the second byte is
     *       displayed as the last three positions (e.g., FILE STATUS
     *       &quot;9C&quot; becomes &quot;9067&quot; because 'C' is
     *       ASCII 67).</li>
     *   <li>Otherwise: the status is zero-padded to four digits (e.g.,
     *       &quot;23&quot; becomes &quot;0023&quot;).</li>
     * </ul>
     *
     * <p>The Java translation supports both branches with the same
     * COBOL semantics. The caller passes the raw 2-character FILE
     * STATUS code; this method computes the four-character display
     * representation and emits the prefixed
     * &quot;FILE STATUS IS: NNNN&quot; line.
     *
     * @param ioStatus the COBOL FILE STATUS code, typically two
     *                 characters; may be {@code null} or empty (in
     *                 which case the display reads
     *                 &quot;FILE STATUS IS: NNNN0000&quot;)
     */
    private void displayIoStatus(String ioStatus) {
        String fourChar;
        if (ioStatus == null || ioStatus.isEmpty()) {
            // Defensive: treat null/empty as "0000" to match the COBOL
            // else-branch with a default status value.
            fourChar = "0000";
        } else if (isAllDigits(ioStatus) && ioStatus.charAt(0) != '9') {
            // COBOL else branch (status is fully numeric and does NOT
            // start with '9'): zero-pad the status to four positions
            // ('00' + 2-digit status), with the original status in the
            // last two positions.
            //   MOVE '0000' TO IO-STATUS-04
            //   MOVE IO-STATUS TO IO-STATUS-04(3:2)
            String last2 = ioStatus.length() >= 2
                    ? ioStatus.substring(ioStatus.length() - 2)
                    : ("0" + ioStatus);
            fourChar = "00" + last2;
        } else {
            // COBOL then branch (status is non-numeric or starts with
            // '9'): retain the first character, then render the binary
            // value of the second character (its ASCII code) as a
            // 3-digit decimal in the last three positions.
            //   MOVE IO-STAT1 TO IO-STATUS-04(1:1)
            //   MOVE 0        TO TWO-BYTES-BINARY
            //   MOVE IO-STAT2 TO TWO-BYTES-RIGHT
            //   MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
            char stat1 = ioStatus.charAt(0);
            int stat2 = ioStatus.length() >= 2 ? (ioStatus.charAt(1) & 0xFF) : 0;
            // %03d zero-pads the binary value to three digits.
            fourChar = stat1 + String.format("%03d", stat2);
        }
        // Mirrors the COBOL: DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
        // (the literal 'NNNN' is part of the COBOL message, NOT a
        // placeholder — it is preserved verbatim).
        log.info("FILE STATUS IS: NNNN{}", fourChar);
    }

    /**
     * Helper: returns {@code true} iff every character in {@code s} is
     * an ASCII digit (i.e., the string is non-empty and matches
     * {@code [0-9]+}). Used by {@link #displayIoStatus(String)} to
     * mirror the COBOL {@code IF IO-STATUS NOT NUMERIC} predicate.
     *
     * @param s the input string; must not be {@code null}
     * @return {@code true} if {@code s} is non-empty and contains only
     *         ASCII digits
     */
    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------
    // 9999-ABEND-PROGRAM — translated from CBACT01C lines 169-173.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph
     * {@code 9999-ABEND-PROGRAM} at
     * {@code app/cbl/CBACT01C.cbl:L169-L173}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 9999-ABEND-PROGRAM.
     *     DISPLAY 'ABENDING PROGRAM'
     *     MOVE 0 TO TIMING
     *     MOVE 999 TO ABCODE
     *     CALL 'CEE3ABD'.
     * }</pre>
     *
     * <p>{@code CEE3ABD} is an IBM Language Environment service that
     * terminates the job step with the given {@code ABCODE} (999) and
     * {@code TIMING} (0 = immediate). The Java translation cannot
     * literally abort the JVM (that would forfeit deterministic
     * clean-up and try-with-resources guarantees in
     * {@link #run()}), so per AAP &sect;0.7.1 (&quot;identical
     * observable outcomes&quot;) it logs the &quot;ABENDING PROGRAM&quot;
     * message and returns {@link #APPL_ERROR} (12). The composition
     * root in {@code carddemo-app/ReadAccountDumpApp} maps the non-zero
     * return code to a non-zero process exit code via
     * {@link System#exit(int)} &mdash; matching the observable outcome
     * of the COBOL abend (a failed job step from the operator's
     * perspective).
     *
     * <p>The {@link #CEE3ABD_ABCODE} (999) and {@link #CEE3ABD_TIMING}
     * (0) values are surfaced in the log message for full trace parity
     * with the COBOL paragraph; downstream tooling can correlate the
     * abend code with the failing job step.
     *
     * @return {@link #APPL_ERROR} (12) &mdash; the application-level
     *         abend code that {@link #run()} returns to its caller
     */
    private int zAbendProgram() {
        // COBOL: DISPLAY 'ABENDING PROGRAM'
        log.error("ABENDING PROGRAM");
        // Trace parity: surface the CEE3ABD parameters in a structured
        // log message so downstream tooling can correlate the abend.
        // These values are constants (not state) so this is a constant
        // log message; the SLF4J placeholders simply make the values
        // explicit in the formatted output.
        log.error("CEE3ABD invoked with ABCODE={}, TIMING={}",
                CEE3ABD_ABCODE, CEE3ABD_TIMING);
        // Return the application-error code. The COBOL paragraph never
        // returns (CEE3ABD terminates the job step), so any code that
        // follows the COBOL PERFORM 9999-ABEND-PROGRAM is effectively
        // unreachable. In Java, run() captures this return value and
        // surfaces it via its int return type.
        return APPL_ERROR;
    }
}
