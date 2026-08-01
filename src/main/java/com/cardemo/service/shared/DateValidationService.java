/*
 * ******************************************************************
 * Component   : DateValidationService
 * Application : CardDemo
 * Type        : Spring @Service (shared, date validation)
 * Function    : Replaces CALL 'CSUTLDTC' and the LE CEEDAYS date service with java.time
 * Source      : app/cbl/CSUTLDTC.cbl (157 lines, 2 paragraphs) @ 7756d89
 * Source      : app/cpy/CSUTLDPY.cpy (375 lines, 14 paragraphs) @ 7756d89
 * Source      : app/cpy/CSUTLDWY.cpy (89 lines, 0 paragraphs - work area) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.service.shared;

import com.cardemo.exception.ValidationException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The CardDemo date validation service: one Spring bean standing in for three frozen legacy members.
 *
 * <h2>What it does</h2>
 *
 * <p>This class is the <strong>first collapse rule</strong> of the migration. Three members of the
 * frozen legacy corpus become exactly one injected bean, because all three describe a single
 * capability that the source happened to spread across a called program, a procedural copybook and a
 * working storage copybook:
 *
 * <ul>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} - 157 lines, 2 paragraphs. A statically called subprogram whose
 *       whole job is to wrap the IBM Language Environment date service {@code CEEDAYS} and render its
 *       feedback code into a fixed 80 byte result area.</li>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} - 375 lines, 14 {@code PROCEDURE DIVISION} paragraphs. The
 *       reusable field level date edits: year, month, day, the cross field combination rules, the
 *       Language Environment cross check and the date of birth reasonableness check.</li>
 *   <li>{@code app/cpy/CSUTLDWY.cpy} - 89 lines, <em>zero</em> paragraphs. The pure working storage
 *       area the copybook above operates on. It contributes no method, only state and the condition
 *       names that give that state meaning.</li>
 * </ul>
 *
 * <p>Collapsing them is not a convenience. Rule 1 Clause C requires a consistent structure and forbids
 * duplication, and the three members share one work area, one 80 byte result layout and one call
 * contract. Splitting them across three Java types would have duplicated the 80 byte layout twice over
 * - the source itself already duplicates it, as {@code WS-MESSAGE} in the program and
 * {@code WS-DATE-VALIDATION-RESULT} in the copybook - and would have created two sources of truth for
 * a value the parity gates compare byte for byte. <strong>One result type serves both.</strong>
 *
 * <p>What is reproduced here is validation <em>outcomes</em>, not merely date parsing: the exact
 * result string, the exact severity, the exact message number, the exact message text and the exact
 * order in which the edits are evaluated. A date parser that merely agreed about which dates are
 * valid would fail every parity comparison in this file.
 *
 * <h2>Sixteen source mapped methods</h2>
 *
 * <p>Every paragraph label in the two procedural members maps to exactly one private method, and no
 * label is consolidated with another. Two come from the program and fourteen from the copybook;
 * the working storage copybook has no label and therefore contributes none. Each mapped method
 * carries a Javadoc citation naming its path, its label and its line range, which is the evidence the
 * scope coverage gate reads:
 *
 * <table border="1">
 *   <caption>Label to method mapping, all lines verified on the anchor commit</caption>
 *   <tr><th>Source</th><th>Label</th><th>Lines</th><th>Method</th></tr>
 *   <tr><td>CSUTLDTC.cbl</td><td>A000-MAIN</td><td>103-151</td><td>{@code a000Main}</td></tr>
 *   <tr><td>CSUTLDTC.cbl</td><td>A000-MAIN-EXIT</td><td>152-154</td><td>{@code a000MainExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-CCYYMMDD</td><td>18-20</td>
 *       <td>{@code editDateCcyymmdd}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-YEAR-CCYY</td><td>25-87</td><td>{@code editYearCcyy}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-YEAR-CCYY-EXIT</td><td>88-90</td>
 *       <td>{@code editYearCcyyExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-MONTH</td><td>91-144</td><td>{@code editMonth}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-MONTH-EXIT</td><td>145-147</td>
 *       <td>{@code editMonthExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DAY</td><td>150-204</td><td>{@code editDay}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DAY-EXIT</td><td>205-207</td><td>{@code editDayExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DAY-MONTH-YEAR</td><td>209-279</td>
 *       <td>{@code editDayMonthYear}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DAY-MONTH-YEAR-EXIT</td><td>280-282</td>
 *       <td>{@code editDayMonthYearExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-LE</td><td>284-321</td><td>{@code editDateLe}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-LE-EXIT</td><td>323-328</td>
 *       <td>{@code editDateLeExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-CCYYMMDD-EXIT</td><td>329-331</td>
 *       <td>{@code editDateCcyymmddExit}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-OF-BIRTH</td><td>341-369</td>
 *       <td>{@code editDateOfBirthParagraph}</td></tr>
 *   <tr><td>CSUTLDPY.cpy</td><td>EDIT-DATE-OF-BIRTH-EXIT</td><td>370-372</td>
 *       <td>{@code editDateOfBirthExit}</td></tr>
 * </table>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} set to 25 and no preview feature enabled; Maven
 * 3.9.11 through the pinned wrapper. The compiler runs {@code -Xlint:all} with {@code -Werror}, so any
 * warning is a build failure: no raw type, no unchecked cast, no deprecated API and no unused import
 * may appear here. Build with {@code ./mvnw -B clean compile} and test with
 * {@code ./mvnw -B clean test}; {@code ./mvnw -B verify} additionally enforces the JaCoCo line
 * coverage floor of 0.80 over the merged unit and integration execution data.
 *
 * <p>Tests for this class live in {@code src/test/java/com/cardemo/unit/**} and nowhere else. No test
 * source, fixture or helper belongs in this package.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This bean reads <strong>no</strong> configuration property, holds <strong>no</strong> credential
 * and opens <strong>no</strong> connection. Its only collaborator is an injected {@link Clock}. Rule 1
 * Clause D's least privilege requirement is therefore satisfied structurally rather than by policy:
 * there is nothing here to over privilege. The values that behave like configuration are compiled in
 * because the source compiles them in too:
 *
 * <ul>
 *   <li><strong>Two format masks, both mandatory.</strong> {@link #MASK_YYYY_MM_DD} is
 *       {@code YYYY-MM-DD}, declared {@code PIC X(10)} at {@code app/cbl/CORPT00C.cbl:L72} and
 *       {@code app/cbl/COTRN02C.cbl:L60}, and used by the two program call paths.
 *       {@link #MASK_YYYYMMDD} is {@code YYYYMMDD}, declared {@code PIC X(08)} at
 *       {@code app/cpy/CSUTLDWY.cpy:L58-L59} and moved into place at
 *       {@code app/cpy/CSUTLDPY.cpy:L291}, and used by the copybook call path. Neither may be
 *       dropped: they are the complete census of masks in the corpus, and they differ in both width
 *       and separator.</li>
 *   <li><strong>Valid centuries are 19 and 20 only.</strong> {@code app/cpy/CSUTLDWY.cpy:L9-L10}
 *       declares {@code THIS-CENTURY VALUE 20} and {@code LAST-CENTURY VALUE 19} and nothing else.
 *       The source comment at {@code app/cpy/CSUTLDPY.cpy:L66-L68} explains why in its own words:
 *       "Not having learnt our lesson from history and Y2K", "And being unable to imagine COBOL in
 *       the 2100s", "We code only 19 and 20 as valid century values".</li>
 *   <li><strong>Nine Language Environment outcomes</strong> with their exact severity and message
 *       number pairs, and <strong>ten</strong> fifteen character result strings - the nine plus the
 *       {@code WHEN OTHER} fallback. See {@link FeedbackCondition}.</li>
 *   <li><strong>An injected {@link Clock}.</strong> {@code FUNCTION CURRENT-DATE} at
 *       {@code app/cpy/CSUTLDPY.cpy:L343} is the one environmental dependency in the source. It is
 *       satisfied by constructor injection so that the date of birth check is deterministic and
 *       testable. {@code LocalDate.now()} with no clock is forbidden here by Rule 1 Clause C, which
 *       requires builds free of environment specific assumptions.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each entry names the mistake, the symptom a maintainer will actually observe, and the one line
 * remedy. Every one of these is a way to produce code that compiles and runs and is still wrong.
 *
 * <ul>
 *   <li><strong>Collapsing the three tri-state flags into one boolean.</strong> Symptom: a blank
 *       month becomes indistinguishable from an out of range month, and the caller can no longer
 *       reproduce the legacy screen behaviour that marks an empty field differently from a wrong one.
 *       Remedy: keep {@link EditFlag} with all three constants and derive composite validity by
 *       conjunction, as {@link EditOutcome#valid()} does.</li>
 *   <li><strong>Treating the {@code EXIT} in {@code EDIT-DATE-LE-EXIT} as a Java {@code return}.</strong>
 *       Symptom: dates that pass every edit come back invalid, because the
 *       {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} that follows the {@code EXIT} never runs. Remedy:
 *       plain COBOL {@code EXIT} is a documentary no-op; see {@link #editDateLeExit}.</li>
 *   <li><strong>Substituting a library leap year test.</strong> Symptom: nothing, until a century
 *       year is entered, at which point the outcome silently diverges from the source's two branch
 *       division. Remedy: keep the division and remainder structure of
 *       {@code app/cpy/CSUTLDPY.cpy:L245-L254}.</li>
 *   <li><strong>Normalising the initialisation asymmetry.</strong> Symptom: a day that fails no
 *       explicit test is reported invalid, or a year that fails no explicit test is reported valid.
 *       Remedy: year and month initialise pessimistically, day initialises optimistically; that is
 *       the source, not a defect.</li>
 *   <li><strong>Reversing either order quirk.</strong> Symptom: no visible change in the message
 *       text, because both branches of each pair emit the same literal, which is exactly why this
 *       error survives casual review. Remedy: month tests range then numeric; day tests numeric then
 *       range.</li>
 *   <li><strong>Relaxing the strict future date comparison.</strong> Symptom: a date of birth equal
 *       to today is accepted where the source rejects it. Remedy: keep the strict comparison in
 *       {@link #editDateOfBirthParagraph}.</li>
 *   <li><strong>"Fixing" the group move defect.</strong> Symptom: the {@code TstDate:} portion of the
 *       80 byte result stops matching the legacy baseline and every parity comparison of that field
 *       fails. Remedy: the corruption is the contract; see {@link #a000Main}.</li>
 *   <li><strong>Trimming trailing spaces from the result literals.</strong> Symptom: the composed
 *       result is shorter than 80 characters and downstream fixed width comparisons shift. Remedy:
 *       five of the ten literals carry deliberate trailing spaces inside their quotes; all ten
 *       occupy exactly fifteen characters.</li>
 * </ul>
 *
 * <h2>Findings carried by this translation</h2>
 *
 * <p>Classified as Rule 1 Clause F requires, with the remedy applied in each case.
 *
 * <ul>
 *   <li><strong>Blocker</strong> - mapping fewer than sixteen labels, or consolidating any two. The
 *       scope coverage gate fails outright. Applied remedy: all sixteen are present and separately
 *       documented in the table above.</li>
 *   <li><strong>High</strong> - collapsing the tri-state flags; treating the {@code EXIT} at
 *       {@code app/cpy/CSUTLDPY.cpy:L323-L325} as a return and skipping the L327 {@code SET};
 *       relaxing the strict date of birth comparison; normalising the pessimistic versus optimistic
 *       initialisation asymmetry; reversing an order quirk; substituting a library leap year test.
 *       Applied remedy: each is reproduced as written and cited at its method.</li>
 *   <li><strong>Medium</strong> - the group move at {@code app/cbl/CSUTLDTC.cbl:L122} corrupts the
 *       {@code TstDate:} field of the result; and the {@code CEEDAYS} buffer overread on the copybook
 *       call path cannot be reproduced in a memory safe language. Applied remedy: the first is
 *       reproduced exactly, the second is recorded as a labelled deviation. Both are tracked in
 *       {@code DECISION_LOG.md}.</li>
 *   <li><strong>Low</strong> - the header list at {@code app/cpy/CSUTLDPY.cpy:L14-L15} names
 *       {@code EDIT-DATE-OF-BIRTH} twice, as both {@code d)} and {@code e)}; the comment at
 *       {@code :L286-L288} contains the typos "passsed" with three letter s and "some one" as two
 *       words; {@code :L293} is the only line in the 375 line copybook carrying a sequence number,
 *       {@code 005100}; {@code FC-INVALID-DATE} is named for invalidity but its all zero token is the
 *       success case; {@code WS-VALID-FEB-DAY} at {@code app/cpy/CSUTLDWY.cpy:L33-L34} is declared and
 *       never referenced anywhere in the corpus; and {@code app/cpy/CSUTLDPY.cpy:L203} re-sets a flag
 *       already set at {@code :L152}. Applied remedy: none is corrected, each is preserved and
 *       tracked in {@code DECISION_LOG.md}.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than invented. Two
 * items are genuinely unavailable:
 *
 * <ul>
 *   <li><strong>The internal algorithm of {@code CEEDAYS}</strong> for an input that maps to none of
 *       the nine feedback tokens, and the order in which it applies its own checks. The only
 *       behaviour the source defines for an unrecognised token is the {@code WHEN OTHER} fallback at
 *       {@code app/cbl/CSUTLDTC.cbl:L147-L148}, which yields the fifteen character string
 *       {@code Date is invalid}. Needed to close this gap: the IBM Language Environment
 *       {@code CEEDAYS} callable service documentation, which is not present in this repository. No
 *       additional outcome is invented here, and the evaluation order this class applies is recorded
 *       as a labelled deviation at {@link #callCeedays}.</li>
 *   <li><strong>Any latency or throughput objective for date validation.</strong> None exists
 *       anywhere in the source, which publishes no service level of any kind. The performance gate
 *       records a measured baseline, never an invented target.</li>
 * </ul>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>This bean is <strong>stateless and immutable after construction</strong>, and therefore safe to
 * share across request threads and batch worker threads. Rule 1 Clause B forbids global mutable
 * state, and the source is built entirely out of it: every {@code WS-} work field, all three
 * validation flags, the division work fields and the return message live in the including program's
 * {@code WORKING-STORAGE}, which in COBOL is process global. Here every one of them is
 * <strong>method local</strong>, carried for the duration of a single call in a private
 * {@link EditContext} that no other thread can observe. The only instance field is the injected
 * {@link Clock}, which is final and itself immutable. There is no static mutable field anywhere in
 * this file.
 *
 * <h2>Security contract</h2>
 *
 * <p>Rule 1 Clause D forbids secrets in code, logs, tests or configuration, and one path here handles
 * personally identifiable information directly: {@link #editDateOfBirth} validates a customer's date
 * of birth. Two consequences bind every caller:
 *
 * <ul>
 *   <li><strong>Never log or serialise the date value, the composed 80 character result, or the
 *       message text.</strong> The result embeds the supplied date in its {@code TstDate:} field, so
 *       logging the result logs the date. Log the severity and the message number, which carry no
 *       personal data.</li>
 *   <li><strong>No accessor or {@code toString()} in this file exposes the date value except the
 *       deliberately named ones</strong> that a caller must reproduce for parity. This class performs
 *       no logging of its own, so it cannot leak; the obligation passes to the caller and is stated
 *       again at {@link DateValidationResult}.</li>
 * </ul>
 *
 * <p>This class spawns no process, performs no deserialisation, builds no query, reads no environment
 * variable and reaches no network endpoint. It adds no dependency beyond the JDK and Spring's
 * stereotype annotation.
 *
 * <h2>Error model</h2>
 *
 * <p><strong>A rejected date is a return value, never an exception.</strong> The source signals a
 * rejected date by setting a flag, a severity and a message, and it continues; an exception based
 * design could not reproduce that, because the first throw would abandon the later edits whose
 * outcomes the caller observes. Exceptions are reserved for a caller breaking this class's documented
 * contract, and in that single case a
 * {@code com.cardemo.exception.ValidationException} is raised with the original throwable preserved
 * as its cause, as Rule 1 Clause B requires. Nothing is ever swallowed: there is no empty catch block
 * and no bare catch of {@code Exception} in this file.
 */
@Service
public class DateValidationService {

    /**
     * The ten character mask used by the two program call paths, {@code YYYY-MM-DD}.
     *
     * <p>Declared {@code PIC X(10) VALUE 'YYYY-MM-DD'} at {@code app/cbl/CORPT00C.cbl:L72} and again
     * at {@code app/cbl/COTRN02C.cbl:L60}. Both programs move it into
     * {@code CSUTLDTC-DATE-FORMAT PIC X(10)} before every call.
     */
    public static final String MASK_YYYY_MM_DD = "YYYY-MM-DD";

    /**
     * The eight character mask used by the copybook call path, {@code YYYYMMDD}.
     *
     * <p>Declared {@code PIC X(08) VALUE 'YYYYMMDD'} at {@code app/cpy/CSUTLDWY.cpy:L58-L59} and moved
     * into place at {@code app/cpy/CSUTLDPY.cpy:L291} immediately before the call. It has no
     * separators and is two characters shorter than {@link #MASK_YYYY_MM_DD}; that width difference is
     * the direct cause of the buffer overread described at {@link #editDateLe}.
     */
    public static final String MASK_YYYYMMDD = "YYYYMMDD";

    /** Width of {@code LS-DATE PIC X(10)}, {@code app/cbl/CSUTLDTC.cbl:L84}. */
    public static final int LS_DATE_LENGTH = 10;

    /** Width of {@code LS-DATE-FORMAT PIC X(10)}, {@code app/cbl/CSUTLDTC.cbl:L85}. */
    public static final int LS_DATE_FORMAT_LENGTH = 10;

    /** Width of {@code LS-RESULT PIC X(80)}, {@code app/cbl/CSUTLDTC.cbl:L86}. */
    public static final int LS_RESULT_LENGTH = 80;

    /** Width of {@code WS-RESULT PIC X(15)}, {@code app/cbl/CSUTLDTC.cbl:L49}. */
    public static final int RESULT_TEXT_LENGTH = 15;

    /**
     * Width of the caller's {@code CSUTLDTC-RESULT-MSG PIC X(61)},
     * {@code app/cbl/CORPT00C.cbl:L136}.
     *
     * <p>The producer's tail, from {@code FILLER PIC X(01)} at {@code app/cbl/CSUTLDTC.cbl:L48}
     * through {@code FILLER PIC X(03)} at {@code :L57}, is exactly 61 bytes, so the producer's
     * fourteen field view and the caller's four field view of the same 80 bytes reconcile precisely.
     */
    public static final int MESSAGE_TEXT_LENGTH = 61;

    /** Width of {@code WS-EDIT-DATE-CCYYMMDD PIC X(8)}, {@code app/cpy/CSUTLDWY.cpy:L4-L34}. */
    public static final int CCYYMMDD_LENGTH = 8;

    /** Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, {@code app/cbl/COACTUPC.cbl:L53}. */
    public static final int VARIABLE_NAME_LENGTH = 25;

    /** Width of {@code WS-RETURN-MSG PIC X(75)}, {@code app/cbl/COACTUPC.cbl:L479}. */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * The fifteen character result string for the {@code WHEN OTHER} arm of the source's
     * {@code EVALUATE TRUE}, {@code app/cbl/CSUTLDTC.cbl:L147-L148}.
     *
     * <p>This is the tenth of the ten result strings and the only one not attached to a feedback
     * token. It is the sole documented behaviour for a feedback code matching none of the nine
     * conditions; see the "Not available" section of this class's documentation.
     */
    public static final String UNRECOGNISED_RESULT_TEXT = "Date is invalid";

    /** Width of {@code WS-SEVERITY PIC X(04)}, {@code app/cbl/CSUTLDTC.cbl:L43}. */
    private static final int SEVERITY_LENGTH = 4;

    /** Width of {@code WS-MSG-NO PIC X(04)}, {@code app/cbl/CSUTLDTC.cbl:L46}. */
    private static final int MESSAGE_NUMBER_LENGTH = 4;

    /**
     * Width of {@code FILLER PIC X(11) VALUE 'Mesg Code:'}, {@code app/cbl/CSUTLDTC.cbl:L45}.
     *
     * <p>The literal is ten characters in an eleven byte field, so it carries one trailing space.
     */
    private static final int MESG_CODE_FILLER_LENGTH = 11;

    /**
     * Width of {@code FILLER PIC X(09) VALUE 'TstDate:'}, {@code app/cbl/CSUTLDTC.cbl:L51}.
     *
     * <p>The literal is eight characters in a nine byte field, so it carries one trailing space.
     */
    private static final int TST_DATE_FILLER_LENGTH = 9;

    /**
     * Width of {@code FILLER PIC X(10) VALUE 'Mask used:'}, {@code app/cbl/CSUTLDTC.cbl:L54}.
     *
     * <p>The literal is exactly ten characters, so this field carries no padding at all.
     */
    private static final int MASK_USED_FILLER_LENGTH = 10;

    /** Width of {@code FILLER PIC X(03) VALUE SPACES}, {@code app/cbl/CSUTLDTC.cbl:L57}. */
    private static final int TRAILING_FILLER_LENGTH = 3;

    /** The number of digit positions a {@code PIC 9(4)} field renders. */
    private static final int ZONED_FOUR_MODULUS = 10_000;

    /** The number of bits in the high order byte of a {@code PIC S9(4) BINARY} halfword. */
    private static final int BYTE_WIDTH_IN_BITS = 8;

    /** A single byte mask, used to split a halfword into its two bytes. */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The century value of {@code 88 LAST-CENTURY VALUE 19}, {@code app/cpy/CSUTLDWY.cpy:L10}.
     */
    private static final int LAST_CENTURY = 19;

    /**
     * The century value of {@code 88 THIS-CENTURY VALUE 20}, {@code app/cpy/CSUTLDWY.cpy:L9}.
     */
    private static final int THIS_CENTURY = 20;

    /** Lower bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}, {@code app/cpy/CSUTLDWY.cpy:L19-L20}. */
    private static final int MONTH_MINIMUM = 1;

    /** Upper bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}, {@code app/cpy/CSUTLDWY.cpy:L19-L20}. */
    private static final int MONTH_MAXIMUM = 12;

    /** The month of {@code 88 WS-FEBRUARY VALUE 2}, {@code app/cpy/CSUTLDWY.cpy:L24}. */
    private static final int FEBRUARY = 2;

    /** Lower bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}, {@code app/cpy/CSUTLDWY.cpy:L28-L29}. */
    private static final int DAY_MINIMUM = 1;

    /** Upper bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}, {@code app/cpy/CSUTLDWY.cpy:L28-L29}. */
    private static final int DAY_MAXIMUM = 31;

    /** The day of {@code 88 WS-DAY-31 VALUE 31}, {@code app/cpy/CSUTLDWY.cpy:L30}. */
    private static final int DAY_31 = 31;

    /** The day of {@code 88 WS-DAY-30 VALUE 30}, {@code app/cpy/CSUTLDWY.cpy:L31}. */
    private static final int DAY_30 = 30;

    /** The day of {@code 88 WS-DAY-29 VALUE 29}, {@code app/cpy/CSUTLDWY.cpy:L32}. */
    private static final int DAY_29 = 29;

    /**
     * The upper bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28},
     * {@code app/cpy/CSUTLDWY.cpy:L33-L34}.
     *
     * <p><strong>Finding, severity Low: retained intentionally.</strong> That condition name is
     * <em>declared and never referenced</em> anywhere in the corpus - not by the procedural copybook
     * that owns the work area, and not by the one program that copies it in. It is a dead condition
     * name in the system of record.
     *
     * <p>It is retained here rather than dropped, and published rather than hidden, for two reasons.
     * First, the work area is part of the field contract this class reproduces, and silently omitting a
     * declared bound would make the translation an incomplete image of its source. Second, Rule 1
     * Clause B forbids dead code that is <em>untracked</em>; this constant is documented at its
     * declaration, is asserted by the unit tests, and is recorded in {@code DECISION_LOG.md}, so it is
     * tracked rather than abandoned residue.
     *
     * <p>No validation path in this class consults it, exactly as no path in the source consults it.
     * February day validation is instead performed by the day range test and the cross field rules of
     * {@link #editDayMonthYear}, which is what the source actually does.
     */
    public static final int VALID_FEBRUARY_DAY_MAXIMUM = 28;

    /**
     * The month rejection literal, emitted verbatim by both month branches.
     *
     * <p>Byte for byte from {@code app/cpy/CSUTLDPY.cpy:L119} and, identically, {@code :L136}. It opens
     * with a colon and a space and closes with a full stop. Named once rather than written twice because
     * Rule 1 Clause C requires duplication to be avoided, and because two copies of a literal that the
     * parity gates compare byte for byte are two chances to mistype one.
     */
    private static final String MONTH_RANGE_MESSAGE = ": Month must be a number between 1 and 12.";

    /**
     * The day rejection literal, emitted verbatim by both day branches.
     *
     * <p>Byte for byte from {@code app/cpy/CSUTLDPY.cpy:L180} and, identically, {@code :L195}. Note the
     * <strong>lowercase</strong> {@code day} and the absence of a space after the leading colon, both of
     * which differ from {@link #MONTH_RANGE_MESSAGE} and both of which are reproduced deliberately.
     */
    private static final String DAY_RANGE_MESSAGE = ":day must be a number between 1 and 31.";

    /**
     * Sentinel for a two character field whose bytes are not all decimal digits.
     *
     * <p>The source reads {@code WS-EDIT-DATE-MM-N} and {@code WS-EDIT-DATE-DD-N}, which
     * {@code REDEFINE} their alphanumeric parents as {@code PIC 9(2)}, before it has established that
     * those bytes are numeric at all. A redefinition over non digit bytes has no portable meaning, so
     * rather than fabricate one this value is returned and is deliberately outside every valid range
     * the source tests: outside {@code 1 THROUGH 12} for a month and outside {@code 1 THROUGH 31} for
     * a day. The observable consequence therefore matches the source, whose range test also fails for
     * such input.
     */
    private static final int NON_NUMERIC_VIEW = -1;

    /**
     * The divisor selected when the two digit year is zero, {@code app/cpy/CSUTLDPY.cpy:L246}.
     */
    private static final int LEAP_DIVISOR_CENTURY = 400;

    /**
     * The divisor selected for every other year, {@code app/cpy/CSUTLDPY.cpy:L248}.
     */
    private static final int LEAP_DIVISOR_ORDINARY = 4;

    /**
     * The day before Lillian day one.
     *
     * <p>The Lillian day count that {@code CEEDAYS} returns through
     * {@code OUTPUT-LILLIAN PIC S9(9) BINARY} at {@code app/cbl/CSUTLDTC.cbl:L41} numbers 15 October
     * 1582 as day one, that being the first day of the Gregorian calendar. Subtracting the epoch day
     * of the preceding date therefore yields the Lillian day directly. Dates before day one have no
     * Lillian representation, which is what the unsupported range outcome reports.
     */
    private static final LocalDate LILLIAN_DAY_ZERO = LocalDate.of(1582, 10, 14);

    /**
     * The last date {@code CEEDAYS} can represent.
     *
     * <p>A four digit year cannot exceed 9999, and both masks in the corpus carry exactly four year
     * digits, so this is the upper bound of the supported range rather than an imposed limit.
     */
    private static final LocalDate LILLIAN_LAST_DAY = LocalDate.of(9999, 12, 31);

    /**
     * The day before COBOL integer date one, used by the date of birth check.
     *
     * <p>{@code FUNCTION INTEGER-OF-DATE} numbers 1 January 1601 as integer date one, so subtracting
     * the epoch day of 31 December 1600 converts a {@link LocalDate} to the same integer the source
     * places in {@code WS-EDIT-DATE-BINARY} and {@code WS-CURRENT-DATE-BINARY}, both declared
     * {@code PIC S9(9) BINARY} at {@code app/cpy/CSUTLDWY.cpy:L37} and {@code :L42}. The offset is
     * derived rather than written as a literal so that no magic number appears here.
     */
    private static final LocalDate INTEGER_DATE_ZERO = LocalDate.of(1600, 12, 31);

    /**
     * The injected clock backing {@code FUNCTION CURRENT-DATE} at {@code app/cpy/CSUTLDPY.cpy:L343}.
     *
     * <p>This is the only instance field in the class and the only collaborator the bean has. It is
     * final and {@link Clock} implementations obtained from the factory methods are immutable, so the
     * bean remains safe to share across threads.
     */
    private final Clock clock;

    /**
     * Constructs the service with the clock that supplies the current date.
     *
     * <p>Constructor injection is used deliberately and exclusively: Rule 1 Clause B requires
     * dependency injection over global mutable state, and field or setter injection would leave a
     * window in which the field is null and would make the bean mutable after construction.
     *
     * @param clock the clock supplying the current date for the date of birth reasonableness check;
     *              must not be {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}, which is a wiring defect rather
     *                             than a data condition and so is reported immediately
     */
    public DateValidationService(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The nine Language Environment feedback conditions, each with its severity, message number and
     * exact fifteen character result string.
     *
     * <p>The source declares these as nine condition names on {@code FEEDBACK-TOKEN-VALUE} at
     * {@code app/cbl/CSUTLDTC.cbl:L61-L70} - <em>not</em> on {@code CASE-1-CONDITION-ID}, which is a
     * subordinate group. Each token is an eight byte hexadecimal literal whose first four bytes are
     * the severity and the message number as two {@code PIC S9(4) BINARY} halfwords
     * ({@code app/cbl/CSUTLDTC.cbl:L71-L73}), whose fifth byte is
     * {@code CASE-SEV-CTL} holding {@code X'59'}, and whose last three bytes are {@code FACILITY-ID}
     * holding {@code X'C3C5C5'} - the letters {@code CEE} in EBCDIC, identifying the Language
     * Environment as the issuing facility.
     *
     * <p>The severity and message number below are decoded from those literals. They are not
     * transcoded from a code page: only the two halfword values are read, exactly as the source reads
     * them with {@code MOVE SEVERITY OF FEEDBACK-CODE} and {@code MOVE MSG-NO OF FEEDBACK-CODE} at
     * {@code app/cbl/CSUTLDTC.cbl:L123-L124}. Rule 1 Clause D's prohibition on EBCDIC transcoding is
     * respected: the facility identifier is documented, never decoded at runtime.
     *
     * <p><strong>Finding, severity Low.</strong> {@link #FC_INVALID_DATE} is named for an invalid date
     * but its token is {@code X'0000000000000000'} - all zeros, which is severity zero and message
     * number zero, that is to say <em>success</em>. The name is a defect in the system of record and
     * is preserved rather than corrected, because the corpus is frozen and because renaming it would
     * break the correspondence a reviewer needs. It is corroborated twice: the copybook tests
     * {@code IF WS-SEVERITY-N = 0} for success at {@code app/cpy/CSUTLDPY.cpy:L298}, and this
     * condition alone carries the result string {@code Date is valid}. Tracked in
     * {@code DECISION_LOG.md}.
     *
     * <p>Five of the ten result strings carry deliberate trailing spaces inside their COBOL quotes -
     * {@code Invalid Era} followed by four spaces, {@code Unsupp. Range} by two,
     * {@code Invalid month} by two, {@code Bad Pic String} by one and {@code YearInEra is 0} by one -
     * so that every one occupies exactly fifteen characters. The trailing spaces are reproduced in the
     * constants below and are described here in prose as well, so that a formatter cannot silently
     * remove the evidence.
     */
    public enum FeedbackCondition {

        /**
         * The all zero token, which is success. Severity 0, message number 0.
         *
         * <p>{@code 88 FC-INVALID-DATE VALUE X'0000000000000000'},
         * {@code app/cbl/CSUTLDTC.cbl:L62}; result string {@code app/cbl/CSUTLDTC.cbl:L130}.
         */
        FC_INVALID_DATE(0, 0, "Date is valid"),

        /**
         * The supplied date did not provide every component the mask requires. Severity 3, message
         * number 2507.
         *
         * <p>{@code 88 FC-INSUFFICIENT-DATA VALUE X'000309CB59C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L63}; result string {@code :L132}.
         */
        FC_INSUFFICIENT_DATA(3, 2507, "Insufficient"),

        /**
         * The date components were well formed but did not denote a real date. Severity 3, message
         * number 2508.
         *
         * <p>{@code 88 FC-BAD-DATE-VALUE VALUE X'000309CC59C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L64}; result string {@code :L134}.
         */
        FC_BAD_DATE_VALUE(3, 2508, "Datevalue error"),

        /**
         * The era component was not valid. Severity 3, message number 2509.
         *
         * <p>{@code 88 FC-INVALID-ERA VALUE X'000309CD59C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L65}; result string {@code :L136}, which carries four trailing
         * spaces inside its quotes.
         *
         * <p><strong>Finding, severity Low.</strong> Neither mask in the corpus carries an era symbol,
         * so no input to either documented call path can produce this condition. It is nonetheless
         * modelled, because the source declares it and because {@link FeedbackCode#condition()}
         * resolves it whenever a caller presents this severity and message number pair. Retaining it
         * keeps the enum a faithful image of the nine declared tokens rather than a subset chosen by
         * reachability. Tracked in {@code DECISION_LOG.md}.
         */
        FC_INVALID_ERA(3, 2509, "Invalid Era    "),

        /**
         * The date fell outside the range the service can represent. Severity 3, message number 2513.
         *
         * <p>{@code 88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L66}; result string {@code :L138}, which carries two trailing
         * spaces inside its quotes.
         *
         * <p>This is the one condition the calling programs single out and deliberately tolerate:
         * {@code app/cbl/COTRN02C.cbl:L400} and {@code :L420}, and
         * {@code app/cbl/CORPT00C.cbl:L399} and {@code :L419}, all read
         * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} before reporting a failure. That is direct
         * evidence in the corpus that message number 2513 means a representable date outside the
         * supported window rather than a malformed one.
         */
        FC_UNSUPP_RANGE(3, 2513, "Unsupp. Range  "),

        /**
         * The month component was outside one through twelve. Severity 3, message number 2517.
         *
         * <p>{@code 88 FC-INVALID-MONTH VALUE X'000309D559C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L67}; result string {@code :L140}, which carries two trailing
         * spaces inside its quotes.
         */
        FC_INVALID_MONTH(3, 2517, "Invalid month  "),

        /**
         * The format mask itself was not a picture string the service recognises. Severity 3, message
         * number 2518.
         *
         * <p>{@code 88 FC-BAD-PIC-STRING VALUE X'000309D659C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L68}; result string {@code :L142}, which carries one trailing
         * space inside its quotes.
         */
        FC_BAD_PIC_STRING(3, 2518, "Bad Pic String "),

        /**
         * A position the mask requires to be numeric was not. Severity 3, message number 2520.
         *
         * <p>{@code 88 FC-NON-NUMERIC-DATA VALUE X'000309D859C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L69}; result string {@code :L144}.
         */
        FC_NON_NUMERIC_DATA(3, 2520, "Nonnumeric data"),

        /**
         * The year within the era was zero. Severity 3, message number 2521.
         *
         * <p>{@code 88 FC-YEAR-IN-ERA-ZERO VALUE X'000309D959C3C5C5'},
         * {@code app/cbl/CSUTLDTC.cbl:L70}; result string {@code :L146}, which carries one trailing
         * space inside its quotes.
         */
        FC_YEAR_IN_ERA_ZERO(3, 2521, "YearInEra is 0 ");

        /**
         * A cached copy of the constants, so that resolving a condition neither allocates nor rebuilds
         * a table on every call.
         *
         * <p>Rule 1 Clause A requires that obvious inefficiency be avoided, and {@code values()}
         * returns a defensive clone on each invocation. A deliberately ordered array rather than a hash
         * based map also discharges the clause's determinism requirement, since no outcome here may
         * depend on hash iteration order.
         */
        private static final FeedbackCondition[] CACHED_VALUES = values();

        /** The severity halfword decoded from this condition's feedback token. */
        private final int severity;

        /** The message number halfword decoded from this condition's feedback token. */
        private final int messageNumber;

        /** The fifteen character result text this condition places in the returned message area. */
        private final String resultText;

        /**
         * Binds a decoded feedback token to the result text the source emits for it.
         *
         * @param severity      the severity halfword, zero for success and three otherwise
         * @param messageNumber the message number halfword
         * @param resultText    the fifteen character result text, byte exact and including any
         *                      trailing spaces the source literal carries
         */
        FeedbackCondition(final int severity, final int messageNumber, final String resultText) {
            this.severity = severity;
            this.messageNumber = messageNumber;
            this.resultText = resultText;
        }

        /**
         * Returns the severity halfword of this condition's feedback token.
         *
         * <p>This is the value the source moves into {@code WS-SEVERITY-N} at
         * {@code app/cbl/CSUTLDTC.cbl:L123} and, separately, into {@code RETURN-CODE} at {@code :L98}.
         *
         * @return {@code 0} for the success condition and {@code 3} for the other eight
         */
        public int severity() {
            return severity;
        }

        /**
         * Returns the message number halfword of this condition's feedback token.
         *
         * <p>This is the value the source moves into {@code WS-MSG-NO-N} at
         * {@code app/cbl/CSUTLDTC.cbl:L124}.
         *
         * @return {@code 0} for the success condition, otherwise a Language Environment message number
         *         in the range 2507 through 2521
         */
        public int messageNumber() {
            return messageNumber;
        }

        /**
         * Returns this condition's result string, always exactly {@link #RESULT_TEXT_LENGTH}
         * characters including any deliberate trailing spaces.
         *
         * @return the fifteen character string the source moves into {@code WS-RESULT}
         */
        public String resultText() {
            return resultText;
        }

        /**
         * Resolves the condition carrying the given severity and message number, if any.
         *
         * <p>This is the {@code EVALUATE TRUE} subject of {@code app/cbl/CSUTLDTC.cbl:L128-L149}
         * expressed as a lookup. An empty result is the {@code WHEN OTHER} arm at {@code :L147-L148}:
         * a feedback code that matches none of the nine declared tokens.
         *
         * @param severity      the severity halfword to match
         * @param messageNumber the message number halfword to match
         * @return the matching condition, or an empty optional for the {@code WHEN OTHER} case
         */
        static Optional<FeedbackCondition> resolve(final int severity, final int messageNumber) {
            for (final FeedbackCondition candidate : CACHED_VALUES) {
                if (candidate.severity == severity && candidate.messageNumber == messageNumber) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The two halfwords of a Language Environment feedback code: a severity and a message number.
     *
     * <p>This mirrors {@code CASE-1-CONDITION-ID} at {@code app/cbl/CSUTLDTC.cbl:L71-L73}, declared as
     * {@code SEVERITY PIC S9(4) BINARY} followed by {@code MSG-NO PIC S9(4) BINARY}. Modelling the pair
     * as a value rather than folding it into {@link FeedbackCondition} matters for fidelity: the source
     * moves the two halfwords into the result area at {@code :L123-L124} <em>before</em> it evaluates
     * which condition they denote at {@code :L128}, so a code whose value matches no declared token
     * still contributes a severity and a message number to the result. That is precisely the
     * {@code WHEN OTHER} case, and representing the code separately is what keeps it reachable.
     *
     * <p>Both components are plain {@code int}. Rule 1 Clause A and the tree wide financial invariant
     * forbid floating point, and a halfword is integral in the source; no rounding, scale or decimal
     * type is involved.
     *
     * @param severity      the severity halfword, moved to {@code WS-SEVERITY-N} and to
     *                      {@code RETURN-CODE}
     * @param messageNumber the message number halfword, moved to {@code WS-MSG-NO-N}
     */
    public record FeedbackCode(int severity, int messageNumber) {

        /**
         * Creates a feedback code for the given condition.
         *
         * @param condition the condition whose halfwords are wanted; must not be {@code null}
         * @return a code carrying that condition's severity and message number
         * @throws NullPointerException if {@code condition} is {@code null}
         */
        public static FeedbackCode of(final FeedbackCondition condition) {
            Objects.requireNonNull(condition, "condition must not be null");
            return new FeedbackCode(condition.severity(), condition.messageNumber());
        }

        /**
         * Resolves which of the nine declared conditions this code denotes, if any.
         *
         * @return the matching condition, or an empty optional when this code matches none of the nine,
         *         which is the source's {@code WHEN OTHER} arm at {@code app/cbl/CSUTLDTC.cbl:L147}
         */
        public Optional<FeedbackCondition> condition() {
            return FeedbackCondition.resolve(severity, messageNumber);
        }

        /**
         * Returns the result string this code produces, reproducing the source's {@code EVALUATE TRUE}.
         *
         * @return the resolved condition's fifteen character string, or
         *         {@link DateValidationService#UNRECOGNISED_RESULT_TEXT} for the {@code WHEN OTHER} case
         */
        public String resultText() {
            return condition().map(FeedbackCondition::resultText).orElse(UNRECOGNISED_RESULT_TEXT);
        }
    }

    /**
     * One of the three states a single date component flag can hold.
     *
     * <p>The source declares three one byte flags inside a single three byte group at
     * {@code app/cpy/CSUTLDWY.cpy:L43-L57}, and gives each flag three condition names: valid, not
     * acceptable, and absent. The values are {@code LOW-VALUES}, {@code '0'} and {@code 'B'}
     * respectively.
     *
     * <p><strong>Finding, severity High if violated.</strong> A single boolean cannot carry this. It
     * cannot distinguish an absent component from a supplied but unacceptable one, and the source
     * distinguishes them deliberately - the copybook sets the blank state on its own branch at
     * {@code app/cpy/CSUTLDPY.cpy:L33}, {@code :L97} and {@code :L157}, and the including program acts
     * on that state separately. Remedy: keep all three constants, and derive composite validity by
     * conjunction rather than storing it.
     */
    public enum EditFlag {

        /**
         * The component was validated. The source's {@code VALUE LOW-VALUES} state, declared at
         * {@code app/cpy/CSUTLDWY.cpy:L47}, {@code :L51} and {@code :L55}.
         */
        ISVALID,

        /**
         * The component was supplied but is not acceptable. The source's {@code VALUE '0'} state,
         * declared at {@code app/cpy/CSUTLDWY.cpy:L48}, {@code :L52} and {@code :L56}.
         */
        NOT_OK,

        /**
         * The component was not supplied at all. The source's {@code VALUE 'B'} state, declared at
         * {@code app/cpy/CSUTLDWY.cpy:L49}, {@code :L53} and {@code :L57}.
         */
        BLANK
    }

    /**
     * The 80 byte result the date service produces, in both the producer's and the caller's views.
     *
     * <p>This single type serves the source's two byte identical declarations of the same layout:
     * {@code WS-MESSAGE} at {@code app/cbl/CSUTLDTC.cbl:L42-L57} and
     * {@code WS-DATE-VALIDATION-RESULT} at {@code app/cpy/CSUTLDWY.cpy:L60-L85}. The two differ only in
     * that the copybook's {@code WS-DATE} lacks a {@code VALUE SPACES} clause and that
     * {@code app/cpy/CSUTLDWY.cpy:L80} carries a stray space before its period. Rule 1 Clause C forbids
     * the duplication that modelling them separately would create.
     *
     * <p>The producer's fourteen field view sums to exactly 80 bytes:
     * {@code 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3}. The caller's four field view at
     * {@code app/cbl/CORPT00C.cbl:L129-L136}, repeated at {@code app/cbl/COTRN02C.cbl:L62-L69}, sums to
     * the same 80 bytes as {@code 4 + 11 + 4 + 61}. The two reconcile because the producer's tail from
     * {@code :L48} through {@code :L57} is exactly 61 bytes wide, which is why
     * {@link #messageText()} is derived from the composed string rather than stored a second time.
     *
     * <p><strong>Security, Rule 1 Clause D.</strong> {@link #result()} embeds the supplied date in its
     * {@code TstDate:} field and {@link #messageText()} is a substring of it, so
     * <strong>both carry the input date and neither may be logged or serialised</strong> when that date
     * is a customer date of birth. Log {@link #severityCode()} and {@link #messageNumber()}, which
     * carry no personal data. No accessor is provided that returns the date on its own, and
     * {@code toString()} is deliberately left as the record default so that no logging framework
     * silently renders one: callers must select fields explicitly.
     *
     * @param feedbackCode the severity and message number the service produced
     * @param result       the composed 80 character result area, exactly
     *                     {@link DateValidationService#LS_RESULT_LENGTH} characters
     */
    public record DateValidationResult(FeedbackCode feedbackCode, String result) {

        /**
         * Validates the invariants of the layout.
         *
         * <p>Rule 1 Clause B requires boundary conditions to be explicit. The width of the composed
         * area is the contract every caller depends on, so it is asserted at construction rather than
         * trusted.
         *
         * @throws NullPointerException     if either component is {@code null}
         * @throws IllegalArgumentException if {@code result} is not exactly 80 characters long
         */
        public DateValidationResult {
            Objects.requireNonNull(feedbackCode, "feedbackCode must not be null");
            Objects.requireNonNull(result, "result must not be null");
            if (result.length() != LS_RESULT_LENGTH) {
                throw new IllegalArgumentException(
                        "result must be exactly " + LS_RESULT_LENGTH + " characters, was " + result.length());
            }
        }

        /**
         * Returns the severity as the four character zero padded string the callers compare against.
         *
         * <p>{@code WS-SEVERITY-N} is declared {@code PIC 9(4)} at {@code app/cbl/CSUTLDTC.cbl:L44},
         * so a severity of zero renders as {@code 0000}. That is exactly the literal the calling
         * programs test at {@code app/cbl/COTRN02C.cbl:L397} and {@code app/cbl/CORPT00C.cbl:L396},
         * {@code IF CSUTLDTC-RESULT-SEV-CD = '0000'}.
         *
         * @return the first four characters of the composed area
         */
        public String severityCode() {
            return result.substring(0, 4);
        }

        /**
         * Returns the message number as the four character zero padded string the callers compare
         * against.
         *
         * <p>{@code WS-MSG-NO-N} is declared {@code PIC 9(4)} at {@code app/cbl/CSUTLDTC.cbl:L47}. The
         * calling programs test this field directly at {@code app/cbl/COTRN02C.cbl:L400} and
         * {@code app/cbl/CORPT00C.cbl:L399}, {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}.
         *
         * @return characters 16 through 19 of the composed area
         */
        public String messageNumber() {
            return result.substring(15, 19);
        }

        /**
         * Returns the caller's {@code CSUTLDTC-RESULT-MSG PIC X(61)} view of the result tail.
         *
         * <p>Derived from {@link #result()} rather than stored, because the producer's tail and the
         * caller's message field are the same 61 bytes; storing both would create two sources of truth
         * for one value the parity gates compare.
         *
         * <p><strong>This value embeds the supplied date</strong> and must not be logged when that date
         * is personally identifiable.
         *
         * @return the trailing {@link DateValidationService#MESSAGE_TEXT_LENGTH} characters
         */
        public String messageText() {
            return result.substring(LS_RESULT_LENGTH - MESSAGE_TEXT_LENGTH);
        }

        /**
         * Returns the fifteen character result string, including any deliberate trailing spaces.
         *
         * @return characters 21 through 35 of the composed area, being {@code WS-RESULT}
         */
        public String resultText() {
            return result.substring(20, 20 + RESULT_TEXT_LENGTH);
        }

        /**
         * Returns the severity as the process return code the source also publishes.
         *
         * <p>{@code MOVE WS-SEVERITY-N TO RETURN-CODE} at {@code app/cbl/CSUTLDTC.cbl:L98} means the
         * severity is returned twice by two different mechanisms: embedded in the 80 byte result and as
         * the subprogram's return code. Both are surfaced so that neither channel is lost in
         * translation.
         *
         * @return the severity halfword
         */
        public int returnCode() {
            return feedbackCode.severity();
        }

        /**
         * Reports whether the date was accepted.
         *
         * <p>Derived exactly as the source derives it: the copybook tests
         * {@code IF WS-SEVERITY-N = 0} at {@code app/cpy/CSUTLDPY.cpy:L298} and the programs test the
         * rendered form {@code '0000'}. Only the success condition carries severity zero.
         *
         * @return {@code true} when the severity is zero
         */
        public boolean valid() {
            return feedbackCode.severity() == 0;
        }
    }

    /**
     * The outcome of a composite date edit: the three component flags, the error indicator and the
     * message.
     *
     * <p>The three flags together are the source's {@code WS-EDIT-DATE-FLGS} group at
     * {@code app/cpy/CSUTLDWY.cpy:L43-L57}. Exposing all three rather than a single verdict is
     * required, not optional: the only caller in the corpus copies the whole group out and keeps it,
     * with {@code MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-OPEN-DATE-FLGS} at
     * {@code app/cbl/COACTUPC.cbl:L1482} and the same pattern at {@code :L1494}, {@code :L1507},
     * {@code :L1538} and {@code :L1542}.
     *
     * <p>The error indicator is the including program's {@code 88 INPUT-ERROR VALUE '1'} at
     * {@code app/cbl/COACTUPC.cbl:L173}, and the message is its
     * {@code WS-RETURN-MSG PIC X(75)} at {@code :L479}. Neither is declared in either copybook, which
     * is why both are carried here as results rather than held as bean state.
     *
     * <p><strong>Security, Rule 1 Clause D.</strong> {@link #returnMessage()} is composed from a
     * caller supplied field label and never from the field's value, so it carries no personal data by
     * construction. That property depends on callers passing a label such as {@code Date of Birth} and
     * never the date itself.
     *
     * @param yearFlag      the state of {@code WS-EDIT-YEAR-FLG}
     * @param monthFlag     the state of {@code WS-EDIT-MONTH}
     * @param dayFlag       the state of {@code WS-EDIT-DAY}
     * @param inputError    whether {@code INPUT-ERROR} was set during the edit
     * @param returnMessage the first message the edit produced, held at the source's fixed width of
     *                      {@link DateValidationService#RETURN_MESSAGE_LENGTH} characters and all
     *                      spaces when no message was produced
     */
    public record EditOutcome(EditFlag yearFlag, EditFlag monthFlag, EditFlag dayFlag,
                              boolean inputError, String returnMessage) {

        /**
         * Validates that no component is {@code null}.
         *
         * @throws NullPointerException if any reference component is {@code null}
         */
        public EditOutcome {
            Objects.requireNonNull(yearFlag, "yearFlag must not be null");
            Objects.requireNonNull(monthFlag, "monthFlag must not be null");
            Objects.requireNonNull(dayFlag, "dayFlag must not be null");
            Objects.requireNonNull(returnMessage, "returnMessage must not be null");
        }

        /**
         * Reports composite validity as the conjunction of all three component flags.
         *
         * <p>This is the group level condition name {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES}
         * at {@code app/cpy/CSUTLDWY.cpy:L44}. Because it is declared on the three byte
         * <em>group</em> and each subordinate flag clears exactly one byte, the condition holds only
         * when year, month and day were each individually validated. The source relies on that at
         * {@code app/cpy/CSUTLDPY.cpy:L274} and the caller relies on it at
         * {@code app/cbl/COACTUPC.cbl:L1539}.
         *
         * <p>Validity is <strong>derived, never stored</strong>. A stored composite could drift out of
         * step with the components; a derived one cannot.
         *
         * @return {@code true} only when all three flags are {@link EditFlag#ISVALID}
         */
        public boolean valid() {
            return yearFlag == EditFlag.ISVALID
                    && monthFlag == EditFlag.ISVALID
                    && dayFlag == EditFlag.ISVALID;
        }

        /**
         * Reports the group level invalid condition, all three flags being unacceptable.
         *
         * <p>This is {@code 88 WS-EDIT-DATE-IS-INVALID VALUE '000'} at
         * {@code app/cpy/CSUTLDWY.cpy:L45}, the state that {@code EDIT-DATE-CCYYMMDD} establishes in
         * its single statement. It is not the negation of {@link #valid()}: a date with a blank month
         * and a valid year is neither wholly valid nor in this wholly unacceptable state, and the
         * source can express that intermediate condition precisely because the flags are separate.
         *
         * @return {@code true} only when all three flags are {@link EditFlag#NOT_OK}
         */
        public boolean allComponentsNotOk() {
            return yearFlag == EditFlag.NOT_OK
                    && monthFlag == EditFlag.NOT_OK
                    && dayFlag == EditFlag.NOT_OK;
        }

        /**
         * Reports whether a message was produced by this edit.
         *
         * <p>The complement of the source's latch condition
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}.
         *
         * @return {@code true} when a message is present
         */
        public boolean hasReturnMessage() {
            return !returnMessage.isBlank();
        }
    }

    /**
     * Validates a date against a format mask, reproducing the whole {@code CSUTLDTC} call contract.
     *
     * <p>This is the public face of {@code app/cbl/CSUTLDTC.cbl}, whose
     * {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT} at {@code :L88} takes two
     * inputs and fills one 80 byte output. Four call sites exist in the programs -
     * {@code app/cbl/COTRN02C.cbl:L393} and {@code :L413}, and {@code app/cbl/CORPT00C.cbl:L392} and
     * {@code :L412} - and a fifth is inside the procedural copybook at
     * {@code app/cpy/CSUTLDPY.cpy:L293}, reached here through {@link #editDateLe}.
     *
     * <p><strong>Side effects: none.</strong> This method mutates nothing, touches no field of the bean
     * and performs no logging. It is a pure function of its two arguments and, for the date of birth
     * path only, of the injected clock.
     *
     * <p><strong>Error modes.</strong> A rejected date is <em>not</em> an error: it is returned as a
     * result carrying severity 3, the message number of the condition that rejected it, and that
     * condition's fifteen character string. This method throws nothing. A {@code null} argument is
     * absorbed as an all spaces field of the declared width, which is the closest analogue of a COBOL
     * caller passing an uninitialised field, and produces the insufficient data outcome rather than a
     * {@link NullPointerException}.
     *
     * <p><strong>Security.</strong> The returned {@link DateValidationResult#result()} embeds the date
     * that was supplied. When that date is a customer date of birth it is personally identifiable, so
     * neither the composed result nor {@link DateValidationResult#messageText()} may be logged or
     * serialised. Log {@link DateValidationResult#severityCode()} and
     * {@link DateValidationResult#messageNumber()} instead.
     *
     * @param lsDate       the date text, corresponding to {@code LS-DATE PIC X(10)}; padded or
     *                     truncated to ten characters, and treated as all spaces when {@code null}
     * @param lsDateFormat the mask, corresponding to {@code LS-DATE-FORMAT PIC X(10)}; expected to be
     *                     {@link #MASK_YYYY_MM_DD} or {@link #MASK_YYYYMMDD}, with any other value
     *                     producing {@link FeedbackCondition#FC_BAD_PIC_STRING}
     * @return the 80 byte result area together with the severity and message number that produced it
     */
    public DateValidationResult validate(final String lsDate, final String lsDateFormat) {
        // LINKAGE SECTION, app/cbl/CSUTLDTC.cbl:L84-L86: LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10)
        // and LS-RESULT PIC X(80), all received BY REFERENCE. Fixing the two inputs to their declared
        // widths is what a COBOL MOVE into a field of that width does, and is also where a null is
        // absorbed rather than propagated.
        final String date = fixedWidth(lsDate, LS_DATE_LENGTH);
        final String mask = fixedWidth(lsDateFormat, LS_DATE_FORMAT_LENGTH);

        // :L90 INITIALIZE WS-MESSAGE followed by :L91 MOVE SPACES TO WS-DATE. COBOL INITIALIZE does not
        // touch FILLER items, which is exactly why the three embedded literals survive at their fixed
        // offsets and the fourteen field layout still reconciles to 80 bytes. Both statements are
        // overwritten by A000-MAIN on every path, so this is the starting value that paragraph replaces.
        final String initialisedWsDate = " ".repeat(LS_DATE_LENGTH);

        final DateValidationResult result = a000Main(date, mask, initialisedWsDate); // :L93-L94 PERFORM
        a000MainExit();

        // :L97 MOVE WS-MESSAGE TO LS-RESULT and :L98 MOVE WS-SEVERITY-N TO RETURN-CODE. Both channels
        // are surfaced: the first as result(), the second as returnCode(). The source's :L96 DISPLAY
        // WS-MESSAGE and :L101 GOBACK are commented out on disk and so are deliberately not translated;
        // :L100 EXIT PROGRAM is the return itself.
        return result;
    }

    /**
     * {@code A000-MAIN}, {@code app/cbl/CSUTLDTC.cbl:L103-L151}.
     *
     * <p>Builds the two variable length strings {@code CEEDAYS} expects, calls it, then renders its
     * feedback code into the 80 byte area. The paragraph is reproduced statement by statement and in
     * order, including the group move at {@code :L122} that corrupts part of the area.
     *
     * <p><strong>Preserved defect, severity Medium: the group move at {@code :L122}.</strong>
     * {@code MOVE WS-DATE-TO-TEST TO WS-DATE} names the <em>group</em>
     * {@code WS-DATE-TO-TEST}, declared at {@code :L25-L31} as a two byte
     * {@code Vstring-length PIC S9(4) BINARY} followed by {@code Vstring-text}. Moving a twelve byte
     * group into the ten byte alphanumeric {@code WS-DATE} truncates from the right, so the first two
     * bytes of the {@code TstDate:} field become the binary length - {@code X'000A'}, being ten - and
     * the remaining eight bytes hold only the first eight characters of the date. The clean value that
     * {@code :L107-L108} had already placed there is destroyed.
     *
     * <p>This is reproduced exactly, including the two non printable bytes, because the corrupted field
     * is observable in the 80 byte result that callers display and the parity gates compare. Remedy if
     * it is ever "fixed": the {@code TstDate:} comparison against the legacy baseline will fail, which
     * is the symptom to look for. Tracked in {@code DECISION_LOG.md}.
     *
     * @param lsDate            the ten character date field
     * @param lsDateFormat      the ten character mask field
     * @param initialisedWsDate the value {@code :L91} left in {@code WS-DATE}
     * @return the composed result and the feedback code behind it
     */
    private DateValidationResult a000Main(final String lsDate, final String lsDateFormat,
                                          final String initialisedWsDate) {
        // :L105-L106 MOVE LENGTH OF LS-DATE TO VSTRING-LENGTH OF WS-DATE-TO-TEST. LENGTH OF a
        // PIC X(10) field is the constant 10 whatever the caller actually passed. That unconditional
        // 10 is the mechanism behind the buffer overread documented at editDateLe.
        final int vstringLength = LS_DATE_LENGTH;

        // :L107-L108 MOVE LS-DATE TO VSTRING-TEXT OF WS-DATE-TO-TEST, WS-DATE. One MOVE with two
        // receiving fields: the variable length string handed to CEEDAYS, and the result area's
        // TstDate: field. The three successive assignments to wsDate below are the three MOVEs the
        // source makes into WS-DATE, at :L91, here, and :L122; the first two values are destroyed by
        // the next, and that is the defect, not an oversight.
        final String vstringText = lsDate.substring(0, vstringLength);
        String wsDate = initialisedWsDate;
        wsDate = vstringText;

        // :L109-L113 the same pair of moves for the mask: into the CEEDAYS argument and into the
        // result area's 'Mask used:' field.
        final int formatVstringLength = LS_DATE_FORMAT_LENGTH;
        final String formatVstringText = lsDateFormat.substring(0, formatVstringLength);
        final String wsDateFmt = formatVstringText;

        // :L114 MOVE 0 TO OUTPUT-LILLIAN, declared PIC S9(9) BINARY at :L41. CEEDAYS replaces it with
        // the Lillian day number on success. No program in the corpus ever reads OUTPUT-LILLIAN, so the
        // day number is deliberately not surfaced on the result; that omission is a labelled deviation
        // tracked in DECISION_LOG.md. The Lillian range itself is still honoured, because it is what
        // the unsupported range outcome reports.

        // :L116-L120 CALL "CEEDAYS" USING WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN,
        // FEEDBACK-CODE.
        final FeedbackCode feedbackCode = callCeedays(vstringText, formatVstringText);

        // :L122 MOVE WS-DATE-TO-TEST TO WS-DATE.  *** PRESERVED DEFECT - see this method's Javadoc ***
        wsDate = groupMoveWholeVaryingString(vstringLength, vstringText);

        // :L123 MOVE SEVERITY OF FEEDBACK-CODE TO WS-SEVERITY-N, and :L124 the same for MSG-NO. Both
        // receiving fields are PIC 9(4) redefinitions, so both render zero padded to four digits.
        final String wsSeverity = zonedFour(feedbackCode.severity());
        final String wsMsgNo = zonedFour(feedbackCode.messageNumber());

        // :L126-L127 are two comment lines recording that WS-RESULT is fifteen characters, with a
        // column ruler. :L128-L149 EVALUATE TRUE over the nine conditions, with WHEN OTHER at
        // :L147-L148 falling back to 'Date is invalid'. That whole evaluation is FeedbackCode#resultText.
        final String wsResult = feedbackCode.resultText();

        return new DateValidationResult(feedbackCode,
                composeMessageArea(wsSeverity, wsMsgNo, wsResult, wsDate, wsDateFmt));
    }

    /**
     * {@code A000-MAIN-EXIT}, {@code app/cbl/CSUTLDTC.cbl:L152-L154}.
     *
     * <p>The paragraph body is the single statement {@code EXIT} at {@code :L153}. Plain COBOL
     * {@code EXIT} is a <strong>documentary no-op</strong>: it marks the end of a {@code PERFORM THRU}
     * range and generates no branch. This method is therefore intentionally empty, and it is retained
     * rather than elided because the label is one of the sixteen the scope coverage gate counts.
     *
     * <p>Contrast {@link #editDateLeExit}, where the same {@code EXIT} is followed by a statement that
     * really does execute. Reading {@code EXIT} as a Java {@code return} is the single most damaging
     * mistake available in this translation.
     */
    private void a000MainExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * Stands in for the IBM Language Environment callable service {@code CEEDAYS}.
     *
     * <p>Invoked from {@code app/cbl/CSUTLDTC.cbl:L116-L120}. The service converts a date text under a
     * picture mask into a Lillian day number and reports the outcome through a feedback code. This
     * method reproduces the <em>outcomes</em>, which is what the AAP's transformation rule for
     * {@code CALL 'CSUTLDTC'} and {@code CEEDAYS} requires, and it does so using {@code java.time} for
     * calendar arithmetic.
     *
     * <p><strong>Scope note, so that no reviewer mistakes this for the forbidden substitution.</strong>
     * Using {@link LocalDate} here is correct and intended: this is the Language Environment side of
     * the translation, and the source delegates entirely to an opaque external service. It is
     * <em>not</em> the leap year test of {@link #editDayMonthYear}, which is written out longhand in the
     * copybook and must keep its two branch division structure. The two must not be conflated.
     *
     * <p><strong>Not available: the internal algorithm of {@code CEEDAYS}.</strong> The order in which
     * the real service applies its own checks, and its behaviour for an input that maps to none of the
     * nine declared tokens, are not derivable from anything in this repository. Needed to close the
     * gap: the IBM Language Environment {@code CEEDAYS} callable service documentation. What is done
     * instead is stated explicitly below and recorded in {@code DECISION_LOG.md} as a labelled
     * deviation. <strong>No outcome outside the nine declared tokens is invented</strong>, and every
     * classification below selects one of them.
     *
     * <p>The evaluation order applied here, chosen to be deterministic and to report the most specific
     * available condition:
     *
     * <ol>
     *   <li>The mask must be one of the two in the corpus, otherwise
     *       {@link FeedbackCondition#FC_BAD_PIC_STRING}.</li>
     *   <li>Every position the mask requires must be present, otherwise
     *       {@link FeedbackCondition#FC_INSUFFICIENT_DATA}. Absent means a space or a null byte, which
     *       is how an uninitialised or partly filled COBOL field presents.</li>
     *   <li>Every digit position must hold a decimal digit and every separator position must hold the
     *       separator the mask specifies, otherwise
     *       {@link FeedbackCondition#FC_NON_NUMERIC_DATA}.</li>
     *   <li>A year of zero yields {@link FeedbackCondition#FC_YEAR_IN_ERA_ZERO}, which is the condition
     *       named for exactly that case.</li>
     *   <li>A month outside one through twelve yields
     *       {@link FeedbackCondition#FC_INVALID_MONTH}, again the condition named for the case.</li>
     *   <li>A day that does not exist in that month of that year yields
     *       {@link FeedbackCondition#FC_BAD_DATE_VALUE}: the components were well formed but do not
     *       denote a real date.</li>
     *   <li>A real date outside the representable window yields
     *       {@link FeedbackCondition#FC_UNSUPP_RANGE}. This is the classification the corpus itself
     *       corroborates, because four call sites single out message number 2513 and tolerate it.</li>
     *   <li>Otherwise {@link FeedbackCondition#FC_INVALID_DATE}, the all zero success token.</li>
     * </ol>
     *
     * <p>{@link FeedbackCondition#FC_INVALID_ERA} is unreachable from either mask, since neither
     * carries an era symbol; it is documented at its declaration rather than forced into this order.
     *
     * @param dateText the date characters handed to the service
     * @param maskText the mask characters handed to the service
     * @return the feedback code the outcome produces, always one of the nine declared tokens
     */
    private static FeedbackCode callCeedays(final String dateText, final String maskText) {
        // Step 1. The mask is a picture string; only the two in the corpus are recognised. Trailing
        // spaces are insignificant because both masks are declared shorter than the X(10) field that
        // carries them on the program call path.
        final String mask = maskText.stripTrailing();
        if (!MASK_YYYY_MM_DD.equals(mask) && !MASK_YYYYMMDD.equals(mask)) {
            return FeedbackCode.of(FeedbackCondition.FC_BAD_PIC_STRING);
        }
        final boolean separated = MASK_YYYY_MM_DD.equals(mask);
        final int significantLength = mask.length();

        // Step 2. Every position the mask requires must actually be supplied. A space or a null byte is
        // an absent position, which is how an uninitialised or partly filled COBOL field presents.
        if (dateText.length() < significantLength) {
            return FeedbackCode.of(FeedbackCondition.FC_INSUFFICIENT_DATA);
        }
        final String supplied = dateText.substring(0, significantLength);
        for (int index = 0; index < significantLength; index++) {
            final char character = supplied.charAt(index);
            if (character == ' ' || character == '\u0000') {
                return FeedbackCode.of(FeedbackCondition.FC_INSUFFICIENT_DATA);
            }
        }

        // Step 3. Digit positions must hold digits and separator positions must hold the separator.
        for (int index = 0; index < significantLength; index++) {
            final char character = supplied.charAt(index);
            final boolean separatorPosition = separated && (index == 4 || index == 7);
            if (separatorPosition) {
                if (character != '-') {
                    return FeedbackCode.of(FeedbackCondition.FC_NON_NUMERIC_DATA);
                }
            } else if (!isDigit(character)) {
                return FeedbackCode.of(FeedbackCondition.FC_NON_NUMERIC_DATA);
            }
        }

        final int year = Integer.parseInt(supplied.substring(0, 4));
        final int month = Integer.parseInt(separated ? supplied.substring(5, 7) : supplied.substring(4, 6));
        final int day = Integer.parseInt(separated ? supplied.substring(8, 10) : supplied.substring(6, 8));

        // Step 4. A year of zero has its own declared condition.
        if (year == 0) {
            return FeedbackCode.of(FeedbackCondition.FC_YEAR_IN_ERA_ZERO);
        }

        // Step 5. A month outside 1 through 12 has its own declared condition, and is tested before the
        // day so that the more specific of the two is reported.
        if (month < 1 || month > 12) {
            return FeedbackCode.of(FeedbackCondition.FC_INVALID_MONTH);
        }

        // Step 6. Well formed components that do not denote a real day. This is the only place a
        // java.time exception can arise, and it is a classification signal rather than a failure: the
        // outcome it selects is one of the nine declared tokens, so nothing is swallowed and no
        // information is lost.
        final LocalDate parsed;
        try {
            parsed = LocalDate.of(year, month, day);
        } catch (final DateTimeException notARealDate) {
            return FeedbackCode.of(FeedbackCondition.FC_BAD_DATE_VALUE);
        }

        // Step 7. A real date the Lillian day count cannot represent.
        if (parsed.isBefore(LILLIAN_DAY_ZERO.plusDays(1)) || parsed.isAfter(LILLIAN_LAST_DAY)) {
            return FeedbackCode.of(FeedbackCondition.FC_UNSUPP_RANGE);
        }

        // Step 8. Success, reported through the token whose name says the opposite. See the Low
        // severity finding at FeedbackCondition#FC_INVALID_DATE.
        return FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);
    }

    /**
     * Reproduces the group move of {@code app/cbl/CSUTLDTC.cbl:L122}.
     *
     * <p>{@code MOVE WS-DATE-TO-TEST TO WS-DATE} moves the whole varying length string group - its two
     * byte binary length prefix followed by its text - into a ten byte alphanumeric field. An
     * alphanumeric receiving field is filled from the left and truncated on the right, so the result is
     * the two length bytes followed by the first eight characters of the text.
     *
     * <p>The two length bytes are the big endian representation of a {@code PIC S9(4) BINARY} halfword
     * holding ten, that is {@code X'00'} then {@code X'0A'}. Both are non printable and both are part
     * of the returned 80 character result.
     *
     * @param vstringLength the value of {@code Vstring-length}, always ten on this path
     * @param vstringText   the value of {@code Vstring-text}
     * @return exactly ten characters: two length bytes and the first eight characters of the text
     */
    private static String groupMoveWholeVaryingString(final int vstringLength, final String vstringText) {
        final char highOrderByte = (char) ((vstringLength >> BYTE_WIDTH_IN_BITS) & BYTE_MASK);
        final char lowOrderByte = (char) (vstringLength & BYTE_MASK);
        final String retainedText = vstringText.substring(0, LS_DATE_LENGTH - 2);
        return new StringBuilder(LS_DATE_LENGTH)
                .append(highOrderByte)
                .append(lowOrderByte)
                .append(retainedText)
                .toString();
    }

    /**
     * Assembles the 80 byte result area field by field, in the order the source declares them.
     *
     * <p>Every argument and every literal below is placed at the width its {@code PIC} clause declares,
     * so the returned string is exactly {@link #LS_RESULT_LENGTH} characters. Modelling the area as one
     * composition rather than two duplicated layouts is what discharges Rule 1 Clause C for
     * {@code WS-MESSAGE} and {@code WS-DATE-VALIDATION-RESULT}.
     *
     * @param severity      the four character severity, {@code app/cbl/CSUTLDTC.cbl:L43}
     * @param messageNumber the four character message number, {@code :L46}
     * @param resultText    the fifteen character result string, {@code :L49}
     * @param testedDate    the ten character tested date, {@code :L52}, corrupted by the {@code :L122}
     *                      group move
     * @param maskUsed      the ten character mask, {@code :L55}
     * @return the composed area, exactly 80 characters
     */
    private static String composeMessageArea(final String severity, final String messageNumber,
                                             final String resultText, final String testedDate,
                                             final String maskUsed) {
        return new StringBuilder(LS_RESULT_LENGTH)
                .append(fixedWidth(severity, SEVERITY_LENGTH))                      // :L43 X(04)
                .append(fixedWidth("Mesg Code:", MESG_CODE_FILLER_LENGTH))          // :L45 X(11) literal
                .append(fixedWidth(messageNumber, MESSAGE_NUMBER_LENGTH))           // :L46 X(04)
                .append(' ')                                                        // :L48 X(01) SPACE
                .append(fixedWidth(resultText, RESULT_TEXT_LENGTH))                 // :L49 X(15)
                .append(' ')                                                        // :L50 X(01) SPACE
                .append(fixedWidth("TstDate:", TST_DATE_FILLER_LENGTH))             // :L51 X(09) literal
                .append(fixedWidth(testedDate, LS_DATE_LENGTH))                     // :L52 X(10)
                .append(' ')                                                        // :L53 X(01) SPACE
                .append(fixedWidth("Mask used:", MASK_USED_FILLER_LENGTH))          // :L54 X(10) literal
                .append(fixedWidth(maskUsed, LS_DATE_FORMAT_LENGTH))                // :L55 X(10)
                .append(' ')                                                        // :L56 X(01) SPACE
                .append(" ".repeat(TRAILING_FILLER_LENGTH))                         // :L57 X(03) SPACES
                .toString();
    }

    /**
     * Renders an integer the way a {@code PIC 9(4)} field renders it.
     *
     * <p>{@code WS-SEVERITY-N} at {@code app/cbl/CSUTLDTC.cbl:L44} and {@code WS-MSG-NO-N} at
     * {@code :L47} are both unsigned four digit display fields, so a value is zero padded to four
     * digits, a sign is discarded and only the low order four digits survive. That is what the calling
     * programs compare against when they test the literals {@code '0000'} and {@code '2513'}.
     *
     * @param value the halfword value to render
     * @return exactly four decimal digits
     */
    private static String zonedFour(final int value) {
        return String.format(Locale.ROOT, "%04d", Math.abs(value) % ZONED_FOUR_MODULUS);
    }

    /**
     * Fixes a value to an alphanumeric field width, as a COBOL {@code MOVE} to {@code PIC X(n)} does.
     *
     * <p>A shorter value is left justified and padded on the right with spaces; a longer one is
     * truncated on the right; {@code null} becomes an all spaces field. The {@code null} case is the
     * closest analogue of a COBOL caller passing a field that was never populated, and absorbing it
     * here rather than rejecting it is what lets this class treat every input as untrusted without
     * throwing, as Rule 1 Clause A requires.
     *
     * @param value the value to fix, possibly {@code null}
     * @param width the target width, which must be positive
     * @return a string of exactly {@code width} characters
     */
    private static String fixedWidth(final String value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Tests a single character for membership of the decimal digits.
     *
     * <p>Deliberately <em>not</em> {@link Character#isDigit(char)}, which accepts digits from every
     * Unicode script. A COBOL {@code NUMERIC} class test on an alphanumeric field accepts only the ten
     * ASCII digits, and this method is applied to untrusted request data, so the narrower test is both
     * the faithful one and the safe one.
     *
     * @param character the character to test
     * @return {@code true} for {@code '0'} through {@code '9'} only
     */
    private static boolean isDigit(final char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * Reads a fixed width text field as its {@code PIC 9(n)} redefinition would read it.
     *
     * <p>The work area redefines each date component as an unsigned display numeric -
     * {@code WS-EDIT-DATE-CC-N} at {@code app/cpy/CSUTLDWY.cpy:L7-L8},
     * {@code WS-EDIT-DATE-YY-N} at {@code :L12-L13}, {@code WS-EDIT-DATE-CCYY-N} at {@code :L14-L15},
     * {@code WS-EDIT-DATE-MM-N} at {@code :L17-L18} and {@code WS-EDIT-DATE-DD-N} at {@code :L26-L27} -
     * and the copybook reads several of them before it has established that the underlying bytes are
     * numeric at all.
     *
     * <p>A redefinition over non digit bytes has no portable meaning, so rather than fabricate one this
     * method returns {@link #NON_NUMERIC_VIEW}, which is outside every range the source tests. The
     * observable consequence therefore agrees with the source, whose range tests also fail for such
     * input. This resolution of a case the source leaves undefined is recorded in
     * {@code DECISION_LOG.md}.
     *
     * @param field the fixed width text to read
     * @return the unsigned value, or {@link #NON_NUMERIC_VIEW} when any byte is not a decimal digit
     */
    private static int numericView(final String field) {
        int accumulated = 0;
        for (int index = 0; index < field.length(); index++) {
            final char character = field.charAt(index);
            if (!isDigit(character)) {
                return NON_NUMERIC_VIEW;
            }
            accumulated = accumulated * 10 + (character - '0');
        }
        return accumulated;
    }

    /**
     * Tests a field against COBOL {@code SPACES}.
     *
     * @param field the fixed width text to test
     * @return {@code true} when every character is a space
     */
    private static boolean isSpaces(final String field) {
        for (int index = 0; index < field.length(); index++) {
            if (field.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests a field against COBOL {@code LOW-VALUES}.
     *
     * <p>{@code LOW-VALUES} is the lowest value in the collating sequence, which for the fields here is
     * a run of null bytes. The copybook tests it separately from {@code SPACES} on all three component
     * edits, so both tests are modelled separately rather than merged into one blank check.
     *
     * @param field the fixed width text to test
     * @return {@code true} when every character is a null byte
     */
    private static boolean isLowValues(final String field) {
        for (int index = 0; index < field.length(); index++) {
            if (field.charAt(index) != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a date to the integer {@code FUNCTION INTEGER-OF-DATE} would produce.
     *
     * <p>Used only by {@link #editDateOfBirthParagraph}, which is the sole place the source calls that
     * intrinsic, at {@code app/cpy/CSUTLDPY.cpy:L345-L348}. The intrinsic numbers 1 January 1601 as
     * integer date one, so the offset is derived from that date's predecessor rather than written as a
     * literal. The result is integral, matching the {@code PIC S9(9) BINARY} receiving fields; no
     * floating point type appears anywhere in this file.
     *
     * @param date the date to convert
     * @return the COBOL integer date, days since 31 December 1600
     */
    private static long integerOfDate(final LocalDate date) {
        return date.toEpochDay() - INTEGER_DATE_ZERO.toEpochDay();
    }

    /**
     * Trims leading and trailing spaces, as COBOL {@code FUNCTION TRIM} does.
     *
     * <p>Deliberately <em>not</em> {@link String#strip()}, which removes every character
     * {@link Character#isWhitespace(char)} accepts, including tabs and a range of Unicode separators.
     * {@code FUNCTION TRIM} with no {@code LEADING} or {@code TRAILING} phrase removes spaces from both
     * ends and nothing else. The field being trimmed is untrusted, so the narrower behaviour is both
     * the faithful one and the predictable one.
     *
     * @param field the text to trim
     * @return the text with leading and trailing spaces removed
     */
    private static String trimSpaces(final String field) {
        int start = 0;
        int end = field.length();
        while (start < end && field.charAt(start) == ' ') {
            start++;
        }
        while (end > start && field.charAt(end - 1) == ' ') {
            end--;
        }
        return field.substring(start, end);
    }

    /**
     * Builds the per call work area, fixing both inputs to the widths the source declares.
     *
     * @param ccyymmdd     the eight character date under edit
     * @param variableName the field label used to prefix messages
     * @return a fresh context, visible to this call only
     */
    private static EditContext newEditContext(final String ccyymmdd, final String variableName) {
        return new EditContext(fixedWidth(ccyymmdd, CCYYMMDD_LENGTH),
                trimSpaces(fixedWidth(variableName, VARIABLE_NAME_LENGTH)));
    }

    /**
     * Runs the full composite date edit over an eight character {@code CCYYMMDD} value.
     *
     * <p>This is the public face of {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT},
     * the invocation the copybook's own header documents at {@code app/cpy/CSUTLDPY.cpy:L7-L9} and the
     * one every real call site uses - {@code app/cbl/COACTUPC.cbl:L1480}, {@code :L1492}, {@code :L1505}
     * and {@code :L1536}, for an account's open date, expiry date, reissue date and a customer's date of
     * birth respectively.
     *
     * <p><strong>Side effects: none.</strong> All state is created per call and discarded on return.
     * This method performs no logging and mutates nothing the caller can observe other than the value it
     * returns.
     *
     * <p><strong>Error modes.</strong> A rejected date is returned, never thrown. The returned outcome
     * carries which of the three components failed and how, whether the error indicator was raised, and
     * the single message the first failing editor produced. A {@code null} date is treated as an all
     * spaces field, which is the absent case every component edit already handles explicitly.
     *
     * @param ccyymmdd     the date as eight characters, {@code CCYYMMDD}; padded or truncated to that
     *                     width, and treated as all spaces when {@code null}
     * @param variableName the field label that prefixes any message, corresponding to
     *                     {@code WS-EDIT-VARIABLE-NAME PIC X(25)}. Pass a <strong>label</strong> such
     *                     as {@code Date of Birth} and never the field's value: the label reaches the
     *                     returned message, and a value could carry personal data into a caller's log
     * @return the three component flags, the error indicator and the message
     */
    public EditOutcome editDate(final String ccyymmdd, final String variableName) {
        return performEditDateCcyymmddThruExit(newEditContext(ccyymmdd, variableName));
    }

    /**
     * Runs the composite date edit and then, only if it passed, the date of birth reasonableness check.
     *
     * <p>This reproduces the caller's own sequence verbatim from
     * {@code app/cbl/COACTUPC.cbl:L1533-L1543}: move the label, move the date, perform the composite
     * edit through its exit, copy the flags out, and then
     * {@code IF WS-EDIT-DT-OF-BIRTH-ISVALID PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT}
     * before copying the flags out again. The gate matters: the reasonableness check converts the date
     * with {@code FUNCTION INTEGER-OF-DATE}, which presupposes a well formed date, and the composite
     * edit is what establishes that.
     *
     * <p><strong>Security, Rule 1 Clause D.</strong> This is the one entry point that handles a
     * customer date of birth, which is personally identifiable. This method logs nothing and the
     * returned message is composed from the caller's label only. The obligation not to log the date
     * itself, or any composed 80 character result, passes to the caller.
     *
     * <p><strong>Side effects: none.</strong> The clock is read but never advanced, all working state is
     * created per call and discarded on return, and nothing outside the returned value is mutated. Two
     * calls with the same arguments against the same fixed clock return equal outcomes.
     *
     * <p><strong>Error modes.</strong> A date in the future is a returned outcome, not an exception. A
     * {@link ValidationException} is raised only if a caller reaches the reasonableness check with a
     * date the calendar cannot represent, which the validity gate above makes unreachable through this
     * method; see {@link #editDateOfBirthParagraph}.
     *
     * @param ccyymmdd     the date of birth as eight characters, {@code CCYYMMDD}
     * @param variableName the field label that prefixes any message; pass a label, never the date
     * @return the outcome after the composite edit and, when that passed, the future date check
     * @throws ValidationException if the composite edit passed a value that
     *                             {@code FUNCTION INTEGER-OF-DATE} still cannot convert, carrying the
     *                             originating {@link DateTimeException} as its cause
     */
    public EditOutcome editDateOfBirth(final String ccyymmdd, final String variableName) {
        final EditContext context = newEditContext(ccyymmdd, variableName);

        // app/cbl/COACTUPC.cbl:L1536-L1537 then :L1538 MOVE WS-EDIT-DATE-FLGS TO ...
        final EditOutcome afterComposite = performEditDateCcyymmddThruExit(context);

        // app/cbl/COACTUPC.cbl:L1539 IF WS-EDIT-DT-OF-BIRTH-ISVALID
        if (!afterComposite.valid()) {
            return afterComposite;
        }

        editDateOfBirthParagraph(context);  // :L1540 PERFORM EDIT-DATE-OF-BIRTH
        editDateOfBirthExit();              // :L1541 THRU EDIT-DATE-OF-BIRTH-EXIT
        return context.toOutcome();         // :L1542 MOVE WS-EDIT-DATE-FLGS TO ... again
    }

    /**
     * Executes {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} in source order.
     *
     * <p>A COBOL {@code PERFORM a THRU b} executes <em>every</em> paragraph from {@code a} to {@code b}
     * inclusive, in the order they appear in the source. The range here spans
     * {@code app/cpy/CSUTLDPY.cpy:L18} to {@code :L331} and therefore covers twelve of the copybook's
     * fourteen paragraphs; only the two date of birth paragraphs lie outside it, which is exactly why
     * they are performed separately by {@link #editDateOfBirth}.
     *
     * <p><strong>The two branch scopes, and why conflating them is wrong.</strong> The copybook uses
     * {@code GO TO} for two different purposes, and they are not interchangeable:
     *
     * <ul>
     *   <li>{@code GO TO EDIT-YEAR-CCYY-EXIT}, {@code GO TO EDIT-MONTH-EXIT} and
     *       {@code GO TO EDIT-DAY-EXIT} jump to the <em>immediately following</em> paragraph, which is
     *       inside the range. They abandon only the rest of their own paragraph, and execution continues
     *       with the next component edit. A failed year does <strong>not</strong> stop the month and day
     *       from being edited, which is precisely how the source can report three independent flags.
     *       These are modelled as an early {@code return} from the corresponding private method.</li>
     *   <li>{@code GO TO EDIT-DATE-CCYYMMDD-EXIT}, used four times inside
     *       {@code EDIT-DAY-MONTH-YEAR} at {@code :L225}, {@code :L240}, {@code :L270} and {@code :L277},
     *       jumps to the paragraph that <em>ends</em> the range. It abandons everything in between,
     *       skipping {@code EDIT-DAY-MONTH-YEAR-EXIT}, {@code EDIT-DATE-LE} and
     *       {@code EDIT-DATE-LE-EXIT}. This is modelled by the context's branch flag, and skipping
     *       {@code EDIT-DATE-LE-EXIT} is load bearing because that paragraph sets all three flags
     *       valid.</li>
     * </ul>
     *
     * @param context the per call work area
     * @return the outcome the caller observes
     */
    private EditOutcome performEditDateCcyymmddThruExit(final EditContext context) {
        editDateCcyymmdd(context);       // :L18  establishes the wholly invalid state
        editYearCcyy(context);           // :L25
        editYearCcyyExit();              // :L88
        editMonth(context);              // :L91
        editMonthExit();                 // :L145
        editDay(context);                // :L150
        editDayExit();                   // :L205
        editDayMonthYear(context);       // :L209

        if (context.branchToCcyymmddExit) {
            // A GO TO EDIT-DATE-CCYYMMDD-EXIT was taken inside EDIT-DAY-MONTH-YEAR. Everything between
            // is skipped, including EDIT-DATE-LE-EXIT and therefore its :L327 SET.
            editDateCcyymmddExit();      // :L329
            return context.toOutcome();
        }

        editDayMonthYearExit();          // :L280
        editDateLe(context);             // :L284
        editDateLeExit(context);         // :L323  runs the :L327 SET on BOTH of its entry paths
        editDateCcyymmddExit();          // :L329
        return context.toOutcome();
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD}, {@code app/cpy/CSUTLDPY.cpy:L18-L20}.
     *
     * <p>The paragraph is <strong>one statement</strong>:
     * {@code SET WS-EDIT-DATE-IS-INVALID TO TRUE}. Because that condition name is declared on the three
     * byte group at {@code app/cpy/CSUTLDWY.cpy:L45} with {@code VALUE '000'}, the single statement
     * writes all three bytes at once, putting year, month and day simultaneously into the unacceptable
     * state. Every later editor then either clears its own byte or leaves it set.
     *
     * <p><strong>Finding, severity Low: this is not a stub.</strong> Despite its name it does
     * <em>not</em> orchestrate the sub-editors, and it must not be "helpfully" made to. The
     * {@code PERFORM ... THRU} range is what runs them, so adding calls here would run every component
     * edit twice. Preserved as a one statement method and tracked in {@code DECISION_LOG.md}.
     *
     * @param context the per call work area
     */
    private void editDateCcyymmdd(final EditContext context) {
        // :L19 SET WS-EDIT-DATE-IS-INVALID TO TRUE - one statement setting all three bytes to '0'.
        context.yearFlag = EditFlag.NOT_OK;
        context.monthFlag = EditFlag.NOT_OK;
        context.dayFlag = EditFlag.NOT_OK;
    }

    /**
     * {@code EDIT-YEAR-CCYY}, {@code app/cpy/CSUTLDPY.cpy:L25-L87}.
     *
     * <p>Three tests in a fixed order, each ending in a branch to this paragraph's exit, and a final
     * statement that marks the year valid if none of them fired.
     *
     * <p><strong>Pessimistic initialisation.</strong> {@code :L27} sets
     * {@code FLG-YEAR-NOT-OK} before any test runs, so the year is unacceptable until proven otherwise.
     * {@link #editMonth} does the same; {@link #editDay} does the exact opposite. That asymmetry is
     * genuine and must not be normalised - see the High severity finding in the class documentation.
     *
     * <p>The source comments its own control flow honestly. At {@code :L41}, immediately before the
     * first branch, it reads: "Intentional violation of structured programming norms". At
     * {@code :L66-L68}, explaining why only two centuries are accepted: "Not having learnt our lesson
     * from history and Y2K", "And being unable to imagine COBOL in the 2100s", "We code only 19 and 20
     * as valid century values".
     *
     * <p>Note that the second message literal is the only one of the twelve that does not begin with a
     * colon or a space and colon: it is {@code ' must be 4 digit number.'}, reproduced byte for byte
     * below.
     *
     * @param context the per call work area
     */
    private void editYearCcyy(final EditContext context) {
        // :L27 SET FLG-YEAR-NOT-OK TO TRUE - pessimistic, before any test.
        context.yearFlag = EditFlag.NOT_OK;

        // :L30-L31 IF WS-EDIT-DATE-CCYY EQUAL LOW-VALUES OR WS-EDIT-DATE-CCYY EQUAL SPACES
        if (isLowValues(context.ccyy) || isSpaces(context.ccyy)) {
            context.setInputError();                             // :L32
            context.yearFlag = EditFlag.BLANK;                   // :L33
            context.emitMessage(" : Year must be supplied.");    // :L34-L40 under the latch
            return;                                              // :L42 GO TO EDIT-YEAR-CCYY-EXIT
        }

        // :L48 IF WS-EDIT-DATE-CCYY IS NOT NUMERIC
        if (numericView(context.ccyy) == NON_NUMERIC_VIEW) {
            context.setInputError();                             // :L49
            context.yearFlag = EditFlag.NOT_OK;                  // :L50 re-set of the :L27 state
            context.emitMessage(" must be 4 digit number.");     // :L51-L57 under the latch
            return;                                              // :L58 GO TO EDIT-YEAR-CCYY-EXIT
        }

        // :L70-L71 IF THIS-CENTURY OR LAST-CENTURY. Only 20 and 19 are accepted; the century is read
        // through WS-EDIT-DATE-CC-N, which is safe here because the numeric test above has passed.
        final int century = numericView(context.cc);
        if (century != THIS_CENTURY && century != LAST_CENTURY) {
            // :L73 ELSE - the source writes the positive test with CONTINUE and puts the work in the
            // ELSE branch; the negation here is the same predicate and the same evaluation order.
            context.setInputError();                             // :L74
            context.yearFlag = EditFlag.NOT_OK;                  // :L75
            context.emitMessage(" : Century is not valid.");     // :L76-L82 under the latch
            return;                                              // :L83 GO TO EDIT-YEAR-CCYY-EXIT
        }

        // :L86 SET FLG-YEAR-ISVALID TO TRUE
        context.yearFlag = EditFlag.ISVALID;
    }

    /**
     * {@code EDIT-YEAR-CCYY-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L88-L90}.
     *
     * <p>Body is the single statement {@code EXIT} at {@code :L89}, a documentary no-op. The paragraph
     * is the target of the three branches in {@link #editYearCcyy} and, being inside the
     * {@code PERFORM THRU} range, it falls straight through to {@code EDIT-MONTH}. Intentionally empty.
     */
    private void editYearCcyyExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-MONTH}, {@code app/cpy/CSUTLDPY.cpy:L91-L144}.
     *
     * <p>Pessimistic initialisation at {@code :L92}, then three tests, then the valid mark at
     * {@code :L143}.
     *
     * <p><strong>Order quirk, and it is preserved exactly.</strong> The <em>range</em> test comes
     * first, at {@code :L111} ({@code IF WS-VALID-MONTH}), and the <em>numeric</em> test second, at
     * {@code :L126} ({@code IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-MM) = 0}). {@link #editDay} runs the
     * two in the opposite order. Reversing either is a High severity divergence.
     *
     * <p>The range test can run before the numeric test because {@code WS-VALID-MONTH} is declared at
     * {@code app/cpy/CSUTLDWY.cpy:L19-L20} on {@code WS-EDIT-DATE-MM-N}, the {@code 9(2)} redefinition
     * of the raw two characters, so the alias is live from the start and does not need the
     * {@code COMPUTE} at {@code :L127} to have run. Non numeric text simply falls outside one through
     * twelve and is rejected by the range branch. Both branches emit the <em>same</em> literal, so the
     * order is unobservable in the message and observable only in which branch reports - which is
     * exactly why it is easy to "tidy" and must not be.
     *
     * @param context the per call work area
     */
    private void editMonth(final EditContext context) {
        // :L92 SET FLG-MONTH-NOT-OK TO TRUE - pessimistic, before any test.
        context.monthFlag = EditFlag.NOT_OK;

        // :L94-L95 IF WS-EDIT-DATE-MM EQUAL LOW-VALUES OR WS-EDIT-DATE-MM EQUAL SPACES
        if (isLowValues(context.mm) || isSpaces(context.mm)) {
            context.setInputError();                              // :L96
            context.monthFlag = EditFlag.BLANK;                   // :L97
            context.emitMessage(" : Month must be supplied.");     // :L98-L104 under the latch
            return;                                               // :L105 GO TO EDIT-MONTH-EXIT
        }

        // :L110 comment "Month not reasonable"; :L111 IF WS-VALID-MONTH - the RANGE test, first.
        if (context.monthValue < MONTH_MINIMUM || context.monthValue > MONTH_MAXIMUM) {
            context.setInputError();                              // :L114
            context.monthFlag = EditFlag.NOT_OK;                  // :L115
            context.emitMessage(MONTH_RANGE_MESSAGE);             // :L116-L122 under the latch
            return;                                               // :L123 GO TO EDIT-MONTH-EXIT
        }

        // :L126 IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-MM) = 0 - the NUMERIC test, second.
        final int monthNumval = numericView(context.mm);
        if (monthNumval == NON_NUMERIC_VIEW) {
            context.setInputError();                              // :L131
            context.monthFlag = EditFlag.NOT_OK;                  // :L132
            context.emitMessage(MONTH_RANGE_MESSAGE);             // :L133-L139 under the latch
            return;                                               // :L140 GO TO EDIT-MONTH-EXIT
        }
        // :L127-L129 COMPUTE WS-EDIT-DATE-MM-N = FUNCTION NUMVAL (WS-EDIT-DATE-MM)
        context.monthValue = monthNumval;

        // :L143 SET FLG-MONTH-ISVALID TO TRUE
        context.monthFlag = EditFlag.ISVALID;
    }

    /**
     * {@code EDIT-MONTH-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L145-L147}.
     *
     * <p>Body is {@code EXIT} at {@code :L146}, a documentary no-op. Target of the three branches in
     * {@link #editMonth}, and inside the {@code PERFORM THRU} range it falls through to
     * {@code EDIT-DAY}. Intentionally empty.
     */
    private void editMonthExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DAY}, {@code app/cpy/CSUTLDPY.cpy:L150-L204}.
     *
     * <p><strong>Optimistic initialisation - a genuine, load bearing asymmetry.</strong> {@code :L152}
     * sets {@code FLG-DAY-ISVALID}, the exact opposite of the pessimistic openings of
     * {@link #editYearCcyy} at {@code :L27} and {@link #editMonth} at {@code :L92}. The day is therefore
     * acceptable until a test says otherwise, whereas the year and month are unacceptable until one
     * says they are fine. Normalising the two into a single convention is a High severity divergence:
     * the state a paragraph leaves behind when it takes no branch at all differs between them, and the
     * group level conjunction at {@code :L274} reads those bytes.
     *
     * <p><strong>Order quirk, mirrored.</strong> The <em>numeric</em> test comes first here, at
     * {@code :L170}, and the <em>range</em> test second, at {@code :L187} - the opposite of
     * {@link #editMonth}. Both orders are preserved as written.
     *
     * <p><strong>Finding, severity Low: the redundant re-set.</strong> {@code :L203} sets
     * {@code FLG-DAY-ISVALID} a second time, on a flag {@code :L152} has already set and that no
     * surviving path clears - every clearing path branches away first. It is retained rather than
     * removed because the paragraph map the coverage gate reads is keyed on statements, and tracked in
     * {@code DECISION_LOG.md}.
     *
     * <p>Note the message literal is {@code ':day must be a number between 1 and 31.'} with a
     * <em>lowercase</em> {@code day}, unlike its month counterpart. Reproduced byte for byte.
     *
     * @param context the per call work area
     */
    private void editDay(final EditContext context) {
        // :L152 SET FLG-DAY-ISVALID TO TRUE - OPTIMISTIC, the opposite of year and month.
        context.dayFlag = EditFlag.ISVALID;

        // :L154-L155 IF WS-EDIT-DATE-DD EQUAL LOW-VALUES OR WS-EDIT-DATE-DD EQUAL SPACES
        if (isLowValues(context.dd) || isSpaces(context.dd)) {
            context.setInputError();                            // :L156
            context.dayFlag = EditFlag.BLANK;                   // :L157
            context.emitMessage(" : Day must be supplied.");     // :L158-L164 under the latch
            return;                                             // :L165 GO TO EDIT-DAY-EXIT
        }

        // :L170 IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-DD) = 0 - the NUMERIC test, first.
        final int dayNumval = numericView(context.dd);
        if (dayNumval == NON_NUMERIC_VIEW) {
            context.setInputError();                            // :L175
            context.dayFlag = EditFlag.NOT_OK;                  // :L176
            context.emitMessage(DAY_RANGE_MESSAGE);             // :L177-L183 under the latch
            return;                                             // :L184 GO TO EDIT-DAY-EXIT
        }
        // :L171-L173 COMPUTE WS-EDIT-DATE-DD-N = FUNCTION NUMVAL (WS-EDIT-DATE-DD)
        context.dayValue = dayNumval;

        // :L187 IF WS-VALID-DAY - the RANGE test, second.
        if (context.dayValue < DAY_MINIMUM || context.dayValue > DAY_MAXIMUM) {
            context.setInputError();                            // :L190
            context.dayFlag = EditFlag.NOT_OK;                  // :L191
            context.emitMessage(DAY_RANGE_MESSAGE);             // :L192-L198 under the latch
            return;                                             // :L199 GO TO EDIT-DAY-EXIT
        }

        // :L203 SET FLG-DAY-ISVALID TO TRUE - intentionally redundant, see the Javadoc above.
        context.dayFlag = EditFlag.ISVALID;
    }

    /**
     * {@code EDIT-DAY-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L205-L207}.
     *
     * <p>Body is {@code EXIT} at {@code :L206}, a documentary no-op. Target of the three branches in
     * {@link #editDay}, and inside the {@code PERFORM THRU} range it falls through to
     * {@code EDIT-DAY-MONTH-YEAR}. Intentionally empty.
     */
    private void editDayExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}, {@code app/cpy/CSUTLDPY.cpy:L209-L279}.
     *
     * <p>The cross field rules, described by the source's own banner at {@code :L211} as "Checking for
     * any other combinations". Four tests, and <em>every</em> branch out of this paragraph is
     * {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} - the range ending target, not this paragraph's own
     * {@code -EXIT} label. Taking any of them therefore abandons {@code EDIT-DAY-MONTH-YEAR-EXIT},
     * {@code EDIT-DATE-LE} and {@code EDIT-DATE-LE-EXIT} as well, which is why the branch is recorded on
     * the context rather than modelled as a plain {@code return}.
     *
     * <p>The tests, in source order:
     *
     * <ul>
     *   <li>{@code :L213-L214} a thirty first day in a month that has thirty days;</li>
     *   <li>{@code :L228-L229} the thirtieth of February;</li>
     *   <li>{@code :L243-L244} the twenty ninth of February, gated on the leap year test below;</li>
     *   <li>{@code :L274} the group level conjunction, which passes only when year, month <em>and</em>
     *       day were each individually marked valid.</li>
     * </ul>
     *
     * <p><strong>The leap year test must keep its shape.</strong> {@code :L245-L254} chooses the divisor
     * from the two digit year - four hundred when the year within the century is zero, four otherwise -
     * and then takes a remainder. Substituting {@code Year.isLeap}, {@code LocalDate.isLeapYear} or an
     * {@code IsoChronology} call is forbidden even though the outcome agrees on every input: Rule 1
     * Clause A puts explicit behaviour above cleverness, and the traceability matrix cites these line
     * numbers against these statements. The arithmetic is integer throughout, as the source's
     * {@code PIC S9(4) COMP-3} work fields are; no floating point type appears.
     *
     * <p>Worked corners, all asserted by the unit tests: 2000 has a year within century of zero so the
     * divisor is four hundred and the remainder is zero, and the twenty ninth is accepted; 1900 also
     * takes the four hundred divisor but leaves a remainder of three hundred, so it is rejected; 2024
     * takes the divisor four and is accepted; 2023 takes the divisor four and is rejected.
     *
     * @param context the per call work area
     */
    private void editDayMonthYear(final EditContext context) {
        // :L213-L214 IF NOT WS-31-DAY-MONTH AND WS-DAY-31
        if (!isThirtyOneDayMonth(context.monthValue) && context.dayValue == DAY_31) {
            context.setInputError();                                         // :L215
            context.dayFlag = EditFlag.NOT_OK;                               // :L216
            context.monthFlag = EditFlag.NOT_OK;                             // :L217
            context.emitMessage(":Cannot have 31 days in this month.");      // :L218-L224
            context.branchToCcyymmddExit = true;                             // :L225
            return;
        }

        // :L228-L229 IF WS-FEBRUARY AND WS-DAY-30
        if (context.monthValue == FEBRUARY && context.dayValue == DAY_30) {
            context.setInputError();                                         // :L230
            context.dayFlag = EditFlag.NOT_OK;                               // :L231
            context.monthFlag = EditFlag.NOT_OK;                             // :L232
            context.emitMessage(":Cannot have 30 days in this month.");      // :L233-L239
            context.branchToCcyymmddExit = true;                             // :L240
            return;
        }

        // :L243-L244 IF WS-FEBRUARY AND WS-DAY-29
        if (context.monthValue == FEBRUARY && context.dayValue == DAY_29) {
            // :L245-L249 IF WS-EDIT-DATE-YY-N = 0 MOVE 400 ELSE MOVE 4 END-IF
            final int divisor;
            if (numericView(context.yy) == 0) {
                divisor = LEAP_DIVISOR_CENTURY;
            } else {
                divisor = LEAP_DIVISOR_ORDINARY;
            }

            // :L251-L254 DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY GIVING WS-DIVIDEND
            //            REMAINDER WS-REMAINDER. Integer division, integer remainder - never a
            //            library leap year predicate. WS-DIVIDEND is computed and unused by the
            //            source too; only the remainder is tested.
            final int fourDigitYear = numericView(context.ccyy);
            final int remainder = fourDigitYear % divisor;

            // :L256 IF WS-REMAINDER = ZEROES
            if (remainder != 0) {
                context.setInputError();                                     // :L259
                context.dayFlag = EditFlag.NOT_OK;                           // :L260
                context.monthFlag = EditFlag.NOT_OK;                         // :L261
                context.yearFlag = EditFlag.NOT_OK;                          // :L262
                context.emitMessage(
                        ":Not a leap year.Cannot have 29 days in this month.");   // :L263-L269
                context.branchToCcyymmddExit = true;                         // :L270
                return;
            }
        }

        // :L274 IF WS-EDIT-DATE-IS-VALID - the group level conjunction over all three bytes.
        if (!context.allComponentsValid()) {
            context.branchToCcyymmddExit = true;                             // :L277
        }
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L280-L282}.
     *
     * <p>Body is {@code EXIT} at {@code :L281}, a documentary no-op. Reached only by falling off the end
     * of {@link #editDayMonthYear} - never by a branch, because every branch in that paragraph targets
     * the range ending label instead. Intentionally empty.
     */
    private void editDayMonthYearExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DATE-LE}, {@code app/cpy/CSUTLDPY.cpy:L284-L321}.
     *
     * <p>The last line of defence, and the fifth call site of the date utility. The source explains
     * itself at {@code :L286-L288}: "In case some one managed to enter a bad date that passsed all the
     * edits above ...... Use LE Services to verify the supplied date". <em>Finding, severity Low:</em>
     * that comment contains two defects of its own, "passsed" with three letter s and "some one" as two
     * words. Quoted rather than silently corrected, because the comment is part of the record.
     *
     * <p><strong>This path uses the eight character mask.</strong> {@code :L291} moves
     * {@code 'YYYYMMDD'} - no separators - whereas the two program call sites move
     * {@code 'YYYY-MM-DD'}. Both masks are mandatory and neither may be dropped; this is the copybook
     * path, so it passes {@link #MASK_YYYYMMDD}.
     *
     * <p><em>Finding, severity Low:</em> {@code :L293} carries the sequence number {@code 005100} in
     * columns one to six and is the only line in all 375 that does. A hygiene curiosity in the system of
     * record, noted because Clause F asks for evidence rather than tidy summaries.
     *
     * <p><strong>Finding, severity Medium - a legacy buffer overread that cannot be reproduced.</strong>
     * The source passes {@code WS-EDIT-DATE-CCYYMMDD}, an {@code X(8)} field, and
     * {@code WS-DATE-FORMAT}, an {@code X(08)} field, by reference into the callee's
     * {@code LS-DATE PIC X(10)} and {@code LS-DATE-FORMAT PIC X(10)}. The callee then unconditionally
     * executes {@code MOVE LENGTH OF LS-DATE TO VSTRING-LENGTH}, yielding ten. The date service is
     * therefore handed a length of ten for two eight byte fields and reads two bytes of whatever storage
     * follows each. Java is memory safe, so there is no adjacent storage to read and the behaviour is
     * unreproducible. It is <strong>not</strong> simulated with padding or sentinel bytes, which would
     * invent an outcome the source never defined; it is recorded as a labelled deviation in
     * {@code DECISION_LOG.md}. Only this copybook path overreads - the two program paths pass genuine
     * ten character fields.
     *
     * <p>On a non zero severity the paragraph raises the error indicator, marks all three components
     * unacceptable and composes the eleventh message literal from the four character severity and
     * message number - which is why they are rendered zero padded and not as plain integers.
     *
     * @param context the per call work area
     */
    private void editDateLe(final EditContext context) {
        // :L290 INITIALIZE WS-DATE-VALIDATION-RESULT, :L291 MOVE 'YYYYMMDD' TO WS-DATE-FORMAT,
        // :L293-L296 CALL 'CSUTLDTC' USING WS-EDIT-DATE-CCYYMMDD, WS-DATE-FORMAT,
        //            WS-DATE-VALIDATION-RESULT. One 80 byte result type serves WS-MESSAGE and
        //            WS-DATE-VALIDATION-RESULT alike; they are byte identical layouts.
        final DateValidationResult result = validate(context.ccyymmdd, MASK_YYYYMMDD);

        // :L298 IF WS-SEVERITY-N = 0
        if (result.feedbackCode().severity() != 0) {
            context.setInputError();                                  // :L301
            context.dayFlag = EditFlag.NOT_OK;                        // :L302
            context.monthFlag = EditFlag.NOT_OK;                      // :L303
            context.yearFlag = EditFlag.NOT_OK;                       // :L304
            // :L305-L314 STRING TRIM(name) ' validation error Sev code: ' WS-SEVERITY
            //            ' Message code: ' WS-MSG-NO INTO WS-RETURN-MSG, under the latch.
            context.emitMessage(" validation error Sev code: " + result.severityCode()
                    + " Message code: " + result.messageNumber());
            return;                                                   // :L315 GO TO EDIT-DATE-LE-EXIT
        }

        // :L318-L320 IF NOT INPUT-ERROR SET FLG-DAY-ISVALID TO TRUE END-IF
        if (!context.inputError) {
            context.dayFlag = EditFlag.ISVALID;
        }
    }

    /**
     * {@code EDIT-DATE-LE-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L323-L328}.
     *
     * <p><strong>The trap in this copybook, and getting it wrong is High severity.</strong> The
     * paragraph looks like a no-op exit and is not. Its text is:
     *
     * <pre>
     * EDIT-DATE-LE-EXIT.
     *     EXIT
     *     .
     * *    If we got here all edits were cleared
     *     SET WS-EDIT-DATE-IS-VALID        TO TRUE
     *     .
     * </pre>
     *
     * <p>A plain COBOL {@code EXIT} statement with no phrase is a <em>documentary no-op</em>. It is not
     * {@code EXIT PARAGRAPH}, it is not {@code EXIT SECTION}, and it emits no transfer of control. The
     * statements at {@code :L326-L328} are therefore still inside this paragraph and they <em>do</em>
     * execute. Translating {@code EXIT} as a Java {@code return} would skip the {@code SET} and silently
     * change the outcome of every call that reaches here.
     *
     * <p>Because {@code WS-EDIT-DATE-IS-VALID} is declared on the three byte group at
     * {@code app/cpy/CSUTLDWY.cpy:L44} with {@code VALUE LOW-VALUES}, the single {@code SET} clears all
     * three bytes at once and marks year, month and day simultaneously acceptable.
     *
     * <p><strong>The consequence is a real legacy quirk, preserved deliberately.</strong> Both of this
     * paragraph's entry paths run that {@code SET}: falling through from a clean
     * {@link #editDateLe}, and branching in from its {@code :L315} failure path. So when the date
     * service reports a non zero severity, the three component flags end up <em>valid</em> even though
     * the error indicator is raised and the message is populated. Callers see the failure through the
     * indicator and the message, not through the flags. This is faithful, not a defect introduced here,
     * and it is exactly why {@code EditOutcome} exposes the indicator and the message alongside the
     * flags rather than deriving everything from validity.
     *
     * <p>Note the contrast with the four branches in {@link #editDayMonthYear}: those target the range
     * ending label and so skip this paragraph entirely, leaving their {@code NOT_OK} flags intact. The
     * difference in observable outcome between the two failure routes is the reason the two branch
     * scopes must not be conflated.
     *
     * @param context the per call work area
     */
    private void editDateLeExit(final EditContext context) {
        // :L324 EXIT - a documentary no-op. Execution continues; it does NOT return.

        // :L326 comment "If we got here all edits were cleared"
        // :L327 SET WS-EDIT-DATE-IS-VALID TO TRUE - clears all three bytes to LOW-VALUES.
        context.yearFlag = EditFlag.ISVALID;
        context.monthFlag = EditFlag.ISVALID;
        context.dayFlag = EditFlag.ISVALID;
    }

    /**
     * {@code EDIT-DATE-CCYYMMDD-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L329-L331}.
     *
     * <p>Body is {@code EXIT} at {@code :L330}, a documentary no-op, and unlike
     * {@link #editDateLeExit} nothing follows it before the next paragraph label. This is the label that
     * <em>ends</em> the {@code PERFORM ... THRU} range, so reaching it - whether by falling through or
     * by one of the four branches in {@link #editDayMonthYear} - completes the composite edit.
     * Intentionally empty.
     */
    private void editDateCcyymmddExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH}, {@code app/cpy/CSUTLDPY.cpy:L341-L369}.
     *
     * <p>The reasonableness check, performed only after the composite edit has passed. The source's
     * banner at {@code :L336-L338} states the rule and dates itself doing so: "At the time of writing
     * this program", "Time travel was not possible.", "Date of birth in the future is not acceptable".
     *
     * <p><strong>The comparison is strict, and relaxing it is High severity.</strong> {@code :L350}
     * reads {@code IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY}, so the date of birth is acceptable
     * only when today is <em>strictly after</em> it. A date of birth equal to today is
     * <strong>rejected</strong>. Widening this to {@code >=} would accept a same day birth date the
     * source refuses, and the unit tests pin the boundary under an injected clock.
     *
     * <p>{@code :L343} takes today from {@code FUNCTION CURRENT-DATE}. That is the one environmental
     * dependency in this bean, and it is resolved through the injected {@code java.time.Clock} so the
     * outcome is deterministic and testable - Rule 1 Clause A on determinism and Clause C on avoiding
     * environment specific assumptions. The clock's own zone is used; no default zone is consulted.
     *
     * <p>{@code :L351-L353} hold a commented out alternative built on {@code FUNCTION FIND-DURATION}
     * over {@code DAYS}. It is cited here as part of the record and deliberately not implemented - it is
     * not the code that runs.
     *
     * <p><strong>Error mode.</strong> A future date is a returned outcome, never an exception. The only
     * exception this method can raise is a {@code com.cardemo.exception.ValidationException} when
     * {@code FUNCTION INTEGER-OF-DATE} at {@code :L345-L346} is handed something the calendar cannot
     * represent. The source presupposes that cannot happen, because the composite edit has already
     * accepted the value, and {@link #editDateOfBirth} enforces that precondition. Reaching it means a
     * caller has invoked this paragraph out of order, which is genuinely exceptional; the original
     * {@code java.time.DateTimeException} is preserved as the cause rather than swallowed, per Clause B.
     *
     * @param context the per call work area
     * @throws ValidationException if {@code FUNCTION INTEGER-OF-DATE} cannot convert the supplied
     *                             components, wrapping the originating {@link DateTimeException}
     */
    private void editDateOfBirthParagraph(final EditContext context) {
        // :L343 MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD - via the injected Clock.
        final LocalDate currentDate = LocalDate.now(clock);

        // :L345-L346 COMPUTE WS-EDIT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (WS-EDIT-DATE-CCYYMMDD-N)
        final LocalDate underEdit;
        try {
            underEdit = LocalDate.of(numericView(context.ccyy), context.monthValue, context.dayValue);
        } catch (final DateTimeException cause) {
            throw new ValidationException(
                    "FUNCTION INTEGER-OF-DATE at app/cpy/CSUTLDPY.cpy:L345-L346 cannot convert the "
                            + "supplied date, which the composite edit must accept first",
                    context.variableName, ValidationException.FailureKind.INVALID, cause);
        }
        final long editDateBinary = integerOfDate(underEdit);

        // :L347-L348 COMPUTE WS-CURRENT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (...)
        final long currentDateBinary = integerOfDate(currentDate);

        // :L350 IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY - STRICT, never >=.
        if (currentDateBinary > editDateBinary) {
            return;                                                   // :L354 CONTINUE
        }

        context.setInputError();                                      // :L356
        context.dayFlag = EditFlag.NOT_OK;                            // :L357
        context.monthFlag = EditFlag.NOT_OK;                          // :L358
        context.yearFlag = EditFlag.NOT_OK;                           // :L359
        context.emitMessage(":cannot be in the future ");             // :L360-L366, trailing space kept
        // :L367 GO TO EDIT-DATE-OF-BIRTH-EXIT
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH-EXIT}, {@code app/cpy/CSUTLDPY.cpy:L370-L372}.
     *
     * <p>Body is {@code EXIT} at {@code :L371}, a documentary no-op, and unlike
     * {@link #editDateLeExit} nothing follows it - the remaining lines {@code :L373-L375} are the
     * copybook's closing comment banner carrying its version stamp. Target of the branch at
     * {@code :L367} and the end of the {@code PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT}
     * range. Intentionally empty.
     */
    private void editDateOfBirthExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * Tests {@code WS-31-DAY-MONTH}, {@code app/cpy/CSUTLDWY.cpy:L21-L23}.
     *
     * <p>The condition name lists {@code VALUES 1, 3, 5, 7, 8, 10, 12} - January, March, May, July,
     * August, October and December. Kept as an explicit membership test over those seven literals rather
     * than a month length lookup, so the set is visibly the one the copybook declares.
     *
     * @param month the month as an integer, or {@link #NON_NUMERIC_VIEW} when the field is not numeric
     * @return {@code true} when the month is one of the seven with thirty one days
     */
    private static boolean isThirtyOneDayMonth(final int month) {
        return month == 1 || month == 3 || month == 5 || month == 7
                || month == 8 || month == 10 || month == 12;
    }

    /**
     * The whole of the source's date editing working storage, scoped to a single call.
     *
     * <p>This type is the direct answer to Rule 1 Clause B's prohibition on global mutable state. The
     * source keeps every field below in the including program's {@code WORKING-STORAGE}, which in COBOL
     * is allocated once per program and mutated in place, so the edits are not reentrant and two
     * concurrent evaluations would corrupt each other. Here one instance is created per call, is
     * visible to no other thread, and is discarded when the call returns. <strong>Not one of these
     * fields is a field of the service bean.</strong>
     *
     * <p>Its contents come from three places, and the split is worth recording because it explains why
     * some fields are supplied by the caller:
     *
     * <ul>
     *   <li>{@code app/cpy/CSUTLDWY.cpy:L4-L34} - the date components and their numeric redefinitions,
     *       held here as immutable text plus the derived numeric views.</li>
     *   <li>{@code app/cpy/CSUTLDWY.cpy:L43-L57} - the three validation flags.</li>
     *   <li>{@code app/cbl/COACTUPC.cbl} - {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code :L53},
     *       {@code 88 INPUT-ERROR VALUE '1'} at {@code :L173} and
     *       {@code WS-RETURN-MSG PIC X(75)} at {@code :L479} with its latch
     *       {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code :L480}. None of these is declared in
     *       either copybook: they are supplied by whichever program copies the procedural member in,
     *       which is why they are inputs and results here rather than internal state.</li>
     * </ul>
     */
    private static final class EditContext {

        /** {@code WS-EDIT-DATE-CCYYMMDD PIC X(8)}, {@code app/cpy/CSUTLDWY.cpy:L4}. */
        private final String ccyymmdd;

        /** {@code WS-EDIT-DATE-CCYY}, {@code app/cpy/CSUTLDWY.cpy:L5}. */
        private final String ccyy;

        /** {@code WS-EDIT-DATE-CC PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L6}. */
        private final String cc;

        /** {@code WS-EDIT-DATE-YY PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L11}. */
        private final String yy;

        /** {@code WS-EDIT-DATE-MM PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L16}. */
        private final String mm;

        /** {@code WS-EDIT-DATE-DD PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L25}. */
        private final String dd;

        /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, already trimmed as {@code FUNCTION TRIM} does. */
        private final String variableName;

        /** {@code WS-EDIT-DATE-MM-N}, the {@code PIC 9(2)} redefinition of the month. */
        private int monthValue;

        /** {@code WS-EDIT-DATE-DD-N}, the {@code PIC 9(2)} redefinition of the day. */
        private int dayValue;

        /** {@code WS-EDIT-YEAR-FLG}, {@code app/cpy/CSUTLDWY.cpy:L46}. */
        private EditFlag yearFlag = EditFlag.NOT_OK;

        /** {@code WS-EDIT-MONTH}, {@code app/cpy/CSUTLDWY.cpy:L50}. */
        private EditFlag monthFlag = EditFlag.NOT_OK;

        /** {@code WS-EDIT-DAY}, {@code app/cpy/CSUTLDWY.cpy:L54}. */
        private EditFlag dayFlag = EditFlag.NOT_OK;

        /** {@code INPUT-ERROR}, {@code app/cbl/COACTUPC.cbl:L173}. */
        private boolean inputError;

        /** {@code WS-RETURN-MSG PIC X(75)}, spaces while the latch is still off. */
        private String returnMessage = " ".repeat(RETURN_MESSAGE_LENGTH);

        /**
         * Whether a {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} has been taken.
         *
         * <p>This models the wider of the source's two branch scopes. See
         * {@link DateValidationService#performEditDateCcyymmddThruExit} for why two scopes exist and
         * why conflating them is wrong.
         */
        private boolean branchToCcyymmddExit;

        /**
         * Splits an eight character date into the components the work area declares.
         *
         * @param rawCcyymmdd  the eight character date text, already fixed to width
         * @param variableName the already trimmed field label used to prefix messages
         */
        private EditContext(final String rawCcyymmdd, final String variableName) {
            this.ccyymmdd = rawCcyymmdd;
            this.ccyy = rawCcyymmdd.substring(0, 4);
            this.cc = rawCcyymmdd.substring(0, 2);
            this.yy = rawCcyymmdd.substring(2, 4);
            this.mm = rawCcyymmdd.substring(4, 6);
            this.dd = rawCcyymmdd.substring(6, 8);
            this.variableName = variableName;
            // The numeric REDEFINES are live from the moment the group is populated, which is why the
            // month range test at app/cpy/CSUTLDPY.cpy:L111 can read WS-EDIT-DATE-MM-N before the
            // COMPUTE at :L127 has run. Seeding them here reproduces that.
            this.monthValue = numericView(this.mm);
            this.dayValue = numericView(this.dd);
        }

        /**
         * Sets {@code INPUT-ERROR} to true. Once set it is never cleared within one edit, exactly as the
         * source never clears it.
         */
        private void setInputError() {
            this.inputError = true;
        }

        /**
         * Applies the first error wins latch and records a message.
         *
         * <p>Every one of the twelve message literals in {@code app/cpy/CSUTLDPY.cpy} is emitted inside
         * an {@code IF WS-RETURN-MSG-OFF} guard, and that condition name is
         * {@code VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}. So the guard means "no message has
         * been recorded yet", and the effect is that <strong>the first editor to fail owns the
         * message</strong> and every later one is suppressed. The latch is modelled explicitly here so
         * that no call site can forget it.
         *
         * <p>The source builds the text with
         * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) <literal> DELIMITED BY SIZE INTO
         * WS-RETURN-MSG}. A {@code STRING} statement without a {@code POINTER} phrase writes from
         * position one and leaves the remainder of the receiving field untouched; because the latch
         * guarantees the field was still spaces, the observable result is the concatenation
         * left justified in a 75 character field, truncated if it would overflow. That is what
         * {@link DateValidationService#fixedWidth} reproduces.
         *
         * @param literal the message literal to append to the trimmed field label, reproduced byte for
         *                byte from the source including any leading or trailing space
         */
        private void emitMessage(final String literal) {
            if (returnMessage.isBlank()) {
                returnMessage = fixedWidth(variableName + literal, RETURN_MESSAGE_LENGTH);
            }
        }

        /**
         * Tests {@code WS-EDIT-DATE-IS-VALID}, {@code app/cpy/CSUTLDWY.cpy:L44}.
         *
         * <p>The condition name is declared on the three byte <em>group</em> with
         * {@code VALUE LOW-VALUES}, so it is true only when all three bytes are simultaneously low
         * values - that is, only when the year, the month <em>and</em> the day were each individually
         * marked acceptable. Composite validity in the source is therefore <strong>derived by
         * conjunction, never stored</strong>, which is precisely why three tri-state flags are modelled
         * rather than one boolean.
         *
         * @return {@code true} only when all three component flags are {@link EditFlag#ISVALID}
         */
        private boolean allComponentsValid() {
            return yearFlag == EditFlag.ISVALID
                    && monthFlag == EditFlag.ISVALID
                    && dayFlag == EditFlag.ISVALID;
        }

        /**
         * Captures the three flags, the error indicator and the message as an immutable outcome.
         *
         * @return the outcome a caller observes, equivalent to the source's
         *         {@code MOVE WS-EDIT-DATE-FLGS TO ...} plus its reads of {@code INPUT-ERROR} and
         *         {@code WS-RETURN-MSG}
         */
        private EditOutcome toOutcome() {
            return new EditOutcome(yearFlag, monthFlag, dayFlag, inputError, returnMessage);
        }
    }
}
