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
package com.blitzy.carddemo.application.card;

// JEP 511 (finalized in Java 25): Module Import Declarations.
// `import module java.base` brings in all packages exported by the
// java.base module, including java.lang, java.util, java.time, and
// java.time.format — exactly the surfaces needed by this read-only
// card-view program. Per AAP §0.7.3, this is the idiomatic choice for
// files that touch many java.* packages.
import module java.base;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of COBOL program {@code COCRDSLC}
 * ({@code app/cbl/COCRDSLC.cbl}, CICS transaction {@code CCDL}).
 *
 * <p><b>Purpose:</b> Card-view detail online transaction. Displays a single
 * credit card's details &mdash; embossed cardholder name, active status, and
 * expiration month/year &mdash; for a given account-id + card-number pair.
 * Strictly read-only.
 *
 * <h2>Two read paths</h2>
 * <ul>
 *   <li>{@code 9100-GETCARD-BYACCTCARD}: primary-key read on
 *       {@link #LIT_CARD_FILE} (CARDDAT) by 16-digit card number. Used when
 *       both the account-id and card-number filters are supplied.</li>
 *   <li>{@code 9150-GETCARD-BYACCT}: alternate-index read on
 *       {@link #LIT_CARD_FILE_ACCT_PATH} (CARDAIX) by 11-digit account-id;
 *       the resolved {@code XREF-CARD-NUM} is then used to fetch the card
 *       record from CARDDAT. Used when only the account-id filter is
 *       supplied (card-number is optional on the view screen).</li>
 * </ul>
 *
 * <p><b>COBOL fidelity note:</b> In the source COBOL, paragraph
 * {@code 9000-READ-DATA} only invokes {@code 9100-GETCARD-BYACCTCARD};
 * {@code 9150-GETCARD-BYACCT} is defined but unreferenced from the main
 * read path. Per the schema mandate for this Java translation, both
 * read paths are wired and selected based on which input filters are
 * supplied, giving users meaningful behaviour when supplying account only.
 * This is documented in {@code java/MIGRATION_NOTES.md} as a translation
 * decision.
 *
 * <h2>Paragraph-to-method mapping</h2>
 * <table>
 *   <caption>COBOL paragraph &rarr; Java method</caption>
 *   <tr><td>{@code 0000-MAIN}</td><td>{@link #execute(CardDemoCommarea, AidKey, CoCrdSlInput)} / {@link #mainEntry(CardDemoCommarea, AidKey, CoCrdSlInput)}</td></tr>
 *   <tr><td>{@code 1000-SEND-MAP}</td><td>{@link #sendScreen(MutableState)}</td></tr>
 *   <tr><td>{@code 1200-SETUP-SCREEN-VARS}</td><td>{@link #setupScreenVars(MutableState, CardRecord)}</td></tr>
 *   <tr><td>{@code 2000-PROCESS-INPUTS}</td><td>{@link #processInputs(CoCrdSlInput, MutableState)}</td></tr>
 *   <tr><td>{@code 2100-RECEIVE-MAP}</td><td>{@link #receiveMap(CoCrdSlInput, MutableState)}</td></tr>
 *   <tr><td>{@code 2200-EDIT-MAP-INPUTS}</td><td>{@link #editMapInputs(MutableState)}</td></tr>
 *   <tr><td>{@code 2210-EDIT-ACCOUNT}</td><td>{@link #editAccount(MutableState)}</td></tr>
 *   <tr><td>{@code 2220-EDIT-CARD}</td><td>{@link #editCard(MutableState)}</td></tr>
 *   <tr><td>{@code 9000-READ-DATA}</td><td>{@link #readData(MutableState)}</td></tr>
 *   <tr><td>{@code 9100-GETCARD-BYACCTCARD}</td><td>{@link #getCardByAcctCard(MutableState)}</td></tr>
 *   <tr><td>{@code 9150-GETCARD-BYACCT}</td><td>{@link #getCardByAcct(MutableState)}</td></tr>
 * </table>
 *
 * <h2>Verbatim error messages preserved per AAP &sect;0.7.1</h2>
 * <ul>
 *   <li>{@code "Account number not provided"} (88-level {@code WS-PROMPT-FOR-ACCT})</li>
 *   <li>{@code "Card number not provided"} (88-level {@code WS-PROMPT-FOR-CARD})</li>
 *   <li>{@code "No input received"} (88-level {@code NO-SEARCH-CRITERIA-RECEIVED})</li>
 *   <li>{@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"} (literal
 *       MOVE'd at {@code COCRDSLC.cbl} line 670; comma-no-space is intentional)</li>
 *   <li>{@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"} (literal
 *       MOVE'd at {@code COCRDSLC.cbl} line 711; comma-no-space is intentional)</li>
 *   <li>{@code "Did not find this account in cards database"}
 *       (88-level {@code DID-NOT-FIND-ACCT-IN-CARDXREF})</li>
 *   <li>{@code "Did not find cards for this search condition"}
 *       (88-level {@code DID-NOT-FIND-ACCTCARD-COMBO})</li>
 *   <li>{@code "Error reading Card Data File"} (88-level {@code XREF-READ-ERROR})</li>
 *   <li>{@code "PF03 pressed.Exiting              "} (88-level
 *       {@code WS-EXIT-MESSAGE}; no space after the period, 14 trailing spaces)</li>
 * </ul>
 *
 * <h2>AID-key dispatch</h2>
 * <p>The {@link Outcome} sealed interface replaces COBOL CICS verbs:
 * {@link Outcome.SendMap} represents {@code EXEC CICS SEND MAP} +
 * {@code EXEC CICS RETURN}, and {@link Outcome.Xctl} represents
 * {@code EXEC CICS XCTL}. The exhaustive pattern-matching switch over
 * the sixteen {@link AidKey} permits enforces compile-time completeness
 * (no {@code default} branch, per AAP &sect;0.6.7).
 *
 * <h2>Security &mdash; PAN masking</h2>
 * <p>Per AAP &sect;0.7.2: no card PAN may be logged in full. The
 * {@link #maskPan(String)} helper masks all but the last 4 digits and
 * is used for every log statement that touches a 16-digit card number.
 */
@CobolProgram(
        value = "COCRDSLC",
        sourcePath = "app/cbl/COCRDSLC.cbl",
        translationDate = "2025-09-16",
        notes = "Card view detail online; READ-ONLY display of card data. " +
                "Two read paths: 9100 (by account+card primary key on CARDDAT) " +
                "and 9150 (by account via CARDAIX alternate index). " +
                "Account/card keys are PROTECTED when the program was XCTL'd " +
                "from COCRDLIC; otherwise UNPROTECTED for user input. " +
                "Valid AID keys: ENTER (re-read), PF03 (exit). Any other key " +
                "yields 'Invalid key pressed' error and re-display."
)
public final class CoCrdSlC {

    // ----- Public WS-LITERALS (per AAP §0.4.1 schema mandate) ---------------
    //
    // Every constant below corresponds to a 05-level WS-LITERALS field in
    // COCRDSLC.cbl (lines 196-228). They are exposed publicly so test
    // harnesses, sibling application classes, and the composition root can
    // reference the canonical COBOL literal values without re-declaring them.

    /** {@code LIT-THISPGM VALUE 'COCRDSLC'} ({@code COCRDSLC.cbl} line 197). */
    public static final String LIT_THIS_PGM = "COCRDSLC";

    /** {@code LIT-THISTRANID VALUE 'CCDL'} ({@code COCRDSLC.cbl} line 199). */
    public static final String LIT_THIS_TRAN_ID = "CCDL";

    /** {@code LIT-THISMAPSET VALUE 'COCRDSL '} ({@code COCRDSLC.cbl} line 201). */
    public static final String LIT_THIS_MAPSET = "COCRDSL";

    /** {@code LIT-THISMAP VALUE 'CCRDSLA'} ({@code COCRDSLC.cbl} line 203). */
    public static final String LIT_THIS_MAP = "CCRDSLA";

    /** {@code LIT-CCLISTPGM VALUE 'COCRDLIC'} ({@code COCRDSLC.cbl} line 205). */
    public static final String LIT_CCLIST_PGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID VALUE 'CCLI'} ({@code COCRDSLC.cbl} line 207). */
    public static final String LIT_CCLIST_TRAN_ID = "CCLI";

    /** {@code LIT-CCLISTMAPSET VALUE 'COCRDLI'} ({@code COCRDSLC.cbl} line 209). */
    public static final String LIT_CCLIST_MAPSET = "COCRDLI";

    /**
     * {@code LIT-CCLISTMAP VALUE 'CCRDSLA'} ({@code COCRDSLC.cbl} line 211).
     * NOTE: this is the SAME 7-char value as {@link #LIT_THIS_MAP} (verbatim
     * from COBOL source &mdash; not a transcription error).
     */
    public static final String LIT_CCLIST_MAP = "CCRDSLA";

    /** {@code LIT-MENUPGM VALUE 'COMEN01C'} ({@code COCRDSLC.cbl} line 213). */
    public static final String LIT_MENU_PGM = "COMEN01C";

    /** {@code LIT-MENUTRANID VALUE 'CM00'} ({@code COCRDSLC.cbl} line 215). */
    public static final String LIT_MENU_TRAN_ID = "CM00";

    /** {@code LIT-MENUMAPSET VALUE 'COMEN01'} ({@code COCRDSLC.cbl} line 217). */
    public static final String LIT_MENU_MAPSET = "COMEN01";

    /** {@code LIT-MENUMAP VALUE 'COMEN1A'} ({@code COCRDSLC.cbl} line 219). */
    public static final String LIT_MENU_MAP = "COMEN1A";

    /** {@code LIT-CARDFILENAME VALUE 'CARDDAT '} ({@code COCRDSLC.cbl} line 221). */
    public static final String LIT_CARD_FILE = "CARDDAT";

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH VALUE 'CARDAIX '}
     * ({@code COCRDSLC.cbl} line 223). The AIX (alternate index) over CARDDAT
     * keyed by account-id; used by paragraph {@code 9150-GETCARD-BYACCT}.
     */
    public static final String LIT_CARD_FILE_ACCT_PATH = "CARDAIX";

    /**
     * Function-key legend rendered on the BMS bottom-row {@code FKEYSO} field.
     * The literal is taken from {@code app/bms/COCRDSL.bms} line 152
     * (the {@code INITIAL='ENTER=Search Cards  F3=Exit'} clause), space-padded
     * to PIC X(75).
     */
    public static final String FKEYS_LEGEND =
            "ENTER=Search Cards  F3=Exit                                                ";

    // ----- Private verbatim message constants (per AAP §0.7.1) --------------
    //
    // Source: COCRDSLC.cbl 88-level VALUE clauses on WS-RETURN-MSG (lines
    // 135-188) and the literal MOVE statements in 2210-EDIT-ACCOUNT / 2220-
    // EDIT-CARD. Reproduced verbatim including the uppercase + comma-no-space
    // idiosyncrasies and trailing-space artifacts.

    /**
     * {@code WS-PROMPT-FOR-INPUT VALUE 'Please enter Account and Card Number'}
     * ({@code COCRDSLC.cbl} line 133). Shown on the BMS info-msg field
     * ({@code INFOMSGO}) when the user first lands on the screen without
     * having supplied any keys.
     */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /**
     * {@code FOUND-CARDS-FOR-ACCOUNT VALUE '   Displaying requested details'}
     * ({@code COCRDSLC.cbl} line 131). NOTE: the three leading spaces are
     * INTENTIONAL and preserved verbatim per AAP &sect;0.7.1. Modifying
     * them changes observable output.
     */
    private static final String MSG_FOUND_CARDS = "   Displaying requested details";

    /** {@code WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'} ({@code COCRDSLC.cbl} line 141). */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD VALUE 'Card number not provided'} ({@code COCRDSLC.cbl} line 143). */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'} ({@code COCRDSLC.cbl} line 145). */
    private static final String MSG_NO_INPUT = "No input received";

    /**
     * Literal MOVE'd at {@code COCRDSLC.cbl} line 670 in paragraph
     * {@code 2210-EDIT-ACCOUNT}. NOTE: the uppercase letters and the
     * comma-without-space between {@code FILTER} and {@code IF} are
     * INTENTIONAL and preserved verbatim per AAP &sect;0.7.1.
     */
    private static final String MSG_ACCT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Literal MOVE'd at {@code COCRDSLC.cbl} line 711 in paragraph
     * {@code 2220-EDIT-CARD}. NOTE: the uppercase letters and the
     * comma-without-space between {@code FILTER} and {@code IF} are
     * INTENTIONAL and preserved verbatim per AAP &sect;0.7.1.
     */
    private static final String MSG_CARD_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF VALUE 'Did not find this account in cards database'} ({@code COCRDSLC.cbl} line 152). */
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in cards database";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO VALUE 'Did not find cards for this search condition'} ({@code COCRDSLC.cbl} line 154). */
    private static final String MSG_NOT_FOUND_COMBO = "Did not find cards for this search condition";

    /** {@code XREF-READ-ERROR VALUE 'Error reading Card Data File'} ({@code COCRDSLC.cbl} line 156). */
    private static final String MSG_READ_ERROR = "Error reading Card Data File";

    /**
     * {@code WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '}
     * ({@code COCRDSLC.cbl} line 139). NOTE: no space after the period,
     * 14 trailing spaces &mdash; verbatim per AAP &sect;0.7.1.
     */
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              ";

    // ----- Static formatters and patterns -----------------------------------

    /**
     * BMS-display date formatter; mirrors COBOL {@code WS-CURDATE-MM-DD-YY}
     * built in 1100-SCREEN-INIT (lines 427-435 of {@code COCRDSLC.cbl}).
     * Locale pinned to {@link Locale#US} so the output does not vary with
     * the JVM default locale.
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US);

    /**
     * BMS-display time formatter; mirrors COBOL {@code WS-CURTIME-HH-MM-SS}
     * built in 1100-SCREEN-INIT (lines 437-445 of {@code COCRDSLC.cbl}).
     * Locale pinned to {@link Locale#US}.
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);

    /**
     * Regex matching strictly ASCII digits. Used by {@link #isNumeric(String)}
     * to translate the COBOL {@code IS NUMERIC} test.
     */
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    /** SLF4J logger for this program. */
    private static final Logger log = LoggerFactory.getLogger(CoCrdSlC.class);

    // ----- Injected collaborators (constructor injection) -------------------

    private final CardRepository cardRepository;
    private final CardXrefRepository cardXrefRepository;
    private final ProgramRegistry programRegistry;

    /**
     * Construct a {@code CoCrdSlC} with the required collaborator ports.
     *
     * @param cardRepository     port for reading CARDDAT
     *                           (paragraph {@code 9100-GETCARD-BYACCTCARD})
     * @param cardXrefRepository port for reading the CARDAIX alternate
     *                           index (paragraph {@code 9150-GETCARD-BYACCT})
     * @param programRegistry    dynamic-CALL routing facility; carried as a
     *                           collaborator so callers (or future dispatch
     *                           code) can resolve target programs by name
     *                           after this method returns {@link Outcome.Xctl}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoCrdSlC(CardRepository cardRepository,
                    CardXrefRepository cardXrefRepository,
                    ProgramRegistry programRegistry) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "cardXrefRepository");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    // ----- Outcome (sealed return value) ------------------------------------

    /**
     * Sealed return value of {@link #execute(CardDemoCommarea, AidKey,
     * CoCrdSlInput)}. Replaces the COBOL CICS terminal verbs:
     * <ul>
     *   <li>{@link SendMap} corresponds to {@code EXEC CICS SEND MAP}
     *       followed by {@code EXEC CICS RETURN TRANSID(CCDL)
     *       COMMAREA(WS-COMMAREA)}. The caller is expected to render the
     *       supplied {@link CoCrdSlOutput} on the BMS screen and route the
     *       next user input back into {@link #execute(CardDemoCommarea,
     *       AidKey, CoCrdSlInput)} with the new state.</li>
     *   <li>{@link Xctl} corresponds to {@code EXEC CICS XCTL
     *       PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)}. The
     *       caller is expected to dispatch through the program registry to
     *       the supplied target program, carrying the supplied commarea.</li>
     * </ul>
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Outcome variant for {@code EXEC CICS SEND MAP} + {@code EXEC CICS
         * RETURN}. Carries the populated BMS output record and the
         * updated commarea.
         *
         * @param output   the populated {@link CoCrdSlOutput} to send on the
         *                 BMS screen; never {@code null}
         * @param commarea the updated commarea to round-trip back into the
         *                 caller; never {@code null}
         */
        record SendMap(CoCrdSlOutput output, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing both arguments are non-null.
             *
             * @throws NullPointerException if {@code output} or
             *                              {@code commarea} is {@code null}
             */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * Outcome variant for {@code EXEC CICS XCTL}. Carries the target
         * program-id (an 8-character left-justified COBOL program name) and
         * the updated commarea.
         *
         * @param targetProgram the destination program-id (e.g.
         *                      {@code "COCRDLIC"} or {@code "COMEN01C"});
         *                      never {@code null} or blank
         * @param commarea      the commarea to pass to the target program;
         *                      never {@code null}
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing both arguments are non-null and
             * that {@code targetProgram} is not blank.
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
                    throw new IllegalArgumentException("targetProgram must not be blank");
                }
            }
        }
    }

    // ----- Mutable working state (translated from WS-MISC-STORAGE) ----------

    /**
     * Mutable container mirroring the COBOL {@code WS-MISC-STORAGE} group
     * fields used across paragraphs to coordinate per-field validity flags,
     * the return message, and the screen-display variables. Local to a
     * single {@link #execute} invocation and never shared across threads
     * (no {@code ThreadLocal} per AAP &sect;0.7.4).
     */
    private static final class MutableState {

        /**
         * Tri-state per-field validity flag mirroring COBOL 88-level
         * conditions {@code FLG-ACCTFILTER-ISVALID / FLG-ACCTFILTER-BLANK /
         * FLG-ACCTFILTER-NOT-OK} (and the corresponding card-filter trio).
         */
        enum ValidityFlag { OK, NOT_OK, BLANK }

        /** {@code FLG-ACCTFILTER-...} flag (default BLANK before validation). */
        ValidityFlag acctFlag = ValidityFlag.BLANK;

        /** {@code FLG-CARDFILTER-...} flag (default BLANK before validation). */
        ValidityFlag cardFlag = ValidityFlag.BLANK;

        // ---- Inputs (account and card search keys) -------------------------

        /** Account-id search filter (CC-ACCT-ID equivalent). */
        String acctSid = "";

        /** Card-number search filter (CC-CARD-NUM equivalent). */
        String cardSid = "";

        // ---- Card data fetched and projected onto the BMS screen ----------

        /** Display value for the {@code ACCTSIDO} BMS field (PIC X(11)). */
        String displayAcctId = "";

        /** Display value for the {@code CARDSIDO} BMS field (PIC X(16)). */
        String displayCardNum = "";

        /** Display value for the {@code CRDNAMEO} BMS field (PIC X(50)). */
        String displayCardName = "";

        /** Display value for the {@code CRDSTCDO} BMS field (PIC X(1)). */
        String displayCardStatus = "";

        /** Display value for the {@code EXPMONO} BMS field (PIC X(2)). */
        String displayExpMon = "";

        /** Display value for the {@code EXPYEARO} BMS field (PIC X(4)). */
        String displayExpYear = "";

        // ---- Messages ------------------------------------------------------

        /** Informational message ({@code INFOMSGO} on the BMS screen). */
        String infoMsg = "";

        /** Error message ({@code ERRMSGO} on the BMS screen). */
        String errorMsg = "";

        // ---- State flags ---------------------------------------------------

        /** {@code true} when invoking program was {@code COCRDLIC} (LIT-CCLISTPGM). */
        boolean fromList = false;

        /** {@code WS-EDIT-VARIABLE-FLAGS / INPUT-OK / INPUT-ERROR}. */
        boolean inputError = false;

        /** {@code true} on a CARDDAT/CARDAIX read error (file I/O failure). */
        boolean readError = false;

        // ---- Header (chrome) ----------------------------------------------

        /** Current date string in the COBOL {@code MM/DD/YY} format. */
        String curDate = "";

        /** Current time string in the COBOL {@code HH:MM:SS} format. */
        String curTime = "";
    }

    // ----- Public entry point (0000-MAIN translation) -----------------------

    /**
     * Execute one invocation of {@code COCRDSLC}. Public entry that
     * translates COBOL paragraph {@code 0000-MAIN} (lines 248-408 of
     * {@code COCRDSLC.cbl}). Dispatches based on the supplied
     * {@link AidKey} and the carried-forward {@link PgmContext}.
     *
     * <p>First-time entry detection: if the inbound commarea's
     * {@code CDEMO-PGM-CONTEXT} is {@link PgmContext.Enter}, this is a
     * first-time entry; otherwise it is a re-entry.
     *
     * <p>The {@link AidKey} parameter is the canonical sealed AID-key value
     * decoded from the BMS terminal-input EIBAID byte by an entry-contract
     * decoder; it is the single source of truth for the {@code EVALUATE
     * TRUE} dispatch on the WHEN clauses {@code CCARD-AID-PFK03},
     * {@code CCARD-AID-ENTER}, and the fall-through (invalid key) branch.
     *
     * @param commareaIn the inbound DFHCOMMAREA; never {@code null}
     * @param aidKey     the CICS AID-key pressed by the terminal user;
     *                   never {@code null}
     * @param input      the BMS map input record; never {@code null}
     * @return one of {@link Outcome.SendMap} or {@link Outcome.Xctl}; never
     *         {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdSlInput input) {
        Objects.requireNonNull(commareaIn, "commareaIn");
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");
        return mainEntry(commareaIn, aidKey, input);
    }

    /**
     * Translation of COBOL paragraph {@code 0000-MAIN}. Performs:
     * <ol>
     *   <li>Initialize {@link MutableState} from inbound commarea, including
     *       header chrome (date/time) and first-entry-vs-re-entry detection
     *       via {@link PgmContext}.</li>
     *   <li>Handle {@link AidKey.PfKey03 PF03} exit (XCTL routing to either
     *       LIT-CCLISTPGM when the user arrived from the card list, or
     *       LIT-MENUPGM otherwise).</li>
     *   <li>On first entry: seed account/card keys from the inbound
     *       {@code CDEMO-ACCOUNT-INFO} and {@code CDEMO-CARD-INFO} groups
     *       (when populated by an upstream XCTL such as COCRDLIC).</li>
     *   <li>On re-entry: receive the user-entered values via
     *       {@link #receiveMap(CoCrdSlInput, MutableState)} (COBOL paragraph
     *       2100-RECEIVE-MAP).</li>
     *   <li>For {@link AidKey.Enter ENTER} or first-entry: validate
     *       (2200-EDIT-MAP-INPUTS, 2210-EDIT-ACCOUNT, 2220-EDIT-CARD),
     *       read CARDDAT (9000-READ-DATA), then send the result screen.</li>
     *   <li>For any other AID key: display the "Invalid key pressed"
     *       message and re-send the screen.</li>
     * </ol>
     *
     * <p>The exhaustive pattern-matching switch over all 16 {@link AidKey}
     * permits enforces compile-time completeness; no {@code default} branch
     * is permitted (AAP &sect;0.6.7).
     */
    private Outcome mainEntry(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdSlInput input) {
        MutableState s = new MutableState();
        CardDemoCommarea commarea = commareaIn;

        // ---- 1100-SCREEN-INIT (lines 427-453): set MM/DD/YY and HH:MM:SS ----
        LocalDateTime now = LocalDateTime.now();
        s.curDate = DATE_FORMATTER.format(now);
        s.curTime = TIME_FORMATTER.format(now);

        // ---- Determine invoking-program lineage (CDEMO-FROM-PROGRAM) -------
        // COBOL: WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL
        // LIT-CCLISTPGM means the user arrived from COCRDLIC with the keys
        // already pre-validated; PF3 must return to that program.
        String fromProgram = orEmpty(commarea.cdemoGeneralInfo().fromProgram()).trim();
        s.fromList = LIT_CCLIST_PGM.equals(fromProgram);

        boolean firstEntry = commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Enter;

        if (log.isDebugEnabled()) {
            log.debug("CoCrdSlC.execute entered: aidKey={} pgmContext={} fromProgram={}",
                    aidKey.mnemonic(),
                    commarea.cdemoGeneralInfo().pgmContext().getClass().getSimpleName(),
                    fromProgram);
        }

        // ---- WHEN CCARD-AID-PFK03 (line 305 of COCRDSLC.cbl) --------------
        // PF3 exits to either LIT-CCLISTPGM (when fromList) or LIT-MENUPGM.
        if (aidKey instanceof AidKey.PfKey03) {
            return handlePfk03Exit(commarea, s);
        }

        // ---- Seed / receive inputs based on entry mode --------------------
        if (firstEntry) {
            // COBOL 0000-MAIN lines 339-346: WHEN CDEMO-PGM-ENTER AND
            // CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM, MOVE CDEMO-ACCT-ID TO
            // CC-ACCT-ID-N and MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N.
            seedKeysFromCommarea(commarea, s);
        } else {
            // Re-entry: pull the values the user typed on the BMS screen.
            receiveMap(input, s);
        }

        // ---- ENTER (or first-entry) processing path -----------------------
        // ENTER triggers full validate + read + display.
        // First-entry without ENTER (i.e., arrival via direct invocation
        // without an AID press) also falls through this path so the initial
        // screen is properly populated when keys are already seeded.
        if (aidKey instanceof AidKey.Enter || firstEntry) {
            return processEnter(commarea, s);
        }

        // ---- WHEN OTHER (lines 395-401 of COCRDSLC.cbl) -------------------
        // Any other AID key is "invalid" for this transaction. The COBOL
        // source coerces invalid keys to ENTER via YYYY-STORE-PFKEY, but the
        // Java translation reports the invalid-key state explicitly per
        // schema mandate. The exhaustive switch covers all 16 AidKey permits
        // (the 14 not handled above: Clear, Pa1, Pa2, and PfKey01-02, 04-12).
        return switch (aidKey) {
            // Already handled above; these are unreachable, but the pattern
            // switch must be exhaustive over the sealed AidKey hierarchy.
            case AidKey.Enter _ -> new Outcome.SendMap(sendScreen(s), markReenter(commarea));
            case AidKey.PfKey03 _ -> handlePfk03Exit(commarea, s);
            // Any of the remaining 14 permits: display invalid-key error.
            case AidKey.Clear _,
                 AidKey.Pa1 _,
                 AidKey.Pa2 _,
                 AidKey.PfKey01 _,
                 AidKey.PfKey02 _,
                 AidKey.PfKey04 _,
                 AidKey.PfKey05 _,
                 AidKey.PfKey06 _,
                 AidKey.PfKey07 _,
                 AidKey.PfKey08 _,
                 AidKey.PfKey09 _,
                 AidKey.PfKey10 _,
                 AidKey.PfKey11 _,
                 AidKey.PfKey12 _ -> {
                s.errorMsg = SystemMessages.INVALID_KEY_MSG;
                log.info("CoCrdSlC: invalid AID key {} pressed; displaying invalid-key message",
                        aidKey.mnemonic());
                yield new Outcome.SendMap(sendScreen(s), markReenter(commarea));
            }
        };
    }

    /**
     * Translation of the WHEN CCARD-AID-PFK03 branch of {@code 0000-MAIN}
     * (lines 305-330 of {@code COCRDSLC.cbl}). XCTL routing target is:
     * <ul>
     *   <li>{@link #LIT_CCLIST_PGM} (COCRDLIC) when the user arrived from
     *       the card list (i.e., {@code fromList} is {@code true}).</li>
     *   <li>{@link #LIT_MENU_PGM} (COMEN01C) otherwise.</li>
     * </ul>
     * The PF03 exit message is set onto the commarea's
     * {@code CCARD-ERROR-MSG} surface for any downstream display (translated
     * to {@link MutableState#errorMsg} although Outcome.Xctl does not carry
     * an output record).
     */
    private Outcome handlePfk03Exit(CardDemoCommarea commarea, MutableState s) {
        s.errorMsg = MSG_PF03_EXIT;
        String target = s.fromList ? LIT_CCLIST_PGM : LIT_MENU_PGM;

        // COBOL: MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID,
        //        MOVE LIT-THISPGM   TO CDEMO-FROM-PROGRAM,
        //        SET  CDEMO-PGM-ENTER TO TRUE
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padOrTruncate(LIT_THIS_TRAN_ID, gi.fromTranId().length()),
                padOrTruncate(LIT_THIS_PGM, gi.fromProgram().length()),
                gi.toTranId(),
                padOrTruncate(target, gi.toProgram().length()),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea outbound = commarea.withCdemoGeneralInfo(updated);

        // COBOL: MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET,
        //        MOVE LIT-THISMAP    TO CDEMO-LAST-MAP
        CardDemoCommarea.CdemoMoreInfo mi = outbound.cdemoMoreInfo();
        CardDemoCommarea.CdemoMoreInfo miUpdated = new CardDemoCommarea.CdemoMoreInfo(
                padOrTruncate(LIT_THIS_MAP, mi.lastMap().length()),
                padOrTruncate(LIT_THIS_MAPSET, mi.lastMapset().length()));
        outbound = outbound.withCdemoMoreInfo(miUpdated);

        log.info("CoCrdSlC PF03 exit: XCTL to {}", target);
        return new Outcome.Xctl(target, outbound);
    }

    /**
     * Translation of the {@code WHEN CCARD-AID-ENTER} (re-validate +
     * re-read + re-display) branch of {@code 0000-MAIN}. Performs:
     * <ol>
     *   <li>2200-EDIT-MAP-INPUTS / 2210-EDIT-ACCOUNT / 2220-EDIT-CARD</li>
     *   <li>If input error: send the prompt screen with the error.</li>
     *   <li>Else: 9000-READ-DATA (which dispatches to 9100 or 9150 based on
     *       which input filters were supplied).</li>
     *   <li>1000-SEND-MAP: build and return the result screen.</li>
     * </ol>
     */
    private Outcome processEnter(CardDemoCommarea commarea, MutableState s) {
        editMapInputs(s);
        if (s.inputError) {
            return new Outcome.SendMap(sendScreen(s), markReenter(commarea));
        }

        Optional<CardRecord> maybe = readData(s);
        if (maybe.isPresent()) {
            setupScreenVars(s, maybe.get());
            s.infoMsg = MSG_FOUND_CARDS;
        } else if (s.readError) {
            s.errorMsg = MSG_READ_ERROR;
        } else {
            // Choose the appropriate not-found message:
            //   - account-only search via AIX → DID-NOT-FIND-ACCT-IN-CARDXREF
            //   - account+card search via primary key → DID-NOT-FIND-ACCTCARD-COMBO
            s.errorMsg = (s.cardFlag == MutableState.ValidityFlag.OK)
                    ? MSG_NOT_FOUND_COMBO
                    : MSG_NOT_FOUND_ACCT;
        }
        return new Outcome.SendMap(sendScreen(s), markReenter(commarea));
    }



    // ----- Input seeding and receive ----------------------------------------

    /**
     * Translation of the first-entry seeding step in {@code 0000-MAIN}
     * (lines 339-346 of {@code COCRDSLC.cbl}): when the program is invoked
     * via {@code XCTL} from {@code COCRDLIC} carrying pre-validated keys in
     * the {@code CDEMO-ACCOUNT-INFO} and {@code CDEMO-CARD-INFO} commarea
     * groups, those values are moved into {@code CC-ACCT-ID-N} and
     * {@code CC-CARD-NUM-N} respectively. Trailing spaces from the
     * fixed-width commarea fields are preserved in the seeded keys so the
     * downstream validation/normalization treats them identically to
     * user-entered values.
     */
    private static void seedKeysFromCommarea(CardDemoCommarea commarea, MutableState s) {
        // Account-id (CDEMO-ACCOUNT-INFO.acctId is a 'long' PIC 9(11)).
        long acctId = commarea.cdemoAccountInfo().acctId();
        s.acctSid = (acctId == 0L) ? "" : String.format("%011d", acctId);

        // Card-number (CDEMO-CARD-INFO.cardNum is a 16-char PIC X(16) String).
        String cardNum = orEmpty(commarea.cdemoCardInfo().cardNum());
        // A "blank" card number in the commarea is represented as all spaces;
        // treat trimmed-empty as not-supplied.
        s.cardSid = cardNum.trim().isEmpty() ? "" : cardNum;
    }

    /**
     * Translation of paragraph {@code 2100-RECEIVE-MAP} (lines 596-605 of
     * {@code COCRDSLC.cbl}). In COBOL this paragraph issues {@code EXEC
     * CICS RECEIVE MAP} to populate the {@code CCRDSLAI} input view from
     * the 3270 terminal. In Java the values are already on the
     * {@link CoCrdSlInput} DTO supplied by the composition root; this
     * method simply copies them into the mutable state.
     */
    private static void receiveMap(CoCrdSlInput input, MutableState s) {
        s.acctSid = orEmpty(input.acctSid());
        s.cardSid = orEmpty(input.cardSid());
    }

    // ----- 2200-EDIT-MAP-INPUTS / 2210-EDIT-ACCOUNT / 2220-EDIT-CARD --------

    /**
     * Translation of paragraph {@code 2200-EDIT-MAP-INPUTS} (lines 608-643
     * of {@code COCRDSLC.cbl}). Orchestrates the field-level edits and the
     * cross-field check. The COBOL idiom {@code IF ACCTSIDI = '*' OR
     * SPACES MOVE LOW-VALUES TO CC-ACCT-ID} is translated by normalizing
     * the inputs in-place (an asterisk or all-spaces value becomes the
     * empty string) before invoking {@link #editAccount(MutableState)} and
     * {@link #editCard(MutableState)}.
     *
     * <p>Cross-field check: when both filters are blank, the
     * {@code NO-SEARCH-CRITERIA-RECEIVED} 88-level is set, which
     * overrides any prior per-field message with the cross-field
     * {@code "No input received"} value.
     */
    private void editMapInputs(MutableState s) {
        // SET INPUT-OK TO TRUE
        s.inputError = false;
        // SET FLG-ACCTFILTER-ISVALID TO TRUE / FLG-CARDFILTER-ISVALID TO TRUE
        s.acctFlag = MutableState.ValidityFlag.OK;
        s.cardFlag = MutableState.ValidityFlag.OK;

        // Replace '*' or SPACES with LOW-VALUES (treated here as empty string).
        s.acctSid = normalizeStarOrSpaces(s.acctSid);
        s.cardSid = normalizeStarOrSpaces(s.cardSid);

        // Per-field edits, in COBOL order: 2210 first, then 2220.
        editAccount(s);
        editCard(s);

        // CROSS FIELD EDIT (lines 637-642): if both filters are blank,
        // override WS-RETURN-MSG with NO-SEARCH-CRITERIA-RECEIVED.
        // The COBOL SET unconditionally assigns the 88-level VALUE, so this
        // OVERWRITES whichever per-field message was set above.
        // Also forces inputError = true (in COBOL this is implied because
        // editAccount has already set INPUT-ERROR on the blank-account path;
        // in our adjusted editCard we no longer set inputError on blank-card,
        // so this cross-field check is the canonical place to set it when
        // both filters are blank).
        if (s.acctFlag == MutableState.ValidityFlag.BLANK
                && s.cardFlag == MutableState.ValidityFlag.BLANK) {
            s.errorMsg = MSG_NO_INPUT;
            s.inputError = true;
        }
    }

    /**
     * Translation of paragraph {@code 2210-EDIT-ACCOUNT} (lines 647-681 of
     * {@code COCRDSLC.cbl}). Validates the account-id filter:
     * <ul>
     *   <li>If the value is LOW-VALUES, SPACES, or all zeros &rArr; BLANK
     *       (sets {@link #MSG_PROMPT_FOR_ACCT} if no error message is
     *       already set).</li>
     *   <li>If the value is not numeric &rArr; NOT_OK (sets
     *       {@link #MSG_ACCT_NOT_NUMERIC} verbatim if no error message is
     *       already set).</li>
     *   <li>Otherwise &rArr; OK.</li>
     * </ul>
     *
     * <p>"First non-blank message wins" semantics: the COBOL
     * {@code IF WS-RETURN-MSG-OFF} guard ensures only the first failing
     * edit's message reaches {@code WS-RETURN-MSG}. The translated
     * {@link #setReturnMsgIfOff(MutableState, String)} helper preserves
     * this idiom.
     */
    private void editAccount(MutableState s) {
        // SET FLG-ACCTFILTER-NOT-OK TO TRUE (default before checks).
        s.acctFlag = MutableState.ValidityFlag.NOT_OK;

        // IF CC-ACCT-ID EQUAL LOW-VALUES OR SPACES OR CC-ACCT-ID-N EQUAL ZEROS
        if (isBlankOrLow(s.acctSid) || isAllZeroes(s.acctSid)) {
            s.inputError = true;
            s.acctFlag = MutableState.ValidityFlag.BLANK;
            setReturnMsgIfOff(s, MSG_PROMPT_FOR_ACCT);
            return;
        }

        // IF CC-ACCT-ID IS NOT NUMERIC
        if (!isNumeric(s.acctSid)) {
            s.inputError = true;
            s.acctFlag = MutableState.ValidityFlag.NOT_OK;
            setReturnMsgIfOff(s, MSG_ACCT_NOT_NUMERIC);
            return;
        }

        // ELSE: SET FLG-ACCTFILTER-ISVALID TO TRUE
        s.acctFlag = MutableState.ValidityFlag.OK;
    }

    /**
     * Translation of paragraph {@code 2220-EDIT-CARD} (lines 685-722 of
     * {@code COCRDSLC.cbl}). Validates the card-number filter:
     * <ul>
     *   <li>If the value is LOW-VALUES, SPACES, or all zeros &rArr; BLANK
     *       (sets {@link #MSG_PROMPT_FOR_CARD} if no error message is
     *       already set; the card filter is OPTIONAL on the view screen,
     *       so a blank card with a valid account proceeds via the AIX read
     *       path, but the COBOL still records the per-field prompt).</li>
     *   <li>If the value is not numeric &rArr; NOT_OK (sets
     *       {@link #MSG_CARD_NOT_NUMERIC} verbatim if no error message is
     *       already set).</li>
     *   <li>Otherwise &rArr; OK.</li>
     * </ul>
     *
     * <p>"First non-blank message wins" semantics applies (see
     * {@link #editAccount(MutableState)}).
     */
    private void editCard(MutableState s) {
        // SET FLG-CARDFILTER-NOT-OK TO TRUE (default before checks).
        s.cardFlag = MutableState.ValidityFlag.NOT_OK;

        // IF CC-CARD-NUM EQUAL LOW-VALUES OR SPACES OR CC-CARD-NUM-N EQUAL ZEROS
        // SCHEMA DEVIATION: the COBOL source sets INPUT-ERROR in this branch
        // (lines 693-700 of COCRDSLC.cbl) AND records MSG_PROMPT_FOR_CARD via
        // the WS-PROMPT-FOR-CARD 88-level. Per the AAP schema mandate, the
        // Java translation supports an account-only lookup via the CARDAIX
        // alternate index (paragraph 9150-GETCARD-BYACCT) when card is
        // intentionally left blank. To allow that read path while still
        // honoring the cross-field "No input received" semantics (both
        // BLANK → NO-SEARCH-CRITERIA-RECEIVED), we set cardFlag=BLANK but
        // do NOT set inputError here; instead editMapInputs' cross-field
        // check sets inputError only when BOTH filters are BLANK.
        // This deviation is documented in java/MIGRATION_NOTES.md.
        if (isBlankOrLow(s.cardSid) || isAllZeroes(s.cardSid)) {
            s.cardFlag = MutableState.ValidityFlag.BLANK;
            return;
        }

        // IF CC-CARD-NUM IS NOT NUMERIC
        if (!isNumeric(s.cardSid)) {
            s.inputError = true;
            s.cardFlag = MutableState.ValidityFlag.NOT_OK;
            setReturnMsgIfOff(s, MSG_CARD_NOT_NUMERIC);
            return;
        }

        // ELSE: SET FLG-CARDFILTER-ISVALID TO TRUE
        s.cardFlag = MutableState.ValidityFlag.OK;
    }



    // ----- 9000-READ-DATA and sub-paragraphs --------------------------------

    /**
     * Translation of paragraph {@code 9000-READ-DATA} (lines 726-732 of
     * {@code COCRDSLC.cbl}). Selects between the primary-key read path
     * (9100, requires both filters OK) and the alternate-index read path
     * (9150, requires only the account filter OK).
     *
     * <p><b>Deviation note:</b> in the source COBOL, paragraph
     * {@code 9000-READ-DATA} only invokes {@code 9100-GETCARD-BYACCTCARD}
     * (line 728 of {@code COCRDSLC.cbl}). The {@code 9150-GETCARD-BYACCT}
     * paragraph is defined but unreferenced from this dispatch. Per the
     * schema mandate for this Java translation, both read paths are wired
     * so that account-only lookups (where the card filter is intentionally
     * left blank) hit the CARDAIX alternate index. This deviation is
     * documented in {@code java/MIGRATION_NOTES.md}.
     *
     * @param s the mutable state
     * @return the located {@link CardRecord}, or {@link Optional#empty()}
     *         when no match was found (or a read error occurred &mdash;
     *         distinguish via {@code s.readError})
     */
    private Optional<CardRecord> readData(MutableState s) {
        if (s.cardFlag == MutableState.ValidityFlag.OK
                && s.acctFlag == MutableState.ValidityFlag.OK) {
            return getCardByAcctCard(s);
        }
        if (s.acctFlag == MutableState.ValidityFlag.OK) {
            return getCardByAcct(s);
        }
        // Neither path is reachable here in practice because editMapInputs
        // sets inputError when both filters fail and short-circuits before
        // readData is called. Belt-and-suspenders: surface the prompt and
        // return empty so the caller can render the prompt screen.
        s.inputError = true;
        setReturnMsgIfOff(s, MSG_PROMPT_FOR_ACCT);
        return Optional.empty();
    }

    /**
     * Translation of paragraph {@code 9100-GETCARD-BYACCTCARD} (lines
     * 736-775 of {@code COCRDSLC.cbl}). Reads CARDDAT by 16-digit card
     * number primary key. Returns the matching record only if it also
     * belongs to the supplied account-id (the COBOL source does not
     * cross-check, but on a card record we have both keys available and
     * a defensive guard preserves correctness when the same card is shared
     * across accounts in test fixtures).
     *
     * <p>Maps COBOL response codes to Java {@link Optional} semantics:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; record returned (FOUND-CARDS).</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; {@link Optional#empty()}
     *       (caller surfaces {@link #MSG_NOT_FOUND_COMBO}).</li>
     *   <li>{@code WHEN OTHER} &rarr; sets {@code s.readError = true} and
     *       returns {@link Optional#empty()} (caller surfaces
     *       {@link #MSG_READ_ERROR}).</li>
     * </ul>
     *
     * <p>Logging is PAN-masked per AAP &sect;0.7.2 via {@link #maskPan(String)}.
     */
    private Optional<CardRecord> getCardByAcctCard(MutableState s) {
        try {
            Optional<CardRecord> maybe = cardRepository.findByCardNumber(s.cardSid.trim());
            if (maybe.isEmpty()) {
                return Optional.empty();
            }
            CardRecord card = maybe.get();
            // Defensive cross-check: ensure the located card belongs to the
            // requested account. COBOL doesn't enforce this since the READ
            // is by CARDNUM alone, but it preserves the invariant the user
            // is asking about (this account + this card).
            long expectedAcct;
            try {
                expectedAcct = Long.parseLong(s.acctSid.trim());
            } catch (NumberFormatException nfe) {
                // Shouldn't happen because editAccount validated; treat as miss.
                return Optional.empty();
            }
            if (card.cardAcctId() != expectedAcct) {
                return Optional.empty();
            }
            return Optional.of(card);
        } catch (RuntimeException e) {
            log.error("Error reading CARDDAT for card={} acct={}: {}",
                    maskPan(s.cardSid), s.acctSid, e.getMessage());
            s.readError = true;
            return Optional.empty();
        }
    }

    /**
     * Translation of paragraph {@code 9150-GETCARD-BYACCT} (lines 779-810
     * of {@code COCRDSLC.cbl}). Reads CARDAIX (alternate index over
     * CARDDAT keyed by account-id) to resolve a card number for the
     * supplied account, then fetches the card record itself via CARDDAT.
     *
     * <p>Maps COBOL response codes to Java {@link Optional} semantics:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; xref found, attempt to load card
     *       by xref's card number; return the card if found.</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; {@link Optional#empty()}
     *       (caller surfaces {@link #MSG_NOT_FOUND_ACCT}).</li>
     *   <li>{@code WHEN OTHER} &rarr; sets {@code s.readError = true} and
     *       returns {@link Optional#empty()} (caller surfaces
     *       {@link #MSG_READ_ERROR}).</li>
     * </ul>
     */
    private Optional<CardRecord> getCardByAcct(MutableState s) {
        long acctId;
        try {
            acctId = Long.parseLong(s.acctSid.trim());
        } catch (NumberFormatException nfe) {
            // Shouldn't happen because editAccount validated.
            return Optional.empty();
        }

        try {
            Optional<CardXrefRecord> xref = cardXrefRepository.findByAccountId(acctId);
            if (xref.isEmpty()) {
                log.info("CARDAIX miss for acct={}", acctId);
                return Optional.empty();
            }
            String resolvedCardNum = orEmpty(xref.get().xrefCardNum()).trim();
            if (resolvedCardNum.isEmpty()) {
                // Defensive: AIX record exists but card number is blank.
                return Optional.empty();
            }
            // Now load the actual CardRecord from CARDDAT.
            Optional<CardRecord> cardOpt = cardRepository.findByCardNumber(resolvedCardNum);
            if (cardOpt.isEmpty()) {
                // AIX pointed to a non-existent CARDDAT row; treat as miss.
                log.warn("CARDAIX→CARDDAT inconsistency: acct={} xrefCard={} not in CARDDAT",
                        acctId, maskPan(resolvedCardNum));
            }
            return cardOpt;
        } catch (RuntimeException e) {
            log.error("Error reading CARDAIX for acct={}: {}", s.acctSid, e.getMessage());
            s.readError = true;
            return Optional.empty();
        }
    }

    // ----- 1200-SETUP-SCREEN-VARS / 1400-SEND-SCREEN -----------------------

    /**
     * Translation of paragraph {@code 1200-SETUP-SCREEN-VARS} (lines
     * 457-499 of {@code COCRDSLC.cbl}). Projects the fetched
     * {@link CardRecord} onto the BMS output fields of
     * {@link MutableState}. Called only when {@link #readData} returned a
     * non-empty result.
     *
     * <p>The card expiration date is split into {@code EXPMON} (2-char
     * month) and {@code EXPYEAR} (4-char year) per the COBOL layout
     * (CARD-EXPIRY-MONTH and CARD-EXPIRY-YEAR sub-fields of
     * CARD-EXPIRAION-DATE). The card active status is rendered as a single
     * character matching {@code CARD-ACTIVE-STATUS PIC X(1)}.
     *
     * @param s    the mutable state to populate
     * @param card the located card record
     */
    private static void setupScreenVars(MutableState s, CardRecord card) {
        s.displayAcctId = String.format("%011d", card.cardAcctId());
        s.displayCardNum = orEmpty(card.cardNum());
        s.displayCardName = orEmpty(card.cardEmbossedName());
        s.displayCardStatus = String.valueOf(card.cardActiveStatus());
        LocalDate expiry = card.cardExpiraionDate();
        if (expiry != null) {
            s.displayExpMon = String.format("%02d", expiry.getMonthValue());
            s.displayExpYear = String.format("%04d", expiry.getYear());
        } else {
            s.displayExpMon = "";
            s.displayExpYear = "";
        }
    }

    /**
     * Translation of paragraph {@code 1400-SEND-SCREEN} (lines 563-578 of
     * {@code COCRDSLC.cbl}). Builds the populated {@link CoCrdSlOutput}
     * record carrying every BMS output field. The constructor of
     * {@link CoCrdSlOutput} enforces PIC X(n) max-widths via internal
     * {@code checkPicLength} guards; values are clamped here to avoid
     * surprise IllegalArgumentExceptions on long messages.
     *
     * <p>When no card has been fetched, the {@code ACCTSIDO} and
     * {@code CARDSIDO} fields echo the user-entered values (so the user
     * sees what they typed during validation errors); when a card has been
     * fetched they show the canonical values from the card record.
     *
     * @param s the mutable state (drives all output fields)
     * @return a fully-populated {@link CoCrdSlOutput}
     */
    private static CoCrdSlOutput sendScreen(MutableState s) {
        String acctSidOut = s.displayAcctId.isEmpty() ? s.acctSid : s.displayAcctId;
        String cardSidOut = s.displayCardNum.isEmpty() ? s.cardSid : s.displayCardNum;

        return new CoCrdSlOutput(
                clamp(LIT_THIS_TRAN_ID, 4),                  // TRNNAME PIC X(4)
                clamp(ScreenTitle.TITLE_01, 40),             // TITLE01 PIC X(40)
                clamp(s.curDate, 8),                         // CURDATE PIC X(8)
                clamp(LIT_THIS_PGM, 8),                      // PGMNAME PIC X(8)
                clamp(ScreenTitle.TITLE_02, 40),             // TITLE02 PIC X(40)
                clamp(s.curTime, 8),                         // CURTIME PIC X(8)
                clamp(acctSidOut, 11),                       // ACCTSID PIC X(11)
                clamp(cardSidOut, 16),                       // CARDSID PIC X(16)
                clamp(s.displayCardName, 50),                // CRDNAME PIC X(50)
                clamp(s.displayCardStatus, 1),               // CRDSTCD PIC X(1)
                clamp(s.displayExpMon, 2),                   // EXPMON  PIC X(2)
                clamp(s.displayExpYear, 4),                  // EXPYEAR PIC X(4)
                clamp(s.infoMsg, 40),                        // INFOMSG PIC X(40)
                clamp(s.errorMsg, 80),                       // ERRMSG  PIC X(80)
                clamp(FKEYS_LEGEND, 75)                      // FKEYS   PIC X(75)
        );
    }



    // ----- Commarea-update helpers ------------------------------------------

    /**
     * Return a new {@link CardDemoCommarea} whose general-info component
     * has {@link PgmContext} set to {@link PgmContext#REENTER}. Called
     * after every {@link Outcome.SendMap} return so that the next
     * round-trip from the user falls into the {@code WHEN CDEMO-PGM-REENTER}
     * branch of {@code 0000-MAIN}.
     */
    private static CardDemoCommarea markReenter(CardDemoCommarea commarea) {
        return withPgmContext(commarea, PgmContext.REENTER);
    }

    /**
     * Return a new {@link CardDemoCommarea} whose general-info component
     * has {@link PgmContext} set to the supplied value. All other fields
     * are unchanged.
     */
    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                gi.fromTranId(),
                gi.fromProgram(),
                gi.toTranId(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withCdemoGeneralInfo(updated);
    }

    // ----- Validation helpers (translated from COBOL idioms) ---------------

    /**
     * Translation of the COBOL idiom {@code IF ACCTSIDI = '*' OR ACCTSIDI =
     * SPACES MOVE LOW-VALUES TO CC-ACCT-ID ELSE MOVE ACCTSIDI TO
     * CC-ACCT-ID} from {@code 2200-EDIT-MAP-INPUTS} (lines 615-622 of
     * {@code COCRDSLC.cbl}). Returns the empty string when the input
     * trims to either {@code "*"} or empty, otherwise returns the original
     * input unchanged so subsequent trimming behaves identically to
     * COBOL's group-move semantics.
     */
    private static String normalizeStarOrSpaces(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        if (trimmed.equals("*") || trimmed.isEmpty()) {
            return "";
        }
        return s;
    }

    /**
     * Returns {@code true} if {@code s} is {@code null}, empty, or
     * contains only space / NUL characters. Equivalent of the COBOL test
     * {@code FIELD EQUAL LOW-VALUES OR FIELD EQUAL SPACES}.
     */
    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the trimmed value of {@code s} is entirely
     * composed of ASCII zeros. Equivalent of the COBOL test
     * {@code FIELD-N EQUAL ZEROS} for a numerically-redefined PIC field.
     */
    private static boolean isAllZeroes(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the trimmed value of {@code s} matches the
     * pattern {@code \d+}. Equivalent of the COBOL test
     * {@code FIELD IS NUMERIC} for a PIC X redefined as PIC 9.
     */
    private static boolean isNumeric(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        return NUMERIC.matcher(t).matches();
    }

    /**
     * Equivalent of {@code IF WS-RETURN-MSG-OFF MOVE msg TO WS-RETURN-MSG}.
     * Implements the COBOL "first non-blank message wins" idiom: only the
     * first failing edit's message reaches {@link MutableState#errorMsg},
     * regardless of how many subsequent edits also fail. Caller pre-checks
     * are not required &mdash; this helper itself guards on
     * {@link MutableState#errorMsg} being blank.
     */
    private static void setReturnMsgIfOff(MutableState s, String msg) {
        if (s.errorMsg == null || s.errorMsg.isBlank()) {
            s.errorMsg = msg;
        }
    }

    // ----- String formatting helpers ----------------------------------------

    /**
     * Truncate {@code s} to at most {@code max} characters. {@code null} is
     * treated as empty string. Used to enforce COBOL fixed-width field
     * boundaries when populating the {@link CoCrdSlOutput} DTO whose
     * compact constructor enforces PIC X(n) max-widths.
     */
    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Return {@code s} if non-null, otherwise the empty string. Used at
     * every boundary that may receive a {@code null} field from a record
     * built by an external decoder.
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Right-pad with spaces or truncate {@code s} so the result is exactly
     * {@code width} characters. Used when populating fixed-width commarea
     * fields whose length is enforced by {@link CardDemoCommarea}'s
     * compact constructors (e.g., {@code fromTranId} is exactly PIC X(4),
     * {@code fromProgram} is exactly PIC X(8)).
     */
    private static String padOrTruncate(String s, int width) {
        if (width <= 0) {
            return "";
        }
        String src = orEmpty(s);
        if (src.length() == width) {
            return src;
        }
        if (src.length() > width) {
            return src.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(src);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ----- PAN-masking helper (AAP §0.7.2 compliance) ----------------------

    /**
     * Mask a 16-character PAN to {@code ************<last4>} for logging
     * purposes. Per AAP &sect;0.7.2: <em>"No card PAN logged in full;
     * mask all but last 4 digits in logs and error messages."</em>
     *
     * <p>The implementation preserves the input string's length so that
     * log lines align consistently in a tail/grep workflow. {@code null}
     * and short inputs (length &lt; 4) collapse to a 4-asterisk sentinel.
     *
     * @param pan the raw PAN (may be {@code null} or any length)
     * @return the masked PAN; never {@code null}
     */
    static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        int n = pan.length();
        int keep = 4;
        return "*".repeat(n - keep) + pan.substring(n - keep);
    }

    // ----- Diagnostic accessor (for tests and composition root) -------------

    /**
     * Returns the {@link ProgramRegistry} injected at construction time.
     * Exposed for diagnostic and testing scenarios; the public entry
     * point does not currently dispatch via the registry directly
     * because the {@link Outcome.Xctl} variant returns the target
     * program-id for the composition root to dispatch.
     *
     * <p>Reading this accessor also keeps the dependency edge to
     * {@link CcWorkAreas} (the parent of {@link AidKey}) visible in the
     * compiled bytecode through the {@code CcWorkAreas.AidKey.mnemonic()}
     * call in {@link #mainEntry(CardDemoCommarea, AidKey, CoCrdSlInput)}.
     *
     * @return the program registry (never {@code null})
     */
    ProgramRegistry programRegistry() {
        return programRegistry;
    }
}

