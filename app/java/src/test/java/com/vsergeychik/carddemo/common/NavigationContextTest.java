package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link NavigationContext}, the Java carrier for {@code 01 CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy} - the CICS pseudo-conversational state that all seventeen online programs
 * copy.
 */
@DisplayName("NavigationContext - CARDDEMO-COMMAREA: 160 bytes, four conditions, and no session")
class NavigationContextTest {
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final int DECLARED_COMMAREA_LENGTH = 160;

    private static final int DECLARED_LAST_MAP_LENGTH = 7;

    private static final int DECLARED_LAST_MAPSET_LENGTH = 7;

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(ASCII);
    }

    private static NavigationContext populated() {
        FixedWidthCodec codec = codec();
        return new NavigationContext(codec.movePicX("CC00", NavigationContext.FROM_TRANID_LENGTH),
                codec.movePicX("COSGN00C", NavigationContext.FROM_PROGRAM_LENGTH),
                codec.movePicX("CM00", NavigationContext.TO_TRANID_LENGTH),
                codec.movePicX("COMEN01C", NavigationContext.TO_PROGRAM_LENGTH),
                codec.movePicX("ADMIN001", NavigationContext.USER_ID_LENGTH),
                NavigationContext.USER_TYPE_ADMIN,
                NavigationContext.PGM_CONTEXT_REENTER,
                123456789,
                codec.movePicX("FIRSTNAME", NavigationContext.CUST_FNAME_LENGTH),
                codec.movePicX("MIDDLENAME", NavigationContext.CUST_MNAME_LENGTH),
                codec.movePicX("LASTNAME", NavigationContext.CUST_LNAME_LENGTH),
                12345678901L,
                "Y",
                4111111111111111L,
                codec.movePicX("COMEN1A", DECLARED_LAST_MAP_LENGTH),
                codec.movePicX("COMEN01", DECLARED_LAST_MAPSET_LENGTH));
    }

    private static String spanOf(byte[] image, int offset, int length) {
        return new String(image, offset, length, ASCII);
    }

    @Nested
    @DisplayName("Declared geometry - 34 + 84 + 12 + 16 + 14 = 160")
    class DeclaredGeometry {
        @Test
        @DisplayName("the serialised image is exactly 160 bytes")
        void imageIsOneHundredAndSixtyBytes() {
            byte[] image = populated().toFixedWidth(codec());

            assertThat(image)
                    .as("01 CARDDEMO-COMMAREA of app/cpy/COCOM01Y.cpy is 160 bytes wide")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the five group widths are 34, 84, 12, 16 and 14, and they sum to 160")
        void groupWidthsSumToTheRecordWidth() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("CDEMO-GENERAL-INFO: X(04)+X(08)+X(04)+X(08)+X(08)+X(01)+9(01)")
                    .isEqualTo(4 + 8 + 4 + 8 + 8 + 1 + 1)
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH)
                    .as("CDEMO-CUSTOMER-INFO: 9(09)+X(25)+X(25)+X(25)")
                    .isEqualTo(9 + 25 + 25 + 25)
                    .isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH)
                    .as("CDEMO-ACCOUNT-INFO: 9(11)+X(01)")
                    .isEqualTo(11 + 1)
                    .isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH)
                    .as("CDEMO-CARD-INFO: 9(16)")
                    .isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-MORE-INFO: X(7)+X(7) - fourteen, not sixteen")
                    .isEqualTo(7 + 7)
                    .isEqualTo(14);

            assertThat(34 + 84 + 12 + 16 + 14)
                    .as("the five group widths must account for the whole record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("and the declared group constants must sum to the declared record width")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the five group offsets are 0, 34, 118, 130 and 146")
        void groupOffsetsFollowTheDeclaredWidths() {
            assertThat(NavigationContext.GENERAL_INFO_OFFSET).as("the record starts here").isZero();
            assertThat(NavigationContext.CUSTOMER_INFO_OFFSET).as("0 + 34").isEqualTo(34);
            assertThat(NavigationContext.ACCOUNT_INFO_OFFSET).as("34 + 84").isEqualTo(118);
            assertThat(NavigationContext.CARD_INFO_OFFSET).as("118 + 12").isEqualTo(130);
            assertThat(NavigationContext.MORE_INFO_OFFSET).as("130 + 16").isEqualTo(146);

            assertThat(NavigationContext.MORE_INFO_OFFSET + NavigationContext.MORE_INFO_LENGTH)
                    .as("146 + 14 must land exactly on the end of the record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the layout declares all sixteen fields contiguously from 0 with no gap")
        void layoutIsContiguousAndComplete() {
            List<FieldSpan> spans = NavigationContext.LAYOUT.storageSpans();

            assertThat(spans)
                    .as("COCOM01Y declares sixteen elementary items and no FILLER")
                    .hasSize(16);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(DECLARED_COMMAREA_LENGTH);

            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("%s must begin where its predecessor ended", span.name())
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            assertThat(expectedOffset)
                    .as("the spans must account for every byte of the record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("every field is named exactly as the copybook spells it, hyphens included")
        void fieldNamesAreTheCopybookNames() {
            assertThat(NavigationContext.LAYOUT.storageSpans())
                    .extracting(FieldSpan::name)
                    .containsExactly("CDEMO-FROM-TRANID",
                            "CDEMO-FROM-PROGRAM",
                            "CDEMO-TO-TRANID",
                            "CDEMO-TO-PROGRAM",
                            "CDEMO-USER-ID",
                            "CDEMO-USER-TYPE",
                            "CDEMO-PGM-CONTEXT",
                            "CDEMO-CUST-ID",
                            "CDEMO-CUST-FNAME",
                            "CDEMO-CUST-MNAME",
                            "CDEMO-CUST-LNAME",
                            "CDEMO-ACCT-ID",
                            "CDEMO-ACCT-STATUS",
                            "CDEMO-CARD-NUM",
                            "CDEMO-LAST-MAP",
                            "CDEMO-LAST-MAPSET");
        }

        @ParameterizedTest(name = "{0} is declared at offset {1} and width {2}")
        @CsvSource({
            "CDEMO-FROM-TRANID,    0,  4",
            "CDEMO-FROM-PROGRAM,   4,  8",
            "CDEMO-TO-TRANID,     12,  4",
            "CDEMO-TO-PROGRAM,    16,  8",
            "CDEMO-USER-ID,       24,  8",
            "CDEMO-USER-TYPE,     32,  1",
            "CDEMO-PGM-CONTEXT,   33,  1",
            "CDEMO-CUST-ID,       34,  9",
            "CDEMO-CUST-FNAME,    43, 25",
            "CDEMO-CUST-MNAME,    68, 25",
            "CDEMO-CUST-LNAME,    93, 25",
            "CDEMO-ACCT-ID,      118, 11",
            "CDEMO-ACCT-STATUS,  129,  1",
            "CDEMO-CARD-NUM,     130, 16",
            "CDEMO-LAST-MAP,     146,  7",
            "CDEMO-LAST-MAPSET,  153,  7"
        })
        @DisplayName("each field sits at its transcribed offset and width")
        void eachFieldSitsWhereTheCopybookPutsIt(String cobolName, int offset, int length) {
            FieldSpan span = NavigationContext.LAYOUT.span(cobolName);

            assertThat(span.offset()).as("%s offset", cobolName).isEqualTo(offset);
            assertThat(span.length()).as("%s width", cobolName).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP precedes CDEMO-LAST-MAPSET, as the copybook declares them")
        void mapPrecedesMapsetInDeclarationOrder() {
            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("CDEMO-LAST-MAP is declared first, at the start of CDEMO-MORE-INFO")
                    .isEqualTo(146)
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET)
                    .as("CDEMO-LAST-MAPSET follows it, at 146 + 7")
                    .isEqualTo(153);
        }
    }

    @Nested
    @DisplayName("The X(7) trap - map and mapset names are seven characters, not eight")
    class TheSevenCharacterTrap {
        @Test
        @DisplayName("CDEMO-LAST-MAP is 7 bytes")
        void lastMapIsSevenBytes() {
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is PIC X(7) at app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(DECLARED_LAST_MAP_LENGTH)
                    .isEqualTo(7);
            assertThat(NavigationContext.LAYOUT.span("CDEMO-LAST-MAP").length()).isEqualTo(7);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAPSET is 7 bytes")
        void lastMapsetIsSevenBytes() {
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET is PIC X(7) at app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(DECLARED_LAST_MAPSET_LENGTH)
                    .isEqualTo(7);
            assertThat(NavigationContext.LAYOUT.span("CDEMO-LAST-MAPSET").length()).isEqualTo(7);
        }

        @Test
        @DisplayName("the two map fields together occupy 14 bytes, so the record is 160 and not 162")
        void theTwoMapFieldsCostFourteenNotSixteen() {
            int asDeclared = DECLARED_LAST_MAP_LENGTH + DECLARED_LAST_MAPSET_LENGTH;
            int ifBothWereEight = 8 + 8;

            assertThat(asDeclared).as("7 + 7").isEqualTo(14);
            assertThat(ifBothWereEight).as("the wrong guess, 8 + 8").isEqualTo(16);
            assertThat(34 + 84 + 12 + 16 + asDeclared)
                    .as("with the declared X(7) widths the record is 160")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
            assertThat(34 + 84 + 12 + 16 + ifBothWereEight)
                    .as("with X(8) it would be 162 - the exact cost of the wrong guess")
                    .isEqualTo(162)
                    .isNotEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "{0} is eight characters, because it names a program")
        @CsvSource({
            "CDEMO-FROM-PROGRAM, 8",
            "CDEMO-TO-PROGRAM,   8",
            "CDEMO-USER-ID,      8"
        })
        @DisplayName("the neighbouring X(08) fields really are eight, which is what makes 7 a trap")
        void programNamesAreEightCharacters(String cobolName, int expectedLength) {
            assertThat(NavigationContext.LAYOUT.span(cobolName).length()).isEqualTo(expectedLength);
        }

        @ParameterizedTest(name = "{0} is four characters, a CICS transaction identifier")
        @CsvSource({
            "CDEMO-FROM-TRANID, 4",
            "CDEMO-TO-TRANID,   4"
        })
        @DisplayName("both transaction identifiers are four characters")
        void transactionIdentifiersAreFourCharacters(String cobolName, int expectedLength) {
            assertThat(NavigationContext.LAYOUT.span(cobolName).length()).isEqualTo(expectedLength);
        }

        @Test
        @DisplayName("a seven-character map name round-trips intact")
        void sevenCharacterNameSurvivesIntact() {
            NavigationContext context = NavigationContext.empty()
                    .withLastMap("COMEN1A")
                    .withLastMapset("COMEN01");

            byte[] image = context.toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(restored.lastMap()).isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).isEqualTo("COMEN01");
            assertThat(spanOf(image, 146, 7)).as("the raw span at 146").isEqualTo("COMEN1A");
            assertThat(spanOf(image, 153, 7)).as("the raw span at 153").isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("an eight-character map name is refused, which is what catches an X(8) model")
        void eightCharacterNameIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COACTUPX"))
                    .withMessageContaining("CDEMO-LAST-MAP")
                    .withMessageContaining("PIC X(7)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMapset("COACTUPX"))
                    .withMessageContaining("CDEMO-LAST-MAPSET")
                    .withMessageContaining("PIC X(7)");
        }

        @Test
        @DisplayName("shortening an eight-character name is explicit, and truncates on the right")
        void eightCharacterNameTruncatesOnTheRightWhenAskedTo() {
            String shortened = codec().movePicX("COACTUPX", NavigationContext.LAST_MAP_LENGTH);

            assertThat(shortened)
                    .as("PIC X discards the surplus from the right, keeping the leading characters")
                    .isEqualTo("COACTUP")
                    .hasSize(7);

            NavigationContext context = NavigationContext.empty().withLastMap(shortened);
            byte[] image = context.toFixedWidth(codec());

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, 146, 7)).isEqualTo("COACTUP");
        }
    }

    @Nested
    @DisplayName("Padding and filling - PIC X right-space-padded, PIC 9 left-zero-filled")
    class PaddingAndFilling {
        @Test
        @DisplayName("a three-character program name occupies 8 bytes with 5 trailing spaces")
        void shortCharacterValueIsRightSpacePadded() {
            NavigationContext context = NavigationContext.empty().withFromProgram("ABC");

            byte[] image = context.toFixedWidth(codec());

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, 4, 8))
                    .as("CDEMO-FROM-PROGRAM: three characters then five spaces, padded on the right")
                    .isEqualTo("ABC     ")
                    .hasSize(8);
        }

        @Test
        @DisplayName("a customer id of 42 occupies 9 bytes as 000000042")
        void shortNumericValueIsLeftZeroFilled() {
            NavigationContext context = NavigationContext.empty().withCustId(42);

            byte[] image = context.toFixedWidth(codec());

            assertThat(spanOf(image, 34, 9))
                    .as("CDEMO-CUST-ID PIC 9(09): zero-filled on the left")
                    .isEqualTo("000000042")
                    .hasSize(9);
        }

        @ParameterizedTest(name = "every PIC X span of an initialised area is all spaces: {0}")
        @CsvSource({
            "CDEMO-FROM-TRANID,    0,  4",
            "CDEMO-FROM-PROGRAM,   4,  8",
            "CDEMO-TO-TRANID,     12,  4",
            "CDEMO-TO-PROGRAM,    16,  8",
            "CDEMO-USER-ID,       24,  8",
            "CDEMO-USER-TYPE,     32,  1",
            "CDEMO-CUST-FNAME,    43, 25",
            "CDEMO-CUST-MNAME,    68, 25",
            "CDEMO-CUST-LNAME,    93, 25",
            "CDEMO-ACCT-STATUS,  129,  1",
            "CDEMO-LAST-MAP,     146,  7",
            "CDEMO-LAST-MAPSET,  153,  7"
        })
        @DisplayName("an initialised area holds spaces in every character span")
        void initialisedCharacterSpansAreSpaces(String cobolName, int offset, int length) {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(spanOf(image, offset, length))
                    .as("%s of an initialised CARDDEMO-COMMAREA", cobolName)
                    .isEqualTo(" ".repeat(length));
        }

        @ParameterizedTest(name = "every PIC 9 span of an initialised area is all zeros: {0}")
        @CsvSource({
            "CDEMO-PGM-CONTEXT,  33,  1",
            "CDEMO-CUST-ID,      34,  9",
            "CDEMO-ACCT-ID,     118, 11",
            "CDEMO-CARD-NUM,    130, 16"
        })
        @DisplayName("an initialised area holds zeros in every numeric span")
        void initialisedNumericSpansAreZeros(String cobolName, int offset, int length) {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(spanOf(image, offset, length))
                    .as("%s of an initialised CARDDEMO-COMMAREA", cobolName)
                    .isEqualTo("0".repeat(length));
        }

        @Test
        @DisplayName("a padded value comes back at full declared width, untrimmed")
        void paddingSurvivesTheRoundTripUntrimmed() {
            NavigationContext context = NavigationContext.empty().withUserId("USER1");

            NavigationContext restored =
                    NavigationContext.fromFixedWidth(codec(), context.toFixedWidth(codec()));

            assertThat(restored.userId())
                    .as("CDEMO-USER-ID comes back at its full declared width of 8")
                    .isEqualTo("USER1   ")
                    .hasSize(8);
            assertThat(restored.toFixedWidth(codec()))
                    .as("and re-rendering it reproduces the same 160 bytes")
                    .isEqualTo(context.toFixedWidth(codec()));
        }
    }

    @Nested
    @DisplayName("CDEMO-USRTYP-ADMIN and CDEMO-USRTYP-USER - both states, and neither")
    class UserTypeConditions {
        @Test
        @DisplayName("CDEMO-USRTYP-ADMIN is true for 'A'")
        void adminIsTrueForA() {
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();

            assertThat(admin.isAdmin()).as("CDEMO-USRTYP-ADMIN VALUE 'A'").isTrue();
            assertThat(admin.userType()).isEqualTo("A");
        }

        @ParameterizedTest(name = "CDEMO-USRTYP-ADMIN is false for a user type of [{0}]")
        @ValueSource(strings = {"U", " ", "X", "a", "0"})
        @DisplayName("CDEMO-USRTYP-ADMIN is false for a regular user, a space, and any other byte")
        void adminIsFalseForEverythingElse(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin())
                    .as("only an exact 'A' satisfies CDEMO-USRTYP-ADMIN")
                    .isFalse();
        }

        @Test
        @DisplayName("CDEMO-USRTYP-USER is true for 'U'")
        void userIsTrueForU() {
            NavigationContext user = NavigationContext.empty().withUserTypeUser();

            assertThat(user.isUser()).as("CDEMO-USRTYP-USER VALUE 'U'").isTrue();
            assertThat(user.userType()).isEqualTo("U");
        }

        @ParameterizedTest(name = "CDEMO-USRTYP-USER is false for a user type of [{0}]")
        @ValueSource(strings = {"A", " ", "X", "u", "1"})
        @DisplayName("CDEMO-USRTYP-USER is false for an administrator, a space, and any other byte")
        void userIsFalseForEverythingElse(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isUser())
                    .as("only an exact 'U' satisfies CDEMO-USRTYP-USER")
                    .isFalse();
        }

        @ParameterizedTest(name = "the two conditions are never both true for [{0}]")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "0"})
        @DisplayName("the two conditions are mutually exclusive for every tested value")
        void theTwoConditionsAreMutuallyExclusive(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin() && context.isUser())
                    .as("one byte cannot be both 'A' and 'U'")
                    .isFalse();
        }

        @ParameterizedTest(name = "neither condition holds for [{0}]")
        @ValueSource(strings = {" ", "X", "a", "u", "0"})
        @DisplayName("neither condition holds for an unset user type, and that is a reachable state")
        void neitherConditionHoldsForAnUnsetUserType(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin()).isFalse();
            assertThat(context.isUser()).isFalse();
        }

        @Test
        @DisplayName("an initialised area implies no role at all")
        void initialisedAreaImpliesNoRole() {
            NavigationContext initial = NavigationContext.empty();

            assertThat(initial.userType()).as("a single space, per PIC X(01)").isEqualTo(" ");
            assertThat(initial.isAdmin()).isFalse();
            assertThat(initial.isUser()).isFalse();
        }

        @Test
        @DisplayName("the role routing COSGN00C hard-codes is reproducible from the predicate alone")
        void roleRoutingFollowsFromThePredicate() {
            assertThat(NavigationContext.empty().withUserTypeAdmin().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("an administrator reaches the admin menu, COSGN00C:232")
                    .isEqualTo("COADM01C");
            assertThat(NavigationContext.empty().withUserTypeUser().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("a regular user reaches the main menu, COSGN00C:237")
                    .isEqualTo("COMEN01C");
            assertThat(NavigationContext.empty().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("and a blank user type takes the ELSE, exactly as the COBOL does")
                    .isEqualTo("COMEN01C");
        }
    }

    @Nested
    @DisplayName("CDEMO-PGM-ENTER and CDEMO-PGM-REENTER - both states, and neither")
    class ProgramContextConditions {
        @Test
        @DisplayName("CDEMO-PGM-ENTER is true when the context is 0")
        void enterIsTrueForZero() {
            NavigationContext enter = NavigationContext.empty().withPgmEnter();

            assertThat(enter.pgmContext()).isEqualTo(0);
            assertThat(enter.isEnter()).as("CDEMO-PGM-ENTER VALUE 0").isTrue();
        }

        @Test
        @DisplayName("CDEMO-PGM-ENTER is false when the context is 1")
        void enterIsFalseForOne() {
            assertThat(NavigationContext.empty().withPgmReenter().isEnter()).isFalse();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER is true when the context is 1")
        void reenterIsTrueForOne() {
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();

            assertThat(reenter.pgmContext()).isEqualTo(1);
            assertThat(reenter.isReenter()).as("CDEMO-PGM-REENTER VALUE 1").isTrue();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER is false when the context is 0")
        void reenterIsFalseForZero() {
            assertThat(NavigationContext.empty().withPgmEnter().isReenter()).isFalse();
        }

        @ParameterizedTest(name = "neither condition holds for a program context of {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("neither condition holds for 2 through 9, which PIC 9(01) can represent")
        void neitherConditionHoldsForOtherDigits(int pgmContext) {
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);

            assertThat(context.pgmContext()).isEqualTo(pgmContext);
            assertThat(context.isEnter()).isFalse();
            assertThat(context.isReenter()).isFalse();
        }

        @ParameterizedTest(name = "the two conditions are never both true for {0}")
        @ValueSource(ints = {0, 1, 2, 5, 9})
        @DisplayName("the two conditions are mutually exclusive for every representable digit")
        void theTwoConditionsAreMutuallyExclusive(int pgmContext) {
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);

            assertThat(context.isEnter() && context.isReenter())
                    .as("one digit cannot be both 0 and 1")
                    .isFalse();
        }

        @Test
        @DisplayName("an initialised area is already in ENTER state, as MOVE ZEROS leaves it")
        void initialisedAreaIsInEnterState() {
            NavigationContext initial = NavigationContext.empty();

            assertThat(initial.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(initial.isEnter()).isTrue();
            assertThat(initial.isReenter()).isFalse();
        }

        @Test
        @DisplayName("the highlight condition holds only on re-entry, never on first entry")
        void highlightAppliesOnlyOnReentry() {
            boolean fieldInError = true;

            assertThat(fieldInError && NavigationContext.empty().withPgmEnter().isReenter())
                    .as("first entry: nothing typed yet, so no DFHRED and no '*'")
                    .isFalse();
            assertThat(fieldInError && NavigationContext.empty().withPgmReenter().isReenter())
                    .as("re-entry: the field really is in error, so the highlight applies")
                    .isTrue();
        }

        @Test
        @DisplayName("the four condition constants are exactly the copybook's four values")
        void theFourConditionConstantsMatchTheCopybook() {
            assertThat(NavigationContext.USER_TYPE_ADMIN).as("L27 VALUE 'A'").isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).as("L28 VALUE 'U'").isEqualTo("U");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).as("L30 VALUE 0").isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).as("L31 VALUE 1").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Fixed-width round trip - per field, so a field-order swap cannot hide")
    class FixedWidthRoundTrip {
        @Test
        @DisplayName("every one of the sixteen fields survives a round trip individually")
        void everyFieldSurvivesIndividually() {
            NavigationContext original = populated();

            byte[] image = original.toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);

            assertThat(restored.fromTranid()).as("CDEMO-FROM-TRANID").isEqualTo("CC00");
            assertThat(restored.fromProgram()).as("CDEMO-FROM-PROGRAM").isEqualTo("COSGN00C");
            assertThat(restored.toTranid()).as("CDEMO-TO-TRANID").isEqualTo("CM00");
            assertThat(restored.toProgram()).as("CDEMO-TO-PROGRAM").isEqualTo("COMEN01C");
            assertThat(restored.userId()).as("CDEMO-USER-ID").isEqualTo("ADMIN001");
            assertThat(restored.userType()).as("CDEMO-USER-TYPE").isEqualTo("A");
            assertThat(restored.pgmContext()).as("CDEMO-PGM-CONTEXT").isEqualTo(1);
            assertThat(restored.custId()).as("CDEMO-CUST-ID").isEqualTo(123456789);
            assertThat(restored.custFname()).as("CDEMO-CUST-FNAME")
                    .isEqualTo(original.custFname()).startsWith("FIRSTNAME").hasSize(25);
            assertThat(restored.custMname()).as("CDEMO-CUST-MNAME")
                    .isEqualTo(original.custMname()).startsWith("MIDDLENAME").hasSize(25);
            assertThat(restored.custLname()).as("CDEMO-CUST-LNAME")
                    .isEqualTo(original.custLname()).startsWith("LASTNAME").hasSize(25);
            assertThat(restored.acctId()).as("CDEMO-ACCT-ID").isEqualTo(12345678901L);
            assertThat(restored.acctStatus()).as("CDEMO-ACCT-STATUS").isEqualTo("Y");
            assertThat(restored.cardNum()).as("CDEMO-CARD-NUM").isEqualTo(4111111111111111L);
            assertThat(restored.lastMap()).as("CDEMO-LAST-MAP").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("CDEMO-LAST-MAPSET").isEqualTo("COMEN01");

            assertThat(restored).isEqualTo(original);
            assertThat(restored.toFixedWidth(codec())).isEqualTo(image);
        }

        @Test
        @DisplayName("the two X(04) transaction identifiers do not swap")
        void theTwoTransactionIdentifiersDoNotSwap() {
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(),
                    populated().toFixedWidth(codec()));

            assertThat(restored.fromTranid()).isEqualTo("CC00");
            assertThat(restored.toTranid()).isEqualTo("CM00");
            assertThat(restored.fromTranid()).isNotEqualTo(restored.toTranid());
        }

        @Test
        @DisplayName("the two X(08) program names do not swap")
        void theTwoProgramNamesDoNotSwap() {
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(),
                    populated().toFixedWidth(codec()));

            assertThat(restored.fromProgram()).isEqualTo("COSGN00C");
            assertThat(restored.toProgram()).isEqualTo("COMEN01C");
            assertThat(restored.fromProgram()).isNotEqualTo(restored.toProgram());
        }

        @Test
        @DisplayName("the two X(7) map fields do not swap")
        void theTwoMapFieldsDoNotSwap() {
            byte[] image = populated().toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(restored.lastMap()).as("declared first, at offset 146").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("declared second, at offset 153").isEqualTo("COMEN01");
            assertThat(restored.lastMap()).isNotEqualTo(restored.lastMapset());
            assertThat(spanOf(image, 146, 7)).isEqualTo("COMEN1A");
            assertThat(spanOf(image, 153, 7)).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the three X(25) customer names do not rotate")
        void theThreeCustomerNamesDoNotRotate() {
            byte[] image = populated().toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(restored.custFname()).startsWith("FIRSTNAME");
            assertThat(restored.custMname()).startsWith("MIDDLENAME");
            assertThat(restored.custLname()).startsWith("LASTNAME");
            assertThat(spanOf(image, 43, 25)).startsWith("FIRSTNAME");
            assertThat(spanOf(image, 68, 25)).startsWith("MIDDLENAME");
            assertThat(spanOf(image, 93, 25)).startsWith("LASTNAME");
        }

        @Test
        @DisplayName("an initialised area serialises to 160 bytes of spaces and zeros")
        void initialisedAreaSerialisesToSpacesAndZeros() {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(image)
                    .as("never short, and never null")
                    .isNotNull()
                    .hasSize(DECLARED_COMMAREA_LENGTH);

            String expected = " ".repeat(4)
                    + " ".repeat(8)
                    + " ".repeat(4)
                    + " ".repeat(8)
                    + " ".repeat(8)
                    + " "
                    + "0"
                    + "0".repeat(9)
                    + " ".repeat(25)
                    + " ".repeat(25)
                    + " ".repeat(25)
                    + "0".repeat(11)
                    + " "
                    + "0".repeat(16)
                    + " ".repeat(7)
                    + " ".repeat(7);

            assertThat(expected).as("the transcribed expectation must itself be 160 characters")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(new String(image, ASCII)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an initialised area round-trips to an equal value, with no null field")
        void initialisedAreaRoundTrips() {
            NavigationContext initial = NavigationContext.empty();

            NavigationContext restored =
                    NavigationContext.fromFixedWidth(codec(), initial.toFixedWidth(codec()));

            assertThat(restored).isEqualTo(initial);
            assertThat(restored.fromTranid()).isNotNull().isBlank().hasSize(4);
            assertThat(restored.fromProgram()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.toTranid()).isNotNull().isBlank().hasSize(4);
            assertThat(restored.toProgram()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.userId()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.userType()).isNotNull().isBlank().hasSize(1);
            assertThat(restored.custFname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.custMname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.custLname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.acctStatus()).isNotNull().isBlank().hasSize(1);
            assertThat(restored.lastMap()).isNotNull().isBlank().hasSize(7);
            assertThat(restored.lastMapset()).isNotNull().isBlank().hasSize(7);
            assertThat(restored.pgmContext()).isZero();
            assertThat(restored.custId()).isZero();
            assertThat(restored.acctId()).isZero();
            assertThat(restored.cardNum()).isZero();
        }

        @Test
        @DisplayName("the layout's field names index the deserialised map, field for field")
        void deserialisedMapIsKeyedByCopybookName() {
            Map<String, String> fields =
                    codec().deserialise(NavigationContext.LAYOUT, populated().toFixedWidth(codec()));

            assertThat(fields).hasSize(16);
            assertThat(fields.get("CDEMO-FROM-TRANID")).isEqualTo("CC00");
            assertThat(fields.get("CDEMO-TO-TRANID")).isEqualTo("CM00");
            assertThat(fields.get("CDEMO-FROM-PROGRAM")).isEqualTo("COSGN00C");
            assertThat(fields.get("CDEMO-TO-PROGRAM")).isEqualTo("COMEN01C");
            assertThat(fields.get("CDEMO-USER-TYPE")).isEqualTo("A");
            assertThat(fields.get("CDEMO-PGM-CONTEXT")).isEqualTo("1");
            assertThat(fields.get("CDEMO-CUST-ID")).as("PIC 9(09), zero-filled").isEqualTo("123456789");
            assertThat(fields.get("CDEMO-ACCT-ID")).as("PIC 9(11)").isEqualTo("12345678901");
            assertThat(fields.get("CDEMO-CARD-NUM")).as("PIC 9(16)").isEqualTo("4111111111111111");
            assertThat(fields.get("CDEMO-LAST-MAP")).isEqualTo("COMEN1A");
            assertThat(fields.get("CDEMO-LAST-MAPSET")).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("an image of the wrong length is refused rather than read short")
        void wrongLengthImageIsRefused() {
            byte[] tooShort = new byte[DECLARED_COMMAREA_LENGTH - 1];
            byte[] tooLong = new byte[DECLARED_COMMAREA_LENGTH + 1];

            assertThatIllegalArgumentException()
                    .as("159 bytes is not a CARDDEMO-COMMAREA")
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), tooShort));
            assertThatIllegalArgumentException()
                    .as("161 bytes is not one either - and 162 is the X(8) mistake")
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), tooLong));
        }

        @Test
        @DisplayName("the codec and the image are both required, and neither is defaulted")
        void codecAndImageAreRequired() {
            byte[] image = populated().toFixedWidth(codec());

            assertThatNullPointerException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(null, image));
            assertThatNullPointerException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().toFixedWidth(null));
        }
    }

    @Nested
    @DisplayName("JSON wire form - all sixteen fields, padding untrimmed")
    class JsonWireForm {
        @Test
        @DisplayName("every one of the sixteen fields survives a JSON round trip individually")
        void everyFieldSurvivesAJsonRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext original = populated();

            String json = mapper.writeValueAsString(original);
            NavigationContext restored = mapper.readValue(json, NavigationContext.class);

            assertThat(restored.fromTranid()).as("CDEMO-FROM-TRANID").isEqualTo("CC00");
            assertThat(restored.fromProgram()).as("CDEMO-FROM-PROGRAM").isEqualTo("COSGN00C");
            assertThat(restored.toTranid()).as("CDEMO-TO-TRANID").isEqualTo("CM00");
            assertThat(restored.toProgram()).as("CDEMO-TO-PROGRAM").isEqualTo("COMEN01C");
            assertThat(restored.userId()).as("CDEMO-USER-ID").isEqualTo("ADMIN001");
            assertThat(restored.userType()).as("CDEMO-USER-TYPE").isEqualTo("A");
            assertThat(restored.pgmContext()).as("CDEMO-PGM-CONTEXT").isEqualTo(1);
            assertThat(restored.custId()).as("CDEMO-CUST-ID").isEqualTo(123456789);
            assertThat(restored.custFname()).as("CDEMO-CUST-FNAME").isEqualTo(original.custFname());
            assertThat(restored.custMname()).as("CDEMO-CUST-MNAME").isEqualTo(original.custMname());
            assertThat(restored.custLname()).as("CDEMO-CUST-LNAME").isEqualTo(original.custLname());
            assertThat(restored.acctId()).as("CDEMO-ACCT-ID").isEqualTo(12345678901L);
            assertThat(restored.acctStatus()).as("CDEMO-ACCT-STATUS").isEqualTo("Y");
            assertThat(restored.cardNum()).as("CDEMO-CARD-NUM").isEqualTo(4111111111111111L);
            assertThat(restored.lastMap()).as("CDEMO-LAST-MAP").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("CDEMO-LAST-MAPSET").isEqualTo("COMEN01");

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("space-padded values survive the round trip untrimmed")
        void paddedValuesAreNotTrimmed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext original = NavigationContext.empty()
                    .withUserId(codec().movePicX("USER1", NavigationContext.USER_ID_LENGTH))
                    .withFromProgram(codec().movePicX("ABC", NavigationContext.FROM_PROGRAM_LENGTH));

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(original), NavigationContext.class);

            assertThat(restored.userId()).isEqualTo("USER1   ").hasSize(8);
            assertThat(restored.fromProgram()).isEqualTo("ABC     ").hasSize(8);
            assertThat(restored.custFname()).as("an all-space field stays all spaces, not null")
                    .isEqualTo(" ".repeat(25));
            assertThat(restored.toFixedWidth(codec()))
                    .as("and the fixed-width image is unchanged by the JSON detour")
                    .isEqualTo(original.toFixedWidth(codec()));
        }

        @Test
        @DisplayName("the JSON payload carries the sixteen copybook fields and no derived condition")
        void payloadCarriesFieldsNotDerivedConditions() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            Map<?, ?> asMap = mapper.readValue(
                    mapper.writeValueAsString(populated()), Map.class);

            List<String> properties = new ArrayList<>();
            for (Object key : asMap.keySet()) {
                properties.add(String.valueOf(key));
            }

            assertThat(properties)
                    .as("the sixteen elementary items of COCOM01Y, and nothing else")
                    .containsExactlyInAnyOrder("fromTranid", "fromProgram", "toTranid", "toProgram",
                            "userId", "userType", "pgmContext", "custId", "custFname", "custMname",
                            "custLname", "acctId", "acctStatus", "cardNum", "lastMap", "lastMapset")
                    .hasSize(16);
            assertThat(properties)
                    .as("the derived conditions are not wire properties")
                    .doesNotContain("admin", "user", "enter", "reenter");
        }

        @Test
        @DisplayName("an initialised area round-trips through JSON unchanged")
        void initialisedAreaRoundTripsThroughJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext initial = NavigationContext.empty();

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(initial), NavigationContext.class);

            assertThat(restored).isEqualTo(initial);
            assertThat(restored.isEnter()).as("still ENTER after the detour").isTrue();
            assertThat(restored.isAdmin()).isFalse();
            assertThat(restored.isUser()).isFalse();
        }

        @Test
        @DisplayName("the four conditions are recomputed from the payload, not carried in it")
        void conditionsAreRecomputedFromThePayload() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin().withPgmReenter();

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(admin), NavigationContext.class);

            assertThat(restored.userType()).isEqualTo("A");
            assertThat(restored.isAdmin()).isTrue();
            assertThat(restored.isUser()).isFalse();
            assertThat(restored.isReenter()).isTrue();
            assertThat(restored.isEnter()).isFalse();
        }
    }

    @Nested
    @DisplayName("Statelessness - this type exists so that no server-side session does")
    class Statelessness {
        @Test
        @DisplayName("the class declares no non-final static field")
        void declaresNoNonFinalStaticField() {
            List<String> mutableStatics = new ArrayList<>();
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("a non-final static field in the COMMAREA carrier would be shared state")
                    .isEmpty();
        }

        @Test
        @DisplayName("every instance field is final, so an instance cannot change after construction")
        void everyInstanceFieldIsFinal() {
            List<String> mutableInstanceFields = new ArrayList<>();
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableInstanceFields.add(field.getName());
                }
            }

            assertThat(mutableInstanceFields).isEmpty();
            assertThat(NavigationContext.class.isRecord())
                    .as("an immutable record, not a mutable bean")
                    .isTrue();
        }

        @Test
        @DisplayName("no field references a servlet session, a ThreadLocal or any ambient store")
        void noFieldReferencesAnAmbientStore() {
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                String typeName = field.getType().getName();
                assertThat(typeName)
                        .as("field %s must not be an ambient state holder", field.getName())
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("HttpSession")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("RequestContext")
                        .doesNotContain("SessionScope");
            }
        }

        @Test
        @DisplayName("two independently built instances share no state")
        void twoInstancesShareNoState() {
            NavigationContext first = NavigationContext.empty().withUserId("USER1");
            NavigationContext second = NavigationContext.empty().withUserId("USER2");

            assertThat(first.userId()).as("stored raw, not padded").isEqualTo("USER1");
            assertThat(second.userId()).isEqualTo("USER2");
            assertThat(first).isNotEqualTo(second);

            assertThat(spanOf(first.toFixedWidth(codec()), 24, 8)).isEqualTo("USER1   ");
            assertThat(spanOf(second.toFixedWidth(codec()), 24, 8)).isEqualTo("USER2   ");
        }

        @Test
        @DisplayName("modifying a context leaves every other reference to the original untouched")
        void modifyingOneContextLeavesTheOriginalUntouched() {
            NavigationContext original = NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmEnter();
            NavigationContext handedToACollaborator = original;

            NavigationContext modified = original
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter();

            assertThat(handedToACollaborator)
                    .as("the reference a collaborator already holds is unchanged")
                    .isSameAs(original);
            assertThat(original.userId()).isEqualTo("ADMIN001");
            assertThat(original.isAdmin()).isTrue();
            assertThat(original.isEnter()).isTrue();

            assertThat(modified.userId()).isEqualTo("USER0001");
            assertThat(modified.isUser()).isTrue();
            assertThat(modified.isReenter()).isTrue();
            assertThat(modified).isNotSameAs(original).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the next program to run is readable from the payload with no server-side lookup")
        void nextProgramIsCarriedByThePayload() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext transferring = NavigationContext.empty()
                    .withFromTranid("CCLI")
                    .withFromProgram("COCRDLIC")
                    .withToTranid("CCUP")
                    .withToProgram("COCRDUPC")
                    .withLastMap("COCRDLA")
                    .withLastMapset("COCRDLI");

            NavigationContext overTheWire = mapper.readValue(
                    mapper.writeValueAsString(transferring), NavigationContext.class);
            NavigationContext overTheRecord = NavigationContext.fromFixedWidth(
                    codec(), transferring.toFixedWidth(codec()));

            for (NavigationContext carried : List.of(overTheWire, overTheRecord)) {
                assertThat(carried.toProgram())
                        .as("the XCTL target travels as data, not as a server-side forward")
                        .isEqualTo("COCRDUPC");
                assertThat(carried.toTranid()).isEqualTo("CCUP");
                assertThat(carried.lastMap()).as("the screen being left").isEqualTo("COCRDLA");
                assertThat(carried.lastMapset()).isEqualTo("COCRDLI");
                assertThat(carried.fromProgram()).as("and where it came from").isEqualTo("COCRDLIC");
                assertThat(carried.fromTranid()).isEqualTo("CCLI");
            }
        }

        @Test
        @DisplayName("the shared LAYOUT constant is immutable, so publishing it adds no shared state")
        void theSharedLayoutIsImmutable() {
            List<FieldSpan> spans = NavigationContext.LAYOUT.storageSpans();

            assertThat(spans).hasSize(16);
            assertThat(spans)
                    .as("no caller can alter the layout every parity comparison depends on")
                    .isUnmodifiable();
            assertThat(NavigationContext.LAYOUT.storageSpans())
                    .as("and a second read returns the same sixteen spans")
                    .hasSize(16)
                    .containsExactlyElementsOf(spans);
            assertThat(NavigationContext.LAYOUT.recordLength())
                    .as("the shared record width is a compile-time constant, not mutable state")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction guards - a value that cannot be stored is refused, not shortened")
    class GuardSweep {
        @ParameterizedTest(name = "a null {0} is refused")
        @ValueSource(strings = {"fromTranid", "fromProgram", "toTranid", "toProgram", "userId",
            "userType", "custFname", "custMname", "custLname", "acctStatus", "lastMap", "lastMapset"})
        @DisplayName("a null character field is refused: there is no null in a COBOL record")
        void nullCharacterFieldIsRefused(String component) {
            assertThatNullPointerException()
                    .as("component %s", component)
                    .isThrownBy(() -> withNullComponent(component));
        }

        private NavigationContext withNullComponent(String component) {
            return new NavigationContext("fromTranid".equals(component) ? null : "CC00",
                    "fromProgram".equals(component) ? null : "COSGN00C",
                    "toTranid".equals(component) ? null : "CM00",
                    "toProgram".equals(component) ? null : "COMEN01C",
                    "userId".equals(component) ? null : "ADMIN001",
                    "userType".equals(component) ? null : "A",
                    0,
                    0,
                    "custFname".equals(component) ? null : "F",
                    "custMname".equals(component) ? null : "M",
                    "custLname".equals(component) ? null : "L",
                    0L,
                    "acctStatus".equals(component) ? null : "Y",
                    0L,
                    "lastMap".equals(component) ? null : "COMEN1A",
                    "lastMapset".equals(component) ? null : "COMEN01");
        }

        @ParameterizedTest(name = "{0} refuses a value of {1} characters when it is declared {2}")
        @CsvSource({
            "CDEMO-FROM-TRANID,   5,  4",
            "CDEMO-FROM-PROGRAM,  9,  8",
            "CDEMO-TO-TRANID,     5,  4",
            "CDEMO-TO-PROGRAM,    9,  8",
            "CDEMO-USER-ID,       9,  8",
            "CDEMO-USER-TYPE,     2,  1",
            "CDEMO-CUST-FNAME,   26, 25",
            "CDEMO-CUST-MNAME,   26, 25",
            "CDEMO-CUST-LNAME,   26, 25",
            "CDEMO-ACCT-STATUS,   2,  1",
            "CDEMO-LAST-MAP,      8,  7",
            "CDEMO-LAST-MAPSET,   8,  7"
        })
        @DisplayName("an over-long character value is refused rather than quietly shortened")
        void overLongCharacterValueIsRefused(String cobolName, int suppliedLength, int declaredWidth) {
            String tooLong = "X".repeat(suppliedLength);

            assertThatIllegalArgumentException()
                    .as("%s is PIC X(%d)", cobolName, declaredWidth)
                    .isThrownBy(() -> withCharacterComponent(cobolName, tooLong))
                    .withMessageContaining(cobolName)
                    .withMessageContaining("PIC X(" + declaredWidth + ")")
                    .withMessageContaining(NavigationContext.REDACTED);
        }

        @ParameterizedTest(name = "{0} accepts a value shorter than its declared width")
        @CsvSource({
            "CDEMO-FROM-TRANID,   1",
            "CDEMO-FROM-PROGRAM,  3",
            "CDEMO-CUST-FNAME,    5",
            "CDEMO-LAST-MAP,      2",
            "CDEMO-LAST-MAPSET,   2"
        })
        @DisplayName("a shorter character value is accepted, and padded when the image is rendered")
        void shorterCharacterValueIsAccepted(String cobolName, int suppliedLength) {
            String shorter = "X".repeat(suppliedLength);

            NavigationContext context = withCharacterComponent(cobolName, shorter);

            assertThat(context.toFixedWidth(codec()))
                    .as("the image is still the full declared width")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
        }

        private NavigationContext withCharacterComponent(String cobolName, String value) {
            NavigationContext base = NavigationContext.empty();
            return switch (cobolName) {
                case "CDEMO-FROM-TRANID" -> base.withFromTranid(value);
                case "CDEMO-FROM-PROGRAM" -> base.withFromProgram(value);
                case "CDEMO-TO-TRANID" -> base.withToTranid(value);
                case "CDEMO-TO-PROGRAM" -> base.withToProgram(value);
                case "CDEMO-USER-ID" -> base.withUserId(value);
                case "CDEMO-USER-TYPE" -> base.withUserType(value);
                case "CDEMO-CUST-FNAME" -> base.withCustFname(value);
                case "CDEMO-CUST-MNAME" -> base.withCustMname(value);
                case "CDEMO-CUST-LNAME" -> base.withCustLname(value);
                case "CDEMO-ACCT-STATUS" -> base.withAcctStatus(value);
                case "CDEMO-LAST-MAP" -> base.withLastMap(value);
                case "CDEMO-LAST-MAPSET" -> base.withLastMapset(value);
                default -> throw new IllegalArgumentException(
                        "The test names a field the copybook does not declare: " + cobolName);
            };
        }

        @Test
        @DisplayName("a negative numeric value is refused: PIC 9 has no sign position")
        void negativeNumericValueIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withPgmContext(-1))
                    .withMessageContaining("CDEMO-PGM-CONTEXT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withCustId(-1))
                    .withMessageContaining("CDEMO-CUST-ID");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withAcctId(-1L))
                    .withMessageContaining("CDEMO-ACCT-ID");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withCardNum(-1L))
                    .withMessageContaining("CDEMO-CARD-NUM");
        }

        @Test
        @DisplayName("a numeric value needing more digits than declared is refused")
        void overWideNumericValueIsRefused() {
            assertThatIllegalArgumentException()
                    .as("CDEMO-PGM-CONTEXT is PIC 9(01), so 10 does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withPgmContext(10))
                    .withMessageContaining("CDEMO-PGM-CONTEXT")
                    .withMessageContaining("PIC 9(1)");
            assertThatIllegalArgumentException()
                    .as("CDEMO-CUST-ID is PIC 9(09), so a tenth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withCustId(1_000_000_000))
                    .withMessageContaining("CDEMO-CUST-ID");
            assertThatIllegalArgumentException()
                    .as("CDEMO-ACCT-ID is PIC 9(11), so a twelfth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withAcctId(100_000_000_000L))
                    .withMessageContaining("CDEMO-ACCT-ID");
            assertThatIllegalArgumentException()
                    .as("CDEMO-CARD-NUM is PIC 9(16), so a seventeenth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withCardNum(10_000_000_000_000_000L))
                    .withMessageContaining("CDEMO-CARD-NUM");
        }

        @ParameterizedTest(name = "the widest value each numeric field can hold is accepted: {0}")
        @CsvSource({
            "CDEMO-PGM-CONTEXT, 9",
            "CDEMO-CUST-ID,     999999999",
            "CDEMO-ACCT-ID,     99999999999",
            "CDEMO-CARD-NUM,    9999999999999999"
        })
        @DisplayName("the boundary value of each numeric field is accepted and renders at full width")
        void widestNumericValueIsAccepted(String cobolName, long widest) {
            NavigationContext context = switch (cobolName) {
                case "CDEMO-PGM-CONTEXT" -> NavigationContext.empty().withPgmContext((int) widest);
                case "CDEMO-CUST-ID" -> NavigationContext.empty().withCustId((int) widest);
                case "CDEMO-ACCT-ID" -> NavigationContext.empty().withAcctId(widest);
                case "CDEMO-CARD-NUM" -> NavigationContext.empty().withCardNum(widest);
                default -> throw new IllegalArgumentException(
                        "The test names a field the copybook does not declare: " + cobolName);
            };

            byte[] image = context.toFixedWidth(codec());
            FieldSpan span = NavigationContext.LAYOUT.span(cobolName);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, span.offset(), span.length()))
                    .as("%s at its widest", cobolName)
                    .isEqualTo(Long.toString(widest));
        }

        @Test
        @DisplayName("zero is accepted in every numeric field, being the initialised state")
        void zeroIsAcceptedInEveryNumericField() {
            NavigationContext context = NavigationContext.empty()
                    .withPgmContext(0)
                    .withCustId(0)
                    .withAcctId(0L)
                    .withCardNum(0L);

            assertThat(context.pgmContext()).isZero();
            assertThat(context.custId()).isZero();
            assertThat(context.acctId()).isZero();
            assertThat(context.cardNum()).isZero();
            assertThat(context.toFixedWidth(codec())).hasSize(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("an empty character value is accepted, being SPACES moved into the field")
        void emptyCharacterValueIsAccepted() {
            NavigationContext context = NavigationContext.empty()
                    .withFromProgram("")
                    .withLastMap("");

            byte[] image = context.toFixedWidth(codec());

            assertThat(spanOf(image, 4, 8)).isEqualTo(" ".repeat(8));
            assertThat(spanOf(image, 146, 7)).isEqualTo(" ".repeat(7));
        }

        @Test
        @DisplayName("a non-digit in a numeric span is refused when an image is read back")
        void nonDigitInANumericSpanIsRefused() {
            byte[] image = populated().toFixedWidth(codec());
            FieldSpan custId = NavigationContext.LAYOUT.span("CDEMO-CUST-ID");
            image[custId.offset()] = (byte) 'X';

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), image));
        }

        @Test
        @DisplayName("a space in a numeric span is refused too, not read as zero")
        void spaceInANumericSpanIsRefused() {
            byte[] image = populated().toFixedWidth(codec());
            FieldSpan cardNum = NavigationContext.LAYOUT.span("CDEMO-CARD-NUM");
            for (int index = 0; index < cardNum.length(); index++) {
                image[cardNum.offset() + index] = (byte) ' ';
            }

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), image));
        }

        @ParameterizedTest(name = "a card-number image containing [{0}] is refused")
        @ValueSource(strings = {"411111111111111X", "411111111111111:", "411111111111111 ",
            "411111111111111-", "411111111111111/", "41111111111111.1", "4111111111111+11"})
        @DisplayName("the digit-range guard refuses a character on either side of '0' through '9'")
        void cardNumberImageRefusesNonDigitsOnBothSidesOfTheRange(String image) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.cardNumberOfImage(image))
                    .withMessageContaining(NavigationContext.CARD_NUM_FIELD)
                    .withMessageContaining("only the digits 0 to 9")
                    .withMessageContaining(NavigationContext.REDACTED);
        }

        @Test
        @DisplayName("a well-formed sixteen-digit image is accepted, the guard's passing path")
        void wellFormedCardNumberImageIsAccepted() {
            assertThat(NavigationContext.cardNumberOfImage("4111111111111111"))
                    .isEqualTo(4111111111111111L);
            assertThat(NavigationContext.cardNumberOfImage("0000000000000000")).isZero();
            assertThat(NavigationContext.cardNumberOfImage("9999999999999999"))
                    .as("the boundary characters '0' and '9' are themselves valid")
                    .isEqualTo(9999999999999999L);
        }

        @Test
        @DisplayName("the layout refuses a field name it does not declare")
        void layoutRefusesAnUndeclaredFieldName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.LAYOUT.span("CDEMO-LAST-MAPSETS"));
            assertThat(NavigationContext.LAYOUT.hasSpan("CDEMO-LAST-MAPSET")).isTrue();
            assertThat(NavigationContext.LAYOUT.hasSpan("cdemo-last-mapset"))
                    .as("names are case-sensitive")
                    .isFalse();
        }
    }
}
