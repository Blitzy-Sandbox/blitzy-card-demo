package com.vsergeychik.carddemo.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Tests for {@link AdminMenuOptions}, the {@code CARDDEMO-ADMIN-MENU-OPTIONS} table of
 * {@code app/cpy/COADM02Y.cpy}.
 *
 * <h2>The same size asymmetry as COMEN02Y, and the same correction</h2>
 * {@code CDEMO-ADMIN-OPTIONS-DATA} spells out four entries with {@code VALUE} clauses - 4 x 45 = 180
 * bytes - and {@code CDEMO-ADMIN-OPTIONS REDEFINES} it with {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES} -
 * 9 x 45 = 405 bytes. Slots 5 through 9 lie <strong>225 bytes past the end of the group being
 * redefined</strong>, so no {@code VALUE} clause reaches them and nothing determines their content.
 *
 * <p>The assertions below hold this class to reporting those five slots as <strong>absent</strong>
 * rather than as entries carrying option number 0 and blank names, and - the case that matters most in
 * practice - they prove a decode still succeeds when those bytes are not even well formed.
 *
 * <p>Every expected literal is transcribed from the copybook rather than read back off the class, so a
 * drift in either direction is caught: option 1 is {@code 'User List (Security)'} paired with
 * {@code COUSR00C}, through option 4 {@code 'User Delete (Security)'} paired with {@code COUSR03C}.
 */
@DisplayName("AdminMenuOptions - CARDDEMO-ADMIN-MENU-OPTIONS of COADM02Y")
class AdminMenuOptionsTest {

    /** The code page of the ASCII fixtures, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, used to prove the encoding is the caller's choice. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The copybook's own {@code PIC X(35)} literal for option 1, at {@code COADM02Y.cpy:25-26}. */
    private static final String OPTION_1 = "User List (Security)               ";

    /** The copybook's own {@code PIC X(35)} literal for option 4, at {@code COADM02Y.cpy:40-41}. */
    private static final String OPTION_4 = "User Delete (Security)             ";

    @Nested
    @DisplayName("Geometry, transcribed from the copybook rather than read off the class")
    class Geometry {

        @Test
        @DisplayName("an entry is 45 bytes and the group is 407")
        void anEntryIsFortyFiveBytes() {
            assertThat(AdminMenuOptions.OPT_NUM_LENGTH).isEqualTo(2);
            assertThat(AdminMenuOptions.OPT_NAME_LENGTH).isEqualTo(35);
            assertThat(AdminMenuOptions.OPT_PGMNAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuOptions.ENTRY_LENGTH).isEqualTo(45);
            assertThat(AdminMenuOptions.TABLE_SIZE).isEqualTo(9);
            assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(4);
            assertThat(AdminMenuOptions.POPULATED_DATA_LENGTH).isEqualTo(180);
            assertThat(AdminMenuOptions.TABLE_LENGTH).isEqualTo(405);
            assertThat(AdminMenuOptions.GROUP_LENGTH).isEqualTo(407);
            assertThat(AdminMenuOptions.OPTIONS_OFFSET).isEqualTo(2);
        }

        @Test
        @DisplayName("the OCCURS overlay is 225 bytes WIDER than the group it redefines")
        void theOverlayIsWiderThanTheGroupItRedefines() {
            assertThat(AdminMenuOptions.TABLE_LENGTH - AdminMenuOptions.POPULATED_DATA_LENGTH)
                    .isEqualTo(225);
            assertThat(AdminMenuOptions.unspecifiedTailSpan().length()).isEqualTo(225);
            assertThat(AdminMenuOptions.unspecifiedTailSpan().offset())
                    .isEqualTo(AdminMenuOptions.OPTIONS_OFFSET
                            + AdminMenuOptions.POPULATED_DATA_LENGTH);
            assertThat(AdminMenuOptions.unspecifiedTailSpan().name())
                    .contains(AdminMenuOptions.UNSPECIFIED_TAIL_SUFFIX);
        }

        @Test
        @DisplayName("the layout's own self-check runs, and the count span carries VALUE 4")
        void theLayoutSelfChecks() {
            assertThat(AdminMenuOptions.GROUP_LAYOUT.recordLength()).isEqualTo(407);
            assertThat(AdminMenuOptions.adminOptCountImage()).isEqualTo("04");
            assertThat(AdminMenuOptions.SPECIFIED_OPTION_COUNT_PLUS_ONE).isEqualTo(5);
            assertThat(AdminMenuOptions.MAX_OPT_NUM).isEqualTo(99);
        }

        @Test
        @DisplayName("the class is a constant table and refuses to be instantiated")
        void theClassRefusesInstantiation() throws Exception {
            var constructor = AdminMenuOptions.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("The four slots the copybook values")
    class SpecifiedSlots {

        @ParameterizedTest(name = "option {0} is {1} / {2}")
        @DisplayName("each carries its transcribed literal at its full declared width")
        @CsvSource({
            "1, User List (Security),   COUSR00C",
            "2, User Add (Security),    COUSR01C",
            "3, User Update (Security), COUSR02C",
            "4, User Delete (Security), COUSR03C",
        })
        void eachCarriesItsLiteral(int subscript, String name, String programName) {
            AdminMenuOption option = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();

            assertThat(option.adminOptNum()).isEqualTo(subscript);
            assertThat(option.adminOptNumImage()).isEqualTo("0" + subscript);
            assertThat(option.adminOptName())
                    .as("PIC X(35) is held at its full declared width, right-space-padded")
                    .hasSize(35)
                    .isEqualTo(name + " ".repeat(35 - name.length()));
            assertThat(option.adminOptPgmName()).hasSize(8).isEqualTo(programName);
        }

        @Test
        @DisplayName("the first and last literals match the copybook byte for byte")
        void theFirstAndLastLiteralsMatchTheCopybook() {
            assertThat(AdminMenuOptions.optionBySubscript(1).orElseThrow().adminOptName())
                    .isEqualTo(OPTION_1);
            assertThat(AdminMenuOptions.optionBySubscript(4).orElseThrow().adminOptName())
                    .isEqualTo(OPTION_4);
        }

        @Test
        @DisplayName("activeOptions() is the four of them, with no absent slot to consider")
        void activeOptionsIsTheFourOfThem() {
            List<AdminMenuOption> active = AdminMenuOptions.activeOptions();

            assertThat(active).hasSize(4);
            assertThat(active.get(0).adminOptPgmName()).isEqualTo("COUSR00C");
            assertThat(active.get(3).adminOptPgmName()).isEqualTo("COUSR03C");
        }

        @ParameterizedTest
        @DisplayName("isSpecified() is true for each of them")
        @ValueSource(ints = {1, 2, 3, 4})
        void isSpecifiedIsTrue(int subscript) {
            assertThat(AdminMenuOptions.isSpecified(subscript)).isTrue();
            assertThat(AdminMenuOptions.optionBySubscript(subscript)).isPresent();
        }

        @Test
        @DisplayName("CDEMO-ADMIN-OPT-COUNT is the copybook's literal 4, not the table's size 9")
        void theCountIsTheLiteralNotTheTableSize() {
            // Conflating the two is the defect this class exists to prevent: COADM01C loops to the count
            // while the OCCURS area is more than twice that long.
            assertThat(AdminMenuOptions.adminOptCountImage()).isEqualTo("04");
            assertThat(AdminMenuOptions.activeOptions()).hasSize(4);
            assertThat(AdminMenuOptions.options()).hasSize(9);
        }
    }

    @Nested
    @DisplayName("The five slots the copybook does NOT value - absent, never fabricated")
    class UnspecifiedTail {

        @ParameterizedTest
        @DisplayName("slots 5 through 9 are addressable and EMPTY")
        @ValueSource(ints = {5, 6, 7, 8, 9})
        void slotsFiveThroughNineAreEmpty(int subscript) {
            assertThat(AdminMenuOptions.isSpecified(subscript)).isFalse();
            assertThat(AdminMenuOptions.optionBySubscript(subscript)).isEmpty();
        }

        @Test
        @DisplayName("the table is still nine slots wide: they are absent, not trimmed away")
        void theTableIsStillNineSlotsWide() {
            List<Optional<AdminMenuOption>> slots = AdminMenuOptions.options();

            assertThat(slots).hasSize(9);
            assertThat(slots.subList(0, 4)).allSatisfy(slot -> assertThat(slot).isPresent());
            assertThat(slots.subList(4, 9)).allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("a subscript outside the table is rejected, never clamped")
        void anOutOfRangeSubscriptIsRejected() {
            // COADM01C's WS-OPTION is PIC 9(02), so a subscript up to 99 can reach this access after a
            // failed numeric validation. Substituting slot 1 or slot 9 would hide the very condition the
            // consuming controller has to report.
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.optionBySubscript(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.optionBySubscript(10));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.optionBySubscript(99));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.isSpecified(10));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuOptions.zeroBasedIndexFor(0));
        }

        @Test
        @DisplayName("a name cannot be subscripted for a slot the table does not have")
        void aNameCannotBeSubscriptedOutOfRange() {
            assertThat(AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, 9))
                    .isEqualTo("CDEMO-ADMIN-OPT-NUM(9)");
            assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                    () -> AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, 10));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.subscriptedName(null, 1));
        }
    }

    @Nested
    @DisplayName("Images - what this module writes, and what it refuses to claim")
    class Images {

        @Test
        @DisplayName("encode() lands the count and the four valued entries at their offsets")
        void encodeLandsTheValuedEntries() {
            String image = new String(AdminMenuOptions.encode(ASCII), ASCII);

            assertThat(image).hasSize(407).startsWith("04");
            for (int subscript = 1; subscript <= 4; subscript++) {
                int base = AdminMenuOptions.OPTIONS_OFFSET
                        + (subscript - 1) * AdminMenuOptions.ENTRY_LENGTH;
                AdminMenuOption option = AdminMenuOptions.optionBySubscript(subscript).orElseThrow();
                assertThat(image.substring(base, base + 2)).isEqualTo(option.adminOptNumImage());
                assertThat(image.substring(base + 2, base + 37)).isEqualTo(option.adminOptName());
                assertThat(image.substring(base + 37, base + 45)).isEqualTo(option.adminOptPgmName());
            }
        }

        @Test
        @DisplayName("the two REDEFINES views address the same bytes")
        void theTwoRedefinesViewsAddressTheSameBytes() {
            byte[] group = AdminMenuOptions.encode(ASCII);

            String dataView = AdminMenuOptions.adminOptionsDataImage(group, ASCII);
            String tableView = AdminMenuOptions.adminOptionsImage(group, ASCII);

            assertThat(dataView).hasSize(180);
            assertThat(tableView).hasSize(405).startsWith(dataView);
        }

        @Test
        @DisplayName("the code page is the caller's: the same table encodes differently under IBM037")
        void theCodePageIsTheCallers() {
            assertThat(AdminMenuOptions.encode(EBCDIC)).hasSize(407)
                    .isNotEqualTo(AdminMenuOptions.encode(ASCII));
        }

        @Test
        @DisplayName("decode of this module's own image returns four entries and five empties")
        void decodeOfOurOwnImageReturnsFourAndFiveEmpties() {
            List<Optional<AdminMenuOption>> decoded =
                    AdminMenuOptions.decode(AdminMenuOptions.encode(ASCII), ASCII);

            assertThat(decoded).hasSize(9).isEqualTo(AdminMenuOptions.options());
        }

        @Test
        @DisplayName("decode reports the TAIL as absent even when its bytes would parse")
        void decodeReportsTheTailAsAbsentEvenWhenParseable() {
            // The assertion that keeps the fabrication out. The layout pads slot 5's number span with
            // "00", which WOULD parse as an entry - and parsing it would put the invented value straight
            // back into the table.
            byte[] group = AdminMenuOptions.encode(ASCII);
            int tailStart = AdminMenuOptions.unspecifiedTailSpan().offset();
            assertThat(new String(group, ASCII).substring(tailStart, tailStart + 2))
                    .as("slot 5's number span really does hold parseable digits")
                    .isEqualTo("00");

            assertThat(AdminMenuOptions.decode(group, ASCII).get(4)).isEmpty();
        }

        @Test
        @DisplayName("decode survives a tail of GARBAGE, which is the case that used to be undecodable")
        void decodeSurvivesAGarbageTail() {
            // Real storage may hold anything in those 225 bytes. Decoding them as entries would put them
            // through readPic9AsInt, so one non-digit there would fail the decode of a perfectly valid
            // group image and the four valued entries would be lost with it.
            byte[] group = AdminMenuOptions.encode(ASCII);
            int tailStart = AdminMenuOptions.unspecifiedTailSpan().offset();
            for (int offset = tailStart; offset < group.length; offset++) {
                group[offset] = (byte) '?';
            }

            List<Optional<AdminMenuOption>> decoded = AdminMenuOptions.decode(group, ASCII);

            assertThat(decoded).hasSize(9);
            assertThat(decoded.subList(0, 4)).isEqualTo(AdminMenuOptions.options().subList(0, 4));
            assertThat(decoded.subList(4, 9)).allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("the garbage tail is still readable as BYTES, which is where it belongs")
        void theGarbageTailIsReadableAsBytes() {
            byte[] group = AdminMenuOptions.encode(ASCII);
            int tailStart = AdminMenuOptions.unspecifiedTailSpan().offset();
            for (int offset = tailStart; offset < group.length; offset++) {
                group[offset] = (byte) '?';
            }

            Map<String, String> images = AdminMenuOptions.fieldImages(group, ASCII);

            assertThat(images.get(
                    AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NAME_FIELD, 5)))
                    .isEqualTo("?".repeat(35));
            assertThat(images.get(
                    AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NUM_FIELD, 9)))
                    .isEqualTo("??");
            assertThat(images.get(
                    AdminMenuOptions.subscriptedName(AdminMenuOptions.ADMIN_OPT_NAME_FIELD, 1)))
                    .as("a garbage tail cannot disturb a valued entry")
                    .isEqualTo(OPTION_1);
        }

        @Test
        @DisplayName("a decode of a SPECIFIED slot's garbage is still an error, because it is claimed")
        void garbageInASpecifiedSlotIsStillAnError() {
            // The tolerance is confined to the tail. Bytes the copybook DOES value are claimed, so a
            // non-digit in option 1's number is a genuine data or offset defect and must be reported.
            byte[] group = AdminMenuOptions.encode(ASCII);
            group[AdminMenuOptions.OPTIONS_OFFSET] = (byte) '?';

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.decode(group, ASCII));
        }
    }

    @Nested
    @DisplayName("encodeSlots - the caller-supplied table")
    class EncodeSlots {

        @Test
        @DisplayName("it round-trips a caller's table through decode")
        void itRoundTripsACallersTable() {
            List<Optional<AdminMenuOption>> slots = new ArrayList<>(AdminMenuOptions.options());
            slots.set(0, Optional.of(AdminMenuOption.of(7, "Replaced", "COXXXXXC")));

            List<Optional<AdminMenuOption>> decoded =
                    AdminMenuOptions.decode(AdminMenuOptions.encodeSlots(slots, ASCII), ASCII);

            assertThat(decoded.get(0).orElseThrow().adminOptNum()).isEqualTo(7);
            assertThat(decoded.get(0).orElseThrow().adminOptName())
                    .isEqualTo("Replaced" + " ".repeat(27));
            assertThat(decoded.subList(4, 9)).allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("the fixed-size area requires exactly nine slots, absent ones included")
        void itRequiresExactlyNineSlots() {
            List<Optional<AdminMenuOption>> tooFew =
                    List.copyOf(AdminMenuOptions.options().subList(0, 4));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(tooFew, ASCII))
                    .withMessageContaining("OCCURS 9 TIMES");
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(null, ASCII));
        }

        @Test
        @DisplayName("a null element is refused: an absent slot is Optional.empty(), not null")
        void aNullElementIsRefused() {
            List<Optional<AdminMenuOption>> withNull =
                    new ArrayList<>(AdminMenuOptions.options());
            withNull.set(8, null);

            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOptions.encodeSlots(withNull, ASCII))
                    .withMessageContaining("Optional.empty()");
        }

        @Test
        @DisplayName("the List<AdminMenuOption> overload reads a null element as an absent slot")
        void theNullTolerantOverloadReadsNullAsAbsent() {
            List<AdminMenuOption> nullTailed = new ArrayList<>();
            for (int subscript = 1; subscript <= AdminMenuOptions.TABLE_SIZE; subscript++) {
                nullTailed.add(AdminMenuOptions.optionBySubscript(subscript).orElse(null));
            }

            assertThat(AdminMenuOptions.encode(nullTailed, ASCII))
                    .isEqualTo(AdminMenuOptions.encode(ASCII));
        }
    }

    @Nested
    @DisplayName("AdminMenuOption - the entry type itself")
    class Entry {

        @Test
        @DisplayName("it pads a short literal and truncates an over-wide one on the RIGHT")
        void itPadsShortAndTruncatesRight() {
            assertThat(AdminMenuOption.of(3, "Short", "PGM").adminOptName())
                    .isEqualTo("Short" + " ".repeat(30));
            assertThat(AdminMenuOption.of(3, "x".repeat(40), "PGM12345").adminOptName())
                    .as("PIC X truncates on the right")
                    .isEqualTo("x".repeat(35));
        }

        @ParameterizedTest
        @DisplayName("it bounds the option number to the PIC 9(02) range")
        @ValueSource(ints = {-1, 100})
        void itBoundsTheOptionNumber(int outOfRange) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AdminMenuOption.of(outOfRange, "Name", "PGM12345"))
                    .withMessageContaining("PIC 9(02)");
        }

        @Test
        @DisplayName("the canonical constructor requires full declared widths")
        void theCanonicalConstructorRequiresFullWidths() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOption(1, "short", "COUSR00C"))
                    .withMessageContaining("PIC X(35)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOption(1, " ".repeat(35), "short"))
                    .withMessageContaining("PIC X(08)");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuOption(1, null, "COUSR00C"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuOption(1, " ".repeat(35), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOption.of(1, null, "COUSR00C"));
            assertThatNullPointerException()
                    .isThrownBy(() -> AdminMenuOption.of(1, "Name", null));
        }

        @Test
        @DisplayName("the number image keeps its leading zero, which an int alone cannot")
        void theNumberImageKeepsItsLeadingZero() {
            // COADM01C:233 moves this image with STRING ... DELIMITED BY SIZE, so the leading zero is
            // part of the observable output.
            assertThat(AdminMenuOption.of(1, "Name", "PGM12345").adminOptNumImage()).isEqualTo("01");
            assertThat(AdminMenuOption.of(99, "Name", "PGM12345").adminOptNumImage()).isEqualTo("99");
            assertThat(AdminMenuOption.of(0, "Name", "PGM12345").adminOptNumImage()).isEqualTo("00");
        }
    }
}
