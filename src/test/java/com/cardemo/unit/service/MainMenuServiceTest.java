/*
 * ******************************************************************
 * Program     : MainMenuServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies that the main menu header furniture is read
 *               from an injected Clock rather than from ambient wall
 *               time, so that the two fields COMEN01C assembles from a
 *               single MOVE FUNCTION CURRENT-DATE are reproducible and
 *               follow the clock's own zone. Covers the zone boundary,
 *               the midnight boundary, the two-digit year reduction and
 *               the locale independence of the rendering, plus the guard
 *               on the seam that makes all of it reachable.
 * Source      : app/cbl/COMEN01C.cbl:214       (MOVE FUNCTION CURRENT-DATE
 *                                               TO WS-CURDATE-DATA)
 *               app/cbl/COMEN01C.cbl:216-231   (POPULATE-HEADER-INFO)
 *               app/cpy/CSDAT01Y.cpy           (WS-CURDATE / WS-CURTIME)
 *               app/cpy/COTTL01Y.cpy           (the two title literals)
 *               app/cpy-bms/COMEN01.CPY:54     (CURTIMEI PIC X(8))
 *               app/csd/CARDDEMO.CSD           (CM00 -> COMEN01C) @ 7756d89
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
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.MenuOptionCopybook;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * Asserts that the header furniture of {@code POPULATE-HEADER-INFO} is deterministic.
 *
 * <p>Before the clock was injected this behaviour was not assertable at all: the paragraph read
 * {@code LocalDateTime.now()}, so the values it produced depended on when the test ran and on the host's
 * default time zone. That is the defect, and a test that cannot fail when the defect is present is not
 * evidence, so the assertions below pin exact rendered strings rather than shapes.
 *
 * <p><strong>That claim was verified rather than asserted.</strong> Reinstating both halves of the defect in
 * the production source - {@code LocalDateTime.now(this.clock)} back to {@code LocalDateTime.now()}, and
 * {@code ofPattern("MM/dd/yy", Locale.ROOT)} back to {@code ofPattern("MM/dd/yy")} - and re-running this
 * class produced 12 failures across three of its four groups: every case in group 2, three of four in
 * group 1, and two of three in group 3. The production source was then restored and confirmed byte
 * identical by checksum. The one case that survived the mutation is
 * {@link ClockIsHonoured#repeatedCallsAreIdentical()}, which two ambient readings inside the same second
 * satisfy by luck; it is kept because with the clock injected it is exact rather than probabilistic, but it
 * is not the case that carries the proof.
 *
 * <p><strong>The observable is the log line.</strong> {@code populateHeaderInfo()} returns {@code void} and
 * {@link com.cardemo.service.menu.MainMenuService.MainMenuScreen} deliberately carries no header components -
 * a REST response has no screen furniture to fill - so the paragraph's only effect is a {@code DEBUG}
 * statement. These tests therefore attach a logback {@link ListAppender} to that logger, which reads the
 * production statement as it stands rather than requiring the production code to grow a test-only accessor.
 *
 * <p>The bean is built through its package-private seam by reflection, following the cross-package reflection
 * precedent of {@code RepositoryContractTest}. The AAP places unit tests at
 * {@code src/test/java/com/cardemo/unit/service}, so the test cannot sit in the production package.
 */
@DisplayName("MainMenuService: the header is read from the injected clock, never from ambient wall time")
class MainMenuServiceTest {

    /** {@code CM00} of {@code app/csd/CARDDEMO.CSD}, the transaction that fronts {@code COMEN01C}. */
    private static final String EXPECTED_TRANSACTION_ID = "CM00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} at {@code app/cbl/COMEN01C.cbl:36}. */
    private static final String EXPECTED_PROGRAM_NAME = "COMEN01C";

    /** {@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy}, forty bytes wide. */
    private static final String EXPECTED_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} of {@code app/cpy/COTTL01Y.cpy}, forty bytes wide. */
    private static final String EXPECTED_TITLE_02 = "              CardDemo                  ";

    /** Index of the rendered date within the log statement's argument array. */
    private static final int DATE_ARGUMENT_INDEX = 4;

    /** Index of the rendered time within the log statement's argument array. */
    private static final int TIME_ARGUMENT_INDEX = 5;

    /** A single legitimate option, so that the seam is exercised with a populated table. */
    private static final MenuResponse.MainMenuOption SAMPLE_OPTION =
            new MenuResponse.MainMenuOption(1, "Account View", "COACTVWC", 'U');

    /** The frozen copybook that declares the main option table. */
    private static final String COPYBOOK_MEMBER = "COMEN02Y";

    /** {@code app/cpy/COMEN02Y.cpy} {@code CDEMO-MENU-OPT-NAME PIC X(35)}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** {@code app/cpy-bms/COMEN01.CPY} {@code OPTIONI PIC X(2)}. */
    private static final int OPTION_INPUT_LENGTH = 2;

    /**
     * {@code app/cbl/COMEN01C.cbl:48} {@code WS-MENU-OPT-TXT PIC X(40)}, corroborated by
     * {@code app/cpy-bms/COMEN01.CPY:182} {@code OPTN001O PIC X(40)}.
     */
    private static final int SCREEN_SLOT_WIDTH = 40;

    /** {@code app/cbl/COMEN01C.cbl:137} compares the user-type byte against the literal {@code 'A'}. */
    private static final char ADMIN_ONLY_OPTION_CODE = 'A';

    /** {@code app/cbl/COMEN01C.cbl:145} tests {@code (1:5)}, so the prefix is exactly five characters. */
    private static final String PLACEHOLDER_PREFIX = "DUMMY";

    /** {@code app/cbl/COMEN01C.cbl:131} - the literal moved to {@code WS-MESSAGE} on a bad option. */
    private static final String EXPECTED_INVALID_OPTION_MESSAGE =
            "Please enter a valid option number...";

    /** {@code app/cbl/COMEN01C.cbl:140} - note the TRAILING SPACE, which is part of the literal. */
    private static final String EXPECTED_ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /** {@code app/cbl/COMEN01C.cbl:173} {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}. */
    private static final String EXPECTED_SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * An admin-only option, needed because no populated slot of {@code COMEN02Y} carries {@code 'A'}.
     *
     * <p>The name is the literal that {@code app/cpy/COMEN02Y.cpy:69} declares and then COMMENTS OUT - the
     * label slot 8 carried while it really was administrator-only. Using the corpus's own disabled literal
     * keeps even this fixture evidence-based rather than invented.</p>
     */
    private static final MenuResponse.MainMenuOption ADMIN_ONLY_OPTION =
            new MenuResponse.MainMenuOption(1, "Transaction Add (Admin Only)       ", "COTRN02C", 'A');

    /**
     * A placeholder option, needed because no populated slot targets a {@code DUMMY} program. The name is
     * slot one's real {@code COMEN02Y} literal, so its truncation at the first space is corpus-derived.
     */
    private static final MenuResponse.MainMenuOption PLACEHOLDER_OPTION =
            new MenuResponse.MainMenuOption(1, "Account View                       ", "DUMMY01C", 'U');

    /** Captures the production {@code DEBUG} statement without altering the production code. */
    private ListAppender<ILoggingEvent> appender;

    /** The service logger, restored to its configured level after each test. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level the logger carried before the test raised it, restored afterwards. */
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
    // 1 - The clock is honoured, and its zone with it.
    // ==================================================================

    @Nested
    @DisplayName("1. The injected clock supplies both fields, and its zone decides the local value")
    class ClockIsHonoured {

        @Test
        @DisplayName("the canonical instant renders exactly one date and one time, with nothing ambient")
        void theCanonicalInstantRendersExactly() {
            renderHeaderWith(FixedClockProvider.canonicalClock());

            assertThat(renderedDate())
                    .as("app/cbl/COMEN01C.cbl:221-225 assembles WS-CURDATE-MM-DD-YY from the single "
                            + "reading taken at :214. With the clock fixed at %s in %s the rendered value "
                            + "is decided entirely by the argument, which is the whole point of the "
                            + "change: before it, this assertion could not be written",
                            FixedClockProvider.CANONICAL_INSTANT, FixedClockProvider.CANONICAL_ZONE)
                    .isEqualTo("06/10/22");
            assertThat(renderedTime())
                    .as("and :227-231 assembles WS-CURTIME-HH-MM-SS from that same reading, twenty-four "
                            + "hour because WS-CURTIME group of app/cpy/CSDAT01Y.cpy carries no meridiem")
                    .isEqualTo("19:27:53");
        }

        @Test
        @DisplayName("the same instant in a different zone renders a different local date and time")
        void theZoneOfTheClockDecidesTheLocalValue() {
            renderHeaderWith(FixedClockProvider.fixedClock(
                    FixedClockProvider.CANONICAL_INSTANT, ZoneId.of("Asia/Tokyo")));

            assertThat(renderedDate())
                    .as("the instant is unchanged; only the clock's zone differs. Nine hours east of UTC "
                            + "the same moment is already the eleventh, so a header that still read "
                            + "06/10/22 would prove the zone was being taken from the host rather than "
                            + "from the clock")
                    .isEqualTo("06/11/22");
            assertThat(renderedTime()).isEqualTo("04:27:53");
        }

        @Test
        @DisplayName("a zone west of UTC likewise moves the time without moving the date")
        void aWesternZoneMovesTheTimeOnly() {
            renderHeaderWith(FixedClockProvider.fixedClock(
                    FixedClockProvider.CANONICAL_INSTANT, ZoneId.of("America/Los_Angeles")));

            assertThat(renderedTime())
                    .as("seven hours west during June daylight time, which the zone rules supply and the "
                            + "test does not hard code as a fixed offset")
                    .isEqualTo("12:27:53");
            assertThat(renderedDate()).isEqualTo("06/10/22");
        }

        @Test
        @DisplayName("repeated calls on one fixed clock produce byte-identical furniture")
        void repeatedCallsAreIdentical() {
            final MainMenuService service = serviceOn(FixedClockProvider.canonicalClock());

            service.getMainMenu(UserType.USER);
            service.getMainMenu(UserType.USER);

            final List<ILoggingEvent> events = headerEvents();
            assertThat(events)
                    .as("two calls, two header statements")
                    .hasSize(2);
            assertThat(argumentsOf(events.get(0)))
                    .as("an ambient clock made two calls differ whenever a second elapsed between them, "
                            + "which is exactly the non-determinism Rule 1 Clause A forbids. With the "
                            + "clock injected the two are the same value, not merely the same shape")
                    .isEqualTo(argumentsOf(events.get(1)));
        }
    }

    // ==================================================================
    // 2 - Boundaries: midnight, the two-digit year, and the pairing of
    //     the two fields that one MOVE produced.
    // ==================================================================

    @Nested
    @DisplayName("2. Boundaries: midnight, the reduced year, and the single reading that feeds both fields")
    class Boundaries {

        @Test
        @DisplayName("an instant that is midnight in the clock's zone but not in UTC renders the later day")
        void midnightIsResolvedInTheClocksZone() {
            renderHeaderWith(FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-10T15:00:00Z"), ZoneId.of("Asia/Tokyo")));

            assertThat(renderedTime())
                    .as("15:00Z is exactly 00:00 the next day in Tokyo, the sharpest available case: the "
                            + "date and the time disagree about the calendar day unless both are resolved "
                            + "in the same zone")
                    .isEqualTo("00:00:00");
            assertThat(renderedDate()).isEqualTo("06/11/22");
        }

        @Test
        @DisplayName("the last second of a day and the first of the next never mix their fields")
        void theTwoFieldsCannotStraddleMidnight() {
            renderHeaderWith(FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-10T23:59:59Z"), ZoneOffset.UTC));
            assertThat(renderedDate()).isEqualTo("06/10/22");
            assertThat(renderedTime()).isEqualTo("23:59:59");

            this.reset();

            renderHeaderWith(FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-11T00:00:00Z"), ZoneOffset.UTC));
            assertThat(renderedDate())
                    .as("app/cbl/COMEN01C.cbl:214 is one MOVE FUNCTION CURRENT-DATE feeding both groups, "
                            + "so the source could not produce a date from one side of midnight and a "
                            + "time from the other. The Java paragraph takes one LocalDateTime for the "
                            + "same reason, and these two cases one second apart are what prove it")
                    .isEqualTo("06/11/22");
            assertThat(renderedTime()).isEqualTo("00:00:00");
        }

        @ParameterizedTest(name = "{0} renders as {1}, the reduced two-digit year being {2}")
        @CsvSource({
            "2000-01-01T00:00:00Z, 01/01/00, 00",
            "2007-03-09T08:05:04Z, 03/09/07, 07",
            "2022-06-10T19:27:53Z, 06/10/22, 22",
            "2099-12-31T23:59:59Z, 12/31/99, 99",
        })
        @DisplayName("the two-digit year is the last two digits of the four, as WS-CURDATE-YY was")
        void theYearIsReducedToItsLastTwoDigits(
                final String instant, final String expectedDate, final String expectedYear) {

            renderHeaderWith(FixedClockProvider.fixedClock(Instant.parse(instant), ZoneOffset.UTC));

            assertThat(renderedDate())
                    .as("WS-CURDATE-YY of app/cpy/CSDAT01Y.cpy is two bytes, and the source filled it "
                            + "from positions three and four of the eight-character intrinsic result. A "
                            + "yy pattern reproduces that, including the century-boundary case where the "
                            + "reduced year is 00 and a naive modulo-100 rendering would emit a single 0")
                    .isEqualTo(expectedDate)
                    .endsWith(expectedYear);
        }

        @Test
        @DisplayName("both fields keep their fixed source widths, eight bytes each")
        void bothFieldsKeepTheirSourceWidths() {
            renderHeaderWith(FixedClockProvider.canonicalClock());

            assertThat(renderedDate())
                    .as("CURDATEI PIC X(8) of app/cpy-bms/COMEN01.CPY, and MM/dd/yy is exactly eight")
                    .hasSize(8);
            assertThat(renderedTime())
                    .as("CURTIMEI PIC X(8) at app/cpy-bms/COMEN01.CPY:54, and HH:mm:ss is exactly eight. "
                            + "A single-digit hour must therefore be zero padded, which HH does and H "
                            + "would not")
                    .hasSize(8);

            this.reset();

            renderHeaderWith(FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-10T04:05:06Z"), ZoneOffset.UTC));
            assertThat(renderedTime()).isEqualTo("04:05:06").hasSize(8);
            assertThat(renderedDate()).isEqualTo("06/10/22").hasSize(8);
        }

        private void reset() {
            MainMenuServiceTest.this.appender.list.clear();
        }
    }

    // ==================================================================
    // 3 - The rest of the header, and locale independence.
    // ==================================================================

    @Nested
    @DisplayName("3. The four literal fields, and a rendering that no default locale can move")
    class LiteralFieldsAndLocale {

        @Test
        @DisplayName("the header carries the transaction, the program and both titles at their source widths")
        void theLiteralFieldsAreCarriedVerbatim() {
            renderHeaderWith(FixedClockProvider.canonicalClock());

            final Object[] arguments = argumentsOf(singleHeaderEvent());

            assertThat(arguments)
                    .as("app/cbl/COMEN01C.cbl:216-219 moves four literals into the map before the two "
                            + "assembled fields, so the statement carries six arguments in that order")
                    .hasSize(6);
            assertThat(arguments[0]).isEqualTo(EXPECTED_TRANSACTION_ID);
            assertThat(arguments[1]).isEqualTo(EXPECTED_PROGRAM_NAME);
            assertThat(arguments[2]).isEqualTo(EXPECTED_TITLE_01);
            assertThat(arguments[3]).isEqualTo(EXPECTED_TITLE_02);
            assertThat((String) arguments[2])
                    .as("the titles are forty-byte screen furniture and their padding is part of the "
                            + "value, so neither may be trimmed on the way into the log")
                    .hasSize(40);
            assertThat((String) arguments[3]).hasSize(40);
        }

        @Test
        @DisplayName("the statement is parameterised, so no header value is concatenated into the message")
        void theStatementIsParameterised() {
            renderHeaderWith(FixedClockProvider.canonicalClock());

            final ILoggingEvent event = singleHeaderEvent();

            assertThat(event.getMessage())
                    .as("the raw pattern must still contain its placeholders: a concatenated message "
                            + "would defeat the structured JSON encoding the observability layer relies "
                            + "on, and Clause A asks for structured logs rather than assembled strings")
                    .contains("{}")
                    .doesNotContain("06/10/22");
            assertThat(event.getFormattedMessage())
                    .as("while the formatted form is what a reader sees")
                    .contains("date=06/10/22")
                    .contains("time=19:27:53");
            assertThat(event.getLevel())
                    .as("header furniture is diagnostic, not operational, so it belongs at DEBUG")
                    .isEqualTo(Level.DEBUG);
        }

        @Test
        @DisplayName("a default locale with non-Latin digits cannot change the rendered digits")
        void theRenderingIsLocaleIndependent() {
            final Locale original = Locale.getDefault();
            try {
                // Locale.of replaces the deprecated Locale constructor; -Xlint:all -Werror rejects the
                // latter. The extension asks for Eastern Arabic digits, which a locale-sensitive
                // formatter would emit instead of ASCII.
                Locale.setDefault(Locale.of("ar", "EG", "u-nu-arab"));

                renderHeaderWith(FixedClockProvider.canonicalClock());

                assertThat(renderedDate())
                        .as("both formatters are built with Locale.ROOT, so the digits cannot follow the "
                                + "platform default. Without that argument this assertion fails, which "
                                + "is what makes it worth writing: the header feeds a fixed-width "
                                + "eight-byte field that only ASCII digits fit")
                        .isEqualTo("06/10/22")
                        .matches("[0-9]{2}/[0-9]{2}/[0-9]{2}");
                assertThat(renderedTime())
                        .as("and the time likewise, character class spelled out as ASCII rather than as "
                                + "\\\\d, which matches any Unicode decimal digit and would pass on "
                                + "Eastern Arabic numerals too")
                        .isEqualTo("19:27:53")
                        .matches("[0-9]{2}:[0-9]{2}:[0-9]{2}");
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    // ==================================================================
    // 4 - The seam itself: guarded, non-public, and holding no state.
    // ==================================================================

    @Nested
    @DisplayName("4. The seam: a guarded, package-private constructor over an immutable clock field")
    class TheSeam {

        @Test
        @DisplayName("a null clock is refused, naming the argument")
        void aNullClockIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the guard runs before the option table is examined, so the first thing wrong is "
                            + "the first thing reported")
                    .isThrownBy(() -> construct(null, List.of(SAMPLE_OPTION)))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("the seam is package-private and the production constructor stays public")
        void theSeamIsNotPartOfThePublicSurface() {
            final Constructor<?> seam = seamConstructor();

            assertThat(Modifier.isPublic(seam.getModifiers()))
                    .as("a public clock argument would invite a caller to supply one in production, "
                            + "where the bean must read real time")
                    .isFalse();
            assertThat(Modifier.isPrivate(seam.getModifiers()))
                    .as("and private would put it out of reach of the tests it exists for")
                    .isFalse();

            assertThat(publicNoArgConstructor())
                    .as("the container selects the no-argument constructor deterministically because no "
                            + "constructor is annotated for injection; removing it would break wiring")
                    .isNotNull();
        }

        @Test
        @DisplayName("the production constructor still wires and renders a well-formed header")
        void theProductionConstructorStillWorks() {
            final MainMenuService production = new MainMenuService();

            production.getMainMenu(UserType.ADMIN);

            assertThat(renderedDate())
                    .as("the no-argument path defaults to the system clock, so the value cannot be "
                            + "pinned - but its shape can, and this is the assertion that would catch "
                            + "the delegation being wired to the wrong constructor")
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(renderedTime()).matches("\\d{2}:\\d{2}:\\d{2}");
        }

        @Test
        @DisplayName("the clock is held in a final field and no static field is mutable")
        void theClockIsImmutableStateAndNothingStaticIsMutable() {
            final Field clockField = Arrays.stream(MainMenuService.class.getDeclaredFields())
                    .filter(field -> field.getType() == Clock.class)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "MainMenuService declares no Clock field, so the header cannot be reading "
                                    + "from an injected clock"));

            assertThat(Modifier.isFinal(clockField.getModifiers()))
                    .as("a reassignable clock would reintroduce the very non-determinism this removes")
                    .isTrue();
            assertThat(Modifier.isStatic(clockField.getModifiers()))
                    .as("and a static one would be shared across every instance, so two tests could not "
                            + "hold different clocks")
                    .isFalse();

            assertThat(Arrays.stream(MainMenuService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .toList())
                    .as("Clause B forbids global mutable state; the statics here are the source literals "
                            + "and the two immutable formatters")
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s is final", field.getName())
                            .isTrue());
        }
    }

    // ==================================================================
    // 5. The option table, read from COMEN02Y rather than retyped.
    // ==================================================================

    @Nested
    @DisplayName("5. The canonical table matches app/cpy/COMEN02Y.cpy, comments excluded")
    class TheOptionTableMatchesTheCopybook {

        @Test
        @DisplayName("the table holds the ten slots the copybook populates, not its twelve subscripts")
        void theTableHoldsTheCopybooksCount() {
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);

            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .as("app/cpy/COMEN02Y.cpy:L21 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE %d",
                            copybook.declaredCount())
                    .hasSize(copybook.declaredCount());
            assertThat(copybook.declaredCount())
                    .as("the OCCURS %d capacity is not a bound; its spare subscripts are unpopulated",
                            copybook.occursCapacity())
                    .isEqualTo(10)
                    .isNotEqualTo(copybook.occursCapacity());
        }

        @ParameterizedTest(name = "slot {0} matches app/cpy/COMEN02Y.cpy exactly")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each slot carries the number, padded name, program and user-type byte declared")
        void eachSlotMatchesTheCopybook(final int slot) {
            // Read from the copybook, not retyped: CDEMO-MENU-OPT-NAME is PIC X(35) whose padding lives
            // inside the quoted literal, and a retyped fixture both loses that padding to whitespace
            // trimming and asserts the implementation against a second copy of the same transcription.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);
            final MenuResponse.MainMenuOption option = MenuResponse.MAIN_MENU_OPTIONS.get(slot - 1);

            assertThat(option.optionNumber()).isEqualTo(copybook.slot(slot).number());
            assertThat(option.optionName())
                    .isEqualTo(copybook.nameOf(slot))
                    .hasSize(OPTION_NAME_WIDTH);
            assertThat(option.programName()).isEqualTo(copybook.programOf(slot));
            assertThat(String.valueOf(option.userTypeCode()))
                    .as("CDEMO-MENU-OPT-USRTYPE is PIC X(01) and drives the admin-only gate")
                    .isEqualTo(copybook.userTypeOf(slot).orElseThrow());
        }

        @Test
        @DisplayName("no populated slot is admin-only, so the 'A' gate cannot fire on the canonical table")
        void noPopulatedSlotIsAdminOnly() {
            // app/cpy/COMEN02Y.cpy:L68-L69 declares slot 8's name TWICE: a commented-out
            // 'Transaction Add (Admin Only)       ' at :69 and the active
            // 'Transaction Add                    ' at :70, with an active user-type byte of 'U'. Slot 8
            // was therefore opened to all users and its label edited, leaving the old label as a comment.
            // The gate at app/cbl/COMEN01C.cbl:136-137 is consequently live code whose triggering data was
            // deliberately removed from the table. It is retained rather than deleted under AAP 0.7.3.7
            // and 0.8.2, and is reachable only through the option-table seam - which is what group 7 uses.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);

            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .as("the Java table must not invent an admin-only option the copybook does not declare")
                    .noneMatch(option -> option.userTypeCode() == ADMIN_ONLY_OPTION_CODE);
            for (final MenuOptionCopybook.Slot slot : copybook.slots()) {
                assertThat(copybook.userTypeOf(slot.number())).contains("U");
            }
            assertThat(copybook.nameOf(8))
                    .as("the disabled :69 literal must not be the value that reaches the table")
                    .doesNotContain("Admin Only");
        }

        @Test
        @DisplayName("no populated slot is a placeholder, so the DUMMY branch also needs the seam")
        void noPopulatedSlotIsAPlaceholder() {
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);

            for (final MenuOptionCopybook.Slot slot : copybook.slots()) {
                assertThat(copybook.programOf(slot.number()))
                        .as("slot %d", slot.number())
                        .doesNotStartWith(PLACEHOLDER_PREFIX);
            }
            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .noneMatch(option -> option.programName().startsWith(PLACEHOLDER_PREFIX));
        }

        @Test
        @DisplayName("labels render as a zero-padded number, '. ', the name, padded to the 40-byte slot")
        void labelsRenderAsCobolAssemblesThem() {
            // app/cbl/COMEN01C.cbl:48 declares WS-MENU-OPT-TXT PIC X(40) and clears it to SPACES before
            // each STRING, and app/cpy-bms/COMEN01.CPY:182 declares OPTN001O PIC X(40). The assembled line
            // is therefore 2 + 2 + 35 = 39 characters of content inside a 40-byte field, so exactly one
            // trailing pad byte survives. This service carries that fixed width into the payload.
            //
            // AdminMenuService reaches the OPPOSITE conclusion on the identical 40-byte field, stripping
            // the padding instead, and each service states its own rationale in its own Javadoc. Both are
            // defensible; the package states no governing rule. Rendering is observable behaviour that the
            // AAP freezes, and no finding in this review concerns it, so the difference is asserted as it
            // stands and reported as an out-of-scope observation rather than normalised in either
            // direction.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);
            final List<String> labels = new MainMenuService().getMainMenu(UserType.USER).optionLabels();

            assertThat(labels).hasSize(copybook.declaredCount());
            for (int slot = 1; slot <= copybook.declaredCount(); slot++) {
                final String assembled = String.format(Locale.ROOT, "%02d", slot) + ". "
                        + copybook.nameOf(slot);
                assertThat(labels.get(slot - 1))
                        .as("slot %d: :250 PIC 9(02), :251 '. ', :252 DELIMITED BY SIZE, into PIC X(40)",
                                slot)
                        .isEqualTo(assembled + " ".repeat(SCREEN_SLOT_WIDTH - assembled.length()))
                        .hasSize(SCREEN_SLOT_WIDTH);
            }
            assertThat(labels.get(9))
                    .as("slot ten proves the two-digit rendering is not a one-digit accident")
                    .startsWith("10. Bill Payment");
        }

        @Test
        @DisplayName("the two menu services render the same 40-byte field differently, and that is asserted")
        void theTwoServicesRenderTheSlotDifferently() {
            // Pinned so that a later "consistency" edit to either service fails here and has to be a
            // deliberate, justified decision rather than an incidental one. The content is identical; only
            // the trailing padding differs.
            final String mainLabel = new MainMenuService().getMainMenu(UserType.USER).optionLabels().get(0);

            assertThat(mainLabel)
                    .as("MainMenuService carries the full PIC X(40) slot")
                    .hasSize(SCREEN_SLOT_WIDTH)
                    .isNotEqualTo(mainLabel.stripTrailing());
            assertThat(mainLabel.stripTrailing())
                    .as("the content itself is untouched: no case folding, no internal whitespace change")
                    .isEqualTo("01. Account View");
        }

        @Test
        @DisplayName("the sign-on target is always COSGN00C, because CDEMO-TO-PROGRAM has no counterpart")
        void theSignOnTargetIsAlwaysTheSignOnProgram() {
            // app/cbl/COMEN01C.cbl:172-174 defaults CDEMO-TO-PROGRAM to 'COSGN00C' only when it is SPACES
            // or LOW-VALUES. That field is COMMAREA routing state, which AAP 0.5.2.4 records as having no
            // stateless counterpart, so the Java form resolves unconditionally to the same program. The
            // observable outcome is identical for every request the source could have served from a menu.
            assertThat(new MainMenuService().getMainMenu(UserType.USER).signOnTarget())
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
            assertThat(new MainMenuService().getMainMenu(UserType.ADMIN).signOnTarget())
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
        }
    }

    // ==================================================================
    // 6. RECEIVE-MAP and PROCESS-ENTER-KEY: normalisation, then bounds.
    // ==================================================================

    @Nested
    @DisplayName("6. Selection right-justifies, substitutes zero for space, then bounds-checks")
    class SelectionNormalisationAndBounds {

        @ParameterizedTest(name = "[{0}] normalises to option {1}")
        @CsvSource(delimiter = '|', value = {
            "1|1", "01|1", " 1|1", "1 |1", "9|9", "09|9", "10|10", "5|5", "05|5",
        })
        @DisplayName("every representation of an in-range option resolves to the same option number")
        void everyRepresentationResolves(final String raw, final int expected) {
            final MainMenuService.MenuSelection selection =
                    new MainMenuService().selectOption(raw, UserType.USER);

            assertThat(selection.optionNumber())
                    .as(":146-:151 scan back over trailing spaces, right-justify into PIC X(2), then"
                            + " substitute '0' for each remaining space before the numeric test")
                    .isEqualTo(expected);
            assertThat(selection.targetProgram())
                    .isEqualTo(MenuResponse.MAIN_MENU_OPTIONS.get(expected - 1).programName());
            assertThat(selection.placeholder()).isFalse();
            assertThat(selection.message()).isEmpty();
        }

        @ParameterizedTest(name = "[{0}] is rejected with the invalid-option message")
        @ValueSource(strings = {"0", "00", " 0", "0 ", "11", "12", "13", "99", "ab", "a", "1a", "a1",
            "-1", "+1", ".5", "  ", " ", ""})
        @DisplayName("zero, out-of-range and non-numeric content are all rejected the same way")
        void unusableContentIsRejected(final String raw) {
            assertInvalidOption(() -> new MainMenuService().selectOption(raw, UserType.USER));
        }

        @Test
        @DisplayName("option 11 and 12 are refused even though the copybook's OCCURS reaches 12")
        void theSpareSubscriptsAreNotSelectable() {
            // The bound at :150 is CDEMO-MENU-OPT-COUNT, which is 10. The OCCURS 12 capacity holds two
            // unpopulated subscripts, and selecting one in COBOL would have read uninitialised storage.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);
            assertThat(copybook.occursCapacity()).isEqualTo(12);

            assertInvalidOption(() -> new MainMenuService().selectOption("11", UserType.ADMIN));
            assertInvalidOption(() -> new MainMenuService().selectOption("12", UserType.ADMIN));
        }

        @ParameterizedTest(name = "[{0}] exceeds PIC X(2) and is refused before any parsing")
        @ValueSource(strings = {"111", "0001", "  1", "abc", "1 1"})
        @DisplayName("content wider than the declared field is refused outright")
        void contentWiderThanTheFieldIsRefused(final String raw) {
            assertThat(raw.length())
                    .as("the fixture must actually exceed the declared width to discriminate")
                    .isGreaterThan(OPTION_INPUT_LENGTH);

            assertInvalidOption(() -> new MainMenuService().selectOption(raw, UserType.USER));
        }

        @Test
        @DisplayName("a null field becomes two spaces and then fails the bounds check as option zero")
        void aNullFieldBecomesSpacesAndThenFailsBounds() {
            assertInvalidOption(() -> new MainMenuService().selectOption(null, UserType.USER));
        }

        @Test
        @DisplayName("a tab is NOT a space: only ' ' is scanned back over and only ' ' becomes '0'")
        void aTabIsNotASpace() {
            // The scan and the substitution both test the literal space character, so a tab survives into
            // the numeric test and fails it. This discriminates a faithful character test from a
            // convenient strip()/isWhitespace() rewrite, which would have accepted "1\t" as option 1.
            assertInvalidOption(() -> new MainMenuService().selectOption("1\t", UserType.USER));
            assertInvalidOption(() -> new MainMenuService().selectOption("\t1", UserType.USER));
        }

        @Test
        @DisplayName("the rejection is a ValidationException naming the option field")
        void theRejectionNamesTheOptionField() {
            final ValidationException rejection = catchValidation(
                    () -> new MainMenuService().selectOption("99", UserType.USER));

            assertThat(rejection.getFieldName()).isEqualTo("option");
            assertThat(rejection.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(rejection.getMessage()).isEqualTo(EXPECTED_INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("a null user type is refused, because the role claim replaces CDEMO-USER-TYPE")
        void aNullUserTypeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService().selectOption("1", null))
                    .withMessageContaining("userType");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService().getMainMenu(null))
                    .withMessageContaining("userType");
        }

        @Test
        @DisplayName("the user type is validated BEFORE the option field, as MAIN-PARA runs first")
        void theUserTypeIsValidatedBeforeTheOptionField() {
            // selectOption performs MAIN-PARA, then RECEIVE-MAP, then PROCESS-ENTER-KEY. A null user type
            // with an also-invalid option must therefore raise IllegalArgumentException, not
            // ValidationException; the order is observable and is part of the reproduced control flow.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService().selectOption("zz", null))
                    .withMessageContaining("userType");
        }

        @Test
        @DisplayName("the upper bound follows the supplied table, not the copybook constant")
        void theUpperBoundFollowsTheSuppliedTable() {
            final MainMenuService oneOption = construct(
                    FixedClockProvider.canonicalClock(), List.of(MenuResponse.MAIN_MENU_OPTIONS.get(0)));

            assertThat(oneOption.selectOption("1", UserType.USER).optionNumber()).isEqualTo(1);
            assertInvalidOption(() -> oneOption.selectOption("2", UserType.USER));
        }
    }

    // ==================================================================
    // 7. The admin-only gate, reachable only through the seam.
    // ==================================================================

    @Nested
    @DisplayName("7. The 'A' gate: live corpus code whose triggering data COMEN02Y no longer carries")
    class TheAdminOnlyGate {

        @Test
        @DisplayName("a standard user selecting an admin-only option is refused with the access message")
        void aStandardUserIsRefusedAnAdminOnlyOption() {
            final MainMenuService gated =
                    construct(FixedClockProvider.canonicalClock(), List.of(ADMIN_ONLY_OPTION));

            assertThatExceptionOfType(ValidationException.class)
                    .as("app/cbl/COMEN01C.cbl:136-137 IF CDEMO-USRTYP-USER AND"
                            + " CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'")
                    .isThrownBy(() -> gated.selectOption("1", UserType.USER))
                    .withMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("the access message keeps its trailing space, as the COBOL literal declares it")
        void theAccessMessageKeepsItsTrailingSpace() {
            // app/cbl/COMEN01C.cbl:140 MOVE 'No access - Admin Only option... ' - the literal ends with a
            // space, and WS-MESSAGE is a fixed-width field, so the space is part of the value. Trimming it
            // would be a silent change to an observable string.
            assertThat(EXPECTED_ADMIN_ONLY_MESSAGE)
                    .endsWith(" ")
                    .isNotEqualTo(EXPECTED_ADMIN_ONLY_MESSAGE.strip());

            final MainMenuService gated =
                    construct(FixedClockProvider.canonicalClock(), List.of(ADMIN_ONLY_OPTION));
            assertThat(catchValidation(() -> gated.selectOption("1", UserType.USER)).getMessage())
                    .isEqualTo(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("an administrator selecting the same option resolves normally")
        void anAdministratorMaySelectAnAdminOnlyOption() {
            final MainMenuService gated =
                    construct(FixedClockProvider.canonicalClock(), List.of(ADMIN_ONLY_OPTION));

            final MainMenuService.MenuSelection selection = gated.selectOption("1", UserType.ADMIN);

            assertThat(selection.optionNumber()).isEqualTo(1);
            assertThat(selection.targetProgram()).isEqualTo("COTRN02C");
            assertThat(selection.message())
                    .as("the gate tests CDEMO-USRTYP-USER, so an administrator is unaffected")
                    .isEmpty();
        }

        @Test
        @DisplayName("the gate refuses ONLY on the 'A' byte; a 'U' option is open to both user classes")
        void theGateRefusesOnlyOnTheAdminByte() {
            final MenuResponse.MainMenuOption userOption = MenuResponse.MAIN_MENU_OPTIONS.get(0);
            final MainMenuService open =
                    construct(FixedClockProvider.canonicalClock(), List.of(userOption));

            assertThat(open.selectOption("1", UserType.USER).optionNumber()).isEqualTo(1);
            assertThat(open.selectOption("1", UserType.ADMIN).optionNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("a standard user's presented menu OMITS admin-only options entirely")
        void aStandardUsersMenuOmitsAdminOnlyOptions() {
            final MainMenuService mixed = construct(FixedClockProvider.canonicalClock(),
                    List.of(MenuResponse.MAIN_MENU_OPTIONS.get(0), ADMIN_ONLY_OPTION));

            assertThat(mixed.getMainMenu(UserType.USER).menu().getOptions())
                    .as("an option the user may not select is not offered")
                    .containsExactly(MenuResponse.MAIN_MENU_OPTIONS.get(0));
            assertThat(mixed.getMainMenu(UserType.USER).optionLabels()).hasSize(1);
        }

        @Test
        @DisplayName("an administrator's presented menu retains them")
        void anAdministratorsMenuRetainsAdminOnlyOptions() {
            final MainMenuService mixed = construct(FixedClockProvider.canonicalClock(),
                    List.of(MenuResponse.MAIN_MENU_OPTIONS.get(0), ADMIN_ONLY_OPTION));

            assertThat(mixed.getMainMenu(UserType.ADMIN).menu().getOptions())
                    .containsExactly(MenuResponse.MAIN_MENU_OPTIONS.get(0), ADMIN_ONLY_OPTION);
            assertThat(mixed.getMainMenu(UserType.ADMIN).optionLabels()).hasSize(2);
        }

        @Test
        @DisplayName("the canonical menu is identical for both user classes, since no slot is admin-only")
        void theCanonicalMenuIsIdenticalForBothUserClasses() {
            final MainMenuService production = new MainMenuService();

            assertThat(production.getMainMenu(UserType.USER).menu().getOptions())
                    .as("COMEN02Y declares every populated slot as 'U', so the filter removes nothing")
                    .isEqualTo(production.getMainMenu(UserType.ADMIN).menu().getOptions())
                    .containsExactlyElementsOf(MenuResponse.MAIN_MENU_OPTIONS);
        }
    }

    // ==================================================================
    // 8. The DUMMY branch, and the space DELIMITED BY SPACE loses.
    // ==================================================================

    @Nested
    @DisplayName("8. The DUMMY notice interpolates a SPACE-TRUNCATED name, losing a space as COBOL does")
    class ThePlaceholderNoticeReproducesTheMissingSpace {

        @Test
        @DisplayName("the notice concatenates the truncated name directly, so the space is genuinely lost")
        void theNoticeLosesASpaceExactlyAsTheCobolDoes() {
            // app/cbl/COMEN01C.cbl:159-162
            //     STRING 'This option '                 DELIMITED BY SIZE
            //            CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY SPACE
            //            'is coming soon ...'           DELIMITED BY SIZE
            //       INTO WS-MESSAGE
            // DELIMITED BY SPACE truncates 'Account View                       ' at its first space,
            // yielding 'Account', which is then butted straight against 'is coming soon ...'. The missing
            // space is a genuine legacy quirk and is reproduced, not repaired. Contrast AdminMenuService,
            // whose equivalent lines at COADM01C.cbl:150-151 are COMMENTED OUT, so its notice is flat and
            // keeps the space from 'This option '.
            final MainMenuService placeholder = construct(
                    FixedClockProvider.canonicalClock(), List.of(PLACEHOLDER_OPTION));

            final MainMenuService.MenuSelection selection =
                    placeholder.selectOption("1", UserType.USER);

            assertThat(selection.message())
                    .isEqualTo("This option Accountis coming soon ...")
                    .as("the absence of a space before 'is' is the quirk under test")
                    .doesNotContain("Account is coming");
        }

        @Test
        @DisplayName("the placeholder branch RETAINS the target program, unlike the administrator menu")
        void thePlaceholderBranchRetainsTheTargetProgram() {
            // A deliberate asymmetry between the two services, pinned rather than normalised:
            // MainMenuService publishes the DUMMY target alongside the notice, AdminMenuService publishes
            // an empty target. Both reproduce their own program's flag handling; neither is a defect.
            final MainMenuService placeholder = construct(
                    FixedClockProvider.canonicalClock(), List.of(PLACEHOLDER_OPTION));

            final MainMenuService.MenuSelection selection =
                    placeholder.selectOption("1", UserType.USER);

            assertThat(selection.placeholder()).isTrue();
            assertThat(selection.targetProgram())
                    .as("app/cbl/COMEN01C.cbl:145 tests (1:5) but does not clear the field")
                    .isEqualTo("DUMMY01C");
            assertThat(selection.optionName()).isEqualTo(PLACEHOLDER_OPTION.optionName());
        }

        @ParameterizedTest(name = "name [{0}] truncates to [{1}]")
        @CsvSource(delimiter = '|', value = {
            "Account View                       |Account",
            "Transaction Reports                |Transaction",
            "Bill Payment                       |Bill",
            "SingleWord                         |SingleWord",
        })
        @DisplayName("the name is truncated at its FIRST space, and a name with none is used whole")
        void theNameIsTruncatedAtTheFirstSpace(final String optionName, final String truncated) {
            final MainMenuService placeholder = construct(FixedClockProvider.canonicalClock(),
                    List.of(new MenuResponse.MainMenuOption(1, optionName, "DUMMY01C", 'U')));

            assertThat(placeholder.selectOption("1", UserType.USER).message())
                    .isEqualTo("This option " + truncated + "is coming soon ...");
        }

        @Test
        @DisplayName("a name that is entirely padding truncates to nothing, leaving the two fragments")
        void aNameThatIsEntirelyPaddingTruncatesToNothing() {
            // DELIMITED BY SPACE on a field whose first character is a space contributes nothing at all.
            // The result is the two literals adjacent, which is what the source would emit.
            final MainMenuService placeholder = construct(FixedClockProvider.canonicalClock(),
                    List.of(new MenuResponse.MainMenuOption(1, " ".repeat(OPTION_NAME_WIDTH),
                            "DUMMY01C", 'U')));

            assertThat(placeholder.selectOption("1", UserType.USER).message())
                    .isEqualTo("This option is coming soon ...");
        }

        @ParameterizedTest(name = "target [{0}] is a placeholder: {1}")
        @CsvSource({
            "DUMMY, true", "DUMMY01C, true", "DUMMYXXX, true",
            "DUMM, false", "DUMMX01C, false", "dummy01C, false", "COACTVWC, false", "XDUMMY01, false",
        })
        @DisplayName("the guard tests the first five characters exactly, case-sensitively")
        void theGuardTestsTheFirstFiveCharactersExactly(final String program, final boolean placeholder) {
            final MainMenuService service = construct(FixedClockProvider.canonicalClock(),
                    List.of(new MenuResponse.MainMenuOption(1, "Probe Option                       ",
                            program, 'U')));

            final MainMenuService.MenuSelection selection = service.selectOption("1", UserType.USER);

            assertThat(selection.placeholder())
                    .as("COMEN01C:145 compares (1:5) against the literal 'DUMMY', case-sensitively")
                    .isEqualTo(placeholder);
            assertThat(selection.message())
                    .isEqualTo(placeholder ? "This option Probeis coming soon ..." : "");
        }

        @Test
        @DisplayName("the admin-only gate is applied BEFORE the placeholder guard")
        void theGateIsAppliedBeforeThePlaceholderGuard() {
            // COMEN01C evaluates the user-type gate at :136 and the DUMMY test at :145, and the latter sits
            // inside IF NOT ERR-FLG-ON. A standard user selecting an admin-only placeholder must therefore
            // see the access refusal, never the coming-soon notice.
            final MainMenuService gatedPlaceholder = construct(FixedClockProvider.canonicalClock(),
                    List.of(new MenuResponse.MainMenuOption(1, "Reserved Admin                     ",
                            "DUMMY01C", ADMIN_ONLY_OPTION_CODE)));

            assertThat(catchValidation(() -> gatedPlaceholder.selectOption("1", UserType.USER))
                    .getMessage())
                    .isEqualTo(EXPECTED_ADMIN_ONLY_MESSAGE);
            assertThat(gatedPlaceholder.selectOption("1", UserType.ADMIN).message())
                    .as("an administrator passes the gate and reaches the placeholder branch")
                    .isEqualTo("This option Reservedis coming soon ...");
        }
    }

    // ==================================================================
    // 9. The seam's remaining guards, and the two payload records' contracts.
    // ==================================================================

    @Nested
    @DisplayName("9. The seam and the payload records refuse exactly what their contracts document")
    class TheSeamAndPayloadContracts {

        @Test
        @DisplayName("a null option list is refused")
        void aNullOptionListIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), null))
                    .withMessageContaining("menuOptions");
        }

        @Test
        @DisplayName("an EMPTY list is ACCEPTED here, unlike AdminMenuService which refuses one")
        void anEmptyOptionListIsAccepted() {
            // A deliberate asymmetry between the two services, pinned rather than normalised. This service
            // documents an empty list as representing "a menu offering no options"; AdminMenuService
            // refuses one, citing COADM02Y's unconditional four populated slots. Both are defensible.
            final MainMenuService empty = construct(FixedClockProvider.canonicalClock(), List.of());

            assertThat(empty.getMainMenu(UserType.USER).menu().getOptions()).isEmpty();
            assertThat(empty.getMainMenu(UserType.USER).optionLabels()).isEmpty();
            assertInvalidOption(() -> empty.selectOption("1", UserType.USER));
        }

        @Test
        @DisplayName("a list longer than the ten populated slots is refused, capacity twelve notwithstanding")
        void anOverLongOptionListIsRefused() {
            final List<MenuResponse.MainMenuOption> tooMany = new ArrayList<>();
            for (int slot = 1; slot <= 11; slot++) {
                tooMany.add(new MenuResponse.MainMenuOption(slot, "Slot " + slot, "COACTVWC", 'U'));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("COMEN02Y populates 10; the OCCURS 12 capacity is not a bound")
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), tooMany))
                    .withMessageContaining("10");
        }

        @Test
        @DisplayName("a null element is refused and the message names its index")
        void aNullElementIsRefused() {
            final List<MenuResponse.MainMenuOption> withHole =
                    Arrays.asList(SAMPLE_OPTION, SAMPLE_OPTION, null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), withHole))
                    .withMessageContaining("index 2");
        }

        @Test
        @DisplayName("MainMenuScreen refuses a null menu, null labels and a blank sign-on target")
        void theScreenRecordRefusesItsNulls() {
            final MenuResponse<MenuResponse.MainMenuOption> menu =
                    MenuResponse.ofMainMenu(List.of(SAMPLE_OPTION));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            null, List.of("01. Account View"), EXPECTED_SIGN_ON_PROGRAM))
                    .withMessageContaining("menu");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            menu, null, EXPECTED_SIGN_ON_PROGRAM))
                    .withMessageContaining("optionLabels");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("COMEN01C:172-174 always resolves a target, so a blank one is a programming error")
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            menu, List.of("01. Account View"), "   "))
                    .withMessageContaining("signOnTarget");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            menu, List.of("01. Account View"), null))
                    .withMessageContaining("signOnTarget");
        }

        @Test
        @DisplayName("MainMenuScreen refuses a null label and a label count that disagrees with the menu")
        void theScreenRecordRefusesInconsistentLabels() {
            final MenuResponse<MenuResponse.MainMenuOption> menu =
                    MenuResponse.ofMainMenu(List.of(SAMPLE_OPTION));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            menu, Arrays.asList("01. Account View", null), EXPECTED_SIGN_ON_PROGRAM))
                    .withMessageContaining("index 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cbl/COMEN01C.cbl:238-239 renders exactly one line per option")
                    .isThrownBy(() -> new MainMenuService.MainMenuScreen(
                            menu, List.of(), EXPECTED_SIGN_ON_PROGRAM))
                    .withMessageContaining("options");
        }

        @Test
        @DisplayName("MainMenuScreen accepts a consistent triple and exposes it unmodifiably")
        void theScreenRecordAcceptsAConsistentTriple() {
            final MainMenuService.MainMenuScreen screen = new MainMenuService.MainMenuScreen(
                    MenuResponse.ofMainMenu(List.of(SAMPLE_OPTION)),
                    List.of("01. Account View"), EXPECTED_SIGN_ON_PROGRAM);

            assertThat(screen.optionLabels()).containsExactly("01. Account View");
            assertThat(screen.signOnTarget()).isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> screen.optionLabels().add("02. Injected"));
        }

        @ParameterizedTest(name = "option number {0} is outside PIC 9(02) minus zero and is refused")
        @ValueSource(ints = {0, -1, 100, 1000})
        @DisplayName("MenuSelection refuses an option number outside one through ninety-nine")
        void theSelectionRecordRefusesAnOutOfRangeNumber(final int optionNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("CDEMO-MENU-OPT-NUM is PIC 9(02) and COMEN01C:133 rejects zero")
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            optionNumber, "Account View", "COACTVWC", false, ""))
                    .withMessageContaining("optionNumber");
        }

        @Test
        @DisplayName("MenuSelection refuses a null name, a blank target and a null message")
        void theSelectionRecordRefusesItsNulls() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, null, "COACTVWC", false, ""))
                    .withMessageContaining("optionName");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, "Account View", "  ", false, ""))
                    .withMessageContaining("targetProgram");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, "Account View", null, false, ""))
                    .withMessageContaining("targetProgram");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the empty string, not null, represents the absence of a notice")
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, "Account View", "COACTVWC", false, null))
                    .withMessageContaining("message");
        }

        @Test
        @DisplayName("MenuSelection requires the placeholder flag and the message to be exact opposites")
        void theSelectionRecordCouplesTheFlagToTheMessage() {
            // A real target carries no notice and a placeholder always carries one, so the two can never
            // agree. This cross-field invariant is what stops a caller publishing a placeholder silently
            // or a real target with a spurious notice.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a real target with a notice")
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, "Account View", "COACTVWC", false, "This option Accountis coming soon ..."))
                    .withMessageContaining("must be empty for a real target");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a placeholder without a notice")
                    .isThrownBy(() -> new MainMenuService.MenuSelection(
                            1, "Account View", "DUMMY01C", true, ""))
                    .withMessageContaining("non-empty");

            assertThat(new MainMenuService.MenuSelection(1, "Account View", "COACTVWC", false, "")
                    .placeholder()).isFalse();
            assertThat(new MainMenuService.MenuSelection(1, "Account View", "DUMMY01C", true, "notice")
                    .message()).isEqualTo("notice");
        }

        @Test
        @DisplayName("the two-digit numeric test refuses a field of the wrong length, defensively")
        void theTwoDigitTestRefusesAWrongLength() {
            // RECEIVE-MAP always hands PROCESS-ENTER-KEY exactly two characters - it pads a shorter field
            // and refuses a longer one - so this length guard cannot be reached through the public surface.
            // It is nonetheless a documented part of the helper's contract, and the only honest way to
            // demonstrate it is to call the helper directly. Asserting it structurally instead would prove
            // the guard exists without proving what it does.
            assertThat(invokeIsTwoDigitNumber("1"))
                    .as("a one-character field is not two digits")
                    .isFalse();
            assertThat(invokeIsTwoDigitNumber("001")).isFalse();
            assertThat(invokeIsTwoDigitNumber("")).isFalse();
            assertThat(invokeIsTwoDigitNumber("01"))
                    .as("the guard must still accept the shape RECEIVE-MAP actually produces")
                    .isTrue();
            assertThat(invokeIsTwoDigitNumber("0a")).isFalse();
        }

        /**
         * Invokes the private static two-digit test directly.
         *
         * @param normalisedOption the candidate field content
         * @return the helper's verdict
         */
        private boolean invokeIsTwoDigitNumber(final String normalisedOption) {
            try {
                final Method test = MainMenuService.class
                        .getDeclaredMethod("isTwoDigitNumber", String.class);
                test.setAccessible(true);
                return (boolean) test.invoke(null, normalisedOption);
            } catch (final ReflectiveOperationException failure) {
                throw new AssertionError("MainMenuService declares no isTwoDigitNumber(String) helper, so"
                        + " the normalisation contract cannot be verified", failure);
            }
        }
    }

    // ==================================================================
    // Fixture and reflection helpers.
    // ==================================================================

    /**
     * Asserts that the supplied action is refused with the invalid-option message from
     * {@code app/cbl/COMEN01C.cbl:131}, naming the option field.
     *
     * @param action the invocation expected to be refused
     */
    private static void assertInvalidOption(final Runnable action) {
        MenuServiceTestSupport.assertInvalidOption(
                action, EXPECTED_INVALID_OPTION_MESSAGE, "option");
    }

    /**
     * Runs the supplied action and returns the {@link ValidationException} it threw.
     *
     * @param action the invocation expected to be refused
     * @return the thrown exception, for assertions on its payload
     */
    private static ValidationException catchValidation(final Runnable action) {
        return MenuServiceTestSupport.catchValidation(action);
    }

    /** Builds the bean on {@code clock} and renders one header, leaving the event in the appender. */
    private void renderHeaderWith(final Clock clock) {
        serviceOn(clock).getMainMenu(UserType.USER);
    }

    /** Builds the bean on {@code clock} over a single legitimate option. */
    private static MainMenuService serviceOn(final Clock clock) {
        return construct(clock, List.of(SAMPLE_OPTION));
    }

    /**
     * Invokes the package-private seam reflectively, unwrapping the cause so that a guard failure surfaces
     * as the {@link IllegalArgumentException} the caller would see rather than as a reflection wrapper.
     */
    private static MainMenuService construct(
            final Clock clock, final List<MenuResponse.MainMenuOption> options) {
        return MenuServiceTestSupport.construct(
                MainMenuService.class, seamConstructor(), clock, options);
    }

    private static Constructor<?> seamConstructor() {
        return MenuServiceTestSupport.seamConstructor(MainMenuService.class,
                "the clock cannot be fixed and the header furniture is unassertable");
    }

    private static Constructor<?> publicNoArgConstructor() {
        try {
            return MainMenuService.class.getConstructor();
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError("MainMenuService declares no public no-argument constructor", absent);
        }
    }

    /** The header statements captured so far, identified by the pattern the paragraph logs. */
    private List<ILoggingEvent> headerEvents() {
        return MenuServiceTestSupport.headerEvents(this.appender, "Main menu header:");
    }

    private ILoggingEvent singleHeaderEvent() {
        return MenuServiceTestSupport.singleHeaderEvent(headerEvents(),
                "exactly one header statement is expected; POPULATE-HEADER-INFO runs once per send");
    }

    private static Object[] argumentsOf(final ILoggingEvent event) {
        return MenuServiceTestSupport.argumentsOf(event,
                "the header statement is parameterised, so it must carry an argument array");
    }

    private String renderedDate() {
        return (String) argumentsOf(singleHeaderEvent())[DATE_ARGUMENT_INDEX];
    }

    private String renderedTime() {
        return (String) argumentsOf(singleHeaderEvent())[TIME_ARGUMENT_INDEX];
    }
}
