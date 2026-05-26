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
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.menu.AdminMenuTable;
import com.blitzy.carddemo.domain.menu.AdminMenuTable.AdminMenuEntry;
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
 * Administrative menu online program for admin users only. Translation of
 * {@code app/cbl/COADM01C.cbl} ({@code PROGRAM-ID COADM01C}, transaction
 * ID {@code CA00}).
 *
 * <h2>Program purpose</h2>
 * <p>Displays the administrative main menu (4 options sourced from the
 * COBOL copybook {@code COADM02Y.cpy} &rarr;
 * {@link com.blitzy.carddemo.domain.menu.AdminMenuTable}), receives the
 * user's option selection, and transfers control ({@code EXEC CICS XCTL})
 * to the corresponding user-management program ({@code COUSR00C},
 * {@code COUSR01C}, {@code COUSR02C}, or {@code COUSR03C}). PF3 returns
 * control to the signon program {@code COSGN00C}; any other AID key
 * surfaces the standard invalid-key message.
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:    COADM01C
 *   WS-PGMNAME:    'COADM01C'
 *   WS-TRANID:     'CA00'
 *   WS-USRSEC-FILE 'USRSEC  '  (not exercised here at runtime)
 * </pre>
 *
 * <h2>Structurally near-identical to {@link CoMen01C}, with these
 * differences (preserved verbatim per AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>Uses {@link AdminMenuTable} (4 entries) instead of
 *       {@link com.blitzy.carddemo.domain.menu.MainMenuTable}
 *       (10 entries) for option lookup.</li>
 *   <li>Renders 10 option-line slots ({@code OPTN001..OPTN010}) instead
 *       of 12 &mdash; matching the {@code COADM01} BMS symbolic structure
 *       in {@code app/cpy-bms/COADM01.CPY} which declares the surfaced
 *       admin-menu option fields. {@link CoAdm01Output} models exactly
 *       those 10 slots.</li>
 *   <li>Has NO user-type access check (admin programs are accessible by
 *       virtue of which menu is dispatched to, not by a per-option
 *       runtime user-type filter). The COBOL admin-menu copybook
 *       {@code COADM02Y.cpy} has no {@code CDEMO-ADMIN-OPT-USRTYPE}
 *       subfield and the program has no {@code IF CDEMO-USRTYP-USER}
 *       guard around the dispatch.</li>
 *   <li>The "coming soon" message has the option-name interpolation
 *       <em>commented out</em> in the COBOL source
 *       ({@code app/cbl/COADM01C.cbl} lines 149&ndash;152): the
 *       {@code STRING CDEMO-ADMIN-OPT-NAME(WS-OPTION) DELIMITED BY SIZE}
 *       line is prefixed with {@code *} in column 7, leaving the literal
 *       message {@code "This option is coming soon ..."}. Preserved
 *       verbatim per AAP &sect;0.7.1 (idiom-for-idiom translation: if
 *       a COBOL paragraph contains dead or commented-out code, translate
 *       it faithfully and document; do not "fix").</li>
 *   <li>Transaction ID {@code "CA00"} (vs. {@code "CM00"} for the regular
 *       main menu) confirms this is a distinct CICS transaction.</li>
 * </ul>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} ({@code app/cbl/COADM01C.cbl:75-110}):
 *       {@code EIBCALEN} check &rarr; return-to-signon vs. first-entry vs.
 *       re-entry dispatch; on re-entry the {@code EVALUATE EIBAID} block
 *       (ENTER / PF3 / OTHER) determines next action.</li>
 *   <li>{@code PROCESS-ENTER-KEY} ({@code app/cbl/COADM01C.cbl:115-155}):
 *       option-number parsing with right-justification and
 *       {@code INSPECT REPLACING ALL ' ' BY '0'}; range and numeric
 *       validation against {@link AdminMenuTable#OPT_COUNT};
 *       {@code XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))} when
 *       the target program name does NOT start with {@code "DUMMY"};
 *       emits the commented-out-name "coming soon" message otherwise.</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN}
 *       ({@code app/cbl/COADM01C.cbl:160-167}):
 *       {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} (defaulting to
 *       {@code COSGN00C} when blank).</li>
 *   <li>{@code SEND-MENU-SCREEN} ({@code app/cbl/COADM01C.cbl:172-184}):
 *       composes the populated {@link CoAdm01Output} record carrying
 *       header date/time/title, 10 option lines, and the optional error
 *       message.</li>
 *   <li>{@code RECEIVE-MENU-SCREEN}
 *       ({@code app/cbl/COADM01C.cbl:189-197}): not directly translated
 *       (the {@link CoAdm01Input} parameter is the Java equivalent of the
 *       received {@code COADM1AI} symbolic structure).</li>
 *   <li>{@code POPULATE-HEADER-INFO}
 *       ({@code app/cbl/COADM01C.cbl:202-221}): populates
 *       {@code TITLE01O / TITLE02O / TRNNAMEO / PGMNAMEO / CURDATEO /
 *       CURTIMEO} on the output map using {@link DateConstants} for
 *       date/time formatting and {@link ScreenTitle} for the banner.</li>
 *   <li>{@code BUILD-MENU-OPTIONS}
 *       ({@code app/cbl/COADM01C.cbl:226-263}): iterates
 *       {@code 1..CDEMO-ADMIN-OPT-COUNT} ({@code = 4}), composes each
 *       option line as {@code "NN. <option-name>"} (39 chars) padded to
 *       40 chars and dispatches to one of {@code OPTN001O..OPTN010O}.
 *       Slots 5&ndash;10 remain blank because there are only 4 entries
 *       in the COBOL admin table.</li>
 * </ul>
 *
 * <h2>AID-key dispatch (Java 25 mandate: exhaustive switch over sealed
 * {@link AidKey} with NO {@code default})</h2>
 * <p>Per AAP &sect;0.7.3, all switches over the sealed {@link AidKey}
 * type must be exhaustive at compile time and must NOT include a
 * {@code default} branch. The pattern-matching {@code switch} in
 * {@link #process(CardDemoCommarea, CoAdm01Input, byte, int)} enumerates
 * all 16 permits: {@link AidKey.Enter}, {@link AidKey.Clear},
 * {@link AidKey.Pa1}, {@link AidKey.Pa2}, and {@link AidKey.PfKey01}
 * through {@link AidKey.PfKey12}.
 *
 * <h2>{@code ProgramRegistry}-based XCTL translation</h2>
 * <p>Per AAP &sect;0.4.2 the COBOL construct
 * {@code EXEC CICS XCTL PROGRAM(<variable>) COMMAREA(...)} translates to
 * {@code programRegistry.invoke(programName, commarea)}. This program
 * uses the registry for:
 * <ol>
 *   <li>Dispatching the selected admin-menu option (variable program
 *       name resolved from {@link AdminMenuEntry#programName()}); and</li>
 *   <li>Returning to the signon screen (variable program name resolved
 *       from {@code CDEMO-TO-PROGRAM} or the default
 *       {@link #SIGNON_PROGRAM}).</li>
 * </ol>
 *
 * <h2>{@link Clock} injection for deterministic header rendering</h2>
 * <p>The constructor accepts a {@link Clock} so that
 * {@link #populateHeaderInfo} can render the current date/time in the
 * output header. Injecting the clock enables deterministic unit tests
 * (e.g., {@code Clock.fixed(Instant.parse("2024-03-15T13:45:23Z"),
 * ZoneOffset.UTC)}) without resorting to mocking
 * {@code LocalDateTime.now()}.
 *
 * <h2>Forbidden / Required (AAP &sect;0.7.3, &sect;0.7.4)</h2>
 * <ul>
 *   <li>NO Spring, Lombok, Bean Validation, ThreadLocal, reflection.</li>
 *   <li>NO {@code --enable-preview}; finalized Java&nbsp;25 features only
 *       (records, sealed types, pattern-matching switch, JEP 513
 *       flexible constructor bodies on records).</li>
 *   <li>NO {@code java.util.Date} / {@code java.util.Calendar}; only
 *       {@code java.time} types.</li>
 *   <li>NO {@code java.io.File}; only {@code java.nio.file} (not
 *       applicable here &mdash; this is an online program with no I/O).</li>
 *   <li>NO {@code double} / {@code float} (no monetary code in this
 *       program).</li>
 *   <li>Required {@link CobolProgram &#64;CobolProgram} traceability
 *       annotation citing PROGRAM-ID {@code COADM01C}, source path
 *       {@code app/cbl/COADM01C.cbl}, and translation date.</li>
 * </ul>
 *
 * @see CoAdm01Input
 * @see CoAdm01Output
 * @see CoMen01C
 * @see com.blitzy.carddemo.domain.menu.AdminMenuTable
 * @see ProgramRegistry
 * @see <a href="../../../../../../../../../../app/cbl/COADM01C.cbl">COADM01C.cbl</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "COADM01C",
        sourcePath = "app/cbl/COADM01C.cbl",
        translationDate = "2025-09-16",
        notes = "Admin menu online program for admin users only. Transaction ID "
                + "CA00. No user-type access check (admin programs are accessible "
                + "by virtue of which menu is dispatched, not by per-option filter). "
                + "Coming-soon message omits option name (matches commented-out "
                + "STRING CDEMO-ADMIN-OPT-NAME line at app/cbl/COADM01C.cbl:150-151)."
)
public final class CoAdm01C {

    // ----------------------------------------------------------------
    // Public constants (exposed for callers, tests, and golden-record
    // harness; mandated by schema members_exposed list)
    // ----------------------------------------------------------------

    /**
     * COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} value. Used
     * when populating {@code CDEMO-FROM-PROGRAM} in the commarea prior
     * to {@code XCTL}, and when rendering the {@code PGMNAMEO} output
     * header field.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * COBOL {@code WS-TRANID PIC X(04) VALUE 'CA00'} value. Used when
     * populating {@code CDEMO-FROM-TRANID} in the commarea prior to
     * {@code XCTL}, and when rendering the {@code TRNNAMEO} output
     * header field.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * COBOL signon-fallback program name. Used as the default destination
     * when the inbound commarea has an empty {@code CDEMO-TO-PROGRAM}
     * (the COBOL idiom
     * {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES MOVE 'COSGN00C'
     * TO CDEMO-TO-PROGRAM}), and as the destination for PF3 / EIBCALEN-zero
     * paths.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Error message used when the operator enters an option number that
     * is non-numeric, zero, or greater than {@link AdminMenuTable#OPT_COUNT}.
     * Verbatim COBOL literal from {@code app/cbl/COADM01C.cbl} line 131:
     * {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.
     */
    public static final String INVALID_OPTION_MSG = "Please enter a valid option number...";

    /**
     * "Coming soon" message. The COBOL source
     * ({@code app/cbl/COADM01C.cbl:149-153}) has the
     * {@code STRING CDEMO-ADMIN-OPT-NAME(WS-OPTION) DELIMITED BY SIZE}
     * interpolation commented out, leaving the prefix
     * {@code 'This option '} directly joined to the suffix
     * {@code 'is coming soon ...'}. The resulting message therefore
     * reads {@code "This option is coming soon ..."} (with a single
     * space between {@code "option"} and {@code "is"} contributed by
     * the trailing space of the prefix literal). Preserved verbatim
     * per AAP &sect;0.7.1.
     *
     * <p>In practice this path is never taken at runtime because all
     * four entries in {@link AdminMenuTable#ENTRIES} have real program
     * names ({@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C},
     * {@code COUSR03C}) and none start with the
     * {@link #DUMMY_PROGRAM_PREFIX}. The translation preserves the COBOL
     * guard for fidelity nonetheless.
     */
    public static final String COMING_SOON_MSG = "This option is coming soon ...";

    // ----------------------------------------------------------------
    // Internal constants (BMS field widths and other invariants)
    // ----------------------------------------------------------------

    /**
     * Prefix used by the COBOL source to detect a placeholder
     * program-id ({@code IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5)
     * NOT = 'DUMMY'} at {@code app/cbl/COADM01C.cbl:138}). When the
     * first 5 characters of the target program name equal {@code "DUMMY"}
     * the program emits the {@link #COMING_SOON_MSG} message instead
     * of dispatching.
     */
    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * Width of the {@code OPTIONO} field per BMS field {@code OPTION}
     * ({@code LENGTH=2}) declared in {@code app/bms/COADM01.bms}. Two
     * digits matching the COBOL {@code WS-OPTION-X PIC X(02)} and
     * {@code WS-OPTION PIC 9(02)} fields.
     */
    private static final int OPTION_FIELD_WIDTH = 2;

    /**
     * Width of each {@code OPTN001O..OPTN010O} option-line field per BMS
     * fields {@code OPTN001..OPTN010} ({@code LENGTH=40}) declared in
     * {@code app/bms/COADM01.bms}. 40 characters matching the COBOL
     * {@code WS-ADMIN-OPT-TXT PIC X(40)}.
     */
    private static final int OPTION_LINE_WIDTH = 40;

    /**
     * Width of the assembled option-line body BEFORE the final
     * 1-character right-pad space ({@value #OPTION_LINE_WIDTH} minus 1).
     * Used internally by {@link #formatOptionLine(int, String)} to
     * guarantee a deterministic 40-character result regardless of the
     * length of the supplied option name.
     */
    private static final int OPTION_LINE_BODY_WIDTH = OPTION_LINE_WIDTH - 1;

    /**
     * Width of the {@code TITLE01O} and {@code TITLE02O} header fields
     * per BMS fields {@code TITLE01} / {@code TITLE02}
     * ({@code LENGTH=40}) declared in {@code app/bms/COADM01.bms}.
     */
    private static final int TITLE_WIDTH = 40;

    /**
     * Width of the {@code CURDATEO} and {@code CURTIMEO} header fields
     * per BMS fields {@code CURDATE} / {@code CURTIME} ({@code LENGTH=8}).
     * The values are formatted by {@link DateConstants#formatMmDdYy} and
     * {@link DateConstants#formatHhMmSs} which both return exactly 8
     * characters.
     */
    private static final int DATE_TIME_WIDTH = 8;

    /**
     * Width of the {@code TRNNAMEO} header field per BMS field
     * {@code TRNNAME} ({@code LENGTH=4}). Matches the
     * {@link #TRANSACTION_ID} literal width.
     */
    private static final int TRAN_WIDTH = 4;

    /**
     * Width of the {@code PGMNAMEO} header field per BMS field
     * {@code PGMNAME} ({@code LENGTH=8}). Matches the
     * {@link #PROGRAM_NAME} literal width.
     */
    private static final int PGM_WIDTH = 8;

    /**
     * Width of the {@code ERRMSGO} field per BMS field {@code ERRMSG}
     * ({@code LENGTH=78}) declared in {@code app/bms/COADM01.bms}. This
     * is the receiving field for the (up to 80-char) {@code WS-MESSAGE}
     * working-storage value; COBOL {@code MOVE WS-MESSAGE TO ERRMSGO}
     * truncates to 78 chars.
     */
    private static final int ERRMSG_WIDTH = 78;

    // ----------------------------------------------------------------
    // Sealed Outcome — the return type of process(...) (per schema)
    // ----------------------------------------------------------------

    /**
     * Result of {@link CoAdm01C#process(CardDemoCommarea, CoAdm01Input,
     * byte, int)}. Two permits model the two terminal actions a CICS
     * online program can take:
     *
     * <ul>
     *   <li>{@link Render &mdash; SEND MAP and RETURN to CICS}:
     *       the menu screen is rendered (or re-rendered with an error
     *       message) and the program returns control to the operator
     *       awaiting their next AID-key event.</li>
     *   <li>{@link Dispatched &mdash; EXEC CICS XCTL}: control is
     *       transferred to another program via
     *       {@link ProgramRegistry#invoke(String, CardDemoCommarea)}.
     *       The carried commarea reflects the destination program's
     *       final state.</li>
     * </ul>
     *
     * <p>The sealed-type pattern is mandated by AAP &sect;0.7.3 so the
     * Java compiler enforces exhaustiveness at every call site of
     * {@link CoAdm01C#process(CardDemoCommarea, CoAdm01Input, byte, int)}.
     */
    public sealed interface Outcome {

        /**
         * The "render the menu screen" outcome &mdash; equivalent to the
         * COBOL {@code PERFORM SEND-MENU-SCREEN} followed by
         * {@code EXEC CICS RETURN TRANSID('CA00') COMMAREA(...)}.
         *
         * @param commarea the commarea to thread back to the next entry
         *                 of this program when the operator next presses
         *                 an AID key. Must be non-null.
         * @param output   the populated output map (title, date, time,
         *                 10 option lines, optional error message). Must
         *                 be non-null.
         */
        record Render(CardDemoCommarea commarea, CoAdm01Output output) implements Outcome {
            /**
             * Compact constructor enforces non-null invariants required
             * by downstream renderers and the golden-record harness.
             *
             * @throws NullPointerException if either argument is null
             */
            public Render {
                Objects.requireNonNull(commarea, "commarea");
                Objects.requireNonNull(output, "output");
            }
        }

        /**
         * The "control transferred" outcome &mdash; equivalent to the
         * COBOL {@code EXEC CICS XCTL PROGRAM(...) COMMAREA(...)}. The
         * destination program has already run (synchronously) via
         * {@link ProgramRegistry#invoke(String, CardDemoCommarea)} and
         * has returned its final commarea, which is carried here for
         * the caller's inspection.
         *
         * @param commarea the commarea returned by the dispatched
         *                 program. Must be non-null.
         */
        record Dispatched(CardDemoCommarea commarea) implements Outcome {
            /**
             * Compact constructor enforces the non-null invariant.
             *
             * @throws NullPointerException if {@code commarea} is null
             */
            public Dispatched {
                Objects.requireNonNull(commarea, "commarea");
            }
        }
    }

    // ----------------------------------------------------------------
    // Instance fields (constructor-injected collaborators) and ctor
    // ----------------------------------------------------------------

    /**
     * Registry used to dispatch the user's selected option (e.g.,
     * {@code COUSR00C}) and to return to the signon program. Equivalent
     * to the COBOL {@code EXEC CICS XCTL PROGRAM(<name>)} construct.
     * Never null.
     */
    private final ProgramRegistry programRegistry;

    /**
     * Clock supplying the current instant used to render the menu's
     * date and time header fields ({@code CURDATEO} and {@code CURTIMEO}).
     * Injecting a clock makes the program deterministic under test.
     * Never null.
     */
    private final Clock clock;

    /**
     * Sole constructor. Both collaborators are required.
     *
     * @param programRegistry registry of online program handlers used
     *                        to translate the COBOL {@code XCTL} idiom
     *                        into Java method dispatch. Must be non-null.
     * @param clock           clock supplying the current instant for
     *                        the menu header. Must be non-null. In
     *                        production use {@link Clock#systemDefaultZone()};
     *                        in unit tests use {@link Clock#fixed} for
     *                        deterministic output.
     * @throws NullPointerException if either argument is null
     */
    public CoAdm01C(ProgramRegistry programRegistry, Clock clock) {
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ----------------------------------------------------------------
    // Primary entry method — translates COBOL MAIN-PARA
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code MAIN-PARA} paragraph
     * ({@code app/cbl/COADM01C.cbl:75-110}). This is the program's
     * single public entry point and faithfully reproduces the COBOL
     * control flow:
     *
     * <ol>
     *   <li><b>{@code IF EIBCALEN = 0}</b> (no inbound commarea): set
     *       {@code CDEMO-FROM-PROGRAM = 'COSGN00C'} and
     *       {@code PERFORM RETURN-TO-SIGNON-SCREEN} &rarr; this method
     *       returns {@link Outcome.Dispatched Dispatched} carrying the
     *       result of dispatching to {@link #SIGNON_PROGRAM}.</li>
     *
     *   <li><b>Otherwise, if {@code NOT CDEMO-PGM-REENTER}</b> (first
     *       entry from the signon program): {@code SET CDEMO-PGM-REENTER
     *       TO TRUE}, {@code MOVE LOW-VALUES TO COADM1AO}, and
     *       {@code PERFORM SEND-MENU-SCREEN} &rarr; this method returns
     *       {@link Outcome.Render Render} carrying the freshly-populated
     *       output map (with no error message) and the commarea now
     *       marked as re-entered.</li>
     *
     *   <li><b>Otherwise (re-entry)</b>, evaluate {@code EIBAID} via
     *       an exhaustive pattern-matching switch over the sealed
     *       {@link AidKey} hierarchy (no {@code default} branch &mdash;
     *       AAP &sect;0.7.3 mandate):
     *     <ul>
     *       <li>{@link AidKey.Enter ENTER} &rarr;
     *           {@link #processEnterKey(CardDemoCommarea, CoAdm01Input)}
     *           &mdash; either dispatches to the selected program or
     *           renders an invalid-option / coming-soon message.</li>
     *       <li>{@link AidKey.PfKey03 PF3} &rarr; set
     *           {@code CDEMO-TO-PROGRAM = 'COSGN00C'} and
     *           {@code PERFORM RETURN-TO-SIGNON-SCREEN}.</li>
     *       <li>Any other permit (CLEAR, PA1, PA2, PF1-PF2, PF4-PF12)
     *           &rarr; render the menu with the standard
     *           {@link SystemMessages#INVALID_KEY_MSG} (COBOL
     *           {@code CCDA-MSG-INVALID-KEY}).</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param commareaIn the inbound commarea. When {@code null} (or when
     *                   {@code eibcalen == 0}), this method behaves as
     *                   if {@code EIBCALEN = 0} per the COBOL guard at
     *                   line 77 and immediately returns to signon. Such
     *                   a null is treated as a sentinel for "no commarea
     *                   established yet" rather than a programming error.
     * @param input      the inbound input map carrying the operator's
     *                   option entry. Must NOT be null (this would
     *                   represent a CICS-impossible state since
     *                   {@code RECEIVE MAP} always produces a record).
     * @param eibaid     the raw CICS {@code EIBAID} byte identifying
     *                   which AID key was pressed. Translated to an
     *                   {@link AidKey} permit via
     *                   {@link PfKeyDecoder#decode(byte)}.
     * @param eibcalen   the CICS {@code EIBCALEN} value &mdash; the length
     *                   of the inbound commarea. The COBOL test
     *                   {@code IF EIBCALEN = 0} is the signal that no
     *                   prior program established a commarea.
     * @return the resulting {@link Outcome}: either {@link Outcome.Render
     *         Render} (operator stays on the menu) or
     *         {@link Outcome.Dispatched Dispatched} (control transferred
     *         via {@link ProgramRegistry#invoke}).
     * @throws NullPointerException if {@code input} is null
     * @throws IllegalArgumentException if {@code eibaid} is not a
     *         recognised CICS AID byte (propagated from
     *         {@link PfKeyDecoder#decode(byte)}; this is a deliberate
     *         divergence from the COBOL {@code EVALUATE WHEN OTHER}
     *         silent fall-through, documented in
     *         {@link PfKeyDecoder}'s Javadoc)
     */
    public Outcome process(
            CardDemoCommarea commareaIn,
            CoAdm01Input input,
            byte eibaid,
            int eibcalen
    ) {
        Objects.requireNonNull(input, "input");

        // ------------------------------------------------------------
        // COBOL: IF EIBCALEN = 0
        //          MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
        //          PERFORM RETURN-TO-SIGNON-SCREEN
        //        ELSE ...
        // ------------------------------------------------------------
        // Treat a null commareaIn as a CICS "no commarea established"
        // sentinel (equivalent to EIBCALEN = 0). Synthesise an empty
        // commarea so the subsequent fromProgram update has a well-
        // defined target, then dispatch via RETURN-TO-SIGNON-SCREEN.
        if (eibcalen == 0 || commareaIn == null) {
            CardDemoCommarea base = (commareaIn == null)
                    ? CardDemoCommarea.empty()
                    : commareaIn;
            CardDemoCommarea seedCommarea = withFromProgram(base, SIGNON_PROGRAM);
            return returnToSignonScreen(seedCommarea);
        }

        CardDemoCommarea commarea = commareaIn;

        // ------------------------------------------------------------
        // COBOL: IF NOT CDEMO-PGM-REENTER
        //          SET CDEMO-PGM-REENTER TO TRUE
        //          MOVE LOW-VALUES TO COADM1AO
        //          PERFORM SEND-MENU-SCREEN
        //        ELSE
        //          (decode EIBAID and dispatch)
        //        END-IF.
        // ------------------------------------------------------------
        boolean isReentry = commarea.cdemoGeneralInfo().pgmContext()
                instanceof PgmContext.Reenter;
        if (!isReentry) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            CoAdm01Output rendered = sendMenuScreen(CoAdm01Output.empty(), "");
            return new Outcome.Render(reentered, rendered);
        }

        // ------------------------------------------------------------
        // Subsequent entry: decode EIBAID and dispatch on the AID key.
        // Pattern-matching switch is EXHAUSTIVE over the 16 permits of
        // the sealed AidKey type (Enter, Clear, Pa1, Pa2, PfKey01..12).
        // NO default branch is permitted (AAP §0.7.3): the compiler
        // enforces that every permit is handled, preventing the silent
        // "WHEN OTHER" fall-through bug class that COBOL allows.
        // ------------------------------------------------------------
        AidKey aid = PfKeyDecoder.decode(eibaid);
        return switch (aid) {
            // COBOL: WHEN DFHENTER PERFORM PROCESS-ENTER-KEY
            case AidKey.Enter e -> processEnterKey(commarea, input);

            // COBOL: WHEN DFHPF3
            //          MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            //          PERFORM RETURN-TO-SIGNON-SCREEN
            case AidKey.PfKey03 pf3 -> {
                CardDemoCommarea withDest = withToProgram(commarea, SIGNON_PROGRAM);
                yield returnToSignonScreen(withDest);
            }

            // COBOL: WHEN OTHER
            //          MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
            //          PERFORM SEND-MENU-SCREEN
            // (Java: each non-ENTER, non-PF3 AID key gets its own case
            //  to satisfy exhaustiveness.)
            case AidKey.Clear c     -> renderInvalidKey(commarea, input);
            case AidKey.Pa1 p1      -> renderInvalidKey(commarea, input);
            case AidKey.Pa2 p2      -> renderInvalidKey(commarea, input);
            case AidKey.PfKey01 pf1 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey02 pf2 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey04 pf4 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey05 pf5 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey06 pf6 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey07 pf7 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey08 pf8 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey09 pf9 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey10 p10 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey11 p11 -> renderInvalidKey(commarea, input);
            case AidKey.PfKey12 p12 -> renderInvalidKey(commarea, input);
        };
    }


    // ----------------------------------------------------------------
    // Paragraph: PROCESS-ENTER-KEY  (lines 115-155 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code PROCESS-ENTER-KEY} paragraph
     * ({@code app/cbl/COADM01C.cbl:115-155}). Parses and validates the
     * operator's option entry from the {@code OPTIONI} input field and
     * either:
     *
     * <ul>
     *   <li>Dispatches via {@link ProgramRegistry#invoke} when the
     *       option resolves to a real program name (e.g.,
     *       {@code COUSR00C} for option {@code 1}); or</li>
     *   <li>Renders the menu with {@link #INVALID_OPTION_MSG} when the
     *       option is non-numeric, zero, or out of range; or</li>
     *   <li>Renders the menu with {@link #COMING_SOON_MSG} when the
     *       target program name starts with the
     *       {@link #DUMMY_PROGRAM_PREFIX} placeholder (currently
     *       impossible since all 4 admin entries have real names, but
     *       preserved for faithful translation).</li>
     * </ul>
     *
     * <p><b>NO user-type access check</b>. Unlike {@link CoMen01C}'s
     * counterpart this method does NOT verify
     * {@code CDEMO-USRTYP-USER} against any per-option access type
     * because the COBOL admin-menu table {@code COADM02Y.cpy} contains
     * no {@code CDEMO-ADMIN-OPT-USRTYPE} subfield and the COBOL program
     * has no {@code IF CDEMO-USRTYP-USER} guard. The admin menu is
     * accessible only by admins by virtue of which menu is dispatched
     * to, not by per-option runtime filtering.
     *
     * <h3>Option-number parsing (COBOL right-justify + zero-fill)</h3>
     * <p>The COBOL idiom at {@code app/cbl/COADM01C.cbl:117-127} is:
     * <pre>
     *     PERFORM VARYING WS-IDX
     *             FROM LENGTH OF OPTIONI OF COADM1AI BY -1
     *             UNTIL OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES
     *                OR WS-IDX = 1
     *     END-PERFORM
     *     MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
     *     INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
     *     MOVE WS-OPTION-X              TO WS-OPTION
     * </pre>
     * Effectively this:
     * <ol>
     *   <li>Strips trailing spaces from the 2-character {@code OPTIONI}
     *       field (down to at most 1 character remaining).</li>
     *   <li>Right-justifies the remaining content within
     *       {@code WS-OPTION-X PIC X(02) JUST RIGHT}.</li>
     *   <li>Replaces any embedded or leading spaces with {@code '0'}.</li>
     *   <li>Parses the resulting 2-character numeric into
     *       {@code WS-OPTION PIC 9(02)}.</li>
     * </ol>
     * Examples:
     * <ul>
     *   <li>{@code "1 "} &rarr; strip trailing &rarr; {@code "1"}
     *       &rarr; right-justify in 2 chars &rarr; {@code " 1"}
     *       &rarr; replace spaces &rarr; {@code "01"}
     *       &rarr; numeric {@code 1}</li>
     *   <li>{@code "01"} &rarr; (no trailing spaces) &rarr;
     *       {@code "01"} &rarr; {@code "01"} &rarr; {@code 1}</li>
     *   <li>{@code "  "} &rarr; {@code "0"} &rarr; right-justify
     *       &rarr; {@code "  "} &rarr; {@code "00"} &rarr; {@code 0}
     *       (caught by the {@code = ZEROS} guard at COBOL line 130).</li>
     *   <li>{@code "99"} &rarr; {@code "99"} &rarr; {@code 99} (caught
     *       by the {@code > CDEMO-ADMIN-OPT-COUNT} guard at line 130).</li>
     *   <li>{@code "AB"} &rarr; non-numeric &rarr; rejected by the
     *       {@code WS-OPTION IS NOT NUMERIC} guard at line 130.</li>
     * </ul>
     *
     * @param commarea the inbound commarea (already verified non-null
     *                 and pgm-context = REENTER by the caller)
     * @param input    the input map carrying the operator's entries
     * @return either {@link Outcome.Render} (invalid option, coming-soon)
     *         or {@link Outcome.Dispatched} (valid option dispatched)
     */
    private Outcome processEnterKey(CardDemoCommarea commarea, CoAdm01Input input) {
        // ------------------------------------------------------------
        // Step 1: parse the option entry, replicating COBOL semantics.
        // ------------------------------------------------------------
        String rawOption = input.option() == null ? "" : input.option();

        // PERFORM VARYING ... FROM 2 BY -1 UNTIL not-space OR WS-IDX = 1
        int lastNonSpace = OPTION_FIELD_WIDTH;
        while (lastNonSpace > 1
                && (rawOption.length() < lastNonSpace
                    || rawOption.charAt(lastNonSpace - 1) == ' ')) {
            lastNonSpace--;
        }

        // MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
        String optionXLeft = rawOption.length() >= lastNonSpace
                ? rawOption.substring(0, lastNonSpace)
                : rawOption;

        // WS-OPTION-X PIC X(02) JUST RIGHT  +
        // INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
        String optionX = String.format("%2s", optionXLeft).replace(' ', '0');
        if (optionX.length() > OPTION_FIELD_WIDTH) {
            // Defensive truncation if input.option() was longer than
            // expected (BMS guarantees 2-char field but never trust
            // upstream invariants in defensive code).
            optionX = optionX.substring(optionX.length() - OPTION_FIELD_WIDTH);
        }

        // MOVE WS-OPTION-X TO WS-OPTION (PIC 9(02))
        boolean isNumeric = !optionX.isEmpty()
                && optionX.chars().allMatch(Character::isDigit);
        int option = isNumeric ? Integer.parseInt(optionX) : -1;

        // Echo the cleansed two-character value back to the user. When
        // the input was non-numeric, preserve the original cleansed
        // value (which will have had spaces replaced by '0') so the
        // operator can see exactly what the program saw.
        String echoedOption = isNumeric
                ? String.format(Locale.ROOT, "%02d", option)
                : optionX;

        // ------------------------------------------------------------
        // Step 2: validate. COBOL test at line 130:
        //   IF WS-OPTION IS NOT NUMERIC
        //      OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT
        //      OR WS-OPTION = ZEROS
        //      MOVE 'Y' TO WS-ERR-FLG
        //      MOVE 'Please enter ...' TO WS-MESSAGE
        // ------------------------------------------------------------
        if (!isNumeric || option < 1 || option > AdminMenuTable.OPT_COUNT) {
            CoAdm01Output base = CoAdm01Output.empty().withOption(echoedOption);
            CoAdm01Output rendered = sendMenuScreen(base, INVALID_OPTION_MSG);
            return new Outcome.Render(commarea, rendered);
        }

        // ------------------------------------------------------------
        // Step 3: look up the entry and check the DUMMY prefix.
        // COBOL: IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
        //          MOVE WS-TRANID  TO CDEMO-FROM-TRANID
        //          MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        //          MOVE ZEROS      TO CDEMO-PGM-CONTEXT
        //          EXEC CICS XCTL PROGRAM(<name>) COMMAREA(<commarea>)
        //        END-IF
        //        (fall-through: coming-soon message)
        // ------------------------------------------------------------
        AdminMenuEntry entry = AdminMenuTable.ENTRIES.get(option - 1);
        String targetProgram = entry.programName();

        boolean isDummy = targetProgram.length() >= DUMMY_PROGRAM_PREFIX.length()
                && targetProgram.startsWith(DUMMY_PROGRAM_PREFIX);

        if (!isDummy) {
            // Build the commarea for the destination program: stamp the
            // calling tranid/program and reset pgm-context to ENTER
            // (so the called program will run its first-entry branch).
            CardDemoCommarea dispatchCommarea = withDispatchFlags(commarea);
            CardDemoCommarea result = programRegistry.invoke(targetProgram, dispatchCommarea);
            return new Outcome.Dispatched(result);
        }

        // The COBOL "coming soon" path: emit the literal message
        // ("This option is coming soon ..." — note the OPTION NAME
        // interpolation is commented out in the COBOL source at
        // app/cbl/COADM01C.cbl:150-151 and is NOT reproduced here per
        // AAP §0.7.1 idiom-for-idiom translation).
        CoAdm01Output comingSoonBase = CoAdm01Output.empty().withOption(echoedOption);
        CoAdm01Output comingSoonRendered = sendMenuScreen(comingSoonBase, COMING_SOON_MSG);
        return new Outcome.Render(commarea, comingSoonRendered);
    }

    // ----------------------------------------------------------------
    // Paragraph: RETURN-TO-SIGNON-SCREEN  (lines 160-167 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code RETURN-TO-SIGNON-SCREEN}
     * paragraph ({@code app/cbl/COADM01C.cbl:160-167}):
     * <pre>
     *     IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
     *         MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *     END-IF
     *     EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) END-EXEC.
     * </pre>
     * The destination is the {@code CDEMO-TO-PROGRAM} commarea field
     * unless it is blank, in which case it defaults to
     * {@link #SIGNON_PROGRAM} ({@code 'COSGN00C'}).
     *
     * @param commarea the commarea to forward to the destination
     * @return a {@link Outcome.Dispatched} carrying the commarea
     *         returned by the destination program
     */
    private Outcome returnToSignonScreen(CardDemoCommarea commarea) {
        String toProgram = commarea.cdemoGeneralInfo().toProgram();
        String target = (toProgram == null || toProgram.isBlank())
                ? SIGNON_PROGRAM
                : toProgram.trim();
        CardDemoCommarea result = programRegistry.invoke(target, commarea);
        return new Outcome.Dispatched(result);
    }

    // ----------------------------------------------------------------
    // Paragraph: WHEN OTHER (invalid AID key)  (lines 100-105 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Renders the menu with the standard invalid-key message. Equivalent
     * to the COBOL {@code WHEN OTHER} branch of the {@code EVALUATE
     * EIBAID} block (lines 100-105):
     * <pre>
     *     WHEN OTHER
     *         MOVE 'CO0000Y'           TO WS-RESP-CODE
     *         MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
     *         PERFORM SEND-MENU-SCREEN
     * </pre>
     * Echoes any previously entered option value back to the operator
     * (so the operator's keystrokes are not silently lost).
     *
     * @param commarea the commarea (carried back to the next entry)
     * @param input    the input map (for echoing the option value)
     * @return a {@link Outcome.Render} carrying the menu with the
     *         invalid-key message in the error line
     */
    private Outcome renderInvalidKey(CardDemoCommarea commarea, CoAdm01Input input) {
        CoAdm01Output base = CoAdm01Output.empty();
        String option = input.option();
        if (option != null && !option.isBlank()) {
            base = base.withOption(option);
        }
        CoAdm01Output rendered = sendMenuScreen(base, SystemMessages.INVALID_KEY_MSG);
        return new Outcome.Render(commarea, rendered);
    }

    // ----------------------------------------------------------------
    // Paragraph: SEND-MENU-SCREEN  (lines 172-184 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code SEND-MENU-SCREEN} paragraph
     * ({@code app/cbl/COADM01C.cbl:172-184}). Calls
     * {@link #populateHeaderInfo} to set the header banner and current
     * date/time fields, then {@link #buildMenuOptions} to populate the
     * 10 option-line fields from the {@link AdminMenuTable}, then
     * stamps the {@code ERRMSGO} field with the supplied message (which
     * may be empty when no message is to be shown).
     *
     * @param base       a starting output (typically
     *                   {@link CoAdm01Output#empty()} possibly with
     *                   {@code option} already echoed)
     * @param wsMessage  the value to render in {@code ERRMSGO}; may be
     *                   {@code null} or empty meaning "no message".
     *                   COBOL emits the value of {@code WS-MESSAGE}
     *                   ({@code PIC X(80)}); the receiving
     *                   {@code ERRMSGO PIC X(78)} field truncates if
     *                   the value is longer than 78 chars.
     * @return a fully populated {@link CoAdm01Output}
     */
    private CoAdm01Output sendMenuScreen(CoAdm01Output base, String wsMessage) {
        CoAdm01Output afterHeader = populateHeaderInfo(base);
        CoAdm01Output afterOptions = buildMenuOptions(afterHeader);
        String errMsg = wsMessage == null ? "" : wsMessage;
        return afterOptions.withErrMsg(padRight(errMsg, ERRMSG_WIDTH));
    }

    // ----------------------------------------------------------------
    // Paragraph: POPULATE-HEADER-INFO  (lines 202-221 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code POPULATE-HEADER-INFO} paragraph
     * ({@code app/cbl/COADM01C.cbl:202-221}). Populates the menu's
     * static and dynamic header fields:
     * <ul>
     *   <li>{@code TITLE01O} &larr; {@link ScreenTitle#TITLE_01}
     *       ({@code 'AWS Mainframe Modernization'} banner)</li>
     *   <li>{@code TITLE02O} &larr; {@link ScreenTitle#TITLE_02}
     *       ({@code 'CardDemo'} banner)</li>
     *   <li>{@code TRNNAMEO} &larr; {@link #TRANSACTION_ID}
     *       ({@code 'CA00'})</li>
     *   <li>{@code PGMNAMEO} &larr; {@link #PROGRAM_NAME}
     *       ({@code 'COADM01C'})</li>
     *   <li>{@code CURDATEO} &larr; current date formatted as
     *       {@code MM/dd/yy} (8 chars) via
     *       {@link DateConstants#formatMmDdYy(LocalDate)}</li>
     *   <li>{@code CURTIMEO} &larr; current time formatted as
     *       {@code HH:mm:ss} (8 chars) via
     *       {@link DateConstants#formatHhMmSs(LocalTime)}</li>
     * </ul>
     *
     * @param base the starting output
     * @return the output with all header fields populated
     */
    private CoAdm01Output populateHeaderInfo(CoAdm01Output base) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();

        return base
                .withTitle01(padRight(ScreenTitle.TITLE_01, TITLE_WIDTH))
                .withTitle02(padRight(ScreenTitle.TITLE_02, TITLE_WIDTH))
                .withTrnName(padRight(TRANSACTION_ID, TRAN_WIDTH))
                .withPgmName(padRight(PROGRAM_NAME, PGM_WIDTH))
                .withCurDate(padRight(DateConstants.formatMmDdYy(today), DATE_TIME_WIDTH))
                .withCurTime(padRight(DateConstants.formatHhMmSs(nowTime), DATE_TIME_WIDTH));
    }

    // ----------------------------------------------------------------
    // Paragraph: BUILD-MENU-OPTIONS  (lines 226-263 of COADM01C.cbl)
    // ----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code BUILD-MENU-OPTIONS} paragraph
     * ({@code app/cbl/COADM01C.cbl:226-263}). For each populated entry
     * of {@link AdminMenuTable#ENTRIES} (4 entries; option numbers
     * 1&ndash;4) composes a 40-character display line of the form
     * {@code "01. <option-name>"} and writes it into the corresponding
     * {@code OPTN00NO} field. The COBOL loop runs
     * {@code VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT}
     * (the bound is {@link AdminMenuTable#OPT_COUNT} = 4), so option
     * slots 5&ndash;10 in the BMS map remain at their initial blank
     * value (a faithful behavioral consequence of the COBOL loop bound).
     *
     * <p>The {@code EVALUATE WS-IDX} block in the COBOL source has
     * {@code WHEN 1} through {@code WHEN 10} arms followed by
     * {@code WHEN OTHER CONTINUE} (an explicit acknowledgement that
     * slots beyond 10 are not surfaced; see
     * {@link #setOptionLine(CoAdm01Output, int, String)}).
     *
     * @param base the starting output (with header already populated)
     * @return the output with all option-line fields populated
     */
    private CoAdm01Output buildMenuOptions(CoAdm01Output base) {
        CoAdm01Output result = base;
        for (int idx = 1; idx <= AdminMenuTable.OPT_COUNT; idx++) {
            AdminMenuEntry entry = AdminMenuTable.ENTRIES.get(idx - 1);
            String line = formatOptionLine(entry.optionNumber(), entry.optionName());
            result = setOptionLine(result, idx, line);
        }
        return result;
    }

    /**
     * Composes a single 40-character option-line display value of the
     * form {@code "NN. <option-name>"}.
     *
     * <p>The COBOL source uses:
     * <pre>
     *     STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
     *            '. '                          DELIMITED BY SIZE
     *            CDEMO-ADMIN-OPT-NAME(WS-IDX)  DELIMITED BY SIZE
     *       INTO WS-ADMIN-OPT-TXT
     * </pre>
     * which concatenates 2 chars + 2 chars + 35 chars = 39 chars and
     * moves them into the 40-char {@code WS-ADMIN-OPT-TXT}, leaving 1
     * trailing space.
     *
     * @param num  the option number (1&ndash;4 in practice)
     * @param name the option name; expected to be 35 chars after COBOL
     *             padding (per {@link AdminMenuTable.AdminMenuEntry}
     *             invariant) but the format string accommodates shorter
     *             values for robustness
     * @return a 40-character display line
     */
    private static String formatOptionLine(int num, String name) {
        // %02d. %-35s  =>  "NN. <name padded to 35>"  =  39 chars
        String formatted = String.format(Locale.ROOT, "%02d. %-35s",
                num, name == null ? "" : name);
        // Defensive: if the supplied name was longer than 35 chars
        // (which would violate the AdminMenuEntry invariant but we
        // handle it gracefully) the formatted length will exceed 39;
        // truncate to 39 to keep the final length at exactly 40.
        if (formatted.length() != OPTION_LINE_BODY_WIDTH) {
            formatted = padRight(formatted, OPTION_LINE_BODY_WIDTH);
        }
        // The MOVE WS-ADMIN-OPT-TXT TO OPTN00NO (PIC X(40)) right-pads
        // by one space, producing the final 40-character value.
        return formatted + " ";
    }

    /**
     * Translation of the {@code EVALUATE WS-IDX} block in
     * {@code BUILD-MENU-OPTIONS} ({@code app/cbl/COADM01C.cbl:234-262}).
     * Dispatches the assembled option-line value to the appropriate
     * {@code OPTN001O..OPTN010O} field. The COBOL source contains
     * {@code WHEN 1} through {@code WHEN 10} followed by
     * {@code WHEN OTHER CONTINUE} &mdash; meaning slots beyond 10 are
     * silently dropped. The Java translation uses the same 1&ndash;10
     * range with a {@code default} arm that returns the output
     * unchanged. (This is the one acceptable use of {@code default} in
     * this program: it is dispatching on a small integer, not on a
     * sealed type, so exhaustiveness is impossible to enforce at compile
     * time anyway. The default arm faithfully reproduces COBOL's
     * {@code WHEN OTHER CONTINUE}.)
     *
     * @param out  the current output
     * @param idx  the 1-based slot index (1&ndash;10 in practice; values
     *             outside this range are dropped)
     * @param line the 40-character display line
     * @return the output with the specified slot updated (or unchanged
     *         if {@code idx} is outside 1&ndash;10)
     */
    private static CoAdm01Output setOptionLine(CoAdm01Output out, int idx, String line) {
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
            default -> out; // COBOL: WHEN OTHER CONTINUE
        };
    }


    // ----------------------------------------------------------------
    // Commarea mutation helpers
    //
    // The {@link CardDemoCommarea.CdemoGeneralInfo} record enforces
    // strict fixed-length ASCII invariants on its string components
    // (e.g., fromTranId must be exactly 4 chars). Composing a new
    // {@link CardDemoCommarea} therefore requires constructing a fresh
    // {@code CdemoGeneralInfo} with all 7 fields supplied. These
    // helpers centralise that boilerplate.
    // ----------------------------------------------------------------

    /**
     * Returns a copy of {@code commarea} with
     * {@code cdemoGeneralInfo.fromProgram} replaced by
     * {@code fromProgram}. The new value must satisfy the
     * {@link CardDemoCommarea.CdemoGeneralInfo#LENGTH_FROM_PROGRAM 8-char}
     * COBOL invariant; the caller is responsible for supplying a value
     * of exactly 8 characters.
     *
     * @param commarea    the existing commarea
     * @param fromProgram the new 8-character program-id (e.g.,
     *                    {@code "COSGN00C"})
     * @return a new {@link CardDemoCommarea} with the updated field
     */
    private static CardDemoCommarea withFromProgram(
            CardDemoCommarea commarea, String fromProgram) {
        CardDemoCommarea.CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                current.fromTranId(),
                fromProgram,
                current.toTranId(),
                current.toProgram(),
                current.userId(),
                current.userType(),
                current.pgmContext());
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a copy of {@code commarea} with
     * {@code cdemoGeneralInfo.toProgram} replaced by {@code toProgram}.
     * Used to set the destination of a {@code RETURN-TO-SIGNON-SCREEN}
     * dispatch (the COBOL line
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at
     * {@code app/cbl/COADM01C.cbl:103}).
     *
     * @param commarea  the existing commarea
     * @param toProgram the new 8-character program-id
     * @return a new {@link CardDemoCommarea} with the updated field
     */
    private static CardDemoCommarea withToProgram(
            CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                current.fromTranId(),
                current.fromProgram(),
                current.toTranId(),
                toProgram,
                current.userId(),
                current.userType(),
                current.pgmContext());
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a copy of {@code commarea} with
     * {@code cdemoGeneralInfo.pgmContext} replaced by
     * {@code pgmContext}. Used to mark the program as re-entered
     * (COBOL {@code SET CDEMO-PGM-REENTER TO TRUE}).
     *
     * @param commarea   the existing commarea
     * @param pgmContext the new program-context value (typically
     *                   {@link PgmContext#REENTER} or
     *                   {@link PgmContext#ENTER})
     * @return a new {@link CardDemoCommarea} with the updated field
     */
    private static CardDemoCommarea withPgmContext(
            CardDemoCommarea commarea, PgmContext pgmContext) {
        CardDemoCommarea.CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                current.fromTranId(),
                current.fromProgram(),
                current.toTranId(),
                current.toProgram(),
                current.userId(),
                current.userType(),
                pgmContext);
        return commarea.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a copy of {@code commarea} with the three dispatch-time
     * fields updated to identify this program as the caller of the
     * forthcoming {@code XCTL} target. Equivalent to the COBOL idiom
     * at {@code app/cbl/COADM01C.cbl:139-141}:
     * <pre>
     *     MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *     MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     *     MOVE ZEROS        TO CDEMO-PGM-CONTEXT
     * </pre>
     * The {@code CDEMO-PGM-CONTEXT} is reset to {@code 0}
     * ({@link PgmContext#ENTER}) so the destination program will run
     * its own first-entry branch on its first invocation.
     *
     * @param commarea the inbound commarea
     * @return a new {@link CardDemoCommarea} ready for dispatch
     */
    private static CardDemoCommarea withDispatchFlags(CardDemoCommarea commarea) {
        CardDemoCommarea.CdemoGeneralInfo current = commarea.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated = new CardDemoCommarea.CdemoGeneralInfo(
                TRANSACTION_ID,      // CDEMO-FROM-TRANID  <-- WS-TRANID
                PROGRAM_NAME,        // CDEMO-FROM-PROGRAM <-- WS-PGMNAME
                current.toTranId(),
                current.toProgram(),
                current.userId(),
                current.userType(),
                PgmContext.ENTER);   // CDEMO-PGM-CONTEXT  <-- ZEROS
        return commarea.withCdemoGeneralInfo(updated);
    }

    // ----------------------------------------------------------------
    // String utility
    // ----------------------------------------------------------------

    /**
     * Right-pads (or, if longer, truncates) {@code s} to exactly
     * {@code width} characters. Used to render every BMS output
     * field at its declared fixed width (COBOL semantics:
     * {@code MOVE <source> TO <PIC X(width)>} pads short strings with
     * spaces and truncates long strings).
     *
     * @param s     the source string (may be {@code null}, treated as
     *              all spaces)
     * @param width the target width (must be non-negative)
     * @return a string of exactly {@code width} characters
     */
    private static String padRight(String s, int width) {
        if (s == null) {
            return " ".repeat(width);
        }
        int len = s.length();
        if (len == width) {
            return s;
        }
        if (len > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - len);
    }
}

