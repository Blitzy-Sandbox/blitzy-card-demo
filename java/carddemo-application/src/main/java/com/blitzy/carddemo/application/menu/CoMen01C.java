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
package com.blitzy.carddemo.application.menu;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.PfKeyDecoder;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea.CdemoGeneralInfo;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.commarea.UserType;
import com.blitzy.carddemo.domain.menu.MainMenuTable;
import com.blitzy.carddemo.domain.menu.MainMenuTable.MainMenuEntry;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;
import com.blitzy.carddemo.domain.validation.DateConstants;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Main menu online program for regular (non-admin) users. Java translation of
 * the COBOL CICS program {@code COMEN01C} ({@code PROGRAM-ID COMEN01C},
 * transaction ID {@code CM00}) defined in {@code app/cbl/COMEN01C.cbl}
 * (283 lines, 5 paragraphs).
 *
 * <h2>Translated Paragraphs</h2>
 * <ul>
 *   <li>{@code 0000-MAIN-PARA} (lines 75-110) &rarr; {@link #process}: the
 *       public entry point. Translates the {@code EIBCALEN = 0} initial-entry
 *       check, the {@code CDEMO-PGM-CONTEXT} first-reentry flip, and the
 *       {@code EVALUATE EIBAID} dispatcher.</li>
 *   <li>{@code PROCESS-ENTER-KEY} (lines 115-165) &rarr;
 *       {@link #processEnterKey}: option parsing with right-justify and
 *       leading-space-to-zero conversion, range/numeric validation, regular-
 *       user admin-only access control, and XCTL dispatch (or "coming soon"
 *       fallback for {@code DUMMY}-prefixed program names).</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} (lines 170-177) &rarr;
 *       {@link #returnToSignonScreen}: defaults {@code CDEMO-TO-PROGRAM} to
 *       {@code "COSGN00C"} if blank, then XCTLs to it.</li>
 *   <li>{@code SEND-MENU-SCREEN} (lines 182-194) &rarr;
 *       {@link #sendMenuScreen}: combines header, menu options, and error
 *       message into a populated {@link CoMen01Output}.</li>
 *   <li>{@code POPULATE-HEADER-INFO} (lines 212-231) &rarr;
 *       {@link #populateHeaderInfo}: populates the title/transaction/program/
 *       date/time header fields.</li>
 *   <li>{@code BUILD-MENU-OPTIONS} (lines 236-277) &rarr;
 *       {@link #buildMenuOptions}: places {@code "NN. <name>"} formatted text
 *       into the {@code OPTN001O..OPTN012O} output slots from the
 *       {@link MainMenuTable#ENTRIES} table.</li>
 * </ul>
 *
 * <h2>Translation Strategy</h2>
 * <ul>
 *   <li>{@code EXEC CICS RECEIVE MAP('COMEN1A') INTO(COMEN1AI)} &rarr; the
 *       {@link CoMen01Input} method parameter (per AAP &sect;0.4.2).</li>
 *   <li>{@code EXEC CICS SEND MAP('COMEN1A') FROM(COMEN1AO) ERASE} &rarr; the
 *       {@link CoMen01Output} field of {@link Outcome.Render} (per AAP
 *       &sect;0.4.2).</li>
 *   <li>{@code EXEC CICS XCTL PROGRAM(name) COMMAREA(...)} &rarr;
 *       {@code programRegistry.invoke(name, commarea)} returning a
 *       {@link Outcome.Dispatched} (per AAP &sect;0.4.2).</li>
 *   <li>{@code DFHCOMMAREA} &rarr; the {@link CardDemoCommarea} method
 *       parameter. {@code EIBCALEN = 0} maps to {@code commarea == null} or
 *       {@code eibcalen == 0}.</li>
 *   <li>{@code EVALUATE EIBAID} &rarr; pattern-matching {@code switch} over
 *       the sealed {@link AidKey} hierarchy. All 16 permits are listed; the
 *       compiler enforces exhaustiveness with NO {@code default} branch
 *       (AAP &sect;0.7.3).</li>
 *   <li>88-level {@code CDEMO-USRTYP-USER} and table {@code USRTYPE='A'} &rarr;
 *       pattern-matching {@code instanceof} against the sealed
 *       {@link UserType} hierarchy.</li>
 * </ul>
 *
 * <h2>Anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>DUMMY-prefix dead code:</b> the COBOL contains an
 *       {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} guard
 *       that, when true, performs the {@code XCTL} and never returns; the
 *       fall-through "coming soon" message is therefore only reachable when
 *       the target program name starts with {@code "DUMMY"}. No entry in the
 *       current {@code COMEN02Y.cpy} uses {@code "DUMMY"} as a program-name
 *       prefix, so this branch is currently dead code. Translated faithfully
 *       per AAP &sect;0.7.1.</li>
 *   <li><b>Admin-only block dead code:</b> all 10 entries in
 *       {@link MainMenuTable#ENTRIES} carry {@link UserType#USER}; no entry is
 *       admin-only. The user-type access block is therefore currently dead
 *       code but is preserved per AAP &sect;0.7.1.</li>
 *   <li><b>{@code STRING ... DELIMITED BY SPACE} on option name:</b> the
 *       COBOL emits only the first whitespace-delimited token of the menu
 *       name in the "coming soon" message (e.g., "Account View" becomes
 *       "Account"). Preserved per AAP &sect;0.7.1.</li>
 *   <li><b>"No access - Admin Only option... " trailing-space:</b> the COBOL
 *       message has a single trailing space; preserved in
 *       {@link #NO_ACCESS_ADMIN_MSG}.</li>
 * </ul>
 *
 * <h2>Thread safety and state</h2>
 * Collaborators ({@link ProgramRegistry} and {@link Clock}) are stored as
 * {@code final} fields injected via the constructor and remain stable across
 * invocations. Per-call state (commarea, input, output, parsed option,
 * error message) is local to {@link #process} and its helpers; no mutable
 * field state is held by this class, so an instance is safe to share across
 * threads provided the injected collaborators are themselves thread-safe.
 *
 * <h2>Forbidden features (AAP &sect;0.7.4)</h2>
 * No Spring annotations, no Lombok, no {@code ThreadLocal}, no
 * {@code --enable-preview}, no {@code double}/{@code float}, no
 * {@code java.util.Date}/{@code Calendar}, no {@code java.io.File}, no
 * reflection, no mutable static state.
 *
 * @see app/cbl/COMEN01C.cbl
 * @see CoMen01Input
 * @see CoMen01Output
 * @see ProgramRegistry
 * @see MainMenuTable
 * @see AidKey
 * @since 1.0.0
 */
@CobolProgram(
        value = "COMEN01C",
        sourcePath = "app/cbl/COMEN01C.cbl",
        translationDate = "2025-10-15",
        notes = "Main menu online program for regular (non-admin) users; "
              + "transaction ID CM00. Translates 5 paragraphs: 0000-MAIN-PARA, "
              + "PROCESS-ENTER-KEY, RETURN-TO-SIGNON-SCREEN, SEND-MENU-SCREEN "
              + "(with POPULATE-HEADER-INFO and BUILD-MENU-OPTIONS sub-paragraphs). "
              + "DUMMY-prefix and admin-only branches are dead code today "
              + "but preserved per AAP §0.7.1."
)
public final class CoMen01C {

    // ====================================================================
    // Public constants — COBOL program identity (members_exposed per schema)
    // ====================================================================

    /**
     * COBOL {@code WS-PGMNAME VALUE 'COMEN01C'}; the 8-character {@code PIC
     * X(08)} program-id stored in the commarea's
     * {@link CdemoGeneralInfo#fromProgram() fromProgram} field when this
     * program XCTLs to a downstream program.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * COBOL {@code WS-TRANID VALUE 'CM00'}; the 4-character {@code PIC X(04)}
     * transaction id used both for the trailing {@code EXEC CICS RETURN
     * TRANSID(...)} clause and for the commarea's
     * {@link CdemoGeneralInfo#fromTranId() fromTranId} field on XCTL.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * The 8-character {@code PIC X(08)} signon program-id ({@code "COSGN00C"})
     * used as the default {@code CDEMO-TO-PROGRAM} when the commarea's
     * to-program field is blank (per COBOL {@code RETURN-TO-SIGNON-SCREEN}
     * lines 170-177) and as the value moved to {@code CDEMO-FROM-PROGRAM}
     * on the initial-entry path (per COBOL {@code 0000-MAIN-PARA} line 80).
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Error message displayed when the option entered fails numeric or range
     * validation. Verbatim from COBOL {@code MOVE 'Please enter a valid
     * option number...' TO WS-MESSAGE} (line 132).
     */
    public static final String INVALID_OPTION_MSG = "Please enter a valid option number...";

    /**
     * Error message displayed when a regular ({@link UserType.User}) user
     * selects a menu option marked admin-only ({@link UserType.Admin} in
     * {@link MainMenuTable#ENTRIES}). Verbatim from COBOL {@code MOVE 'No
     * access - Admin Only option... ' TO WS-MESSAGE} (line 138); the single
     * trailing space is part of the message and is preserved per AAP
     * &sect;0.7.1.
     */
    public static final String NO_ACCESS_ADMIN_MSG = "No access - Admin Only option... ";

    // ====================================================================
    // Private constants — translation-internal sentinel values
    // ====================================================================

    /**
     * 5-character prefix used by the COBOL guard {@code IF CDEMO-MENU-OPT-
     * PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} on line 146 to suppress XCTL
     * for placeholder menu entries (which then fall through to the "coming
     * soon" message). No entry in the current {@code COMEN02Y.cpy} uses
     * this prefix; preserved as dead code per AAP &sect;0.7.1.
     */
    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * Leading text of the "coming soon" message. Verbatim from COBOL
     * {@code STRING 'This option ' DELIMITED BY SIZE ...} (line 159).
     */
    private static final String COMING_SOON_PREFIX = "This option ";

    /**
     * Trailing text of the "coming soon" message. Verbatim from COBOL
     * {@code 'is coming soon ...' DELIMITED BY SIZE} (line 161). Note: no
     * leading space &mdash; the COBOL emits the option's first word
     * immediately followed by this suffix.
     */
    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * Sentinel value placed in {@link CoMen01Output#errMsgColor()} for the
     * "coming soon" path, translating the COBOL {@code MOVE DFHGREEN TO
     * ERRMSGC OF COMEN1AO} (line 157). The actual BMS extended-attribute
     * byte for DFHGREEN is X'04', but the in-memory record carries a
     * human-readable mnemonic and the on-wire BMS adapter is responsible
     * for translating to the binary attribute byte. An empty
     * {@link #ERRMSGC_DEFAULT} value (used on every other path) lets the
     * BMS map's static color attribute (typically red for errors) prevail.
     */
    private static final String ERRMSGC_DFHGREEN = "G";

    /**
     * Default (empty) value placed in {@link CoMen01Output#errMsgColor()}
     * when the COBOL did not override {@code ERRMSGC}; the BMS map's static
     * attribute then prevails.
     */
    private static final String ERRMSGC_DEFAULT = "";

    /**
     * Width of the COBOL {@code WS-OPTION-X PIC X(02) JUST RIGHT} field and
     * the BMS {@code OPTION} input field; the operator may enter at most
     * two characters in the option box.
     */
    private static final int OPTION_FIELD_WIDTH = 2;

    /**
     * Width of each COBOL {@code OPTN001O..OPTN012O PIC X(40)} output slot
     * on the {@code COMEN1A} BMS map; option lines are emitted in this
     * exact width to preserve byte-for-byte parity with COBOL output per
     * AAP &sect;0.6.1.
     */
    private static final int OPTION_LINE_WIDTH = 40;

    /**
     * Width of the formatted prefix produced by the COBOL {@code STRING
     * CDEMO-MENU-OPT-NUM '. '} fragment (line 244): 2-digit option number
     * + ". " separator = 4 characters.
     */
    private static final int OPTION_LINE_PREFIX_WIDTH = 4;

    // ====================================================================
    // Outcome sealed interface — terminal result of process()
    // ====================================================================

    /**
     * Sealed result type produced by {@link CoMen01C#process}. Distinguishes
     * between the two terminal COBOL outcomes:
     * <ul>
     *   <li>{@link Render}: the COBOL paragraph performed {@code SEND-MENU-
     *       SCREEN} and the enclosing {@code MAIN-PARA} subsequently
     *       executed {@code EXEC CICS RETURN TRANSID('CM00') COMMAREA(...)}.
     *       The caller is expected to display {@link Render#output()} to the
     *       terminal and re-invoke {@code CoMen01C} with the same commarea
     *       on the next user interaction.</li>
     *   <li>{@link Dispatched}: the COBOL performed {@code EXEC CICS XCTL
     *       PROGRAM(...) COMMAREA(...)}. In CICS, XCTL transfers control to
     *       another program and never returns; in the Java translation the
     *       dispatched program's return value (its own updated commarea) is
     *       wrapped in {@link Dispatched#commarea()} and the caller is
     *       expected to honor that program's outcome rather than re-rendering
     *       the menu.</li>
     * </ul>
     *
     * <p>Pattern-matching {@code switch} over {@code Outcome} MUST be
     * exhaustive with no {@code default} branch (AAP &sect;0.7.3); the
     * Java&nbsp;25 compiler enforces this via the sealed {@code permits}
     * clause.
     */
    public sealed interface Outcome permits Outcome.Render, Outcome.Dispatched {

        /**
         * The commarea associated with this outcome. For {@link Render},
         * this is the commarea that should be threaded into the next
         * invocation of {@code CoMen01C}; for {@link Dispatched}, this is
         * the commarea returned by the program that {@code CoMen01C}
         * XCTL'd to.
         *
         * @return the commarea associated with this outcome; never
         *         {@code null}
         */
        CardDemoCommarea commarea();

        /**
         * Terminal outcome wrapping a {@link CoMen01Output} screen render.
         * Translates the COBOL {@code EXEC CICS SEND MAP('COMEN1A')
         * MAPSET('COMEN01') FROM(COMEN1AO) ERASE} +
         * {@code EXEC CICS RETURN TRANSID('CM00') COMMAREA(...)} sequence
         * (lines 105 and 192). The caller displays {@link #output()} to
         * the terminal and re-invokes {@code CoMen01C} with
         * {@link #commarea()} on the next user interaction.
         *
         * @param commarea the commarea to thread into the next invocation
         *                 (typically updated with {@code CDEMO-PGM-
         *                 REENTER} on the first-display path); non-null
         * @param output   the populated BMS output to display; non-null
         */
        record Render(CardDemoCommarea commarea, CoMen01Output output) implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract
             * on both components.
             *
             * @throws NullPointerException if {@code commarea} or
             *                              {@code output} is {@code null}
             */
            public Render {
                Objects.requireNonNull(commarea, "commarea");
                Objects.requireNonNull(output, "output");
            }
        }

        /**
         * Terminal outcome wrapping a downstream program's commarea after
         * an {@code EXEC CICS XCTL}. Translates the COBOL XCTL on line 153
         * (option dispatch) and lines 175 (signon dispatch in
         * {@code RETURN-TO-SIGNON-SCREEN}).
         *
         * @param commarea the commarea returned by the dispatched program;
         *                 non-null
         */
        record Dispatched(CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact canonical constructor enforcing the non-null contract.
             *
             * @throws NullPointerException if {@code commarea} is {@code null}
             */
            public Dispatched {
                Objects.requireNonNull(commarea, "commarea");
            }
        }
    }

    // ====================================================================
    // Constructor-injected collaborators
    // ====================================================================

    /**
     * Dynamic-XCTL dispatch registry. Used by:
     * <ul>
     *   <li>{@link #processEnterKey} for {@code EXEC CICS XCTL
     *       PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))} (line 153) where the
     *       target is selected from the menu table at run time.</li>
     *   <li>{@link #returnToSignonScreen} for {@code EXEC CICS XCTL
     *       PROGRAM(CDEMO-TO-PROGRAM)} (line 175).</li>
     * </ul>
     */
    private final ProgramRegistry programRegistry;

    /**
     * Clock used to obtain the current date and time for
     * {@link #populateHeaderInfo} (translating the COBOL
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} on line 213).
     * Constructor-injected so tests can substitute
     * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} for
     * deterministic header rendering; production callers typically pass
     * {@link Clock#systemDefaultZone()}.
     */
    private final Clock clock;

    /**
     * Constructs a {@code CoMen01C} with its collaborator dependencies.
     *
     * @param programRegistry the dynamic-XCTL dispatch registry through
     *                        which COBOL {@code EXEC CICS XCTL PROGRAM(name)
     *                        COMMAREA(...)} translates to
     *                        {@code programRegistry.invoke(name, commarea)};
     *                        must be non-null
     * @param clock           the {@link Clock} used by
     *                        {@link #populateHeaderInfo} to format the
     *                        screen's date/time header (translating the
     *                        COBOL {@code FUNCTION CURRENT-DATE}); must be
     *                        non-null
     * @throws NullPointerException if {@code programRegistry} or
     *                              {@code clock} is {@code null}
     */
    public CoMen01C(ProgramRegistry programRegistry, Clock clock) {
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ====================================================================
    // Public entry method — process()
    // ====================================================================

    /**
     * Processes one CICS pseudo-conversational invocation of {@code COMEN01C}.
     * Faithfully translates the COBOL {@code 0000-MAIN-PARA} paragraph
     * (lines 75-110 of {@code app/cbl/COMEN01C.cbl}).
     *
     * <h3>Logic flow</h3>
     * <ol>
     *   <li>If {@code eibcalen == 0} (or {@code commarea == null}): set
     *       {@code CDEMO-FROM-PROGRAM} to {@link #SIGNON_PROGRAM} and dispatch
     *       to the signon program via {@link #returnToSignonScreen}. Returns
     *       an {@link Outcome.Dispatched}.</li>
     *   <li>Else if the commarea's {@code CDEMO-PGM-CONTEXT} is not
     *       {@code REENTER} (first-entry path): flip the context to
     *       {@link PgmContext#REENTER}, build an empty output via
     *       {@link CoMen01Output#empty()}, populate it via
     *       {@link #sendMenuScreen}, and return an {@link Outcome.Render}.</li>
     *   <li>Else (re-entry path): decode the AID byte via
     *       {@link PfKeyDecoder#decode(byte)} and dispatch on the resulting
     *       sealed {@link AidKey}:
     *       <ul>
     *         <li>{@link AidKey.Enter}: invoke {@link #processEnterKey}.</li>
     *         <li>{@link AidKey.PfKey03}: set {@code CDEMO-TO-PROGRAM} to
     *             {@link #SIGNON_PROGRAM} and dispatch via
     *             {@link #returnToSignonScreen}.</li>
     *         <li>Any other {@link AidKey} permit (15 total &mdash; Clear,
     *             Pa1, Pa2, PfKey01, PfKey02, PfKey04..PfKey12): re-render
     *             the menu with {@link SystemMessages#INVALID_KEY_MSG} via
     *             {@link #renderInvalidKey}.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h3>Pattern-matching exhaustiveness</h3>
     * The {@code switch} over {@link AidKey} lists all 16 permits explicitly
     * (no {@code default} branch). The Java&nbsp;25 compiler enforces
     * exhaustiveness; if a new permit is ever added to {@code AidKey}, this
     * method will fail to compile until the new permit is handled
     * (AAP &sect;0.7.3 safety net).
     *
     * @param commareaIn the inbound commarea (the Java analogue of
     *                   {@code DFHCOMMAREA}); may be {@code null} to
     *                   represent {@code EIBCALEN = 0}
     * @param input      the BMS map input record (the Java analogue of
     *                   {@code RECEIVE MAP INTO(COMEN1AI)}); must be
     *                   non-null even on the initial-entry path &mdash;
     *                   callers should pass {@link CoMen01Input#empty()}
     *                   if no operator input is available
     * @param eibaid     the CICS attention-identifier byte received from
     *                   the terminal (the Java analogue of {@code EIBAID})
     * @param eibcalen   the COBOL {@code EIBCALEN} value (length of the
     *                   inbound commarea in bytes); {@code 0} signals no
     *                   commarea was passed (initial entry)
     * @return an {@link Outcome} describing the terminal action: either
     *         {@link Outcome.Render} (the menu was rendered and CICS should
     *         return control to the user) or {@link Outcome.Dispatched}
     *         (an XCTL transferred control to another program); never
     *         {@code null}
     * @throws NullPointerException     if {@code input} is {@code null}
     * @throws IllegalArgumentException if {@code eibaid} is not one of the
     *                                  28 recognized CICS AID bytes
     *                                  (propagated from
     *                                  {@link PfKeyDecoder#decode(byte)})
     */
    public Outcome process(
            CardDemoCommarea commareaIn,
            CoMen01Input input,
            byte eibaid,
            int eibcalen
    ) {
        // ----------------------------------------------------------------
        // Input contract: input is always non-null (callers pass
        // CoMen01Input.empty() on the initial-entry path). The COBOL has no
        // analogous nullable LINKAGE field; the Java contract surfaces this
        // explicitly so callers cannot silently pass a null DTO.
        // ----------------------------------------------------------------
        Objects.requireNonNull(input, "input");

        // ----------------------------------------------------------------
        // COBOL 0000-MAIN-PARA lines 78-82:
        //     IF EIBCALEN = 0
        //         MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
        //         PERFORM RETURN-TO-SIGNON-SCREEN
        //
        // We treat commareaIn == null and eibcalen == 0 identically; both
        // represent the "first ever entry" state where no commarea has
        // been threaded in from a prior CICS dispatch.
        // ----------------------------------------------------------------
        if (eibcalen == 0 || commareaIn == null) {
            CardDemoCommarea seed = (commareaIn == null)
                    ? CardDemoCommarea.empty()
                    : commareaIn;
            // MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
            CardDemoCommarea withFromProgram = seed.withCdemoGeneralInfo(
                    withFromProgram(seed.cdemoGeneralInfo(), SIGNON_PROGRAM));
            // PERFORM RETURN-TO-SIGNON-SCREEN
            return returnToSignonScreen(withFromProgram);
        }

        // ----------------------------------------------------------------
        // COBOL 0000-MAIN-PARA line 83:
        //     MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
        //
        // In the Java translation the commarea has already been parsed into
        // a CardDemoCommarea record before being handed to process(); the
        // MOVE is a no-op.
        // ----------------------------------------------------------------
        CardDemoCommarea commarea = commareaIn;

        // ----------------------------------------------------------------
        // COBOL 0000-MAIN-PARA lines 84-88:
        //     IF NOT CDEMO-PGM-REENTER
        //         SET CDEMO-PGM-REENTER TO TRUE
        //         MOVE LOW-VALUES TO COMEN1AO
        //         PERFORM SEND-MENU-SCREEN
        //
        // First-entry path: flip the program-context flag to REENTER, build
        // the populated output (LOW-VALUES initialization is implicit in the
        // CoMen01Output.empty() factory used inside sendMenuScreen), and
        // return Outcome.Render. The flipped commarea is what the next
        // invocation receives.
        // ----------------------------------------------------------------
        if (!(commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = commarea.withCdemoGeneralInfo(
                    withPgmContext(commarea.cdemoGeneralInfo(), PgmContext.REENTER));
            return new Outcome.Render(
                    reentered,
                    sendMenuScreen("", "", ERRMSGC_DEFAULT));
        }

        // ----------------------------------------------------------------
        // COBOL 0000-MAIN-PARA lines 89-102:
        //     PERFORM RECEIVE-MENU-SCREEN
        //     EVALUATE EIBAID
        //         WHEN DFHENTER    PERFORM PROCESS-ENTER-KEY
        //         WHEN DFHPF3      MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        //                          PERFORM RETURN-TO-SIGNON-SCREEN
        //         WHEN OTHER       MOVE 'Y' TO WS-ERR-FLG
        //                          MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        //                          PERFORM SEND-MENU-SCREEN
        //     END-EVALUATE
        //
        // The RECEIVE-MENU-SCREEN PERFORM is a no-op in Java because the
        // input record was passed in as a parameter. The EVALUATE becomes
        // an exhaustive pattern-matching switch over the sealed AidKey
        // hierarchy (16 permits). Per AAP §0.7.3, there is NO default
        // branch — exhaustiveness checking IS the safety net.
        // ----------------------------------------------------------------
        AidKey aid = PfKeyDecoder.decode(eibaid);
        return switch (aid) {
            // WHEN DFHENTER → PROCESS-ENTER-KEY
            case AidKey.Enter _ -> processEnterKey(commarea, input);

            // WHEN DFHPF3 → MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM;
            //               PERFORM RETURN-TO-SIGNON-SCREEN
            case AidKey.PfKey03 _ -> {
                CardDemoCommarea withToProgram = commarea.withCdemoGeneralInfo(
                        withToProgram(commarea.cdemoGeneralInfo(), SIGNON_PROGRAM));
                yield returnToSignonScreen(withToProgram);
            }

            // WHEN OTHER → MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE;
            //              PERFORM SEND-MENU-SCREEN
            //
            // All 14 non-Enter, non-PF3 permits route to renderInvalidKey.
            // Multi-pattern case labels with unnamed-variable bindings
            // (Java 21+ pattern matching for switch finalized) keep the
            // arm compact while preserving exhaustiveness checking.
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
                 AidKey.PfKey12 _ -> renderInvalidKey(commarea, input);
        };
    }

    // ====================================================================
    // Paragraph: PROCESS-ENTER-KEY (lines 115-165)
    // ====================================================================

    /**
     * Translates the COBOL {@code PROCESS-ENTER-KEY} paragraph (lines
     * 115-165). The body performs three sequential checks and a dispatch:
     * <ol>
     *   <li>Parse the operator's 2-character option entry using COBOL's
     *       {@code JUST RIGHT} + {@code INSPECT REPLACING ALL ' ' BY '0'}
     *       semantics (right-justified with leading zeros). If the parsed
     *       value is not numeric, is zero, or exceeds
     *       {@link MainMenuTable#OPT_COUNT}, render
     *       {@link #INVALID_OPTION_MSG}.</li>
     *   <li>If the calling user is a regular user
     *       ({@link UserType.User}) and the selected menu entry is marked
     *       admin-only ({@link UserType.Admin}), render
     *       {@link #NO_ACCESS_ADMIN_MSG}.</li>
     *   <li>If the selected entry's program name does not start with the
     *       5-character {@link #DUMMY_PROGRAM_PREFIX} guard, update the
     *       commarea ({@code FROM-TRANID = CM00}, {@code FROM-PROGRAM =
     *       COMEN01C}, {@code PGM-CONTEXT = ENTER}) and XCTL via
     *       {@link ProgramRegistry#invoke}. Otherwise (DUMMY-prefix path),
     *       render the "coming soon" message.</li>
     * </ol>
     *
     * <h4>COBOL fall-through divergence (DOCUMENTED DEVIATION)</h4>
     * The COBOL paragraph's three {@code IF} blocks use a shared
     * {@code WS-ERR-FLG} flag and fall through each other; in particular,
     * if the option fails numeric validation, the subsequent user-type
     * check still evaluates the subscript {@code CDEMO-MENU-OPT-
     * USRTYPE(WS-OPTION)} with {@code WS-OPTION = 0}, which is undefined
     * behavior in COBOL. The Java translation returns early on the first
     * error (invalid option / access denied) to avoid an
     * {@link IndexOutOfBoundsException} on a deliberately invalid
     * subscript. The observable outcome is identical: the user sees the
     * INVALID-OPTION message on the screen and the menu remains rendered.
     * The early-return pattern is the idiomatic Java translation of the
     * COBOL "set error flag, send screen, abandon dispatch" intent.
     *
     * @param commarea the current commarea
     * @param input    the BMS input record with the operator's option entry
     * @return either {@link Outcome.Render} with the error message and
     *         echoed option, or {@link Outcome.Dispatched} after a
     *         successful XCTL
     */
    private Outcome processEnterKey(CardDemoCommarea commarea, CoMen01Input input) {
        // ----------------------------------------------------------------
        // COBOL lines 117-123 (right-justify):
        //     PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1 UNTIL
        //         OPTIONI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1
        //     END-PERFORM
        //     MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X
        //     INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
        //
        // The PERFORM finds the last non-space position; MOVE into the
        // PIC X(02) JUST RIGHT field right-justifies the operator's input
        // by prepending spaces; INSPECT then promotes those leading
        // spaces to leading zeros. The net effect: "5 " → " 5" → "05",
        // "12" → "12", "  " → "00".
        // ----------------------------------------------------------------
        String rawOption = input.option();
        String trimmed = rawOption.stripTrailing();
        // Defensive: if the operator somehow types more than 2 chars
        // (shouldn't happen with a PIC X(02) BMS field), take the rightmost
        // 2 characters to mirror COBOL's "MOVE ... TO WS-OPTION-X" with
        // truncation semantics on the left.
        if (trimmed.length() > OPTION_FIELD_WIDTH) {
            trimmed = trimmed.substring(trimmed.length() - OPTION_FIELD_WIDTH);
        }
        // Left-pad with spaces (the JUST RIGHT effect) and then promote
        // leading spaces to zeros (the INSPECT effect).
        String justRight = String.format(Locale.ROOT, "%2s", trimmed);
        String optionX = justRight.replace(' ', '0');

        // ----------------------------------------------------------------
        // COBOL lines 124-125:
        //     MOVE WS-OPTION-X TO WS-OPTION
        //     MOVE WS-OPTION TO OPTIONO
        //
        // WS-OPTION is PIC 9(02). If WS-OPTION-X contains non-digit
        // characters, the MOVE produces garbage and the subsequent
        // IS NOT NUMERIC check returns true. In Java we parse via
        // Character.isDigit before Integer.parseInt to avoid
        // NumberFormatException.
        // ----------------------------------------------------------------
        boolean isNumeric = optionX.length() == OPTION_FIELD_WIDTH
                && optionX.chars().allMatch(Character::isDigit);
        int option = isNumeric ? Integer.parseInt(optionX) : -1;
        // The echo back to OPTIONO: numeric value formatted as 2 digits
        // (PIC 9(02) → "00".."99"); for the non-numeric path the original
        // padded 2-char string is echoed so the user sees what they typed.
        String optionEcho = isNumeric
                ? String.format(Locale.ROOT, "%02d", option)
                : optionX;

        // ----------------------------------------------------------------
        // COBOL lines 127-134 (numeric / range validation):
        //     IF WS-OPTION IS NOT NUMERIC OR
        //        WS-OPTION > CDEMO-MENU-OPT-COUNT OR
        //        WS-OPTION = ZEROS
        //         MOVE 'Y' TO WS-ERR-FLG
        //         MOVE 'Please enter a valid option number...' TO WS-MESSAGE
        //         PERFORM SEND-MENU-SCREEN
        //     END-IF
        // ----------------------------------------------------------------
        if (!isNumeric || option == 0 || option > MainMenuTable.OPT_COUNT) {
            return new Outcome.Render(
                    commarea,
                    sendMenuScreen(optionEcho, INVALID_OPTION_MSG, ERRMSGC_DEFAULT));
        }

        // Resolve the selected entry. By post-condition of the range check,
        // option is in [1, OPT_COUNT] and the list access is in-bounds.
        MainMenuEntry entry = MainMenuTable.ENTRIES.get(option - 1);

        // ----------------------------------------------------------------
        // COBOL lines 135-140 (user-type access control):
        //     IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        //         SET ERR-FLG-ON TO TRUE
        //         MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
        //         PERFORM SEND-MENU-SCREEN
        //     END-IF
        //
        // Translates to pattern matching on the sealed UserType hierarchy:
        // CDEMO-USRTYP-USER ≡ commarea user type is UserType.User; the
        // USRTYPE='A' check on the menu entry ≡ entry user type is
        // UserType.Admin.
        //
        // Dead code today: all 10 MainMenuTable.ENTRIES carry UserType.USER,
        // so this branch never fires from the production table. Preserved
        // per AAP §0.7.1.
        // ----------------------------------------------------------------
        UserType callerType = commarea.cdemoGeneralInfo().userType();
        if (callerType instanceof UserType.User
                && entry.userType() instanceof UserType.Admin) {
            return new Outcome.Render(
                    commarea,
                    sendMenuScreen(optionEcho, NO_ACCESS_ADMIN_MSG, ERRMSGC_DEFAULT));
        }

        // ----------------------------------------------------------------
        // COBOL lines 142-164 (DUMMY guard + XCTL + coming-soon fallback):
        //     IF NOT ERR-FLG-ON
        //         IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
        //             MOVE WS-TRANID TO CDEMO-FROM-TRANID
        //             MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        //             MOVE ZEROS TO CDEMO-PGM-CONTEXT
        //             EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
        //                            COMMAREA(CARDDEMO-COMMAREA)
        //             END-EXEC
        //         END-IF
        //         MOVE DFHGREEN TO ERRMSGC OF COMEN1AO
        //         STRING 'This option ' DELIMITED BY SIZE
        //                CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE
        //                'is coming soon ...' DELIMITED BY SIZE
        //           INTO WS-MESSAGE
        //         END-STRING
        //         PERFORM SEND-MENU-SCREEN
        //     END-IF
        //
        // The CICS XCTL never returns to the calling program; the
        // statements AFTER the inner IF block (the MOVE DFHGREEN and the
        // STRING + SEND) are therefore only reachable when the DUMMY guard
        // matched and the XCTL was skipped. This makes the "coming soon"
        // branch effectively dead code today (no MainMenuTable entry uses
        // a "DUMMY"-prefixed program name) but it is preserved per AAP
        // §0.7.1.
        // ----------------------------------------------------------------
        String targetProgram = entry.programName();
        if (!targetProgram.startsWith(DUMMY_PROGRAM_PREFIX)) {
            // Build outbound commarea: FROM-TRANID=CM00, FROM-PROGRAM=COMEN01C,
            // PGM-CONTEXT=ENTER (reset for the callee's first entry).
            CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
            CdemoGeneralInfo outbound = new CdemoGeneralInfo(
                    TRANSACTION_ID,      // fromTranId = "CM00" (4 chars)
                    PROGRAM_NAME,        // fromProgram = "COMEN01C" (8 chars)
                    current.toTranId(),  // unchanged
                    current.toProgram(), // unchanged
                    current.userId(),    // unchanged
                    current.userType(),  // unchanged
                    PgmContext.ENTER);   // MOVE ZEROS TO CDEMO-PGM-CONTEXT
            CardDemoCommarea outboundCommarea = commarea.withCdemoGeneralInfo(outbound);
            // EXEC CICS XCTL PROGRAM(targetProgram) COMMAREA(...)
            CardDemoCommarea result = programRegistry.invoke(targetProgram, outboundCommarea);
            return new Outcome.Dispatched(result);
        }

        // "Coming soon" fall-through (DUMMY-prefixed program name).
        // STRING ... DELIMITED BY SPACE on the option name: emit only the
        // first whitespace-delimited token (e.g., "Account View" → "Account").
        String optionName = entry.optionName();
        int firstSpace = optionName.indexOf(' ');
        String firstWord = (firstSpace < 0) ? optionName : optionName.substring(0, firstSpace);
        String comingSoonMsg = COMING_SOON_PREFIX + firstWord + COMING_SOON_SUFFIX;
        return new Outcome.Render(
                commarea,
                sendMenuScreen(optionEcho, comingSoonMsg, ERRMSGC_DFHGREEN));
    }

    // ====================================================================
    // Paragraph: RETURN-TO-SIGNON-SCREEN (lines 170-177)
    // ====================================================================

    /**
     * Translates the COBOL {@code RETURN-TO-SIGNON-SCREEN} paragraph
     * (lines 170-177):
     * <pre>
     *     IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *         MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *     END-IF
     *     EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) END-EXEC
     * </pre>
     *
     * <p>If the commarea's {@code CDEMO-TO-PROGRAM} field is blank (all
     * spaces or otherwise empty after right-trim), defaults it to
     * {@link #SIGNON_PROGRAM} ({@code "COSGN00C"}) and updates the
     * commarea so the dispatched program sees the resolved target name.
     * Then dispatches via {@link ProgramRegistry#invoke}.
     *
     * @param commarea the commarea to inspect and dispatch with; non-null
     * @return an {@link Outcome.Dispatched} carrying the commarea returned
     *         by the dispatched program
     */
    private Outcome returnToSignonScreen(CardDemoCommarea commarea) {
        // ----------------------------------------------------------------
        // The COBOL "= LOW-VALUES OR SPACES" check translates to: is the
        // stored toProgram either null, all-spaces, or otherwise blank?
        // CdemoGeneralInfo fixed-length validation guarantees toProgram is
        // exactly 8 chars and non-null, so isBlank() covers the all-spaces
        // case.
        // ----------------------------------------------------------------
        String toProgram = commarea.cdemoGeneralInfo().toProgram();
        boolean isBlank = toProgram == null || toProgram.isBlank();

        // Resolve the dispatch target and the outbound commarea. If the
        // toProgram was blank, we both compute the target ("COSGN00C") and
        // update the commarea field so the dispatched program sees the
        // resolved name (matching the COBOL "MOVE 'COSGN00C' TO CDEMO-TO-
        // PROGRAM" before the XCTL).
        CardDemoCommarea outbound;
        String target;
        if (isBlank) {
            target = SIGNON_PROGRAM;
            outbound = commarea.withCdemoGeneralInfo(
                    withToProgram(commarea.cdemoGeneralInfo(), SIGNON_PROGRAM));
        } else {
            // Right-trim for the registry lookup; the stored value remains
            // exactly 8 chars per the CdemoGeneralInfo fixed-length contract.
            target = toProgram.strip();
            outbound = commarea;
        }

        // EXEC CICS XCTL PROGRAM(target) (no COMMAREA clause in this paragraph,
        // but the program's working commarea is what we pass through to the
        // dispatched program — same value the registry would receive in any
        // case).
        CardDemoCommarea result = programRegistry.invoke(target, outbound);
        return new Outcome.Dispatched(result);
    }

    // ====================================================================
    // EVALUATE EIBAID "WHEN OTHER" branch
    // ====================================================================

    /**
     * Translates the COBOL {@code WHEN OTHER} branch of the
     * {@code EVALUATE EIBAID} statement in {@code 0000-MAIN-PARA}
     * (lines 99-102):
     * <pre>
     *     WHEN OTHER       MOVE 'Y' TO WS-ERR-FLG
     *                      MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
     *                      PERFORM SEND-MENU-SCREEN
     * </pre>
     *
     * <p>Re-renders the menu with {@link SystemMessages#INVALID_KEY_MSG}
     * and the operator's option echoed back (a courtesy: their entry is
     * not discarded just because they pressed an unrecognized key).
     *
     * @param commarea the current commarea (returned unchanged in the
     *                 Render outcome)
     * @param input    the BMS input record (used to echo back the
     *                 operator's option entry, if any)
     * @return an {@link Outcome.Render} carrying the menu output with the
     *         invalid-key error message
     */
    private Outcome renderInvalidKey(CardDemoCommarea commarea, CoMen01Input input) {
        // Echo the operator's option entry (right-trimmed) so the screen
        // refresh does not erase what they typed. CoMen01Input.option() is
        // guaranteed non-null by the compact constructor.
        String optionEcho = input.option().stripTrailing();
        return new Outcome.Render(
                commarea,
                sendMenuScreen(optionEcho, SystemMessages.INVALID_KEY_MSG, ERRMSGC_DEFAULT));
    }

    // ====================================================================
    // Paragraph: SEND-MENU-SCREEN (lines 182-194)
    // ====================================================================

    /**
     * Translates the COBOL {@code SEND-MENU-SCREEN} paragraph
     * (lines 182-194). Builds the populated {@link CoMen01Output} by:
     * <ol>
     *   <li>Starting from {@link CoMen01Output#empty()} (the Java analogue
     *       of {@code MOVE LOW-VALUES TO COMEN1AO}).</li>
     *   <li>Populating the header (title/transaction/program/date/time)
     *       via {@link #populateHeaderInfo}.</li>
     *   <li>Populating the 12 option-line slots
     *       ({@code OPTN001O..OPTN012O}) via {@link #buildMenuOptions}; only
     *       the first {@link MainMenuTable#OPT_COUNT} slots receive option
     *       text (slots 11 and 12 remain at their {@code ""} default,
     *       matching the COBOL {@code MOVE LOW-VALUES} initialization for
     *       slots beyond {@code CDEMO-MENU-OPT-COUNT}).</li>
     *   <li>Setting the operator-input echo ({@code OPTIONO}), error
     *       message ({@code ERRMSGO}), and error color ({@code ERRMSGC}).</li>
     * </ol>
     *
     * <p>The COBOL paragraph also issues the {@code EXEC CICS SEND MAP}
     * call; in Java that step is performed by the caller wrapping this
     * output in {@link Outcome.Render} and the BMS adapter at the
     * composition root.
     *
     * @param optionEcho   the operator's option entry to echo into
     *                     {@code OPTIONO}; pass {@code ""} for a clean
     *                     refresh
     * @param wsMessage    the error/status message to place in
     *                     {@code ERRMSGO}; pass {@code ""} for no message
     * @param errMsgColor  the BMS extended-attribute mnemonic for
     *                     {@code ERRMSGC} (typically
     *                     {@link #ERRMSGC_DEFAULT} or
     *                     {@link #ERRMSGC_DFHGREEN})
     * @return a fully populated {@link CoMen01Output}; never {@code null}
     */
    private CoMen01Output sendMenuScreen(String optionEcho, String wsMessage, String errMsgColor) {
        CoMen01Output base = CoMen01Output.empty();
        // PERFORM POPULATE-HEADER-INFO
        base = populateHeaderInfo(base);
        // PERFORM BUILD-MENU-OPTIONS
        base = buildMenuOptions(base);
        // MOVE WS-OPTION TO OPTIONO (echo back); MOVE WS-MESSAGE TO ERRMSGO;
        // ERRMSGC was either left at its BMS-map default or moved to DFHGREEN
        // earlier in PROCESS-ENTER-KEY's coming-soon path.
        base = base.withOption(optionEcho == null ? "" : optionEcho);
        base = base.withErrMsg(wsMessage == null ? "" : wsMessage);
        base = base.withErrMsgColor(errMsgColor == null ? "" : errMsgColor);
        return base;
    }

    // ====================================================================
    // Paragraph: POPULATE-HEADER-INFO (lines 212-231)
    // ====================================================================

    /**
     * Translates the COBOL {@code POPULATE-HEADER-INFO} paragraph
     * (lines 212-231):
     * <pre>
     *     MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     *     MOVE CCDA-TITLE01 TO TITLE01O
     *     MOVE CCDA-TITLE02 TO TITLE02O
     *     MOVE WS-TRANID TO TRNNAMEO
     *     MOVE WS-PGMNAME TO PGMNAMEO
     *     ... (format MM/DD/YY and HH:MM:SS) ...
     *     MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
     *     MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
     * </pre>
     *
     * <p>The current date/time is obtained from the injected {@link Clock}
     * to support deterministic test fixtures
     * ({@code Clock.fixed(Instant, ZoneId)}); production callers typically
     * pass {@link Clock#systemDefaultZone()}.
     *
     * @param base the input output record to populate; non-null
     * @return a new {@link CoMen01Output} with the header fields populated;
     *         all non-header fields from {@code base} are preserved
     */
    private CoMen01Output populateHeaderInfo(CoMen01Output base) {
        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();
        // The chained withers return new CoMen01Output instances at each
        // step; the JIT should inline them away. The order matches the
        // COBOL paragraph order to ease cross-reference with the source.
        return base
                .withTitle01(ScreenTitle.TITLE_01)                 // 40-char title
                .withTitle02(ScreenTitle.TITLE_02)                 // 40-char title
                .withTrnName(TRANSACTION_ID)                        // 4-char tranid
                .withPgmName(PROGRAM_NAME)                          // 8-char program-id
                .withCurDate(DateConstants.formatMmDdYy(today))     // 8-char MM/DD/YY
                .withCurTime(DateConstants.formatHhMmSs(nowTime));  // 8-char HH:MM:SS
    }

    // ====================================================================
    // Paragraph: BUILD-MENU-OPTIONS (lines 236-277)
    // ====================================================================

    /**
     * Translates the COBOL {@code BUILD-MENU-OPTIONS} paragraph
     * (lines 236-277):
     * <pre>
     *     PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
     *         MOVE SPACES TO WS-MENU-OPT-TXT
     *         STRING CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE
     *                '. ' DELIMITED BY SIZE
     *                CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *           INTO WS-MENU-OPT-TXT
     *         EVALUATE WS-IDX
     *             WHEN 1  MOVE WS-MENU-OPT-TXT TO OPTN001O
     *             WHEN 2  MOVE WS-MENU-OPT-TXT TO OPTN002O
     *             ...
     *             WHEN 12 MOVE WS-MENU-OPT-TXT TO OPTN012O
     *             WHEN OTHER CONTINUE
     *         END-EVALUATE
     *     END-PERFORM
     * </pre>
     *
     * <p>The COBOL loop populates slots 1 through {@link MainMenuTable#OPT_COUNT}
     * (10 in the current table). Slots 11 and 12 are not populated by this
     * paragraph because the {@code UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT}
     * condition exits the loop at slot 11. Per the prior {@code MOVE LOW-
     * VALUES TO COMEN1AO}, those slots remain at their default empty value.
     *
     * @param base the input output record to populate; non-null
     * @return a new {@link CoMen01Output} with the option lines populated;
     *         all non-option fields from {@code base} are preserved
     */
    private static CoMen01Output buildMenuOptions(CoMen01Output base) {
        CoMen01Output result = base;
        for (int idx = 1; idx <= MainMenuTable.OPT_COUNT; idx++) {
            MainMenuEntry entry = MainMenuTable.ENTRIES.get(idx - 1);
            // STRING formatting: 2-digit option num + ". " + 35-char name,
            // right-padded to 40 chars per PIC X(40) on WS-MENU-OPT-TXT.
            String line = formatOptionLine(entry.optionNumber(), entry.optionName());
            result = setOptionLine(result, idx, line);
        }
        return result;
    }

    /**
     * Formats one menu option line per the COBOL {@code STRING
     * CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE '. ' DELIMITED BY SIZE
     * CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE INTO WS-MENU-OPT-TXT}
     * statement (lines 243-246). Produces a 40-character (PIC X(40)) line:
     * <pre>
     *     "01. Account View                       " ← 40 chars total
     * </pre>
     * The format is: 2-digit option number (PIC 9(02) {@code "%02d"}) + ". "
     * separator (2 chars) + option name (35 chars, already padded by
     * {@link MainMenuTable.MainMenuEntry#optionName()}) = 39 chars, then
     * right-padded with one trailing space to fill PIC X(40).
     *
     * @param num  the option number; range [1, OPT_COUNT]
     * @param name the option name (exactly {@link MainMenuTable#OPT_NAME_LENGTH}
     *             chars per the {@link MainMenuTable.MainMenuEntry} contract)
     * @return a 40-character line; never {@code null}
     */
    private static String formatOptionLine(int num, String name) {
        // 2-digit number + ". " separator = 4 chars (OPTION_LINE_PREFIX_WIDTH).
        // The name is already exactly OPT_NAME_LENGTH chars from MainMenuEntry's
        // canonical constructor; we right-pad the concatenation to 40 chars
        // (OPTION_LINE_WIDTH) to match PIC X(40) on WS-MENU-OPT-TXT.
        String prefix = String.format(Locale.ROOT, "%02d. ", num);
        String combined = prefix + (name == null ? "" : name);
        return padRight(combined, OPTION_LINE_WIDTH);
    }

    /**
     * Translates the COBOL {@code EVALUATE WS-IDX ... END-EVALUATE} dispatch
     * inside {@code BUILD-MENU-OPTIONS} (lines 248-275). Places the
     * pre-formatted option line into the appropriate
     * {@code OPTN001O..OPTN012O} slot of the output record.
     *
     * <p><b>Note on {@code default} branch usage:</b> This switch is over a
     * primitive {@code int}, NOT over a sealed type, so a {@code default}
     * branch is permitted (and required to express the COBOL
     * {@code WHEN OTHER CONTINUE}). The "no default branch" rule of AAP
     * &sect;0.7.3 applies only to switches over sealed types.
     *
     * @param out  the output record to update
     * @param idx  the 1-based slot index (1..12)
     * @param line the pre-formatted 40-char option line
     * @return a new {@link CoMen01Output} with the appropriate slot
     *         updated; if {@code idx} is outside [1, 12] returns
     *         {@code out} unchanged (the COBOL {@code WHEN OTHER CONTINUE})
     */
    private static CoMen01Output setOptionLine(CoMen01Output out, int idx, String line) {
        return switch (idx) {
            case 1  -> out.withOption001(line);
            case 2  -> out.withOption002(line);
            case 3  -> out.withOption003(line);
            case 4  -> out.withOption004(line);
            case 5  -> out.withOption005(line);
            case 6  -> out.withOption006(line);
            case 7  -> out.withOption007(line);
            case 8  -> out.withOption008(line);
            case 9  -> out.withOption009(line);
            case 10 -> out.withOption010(line);
            case 11 -> out.withOption011(line);
            case 12 -> out.withOption012(line);
            default -> out;   // COBOL: WHEN OTHER CONTINUE
        };
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    /**
     * Returns a copy of {@code info} with {@code fromProgram} replaced.
     * Preserves all other components verbatim. Helper for translating the
     * COBOL {@code MOVE ... TO CDEMO-FROM-PROGRAM} idiom; the
     * {@link CdemoGeneralInfo} record has no built-in {@code with*}
     * methods, so this hand-rolled copy is used at the few call sites that
     * need to update a single field.
     *
     * @param info        the source general-info; non-null
     * @param fromProgram the new {@code fromProgram} value (must be exactly
     *                    {@value CardDemoCommarea#LENGTH_FROM_PROGRAM}
     *                    ASCII characters)
     * @return a new {@link CdemoGeneralInfo} with the replacement; never
     *         {@code null}
     */
    private static CdemoGeneralInfo withFromProgram(CdemoGeneralInfo info, String fromProgram) {
        return new CdemoGeneralInfo(
                info.fromTranId(),
                fromProgram,
                info.toTranId(),
                info.toProgram(),
                info.userId(),
                info.userType(),
                info.pgmContext());
    }

    /**
     * Returns a copy of {@code info} with {@code toProgram} replaced.
     * Preserves all other components verbatim. Helper for translating the
     * COBOL {@code MOVE ... TO CDEMO-TO-PROGRAM} idiom.
     *
     * @param info      the source general-info; non-null
     * @param toProgram the new {@code toProgram} value (must be exactly
     *                  {@value CardDemoCommarea#LENGTH_TO_PROGRAM} ASCII
     *                  characters)
     * @return a new {@link CdemoGeneralInfo} with the replacement; never
     *         {@code null}
     */
    private static CdemoGeneralInfo withToProgram(CdemoGeneralInfo info, String toProgram) {
        return new CdemoGeneralInfo(
                info.fromTranId(),
                info.fromProgram(),
                info.toTranId(),
                toProgram,
                info.userId(),
                info.userType(),
                info.pgmContext());
    }

    /**
     * Returns a copy of {@code info} with {@code pgmContext} replaced.
     * Preserves all other components verbatim. Helper for translating the
     * COBOL {@code SET CDEMO-PGM-REENTER TO TRUE} (and inverse) idioms.
     *
     * @param info       the source general-info; non-null
     * @param pgmContext the new {@code pgmContext} value (must be non-null;
     *                   typically {@link PgmContext#ENTER} or
     *                   {@link PgmContext#REENTER})
     * @return a new {@link CdemoGeneralInfo} with the replacement; never
     *         {@code null}
     */
    private static CdemoGeneralInfo withPgmContext(CdemoGeneralInfo info, PgmContext pgmContext) {
        return new CdemoGeneralInfo(
                info.fromTranId(),
                info.fromProgram(),
                info.toTranId(),
                info.toProgram(),
                info.userId(),
                info.userType(),
                pgmContext);
    }

    /**
     * Right-pads a string with spaces to the given width, truncating if
     * longer. Mirrors the COBOL behavior of moving a shorter alphanumeric
     * value into a {@code PIC X(N)} field (space-padding on the right) and
     * truncating a longer source (right-side truncation).
     *
     * @param s     the string to pad; may be {@code null} (returns a
     *              string of {@code width} spaces)
     * @param width the desired width; must be non-negative
     * @return a string of exactly {@code width} characters; never
     *         {@code null}
     */
    private static String padRight(String s, int width) {
        if (s == null) {
            return " ".repeat(width);
        }
        if (s.length() == width) {
            return s;
        }
        if (s.length() > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }
}
