/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.report;

// JEP 511 (finalized in Java 25): a single declaration imports every package
// exported by the java.base module (and by the modules java.base reads). This
// brings the following standard-library types into scope without further
// import statements:
//   * java.lang.String                — type of the ten PIC X(n) record
//                                       components carrying operator input
//                                       (monthly / yearly / custom / six date
//                                       pieces / confirmation) plus the helper
//                                       methods isEmpty(), charAt(int), and
//                                       chars().{allMatch,anyMatch} used by
//                                       the COBOL-semantic predicates;
//   * java.util.Objects               — source of Objects.requireNonNull(...)
//                                       used in the compact canonical
//                                       constructor to enforce the non-null
//                                       contract on the aidKey component per
//                                       the agent prompt Phase 2;
//   * java.lang.IllegalArgumentException — raised by the ten length sanity
//                                       checks in the compact canonical
//                                       constructor when a string component
//                                       exceeds its BMS-declared PIC X(n)
//                                       width;
//   * java.lang.NullPointerException  — raised (via Objects.requireNonNull)
//                                       when a null AidKey is supplied,
//                                       carrying the parameter name "aidKey"
//                                       as its detail message.
//
// The single "import module java.base;" declaration replaces the otherwise
// implicit "import java.lang.String;" and the explicit
// "import java.util.Objects;" required to compile this file, and aligns the
// code with the AAP §0.6.7 / §0.7.3 mandate to use Module Import Declarations
// finalized in Java 25 (JEP 511).
import module java.base;

// Module-import declarations may not import application-defined types; the
// sealed AidKey hierarchy lives in carddemo-domain and must be brought in by
// a conventional single-type import. carddemo-application declares
// carddemo-domain as a direct dependency in its pom.xml, so this import is
// always resolvable.
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;

// AAP §0.7.1 traceability mandate: every translated BMS DTO must cite its
// original BMS / symbolic-copybook source via the @CobolProgram annotation
// declared in the carddemo-domain module. carddemo-application declares
// carddemo-domain as a direct dependency in its pom.xml, so the annotation
// is on the classpath and resolvable here.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code CORPT0A} / {@code CORPT00}
 * Print-Transaction-Reports screen (CICS transaction {@code CR00}, online
 * program {@code CORPT00C}).
 *
 * <p>This record is the Java translation of the input view of the symbolic
 * map structure {@code 01 CORPT0AI} declared in
 * {@code app/cpy-bms/CORPT00.CPY} (lines 17-120). It carries the values that
 * the operator submits from the 3270 terminal back to
 * {@code CoRpt00C.run(CoRpt00Input, com.blitzy.carddemo.domain.commarea.CardDemoCommarea)}
 * via the {@code EXEC CICS RECEIVE MAP} idiom in the original COBOL.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/CORPT00.bms} (mapset
 *       {@code CORPT00}, map {@code CORPT0A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES},
 *       {@code LANG=COBOL}, {@code MODE=INOUT}, {@code STORAGE=AUTO},
 *       {@code TIOAPFX=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/CORPT00.CPY} &mdash; the
 *       input group {@code 01 CORPT0AI} on lines 17-120 declares for every
 *       BMS field a {@code L}-suffixed length attribute, an {@code F}-suffixed
 *       cursor flag, an {@code A}-suffixed attribute redefinition, four
 *       filler bytes, and an {@code I}-suffixed input value. Only the ten
 *       editable {@code I}-suffixed leaves carry operator data: the three
 *       report-type radio buttons ({@code MONTHLYI} / {@code YEARLYI} /
 *       {@code CUSTOMI}), the six custom-range date pieces ({@code SDTMMI} /
 *       {@code SDTDDI} / {@code SDTYYYYI} / {@code EDTMMI} / {@code EDTDDI} /
 *       {@code EDTYYYYI}), and the one-character confirmation flag
 *       ({@code CONFIRMI}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/CORPT00C.cbl}
 *       ({@code PROGRAM-ID CORPT00C}, {@code WS-TRANID 'CR00'}). The
 *       {@code EVALUATE EIBAID} block at lines 184-196 enumerates the AID
 *       keys handled by the program: {@code DFHENTER} (process the
 *       selection), {@code DFHPF3} (return to the previous program, defaulting
 *       to {@code COMEN01C}), and {@code WHEN OTHER} (display the
 *       "Invalid key pressed..." error).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per Agent Action Plan &sect;0.3.5 and &sect;0.4.1, BMS maps are translated
 * into <em>entry-contract DTO records</em> on the corresponding application
 * class. This record carries the field values delivered by
 * {@code EXEC CICS RECEIVE MAP} to {@code CoRpt00C}. There is no web framework,
 * no Spring binding, and no Jakarta Bean Validation; the record is a plain
 * Java carrier built around finalized Java 25 language features only
 * (records, JEP 511 module imports, JEP 513 flexible constructor bodies, and
 * the sealed {@link AidKey} hierarchy from {@code carddemo-domain}).
 *
 * <h2>Header-row fields belong on the output side, not here</h2>
 * <p>The six header-row leaves declared in the symbolic copybook
 * ({@code TRNNAMEI} / {@code TITLE01I} / {@code CURDATEI} / {@code PGMNAMEI} /
 * {@code TITLE02I} / {@code CURTIMEI}) and the error-message leaf
 * ({@code ERRMSGI}) are NOT carried on this input record. They are populated
 * by the program before SEND-MAP and arrive back as SPACES on RECEIVE-MAP;
 * the controller ignores their echoed value. They live on the sibling
 * {@code CoRpt00Output} record instead.
 *
 * <h2>Field-for-field translation</h2>
 * <table>
 * <caption>CORPT0AI to record component mapping</caption>
 *   <thead>
 *     <tr><th>BMS field (CORPT0AI)</th><th>PIC</th><th>Record component</th>
 *         <th>Notes</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code MONTHLYI}</td><td>{@code X(1)}</td>
 *         <td>{@link #monthly()}</td>
 *         <td>Radio: blank = not selected; any non-blank = selected. The COBOL
 *             test is {@code MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES}.
 *             See {@link #isMonthlySelected()}.</td></tr>
 *     <tr><td>{@code YEARLYI}</td><td>{@code X(1)}</td>
 *         <td>{@link #yearly()}</td>
 *         <td>Same convention as MONTHLYI; see {@link #isYearlySelected()}.</td></tr>
 *     <tr><td>{@code CUSTOMI}</td><td>{@code X(1)}</td>
 *         <td>{@link #custom()}</td>
 *         <td>Same convention as MONTHLYI; see {@link #isCustomSelected()}.</td></tr>
 *     <tr><td>{@code SDTMMI}</td><td>{@code X(2)}</td>
 *         <td>{@link #startMonth()}</td>
 *         <td>Custom-range start date: month, expected {@code "01".."12"} or
 *             a numeric string with leading SPACES.</td></tr>
 *     <tr><td>{@code SDTDDI}</td><td>{@code X(2)}</td>
 *         <td>{@link #startDay()}</td>
 *         <td>Custom-range start date: day.</td></tr>
 *     <tr><td>{@code SDTYYYYI}</td><td>{@code X(4)}</td>
 *         <td>{@link #startYear()}</td>
 *         <td>Custom-range start date: 4-digit year.</td></tr>
 *     <tr><td>{@code EDTMMI}</td><td>{@code X(2)}</td>
 *         <td>{@link #endMonth()}</td>
 *         <td>Custom-range end date: month.</td></tr>
 *     <tr><td>{@code EDTDDI}</td><td>{@code X(2)}</td>
 *         <td>{@link #endDay()}</td>
 *         <td>Custom-range end date: day.</td></tr>
 *     <tr><td>{@code EDTYYYYI}</td><td>{@code X(4)}</td>
 *         <td>{@link #endYear()}</td>
 *         <td>Custom-range end date: 4-digit year.</td></tr>
 *     <tr><td>{@code CONFIRMI}</td><td>{@code X(1)}</td>
 *         <td>{@link #confirmation()}</td>
 *         <td>{@code 'Y'}/{@code 'y'} to submit the job, {@code 'N'}/{@code 'n'}
 *             to reset the screen; any other value is rejected by the
 *             controller. See {@link #isConfirmYes()},
 *             {@link #isConfirmNo()}, {@link #isConfirmBlank()}.</td></tr>
 *     <tr><td>(synthetic)</td><td>&mdash;</td>
 *         <td>{@link #aidKey()}</td>
 *         <td>3270 Attention Identifier captured from {@code EIBAID} at
 *             RECEIVE-MAP time; typed as the sealed {@link AidKey} sealed
 *             interface from {@code com.blitzy.carddemo.domain.text.CcWorkAreas}
 *             with 16 record permits (Enter, Clear, Pa1, Pa2,
 *             PfKey01..PfKey12). The controller's pattern-matching switch
 *             over this component is compiler-enforced exhaustive per AAP
 *             &sect;0.6.10.</td></tr>
 *   </tbody>
 * </table>
 *
 * <p><b>Why {@link String}, not {@code char}, for one-byte fields?</b>
 * COBOL {@code PIC X(n)} fields preserve trailing SPACES and may contain
 * LOW-VALUES ({@code \u005Cu0000}). Storing them as {@link String} matches
 * the convention adopted by every sibling BMS input DTO under
 * {@code application/account}, {@code application/billpay},
 * {@code application/card}, {@code application/menu}, and
 * {@code application/transaction} (e.g.,
 * {@code CoActVwInput}, {@code CoBil00Input}, {@code CoTrn00Input},
 * {@code CoTrn02Input}). Using {@code char} would lose the distinction
 * between an empty field and a SPACE-filled field, which the COBOL paragraph
 * tests via {@code NOT = SPACES AND LOW-VALUES}.
 *
 * <h2>Null and emptiness semantics</h2>
 * <p>The compact (canonical) constructor coerces every {@code null}
 * {@link String} component to the empty {@link String} {@code ""}, matching
 * the COBOL RECEIVE-MAP idiom where unfilled {@code PIC X(n)} fields are
 * SPACES, never undefined. This is a pre-canonical-assignment transformation
 * performed in the body of the compact constructor (JEP 513 Flexible
 * Constructor Bodies, finalized in Java 25).
 *
 * <p>The {@link AidKey} component is required: any RECEIVE-MAP-driven
 * invocation MUST supply a decoded AID key. A {@code null} {@link AidKey} is
 * rejected with a {@link NullPointerException} carrying the message
 * {@code "aidKey"} (via {@link java.util.Objects#requireNonNull(Object,
 * String)}). The {@link #empty()} factory supplies a sentinel
 * {@code new AidKey.Enter()} for first-entry navigation when there is no
 * actual operator keypress.
 *
 * <h2>{@code PIC X(n)} length validation &mdash; throw, not clamp</h2>
 * <p>The compact constructor performs defense-in-depth length checks on
 * every {@link String} component, throwing {@link IllegalArgumentException}
 * when a value exceeds the BMS-declared PIC X(n) width. This rejects
 * programming errors (e.g., a controller writing past the field length)
 * rather than silently truncating; silent truncation could corrupt the
 * SEND-MAP and break golden-record byte-for-byte parity (AAP &sect;0.6.5).
 * Values shorter than the declared width are accepted unchanged: shorter
 * values represent an unfilled field, which arrives from CICS as SPACES
 * (and is therefore equivalent to the empty {@link String}).
 *
 * <h2>Immutability and concurrency</h2>
 * <p>Because this is a {@code record}, all eleven components are
 * {@code final} and accessors are auto-generated; there are no setters and
 * no mutable internal state. The resulting instance is safe to publish
 * across virtual threads (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Non-goals (faithful to AAP &sect;0.7.4 "explicitly forbidden")</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components, accessors, and the empty
 *       factory are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in
 *       the compact constructor per AAP &sect;0.6.3 / JEP 513.</li>
 *   <li>No nested {@code AidKey} type &mdash; the AID key is the sealed
 *       {@link AidKey} hierarchy from
 *       {@code com.blitzy.carddemo.domain.text.CcWorkAreas}, which provides
 *       compiler-enforced exhaustive {@code switch} dispatch over the 16
 *       AID-key permits per AAP &sect;0.6.10.</li>
 *   <li>No {@code java.util.Date} / {@code java.util.Calendar} /
 *       {@code java.text.SimpleDateFormat} &mdash; this record carries raw
 *       date components as {@link String}; parsing into
 *       {@code java.time.LocalDate} happens downstream in the controller
 *       via {@code DateValidator} (which delegates to
 *       {@code LocalDate.parse} with a STRICT resolver style).</li>
 *   <li>No {@code java.io.File} &mdash; no file I/O is performed in this
 *       DTO.</li>
 *   <li>No {@code double} or {@code float} &mdash; no monetary values are
 *       carried.</li>
 *   <li>No {@code ThreadLocal} &mdash; the record is thread-safe by
 *       immutability.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No preview features &mdash; the file compiles under
 *       {@code --release 25} without {@code --enable-preview}.</li>
 * </ul>
 *
 * @param monthly      MONTHLYI:  blank = not selected; any non-blank = selected
 * @param yearly       YEARLYI:   blank = not selected; any non-blank = selected
 * @param custom       CUSTOMI:   blank = not selected; any non-blank = selected
 * @param startMonth   SDTMMI:    start-date month  (PIC X(2))
 * @param startDay     SDTDDI:    start-date day    (PIC X(2))
 * @param startYear    SDTYYYYI:  start-date year   (PIC X(4))
 * @param endMonth     EDTMMI:    end-date month    (PIC X(2))
 * @param endDay       EDTDDI:    end-date day      (PIC X(2))
 * @param endYear      EDTYYYYI:  end-date year     (PIC X(4))
 * @param confirmation CONFIRMI:  Y/y to submit, N/n to reset, other = invalid
 * @param aidKey       the AID key pressed by the operator (one of 16 permits
 *                     of the sealed {@link AidKey} hierarchy); must not be
 *                     {@code null}
 * @see CoRpt00C
 * @see CoRpt00Output
 * @see com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey
 */
@CobolProgram(
        value = "CORPT00",
        sourcePath = "app/bms/CORPT00.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 CORPT0AI "
                + "in app/cpy-bms/CORPT00.CPY (lines 17-120). Driven by online program "
                + "app/cbl/CORPT00C.cbl (transaction CR00 — Print Transaction Reports). "
                + "AidKey is synthesized from EIBAID and carried as part of the entry "
                + "contract per AAP §0.6.10."
)
public record CoRpt00Input(
        String monthly,        // MONTHLYI  PIC X(1)
        String yearly,         // YEARLYI   PIC X(1)
        String custom,         // CUSTOMI   PIC X(1)
        String startMonth,     // SDTMMI    PIC X(2)
        String startDay,       // SDTDDI    PIC X(2)
        String startYear,      // SDTYYYYI  PIC X(4)
        String endMonth,       // EDTMMI    PIC X(2)
        String endDay,         // EDTDDI    PIC X(2)
        String endYear,        // EDTYYYYI  PIC X(4)
        String confirmation,   // CONFIRMI  PIC X(1)
        AidKey aidKey          // synthetic — captures EIBAID via the sealed type
) {

    // =================================================================
    // BMS PIC X(n) field-width constants. These match the symbolic-map
    // copybook lines 17-120 of app/cpy-bms/CORPT00.CPY exactly. They are
    // referenced from both the compact constructor's length checks and
    // the @param-line documentation above.
    // =================================================================

    /** {@code MONTHLYI PIC X(1)} width. */
    private static final int MONTHLY_LENGTH = 1;
    /** {@code YEARLYI PIC X(1)} width. */
    private static final int YEARLY_LENGTH = 1;
    /** {@code CUSTOMI PIC X(1)} width. */
    private static final int CUSTOM_LENGTH = 1;
    /** {@code SDTMMI PIC X(2)} width. */
    private static final int START_MONTH_LENGTH = 2;
    /** {@code SDTDDI PIC X(2)} width. */
    private static final int START_DAY_LENGTH = 2;
    /** {@code SDTYYYYI PIC X(4)} width. */
    private static final int START_YEAR_LENGTH = 4;
    /** {@code EDTMMI PIC X(2)} width. */
    private static final int END_MONTH_LENGTH = 2;
    /** {@code EDTDDI PIC X(2)} width. */
    private static final int END_DAY_LENGTH = 2;
    /** {@code EDTYYYYI PIC X(4)} width. */
    private static final int END_YEAR_LENGTH = 4;
    /** {@code CONFIRMI PIC X(1)} width. */
    private static final int CONFIRMATION_LENGTH = 1;

    /**
     * Compact (canonical) constructor enforcing COBOL "SPACES by default"
     * semantics on every {@link String} component and rejecting any
     * over-length value with an {@link IllegalArgumentException}.
     *
     * <p>This constructor exploits JEP 513 (Flexible Constructor Bodies,
     * finalized in Java 25): the normalization and validation logic runs
     * <em>before</em> the implicit canonical field assignments, so a
     * subsequent record-instance reflection of any component will return
     * the post-normalization value (never {@code null}).
     *
     * <h4>Normalization rules (run in order)</h4>
     * <ol>
     *   <li>Each of the ten {@link String} components: {@code null} is
     *       replaced with the empty {@link String} {@code ""}.</li>
     *   <li>The {@link AidKey} component: {@code null} is rejected via
     *       {@link java.util.Objects#requireNonNull(Object, String)} with
     *       the parameter name {@code "aidKey"}.</li>
     *   <li>Each of the ten {@link String} components: length is verified
     *       against the BMS-declared {@code PIC X(n)} width; over-length
     *       values raise {@link IllegalArgumentException}.</li>
     * </ol>
     *
     * @throws NullPointerException     if {@code aidKey} is {@code null}
     * @throws IllegalArgumentException if any {@link String} component
     *                                  exceeds its declared {@code PIC X(n)}
     *                                  width
     */
    public CoRpt00Input {
        // ---- Step 1: null-to-empty coercion (JEP 513 pre-binding) ----
        // COBOL distinguishes between LOW-VALUES, SPACES, and a non-empty
        // value, but the COBOL CORPT00C source only ever tests
        //   "NOT = SPACES AND LOW-VALUES"
        // (i.e. "is there any non-blank, non-low-value character?"). Treating
        // null as blank therefore yields the same observable outcome as the
        // COBOL idiom and matches the sibling DTO normalization pattern.
        monthly      = (monthly      == null) ? "" : monthly;
        yearly       = (yearly       == null) ? "" : yearly;
        custom       = (custom       == null) ? "" : custom;
        startMonth   = (startMonth   == null) ? "" : startMonth;
        startDay     = (startDay     == null) ? "" : startDay;
        startYear    = (startYear    == null) ? "" : startYear;
        endMonth     = (endMonth     == null) ? "" : endMonth;
        endDay       = (endDay       == null) ? "" : endDay;
        endYear      = (endYear      == null) ? "" : endYear;
        confirmation = (confirmation == null) ? "" : confirmation;

        // ---- Step 2: non-null AID key (RECEIVE-MAP guarantees a decoded
        //              AID; the controller MUST pass one). For initial
        //              navigation when there is no operator keypress yet,
        //              callers SHOULD use the empty() factory which
        //              supplies a sentinel new AidKey.Enter(). ----
        Objects.requireNonNull(aidKey, "aidKey");

        // ---- Step 3: PIC X(n) length sanity checks. Throw rather than
        //              clamp: silent truncation could corrupt SEND-MAP and
        //              break golden-record byte-for-byte parity (AAP §0.6.5).
        //              Each message names the BMS field and quotes the
        //              actual length to make controller bugs easy to find. ----
        if (monthly.length() > MONTHLY_LENGTH) {
            throw new IllegalArgumentException(
                    "monthly must be at most " + MONTHLY_LENGTH + " char, got "
                            + monthly.length());
        }
        if (yearly.length() > YEARLY_LENGTH) {
            throw new IllegalArgumentException(
                    "yearly must be at most " + YEARLY_LENGTH + " char, got "
                            + yearly.length());
        }
        if (custom.length() > CUSTOM_LENGTH) {
            throw new IllegalArgumentException(
                    "custom must be at most " + CUSTOM_LENGTH + " char, got "
                            + custom.length());
        }
        if (startMonth.length() > START_MONTH_LENGTH) {
            throw new IllegalArgumentException(
                    "startMonth must be at most " + START_MONTH_LENGTH + " chars, got "
                            + startMonth.length());
        }
        if (startDay.length() > START_DAY_LENGTH) {
            throw new IllegalArgumentException(
                    "startDay must be at most " + START_DAY_LENGTH + " chars, got "
                            + startDay.length());
        }
        if (startYear.length() > START_YEAR_LENGTH) {
            throw new IllegalArgumentException(
                    "startYear must be at most " + START_YEAR_LENGTH + " chars, got "
                            + startYear.length());
        }
        if (endMonth.length() > END_MONTH_LENGTH) {
            throw new IllegalArgumentException(
                    "endMonth must be at most " + END_MONTH_LENGTH + " chars, got "
                            + endMonth.length());
        }
        if (endDay.length() > END_DAY_LENGTH) {
            throw new IllegalArgumentException(
                    "endDay must be at most " + END_DAY_LENGTH + " chars, got "
                            + endDay.length());
        }
        if (endYear.length() > END_YEAR_LENGTH) {
            throw new IllegalArgumentException(
                    "endYear must be at most " + END_YEAR_LENGTH + " chars, got "
                            + endYear.length());
        }
        if (confirmation.length() > CONFIRMATION_LENGTH) {
            throw new IllegalArgumentException(
                    "confirmation must be at most " + CONFIRMATION_LENGTH + " char, got "
                            + confirmation.length());
        }
    }

    // =================================================================
    // Static factory
    // =================================================================

    /**
     * Returns a fully-blank input: every {@link String} component is the
     * empty {@link String} {@code ""} and the {@link #aidKey()} is a fresh
     * {@code new AidKey.Enter()} sentinel.
     *
     * <p>This is the Java equivalent of the COBOL idiom
     * {@code MOVE LOW-VALUES TO CORPT0AI} performed by {@code CORPT00C} on
     * first entry (when {@code EIBCALEN = 0}, signalling no prior screen
     * state). It is also the canonical synthetic input for invocations
     * routed through {@code com.blitzy.carddemo.application.ProgramRegistry}
     * when the calling program has no AID-key context to pass.
     *
     * <p>The returned record satisfies every invariant of the compact
     * canonical constructor and carries no payload; the controller's
     * first-entry branch ({@code CDEMO-PGM-CONTEXT != REENTER}) short-circuits
     * past any AID-key dispatch, so the {@link AidKey.Enter} value is a
     * harmless placeholder.
     *
     * @return a fresh empty {@code CoRpt00Input} (never {@code null})
     */
    public static CoRpt00Input empty() {
        return new CoRpt00Input(
                "",   // monthly
                "",   // yearly
                "",   // custom
                "",   // startMonth
                "",   // startDay
                "",   // startYear
                "",   // endMonth
                "",   // endDay
                "",   // endYear
                "",   // confirmation
                new AidKey.Enter());
    }

    // =================================================================
    // Convenience helper methods — match the COBOL EVALUATE-TRUE chain
    // and the literal 'Y' / 'y' / 'N' / 'n' tests at PROCESS-ENTER-KEY
    // (lines 207-453 of app/cbl/CORPT00C.cbl). These reduce duplication
    // in CoRpt00C and make the controller's call sites read closer to
    // the original COBOL.
    // =================================================================

    /**
     * Returns {@code true} if the Monthly radio button is selected.
     *
     * <p>Matches the COBOL test
     * {@code MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES}: any
     * character with a code point other than {@code ' '} (0x20) or
     * {@code '\u005Cu0000'} counts as selected. An empty string is treated
     * as "not selected".
     *
     * @return {@code true} if the operator marked the Monthly radio
     */
    public boolean isMonthlySelected() {
        return isFlagOn(monthly);
    }

    /**
     * Returns {@code true} if the Yearly radio button is selected.
     *
     * <p>Matches the COBOL test
     * {@code YEARLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES} (same
     * semantics as {@link #isMonthlySelected()}).
     *
     * @return {@code true} if the operator marked the Yearly radio
     */
    public boolean isYearlySelected() {
        return isFlagOn(yearly);
    }

    /**
     * Returns {@code true} if the Custom-range radio button is selected.
     *
     * <p>Matches the COBOL test
     * {@code CUSTOMI OF CORPT0AI NOT = SPACES AND LOW-VALUES} (same
     * semantics as {@link #isMonthlySelected()}). When this returns
     * {@code true} the controller additionally validates the six
     * {@code SDT.../EDT...} date pieces.
     *
     * @return {@code true} if the operator marked the Custom radio
     */
    public boolean isCustomSelected() {
        return isFlagOn(custom);
    }

    /**
     * Returns {@code true} if {@link #confirmation()} is {@code 'Y'} or
     * {@code 'y'} (the only two values that the COBOL paragraph
     * {@code SUBMIT-JOB-TO-INTRDR} treats as "confirmed").
     *
     * <p>Matches the COBOL test
     * {@code CONFIRMI OF CORPT0AI = 'Y' OR 'y'}. The case-insensitivity is
     * limited to ASCII Y/y by design: COBOL treats the field byte-for-byte
     * and accepts only those two literal values.
     *
     * @return {@code true} if confirmation is the ASCII letter
     *         {@code 'Y'} or {@code 'y'}; {@code false} otherwise
     *         (including when the field is blank or holds any other
     *         character)
     */
    public boolean isConfirmYes() {
        if (confirmation.isEmpty()) {
            return false;
        }
        char c = confirmation.charAt(0);
        return c == 'Y' || c == 'y';
    }

    /**
     * Returns {@code true} if {@link #confirmation()} is {@code 'N'} or
     * {@code 'n'} (the only two values that the COBOL paragraph
     * {@code SUBMIT-JOB-TO-INTRDR} treats as "cancel and reset").
     *
     * <p>Matches the COBOL test
     * {@code CONFIRMI OF CORPT0AI = 'N' OR 'n'}.
     *
     * @return {@code true} if confirmation is the ASCII letter
     *         {@code 'N'} or {@code 'n'}; {@code false} otherwise
     */
    public boolean isConfirmNo() {
        if (confirmation.isEmpty()) {
            return false;
        }
        char c = confirmation.charAt(0);
        return c == 'N' || c == 'n';
    }

    /**
     * Returns {@code true} if the {@link #confirmation()} field is blank or
     * filled with LOW-VALUES.
     *
     * <p>Matches the COBOL test
     * {@code CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES}. Both an empty
     * {@link String} and a {@link String} consisting entirely of {@code ' '}
     * (0x20) or {@code '\u005Cu0000'} characters count as blank.
     *
     * <p>The controller's PROCESS-ENTER-KEY paragraph uses this predicate
     * to short-circuit the {@code SUBMIT-JOB-TO-INTRDR} chain and re-display
     * the screen with the "Please confirm..." prompt when the operator has
     * not yet typed Y or N.
     *
     * @return {@code true} if confirmation is empty, all spaces, or all
     *         LOW-VALUES; {@code false} if any character is non-blank
     */
    public boolean isConfirmBlank() {
        if (confirmation.isEmpty()) {
            return true;
        }
        return confirmation.chars().allMatch(c -> c == ' ' || c == '\u0000');
    }

    // =================================================================
    // Private static helper — implements the COBOL test
    //   "field-name NOT = SPACES AND LOW-VALUES"
    // ("is there any character that is not SPACE and not LOW-VALUE?").
    // =================================================================

    /**
     * Returns {@code true} if {@code s} contains at least one character
     * that is neither the ASCII SPACE ({@code 0x20}) nor the
     * NULL / LOW-VALUE ({@code '\u005Cu0000'}).
     *
     * <p>An empty string yields {@code false} (no characters at all means
     * no "non-blank" characters). This precisely mirrors the COBOL idiom
     * {@code field-name NOT = SPACES AND LOW-VALUES} on a single-character
     * PIC X field, and extends the same semantics to multi-character
     * fields by short-circuiting on the first non-blank character.
     *
     * @param s the string to inspect; must not be {@code null} (the
     *          compact canonical constructor guarantees that none of the
     *          {@link String} components are {@code null} at this point)
     * @return {@code true} if any character is non-blank, {@code false}
     *         otherwise
     */
    private static boolean isFlagOn(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.chars().anyMatch(c -> c != ' ' && c != '\u0000');
    }
}
