/*
 * ******************************************************************
 * Program     : MainMenuServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Behavioural parity harness for the main menu. Pins the
 *               option-table bound to the COUNT field rather than the
 *               REDEFINES arity, the JUST RIGHT plus space-to-zero
 *               normalisation, the two byte-exact refusal literals, the
 *               five-character DUMMY guard, the defective coming-soon
 *               concatenation, and the one-to-one paragraph map.
 * Source      : app/cbl/COMEN01C.cbl (282 lines, 7 paragraphs)
 *               app/cpy/COMEN02Y.cpy (the ten-slot option table)
 *               app/cpy/COCOM01Y.cpy (CDEMO-USER-TYPE identity)
 *               frozen at commit 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.MainMenuService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Parity harness for {@link MainMenuService}, the Java replacement for {@code app/cbl/COMEN01C.cbl}.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus. All of them
 * are keyed to the traceability anchor commit {@code 7756d89}, stated once here rather than repeated on
 * each citation.</p>
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds the migrated main menu to the observable behaviour of the CICS program it replaces, and
 * it is written so that each of the four legacy hazards below makes it fail rather than pass quietly.
 * Those hazards, not the happy path, are the reason this class exists.</p>
 *
 * <ul>
 *   <li><strong>Blocker - the bound is the count field, never the {@code REDEFINES} arity.</strong>
 *       {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21} governs a
 *       table whose literal data at {@code :23} onward populates ten 46-byte slots, 460 bytes, while
 *       the redefinition at {@code :87-92} declares {@code OCCURS 12 TIMES} over the same storage, 552
 *       bytes. Subscripts 11 and 12 therefore address 92 bytes of neighbouring
 *       {@code WORKING-STORAGE}, and what they hold is <strong>not available</strong>. The source's own
 *       display loop bounds on the count field, not the arity - {@code :236} loops
 *       {@code UNTIL WS-IDX &gt; CDEMO-MENU-OPT-COUNT} with the bound expression at {@code :239} - and
 *       the {@code WHEN 11} and {@code WHEN 12} limbs of that loop's {@code EVALUATE} are consequently
 *       unreachable. Group 1 pins this.</li>
 *   <li><strong>High - {@code SEND-MENU-SCREEN} has no {@code EXEC CICS RETURN}.</strong> The paragraph
 *       begins at {@code app/cbl/COMEN01C.cbl:182} and ends with {@code EXEC CICS SEND MAP}; the only
 *       {@code EXEC CICS RETURN} in all 282 lines is at {@code :107}. So when the bounds check at
 *       {@code :127-131} fails and sends the error screen, control <em>falls through</em> to the
 *       eligibility gate at {@code :136-140}, which subscripts
 *       {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} with the value that just failed. The dispatch and the
 *       coming-soon block are guarded by {@code IF NOT ERR-FLG-ON} at {@code :145}; the gate is not.
 *       That is the whole defect. Java is memory safe, so the overread cannot be reproduced: the
 *       refusal is raised <em>before</em> any subscript. Group 4 makes that
 *       <strong>labelled deviation</strong> observable instead of silent.</li>
 *   <li><strong>High - the coming-soon block sits outside the {@code DUMMY} guard.</strong> The guard at
 *       {@code app/cbl/COMEN01C.cbl:146} closes before the notice is assembled, so on a successful
 *       dispatch the notice is unreached only because {@code EXEC CICS XCTL} at {@code :153} never
 *       returns. A Java call does return, so the transfer must be modelled as a terminal one; a naive
 *       transliteration answers "coming soon" for every valid selection. Group 5 pins this.</li>
 *   <li><strong>Medium - two byte-exact literals.</strong>
 *       {@code 'Please enter a valid option number...'} at {@code :131}, and
 *       {@code 'No access - Admin Only option... '} at {@code :140} whose <strong>trailing space is
 *       inside the quotes</strong>. Group 3 asserts both to the byte. Equally byte-exact is the
 *       defective {@code STRING} at {@code :159-163}, whose middle operand is transferred
 *       {@code DELIMITED BY SPACE} and therefore truncates the caption at its first space while
 *       emitting no separator, producing {@code This option Accountis coming soon ...}. It is
 *       reproduced, not repaired.</li>
 * </ul>
 *
 * <p>Also pinned: the normalisation chain of {@code :117-125} through
 * {@code WS-OPTION-X PIC X(02) JUST RIGHT} at {@code :45} and {@code WS-OPTION PIC 9(02)} at
 * {@code :46}; the identity contract of {@code CDEMO-USER-TYPE PIC X(01)} at
 * {@code app/cpy/COCOM01Y.cpy:26} with its {@code 88} levels at {@code :27-28}; and the one-to-one
 * correspondence between the seven paragraph labels and seven private methods (group 7).</p>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <pre>
 * ./mvnw -B test                                            # whole unit tier
 * ./mvnw -B test -Dtest=MainMenuServiceTest                 # this class only
 * ./mvnw -B verify                                          # adds the JaCoCo line gate
 * </pre>
 *
 * <p>This class is bound by <strong>Surefire</strong> 3.5.4, whose include set is
 * {@code **}{@code /*Test.java} minus the {@code integration} and {@code e2e} trees. It is a pure-JVM
 * test: no container, no Spring context, no database, no queue and no network, so it needs nothing
 * running. Where a JDK is absent from the host the identical build runs in the pinned image:</p>
 *
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B test
 * </pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p><strong>Nothing here is tunable, and that is the point.</strong> The option count is a
 * <em>parity contract</em> fixed at ten by {@code app/cpy/COMEN02Y.cpy:21}, not a setting; a build that
 * wants eleven options has changed behaviour, and this class is meant to fail. Every expected literal
 * below is declared as a constant transcribed from the corpus and is never derived from the production
 * constant it checks, because a test that reads its expectation from the code under test proves only
 * self-consistency.</p>
 *
 * <p>No property, profile or classpath resource is read. <strong>No mock is created</strong>: the bean
 * under test has no collaborator to stub - the source program's whole {@code EXEC CICS} verb inventory
 * is {@code RETURN}, {@code XCTL}, {@code SEND} and {@code RECEIVE}, with no file access at all - so
 * Mockito's strict-stubs default has nothing to relax and the tier's strictness is left untouched.</p>
 *
 * <p>Two mechanisms need explanation. First, the bean is built through its package-private
 * {@code (Clock, List)} seam by reflection, because this class sits in
 * {@code com.cardemo.unit.service} where the build places the unit tier while the seam is declared in
 * {@code com.cardemo.service.menu}; {@link MenuServiceTestSupport} owns that bridge so it is written
 * once. The seam is needed only for the two retained guards that the frozen table cannot trigger, and
 * for a fixed clock. Second, {@code POPULATE-HEADER-INFO} returns nothing and publishes nothing, so its
 * only observable is a {@code DEBUG} statement; a {@link ListAppender} is attached before each test and
 * group 8 reads the rendered furniture from it, rather than asking production code to grow a test-only
 * accessor.</p>
 *
 * <p>Determinism is absolute: the clock is always
 * {@link Clock#fixed(Instant, java.time.ZoneId)} over an explicit instant and an explicit
 * {@link ZoneOffset}, never an ambient {@code now()}, and never a named zone whose rules could shift
 * with a time-zone data update.</p>
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>What a failure in this class is telling you</caption>
 *   <tr><th>Symptom</th><th>Diagnosis</th></tr>
 *   <tr>
 *     <td>Group 1 fails on the option count or on subscript 11 or 12</td>
 *     <td>The implementation bounded on {@code OCCURS 12} instead of {@code VALUE 10}. That is the
 *         Blocker: it exposes the 92-byte unpopulated region as if it held options.</td>
 *   </tr>
 *   <tr>
 *     <td>Group 4 reports an {@code IndexOutOfBoundsException}, or the administrator-only literal for
 *         an out-of-range option</td>
 *     <td>The bounds failure no longer short-circuits, so control now reaches the gate subscript
 *         exactly as {@code :136-137} does. Restore the refusal ahead of the table access.</td>
 *   </tr>
 *   <tr>
 *     <td>Group 5 finds a coming-soon notice on a valid selection</td>
 *     <td>{@code EXEC CICS XCTL} is no longer modelled as a terminal transfer, so the block at
 *         {@code :157-164} is now reached on the success path.</td>
 *   </tr>
 *   <tr>
 *     <td>Group 5 finds {@code This option Account is coming soon ...} with a space</td>
 *     <td>The defective {@code STRING} of {@code :159-163} was "fixed". The missing space and the
 *         truncation at the first space are both behaviour; parity forbids repairing them.</td>
 *   </tr>
 *   <tr>
 *     <td>Group 3 fails on the administrator-only literal by one character</td>
 *     <td>The trailing space inside the {@code :140} literal was trimmed.</td>
 *   </tr>
 *   <tr>
 *     <td>Group 7 cannot find a paragraph method</td>
 *     <td>A paragraph was renamed, consolidated with another or deleted. The seven labels map one to
 *         one and the coverage gate reads that map.</td>
 *   </tr>
 *   <tr>
 *     <td>Compilation fails with no test having run</td>
 *     <td>{@code -Xlint:all -Werror} with {@code failOnWarning} is fatal for the test tree too. A
 *         single unused import, or a {@code /**} comment not attached to a declaration, ends the
 *         build.</td>
 *   </tr>
 *   <tr>
 *     <td>The report says {@code Tests run: 0} for this class</td>
 *     <td>Not a failure. Surefire leaves the summary attribute at zero for a class whose tests all
 *         live in {@code @Nested} groups; the real cases are the {@code testcase} elements of
 *         {@code target/surefire-reports/TEST-com.cardemo.unit.service.MainMenuServiceTest.xml}.</td>
 *   </tr>
 * </table>
 *
 * @see MainMenuService
 * @see MenuServiceTestSupport
 */
@DisplayName("MainMenuService: behavioural parity with app/cbl/COMEN01C.cbl and app/cpy/COMEN02Y.cpy")
final class MainMenuServiceTest {

    // ------------------------------------------------------------------
    // Literals transcribed from the corpus. None is imported from the
    // production class: an expectation taken from the code under test
    // would prove only that the code agrees with itself.
    // ------------------------------------------------------------------

    /** {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}. */
    private static final int POPULATED_OPTION_COUNT = 10;

    /** {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88}. */
    private static final int DECLARED_OCCURS_CAPACITY = 12;

    /** The 46-byte slot of {@code app/cpy/COMEN02Y.cpy:89-92}: 2 plus 35 plus 8 plus 1. */
    private static final int SLOT_BYTES = 46;

    /** {@code CDEMO-MENU-OPT-NAME PIC X(35)} at {@code app/cpy/COMEN02Y.cpy:90}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} at {@code app/cpy/COMEN02Y.cpy:91}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code OPTIONI PIC X(2)} of the {@code COMEN01} symbolic map, and {@code WS-OPTION-X} at {@code :45}. */
    private static final int OPTION_INPUT_WIDTH = 2;

    /** {@code WS-MENU-OPT-TXT PIC X(40)} at {@code app/cbl/COMEN01C.cbl:48}. */
    private static final int SCREEN_SLOT_WIDTH = 40;

    /** The literal moved to {@code WS-MESSAGE} at {@code app/cbl/COMEN01C.cbl:131}. */
    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * The literal moved to {@code WS-MESSAGE} at {@code app/cbl/COMEN01C.cbl:140}.
     *
     * <p><strong>The trailing space is inside the COBOL quotes and is part of the literal.</strong> It
     * is not decoration and must not be trimmed.</p>
     */
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /** The exact text the defective {@code STRING} of {@code :159-163} produces for {@code Account View}. */
    private static final String DEFECTIVE_NOTICE_FOR_ACCOUNT_VIEW =
            "This option Accountis coming soon ...";

    /** {@code 'DUMMY'}, the five characters {@code app/cbl/COMEN01C.cbl:146} compares with {@code (1:5)}. */
    private static final String PLACEHOLDER_PREFIX = "DUMMY";

    /** {@code 'COSGN00C'} at {@code app/cbl/COMEN01C.cbl:173}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The field name every refusal names, matching {@code OPTIONI} of the symbolic map in lower case. */
    private static final String OPTION_FIELD_NAME = "option";

    /**
     * The prefix of the statement {@code PROCESS-ENTER-KEY} logs once a selection has resolved, which is
     * the observable proxy for having reached {@code app/cbl/COMEN01C.cbl:152-164} at all.
     */
    private static final String SELECTION_MESSAGE_PREFIX = "Main menu option ";

    /**
     * The word that distinguishes the notice branch of {@code app/cbl/COMEN01C.cbl:157-164} from the
     * terminal dispatch of {@code :152-155} in the logged statement.
     */
    private static final String PLACEHOLDER_MESSAGE_MARKER = "placeholder";

    /** {@code WS-TRANID PIC X(04) VALUE 'CM00'} at {@code app/cbl/COMEN01C.cbl:37}. */
    private static final String TRANSACTION_ID = "CM00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} at {@code app/cbl/COMEN01C.cbl:36}. */
    private static final String PROGRAM_NAME = "COMEN01C";

    /** Index of the rendered date in the header statement's argument array. */
    private static final int DATE_ARGUMENT_INDEX = 4;

    /** Index of the rendered time in the header statement's argument array. */
    private static final int TIME_ARGUMENT_INDEX = 5;

    /** The instant every fixed clock in this class is built on. Explicit, so nothing is ambient. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * A synthetic administrator-only entry.
     *
     * <p>Needed because <strong>no populated slot of {@code app/cpy/COMEN02Y.cpy} carries
     * {@code 'A'}</strong>. The caption is the literal that {@code :69} declares and then comments out -
     * the label slot 8 bore while it really was administrator-only - so even this fixture is
     * corpus-derived rather than invented. It exists only inside this test class and is never added to
     * the production table.</p>
     */
    private static final MenuResponse.MainMenuOption SYNTHETIC_ADMIN_ONLY_OPTION =
            new MenuResponse.MainMenuOption(1, "Transaction Add (Admin Only)       ", "COTRN02C", 'A');

    /**
     * A synthetic placeholder entry, needed because no populated slot targets a {@code DUMMY} program.
     * The caption is slot one's own {@code COMEN02Y} literal, so its truncation at the first space is
     * corpus-derived.
     */
    private static final MenuResponse.MainMenuOption SYNTHETIC_PLACEHOLDER_OPTION =
            new MenuResponse.MainMenuOption(1, "Account View                       ", "DUMMY01C", 'U');

    /** A single ordinary entry, for exercising the seam with a table that refuses nothing. */
    private static final MenuResponse.MainMenuOption SYNTHETIC_ORDINARY_OPTION =
            new MenuResponse.MainMenuOption(1, "Account View                       ", "COACTVWC", 'U');

    /** Captures the production {@code DEBUG} statements without altering production code. */
    private ListAppender<ILoggingEvent> appender;

    /** The service logger, restored to its configured level after each test. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level the logger carried before the test raised it. */
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        this.serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MainMenuService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.appender = new ListAppender<>();
        this.appender.start();
        this.serviceLogger.addAppender(this.appender);
        this.serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        this.serviceLogger.detachAppender(this.appender);
        this.serviceLogger.setLevel(this.originalLevel);
        this.appender.stop();
    }

    // ==================================================================
    // 1 - BLOCKER. The bound is the COUNT field, never the OCCURS arity.
    // ==================================================================

    /**
     * The {@code REDEFINES} over-arity of {@code app/cpy/COMEN02Y.cpy:87-92}.
     *
     * <p>{@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code :21} declares ten populated slots,
     * whose literal data runs from {@code :23}. The redefinition at {@code :87} lays
     * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code :88} over that same storage, and each slot is 46
     * bytes: {@code PIC 9(02)} plus {@code PIC X(35)} plus {@code PIC X(08)} plus {@code PIC X(01)} at
     * {@code :89-92}. Ten slots occupy 460 bytes and twelve address 552, so subscripts 11 and 12 reach
     * 92 bytes of neighbouring {@code WORKING-STORAGE} whose content is <strong>not
     * available</strong>.</p>
     *
     * <p>The source itself bounds on the count field: {@code BUILD-MENU-OPTIONS} at {@code :236} loops
     * {@code UNTIL WS-IDX &gt; CDEMO-MENU-OPT-COUNT}, the bound expression standing at {@code :239}. The
     * corroborating evidence is that the loop's own {@code EVALUATE} carries {@code WHEN 11} and
     * {@code WHEN 12} limbs at {@code :269-272} which that bound can never reach - dead limbs written
     * against the arity, proving the arity was never the bound.</p>
     */
    @Nested
    @DisplayName("1. Blocker: the option count comes from CDEMO-MENU-OPT-COUNT, not from OCCURS 12")
    class TheBoundIsTheCountFieldNotTheOccursArity {

        @Test
        @DisplayName("the menu offers exactly the ten options the count field declares")
        void theMenuOffersExactlyTenOptions() {
            final MainMenuService.MainMenuScreen screen = productionService().getMainMenu(UserType.ADMIN);

            assertThat(screen.menu().getOptionCount())
                    .as("app/cpy/COMEN02Y.cpy:21 declares CDEMO-MENU-OPT-COUNT VALUE 10 and the literal "
                            + "data from :23 populates exactly that many slots")
                    .isEqualTo(POPULATED_OPTION_COUNT);
            assertThat(screen.menu().getOptions()).hasSize(POPULATED_OPTION_COUNT);
            assertThat(screen.optionLabels())
                    .as("app/cbl/COMEN01C.cbl:238-239 renders one line per option up to the count, so the "
                            + "line count and the option count cannot diverge")
                    .hasSize(POPULATED_OPTION_COUNT);
        }

        @Test
        @DisplayName("the count field and the OCCURS arity are different numbers, and the count is the bound")
        void theCountFieldIsTheBoundAndTheArityIsNot() {
            assertThat(MenuResponse.MenuType.MAIN.getPopulatedOptionCount())
                    .as("the transcription of CDEMO-MENU-OPT-COUNT at app/cpy/COMEN02Y.cpy:21")
                    .isEqualTo(POPULATED_OPTION_COUNT);
            assertThat(MenuResponse.MenuType.MAIN.getDeclaredCapacity())
                    .as("the transcription of OCCURS 12 TIMES at app/cpy/COMEN02Y.cpy:88")
                    .isEqualTo(DECLARED_OCCURS_CAPACITY);
            assertThat(MenuResponse.MenuType.MAIN.getDeclaredCapacity())
                    .as("the two must differ, or the over-arity this group guards against would not exist")
                    .isGreaterThan(MenuResponse.MenuType.MAIN.getPopulatedOptionCount());

            final int declaredBytes = POPULATED_OPTION_COUNT * SLOT_BYTES;
            final int redefinedBytes = DECLARED_OCCURS_CAPACITY * SLOT_BYTES;
            assertThat(declaredBytes).as("ten 46-byte slots").isEqualTo(460);
            assertThat(redefinedBytes).as("twelve 46-byte slots").isEqualTo(552);
            assertThat(redefinedBytes - declaredBytes)
                    .as("the unpopulated region the spare subscripts would read, in bytes")
                    .isEqualTo(92);
        }

        @Test
        @DisplayName("the offered count is read from the count field, never from the OCCURS capacity")
        void theOfferedCountFollowsTheCountFieldNotTheCapacity() {
            final int offered = productionService().getMainMenu(UserType.ADMIN).menu().getOptionCount();

            assertThat(offered)
                    .as("were the arity used as the bound, twelve options would be offered and the two "
                            + "spare subscripts would surface as if they held data")
                    .isEqualTo(MenuResponse.MenuType.MAIN.getPopulatedOptionCount())
                    .isNotEqualTo(MenuResponse.MenuType.MAIN.getDeclaredCapacity());
        }

        @ParameterizedTest(name = "subscript {0} addresses the unpopulated region and is refused")
        @ValueSource(strings = {"11", "12"})
        @DisplayName("subscripts 11 and 12 are refused as out of range, never resolved from spare storage")
        void theSpareSubscriptsAreRefused(final String spareSubscript) {
            final ValidationException refusal =
                    catchValidation(() -> productionService().selectOption(spareSubscript, UserType.ADMIN));

            assertThat(refusal.getMessage())
                    .as("app/cbl/COMEN01C.cbl:128 tests WS-OPTION > CDEMO-MENU-OPT-COUNT, so a subscript "
                            + "inside the OCCURS capacity but beyond the count is still unusable and "
                            + "reports the literal of :131")
                    .isEqualTo(INVALID_OPTION_MESSAGE);
            assertThat(refusal.getFieldName()).isEqualTo(OPTION_FIELD_NAME);
        }

        @Test
        @DisplayName("no populated slot carries a spare subscript number, so nothing resolves them")
        void noPopulatedSlotCarriesASpareSubscriptNumber() {
            final List<Integer> numbers = new ArrayList<>();
            for (final MenuResponse.MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                numbers.add(option.optionNumber());
            }

            assertThat(numbers)
                    .as("app/cpy/COMEN02Y.cpy:25-84 numbers its slots 1 through 10 in copybook order")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .doesNotContain(11, DECLARED_OCCURS_CAPACITY);
        }

        @Test
        @DisplayName("a table longer than the count is refused, and the refusal says why the arity is not a bound")
        void anOverLongTableIsRefused() {
            final List<MenuResponse.MainMenuOption> elevenOptions = new ArrayList<>();
            for (int number = 1; number <= POPULATED_OPTION_COUNT + 1; number++) {
                elevenOptions.add(new MenuResponse.MainMenuOption(
                        number, "Account View                       ", "COACTVWC", 'U'));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("eleven entries exceed what app/cpy/COMEN02Y.cpy:21 populates, and the twelve of "
                            + ":88 are not an alternative bound because their storage is unpopulated")
                    .isThrownBy(() -> serviceOver(elevenOptions))
                    .withMessageContaining(String.valueOf(POPULATED_OPTION_COUNT))
                    .withMessageContaining(String.valueOf(DECLARED_OCCURS_CAPACITY))
                    .withMessageContaining("not a valid bound");
        }
    }

    // ==================================================================
    // 2 - JUST RIGHT and the space-to-zero substitution.
    // ==================================================================

    /**
     * The normalisation chain of {@code app/cbl/COMEN01C.cbl:117-125}, in the source's own order.
     *
     * <p>{@code :117-121} scan {@code OPTIONI} backwards from its last byte until a non-space is found
     * or byte one is reached - the loop body is empty, its only purpose being to leave {@code WS-IDX} on
     * that byte. {@code :122} moves the prefix into {@code WS-OPTION-X PIC X(02) JUST RIGHT}, declared
     * at {@code :45}, which right-justifies it within two bytes. {@code :123} then rewrites every
     * remaining space with {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}, and {@code :124} moves
     * the result into {@code WS-OPTION PIC 9(02)}, declared at {@code :46}. {@code :125} echoes it back
     * to the screen field.</p>
     *
     * <p>So {@code "3"} right-justifies to {@code " 3"} and is rewritten to {@code "03"}, and an
     * untouched field of two spaces becomes {@code "00"} - which is precisely how the source handles an
     * absent value, and why no separate blank test exists ahead of the bounds check. A byte that is not
     * a digit survives the substitution untouched and is what the {@code IS NOT NUMERIC} limb of
     * {@code :127} then detects.</p>
     */
    @Nested
    @DisplayName("2. Normalisation: JUST RIGHT, then INSPECT REPLACING ALL ' ' BY '0'")
    class NormalisationRightJustifiesThenSubstitutesZero {

        @ParameterizedTest(name = "the field {0} normalises to option {1}")
        @CsvSource({
            "'3', 3",
            "' 3', 3",
            "'03', 3",
            "'1', 1",
            "'01', 1",
            "'10', 10",
        })
        @DisplayName("every representation of a usable option resolves to the same option number")
        void everyRepresentationResolvesToTheSameOption(final String field, final int expected) {
            final MainMenuService.MenuSelection selection =
                    productionService().selectOption(field, UserType.USER);

            assertThat(selection.optionNumber())
                    .as("app/cbl/COMEN01C.cbl:122-124 right-justifies the typed prefix within two bytes "
                            + "and substitutes zero for each remaining space, so %s and its padded and "
                            + "zero-filled forms are one and the same option", field)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a single character and its zero-filled form select the identical option and target")
        void aSingleCharacterAndItsZeroFilledFormAgreeCompletely() {
            final MainMenuService.MenuSelection terse = productionService().selectOption("3", UserType.USER);
            final MainMenuService.MenuSelection padded = productionService().selectOption(" 3", UserType.USER);
            final MainMenuService.MenuSelection filled = productionService().selectOption("03", UserType.USER);

            assertThat(terse)
                    .as("the three forms differ only in what INSPECT REPLACING at :123 rewrites, so the "
                            + "resolved selections must be indistinguishable, target program included")
                    .isEqualTo(padded)
                    .isEqualTo(filled);
            assertThat(terse.targetProgram())
                    .as("slot 3 of app/cpy/COMEN02Y.cpy:37-41 names COCRDLIC")
                    .isEqualTo("COCRDLIC");
        }

        @ParameterizedTest(name = "the blank field [{0}] normalises to zero and is refused")
        @ValueSource(strings = {"", " ", "  "})
        @DisplayName("an empty or all-blank field becomes option zero and is refused by the ZEROS limb")
        void anAllBlankFieldBecomesZeroAndIsRefused(final String blankField) {
            assertInvalidOption(() -> productionService().selectOption(blankField, UserType.USER));
        }

        @Test
        @DisplayName("an absent field is treated exactly as an untouched screen field, not as a special case")
        void anAbsentFieldIsTreatedAsAnUntouchedScreenField() {
            final ValidationException fromNull =
                    catchValidation(() -> productionService().selectOption(null, UserType.USER));
            final ValidationException fromBlanks =
                    catchValidation(() -> productionService().selectOption("  ", UserType.USER));

            assertThat(fromNull.getMessage())
                    .as("RECEIVE-MAP at app/cbl/COMEN01C.cbl:201-207 hands PROCESS-ENTER-KEY the input "
                            + "area whether or not the operator typed anything, so an absent value and an "
                            + "untouched field both normalise to 00 and report the same literal")
                    .isEqualTo(fromBlanks.getMessage())
                    .isEqualTo(INVALID_OPTION_MESSAGE);
            assertThat(fromNull.getFailureKind())
                    .as("the source has no separate blank marker here; both are simply unusable")
                    .isEqualTo(fromBlanks.getFailureKind());
        }

        @ParameterizedTest(name = "the non-numeric field {0} is refused by the IS NOT NUMERIC limb")
        @ValueSource(strings = {"A1", "1A", "AA", "-1", "+1", "1.", "*1", "1,"})
        @DisplayName("a byte that is not a digit survives the substitution and fails the numeric limb")
        void nonNumericContentFailsTheNumericLimb(final String field) {
            assertInvalidOption(() -> productionService().selectOption(field, UserType.USER));
        }

        @ParameterizedTest(name = "the control character in [{0}] is not a space and is not substituted")
        @ValueSource(strings = {"\t1", "\u00001", "\n1", "1\t", "\u000b1"})
        @DisplayName("only the space character is scanned over and substituted, never any other whitespace")
        void onlyTheSpaceCharacterIsSubstituted(final String field) {
            // INSPECT ... REPLACING ALL ' ' BY '0' at :123 names one character. A tab, a null and a
            // newline are none of them, so they survive into WS-OPTION and fail the numeric limb rather
            // than being quietly normalised to a digit.
            assertInvalidOption(() -> productionService().selectOption(field, UserType.USER));
        }

        @Test
        @DisplayName("a field wider than the two bytes the map declares is refused outright")
        void anOverWideFieldIsRefused() {
            // A 3270 field cannot deliver more bytes than OPTIONI PIC X(2) declares, so what the source
            // would do with a wider value is NOT AVAILABLE. Keeping only the first two characters would
            // accept "100" as option 10 and dispatch to bill payment - an outcome the source can never
            // produce - so the value is refused with the same literal every other unusable field gets.
            assertThat(OPTION_INPUT_WIDTH).as("OPTIONI PIC X(2), and WS-OPTION-X PIC X(02) at :45").isEqualTo(2);
            assertInvalidOption(() -> productionService().selectOption("100", UserType.USER));
            assertInvalidOption(() -> productionService().selectOption("010", UserType.USER));
            assertInvalidOption(() -> productionService().selectOption("1 2", UserType.USER));
        }
    }

    // ==================================================================
    // 3 - The bounds check and the two byte-exact refusal literals.
    // ==================================================================

    /**
     * The refusals of {@code app/cbl/COMEN01C.cbl:127-131} and {@code :136-140}.
     *
     * <p>The bounds check is a three-way disjunction in the source's order:
     * {@code WS-OPTION IS NOT NUMERIC}, then {@code WS-OPTION &gt; CDEMO-MENU-OPT-COUNT}, then
     * {@code WS-OPTION = ZEROS}. Any of the three moves {@code 'Y'} to {@code WS-ERR-FLG} at
     * {@code :130} and the literal of {@code :131} to {@code WS-MESSAGE}. All three report the same
     * text because the source reports the same text.</p>
     *
     * <p>The eligibility gate at {@code :136-140} is reached only for an in-range option here; see
     * group 4 for why. It moves the literal of {@code :140} to {@code WS-MESSAGE} after a redundant
     * {@code MOVE SPACES} at {@code :139} that the next statement immediately overwrites.</p>
     */
    @Nested
    @DisplayName("3. The refusal literals are byte exact, trailing space included")
    class TheRefusalLiteralsAreByteExact {

        @ParameterizedTest(name = "the unusable field {0} reports the invalid-option literal")
        @ValueSource(strings = {"0", "00", "11", "12", "99", "-1", "A1", "1A", "100", "", "  "})
        @DisplayName("zero, out of range, non-numeric, over-wide and blank all report one literal")
        void everyUnusableFieldReportsTheSameLiteral(final String field) {
            final ValidationException refusal =
                    catchValidation(() -> productionService().selectOption(field, UserType.USER));

            assertThat(refusal.getMessage())
                    .as("app/cbl/COMEN01C.cbl:131 is the only literal the bounds check moves to "
                            + "WS-MESSAGE, whichever of the three disjuncts of :127-129 fired")
                    .isEqualTo(INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("the invalid-option literal is 37 characters, three full stops and no trailing space")
        void theInvalidOptionLiteralIsByteExact() {
            final ValidationException refusal =
                    catchValidation(() -> productionService().selectOption("0", UserType.USER));

            assertThat(refusal.getMessage())
                    .isEqualTo("Please enter a valid option number...")
                    .hasSize(37)
                    .endsWith("...")
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("the admin-only literal keeps the trailing space that lives inside the COBOL quotes")
        void theAdminOnlyLiteralKeepsItsTrailingSpace() {
            final ValidationException refusal = catchValidation(() ->
                    serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION)).selectOption("1", UserType.USER));

            assertThat(refusal.getMessage())
                    .as("app/cbl/COMEN01C.cbl:140 reads 'No access - Admin Only option... ' and the space "
                            + "after the third full stop is INSIDE the quotes. Trimming it changes an "
                            + "operator-visible literal, so it is asserted to the byte")
                    .isEqualTo(ADMIN_ONLY_MESSAGE)
                    .isEqualTo("No access - Admin Only option... ")
                    .hasSize(33)
                    .endsWith("... ");
            assertThat(refusal.getMessage().charAt(32))
                    .as("the thirty-third character is the trailing space itself")
                    .isEqualTo(' ');
            assertThat(refusal.getMessage())
                    .as("and it must not have been trimmed away")
                    .isNotEqualTo(ADMIN_ONLY_MESSAGE.trim());
        }

        @Test
        @DisplayName("the two literals are distinct, so a refusal always says which rule refused it")
        void theTwoLiteralsAreDistinct() {
            assertThat(INVALID_OPTION_MESSAGE)
                    .as("app/cbl/COMEN01C.cbl:131 and :140 are different literals for different rules; "
                            + "collapsing them would lose what the operator was told")
                    .isNotEqualTo(ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("a refusal carries its type, its message, its field, its kind and no spurious cause")
        void aRefusalCarriesTypeMessageFieldKindAndCause() {
            final ValidationException refusal =
                    catchValidation(() -> productionService().selectOption("99", UserType.USER));

            assertThat(refusal)
                    .as("the typed exception hierarchy: every refusal is a CardDemoException and "
                            + "therefore unchecked, so no caller is forced to catch it")
                    .isInstanceOf(ValidationException.class)
                    .isInstanceOf(CardDemoException.class)
                    .isInstanceOf(RuntimeException.class);
            assertThat(refusal.getMessage()).isEqualTo(INVALID_OPTION_MESSAGE);
            assertThat(refusal.getFieldName())
                    .as("the refusal names the input it attaches to, so a caller can mark that field")
                    .isEqualTo(OPTION_FIELD_NAME);
            assertThat(refusal.hasFieldName()).isTrue();
            assertThat(refusal.getFailureKind())
                    .as("a value was supplied and it is wrong, which is INVALID rather than BLANK")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getCause())
                    .as("the refusal originates in the bounds check itself; there is no underlying "
                            + "throwable, so wrapping one in would fabricate a root cause")
                    .isNull();
        }

        @ParameterizedTest(name = "the refusal for {0} does not echo it back")
        @ValueSource(strings = {"99", "A1", "<script>", "' OR '1'='1", "../../etc/passwd", "%00"})
        @DisplayName("no refusal echoes the supplied value, so hostile input cannot be reflected")
        void noRefusalEchoesTheSuppliedValue(final String hostileField) {
            final ValidationException refusal =
                    catchValidation(() -> productionService().selectOption(hostileField, UserType.USER));

            assertThat(refusal.getMessage())
                    .as("the source moves a fixed literal to WS-MESSAGE at :131 and never interpolates "
                            + "the operator's keystrokes, so the message is constant and cannot carry a "
                            + "reflected payload back to a caller")
                    .isEqualTo(INVALID_OPTION_MESSAGE)
                    .doesNotContain(hostileField);
        }
    }

    // ==================================================================
    // 4 - HIGH. SEND-MENU-SCREEN has no EXEC CICS RETURN, so the source
    //     falls through into an unguarded subscript. LABELLED DEVIATION.
    // ==================================================================

    /**
     * The fall-through hazard of {@code app/cbl/COMEN01C.cbl:127-140}, and the deviation that answers it.
     *
     * <p><strong>The source has no early exit here.</strong> {@code SEND-MENU-SCREEN} begins at
     * {@code :182} and its body ends with {@code EXEC CICS SEND MAP('COMEN1A') ... ERASE}; it contains
     * no {@code EXEC CICS RETURN}. The <em>only</em> {@code EXEC CICS RETURN} in all 282 lines of the
     * program is at {@code :107}, in {@code MAIN-PARA}, long after {@code PROCESS-ENTER-KEY} has
     * finished. So when the bounds check at {@code :127-131} refuses an option and performs
     * {@code SEND-MENU-SCREEN} at {@code :133}, the send returns and control falls through {@code :134}
     * into the eligibility gate at {@code :136-140}, which evaluates
     * {@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION)} with the very value that just failed - zero, eleven,
     * twelve or a non-numeric byte - and therefore reads the unpopulated region described in group
     * 1.</p>
     *
     * <p><strong>The asymmetry is the defect.</strong> The dispatch at {@code :147-155} and the
     * coming-soon block at {@code :157-164} are both inside {@code IF NOT ERR-FLG-ON} at {@code :145},
     * so a refused option reaches neither. The gate at {@code :136} is <em>not</em> wrapped in that
     * condition. Its sibling program is structurally safe by comparison: the corresponding subscript in
     * {@code app/cbl/COADM01C.cbl:138} sits inside its own {@code IF NOT ERR-FLG-ON} at {@code :137}.</p>
     *
     * <p><strong>Labelled deviation.</strong> Java is memory safe: an out-of-range subscript throws
     * rather than returning adjacent storage, so the overread has <em>no defined Java semantics</em> and
     * cannot be reproduced. The implementation therefore refuses {@code option &gt; count} <em>before</em>
     * any table access. The observable outcome is unchanged, because in the source both paths perform
     * {@code SEND-MENU-SCREEN} - both redisplay the menu carrying a message - and the operator sees a
     * refusal either way. This deviation is recorded in {@code DECISION_LOG.md} under
     * <em>bounds short-circuit</em>, and the tests below make it <strong>observable rather than
     * silent</strong>: they prove the refusal is the bounds refusal and not the gate's, that no
     * {@code IndexOutOfBoundsException} escapes, and - by the control case - that the gate is still
     * genuinely reached for an option that is in range.</p>
     */
    @Nested
    @DisplayName("4. High: the bounds refusal short-circuits before the unguarded gate subscript")
    class TheBoundsRefusalShortCircuitsBeforeTheGate {

        @Test
        @DisplayName("an out-of-range option never reaches user-type evaluation")
        void anOutOfRangeOptionNeverReachesUserTypeEvaluation() {
            // The table holds ONE entry and it is administrator-only. A standard user naming option 2
            // is out of range. Had the fall-through of :134 into :136-137 been transliterated, this call
            // would either read past the end of the table or report the gate's own literal.
            final MainMenuService service = serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION));

            final ValidationException refusal =
                    catchValidation(() -> service.selectOption("2", UserType.USER));

            assertThat(refusal.getMessage())
                    .as("the bounds check of app/cbl/COMEN01C.cbl:127-131 refuses first, so the literal is "
                            + ":131's and NOT the gate's at :140. Seeing the admin-only literal here would "
                            + "mean the gate had been reached with an out-of-range subscript")
                    .isEqualTo(INVALID_OPTION_MESSAGE)
                    .isNotEqualTo(ADMIN_ONLY_MESSAGE);
        }

        @ParameterizedTest(name = "the out-of-range field {0} raises a refusal, never an index failure")
        @ValueSource(strings = {"0", "00", "11", "12", "99", "A1"})
        @DisplayName("no IndexOutOfBoundsException escapes for any value the bounds check refuses")
        void noIndexFailureEscapes(final String field) {
            final Throwable thrown =
                    catchValidation(() -> productionService().selectOption(field, UserType.USER));

            assertThat(thrown)
                    .as("Java cannot reproduce the source's read of the 92 unpopulated bytes; it would "
                            + "throw instead. The refusal must therefore be raised ahead of the subscript, "
                            + "so a caller sees a validation failure and never a memory-shape failure")
                    .isInstanceOf(ValidationException.class)
                    .isNotInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("the refusal for an out-of-range option is identical for both user classes")
        void theRefusalIsIndependentOfTheUserType() {
            final MainMenuService service = serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION));

            final ValidationException asUser = catchValidation(() -> service.selectOption("2", UserType.USER));
            final ValidationException asAdmin = catchValidation(() -> service.selectOption("2", UserType.ADMIN));

            assertThat(asUser.getMessage())
                    .as("the gate at :136 opens with IF CDEMO-USRTYP-USER, so an outcome that varied with "
                            + "the user class would prove the gate had been evaluated. It must not have "
                            + "been: the option never got that far")
                    .isEqualTo(asAdmin.getMessage())
                    .isEqualTo(INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("nothing is resolved or dispatched when the bounds check refuses")
        void nothingIsResolvedWhenTheBoundsCheckRefuses() {
            catchValidation(() -> productionService().selectOption("11", UserType.USER));

            assertThat(selectionEvents())
                    .as("the dispatch at :147-155 and the notice at :157-164 are both inside IF NOT "
                            + "ERR-FLG-ON at :145, so a refused option reaches neither and the service "
                            + "logs no resolved selection at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("an in-range option DOES reach the gate, so the short-circuit is bounded to what fails")
        void anInRangeOptionStillReachesTheGate() {
            // The control case. Without it, an implementation that refused everything would pass the
            // three tests above, and the retained gate of :136-140 would be unverifiable.
            final MainMenuService service = serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION));

            final ValidationException refusal =
                    catchValidation(() -> service.selectOption("1", UserType.USER));

            assertThat(refusal.getMessage())
                    .as("option 1 is within the count, so the subscript at :137 is safe and the gate is "
                            + "evaluated exactly as the source evaluates it")
                    .isEqualTo(ADMIN_ONLY_MESSAGE);
        }
    }

    // ==================================================================
    // 5 - HIGH. EXEC CICS XCTL is a terminal transfer, so the
    //     coming-soon block is unreachable on the success path.
    // ==================================================================

    /**
     * The placeholder guard of {@code app/cbl/COMEN01C.cbl:146} and the notice of {@code :157-164}.
     *
     * <p><strong>The notice sits outside the guard.</strong> The inner {@code END-IF} closes at
     * {@code :156}, so the assembly at {@code :157-163} and the {@code SEND} at {@code :164} follow the
     * dispatch rather than being an alternative to it. They are inside {@code IF NOT ERR-FLG-ON} at
     * {@code :145} but outside the {@code DUMMY} test. Under CICS they are unreached whenever a real
     * target resolves for one reason only: {@code EXEC CICS XCTL} at {@code :152-155} transfers control
     * and <em>never returns</em>. A Java call does return, so the transfer must be modelled as terminal;
     * an implementation that simply follows the source's statement order answers "coming soon" for every
     * valid selection.</p>
     *
     * <p><strong>The guard compares five characters.</strong> {@code :146} tests
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} - a reference modifier over the first
     * five bytes of the eight-byte name, not the whole name, and case sensitive. Both menu programs
     * carry this guard: {@code app/cbl/COMEN01C.cbl:146} and {@code app/cbl/COADM01C.cbl:138}.</p>
     *
     * <p><strong>The {@code STRING} statement is defective, and the defect is preserved.</strong>
     * {@code :159-163} concatenates {@code 'This option '} {@code DELIMITED BY SIZE},
     * {@code CDEMO-MENU-OPT-NAME(WS-OPTION)} <strong>{@code DELIMITED BY SPACE}</strong>, and
     * {@code 'is coming soon ...'} {@code DELIMITED BY SIZE}. {@code DELIMITED BY SPACE} stops at the
     * first space, so a caption of {@code 'Account View'} contributes only {@code 'Account'}, and
     * because the closing operand carries no leading space none is emitted. The text is therefore
     * exactly {@code This option Accountis coming soon ...}. Repairing it would be a behaviour change.
     * The same field is transferred {@code DELIMITED BY SIZE} at {@code :245} and kept whole there; both
     * renderings are reproduced and neither is generalised to the other.</p>
     *
     * <p>{@code :158 MOVE DFHGREEN TO ERRMSGC} colours the message green - the attribute CICS used for
     * information rather than the red of an error. There is no 3270 attribute byte in a REST response,
     * so the assertable counterpart is the notice's <em>character</em>: it resolves successfully instead
     * of being refused, and the service records it at {@code DEBUG} rather than at a warning level.</p>
     */
    @Nested
    @DisplayName("5. High: XCTL is terminal, and the defective STRING is reproduced verbatim")
    class XctlIsTerminalAndTheNoticeIsDefectiveByDesign {

        @ParameterizedTest(name = "shipped option {0} dispatches with no coming-soon text")
        @ValueSource(strings = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"})
        @DisplayName("a successful dispatch emits no coming-soon text, because XCTL never returns")
        void aSuccessfulDispatchEmitsNoComingSoonText(final String field) {
            final MainMenuService.MenuSelection selection =
                    productionService().selectOption(field, UserType.USER);

            assertThat(selection.placeholder())
                    .as("no program name in app/cpy/COMEN02Y.cpy:25-84 begins DUMMY, so every shipped "
                            + "option is a real target")
                    .isFalse();
            assertThat(selection.message())
                    .as("EXEC CICS XCTL at app/cbl/COMEN01C.cbl:152-155 transfers control and never comes "
                            + "back, so the notice at :157-164 cannot be reached on this path. A message "
                            + "here would mean the transfer had been modelled as an ordinary call")
                    .isEmpty();
            assertThat(selection.message()).doesNotContain("coming soon");
            assertThat(selection.targetProgram()).isNotBlank().doesNotStartWith(PLACEHOLDER_PREFIX);
        }

        @Test
        @DisplayName("not one of the ten shipped options answers with a notice")
        void noShippedOptionAnswersWithANotice() {
            final MainMenuService service = productionService();

            for (int number = 1; number <= POPULATED_OPTION_COUNT; number++) {
                final MainMenuService.MenuSelection selection =
                        service.selectOption(String.valueOf(number), UserType.USER);
                assertThat(selection.message())
                        .as("option %d must dispatch silently; a notice on any of the ten would mean every "
                                + "valid selection now answers 'coming soon'", number)
                        .isEmpty();
            }
        }

        @ParameterizedTest(name = "the program name {0} is a placeholder: {1}")
        @CsvSource({
            "DUMMY01C, true",
            "DUMMY123, true",
            "DUMMY, true",
            "DUMM, false",
            "DUMY01C, false",
            "dummy01C, false",
            "XDUMMY1, false",
            "COACTVWC, false",
        })
        @DisplayName("the guard compares exactly the first five characters, case sensitively")
        void theGuardComparesTheFirstFiveCharactersOnly(
                final String programName, final boolean expectedPlaceholder) {
            final MenuResponse.MainMenuOption option = new MenuResponse.MainMenuOption(
                    1, "Account View                       ", programName, 'U');

            final MainMenuService.MenuSelection selection =
                    serviceOver(List.of(option)).selectOption("1", UserType.USER);

            assertThat(selection.placeholder())
                    .as("app/cbl/COMEN01C.cbl:146 tests (1:5) NOT = 'DUMMY'. Four characters are too few, "
                            + "a different fifth character fails, lower case fails because COBOL compares "
                            + "bytes, and a name that merely contains DUMMY is not prefixed by it")
                    .isEqualTo(expectedPlaceholder);
        }

        @Test
        @DisplayName("the notice loses the separating space and truncates the caption at its first space")
        void theNoticeLosesTheSpaceAndTruncatesTheCaption() {
            final MainMenuService.MenuSelection selection =
                    serviceOver(List.of(SYNTHETIC_PLACEHOLDER_OPTION)).selectOption("1", UserType.USER);

            assertThat(selection.message())
                    .as("app/cbl/COMEN01C.cbl:159-163 transfers the caption DELIMITED BY SPACE, which "
                            + "stops at the first space, and the closing operand 'is coming soon ...' "
                            + "carries no leading space. Both halves of the defect are preserved")
                    .isEqualTo(DEFECTIVE_NOTICE_FOR_ACCOUNT_VIEW)
                    .isEqualTo("This option Accountis coming soon ...");
            assertThat(selection.message())
                    .as("the space a reader expects between the caption and 'is' is genuinely absent")
                    .isNotEqualTo("This option Account is coming soon ...")
                    .doesNotContain("Account is");
            assertThat(selection.message())
                    .as("and everything after the caption's first space is genuinely dropped, so the "
                            + "second word of 'Account View' never appears")
                    .doesNotContain("View");
            assertThat(selection.optionName())
                    .as("the truncation is confined to the notice; the selection still carries the whole "
                            + "PIC X(35) caption, which is what :245 transfers DELIMITED BY SIZE")
                    .isEqualTo("Account View                       ")
                    .hasSize(OPTION_NAME_WIDTH);
        }

        @Test
        @DisplayName("a caption with no space at all is spliced whole, the delimiter never being found")
        void aCaptionWithNoSpaceIsSplicedWhole() {
            final MenuResponse.MainMenuOption option =
                    new MenuResponse.MainMenuOption(1, "AccountView", "DUMMY01C", 'U');

            final MainMenuService.MenuSelection selection =
                    serviceOver(List.of(option)).selectOption("1", UserType.USER);

            assertThat(selection.message())
                    .as("DELIMITED BY SPACE transfers the whole operand when it contains no space, so "
                            + "nothing is truncated - but the missing separator is unchanged")
                    .isEqualTo("This option AccountViewis coming soon ...");
        }

        @Test
        @DisplayName("the notice is informational, matching DFHGREEN rather than an error attribute")
        void theNoticeIsInformationalRatherThanAnError() {
            // :158 MOVE DFHGREEN TO ERRMSGC precedes the assembly, colouring the message green - CICS's
            // information attribute, not the red it used for errors. A REST response has no attribute
            // byte, so the assertable counterpart is the notice's character: the call SUCCEEDS, carrying
            // the text as a value, and the service records it at DEBUG rather than as a warning.
            final MainMenuService.MenuSelection selection =
                    serviceOver(List.of(SYNTHETIC_PLACEHOLDER_OPTION)).selectOption("1", UserType.USER);

            assertThat(selection.placeholder()).isTrue();
            assertThat(selection.message())
                    .as("the notice is delivered as a value, not raised as a refusal, which is what makes "
                            + "it green rather than red")
                    .isNotEmpty()
                    .isNotEqualTo(INVALID_OPTION_MESSAGE)
                    .isNotEqualTo(ADMIN_ONLY_MESSAGE);
            assertThat(selection.targetProgram())
                    .as("and the placeholder target is still reported, so a caller can name what is coming")
                    .startsWith(PLACEHOLDER_PREFIX);

            final List<ILoggingEvent> notices = placeholderEvents();
            assertThat(notices).as("the placeholder path is recorded exactly once").hasSize(1);
            assertThat(notices.get(0).getLevel())
                    .as("DEBUG, never WARN or ERROR: an option that is not built yet is information for "
                            + "the operator, which is precisely what DFHGREEN at :158 signified")
                    .isEqualTo(Level.DEBUG);
        }
    }

    // ==================================================================
    // 6 - The shipped table triggers neither retained guard.
    //     INTENTIONAL NO-OP, retained for parity, never deleted.
    // ==================================================================

    /**
     * What the frozen data actually contains, and why two live branches can never fire on it.
     *
     * <p><strong>Intentional no-op marker, and the one conflict this file carries.</strong> Rule 1
     * Clause B forbids dead code; the parity mandate forbids deleting a reachable branch. Parity governs,
     * and Clause B is satisfied on its own terms because its prohibition is on artefacts <em>without an
     * owner or tracking reference</em>. Both retained branches have one: a {@code DECISION_LOG.md} entry,
     * a {@code TRACEABILITY_MATRIX.md} row, Javadoc citing the source locator, and this marker.</p>
     *
     * <ul>
     *   <li><strong>The eligibility gate of {@code app/cbl/COMEN01C.cbl:136-142} is never true with the
     *       shipped data.</strong> All ten populated slots of {@code app/cpy/COMEN02Y.cpy:25-84} carry
     *       {@code CDEMO-MENU-OPT-USRTYPE} of {@code 'U'}, so the second conjunct
     *       {@code = 'A'} cannot hold. It is exercised only through the synthetic fixture
     *       {@link MainMenuServiceTest#SYNTHETIC_ADMIN_ONLY_OPTION}, and <strong>no administrator-typed
     *       slot is invented into the production table</strong>.</li>
     *   <li><strong>The {@code DUMMY} guard of {@code :146} is never taken with the shipped data.</strong>
     *       No populated slot names a program beginning {@code DUMMY}, so the notice branch is
     *       unreachable in production and is exercised only through
     *       {@link MainMenuServiceTest#SYNTHETIC_PLACEHOLDER_OPTION}.</li>
     * </ul>
     *
     * <p>Deleting either would break the paragraph map that group 7 pins and the coverage gate reads.
     * Two further items of evidence: slot 8 of {@code app/cpy/COMEN02Y.cpy:67-72} carries a
     * <em>commented-out</em> caption at {@code :69} reading
     * {@code 'Transaction Add (Admin Only)       '}, which is why that caption is the honest choice for
     * the synthetic fixture and why the live slot 8 must still read {@code 'Transaction Add'}; and
     * {@code :149-150} of the program are two commented-out identity moves,
     * {@code MOVE WS-USER-ID TO CDEMO-USER-ID} and {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, that
     * {@code app/cbl/COADM01C.cbl} does not carry - a divergence of severity <strong>Low</strong>, noted
     * because it is the only structural difference between the two dispatch blocks.</p>
     */
    @Nested
    @DisplayName("6. The shipped table fires neither retained guard, and no slot is invented")
    class TheShippedTableFiresNeitherRetainedGuard {

        @Test
        @DisplayName("every shipped option is user-type U, so a standard user may reach all ten")
        void everyShippedOptionIsUserTypeU() {
            assertThat(MenuResponse.MAIN_MENU_OPTIONS).hasSize(POPULATED_OPTION_COUNT);

            for (final MenuResponse.MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(option.userTypeCode())
                        .as("slot %d of app/cpy/COMEN02Y.cpy:25-84 carries CDEMO-MENU-OPT-USRTYPE 'U'",
                                option.optionNumber())
                        .isEqualTo('U');
                assertThat(option.userType())
                        .as("and 'U' is CDEMO-USRTYP-USER of app/cpy/COCOM01Y.cpy:28")
                        .isEqualTo(UserType.USER);
            }
        }

        @Test
        @DisplayName("the shipped table triggers the admin gate for no option at all")
        void theShippedTableTriggersTheAdminGateForNoOption() {
            final MainMenuService service = productionService();

            for (int number = 1; number <= POPULATED_OPTION_COUNT; number++) {
                final MainMenuService.MenuSelection selection =
                        service.selectOption(String.valueOf(number), UserType.USER);
                assertThat(selection.optionNumber())
                        .as("a standard user resolves shipped option %d without meeting the gate of "
                                + "app/cbl/COMEN01C.cbl:136-142, which no shipped slot can satisfy", number)
                        .isEqualTo(number);
            }
        }

        @Test
        @DisplayName("the admin gate is reachable only through the synthetic fixture, and it does fire there")
        void theAdminGateIsReachableOnlyThroughTheSyntheticFixture() {
            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .as("the never-true shipped path: not one populated slot carries 'A', which is why "
                            + "this branch needs a synthetic fixture rather than production data")
                    .noneMatch(option -> option.userTypeCode() == 'A');

            final ValidationException refusal = catchValidation(() ->
                    serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION)).selectOption("1", UserType.USER));

            assertThat(refusal.getMessage())
                    .as("and the branch is live code that still behaves exactly as :140 made it behave "
                            + "once a slot carrying 'A' does reach it")
                    .isEqualTo(ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("no shipped option targets a DUMMY program, so the notice branch also needs the fixture")
        void noShippedOptionTargetsAPlaceholderProgram() {
            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .as("the second never-taken shipped path, guarded at app/cbl/COMEN01C.cbl:146")
                    .noneMatch(option -> option.programName().startsWith(PLACEHOLDER_PREFIX));

            for (final MenuResponse.MainMenuOption option : MenuResponse.MAIN_MENU_OPTIONS) {
                assertThat(option.programName())
                        .as("every shipped slot names a real eight-character program, "
                                + "CDEMO-MENU-OPT-PGMNAME PIC X(08) at app/cpy/COMEN02Y.cpy:91")
                        .hasSize(PROGRAM_NAME_WIDTH);
            }
        }

        @Test
        @DisplayName("slot 8 carries the live caption, not the commented-out administrator-only one")
        void slotEightCarriesTheLiveCaptionNotTheCommentedOutOne() {
            final MenuResponse.MainMenuOption slotEight = MenuResponse.MAIN_MENU_OPTIONS.get(7);

            assertThat(slotEight.optionNumber()).isEqualTo(8);
            assertThat(slotEight.optionName())
                    .as("app/cpy/COMEN02Y.cpy:70 is the live literal; :69 is the commented-out "
                            + "'Transaction Add (Admin Only)       ' and is evidence only, never a slot")
                    .isEqualTo("Transaction Add                    ")
                    .doesNotContain("Admin Only");
            assertThat(slotEight.userTypeCode())
                    .as("and the live slot is 'U', which is why the gate cannot fire on it despite the "
                            + "commented-out caption's wording")
                    .isEqualTo('U');
            assertThat(SYNTHETIC_ADMIN_ONLY_OPTION.optionName())
                    .as("the fixture, by contrast, uses the disabled literal verbatim so that even the "
                            + "synthetic case is corpus-derived")
                    .isEqualTo("Transaction Add (Admin Only)       ");
        }

        @Test
        @DisplayName("a standard user cannot reach an administrator-typed option by any route")
        void aStandardUserCannotReachAnAdminTypedOptionByAnyRoute() {
            // Rule 1 Clause D, least privilege. Two independent controls must both hold: the option is
            // withheld from what the user is offered, AND naming it directly is still refused.
            final MainMenuService service = serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION));

            assertThat(service.getMainMenu(UserType.USER).menu().getOptions())
                    .as("an administrator-only option is not offered to a standard user")
                    .isEmpty();
            assertThat(service.getMainMenu(UserType.ADMIN).menu().getOptions())
                    .as("but an administrator is offered it, so the control is a gate and not a deletion")
                    .containsExactly(SYNTHETIC_ADMIN_ONLY_OPTION);

            final ValidationException refusal =
                    catchValidation(() -> service.selectOption("1", UserType.USER));
            assertThat(refusal.getMessage())
                    .as("and naming the withheld option directly is refused by the gate of :136-142, so "
                            + "withholding it from the list is not the only thing standing in the way")
                    .isEqualTo(ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("an administrator selecting the administrator-only option resolves normally")
        void anAdministratorMaySelectTheAdminOnlyOption() {
            final MainMenuService.MenuSelection selection =
                    serviceOver(List.of(SYNTHETIC_ADMIN_ONLY_OPTION)).selectOption("1", UserType.ADMIN);

            assertThat(selection.targetProgram())
                    .as("the gate at :136 opens with IF CDEMO-USRTYP-USER, so it does not apply to an "
                            + "administrator and the option dispatches")
                    .isEqualTo("COTRN02C");
            assertThat(selection.message()).isEmpty();
        }
    }

    // ==================================================================
    // 7 - The seven paragraph labels map one to one.
    // ==================================================================

    /**
     * The paragraph map of {@code app/cbl/COMEN01C.cbl}, which has 282 lines and exactly seven labels.
     *
     * <p>Each label corresponds to exactly one private method. None is consolidated with another, none is
     * deleted, and that includes the guards groups 4 and 6 show cannot fire on the shipped data. The
     * coverage gate reads this correspondence, so it is asserted structurally here rather than assumed:
     * a rename, a merge or a deletion fails a named test that cites the line it was found at.</p>
     *
     * <table>
     *   <caption>The seven labels and their counterparts</caption>
     *   <tr><th>Label</th><th>Line</th><th>Method</th></tr>
     *   <tr><td>{@code MAIN-PARA}</td><td>75</td><td>{@code mainPara(UserType)}</td></tr>
     *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>115</td>
     *       <td>{@code processEnterKey(String, UserType)}</td></tr>
     *   <tr><td>{@code RETURN-TO-SIGNON-SCREEN}</td><td>170</td>
     *       <td>{@code returnToSignonScreen()}</td></tr>
     *   <tr><td>{@code SEND-MENU-SCREEN}</td><td>182</td><td>{@code sendMenuScreen(UserType)}</td></tr>
     *   <tr><td>{@code RECEIVE-MENU-SCREEN}</td><td>199</td>
     *       <td>{@code receiveMenuScreen(String)}</td></tr>
     *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>212</td><td>{@code populateHeaderInfo()}</td></tr>
     *   <tr><td>{@code BUILD-MENU-OPTIONS}</td><td>236</td><td>{@code buildMenuOptions(List)}</td></tr>
     * </table>
     */
    @Nested
    @DisplayName("7. The seven paragraphs of COMEN01C map one to one onto seven private methods")
    class TheSevenParagraphsMapOneToOne {

        @Test
        @DisplayName("MAIN-PARA at app/cbl/COMEN01C.cbl:75 maps to mainPara(UserType)")
        void mainParaIsMapped() {
            assertParagraphMethod("MAIN-PARA", 75, "mainPara", UserType.class);
        }

        @Test
        @DisplayName("PROCESS-ENTER-KEY at app/cbl/COMEN01C.cbl:115 maps to processEnterKey(String, UserType)")
        void processEnterKeyIsMapped() {
            assertParagraphMethod("PROCESS-ENTER-KEY", 115, "processEnterKey", String.class, UserType.class);
        }

        @Test
        @DisplayName("RETURN-TO-SIGNON-SCREEN at app/cbl/COMEN01C.cbl:170 maps to returnToSignonScreen()")
        void returnToSignonScreenIsMapped() {
            assertParagraphMethod("RETURN-TO-SIGNON-SCREEN", 170, "returnToSignonScreen");
        }

        @Test
        @DisplayName("SEND-MENU-SCREEN at app/cbl/COMEN01C.cbl:182 maps to sendMenuScreen(UserType)")
        void sendMenuScreenIsMapped() {
            // The paragraph that carries no EXEC CICS RETURN; see group 4 for what that causes.
            assertParagraphMethod("SEND-MENU-SCREEN", 182, "sendMenuScreen", UserType.class);
        }

        @Test
        @DisplayName("RECEIVE-MENU-SCREEN at app/cbl/COMEN01C.cbl:199 maps to receiveMenuScreen(String)")
        void receiveMenuScreenIsMapped() {
            assertParagraphMethod("RECEIVE-MENU-SCREEN", 199, "receiveMenuScreen", String.class);
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO at app/cbl/COMEN01C.cbl:212 maps to populateHeaderInfo()")
        void populateHeaderInfoIsMapped() {
            assertParagraphMethod("POPULATE-HEADER-INFO", 212, "populateHeaderInfo");
        }

        @Test
        @DisplayName("BUILD-MENU-OPTIONS at app/cbl/COMEN01C.cbl:236 maps to buildMenuOptions(List)")
        void buildMenuOptionsIsMapped() {
            assertParagraphMethod("BUILD-MENU-OPTIONS", 236, "buildMenuOptions", List.class);
        }

        @Test
        @DisplayName("no paragraph leaked into the public surface, which stays the two REST operations")
        void noParagraphLeakedIntoThePublicSurface() {
            final List<String> publicMethodNames = new ArrayList<>();
            for (final Method method : MainMenuService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    publicMethodNames.add(method.getName());
                }
            }

            assertThat(publicMethodNames)
                    .as("the paragraphs are internal structure. The published surface is the two "
                            + "operations that replace the pseudo-conversational halves of MAIN-PARA: the "
                            + "first-entry send of app/cbl/COMEN01C.cbl:87-90 and the re-entry receive of "
                            + ":92-95")
                    .containsExactlyInAnyOrder("getMainMenu", "selectOption");
        }
    }

    // ==================================================================
    // 8 - The header furniture, and the session state that does not exist.
    // ==================================================================

    /**
     * {@code POPULATE-HEADER-INFO} of {@code app/cbl/COMEN01C.cbl:212-233}, and the state the request
     * deliberately does not carry.
     *
     * <p>{@code :214 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} is a <em>single</em> intrinsic call
     * feeding both the date group of {@code :221-225} and the time group of {@code :227-231}, so the two
     * can never straddle midnight relative to one another. The Java counterpart reads one
     * {@code LocalDateTime} from the injected clock for the same reason, and this group asserts the
     * rendering against a clock fixed at a known instant - never against the wall clock, which Rule 1
     * Clause A forbids and which would cluster failures on second, day and month boundaries.</p>
     *
     * <p>{@code :216-219} moves four literal header fields, and {@code :170-179
     * RETURN-TO-SIGNON-SCREEN} names {@code 'COSGN00C'} as the sign-on target. What has no counterpart at
     * all is {@code CDEMO-PGM-CONTEXT} and its two condition names at {@code app/cpy/COCOM01Y.cpy:29-31}:
     * the first-entry versus re-entry flag of {@code :87-90} is pseudo-conversational session state, and a
     * stateless request does not carry it, so every call is a first entry. Note also that
     * {@code app/cpy/COCOM01Y.cpy} carries <strong>no page-number and no next-page field</strong>, so
     * nothing of the sort is asserted here.</p>
     */
    @Nested
    @DisplayName("8. The header is read from the injected clock, and no session state survives a call")
    class TheHeaderIsDeterministicAndTheRequestCarriesNoSessionState {

        @Test
        @DisplayName("the header date and time come from the injected clock, not from the wall clock")
        void theHeaderIsReadFromTheInjectedClock() {
            productionService().getMainMenu(UserType.USER);

            final Object[] arguments = MenuServiceTestSupport.argumentsOf(
                    MenuServiceTestSupport.singleHeaderEvent(headerEvents(),
                            "app/cbl/COMEN01C.cbl:212 runs once per SEND-MENU-SCREEN, so a second event "
                                    + "would mean the Java service sends twice"),
                    "the header statement is parameterised, so its rendered furniture is readable");

            assertThat(arguments[DATE_ARGUMENT_INDEX])
                    .as("WS-CURDATE-MM-DD-YY of app/cbl/COMEN01C.cbl:221-225 rendered from the instant "
                            + "%s read at UTC - fixed, so this can never fail on a date boundary",
                            FIXED_INSTANT)
                    .isEqualTo("06/10/22");
            assertThat(arguments[TIME_ARGUMENT_INDEX])
                    .as("WS-CURTIME-HH-MM-SS of app/cbl/COMEN01C.cbl:227-231 from the same single "
                            + "FUNCTION CURRENT-DATE call at :214")
                    .isEqualTo("19:27:53");
        }

        @Test
        @DisplayName("the header names the transaction and the program with the source's own literals")
        void theHeaderNamesTheTransactionAndProgramLiterally() {
            productionService().getMainMenu(UserType.USER);

            final Object[] arguments = MenuServiceTestSupport.argumentsOf(
                    MenuServiceTestSupport.singleHeaderEvent(headerEvents(), "one send, one header"),
                    "the header statement is parameterised");

            assertThat(arguments[0])
                    .as("WS-TRANID PIC X(04) VALUE 'CM00' at app/cbl/COMEN01C.cbl:37, which is also the "
                            + "CSD transaction that fronted this program")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(arguments[1])
                    .as("WS-PGMNAME PIC X(08) VALUE 'COMEN01C' at app/cbl/COMEN01C.cbl:36")
                    .isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("the date and the time are read together, so they cannot straddle midnight")
        void theDateAndTimeAreReadTogether() {
            // One FUNCTION CURRENT-DATE call at :214 fed both groups. Two independent renderings from the
            // same fixed clock must therefore agree completely; a service reading now() twice could not.
            final MainMenuService service = productionService();
            service.getMainMenu(UserType.USER);
            service.getMainMenu(UserType.ADMIN);

            final List<ILoggingEvent> headers = headerEvents();
            assertThat(headers).hasSize(2);

            final Object[] first = MenuServiceTestSupport.argumentsOf(headers.get(0), "parameterised");
            final Object[] second = MenuServiceTestSupport.argumentsOf(headers.get(1), "parameterised");
            assertThat(second[DATE_ARGUMENT_INDEX])
                    .as("the same fixed clock renders the same date on every send")
                    .isEqualTo(first[DATE_ARGUMENT_INDEX]);
            assertThat(second[TIME_ARGUMENT_INDEX])
                    .as("and the same time, which is what proves the clock is injected rather than read "
                            + "from a static now() call")
                    .isEqualTo(first[TIME_ARGUMENT_INDEX]);
        }

        @Test
        @DisplayName("the screen names COSGN00C as the sign-on target, per RETURN-TO-SIGNON-SCREEN")
        void theScreenNamesTheSignOnTarget() {
            assertThat(productionService().getMainMenu(UserType.USER).signOnTarget())
                    .as("app/cbl/COMEN01C.cbl:170-179 RETURN-TO-SIGNON-SCREEN moves 'COSGN00C' when "
                            + "CDEMO-TO-PROGRAM is blank, then XCTLs to it")
                    .isEqualTo(SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("every display line is exactly forty characters, per BUILD-MENU-OPTIONS")
        void everyDisplayLineIsExactlyFortyCharacters() {
            final List<String> lines = productionService().getMainMenu(UserType.USER).optionLabels();

            assertThat(lines)
                    .as("one line per option offered, bounded by CDEMO-MENU-OPT-COUNT at "
                            + "app/cbl/COMEN01C.cbl:239")
                    .hasSize(POPULATED_OPTION_COUNT);
            for (final String line : lines) {
                assertThat(line)
                        .as("OPTN001O through OPTN012O of app/cpy-bms/COMEN01.CPY are PIC X(40), and "
                                + "app/cbl/COMEN01C.cbl:241-244 assembles a two-digit number, '. ' and the "
                                + "thirty-five-character caption into that slot")
                        .hasSize(SCREEN_SLOT_WIDTH);
            }
        }

        @Test
        @DisplayName("a null user type is refused before anything else is read")
        void aNullUserTypeIsRefusedBeforeAnythingElseIsRead() {
            // Hostile input, Rule 1 Clause A. MAIN-PARA runs first in both operations, so an unresolved
            // user class is refused before the option field is even looked at.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("CDEMO-USER-TYPE of app/cpy/COCOM01Y.cpy:26 was always populated by the sign-on "
                            + "program; a stateless request must carry the equivalent claim")
                    .isThrownBy(() -> productionService().getMainMenu(null))
                    .withMessageContaining("userType must not be null");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and the same holds on the selection path, where MAIN-PARA also runs first")
                    .isThrownBy(() -> productionService().selectOption("1", null))
                    .withMessageContaining("userType must not be null");

            assertThat(headerEvents())
                    .as("nothing was sent, so the refusal preceded SEND-MENU-SCREEN entirely")
                    .isEmpty();
        }

        @Test
        @DisplayName("no session state survives a call, so every call is a first entry")
        void noSessionStateSurvivesACall() {
            final MainMenuService service = productionService();

            final MainMenuService.MainMenuScreen first = service.getMainMenu(UserType.USER);
            service.selectOption("4", UserType.USER);
            final MainMenuService.MainMenuScreen second = service.getMainMenu(UserType.USER);

            assertThat(second.menu().getOptions())
                    .as("CDEMO-PGM-CONTEXT of app/cpy/COCOM01Y.cpy:29-31 has no counterpart, so the "
                            + "re-entry branch of app/cbl/COMEN01C.cbl:87-90 cannot be taken and an "
                            + "intervening selection changes nothing")
                    .isEqualTo(first.menu().getOptions());
            assertThat(second.optionLabels()).isEqualTo(first.optionLabels());
            assertThat(second.signOnTarget()).isEqualTo(first.signOnTarget());
        }
    }

    // ==================================================================
    // 9 - Nothing shared, nothing mutable, and one way in.
    // ==================================================================

    /**
     * The immutability and construction contracts that replace {@code WORKING-STORAGE}.
     *
     * <p>{@code WS-OPTION} at {@code app/cbl/COMEN01C.cbl:46}, {@code WS-OPTION-X} at {@code :45},
     * {@code WS-ERR-FLG} and {@code WS-IDX} were program-global storage that survived between paragraphs.
     * Rule 1 Clause B forbids global mutable state, so every one of them becomes a method-local in the
     * Java counterpart and the bean itself holds only immutable, final collaborators. This group asserts
     * that structurally rather than trusting it, because a single {@code static} accumulator would make
     * the bean unsafe to share between request threads while every behavioural test above still passed.</p>
     */
    @Nested
    @DisplayName("9. No global mutable state, a copied table, and a package-private seam")
    class NoGlobalMutableStateAndOneWayIn {

        @Test
        @DisplayName("the service declares no mutable static and no mutable instance field")
        void theServiceDeclaresNoMutableState() {
            for (final Field field : MainMenuService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field '%s' must be final: the legacy WS-OPTION, WS-OPTION-X, WS-ERR-FLG and "
                                + "WS-IDX of app/cbl/COMEN01C.cbl:45-46 are method-locals in the Java "
                                + "counterpart, and nothing accumulates on the bean", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the offered option list cannot be mutated through the response")
        void theOfferedOptionListCannotBeMutated() {
            assertThat(productionService().getMainMenu(UserType.USER).menu().getOptions())
                    .as("CDEMO-MENU-OPTIONS-DATA of app/cpy/COMEN02Y.cpy:23 is literal VALUE storage that "
                            + "no paragraph writes to, so the Java view must be unmodifiable too")
                    .isUnmodifiable();
        }

        @Test
        @DisplayName("the supplied table is copied, so a later caller mutation cannot reach the bean")
        void theSuppliedTableIsCopiedDefensively() {
            final List<MenuResponse.MainMenuOption> caller = new ArrayList<>();
            caller.add(SYNTHETIC_ORDINARY_OPTION);
            final MainMenuService service = serviceOver(caller);

            caller.clear();
            caller.add(SYNTHETIC_ADMIN_ONLY_OPTION);

            final MainMenuService.MenuSelection selection = service.selectOption("1", UserType.USER);
            assertThat(selection.targetProgram())
                    .as("the bean resolves against its own copy, so swapping the caller's list for an "
                            + "administrator-only slot cannot retroactively gate an accepted option")
                    .isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("a null clock is refused, so the header can never be read from an absent collaborator")
        void aNullClockIsRefused() {
            // The other half of the construction contract. FUNCTION CURRENT-DATE at
            // app/cbl/COMEN01C.cbl:214 could not fail to have a clock; the injected equivalent can, so the
            // guard exists and is asserted rather than assumed.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("POPULATE-HEADER-INFO reads the clock on every send, so an absent one must be "
                            + "refused at construction rather than surfacing as a failure per request")
                    .isThrownBy(() -> MenuServiceTestSupport.construct(
                            MainMenuService.class, seam(), null, List.of()))
                    .withMessageContaining("clock must not be null");
        }

        @Test
        @DisplayName("a null option table is refused, and the message says an empty list is the way to say none")
        void aNullOptionTableIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("CDEMO-MENU-OPTIONS-DATA of app/cpy/COMEN02Y.cpy:23 is literal storage that always "
                            + "exists, so absence is a programming error rather than an empty menu")
                    .isThrownBy(() -> serviceOver(null))
                    .withMessageContaining("menuOptions must not be null")
                    .withMessageContaining("empty list");
        }

        @Test
        @DisplayName("an empty table is permitted, and then every selection is refused")
        void anEmptyTableIsPermittedAndRefusesEverySelection() {
            final MainMenuService service = serviceOver(List.of());

            assertThat(service.getMainMenu(UserType.USER).menu().getOptions())
                    .as("an empty table means a menu offering nothing, which is a legitimate state and "
                            + "not a construction failure")
                    .isEmpty();
            assertInvalidOption(() -> service.selectOption("1", UserType.USER));
        }

        @Test
        @DisplayName("a null element in the supplied table is refused, and the message names its index")
        void aNullElementInTheSuppliedTableIsRefused() {
            final List<MenuResponse.MainMenuOption> withHole = new ArrayList<>();
            withHole.add(SYNTHETIC_ORDINARY_OPTION);
            withHole.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an unpopulated slot is exactly the hazard the OCCURS over-arity of "
                            + "app/cpy/COMEN02Y.cpy:88 creates, and it is refused at construction rather "
                            + "than read at selection time")
                    .isThrownBy(() -> serviceOver(withHole))
                    .withMessageContaining("must not contain a null element")
                    .withMessageContaining("index 1");
        }

        @Test
        @DisplayName("the published constructor is the no-argument one and the seam is package-private")
        void thePublishedConstructorIsTheNoArgumentOne() {
            final List<String> publicConstructorShapes = new ArrayList<>();
            for (final Constructor<?> constructor : MainMenuService.class.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    publicConstructorShapes.add(String.valueOf(constructor.getParameterCount()));
                }
            }

            assertThat(publicConstructorShapes)
                    .as("only the no-argument constructor is published; the container has one "
                            + "deterministic choice and cannot fail to wire this bean")
                    .containsExactly("0");

            final Constructor<?> seam = MenuServiceTestSupport.seamConstructor(MainMenuService.class,
                    "the two retained parity branches of app/cbl/COMEN01C.cbl:136-142 and :146 could not "
                            + "be exercised at all, and no coverage exclusion is permitted");
            final int seamModifiers = seam.getModifiers();
            assertThat(Modifier.isPublic(seamModifiers) || Modifier.isProtected(seamModifiers))
                    .as("the seam is package-private, so it widens nothing that production can reach")
                    .isFalse();
        }

        @Test
        @DisplayName("the default construction path carries the ten shipped options")
        void theDefaultConstructionPathCarriesTheShippedOptions() {
            // The one place the production no-argument constructor is exercised. Its clock is the system
            // default zone, which is why nothing here asserts a rendered date or time.
            assertThat(new MainMenuService().getMainMenu(UserType.USER).menu().getOptions())
                    .as("app/cpy/COMEN02Y.cpy:25-84 in copybook order, which is what the seam-injected "
                            + "table in every other test stands in for")
                    .isEqualTo(MenuResponse.MAIN_MENU_OPTIONS);
        }

        @Test
        @DisplayName("repeated selections do not accumulate state on the bean")
        void repeatedSelectionsDoNotAccumulateState() {
            final MainMenuService service = productionService();

            final MainMenuService.MenuSelection first = service.selectOption("7", UserType.USER);
            catchValidation(() -> service.selectOption("11", UserType.USER));
            final MainMenuService.MenuSelection third = service.selectOption("7", UserType.USER);

            assertThat(third)
                    .as("WS-ERR-FLG of the source survived between paragraphs within one task but never "
                            + "between tasks; an intervening refusal must leave the bean unchanged")
                    .isEqualTo(first);
        }
    }

    // ==================================================================
    // Fixtures and shared assertions.
    // ==================================================================

    /**
     * The construction seam, resolved once so that every helper below shares one lookup.
     *
     * @return the package-private {@code (Clock, List)} constructor of the service under test
     */
    private static Constructor<?> seam() {
        return MenuServiceTestSupport.seamConstructor(MainMenuService.class,
                "the header furniture could not be pinned to a fixed instant and the two retained parity "
                        + "branches of app/cbl/COMEN01C.cbl:136-142 and :146 could not be reached at all");
    }

    /**
     * A clock fixed at {@link #FIXED_INSTANT} in {@link ZoneOffset#UTC}.
     *
     * <p>An explicit offset is used rather than the ambient zone: Rule 1 Clause A forbids
     * {@code ZoneId.systemDefault()} here, and a fixed offset is what makes the rendered header of group 8
     * identical on every machine and in every season.</p>
     *
     * @return an immutable fixed clock
     */
    private static Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    /**
     * The service over the ten shipped options of {@code app/cpy/COMEN02Y.cpy:25-84} and a fixed clock.
     *
     * @return a service whose option table is the frozen production table
     */
    private static MainMenuService productionService() {
        return serviceOver(MenuResponse.MAIN_MENU_OPTIONS);
    }

    /**
     * The service over a caller-supplied option table and a fixed clock.
     *
     * @param options the table to resolve selections against, in menu order
     * @return the constructed service
     * @throws IllegalArgumentException if the table breaches the construction contract, rethrown
     *                                  unchanged from the seam
     */
    private static MainMenuService serviceOver(final List<MenuResponse.MainMenuOption> options) {
        return MenuServiceTestSupport.construct(MainMenuService.class, seam(), fixedClock(), options);
    }

    /**
     * Runs an action and returns the {@link ValidationException} it must raise.
     *
     * @param action the call expected to be refused
     * @return the raised refusal, for independent assertions on message, field and failure kind
     */
    private static ValidationException catchValidation(final Runnable action) {
        return MenuServiceTestSupport.catchValidation(action);
    }

    /**
     * Asserts that an action is refused with the invalid-option literal, naming the option field.
     *
     * @param action the call expected to be refused
     */
    private static void assertInvalidOption(final Runnable action) {
        MenuServiceTestSupport.assertInvalidOption(action, INVALID_OPTION_MESSAGE, OPTION_FIELD_NAME);
    }

    /**
     * Asserts that a source paragraph label has exactly one private counterpart of the given shape.
     *
     * @param label          the {@code app/cbl/COMEN01C.cbl} paragraph label
     * @param line           the line the label is declared at, cited in the failure message
     * @param methodName     the expected Java method name
     * @param parameterTypes the expected parameter types, in order
     */
    private static void assertParagraphMethod(final String label, final int line, final String methodName,
            final Class<?>... parameterTypes) {

        Method counterpart = null;
        for (final Method candidate : MainMenuService.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName) && !candidate.isSynthetic()) {
                assertThat(counterpart)
                        .as("paragraph %s of app/cbl/COMEN01C.cbl:%d must map to exactly one method, but "
                                + "'%s' is overloaded, which would make the correspondence ambiguous",
                                label, line, methodName)
                        .isNull();
                counterpart = candidate;
            }
        }

        assertThat(counterpart)
                .as("paragraph %s of app/cbl/COMEN01C.cbl:%d has no counterpart named '%s'; labels are "
                        + "never consolidated, renamed or deleted, and the coverage gate reads this map",
                        label, line, methodName)
                .isNotNull();
        assertThat(counterpart.getParameterTypes())
                .as("the counterpart of %s at app/cbl/COMEN01C.cbl:%d must take exactly what the "
                        + "paragraph consumed", label, line)
                .containsExactly(parameterTypes);
        assertThat(Modifier.isPrivate(counterpart.getModifiers()))
                .as("paragraph %s is internal structure and must not widen the published surface", label)
                .isTrue();
    }

    /**
     * The header-rendering events logged since the appender was attached.
     *
     * @return the matching events in logged order
     */
    private List<ILoggingEvent> headerEvents() {
        return MenuServiceTestSupport.headerEvents(this.appender, "Main menu header:");
    }

    /**
     * The events {@code PROCESS-ENTER-KEY} logs when a selection resolves to a real target program.
     *
     * @return the matching events in logged order
     */
    private List<ILoggingEvent> selectionEvents() {
        return this.appender.list.stream()
                .filter(event -> event.getMessage().startsWith(SELECTION_MESSAGE_PREFIX))
                .filter(event -> !event.getMessage().contains(PLACEHOLDER_MESSAGE_MARKER))
                .toList();
    }

    /**
     * The events {@code PROCESS-ENTER-KEY} logs when a selection yields the coming-soon notice.
     *
     * @return the matching events in logged order
     */
    private List<ILoggingEvent> placeholderEvents() {
        return this.appender.list.stream()
                .filter(event -> event.getMessage().startsWith(SELECTION_MESSAGE_PREFIX))
                .filter(event -> event.getMessage().contains(PLACEHOLDER_MESSAGE_MARKER))
                .toList();
    }
}
