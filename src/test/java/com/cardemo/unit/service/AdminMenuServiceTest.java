/*
 * ******************************************************************
 * Program     : AdminMenuServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the administrator menu dispatch: the option
 *               field normalisation COADM01C performs before it parses
 *               (trailing-space scan, right justification, space-to-zero
 *               substitution), the bounds check that rejects zero and
 *               anything past the populated option count, the DUMMY
 *               placeholder guard, the sign-off target fallback, the
 *               option-label rendering, and the header furniture read
 *               from an injected Clock. Also pins the one place where
 *               this service legitimately DIVERGES from MainMenuService:
 *               COADM01C's coming-soon message omits the option name
 *               because the two lines that would supply it are commented
 *               out in the COBOL, whereas COMEN01C's are active.
 * Source      : app/cbl/COADM01C.cbl:112-129  (MAIN-PARA / RECEIVE-MAP)
 *               app/cbl/COADM01C.cbl:131      (invalid-option literal)
 *               app/cbl/COADM01C.cbl:134-145  (PROCESS-ENTER-KEY)
 *               app/cbl/COADM01C.cbl:138      (1:5 NOT = 'DUMMY')
 *               app/cbl/COADM01C.cbl:148-153  (coming-soon STRING with
 *                                              the option-name lines
 *                                              COMMENTED OUT at :150-151)
 *               app/cbl/COADM01C.cbl:179-184  (SEND-MENU-SCREEN)
 *               app/cbl/COADM01C.cbl:204-221  (POPULATE-HEADER-INFO)
 *               app/cbl/COADM01C.cbl:226-263  (BUILD-MENU-OPTIONS)
 *               app/cpy/COADM02Y.cpy          (4 populated option slots)
 *               app/cpy/CSDAT01Y.cpy          (WS-CURDATE / WS-CURTIME)
 *               app/cpy/COTTL01Y.cpy          (the two title literals)
 *               app/csd/CARDDEMO.CSD          (CA00 -> COADM01C) @ 7756d89
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
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.unit.model.MenuOptionCopybook;
import com.cardemo.unit.model.FixedClockProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
 * Behavioural verification of {@link AdminMenuService} against {@code app/cbl/COADM01C.cbl} at the frozen
 * anchor {@code 7756d89}.
 *
 * <p><strong>Why the assertions are phrased against the corpus and not against the service.</strong>
 * Every expectation in this class is derived from a COBOL locator, a copybook declaration or the CSD, and
 * each is cited on the test that carries it. Where an expectation could equally have been read off the
 * Java constants, the corpus form is preferred, so that a test cannot silently agree with a regression in
 * the code it is meant to guard.</p>
 *
 * <p><strong>The one divergence from {@code MainMenuService}, and why it is correct.</strong>
 * {@code COMEN01C.cbl:159-162} assembles its coming-soon notice from three fragments, the middle one being
 * the option name {@code DELIMITED BY SPACE}. {@code COADM01C.cbl:148-153} assembles the same notice from
 * the same skeleton, but the two lines that would contribute {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)} are
 * <em>commented out</em> - an asterisk in column seven at {@code :150} and {@code :151}. The administrator
 * message is therefore the flat literal {@code "This option is coming soon ..."}, with the single space
 * that {@code 'This option '} already carries, while the main menu message concatenates the truncated
 * option name directly onto {@code "This option "} and legitimately loses a space. Two different strings,
 * both faithful. {@link TheComingSoonNoticeIsFlatByCorpusMandate} pins this so that a well-meaning
 * "consistency" edit to either service fails here rather than in production.</p>
 *
 * <p><strong>Reaching the placeholder branch.</strong> No entry in the canonical administrator option
 * table names a program beginning {@code DUMMY} - all four target real {@code COUSR0nC} programs - so the
 * placeholder branch of {@code PROCESS-ENTER-KEY} is unreachable through the public no-argument
 * constructor. It is nonetheless live code in the corpus at {@code COADM01C.cbl:138}, retained under AAP
 * §0.7.3.7 and §0.8.2 rather than deleted, and the package-private {@code (Clock, List)} seam exists
 * precisely so that it can be exercised. These tests reach it that way and never by widening the
 * production surface.</p>
 *
 * <p><strong>Observing the header.</strong> {@code POPULATE-HEADER-INFO} has no payload field to write
 * into once the BMS map is gone, so {@code sendMenuScreen} emits the assembled header as the third
 * argument of a single parameterised {@code DEBUG} statement. Unlike {@code MainMenuService}, which
 * parameterises each header field separately, the administrator service assembles one string; these tests
 * therefore attach a logback {@link ListAppender} and read that argument, which keeps the assertion on the
 * value the service computed rather than on a re-rendering of it.</p>
 *
 * @see AdminMenuService
 * @see FixedClockProvider
 */
@DisplayName("AdminMenuService - app/cbl/COADM01C.cbl administrator menu dispatch")
class AdminMenuServiceTest {

    /** {@code app/csd/CARDDEMO.CSD} defines transaction {@code CA00} against program {@code COADM01C}. */
    private static final String EXPECTED_TRANSACTION_ID = "CA00";

    /** {@code app/cbl/COADM01C.cbl:L26} {@code PROGRAM-ID. COADM01C}. */
    private static final String EXPECTED_PROGRAM_NAME = "COADM01C";

    /** {@code app/cpy/COTTL01Y.cpy} {@code CCDA-TITLE01}, {@code PIC X(40)} including its padding. */
    private static final String EXPECTED_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code app/cpy/COTTL01Y.cpy} {@code CCDA-TITLE02}, {@code PIC X(40)} including its padding. */
    private static final String EXPECTED_TITLE_02 = "              CardDemo                  ";

    /** {@code app/cbl/COADM01C.cbl:131} - the literal moved to {@code WS-MESSAGE} on a bad option. */
    private static final String EXPECTED_INVALID_OPTION_MESSAGE =
            "Please enter a valid option number...";

    /**
     * {@code app/cbl/COADM01C.cbl:148-153} - the coming-soon notice with the option-name lines commented
     * out at {@code :150-151}, hence flat and hence carrying the space from {@code 'This option '}.
     */
    private static final String EXPECTED_COMING_SOON_MESSAGE = "This option is coming soon ...";

    /** {@code app/cbl/COADM01C.cbl:167} {@code XCTL PROGRAM('COSGN00C')} on the sign-off path. */
    private static final String EXPECTED_SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code app/cbl/COADM01C.cbl:138} tests {@code (1:5)}, so the prefix is exactly five characters. */
    private static final String PLACEHOLDER_PREFIX = "DUMMY";

    /** {@code app/cpy-bms/COADM01.CPY} {@code OPTIONI PIC X(2)}. */
    private static final int OPTION_FIELD_LENGTH = 2;

    /** {@code app/cpy/COADM02Y.cpy:L20} {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}. */
    private static final int POPULATED_OPTION_COUNT = 4;

    /** The frozen copybook that declares the administrator option table. */
    private static final String COPYBOOK_MEMBER = "COADM02Y";

    /** {@code app/cpy/COADM02Y.cpy} {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** The index of the assembled header within the {@code sendMenuScreen} argument array. */
    private static final int HEADER_ARGUMENT_INDEX = 2;

    /** A real, non-placeholder option: slot one of the canonical table. */
    private static final MenuResponse.AdminMenuOption REAL_OPTION =
            new MenuResponse.AdminMenuOption(1, "User List (Security)               ", "COUSR00C");

    /**
     * A placeholder option in the shape {@code COADM01C.cbl:138} tests for: the program name begins with
     * the five characters {@code DUMMY}. No canonical entry does, which is why the seam is required.
     */
    private static final MenuResponse.AdminMenuOption PLACEHOLDER_OPTION =
            new MenuResponse.AdminMenuOption(1, "Reserved Admin Function            ", "DUMMY01C");

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger serviceLogger;

    private Level restoreLevel;

    /**
     * Attaches a list appender to the service logger at {@code DEBUG}, because the assembled header exists
     * only as an argument of a debug statement once the BMS map is gone.
     */
    @BeforeEach
    void attachAppender() {
        this.serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AdminMenuService.class);
        this.restoreLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.serviceLogger.addAppender(this.appender);
    }

    /** Detaches the appender and restores the previous level so no test leaks logging configuration. */
    @AfterEach
    void detachAppender() {
        this.serviceLogger.detachAppender(this.appender);
        this.appender.stop();
        this.serviceLogger.setLevel(this.restoreLevel);
    }

    // ==================================================================
    // 1. The seam and its guards.
    // ==================================================================

    @Nested
    @DisplayName("1. The seam: package-private, guarded, and bounded by the populated option count")
    class TheSeamAndItsGuards {

        @Test
        @DisplayName("a null clock is refused, so no instance can fall back to ambient wall time")
        void aNullClockIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the whole point of the seam is that the clock is supplied, never defaulted")
                    .isThrownBy(() -> construct(null, List.of(REAL_OPTION)))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("a null option list is refused")
        void aNullOptionListIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), null))
                    .withMessageContaining("adminMenuOptions");
        }

        @Test
        @DisplayName("an EMPTY list is refused here, unlike MainMenuService which accepts one")
        void anEmptyOptionListIsRefused() {
            // A deliberate asymmetry between the two services, pinned rather than normalised:
            // app/cpy/COADM02Y.cpy:L20 populates four slots unconditionally and COADM01C has no path
            // that presents an empty administrator menu, whereas MainMenuService documents an empty list
            // as representing "a menu offering no options". Both are defensible; both are behaviour.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), List.of()))
                    .withMessageContaining("COADM02Y");
        }

        @Test
        @DisplayName("a list longer than the four populated slots is refused, capacity nine notwithstanding")
        void anOverLongOptionListIsRefused() {
            final List<MenuResponse.AdminMenuOption> tooMany = new ArrayList<>();
            for (int slot = 1; slot <= POPULATED_OPTION_COUNT + 1; slot++) {
                tooMany.add(new MenuResponse.AdminMenuOption(slot, "Slot " + slot, "COUSR00C"));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("COADM02Y.cpy populates 4; the OCCURS 9 capacity is not a bound")
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), tooMany))
                    .withMessageContaining(String.valueOf(POPULATED_OPTION_COUNT));
        }

        @Test
        @DisplayName("a null element is refused and the message names its index")
        void aNullElementIsRefused() {
            final List<MenuResponse.AdminMenuOption> withHole = Arrays.asList(REAL_OPTION, null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> construct(FixedClockProvider.canonicalClock(), withHole))
                    .withMessageContaining("index 1");
        }

        @Test
        @DisplayName("the seam is package-private, so it widens no production surface")
        void theSeamIsNotPartOfThePublicSurface() {
            final Constructor<?> seam = seamConstructor();

            assertThat(Modifier.isPublic(seam.getModifiers()))
                    .as("a public (Clock, List) constructor would let a caller replace the option table")
                    .isFalse();
            assertThat(Modifier.isProtected(seam.getModifiers())).isFalse();
            assertThat(Modifier.isPrivate(seam.getModifiers()))
                    .as("private would put it beyond the reach of this same-package-tier test")
                    .isFalse();
        }

        @Test
        @DisplayName("the production no-argument constructor still works and uses the canonical table")
        void theProductionConstructorStillWorks() {
            final AdminMenuService production = new AdminMenuService();

            assertThat(production.getMenuScreen().menu().getOptions())
                    .as("the no-argument path must delegate to the canonical COADM02Y table")
                    .containsExactlyElementsOf(MenuResponse.ADMIN_MENU_OPTIONS);
        }

        @Test
        @DisplayName("the clock is the only instance state that is a Clock, and nothing static is mutable")
        void theClockIsImmutableStateAndNothingStaticIsMutable() {
            for (final Field field : AdminMenuService.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final; a mutable field would defeat the fixed clock", field.getName())
                        .isTrue();
            }
        }
    }

    // ==================================================================
    // 2. The option table, and the label rendering built from it.
    // ==================================================================

    @Nested
    @DisplayName("2. The option table matches COADM02Y, and BUILD-MENU-OPTIONS renders it as COBOL does")
    class TheOptionTableAndItsRendering {

        @Test
        @DisplayName("the canonical table holds exactly the four slots COADM02Y populates, not nine")
        void theCanonicalTableHoldsTheCopybooksCount() {
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);

            assertThat(MenuResponse.ADMIN_MENU_OPTIONS)
                    .as("app/cpy/COADM02Y.cpy CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE %d",
                            copybook.declaredCount())
                    .hasSize(copybook.declaredCount());
            assertThat(copybook.declaredCount())
                    .as("the OCCURS %d capacity is not a bound; its spare subscripts are unpopulated",
                            copybook.occursCapacity())
                    .isEqualTo(POPULATED_OPTION_COUNT)
                    .isNotEqualTo(copybook.occursCapacity());
        }

        @ParameterizedTest(name = "slot {0} matches app/cpy/COADM02Y.cpy exactly")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("each slot carries the number, padded name and program COADM02Y declares")
        void eachSlotMatchesTheCopybook(final int slot) {
            // The expectation is READ FROM THE COPYBOOK, not retyped into this test. Two reasons. First,
            // CDEMO-ADMIN-OPT-NAME is PIC X(35) whose padding lives inside the quoted literal, and a
            // retyped @CsvSource fixture silently loses it to JUnit's whitespace trimming - which is
            // exactly how this test first failed. Second, a retyped constant asserts the implementation
            // against a second copy of the same transcription, so a shared mistake would pass.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);
            final MenuResponse.AdminMenuOption option = MenuResponse.ADMIN_MENU_OPTIONS.get(slot - 1);

            assertThat(option.optionNumber())
                    .as("the Java table must be in copybook declaration order")
                    .isEqualTo(copybook.slot(slot).number());
            assertThat(option.optionName())
                    .as("CDEMO-ADMIN-OPT-NAME is PIC X(35); its trailing padding is part of the value")
                    .isEqualTo(copybook.nameOf(slot))
                    .hasSize(OPTION_NAME_WIDTH);
            assertThat(option.programName())
                    .as("CDEMO-ADMIN-OPT-PGMNAME is PIC X(08)")
                    .isEqualTo(copybook.programOf(slot));
        }

        @Test
        @DisplayName("COADM02Y declares no user-type byte, so this menu has no admin-only gate to reproduce")
        void theCopybookDeclaresNoUserTypeByte() {
            // COMEN02Y declares a PIC X(01) user-type byte per slot and COMEN01C gates on it; COADM02Y
            // declares none and COADM01C has no such gate. That is why AdminMenuService.selectOption
            // takes no user type, and it is a structural difference rather than an omission.
            final MenuOptionCopybook copybook = MenuOptionCopybook.of(COPYBOOK_MEMBER);

            for (final MenuOptionCopybook.Slot slot : copybook.slots()) {
                assertThat(copybook.userTypeOf(slot.number()))
                        .as("slot %d", slot.number())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no canonical slot is a placeholder, which is why the seam is needed to reach that branch")
        void noCanonicalSlotIsAPlaceholder() {
            assertThat(MenuResponse.ADMIN_MENU_OPTIONS)
                    .as("COADM02Y populates four real COUSR0nC targets; none begins with DUMMY")
                    .noneMatch(option -> option.programName().startsWith(PLACEHOLDER_PREFIX));
        }

        @Test
        @DisplayName("labels render as a zero-padded number, '. ', the name, and no trailing padding")
        void labelsRenderAsCobolAssemblesThem() {
            final List<String> labels = new AdminMenuService().getMenuScreen().optionLabels();

            assertThat(labels).hasSize(POPULATED_OPTION_COUNT);
            assertThat(labels.get(0))
                    .as(":233 PIC 9(02) zero-padded, :234 '. ', :235 DELIMITED BY SIZE, then stripTrailing")
                    .isEqualTo("01. User List (Security)");
            assertThat(labels.get(3)).isEqualTo("04. User Delete (Security)");
        }

        @Test
        @DisplayName("every label is zero-padded to two digits, so nine and ten would sort as COBOL does")
        void everyLabelIsZeroPaddedToTwoDigits() {
            final List<MenuResponse.AdminMenuOption> table = new ArrayList<>();
            for (int slot = 1; slot <= POPULATED_OPTION_COUNT; slot++) {
                table.add(new MenuResponse.AdminMenuOption(slot, "Name " + slot, "COUSR00C"));
            }

            final List<String> labels =
                    construct(FixedClockProvider.canonicalClock(), table).getMenuScreen().optionLabels();

            for (int slot = 1; slot <= POPULATED_OPTION_COUNT; slot++) {
                assertThat(labels.get(slot - 1))
                        .startsWith(String.format(Locale.ROOT, "%02d. ", slot));
            }
        }

        @Test
        @DisplayName("no label retains trailing white space, because :236 moves into a cleared buffer")
        void noLabelRetainsTrailingWhiteSpace() {
            for (final String label : new AdminMenuService().getMenuScreen().optionLabels()) {
                assertThat(label).isEqualTo(label.stripTrailing());
            }
        }

        @Test
        @DisplayName("the view's option list and menu options are unmodifiable")
        void theViewExposesUnmodifiableCollections() {
            final AdminMenuService.AdminMenuView view = new AdminMenuService().getMenuScreen();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.optionLabels().add("05. Injected"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.menu().getOptions().add(REAL_OPTION));
        }

        @Test
        @DisplayName("the first view carries no message, per SEND-MENU-SCREEN with WS-MESSAGE cleared")
        void theFirstViewCarriesNoMessage() {
            assertThat(new AdminMenuService().getMenuScreen().message())
                    .as("app/cbl/COADM01C.cbl:L118 MOVE SPACES TO WS-MESSAGE before the first send")
                    .isEmpty();
        }
    }

    // ==================================================================
    // 3. RECEIVE-MAP: the declared field width is the only thing it polices.
    // ==================================================================

    @Nested
    @DisplayName("3. RECEIVE-MENU-SCREEN pads to the declared PIC X(2) and refuses anything wider")
    class ReceiveMenuScreenNormalisation {

        @Test
        @DisplayName("a null field becomes two spaces, which then fails the bounds check as option zero")
        void aNullFieldBecomesSpacesAndThenFailsBounds() {
            // A null stands in for a map that was received with OPTIONI never keyed. COBOL would see
            // LOW-VALUES or spaces; either way the space-to-zero substitution yields "00", and :143
            // rejects zero. The observable outcome is the invalid-option message, not a NullPointerException.
            assertInvalidOption(() -> new AdminMenuService().selectOption(null));
        }

        @ParameterizedTest(name = "[{0}] is wider than PIC X(2) and is refused outright")
        @ValueSource(strings = {"111", "0001", "  1", "abc", "1 1"})
        @DisplayName("content wider than the declared field is refused before any parsing")
        void contentWiderThanTheFieldIsRefused(final String raw) {
            assertThat(raw.length())
                    .as("the fixture must actually exceed the declared width for this test to discriminate")
                    .isGreaterThan(OPTION_FIELD_LENGTH);

            assertInvalidOption(() -> new AdminMenuService().selectOption(raw));
        }

        @ParameterizedTest(name = "[{0}] is at or under the declared width and reaches the parser")
        @ValueSource(strings = {"1", "01", " 1", "1 ", "4"})
        @DisplayName("content at or under the declared width is padded and parsed, not refused")
        void contentAtOrUnderTheWidthIsPadded(final String raw) {
            assertThat(new AdminMenuService().selectOption(raw).optionNumber())
                    .as("[%s] must survive RECEIVE-MENU-SCREEN and resolve through PROCESS-ENTER-KEY", raw)
                    .isBetween(1, POPULATED_OPTION_COUNT);
        }

        @Test
        @DisplayName("the rejection is a ValidationException naming the option field, not a raw runtime error")
        void theRejectionNamesTheOptionField() {
            final ValidationException rejection = catchValidation(
                    () -> new AdminMenuService().selectOption("999"));

            assertThat(rejection.getFieldName())
                    .as("CSSETATY-style per-field error marking becomes the field name on the exception")
                    .isEqualTo("option");
            assertThat(rejection.getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(rejection.getMessage()).isEqualTo(EXPECTED_INVALID_OPTION_MESSAGE);
        }
    }

    // ==================================================================
    // 4. PROCESS-ENTER-KEY: the normalisation, then the bounds check.
    // ==================================================================

    @Nested
    @DisplayName("4. PROCESS-ENTER-KEY right-justifies, substitutes zero for space, then bounds-checks")
    class ProcessEnterKeyNormalisationAndBounds {

        @ParameterizedTest(name = "[{0}] normalises to option {1}")
        @CsvSource(delimiter = '|', value = {
            "1|1",
            "01|1",
            " 1|1",
            "1 |1",
            "2|2",
            "02|2",
            "3|3",
            "4|4",
            "04|4",
            " 4|4",
        })
        @DisplayName("every representation of an in-range option resolves to the same option number")
        void everyRepresentationOfAnInRangeOptionResolves(final String raw, final int expected) {
            final AdminMenuService.AdminMenuSelection selection =
                    new AdminMenuService().selectOption(raw);

            assertThat(selection.optionNumber())
                    .as(":134-:139 scan back over trailing spaces, right-justify into PIC X(2), then"
                            + " substitute '0' for each remaining space before the numeric test")
                    .isEqualTo(expected);
            assertThat(selection.targetProgram())
                    .isEqualTo(MenuResponse.ADMIN_MENU_OPTIONS.get(expected - 1).programName());
        }

        @ParameterizedTest(name = "[{0}] is rejected with the invalid-option message")
        @ValueSource(strings = {"0", "00", " 0", "0 ", "5", "05", "9", "10", "99", "ab", "a", "1a", "a1",
            "-1", "+1", ".5", "  ", " ", ""})
        @DisplayName("zero, out-of-range and non-numeric content are all rejected the same way")
        void unusableContentIsRejected(final String raw) {
            assertInvalidOption(() -> new AdminMenuService().selectOption(raw));
        }

        @Test
        @DisplayName("a tab is NOT a space: only ' ' is scanned back over and only ' ' becomes '0'")
        void aTabIsNotASpace() {
            // The scan at :135 and the substitution at :139 both test the literal space character, so a
            // tab survives into the numeric test and fails it. This discriminates a faithful character
            // test from a convenient isWhitespace()/strip() rewrite, which would have accepted "1\t".
            assertInvalidOption(() -> new AdminMenuService().selectOption("1\t"));
            assertInvalidOption(() -> new AdminMenuService().selectOption("\t1"));
        }

        @Test
        @DisplayName("the upper bound is the supplied option count, not the copybook constant")
        void theUpperBoundFollowsTheSuppliedTable() {
            // :143 compares against CDEMO-ADMIN-OPT-COUNT, so a shorter table must narrow the accepted
            // range. Were the bound hardcoded to four, option 2 would resolve against a one-entry table
            // and read past its end.
            final AdminMenuService oneOption =
                    construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION));

            assertThat(oneOption.selectOption("1").optionNumber()).isEqualTo(1);
            assertInvalidOption(() -> oneOption.selectOption("2"));
        }

        @Test
        @DisplayName("a resolved real option carries its target program, an empty message and no placeholder")
        void aResolvedRealOptionCarriesItsTarget() {
            final AdminMenuService.AdminMenuSelection selection =
                    new AdminMenuService().selectOption("3");

            assertThat(selection.optionNumber()).isEqualTo(3);
            assertThat(selection.optionName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_OPTIONS.get(2).optionName());
            assertThat(selection.targetProgram()).isEqualTo("COUSR02C");
            assertThat(selection.message())
                    .as("a real target carries no message; the notice belongs to the placeholder branch")
                    .isEmpty();
            assertThat(selection.comingSoon()).isFalse();
        }

        @Test
        @DisplayName("selection does not mutate the service, so repeated calls agree")
        void selectionIsSideEffectFree() {
            final AdminMenuService service = new AdminMenuService();

            final AdminMenuService.AdminMenuSelection first = service.selectOption("2");
            assertInvalidOption(() -> service.selectOption("7"));
            final AdminMenuService.AdminMenuSelection second = service.selectOption("2");

            assertThat(second).isEqualTo(first);
        }
    }

    // ==================================================================
    // 5. The DUMMY guard, and the flat literal the corpus mandates.
    // ==================================================================

    @Nested
    @DisplayName("5. The DUMMY guard yields a FLAT notice, because COADM01C:150-151 are commented out")
    class TheComingSoonNoticeIsFlatByCorpusMandate {

        @Test
        @DisplayName("a placeholder target yields the coming-soon notice, an empty target and the flag set")
        void aPlaceholderTargetYieldsTheNotice() {
            final AdminMenuService withPlaceholder =
                    construct(FixedClockProvider.canonicalClock(), List.of(PLACEHOLDER_OPTION));

            final AdminMenuService.AdminMenuSelection selection = withPlaceholder.selectOption("1");

            assertThat(selection.comingSoon())
                    .as("app/cbl/COADM01C.cbl:138 IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'")
                    .isTrue();
            assertThat(selection.targetProgram())
                    .as("the placeholder branch performs no XCTL, so no target is published")
                    .isEmpty();
            assertThat(selection.message()).isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(selection.optionName()).isEqualTo(PLACEHOLDER_OPTION.optionName());
        }

        @Test
        @DisplayName("the notice is FLAT: it never interpolates the option name, however padded")
        void theNoticeNeverInterpolatesTheOptionName() {
            // The discriminating assertion of this class. COADM01C.cbl:150-151 carry an asterisk in
            // column seven, so CDEMO-ADMIN-OPT-NAME is NOT contributed to WS-MESSAGE. A "consistency"
            // edit that borrowed MainMenuService's assembly would produce
            // "This option Reservedis coming soon ..." and fail here.
            final AdminMenuService withPlaceholder =
                    construct(FixedClockProvider.canonicalClock(), List.of(PLACEHOLDER_OPTION));

            final String message = withPlaceholder.selectOption("1").message();

            assertThat(message).isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
            assertThat(message)
                    .as("no fragment of the option name may appear, truncated at a space or otherwise")
                    .doesNotContain("Reserved")
                    .doesNotContain("Admin Function");
        }

        @Test
        @DisplayName("the notice keeps the single space that 'This option ' already carries")
        void theNoticeKeepsTheSpaceFromItsFirstFragment() {
            // COMEN01C loses a space because its option name is concatenated onto 'This option '.
            // COADM01C keeps it because nothing is concatenated. Two different strings, both faithful,
            // and the difference is exactly the two commented-out lines.
            assertThat(EXPECTED_COMING_SOON_MESSAGE)
                    .startsWith("This option ")
                    .endsWith("is coming soon ...")
                    .isEqualTo("This option " + "is coming soon ...");

            final AdminMenuService withPlaceholder =
                    construct(FixedClockProvider.canonicalClock(), List.of(PLACEHOLDER_OPTION));
            assertThat(withPlaceholder.selectOption("1").message())
                    .containsOnlyOnce("option is coming");
        }

        @ParameterizedTest(name = "target [{0}] is treated as a placeholder: {1}")
        @CsvSource({
            "DUMMY, true",
            "DUMMY01C, true",
            "DUMMYXXX, true",
            "DUMM, false",
            "DUMMX01C, false",
            "dummy01C, false",
            "COUSR00C, false",
            "XDUMMY01, false",
        })
        @DisplayName("the guard tests the first five characters exactly, case-sensitively")
        void theGuardTestsTheFirstFiveCharactersExactly(final String program, final boolean placeholder) {
            final MenuResponse.AdminMenuOption option =
                    new MenuResponse.AdminMenuOption(1, "Probe Option                       ", program);
            final AdminMenuService service =
                    construct(FixedClockProvider.canonicalClock(), List.of(option));

            final AdminMenuService.AdminMenuSelection selection = service.selectOption("1");

            assertThat(selection.comingSoon())
                    .as("COADM01C:138 compares (1:5) against the literal 'DUMMY'; COBOL literals are"
                            + " case-sensitive, so [%s] must be treated as %s", program,
                            placeholder ? "a placeholder" : "a real target")
                    .isEqualTo(placeholder);
            assertThat(selection.targetProgram()).isEqualTo(placeholder ? "" : program);
            assertThat(selection.message())
                    .isEqualTo(placeholder ? EXPECTED_COMING_SOON_MESSAGE : "");
        }

        @Test
        @DisplayName("a mixed table routes each option independently")
        void aMixedTableRoutesEachOptionIndependently() {
            final AdminMenuService mixed = construct(FixedClockProvider.canonicalClock(), List.of(
                    REAL_OPTION,
                    new MenuResponse.AdminMenuOption(2, "Reserved Two                       ", "DUMMY02C")));

            assertThat(mixed.selectOption("1").comingSoon()).isFalse();
            assertThat(mixed.selectOption("1").targetProgram()).isEqualTo("COUSR00C");
            assertThat(mixed.selectOption("2").comingSoon()).isTrue();
            assertThat(mixed.selectOption("2").message()).isEqualTo(EXPECTED_COMING_SOON_MESSAGE);
        }
    }

    // ==================================================================
    // 6. The sign-off target.
    // ==================================================================

    @Nested
    @DisplayName("6. RETURN-TO-SIGNON-SCREEN defaults an unnamed program to COSGN00C")
    class ReturnToSignOnScreenFallback {

        @ParameterizedTest(name = "[{0}] falls back to COSGN00C")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank requested program falls back to the sign-on program")
        void aBlankRequestedProgramFallsBack(final String requested) {
            assertThat(new AdminMenuService().signOnProgram(requested))
                    .as("app/cbl/COADM01C.cbl:165-167 IF CDEMO-TO-PROGRAM = SPACES/LOW-VALUES"
                            + " MOVE 'COSGN00C'")
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("a null requested program falls back to the sign-on program")
        void aNullRequestedProgramFallsBack() {
            assertThat(new AdminMenuService().signOnProgram(null))
                    .as("LOW-VALUES has no Java counterpart other than null; the outcome must match SPACES")
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("a named program is passed through unchanged, including a name needing no default")
        void aNamedProgramIsPassedThrough() {
            assertThat(new AdminMenuService().signOnProgram("COUSR00C")).isEqualTo("COUSR00C");
            assertThat(new AdminMenuService().signOnProgram(EXPECTED_SIGN_ON_PROGRAM))
                    .isEqualTo(EXPECTED_SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("a name with significant content and surrounding space is not trimmed")
        void aNamedProgramIsNotTrimmed() {
            // The COBOL tests the whole field against SPACES; it does not trim and re-test. A name that
            // is not blank is moved as it stands, so the padding a PIC X(8) carries survives.
            assertThat(new AdminMenuService().signOnProgram(" COUSR00C "))
                    .isEqualTo(" COUSR00C ");
        }
    }

    // ==================================================================
    // 7. POPULATE-HEADER-INFO, driven by the injected clock.
    // ==================================================================

    @Nested
    @DisplayName("7. POPULATE-HEADER-INFO reads the injected clock once and follows its zone")
    class TheHeaderIsDrivenByTheInjectedClock {

        @Test
        @DisplayName("the canonical instant renders exactly, in MM/dd/yy and HH:mm:ss")
        void theCanonicalInstantRendersExactly() {
            construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION)).getMenuScreen();

            assertThat(renderedHeader())
                    .as("2022-06-10T19:27:53Z in UTC, per app/cpy/CSDAT01Y.cpy separators")
                    .isEqualTo("AWS Mainframe Modernization | CardDemo | tranid=CA00"
                            + " | pgmname=COADM01C | date=06/10/22 | time=19:27:53");
        }

        @Test
        @DisplayName("the header names the CSD transaction and the PROGRAM-ID, not the Java class")
        void theHeaderNamesTheCsdTransactionAndProgram() {
            construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION)).getMenuScreen();

            assertThat(renderedHeader())
                    .contains("tranid=" + EXPECTED_TRANSACTION_ID)
                    .contains("pgmname=" + EXPECTED_PROGRAM_NAME)
                    .as("the titles are stripped of the padding their PIC X(40) declarations carry")
                    .contains(EXPECTED_TITLE_01.strip())
                    .contains(EXPECTED_TITLE_02.strip());
        }

        @Test
        @DisplayName("the zone of the clock decides the local value, not the platform default")
        void theZoneOfTheClockDecidesTheLocalValue() {
            final Clock tokyo = FixedClockProvider.fixedClock(
                    FixedClockProvider.CANONICAL_INSTANT, ZoneId.of("Asia/Tokyo"));

            construct(tokyo, List.of(REAL_OPTION)).getMenuScreen();

            assertThat(renderedHeader())
                    .as("19:27:53Z is 04:27:53 on the following day at UTC+09:00")
                    .contains("date=06/11/22")
                    .contains("time=04:27:53");
        }

        @Test
        @DisplayName("midnight is resolved in the clock's zone, and the two fields cannot straddle it")
        void midnightIsResolvedInTheClocksZone() {
            final Clock justBefore = FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-10T23:59:59Z"), ZoneOffset.UTC);
            construct(justBefore, List.of(REAL_OPTION)).getMenuScreen();
            assertThat(renderedHeader()).contains("date=06/10/22").contains("time=23:59:59");

            reset();

            final Clock exactlyMidnight = FixedClockProvider.fixedClock(
                    Instant.parse("2022-06-11T00:00:00Z"), ZoneOffset.UTC);
            construct(exactlyMidnight, List.of(REAL_OPTION)).getMenuScreen();
            assertThat(renderedHeader())
                    .as("a single MOVE FUNCTION CURRENT-DATE means one reading feeds both fields")
                    .contains("date=06/11/22")
                    .contains("time=00:00:00");
        }

        @Test
        @DisplayName("the year is reduced to its last two digits, as PIC X(8) MM/DD/YY requires")
        void theYearIsReducedToItsLastTwoDigits() {
            final Clock turnOfCentury = FixedClockProvider.fixedClock(
                    Instant.parse("2001-01-02T03:04:05Z"), ZoneOffset.UTC);

            construct(turnOfCentury, List.of(REAL_OPTION)).getMenuScreen();

            assertThat(renderedHeader())
                    .as("WS-CURDATE-MM/DD/YY, each PIC X(2), so 2001 renders as 01")
                    .contains("date=01/02/01")
                    .contains("time=03:04:05");
        }

        @Test
        @DisplayName("repeated sends on a fixed clock render identically")
        void repeatedSendsAreIdentical() {
            final AdminMenuService service =
                    construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION));

            service.getMenuScreen();
            final String first = renderedHeader();
            reset();
            service.getMenuScreen();

            assertThat(renderedHeader())
                    .as("nothing ambient may leak into the rendering")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("the rendering is locale-independent, so a non-Gregorian default cannot move it")
        void theRenderingIsLocaleIndependent() {
            final Locale restore = Locale.getDefault();
            try {
                Locale.setDefault(new Locale.Builder().setLanguage("th").setRegion("TH")
                        .setExtension('u', "nu-thai-ca-buddhist").build());

                construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION)).getMenuScreen();

                assertThat(renderedHeader())
                        .as("Locale.ROOT is passed to both formatters precisely so this cannot move")
                        .contains("date=06/10/22")
                        .contains("time=19:27:53");
            } finally {
                Locale.setDefault(restore);
            }
        }

        @Test
        @DisplayName("the header is emitted once per send, because POPULATE-HEADER-INFO runs once")
        void theHeaderIsEmittedOncePerSend() {
            construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION)).getMenuScreen();

            assertThat(headerEvents())
                    .as("app/cbl/COADM01C.cbl:L182 performs POPULATE-HEADER-INFO once per SEND MAP")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the diagnostic statement is parameterised, so no value is pre-formatted into it")
        void theStatementIsParameterised() {
            construct(FixedClockProvider.canonicalClock(), List.of(REAL_OPTION)).getMenuScreen();

            final ILoggingEvent event = singleHeaderEvent();
            assertThat(event.getMessage())
                    .as("string concatenation into a log message defeats structured logging")
                    .contains("{}");
            assertThat(argumentsOf(event))
                    .hasSizeGreaterThan(HEADER_ARGUMENT_INDEX);
            assertThat(argumentsOf(event)[0]).isEqualTo(EXPECTED_TRANSACTION_ID);
            assertThat(argumentsOf(event)[1]).isEqualTo(1);
        }
    }

    // ==================================================================
    // Fixture, assertion and reflection helpers.
    // ==================================================================

    /** Clears the captured events so a second send within one test can be read in isolation. */
    private void reset() {
        this.appender.list.clear();
    }

    /**
     * Asserts that the supplied action is refused with the invalid-option message from
     * {@code app/cbl/COADM01C.cbl:131}, naming the option field.
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

    private static AdminMenuService construct(
            final Clock clock, final List<MenuResponse.AdminMenuOption> options) {
        return MenuServiceTestSupport.construct(
                AdminMenuService.class, seamConstructor(), clock, options);
    }

    private static Constructor<?> seamConstructor() {
        return MenuServiceTestSupport.seamConstructor(AdminMenuService.class,
                "the DUMMY placeholder branch that no canonical option exercises "
                        + "becomes unreachable");
    }

    private List<ILoggingEvent> headerEvents() {
        return MenuServiceTestSupport.headerEvents(this.appender, "Sending admin menu");
    }

    private ILoggingEvent singleHeaderEvent() {
        return MenuServiceTestSupport.singleHeaderEvent(headerEvents(),
                "exactly one send statement is expected; POPULATE-HEADER-INFO runs once per send");
    }

    private static Object[] argumentsOf(final ILoggingEvent event) {
        return MenuServiceTestSupport.argumentsOf(event,
                "the send statement is parameterised, so it must carry an argument array");
    }

    private String renderedHeader() {
        return (String) argumentsOf(singleHeaderEvent())[HEADER_ARGUMENT_INDEX];
    }
}
