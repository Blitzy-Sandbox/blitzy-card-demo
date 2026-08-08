package com.vsergeychik.carddemo.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MenuOptions}, the {@code CARDDEMO-MAIN-MENU-OPTIONS} table of
 * {@code app/cpy/COMEN02Y.cpy}.
 *
 * <h2>What the copybook actually declares, and where the trap is</h2>
 * {@code CDEMO-MENU-OPTIONS-DATA} spells out ten entries with {@code VALUE} clauses - 10 x 46 = 460
 * bytes. {@code CDEMO-MENU-OPTIONS REDEFINES} it with {@code CDEMO-MENU-OPT OCCURS 12 TIMES} - 12 x 46 =
 * 552 bytes. The overlay is <strong>92 bytes wider than the group it redefines</strong>, so slots 11 and
 * 12 reach past the end of that group entirely and no {@code VALUE} clause applies to them.
 *
 * <p>The trap is to describe those 92 bytes as "a zero-filled {@code PIC 9(02)} and space-filled
 * {@code PIC X} columns". IBM Enterprise COBOL initialises storage from applicable {@code VALUE}
 * clauses; there is no applicable clause here, so no particular content is documented. This class
 * therefore reports those slots as <strong>absent</strong>, and the assertions below exist to keep it
 * that way - including one that walks an image whose tail is deliberate garbage.
 *
 * <p>Two transcription facts are asserted rather than trusted, because both are easy to "fix" by
 * accident: option 7 pairs {@code Transaction View} with {@code COTRN01C} and option 8 pairs
 * {@code Transaction Add} with {@code COTRN02C} - the source of the documented naming inversion in this
 * migration; and option 8's name is the LIVE line only, never the commented-out
 * {@code 'Transaction Add (Admin Only)'} above it.
 */
@DisplayName("MenuOptions - CARDDEMO-MAIN-MENU-OPTIONS of COMEN02Y")
class MenuOptionsTest {

    /** The code page of the ASCII fixtures, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, used to prove the encoding is the caller's choice. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    @Nested
    @DisplayName("Geometry, transcribed from the copybook rather than read off the class")
    class Geometry {

        @Test
        @DisplayName("an entry is 46 bytes and the group is 554")
        void anEntryIsFortySixBytes() {
            assertThat(MenuOptions.OPT_NUM_LENGTH).isEqualTo(2);
            assertThat(MenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            assertThat(MenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(8);
            assertThat(MenuOptions.OPT_USRTYPE_LENGTH).isEqualTo(1);
            assertThat(MenuOptions.ENTRY_LENGTH).isEqualTo(46);
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(12);
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(460);
            assertThat(MenuOptions.TABLE_LENGTH).isEqualTo(552);
            assertThat(MenuOptions.GROUP_LENGTH).isEqualTo(554);
        }

        @Test
        @DisplayName("the OCCURS overlay is 92 bytes WIDER than the group it redefines")
        void theOverlayIsWiderThanTheGroupItRedefines() {
            assertThat(MenuOptions.TABLE_LENGTH - MenuOptions.POPULATED_DATA_LENGTH).isEqualTo(92);
            assertThat(MenuOptions.unspecifiedTailSpan().length()).isEqualTo(92);
            assertThat(MenuOptions.unspecifiedTailSpan().offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET + MenuOptions.POPULATED_DATA_LENGTH);
        }

        @Test
        @DisplayName("the layout's own self-check runs, and the count span carries VALUE 10")
        void theLayoutSelfChecks() {
            assertThat(MenuOptions.GROUP_LAYOUT.recordLength()).isEqualTo(554);
            assertThat(MenuOptions.menuOptCount()).isEqualTo(10);
            assertThat(MenuOptions.menuOptCountImage()).isEqualTo("10");
        }

        @Test
        @DisplayName("the table size and the active count are two DISTINCT facts")
        void theTableSizeAndActiveCountAreDistinct() {
            // The single assertion that catches the most likely defect in this table: VALUE 10 is the
            // active count and OCCURS 12 is the table size. Conflating them either trims two real slots
            // away or loops COMEN01C past the entries that exist.
            assertThat(MenuOptions.TABLE_SIZE).isNotEqualTo(MenuOptions.ACTIVE_OPTION_COUNT);
        }

        @Test
        @DisplayName("the two REDEFINES views start at the same offset over one backing span")
        void theTwoRedefinesViewsShareOneSpan() {
            assertThat(MenuOptions.populatedDataSpan().offset())
                    .isEqualTo(MenuOptions.tableSpan().offset())
                    .isEqualTo(MenuOptions.TABLE_OFFSET);
            assertThat(MenuOptions.populatedDataSpan().length()).isEqualTo(460);
            assertThat(MenuOptions.tableSpan().length()).isEqualTo(552);
        }

        @Test
        @DisplayName("nothing mutable escapes: the published collections refuse modification")
        void nothingMutableEscapes() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.options().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.activeOptions().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.fieldSpans().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> MenuOptions.entryFieldSpans(1).clear());
        }

        @Test
        @DisplayName("encode() hands back a fresh array each time, so no caller can alter the table")
        void encodeHandsBackAFreshArray() {
            byte[] first = MenuOptions.encode(ASCII);
            first[0] = (byte) '?';

            assertThat(MenuOptions.encode(ASCII)[0]).isEqualTo((byte) '1');
        }

        @Test
        @DisplayName("the class is a constant table and refuses to be instantiated")
        void theClassRefusesInstantiation() throws Exception {
            var constructor = MenuOptions.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("The ten slots the copybook values")
    class SpecifiedSlots {

        @ParameterizedTest(name = "option {0} is {1} / {2}")
        @DisplayName("each carries its transcribed literal at its full declared width")
        @CsvSource({
            "1,  Account View,        COACTVWC",
            "2,  Account Update,      COACTUPC",
            "3,  Credit Card List,    COCRDLIC",
            "4,  Credit Card View,    COCRDSLC",
            "5,  Credit Card Update,  COCRDUPC",
            "6,  Transaction List,    COTRN00C",
            "7,  Transaction View,    COTRN01C",
            "8,  Transaction Add,     COTRN02C",
            "9,  Transaction Reports, CORPT00C",
            "10, Bill Payment,        COBIL00C",
        })
        void eachCarriesItsLiteral(int subscript, String name, String programName) {
            MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.menuOptNum()).isEqualTo(subscript);
            assertThat(option.menuOptNumImage()).hasSize(2)
                    .isEqualTo(subscript < 10 ? "0" + subscript : String.valueOf(subscript));
            assertThat(option.menuOptName()).isEqualTo(name + " ".repeat(35 - name.length()));
            assertThat(option.menuOptPgmName()).isEqualTo(programName);
            assertThat(option.menuOptUsrType())
                    .as("the LIVE user-type column of every entry is 'U', including option 8's")
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("option 7 views and option 8 adds - the inversion is transcribed, never corrected")
        void theViewAddInversionIsTranscribed() {
            // README.md:L213-231 documents CT01 as Transaction View and CT02 as Transaction Add, and the
            // copybook agrees. The prompt's class names invert it. The copybook is the oracle here.
            assertThat(MenuOptions.optionBySubscript(7).orElseThrow().menuOptName().strip())
                    .isEqualTo("Transaction View");
            assertThat(MenuOptions.optionBySubscript(7).orElseThrow().menuOptPgmName())
                    .isEqualTo("COTRN01C");
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptName().strip())
                    .isEqualTo("Transaction Add");
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptPgmName())
                    .isEqualTo("COTRN02C");
        }

        @Test
        @DisplayName("option 8 never carries the commented-out '(Admin Only)' wording")
        void optionEightNeverCarriesTheCommentedOutWording() {
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptName())
                    .doesNotContain("Admin Only");
        }

        @Test
        @DisplayName("activeOptions() is the ten of them, with no absent slot to consider")
        void activeOptionsIsTheTenOfThem() {
            List<MenuOption> active = MenuOptions.activeOptions();

            assertThat(active).hasSize(10);
            assertThat(active.get(0).menuOptPgmName()).isEqualTo("COACTVWC");
            assertThat(active.get(9).menuOptPgmName()).isEqualTo("COBIL00C");
        }

        @ParameterizedTest
        @DisplayName("isSpecified() is true for each of them")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void isSpecifiedIsTrue(int subscript) {
            assertThat(MenuOptions.isSpecified(subscript)).isTrue();
            assertThat(MenuOptions.optionBySubscript(subscript)).isPresent();
        }
    }

    @Nested
    @DisplayName("The two slots the copybook does NOT value - absent, never fabricated")
    class UnspecifiedTail {

        @ParameterizedTest
        @DisplayName("slots 11 and 12 are addressable and EMPTY")
        @ValueSource(ints = {11, 12})
        void slotsElevenAndTwelveAreEmpty(int subscript) {
            // The correction. These used to hand back an entry claiming option number 0 with blank text
            // - a value indistinguishable from data and derived from nothing the copybook says.
            assertThat(MenuOptions.isSpecified(subscript)).isFalse();
            assertThat(MenuOptions.optionBySubscript(subscript)).isEmpty();
        }

        @Test
        @DisplayName("the table is still twelve slots wide: they are absent, not trimmed away")
        void theTableIsStillTwelveSlotsWide() {
            List<Optional<MenuOption>> slots = MenuOptions.options();

            assertThat(slots).hasSize(12);
            assertThat(slots.subList(0, 10)).allSatisfy(slot -> assertThat(slot).isPresent());
            assertThat(slots.subList(10, 12)).allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("no accessor can be talked into reporting a value for them")
        void noAccessorReportsAValueForThem() {
            assertThatExceptionOfType(java.util.NoSuchElementException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(11).orElseThrow());
        }

        @Test
        @DisplayName("a subscript outside the table is rejected, never clamped")
        void anOutOfRangeSubscriptIsRejected() {
            // COMEN01C's WS-OPTION is PIC 9(02), so a subscript up to 99 can reach this access after a
            // failed numeric validation. Substituting slot 1 or slot 12 would hide exactly the condition
            // the consuming service has to handle.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(13));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.optionBySubscript(99));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> MenuOptions.isSpecified(13));
        }
    }

    @Nested
    @DisplayName("Images - what this module writes, and what it refuses to claim")
    class Images {

        @Test
        @DisplayName("encode() and declaredImage() agree, by two independent paths")
        void encodeAndDeclaredImageAgree() {
            assertThat(MenuOptions.encode(ASCII)).isEqualTo(MenuOptions.declaredImage(ASCII));
            assertThat(MenuOptions.encode(ASCII)).hasSize(554);
        }

        @Test
        @DisplayName("the ten valued entries land at their declared offsets")
        void theValuedEntriesLandAtTheirOffsets() {
            String image = new String(MenuOptions.encode(ASCII), ASCII);

            assertThat(image).startsWith("10");
            for (int subscript = 1; subscript <= 10; subscript++) {
                int base = MenuOptions.TABLE_OFFSET + (subscript - 1) * MenuOptions.ENTRY_LENGTH;
                MenuOption option = MenuOptions.optionBySubscript(subscript).orElseThrow();
                assertThat(image.substring(base, base + 2)).isEqualTo(option.menuOptNumImage());
                assertThat(image.substring(base + 2, base + 37)).isEqualTo(option.menuOptName());
                assertThat(image.substring(base + 37, base + 45)).isEqualTo(option.menuOptPgmName());
                assertThat(image.substring(base + 45, base + 46)).isEqualTo(option.menuOptUsrType());
            }
        }

        @Test
        @DisplayName("the code page is the caller's: the same table encodes differently under IBM037")
        void theCodePageIsTheCallers() {
            assertThat(MenuOptions.encode(EBCDIC)).hasSize(554)
                    .isNotEqualTo(MenuOptions.encode(ASCII));
        }

        @Test
        @DisplayName("decode of this module's own image returns ten entries and two empties")
        void decodeOfOurOwnImageReturnsTenAndTwoEmpties() {
            List<Optional<MenuOption>> decoded = MenuOptions.decode(MenuOptions.encode(ASCII), ASCII);

            assertThat(decoded).hasSize(12).isEqualTo(MenuOptions.options());
        }

        @Test
        @DisplayName("decode reports the TAIL as absent even when its bytes would parse")
        void decodeReportsTheTailAsAbsentEvenWhenParseable() {
            // This is the assertion that keeps the fabrication out. This module's own image pads the tail
            // with "00" and spaces, which WOULD parse as an entry - and parsing it would put the invented
            // value straight back. Nothing decoded there can be attributed to the copybook's table.
            byte[] image = MenuOptions.encode(ASCII);
            assertThat(new String(image, ASCII).substring(462, 464))
                    .as("the tail's first two bytes really are parseable digits")
                    .isEqualTo("00");

            assertThat(MenuOptions.decode(image, ASCII).get(10)).isEmpty();
            assertThat(MenuOptions.decode(image, ASCII).get(11)).isEmpty();
        }

        @Test
        @DisplayName("decode survives a tail of GARBAGE, which is the case that used to be undecodable")
        void decodeSurvivesAGarbageTail() {
            // Real storage may hold anything in those 92 bytes. Decoding them as an entry would put them
            // through decodePic9AsInt, so a non-digit there would fail the decode of a perfectly valid
            // group image - and the ten valued entries would be lost with it.
            byte[] image = MenuOptions.encode(ASCII);
            int tailStart = MenuOptions.unspecifiedTailSpan().offset();
            for (int offset = tailStart; offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            List<Optional<MenuOption>> decoded = MenuOptions.decode(image, ASCII);

            assertThat(decoded).hasSize(12);
            assertThat(decoded.subList(0, 10)).isEqualTo(MenuOptions.options().subList(0, 10));
            assertThat(decoded.get(10)).isEmpty();
            assertThat(decoded.get(11)).isEmpty();
        }

        @Test
        @DisplayName("the garbage tail is still readable as BYTES, which is where it belongs")
        void theGarbageTailIsReadableAsBytes() {
            byte[] image = MenuOptions.encode(ASCII);
            int tailStart = MenuOptions.unspecifiedTailSpan().offset();
            for (int offset = tailStart; offset < image.length; offset++) {
                image[offset] = (byte) '?';
            }

            Map<String, String> images = MenuOptions.fieldImages(image, ASCII);

            assertThat(images).hasSize(49);
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 11)))
                    .isEqualTo("?".repeat(35));
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NUM, 12)))
                    .isEqualTo("??");
            assertThat(images.get(MenuOptions.subscriptedName(MenuOptions.CDEMO_MENU_OPT_NAME, 1)))
                    .as("a garbage tail cannot disturb a valued entry")
                    .startsWith("Account View");
        }

        @Test
        @DisplayName("a decode of a SPECIFIED slot's garbage is still an error, because it is claimed")
        void garbageInASpecifiedSlotIsStillAnError() {
            // The tolerance is confined to the tail. Bytes the copybook DOES value are claimed, so a
            // non-digit in option 1's number is a genuine data or offset defect and must be reported.
            byte[] image = MenuOptions.encode(ASCII);
            image[MenuOptions.TABLE_OFFSET] = (byte) '?';

            assertThatIllegalArgumentException().isThrownBy(() -> MenuOptions.decode(image, ASCII));
        }
    }

    @Nested
    @DisplayName("MenuOption - the entry type itself")
    class Entry {

        @Test
        @DisplayName("it pads a short literal and refuses an over-long one")
        void itPadsShortAndRefusesLong() {
            MenuOption option = MenuOption.of(3, "Short", "PGM12345", "U");

            assertThat(option.menuOptName()).isEqualTo("Short" + " ".repeat(30));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(3, "x".repeat(36), "PGM12345", "U"));
        }

        @Test
        @DisplayName("it bounds the option number to the PIC 9(02) range, refusing rather than truncating")
        void itBoundsTheOptionNumber() {
            // PIC 9(02) is unsigned, so it has no sign position at all, and it holds two digits. Every
            // caller of this factory is transcribing a copybook literal, so a value that does not fit is
            // a transcription error to report rather than one to left-truncate into range.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(-1, "Name", "PGM12345", "U"))
                    .withMessageContainingAll("negative", "unsigned", "no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuOption.of(100, "Name", "PGM12345", "U"))
                    .withMessageContainingAll("needs 3 digit(s)", "PIC 9(2)");
        }

        @Test
        @DisplayName("the canonical constructor bounds the number itself, not only the factory")
        void theCanonicalConstructorBoundsTheNumber() {
            // The factory refuses out-of-range input while forming the image, so the constructor's own
            // guard is only reachable directly. It still has to hold: decode() and any future caller
            // construct through it, and a PIC 9(02) that admitted 100 or -1 would encode to the wrong
            // width or to a sign position the field does not have.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(-1, "01", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(100, "00", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContainingAll("PIC 9(02)", "0 to 99");
        }

        @Test
        @DisplayName("the canonical constructor rejects an image that disagrees with the number")
        void theCanonicalConstructorRejectsDisagreeingViews() {
            // Two views of one PIC 9(02) field. Letting them drift would let the raw image a parity diff
            // compares say something different from the number the branch logic tests.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "04", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContainingAll("decodes to 4", "numeric form is 3");
            assertThatIllegalArgumentException()
                    .as("a character ABOVE '9'")
                    .isThrownBy(() -> new MenuOption(3, "0x", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .as("and one BELOW '0' - a space is the byte a group MOVE most often leaves here")
                    .isThrownBy(() -> new MenuOption(3, "0 ", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContaining("digits only");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MenuOption(3, "3", " ".repeat(35), "PGM12345", "U"))
                    .withMessageContaining("declares exactly 2");
        }

        @Test
        @DisplayName("the range bound is 0 to 99, and 0 makes no claim about unvalued storage")
        void theRangeBoundIsZeroToNinetyNine() {
            assertThat(MenuOptions.MIN_OPT_NUM).isZero();
            assertThat(MenuOptions.MAX_OPT_NUM).isEqualTo(99);
            assertThat(MenuOption.of(0, "Name", "PGM12345", "U").menuOptNumImage()).isEqualTo("00");
        }
    }
}
