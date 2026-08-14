/*
 * ******************************************************************
 * Program     : AdminMenuServiceCoverageTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the administrator menu dispatch of COADM01C: the
 *               four populated option-table entries, the labels it
 *               renders with trailing blanks stripped rather than
 *               padded to a screen slot, the right-justify plus
 *               space-to-zero normalisation of the two-byte option
 *               field, the DUMMY placeholder guard that blanks the
 *               target program, the fixed coming-soon notice that
 *               carries no option name because the source operand is
 *               commented out, and the PF3 sign-on fall-back.
 * Source      : app/cbl/COADM01C.cbl:L108-L175 (PROCESS-ENTER-KEY: the
 *               backward scan, the space-to-zero substitution, the
 *               numeric and bounds test against
 *               CDEMO-ADMIN-OPT-COUNT, the (1:5) DUMMY guard, and the
 *               STRING at :L145-L156 whose option-name operands at
 *               :L150-L151 are commented out with an asterisk in
 *               column 7) @ 7756d89
 * Source      : app/cpy/COADM02Y.cpy (CDEMO-ADMIN-OPT-COUNT VALUE 4,
 *               the four populated entries each carrying PIC 9(02)
 *               number, PIC X(35) name and PIC X(08) program with no
 *               user-type byte, and the REDEFINES OCCURS 9)
 *               @ 7756d89
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
import com.cardemo.service.menu.AdminMenuService;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link AdminMenuService}, the Java target of {@code app/cbl/COADM01C.cbl}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The administrator menu is the main menu's near-twin: the same backward scan, the same space-to-zero
 * substitution, the same numeric-and-bounds refusal, the same {@code DUMMY} prefix guard. It is emphatically
 * <em>not</em> the same code, and the differences are the reason this test exists as a peer of
 * {@code MainMenuServiceTest} rather than as a few extra cases inside it. Six differences are asserted here
 * as first-class subjects, each with the source locator that makes it correct:</p>
 *
 * <ul>
 *   <li><strong>The coming-soon notice carries no option name.</strong> {@code app/cbl/COMEN01C.cbl}
 *       splices the caption into its notice; {@code app/cbl/COADM01C.cbl} does not, because the two operands
 *       that would have contributed it - the {@code CDEMO-ADMIN-OPT-NAME} reference and its
 *       {@code DELIMITED BY SIZE} clause at {@code :L150-L151} - are commented out with an asterisk in
 *       column 7. The admin notice is therefore the fixed thirty-character
 *       {@code "This option is coming soon ..."}. This is easy to get wrong: a column-truncating extract
 *       such as {@code cut -c8-79} hides column 7 and makes the operands look live.</li>
 *   <li><strong>A placeholder blanks the target program.</strong> The main menu returns the
 *       {@code DUMMY} name it read; the admin menu substitutes the empty string and lets the flag carry the
 *       whole meaning.</li>
 *   <li><strong>Labels are stripped, not padded.</strong> The main menu pads every line to a forty-column
 *       screen slot; the admin menu strips trailing blanks, so its lines are ragged and of differing
 *       length.</li>
 *   <li><strong>There is no user-class gate.</strong> {@code app/cpy/COADM02Y.cpy} has no user-type byte at
 *       all - reaching this transaction is itself the authorisation - so no option can be withheld and
 *       {@code selectOption} takes no {@link com.cardemo.model.enums.UserType}.</li>
 *   <li><strong>An empty option table is refused.</strong> The main menu's injectable constructor accepts
 *       one; this one does not.</li>
 *   <li><strong>The records carry no guards and no defensive copy.</strong>
 *       {@code MainMenuService.MainMenuScreen} and {@code MenuSelection} validate five conditions each and
 *       copy their label list; {@code AdminMenuView} and {@code AdminMenuSelection} are plain records that
 *       accept anything, including all-null components. That asymmetry is pinned here as <em>observed</em>
 *       behaviour so that a later edit which adds or removes a guard is visible as a test change rather
 *       than as silence.</li>
 *   </ul>
 *
 * <p>One further observation is recorded rather than exploited: the service takes a {@link Clock}, but
 * nothing the clock feeds escapes the service. {@code populateHeaderInfo} composes a header string that is
 * passed only to a debug log statement, and {@code AdminMenuView} does not carry it. Two fixed clocks
 * thirty-eight years apart therefore produce equal views, and this test asserts that invariance instead of
 * pretending the clock is observable.</p>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>
 *   ./mvnw -B -ntp -o test -Dtest=AdminMenuServiceCoverageTest -DfailIfNoSpecifiedTests=false
 *   ./mvnw -B -ntp -o clean verify -Ddependency-check.skip=true   (full gate: coverage floor plus tests)
 * </pre>
 *
 * <p>No profile, container, database or network endpoint is required.</p>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>The public constructor binds the system default-zone clock and the canonical four-entry table
 * {@code MenuResponse.ADMIN_MENU_OPTIONS}, transcribed from {@code app/cpy/COADM02Y.cpy}. The
 * package-private constructor accepts a substitute clock and table and exists as a test seam for the
 * {@code DUMMY} placeholder branch, which the canonical table cannot reach because none of its four entries
 * names a placeholder program. Because this test lives in {@code com.cardemo.unit.service} rather than
 * {@code com.cardemo.service.menu}, that constructor is reached reflectively with
 * {@code setAccessible(true)}. Unlike {@link com.cardemo.service.menu.MainMenuService}, this service
 * publishes its message and identity literals as {@code public} constants, so they are referenced directly
 * here rather than duplicated as test literals.</p>
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code CanonicalOptionTable} means {@code MenuResponse.ADMIN_MENU_OPTIONS} has drifted
 *       from {@code app/cpy/COADM02Y.cpy}. Recount the copybook; it is the authority.</li>
 *   <li>A failure asserting that the coming-soon notice contains no option name is almost always someone
 *       having "restored" the commented-out operands. Verify column 7 of
 *       {@code app/cbl/COADM01C.cbl:L150-L151} with an extract that does not truncate columns, for example
 *       {@code awk '{print substr($0,1,72)}'}, before changing anything.</li>
 *   <li>A failure in {@code LabelRendering} on a length assertion usually means the {@code stripTrailing}
 *       has been replaced by the main menu's forty-column padding. The two services differ here on
 *       purpose.</li>
 *   <li>A failure in {@code RecordContracts} means guards have been added to, or removed from, the admin
 *       records. That may well be an improvement, but it is a behaviour change and belongs in the decision
 *       log rather than in a silent edit.</li>
 *   </ul>
 */
@DisplayName("AdminMenuService - the COADM01C administrator menu dispatch")
final class AdminMenuServiceCoverageTest {

    /** The declared caption width, {@code PIC X(35)} at {@code app/cpy/COADM02Y.cpy}. */
    private static final int CAPTION_WIDTH = MenuResponse.OPTION_NAME_LENGTH;

    /** The service under test, bound to the canonical four-entry table by its public constructor. */
    private final AdminMenuService service = new AdminMenuService();

    /**
     * Constructs a service over a substitute clock and option table through the package-private constructor.
     *
     * <p>This is the deliberate test seam for the {@code DUMMY} placeholder guard, which the canonical
     * table cannot reach. Any {@link RuntimeException} the constructor raises is unwrapped and rethrown so
     * that guard assertions see the real exception rather than an {@link InvocationTargetException}.</p>
     *
     * @param clock   the clock to inject
     * @param options the substitute table
     * @return a service bound to {@code clock} and {@code options}
     */
    private static AdminMenuService serviceOver(final Clock clock,
            final List<MenuResponse.AdminMenuOption> options) {
        try {
            final Constructor<AdminMenuService> injectable =
                    AdminMenuService.class.getDeclaredConstructor(Clock.class, List.class);
            injectable.setAccessible(true);
            return injectable.newInstance(clock, options);
        } catch (final InvocationTargetException invocationFailure) {
            throw unwrap(invocationFailure);
        } catch (final ReflectiveOperationException reflectionFailure) {
            throw new IllegalStateException(
                    "the package-private AdminMenuService(Clock, List) test seam is no longer reachable",
                    reflectionFailure);
        }
    }

    /**
     * Constructs a service over a substitute option table and an arbitrary fixed clock.
     *
     * @param options the substitute table
     * @return a service bound to {@code options}
     */
    private static AdminMenuService serviceOver(final List<MenuResponse.AdminMenuOption> options) {
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
     * Builds one administrator menu option, so that substitute tables read as data.
     *
     * @param number  the option number
     * @param caption the option caption
     * @param program the target program name
     * @return the option
     */
    private static MenuResponse.AdminMenuOption option(final int number, final String caption,
            final String program) {
        return new MenuResponse.AdminMenuOption(number, caption, program);
    }

    @Nested
    @DisplayName("Identity constants that name the legacy transaction and program")
    final class IdentityConstants {

        @Test
        @DisplayName("the service names the COBOL program it replaces")
        void theServiceNamesTheProgramItReplaces() {
            assertThat(AdminMenuService.PROGRAM_NAME)
                    .as("the program name is what the legacy screen header displayed as PGMNAME, and it is "
                            + "the traceability anchor for every citation in this test")
                    .isEqualTo("COADM01C");
        }

        @Test
        @DisplayName("the service names the CICS transaction it replaces")
        void theServiceNamesTheTransactionItReplaces() {
            assertThat(AdminMenuService.TRANSACTION_ID)
                    .as("CA00 is the transaction the CSD maps to COADM01C, and it is distinct from CM00, "
                            + "which fronts the main menu; conflating the two would merge the two menus")
                    .isEqualTo("CA00")
                    .hasSize(4);
        }

        @Test
        @DisplayName("the sign-on program is the one the source falls back to on PF3")
        void theSignOnProgramIsTheSourceFallBack() {
            assertThat(AdminMenuService.SIGN_ON_PROGRAM)
                    .as("RETURN-TO-SIGN-ON-SCREEN moves this literal when no program has been requested, "
                            + "and both menus share the same sign-on target")
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the invalid-option notice is the same literal the main menu redisplays")
        void theInvalidOptionNoticeMatchesTheMainMenu() {
            assertThat(AdminMenuService.INVALID_OPTION_MESSAGE)
                    .as("both programs carry the same literal; they are separate declarations in separate "
                            + "programs that happen to agree, and the parity gates compare the text")
                    .isEqualTo("Please enter a valid option number...")
                    .endsWith("...");
        }

        @Test
        @DisplayName("the coming-soon notice is the fixed thirty-character literal with no option name")
        void theComingSoonNoticeIsTheFixedLiteral() {
            assertThat(AdminMenuService.COMING_SOON_MESSAGE)
                    .as("app/cbl/COADM01C.cbl:L150-L151 comments out the CDEMO-ADMIN-OPT-NAME operand and "
                            + "its DELIMITED BY SIZE clause with an asterisk in column 7, so the STRING "
                            + "concatenates only the two surviving literals and the caption never appears")
                    .isEqualTo("This option is coming soon ...")
                    .hasSize(30);
        }

        @Test
        @DisplayName("the admin notice differs from the main menu's spliced notice")
        void theAdminNoticeDiffersFromTheMainMenusSplicedNotice() {
            assertThat(AdminMenuService.COMING_SOON_MESSAGE)
                    .as("the main menu produces 'This option Accountis coming soon ...' for its first "
                            + "option because its caption operand is live; the admin menu produces the "
                            + "same text with the caption absent and, in consequence, with the single "
                            + "blank that the caption's DELIMITED BY SPACE truncation had swallowed")
                    .isEqualTo("This option " + "is coming soon ...")
                    .doesNotContain("User List");
        }
    }

    @Nested
    @DisplayName("The canonical option table transcribed from app/cpy/COADM02Y.cpy")
    final class CanonicalOptionTable {

        @Test
        @DisplayName("the menu offers the four options CDEMO-ADMIN-OPT-COUNT declares populated")
        void theMenuOffersTheFourPopulatedOptions() {
            assertThat(service.getMenuScreen().menu().getOptionCount())
                    .as("app/cpy/COADM02Y.cpy sets CDEMO-ADMIN-OPT-COUNT to 4; the REDEFINES OCCURS 9 is a "
                            + "storage capacity whose five spare subscripts are unpopulated and is "
                            + "therefore not the bound the menu offers")
                    .isEqualTo(4);
        }

        @ParameterizedTest(name = "option {0} is {2}")
        @CsvSource({
            "1, User List (Security), COUSR00C",
            "2, User Add (Security), COUSR01C",
            "3, User Update (Security), COUSR02C",
            "4, User Delete (Security), COUSR03C",
        })
        @DisplayName("each populated entry carries the number, caption and program the copybook declares")
        void eachPopulatedEntryMatchesTheCopybook(final int number, final String caption,
                final String program) {
            final MenuResponse.AdminMenuOption entry =
                    service.getMenuScreen().menu().getOptions().get(number - 1);

            assertThat(entry.optionNumber())
                    .as("the table is offered in copybook subscript order, so entry %d is option %d",
                            number, number)
                    .isEqualTo(number);
            assertThat(entry.optionName().stripTrailing())
                    .as("the caption of option %d, once the PIC X(35) padding is discounted", number)
                    .isEqualTo(caption);
            assertThat(entry.programName())
                    .as("CDEMO-ADMIN-OPT-PGMNAME of option %d, PIC X(08)", number)
                    .isEqualTo(program);
        }

        @Test
        @DisplayName("every caption occupies the full PIC X(35) declared width, unstripped")
        void everyCaptionOccupiesTheDeclaredCaptionWidth() {
            assertThat(service.getMenuScreen().menu().getOptions())
                    .as("CDEMO-ADMIN-OPT-NAME is PIC X(35), so a shorter caption is stored space-padded to "
                            + "the field width; the label renderer strips that padding but the table "
                            + "entry keeps it")
                    .allSatisfy(entry -> assertThat(entry.optionName()).hasSize(CAPTION_WIDTH));
        }

        @Test
        @DisplayName("every program name occupies the PIC X(08) declared width")
        void everyProgramNameOccupiesTheDeclaredWidth() {
            assertThat(service.getMenuScreen().menu().getOptions())
                    .as("all four entries fill CDEMO-ADMIN-OPT-PGMNAME exactly, which is what lets the "
                            + "(1:5) DUMMY reference modification be safe without a length test")
                    .allSatisfy(entry -> assertThat(entry.programName())
                            .hasSize(MenuResponse.PROGRAM_NAME_LENGTH));
        }

        @Test
        @DisplayName("all four programs are the user administration screens, in security order")
        void allFourProgramsAreTheUserAdministrationScreens() {
            assertThat(service.getMenuScreen().menu().getOptions())
                    .as("the administrator menu fronts exactly the four COUSR programs; the CSD maps them "
                            + "to CU00, CU01, CU02 and CU03, and no other transaction is reachable here")
                    .extracting(MenuResponse.AdminMenuOption::programName)
                    .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");
        }

        @Test
        @DisplayName("the response identifies itself as the admin menu, not the main menu")
        void theResponseIdentifiesItselfAsTheAdminMenu() {
            assertThat(service.getMenuScreen().menu().getMenuType())
                    .as("the menu type distinguishes COADM02Y's four-entry table from COMEN02Y's ten-entry "
                            + "one, and the populated counts differ as well as the content")
                    .isEqualTo(MenuResponse.MenuType.ADMIN);
        }

        @Test
        @DisplayName("the table carries no user-type byte, so no option can be withheld")
        void theTableCarriesNoUserTypeByte() {
            assertThat(MenuResponse.AdminMenuOption.class.getRecordComponents())
                    .as("app/cpy/COADM02Y.cpy declares only a number, a name and a program per entry; "
                            + "reaching transaction CA00 is itself the authorisation, so there is nothing "
                            + "for a per-option gate to test and the record carries no such component")
                    .hasSize(3)
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("optionNumber", "optionName", "programName");
        }

        @Test
        @DisplayName("the menu the screen carries has no message of its own")
        void theMenuScreenCarriesNoMessage() {
            assertThat(service.getMenuScreen().message())
                    .as("SEND-MENU-SCREEN is entered with the message area cleared on a first display, so "
                            + "the Java analogue is the empty string rather than null")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Label rendering: padded to the forty-column screen slot, exactly as the main menu is")
    final class LabelRendering {

        @ParameterizedTest(name = "label {0} is [{1}]")
        @CsvSource(value = {
            "0|01. User List (Security)",
            "1|02. User Add (Security)",
            "2|03. User Update (Security)",
            "3|04. User Delete (Security)",
        }, delimiter = '|')
        @DisplayName("each label is the zero-padded number, the separator and the caption at its full width")
        void eachLabelIsItsThreeParts(final int index, final String expected) {
            assertThat(service.getMenuScreen().optionLabels().get(index))
                    .as("the renderer formats the PIC 9(02) number with a leading zero, appends '. ', "
                            + "appends the whole PIC X(35) caption and pads the line to PIC X(40)")
                    .isEqualTo(expected + " ".repeat(
                            MenuResponse.SCREEN_OPTION_SLOT_LENGTH - expected.length()));
        }

        @Test
        @DisplayName("every label is exactly forty characters, so the lines are not ragged")
        void everyLabelIsExactlyFortyCharacters() {
            // app/cbl/COADM01C.cbl:L231-L236 and app/cbl/COMEN01C.cbl:L241-L246 are byte-identical STRING
            // statements into byte-identical PIC X(40) receiving fields, so the two renderers must produce
            // the same geometry. This service previously applied stripTrailing while the main menu padded,
            // which made lines of 24, 23, 26 and 26 characters out of a fixed-width screen field.
            assertThat(service.getMenuScreen().optionLabels())
                    .extracting(String::length)
                    .containsOnly(MenuResponse.SCREEN_OPTION_SLOT_LENGTH);
        }

        @Test
        @DisplayName("every label carries the trailing blank the MOVE SPACES left, rather than losing it")
        void everyLabelCarriesItsResidualBlank() {
            // Thirty-nine bytes are written - two digits, two separator characters, thirty-five caption
            // bytes - and the fortieth is the space :L231 left. Stripping it discarded a byte the screen
            // slot genuinely carried.
            assertThat(service.getMenuScreen().optionLabels())
                    .allSatisfy(label -> assertThat(label).endsWith(" "));
        }

        @Test
        @DisplayName("the caption occupies its whole PIC X(35) width, wherever it ends")
        void theCaptionOccupiesItsDeclaredWidth() {
            // The caption starts after the two digits and the two-character separator, and DELIMITED BY SIZE
            // transfers all thirty-five of its bytes whatever the caption's own text length is.
            final int captionStart = 2 + 2;
            assertThat(service.getMenuScreen().optionLabels())
                    .allSatisfy(label -> assertThat(label.length() - captionStart)
                            .isGreaterThanOrEqualTo(MenuResponse.OPTION_NAME_LENGTH));
        }

        @Test
        @DisplayName("the rendered label count equals the offered option count")
        void theRenderedLabelCountEqualsTheOptionCount() {
            final AdminMenuService.AdminMenuView view = service.getMenuScreen();

            assertThat(view.optionLabels())
                    .as("one line per option, exactly as the source's per-subscript MOVE produced")
                    .hasSize(view.menu().getOptionCount());
        }

        @Test
        @DisplayName("the label list the view exposes cannot be modified by its caller")
        void theLabelListIsUnmodifiable() {
            final List<String> labels = service.getMenuScreen().optionLabels();

            assertThatCode(() -> labels.add("05. Injected"))
                    .as("the renderer returns an immutable copy, so a consumer cannot alter what the "
                            + "service decided to render even though the record itself performs no copy")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the option list the menu exposes cannot be modified by its caller")
        void theOptionListIsUnmodifiable() {
            final List<MenuResponse.AdminMenuOption> options = service.getMenuScreen().menu().getOptions();

            assertThatCode(() -> options.add(option(9, "Injected", "COUSR09C")))
                    .as("the menu response holds an immutable list, which is what allows the canonical "
                            + "table to be a shared static constant safely")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Option field normalisation: right-justify then substitute zeros")
    final class OptionFieldNormalisation {

        @ParameterizedTest(name = "[{0}] resolves to option {1}")
        @CsvSource(value = {
            "1|1",
            "01|1",
            " 1|1",
            "1 |1",
            "4|4",
            "04|4",
            " 4|4",
            "4 |4",
        }, delimiter = '|')
        @DisplayName("a usable option field resolves to its number however it is justified or padded")
        void aUsableOptionFieldResolvesToItsNumber(final String typed, final int expected) {
            assertThat(service.selectOption(typed).optionNumber())
                    .as("the source right-pads the field to two bytes, scans backward over trailing "
                            + "blanks, right-justifies the surviving prefix and then substitutes '0' for "
                            + "every blank, so all four spellings of a digit normalise identically")
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{0}] is refused")
        @ValueSource(strings = {"", " ", "  ", "0", "00", "0 ", " 0", "5", "05", "9", "99", "1X", "X",
            "-1", "1.", "abc", "111", "  1", "+1"})
        @DisplayName("an unusable option field is refused with the redisplayed notice")
        void anUnusableOptionFieldIsRefused(final String typed) {
            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class, () -> service.selectOption(typed));

            assertThat(failure)
                    .as("the source refuses a field that is non-numeric after substitution, zero, or "
                            + "beyond CDEMO-ADMIN-OPT-COUNT; each of these is one of those three cases")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .as("one notice serves all three refusal causes, and the undifferentiated notice is "
                            + "itself the behaviour rather than an omission")
                    .isEqualTo(AdminMenuService.INVALID_OPTION_MESSAGE);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent option field is refused, having normalised to two blanks then two zeros")
        void anAbsentOptionFieldIsRefused(final String typed) {
            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class, () -> service.selectOption(typed));

            assertThat(failure.getMessage())
                    .as("this service returns two blanks for an absent field where MainMenuService returns "
                            + "the empty string; both then normalise to '00' and are refused, so the "
                            + "internal difference is invisible from outside and the outcome is identical")
                    .isEqualTo(AdminMenuService.INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("an over-length field is refused with the same notice as an unusable one")
        void anOverLengthFieldIsRefusedWithTheSameNotice() {
            assertThat(catchThrowableOfType(ValidationException.class, () -> service.selectOption("001"))
                    .getMessage())
                    .as("OPTIONI is PIC X(02), so three characters could not have reached the program "
                            + "under BMS; this service refuses them while receiving the screen rather than "
                            + "while processing it, but the notice and the outcome are the same, so the "
                            + "difference from MainMenuService is not observable")
                    .isEqualTo(AdminMenuService.INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("an over-length field that would otherwise be in range is still refused")
        void anOverLengthFieldThatWouldBeInRangeIsStillRefused() {
            assertThat(catchThrowableOfType(ValidationException.class, () -> service.selectOption(" 1 ")))
                    .as("' 1 ' would normalise to a usable option were the field three bytes wide, so "
                            + "refusing it proves the width test runs before normalisation")
                    .isNotNull();
        }

        @Test
        @DisplayName("every refusal names the option field and classifies the failure as invalid")
        void everyRefusalNamesTheOptionField() {
            final ValidationException failure =
                    catchThrowableOfType(ValidationException.class, () -> service.selectOption("5"));

            assertThat(failure.getFieldName())
                    .as("the field name drives the per-field error marker of app/cpy/CSSETATY.cpy, so a "
                            + "refusal naming no field could not turn the screen field red")
                    .isEqualTo("option");
            assertThat(failure.getFailureKind())
                    .as("the field was supplied and is unusable rather than blank, so the kind is INVALID; "
                            + "BLANK would additionally place the asterisk marker")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
        }

        @Test
        @DisplayName("the boundary between the last usable and first unusable number is exact")
        void theBoundaryBetweenUsableAndUnusableIsExact() {
            assertThat(service.selectOption("4").optionNumber())
                    .as("four is the populated count and is inclusive, because the source tests strictly "
                            + "greater than")
                    .isEqualTo(4);
            assertThat(catchThrowableOfType(ValidationException.class, () -> service.selectOption("5")))
                    .as("five lies inside the OCCURS 9 storage capacity but beyond the populated count, "
                            + "and it is the populated count that bounds the menu")
                    .isNotNull();
        }

        @Test
        @DisplayName("the substitution maps a blank to zero rather than discarding it")
        void theSubstitutionMapsABlankToZero() {
            assertThat(catchThrowableOfType(ValidationException.class, () -> service.selectOption(" ")))
                    .as("a trim-then-parse implementation would see an empty string; the source substitutes "
                            + "zeros and refuses on the zero test, so a single blank behaves exactly like "
                            + "a typed '0'")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("Selection resolution: the program a usable option transfers to")
    final class SelectionResolution {

        @ParameterizedTest(name = "option {0} transfers to {1}")
        @CsvSource({"1, COUSR00C", "2, COUSR01C", "3, COUSR02C", "4, COUSR03C"})
        @DisplayName("each usable option resolves to the program its copybook entry names")
        void eachUsableOptionResolvesToItsProgram(final String typed, final String program) {
            assertThat(service.selectOption(typed).targetProgram())
                    .as("this is the EXEC CICS XCTL PROGRAM target the source transfers to, which the "
                            + "stateless target returns instead")
                    .isEqualTo(program);
        }

        @Test
        @DisplayName("a resolved selection carries the unstripped PIC X(35) caption")
        void aResolvedSelectionCarriesTheUnstrippedCaption() {
            assertThat(service.selectOption("1").optionName())
                    .as("the selection carries the caption as the table holds it, space-padded to the "
                            + "declared width; only the label renderer strips it, and the two paths "
                            + "deliberately disagree")
                    .hasSize(CAPTION_WIDTH)
                    .startsWith("User List (Security)");
        }

        @Test
        @DisplayName("a real target is not coming soon and carries no notice")
        void aRealTargetIsNotComingSoonAndCarriesNoNotice() {
            final AdminMenuService.AdminMenuSelection selection = service.selectOption("1");

            assertThat(selection.comingSoon())
                    .as("COUSR00C exists, so the (1:5) DUMMY test fails and control transfers")
                    .isFalse();
            assertThat(selection.message())
                    .as("the notice was unreachable once XCTL had transferred control, so a real target "
                            + "carries the empty string")
                    .isEmpty();
        }

        @Test
        @DisplayName("the selection record renders its five components with the message before the flag")
        void theSelectionRecordRendersItsComponentsInItsOwnOrder() {
            assertThat(service.selectOption("1").toString())
                    .as("AdminMenuSelection orders its components as number, name, program, message, flag, "
                            + "whereas MainMenuService.MenuSelection places the flag before the message; "
                            + "the two records are not interchangeable and the rendering shows it")
                    .contains("optionNumber=1")
                    .contains("targetProgram=COUSR00C")
                    .containsPattern("message=.*comingSoon=false");
        }

        @Test
        @DisplayName("two selections of the same option are equal, and of different options are not")
        void selectionsCompareByValue() {
            final AdminMenuService.AdminMenuSelection first = service.selectOption("1");
            final AdminMenuService.AdminMenuSelection again = service.selectOption("01");
            final AdminMenuService.AdminMenuSelection other = service.selectOption("2");

            assertThat(first)
                    .as("'1' and '01' normalise to the same option, so the selections must be equal even "
                            + "though the operator typed different characters")
                    .isEqualTo(again)
                    .hasSameHashCodeAs(again)
                    .isNotEqualTo(other);
        }
    }

    @Nested
    @DisplayName("The DUMMY placeholder guard, reachable only through the injected table")
    final class PlaceholderProgramGuard {

        /** No canonical entry names a DUMMY program, so a placeholder entry is injected here. */
        private final AdminMenuService withPlaceholder = serviceOver(List.of(
                option(1, "User List (Security)", "COUSR00C"),
                option(2, "Future Admin Feature", "DUMMY003")));

        @Test
        @DisplayName("a placeholder target is flagged as coming soon")
        void aPlaceholderTargetIsFlagged() {
            assertThat(withPlaceholder.selectOption("2").comingSoon())
                    .as("the source tests the first five characters of the program name against 'DUMMY' "
                            + "and skips the XCTL when they match")
                    .isTrue();
        }

        @Test
        @DisplayName("a placeholder selection blanks its target program rather than returning DUMMY")
        void aPlaceholderSelectionBlanksItsTargetProgram() {
            assertThat(withPlaceholder.selectOption("2").targetProgram())
                    .as("MainMenuService returns the DUMMY name it read and relies on its flag; this "
                            + "service substitutes the empty string, so the flag carries the whole "
                            + "meaning - the two services genuinely diverge and both are faithful")
                    .isEmpty();
        }

        @Test
        @DisplayName("the coming-soon notice carries no option name, unlike the main menu's")
        void theComingSoonNoticeCarriesNoOptionName() {
            assertThat(withPlaceholder.selectOption("2").message())
                    .as("the caption operand at app/cbl/COADM01C.cbl:L150-L151 is commented out in column "
                            + "7, so no part of 'Future Admin Feature' can reach the notice; the main "
                            + "menu, whose operand is live, would have spliced 'Future' into it")
                    .isEqualTo(AdminMenuService.COMING_SOON_MESSAGE)
                    .doesNotContain("Future")
                    .doesNotContain("Admin Feature");
        }

        @Test
        @DisplayName("the notice is identical whatever the placeholder option is called")
        void theNoticeIsIdenticalWhateverTheOptionIsCalled() {
            final AdminMenuService differentCaption = serviceOver(List.of(
                    option(1, "An Entirely Different Caption", "DUMMY007")));

            assertThat(differentCaption.selectOption("1").message())
                    .as("because the notice is a fixed literal rather than an assembled string, two "
                            + "placeholders with different captions must produce byte-identical text; "
                            + "that is the observable consequence of the commented-out operand")
                    .isEqualTo(withPlaceholder.selectOption("2").message());
        }

        @Test
        @DisplayName("a real target beside a placeholder is unaffected by the guard")
        void aRealTargetBesideAPlaceholderIsUnaffected() {
            final AdminMenuService.AdminMenuSelection real = withPlaceholder.selectOption("1");

            assertThat(real.comingSoon())
                    .as("the guard is per-entry, so one placeholder does not make its neighbours "
                            + "placeholders")
                    .isFalse();
            assertThat(real.targetProgram())
                    .as("a real neighbour keeps its program name; only the placeholder's is blanked")
                    .isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("a program name that merely contains DUMMY is not a placeholder")
        void aProgramNameThatMerelyContainsDummyIsNotAPlaceholder() {
            final AdminMenuService notPrefixed = serviceOver(List.of(option(1, "Odd", "XDUMMY01")));
            final AdminMenuService.AdminMenuSelection selection = notPrefixed.selectOption("1");

            assertThat(selection.comingSoon())
                    .as("the source uses the reference modification (1:5), a prefix test rather than a "
                            + "containment test, so DUMMY elsewhere in the name leaves a real target")
                    .isFalse();
            assertThat(selection.targetProgram())
                    .as("a real target keeps its name, which is how the prefix test is observable")
                    .isEqualTo("XDUMMY01");
        }

        @Test
        @DisplayName("a placeholder still renders a label, because rendering ignores the guard")
        void aPlaceholderStillRendersALabel() {
            assertThat(withPlaceholder.getMenuScreen().optionLabels())
                    .as("the DUMMY guard runs when an option is chosen, not when the menu is drawn, so a "
                            + "placeholder is offered on screen exactly like a real option")
                    .containsExactly(
                            "01. User List (Security)"
                                    + " ".repeat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH - 24),
                            "02. Future Admin Feature"
                                    + " ".repeat(MenuResponse.SCREEN_OPTION_SLOT_LENGTH - 24));
        }
    }

    @Nested
    @DisplayName("The PF3 sign-on fall-back")
    final class SignOnFallBack {

        @ParameterizedTest(name = "requested program [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank requested program falls back to the sign-on program")
        void aBlankRequestedProgramFallsBackToSignOn(final String requested) {
            assertThat(service.signOnProgram(requested))
                    .as("RETURN-TO-SIGN-ON-SCREEN moves the sign-on literal when CDEMO-TO-PROGRAM is "
                            + "blank, which is the state a PF3 from a first display leaves it in")
                    .isEqualTo(AdminMenuService.SIGN_ON_PROGRAM);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent requested program falls back to the sign-on program")
        void anAbsentRequestedProgramFallsBackToSignOn(final String requested) {
            assertThat(service.signOnProgram(requested))
                    .as("the COMMAREA field was space-filled rather than absent, so null and blank must "
                            + "collapse to the same fall-back in the stateless target")
                    .isEqualTo(AdminMenuService.SIGN_ON_PROGRAM);
        }

        @ParameterizedTest(name = "requested program [{0}]")
        @ValueSource(strings = {"COMEN01C", "COUSR00C", "COSGN00C", "X"})
        @DisplayName("a named requested program is returned unchanged")
        void aNamedRequestedProgramIsReturnedUnchanged(final String requested) {
            assertThat(service.signOnProgram(requested))
                    .as("the fall-back applies only when nothing was requested; a populated field is "
                            + "honoured as written, with no validation against the program inventory - "
                            + "the source performs none either")
                    .isEqualTo(requested);
        }

        @Test
        @DisplayName("the fall-back does not strip a requested program that carries padding")
        void theFallBackDoesNotStripAPaddedRequestedProgram() {
            assertThat(service.signOnProgram("COMEN01C  "))
                    .as("CDEMO-TO-PROGRAM is a fixed-width field, so trailing blanks are expected; the "
                            + "source moves the field whole and this service returns it whole")
                    .isEqualTo("COMEN01C  ");
        }
    }

    @Nested
    @DisplayName("The injected clock and option table: the guards on the test seam itself")
    final class InjectedSeamGuards {

        @Test
        @DisplayName("an absent clock is refused")
        void anAbsentClockIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(null, MenuResponse.ADMIN_MENU_OPTIONS)).getMessage())
                    .as("the clock feeds the screen header; a null clock is a wiring defect that would "
                            + "otherwise surface as a NullPointerException on first render")
                    .contains("clock must not be null");
        }

        @Test
        @DisplayName("an absent option table is refused")
        void anAbsentOptionTableIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(Clock.systemUTC(), null)).getMessage())
                    .as("a null table is a wiring defect distinct from an empty one, and the two carry "
                            + "different messages")
                    .contains("adminMenuOptions must not be null");
        }

        @Test
        @DisplayName("an empty option table is refused, unlike the main menu's")
        void anEmptyOptionTableIsRefused() {
            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(List.<MenuResponse.AdminMenuOption>of())).getMessage())
                    .as("MainMenuService accepts an empty table and this service rejects one; the two "
                            + "constructors genuinely differ, and pinning the difference stops a later "
                            + "edit from quietly harmonising them")
                    .contains("adminMenuOptions must not be empty")
                    .contains("COADM02Y");
        }

        @Test
        @DisplayName("an option table containing a null element is refused, naming the index")
        void anOptionTableContainingANullElementIsRefused() {
            final List<MenuResponse.AdminMenuOption> withNull =
                    Arrays.asList(option(1, "User List (Security)", "COUSR00C"), null);

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(withNull)).getMessage())
                    .as("naming the offending subscript makes the defect locatable rather than merely "
                            + "reported")
                    .contains("must not contain a null element")
                    .contains("index 1");
        }

        @Test
        @DisplayName("an option table beyond the populated count is refused, and OCCURS is not the bound")
        void anOptionTableBeyondThePopulatedCountIsRefused() {
            final List<MenuResponse.AdminMenuOption> five = new ArrayList<>();
            for (int number = 1; number <= 5; number++) {
                five.add(option(number, "Caption " + number, "PGM0000" + number));
            }

            assertThat(catchThrowableOfType(IllegalArgumentException.class,
                    () -> serviceOver(five)).getMessage())
                    .as("five entries fit the REDEFINES OCCURS 9 storage but exceed the four the copybook "
                            + "populates; the spare subscripts hold no caption or program, so admitting "
                            + "them would offer options that resolve to nothing")
                    .contains("holds 5 entries")
                    .contains("exceeds the 4")
                    .contains("OCCURS 9 capacity");
        }

        @Test
        @DisplayName("a table of exactly the populated count is accepted")
        void aTableOfExactlyThePopulatedCountIsAccepted() {
            final List<MenuResponse.AdminMenuOption> four = new ArrayList<>();
            for (int number = 1; number <= 4; number++) {
                four.add(option(number, "Caption " + number, "PGM0000" + number));
            }

            assertThatCode(() -> serviceOver(four))
                    .as("the bound is inclusive, because four is the count the copybook populates")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a single-entry table offers one option and refuses the second")
        void aSingleEntryTableOffersOneOption() {
            final AdminMenuService single = serviceOver(List.of(option(1, "Only", "COUSR00C")));

            assertThat(single.getMenuScreen().menu().getOptionCount())
                    .as("the bounds test reads the table size rather than the copybook constant, so a "
                            + "substitute table narrows the accepted range with it")
                    .isEqualTo(1);
            assertThat(catchThrowableOfType(ValidationException.class, () -> single.selectOption("2")))
                    .as("option 2 lies beyond a one-entry table and is refused, which proves the bound is "
                            + "the injected size and not the copybook's four")
                    .isNotNull();
        }

        @Test
        @DisplayName("the injected table is copied defensively")
        void theInjectedTableIsCopiedDefensively() {
            final List<MenuResponse.AdminMenuOption> mutable =
                    new ArrayList<>(List.of(option(1, "User List (Security)", "COUSR00C")));
            final AdminMenuService injected = serviceOver(mutable);
            mutable.add(option(2, "Late Arrival", "COUSR01C"));

            assertThat(injected.getMenuScreen().menu().getOptionCount())
                    .as("the constructor copies the list, so a caller mutating its own list afterwards "
                            + "cannot change what the service offers")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the injected clock changes nothing the public surface exposes")
        void theInjectedClockChangesNothingObservable() {
            final AdminMenuService atMillennium = serviceOver(
                    Clock.fixed(Instant.parse("1999-12-31T23:59:59Z"), ZoneOffset.UTC),
                    MenuResponse.ADMIN_MENU_OPTIONS);
            final AdminMenuService atEpochRollover = serviceOver(
                    Clock.fixed(Instant.parse("2038-01-19T03:14:07Z"), ZoneOffset.UTC),
                    MenuResponse.ADMIN_MENU_OPTIONS);

            assertThat(atMillennium.getMenuScreen())
                    .as("populateHeaderInfo composes the CURDATE and CURTIME header the legacy screen "
                            + "displayed, but the view does not carry it - it reaches only a debug log "
                            + "statement. Two clocks thirty-eight years apart must therefore produce "
                            + "equal views, and asserting that invariance is more honest than pretending "
                            + "the header is observable through this API")
                    .isEqualTo(atEpochRollover.getMenuScreen());
        }

        @Test
        @DisplayName("a fixed clock leaves selection behaviour untouched")
        void aFixedClockLeavesSelectionBehaviourUntouched() {
            final AdminMenuService fixed = serviceOver(
                    Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneOffset.UTC),
                    MenuResponse.ADMIN_MENU_OPTIONS);

            assertThat(fixed.selectOption("3"))
                    .as("PROCESS-ENTER-KEY reads no date or time, so a selection must be clock-independent")
                    .isEqualTo(service.selectOption("3"));
        }
    }

    @Nested
    @DisplayName("Record contracts: plain records carrying no guards, unlike the main menu's")
    final class RecordContracts {

        @Test
        @DisplayName("the view accepts all-null components, because it declares no compact constructor")
        void theViewAcceptsAllNullComponents() {
            assertThatCode(() -> new AdminMenuService.AdminMenuView(null, null, null))
                    .as("MainMenuService.MainMenuScreen validates five conditions in a compact "
                            + "constructor; this record has an empty body and validates none. That is "
                            + "recorded here as observed behaviour so that adding or removing a guard "
                            + "shows up as a test change rather than as silence")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the selection accepts an out-of-range number and null components")
        void theSelectionAcceptsOutOfRangeAndNullComponents() {
            assertThatCode(() -> new AdminMenuService.AdminMenuSelection(0, null, null, null, false))
                    .as("MainMenuService.MenuSelection refuses option number zero, a null caption, a blank "
                            + "program and a null notice; this record refuses none of them, which is why "
                            + "the service itself is the only place those invariants are enforced")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the selection accepts a real target that carries a notice")
        void theSelectionAcceptsARealTargetCarryingANotice() {
            assertThatCode(() -> new AdminMenuService.AdminMenuSelection(1, "n", "COUSR00C", "notice", false))
                    .as("MainMenuService.MenuSelection enforces the XCTL fall-through invariant - a real "
                            + "target carries no notice and a placeholder carries one - and this record "
                            + "does not, so the combination the main menu forbids is constructible here")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the view performs no defensive copy of its label list")
        void theViewPerformsNoDefensiveCopy() {
            final List<String> mutable = new ArrayList<>(List.of("01. User List (Security)"));
            final AdminMenuService.AdminMenuView view =
                    new AdminMenuService.AdminMenuView(MenuResponse.adminMenu(), mutable, "");
            mutable.add("02. Late Arrival");

            assertThat(view.optionLabels())
                    .as("the record stores the caller's list by reference. It is safe in practice only "
                            + "because buildMenuOptions always hands it an immutable copy, so the "
                            + "immutability the service's callers observe comes from the renderer rather "
                            + "than from the record")
                    .hasSize(2);
        }

        @Test
        @DisplayName("the view exposes its three components unchanged")
        void theViewExposesItsThreeComponents() {
            final MenuResponse<MenuResponse.AdminMenuOption> menu = MenuResponse.adminMenu();
            final AdminMenuService.AdminMenuView view =
                    new AdminMenuService.AdminMenuView(menu, List.of("a", "b", "c", "d"), "a message");

            assertThat(view.menu()).as("the menu component is carried through by reference").isSameAs(menu);
            assertThat(view.optionLabels()).as("the label component is carried through").hasSize(4);
            assertThat(view.message()).as("the message component is carried through").isEqualTo("a message");
        }

        @Test
        @DisplayName("two views over the same values are equal")
        void twoViewsOverTheSameValuesAreEqual() {
            final MenuResponse<MenuResponse.AdminMenuOption> menu = MenuResponse.adminMenu();
            final AdminMenuService.AdminMenuView first =
                    new AdminMenuService.AdminMenuView(menu, List.of("a"), "");
            final AdminMenuService.AdminMenuView second =
                    new AdminMenuService.AdminMenuView(menu, List.of("a"), "");

            assertThat(first)
                    .as("the view is a value object, so equality is by component")
                    .isEqualTo(second)
                    .hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("the selection exposes all five components in its declared order")
        void theSelectionExposesAllFiveComponents() {
            final AdminMenuService.AdminMenuSelection selection =
                    new AdminMenuService.AdminMenuSelection(3, "User Update (Security)", "COUSR02C",
                            "", false);

            assertThat(selection.optionNumber()).as("the option number component").isEqualTo(3);
            assertThat(selection.optionName())
                    .as("the caption component").isEqualTo("User Update (Security)");
            assertThat(selection.targetProgram()).as("the program component").isEqualTo("COUSR02C");
            assertThat(selection.message()).as("the notice component, declared fourth").isEmpty();
            assertThat(selection.comingSoon()).as("the flag component, declared fifth").isFalse();
        }

        @Test
        @DisplayName("the selection record declares its components in a different order from the main menu's")
        void theSelectionDeclaresADifferentComponentOrder() {
            assertThat(AdminMenuService.AdminMenuSelection.class.getRecordComponents())
                    .as("the notice precedes the flag here and follows it in "
                            + "MainMenuService.MenuSelection; the two records are not interchangeable, and "
                            + "a positional constructor call written against the wrong one would not "
                            + "compile - which is the safeguard this assertion documents")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("optionNumber", "optionName", "targetProgram", "message", "comingSoon");
        }
    }

    @Nested
    @DisplayName("Statelessness: the service holds no per-request state")
    final class Statelessness {

        @Test
        @DisplayName("repeated screen requests are equal, so no counter or cursor accumulates")
        void repeatedScreenRequestsAreEqual() {
            assertThat(service.getMenuScreen())
                    .as("the COMMAREA carried the pseudo-conversational enter-versus-re-enter flag; this "
                            + "target keeps none, so two identical requests must produce identical views")
                    .isEqualTo(service.getMenuScreen());
        }

        @Test
        @DisplayName("a refused selection leaves the next selection unaffected")
        void aRefusedSelectionLeavesTheNextUnaffected() {
            assertThat(catchThrowableOfType(ValidationException.class, () -> service.selectOption("9")))
                    .as("the first selection is refused")
                    .isNotNull();

            assertThat(service.selectOption("1").targetProgram())
                    .as("no error flag survives the refusal; the legacy ERR-FLG-ON lived in working "
                            + "storage that CICS reinitialised per task, and holding no flag at all is the "
                            + "Java analogue of that")
                    .isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("two independently constructed services agree on every canonical option")
        void twoIndependentlyConstructedServicesAgree() {
            assertThat(new AdminMenuService().getMenuScreen())
                    .as("the canonical table is a shared immutable constant and the default clock feeds "
                            + "nothing observable, so instances are interchangeable and the bean may "
                            + "safely be a singleton")
                    .isEqualTo(new AdminMenuService().getMenuScreen());
        }

        @Test
        @DisplayName("a selection is unaffected by the screen having been rendered first")
        void aSelectionIsUnaffectedByAPriorScreenRender() {
            final AdminMenuService.AdminMenuSelection withoutRender = service.selectOption("2");
            service.getMenuScreen();

            assertThat(service.selectOption("2"))
                    .as("SEND-MENU-SCREEN and PROCESS-ENTER-KEY shared working storage under CICS but "
                            + "exchanged nothing that outlived the task, so the Java pair must be "
                            + "order-independent")
                    .isEqualTo(withoutRender);
        }

        @Test
        @DisplayName("the sign-on fall-back is unaffected by any prior call")
        void theSignOnFallBackIsUnaffectedByPriorCalls() {
            service.selectOption("1");
            service.getMenuScreen();

            assertThat(service.signOnProgram(null))
                    .as("the fall-back reads only its argument, so no earlier selection or render can "
                            + "change what it resolves")
                    .isEqualTo(AdminMenuService.SIGN_ON_PROGRAM);
        }
    }
}
