/*
 * ******************************************************************
 * Program     : DateValidationService.java
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
 *   </ul>
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
 *   </table>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} set to 25 and no preview feature enabled; Maven
 * 3.9.11 through the pinned wrapper. The compiler runs {@code -Xlint:all} with {@code -Werror}, so any
 * warning in a category {@code javac} 25 publishes is a build failure: no raw type, no unchecked cast and
 * no deprecated API may appear here. An unused import must not appear either, but that has to be
 * spotted by hand - {@code javac} at release 25 publishes no unused-import lint key.
 * Build with {@code ./mvnw -B clean compile} and test with
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
 *   </ul>
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
 *   <li><strong>Reading the date argument at fixed offsets.</strong> Symptom: every date a terminal
 *       operator typed without leading zeroes, and every date that arrived with a leading or trailing
 *       blank, is rejected as insufficient data - so the two screens at
 *       {@code app/cbl/COTRN02C.cbl:L389} and {@code :L409}, whose input fields are free form
 *       {@code PIC X(10)}, refuse input the mainframe accepted. It is the most damaging error available
 *       here because the code looks obviously correct and the rejections look like user error. Remedy:
 *       the documented input domain is a cursor driven scan, not a character window; see
 *       {@link #callCeedays} for all four rules and the boundary table.</li>
 *   </ul>
 *
 * <h2>What must not be tidied, and what is preserved as written</h2>
 *
 * <ul>
 *   <li><strong>All sixteen paragraph labels are mapped</strong> one-to-one and separately documented
 *       in the table above; mapping fewer, or consolidating any two, breaks the correspondence the
 *       scope coverage gate reads.</li>
 *   <li><strong>Reproduced exactly as written, each cited at its method:</strong> the tri-state flags,
 *       which are not collapsed; the {@code EXIT} at
 *       {@code app/cpy/CSUTLDPY.cpy:L323-L325}, which is not a return, so the L327 {@code SET} still
 *       runs; the strict date of birth comparison; the pessimistic versus optimistic
 *       initialisation asymmetry; both order quirks; and the longhand leap year test, which is not
 *       substituted for a library call.</li>
 *   <li><strong>Two legacy defects, one reproduced and one impossible to reproduce:</strong> the group
 *       move at {@code app/cbl/CSUTLDTC.cbl:L122} corrupts the
 *       {@code TstDate:} field of the result, and that corruption is reproduced exactly; the
 *       {@code CEEDAYS} buffer overread on the copybook
 *       call path cannot occur in a memory safe language and is recorded as a labelled deviation.</li>
 *   <li><strong>Six hygiene curiosities in the system of record, none corrected:</strong> the header
 *       list at {@code app/cpy/CSUTLDPY.cpy:L14-L15} names
 *       {@code EDIT-DATE-OF-BIRTH} twice, as both {@code d)} and {@code e)}; the comment at
 *       {@code :L286-L288} contains the typos "passsed" with three letter s and "some one" as two
 *       words; {@code :L293} is the only line in the 375 line copybook carrying a sequence number,
 *       {@code 005100}; {@code FC-INVALID-DATE} is named for invalidity but its all zero token is the
 *       success case; {@code WS-VALID-FEB-DAY} at {@code app/cpy/CSUTLDWY.cpy:L33-L34} is declared and
 *       never referenced anywhere in the corpus; and {@code app/cpy/CSUTLDPY.cpy:L203} re-sets a flag
 *       already set at {@code :L152}. Each is preserved rather than corrected.</li>
 *   </ul>
 *
 * <h2>Two things nothing in this repository determines</h2>
 *
 * <ul>
 *   <li><strong>The internal check order of {@code CEEDAYS}</strong>, and its behaviour for an input
 *       that maps to none of the nine feedback tokens. This gap was previously wider: the
 *       <em>input domain</em> half of it is now closed, because the published documentation for the
 *       service defines that domain explicitly, and all four of its rules are implemented and cited at
 *       {@link #callCeedays}. What remains genuinely unavailable is narrower - the order in which the
 *       real service applies its own <em>value</em> checks once the components have been read, and what
 *       it returns for a token outside the nine. The only behaviour the source itself defines for an
 *       unrecognised token is the {@code WHEN OTHER} fallback at
 *       {@code app/cbl/CSUTLDTC.cbl:L147-L148}, which yields the fifteen character string
 *       {@code Date is invalid}. No additional outcome is invented, and the evaluation order this class
 *       applies is recorded as a labelled deviation at {@link #callCeedays}.</li>
 *   <li><strong>Any latency or throughput objective for date validation.</strong> None exists
 *       anywhere in the source, which publishes no service level of any kind. The performance gate
 *       records a measured baseline, never an invented target.</li>
 *   </ul>
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
 *       deliberately named ones</strong> that a caller must reproduce for parity. That property is
 *       enforced, not merely asserted: {@link DateValidationResult#toString()} is
 *       <strong>overridden</strong> to emit the severity code and the message number only, because a
 *       record's generated {@code toString()} would have rendered the composed 80 character area and
 *       with it the date. {@link EditOutcome} needs no such override - its
 *       {@link EditOutcome#returnMessage()} is composed from a caller supplied field <em>label</em> and
 *       never from the field's value - and {@link FeedbackCode} holds two integers. This class performs
 *       no logging of its own, so it cannot leak; the obligation passes to the caller and is stated
 *       again at {@link DateValidationResult}.</li>
 *   </ul>
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
     * The ten character mask used by the two program call paths, {@code YYYY-MM-DD}. Declared at
     * {@code app/cbl/CORPT00C.cbl:L72}.
     */
    public static final String MASK_YYYY_MM_DD = "YYYY-MM-DD";

    /**
     * The eight character mask used by the copybook call path, {@code YYYYMMDD}. Declared at
     * {@code app/cpy/CSUTLDWY.cpy:L58-L59}.
     */
    public static final String MASK_YYYYMMDD = "YYYYMMDD";

    /**
     * Width of {@code LS-DATE PIC X(10)}, {@code app/cbl/CSUTLDTC.cbl:L84}.
     */
    public static final int LS_DATE_LENGTH = 10;

    /**
     * Width of {@code LS-DATE-FORMAT PIC X(10)}, {@code app/cbl/CSUTLDTC.cbl:L85}.
     */
    public static final int LS_DATE_FORMAT_LENGTH = 10;

    /**
     * Width of {@code LS-RESULT PIC X(80)}, {@code app/cbl/CSUTLDTC.cbl:L86}.
     */
    public static final int LS_RESULT_LENGTH = 80;

    /**
     * Width of {@code WS-RESULT PIC X(15)}, {@code app/cbl/CSUTLDTC.cbl:L49}.
     */
    public static final int RESULT_TEXT_LENGTH = 15;

    /**
     * Width of the caller's {@code CSUTLDTC-RESULT-MSG PIC X(61)}, {@code app/cbl/CORPT00C.cbl:L136}.
     */
    public static final int MESSAGE_TEXT_LENGTH = 61;

    /**
     * Width of {@code WS-EDIT-DATE-CCYYMMDD PIC X(8)}, {@code app/cpy/CSUTLDWY.cpy:L4-L34}.
     */
    public static final int CCYYMMDD_LENGTH = 8;

    /**
     * Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, {@code app/cbl/COACTUPC.cbl:L53}.
     */
    public static final int VARIABLE_NAME_LENGTH = 25;

    /**
     * Width of {@code WS-RETURN-MSG PIC X(75)}, {@code app/cbl/COACTUPC.cbl:L479}.
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * The fifteen character result string for the {@code WHEN OTHER} arm of the source's {@code EVALUATE TRUE},
     * {@code app/cbl/CSUTLDTC.cbl:L147-L148}.
     */
    public static final String UNRECOGNISED_RESULT_TEXT = "Date is invalid";

    /**
     * Width of {@code WS-SEVERITY PIC X(04)}, {@code app/cbl/CSUTLDTC.cbl:L43}.
     */
    private static final int SEVERITY_LENGTH = 4;

    /**
     * Width of {@code WS-MSG-NO PIC X(04)}, {@code app/cbl/CSUTLDTC.cbl:L46}.
     */
    private static final int MESSAGE_NUMBER_LENGTH = 4;

    /**
     * Width of {@code FILLER PIC X(11) VALUE 'Mesg Code:'}, {@code app/cbl/CSUTLDTC.cbl:L45}.
     */
    private static final int MESG_CODE_FILLER_LENGTH = 11;

    /**
     * Width of {@code FILLER PIC X(09) VALUE 'TstDate:'}, {@code app/cbl/CSUTLDTC.cbl:L51}.
     */
    private static final int TST_DATE_FILLER_LENGTH = 9;

    /**
     * Width of {@code FILLER PIC X(10) VALUE 'Mask used:'}, {@code app/cbl/CSUTLDTC.cbl:L54}.
     */
    private static final int MASK_USED_FILLER_LENGTH = 10;

    /**
     * Width of {@code FILLER PIC X(03) VALUE SPACES}, {@code app/cbl/CSUTLDTC.cbl:L57}.
     */
    private static final int TRAILING_FILLER_LENGTH = 3;

    /**
     * The number of digit positions a {@code PIC 9(4)} field renders.
     */
    private static final int ZONED_FOUR_MODULUS = 10_000;

    /**
     * The number of bits in the high order byte of a {@code PIC S9(4) BINARY} halfword.
     */
    private static final int BYTE_WIDTH_IN_BITS = 8;

    /**
     * A single byte mask, used to split a halfword into its two bytes.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The one delimiter either picture string uses, taken from {@link #MASK_YYYY_MM_DD} itself.
     *
     * <p>Its presence in the picture is what licenses omitted leading zeroes on the month and the day;
     * its absence from {@link #MASK_YYYYMMDD} is what forbids them there. See {@link #callCeedays}.
     */
    private static final char DATE_SEPARATOR = '-';

    /** Digits the year component supplies under both pictures, {@code YYYY} being four characters. */
    private static final int YEAR_DIGITS = 4;

    /**
     * Digits the month and the day components supply at most, {@code MM} and {@code DD} each being two
     * characters. Under the delimited picture one digit is also accepted; under the undelimited picture
     * both are required.
     */
    private static final int MONTH_DAY_DIGITS = 2;

    /** The radix every numeric component of a date is read in. */
    private static final int DECIMAL_RADIX = 10;

    /**
     * The century value of {@code 88 LAST-CENTURY VALUE 19}, {@code app/cpy/CSUTLDWY.cpy:L10}.
     */
    private static final int LAST_CENTURY = 19;

    /**
     * The century value of {@code 88 THIS-CENTURY VALUE 20}, {@code app/cpy/CSUTLDWY.cpy:L9}.
     */
    private static final int THIS_CENTURY = 20;

    /**
     * Lower bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}, {@code app/cpy/CSUTLDWY.cpy:L19-L20}.
     */
    private static final int MONTH_MINIMUM = 1;

    /**
     * Upper bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}, {@code app/cpy/CSUTLDWY.cpy:L19-L20}.
     */
    private static final int MONTH_MAXIMUM = 12;

    /**
     * The month of {@code 88 WS-FEBRUARY VALUE 2}, {@code app/cpy/CSUTLDWY.cpy:L24}.
     */
    private static final int FEBRUARY = 2;

    /**
     * Lower bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}, {@code app/cpy/CSUTLDWY.cpy:L28-L29}.
     */
    private static final int DAY_MINIMUM = 1;

    /**
     * Upper bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}, {@code app/cpy/CSUTLDWY.cpy:L28-L29}.
     */
    private static final int DAY_MAXIMUM = 31;

    /**
     * The day of {@code 88 WS-DAY-31 VALUE 31}, {@code app/cpy/CSUTLDWY.cpy:L30}.
     */
    private static final int DAY_31 = 31;

    /**
     * The day of {@code 88 WS-DAY-30 VALUE 30}, {@code app/cpy/CSUTLDWY.cpy:L31}.
     */
    private static final int DAY_30 = 30;

    /**
     * The day of {@code 88 WS-DAY-29 VALUE 29}, {@code app/cpy/CSUTLDWY.cpy:L32}.
     */
    private static final int DAY_29 = 29;

    /**
     * The upper bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28}, {@code app/cpy/CSUTLDWY.cpy:L33-L34}.
     */
    public static final int VALID_FEBRUARY_DAY_MAXIMUM = 28;

    /**
     * The month rejection literal, emitted verbatim by both month branches. Declared at
     * {@code app/cpy/CSUTLDPY.cpy:L119}.
     */
    private static final String MONTH_RANGE_MESSAGE = ": Month must be a number between 1 and 12.";

    /**
     * The day rejection literal, emitted verbatim by both day branches. Declared at
     * {@code app/cpy/CSUTLDPY.cpy:L180}.
     */
    private static final String DAY_RANGE_MESSAGE = ":day must be a number between 1 and 31.";

    /**
     * Sentinel for a two character field whose bytes are not all decimal digits.
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
     * The day before Lillian day one. {@code OUTPUT-LILLIAN PIC S9(9) BINARY} at {@code app/cbl/CSUTLDTC.cbl:L41}.
     */
    private static final LocalDate LILLIAN_DAY_ZERO = LocalDate.of(1582, 10, 14);

    /**
     * The last date {@code CEEDAYS} can represent.
     */
    private static final LocalDate LILLIAN_LAST_DAY = LocalDate.of(9999, 12, 31);

    /**
     * The day before COBOL integer date one, used by the date of birth check. Declared at
     * {@code app/cpy/CSUTLDWY.cpy:L37}.
     */
    private static final LocalDate INTEGER_DATE_ZERO = LocalDate.of(1600, 12, 31);

    /**
     * The injected clock backing {@code FUNCTION CURRENT-DATE} at {@code app/cpy/CSUTLDPY.cpy:L343}.
     */
    private final Clock clock;

    /**
     * Constructs the service with the clock that supplies the current date.
     *
     * @param clock the clock supplying the current date for the date of birth reasonableness check.
     * @throws NullPointerException if {@code clock} is {@code null}, which is a wiring defect rather than a
     * data condition and so is reported immediately
     */
    public DateValidationService(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Five of the ten result strings carry deliberate trailing spaces inside their COBOL quotes -
     * {@code Invalid Era} followed by four spaces, {@code Unsupp. Range} by two, {@code Invalid month} by two,
     * {@code Bad Pic String} by one and {@code YearInEra is 0} by one - so that every one occupies exactly
     * fifteen characters. The trailing spaces are reproduced in the constants below and are described here in
     * prose as well, so that a formatter cannot silently remove the evidence.
     */
    public enum FeedbackCondition {

        /**
         * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'}, {@code app/cbl/CSUTLDTC.cbl:L62}; result string
         * {@code app/cbl/CSUTLDTC.cbl:L130}.
         *
         * <p><strong>This constant is named for an invalid date but means success.</strong> Its token is all
         * zeros, which is severity zero and message number zero, and it is the only condition carrying the
         * result string {@code Date is valid}. The copybook corroborates it by testing
         * {@code IF WS-SEVERITY-N = 0} for success at {@code app/cpy/CSUTLDPY.cpy:L298}. The misleading name
         * belongs to the frozen system of record and is reproduced rather than corrected.
         */
        FC_INVALID_DATE(0, 0, "Date is valid"),

        /**
         * {@code 88 FC-INSUFFICIENT-DATA VALUE X'000309CB59C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L63}; result
         * string {@code :L132}.
         */
        FC_INSUFFICIENT_DATA(3, 2507, "Insufficient"),

        /**
         * {@code 88 FC-BAD-DATE-VALUE VALUE X'000309CC59C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L64}; result
         * string {@code :L134}.
         */
        FC_BAD_DATE_VALUE(3, 2508, "Datevalue error"),

        /**
         * {@code 88 FC-INVALID-ERA VALUE X'000309CD59C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L65}; result string
         * {@code :L136}, which carries four trailing spaces inside its quotes.
         */
        FC_INVALID_ERA(3, 2509, "Invalid Era    "),

        /**
         * {@code 88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L66}; result string
         * {@code :L138}, which carries two trailing spaces inside its quotes.
         */
        FC_UNSUPP_RANGE(3, 2513, "Unsupp. Range  "),

        /**
         * {@code 88 FC-INVALID-MONTH VALUE X'000309D559C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L67}; result
         * string {@code :L140}, which carries two trailing spaces inside its quotes.
         */
        FC_INVALID_MONTH(3, 2517, "Invalid month  "),

        /**
         * {@code 88 FC-BAD-PIC-STRING VALUE X'000309D659C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L68}; result
         * string {@code :L142}, which carries one trailing space inside its quotes.
         */
        FC_BAD_PIC_STRING(3, 2518, "Bad Pic String "),

        /**
         * {@code 88 FC-NON-NUMERIC-DATA VALUE X'000309D859C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L69}; result
         * string {@code :L144}.
         */
        FC_NON_NUMERIC_DATA(3, 2520, "Nonnumeric data"),

        /**
         * {@code 88 FC-YEAR-IN-ERA-ZERO VALUE X'000309D959C3C5C5'}, {@code app/cbl/CSUTLDTC.cbl:L70}; result
         * string {@code :L146}, which carries one trailing space inside its quotes.
         */
        FC_YEAR_IN_ERA_ZERO(3, 2521, "YearInEra is 0 ");

        /**
         * A cached copy of the constants, so that resolving a condition neither allocates nor rebuilds a table
         * on every call.
         */
        private static final FeedbackCondition[] CACHED_VALUES = values();

        /**
         * The severity halfword decoded from this condition's feedback token, which is what the caller
         * tests rather than the token as a whole.
         */
        private final int severity;

        /**
         * The message number halfword decoded from this condition's feedback token.
         */
        private final int messageNumber;

        /**
         * The fifteen character result text this condition places in the returned message area.
         */
        private final String resultText;

        /**
         * Binds a decoded feedback token to the result text the source emits for it.
         *
         * @param severity the severity halfword, zero for success and three otherwise
         * @param messageNumber the message number halfword
         * @param resultText the fifteen character result text, byte exact and including any trailing spaces the
         * source literal carries
         */
        FeedbackCondition(final int severity, final int messageNumber, final String resultText) {
            this.severity = severity;
            this.messageNumber = messageNumber;
            this.resultText = resultText;
        }

        /**
         * Returns the severity halfword this condition decodes to.
         *
         * @return {@code 0} for the success condition and {@code 3} for the other eight
         */
        public int severity() {
            return severity;
        }

        /**
         * Returns the message number halfword of this condition's feedback token.
         *
         * @return {@code 0} for the success condition, otherwise a Language Environment message number in the
         * range 2507 through 2521
         */
        public int messageNumber() {
            return messageNumber;
        }

        /**
         * Returns this condition's result string, always exactly {@link #RESULT_TEXT_LENGTH} characters
         * including any deliberate trailing spaces.
         *
         * @return the fifteen character string the source moves into {@code WS-RESULT}
         */
        public String resultText() {
            return resultText;
        }

        /**
         * This is the {@code EVALUATE TRUE} subject of {@code app/cbl/CSUTLDTC.cbl:L128-L149} expressed as a
         * lookup. An empty result is the {@code WHEN OTHER} arm at {@code :L147-L148}: a feedback code that
         * matches none of the nine declared tokens.
         *
         * @param severity the severity halfword to match
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
     * Both components are plain {@code int}. Rule 1 Clause A and the tree wide financial invariant forbid
     * floating point, and a halfword is integral in the source; no rounding, scale or decimal type is involved.
     *
     * @param severity the severity halfword, moved to {@code WS-SEVERITY-N} and to {@code RETURN-CODE}
     * @param messageNumber the message number halfword, moved to {@code WS-MSG-NO-N}
     */
    public record FeedbackCode(int severity, int messageNumber) {

        /**
         * Creates a feedback code for the given condition.
         *
         * @param condition the condition whose halfwords are wanted.
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
         * @return the matching condition, or an empty optional when this code matches none of the nine, which
         * is the source's {@code WHEN OTHER} arm at {@code app/cbl/CSUTLDTC.cbl:L147}
         */
        public Optional<FeedbackCondition> condition() {
            return FeedbackCondition.resolve(severity, messageNumber);
        }

        /**
         * Returns the result string this code produces, reproducing the source's {@code EVALUATE TRUE}.
         *
         * @return the resolved condition's fifteen character string, or
         * {@link DateValidationService#UNRECOGNISED_RESULT_TEXT} for the {@code WHEN OTHER} case
         */
        public String resultText() {
            return condition().map(FeedbackCondition::resultText).orElse(UNRECOGNISED_RESULT_TEXT);
        }
    }

    /**
     * One of the three states a single date component flag can hold.
     */
    public enum EditFlag {

        /**
         * The component was validated. The source's {@code VALUE LOW-VALUES} state, declared at
         * {@code app/cpy/CSUTLDWY.cpy:L47}, {@code :L51} and {@code :L55}.
         */
        ISVALID,

        /**
         * The component was supplied but is not acceptable. The source's {@code VALUE '0'} state, declared at
         * {@code app/cpy/CSUTLDWY.cpy:L48}, {@code :L52} and {@code :L56}.
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
     * <strong>{@link #toString()} is overridden to emit those two fields and nothing else</strong>, so
     * that neither a logging framework nor an assertion failure nor string interpolation can render the
     * composed area by accident: a caller that wants it must ask for {@link #result()} by name. Leaving
     * the record default in place would have had the opposite effect, because a record's generated
     * {@code toString()} renders every component - see {@link #toString()} for the full reasoning.
     *
     * @param feedbackCode the severity and message number the service produced
     * @param result the composed 80 character result area, exactly
     * {@link DateValidationService#LS_RESULT_LENGTH} characters
     */
    public record DateValidationResult(FeedbackCode feedbackCode, String result) {

        /**
         * Validates the invariants of the layout.
         *
         * @throws NullPointerException if either component is {@code null}
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
         * Returns the severity code as the caller sees it, positioned as the source positions it.
         *
         * @return the first four characters of the composed area
         */
        public String severityCode() {
            return result.substring(0, 4);
        }

        /**
         * Returns the message number as the four character zero padded string the callers compare against.
         *
         * @return characters 16 through 19 of the composed area
         */
        public String messageNumber() {
            return result.substring(15, 19);
        }

        /**
         * Returns the caller's {@code CSUTLDTC-RESULT-MSG PIC X(61)} view of the result tail.
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
         * Returns the value the caller tests as a return code, which is the feedback token's severity.
         *
         * @return the severity halfword
         */
        public int returnCode() {
            return feedbackCode.severity();
        }

        /**
         * Reports whether the date was accepted.
         *
         * @return {@code true} when the severity is zero
         */
        public boolean valid() {
            return feedbackCode.severity() == 0;
        }

        /**
         * Returns a redacted rendering carrying only the two fields that cannot hold personal data.
         *
         * <p><strong>Overridden deliberately, and the override is load bearing rather than cosmetic.</strong>
         * A record's generated {@code toString()} renders <em>every</em> component, so the generated form
         * this replaces rendered {@link #result()} in full - and {@link #result()} embeds the supplied
         * date in its {@code TstDate:} field. Because
         * {@link DateValidationService#editDateOfBirth(String, String)} validates a customer's date of
         * birth, that generated rendering would place a date of birth into any log line, exception
         * message, assertion failure or serialised form that interpolated an instance, without a single
         * call site ever naming the date. Rule 1 Clause D forbids precisely that, and the masking rules
         * of {@code logback-spring.xml} cannot compensate: the leak sits inside the value handed to the
         * logger rather than behind a recognisable key, so there is nothing for a pattern to match.
         *
         * <p>Only {@link #severityCode()} and {@link #messageNumber()} are emitted. Both are four
         * character numeric fields - {@code WS-SEVERITY-N PIC 9(4)} at {@code app/cbl/CSUTLDTC.cbl:L44}
         * and {@code WS-MSG-NO-N PIC 9(4)} at {@code :L47} - and both are exactly the fields the source's
         * own callers test, at {@code app/cbl/COTRN02C.cbl:L397} and {@code :L400} and at
         * {@code app/cbl/CORPT00C.cbl:L396} and {@code :L399}. Neither can carry personal data whatever
         * the input, so this rendering is safe by construction rather than by convention, and it stays
         * diagnostically useful: the severity and the message number are what identifies an outcome.
         *
         * <p>This changes no behaviour the parity gates measure. The source has no counterpart to
         * {@code toString()} at all - {@code CSUTLDTC} publishes its outcome through the 80 byte area and
         * {@code RETURN-CODE} only - so the rendered text is new diagnostic surface, not translated
         * behaviour, and {@link #result()} still returns the composed area byte for byte.
         *
         * <p>Side effects: none. Error modes: none - this method cannot fail, because the compact
         * constructor has already proven {@code result} is exactly
         * {@link DateValidationService#LS_RESULT_LENGTH} characters, so neither substring can run out of
         * bounds.
         *
         * @return the type name followed by the severity code and the message number only, never
         *         {@code null} and never carrying the validated date
         */
        @Override
        public String toString() {
            return "DateValidationResult[severityCode=" + severityCode()
                    + ", messageNumber=" + messageNumber() + "]";
        }
    }

    /**
     * The outcome of a composite date edit: the three component flags, the error indicator and the message.
     *
     * @param yearFlag the state of {@code WS-EDIT-YEAR-FLG}
     * @param monthFlag the state of {@code WS-EDIT-MONTH}
     * @param dayFlag the state of {@code WS-EDIT-DAY}
     * @param inputError whether {@code INPUT-ERROR} was set during the edit
     * @param returnMessage the first message the edit produced, held at the source's fixed width of
     * {@link DateValidationService#RETURN_MESSAGE_LENGTH} characters and all spaces when no message was
     * produced
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
         * @return {@code true} when a message is present
         */
        public boolean hasReturnMessage() {
            return !returnMessage.isBlank();
        }
    }

    /**
     * Validates a date against a format mask, reproducing the whole {@code CSUTLDTC} call contract.
     *
     * @param lsDate the date text, corresponding to {@code LS-DATE PIC X(10)}.
     * @param lsDateFormat the mask, corresponding to {@code LS-DATE-FORMAT PIC X(10)}.
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
     * @param lsDate the ten character date field
     * @param lsDateFormat the ten character mask field
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
        // day number is deliberately not surfaced on the result; that omission is a labelled
        // deviation. The Lillian range itself is still honoured, because it is what
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
     * <p><strong>Scope note, so that this is not mistaken for the forbidden substitution.</strong>
     * Using {@link LocalDate} here is correct and intended: this is the Language Environment side of
     * the translation, and the source delegates entirely to an opaque external service. It is
     * <em>not</em> the leap year test of {@link #editDayMonthYear}, which is written out longhand in the
     * copybook and must keep its two branch division structure. The two must not be conflated.
     *
     * <h4>The documented input domain</h4>
     *
     * <p>The accepted input domain is <strong>not</strong> a fixed character window. The IBM Language
     * Environment and ILE documentation for the {@code CEEDAYS} callable service defines it with four
     * rules, and every one of them is implemented here. Each is stated with the corroboration the
     * frozen corpus independently supplies, because a rule that only an external document asserts is
     * weaker evidence than one the source also demonstrates:
     *
     * <ul>
     *   <li><strong>Leading blanks are skipped.</strong> Parsing begins at the first non-blank
     *       character. Corroborated by {@code app/cbl/CSUTLDTC.cbl:L105-L106}, which moves
     *       {@code LENGTH OF LS-DATE} into {@code VSTRING-LENGTH} - the constant ten, the field's
     *       declared width, <em>never</em> a trimmed length. The service is therefore always handed ten
     *       bytes however few the caller populated, so tolerating blanks is not a convenience but the
     *       precondition for the call contract working at all.</li>
     *   <li><strong>Leading zeroes may be omitted from the month and the day when, and only when, the
     *       picture string carries delimiters.</strong> The delimiters are what make a variable width
     *       component unambiguous, which is why the permission is conditional on them. Of the two masks
     *       in the corpus only {@link #MASK_YYYY_MM_DD} qualifies; {@link #MASK_YYYYMMDD} has no
     *       delimiter and so requires all eight digits.</li>
     *   <li><strong>After a valid date has been parsed, every remaining character is ignored</strong> -
     *       whatever it is, not merely a blank. This is the rule that makes the buffer overread on the
     *       copybook call path harmless in production, and it is corroborated by that path existing at
     *       all: {@code app/cpy/CSUTLDPY.cpy:L293} passes the eight byte group
     *       {@code WS-EDIT-DATE-CCYYMMDD} into a {@code PIC X(10)} linkage item whose length is
     *       declared as ten, so bytes nine and ten are adjacent storage and are read. The call
     *       nevertheless succeeds, because the eight digits complete the date before those two bytes are
     *       reached. See {@link #editDateLe}.</li>
     *   <li><strong>Representable dates run from 15 October 1582 through 31 December 9999.</strong>
     *       Already enforced at step 7 below, against {@code LILLIAN_DAY_ZERO} and
     *       {@code LILLIAN_LAST_DAY}.</li>
     * </ul>
     *
     * <p><strong>Where each form is actually reachable in the corpus.</strong> The domain is not
     * theoretical. {@code app/cbl/COTRN02C.cbl:L389} and {@code :L409} move {@code TORIGDTI} and
     * {@code TPROCDTI} - single free form {@code PIC X(10)} screen fields, declared at
     * {@code app/cpy-bms/COTRN02.CPY:L102} and {@code :L108} - <em>directly</em> into
     * {@code CSUTLDTC-DATE} under the delimited mask. A terminal operator who types {@code 2022-6-1}
     * into one of them produces an input carrying an omitted month zero, an omitted day zero and two
     * trailing blanks simultaneously. By contrast {@code app/cbl/CORPT00C.cbl:L60-L71} assembles its
     * two dates <em>positionally</em>, as {@code X(04)} then a literal hyphen then {@code X(02)} then a
     * literal hyphen then {@code X(02)}, filled from six discrete screen fields at {@code :L381-L386};
     * that group is always exactly ten bytes with the hyphens fixed, so a half typed component there
     * yields an <em>embedded</em> blank rather than an omitted zero. Both behaviours are reproduced:
     * the first is accepted, the second is rejected.
     *
     * <p><strong>Omission is deliberately not extended to the year.</strong> The documented permission
     * is demonstrated on two character month and day components only, and a year shortened by omission
     * can only denote a value of 999 or less, which the representable range rejects regardless. Widening
     * the year would therefore add no accepted date while asserting behaviour no source documents, so
     * {@code 22-6-1} remains rejected exactly as it is today.
     *
     * <p><strong>The internal check order of {@code CEEDAYS}, and its behaviour for an
     * input that maps to none of the nine declared tokens, are not determinable.</strong> The documented
     * input domain above
     * closes the parsing half of this gap; the order in which the real service applies its own
     * <em>value</em> checks, and what it returns for a token outside the nine, remain underivable from
     * anything in this repository and from the published interface. The only behaviour the source
     * defines for an unrecognised token is the {@code WHEN OTHER} arm at
     * {@code app/cbl/CSUTLDTC.cbl:L147-L148}. The order applied below is therefore a labelled
     * deviation. <strong>No outcome outside the nine declared tokens is
     * invented</strong>, and every classification below selects one of them.
     *
     * <p>The evaluation order applied here, chosen to be deterministic and to report the most specific
     * available condition:
     *
     * <ol>
     *   <li>The mask must be one of the two in the corpus, otherwise
     *       {@link FeedbackCondition#FC_BAD_PIC_STRING}.</li>
     *   <li>Leading blanks and null bytes are skipped. If nothing but blanks was supplied the date
     *       provided no component at all, which is
     *       {@link FeedbackCondition#FC_INSUFFICIENT_DATA}.</li>
     *   <li>The components are then read in mask order with a cursor rather than at fixed offsets: four
     *       year digits, the separator when the mask carries one, one or two month digits, the separator
     *       again, and one or two day digits. Under the undelimited mask month and day must supply both
     *       digits. Everything after the day is ignored. A component that cannot be read is classified
     *       by <em>what blocked it</em>: a blank, a null byte or the end of the field means the field
     *       stopped short and yields {@link FeedbackCondition#FC_INSUFFICIENT_DATA}, whereas a character
     *       that is present and not blank but is the wrong character - a non digit where a digit is
     *       required, or anything other than the separator where the separator is required - yields
     *       {@link FeedbackCondition#FC_NON_NUMERIC_DATA}. That split is what keeps every rejection this
     *       method made under the previous fixed offset reading, and reporting the same condition for
     *       it.</li>
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
     * <h4>The domain boundary, stated exhaustively</h4>
     *
     * <p>The boundary between accepted and rejected is stated as a table rather than left to be
     * inferred, because the one property that must hold is easy to lose silently:
     * <strong>acceptance widens only to inputs that denote a real, representable date.</strong> No
     * input that was rejected as a date is accepted as one. Every row is asserted in
     * {@code src/test/java/com/cardemo/unit/validation/DateValidationServiceTest.java}.
     *
     * <table border="1">
     *   <caption>Accepted and rejected forms, with the mask each is read under</caption>
     *   <tr><th>Input, blanks shown as they are supplied</th><th>Mask</th><th>Outcome</th></tr>
     *   <tr><td>{@code 2022-06-10}</td><td>delimited</td><td>accepted, as before</td></tr>
     *   <tr><td>{@code 2022-6-1__}</td><td>delimited</td><td>accepted, omitted zeroes and trailing
     *       blanks</td></tr>
     *   <tr><td>{@code __2022-6-1}</td><td>delimited</td><td>accepted, leading blanks</td></tr>
     *   <tr><td>{@code 2020-2-29_}</td><td>delimited</td><td>accepted, a real leap day - and
     *       {@code 2022-2-29} is rejected as a bad date value, because 2022 is not a leap year</td></tr>
     *   <tr><td>{@code _20220610_}</td><td>undelimited</td><td>accepted, leading and trailing
     *       blanks</td></tr>
     *   <tr><td>{@code __________}</td><td>either</td><td>insufficient data</td></tr>
     *   <tr><td>{@code 2022-6_-10}</td><td>delimited</td><td>insufficient data, blank where the
     *       separator is required</td></tr>
     *   <tr><td>{@code 2022-_6-10}</td><td>delimited</td><td>insufficient data, blank where a digit is
     *       required</td></tr>
     *   <tr><td>{@code 2022-06___}</td><td>delimited</td><td>insufficient data, no day</td></tr>
     *   <tr><td>{@code 202206____}</td><td>undelimited</td><td>insufficient data, six digits of
     *       eight</td></tr>
     *   <tr><td>{@code 2022/06/10}</td><td>delimited</td><td>non numeric data, wrong separator</td></tr>
     *   <tr><td>{@code 20226-1___}</td><td>delimited</td><td>non numeric data, digit where the separator
     *       is required</td></tr>
     *   <tr><td>{@code 22-6-1____}</td><td>delimited</td><td>non numeric data, the year needs four
     *       digits</td></tr>
     *   <tr><td>{@code 0000-06-10}</td><td>delimited</td><td>year in era zero</td></tr>
     *   <tr><td>{@code 2022-13-01}</td><td>delimited</td><td>invalid month</td></tr>
     *   <tr><td>{@code 2022-02-30}</td><td>delimited</td><td>bad date value</td></tr>
     *   <tr><td>{@code 1582-10-14}</td><td>delimited</td><td>unsupported range, the day before Lillian
     *       day one</td></tr>
     * </table>
     *
     * <p><strong>Two outcomes are deliberately reclassified, and neither is a loosening.</strong>
     * {@code 2022-0-01} and {@code 2022-6-31} were previously reported as non numeric data, because the
     * fixed offset reading found a hyphen or a digit in a position it expected to be otherwise and never
     * reached the value. Both are now readable, so both report the condition that names their actual
     * defect - invalid month and bad date value respectively. They remain rejected with severity three;
     * only the message number becomes more specific, which is what the evaluation order above exists to
     * achieve.
     *
     * <p>{@link FeedbackCondition#FC_INVALID_ERA} is unreachable from either mask, since neither
     * carries an era symbol; it is documented at its declaration rather than forced into this order.
     *
     * <p>One documented rule is likewise unreachable and is therefore recorded rather than implemented:
     * a picture string that <em>itself</em> begins with blanks causes the service to skip exactly that
     * many positions before parsing, instead of skipping to the first non-blank character. Step 1 admits
     * only the two masks the corpus declares, and neither begins with a blank, so any such picture is
     * rejected as a bad picture string before the rule could apply. Implementing the branch would create
     * code no input can reach, which Rule 1 Clause B forbids.
     *
     * @param dateText the date characters handed to the service, always ten characters on the one call
     *                 path because {@link #validate} fixes it to the declared width of {@code LS-DATE}
     * @param maskText the mask characters handed to the service
     * @return the feedback code the outcome produces, always one of the nine declared tokens
     */
    private static FeedbackCode callCeedays(final String dateText, final String maskText) {
        // Step 1. The mask is a picture string; only the two in the corpus are recognised. Trailing
        // spaces are insignificant because both masks are declared shorter than the X(10) field that
        // carries them on the program call path. A mask carrying LEADING blanks fails this test and is
        // reported as a bad picture string, which is precisely why the documented rule for a picture
        // that itself begins with blanks is unreachable here. See this method's Javadoc.
        final String mask = maskText.stripTrailing();
        if (!MASK_YYYY_MM_DD.equals(mask) && !MASK_YYYYMMDD.equals(mask)) {
            return FeedbackCode.of(FeedbackCondition.FC_BAD_PIC_STRING);
        }
        final boolean separated = MASK_YYYY_MM_DD.equals(mask);

        // Leading zeroes may be omitted from the month and the day only when the picture carries
        // delimiters, because the delimiters are what make a variable width component unambiguous. The
        // undelimited mask therefore requires both digits of each.
        final int minimumComponentDigits = separated ? 1 : MONTH_DAY_DIGITS;

        // Step 2. Parsing begins at the first non-blank character. A null byte counts as blank, because
        // that is how an unpopulated COBOL field presents and is exactly what the front end edits test
        // for with EQUAL LOW-VALUES at app/cpy/CSUTLDPY.cpy:L30, :L94 and :L153.
        int cursor = 0;
        while (cursor < dateText.length() && isBlankOrNull(dateText.charAt(cursor))) {
            cursor++;
        }
        if (cursor == dateText.length()) {
            return FeedbackCode.of(FeedbackCondition.FC_INSUFFICIENT_DATA);
        }
        // Where the value begins, so the picture width below is measured from the value rather than
        // from the start of a field that may be blank padded on the left.
        final int valueStart = cursor;

        // Step 3. The components are read in mask order with a cursor, never at fixed offsets. Reading
        // them positionally is what rejected every form the documented domain accepts, and is the defect
        // this step exists to avoid reintroducing.
        final int yearEnd = digitRunEnd(dateText, cursor, YEAR_DIGITS);
        if (yearEnd - cursor < YEAR_DIGITS) {
            return FeedbackCode.of(shortfallCondition(dateText, yearEnd));
        }
        final int year = Integer.parseInt(dateText, cursor, yearEnd, DECIMAL_RADIX);
        cursor = yearEnd;

        if (separated) {
            if (cursor >= dateText.length() || dateText.charAt(cursor) != DATE_SEPARATOR) {
                return FeedbackCode.of(shortfallCondition(dateText, cursor));
            }
            cursor++;
        }

        final int monthEnd = digitRunEnd(dateText, cursor, MONTH_DAY_DIGITS);
        if (monthEnd - cursor < minimumComponentDigits) {
            return FeedbackCode.of(shortfallCondition(dateText, monthEnd));
        }
        final int month = Integer.parseInt(dateText, cursor, monthEnd, DECIMAL_RADIX);
        cursor = monthEnd;

        if (separated) {
            if (cursor >= dateText.length() || dateText.charAt(cursor) != DATE_SEPARATOR) {
                return FeedbackCode.of(shortfallCondition(dateText, cursor));
            }
            cursor++;
        }

        final int dayEnd = digitRunEnd(dateText, cursor, MONTH_DAY_DIGITS);
        if (dayEnd - cursor < minimumComponentDigits) {
            return FeedbackCode.of(shortfallCondition(dateText, dayEnd));
        }
        final int day = Integer.parseInt(dateText, cursor, dayEnd, DECIMAL_RADIX);
        cursor = dayEnd;

        // Step 3a. The cursor scan reads a digit RUN, so a component shorter than its picture stops
        // early and whatever follows it would otherwise go unexamined: "2022-06-1a" reads day 1 and
        // would silently discard the 'a'. The service consumes the picture, so any character still
        // inside the picture width belongs to the supplied value and is nonnumeric data, while a
        // character BEYOND that width is not part of the value at all and must be ignored.
        //
        // That second half is not a nicety. LS-DATE is PIC X(10) at app/cbl/CSUTLDTC.cbl:L84 and the
        // length handed to the service is unconditionally LENGTH OF LS-DATE at
        // app/cbl/CSUTLDTC.cbl:L105-L106, yet the caller at app/cpy/CSUTLDPY.cpy:L293-L294 passes an
        // eight byte field under the eight character 'YYYYMMDD' picture set at :L291. The final two
        // bytes the service reads are therefore adjacent storage the caller never supplied, and
        // rejecting them would fail every undelimited call. Bounding the scan by the picture width
        // reproduces both behaviours with one rule. Trailing blanks inside the width are accepted
        // because the value arrives from a space padded fixed width field, so only non-blank residue
        // is rejected, classified by the same helper the component checks above use.
        final int residueEnd = Math.min(dateText.length(), valueStart + mask.length());
        for (int index = cursor; index < residueEnd; index++) {
            if (!isBlankOrNull(dateText.charAt(index))) {
                return FeedbackCode.of(shortfallCondition(dateText, index));
            }
        }

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

        // Step 8. Success, reported through FC_INVALID_DATE, the all zero token whose name says the
        // opposite of what it means.
        return FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);
    }

    /**
     * Reproduces the group move of {@code app/cbl/CSUTLDTC.cbl:L122}.
     *
     * @param vstringLength the value of {@code Vstring-length}, always ten on this path
     * @param vstringText the value of {@code Vstring-text}
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
     * @param severity the four character severity, {@code app/cbl/CSUTLDTC.cbl:L43}
     * @param messageNumber the four character message number, {@code :L46}
     * @param resultText the fifteen character result string, {@code :L49}
     * @param testedDate the ten character tested date, {@code :L52}, corrupted by the {@code :L122} group move
     * @param maskUsed the ten character mask, {@code :L55}
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
     * @param value the halfword value to render
     * @return exactly four decimal digits
     */
    private static String zonedFour(final int value) {
        return String.format(Locale.ROOT, "%04d", Math.abs(value) % ZONED_FOUR_MODULUS);
    }

    /**
     * Fixes a value to an alphanumeric field width, as a COBOL {@code MOVE} to {@code PIC X(n)} does.
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
     * @param character the character to test
     * @return {@code true} for {@code '0'} through {@code '9'} only
     */
    private static boolean isDigit(final char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * Tests a single character for the two forms an unpopulated position can take.
     *
     * <p>The front end edits test each component against {@code EQUAL SPACES} and {@code EQUAL
     * LOW-VALUES} separately, at {@code app/cpy/CSUTLDPY.cpy:L30}, {@code :L94} and {@code :L153}, so
     * both must be treated as absent here too. That distinction is preserved at field granularity by
     * {@link #isSpaces} and {@link #isLowValues}; this method is the character granularity form the
     * picture scan of {@link #callCeedays} needs, where a single position is examined at a time.
     *
     * @param character the character to test
     * @return {@code true} for a space or a null byte
     */
    private static boolean isBlankOrNull(final char character) {
        return character == ' ' || character == '\u0000';
    }

    /**
     * Returns the exclusive end of the run of decimal digits beginning at an index, reading no more than
     * a stated number of them.
     *
     * <p>This is the mechanism behind the omitted leading zero rule documented at {@link #callCeedays}:
     * a component is read greedily up to its picture width and stops early at the first character that
     * is not a digit, which under the delimited picture is the separator that follows it. A return value
     * equal to {@code from} means the component supplied no digit at all.
     *
     * <p>The cap is applied before the field end, so a component can never consume a following one: with
     * a cap of two, {@code 123} yields an end two positions on and leaves {@code 3} for whatever the
     * picture requires next.
     *
     * @param text      the field being scanned
     * @param from      the index to begin at, which may equal the field length
     * @param maxDigits the greatest number of digits this component may supply
     * @return the exclusive end index of the digit run, never past the field end and never more than
     *         {@code maxDigits} beyond {@code from}
     */
    private static int digitRunEnd(final String text, final int from, final int maxDigits) {
        final int limit = Math.min(text.length(), from + maxDigits);
        int end = from;
        while (end < limit && isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * Classifies a component that could not be read, by what blocked it.
     *
     * <p>This is the single place the distinction between the two malformed input conditions is decided,
     * and it is what allows the cursor based scan of {@link #callCeedays} to report exactly the condition
     * the earlier fixed offset reading reported for every input that reading also rejected:
     *
     * <ul>
     *   <li>The field ran out, or the blocking character is a space or a null byte. The supplied date
     *       stopped short of what the picture requires, which is
     *       {@link FeedbackCondition#FC_INSUFFICIENT_DATA} - the condition whose own declaration reads
     *       "did not provide every component the mask requires".</li>
     *   <li>The blocking character is present and is not blank, but is the wrong character: a non digit
     *       where a digit is required, or anything other than {@link #DATE_SEPARATOR} where the separator
     *       is required. That is {@link FeedbackCondition#FC_NON_NUMERIC_DATA} - "a position the mask
     *       requires to be numeric was not".</li>
     * </ul>
     *
     * @param text  the field being scanned
     * @param index the index at which the scan could not proceed
     * @return the condition that classifies the shortfall
     */
    private static FeedbackCondition shortfallCondition(final String text, final int index) {
        if (index >= text.length() || isBlankOrNull(text.charAt(index))) {
            return FeedbackCondition.FC_INSUFFICIENT_DATA;
        }
        return FeedbackCondition.FC_NON_NUMERIC_DATA;
    }

    /**
     * Reads a fixed width text field as its {@code PIC 9(n)} redefinition would read it.
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
     * @param date the date to convert
     * @return the COBOL integer date, days since 31 December 1600
     */
    private static long integerOfDate(final LocalDate date) {
        return date.toEpochDay() - INTEGER_DATE_ZERO.toEpochDay();
    }

    /**
     * Trims leading and trailing spaces, as COBOL {@code FUNCTION TRIM} does.
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
     * @param ccyymmdd the eight character date under edit
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
     * @param ccyymmdd the date as eight characters, {@code CCYYMMDD}.
     * @param variableName the field label that prefixes any message, corresponding to
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)}.
     * @return the three component flags, the error indicator and the message
     */
    public EditOutcome editDate(final String ccyymmdd, final String variableName) {
        return performEditDateCcyymmddThruExit(newEditContext(ccyymmdd, variableName));
    }

    /**
     * Runs the composite date edit and then, only if it passed, the date of birth reasonableness check.
     *
     * @param ccyymmdd the date of birth as eight characters, {@code CCYYMMDD}
     * @param variableName the field label that prefixes any message.
     * @return the outcome after the composite edit and, when that passed, the future date check
     * @throws ValidationException if the composite edit passed a value that {@code FUNCTION INTEGER-OF-DATE}
     * still cannot convert, carrying the originating {@link DateTimeException} as its cause
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
     */
    private void editYearCcyyExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-MONTH}, {@code app/cpy/CSUTLDPY.cpy:L91-L144}.
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
     */
    private void editMonthExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DAY}, {@code app/cpy/CSUTLDPY.cpy:L150-L204}.
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
     */
    private void editDayExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}, {@code app/cpy/CSUTLDPY.cpy:L209-L279}.
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
     */
    private void editDayMonthYearExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DATE-LE}, {@code app/cpy/CSUTLDPY.cpy:L284-L321}.
     *
     * <p>The last line of defence, and the fifth call site of the date utility. The source explains
     * itself at {@code :L286-L288}: "In case some one managed to enter a bad date that passsed all the
     * edits above ...... Use LE Services to verify the supplied date". That comment contains two
     * defects of its own, "passsed" with three letter s and "some one" as two
     * words. Quoted rather than silently corrected, because the comment is part of the record.
     *
     * <p><strong>This path uses the eight character mask.</strong> {@code :L291} moves
     * {@code 'YYYYMMDD'} - no separators - whereas the two program call sites move
     * {@code 'YYYY-MM-DD'}. Both masks are mandatory and neither may be dropped; this is the copybook
     * path, so it passes {@link #MASK_YYYYMMDD}.
     *
     * <p>{@code :L293} carries the sequence number {@code 005100} in
     * columns one to six and is the only line in all 375 that does. A hygiene curiosity in the system of
     * record, noted rather than tidied away.
     *
     * <p><strong>A legacy buffer overread that cannot be reproduced.</strong>
     * The source passes {@code WS-EDIT-DATE-CCYYMMDD}, an {@code X(8)} field, and
     * {@code WS-DATE-FORMAT}, an {@code X(08)} field, by reference into the callee's
     * {@code LS-DATE PIC X(10)} and {@code LS-DATE-FORMAT PIC X(10)}. The callee then unconditionally
     * executes {@code MOVE LENGTH OF LS-DATE TO VSTRING-LENGTH}, yielding ten. The date service is
     * therefore handed a length of ten for two eight byte fields and reads two bytes of whatever storage
     * follows each. Java is memory safe, so there is no adjacent storage to read and the behaviour is
     * unreproducible. It is <strong>not</strong> simulated with padding or sentinel bytes, which would
     * invent an outcome the source never defined; it is a labelled deviation. Only this copybook path
     * overreads - the two program paths pass genuine
     * ten character fields.
     *
     * <p><strong>Why the overread is nonetheless benign in the source.</strong> The documented input
     * domain of the date service ignores every character
     * after a valid date has been parsed. The eight digits of {@code WS-EDIT-DATE-CCYYMMDD} complete the
     * date under the {@code YYYYMMDD} picture, so the two overread bytes are never examined whatever they
     * contain. That is why this path works in production rather than failing intermittently, and it is
     * also why not simulating the overread in Java loses no observable behaviour: there is none to lose.
     * The rule and its citations are at {@link #callCeedays}.
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
     */
    private void editDateCcyymmddExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH}, {@code app/cpy/CSUTLDPY.cpy:L341-L369}.
     *
     * @param context the per call work area
     * @throws ValidationException if {@code FUNCTION INTEGER-OF-DATE} cannot convert the supplied components,
     * wrapping the originating {@link DateTimeException}
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
     */
    private void editDateOfBirthExit() {
        // Intentionally empty: COBOL EXIT is a documentary no-op, not a return.
    }

    /**
     * Tests {@code WS-31-DAY-MONTH}, {@code app/cpy/CSUTLDWY.cpy:L21-L23}.
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
     */
    private static final class EditContext {

        /**
         * {@code WS-EDIT-DATE-CCYYMMDD PIC X(8)}, {@code app/cpy/CSUTLDWY.cpy:L4}.
         */
        private final String ccyymmdd;

        /**
         * {@code WS-EDIT-DATE-CCYY}, {@code app/cpy/CSUTLDWY.cpy:L5}.
         */
        private final String ccyy;

        /**
         * {@code WS-EDIT-DATE-CC PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L6}.
         */
        private final String cc;

        /**
         * {@code WS-EDIT-DATE-YY PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L11}.
         */
        private final String yy;

        /**
         * {@code WS-EDIT-DATE-MM PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L16}.
         */
        private final String mm;

        /**
         * {@code WS-EDIT-DATE-DD PIC X(2)}, {@code app/cpy/CSUTLDWY.cpy:L25}.
         */
        private final String dd;

        /**
         * {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, already trimmed as {@code FUNCTION TRIM} does.
         */
        private final String variableName;

        /**
         * {@code WS-EDIT-DATE-MM-N}, the {@code PIC 9(2)} redefinition of the month.
         */
        private int monthValue;

        /**
         * {@code WS-EDIT-DATE-DD-N}, the {@code PIC 9(2)} redefinition of the day.
         */
        private int dayValue;

        /**
         * {@code WS-EDIT-YEAR-FLG}, {@code app/cpy/CSUTLDWY.cpy:L46}.
         */
        private EditFlag yearFlag = EditFlag.NOT_OK;

        /**
         * {@code WS-EDIT-MONTH}, {@code app/cpy/CSUTLDWY.cpy:L50}.
         */
        private EditFlag monthFlag = EditFlag.NOT_OK;

        /**
         * {@code WS-EDIT-DAY}, {@code app/cpy/CSUTLDWY.cpy:L54}.
         */
        private EditFlag dayFlag = EditFlag.NOT_OK;

        /**
         * {@code INPUT-ERROR}, {@code app/cbl/COACTUPC.cbl:L173}.
         */
        private boolean inputError;

        /**
         * {@code WS-RETURN-MSG PIC X(75)}, spaces while the latch is still off.
         */
        private String returnMessage = " ".repeat(RETURN_MESSAGE_LENGTH);

        /**
         * Whether a {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} has been taken.
         */
        private boolean branchToCcyymmddExit;

        /**
         * Splits an eight character date into the components the work area declares.
         *
         * @param rawCcyymmdd the eight character date text, already fixed to width
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
         * Sets {@code INPUT-ERROR} to true. Once set it is never cleared within one edit, exactly as the source
         * never clears it.
         */
        private void setInputError() {
            this.inputError = true;
        }

        /**
         * Applies the first error wins latch and records a message.
         *
         * @param literal the message literal to append to the trimmed field label, reproduced byte for byte
         * from the source including any leading or trailing space
         */
        private void emitMessage(final String literal) {
            if (returnMessage.isBlank()) {
                returnMessage = fixedWidth(variableName + literal, RETURN_MESSAGE_LENGTH);
            }
        }

        /**
         * Tests {@code WS-EDIT-DATE-IS-VALID}, {@code app/cpy/CSUTLDWY.cpy:L44}.
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
         * {@code MOVE WS-EDIT-DATE-FLGS TO ...} plus its reads of {@code INPUT-ERROR} and {@code WS-RETURN-MSG}
         */
        private EditOutcome toOutcome() {
            return new EditOutcome(yearFlag, monthFlag, dayFlag, inputError, returnMessage);
        }
    }
}
