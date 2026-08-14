/*
 * ******************************************************************
 * Program     : DateValidationServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the behaviour of the injected date validation
 *               bean that collapses the statically called date utility
 *               and its two work area copybooks into a single Spring
 *               bean: the sixteen paragraph labels it maps, its
 *               constructor injected clock, the sequential fall
 *               through of the component edits, the first error wins
 *               message latch, the derived tri state flag conjunction
 *               and outcomes returned as values rather than thrown.
 * Source      : app/cbl/CSUTLDTC.cbl  (157 lines, 2 paragraph labels)
 *               app/cpy/CSUTLDPY.cpy  (375 lines, 14 labels)
 *               app/cpy/CSUTLDWY.cpy  (89 lines, pure work area)
 *               all frozen at commit 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.EditOutcome;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;
import com.cardemo.unit.model.FixedClockProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Behavioural specification for {@link DateValidationService}, the first collapse rule of the
 * migration.
 *
 * <h2>What this class does</h2>
 *
 * <p>One injected bean subsumes three frozen legacy artefacts: the statically called utility
 * {@code app/cbl/CSUTLDTC.cbl} (157 lines), the procedure division copybook
 * {@code app/cpy/CSUTLDPY.cpy} (375 lines) and the working storage copybook
 * {@code app/cpy/CSUTLDWY.cpy} (89 lines). This class asserts the <strong>bean's behaviour</strong>
 * - its wiring, its call ordering and the values it returns - across eight concerns, one per nested
 * section:
 *
 * <ul>
 *   <li><strong>The sixteen label collapse.</strong> Two labels come from the utility,
 *       {@code A000-MAIN} at {@code app/cbl/CSUTLDTC.cbl:L103} and {@code A000-MAIN-EXIT} at
 *       {@code :L152}; fourteen come from the copybook at {@code app/cpy/CSUTLDPY.cpy:L18},
 *       {@code :L25}, {@code :L88}, {@code :L91}, {@code :L145}, {@code :L150}, {@code :L205},
 *       {@code :L209}, {@code :L280}, {@code :L284}, {@code :L323}, {@code :L329}, {@code :L341}
 *       and {@code :L370}; the work area contributes none. Two plus fourteen plus zero is sixteen,
 *       and each maps to its own private method carrying a Javadoc citation.</li>
 *   <li><strong>Constructor injection and statelessness.</strong> The date of birth check consumes
 *       {@code FUNCTION CURRENT-DATE} at {@code app/cpy/CSUTLDPY.cpy:L343}, so the clock arrives
 *       through the sole constructor and nothing reads ambient time. Every identifier the copybook
 *       leaves to its including program - {@code WS-EDIT-VARIABLE-NAME}, {@code WS-RETURN-MSG},
 *       {@code WS-RETURN-MSG-OFF}, {@code INPUT-ERROR}, {@code WS-DIV-BY}, {@code WS-DIVIDEND} and
 *       {@code WS-REMAINDER} - is therefore a parameter or a method local, never a bean field, and
 *       the type carries no mutable static state at all.</li>
 *   <li><strong>The misnamed success token.</strong>
 *       {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'} at {@code app/cbl/CSUTLDTC.cbl:L62} is
 *       all zeros, which is severity zero and message number zero, and it selects the result string
 *       {@code Date is valid} at {@code :L130}. The severity is also the process return code, moved
 *       by {@code MOVE WS-SEVERITY-N TO RETURN-CODE} at {@code :L98}.</li>
 *   <li><strong>Sequential fall through.</strong> Every {@code -EXIT} paragraph body is a bare
 *       {@code EXIT}, which is a documentary no-op, so a component {@code GO TO} does not abandon
 *       validation. All three component edits always run and only the first failure emits a message.
 *       Only {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} at {@code :L225}, {@code :L240}, {@code :L270}
 *       and {@code :L277}, and {@code GO TO EDIT-DATE-LE-EXIT} at {@code :L315}, abort the chain.
 *       {@code EDIT-DATE-LE-EXIT} spans {@code :L323-L328} and its {@code :L327} set of the group
 *       level valid condition therefore executes on both of its entry paths.</li>
 *   <li><strong>The derived tri state flag conjunction.</strong>
 *       {@code app/cpy/CSUTLDWY.cpy:L43-L57} declares a three byte group whose two group level
 *       conditions test all three bytes at once, so composite validity is a conjunction of three
 *       independent tri state flags and the two group conditions are not complementary.</li>
 *   <li><strong>Boundary rules as behaviour.</strong> Only centuries 19 and 20 are admissible
 *       ({@code app/cpy/CSUTLDWY.cpy:L9-L10}, gated at {@code app/cpy/CSUTLDPY.cpy:L70-L72}), the
 *       month range is one through twelve ({@code :L19-L20}), the day range one through thirty one
 *       ({@code :L28-L29}), thirty one days in a thirty day month is refused at
 *       {@code app/cpy/CSUTLDPY.cpy:L213-L226}, thirty February at {@code :L228-L241}, and the leap
 *       year test at {@code :L243-L272} is a two branch integer remainder, never a library
 *       predicate. The gate at {@code :L274-L278} is what stops the language environment call when a
 *       component failed.</li>
 *   <li><strong>The date of birth check on the injected clock.</strong> {@code EDIT-DATE-OF-BIRTH} at
 *       {@code app/cpy/CSUTLDPY.cpy:L341-L369} truncates {@code FUNCTION CURRENT-DATE} to eight
 *       characters at {@code :L343} and compares with a <strong>strict</strong> {@code >} at
 *       {@code :L350}, so a date of birth equal to today is refused. It initialises no flag of its
 *       own and emits the lowercase literal {@code ':cannot be in the future '} at {@code :L363}.</li>
 *   <li><strong>Outcomes as return values.</strong> A rejected date is a value, never a throw. The
 *       callers read only the four byte severity of the eighty byte area, declared as
 *       {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)} at {@code app/cbl/CORPT00C.cbl:L133}, and compare
 *       it against {@code '0000'}.</li>
 *   </ul>
 *
 * <h2>Complementary sibling suites - what this class deliberately does NOT assert</h2>
 *
 * <p>The sibling package {@code com.cardemo.unit.validation} owns the <strong>data contracts</strong>
 * and this class must not restate them, because Rule 1 Clause C forbids duplication. Both tiers are
 * required and neither substitutes for the other:
 *
 * <ul>
 *   <li>{@code com.cardemo.unit.validation.DateValidationServiceTest} owns the eighty byte result
 *       area field by field, the nine feedback tokens decoded from their hexadecimal literals, the
 *       twelve message literals character for character and the declared field widths.</li>
 *   <li>{@code com.cardemo.unit.validation.DateValidationServiceGuardPathTest} owns the eighty byte
 *       width guard, fixed width truncation and padding, low values detection and variable name
 *       trimming.</li>
 *   <li>{@code com.cardemo.unit.validation.DateValidationServiceSweepTest} owns the documented
 *       language environment input domain, its four parsing rules and the per input condition
 *       census.</li>
 *   <li>{@code com.cardemo.unit.validation.ValidationLookupServiceTest} owns the externalised lookup
 *       table resources, which are a different collapse rule entirely.</li>
 *   </ul>
 *
 * <p>Where an assertion here necessarily touches an outcome the sibling also observes - the all
 * zeros token, for instance - it is asserted through the bean's behaviour rather than through the
 * token's byte pattern, and only one exemplar of each is used.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is bound to Surefire 3.5.4 by path: the plugin includes {@code **}{@code /*Test.java}
 * and excludes only the integration and end to end trees, so a class under
 * {@code src/test/java/com/cardemo/unit/} is collected by Surefire and never by Failsafe. Run it
 * with {@code ./mvnw -B -ntp test}, the whole tier with {@code ./mvnw -B -ntp clean test}, or this class alone with
 * {@code ./mvnw -B -ntp test -Dtest=DateValidationServiceTest}. Coverage is measured by JaCoCo 0.8.12 at
 * {@code verify} with no exclusions. Compilation is Java 25 with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so a single raw type, unchecked cast or dangling documentation comment fails the
 * build; an unused import does not, because {@code javac} 25 publishes no {@code unused} lint key.
 *
 * <p>The toolchain is OpenJDK 25.0.3 with Apache Maven 3.9.11, and {@code ./mvnw -B -ntp clean test} exits
 * zero with no warning of any kind. No count of module-wide tests or coverage is stated here, because a
 * figure of that kind goes stale the moment another class is added; this class asserts nothing about the
 * total. The one goal that cannot run without network access is the OWASP dependency check, which
 * requires network access to refresh its advisory database; its state is a property of
 * {@code pom.xml} rather than of this class, and no assertion here depends on it.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>The injected fixed clock.</strong> Every date of birth assertion runs on
 *       {@link FixedClockProvider#canonicalClock()}, whose instant is
 *       {@link FixedClockProvider#CANONICAL_INSTANT} - the tenth of June 2022. No method here calls
 *       any ambient time overload of {@code Instant}, {@code LocalDate} or {@code System}, nor
 *       {@code Math.random}, nor an unseeded {@code Random}, because the strict inequality at
 *       {@code app/cpy/CSUTLDPY.cpy:L350} makes today's verdict differ from tomorrow's and an
 *       ambient clock would make the boundary untestable.</li>
 *   <li><strong>Mockito strict stubs</strong> are the standing policy for any stubbed collaborator.
 *       None is stubbed here: the bean's only collaborator is a {@link Clock} and
 *       {@code FixedClockProvider} supplies a real deterministic one, so a mock would add a
 *       self attaching agent and no information. The interaction that a mock would have verified -
 *       that only the date of birth path consults the clock - is proved instead by running the same
 *       input under two different clocks and observing which outcomes move.</li>
 *   <li><strong>Two masks, never conflated.</strong> The copybook path moves the eight character
 *       {@code 'YYYYMMDD'} at {@code app/cpy/CSUTLDPY.cpy:L291}; the four program call sites at
 *       {@code app/cbl/COTRN02C.cbl:L393}, {@code :L413}, {@code app/cbl/CORPT00C.cbl:L392} and
 *       {@code :L412} move the ten character {@code 'YYYY-MM-DD'} declared at
 *       {@code app/cbl/COTRN02C.cbl:L60} and {@code app/cbl/CORPT00C.cbl:L72}. Conflating them
 *       changes what the language environment service parses.</li>
 *   <li><strong>Structural assertions read the production source.</strong> Two parity properties -
 *       the Javadoc citation on each of the sixteen label methods, and the two initialisation and
 *       ordering asymmetries - are not observable through the public surface, so they are asserted
 *       by reading {@code src/main/java/com/cardemo/service/shared/DateValidationService.java} from
 *       the module base directory. That is the established convention of this test tree, which
 *       already reads {@code app/} and {@code src/main/resources/} from disk in the same way.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on a dangling documentation comment.</strong> {@code -Xlint:all -Werror}
 *       plus {@code failOnWarning} reaches test compilation, and a documentation comment in a position
 *       where it would be ignored fails it, because {@code dangling-doc-comments} is part of
 *       {@code -Xlint:all} on this compiler. An unused import does <em>not</em> fail it: {@code javac} 25
 *       publishes no {@code unused} lint key, so remove such an import because Rule 1 Clause B requires
 *       it, not because the build demands it. Never relax the flag.</li>
 *   <li><strong>The inverted success token.</strong> A port that trusts the identifier
 *       {@code FC-INVALID-DATE} maps the all zeros token to an invalid outcome and produces exactly
 *       inverted behaviour that still compiles and still passes a naively written test.
 *       {@link TheMisnamedSuccessToken} is named to make that impossible to miss.</li>
 *   <li><strong>A bare {@code EXIT} read as an early return.</strong> Modelling each component exit
 *       as a Java {@code return} sets exactly one flag and never runs the later components, and it
 *       also skips the {@code :L327} set. {@link SequentialFallThrough} pins both effects.</li>
 *   <li><strong>The tri state flags collapsed into a boolean.</strong> A single boolean cannot
 *       represent the group of {@code '0'}, {@code 'B'}, {@code '0'} that satisfies neither group
 *       level condition. {@link TriStateFlagConjunction} pins it.</li>
 *   <li><strong>The strict inequality relaxed.</strong> Changing {@code >} to {@code >=} at
 *       {@code app/cpy/CSUTLDPY.cpy:L350} silently accepts a date of birth of today.
 *       {@link DateOfBirthOnTheInjectedClock} pins it from both sides of the boundary.</li>
 *   <li><strong>A library leap year predicate substituted.</strong> The source's two branch
 *       remainder at {@code app/cpy/CSUTLDPY.cpy:L243-L272} is the traceable artefact, and a
 *       February bound of one through twenty nine would bypass it altogether.
 *       {@link BoundaryRulesAsBehaviour} pins the four way leap outcome and the unreferenced
 *       twenty eight day condition name.</li>
 *   <li><strong>A structural assertion fails after a rename.</strong> The four tests that read the
 *       production source name the identifiers they look for in their failure descriptions. They are
 *       parity gates, not style checks: if the citation or the predicate order genuinely changed, the
 *       citation in this class has to be corrected in the same commit.</li>
 *   </ul>
 */
@DisplayName("DateValidationService: sixteen labels, one injected clock, and outcomes returned as values")
class DateValidationServiceTest {

    /**
     * The production source of the bean under test, read once from the module base directory.
     *
     * <p>Reading {@code src/main/} and {@code app/} from a unit test is the established convention of
     * this tree, and Maven guarantees the working directory is the module base directory.
     */
    private static final String SERVICE_SOURCE = readServiceSource();

    /** The production source split into lines, so a declaration can be located by its own line. */
    private static final List<String> SERVICE_LINES = SERVICE_SOURCE.lines().toList();

    /** The path of the frozen procedure division copybook, cited by fourteen of the sixteen labels. */
    private static final String COPYBOOK_PATH = "app/cpy/CSUTLDPY.cpy";

    /** The path of the frozen utility program, cited by the remaining two labels. */
    private static final String UTILITY_PATH = "app/cbl/CSUTLDTC.cbl";

    /**
     * The sixteen paragraph labels the bean maps, in source order: the two utility labels first,
     * then the fourteen copybook labels in the order the copybook declares them.
     *
     * <p>Every row was established by
     * {@code grep -nE '^ {6,7}[A-Z][A-Z0-9-]*\.[[:space:]]*$'} over the two frozen sources, which
     * yields fourteen matches in the copybook and two in the program.
     */
    private static final List<ParagraphMapping> PARAGRAPHS = List.of(
            new ParagraphMapping("a000Main", "A000-MAIN", UTILITY_PATH, 103),
            new ParagraphMapping("a000MainExit", "A000-MAIN-EXIT", UTILITY_PATH, 152),
            new ParagraphMapping("editDateCcyymmdd", "EDIT-DATE-CCYYMMDD", COPYBOOK_PATH, 18),
            new ParagraphMapping("editYearCcyy", "EDIT-YEAR-CCYY", COPYBOOK_PATH, 25),
            new ParagraphMapping("editYearCcyyExit", "EDIT-YEAR-CCYY-EXIT", COPYBOOK_PATH, 88),
            new ParagraphMapping("editMonth", "EDIT-MONTH", COPYBOOK_PATH, 91),
            new ParagraphMapping("editMonthExit", "EDIT-MONTH-EXIT", COPYBOOK_PATH, 145),
            new ParagraphMapping("editDay", "EDIT-DAY", COPYBOOK_PATH, 150),
            new ParagraphMapping("editDayExit", "EDIT-DAY-EXIT", COPYBOOK_PATH, 205),
            new ParagraphMapping("editDayMonthYear", "EDIT-DAY-MONTH-YEAR", COPYBOOK_PATH, 209),
            new ParagraphMapping("editDayMonthYearExit", "EDIT-DAY-MONTH-YEAR-EXIT", COPYBOOK_PATH, 280),
            new ParagraphMapping("editDateLe", "EDIT-DATE-LE", COPYBOOK_PATH, 284),
            new ParagraphMapping("editDateLeExit", "EDIT-DATE-LE-EXIT", COPYBOOK_PATH, 323),
            new ParagraphMapping("editDateCcyymmddExit", "EDIT-DATE-CCYYMMDD-EXIT", COPYBOOK_PATH, 329),
            new ParagraphMapping("editDateOfBirthParagraph", "EDIT-DATE-OF-BIRTH", COPYBOOK_PATH, 341),
            new ParagraphMapping("editDateOfBirthExit", "EDIT-DATE-OF-BIRTH-EXIT", COPYBOOK_PATH, 370));

    /** The bean under test, rebuilt on the canonical fixed clock before every test. */
    private DateValidationService service;

    @BeforeEach
    void buildTheBeanOnAFixedClock() {
        service = new DateValidationService(FixedClockProvider.canonicalClock());
    }

    /**
     * One row of the paragraph map: a Java method, the COBOL label it reproduces, and where that
     * label is declared in the frozen corpus.
     *
     * @param javaMethod the name of the private method on the bean
     * @param cobolLabel the Area A paragraph label the method reproduces
     * @param sourcePath the repository relative path of the frozen source declaring the label
     * @param line the line at which that source declares the label
     */
    private record ParagraphMapping(String javaMethod, String cobolLabel, String sourcePath, int line) {
    }

    /**
     * Reads the production source of the bean under test.
     *
     * @return the whole file as text
     * @throws UncheckedIOException if the file cannot be read, which means the module layout changed
     */
    private static String readServiceSource() {
        final Path path = Path.of("src", "main", "java", "com", "cardemo", "service", "shared",
                "DateValidationService.java");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("cannot read " + path
                    + "; the structural parity assertions require the production source", cause);
        }
    }

    /**
     * Finds the line index at which the bean declares a private method.
     *
     * <p>The search is anchored on the declaration indent and on the trailing parenthesis, so a call
     * site cannot be mistaken for a declaration and {@code editDateCcyymmdd} cannot match
     * {@code editDateCcyymmddExit}.
     *
     * @param methodName the method to locate
     * @return the zero based line index of the declaration
     */
    private static int declarationLineIndexOf(final String methodName) {
        for (int index = 0; index < SERVICE_LINES.size(); index++) {
            final String line = SERVICE_LINES.get(index);
            if (line.startsWith("    private ") && line.contains(" " + methodName + "(")) {
                return index;
            }
        }
        throw new AssertionError("no private declaration of " + methodName
                + " on the bean, so the paragraph it maps has been consolidated away");
    }

    /**
     * Returns the documentation comment immediately preceding a private method declaration.
     *
     * @param methodName the method whose documentation is wanted
     * @return the text of the comment, or the empty string when the declaration carries none
     */
    private static String javadocPreceding(final String methodName) {
        final int declaration = declarationLineIndexOf(methodName);
        final List<String> collected = new ArrayList<>();
        for (int index = declaration - 1; index >= 0; index--) {
            final String line = SERVICE_LINES.get(index);
            collected.add(line);
            if (line.strip().startsWith("/**")) {
                return String.join(" ", collected);
            }
            if (!line.strip().startsWith("*") && !line.strip().equals("*/")) {
                return "";
            }
        }
        return "";
    }

    /**
     * Returns the body of a private method on the bean, from its declaration to its closing brace.
     *
     * @param methodName the method whose body is wanted
     * @return the body as text, including the declaration line
     */
    private static String bodyOf(final String methodName) {
        final int declaration = declarationLineIndexOf(methodName);
        final List<String> collected = new ArrayList<>();
        for (int index = declaration; index < SERVICE_LINES.size(); index++) {
            final String line = SERVICE_LINES.get(index);
            collected.add(line);
            if (line.equals("    }")) {
                break;
            }
        }
        return String.join("\n", collected);
    }

    /**
     * Enumerates every eight character date whose century the year edit admits and whose month and
     * day are inside their declared ranges.
     *
     * <p>Centuries 19 and 20 are the only two the year edit accepts, the month range is one through
     * twelve and the day range one through thirty one, so this is exactly the set of inputs that
     * reaches the combination checks: two centuries times one hundred years times twelve months
     * times thirty one days.
     *
     * @return the candidate dates, in ascending order
     */
    private static List<String> admissibleCandidates() {
        final List<String> candidates = new ArrayList<>();
        for (final int century : new int[] {19, 20}) {
            for (int yearInCentury = 0; yearInCentury <= 99; yearInCentury++) {
                for (int month = 1; month <= 12; month++) {
                    for (int day = 1; day <= 31; day++) {
                        candidates.add(String.format(Locale.ROOT, "%02d%02d%02d%02d",
                                century, yearInCentury, month, day));
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Reports whether an eight digit date denotes a real Gregorian day, using {@code java.time} as an
     * independent oracle.
     *
     * <p>The bean must never use a library predicate for the leap year test, because the source's two
     * branch remainder is the traceable artefact. Using one <em>here</em> is the opposite situation:
     * an oracle written a different way is what makes the comparison meaningful.
     *
     * <p>The {@link DateTimeException} below is not swallowed: it <em>is</em> the negative answer this
     * predicate exists to return, which is the only way {@code java.time} expresses "no such day".
     * The argument is always an eight digit value produced by {@link #admissibleCandidates()}, so no
     * other failure can reach the handler and none is therefore discarded.
     *
     * @param ccyymmdd the eight digit date
     * @return {@code true} when the three components denote a real day
     */
    private static boolean isRealGregorianDay(final String ccyymmdd) {
        try {
            LocalDate.of(Integer.parseInt(ccyymmdd.substring(0, 4)),
                    Integer.parseInt(ccyymmdd.substring(4, 6)),
                    Integer.parseInt(ccyymmdd.substring(6, 8)));
            return true;
        } catch (final DateTimeException noSuchDay) {
            // The exception is the answer, not an error: java.time signals an impossible
            // day-of-month combination this way and there is nothing to report or wrap.
            return false;
        }
    }

    @Nested
    @DisplayName("1. The sixteen paragraph labels collapse one for one, exits included")
    class ParagraphCollapse {

        @Test
        @DisplayName("the frozen sources declare exactly sixteen labels: two, fourteen and none")
        void theCorpusDeclaresSixteenLabels() {
            // The census this class asserts against. Two labels come from the utility program and
            // fourteen from the procedure division copybook; the working storage copybook is a pure
            // work area and declares none. Fewer than sixteen mapped methods means a paragraph lost its
            // Java counterpart, which is what paragraph-level correspondence forbids.
            //
            // Three defects sit in the comment text the census walks past and are
            // deliberately not corrected, because app/ is frozen: the header at
            // app/cpy/CSUTLDPY.cpy:L5 names the companion work area 'CSUTLDTR' where it means
            // CSUTLDWY, and :L14-L15 list EDIT-DATE-OF-BIRTH twice, as both item d) and item e).
            // Neither affects a single generated line; they are recorded so that a reader who spots
            // them knows they were seen and left alone.
            final long fromUtility = PARAGRAPHS.stream()
                    .filter(row -> UTILITY_PATH.equals(row.sourcePath()))
                    .count();
            final long fromCopybook = PARAGRAPHS.stream()
                    .filter(row -> COPYBOOK_PATH.equals(row.sourcePath()))
                    .count();

            assertThat(fromUtility).as("labels contributed by app/cbl/CSUTLDTC.cbl").isEqualTo(2L);
            assertThat(fromCopybook).as("labels contributed by app/cpy/CSUTLDPY.cpy").isEqualTo(14L);
            assertThat(PARAGRAPHS).as("the whole paragraph map").hasSize(16);
        }

        @Test
        @DisplayName("every one of the sixteen labels has its own private method on the bean")
        void everyLabelHasItsOwnPrivateMethod() {
            // Reflection is used for structural inspection only: getDeclaredMethods reads the class,
            // nothing is made accessible and nothing is invoked reflectively. Rule 1 Clause D bars
            // reflection driven arbitrary invocation, not a read only census.
            final Set<String> declared = new TreeSet<>();
            for (final Method method : DateValidationService.class.getDeclaredMethods()) {
                if (Modifier.isPrivate(method.getModifiers())) {
                    declared.add(method.getName());
                }
            }

            assertThat(declared)
                    .as("the bean must declare one private method per COBOL paragraph label")
                    .containsAll(PARAGRAPHS.stream().map(ParagraphMapping::javaMethod).toList());
        }

        @Test
        @DisplayName("no two labels share a method, so none has been consolidated away")
        void noLabelsAreConsolidated() {
            // A port that folded, say, the seven bare-EXIT paragraphs into their predecessors would
            // still pass every behavioural test in this file. Distinctness of the sixteen method
            // names is what makes consolidation detectable at all.
            final Set<String> methods = new TreeSet<>(
                    PARAGRAPHS.stream().map(ParagraphMapping::javaMethod).toList());
            final Set<String> labels = new TreeSet<>(
                    PARAGRAPHS.stream().map(ParagraphMapping::cobolLabel).toList());

            assertThat(methods).as("sixteen distinct method names").hasSize(16);
            assertThat(labels).as("sixteen distinct COBOL labels").hasSize(16);
        }

        @Test
        @DisplayName("each of the sixteen methods cites its source path, its label and its line")
        void eachMethodCitesItsSourceLocator() {
            // The citation is the artefact Gate 7 reads. It is not observable at runtime, so it is
            // asserted by reading the production source; see the class documentation for why that is
            // the convention of this tree rather than an environment assumption.
            final List<String> uncited = new ArrayList<>();
            for (final ParagraphMapping row : PARAGRAPHS) {
                final String javadoc = javadocPreceding(row.javaMethod());
                final String citation = row.sourcePath() + ":L" + row.line();
                if (!javadoc.contains(row.cobolLabel()) || !javadoc.contains(citation)) {
                    uncited.add(row.javaMethod() + " must cite " + row.cobolLabel() + " at " + citation);
                }
            }

            assertThat(uncited).as("methods whose documentation does not cite their paragraph").isEmpty();
        }

        @Test
        @DisplayName("the seven bare-EXIT paragraphs are mapped rather than deleted, and are marked no-ops")
        void theBareExitParagraphsAreMappedNotDeleted() {
            // These seven paragraphs are reachable no-ops in the source, so they are mapped rather than
            // deleted. Each carries a Javadoc citation plus an explicit intentional-no-op marker, which is
            // what keeps it from reading as residue. Deleting a call site would break the paragraph map,
            // which the label count below catches.
            final List<String> unmarked = new ArrayList<>();
            for (final String exitMethod : List.of("a000MainExit", "editYearCcyyExit", "editMonthExit",
                    "editDayExit", "editDayMonthYearExit", "editDateCcyymmddExit", "editDateOfBirthExit")) {
                if (!bodyOf(exitMethod).contains("Intentionally empty")) {
                    unmarked.add(exitMethod);
                }
            }

            assertThat(unmarked).as("no-op paragraphs lacking an intentional-no-op marker").isEmpty();
        }

        @Test
        @DisplayName("the eighth exit paragraph is NOT a no-op, because :L327 sits after its bare EXIT")
        void theLanguageEnvironmentExitIsNotANoOp() {
            // EDIT-DATE-LE-EXIT spans app/cpy/CSUTLDPY.cpy:L323-L328: the bare EXIT at :L324-L325, a
            // comment at :L326, then SET WS-EDIT-DATE-IS-VALID TO TRUE at :L327. Because the EXIT is
            // documentary, :L327 executes. This is the one exit paragraph with a body, and reading it
            // as empty is the mistake this test exists to prevent.
            final String body = bodyOf("editDateLeExit");

            assertThat(body).as("EDIT-DATE-LE-EXIT must carry the :L327 set").contains(":L327");
            assertThat(body).as("all three flags are set by the group level condition")
                    .contains("EditFlag.ISVALID");
            assertThat(body).as("it is not an intentional no-op").doesNotContain("Intentionally empty");
        }
    }

    @Nested
    @DisplayName("2. The clock arrives through the constructor and nothing else keeps state")
    class ConstructorInjectionAndStatelessness {

        @Test
        @DisplayName("the bean is constructed with its clock, which is its only collaborator")
        void theBeanTakesItsClockThroughTheConstructor() {
            // Rule 1 Clause B: prefer dependency injection over global mutable state. The source's
            // FUNCTION CURRENT-DATE at app/cpy/CSUTLDPY.cpy:L343 is the only ambient input the
            // paragraphs have, and this is where it enters.
            final Clock clock = FixedClockProvider.canonicalClock();

            assertThatCode(() -> new DateValidationService(clock)).doesNotThrowAnyException();
            assertThat(DateValidationService.class.getDeclaredConstructors())
                    .as("exactly one constructor, so the clock cannot be omitted").hasSize(1);
            assertThat(DateValidationService.class.getDeclaredConstructors()[0].getParameterTypes())
                    .as("and its single parameter is the clock").containsExactly(Clock.class);
        }

        @Test
        @DisplayName("a null clock is refused at construction rather than at first use")
        void aNullClockIsRefusedAtConstruction() {
            // A wiring defect, not a data condition, so it is reported immediately and with context.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateValidationService(null))
                    .withMessage("clock must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("neither the bean nor any of its nested types holds mutable static state")
        void nothingIsHeldInMutableStaticState() {
            // The source's whole date editing working storage - the three flags, the error indicator,
            // the message, the divisor and the remainder - is per call state in COBOL and must be per
            // call state here too. Rule 1 Clause B: avoid global mutable state.
            final List<String> mutable = new ArrayList<>();
            collectMutableStatics(DateValidationService.class, mutable);
            for (final Class<?> nested : DateValidationService.class.getDeclaredClasses()) {
                collectMutableStatics(nested, mutable);
            }

            assertThat(mutable).as("static fields that are not final").isEmpty();
        }

        @Test
        @DisplayName("two beans on two clocks are independent, so no state leaks between them")
        void twoBeansOnTwoClocksAreIndependent() {
            final DateValidationService asOf2019 = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2019-01-01T00:00:00Z")));
            final DateValidationService asOf2031 = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2031-01-01T00:00:00Z")));

            // The same synthetic value, two beans, two verdicts - and running them in this order and
            // then in reverse must not change either answer.
            assertThat(asOf2019.editDateOfBirth("20300115", "Date of Birth").valid()).isFalse();
            assertThat(asOf2031.editDateOfBirth("20300115", "Date of Birth").valid()).isTrue();
            assertThat(asOf2019.editDateOfBirth("20300115", "Date of Birth").valid()).isFalse();
        }

        @Test
        @DisplayName("repeating a call yields an equal outcome, so the per-call work area is fresh")
        void repeatingACallYieldsAnEqualOutcome() {
            // The three flags, INPUT-ERROR and WS-RETURN-MSG are method local. Were any of them a
            // bean field, the latch would already be closed on the second call and the message would
            // come back blank.
            final EditOutcome first = service.editDate("20220631", "Date");
            final EditOutcome second = service.editDate("20220631", "Date");

            assertThat(second).isEqualTo(first);
            assertThat(second.hasReturnMessage()).as("the latch reopens for a new call").isTrue();
        }

        /**
         * Collects the names of every static field of a type that is not also final.
         *
         * @param type the type to inspect
         * @param sink the list receiving the qualified names of any offending fields
         */
        private void collectMutableStatics(final Class<?> type, final List<String> sink) {
            for (final Field field : type.getDeclaredFields()) {
                final int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                    sink.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }
    }

    @Nested
    @DisplayName("3. FC-INVALID-DATE is the all-zeros SUCCESS token, and the name says the opposite")
    class TheMisnamedSuccessToken {

        @Test
        @DisplayName("BEWARE THE NAME: the all-zeros token yields a VALID outcome, not an invalid one")
        void theAllZerosTokenYieldsAValidOutcomeDespiteItsName() {
            // app/cbl/CSUTLDTC.cbl:L62 declares 88 FC-INVALID-DATE VALUE X'0000000000000000' on the
            // eight byte group FEEDBACK-TOKEN-VALUE at :L61. All zeros is severity zero and message
            // number zero, and :L130 moves 'Date is valid' for exactly that arm of the EVALUATE.
            //
            // A port that trusts the identifier maps this token to an invalid
            // outcome and produces exactly inverted behaviour that still compiles, still runs, and
            // still passes a naively written test - because the naive test asserts the name.
            final DateValidationResult result =
                    service.validate("20220610", DateValidationService.MASK_YYYYMMDD);

            assertThat(result.valid()).as("the all-zeros token means the date was ACCEPTED").isTrue();
            assertThat(result.feedbackCode())
                    .as("severity zero, message number zero, per app/cbl/CSUTLDTC.cbl:L62")
                    .isEqualTo(new FeedbackCode(0, 0));
            assertThat(result.feedbackCode().condition())
                    .as("and it resolves to the misnamed condition itself")
                    .contains(FeedbackCondition.FC_INVALID_DATE);
            assertThat(result.resultText().strip())
                    .as("the literal app/cbl/CSUTLDTC.cbl:L130 moves for this arm")
                    .isEqualTo("Date is valid");
        }

        @Test
        @DisplayName("a non-zero severity is what makes an outcome invalid, whatever the token is called")
        void aNonZeroSeverityIsWhatMakesAnOutcomeInvalid() {
            // The copybook corroborates the inversion at app/cpy/CSUTLDPY.cpy:L298, which tests
            // WS-SEVERITY-N = 0 rather than any condition name. Validity is the severity, never the
            // identifier.
            final DateValidationResult rejected =
                    service.validate("20221310", DateValidationService.MASK_YYYYMMDD);

            assertThat(rejected.feedbackCode().severity()).as("a rejection carries severity three").isEqualTo(3);
            assertThat(rejected.valid()).as("and is therefore invalid").isFalse();
            assertThat(rejected.feedbackCode().condition())
                    .as("a condition whose name reads positively is nonetheless a rejection")
                    .contains(FeedbackCondition.FC_INVALID_MONTH);
        }

        @Test
        @DisplayName("the severity IS the process return code, a dual output beside the eighty-byte area")
        void theSeverityIsTheProcessReturnCode() {
            // app/cbl/CSUTLDTC.cbl:L97-L98 moves WS-MESSAGE to LS-RESULT and then WS-SEVERITY-N to
            // RETURN-CODE. The utility therefore publishes two channels from one run, and a caller
            // that reads only the eighty byte block loses the exit code the JCL would have tested.
            final DateValidationResult accepted =
                    service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult rejected =
                    service.validate("2022-13-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(accepted.returnCode()).as("severity zero becomes return code zero").isZero();
            assertThat(rejected.returnCode())
                    .as("and a rejection's severity becomes the return code unchanged")
                    .isEqualTo(rejected.feedbackCode().severity());
            assertThat(rejected.returnCode())
                    .as("which is the same value the four-byte severity field renders")
                    .isEqualTo(Integer.parseInt(rejected.severityCode()));
        }

        @Test
        @DisplayName("the caller's gate is the four-byte severity compared against '0000'")
        void theCallersGateIsTheFourByteSeverity() {
            // app/cbl/CORPT00C.cbl:L133 declares CSUTLDTC-RESULT-SEV-CD PIC X(04), and the four
            // program call sites compare exactly that field. Nothing else in the eighty bytes decides
            // acceptance, which is why the outcome is a value rather than an exception.
            final DateValidationResult accepted =
                    service.validate("20220610", DateValidationService.MASK_YYYYMMDD);
            final DateValidationResult rejected =
                    service.validate("20220631", DateValidationService.MASK_YYYYMMDD);

            assertThat(accepted.severityCode()).isEqualTo("0000");
            assertThat(rejected.severityCode()).isNotEqualTo("0000");
            assertThat(accepted.valid()).isEqualTo("0000".equals(accepted.severityCode()));
            assertThat(rejected.valid()).isEqualTo("0000".equals(rejected.severityCode()));
        }
    }

    @Nested
    @DisplayName("4. The paragraphs fall through: a component GO TO is not an early return")
    class SequentialFallThrough {

        /**
         * A value whose year, month and day each fail differently: century eighteen, an absent month
         * and a day of ninety nine. Obviously synthetic, and chosen because the three failures are
         * distinguishable by flag state alone.
         */
        private static final String THREE_WAY_FAILURE = "1855  99";

        @Test
        @DisplayName("all three component edits run even though the first one already failed")
        void allThreeComponentEditsRunEvenWhenTheFirstFails() {
            // Every -EXIT paragraph body is a bare COBOL EXIT, a documentary
            // no-op, so control walks straight through app/cpy/CSUTLDPY.cpy:L18 to :L25 to :L88 to
            // :L91 to :L145 to :L150 to :L205 to :L209. GO TO EDIT-YEAR-CCYY-EXIT at :L42 therefore
            // leaves EDIT-MONTH and EDIT-DAY still to run. A port that modelled each component exit
            // as a Java return would set exactly one flag and leave the other two at the '0' the
            // single statement of EDIT-DATE-CCYYMMDD at :L19 wrote.
            final EditOutcome outcome = service.editDate(THREE_WAY_FAILURE, "Date");

            assertThat(outcome.yearFlag())
                    .as("EDIT-YEAR-CCYY ran and rejected century eighteen at app/cpy/CSUTLDPY.cpy:L70-L83")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.monthFlag())
                    .as("EDIT-MONTH ran too, and found the month absent at :L94-L105")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.dayFlag())
                    .as("EDIT-DAY ran as well, and found the day out of range at :L187-L199")
                    .isEqualTo(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("only the first failure emits a message, latched by IF WS-RETURN-MSG-OFF")
        void onlyTheFirstFailureEmitsAMessage() {
            // Each STRING into WS-RETURN-MSG sits under IF WS-RETURN-MSG-OFF, so the first branch to
            // fire closes the latch for the rest of the edit. Three failures, one message.
            final EditOutcome outcome = service.editDate(THREE_WAY_FAILURE, "Date");

            assertThat(outcome.hasReturnMessage()).as("exactly one message was produced").isTrue();
            assertThat(outcome.returnMessage().strip())
                    .as("and it is the earliest failure's message, from EDIT-YEAR-CCYY")
                    .isEqualTo("Date : Century is not valid.");
            assertThat(outcome.returnMessage())
                    .as("the later month and day failures were suppressed by the latch")
                    .doesNotContain("must be supplied")
                    .doesNotContain("between 1 and 31");
        }

        @Test
        @DisplayName("INPUT-ERROR is set once and never cleared, however many components fail")
        void inputErrorIsSetOnceAndNeverCleared() {
            // The source never clears INPUT-ERROR inside one edit, which is what lets a later
            // paragraph read it: app/cpy/CSUTLDPY.cpy:L318 sets the day flag valid only IF NOT
            // INPUT-ERROR.
            assertThat(service.editDate(THREE_WAY_FAILURE, "Date").inputError()).isTrue();
            assertThat(service.editDate("20220610", "Date").inputError()).isFalse();
        }

        @Test
        @DisplayName("the :L274 gate stops the language-environment call when a component failed")
        void theConjunctionGateStopsTheLanguageEnvironmentCall() {
            // app/cpy/CSUTLDPY.cpy:L274-L278 tests the group level valid condition and, when it does
            // not hold, takes GO TO EDIT-DATE-CCYYMMDD-EXIT at :L277 - skipping EDIT-DATE-LE
            // entirely.
            //
            // The proof is by contradiction on the flag state. Had the gate not held, EDIT-DATE-LE at
            // :L284 would have called the utility on this value; the utility rejects it, as the first
            // assertion below shows independently; the failure branch at :L301-L304 would then have
            // set ALL THREE flags to '0'; and EDIT-DATE-LE-EXIT's :L327 would have wiped all three to
            // LOW-VALUES. Neither of those two states contains a 'B'. The observed BLANK month flag
            // is therefore only reachable if EDIT-DATE-LE never ran.
            final DateValidationResult whatTheUtilityWouldHaveSaid =
                    service.validate(THREE_WAY_FAILURE, DateValidationService.MASK_YYYYMMDD);
            final EditOutcome outcome = service.editDate(THREE_WAY_FAILURE, "Date");

            assertThat(whatTheUtilityWouldHaveSaid.valid())
                    .as("the utility would have rejected this value, so its branch is distinguishable")
                    .isFalse();
            assertThat(outcome.monthFlag())
                    .as("BLANK survives neither the :L301-L304 failure branch nor the :L327 wipe")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage())
                    .as("and no language-environment message was composed at :L306-L313")
                    .doesNotContain("validation error Sev code:");
        }

        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource({
            "18220610, a component early-out then the :L274 gate at :L277",
            "20220631, the thirty-one-day refusal at :L225",
            "20220230, the thirty-February refusal at :L240",
            "20230229, the leap-year refusal at :L270",
            "1855  99, three component failures then the :L274 gate at :L277",
        })
        @DisplayName("the five paths that branch straight to :L329 skip :L327 and PRESERVE their flags")
        void thePathsThatBranchStraightToTheChainExitPreserveTheirFlags(final String candidate,
                final String path) {
            // Each of these five GO TO EDIT-DATE-CCYYMMDD-EXIT jumps lands at :L329, which is AFTER
            // :L327. The set of the group level valid condition is therefore skipped and the flags
            // the component edits left behind survive to the caller. Were :L327 reached on these
            // paths, every one of these rejections would be reported as an acceptance.
            final EditOutcome outcome = service.editDate(candidate, "Date");

            assertThat(outcome.valid()).as("via " + path).isFalse();
            assertThat(outcome.inputError()).as("via " + path).isTrue();
            assertThat(List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag()))
                    .as("at least one flag is not ISVALID, so :L327 did not run - via " + path)
                    .contains(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the path that reaches :L327 sets all three flags valid, on both of its entries")
        void thePathThatReachesTheLanguageEnvironmentExitSetsAllThreeFlagsValid() {
            // The success fall-through at :L321 arrives at :L323, the bare EXIT at :L324-L325 does
            // nothing, the comment at :L326 says "If we got here all edits were cleared", and :L327
            // sets the group level valid condition. That single statement is unconditional, which is
            // exactly why the failure entry from :L315 would wipe the flags it had just set.
            final EditOutcome outcome = service.editDate("20220610", "Date");

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.valid()).isTrue();
        }

        @Test
        @DisplayName("no admissible input reaches the :L315 failure entry, so the :L327 wipe is unreachable")
        void theLanguageEnvironmentFailureEntryIsUnreachableFromTheCompositeEdit() {
            // The source explains EDIT-DATE-LE at :L286-L288 as a last line of defence "in case some
            // one managed to enter a bad date that passsed all the edits above" - its own spelling.
            // Exhausting the admissible input space shows nobody manages it: every value that clears
            // the year, month, day and combination edits is also accepted by the utility.
            //
            // Consequence, severity High if mishandled: the :L327 wipe of the four flags :L301-L304
            // had just set is real code on a real branch, but that branch is not selectable through
            // this bean. It is therefore preserved and documented rather than exercised, and the two
            // survivors of the wipe - INPUT-ERROR and WS-RETURN-MSG - are asserted above instead.
            final List<String> reachedTheFailureBranch = new ArrayList<>();
            for (final String candidate : admissibleCandidates()) {
                if (service.editDate(candidate, "Date").returnMessage()
                        .contains("validation error Sev code:")) {
                    reachedTheFailureBranch.add(candidate);
                }
            }

            assertThat(reachedTheFailureBranch)
                    .as("admissible values that the utility nonetheless rejected")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("5. Three independent tri-state flags, whose conjunction is the composite verdict")
    class TriStateFlagConjunction {

        @ParameterizedTest(name = "[{index}] the year flag reaches {1} for an obviously synthetic input")
        @CsvSource({
            "20220610, ISVALID",
            "18220610, NOT_OK",
            "'    0610', BLANK",
        })
        @DisplayName("the year flag reaches all three of its declared states")
        void theYearFlagReachesAllThreeStates(final String candidate, final EditFlag expected) {
            // app/cpy/CSUTLDWY.cpy:L46-L49 declares WS-EDIT-YEAR-FLG PIC X(01) with three condition
            // names: LOW-VALUES is valid, '0' is not acceptable and 'B' is not supplied. A boolean
            // cannot hold three states, which is why EditFlag is an enumeration.
            assertThat(service.editDate(candidate, "Date").yearFlag()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] the month flag reaches {1}")
        @CsvSource({
            "20220610, ISVALID",
            "20221310, NOT_OK",
            "'2022  10', BLANK",
        })
        @DisplayName("the month flag reaches all three of its declared states")
        void theMonthFlagReachesAllThreeStates(final String candidate, final EditFlag expected) {
            // app/cpy/CSUTLDWY.cpy:L50-L53, the second byte of the group.
            assertThat(service.editDate(candidate, "Date").monthFlag()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] the day flag reaches {1}")
        @CsvSource({
            "20220610, ISVALID",
            "20220632, NOT_OK",
            "'202206  ', BLANK",
        })
        @DisplayName("the day flag reaches all three of its declared states")
        void theDayFlagReachesAllThreeStates(final String candidate, final EditFlag expected) {
            // app/cpy/CSUTLDWY.cpy:L54-L57, the third byte of the group.
            assertThat(service.editDate(candidate, "Date").dayFlag()).isEqualTo(expected);
        }

        @Test
        @DisplayName("composite validity is DERIVED by conjunction, never stored as a fourth flag")
        void compositeValidityIsDerivedByConjunction() {
            // WS-EDIT-DATE-IS-VALID at app/cpy/CSUTLDWY.cpy:L44 is declared on the three byte GROUP
            // WS-EDIT-DATE-FLGS at :L43, so it tests all three bytes against LOW-VALUES at once. Any
            // single component failing is therefore enough to make the composite invalid, and there
            // is no separate composite byte that could disagree with its parts.
            assertThat(service.editDate("20220610", "Date").valid())
                    .as("all three ISVALID is the only accepting combination").isTrue();

            for (final String singleComponentFailure : List.of("18220610", "20221310", "20220632")) {
                final EditOutcome outcome = service.editDate(singleComponentFailure, "Date");
                final long stillValid = List.of(outcome.yearFlag(), outcome.monthFlag(), outcome.dayFlag())
                        .stream().filter(flag -> flag == EditFlag.ISVALID).count();

                assertThat(stillValid).as("two of the three components still passed").isEqualTo(2L);
                assertThat(outcome.valid()).as("yet the conjunction is false").isFalse();
            }
        }

        @Test
        @DisplayName("the two group-level conditions are NOT complementary: '0','B','0' satisfies neither")
        void theTwoGroupLevelConditionsAreNotComplementary() {
            // WS-EDIT-DATE-IS-VALID is VALUE LOW-VALUES at :L44 and
            // WS-EDIT-DATE-IS-INVALID is VALUE '000' at :L45. A group holding '0', 'B', '0' equals
            // neither of those two byte patterns, so both conditions are false at the same time. A
            // port that stored one boolean and negated it for the other would report this state as
            // wholly invalid and lose the fact that the month was never supplied at all.
            final EditOutcome fromRealInput = service.editDate("1855  99", "Date");

            assertThat(fromRealInput.yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(fromRealInput.monthFlag()).isEqualTo(EditFlag.BLANK);
            assertThat(fromRealInput.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(fromRealInput.valid())
                    .as("the LOW-VALUES group condition does not hold").isFalse();
            assertThat(fromRealInput.allComponentsNotOk())
                    .as("and neither does the '000' group condition").isFalse();
        }

        @Test
        @DisplayName("the outcome record itself exposes the non-complementarity, independently of any input")
        void theOutcomeRecordExposesTheNonComplementarity() {
            // Asserted on a directly constructed value as well, so the property is pinned on the type
            // rather than only on one path that happens to produce it.
            final EditOutcome mixed = new EditOutcome(EditFlag.NOT_OK, EditFlag.BLANK, EditFlag.NOT_OK,
                    true, " ".repeat(DateValidationService.RETURN_MESSAGE_LENGTH));

            assertThat(mixed.valid()).isFalse();
            assertThat(mixed.allComponentsNotOk()).isFalse();
            assertThat(new EditOutcome(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK, true,
                    " ".repeat(DateValidationService.RETURN_MESSAGE_LENGTH)).allComponentsNotOk())
                    .as("only a group of three '0' bytes satisfies the second condition").isTrue();
        }

        @Test
        @DisplayName("a single boolean would lose information: three distinct rejecting group states exist")
        void aSingleBooleanWouldLoseInformation() {
            // Three reachable rejections whose group states differ. Collapsing the flags into one
            // boolean maps all three onto the same value and discards which component was at fault -
            // which is precisely what the legacy screens rendered.
            final Set<String> distinctGroupStates = new TreeSet<>();
            for (final String candidate : List.of("        ", "19000229", "1855  99")) {
                final EditOutcome outcome = service.editDate(candidate, "Date");
                distinctGroupStates.add(outcome.yearFlag() + "/" + outcome.monthFlag()
                        + "/" + outcome.dayFlag());
                assertThat(outcome.valid()).as("all three are rejections").isFalse();
            }

            assertThat(distinctGroupStates)
                    .as("all-BLANK, all-NOT_OK and the mixed group are three different states")
                    .hasSize(3);
        }

        @Test
        @DisplayName("year and month initialise PESSIMISTICALLY while day initialises OPTIMISTICALLY")
        void theInitialisationAsymmetryIsPreservedNotNormalised() {
            // app/cpy/CSUTLDPY.cpy:L27 sets FLG-YEAR-NOT-OK and :L92 sets
            // FLG-MONTH-NOT-OK before any test runs, but :L152 sets FLG-DAY-ISVALID - the exact
            // opposite. Every path then overwrites the initial state, so the asymmetry is NOT
            // observable through the public surface; that is why it must be pinned structurally, or a
            // future tidy-up would normalise all three and no behavioural test would notice.
            assertThat(firstFlagAssignedIn("editYearCcyy"))
                    .as("EDIT-YEAR-CCYY at app/cpy/CSUTLDPY.cpy:L27 is pessimistic")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(firstFlagAssignedIn("editMonth"))
                    .as("EDIT-MONTH at app/cpy/CSUTLDPY.cpy:L92 is pessimistic too")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(firstFlagAssignedIn("editDay"))
                    .as("but EDIT-DAY at app/cpy/CSUTLDPY.cpy:L152 is optimistic")
                    .isEqualTo(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("the month checks range then numeric, while the day checks numeric then range")
        void theOrderOfTheTwoChecksIsReversedBetweenMonthAndDay() {
            // EDIT-MONTH tests the range at app/cpy/CSUTLDPY.cpy:L111 and only then
            // TEST-NUMVAL at :L126; EDIT-DAY tests TEST-NUMVAL at :L170 and only then the range at
            // :L187. Both branches of each pair emit that component's single message literal, so the
            // order is not distinguishable from the flags or the message - the structural assertion is
            // the only one available, and reversing either order is a silent parity break.
            final String monthBody = bodyOf("editMonth");
            final String dayBody = bodyOf("editDay");

            assertThat(monthBody.indexOf("MONTH_MINIMUM"))
                    .as("EDIT-MONTH: the :L111 range test comes first")
                    .isGreaterThan(0)
                    .isLessThan(monthBody.indexOf("NON_NUMERIC_VIEW"));
            assertThat(dayBody.indexOf("NON_NUMERIC_VIEW"))
                    .as("EDIT-DAY: the :L170 numeric test comes first")
                    .isGreaterThan(0)
                    .isLessThan(dayBody.indexOf("DAY_MINIMUM"));
        }

        @Test
        @DisplayName("EDIT-DATE-CCYYMMDD is one statement writing '000' to all three bytes, not a stub")
        void theChainEntryWritesTheWhollyInvalidStateInOneStatement() {
            // Worth pinning: app/cpy/CSUTLDPY.cpy:L18-L20 contains a single SET of
            // WS-EDIT-DATE-IS-INVALID, which writes '0' to all three bytes of the group at once. It
            // reads like an unfinished paragraph and is not one.
            final String body = bodyOf("editDateCcyymmdd");
            final long assignments = body.lines()
                    .filter(line -> line.contains("EditFlag.NOT_OK"))
                    .count();

            assertThat(body).as("it cites the single source statement").contains(":L19");
            assertThat(assignments).as("one COBOL statement, three bytes").isEqualTo(3L);
        }

        @Test
        @DisplayName("EDIT-DAY sets its flag valid twice, and the redundant second set is preserved")
        void theRedundantDayFlagReSetIsPreserved() {
            // the fourth instance in this file of the no-dead-code versus parity
            // conflict. app/cpy/CSUTLDPY.cpy:L152 already sets FLG-DAY-ISVALID optimistically, and no
            // path that reaches :L203 has changed it, so the second SET is unreachable as a state
            // change. It is nonetheless a real statement on a real path and is preserved with an
            // explicit marker rather than deleted, because removing it would break the statement-level
            // correspondence with the source paragraph. This assertion is what keeps it visible.
            final String body = bodyOf("editDay");
            final long validAssignments = body.lines()
                    .filter(line -> line.contains("EditFlag.ISVALID"))
                    .count();

            assertThat(validAssignments)
                    .as("the :L152 optimistic initialisation and the :L203 redundant re-set")
                    .isEqualTo(2L);
            assertThat(body).as(":L203 is marked as intentional, not mistaken").contains(":L203");
            assertThat(service.editDate("20220610", "Date").dayFlag())
                    .as("and the observable outcome is identical either way")
                    .isEqualTo(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("WS-VALID-FEB-DAY is bounded at 28, not 29, and is declared without ever being used")
        void theFebruaryDayConditionNameIsBoundedAtTwentyEightAndUnreferenced() {
            // app/cpy/CSUTLDWY.cpy:L33-L34 declares 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28. Writing that
            // bound as 29 would be wrong: a February range of one through twenty nine would
            // accept the twenty ninth without ever consulting the leap year test at
            // app/cpy/CSUTLDPY.cpy:L243-L272. The condition name is never
            // referenced anywhere in the corpus - it is preserved as a documented dead artefact under
            // the parity mandate, and this assertion is what pins it.
            final long occurrences = SERVICE_SOURCE.lines()
                    .filter(line -> line.contains("VALID_FEBRUARY_DAY_MAXIMUM"))
                    .count();

            assertThat(DateValidationService.VALID_FEBRUARY_DAY_MAXIMUM)
                    .as("the declared upper bound of WS-VALID-FEB-DAY").isEqualTo(28);
            assertThat(occurrences)
                    .as("exactly one occurrence, its own declaration, so it is unreferenced")
                    .isEqualTo(1L);
            assertThat(service.editDate("20200229", "Date").valid())
                    .as("the twenty ninth is nonetheless accepted in a leap year, via the leap test")
                    .isTrue();
        }

        /**
         * Returns the state a paragraph assigns to its own component flag before any test runs.
         *
         * @param methodName the paragraph method to inspect
         * @return the first of the three flag states its body assigns
         */
        private EditFlag firstFlagAssignedIn(final String methodName) {
            final String body = bodyOf(methodName);
            EditFlag first = null;
            int earliest = Integer.MAX_VALUE;
            for (final EditFlag candidate : EditFlag.values()) {
                final int position = body.indexOf("EditFlag." + candidate.name());
                if (position >= 0 && position < earliest) {
                    earliest = position;
                    first = candidate;
                }
            }
            assertThat(first).as(methodName + " must assign a component flag").isNotNull();
            return first;
        }
    }

    @Nested
    @DisplayName("6. The declared boundary rules, asserted as behaviour")
    class BoundaryRulesAsBehaviour {

        @ParameterizedTest(name = "[{index}] century {1} is {2}")
        @CsvSource({
            "18220610, 18, rejected",
            "19220610, 19, accepted",
            "20220610, 20, accepted",
            "21220610, 21, rejected",
        })
        @DisplayName("only centuries 19 and 20 are admissible, exactly as the source explains")
        void onlyTwoCenturiesAreAdmissible(final String candidate, final int century, final String verdict) {
            // app/cpy/CSUTLDWY.cpy:L9-L10 declares 88 THIS-CENTURY VALUE 20 and 88 LAST-CENTURY
            // VALUE 19, and the gate at app/cpy/CSUTLDPY.cpy:L70-L72 admits only those two. The
            // source records its own reasoning at :L66-L68: not having learnt the lesson of Y2K, and
            // unable to imagine COBOL in the 2100s, it codes only 19 and 20.
            final boolean accepted = "accepted".equals(verdict);

            assertThat(service.editDate(candidate, "Date").valid())
                    .as("century " + century).isEqualTo(accepted);
        }

        @ParameterizedTest(name = "[{index}] month {1} is {2}")
        @CsvSource({
            "20220010, 0, rejected",
            "20220110, 1, accepted",
            "20221210, 12, accepted",
            "20221310, 13, rejected",
        })
        @DisplayName("the month range is one through twelve, and both ends are inclusive")
        void theMonthRangeIsOneThroughTwelve(final String candidate, final int month, final String verdict) {
            // 88 WS-VALID-MONTH VALUES 1 THROUGH 12, app/cpy/CSUTLDWY.cpy:L19-L20.
            assertThat(service.editDate(candidate, "Date").valid())
                    .as("month " + month).isEqualTo("accepted".equals(verdict));
        }

        @ParameterizedTest(name = "[{index}] day {1} is {2}")
        @CsvSource({
            "20220100, 0, rejected",
            "20220101, 1, accepted",
            "20220131, 31, accepted",
            "20220132, 32, rejected",
        })
        @DisplayName("the day range is one through thirty-one, and both ends are inclusive")
        void theDayRangeIsOneThroughThirtyOne(final String candidate, final int day, final String verdict) {
            // 88 WS-VALID-DAY VALUES 1 THROUGH 31, app/cpy/CSUTLDWY.cpy:L28-L29. January is used for
            // the upper bound so that the range test, not the combination test, is what decides.
            assertThat(service.editDate(candidate, "Date").valid())
                    .as("day " + day).isEqualTo("accepted".equals(verdict));
        }

        @Test
        @DisplayName("thirty-one days in a thirty-day month is refused, and blames month AND day")
        void thirtyOneDaysInAThirtyDayMonthIsRefused() {
            // app/cpy/CSUTLDPY.cpy:L213-L226. The combination check sets BOTH the day flag at :L216
            // and the month flag at :L217 before taking GO TO EDIT-DATE-CCYYMMDD-EXIT at :L225, which
            // is why a single component is not enough to describe this rejection.
            final EditOutcome june31 = service.editDate("20220631", "Date");

            assertThat(june31.valid()).isFalse();
            assertThat(june31.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(june31.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(june31.yearFlag())
                    .as("the year was never at fault and is left as EDIT-YEAR-CCYY set it")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(service.editDate("20220731", "Date").valid())
                    .as("July does have thirty-one days, per 88 WS-31-DAY-MONTH at CSUTLDWY.cpy:L21-L23")
                    .isTrue();
        }

        @Test
        @DisplayName("thirty February is refused on its own check, separate from the thirty-one-day one")
        void thirtyFebruaryIsRefused() {
            // app/cpy/CSUTLDPY.cpy:L228-L241, a distinct branch because February is not a thirty day
            // month either and would otherwise slip past the first check.
            final EditOutcome february30 = service.editDate("20220230", "Date");

            assertThat(february30.valid()).isFalse();
            assertThat(february30.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(february30.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(service.editDate("20220330", "Date").valid())
                    .as("March does have a thirtieth").isTrue();
        }

        @ParameterizedTest(name = "[{index}] 29 February {1} is {2}")
        @CsvSource({
            "20000229, 2000, accepted",
            "19000229, 1900, rejected",
            "20240229, 2024, accepted",
            "20230229, 2023, rejected",
        })
        @DisplayName("the leap-year test is correct Gregorian: 2000 yes, 1900 no, 2024 yes, 2023 no")
        void theLeapYearTestIsCorrectGregorian(final String candidate, final int year, final String verdict) {
            // app/cpy/CSUTLDPY.cpy:L243-L272. When the two digit year is zero the divisor is 400 at
            // :L246, otherwise 4 at :L248; the DIVIDE at :L251-L254 keeps the remainder and :L256
            // accepts only a remainder of zero. Two branches over integers reproduce the full
            // Gregorian rule for the two admissible centuries: 1900 is divisible by 4 but not by 400
            // and is refused, 2000 is divisible by 400 and is accepted.
            assertThat(service.editDate(candidate, "Date").valid())
                    .as("29 February " + year).isEqualTo("accepted".equals(verdict));
        }

        @Test
        @DisplayName("a leap-year refusal blames all three components, unlike the other two combinations")
        void aLeapYearRefusalBlamesAllThreeComponents() {
            // app/cpy/CSUTLDPY.cpy:L259-L262 sets INPUT-ERROR and then the day, month AND year flags
            // before GO TO EDIT-DATE-CCYYMMDD-EXIT at :L270 - the only combination check that faults
            // the year, because the year is what made the twenty ninth impossible.
            final EditOutcome outcome = service.editDate("20230229", "Date");

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.allComponentsNotOk())
                    .as("this is the '000' group state of app/cpy/CSUTLDWY.cpy:L45").isTrue();
        }

        @Test
        @DisplayName("the leap test is the source's two-branch remainder, not a library predicate")
        void theLeapTestIsTheSourcesTwoBranchRemainder() {
            // Substituting a library leap year predicate is a mechanism substitution
            // that must be logged, and it erases the traceable artefact the matrix cites. The two
            // divisors and the remainder are asserted structurally; the four-way outcome above is the
            // behavioural half of the same claim.
            final String body = bodyOf("editDayMonthYear");

            assertThat(body).as("the :L246 divisor branch").contains("LEAP_DIVISOR_CENTURY");
            assertThat(body).as("the :L248 divisor branch").contains("LEAP_DIVISOR_ORDINARY");
            assertThat(body).as("the :L251-L254 DIVIDE ... REMAINDER, over integers").contains("%");
            assertThat(SERVICE_SOURCE)
                    .as("no library leap-year predicate anywhere in the bean")
                    .doesNotContain("isLeap");
        }

        @Test
        @DisplayName("the composite edit accepts exactly the real Gregorian days of the two centuries")
        void theCompositeEditAcceptsExactlyTheRealGregorianDays() {
            // The strongest available statement of the combination rules: over the whole admissible
            // input space - two centuries, one hundred years, twelve months, thirty-one days - the
            // bean's acceptance set is identical to java.time's, which is an oracle written a
            // completely different way. 200 years contribute 151 non-leap years rejecting seven days
            // each and 49 leap years rejecting six, which is 1057 plus 294, or 1351 rejections out of
            // 74,400 candidates.
            final List<String> disagreements = new ArrayList<>();
            int accepted = 0;
            for (final String candidate : admissibleCandidates()) {
                final boolean beanAccepts = service.editDate(candidate, "Date").valid();
                if (beanAccepts != isRealGregorianDay(candidate)) {
                    disagreements.add(candidate);
                }
                if (beanAccepts) {
                    accepted++;
                }
            }

            assertThat(disagreements).as("values where the bean and java.time disagree").isEmpty();
            assertThat(accepted).as("real days in 1900 through 2099").isEqualTo(73_049);
            assertThat(admissibleCandidates()).as("the admissible input space").hasSize(74_400);
        }
    }

    @Nested
    @DisplayName("7. The date-of-birth check reads the INJECTED clock, and its comparison is strict")
    class DateOfBirthOnTheInjectedClock {

        /** The calendar date the canonical fixed clock reports, as the source's eight character form. */
        private static final String TODAY_ON_THE_CANONICAL_CLOCK = "20220610";

        /** The day before it, the nearest value the strict comparison accepts. */
        private static final String YESTERDAY_ON_THE_CANONICAL_CLOCK = "20220609";

        /** The day after it, unambiguously in the future. */
        private static final String TOMORROW_ON_THE_CANONICAL_CLOCK = "20220611";

        @Test
        @DisplayName("a date of birth EQUAL to the clock's own date is REJECTED, because :L350 is strict")
        void aDateOfBirthEqualToTodayIsRejected() {
            // app/cpy/CSUTLDPY.cpy:L350 reads
            // IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY, and only that strict comparison reaches
            // the CONTINUE at :L354. Equality therefore falls to the ELSE at :L355 and is refused.
            // Relaxing the comparison to a non strict one silently accepts today, which is a parity
            // break no other assertion in this suite would detect.
            assertThat(service.editDateOfBirth(TODAY_ON_THE_CANONICAL_CLOCK, "Date of Birth").valid())
                    .as("the clock's own date is not strictly before itself").isFalse();
            assertThat(service.editDateOfBirth(YESTERDAY_ON_THE_CANONICAL_CLOCK, "Date of Birth").valid())
                    .as("one day earlier is the nearest accepted value").isTrue();
            assertThat(service.editDateOfBirth(TOMORROW_ON_THE_CANONICAL_CLOCK, "Date of Birth").valid())
                    .as("one day later is refused as well").isFalse();
        }

        @Test
        @DisplayName("the comparison is the source's INTEGER-OF-DATE difference, not a library predicate")
        void theComparisonIsTheSourcesIntegerOfDateDifference() {
            // app/cpy/CSUTLDPY.cpy:L345-L348 converts both dates through FUNCTION INTEGER-OF-DATE
            // before comparing them at :L350. The commented out FUNCTION FIND-DURATION alternative at
            // :L351-L353 has unbalanced parentheses and was never live; it is cited here and
            // deliberately not implemented, which this assertion pins.
            assertThat(SERVICE_SOURCE)
                    .as("the abandoned FIND-DURATION alternative must not be implemented")
                    .doesNotContain("FIND-DURATION");
            assertThat(javadocPreceding("editDateOfBirthParagraph"))
                    .as("the paragraph cites its own source span")
                    .contains(COPYBOOK_PATH);
        }

        @Test
        @DisplayName("the verdict follows the injected clock: a bean on a later clock accepts what this one refuses")
        void theVerdictFollowsTheInjectedClock() {
            // The clock is a collaborator, so the same date of birth must flip when the collaborator
            // changes. This is what makes the bean deterministic under test and is the reason
            // FUNCTION CURRENT-DATE at :L343 became an injected java.time.Clock rather than an
            // ambient reading.
            final DateValidationService onTheDayAfter = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2022-06-11T00:00:00Z")));

            assertThat(service.editDateOfBirth(TODAY_ON_THE_CANONICAL_CLOCK, "Date of Birth").valid())
                    .as("refused on the canonical clock").isFalse();
            assertThat(onTheDayAfter.editDateOfBirth(TODAY_ON_THE_CANONICAL_CLOCK, "Date of Birth").valid())
                    .as("accepted on a clock one day later").isTrue();
        }

        @Test
        @DisplayName("no ambient clock is consulted: on a 1980 clock a 1990 birth date is in the future")
        void noAmbientClockIsConsulted() {
            // The decisive proof that the wall clock plays no part. A date of birth in 1990 is
            // comfortably in the past for any real execution of this suite, yet a bean built on a
            // 1980 clock must refuse it. Any use of an ambient reading would accept it and this test
            // would fail - which is exactly the regression it exists to catch.
            final DateValidationService onAnEarlierClock = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("1980-01-01T00:00:00Z")));

            assertThat(onAnEarlierClock.editDateOfBirth("19900101", "Date of Birth").valid())
                    .as("in the future relative to the injected 1980 clock").isFalse();
            assertThat(service.editDateOfBirth("19900101", "Date of Birth").valid())
                    .as("but in the past relative to the canonical 2022 clock").isTrue();
        }

        @Test
        @DisplayName("only YYYYMMDD participates: two clocks on the same day but different times agree")
        void onlyTheEightCharacterDatePortionParticipates() {
            // app/cpy/CSUTLDPY.cpy:L343 moves the twenty one character FUNCTION CURRENT-DATE into a
            // PIC X(08) field, so everything after YYYYMMDD is discarded. Two clocks a whole day apart
            // in time of day but on the same calendar date must therefore reach the same verdict.
            final DateValidationService atMidnight = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2022-06-10T00:00:00Z")));
            final DateValidationService justBeforeTheNextMidnight = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2022-06-10T23:59:59Z")));

            for (final String candidate : List.of(TODAY_ON_THE_CANONICAL_CLOCK,
                    YESTERDAY_ON_THE_CANONICAL_CLOCK, TOMORROW_ON_THE_CANONICAL_CLOCK)) {
                assertThat(atMidnight.editDateOfBirth(candidate, "Date of Birth").valid())
                        .as("the time of day is discarded, so both clocks agree")
                        .isEqualTo(justBeforeTheNextMidnight.editDateOfBirth(candidate,
                                "Date of Birth").valid());
            }
            assertThat(FixedClockProvider.CANONICAL_INSTANT.toString())
                    .as("the canonical instant deliberately carries a non-midnight time of day")
                    .contains("19:27:53");
        }

        @Test
        @DisplayName("the refusal faults all three components and raises INPUT-ERROR")
        void theRefusalFaultsAllThreeComponentsAndRaisesInputError() {
            // app/cpy/CSUTLDPY.cpy:L356 sets INPUT-ERROR and :L357-L359 set the day, month and year
            // flags in that order, so a future date of birth reaches the '000' group state of
            // app/cpy/CSUTLDWY.cpy:L45 even though every component was individually well formed.
            final EditOutcome outcome = service.editDateOfBirth(TOMORROW_ON_THE_CANONICAL_CLOCK,
                    "Date of Birth");

            assertThat(outcome.inputError()).as(":L356 SET INPUT-ERROR TO TRUE").isTrue();
            assertThat(outcome.allComponentsNotOk()).as(":L357-L359 fault all three").isTrue();
            assertThat(outcome.valid()).isFalse();
        }

        @Test
        @DisplayName("the message is the lowercase literal of :L363 and never echoes the date of birth")
        void theMessageIsTheLowercaseLiteralAndNeverEchoesTheValue() {
            // app/cpy/CSUTLDPY.cpy:L361-L365 strings the trimmed variable name onto
            // ':cannot be in the future ' - lowercase c, and a trailing space that the seventy five
            // character field absorbs into its own padding. Under clause D a date of birth is direct
            // personally identifiable information, so the assertion checks that the rendered message
            // carries no part of the supplied value.
            final EditOutcome outcome = service.editDateOfBirth(TOMORROW_ON_THE_CANONICAL_CLOCK,
                    "Date of Birth");

            assertThat(outcome.returnMessage())
                    .as("the :L363 literal, lowercase")
                    .contains(":cannot be in the future");
            assertThat(outcome.returnMessage())
                    .as("and never the capitalised form used by the combination messages")
                    .doesNotContain(":Cannot be in the future");
            assertThat(outcome.returnMessage())
                    .as("the trimmed variable name prefixes it, per FUNCTION TRIM at :L362")
                    .startsWith("Date of Birth:");
            assertThat(outcome.returnMessage())
                    .as("no digit of the supplied date of birth may appear in the message")
                    .doesNotContain("2022").doesNotContain("0611");
            assertThat(outcome.returnMessage())
                    .as("the field is WS-RETURN-MSG, space padded to its declared width")
                    .hasSize(DateValidationService.RETURN_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the check is a pure add-on: a composite failure short-circuits before it runs")
        void theCheckIsAPureAddOnAfterTheCompositeEdit() {
            // EDIT-DATE-OF-BIRTH performs no flag initialisation whatsoever - it
            // presupposes the main chain already ran, which is why the caller at
            // app/cbl/COACTUPC.cbl gates it behind WS-EDIT-DT-OF-BIRTH-ISVALID. A composite failure
            // must therefore keep the composite message and never reach the future check.
            final EditOutcome compositeFailure = service.editDateOfBirth("20221310", "Date of Birth");

            assertThat(compositeFailure.valid()).isFalse();
            assertThat(compositeFailure.returnMessage())
                    .as("the month message from the composite edit survives")
                    .contains("Month must be a number between 1 and 12");
            assertThat(compositeFailure.returnMessage())
                    .as("and the future check never contributed its own")
                    .doesNotContain("cannot be in the future");
            assertThat(compositeFailure.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(compositeFailure.yearFlag())
                    .as("the year is untouched, which a wholesale initialisation would have reset")
                    .isEqualTo(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("EDIT-DATE-OF-BIRTH touches no flag before its own comparison has been made")
        void theParagraphInitialisesNoFlagAtAll() {
            // Structural, because it is unobservable: every reachable call has already
            // had its flags set to ISVALID by app/cpy/CSUTLDPY.cpy:L327. The absence of an
            // initialisation is nonetheless a real property of the paragraph, and adding one - by
            // analogy with :L27, :L92 or :L152 - would change what a future caller observes.
            final String body = bodyOf("editDateOfBirthParagraph");

            assertThat(body.indexOf("currentDateBinary >"))
                    .as("the :L350 comparison precedes every flag assignment")
                    .isGreaterThan(0)
                    .isLessThan(body.indexOf("EditFlag."));
        }
    }

    @Nested
    @DisplayName("8. Outcomes are RETURN VALUES: hostile input is answered, never thrown at")
    class OutcomesAreReturnValues {

        @ParameterizedTest(name = "[{index}] validate answers a hostile date rather than throwing: {0}")
        @CsvSource(nullValues = "NULL", value = {
            "NULL, YYYYMMDD",
            "'', YYYYMMDD",
            "'        ', YYYYMMDD",
            "'0000-00-00', YYYY-MM-DD",
            "'YYYY-MM-DD', YYYY-MM-DD",
            "'20-2206-10', YYYY-MM-DD",
            "'2022X610', YYYYMMDD",
            "20220610, NULL",
            "20220610, 'DD/MM/YYYY'",
        })
        @DisplayName("validate refuses hostile input by RETURNING a severity, never by throwing")
        void validateAnswersHostileInputWithASeverity(final String date, final String mask) {
            // Rule 1 clause A: inputs are untrusted. The utility has no exception mechanism at all -
            // app/cbl/CSUTLDTC.cbl:L97-L98 always fills the eighty byte block and always sets
            // RETURN-CODE, so every rejection is a value the caller reads.
            final DateValidationResult result = service.validate(date, mask);

            assertThat(result.valid()).as("every one of these is a rejection").isFalse();
            assertThat(result.returnCode())
                    .as("and the severity reached the process return code, per :L98")
                    .isEqualTo(3);
            assertThat(result.severityCode())
                    .as("the four byte severity the caller compares against '0000'")
                    .isEqualTo("0003");
        }

        @ParameterizedTest(name = "[{index}] editDate answers a hostile value rather than throwing: {0}")
        @ValueSource(strings = {"", "        ", "0000-00-00", "YYYYMMDD", "2022061", "20X20610",
            "20-20610", "18220610", "21220610", "20220010", "20221310", "20220100", "20220132"})
        @DisplayName("editDate refuses hostile input by RETURNING flags and a message, never by throwing")
        void editDateAnswersHostileInputWithFlags(final String candidate) {
            final EditOutcome outcome = service.editDate(candidate, "Date");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.inputError()).as("INPUT-ERROR is the source's only failure signal").isTrue();
            assertThat(outcome.hasReturnMessage()).as("and exactly one message was emitted").isTrue();
        }

        @Test
        @DisplayName("a null date is absorbed into a blank field, exactly as a MOVE to PIC X(8) would")
        void aNullDateIsAbsorbedIntoABlankField() {
            // A COBOL field cannot be null; the closest analogue of an absent value is a blank one, so
            // the bean pads rather than throws. The three entry points all agree on that.
            assertThatCode(() -> service.editDate(null, "Date")).doesNotThrowAnyException();
            assertThatCode(() -> service.editDateOfBirth(null, "Date of Birth"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.editDate("20220610", null)).doesNotThrowAnyException();

            final EditOutcome outcome = service.editDate(null, "Date");
            assertThat(outcome.yearFlag()).as("a blank field is 'not supplied', not 'wrong'")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage().trim()).isEqualTo("Date : Year must be supplied.");
        }

        @Test
        @DisplayName("a nine-character value is truncated to eight, and an eleven-character one is accepted")
        void surplusCharactersAreAbsorbedRatherThanRejected() {
            // Documented rather than corrected. The copybook path moves its input into
            // PIC X(08), so a ninth character is simply lost; the utility path scans component digit
            // runs rather than fixed positions, so a value one character short or one character long
            // still parses. Both are legacy behaviour and both are preserved.
            assertThat(service.editDate("202206100", "Date").valid())
                    .as("the ninth character never reaches the edit").isTrue();
            assertThat(service.validate("2022-06-1", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("nine characters under the ten character mask still parse").isTrue();
            assertThat(service.validate("2022-06-100", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("eleven characters under the ten character mask still parse").isTrue();
        }

        @Test
        @DisplayName("the eight-character copybook mask and the ten-character program mask are NOT interchangeable")
        void theTwoMasksAreNotInterchangeable() {
            // app/cpy/CSUTLDPY.cpy:L291 moves 'YYYYMMDD' - eight characters, no
            // separators - before the CALL at :L293, whereas the four program call sites pass
            // 'YYYY-MM-DD' PIC X(10) from app/cbl/CORPT00C.cbl:L72 and app/cbl/COTRN02C.cbl:L60.
            // Conflating the two changes what CEEDAYS is asked to parse, and each mask rejects the
            // other's date shape as non numeric data.
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("the copybook mask, :L291").hasSize(8).doesNotContain("-");
            assertThat(DateValidationService.MASK_YYYY_MM_DD)
                    .as("the program mask, CORPT00C.cbl:L72").hasSize(10).contains("-");

            assertThat(service.validate("20220610", DateValidationService.MASK_YYYYMMDD).valid())
                    .as("each mask accepts its own shape").isTrue();
            assertThat(service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .isTrue();
            assertThat(service.validate("2022-06-10", DateValidationService.MASK_YYYYMMDD).valid())
                    .as("and refuses the other's").isFalse();
            assertThat(service.validate("20220610", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .isFalse();
        }

        @Test
        @DisplayName("the copybook layer calls the utility with the eight-character mask, per :L291")
        void theCopybookLayerCallsTheUtilityWithTheEightCharacterMask() {
            // The two layers meet inside EDIT-DATE-LE, so the mask that layer chooses is a structural
            // property of that paragraph rather than something a caller can select.
            final String body = bodyOf("editDateLe");

            assertThat(body).as("the :L291 MOVE 'YYYYMMDD'").contains("MASK_YYYYMMDD");
            assertThat(body).as("and not the program mask").doesNotContain("MASK_YYYY_MM_DD");
        }

        @Test
        @DisplayName("the rendered block renders the mask cleanly while the date portion is corrupted")
        void theRenderedBlockCorruptsTheDateButNotTheMask() {
            // A preserved legacy defect: app/cbl/CSUTLDTC.cbl:L122 moves the whole
            // WS-DATE-TO-TEST group - a two byte binary length prefix followed by the text - into
            // WS-DATE PIC X(10), so the two prefix bytes displace the last two characters of the date.
            // WS-DATE-FMT is never moved over a second time, so the mask survives intact. This
            // asserts the asymmetry itself; the byte offsets of the block belong to
            // com.cardemo.unit.validation.DateValidationServiceTest.ResultAreaGeometry, not here.
            final DateValidationResult tenCharacterPath = service.validate("2022-06-10",
                    DateValidationService.MASK_YYYY_MM_DD);

            assertThat(tenCharacterPath.result())
                    .as("the mask renders cleanly: it was never re-moved")
                    .contains(DateValidationService.MASK_YYYY_MM_DD);
            assertThat(tenCharacterPath.result())
                    .as("but the date does not survive the :L122 group move")
                    .doesNotContain("2022-06-10");
            assertThat(tenCharacterPath.valid())
                    .as("and the corruption is cosmetic: the verdict is unaffected").isTrue();

            assertThat(service.validate("20220610", DateValidationService.MASK_YYYYMMDD).result())
                    .as("the eight character mask also renders cleanly")
                    .contains(DateValidationService.MASK_YYYYMMDD);
        }

        @Test
        @DisplayName("an invalid date is answered with a feedback code, and no exception type is involved")
        void anInvalidDateIsAnsweredWithAFeedbackCode() {
            // The whole of app/cbl/CSUTLDTC.cbl:L128-L149 is an EVALUATE that selects a result text
            // and falls through to WHEN OTHER at :L147; there is no path that terminates the run. The
            // Java surface must be the same shape, or callers would have to catch where the source
            // asked them to compare.
            assertThatCode(() -> service.validate("20220230", DateValidationService.MASK_YYYYMMDD))
                    .doesNotThrowAnyException();

            final DateValidationResult result = service.validate("20220230",
                    DateValidationService.MASK_YYYYMMDD);

            assertThat(result.feedbackCode().condition())
                    .as("a recognised feedback condition was selected")
                    .contains(FeedbackCondition.FC_BAD_DATE_VALUE);
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("the one declared error mode preserves its root cause and is unreachable by design")
        void theOneDeclaredErrorModePreservesItsRootCause() {
            // Rule 1 clause B forbids swallowing a cause. The bean has exactly one wrapping site: the
            // conversion guard inside EDIT-DATE-OF-BIRTH, which would fire only if the composite edit
            // accepted a date that java.time cannot construct. The Gregorian sweep in section 6 proves
            // that cannot happen, so the guard is asserted structurally: it names its field, states its
            // locator, and passes the originating cause through rather than discarding it.
            final String body = bodyOf("editDateOfBirthParagraph");

            assertThat(body).as("the guard catches the conversion failure")
                    .contains("catch (final DateTimeException cause)");
            assertThat(body).as("and rethrows as a validation failure").contains("ValidationException");
            assertThat(body).as("carrying the root cause, never discarding it").contains(", cause)");
            assertThat(body).as("and citing the source statement it guards").contains(":L345-L346");
            assertThat(javadocPreceding("editDateOfBirthParagraph"))
                    .as("the error mode is documented on the method, per clause B")
                    .contains("@throws");
        }

        @Test
        @DisplayName("that one error mode is UNCHECKED, so no caller is forced to catch where COBOL compared")
        void theOneDeclaredErrorModeIsUnchecked() {
            // The return value discipline would be undone by a checked exception: a caller of the
            // legacy utility compared four bytes and moved on, and the Java surface must not oblige it
            // to write a try block instead. The bean's single declared failure is therefore a
            // ValidationException, which reaches RuntimeException through CardDemoException.
            assertThat(CardDemoException.class)
                    .as("the project's base failure type is unchecked")
                    .isAssignableTo(RuntimeException.class);
            assertThat(ValidationException.class)
                    .as("and the bean's declared error mode extends it, so it is unchecked too")
                    .isAssignableTo(CardDemoException.class);
            assertThat(ValidationException.class.getPackageName())
                    .as("it is the shared exception hierarchy, not a bean local type")
                    .isEqualTo("com.cardemo.exception");
        }

        @Test
        @DisplayName("repeated calls with identical arguments return equal outcomes: no state is carried")
        void repeatedCallsReturnEqualOutcomes() {
            // Both public entry points are pure with respect to the bean. Every flag, the error
            // indicator and the message live in a per call work area, which is what makes the first
            // error wins latch reopen on the next call rather than staying closed.
            final EditOutcome firstEdit = service.editDate("20221310", "Date");
            final EditOutcome secondEdit = service.editDate("20221310", "Date");
            final DateValidationResult firstValidate = service.validate("20221310",
                    DateValidationService.MASK_YYYYMMDD);
            final DateValidationResult secondValidate = service.validate("20221310",
                    DateValidationService.MASK_YYYYMMDD);

            assertThat(secondEdit).isEqualTo(firstEdit);
            assertThat(secondValidate).isEqualTo(firstValidate);
            assertThat(service.editDate("20220610", "Date").valid())
                    .as("and a preceding failure does not poison the next call").isTrue();
        }
    }
}
