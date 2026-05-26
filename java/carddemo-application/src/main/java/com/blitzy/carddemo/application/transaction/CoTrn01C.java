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
 * Java translation of the COBOL {@code COTRN01C} CICS online program
 * ({@code app/cbl/COTRN01C.cbl}) &mdash; the <em>View Transaction</em>
 * detail screen for transaction {@code CT01}.
 *
 * <h2>Program purpose</h2>
 * Reads a single transaction record from the {@code TRANSACT} VSAM KSDS by
 * its 16-character primary key and projects all 14 transaction-detail
 * fields onto the BMS map {@code COTRN1A}. The screen supports four AID
 * keys:
 * <ul>
 *   <li><b>ENTER</b> &mdash; fetch the transaction record identified by
 *       the operator-typed {@code TRNIDIN} field. Empty input triggers
 *       the verbatim {@code "Tran ID can NOT be empty..."} error; a
 *       successful read populates all 13 detail fields; a NOTFND yields
 *       {@code "Transaction ID NOT found..."}; any other read error yields
 *       {@code "Unable to lookup Transaction..."} and logs RESP/REAS at
 *       error level (per the COBOL
 *       {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} idiom).</li>
 *   <li><b>PF3</b> &mdash; return to the calling program. Uses the
 *       {@code CDEMO-FROM-PROGRAM} commarea field if populated, defaulting
 *       to {@link #BACK_PROGRAM} (COMEN01C, the main menu) when blank.
 *       Note that COTRN01 specifically uses {@code FROM-PROGRAM} (not
 *       {@code TO-PROGRAM}) for the PF3 target &mdash; preserved verbatim
 *       from {@code app/cbl/COTRN01C.cbl:L116-L122}.</li>
 *   <li><b>PF4</b> &mdash; clear the screen (zero all 13 detail fields,
 *       the {@code TRNIDIN} input field, and the error-message area) and
 *       re-display.</li>
 *   <li><b>PF5</b> &mdash; XCTL to {@link #BROWSE_PROGRAM} (COTRN00C, the
 *       transaction-browse list). The BMS footer at
 *       {@code app/bms/COTRN01.bms:L267} labels this as
 *       {@code "F5=Browse Tran."}.</li>
 * </ul>
 * Any other AID key (CLEAR, PA1, PA2, PFK01, PFK02, PFK06..PFK12) yields
 * the verbatim {@code CCDA-MSG-INVALID-KEY} error message from
 * {@code app/cpy/CSMSG01Y.cpy}.
 *
 * <h2>Auto-trigger from {@code COTRN00C}</h2>
 * <p>The COBOL program at {@code app/cbl/COTRN01C.cbl:L103-L108} performs
 * a one-shot auto-fetch when entered with {@code CDEMO-CT01-TRN-SELECTED}
 * populated (the transaction id selected by the operator on the
 * preceding {@code COTRN00C} list screen). The Java translation
 * preserves this behaviour through a different but observationally
 * equivalent mechanism: the caller (COTRN00C's
 * {@link CoTrn00C.Outcome.Xctl} branch) populates {@link CoTrn01Input#trnIdIn()}
 * with the selected id before invoking {@code CoTrn01C.handle(...)}. On
 * the first entry (when {@link PgmContext#isEnter()} is true), this
 * translation checks whether {@code trnIdIn} is non-blank and, if so,
 * auto-triggers {@link #processEnterKey(MutableState)} &mdash; matching
 * the COBOL idiom exactly. This workaround is necessary because
 * {@link CardDemoCommarea} does not (yet) carry the
 * {@code CDEMO-CT01-INFO} extension fields.
 *
 * <h2>Translated paragraphs (COBOL &rarr; Java)</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #handle(CoTrn01Input, CardDemoCommarea, AidKey)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(MutableState)}</li>
 *   <li>{@code READ-TRANSACT-FILE} &rarr; {@link #readTransactFile(MutableState, String)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link #clearCurrentScreen(MutableState)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(MutableState)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen(MutableState, String)}</li>
 *   <li>{@code SEND-TRNVIEW-SCREEN} &rarr; {@link #sendTrnviewScreen(MutableState)}</li>
 *   <li>{@code RECEIVE-TRNVIEW-SCREEN} &rarr; subsumed into the {@link CoTrn01Input}
 *       parameter (the BMS RECEIVE-MAP decode happens in the adapter layer).</li>
 *   <li>{@code POPULATE-HEADER-INFO} &rarr; {@link #buildHeaderFields()}</li>
 * </ul>
 *
 * <h2>Outcome model</h2>
 * The method returns one of two {@link Outcome} variants:
 * <ul>
 *   <li>{@link Outcome.SendMap SendMap} &mdash; analogous to the COBOL
 *       {@code EXEC CICS SEND MAP / RETURN}: a fully populated
 *       {@link CoTrn01Output} for the renderer plus the updated
 *       {@link CardDemoCommarea}.</li>
 *   <li>{@link Outcome.Xctl Xctl} &mdash; analogous to the COBOL
 *       {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}: a target
 *       program name plus the updated {@link CardDemoCommarea}. The
 *       caller dispatches the next program via
 *       {@link ProgramRegistry#invoke(String, CardDemoCommarea)}.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * Instances of {@code CoTrn01C} are immutable after construction (only
 * holding final references to the injected collaborators) and are safe
 * for concurrent use. The mutable per-invocation state lives in a
 * private {@link MutableState} object allocated fresh inside
 * {@link #handle(CoTrn01Input, CardDemoCommarea, AidKey)} so each call
 * is fully isolated.
 *
 * <h2>Security</h2>
 * <p>Per AAP &sect;0.7.2, the full Primary Account Number (PAN) is
 * displayed on the BMS screen ({@link CoTrn01Output#cardNumber()}) but
 * is masked in any log output via {@link #maskPan(String)}: all but the
 * last 4 digits are replaced with asterisks.
 *
 * @see CoTrn01Input  for the entry-contract (BMS COTRN1AI) input record
 * @see CoTrn01Output for the entry-contract (BMS COTRN1AO) output record
 * @see TransactionRepository for the port that backs the {@code TRANSACT} read
 * @see TranRecord for the 350-byte transaction record translated from copybook CVTRA05Y
 */
@CobolProgram(
        value = "COTRN01C",
        sourcePath = "app/cbl/COTRN01C.cbl",
        translationDate = "2025-01-21",
        notes = "View Transaction online CICS program (transaction CT01). "
              + "Reads TRANSACT by 16-char key; displays all 14 fields incl. card, "
              + "type, category, source, description, amount, dates, merchant data. "
              + "Invoked directly or from COTRN00C via TRN-SELECTED commarea; the "
              + "Java translation preserves the auto-trigger by checking "
              + "input.trnIdIn() non-blank on first PgmContext.ENTER entry since "
              + "CardDemoCommarea does not carry the CDEMO-CT01-INFO extension. "
              + "PF3 uses CDEMO-FROM-PROGRAM (not TO-PROGRAM) as the back target."
)
public final class CoTrn01C {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoTrn01C.class);

    // ============================================================================
    // Program identity constants (mirror COBOL WORKING-STORAGE fields).
    // ============================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} from COBOL line 36. */
    public static final String PROGRAM_NAME = "COTRN01C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CT01'} from COBOL line 37. */
    public static final String TRANSACTION_ID = "CT01";

    /** {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} from COBOL line 39. */
    public static final String TRANSACT_FILE = "TRANSACT";

    /**
     * Default {@code CDEMO-TO-PROGRAM} when EIBCALEN is 0 (no commarea).
     * Translates the COBOL {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at
     * lines 94-96 of {@code app/cbl/COTRN01C.cbl}.
     */
    public static final String DEFAULT_PREV_PROGRAM = ProgramRegistry.CO_SGN_00C;

    /**
     * PF3 default target: main menu. Translates the COBOL
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} at line 117 of
     * {@code app/cbl/COTRN01C.cbl}, used when {@code CDEMO-FROM-PROGRAM}
     * is blank.
     */
    public static final String BACK_PROGRAM = ProgramRegistry.CO_MEN_01C;

    /**
     * PF5 target: transaction-browse list. Translates the COBOL
     * {@code MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM} at line 126 of
     * {@code app/cbl/COTRN01C.cbl}. The BMS footer at
     * {@code app/bms/COTRN01.bms:L267} labels this as "F5=Browse Tran.".
     */
    public static final String BROWSE_PROGRAM = ProgramRegistry.CO_TRN_00C;

    // ============================================================================
    // Header constants (mirror CCDA-TITLE01 / CCDA-TITLE02 in COTTL01Y).
    // ============================================================================

    /**
     * {@code CCDA-TITLE01 PIC X(40) VALUE '      AWS Mainframe Modernization       '}
     * from {@code app/cpy/COTTL01Y.cpy:L18-L19}. The meaningful prefix is
     * kept untrimmed here; the BMS SEND-MAP adapter pads to the full
     * {@code PIC X(40)} width when serializing.
     */
    static final String TITLE_01 = "AWS Mainframe Modernization";

    /**
     * {@code CCDA-TITLE02 PIC X(40) VALUE '              CardDemo                  '}
     * from {@code app/cpy/COTTL01Y.cpy:L20-L22}. The historical commented-out
     * value at line 21 was {@code '  Credit Card Demo Application (CCDA)   '};
     * the active value is preserved verbatim here.
     */
    static final String TITLE_02 = "CardDemo";

    // ============================================================================
    // Verbatim COBOL error messages (preserved exactly per AAP §0.7.1).
    // ============================================================================

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} from
     * {@code app/cpy/CSMSG01Y.cpy:L20-L21}, content
     * {@code 'Invalid key pressed. Please see below...         '}.
     * The 40-character meaningful prefix is preserved verbatim;
     * trailing pad is supplied by the BMS encoder. Used by the
     * {@link #invalidKey(MutableState)} branch (translation of COBOL
     * {@code WHEN OTHER} on the EIBAID EVALUATE).
     */
    static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Verbatim from {@code app/cbl/COTRN01C.cbl:L149} &mdash; emitted when the
     * operator presses ENTER without populating {@code TRNIDIN}.
     */
    static final String MSG_TRANID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Verbatim from {@code app/cbl/COTRN01C.cbl:L285-L286} &mdash; emitted on
     * {@code DFHRESP(NOTFND)} during the {@code TRANSACT} read.
     */
    static final String MSG_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * Verbatim from {@code app/cbl/COTRN01C.cbl:L292-L293} &mdash; emitted on
     * any unexpected {@code RESP} code from {@code EXEC CICS READ DATASET}.
     * Note the capital "T" in "Transaction" (preserved from COBOL).
     */
    static final String MSG_LOOKUP_ERROR = "Unable to lookup Transaction...";

    // ============================================================================
    // Java time formatters (replace COBOL FUNCTION CURRENT-DATE / substring).
    // ============================================================================

    /**
     * {@code MM/DD/YY} &mdash; format used by CURDATEO. Translates the
     * COBOL {@code WS-CURDATE-MM-DD-YY} concatenation at
     * {@code app/cbl/COTRN01C.cbl:L252-L256}.
     */
    private static final DateTimeFormatter DATE_MM_DD_YY =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code HH:MM:SS} &mdash; format used by CURTIMEO. Translates the
     * COBOL {@code WS-CURTIME-HH-MM-SS} concatenation at
     * {@code app/cbl/COTRN01C.cbl:L258-L262}.
     */
    private static final DateTimeFormatter TIME_HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * {@code yyyy-MM-dd} &mdash; format used to project the
     * {@link TranRecord#tranOrigTs()} / {@link TranRecord#tranProcTs()}
     * {@link LocalDateTime} values onto the 10-character {@code TORIGDTO} /
     * {@code TPROCDTO} BMS fields. The COBOL {@code MOVE TRAN-ORIG-TS TO
     * TORIGDTI} at lines 185-186 of {@code app/cbl/COTRN01C.cbl} performs
     * an implicit left-truncation of the 26-character {@code PIC X(26)}
     * timestamp to the 10-character {@code PIC X(10)} display field
     * &mdash; effectively yielding the date-portion (YYYY-MM-DD).
     */
    private static final DateTimeFormatter DATE_YYYY_MM_DD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    // ============================================================================
    // Collaborators (constructor-injected).
    // ============================================================================

    private final TransactionRepository transactionRepository;
    private final ProgramRegistry programRegistry;

    /**
     * Constructs a new {@code CoTrn01C} use case with the required
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
    public CoTrn01C(TransactionRepository transactionRepository,
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
     * The result of a single {@link CoTrn01C#handle invocation}.
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
         * @param screen   the {@link CoTrn01Output} carrying the 24x80
         *                 screen state (headers, 13 detail fields, error
         *                 message)
         * @param commarea the {@link CardDemoCommarea} to round-trip to
         *                 the next invocation of this program
         */
        record SendMap(CoTrn01Output screen, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null
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
         * @param targetProgram the COBOL program-id (e.g. {@code "COTRN00C"},
         *                      {@code "COMEN01C"}, {@code "COSGN00C"})
         *                      to which control transfers
         * @param commarea      the {@link CardDemoCommarea} to pass to the
         *                      target program
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea)
                implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract
             * and rejecting blank target-program names.
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
     * Executes one round-trip of the {@code COTRN01C} CICS online program
     * &mdash; the Java translation of {@code MAIN-PARA} at
     * {@code app/cbl/COTRN01C.cbl:L86-L139}.
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
     *       program clears the output, places the cursor on {@code TRNIDIN},
     *       and switches the commarea to {@link PgmContext#REENTER}. If
     *       {@link CoTrn01Input#trnIdIn()} is non-blank on first entry, it
     *       is treated as the {@code CDEMO-CT01-TRN-SELECTED} auto-trigger
     *       and the program performs {@link #processEnterKey(MutableState)}
     *       immediately (matching the COBOL behaviour at lines 103-108).</li>
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
     *                 {@link CoTrn01Input#empty()} on first entry. Must
     *                 not be {@code null}; pass {@code CoTrn01Input.empty()}
     *                 to represent an absent input.
     * @param commarea the CICS commarea round-tripped from the previous
     *                 invocation, or {@code null} if {@code EIBCALEN = 0}
     *                 (first attachment of the transaction)
     * @param aidKey   the 3270 attention identifier the user pressed.
     *                 Must not be {@code null}; pass {@code new AidKey.Enter()}
     *                 (or {@code AidKey.ENTER}) on first entry.
     * @return either an {@link Outcome.SendMap} carrying the populated
     *         {@link CoTrn01Output} for the renderer, or an
     *         {@link Outcome.Xctl} carrying the next program-id to
     *         dispatch via the {@link ProgramRegistry}
     * @throws NullPointerException if {@code input} or {@code aidKey} is
     *                              {@code null}
     */
    public Outcome handle(CoTrn01Input input, CardDemoCommarea commarea, AidKey aidKey) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(aidKey, "aidKey");

        // MAIN-PARA lines 88-92: initialize 88-level flags and clear WS-MESSAGE.
        MutableState state = new MutableState();
        state.input = input;
        state.commarea = commarea;
        state.aidKey = aidKey;

        // COBOL line 94-96: IF EIBCALEN = 0 → XCTL to COSGN00C
        if (commarea == null) {
            CardDemoCommarea outbound = buildXctlCommarea(
                    CardDemoCommarea.empty(), DEFAULT_PREV_PROGRAM);
            return new Outcome.Xctl(DEFAULT_PREV_PROGRAM, outbound);
        }

        // COBOL line 99-109: IF NOT CDEMO-PGM-REENTER → first-time entry;
        // ELSE → process the AID key.
        PgmContext context = commarea.cdemoGeneralInfo().pgmContext();
        if (context.isEnter()) {
            // First time through: switch context to REENTER, place cursor on
            // TRNIDIN (translated semantically: we don't actually drive the
            // physical cursor), then check the CDEMO-CT01-TRN-SELECTED
            // auto-trigger. Since CardDemoCommarea does not carry the
            // CDEMO-CT01-INFO extension, the calling COTRN00C populates the
            // auto-fetch id into input.trnIdIn() before invoking this method
            // — non-blank trnIdIn on first entry implies an auto-fetch.
            state.commarea = withPgmContext(commarea, PgmContext.REENTER);
            if (!input.isTransactionIdBlank()) {
                // COBOL line 103-108: MOVE CDEMO-CT01-TRN-SELECTED TO
                // TRNIDINI; PERFORM PROCESS-ENTER-KEY.
                LOGGER.debug("CoTrn01C: first-entry auto-trigger from "
                        + "TRN-SELECTED, tranId={}", input.trnIdIn());
                return processEnterKey(state);
            }
            // COBOL line 109: PERFORM SEND-TRNVIEW-SCREEN with all output
            // fields cleared (MOVE LOW-VALUES TO COTRN1AO at line 101).
            return sendTrnviewScreen(state);
        }

        // Re-entry: dispatch on the AID key using an exhaustive sealed switch.
        // NO default branch — exhaustiveness is the safety guarantee per AAP
        // §0.6.2 and §0.6.7. The Java 25 compiler enforces every permit is
        // covered.
        return switch (aidKey) {
            // COBOL line 113-114: WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
            case AidKey.Enter e         -> processEnterKey(state);
            // COBOL line 115-122: WHEN DFHPF3 → set TO-PROGRAM from
            // FROM-PROGRAM (default COMEN01C), then RETURN-TO-PREV-SCREEN.
            case AidKey.PfKey03 pf3     -> handlePf3Back(state);
            // COBOL line 123-124: WHEN DFHPF4 → PERFORM CLEAR-CURRENT-SCREEN
            case AidKey.PfKey04 pf4     -> clearCurrentScreen(state);
            // COBOL line 125-127: WHEN DFHPF5 → set TO-PROGRAM='COTRN00C',
            // then RETURN-TO-PREV-SCREEN.
            case AidKey.PfKey05 pf5     -> handlePf5Browse(state);
            // COBOL line 128-131: WHEN OTHER → set CCDA-MSG-INVALID-KEY,
            // PERFORM SEND-TRNVIEW-SCREEN. Every remaining AidKey permit
            // (Clear, Pa1, Pa2, PfKey01, PfKey02, PfKey06..PfKey12) is
            // enumerated here to satisfy the exhaustiveness check.
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

    // ============================================================================
    // Paragraph translations (private methods, one per COBOL paragraph).
    // ============================================================================

    /**
     * Java translation of the {@code PROCESS-ENTER-KEY} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L144-L192}.
     *
     * <p>Two-phase logic:
     * <ol>
     *   <li><b>Empty-TRNIDIN check</b> (COBOL lines 146-156): if
     *       {@code TRNIDINI = SPACES OR LOW-VALUES}, set the verbatim
     *       {@code "Tran ID can NOT be empty..."} error message, move -1
     *       to {@code TRNIDINL} (cursor positioning &mdash; semantic
     *       no-op in Java), and PERFORM {@code SEND-TRNVIEW-SCREEN}.</li>
     *   <li><b>Read and project</b> (COBOL lines 158-192): if no error,
     *       clear all 13 detail fields, MOVE {@code TRNIDINI} to
     *       {@code TRAN-ID}, PERFORM {@code READ-TRANSACT-FILE}, then if
     *       still no error MOVE every {@code TRAN-*} field to its
     *       corresponding {@code COTRN1AI} display position and PERFORM
     *       {@code SEND-TRNVIEW-SCREEN}.</li>
     * </ol>
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying the populated or
     *         error-decorated output
     */
    private Outcome processEnterKey(MutableState state) {
        CoTrn01Input in = state.input;

        // COBOL lines 146-156: EVALUATE TRUE / WHEN TRNIDINI = SPACES OR LOW-VALUES
        if (in.isTransactionIdBlank()) {
            // COBOL line 148: MOVE 'Y' TO WS-ERR-FLG
            state.errFlgOn = true;
            // COBOL line 149-150: MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE
            state.wsMessage = MSG_TRANID_EMPTY;
            // COBOL line 151: MOVE -1 TO TRNIDINL OF COTRN1AI
            //   (semantic cursor positioning — Java translation is a no-op
            //    since the SEND-MAP adapter handles cursor encoding).
            // COBOL line 152: PERFORM SEND-TRNVIEW-SCREEN
            return sendTrnviewScreen(state);
        }

        // COBOL lines 158-174: IF NOT ERR-FLG-ON →
        // MOVE SPACES TO TRNIDI, CARDNUMI, TTYPCDI, TCATCDI, TRNSRCI, TRNAMTI,
        //                TDESCI, TORIGDTI, TPROCDTI, MIDI, MNAMEI, MCITYI, MZIPI
        // MOVE TRNIDINI TO TRAN-ID; PERFORM READ-TRANSACT-FILE.
        // Java translation: the detail fields live on the *output* DTO and
        // are populated below; we don't need to explicitly clear them here
        // because we construct the CoTrn01Output from scratch when sending.
        String tranId = orEmpty(in.trnIdIn());
        readTransactFile(state, tranId);

        // COBOL lines 176-192: IF NOT ERR-FLG-ON → populate all 13 detail
        // fields from the TRAN-RECORD and PERFORM SEND-TRNVIEW-SCREEN.
        if (!state.errFlgOn) {
            TranRecord tr = state.tranRecord;
            // COBOL line 177: MOVE TRAN-AMT TO WS-TRAN-AMT (PIC +99999999.99)
            // — captured into the formatted amount string below.
            state.outTranId       = orEmpty(tr.tranId());
            state.outCardNum      = orEmpty(tr.tranCardNum());
            state.outTypeCode     = orEmpty(tr.tranTypeCd());
            state.outCategoryCode = String.format(Locale.ROOT, "%04d", tr.tranCatCd());
            state.outSource       = orEmpty(tr.tranSource());
            state.outAmount       = formatTranAmount(tr.tranAmt());
            // TRAN-DESC PIC X(100) → TDESCI PIC X(60): COBOL MOVE truncates
            // a longer sending field on the right when the receiving field
            // is shorter. Replicate by taking the leading 60 characters.
            state.outDescription  = truncateTo(orEmpty(tr.tranDesc()), 60);
            // TRAN-ORIG-TS PIC X(26) → TORIGDTI PIC X(10): COBOL implicit
            // truncation to YYYY-MM-DD (the first 10 chars of "yyyy-MM-dd HH:mm:ss.SSSSSS").
            state.outOrigDate     = formatTimestampField(tr.tranOrigTs());
            state.outProcDate     = formatTimestampField(tr.tranProcTs());
            state.outMerchantId   = String.format(Locale.ROOT, "%09d", tr.tranMerchantId());
            // TRAN-MERCHANT-NAME PIC X(50) → MNAMEI PIC X(30): truncate.
            state.outMerchantName = truncateTo(orEmpty(tr.tranMerchantName()), 30);
            // TRAN-MERCHANT-CITY PIC X(50) → MCITYI PIC X(25): truncate.
            state.outMerchantCity = truncateTo(orEmpty(tr.tranMerchantCity()), 25);
            state.outMerchantZip  = orEmpty(tr.tranMerchantZip());

            // Log the successful fetch with PAN masked per AAP §0.7.2 (the
            // BMS output field CARDNUMO still displays the full PAN — masking
            // is logging-only).
            LOGGER.debug("CoTrn01C: fetched transaction tranId={} cardNum={}",
                    state.outTranId, maskPan(state.outCardNum));

            // COBOL line 191: PERFORM SEND-TRNVIEW-SCREEN
            return sendTrnviewScreen(state);
        }

        // ERR-FLG-ON was set inside READ-TRANSACT-FILE — that paragraph
        // already PERFORMed SEND-TRNVIEW-SCREEN, so we just return the
        // current screen here. (Java translation: readTransactFile records
        // the error message on state and we send the screen below.)
        return sendTrnviewScreen(state);
    }

    /**
     * Java translation of the {@code READ-TRANSACT-FILE} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L267-L296}.
     *
     * <p>The COBOL paragraph issues
     * {@code EXEC CICS READ DATASET('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID) UPDATE}
     * and dispatches on the response code via
     * {@code EVALUATE WS-RESP-CD}:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; CONTINUE (no-op; caller projects
     *       TRAN-RECORD into the output map fields).</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; set the verbatim
     *       {@code "Transaction ID NOT found..."} message and PERFORM
     *       {@code SEND-TRNVIEW-SCREEN}.</li>
     *   <li>{@code OTHER} &rarr; DISPLAY the {@code RESP} / {@code REAS}
     *       diagnostic, set the verbatim {@code "Unable to lookup Transaction..."}
     *       message and PERFORM {@code SEND-TRNVIEW-SCREEN}.</li>
     * </ul>
     *
     * <p>The {@code UPDATE} clause on the original COBOL READ is a vestigial
     * artifact &mdash; this program issues no subsequent {@code REWRITE} or
     * {@code UNLOCK}, so the translation is a plain read-only lookup via
     * {@link TransactionRepository#findById(String)}, which returns
     * {@link Optional#empty()} when the record does not exist and throws
     * a {@link RuntimeException} on any other I/O failure. The mapping
     * preserves observable semantics exactly.
     *
     * @param state  the mutable per-invocation state (populated with the
     *               fetched {@link TranRecord} on success, or with the
     *               error flag and message on failure)
     * @param tranId the 16-character key to look up in {@code TRANSACT}
     */
    private void readTransactFile(MutableState state, String tranId) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tranId, "tranId");
        try {
            Optional<TranRecord> result = transactionRepository.findById(tranId);
            if (result.isPresent()) {
                // COBOL line 281-282: WHEN DFHRESP(NORMAL) → CONTINUE
                state.tranRecord = result.get();
            } else {
                // COBOL lines 283-288: WHEN DFHRESP(NOTFND) →
                // MOVE 'Y' TO WS-ERR-FLG; MOVE 'Transaction ID NOT found...' TO WS-MESSAGE;
                // MOVE -1 TO TRNIDINL OF COTRN1AI; PERFORM SEND-TRNVIEW-SCREEN.
                state.errFlgOn = true;
                state.wsMessage = MSG_NOT_FOUND;
                LOGGER.debug("CoTrn01C: TRANSACT NOTFND for tranId={}", tranId);
            }
        } catch (RuntimeException ex) {
            // COBOL lines 289-295: WHEN OTHER →
            // DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD;
            // MOVE 'Y' TO WS-ERR-FLG;
            // MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE;
            // MOVE -1 TO TRNIDINL OF COTRN1AI; PERFORM SEND-TRNVIEW-SCREEN.
            state.errFlgOn = true;
            state.wsMessage = MSG_LOOKUP_ERROR;
            LOGGER.error("CoTrn01C: TRANSACT read failed for tranId={}: {}",
                    tranId, ex.getMessage(), ex);
        }
    }

    /**
     * Java translation of the {@code CLEAR-CURRENT-SCREEN} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L301-L304}.
     *
     * <p>Clears all input/output fields via {@link #initializeAllFields(MutableState)}
     * and then PERFORMs {@code SEND-TRNVIEW-SCREEN}.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying a fully blanked screen
     */
    private Outcome clearCurrentScreen(MutableState state) {
        initializeAllFields(state);
        return sendTrnviewScreen(state);
    }

    /**
     * Java translation of the {@code INITIALIZE-ALL-FIELDS} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L309-L326}.
     *
     * <p>MOVE -1 TO TRNIDINL (cursor positioning &mdash; semantic no-op);
     * MOVE SPACES TO TRNIDINI, TRNIDI, CARDNUMI, TTYPCDI, TCATCDI, TRNSRCI,
     * TRNAMTI, TDESCI, TORIGDTI, TPROCDTI, MIDI, MNAMEI, MCITYI, MZIPI,
     * WS-MESSAGE.
     *
     * @param state the mutable per-invocation state to clear
     */
    private static void initializeAllFields(MutableState state) {
        Objects.requireNonNull(state, "state");
        state.errFlgOn = false;
        state.wsMessage = "";
        state.tranRecord = null;
        // Clear input echo and all 13 detail-display field staging slots.
        state.input = CoTrn01Input.empty();
        state.outTranId       = "";
        state.outCardNum      = "";
        state.outTypeCode     = "";
        state.outCategoryCode = "";
        state.outSource       = "";
        state.outAmount       = "";
        state.outDescription  = "";
        state.outOrigDate     = "";
        state.outProcDate     = "";
        state.outMerchantId   = "";
        state.outMerchantName = "";
        state.outMerchantCity = "";
        state.outMerchantZip  = "";
    }

    /**
     * Handles the PF3 branch of the EIBAID EVALUATE at
     * {@code app/cbl/COTRN01C.cbl:L115-L122}. Selects the back-target by
     * the COBOL precedence rule:
     * <ul>
     *   <li>If {@code CDEMO-FROM-PROGRAM} is SPACES or LOW-VALUES, set
     *       {@code CDEMO-TO-PROGRAM} to {@link #BACK_PROGRAM} (COMEN01C).</li>
     *   <li>Otherwise, set {@code CDEMO-TO-PROGRAM} to {@code CDEMO-FROM-PROGRAM}.</li>
     * </ul>
     * Then PERFORM {@code RETURN-TO-PREV-SCREEN}.
     *
     * <p>Note: COTRN01 deliberately uses {@code CDEMO-FROM-PROGRAM} (not
     * {@code CDEMO-TO-PROGRAM}) as the PF3 source &mdash; this is a
     * preserved nuance of COTRN01 that differs from other transaction
     * programs in the suite.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.Xctl} to the chosen back-target
     */
    private Outcome handlePf3Back(MutableState state) {
        String fromProgram = orEmpty(state.commarea.cdemoGeneralInfo().fromProgram()).strip();
        String target = fromProgram.isEmpty() ? BACK_PROGRAM : fromProgram;
        LOGGER.debug("CoTrn01C: PF3 → returning to {} (fromProgram=[{}])",
                target, fromProgram);
        return returnToPrevScreen(state, target);
    }

    /**
     * Handles the PF5 branch of the EIBAID EVALUATE at
     * {@code app/cbl/COTRN01C.cbl:L125-L127}: set
     * {@code CDEMO-TO-PROGRAM = 'COTRN00C'}, then PERFORM
     * {@code RETURN-TO-PREV-SCREEN}.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.Xctl} to {@link #BROWSE_PROGRAM} (COTRN00C)
     */
    private Outcome handlePf5Browse(MutableState state) {
        LOGGER.debug("CoTrn01C: PF5 → XCTL'ing to {} (Browse Tran)", BROWSE_PROGRAM);
        return returnToPrevScreen(state, BROWSE_PROGRAM);
    }

    /**
     * Java translation of the {@code RETURN-TO-PREV-SCREEN} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L197-L208}.
     *
     * <p>Sets the outbound commarea fields per the COBOL idiom:
     * <ul>
     *   <li>If the chosen {@code CDEMO-TO-PROGRAM} is LOW-VALUES or SPACES,
     *       fall back to {@link #DEFAULT_PREV_PROGRAM} (COSGN00C).</li>
     *   <li>MOVE {@code WS-TRANID} TO {@code CDEMO-FROM-TRANID}.</li>
     *   <li>MOVE {@code WS-PGMNAME} TO {@code CDEMO-FROM-PROGRAM}.</li>
     *   <li>MOVE ZEROS TO {@code CDEMO-PGM-CONTEXT} (i.e., set to
     *       {@link PgmContext#ENTER}, which has indicator 0).</li>
     * </ul>
     * Then emit an {@link Outcome.Xctl} carrying the target program-id
     * and the updated commarea.
     *
     * @param state    the mutable per-invocation state
     * @param toTarget the target program name set by the caller
     * @return the {@link Outcome.Xctl} carrying the resolved target and
     *         updated commarea
     */
    private Outcome returnToPrevScreen(MutableState state, String toTarget) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(toTarget, "toTarget");
        // COBOL line 199-201: IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES →
        // MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        String target = toTarget.strip();
        if (target.isEmpty()) {
            target = DEFAULT_PREV_PROGRAM;
        }
        CardDemoCommarea outbound = buildXctlCommarea(state.commarea, target);
        return new Outcome.Xctl(target, outbound);
    }

    /**
     * Translation of the COBOL {@code WHEN OTHER} branch of the EIBAID
     * EVALUATE at {@code app/cbl/COTRN01C.cbl:L128-L131}.
     *
     * <p>Sets {@link MutableState#errFlgOn} to {@code true}, sets
     * {@link MutableState#wsMessage} to the verbatim
     * {@link #MSG_INVALID_KEY} from {@code app/cpy/CSMSG01Y.cpy}, and
     * PERFORMs {@code SEND-TRNVIEW-SCREEN}.
     *
     * @param state the mutable per-invocation state
     * @return the {@link Outcome.SendMap} carrying the error-decorated screen
     */
    private Outcome invalidKey(MutableState state) {
        state.errFlgOn = true;
        state.wsMessage = MSG_INVALID_KEY;
        return sendTrnviewScreen(state);
    }


    // ============================================================================
    // Screen assembly (SEND-TRNVIEW-SCREEN + POPULATE-HEADER-INFO).
    // ============================================================================

    /**
     * Java translation of the {@code SEND-TRNVIEW-SCREEN} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L213-L225}.
     *
     * <p>Performs {@code POPULATE-HEADER-INFO}, moves {@code WS-MESSAGE}
     * to {@code ERRMSGO}, and issues the equivalent of
     * {@code EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01') FROM(COTRN1AO) ERASE CURSOR}.
     * In Java this is materialized as a {@link CoTrn01Output} record bundled
     * into an {@link Outcome.SendMap}.
     *
     * @param state the mutable per-invocation state carrying staged field
     *              values, error flag, and the inbound commarea
     * @return the {@link Outcome.SendMap} carrying the populated screen
     */
    private Outcome sendTrnviewScreen(MutableState state) {
        Objects.requireNonNull(state, "state");
        HeaderFields hdr = buildHeaderFields();

        // The error-message color flips to RED whenever WS-MESSAGE is
        // non-empty; otherwise it stays DEFAULT (the BMS compile-time
        // ERRMSG COLOR=RED still applies for empty messages but the
        // dynamic attribute byte is not modified). Per AAP §0.7.1
        // verbatim preservation of COBOL behaviour.
        boolean errPresent = state.wsMessage != null && !state.wsMessage.isEmpty();
        CoTrn01Output.FieldColor errColor = errPresent
                ? CoTrn01Output.FieldColor.RED
                : CoTrn01Output.FieldColor.DEFAULT;

        // The operator-typed TRNIDIN echo. On the first entry path the
        // input.trnIdIn() may already carry the auto-trigger id; on
        // re-entry the user-typed value is echoed unchanged.
        String trnIdInEcho = orEmpty(state.input.trnIdIn());

        CoTrn01Output screen = new CoTrn01Output(
                TITLE_01,                       // title1
                TITLE_02,                       // title2
                TRANSACTION_ID,                 // transactionName ("CT01")
                PROGRAM_NAME,                   // programName    ("COTRN01C")
                hdr.curDate(),                  // currentDate    (MM/DD/YY)
                hdr.curTime(),                  // currentTime    (HH:MM:SS)
                trnIdInEcho,                    // transactionIdIn (TRNIDINO echo)
                orEmpty(state.outTranId),       // transactionId
                orEmpty(state.outCardNum),      // cardNumber (full PAN; masked only in logs)
                orEmpty(state.outTypeCode),     // typeCode
                orEmpty(state.outCategoryCode), // categoryCode
                orEmpty(state.outSource),       // source
                orEmpty(state.outDescription),  // description
                orEmpty(state.outAmount),       // amount
                orEmpty(state.outOrigDate),     // origDate
                orEmpty(state.outProcDate),     // procDate
                orEmpty(state.outMerchantId),   // merchantId
                orEmpty(state.outMerchantName), // merchantName
                orEmpty(state.outMerchantCity), // merchantCity
                orEmpty(state.outMerchantZip),  // merchantZip
                orEmpty(state.wsMessage),       // errMsg
                errColor);                      // errMsgColor

        return new Outcome.SendMap(screen, state.commarea);
    }

    /**
     * Java translation of the {@code POPULATE-HEADER-INFO} paragraph at
     * {@code app/cbl/COTRN01C.cbl:L243-L262}.
     *
     * <p>Captures the current date and time once per send to ensure the
     * header date and time share the same wall-clock snapshot. The COBOL
     * paragraph uses {@code FUNCTION CURRENT-DATE} and slices the result
     * to populate {@code WS-CURDATE-MM-DD-YY} and {@code WS-CURTIME-HH-MM-SS};
     * the Java translation uses a single {@link LocalDateTime#now()} call
     * and the pre-built {@link #DATE_MM_DD_YY} / {@link #TIME_HH_MM_SS}
     * formatters.
     *
     * @return a {@link HeaderFields} carrier (always non-{@code null})
     */
    private static HeaderFields buildHeaderFields() {
        LocalDateTime now = LocalDateTime.now();
        return new HeaderFields(
                DATE_MM_DD_YY.format(now),
                TIME_HH_MM_SS.format(now));
    }

    /**
     * Carrier for the header date / time pair produced by
     * {@link #buildHeaderFields()}. Internal record used as a tuple — not
     * exposed on the public API. Both components are non-null formatted
     * strings of fixed width: {@code curDate} is exactly 8 characters
     * (MM/DD/YY), {@code curTime} is exactly 8 characters (HH:MM:SS).
     *
     * @param curDate the MM/DD/YY-formatted current date
     * @param curTime the HH:MM:SS-formatted current time
     */
    private record HeaderFields(String curDate, String curTime) {
    }

    // ============================================================================
    // Helper methods (date, amount, PAN masking, padding, etc.).
    // ============================================================================

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
     * by taking the rightmost 8 digits &mdash; mirroring the COBOL implicit
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
     * Formats a {@link LocalDateTime} timestamp as the 10-character
     * {@code YYYY-MM-DD} representation used by {@code TORIGDTO} and
     * {@code TPROCDTO}.
     *
     * <p>The COBOL {@code MOVE TRAN-ORIG-TS TO TORIGDTI} at
     * {@code app/cbl/COTRN01C.cbl:L185} performs an implicit left-truncation
     * of the 26-character {@code PIC X(26)} source to the 10-character
     * {@code PIC X(10)} target. The 26-character TRAN-ORIG-TS layout is
     * {@code "yyyy-MM-dd HH:mm:ss.SSSSSS"}, so the leading 10 characters
     * are the date portion in ISO format. The Java translation simply
     * formats the {@link LocalDateTime} with the {@code yyyy-MM-dd}
     * pattern, yielding identical output.
     *
     * <p>A {@code null} input (an all-spaces TRAN-ORIG-TS sentinel) is
     * rendered as the empty {@link String} so the SEND-MAP adapter can
     * space-pad to the {@code PIC X(10)} width.
     *
     * @param ts the timestamp to format, may be {@code null}
     * @return the 10-character YYYY-MM-DD representation (or {@code ""}
     *         if {@code ts} is {@code null}); never {@code null}
     */
    static String formatTimestampField(LocalDateTime ts) {
        if (ts == null) {
            return "";
        }
        return DATE_YYYY_MM_DD.format(ts);
    }

    /**
     * Masks a Primary Account Number (PAN) for log output per AAP
     * &sect;0.7.2: all but the last 4 digits are replaced with
     * asterisks ({@code *}). Used exclusively for log records, NEVER
     * for BMS output (the {@code CARDNUMO} field on the screen displays
     * the full PAN exactly as in the legacy COBOL implementation).
     *
     * <p>Edge cases:
     * <ul>
     *   <li>{@code null} &rarr; {@code "****"}</li>
     *   <li>length &lt; 4 &rarr; {@code "****"}</li>
     *   <li>length == 4 &rarr; {@code "****" + value} (12 asterisks
     *       plus the value when value is shorter than 4)</li>
     *   <li>length &gt; 4 &rarr; {@code "*".repeat(length-4) + last4digits}</li>
     * </ul>
     *
     * @param pan the PAN to mask, may be {@code null}
     * @return the masked string (never {@code null})
     */
    static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        int prefixLen = pan.length() - 4;
        return "*".repeat(prefixLen) + pan.substring(prefixLen);
    }

    /**
     * Truncates {@code value} to at most {@code max} characters, taking the
     * leading slice. Returns the input unchanged when already short enough.
     * Mirrors COBOL {@code MOVE} semantics for unsigned alphanumeric fields
     * where a longer sending field is truncated on the right.
     *
     * @param value the string to truncate (never {@code null}; callers pass
     *              the result of {@link #orEmpty(String)})
     * @param max   the maximum length (must be {@code >= 0})
     * @return either {@code value} or {@code value.substring(0, max)};
     *         never {@code null}
     */
    static String truncateTo(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * Returns {@code s} or the empty string if {@code s} is {@code null}.
     * Used to satisfy the {@link CoTrn01Output} canonical constructor's
     * non-{@code null} expectation without changing length semantics.
     *
     * @param s the input, may be {@code null}
     * @return {@code s} if non-{@code null}; otherwise {@code ""}
     */
    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ============================================================================
    // Commarea-mutation helpers (translate to_program / from_program / context).
    // ============================================================================

    /**
     * Constructs the outbound commarea for an XCTL: sets
     * {@code CDEMO-TO-PROGRAM} to the supplied target,
     * {@code CDEMO-FROM-TRANID} to {@code "CT01"},
     * {@code CDEMO-FROM-PROGRAM} to {@code "COTRN01C"}, and
     * {@code CDEMO-PGM-CONTEXT} to {@link PgmContext#ENTER}.
     *
     * <p>Equivalent to the COBOL pattern at
     * {@code app/cbl/COTRN01C.cbl:L197-L208} (the RETURN-TO-PREV-SCREEN
     * paragraph). All other commarea fields (userId, userType, and the
     * non-general extensions) are preserved unchanged.
     *
     * @param current   the current commarea (never {@code null})
     * @param toProgram the 8-character target program name (e.g.
     *                  {@code "COTRN00C"}, {@code "COMEN01C"},
     *                  {@code "COSGN00C"}); will be space-padded to
     *                  exactly 8 characters
     * @return a fresh {@link CardDemoCommarea} with the routing fields
     *         updated and all other fields preserved
     */
    private static CardDemoCommarea buildXctlCommarea(
            CardDemoCommarea current, String toProgram) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(toProgram, "toProgram");
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padExact(TRANSACTION_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padExact(PROGRAM_NAME, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                gi.toTranId(),
                padExact(toProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a commarea identical to {@code current} except with
     * {@code CDEMO-PGM-CONTEXT} set to {@code newContext}. Used by the
     * first-entry branch of {@link #handle(CoTrn01Input, CardDemoCommarea, AidKey)}
     * to flip the context from {@link PgmContext#ENTER} to
     * {@link PgmContext#REENTER}.
     *
     * @param current    the current commarea (never {@code null})
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
     *   <li>COBOL 88-level switches ({@code ERR-FLG-ON},
     *       {@code USR-MODIFIED-NO}) &rarr; boolean fields.</li>
     *   <li>COBOL working-storage scalars ({@code WS-MESSAGE}) &rarr;
     *       {@link String} field.</li>
     *   <li>COBOL row-staging variables (TRNIDI, CARDNUMI, TTYPCDI,
     *       TCATCDI, TRNSRCI, TRNAMTI, TDESCI, TORIGDTI, TPROCDTI, MIDI,
     *       MNAMEI, MCITYI, MZIPI) &rarr; individual {@link String}
     *       fields named {@code outXxx} (the 'I' suffix denotes the
     *       symbolic-map input view, but the program writes to those
     *       same fields and they appear on the output side via the
     *       {@code COTRN1AO REDEFINES COTRN1AI} aliasing).</li>
     *   <li>The fetched {@link TranRecord} (passed between
     *       READ-TRANSACT-FILE and the field-projection loop in
     *       PROCESS-ENTER-KEY).</li>
     * </ul>
     *
     * <p>An instance is allocated fresh at the top of every
     * {@link #handle(CoTrn01Input, CardDemoCommarea, AidKey)} call to
     * guarantee thread isolation; the class is package-private to permit
     * unit-test visibility but is never exposed via the public API.
     */
    static final class MutableState {

        /** {@code ERR-FLG-ON} 88-level: {@code 'Y'} when an error has occurred. */
        boolean errFlgOn;

        /**
         * {@code WS-MESSAGE PIC X(80) VALUE SPACES}. The verbatim error
         * message that will be moved to {@code ERRMSGO} of {@code COTRN1AO}
         * by SEND-TRNVIEW-SCREEN. Initialized to empty per COBOL line 38
         * and re-initialized to empty in MAIN-PARA at line 91.
         */
        String wsMessage = "";

        /**
         * The {@link CoTrn01Input} the user submitted (or
         * {@link CoTrn01Input#empty()} on first entry).
         */
        CoTrn01Input input;

        /**
         * The {@link CardDemoCommarea} as it stands at the start of the
         * invocation; mutated via {@link CardDemoCommarea#withCdemoGeneralInfo}
         * to record program-context transitions, and ultimately returned
         * in the {@link Outcome}.
         */
        CardDemoCommarea commarea;

        /**
         * The {@link AidKey} the user pressed; inspected by
         * {@link #handle(CoTrn01Input, CardDemoCommarea, AidKey)}'s
         * exhaustive switch.
         */
        AidKey aidKey;

        /**
         * The fetched {@link TranRecord} populated by
         * {@link #readTransactFile(MutableState, String)} on a successful
         * {@code DFHRESP(NORMAL)}; {@code null} when no record has been
         * read or when an error occurred.
         */
        TranRecord tranRecord;

        // -- Output-staging fields (one per COTRN1A display leaf) --------

        /** {@code TRNIDI} output stage (PIC X(16)). */
        String outTranId = "";

        /** {@code CARDNUMI} output stage (PIC X(16)) &mdash; full PAN. */
        String outCardNum = "";

        /** {@code TTYPCDI} output stage (PIC X(2)). */
        String outTypeCode = "";

        /** {@code TCATCDI} output stage (PIC X(4)). */
        String outCategoryCode = "";

        /** {@code TRNSRCI} output stage (PIC X(10)). */
        String outSource = "";

        /** {@code TDESCI} output stage (PIC X(60); truncated from PIC X(100)). */
        String outDescription = "";

        /** {@code TRNAMTI} output stage (PIC X(12), formatted as +99999999.99). */
        String outAmount = "";

        /** {@code TORIGDTI} output stage (PIC X(10), YYYY-MM-DD truncation). */
        String outOrigDate = "";

        /** {@code TPROCDTI} output stage (PIC X(10), YYYY-MM-DD truncation). */
        String outProcDate = "";

        /** {@code MIDI} output stage (PIC X(9), zero-padded). */
        String outMerchantId = "";

        /** {@code MNAMEI} output stage (PIC X(30); truncated from PIC X(50)). */
        String outMerchantName = "";

        /** {@code MCITYI} output stage (PIC X(25); truncated from PIC X(50)). */
        String outMerchantCity = "";

        /** {@code MZIPI} output stage (PIC X(10)). */
        String outMerchantZip = "";
    }
}

