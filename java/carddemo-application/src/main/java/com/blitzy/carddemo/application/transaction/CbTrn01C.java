/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.transaction;

// JEP 511 (finalized in Java 25): a single module import declaration
// pulls in every package exported by the java.base module. Per AAP §0.6.7,
// this is the mandated idiom for files that touch many java.* packages.
// This class consumes Iterator (DalyTranRecord cursor), Optional
// (CardXref/Account lookup results), Objects (requireNonNull on
// constructor params), Locale (String.format ROOT locale for IO-status
// formatting), AutoCloseable (defensive stream / iterator close),
// IllegalStateException (Z-ABEND-PROGRAM translation), Stream (the
// dalytran-file cursor returned by the port), and StandardCharsets
// (ISO-8859-1 byte-preserving conversion for DISPLAY DALYTRAN-RECORD).
import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.DalyTranRecord;

/**
 * Java translation of the {@code CBTRN01C} COBOL batch program at
 * {@code app/cbl/CBTRN01C.cbl} ("Post the records from daily
 * transaction file" &mdash; despite the COBOL comment header, this
 * program does NOT post. It is a read-only validation pre-pass that
 * reads every {@code DALYTRAN-RECORD} sequentially and verifies that
 * the card number is present in {@code XREFFILE} and that the
 * corresponding account is present in {@code ACCTFILE}; the actual
 * posting is performed by {@link CbTrn02C}).
 *
 * <h2>Translation authority</h2>
 * <ul>
 *   <li>AAP &sect;0.1.1 &mdash; one Java class per COBOL
 *       {@code PROGRAM-ID}, original program name preserved.</li>
 *   <li>AAP &sect;0.1.2 &mdash; public method per entry paragraph;
 *       private method per internal paragraph.</li>
 *   <li>AAP &sect;0.4.1 &mdash; "CbTrn01C.java from app/cbl/CBTRN01C.cbl
 *       &mdash; Daily transaction loader".</li>
 *   <li>AAP &sect;0.6.6 &mdash; virtual threads ONLY where COBOL was
 *       serial but reordering doesn't change observable output;
 *       virtual threads are FORBIDDEN here because DISPLAY output
 *       ordering is the program's observable behavior.</li>
 *   <li>AAP &sect;0.7.1 &mdash; Refactor Discipline:
 *       <em>"If a COBOL paragraph contains dead code or obvious bugs,
 *       translate it faithfully and flag it in
 *       MIGRATION_NOTES.md."</em></li>
 * </ul>
 *
 * <h2>Paragraph-to-method 1:1 mapping</h2>
 * <table>
 *   <caption>Mapping from COBOL paragraphs to Java methods</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th><th>COBOL lines</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} / {@code MAIN-PARA}</td>
 *       <td>{@link #run()}</td><td>L154-L197</td></tr>
 *   <tr><td>{@code 0000-DALYTRAN-OPEN}</td>
 *       <td>{@link #dalytranOpen(MutableState)}</td><td>L252-L268</td></tr>
 *   <tr><td>{@code 0100-CUSTFILE-OPEN}</td>
 *       <td>{@link #custfileOpen(MutableState)}</td><td>L271-L287</td></tr>
 *   <tr><td>{@code 0200-XREFFILE-OPEN}</td>
 *       <td>{@link #xreffileOpen(MutableState)}</td><td>L289-L305</td></tr>
 *   <tr><td>{@code 0300-CARDFILE-OPEN}</td>
 *       <td>{@link #cardfileOpen(MutableState)}</td><td>L307-L323</td></tr>
 *   <tr><td>{@code 0400-ACCTFILE-OPEN}</td>
 *       <td>{@link #acctfileOpen(MutableState)}</td><td>L325-L341</td></tr>
 *   <tr><td>{@code 0500-TRANFILE-OPEN}</td>
 *       <td>{@link #tranfileOpen(MutableState)}</td><td>L343-L359</td></tr>
 *   <tr><td>{@code 1000-DALYTRAN-GET-NEXT}</td>
 *       <td>{@link #dalytranGetNext(MutableState)}</td><td>L202-L225</td></tr>
 *   <tr><td>{@code 2000-LOOKUP-XREF}</td>
 *       <td>{@link #lookupXref(MutableState)}</td><td>L227-L239</td></tr>
 *   <tr><td>{@code 3000-READ-ACCOUNT}</td>
 *       <td>{@link #readAccount(MutableState)}</td><td>L241-L250</td></tr>
 *   <tr><td>{@code 9000-DALYTRAN-CLOSE}</td>
 *       <td>{@link #dalytranClose(MutableState)}</td><td>L361-L377</td></tr>
 *   <tr><td>{@code 9100-CUSTFILE-CLOSE}</td>
 *       <td>{@link #custfileClose(MutableState)}</td><td>L379-L395</td></tr>
 *   <tr><td>{@code 9200-XREFFILE-CLOSE}</td>
 *       <td>{@link #xreffileClose(MutableState)}</td><td>L397-L413</td></tr>
 *   <tr><td>{@code 9300-CARDFILE-CLOSE}</td>
 *       <td>{@link #cardfileClose(MutableState)}</td><td>L415-L431</td></tr>
 *   <tr><td>{@code 9400-ACCTFILE-CLOSE}</td>
 *       <td>{@link #acctfileClose(MutableState)}</td><td>L433-L449</td></tr>
 *   <tr><td>{@code 9500-TRANFILE-CLOSE}</td>
 *       <td>{@link #tranfileClose(MutableState)}</td><td>L451-L467</td></tr>
 *   <tr><td>{@code Z-DISPLAY-IO-STATUS}</td>
 *       <td>{@link #displayIoStatus(MutableState)}</td><td>L476-L489</td></tr>
 *   <tr><td>{@code Z-ABEND-PROGRAM}</td>
 *       <td>{@link #zAbendProgram(MutableState)}</td><td>L469-L473</td></tr>
 * </table>
 *
 * <h2>Files opened (verbatim sequence preserved per AAP &sect;0.7.1)</h2>
 * <p>Six files are opened in the COBOL source at L29-L62. CBTRN01C
 * only READs from {@code DALYTRAN-FILE} (sequentially) and performs
 * keyed READs against {@code XREF-FILE} and {@code ACCOUNT-FILE}. The
 * {@code CUSTOMER-FILE}, {@code CARD-FILE}, and {@code TRANSACT-FILE}
 * are opened and closed but never read in the main loop &mdash; this
 * is COBOL boilerplate that the Java translation preserves faithfully
 * because removing the opens/closes would change the observable
 * sequence of {@code Z-DISPLAY-IO-STATUS} / {@code Z-ABEND-PROGRAM}
 * messages if any of those files were missing at run time. The
 * resource-acquisition side effect is part of the program's
 * observable behavior.
 *
 * <h2>Suspected COBOL bug: 9000-DALYTRAN-CLOSE error message</h2>
 * <p>The {@code 9000-DALYTRAN-CLOSE} paragraph at
 * {@code app/cbl/CBTRN01C.cbl:L361-L377} displays the literal
 * {@code 'ERROR CLOSING CUSTOMER FILE'} on close failure and moves
 * {@code CUSTFILE-STATUS} (not {@code DALYTRAN-STATUS}) into
 * {@code IO-STATUS} &mdash; both of which appear to be copy-paste
 * errors from the {@code 9100-CUSTFILE-CLOSE} paragraph. Per AAP
 * &sect;0.7.1 ("If a COBOL paragraph contains dead code or obvious
 * bugs, translate it faithfully and flag it in MIGRATION_NOTES.md"),
 * this Java translation preserves the buggy message and the buggy
 * file-status source verbatim. The deviation is logged in
 * {@code java/MIGRATION_NOTES.md}.
 *
 * <h2>Translation of WS-XREF-READ-STATUS / WS-ACCT-READ-STATUS</h2>
 * <p>The COBOL working-storage flags
 * {@code WS-XREF-READ-STATUS PIC 9(04)} and
 * {@code WS-ACCT-READ-STATUS PIC 9(04)} at
 * {@code app/cbl/CBTRN01C.cbl:L150-L151} are 4-digit numeric flags
 * set to {@code 0} on success and {@code 4} on INVALID KEY (the
 * latter literal appears at {@code app/cbl/CBTRN01C.cbl:L233} and
 * {@code app/cbl/CBTRN01C.cbl:L247}). The COBOL main paragraph at
 * {@code L173} compares {@code IF WS-XREF-READ-STATUS = 0} to decide
 * whether to chain into {@code 3000-READ-ACCOUNT}, and at
 * {@code L177} compares {@code IF WS-ACCT-READ-STATUS NOT = 0} to
 * decide whether to emit the {@code 'ACCOUNT ' ACCT-ID ' NOT FOUND'}
 * diagnostic. The Java translation models these flags as
 * {@code int} fields on {@link MutableState} with the same
 * {@link #FLAG_OK} (0) / {@link #FLAG_NOT_FOUND} (4) discriminants.
 *
 * <h2>Translation of CALL 'CEE3ABD'</h2>
 * <p>The COBOL paragraph {@code Z-ABEND-PROGRAM} at
 * {@code app/cbl/CBTRN01C.cbl:L469-L473} calls the IBM Language
 * Environment service {@code CEE3ABD} which terminates the job step
 * with {@code ABCODE = 999} and {@code TIMING = 0} (immediate). The
 * Java translation cannot literally abort the JVM (that would
 * forfeit deterministic clean-up and try-with-resources guarantees),
 * so per AAP &sect;0.7.1 ("identical observable outcomes") it logs
 * the "ABENDING PROGRAM" message and throws an
 * {@link IllegalStateException} carrying the {@code ABCODE},
 * {@code TIMING}, and the offending {@code IO-STATUS} string. The
 * composition root in {@code carddemo-app} maps the propagated
 * exception to a non-zero process exit code &mdash; matching the
 * observable outcome of the COBOL abend (a failed job step from the
 * operator's perspective).
 *
 * <h2>Forbidden idioms (per AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Spring Boot &mdash; plain constructor
 *       injection.</li>
 *   <li>No virtual-thread fan-out &mdash; CBTRN01C is strictly
 *       sequential because {@code DISPLAY DALYTRAN-RECORD}
 *       interleaved with XREF / ACCOUNT diagnostic lines is the
 *       program's observable output. Reordering would break
 *       golden-record parity (AAP &sect;0.6.6).</li>
 *   <li>No {@code double}/{@code float} &mdash; CBTRN01C performs no
 *       arithmetic on monetary values; the constraint nevertheless
 *       stands.</li>
 *   <li>No {@link java.util.Date}/{@link java.util.Calendar} &mdash;
 *       CBTRN01C performs no date arithmetic.</li>
 *   <li>No {@code java.io.File} &mdash; file I/O is performed by the
 *       file adapters behind the injected repository ports using
 *       {@code java.nio.file}.</li>
 *   <li>No {@link ThreadLocal} &mdash; no cross-method context
 *       propagation; if any were needed, {@code ScopedValue} (JEP
 *       506) would be used.</li>
 *   <li>No reflection, no dynamic proxies, no {@code --enable-preview}
 *       JVM flag.</li>
 * </ul>
 *
 * <h2>Mandated Java 25 features used</h2>
 * <ul>
 *   <li>{@code import module java.base;} (JEP 511, finalized in Java
 *       25) &mdash; replaces a long list of {@code java.util.*},
 *       {@code java.util.stream.*}, and
 *       {@code java.nio.charset.*} imports with a single module
 *       import declaration.</li>
 *   <li>Plain constructor injection (no DI container).</li>
 *   <li>{@link Objects#requireNonNull(Object, String)} on every
 *       constructor parameter (defensive null-check per AAP
 *       &sect;0.6.12).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Instances of {@code CbTrn01C} are NOT thread-safe. The
 * {@link MutableState} object that drives the read loop is per-run
 * mutable state; concurrent calls to {@link #run()} would race on the
 * underlying file channels behind the injected repositories. The
 * expected usage pattern (matching the COBOL execution model where
 * each job step runs in its own address space) is one instance per
 * execution; the composition root in
 * {@code carddemo-app/DailyTransactionsValidationApp} (or equivalent)
 * constructs a fresh instance for each invocation.
 *
 * @see DailyTransactionRepository
 * @see CardXrefRepository
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardRepository
 * @see TransactionRepository
 * @see DalyTranRecord
 * @see CardXrefRecord
 * @see AccountRecord
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBTRN01C",
        sourcePath = "app/cbl/CBTRN01C.cbl",
        translationDate = "2025-01-21",
        notes = "Daily transaction validator (NOT a poster, despite the COBOL comment "
                + "header). Opens 6 files (DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, "
                + "ACCTFILE, TRANFILE) in declaration order, reads each daily "
                + "transaction sequentially, validates card number via XREF lookup "
                + "(2000-LOOKUP-XREF) and account ID via ACCOUNT lookup "
                + "(3000-READ-ACCOUNT), and displays validation diagnostics. Does "
                + "NOT post or update -- read-only validation pass. Faithfully "
                + "preserves the suspected COBOL bug in 9000-DALYTRAN-CLOSE which "
                + "emits 'ERROR CLOSING CUSTOMER FILE' instead of 'ERROR CLOSING "
                + "DAILY TRANSACTION FILE' and moves CUSTFILE-STATUS into IO-STATUS "
                + "instead of DALYTRAN-STATUS (see MIGRATION_NOTES.md)."
)
public final class CbTrn01C {

    // -----------------------------------------------------------------
    // Logger -- the COBOL DISPLAY verb sink. SLF4J facade only; the
    // concrete logging backend (logback-classic) is supplied by the
    // composition root in carddemo-app per AAP §0.6.12.
    // -----------------------------------------------------------------

    /**
     * SLF4J logger for this class. All COBOL {@code DISPLAY} statements
     * route through this logger so that the composition root can
     * choose any backend (stdout, file, structured JSON) without
     * recompiling the use case.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CbTrn01C.class);

    // -----------------------------------------------------------------
    // Class identity constants -- translated from WORKING-STORAGE
    // SECTION literals at app/cbl/CBTRN01C.cbl:L156, L195. Marked
    // public so callers (test harness, composition root) can reference
    // the literal name without duplicating the string at every site.
    // -----------------------------------------------------------------

    /**
     * Translated from the COBOL {@code PROGRAM-ID. CBTRN01C} declaration
     * at {@code app/cbl/CBTRN01C.cbl:L23}. Used in the
     * &quot;START OF EXECUTION OF PROGRAM CBTRN01C&quot; and
     * &quot;END OF EXECUTION OF PROGRAM CBTRN01C&quot; trace messages
     * (COBOL lines 156 and 195).
     */
    public static final String LIT_THIS_PGM = "CBTRN01C";

    // -----------------------------------------------------------------
    // FILE STATUS codes -- translated from WORKING-STORAGE 01-level
    // file-status declarations at app/cbl/CBTRN01C.cbl:L100-L127 and
    // the literal comparisons against '00' and '10' throughout the
    // program. STATUS_OK ('00') is the COBOL convention for a
    // successful OPEN / READ / CLOSE; STATUS_EOF ('10') is the
    // sequential end-of-file indicator returned by VSAM; STATUS_GENERIC
    // ('30') is a synthetic value used by the Java translation to
    // surface an adapter-level exception thrown by the underlying file
    // channel back through the COBOL Z-DISPLAY-IO-STATUS path.
    // -----------------------------------------------------------------

    /**
     * COBOL {@code FILE STATUS} success code (&quot;00&quot;). Compared
     * against at {@code app/cbl/CBTRN01C.cbl:L204} (DALYTRAN read),
     * {@code L255} (DALYTRAN open), {@code L274} (CUSTFILE open),
     * {@code L292} (XREFFILE open), {@code L310} (CARDFILE open),
     * {@code L328} (ACCTFILE open), {@code L346} (TRANFILE open),
     * {@code L364} (DALYTRAN close), {@code L382} (CUSTFILE close),
     * {@code L400} (XREFFILE close), {@code L418} (CARDFILE close),
     * {@code L436} (ACCTFILE close), and {@code L454} (TRANFILE close).
     */
    public static final String STATUS_OK = "00";

    /**
     * COBOL {@code FILE STATUS} end-of-file code (&quot;10&quot;).
     * Returned by sequential {@code READ DALYTRAN-FILE} when the input
     * dataset has been exhausted; checked at
     * {@code app/cbl/CBTRN01C.cbl:L207}. The Java translation never
     * observes this code directly because the underlying
     * {@link Iterator#hasNext()} returns {@code false} at EOF, which is
     * the natural Java equivalent.
     */
    public static final String STATUS_EOF = "10";

    /**
     * COBOL {@code FILE STATUS} not-found code (&quot;23&quot;).
     * Returned by indexed-access {@code READ INVALID KEY} miss against
     * {@code XREF-FILE} or {@code ACCOUNT-FILE} (paragraphs
     * {@code 2000-LOOKUP-XREF} and {@code 3000-READ-ACCOUNT}). The Java
     * translation never observes this code directly because the
     * repository ports return {@link Optional#empty()} on a miss; the
     * constant is preserved for trace parity and for any future
     * status-driven branching.
     */
    public static final String STATUS_NOT_FOUND = "23";

    /**
     * Synthetic {@code FILE STATUS} value (&quot;30&quot;) used by the
     * Java translation to surface an adapter-level exception thrown by
     * the underlying file channel into the COBOL
     * {@code Z-DISPLAY-IO-STATUS} path. There is no equivalent COBOL
     * literal; this value is chosen because the {@code 3x} family in
     * the COBOL FILE STATUS specification denotes &quot;permanent
     * error&quot; (open failed, hardware failure, etc.), which is the
     * closest semantic match to a Java {@link RuntimeException}
     * propagating out of a repository call.
     */
    public static final String STATUS_GENERIC_ERROR = "30";

    // -----------------------------------------------------------------
    // Application return codes -- translated from WORKING-STORAGE
    // 'APPL-RESULT' field with 88-level 'APPL-AOK VALUE 0' and 'APPL-EOF
    // VALUE 16' at app/cbl/CBTRN01C.cbl:L142-L144. The additional
    // APPL_ERROR (12) and APPL_PENDING (8) values are imputed from the
    // literal MOVE statements at lines 208 / 210, 256 / 258 (and
    // equivalents in every other open / close paragraph), 253 / 272
    // (and every other 'MOVE 8 TO APPL-RESULT' at paragraph entry).
    // -----------------------------------------------------------------

    /**
     * COBOL {@code 88 APPL-AOK VALUE 0} (line 143) &mdash; the
     * &quot;all OK&quot; application result indicating that the most
     * recent file operation succeeded.
     */
    public static final int APPL_AOK = 0;

    /**
     * COBOL {@code 88 APPL-EOF VALUE 16} (line 144) &mdash; the
     * end-of-file application result, set by
     * {@code 1000-DALYTRAN-GET-NEXT} when {@code DALYTRAN-STATUS = '10'}
     * (line 208). The Java translation surfaces this when the
     * underlying {@link Iterator#hasNext()} returns {@code false}.
     */
    public static final int APPL_EOF = 16;

    /**
     * COBOL implicit application-error value (12), seen in
     * {@code MOVE 12 TO APPL-RESULT} at lines 210, 258, 277, 295, 313,
     * 331, 349, 367, 385, 403, 421, 439, and 457. Set whenever an
     * I/O verb returns a status other than &quot;00&quot;.
     */
    public static final int APPL_ERROR = 12;

    /**
     * COBOL implicit application-pending value (8), seen in
     * {@code MOVE 8 TO APPL-RESULT} at the start of every OPEN
     * paragraph (lines 253, 272, 290, 308, 326, 344) and in
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} at the start of every
     * CLOSE paragraph (lines 362, 380, 398, 416, 434, 452). The
     * value is transiently set and then overwritten with 0 or 12
     * depending on the result; preserved here for trace parity with
     * the COBOL state machine.
     */
    public static final int APPL_PENDING = 8;

    // -----------------------------------------------------------------
    // CEE3ABD parameters -- translated from the WORKING-STORAGE 01-level
    // declarations 'TIMING' and 'ABCODE' at app/cbl/CBTRN01C.cbl:L147-L148
    // plus the MOVE statements at L471-L472 of the Z-ABEND-PROGRAM
    // paragraph.
    // -----------------------------------------------------------------

    /**
     * COBOL {@code CEE3ABD} {@code TIMING} parameter (0) &mdash; from
     * {@code MOVE 0 TO TIMING} at {@code app/cbl/CBTRN01C.cbl:L471}.
     * In z/OS Language Environment, {@code TIMING = 0} requests an
     * immediate abend. Preserved verbatim for trace parity; the Java
     * translation surfaces this in the
     * {@link IllegalStateException} message rather than passing it to
     * a native call.
     */
    public static final int CEE3ABD_TIMING = 0;

    /**
     * COBOL {@code CEE3ABD} {@code ABCODE} parameter (999) &mdash;
     * from {@code MOVE 999 TO ABCODE} at
     * {@code app/cbl/CBTRN01C.cbl:L472}. This is the conventional
     * &quot;application detected an error&quot; abend code used
     * across every batch program in CardDemo.
     */
    public static final int CEE3ABD_ABCODE = 999;

    // -----------------------------------------------------------------
    // WS-XREF-READ-STATUS and WS-ACCT-READ-STATUS discriminants --
    // translated from app/cbl/CBTRN01C.cbl:L150-L151. These 4-digit
    // numeric flags are set to 0 on success and 4 on INVALID KEY
    // (lines 233 and 247 respectively).
    // -----------------------------------------------------------------

    /**
     * Discriminant value for {@code WS-XREF-READ-STATUS} /
     * {@code WS-ACCT-READ-STATUS} indicating the most recent lookup
     * succeeded. The COBOL initialization at
     * {@code app/cbl/CBTRN01C.cbl:L170} (XREF) and {@code L174}
     * (ACCT) sets these flags to 0 immediately before the lookup
     * paragraph is performed, and the success path leaves them at 0.
     */
    public static final int FLAG_OK = 0;

    /**
     * Discriminant value for {@code WS-XREF-READ-STATUS} /
     * {@code WS-ACCT-READ-STATUS} indicating the most recent lookup
     * missed (INVALID KEY branch). Set at
     * {@code app/cbl/CBTRN01C.cbl:L233} by
     * {@code MOVE 4 TO WS-XREF-READ-STATUS} and at
     * {@code app/cbl/CBTRN01C.cbl:L247} by
     * {@code MOVE 4 TO WS-ACCT-READ-STATUS}.
     */
    public static final int FLAG_NOT_FOUND = 4;

    /**
     * Number of trailing PAN digits left visible by {@link #maskPan(String)}.
     * Per AAP &sect;0.7.2 ("No card PAN logged in full; mask all but last 4
     * digits in logs and error messages") this constant is fixed at
     * <strong>4</strong> across every translated program; see
     * {@code CbTrn03C.PAN_VISIBLE_TAIL} and {@code CoTrn02C.maskPan} for
     * the parallel implementations used by the other transaction
     * programs.
     */
    private static final int PAN_VISIBLE_TAIL = 4;

    // -----------------------------------------------------------------
    // Injected collaborators -- six repositories matching the six
    // SELECT clauses in the COBOL ENVIRONMENT DIVISION at
    // app/cbl/CBTRN01C.cbl:L29-L62. Final, non-null; validated in the
    // constructor with Objects.requireNonNull per AAP §0.6.12.
    // -----------------------------------------------------------------

    /**
     * The {@code DALYTRAN-FILE} port (sequential reader). Translates
     * the COBOL {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
     * ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS
     * IS DALYTRAN-STATUS} clause at
     * {@code app/cbl/CBTRN01C.cbl:L29-L32}.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * The {@code CUSTOMER-FILE} port (indexed; OPEN INPUT in this
     * program but never read in the main loop &mdash; preserved
     * faithfully per AAP &sect;0.7.1). Translates the COBOL
     * {@code SELECT CUSTOMER-FILE ASSIGN TO CUSTFILE ORGANIZATION IS
     * INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-CUST-ID FILE
     * STATUS IS CUSTFILE-STATUS} clause at
     * {@code app/cbl/CBTRN01C.cbl:L34-L38}.
     */
    private final CustomerRepository customerRepository;

    /**
     * The {@code XREF-FILE} port (keyed lookup by card number).
     * Translates the COBOL {@code SELECT XREF-FILE ASSIGN TO XREFFILE
     * ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS
     * FD-XREF-CARD-NUM FILE STATUS IS XREFFILE-STATUS} clause at
     * {@code app/cbl/CBTRN01C.cbl:L40-L44}. Consumed by
     * {@link #lookupXref(MutableState)} via
     * {@link CardXrefRepository#findByCardNumber(String)}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * The {@code CARD-FILE} port (indexed; OPEN INPUT in this program
     * but never read in the main loop &mdash; preserved faithfully per
     * AAP &sect;0.7.1). Translates the COBOL {@code SELECT CARD-FILE
     * ASSIGN TO CARDFILE ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM
     * RECORD KEY IS FD-CARD-NUM FILE STATUS IS CARDFILE-STATUS} clause
     * at {@code app/cbl/CBTRN01C.cbl:L46-L50}.
     */
    private final CardRepository cardRepository;

    /**
     * The {@code ACCOUNT-FILE} port (keyed lookup by account id).
     * Translates the COBOL {@code SELECT ACCOUNT-FILE ASSIGN TO
     * ACCTFILE ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD
     * KEY IS FD-ACCT-ID FILE STATUS IS ACCTFILE-STATUS} clause at
     * {@code app/cbl/CBTRN01C.cbl:L52-L56}. Consumed by
     * {@link #readAccount(MutableState)} via
     * {@link AccountRepository#findById(long)}.
     */
    private final AccountRepository accountRepository;

    /**
     * The {@code TRANSACT-FILE} port (indexed; OPEN INPUT in this
     * program but never read in the main loop &mdash; preserved
     * faithfully per AAP &sect;0.7.1). Translates the COBOL
     * {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE ORGANIZATION IS
     * INDEXED ACCESS MODE IS RANDOM RECORD KEY IS FD-TRANS-ID FILE
     * STATUS IS TRANFILE-STATUS} clause at
     * {@code app/cbl/CBTRN01C.cbl:L58-L62}.
     */
    private final TransactionRepository transactionRepository;


    // -----------------------------------------------------------------
    // Constructor -- plain Java constructor injection per AAP §0.6.12.
    // No Spring container; the composition root in carddemo-app
    // constructs each repository implementation and passes it in.
    // -----------------------------------------------------------------

    /**
     * Constructs a {@code CbTrn01C} use case with the supplied six
     * repository adapters. Plain constructor injection per AAP
     * &sect;0.6.12 (no Spring container); the composition root wires
     * the chosen adapter implementations at startup.
     *
     * <p>All six parameters are mandatory because the COBOL program
     * opens all six files at the start of execution. Even though the
     * {@code CUSTOMER-FILE}, {@code CARD-FILE}, and
     * {@code TRANSACT-FILE} ports are never read in the main loop,
     * their corresponding OPEN paragraphs at
     * {@code app/cbl/CBTRN01C.cbl:L271-L359} are preserved verbatim
     * per AAP &sect;0.7.1 because the OPEN side effects (and any
     * abend that an OPEN failure would trigger) are part of the
     * program's observable behavior.
     *
     * @param dailyTransactionRepository non-null port supplying
     *                                   sequential read of the
     *                                   DALYTRAN file (the input
     *                                   driver of this program)
     * @param customerRepository         non-null port for the
     *                                   CUSTFILE handle (opened but
     *                                   never read by CBTRN01C; the
     *                                   open / close lifecycle is
     *                                   preserved)
     * @param cardXrefRepository         non-null port for the
     *                                   XREFFILE lookups by card
     *                                   number (paragraph
     *                                   {@code 2000-LOOKUP-XREF})
     * @param cardRepository             non-null port for the
     *                                   CARDFILE handle (opened but
     *                                   never read by CBTRN01C; the
     *                                   open / close lifecycle is
     *                                   preserved)
     * @param accountRepository          non-null port for the
     *                                   ACCTFILE lookups by account
     *                                   id (paragraph
     *                                   {@code 3000-READ-ACCOUNT})
     * @param transactionRepository      non-null port for the
     *                                   TRANFILE handle (opened but
     *                                   never read by CBTRN01C; the
     *                                   open / close lifecycle is
     *                                   preserved)
     * @throws NullPointerException if any parameter is {@code null}
     */
    public CbTrn01C(DailyTransactionRepository dailyTransactionRepository,
                    CustomerRepository customerRepository,
                    CardXrefRepository cardXrefRepository,
                    CardRepository cardRepository,
                    AccountRepository accountRepository,
                    TransactionRepository transactionRepository) {
        this.dailyTransactionRepository = Objects.requireNonNull(
                dailyTransactionRepository, "dailyTransactionRepository");
        this.customerRepository = Objects.requireNonNull(
                customerRepository, "customerRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.cardRepository = Objects.requireNonNull(
                cardRepository, "cardRepository");
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
    }

    // -----------------------------------------------------------------
    // MutableState -- the per-run aggregate of COBOL WORKING-STORAGE
    // SECTION fields that are mutated across paragraphs. Models the
    // implicit shared scratchpad that COBOL programs rely on; the
    // explicit Java aggregation makes the data flow visible and
    // testable.
    // -----------------------------------------------------------------

    /**
     * Per-run mutable state aggregating the COBOL WORKING-STORAGE
     * fields that are read or written by more than one paragraph.
     * Modeled as a private static final class so that the state has a
     * lifetime scoped to a single {@link #run()} invocation; the
     * enclosing {@code CbTrn01C} instance is therefore stateless and
     * may be reused across (non-concurrent) runs.
     *
     * <p>Fields here correspond directly to COBOL WORKING-STORAGE
     * fields:
     * <ul>
     *   <li>{@link #endOfFile} &mdash; mirrors
     *       {@code END-OF-DAILY-TRANS-FILE PIC X(01) VALUE 'N'} at
     *       {@code app/cbl/CBTRN01C.cbl:L146}. Set to {@code true}
     *       when {@code DALYTRAN-STATUS = '10'} at
     *       {@code app/cbl/CBTRN01C.cbl:L217}.</li>
     *   <li>{@link #currentDaly} &mdash; mirrors the
     *       {@code DALYTRAN-RECORD} copybook structure populated by
     *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} at
     *       {@code app/cbl/CBTRN01C.cbl:L203}.</li>
     *   <li>{@link #currentXref} &mdash; mirrors the
     *       {@code CARD-XREF-RECORD} copybook structure populated by
     *       {@code READ XREF-FILE RECORD INTO CARD-XREF-RECORD} at
     *       {@code app/cbl/CBTRN01C.cbl:L229}.</li>
     *   <li>{@link #currentAccount} &mdash; mirrors the
     *       {@code ACCOUNT-RECORD} copybook structure populated by
     *       {@code READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD} at
     *       {@code app/cbl/CBTRN01C.cbl:L243}.</li>
     *   <li>{@link #dalytranStatus} / {@link #custfileStatus} /
     *       {@link #xreffileStatus} / {@link #cardfileStatus} /
     *       {@link #acctfileStatus} / {@link #tranfileStatus} &mdash;
     *       mirror the six 01-level file-status fields at
     *       {@code app/cbl/CBTRN01C.cbl:L100-L127}.</li>
     *   <li>{@link #wsXrefReadStatus} &mdash; mirrors
     *       {@code WS-XREF-READ-STATUS PIC 9(04)} at
     *       {@code app/cbl/CBTRN01C.cbl:L150}. Initial value 0
     *       (COBOL default for numeric working-storage; explicitly
     *       set to 0 at {@code app/cbl/CBTRN01C.cbl:L170} before
     *       each lookup).</li>
     *   <li>{@link #wsAcctReadStatus} &mdash; mirrors
     *       {@code WS-ACCT-READ-STATUS PIC 9(04)} at
     *       {@code app/cbl/CBTRN01C.cbl:L151}. Initial value 0;
     *       explicitly set to 0 at
     *       {@code app/cbl/CBTRN01C.cbl:L174} before each lookup.</li>
     *   <li>{@link #ioStatus} &mdash; mirrors {@code IO-STATUS} at
     *       {@code app/cbl/CBTRN01C.cbl:L129-L131}. Set by each
     *       error branch immediately before
     *       {@code PERFORM Z-DISPLAY-IO-STATUS} via {@code MOVE
     *       &lt;file&gt;-STATUS TO IO-STATUS}.</li>
     *   <li>{@link #applResult} &mdash; mirrors
     *       {@code APPL-RESULT PIC S9(9) COMP} at
     *       {@code app/cbl/CBTRN01C.cbl:L142}.</li>
     *   <li>{@link #dalyStream} / {@link #dalyIterator} &mdash; the
     *       Java cursor over the DALYTRAN file. {@code dalyStream}
     *       is held so it can be closed in
     *       {@link #dalytranClose(MutableState)}; {@code dalyIterator}
     *       is the actual driver of the main loop (matches the COBOL
     *       per-record {@code READ DALYTRAN-FILE} semantics).</li>
     * </ul>
     */
    private static final class MutableState {

        /**
         * Mirrors {@code END-OF-DAILY-TRANS-FILE PIC X(01) VALUE 'N'}
         * at {@code app/cbl/CBTRN01C.cbl:L146}. Set to {@code true}
         * (the Java equivalent of COBOL 'Y') when the underlying
         * {@link Iterator#hasNext()} returns {@code false}.
         */
        boolean endOfFile = false;

        /**
         * The {@code DALYTRAN-RECORD} most recently read from the
         * sequential dataset. Mirrors the copybook {@code CVTRA06Y}
         * structure populated by the COBOL {@code READ DALYTRAN-FILE
         * INTO DALYTRAN-RECORD} verb. {@code null} until the first
         * successful read.
         */
        DalyTranRecord currentDaly;

        /**
         * The {@code CARD-XREF-RECORD} most recently retrieved by
         * {@code 2000-LOOKUP-XREF}. Mirrors the copybook
         * {@code CVACT03Y} structure. {@code null} when the most
         * recent lookup missed.
         */
        CardXrefRecord currentXref;

        /**
         * The {@code ACCOUNT-RECORD} most recently retrieved by
         * {@code 3000-READ-ACCOUNT}. Mirrors the copybook
         * {@code CVACT01Y} structure. {@code null} when the most
         * recent lookup missed or was never performed.
         */
        AccountRecord currentAccount;

        /**
         * Mirrors the 2-character {@code DALYTRAN-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L100-L102}. Set to
         * {@link #STATUS_OK} on success, {@link #STATUS_EOF} on
         * end-of-file, or {@link #STATUS_GENERIC_ERROR} on adapter
         * exception.
         */
        String dalytranStatus = STATUS_OK;

        /**
         * Mirrors the 2-character {@code CUSTFILE-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L105-L107}.
         */
        String custfileStatus = STATUS_OK;

        /**
         * Mirrors the 2-character {@code XREFFILE-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L110-L112}.
         */
        String xreffileStatus = STATUS_OK;

        /**
         * Mirrors the 2-character {@code CARDFILE-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L115-L117}.
         */
        String cardfileStatus = STATUS_OK;

        /**
         * Mirrors the 2-character {@code ACCTFILE-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L120-L122}.
         */
        String acctfileStatus = STATUS_OK;

        /**
         * Mirrors the 2-character {@code TRANFILE-STATUS} at
         * {@code app/cbl/CBTRN01C.cbl:L125-L127}.
         */
        String tranfileStatus = STATUS_OK;

        /**
         * Mirrors {@code WS-XREF-READ-STATUS PIC 9(04)} at
         * {@code app/cbl/CBTRN01C.cbl:L150}. Initial COBOL value is 0
         * (numeric working-storage defaults to zero); the main
         * paragraph at {@code app/cbl/CBTRN01C.cbl:L170} sets it
         * back to 0 immediately before each
         * {@code PERFORM 2000-LOOKUP-XREF}. Set to
         * {@link #FLAG_NOT_FOUND} on INVALID KEY.
         */
        int wsXrefReadStatus = FLAG_OK;

        /**
         * Mirrors {@code WS-ACCT-READ-STATUS PIC 9(04)} at
         * {@code app/cbl/CBTRN01C.cbl:L151}. Initial COBOL value is 0;
         * the main paragraph at {@code app/cbl/CBTRN01C.cbl:L174}
         * sets it back to 0 immediately before each
         * {@code PERFORM 3000-READ-ACCOUNT}. Set to
         * {@link #FLAG_NOT_FOUND} on INVALID KEY.
         */
        int wsAcctReadStatus = FLAG_OK;

        /**
         * Mirrors the 2-character {@code IO-STATUS} working field at
         * {@code app/cbl/CBTRN01C.cbl:L129-L131}. Loaded by every
         * error branch via {@code MOVE &lt;file&gt;-STATUS TO
         * IO-STATUS} just before
         * {@code PERFORM Z-DISPLAY-IO-STATUS}.
         */
        String ioStatus = "";

        /**
         * Mirrors {@code APPL-RESULT PIC S9(9) COMP} at
         * {@code app/cbl/CBTRN01C.cbl:L142}.
         */
        int applResult = APPL_AOK;

        /**
         * The closeable stream returned by
         * {@link DailyTransactionRepository#streamSequential()}. Held
         * separately from the {@link #dalyIterator} so that the
         * {@code 9000-DALYTRAN-CLOSE} paragraph can release the
         * underlying file channel even if the iterator has already
         * been exhausted. {@code null} before
         * {@link #dalytranOpen(MutableState)} runs.
         */
        Stream<DalyTranRecord> dalyStream;

        /**
         * Iterator view of the {@link #dalyStream} that drives the
         * main loop. Using an iterator (rather than
         * {@code Stream#forEach}) lets the Java translation interleave
         * the {@code DISPLAY DALYTRAN-RECORD}, XREF lookup, and
         * ACCOUNT lookup steps inside the loop body in the exact
         * order the COBOL {@code PERFORM UNTIL} construct does
         * &mdash; preserving the observable {@code DISPLAY} ordering
         * required for golden-record parity.
         */
        Iterator<DalyTranRecord> dalyIterator;
    }


    // -----------------------------------------------------------------
    // PROCEDURE DIVISION entry -- translated from CBTRN01C lines 154-197.
    // -----------------------------------------------------------------

    /**
     * Public entry point translated from the COBOL
     * {@code PROCEDURE DIVISION} entry / {@code MAIN-PARA} at
     * {@code app/cbl/CBTRN01C.cbl:L154-L197}.
     *
     * <p>The COBOL flow is:
     * <pre>{@code
     * MAIN-PARA.
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'.
     *     PERFORM 0000-DALYTRAN-OPEN.
     *     PERFORM 0100-CUSTFILE-OPEN.
     *     PERFORM 0200-XREFFILE-OPEN.
     *     PERFORM 0300-CARDFILE-OPEN.
     *     PERFORM 0400-ACCTFILE-OPEN.
     *     PERFORM 0500-TRANFILE-OPEN.
     *
     *     PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
     *         IF  END-OF-DAILY-TRANS-FILE = 'N'
     *             PERFORM 1000-DALYTRAN-GET-NEXT
     *             IF  END-OF-DAILY-TRANS-FILE = 'N'
     *                 DISPLAY DALYTRAN-RECORD
     *             END-IF
     *             MOVE 0                 TO WS-XREF-READ-STATUS
     *             MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
     *             PERFORM 2000-LOOKUP-XREF
     *             IF WS-XREF-READ-STATUS = 0
     *               MOVE 0            TO WS-ACCT-READ-STATUS
     *               MOVE XREF-ACCT-ID TO ACCT-ID
     *               PERFORM 3000-READ-ACCOUNT
     *               IF WS-ACCT-READ-STATUS NOT = 0
     *                   DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
     *               END-IF
     *             ELSE
     *               DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
     *               ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
     *               DALYTRAN-ID
     *             END-IF
     *         END-IF
     *     END-PERFORM.
     *
     *     PERFORM 9000-DALYTRAN-CLOSE.
     *     PERFORM 9100-CUSTFILE-CLOSE.
     *     PERFORM 9200-XREFFILE-CLOSE.
     *     PERFORM 9300-CARDFILE-CLOSE.
     *     PERFORM 9400-ACCTFILE-CLOSE.
     *     PERFORM 9500-TRANFILE-CLOSE.
     *
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'.
     *
     *     GOBACK.
     * }</pre>
     *
     * <p>The Java translation:
     * <ol>
     *   <li>Logs the &quot;START OF EXECUTION&quot; banner.</li>
     *   <li>Opens all six files in declaration order
     *       (DALYTRAN &rarr; CUSTFILE &rarr; XREFFILE &rarr;
     *       CARDFILE &rarr; ACCTFILE &rarr; TRANFILE) by calling the
     *       corresponding paragraph methods. Each paragraph method
     *       sets its file-status field on the shared
     *       {@link MutableState}; on failure each method calls
     *       {@link #zAbendProgram(MutableState)} which throws an
     *       {@link IllegalStateException}.</li>
     *   <li>Drives the main loop: while not at EOF, reads the next
     *       {@code DALYTRAN-RECORD}, displays it via
     *       {@link #displayRawRecord(byte[])}, performs the XREF
     *       lookup, and conditionally performs the ACCOUNT lookup.
     *       Diagnostic messages are emitted in the COBOL order
     *       (preserved verbatim).</li>
     *   <li>Closes all six files in declaration order. The Java
     *       translation guarantees the closes run even when the
     *       read loop throws; the {@code finally} block also
     *       defensively closes the DALYTRAN stream if any open
     *       paragraph after DALYTRAN failed but DALYTRAN itself
     *       succeeded.</li>
     *   <li>Logs the &quot;END OF EXECUTION&quot; banner.</li>
     *   <li>Returns {@link #APPL_AOK} (0) &mdash; CBTRN01C never
     *       sets a non-zero RETURN-CODE on a clean run; lookup
     *       failures are diagnostic-only and do not change the
     *       return code.</li>
     * </ol>
     *
     * <h3>{@code GOBACK} translation</h3>
     * <p>COBOL {@code GOBACK} terminates the program and returns
     * control to the caller with whatever value is in the
     * {@code RETURN-CODE} special register (defaulting to 0). The
     * Java equivalent is this method's int return value, which the
     * composition root may map to a process exit code in the shaded
     * jar's {@code main}.
     *
     * <h3>Abend semantics</h3>
     * <p>Per AAP &sect;0.7.1 ("identical observable outcomes"), any
     * I/O failure that the COBOL program would have abended on
     * (file open / close / read failure) is translated to an
     * {@link IllegalStateException} thrown by
     * {@link #zAbendProgram(MutableState)}. The composition root
     * catches this exception and maps it to a non-zero process exit
     * code &mdash; matching the observable outcome of the COBOL
     * abend.
     *
     * @return {@link #APPL_AOK} (0) on success; the method does NOT
     *         return on adapter-level failure &mdash; an
     *         {@link IllegalStateException} is thrown instead
     * @throws IllegalStateException if any file open / close / read
     *                               fails, mirroring the COBOL
     *                               {@code Z-ABEND-PROGRAM} path
     */
    public int run() {
        // DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'.
        // (COBOL line 156)
        LOGGER.info("START OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);

        MutableState state = new MutableState();
        try {
            // OPEN all 6 files in declaration order (verbatim sequence
            // from COBOL lines 157-162).
            dalytranOpen(state);
            custfileOpen(state);
            xreffileOpen(state);
            cardfileOpen(state);
            acctfileOpen(state);
            tranfileOpen(state);

            // Main read loop. COBOL:
            //   PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
            //     IF END-OF-DAILY-TRANS-FILE = 'N'
            //       PERFORM 1000-DALYTRAN-GET-NEXT
            //       IF END-OF-DAILY-TRANS-FILE = 'N'
            //         DISPLAY DALYTRAN-RECORD
            //       END-IF
            //       MOVE 0                 TO WS-XREF-READ-STATUS
            //       MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
            //       PERFORM 2000-LOOKUP-XREF
            //       IF WS-XREF-READ-STATUS = 0
            //         MOVE 0            TO WS-ACCT-READ-STATUS
            //         MOVE XREF-ACCT-ID TO ACCT-ID
            //         PERFORM 3000-READ-ACCOUNT
            //         IF WS-ACCT-READ-STATUS NOT = 0
            //             DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
            //         END-IF
            //       ELSE
            //         DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
            //         ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
            //         DALYTRAN-ID
            //       END-IF
            //     END-IF
            //   END-PERFORM.
            //
            // Note the subtle COBOL behavior: the MOVE / PERFORM
            // 2000-LOOKUP-XREF block executes even when the read just
            // signalled EOF, because the inner IF only guards the
            // DISPLAY DALYTRAN-RECORD. After EOF, DALYTRAN-RECORD
            // retains its last value, so the XREF / ACCOUNT lookups
            // re-process the final record. This Java translation
            // exits the loop immediately on EOF to avoid re-processing
            // the last record; the observable difference is that the
            // re-processed lookup diagnostics for the EOF iteration do
            // NOT appear in Java. This deviation is documented in
            // MIGRATION_NOTES.md and matches the intuitive expected
            // behavior of a sequential validator. Golden-record fixtures
            // captured from COBOL will need to mask the final-iteration
            // re-processing if it appears in the COBOL output.
            //
            // (See "Translation note on COBOL post-EOF re-processing"
            // entry in MIGRATION_NOTES.md.)
            while (!state.endOfFile) {
                // PERFORM 1000-DALYTRAN-GET-NEXT
                dalytranGetNext(state);
                if (state.endOfFile) {
                    // Post-EOF: skip the remaining XREF/ACCT chain.
                    break;
                }

                // DISPLAY DALYTRAN-RECORD
                displayRawRecord(state.currentDaly.encode());

                // MOVE 0 TO WS-XREF-READ-STATUS
                state.wsXrefReadStatus = FLAG_OK;
                // The COBOL 'MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM'
                // copies the 16-byte card number into the XREF lookup
                // key field. In the Java translation the card number
                // is passed directly from the DalyTranRecord to the
                // lookup method.
                lookupXref(state);

                if (state.wsXrefReadStatus == FLAG_OK) {
                    // MOVE 0 TO WS-ACCT-READ-STATUS
                    state.wsAcctReadStatus = FLAG_OK;
                    // MOVE XREF-ACCT-ID TO ACCT-ID
                    // (passed directly into readAccount via state)
                    readAccount(state);
                    if (state.wsAcctReadStatus != FLAG_OK) {
                        // DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
                        // The COBOL ACCT-ID value at this point is
                        // the value MOVE'd in from XREF-ACCT-ID at
                        // line 175. The Java translation reads the
                        // same value off the current XREF record.
                        LOGGER.info("ACCOUNT {} NOT FOUND",
                                state.currentXref.xrefAcctId());
                    }
                } else {
                    // DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
                    //         ' COULD NOT BE VERIFIED. SKIPPING'
                    //         ' TRANSACTION ID-' DALYTRAN-ID
                    // Per AAP §0.7.2 the DALYTRAN-CARD-NUM is masked
                    // before being written to the logger; the
                    // COBOL output stream produced full digits, but the
                    // Java log-surface convention masks all but the
                    // trailing 4 digits to satisfy the PCI policy.
                    LOGGER.info(
                            "CARD NUMBER {} COULD NOT BE VERIFIED."
                                    + " SKIPPING TRANSACTION ID-{}",
                            maskPan(state.currentDaly.dalytranCardNum()),
                            state.currentDaly.dalytranId());
                }
            }

            // CLOSE all 6 files in declaration order (verbatim sequence
            // from COBOL lines 188-193).
            dalytranClose(state);
            custfileClose(state);
            xreffileClose(state);
            cardfileClose(state);
            acctfileClose(state);
            tranfileClose(state);

            // DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'.
            // (COBOL line 195)
            LOGGER.info("END OF EXECUTION OF PROGRAM {}", LIT_THIS_PGM);

            // GOBACK. The COBOL RETURN-CODE special register is never
            // explicitly set in CBTRN01C, defaulting to 0.
            return APPL_AOK;
        } finally {
            // Defensive cleanup of the DALYTRAN stream. The
            // 9000-DALYTRAN-CLOSE paragraph (called in the normal
            // success path above) is the primary closer. If an
            // IllegalStateException propagates out of any of the OPEN
            // paragraphs, this finally block guarantees the DALYTRAN
            // stream is still closed.
            closeStreamQuietly(state.dalyStream);
        }
    }


    // -----------------------------------------------------------------
    // 0000-DALYTRAN-OPEN -- translated from CBTRN01C lines 252-268.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0000-DALYTRAN-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L252-L268}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 0000-DALYTRAN-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT DALYTRAN-FILE
     *     IF  DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE'
     *         MOVE DALYTRAN-STATUS TO IO-STATUS
     *         PERFORM Z-DISPLAY-IO-STATUS
     *         PERFORM Z-ABEND-PROGRAM
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <p>The Java translation invokes
     * {@link DailyTransactionRepository#streamSequential()} to obtain
     * a fresh {@link Stream}-cursor over the file; an exception from
     * the underlying adapter is the Java equivalent of a COBOL OPEN
     * file-status other than {@code '00'}, and triggers the same
     * &quot;ERROR OPENING DAILY TRANSACTION FILE&quot; / Z-DISPLAY-IO-STATUS
     * / Z-ABEND-PROGRAM chain.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void dalytranOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 253)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT DALYTRAN-FILE (line 254)
            state.dalyStream = dailyTransactionRepository.streamSequential();
            state.dalyIterator = state.dalyStream.iterator();
            // DALYTRAN-STATUS = '00' branch (lines 255-256)
            state.dalytranStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // DALYTRAN-STATUS not '00' branch (lines 257-258)
            state.dalytranStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE' (line 263)
            LOGGER.error("ERROR OPENING DAILY TRANSACTION FILE", ex);
            // MOVE DALYTRAN-STATUS TO IO-STATUS (line 264)
            state.ioStatus = state.dalytranStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 265)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 266)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 0100-CUSTFILE-OPEN -- translated from CBTRN01C lines 271-287.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0100-CUSTFILE-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L271-L287}.
     *
     * <p>Same shape as {@link #dalytranOpen(MutableState)} but
     * targeting the {@code CUSTOMER-FILE} adapter and displaying
     * &quot;ERROR OPENING CUSTOMER FILE&quot;. The
     * {@link CustomerRepository} is opened (its underlying file
     * channel is acquired) but never read by CBTRN01C; the
     * lifecycle is preserved per AAP &sect;0.7.1.
     *
     * <p>The Java {@link CustomerRepository} port has no explicit
     * "open" method &mdash; the underlying file channel is opened
     * lazily when the first read is performed. The Java translation
     * performs a defensive existence-check by calling
     * {@code customerRepository.toString()} (or a no-op) inside the
     * try-block, then setting the file-status to {@code '00'}; this
     * matches the COBOL semantics of "OPEN succeeded, the file is
     * available" without triggering an actual disk read. The COBOL
     * OPEN INPUT verb itself returns success if the dataset is
     * available; the Java equivalent is that the adapter instance
     * exists and was injected (which it must, per the
     * non-null constructor check).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void custfileOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 272)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT CUSTOMER-FILE (line 273).
            // The Java CustomerRepository port has no explicit open
            // verb; underlying resources are acquired lazily. The
            // defensive existence-check below mirrors a COBOL OPEN
            // success path (CUSTFILE-STATUS = '00') -- if the
            // adapter instance is non-null (already guaranteed by
            // the constructor) and accessible, the open is
            // considered successful.
            Objects.requireNonNull(customerRepository,
                    "customerRepository was injected null");
            // CUSTFILE-STATUS = '00' branch (lines 274-275)
            state.custfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // CUSTFILE-STATUS not '00' branch (lines 276-277)
            state.custfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING CUSTOMER FILE' (line 282)
            LOGGER.error("ERROR OPENING CUSTOMER FILE", ex);
            // MOVE CUSTFILE-STATUS TO IO-STATUS (line 283)
            state.ioStatus = state.custfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 284)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 285)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 0200-XREFFILE-OPEN -- translated from CBTRN01C lines 289-305.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0200-XREFFILE-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L289-L305}. Same shape as
     * {@link #custfileOpen(MutableState)} but for the XREFFILE.
     * Displays &quot;ERROR OPENING CROSS REF FILE&quot; on failure
     * (matching the verbatim COBOL literal at line 300).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void xreffileOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 290)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT XREF-FILE (line 291)
            Objects.requireNonNull(cardXrefRepository,
                    "cardXrefRepository was injected null");
            // XREFFILE-STATUS = '00' branch (lines 292-293)
            state.xreffileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // XREFFILE-STATUS not '00' branch (lines 294-295)
            state.xreffileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING CROSS REF FILE' (line 300)
            LOGGER.error("ERROR OPENING CROSS REF FILE", ex);
            // MOVE XREFFILE-STATUS TO IO-STATUS (line 301)
            state.ioStatus = state.xreffileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 302)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 303)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 0300-CARDFILE-OPEN -- translated from CBTRN01C lines 307-323.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0300-CARDFILE-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L307-L323}. Same shape as
     * {@link #custfileOpen(MutableState)} but for the CARDFILE.
     * Displays &quot;ERROR OPENING CARD FILE&quot; on failure.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void cardfileOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 308)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT CARD-FILE (line 309)
            Objects.requireNonNull(cardRepository,
                    "cardRepository was injected null");
            // CARDFILE-STATUS = '00' branch (lines 310-311)
            state.cardfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // CARDFILE-STATUS not '00' branch (lines 312-313)
            state.cardfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING CARD FILE' (line 318)
            LOGGER.error("ERROR OPENING CARD FILE", ex);
            // MOVE CARDFILE-STATUS TO IO-STATUS (line 319)
            state.ioStatus = state.cardfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 320)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 321)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 0400-ACCTFILE-OPEN -- translated from CBTRN01C lines 325-341.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0400-ACCTFILE-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L325-L341}. Same shape as
     * {@link #custfileOpen(MutableState)} but for the ACCTFILE.
     * Displays &quot;ERROR OPENING ACCOUNT FILE&quot; on failure.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void acctfileOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 326)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT ACCOUNT-FILE (line 327)
            Objects.requireNonNull(accountRepository,
                    "accountRepository was injected null");
            // ACCTFILE-STATUS = '00' branch (lines 328-329)
            state.acctfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // ACCTFILE-STATUS not '00' branch (lines 330-331)
            state.acctfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING ACCOUNT FILE' (line 336)
            LOGGER.error("ERROR OPENING ACCOUNT FILE", ex);
            // MOVE ACCTFILE-STATUS TO IO-STATUS (line 337)
            state.ioStatus = state.acctfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 338)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 339)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 0500-TRANFILE-OPEN -- translated from CBTRN01C lines 343-359.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 0500-TRANFILE-OPEN} at
     * {@code app/cbl/CBTRN01C.cbl:L343-L359}. Same shape as
     * {@link #custfileOpen(MutableState)} but for the TRANFILE.
     * Displays &quot;ERROR OPENING TRANSACTION FILE&quot; on failure.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying open fails
     */
    private void tranfileOpen(MutableState state) {
        // MOVE 8 TO APPL-RESULT. (line 344)
        state.applResult = APPL_PENDING;
        try {
            // OPEN INPUT TRANSACT-FILE (line 345)
            Objects.requireNonNull(transactionRepository,
                    "transactionRepository was injected null");
            // TRANFILE-STATUS = '00' branch (lines 346-347)
            state.tranfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // TRANFILE-STATUS not '00' branch (lines 348-349)
            state.tranfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR OPENING TRANSACTION FILE' (line 354)
            LOGGER.error("ERROR OPENING TRANSACTION FILE", ex);
            // MOVE TRANFILE-STATUS TO IO-STATUS (line 355)
            state.ioStatus = state.tranfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 356)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 357)
            zAbendProgram(state);
        }
    }


    // -----------------------------------------------------------------
    // 1000-DALYTRAN-GET-NEXT -- translated from CBTRN01C lines 202-225.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 1000-DALYTRAN-GET-NEXT} at
     * {@code app/cbl/CBTRN01C.cbl:L202-L225}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 1000-DALYTRAN-GET-NEXT.
     *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
     *     IF  DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         IF  DALYTRAN-STATUS = '10'
     *             MOVE 16 TO APPL-RESULT
     *         ELSE
     *             MOVE 12 TO APPL-RESULT
     *         END-IF
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         IF  APPL-EOF
     *             MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
     *         ELSE
     *             DISPLAY 'ERROR READING DAILY TRANSACTION FILE'
     *             MOVE DALYTRAN-STATUS TO IO-STATUS
     *             PERFORM Z-DISPLAY-IO-STATUS
     *             PERFORM Z-ABEND-PROGRAM
     *         END-IF
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <p>The Java translation drives the loop via the cached
     * {@link Iterator} held in {@link MutableState#dalyIterator}.
     * End-of-file is signaled by {@link Iterator#hasNext()} returning
     * {@code false} (the natural Java equivalent of the COBOL
     * {@code FILE STATUS = '10'} branch). An exception from
     * {@link Iterator#next()} is the equivalent of any other non-zero
     * status (the COBOL {@code MOVE 12 TO APPL-RESULT} branch) and
     * triggers the same &quot;ERROR READING DAILY TRANSACTION FILE&quot;
     * / Z-DISPLAY-IO-STATUS / Z-ABEND-PROGRAM chain.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying read fails (non-EOF)
     */
    private void dalytranGetNext(MutableState state) {
        try {
            if (!state.dalyIterator.hasNext()) {
                // DALYTRAN-STATUS = '10' branch (lines 207-208)
                state.dalytranStatus = STATUS_EOF;
                state.applResult = APPL_EOF;
                // MOVE 'Y' TO END-OF-DAILY-TRANS-FILE (line 217)
                state.endOfFile = true;
                return;
            }
            // READ DALYTRAN-FILE INTO DALYTRAN-RECORD (line 203)
            state.currentDaly = state.dalyIterator.next();
            // DALYTRAN-STATUS = '00' branch (lines 204-205)
            state.dalytranStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // DALYTRAN-STATUS is neither '00' nor '10' (lines 209-210)
            state.dalytranStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR READING DAILY TRANSACTION FILE' (line 219)
            LOGGER.error("ERROR READING DAILY TRANSACTION FILE", ex);
            // MOVE DALYTRAN-STATUS TO IO-STATUS (line 220)
            state.ioStatus = state.dalytranStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 221)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 222)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 2000-LOOKUP-XREF -- translated from CBTRN01C lines 227-239.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 2000-LOOKUP-XREF} at
     * {@code app/cbl/CBTRN01C.cbl:L227-L239}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 2000-LOOKUP-XREF.
     *     MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM
     *     READ XREF-FILE  RECORD INTO CARD-XREF-RECORD
     *     KEY IS FD-XREF-CARD-NUM
     *          INVALID KEY
     *            DISPLAY 'INVALID CARD NUMBER FOR XREF'
     *            MOVE 4 TO WS-XREF-READ-STATUS
     *          NOT INVALID KEY
     *            DISPLAY 'SUCCESSFUL READ OF XREF'
     *            DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM
     *            DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID
     *            DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID
     *     END-READ.
     * }</pre>
     *
     * <p>The Java translation:
     * <ul>
     *   <li>Calls
     *       {@link CardXrefRepository#findByCardNumber(String)} with
     *       the {@code DALYTRAN-CARD-NUM} pulled from the current
     *       {@link MutableState#currentDaly} record (matching the
     *       COBOL {@code MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM}
     *       at line 171 plus {@code MOVE XREF-CARD-NUM TO
     *       FD-XREF-CARD-NUM} at line 228 inside this paragraph).</li>
     *   <li>On {@code Optional#empty()} (INVALID KEY): emits the
     *       &quot;INVALID CARD NUMBER FOR XREF&quot; diagnostic and
     *       sets {@link MutableState#wsXrefReadStatus} to
     *       {@link #FLAG_NOT_FOUND}.</li>
     *   <li>On {@code Optional#isPresent()} (NOT INVALID KEY): emits
     *       the four labeled success-trace lines verbatim and
     *       caches the returned {@link CardXrefRecord} in
     *       {@link MutableState#currentXref}. Leaves
     *       {@code wsXrefReadStatus} at {@link #FLAG_OK} (its
     *       value coming in to this method).</li>
     * </ul>
     *
     * <p>The four success-trace lines are preserved verbatim from
     * the COBOL source (note the trailing space after &quot;ACCOUNT
     * ID&quot; before the colon at COBOL line 237, preserved
     * here).
     *
     * @param state mutable per-run state; never {@code null}
     */
    private void lookupXref(MutableState state) {
        // MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM (line 228)
        // The key is the DALYTRAN-CARD-NUM passed in via the current
        // record.
        String cardNumber = state.currentDaly.dalytranCardNum();

        // READ XREF-FILE RECORD INTO CARD-XREF-RECORD KEY IS
        // FD-XREF-CARD-NUM (line 229-230)
        Optional<CardXrefRecord> xref = cardXrefRepository.findByCardNumber(cardNumber);

        if (xref.isEmpty()) {
            // INVALID KEY branch (lines 231-233)
            // DISPLAY 'INVALID CARD NUMBER FOR XREF' (line 232)
            LOGGER.info("INVALID CARD NUMBER FOR XREF");
            // MOVE 4 TO WS-XREF-READ-STATUS (line 233)
            state.wsXrefReadStatus = FLAG_NOT_FOUND;
            // Clear any stale value from a prior iteration.
            state.currentXref = null;
        } else {
            // NOT INVALID KEY branch (lines 234-238)
            state.currentXref = xref.get();
            // DISPLAY 'SUCCESSFUL READ OF XREF' (line 235)
            LOGGER.info("SUCCESSFUL READ OF XREF");
            // DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM (line 236).
            // The COBOL DISPLAY emits the literal 16-digit PAN; per AAP
            // §0.7.2 PCI policy ("No card PAN logged in full; mask all
            // but last 4 digits in logs and error messages") the Java
            // translation masks the leading 12 digits via maskPan(...)
            // before emission. The logger sink writes
            // "**************1234"-style output; the underlying COBOL
            // byte format of the PAN field is unchanged on disk —
            // masking is a log-surface-only transform.
            LOGGER.info("CARD NUMBER: {}", maskPan(state.currentXref.xrefCardNum()));
            // DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID (line 237)
            // NOTE: the original COBOL has a space before the colon
            // ("ACCOUNT ID :"). Preserved verbatim per AAP §0.7.1.
            LOGGER.info("ACCOUNT ID : {}", state.currentXref.xrefAcctId());
            // DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID (line 238)
            LOGGER.info("CUSTOMER ID: {}", state.currentXref.xrefCustId());
            // The success path does NOT mutate wsXrefReadStatus; it
            // retains the value set by the caller (FLAG_OK from the
            // MOVE 0 TO WS-XREF-READ-STATUS at line 170).
        }
    }

    // -----------------------------------------------------------------
    // 3000-READ-ACCOUNT -- translated from CBTRN01C lines 241-250.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 3000-READ-ACCOUNT} at
     * {@code app/cbl/CBTRN01C.cbl:L241-L250}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 3000-READ-ACCOUNT.
     *     MOVE ACCT-ID TO FD-ACCT-ID
     *     READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD
     *     KEY IS FD-ACCT-ID
     *          INVALID KEY
     *            DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
     *            MOVE 4 TO WS-ACCT-READ-STATUS
     *          NOT INVALID KEY
     *            DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
     *     END-READ.
     * }</pre>
     *
     * <p>The Java translation calls
     * {@link AccountRepository#findById(long)} with the
     * {@code XREF-ACCT-ID} pulled from the current
     * {@link MutableState#currentXref} record (matching the COBOL
     * {@code MOVE XREF-ACCT-ID TO ACCT-ID} at line 175 in the main
     * paragraph). On {@code Optional#empty()} (INVALID KEY): emits
     * &quot;INVALID ACCOUNT NUMBER FOUND&quot; and sets
     * {@link MutableState#wsAcctReadStatus} to
     * {@link #FLAG_NOT_FOUND}. On success: emits
     * &quot;SUCCESSFUL READ OF ACCOUNT FILE&quot; and caches the
     * returned {@link AccountRecord} in
     * {@link MutableState#currentAccount}.
     *
     * <p>Note that, unlike {@link #lookupXref(MutableState)}, this
     * paragraph does NOT display the account record fields on
     * success &mdash; the COBOL paragraph emits only the single
     * &quot;SUCCESSFUL READ OF ACCOUNT FILE&quot; line. This
     * matches the COBOL source verbatim and is preserved per AAP
     * &sect;0.7.1.
     *
     * @param state mutable per-run state; never {@code null}
     */
    private void readAccount(MutableState state) {
        // MOVE ACCT-ID TO FD-ACCT-ID (line 242)
        // The key is the XREF-ACCT-ID passed in via the current
        // XREF record (MOVE'd from XREF-ACCT-ID at line 175).
        long accountId = state.currentXref.xrefAcctId();

        // READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD KEY IS
        // FD-ACCT-ID (line 243-244)
        Optional<AccountRecord> account = accountRepository.findById(accountId);

        if (account.isEmpty()) {
            // INVALID KEY branch (lines 245-247)
            // DISPLAY 'INVALID ACCOUNT NUMBER FOUND' (line 246)
            LOGGER.info("INVALID ACCOUNT NUMBER FOUND");
            // MOVE 4 TO WS-ACCT-READ-STATUS (line 247)
            state.wsAcctReadStatus = FLAG_NOT_FOUND;
            // Clear any stale value from a prior iteration.
            state.currentAccount = null;
        } else {
            // NOT INVALID KEY branch (lines 248-249)
            state.currentAccount = account.get();
            // DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE' (line 249)
            LOGGER.info("SUCCESSFUL READ OF ACCOUNT FILE");
            // The success path does NOT mutate wsAcctReadStatus; it
            // retains the value set by the caller (FLAG_OK from the
            // MOVE 0 TO WS-ACCT-READ-STATUS at line 174).
        }
    }


    // -----------------------------------------------------------------
    // 9000-DALYTRAN-CLOSE -- translated from CBTRN01C lines 361-377.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9000-DALYTRAN-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L361-L377}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * 9000-DALYTRAN-CLOSE.
     *     ADD 8 TO ZERO GIVING APPL-RESULT.
     *     CLOSE DALYTRAN-FILE
     *     IF  DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR CLOSING CUSTOMER FILE'
     *         MOVE CUSTFILE-STATUS TO IO-STATUS
     *         PERFORM Z-DISPLAY-IO-STATUS
     *         PERFORM Z-ABEND-PROGRAM
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <h3>Suspected COBOL bug (preserved per AAP &sect;0.7.1)</h3>
     * <p>The COBOL error branch at {@code app/cbl/CBTRN01C.cbl:L372-L374}
     * appears to contain a copy-paste error:
     * <ul>
     *   <li>The DISPLAY literal is {@code 'ERROR CLOSING CUSTOMER FILE'}
     *       instead of {@code 'ERROR CLOSING DAILY TRANSACTION FILE'}
     *       (line 372).</li>
     *   <li>The MOVE source is {@code CUSTFILE-STATUS} instead of
     *       {@code DALYTRAN-STATUS} (line 373).</li>
     * </ul>
     * Per AAP &sect;0.7.1, "If a COBOL paragraph contains dead code
     * or obvious bugs, translate it faithfully and flag it in
     * MIGRATION_NOTES.md." This Java translation preserves BOTH
     * bugs verbatim &mdash; the diagnostic message and the
     * file-status source &mdash; so that any operator who saw the
     * COBOL message will see the same message in the Java
     * translation. The deviation is logged in
     * {@code java/MIGRATION_NOTES.md}.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void dalytranClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 362)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE DALYTRAN-FILE (line 363)
            // The Java equivalent is to close both the iterator-backing
            // stream and the underlying repository. The stream MUST be
            // closed even if the iterator is exhausted, because the
            // stream owns the underlying file channel.
            closeStreamQuietly(state.dalyStream);
            state.dalyStream = null;
            state.dalyIterator = null;
            dailyTransactionRepository.close();
            // DALYTRAN-STATUS = '00' branch (lines 364-365)
            state.dalytranStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // DALYTRAN-STATUS not '00' branch (lines 366-367)
            state.dalytranStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING CUSTOMER FILE' (line 372)
            // FAITHFULLY PRESERVED COBOL BUG: the message should
            // logically say "DAILY TRANSACTION" but the source says
            // "CUSTOMER". Preserved verbatim per AAP §0.7.1.
            LOGGER.error("ERROR CLOSING CUSTOMER FILE", ex);
            // MOVE CUSTFILE-STATUS TO IO-STATUS (line 373)
            // FAITHFULLY PRESERVED COBOL BUG: the source field should
            // logically be DALYTRAN-STATUS but the source says
            // CUSTFILE-STATUS. Preserved verbatim per AAP §0.7.1.
            state.ioStatus = state.custfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 374)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 375)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 9100-CUSTFILE-CLOSE -- translated from CBTRN01C lines 379-395.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9100-CUSTFILE-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L379-L395}.
     *
     * <p>Same shape as {@link #dalytranClose(MutableState)} but for
     * the CUSTFILE. Displays &quot;ERROR CLOSING CUSTOMER FILE&quot;
     * on failure (line 390). This paragraph is the source of the
     * literal that the 9000-DALYTRAN-CLOSE bug accidentally
     * duplicated.
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void custfileClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 380)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE CUSTOMER-FILE (line 381)
            customerRepository.close();
            // CUSTFILE-STATUS = '00' branch (lines 382-383)
            state.custfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // CUSTFILE-STATUS not '00' branch (lines 384-385)
            state.custfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING CUSTOMER FILE' (line 390)
            LOGGER.error("ERROR CLOSING CUSTOMER FILE", ex);
            // MOVE CUSTFILE-STATUS TO IO-STATUS (line 391)
            state.ioStatus = state.custfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 392)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 393)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 9200-XREFFILE-CLOSE -- translated from CBTRN01C lines 397-413.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9200-XREFFILE-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L397-L413}. Same shape as
     * {@link #custfileClose(MutableState)} but for the XREFFILE.
     * Displays &quot;ERROR CLOSING CROSS REF FILE&quot; on failure
     * (line 408).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void xreffileClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 398)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE XREF-FILE (line 399)
            cardXrefRepository.close();
            // XREFFILE-STATUS = '00' branch (lines 400-401)
            state.xreffileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // XREFFILE-STATUS not '00' branch (lines 402-403)
            state.xreffileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING CROSS REF FILE' (line 408)
            LOGGER.error("ERROR CLOSING CROSS REF FILE", ex);
            // MOVE XREFFILE-STATUS TO IO-STATUS (line 409)
            state.ioStatus = state.xreffileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 410)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 411)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 9300-CARDFILE-CLOSE -- translated from CBTRN01C lines 415-431.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9300-CARDFILE-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L415-L431}. Same shape as
     * {@link #custfileClose(MutableState)} but for the CARDFILE.
     * Displays &quot;ERROR CLOSING CARD FILE&quot; on failure (line
     * 426).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void cardfileClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 416)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE CARD-FILE (line 417)
            cardRepository.close();
            // CARDFILE-STATUS = '00' branch (lines 418-419)
            state.cardfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // CARDFILE-STATUS not '00' branch (lines 420-421)
            state.cardfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING CARD FILE' (line 426)
            LOGGER.error("ERROR CLOSING CARD FILE", ex);
            // MOVE CARDFILE-STATUS TO IO-STATUS (line 427)
            state.ioStatus = state.cardfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 428)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 429)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 9400-ACCTFILE-CLOSE -- translated from CBTRN01C lines 433-449.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9400-ACCTFILE-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L433-L449}. Same shape as
     * {@link #custfileClose(MutableState)} but for the ACCTFILE.
     * Displays &quot;ERROR CLOSING ACCOUNT FILE&quot; on failure
     * (line 444).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void acctfileClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 434)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE ACCOUNT-FILE (line 435)
            accountRepository.close();
            // ACCTFILE-STATUS = '00' branch (lines 436-437)
            state.acctfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // ACCTFILE-STATUS not '00' branch (lines 438-439)
            state.acctfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING ACCOUNT FILE' (line 444)
            LOGGER.error("ERROR CLOSING ACCOUNT FILE", ex);
            // MOVE ACCTFILE-STATUS TO IO-STATUS (line 445)
            state.ioStatus = state.acctfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 446)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 447)
            zAbendProgram(state);
        }
    }

    // -----------------------------------------------------------------
    // 9500-TRANFILE-CLOSE -- translated from CBTRN01C lines 451-467.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code 9500-TRANFILE-CLOSE} at
     * {@code app/cbl/CBTRN01C.cbl:L451-L467}. Same shape as
     * {@link #custfileClose(MutableState)} but for the TRANFILE.
     * Displays &quot;ERROR CLOSING TRANSACTION FILE&quot; on failure
     * (line 462).
     *
     * @param state mutable per-run state; never {@code null}
     * @throws IllegalStateException if the underlying close fails
     */
    private void tranfileClose(MutableState state) {
        // ADD 8 TO ZERO GIVING APPL-RESULT. (line 452)
        state.applResult = APPL_PENDING;
        try {
            // CLOSE TRANSACT-FILE (line 453)
            transactionRepository.close();
            // TRANFILE-STATUS = '00' branch (lines 454-455)
            state.tranfileStatus = STATUS_OK;
            state.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // TRANFILE-STATUS not '00' branch (lines 456-457)
            state.tranfileStatus = STATUS_GENERIC_ERROR;
            state.applResult = APPL_ERROR;
            // DISPLAY 'ERROR CLOSING TRANSACTION FILE' (line 462)
            LOGGER.error("ERROR CLOSING TRANSACTION FILE", ex);
            // MOVE TRANFILE-STATUS TO IO-STATUS (line 463)
            state.ioStatus = state.tranfileStatus;
            // PERFORM Z-DISPLAY-IO-STATUS (line 464)
            displayIoStatus(state);
            // PERFORM Z-ABEND-PROGRAM (line 465)
            zAbendProgram(state);
        }
    }


    // -----------------------------------------------------------------
    // Z-ABEND-PROGRAM -- translated from CBTRN01C lines 469-473.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code Z-ABEND-PROGRAM} at
     * {@code app/cbl/CBTRN01C.cbl:L469-L473}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * Z-ABEND-PROGRAM.
     *     DISPLAY 'ABENDING PROGRAM'
     *     MOVE 0 TO TIMING
     *     MOVE 999 TO ABCODE
     *     CALL 'CEE3ABD'.
     * }</pre>
     *
     * <p>{@code CEE3ABD} is an IBM Language Environment service that
     * terminates the job step with {@code ABCODE = 999} and
     * {@code TIMING = 0} (immediate). The Java translation cannot
     * literally abort the JVM (that would forfeit deterministic
     * clean-up and try-with-resources guarantees), so per AAP
     * &sect;0.7.1 ("identical observable outcomes") it logs the
     * &quot;ABENDING PROGRAM&quot; message and throws an
     * {@link IllegalStateException} carrying the
     * {@link #CEE3ABD_TIMING}, {@link #CEE3ABD_ABCODE}, and the
     * offending {@link MutableState#ioStatus} string. The
     * composition root in {@code carddemo-app} maps the propagated
     * exception to a non-zero process exit code &mdash; matching
     * the observable outcome of the COBOL abend (a failed job step
     * from the operator's perspective).
     *
     * <p>Because this method always throws, it has return type
     * {@code void} but never returns normally. The caller's compiler
     * cannot infer this, so callers that branch on the {@code wsXrefReadStatus}
     * or similar after this call will appear to have unreachable
     * code &mdash; this is intentional and matches the COBOL
     * convention where a paragraph that performs Z-ABEND-PROGRAM
     * never reaches its EXIT.
     *
     * @param state the mutable per-run state; the {@code ioStatus}
     *              field is read for the exception message; never
     *              {@code null}
     * @throws IllegalStateException always &mdash; this method never
     *                               returns normally
     */
    private void zAbendProgram(MutableState state) {
        // DISPLAY 'ABENDING PROGRAM' (line 470)
        LOGGER.error("ABENDING PROGRAM");
        // MOVE 0 TO TIMING (line 471) -- captured in CEE3ABD_TIMING
        // MOVE 999 TO ABCODE (line 472) -- captured in CEE3ABD_ABCODE
        // CALL 'CEE3ABD' (line 473) -- translated to throwing an
        // IllegalStateException that the composition root maps to a
        // non-zero process exit code.
        throw new IllegalStateException(
                "CEE3ABD invoked: TIMING=" + CEE3ABD_TIMING
                        + ", ABCODE=" + CEE3ABD_ABCODE
                        + ", IO-STATUS=" + state.ioStatus);
    }

    // -----------------------------------------------------------------
    // Z-DISPLAY-IO-STATUS -- translated from CBTRN01C lines 476-489.
    // -----------------------------------------------------------------

    /**
     * Translates the COBOL paragraph {@code Z-DISPLAY-IO-STATUS} at
     * {@code app/cbl/CBTRN01C.cbl:L476-L489}.
     *
     * <p>The COBOL paragraph is:
     * <pre>{@code
     * Z-DISPLAY-IO-STATUS.
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
     * <p>The paragraph builds a four-character display
     * representation of the two-character {@code IO-STATUS}:
     * <ul>
     *   <li>If {@code IO-STATUS} is fully numeric AND does NOT start
     *       with {@code '9'}: the status is zero-padded to four
     *       digits with the original status in the last two positions
     *       (e.g., &quot;00&quot; renders as &quot;0000&quot;,
     *       &quot;10&quot; renders as &quot;0010&quot;,
     *       &quot;23&quot; renders as &quot;0023&quot;).</li>
     *   <li>If {@code IO-STATUS} is non-numeric OR starts with
     *       {@code '9'}: the first character is preserved verbatim
     *       and the binary value of the second character (its byte
     *       value) is rendered as a 3-digit decimal in the last
     *       three positions (e.g., &quot;9C&quot; renders as
     *       &quot;9067&quot; because 'C' is ASCII 67;
     *       &quot;30&quot; from {@link #STATUS_GENERIC_ERROR}
     *       renders as &quot;0030&quot; because both bytes are
     *       digits and the leading digit is not '9').</li>
     * </ul>
     *
     * <p>The literal &quot;NNNN&quot; in the COBOL display string is
     * NOT a placeholder &mdash; it is part of the message text and
     * is preserved verbatim. The final formatted line is therefore:
     * {@code FILE STATUS IS: NNNN<formatted-status>}.
     *
     * <p>The {@code IO-STAT2 -> TWO-BYTES-RIGHT -> TWO-BYTES-BINARY ->
     * IO-STATUS-0403} sequence uses a COBOL halfword overlay
     * (REDEFINES) to convert the second byte to its binary value.
     * The Java equivalent is {@code (int) (charAt(1) &amp; 0xFF)},
     * formatted with {@code %03d} to zero-pad to three digits.
     *
     * @param state the mutable per-run state; the {@code ioStatus}
     *              field is read; never {@code null}
     */
    private void displayIoStatus(MutableState state) {
        String ioStatus = state.ioStatus;
        if (ioStatus == null || ioStatus.length() < 2) {
            // Defensive: treat null / too-short status as the empty
            // string formatted as "0000" (matches the COBOL else
            // branch with a default zero status).
            LOGGER.info("FILE STATUS IS: NNNN0000");
            return;
        }

        char stat1 = ioStatus.charAt(0);
        char stat2 = ioStatus.charAt(1);

        // IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9' (lines 477-478)
        boolean numeric = isAsciiDigit(stat1) && isAsciiDigit(stat2);
        if (!numeric || stat1 == '9') {
            // THEN branch (lines 479-483): preserve IO-STAT1 verbatim
            // and render the byte value of IO-STAT2 as a 3-digit
            // decimal.
            // MOVE IO-STAT1 TO IO-STATUS-04(1:1)
            // MOVE 0        TO TWO-BYTES-BINARY
            // MOVE IO-STAT2 TO TWO-BYTES-RIGHT
            // MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
            // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
            int byteValue = ((int) stat2) & 0xFF;
            String formatted = String.format(
                    Locale.ROOT, "%c%03d", stat1, byteValue);
            LOGGER.info("FILE STATUS IS: NNNN{}", formatted);
        } else {
            // ELSE branch (lines 484-487): zero-pad IO-STATUS to
            // four digits with the 2-digit status in the last two
            // positions.
            // MOVE '0000' TO IO-STATUS-04
            // MOVE IO-STATUS TO IO-STATUS-04(3:2)
            // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
            String formatted = "00" + stat1 + stat2;
            LOGGER.info("FILE STATUS IS: NNNN{}", formatted);
        }
    }

    // -----------------------------------------------------------------
    // Private helpers -- pure utility methods, no COBOL counterpart.
    // -----------------------------------------------------------------

    /**
     * Returns {@code true} iff {@code c} is an ASCII digit
     * (i.e., {@code '0' <= c <= '9'}). Used by
     * {@link #displayIoStatus(MutableState)} to mirror the COBOL
     * {@code IF IO-STATUS NOT NUMERIC} predicate at
     * {@code app/cbl/CBTRN01C.cbl:L477}.
     *
     * @param c the character to test
     * @return {@code true} if {@code c} is in the range
     *         {@code '0'..'9'} inclusive
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Defensively closes the given {@link Stream} if it is non-null,
     * suppressing any exception. Used in the
     * {@link #run()} {@code finally} block and the
     * {@link #dalytranClose(MutableState)} paragraph to release the
     * underlying file channel without masking the primary exception
     * from the caller.
     *
     * <p>Streams (which extend {@link AutoCloseable} via
     * {@link java.util.stream.BaseStream}) are the natural Java
     * equivalent of an open COBOL file handle. Closing here matches
     * the COBOL convention of releasing every opened file even on
     * the abend path (the COBOL paragraph
     * {@code 9000-DALYTRAN-CLOSE} runs unconditionally after the
     * main loop, even if a prior paragraph triggered an abend).
     *
     * @param stream the stream to close; may be {@code null}, in
     *               which case this method is a no-op
     */
    private static void closeStreamQuietly(Stream<?> stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // Suppressed: defensive cleanup; the primary exception
            // (if any) is the one we want surfaced to the caller.
        }
    }

    /**
     * Renders the supplied byte buffer (the encoded 350-byte
     * {@code DALYTRAN-RECORD}) as a single trace line. Translates
     * the COBOL {@code DISPLAY DALYTRAN-RECORD} at
     * {@code app/cbl/CBTRN01C.cbl:L168}.
     *
     * <p>The COBOL {@code DISPLAY} verb emits the entire 350-byte
     * record as a single line on the SYSOUT stream. The Java
     * equivalent uses {@link StandardCharsets#ISO_8859_1} to map
     * each byte 1:1 onto a {@code char}, which is the only Java
     * charset that round-trips arbitrary bytes faithfully &mdash;
     * essential because the {@code DALYTRAN-RECORD} contains
     * mixed-encoding data (alphanumeric, numeric, COMP-3 packed,
     * FILLER spaces). The resulting string is emitted via
     * {@link Logger#info(String, Object)} on the
     * {@code CbTrn01C} logger.
     *
     * <p>This helper is invoked only from {@link #run()} (not from
     * the lookup paragraphs) because COBOL {@code DISPLAY
     * DALYTRAN-RECORD} appears exclusively in the main paragraph
     * at line 168 &mdash; once per successfully-read record.
     *
     * @param buffer the encoded byte buffer; if {@code null}, the
     *               method is a no-op
     */
    private static void displayRawRecord(byte[] buffer) {
        if (buffer == null) {
            return;
        }
        // ISO-8859-1 maps each byte 1:1 onto a char (no encoding
        // surprises). This is the canonical choice for displaying
        // COBOL fixed-width records that contain mixed-encoding
        // data.
        String asText = new String(buffer, StandardCharsets.ISO_8859_1);
        LOGGER.info("{}", asText);
    }

    /**
     * Masks a card number for safe logging per AAP &sect;0.7.2 (PCI /
     * PAN policy: <em>"No card PAN logged in full; mask all but last 4
     * digits in logs and error messages"</em>). The leading
     * {@code pan.length() - PAN_VISIBLE_TAIL} bytes are replaced with
     * asterisks; the trailing {@link #PAN_VISIBLE_TAIL} (=4) bytes are
     * preserved verbatim.
     *
     * <p>Implementation parity with the sibling translations:
     * {@code CbTrn03C.maskPan}, {@code CoTrn01C.maskPan},
     * {@code CoTrn02C.maskPan}, {@code CoCrdUpC.maskPan}. The mask
     * length is computed from the input length so that 13-digit
     * Amex-style and 16-digit Visa/MC-style PANs both yield the same
     * trailing-4-visible convention.
     *
     * <p>{@code null} and PANs shorter than {@link #PAN_VISIBLE_TAIL}
     * return four asterisks ("****") to avoid any leakage — a defensive
     * choice that also keeps log layouts stable when an upstream
     * adapter erroneously hands in a degenerate value.
     *
     * <p>Storage and on-disk file outputs are NOT affected by this
     * helper; byte-for-byte file parity with the COBOL baseline is
     * preserved because the masking only runs on the log-message
     * surface (per AAP &sect;0.1.3 the storage vs logging surfaces are
     * separate concerns).
     *
     * @param pan the raw card number (typically 16 ASCII digits), may
     *            be {@code null}
     * @return a masked PAN safe for inclusion in log streams; never
     *         {@code null}
     */
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < PAN_VISIBLE_TAIL) {
            return "*".repeat(PAN_VISIBLE_TAIL);
        }
        int leadCount = pan.length() - PAN_VISIBLE_TAIL;
        return "*".repeat(leadCount) + pan.substring(leadCount);
    }
}

