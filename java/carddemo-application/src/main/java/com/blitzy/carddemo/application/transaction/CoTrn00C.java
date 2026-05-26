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
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.util.Decimals;

/**
 * Java translation of the COBOL {@code COTRN00C} CICS online program
 * ({@code app/cbl/COTRN00C.cbl}) &mdash; the <em>Transaction List</em>
 * screen for transaction {@code CT00}.
 *
 * <h2>Program purpose</h2>
 * Displays a paged listing of the {@code TRANSACT} VSAM KSDS file, showing
 * 10 transaction summaries per page (TRAN-ID, TRAN-ORIG-TS date,
 * TRAN-DESC, TRAN-AMT). The screen supports:
 * <ul>
 *   <li><b>ENTER</b> &mdash; row-selection ('S'/'s' XCTLs to {@code COTRN01C}
 *       for the detail view) or repositioning via the {@code TRNIDIN}
 *       numeric search key, followed by a fresh forward page.</li>
 *   <li><b>PF7</b> &mdash; paginate backward (one page earlier).</li>
 *   <li><b>PF8</b> &mdash; paginate forward (one page later).</li>
 *   <li><b>PF3</b> &mdash; XCTL back to {@code COMEN01C} (main menu).</li>
 *   <li>Other AID keys &mdash; reject with the verbatim
 *       {@code CCDA-MSG-INVALID-KEY} error message from
 *       {@code app/cpy/CSMSG01Y.cpy}.</li>
 * </ul>
 *
 * <h2>Translated paragraphs (COBOL &rarr; Java)</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #handle(CoTrn00Input, CardDemoCommarea, AidKey)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(MutableState)}</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link #processPf7Key(MutableState)}</li>
 *   <li>{@code PROCESS-PF8-KEY} &rarr; {@link #processPf8Key(MutableState)}</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} &rarr; {@link #processPageForward(MutableState, String, boolean)}</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} &rarr; {@link #processPageBackward(MutableState, String)}</li>
 *   <li>{@code POPULATE-TRAN-DATA} &rarr; {@link #populateRow(MutableState, int, TranRecord)}</li>
 *   <li>{@code INITIALIZE-TRAN-DATA} &rarr; {@link #initializeRows(MutableState)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen(MutableState)}</li>
 *   <li>{@code SEND-TRNLST-SCREEN} &rarr; {@link #sendTrnlstScreen(MutableState)}</li>
 *   <li>{@code POPULATE-HEADER-INFO} &rarr; {@link #populateHeaderInfo(MutableState)}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE}, {@code READNEXT-TRANSACT-FILE},
 *       {@code READPREV-TRANSACT-FILE}, {@code ENDBR-TRANSACT-FILE}
 *       &rarr; the streaming primitives in {@code TransactionRepository}.</li>
 * </ul>
 *
 * <h2>Pagination strategy</h2>
 * The COBOL program uses CICS VSAM cursor semantics (STARTBR / READNEXT /
 * READPREV / ENDBR) on the {@code TRANSACT} KSDS, with a separate
 * {@code CDEMO-CT00-INFO} working-storage extension that round-trips through
 * the COMMAREA carrying {@code TRNID-FIRST}, {@code TRNID-LAST},
 * {@code PAGE-NUM}, and {@code NEXT-PAGE-FLG}.
 *
 * <p>The Java translation faithfully preserves these semantics through a
 * different but observationally equivalent mechanism:
 * <ul>
 *   <li>{@link CardDemoCommarea} does not include the
 *       {@code CDEMO-CT00-INFO} extension fields, so pagination state is
 *       carried via the BMS map's own echoed row fields:
 *       {@code TRNID01} (= {@code TRNID-FIRST}) and {@code TRNID10}
 *       (= {@code TRNID-LAST}) on the input record, plus
 *       {@code PAGENUM} (= {@code CDEMO-CT00-PAGE-NUM}).</li>
 *   <li>Forward paging uses
 *       {@link TransactionRepository#streamFrom(String)} with
 *       {@code startTranId = TRNID-LAST}, skipping the boundary record
 *       when the AID is PF8 (mirroring the COBOL "skip current" READNEXT
 *       at lines 285-287 of {@code COTRN00C.cbl}).</li>
 *   <li>Backward paging uses
 *       {@link TransactionRepository#streamSequential()} filtered to
 *       records strictly less than {@code TRNID-FIRST} and collects the
 *       last 10, mirroring COBOL READPREV semantics on a KSDS.</li>
 * </ul>
 *
 * <h2>Outcome model</h2>
 * The program returns one of two {@link Outcome} variants:
 * <ul>
 *   <li>{@link Outcome.SendMap SendMap} &mdash; analogous to COBOL
 *       {@code EXEC CICS SEND MAP / RETURN}: a fully populated
 *       {@link CoTrn00Output} for the renderer plus the updated
 *       {@link CardDemoCommarea}.</li>
 *   <li>{@link Outcome.Xctl Xctl} &mdash; analogous to COBOL
 *       {@code EXEC CICS XCTL}: a target program name plus the updated
 *       {@link CardDemoCommarea}. The caller dispatches the next
 *       program via {@link ProgramRegistry}.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * Instances of {@code CoTrn00C} are immutable after construction (only
 * holding final references to the injected collaborators) and are safe
 * for concurrent use. The mutable per-invocation state lives in a private
 * {@link MutableState} object allocated fresh inside
 * {@link #handle(CoTrn00Input, CardDemoCommarea, AidKey)} so each call is
 * fully isolated.
 *
 * @see CoTrn00Input  for the entry-contract (BMS COTRN0AI) input record
 * @see CoTrn00Output for the entry-contract (BMS COTRN0AO) output record
 * @see TransactionRepository for the port that backs STARTBR/READNEXT/READPREV/ENDBR
 * @see TranRecord for the 350-byte transaction record translated from copybook CVTRA05Y
 */
@CobolProgram(
        value = "COTRN00C",
        sourcePath = "app/cbl/COTRN00C.cbl",
        translationDate = "2025-01-21",
        notes = "Transaction List online CICS program (transaction CT00). "
              + "Paged browsing of TRANSACT KSDS via STARTBR/READNEXT/READPREV/ENDBR; "
              + "10 rows per page; PF7 backward / PF8 forward; row selection 'S'/'s' "
              + "XCTLs to COTRN01C; PF3 XCTLs to COMEN01C. Pagination state "
              + "round-trips via BMS map echoed fields (TRNID01/TRNID10/PAGENUM) "
              + "since CardDemoCommarea does not carry CDEMO-CT00-INFO extension."
)
public final class CoTrn00C {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoTrn00C.class);

    // ============================================================================
    // Program identity constants (mirror COBOL WORKING-STORAGE fields).
    // ============================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'} from COBOL line 33. */
    public static final String PROGRAM_NAME = "COTRN00C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CT00'} from COBOL line 34. */
    public static final String TRANSACTION_ID = "CT00";

    /** {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} from COBOL line 35. */
    public static final String TRANSACT_FILE = "TRANSACT";

    /** Default {@code CDEMO-TO-PROGRAM} when EIBCALEN is 0 (no commarea). */
    public static final String DEFAULT_PREV_PROGRAM = ProgramRegistry.CO_SGN_00C;

    /** PF3 target: main menu. */
    public static final String BACK_PROGRAM = ProgramRegistry.CO_MEN_01C;

    /** Row-selection target: transaction view. */
    public static final String VIEW_PROGRAM = ProgramRegistry.CO_TRN_01C;

    /** Selectable rows per page: 10 (NOT 7 like {@code COCRDLIC}). */
    public static final int ROWS_PER_PAGE = 10;

    // ============================================================================
    // Header constants (mirror CCDA-TITLE01 / CCDA-TITLE02 in CVCRD01Y).
    // ============================================================================

    /** {@code CCDA-TITLE01 PIC X(40) VALUE 'AWS Mainframe Modernization'}. */
    static final String TITLE_01 = "AWS Mainframe Modernization";

    /** {@code CCDA-TITLE02 PIC X(40) VALUE 'CardDemo'} (the screen subtitle). */
    static final String TITLE_02 = "CardDemo";

    // ============================================================================
    // Verbatim COBOL error messages (preserved exactly per AAP §0.7.1).
    // ============================================================================

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} from
     * {@code app/cpy/CSMSG01Y.cpy:L20-L22}, content
     * {@code 'Invalid key pressed. Please see below...         '}.
     * The 40-character meaningful prefix is preserved verbatim;
     * trailing pad is supplied by the BMS encoder.
     */
    static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Verbatim from {@code COTRN00C.cbl:L199} (singular "value", single 'S'). */
    static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** Verbatim from {@code COTRN00C.cbl:L213-L214} (trailing space before "..."). */
    static final String MSG_TRAN_ID_MUST_BE_NUMERIC = "Tran ID must be Numeric ...";

    /** Verbatim from {@code COTRN00C.cbl:L248} (PF7 with PAGE-NUM = 1). */
    static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Verbatim from {@code COTRN00C.cbl:L270} (PF8 with NEXT-PAGE-NO). */
    static final String MSG_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /** Verbatim from {@code COTRN00C.cbl:L608} (STARTBR NOTFND). */
    static final String MSG_AT_TOP_OF_PAGE = "You are at the top of the page...";

    /** Verbatim from {@code COTRN00C.cbl:L642} (READNEXT ENDFILE). */
    static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** Verbatim from {@code COTRN00C.cbl:L676} (READPREV ENDFILE). */
    static final String MSG_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Verbatim from {@code COTRN00C.cbl:L615/L649/L683}
     * (STARTBR/READNEXT/READPREV with an unexpected RESP code).
     * The lowercase 't' in "transaction" is preserved per the COBOL source.
     */
    static final String MSG_LOOKUP_ERROR = "Unable to lookup transaction...";

    // ============================================================================
    // Java time formatters (replace COBOL FUNCTION CURRENT-DATE / substring).
    // ============================================================================

    /** {@code MM/DD/YY} &mdash; format used by both CURDATEO and TDATExxO. */
    private static final DateTimeFormatter DATE_MM_DD_YY =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /** {@code HH:MM:SS} &mdash; format used by CURTIMEO. */
    private static final DateTimeFormatter TIME_HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    // ============================================================================
    // Collaborators (constructor-injected).
    // ============================================================================

    private final TransactionRepository transactionRepository;
    private final ProgramRegistry programRegistry;

    /**
     * Constructs a new {@code CoTrn00C} use case with the required
     * collaborators. Both arguments are mandatory and must be non-{@code null};
     * passing {@code null} for either parameter raises
     * {@link NullPointerException} immediately via
     * {@link Objects#requireNonNull(Object, String)}.
     *
     * @param transactionRepository the port to the {@code TRANSACT} VSAM KSDS
     *                              (or the JDBC adapter at the composition root);
     *                              must not be {@code null}
     * @param programRegistry       the dynamic CICS XCTL/CALL routing facade;
     *                              must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CoTrn00C(TransactionRepository transactionRepository,
                    ProgramRegistry programRegistry) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.programRegistry = Objects.requireNonNull(
                programRegistry, "programRegistry");
    }

    // ============================================================================
    // Outcome sealed interface (return type of handle()).
    // ============================================================================

    /**
     * The result of a single {@link CoTrn00C#handle invocation}.
     *
     * <p>This sealed interface mirrors the two terminal CICS verbs the
     * COBOL program issues: {@code EXEC CICS RETURN WITH COMMAREA}
     * (after {@code SEND MAP}) and {@code EXEC CICS XCTL PROGRAM}. Both
     * variants carry the updated {@link CardDemoCommarea} so the caller
     * can either render the screen or dispatch the next program.
     *
     * <p><b>Exhaustiveness:</b> the {@code permits} clause closes the
     * permit space to exactly {@link SendMap} and {@link Xctl}. The
     * Java 25 compiler enforces this; switch expressions over an
     * {@code Outcome} value must cover both permits with no
     * {@code default} branch.
     */
    public sealed interface Outcome
            permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Equivalent to {@code EXEC CICS SEND MAP}: a fully populated
         * output record and the updated commarea to be persisted on the
         * subsequent {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)}.
         *
         * @param screen   the {@link CoTrn00Output} carrying the 24x80
         *                 screen state (headers, 10 rows, error message)
         * @param commarea the {@link CardDemoCommarea} to round-trip to
         *                 the next invocation of this program
         */
        record SendMap(CoTrn00Output screen, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing the {@code @NonNull}
             * contract on both record components.
             *
             * @throws NullPointerException if {@code screen} or
             *                              {@code commarea} is {@code null}
             */
            public SendMap {
                Objects.requireNonNull(screen, "screen");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * Equivalent to {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}:
         * a target program name and the updated commarea. The caller
         * dispatches via {@link ProgramRegistry#invoke(String, CardDemoCommarea)}.
         *
         * @param targetProgram the COBOL program-id (e.g. {@code "COTRN01C"},
         *                      {@code "COMEN01C"}, {@code "COSGN00C"})
         *                      to which control transfers
         * @param commarea      the {@link CardDemoCommarea} to pass to the
         *                      target program
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing the {@code @NonNull}
             * contract and rejecting blank target-program names.
             *
             * @throws NullPointerException     if either component is {@code null}
             * @throws IllegalArgumentException if {@code targetProgram} is blank
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

    // ============================================================================
    // Public entry point: handle() — translation of COBOL MAIN-PARA.
    // ============================================================================

    /**
     * Executes one round-trip of the {@code COTRN00C} CICS online program
     * &mdash; the Java translation of {@code MAIN-PARA} at
     * {@code app/cbl/COTRN00C.cbl:L93-L143}.
     *
     * <p>The method dispatches on three coordinated pieces of context:
     * <ol>
     *   <li>The {@code commarea} (analogous to CICS {@code DFHCOMMAREA}):
     *       a {@code null} value means {@code EIBCALEN = 0} (first
     *       attachment of the transaction), causing an immediate XCTL to
     *       {@link #DEFAULT_PREV_PROGRAM} (COSGN00C).</li>
     *   <li>The {@link PgmContext} stored in the commarea
     *       (analogous to {@code CDEMO-PGM-CONTEXT}):
     *       {@link PgmContext#ENTER} means first time through &mdash; the
     *       program acts as if ENTER was pressed with an empty input,
     *       producing the first page of transactions and switching the
     *       commarea to {@link PgmContext#REENTER}.</li>
     *   <li>The {@code aidKey} (analogous to CICS {@code EIBAID}): on
     *       re-entry, the AID key is pattern-matched against the closed
     *       set of {@link AidKey} permits to select the corresponding
     *       paragraph.</li>
     * </ol>
     *
     * <p>The switch over {@code aidKey} is <b>exhaustive</b> by virtue of
     * the sealed {@link AidKey} type: the Java 25 compiler refuses to
     * compile if any permit is omitted, and the switch declares
     * <b>no</b> {@code default} branch. This is the safety guarantee
     * mandated by AAP &sect;0.6.2 and &sect;0.6.7.
     *
     * @param input    the BMS-decoded input record. May be the result of
     *                 {@link CoTrn00Input#empty()} on first entry. Must
     *                 not be {@code null}; pass {@code CoTrn00Input.empty()}
     *                 to represent an absent input.
     * @param commarea the CICS commarea round-tripped from the previous
     *                 invocation, or {@code null} if {@code EIBCALEN = 0}
     *                 (first attachment of the transaction)
     * @param aidKey   the 3270 attention identifier the user pressed.
     *                 Must not be {@code null}; pass {@link AidKey#ENTER}
     *                 on first entry.
     * @return either an {@link Outcome.SendMap} carrying the populated
     *         {@link CoTrn00Output} for the renderer, or an
     *         {@link Outcome.Xctl} carrying the next program-id to
     *         dispatch via the {@link ProgramRegistry}
     * @throws NullPointerException if {@code input} or {@code aidKey} is
     *                              {@code null}
     */
    public Outcome handle(CoTrn00Input input, CardDemoCommarea commarea, AidKey aidKey) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aidKey, "aidKey");

        // MAIN-PARA line 99-104: initialize 88-level flags and clear WS-MESSAGE.
        MutableState state = new MutableState();
        state.input = input;
        state.commarea = commarea;
        state.aidKey = aidKey;

        // COBOL line 106-110: IF EIBCALEN = 0 → XCTL to COSGN00C
        if (commarea == null) {
            CardDemoCommarea outbound = buildXctlCommarea(
                    CardDemoCommarea.empty(), DEFAULT_PREV_PROGRAM);
            return new Outcome.Xctl(DEFAULT_PREV_PROGRAM, outbound);
        }

        // Restore pagination state from the BMS-echoed input fields.
        // The COBOL CDEMO-CT00-INFO extension is NOT in CardDemoCommarea, so
        // the canonical sources for the prior page boundaries are the BMS
        // map's own echoed row fields and PAGENUM display.
        state.tranIdFirst = trimToEmpty(input.trnId01());
        state.tranIdLast = trimToEmpty(input.trnId10());
        state.pageNum = parsePageNum(input.pageNum());

        // COBOL line 111-141: IF NOT CDEMO-PGM-REENTER → first-time entry;
        // ELSE → process the AID key.
        PgmContext context = commarea.cdemoGeneralInfo().pgmContext();
        if (context.isEnter()) {
            // First time through: clear input to defaults, switch context to
            // REENTER for the next round-trip, and behave as if ENTER was
            // pressed (showing the first page).
            state.commarea = withPgmContext(commarea, PgmContext.REENTER);
            state.input = CoTrn00Input.empty();
            state.tranIdFirst = "";
            state.tranIdLast = "";
            state.pageNum = 0;
            return processEnterKey(state);
        }

        // Re-entry: dispatch on the AID key using an exhaustive sealed switch.
        // NO default branch — exhaustiveness is the safety guarantee per AAP
        // §0.6.2 and §0.6.7. The Java 25 compiler enforces every permit is
        // covered.
        return switch (aidKey) {
            case AidKey.Enter e         -> processEnterKey(state);
            case AidKey.PfKey03 pf3     -> handlePf3Back(state);
            case AidKey.PfKey07 pf7     -> processPf7Key(state);
            case AidKey.PfKey08 pf8     -> processPf8Key(state);
            case AidKey.Clear c         -> invalidKey(state);
            case AidKey.Pa1 p1          -> invalidKey(state);
            case AidKey.Pa2 p2          -> invalidKey(state);
            case AidKey.PfKey01 pf1     -> invalidKey(state);
            case AidKey.PfKey02 pf2     -> invalidKey(state);
            case AidKey.PfKey04 pf4     -> invalidKey(state);
            case AidKey.PfKey05 pf5     -> invalidKey(state);
            case AidKey.PfKey06 pf6     -> invalidKey(state);
            case AidKey.PfKey09 pf9     -> invalidKey(state);
            case AidKey.PfKey10 pf10    -> invalidKey(state);
            case AidKey.PfKey11 pf11    -> invalidKey(state);
            case AidKey.PfKey12 pf12    -> invalidKey(state);
        };
    }

    // ============================================================================
    // Paragraph translations (private methods, one per COBOL paragraph).
    // ============================================================================

    /**
     * Java translation of the {@code PROCESS-ENTER-KEY} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L150-L227}.
     *
     * <p>Two-phase logic:
     * <ol>
     *   <li><b>Row selection scan</b>: the COBOL {@code EVALUATE TRUE}
     *       walks SEL0001I..SEL0010I top-to-bottom and stops at the
     *       first non-blank, non-LOW-VALUES selection. If that selection
     *       is {@code 'S'} or {@code 's'}, an XCTL to {@link #VIEW_PROGRAM}
     *       (COTRN01C) is issued carrying the selected TRAN-ID; otherwise
     *       the {@link #MSG_INVALID_SELECTION} message is set.</li>
     *   <li><b>TRNIDIN repositioning</b>: if no row was selected, the
     *       {@code TRNIDINI} field is validated as numeric. If non-numeric,
     *       the {@link #MSG_TRAN_ID_MUST_BE_NUMERIC} message is set and
     *       the screen is re-sent. Otherwise PROCESS-PAGE-FORWARD is
     *       invoked with PAGE-NUM reset to 0.</li>
     * </ol>
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome} (XCTL on row-selection, SendMap on
     *         repositioning / error)
     */
    private Outcome processEnterKey(MutableState state) {
        CoTrn00Input in = state.input;

        // ---- Row-selection scan (COBOL line 151-181) -----------------------
        int selectedRow = in.firstSelectedRow();
        if (selectedRow > 0) {
            String selFlag = selFlagAtRow(in, selectedRow);
            String selectedTranId = trnIdAtRow(in, selectedRow);
            if ("S".equals(selFlag) || "s".equals(selFlag)) {
                // COBOL line 188-194: XCTL to COTRN01C with commarea
                // configured for the detail-view callee.
                LOGGER.debug("CoTrn00C: row {} selected with 'S', "
                                + "XCTL'ing to {} for tranId={}",
                        selectedRow, VIEW_PROGRAM, selectedTranId);
                CardDemoCommarea outbound = buildXctlCommarea(
                        state.commarea, VIEW_PROGRAM);
                if (programRegistry.isRegistered(VIEW_PROGRAM)) {
                    LOGGER.trace("CoTrn00C: ProgramRegistry has {} registered",
                            VIEW_PROGRAM);
                }
                return new Outcome.Xctl(VIEW_PROGRAM, outbound);
            }
            // COBOL line 196-202: selection is not 'S'/'s' — set error
            // message; PERFORM SEND-TRNLST-SCREEN was commented out, so
            // we fall through to the TRNIDINI check below.
            state.wsMessage = MSG_INVALID_SELECTION;
        }

        // ---- TRNIDINI repositioning (COBOL line 207-223) -------------------
        String trnIdIn = trimToEmpty(in.trnIdIn());
        String startKey = "";
        if (trnIdIn.isEmpty()) {
            // COBOL line 207-208: MOVE LOW-VALUES TO TRAN-ID → start at top.
            startKey = "";
        } else if (isNumeric(trnIdIn)) {
            // COBOL line 210-211: MOVE TRNIDINI TO TRAN-ID → start at exact key.
            startKey = trnIdIn;
        } else {
            // COBOL line 212-222: non-numeric TRNIDINI → set message + send.
            state.wsMessage = MSG_TRAN_ID_MUST_BE_NUMERIC;
            state.errFlgOn = true;
            return sendTrnlstScreen(state);
        }

        // COBOL line 225-227: PAGE-NUM <- 0; PROCESS-PAGE-FORWARD.
        state.pageNum = 0;
        return processPageForward(state, startKey, false);
    }

    /**
     * Java translation of the {@code PROCESS-PF7-KEY} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L233-L255}: paginate backward by one page.
     *
     * <p>If the prior {@code CDEMO-CT00-TRNID-FIRST} is blank, the start
     * key is set to LOW-VALUES (top of file). The {@code NEXT-PAGE} flag
     * is forced to YES (COBOL line 244) because going backward leaves a
     * forward page available. If {@code PAGE-NUM} is already 1, no actual
     * paging happens &mdash; only the verbatim "already at top" message
     * is set and the screen is re-sent without ERASE.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying either the
     *         previous-page rows or the "already at top" error message
     */
    private Outcome processPf7Key(MutableState state) {
        // COBOL line 235-239: set start key from TRNID-FIRST (or LOW-VALUES).
        String startKey = state.tranIdFirst.isBlank() ? "" : state.tranIdFirst;

        // COBOL line 244: SET NEXT-PAGE-YES TO TRUE
        state.nextPageYes = true;

        // COBOL line 245-254: IF PAGE-NUM > 1 → backward page; ELSE error.
        if (state.pageNum > 1) {
            return processPageBackward(state, startKey);
        }
        state.wsMessage = MSG_ALREADY_AT_TOP;
        state.sendEraseYes = false;
        return sendTrnlstScreen(state);
    }

    /**
     * Java translation of the {@code PROCESS-PF8-KEY} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L260-L277}: paginate forward by one page.
     *
     * <p>If {@code NEXT-PAGE-YES} (set by the prior PROCESS-PAGE-FORWARD),
     * the forward page is computed starting from {@code TRNID-LAST}
     * (skipping the boundary record). Otherwise the verbatim
     * "already at bottom" message is set and the screen is re-sent
     * without ERASE.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying either the next-page
     *         rows or the "already at bottom" error message
     */
    private Outcome processPf8Key(MutableState state) {
        // COBOL line 263-267: set start key from TRNID-LAST (or HIGH-VALUES).
        String startKey = state.tranIdLast.isBlank()
                ? "\uFFFF".repeat(16)   // HIGH-VALUES sentinel: no record matches
                : state.tranIdLast;

        // COBOL line 271-276: IF NEXT-PAGE-YES → forward page; ELSE error.
        // NEXT-PAGE-YES was the result of the previous PROCESS-PAGE-FORWARD;
        // since the commarea cannot carry it across CICS round-trips here,
        // we derive it from the presence of a non-blank TRNID-LAST in the
        // input (which implies the previous page was full and forward
        // browsing is meaningful).
        if (state.tranIdLast.isBlank()) {
            state.wsMessage = MSG_ALREADY_AT_BOTTOM;
            state.sendEraseYes = false;
            return sendTrnlstScreen(state);
        }
        return processPageForward(state, startKey, true);
    }

    /**
     * Java translation of the {@code PROCESS-PAGE-FORWARD} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L281-L327}.
     *
     * <p>Uses {@link TransactionRepository#streamFrom(String)} to obtain
     * an ordered cursor positioned at-or-after the supplied
     * {@code startKey}. The {@code skipFirst} parameter selects between
     * the two COBOL behaviors at line 285-287:
     * <ul>
     *   <li>{@code skipFirst=true} (PF8 path) &mdash; one extra READNEXT
     *       is issued before the 10-row collection loop, skipping past
     *       the boundary record from the previous page.</li>
     *   <li>{@code skipFirst=false} (ENTER path) &mdash; the first record
     *       at-or-after {@code startKey} is collected.</li>
     * </ul>
     *
     * <p>After collecting up to 10 records, an additional {@code hasNext}
     * peek determines whether {@code NEXT-PAGE-YES} should be set (COBOL
     * lines 307-313). On any I/O exception, the verbatim
     * {@link #MSG_LOOKUP_ERROR} is set per COBOL line 615.
     *
     * @param state     the mutable per-invocation state
     * @param startKey  the positioning key (empty string means top of file)
     * @param skipFirst {@code true} when called from PF8 to mirror the
     *                  COBOL "skip current" READNEXT
     * @return the {@link Outcome.SendMap} carrying the forward page or
     *         an error
     */
    private Outcome processPageForward(MutableState state, String startKey, boolean skipFirst) {
        // PERFORM STARTBR-TRANSACT-FILE (COBOL line 283).
        // streamFrom() encapsulates STARTBR semantics: returns an ordered
        // stream of records with TRAN-ID >= startKey.
        initializeRows(state);
        state.rowsPopulated = 0;

        // Java translation of STARTBR + READNEXT loop using try-with-resources
        // to guarantee ENDBR-TRANSACT-FILE (the close()) is always invoked.
        try (Stream<TranRecord> stream =
                     transactionRepository.streamFrom(startKey == null ? "" : startKey)) {

            Iterator<TranRecord> iter = stream.iterator();

            // COBOL line 285-287: skip the boundary record on PF8.
            if (skipFirst && iter.hasNext()) {
                iter.next();
            }

            // COBOL line 296-303: PERFORM UNTIL WS-IDX >= 11 OR EOF OR ERR
            int idx = 0; // 0-based; COBOL uses 1..10
            while (idx < ROWS_PER_PAGE && iter.hasNext()) {
                TranRecord tr = iter.next();
                populateRow(state, idx + 1, tr);
                idx++;
            }
            state.rowsPopulated = idx;

            // COBOL line 305-322: page-num bookkeeping and NEXT-PAGE flag.
            //
            // Behavioral mapping (verbatim COBOL semantics):
            //
            //   * Full page (idx == 10) + at least one more record available:
            //     PAGE-NUM++; NEXT-PAGE-YES; no message. The COBOL +1 probe
            //     READNEXT succeeds, mirroring DFHRESP(NORMAL).
            //
            //   * Full page (idx == 10) + no more records: PAGE-NUM++;
            //     NEXT-PAGE-NO; WS-MESSAGE = "You have reached the bottom of
            //     the page..." per the COBOL READNEXT-TRANSACT-FILE ENDFILE
            //     handler at line 642 (which fires during the +1 probe).
            //
            //   * Partial page (0 < idx < 10): PAGE-NUM++; NEXT-PAGE-NO;
            //     WS-MESSAGE set by the same READNEXT ENDFILE handler that
            //     terminated the inner collection loop.
            //
            //   * Zero rows (idx == 0): PAGE-NUM unchanged; NEXT-PAGE-NO.
            //     On the skipFirst (PF8) path, the boundary record existed
            //     but nothing follows it → bottom-of-page message. On the
            //     !skipFirst (ENTER) path, STARTBR yielded no records at
            //     all → "You are at the top of the page..." per the
            //     STARTBR NOTFND handler at COBOL line 608.
            if (idx == ROWS_PER_PAGE && iter.hasNext()) {
                state.pageNum = state.pageNum + 1;
                state.nextPageYes = true;
            } else if (idx == ROWS_PER_PAGE) {
                // Full page, but the +1 probe hit ENDFILE.
                state.pageNum = state.pageNum + 1;
                state.nextPageYes = false;
                state.wsMessage = MSG_REACHED_BOTTOM;
            } else if (idx > 0) {
                // Partial page — READNEXT hit ENDFILE during the collection
                // loop, which sets the bottom-of-page message.
                state.pageNum = state.pageNum + 1;
                state.nextPageYes = false;
                state.wsMessage = MSG_REACHED_BOTTOM;
            } else {
                // No records found at all.
                state.transactEof = true;
                state.nextPageYes = false;
                if (skipFirst) {
                    // PF8 path: tried to advance past the bottom row.
                    state.wsMessage = MSG_REACHED_BOTTOM;
                } else {
                    // ENTER path: STARTBR yielded nothing → COBOL NOTFND.
                    state.wsMessage = MSG_AT_TOP_OF_PAGE;
                }
            }
        } catch (RuntimeException re) {
            // OTHER branch from STARTBR/READNEXT (COBOL line 612-617, 645-651).
            LOGGER.warn(
                    "CoTrn00C: TRANSACT browse failed for startKey={} skipFirst={} RESP={}",
                    startKey, skipFirst, re.toString());
            state.errFlgOn = true;
            state.wsMessage = MSG_LOOKUP_ERROR;
        }

        // PERFORM ENDBR-TRANSACT-FILE happened implicitly via try-with-resources.
        return sendTrnlstScreen(state);
    }

    /**
     * Java translation of the {@code PROCESS-PAGE-BACKWARD} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L335-L378}.
     *
     * <p>{@link TransactionRepository} does not expose a READPREV primitive
     * (the COBOL VSAM cursor's reverse iteration), so this method collects
     * a window of records strictly less than {@code endKey} and takes the
     * last {@link #ROWS_PER_PAGE} in ascending order. This is
     * observationally equivalent to STARTBR at {@code endKey} + 10
     * READPREVs followed by a re-sort into display (ascending) order.
     *
     * <p>The COBOL "skip current" READPREV on line 339-341 is mirrored by
     * the strict {@code <} comparison against {@code endKey} (rather than
     * {@code <=}), which excludes the boundary record from the previous
     * page.
     *
     * @param state  the mutable per-invocation state
     * @param endKey the positioning key (the prior {@code TRNID-FIRST})
     * @return the {@link Outcome.SendMap} carrying the previous-page rows
     *         or an error
     */
    private Outcome processPageBackward(MutableState state, String endKey) {
        initializeRows(state);
        state.rowsPopulated = 0;

        try (Stream<TranRecord> stream = transactionRepository.streamSequential()) {

            // Collect at most ROWS_PER_PAGE records with tranId strictly
            // less than endKey. A bounded ring buffer (ArrayDeque) avoids
            // materializing the entire TRANSACT file when only the last 10
            // records before endKey are required.
            Deque<TranRecord> window = new ArrayDeque<>(ROWS_PER_PAGE);
            Iterator<TranRecord> iter = stream.iterator();
            while (iter.hasNext()) {
                TranRecord tr = iter.next();
                if (compareTranId(tr.tranId(), endKey) >= 0) {
                    // We've reached endKey; stop scanning.
                    break;
                }
                if (window.size() == ROWS_PER_PAGE) {
                    window.removeFirst();
                }
                window.addLast(tr);
            }

            int idx = 0;
            for (TranRecord tr : window) {
                populateRow(state, idx + 1, tr);
                idx++;
            }
            state.rowsPopulated = idx;

            // COBOL line 359-369: adjust PAGE-NUM (decrement by 1, floor 1)
            // and set NEXT-PAGE-YES because we paged backward.
            if (idx > 0) {
                state.pageNum = Math.max(1L, state.pageNum - 1);
                state.nextPageYes = true;
            } else {
                state.transactEof = true;
                state.wsMessage = MSG_REACHED_TOP;
            }
        } catch (RuntimeException re) {
            // OTHER branch from STARTBR/READPREV (COBOL line 678-684).
            LOGGER.warn(
                    "CoTrn00C: TRANSACT backward browse failed for endKey={} RESP={}",
                    endKey, re.toString());
            state.errFlgOn = true;
            state.wsMessage = MSG_LOOKUP_ERROR;
        }

        return sendTrnlstScreen(state);
    }

    /**
     * Java translation of the PF3 branch of {@code MAIN-PARA}'s
     * {@code EVALUATE EIBAID} at {@code app/cbl/COTRN00C.cbl:L122-L124}:
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM; PERFORM RETURN-TO-PREV-SCREEN}.
     *
     * @param state the mutable per-invocation state
     * @return an {@link Outcome.Xctl} targeting {@link #BACK_PROGRAM}
     */
    private Outcome handlePf3Back(MutableState state) {
        state.commarea = withToProgram(state.commarea, BACK_PROGRAM);
        return returnToPrevScreen(state);
    }

    /**
     * Java translation of the {@code WHEN OTHER} branch of the
     * {@code EVALUATE EIBAID} at {@code app/cbl/COTRN00C.cbl:L129-L133}:
     * unsupported AID key &rarr; set {@link #MSG_INVALID_KEY}, raise the
     * error flag, move -1 to TRNIDINL (cursor positioning), and re-send
     * the screen.
     *
     * @param state the mutable per-invocation state
     * @return an {@link Outcome.SendMap} carrying the error
     */
    private Outcome invalidKey(MutableState state) {
        state.errFlgOn = true;
        state.wsMessage = MSG_INVALID_KEY;
        // Note: "MOVE -1 TO TRNIDINL" in COBOL positions the 3270 cursor on
        // the TRNIDIN input field. In the Java output, no equivalent field
        // exists on CoTrn00Output (cursor positioning is a renderer concern);
        // the verbatim error message in ERRMSGO is the observable outcome.
        return sendTrnlstScreen(state);
    }

    /**
     * Java translation of the {@code RETURN-TO-PREV-SCREEN} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L508-L524}.
     *
     * <p>Defaults {@code CDEMO-TO-PROGRAM} to {@link #DEFAULT_PREV_PROGRAM}
     * if blank, records the program identity in {@code CDEMO-FROM-TRANID}
     * and {@code CDEMO-FROM-PROGRAM}, resets {@code CDEMO-PGM-CONTEXT}
     * to {@link PgmContext#ENTER}, and emits {@code EXEC CICS XCTL
     * PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(...)}.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.Xctl} carrying the target program-id
     */
    private Outcome returnToPrevScreen(MutableState state) {
        String target = state.commarea.cdemoGeneralInfo().toProgram().strip();
        if (target.isEmpty()) {
            target = DEFAULT_PREV_PROGRAM;
        }
        CardDemoCommarea outbound = buildXctlCommarea(state.commarea, target);
        LOGGER.info("CoTrn00C: XCTL to {} (from {} TRANID={})",
                target, PROGRAM_NAME, TRANSACTION_ID);
        return new Outcome.Xctl(target, outbound);
    }

    /**
     * Java translation of the {@code SEND-TRNLST-SCREEN} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L530-L555}.
     *
     * <p>Calls {@link #populateHeaderInfo(MutableState)} to set the
     * standard header fields (titles, transaction id, program name,
     * current date / time), then builds the {@link CoTrn00Output} record
     * carrying the 10-row staging buffer and the verbatim error message.
     *
     * <p>The COBOL {@code SEND-ERASE-YES} vs {@code SEND-ERASE-NO}
     * distinction has no direct counterpart in the Java {@link Outcome}
     * model: both modes resolve to the same {@link Outcome.SendMap} and
     * the renderer (BMS encoder) interprets the erase mode separately if
     * needed. This is a documented translation deviation.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} with the populated output
     */
    private Outcome sendTrnlstScreen(MutableState state) {
        HeaderFields hdr = buildHeaderFields();
        // PAGENUMO format: COBOL CDEMO-CT00-PAGE-NUM PIC 9(8). When zero
        // (first entry), display blanks; otherwise zero-pad to 8 digits.
        String pageNumStr = state.pageNum == 0L
                ? "        "
                : String.format(Locale.ROOT, "%08d", state.pageNum);

        // COBOL line 326: MOVE SPACE TO TRNIDINO at end of forward page
        // (clear the search input on the redrawn screen). Otherwise echo
        // the input value. Our caller already cleared input on first entry,
        // so faithfully echo trnIdIn here.
        String trnIdInOut = orEmpty(state.input.trnIdIn());

        CoTrn00Output screen = new CoTrn00Output(
                // Header (8)
                TRANSACTION_ID,    // trnName
                TITLE_01,          // title01
                hdr.curDate(),     // curDate
                PROGRAM_NAME,      // pgmName
                TITLE_02,          // title02
                hdr.curTime(),     // curTime
                pageNumStr,        // pageNum
                trnIdInOut,        // trnIdIn echo
                // Row 1 (5)
                "", state.rowTranId[0], state.rowDate[0], state.rowDesc[0], state.rowAmt[0],
                // Row 2 (5)
                "", state.rowTranId[1], state.rowDate[1], state.rowDesc[1], state.rowAmt[1],
                // Row 3 (5)
                "", state.rowTranId[2], state.rowDate[2], state.rowDesc[2], state.rowAmt[2],
                // Row 4 (5)
                "", state.rowTranId[3], state.rowDate[3], state.rowDesc[3], state.rowAmt[3],
                // Row 5 (5)
                "", state.rowTranId[4], state.rowDate[4], state.rowDesc[4], state.rowAmt[4],
                // Row 6 (5)
                "", state.rowTranId[5], state.rowDate[5], state.rowDesc[5], state.rowAmt[5],
                // Row 7 (5)
                "", state.rowTranId[6], state.rowDate[6], state.rowDesc[6], state.rowAmt[6],
                // Row 8 (5)
                "", state.rowTranId[7], state.rowDate[7], state.rowDesc[7], state.rowAmt[7],
                // Row 9 (5)
                "", state.rowTranId[8], state.rowDate[8], state.rowDesc[8], state.rowAmt[8],
                // Row 10 (5)
                "", state.rowTranId[9], state.rowDate[9], state.rowDesc[9], state.rowAmt[9],
                // Error message
                state.wsMessage);

        return new Outcome.SendMap(screen, state.commarea);
    }

    /**
     * Java translation of the {@code POPULATE-HEADER-INFO} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L560-L588}. Captures the current date /
     * time once per send to ensure all header fields share the same
     * snapshot. Returns a small immutable carrier of the formatted date
     * and time strings.
     *
     * @param state the mutable per-invocation state (currently unused but
     *              kept on the signature for future extensions such as
     *              clock injection)
     * @return a {@link HeaderFields} carrier (always non-{@code null})
     */
    private HeaderFields populateHeaderInfo(MutableState state) {
        return buildHeaderFields();
    }

    /**
     * Java translation of the {@code POPULATE-TRAN-DATA} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L382-L496}.
     *
     * <p>Stages one row's worth of display fields into the
     * {@link MutableState} parallel arrays. For row 1, also updates
     * {@link MutableState#tranIdFirst}; for row 10, also updates
     * {@link MutableState#tranIdLast} &mdash; these correspond to the
     * COBOL {@code CDEMO-CT00-TRNID-FIRST} and {@code TRNID-LAST} fields
     * that drive backward / forward paging.
     *
     * <p>The COBOL date extraction logic at lines 386-389 takes positions
     * {@code TRAN-ORIG-TS(3:2)} as YY, then MM and DD slices, producing
     * MM/DD/YY. In Java, the {@code tranOrigTs} field is already a
     * {@link LocalDateTime}, so the equivalent transformation is
     * {@code DateTimeFormatter.ofPattern("MM/dd/yy")}.
     *
     * @param state the mutable per-invocation state
     * @param idx   the 1-based row position (1..{@value #ROWS_PER_PAGE})
     * @param tr    the {@link TranRecord} to render into the row
     */
    private static void populateRow(MutableState state, int idx, TranRecord tr) {
        if (idx < 1 || idx > ROWS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "row index out of range: " + idx);
        }
        int i = idx - 1;
        // tranId: TRAN-ID PIC X(16) → TRNIDxxI PIC X(16) — same width, no truncation.
        state.rowTranId[i] = orEmpty(tr.tranId());
        state.rowDate[i] = formatTranDate(tr.tranOrigTs());
        // tranDesc: TRAN-DESC PIC X(100) → TDESCxxI PIC X(26) on the screen.
        // COBOL MOVE truncates the sending field on the right when the receiving
        // field is shorter; replicate by taking the first 26 characters. This
        // preserves byte-for-byte fidelity with the COBOL output.
        state.rowDesc[i] = truncateTo(orEmpty(tr.tranDesc()), BMS_TDESC_WIDTH);
        state.rowAmt[i] = formatTranAmount(tr.tranAmt());
        if (idx == 1) {
            // COBOL line 392-394: also MOVE TRAN-ID to CDEMO-CT00-TRNID-FIRST.
            state.tranIdFirst = orEmpty(tr.tranId());
        }
        if (idx == ROWS_PER_PAGE) {
            // COBOL line 481-484: also MOVE TRAN-ID to CDEMO-CT00-TRNID-LAST.
            state.tranIdLast = orEmpty(tr.tranId());
        }
    }

    /**
     * Width of the {@code TDESCxxI} fields on the COTRN00 BMS screen
     * ({@code PIC X(26)}). Used by {@link #populateRow} to truncate the
     * 100-character {@code TRAN-DESC} sending field on the right (COBOL
     * MOVE semantics) when staging it into the {@link CoTrn00Output} row.
     */
    private static final int BMS_TDESC_WIDTH = 26;

    /**
     * Truncates {@code value} to at most {@code max} characters, taking the
     * leading slice. Returns the input unchanged when already short enough.
     * Mirrors COBOL {@code MOVE} semantics for unsigned alphanumeric fields
     * where a longer sending field is truncated on the right.
     *
     * @param value the string to truncate (never {@code null}; callers pass
     *              the result of {@link #orEmpty(String)})
     * @param max   the maximum length (must be {@code >= 0})
     * @return either {@code value} or {@code value.substring(0, max)}
     */
    private static String truncateTo(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * Java translation of the {@code INITIALIZE-TRAN-DATA} paragraph at
     * {@code app/cbl/COTRN00C.cbl:L500-L504}. Clears all 10 row slots to
     * empty strings (equivalent to COBOL {@code MOVE SPACES} to each
     * field on the output map).
     *
     * @param state the mutable per-invocation state to clear
     */
    private static void initializeRows(MutableState state) {
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            state.rowTranId[i] = "";
            state.rowDate[i] = "";
            state.rowDesc[i] = "";
            state.rowAmt[i] = "";
        }
    }

    /**
     * Carrier for the header date/time pair produced by
     * {@link #buildHeaderFields()}.
     */
    private record HeaderFields(String curDate, String curTime) {
    }

    /**
     * Builds the common header fields (CURDATEO / CURTIMEO) using the
     * current system clock. Centralized so both {@link #sendTrnlstScreen}
     * and {@link #populateHeaderInfo} share the formatting logic.
     *
     * @return a fresh {@link HeaderFields} carrying MM/DD/YY date and
     *         HH:MM:SS time
     */
    private static HeaderFields buildHeaderFields() {
        LocalDateTime now = LocalDateTime.now();
        return new HeaderFields(
                DATE_MM_DD_YY.format(now),
                TIME_HH_MM_SS.format(now));
    }

    // ============================================================================
    // Helper methods (date, amount, comparison, validation utilities).
    // ============================================================================

    /**
     * Formats a {@link LocalDateTime} as the COBOL MM/DD/YY date display
     * format used for TDATExxO row fields and CURDATEO.
     *
     * <p>Equivalent to the COBOL extraction at {@code COTRN00C.cbl:L386-L389}:
     * <pre>
     *   MOVE WS-TIMESTAMP-DT-YYYY(3:2) TO WS-CURDATE-YY  (last 2 digits of year)
     *   MOVE WS-TIMESTAMP-DT-MM        TO WS-CURDATE-MM
     *   MOVE WS-TIMESTAMP-DT-DD        TO WS-CURDATE-DD
     *   MOVE WS-CURDATE-MM-DD-YY       TO WS-TRAN-DATE
     * </pre>
     *
     * <p>A {@code null} input (an all-spaces TRAN-ORIG-TS sentinel) is
     * rendered as the COBOL initial value {@code "00/00/00"} per the
     * working-storage default at {@code COTRN00C.cbl:L62}.
     *
     * @param ts the timestamp to format, may be {@code null}
     * @return the MM/DD/YY representation (8 chars, never {@code null})
     */
    static String formatTranDate(LocalDateTime ts) {
        if (ts == null) {
            return "00/00/00";
        }
        return DATE_MM_DD_YY.format(ts);
    }

    /**
     * Formats a {@link BigDecimal} amount as the COBOL
     * {@code WS-TRAN-AMT PIC +99999999.99} display format
     * (12 characters: sign + 8 digits + decimal + 2 digits).
     *
     * <p>Per AAP &sect;0.6.1, scale normalization routes through
     * {@link Decimals#scaled(BigDecimal, int, RoundingMode)} with
     * {@link Decimals#DEFAULT_MONETARY_SCALE} and
     * {@link Decimals#ROUNDED_MODE} (banker's rounding). High-order
     * overflow (values whose integer part exceeds 8 digits) is truncated
     * by taking modulo 100,000,000 &mdash; mirroring the COBOL implicit
     * truncation when a {@code PIC S9(09)V99} source value is moved to
     * a {@code PIC +99999999.99} target with only 8 integer digits.
     *
     * @param amt the amount to format, may be {@code null} (renders as
     *            {@code "+00000000.00"})
     * @return the 12-character formatted string (never {@code null})
     */
    static String formatTranAmount(BigDecimal amt) {
        if (amt == null) {
            return "+00000000.00";
        }
        BigDecimal scaled = Decimals.scaled(
                amt,
                Decimals.DEFAULT_MONETARY_SCALE,
                Decimals.ROUNDED_MODE);
        char sign = scaled.signum() < 0 ? '-' : '+';
        BigDecimal abs = scaled.abs();
        // Extract integer and fractional parts via string manipulation to
        // avoid long-overflow on extreme inputs (preserves COBOL truncation
        // semantics by taking the rightmost 8 digits of the integer part).
        String plain = abs.toPlainString();
        int dot = plain.indexOf('.');
        String intPart;
        String fracPart;
        if (dot < 0) {
            intPart = plain;
            fracPart = "00";
        } else {
            intPart = plain.substring(0, dot);
            fracPart = plain.substring(dot + 1);
        }
        // Normalize fractional part to exactly 2 digits.
        if (fracPart.length() < 2) {
            fracPart = fracPart + "0".repeat(2 - fracPart.length());
        } else if (fracPart.length() > 2) {
            fracPart = fracPart.substring(0, 2);
        }
        // Normalize integer part to exactly 8 digits via high-order truncation
        // or zero-padding (COBOL implicit MOVE truncation semantics).
        if (intPart.length() > 8) {
            intPart = intPart.substring(intPart.length() - 8);
        } else if (intPart.length() < 8) {
            intPart = "0".repeat(8 - intPart.length()) + intPart;
        }
        return sign + intPart + "." + fracPart;
    }

    /**
     * Returns {@code true} if {@code s} is a non-empty string consisting
     * entirely of ASCII decimal digits {@code '0'..'9'}. Translation of
     * the COBOL {@code IS NUMERIC} class condition at
     * {@code COTRN00C.cbl:L210}.
     *
     * @param s the string to test, may be {@code null}
     * @return {@code true} iff {@code s} is non-{@code null}, non-empty,
     *         and every character is a digit
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
     * Parses the 8-character {@code PAGENUMI} input field as a long. Blank
     * or non-numeric values default to 0. Used to restore
     * {@code CDEMO-CT00-PAGE-NUM} from the BMS-echoed input.
     *
     * @param s the input page-number string, may be {@code null}
     * @return the parsed page number, or {@code 0} on blank/invalid input
     */
    static long parsePageNum(String s) {
        if (s == null) {
            return 0L;
        }
        String trimmed = s.strip();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException nfe) {
            return 0L;
        }
    }

    /**
     * Lexicographic comparison of two TRAN-ID strings. {@code null} values
     * are coerced to empty strings to mirror COBOL's behavior of treating
     * uninitialized PIC X fields as SPACES (which sort before any
     * printable character).
     *
     * @param a the left-hand TRAN-ID, may be {@code null}
     * @param b the right-hand TRAN-ID, may be {@code null}
     * @return a negative, zero, or positive value per {@link Comparable}
     */
    static int compareTranId(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.compareTo(bb);
    }

    /**
     * Returns {@code s} with leading/trailing whitespace stripped, or the
     * empty string if {@code s} is {@code null} or all whitespace.
     *
     * @param s the input, may be {@code null}
     * @return the stripped value (never {@code null})
     */
    static String trimToEmpty(String s) {
        if (s == null) {
            return "";
        }
        return s.strip();
    }

    /**
     * Returns {@code s} or the empty string if {@code s} is {@code null}.
     * Used to satisfy the {@link CoTrn00Output} canonical constructor's
     * non-{@code null} expectation without changing length semantics.
     *
     * @param s the input, may be {@code null}
     * @return {@code s} if non-{@code null}; otherwise {@code ""}
     */
    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns the {@code SELxxxxI} flag at the supplied 1-based row index.
     * Faithful translation of the COBOL {@code EVALUATE TRUE} at lines
     * 151-180 that walks SEL0001I..SEL0010I.
     *
     * @param in  the {@link CoTrn00Input}
     * @param row the 1-based row index (1..10)
     * @return the selection flag at that row, or {@code ""} if out of range
     */
    static String selFlagAtRow(CoTrn00Input in, int row) {
        return switch (row) {
            case 1  -> orEmpty(in.sel0001());
            case 2  -> orEmpty(in.sel0002());
            case 3  -> orEmpty(in.sel0003());
            case 4  -> orEmpty(in.sel0004());
            case 5  -> orEmpty(in.sel0005());
            case 6  -> orEmpty(in.sel0006());
            case 7  -> orEmpty(in.sel0007());
            case 8  -> orEmpty(in.sel0008());
            case 9  -> orEmpty(in.sel0009());
            case 10 -> orEmpty(in.sel0010());
            default -> "";
        };
    }

    /**
     * Returns the {@code TRNID0xI} echo value at the supplied 1-based row
     * index. Used to look up the TRAN-ID corresponding to a user's row
     * selection.
     *
     * @param in  the {@link CoTrn00Input}
     * @param row the 1-based row index (1..10)
     * @return the transaction id at that row, or {@code ""} if out of range
     */
    static String trnIdAtRow(CoTrn00Input in, int row) {
        return switch (row) {
            case 1  -> orEmpty(in.trnId01());
            case 2  -> orEmpty(in.trnId02());
            case 3  -> orEmpty(in.trnId03());
            case 4  -> orEmpty(in.trnId04());
            case 5  -> orEmpty(in.trnId05());
            case 6  -> orEmpty(in.trnId06());
            case 7  -> orEmpty(in.trnId07());
            case 8  -> orEmpty(in.trnId08());
            case 9  -> orEmpty(in.trnId09());
            case 10 -> orEmpty(in.trnId10());
            default -> "";
        };
    }

    // ============================================================================
    // Commarea-mutation helpers (translate to_program / from_program / context).
    // ============================================================================

    /**
     * Constructs the outbound commarea for an XCTL: sets
     * {@code CDEMO-TO-PROGRAM} to the supplied target,
     * {@code CDEMO-FROM-TRANID} to {@code "CT00"},
     * {@code CDEMO-FROM-PROGRAM} to {@code "COTRN00C"}, and
     * {@code CDEMO-PGM-CONTEXT} to {@link PgmContext#ENTER}.
     *
     * <p>Equivalent to the COBOL pattern at lines 188-191 and 510-516.
     *
     * @param current the current commarea (never {@code null})
     * @param toProgram the 8-character target program name (e.g. "COTRN01C")
     * @return a fresh {@link CardDemoCommarea} with the routing fields
     *         updated and all other fields preserved
     */
    private static CardDemoCommarea buildXctlCommarea(
            CardDemoCommarea current, String toProgram) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(toProgram, "toProgram");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        // COBOL CDEMO-FROM-TRANID PIC X(4) and CDEMO-FROM-PROGRAM PIC X(8) are
        // fixed-width; pad/trim to exact lengths for the validateFixedLengthAscii
        // compact-constructor check in CdemoGeneralInfo.
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padExact(TRANSACTION_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padExact(PROGRAM_NAME, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padExact(gi.toTranId(), CardDemoCommarea.LENGTH_TO_TRANID),
                padExact(toProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a commarea identical to {@code current} except with
     * {@code CDEMO-PGM-CONTEXT} set to {@code newContext}.
     *
     * @param current the current commarea (never {@code null})
     * @param newContext the new program context (never {@code null})
     * @return a fresh {@link CardDemoCommarea} with the context updated
     */
    private static CardDemoCommarea withPgmContext(
            CardDemoCommarea current, PgmContext newContext) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(newContext, "newContext");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
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
     * Returns a commarea identical to {@code current} except with
     * {@code CDEMO-TO-PROGRAM} set to the supplied target. Used by the
     * PF3 branch which sets the target then performs RETURN-TO-PREV-SCREEN.
     *
     * @param current the current commarea (never {@code null})
     * @param toProgram the 8-character target program name
     * @return a fresh {@link CardDemoCommarea} with the to-program updated
     */
    private static CardDemoCommarea withToProgram(
            CardDemoCommarea current, String toProgram) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(toProgram, "toProgram");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                padExact(toProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                gi.pgmContext());
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Pads {@code s} to exactly {@code length} characters: truncates from
     * the right if too long, space-pads on the right if too short.
     * {@code null} is treated as the empty string. The result satisfies
     * the {@code validateFixedLengthAscii} contract in
     * {@link CardDemoCommarea.CdemoGeneralInfo}.
     *
     * @param s      the input (may be {@code null})
     * @param length the exact required length (positive)
     * @return a string of exactly {@code length} characters
     */
    static String padExact(String s, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
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

    // ============================================================================
    // Mutable per-invocation state (translation of COBOL WORKING-STORAGE).
    // ============================================================================

    /**
     * Mutable scratchpad bundling all per-invocation COBOL WORKING-STORAGE
     * fields that the paragraphs read and write.
     *
     * <p>This is a deliberate translation of:
     * <ul>
     *   <li>COBOL 88-level switches (ERR-FLG-ON, TRANSACT-EOF,
     *       SEND-ERASE-YES/NO, NEXT-PAGE-YES/NO) &rarr; boolean fields.</li>
     *   <li>COBOL working-storage scalars (WS-MESSAGE, WS-IDX,
     *       CDEMO-CT00-PAGE-NUM, CDEMO-CT00-TRNID-FIRST, CDEMO-CT00-TRNID-LAST,
     *       CDEMO-CT00-NEXT-PAGE-FLG) &rarr; String / int fields.</li>
     *   <li>COBOL row-staging variables (TRNIDxxI, TDATExxI, TDESCxxI,
     *       TAMTxxxI for x=01..10) &rarr; parallel String arrays of length 10.</li>
     * </ul>
     *
     * <p>An instance is allocated fresh at the top of every
     * {@link #handle(CoTrn00Input, CardDemoCommarea, AidKey)} call to
     * guarantee thread isolation; the class is package-private to permit
     * unit-test visibility but is never exposed via the public API.
     */
    static final class MutableState {

        /** {@code ERR-FLG-ON} 88-level: {@code 'Y'} when an error has occurred. */
        boolean errFlgOn;

        /**
         * {@code TRANSACT-EOF} 88-level: {@code 'Y'} when the browse cursor
         * has reached the end of the file (NOTFND or ENDFILE).
         */
        boolean transactEof;

        /**
         * {@code SEND-ERASE-YES} / {@code SEND-ERASE-NO}: {@code true} (YES)
         * means the subsequent {@code SEND-TRNLST-SCREEN} uses
         * {@code EXEC CICS SEND ... ERASE}; {@code false} (NO) omits ERASE.
         * Initialized to {@code true} (YES) per COBOL line 99.
         */
        boolean sendEraseYes = true;

        /**
         * {@code NEXT-PAGE-YES} / {@code NEXT-PAGE-NO}: {@code true} when
         * PROCESS-PAGE-FORWARD detected at least one record beyond the
         * current 10-row window. Set to {@code true} during forward paging
         * by PF7 (see COBOL line 240).
         */
        boolean nextPageYes;

        /**
         * {@code WS-MESSAGE PIC X(78)} VALUE SPACES. The verbatim error
         * message that will be moved to {@code ERRMSGO} of {@code COTRN0AO}
         * by SEND-TRNLST-SCREEN. Initialized to empty per COBOL line 102.
         */
        String wsMessage = "";

        /**
         * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}: the TRAN-ID of row 1 on
         * the most recently displayed page. Populated by POPULATE-TRAN-DATA
         * when WS-IDX = 1; consumed by PROCESS-PF7-KEY for backward paging.
         */
        String tranIdFirst = "";

        /**
         * {@code CDEMO-CT00-TRNID-LAST PIC X(16)}: the TRAN-ID of row 10 on
         * the most recently displayed page. Populated by POPULATE-TRAN-DATA
         * when WS-IDX = 10; consumed by PROCESS-PF8-KEY for forward paging.
         */
        String tranIdLast = "";

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}: the 1-based ordinal of the
         * currently displayed page. 0 means no page has been shown yet
         * (e.g. first entry); 1 is the first page; incremented by
         * PROCESS-PAGE-FORWARD, decremented by PROCESS-PAGE-BACKWARD.
         */
        long pageNum;

        /**
         * The {@link CoTrn00Input} the user submitted (or
         * {@link CoTrn00Input#empty()} on first entry).
         */
        CoTrn00Input input;

        /**
         * The {@link CardDemoCommarea} as it stands at the start of the
         * invocation; mutated via {@link CardDemoCommarea#withCdemoGeneralInfo}
         * to record program-context transitions, and ultimately returned in
         * the {@link Outcome}.
         */
        CardDemoCommarea commarea;

        /**
         * The {@link AidKey} the user pressed (or
         * {@link AidKey#ENTER} on first entry); inspected by
         * {@link #handle(CoTrn00Input, CardDemoCommarea, AidKey)}'s
         * exhaustive switch.
         */
        AidKey aidKey;

        // -- Row-staging arrays (length 10, parallel) ------------------------

        /** TRNID01..TRNID10 staged output; index {@code i} holds row {@code i+1}. */
        final String[] rowTranId = new String[ROWS_PER_PAGE];

        /** TDATE01..TDATE10 staged output (MM/DD/YY). */
        final String[] rowDate = new String[ROWS_PER_PAGE];

        /** TDESC01..TDESC10 staged output (TRAN-DESC PIC X(26)). */
        final String[] rowDesc = new String[ROWS_PER_PAGE];

        /** TAMT001..TAMT010 staged output (PIC +99999999.99). */
        final String[] rowAmt = new String[ROWS_PER_PAGE];

        /** Tracks how many rows were actually populated (for PAGE-NUM bookkeeping). */
        int rowsPopulated;

        MutableState() {
            for (int i = 0; i < ROWS_PER_PAGE; i++) {
                rowTranId[i] = "";
                rowDate[i] = "";
                rowDesc[i] = "";
                rowAmt[i] = "";
            }
        }
    }
}
