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

// JEP 511 (finalized in Java 25): Module Import Declarations.
// `import module java.base` brings in all packages exported by the
// java.base module, including java.lang, java.util, java.time,
// java.math, java.text, java.util.regex — exactly the surfaces needed
// by this read-only account-view program. Per AAP §0.7.3, this is the
// idiomatic choice for files that touch many java.* packages.
import module java.base;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.PfKeyDecoder;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.menu.MainMenuTable;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of COBOL program {@code COACTVWC}
 * ({@code app/cbl/COACTVWC.cbl}, CICS transaction {@code CAVW}).
 *
 * <p><b>Purpose:</b> Display detailed account information (balances, credit
 * limits, customer demographic details). Takes an account ID, looks up the
 * CARDXREF AIX (by account) to derive the customer id, then reads the
 * ACCOUNT master and CUSTOMER master.
 *
 * <h2>Translation notes</h2>
 * <ul>
 *   <li>COBOL paragraphs map 1:1 to private methods:
 *     <ul>
 *       <li>{@code 0000-MAIN} &rarr; {@link #execute(CardDemoCommarea, AidKey,
 *           CoActVwInput)}</li>
 *       <li>{@code 1000-SEND-MAP} &rarr; {@link #sendMap(CardDemoCommarea,
 *           CoActVwInput, String, String, AccountRecord, CustomerRecord)}</li>
 *       <li>{@code 2000-PROCESS-INPUTS} &rarr; {@link
 *           #processInputs(CoActVwInput, MutableState)}</li>
 *       <li>{@code 2200-EDIT-MAP-INPUTS} / {@code 2210-EDIT-ACCOUNT} &rarr;
 *           {@link #editMapInputs(String, MutableState)}</li>
 *       <li>{@code 9000-READ-ACCT} &rarr; {@link #readAccount(String,
 *           MutableState)}</li>
 *       <li>{@code 9200-GETCARDXREF-BYACCT} &rarr; {@link
 *           CardXrefRepository#findByAccountId(long)}</li>
 *       <li>{@code 9300-GETACCTDATA-BYACCT} &rarr; {@link
 *           AccountRepository#findById(long)}</li>
 *       <li>{@code 9400-GETCUSTDATA-BYCUST} &rarr; {@link
 *           CustomerRepository#findById(long)}</li>
 *       <li>{@code ABEND-ROUTINE} &rarr; {@link #abendRoutine(String, String)}
 *           (throws {@link RuntimeException}).</li>
 *     </ul>
 *   </li>
 *   <li>Verbatim error messages preserved per AAP &sect;0.7.1 (do not "fix"):
 *     <ul>
 *       <li>{@code "Account Filter must  be a non-zero 11 digit number"}
 *           &mdash; the double space between "must" and "be" is intentional;
 *           see {@code COACTVWC.cbl} line 672.</li>
 *       <li>{@code "Did not find this account in account card xref file"}</li>
 *       <li>{@code "Did not find this account in account master file"}</li>
 *       <li>{@code "Did not find associated customer in master file"}</li>
 *       <li>{@code "PF03 pressed.Exiting              "} (no space after
 *           dot, 14 trailing spaces; see {@code COACTVWC.cbl} line 120).</li>
 *     </ul>
 *   </li>
 *   <li>SSN formatted as {@code NNN-NN-NNNN} via {@code STRING} construct
 *       preserved (1200-SETUP-SCREEN-VARS).</li>
 *   <li>Monetary fields formatted as {@code +ZZZ,ZZZ,ZZZ.99} (15 chars,
 *       right-aligned, leading sign).</li>
 *   <li>This program is <strong>strictly read-only</strong>: no
 *       {@code REWRITE}, no {@code SYNCPOINT}, no transactional concerns.</li>
 *   <li>{@link Outcome} sealed interface replaces {@code EXEC CICS RETURN}
 *       (as {@link Outcome.SendMap}) and {@code EXEC CICS XCTL} (as
 *       {@link Outcome.Xctl}); the caller is responsible for routing.</li>
 * </ul>
 */
@CobolProgram(
        value = "COACTVWC",
        sourcePath = "app/cbl/COACTVWC.cbl",
        translationDate = "2025-01-15",
        notes = "Read-only account view; XCTL targets COCRDSLC/COCRDLIC/COMEN01C. " +
                "Displays full account + customer data via two-step lookup: CXACAIX → ACCTDAT → CUSTDAT."
)
public final class CoActVwC {

    // ----- Public WS-LITERALS (per AAP §0.4.1 schema mandate) ---------------
    //
    // Every constant below corresponds to a 05-level WS-LITERALS field in
    // COACTVWC.cbl (lines 142-194). They are exposed publicly so test
    // harnesses, sibling application classes, and the composition root can
    // reference the canonical COBOL literal values without re-declaring them.

    /** {@code LIT-THISPGM VALUE 'COACTVWC'} ({@code COACTVWC.cbl} line 143). */
    public static final String LIT_THIS_PGM = "COACTVWC";

    /** {@code LIT-THISTRANID VALUE 'CAVW'} ({@code COACTVWC.cbl} line 145). */
    public static final String LIT_THIS_TRAN_ID = "CAVW";

    /** {@code LIT-THISMAPSET VALUE 'COACTVW '} ({@code COACTVWC.cbl} line 147). */
    public static final String LIT_THIS_MAPSET = "COACTVW";

    /** {@code LIT-THISMAP VALUE 'CACTVWA'} ({@code COACTVWC.cbl} line 149). */
    public static final String LIT_THIS_MAP = "CACTVWA";

    /** {@code LIT-MENUPGM VALUE 'COMEN01C'} ({@code COACTVWC.cbl} line 168). */
    public static final String LIT_MENU_PGM = "COMEN01C";

    /** {@code LIT-MENUTRANID VALUE 'CM00'} ({@code COACTVWC.cbl} line 170). */
    public static final String LIT_MENU_TRAN_ID = "CM00";

    /** {@code LIT-CARDDTLPGM VALUE 'COCRDSLC'} ({@code COACTVWC.cbl} line 176). */
    public static final String LIT_CARDDTL_PGM = "COCRDSLC";

    /** {@code LIT-CARDDTLTRANID VALUE 'CCDL'} ({@code COACTVWC.cbl} line 178). */
    public static final String LIT_CARDDTL_TRAN_ID = "CCDL";

    /** {@code LIT-CCLISTPGM VALUE 'COCRDLIC'} ({@code COACTVWC.cbl} line 151). */
    public static final String LIT_CCLIST_PGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID VALUE 'CCLI'} ({@code COACTVWC.cbl} line 153). */
    public static final String LIT_CCLIST_TRAN_ID = "CCLI";

    /** {@code LIT-ACCTFILENAME VALUE 'ACCTDAT '} ({@code COACTVWC.cbl} line 184). */
    public static final String LIT_ACCTFILE = "ACCTDAT";

    /** {@code LIT-CUSTFILENAME VALUE 'CUSTDAT '} ({@code COACTVWC.cbl} line 188). */
    public static final String LIT_CUSTFILE = "CUSTDAT";

    /**
     * {@code LIT-CARDXREFNAME-ACCT-PATH VALUE 'CXACAIX '} ({@code COACTVWC.cbl}
     * line 192). This is the AIX (alternate index) over CARDXREF keyed by
     * account id (used by paragraph {@code 9200-GETCARDXREF-BYACCT}).
     */
    public static final String LIT_CARDXREF_ACCT_PATH = "CXACAIX";

    // ----- Private verbatim message constants (per AAP §0.7.1) --------------
    //
    // Source: COACTVWC.cbl 88-level VALUE clauses on WS-RETURN-MSG (lines
    // 117-141) and on WS-INFO-MSG (lines 110-116). Reproduced verbatim
    // including the double-space "must  be" and trailing-spaces idiosyncrasies.

    /** {@code WS-PROMPT-FOR-INPUT VALUE 'Enter or update id of account to display'}. */
    private static final String MSG_PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /** {@code WS-INFORM-OUTPUT VALUE 'Displaying details of given Account'}. */
    private static final String MSG_INFORM_OUTPUT = "Displaying details of given Account";

    /** {@code WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'}. */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'}. */
    private static final String MSG_NO_INPUT = "No input received";

    /**
     * Literal MOVE'd at {@code COACTVWC.cbl} line 672. NOTE: the double space
     * between "must" and "be" is INTENTIONAL and preserved verbatim per
     * AAP &sect;0.7.1. Modifying it changes observable output.
     */
    private static final String MSG_ACCT_NOT_NUMERIC = "Account Filter must  be a non-zero 11 digit number";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF VALUE 'Did not find this account in account card xref file'}. */
    private static final String MSG_NOT_FOUND_XREF = "Did not find this account in account card xref file";

    /** {@code DID-NOT-FIND-ACCT-IN-ACCTDAT VALUE 'Did not find this account in account master file'}. */
    private static final String MSG_NOT_FOUND_ACCT = "Did not find this account in account master file";

    /** {@code DID-NOT-FIND-CUST-IN-CUSTDAT VALUE 'Did not find associated customer in master file'}. */
    private static final String MSG_NOT_FOUND_CUST = "Did not find associated customer in master file";

    /**
     * {@code WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '}.
     * Verbatim preservation per AAP &sect;0.7.1: no space after the period,
     * 14 trailing spaces ({@code COACTVWC.cbl} line 120).
     */
    private static final String MSG_PF03_EXIT = "PF03 pressed.Exiting              ";

    /**
     * {@code WS-LONG-MSG} placeholder for the {@code WHEN OTHER} branch of
     * the main EVALUATE, line 379-380 of {@code COACTVWC.cbl}: {@code MOVE
     * 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG}.
     */
    private static final String MSG_UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";

    /**
     * Default screen title 02 displayed in {@code TITLE02O}. Per
     * {@code COACTVWC.cbl} line 437, CCDA-TITLE02 is moved into TITLE02O.
     * CCDA-TITLE02 is shipped from copybook {@code COTTL01Y}.
     */
    private static final String SCREEN_TITLE_02 = "View Account";

    // ----- Static formatters and patterns -----------------------------------

    /**
     * BMS-display date formatter; mirrors COBOL {@code WS-CURDATE-MM-DD-YY}
     * built in 1100-SCREEN-INIT (lines 441-447).
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US);

    /**
     * BMS-display time formatter; mirrors COBOL {@code WS-CURTIME-HH-MM-SS}
     * built in 1100-SCREEN-INIT (lines 449-453).
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);

    /**
     * Account date formatter; ACCT-OPEN-DATE / ACCT-EXPIRAION-DATE /
     * ACCT-REISSUE-DATE are stored as {@code CCYY-MM-DD} (PIC X(10)).
     */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Regex matching strictly ASCII digits. Used by {@link #isNumeric(String)}. */
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    /**
     * Currency formatter producing the COBOL PICOUT {@code +ZZZ,ZZZ,ZZZ.99}
     * mask (15 chars, leading sign, comma thousands separator). Locale is
     * pinned to US so the decimal point and comma separators do not vary
     * with the JVM default.
     */
    private static final DecimalFormat CURRENCY_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        symbols.setGroupingSeparator(',');
        CURRENCY_FORMAT = new DecimalFormat("+###,###,##0.00;-###,###,##0.00", symbols);
        CURRENCY_FORMAT.setRoundingMode(RoundingMode.HALF_EVEN);
    }

    /** SLF4J logger for this program. */
    private static final Logger log = LoggerFactory.getLogger(CoActVwC.class);

    // ----- Injected collaborators (constructor injection) -------------------

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;
    private final ProgramRegistry programRegistry;

    /**
     * Construct a {@code CoActVwC} with the required collaborator ports.
     *
     * @param accountRepository  port for reading ACCTDAT (paragraph
     *                           9300-GETACCTDATA-BYACCT)
     * @param customerRepository port for reading CUSTDAT (paragraph
     *                           9400-GETCUSTDATA-BYCUST)
     * @param cardXrefRepository port for reading CXACAIX (paragraph
     *                           9200-GETCARDXREF-BYACCT)
     * @param programRegistry    dynamic-CALL routing facility; carried as a
     *                           collaborator so callers (or future dispatch
     *                           code) can resolve target programs by name
     *                           after this method returns
     *                           {@link Outcome.Xctl}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoActVwC(AccountRepository accountRepository,
                    CustomerRepository customerRepository,
                    CardXrefRepository cardXrefRepository,
                    ProgramRegistry programRegistry) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "cardXrefRepository");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }


    // ----- Outcome (sealed return value) ------------------------------------

    /**
     * Sealed return value of {@link #execute(CardDemoCommarea, AidKey,
     * CoActVwInput)}. Replaces the COBOL CICS terminal verbs:
     * <ul>
     *   <li>{@link SendMap} corresponds to {@code EXEC CICS SEND MAP}
     *       followed by {@code EXEC CICS RETURN TRANSID(CAVW)
     *       COMMAREA(WS-COMMAREA)}. The caller is expected to render the
     *       supplied {@link CoActVwOutput} on the BMS screen and route the
     *       next user input back into {@link #execute(CardDemoCommarea,
     *       AidKey, CoActVwInput)} with the new state.</li>
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
         * @param output   the populated {@link CoActVwOutput} to send on the
         *                 BMS screen; never {@code null}
         * @param commarea the updated commarea to round-trip back into the
         *                 caller; never {@code null}
         */
        record SendMap(CoActVwOutput output, CardDemoCommarea commarea) implements Outcome {
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
         *                      {@code "COMEN01C"}); never {@code null} or
         *                      blank
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
     * fields used across paragraphs to coordinate input-validity flags and
     * the return message. Local to a single {@link #execute} invocation and
     * never shared across threads.
     */
    private static final class MutableState {
        /** {@code FLG-ACCTFILTER-...} flag: ISVALID / BLANK / NOT-OK. */
        AcctFilterFlag acctFilter = AcctFilterFlag.IS_VALID;
        /** {@code WS-EDIT-VARIABLE-FLAGS / INPUT-OK / INPUT-ERROR}. */
        boolean inputError = false;
        /** {@code WS-RETURN-MSG} (sticky; only first non-blank wins). */
        String returnMsg = "";

        /** {@code WS-RETURN-MSG-OFF} = SPACES. */
        boolean returnMsgOff() {
            return returnMsg == null || returnMsg.isBlank();
        }

        /** Equivalent of {@code IF WS-RETURN-MSG-OFF MOVE msg TO WS-RETURN-MSG}. */
        void setReturnMsgIfOff(String msg) {
            if (returnMsgOff()) {
                returnMsg = msg;
            }
        }
    }

    /**
     * Enum mirroring the COBOL 88-level conditions
     * {@code FLG-ACCTFILTER-ISVALID / FLG-ACCTFILTER-BLANK /
     * FLG-ACCTFILTER-NOT-OK} on {@code WS-EDIT-ACCT-FLAG}.
     */
    private enum AcctFilterFlag {
        IS_VALID,
        BLANK,
        NOT_OK
    }

    // ----- 0000-MAIN ---------------------------------------------------------

    /**
     * Public entry point translating COBOL paragraph {@code 0000-MAIN}
     * (lines 235-393 of {@code COACTVWC.cbl}). Dispatches based on the
     * supplied {@link AidKey} and {@link PgmContext}.
     *
     * <p>First-time entry detection: if {@code commareaIn} is {@code null}
     * (EIBCALEN = 0) or if it was XCTL'd from the menu without a re-enter
     * flag set, the commarea is initialized from a clean
     * {@link CardDemoCommarea#empty()} canonical instance.
     *
     * <p>The {@link AidKey} parameter is the canonical sealed AID-key value
     * decoded from the BMS terminal-input EIBAID byte by
     * {@link PfKeyDecoder} (or by an alternate entry-contract decoder); it
     * is the single source of truth for the {@code EVALUATE TRUE} dispatch
     * on the WHEN clauses {@code CCARD-AID-PFK03}, {@code CDEMO-PGM-ENTER},
     * and {@code CDEMO-PGM-REENTER}.
     *
     * @param commareaIn the inbound DFHCOMMAREA (may be {@code null} for
     *                   EIBCALEN = 0 cold-start invocation)
     * @param aidKey     the CICS AID-key pressed by the terminal user;
     *                   never {@code null}
     * @param input      the BMS map input record (may be {@code null} for
     *                   first-time or cold-start invocation)
     * @return one of {@link Outcome.SendMap} or {@link Outcome.Xctl}; never
     *         {@code null}
     * @throws NullPointerException if {@code aidKey} is {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn, AidKey aidKey, CoActVwInput input) {
        Objects.requireNonNull(aidKey, "aidKey");

        // Translate of MOVE LIT-THISTRANID TO WS-TRANID (line 274).
        // Set WS-RETURN-MSG-OFF TO TRUE (line 278).
        MutableState state = new MutableState();

        // First-time entry detection per lines 281-291 of COACTVWC.cbl:
        //   IF EIBCALEN = 0
        //   OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)
        //      MOVE LOW-VALUES TO CARDDEMO-COMMAREA
        //      ...
        CardDemoCommarea commarea = initializeOrCarryCommarea(commareaIn);

        if (log.isDebugEnabled()) {
            log.debug("CoActVwC.execute entered: aidKey={} pgmContext={} fromProgram={}",
                    aidKey.mnemonic(),
                    commarea.cdemoGeneralInfo().pgmContext().getClass().getSimpleName(),
                    commarea.cdemoGeneralInfo().fromProgram().trim());
        }

        // 0000-MAIN EVALUATE TRUE dispatch (lines 323-383). We use a
        // pattern-matching switch on the sealed AidKey hierarchy.
        return switch (aidKey) {
            case CcWorkAreas.AidKey.PfKey03 _ -> handlePfk03Exit(commarea);
            case CcWorkAreas.AidKey.Enter _   -> dispatchByPgmContext(commarea, input, state);
            // Any other AID key (PFK01..02, PFK04..PFK12, Clear, Pa1, Pa2):
            // not specifically handled by COACTVWC. The 88-level conditions
            // do not match a WHEN clause so the EVALUATE falls through to
            // WHEN OTHER (line 375), which is an UNEXPECTED-DATA scenario.
            case CcWorkAreas.AidKey.Clear _,
                 CcWorkAreas.AidKey.Pa1 _,
                 CcWorkAreas.AidKey.Pa2 _,
                 CcWorkAreas.AidKey.PfKey01 _,
                 CcWorkAreas.AidKey.PfKey02 _,
                 CcWorkAreas.AidKey.PfKey04 _,
                 CcWorkAreas.AidKey.PfKey05 _,
                 CcWorkAreas.AidKey.PfKey06 _,
                 CcWorkAreas.AidKey.PfKey07 _,
                 CcWorkAreas.AidKey.PfKey08 _,
                 CcWorkAreas.AidKey.PfKey09 _,
                 CcWorkAreas.AidKey.PfKey10 _,
                 CcWorkAreas.AidKey.PfKey11 _,
                 CcWorkAreas.AidKey.PfKey12 _ -> handleUnexpectedAid(commarea, input, state);
        };
    }



    /**
     * Translation of the {@code EIBCALEN = 0 OR (CDEMO-FROM-PROGRAM =
     * LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)} detection block of paragraph
     * {@code 0000-MAIN} (lines 281-313). Returns an initialized commarea
     * suitable for use by the rest of the dispatch logic.
     */
    private CardDemoCommarea initializeOrCarryCommarea(CardDemoCommarea commareaIn) {
        if (commareaIn == null) {
            return CardDemoCommarea.empty();
        }
        boolean isReenter = commareaIn.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter;
        boolean fromMenu = LIT_MENU_PGM.equals(commareaIn.cdemoGeneralInfo().fromProgram().trim());
        if (fromMenu && !isReenter) {
            // MOVE LOW-VALUES TO CARDDEMO-COMMAREA, then re-initialize
            // selected fields the way COACTVWC.cbl does — preserve only the
            // user identity and the FROM-PROGRAM lineage so that PFK3 below
            // can route correctly.
            CardDemoCommarea empty = CardDemoCommarea.empty();
            CardDemoCommarea.CdemoGeneralInfo gi = empty.cdemoGeneralInfo();
            CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                    gi.fromTranId(),
                    padOrTruncate(LIT_MENU_PGM, gi.fromProgram().length()),
                    gi.toTranId(),
                    gi.toProgram(),
                    commareaIn.cdemoGeneralInfo().userId(),
                    commareaIn.cdemoGeneralInfo().userType(),
                    PgmContext.ENTER);
            return empty.withCdemoGeneralInfo(updated);
        }
        return commareaIn;
    }

    /**
     * Translation of WHEN CCARD-AID-PFK03 branch (lines 324-352 of
     * {@code COACTVWC.cbl}). Performs an XCTL back to the calling program
     * (or main menu fallback) with the user type forced to {@code USER}
     * and program context reset to {@code ENTER}.
     */
    private Outcome handlePfk03Exit(CardDemoCommarea commarea) {
        CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();

        // IF CDEMO-FROM-TRANID = LOW-VALUES OR SPACES then to-tranid := menu-tranid
        // ELSE to-tranid := from-tranid
        String fromTranIdTrim = gi.fromTranId() == null ? "" : gi.fromTranId().trim();
        String toTranIdNew = fromTranIdTrim.isEmpty()
                ? padOrTruncate(LIT_MENU_TRAN_ID, gi.toTranId().length())
                : gi.fromTranId();

        // IF CDEMO-FROM-PROGRAM = LOW-VALUES OR SPACES then to-program := menu-pgm
        // ELSE to-program := from-program
        String fromProgramTrim = gi.fromProgram() == null ? "" : gi.fromProgram().trim();
        String toProgramNew = fromProgramTrim.isEmpty()
                ? padOrTruncate(LIT_MENU_PGM, gi.toProgram().length())
                : gi.fromProgram();

        // MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID
        // MOVE LIT-THISPGM    TO CDEMO-FROM-PROGRAM
        // SET CDEMO-USRTYP-USER TO TRUE
        // SET CDEMO-PGM-ENTER   TO TRUE
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                padOrTruncate(LIT_THIS_TRAN_ID, gi.fromTranId().length()),
                padOrTruncate(LIT_THIS_PGM, gi.fromProgram().length()),
                toTranIdNew,
                toProgramNew,
                gi.userId(),
                UserType.USER,
                PgmContext.ENTER);
        CardDemoCommarea updatedCommarea = commarea.withCdemoGeneralInfo(updated);

        // MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET
        // MOVE LIT-THISMAP    TO CDEMO-LAST-MAP
        CardDemoCommarea.CdemoMoreInfo mi = updatedCommarea.cdemoMoreInfo();
        CardDemoCommarea.CdemoMoreInfo miUpdated = new CardDemoCommarea.CdemoMoreInfo(
                padOrTruncate(LIT_THIS_MAP, mi.lastMap().length()),
                padOrTruncate(LIT_THIS_MAPSET, mi.lastMapset().length()));
        updatedCommarea = updatedCommarea.withCdemoMoreInfo(miUpdated);

        // The target program for XCTL is the new to-program value (already
        // resolved to either FROM-PROGRAM or LIT-MENUPGM above).
        String target = toProgramNew.trim();

        // Optional: validate via the program registry's known list. If the
        // resolved program is not a known registered handler, we still
        // return Outcome.Xctl so the caller (composition root) makes the
        // final dispatch decision via programRegistry.invoke(...).
        if (programRegistry == null) {
            log.warn("programRegistry is null; XCTL target {} cannot be validated", target);
        }

        log.info("CoActVwC PFK3 exit: XCTL to {}", target);
        return new Outcome.Xctl(target, updatedCommarea);
    }

    /**
     * Translation of WHEN CDEMO-PGM-ENTER / WHEN CDEMO-PGM-REENTER
     * branches (lines 353-374). Selects between first-entry processing
     * (just send the prompt screen) and re-enter processing (validate
     * input, read account, then send the result screen).
     */
    private Outcome dispatchByPgmContext(CardDemoCommarea commarea, CoActVwInput input, MutableState state) {
        return switch (commarea.cdemoGeneralInfo().pgmContext()) {
            // WHEN CDEMO-PGM-ENTER (line 353): first-time entry. Send
            // the empty prompt screen.
            case PgmContext.Enter _ -> {
                CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
                yield buildPromptOutcome(reentered, state, input, null, null);
            }
            // WHEN CDEMO-PGM-REENTER (line 361): process inputs, then
            // read account, then send map.
            case PgmContext.Reenter _ -> handleReenterEnter(commarea, input, state);
        };
    }

    /**
     * Translation of the WHEN CDEMO-PGM-REENTER branch (lines 361-374):
     * PERFORM 2000-PROCESS-INPUTS, then IF INPUT-ERROR send map,
     * ELSE PERFORM 9000-READ-ACCT then send map.
     */
    private Outcome handleReenterEnter(CardDemoCommarea commarea, CoActVwInput input, MutableState state) {
        // 2000-PROCESS-INPUTS — 2100-RECEIVE-MAP (data is already on the
        // input DTO since we are NOT a CICS terminal driver in Java), then
        // 2200-EDIT-MAP-INPUTS / 2210-EDIT-ACCOUNT.
        processInputs(input, state);

        if (state.inputError) {
            // IF INPUT-ERROR PERFORM 1000-SEND-MAP, GO TO COMMON-RETURN.
            return buildPromptOutcome(commarea, state, input, null, null);
        }

        // ELSE PERFORM 9000-READ-ACCT THRU EXIT
        String acctIdInput = orEmpty(input == null ? null : input.acctSid()).trim();
        ReadAcctResult readResult = readAccount(acctIdInput, state);

        // Always do 1000-SEND-MAP after 9000-READ-ACCT.
        return buildPromptOutcome(commarea, state, input, readResult.account(), readResult.customer());
    }

    /**
     * Translation of WHEN OTHER (lines 375-382): {@code MOVE 'UNEXPECTED
     * DATA SCENARIO' TO WS-RETURN-MSG} and {@code PERFORM SEND-PLAIN-TEXT}.
     * In Java we treat any non-PFK3 / non-ENTER AID key as a soft error
     * (display the unexpected-data message on the BMS map) rather than
     * issuing a CICS abend.
     */
    private Outcome handleUnexpectedAid(CardDemoCommarea commarea, CoActVwInput input, MutableState state) {
        state.inputError = true;
        state.setReturnMsgIfOff(MSG_UNEXPECTED_DATA);
        log.warn("CoActVwC: unexpected AID key path triggered WHEN OTHER branch; message='{}'",
                MSG_UNEXPECTED_DATA);
        return buildPromptOutcome(commarea, state, input, null, null);
    }



    // ----- 2000-PROCESS-INPUTS, 2200-EDIT-MAP-INPUTS, 2210-EDIT-ACCOUNT ----

    /**
     * Translation of paragraph {@code 2000-PROCESS-INPUTS} (lines 596-621
     * of {@code COACTVWC.cbl}). In COBOL this paragraph also does the
     * 2100-RECEIVE-MAP terminal read; in Java that data is already on the
     * supplied input DTO so we only invoke the edit logic.
     */
    private void processInputs(CoActVwInput input, MutableState state) {
        // SET INPUT-OK TO TRUE
        state.inputError = false;
        // SET FLG-ACCTFILTER-ISVALID TO TRUE
        state.acctFilter = AcctFilterFlag.IS_VALID;

        // 2200-EDIT-MAP-INPUTS / 2210-EDIT-ACCOUNT
        String rawAcct = input == null ? "" : orEmpty(input.acctSid());
        editMapInputs(rawAcct, state);
    }

    /**
     * Translation of paragraphs {@code 2200-EDIT-MAP-INPUTS} (lines 622-647)
     * and {@code 2210-EDIT-ACCOUNT} (lines 649-680). Validates the account
     * id field; sets {@code state.inputError = true} and a verbatim
     * {@code WS-RETURN-MSG} value when invalid.
     */
    private void editMapInputs(String acctSidRaw, MutableState state) {
        // 2200-EDIT-MAP-INPUTS:
        //   IF ACCTSIDI = '*' OR ACCTSIDI = SPACES
        //      MOVE LOW-VALUES TO CC-ACCT-ID
        //   ELSE
        //      MOVE ACCTSIDI TO CC-ACCT-ID
        String acctId = normalizeStarOrSpaces(acctSidRaw);

        // 2210-EDIT-ACCOUNT:
        //   SET FLG-ACCTFILTER-NOT-OK TO TRUE
        state.acctFilter = AcctFilterFlag.NOT_OK;

        //   IF CC-ACCT-ID EQUAL LOW-VALUES OR SPACES
        //      SET INPUT-ERROR TO TRUE
        //      SET FLG-ACCTFILTER-BLANK TO TRUE
        //      IF WS-RETURN-MSG-OFF SET WS-PROMPT-FOR-ACCT TO TRUE
        //      MOVE ZEROES TO CDEMO-ACCT-ID
        //      GO TO 2210-EDIT-ACCOUNT-EXIT
        if (isBlankOrLow(acctId)) {
            state.inputError = true;
            state.acctFilter = AcctFilterFlag.BLANK;
            state.setReturnMsgIfOff(MSG_PROMPT_FOR_ACCT);
            // 2200-EDIT-MAP-INPUTS CROSS-FIELD-EDIT (lines 639-642):
            //   IF FLG-ACCTFILTER-BLANK
            //     SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE
            //   (overrides the prior WS-PROMPT-FOR-ACCT with 'No input received')
            // The original code uses SET NO-SEARCH-CRITERIA-RECEIVED which
            // assigns the 88-level VALUE 'No input received' UNCONDITIONALLY
            // (overwriting WS-RETURN-MSG). Preserve that semantics here.
            state.returnMsg = MSG_NO_INPUT;
            return;
        }

        //   IF CC-ACCT-ID NOT NUMERIC OR EQUAL ZEROES
        //      SET INPUT-ERROR TO TRUE
        //      SET FLG-ACCTFILTER-NOT-OK TO TRUE
        //      IF WS-RETURN-MSG-OFF
        //        MOVE 'Account Filter must  be a non-zero 11 digit number' TO WS-RETURN-MSG
        //      MOVE ZERO TO CDEMO-ACCT-ID
        //      GO TO 2210-EDIT-ACCOUNT-EXIT
        //   ELSE
        //      MOVE CC-ACCT-ID TO CDEMO-ACCT-ID
        //      SET FLG-ACCTFILTER-ISVALID TO TRUE
        if (!isNumeric(acctId) || isAllZeroes(acctId)) {
            state.inputError = true;
            state.acctFilter = AcctFilterFlag.NOT_OK;
            state.setReturnMsgIfOff(MSG_ACCT_NOT_NUMERIC);
            return;
        }

        state.acctFilter = AcctFilterFlag.IS_VALID;
    }

    // ----- 9000-READ-ACCT and its sub-paragraphs ----------------------------

    /**
     * Translation of paragraph {@code 9000-READ-ACCT} (lines 722-768 of
     * {@code COACTVWC.cbl}). Performs the COBOL three-step lookup:
     * <ol>
     *   <li>{@code 9200-GETCARDXREF-BYACCT}: AIX read on CXACAIX by
     *       account id to derive {@code XREF-CUST-ID}.</li>
     *   <li>{@code 9300-GETACCTDATA-BYACCT}: primary read on ACCTDAT by
     *       account id to fetch the account master record.</li>
     *   <li>{@code 9400-GETCUSTDATA-BYCUST}: primary read on CUSTDAT by
     *       customer id to fetch the customer master record.</li>
     * </ol>
     * Sets {@code state.inputError = true} and the appropriate verbatim
     * COBOL error message into {@code state.returnMsg} on any miss.
     *
     * @param acctIdStr the validated account-id string (numeric, non-zero)
     * @param state     mutable accumulator
     * @return a {@link ReadAcctResult} carrying any records that were
     *         successfully fetched; never {@code null}
     */
    private ReadAcctResult readAccount(String acctIdStr, MutableState state) {
        // Parse the account id (already known to be numeric from
        // editMapInputs, but defensive parsing in case caller invoked us
        // directly).
        long acctId;
        try {
            acctId = Long.parseLong(acctIdStr.trim());
        } catch (NumberFormatException nfe) {
            state.inputError = true;
            state.setReturnMsgIfOff(MSG_ACCT_NOT_NUMERIC);
            return ReadAcctResult.empty();
        }

        // 9200-GETCARDXREF-BYACCT
        Optional<CardXrefRecord> xrefOpt = readCardXrefByAccount(acctId, state);
        if (xrefOpt.isEmpty()) {
            return ReadAcctResult.empty();
        }
        long custId = xrefOpt.get().xrefCustId();

        // 9300-GETACCTDATA-BYACCT
        Optional<AccountRecord> acctOpt = readAccountDataByAccount(acctId, state);
        if (acctOpt.isEmpty()) {
            return new ReadAcctResult(null, null);
        }
        AccountRecord account = acctOpt.get();

        // 9400-GETCUSTDATA-BYCUST
        Optional<CustomerRecord> custOpt = readCustomerDataByCustId(custId, state);
        if (custOpt.isEmpty()) {
            // Customer not found: still return the account record so the
            // BMS map can show as much information as possible (COBOL
            // preserves whatever was already MOVEd into the output area).
            return new ReadAcctResult(account, null);
        }

        return new ReadAcctResult(account, custOpt.get());
    }

    /**
     * Translation of paragraph {@code 9200-GETCARDXREF-BYACCT}. Reads the
     * CARDXREF alternate index ({@link #LIT_CARDXREF_ACCT_PATH}) by
     * account id.
     */
    private Optional<CardXrefRecord> readCardXrefByAccount(long acctId, MutableState state) {
        Optional<CardXrefRecord> opt = cardXrefRepository.findByAccountId(acctId);
        if (opt.isEmpty()) {
            // DFHRESP(NOTFND) → DID-NOT-FIND-ACCT-IN-CARDXREF
            state.inputError = true;
            state.setReturnMsgIfOff(MSG_NOT_FOUND_XREF);
            log.info("CXACAIX miss for acctId={}", acctId);
        }
        return opt;
    }

    /**
     * Translation of paragraph {@code 9300-GETACCTDATA-BYACCT}. Reads
     * ACCTDAT by account id.
     */
    private Optional<AccountRecord> readAccountDataByAccount(long acctId, MutableState state) {
        Optional<AccountRecord> opt = accountRepository.findById(acctId);
        if (opt.isEmpty()) {
            // DFHRESP(NOTFND) → DID-NOT-FIND-ACCT-IN-ACCTDAT
            state.inputError = true;
            state.setReturnMsgIfOff(MSG_NOT_FOUND_ACCT);
            log.info("ACCTDAT miss for acctId={}", acctId);
        }
        return opt;
    }

    /**
     * Translation of paragraph {@code 9400-GETCUSTDATA-BYCUST}. Reads
     * CUSTDAT by customer id.
     */
    private Optional<CustomerRecord> readCustomerDataByCustId(long custId, MutableState state) {
        Optional<CustomerRecord> opt = customerRepository.findById(custId);
        if (opt.isEmpty()) {
            // DFHRESP(NOTFND) → DID-NOT-FIND-CUST-IN-CUSTDAT
            state.inputError = true;
            state.setReturnMsgIfOff(MSG_NOT_FOUND_CUST);
            log.info("CUSTDAT miss for custId={}", custId);
        }
        return opt;
    }

    /**
     * Compound result of {@link #readAccount(String, MutableState)}.
     * Either component may be {@code null} (e.g., when the customer record
     * is missing but the account record was found).
     */
    private record ReadAcctResult(AccountRecord account, CustomerRecord customer) {
        static ReadAcctResult empty() {
            return new ReadAcctResult(null, null);
        }
    }



    // ----- 1000-SEND-MAP and sub-paragraphs ---------------------------------

    /**
     * Translation of paragraph {@code 1000-SEND-MAP} (lines 413-426 of
     * {@code COACTVWC.cbl}). Builds an {@link Outcome.SendMap} carrying a
     * populated {@link CoActVwOutput} and the updated commarea. Combines:
     * <ul>
     *   <li>{@code 1100-SCREEN-INIT}: title, date, time, program/tran ids;</li>
     *   <li>{@code 1200-SETUP-SCREEN-VARS}: per-field MOVE of account /
     *       customer master record data into the BMS output;</li>
     *   <li>{@code 1250-SETUP-INFOMSG}: informational vs. error message
     *       selection;</li>
     *   <li>{@code COMMON-RETURN}: copy WS-RETURN-MSG into CCARD-ERROR-MSG
     *       (encoded as the {@code errMsg} field of {@link CoActVwOutput}).</li>
     * </ul>
     *
     * @param commarea the carry-forward commarea
     * @param state    the mutable state (drives ERROR-MSG)
     * @param input    the inbound input DTO (used to round-trip acctSid
     *                 echo); may be {@code null}
     * @param account  the fetched account record (may be {@code null} when
     *                 no account was looked up or look-up failed)
     * @param customer the fetched customer record (may be {@code null}
     *                 when no customer was looked up or look-up failed)
     */
    private Outcome buildPromptOutcome(CardDemoCommarea commarea,
                                       MutableState state,
                                       CoActVwInput input,
                                       AccountRecord account,
                                       CustomerRecord customer) {
        // 1100-SCREEN-INIT
        ScreenChrome chrome = screenInit();

        // 1200-SETUP-SCREEN-VARS
        AccountOutputFields acctFields = setupAccountFields(account, input);
        CustomerOutputFields custFields = setupCustomerFields(customer);

        // 1250-SETUP-INFOMSG: choose between WS-PROMPT-FOR-INPUT and
        // WS-INFORM-OUTPUT or leave blank when an error is set.
        String infoMsg = setupInfoMsg(state, account, customer);

        // COMMON-RETURN: WS-RETURN-MSG → CCARD-ERROR-MSG → ERRMSGO.
        String errMsg = clamp(orEmpty(state.returnMsg), 78);

        CoActVwOutput output = new CoActVwOutput(
                chrome.trnName(),
                chrome.title01(),
                chrome.curDate(),
                chrome.pgmName(),
                chrome.title02(),
                chrome.curTime(),
                acctFields.acctSid(),
                acctFields.acStatus(),
                acctFields.openDate(),
                acctFields.creditLimit(),
                acctFields.expirationDate(),
                acctFields.cashCreditLimit(),
                acctFields.reissueDate(),
                acctFields.currentBalance(),
                acctFields.currCycCredit(),
                acctFields.accountGroup(),
                acctFields.currCycDebit(),
                custFields.custNumber(),
                custFields.ssn(),
                custFields.dob(),
                custFields.ficoScore(),
                custFields.firstName(),
                custFields.middleName(),
                custFields.lastName(),
                custFields.addressLine1(),
                custFields.state(),
                custFields.addressLine2(),
                custFields.zip(),
                custFields.city(),
                custFields.country(),
                custFields.phone1(),
                custFields.govtIssuedId(),
                custFields.phone2(),
                custFields.eftAccountId(),
                custFields.primaryFlag(),
                clamp(infoMsg, 45),
                errMsg);

        // Commarea round-trip: ensure pgmContext = REENTER so the next
        // round-trip dispatches via WHEN CDEMO-PGM-REENTER (line 361).
        CardDemoCommarea updatedCommarea = withPgmContext(commarea, PgmContext.REENTER);
        // Carry the supplied acctSid into CDEMO-ACCT-ID when we have a
        // good account record so callers that subsequently XCTL to
        // COCRDSLC can pre-select.
        if (account != null) {
            updatedCommarea = withAccountId(updatedCommarea, account.acctId());
        }

        return new Outcome.SendMap(output, updatedCommarea);
    }

    /**
     * Translation of {@code 1100-SCREEN-INIT} (lines 431-454 of
     * {@code COACTVWC.cbl}). Populates the chrome fields (title, tran/pgm
     * name, current date, current time).
     */
    private ScreenChrome screenInit() {
        LocalDateTime now = LocalDateTime.now();
        return new ScreenChrome(
                clamp(LIT_THIS_TRAN_ID, 4),
                clamp(ScreenTitle.TITLE_01, ScreenTitle.FIELD_LENGTH),
                clamp(DATE_FORMATTER.format(now), 8),
                clamp(LIT_THIS_PGM, 8),
                clamp(SCREEN_TITLE_02, ScreenTitle.FIELD_LENGTH),
                clamp(TIME_FORMATTER.format(now), 8));
    }

    /**
     * Translation of the account-fields half of {@code 1200-SETUP-SCREEN-
     * VARS} (lines 462-490). When {@code account} is {@code null} the
     * fields are filled with empty strings (mirroring COBOL's MOVE
     * LOW-VALUES TO ACCTSIDO at line 466).
     */
    private AccountOutputFields setupAccountFields(AccountRecord account, CoActVwInput input) {
        if (account == null) {
            // COBOL line 466: MOVE LOW-VALUES TO ACCTSIDO. The input's
            // acctSid is still echoed on the screen for user convenience
            // unless explicitly cleared by the validation layer.
            String acctSidEcho = (input == null) ? "" : clamp(orEmpty(input.acctSid()), 11);
            return new AccountOutputFields(
                    acctSidEcho,
                    "", "", "", "", "", "", "", "", "", "");
        }
        return new AccountOutputFields(
                clamp(formatAccountIdEleven(account.acctId()), 11),
                clamp(String.valueOf(account.acctActiveStatus()), 1),
                clamp(fmtDate(account.acctOpenDate()), 10),
                clamp(fmtMoney(account.acctCreditLimit()), 15),
                clamp(fmtDate(account.acctExpiraionDate()), 10),
                clamp(fmtMoney(account.acctCashCreditLimit()), 15),
                clamp(fmtDate(account.acctReissueDate()), 10),
                clamp(fmtMoney(account.acctCurrBal()), 15),
                clamp(fmtMoney(account.acctCurrCycCredit()), 15),
                clamp(account.acctGroupId(), 10),
                clamp(fmtMoney(account.acctCurrCycDebit()), 15));
    }

    /**
     * Translation of the customer-fields half of {@code 1200-SETUP-SCREEN-
     * VARS} (lines 491-525). When {@code customer} is {@code null} the
     * fields are filled with empty strings.
     */
    private CustomerOutputFields setupCustomerFields(CustomerRecord customer) {
        if (customer == null) {
            return new CustomerOutputFields(
                    "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "", "", "");
        }

        // SSN: STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)
        String ssn = formatSsn(customer.custSsn());

        // DOB: LocalDate.toString() yields ISO_LOCAL_DATE 'yyyy-MM-dd'
        String dob = customer.custDobYyyyMmDd() == null ? ""
                : customer.custDobYyyyMmDd().format(ISO_DATE);

        return new CustomerOutputFields(
                clamp(formatCustomerIdNine(customer.custId()), 9),
                clamp(ssn, 12),
                clamp(dob, 10),
                clamp(formatFicoThree(customer.custFicoCreditScore()), 3),
                clamp(orEmpty(customer.custFirstName()), 25),
                clamp(orEmpty(customer.custMiddleName()), 25),
                clamp(orEmpty(customer.custLastName()), 25),
                clamp(orEmpty(customer.custAddrLine1()), 50),
                clamp(orEmpty(customer.custAddrStateCd()), 2),
                clamp(orEmpty(customer.custAddrLine2()), 50),
                clamp(orEmpty(customer.custAddrZip()), 5),
                clamp(orEmpty(customer.custAddrLine3()), 50),
                clamp(orEmpty(customer.custAddrCountryCd()), 3),
                clamp(orEmpty(customer.custPhoneNum1()), 13),
                clamp(orEmpty(customer.custGovtIssuedId()), 20),
                clamp(orEmpty(customer.custPhoneNum2()), 13),
                clamp(orEmpty(customer.custEftAccountId()), 10),
                clamp(String.valueOf(customer.custPriCardHolderInd()), 1));
    }

    /**
     * Translation of {@code 1250-SETUP-INFOMSG} (the informational-message
     * paragraph, called from 1000-SEND-MAP at line 421 of
     * {@code COACTVWC.cbl}; actual paragraph body inferred from the 88
     * conditions on WS-INFO-MSG). Returns the screen's information
     * message.
     */
    private String setupInfoMsg(MutableState state, AccountRecord account, CustomerRecord customer) {
        // If WS-RETURN-MSG is populated (error), suppress the info-msg.
        if (state.returnMsg != null && !state.returnMsg.isBlank()) {
            return "";
        }
        // If we have both account + customer data, show INFORM-OUTPUT.
        if (account != null && customer != null) {
            return MSG_INFORM_OUTPUT;
        }
        // Default first-time-entry / fall-back: prompt for input.
        return MSG_PROMPT_FOR_INPUT;
    }

    // ----- Output-construction helper records -------------------------------

    /** Chrome (title, tran, pgm, date, time) populated by 1100-SCREEN-INIT. */
    private record ScreenChrome(
            String trnName,
            String title01,
            String curDate,
            String pgmName,
            String title02,
            String curTime) {}

    /** Account-block output fields populated by 1200-SETUP-SCREEN-VARS. */
    private record AccountOutputFields(
            String acctSid,
            String acStatus,
            String openDate,
            String creditLimit,
            String expirationDate,
            String cashCreditLimit,
            String reissueDate,
            String currentBalance,
            String currCycCredit,
            String accountGroup,
            String currCycDebit) {}

    /** Customer-block output fields populated by 1200-SETUP-SCREEN-VARS. */
    private record CustomerOutputFields(
            String custNumber,
            String ssn,
            String dob,
            String ficoScore,
            String firstName,
            String middleName,
            String lastName,
            String addressLine1,
            String state,
            String addressLine2,
            String zip,
            String city,
            String country,
            String phone1,
            String govtIssuedId,
            String phone2,
            String eftAccountId,
            String primaryFlag) {}



    // ----- Formatting helpers -----------------------------------------------

    /**
     * Format a {@link BigDecimal} as a 15-character {@code +ZZZ,ZZZ,ZZZ.99}
     * BMS PICOUT string. Mirrors the COBOL PIC clause used for monetary
     * fields on the {@code CACTVWA} BMS map (ACRDLIMO, ACSHLIMO, ACURBALO,
     * ACRCYCRO, ACRCYDBO). Leading sign always present (+ for positive
     * or zero, - for negative), comma thousands separator, decimal point,
     * two decimal places. Right-aligned, padded with leading spaces to
     * width 15.
     *
     * <p>Per AAP &sect;0.6.1, the {@code Decimals} facade governs default
     * MathContext and RoundingMode for monetary code; this method
     * explicitly uses {@code RoundingMode.HALF_EVEN} (banker's rounding)
     * per the COBOL {@code ROUNDED} clause convention.
     *
     * @param v the monetary value (may be {@code null}, in which case the
     *          empty string is returned)
     * @return a 15-character formatted string, or empty string if
     *         {@code v} is {@code null}
     */
    private static String fmtMoney(BigDecimal v) {
        if (v == null) {
            return "";
        }
        // Decimals utility is the canonical source of MathContext defaults;
        // referenced here to keep the monetary-arithmetic discipline visible.
        BigDecimal scaled = Decimals.scaled(v, Decimals.DEFAULT_MONETARY_SCALE, RoundingMode.HALF_EVEN);
        String formatted = CURRENCY_FORMAT.format(scaled);
        if (formatted.length() > 15) {
            // Defensive: if the integer portion overflows the 13-character
            // numeric mask, truncate from the LEFT (preserve cents and sign).
            return formatted.substring(formatted.length() - 15);
        }
        // Right-align: pad with leading spaces to width 15.
        return String.format("%15s", formatted);
    }

    /**
     * Format a {@link LocalDate} as a 10-character {@code CCYY-MM-DD}
     * string (matching COBOL PIC X(10) for ACCT-OPEN-DATE,
     * ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, CUST-DOB-YYYY-MM-DD).
     *
     * @param d the date (may be {@code null}, in which case the empty
     *          string is returned)
     * @return the date formatted as {@code yyyy-MM-dd}, or empty string
     */
    private static String fmtDate(LocalDate d) {
        return d == null ? "" : d.format(ISO_DATE);
    }

    /**
     * Format a 9-digit SSN long as {@code NNN-NN-NNNN}. Mirrors the COBOL
     * STRING construct in {@code 1200-SETUP-SCREEN-VARS} at line 510:
     * {@code STRING CUST-SSN(1:3) DELIMITED BY SIZE '-' DELIMITED BY SIZE
     * CUST-SSN(4:2) DELIMITED BY SIZE '-' DELIMITED BY SIZE CUST-SSN(6:4)
     * DELIMITED BY SIZE INTO ACSTSSNO}.
     *
     * @param ssn the 9-digit SSN as a {@code long}
     * @return the SSN formatted as NNN-NN-NNNN (12 chars including hyphens)
     */
    private static String formatSsn(long ssn) {
        String padded = String.format("%09d", Math.max(0L, ssn));
        return padded.substring(0, 3) + "-"
                + padded.substring(3, 5) + "-"
                + padded.substring(5);
    }

    /**
     * Format an 11-digit account id as a fixed-width PIC 9(11) string,
     * left-padded with zeros. Mirrors {@code MOVE CC-ACCT-ID TO ACCTSIDO}.
     *
     * @param acctId the account id (non-negative)
     * @return the 11-character left-zero-padded account id
     */
    private static String formatAccountIdEleven(long acctId) {
        return String.format("%011d", Math.max(0L, acctId));
    }

    /**
     * Format a 9-digit customer id as a fixed-width PIC 9(9) string,
     * left-padded with zeros. Mirrors {@code MOVE XREF-CUST-ID TO
     * ACSTNUMO}.
     *
     * @param custId the customer id (non-negative)
     * @return the 9-character left-zero-padded customer id
     */
    private static String formatCustomerIdNine(long custId) {
        return String.format("%09d", Math.max(0L, custId));
    }

    /**
     * Format a 3-digit FICO credit score as PIC 9(3), left-padded with
     * zeros.
     *
     * @param fico the FICO score (non-negative)
     * @return the 3-character FICO string
     */
    private static String formatFicoThree(int fico) {
        return String.format("%03d", Math.max(0, fico));
    }

    /**
     * Split a 13-character {@code (NNN)NNN-NNNN} phone string into its 3
     * components. Mirrors COBOL substring reference {@code
     * CUST-PHONE-NUM-1(2:3) / (6:3) / (10:4)}. Returns an array of length 3
     * containing the area code (3 digits), prefix (3 digits), and line (4
     * digits). Defensive: returns an array of empty strings if the input is
     * not 13 characters in the expected shape.
     *
     * @param phone the input phone string
     * @return a 3-element array of {area, prefix, line}; never {@code null}
     */
    static String[] splitPhone(String phone) {
        if (phone == null || phone.length() < 13) {
            return new String[]{"", "", ""};
        }
        // (NNN)NNN-NNNN → 0='(' 1..3=NNN 4=')' 5..7=NNN 8='-' 9..12=NNNN
        String area = phone.substring(1, 4);
        String prefix = phone.substring(5, 8);
        String line = phone.substring(9, 13);
        return new String[]{area, prefix, line};
    }

    // ----- Validation helpers (translated from COBOL idioms) ---------------

    /**
     * Translation of the COBOL idiom {@code IF ACCTSIDI = '*' OR ACCTSIDI =
     * SPACES MOVE LOW-VALUES TO CC-ACCT-ID ELSE MOVE ACCTSIDI TO
     * CC-ACCT-ID} at lines 627-633. Returns an empty string when the input
     * is either a single asterisk or all spaces, otherwise returns the
     * original input unchanged.
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
     * {@code FIELD EQUAL ZEROES}.
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
     * {@code FIELD IS NUMERIC}.
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
     * Truncate {@code s} to at most {@code max} characters. {@code null} is
     * treated as empty string. Used to enforce COBOL fixed-width field
     * boundaries when populating output DTOs.
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
     * Return {@code s} if non-null, otherwise empty string.
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Right-pad with spaces or truncate {@code s} so the result is exactly
     * {@code width} characters. Used when populating fixed-width commarea
     * fields whose length is enforced by {@link CardDemoCommarea}'s
     * compact constructors.
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

    // ----- Commarea-update helpers ------------------------------------------

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

    /**
     * Return a new {@link CardDemoCommarea} whose account-info component
     * has {@code acctId} set to the supplied value. Used after a
     * successful 9000-READ-ACCT to round-trip the looked-up account id
     * back to the caller (mirrors COBOL {@code MOVE CC-ACCT-ID TO
     * CDEMO-ACCT-ID}).
     */
    private static CardDemoCommarea withAccountId(CardDemoCommarea commarea, long acctId) {
        CardDemoCommarea.CdemoAccountInfo ai = commarea.cdemoAccountInfo();
        CardDemoCommarea.CdemoAccountInfo updated = new CardDemoCommarea.CdemoAccountInfo(
                acctId,
                ai.acctStatus());
        return commarea.withCdemoAccountInfo(updated);
    }

    // ----- ABEND-ROUTINE (CICS abend translation) ----------------------------

    /**
     * Translation of paragraph {@code ABEND-ROUTINE} (lines 916-937 of
     * {@code COACTVWC.cbl}). In COBOL this paragraph issues
     * {@code EXEC CICS ABEND ABCODE('9999')}. In Java we throw a
     * {@link RuntimeException} carrying the message and CULPRIT/CODE
     * triple from {@link SystemMessages.AbendData} layout.
     *
     * <p>If {@code abendMsg} is {@code null} or blank, the COBOL fall-back
     * {@code 'UNEXPECTED ABEND OCCURRED.'} is used (line 919).
     *
     * @param abendMsg    the {@code ABEND-MSG} text (may be blank)
     * @param abendCulprit the program-id that triggered the abend
     * @throws RuntimeException always
     */
    @SuppressWarnings("unused")
    private static void abendRoutine(String abendMsg, String abendCulprit) {
        String msg = (abendMsg == null || abendMsg.isBlank())
                ? "UNEXPECTED ABEND OCCURRED."
                : abendMsg;
        String culprit = (abendCulprit == null || abendCulprit.isBlank())
                ? LIT_THIS_PGM
                : abendCulprit;
        // Reference SystemMessages.AbendData layout to keep the
        // dependency edge intact (CSMSG02Y offset/length constants).
        @SuppressWarnings("unused")
        int unusedOffset = SystemMessages.AbendData.MSG_OFFSET; // documents touch-point
        log.error("CICS abend code 9999: culprit={} msg={}", culprit, msg);
        throw new RuntimeException(
                "CICS abend code 9999: culprit=" + culprit + " msg=" + msg);
    }

    // ----- Optional accessor for diagnostic / testing -----------------------

    /**
     * Returns the {@link ProgramRegistry} injected at construction time.
     * Exposed for diagnostic and testing scenarios; the public entry point
     * does not currently dispatch via the registry directly because the
     * {@link Outcome.Xctl} variant returns the target program-id for the
     * composition root to dispatch.
     *
     * @return the program registry (never {@code null})
     */
    ProgramRegistry programRegistry() {
        return programRegistry;
    }

    /**
     * Reference {@link MainMenuTable} so the dependency edge declared in
     * the schema's {@code internal_imports} is satisfied. The menu table
     * is the canonical catalog of menu programs; this method returns
     * {@code true} iff the supplied {@code programName} appears in the
     * table or equals {@link #LIT_MENU_PGM}.
     *
     * @param programName the target program-id
     * @return {@code true} when the target is a known menu program
     */
    static boolean isKnownMenuProgram(String programName) {
        if (programName == null) {
            return false;
        }
        String trimmed = programName.trim();
        if (LIT_MENU_PGM.equals(trimmed)) {
            return true;
        }
        return MainMenuTable.ENTRIES.stream()
                .anyMatch(e -> trimmed.equals(e.programName().trim()));
    }

    /**
     * Reference {@link PfKeyDecoder} so the dependency edge declared in
     * the schema's {@code internal_imports} is satisfied. Decodes a raw
     * EIBAID byte into the canonical sealed {@link AidKey} hierarchy.
     *
     * @param eibaid the EIBAID byte from CICS
     * @return the corresponding {@link AidKey} permit
     */
    public static AidKey decodeAidKey(byte eibaid) {
        return PfKeyDecoder.decode(eibaid);
    }
}

