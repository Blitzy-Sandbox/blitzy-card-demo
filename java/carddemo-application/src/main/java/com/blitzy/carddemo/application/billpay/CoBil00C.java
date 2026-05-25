/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.application.billpay;

import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.util.Decimals;

/**
 * Java&nbsp;25 LTS translation of the COBOL online CICS program {@code COBIL00C}
 * (CICS transaction {@code CB00}) at {@code app/cbl/COBIL00C.cbl} &mdash; the
 * Bill Payment screen that pays an account's entire current balance in a single
 * action.
 *
 * <h2>Program Purpose</h2>
 * <p>An operator enters an account number and a Y/N confirmation. The program
 * loads the account, displays its current balance, and &mdash; on
 * confirmation {@code Y} &mdash; performs the following atomic-from-the-user's-
 * perspective sequence:
 * <ol>
 *   <li>Look up the cross-reference card number via the {@code CXACAIX} AIX
 *       on {@code CARDXREF} (port: {@link CardXrefRepository}).</li>
 *   <li>Allocate the next sequential {@code TRAN-ID} via the
 *       {@code STARTBR(HIGH-VALUES) + READPREV + ENDBR} idiom against
 *       {@code TRANSACT} (port: {@link TransactionRepository#findHighestId()}).</li>
 *   <li>Build a new {@link TranRecord} populated with the COBOL constants
 *       ({@code TRAN-TYPE-CD='02'}, {@code TRAN-CAT-CD=2},
 *       {@code TRAN-SOURCE='POS TERM'}, {@code TRAN-DESC='BILL PAYMENT - ONLINE'},
 *       {@code TRAN-MERCHANT-ID=999999999},
 *       {@code TRAN-MERCHANT-NAME='BILL PAYMENT'},
 *       {@code TRAN-MERCHANT-CITY='N/A'}, {@code TRAN-MERCHANT-ZIP='N/A'},
 *       {@code TRAN-AMT = ACCT-CURR-BAL}, {@code TRAN-CARD-NUM = XREF-CARD-NUM},
 *       {@code TRAN-ORIG-TS = TRAN-PROC-TS = current timestamp}).</li>
 *   <li>Persist the new transaction via {@link TransactionRepository#save}.</li>
 *   <li>Subtract {@code TRAN-AMT} from {@code ACCT-CURR-BAL} using
 *       {@link Decimals#subtract(BigDecimal, BigDecimal, int, RoundingMode)} at
 *       scale 2 with HALF_EVEN rounding (banker's rounding).</li>
 *   <li>Rewrite the account record via {@link AccountRepository#save}.</li>
 * </ol>
 *
 * <h2>COBOL Paragraph &rarr; Java Method Mapping</h2>
 * <table border="1" summary="Paragraph mapping">
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td>
 *       <td>{@link #execute(CoBil00Input, CardDemoCommarea, AidKey)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td>
 *       <td>{@link #processEnterKey(MutableState)}</td></tr>
 *   <tr><td>{@code GET-CURRENT-TIMESTAMP}</td>
 *       <td>{@link #getCurrentTimestamp()}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td>
 *       <td>{@link #returnToPrevScreen(MutableState, String)}</td></tr>
 *   <tr><td>{@code SEND-BILLPAY-SCREEN}</td>
 *       <td>{@link #sendBillpayScreen(MutableState)}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td>
 *       <td>{@link #populateHeaderInfo(MutableState)}</td></tr>
 *   <tr><td>{@code READ-ACCTDAT-FILE}</td>
 *       <td>{@link #readAcctDatFile(MutableState, long)}</td></tr>
 *   <tr><td>{@code UPDATE-ACCTDAT-FILE}</td>
 *       <td>{@link #updateAcctDatFile(MutableState, AccountRecord)}</td></tr>
 *   <tr><td>{@code READ-CXACAIX-FILE}</td>
 *       <td>{@link #readCxAcAixFile(MutableState, long)}</td></tr>
 *   <tr><td>{@code STARTBR + READPREV + ENDBR-TRANSACT-FILE}</td>
 *       <td>{@link #findHighestTransactionIdNumeric(MutableState)} (coalesced)</td></tr>
 *   <tr><td>{@code WRITE-TRANSACT-FILE}</td>
 *       <td>{@link #writeTransactFile(MutableState, TranRecord)}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td>
 *       <td>{@link #clearCurrentScreen(MutableState)}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td>
 *       <td>{@link #initializeAllFields(MutableState)}</td></tr>
 * </table>
 *
 * <h2>Architectural Compliance (AAP &sect;0.3, &sect;0.6, &sect;0.7)</h2>
 * <ul>
 *   <li><b>Constructor injection</b> of five collaborators
 *       ({@link AccountRepository}, {@link CardXrefRepository},
 *       {@link TransactionRepository}, {@link ProgramRegistry},
 *       {@link Clock}).</li>
 *   <li><b>Sealed {@link Outcome}</b> with two record permits
 *       ({@link Outcome.SendMap}, {@link Outcome.Xctl}) modelling the CICS
 *       SEND-MAP/RETURN versus XCTL dispositions.</li>
 *   <li><b>Pattern-matching switch</b> over the {@link AidKey} sealed hierarchy
 *       with exhaustiveness checking and <strong>NO {@code default} branch</strong>
 *       (AAP &sect;0.6.7). The Java&nbsp;25 compiler enforces that every one of
 *       the sixteen {@code CcWorkAreas.AidKey} permits is handled.</li>
 *   <li><b>{@link BigDecimal}</b> with {@link RoundingMode#HALF_EVEN} for the
 *       monetary {@code TRAN-AMT} field via {@link Decimals}; never
 *       {@code double}/{@code float}.</li>
 *   <li><b>{@link java.time}</b> for current-date/-time header formatting; never
 *       {@link java.util.Date} or {@link java.util.Calendar}.</li>
 *   <li><b>SLF4J</b> for all error logging; no card PAN is logged in full per
 *       AAP &sect;0.7.2.</li>
 *   <li><b>JEP&nbsp;511 module import declaration</b> ({@code import module java.base;})
 *       avoids the long list of {@code java.util.*}/{@code java.math.*}/
 *       {@code java.time.*} imports.</li>
 *   <li><b>No</b> Spring container, no Hibernate/JPA, no Lombok, no reflection,
 *       no {@code ThreadLocal}, no preview features
 *       (AAP &sect;0.6.7, &sect;0.7.3, &sect;0.7.4).</li>
 * </ul>
 *
 * <h2>Translation Fidelity Highlights (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>The COBOL {@code STRING} concatenation
 *       <code>'Payment successful. '</code> + <code>' Your Transaction ID is '</code>
 *       + {@code TRAN-ID} + <code>'.'</code> produces TWO consecutive spaces
 *       between {@code "successful."} and {@code "Your"}. This double-space is
 *       preserved verbatim in {@link #MSG_PAYMENT_SUCCESS_TEMPLATE}.</li>
 *   <li>The verbatim COBOL typo {@code "Tran ID already exist..."} (missing
 *       trailing 's') is preserved in {@link #MSG_TRAN_DUPLICATE}.</li>
 *   <li>Every error message keeps its trailing ellipsis exactly as in the
 *       COBOL source.</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP} explicitly
 *       {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}, so the Java translation
 *       truncates to second precision via {@link LocalDateTime#withNano(int)}
 *       passing {@code 0}.</li>
 *   <li>The COBOL {@code MOVE WS-TRAN-ID-NUM PIC 9(16) TO TRAN-ID PIC X(16)}
 *       produces a 16-character right-justified zero-padded numeric string;
 *       the Java equivalent is {@code String.format("%016d", longValue)}.</li>
 *   <li>The COBOL {@code READPREV ENDFILE} branch
 *       {@code MOVE ZEROS TO TRAN-ID} means the first transaction gets ID
 *       {@code 0000000000000001} when the file is empty; the Java translation
 *       returns {@code 0L} when {@link TransactionRepository#findHighestId()}
 *       is empty and the caller adds {@code 1L}.</li>
 *   <li>Confirmation accepts both upper- and lower-case
 *       {@code 'Y'}/{@code 'y'} and {@code 'N'}/{@code 'n'} per the COBOL
 *       {@code EVALUATE 'Y' OR 'y'} idiom.</li>
 * </ul>
 *
 * <h2>Note on the CB00 Pre-Selected Transaction Path</h2>
 * <p>The COBOL source contains a pre-selected-transaction shortcut
 * ({@code IF CDEMO-CB00-TRN-SELECTED NOT EQUAL SPACES AND LOW-VALUES &rarr;
 * MOVE CDEMO-CB00-TRN-SELECTED TO ACTIDINI; PERFORM PROCESS-ENTER-KEY}) that
 * is triggered by a calling program (e.g., a transaction-list screen) that
 * pre-populates the {@code CDEMO-CB00-INFO} subgroup of the commarea before
 * XCTL-ing into {@code COBIL00C}. The {@code CDEMO-CB00-INFO} subgroup is NOT
 * modeled in the Java {@link CardDemoCommarea} record (it would be a REDEFINES
 * over the trailing {@code CDEMO-MORE-INFO} bytes and is unused outside of
 * COBIL00C). The Java equivalent is achieved by the caller passing the
 * pre-selected account-id directly on {@link CoBil00Input#accountId()} and
 * dispatching {@code AidKey.Enter}; this routes through
 * {@link #processEnterKey} identically to the COBOL flow without requiring
 * the commarea to expose a CB00-specific accessor.</p>
 *
 * @see <a href="file:../../../../../../../../../../app/cbl/COBIL00C.cbl">app/cbl/COBIL00C.cbl</a>
 * @see com.blitzy.carddemo.application.transaction.CoTrn02C
 * @since 1.0.0
 */
@CobolProgram(
        value = "COBIL00C",
        sourcePath = "app/cbl/COBIL00C.cbl",
        translationDate = "2025-01-21",
        notes = "Online bill payment screen for CICS transaction CB00; "
                + "reads ACCTDAT (UPDATE), reads CXACAIX (AIX by account ID), "
                + "creates a TRANSACT record via STARTBR/READPREV/ENDBR/WRITE "
                + "sequencing, then REWRITEs ACCTDAT with the deducted balance. "
                + "Mirrors the CoTrn02C add-transaction pattern. "
                + "Preserves COBOL verbatim: 'Tran ID already exist...' typo and "
                + "two-space gap in 'Payment successful.  Your Transaction ID is %s.'"
)
public final class CoBil00C {

    // =====================================================================
    // Logger
    // =====================================================================

    /**
     * SLF4J logger for infrastructure warnings (ACCTDAT/CXACAIX/TRANSACT
     * failures). Per AAP &sect;0.7.2, no card PAN is ever logged in full;
     * the {@link TranRecord#tranCardNum()} field is never written to this
     * logger by this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CoBil00C.class);

    // =====================================================================
    // Public program-identity constants (members_exposed by schema)
    // =====================================================================

    /**
     * COBOL {@code PROGRAM-ID} value, verified at
     * {@code app/cbl/COBIL00C.cbl} (WS-PGMNAME). Exposed as a public constant
     * so external composition roots can register this program with the
     * {@link ProgramRegistry} without re-typing the literal string.
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * CICS transaction identifier, verified at
     * {@code app/cbl/COBIL00C.cbl} (WS-TRANID). Returned to CICS via
     * {@code EXEC CICS RETURN TRANSID(...)} on every {@link Outcome.SendMap}.
     */
    public static final String TRANSACTION_ID = "CB00";

    /**
     * BMS mapset name, verified in {@code app/bms/COBIL00.bms} ({@code DFHMSD}).
     */
    public static final String MAPSET_NAME = "COBIL00";

    /**
     * BMS map name, verified in {@code app/bms/COBIL00.bms} ({@code DFHMDI}).
     */
    public static final String MAP_NAME = "COBIL0A";

    // =====================================================================
    // Default XCTL targets
    // =====================================================================

    /**
     * Default PF3 target program (main menu) per COBOL: {@code MOVE 'COMEN01C'
     * TO CDEMO-TO-PROGRAM} when {@code CDEMO-FROM-PROGRAM} is blank.
     */
    private static final String DEFAULT_PF3_PROGRAM = ProgramRegistry.CO_MEN_01C;

    /**
     * Default cold-start target program per COBOL: {@code WHEN EIBCALEN = 0
     * &rarr; MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, PERFORM
     * RETURN-TO-PREV-SCREEN}.
     */
    private static final String DEFAULT_FIRST_ENTRY_PROGRAM = ProgramRegistry.CO_SGN_00C;

    // =====================================================================
    // Transaction generation constants (PROCESS-ENTER-KEY, verbatim COBOL)
    // =====================================================================

    /** COBOL {@code MOVE '02' TO TRAN-TYPE-CD}. */
    private static final String TRAN_TYPE_PAYMENT = "02";

    /** COBOL {@code MOVE 2 TO TRAN-CAT-CD}. */
    private static final int TRAN_CAT_PAYMENT = 2;

    /** COBOL {@code MOVE 'POS TERM' TO TRAN-SOURCE}. */
    private static final String TRAN_SOURCE_POS = "POS TERM";

    /** COBOL {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}. */
    private static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /** COBOL {@code MOVE 999999999 TO TRAN-MERCHANT-ID}. */
    private static final long MERCHANT_ID_BILL_PAYMENT = 999_999_999L;

    /** COBOL {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}. */
    private static final String MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /** COBOL {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} and {@code TRAN-MERCHANT-ZIP}. */
    private static final String MERCHANT_LOCATION_NA = "N/A";

    /** Monetary scale (decimal places) for {@code TRAN-AMT} and {@code ACCT-CURR-BAL}. */
    private static final int MONETARY_SCALE = 2;

    /** COBOL {@code TRAN-ID PIC X(16)} width. */
    private static final int TRAN_ID_WIDTH = 16;

    // =====================================================================
    // Verbatim COBOL error messages (PRESERVE-AS-IS, AAP §0.7.1)
    // =====================================================================

    /**
     * COBOL {@code MAIN-PARA WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L140}).
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed.";

    /**
     * COBOL {@code PROCESS-ENTER-KEY} message when {@code ACTIDINI} is blank
     * ({@code app/cbl/COBIL00C.cbl:L160}).
     */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * COBOL {@code PROCESS-ENTER-KEY EVALUATE CONFIRMI WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L189}).
     */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /**
     * COBOL {@code PROCESS-ENTER-KEY} message when {@code ACCT-CURR-BAL <= 0}
     * ({@code app/cbl/COBIL00C.cbl:L204}).
     */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * COBOL {@code PROCESS-ENTER-KEY} message when {@code CONF-PAY-NO}
     * ({@code app/cbl/COBIL00C.cbl:L240}).
     */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /**
     * COBOL {@code READ-ACCTDAT-FILE / READ-CXACAIX-FILE} NOTFND message
     * ({@code app/cbl/COBIL00C.cbl:L354, L420}).
     */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /**
     * COBOL {@code READ-ACCTDAT-FILE WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L364}).
     */
    private static final String MSG_ACCT_LOOKUP_FAILED = "Unable to lookup Account...";

    /**
     * COBOL {@code UPDATE-ACCTDAT-FILE WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L395}).
     */
    private static final String MSG_ACCT_UPDATE_FAILED = "Unable to Update Account...";

    /**
     * COBOL {@code READ-CXACAIX-FILE WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L428}).
     */
    private static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup XREF AIX file...";

    /**
     * COBOL {@code STARTBR-TRANSACT-FILE / READPREV-TRANSACT-FILE WHEN OTHER}
     * message ({@code app/cbl/COBIL00C.cbl:L459, L488}).
     */
    private static final String MSG_TRAN_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /**
     * COBOL {@code WRITE-TRANSACT-FILE DUPKEY/DUPREC} message
     * ({@code app/cbl/COBIL00C.cbl:L535}). <strong>Note the typo "exist"
     * (missing trailing 's') is preserved verbatim per AAP &sect;0.7.1.</strong>
     */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";

    /**
     * COBOL {@code WRITE-TRANSACT-FILE WHEN OTHER} message
     * ({@code app/cbl/COBIL00C.cbl:L541}).
     */
    private static final String MSG_TRAN_WRITE_FAILED = "Unable to Add Bill pay Transaction...";

    /**
     * Template for the COBOL {@code STRING} concatenation success message
     * ({@code app/cbl/COBIL00C.cbl:L519-L528}):
     * <pre>{@code
     *   STRING 'Payment successful. '   DELIMITED BY SIZE
     *          ' Your Transaction ID is ' DELIMITED BY SIZE
     *          TRAN-ID                  DELIMITED BY SPACE
     *          '.'                      DELIMITED BY SIZE
     *     INTO WS-MESSAGE
     * }</pre>
     * <p>The first chunk ends with one trailing space and the second begins
     * with one leading space &mdash; the concatenation therefore produces
     * <strong>two consecutive spaces</strong> between {@code "successful."}
     * and {@code "Your"}. This double-space is preserved verbatim per AAP
     * &sect;0.7.1 (byte-for-byte fidelity).</p>
     */
    private static final String MSG_PAYMENT_SUCCESS_TEMPLATE =
            "Payment successful.  Your Transaction ID is %s.";

    // =====================================================================
    // Header formatters (POPULATE-HEADER-INFO)
    // =====================================================================

    /**
     * Header date {@code MM/dd/yy} formatter, mirrors COBOL
     * {@code WS-CURDATE-MM-DD-YY}. {@link Locale#ROOT} enforces
     * locale-independent formatting (no locale-specific separators) for
     * deterministic output.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * Header time {@code HH:mm:ss} formatter, mirrors COBOL
     * {@code WS-CURTIME-HH-MM-SS}. {@link Locale#ROOT} enforces
     * locale-independent formatting.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    // =====================================================================
    // Sealed Outcome interface (members_exposed by schema)
    // =====================================================================

    /**
     * Sealed disposition type returned by
     * {@link CoBil00C#execute(CoBil00Input, CardDemoCommarea, AidKey)}. Exactly
     * two outcomes are permitted:
     * <ul>
     *   <li>{@link SendMap} &mdash; redraw the {@code COBIL0A} BMS map
     *       (re-enter the {@code CB00} transaction on the next AID byte).</li>
     *   <li>{@link Xctl} &mdash; transfer control to another program via the
     *       {@link ProgramRegistry} (CICS {@code XCTL} semantics).</li>
     * </ul>
     *
     * <p>The sealed permits enforce exhaustiveness in downstream
     * pattern-matching switches per AAP &sect;0.6.7.</p>
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * COBOL {@code SEND-BILLPAY-SCREEN} + {@code RETURN TRANSID(WS-TRANID)
         * COMMAREA(CARDDEMO-COMMAREA)} &mdash; redraws the {@code COBIL0A}
         * BMS map with the staged output and re-arms the {@code CB00}
         * transaction for the next AID byte (CICS pseudo-conversational mode).
         *
         * @param output   the fully populated screen output to render
         *                 (never {@code null})
         * @param commarea the outbound commarea threaded to the next
         *                 {@code CB00} invocation (never {@code null})
         */
        record SendMap(CoBil00Output output, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing non-null contracts on
             * both components.
             *
             * @throws NullPointerException if either argument is {@code null}
             */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * COBOL {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
         * COMMAREA(CARDDEMO-COMMAREA)} &mdash; transfers control to another
         * Java program in the CardDemo family. The composition root calls
         * {@link ProgramRegistry#invoke(String, CardDemoCommarea)} with the
         * supplied {@code targetProgram} and {@code commarea}.
         *
         * @param targetProgram the COBOL {@code PROGRAM-ID} of the target
         *                      (e.g. {@code "COMEN01C"}, {@code "COSGN00C"});
         *                      never {@code null} or blank
         * @param commarea      the outbound commarea passed to the callee
         *                      (never {@code null})
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing non-null and non-blank
             * contracts.
             *
             * @throws NullPointerException     if either argument is
             *                                  {@code null}
             * @throws IllegalArgumentException if {@code targetProgram} is
             *                                  blank
             */
            public Xctl {
                Objects.requireNonNull(targetProgram, "targetProgram");
                Objects.requireNonNull(commarea, "commarea");
                if (targetProgram.isBlank()) {
                    throw new IllegalArgumentException(
                            "targetProgram must not be blank");
                }
            }
        }
    }

    // =====================================================================
    // MutableState inner class — per-request working-storage carrier
    // =====================================================================

    /**
     * Per-request mutable state, mirroring the COBOL
     * {@code WORKING-STORAGE SECTION} fields that are read and written
     * across multiple paragraphs. Exactly one instance exists per call to
     * {@link CoBil00C#execute}; <strong>this class is NOT thread-safe and
     * MUST NOT be shared across invocations</strong>.
     *
     * <p>Reasoning: COBOL paragraphs operate on shared
     * {@code WORKING-STORAGE} fields. To preserve a 1:1 paragraph-to-method
     * mapping while keeping the surrounding {@link CoBil00C} class
     * thread-safe (the {@code execute} method is re-entrant from any
     * virtual thread or platform thread), each invocation gets its own
     * {@code MutableState} that flows through the private paragraph
     * methods. Per AAP &sect;0.6.6 this design lets the same
     * {@link CoBil00C} instance be invoked concurrently for independent
     * CICS transactions without cross-talk.</p>
     *
     * <p>The class is {@code static} (no enclosing-instance reference) and
     * {@code final} (no subclassing) per the canonical pattern established
     * by {@code com.blitzy.carddemo.application.transaction.CoTrn02C}.</p>
     */
    static final class MutableState {

        /**
         * COBOL {@code WS-ERR-FLG} 88-level {@code ERR-FLG-ON}/{@code OFF}.
         * Set to {@code true} when any validation, lookup, or write error
         * has been raised; subsequent paragraphs short-circuit on this
         * flag (mirrors {@code IF NOT ERR-FLG-ON THEN ... END-IF} guards).
         */
        boolean errFlgOn;

        /**
         * COBOL {@code WS-CONF-PAY-FLG} 88-level
         * {@code CONF-PAY-YES}/{@code CONF-PAY-NO}. Set to {@code true}
         * once the operator has confirmed the payment by entering
         * {@code 'Y'} or {@code 'y'} in the {@code CONFIRM} field.
         */
        boolean confPayYes;

        /**
         * COBOL {@code WS-MESSAGE PIC X(80)}. Holds the user-facing error
         * or success message rendered into {@code ERRMSGO} on the next
         * {@code SEND MAP}. Initialised to {@code ""} (SPACES).
         */
        String wsMessage = "";

        /**
         * Set to {@code true} after a successful
         * {@code WRITE-TRANSACT-FILE NORMAL} so that
         * {@link #sendBillpayScreen(MutableState)} colours the
         * {@code ERRMSGO} field {@link CoBil00Output.FieldColor#GREEN}
         * (COBOL: {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO}).
         */
        boolean successMessage;

        /**
         * The immutable input record from the BMS map. Read-only reference;
         * any field that needs to be echoed back to the screen is staged
         * via {@link #outAccountId} / {@link #outConfirm} etc.
         */
        CoBil00Input input;

        /**
         * The current commarea being threaded through the paragraphs. Each
         * mutation produces a new {@link CardDemoCommarea} instance (records
         * are immutable) and re-assigns this field; the final value is
         * returned in the {@link Outcome.SendMap} or {@link Outcome.Xctl}.
         */
        CardDemoCommarea commarea;

        /**
         * Staged output for the {@code ACCOUNT-ID} field (COBOL: {@code ACTIDINI}
         * of {@code COBIL0AI} / {@code ACTIDINO} of {@code COBIL0AO}).
         * Initialised to {@code ""}.
         */
        String outAccountId = "";

        /**
         * Staged output for the {@code CURBALI} field. Initialised to
         * {@code ""}; populated by {@code PROCESS-ENTER-KEY} via
         * {@link CoBil00C#formatCurrencyForDisplay(BigDecimal)} after a
         * successful {@code READ-ACCTDAT-FILE}.
         */
        String outCurrentBalance = "";

        /**
         * Staged output for the {@code CONFIRMI} field. Initialised to
         * {@code ""}; populated by echoing the user's confirmation back.
         */
        String outConfirm = "";

        /**
         * Cursor hint for the {@code ACCOUNT-ID} field. {@code -1}
         * requests cursor placement (COBOL: {@code MOVE -1 TO ACTIDINL});
         * {@code 0} means "no hint".
         */
        int outAccountIdCursor;

        /**
         * Cursor hint for the {@code CONFIRM} field. {@code -1} requests
         * cursor placement; {@code 0} means "no hint".
         */
        int outConfirmCursor;

        /**
         * Snapshot of the {@code AccountRecord} loaded by
         * {@code READ-ACCTDAT-FILE}; consumed by {@code PROCESS-ENTER-KEY}
         * for balance display and by {@code UPDATE-ACCTDAT-FILE} for
         * rewriting with the deducted balance.
         */
        AccountRecord accountRecord;

        /**
         * Snapshot of the {@code CardXrefRecord} loaded by
         * {@code READ-CXACAIX-FILE}; consumed by {@code PROCESS-ENTER-KEY}
         * to populate {@code TRAN-CARD-NUM} on the new transaction.
         */
        CardXrefRecord cardXrefRecord;
    }

    // =====================================================================
    // Collaborator fields (constructor-injected)
    // =====================================================================

    /**
     * Hexagonal port for the {@code ACCTDAT} VSAM KSDS. Used by
     * {@link #readAcctDatFile(MutableState, long)} (COBOL
     * {@code READ-ACCTDAT-FILE}) and
     * {@link #updateAcctDatFile(MutableState, AccountRecord)} (COBOL
     * {@code UPDATE-ACCTDAT-FILE}, which performs a {@code REWRITE} via
     * {@link AccountRepository#save(AccountRecord)}).
     */
    private final AccountRepository accountRepository;

    /**
     * Hexagonal port for the {@code CXACAIX} alternate index over
     * {@code CARDXREF} keyed by account ID. Used by
     * {@link #readCxAcAixFile(MutableState, long)} (COBOL
     * {@code READ-CXACAIX-FILE}) to resolve the account-ID to a card
     * cross-reference record so its card number can populate
     * {@code TRAN-CARD-NUM}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Hexagonal port for the {@code TRANSACT} VSAM KSDS. Used by
     * {@link #findHighestTransactionIdNumeric(MutableState)} (COBOL
     * {@code STARTBR(HIGH-VALUES) + READPREV + ENDBR} chain &mdash;
     * coalesced into the single port call
     * {@link TransactionRepository#findHighestId()}) and
     * {@link #writeTransactFile(MutableState, TranRecord)} (COBOL
     * {@code WRITE-TRANSACT-FILE}).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Dispatch table for dynamic {@code XCTL} targets. Required by the
     * constructor for type-checking and dependency-injection wiring; the
     * field itself is held to satisfy the hexagonal-architecture pattern
     * that all collaborators be injected via the constructor. The actual
     * XCTL routing is performed by the composition root in
     * {@code carddemo-app} based on the {@link Outcome.Xctl#targetProgram()}
     * value returned by this class &mdash; this class does NOT call
     * {@code programRegistry.invoke(...)} directly because that would
     * couple two online programs in the same JVM stack frame, which the
     * COBOL XCTL semantics does not.
     */
    @SuppressWarnings("unused")
    private final ProgramRegistry programRegistry;

    /**
     * Time source for {@code GET-CURRENT-TIMESTAMP} and
     * {@code POPULATE-HEADER-INFO}. Injected so that tests can substitute
     * {@link Clock#fixed} for deterministic timestamps; production code
     * uses {@link Clock#systemDefaultZone()} (provided by the four-argument
     * convenience constructor).
     */
    private final Clock clock;

    // =====================================================================
    // Constructors
    // =====================================================================

    /**
     * Full-fidelity constructor accepting an explicit {@link Clock}.
     *
     * <p>Use this constructor in unit tests to inject a deterministic
     * clock (typically {@code Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"),
     * ZoneOffset.UTC)}) so that {@link TranRecord#tranOrigTs()} and
     * {@link TranRecord#tranProcTs()} are reproducible byte-for-byte.</p>
     *
     * <p>All collaborators are non-null and validated immediately via
     * {@link Objects#requireNonNull}; failure throws
     * {@link NullPointerException} with a diagnostic message naming the
     * offending parameter.</p>
     *
     * @param accountRepository     non-null port for {@code ACCTDAT}
     * @param cardXrefRepository    non-null port for {@code CXACAIX}
     * @param transactionRepository non-null port for {@code TRANSACT}
     * @param programRegistry       non-null dispatch table (held but not
     *                              invoked directly &mdash; see field
     *                              documentation)
     * @param clock                 non-null time source
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoBil00C(
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            ProgramRegistry programRegistry,
            Clock clock) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.programRegistry = Objects.requireNonNull(
                programRegistry, "programRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Convenience constructor for production wiring that defaults the
     * {@link Clock} to {@link Clock#systemDefaultZone()}.
     *
     * <p>The four-argument form is preferred by the composition root in
     * {@code carddemo-app} where wiring is configured once at startup and
     * the system clock is the desired time source.</p>
     *
     * @param accountRepository     non-null port for {@code ACCTDAT}
     * @param cardXrefRepository    non-null port for {@code CXACAIX}
     * @param transactionRepository non-null port for {@code TRANSACT}
     * @param programRegistry       non-null dispatch table
     * @throws NullPointerException if any argument is {@code null}
     * @see #CoBil00C(AccountRepository, CardXrefRepository,
     *      TransactionRepository, ProgramRegistry, Clock)
     */
    public CoBil00C(
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            ProgramRegistry programRegistry) {
        this(accountRepository, cardXrefRepository, transactionRepository,
                programRegistry, Clock.systemDefaultZone());
    }

    // =====================================================================
    // Public entry point — COBOL MAIN-PARA
    // =====================================================================

    /**
     * Translates COBOL {@code MAIN-PARA} (app/cbl/COBIL00C.cbl, paragraph at
     * lines 99&ndash;149). Single-shot entry point invoked by the CICS
     * dispatcher (or, in JVM hosting, the composition root) on every AID
     * byte from the {@code COBIL0A} screen.
     *
     * <p><strong>Decision tree</strong>:</p>
     * <ol>
     *   <li>If {@code commareaIn} is {@code null} (COBOL
     *       {@code EIBCALEN = 0}): build a fresh commarea and
     *       {@code XCTL} to {@link ProgramRegistry#CO_SGN_00C} (cold-start
     *       redirect to signon).</li>
     *   <li>Otherwise re-hydrate the commarea, check the
     *       {@link PgmContext}:
     *     <ul>
     *       <li>{@code Enter} (first entry into this program): flip the
     *           context to {@link PgmContext#REENTER}, blank the input
     *           fields, position cursor on {@code ACCOUNT-ID} (cursor
     *           hint {@code -1}), and send the map.</li>
     *       <li>{@code Reenter} (subsequent AID byte): dispatch on the
     *           {@link AidKey} via a pattern-matching switch with
     *           compile-time exhaustiveness checking. The supported keys
     *           are {@code Enter} (process the form), {@code PfKey03}
     *           (back to caller's program), {@code PfKey04} (clear the
     *           screen); every other key produces the COBOL
     *           {@code "Invalid key pressed."} error.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p><strong>Exhaustiveness mandate (AAP &sect;0.6.7)</strong>: the
     * pattern-matching switch over {@link AidKey} covers every one of the
     * sixteen permits with NO {@code default} branch &mdash; the Java&nbsp;25
     * compiler refuses to compile the file if any permit is missing,
     * which is the safety property that replaces COBOL's
     * {@code EVALUATE WHEN OTHER} catch-all idiom.</p>
     *
     * @param input      the BMS map input (use {@link CoBil00Input#empty()}
     *                   for cold-start scenarios); must not be {@code null}
     * @param commareaIn the inbound commarea from CICS; {@code null}
     *                   represents the COBOL {@code EIBCALEN = 0}
     *                   cold-start case
     * @param aidKey     the AID byte the operator pressed; must not be
     *                   {@code null}; one of sixteen
     *                   {@link AidKey} permits
     * @return either {@link Outcome.SendMap} (re-display the map) or
     *         {@link Outcome.Xctl} (transfer to another program); never
     *         {@code null}
     * @throws NullPointerException if {@code input} or {@code aidKey} is
     *                              {@code null}
     */
    public Outcome execute(
            CoBil00Input input,
            CardDemoCommarea commareaIn,
            AidKey aidKey) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aidKey, "aidKey");

        // Build per-request state (1:1 with COBOL WORKING-STORAGE).
        MutableState state = new MutableState();
        state.input = input;
        state.outAccountId = orEmpty(input.accountId());
        state.outConfirm = orEmpty(input.confirmation());

        // COBOL line 99-101: SET ERR-FLG-OFF / USR-MODIFIED-NO; MOVE
        // SPACES TO WS-MESSAGE and ERRMSGO. Both flags default to false
        // and wsMessage defaults to "" in MutableState — nothing to do.

        // COBOL line 102-117: EVALUATE EIBCALEN.
        //   WHEN 0  → cold start: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM,
        //             PERFORM RETURN-TO-PREV-SCREEN.
        //   WHEN OTHER → MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA; IF NOT
        //                CDEMO-PGM-REENTER → SET CDEMO-PGM-REENTER TRUE,
        //                MOVE LOW-VALUES TO COBIL0AO, MOVE -1 TO ACTIDINL.
        if (commareaIn == null) {
            LOGGER.debug(
                    "CoBil00C: EIBCALEN=0 (cold start) → XCTL to {}",
                    DEFAULT_FIRST_ENTRY_PROGRAM);
            CardDemoCommarea cold = buildXctlCommarea(
                    CardDemoCommarea.empty(), DEFAULT_FIRST_ENTRY_PROGRAM);
            return new Outcome.Xctl(DEFAULT_FIRST_ENTRY_PROGRAM, cold);
        }

        state.commarea = commareaIn;
        PgmContext context = commareaIn.cdemoGeneralInfo().pgmContext();

        // First entry: blank the output, position cursor, set REENTER.
        if (context.isEnter()) {
            LOGGER.debug("CoBil00C: first-entry → setting PgmContext=REENTER");
            state.commarea = withPgmContext(state.commarea, PgmContext.REENTER);
            // COBOL: MOVE LOW-VALUES TO COBIL0AO; MOVE -1 TO ACTIDINL.
            state.outAccountId = "";
            state.outCurrentBalance = "";
            state.outConfirm = "";
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return sendBillpayScreen(state);
        }

        // Re-entry: dispatch on AID key. The COBOL PROCESS-ENTER-KEY,
        // RETURN-TO-PREV-SCREEN, and CLEAR-CURRENT-SCREEN paragraphs each
        // end by performing SEND-BILLPAY-SCREEN (or, for PF3,
        // returnToPrevScreen which produces an Outcome.Xctl directly).
        //
        // Exhaustiveness mandate (AAP §0.6.7): every CcWorkAreas.AidKey
        // permit (16 total) must appear in this switch with NO default.
        return switch (aidKey) {
            // COBOL line 124-125: WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY.
            case AidKey.Enter e -> {
                processEnterKey(state);
                yield sendBillpayScreen(state);
            }
            // COBOL line 126-131: WHEN DFHPF3 → IF CDEMO-FROM-PROGRAM
            //   BLANK MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM, PERFORM
            //   RETURN-TO-PREV-SCREEN.
            case AidKey.PfKey03 pf3 -> {
                String fromProgram = state.commarea.cdemoGeneralInfo()
                        .fromProgram();
                String toProgram = isBlankOrLow(fromProgram)
                        ? DEFAULT_PF3_PROGRAM
                        : fromProgram.strip();
                if (toProgram.isEmpty()) {
                    toProgram = DEFAULT_PF3_PROGRAM;
                }
                yield returnToPrevScreen(state, toProgram);
            }
            // COBOL line 132-133: WHEN DFHPF4 → PERFORM CLEAR-CURRENT-SCREEN.
            case AidKey.PfKey04 pf4 -> {
                clearCurrentScreen(state);
                yield sendBillpayScreen(state);
            }
            // COBOL line 134-141: WHEN OTHER → MOVE 'Y' TO WS-ERR-FLG,
            // MOVE 'Invalid key pressed.' TO WS-MESSAGE, PERFORM
            // SEND-BILLPAY-SCREEN. Every other AID permit hits this arm.
            case AidKey.Clear c       -> invalidKey(state);
            case AidKey.Pa1 p1        -> invalidKey(state);
            case AidKey.Pa2 p2        -> invalidKey(state);
            case AidKey.PfKey01 pf1   -> invalidKey(state);
            case AidKey.PfKey02 pf2   -> invalidKey(state);
            case AidKey.PfKey05 pf5   -> invalidKey(state);
            case AidKey.PfKey06 pf6   -> invalidKey(state);
            case AidKey.PfKey07 pf7   -> invalidKey(state);
            case AidKey.PfKey08 pf8   -> invalidKey(state);
            case AidKey.PfKey09 pf9   -> invalidKey(state);
            case AidKey.PfKey10 pf10  -> invalidKey(state);
            case AidKey.PfKey11 pf11  -> invalidKey(state);
            case AidKey.PfKey12 pf12  -> invalidKey(state);
        };
    }

    /**
     * Helper for the COBOL {@code WHEN OTHER} arm in {@code MAIN-PARA}'s
     * {@code EVALUATE EIBAID} block. Sets the error flag, populates the
     * {@code "Invalid key pressed."} message, and re-displays the map.
     *
     * @param state the per-request mutable state
     * @return a {@link Outcome.SendMap} re-displaying the {@code COBIL0A}
     *         screen with the error message in the default color
     */
    private Outcome invalidKey(MutableState state) {
        state.errFlgOn = true;
        state.wsMessage = MSG_INVALID_KEY;
        return sendBillpayScreen(state);
    }

    // =====================================================================
    // PROCESS-ENTER-KEY paragraph (COBOL lines 154-244)
    // =====================================================================

    /**
     * Translates COBOL {@code PROCESS-ENTER-KEY} (app/cbl/COBIL00C.cbl
     * lines 154&ndash;244) &mdash; the central business logic of the
     * program.
     *
     * <p><strong>Algorithm</strong>:</p>
     * <ol>
     *   <li>Validate that {@code ACTIDINI} (account ID) is non-blank;
     *       otherwise raise {@link #MSG_ACCT_ID_EMPTY}.</li>
     *   <li>Parse the account ID as a {@code long} (COBOL
     *       {@code PIC 9(11)} field); a parse failure or out-of-range
     *       value translates to {@link #MSG_ACCT_NOT_FOUND}.</li>
     *   <li>Evaluate {@code CONFIRMI}:
     *     <ul>
     *       <li>{@code "Y"}/{@code "y"} &rarr; set {@code CONF-PAY-YES},
     *           then {@code READ-ACCTDAT-FILE}.</li>
     *       <li>{@code "N"}/{@code "n"} &rarr; {@code CLEAR-CURRENT-SCREEN}
     *           (and set the error flag to short-circuit the rest of
     *           {@code PROCESS-ENTER-KEY}).</li>
     *       <li>blank/{@code LOW-VALUES} &rarr; {@code READ-ACCTDAT-FILE}
     *           (display the balance, do not process the payment).</li>
     *       <li>any other value &rarr; raise
     *           {@link #MSG_INVALID_CONFIRM}.</li>
     *     </ul>
     *   </li>
     *   <li>If the read succeeded: {@code MOVE ACCT-CURR-BAL TO CURBALI}
     *       (display the balance), then check {@code ACCT-CURR-BAL &lt;= 0}
     *       and raise {@link #MSG_NOTHING_TO_PAY} when the account has
     *       nothing owing.</li>
     *   <li>If {@code CONF-PAY-YES}:
     *     <ul>
     *       <li>{@code READ-CXACAIX-FILE} to obtain
     *           {@code XREF-CARD-NUM}.</li>
     *       <li>{@code STARTBR-TRANSACT-FILE} +
     *           {@code READPREV-TRANSACT-FILE} +
     *           {@code ENDBR-TRANSACT-FILE} (coalesced into
     *           {@link #findHighestTransactionIdNumeric}) to obtain the
     *           highest existing {@code TRAN-ID}; add 1 to get the new
     *           ID.</li>
     *       <li>Build a new {@link TranRecord} populated with the COBOL
     *           constants and the runtime values (account balance,
     *           card number, current timestamp).</li>
     *       <li>{@code WRITE-TRANSACT-FILE} to persist the payment.</li>
     *       <li>{@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
     *           via {@link Decimals#subtract} and
     *           {@code UPDATE-ACCTDAT-FILE} to rewrite the account.</li>
     *     </ul>
     *   </li>
     *   <li>Otherwise (blank confirmation): raise
     *       {@link #MSG_CONFIRM_PAYMENT}.</li>
     * </ol>
     *
     * <p>The {@code state.errFlgOn} flag short-circuits successive steps;
     * mirroring the COBOL {@code IF NOT ERR-FLG-ON ... END-IF} guards.</p>
     *
     * @param state the per-request mutable state
     */
    private void processEnterKey(MutableState state) {
        // COBOL line 156: SET CONF-PAY-NO TRUE.
        state.confPayYes = false;

        // COBOL line 158-165: IF ACTIDINI = SPACES OR LOW-VALUES →
        //   MOVE 'Y' TO WS-ERR-FLG; MOVE 'Acct ID can NOT be empty...'
        //   TO WS-MESSAGE; MOVE -1 TO ACTIDINL.
        String actIdRaw = orEmpty(state.input.accountId());
        String actIdTrim = actIdRaw.strip();
        if (actIdTrim.isEmpty() || isBlankOrLow(actIdRaw)) {
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_ID_EMPTY;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return;
        }

        // COBOL line 166-169: IF NOT ERR-FLG-ON → MOVE ACTIDINI TO
        // ACCT-ID OF ACCOUNT-RECORD AND XREF-ACCT-ID OF CARD-XREF-RECORD.
        // The account-ID is a PIC 9(11) field, so parse it as a long.
        long acctId;
        try {
            if (!isNumeric(actIdTrim) || actIdTrim.length() > 11) {
                throw new NumberFormatException("non-numeric or too long");
            }
            acctId = Long.parseLong(actIdTrim);
            if (acctId < 0L || acctId > 99_999_999_999L) {
                throw new NumberFormatException("out of range");
            }
        } catch (NumberFormatException nfe) {
            // COBOL truncates a non-numeric source to ACCT-ID, then the
            // subsequent READ-ACCTDAT-FILE returns NOTFND. Surface the
            // same user-facing error message.
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_NOT_FOUND;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return;
        }

        // COBOL line 170-189: EVALUATE CONFIRMI OF COBIL0AI.
        //   WHEN 'Y' OR 'y' → SET CONF-PAY-YES TRUE; PERFORM
        //                     READ-ACCTDAT-FILE.
        //   WHEN 'N' OR 'n' → PERFORM CLEAR-CURRENT-SCREEN; MOVE 'Y' TO
        //                     WS-ERR-FLG (to short-circuit subsequent
        //                     logic).
        //   WHEN SPACES OR LOW-VALUES → PERFORM READ-ACCTDAT-FILE
        //                     (display the balance, no payment).
        //   WHEN OTHER → MOVE 'Y' TO WS-ERR-FLG; MOVE 'Invalid value.
        //                     Valid values are (Y/N)...' TO WS-MESSAGE;
        //                     MOVE -1 TO CONFIRML.
        String confirmRaw = orEmpty(state.input.confirmation());
        String confirmTrim = confirmRaw.strip();

        if ("Y".equalsIgnoreCase(confirmTrim) && !confirmTrim.isEmpty()) {
            state.confPayYes = true;
            if (!readAcctDatFile(state, acctId)) {
                return;
            }
        } else if ("N".equalsIgnoreCase(confirmTrim) && !confirmTrim.isEmpty()) {
            // COBOL: PERFORM CLEAR-CURRENT-SCREEN; immediately after this
            // the COBOL program raises ERR-FLG-ON to short-circuit the
            // rest of PROCESS-ENTER-KEY.
            clearCurrentScreen(state);
            state.errFlgOn = true;
            return;
        } else if (confirmTrim.isEmpty() || isBlankOrLow(confirmRaw)) {
            // SPACES / LOW-VALUES → read-only display.
            if (!readAcctDatFile(state, acctId)) {
                return;
            }
        } else {
            state.errFlgOn = true;
            state.wsMessage = MSG_INVALID_CONFIRM;
            state.outConfirmCursor = CoBil00Output.CURSOR_HINT;
            return;
        }

        // Defensive: if any of the file-IO paragraphs raised the error
        // flag, do not proceed.
        if (state.errFlgOn) {
            return;
        }

        // COBOL line 191-194: MOVE ACCT-CURR-BAL TO WS-CURR-BAL;
        //   MOVE WS-CURR-BAL TO CURBALI OF COBIL0AI.
        BigDecimal currBal = state.accountRecord.acctCurrBal();
        state.outCurrentBalance = formatCurrencyForDisplay(currBal);

        // COBOL line 196-205: IF ACCT-CURR-BAL <= 0 AND ACTIDINI NOT =
        //   SPACES/LOW-VALUES → MOVE 'Y' TO WS-ERR-FLG; MOVE 'You have
        //   nothing to pay...' TO WS-MESSAGE; MOVE -1 TO ACTIDINL.
        if (currBal.signum() <= 0) {
            state.errFlgOn = true;
            state.wsMessage = MSG_NOTHING_TO_PAY;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return;
        }

        // COBOL line 207-237: IF CONF-PAY-YES → perform the payment.
        if (state.confPayYes) {
            performPayment(state, currBal);
        } else {
            // COBOL line 238-243: ELSE (i.e., CONF-PAY-NO via blank
            //   confirmation) → MOVE 'Y' TO WS-ERR-FLG; MOVE 'Confirm
            //   to make a bill payment...' TO WS-MESSAGE; MOVE -1 TO
            //   CONFIRML.
            state.errFlgOn = true;
            state.wsMessage = MSG_CONFIRM_PAYMENT;
            state.outConfirmCursor = CoBil00Output.CURSOR_HINT;
        }
    }

    /**
     * Inner helper for the {@code CONF-PAY-YES} branch of
     * {@code PROCESS-ENTER-KEY}, covering COBOL lines 207&ndash;237.
     * Reads the card cross-reference, allocates the next transaction ID,
     * builds and writes the {@link TranRecord}, and rewrites the account
     * with the deducted balance.
     *
     * <p>Each sub-step that fails sets {@code state.errFlgOn} and
     * populates {@code state.wsMessage}; this method returns immediately
     * on any failure to mirror the COBOL {@code IF NOT ERR-FLG-ON ...
     * END-IF} guard pattern.</p>
     *
     * @param state   the per-request mutable state
     * @param currBal the account's current balance (becomes
     *                {@code TRAN-AMT})
     */
    private void performPayment(MutableState state, BigDecimal currBal) {
        // COBOL line 207: PERFORM READ-CXACAIX-FILE.
        long acctId = state.accountRecord.acctId();
        if (!readCxAcAixFile(state, acctId)) {
            return;
        }

        // COBOL line 208: MOVE HIGH-VALUES TO TRAN-ID; PERFORM
        // STARTBR-TRANSACT-FILE; PERFORM READPREV-TRANSACT-FILE;
        // PERFORM ENDBR-TRANSACT-FILE. The Java port coalesces this into
        // a single findHighestId() call; an empty Optional mirrors the
        // COBOL ENDFILE branch (MOVE ZEROS TO TRAN-ID).
        long highestId = findHighestTransactionIdNumeric(state);
        if (state.errFlgOn) {
            return;
        }

        // COBOL line 215-216: MOVE TRAN-ID TO WS-TRAN-ID-NUM; ADD 1 TO
        // WS-TRAN-ID-NUM.
        long newTranIdNum = highestId + 1L;

        // COBOL line 219: MOVE WS-TRAN-ID-NUM TO TRAN-ID. WS-TRAN-ID-NUM
        // is PIC 9(16); TRAN-ID is PIC X(16). The COBOL MOVE pads with
        // leading zeros and stores the digits as ASCII text. Java
        // equivalent: %016d formatter.
        String newTranId = padLeft(String.valueOf(newTranIdNum),
                TRAN_ID_WIDTH, '0');

        // COBOL line 217-234: INITIALIZE TRAN-RECORD; populate fields.
        LocalDateTime now = getCurrentTimestamp();
        TranRecord newTran = buildPaymentTransaction(
                newTranId,
                currBal,
                state.cardXrefRecord.xrefCardNum(),
                now);

        // COBOL line 235: PERFORM WRITE-TRANSACT-FILE.
        if (!writeTransactFile(state, newTran)) {
            return;
        }

        // COBOL line 236: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT.
        // Use Decimals.subtract(a, b, scale, mode) with HALF_EVEN per
        // AAP §0.6.1 (banker's rounding for ROUNDED clauses; the COBOL
        // COMPUTE has no ROUNDED keyword, but since both operands have
        // scale 2 and the difference is exact, the rounding is a no-op
        // and HALF_EVEN matches the COBOL default).
        BigDecimal newBalance = Decimals.subtract(
                currBal, newTran.tranAmt(), MONETARY_SCALE,
                RoundingMode.HALF_EVEN);

        // COBOL line 237: PERFORM UPDATE-ACCTDAT-FILE.
        AccountRecord updated = withAcctCurrBal(
                state.accountRecord, newBalance);
        updateAcctDatFile(state, updated);
    }

    // =====================================================================
    // READ-ACCTDAT-FILE paragraph (COBOL lines 343-372)
    // =====================================================================

    /**
     * Translates COBOL {@code READ-ACCTDAT-FILE} (app/cbl/COBIL00C.cbl
     * lines 343&ndash;372) &mdash; reads the {@code ACCOUNT-RECORD} for the
     * given {@code ACCT-ID}.
     *
     * <p>COBOL {@code EVALUATE} arms:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; CONTINUE; store the record in
     *       {@code state.accountRecord}.</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; raise
     *       {@link #MSG_ACCT_NOT_FOUND}.</li>
     *   <li>{@code WHEN OTHER} &rarr; raise
     *       {@link #MSG_ACCT_LOOKUP_FAILED}.</li>
     * </ul>
     *
     * <p>The Java port models {@code NOTFND} as {@link Optional#empty()};
     * any {@link RuntimeException} maps to {@code WHEN OTHER}. A failed
     * read is logged at WARN level (no PAN/PII is included in the log).</p>
     *
     * @param state  the per-request mutable state
     * @param acctId the account ID to look up
     * @return {@code true} on success; {@code false} on any error (caller
     *         must short-circuit any subsequent steps)
     */
    private boolean readAcctDatFile(MutableState state, long acctId) {
        Optional<AccountRecord> result;
        try {
            result = accountRepository.findById(acctId);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "CoBil00C: ACCTDAT read failed for acctId={}: {}",
                    acctId, ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_LOOKUP_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }
        if (result.isEmpty()) {
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_NOT_FOUND;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }
        state.accountRecord = result.get();
        return true;
    }

    // =====================================================================
    // UPDATE-ACCTDAT-FILE paragraph (COBOL lines 377-403)
    // =====================================================================

    /**
     * Translates COBOL {@code UPDATE-ACCTDAT-FILE} (app/cbl/COBIL00C.cbl
     * lines 377&ndash;403) &mdash; rewrites the previously-read account
     * record with the new (deducted) balance.
     *
     * <p>The COBOL {@code REWRITE} verb requires that the record was
     * previously read with the {@code UPDATE} option; in the Java port,
     * the {@link AccountRepository#save} contract permits an in-place
     * update of an existing record.</p>
     *
     * @param state   the per-request mutable state
     * @param updated the account record with the post-payment balance
     * @return {@code true} on success; {@code false} on any error
     */
    private boolean updateAcctDatFile(MutableState state, AccountRecord updated) {
        try {
            accountRepository.save(updated);
            return true;
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "CoBil00C: ACCTDAT rewrite failed for acctId={}: {}",
                    updated.acctId(), ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_UPDATE_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }
    }

    // =====================================================================
    // READ-CXACAIX-FILE paragraph (COBOL lines 408-436)
    // =====================================================================

    /**
     * Translates COBOL {@code READ-CXACAIX-FILE} (app/cbl/COBIL00C.cbl
     * lines 408&ndash;436) &mdash; reads the {@code CARD-XREF-RECORD}
     * keyed by account ID via the {@code CXACAIX} alternate index over
     * {@code CARDXREF}.
     *
     * <p>COBOL {@code EVALUATE} arms:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; CONTINUE; store the record in
     *       {@code state.cardXrefRecord}.</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; raise
     *       {@link #MSG_ACCT_NOT_FOUND} (COBOL verbatim re-uses the
     *       account-not-found message even though this is a card-xref
     *       miss).</li>
     *   <li>{@code WHEN OTHER} &rarr; raise
     *       {@link #MSG_XREF_LOOKUP_FAILED}.</li>
     * </ul>
     *
     * @param state  the per-request mutable state
     * @param acctId the account ID used as the AIX key
     * @return {@code true} on success; {@code false} on any error
     */
    private boolean readCxAcAixFile(MutableState state, long acctId) {
        Optional<CardXrefRecord> result;
        try {
            result = cardXrefRepository.findByAccountId(acctId);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "CoBil00C: CXACAIX read failed for acctId={}: {}",
                    acctId, ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_XREF_LOOKUP_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }
        if (result.isEmpty()) {
            // COBOL line 420-424: WHEN DFHRESP(NOTFND) → MOVE 'Y' TO
            // WS-ERR-FLG; MOVE 'Account ID NOT found...' TO WS-MESSAGE.
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_NOT_FOUND;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }
        state.cardXrefRecord = result.get();
        return true;
    }

    // =====================================================================
    // STARTBR/READPREV/ENDBR-TRANSACT-FILE coalesced (COBOL lines 441-505)
    // =====================================================================

    /**
     * Coalesced translation of COBOL
     * {@code STARTBR-TRANSACT-FILE + READPREV-TRANSACT-FILE +
     * ENDBR-TRANSACT-FILE} (app/cbl/COBIL00C.cbl lines 441&ndash;505).
     *
     * <p>The COBOL idiom is:</p>
     * <pre>{@code
     *   MOVE HIGH-VALUES TO TRAN-ID.
     *   EXEC CICS STARTBR DATASET(TRANSACT) RIDFLD(TRAN-ID) ...
     *   EXEC CICS READPREV DATASET(TRANSACT) INTO(TRAN-RECORD) ...
     *     -- WHEN DFHRESP(ENDFILE): MOVE ZEROS TO TRAN-ID
     *   EXEC CICS ENDBR DATASET(TRANSACT) ...
     *   MOVE TRAN-ID TO WS-TRAN-ID-NUM
     * }</pre>
     * <p>Net effect: read the record with the highest {@code TRAN-ID}
     * (the file is keyed in ascending order so this is the last record).
     * If the file is empty, set {@code TRAN-ID} to zero so the next
     * {@code ADD 1} yields {@code 0000000000000001}.</p>
     *
     * <p>The Java port replaces this with the single port call
     * {@link TransactionRepository#findHighestId()}, which returns an
     * {@link Optional}: {@code empty()} mirrors {@code ENDFILE},
     * {@code present()} mirrors {@code NORMAL}.</p>
     *
     * @param state the per-request mutable state
     * @return the numeric value of the highest existing {@code TRAN-ID},
     *         or {@code 0L} if the file is empty; on infrastructure error
     *         the method sets {@code state.errFlgOn} and returns
     *         {@code 0L} (caller must check {@code errFlgOn})
     */
    private long findHighestTransactionIdNumeric(MutableState state) {
        Optional<TranRecord> highest;
        try {
            highest = transactionRepository.findHighestId();
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "CoBil00C: TRANSACT STARTBR/READPREV failed: {}",
                    ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_LOOKUP_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return 0L;
        }
        if (highest.isEmpty()) {
            // COBOL ENDFILE → MOVE ZEROS TO TRAN-ID. Caller adds 1 to get
            // 0000000000000001 as the first-ever transaction ID.
            return 0L;
        }
        // COBOL: MOVE TRAN-ID TO WS-TRAN-ID-NUM. TRAN-ID is PIC X(16);
        // WS-TRAN-ID-NUM is PIC 9(16). The MOVE assumes the alphanumeric
        // content is fully numeric. We strip pad bytes (NUL or space) and
        // parse as long.
        String tranId = orEmpty(highest.get().tranId()).strip();
        if (tranId.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(tranId);
        } catch (NumberFormatException nfe) {
            LOGGER.warn(
                    "CoBil00C: TRANSACT highest-id parse failed for "
                            + "tranId=[{}]: {}",
                    tranId, nfe.getMessage());
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_LOOKUP_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return 0L;
        }
    }

    // =====================================================================
    // WRITE-TRANSACT-FILE paragraph (COBOL lines 510-547)
    // =====================================================================

    /**
     * Translates COBOL {@code WRITE-TRANSACT-FILE} (app/cbl/COBIL00C.cbl
     * lines 510&ndash;547) &mdash; persists the freshly-built
     * {@code TRAN-RECORD} via {@link TransactionRepository#save}.
     *
     * <p>COBOL {@code EVALUATE} arms:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; PERFORM INITIALIZE-ALL-FIELDS;
     *       set the success message in DFHGREEN; PERFORM
     *       SEND-BILLPAY-SCREEN (deferred &mdash; the caller invokes
     *       {@link #sendBillpayScreen}).</li>
     *   <li>{@code DFHRESP(DUPKEY) / DFHRESP(DUPREC)} &rarr; raise
     *       {@link #MSG_TRAN_DUPLICATE}. The Java port maps this to
     *       {@link IllegalStateException} per the canonical
     *       {@link com.blitzy.carddemo.application.transaction.CoTrn02C}
     *       pattern.</li>
     *   <li>{@code WHEN OTHER} &rarr; raise
     *       {@link #MSG_TRAN_WRITE_FAILED}.</li>
     * </ul>
     *
     * <p>The COBOL success message is built by a {@code STRING}
     * concatenation that produces TWO consecutive spaces between
     * {@code "successful."} and {@code "Your"}; this double-space is
     * preserved verbatim in {@link #MSG_PAYMENT_SUCCESS_TEMPLATE}.</p>
     *
     * @param state the per-request mutable state
     * @param tran  the transaction record to persist
     * @return {@code true} on success (state.successMessage will be true,
     *         state.wsMessage will hold the formatted success message);
     *         {@code false} on any error
     */
    private boolean writeTransactFile(MutableState state, TranRecord tran) {
        try {
            transactionRepository.save(tran);
        } catch (IllegalStateException dup) {
            // COBOL line 533-538: WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)
            //   → MOVE 'Y' TO WS-ERR-FLG; MOVE 'Tran ID already exist...'
            //   TO WS-MESSAGE. The typo 'exist' (missing 's') is verbatim.
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_DUPLICATE;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        } catch (RuntimeException ex) {
            // COBOL line 539-543: WHEN OTHER → MOVE 'Y' TO WS-ERR-FLG;
            //   MOVE 'Unable to Add Bill pay Transaction...' TO WS-MESSAGE.
            // Log the failure but never include the card PAN per AAP §0.7.2;
            // tranId is safe to log (it is a synthetic sequential ID).
            LOGGER.warn(
                    "CoBil00C: TRANSACT WRITE failed for tranId={}: {}",
                    tran.tranId(), ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_WRITE_FAILED;
            state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
            return false;
        }

        // COBOL line 519-528: WHEN DFHRESP(NORMAL) → PERFORM
        //   INITIALIZE-ALL-FIELDS; MOVE SPACES TO WS-MESSAGE; MOVE
        //   DFHGREEN TO ERRMSGC OF COBIL0AO; STRING 'Payment
        //   successful. ' || ' Your Transaction ID is ' || TRAN-ID ||
        //   '.' INTO WS-MESSAGE.
        initializeAllFields(state);
        String trimmedId = firstSpaceDelimitedToken(tran.tranId());
        state.wsMessage = String.format(
                MSG_PAYMENT_SUCCESS_TEMPLATE, trimmedId);
        state.successMessage = true;
        return true;
    }

    // =====================================================================
    // CLEAR-CURRENT-SCREEN paragraph (COBOL lines 552-555)
    // =====================================================================

    /**
     * Translates COBOL {@code CLEAR-CURRENT-SCREEN} (app/cbl/COBIL00C.cbl
     * lines 552&ndash;555). Just calls {@link #initializeAllFields};
     * the COBOL paragraph also issues a {@code PERFORM
     * SEND-BILLPAY-SCREEN} at the end, but in the Java port that is the
     * caller's responsibility (the calling switch arm in {@code execute}
     * returns {@code sendBillpayScreen(state)} after this paragraph
     * returns).
     *
     * @param state the per-request mutable state
     */
    private void clearCurrentScreen(MutableState state) {
        initializeAllFields(state);
    }

    // =====================================================================
    // INITIALIZE-ALL-FIELDS paragraph (COBOL lines 560-566)
    // =====================================================================

    /**
     * Translates COBOL {@code INITIALIZE-ALL-FIELDS} (app/cbl/COBIL00C.cbl
     * lines 560&ndash;566). Blanks the input/output fields and clears
     * the message; mirrors:
     * <pre>{@code
     *   MOVE -1 TO ACTIDINL
     *   MOVE SPACES TO ACTIDINI, CURBALI, CONFIRMI, WS-MESSAGE
     * }</pre>
     *
     * <p>The COBOL also moves spaces into the corresponding output
     * fields (since INITIALIZE on the symbolic map blanks both input
     * and output halves); the Java port likewise blanks the staged
     * output fields.</p>
     *
     * @param state the per-request mutable state
     */
    private void initializeAllFields(MutableState state) {
        state.outAccountIdCursor = CoBil00Output.CURSOR_HINT;
        state.outAccountId = "";
        state.outCurrentBalance = "";
        state.outConfirm = "";
        state.wsMessage = "";
    }

    // =====================================================================
    // SEND-BILLPAY-SCREEN paragraph (COBOL lines 289-301)
    // =====================================================================

    /**
     * Translates COBOL {@code SEND-BILLPAY-SCREEN} (app/cbl/COBIL00C.cbl
     * lines 289&ndash;301) &mdash; assembles the {@link CoBil00Output}
     * and returns it as a {@link Outcome.SendMap}.
     *
     * <p>The COBOL sequence is:</p>
     * <pre>{@code
     *   PERFORM POPULATE-HEADER-INFO
     *   MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO
     *   EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO)
     *                  ERASE CURSOR
     *   EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)
     * }</pre>
     *
     * @param state the per-request mutable state
     * @return a {@link Outcome.SendMap} carrying the fully-populated
     *         output and the current commarea
     */
    private Outcome sendBillpayScreen(MutableState state) {
        // COBOL: PERFORM POPULATE-HEADER-INFO. We need the header data
        // (current date/time/titles) on every send.
        LocalDateTime now = LocalDateTime.now(clock);
        CoBil00Output.FieldColor color = state.successMessage
                ? CoBil00Output.FieldColor.GREEN
                : CoBil00Output.FieldColor.DEFAULT;

        // Echo the user's input back to the output fields (COBOL maps a
        // user's input field <X>I to the corresponding output field <X>O
        // through the same memory; the symbolic copybook simply names the
        // pair). The processEnterKey paragraph and clearCurrentScreen
        // adjust state.outAccountId / state.outConfirm etc. as needed.
        String accountIdOut = orEmpty(state.outAccountId);
        if (accountIdOut.isEmpty()) {
            accountIdOut = orEmpty(state.input == null
                    ? "" : state.input.accountId());
        }
        String confirmOut = orEmpty(state.outConfirm);
        if (confirmOut.isEmpty()) {
            confirmOut = orEmpty(state.input == null
                    ? "" : state.input.confirmation());
        }

        CoBil00Output output = CoBil00Output.builder()
                .transactionName(TRANSACTION_ID)
                .title01(ScreenTitle.TITLE_01)
                .currentDate(HEADER_DATE_FORMAT.format(now))
                .programName(PROGRAM_NAME)
                .title02(ScreenTitle.TITLE_02)
                .currentTime(HEADER_TIME_FORMAT.format(now))
                .accountIdDisplay(accountIdOut)
                .currentBalance(orEmpty(state.outCurrentBalance))
                .confirmDisplay(confirmOut)
                .errorMessage(orEmpty(state.wsMessage))
                .errMsgColor(color)
                .actIdInLength(state.outAccountIdCursor)
                .confirmLength(state.outConfirmCursor)
                .build();

        return new Outcome.SendMap(output, state.commarea);
    }

    // =====================================================================
    // POPULATE-HEADER-INFO paragraph (COBOL lines 319-338)
    // =====================================================================

    // Note: Header population is folded into sendBillpayScreen() above so
    // the header fields and the body fields can be set in a single
    // CoBil00Output.builder() chain. The original COBOL paragraph
    // produces the same observable output; this micro-refactor is purely
    // mechanical and does not change behavior.

    // =====================================================================
    // GET-CURRENT-TIMESTAMP paragraph (COBOL lines 249-267)
    // =====================================================================

    /**
     * Translates COBOL {@code GET-CURRENT-TIMESTAMP} (app/cbl/COBIL00C.cbl
     * lines 249&ndash;267). Returns the current local date-time
     * <strong>truncated to second precision</strong>.
     *
     * <p>The COBOL paragraph uses {@code EXEC CICS ASKTIME} which provides
     * at most second resolution, then explicitly
     * {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6} to zero out the
     * microsecond subfield. To preserve byte-for-byte parity with the
     * COBOL output (the trailing {@code .000000} of every persisted
     * {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}), the Java port likewise
     * truncates the {@link LocalDateTime} to second precision via
     * {@link LocalDateTime#withNano(int)} passing {@code 0}.</p>
     *
     * @return the current local date-time with nano-of-second set to
     *         {@code 0}
     */
    private LocalDateTime getCurrentTimestamp() {
        return LocalDateTime.now(clock).withNano(0);
    }

    // =====================================================================
    // RETURN-TO-PREV-SCREEN paragraph (COBOL lines 273-284)
    // =====================================================================

    /**
     * Translates COBOL {@code RETURN-TO-PREV-SCREEN} (app/cbl/COBIL00C.cbl
     * lines 273&ndash;284). Builds the outbound commarea for the
     * {@code XCTL} target program (clearing {@code PGM-CONTEXT} to
     * {@link PgmContext#ENTER} so the callee enters via its first-entry
     * path) and returns an {@link Outcome.Xctl}.
     *
     * @param state     the per-request mutable state
     * @param toProgram the target {@code PROGRAM-ID} (typically
     *                  {@link ProgramRegistry#CO_SGN_00C} or
     *                  {@link ProgramRegistry#CO_MEN_01C})
     * @return an {@link Outcome.Xctl} carrying the target program and the
     *         updated commarea
     */
    private Outcome returnToPrevScreen(MutableState state, String toProgram) {
        CardDemoCommarea outbound = buildXctlCommarea(
                state.commarea, toProgram);
        return new Outcome.Xctl(toProgram, outbound);
    }

    // =====================================================================
    // Commarea-mutation helpers
    // =====================================================================

    /**
     * Builds an outbound commarea for an {@code XCTL} target program. The
     * helper:
     * <ul>
     *   <li>Stamps {@code CDEMO-FROM-TRANID} with {@link #TRANSACTION_ID}.</li>
     *   <li>Stamps {@code CDEMO-FROM-PROGRAM} with {@link #PROGRAM_NAME}.</li>
     *   <li>Stamps {@code CDEMO-TO-PROGRAM} with {@code toProgram}.</li>
     *   <li>Clears {@code CDEMO-PGM-CONTEXT} to {@link PgmContext#ENTER}
     *       so the target program enters via its first-entry path.</li>
     *   <li>Preserves all other fields (user identity, customer/account/
     *       card sub-groups).</li>
     * </ul>
     *
     * <p>All string fields are padded/truncated to the exact byte width
     * declared by the COBOL {@code PIC X(n)} clauses via
     * {@link #padExact(String, int)} to satisfy the
     * {@link CardDemoCommarea.CdemoGeneralInfo} compact-constructor
     * validation.</p>
     *
     * @param current   the current commarea (never {@code null})
     * @param toProgram the {@code PROGRAM-ID} to XCTL to
     * @return a fresh {@link CardDemoCommarea} with the outbound fields set
     */
    static CardDemoCommarea buildXctlCommarea(
            CardDemoCommarea current, String toProgram) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(toProgram, "toProgram");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated =
                new CardDemoCommarea.CdemoGeneralInfo(
                        padExact(TRANSACTION_ID,
                                CardDemoCommarea.LENGTH_FROM_TRANID),
                        padExact(PROGRAM_NAME,
                                CardDemoCommarea.LENGTH_FROM_PROGRAM),
                        gi.toTranId(),
                        padExact(toProgram,
                                CardDemoCommarea.LENGTH_TO_PROGRAM),
                        gi.userId(),
                        gi.userType(),
                        PgmContext.ENTER);
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a clone of the given commarea with only the
     * {@link PgmContext} field replaced. Used on the first-entry path
     * to flip the context from {@link PgmContext#ENTER} to
     * {@link PgmContext#REENTER}.
     *
     * @param current    the current commarea (never {@code null})
     * @param newContext the new program-context value (never {@code null})
     * @return a fresh {@link CardDemoCommarea} with only the
     *         {@code pgmContext} component changed
     */
    static CardDemoCommarea withPgmContext(
            CardDemoCommarea current, PgmContext newContext) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(newContext, "newContext");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated =
                new CardDemoCommarea.CdemoGeneralInfo(
                        gi.fromTranId(),
                        gi.fromProgram(),
                        gi.toTranId(),
                        gi.toProgram(),
                        gi.userId(),
                        gi.userType(),
                        newContext);
        return current.withCdemoGeneralInfo(updated);
    }

    // =====================================================================
    // TranRecord and AccountRecord builders
    // =====================================================================

    /**
     * Constructs the new {@link TranRecord} per COBOL {@code PROCESS-ENTER-KEY}
     * lines 217&ndash;234 (the {@code INITIALIZE TRAN-RECORD} block
     * followed by the per-field {@code MOVE} statements).
     *
     * <p>The COBOL constants are encoded as static final fields:
     * {@link #TRAN_TYPE_PAYMENT}, {@link #TRAN_CAT_PAYMENT},
     * {@link #TRAN_SOURCE_POS}, {@link #TRAN_DESC_BILL_PAYMENT},
     * {@link #MERCHANT_ID_BILL_PAYMENT},
     * {@link #MERCHANT_NAME_BILL_PAYMENT},
     * {@link #MERCHANT_LOCATION_NA}.</p>
     *
     * @param tranId  the new 16-character right-justified zero-padded
     *                transaction ID
     * @param amount  the payment amount (equal to the account's current
     *                balance per the COBOL
     *                {@code MOVE ACCT-CURR-BAL TO TRAN-AMT})
     * @param cardNum the cross-reference card number from
     *                {@code READ-CXACAIX-FILE}
     * @param now     the current timestamp from
     *                {@link #getCurrentTimestamp()}
     * @return the fully-populated transaction record ready for
     *         {@code WRITE-TRANSACT-FILE}
     */
    private static TranRecord buildPaymentTransaction(
            String tranId,
            BigDecimal amount,
            String cardNum,
            LocalDateTime now) {
        return new TranRecord(
                tranId,
                TRAN_TYPE_PAYMENT,
                TRAN_CAT_PAYMENT,
                TRAN_SOURCE_POS,
                TRAN_DESC_BILL_PAYMENT,
                amount,
                MERCHANT_ID_BILL_PAYMENT,
                MERCHANT_NAME_BILL_PAYMENT,
                MERCHANT_LOCATION_NA,
                MERCHANT_LOCATION_NA,
                orEmpty(cardNum),
                now,
                now,
                TranRecord.emptyFiller());
    }

    /**
     * Returns a clone of the given {@link AccountRecord} with only the
     * {@code acctCurrBal} field replaced. Necessary because
     * {@link AccountRecord} does not expose a built-in {@code with}-style
     * helper for the balance field; we must invoke the canonical
     * constructor with every other field passed through unchanged.
     *
     * @param current    the current account record
     * @param newBalance the new {@code ACCT-CURR-BAL} value
     * @return a fresh {@link AccountRecord} with only the balance field
     *         changed
     */
    private static AccountRecord withAcctCurrBal(
            AccountRecord current, BigDecimal newBalance) {
        return new AccountRecord(
                current.acctId(),
                current.acctActiveStatus(),
                newBalance,
                current.acctCreditLimit(),
                current.acctCashCreditLimit(),
                current.acctOpenDate(),
                current.acctExpiraionDate(),
                current.acctReissueDate(),
                current.acctCurrCycCredit(),
                current.acctCurrCycDebit(),
                current.acctAddrZip(),
                current.acctGroupId(),
                current.filler());
    }

    // =====================================================================
    // String and currency-formatting helpers
    // =====================================================================

    /**
     * Formats a {@link BigDecimal} monetary value for the
     * {@code CURBAL} output field (COBOL
     * {@code PIC +9999999999.99}, 14 characters including the leading
     * sign).
     *
     * <p>The COBOL display picture forces a sign (either {@code +} or
     * {@code -}) and pads with leading zeros to width 13 (10 integer +
     * 1 decimal point + 2 fraction). The total field width is 14 (one
     * sign character + 13). This helper produces the same shape.</p>
     *
     * @param value the value to format (any scale; will be normalised
     *              to 2 via {@link RoundingMode#HALF_EVEN})
     * @return a 14-character display string
     */
    private static String formatCurrencyForDisplay(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONETARY_SCALE,
                RoundingMode.HALF_EVEN);
        String abs = scaled.abs().toPlainString();
        // Split into integer and fraction parts.
        int dot = abs.indexOf('.');
        String intPart;
        String fracPart;
        if (dot < 0) {
            intPart = abs;
            fracPart = "00";
        } else {
            intPart = abs.substring(0, dot);
            fracPart = abs.substring(dot + 1);
            if (fracPart.length() < 2) {
                fracPart = (fracPart + "00").substring(0, 2);
            }
        }
        // Pad integer part to 10 digits with leading zeros.
        if (intPart.length() < 10) {
            intPart = "0".repeat(10 - intPart.length()) + intPart;
        } else if (intPart.length() > 10) {
            intPart = intPart.substring(intPart.length() - 10);
        }
        char sign = scaled.signum() < 0 ? '-' : '+';
        return sign + intPart + "." + fracPart;
    }

    /**
     * Pads or truncates a string to an exact length. Length must be
     * positive; {@code null} input is treated as empty string. When the
     * input is shorter than {@code length} it is right-padded with ASCII
     * spaces; when longer, it is truncated from the right (keeping the
     * leftmost {@code length} characters).
     *
     * @param s      the string to pad/truncate (may be {@code null})
     * @param length the desired exact length (must be {@code > 0})
     * @return a string of exactly {@code length} characters
     */
    static String padExact(String s, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException(
                    "length must be positive: " + length);
        }
        String value = s == null ? "" : s;
        if (value.length() == length) {
            return value;
        }
        if (value.length() > length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Left-pads a string with the given character to the requested
     * length. Truncates from the left if the input exceeds the length.
     * COBOL equivalent: {@code MOVE} to a {@code PIC 9(n)} field with
     * implicit zero-padding.
     *
     * @param s      the input string
     * @param length the target length
     * @param pad    the padding character
     * @return the left-padded result
     */
    static String padLeft(String s, int length, char pad) {
        if (length <= 0) {
            return "";
        }
        String value = s == null ? "" : s;
        if (value.length() == length) {
            return value;
        }
        if (value.length() > length) {
            return value.substring(value.length() - length);
        }
        return String.valueOf(pad).repeat(length - value.length()) + value;
    }

    /**
     * COBOL idiom {@code TEST IF field = SPACES OR LOW-VALUES} &mdash;
     * true if the input is {@code null}, empty, blank (whitespace only),
     * or composed entirely of NUL bytes.
     *
     * @param s the string to test (may be {@code null})
     * @return {@code true} when {@code s} is {@code null}, empty, blank,
     *         or all NUL bytes; {@code false} otherwise
     */
    static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        if (s.isBlank()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * COBOL idiom {@code TEST IF field IS NUMERIC} &mdash; true if the
     * input is a non-empty string of ASCII digits.
     *
     * @param s the string to test (must already be trimmed if needed)
     * @return {@code true} when {@code s} consists exclusively of one or
     *         more ASCII digits; {@code false} otherwise
     */
    static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
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

    /**
     * Returns {@code ""} when the input is {@code null}, otherwise the
     * input unchanged. Mirrors the COBOL convention of treating an
     * unpopulated field as an empty string.
     *
     * @param s the input (may be {@code null})
     * @return a non-{@code null} string ({@code ""} when input is
     *         {@code null})
     */
    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * COBOL {@code STRING ... DELIMITED BY SPACE} idiom for the
     * transaction-ID extraction in {@code WRITE-TRANSACT-FILE}: returns
     * the first space-delimited token of the input. If the input begins
     * with one or more spaces, returns an empty string.
     *
     * <p>For our use case the input is always a 16-character numeric
     * string with no internal spaces (e.g. {@code "0000000000000123"})
     * and {@link String#strip()} would suffice. We use the
     * space-delimited-token approach to mirror COBOL exactly.</p>
     *
     * @param input the input string (may be {@code null})
     * @return the first space-delimited token (never {@code null})
     */
    static String firstSpaceDelimitedToken(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        int spaceIdx = input.indexOf(' ');
        if (spaceIdx < 0) {
            return input;
        }
        return input.substring(0, spaceIdx);
    }
}
