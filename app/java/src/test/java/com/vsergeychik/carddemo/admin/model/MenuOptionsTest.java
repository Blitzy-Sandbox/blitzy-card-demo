package com.vsergeychik.carddemo.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MenuOptions}, the single Java type for COBOL copybook {@code app/cpy/COMEN02Y.cpy} - the
 * {@code 01 CARDDEMO-MAIN-MENU-OPTIONS} main-menu option table of transaction {@code CM00}.
 */
@DisplayName("MenuOptions - CARDDEMO-MAIN-MENU-OPTIONS of COMEN02Y")
class MenuOptionsTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String OPTION_1_NAME = "Account View" + " ".repeat(23);

    private static final String OPTION_2_NAME = "Account Update" + " ".repeat(21);

    private static final String OPTION_3_NAME = "Credit Card List" + " ".repeat(19);

    private static final String OPTION_4_NAME = "Credit Card View" + " ".repeat(19);

    private static final String OPTION_5_NAME = "Credit Card Update" + " ".repeat(17);

    private static final String OPTION_6_NAME = "Transaction List" + " ".repeat(19);

    private static final String OPTION_7_NAME = "Transaction View" + " ".repeat(19);

    private static final String OPTION_8_NAME = "Transaction Add" + " ".repeat(20);

    private static final String OPTION_9_NAME = "Transaction Reports" + " ".repeat(16);

    private static final String OPTION_10_NAME = "Bill Payment" + " ".repeat(23);

    private static final String DECOY_OPTION_8_NAME = "Transaction Add (Admin Only)" + " ".repeat(7);

    private static final List<String> OPTION_NAMES = List.of(
            OPTION_1_NAME, OPTION_2_NAME, OPTION_3_NAME, OPTION_4_NAME, OPTION_5_NAME,
            OPTION_6_NAME, OPTION_7_NAME, OPTION_8_NAME, OPTION_9_NAME, OPTION_10_NAME);

    private static final List<String> OPTION_PROGRAM_NAMES = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    private static final String USER_TYPE_USER = "U";

    private static final String USER_TYPE_ADMIN = "A";

    private static final int SIBLING_ADMIN_ENTRY_LENGTH = 45;

    private static final int POPULATED_DATA_LENGTH_IF_COUNT_WERE_FOLDED_IN = 462;

    private static String toNameWidth(String visible) {
        return visible + " ".repeat(MenuOptions.OPT_NAME_LENGTH - visible.length());
    }

    private static FixedWidthCodec asciiCodec() {
        return new FixedWidthCodec(ASCII);
    }

    private static List<FieldSpan> mutableStorageSpans() {
        return new ArrayList<>(MenuOptions.GROUP_LAYOUT.storageSpans());
    }

    @Nested
    @DisplayName("Constants and widths, transcribed from the copybook rather than read off the class")
    class ConstantsAndWidths {
        @Test
        @DisplayName("each declared width is the PICTURE the copybook writes")
        void eachDeclaredWidthIsItsPicture() {
            assertThat(MenuOptions.OPT_NUM_LENGTH).isEqualTo(2);
            assertThat(MenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            assertThat(MenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(8);
            assertThat(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(1);
            assertThat(MenuOptions.MENU_OPT_COUNT_LENGTH).isEqualTo(2);
            assertThat(MenuOptions.SUBFIELDS_PER_ENTRY).isEqualTo(4);
            assertThat(MenuOptions.FIRST_SUBSCRIPT).isEqualTo(1);
            assertThat(MenuOptions.MIN_OPT_NUM).isZero();
            assertThat(MenuOptions.MAX_OPT_NUM).isEqualTo(99);
        }

        @Test
        @DisplayName("an entry is 46 bytes: 2 + 35 + 8 + 1, one MORE than the sibling admin table's 45")
        void anEntryIsFortySixBytesComponentWise() {
            assertThat(MenuOptions.OPT_NUM_LENGTH
                    + MenuOptions.OPT_NAME_LENGTH
                    + MenuOptions.OPT_PGMNAME_LENGTH
                    + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isEqualTo(MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(46);
            assertThat(MenuOptions.ENTRY_LENGTH)
                    .as("46, not the sibling admin entry's 45; the extra byte is USRTYPE")
                    .isEqualTo(SIBLING_ADMIN_ENTRY_LENGTH + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isNotEqualTo(SIBLING_ADMIN_ENTRY_LENGTH);
        }

        @Test
        @DisplayName("the table declares TWELVE slots while only TEN are active - two independent facts")
        void theTableSizeAndTheActiveCountAreIndependent() {
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(12);
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuOptions.TABLE_SIZE).isGreaterThan(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(MenuOptions.TABLE_SIZE - MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(2);
            assertThat(MenuOptions.SPECIFIED_OPTION_COUNT_PLUS_ONE).isEqualTo(11);
        }

        @Test
        @DisplayName("the populated area is 460 bytes, NOT 462: the count field sits outside the group")
        void thePopulatedAreaIsFourHundredAndSixty() {
            assertThat(MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(460)
                    .isNotEqualTo(POPULATED_DATA_LENGTH_IF_COUNT_WERE_FOLDED_IN);
            assertThat(MenuOptions.TABLE_LENGTH)
                    .isEqualTo(MenuOptions.TABLE_SIZE * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(552);
            assertThat(MenuOptions.GROUP_LENGTH)
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_LENGTH + MenuOptions.TABLE_LENGTH)
                    .isEqualTo(554);
        }

        @Test
        @DisplayName("the subfield offsets follow the copybook's declaration order exactly")
        void theSubfieldOffsetsFollowDeclarationOrder() {
            assertThat(MenuOptions.OPT_NUM_OFFSET).isZero();
            assertThat(MenuOptions.OPT_NAME_OFFSET).isEqualTo(2);
            assertThat(MenuOptions.OPT_PGMNAME_OFFSET).isEqualTo(37);
            assertThat(MenuOptions.OPT_USRTYPE_OFFSET).isEqualTo(45);
            assertThat(MenuOptions.OPT_USRTYPE_OFFSET + MenuOptions.OPT_USRTYPE_LENGTH)
                    .isEqualTo(MenuOptions.ENTRY_LENGTH);
            assertThat(MenuOptions.NUM_SUBFIELD).isZero();
            assertThat(MenuOptions.NAME_SUBFIELD).isEqualTo(1);
            assertThat(MenuOptions.PGMNAME_SUBFIELD).isEqualTo(2);
            assertThat(MenuOptions.USRTYPE_SUBFIELD).isEqualTo(3);
        }

        @Test
        @DisplayName("the count field precedes the option area, and both views start right after it")
        void theCountFieldPrecedesTheOptionArea() {
            assertThat(MenuOptions.MENU_OPT_COUNT_OFFSET).isZero();
            assertThat(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_OFFSET + MenuOptions.MENU_OPT_COUNT_LENGTH)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the copybook item names are published verbatim, hyphens and all")
        void theCopybookItemNamesArePublishedVerbatim() {
            assertThat(MenuOptions.CARDDEMO_MAIN_MENU_OPTIONS)
                    .as("COMEN02Y.cpy:19 - and note it is MAIN, not ADMIN, despite the header on line 2")
                    .isEqualTo("CARDDEMO-MAIN-MENU-OPTIONS");
            assertThat(MenuOptions.CDEMO_MENU_OPT_COUNT).isEqualTo("CDEMO-MENU-OPT-COUNT");
            assertThat(MenuOptions.CDEMO_MENU_OPTIONS_DATA).isEqualTo("CDEMO-MENU-OPTIONS-DATA");
            assertThat(MenuOptions.CDEMO_MENU_OPTIONS).isEqualTo("CDEMO-MENU-OPTIONS");
            assertThat(MenuOptions.CDEMO_MENU_OPT).isEqualTo("CDEMO-MENU-OPT");
            assertThat(MenuOptions.CDEMO_MENU_OPT_NUM).isEqualTo("CDEMO-MENU-OPT-NUM");
            assertThat(MenuOptions.CDEMO_MENU_OPT_NAME).isEqualTo("CDEMO-MENU-OPT-NAME");
            assertThat(MenuOptions.CDEMO_MENU_OPT_PGMNAME).isEqualTo("CDEMO-MENU-OPT-PGMNAME");
            assertThat(MenuOptions.CDEMO_MENU_OPT_USRTYPE).isEqualTo("CDEMO-MENU-OPT-USRTYPE");
        }

        @Test
        @DisplayName("CDEMO-MENU-OPT-COUNT is readable as a number, as a two-byte image and as a span")
        void theCountFieldIsReadableEveryWay() {
            assertThat(MenuOptions.menuOptCount()).isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuOptions.menuOptCountImage()).isEqualTo("10").hasSize(2);

            FieldSpan count = MenuOptions.menuOptCountSpan();
            assertThat(count.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPT_COUNT);
            assertThat(count.offset()).isZero();
            assertThat(count.length()).isEqualTo(2);
            assertThat(count.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(count.redefinition())
                    .as("the count is real storage, not an overlay")
                    .isFalse();
            assertThat(count.hasInitialValue())
                    .as("COMEN02Y.cpy:21 declares VALUE 10, so the descriptor carries it")
                    .isTrue();
        }

        @Test
        @DisplayName("nothing mutable escapes: every published collection refuses modification")
        void nothingMutableEscapes() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.options().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.activeOptions().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.fieldSpans().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.entryFieldSpans(1).clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.GROUP_LAYOUT.spans().clear());
        }

        @Test
        @DisplayName("encode() hands back a fresh array each time, so no caller can alter the table")
        void encodeHandsBackAFreshArray() {
            byte[] first = MenuOptions.encode(ASCII);
            first[0] = (byte) '?';

            assertThat(MenuOptions.encode(ASCII))
                    .as("COMEN02Y.cpy:21's VALUE 10 still leads the image")
                    .isNotSameAs(first)
                    .startsWith((byte) '1', (byte) '0');
        }

        @Test
        @DisplayName("the class is a constant table and refuses to be instantiated")
        void theClassRefusesInstantiation() throws Exception {
            Constructor<MenuOptions> constructor = MenuOptions.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("The ten entries the copybook values, byte for byte")
    class PopulatedEntries {
        @ParameterizedTest(name = "option {0} from COMEN02Y.cpy:{1}-:{2}")
        @DisplayName("each carries its transcribed literal at its full declared width")
        @CsvSource({
            "1,  25,  29",
            "2,  31,  35",
            "3,  37,  41",
            "4,  43,  47",
            "5,  49,  53",
            "6,  55,  59",
            "7,  61,  65",
            "8,  67,  72",
            "9,  74,  78",
            "10, 80,  84",
        })
        void eachCarriesItsLiteralAtFullDeclaredWidth(int subscript, int firstLine, int lastLine) {
            String where = "COMEN02Y.cpy:" + firstLine + "-:" + lastLine;
            MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.menuOptNum()).as(where + " - CDEMO-MENU-OPT-NUM").isEqualTo(subscript);
            assertThat(option.menuOptNumImage())
                    .as(where + " - the PIC 9(02) image is always two bytes")
                    .hasSize(MenuOptions.OPT_NUM_LENGTH);
            assertThat(option.menuOptName())
                    .as(where + " - CDEMO-MENU-OPT-NAME, PIC X(35) per COMEN02Y.cpy:90")
                    .isEqualTo(OPTION_NAMES.get(subscript - 1))
                    .hasSize(MenuOptions.OPT_NAME_LENGTH);
            assertThat(option.menuOptPgmName())
                    .as(where + " - CDEMO-MENU-OPT-PGMNAME, PIC X(08) per COMEN02Y.cpy:91")
                    .isEqualTo(OPTION_PROGRAM_NAMES.get(subscript - 1))
                    .hasSize(MenuOptions.OPT_PGMNAME_LENGTH);
            assertThat(option.menuOptUsrType())
                    .as(where + " - CDEMO-MENU-OPT-USRTYPE, PIC X(01) per COMEN02Y.cpy:92")
                    .isEqualTo(USER_TYPE_USER)
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH);
        }

        @Test
        @DisplayName("every one of the ten transcribed names composes to exactly 35 characters")
        void everyTranscribedNameIsExactlyThirtyFiveCharacters() {
            assertThat(OPTION_NAMES).hasSize(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(OPTION_NAMES)
                    .allSatisfy(name -> assertThat(name).hasSize(MenuOptions.OPT_NAME_LENGTH));
        }

        @Test
        @DisplayName("every one of the ten program names is exactly 8 characters")
        void everyProgramNameIsExactlyEightCharacters() {
            assertThat(OPTION_PROGRAM_NAMES).hasSize(MenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(OPTION_PROGRAM_NAMES)
                    .allSatisfy(name -> assertThat(name).hasSize(MenuOptions.OPT_PGMNAME_LENGTH));
            assertThat(MenuOptions.activeOptions())
                    .allSatisfy(option -> assertThat(option.menuOptPgmName())
                            .hasSize(MenuOptions.OPT_PGMNAME_LENGTH));
        }

        @Test
        @DisplayName("slot 4 pairs 'Credit Card View' with COCRDSLC - transcribed, never corrected")
        void slotFourPairsCreditCardViewWithTheSelectProgram() {
            MenuOption option = MenuOptions.optionBySubscript(4).orElseThrow();

            assertThat(option.menuOptName()).isEqualTo(OPTION_4_NAME);
            assertThat(option.menuOptPgmName()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("slot 7 views with COTRN01C and slot 8 adds with COTRN02C - the inversion is kept")
        void slotsSevenAndEightCarryTheTransactionInversion() {
            assertThat(MenuOptions.optionBySubscript(7).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptName()).isEqualTo(OPTION_7_NAME);
                        assertThat(option.menuOptPgmName()).isEqualTo("COTRN01C");
                    });
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptName()).isEqualTo(OPTION_8_NAME);
                        assertThat(option.menuOptPgmName()).isEqualTo("COTRN02C");
                    });
        }

        @Test
        @DisplayName("activeOptions() is the ten of them, in subscript order, with no absent slot")
        void activeOptionsIsTheTenOfThemInOrder() {
            List<MenuOption> active = MenuOptions.activeOptions();

            assertThat(active).hasSize(MenuOptions.ACTIVE_OPTION_COUNT).hasSize(10);
            assertThat(active).extracting(MenuOption::menuOptNum)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(active).extracting(MenuOption::menuOptPgmName)
                    .containsExactlyElementsOf(OPTION_PROGRAM_NAMES);
            assertThat(active).extracting(MenuOption::menuOptName)
                    .containsExactlyElementsOf(OPTION_NAMES);
        }

        @ParameterizedTest
        @DisplayName("isSpecified() is true for each of the ten the copybook values")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void isSpecifiedIsTrueForEveryValuedSlot(int subscript) {
            assertThat(MenuOptions.isSpecified(subscript)).isTrue();
            assertThat(MenuOptions.optionBySubscript(subscript)).isPresent();
        }
    }

    @Nested
    @DisplayName("COMEN02Y.cpy:69 - the commented-out decoy, asserted AGAINST")
    class CommentedOutDecoy {
        @Test
        @DisplayName("slot 8 uses the ACTIVE line-70 label, not the commented-out '(Admin Only)' decoy")
        void slotEightUsesActiveLabelNotCommentedOutAdminOnlyDecoy() {
            String name = MenuOptions.optionBySubscript(8).orElseThrow().menuOptName();

            assertThat(name)
                    .as("COMEN02Y.cpy:70 - the LIVE literal")
                    .isEqualTo(OPTION_8_NAME);
            assertThat(name)
                    .as("COMEN02Y.cpy:69 - the commented-out literal must NEVER be the value")
                    .isNotEqualTo(DECOY_OPTION_8_NAME)
                    .doesNotContain("Admin Only");
        }

        @Test
        @DisplayName("the decoy is ALSO exactly 35 characters, so length cannot discriminate")
        void theDecoyIsAlsoThirtyFiveCharacters() {
            assertThat(DECOY_OPTION_8_NAME).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(OPTION_8_NAME).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(DECOY_OPTION_8_NAME).hasSameSizeAs(OPTION_8_NAME).isNotEqualTo(OPTION_8_NAME);
        }

        @Test
        @DisplayName("nothing is inferred from the dead comment: slot 8's user type is 'U', not 'A'")
        void nothingIsInferredFromTheDeadComment() {
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptUsrType())
                    .as("COMEN02Y.cpy:72")
                    .isEqualTo(USER_TYPE_USER)
                    .isNotEqualTo(USER_TYPE_ADMIN);
        }

        @Test
        @DisplayName("the decoy appears nowhere in the encoded image either")
        void theDecoyAppearsNowhereInTheEncodedImage() {
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            assertThat(image).contains(OPTION_8_NAME).doesNotContain("Admin Only");
        }
    }

    @Nested
    @DisplayName("CDEMO-MENU-OPT-USRTYPE - the user-type column the sibling admin table lacks")
    class UserTypeColumn {
        @ParameterizedTest
        @DisplayName("it is exactly one character wide on every populated slot")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void itIsExactlyOneCharacterWide(int subscript) {
            assertThat(MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptUsrType())
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH)
                    .hasSize(1);
        }

        @Test
        @DisplayName("it is 'U' on all ten populated entries")
        void itIsUserOnAllTenPopulatedEntries() {
            assertThat(MenuOptions.activeOptions())
                    .hasSize(10)
                    .extracting(MenuOption::menuOptUsrType)
                    .containsExactly(USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER,
                            USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER, USER_TYPE_USER,
                            USER_TYPE_USER, USER_TYPE_USER);
        }

        @Test
        @DisplayName("'A' appears on NO populated slot, in the entries or in the bytes")
        void adminNeverAppearsOnAnyPopulatedSlot() {
            assertThat(MenuOptions.activeOptions())
                    .extracting(MenuOption::menuOptUsrType)
                    .doesNotContain(USER_TYPE_ADMIN);

            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);
            for (int subscript = 1; subscript <= MenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                String key = MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, subscript);
                assertThat(images.get(key))
                        .as(key + " - COMEN02Y.cpy's X(01) column for that slot")
                        .isEqualTo(USER_TYPE_USER)
                        .isNotEqualTo(USER_TYPE_ADMIN);
            }
        }

        @Test
        @DisplayName("the column occupies the entry's last byte, so omitting it would shift nothing else")
        void theColumnOccupiesTheEntrysLastByte() {
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(1);
            FieldSpan usrType = spans.get(MenuOptions.USRTYPE_SUBFIELD);

            assertThat(usrType.name())
                    .isEqualTo(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, 1));
            assertThat(usrType.length()).isEqualTo(1);
            assertThat(usrType.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.OPT_USRTYPE_OFFSET)
                    .isEqualTo(47);
            assertThat(usrType.endOffsetExclusive())
                    .as("byte 48 is where slot 2's CDEMO-MENU-OPT-NUM begins")
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(48);
        }
    }

    @Nested
    @DisplayName("Untrimmed values - the padding is part of the field, and here it is load-bearing")
    class UntrimmedValues {
        @ParameterizedTest
        @DisplayName("CDEMO-MENU-OPT-NAME comes back untrimmed at its full 35 characters")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void theNameComesBackUntrimmed(int subscript) {
            String name = MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptName();

            assertThat(name).hasSize(MenuOptions.OPT_NAME_LENGTH).hasSize(35);
            assertThat(name)
                    .as("every one of the ten literals is shorter than 35 visible characters")
                    .endsWith(" ");
            assertThat(name)
                    .as("the NEGATIVE assertion: a future 'helpful' trim inside the model fails here")
                    .isNotEqualTo(name.trim())
                    .isNotEqualTo(name.strip());
        }

        @Test
        @DisplayName("both consumer shapes are reproducible from the untrimmed value")
        void bothConsumerShapesAreReproducible() {
            String name = MenuOptions.optionBySubscript(1).orElseThrow().menuOptName();

            assertThat(name).isEqualTo(OPTION_1_NAME).hasSize(35);
            assertThat(name.substring(0, name.indexOf(' '))).isEqualTo("Account");
        }

        @ParameterizedTest
        @DisplayName("CDEMO-MENU-OPT-PGMNAME comes back untrimmed at its full 8 characters")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void theProgramNameComesBackUntrimmed(int subscript) {
            String programName = MenuOptions.optionBySubscript(subscript).orElseThrow().menuOptPgmName();

            assertThat(programName).hasSize(MenuOptions.OPT_PGMNAME_LENGTH).hasSize(8);
            assertThat(programName)
                    .as("COMEN02Y.cpy's PGMNAME literals contain no space at all")
                    .doesNotContain(" ")
                    .isEqualTo(programName.trim());
            assertThat(programName.substring(0, 5))
                    .as("COMEN01C.cbl:146 slices five bytes out of this column")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the program-name column DOES pad, proven with a shorter synthetic value")
        void theProgramNameColumnDoesPad() {
            assertThat(MenuOption.of(1, toNameWidth("Synthetic"), "PGM", USER_TYPE_USER)
                    .menuOptPgmName())
                    .isEqualTo("PGM" + " ".repeat(5))
                    .hasSize(MenuOptions.OPT_PGMNAME_LENGTH);
        }

        @Test
        @DisplayName("the user-type column is handed back at its declared single byte")
        void theUserTypeColumnIsHandedBackWhole() {
            String usrType = MenuOptions.optionBySubscript(1).orElseThrow().menuOptUsrType();

            assertThat(usrType).hasSize(1).isEqualTo(USER_TYPE_USER).isEqualTo(usrType.trim());
        }
    }

    @Nested
    @DisplayName("PIC 9(02) has BOTH an int value and a two-byte zero-filled image")
    class OptionNumberImage {
        @Test
        @DisplayName("option 1 renders '01', option 9 renders '09' and option 10 renders '10'")
        void optionTenRendersAsTwoCharacterImage() {
            assertThat(MenuOptions.optionBySubscript(1).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(1);
                        assertThat(option.menuOptNumImage()).isEqualTo("01");
                    });
            assertThat(MenuOptions.optionBySubscript(9).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(9);
                        assertThat(option.menuOptNumImage()).isEqualTo("09");
                    });
            assertThat(MenuOptions.optionBySubscript(10).orElseThrow())
                    .satisfies(option -> {
                        assertThat(option.menuOptNum()).isEqualTo(10);
                        assertThat(option.menuOptNumImage()).isEqualTo("10");
                    });
        }

        @ParameterizedTest
        @DisplayName("the image is exactly two characters on every one of the ten populated slots")
        @CsvSource({"1, 01", "2, 02", "3, 03", "4, 04", "5, 05",
                    "6, 06", "7, 07", "8, 08", "9, 09", "10, 10"})
        void theImageIsAlwaysTwoCharacters(int subscript, String expectedImage) {
            MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.menuOptNumImage())
                    .isEqualTo(expectedImage)
                    .hasSize(MenuOptions.OPT_NUM_LENGTH);
            assertThat(option.menuOptNum()).isEqualTo(subscript);
            assertThat(Integer.parseInt(option.menuOptNumImage()))
                    .as("the two views of one PIC 9(02) field must agree")
                    .isEqualTo(option.menuOptNum());
        }

        @Test
        @DisplayName("the composed 'NN. ' + name line is derivable, across the 9 to 10 rollover")
        void theComposedOptionLineIsDerivable() {
            MenuOption ninth = MenuOptions.optionBySubscript(9).orElseThrow();
            MenuOption tenth = MenuOptions.optionBySubscript(10).orElseThrow();

            assertThat(ninth.menuOptNumImage() + ". " + ninth.menuOptName())
                    .startsWith("09. Transaction Reports")
                    .hasSize(39);
            assertThat(tenth.menuOptNumImage() + ". " + tenth.menuOptName())
                    .as("the rollover: two digits, still two bytes, so the line width does not change")
                    .startsWith("10. Bill Payment")
                    .hasSize(39);
        }

        @Test
        @DisplayName("the option number is an int - never a decimal type (rule R4, gate G22)")
        void theOptionNumberIsIntegral() throws Exception {
            assertThat(MenuOption.class.getMethod("menuOptNum").getReturnType()).isEqualTo(int.class);

            assertThat(MenuOption.class.getRecordComponents())
                    .as("no component of the entry may be a floating-point or fixed-point decimal type")
                    .allSatisfy(component -> assertThat(component.getType())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class)
                            .isNotEqualTo(Float.class)
                            .isNotEqualTo(BigDecimal.class));
            assertThat(MenuOptions.class.getMethod("menuOptCount").getReturnType())
                    .isEqualTo(int.class);
        }
    }

    @Nested
    @DisplayName("Gate G33 - OCCURS is 1-based in COBOL and 0-based in Java")
    class OneBasedSubscripts {
        @Test
        @DisplayName("the FIRST element is subscript 1, and it is Account View / COACTVWC")
        void theFirstElementIsSubscriptOne() {
            MenuOption first = MenuOptions.optionBySubscript(MenuOptions.FIRST_SUBSCRIPT).orElseThrow();

            assertThat(first.menuOptNum()).isEqualTo(1);
            assertThat(first.menuOptNumImage()).isEqualTo("01");
            assertThat(first.menuOptName()).isEqualTo(OPTION_1_NAME);
            assertThat(first.menuOptPgmName()).isEqualTo("COACTVWC");
            assertThat(first.menuOptUsrType()).isEqualTo(USER_TYPE_USER);
        }

        @Test
        @DisplayName("the LAST element is subscript 12, not 10 - and it is accepted and empty")
        void theLastElementIsSubscriptTwelve() {
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(12);
            assertThatNoException()
                    .isThrownBy(() -> MenuOptions.optionBySubscript(MenuOptions.TABLE_SIZE));
            assertThat(MenuOptions.optionBySubscript(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThat(MenuOptions.isSpecified(MenuOptions.TABLE_SIZE)).isFalse();
        }

        @Test
        @DisplayName("subscript 10 is the last POPULATED slot: Bill Payment / COBIL00C")
        void subscriptTenIsTheLastPopulatedSlot() {
            MenuOption tenth = MenuOptions.optionBySubscript(MenuOptions.ACTIVE_OPTION_COUNT).orElseThrow();

            assertThat(tenth.menuOptNum()).isEqualTo(10);
            assertThat(tenth.menuOptName()).isEqualTo(OPTION_10_NAME).hasSize(35);
            assertThat(tenth.menuOptPgmName()).isEqualTo("COBIL00C").hasSize(8);
            assertThat(tenth.menuOptUsrType()).isEqualTo(USER_TYPE_USER);
        }

        @Test
        @DisplayName("the 1-based to 0-based correspondence holds at both ends AND mid-table")
        void theCorrespondenceHoldsAtBothEndsAndMidTable() {
            List<Optional<MenuOption>> zeroBased = MenuOptions.options();

            assertThat(zeroBased).hasSize(MenuOptions.TABLE_SIZE).hasSize(12);
            assertThat(MenuOptions.optionBySubscript(1)).isEqualTo(zeroBased.get(0));
            assertThat(MenuOptions.optionBySubscript(12)).isEqualTo(zeroBased.get(11));
            assertThat(MenuOptions.optionBySubscript(10)).isEqualTo(zeroBased.get(9));
        }

        @ParameterizedTest
        @DisplayName("every subscript 1 to 12 maps to list index subscript minus one")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        void everySubscriptMapsToItsIndex(int subscript) {
            assertThat(MenuOptions.optionBySubscript(subscript))
                    .isEqualTo(MenuOptions.options().get(subscript - MenuOptions.FIRST_SUBSCRIPT));
        }

        @Test
        @DisplayName("the element spans are derived through the shared one-based OCCURS primitive")
        void theElementSpansAreDerivedThroughTheSharedPrimitive() {
            assertThat(MenuOptions.entrySpan(1).offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(2);
            assertThat(MenuOptions.entrySpan(12).offset())
                    .isEqualTo(FixedWidthRecord.occursElementOffsetOneBased(MenuOptions.TABLE_OFFSET,
                            MenuOptions.ENTRY_LENGTH, MenuOptions.TABLE_SIZE, 12))
                    .isEqualTo(MenuOptions.TABLE_OFFSET + 11 * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(508);
            assertThat(MenuOptions.entrySpan(12).endOffsetExclusive())
                    .as("the last element ends exactly at the end of the group")
                    .isEqualTo(MenuOptions.GROUP_LENGTH)
                    .isEqualTo(554);
            assertThat(MenuOptions.entrySpan(1).length())
                    .isEqualTo(MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(46);
            assertThat(MenuOptions.entrySpan(1).name()).isEqualTo(MenuOptions.CDEMO_MENU_OPT);
        }

        @ParameterizedTest
        @DisplayName("each entry's four subfield spans sit at their declared offsets within the element")
        @ValueSource(ints = {1, 6, 10, 11, 12})
        void eachEntrysSubfieldSpansSitAtTheirDeclaredOffsets(int subscript) {
            int base = MenuOptions.TABLE_OFFSET
                    + (subscript - MenuOptions.FIRST_SUBSCRIPT) * MenuOptions.ENTRY_LENGTH;
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(subscript);

            assertThat(spans).hasSize(MenuOptions.SUBFIELDS_PER_ENTRY).hasSize(4);
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).offset()).isEqualTo(base);
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).length()).isEqualTo(2);
            assertThat(spans.get(MenuOptions.NUM_SUBFIELD).kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(spans.get(MenuOptions.NAME_SUBFIELD).offset()).isEqualTo(base + 2);
            assertThat(spans.get(MenuOptions.NAME_SUBFIELD).length()).isEqualTo(35);
            assertThat(spans.get(MenuOptions.PGMNAME_SUBFIELD).offset()).isEqualTo(base + 37);
            assertThat(spans.get(MenuOptions.PGMNAME_SUBFIELD).length()).isEqualTo(8);
            assertThat(spans.get(MenuOptions.USRTYPE_SUBFIELD).offset()).isEqualTo(base + 45);
            assertThat(spans.get(MenuOptions.USRTYPE_SUBFIELD).length()).isEqualTo(1);
            assertThat(spans).allSatisfy(span -> assertThat(span.redefinition()).isTrue());
        }

        @Test
        @DisplayName("subscripted names are spelled the way COBOL references them")
        void subscriptedNamesAreSpelledTheWayCobolReferencesThem() {
            assertThat(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 3))
                    .isEqualTo("CDEMO-MENU-OPT-NAME(3)");
            assertThat(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, 12))
                    .isEqualTo("CDEMO-MENU-OPT-USRTYPE(12)");
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.subscriptedName(null, 1));
        }
    }

    @Nested
    @DisplayName("Gate G33 - the accessor REJECTS an out-of-range subscript, and never clamps it")
    class SubscriptBoundsRejection {
        @Test
        @DisplayName("the LOWER boundary pair, adjacently: 0 is rejected and 1 is accepted")
        void theLowerBoundaryPairIsZeroRejectedAndOneAccepted() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(0))
                    .withMessageContaining("outside 1..12");
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(1));
            assertThat(MenuOptions.optionBySubscript(1)).isPresent();
        }

        @Test
        @DisplayName("the UPPER boundary pair, adjacently: 12 is accepted and 13 is rejected")
        void subscriptThirteenIsRejectedNotClamped() {
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(12));
            assertThat(MenuOptions.optionBySubscript(12)).isEmpty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(13))
                    .withMessageContaining("outside 1..12");
        }

        @ParameterizedTest
        @DisplayName("every out-of-range subscript is rejected, never clamped and never blanked")
        @ValueSource(ints = {0, 13, 14, 99, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
        void everyOutOfRangeSubscriptIsRejected(int subscript) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("subscript " + subscript + " must be REFUSED")
                    .isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
        }

        @ParameterizedTest
        @DisplayName("every subscript-taking accessor rejects consistently, so none is a back door")
        @ValueSource(ints = {0, 13, 99})
        void everySubscriptTakingAccessorRejectsConsistently(int subscript) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.isSpecified(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.entrySpan(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.entryFieldSpans(subscript));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME,
                            subscript));
        }

        @Test
        @DisplayName("rejection is a throw, not a silent substitution of slot 1 or slot 12")
        void rejectionIsAThrowNotASilentSubstitution() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(0))
                    .withMessageContaining("COBOL subscripts are 1-based");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(13))
                    .withMessageContaining("COBOL subscripts are 1-based");
            assertThat(MenuOptions.optionBySubscript(1))
                    .isNotEqualTo(MenuOptions.optionBySubscript(12));
        }
    }

    @Nested
    @DisplayName("Slots 11 and 12 - present as slots, absent as entries, and never fabricated")
    class UnspecifiedTailSlots {
        @ParameterizedTest
        @DisplayName("slots 11 and 12 are present but empty: accepted, addressable, and unvalued")
        @ValueSource(ints = {11, 12})
        void slotsElevenAndTwelveArePresentButEmpty(int subscript) {
            assertThatNoException().isThrownBy(() -> MenuOptions.optionBySubscript(subscript));
            assertThatNoException().isThrownBy(() -> MenuOptions.isSpecified(subscript));
            assertThatNoException().isThrownBy(() -> MenuOptions.entrySpan(subscript));

            assertThat(MenuOptions.isSpecified(subscript))
                    .as("COMEN02Y.cpy declares no VALUE for this slot")
                    .isFalse();
            assertThat(MenuOptions.optionBySubscript(subscript)).isEmpty();
        }

        @Test
        @DisplayName("the table stays TWELVE slots wide: the tail is absent, never trimmed away")
        void theTableStaysTwelveSlotsWide() {
            List<Optional<MenuOption>> slots = MenuOptions.options();

            assertThat(slots).hasSize(12);
            assertThat(slots.subList(0, MenuOptions.ACTIVE_OPTION_COUNT))
                    .as("COMEN02Y.cpy:25-84 - the ten the copybook values")
                    .allSatisfy(slot -> assertThat(slot).isPresent());
            assertThat(slots.subList(MenuOptions.ACTIVE_OPTION_COUNT, MenuOptions.TABLE_SIZE))
                    .as("slots 11 and 12 - present as slots, absent as entries")
                    .hasSize(2)
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("no accessor can be talked into reporting a value for them")
        void noAccessorReportsAValueForThem() {
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(11).orElseThrow());
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(12).orElseThrow());
            assertThat(MenuOptions.activeOptions())
                    .as("activeOptions() never contains a fabricated tail entry")
                    .hasSize(MenuOptions.ACTIVE_OPTION_COUNT)
                    .extracting(MenuOption::menuOptNum)
                    .doesNotContain(0);
        }

        @Test
        @DisplayName("the tail's spans ARE addressable, at their declared widths of 35, 8 and 1")
        void theTailSpansAreAddressableAtTheirDeclaredWidths() {
            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);

            for (int subscript : new int[] {11, 12}) {
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NUM, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-NUM, PIC 9(02) - two bytes")
                        .hasSize(MenuOptions.OPT_NUM_LENGTH)
                        .isEqualTo("00");
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-NAME, PIC X(35) - space-filled")
                        .hasSize(MenuOptions.OPT_NAME_LENGTH)
                        .isEqualTo(" ".repeat(35));
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_PGMNAME, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-PGMNAME, PIC X(08) - space-filled")
                        .hasSize(MenuOptions.OPT_PGMNAME_LENGTH)
                        .isEqualTo(" ".repeat(8));
                assertThat(images.get(
                        MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE, subscript)))
                        .as("slot " + subscript + " CDEMO-MENU-OPT-USRTYPE, PIC X(01) - space-filled")
                        .hasSize(MenuOptions.OPT_USRTYPE_LENGTH)
                        .isEqualTo(" ");
            }
        }

        @Test
        @DisplayName("the unspecified tail descriptor names the 92 bytes the overlay adds")
        void theUnspecifiedTailDescriptorNamesTheNinetyTwoBytes() {
            FieldSpan tail = MenuOptions.unspecifiedTailSpan();

            assertThat(tail.name())
                    .isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS + MenuOptions.UNSPECIFIED_TAIL_SUFFIX);
            assertThat(tail.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(462);
            assertThat(tail.length())
                    .isEqualTo(MenuOptions.TABLE_LENGTH - MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo((MenuOptions.TABLE_SIZE - MenuOptions.ACTIVE_OPTION_COUNT)
                            * MenuOptions.ENTRY_LENGTH)
                    .isEqualTo(92);
            assertThat(tail.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(tail.redefinition()).isTrue();
            assertThat(tail.hasInitialValue())
                    .as("the copybook declares no VALUE for these bytes, so the descriptor carries none")
                    .isFalse();
            assertThat(tail.endOffsetExclusive()).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
        }

        @Test
        @DisplayName("decode reports the tail as absent even when its bytes WOULD parse")
        void decodeReportsTheTailAsAbsentEvenWhenParseable() {
            byte[] image = MenuOptions.encode(ASCII);

            assertThat(new String(image, ASCII)
                    .substring(MenuOptions.unspecifiedTailSpan().offset(),
                            MenuOptions.unspecifiedTailSpan().offset() + MenuOptions.OPT_NUM_LENGTH))
                    .as("the tail's first two bytes really are parseable digits")
                    .isEqualTo("00");

            List<Optional<MenuOption>> decoded = MenuOptions.decode(image, ASCII);
            assertThat(decoded.get(10)).isEmpty();
            assertThat(decoded.get(11)).isEmpty();
        }

        @Test
        @DisplayName("decode survives a tail of GARBAGE, and the ten valued entries are unharmed")
        void decodeSurvivesAGarbageTail() {
            byte[] image = MenuOptions.encode(ASCII);
            for (int offset = MenuOptions.unspecifiedTailSpan().offset(); offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            List<Optional<MenuOption>> decoded = MenuOptions.decode(image, ASCII);

            assertThat(decoded).hasSize(MenuOptions.TABLE_SIZE);
            assertThat(decoded.subList(0, MenuOptions.ACTIVE_OPTION_COUNT))
                    .isEqualTo(MenuOptions.options().subList(0, MenuOptions.ACTIVE_OPTION_COUNT));
            assertThat(decoded.get(10)).isEmpty();
            assertThat(decoded.get(11)).isEmpty();
        }

        @Test
        @DisplayName("the garbage tail is still readable as BYTES, which is where it belongs")
        void theGarbageTailIsStillReadableAsBytes() {
            byte[] image = MenuOptions.encode(ASCII);
            for (int offset = MenuOptions.unspecifiedTailSpan().offset(); offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            Map<String, String> images = MenuOptions.fieldImages(image, ASCII);

            assertThat(images).hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 11)))
                    .isEqualTo("?".repeat(MenuOptions.OPT_NAME_LENGTH));
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NUM, 12)))
                    .isEqualTo("??");
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .as("a garbage tail cannot disturb a valued entry")
                    .isEqualTo(OPTION_1_NAME);
        }

        @Test
        @DisplayName("garbage in a SPECIFIED slot is still an error, because those bytes ARE claimed")
        void garbageInASpecifiedSlotIsStillAnError() {
            byte[] image = MenuOptions.encode(ASCII);
            image[MenuOptions.TABLE_OFFSET] = (byte) '?';

            assertThatIllegalArgumentException().isThrownBy(() -> MenuOptions.decode(image, ASCII));
        }
    }

    @Nested
    @DisplayName("Gate G21 - the total declared width, and the self-check that enforces it")
    class TotalWidthSelfCheck {
        @Test
        @DisplayName("the layout declares 554 bytes as 49 contiguous storage spans from offset 0")
        void theLayoutDeclaresEveryByteExactlyOnce() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;
            List<FieldSpan> storage = layout.storageSpans();

            assertThat(layout.recordLength()).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
            assertThat(storage)
                    .as("the count span plus 12 slots x 4 subfields")
                    .hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(storage.stream().mapToInt(FieldSpan::length).sum())
                    .as("the storage spans sum to exactly the declared record length")
                    .isEqualTo(MenuOptions.GROUP_LENGTH);

            int cursor = 0;
            for (FieldSpan span : storage) {
                assertThat(span.offset())
                        .as("no gap and no overlap before " + span.describe())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(MenuOptions.GROUP_LENGTH).isEqualTo(554);
        }

        @Test
        @DisplayName("the FILLER spans of COMEN02Y.cpy:25-84 are EMITTED, which is what makes 460 reachable")
        void theFillerSpansAreEmittedRatherThanSkipped() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;
            List<FieldSpan> valued = layout.storageSpans().stream()
                    .filter(FieldSpan::hasInitialValue)
                    .toList();

            assertThat(valued)
                    .as("the count field plus 10 valued slots x 4 subfields")
                    .hasSize(1 + MenuOptions.ACTIVE_OPTION_COUNT * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(41);
            assertThat(valued.stream().mapToInt(FieldSpan::length).sum())
                    .as("2 bytes of count (COMEN02Y.cpy:21) plus the 460 of COMEN02Y.cpy:25-84")
                    .isEqualTo(MenuOptions.MENU_OPT_COUNT_LENGTH + MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(462);
            assertThat(valued.stream().mapToInt(FieldSpan::length).sum()
                    - MenuOptions.MENU_OPT_COUNT_LENGTH)
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(460);
            assertThat(MenuOptions.GROUP_LAYOUT.storageSpans().stream()
                    .filter(span -> !span.hasInitialValue())
                    .count())
                    .isEqualTo(2L * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .isEqualTo(8L);
        }

        @Test
        @DisplayName("FILLER may repeat, which is the only reason a 48-FILLER layout can exist at all")
        void fillerMayRepeatWhereAReferableNameMayNot() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThat(layout.storageSpans().stream().filter(span -> "FILLER".equals(span.name())).count())
                    .isEqualTo((long) MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .isEqualTo(48L);
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPT_COUNT)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPTIONS_DATA)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.CDEMO_MENU_OPTIONS)).isTrue();
            assertThat(layout.hasSpan(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .as("the storage spans are FILLER-named, so a subscripted name is not among them")
                    .isFalse();
        }

        @Test
        @DisplayName("the self-check PASSES for the correct descriptor set, storage only and complete")
        void theSelfCheckPassesForTheCorrectDescriptorSet() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(MenuOptions.GROUP_LENGTH, layout.storageSpans()));
            assertThatNoException().isThrownBy(
                    () -> new RecordLayout(MenuOptions.GROUP_LENGTH, layout.spans()));
            assertThat(layout.spans())
                    .as("49 storage spans plus the two REDEFINES overlays")
                    .hasSize(49 + 2)
                    .hasSize(51);
        }

        @Test
        @DisplayName("the self-check FAILS when the TRAILING one-byte USRTYPE span is omitted")
        void theSelfCheckFailsWhenTheTrailingUsrtypeSpanIsOmitted() {
            List<FieldSpan> spans = mutableStorageSpans();
            FieldSpan dropped = spans.remove(spans.size() - 1);

            assertThat(dropped.length())
                    .as("the trailing span is the one-byte user-type column of slot 12")
                    .isEqualTo(MenuOptions.OPT_USRTYPE_LENGTH);
            assertThat(dropped.offset()).isEqualTo(553);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("1 byte(s) short");
        }

        @Test
        @DisplayName("the self-check FAILS when an INTERIOR USRTYPE span is omitted, shifting later offsets")
        void theSelfCheckFailsWhenAnInteriorUsrtypeSpanIsOmitted() {
            List<FieldSpan> spans = mutableStorageSpans();
            FieldSpan dropped = spans.remove(1 + MenuOptions.USRTYPE_SUBFIELD);

            assertThat(dropped.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.OPT_USRTYPE_OFFSET)
                    .isEqualTo(47);
            assertThat(dropped.length()).isEqualTo(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("Layout gap of 1 byte(s) before")
                    .withMessageContaining("offset 48")
                    .withMessageContaining("the preceding spans end at byte 47");
        }

        @Test
        @DisplayName("the self-check FAILS when a PIC X(35) span is mis-sized, and names the shortfall")
        void theSelfCheckFailsWhenASpanIsMisSized() {
            List<FieldSpan> spans = mutableStorageSpans();
            int nameIndex = 1 + MenuOptions.NAME_SUBFIELD;
            FieldSpan original = spans.get(nameIndex);

            assertThat(original.length()).isEqualTo(MenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            assertThatIllegalArgumentException()
                    .as("FieldSpan alone rejects a 35-character literal in a 34-byte span")
                    .isThrownBy(() -> new FieldSpan(original.name(), original.offset(),
                            original.length() - 1, original.kind(), original.initialValue(), false))
                    .withMessageContaining("VALUE literal of 35 character(s)");

            spans.set(nameIndex, new FieldSpan(original.name(), original.offset(),
                    original.length() - 1, original.kind(), null, false));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH, spans))
                    .withMessageContaining("Layout gap of 1 byte(s) before")
                    .withMessageContaining("offset 39");
        }

        @Test
        @DisplayName("the self-check FAILS when the record length is overstated")
        void theSelfCheckFailsWhenTheRecordLengthIsOverstated() {
            List<FieldSpan> spans = mutableStorageSpans();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH + 1, spans))
                    .withMessageContaining("1 byte(s) short");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(MenuOptions.GROUP_LENGTH - 1, spans))
                    .withMessageContaining("1 byte(s) too long");
        }

        @Test
        @DisplayName("every named field of the group is readable, keyed by its COBOL reference form")
        void everyNamedFieldOfTheGroupIsReadable() {
            Map<String, String> images = MenuOptions.fieldImages(MenuOptions.encode(ASCII), ASCII);

            assertThat(images)
                    .as("the count plus 12 slots x 4 subfields")
                    .hasSize(1 + MenuOptions.TABLE_SIZE * MenuOptions.SUBFIELDS_PER_ENTRY)
                    .hasSize(49);
            assertThat(MenuOptions.fieldSpans()).hasSameSizeAs(images.keySet());
            assertThat(images.get(MenuOptions.CDEMO_MENU_OPT_COUNT)).isEqualTo("10");
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .isEqualTo(OPTION_1_NAME);
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_PGMNAME, 10)))
                    .isEqualTo("COBIL00C");
            assertThat(images.keySet()).first().isEqualTo(MenuOptions.CDEMO_MENU_OPT_COUNT);
            assertThat(images.keySet()).last()
                    .isEqualTo(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_USRTYPE,
                            MenuOptions.TABLE_SIZE));
        }
    }

    @Nested
    @DisplayName("Gate G34 - REDEFINES is two typed views over ONE backing span")
    class RedefinesViews {
        @Test
        @DisplayName("both views begin at the same offset, so they address one backing span")
        void bothViewsBeginAtTheSameOffset() {
            FieldSpan dataView = MenuOptions.populatedDataSpan();
            FieldSpan tableView = MenuOptions.tableSpan();

            assertThat(dataView.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS_DATA);
            assertThat(tableView.name()).isEqualTo(MenuOptions.CDEMO_MENU_OPTIONS);
            assertThat(dataView.offset()).isEqualTo(tableView.offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET)
                    .isEqualTo(2);
            assertThat(dataView.redefinition()).isTrue();
            assertThat(tableView.redefinition()).isTrue();
        }

        @Test
        @DisplayName("the redefining view is declared over a SHORTER area: 552 over 460")
        void theRedefiningViewIsDeclaredOverAShorterArea() {
            assertThat(MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(460);
            assertThat(MenuOptions.TABLE_LENGTH).isEqualTo(552);
            assertThat(MenuOptions.TABLE_LENGTH).isGreaterThan(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(MenuOptions.TABLE_LENGTH - MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(92);
            assertThat(MenuOptions.populatedDataSpan().length())
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(MenuOptions.tableSpan().length()).isEqualTo(MenuOptions.TABLE_LENGTH);
        }

        @Test
        @DisplayName("the REDEFINES adds ZERO bytes to the group - it is an overlay, not an append")
        void theRedefinesAddsZeroBytesToTheGroup() {
            RecordLayout layout = MenuOptions.GROUP_LAYOUT;

            assertThat(layout.redefinitions()).hasSize(2);
            assertThat(layout.redefinitions().stream().mapToInt(FieldSpan::length).sum())
                    .isEqualTo(MenuOptions.POPULATED_DATA_LENGTH + MenuOptions.TABLE_LENGTH)
                    .isEqualTo(1012);
            assertThat(layout.storageSpans().stream().mapToInt(FieldSpan::length).sum())
                    .as("the overlays contribute nothing: storage alone is the record length")
                    .isEqualTo(layout.recordLength())
                    .isEqualTo(MenuOptions.GROUP_LENGTH);
            assertThat(layout.redefinitions())
                    .allSatisfy(overlay -> assertThat(overlay.endOffsetExclusive())
                            .isLessThanOrEqualTo(MenuOptions.GROUP_LENGTH));
        }

        @Test
        @DisplayName("reading through the table view returns exactly the bytes the -DATA view holds")
        void readingThroughTheTableViewReturnsTheDataViewBytes() {
            FixedWidthRecord area = FixedWidthRecord.copyOf(MenuOptions.encode(ASCII),
                    MenuOptions.GROUP_LENGTH, ASCII);

            String dataView = area.readSpan(MenuOptions.populatedDataSpan());
            String tableView = area.readSpan(MenuOptions.tableSpan());

            assertThat(dataView).hasSize(MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(tableView).hasSize(MenuOptions.TABLE_LENGTH);
            assertThat(tableView)
                    .as("one backing span: the wider view starts with the narrower one's bytes")
                    .startsWith(dataView);

            StringBuilder expected = new StringBuilder();
            for (MenuOption option : MenuOptions.activeOptions()) {
                expected.append(option.menuOptNumImage())
                        .append(option.menuOptName())
                        .append(option.menuOptPgmName())
                        .append(option.menuOptUsrType());
            }
            assertThat(dataView).isEqualTo(expected.toString());
        }

        @Test
        @DisplayName("writing through one view and reading through the other round-trips both ways")
        void writingThroughOneViewAndReadingThroughTheOtherRoundTrips() {
            byte[] literalPath = MenuOptions.declaredImage(ASCII);
            byte[] tableViewPath = MenuOptions.encode(ASCII);

            assertThat(literalPath).isEqualTo(tableViewPath).hasSize(MenuOptions.GROUP_LENGTH);
            assertThat(MenuOptions.decode(literalPath, ASCII)).isEqualTo(MenuOptions.options());
            assertThat(MenuOptions.decode(tableViewPath, ASCII)).isEqualTo(MenuOptions.options());

            assertThat(MenuOptions.encode(ASCII)).isEqualTo(tableViewPath);
        }

        @Test
        @DisplayName("a byte written through the table view is visible through the -DATA view")
        void aByteWrittenThroughTheTableViewIsVisibleThroughTheDataView() {
            FixedWidthCodec codec = asciiCodec();
            FixedWidthRecord area = codec.wrap(MenuOptions.encode(ASCII), MenuOptions.GROUP_LAYOUT);
            FieldSpan slotThreeName = MenuOptions.entryFieldSpans(3).get(MenuOptions.NAME_SUBFIELD);

            codec.writePicX(area, slotThreeName, toNameWidth("Overwritten"));

            int offsetWithinDataView = slotThreeName.offset() - MenuOptions.TABLE_OFFSET;
            assertThat(offsetWithinDataView).isEqualTo(2 * MenuOptions.ENTRY_LENGTH
                    + MenuOptions.OPT_NAME_OFFSET);
            assertThat(area.readSpan(MenuOptions.populatedDataSpan())
                    .substring(offsetWithinDataView, offsetWithinDataView + MenuOptions.OPT_NAME_LENGTH))
                    .isEqualTo(toNameWidth("Overwritten"));

            area.writeString(slotThreeName.offset(), MenuOptions.OPT_NAME_LENGTH, OPTION_3_NAME);
            assertThat(codec.readPicX(area, slotThreeName)).isEqualTo(OPTION_3_NAME);
            assertThat(MenuOptions.decode(area.toByteArray(), ASCII))
                    .as("and the whole table is back to its declared state")
                    .isEqualTo(MenuOptions.options());
        }

        @ParameterizedTest
        @DisplayName("slots 11 and 12 are addressable through the table view the -DATA view never reached")
        @ValueSource(ints = {11, 12})
        void theTailSlotsAreAddressableThroughTheTableView(int subscript) {
            FieldSpan element = MenuOptions.entrySpan(subscript);
            List<FieldSpan> spans = MenuOptions.entryFieldSpans(subscript);

            assertThat(element.offset())
                    .as("the tail lies entirely past the 460-byte literal-storage view")
                    .isGreaterThanOrEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.POPULATED_DATA_LENGTH);
            assertThat(element.length()).isEqualTo(MenuOptions.ENTRY_LENGTH);
            assertThat(spans).extracting(FieldSpan::length)
                    .containsExactly(MenuOptions.OPT_NUM_LENGTH, MenuOptions.OPT_NAME_LENGTH,
                            MenuOptions.OPT_PGMNAME_LENGTH, MenuOptions.OPT_USRTYPE_LENGTH)
                    .containsExactly(2, 35, 8, 1);
            assertThat(spans).allSatisfy(span -> assertThat(span.hasInitialValue())
                    .as(span.describe() + " carries no copybook VALUE")
                    .isFalse());
        }
    }

    @Nested
    @DisplayName("The byte image - offsets, the caller's code page, and what is not claimed")
    class ByteImage {
        @Test
        @DisplayName("encode() and declaredImage() agree, reached by two independent paths")
        void encodeAndDeclaredImageAgree() {
            assertThat(MenuOptions.encode(ASCII))
                    .isEqualTo(MenuOptions.declaredImage(ASCII))
                    .hasSize(MenuOptions.GROUP_LENGTH)
                    .hasSize(554);
        }

        @Test
        @DisplayName("CDEMO-MENU-OPT-COUNT leads the image with '10' at offset 0")
        void theCountLeadsTheImage() {
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            assertThat(image).hasSize(554).startsWith("10");
            assertThat(image.substring(MenuOptions.MENU_OPT_COUNT_OFFSET,
                    MenuOptions.MENU_OPT_COUNT_OFFSET + MenuOptions.MENU_OPT_COUNT_LENGTH))
                    .isEqualTo(MenuOptions.menuOptCountImage())
                    .isEqualTo("10");
            assertThat(image.substring(MenuOptions.TABLE_OFFSET,
                    MenuOptions.TABLE_OFFSET + MenuOptions.OPT_NUM_LENGTH))
                    .as("and the option area begins immediately after it, with slot 1's number")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("the ten valued entries land at their declared offsets, field by field")
        void theValuedEntriesLandAtTheirDeclaredOffsets() {
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            for (int subscript = 1; subscript <= MenuOptions.ACTIVE_OPTION_COUNT; subscript++) {
                int base = MenuOptions.TABLE_OFFSET
                        + (subscript - MenuOptions.FIRST_SUBSCRIPT) * MenuOptions.ENTRY_LENGTH;
                MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

                assertThat(image.substring(base + MenuOptions.OPT_NUM_OFFSET,
                        base + MenuOptions.OPT_NUM_OFFSET + MenuOptions.OPT_NUM_LENGTH))
                        .isEqualTo(option.menuOptNumImage());
                assertThat(image.substring(base + MenuOptions.OPT_NAME_OFFSET,
                        base + MenuOptions.OPT_NAME_OFFSET + MenuOptions.OPT_NAME_LENGTH))
                        .isEqualTo(option.menuOptName());
                assertThat(image.substring(base + MenuOptions.OPT_PGMNAME_OFFSET,
                        base + MenuOptions.OPT_PGMNAME_OFFSET + MenuOptions.OPT_PGMNAME_LENGTH))
                        .isEqualTo(option.menuOptPgmName());
                assertThat(image.substring(base + MenuOptions.OPT_USRTYPE_OFFSET,
                        base + MenuOptions.OPT_USRTYPE_OFFSET + MenuOptions.OPT_USRTYPE_LENGTH))
                        .isEqualTo(option.menuOptUsrType());
            }
        }

        @Test
        @DisplayName("the code page is the CALLER'S: the same table encodes differently under IBM037")
        void theCodePageIsTheCallers() {
            byte[] ascii = MenuOptions.encode(ASCII);
            byte[] ebcdic = MenuOptions.encode(EBCDIC);

            assertThat(ebcdic).hasSize(MenuOptions.GROUP_LENGTH).isNotEqualTo(ascii);
            assertThat(MenuOptions.decode(ebcdic, EBCDIC))
                    .as("and each round-trips within its own code page")
                    .isEqualTo(MenuOptions.options());
            assertThat(MenuOptions.declaredImage(EBCDIC)).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("every byte accessor refuses a null code page rather than choosing one")
        void everyByteAccessorRefusesANullCodePage() {
            assertThatNullPointerException().isThrownBy(() -> MenuOptions.encode(null));
            assertThatNullPointerException().isThrownBy(() -> MenuOptions.declaredImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.decode(MenuOptions.encode(ASCII), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> MenuOptions.fieldImages(MenuOptions.encode(ASCII), null));
        }

        @Test
        @DisplayName("decode rejects an image that is not exactly the declared width")
        void decodeRejectsAnImageOfTheWrongWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.decode(new byte[MenuOptions.GROUP_LENGTH - 1], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.decode(new byte[MenuOptions.GROUP_LENGTH + 1], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOptions.fieldImages(new byte[0], ASCII));
        }

        @Test
        @DisplayName("decode of this module's own image returns ten entries and two empties")
        void decodeOfOwnImageReturnsTenEntriesAndTwoEmpties() {
            List<Optional<MenuOption>> decoded = MenuOptions.decode(MenuOptions.encode(ASCII), ASCII);

            assertThat(decoded).hasSize(MenuOptions.TABLE_SIZE).isEqualTo(MenuOptions.options());
            assertThat(decoded.stream().filter(Optional::isPresent).count())
                    .isEqualTo(MenuOptions.ACTIVE_OPTION_COUNT);
        }
    }

    @Nested
    @DisplayName("MenuOption - the entry type, and the PICTURE rules it enforces")
    class EntryType {
        @Test
        @DisplayName("it pads a short literal to its declared width and refuses an over-long one")
        void itPadsShortAndRefusesLong() {
            MenuOption option = MenuOption.of(3, "Short", "PGM12345", USER_TYPE_USER);

            assertThat(option.menuOptName())
                    .isEqualTo("Short" + " ".repeat(30))
                    .hasSize(MenuOptions.OPT_NAME_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(3, "x".repeat(36), "PGM12345", USER_TYPE_USER))
                    .withMessageContaining("PIC X(35)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(3, "Name", "PGM123456", USER_TYPE_USER))
                    .withMessageContaining("PIC X(8)");
        }

        @Test
        @DisplayName("it bounds the option number to PIC 9(02), refusing rather than truncating")
        void itBoundsTheOptionNumber() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(-1, "Name", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll("negative", "unsigned", "no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(100, "Name", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll("needs 3 digit(s)", "PIC 9(2)");
        }

        @Test
        @DisplayName("the canonical constructor bounds the number itself, not only the factory")
        void theCanonicalConstructorBoundsTheNumber() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(-1, "01", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(100, "00", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
        }

        @Test
        @DisplayName("the canonical constructor rejects an image that disagrees with the number")
        void theCanonicalConstructorRejectsDisagreeingViews() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "04", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContainingAll("decodes to 4", "numeric form is 3");
            assertThatIllegalArgumentException()
                    .as("a character ABOVE '9'")
                    .isThrownBy(() -> new MenuOption(3, "0x", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .as("and one BELOW '0' - a space is the byte a group MOVE most often leaves here")
                    .isThrownBy(() -> new MenuOption(3, "0 ", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "3", toNameWidth(""), "PGM12345",
                            USER_TYPE_USER))
                    .withMessageContaining("declares exactly 2");
        }

        @Test
        @DisplayName("the canonical constructor enforces the exact width of every character column")
        void theCanonicalConstructorEnforcesExactWidths() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", "Too short", "PGM12345", USER_TYPE_USER))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_NAME, "declares exactly 35");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", toNameWidth(""), "PGM", USER_TYPE_USER))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_PGMNAME, "declares exactly 8");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(1, "01", toNameWidth(""), "PGM12345", "UU"))
                    .withMessageContainingAll(MenuOptions.CDEMO_MENU_OPT_USRTYPE, "declares exactly 1");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MenuOption(1, "01", null, "PGM12345", USER_TYPE_USER));
        }

        @Test
        @DisplayName("the range bound is 0 to 99, and 0 makes no claim about unvalued storage")
        void theRangeBoundIsZeroToNinetyNine() {
            assertThat(MenuOptions.MIN_OPT_NUM).isZero();
            assertThat(MenuOptions.MAX_OPT_NUM).isEqualTo(99);
            assertThat(MenuOption.of(0, "Name", "PGM12345", USER_TYPE_USER).menuOptNumImage())
                    .isEqualTo("00");
            assertThat(MenuOption.of(99, "Name", "PGM12345", USER_TYPE_USER).menuOptNumImage())
                    .isEqualTo("99");
            assertThat(MenuOptions.options().subList(MenuOptions.ACTIVE_OPTION_COUNT,
                    MenuOptions.TABLE_SIZE))
                    .as("the tail is absent, not an entry numbered MIN_OPT_NUM")
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }
    }
}
