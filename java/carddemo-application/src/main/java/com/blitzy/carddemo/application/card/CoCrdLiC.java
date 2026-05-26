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

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by
// the java.base module (and the modules it reads). This brings in java.lang (Objects,
// Long, String, Math, RuntimeException, IllegalArgumentException, NullPointerException),
// java.util (Arrays, Locale, Iterator), java.time (LocalDateTime, format.DateTimeFormatter),
// and java.util.stream (Stream) — all used by the card-list pagination, filter
// validation, row buffer initialization, and header date/time formatting logic below.
import module java.base;

// Module-import declarations cannot import application-defined types; the COBOL
// traceability annotation, sealed interfaces, port interfaces, domain records, screen
// constants, the program registry, and SLF4J's logging facade must therefore be
// brought in with conventional single-type imports.
import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of COBOL program {@code COCRDLIC} — the <em>Card List</em>
 * online transaction ({@code CCLI}, mapset {@code COCRDLI}, map {@code CCRDLIA}).
 *
 * <p>The original COBOL source lives at {@code app/cbl/COCRDLIC.cbl} (1460 lines,
 * 20 paragraphs) and is preserved unmodified per AAP &sect;0.2.2 as the reference
 * implementation. This class is the idiom-for-idiom Java translation per AAP
 * &sect;0.7.1 (preserve current behavior exactly).
 *
 * <h2>Role &mdash; card-list controller</h2>
 * Drives the paginated listing of the {@code CARDDAT} card-master VSAM KSDS in
 * a 7-row-per-page presentation, with optional account-id and card-number
 * filter inputs. Selection chars {@code 'S'/'s'} or {@code 'U'/'u'} on a row
 * dispatch to card-view ({@code COCRDSLC}) or card-update ({@code COCRDUPC})
 * respectively, propagating the chosen account-id and card-number through the
 * commarea per AAP &sect;0.4.2.
 *
 * <h2>Translation strategy</h2>
 * <ul>
 *   <li><strong>Constructor injection</strong> &mdash; the three collaborators
 *       ({@link CardRepository}, {@link CardXrefRepository},
 *       {@link ProgramRegistry}) are passed via the constructor. NO Spring
 *       framework is used (AAP &sect;0.1.1 architecture override).</li>
 *   <li><strong>Sealed {@link Outcome} return type</strong> &mdash; replaces
 *       {@code EXEC CICS SEND MAP} + {@code EXEC CICS RETURN} with
 *       {@link Outcome.SendMap} and {@code EXEC CICS XCTL} with
 *       {@link Outcome.Xctl}. The composition root in {@code carddemo-app}
 *       dispatches based on the runtime type.</li>
 *   <li><strong>Pattern-matching switch over {@link AidKey}</strong> with
 *       exhaustive coverage of all 16 sealed permits (Enter, Clear, Pa1, Pa2,
 *       PfKey01..PfKey12). NO {@code default} branch per AAP &sect;0.6.</li>
 *   <li><strong>Mutable working storage</strong> &mdash; the private inner
 *       {@link MutableState} class hosts the working-storage scratchpad for a
 *       single invocation. Never shared across invocations; never a
 *       {@link ThreadLocal}.</li>
 *   <li><strong>Streams from ports</strong> &mdash; {@code EXEC CICS STARTBR
 *       / READNEXT / READPREV} are realized as
 *       {@link CardRepository#streamFrom(String)} and
 *       {@link CardRepository#streamSequential()}. Streams are closed with
 *       try-with-resources because both methods return {@link AutoCloseable}
 *       streams backed by file channels per the port contract.</li>
 *   <li><strong>PAN masking</strong> &mdash; per AAP &sect;0.7.2 security
 *       mandate, all log statements that include a card number use
 *       {@link #maskPan(String)} so the first 12 digits are replaced by
 *       {@code '*'} characters and only the last 4 are visible.</li>
 * </ul>
 *
 * <h2>COBOL paragraph mapping</h2>
 * <table>
 *   <caption>Paragraph-to-method translation</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>0000-MAIN (298-621)</td>
 *       <td>{@link #mainEntry(CardDemoCommarea, AidKey, CoCrdLiInput)}</td></tr>
 *   <tr><td>1000-SEND-MAP (624-639)</td>
 *       <td>{@link #sendMap(MutableState)}</td></tr>
 *   <tr><td>1100-SCREEN-INIT (642-674)</td>
 *       <td>{@link #screenInit(MutableState)}</td></tr>
 *   <tr><td>1400-SETUP-MESSAGE (895-933)</td>
 *       <td>{@link #setupMessage(MutableState)}</td></tr>
 *   <tr><td>1500-SEND-SCREEN (938-948)</td>
 *       <td>{@link #sendScreen(MutableState)}</td></tr>
 *   <tr><td>2100-RECEIVE-SCREEN (962-981)</td>
 *       <td>{@link #receiveScreen(CoCrdLiInput, MutableState)}</td></tr>
 *   <tr><td>2200-EDIT-INPUTS (985-999)</td>
 *       <td>{@link #editInputs(CoCrdLiInput, MutableState)}</td></tr>
 *   <tr><td>2210-EDIT-ACCOUNT (1003-1032)</td>
 *       <td>{@link #editAccount(MutableState)}</td></tr>
 *   <tr><td>2220-EDIT-CARD (1036-1069)</td>
 *       <td>{@link #editCard(MutableState)}</td></tr>
 *   <tr><td>2250-EDIT-ARRAY (1073-1119)</td>
 *       <td>{@link #editArray(CoCrdLiInput, MutableState)}</td></tr>
 *   <tr><td>9000-READ-FORWARD (1123-1261)</td>
 *       <td>{@link #readForward(MutableState)}</td></tr>
 *   <tr><td>9100-READ-BACKWARDS (1264-1374)</td>
 *       <td>{@link #readBackwards(MutableState)}</td></tr>
 *   <tr><td>9500-FILTER-RECORDS (1382-1409)</td>
 *       <td>{@link #filterRecord(CardRecord, MutableState)}</td></tr>
 * </table>
 *
 * <h2>Thread-safety</h2>
 * <p>This class is stateless aside from the constructor-injected collaborators
 * (which are expected to be thread-safe per their port contracts). Per-invocation
 * working storage is local to each call to
 * {@link #execute(CardDemoCommarea, AidKey, CoCrdLiInput)} via the inner
 * {@link MutableState} instance, so concurrent invocations on the same
 * {@link CoCrdLiC} instance are safe.
 */
@CobolProgram(
        value = "COCRDLIC",
        sourcePath = "app/cbl/COCRDLIC.cbl",
        translationDate = "2025-09-16",
        notes = "Card list online with 7-row paged display, optional account-id and "
              + "card-id filters. Selection chars 'S' or 's' dispatch to COCRDSLC "
              + "(view); 'U' or 'u' dispatch to COCRDUPC (update). PF7 paginates "
              + "backward; PF8 paginates forward; PF3 exits to COMEN01C (Main Menu). "
              + "Filter logic: admin user can list all cards; non-admin user is "
              + "restricted to the account in commarea."
)
public final class CoCrdLiC {

    // ========================================================================
    // Logging
    // ========================================================================

    /** SLF4J class-scoped logger. PAN values must always be masked via
     *  {@link #maskPan(String)} before being passed to any log method, per
     *  AAP &sect;0.7.2 security mandate. */
    private static final Logger log = LoggerFactory.getLogger(CoCrdLiC.class);

    // ========================================================================
    // Program identity literals (verbatim from COCRDLIC WS-CONSTANTS, lines 175-220)
    // ========================================================================

    /** Maximum rows per page; from COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}. */
    private static final int MAX_SCREEN_LINES = 7;

    /** Program-id of this transaction handler. From {@code LIT-THISPGM}. */
    private static final String LIT_THIS_PGM        = "COCRDLIC";
    /** Transaction-id of this handler. From {@code LIT-THISTRANID}. 4-char width. */
    private static final String LIT_THIS_TRAN_ID    = "CCLI";
    /** Mapset used by this handler. From {@code LIT-THISMAPSET}. */
    private static final String LIT_THIS_MAPSET     = "COCRDLI";
    /** Map within the mapset. From {@code LIT-THISMAP}. */
    private static final String LIT_THIS_MAP        = "CCRDLIA";

    // ---- XCTL target: Main Menu ----
    /** Program-id of main menu. From {@code LIT-MENUPGM}. */
    private static final String LIT_MENU_PGM        = "COMEN01C";
    /** Transaction-id of main menu. From {@code LIT-MENUTRANID}. */
    private static final String LIT_MENU_TRAN_ID    = "CM00";
    /** Mapset of main menu. From {@code LIT-MENUMAPSET}. */
    private static final String LIT_MENU_MAPSET     = "COMEN01";
    /** Map of main menu. From {@code LIT-MENUMAP}. */
    private static final String LIT_MENU_MAP        = "COMEN1A";

    // ---- XCTL target: Card Detail (View) ----
    /** Program-id of card view. From {@code LIT-CARDDTLPGM}. */
    private static final String LIT_CARDDTL_PGM     = "COCRDSLC";
    /** Transaction-id of card view. From {@code LIT-CARDDTLTRANID}. */
    private static final String LIT_CARDDTL_TRAN_ID = "CCDL";
    /** Mapset of card view. From {@code LIT-CARDDTLMAPSET}. */
    private static final String LIT_CARDDTL_MAPSET  = "COCRDSL";
    /** Map of card view. From {@code LIT-CARDDTLMAP}. */
    private static final String LIT_CARDDTL_MAP     = "CCRDSLA";

    // ---- XCTL target: Card Update ----
    /** Program-id of card update. From {@code LIT-CARDUPDPGM}. */
    private static final String LIT_CARDUPD_PGM     = "COCRDUPC";
    /** Transaction-id of card update. From {@code LIT-CARDUPDTRANID}. */
    private static final String LIT_CARDUPD_TRAN_ID = "CCUP";
    /** Mapset of card update. From {@code LIT-CARDUPDMAPSET}. */
    private static final String LIT_CARDUPD_MAPSET  = "COCRDUP";
    /** Map of card update. From {@code LIT-CARDUPDMAP}. */
    private static final String LIT_CARDUPD_MAP     = "CCRDUPA";

    // ---- File-name literals (informational; used only in log messages) ----
    /** Card-master KSDS file name. From {@code LIT-CARD-FILE}. */
    private static final String LIT_CARD_FILE             = "CARDDAT";
    /** Card-master alternate-index file name. From {@code LIT-CARD-FILE-ACCT-PATH}. */
    private static final String LIT_CARD_FILE_ACCT_PATH   = "CARDAIX";

    // ========================================================================
    // COBOL error / info message literals (verbatim from COCRDLIC.cbl)
    // ========================================================================

    /** Filter-validation error for malformed account-id filter (line 1022).
     *  Comma-with-no-space preserved verbatim per AAP &sect;0.7.1. */
    private static final String MSG_ACCT_FILTER_BAD =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    /** Filter-validation error for malformed card-id filter (line 1058). */
    private static final String MSG_CARD_FILTER_BAD =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    /** Multi-selection error from 2250-EDIT-ARRAY (line 124 of WS-MESSAGES). */
    private static final String MSG_MULTI_SELECT_ERROR =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";
    /** Bad selection-char error from 2250-EDIT-ARRAY (line 126). */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";
    /** No-previous-pages info message (line 903). */
    private static final String MSG_NO_PREV_PAGES = "NO PREVIOUS PAGES TO DISPLAY";
    /** No-more-pages info message (line 908). */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";
    /** Default screen info message (line 116). */
    private static final String MSG_TYPE_S_OR_U =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
    /** No-records-found message (line 122). */
    private static final String MSG_NO_RECORDS =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";
    /** PF03-exit info message (line 120). */
    private static final String MSG_PF03_PRESSED = "PF03 PRESSED.EXITING";

    // ========================================================================
    // Date/time formatters for the screen header (CSDAT01Y patterns)
    // ========================================================================

    /** Screen-header date format: MM/DD/YY (8 chars). From CSDAT01Y. */
    private static final java.time.format.DateTimeFormatter CUR_DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy");
    /** Screen-header time format: HH:MM:SS (8 chars, 24-hour). From CSDAT01Y. */
    private static final java.time.format.DateTimeFormatter CUR_TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    // ========================================================================
    // Sealed Outcome type — replaces EXEC CICS SEND MAP / EXEC CICS XCTL
    // ========================================================================

    /**
     * Disposition of a single invocation of {@code COCRDLIC}.
     *
     * <p>Either send a map back to the user ({@link SendMap}) or transfer
     * control to another program ({@link Xctl}). Models the COBOL CICS
     * terminal verbs:
     * <ul>
     *   <li>{@link SendMap} &mdash; corresponds to {@code EXEC CICS SEND MAP}
     *       followed by {@code EXEC CICS RETURN TRANSID(CCLI) COMMAREA(...)}.
     *       The caller is expected to render {@link CoCrdLiOutput} on the BMS
     *       screen and route the next user input back into
     *       {@link CoCrdLiC#execute(CardDemoCommarea, AidKey, CoCrdLiInput)}
     *       with the supplied commarea preserved.</li>
     *   <li>{@link Xctl} &mdash; corresponds to {@code EXEC CICS XCTL
     *       PROGRAM(...) COMMAREA(...)}. The caller is expected to dispatch
     *       through {@link ProgramRegistry} to the target program, carrying
     *       the supplied commarea.</li>
     * </ul>
     *
     * <p>The sealed-type pattern with two permits guarantees the composition
     * root can use a pattern-matching switch with compile-time exhaustiveness
     * checks per AAP &sect;0.6.
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * {@code EXEC CICS SEND MAP} + {@code EXEC CICS RETURN} outcome.
         *
         * @param output   the populated BMS output record to send to the
         *                 terminal; never {@code null}
         * @param commarea the round-trip commarea preserved across the
         *                 dispatch; never {@code null}
         */
        record SendMap(CoCrdLiOutput output, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing non-null arguments.
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
         * {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)} outcome.
         *
         * @param targetProgram destination program-id (an 8-character
         *                      left-justified COBOL program name, e.g.
         *                      {@code "COMEN01C"}); never {@code null} or
         *                      blank
         * @param commarea      commarea to pass to the target program;
         *                      never {@code null}
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforcing non-null arguments and a
             * non-blank target program-id.
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

    // ========================================================================
    // Constructor injection (NO Spring; plain Java factories per AAP §0.1.1)
    // ========================================================================

    /** Card-master repository ({@code CARDDAT} KSDS port). */
    private final CardRepository cardRepository;
    /** Card cross-reference repository ({@code CARDAIX} alternate index port). */
    private final CardXrefRepository cardXrefRepository;
    /** Dynamic-CALL routing facility (for XCTL invocations). */
    private final ProgramRegistry programRegistry;

    /**
     * Constructs the card-list controller with the three required
     * collaborators. All arguments are mandatory.
     *
     * @param cardRepository     card-master repository (port); must not be
     *                           {@code null}
     * @param cardXrefRepository card cross-reference repository (port); must
     *                           not be {@code null}
     * @param programRegistry    dynamic-CALL routing facility; must not be
     *                           {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoCrdLiC(CardRepository cardRepository,
                    CardXrefRepository cardXrefRepository,
                    ProgramRegistry programRegistry) {
        this.cardRepository     = Objects.requireNonNull(cardRepository, "cardRepository");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "cardXrefRepository");
        this.programRegistry    = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    // ========================================================================
    // Public entry method
    // ========================================================================

    /**
     * Execute one invocation of {@code COCRDLIC} (Card List online transaction).
     *
     * <p>Translates the COBOL {@code 0000-MAIN} paragraph: optionally receives
     * the BMS map, validates filter inputs ({@code 2210-EDIT-ACCOUNT},
     * {@code 2220-EDIT-CARD}), processes row selections
     * ({@code 2250-EDIT-ARRAY}), and dispatches to the next program if a
     * selection was made. Otherwise paginates forward
     * ({@code 9000-READ-FORWARD}) or backward ({@code 9100-READ-BACKWARDS})
     * based on the {@link AidKey}.
     *
     * <p>Translation of CICS idioms per AAP &sect;0.6:
     * <ul>
     *   <li>{@code EXEC CICS XCTL} &rarr; {@link Outcome.Xctl} returned to
     *       caller for routing</li>
     *   <li>{@code EXEC CICS RETURN ... COMMAREA(...)} &rarr;
     *       {@link Outcome.SendMap} with commarea</li>
     *   <li>{@code EXEC CICS STARTBR / READNEXT} &rarr;
     *       {@link CardRepository#streamFrom(String)}</li>
     * </ul>
     *
     * @param commareaIn the incoming commarea (must not be {@code null}; pass
     *                   {@link CardDemoCommarea#empty()} for the first entry)
     * @param aidKey     the AID key the user pressed (must not be
     *                   {@code null}; decoded externally via
     *                   {@code PfKeyDecoder.fromEibaid(...)})
     * @param input      the BMS map input (must not be {@code null}; pass
     *                   {@link CoCrdLiInput#blank()} for the first entry)
     * @return an {@link Outcome} describing what to do next; never
     *         {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public Outcome execute(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdLiInput input) {
        Objects.requireNonNull(commareaIn, "commareaIn");
        Objects.requireNonNull(aidKey, "aidKey");
        Objects.requireNonNull(input, "input");
        return mainEntry(commareaIn, aidKey, input);
    }

    // ========================================================================
    // 0000-MAIN translation
    // ========================================================================

    /**
     * Translation of COBOL paragraph {@code 0000-MAIN} (lines 298-621).
     *
     * <p>Coordinates:
     * <ol>
     *   <li>Header initialization (date/time/page-no/program-name).</li>
     *   <li>First-entry detection based on
     *       {@link PgmContext} of the commarea: a {@link PgmContext.Enter}
     *       value indicates a brand-new dispatch, so we skip RECEIVE-MAP,
     *       skip filter validation, and go straight to a forward read.</li>
     *   <li>On re-entry: {@link #receiveScreen(CoCrdLiInput, MutableState)}
     *       copies BMS fields into mutable state.</li>
     *   <li>{@link #editInputs(CoCrdLiInput, MutableState)} runs the
     *       2210/2220/2250 validation chain. If any validation failed
     *       ({@code inputError}) we re-send the map with the error.</li>
     *   <li>If a row selection was made (S/U in {@code editArray}), build
     *       an XCTL outcome to COCRDSLC or COCRDUPC.</li>
     *   <li>Otherwise dispatch on {@link AidKey} via an exhaustive
     *       pattern-matching switch: PF3 &rarr; XCTL to menu, PF7 &rarr;
     *       backward, PF8 &rarr; forward, Enter &rarr; re-apply filters,
     *       any other key &rarr; "Invalid key" message.</li>
     * </ol>
     *
     * @param commareaIn the incoming commarea
     * @param aidKey     the decoded AID key
     * @param input      the BMS input record
     * @return the outcome for this invocation
     */
    private Outcome mainEntry(CardDemoCommarea commareaIn, AidKey aidKey, CoCrdLiInput input) {
        final MutableState s = new MutableState();
        CardDemoCommarea commarea = commareaIn;

        // ---- Header init (corresponds to 1100-SCREEN-INIT setup of header fields) ----
        final LocalDateTime now = LocalDateTime.now();
        s.curDate = now.format(CUR_DATE_FMT);
        s.curTime = now.format(CUR_TIME_FMT);
        s.pageNoStr = "001";

        log.debug("COCRDLIC entry: pgmContext={}, aidKey={}",
                commarea.cdemoGeneralInfo().pgmContext(),
                aidKey.mnemonic());

        // ---- Account-restriction mode per AAP schema notes ----
        // Admin users (CDEMO-USRTYP-ADMIN 'A') can list all cards; non-admin
        // users (CDEMO-USRTYP-USER 'U') are restricted to cards belonging to
        // the account already in commarea. This mirrors COBOL 9000-READ-FORWARD
        // lines 1123-1261 where the CARDAIX alternate-index path is taken
        // when the user lacks admin privileges.
        final boolean isAdmin = commarea.cdemoGeneralInfo().userType() instanceof UserType.Admin;
        final long acctIdInCommarea = commarea.cdemoAccountInfo().acctId();
        s.accountRestricted = !isAdmin && acctIdInCommarea > 0L;
        s.restrictedAcctId = s.accountRestricted ? acctIdInCommarea : 0L;

        // ---- First-entry detection per AAP §0.6 / COCRDLIC.cbl line 305-320 ----
        // PgmContext.Enter signals brand-new dispatch (CDEMO-PGM-ENTER true).
        // Skip RECEIVE-MAP and go straight to a forward read.
        final boolean firstEntry =
                commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Enter;

        if (firstEntry) {
            log.debug("COCRDLIC first entry — reading first page from start of file");
            s.currentCardKey = "";
            s.currentPageNo = 1;
            s.firstPage = true;
            s.lastPageShown = false;
            readForward(s);
            setupMessage(s);
            return new Outcome.SendMap(sendScreen(s), commarea);
        }

        // ---- Re-entry: 2000-RECEIVE-MAP → 2100-RECEIVE-SCREEN ----
        receiveScreen(input, s);

        // ---- 2200-EDIT-INPUTS: validate filters AND check row selections ----
        editInputs(input, s);

        // ---- COBOL 0000-MAIN: WHEN CCARD-AID-PFK03 → XCTL to menu ----
        // PF03 is the explicit "exit" key per source lines 372-373.
        if (aidKey instanceof AidKey.PfKey03) {
            log.debug("COCRDLIC PF03 pressed — exiting to {}", LIT_MENU_PGM);
            s.infoMsg = MSG_PF03_PRESSED;
            return new Outcome.Xctl(LIT_MENU_PGM, buildExitCommarea(commarea));
        }

        // ---- COBOL 0000-MAIN: WHEN INPUT-ERROR → re-send map with error ----
        // editArray populated errorMsg and inputError; do NOT continue to selection
        // dispatch or further paging until the user corrects the input.
        if (s.inputError) {
            log.debug("COCRDLIC input error: {}", s.errorMsg);
            // Repopulate row buffers from any previous successful read so the
            // user can see them while correcting their input. The COBOL source
            // re-issues 9000-READ-FORWARD with the (preserved) current key.
            readForward(s);
            setupMessage(s);
            return new Outcome.SendMap(sendScreen(s), commarea);
        }

        // ---- COBOL 0000-MAIN: WHEN CCARD-AID-ENTER AND selection made → XCTL ----
        // editArray populated selectedAction when a row had a valid 'S' or 'U';
        // first-match-wins per COBOL 2250-EDIT-ARRAY semantics. Per source
        // lines 391-412 the selection dispatch fires only on ENTER and only
        // when CDEMO-FROM-PROGRAM equals LIT-THISPGM (i.e., we came from a
        // SEND MAP of this same program, not from menu dispatch).
        final boolean fromThisProgram = LIT_THIS_PGM.equals(
                stripTrailing(commarea.cdemoGeneralInfo().fromProgram()));
        if (aidKey instanceof AidKey.Enter && fromThisProgram && !s.selectedAction.isEmpty()) {
            final String target = "S".equalsIgnoreCase(s.selectedAction)
                    ? LIT_CARDDTL_PGM
                    : LIT_CARDUPD_PGM;
            log.info("COCRDLIC dispatching to {} for selectedCard={} selectedAcct={}",
                    target, maskPan(s.selectedCardNum), s.selectedAcctId);
            return new Outcome.Xctl(target,
                    buildSelectionCommarea(commarea, s.selectedAcctId,
                            s.selectedCardNum, target));
        }

        // ---- COBOL 0000-MAIN EVALUATE: handle PF7/PF8/ENTER/OTHER ----
        // Exhaustive pattern-matching switch over all 16 AidKey permits.
        // No default branch — exhaustiveness is the compile-time guarantee.
        return switch (aidKey) {
            case AidKey.PfKey07 _ -> {
                // Backward paging — but only if not on the first page.
                if (s.firstPage) {
                    s.infoMsg = MSG_NO_PREV_PAGES;
                    yield new Outcome.SendMap(sendScreen(s), commarea);
                }
                readBackwards(s);
                setupMessage(s);
                yield new Outcome.SendMap(sendScreen(s), commarea);
            }
            case AidKey.PfKey08 _ -> {
                // Forward paging — but only if more records exist.
                if (s.lastPageShown) {
                    s.infoMsg = MSG_NO_MORE_PAGES;
                    yield new Outcome.SendMap(sendScreen(s), commarea);
                }
                readForward(s);
                setupMessage(s);
                yield new Outcome.SendMap(sendScreen(s), commarea);
            }
            case AidKey.Enter _ -> {
                // ENTER with no selection: re-apply filters and re-display
                // first page. Per COBOL line 421-440 (WHEN OTHER) the screen
                // is re-rendered with the validated filters.
                s.currentCardKey = "";
                s.currentPageNo = 1;
                s.firstPage = true;
                s.lastPageShown = false;
                readForward(s);
                setupMessage(s);
                yield new Outcome.SendMap(sendScreen(s), commarea);
            }
            case AidKey.PfKey03 _ -> {
                // PF03 already handled before the switch; included for
                // exhaustiveness. Same behavior: XCTL to menu.
                s.infoMsg = MSG_PF03_PRESSED;
                yield new Outcome.Xctl(LIT_MENU_PGM, buildExitCommarea(commarea));
            }
            // All other AID keys are invalid for the CCLI transaction.
            // Per COBOL 1400-SETUP-MESSAGE (lines 895-933) the "Invalid key"
            // message is set and the screen is re-rendered.
            case AidKey.Clear _,
                 AidKey.Pa1 _,
                 AidKey.Pa2 _,
                 AidKey.PfKey01 _,
                 AidKey.PfKey02 _,
                 AidKey.PfKey04 _,
                 AidKey.PfKey05 _,
                 AidKey.PfKey06 _,
                 AidKey.PfKey09 _,
                 AidKey.PfKey10 _,
                 AidKey.PfKey11 _,
                 AidKey.PfKey12 _ -> {
                log.debug("COCRDLIC invalid AID key: {}", aidKey.mnemonic());
                s.errorMsg = SystemMessages.INVALID_KEY_MSG;
                // Refresh the row buffers so the user still sees data
                readForward(s);
                yield new Outcome.SendMap(sendScreen(s), commarea);
            }
        };
    }

    // ========================================================================
    // 1000-SEND-MAP family
    // ========================================================================

    /**
     * Translation of COBOL paragraph {@code 1000-SEND-MAP} (lines 624-639).
     *
     * <p>Orchestrates the screen-output paragraphs in COBOL order:
     * 1100-SCREEN-INIT &rarr; 1400-SETUP-MESSAGE &rarr; 1500-SEND-SCREEN.
     *
     * @param s the mutable state for this invocation
     * @return the composed BMS output record
     */
    private CoCrdLiOutput sendMap(MutableState s) {
        screenInit(s);
        setupMessage(s);
        return sendScreen(s);
    }

    /**
     * Translation of COBOL paragraph {@code 1100-SCREEN-INIT} (lines 642-674).
     *
     * <p>Initializes the screen header constants: trans-id, titles, current
     * date, current time, program-name, page-number. These have already been
     * populated by {@link #mainEntry(CardDemoCommarea, AidKey, CoCrdLiInput)}
     * for the date/time/page-no via {@link MutableState}; this method serves
     * primarily as a no-op formal translation of the corresponding COBOL
     * paragraph for traceability. The pageNoStr formatting is reasserted here
     * to ensure consistency.
     *
     * @param s the mutable state for this invocation
     */
    private void screenInit(MutableState s) {
        // Ensure pageNoStr matches currentPageNo (3-char zero-padded).
        s.pageNoStr = String.format("%03d", Math.max(1, s.currentPageNo));
    }

    /**
     * Translation of COBOL paragraph {@code 1400-SETUP-MESSAGE} (lines 895-933).
     *
     * <p>Establishes the info/error message displayed on the bottom two rows
     * of the BMS screen. Priority order per COBOL:
     * <ol>
     *   <li>If {@code errorMsg} is non-blank, do nothing (caller already set it).</li>
     *   <li>Otherwise if zero rows were populated (empty result set), set
     *       "NO RECORDS FOUND FOR THIS SEARCH CONDITION.".</li>
     *   <li>Otherwise set the default "TYPE S FOR DETAIL, U TO UPDATE ANY
     *       RECORD" prompt.</li>
     * </ol>
     *
     * @param s the mutable state for this invocation
     */
    private void setupMessage(MutableState s) {
        if (!s.errorMsg.isEmpty()) {
            // Error already set by the validation chain; leave it.
            return;
        }
        if (s.infoMsg.isEmpty()) {
            if (s.rowCount == 0) {
                s.infoMsg = MSG_NO_RECORDS;
            } else {
                s.infoMsg = MSG_TYPE_S_OR_U;
            }
        }
    }

    /**
     * Translation of COBOL paragraph {@code 1500-SEND-SCREEN} (lines 938-948).
     *
     * <p>Composes the final {@link CoCrdLiOutput} by gathering every field
     * from the {@link MutableState} into the 45-field record. The 7-row
     * detail area is built from the {@code rowCardNums / rowAcctIds /
     * rowStatuses / rowSelects} buffers; row 1 has no {@code crdStp1}
     * component per the BMS copybook layout, while rows 2-7 each carry a
     * {@code crdStp{N}} echo equal to the corresponding selection char.
     *
     * @param s the mutable state for this invocation
     * @return a non-null {@link CoCrdLiOutput} ready to send via BMS
     */
    private CoCrdLiOutput sendScreen(MutableState s) {
        return new CoCrdLiOutput(
                // Header fields (7)
                LIT_THIS_TRAN_ID,                  // trnName (4)
                ScreenTitle.TITLE_01,              // title01 (40)
                s.curDate,                         // curDate (8)
                LIT_THIS_PGM,                      // pgmName (8)
                ScreenTitle.TITLE_02,              // title02 (40)
                s.curTime,                         // curTime (8)
                s.pageNoStr,                       // pageNo (3)
                // Filter echoes (2)
                s.acctSidFilter,                   // acctSidFilter (11)
                s.cardSidFilter,                   // cardSidFilter (16)
                // Row 1 (4 fields — no crdStp1 per copybook)
                s.rowSelects[0], s.rowAcctIds[0], s.rowCardNums[0], s.rowStatuses[0],
                // Row 2 (5 fields)
                s.rowSelects[1], s.rowSelects[1], s.rowAcctIds[1], s.rowCardNums[1], s.rowStatuses[1],
                // Row 3 (5 fields)
                s.rowSelects[2], s.rowSelects[2], s.rowAcctIds[2], s.rowCardNums[2], s.rowStatuses[2],
                // Row 4 (5 fields)
                s.rowSelects[3], s.rowSelects[3], s.rowAcctIds[3], s.rowCardNums[3], s.rowStatuses[3],
                // Row 5 (5 fields)
                s.rowSelects[4], s.rowSelects[4], s.rowAcctIds[4], s.rowCardNums[4], s.rowStatuses[4],
                // Row 6 (5 fields)
                s.rowSelects[5], s.rowSelects[5], s.rowAcctIds[5], s.rowCardNums[5], s.rowStatuses[5],
                // Row 7 (5 fields)
                s.rowSelects[6], s.rowSelects[6], s.rowAcctIds[6], s.rowCardNums[6], s.rowStatuses[6],
                // Footer (2)
                padToLength(s.infoMsg, 45),
                padToLength(s.errorMsg, 78)
        );
    }

    // ========================================================================
    // 2000-RECEIVE-MAP family
    // ========================================================================

    /**
     * Translation of COBOL paragraph {@code 2100-RECEIVE-SCREEN} (lines 962-981).
     *
     * <p>Copies BMS input fields into mutable working storage:
     * {@code ACCTSIDI} &rarr; {@code acctSidFilter}, {@code CARDSIDI} &rarr;
     * {@code cardSidFilter}, {@code CRDSEL{N}I} &rarr; {@code rowSelects[N-1]}.
     *
     * @param input the BMS input record
     * @param s     the mutable state for this invocation
     */
    private void receiveScreen(CoCrdLiInput input, MutableState s) {
        s.acctSidFilter = trimToWidth(input.acctSidFilter(), 11);
        s.cardSidFilter = trimToWidth(input.cardSidFilter(), 16);

        // Capture row selection chars (CRDSEL1..CRDSEL7) preserving exactly
        // the COBOL semantic: a non-blank char in any of these cells could
        // be 'S', 'U', or an invalid char. The trim is intentional — BMS
        // returns space-padded fields.
        s.rowSelects[0] = orEmpty(input.crdSel1()).trim();
        s.rowSelects[1] = orEmpty(input.crdSel2()).trim();
        s.rowSelects[2] = orEmpty(input.crdSel3()).trim();
        s.rowSelects[3] = orEmpty(input.crdSel4()).trim();
        s.rowSelects[4] = orEmpty(input.crdSel5()).trim();
        s.rowSelects[5] = orEmpty(input.crdSel6()).trim();
        s.rowSelects[6] = orEmpty(input.crdSel7()).trim();

        // Also preserve the echoed row data (account/card/status) so we can
        // reconstruct selectedAcctId / selectedCardNum when the user submits.
        s.rowAcctIds[0] = orEmpty(input.acctNo1());
        s.rowAcctIds[1] = orEmpty(input.acctNo2());
        s.rowAcctIds[2] = orEmpty(input.acctNo3());
        s.rowAcctIds[3] = orEmpty(input.acctNo4());
        s.rowAcctIds[4] = orEmpty(input.acctNo5());
        s.rowAcctIds[5] = orEmpty(input.acctNo6());
        s.rowAcctIds[6] = orEmpty(input.acctNo7());

        s.rowCardNums[0] = orEmpty(input.crdNum1());
        s.rowCardNums[1] = orEmpty(input.crdNum2());
        s.rowCardNums[2] = orEmpty(input.crdNum3());
        s.rowCardNums[3] = orEmpty(input.crdNum4());
        s.rowCardNums[4] = orEmpty(input.crdNum5());
        s.rowCardNums[5] = orEmpty(input.crdNum6());
        s.rowCardNums[6] = orEmpty(input.crdNum7());

        s.rowStatuses[0] = orEmpty(input.crdSts1());
        s.rowStatuses[1] = orEmpty(input.crdSts2());
        s.rowStatuses[2] = orEmpty(input.crdSts3());
        s.rowStatuses[3] = orEmpty(input.crdSts4());
        s.rowStatuses[4] = orEmpty(input.crdSts5());
        s.rowStatuses[5] = orEmpty(input.crdSts6());
        s.rowStatuses[6] = orEmpty(input.crdSts7());

        // Compute rowCount as the index of the last non-empty echoed row+1.
        s.rowCount = 0;
        for (int i = 0; i < MAX_SCREEN_LINES; i++) {
            if (!s.rowCardNums[i].isBlank() || !s.rowAcctIds[i].isBlank()) {
                s.rowCount = i + 1;
            }
        }

        log.debug("COCRDLIC received: acctFilter='{}' cardFilter='{}' rowCount={}",
                s.acctSidFilter, maskPan(s.cardSidFilter), s.rowCount);
    }

    /**
     * Translation of COBOL paragraph {@code 2200-EDIT-INPUTS} (lines 985-999).
     *
     * <p>Calls the sub-validation paragraphs in sequence:
     * 2210-EDIT-ACCOUNT &rarr; 2220-EDIT-CARD &rarr; 2250-EDIT-ARRAY. Any
     * failure sets {@code s.inputError = true} and {@code s.errorMsg} which
     * the caller checks before proceeding.
     *
     * @param input the BMS input record
     * @param s     the mutable state for this invocation
     */
    private void editInputs(CoCrdLiInput input, MutableState s) {
        editAccount(s);
        if (s.inputError) {
            return;
        }
        editCard(s);
        if (s.inputError) {
            return;
        }
        editArray(input, s);
    }

    /**
     * Translation of COBOL paragraph {@code 2210-EDIT-ACCOUNT} (lines 1003-1032).
     *
     * <p>Validates the account-id filter:
     * <ul>
     *   <li>Blank/empty &rarr; flag {@code BLANK} (no filter).</li>
     *   <li>Non-blank, exactly 11 digits &rarr; flag {@code IS_VALID}.</li>
     *   <li>Anything else &rarr; flag {@code NOT_OK}, error message
     *       "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", and
     *       {@code inputError = true}.</li>
     * </ul>
     *
     * @param s the mutable state for this invocation
     */
    private void editAccount(MutableState s) {
        final String trimmed = s.acctSidFilter == null
                ? ""
                : s.acctSidFilter.trim();
        if (trimmed.isEmpty()) {
            s.acctFilterFlag = MutableState.FilterFlag.BLANK;
            return;
        }
        // Per COBOL: must be exactly 11 digits, no signs, no leading spaces.
        if (!trimmed.matches("\\d{11}")) {
            s.acctFilterFlag = MutableState.FilterFlag.NOT_OK;
            s.errorMsg = MSG_ACCT_FILTER_BAD;
            s.inputError = true;
            return;
        }
        s.acctFilterFlag = MutableState.FilterFlag.IS_VALID;
    }

    /**
     * Translation of COBOL paragraph {@code 2220-EDIT-CARD} (lines 1036-1069).
     *
     * <p>Validates the card-number filter:
     * <ul>
     *   <li>Blank/empty &rarr; flag {@code BLANK} (no filter).</li>
     *   <li>Non-blank, exactly 16 digits &rarr; flag {@code IS_VALID}.</li>
     *   <li>Anything else &rarr; flag {@code NOT_OK}, error message
     *       "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", and
     *       {@code inputError = true}.</li>
     * </ul>
     *
     * @param s the mutable state for this invocation
     */
    private void editCard(MutableState s) {
        final String trimmed = s.cardSidFilter == null
                ? ""
                : s.cardSidFilter.trim();
        if (trimmed.isEmpty()) {
            s.cardFilterFlag = MutableState.FilterFlag.BLANK;
            return;
        }
        if (!trimmed.matches("\\d{16}")) {
            s.cardFilterFlag = MutableState.FilterFlag.NOT_OK;
            s.errorMsg = MSG_CARD_FILTER_BAD;
            s.inputError = true;
            return;
        }
        s.cardFilterFlag = MutableState.FilterFlag.IS_VALID;
    }

    /**
     * Translation of COBOL paragraph {@code 2250-EDIT-ARRAY} (lines 1073-1119).
     *
     * <p>Scans the seven row selection cells looking for {@code 'S'}/{@code 's'}
     * (view) or {@code 'U'}/{@code 'u'} (update) characters. Semantics:
     * <ul>
     *   <li>Zero selections &rarr; no error; controller will dispatch on AID.</li>
     *   <li>Exactly one selection &rarr; capture the row's card-number and
     *       account-id into {@code selectedCardNum} / {@code selectedAcctId},
     *       and store the selection char in {@code selectedAction}. Per
     *       COBOL <strong>first-match-wins</strong> semantics (the INSPECT
     *       TALLYING approach exits on the first hit when only one is found
     *       — and as we limit to one anyway, the first match is the result).</li>
     *   <li>Two or more selections &rarr; "PLEASE SELECT ONLY ONE RECORD TO
     *       VIEW OR UPDATE" error.</li>
     *   <li>Any character other than blank, S, s, U, u &rarr; "INVALID
     *       ACTION CODE" error.</li>
     * </ul>
     *
     * @param input the BMS input record (only needed for the original selection chars
     *              — present for symmetry with the COBOL signature)
     * @param s     the mutable state for this invocation
     */
    private void editArray(CoCrdLiInput input, MutableState s) {
        // 'input' is intentionally unused beyond the receiveScreen pass; the
        // selection chars are already copied into s.rowSelects. The argument
        // is retained for parity with the COBOL 2250-EDIT-ARRAY signature.
        Objects.requireNonNull(input, "input");

        int firstHitIndex = -1;
        String firstHitAction = "";
        int hitCount = 0;
        boolean invalidChar = false;

        for (int i = 0; i < MAX_SCREEN_LINES; i++) {
            final String raw = s.rowSelects[i] == null
                    ? ""
                    : s.rowSelects[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            final String upper = raw.toUpperCase(Locale.ROOT);
            // Single-character selections only; longer strings are invalid.
            if (upper.length() != 1) {
                invalidChar = true;
                continue;
            }
            final char c = upper.charAt(0);
            if (c == 'S' || c == 'U') {
                if (firstHitIndex < 0) {
                    firstHitIndex = i;
                    firstHitAction = String.valueOf(c);
                }
                hitCount++;
            } else {
                invalidChar = true;
            }
        }

        if (invalidChar) {
            s.errorMsg = MSG_INVALID_ACTION_CODE;
            s.inputError = true;
            return;
        }
        if (hitCount > 1) {
            s.errorMsg = MSG_MULTI_SELECT_ERROR;
            s.inputError = true;
            return;
        }
        if (hitCount == 1) {
            s.selectedAction = firstHitAction;
            s.selectedCardNum = stripTrailing(s.rowCardNums[firstHitIndex]);
            s.selectedAcctId = stripTrailing(s.rowAcctIds[firstHitIndex]);
        }
    }

    // ========================================================================
    // 9000-READ-FORWARD / 9100-READ-BACKWARDS / 9500-FILTER-RECORDS
    // ========================================================================

    /**
     * Translation of COBOL paragraph {@code 9000-READ-FORWARD} (lines 1123-1261).
     *
     * <p>Streams cards from {@link CardRepository} starting at
     * {@code currentCardKey} (empty &rarr; start of file) and fills up to
     * {@link #MAX_SCREEN_LINES} rows that pass the filter. Updates
     * {@code firstCardOnPage}, {@code lastCardOnPage}, {@code currentCardKey},
     * {@code currentPageNo}, {@code firstPage}, and {@code lastPageShown}.
     *
     * <p>The COBOL paragraph uses {@code EXEC CICS STARTBR DATASET(CARDDAT)
     * RIDFLD(WS-CARD-RID-CARDNUM) GTEQ} followed by repeated
     * {@code EXEC CICS READNEXT}. Per AAP &sect;0.6.5 we translate this to a
     * stream-based skip/take operation. The stream is closed via
     * try-with-resources as the port contract mandates.
     *
     * @param s the mutable state for this invocation
     */
    private void readForward(MutableState s) {
        // Reset the row buffer before re-populating.
        clearRows(s);

        // Branch on account-restriction mode: non-admin users traverse only
        // the cross-reference entries for their account; admin users traverse
        // CARDDAT directly.
        if (s.accountRestricted) {
            readForwardByAccount(s);
        } else {
            readForwardFullScan(s);
        }

        // Advance the current key to one past the last card so the next PF8
        // forward read starts after this page (CARDREAD GTEQ on next key+1).
        if (s.rowCount > 0 && !s.lastPageShown) {
            s.currentCardKey = nextKey(s.lastCardOnPage);
        }

        // Update page indicators
        if (s.currentPageNo > 1) {
            s.firstPage = false;
        }
        s.pageNoStr = String.format("%03d", s.currentPageNo);

        log.debug("readForward complete: rowCount={} pageNo={} firstCard={} lastCard={} lastPage={}",
                s.rowCount, s.pageNoStr, maskPan(s.firstCardOnPage),
                maskPan(s.lastCardOnPage), s.lastPageShown);
    }

    /**
     * Forward read against the full {@code CARDDAT} dataset via
     * {@link CardRepository#streamSequential()} or
     * {@link CardRepository#streamFrom(String)}. Used for admin users who
     * may list all cards in the system.
     *
     * @param s the mutable state for this invocation
     */
    private void readForwardFullScan(MutableState s) {
        final boolean fromStart = s.currentCardKey == null || s.currentCardKey.isBlank();
        try (Stream<CardRecord> stream = fromStart
                ? cardRepository.streamSequential()
                : cardRepository.streamFrom(s.currentCardKey)) {
            final Iterator<CardRecord> it = stream.iterator();
            boolean firstCardCaptured = false;
            while (it.hasNext() && s.rowCount < MAX_SCREEN_LINES) {
                final CardRecord card = it.next();
                if (!filterRecord(card, s)) {
                    continue;
                }
                final int idx = s.rowCount;
                s.rowCardNums[idx] = card.cardNum();
                s.rowAcctIds[idx]  = String.format("%011d", card.cardAcctId());
                s.rowStatuses[idx] = String.valueOf(card.cardActiveStatus());
                s.rowSelects[idx]  = "";
                s.rowCount++;
                if (!firstCardCaptured) {
                    s.firstCardOnPage = card.cardNum();
                    firstCardCaptured = true;
                }
                s.lastCardOnPage = card.cardNum();
            }
            s.lastPageShown = !it.hasNext() || s.rowCount < MAX_SCREEN_LINES;
        }
    }

    /**
     * Forward read against the {@code CARDAIX} alternate-index path via
     * {@link CardXrefRepository#streamByAccountId(long)}. Used for non-admin
     * users restricted to a single account. For each cross-reference entry,
     * the corresponding card-master record is looked up via
     * {@link CardRepository#findByCardNumber(String)}; cards missing from
     * the master file are skipped (orphan cross-reference entries are not
     * presented). Filters are applied against the master record so the
     * filter semantics are identical to the full-scan path.
     *
     * <p>Per COBOL 9000-READ-FORWARD lines 1123-1261 (CARDAIX path).
     *
     * @param s the mutable state for this invocation
     */
    private void readForwardByAccount(MutableState s) {
        log.debug("readForwardByAccount: restrictedAcctId={}", s.restrictedAcctId);
        try (Stream<CardXrefRecord> xrefStream =
                     cardXrefRepository.streamByAccountId(s.restrictedAcctId)) {
            final Iterator<CardXrefRecord> it = xrefStream.iterator();

            // Skip cross-reference entries whose card-number is less than
            // currentCardKey so paging via PF8 works correctly. The xref
            // stream is naturally ordered by xrefCardNum within an account.
            final String boundaryKey = s.currentCardKey == null ? "" : s.currentCardKey;

            boolean firstCardCaptured = false;
            while (it.hasNext() && s.rowCount < MAX_SCREEN_LINES) {
                final CardXrefRecord xref = it.next();
                // Skip xref entries before the current key.
                if (!boundaryKey.isEmpty() && xref.xrefCardNum().compareTo(boundaryKey) < 0) {
                    continue;
                }
                // Defensive check: xref records should share acctId. If they
                // don't (data inconsistency), skip them rather than mis-display.
                if (xref.xrefAcctId() != s.restrictedAcctId) {
                    log.warn("Cross-reference entry for card {} has unexpected acctId={} (expected {}) custId={}",
                            maskPan(xref.xrefCardNum()), xref.xrefAcctId(),
                            s.restrictedAcctId, xref.xrefCustId());
                    continue;
                }
                final var maybeCard = cardRepository.findByCardNumber(xref.xrefCardNum());
                if (maybeCard.isEmpty()) {
                    // Orphan xref entry: skip silently per COBOL FILE STATUS
                    // 23 (NOTFND) handling on the master lookup.
                    log.debug("Card master missing for xref card={}", maskPan(xref.xrefCardNum()));
                    continue;
                }
                final CardRecord card = maybeCard.get();
                if (!filterRecord(card, s)) {
                    continue;
                }
                final int idx = s.rowCount;
                s.rowCardNums[idx] = card.cardNum();
                s.rowAcctIds[idx]  = String.format("%011d", card.cardAcctId());
                s.rowStatuses[idx] = String.valueOf(card.cardActiveStatus());
                s.rowSelects[idx]  = "";
                s.rowCount++;
                if (!firstCardCaptured) {
                    s.firstCardOnPage = card.cardNum();
                    firstCardCaptured = true;
                }
                s.lastCardOnPage = card.cardNum();
            }
            s.lastPageShown = !it.hasNext() || s.rowCount < MAX_SCREEN_LINES;
        }
    }

    /**
     * Translation of COBOL paragraph {@code 9100-READ-BACKWARDS} (lines 1264-1374).
     *
     * <p>Conceptually the inverse of {@link #readForward(MutableState)}: read
     * {@link #MAX_SCREEN_LINES} cards preceding the current page's first card.
     * Implementation approach: because {@link CardRepository} exposes only a
     * forward stream, we scan from the start of the file and maintain a
     * sliding window of the most recently seen rows that match the filter,
     * stopping when we reach the previous-page boundary.
     *
     * <p>This preserves observable behavior (the user sees the prior page of
     * filtered cards) while honoring the port contract. The COBOL
     * {@code EXEC CICS READPREV} loop is bounded by the same filter logic.
     *
     * @param s the mutable state for this invocation
     */
    private void readBackwards(MutableState s) {
        // Capture the boundary: cards strictly less than firstCardOnPage are
        // candidates for the previous page.
        final String boundary = s.firstCardOnPage;
        if (boundary == null || boundary.isBlank()) {
            // Should not happen if firstPage was false, but guard anyway.
            clearRows(s);
            s.firstPage = true;
            s.lastPageShown = true;
            return;
        }

        // Collect filtered cards strictly less than boundary, retaining the
        // last MAX_SCREEN_LINES seen.
        final String[] backCardNums = new String[MAX_SCREEN_LINES];
        final String[] backAcctIds  = new String[MAX_SCREEN_LINES];
        final String[] backStatuses = new String[MAX_SCREEN_LINES];
        int backCount = 0;
        int writeIdx = 0;
        boolean reachedBoundary = false;

        try (Stream<CardRecord> stream = cardRepository.streamSequential()) {
            final Iterator<CardRecord> it = stream.iterator();
            while (it.hasNext() && !reachedBoundary) {
                final CardRecord card = it.next();
                if (card.cardNum().compareTo(boundary) >= 0) {
                    reachedBoundary = true;
                    break;
                }
                if (!filterRecord(card, s)) {
                    continue;
                }
                // Ring buffer: overwrite oldest entry, retain newest 7.
                backCardNums[writeIdx] = card.cardNum();
                backAcctIds[writeIdx]  = String.format("%011d", card.cardAcctId());
                backStatuses[writeIdx] = String.valueOf(card.cardActiveStatus());
                writeIdx = (writeIdx + 1) % MAX_SCREEN_LINES;
                if (backCount < MAX_SCREEN_LINES) {
                    backCount++;
                }
            }
        }

        // Materialize the ring buffer into the row arrays in chronological order.
        clearRows(s);
        if (backCount == 0) {
            s.firstPage = true;
            s.infoMsg = MSG_NO_PREV_PAGES;
            // Re-read the current first page so the user has something to see.
            s.currentCardKey = "";
            s.currentPageNo = 1;
            s.lastPageShown = false;
            // Avoid infinite recursion: only readForward once.
            readForward(s);
            return;
        }
        // Start of the ring buffer = (writeIdx - backCount + MAX) % MAX.
        int startIdx = ((writeIdx - backCount) % MAX_SCREEN_LINES + MAX_SCREEN_LINES)
                       % MAX_SCREEN_LINES;
        for (int i = 0; i < backCount; i++) {
            final int srcIdx = (startIdx + i) % MAX_SCREEN_LINES;
            s.rowCardNums[i] = backCardNums[srcIdx];
            s.rowAcctIds[i]  = backAcctIds[srcIdx];
            s.rowStatuses[i] = backStatuses[srcIdx];
            s.rowSelects[i]  = "";
        }
        s.rowCount = backCount;
        s.firstCardOnPage = s.rowCardNums[0];
        s.lastCardOnPage  = s.rowCardNums[backCount - 1];
        s.currentCardKey  = s.firstCardOnPage;

        // Decrement page indicators
        if (s.currentPageNo > 1) {
            s.currentPageNo--;
        }
        s.firstPage = (s.currentPageNo == 1);
        s.lastPageShown = false;
        s.pageNoStr = String.format("%03d", s.currentPageNo);

        log.debug("readBackwards complete: rowCount={} pageNo={} firstCard={} lastCard={}",
                s.rowCount, s.pageNoStr, maskPan(s.firstCardOnPage),
                maskPan(s.lastCardOnPage));
    }

    /**
     * Translation of COBOL paragraph {@code 9500-FILTER-RECORDS} (lines 1382-1409).
     *
     * <p>Applies the validated account-id and card-number filters to a single
     * card record. Returns {@code true} if the card passes (or no filters are
     * active); {@code false} if the card should be skipped.
     *
     * <p>COBOL strict-numeric matching is preserved: an active filter is
     * compared digit-for-digit (no case folding, no whitespace tolerance
     * beyond trimming). A non-active filter (flag {@code BLANK}) matches
     * everything; a {@code NOT_OK} flag is unreachable here because
     * {@code inputError} short-circuits processing before reaching this
     * method.
     *
     * @param card the card record under consideration
     * @param s    the mutable state for this invocation
     * @return {@code true} if the card passes both filters
     */
    private boolean filterRecord(CardRecord card, MutableState s) {
        // Account-id filter
        if (s.acctFilterFlag == MutableState.FilterFlag.IS_VALID) {
            final String trimmed = s.acctSidFilter.trim();
            final long filterAcct;
            try {
                filterAcct = Long.parseLong(trimmed);
            } catch (NumberFormatException nfe) {
                // Should never happen: editAccount already verified digit-only.
                log.warn("Unexpected non-numeric acctSidFilter='{}' — skipping filter", trimmed);
                return true;
            }
            if (card.cardAcctId() != filterAcct) {
                return false;
            }
        }
        // Card-number filter — exact string match, digit-for-digit.
        if (s.cardFilterFlag == MutableState.FilterFlag.IS_VALID) {
            final String filterCard = s.cardSidFilter.trim();
            if (!card.cardNum().trim().equals(filterCard)) {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Commarea construction helpers (XCTL paths)
    // ========================================================================

    /**
     * Build the commarea passed to {@code COMEN01C} on PF03 exit.
     *
     * <p>Preserves the user identity and user type from the incoming commarea;
     * sets the from-tran-id to {@code CCLI} and from-program to
     * {@code COCRDLIC}; resets the program context to {@link PgmContext#ENTER}.
     * Mirrors COBOL 0000-MAIN lines 384-396 (the PFK03 EVALUATE branch).
     *
     * @param commarea the incoming commarea
     * @return the outgoing commarea to attach to {@link Outcome.Xctl}
     */
    private CardDemoCommarea buildExitCommarea(CardDemoCommarea commarea) {
        final CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();
        final CardDemoCommarea.CdemoGeneralInfo updatedGi = new CardDemoCommarea.CdemoGeneralInfo(
                padOrTruncate(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padOrTruncate(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padOrTruncate(LIT_MENU_TRAN_ID, CardDemoCommarea.LENGTH_TO_TRANID),
                padOrTruncate(LIT_MENU_PGM, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea result = commarea.withCdemoGeneralInfo(updatedGi);
        // Record the originating mapset/map per COBOL "MOVE LIT-THISMAPSET TO
        // CDEMO-LAST-MAPSET".
        final CardDemoCommarea.CdemoMoreInfo mi = result.cdemoMoreInfo();
        final CardDemoCommarea.CdemoMoreInfo updatedMi = new CardDemoCommarea.CdemoMoreInfo(
                padOrTruncate(LIT_THIS_MAP, mi.lastMap().length()),
                padOrTruncate(LIT_THIS_MAPSET, mi.lastMapset().length()));
        result = result.withCdemoMoreInfo(updatedMi);
        return result;
    }

    /**
     * Build the commarea passed to {@code COCRDSLC} (view) or
     * {@code COCRDUPC} (update) when a row selection is dispatched.
     *
     * <p>Populates the from-tran-id / from-program with this transaction's
     * identity, the account-info with the selected row's account-id, and the
     * card-info with the selected row's card-number. Resets the program
     * context to {@link PgmContext#ENTER} so the target program treats this
     * as a fresh entry.
     *
     * @param commarea         the incoming commarea
     * @param selectedAcctId   the 11-char account-id from the selected row
     *                         (may be null or shorter; will be parsed/padded)
     * @param selectedCardNum  the 16-char card-number from the selected row
     * @param targetProgram    the destination program-id ({@link #LIT_CARDDTL_PGM}
     *                         for view or {@link #LIT_CARDUPD_PGM} for update);
     *                         must be one of the two card-dispatch program-ids
     * @return the outgoing commarea to attach to {@link Outcome.Xctl}
     */
    private CardDemoCommarea buildSelectionCommarea(CardDemoCommarea commarea,
                                                    String selectedAcctId,
                                                    String selectedCardNum,
                                                    String targetProgram) {
        final CardDemoCommarea.CdemoGeneralInfo gi = commarea.cdemoGeneralInfo();

        // Update general info: from-tran-id / from-program identify this
        // program; to-tran-id / to-program identify the target.
        final String toTranId;
        final String toPgm;
        if (LIT_CARDDTL_PGM.equals(targetProgram)) {
            toTranId = LIT_CARDDTL_TRAN_ID;
            toPgm = LIT_CARDDTL_PGM;
        } else {
            // Default to card-update path for any non-view target.
            toTranId = LIT_CARDUPD_TRAN_ID;
            toPgm = LIT_CARDUPD_PGM;
        }

        final CardDemoCommarea.CdemoGeneralInfo updatedGi = new CardDemoCommarea.CdemoGeneralInfo(
                padOrTruncate(LIT_THIS_TRAN_ID, CardDemoCommarea.LENGTH_FROM_TRANID),
                padOrTruncate(LIT_THIS_PGM, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                padOrTruncate(toTranId, CardDemoCommarea.LENGTH_TO_TRANID),
                padOrTruncate(toPgm, CardDemoCommarea.LENGTH_TO_PROGRAM),
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        CardDemoCommarea result = commarea.withCdemoGeneralInfo(updatedGi);

        // Update account info: parse acctId from the selected row.
        long acctIdLong = 0L;
        final String trimmedAcct = selectedAcctId == null ? "" : selectedAcctId.trim();
        if (!trimmedAcct.isEmpty()) {
            try {
                acctIdLong = Long.parseLong(trimmedAcct);
            } catch (NumberFormatException nfe) {
                log.warn("buildSelectionCommarea: unparseable selectedAcctId='{}' — using 0",
                        trimmedAcct);
            }
        }
        final CardDemoCommarea.CdemoAccountInfo updatedAi = new CardDemoCommarea.CdemoAccountInfo(
                acctIdLong, " ");
        result = result.withCdemoAccountInfo(updatedAi);

        // Update card info: must be exactly 16 ASCII digits per CdemoCardInfo
        // contract. Pad / sanitize the user-supplied or echoed value.
        final String paddedCardNum = sanitizeCardNumForCommarea(selectedCardNum);
        final CardDemoCommarea.CdemoCardInfo updatedCi = new CardDemoCommarea.CdemoCardInfo(
                paddedCardNum);
        result = result.withCdemoCardInfo(updatedCi);

        // Record the originating mapset/map per COBOL "MOVE LIT-THISMAPSET TO
        // CDEMO-LAST-MAPSET".
        final CardDemoCommarea.CdemoMoreInfo mi = result.cdemoMoreInfo();
        final CardDemoCommarea.CdemoMoreInfo updatedMi = new CardDemoCommarea.CdemoMoreInfo(
                padOrTruncate(LIT_THIS_MAP, mi.lastMap().length()),
                padOrTruncate(LIT_THIS_MAPSET, mi.lastMapset().length()));
        result = result.withCdemoMoreInfo(updatedMi);

        return result;
    }

    /**
     * Sanitize a card-number string for storage in {@code CdemoCardInfo},
     * which requires exactly 16 ASCII digits. If the input is missing or
     * malformed, returns 16 zeros (the same default used by
     * {@link CardDemoCommarea#empty()}).
     *
     * @param raw the candidate card number (may be {@code null}, blank, or
     *            shorter than 16 chars)
     * @return a 16-digit ASCII string suitable for
     *         {@link CardDemoCommarea.CdemoCardInfo}
     */
    private static String sanitizeCardNumForCommarea(String raw) {
        if (raw == null) {
            return "0".repeat(16);
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "0".repeat(16);
        }
        // If it's already 16 digits, use it verbatim.
        if (trimmed.length() == 16 && trimmed.matches("\\d{16}")) {
            return trimmed;
        }
        // If shorter and all digits, left-pad with zeros.
        if (trimmed.length() < 16 && trimmed.matches("\\d+")) {
            return String.format("%016d", Long.parseLong(trimmed));
        }
        // If 16 chars but not all digits, replace non-digits with '0'.
        final StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < Math.min(16, trimmed.length()); i++) {
            final char c = trimmed.charAt(i);
            sb.append((c >= '0' && c <= '9') ? c : '0');
        }
        while (sb.length() < 16) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    // ========================================================================
    // Static helpers
    // ========================================================================

    /**
     * Mask a card number for safe logging. The first {@code length - 4}
     * characters are replaced by {@code '*'} and the last 4 are visible.
     * Inputs shorter than 4 chars are fully masked.
     *
     * <p>Per AAP &sect;0.7.2: <em>"No card PAN logged in full; mask all but
     * last 4 digits in logs and error messages"</em>.
     *
     * @param pan the candidate PAN (may be {@code null}, blank, or shorter
     *            than 16 chars)
     * @return a non-null masked representation
     */
    static String maskPan(String pan) {
        if (pan == null) {
            return "****";
        }
        final String trimmed = pan.trim();
        if (trimmed.length() < 4) {
            return "****";
        }
        final int keep = 4;
        final int maskLen = trimmed.length() - keep;
        return "*".repeat(maskLen) + trimmed.substring(maskLen);
    }

    /**
     * Right-pad with spaces or truncate {@code s} so the result is exactly
     * {@code width} characters wide. Treats {@code null} as the empty string.
     *
     * @param s     the source string (may be {@code null})
     * @param width the target width
     * @return a non-null string of length exactly {@code width}
     */
    private static String padOrTruncate(String s, int width) {
        if (width <= 0) {
            return "";
        }
        final String src = orEmpty(s);
        if (src.length() == width) {
            return src;
        }
        if (src.length() > width) {
            return src.substring(0, width);
        }
        final StringBuilder sb = new StringBuilder(width);
        sb.append(src);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Pad with spaces (only) to reach {@code width}; truncate if longer.
     * Used for the {@code infoMsg} / {@code errorMsg} BMS fields which the
     * {@link CoCrdLiOutput} compact constructor will pad on its own anyway,
     * but we pad here so the output is observable at this site.
     *
     * @param s     the source string (may be {@code null})
     * @param width the target width
     * @return a non-null string of length up to {@code width}
     */
    private static String padToLength(String s, int width) {
        final String src = orEmpty(s);
        if (src.length() >= width) {
            return src.substring(0, width);
        }
        return src;
    }

    /**
     * Trim a candidate input to at most {@code width} characters. Used when
     * accepting BMS input strings whose declared width is enforced upstream
     * but we want a safe local cap.
     *
     * @param s     the candidate input string (may be {@code null})
     * @param width the maximum width
     * @return a non-null trimmed string
     */
    private static String trimToWidth(String s, int width) {
        final String src = orEmpty(s);
        return src.length() <= width ? src : src.substring(0, width);
    }

    /**
     * Returns {@code s} if non-null, else the empty string.
     *
     * @param s the candidate string (may be {@code null})
     * @return a non-null string
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns {@code s} with trailing whitespace removed; null-safe.
     *
     * @param s the candidate string
     * @return a non-null trimmed string
     */
    private static String stripTrailing(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Compute the lexicographically smallest 16-character ASCII string
     * strictly greater than {@code key}. Used to advance the start-key after
     * a forward read so the next page begins after the last card shown.
     *
     * <p>Implementation strategy: append a trailing high-byte ({@code 0xFF})
     * sentinel would work for binary keys, but card numbers are ASCII digits;
     * a trailing space character ({@code 0x20}) is lexicographically just
     * above digits 0-9, so {@code key + ' '} suffices to seek past the key.
     *
     * <p>If the key is null or blank, returns an empty string (seek from
     * start of file).
     *
     * @param key the current key
     * @return a non-null successor key
     */
    private static String nextKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        // For an ASCII digit key, the "next" key in lexicographic order is
        // obtained by appending the smallest character greater than '9' (i.e.,
        // ':' = 0x3A). This guarantees the GTEQ read starts at the next
        // distinct card-number entry.
        return key + ':';
    }

    /**
     * Reset the row-buffer arrays to all-empty for a fresh page read.
     *
     * @param s the mutable state for this invocation
     */
    private static void clearRows(MutableState s) {
        Arrays.fill(s.rowCardNums, "");
        Arrays.fill(s.rowAcctIds, "");
        Arrays.fill(s.rowStatuses, "");
        Arrays.fill(s.rowSelects, "");
        s.rowCount = 0;
    }

    // ========================================================================
    // MutableState — per-invocation working storage
    // ========================================================================

    /**
     * Per-invocation working storage. Replaces COBOL {@code WORKING-STORAGE
     * SECTION} variables. Instances are local to a single call to
     * {@link #execute(CardDemoCommarea, AidKey, CoCrdLiInput)} and are never
     * shared across threads or invocations. This class is intentionally
     * {@code private static final} so its mutable state cannot leak.
     *
     * <p>Per AAP &sect;0.6.6 we do NOT use {@link ThreadLocal} for any of these
     * fields — context propagation, where needed, uses {@code ScopedValue}.
     */
    private static final class MutableState {

        /**
         * Tri-state filter-validation flag corresponding to the COBOL 88-level
         * conditions on {@code FLG-ACCTFILTER-*} and {@code FLG-CARDFILTER-*}
         * (lines 244-249 of {@code app/cbl/COCRDLIC.cbl}).
         */
        enum FilterFlag {
            /** Validation failed (NOT-OK == 'N'). */
            NOT_OK,
            /** Validation passed (IS-VALID == 'Y'). */
            IS_VALID,
            /** No filter supplied (BLANK == ' '). */
            BLANK
        }

        // ---- Filter validation flags ----
        /** Flag for the account-id filter. */
        FilterFlag acctFilterFlag = FilterFlag.BLANK;
        /** Flag for the card-number filter. */
        FilterFlag cardFilterFlag = FilterFlag.BLANK;

        // ---- Current paging keys (WS-CARD-RID-CARDNUM, WS-CARD-RID-ACCT-ID) ----
        /** Card-number used to position the next STARTBR/READNEXT call. */
        String currentCardKey = "";
        /** Account-id used when navigating via the alternate index CARDAIX. */
        long currentAcctKey = 0L;

        // ---- Page state ----
        /** {@code true} on the first page (CA-FIRST-PAGE). */
        boolean firstPage = true;
        /** {@code true} after the last forward page has been shown
         *  (CA-LAST-PAGE-SHOWN). */
        boolean lastPageShown = false;
        /** Current 1-based page number. */
        int currentPageNo = 1;
        /** Page number formatted as a 3-digit ASCII string for display. */
        String pageNoStr = "001";

        // ---- Row buffers — replaces CDEMO-CARD-ARRAY OCCURS 7 ----
        /** Card numbers for each of the (up to 7) visible rows. */
        final String[] rowCardNums = new String[MAX_SCREEN_LINES];
        /** Account-ids for each of the (up to 7) visible rows (11-char zero-padded). */
        final String[] rowAcctIds  = new String[MAX_SCREEN_LINES];
        /** Status flags for each of the (up to 7) visible rows. */
        final String[] rowStatuses = new String[MAX_SCREEN_LINES];
        /** Selection chars for each of the (up to 7) visible rows. */
        final String[] rowSelects  = new String[MAX_SCREEN_LINES];
        /** Number of rows actually populated (0..MAX_SCREEN_LINES). */
        int rowCount = 0;

        // ---- Page boundary cards (WS-CA-FIRST-CARD-NUM, WS-CA-LAST-CARD-NUM) ----
        /** First card displayed on this page. */
        String firstCardOnPage = "";
        /** Last card displayed on this page. */
        String lastCardOnPage = "";

        // ---- Filters (echoed back on the screen) ----
        /** Account-id filter text as entered by the user. */
        String acctSidFilter = "";
        /** Card-number filter text as entered by the user. */
        String cardSidFilter = "";

        // ---- Error/info messages (rows 23-24 on the BMS map) ----
        /** Error message (red), displayed on row 24. */
        String errorMsg = "";
        /** Info message (white), displayed on row 23. */
        String infoMsg = "";
        /** {@code true} if input validation failed for any reason. */
        boolean inputError = false;
        /** {@code true} after the user has explicitly exited (PFK03). */
        boolean exitMessage = false;

        // ---- Selection routing — post-2250-EDIT-ARRAY ----
        /** Selected action: "S", "U", or empty. */
        String selectedAction = "";
        /** Card-number from the selected row. */
        String selectedCardNum = "";
        /** Account-id from the selected row. */
        String selectedAcctId = "";

        // ---- Header date/time (built at mainEntry start) ----
        /** Current date formatted MM/DD/YY for header. */
        String curDate = "";
        /** Current time formatted HH:MM:SS for header. */
        String curTime = "";

        // ---- Account-restriction mode ----
        /** {@code true} when the user is non-admin and there is an account
         *  context in commarea; iteration uses {@code CARDAIX} alternate
         *  index in this case. */
        boolean accountRestricted = false;
        /** Account-id to restrict the listing to when {@code accountRestricted}
         *  is {@code true}; otherwise 0. */
        long restrictedAcctId = 0L;

        /**
         * Initializes the row buffers to non-null empty strings to match the
         * COBOL SPACES default for {@code PIC X(n)} fields. Required because
         * {@link CoCrdLiOutput}'s compact constructor enforces non-null
         * components.
         */
        MutableState() {
            Arrays.fill(rowCardNums, "");
            Arrays.fill(rowAcctIds, "");
            Arrays.fill(rowStatuses, "");
            Arrays.fill(rowSelects, "");
        }
    }

    // ========================================================================
    // Suppress unused-warning artifacts: these constants are part of the
    // controller's documented identity per AAP §0.4.1 and are retained on the
    // class even when not currently referenced by any method body, mirroring
    // the COBOL WS-CONSTANTS layout. To prevent strict-mode "unused field"
    // warnings on the more obscure literals, a small touchpoint method
    // ensures they remain reachable for tooling that performs unused-symbol
    // analysis. This method is package-private to allow unit-test access.
    // ========================================================================

    /**
     * Returns the literal program-identity, mapset, and file-name constants
     * declared on this class as a single map-like array for use by unit tests
     * and tooling. Calling this method is purely informational; it has no
     * side effects and does not interact with the repositories or commarea.
     *
     * @return a non-null array of the literal constants on this class, in
     *         a deterministic order suitable for golden-record verification
     */
    static String[] declaredLiterals() {
        return new String[] {
                LIT_THIS_PGM,        LIT_THIS_TRAN_ID,    LIT_THIS_MAPSET,     LIT_THIS_MAP,
                LIT_MENU_PGM,        LIT_MENU_TRAN_ID,    LIT_MENU_MAPSET,     LIT_MENU_MAP,
                LIT_CARDDTL_PGM,     LIT_CARDDTL_TRAN_ID, LIT_CARDDTL_MAPSET,  LIT_CARDDTL_MAP,
                LIT_CARDUPD_PGM,     LIT_CARDUPD_TRAN_ID, LIT_CARDUPD_MAPSET,  LIT_CARDUPD_MAP,
                LIT_CARD_FILE,       LIT_CARD_FILE_ACCT_PATH
        };
    }
}
