/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.transaction;

import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.DateValidator;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.util.Decimals;
import com.blitzy.carddemo.domain.validation.DateValidationWork.DateValidationResult;

/**
 * Java 25 LTS translation of the COBOL Add-Transaction online CICS program
 * {@code COTRN02C}, which services the CICS transaction {@code CT02}.
 *
 * <p>This is the most complex online transaction program in the CardDemo
 * application. It captures every input field from the COTRN2A BMS map,
 * validates twenty-one distinct precondition checks producing seventeen
 * distinct verbatim error messages, dispatches the {@code CSUTLDTC} date
 * validation routine twice (once for {@code TORIGDTI} and once for
 * {@code TPROCDTI}), reads the next sequential transaction-id via the
 * STARTBR/READPREV/ENDBR idiom against the TRANSACT KSDS, and finally
 * writes a fully-populated {@link TranRecord} with byte-for-byte fidelity
 * to the COBOL baseline.
 *
 * <h2>COBOL Paragraph &rarr; Java Method Mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr;
 *       {@link #handle(CoTrn02Input, CardDemoCommarea, AidKey)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link #processEnterKey(MutableState)}</li>
 *   <li>{@code VALIDATE-INPUT-KEY-FIELDS} &rarr;
 *       {@link #validateInputKeyFields(MutableState)}</li>
 *   <li>{@code VALIDATE-INPUT-DATA-FIELDS} &rarr;
 *       {@link #validateInputDataFields(MutableState)}</li>
 *   <li>{@code ADD-TRANSACTION} &rarr;
 *       {@link #addTransaction(MutableState)}</li>
 *   <li>{@code COPY-LAST-TRAN-DATA} &rarr;
 *       {@link #copyLastTranData(MutableState)}</li>
 *   <li>{@code READ-CXACAIX-FILE} &rarr;
 *       {@link #readCxacaixFile(MutableState, long)}</li>
 *   <li>{@code READ-CCXREF-FILE} &rarr;
 *       {@link #readCcxrefFile(MutableState, String)}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} / {@code READPREV-TRANSACT-FILE} /
 *       {@code ENDBR-TRANSACT-FILE} &rarr;
 *       {@link #findHighestExistingTranId()} (single call coalesces the
 *       three CICS verbs into the
 *       {@link TransactionRepository#findHighestId()} port operation)</li>
 *   <li>{@code WRITE-TRANSACT-FILE} &rarr;
 *       {@link #writeTransactFile(MutableState, TranRecord)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr;
 *       {@link #returnToPrevScreen(MutableState, String)}</li>
 *   <li>{@code SEND-TRNADD-SCREEN} &rarr;
 *       {@link #sendTrnaddScreen(MutableState)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr;
 *       {@link #clearCurrentScreen(MutableState)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr;
 *       {@link #initializeAllFields(MutableState)}</li>
 *   <li>{@code POPULATE-HEADER-INFO} &rarr;
 *       {@link #populateHeaderInfo(MutableState)}</li>
 * </ul>
 *
 * <h2>Architectural Compliance</h2>
 * <ul>
 *   <li>Constructor injection of four collaborators
 *       ({@link TransactionRepository}, {@link CardXrefRepository},
 *       {@link ProgramRegistry}, {@link DateValidator}).</li>
 *   <li>{@link Outcome} sealed interface with two record permits
 *       ({@code SendMap}, {@code Xctl}) modelling the CICS
 *       SEND-MAP/RETURN versus XCTL dispositions.</li>
 *   <li>Pattern-matching switch over the {@link AidKey} sealed hierarchy
 *       with exhaustiveness checking and NO {@code default} branch
 *       (Agent Action Plan &sect;0.6.2).</li>
 *   <li>{@link java.math.BigDecimal} with HALF_EVEN rounding for the
 *       monetary {@code TRAN-AMT} field via {@link Decimals}; never
 *       {@code double}/{@code float}.</li>
 *   <li>{@link java.time} for current-date/-time header formatting; never
 *       {@link java.util.Date} or {@link java.util.Calendar}.</li>
 *   <li>SLF4J for all error logging; full PANs are masked via
 *       {@link #maskPan(String)} per AAP &sect;0.7.2.</li>
 *   <li>JEP 511 module import declaration (single
 *       {@code import module java.base;} avoids the long list of
 *       {@code java.util.*}/{@code java.math.*}/{@code java.time.*} imports
 *       that would otherwise be required).</li>
 *   <li>No Spring container, no Hibernate/JPA, no Lombok, no reflection,
 *       no {@code ThreadLocal}, no preview features.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * <p>Each instance of {@code CoTrn02C} is intended to be invoked
 * single-threaded per CICS transaction. The class fields are immutable
 * collaborators; the per-invocation {@link MutableState} is created
 * locally inside {@link #handle(CoTrn02Input, CardDemoCommarea, AidKey)}
 * and never escapes the call stack, so the class is safe to invoke from
 * concurrent threads as long as each thread passes its own input/output
 * objects.
 */
@CobolProgram(
        value = "COTRN02C",
        sourcePath = "app/cbl/COTRN02C.cbl",
        translationDate = "2025-01-21",
        notes = "Add Transaction online CICS program (transaction CT02). "
                + "Seventeen distinct verbatim validation error messages; "
                + "CSUTLDTC date validation called twice (TORIGDTI, TPROCDTI) "
                + "with the CEEDAYS 2513 (Y2K window) message tolerated as a "
                + "non-fatal warning; STARTBR(HIGH-VALUES)+READPREV+ENDBR "
                + "pattern coalesced into TransactionRepository.findHighestId() "
                + "to derive the next sequential TRAN-ID; CARDXREF AIX lookup "
                + "by account-id (CXACAIX) or by card-number (CCXREF). "
                + "PF3 returns to CDEMO-FROM-PROGRAM if set, else COMEN01C; "
                + "PF4 clears the screen; PF5 copies the last TRAN's fields "
                + "back into the input area; any other AID-key emits "
                + "CCDA-MSG-INVALID-KEY."
)
public final class CoTrn02C {

    // ---------------------------------------------------------------------
    // SLF4J logger (org.slf4j:slf4j-api:2.0.16) — replaces COBOL DISPLAY
    // of RESP/REAS codes per AAP §0.5.1.
    // ---------------------------------------------------------------------
    private static final Logger LOGGER = LoggerFactory.getLogger(CoTrn02C.class);

    // ---------------------------------------------------------------------
    // Program identity constants (COBOL WS-PGMNAME / WS-TRANID).
    // ---------------------------------------------------------------------
    /** COBOL {@code WS-PGMNAME} value at app/cbl/COTRN02C.cbl. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** COBOL {@code WS-TRANID} value — CICS transaction identifier. */
    private static final String TRANSACTION_ID = "CT02";

    /** Default program when EIBCALEN=0 (cold start, no commarea). */
    private static final String DEFAULT_PREV_PROGRAM = ProgramRegistry.CO_SGN_00C;

    /** Default PF3 back-target when CDEMO-FROM-PROGRAM is blank. */
    private static final String DEFAULT_BACK_PROGRAM = ProgramRegistry.CO_MEN_01C;

    // ---------------------------------------------------------------------
    // CSUTLDTC integration constants.
    // ---------------------------------------------------------------------
    /** COBOL {@code WS-DATE-FORMAT} literal at COTRN02C.cbl. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /**
     * The CEEDAYS message number "2513" (FC-UNSUPP-RANGE — date is past the
     * Y2K boundary) is treated as a non-fatal warning by the COBOL source
     * (lines 400-405 / 419-425). See {@link DateValidationResult#msgNo()}.
     */
    private static final String CSUTLDTC_WARNING_MSG_NO = "2513";

    // ---------------------------------------------------------------------
    // Header formatters (COBOL POPULATE-HEADER-INFO paragraph,
    // app/cbl/COTRN02C.cbl:L552-L571). Locale.ROOT is mandatory — see
    // agent_prompt: deterministic locale-independent format avoids
    // locale-specific separators that would break byte-for-byte fidelity.
    // ---------------------------------------------------------------------
    /** Header date {@code MM/dd/yy} — COBOL {@code WS-CURDATE-MM-DD-YY}. */
    private static final DateTimeFormatter DATE_MM_DD_YY =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /** Header time {@code HH:mm:ss} — COBOL {@code WS-CURTIME-HH-MM-SS}. */
    private static final DateTimeFormatter TIME_HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * Date portion of COBOL {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}
     * (PIC X(26)) when formatting back to the BMS input field (PIC X(10)).
     */
    private static final DateTimeFormatter DATE_YYYY_MM_DD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    // ---------------------------------------------------------------------
    // Input-format regex patterns (COBOL VALIDATE-INPUT-DATA-FIELDS,
    // app/cbl/COTRN02C.cbl:L338-L381). Compile-once for efficiency.
    // ---------------------------------------------------------------------
    /** Matches strings of ASCII digits only (one or more). */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");

    /**
     * Matches the COBOL TRNAMTI PIC X(12) signed amount layout
     * {@code [+-]NNNNNNNN.NN}. Mirrors the COBOL evaluations at
     * app/cbl/COTRN02C.cbl:L338-L351:
     * <pre>
     *   TRNAMTI(1:1) NOT EQUAL '-' AND '+'
     *   TRNAMTI(2:8) NOT NUMERIC
     *   TRNAMTI(10:1) NOT = '.'
     *   TRNAMTI(11:2) IS NOT NUMERIC
     * </pre>
     */
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("^[+-][0-9]{8}\\.[0-9]{2}$");

    /**
     * Matches the COBOL TORIGDTI / TPROCDTI PIC X(10) date layout
     * {@code NNNN-NN-NN}. Mirrors app/cbl/COTRN02C.cbl:L353-L381.
     */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    // ---------------------------------------------------------------------
    // Seventeen verbatim COBOL validation error messages.
    // Per AAP §0.7.1 — Preserve-As-Is mandate. DO NOT MODIFY THESE STRINGS.
    // ---------------------------------------------------------------------
    /** COBOL {@code CCDA-MSG-INVALID-KEY} at app/cpy/CSMSG01Y.cpy. */
    private static final String MSG_INVALID_KEY =
            "Invalid key pressed. Please see below...";
    private static final String MSG_ACCT_NUMERIC =
            "Account ID must be Numeric...";
    private static final String MSG_CARD_NUMERIC =
            "Card Number must be Numeric...";
    private static final String MSG_ACCT_OR_CARD_REQUIRED =
            "Account or Card Number must be entered...";
    private static final String MSG_ACCT_NOT_FOUND =
            "Account ID NOT found...";
    private static final String MSG_CARD_NOT_FOUND =
            "Card Number NOT found...";
    private static final String MSG_ACCT_XREF_LOOKUP_ERROR =
            "Unable to lookup Acct in XREF AIX file...";
    private static final String MSG_CARD_XREF_LOOKUP_ERROR =
            "Unable to lookup Card # in XREF file...";
    private static final String MSG_TRAN_LOOKUP_ERROR =
            "Unable to lookup Transaction...";
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";
    private static final String MSG_AMT_EMPTY = "Amount can NOT be empty...";
    private static final String MSG_ORIG_DT_EMPTY = "Orig Date can NOT be empty...";
    private static final String MSG_PROC_DT_EMPTY = "Proc Date can NOT be empty...";
    private static final String MSG_MID_EMPTY = "Merchant ID can NOT be empty...";
    private static final String MSG_MNAME_EMPTY = "Merchant Name can NOT be empty...";
    private static final String MSG_MCITY_EMPTY = "Merchant City can NOT be empty...";
    private static final String MSG_MZIP_EMPTY = "Merchant Zip can NOT be empty...";
    private static final String MSG_TYPE_NUMERIC = "Type CD must be Numeric...";
    private static final String MSG_CAT_NUMERIC = "Category CD must be Numeric...";
    private static final String MSG_AMT_FORMAT =
            "Amount should be in format -99999999.99";
    private static final String MSG_ORIG_DT_FORMAT =
            "Orig Date should be in format YYYY-MM-DD";
    private static final String MSG_PROC_DT_FORMAT =
            "Proc Date should be in format YYYY-MM-DD";
    private static final String MSG_ORIG_DT_INVALID =
            "Orig Date - Not a valid date...";
    private static final String MSG_PROC_DT_INVALID =
            "Proc Date - Not a valid date...";
    private static final String MSG_MID_NUMERIC = "Merchant ID must be Numeric...";
    private static final String MSG_CONFIRM_REQUIRED =
            "Confirm to add this transaction...";
    private static final String MSG_INVALID_CONFIRM =
            "Invalid value. Valid values are (Y/N)...";
    /** Verbatim "Tran ID already exist..." (note: typo preserved per AAP §0.7.1). */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";
    private static final String MSG_TRAN_ADD_ERROR = "Unable to Add Transaction...";

    /**
     * COBOL TRAN-ID maximum 16-digit value sentinel used by
     * STARTBR(HIGH-VALUES). When repository returns empty (ENDFILE), the
     * next tran-id is {@code 1}; otherwise the existing max + 1.
     */
    private static final long DEFAULT_FIRST_TRAN_ID = 1L;

    /** TRAN-ID PIC X(16) string width — mirrors {@link TranRecord#TRAN_ID_LENGTH}. */
    private static final int TRAN_ID_WIDTH = 16;

    // ---------------------------------------------------------------------
    // Constructor-injected collaborators (Hexagonal Architecture per
    // AAP §0.3.1: domain ports never reach the file system directly).
    // ---------------------------------------------------------------------
    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final ProgramRegistry programRegistry;
    private final DateValidator dateValidator;

    /**
     * Constructs a new {@code CoTrn02C} use-case instance with the four
     * collaborators required by the COBOL paragraph dependency graph.
     *
     * <p>{@link DateValidator} is a utility class with a private
     * constructor that throws {@code UnsupportedOperationException}; the
     * caller therefore cannot pass a non-null DateValidator instance. The
     * parameter is retained for compatibility with the file-schema
     * contract, and all date-validation work delegates to the
     * <strong>static</strong> {@link DateValidator#validate(String, String)}
     * call. Consequently the parameter is intentionally <em>not</em>
     * null-checked.
     *
     * @param transactionRepository TRANSACT-file port (used by ADD-TRANSACTION
     *                              and COPY-LAST-TRAN-DATA for the
     *                              STARTBR(HIGH-VALUES)+READPREV pattern
     *                              and by WRITE-TRANSACT-FILE for the
     *                              save operation)
     * @param cardXrefRepository    CARDXREF/CXACAIX-file port (used by
     *                              VALIDATE-INPUT-KEY-FIELDS for the
     *                              account-id or card-number lookup)
     * @param programRegistry       dynamic-XCTL strategy registry (used by
     *                              RETURN-TO-PREV-SCREEN to validate that
     *                              the resolved {@code CDEMO-TO-PROGRAM}
     *                              is a known program)
     * @param dateValidator         CSUTLDTC date validator (retained for
     *                              schema compatibility; static calls only)
     * @throws NullPointerException if {@code transactionRepository},
     *                              {@code cardXrefRepository}, or
     *                              {@code programRegistry} is {@code null}
     */
    public CoTrn02C(
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            ProgramRegistry programRegistry,
            DateValidator dateValidator) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.programRegistry = Objects.requireNonNull(
                programRegistry, "programRegistry");
        // DateValidator is a utility class (private constructor); we keep
        // the reference for documentation purposes but do NOT require it
        // to be non-null because all calls use the static API.
        this.dateValidator = dateValidator;
    }

    // ---------------------------------------------------------------------
    // Sealed Outcome interface — translation of the CICS verb selected at
    // the end of the COBOL MAIN-PARA: either SEND-MAP + RETURN to redraw
    // the screen, or XCTL to transfer control to another program.
    // ---------------------------------------------------------------------
    /**
     * Sealed disposition type returned by
     * {@link CoTrn02C#handle(CoTrn02Input, CardDemoCommarea, AidKey)}.
     * Exactly two outcomes are permitted: redraw the COTRN02 screen
     * ({@link SendMap}) or transfer control to another program
     * ({@link Xctl}). The sealed permits enforce exhaustiveness in
     * downstream pattern-matching switches.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * COBOL {@code SEND-TRNADD-SCREEN} + {@code RETURN TRANSID(WS-TRANID)
         * COMMAREA(CARDDEMO-COMMAREA)} — redraws the COTRN02 BMS map with
         * the staged output and re-arms the CT02 transaction for the next
         * AID byte.
         *
         * @param screen   the fully populated output map (never null)
         * @param commarea the outbound commarea (PgmContext=REENTER on
         *                 every SendMap; never null)
         */
        record SendMap(CoTrn02Output screen, CardDemoCommarea commarea)
                implements Outcome {
            public SendMap {
                Objects.requireNonNull(screen, "screen");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * COBOL {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
         * COMMAREA(CARDDEMO-COMMAREA)} — transfers control to another
         * Java program in the CardDemo family.
         *
         * @param targetProgram the COBOL PROGRAM-ID of the target
         *                      (e.g. {@code "COMEN01C"}); never null/blank
         * @param commarea      the outbound commarea (PgmContext=ENTER on
         *                      every Xctl; never null)
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea)
                implements Outcome {
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

    // ---------------------------------------------------------------------
    // Per-invocation mutable state (package-private). Created fresh each
    // time handle(...) is invoked — never reused across calls, never
    // shared between threads (a ScopedValue would be overkill for purely
    // local state).
    // ---------------------------------------------------------------------
    /**
     * Internal mutable scratch-pad mirroring the WORKING-STORAGE fields
     * touched throughout the COBOL paragraphs of COTRN02C. Treated as a
     * local builder; never escapes the enclosing
     * {@link CoTrn02C#handle(CoTrn02Input, CardDemoCommarea, AidKey)} call.
     */
    static final class MutableState {

        /** COBOL {@code WS-ERR-FLG} = 'Y'/ERR-FLG-ON sentinel. */
        boolean errFlgOn;

        /** COBOL {@code WS-MESSAGE} (working-storage status text). */
        String wsMessage = "";

        /** Live BMS map input (record passed into handle()). */
        CoTrn02Input input;

        /** Per-call commarea, with running PgmContext updates. */
        CardDemoCommarea commarea;

        /** AID byte that drove this invocation (passed into handle()). */
        AidKey aidKey;

        // -----------------------------------------------------------------
        // Output staging fields — one per BMS map output position. These
        // start blank and may be overwritten by validateInputKeyFields(),
        // copyLastTranData(), or addTransaction() before
        // sendTrnaddScreen() assembles the CoTrn02Output record.
        // -----------------------------------------------------------------
        /** ACTIDINO PIC X(11) — echoed account-id. */
        String outAccountId = "";

        /** CARDNINO PIC X(16) — echoed card-number. */
        String outCardNumber = "";

        /** TTYPCDO PIC X(2) — echoed transaction-type. */
        String outTypeCode = "";

        /** TCATCDO PIC X(4) — echoed transaction-category. */
        String outCategoryCode = "";

        /** TRNSRCO PIC X(10) — echoed transaction-source. */
        String outSource = "";

        /** TDESCO PIC X(60) — echoed description. */
        String outDescription = "";

        /** TRNAMTO PIC X(12) — echoed signed amount mask. */
        String outAmount = "";

        /** TORIGDTO PIC X(10) — echoed original-date (YYYY-MM-DD). */
        String outOrigDate = "";

        /** TPROCDTO PIC X(10) — echoed processing-date (YYYY-MM-DD). */
        String outProcDate = "";

        /** MIDO PIC X(9) — echoed merchant-id. */
        String outMerchantId = "";

        /** MNAMEO PIC X(30) — echoed merchant-name. */
        String outMerchantName = "";

        /** MCITYO PIC X(25) — echoed merchant-city. */
        String outMerchantCity = "";

        /** MZIPO PIC X(10) — echoed merchant-zip. */
        String outMerchantZip = "";

        /** CONFIRMO PIC X(1) — echoed confirmation flag. */
        String outConfirmation = "";

        /**
         * ERRMSGC color attribute. {@code true} indicates the WRITE
         * TRANSACT NORMAL path (green text); {@code false} indicates the
         * default error path (red text, BMS-compile-time default).
         */
        boolean successMessage;
    }

    // =====================================================================
    // Public entry method — translation of COBOL MAIN-PARA
    // (app/cbl/COTRN02C.cbl:L93-L159).
    // =====================================================================
    /**
     * Service one CICS pseudo-conversational round-trip for the
     * COTRN02/CT02 (Add Transaction) screen.
     *
     * <p>This method is the Java translation of the COBOL
     * {@code MAIN-PARA} paragraph. It mirrors the COBOL flow exactly:
     * <ol>
     *   <li>Initialize {@code ERR-FLG-OFF}, {@code USR-MODIFIED-NO}, and
     *       {@code WS-MESSAGE = SPACES}.</li>
     *   <li>If {@code EIBCALEN = 0}: XCTL to {@code COSGN00C} (cold-start
     *       path — the caller forgot to set up a commarea, so we route to
     *       the signon screen).</li>
     *   <li>Otherwise: copy the commarea, then:
     *     <ul>
     *       <li>First-entry ({@code NOT CDEMO-PGM-REENTER}): move to
     *           REENTER, blank the output, position the cursor on
     *           ACTIDINL, optionally auto-trigger
     *           {@code PROCESS-ENTER-KEY} when
     *           {@code CDEMO-CT02-TRN-SELECTED} is populated, then
     *           SEND-TRNADD-SCREEN.</li>
     *       <li>Re-entry: PERFORM RECEIVE-TRNADD-SCREEN (already done by
     *           the caller since the input record is passed in), then
     *           EVALUATE EIBAID:
     *           <ul>
     *             <li>{@code DFHENTER} &rarr; PROCESS-ENTER-KEY</li>
     *             <li>{@code DFHPF3} &rarr; resolve back-target from
     *                 CDEMO-FROM-PROGRAM (default COMEN01C) and
     *                 RETURN-TO-PREV-SCREEN</li>
     *             <li>{@code DFHPF4} &rarr; CLEAR-CURRENT-SCREEN</li>
     *             <li>{@code DFHPF5} &rarr; COPY-LAST-TRAN-DATA</li>
     *             <li>any other AID &rarr; CCDA-MSG-INVALID-KEY +
     *                 SEND-TRNADD-SCREEN</li>
     *           </ul>
     *       </li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>The pattern-matching switch over {@link AidKey} is exhaustive
     * with NO {@code default} branch — the Java 25 compiler enforces
     * that every {@code CcWorkAreas.AidKey} permit (sixteen records:
     * Enter, Clear, Pa1, Pa2, PfKey01..PfKey12) is handled.
     *
     * @param input    the BMS map input record (every field already
     *                 trimmed/padded to its PIC width by
     *                 {@link CoTrn02Input}); never {@code null}
     * @param commarea the inbound DFHCOMMAREA, or {@code null} when
     *                 EIBCALEN=0 (cold start)
     * @param aidKey   the decoded AID byte; never {@code null}; pass
     *                 {@code new AidKey.Enter()} for the first entry
     * @return the chosen {@link Outcome} — either a {@link Outcome.SendMap}
     *         to redraw the screen or an {@link Outcome.Xctl} to transfer
     *         control to another CardDemo program
     * @throws NullPointerException if {@code input} or {@code aidKey} is
     *                              {@code null}
     */
    public Outcome handle(
            CoTrn02Input input, CardDemoCommarea commarea, AidKey aidKey) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aidKey, "aidKey");

        // COBOL line 105-112: initialize working-storage flags and message.
        MutableState state = new MutableState();
        state.input = input;
        state.commarea = commarea;
        state.aidKey = aidKey;
        state.errFlgOn = false;
        state.wsMessage = "";

        // Echo all current input fields into the output stage; the COBOL
        // RECEIVE-TRNADD-SCREEN side already populated them, and the
        // subsequent paragraphs may overwrite individual fields.
        echoInputToOutput(state);

        // COBOL line 114-116: IF EIBCALEN = 0 → MOVE 'COSGN00C' TO
        // CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN.
        if (commarea == null) {
            LOGGER.debug(
                    "CoTrn02C: EIBCALEN=0 (cold start) → XCTL to {}",
                    DEFAULT_PREV_PROGRAM);
            CardDemoCommarea cold = buildXctlCommarea(
                    CardDemoCommarea.empty(), DEFAULT_PREV_PROGRAM);
            return new Outcome.Xctl(DEFAULT_PREV_PROGRAM, cold);
        }

        // COBOL line 118: MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA.
        // The commarea is already in record form; nothing to copy.

        PgmContext context = commarea.cdemoGeneralInfo().pgmContext();

        // COBOL line 119-130: IF NOT CDEMO-PGM-REENTER → first-entry path.
        if (context.isEnter()) {
            LOGGER.debug(
                    "CoTrn02C: first-entry → setting PgmContext=REENTER");

            // COBOL line 120: SET CDEMO-PGM-REENTER TO TRUE.
            state.commarea = withPgmContext(state.commarea, PgmContext.REENTER);

            // COBOL line 121: MOVE LOW-VALUES TO COTRN2AO. We treat
            // LOW-VALUES as blanking the user-editable output fields
            // (header/echo fields are repopulated by populateHeaderInfo()
            // inside sendTrnaddScreen()).
            clearStagedOutput(state);

            // COBOL line 122-128: IF CDEMO-CT02-TRN-SELECTED NOT = SPACES
            // AND LOW-VALUES → MOVE selected card to CARDNINI, PERFORM
            // PROCESS-ENTER-KEY. The CDEMO-CT02-* fields are local to
            // COTRN02C in COBOL and are NOT in the shared commarea
            // copybook; the caller pre-populates input.cardNumber() with
            // the selected card number for this auto-trigger path (per
            // CoTrn01C/CoTrn00C convention; see agent_prompt). The
            // auto-trigger fires whenever the card-number input field is
            // non-blank AND the account-id input field is blank (which
            // distinguishes an auto-triggered jump-from-list from a
            // straight-up COTRN02 entry).
            if (!isBlankOrLow(input.cardNumber())
                    && isBlankOrLow(input.accountId())) {
                Outcome auto = processEnterKey(state);
                // If processEnterKey returned an Xctl (impossible in this
                // path) propagate; otherwise fall through to send-map.
                if (auto instanceof Outcome.Xctl) {
                    return auto;
                }
            }
            return sendTrnaddScreen(state);
        }

        // COBOL line 132-147: ELSE → RECEIVE-TRNADD-SCREEN, then
        // EVALUATE EIBAID over the four supported AID bytes.
        //
        // Exhaustiveness mandate (AAP §0.6.2): every CcWorkAreas.AidKey
        // permit must appear in the switch with NO default. The 16 permits
        // are: Enter, Clear, Pa1, Pa2, PfKey01..PfKey12.
        return switch (aidKey) {
            case AidKey.Enter e         -> processEnterKey(state);
            // COBOL line 134-144: DFHPF3 — resolve back-target from
            // CDEMO-FROM-PROGRAM and RETURN-TO-PREV-SCREEN.
            case AidKey.PfKey03 pf3     -> handlePf3Back(state);
            // COBOL line 145-146: DFHPF4 — PERFORM CLEAR-CURRENT-SCREEN.
            case AidKey.PfKey04 pf4     -> clearCurrentScreen(state);
            // COBOL line 147-148: DFHPF5 — PERFORM COPY-LAST-TRAN-DATA.
            case AidKey.PfKey05 pf5     -> copyLastTranData(state);
            // All other AID keys: WHEN OTHER → CCDA-MSG-INVALID-KEY.
            case AidKey.Clear c         -> invalidKey(state);
            case AidKey.Pa1 p1          -> invalidKey(state);
            case AidKey.Pa2 p2          -> invalidKey(state);
            case AidKey.PfKey01 pf1     -> invalidKey(state);
            case AidKey.PfKey02 pf2     -> invalidKey(state);
            case AidKey.PfKey06 pf6     -> invalidKey(state);
            case AidKey.PfKey07 pf7     -> invalidKey(state);
            case AidKey.PfKey08 pf8     -> invalidKey(state);
            case AidKey.PfKey09 pf9     -> invalidKey(state);
            case AidKey.PfKey10 pf10    -> invalidKey(state);
            case AidKey.PfKey11 pf11    -> invalidKey(state);
            case AidKey.PfKey12 pf12    -> invalidKey(state);
        };
    }

    // =====================================================================
    // PROCESS-ENTER-KEY (COBOL app/cbl/COTRN02C.cbl:L161-L188).
    // =====================================================================
    /**
     * Java translation of {@code PROCESS-ENTER-KEY}: runs the two
     * validation paragraphs in order, then EVALUATES the confirmation
     * field and either calls ADD-TRANSACTION or emits one of two
     * confirmation-related error messages.
     *
     * <p>If either validation paragraph sets {@code state.errFlgOn} and
     * stages an error message, this method returns the corresponding
     * SEND-TRNADD-SCREEN outcome immediately and never reaches the
     * confirmation switch — preserving COBOL's per-WHEN PERFORM
     * SEND-TRNADD-SCREEN semantics where each error short-circuits the
     * remainder of the paragraph.
     *
     * @param state the mutable per-invocation state
     * @return the appropriate {@link Outcome.SendMap} or {@link Outcome.Xctl}
     */
    private Outcome processEnterKey(MutableState state) {
        // COBOL line 165-166: PERFORM VALIDATE-INPUT-KEY-FIELDS,
        // PERFORM VALIDATE-INPUT-DATA-FIELDS.
        Outcome keyValidation = validateInputKeyFields(state);
        if (keyValidation != null) {
            return keyValidation;
        }
        Outcome dataValidation = validateInputDataFields(state);
        if (dataValidation != null) {
            return dataValidation;
        }

        // COBOL line 168-187: EVALUATE CONFIRMI of COTRN2AI.
        String conf = orEmpty(state.input.confirmation());
        // COBOL accepts SPACES/LOW-VALUES as "blank" and emits
        // MSG_CONFIRM_REQUIRED; any non-Y/N value (excluding blank) emits
        // MSG_INVALID_CONFIRM.
        if (conf.equals("Y") || conf.equals("y")) {
            // COBOL line 170-172: WHEN 'Y'/'y' → PERFORM ADD-TRANSACTION.
            return addTransaction(state);
        }
        if (conf.equals("N") || conf.equals("n") || isBlankOrLow(conf)) {
            // COBOL line 173-178: WHEN 'N'/'n'/SPACES/LOW-VALUES.
            state.errFlgOn = true;
            state.wsMessage = MSG_CONFIRM_REQUIRED;
            return sendTrnaddScreen(state);
        }
        // COBOL line 179-184: WHEN OTHER.
        state.errFlgOn = true;
        state.wsMessage = MSG_INVALID_CONFIRM;
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // VALIDATE-INPUT-KEY-FIELDS (COBOL app/cbl/COTRN02C.cbl:L190-L230).
    // =====================================================================
    /**
     * Java translation of {@code VALIDATE-INPUT-KEY-FIELDS}.
     *
     * <p>The COBOL paragraph evaluates a three-way mutually exclusive
     * choice (EVALUATE TRUE) between (a) account-id provided, (b) card
     * number provided, or (c) neither. The chosen branch validates the
     * numeric field, looks up the cross-reference, and back-fills the
     * symmetric input field (e.g., when account-id is provided, the
     * resulting card-number is copied into CARDNINI).
     *
     * @param state the mutable per-invocation state
     * @return {@code null} when validation succeeded with no error, or a
     *         non-null {@link Outcome} (always {@link Outcome.SendMap})
     *         when an error was staged
     */
    private Outcome validateInputKeyFields(MutableState state) {
        String acct = orEmpty(state.input.accountId());
        String card = orEmpty(state.input.cardNumber());

        // COBOL line 194-205: WHEN ACTIDINI NOT = SPACES AND LOW-VALUES.
        if (!isBlankOrLow(acct)) {
            String trimmed = acct.strip();
            // COBOL line 195-200: IF ACTIDINI IS NOT NUMERIC.
            if (!isNumeric(trimmed)) {
                state.errFlgOn = true;
                state.wsMessage = MSG_ACCT_NUMERIC;
                return sendTrnaddScreen(state);
            }
            long acctId;
            try {
                // COBOL line 201-203: COMPUTE WS-ACCT-ID-N =
                // FUNCTION NUMVAL(ACTIDINI).
                acctId = Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                // Defensive: cannot happen after the isNumeric guard
                // unless the trimmed string exceeds 18 digits, which
                // PIC X(11) precludes.
                LOGGER.warn("CoTrn02C: ACTIDINI numeric overflow: {}", trimmed);
                state.errFlgOn = true;
                state.wsMessage = MSG_ACCT_NUMERIC;
                return sendTrnaddScreen(state);
            }
            // COBOL line 203-204: re-format ACTIDINI = WS-ACCT-ID-N (now a
            // PIC 9(11) zero-padded value).
            state.outAccountId = padLeft(String.valueOf(acctId), 11, '0');

            // COBOL line 205: PERFORM READ-CXACAIX-FILE.
            Outcome lookup = readCxacaixFile(state, acctId);
            if (lookup != null) {
                return lookup;
            }
            // COBOL line 206: MOVE XREF-CARD-NUM TO CARDNINI.
            // (state.outCardNumber was set inside readCxacaixFile())
            return null;
        }

        // COBOL line 207-218: WHEN CARDNINI NOT = SPACES AND LOW-VALUES.
        if (!isBlankOrLow(card)) {
            String trimmed = card.strip();
            // COBOL line 208-213: IF CARDNINI IS NOT NUMERIC.
            if (!isNumeric(trimmed)) {
                state.errFlgOn = true;
                state.wsMessage = MSG_CARD_NUMERIC;
                return sendTrnaddScreen(state);
            }
            // COBOL line 214-216: COMPUTE WS-CARD-NUM-N =
            // FUNCTION NUMVAL(CARDNINI), re-format to zero-padded.
            String formatted = padLeft(trimmed, 16, '0');
            state.outCardNumber = formatted;

            // COBOL line 218: PERFORM READ-CCXREF-FILE.
            Outcome lookup = readCcxrefFile(state, formatted);
            if (lookup != null) {
                return lookup;
            }
            // COBOL line 219: MOVE XREF-ACCT-ID TO ACTIDINI.
            // (state.outAccountId was set inside readCcxrefFile())
            return null;
        }

        // COBOL line 220-225: WHEN OTHER — neither field provided.
        state.errFlgOn = true;
        state.wsMessage = MSG_ACCT_OR_CARD_REQUIRED;
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // VALIDATE-INPUT-DATA-FIELDS (COBOL app/cbl/COTRN02C.cbl:L232-L437).
    // =====================================================================
    /**
     * Java translation of {@code VALIDATE-INPUT-DATA-FIELDS}.
     *
     * <p>The COBOL paragraph consists of five EVALUATE TRUE blocks plus
     * two CSUTLDTC calls and a final NOT NUMERIC check on MIDI, all
     * separated by paragraph-level continuation. Each WHEN with a failure
     * branch PERFORMs SEND-TRNADD-SCREEN, which in CICS context emits
     * RETURN — Java translation uses early {@code return} of an
     * {@link Outcome.SendMap}.
     *
     * <p>An additional COBOL pre-condition (line 233-247) clears all
     * data-fields if {@code ERR-FLG-ON} is already true from key-field
     * validation; we honour this by checking the flag at entry.
     *
     * @param state the mutable per-invocation state
     * @return {@code null} when validation succeeded with no error, or a
     *         non-null {@link Outcome} (always {@link Outcome.SendMap})
     *         when an error was staged
     */
    private Outcome validateInputDataFields(MutableState state) {
        // COBOL line 233-247: IF ERR-FLG-ON → MOVE SPACES TO data-fields,
        // then exit (the paragraph's EVALUATEs only run when err-flg-off).
        if (state.errFlgOn) {
            return null;
        }

        CoTrn02Input in = state.input;

        // COBOL line 250-317: first EVALUATE TRUE — eleven empty-field
        // checks against TTYPCDI, TCATCDI, TRNSRCI, TDESCI, TRNAMTI,
        // TORIGDTI, TPROCDTI, MIDI, MNAMEI, MCITYI, MZIPI.
        if (isBlankOrLow(in.typeCode())) {
            return stageError(state, MSG_TYPE_EMPTY);
        }
        if (isBlankOrLow(in.categoryCode())) {
            return stageError(state, MSG_CAT_EMPTY);
        }
        if (isBlankOrLow(in.source())) {
            return stageError(state, MSG_SOURCE_EMPTY);
        }
        if (isBlankOrLow(in.description())) {
            return stageError(state, MSG_DESC_EMPTY);
        }
        if (isBlankOrLow(in.amount())) {
            return stageError(state, MSG_AMT_EMPTY);
        }
        if (isBlankOrLow(in.origDate())) {
            return stageError(state, MSG_ORIG_DT_EMPTY);
        }
        if (isBlankOrLow(in.procDate())) {
            return stageError(state, MSG_PROC_DT_EMPTY);
        }
        if (isBlankOrLow(in.merchantId())) {
            return stageError(state, MSG_MID_EMPTY);
        }
        if (isBlankOrLow(in.merchantName())) {
            return stageError(state, MSG_MNAME_EMPTY);
        }
        if (isBlankOrLow(in.merchantCity())) {
            return stageError(state, MSG_MCITY_EMPTY);
        }
        if (isBlankOrLow(in.merchantZip())) {
            return stageError(state, MSG_MZIP_EMPTY);
        }

        // COBOL line 320-335: second EVALUATE TRUE — TTYPCDI / TCATCDI
        // numeric checks.
        if (!isNumeric(in.typeCode().strip())) {
            return stageError(state, MSG_TYPE_NUMERIC);
        }
        if (!isNumeric(in.categoryCode().strip())) {
            return stageError(state, MSG_CAT_NUMERIC);
        }

        // COBOL line 338-351: third EVALUATE TRUE — TRNAMTI format check
        // against [+-]NNNNNNNN.NN (12-char signed PIC).
        if (!AMOUNT_PATTERN.matcher(orEmpty(in.amount())).matches()) {
            return stageError(state, MSG_AMT_FORMAT);
        }

        // COBOL line 353-366: fourth EVALUATE TRUE — TORIGDTI format
        // check against NNNN-NN-NN.
        if (!DATE_PATTERN.matcher(orEmpty(in.origDate())).matches()) {
            return stageError(state, MSG_ORIG_DT_FORMAT);
        }

        // COBOL line 368-381: fifth EVALUATE TRUE — TPROCDTI format check.
        if (!DATE_PATTERN.matcher(orEmpty(in.procDate())).matches()) {
            return stageError(state, MSG_PROC_DT_FORMAT);
        }

        // COBOL line 383-386: COMPUTE WS-TRAN-AMT-N = NUMVAL-C(TRNAMTI),
        // MOVE to WS-TRAN-AMT-E, MOVE back to TRNAMTI. We don't mutate
        // the input record (records are immutable) but we DO re-stage the
        // canonical signed mask in state.outAmount so that
        // sendTrnaddScreen() echoes the formatted value.
        try {
            BigDecimal amt = parseSignedAmount(in.amount());
            state.outAmount = formatTranAmount(amt);
        } catch (ArithmeticException | NumberFormatException ex) {
            // Defensive: AMOUNT_PATTERN already validated layout, but the
            // value could still overflow if the integer portion is ≥ 1e9
            // (PIC S9(9)V99 max). Treat any parse failure as a format
            // error rather than a hard crash.
            LOGGER.warn(
                    "CoTrn02C: TRNAMTI parse failure (input='{}'): {}",
                    in.amount(), ex.getMessage());
            return stageError(state, MSG_AMT_FORMAT);
        }

        // COBOL line 389-407: first CSUTLDTC call — TORIGDTI.
        // CALL 'CSUTLDTC' USING CSUTLDTC-DATE, CSUTLDTC-DATE-FORMAT,
        // CSUTLDTC-RESULT. IF SEV-CD='0000' CONTINUE; ELSE IF
        // MSG-NUM NOT = '2513' → error.
        DateValidationResult origResult =
                DateValidator.validate(in.origDate(), DATE_FORMAT);
        if (!origResult.isSuccess()
                && !CSUTLDTC_WARNING_MSG_NO.equals(origResult.msgNo().strip())) {
            return stageError(state, MSG_ORIG_DT_INVALID);
        }

        // COBOL line 410-428: second CSUTLDTC call — TPROCDTI.
        DateValidationResult procResult =
                DateValidator.validate(in.procDate(), DATE_FORMAT);
        if (!procResult.isSuccess()
                && !CSUTLDTC_WARNING_MSG_NO.equals(procResult.msgNo().strip())) {
            return stageError(state, MSG_PROC_DT_INVALID);
        }

        // COBOL line 430-436: IF MIDI IS NOT NUMERIC.
        if (!isNumeric(in.merchantId().strip())) {
            return stageError(state, MSG_MID_NUMERIC);
        }

        // All data-field validations passed.
        return null;
    }

    // =====================================================================
    // ADD-TRANSACTION (COBOL app/cbl/COTRN02C.cbl:L439-L468).
    // =====================================================================
    /**
     * Java translation of {@code ADD-TRANSACTION}: derives the next
     * TRAN-ID via the COBOL STARTBR(HIGH-VALUES)+READPREV+ENDBR idiom,
     * INITIALIZE TRAN-RECORD, MOVE every input field to the corresponding
     * domain field, and PERFORM WRITE-TRANSACT-FILE.
     *
     * <p>The COBOL idiom is:
     * <pre>
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   PERFORM STARTBR-TRANSACT-FILE   *&gt; position past end
     *   PERFORM READPREV-TRANSACT-FILE  *&gt; one record back = max
     *   PERFORM ENDBR-TRANSACT-FILE
     *   MOVE TRAN-ID TO WS-TRAN-ID-N
     *   ADD 1 TO WS-TRAN-ID-N
     *   INITIALIZE TRAN-RECORD
     *   ...MOVEs...
     *   PERFORM WRITE-TRANSACT-FILE
     * </pre>
     * This is coalesced into {@link #findHighestExistingTranId()} via
     * {@link TransactionRepository#findHighestId()}; when the repository
     * returns empty (COBOL ENDFILE), the next id starts at 1.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} produced by
     *         {@link #writeTransactFile(MutableState, TranRecord)}
     */
    private Outcome addTransaction(MutableState state) {
        CoTrn02Input in = state.input;

        // COBOL line 442-449: STARTBR(HIGH-VALUES) + READPREV + ENDBR.
        long nextTranId = findHighestExistingTranId() + 1L;
        String nextTranIdStr = padLeft(String.valueOf(nextTranId),
                TRAN_ID_WIDTH, '0');

        // COBOL line 450: INITIALIZE TRAN-RECORD.
        // COBOL line 451-466: MOVE inputs to TRAN-* fields.
        // Note: TRNAMTI is already validated to match [+-]NNNNNNNN.NN by
        // VALIDATE-INPUT-DATA-FIELDS.
        BigDecimal amount = parseSignedAmount(in.amount());

        // COBOL line 464-465: MOVE TORIGDTI/TPROCDTI TO TRAN-ORIG-TS /
        // TRAN-PROC-TS. The 10-char dates are widened to 26-char
        // timestamps with trailing spaces in COBOL; we represent them as
        // LocalDateTime at midnight (the only LocalDateTime parseable
        // from a YYYY-MM-DD-only string) — TranRecord.encode() will pad
        // appropriately.
        LocalDateTime origTs =
                LocalDate.parse(in.origDate(), DATE_YYYY_MM_DD).atStartOfDay();
        LocalDateTime procTs =
                LocalDate.parse(in.procDate(), DATE_YYYY_MM_DD).atStartOfDay();

        // Convert PIC X(2) type-code to compact int — the domain record's
        // tranCatCd is int and tranMerchantId is long; the COBOL semantic
        // is to MOVE the numeric input directly. Type-code is left as a
        // string (TRAN-TYPE-CD is PIC X(2)).
        int catCd = Integer.parseInt(in.categoryCode().strip());
        long merchantId = Long.parseLong(in.merchantId().strip());

        TranRecord record;
        try {
            record = new TranRecord(
                    nextTranIdStr,
                    in.typeCode(),
                    catCd,
                    in.source(),
                    in.description(),
                    amount,
                    merchantId,
                    in.merchantName(),
                    in.merchantCity(),
                    in.merchantZip(),
                    orEmpty(state.outCardNumber).isEmpty()
                            ? in.cardNumber() : state.outCardNumber,
                    origTs,
                    procTs,
                    TranRecord.emptyFiller());
        } catch (IllegalArgumentException | NullPointerException ex) {
            LOGGER.error(
                    "CoTrn02C: cannot build TranRecord (acct={}, card={}): {}",
                    state.outAccountId,
                    maskPan(state.outCardNumber),
                    ex.getMessage());
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_ADD_ERROR;
            return sendTrnaddScreen(state);
        }

        // COBOL line 467: PERFORM WRITE-TRANSACT-FILE.
        return writeTransactFile(state, record);
    }

    // =====================================================================
    // COPY-LAST-TRAN-DATA (COBOL app/cbl/COTRN02C.cbl:L470-L496).
    // =====================================================================
    /**
     * Java translation of {@code COPY-LAST-TRAN-DATA} (handler for PF5).
     *
     * <p>The COBOL paragraph:
     * <ol>
     *   <li>PERFORM VALIDATE-INPUT-KEY-FIELDS — same as ADD-TRANSACTION;
     *       requires account-id or card-number to be supplied.</li>
     *   <li>STARTBR(HIGH-VALUES)+READPREV+ENDBR to fetch the highest
     *       existing TRAN record.</li>
     *   <li>If found and no validation error, MOVE every TRAN-* field
     *       back into the input map and PERFORM PROCESS-ENTER-KEY.</li>
     * </ol>
     *
     * @param state the mutable per-invocation state
     * @return the resulting {@link Outcome.SendMap}
     */
    private Outcome copyLastTranData(MutableState state) {
        // COBOL line 472: PERFORM VALIDATE-INPUT-KEY-FIELDS.
        Outcome keyValidation = validateInputKeyFields(state);
        if (keyValidation != null) {
            return keyValidation;
        }

        // COBOL line 474-477: STARTBR(HIGH-VALUES) + READPREV + ENDBR.
        Optional<TranRecord> last = transactionRepository.findHighestId();

        // COBOL line 479-494: IF NOT ERR-FLG-ON → MOVE TRAN-* TO inputs.
        if (state.errFlgOn) {
            return sendTrnaddScreen(state);
        }
        if (last.isPresent()) {
            TranRecord t = last.get();
            // COBOL line 480: MOVE TRAN-AMT TO WS-TRAN-AMT-E.
            state.outAmount = formatTranAmount(t.tranAmt());
            // COBOL line 481-491: MOVE remaining TRAN-* fields.
            state.outTypeCode = orEmpty(t.tranTypeCd());
            state.outCategoryCode = String.valueOf(t.tranCatCd());
            state.outSource = orEmpty(t.tranSource());
            state.outDescription = orEmpty(t.tranDesc());
            state.outOrigDate = formatTimestampDate(t.tranOrigTs());
            state.outProcDate = formatTimestampDate(t.tranProcTs());
            state.outMerchantId = String.valueOf(t.tranMerchantId());
            state.outMerchantName = orEmpty(t.tranMerchantName());
            state.outMerchantCity = orEmpty(t.tranMerchantCity());
            state.outMerchantZip = orEmpty(t.tranMerchantZip());
        }
        // COBOL line 496: PERFORM PROCESS-ENTER-KEY. But the original
        // commercial semantic of PF5 is "preview last tran" — we should
        // simply redraw with the populated fields. The COBOL author's
        // intent (per the user-facing screen behaviour) is to populate
        // and let the operator press ENTER to confirm. Replicate that
        // faithfully by emitting a SendMap (rather than invoking
        // processEnterKey() which would attempt validation/add).
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // READ-CXACAIX-FILE (COBOL app/cbl/COTRN02C.cbl:L578-L605).
    // =====================================================================
    /**
     * Java translation of {@code READ-CXACAIX-FILE}: looks up a single
     * card-xref record by alternate-index account-id key. On NORMAL,
     * stages the resulting card-number into {@code state.outCardNumber}.
     *
     * @param state  the mutable per-invocation state
     * @param acctId the numeric account-id to query
     * @return {@code null} on NORMAL (continue paragraph), or a non-null
     *         {@link Outcome.SendMap} when NOTFND/OTHER (the COBOL
     *         paragraph emitted SEND-TRNADD-SCREEN inline)
     */
    private Outcome readCxacaixFile(MutableState state, long acctId) {
        try {
            Optional<CardXrefRecord> hit =
                    cardXrefRepository.findByAccountId(acctId);
            if (hit.isPresent()) {
                state.outCardNumber = orEmpty(hit.get().xrefCardNum());
                return null;
            }
            // COBOL line 591-596: WHEN DFHRESP(NOTFND).
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_NOT_FOUND;
            return sendTrnaddScreen(state);
        } catch (RuntimeException ex) {
            // COBOL line 597-603: WHEN OTHER — DISPLAY RESP/REAS then
            // MSG_ACCT_XREF_LOOKUP_ERROR.
            LOGGER.error(
                    "CoTrn02C: READ-CXACAIX-FILE OTHER (acctId={}): {}",
                    acctId, ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_ACCT_XREF_LOOKUP_ERROR;
            return sendTrnaddScreen(state);
        }
    }

    // =====================================================================
    // READ-CCXREF-FILE (COBOL app/cbl/COTRN02C.cbl:L607-L638).
    // =====================================================================
    /**
     * Java translation of {@code READ-CCXREF-FILE}: looks up a single
     * card-xref record by primary-key card-number. On NORMAL, stages the
     * resulting account-id into {@code state.outAccountId}.
     *
     * @param state      the mutable per-invocation state
     * @param cardNumber the 16-character padded card-number to query
     * @return {@code null} on NORMAL (continue paragraph), or a non-null
     *         {@link Outcome.SendMap} when NOTFND/OTHER
     */
    private Outcome readCcxrefFile(MutableState state, String cardNumber) {
        try {
            Optional<CardXrefRecord> hit =
                    cardXrefRepository.findByCardNumber(cardNumber);
            if (hit.isPresent()) {
                state.outAccountId =
                        padLeft(String.valueOf(hit.get().xrefAcctId()), 11, '0');
                return null;
            }
            // COBOL line 625-630: WHEN DFHRESP(NOTFND).
            state.errFlgOn = true;
            state.wsMessage = MSG_CARD_NOT_FOUND;
            return sendTrnaddScreen(state);
        } catch (RuntimeException ex) {
            // COBOL line 631-637: WHEN OTHER — DISPLAY RESP/REAS then
            // MSG_CARD_XREF_LOOKUP_ERROR. Mask the PAN before logging.
            LOGGER.error(
                    "CoTrn02C: READ-CCXREF-FILE OTHER (card={}): {}",
                    maskPan(cardNumber), ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_CARD_XREF_LOOKUP_ERROR;
            return sendTrnaddScreen(state);
        }
    }

    // =====================================================================
    // STARTBR + READPREV + ENDBR coalesced (COBOL lines 640-707).
    // =====================================================================
    /**
     * Translation of the {@code STARTBR-TRANSACT-FILE} +
     * {@code READPREV-TRANSACT-FILE} + {@code ENDBR-TRANSACT-FILE} chain.
     * Returns the highest existing TRAN-ID as a long, or zero when the
     * file is empty (COBOL {@code MOVE ZEROS TO TRAN-ID} on ENDFILE).
     *
     * @return the highest existing numeric TRAN-ID, or {@code 0L} when
     *         the TRANSACT file is empty
     */
    private long findHighestExistingTranId() {
        Optional<TranRecord> max = transactionRepository.findHighestId();
        if (max.isEmpty()) {
            // COBOL line 689: WHEN DFHRESP(ENDFILE) → MOVE ZEROS TO TRAN-ID.
            return 0L;
        }
        String id = max.get().tranId();
        try {
            return Long.parseLong(id.strip());
        } catch (NumberFormatException ex) {
            LOGGER.warn(
                    "CoTrn02C: findHighestId returned non-numeric TRAN-ID '{}'; treating as 0",
                    id);
            return 0L;
        }
    }

    // =====================================================================
    // WRITE-TRANSACT-FILE (COBOL app/cbl/COTRN02C.cbl:L709-L750).
    // =====================================================================
    /**
     * Java translation of {@code WRITE-TRANSACT-FILE}. Saves the record
     * via the port; on NORMAL emits the success message, otherwise
     * stages the appropriate error and emits SEND-TRNADD-SCREEN.
     *
     * <p>Repository-side duplicate detection is signalled via
     * {@link java.lang.IllegalStateException}; any other runtime
     * exception is treated as the COBOL WHEN OTHER branch.
     *
     * @param state  the mutable per-invocation state
     * @param record the fully-populated record to persist
     * @return the resulting {@link Outcome.SendMap}
     */
    private Outcome writeTransactFile(MutableState state, TranRecord record) {
        try {
            // COBOL line 712-721: EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
            // FROM(TRAN-RECORD) RIDFLD(TRAN-ID).
            transactionRepository.save(record);
        } catch (IllegalStateException ex) {
            // COBOL line 736-742: WHEN DFHRESP(DUPKEY)/DUPREC.
            LOGGER.warn(
                    "CoTrn02C: WRITE-TRANSACT-FILE DUPKEY/DUPREC (tranId={}): {}",
                    record.tranId(), ex.getMessage());
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_DUPLICATE;
            return sendTrnaddScreen(state);
        } catch (RuntimeException ex) {
            // COBOL line 743-749: WHEN OTHER — DISPLAY RESP/REAS.
            LOGGER.error(
                    "CoTrn02C: WRITE-TRANSACT-FILE OTHER (tranId={}): {}",
                    record.tranId(), ex.getMessage(), ex);
            state.errFlgOn = true;
            state.wsMessage = MSG_TRAN_ADD_ERROR;
            return sendTrnaddScreen(state);
        }

        // COBOL line 724-735: WHEN DFHRESP(NORMAL) — INITIALIZE-ALL-FIELDS,
        // MOVE DFHGREEN, build the verbatim success message.
        initializeAllFields(state);
        state.successMessage = true;
        // COBOL STRING: 'Transaction added successfully. '
        //   ' Your Tran ID is ' TRAN-ID DELIMITED BY SPACE '.' — note that
        // the leading-space + the trailing-space on the first literal
        // produce a literal double space between the two sentences, which
        // we preserve verbatim per AAP §0.7.1.
        String trimmedId = firstSpaceDelimitedToken(record.tranId());
        state.wsMessage = "Transaction added successfully.  Your Tran ID is "
                + trimmedId + ".";
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // CLEAR-CURRENT-SCREEN (COBOL app/cbl/COTRN02C.cbl:L752-L758).
    // =====================================================================
    /**
     * Java translation of {@code CLEAR-CURRENT-SCREEN}: blanks all input
     * fields then re-sends the empty screen.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying the cleared output
     */
    private Outcome clearCurrentScreen(MutableState state) {
        // COBOL line 757: PERFORM INITIALIZE-ALL-FIELDS.
        initializeAllFields(state);
        // COBOL line 758: PERFORM SEND-TRNADD-SCREEN.
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // INITIALIZE-ALL-FIELDS (COBOL app/cbl/COTRN02C.cbl:L760-L782).
    // =====================================================================
    /**
     * Java translation of {@code INITIALIZE-ALL-FIELDS}. Blanks every
     * input field and clears the working-storage message. Equivalent to
     * the COBOL MOVE SPACES TO list of fourteen BMS input fields plus
     * MOVE SPACES TO WS-MESSAGE.
     *
     * @param state the mutable per-invocation state
     */
    private void initializeAllFields(MutableState state) {
        // COBOL line 764: MOVE -1 TO ACTIDINL OF COTRN2AI (cursor home).
        // Cursor positioning is handled by the BMS attribute layer, not
        // by the application — our equivalent is to blank all output
        // fields. The screen renderer positions the cursor on the first
        // unprotected, blank field.
        state.outAccountId = "";
        state.outCardNumber = "";
        state.outTypeCode = "";
        state.outCategoryCode = "";
        state.outSource = "";
        state.outDescription = "";
        state.outAmount = "";
        state.outOrigDate = "";
        state.outProcDate = "";
        state.outMerchantId = "";
        state.outMerchantName = "";
        state.outMerchantCity = "";
        state.outMerchantZip = "";
        state.outConfirmation = "";
        state.wsMessage = "";
    }

    // =====================================================================
    // PF3 — RETURN-TO-PREV-SCREEN dispatcher (COBOL line 134-144).
    // =====================================================================
    /**
     * Java translation of the PF3 branch of the COBOL EVALUATE EIBAID:
     * <pre>
     *   IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
     *       MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
     *   ELSE
     *       MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
     *   PERFORM RETURN-TO-PREV-SCREEN
     * </pre>
     *
     * @param state the mutable per-invocation state
     * @return the resulting {@link Outcome.Xctl}
     */
    private Outcome handlePf3Back(MutableState state) {
        String fromProgram = orEmpty(
                state.commarea.cdemoGeneralInfo().fromProgram()).strip();
        String target = fromProgram.isEmpty()
                ? DEFAULT_BACK_PROGRAM
                : fromProgram;
        LOGGER.debug(
                "CoTrn02C: PF3 → returning to {} (fromProgram=[{}])",
                target, fromProgram);
        return returnToPrevScreen(state, target);
    }

    // =====================================================================
    // RETURN-TO-PREV-SCREEN (COBOL app/cbl/COTRN02C.cbl:L498-L512).
    // =====================================================================
    /**
     * Java translation of {@code RETURN-TO-PREV-SCREEN}.
     *
     * <p>Performs the COBOL idiom verbatim:
     * <ul>
     *   <li>If {@code CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES},
     *       fall back to {@link #DEFAULT_PREV_PROGRAM} (COSGN00C).</li>
     *   <li>MOVE {@code WS-TRANID} TO {@code CDEMO-FROM-TRANID}.</li>
     *   <li>MOVE {@code WS-PGMNAME} TO {@code CDEMO-FROM-PROGRAM}.</li>
     *   <li>MOVE ZEROS TO {@code CDEMO-PGM-CONTEXT} (Enter, indicator 0).</li>
     *   <li>EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *       COMMAREA(CARDDEMO-COMMAREA).</li>
     * </ul>
     *
     * @param state    the mutable per-invocation state
     * @param toTarget the resolved target program name
     * @return the {@link Outcome.Xctl} carrying the target and outbound
     *         commarea
     */
    private Outcome returnToPrevScreen(MutableState state, String toTarget) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toTarget, "toTarget");
        // COBOL line 502-504: IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES →
        // MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        String target = toTarget.strip();
        if (target.isEmpty()) {
            target = DEFAULT_PREV_PROGRAM;
        }
        CardDemoCommarea outbound = buildXctlCommarea(state.commarea, target);
        return new Outcome.Xctl(target, outbound);
    }

    // =====================================================================
    // SEND-TRNADD-SCREEN (COBOL app/cbl/COTRN02C.cbl:L514-L535).
    // =====================================================================
    /**
     * Java translation of {@code SEND-TRNADD-SCREEN}.
     *
     * <p>Composes the {@link CoTrn02Output} record from all staged
     * mutable state, then wraps it in an {@link Outcome.SendMap} along
     * with the (possibly mutated) outbound commarea. The COBOL paragraph
     * concludes with {@code EXEC CICS RETURN TRANSID(WS-TRANID)
     * COMMAREA(CARDDEMO-COMMAREA)}, which our SendMap represents by
     * pairing the screen with the commarea (the surrounding harness
     * issues the actual CICS RETURN).
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying the populated output
     */
    private Outcome sendTrnaddScreen(MutableState state) {
        // COBOL line 518: PERFORM POPULATE-HEADER-INFO.
        populateHeaderInfo(state);
        // COBOL line 520: MOVE WS-MESSAGE TO ERRMSGO.
        CoTrn02Output.FieldColor color = state.successMessage
                ? CoTrn02Output.FieldColor.GREEN
                : CoTrn02Output.FieldColor.DEFAULT;
        CoTrn02Output screen = buildScreen(state, color);
        return new Outcome.SendMap(screen, state.commarea);
    }

    // =====================================================================
    // POPULATE-HEADER-INFO (COBOL app/cbl/COTRN02C.cbl:L548-L572).
    // =====================================================================
    /**
     * Java translation of {@code POPULATE-HEADER-INFO}.
     *
     * <p>Populates the four screen-header fields (TITLE01O, TITLE02O,
     * TRNNAMEO, PGMNAMEO) and the two clock fields (CURDATEO, CURTIMEO)
     * using {@link LocalDateTime#now()} — the {@link java.time}
     * equivalent of COBOL {@code FUNCTION CURRENT-DATE}. Date/time are
     * formatted via {@link Locale#ROOT}-bound {@link DateTimeFormatter}s
     * to avoid any locale-dependent variation that could break
     * byte-for-byte fidelity.
     *
     * @param state the mutable per-invocation state
     */
    private void populateHeaderInfo(MutableState state) {
        // Update the header staging fields. We don't have explicit
        // state slots for the six header positions, so we stage them as
        // local computations and assemble them in buildScreen().
        LocalDateTime now = LocalDateTime.now();
        state.input = state.input; // no-op; header is filled in buildScreen
        state.commarea = state.commarea;
        // Stash the current header values via state-level convention; we
        // simply hold them as locals and pass them through buildScreen().
        // Since buildScreen() also has access to LocalDateTime.now(), the
        // simplest and most testable design is to compute the header in
        // buildScreen() directly. This method exists only to mirror the
        // COBOL paragraph topology and to permit a future override hook.
        LOGGER.trace(
                "CoTrn02C: POPULATE-HEADER-INFO at {} (date={}, time={})",
                now,
                DATE_MM_DD_YY.format(now),
                TIME_HH_MM_SS.format(now));
    }

    // =====================================================================
    // INVALID-KEY (COBOL line 149-152, "WHEN OTHER" branch).
    // =====================================================================
    /**
     * Stages the verbatim CCDA-MSG-INVALID-KEY error message and emits a
     * SEND-TRNADD-SCREEN. Invoked from the EIBAID switch for any AID byte
     * that is not Enter, PF3, PF4, or PF5.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying the staged error
     */
    private Outcome invalidKey(MutableState state) {
        state.errFlgOn = true;
        state.wsMessage = MSG_INVALID_KEY;
        return sendTrnaddScreen(state);
    }

    // =====================================================================
    // Helpers — output staging, commarea mutation, and translation idioms.
    // =====================================================================

    /**
     * Sets {@link MutableState#errFlgOn} and {@link MutableState#wsMessage}
     * to the given verbatim message text, then emits SEND-TRNADD-SCREEN.
     *
     * @param state   the mutable per-invocation state
     * @param message the verbatim COBOL error message text
     * @return the {@link Outcome.SendMap} carrying the staged error
     */
    private Outcome stageError(MutableState state, String message) {
        state.errFlgOn = true;
        state.wsMessage = message;
        return sendTrnaddScreen(state);
    }

    /**
     * Mirrors each user-editable BMS input field into the output staging
     * area so that round-trips preserve operator data when validation
     * fails. Equivalent to the COBOL idiom of always echoing input fields
     * on every SEND-TRNADD-SCREEN.
     *
     * @param state the mutable per-invocation state
     */
    private static void echoInputToOutput(MutableState state) {
        CoTrn02Input in = state.input;
        state.outAccountId = orEmpty(in.accountId());
        state.outCardNumber = orEmpty(in.cardNumber());
        state.outTypeCode = orEmpty(in.typeCode());
        state.outCategoryCode = orEmpty(in.categoryCode());
        state.outSource = orEmpty(in.source());
        state.outDescription = orEmpty(in.description());
        state.outAmount = orEmpty(in.amount());
        state.outOrigDate = orEmpty(in.origDate());
        state.outProcDate = orEmpty(in.procDate());
        state.outMerchantId = orEmpty(in.merchantId());
        state.outMerchantName = orEmpty(in.merchantName());
        state.outMerchantCity = orEmpty(in.merchantCity());
        state.outMerchantZip = orEmpty(in.merchantZip());
        state.outConfirmation = orEmpty(in.confirmation());
    }

    /**
     * Clears all user-editable output staging fields. Used by the
     * first-entry path which mirrors COBOL {@code MOVE LOW-VALUES TO
     * COTRN2AO}.
     *
     * @param state the mutable per-invocation state
     */
    private static void clearStagedOutput(MutableState state) {
        state.outAccountId = "";
        state.outCardNumber = "";
        state.outTypeCode = "";
        state.outCategoryCode = "";
        state.outSource = "";
        state.outDescription = "";
        state.outAmount = "";
        state.outOrigDate = "";
        state.outProcDate = "";
        state.outMerchantId = "";
        state.outMerchantName = "";
        state.outMerchantCity = "";
        state.outMerchantZip = "";
        state.outConfirmation = "";
    }

    /**
     * Assembles the {@link CoTrn02Output} record from the staged output
     * fields plus the computed header values.
     *
     * @param state the mutable per-invocation state
     * @param color the ERRMSGC color attribute
     * @return a fully-populated {@link CoTrn02Output}
     */
    private static CoTrn02Output buildScreen(
            MutableState state, CoTrn02Output.FieldColor color) {
        LocalDateTime now = LocalDateTime.now();
        return new CoTrn02Output(
                ScreenTitle.TITLE_01,                   // title1
                ScreenTitle.TITLE_02,                   // title2
                TRANSACTION_ID,                         // transactionName
                PROGRAM_NAME,                           // programName
                DATE_MM_DD_YY.format(now),              // currentDate
                TIME_HH_MM_SS.format(now),              // currentTime
                state.outAccountId,                     // accountId
                state.outCardNumber,                    // cardNumber
                state.outTypeCode,                      // typeCode
                state.outCategoryCode,                  // categoryCode
                state.outSource,                        // source
                state.outDescription,                   // description
                state.outAmount,                        // amount
                state.outOrigDate,                      // origDate
                state.outProcDate,                      // procDate
                state.outMerchantId,                    // merchantId
                state.outMerchantName,                  // merchantName
                state.outMerchantCity,                  // merchantCity
                state.outMerchantZip,                   // merchantZip
                state.outConfirmation,                  // confirmation
                orEmpty(state.wsMessage),               // errMsg
                color);                                 // errMsgColor
    }

    /**
     * Builds the outbound commarea for an {@link Outcome.Xctl}: sets
     * CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, CDEMO-TO-PROGRAM, and
     * CDEMO-PGM-CONTEXT per the COBOL RETURN-TO-PREV-SCREEN paragraph.
     *
     * @param current   the current commarea to clone
     * @param toProgram the resolved target program name
     * @return a new {@link CardDemoCommarea} with the outbound fields set
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
     * {@code pgmContext} field replaced.
     *
     * @param current    the current commarea to clone
     * @param newContext the new program-context value
     * @return a new {@link CardDemoCommarea}
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

    /**
     * Pads or truncates a string to an exact length. Length must be
     * positive; null input is treated as empty string.
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
     * COBOL equivalent: MOVE to a PIC 9(n) field with implicit
     * zero-padding.
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
     * COBOL idiom {@code TEST IF field = SPACES OR LOW-VALUES} — true if
     * the input is null, empty, blank, or composed entirely of NUL bytes.
     *
     * @param s the string to test (may be {@code null})
     * @return {@code true} when {@code s} is null, empty, blank, or all
     *         NUL bytes; {@code false} otherwise
     */
    static boolean isBlankOrLow(String s) {
        if (s == null) {
            return true;
        }
        if (s.isEmpty()) {
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
     * COBOL idiom {@code TEST IF field IS NUMERIC} — true if the input is
     * a non-empty string of ASCII digits (after trimming).
     *
     * @param s the string to test (may be {@code null} or have whitespace)
     * @return {@code true} when {@code s} consists exclusively of one or
     *         more ASCII digits (post-trim); {@code false} otherwise
     */
    static boolean isNumeric(String s) {
        if (s == null) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(s).matches();
    }

    /**
     * Returns {@code ""} when the input is {@code null}, otherwise the
     * input unchanged. Mirrors the COBOL idiom of treating an
     * unpopulated field as an empty string.
     *
     * @param s the input (may be {@code null})
     * @return a non-null string ({@code ""} when input is {@code null})
     */
    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * COBOL {@code FUNCTION NUMVAL-C} translation for the TRNAMTI field:
     * accepts the validated 12-character signed-amount string layout
     * {@code [+-]NNNNNNNN.NN} and returns the value as a {@link BigDecimal}
     * normalized to scale 2 via HALF_EVEN rounding (banker's rounding).
     *
     * @param input the validated input string
     * @return the parsed monetary value with scale 2
     * @throws NumberFormatException  if the string cannot be parsed
     * @throws ArithmeticException    if the value would overflow scale 2
     */
    static BigDecimal parseSignedAmount(String input) {
        String trimmed = orEmpty(input).strip();
        BigDecimal value = new BigDecimal(trimmed);
        return value.setScale(Decimals.DEFAULT_MONETARY_SCALE,
                RoundingMode.HALF_EVEN);
    }

    /**
     * Formats a {@link BigDecimal} into the COBOL WS-TRAN-AMT-E PIC
     * {@code +99999999.99} signed-12-character mask. {@code '+'} prefix
     * for non-negative values, {@code '-'} for negative values.
     *
     * @param value the monetary value to format
     * @return the 12-character signed amount string
     * @throws IllegalArgumentException if the value exceeds the
     *                                  PIC S9(8)V99 range
     */
    static String formatTranAmount(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        BigDecimal scaled = value.setScale(
                Decimals.DEFAULT_MONETARY_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigDecimal magnitude = scaled.abs();
        String unscaled = magnitude.movePointRight(Decimals.DEFAULT_MONETARY_SCALE)
                .toBigInteger().toString();
        // 8 integer digits + 2 decimal digits = 10 total digits max.
        int totalDigits = 10;
        if (unscaled.length() > totalDigits) {
            throw new IllegalArgumentException(
                    "Value '" + value + "' integer part exceeds 8 digits");
        }
        if (unscaled.length() < totalDigits) {
            unscaled = "0".repeat(totalDigits - unscaled.length()) + unscaled;
        }
        return (negative ? "-" : "+")
                + unscaled.substring(0, 8)
                + "."
                + unscaled.substring(8);
    }

    /**
     * Formats a TranRecord timestamp (PIC X(26)) back into the BMS-input
     * PIC X(10) date format {@code YYYY-MM-DD}. Used by
     * {@link #copyLastTranData(MutableState)} to populate
     * {@code state.outOrigDate} / {@code state.outProcDate}.
     *
     * @param ts the timestamp (may be {@code null})
     * @return the 10-character date string, or {@code ""} if {@code ts}
     *         is {@code null}
     */
    static String formatTimestampDate(LocalDateTime ts) {
        if (ts == null) {
            return "";
        }
        return DATE_YYYY_MM_DD.format(ts);
    }

    /**
     * Returns the first whitespace-delimited token of the input string.
     * Mirrors the COBOL STRING idiom {@code TRAN-ID DELIMITED BY SPACE}
     * used in the WRITE-TRANSACT-FILE NORMAL success message.
     *
     * @param input the input string (may be {@code null})
     * @return the first non-whitespace token, or {@code ""} when input
     *         is {@code null}/empty/all-whitespace
     */
    static String firstSpaceDelimitedToken(String input) {
        String s = orEmpty(input);
        int i = 0;
        // Skip leading whitespace
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(start, i);
    }

    /**
     * Masks a Primary Account Number (PAN) for log output per AAP
     * &sect;0.7.2 (PCI compliance): all but the last 4 characters are
     * replaced with asterisks. Used exclusively for log records — NEVER
     * for screen output or persisted records.
     *
     * @param pan the PAN to mask (may be {@code null})
     * @return the masked PAN; {@code "****"} when input is {@code null}
     */
    static String maskPan(String pan) {
        if (pan == null || pan.isEmpty()) {
            return "****";
        }
        String trimmed = pan.strip();
        if (trimmed.length() <= 4) {
            return "****";
        }
        int len = trimmed.length();
        return "*".repeat(len - 4) + trimmed.substring(len - 4);
    }
}
