package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.DateHeader.CapturedDateTime;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link DateHeader}, the Java form of {@code app/cpy/CSDAT01Y.cpy}'s
 * {@code 01 WS-DATE-TIME}.
 *
 * <h2>What this test is really measuring</h2>
 * Three things, in descending order of importance.
 *
 * <p><strong>1. That the clock is the only source of time.</strong> All 17 CICS online programs stamp
 * their screen with this header. If it read the wall clock, no parity case could assert an exact byte
 * image and the diff-count-equals-zero gate would be unreachable for every one of them.
 * {@link Determinism} proves the clock is genuinely the sole input: the same {@code Clock.fixed(...)}
 * produces character-identical renderings on repeated builds, and two different fixed clocks produce
 * different ones. A stray {@code now()} anywhere inside the class would break the first of those.
 *
 * <p><strong>2. That the separator {@code FILLER}s emit their declared {@code VALUE}s.</strong> This
 * copybook is the counter-example to "{@code FILLER} is spaces": every separator here is a
 * {@code FILLER PIC X(01)} carrying an explicit literal. Blanket space-filling would turn
 * {@code 12/25/24} into {@code 12 25 24} on every screen in the system while still passing every
 * length check, so {@link Separators} asserts the literals positionally, in the rendered strings and
 * again in the serialised bytes.
 *
 * <p><strong>3. That the widths are exactly as declared.</strong> 8, 8, 16, 8, 8, 26 and a 58-byte
 * total. Lengths are asserted as numbers rather than inferred from content patterns, because a
 * pattern match passes on a string of the wrong width.
 *
 * <h2>How the expectations were derived</h2>
 * The COBOL cannot be executed in this environment, so nothing here was captured from a running
 * program. Every expected value was derived by reading {@code app/cpy/CSDAT01Y.cpy} and the four
 * programs that populate or consume the group - {@code COMEN01C}, {@code COSGN00C}, {@code CBACT04C}
 * and, for the timestamp direction, {@code COTRN00C} and {@code COBIL00C} - and each expectation
 * carries a citation to the line it came from. Those sources are a read-only parity oracle: this test
 * opens no file, reads nothing under {@code app/} at run time, and writes nowhere.
 */
@DisplayName("DateHeader - app/cpy/CSDAT01Y.cpy 01 WS-DATE-TIME")
class DateHeaderTest {

    /**
     * The code page of the ASCII fixtures under {@code app/data/ASCII}. Named explicitly here for the
     * same reason the production code refuses to derive one: a fixed-width record is bytes in a
     * specific code page.
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the datasets under {@code app/data/EBCDIC}. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /**
     * The reference instant used throughout: 25 December 2024 at 13:45:07 and 89,123 microseconds.
     * Chosen because every component is distinguishable - a two-digit month and day, an afternoon
     * hour, and a fractional second whose hundredths ({@code 08}) and microseconds ({@code 089123})
     * are different values, so a test cannot accidentally pass by confusing the two precisions.
     */
    private static final LocalDateTime REFERENCE = LocalDateTime.of(2024, 12, 25, 13, 45, 7, 89_123_000);

    /** {@code WS-CURDATE-DATA} for {@link #REFERENCE}: {@code YYYYMMDD} then {@code HHMMSSss}. */
    private static final String REFERENCE_CURDATE_DATA = "2024122513450708";

    /** {@code WS-TIMESTAMP} for {@link #REFERENCE} - six fractional digits, not two. */
    private static final String REFERENCE_TIMESTAMP = "2024-12-25 13:45:07.089123";

    private static DateHeader referenceHeader() {
        return DateHeader.of(ASCII_CODEC, REFERENCE);
    }

    private static Clock fixedUtc(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Determinism - the injected Clock is the only source of time")
    class Determinism {

        @Test
        @DisplayName("the same fixed Clock yields character-identical renderings every time")
        void sameClockIsReproducible() {
            Clock clock = fixedUtc("2024-12-25T13:45:07.089123Z");

            DateHeader first = DateHeader.from(ASCII_CODEC, clock);
            DateHeader second = DateHeader.from(ASCII_CODEC, clock);

            assertThat(second.wsCurdate()).isEqualTo(first.wsCurdate());
            assertThat(second.wsCurtime()).isEqualTo(first.wsCurtime());
            assertThat(second.wsCurdateData()).isEqualTo(first.wsCurdateData());
            assertThat(second.wsCurdateMmDdYy()).isEqualTo(first.wsCurdateMmDdYy());
            assertThat(second.wsCurtimeHhMmSs()).isEqualTo(first.wsCurtimeHhMmSs());
            assertThat(second.wsTimestamp()).isEqualTo(first.wsTimestamp());
            assertThat(second.db2FormatTimestamp()).isEqualTo(first.db2FormatTimestamp());
            assertThat(second.functionCurrentDate()).isEqualTo(first.functionCurrentDate());
            assertThat(second.toBytes()).isEqualTo(first.toBytes());
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("two different fixed Clocks yield different renderings, so time is not ignored")
        void differentClocksDiffer() {
            DateHeader earlier = DateHeader.from(ASCII_CODEC, fixedUtc("2024-12-25T13:45:07Z"));
            DateHeader later = DateHeader.from(ASCII_CODEC, fixedUtc("2024-12-25T13:45:08Z"));

            assertThat(later.wsCurtime()).isNotEqualTo(earlier.wsCurtime());
            assertThat(later.wsCurtimeHhMmSs()).isNotEqualTo(earlier.wsCurtimeHhMmSs());
            assertThat(later.wsTimestamp()).isNotEqualTo(earlier.wsTimestamp());
            assertThat(later).isNotEqualTo(earlier);
        }

        @Test
        @DisplayName("a repeated build never straddles a second boundary: one capture, many renderings")
        void oneCaptureManyRenderings() {
            DateHeader header = DateHeader.from(ASCII_CODEC, fixedUtc("2024-12-25T13:45:07.089123Z"));

            // Every rendering must agree about the same instant. Reading a clock per accessor is
            // exactly the defect that would let these disagree, so they are cross-checked.
            assertThat(header.wsCurdateData()).startsWith(header.wsCurdate());
            assertThat(header.wsCurdateData()).endsWith(header.wsCurtime());
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("12/25/24");
            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("13:45:07");
            assertThat(header.wsTimestamp()).isEqualTo(REFERENCE_TIMESTAMP);
        }

        @Test
        @DisplayName("the Clock supplies the zone too, so the offset tail follows it")
        void zoneComesFromTheClock() {
            // 19:45:07Z is 13:45:07 in Chicago on 25 December, at CST = UTC-06:00.
            Clock chicago = Clock.fixed(Instant.parse("2024-12-25T19:45:07Z"),
                    ZoneId.of("America/Chicago"));

            DateHeader header = DateHeader.from(ASCII_CODEC, chicago);

            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("13:45:07");
            assertThat(header.gmtOffsetImage()).isEqualTo("-0600");
            assertThat(header.captured().gmtOffsetMinutes()).isEqualTo(-360);
        }

        @Test
        @DisplayName("a positive offset renders with a plus sign and a non-zero minute part")
        void positiveOffsetWithMinutes() {
            // +05:45 is Asia/Kathmandu, the offset that exercises a non-zero minute half.
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE,
                    ZoneOffset.ofHoursMinutes(5, 45));

            assertThat(header.gmtOffsetImage()).isEqualTo("+0545");
            assertThat(header.gmtOffsetImage()).hasSize(DateHeader.GMT_OFFSET_LENGTH);
        }

        @Test
        @DisplayName("an explicit LocalDateTime with no zone is taken as +0000")
        void bareLocalDateTimeHasNoOffset() {
            assertThat(referenceHeader().gmtOffsetImage()).isEqualTo("+0000");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Widths - asserted as exact lengths, never as content patterns")
    class Widths {

        @Test
        @DisplayName("the declared constants sum to the declared 58-byte total")
        void constantsSumToFiftyEight() {
            assertThat(DateHeader.WS_CURDATE_DATA_LENGTH).isEqualTo(16);
            assertThat(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH).isEqualTo(8);
            assertThat(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH).isEqualTo(8);
            assertThat(DateHeader.WS_TIMESTAMP_LENGTH).isEqualTo(26);
            assertThat(DateHeader.WS_CURDATE_DATA_LENGTH
                    + DateHeader.WS_CURDATE_MM_DD_YY_LENGTH
                    + DateHeader.WS_CURTIME_HH_MM_SS_LENGTH
                    + DateHeader.WS_TIMESTAMP_LENGTH)
                    .isEqualTo(DateHeader.WS_DATE_TIME_LENGTH)
                    .isEqualTo(58);
            // WS-CURDATE-DATA is WS-CURDATE then WS-CURTIME, and nothing else.
            assertThat(DateHeader.WS_CURDATE_LENGTH + DateHeader.WS_CURTIME_LENGTH)
                    .isEqualTo(DateHeader.WS_CURDATE_DATA_LENGTH);
            // The intrinsic is WS-CURDATE-DATA plus the offset it discards - CBACT04C's COBOL-TS.
            assertThat(DateHeader.WS_CURDATE_DATA_LENGTH + DateHeader.GMT_OFFSET_LENGTH)
                    .isEqualTo(DateHeader.FUNCTION_CURRENT_DATE_LENGTH)
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("every rendering is exactly its declared width")
        void renderingWidths() {
            DateHeader header = referenceHeader();

            assertThat(header.wsCurdate()).hasSize(DateHeader.WS_CURDATE_LENGTH).hasSize(8);
            assertThat(header.wsCurtime()).hasSize(DateHeader.WS_CURTIME_LENGTH).hasSize(8);
            assertThat(header.wsCurdateData()).hasSize(DateHeader.WS_CURDATE_DATA_LENGTH).hasSize(16);
            assertThat(header.wsCurdateMmDdYy())
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH).hasSize(8);
            assertThat(header.wsCurtimeHhMmSs())
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH).hasSize(8);
            assertThat(header.wsTimestamp()).hasSize(DateHeader.WS_TIMESTAMP_LENGTH).hasSize(26);
            assertThat(header.db2FormatTimestamp()).hasSize(DateHeader.WS_TIMESTAMP_LENGTH);
            assertThat(header.functionCurrentDate())
                    .hasSize(DateHeader.FUNCTION_CURRENT_DATE_LENGTH).hasSize(21);
            assertThat(header.gmtOffsetImage()).hasSize(DateHeader.GMT_OFFSET_LENGTH).hasSize(5);
            assertThat(header.wsCurdateYy()).hasSize(DateHeader.TWO_DIGIT_YEAR_DIGITS).hasSize(2);
        }

        @Test
        @DisplayName("the serialised structure is exactly 58 bytes, and is the four groups in order")
        void serialisedWidth() {
            DateHeader header = referenceHeader();

            byte[] bytes = header.toBytes();

            assertThat(bytes).hasSize(DateHeader.WS_DATE_TIME_LENGTH).hasSize(58);
            // The byte image is the four sub-groups concatenated - nothing more, nothing reordered.
            assertThat(new String(bytes, ASCII)).isEqualTo(header.wsCurdateData()
                    + header.wsCurdateMmDdYy()
                    + header.wsCurtimeHhMmSs()
                    + header.wsTimestamp());
        }

        @Test
        @DisplayName("the layout proves the geometry: 30 storage spans, 8 overlays, total 58")
        void layoutGeometry() {
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.recordLength())
                    .isEqualTo(DateHeader.WS_DATE_TIME_LENGTH);
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.storageSpans()).hasSize(30);
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.redefinitions()).hasSize(8);
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.spans()).hasSize(38);

            int storageBytes = DateHeader.WS_DATE_TIME_LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length)
                    .sum();
            assertThat(storageBytes).isEqualTo(58);
            // An overlay contributes no storage, which is the whole point of declaring it as one.
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.redefinitions())
                    .allMatch(FieldSpan::redefinition);
        }

        @ParameterizedTest(name = "the group item {0} resolves at offset {1} with length {2}")
        @CsvSource({
            "WS-CURDATE-DATA,0,16",
            "WS-CURDATE,0,8",
            "WS-CURTIME,8,8",
            "WS-CURDATE-MM-DD-YY,16,8",
            "WS-CURTIME-HH-MM-SS,24,8",
            "WS-TIMESTAMP,32,26",
        })
        @DisplayName("every COBOL group item is a referable name in the layout")
        void groupItemsAreAddressable(String name, int offset, int length) {
            FieldSpan span = DateHeader.WS_DATE_TIME_LAYOUT.span(name);

            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(span.redefinition()).isTrue();
        }

        @Test
        @DisplayName("the declared sub-group offsets match the layout's own")
        void declaredOffsetsAgreeWithLayout() {
            assertThat(DateHeader.WS_CURDATE_DATA_OFFSET).isZero();
            assertThat(DateHeader.WS_CURTIME_OFFSET).isEqualTo(8);
            assertThat(DateHeader.WS_CURDATE_MM_DD_YY_OFFSET).isEqualTo(16);
            assertThat(DateHeader.WS_CURTIME_HH_MM_SS_OFFSET).isEqualTo(24);
            assertThat(DateHeader.WS_TIMESTAMP_OFFSET).isEqualTo(32);
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.span(DateHeader.WS_TIMESTAMP).offset())
                    .isEqualTo(DateHeader.WS_TIMESTAMP_OFFSET);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Separators - the FILLER exception: a declared VALUE, never a pad byte")
    class Separators {

        @Test
        @DisplayName("MM/DD/YY carries its two slashes and a TWO-digit year")
        void slashesAndTwoDigitYear() {
            // app/cpy/CSDAT01Y.cpy:32 and :34 - FILLER PIC X(01) VALUE '/'
            assertThat(referenceHeader().wsCurdateMmDdYy()).isEqualTo("12/25/24");
            assertThat(referenceHeader().wsCurdateMmDdYy().charAt(2))
                    .isEqualTo(DateHeader.DATE_SEPARATOR);
            assertThat(referenceHeader().wsCurdateMmDdYy().charAt(5))
                    .isEqualTo(DateHeader.DATE_SEPARATOR);
            // The classic space-filling defect would produce this instead.
            assertThat(referenceHeader().wsCurdateMmDdYy()).isNotEqualTo("12 25 24");
        }

        @Test
        @DisplayName("HH:MM:SS carries its two colons")
        void colons() {
            // app/cpy/CSDAT01Y.cpy:38 and :40 - FILLER PIC X(01) VALUE ':'
            assertThat(referenceHeader().wsCurtimeHhMmSs()).isEqualTo("13:45:07");
            assertThat(referenceHeader().wsCurtimeHhMmSs().charAt(2))
                    .isEqualTo(DateHeader.TIME_SEPARATOR);
            assertThat(referenceHeader().wsCurtimeHhMmSs().charAt(5))
                    .isEqualTo(DateHeader.TIME_SEPARATOR);
            assertThat(referenceHeader().wsCurtimeHhMmSs()).isNotEqualTo("13 45 07");
        }

        @Test
        @DisplayName("WS-TIMESTAMP carries dash, dash, space, colon, colon, dot - in that order")
        void timestampSeparatorsPositionally() {
            String timestamp = referenceHeader().wsTimestamp();

            assertThat(timestamp).isEqualTo(REFERENCE_TIMESTAMP);
            // app/cpy/CSDAT01Y.cpy:44, :46 - '-'
            assertThat(timestamp.charAt(4)).isEqualTo(DateHeader.TIMESTAMP_DATE_SEPARATOR);
            assertThat(timestamp.charAt(7)).isEqualTo(DateHeader.TIMESTAMP_DATE_SEPARATOR);
            // app/cpy/CSDAT01Y.cpy:48 - a SPACE, and it is a declared literal, not a pad
            assertThat(timestamp.charAt(10)).isEqualTo(DateHeader.TIMESTAMP_DATE_TIME_SEPARATOR);
            assertThat(timestamp.charAt(10)).isEqualTo(' ');
            // app/cpy/CSDAT01Y.cpy:50, :52 - ':'
            assertThat(timestamp.charAt(13)).isEqualTo(DateHeader.TIME_SEPARATOR);
            assertThat(timestamp.charAt(16)).isEqualTo(DateHeader.TIME_SEPARATOR);
            // app/cpy/CSDAT01Y.cpy:54 - '.', then SIX digits
            assertThat(timestamp.charAt(19)).isEqualTo(DateHeader.TIMESTAMP_FRACTION_SEPARATOR);
            assertThat(timestamp.substring(20)).hasSize(6).isEqualTo("089123");
        }

        @ParameterizedTest(name = "byte {0} of the serialised area holds the declared literal {1}")
        @CsvSource({
            "18,/", "21,/",
            "26,:", "29,:",
            "36,-", "39,-",
            "45,:", "48,:",
            "51,.",
        })
        @DisplayName("each separator FILLER emits its literal in the serialised bytes")
        void separatorsSurviveSerialisation(int offset, char expected) {
            byte[] bytes = referenceHeader().toBytes();

            assertThat(new String(bytes, ASCII).charAt(offset)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the space FILLER at byte 42 is the declared literal, asserted separately")
        void spaceFillerSurvivesSerialisation() {
            // Asserted apart from the others because a space is the one literal that a blanket
            // space-fill would also produce, so it is the position where a wrong implementation
            // looks right. Its correctness is evidenced by its neighbours: bytes 36 and 39 must be
            // '-' and byte 45 must be ':', which a blanket fill could not manage.
            String area = new String(referenceHeader().toBytes(), ASCII);

            assertThat(area.charAt(42)).isEqualTo(DateHeader.TIMESTAMP_DATE_TIME_SEPARATOR);
            assertThat(area.charAt(36)).isEqualTo('-');
            assertThat(area.charAt(39)).isEqualTo('-');
            assertThat(area.charAt(45)).isEqualTo(':');
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Zero-fill - PIC 9 pads on the LEFT, through the codec's single implementation")
    class ZeroFill {

        @Test
        @DisplayName("a single-digit month, day, hour, minute and second all keep their leading zero")
        void leadingZerosPresent() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(2024, 1, 2, 3, 4, 5));

            assertThat(header.wsCurdate()).isEqualTo("20240102");
            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("03:04:05");
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("01/02/24");
            assertThat(header.wsCurtime()).isEqualTo("03040500");
            assertThat(header.wsTimestamp()).isEqualTo("2024-01-02 03:04:05.000000");
        }

        @Test
        @DisplayName("the concatenation defect is excluded: WS-CURDATE is never a short string")
        void notTheConcatenationDefect() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(2024, 1, 2, 3, 4, 5));

            // Naive concatenation of the numeric values would give "202412" - six characters.
            assertThat(header.wsCurdate()).isNotEqualTo("202412").hasSize(8);
            assertThat(header.wsCurtimeHhMmSs()).isNotEqualTo("3:4:5").hasSize(8);
        }

        @Test
        @DisplayName("a year below 1000 keeps all four digits in the image")
        void shortYearIsStillFourDigits() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(7, 3, 4, 5, 6, 7));

            assertThat(header.wsCurdate()).isEqualTo("00070304");
            assertThat(header.wsTimestamp()).isEqualTo("0007-03-04 05:06:07.000000");
            // The REDEFINES numeric view legitimately loses those leading zeros, as COBOL does.
            assertThat(header.wsCurdateN()).isEqualTo(70304);
        }

        @Test
        @DisplayName("the fractional second is truncated toward zero, never rounded")
        void fractionTruncates() {
            // 999,999,999 nanoseconds is 0.999999999 s: hundredths 99, microseconds 999999. Rounding
            // either would carry into the next second, which COBOL never does - ROUNDED appears
            // nowhere in the 28 programs.
            DateHeader header = DateHeader.of(ASCII_CODEC,
                    LocalDateTime.of(2024, 12, 25, 13, 45, 7, 999_999_999));

            assertThat(header.captured().hundredths()).isEqualTo(99);
            assertThat(header.captured().microseconds()).isEqualTo(999_999);
            assertThat(header.wsCurtime()).isEqualTo("13450799");
            assertThat(header.wsTimestamp()).isEqualTo("2024-12-25 13:45:07.999999");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The two-digit year - WS-CURDATE-YEAR(3:2), keeping the low-order digits")
    class TwoDigitYear {

        @ParameterizedTest(name = "year {0} renders as {1}")
        @CsvSource({
            "2024,24",
            "1999,99",
            "2000,00",
            "1900,00",
            "2001,01",
            "1970,70",
            "9999,99",
            "7,07",
        })
        @DisplayName("reference modification keeps positions 3 and 4 of the four-digit year")
        void lowOrderTwoDigits(int year, String expected) {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(year, 6, 15, 12, 0, 0));

            assertThat(header.wsCurdateYy()).isEqualTo(expected).hasSize(2);
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("06/15/" + expected);
        }

        @Test
        @DisplayName("the (3:2) mechanism agrees with the numeric MOVE rule for the same year")
        void mechanismsAgree() {
            // app/cbl/COMEN01C.cbl:223 uses reference modification; the general PIC 9(04) into
            // PIC 9(02) numeric MOVE rule truncates on the left. Both must give the same two digits,
            // and this asserts they do rather than assuming it.
            DateHeader header = referenceHeader();

            assertThat(header.wsCurdateYy())
                    .isEqualTo(ASCII_CODEC.movePic9(header.captured().year(), 2));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - two views over one span, which therefore cannot disagree")
    class Redefines {

        @Test
        @DisplayName("WS-CURDATE-N is the numeric value of the WS-CURDATE span")
        void curdateNMatchesItsSpan() {
            DateHeader header = referenceHeader();

            assertThat(header.wsCurdateN()).isEqualTo(20241225);
            assertThat(header.wsCurdateN()).isEqualTo(Integer.parseInt(header.wsCurdate()));
        }

        @Test
        @DisplayName("WS-CURTIME-N is the numeric value of the WS-CURTIME span")
        void curtimeNMatchesItsSpan() {
            DateHeader header = referenceHeader();

            assertThat(header.wsCurtimeN()).isEqualTo(13450708);
            assertThat(header.wsCurtimeN()).isEqualTo(Integer.parseInt(header.wsCurtime()));
        }

        @Test
        @DisplayName("both views round-trip through the serialised area at the same offsets")
        void viewsRoundTripThroughBytes() {
            DateHeader header = referenceHeader();

            Map<String, String> images =
                    ASCII_CODEC.deserialise(DateHeader.WS_DATE_TIME_LAYOUT, header.toBytes());

            // The overlay and the span it redefines read back the same characters, because they are
            // the same bytes.
            assertThat(images.get(DateHeader.WS_CURDATE_N)).isEqualTo(header.wsCurdate());
            assertThat(images.get(DateHeader.WS_CURDATE)).isEqualTo(header.wsCurdate());
            assertThat(images.get(DateHeader.WS_CURTIME_N)).isEqualTo(header.wsCurtime());
            assertThat(images.get(DateHeader.WS_CURTIME)).isEqualTo(header.wsCurtime());
            assertThat(ASCII_CODEC.decodePic9AsInt(images.get(DateHeader.WS_CURDATE_N)))
                    .isEqualTo(header.wsCurdateN());
            assertThat(ASCII_CODEC.decodePic9AsInt(images.get(DateHeader.WS_CURTIME_N)))
                    .isEqualTo(header.wsCurtimeN());
        }

        @ParameterizedTest(name = "the REDEFINES view {0} is 8 digits over already-declared storage")
        @ValueSource(strings = {"WS-CURDATE-N", "WS-CURTIME-N"})
        @DisplayName("each numeric REDEFINES view is declared PIC 9(08) as an overlay")
        void numericViewsAreOverlays(String name) {
            FieldSpan span = DateHeader.WS_DATE_TIME_LAYOUT.span(name);

            assertThat(span.length()).isEqualTo(DateHeader.REDEFINED_VIEW_DIGITS).isEqualTo(8);
            assertThat(span.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(span.redefinition()).isTrue();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("FUNCTION CURRENT-DATE - 21 characters, right-truncated to 16")
    class CurrentDateTruncation {

        @Test
        @DisplayName("the intrinsic result truncated to 16 is exactly WS-CURDATE-DATA")
        void truncationProducesCurdateData() {
            DateHeader header = referenceHeader();

            String intrinsic = header.functionCurrentDate();

            assertThat(intrinsic).hasSize(21);
            assertThat(intrinsic.substring(0, 16)).isEqualTo(header.wsCurdateData());
            assertThat(header.wsCurdateData()).isEqualTo(REFERENCE_CURDATE_DATA);
        }

        @Test
        @DisplayName("the discarded tail is the five-character GMT offset, and nothing else")
        void discardedTailIsTheOffset() {
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE, ZoneOffset.ofHours(-6));

            assertThat(header.discardedGmtOffset())
                    .hasSize(5)
                    .isEqualTo("-0600")
                    .isEqualTo(header.gmtOffsetImage());
            assertThat(header.functionCurrentDate())
                    .isEqualTo(header.wsCurdateData() + header.discardedGmtOffset());
            // The offset is genuinely absent from the header the screens are stamped with.
            assertThat(header.wsCurdateData()).doesNotContain("-").doesNotContain("+");
        }

        @Test
        @DisplayName("the intrinsic is WS-CURDATE then WS-CURTIME then the offset - CBACT04C COBOL-TS")
        void intrinsicComposition() {
            DateHeader header = referenceHeader();

            // app/cbl/CBACT04C.cbl:141-149 declares this partition as 4+2+2 / 2+2+2+2 / 5.
            assertThat(header.functionCurrentDate())
                    .isEqualTo(header.wsCurdate() + header.wsCurtime() + header.gmtOffsetImage());
            assertThat(header.functionCurrentDate()).isEqualTo("2024122513450708+0000");
        }

        @Test
        @DisplayName("changing only the offset leaves WS-CURDATE-DATA byte-identical")
        void offsetDoesNotReachTheHeader() {
            DateHeader greenwich = DateHeader.of(ASCII_CODEC, REFERENCE, ZoneOffset.UTC);
            DateHeader chicago = DateHeader.of(ASCII_CODEC, REFERENCE, ZoneOffset.ofHours(-6));

            assertThat(chicago.wsCurdateData()).isEqualTo(greenwich.wsCurdateData());
            assertThat(chicago.toBytes()).isEqualTo(greenwich.toBytes());
            assertThat(chicago.functionCurrentDate())
                    .isNotEqualTo(greenwich.functionCurrentDate());
        }

        @Test
        @DisplayName("an offset carrying seconds is reduced to whole minutes, as shhmm requires")
        void offsetSecondsAreDropped() {
            // Amsterdam ran at +00:19:32 before 1937. shhmm cannot express the 32 seconds.
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE,
                    ZoneOffset.ofHoursMinutesSeconds(0, 19, 32));

            assertThat(header.captured().gmtOffsetMinutes()).isEqualTo(19);
            assertThat(header.gmtOffsetImage()).isEqualTo("+0019").hasSize(5);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The two 26-byte timestamps carry different separators and different precisions")
    class TwoTimestampForms {

        @Test
        @DisplayName("WS-TIMESTAMP uses space and colons; DB2-FORMAT-TS uses dash and dots")
        void separatorsDiffer() {
            DateHeader header = referenceHeader();

            assertThat(header.wsTimestamp()).isEqualTo("2024-12-25 13:45:07.089123");
            // app/cbl/CBACT04C.cbl:623-624 - DB2-STREEP-3 is '-' and DB2-DOT-1/2 are '.'
            assertThat(header.db2FormatTimestamp()).isEqualTo("2024-12-25-13.45.07.080000");
            assertThat(header.db2FormatTimestamp()).isNotEqualTo(header.wsTimestamp());
            assertThat(header.db2FormatTimestamp()).hasSameSizeAs(header.wsTimestamp());
        }

        @Test
        @DisplayName("the fractional halves differ: six true microseconds against hundredths plus 0000")
        void fractionalPrecisionsDiffer() {
            DateHeader header = referenceHeader();

            // app/cbl/CBACT04C.cbl:621-622 - DB2-MIL PIC 9(02) then the literal '0000'.
            assertThat(header.wsTimestamp().substring(20)).isEqualTo("089123");
            assertThat(header.db2FormatTimestamp().substring(20)).isEqualTo("080000");
            assertThat(header.db2FormatTimestamp().substring(20, 22))
                    .isEqualTo(header.wsCurtime().substring(6));
            assertThat(header.db2FormatTimestamp().substring(22)).isEqualTo("0000");
        }

        @Test
        @DisplayName("the date halves are identical, so only the documented positions differ")
        void dateHalvesAgree() {
            DateHeader header = referenceHeader();

            assertThat(header.db2FormatTimestamp().substring(0, 10))
                    .isEqualTo(header.wsTimestamp().substring(0, 10))
                    .isEqualTo("2024-12-25");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Serialisation and field images")
    class Serialisation {

        @Test
        @DisplayName("fieldImages holds the 20 referable elementary fields, and no FILLER")
        void twentyElementaryImages() {
            Map<String, String> images = referenceHeader().fieldImages();

            assertThat(images).hasSize(20);
            assertThat(images).doesNotContainKey("FILLER");
            assertThat(images).doesNotContainKey(DateHeader.WS_CURDATE_N);
            assertThat(images).doesNotContainKey(DateHeader.WS_TIMESTAMP);
            assertThat(images.get(DateHeader.WS_CURDATE_YEAR)).isEqualTo("2024");
            assertThat(images.get(DateHeader.WS_CURDATE_MONTH)).isEqualTo("12");
            assertThat(images.get(DateHeader.WS_CURDATE_DAY)).isEqualTo("25");
            assertThat(images.get(DateHeader.WS_CURTIME_MILSEC)).isEqualTo("08");
            assertThat(images.get(DateHeader.WS_CURDATE_YY)).isEqualTo("24");
            assertThat(images.get(DateHeader.WS_TIMESTAMP_TM_MS6)).isEqualTo("089123");
        }

        @Test
        @DisplayName("fieldImages is unmodifiable, so a caller cannot mutate a header's state")
        void fieldImagesUnmodifiable() {
            Map<String, String> images = referenceHeader().fieldImages();

            assertThat(images).isUnmodifiable();
        }

        @Test
        @DisplayName("deserialising the 58 bytes returns all 20 fields plus the 8 named views")
        void deserialiseReturnsEveryName() {
            Map<String, String> images =
                    ASCII_CODEC.deserialise(DateHeader.WS_DATE_TIME_LAYOUT, referenceHeader().toBytes());

            assertThat(images).hasSize(28);
            assertThat(images.get(DateHeader.WS_CURDATE_DATA)).isEqualTo(REFERENCE_CURDATE_DATA);
            assertThat(images.get(DateHeader.WS_CURDATE_MM_DD_YY)).isEqualTo("12/25/24");
            assertThat(images.get(DateHeader.WS_CURTIME_HH_MM_SS)).isEqualTo("13:45:07");
            assertThat(images.get(DateHeader.WS_TIMESTAMP)).isEqualTo(REFERENCE_TIMESTAMP);
        }

        @Test
        @DisplayName("the characters are code-page independent but the bytes are not")
        void charsetReachesTheBytesOnly() {
            DateHeader ascii = DateHeader.of(ASCII_CODEC, REFERENCE);
            DateHeader ebcdic = DateHeader.of(new FixedWidthCodec(EBCDIC), REFERENCE);

            assertThat(ebcdic.wsTimestamp()).isEqualTo(ascii.wsTimestamp());
            assertThat(ebcdic.toBytes()).hasSize(58);
            assertThat(ebcdic.toBytes()).isNotEqualTo(ascii.toBytes());
            // Decoded in its own code page, the EBCDIC image reads identically.
            assertThat(new String(ebcdic.toBytes(), EBCDIC))
                    .isEqualTo(new String(ascii.toBytes(), ASCII));
        }

        @Test
        @DisplayName("toBytes returns a fresh array each time, so a caller cannot corrupt a header")
        void toBytesIsDefensive() {
            DateHeader header = referenceHeader();

            byte[] first = header.toBytes();
            first[0] = (byte) '9';

            assertThat(header.toBytes()[0]).isEqualTo((byte) '2');
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("ofTimestampImage - MOVE TRAN-ORIG-TS TO WS-TIMESTAMP, COTRN00C:384")
    class TimestampImageDirection {

        @Test
        @DisplayName("a well-formed image round-trips to itself")
        void roundTrips() {
            DateHeader header = DateHeader.ofTimestampImage(ASCII_CODEC, REFERENCE_TIMESTAMP);

            assertThat(header.wsTimestamp()).isEqualTo(REFERENCE_TIMESTAMP);
            assertThat(header.captured().year()).isEqualTo(2024);
            assertThat(header.captured().month()).isEqualTo(12);
            assertThat(header.captured().day()).isEqualTo(25);
            assertThat(header.captured().hours()).isEqualTo(13);
            assertThat(header.captured().minutes()).isEqualTo(45);
            assertThat(header.captured().seconds()).isEqualTo(7);
            assertThat(header.captured().microseconds()).isEqualTo(89_123);
            // The hundredths are recovered from the only fractional data the image carries.
            assertThat(header.captured().hundredths()).isEqualTo(8);
            assertThat(header.gmtOffsetImage()).isEqualTo("+0000");
        }

        @Test
        @DisplayName("COTRN00C's screen fields are readable straight off a parsed image")
        void feedsTheScreenFields() {
            // app/cbl/COTRN00C.cbl:385-387 reads DT-YYYY(3:2), DT-MM and DT-DD out of the group.
            DateHeader header = DateHeader.ofTimestampImage(ASCII_CODEC, "2022-07-18 12:00:00.000000");

            assertThat(header.wsCurdateYy()).isEqualTo("22");
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("07/18/22");
            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("12:00:00");
        }

        @Test
        @DisplayName("an all-zero image is accepted, because INITIALIZE WS-TIMESTAMP produces one")
        void initializedImageIsValid() {
            // app/cbl/COBIL00C.cbl:263 - INITIALIZE WS-TIMESTAMP before populating it. Month 00 is
            // therefore reachable, and rejecting it would be a new business rule.
            DateHeader header =
                    DateHeader.ofTimestampImage(ASCII_CODEC, "0000-00-00 00:00:00.000000");

            assertThat(header.captured().month()).isZero();
            assertThat(header.captured().day()).isZero();
            assertThat(header.wsTimestamp()).isEqualTo("0000-00-00 00:00:00.000000");
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("an image of the wrong width is rejected, naming the declared width")
        void wrongWidthRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC, "2024-12-25"))
                    .withMessageContaining("PIC X(26)")
                    .withMessageContaining("10 character(s)");
        }

        @Test
        @DisplayName("CBACT04C's DB2-FORMAT-TS form is rejected: same width, different layout")
        void db2FormIsRejected() {
            String db2Form = referenceHeader().db2FormatTimestamp();

            assertThat(db2Form).hasSize(26);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC, db2Form))
                    .withMessageContaining("does not round-trip")
                    .withMessageContaining("DB2-FORMAT-TS");
        }

        @ParameterizedTest(name = "the image {0} is rejected for a wrong separator")
        @ValueSource(strings = {
            "2024/12/25 13:45:07.089123",
            "2024-12-25T13:45:07.089123",
            "2024-12-25 13.45.07.089123",
            "2024-12-25 13:45:07,089123",
        })
        @DisplayName("a separator in any other convention is rejected")
        void wrongSeparatorsRejected(String image) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC, image))
                    .withMessageContaining("does not round-trip");
        }

        @Test
        @DisplayName("a non-digit in a sub-field is rejected, and the failure names that sub-field")
        void nonDigitNamesTheField() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC,
                            "2024-12-25 13:45:0X.089123"))
                    .withMessageContaining("WS-TIMESTAMP-TM-SS")
                    .withMessageContaining("PIC 9(02)");
        }

        @Test
        @DisplayName("an all-spaces image is rejected at its first numeric sub-field")
        void blankImageRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC, " ".repeat(26)))
                    .withMessageContaining("WS-TIMESTAMP-DT-YYYY");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Boundaries")
    class Boundaries {

        @ParameterizedTest(name = "{0}-{1}-{2} {3}:{4}:{5} renders at full width with its separators")
        @CsvSource({
            // midnight, one second before midnight, first and last day of the year, and a leap day
            "2024,12,25,0,0,0",
            "2024,12,25,23,59,59",
            "2024,1,1,0,0,0",
            "2024,12,31,23,59,59",
            "2024,2,29,12,0,0",
            "2000,2,29,12,0,0",
            "1999,12,31,23,59,59",
        })
        @DisplayName("each boundary instant renders at the declared widths")
        void boundaryInstants(int year, int month, int day, int hour, int minute, int second) {
            DateHeader header = DateHeader.of(ASCII_CODEC,
                    LocalDateTime.of(year, month, day, hour, minute, second));

            assertThat(header.wsCurdate()).hasSize(8);
            assertThat(header.wsCurtime()).hasSize(8);
            assertThat(header.wsCurdateData()).hasSize(16);
            assertThat(header.wsCurdateMmDdYy()).hasSize(8);
            assertThat(header.wsCurtimeHhMmSs()).hasSize(8);
            assertThat(header.wsTimestamp()).hasSize(26);
            assertThat(header.toBytes()).hasSize(58);
            assertThat(header.wsCurdateMmDdYy().charAt(2)).isEqualTo('/');
            assertThat(header.wsCurtimeHhMmSs().charAt(2)).isEqualTo(':');
            assertThat(header.wsTimestamp().charAt(10)).isEqualTo(' ');
        }

        @Test
        @DisplayName("midnight renders as all zeros, not as blanks")
        void midnight() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(2024, 1, 1, 0, 0, 0));

            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("00:00:00");
            assertThat(header.wsCurtime()).isEqualTo("00000000");
            assertThat(header.wsCurtimeN()).isZero();
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("01/01/24");
        }

        @Test
        @DisplayName("one second before midnight on 31 December")
        void lastSecondOfTheYear() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(2024, 12, 31, 23, 59, 59));

            assertThat(header.wsCurdate()).isEqualTo("20241231");
            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("23:59:59");
            assertThat(header.wsTimestamp()).isEqualTo("2024-12-31 23:59:59.000000");
        }

        @Test
        @DisplayName("the leap day of 29 February 2024")
        void leapDay() {
            DateHeader header = DateHeader.of(ASCII_CODEC, LocalDateTime.of(2024, 2, 29, 12, 0, 0));

            assertThat(header.wsCurdate()).isEqualTo("20240229");
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("02/29/24");
            assertThat(header.wsTimestamp()).isEqualTo("2024-02-29 12:00:00.000000");
        }

        @Test
        @DisplayName("year 0 and year 9999 are the extremes a PIC 9(04) receiver can hold")
        void yearExtremes() {
            assertThat(DateHeader.of(ASCII_CODEC, LocalDateTime.of(0, 1, 1, 0, 0)).wsCurdate())
                    .isEqualTo("00000101");
            assertThat(DateHeader.of(ASCII_CODEC, LocalDateTime.of(9999, 12, 31, 0, 0)).wsCurdate())
                    .isEqualTo("99991231");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Guards - every one of them a reachable branch")
    class Guards {

        @Test
        @DisplayName("a null Clock is rejected, and the message says why a clock is required")
        void nullClock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.from(ASCII_CODEC, null))
                    .withMessageContaining("Clock is required")
                    .withMessageContaining("now()");
        }

        @Test
        @DisplayName("a null LocalDateTime is rejected by both of the explicit factories")
        void nullDateTime() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.of(ASCII_CODEC, null))
                    .withMessageContaining("local date and time is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.of(ASCII_CODEC, null, ZoneOffset.UTC))
                    .withMessageContaining("local date and time is required");
        }

        @Test
        @DisplayName("a null codec is rejected by every factory")
        void nullCodec() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.from(null, fixedUtc("2024-12-25T00:00:00Z")))
                    .withMessageContaining("FixedWidthCodec is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.of(null, REFERENCE))
                    .withMessageContaining("zero-fill");
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.of(null, REFERENCE, ZoneOffset.UTC))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(null, REFERENCE_TIMESTAMP))
                    .withMessageContaining("FixedWidthCodec is required");
        }

        @Test
        @DisplayName("a null ZoneOffset is rejected")
        void nullOffset() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.of(ASCII_CODEC, REFERENCE, null))
                    .withMessageContaining("ZoneOffset is required");
        }

        @Test
        @DisplayName("a null timestamp image is rejected")
        void nullImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DateHeader.ofTimestampImage(ASCII_CODEC, null))
                    .withMessageContaining("26-character WS-TIMESTAMP image is required");
        }

        @Test
        @DisplayName("a year wider than PIC 9(04) is rejected rather than truncated on the left")
        void yearTooWide() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.of(ASCII_CODEC, LocalDateTime.of(10_000, 1, 1, 0, 0)))
                    .withMessageContaining("WS-CURDATE-YEAR")
                    .withMessageContaining("PIC 9(04)")
                    .withMessageContaining("0 to 9999");
        }

        @Test
        @DisplayName("a negative year is rejected: PIC 9 is unsigned")
        void negativeYear() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DateHeader.of(ASCII_CODEC, LocalDateTime.of(-1, 1, 1, 0, 0)))
                    .withMessageContaining("WS-CURDATE-YEAR");
        }

        @ParameterizedTest(name = "an offset of {0} minutes cannot render as shhmm and is rejected")
        @ValueSource(ints = {1440, -1440, 6000, -6000})
        @DisplayName("an offset beyond plus or minus 23:59 is rejected")
        void offsetTooWide(int offsetMinutes) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, 89_123,
                            offsetMinutes))
                    .withMessageContaining("GMT offset")
                    .withMessageContaining("two digits");
        }

        @ParameterizedTest(name = "component {0} out of range is rejected, naming {1}")
        @CsvSource({
            "month,WS-CURDATE-MONTH",
            "day,WS-CURDATE-DAY",
            "hours,WS-CURTIME-HOURS",
            "minutes,WS-CURTIME-MINUTE",
            "seconds,WS-CURTIME-SECOND",
            "hundredths,WS-CURTIME-MILSEC",
            "microseconds,WS-TIMESTAMP-TM-MS6",
        })
        @DisplayName("every two-digit and six-digit component is width-checked by name")
        void everyComponentIsWidthChecked(String component, String fieldName) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildWithOverflow(component))
                    .withMessageContaining(fieldName);
        }

        private CapturedDateTime buildWithOverflow(String component) {
            int overflowTwoDigit = 100;
            int overflowSixDigit = 1_000_000;
            return switch (component) {
                case "month" -> new CapturedDateTime(2024, overflowTwoDigit, 25, 13, 45, 7, 8, 0, 0);
                case "day" -> new CapturedDateTime(2024, 12, overflowTwoDigit, 13, 45, 7, 8, 0, 0);
                case "hours" -> new CapturedDateTime(2024, 12, 25, overflowTwoDigit, 45, 7, 8, 0, 0);
                case "minutes" -> new CapturedDateTime(2024, 12, 25, 13, overflowTwoDigit, 7, 8, 0, 0);
                case "seconds" -> new CapturedDateTime(2024, 12, 25, 13, 45, overflowTwoDigit, 8, 0, 0);
                case "hundredths" ->
                        new CapturedDateTime(2024, 12, 25, 13, 45, 7, overflowTwoDigit, 0, 0);
                case "microseconds" ->
                        new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, overflowSixDigit, 0);
                default -> throw new IllegalStateException("Unknown component " + component);
            };
        }

        @Test
        @DisplayName("a negative component is rejected as well, since PIC 9 has no sign position")
        void negativeComponent() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CapturedDateTime(2024, -1, 25, 13, 45, 7, 8, 0, 0))
                    .withMessageContaining("WS-CURDATE-MONTH");
        }

        @Test
        @DisplayName("an offset at exactly plus or minus 23:59 is accepted")
        void offsetAtTheLimit() {
            assertThat(new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, 0, 1439).gmtOffsetMinutes())
                    .isEqualTo(1439);
            assertThat(new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, 0, -1439).gmtOffsetMinutes())
                    .isEqualTo(-1439);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Value semantics and immutability")
    class ValueSemantics {

        @Test
        @DisplayName("equal instants in the same code page are equal, and hash alike")
        void equality() {
            DateHeader first = DateHeader.of(ASCII_CODEC, REFERENCE);
            DateHeader second = DateHeader.of(new FixedWidthCodec(ASCII), REFERENCE);

            assertThat(second).isEqualTo(first).hasSameHashCodeAs(first);
            assertThat(first).isEqualTo(first);
        }

        @Test
        @DisplayName("a different instant, a different offset or a different code page is unequal")
        void inequality() {
            DateHeader reference = DateHeader.of(ASCII_CODEC, REFERENCE);

            assertThat(DateHeader.of(ASCII_CODEC, REFERENCE.plusSeconds(1))).isNotEqualTo(reference);
            assertThat(DateHeader.of(ASCII_CODEC, REFERENCE, ZoneOffset.ofHours(-6)))
                    .isNotEqualTo(reference);
            assertThat(DateHeader.of(new FixedWidthCodec(EBCDIC), REFERENCE))
                    .isNotEqualTo(reference);
            assertThat(reference).isNotEqualTo(REFERENCE_TIMESTAMP);
            assertThat(reference).isNotEqualTo(null);
        }

        @Test
        @DisplayName("toString labels the timestamp so it cannot be mistaken for a field image")
        void toStringIsLabelled() {
            assertThat(referenceHeader().toString())
                    .startsWith("DateHeader[")
                    .contains("WS-TIMESTAMP=" + REFERENCE_TIMESTAMP)
                    .contains("offset=+0000")
                    .contains("charset=US-ASCII")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the captured components are exposed as an immutable record")
        void capturedIsARecord() {
            CapturedDateTime captured = referenceHeader().captured();

            assertThat(CapturedDateTime.class.isRecord()).isTrue();
            assertThat(captured).isEqualTo(new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, 89_123, 0));
            assertThat(captured).hasSameHashCodeAs(
                    new CapturedDateTime(2024, 12, 25, 13, 45, 7, 8, 89_123, 0));
            assertThat(captured.toString()).contains("2024");
        }

        @Test
        @DisplayName("the codec is exposed for callers that need the code page, and is the one supplied")
        void codecIsExposed() {
            assertThat(referenceHeader().codec()).isSameAs(ASCII_CODEC);
            assertThat(referenceHeader().codec().charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("the class is final, every instance field is final, and there is no setter")
        void immutableShape() {
            assertThat(Modifier.isFinal(DateHeader.class.getModifiers())).isTrue();
            for (Field field : DateHeader.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
            assertThat(DateHeader.class.getMethods())
                    .noneMatch(method -> method.getName().startsWith("set"));
        }

        @Test
        @DisplayName("there is no static mutable state: COBOL WORKING-STORAGE is not a static field")
        void noStaticMutableState() {
            for (Field field : DateHeader.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                    // Only primitives, Strings and the immutable RecordLayout are permitted; an
                    // array or a collection would be mutable however it was declared.
                    assertThat(field.getType().isArray())
                            .as("static field %s must not be an array", field.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("the layout constant cannot be mutated through its span list")
        void layoutSpansUnmodifiable() {
            assertThat(DateHeader.WS_DATE_TIME_LAYOUT.spans()).isUnmodifiable();
        }
    }

    // =============================================================================================
    // F09 - the four 05 groups of 01 WS-DATE-TIME are independent storage.
    // =============================================================================================

    /**
     * That {@code WS-CURDATE-DATA}, {@code WS-CURDATE-MM-DD-YY}, {@code WS-CURTIME-HH-MM-SS} and
     * {@code WS-TIMESTAMP} hold their own values rather than four projections of one instant.
     *
     * <p>Two states of these programs are the whole reason it matters, and each is asserted here
     * against the statements that produce it:
     * <ul>
     *   <li>{@code app/cbl/COTRN00C.cbl:384-388} - a stored {@code TRAN-ORIG-TS} in
     *       {@code WS-TIMESTAMP} and the transaction's date in {@code WS-CURDATE-MM-DD-YY}, while
     *       {@code WS-CURDATE-DATA} still carries the current date the screen heading was painted
     *       from;</li>
     *   <li>{@code app/cbl/COBIL00C.cbl:263-266} - {@code WS-TIMESTAMP-TM-MS6} zero by explicit
     *       statement while {@code WS-CURTIME-MILSEC} keeps real hundredths.</li>
     * </ul>
     * Both were unrepresentable while every accessor projected {@link CapturedDateTime}.
     */
    @Nested
    @DisplayName("Independent 05 groups - COTRN00C and COBIL00C - F09")
    class IndependentGroups {

        /** A stored transaction timestamp: years away from {@link #REFERENCE}, and never "now". */
        private static final String STORED_TRAN_ORIG_TS = "2019-03-04 08:09:10.111213";

        @Test
        @DisplayName("a fresh header is self-consistent, exactly as POPULATE-HEADER-INFO leaves it")
        void aFreshHeaderIsSelfConsistent() {
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE);

            assertThat(header.wsCurdateData()).isEqualTo(REFERENCE_CURDATE_DATA);
            assertThat(header.wsCurdateMmDdYy()).isEqualTo("12/25/24");
            assertThat(header.wsCurtimeHhMmSs()).isEqualTo("13:45:07");
            assertThat(header.wsTimestamp()).isEqualTo(REFERENCE_TIMESTAMP);
        }

        @Test
        @DisplayName("COTRN00C:384 - WS-TIMESTAMP takes a stored value and NOTHING else moves")
        void movingAStoredTimestampLeavesTheOtherThreeGroupsAlone() {
            DateHeader painted = DateHeader.of(ASCII_CODEC, REFERENCE);

            DateHeader withStored = painted.withTimestampImage(STORED_TRAN_ORIG_TS);

            assertThat(withStored.wsTimestamp())
                    .as("MOVE TRAN-ORIG-TS TO WS-TIMESTAMP")
                    .isEqualTo(STORED_TRAN_ORIG_TS);
            assertThat(withStored.wsCurdateData())
                    .as("WS-CURDATE-DATA still holds the current date the heading was painted from")
                    .isEqualTo(REFERENCE_CURDATE_DATA);
            assertThat(withStored.wsCurdateMmDdYy()).isEqualTo("12/25/24");
            assertThat(withStored.wsCurtimeHhMmSs()).isEqualTo("13:45:07");
            assertThat(painted.wsTimestamp())
                    .as("the operation returns a new header and mutates nothing")
                    .isEqualTo(REFERENCE_TIMESTAMP);
        }

        @Test
        @DisplayName("COTRN00C:385-387 - WS-CURDATE-MM-DD-YY is refilled from WS-TIMESTAMP")
        void curdateMmDdYyIsRefilledFromTheTimestamp() {
            DateHeader row = DateHeader.of(ASCII_CODEC, REFERENCE)
                    .withTimestampImage(STORED_TRAN_ORIG_TS)
                    .withCurdateMmDdYyFromTimestamp();

            assertThat(row.wsCurdateMmDdYy())
                    .as("MOVE WS-TIMESTAMP-DT-MM/-DD and -DT-YYYY(3:2), which L388 sends to "
                            + "WS-TRAN-DATE")
                    .isEqualTo("03/04/19");
            assertThat(row.wsCurdateYy()).isEqualTo("19");
            assertThat(row.wsCurdateData())
                    .as("WS-CURDATE-DATA is not touched by those three moves")
                    .isEqualTo(REFERENCE_CURDATE_DATA);
            assertThat(row.wsCurtimeHhMmSs())
                    .as("nor is WS-CURTIME-HH-MM-SS")
                    .isEqualTo("13:45:07");
        }

        @Test
        @DisplayName("the whole 58-byte image carries both moments at once")
        void theWholeImageCarriesBothMomentsAtOnce() {
            DateHeader row = DateHeader.of(ASCII_CODEC, REFERENCE)
                    .withTimestampImage(STORED_TRAN_ORIG_TS)
                    .withCurdateMmDdYyFromTimestamp();

            Map<String, String> images = row.fieldImages();

            assertThat(images).containsEntry(DateHeader.WS_CURDATE_YEAR, "2024")
                    .containsEntry(DateHeader.WS_CURDATE_MONTH, "12")
                    .containsEntry(DateHeader.WS_CURDATE_DAY, "25")
                    .containsEntry(DateHeader.WS_CURDATE_MM, "03")
                    .containsEntry(DateHeader.WS_CURDATE_DD, "04")
                    .containsEntry(DateHeader.WS_CURDATE_YY, "19")
                    .containsEntry(DateHeader.WS_TIMESTAMP_DT_YYYY, "2019")
                    .containsEntry(DateHeader.WS_TIMESTAMP_TM_MS6, "111213");
            assertThat(row.toBytes()).hasSize(DateHeader.WS_DATE_TIME_LENGTH);
        }

        @Test
        @DisplayName("COBIL00C:263-266 - MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 survives a precise clock")
        void formatTimeLeavesTheFractionalPartZero() {
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE)
                    .withTimestampFromFormatTime("2024-12-25", "13:45:07");

            assertThat(header.wsTimestamp())
                    .as("the six microsecond positions are the literal ZEROS of L266")
                    .isEqualTo("2024-12-25 13:45:07.000000");
            assertThat(header.fieldImages())
                    .containsEntry(DateHeader.WS_TIMESTAMP_TM_MS6, "000000")
                    .as("WS-CURTIME-MILSEC is a different group and keeps its real hundredths")
                    .containsEntry(DateHeader.WS_CURTIME_MILSEC, "08");
        }

        @Test
        @DisplayName("FORMATTIME values are validated through the group's own separator contract")
        void formatTimeValuesAreValidated() {
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE);

            assertThatIllegalArgumentException()
                    .as("a nine-character date cannot compose 26 characters")
                    .isThrownBy(() -> header.withTimestampFromFormatTime("2024-12-2", "13:45:07"))
                    .withMessageContaining("compose");
            assertThatIllegalArgumentException()
                    .as("DATESEP('-') and TIMESEP(':') are part of the contract")
                    .isThrownBy(() -> header.withTimestampFromFormatTime("2024/12/25", "13:45:07"));
            assertThatNullPointerException()
                    .isThrownBy(() -> header.withTimestampFromFormatTime(null, "13:45:07"));
            assertThatNullPointerException()
                    .isThrownBy(() -> header.withTimestampFromFormatTime("2024-12-25", null));
        }

        @Test
        @DisplayName("equality covers all four groups, so two headers differing only in one are unequal")
        void equalityCoversAllFourGroups() {
            DateHeader painted = DateHeader.of(ASCII_CODEC, REFERENCE);
            DateHeader withStored = painted.withTimestampImage(STORED_TRAN_ORIG_TS);
            DateHeader withRefilled = withStored.withCurdateMmDdYyFromTimestamp();

            assertThat(withStored).isNotEqualTo(painted);
            assertThat(withRefilled).isNotEqualTo(withStored);
            assertThat(withStored.hashCode()).isNotEqualTo(painted.hashCode());
            assertThat(painted.withTimestampImage(STORED_TRAN_ORIG_TS))
                    .as("the same operations from the same start yield an equal header")
                    .isEqualTo(withStored)
                    .hasSameHashCodeAs(withStored);
        }

        @Test
        @DisplayName("withTimestampImage rejects a malformed image rather than accepting it")
        void withTimestampImageRejectsAMalformedImage() {
            DateHeader header = DateHeader.of(ASCII_CODEC, REFERENCE);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> header.withTimestampImage("2024-12-25-13.45.07.089123"));
            assertThatNullPointerException()
                    .isThrownBy(() -> header.withTimestampImage(null));
        }
    }
}
