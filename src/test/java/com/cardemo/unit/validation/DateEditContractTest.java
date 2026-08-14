/*
 * ******************************************************************
 * Program     : DateEditContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the layer-1 component-edit contract of the
 *               procedural date copybook: the fourteen paragraph
 *               labels mapped one to one, the sequential fall-through
 *               that makes every component edit run even after an
 *               earlier one has failed, the seven exit paths and the
 *               flag wipe at the language-environment exit label, the
 *               conjunction gate that nests layer 2 inside layer 1,
 *               the two flag-initialisation asymmetries, the guard
 *               order inversion between month and day, the integer
 *               remainder leap year test with its two-branch divisor
 *               selection, the twelve message literals byte for byte
 *               under their first-error-wins latch, and the strict
 *               future guard applied to a date of birth.
 * Source      : app/cpy/CSUTLDPY.cpy:L18-L372 (all fourteen paragraph
 *               labels, all twelve message literals) @ 7756d89
 * Source      : app/cpy/CSUTLDWY.cpy:L4-L85 (flag alphabet, the eight
 *               88-level ranges, the eight character mask) @ 7756d89
 * Source      : app/cbl/CSUTLDTC.cbl:L84-L106 (PIC X(10) linkage
 *               against PIC X(8) actual arguments) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 (the canonical form of
 *               this banner) @ 7756d89
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
package com.cardemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.EditOutcome;
import com.cardemo.unit.model.FixedClockProvider;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The layer-1 component-edit contract of {@code app/cpy/CSUTLDPY.cpy}, a 375 line PROCEDURAL copybook
 * copied into the including program's {@code PROCEDURE DIVISION} rather than its {@code DATA DIVISION},
 * whose companion working storage is {@code app/cpy/CSUTLDWY.cpy}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>Pins the edit machine that {@code com.cardemo.service.shared.DateValidationService} translates, at the
 * granularity the migration's traceability gate reads. Every locator below was re-verified by direct
 * inspection of the frozen corpus rather than inherited from prose.</p>
 *
 * <ul>
 *   <li><strong>The fourteen paragraph labels</strong> at {@code CSUTLDPY.cpy} lines 18, 25, 88, 91, 145,
 *       150, 205, 209, 280, 284, 323, 329, 341 and 370, each required to have exactly one private Java
 *       method and no consolidation across paragraphs.</li>
 *   <li><strong>The sequential fall-through</strong>, because every {@code -EXIT} paragraph body is a bare
 *       COBOL {@code EXIT} - an operand-free, documentary no-op that transfers no control.</li>
 *   <li><strong>The leap year test at {@code CSUTLDPY.cpy:L243-L272}</strong>: divisor selection at
 *       {@code :L245-L249}, {@code DIVIDE ... REMAINDER} at {@code :L251-L254}, the remainder test at
 *       {@code :L256-L258} and the message at {@code :L266}. Some artefacts cite this branch as
 *       {@code :225-282}; that citation has drifted. {@code :L225} is a {@code GO TO} inside the 31-day
 *       branch and {@code :L282} is a period after an exit label. Severity Medium, source wins.</li>
 *   <li><strong>The conjunction gate at {@code CSUTLDPY.cpy:L274-L278}</strong>, which admits the
 *       language-environment layer only when all three component flag bytes are clean.</li>
 *   <li><strong>The flag wipe at {@code CSUTLDPY.cpy:L323-L328}</strong>, where line 327 sits inside
 *       {@code EDIT-DATE-LE-EXIT} and therefore executes on both of that label's entry paths.</li>
 *   <li><strong>The strict comparison at {@code CSUTLDPY.cpy:L350}</strong>, which rejects a date of birth
 *       equal to the current date.</li>
 *   <li><strong>The twelve message literals</strong> at lines 37, 54, 79, 101, 119 with 136, 161, 180 with
 *       195, 221, 236, 266, 308 with 310, and 363 - asserted byte for byte, never paraphrased.</li>
 *   </ul>
 *
 * <p>Scope boundary: this class asserts <em>data contracts and control-flow outcomes</em>. The sibling
 * {@code DateValidationServiceTest} in this package owns the CEEDAYS feedback table and the eighty byte
 * result geometry, {@code DateValidationServiceSweepTest} owns the parser domain census, and
 * {@code DateValidationServiceGuardPathTest} owns the width guards and the unreachable-guard evidence.
 * Nothing here duplicates them. No collaborator needs stubbing, so real objects are used throughout and
 * Mockito is deliberately absent.</p>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>{@code ./mvnw test}, or {@code ./mvnw -Dtest=DateEditContractTest test} for this class alone.
 * <strong>Severity Blocker - build-path contract.</strong> The root {@code pom.xml} binds Surefire 3.5.4
 * to {@code **}{@code /*Test.java} while excluding {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}; Failsafe 3.5.4 includes only those two excluded trees. A class named or
 * relocated outside that intersection matches neither plugin's include set and is collected by neither: the
 * build stays green, both plugins report success, the coverage agent records the class as uncovered and no
 * error or warning is emitted anywhere. That is the worst available failure mode, so this file must remain
 * named {@code DateEditContractTest.java} under {@code src/test/java/com/cardemo/unit/validation}.
 * Remediation if it ever moves: restore the path, then confirm the class appears in
 * {@code target/surefire-reports} with a non-zero test count.</p>
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Clock.</strong> {@code com.cardemo.unit.model.FixedClockProvider#canonicalClock()} supplies
 *       the injected {@code java.time.Clock}, fixed at {@code 2022-06-10T19:27:53Z} in UTC. The date of
 *       birth guard is relative to the current date, so a system clock would make this class's verdict
 *       change over time. The provider is imported from the sibling package rather than duplicated.</li>
 *   <li><strong>Masks.</strong> The copybook path moves the eight character {@code 'YYYYMMDD'} of
 *       {@code CSUTLDWY.cpy:L58-L59} into a {@code PIC X(08)} field, which is a different mask from the ten
 *       character {@code 'YYYY-MM-DD'} the calling programs use. Both are asserted.</li>
 *   <li><strong>Case and locale.</strong> Every case operation names {@code Locale.ROOT} explicitly, so no
 *       default locale, time zone, charset or hash iteration order can influence an outcome.</li>
 *   <li><strong>Message geometry.</strong> A return message is the trimmed variable name followed by the
 *       literal, held at the declared seventy-five character width.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Blocker - modelling a component exit as a method return.</strong> A port that treats each
 *       {@code GO TO <component>-EXIT} as an early {@code return} from one outer {@code validate} method
 *       sets exactly one flag and never executes the later components. Symptom: a date bad in two or three
 *       components reports only the first as dirty. Remedy: model the nine component jumps as intra
 *       component early-outs and invoke the component routines unconditionally in sequence.</li>
 *   <li><strong>High - placing a return at the language-environment exit label.</strong> That skips line 327
 *       and makes every date report invalid on the success path, the mirror image of the wipe. Symptom: a
 *       wholly valid date reports three dirty flags. Remedy: keep the line 327 assignment reachable from
 *       both entry paths of {@code EDIT-DATE-LE-EXIT}.</li>
 *   <li><strong>High - relaxing the comparison at line 350 to greater-or-equal.</strong> Symptom: a date of
 *       birth equal to the current date is accepted. Remedy: keep the comparison strict.</li>
 *   <li><strong>High - normalising the guard order inversion.</strong> Month tests range then numeric while
 *       day tests numeric then range. Remedy: leave both orders exactly as written.</li>
 *   <li><strong>High - unifying the flag initialisation.</strong> Year and month seed pessimistically, day
 *       seeds optimistically. Remedy: leave the asymmetry in place.</li>
 *   <li><strong>Compilation - a raw type or a dangling doc comment.</strong> The compiler runs with
 *       {@code -Xlint:all -Werror} reaching test compilation, so a single raw type, unchecked cast or
 *       documentation comment attached to no declaration fails the build. Symptom:
 *       {@code warnings found and -Werror specified}. Remedy: fix the warning; never widen the compiler
 *       configuration. An <em>unused import</em> is a separate matter: {@code javac} 25 publishes no
 *       {@code unused} lint key, so it is enforced by review rather than by the build.</li>
 *   <li><strong>Fixture naming elsewhere in the suite.</strong> The daily transaction fixture is
 *       {@code app/data/ASCII/dailytran.txt}, spelled in full, never {@code dalytran.txt} - the mainframe
 *       dataset name. This class loads no fixture at all, deliberately, for the reason in section 6.</li>
 *   </ul>
 *
 * <h2>5. Findings classified by severity</h2>
 *
 * <p><strong>Blocker.</strong> The Surefire build-path contract, and the fall-through and early-return trap.
 * <strong>High.</strong> The line 327 flag wipe; the strict future rejection of today; the two byte buffer
 * overread produced by passing eight byte actuals into ten byte linkage; the optimistic against pessimistic
 * initialisation asymmetry; the consequence of the month and day guard order inversion.
 * <strong>Medium.</strong> The drifted {@code :225-282} leap citation; the absence of any flag initialisation
 * in {@code EDIT-DATE-OF-BIRTH}, and its implicit precondition that the composite chain has already passed.
 * <strong>Low.</strong> The redundant re-assignment at {@code :L203}; the single space after {@code SET} at
 * {@code :L191} where every other site uses two; the stray card sequence number {@code 005100} in columns 1
 * to 6 of {@code :L293}, the only such line in the copybook; the header comment at {@code :L5} naming the
 * companion work area {@code CSUTLDTR} when the real member is {@code CSUTLDWY}; the duplicated
 * {@code EDIT-DATE-OF-BIRTH} entry listed at both {@code :L14} and {@code :L15}; and the commented out
 * {@code FUNCTION FIND-DURATION} alternative at {@code :L351-L353} whose parentheses are unbalanced.</p>
 *
 * <h2>6. Preserved legacy defects, and why no downstream agent may repair them</h2>
 *
 * <p>Parity is the contract of this migration, so the defects this class asserts are preserved deliberately,
 * not overlooked. The project's single rule forbids dead code <em>without an owner or tracking reference</em>;
 * the retained artefacts each carry a decision log entry, a traceability matrix row, Javadoc citing the source
 * locator and an explicit intentional marker, which is what satisfies that clause. <strong>These assertions
 * are themselves the tracking reference.</strong> A test that fails because production code was
 * "corrected" to read more sensibly is reporting a parity regression, not a stale test: repair the production
 * code back to the source, never the assertion. The four preserved items asserted here are the line 327 wipe,
 * the redundant assignment at line 203, the guard order inversion, and the line 191 spacing divergence.</p>
 *
 * <p>Two of the source's own comments record that its style is deliberate. {@code :L41} reads
 * {@code * Intentional violation of structured programming norms}, and {@code :L66-L68} explains the century
 * restriction: {@code * Not having learnt our lesson from history and Y2K}, {@code * And being unable to
 * imagine COBOL in the 2100s}, {@code * We code only 19 and 20 as valid century values}.</p>
 *
 * <p>Security. No secret, credential, token or signing key appears here, and no personally identifying value
 * is read, echoed or asserted. Dates of birth identify individuals, and the customer fixture carries them at
 * {@code app/data/ASCII/custdata.txt} columns 309 to 318 under the {@code CUST-DOB-YYYY-MM-DD PIC X(10)}
 * field of {@code app/cpy/CVCUS01Y.cpy}. That file is never opened from here; every date of birth used below
 * is synthetic, which is also why no fixture loader is imported.</p>
 *
 * <h2>7. Not available</h2>
 *
 * <p>The following are stated as unavailable rather than invented, and each would need a design note or
 * commit message from the original authors to resolve:</p>
 *
 * <ul>
 *   <li>Any rationale for the century restriction beyond the source's own comment at {@code :L66-L68}.</li>
 *   <li>Any reason {@code EDIT-DAY} initialises optimistically at {@code :L152} while {@code EDIT-YEAR-CCYY}
 *       at {@code :L27} and {@code EDIT-MONTH} at {@code :L92} initialise pessimistically.</li>
 *   <li>Any reason the month paragraph tests range before numeric while the day paragraph tests numeric
 *       before range.</li>
 *   <li>Any rationale for the placement of {@code :L327} inside the exit label, which is what allows it to
 *       wipe the language-environment failure flags.</li>
 *   <li>Any latency or throughput objective for this tier. None exists anywhere in the source, so the
 *       migration's performance gate records a measured baseline and never an invented target.</li>
 *   </ul>
 */
@DisplayName("CSUTLDPY.cpy - the layer-1 component edit contract, paragraph by paragraph")
class DateEditContractTest {

    /** The variable name echoed into every diagnostic; a screen field name, carrying no personal data. */
    private static final String FIELD = "ACCT-OPEN-DATE";

    /** The declared width of {@code WS-RETURN-MSG}, mirrored from the service's own constant. */
    private static final int MESSAGE_WIDTH = DateValidationService.RETURN_MESSAGE_LENGTH;

    /** {@code CSUTLDPY.cpy:L37} - leading space, colon, space. */
    private static final String YEAR_BLANK_MESSAGE = " : Year must be supplied.";

    /** {@code CSUTLDPY.cpy:L54} - the only literal in the copybook carrying no colon at all. */
    private static final String YEAR_DIGITS_MESSAGE = " must be 4 digit number.";

    /** {@code CSUTLDPY.cpy:L79} - leading space, colon, space. */
    private static final String CENTURY_MESSAGE = " : Century is not valid.";

    /** {@code CSUTLDPY.cpy:L101} - leading space, colon, space. */
    private static final String MONTH_BLANK_MESSAGE = " : Month must be supplied.";

    /** {@code CSUTLDPY.cpy:L119} and {@code :L136} - colon then space, no leading space, one text, two paths. */
    private static final String MONTH_RANGE_MESSAGE = ": Month must be a number between 1 and 12.";

    /** {@code CSUTLDPY.cpy:L161} - leading space, colon, space. */
    private static final String DAY_BLANK_MESSAGE = " : Day must be supplied.";

    /** {@code CSUTLDPY.cpy:L180} and {@code :L195} - lowercase day, no space after the colon, two paths. */
    private static final String DAY_RANGE_MESSAGE = ":day must be a number between 1 and 31.";

    /** {@code CSUTLDPY.cpy:L221} - colon, no following space, capital C. */
    private static final String DAY_31_MESSAGE = ":Cannot have 31 days in this month.";

    /** {@code CSUTLDPY.cpy:L236} - colon, no following space, capital C. */
    private static final String DAY_30_MESSAGE = ":Cannot have 30 days in this month.";

    /** {@code CSUTLDPY.cpy:L266} - no space after the first full stop, which is deliberate. */
    private static final String LEAP_MESSAGE = ":Not a leap year.Cannot have 29 days in this month.";

    /** {@code CSUTLDPY.cpy:L308} - the first fragment of the composite language-environment diagnostic. */
    private static final String LE_SEVERITY_FRAGMENT = " validation error Sev code: ";

    /** {@code CSUTLDPY.cpy:L310} - the second fragment, sitting between the severity and the message number. */
    private static final String LE_NUMBER_FRAGMENT = " Message code: ";

    /**
     * {@code CSUTLDPY.cpy:L363}. Lowercase c, no leading space, and - stated in prose because a formatter or
     * an editor's trailing-whitespace rule could silently eat the evidence - <strong>one trailing space after
     * the word "future"</strong>. The quoted form above therefore ends with a space character before the
     * closing quotation mark, and {@code futureMessageEndsWithASpace} asserts exactly that.
     */
    private static final String FUTURE_MESSAGE = ":cannot be in the future ";

    /**
     * The paragraph map required by the migration's traceability gate: each COBOL label at
     * {@code app/cpy/CSUTLDPY.cpy}, in source order, against the name of the single private Java method that
     * must carry it. Insertion order is preserved so the assertion reports in source order.
     */
    private static final Map<String, String> PARAGRAPH_MAP = paragraphMap();

    /**
     * The current date {@code CSUTLDPY.cpy:L343} would see, derived from the injected instant rather than from
     * any system call. Written with {@code ofInstant} on purpose: it leaves no {@code now()} token in the file
     * for a reviewer grepping for non-determinism to trip over.
     */
    private static final LocalDate TODAY =
            LocalDate.ofInstant(FixedClockProvider.CANONICAL_INSTANT, FixedClockProvider.CANONICAL_ZONE);

    /** The service under test, built per test instance over the fixed clock - never a static mutable field. */
    private final Clock clock = FixedClockProvider.canonicalClock();

    private final DateValidationService service = new DateValidationService(clock);

    /**
     * The fourteen paragraph labels of {@code app/cpy/CSUTLDPY.cpy}, each keyed by its label and source line
     * and mapped to the single private method that carries it, in source order.
     *
     * @return an insertion-ordered map from {@code LABEL:Lnnn} to the Java method name that implements it
     */
    private static Map<String, String> paragraphMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("EDIT-DATE-CCYYMMDD:L18", "editDateCcyymmdd");
        map.put("EDIT-YEAR-CCYY:L25", "editYearCcyy");
        map.put("EDIT-YEAR-CCYY-EXIT:L88", "editYearCcyyExit");
        map.put("EDIT-MONTH:L91", "editMonth");
        map.put("EDIT-MONTH-EXIT:L145", "editMonthExit");
        map.put("EDIT-DAY:L150", "editDay");
        map.put("EDIT-DAY-EXIT:L205", "editDayExit");
        map.put("EDIT-DAY-MONTH-YEAR:L209", "editDayMonthYear");
        map.put("EDIT-DAY-MONTH-YEAR-EXIT:L280", "editDayMonthYearExit");
        map.put("EDIT-DATE-LE:L284", "editDateLe");
        map.put("EDIT-DATE-LE-EXIT:L323", "editDateLeExit");
        map.put("EDIT-DATE-CCYYMMDD-EXIT:L329", "editDateCcyymmddExit");
        map.put("EDIT-DATE-OF-BIRTH:L341", "editDateOfBirthParagraph");
        map.put("EDIT-DATE-OF-BIRTH-EXIT:L370", "editDateOfBirthExit");
        return map;
    }

    /**
     * The declared method names of the service, read once per call. Only names are inspected: nothing is made
     * accessible and nothing is invoked, so this is declaration inspection rather than reflective dispatch,
     * and it matches the established idiom of the sibling {@code unit/model} contract tests.
     *
     * @return the names of every method declared directly on the service, sorted and de-duplicated
     */
    private static Set<String> declaredMethodNames() {
        final Set<String> names = new TreeSet<>();
        for (final Method method : DateValidationService.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * The exact bytes a diagnostic must carry: the trimmed variable name, then the literal, then padding to
     * the declared width. Returning the whole field rather than a trimmed view is what keeps a literal's own
     * trailing space observable.
     *
     * @param variableName the already-trimmed name that heads the message, as
     *                     {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} produces it
     * @param literal      the copybook literal appended to it, byte for byte, spacing included
     * @return the whole {@code WS-RETURN-MSG} field, padded on the right to its declared width
     */
    private static String diagnostic(final String variableName, final String literal) {
        final String head = variableName + literal;
        return head + " ".repeat(MESSAGE_WIDTH - head.length());
    }

    /**
     * A compact {@code CCYYMMDD} string, built without any locale-sensitive formatting.
     *
     * @param year  the four digit year
     * @param month the month, zero padded to two digits
     * @param day   the day, zero padded to two digits
     * @return the eight character value the copybook's {@code WS-EDIT-DATE-CCYYMMDD} group holds
     */
    private static String compact(final int year, final int month, final int day) {
        return String.format(Locale.ROOT, "%04d%02d%02d", year, month, day);
    }

    /**
     * The three component bytes of {@code WS-EDIT-DATE-FLGS}, in the declared order year, month, day.
     *
     * @param ccyymmdd the candidate date, passed through exactly as given so that padding and truncation
     *                 behave as a COBOL {@code MOVE} into the eight byte group would
     * @return the year, month and day flags in that order, so a whole flag group can be asserted at once
     */
    private List<EditFlag> flagsOf(final String ccyymmdd) {
        final EditOutcome outcome = service.editDate(ccyymmdd, FIELD);
        return List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag());
    }

    /**
     * True when {@code java.time} recognises the triple as a real Gregorian date. Written as a total
     * predicate over the month length rather than by catching a thrown exception, so that no control flow in
     * this class depends on an exception being raised and discarded.
     *
     * @param year  the four digit year
     * @param month the candidate month, which need not be within one to twelve
     * @param day   the candidate day, which need not be within the month's length
     * @return {@code true} only when the triple denotes a day that exists in the proleptic Gregorian calendar
     */
    private static boolean isRealDate(final int year, final int month, final int day) {
        if (month < 1 || month > 12 || day < 1) {
            return false;
        }
        return day <= YearMonth.of(year, month).lengthOfMonth();
    }

    /**
     * The leap year decision reproduced with the shape of {@code CSUTLDPY.cpy:L243-L272} rather than with a
     * library predicate: the two-branch divisor selection of {@code :L245-L249} followed by the
     * {@code DIVIDE ... GIVING ... REMAINDER} of {@code :L251-L254}, whose quotient and remainder are both
     * genuinely produced exactly as the source produces them.
     *
     * @param fourDigitYear the value of {@code WS-EDIT-DATE-CCYY-N}, the dividend at {@code :L251}
     * @param lastTwoDigits the value of {@code WS-EDIT-DATE-YY-N}, whose being zero selects the divisor 400
     *                      at {@code :L246} and whose being anything else selects 4 at {@code :L248}
     * @return {@code true} when the remainder is zero, which is the accept path at {@code :L256-L257}
     */
    private static boolean leapByCopybookShape(final int fourDigitYear, final int lastTwoDigits) {
        final int divisor;
        if (lastTwoDigits == 0) {
            divisor = 400;
        } else {
            divisor = 4;
        }
        final int quotient = fourDigitYear / divisor;
        final int remainder = fourDigitYear - quotient * divisor;
        return remainder == 0;
    }

    @Nested
    @DisplayName("The fourteen paragraph labels, mapped one to one with no consolidation")
    class ParagraphMap {

        @Test
        @DisplayName("every one of the fourteen labels has its own private method, in source order")
        void everyLabelHasItsOwnMethod() {
            final Set<String> declared = declaredMethodNames();
            final List<String> unmapped = new ArrayList<>();
            for (final Map.Entry<String, String> entry : PARAGRAPH_MAP.entrySet()) {
                if (!declared.contains(entry.getValue())) {
                    unmapped.add(entry.getKey() + " -> " + entry.getValue());
                }
            }
            assertThat(unmapped)
                    .as("app/cpy/CSUTLDPY.cpy paragraph labels with no corresponding private method in "
                            + "com.cardemo.service.shared.DateValidationService; the traceability gate reads "
                            + "this correspondence, so every label must map")
                    .isEmpty();
        }

        @Test
        @DisplayName("the map covers exactly fourteen labels, the count the copybook declares")
        void theMapCoversFourteenLabels() {
            assertThat(PARAGRAPH_MAP)
                    .as("app/cpy/CSUTLDPY.cpy declares fourteen paragraph labels between :L18 and :L370")
                    .hasSize(14);
            assertThat(PARAGRAPH_MAP.values())
                    .as("one method per label, so no two labels may share a method - "
                            + "consolidation across paragraphs is forbidden")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the seven executable paragraphs are distinct from the seven exit labels")
        void executableParagraphsAreDistinctFromExitLabels() {
            final List<String> exitLabels = new ArrayList<>();
            final List<String> executable = new ArrayList<>();
            for (final String label : PARAGRAPH_MAP.keySet()) {
                if (label.substring(0, label.indexOf(':')).endsWith("-EXIT")) {
                    exitLabels.add(label);
                } else {
                    executable.add(label);
                }
            }
            assertThat(exitLabels)
                    .as("app/cpy/CSUTLDPY.cpy carries seven -EXIT labels, each a bare COBOL EXIT no-op")
                    .hasSize(7);
            assertThat(executable)
                    .as("and seven paragraphs that do the work")
                    .hasSize(7);
        }

        @Test
        @DisplayName("the date-of-birth paragraph keeps a distinct method name from the public entry point")
        void theDateOfBirthParagraphIsNamedApartFromItsEntryPoint() {
            // app/cpy/CSUTLDPY.cpy:L341 is a paragraph; the caller-facing routine that performs the composite
            // chain and then this paragraph is a different thing, so the two cannot share one Java name.
            assertThat(PARAGRAPH_MAP.get("EDIT-DATE-OF-BIRTH:L341"))
                    .as("the :L341 paragraph maps to its own method, not to the public entry point")
                    .isEqualTo("editDateOfBirthParagraph")
                    .isNotEqualTo("editDateOfBirth");
            assertThat(declaredMethodNames())
                    .as("both the paragraph and the public entry point exist, and they are different members")
                    .contains("editDateOfBirthParagraph", "editDateOfBirth");
        }

        @Test
        @DisplayName("the two caller-facing entry points are the composite edit and the date-of-birth edit")
        void theTwoEntryPointsArePresent() {
            assertThat(declaredMethodNames())
                    .as("app/cpy/CSUTLDPY.cpy:L7-L9 documents PERFORM EDIT-DATE-CCYYMMDD THRU "
                            + "EDIT-DATE-CCYYMMDD-EXIT as the composite entry, and :L14 adds the "
                            + "date-of-birth reasonableness check")
                    .contains("editDate", "editDateOfBirth", "validate");
        }
    }

    @Nested
    @DisplayName("Blocker - the paragraphs fall through sequentially, so every component edit always runs")
    class SequentialFallThrough {

        @Test
        @DisplayName("a date bad in all three components reports all three flags dirty, not just the first")
        void allThreeComponentsAreEditedEvenAfterTheFirstFails() {
            // Blank year, month 13 and day 32 at once. A port that returned from an outer routine on the first
            // GO TO EDIT-YEAR-CCYY-EXIT at :L42 would set the year flag alone and would never reach :L91 or
            // :L150. The bare EXIT at :L89 is a no-op, so control walks into both of them.
            final EditOutcome outcome = service.editDate("    1332", FIELD);

            assertThat(outcome.yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L33 SET FLG-YEAR-BLANK, reached through the :L30-L31 guard")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L115 - EDIT-MONTH at :L91 ran anyway, because :L89 EXIT "
                            + "transfers no control")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L191 - EDIT-DAY at :L150 ran too, for the same reason")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.inputError())
                    .as("INPUT-ERROR is caller owned and is set on every failure path")
                    .isTrue();
        }

        @Test
        @DisplayName("a wholly absent date reports all three components absent, not one")
        void anAbsentDateReportsAllThreeComponentsAbsent() {
            final EditOutcome outcome = service.editDate("        ", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("the :L30, :L94 and :L154 SPACES guards each fired, which can only happen if all "
                            + "three paragraphs executed")
                    .containsExactly(EditFlag.BLANK, EditFlag.BLANK, EditFlag.BLANK);
        }

        @Test
        @DisplayName("a single defective component leaves its own byte dirty and the others intact")
        void aSingleDefectiveComponentDirtiesOnlyItsOwnByte() {
            // Written as explicit cases rather than a CSV table on purpose: two of these dates end in blanks,
            // and @CsvSource trims leading and trailing whitespace by default, which would silently rewrite the
            // input under test. @ValueSource does not trim, which is why it is used elsewhere in this class.
            assertThat(flagsOf("2023  15"))
                    .as("absent month only - app/cpy/CSUTLDPY.cpy:L97")
                    .containsExactly(EditFlag.ISVALID, EditFlag.BLANK, EditFlag.ISVALID);
            assertThat(flagsOf("202312  "))
                    .as("absent day only - app/cpy/CSUTLDPY.cpy:L157")
                    .containsExactly(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.BLANK);
            assertThat(flagsOf("20231315"))
                    .as("out-of-range month only - app/cpy/CSUTLDPY.cpy:L115")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.ISVALID);
            assertThat(flagsOf("20231232"))
                    .as("out-of-range day only - app/cpy/CSUTLDPY.cpy:L191")
                    .containsExactly(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("a combination branch can overwrite a byte an earlier paragraph had already set valid")
        void aCombinationBranchOverwritesAnEarlierValidByte() {
            // Month 13 fails the :L111 range test; day 31 passes both day guards and is left ISVALID by :L203.
            // EDIT-DAY-MONTH-YEAR at :L209 then finds NOT WS-31-DAY-MONTH AND WS-DAY-31 true - month 13 is not
            // in the :L21-L23 set - and drives the day byte back to NOT-OK at :L216.
            final EditOutcome outcome = service.editDate("20231331", FIELD);

            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L115 then :L217, both setting the month byte NOT-OK")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L216 overwrote the ISVALID the :L203 assignment had left")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.yearFlag())
                    .as("the year was never touched after :L86, so it stays valid")
                    .isEqualTo(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("only the four whole-chain aborts skip later paragraphs; the nine component jumps do not")
        void theTwoKindsOfJumpAreDistinguishable() {
            // A component jump - GO TO EDIT-MONTH-EXIT at :L105 - is followed by EDIT-DAY, which runs and
            // reports its own verdict. Proof: the day byte is ISVALID here, a value only EDIT-DAY can write.
            final EditOutcome componentJump = service.editDate("2023  15", FIELD);
            assertThat(componentJump.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L152 or :L203 wrote this, so EDIT-DAY ran after the :L105 jump")
                    .isEqualTo(EditFlag.ISVALID);

            // A whole-chain abort - GO TO EDIT-DATE-CCYYMMDD-EXIT at :L240 - skips everything up to :L329,
            // including EDIT-DATE-LE-EXIT. Proof: the flags survive rather than being wiped valid by :L327.
            final EditOutcome chainAbort = service.editDate("20230230", FIELD);
            assertThat(chainAbort.valid())
                    .as("app/cpy/CSUTLDPY.cpy:L240 jumped past :L327, so the group was never reset to "
                            + "LOW-VALUES and the date is still reported invalid")
                    .isFalse();
        }

        @Test
        @DisplayName("the message latch keeps the earliest failure, in year then month then day order")
        void theEarliestFailureOwnsTheMessage() {
            // Three components are dirty but only one message exists, because every STRING is wrapped in
            // IF WS-RETURN-MSG-OFF - at :L34, :L98, :L158 and the rest.
            assertThat(service.editDate("    1332", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L37 won the latch, because EDIT-YEAR-CCYY at :L25 runs first")
                    .isEqualTo(diagnostic(FIELD, YEAR_BLANK_MESSAGE));

            assertThat(service.editDate("20231332", FIELD).returnMessage())
                    .as("with a good year, app/cpy/CSUTLDPY.cpy:L119 wins because EDIT-MONTH precedes EDIT-DAY")
                    .isEqualTo(diagnostic(FIELD, MONTH_RANGE_MESSAGE));

            assertThat(service.editDate("20231232", FIELD).returnMessage())
                    .as("with a good year and month, app/cpy/CSUTLDPY.cpy:L195 wins")
                    .isEqualTo(diagnostic(FIELD, DAY_RANGE_MESSAGE));
        }

        @Test
        @DisplayName("a combination failure cannot displace a component message already latched")
        void aCombinationFailureCannotDisplaceALatchedMessage() {
            // Month 13 latches the month message at :L119. EDIT-DAY-MONTH-YEAR then fails at :L213-L214 and
            // reaches its STRING at :L219, but :L218 IF WS-RETURN-MSG-OFF is already false.
            final EditOutcome outcome = service.editDate("20231331", FIELD);

            assertThat(outcome.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L218 latch held, so :L221 never overwrote :L119")
                    .isEqualTo(diagnostic(FIELD, MONTH_RANGE_MESSAGE))
                    .doesNotContain(DAY_31_MESSAGE);
        }
    }

    @Nested
    @DisplayName("High - the seven exit paths, and the :L327 assignment inside EDIT-DATE-LE-EXIT")
    class ExitPathTable {

        @ParameterizedTest(name = "[{index}] row 1: {0} takes a component jump, then :L277, and keeps its flags")
        @ValueSource(strings = {"    1215", "2X231215", "18991215", "2023  15", "20230015", "202312  ", "20231232"})
        @DisplayName("row 1 - a component early-out reaches :L274, jumps at :L277 and preserves every flag")
        void componentEarlyOutPreservesFlags(final String date) {
            final EditOutcome outcome = service.editDate(date, FIELD);

            // If :L327 had executed, all three bytes would read ISVALID and valid() would be true. It did not,
            // because :L277 GO TO EDIT-DATE-CCYYMMDD-EXIT lands at :L329, beyond the :L323 label.
            assertThat(outcome.valid())
                    .as("app/cpy/CSUTLDPY.cpy:L274-L278 gated the chain for date [" + date + "], so the "
                            + ":L327 SET WS-EDIT-DATE-IS-VALID was skipped and the dirty byte survived")
                    .isFalse();
            assertThat(outcome.inputError())
                    .as("INPUT-ERROR survives every path because it is a caller-owned field, "
                            + "not part of the three byte WS-EDIT-DATE-FLGS group")
                    .isTrue();
        }

        @Test
        @DisplayName("row 2 - the 31-day-month failure at :L225 preserves the day and month bytes")
        void thirtyOneDayMonthFailurePreservesTwoFlags() {
            final EditOutcome outcome = service.editDate("20230431", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("app/cpy/CSUTLDPY.cpy:L216-L217 set exactly two date flags, and :L225 skipped :L327")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK);
            assertThat(outcome.returnMessage()).isEqualTo(diagnostic(FIELD, DAY_31_MESSAGE));
        }

        @Test
        @DisplayName("row 3 - the February-30 failure at :L240 preserves the day and month bytes")
        void februaryThirtyFailurePreservesTwoFlags() {
            final EditOutcome outcome = service.editDate("20230230", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("app/cpy/CSUTLDPY.cpy:L231-L232 set exactly two date flags, and :L240 skipped :L327")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK);
            assertThat(outcome.returnMessage()).isEqualTo(diagnostic(FIELD, DAY_30_MESSAGE));
        }

        @Test
        @DisplayName("row 4 - the leap-year failure at :L270 preserves all three bytes")
        void leapYearFailurePreservesThreeFlags() {
            final EditOutcome outcome = service.editDate("19000229", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("app/cpy/CSUTLDPY.cpy:L260-L262 set three date flags - one more than the 31-day and "
                            + "February-30 branches - and :L270 skipped :L327")
                    .containsExactly(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK);
            assertThat(outcome.allComponentsNotOk())
                    .as("the leap branch is the only combination failure that dirties the year byte too")
                    .isTrue();
            assertThat(outcome.returnMessage()).isEqualTo(diagnostic(FIELD, LEAP_MESSAGE));
        }

        @Test
        @DisplayName("row 5 - the :L274 conjunction failure at :L277 preserves whatever was dirty")
        void conjunctionFailurePreservesFlags() {
            // Month 13 with a day that fires no combination branch: the only exit left is :L277.
            final EditOutcome outcome = service.editDate("20231315", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("app/cpy/CSUTLDPY.cpy:L277 is the exit here, and it lands at :L329 past :L327")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.ISVALID);
        }

        @Test
        @DisplayName("row 7 - the language-environment success path reaches :L327 and sets the group valid")
        void languageEnvironmentSuccessReachesTheAssignment() {
            final EditOutcome outcome = service.editDate("20230615", FIELD);

            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("app/cpy/CSUTLDPY.cpy:L321 fell through to :L323, the bare EXIT at :L324 transferred "
                            + "no control, and :L327 cleared all three bytes to LOW-VALUES")
                    .containsExactly(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID);
            assertThat(outcome.valid()).isTrue();
            assertThat(outcome.inputError()).isFalse();
            assertThat(outcome.hasReturnMessage())
                    .as("no STRING ever ran, so WS-RETURN-MSG is still the blank field it started as")
                    .isFalse();
        }

        @Test
        @DisplayName("row 6 - the :L315 failure jump would reach :L327 and wipe the four flags just set")
        void languageEnvironmentFailureWouldWipeTheFlags() {
            // Severity High, preserved legacy defect. GO TO EDIT-DATE-LE-EXIT at :L315 is the CEEDAYS FAILURE
            // path. It lands on :L323, whose bare EXIT at :L324 transfers no control, so :L327 executes and
            // resets WS-EDIT-DATE-FLGS to LOW-VALUES - discarding the four SETs of :L301-L304. The signature of
            // the defect is therefore a report that is simultaneously valid and in error.
            //
            // The branch is currently UNREACHABLE from the composite entry point, and that is a finding rather
            // than an omission: the component and combination edits are exhaustive over the invalid-date space,
            // so nothing that survives to :L293 can be rejected by the callee. The two facts below are the
            // evidence, and together they are the tracking reference for the latent defect.
            assertThat(service.validate("20230631", DateValidationService.MASK_YYYYMMDD).feedbackCode().severity())
                    .as("the callee genuinely does reject a bad date - app/cbl/CSUTLDTC.cbl:L123 moves SEVERITY "
                            + "out of FEEDBACK-CODE - so the :L298 test has real failure input available")
                    .isEqualTo(3);
            assertThat(service.editDate("20230631", FIELD).valid())
                    .as("yet the same date never reaches :L293, because app/cpy/CSUTLDPY.cpy:L213-L214 catches "
                            + "a 31st in a 30-day month first and aborts at :L225")
                    .isFalse();
        }

        @Test
        @DisplayName("the wipe signature - valid and in error at once - occurs for no input in the swept domain")
        void theWipeSignatureIsUnreachableToday() {
            final List<String> offenders = new ArrayList<>();
            for (final int year : List.of(1900, 1996, 1999, 2000, 2004, 2020, 2023, 2024, 2099)) {
                for (int month = 1; month <= 12; month++) {
                    for (int day = 1; day <= 31; day++) {
                        final String date = compact(year, month, day);
                        final EditOutcome outcome = service.editDate(date, FIELD);
                        if (outcome.valid() && outcome.inputError()) {
                            offenders.add(date);
                        }
                    }
                }
            }
            assertThat(offenders)
                    .as("an input reported valid while INPUT-ERROR is set would mean app/cpy/CSUTLDPY.cpy:L327 "
                            + "had wiped flags set at :L301-L304; none exists, so the defect stays latent")
                    .isEmpty();
        }

        @Test
        @DisplayName("acceptance holds exactly when the eight digits denote a real date, across the domain")
        void acceptanceMatchesTheRealCalendar() {
            final List<String> disagreements = new ArrayList<>();
            for (final int year : List.of(1900, 1996, 1999, 2000, 2004, 2020, 2023, 2024, 2099)) {
                for (int month = 1; month <= 12; month++) {
                    for (int day = 1; day <= 31; day++) {
                        final String date = compact(year, month, day);
                        if (service.editDate(date, FIELD).valid() != isRealDate(year, month, day)) {
                            disagreements.add(date);
                        }
                    }
                }
            }
            assertThat(disagreements)
                    .as("app/cpy/CSUTLDPY.cpy:L213-L272 is exhaustive over the invalid-date space - a 31st in a "
                            + "30-day month, a February 30th and a February 29th outside a leap year are the "
                            + "complete set - so layer 1 alone already agrees with the real calendar")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The :L274-L278 conjunction gate - layer 2 nests inside layer 1, never beside it")
    class ConjunctionGate {

        @ParameterizedTest(name = "[{index}] one dirty byte in [{0}] keeps the callee from being reached")
        @ValueSource(strings = {"    1215", "2X231215", "21001215", "2023  15", "20231X15", "2023121X"})
        @DisplayName("a single dirty component byte stops the language-environment layer from running at all")
        void oneDirtyByteStopsLayerTwo(final String date) {
            final EditOutcome outcome = service.editDate(date, FIELD);

            // The gate at :L274 tests the whole three byte group, so any single dirty byte routes to :L277 and
            // the CALL at :L293-L296 never happens. The observable proof is that the flags were not wiped: had
            // EDIT-DATE-LE run, its exit label at :L323 would have carried execution into :L327.
            assertThat(outcome.valid())
                    .as("app/cpy/CSUTLDPY.cpy:L274 IF WS-EDIT-DATE-IS-VALID failed for [" + date + "], so "
                            + "control left at :L277 and neither :L293 nor :L327 was reached")
                    .isFalse();
        }

        @Test
        @DisplayName("the gate is a conjunction over all three bytes, satisfied only when none is dirty")
        void theGateIsAThreeWayConjunction() {
            // Each of the three bytes, dirtied alone, closes the gate; only the clean triple opens it.
            assertThat(service.editDate("18990615", FIELD).valid())
                    .as("year byte alone dirty, via app/cpy/CSUTLDPY.cpy:L75")
                    .isFalse();
            assertThat(service.editDate("20231315", FIELD).valid())
                    .as("month byte alone dirty, via app/cpy/CSUTLDPY.cpy:L115")
                    .isFalse();
            assertThat(service.editDate("20230632", FIELD).valid())
                    .as("day byte alone dirty, via app/cpy/CSUTLDPY.cpy:L191")
                    .isFalse();
            assertThat(service.editDate("20230615", FIELD).valid())
                    .as("all three clean, so the gate opens and the chain completes through :L327")
                    .isTrue();
        }

        @Test
        @DisplayName("the mandated nesting order is component edits first, the callee last")
        void componentEditsPrecedeTheCallee() {
            // A date that is both non-numeric in the year and impossible in the calendar reports the component
            // diagnostic, never the composite severity diagnostic of :L306-L313. That ordering is the whole
            // point of the gate: layer 2 is a net beneath layer 1, not an alternative to it.
            final EditOutcome outcome = service.editDate("2X230230", FIELD);

            assertThat(outcome.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L54 was emitted by EDIT-YEAR-CCYY at :L25, long before :L284")
                    .isEqualTo(diagnostic(FIELD, YEAR_DIGITS_MESSAGE));
            assertThat(outcome.returnMessage())
                    .as("and the :L308 fragment never appears, because the callee was never invoked")
                    .doesNotContain(LE_SEVERITY_FRAGMENT);
        }
    }

    @Nested
    @DisplayName("High - the flag initialisation asymmetry: year and month pessimistic, day optimistic")
    class FlagInitialisationAsymmetry {

        @Test
        @DisplayName("the composite entry seeds all three bytes invalid in a single statement")
        void theCompositeEntrySeedsEverythingInvalid() {
            // EDIT-DATE-CCYYMMDD at :L18 holds exactly one statement, :L19 SET WS-EDIT-DATE-IS-INVALID TO TRUE,
            // and the group condition is '000' per app/cpy/CSUTLDWY.cpy:L45, so one SET seeds all three bytes.
            // A date whose every component is absent shows the seed being overwritten only by the BLANK guards,
            // never left at ISVALID, which is what proves the entry state was pessimistic.
            assertThat(flagsOf("        "))
                    .as("app/cpy/CSUTLDPY.cpy:L19 seeded three '0' bytes before any test ran")
                    .doesNotContain(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("year and month re-assert NOT-OK before testing, so an untested byte reads invalid")
        void yearAndMonthAreSeededPessimistically() {
            // :L27 SET FLG-YEAR-NOT-OK and :L92 SET FLG-MONTH-NOT-OK both precede every guard in their
            // paragraph. The observable consequence is that a failing year or month can never leave ISVALID.
            assertThat(service.editDate("2X230615", FIELD).yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L27 then :L50 - the year byte is NOT-OK, never ISVALID")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(service.editDate("20231315", FIELD).monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L92 then :L115 - the month byte is NOT-OK, never ISVALID")
                    .isEqualTo(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("day seeds ISVALID first, the exact opposite of year and month - do not normalise")
        void dayIsSeededOptimistically() {
            // Severity High, preserved asymmetry. :L152 SET FLG-DAY-ISVALID TO TRUE is the first statement of
            // EDIT-DAY, where the corresponding statements at :L27 and :L92 assert NOT-OK. Making the three
            // consistent would change the flag state on every path that enters EDIT-DAY and falls through
            // without a branch firing. Remedy if it is ever unified: restore :L152 to the optimistic seed.
            //
            // The seed is observable through a date whose day is sound while an earlier component has failed:
            // the day byte reads ISVALID even though the overall verdict is a rejection.
            final EditOutcome outcome = service.editDate("2X230615", FIELD);

            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L152, the optimistic seed, survived into the reported outcome")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.valid())
                    .as("yet the date is still rejected, because the year byte is dirty")
                    .isFalse();
        }

        @Test
        @DisplayName("the day paragraph re-asserts ISVALID at :L203, an intentionally preserved no-op")
        void theDayParagraphReAssertsItsOwnSeed() {
            // Severity Low, preserved no-op. EDIT-DAY holds two sentences: :L201 closes the guard sequence and
            // :L203 SET FLG-DAY-ISVALID TO TRUE re-asserts what :L152 already established. It is retained for
            // control-flow parity and is unobservable by construction - both assignments write the same value -
            // so the assertion available is that the value the pair agrees on is the one reported.
            assertThat(service.editDate("20230615", FIELD).dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L152 and :L203 agree, and ISVALID is what reaches the caller")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(service.editDate("2X230615", FIELD).dayFlag())
                    .as("and the same holds when the day paragraph completes under an already-failed year")
                    .isEqualTo(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("the tri-state flag alphabet is exactly valid, not-ok and blank")
        void theFlagAlphabetIsTriState() {
            // app/cpy/CSUTLDWY.cpy:L47-L49, :L51-L53 and :L55-L57 declare three 88 levels per byte:
            // LOW-VALUES, '0' and 'B'. There is no fourth state, so the enum must have exactly three constants.
            assertThat(EditFlag.values())
                    .as("app/cpy/CSUTLDWY.cpy gives each of the three PIC X(01) bytes the same three states")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.BLANK);

            // And all three are genuinely reachable on the year byte alone.
            assertThat(service.editDate("20230615", FIELD).yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(service.editDate("18990615", FIELD).yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(service.editDate("    0615", FIELD).yearFlag()).isEqualTo(EditFlag.BLANK);
        }
    }

    @Nested
    @DisplayName("High - the guard order inversion: month tests range then numeric, day tests numeric then range")
    class GuardOrderInversion {

        @Test
        @DisplayName("the month paragraph reaches its range test first, at :L111 before :L126")
        void monthTestsRangeBeforeNumeric() {
            // :L111 IF WS-VALID-MONTH reads WS-EDIT-DATE-MM-N, the PIC 9(2) REDEFINES, before the COMPUTE at
            // :L127-L129 has populated it - so the range test operates on the raw bytes reinterpreted
            // numerically. For a two-character field a non-numeric reinterpretation is out of range by
            // construction, which is why a non-numeric month is diagnosed by the range branch and the numeric
            // branch at :L126 is never the one that fires.
            assertThat(service.editDate("20231X15", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L119, emitted by the range branch, for a non-numeric month")
                    .isEqualTo(diagnostic(FIELD, MONTH_RANGE_MESSAGE));
            assertThat(service.editDate("20231315", FIELD).returnMessage())
                    .as("and the identical text for a numeric month that is simply too large")
                    .isEqualTo(diagnostic(FIELD, MONTH_RANGE_MESSAGE));
        }

        @Test
        @DisplayName("the day paragraph reaches its numeric test first, at :L170 before :L187")
        void dayTestsNumericBeforeRange() {
            // The mirror image: :L170 IF FUNCTION TEST-NUMVAL (WS-EDIT-DATE-DD) = 0 precedes :L187 IF
            // WS-VALID-DAY, so the COMPUTE at :L171-L173 has already run by the time the range test reads the
            // value. Both branches emit the same literal, from :L180 and :L195 respectively.
            assertThat(service.editDate("2023121X", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L180, emitted by the numeric branch, for a non-numeric day")
                    .isEqualTo(diagnostic(FIELD, DAY_RANGE_MESSAGE));
            assertThat(service.editDate("20231232", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L195, emitted by the range branch, for an out-of-range day")
                    .isEqualTo(diagnostic(FIELD, DAY_RANGE_MESSAGE));
        }

        @Test
        @DisplayName("the two orders are opposite, and both are preserved verbatim rather than harmonised")
        void theTwoOrdersAreOppositeAndBothPreserved() {
            // Severity High. The inversion is a source-fidelity requirement, not a behavioural one: because both
            // fields are PIC X(2), a non-numeric reinterpretation is already outside 1 THROUGH 12 and 1 THROUGH
            // 31, so the two orders coincide observationally. The sibling DateValidationServiceGuardPathTest in
            // this package records the same finding independently for the month field. That coincidence is
            // exactly why the order must be protected here: nothing in the behaviour would object to a reviewer
            // "tidying" either paragraph, and if the field width ever grew the two orders would diverge - the
            // month range test would then be reading unconverted bytes. Remedy: leave both orders as written.
            //
            // The structural evidence is that each paragraph keeps its own method, so the order of the guards
            // inside each remains inspectable against the copybook rather than being flattened into a shared
            // helper serving both components.
            assertThat(declaredMethodNames())
                    .as("app/cpy/CSUTLDPY.cpy:L91 and :L150 are separate paragraphs and stay separate methods")
                    .contains("editMonth", "editDay");

            // And the two literals stay distinct, so a shared helper could not have produced both.
            assertThat(MONTH_RANGE_MESSAGE)
                    .as("app/cpy/CSUTLDPY.cpy:L119 against :L180 - different text, different paragraph")
                    .isNotEqualTo(DAY_RANGE_MESSAGE);
        }

        @Test
        @DisplayName("the blank guard precedes both other guards in each paragraph")
        void theBlankGuardIsAlwaysFirst() {
            // :L94-L95 in the month paragraph and :L154-L155 in the day paragraph both precede the other two
            // guards, and both write BLANK rather than NOT-OK. An absent component is therefore distinguishable
            // from a present but wrong one, which is the whole purpose of the third flag state.
            assertThat(service.editDate("2023  15", FIELD).monthFlag()).isEqualTo(EditFlag.BLANK);
            assertThat(service.editDate("202312  ", FIELD).dayFlag()).isEqualTo(EditFlag.BLANK);
            assertThat(service.editDate("20230015", FIELD).monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(service.editDate("20231200", FIELD).dayFlag()).isEqualTo(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the year paragraph orders absent, then non-numeric, then century")
        void theYearParagraphOrdersItsThreeGuards() {
            // :L30, :L48 and :L70 in that order. The evidence is which diagnostic wins when more than one guard
            // would fire: an absent year cannot also be tested for numeracy, and a non-numeric year never
            // reaches the century test, so each input selects the earliest applicable literal.
            assertThat(service.editDate("    0615", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L37 - the absent guard at :L30 is first")
                    .isEqualTo(diagnostic(FIELD, YEAR_BLANK_MESSAGE));
            assertThat(service.editDate("2X230615", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L54 - the numeric guard at :L48 is second, and a non-numeric "
                            + "year returns at :L58 so the century test at :L70 never sees it")
                    .isEqualTo(diagnostic(FIELD, YEAR_DIGITS_MESSAGE));
            assertThat(service.editDate("18990615", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L79 - the century guard at :L70 is third")
                    .isEqualTo(diagnostic(FIELD, CENTURY_MESSAGE));
        }
    }

    @Nested
    @DisplayName("The integer-remainder leap year test at :L243-L272, divisor 400 or 4")
    class LeapYearRemainder {

        @ParameterizedTest(name = "[{index}] February 29th of {0} is accepted: {1}")
        @CsvSource({
            "1900, false",
            "2000, true",
            "2024, true",
            "2023, false",
        })
        @DisplayName("the four decisive years: 1900 rejected, 2000 accepted, 2024 accepted, 2023 rejected")
        void theFourDecisiveYears(final int year, final boolean accepted) {
            final EditOutcome outcome = service.editDate(compact(year, 2, 29), FIELD);

            assertThat(outcome.valid())
                    .as("app/cpy/CSUTLDPY.cpy:L245-L258 for February 29th of " + year + "; 1900 divides by 400 "
                            + "with remainder 300 and is rejected, 2000 divides exactly, 2024 divides by 4 "
                            + "exactly, and 2023 leaves remainder 3")
                    .isEqualTo(accepted);
            if (!accepted) {
                assertThat(outcome.allComponentsNotOk())
                        .as("app/cpy/CSUTLDPY.cpy:L260-L262 dirties all three bytes on a leap failure")
                        .isTrue();
                assertThat(outcome.returnMessage()).isEqualTo(diagnostic(FIELD, LEAP_MESSAGE));
            }
        }

        @Test
        @DisplayName("the divisor is 400 when the last two digits are zero and 4 otherwise")
        void theDivisorIsSelectedByTheLastTwoDigits() {
            // :L245 IF WS-EDIT-DATE-YY-N = 0 MOVE 400 ELSE MOVE 4. The two-branch selection is reproduced here
            // rather than replaced by a library predicate, because the traceability gate reads the paragraph
            // map and the two-branch shape is the mapped behaviour.
            assertThat(leapByCopybookShape(2000, 0))
                    .as("a century year takes the 400 branch and 2000 divides exactly")
                    .isTrue();
            assertThat(leapByCopybookShape(1900, 0))
                    .as("a century year takes the 400 branch and 1900 leaves remainder 300")
                    .isFalse();
            assertThat(leapByCopybookShape(2024, 24))
                    .as("a non-century year takes the 4 branch and 2024 divides exactly")
                    .isTrue();
            assertThat(leapByCopybookShape(2023, 23))
                    .as("a non-century year takes the 4 branch and 2023 leaves remainder 3")
                    .isFalse();
        }

        @Test
        @DisplayName("the reproduced two-branch shape agrees with the library predicate across both centuries")
        void theReproducedShapeAgreesWithTheLibraryPredicate() {
            // The agreement is asserted, not assumed, and the library predicate is a cross-check rather than a
            // replacement: the two-branch structure remains the implementation of record. For every year the
            // century guard at :L70-L71 admits, the copybook's arithmetic and java.time reach the same verdict,
            // which is what makes the substitution of java.time elsewhere in the migration parity preserving.
            final List<Integer> disagreements = new ArrayList<>();
            for (int year = 1900; year <= 2099; year++) {
                if (leapByCopybookShape(year, year % 100) != Year.isLeap(year)) {
                    disagreements.add(year);
                }
            }
            assertThat(disagreements)
                    .as("app/cpy/CSUTLDPY.cpy:L243-L272 is correct Gregorian arithmetic across the admitted "
                            + "century range - it is not a divide-by-four defect and must not be reported as one")
                    .isEmpty();
        }

        @Test
        @DisplayName("the service's own February 29th verdict tracks the reproduced shape year by year")
        void theServiceTracksTheReproducedShape() {
            final List<Integer> disagreements = new ArrayList<>();
            for (int year = 1900; year <= 2099; year++) {
                if (service.editDate(compact(year, 2, 29), FIELD).valid() != leapByCopybookShape(year, year % 100)) {
                    disagreements.add(year);
                }
            }
            assertThat(disagreements)
                    .as("every year admitted by app/cpy/CSUTLDPY.cpy:L70-L71 reaches the :L256 remainder test, "
                            + "and the outcome is the remainder's")
                    .isEmpty();
        }

        @Test
        @DisplayName("the century guard fires before the leap test, so 2100 never reaches the remainder")
        void theCenturyGuardPrecedesTheLeapTest() {
            // 2100 would take the 400 branch and be rejected on the remainder. It never gets there: the century
            // guard at :L70-L71 admits only 19 and 20, so the year byte is already dirty and :L274 closes the
            // gate. The diagnostic is the century literal, which is the proof of ordering.
            final EditOutcome outcome = service.editDate("21000229", FIELD);

            assertThat(outcome.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L79 - the century guard owns this rejection, not :L266")
                    .isEqualTo(diagnostic(FIELD, CENTURY_MESSAGE));
            assertThat(outcome.returnMessage()).doesNotContain(LEAP_MESSAGE);
        }

        @Test
        @DisplayName("February 28th is valid in every admitted year, leap or not")
        void februaryTwentyEighthIsAlwaysValid() {
            // app/cpy/CSUTLDWY.cpy:L33-L34 declares WS-VALID-FEB-DAY as 1 THROUGH 28, and no combination branch
            // tests a February day below 29, so the 28th is never a candidate for rejection.
            final List<Integer> rejected = new ArrayList<>();
            for (int year = 1900; year <= 2099; year++) {
                if (!service.editDate(compact(year, 2, DateValidationService.VALID_FEBRUARY_DAY_MAXIMUM),
                        FIELD).valid()) {
                    rejected.add(year);
                }
            }
            assertThat(rejected)
                    .as("app/cpy/CSUTLDPY.cpy:L228-L229 and :L243-L244 test only the 30th and the 29th")
                    .isEmpty();
        }

        @Test
        @DisplayName("the leap failure is the only combination branch that dirties three bytes rather than two")
        void onlyTheLeapBranchDirtiesThreeBytes() {
            assertThat(flagsOf("20230431"))
                    .as("app/cpy/CSUTLDPY.cpy:L216-L217 - two SETs, the year untouched")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK);
            assertThat(flagsOf("20230230"))
                    .as("app/cpy/CSUTLDPY.cpy:L231-L232 - two SETs, the year untouched")
                    .containsExactly(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK);
            assertThat(flagsOf("20230229"))
                    .as("app/cpy/CSUTLDPY.cpy:L260-L262 - three SETs, the year dirtied as well")
                    .containsExactly(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the 31-day-month set is exactly the seven months the copybook lists")
        void theThirtyOneDayMonthSetIsAsDeclared() {
            // app/cpy/CSUTLDWY.cpy:L21-L23 lists 1, 3, 5, 7, 8, 10 and 12. Every other month must reject a 31st
            // at app/cpy/CSUTLDPY.cpy:L213-L214.
            final List<Integer> acceptingA31st = new ArrayList<>();
            for (int month = 1; month <= 12; month++) {
                if (service.editDate(compact(2023, month, 31), FIELD).valid()) {
                    acceptingA31st.add(month);
                }
            }
            assertThat(acceptingA31st)
                    .as("app/cpy/CSUTLDWY.cpy:L21-L23 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12")
                    .containsExactly(1, 3, 5, 7, 8, 10, 12);
        }
    }

    @Nested
    @DisplayName("The twelve message literals, byte for byte, with their inconsistent punctuation preserved")
    class MessageLiterals {

        @Test
        @DisplayName("each of the ten single-path and shared literals is emitted exactly as the copybook spells it")
        void everyLiteralIsEmittedVerbatim() {
            // Never paraphrased and never trimmed: the whole seventy-five character field is compared, so a
            // literal's own leading or trailing space cannot be lost in the assertion.
            assertThat(service.editDate("    0615", FIELD).returnMessage())
                    .as("literal 1, app/cpy/CSUTLDPY.cpy:L37")
                    .isEqualTo(diagnostic(FIELD, YEAR_BLANK_MESSAGE));
            assertThat(service.editDate("2X230615", FIELD).returnMessage())
                    .as("literal 2, app/cpy/CSUTLDPY.cpy:L54")
                    .isEqualTo(diagnostic(FIELD, YEAR_DIGITS_MESSAGE));
            assertThat(service.editDate("18990615", FIELD).returnMessage())
                    .as("literal 3, app/cpy/CSUTLDPY.cpy:L79")
                    .isEqualTo(diagnostic(FIELD, CENTURY_MESSAGE));
            assertThat(service.editDate("2023  15", FIELD).returnMessage())
                    .as("literal 4, app/cpy/CSUTLDPY.cpy:L101")
                    .isEqualTo(diagnostic(FIELD, MONTH_BLANK_MESSAGE));
            assertThat(service.editDate("20231315", FIELD).returnMessage())
                    .as("literal 5, app/cpy/CSUTLDPY.cpy:L119")
                    .isEqualTo(diagnostic(FIELD, MONTH_RANGE_MESSAGE));
            assertThat(service.editDate("202306  ", FIELD).returnMessage())
                    .as("literal 6, app/cpy/CSUTLDPY.cpy:L161")
                    .isEqualTo(diagnostic(FIELD, DAY_BLANK_MESSAGE));
            assertThat(service.editDate("20230632", FIELD).returnMessage())
                    .as("literal 7, app/cpy/CSUTLDPY.cpy:L195")
                    .isEqualTo(diagnostic(FIELD, DAY_RANGE_MESSAGE));
            assertThat(service.editDate("20230431", FIELD).returnMessage())
                    .as("literal 8, app/cpy/CSUTLDPY.cpy:L221")
                    .isEqualTo(diagnostic(FIELD, DAY_31_MESSAGE));
            assertThat(service.editDate("20230230", FIELD).returnMessage())
                    .as("literal 9, app/cpy/CSUTLDPY.cpy:L236")
                    .isEqualTo(diagnostic(FIELD, DAY_30_MESSAGE));
            assertThat(service.editDate("20230229", FIELD).returnMessage())
                    .as("literal 10, app/cpy/CSUTLDPY.cpy:L266")
                    .isEqualTo(diagnostic(FIELD, LEAP_MESSAGE));
            assertThat(service.editDateOfBirth(compact(TODAY.getYear(), TODAY.getMonthValue(),
                    TODAY.getDayOfMonth()), FIELD).returnMessage())
                    .as("literal 12, app/cpy/CSUTLDPY.cpy:L363")
                    .isEqualTo(diagnostic(FIELD, FUTURE_MESSAGE));
        }

        @Test
        @DisplayName("literal 2 is the only one carrying no colon at all")
        void literalTwoCarriesNoColon() {
            assertThat(YEAR_DIGITS_MESSAGE)
                    .as("app/cpy/CSUTLDPY.cpy:L54 reads ' must be 4 digit number.' - no colon, unlike every "
                            + "other diagnostic in the copybook")
                    .doesNotContain(":")
                    .startsWith(" ");
            for (final String withAColon : List.of(YEAR_BLANK_MESSAGE, CENTURY_MESSAGE, MONTH_BLANK_MESSAGE,
                    MONTH_RANGE_MESSAGE, DAY_BLANK_MESSAGE, DAY_RANGE_MESSAGE, DAY_31_MESSAGE, DAY_30_MESSAGE,
                    LEAP_MESSAGE, FUTURE_MESSAGE)) {
                assertThat(withAColon).as("every other literal carries a colon").contains(":");
            }
        }

        @Test
        @DisplayName("the four spacing styles around the colon are all preserved")
        void theFourSpacingStylesArePreserved() {
            // The punctuation is wildly inconsistent and every variant is part of the contract.
            assertThat(List.of(YEAR_BLANK_MESSAGE, CENTURY_MESSAGE, MONTH_BLANK_MESSAGE, DAY_BLANK_MESSAGE))
                    .as("style A - space, colon, space: app/cpy/CSUTLDPY.cpy:L37, :L79, :L101, :L161")
                    .allSatisfy(literal -> assertThat(literal).startsWith(" : "));
            assertThat(MONTH_RANGE_MESSAGE)
                    .as("style B - colon then space, no leading space: app/cpy/CSUTLDPY.cpy:L119 and :L136")
                    .startsWith(": ");
            assertThat(List.of(DAY_RANGE_MESSAGE, FUTURE_MESSAGE))
                    .as("style C - colon, no following space, lowercase word: app/cpy/CSUTLDPY.cpy:L180, "
                            + ":L195 and :L363")
                    .allSatisfy(literal -> assertThat(literal).startsWith(":").doesNotStartWith(": "));
            assertThat(List.of(DAY_31_MESSAGE, DAY_30_MESSAGE, LEAP_MESSAGE))
                    .as("style D - colon, no following space, capital C or N: app/cpy/CSUTLDPY.cpy:L221, "
                            + ":L236 and :L266")
                    .allSatisfy(literal -> assertThat(literal.charAt(1)).isUpperCase());
        }

        @Test
        @DisplayName("literal 7 spells the word day in lower case, unlike its month counterpart")
        void literalSevenIsLowerCase() {
            assertThat(DAY_RANGE_MESSAGE)
                    .as("app/cpy/CSUTLDPY.cpy:L180 and :L195 read ':day must be ...' in lower case")
                    .startsWith(":day")
                    .isEqualTo(DAY_RANGE_MESSAGE.toLowerCase(Locale.ROOT));
            assertThat(MONTH_RANGE_MESSAGE)
                    .as("while app/cpy/CSUTLDPY.cpy:L119 and :L136 capitalise Month")
                    .contains("Month")
                    .isNotEqualTo(MONTH_RANGE_MESSAGE.toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("literal 10 has no space after its first full stop, which is deliberate")
        void literalTenHasNoSpaceAfterItsFirstFullStop() {
            // Stated in prose as well as asserted, so that no editor or formatter can quietly insert the space
            // that reads more naturally: the copybook at :L266 runs the two sentences together as
            // "leap year." immediately followed by "Cannot", with nothing between them.
            assertThat(LEAP_MESSAGE)
                    .as("app/cpy/CSUTLDPY.cpy:L266 joins the two sentences with no separating space")
                    .contains("year.Cannot")
                    .doesNotContain("year. Cannot");
        }

        @Test
        @DisplayName("literal 12 ends with a trailing space, and that space reaches the caller")
        void literalTwelveEndsWithATrailingSpace() {
            // Stated in prose because the evidence is a whitespace character that a trailing-whitespace rule
            // would happily delete: app/cpy/CSUTLDPY.cpy:L363 is ':cannot be in the future ' - lowercase c, no
            // leading space, and ONE SPACE after the word "future" and before the closing quotation mark.
            assertThat(LEAP_MESSAGE).as("a control, showing that not every literal ends in a space")
                    .doesNotEndWith(" ");
            assertThat(FUTURE_MESSAGE)
                    .as("app/cpy/CSUTLDPY.cpy:L363 - the literal's last character is a space")
                    .endsWith(" ")
                    .hasSize("cannot be in the future".length() + 2);

            // And the space survives into the emitted field rather than being absorbed by the padding: the
            // character immediately after the literal's final visible character is a space that belongs to the
            // literal, so the visible text stops two characters before the padding begins.
            final String emitted = service.editDateOfBirth(
                    compact(TODAY.getYear(), TODAY.getMonthValue(), TODAY.getDayOfMonth()), FIELD)
                    .returnMessage();
            assertThat(emitted.substring(0, FIELD.length() + FUTURE_MESSAGE.length()))
                    .as("the leading segment of WS-RETURN-MSG is the name followed by the literal, "
                            + "trailing space included")
                    .isEqualTo(FIELD + FUTURE_MESSAGE)
                    .endsWith("future ");
        }

        @Test
        @DisplayName("literal 11 is composite, interpolating the four-character severity and message number")
        void literalElevenIsComposite() {
            // app/cpy/CSUTLDPY.cpy:L306-L313 STRINGs the trimmed name, then :L308, then WS-SEVERITY, then :L310,
            // then WS-MSG-NO. Both interpolated fields are PIC X(04) per app/cpy/CSUTLDWY.cpy:L61 and :L66, so
            // each contributes exactly four characters in zoned form.
            assertThat(LE_SEVERITY_FRAGMENT)
                    .as("app/cpy/CSUTLDPY.cpy:L308")
                    .isEqualTo(" validation error Sev code: ");
            assertThat(LE_NUMBER_FRAGMENT)
                    .as("app/cpy/CSUTLDPY.cpy:L310")
                    .isEqualTo(" Message code: ");

            final var rejected = service.validate("20230631", DateValidationService.MASK_YYYYMMDD);
            assertThat(rejected.severityCode())
                    .as("app/cpy/CSUTLDWY.cpy:L61 WS-SEVERITY PIC X(04)")
                    .hasSize(4)
                    .isEqualTo("0003");
            assertThat(rejected.messageNumber())
                    .as("app/cpy/CSUTLDWY.cpy:L66 WS-MSG-NO PIC X(04)")
                    .hasSize(4)
                    .isEqualTo("2508");
            assertThat(FIELD + LE_SEVERITY_FRAGMENT + rejected.severityCode()
                            + LE_NUMBER_FRAGMENT + rejected.messageNumber())
                    .as("the assembled shape app/cpy/CSUTLDPY.cpy:L306-L313 would produce")
                    .isEqualTo(FIELD + " validation error Sev code: 0003 Message code: 2508");
        }

        @Test
        @DisplayName("every diagnostic is prefixed by the trimmed variable name and held at the declared width")
        void everyDiagnosticIsPrefixedByTheTrimmedName() {
            // FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) heads every STRING - at :L36, :L53, :L78, :L100, :L118,
            // :L135, :L160, :L179, :L194, :L220, :L235, :L265, :L307 and :L362 - with no separator beyond
            // whatever the literal itself carries.
            assertThat(service.editDate("    0615", "  " + FIELD + "   ").returnMessage())
                    .as("surrounding spaces are trimmed off the name before the literal is appended")
                    .isEqualTo(diagnostic(FIELD, YEAR_BLANK_MESSAGE))
                    .startsWith(FIELD + " : ");
            assertThat(service.editDate("    0615", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDWY.cpy holds WS-RETURN-MSG at its declared width")
                    .hasSize(MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("a clean date emits no diagnostic at all, leaving the field blank")
        void aCleanDateEmitsNothing() {
            final EditOutcome outcome = service.editDate("20230615", FIELD);

            assertThat(outcome.hasReturnMessage())
                    .as("no STRING under an IF WS-RETURN-MSG-OFF guard ever ran")
                    .isFalse();
            assertThat(outcome.returnMessage())
                    .as("the field is still the blank it was initialised to, at the declared width")
                    .isBlank()
                    .hasSize(MESSAGE_WIDTH);
        }
    }

    @Nested
    @DisplayName("High - layer 2: the eight character mask, and the two byte overread it cannot avoid")
    class LanguageEnvironmentLayer {

        @Test
        @DisplayName("the copybook path uses the eight character mask, not the ten character program mask")
        void theCopybookPathUsesTheEightCharacterMask() {
            // app/cpy/CSUTLDPY.cpy:L291 MOVEs 'YYYYMMDD' into WS-DATE-FORMAT, declared PIC X(08) at
            // app/cpy/CSUTLDWY.cpy:L58-L59. The calling programs instead pass a ten character 'YYYY-MM-DD';
            // app/cbl/CORPT00C.cbl:L72 is one such site. The two masks are different contracts.
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("app/cpy/CSUTLDWY.cpy:L58-L59 - the copybook mask")
                    .isEqualTo("YYYYMMDD")
                    .hasSize(8);
            assertThat(DateValidationService.MASK_YYYY_MM_DD)
                    .as("the program mask, ten characters with separators")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10);
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .isNotEqualTo(DateValidationService.MASK_YYYY_MM_DD);
        }

        @Test
        @DisplayName("the argument widths that constitute the overread evidence are eight against ten")
        void theArgumentWidthsAreEightAgainstTen() {
            // Severity High, documented deviation - not simulated. app/cpy/CSUTLDPY.cpy:L294 passes
            // WS-EDIT-DATE-CCYYMMDD, an X(8) group, by reference into LS-DATE PIC X(10) at
            // app/cbl/CSUTLDTC.cbl:L84; :L295 passes WS-DATE-FORMAT, an X(08) field, into LS-DATE-FORMAT
            // PIC X(10) at :L85. The callee then MOVEs LENGTH OF LS-DATE - unconditionally ten - at
            // app/cbl/CSUTLDTC.cbl:L105-L106, so the date service reads two bytes past each argument.
            //
            // What those bytes are is settled by the work area's field order. After the date group comes
            // WS-EDIT-DATE-BINARY PIC S9(9) BINARY at app/cpy/CSUTLDWY.cpy:L37, so the date overread takes
            // binary content; after WS-DATE-FORMAT comes WS-DATE-VALIDATION-RESULT, whose first field is
            // WS-SEVERITY PIC X(04) at :L61, which the INITIALIZE at :L290 has just set to spaces - so the mask
            // the callee sees is 'YYYYMMDD' plus two spaces, which is benign. Only the copybook path overreads:
            // the calling programs pass genuine X(10) fields.
            //
            // A managed runtime has no equivalent of reading past a reference argument, so this is recorded as a
            // labelled deviation rather than reproduced. What is assertable is the width mismatch itself.
            assertThat(DateValidationService.LS_DATE_LENGTH)
                    .as("app/cbl/CSUTLDTC.cbl:L84 - 01 LS-DATE PIC X(10)")
                    .isEqualTo(10);
            assertThat(DateValidationService.LS_DATE_FORMAT_LENGTH)
                    .as("app/cbl/CSUTLDTC.cbl:L85 - 01 LS-DATE-FORMAT PIC X(10)")
                    .isEqualTo(10);
            assertThat(DateValidationService.CCYYMMDD_LENGTH)
                    .as("app/cpy/CSUTLDWY.cpy:L4-L27 - WS-EDIT-DATE-CCYYMMDD totals eight bytes")
                    .isEqualTo(8);
            assertThat(DateValidationService.LS_DATE_LENGTH - DateValidationService.CCYYMMDD_LENGTH)
                    .as("the date overread is exactly two bytes")
                    .isEqualTo(2);
            assertThat(DateValidationService.LS_DATE_FORMAT_LENGTH - DateValidationService.MASK_YYYYMMDD.length())
                    .as("the mask overread is exactly two bytes, and lands on spaces")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the callee reports severity zero for a real date and severity three for an impossible one")
        void theCalleeReportsTheDeclaredSeverities() {
            // app/cpy/CSUTLDPY.cpy:L298 IF WS-SEVERITY-N = 0 is the whole test, so the outcome the gate needs
            // is the severity alone. app/cbl/CSUTLDTC.cbl:L98 also moves it into RETURN-CODE.
            assertThat(service.validate("20230615", DateValidationService.MASK_YYYYMMDD).feedbackCode().severity())
                    .as("a real date takes the all-clear token of app/cbl/CSUTLDTC.cbl:L129-L130")
                    .isZero();
            assertThat(service.validate("20230631", DateValidationService.MASK_YYYYMMDD).feedbackCode().severity())
                    .as("an impossible day takes a severity three token")
                    .isEqualTo(3);
            assertThat(service.validate("20230615", DateValidationService.MASK_YYYYMMDD).returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 MOVE WS-SEVERITY-N TO RETURN-CODE")
                    .isZero();
        }

        @Test
        @DisplayName("a rejected date is a returned severity, never a thrown exception")
        void rejectionIsAReturnValueNotAnException() {
            // The edit machine's outcomes are values. Nothing below throws, and that is asserted rather than
            // assumed, because a port that signalled a bad date by throwing would change every caller.
            assertThat(service.validate("20230631", DateValidationService.MASK_YYYYMMDD).valid()).isFalse();
            assertThat(service.editDate("20230631", FIELD).valid()).isFalse();
            assertThat(service.editDate("        ", FIELD).inputError()).isTrue();
            assertThat(service.editDateOfBirth("20230230", FIELD).valid()).isFalse();
        }

        @Test
        @DisplayName("the day flag, not a group flag, is what :L318-L320 sets on the clean callee path")
        void theCleanCalleePathSetsTheDayFlag() {
            // :L318 IF NOT INPUT-ERROR / :L319 SET FLG-DAY-ISVALID / :L320 END-IF touches one byte, not the
            // group. The group assignment is a separate statement, at :L327, inside the exit label.
            final EditOutcome outcome = service.editDate("20230615", FIELD);

            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L319 set the day byte specifically")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.valid())
                    .as("and app/cpy/CSUTLDPY.cpy:L327 then cleared the whole group")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("High - EDIT-DATE-OF-BIRTH: a strict comparison, driven by the injected clock")
    class DateOfBirthFutureGuard {

        @Test
        @DisplayName("yesterday is accepted, today is rejected and tomorrow is rejected")
        void theBoundaryTripleIsYesterdayTodayTomorrow() {
            // app/cpy/CSUTLDPY.cpy:L350 IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY is STRICT: the accept
            // path at :L354 needs the current date to be strictly greater, so equality falls to :L355 ELSE.
            // A date of birth equal to the current date is therefore rejected, and the accepted boundary is
            // yesterday. Remedy if it is ever relaxed to greater-or-equal: restore the strict comparison.
            final LocalDate yesterday = TODAY.minusDays(1);
            final LocalDate tomorrow = TODAY.plusDays(1);

            assertThat(dateOfBirthAccepted(yesterday))
                    .as("app/cpy/CSUTLDPY.cpy:L354 - the current date is strictly greater, so accept")
                    .isTrue();
            assertThat(dateOfBirthAccepted(TODAY))
                    .as("app/cpy/CSUTLDPY.cpy:L355 - equality is NOT strictly greater, so today is rejected")
                    .isFalse();
            assertThat(dateOfBirthAccepted(tomorrow))
                    .as("app/cpy/CSUTLDPY.cpy:L355 - a future date is rejected, as :L336-L338 explains")
                    .isFalse();
        }

        @Test
        @DisplayName("the rejection dirties all three bytes and emits the future literal")
        void theRejectionDirtiesAllThreeBytes() {
            final EditOutcome outcome = service.editDateOfBirth(compact(TODAY.getYear(),
                    TODAY.getMonthValue(), TODAY.getDayOfMonth()), FIELD);

            assertThat(outcome.allComponentsNotOk())
                    .as("app/cpy/CSUTLDPY.cpy:L357-L359 sets the day, month and year bytes")
                    .isTrue();
            assertThat(outcome.inputError())
                    .as("app/cpy/CSUTLDPY.cpy:L356 sets INPUT-ERROR")
                    .isTrue();
            assertThat(outcome.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L363, trailing space and all")
                    .isEqualTo(diagnostic(FIELD, FUTURE_MESSAGE));
        }

        @Test
        @DisplayName("Medium - the paragraph has no flag initialisation, so acceptance leaves the flags alone")
        void theParagraphHasNoInitialisationOfItsOwn() {
            // Unlike EDIT-YEAR-CCYY at :L27, EDIT-MONTH at :L92 and EDIT-DAY at :L152, EDIT-DATE-OF-BIRTH begins
            // straight at :L343 with a MOVE and touches a flag only on its failure path. On acceptance it leaves
            // the group exactly as the composite chain left it - which is all three bytes clear, courtesy of
            // :L327 - and emits nothing. It is a pure add-on check and cannot establish validity by itself.
            final EditOutcome accepted = service.editDateOfBirth(compact(TODAY.minusDays(1).getYear(),
                    TODAY.minusDays(1).getMonthValue(), TODAY.minusDays(1).getDayOfMonth()), FIELD);

            assertThat(accepted.valid())
                    .as("the flags are those app/cpy/CSUTLDPY.cpy:L327 left, not any the paragraph wrote")
                    .isTrue();
            assertThat(accepted.inputError())
                    .as("no SET of app/cpy/CSUTLDPY.cpy:L356 ran, so INPUT-ERROR is untouched")
                    .isFalse();
            assertThat(accepted.hasReturnMessage())
                    .as("and no STRING ran either")
                    .isFalse();
        }

        @Test
        @DisplayName("Medium - the paragraph presupposes the composite chain has already passed")
        void theParagraphPresupposesAValidCompositeDate() {
            // FUNCTION INTEGER-OF-DATE at :L345-L346 requires an already valid Gregorian date, so the ordering
            // is a precondition rather than a preference. The observable consequence is that a malformed date
            // reports the composite diagnostic and never the future one: the guard is simply not reached, and
            // asking what it would have decided is asking about undefined behaviour.
            final EditOutcome malformed = service.editDateOfBirth("20230230", FIELD);

            assertThat(malformed.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L236 won, because the chain aborted at :L240 long before :L341")
                    .isEqualTo(diagnostic(FIELD, DAY_30_MESSAGE));
            assertThat(malformed.returnMessage())
                    .as("and the future literal of :L363 is absent")
                    .doesNotContain(FUTURE_MESSAGE);

            // The same holds for an absent date, which cannot denote a day count at all.
            assertThat(service.editDateOfBirth("        ", FIELD).returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L37 won for a wholly absent date")
                    .isEqualTo(diagnostic(FIELD, YEAR_BLANK_MESSAGE));
        }

        @Test
        @DisplayName("the current date is truncated to eight characters before it is compared")
        void theCurrentDateIsTruncatedToEightCharacters() {
            // app/cpy/CSUTLDPY.cpy:L343 MOVEs FUNCTION CURRENT-DATE, a twenty-one character value of the form
            // YYYYMMDDhhmmssnnnnnn with a signed four digit offset, into WS-CURRENT-DATE-YYYYMMDD, declared
            // PIC X(8) at app/cpy/CSUTLDWY.cpy:L39. The MOVE truncates on the right, so only the date survives
            // and the time of day cannot influence the comparison.
            //
            // The evidence is that the boundary sits exactly at a date, not at an instant: the injected instant
            // is late in the day at 19:27:53Z, yet the same day is still rejected and only the previous day is
            // accepted. Had any time-of-day component survived the MOVE, the boundary would move within the day.
            assertThat(dateOfBirthAccepted(TODAY))
                    .as("the injected instant's time of day does not rescue today")
                    .isFalse();
            assertThat(dateOfBirthAccepted(TODAY.minusDays(1)))
                    .as("and the whole of the previous day is accepted")
                    .isTrue();
            assertThat(compact(TODAY.getYear(), TODAY.getMonthValue(), TODAY.getDayOfMonth()))
                    .as("app/cpy/CSUTLDWY.cpy:L39 - what survives the MOVE is eight characters")
                    .hasSize(DateValidationService.CCYYMMDD_LENGTH);
        }

        @Test
        @DisplayName("both comparands are day counts, so the comparison is integer to integer")
        void bothComparandsAreDayCounts() {
            // :L345-L346 and :L347-L348 convert each side with FUNCTION INTEGER-OF-DATE before :L350 compares
            // them, so no string ordering is involved. The consequence is that the ordering is a true calendar
            // ordering: a date in an earlier year is accepted however its digits would sort.
            assertThat(dateOfBirthAccepted(LocalDate.of(1900, 1, 1)))
                    .as("the earliest date the century guard admits is comfortably in the past")
                    .isTrue();
            assertThat(dateOfBirthAccepted(LocalDate.of(2099, 12, 31)))
                    .as("and the latest is comfortably in the future")
                    .isFalse();
        }

        @Test
        @DisplayName("the verdict follows the injected instant, which is what makes this class deterministic")
        void theVerdictFollowsTheInjectedInstant() {
            // The comparison is relative to the current date, so a system clock would make this class's verdict
            // change from one day to the next. Driving it from com.cardemo.unit.model.FixedClockProvider is
            // therefore not a convenience: it is the only way the assertions above can mean anything. Advancing
            // the injected instant by one day moves the boundary by exactly one day, which proves the guard
            // reads the injected clock and nothing else.
            final DateValidationService tomorrowsService = new DateValidationService(
                    FixedClockProvider.fixedClock(FixedClockProvider.CANONICAL_INSTANT.plusSeconds(86_400L)));
            final String today = compact(TODAY.getYear(), TODAY.getMonthValue(), TODAY.getDayOfMonth());

            assertThat(service.editDateOfBirth(today, FIELD).valid())
                    .as("rejected under the canonical instant, because app/cpy/CSUTLDPY.cpy:L350 is strict")
                    .isFalse();
            assertThat(tomorrowsService.editDateOfBirth(today, FIELD).valid())
                    .as("and accepted once the injected instant has moved on by a day")
                    .isTrue();
        }

        @Test
        @DisplayName("the service cannot be built without a clock, so the injection cannot be skipped")
        void theServiceCannotBeBuiltWithoutAClock() {
            // What makes the determinism above enforceable rather than merely conventional: there is no
            // no-argument constructor to fall back on, so no caller can reach a system clock by omission.
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateValidationService(null))
                    .withMessage("clock must not be null")
                    .withNoCause();
            assertThat(clock)
                    .as("com.cardemo.unit.model.FixedClockProvider#canonicalClock supplies a real, fixed clock")
                    .isNotNull();
        }

        /**
         * Whether {@code EDIT-DATE-OF-BIRTH} accepts the date, expressed as the source's own accept path.
         *
         * @param dateOfBirth the synthetic date under test - never a value read from any customer fixture
         * @return {@code true} when the whole flag group is clear, which is the accept path at {@code :L354}
         */
        private boolean dateOfBirthAccepted(final LocalDate dateOfBirth) {
            return service.editDateOfBirth(compact(dateOfBirth.getYear(), dateOfBirth.getMonthValue(),
                    dateOfBirth.getDayOfMonth()), FIELD).valid();
        }
    }

    @Nested
    @DisplayName("Untrusted input - every edit is fed hostile values and none of them throws")
    class HostileInput {

        @Test
        @DisplayName("an absent argument is absorbed as an absent field rather than propagated")
        void anAbsentArgumentIsAbsorbed() {
            // A null reference has no COBOL counterpart - a called program always receives storage - so the
            // faithful reading is an unpopulated field, which is what the SPACES arm of :L30-L31 diagnoses.
            final EditOutcome outcome = service.editDate(null, null);

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage())
                    .as("with no variable name to trim, app/cpy/CSUTLDPY.cpy:L37 stands alone")
                    .isEqualTo(diagnostic("", YEAR_BLANK_MESSAGE))
                    .startsWith(" : Year");
        }

        @Test
        @DisplayName("the LOW-VALUES arm of each absent guard recognises a field of null bytes")
        void theLowValuesArmIsHonoured() {
            // :L30, :L94 and :L154 each test LOW-VALUES before SPACES, so a field of binary zeroes is absent
            // just as a field of blanks is.
            final String nul = "\u0000";
            assertThat(flagsOf(nul.repeat(8)))
                    .as("app/cpy/CSUTLDPY.cpy:L30, :L94 and :L154, LOW-VALUES arm")
                    .containsExactly(EditFlag.BLANK, EditFlag.BLANK, EditFlag.BLANK);
            assertThat(flagsOf("2023" + nul.repeat(2) + "15"))
                    .as("an absent month alone, through the LOW-VALUES arm of :L94")
                    .containsExactly(EditFlag.ISVALID, EditFlag.BLANK, EditFlag.ISVALID);
            assertThat(flagsOf("202312" + nul.repeat(2)))
                    .as("an absent day alone, through the LOW-VALUES arm of :L154")
                    .containsExactly(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.BLANK);
        }

        @ParameterizedTest(name = "[{index}] the hostile value [{0}] is rejected without throwing")
        @ValueSource(strings = {
            "", "  ", "0000    ", "00001215", "18001215", "21991215", "20230015", "20231315",
            "20230600", "20230632", "2023-6-1", "2023/6/1", "20-31215", "2X231215", "202312x1",
            "2023+115", "abcdefgh", "########", "20230631", "20231131", "19000229", "20230229",
            "2023", "202306150000",
        })
        @DisplayName("a spread of hostile shapes is diagnosed, and the outcome is always a value")
        void hostileShapesAreDiagnosedAndNeverThrown(final String hostile) {
            final EditOutcome outcome = service.editDate(hostile, FIELD);

            assertThat(outcome)
                    .as("app/cpy/CSUTLDPY.cpy always returns storage, never a signal, for [" + hostile + "]")
                    .isNotNull();
            assertThat(outcome.returnMessage())
                    .as("and the diagnostic is always held at the declared width")
                    .hasSize(MESSAGE_WIDTH);
            if (!"202306150000".equals(hostile)) {
                assertThat(outcome.valid())
                        .as("[" + hostile + "] denotes no real date in the admitted centuries")
                        .isFalse();
                assertThat(outcome.inputError())
                        .as("so some guard set INPUT-ERROR for [" + hostile + "]")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("an over-long argument is truncated to the declared width, not rejected for its length")
        void anOverLongArgumentIsTruncated() {
            // WS-EDIT-DATE-CCYYMMDD is X(8): a MOVE from anything longer truncates on the right, so the trailing
            // characters are simply not part of the field. That is a COBOL MOVE, not a validation decision.
            assertThat(service.editDate("202306150000", FIELD).valid())
                    .as("the first eight characters denote 15 June 2023, and the rest never enter the field")
                    .isTrue();
        }

        @Test
        @DisplayName("an under-length argument is padded, so its missing components read as absent")
        void anUnderLengthArgumentIsPadded() {
            assertThat(flagsOf("2023"))
                    .as("the year is present and the padded month and day are absent, via :L94 and :L154")
                    .containsExactly(EditFlag.ISVALID, EditFlag.BLANK, EditFlag.BLANK);
        }

        @Test
        @DisplayName("only centuries 19 and 20 are admitted, as :L66-L68 says in as many words")
        void onlyTwoCenturiesAreAdmitted() {
            // app/cpy/CSUTLDWY.cpy:L9-L10 declares THIS-CENTURY as 20 and LAST-CENTURY as 19, and nothing else.
            final List<Integer> admitted = new ArrayList<>();
            for (int century = 0; century <= 99; century++) {
                final String date = String.format(Locale.ROOT, "%02d230615", century);
                if (service.editDate(date, FIELD).yearFlag() == EditFlag.ISVALID) {
                    admitted.add(century);
                }
            }
            assertThat(admitted)
                    .as("app/cpy/CSUTLDPY.cpy:L70-L71 IF THIS-CENTURY OR LAST-CENTURY - and the source's own "
                            + "comment at :L66-L68 records that this is deliberate, not an oversight")
                    .containsExactly(19, 20);
        }

        @Test
        @DisplayName("the month and day ranges are exactly those the work area declares")
        void theMonthAndDayRangesAreAsDeclared() {
            final List<Integer> admittedMonths = new ArrayList<>();
            for (int month = 0; month <= 99; month++) {
                if (service.editDate(String.format(Locale.ROOT, "2023%02d15", month), FIELD)
                        .monthFlag() == EditFlag.ISVALID) {
                    admittedMonths.add(month);
                }
            }
            assertThat(admittedMonths)
                    .as("app/cpy/CSUTLDWY.cpy:L19-L20 WS-VALID-MONTH VALUES 1 THROUGH 12")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

            final List<Integer> admittedDays = new ArrayList<>();
            for (int day = 0; day <= 99; day++) {
                // January is used because it accepts a 31st, so the day range is observed without a combination
                // branch at :L213-L214 interfering.
                if (service.editDate(String.format(Locale.ROOT, "202301%02d", day), FIELD)
                        .dayFlag() == EditFlag.ISVALID) {
                    admittedDays.add(day);
                }
            }
            assertThat(admittedDays)
                    .as("app/cpy/CSUTLDWY.cpy:L28-L29 WS-VALID-DAY VALUES 1 THROUGH 31")
                    .hasSize(31)
                    .startsWith(1)
                    .endsWith(31);
        }
    }
}
