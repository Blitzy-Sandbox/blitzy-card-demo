/*
 * ******************************************************************
 * Program     : MainMenuServiceCoverageTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the main menu dispatch of COMEN01C: the ten
 *               populated option-table entries, the forty-column
 *               screen slot each option renders into, the backward
 *               scan plus space-to-zero substitution that normalises
 *               the two-byte option field, the administrator-only
 *               gate, the DUMMY placeholder guard and its spliced
 *               coming-soon notice, and the XCTL fall-through
 *               invariant carried by the selection record.
 * Source      : app/cbl/COMEN01C.cbl:L95-L215 (PROCESS-ENTER-KEY: the
 *               backward scan, INSPECT REPLACING ALL ' ' BY '0', the
 *               numeric and bounds test at :L133, the administrator
 *               gate at :L137, the DUMMY guard at :L146 and the
 *               STRING at :L159-L163) @ 7756d89
 * Source      : app/cpy/COMEN02Y.cpy (CDEMO-MENU-OPT-COUNT VALUE 10,
 *               the ten populated entries at :L25-L84 each carrying
 *               PIC 9(02) number, PIC X(35) name, PIC X(08) program
 *               and PIC X(01) user type, and the REDEFINES OCCURS 12)
 *               @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:26 (CDEMO-USER-TYPE, whose 'A'
 *               and 'U' condition names became UserType) @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.MainMenuService;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link MainMenuService}, the Java target of {@code app/cbl/COMEN01C.cbl}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The legacy main menu is two paragraphs and a table. {@code SEND-MENU-SCREEN} renders one
 * forty-column line per option it is allowed to show, and {@code PROCESS-ENTER-KEY} turns whatever the
 * operator left in the two-byte {@code OPTIONI} field into either a program to transfer to or a message to
 * redisplay. Everything interesting about it is in the details of those two steps, so this test pins them
 * one at a time rather than asserting that a menu "works".</p>
 *
 * <p>Four behaviours here are counter-intuitive enough that they are asserted explicitly, with the source
 * locator that makes each of them correct:</p>
 *
 * <ul>
 *   <li><strong>Every one of the ten shipped options is user-visible.</strong> A {@code grep} of
 *       {@code app/cpy/COMEN02Y.cpy} for {@code PIC X(01) VALUE} returns ten {@code 'U'} literals and no
 *       {@code 'A'}. The administrator-only branch at {@code app/cbl/COMEN01C.cbl:L137} is therefore
 *       unreachable through the shipped table, and the only honest way to test it is to inject a table that
 *       contains an {@code 'A'} entry. This test does exactly that, through the package-private
 *       constructor, and says so.</li>
 *   <li><strong>The bounds check counts the whole table, not the filtered view.</strong> The source tests
 *       {@code WS-OPTION &gt; CDEMO-MENU-OPT-COUNT}, and the count is a copybook constant that does not
 *       shrink when an option is withheld from a standard user. A standard user can therefore name an
 *       option number that lies beyond the end of their own visible list and be admitted, provided the
 *       entry at that subscript is not itself administrator-only.</li>
 *   <li><strong>The spliced coming-soon notice has no separating space.</strong> The source concatenates
 *       {@code 'This option '}, then the caption {@code DELIMITED BY SPACE}, then
 *       {@code 'is coming soon ...'}. Delimiting by space truncates the caption at its first blank and
 *       contributes no blank of its own, so {@code Account View} yields
 *       {@code "This option Accountis coming soon ..."}. That missing space is the source's behaviour and
 *       is preserved, not repaired.</li>
 *   <li><strong>A placeholder selection keeps its {@code DUMMY} program name.</strong> The main menu
 *       returns the name it found and flags the selection; the administrator menu, by contrast, blanks it.
 *       The two services diverge here and both are faithful, so the divergence is pinned on both sides.</li>
 * </ul>
 *
 * <p>The record contracts are covered as first-class subjects rather than as a side effect of the service
 * calls, because {@code MenuSelection} carries an invariant that no service call can violate: a real target
 * must carry no notice and a placeholder target must carry one. Under CICS that invariant held for free,
 * because {@code XCTL} transferred control before the notice could be assembled. In a stateless target
 * nothing enforces it but the record itself.</p>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>
 *   mvn -o -B test -Dtest=MainMenuServiceCoverageTest -DfailIfNoSpecifiedTests=false
 *   mvn -o -B clean verify -Ddependency-check.skip=true   (full gate: coverage floor plus tests)
 * </pre>
 *
 * <p>No profile, container, database or network endpoint is required. Every assertion runs against a plain
 * instance constructed in the test method that needs it.</p>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>{@link MainMenuService} takes no configuration. Its public constructor binds the canonical table
 * {@code MenuResponse.MAIN_MENU_OPTIONS}, transcribed from {@code app/cpy/COMEN02Y.cpy}; its
 * package-private constructor accepts a substitute table and exists as a test seam for the two branches the
 * canonical table cannot reach. Because this test lives in {@code com.cardemo.unit.service} rather than
 * {@code com.cardemo.service.menu}, that constructor is reached reflectively with
 * {@code setAccessible(true)} - the same seam already used for the entities' protected JPA constructors.
 * The service's own constants are package-private for the same reason, so the literals they hold are
 * asserted as literals here; that is deliberate, and it is what makes an accidental edit to one of those
 * literals fail this test rather than pass it silently.</p>
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code CanonicalOptionTable} means {@code MenuResponse.MAIN_MENU_OPTIONS} has drifted
 *       from {@code app/cpy/COMEN02Y.cpy}. Recount the copybook rather than adjusting the expectation - the
 *       copybook is the authority.</li>
 *   <li>A failure in {@code OptionFieldNormalisation} means the backward scan or the space-to-zero
 *       substitution has changed. Compare against {@code app/cbl/COMEN01C.cbl:L108-L133}; the normalisation
 *       is deliberately not {@code trim} plus {@code parseInt}, because those accept and reject a different
 *       set of inputs.</li>
 *   <li>A failure in {@code PlaceholderProgramGuard} on the missing space is almost always a well-meant
 *       repair. The space is absent in the source. Restore it in the test only by first changing
 *       {@code app/cbl/COMEN01C.cbl}, which is frozen.</li>
 *   <li>A {@link ValidationException} where an {@link IllegalArgumentException} is expected, or the
 *       reverse, means an operator-input rejection has been confused with a programming error. Absent
 *       operator input is a validation failure; an absent {@link UserType} is a wiring defect, because the
 *       role claim that replaces {@code CDEMO-USER-TYPE} is always present by the time the service is
 *       reached.</li>
 * </ul>
 */
@DisplayName("MainMenuService - the COMEN01C main menu dispatch")
final class MainMenuServiceCoverageTest {

    /** The notice the source redisplays for any option field that does not resolve to a usable number. */
    private static final String INVALID_OPTION_NOTICE = "Please enter a valid option number...";

    /**
     * The refusal notice for a standard user naming an administrator-only option. The trailing space is
     * part of the source literal at {@code app/cbl/COMEN01C.cbl:L138} and is asserted, not trimmed.
     */
    private static final String ADMIN_ONLY_NOTICE = "No access - Admin Only option... ";

    /** The program the source always resolves for PF3, at {@code app/cbl/COMEN01C.cbl:L172-L174}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The screen slot each rendered option line occupies, from {@code MenuResponse}. */
    private static final int SCREEN_SLOT = MenuResponse.SCREEN_OPTION_SLOT_LENGTH;

    /** The declared caption width, {@code PIC X(35)} at {@code app/cpy/COMEN02Y.cpy}. */
    private static final int CAPTION_WIDTH = MenuResponse.OPTION_NAME_LENGTH;

    /** The service under test, bound to the canonical ten-entry table by its public constructor. */
    private final MainMenuService service = new MainMenuService();

    /**
     * Constructs a service over a substitute option table through the package-private constructor.
     *
     * <p>This is the deliberate test seam for the administrator-only gate and the {@code DUMMY} placeholder
     * guard, neither of which the shipped table can reach. Any {@link RuntimeException} the constructor
     * raises is unwrapped and rethrown so that guard assertions see the real exception rather than an
     * {@link InvocationTargetException}.</p>
     *
     * @param options the substitute table
     * @return a service bound to {@code options}
     */
    private static MainMenuService serviceOver(final Clock clock,
            final List<MenuResponse.MainMenuOption> options) {
        try {
            final Constructor<MainMenuService> injectable =
                    MainMenuService.class.getDeclaredConstructor(Clock.class, List.class);
            injectable.setAccessible(true);
            return injectable.newInstance(clock, options);
        } catch (final InvocationTargetException invocationFailure) {
            throw unwrap(invocationFailure);
        } catch (final ReflectiveOperationException reflectionFailure) {
            throw new IllegalStateException(
                    "the package-private MainMenuService(Clock, List) test seam is no longer reachable",
                    reflectionFailure);
        }
    }

    /**
     * Constructs a service over a substitute option table and an arbitrary fixed clock.
     *
     * <p>The clock is a constructor parameter rather than an ambient value because
     * {@code POPULATE-HEADER-INFO} reads the current date and time once per response, so the header
     * fields are only assertable when the instant is supplied. Callers that assert nothing about the
     * header pass through this overload.</p>
     *
     * @param options the substitute table
     * @return a service bound to {@code options}
     */
    private static MainMenuService serviceOver(final List<MenuResponse.MainMenuOption> options) {
        return serviceOver(Clock.systemUTC(), options);
    }

    /**
     * Unwraps a reflective invocation failure into the runtime exception the constructor actually threw.
     *
     * @param invocationFailure the reflective wrapper
     * @return the unwrapped runtime exception
     */
    private static RuntimeException unwrap(final InvocationTargetException invocationFailure) {
        final Throwable cause = invocationFailure.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("the test seam raised an unexpected checked failure", cause);
    }

    /**
     * Builds one main menu option, so that substitute tables read as data rather than as construction.
     *
     * @param number       the option number
     * @param caption      the option caption
     * @param program      the target program name
     * @param userTypeCode {@code 'A'} for administrator-only, {@code 'U'} otherwise
     * @return the option
     */
    private static MenuResponse.MainMenuOption option(final int number, final String caption,
            final String program, final char userTypeCode) {
        return new MenuResponse.MainMenuOption(number, caption, program, userTypeCode);
    }

    @Nested
    @DisplayName("The canonical option table transcribed from app/cpy/COMEN02Y.cpy")
    final class CanonicalOptionTable {

        @Test
        @DisplayName("an administrator is offered the ten options CDEMO-MENU-OPT-COUNT declares populated")
        void anAdministratorIsOfferedTheTenPopulatedOptions() {
            final MainMenuService.MainMenuScreen screen = service.getMainMenu(UserType.ADMIN);

            assertThat(screen.menu().getOptionCount())
                    .as("app/cpy/COMEN02Y.cpy sets CDEMO-MENU-OPT-COUNT to 10, and the REDEFINES OCCURS 12 "
                            + "is a storage capacity whose two spare subscripts are unpopulated, so it is "
                            + "not the bound the menu offers")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("every caption occupies the full PIC X(35) declared width, unstripped")
        void everyCaptionOccupiesTheDeclaredCaptionWidth() {
            final MenuResponse<MenuResponse.MainMenuOption> menu = service.getMainMenu(UserType.ADMIN).menu();

            assertThat(menu.getOptions())
                    .as("CDEMO-MENU-OPT-NAME is PIC X(35), so a caption shorter than the field is stored "
                            + "space-padded to the field width rather than stripped")
                    .allSatisfy(option -> assertThat(option.optionName()).hasSize(CAPTION_WIDTH));
        }

        @ParameterizedTest(name = "option {0} is {2}")
        @CsvSource({
            "1, Account View, COACTVWC",
            "2, Account Update, COACTUPC",
            "3, Credit Card List, COCRDLIC",
            "4, Credit Card View, COCRDSLC",
            "5, Credit Card Update, COCRDUPC",
            "6, Transaction List, COTRN00C",
            "7, Transaction View, COTRN01C",
            "8, Transaction Add, COTRN02C",
            "9, Transaction Reports, CORPT00C",
            "10, Bill Payment, COBIL00C",
        })
        @DisplayName("each populated entry carries the number, caption and program the copybook declares")
        void eachPopulatedEntryMatchesTheCopybook(final int number, final String caption,
                final String program) {
            final MenuResponse.MainMenuOption entry =
                    service.getMainMenu(UserType.ADMIN).menu().getOptions().get(number - 1);

            assertThat(entry.optionNumber())
                    .as("the table is offered in copybook subscript order, so entry %d is option %d",
                            number, number)
                    .isEqualTo(number);
            assertThat(entry.optionName().stripTrailing())
                    .as("the caption of option %d, once the PIC X(35) padding is discounted", number)
                    .isEqualTo(caption);
            assertThat(entry.programName())
                    .as("CDEMO-MENU-OPT-PGMNAME of option %d, PIC X(08)", number)
                    .isEqualTo(program);
        }

        @Test
        @DisplayName("every shipped entry carries the standard user-type code, so none is admin-only")
        void everyShippedEntryIsUserVisible() {
            final MenuResponse<MenuResponse.MainMenuOption> menu = service.getMainMenu(UserType.ADMIN).menu();

            assertThat(menu.getOptions())
                    .as("a grep of app/cpy/COMEN02Y.cpy for PIC X(01) VALUE returns ten 'U' literals and no "
                            + "'A'; the commented-out 'Transaction Add (Admin Only)' line above entry 8 is "
                            + "the only trace of an administrator-only option and it is not live")
                    .allSatisfy(option -> assertThat(option.userTypeCode()).isEqualTo('U'));
        }

        @Test
        @DisplayName("every shipped program name occupies the PIC X(08) declared width")
        void everyShippedProgramNameOccupiesTheDeclaredWidth() {
            final MenuResponse<MenuResponse.MainMenuOption> menu = service.getMainMenu(UserType.ADMIN).menu();

            assertThat(menu.getOptions())
                    .as("CDEMO-MENU-OPT-PGMNAME is PIC X(08) and every populated entry fills it exactly, "
                            + "which is why the DUMMY guard can test a five-character prefix safely")
                    .allSatisfy(option -> assertThat(option.programName())
                            .hasSize(MenuResponse.PROGRAM_NAME_LENGTH));
        }

        @Test
        @DisplayName("the response identifies itself as the main menu, not the admin menu")
        void theResponseIdentifiesItselfAsTheMainMenu() {
            assertThat(service.getMainMenu(UserType.ADMIN).menu().getMenuType())
                    .as("the menu type is what distinguishes COMEN01C's table from COADM01C's, and the two "
                            + "differ in populated count as well as in content")
                    .isEqualTo(MenuResponse.MenuType.MAIN);
        }

        @Test
        @DisplayName("the main menu offers more options than the admin menu populates")
        void theMainMenuOffersMoreOptionsThanTheAdminMenu() {
            assertThat(MenuResponse.MenuType.MAIN.getPopulatedOptionCount())
                    .as("COMEN02Y populates ten entries and COADM02Y four; the two counts are independent "
                            + "copybook constants and neither derives from the other")
                    .isGreaterThan(MenuResponse.MenuType.ADMIN.getPopulatedOptionCount());
        }
    }

    @Nested
    @DisplayName("Screen rendering: the forty-column slot and the sign-on target")
    final class ScreenRendering {

        @ParameterizedTest(name = "for a {0}")
        @CsvSource({"ADMIN", "USER"})
        @DisplayName("the sign-on target is the sign-on program for either user class")
        void theSignOnTargetIsAlwaysTheSignOnProgram(final UserType userType) {
            assertThat(service.getMainMenu(userType).signOnTarget())
                    .as("RETURN-TO-SIGNON-SCREEN at app/cbl/COMEN01C.cbl:L172-L174 moves the literal "
                            + "'COSGN00C' unconditionally; it does not depend on who is signed on")
                    .isEqualTo(SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("every rendered label occupies exactly the forty-column screen slot")
        void everyRenderedLabelOccupiesTheScreenSlot() {
            final List<String> labels = service.getMainMenu(UserType.ADMIN).optionLabels();

            assertThat(labels)
                    .as("the BMS option field is forty bytes wide, so a shorter line is space-padded and a "
                            + "longer one truncated; a variable-width label would misalign the screen")
                    .allSatisfy(label -> assertThat(label).hasSize(SCREEN_SLOT));
        }

        @Test
        @DisplayName("the first label is the zero-padded number, the separator and the padded caption")
        void theFirstLabelCarriesTheZeroPaddedNumberAndSeparator() {
            final List<String> labels = service.getMainMenu(UserType.ADMIN).optionLabels();

            assertThat(labels.getFirst())
                    .as("option 1 renders as '01' rather than ' 1', because the source formats the PIC "
                            + "9(02) number with a leading zero")
                    .startsWith("01. Account View")
                    .hasSize(SCREEN_SLOT);
        }

        @Test
        @DisplayName("the tenth label needs no leading zero and still fills the slot")
        void theTenthLabelNeedsNoLeadingZero() {
            final List<String> labels = service.getMainMenu(UserType.ADMIN).optionLabels();

            assertThat(labels.get(9))
                    .as("option 10 is already two digits, so the zero pad is a no-op and only the caption "
                            + "padding brings the line up to the slot width")
                    .startsWith("10. Bill Payment")
                    .hasSize(SCREEN_SLOT);
        }

        @Test
        @DisplayName("a rendered label is the number, the separator, the caption and nothing else")
        void aRenderedLabelIsExactlyItsThreeParts() {
            final String label = service.getMainMenu(UserType.ADMIN).optionLabels().getFirst();

            assertThat(label.stripTrailing())
                    .as("two digits plus '. ' plus the stripped caption; the remaining columns are padding, "
                            + "so stripping the trailing blanks must leave precisely those three parts")
                    .isEqualTo("01. Account View");
        }

        @Test
        @DisplayName("a standard user sees the same ten options, because no shipped entry is admin-only")
        void aStandardUserSeesTheSameTenOptions() {
            final MainMenuService.MainMenuScreen adminScreen = service.getMainMenu(UserType.ADMIN);
            final MainMenuService.MainMenuScreen userScreen = service.getMainMenu(UserType.USER);

            assertThat(userScreen.menu().getOptions())
                    .as("the filter withholds only entries whose user-type byte is 'A', and the shipped "
                            + "table has none, so the two screens are identical rather than merely similar")
                    .isEqualTo(adminScreen.menu().getOptions());
        }

        @Test
        @DisplayName("the rendered label count always equals the offered option count")
        void theRenderedLabelCountEqualsTheOptionCount() {
            final MainMenuService.MainMenuScreen screen = service.getMainMenu(UserType.USER);

            assertThat(screen.optionLabels())
                    .as("app/cbl/COMEN01C.cbl renders exactly one line per option it is allowed to show; a "
                            + "mismatch would leave a blank row or drop an option")
                    .hasSize(screen.menu().getOptionCount());
        }

        @Test
        @DisplayName("the label list the screen exposes cannot be modified by its caller")
        void theLabelListIsUnmodifiable() {
            final List<String> labels = service.getMainMenu(UserType.ADMIN).optionLabels();

            assertThatCode(() -> labels.add("11. Injected"))
                    .as("the screen is a value object handed to a caller; a mutable label list would let a "
                            + "controller alter what the service decided to render")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("requesting the menu without a user class is a wiring defect, not a validation failure")
        void requestingTheMenuWithoutAUserClassIsAWiringDefect() {
            final IllegalArgumentException failure =
                    catchThrowableOfType(IllegalArgumentException.class, () -> service.getMainMenu(null));

            assertThat(failure)
                    .as("the role claim that replaces CDEMO-USER-TYPE is established by the authentication "
                            + "filter, so its absence is a programming error rather than something an "
                            + "operator can cause; a ValidationException here would be surfaced to the "
                            + "operator as a correctable field problem, which it is not")
                    .isNotNull()
                    .isNotInstanceOf(ValidationException.class);
            assertThat(failure.getMessage())
                    .as("the message must name the claim it stands in for, so that a wiring defect is "
                            + "diagnosable without reading the service")
                    .contains("userType must not be null")
                    .contains("CDEMO-USER-TYPE")
                    .contains("app/cpy/COCOM01Y.cpy:26");
        }
    }

    @Nested
    @DisplayName("Option field normalisation: the backward scan and space-to-zero substitution")
    final class OptionFieldNormalisation {

        @ParameterizedTest(name = "[{0}] resolves to option {1}")
        @CsvSource(value = {
            "1|1",
            "01|1",
            " 1|1",
            "1 |1",
            "9|9",
            "09|9",
            "10|10",
        }, delimiter = '|')
        @DisplayName("a usable option field resolves to its number however it is justified or padded")
        void aUsableOptionFieldResolvesToItsNumber(final String typed, final int expected) {
            assertThat(service.selectOption(typed, UserType.ADMIN).optionNumber())
                    .as("the source right-pads the field to two bytes, scans backward over trailing blanks, "
                            + "then substitutes '0' for every remaining blank, so '1', '01', ' 1' and '1 ' "
                            + "all normalise to '01'")
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{0}] is refused")
        @ValueSource(strings = {"", " ", "  ", "0", "00", "0 ", " 0", "11", "99", "1X", "X", "-1", "1.",
            "abc", "111", "1  ", "+1"})
        @DisplayName("an unusable option field is refused with the redisplayed notice")
        void anUnusableOptionFieldIsRefused(final String typed) {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption(typed, UserType.ADMIN));

            assertThat(failure)
                    .as("app/cbl/COMEN01C.cbl:L133 refuses a field that is non-numeric after "
                            + "substitution, zero, or beyond the populated count; each of these is one of "
                            + "those three cases")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .as("the source redisplays one notice for all three refusal causes rather than "
                            + "distinguishing them, and that undifferentiated notice is the behaviour")
                    .isEqualTo(INVALID_OPTION_NOTICE);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent option field normalises to two zeros and is refused like an untouched screen")
        void anAbsentOptionFieldIsRefused(final String typed) {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption(typed, UserType.ADMIN));

            assertThat(failure)
                    .as("an absent field is the stateless equivalent of a screen the operator submitted "
                            + "without typing anything; the source normalises the blanks to '00', which "
                            + "fails the zero test rather than the numeric test")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .as("an absent field is not a distinct outcome; it collapses into the same notice")
                    .isEqualTo(INVALID_OPTION_NOTICE);
        }

        @Test
        @DisplayName("an over-length field is refused with the same notice as an unusable one")
        void anOverLengthFieldIsRefusedWithTheSameNotice() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption("001", UserType.ADMIN));

            assertThat(failure.getMessage())
                    .as("OPTIONI is PIC X(02), so three characters could not have reached the program "
                            + "under BMS at all; the stateless target must refuse them, and refusing them "
                            + "with the same notice keeps the observable behaviour identical")
                    .isEqualTo(INVALID_OPTION_NOTICE);
        }

        @Test
        @DisplayName("an over-length field that would otherwise be in range is still refused")
        void anOverLengthFieldThatWouldBeInRangeIsStillRefused() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption(" 1 ", UserType.ADMIN)))
                    .as("' 1 ' would normalise to a usable option were it two bytes wide, so refusing it "
                            + "proves the width test runs before normalisation rather than after")
                    .isNotNull();
        }

        @Test
        @DisplayName("every refusal names the option field and classifies the failure as invalid")
        void everyRefusalNamesTheOptionField() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption("0", UserType.ADMIN));

            assertThat(failure.getFieldName())
                    .as("the field name is what drives the per-field error marker of app/cpy/CSSETATY.cpy, "
                            + "so a refusal that names no field could not turn the screen field red")
                    .isEqualTo("option");
            assertThat(failure.getFailureKind())
                    .as("the field was supplied and is unusable rather than blank, so the kind is INVALID; "
                            + "BLANK carries the additional asterisk marker, which this case must not")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
        }

        @Test
        @DisplayName("the substitution maps a blank to zero rather than discarding it")
        void theSubstitutionMapsABlankToZeroRatherThanDiscardingIt() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption(" ", UserType.ADMIN)))
                    .as("a trim-then-parse implementation would see an empty string and could report a "
                            + "parse error; the source substitutes zeros and reports the zero refusal, "
                            + "which is why a single blank behaves exactly like a typed '0'")
                    .isNotNull();
        }

        @Test
        @DisplayName("the boundary between the last usable and first unusable number is exact")
        void theBoundaryBetweenUsableAndUnusableIsExact() {
            assertThat(service.selectOption("10", UserType.ADMIN).optionNumber())
                    .as("ten is the populated count and is inclusive, because the source tests strictly "
                            + "greater than")
                    .isEqualTo(10);
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption("11", UserType.ADMIN)))
                    .as("eleven lies inside the OCCURS 12 storage capacity but beyond the populated count, "
                            + "and it is the populated count that bounds the menu")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("Selection resolution: the program a usable option transfers to")
    final class SelectionResolution {

        @ParameterizedTest(name = "option {0} transfers to {1}")
        @CsvSource({
            "1, COACTVWC", "2, COACTUPC", "3, COCRDLIC", "4, COCRDSLC", "5, COCRDUPC",
            "6, COTRN00C", "7, COTRN01C", "8, COTRN02C", "9, CORPT00C", "10, COBIL00C",
        })
        @DisplayName("each usable option resolves to the program its copybook entry names")
        void eachUsableOptionResolvesToItsProgram(final String typed, final String program) {
            assertThat(service.selectOption(typed, UserType.USER).targetProgram())
                    .as("this is the EXEC CICS XCTL PROGRAM target of app/cbl/COMEN01C.cbl:L147, which the "
                            + "stateless target returns rather than transferring to")
                    .isEqualTo(program);
        }

        @Test
        @DisplayName("a resolved selection carries the unstripped PIC X(35) caption")
        void aResolvedSelectionCarriesTheUnstrippedCaption() {
            final MainMenuService.MenuSelection selection = service.selectOption("1", UserType.ADMIN);

            assertThat(selection.optionName())
                    .as("the selection carries the caption as the table holds it, space-padded to the "
                            + "declared field width, because that is the value the source would have moved")
                    .hasSize(CAPTION_WIDTH)
                    .startsWith("Account View");
        }

        @Test
        @DisplayName("a real target is not a placeholder and carries no notice")
        void aRealTargetIsNotAPlaceholderAndCarriesNoNotice() {
            final MainMenuService.MenuSelection selection = service.selectOption("1", UserType.ADMIN);

            assertThat(selection.placeholder())
                    .as("COACTVWC exists, so the DUMMY prefix test at app/cbl/COMEN01C.cbl:L146 fails and "
                            + "control transfers")
                    .isFalse();
            assertThat(selection.message())
                    .as("under CICS the notice at :L157-L164 was unreachable once XCTL had transferred "
                            + "control, so a real target must carry the empty string rather than null")
                    .isEmpty();
        }

        @Test
        @DisplayName("the selection record renders all five of its components")
        void theSelectionRecordRendersAllFiveComponents() {
            assertThat(service.selectOption("1", UserType.ADMIN).toString())
                    .as("the record rendering is the diagnostic a controller logs, so every component must "
                            + "appear; a menu selection carries no personally identifiable data, which is "
                            + "why the default record rendering is left in place here")
                    .contains("optionNumber=1")
                    .contains("targetProgram=COACTVWC")
                    .contains("placeholder=false")
                    .contains("message=");
        }

        @Test
        @DisplayName("two selections of the same option are equal, and of different options are not")
        void selectionsCompareByValue() {
            final MainMenuService.MenuSelection first = service.selectOption("1", UserType.ADMIN);
            final MainMenuService.MenuSelection again = service.selectOption("01", UserType.ADMIN);
            final MainMenuService.MenuSelection other = service.selectOption("2", UserType.ADMIN);

            assertThat(first)
                    .as("'1' and '01' normalise to the same option, so the two selections must be equal "
                            + "even though the operator typed different characters")
                    .isEqualTo(again)
                    .hasSameHashCodeAs(again)
                    .isNotEqualTo(other);
        }
    }

    @Nested
    @DisplayName("The administrator-only gate, reachable only through the injected table")
    final class AdministratorOnlyGate {

        /** Entry 2 is administrator-only; the shipped table has no such entry, so it is injected here. */
        private final MainMenuService mixed = serviceOver(List.of(
                option(1, "Account View", "COACTVWC", 'U'),
                option(2, "Admin Only Thing", "COUSR00C", 'A'),
                option(3, "Bill Payment", "COBIL00C", 'U')));

        @Test
        @DisplayName("an administrator-only option is withheld from a standard user's screen")
        void anAdministratorOnlyOptionIsWithheldFromAStandardUser() {
            assertThat(mixed.getMainMenu(UserType.USER).menu().getOptionCount())
                    .as("SEND-MENU-SCREEN filters the table before rendering, so the option never reaches "
                            + "the screen a standard user is shown")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an administrator sees the administrator-only option")
        void anAdministratorSeesTheAdministratorOnlyOption() {
            assertThat(mixed.getMainMenu(UserType.ADMIN).menu().getOptionCount())
                    .as("the filter applies only when the signed-on class is the standard user, so an "
                            + "administrator sees the whole table")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the withheld option is the administrator-only one, not merely the last one")
        void theWithheldOptionIsTheAdministratorOnlyOne() {
            final List<MenuResponse.MainMenuOption> visible =
                    mixed.getMainMenu(UserType.USER).menu().getOptions();

            assertThat(visible)
                    .as("the filter selects on the user-type byte, so the surviving entries are those "
                            + "carrying 'U' and the numbering they keep is the table's own, not a "
                            + "renumbering of the filtered view")
                    .extracting(MenuResponse.MainMenuOption::optionNumber)
                    .containsExactly(1, 3);
        }

        @Test
        @DisplayName("the labels rendered for a standard user omit the withheld option")
        void theLabelsRenderedForAStandardUserOmitTheWithheldOption() {
            final List<String> labels = mixed.getMainMenu(UserType.USER).optionLabels();

            assertThat(labels)
                    .as("one line per visible option, each still keyed on the table's own option number, "
                            + "so the visible numbers jump from 01 to 03")
                    .hasSize(2)
                    .satisfies(rendered -> {
                        assertThat(rendered.getFirst()).startsWith("01. Account View");
                        assertThat(rendered.get(1)).startsWith("03. Bill Payment");
                    });
        }

        @Test
        @DisplayName("a standard user naming an administrator-only option is refused with its own notice")
        void aStandardUserNamingAnAdministratorOnlyOptionIsRefused() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> mixed.selectOption("2", UserType.USER));

            assertThat(failure.getMessage())
                    .as("app/cbl/COMEN01C.cbl:L137-L138 refuses the selection with a distinct notice, not "
                            + "with the generic invalid-option notice, so the operator learns that the "
                            + "option exists but is not theirs")
                    .isEqualTo(ADMIN_ONLY_NOTICE)
                    .isNotEqualTo(INVALID_OPTION_NOTICE);
        }

        @Test
        @DisplayName("the refusal notice keeps the trailing space its source literal declares")
        void theRefusalNoticeKeepsItsTrailingSpace() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> mixed.selectOption("2", UserType.USER));

            assertThat(failure.getMessage())
                    .as("the literal at app/cbl/COMEN01C.cbl:L138 ends in a blank, and the parity gates "
                            + "compare message text byte for byte, so trimming it would be a diff")
                    .endsWith(" ")
                    .hasSize(ADMIN_ONLY_NOTICE.length());
        }

        @Test
        @DisplayName("the refusal names the option field so the screen field can be marked")
        void theRefusalNamesTheOptionField() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> mixed.selectOption("2", UserType.USER));

            assertThat(failure.getFieldName())
                    .as("both refusal causes mark the same screen field, because both leave the operator "
                            + "on the menu with the option field in error")
                    .isEqualTo("option");
        }

        @Test
        @DisplayName("an administrator naming the administrator-only option is admitted")
        void anAdministratorNamingTheAdministratorOnlyOptionIsAdmitted() {
            final MainMenuService.MenuSelection selection = mixed.selectOption("2", UserType.ADMIN);

            assertThat(selection.targetProgram())
                    .as("the gate tests the signed-on class, so the same option that a standard user is "
                            + "refused resolves normally for an administrator")
                    .isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("the bounds check counts the whole table, not the standard user's filtered view")
        void theBoundsCheckCountsTheWholeTable() {
            final MainMenuService.MenuSelection selection = mixed.selectOption("3", UserType.USER);

            assertThat(selection.targetProgram())
                    .as("app/cbl/COMEN01C.cbl:L133 tests WS-OPTION against CDEMO-MENU-OPT-COUNT, a "
                            + "copybook constant that does not shrink when the filter withholds an entry; "
                            + "a standard user whose screen shows two lines can therefore still name "
                            + "option 3 and be admitted, and reproducing that is parity rather than a bug")
                    .isEqualTo("COBIL00C");
        }
    }

    @Nested
    @DisplayName("The DUMMY placeholder guard and its spliced coming-soon notice")
    final class PlaceholderProgramGuard {

        /** The shipped table names no DUMMY program, so placeholder entries are injected here. */
        private final MainMenuService withPlaceholders = serviceOver(List.of(
                option(1, "Account View", "COACTVWC", 'U'),
                option(2, "Future Feature", "DUMMY001", 'U'),
                option(3, "Solo", "DUMMY002", 'U')));

        @Test
        @DisplayName("a placeholder target is flagged as a placeholder")
        void aPlaceholderTargetIsFlagged() {
            assertThat(withPlaceholders.selectOption("2", UserType.USER).placeholder())
                    .as("app/cbl/COMEN01C.cbl:L146 tests the first five characters of the program name "
                            + "against 'DUMMY' and skips the XCTL when they match")
                    .isTrue();
        }

        @Test
        @DisplayName("a placeholder selection retains its DUMMY program name")
        void aPlaceholderSelectionRetainsItsDummyProgramName() {
            assertThat(withPlaceholders.selectOption("2", UserType.USER).targetProgram())
                    .as("the main menu returns the name it read from the table and lets the flag say the "
                            + "target is not real; the administrator menu blanks the name instead, and the "
                            + "two services genuinely diverge here - both are faithful to their own source")
                    .isEqualTo("DUMMY001");
        }

        @Test
        @DisplayName("the coming-soon notice splices the caption truncated at its first blank")
        void theComingSoonNoticeSplicesTheTruncatedCaption() {
            assertThat(withPlaceholders.selectOption("2", UserType.USER).message())
                    .as("the STRING at app/cbl/COMEN01C.cbl:L159-L163 delimits the caption operand BY "
                            + "SPACE, which truncates 'Future Feature' at its first blank and contributes "
                            + "no blank of its own, so the words run together")
                    .isEqualTo("This option Futureis coming soon ...");
        }

        @Test
        @DisplayName("the spliced notice has no separating space, exactly as DELIMITED BY SPACE produces")
        void theSplicedNoticeHasNoSeparatingSpace() {
            final String notice = withPlaceholders.selectOption("2", UserType.USER).message();

            assertThat(notice)
                    .as("this reads like a defect and is the source's behaviour; repairing it would change "
                            + "text the parity gates compare byte for byte, and app/cbl is frozen")
                    .doesNotContain("Future is")
                    .contains("Futureis");
        }

        @Test
        @DisplayName("a caption with no blank at all is spliced whole")
        void aCaptionWithNoBlankIsSplicedWhole() {
            assertThat(withPlaceholders.selectOption("3", UserType.USER).message())
                    .as("DELIMITED BY SPACE truncates at the first blank; when there is none the whole "
                            + "operand is contributed, which is why a single-word caption survives intact")
                    .isEqualTo("This option Solois coming soon ...");
        }

        @Test
        @DisplayName("the notice opens and closes with the two literal operands unchanged")
        void theNoticeOpensAndClosesWithTheLiteralOperands() {
            final String notice = withPlaceholders.selectOption("2", UserType.USER).message();

            assertThat(notice)
                    .as("the first and third STRING operands are literals delimited BY SIZE, so they "
                            + "contribute their declared text exactly, including the prefix's trailing "
                            + "blank and the suffix's absent leading blank")
                    .startsWith("This option ")
                    .endsWith("is coming soon ...");
        }

        @Test
        @DisplayName("a real target beside a placeholder is unaffected by the guard")
        void aRealTargetBesideAPlaceholderIsUnaffected() {
            final MainMenuService.MenuSelection real = withPlaceholders.selectOption("1", UserType.USER);

            assertThat(real.placeholder())
                    .as("the guard is per-entry, so one placeholder in the table does not make its "
                            + "neighbours placeholders")
                    .isFalse();
            assertThat(real.message())
                    .as("a real target still carries no notice, which the record's own invariant also "
                            + "requires")
                    .isEmpty();
        }

        @Test
        @DisplayName("a program name that merely contains DUMMY is not a placeholder")
        void aProgramNameThatMerelyContainsDummyIsNotAPlaceholder() {
            final MainMenuService notPrefixed =
                    serviceOver(List.of(option(1, "Odd Name", "XDUMMY01", 'U')));

            assertThat(notPrefixed.selectOption("1", UserType.USER).placeholder())
                    .as("the source tests the reference modification (1:5), a prefix test rather than a "
                            + "containment test, so a name with DUMMY elsewhere in it is a real target")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The injected option table: the guards on the test seam itself")
    final class InjectedTableGuards {

        @Test
        @DisplayName("a null option table is refused")
        void aNullOptionTableIsRefused() {
            final IllegalArgumentException failure = catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(null));

            assertThat(failure.getMessage())
                    .as("a null table is a wiring defect; the message must point at the empty list as the "
                            + "way to express a menu that offers nothing")
                    .contains("menuOptions must not be null");
        }

        @Test
        @DisplayName("an empty option table is accepted, unlike the administrator menu's")
        void anEmptyOptionTableIsAccepted() {
            assertThatCode(() -> serviceOver(List.of()))
                    .as("MainMenuService accepts an empty table and AdminMenuService rejects one; the two "
                            + "constructors genuinely differ, and pinning the difference here stops a "
                            + "later edit from quietly harmonising them")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an option table containing a null element is refused, naming the index")
        void anOptionTableContainingANullElementIsRefused() {
            final List<MenuResponse.MainMenuOption> withNull =
                    Arrays.asList(option(1, "Account View", "COACTVWC", 'U'), null);

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(withNull)).getMessage())
                    .as("naming the offending subscript is what makes the defect locatable in a table that "
                            + "may hold ten entries")
                    .contains("must not contain a null element")
                    .contains("index 1");
        }

        @Test
        @DisplayName("an option table beyond the populated count is refused, and OCCURS is not the bound")
        void anOptionTableBeyondThePopulatedCountIsRefused() {
            final List<MenuResponse.MainMenuOption> eleven = new ArrayList<>();
            for (int number = 1; number <= 11; number++) {
                eleven.add(option(number, "Caption " + number, "PGM0000" + (number % 10), 'U'));
            }

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(eleven)).getMessage())
                    .as("eleven entries fit the REDEFINES OCCURS 12 storage but exceed the ten the "
                            + "copybook populates; the spare subscripts hold no caption, program or user "
                            + "type, so admitting them would offer options that resolve to nothing")
                    .contains("holds 11 entries")
                    .contains("exceeds the 10")
                    .contains("OCCURS capacity of 12");
        }

        @Test
        @DisplayName("a table of exactly the populated count is accepted")
        void aTableOfExactlyThePopulatedCountIsAccepted() {
            final List<MenuResponse.MainMenuOption> ten = new ArrayList<>();
            for (int number = 1; number <= 10; number++) {
                ten.add(option(number, "Caption " + number, "PGM0000" + (number % 10), 'U'));
            }

            assertThatCode(() -> serviceOver(ten))
                    .as("the bound is inclusive, because ten is the count the copybook populates")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an empty menu renders no options and still resolves a sign-on target")
        void anEmptyMenuRendersNoOptionsAndStillResolvesASignOnTarget() {
            final MainMenuService.MainMenuScreen screen =
                    serviceOver(List.of()).getMainMenu(UserType.ADMIN);

            assertThat(screen.menu().getOptionCount())
                    .as("an empty table offers nothing")
                    .isZero();
            assertThat(screen.optionLabels())
                    .as("one line per option means no lines")
                    .isEmpty();
            assertThat(screen.signOnTarget())
                    .as("PF3 is not table-driven, so the sign-on target survives an empty menu")
                    .isEqualTo(SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("every selection against an empty menu is refused")
        void everySelectionAgainstAnEmptyMenuIsRefused() {
            final MainMenuService empty = serviceOver(List.of());

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> empty.selectOption("1", UserType.ADMIN)).getMessage())
                    .as("with a populated count of zero, every number is greater than the count, so the "
                            + "bounds test refuses everything without ever indexing the table")
                    .isEqualTo(INVALID_OPTION_NOTICE);
        }

        @Test
        @DisplayName("the injected table is copied defensively")
        void theInjectedTableIsCopiedDefensively() {
            final List<MenuResponse.MainMenuOption> mutable =
                    new ArrayList<>(List.of(option(1, "Account View", "COACTVWC", 'U')));
            final MainMenuService injected = serviceOver(mutable);
            mutable.add(option(2, "Late Arrival", "COBIL00C", 'U'));

            assertThat(injected.getMainMenu(UserType.ADMIN).menu().getOptionCount())
                    .as("the constructor copies the list, so a caller mutating its own list afterwards "
                            + "cannot change what the service offers")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("The MainMenuScreen record contract")
    final class MainMenuScreenRecordContract {

        private final MenuResponse<MenuResponse.MainMenuOption> oneOption =
                MenuResponse.ofMainMenu(List.of(option(1, "Account View", "COACTVWC", 'U')));

        @Test
        @DisplayName("an absent menu is refused")
        void anAbsentMenuIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(null, List.of("01. x"), SIGN_ON_PROGRAM))
                    .getMessage())
                    .as("a screen without a menu could not be rendered; the message must point at the "
                            + "empty-list factory as the way to express an empty menu")
                    .contains("menu must not be null");
        }

        @Test
        @DisplayName("an absent label list is refused")
        void anAbsentLabelListIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(oneOption, null, SIGN_ON_PROGRAM))
                    .getMessage())
                    .as("null and empty are different states; only one of them is legitimate")
                    .contains("optionLabels must not be null");
        }

        @ParameterizedTest(name = "sign-on target [{0}]")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank sign-on target is refused, because the source always resolves one")
        void aBlankSignOnTargetIsRefused(final String target) {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(oneOption, List.of("01. x"), target))
                    .getMessage())
                    .as("app/cbl/COMEN01C.cbl:L172-L174 moves a literal program name unconditionally, so "
                            + "a blank target could not arise from the source")
                    .contains("signOnTarget must name a program")
                    .contains(SIGN_ON_PROGRAM);
        }

        @Test
        @DisplayName("an absent sign-on target is refused for the same reason as a blank one")
        void anAbsentSignOnTargetIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(oneOption, List.of("01. x"), null))
                    .getMessage())
                    .as("null and blank collapse into one refusal here, because neither names a program")
                    .contains("signOnTarget must name a program");
        }

        @Test
        @DisplayName("a null label is refused, naming its index")
        void aNullLabelIsRefused() {
            final MenuResponse<MenuResponse.MainMenuOption> twoOptions = MenuResponse.ofMainMenu(List.of(
                    option(1, "Account View", "COACTVWC", 'U'),
                    option(2, "Bill Payment", "COBIL00C", 'U')));

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(twoOptions,
                            Arrays.asList("01. x", null), SIGN_ON_PROGRAM))
                    .getMessage())
                    .as("a null line would render as the four characters 'null' on a forty-column screen "
                            + "slot, so it is refused at construction and the index locates it")
                    .contains("optionLabels must not contain a null element")
                    .contains("index 1");
        }

        @Test
        @DisplayName("a label count that disagrees with the option count is refused")
        void aLabelCountThatDisagreesWithTheOptionCountIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MainMenuScreen(oneOption,
                            List.of("01. x", "02. y"), SIGN_ON_PROGRAM))
                    .getMessage())
                    .as("the source renders exactly one line per option, so a disagreement means either a "
                            + "blank row or a dropped option; catching it at construction is cheaper than "
                            + "diagnosing a misaligned screen")
                    .contains("holds 2 lines")
                    .contains("carries 1 options");
        }

        @Test
        @DisplayName("the record copies the label list defensively and exposes it unmodifiably")
        void theRecordCopiesTheLabelListDefensively() {
            final List<String> mutable = new ArrayList<>(List.of("01. Account View"));
            final MainMenuService.MainMenuScreen screen =
                    new MainMenuService.MainMenuScreen(oneOption, mutable, SIGN_ON_PROGRAM);
            mutable.add("02. Late Arrival");

            assertThat(screen.optionLabels())
                    .as("the compact constructor reassigns the component to a copy, so the caller's later "
                            + "mutation cannot reach the record")
                    .hasSize(1);
            assertThatCode(() -> screen.optionLabels().add("03. Injected"))
                    .as("the copy is unmodifiable, so a consumer cannot alter the rendered screen either")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a valid screen is accepted and exposes its three components")
        void aValidScreenIsAcceptedAndExposesItsComponents() {
            final MainMenuService.MainMenuScreen screen =
                    new MainMenuService.MainMenuScreen(oneOption, List.of("01. Account View"),
                            SIGN_ON_PROGRAM);

            assertThat(screen.menu())
                    .as("the menu component is carried through unchanged")
                    .isSameAs(oneOption);
            assertThat(screen.signOnTarget())
                    .as("the sign-on target is carried through unchanged")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(screen.optionLabels())
                    .as("the single label survives the defensive copy")
                    .containsExactly("01. Account View");
        }

        @Test
        @DisplayName("two screens over the same values are equal")
        void twoScreensOverTheSameValuesAreEqual() {
            final MainMenuService.MainMenuScreen first =
                    new MainMenuService.MainMenuScreen(oneOption, List.of("01. x"), SIGN_ON_PROGRAM);
            final MainMenuService.MainMenuScreen second =
                    new MainMenuService.MainMenuScreen(oneOption, List.of("01. x"), SIGN_ON_PROGRAM);

            assertThat(first)
                    .as("the screen is a value object, so equality is by component and the defensive copy "
                            + "must not defeat it")
                    .isEqualTo(second)
                    .hasSameHashCodeAs(second);
        }
    }

    @Nested
    @DisplayName("The MenuSelection record contract and its XCTL fall-through invariant")
    final class MenuSelectionRecordContract {

        @ParameterizedTest(name = "option number {0}")
        @ValueSource(ints = {0, -1, 100, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("an option number outside the two-digit picture range is refused")
        void anOptionNumberOutsideTheDeclaredRangeIsRefused(final int optionNumber) {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(optionNumber, "n", "PGM", false, ""))
                    .getMessage())
                    .as("CDEMO-MENU-OPT-NUM is PIC 9(02), so ninety-nine is the largest value the field "
                            + "can hold, and app/cbl/COMEN01C.cbl:L133 refuses zero explicitly")
                    .contains("optionNumber must be between 1 and 99")
                    .contains("but was " + optionNumber);
        }

        @ParameterizedTest(name = "option number {0}")
        @ValueSource(ints = {1, 2, 10, 98, 99})
        @DisplayName("an option number inside the two-digit picture range is accepted")
        void anOptionNumberInsideTheDeclaredRangeIsAccepted(final int optionNumber) {
            assertThatCode(() -> new MainMenuService.MenuSelection(optionNumber, "n", "PGM", false, ""))
                    .as("both bounds are inclusive; the record models the field's capacity rather than the "
                            + "menu's populated count, because a substitute table may hold fewer entries")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent caption is refused")
        void anAbsentCaptionIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, null, "PGM", false, ""))
                    .getMessage())
                    .as("every populated copybook entry carries a caption, so null could not arise from "
                            + "the table")
                    .contains("optionName must not be null");
        }

        @Test
        @DisplayName("an empty caption is accepted, because only null is impossible")
        void anEmptyCaptionIsAccepted() {
            assertThatCode(() -> new MainMenuService.MenuSelection(1, "", "PGM", false, ""))
                    .as("the guard is a null test rather than a blank test, and widening it would refuse a "
                            + "substitute table whose caption field is genuinely all blanks")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "target program [{0}]")
        @ValueSource(strings = {"", " ", "    "})
        @DisplayName("a blank target program is refused")
        void aBlankTargetProgramIsRefused(final String targetProgram) {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, "n", targetProgram, false, ""))
                    .getMessage())
                    .as("every populated entry of app/cpy/COMEN02Y.cpy carries an eight-character program "
                            + "name, including the DUMMY placeholders, so a blank name is impossible")
                    .contains("targetProgram must name a program");
        }

        @Test
        @DisplayName("an absent target program is refused")
        void anAbsentTargetProgramIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, "n", null, false, ""))
                    .getMessage())
                    .as("null and blank collapse into one refusal, because neither names a program")
                    .contains("targetProgram must name a program");
        }

        @Test
        @DisplayName("an absent notice is refused, because the empty string is how absence is expressed")
        void anAbsentNoticeIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, "n", "PGM", true, null))
                    .getMessage())
                    .as("the source moves spaces rather than leaving the field unset, so the Java analogue "
                            + "of no notice is the empty string and never null")
                    .contains("message must not be null");
        }

        @Test
        @DisplayName("a real target carrying a notice is refused by the XCTL fall-through invariant")
        void aRealTargetCarryingANoticeIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, "n", "COACTVWC", false, "a notice"))
                    .getMessage())
                    .as("under CICS the notice at app/cbl/COMEN01C.cbl:L157-L164 was unreachable once XCTL "
                            + "had transferred control, so a real target with a notice is a state the "
                            + "source could not produce; in a stateless target nothing enforces that but "
                            + "this record")
                    .contains("message must be empty for a real target")
                    .contains("placeholder was false")
                    .contains("the message was non-empty");
        }

        @Test
        @DisplayName("a placeholder target carrying no notice is refused by the same invariant")
        void aPlaceholderTargetCarryingNoNoticeIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> new MainMenuService.MenuSelection(1, "n", "DUMMY001", true, ""))
                    .getMessage())
                    .as("the STRING at :L159-L163 always assembles a non-empty notice, so a placeholder "
                            + "without one is equally impossible; the invariant is an exclusive-or rather "
                            + "than two independent tests")
                    .contains("placeholder was true")
                    .contains("the message was empty");
        }

        @Test
        @DisplayName("both legitimate combinations of the invariant are accepted")
        void bothLegitimateCombinationsAreAccepted() {
            assertThatCode(() -> new MainMenuService.MenuSelection(1, "n", "COACTVWC", false, ""))
                    .as("a real target with no notice is the XCTL path")
                    .doesNotThrowAnyException();
            assertThatCode(() -> new MainMenuService.MenuSelection(1, "n", "DUMMY001", true, "notice"))
                    .as("a placeholder with a notice is the fall-through path")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a valid selection exposes all five of its components")
        void aValidSelectionExposesAllFiveComponents() {
            final MainMenuService.MenuSelection selection =
                    new MainMenuService.MenuSelection(7, "Transaction View", "COTRN01C", false, "");

            assertThat(selection.optionNumber()).as("the option number component").isEqualTo(7);
            assertThat(selection.optionName()).as("the caption component").isEqualTo("Transaction View");
            assertThat(selection.targetProgram()).as("the program component").isEqualTo("COTRN01C");
            assertThat(selection.placeholder()).as("the placeholder flag component").isFalse();
            assertThat(selection.message()).as("the notice component").isEmpty();
        }
    }

    @Nested
    @DisplayName("Statelessness: the service holds no per-request state")
    final class Statelessness {

        @Test
        @DisplayName("repeated screen requests are equal, so no counter or cursor accumulates")
        void repeatedScreenRequestsAreEqual() {
            final MainMenuService.MainMenuScreen first = service.getMainMenu(UserType.ADMIN);
            final MainMenuService.MainMenuScreen second = service.getMainMenu(UserType.ADMIN);

            assertThat(first)
                    .as("the COMMAREA carried the pseudo-conversational enter-versus-re-enter flag; the "
                            + "stateless target keeps no such flag, so two identical requests must produce "
                            + "identical screens")
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("a refused selection leaves the next selection unaffected")
        void aRefusedSelectionLeavesTheNextUnaffected() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.selectOption("99", UserType.ADMIN)))
                    .as("the first selection is refused")
                    .isNotNull();

            assertThat(service.selectOption("1", UserType.ADMIN).targetProgram())
                    .as("no error flag survives the refusal; the legacy ERR-FLG-ON lived in working "
                            + "storage that CICS reinitialised per task, and the Java analogue of that is "
                            + "holding no flag at all")
                    .isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("two independently constructed services agree on every canonical option")
        void twoIndependentlyConstructedServicesAgree() {
            assertThat(new MainMenuService().getMainMenu(UserType.ADMIN))
                    .as("the canonical table is a shared immutable constant, so instances are "
                            + "interchangeable and the bean may safely be a singleton")
                    .isEqualTo(new MainMenuService().getMainMenu(UserType.ADMIN));
        }

        @Test
        @DisplayName("a selection is unaffected by the screen having been rendered first")
        void aSelectionIsUnaffectedByAPriorScreenRender() {
            final MainMenuService.MenuSelection withoutRender = service.selectOption("5", UserType.USER);
            service.getMainMenu(UserType.USER);
            final MainMenuService.MenuSelection afterRender = service.selectOption("5", UserType.USER);

            assertThat(afterRender)
                    .as("SEND-MENU-SCREEN and PROCESS-ENTER-KEY shared working storage under CICS but "
                            + "exchanged nothing that outlived the task; the Java pair must be "
                            + "order-independent")
                    .isEqualTo(withoutRender);
        }
    }
}
