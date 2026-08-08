package com.vsergeychik.carddemo.transaction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranCategoryRecord}, the 60-byte {@code TRANCATG} record declared by
 * {@code app/cpy/CVTRA04Y.cpy}.
 *
 * <h2>What is proved, and against what</h2>
 * Every expectation is derived from a reference source rather than restated from the implementation:
 * <ul>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} - the 6-byte {@code TRAN-CAT-KEY} group over
 *       {@code TRAN-TYPE-CD X(02)} and {@code TRAN-CAT-CD 9(04)}, the {@code TRAN-CAT-TYPE-DESC X(50)}
 *       that follows it, the trailing {@code FILLER X(04)} and the 60-byte total;</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl:48,79-81} - {@code RECORD KEY IS FD-TRAN-CAT-KEY} over the same
 *       two items, which fixes the key at 6 bytes independently of the copybook;</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} - {@code TRANCATG} bound as a keyed input to {@code CBTRN03C};</li>
 *   <li>{@code src/test/resources/fixtures/trancatg.txt}, a byte-identical copy of
 *       {@code app/data/ASCII/trancatg.txt}: eighteen rows measured at exactly 60 bytes each, decoded
 *       below at the copybook's own offsets.</li>
 * </ul>
 * The expectations are <strong>statically derived</strong>: the COBOL cannot be executed in this
 * environment, so nothing here was captured from a live run.
 *
 * <h2>The namesake trap</h2>
 * {@code app/cpy/CVTRA01Y.cpy} declares a group with the <em>identical</em> COBOL name
 * {@code TRAN-CAT-KEY} that is <strong>17</strong> bytes rather than 6, because it carries an account
 * identifier this record has no notion of. The two are distinct Java types precisely so that no
 * repository, service or parity case can pass one where the other is expected, and
 * {@link TranCategoryRecord#verifyGeometry(int, int, int, int, int)} exists so that a transcription
 * error which conflated them is reported by name. That method takes its operands as parameters rather
 * than reading the constants, which is what makes its four diagnostics reachable from here - through
 * {@code verifyDeclaredGeometry()} they are unreachable, because the constants they guard are correct.
 *
 * <h2>Conventions</h2>
 * Plain JUnit 5 with AssertJ, no Spring context and no {@code JobLauncher} (gate G51). Both code pages
 * are named explicitly and neither is ever defaulted (practice B8). No wildcard import (gate G52) and no
 * mutable static state (gate G53).
 */
@DisplayName("TranCategoryRecord - CVTRA04Y TRAN-CAT-RECORD, 60 bytes, 6-byte composite key")
class TranCategoryRecordTest {

    /** The text fixtures are ASCII; named explicitly rather than defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the charset is honoured. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The shipped fixture on the test classpath, byte-identical to {@code app/data/ASCII}. */
    private static final String FIXTURE = "/fixtures/trancatg.txt";

    /** Every fixture row's trailing {@code FILLER X(04)}: four ASCII zeros, not spaces. */
    private static final String FIXTURE_FILLER = "0000";

    /**
     * Reads the shipped fixture as 60-character rows, exactly as stored and with nothing trimmed.
     *
     * @return the eighteen rows in file order
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = TranCategoryRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the TRANCATG fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * Pads a value to a width with spaces on the right, which is how COBOL fills a {@code PIC X}
     * receiver. Written out here rather than borrowed from the codec so an expected image is independent
     * of the code that produces it.
     *
     * @param value the value
     * @param width the receiver width
     * @return the padded image
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * The first fixture row, decoded under {@code US-ASCII}.
     *
     * @return a record over the shipped bytes of row 1
     */
    private static TranCategoryRecord firstFixtureRecord() {
        return TranCategoryRecord.decode(fixtureRows().get(0).getBytes(ASCII), ASCII);
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (gates G19 and G21)")
    class DeclaredGeometry {

        @Test
        @DisplayName("RECLN = 60 is the record length, and the declared spans sum to it")
        void theRecordIsSixtyBytes() {
            assertThat(TranCategoryRecord.RECORD_LENGTH).isEqualTo(60);
            assertThat(TranCategoryRecord.sumOfDeclaredSpanWidths()).isEqualTo(60);
            assertThat(TranCategoryRecord.LAYOUT.recordLength()).isEqualTo(60);
            // 6 + 50 + 4 = 60, stated as the sum so a dropped span shows up as arithmetic.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                    + TranCategoryRecord.FILLER_LENGTH).isEqualTo(60);
        }

        @Test
        @DisplayName("the key is SIX bytes, not CVTRA01Y's seventeen")
        void theKeyIsSixBytes() {
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .as("CVTRA04Y's TRAN-CAT-KEY has no account identifier in it")
                    .isEqualTo(6)
                    .isNotEqualTo(TranCatBalRecord.TRAN_CAT_KEY_LENGTH);
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD_OFFSET).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH
                    + TranCategoryRecord.TRAN_CAT_CD_LENGTH).isEqualTo(6);
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET)
                    .as("the description begins exactly where the key ends")
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("the trailing FILLER X(04) is a declared span, not an inferred gap (G21)")
        void theFillerIsADeclaredSpan() {
            assertThat(TranCategoryRecord.FILLER_OFFSET).isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER_LENGTH).isEqualTo(4);
            assertThat(TranCategoryRecord.FILLER.offset()).isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER.length()).isEqualTo(4);
            assertThat(TranCategoryRecord.LAYOUT.storageSpans())
                    .contains(TranCategoryRecord.FILLER);
        }

        @Test
        @DisplayName("the group name is carried verbatim, collision with CVTRA01Y included")
        void theGroupNameIsVerbatim() {
            // The parity differ compares field by field BY NAME, so the string is part of the migration
            // contract and is never tidied or disambiguated - even though it collides.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_NAME)
                    .isEqualTo("TRAN-CAT-KEY")
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_KEY_NAME);
        }

        @Test
        @DisplayName("verifyDeclaredGeometry passes on this class's real constants")
        void verifyDeclaredGeometryPasses() {
            // The static initialiser already ran it, so this only records that it is safe to call again:
            // it reads immutable constants and has no side effect.
            TranCategoryRecord.verifyDeclaredGeometry();
            TranCategoryRecord.verifyDeclaredGeometry();
        }

        @Test
        @DisplayName("a key width that does not equal its two items is reported, with both widths")
        void aKeyWidthThatContradictsItsItemsIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, 7, 7, 60))
                    .withMessageContaining("declared as 7 byte(s)")
                    .withMessageContaining("occupy 6")
                    .withMessageContaining("sub-span over exactly those two items");
        }

        @Test
        @DisplayName("a 17-byte key width is reported as the CVTRA01Y conflation it almost certainly is")
        void aSeventeenByteKeyIsReportedAsTheConflation() {
            // 11 + 2 + 4 = 17 is exactly what a transcription from CVTRA01Y produces, so the two items
            // sum correctly and only the named trap distinguishes the mistake from a typo.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(13, 4, 17, 17, 60))
                    .withMessageContaining("BEWARE THE NAMESAKE")
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("TRANCAT-ACCT-ID 9(11)")
                    .withMessageContaining("the two records have been conflated");
        }

        @Test
        @DisplayName("a description offset that is not the key width is reported, naming 17")
        void aDescriptionOffsetThatIsNotTheKeyWidthIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, 6, 17, 60))
                    .withMessageContaining("must begin at offset 6")
                    .withMessageContaining("declared at offset 17")
                    .withMessageContaining("An offset of 17 here");
        }

        @Test
        @DisplayName("a total that is not 60 is reported, and names the dropped FILLER")
        void aTotalThatIsNotSixtyIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, 6, 6, 56))
                    .withMessageContaining("RECLN = 60")
                    .withMessageContaining("sum to 56")
                    .withMessageContaining("FILLER 4");
        }
    }

    @Nested
    @DisplayName("The shipped TRANCATG fixture, decoded at the copybook's offsets")
    class ShippedFixture {

        @Test
        @DisplayName("all eighteen rows are exactly 60 bytes, so none needs widening")
        void everyRowIsSixtyBytes() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(18);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(60));
        }

        @Test
        @DisplayName("row 1 decodes to type 01, category 1 and 'Regular Sales Draft'")
        void rowOneDecodesFieldByField() {
            TranCategoryRecord record = firstFixtureRecord();

            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.tranCatTypeDesc())
                    .as("PIC X is never trimmed on read - the padding is stored data")
                    .isEqualTo(padded("Regular Sales Draft", 50));
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.tranCatKeyImage()).isEqualTo("010001").hasSize(6);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @ParameterizedTest(name = "row {0} is type {1}, category {2}")
        @DisplayName("the eighteen rows carry the seven type codes the TRANTYPE dataset declares")
        @CsvSource({
            "1,  01, 1, Regular Sales Draft",
            "5,  01, 5, Interest Amount",
            "6,  02, 1, Cash payment",
            "9,  03, 1, Credit to Account",
            "12, 04, 1, Zero dollar authorization",
            "15, 05, 1, Refund credit",
            "16, 06, 1, Fraud reversal",
            "18, 07, 1, Sales draft credit adjustment",
        })
        void theEighteenRowsCarryTheSevenTypeCodes(int rowNumber, String typeCd, int catCd,
                                                  String description) {
            TranCategoryRecord record = TranCategoryRecord.decode(
                    fixtureRows().get(rowNumber - 1).getBytes(ASCII), ASCII);
            assertThat(record.tranTypeCd()).isEqualTo(typeCd);
            assertThat(record.tranCatCd()).isEqualTo(catCd);
            assertThat(record.tranCatTypeDesc()).isEqualTo(padded(description, 50));
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("a decoded row re-serialises byte for byte, FILLER included (G21)")
        void aDecodedRowReserialisesByteForByte() {
            for (String row : fixtureRows()) {
                TranCategoryRecord record = TranCategoryRecord.decode(row.getBytes(ASCII), ASCII);
                assertThat(record.toByteArray()).isEqualTo(row.getBytes(ASCII));
                assertThat(record.toImage()).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("every key in the fixture is unique, which is what KEYS(6,0) requires")
        void everyKeyIsUnique() {
            List<String> keys = new ArrayList<>();
            for (String row : fixtureRows()) {
                keys.add(TranCategoryRecord.decode(row.getBytes(ASCII), ASCII).tranCatKeyImage());
            }
            assertThat(keys).hasSize(18).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("named spans are readable by descriptor, and the arrays handed back are copies")
        void namedSpansAreReadableAndReturnedAsCopies() {
            TranCategoryRecord record = firstFixtureRecord();

            assertThat(record.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo("01");
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_CD)).isEqualTo("0001");
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .isEqualTo(padded("Regular Sales Draft", 50));
            assertThat(record.fieldImage(TranCategoryRecord.FILLER)).isEqualTo(FIXTURE_FILLER);
            assertThat(record.fieldBytes(TranCategoryRecord.TRAN_TYPE_CD))
                    .isEqualTo("01".getBytes(ASCII));

            byte[] whole = record.toByteArray();
            whole[0] = (byte) '9';
            assertThat(record.tranTypeCd()).isEqualTo("01");
            byte[] filler = record.fillerBytes();
            filler[0] = (byte) '9';
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            byte[] keyBytes = record.tranCatKeyBytes();
            keyBytes[0] = (byte) '9';
            assertThat(record.tranCatKeyImage()).isEqualTo("010001");
            assertThat(record.toRecordArea().readSpan(TranCategoryRecord.TRAN_TYPE_CD))
                    .isEqualTo("01");
        }
    }

    @Nested
    @DisplayName("Construction from field values, and the keys a repository issues")
    class Construction {

        @Test
        @DisplayName("of() assembles 60 bytes and blanks the FILLER, because it is never written back")
        void ofAssemblesSixtyBytes() {
            // TRANCATG is never written by this system, so a built record is a test or in-memory
            // construct: its FILLER is the space-filled default rather than the dataset's four zeros,
            // and that difference is intentional.
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);
            assertThat(built.toImage())
                    .isEqualTo("01" + "0001" + padded("Regular Sales Draft", 50) + "    ")
                    .hasSize(60);
            assertThat(built.filler()).isEqualTo("    ").isNotEqualTo(FIXTURE_FILLER);
        }

        @ParameterizedTest(name = "type \"{0}\" and category {1} key on \"{2}\"")
        @DisplayName("PIC X pads on the right and PIC 9 zero-fills on the left, per picture")
        @CsvSource({
            "'01', 1,    '010001'",
            "'1',  1,    '1 0001'",
            "'',   0,    '  0000'",
            "'01', 9999, '019999'",
        })
        void eachPictureUsesItsOwnMoveRule(String typeCd, int catCd, String keyImage) {
            assertThat(TranCategoryRecord.tranCatKeyImage(typeCd, catCd, ASCII)).isEqualTo(keyImage);
            assertThat(TranCategoryRecord.tranCatKeyBytes(typeCd, catCd, ASCII))
                    .isEqualTo(keyImage.getBytes(ASCII));
            assertThat(TranCategoryRecord.of(typeCd, catCd, "x", ASCII).tranCatKeyImage())
                    .isEqualTo(keyImage);
        }

        @Test
        @DisplayName("an over-long description truncates on the right, as a PIC X receiver does")
        void anOverLongDescriptionTruncatesOnTheRight() {
            String tooLong = "D".repeat(51);
            assertThat(TranCategoryRecord.of("01", 1, tooLong, ASCII).tranCatTypeDesc())
                    .isEqualTo("D".repeat(50));
        }

        @Test
        @DisplayName("an over-wide category code keeps its low-order digits, as PIC 9 does")
        void anOverWideCategoryCodeTruncatesOnTheLeft() {
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 12345, ASCII)).isEqualTo("012345");
        }

        @Test
        @DisplayName("a negative category code has no PIC 9(04) representation and is rejected")
        void aNegativeCategoryCodeIsRejected() {
            // Storing the magnitude instead would invent data the COBOL could never hold, so this is
            // rejected at every entry point that accepts a category code.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", -1, "x", ASCII))
                    .withMessageContaining("PIC 9(04)")
                    .withMessageContaining("unsigned picture with no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", -1, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyBytes("01", -1, ASCII));
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aWrongWidthRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[59], ASCII))
                    .withMessageContaining("59 byte(s)")
                    .withMessageContaining("exactly 60");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[61], ASCII))
                    .withMessageContaining("61 byte(s)");
        }

        @Test
        @DisplayName("a row whose TRAN-CAT-CD does not hold digits is rejected, not silently zeroed")
        void aNonNumericCategoryCodeIsRejected() {
            String corrupt = "01" + "ABCD" + padded("Regular Sales Draft", 50) + FIXTURE_FILLER;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(corrupt.getBytes(ASCII), ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that needs a value")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(null, ASCII))
                    .withMessageContaining("record bytes are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[60], null))
                    .withMessageContaining("never a platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of(null, 1, "x", ASCII))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, null, ASCII))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, "x", null))
                    .withMessageContaining("never derived from the platform");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage(null, 1, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", 1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> firstFixtureRecord().fieldImage(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> firstFixtureRecord().fieldBytes(null))
                    .withMessageContaining("field descriptor is required");
        }

        @Test
        @DisplayName("the same characters under EBCDIC are different bytes and the same values")
        void theCodePageIsHonoured() {
            TranCategoryRecord ascii = firstFixtureRecord();
            TranCategoryRecord ebcdic = TranCategoryRecord.decode(
                    fixtureRows().get(0).getBytes(EBCDIC), EBCDIC);

            assertThat(ebcdic.charset()).isEqualTo(EBCDIC);
            assertThat(ebcdic.toByteArray()).isNotEqualTo(ascii.toByteArray());
            assertThat(ebcdic.tranTypeCd()).isEqualTo(ascii.tranTypeCd());
            assertThat(ebcdic.tranCatCd()).isEqualTo(ascii.tranCatCd());
            assertThat(ebcdic.tranCatTypeDesc()).isEqualTo(ascii.tranCatTypeDesc());
        }
    }

    @Nested
    @DisplayName("Value semantics and rendering")
    class ValueSemanticsAndRendering {

        @Test
        @DisplayName("two records over the same bytes and code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            byte[] row = fixtureRows().get(0).getBytes(ASCII);
            assertThat(TranCategoryRecord.decode(row, ASCII))
                    .isEqualTo(TranCategoryRecord.decode(row, ASCII))
                    .hasSameHashCodeAs(TranCategoryRecord.decode(row, ASCII));
        }

        @Test
        @DisplayName("a record equals itself without comparing bytes at all")
        void aRecordEqualsItself() {
            TranCategoryRecord record = firstFixtureRecord();
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCategoryRecord is equal to one")
        void anotherTypeIsNeverEqual() {
            TranCategoryRecord record = firstFixtureRecord();
            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("010001")).isFalse();
            // The namesake made concrete: the 17-byte record is a different type and is never equal.
            assertThat(record.equals(TranCatBalRecord.newInstance(ASCII))).isFalse();
        }

        @Test
        @DisplayName("identical bytes under a different code page are NOT the same record")
        void adifferentCodePageIsNotEqual() {
            // This isolates the second half of the comparison: the two records hold byte-for-byte
            // identical images, so only the declared code page can distinguish them - and it must,
            // because the record's meaning is bytes plus the page they are read under.
            //
            // ISO-8859-1 rather than IBM037 for the second reading, deliberately. This type decodes its
            // fields eagerly, so the same bytes must remain valid under both pages for the comparison to
            // be about the charset alone; ISO-8859-1 agrees with US-ASCII on every digit and letter in
            // the fixture, whereas reading ASCII bytes as EBCDIC would fail the PIC 9 digit check first
            // and prove nothing about equality. Neither page is a platform default: both are named.
            byte[] row = fixtureRows().get(0).getBytes(ASCII);
            TranCategoryRecord underAscii = TranCategoryRecord.decode(row, ASCII);
            TranCategoryRecord underLatin1 = TranCategoryRecord.decode(row,
                    StandardCharsets.ISO_8859_1);

            assertThat(underAscii.toByteArray()).isEqualTo(underLatin1.toByteArray());
            assertThat(underAscii.tranCatKeyImage()).isEqualTo(underLatin1.tranCatKeyImage());
            assertThat(underAscii).isNotEqualTo(underLatin1);
            assertThat(underLatin1).isNotEqualTo(underAscii);
        }

        @Test
        @DisplayName("a row that will not decode under a code page is rejected, not silently accepted")
        void aRowThatWillNotDecodeIsRejected() {
            // The counterpart of the case above, and the reason it had to use ISO-8859-1: this type
            // decodes eagerly, so ASCII digits read as EBCDIC are not digits at all and construction
            // fails rather than producing a record whose fields are quietly wrong.
            byte[] asciiRow = fixtureRows().get(0).getBytes(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(asciiRow, EBCDIC))
                    .withMessageContaining("is not a digit");
        }

        @Test
        @DisplayName("a difference confined to the FILLER is never mistaken for equality")
        void aFillerDifferenceIsNotEqual() {
            byte[] fromDataset = fixtureRows().get(0).getBytes(ASCII);
            byte[] blanked = fixtureRows().get(0).getBytes(ASCII);
            for (int i = TranCategoryRecord.FILLER_OFFSET; i < TranCategoryRecord.RECORD_LENGTH; i++) {
                blanked[i] = (byte) ' ';
            }
            TranCategoryRecord stored = TranCategoryRecord.decode(fromDataset, ASCII);
            TranCategoryRecord rebuilt = TranCategoryRecord.decode(blanked, ASCII);

            assertThat(stored.tranCatKeyImage()).isEqualTo(rebuilt.tranCatKeyImage());
            assertThat(stored.tranCatTypeDesc()).isEqualTo(rebuilt.tranCatTypeDesc());
            assertThat(stored).isNotEqualTo(rebuilt);
        }

        @Test
        @DisplayName("the rendering names each COBOL item and quotes the padding it keeps")
        void theRenderingNamesEachItem() {
            // TRANCATG carries no personal data of any kind - a transaction type and a category
            // description identify a kind of activity, not a person - so this rendering withholds
            // nothing, and the padding stays visible because a wrong width is a real defect.
            String rendered = firstFixtureRecord().toString();
            assertThat(rendered)
                    .startsWith("TranCategoryRecord[")
                    .contains("TRAN-CAT-KEY='010001'")
                    .contains("TRAN-TYPE-CD='01'")
                    .contains("TRAN-CAT-CD=1")
                    .contains("TRAN-CAT-TYPE-DESC='" + padded("Regular Sales Draft", 50) + "'")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("charset=US-ASCII")
                    .endsWith("]");
            // Deterministic: no clock, no identity hash, no locale-dependent formatting (practice B7).
            assertThat(rendered).isEqualTo(firstFixtureRecord().toString()).doesNotContain("@");
        }
    }

    @Nested
    @DisplayName("Structural guards - properties of the type itself (gates G22, G44, G53)")
    class StructuralGuards {

        /**
         * The fields this class declares, with the coverage agent's contribution removed.
         *
         * <p>JaCoCo adds a non-final {@code private static transient synthetic boolean[] $jacocoData}
         * probe array to every instrumented class, so a bare walk over {@code getDeclaredFields()} would
         * fail the no-mutable-static-state guard under {@code verify} while passing under a plain
         * {@code test} run.
         *
         * @return the declared fields, instrumentation artefacts excluded
         */
        private static List<Field> declaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : TranCategoryRecord.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields to vouch for").isNotEmpty();
            return fields;
        }

        @Test
        @DisplayName("no field or accessor uses double or float anywhere (R4, G22)")
        void noBinaryFloatingPointAnywhere() {
            for (Field field : declaredFields()) {
                assertThat(field.getType()).isNotIn(double.class, float.class, Double.class,
                        Float.class);
            }
            for (Method method : TranCategoryRecord.class.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getName().startsWith("$")) {
                    continue;
                }
                assertThat(method.getReturnType()).isNotIn(double.class, float.class, Double.class,
                        Float.class);
                assertThat(method.getParameterTypes()).doesNotContain(double.class, float.class,
                        Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("no persistence mapping of any kind is declared on the type (G44)")
        void noPersistenceMapping() {
            assertThat(TranCategoryRecord.class.getAnnotations()).isEmpty();
            for (Field field : declaredFields()) {
                assertThat(field.getAnnotations())
                        .as("field %s must carry no mapping annotation", field.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("every field is private and final, so an instance cannot be mutated (G53)")
        void everyFieldIsPrivateAndFinal() {
            for (Field field : declaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }
        }
    }
}
