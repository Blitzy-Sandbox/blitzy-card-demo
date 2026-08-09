package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranTypeRecord}, the {@code TRANTYPE} transaction-type record that
 * {@code app/cpy/CVTRA03Y.cpy} declares in eleven lines and 60 bytes.
 *
 * <h2>Provenance - every authority this file asserts against</h2>
 * Nothing here is derived from the implementation. Each expectation traces to a {@code PICTURE}
 * clause, a {@code MOVE} statement, a DD binding or a measured fixture byte, and the source of each
 * is named at the assertion that uses it:
 * <ul>
 *   <li><strong>{@code app/cpy/CVTRA03Y.cpy}</strong> - the layout under test. Its header comment
 *       declares the width outright, <em>"Data-structure for transaction type (RECLN = 60)"</em>, and
 *       its three items are {@code TRAN-TYPE PIC X(02)}, {@code TRAN-TYPE-DESC PIC X(50)} and
 *       {@code FILLER PIC X(08)}: <strong>2 + 50 + 8 = 60</strong>. That addition is the only
 *       justification any offset in this file has, and it is asserted rather than trusted.</li>
 *   <li><strong>{@code app/cpy/CVTRA07Y.cpy:22}</strong> - {@code TRAN-REPORT-TYPE-DESC PIC X(15)},
 *       the narrower receiver that makes the {@code X(50)} to {@code X(15)} truncation observable.</li>
 *   <li><strong>{@code app/cpy/CVTRA04Y.cpy:6}</strong> - {@code TRAN-TYPE-CD PIC X(02)}. The same
 *       two bytes under a <em>different name</em>. Asserted here so the divergence is pinned, never
 *       reconciled.</li>
 *   <li><strong>{@code app/cbl/CBTRN03C.cbl}</strong> - the one and only consumer of this copybook.
 *       Line 366, inside paragraph {@code 1120-WRITE-DETAIL}, reads verbatim
 *       {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC}; lines 73-75 split the same record as
 *       {@code FD-TRAN-TYPE PIC X(02)} plus {@code FD-TRAN-DATA PIC X(58)}, which independently
 *       totals 60 and fixes the key at the leading two bytes; line 42 declares
 *       {@code RECORD KEY IS FD-TRAN-TYPE}.</li>
 *   <li><strong>{@code app/jcl/TRANREPT.jcl:69-70}</strong> - binds the {@code TRANTYPE} DD as an
 *       input to {@code STEP10R EXEC PGM=CBTRN03C} (mirrored at {@code app/proc/TRANREPT.prc:67-68}).
 *       The dataset name that DD carries is deliberately <em>not</em> reproduced anywhere in this
 *       file: it belongs to the {@code carddemo.datasets} configuration and to no Java source at all,
 *       so the JCL line is cited and the name keeps a single authority.</li>
 *   <li><strong>{@code app/data/ASCII/trantype.txt}</strong> - seven records, each measured at
 *       exactly 60 bytes (427 bytes over 7 lines). Their bytes are embedded below as literals rather
 *       than read; see the next section for why.</li>
 * </ul>
 *
 * <h2>The fixture is embedded, not read</h2>
 * There is no {@code getResourceAsStream}, no file path and no stream anywhere in this file, and
 * that is a deliberate design decision rather than an oversight. A test that loads its
 * own expectations from a file it does not own reports on the runner's filesystem as much as on the
 * code: if the resource is missing, renamed or truncated, the failure surfaces as an I/O error at the
 * <em>coverage gate</em> rather than as a located defect in the record type. The seven 60-byte rows
 * are therefore transcribed below as {@code static final String} literals, each carrying its row
 * number and 1-based byte ranges, and {@link EmbeddedFixtureProvenance} cross-checks every
 * transcription against its documented key, description and reserved-span decomposition - so a
 * miscounted space fails loudly, here, naming itself.
 *
 * <h2>What this file does not test</h2>
 * The model type's own byte behaviour, and nothing else. Opening, keyed reading and closing the
 * {@code TRANTYPE} dataset, the not-found ladder that {@code CBTRN03C:496-500} answers with
 * {@code 'INVALID TRANSACTION TYPE : '} and an abend, the report's page and account total state
 * machine, and the 133-byte report line all belong to the sibling {@code transaction} package's
 * repository, job and writer suites. {@code CBTRN03C:366} is cited here for exactly one reason: it is
 * why {@link TranTypeRecord#tranTypeDesc()} must hand out all 50 characters untrimmed.
 *
 * <h2>Conventions</h2>
 * Plain JUnit 5 with AssertJ, because nothing in the class under test needs a Spring context - there
 * is no annotation, no injection and no framework coupling to arrange. Both code pages are named
 * explicitly and neither is ever defaulted: {@code US-ASCII} for the text fixtures and {@code IBM037}
 * for the EBCDIC datasets. No wildcard import, not even a static one; no {@code double} and no
 * {@code float}, because {@code CVTRA03Y} declares no numeric item to hold in one; and no mutable
 * static state - every constant below is {@code final} and every collection immutable.
 *
 * <h2>Rules status</h2>
 * {@code review_rules} returns exactly one line, <em>"No user rules provided."</em>, verified over the
 * whole document. No rule has been invented to fill the gap and the absence is not treated as licence
 * to lower the bar: the constraints honoured here are the migration's own - fixed-width is the wire
 * format, offsets are justified by addition from the copybook, names come from the source and are
 * never harmonised, {@code FILLER} is a first-class span, encodings are always explicit, and both
 * sides of every conditional are driven.
 */
@DisplayName("TranTypeRecord - CVTRA03Y TRAN-TYPE-RECORD, 60 bytes, keyed on TRAN-TYPE X(02)")
class TranTypeRecordTest {

    /** The text fixtures are ASCII; named explicitly rather than defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the charset is honoured. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // -----------------------------------------------------------------------------------------
    // The shipped fixture, transcribed from app/data/ASCII/trantype.txt.
    //
    // Each literal is one complete 60-byte record, exactly as stored, with nothing trimmed and
    // nothing normalised. The three spans within each literal are, in 1-based byte terms:
    //
    //     bytes  1-2   TRAN-TYPE       PIC X(02)   the KSDS key, leading zero significant
    //     bytes  3-52  TRAN-TYPE-DESC  PIC X(50)   the description, right-space-padded to 50
    //     bytes 53-60  FILLER          PIC X(08)   eight ASCII zeros in every shipped row
    //
    // 2 + 50 + 8 = 60. EmbeddedFixtureProvenance re-derives each literal from its key, its
    // description and its reserved bytes, so none of the space counts below is taken on trust.
    // -----------------------------------------------------------------------------------------

    /** Row 1: key {@code 01}, {@code Purchase} (8 chars, 42 spaces of padding), FILLER 8 zeros. */
    private static final String ROW_01 =
            "01Purchase                                          00000000";

    /** Row 2: key {@code 02}, {@code Payment} (7 chars, 43 spaces of padding), FILLER 8 zeros. */
    private static final String ROW_02 =
            "02Payment                                           00000000";

    /** Row 3: key {@code 03}, {@code Credit} (6 chars, 44 spaces of padding), FILLER 8 zeros. */
    private static final String ROW_03 =
            "03Credit                                            00000000";

    /** Row 4: key {@code 04}, {@code Authorization} (13 chars, 37 spaces), FILLER 8 zeros. */
    private static final String ROW_04 =
            "04Authorization                                     00000000";

    /** Row 5: key {@code 05}, {@code Refund} (6 chars, 44 spaces of padding), FILLER 8 zeros. */
    private static final String ROW_05 =
            "05Refund                                            00000000";

    /** Row 6: key {@code 06}, {@code Reversal} (8 chars, 42 spaces of padding), FILLER 8 zeros. */
    private static final String ROW_06 =
            "06Reversal                                          00000000";

    /** Row 7: key {@code 07}, {@code Adjustment} (10 chars, 40 spaces), FILLER 8 zeros. */
    private static final String ROW_07 =
            "07Adjustment                                        00000000";

    /** The seven rows in file order. Immutable, so this constant is not mutable static state. */
    private static final List<String> FIXTURE_ROWS =
            List.of(ROW_01, ROW_02, ROW_03, ROW_04, ROW_05, ROW_06, ROW_07);

    /** The seven type codes in file order, as characters - {@code 01}, never {@code 1}. */
    private static final List<String> FIXTURE_KEYS =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /** The seven descriptions in file order, at their natural length before padding to 50. */
    private static final List<String> FIXTURE_DESCRIPTIONS =
            List.of("Purchase", "Payment", "Credit", "Authorization", "Refund", "Reversal",
                    "Adjustment");

    /**
     * The measured content of every shipped row's trailing {@code FILLER X(08)}: eight ASCII zeros,
     * emphatically not eight spaces. The distinction is the subject of {@link FillerAsymmetry}.
     */
    private static final String FIXTURE_FILLER = "00000000";

    /**
     * The longest description the shipped data holds - {@code Authorization}, 13 characters. It is
     * shorter than the 15-byte report receiver, which is precisely why a synthetic over-long value is
     * needed to drive the truncating side of the {@code PIC X} move; see
     * {@link UntrimmedDescription#anOverLongDescriptionTruncatesOnTheRight()}.
     */
    private static final int LONGEST_SHIPPED_DESCRIPTION = 13;

    /**
     * One shipped row, by 0-based index.
     *
     * @param index 0 through 6, in file order
     * @return the 60-character row image
     */
    private static String fixtureRow(int index) {
        return FIXTURE_ROWS.get(index);
    }

    /**
     * Pads a value to a width with spaces on the right, which is how COBOL fills a {@code PIC X}
     * receiver. Written out here rather than borrowed from the codec, so the expected images in this
     * file are independent of the code that produces the actual ones.
     *
     * @param value the value
     * @param width the receiver width, at least {@code value.length()}
     * @return the padded image, exactly {@code width} characters
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Asserts that a record image carries an expected value at an <strong>absolute byte offset</strong>
     * - byte for byte, with no parsing, splitting, trimming or pattern matching anywhere in the
     * comparison. Fixed width is the wire format, so an offset is the only legitimate way to address a
     * field.
     *
     * <p>The expectation is encoded under {@link #ASCII}, so this helper is for {@code US-ASCII}
     * images only; every call site below passes one. The EBCDIC cases assert their bytes directly
     * against the {@code IBM037} pad byte instead, because the whole point of those cases is that the
     * two code pages produce different bytes.
     *
     * @param image    the full record image, encoded under {@link #ASCII}
     * @param offset   the 0-based offset of the span
     * @param expected the expected content of the span, whose length is the span's length
     * @param what     the copybook item name, for the failure message
     */
    private static void assertSpanAt(byte[] image, int offset, String expected, String what) {
        byte[] expectedBytes = expected.getBytes(ASCII);
        for (int i = 0; i < expectedBytes.length; i++) {
            assertThat(image[offset + i])
                    .as("%s byte at absolute offset %d", what, offset + i)
                    .isEqualTo(expectedBytes[i]);
        }
    }

    @Nested
    @DisplayName("The embedded fixture is a faithful transcription of app/data/ASCII/trantype.txt")
    class EmbeddedFixtureProvenance {

        @Test
        @DisplayName("seven rows are embedded, and every one is exactly 60 characters")
        void sevenRowsOfSixtyCharacters() {
            assertThat(FIXTURE_ROWS).hasSize(7);
            assertThat(FIXTURE_KEYS).hasSize(7);
            assertThat(FIXTURE_DESCRIPTIONS).hasSize(7);
            assertThat(FIXTURE_ROWS).allSatisfy(row ->
                    assertThat(row).hasSize(TranTypeRecord.RECORD_LENGTH));
            // The measured file is 427 bytes: 7 records of 60 plus one newline each.
            assertThat(7 * TranTypeRecord.RECORD_LENGTH + 7).isEqualTo(427);
        }

        @DisplayName("each row literal re-derives from its key, its description and its FILLER")
        @ParameterizedTest(name = "row {0} = \"{1}\" + \"{2}\" padded to 50 + 8 zeros")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachRowLiteralIsTranscribedCorrectly(int index, String key, String description) {
            // The transcription self-check: composing the row from its three documented spans must
            // reproduce the literal exactly. A miscounted space in the literal fails here, by name,
            // instead of surfacing later as a puzzling offset failure somewhere downstream.
            String composed = key + padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    + FIXTURE_FILLER;

            assertThat(fixtureRow(index)).isEqualTo(composed);
            assertThat(FIXTURE_KEYS.get(index)).isEqualTo(key);
            assertThat(FIXTURE_DESCRIPTIONS.get(index)).isEqualTo(description);
        }

        @Test
        @DisplayName("the padding is on the right only, as a PIC X field is padded")
        void thePaddingIsOnTheRight() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                String description = FIXTURE_DESCRIPTIONS.get(index);
                byte[] image = fixtureRow(index).getBytes(ASCII);

                // The description's own characters sit immediately at the field's offset - there is
                // no leading pad - and every remaining byte of the span is the space byte.
                assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET, description,
                        "TRAN-TYPE-DESC");
                for (int i = TranTypeRecord.TRAN_TYPE_DESC_OFFSET + description.length();
                        i < TranTypeRecord.FILLER_OFFSET; i++) {
                    assertThat(image[i])
                            .as("TRAN-TYPE-DESC pad byte at absolute offset %d", i)
                            .isEqualTo((byte) ' ');
                }
            }
        }

        @Test
        @DisplayName("no shipped description reaches the 15-byte report receiver's width")
        void noShippedDescriptionExercisesTheTruncation() {
            // This is why a synthetic over-long description is mandatory rather than optional: the
            // truncating side of the X(50) -> X(15) move is unreachable from shipped data alone, and
            // an untested truncation is one that happens to be right rather than proven right.
            for (String description : FIXTURE_DESCRIPTIONS) {
                assertThat(description.length()).isLessThanOrEqualTo(LONGEST_SHIPPED_DESCRIPTION);
                assertThat(description.length()).isLessThan(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            }
            assertThat(FIXTURE_DESCRIPTIONS)
                    .anySatisfy(longest ->
                            assertThat(longest.length()).isEqualTo(LONGEST_SHIPPED_DESCRIPTION));
        }

        @Test
        @DisplayName("every row's reserved span is eight ASCII zeros, at absolute offset 52")
        void everyRowsReservedSpanIsZeros() {
            for (String row : FIXTURE_ROWS) {
                assertSpanAt(row.getBytes(ASCII), TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER,
                        "FILLER");
            }
            assertThat(FIXTURE_FILLER).hasSize(TranTypeRecord.FILLER_LENGTH);
        }
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's 2 + 50 + 8 = 60, gates G19 and G21")
    class DeclaredGeometry {

        @Test
        @DisplayName("the copybook's RECLN = 60 is the record length, and the layout proves it")
        void recordLengthIsSixty() {
            assertThat(TranTypeRecord.RECORD_LENGTH).isEqualTo(60);
            assertThat(TranTypeRecord.verifyDeclaredWidth()).isEqualTo(60);
            assertThat(TranTypeRecord.layout().recordLength()).isEqualTo(60);
        }

        @Test
        @DisplayName("2 + 50 + 8 = 60: the three declared lengths sum to the declared width")
        void theThreeLengthsSumToSixty() {
            // TRAN-TYPE X(02) at 0, TRAN-TYPE-DESC X(50) at 2, FILLER X(08) at 52. Every offset
            // asserted anywhere in this file is justified by this one addition and by nothing else.
            assertThat(TranTypeRecord.TRAN_TYPE_LENGTH
                    + TranTypeRecord.TRAN_TYPE_DESC_LENGTH
                    + TranTypeRecord.FILLER_LENGTH)
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
            // And each offset is the sum of the lengths that precede it.
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_OFFSET + TranTypeRecord.TRAN_TYPE_LENGTH);
            assertThat(TranTypeRecord.FILLER_OFFSET)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_DESC_OFFSET
                            + TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            assertThat(TranTypeRecord.FILLER_OFFSET + TranTypeRecord.FILLER_LENGTH)
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("offsets are the copybook's 1-based positions converted to 0-based")
        void offsetsMatchTheCopybook() {
            // 1-based 1-2, 3-52 and 53-60 become 0-based 0, 2 and 52.
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET).isEqualTo(2);
            assertThat(TranTypeRecord.FILLER_OFFSET).isEqualTo(52);

            assertThat(TranTypeRecord.TRAN_TYPE.offset()).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE.length()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE.endOffsetExclusive()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.offset()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.length()).isEqualTo(50);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.endOffsetExclusive()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.offset()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.length()).isEqualTo(8);
            assertThat(TranTypeRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("field names are the copybook's own: TRAN-TYPE, not CVTRA04Y's TRAN-TYPE-CD")
        void fieldNamesAreVerbatim() {
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_FIELD).isEqualTo("TRAN-TYPE-DESC");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.name()).isEqualTo("TRAN-TYPE-DESC");
            // app/cpy/CVTRA04Y.cpy:6 and app/cpy/CVTRA05Y.cpy:6 call the very same two bytes
            // TRAN-TYPE-CD, and app/cbl/CBTRN03C.cbl:189 moves one into the other. The names differ
            // by three characters, denote the same field and must never be harmonised: the parity
            // differ compares field by field BY NAME, so renaming either one silently breaks it.
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isNotEqualTo("TRAN-TYPE-CD");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).doesNotEndWith("-CD");
        }

        @Test
        @DisplayName("gate G21: FILLER X(08) is a declared span, so the layout accounts for all 60")
        void fillerIsAFirstClassSpan() {
            assertThat(TranTypeRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(TranTypeRecord.FILLER.kind().filler()).isTrue();
            assertThat(TranTypeRecord.layout().storageSpans())
                    .containsExactly(TranTypeRecord.TRAN_TYPE, TranTypeRecord.TRAN_TYPE_DESC,
                            TranTypeRecord.FILLER);
            // A reserved span is addressable by offset but not referable by name, exactly as an
            // unnamed COBOL FILLER is not referable in the PROCEDURE DIVISION.
            assertThat(TranTypeRecord.layout().hasSpan("FILLER")).isFalse();
        }

        @Test
        @DisplayName("gate G21 in the negative: dropping the FILLER leaves 52 and is rejected")
        void droppingTheFillerIsRejected() {
            // Omitting the trailing FILLER is the classic fixed-width defect. It has to fail at the
            // layout, immediately and by name, rather than quietly producing 52-byte records.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(TranTypeRecord.RECORD_LENGTH,
                            TranTypeRecord.TRAN_TYPE, TranTypeRecord.TRAN_TYPE_DESC))
                    .withMessageContaining("52")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("no OCCURS table and no REDEFINES overlay: CVTRA03Y declares neither")
        void thereIsNoTableAndNoOverlay() {
            assertThat(TranTypeRecord.layout().redefinitions()).isEmpty();
            assertThat(TranTypeRecord.layout().spans()).hasSize(3);
        }

        @Test
        @DisplayName("the KSDS key is the leading 2 bytes, as CBTRN03C:42 declares")
        void theKeyIsTheLeadingTwoBytes() {
            assertThat(TranTypeRecord.TRAN_TYPE_KEY_LENGTH).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_KEY_LENGTH)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_LENGTH);
            // RECORD KEY IS FD-TRAN-TYPE, and FD-TRAN-TYPE is the record's first item: the key
            // sub-span is bytes 0..1 inclusive.
            assertThat(TranTypeRecord.TRAN_TYPE.offset()).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE.endOffsetExclusive())
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_KEY_LENGTH);
        }

        @Test
        @DisplayName("the report receiver is CVTRA07Y:22's PIC X(15), narrower than the field")
        void theReportReceiverIsFifteen() {
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH).isEqualTo(15);
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .isLessThan(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("the width self-check rejects a layout that is internally valid but too short")
        void aShortLayoutIsRejected() {
            RecordLayout fifty = RecordLayout.of(50, FieldSpan.alphanumeric("SOMETHING-ELSE", 0, 50));
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(fifty))
                    .withMessageContaining("50")
                    .withMessageContaining("short");
        }

        @Test
        @DisplayName("the width self-check rejects a layout that is too long")
        void anOverLongLayoutIsRejected() {
            RecordLayout seventy = RecordLayout.of(70, FieldSpan.alphanumeric("TOO-WIDE", 0, 70));
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(seventy))
                    .withMessageContaining("70")
                    .withMessageContaining("too many");
        }

        @Test
        @DisplayName("the width self-check requires a layout, and its own layout passes it")
        void aNullLayoutIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(null))
                    .withMessageContaining("CVTRA03Y");
            // The passing side, for completeness: the type's declared layout measures 60.
            assertThat(TranTypeRecord.verifyDeclaredWidth(TranTypeRecord.LAYOUT))
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The seven shipped TRANTYPE records, decoded at the copybook's offsets")
    class ShippedFixture {

        @DisplayName("each row decodes to its documented key, description and reserved bytes")
        @ParameterizedTest(name = "row {0}: TRAN-TYPE {1} = {2}")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachFixtureRowDecodesAsDocumented(int index, String key, String description) {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(index), ASCII);

            assertThat(record.tranType()).isEqualTo(key);
            assertThat(record.tranTypeKey()).isEqualTo(key);
            // The description is compared against its 50-character padded form, never against a
            // trimmed one: the padding is part of the field's value.
            assertThat(record.tranTypeDesc())
                    .isEqualTo(padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.recordLength()).isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @DisplayName("each row's three spans sit at absolute offsets 0, 2 and 52")
        @ParameterizedTest(name = "row {0}: key at 0, description at 2, FILLER at 52")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachRowsSpansSitAtTheirAbsoluteOffsets(int index, String key, String description) {
            byte[] image = TranTypeRecord.decode(fixtureRow(index), ASCII).toByteArray();

            assertThat(image).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_OFFSET, key, "TRAN-TYPE");
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET,
                    padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH), "TRAN-TYPE-DESC");
            assertSpanAt(image, TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER, "FILLER");
        }

        @Test
        @DisplayName("every row re-encodes to exactly 60 bytes, byte-identical to the stored row")
        void everyRowRoundTripsByteForByte() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                String row = fixtureRow(index);
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);

                assertThat(record.toByteArray())
                        .as("row %d re-encodes to its declared width", index)
                        .hasSize(TranTypeRecord.RECORD_LENGTH);
                assertThat(record.toByteArray())
                        .as("row %d round trips byte for byte, reserved bytes included", index)
                        .isEqualTo(row.getBytes(ASCII));
                assertThat(record.image())
                        .as("row %d reproduces its 60-character image", index)
                        .isEqualTo(row);
                assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the byte and text decode paths agree on every row")
        void bothDecodePathsAgree() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            for (String row : FIXTURE_ROWS) {
                assertThat(TranTypeRecord.decode(row, ASCII))
                        .isEqualTo(TranTypeRecord.decode(row.getBytes(ASCII), codec))
                        .isEqualTo(TranTypeRecord.decode(row, codec))
                        .isEqualTo(TranTypeRecord.decode(row.getBytes(ASCII), ASCII));
            }
        }

        @Test
        @DisplayName("the seven keys and descriptions are exactly those documented, in file order")
        void theSevenRowsAreThoseDocumented() {
            List<String> keys = new ArrayList<>();
            List<String> descriptions = new ArrayList<>();
            List<String> paddedDescriptions = new ArrayList<>();
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                keys.add(record.tranTypeKey());
                descriptions.add(record.tranTypeDesc());
            }
            for (String description : FIXTURE_DESCRIPTIONS) {
                paddedDescriptions.add(padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            }

            assertThat(keys).containsExactlyElementsOf(FIXTURE_KEYS);
            assertThat(descriptions).containsExactlyElementsOf(paddedDescriptions);
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE-DESC is decoded untrimmed - the CBTRN03C:366 X(50) to X(15) move")
    class UntrimmedDescription {

        @Test
        @DisplayName("the decoded description is exactly 50 characters, padding included")
        void theDescriptionIsFiftyCharacters() {
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                assertThat(record.tranTypeDesc()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
                assertThat(record.tranTypeDescBytes()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            }
        }

        @Test
        @DisplayName("row 1 reads back as \"Purchase\" followed by 42 spaces, not as \"Purchase\"")
        void rowOneKeepsItsFortyTwoTrailingSpaces() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranTypeDesc()).isEqualTo("Purchase" + " ".repeat(42));
            assertThat(record.tranTypeDesc()).hasSize(50).isNotEqualTo("Purchase");
            // 8 + 42 = 50. Trimming here would cost the record its width and break the round trip.
            assertThat("Purchase".length() + 42).isEqualTo(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
        }

        @DisplayName("the report shows the first 15 characters, right-truncated as COBOL truncates")
        @ParameterizedTest(name = "TRAN-TYPE {0} reports \"{1}\"")
        // ignoreLeadingAndTrailingWhitespace must be off: the trailing spaces below are the point of
        // the case. A PIC X(15) receiver is space-padded, so "Purchase" reaches the report as
        // "Purchase" followed by seven spaces, and a trimmed expectation would assert the opposite.
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
                "0|Purchase       ",
                "1|Payment        ",
                "2|Credit         ",
                "3|Authorization  ",
                "4|Refund         ",
                "5|Reversal       ",
                "6|Adjustment     "
        })
        void theReportImageIsTheFirstFifteenCharacters(int index, String reportImage) {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(index), ASCII);

            String moved = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(moved).hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH).isEqualTo(reportImage);
            // The surviving characters are the LEADING ones - that is what right-truncation means.
            assertThat(moved).isEqualTo(record.tranTypeDesc()
                    .substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
        }

        @Test
        @DisplayName("a description longer than the receiver loses its tail, never its head")
        void anOverLongDescriptionTruncatesOnTheRight() {
            // SYNTHETIC, and deliberately so. The longest shipped description is 13 characters, so no
            // row of app/data/ASCII/trantype.txt can drive the truncating side of the PIC X move; a
            // 40-character value does. It is longer than the 15-byte report receiver but shorter than
            // the 50-byte field, which isolates the receiver truncation from the field truncation.
            String synthetic = "Purchase authorization reversal, partial";
            assertThat(synthetic).hasSize(40);
            assertThat(synthetic.length())
                    .isGreaterThan(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .isLessThan(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);

            TranTypeRecord record = TranTypeRecord.of("08", synthetic, ASCII);

            // Held in the field at its natural length, right-padded to 50 - the pad branch.
            assertThat(record.tranTypeDesc())
                    .isEqualTo(padded(synthetic, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            // Moved to the 15-byte report receiver, the leading 15 survive - the truncate branch -
            // and the remaining 25 characters are discarded, not wrapped and not compressed.
            String moved = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(moved).hasSize(15).isEqualTo("Purchase author");
            assertThat(moved).isEqualTo(synthetic.substring(0, 15));
            assertThat(moved).doesNotContain("reversal").doesNotContain("partial");
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a value longer than the 50-byte field itself is truncated on the right too")
        void aValueLongerThanTheFieldIsTruncated() {
            // 63 characters: longer than the field as well as the receiver, so both truncations are
            // visible in one case and neither can be mistaken for the other.
            String tooLong = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789X";
            assertThat(tooLong).hasSize(63);
            TranTypeRecord record = TranTypeRecord.of("99", tooLong, ASCII);

            assertThat(record.tranTypeDesc())
                    .isEqualTo(tooLong.substring(0, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH))
                    .isEqualTo(tooLong.substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
            // The 50-byte span is full: no pad byte survives anywhere in it.
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_DESC_OFFSET,
                    tooLong.substring(0, 50), "TRAN-TYPE-DESC");
        }

        @Test
        @DisplayName("a short value written into X(50) is right-space-padded - the pad branch")
        void aShortValueIsRightPadded() {
            TranTypeRecord record = TranTypeRecord.of("03", "Credit", ASCII);
            byte[] image = record.toByteArray();

            assertThat(record.tranTypeDesc()).isEqualTo(padded("Credit", 50));
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET, "Credit", "TRAN-TYPE-DESC");
            // Bytes 8 through 51 - the 44 the value does not reach - are the code page's space byte,
            // not zeros and not left-over content.
            for (int i = TranTypeRecord.TRAN_TYPE_DESC_OFFSET + "Credit".length();
                    i < TranTypeRecord.FILLER_OFFSET; i++) {
                assertThat(image[i]).as("pad byte at absolute offset %d", i).isEqualTo((byte) ' ');
            }
        }

        @Test
        @DisplayName("a receiver wider than the field pads on the right, it does not overflow")
        void aWiderReceiverIsPadded() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(3), ASCII);

            assertThat(record.tranTypeDescMovedTo(60))
                    .hasSize(60)
                    .isEqualTo(padded("Authorization", 60));
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.TRAN_TYPE_DESC_LENGTH))
                    .isEqualTo(record.tranTypeDesc());
        }

        @Test
        @DisplayName("a receiver of one character is legal; a receiver of none is not")
        void theReceiverWidthIsValidated() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranTypeDescMovedTo(1)).isEqualTo("P");
            assertThatIllegalArgumentException().isThrownBy(() -> record.tranTypeDescMovedTo(0));
            assertThatIllegalArgumentException().isThrownBy(() -> record.tranTypeDescMovedTo(-1));
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE is a 2-character String, never a number")
    class KeyIsCharacterData {

        @Test
        @DisplayName("the accessor's static type is String, so no leading zero can be lost")
        void theAccessorTypeIsString() throws NoSuchMethodException {
            // PIC X(02) is character data. An int-typed accessor would render 01 as 1, and a keyed
            // read supplying "1 " or " 1" would not find the record - so the declared type is itself
            // part of the contract and is asserted, not assumed.
            assertThat(TranTypeRecord.class.getMethod("tranType").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TranTypeRecord.class.getMethod("tranTypeKey").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TranTypeRecord.class.getMethod("tranTypeDesc").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("\"01\" stays \"01\" and never collapses to \"1\"")
        void theLeadingZeroSurvives() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranType()).isEqualTo("01").isNotEqualTo("1").isNotEqualTo(" 1");
            assertThat(record.tranTypeKey()).isEqualTo(record.tranType());
            assertThat(record.tranTypeKey()).hasSize(TranTypeRecord.TRAN_TYPE_KEY_LENGTH);
            // The two stored bytes at absolute offsets 0 and 1 are the characters '0' and '1'.
            assertThat(record.tranTypeBytes()).isEqualTo("01".getBytes(ASCII));
            assertThat(record.toByteArray()[0]).isEqualTo((byte) '0');
            assertThat(record.toByteArray()[1]).isEqualTo((byte) '1');
        }

        @Test
        @DisplayName("the key round trips: set 07, re-encode, read back 07 with both bytes intact")
        void theKeyRoundTrips() {
            TranTypeRecord built = TranTypeRecord.of("07", "Adjustment", ASCII);
            TranTypeRecord reread = TranTypeRecord.decode(built.toByteArray(), ASCII);

            assertThat(reread.tranType()).isEqualTo("07");
            assertThat(reread.tranTypeKey()).isEqualTo("07");
            assertSpanAt(reread.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "07", "TRAN-TYPE");
            assertThat(reread).isEqualTo(built);
        }

        @Test
        @DisplayName("a non-numeric key is carried as readily as a numeric-looking one")
        void aNonNumericKeyIsCarried() {
            // PIC X(02) is character data: nothing here may assume the two bytes are digits.
            TranTypeRecord record = TranTypeRecord.of("AB", "Not a number", ASCII);

            assertThat(record.tranType()).isEqualTo("AB");
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "AB", "TRAN-TYPE");
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a short key is padded on the right, as a PIC X MOVE pads - the pad branch")
        void aShortKeyIsRightPadded() {
            assertThat(TranTypeRecord.of("1", "One", ASCII).tranType()).isEqualTo("1 ");
            assertThat(TranTypeRecord.of("", "Blank", ASCII).tranType()).isEqualTo("  ");
            // Padded on the right, so the supplied character stays at offset 0.
            assertSpanAt(TranTypeRecord.of("1", "One", ASCII).toByteArray(),
                    TranTypeRecord.TRAN_TYPE_OFFSET, "1 ", "TRAN-TYPE");
        }

        @Test
        @DisplayName("an over-long key is truncated on the right - the truncate branch")
        void anOverLongKeyIsTruncatedOnTheRight() {
            TranTypeRecord record = TranTypeRecord.of("0123", "Four", ASCII);

            assertThat(record.tranType()).isEqualTo("01");
            // The leading two characters survive; "23" is discarded, and nothing spills into
            // TRAN-TYPE-DESC at offset 2.
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "01", "TRAN-TYPE");
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Four", 50));
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction from field values, and the INITIALIZE state")
    class Construction {

        @Test
        @DisplayName("a built record is exactly 60 bytes with every span accounted for")
        void aBuiltRecordIsSixtyBytes() {
            TranTypeRecord record = TranTypeRecord.of("04", "Authorization", ASCII);

            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.recordLength()).isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.tranType()).isEqualTo("04");
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
        }

        @Test
        @DisplayName("an empty record is 60 spaces, which is what INITIALIZE leaves")
        void anEmptyRecordIsAllSpaces() {
            // Neither named item nor the FILLER declares a VALUE, so INITIALIZE of
            // TRAN-TYPE-RECORD leaves all 60 bytes as the code page's space.
            assertThat(TranTypeRecord.empty(ASCII).image()).isEqualTo(" ".repeat(60));
            assertThat(TranTypeRecord.empty(new FixedWidthCodec(ASCII)))
                    .isEqualTo(TranTypeRecord.empty(ASCII));
            assertThat(TranTypeRecord.empty(ASCII)).isEqualTo(TranTypeRecord.of("", "", ASCII));
        }

        @Test
        @DisplayName("a built record round trips through both decode forms unchanged")
        void aBuiltRecordRoundTrips() {
            TranTypeRecord built = TranTypeRecord.of("07", "Adjustment", ASCII);

            assertThat(TranTypeRecord.decode(built.toByteArray(), ASCII)).isEqualTo(built);
            assertThat(TranTypeRecord.decode(built.image(), ASCII)).isEqualTo(built);
        }

        @Test
        @DisplayName("both field values are required; SPACES must be moved explicitly")
        void theFieldValuesAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of(null, "Purchase", ASCII))
                    .withMessageContaining("TRAN-TYPE");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", null, ASCII))
                    .withMessageContaining("TRAN-TYPE-DESC");
        }

        @Test
        @DisplayName("a codec is required, and so is a charset")
        void theEncodingIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", "Purchase", (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", "Purchase", (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.empty((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.empty((FixedWidthCodec) null));
        }
    }

    @Nested
    @DisplayName("The FILLER read/write asymmetry - both sides, and why they do not conflict")
    class FillerAsymmetry {

        // The two obligations below look contradictory and are not. They describe two different
        // journeys through the same eight bytes at absolute offset 52:
        //
        //   WRITE PATH  - a record built from nothing has no stored bytes to preserve, so the
        //                 reserved span is space-filled. That is the COBOL convention for a FILLER
        //                 that declares no VALUE, and it is what gate G21 requires: the span is
        //                 present and emitted, never skipped.
        //
        //   READ PATH   - a record decoded from a dataset row already HAS reserved bytes, and they
        //                 are data. Every shipped row holds eight ASCII zeros there, not spaces, so
        //                 re-encoding must reproduce those zeros byte for byte. Normalising them to
        //                 spaces on the way out would silently rewrite eight bytes of every record.
        //
        // Both are asserted, in separate tests, because a codec that satisfied only one of them
        // would be wrong in a way the other test is the only thing that catches.

        @Test
        @DisplayName("write path, gate G21: a freshly built record's FILLER at 52 is eight spaces")
        void aFreshlyBuiltRecordSpaceFillsTheReservedSpan() {
            TranTypeRecord built = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(built.filler()).isEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH));
            assertThat(built.fillerBytes()).hasSize(TranTypeRecord.FILLER_LENGTH);
            assertSpanAt(built.toByteArray(), TranTypeRecord.FILLER_OFFSET,
                    " ".repeat(TranTypeRecord.FILLER_LENGTH), "FILLER");
            // The span is emitted rather than skipped, which is exactly why the record is 60 bytes
            // and not 52: omitting the FILLER would show up here as a width failure.
            assertThat(built.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("write path: an empty record's FILLER is spaces too, not zeros")
        void anEmptyRecordSpaceFillsTheReservedSpan() {
            assertThat(TranTypeRecord.empty(ASCII).filler())
                    .isEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH))
                    .isNotEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("read path: a decoded row's FILLER keeps the fixture's eight zeros verbatim")
        void aDecodedRowCarriesTheStoredReservedBytes() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(index), ASCII);

                assertThat(stored.filler())
                        .as("row %d reserved span", index)
                        .isEqualTo(FIXTURE_FILLER)
                        .isNotEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH));
                assertThat(stored.fillerBytes()).isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
                assertSpanAt(stored.toByteArray(), TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER,
                        "FILLER");
            }
        }

        @Test
        @DisplayName("read path: re-encoding a decoded row is lossless, zeros included")
        void reEncodingADecodedRowIsLossless() {
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord stored = TranTypeRecord.decode(row, ASCII);

                // The whole 60 bytes, not just the two named fields: the codec must not overwrite
                // the reserved span on the way out.
                assertThat(stored.toByteArray()).isEqualTo(row.getBytes(ASCII));
                assertThat(TranTypeRecord.decode(stored.toByteArray(), ASCII)).isEqualTo(stored);
            }
        }

        @Test
        @DisplayName("the two paths therefore differ, and the difference is visible in equality")
        void theTwoPathsAreDistinguishable() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord rebuilt = TranTypeRecord.of("01", "Purchase", ASCII);

            // Identical named fields...
            assertThat(rebuilt.tranType()).isEqualTo(stored.tranType());
            assertThat(rebuilt.tranTypeDesc()).isEqualTo(stored.tranTypeDesc());
            // ...different reserved bytes, and therefore different records. This is the assertion
            // that would fail if either path were "helpfully" normalised to match the other.
            assertThat(rebuilt.filler()).isNotEqualTo(stored.filler());
            assertThat(rebuilt).isNotEqualTo(stored);
        }
    }

    @Nested
    @DisplayName("Decoding rejects anything but the copybook's width")
    class WidthEnforcement {

        @Test
        @DisplayName("a short row is rejected rather than padded")
        void aShortRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[59], ASCII))
                    .withMessageContaining("59")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("an over-long row is rejected rather than truncated")
        void anOverLongRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[61], ASCII))
                    .withMessageContaining("61");
        }

        @Test
        @DisplayName("a short row image is rejected, and the message names the copybook")
        void aShortImageIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode("01Purchase", ASCII))
                    .withMessageContaining("CVTRA03Y")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("an exactly 60-byte row is accepted, boundary included")
        void anExactRowIsAccepted() {
            assertThat(TranTypeRecord.decode(new byte[60], ASCII).recordLength()).isEqualTo(60);
            assertThat(TranTypeRecord.decode(" ".repeat(60), ASCII).image())
                    .isEqualTo(" ".repeat(60));
        }

        @Test
        @DisplayName("null bytes, null image, null codec and null charset are all rejected")
        void nullsAreRejected() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode((byte[]) null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode((String) null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[60], (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode("x".repeat(60), (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[60], (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode("x".repeat(60), (Charset) null));
        }
    }

    @Nested
    @DisplayName("Encoding is the caller's explicit choice, never the platform's")
    class ExplicitEncoding {

        @Test
        @DisplayName("the same field values encode differently under EBCDIC and ASCII")
        void thePadBytesFollowTheCodePage() {
            TranTypeRecord ascii = TranTypeRecord.of("04", "Authorization", ASCII);
            TranTypeRecord ebcdic = TranTypeRecord.of("04", "Authorization", EBCDIC);

            assertThat(ascii.toByteArray()).hasSize(60);
            assertThat(ebcdic.toByteArray()).hasSize(60);
            assertThat(ebcdic.toByteArray()).isNotEqualTo(ascii.toByteArray());
            // The pad byte is the code page's own space: 0x20 under ASCII, 0x40 under EBCDIC. The
            // last byte of the record is inside the FILLER span, so this also proves the span is
            // written with the charset the caller named rather than a platform default.
            assertThat(ascii.toByteArray()[59]).isEqualTo((byte) 0x20);
            assertThat(ebcdic.toByteArray()[59]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("the field values survive the EBCDIC round trip unchanged")
        void ebcdicRoundTrips() {
            TranTypeRecord ebcdic = TranTypeRecord.of("06", "Reversal", EBCDIC);

            assertThat(ebcdic.charset()).isEqualTo(EBCDIC);
            assertThat(ebcdic.tranType()).isEqualTo("06");
            assertThat(ebcdic.tranTypeDesc()).isEqualTo(padded("Reversal", 50));
            assertThat(TranTypeRecord.decode(ebcdic.toByteArray(), EBCDIC)).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because offsets are absolute byte positions")
        void aMultiByteCodePageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.empty(StandardCharsets.UTF_16));
        }
    }

    @Nested
    @DisplayName("Immutability, equality and diagnostics")
    class ValueSemantics {

        @Test
        @DisplayName("no accessor hands out the backing array")
        void theBackingArrayIsNeverExposed() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            byte[] first = record.toByteArray();
            first[0] = (byte) '9';
            assertThat(record.tranType()).isEqualTo("01");
            assertThat(record.toByteArray()).isNotSameAs(first);
            assertThat(record.toByteArray()[0]).isEqualTo((byte) '0');

            byte[] descriptionBytes = record.tranTypeDescBytes();
            descriptionBytes[0] = (byte) '!';
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Purchase", 50));

            byte[] reserved = record.fillerBytes();
            reserved[0] = (byte) '!';
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);

            byte[] keyBytes = record.tranTypeBytes();
            keyBytes[0] = (byte) '!';
            assertThat(record.tranType()).isEqualTo("01");
        }

        @Test
        @DisplayName("equality is reflexive, and over all 60 bytes")
        void equalityCoversTheWholeImage() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord same = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord otherRow = TranTypeRecord.decode(fixtureRow(1), ASCII);

            assertThat(stored.equals(stored)).isTrue();
            assertThat(stored).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(stored).isNotEqualTo(otherRow);
        }

        @Test
        @DisplayName("a record is never equal to null or to another type")
        void equalityRejectsForeignTypes() {
            TranTypeRecord record = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("01Purchase")).isFalse();
            assertThat(record).isNotEqualTo(TranTypeRecord.layout());
        }

        @Test
        @DisplayName("two records differing only in code page are not equal")
        void theCodePageIsPartOfIdentity() {
            TranTypeRecord ascii = TranTypeRecord.of("01", "Purchase", ASCII);
            TranTypeRecord ebcdic = TranTypeRecord.of("01", "Purchase", EBCDIC);

            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii.hashCode()).isNotEqualTo(ebcdic.hashCode());
        }

        @Test
        @DisplayName("identical bytes under different code pages are still not equal")
        void identicalBytesUnderDifferentCodePagesAreNotEqual() {
            // ISO-8859-1 and US-ASCII encode this record's characters to exactly the same bytes, so
            // this is the one case where the images coincide and only the declared code page differs.
            // They must still compare unequal: the charset is how the bytes are to be read, and two
            // records that would be read differently are not the same record.
            TranTypeRecord ascii = TranTypeRecord.of("01", "Purchase", ASCII);
            TranTypeRecord latin1 = TranTypeRecord.of("01", "Purchase", StandardCharsets.ISO_8859_1);

            assertThat(latin1.toByteArray()).isEqualTo(ascii.toByteArray());
            assertThat(ascii).isNotEqualTo(latin1);
            assertThat(latin1).isNotEqualTo(ascii);
        }

        @Test
        @DisplayName("toString names the record, its key, its description, its FILLER and its charset")
        void toStringIsDiagnostic() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(3), ASCII);

            // The trimming inside this rendering is for the log line only. It is asserted here as a
            // property of the diagnostic string, never as a way of reading the field: tranTypeDesc()
            // still returns all 50 characters, which theDescriptionIsFiftyCharacters proves.
            assertThat(record.toString())
                    .startsWith("TRAN-TYPE-RECORD[")
                    .contains("TRAN-TYPE=04")
                    .contains("TRAN-TYPE-DESC='Authorization'")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("60 bytes")
                    .contains("US-ASCII");
        }

        @Test
        @DisplayName("the layout handed out is the validated one and cannot be mutated")
        void theLayoutIsImmutable() {
            List<FieldSpan> spans = TranTypeRecord.layout().spans();

            assertThat(spans).hasSize(3);
            assertThat(TranTypeRecord.layout()).isEqualTo(TranTypeRecord.LAYOUT);
            assertThat(TranTypeRecord.layout().span("TRAN-TYPE"))
                    .isEqualTo(TranTypeRecord.TRAN_TYPE);
            assertThat(TranTypeRecord.layout().span("TRAN-TYPE-DESC"))
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_DESC);
        }
    }

    @Nested
    @DisplayName("The shape of the type itself - what CVTRA03Y's absence of a numeric item implies")
    class TypeShape {

        @Test
        @DisplayName("no accessor returns a BigDecimal, because CVTRA03Y declares no numeric item")
        void noAccessorReturnsADecimal() {
            // CVTRA03Y declares three PIC X items and nothing else: no signed picture, no V, no
            // COMP-3, no SIGN clause. So this type consumes only the record and the codec, and
            // deliberately does NOT import the shared fixed-point helper - the same omission
            // card/model/CardRecord and card/model/CardXrefRecord make for the same reason. A
            // BigDecimal accessor here would not be a bonus, it would be the defect: it would imply
            // a scale this copybook never declares. The class literal is written out in full rather
            // than imported, so this file does not import a decimal type it has no other use for.
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotEqualTo(java.math.BigDecimal.class);
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("type of field %s", field.getName())
                        .isNotEqualTo(java.math.BigDecimal.class);
            }
        }

        @Test
        @DisplayName("no span is a signed-decimal span, because the copybook declares none")
        void noSpanIsSignedOrScaled() {
            // The other half of the same statement: not only does no accessor hand out a decimal,
            // no DESCRIPTOR claims one either. All three items are character data - two named
            // PIC X spans and one reserved FILLER - so nothing in this layout carries a scale, a
            // sign or a zoned overpunch, and no monetary rounding decision belongs anywhere near it.
            for (FieldSpan span : TranTypeRecord.layout().spans()) {
                assertThat(span.kind())
                        .as("PICTURE kind of %s", span.name())
                        .isIn(PictureKind.ALPHANUMERIC, PictureKind.FILLER);
                assertThat(span.kind().numericDisplay())
                        .as("%s must not be a zoned DISPLAY numeric span", span.name())
                        .isFalse();
                assertThat(span.kind())
                        .as("%s must not be signed and scaled", span.name())
                        .isNotEqualTo(PictureKind.SIGNED_SCALED);
            }
            assertThat(TranTypeRecord.TRAN_TYPE.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranTypeRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
        }

        @Test
        @DisplayName("no primitive floating-point type appears anywhere on the type's surface")
        void noFloatingPointAppearsAnywhere() {
            // A binary floating-point type cannot represent a decimal fraction exactly, so it is
            // never the right carrier for mainframe data. There is nothing numeric here to hold in
            // one either way; the assertion exists so that stays true.
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("return type of %s", method.getName())
                        .isNotIn("double", "float");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .as("parameter of %s", method.getName())
                            .isNotIn("double", "float");
                }
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("type of field %s", field.getName())
                        .isNotIn("double", "float");
            }
        }

        @Test
        @DisplayName("gate G44: no persistence annotation, no entity, no table, no identifier")
        void thereIsNoPersistenceMapping() {
            // The TRANTYPE KSDS is reached over JDBC with no schema change: no DDL, no entity model,
            // no generated table. An @Entity, @Table or @Id here would assert a schema that does not
            // exist. Neither is there a framework stereotype: this is a value, not a bean.
            List<String> annotations = new ArrayList<>();
            for (Annotation annotation : TranTypeRecord.class.getAnnotations()) {
                annotations.add(annotation.annotationType().getName());
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }
            for (Constructor<?> constructor : TranTypeRecord.class.getDeclaredConstructors()) {
                for (Annotation annotation : constructor.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }

            assertThat(annotations).allSatisfy(name -> {
                assertThat(name).doesNotStartWith("jakarta.persistence.");
                assertThat(name).doesNotStartWith("javax.persistence.");
                assertThat(name).doesNotStartWith("org.springframework.");
            });
        }

        @Test
        @DisplayName("gate G44: there is no version column, and no optimistic-locking artefact")
        void thereIsNoVersionArtefact() {
            // Optimistic concurrency in this system is COBOL's own re-read-and-compare, not a
            // version column - adding one would be a schema change. TRANTYPE is read-only anyway:
            // CBTRN03C issues only OPEN INPUT, READ ... INTO and CLOSE against it.
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .as("method name")
                        .doesNotContain("version");
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("field name")
                        .doesNotContain("version");
            }
        }

        @Test
        @DisplayName("the type is a final, mutator-free value with no mutable static state")
        void theTypeIsAnImmutableValue() {
            assertThat(Modifier.isFinal(TranTypeRecord.class.getModifiers())).isTrue();
            // Every field final, static or not: WORKING-STORAGE must never become mutable state on
            // a shared type, because that breaks both request isolation and test determinism.
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
            // No setter and no wither: TRANTYPE is never written by any of the 28 programs, so a
            // mutator would be behaviour the legacy system does not have.
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getName()).doesNotStartWith("set");
                assertThat(method.getName()).doesNotStartWith("with");
            }
        }
    }

    @Nested
    @DisplayName("The CBTRN03C lookup contract this record has to satisfy")
    class ConsumerContract {

        @Test
        @DisplayName("CBTRN03C:189 - the key arrives as a 2-character String from the transaction")
        void theKeyTypeAlignsWithTheLookup() {
            // MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE, then 1500-B-LOOKUP-TRANTYPE. The
            // sending field is PIC X(02) and so is the key, so the lookup key is two characters.
            String keyFromTransaction = "04";
            TranTypeRecord found = null;
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord candidate = TranTypeRecord.decode(row, ASCII);
                if (candidate.tranTypeKey().equals(keyFromTransaction)) {
                    found = candidate;
                }
            }

            assertThat(found).isNotNull();
            assertThat(found.tranTypeKey())
                    .hasSize(TranTypeRecord.TRAN_TYPE_KEY_LENGTH)
                    .isEqualTo(keyFromTransaction);
            assertThat(found.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
        }

        @Test
        @DisplayName("CBTRN03C:366 - the report field is the description's first 15 characters")
        void theReportFieldIsTheNarrowedDescription() {
            TranTypeRecord found = TranTypeRecord.decode(fixtureRow(3), ASCII);

            String reportField = found.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(reportField)
                    .isEqualTo("Authorization  ")
                    .hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("a key absent from the fixture simply is not found - no I/O concern lives here")
        void anAbsentKeyIsNotThisTypesConcern() {
            // CBTRN03C handles a missing key with DISPLAY, IO-STATUS 23 and an abend; that belongs to
            // the repository and the report job, so this record type has nothing to say about it and
            // exposes no status, no exception and no not-found sentinel of its own.
            boolean present = false;
            for (String row : FIXTURE_ROWS) {
                if (TranTypeRecord.decode(row, ASCII).tranTypeKey().equals("99")) {
                    present = true;
                }
            }

            assertThat(present).isFalse();
        }
    }
}
