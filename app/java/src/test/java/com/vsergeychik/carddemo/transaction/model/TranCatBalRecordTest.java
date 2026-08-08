package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;
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
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranCatBalRecord}, the 50-byte {@code TCATBALF} record declared by
 * {@code app/cpy/CVTRA01Y.cpy}.
 *
 * <h2>What is proved, and against what</h2>
 * Every expectation here is derived from a reference source rather than restated from the
 * implementation, and the source of each is named in the test that asserts it:
 * <ul>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} - the four elementary items, the 17-byte {@code TRAN-CAT-KEY}
 *       group, the trailing {@code FILLER X(22)} and the {@code RECLN = 50} total;</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:60,92-96} - {@code RECORD KEY IS FD-TRAN-CAT-KEY} over the same
 *       three-part split, which fixes the key at 17 bytes independently of the copybook;</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:504-510} and {@code :527-528} - the create and update paths, whose
 *       {@code INITIALIZE}, {@code ADD ... TO TRAN-CAT-BAL} and {@code REWRITE} sequences the mutator
 *       tests reproduce statement for statement;</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:193,326,464-465} - {@code DISPLAY TRAN-CAT-BAL-RECORD}, the keyed
 *       read, and the one division in the whole system, which consumes this balance;</li>
 *   <li>{@code src/test/resources/fixtures/tcatbal.txt}, a byte-identical copy of
 *       {@code app/data/ASCII/tcatbal.txt}: fifty rows measured at exactly 50 bytes each, decoded below
 *       at the copybook's own offsets.</li>
 * </ul>
 * The expectations are <strong>statically derived</strong>: the COBOL cannot be executed in this
 * environment, so nothing here was captured from a live run.
 *
 * <h2>The trap this record lives beside</h2>
 * {@code app/cpy/CVTRA02Y.cpy} declares {@code DIS-GROUP-RECORD}, which <em>also</em> totals 50 bytes
 * but whose key is <strong>16</strong> and whose signed amount is 6 bytes at 0-based 16 rather than 11
 * bytes at 0-based 17. A whole-record width check passes for either layout, so the substitution would
 * surface only as wrong money - through the interest formula, in {@code CBACT04C}, the one program that
 * works both layouts side by side. {@link TranCatBalRecord#verifyKeyGeometry(int, int)} exists to catch
 * it and is driven here with the wrong widths deliberately.
 *
 * <h2>Conventions</h2>
 * Plain JUnit 5 with AssertJ, no Spring context and no {@code JobLauncher}: every decision in the class
 * under test is reachable directly, which is what makes the mandated branch coverage attainable without
 * an HTTP or batch layer in the path (gate G51). Both code pages are named explicitly and neither is
 * ever defaulted. No wildcard import (gate G52) and no mutable static state (gate G53).
 */
@DisplayName("TranCatBalRecord - CVTRA01Y TRAN-CAT-BAL-RECORD, 50 bytes, 17-byte composite key")
class TranCatBalRecordTest {

    /** The text fixtures are ASCII; named explicitly rather than defaulted (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the charset is honoured. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The shipped fixture on the test classpath, byte-identical to {@code app/data/ASCII}. */
    private static final String FIXTURE = "/fixtures/tcatbal.txt";

    /** Every fixture row carries this type code: {@code TRANCAT-TYPE-CD PIC X(02)} holding "01". */
    private static final String FIXTURE_TYPE_CD = "01";

    /** Every fixture row carries category 1, stored as the four digits {@code 0001}. */
    private static final int FIXTURE_CAT_CD = 1;

    /**
     * Every fixture row's {@code TRAN-CAT-BAL} image: ten zeros and a positive-zero overpunch.
     *
     * <p><code>&#123;</code> is the zone-{@code C} form of digit 0, so the value is {@code +0.00}. This
     * is measured from the file, not assumed: all fifty rows end the same way.
     */
    private static final String FIXTURE_BALANCE_IMAGE = "0000000000{";

    /** Every fixture row's {@code FILLER X(22)}: twenty-two ASCII zeros, not spaces. */
    private static final String FIXTURE_FILLER = "0".repeat(22);

    /**
     * Reads the shipped fixture as 50-character rows, exactly as stored and with nothing trimmed.
     *
     * @return the fifty rows in file order
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = TranCatBalRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the TCATBALF fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * The first fixture row, decoded under {@code US-ASCII}.
     *
     * @return a record over the shipped bytes of row 1
     */
    private static TranCatBalRecord firstFixtureRecord() {
        return TranCatBalRecord.decode(fixtureRows().get(0), ASCII);
    }

    /**
     * Builds a 50-character image from its four field images plus a filler, so an expected value is
     * spelled out from the copybook rather than produced by the code under test.
     *
     * @param acctId  eleven digits
     * @param typeCd  two characters
     * @param catCd   four digits
     * @param balance eleven characters, sign overpunch included
     * @param filler  twenty-two characters
     * @return the assembled 50-character row
     */
    private static String image(String acctId, String typeCd, String catCd, String balance,
                                String filler) {
        String row = acctId + typeCd + catCd + balance + filler;
        assertThat(row).as("a hand-assembled expectation must itself be 50 characters").hasSize(50);
        return row;
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (gates G19 and G21)")
    class DeclaredGeometry {

        @Test
        @DisplayName("RECLN = 50 is the record length, and the layout accounts for every byte")
        void theRecordIsFiftyBytes() {
            assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(TranCatBalRecord.verifyGeometry()).isEqualTo(50);
            assertThat(TranCatBalRecord.LAYOUT.recordLength()).isEqualTo(50);
            // 17 + 11 + 22 = 50. Stated as the sum so a dropped span is visible as arithmetic.
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH
                    + TranCatBalRecord.TRAN_CAT_BAL_LENGTH
                    + TranCatBalRecord.FILLER_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the key is 17 bytes over three items, and the balance begins where it ends")
        void theKeyIsSeventeenBytes() {
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET).isZero();
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(TranCatBalRecord.TRANCAT_CD_OFFSET).isEqualTo(13);
            assertThat(TranCatBalRecord.TRANCAT_CD_LENGTH).isEqualTo(4);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH
                    + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH
                    + TranCatBalRecord.TRANCAT_CD_LENGTH).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .as("TRAN-CAT-BAL begins immediately after the key, not one byte earlier as in "
                            + "CVTRA02Y")
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("TRAN-CAT-BAL is 11 bytes at scale 2, with no byte reserved for the sign")
        void theBalanceIsElevenBytesAtScaleTwo() {
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH).isEqualTo(11);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .isEqualTo(2)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            // p + s = 9 + 2 = 11: the sign is overpunched into the trailing byte and occupies none of
            // its own, which is why twelve would make the record 51.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS
                    + TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_LENGTH);
        }

        @Test
        @DisplayName("the trailing FILLER X(22) is a declared span, not an inferred gap (G21)")
        void theFillerIsADeclaredSpan() {
            assertThat(TranCatBalRecord.FILLER_OFFSET).isEqualTo(28);
            assertThat(TranCatBalRecord.FILLER_LENGTH).isEqualTo(22);
            assertThat(TranCatBalRecord.FILLER_SPAN.offset()).isEqualTo(28);
            assertThat(TranCatBalRecord.FILLER_SPAN.length()).isEqualTo(22);
            // Present in the layout is what matters: dropping it would leave the layout 22 bytes short
            // and shift every subsequent record in the dataset.
            assertThat(TranCatBalRecord.LAYOUT.storageSpans())
                    .contains(TranCatBalRecord.FILLER_SPAN);
        }

        @Test
        @DisplayName("the key layout addresses the same three spans as the whole-record layout")
        void theKeyLayoutReusesTheRecordsSpans() {
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.recordLength()).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.storageSpans())
                    .containsExactly(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN,
                            TranCatBalRecord.TRANCAT_TYPE_CD_SPAN,
                            TranCatBalRecord.TRANCAT_CD_SPAN);
        }

        @Test
        @DisplayName("the copybook, group and DD names are carried verbatim for the parity differ")
        void theCopybookVocabularyIsVerbatim() {
            assertThat(TranCatBalRecord.COPYBOOK).isEqualTo("CVTRA01Y");
            assertThat(TranCatBalRecord.RECORD_NAME).isEqualTo("TRAN-CAT-BAL-RECORD");
            assertThat(TranCatBalRecord.DD_NAME).isEqualTo("TCATBALF");
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME).isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_NAME).isEqualTo("TRANCAT-ACCT-ID");
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_NAME).isEqualTo("TRANCAT-TYPE-CD");
            assertThat(TranCatBalRecord.TRANCAT_CD_NAME).isEqualTo("TRANCAT-CD");
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_NAME).isEqualTo("TRAN-CAT-BAL");
        }

        @Test
        @DisplayName("verifyRecordLength accepts agreement and names the two ways it can fail")
        void verifyRecordLengthRejectsDisagreement() {
            assertThat(TranCatBalRecord.verifyRecordLength(50, 50)).isEqualTo(50);
            // 28 is the record with its FILLER dropped; 51 is a sign byte wrongly reserved for
            // TRAN-CAT-BAL. The message has to name both, because either is a plausible regression.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(50, 28))
                    .withMessageContaining("RECLN = 50")
                    .withMessageContaining("28")
                    .withMessageContaining("dropped FILLER")
                    .withMessageContaining("overpunched");
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(51, 50))
                    .withMessageContaining("51");
        }

        @Test
        @DisplayName("verifyKeyGeometry catches the CVTRA02Y substitution by name")
        void verifyKeyGeometryCatchesTheCvtra02yTrap() {
            assertThat(TranCatBalRecord.verifyKeyGeometry(17, 17)).isEqualTo(17);
            // The single most valuable assertion in the file: a 16-byte key with its amount at 0-based
            // 16 is CVTRA02Y's DIS-GROUP-RECORD, which also totals 50. Nothing else in the system can
            // tell the two apart, so the diagnostic must say so rather than merely report a mismatch.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyKeyGeometry(16, 16 + 1))
                    .withMessageContaining("BEWARE THE CVTRA02Y TRAP")
                    .withMessageContaining("DIS-GROUP-KEY is 16 bytes")
                    .withMessageContaining("silently corrupts every balance");
        }
    }

    @Nested
    @DisplayName("TRAN-CAT-KEY as a value - the 17 bytes a keyed read is issued with")
    class CompositeKey {

        @Test
        @DisplayName("the image is 11 zero-filled digits, a right-padded type code, then 4 digits")
        void theKeyImageFollowsEachPicturesOwnMoveRule() {
            // app/cbl/CBTRN02C.cbl:469-471 moves XREF-ACCT-ID, DALYTRAN-TYPE-CD and DALYTRAN-CAT-CD
            // into the three components; :476-477 then DISPLAYs the image itself when the read misses,
            // which is why the raw image is what this accessor returns.
            TranCatKey key = new TranCatKey(1L, "01", 1);
            assertThat(key.image(ASCII)).isEqualTo("00000000001" + "01" + "0001").hasSize(17);
            assertThat(key.toByteArray(ASCII)).hasSize(17)
                    .isEqualTo("00000000001010001".getBytes(ASCII));
        }

        @ParameterizedTest(name = "type code \"{0}\" stores as \"{1}\"")
        @DisplayName("PIC X pads and truncates on the RIGHT, which PIC 9 never does")
        @CsvSource({
            "'01', '01'",
            "'1',  '1 '",
            "'',   '  '",
            "'012','01'",
        })
        void thePicXComponentPadsAndTruncatesOnTheRight(String supplied, String stored) {
            assertThat(new TranCatKey(1L, supplied, 1).image(ASCII))
                    .isEqualTo("00000000001" + stored + "0001");
        }

        @Test
        @DisplayName("PIC 9 zero-fills and truncates on the LEFT, keeping the low-order digits")
        void thePic9ComponentsTruncateOnTheLeft() {
            assertThat(new TranCatKey(123L, "01", 45).image(ASCII))
                    .isEqualTo("00000000123" + "01" + "0045");
            // Twelve digits into an eleven-digit receiver: COBOL keeps the low-order eleven.
            assertThat(new TranCatKey(123456789012L, "01", 12345).image(ASCII))
                    .isEqualTo("23456789012" + "01" + "2345");
        }

        @Test
        @DisplayName("a stored key image decodes to its three components, the type code untrimmed")
        void aStoredKeyImageDecodes() {
            TranCatKey decoded = TranCatKey.decode("00000000042" + "1 " + "0007", ASCII);
            assertThat(decoded.trancatAcctId()).isEqualTo(42L);
            assertThat(decoded.trancatTypeCd())
                    .as("PIC X is never trimmed on read - the trailing space is stored data")
                    .isEqualTo("1 ");
            assertThat(decoded.trancatCd()).isEqualTo(7);
            assertThat(TranCatKey.decode(decoded.toByteArray(ASCII), ASCII)).isEqualTo(decoded);
        }

        @Test
        @DisplayName("the key round-trips byte-identically under EBCDIC too")
        void theKeyRoundTripsUnderEbcdic() {
            TranCatKey key = new TranCatKey(50L, "01", 1);
            byte[] ebcdic = key.toByteArray(EBCDIC);
            assertThat(ebcdic).hasSize(17).isNotEqualTo(key.toByteArray(ASCII));
            assertThat(TranCatKey.decode(ebcdic, EBCDIC)).isEqualTo(key);
        }

        @Test
        @DisplayName("null is rejected wherever a value is structurally required")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranCatKey(1L, null, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode((String) null, ASCII))
                    .withMessageContaining("17-character");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode("00000000001010001", null))
                    .withMessageContaining("never a platform default");
        }

        @Test
        @DisplayName("a key image of the wrong width is rejected rather than widened")
        void aWrongWidthKeyImageIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatKey.decode("0000000000101000", ASCII));
        }
    }

    @Nested
    @DisplayName("The shipped TCATBALF fixture, decoded at the copybook's offsets")
    class ShippedFixture {

        @Test
        @DisplayName("all fifty rows are exactly 50 bytes, so none needs widening")
        void everyRowIsFiftyBytes() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(50);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(50));
        }

        @Test
        @DisplayName("row 1 decodes to account 1, type 01, category 1 and a balance of +0.00")
        void rowOneDecodesFieldByField() {
            TranCatBalRecord record = firstFixtureRecord();

            assertThat(record.trancatAcctId()).isEqualTo(1L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000001");
            assertThat(record.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(record.trancatCd()).isEqualTo(FIXTURE_CAT_CD);
            assertThat(record.trancatCdImage()).isEqualTo("0001");
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.tranCatBal()).isEqualByComparingTo("0.00");
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.recordLength()).isEqualTo(50);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("the fifty rows carry accounts 1 to 50, all on the same type and category")
        void theFiftyRowsCarryAccountsOneToFifty() {
            List<String> rows = fixtureRows();
            for (int index = 0; index < rows.size(); index++) {
                TranCatBalRecord record = TranCatBalRecord.decode(rows.get(index), ASCII);
                assertThat(record.trancatAcctId())
                        .as("row %d's TRANCAT-ACCT-ID", index + 1)
                        .isEqualTo(index + 1L);
                assertThat(record.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
                assertThat(record.trancatCd()).isEqualTo(FIXTURE_CAT_CD);
                assertThat(record.tranCatBalIsZero())
                        .as("every shipped balance is the positive-zero overpunch")
                        .isTrue();
                assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER);
            }
        }

        @Test
        @DisplayName("a decoded row re-serialises byte for byte, FILLER included (G21)")
        void aDecodedRowReserialisesByteForByte() {
            for (String row : fixtureRows()) {
                TranCatBalRecord record = TranCatBalRecord.decode(row, ASCII);
                assertThat(record.encode()).isEqualTo(row.getBytes(ASCII));
                assertThat(record.rawImage()).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("the key inside the record is the same 17 bytes as the standalone key value")
        void theEmbeddedKeyMatchesTheStandaloneKey() {
            TranCatBalRecord record = firstFixtureRecord();
            assertThat(record.tranCatKeyImage()).isEqualTo("00000000001010001").hasSize(17);
            assertThat(record.tranCatKeyBytes()).isEqualTo("00000000001010001".getBytes(ASCII));
            assertThat(record.tranCatKey()).isEqualTo(new TranCatKey(1L, "01", 1));
            assertThat(record.tranCatKey().image(ASCII)).isEqualTo(record.tranCatKeyImage());
        }

        @Test
        @DisplayName("fieldImages names four fields in copybook order and excludes FILLER")
        void fieldImagesNamesFourFieldsInOrder() {
            // FILLER is not a referable COBOL name, so it cannot be a map key; it is separately
            // available and separately proven present by the layout's own width check.
            assertThat(firstFixtureRecord().fieldImages())
                    .containsExactly(
                            org.assertj.core.api.Assertions.entry("TRANCAT-ACCT-ID", "00000000001"),
                            org.assertj.core.api.Assertions.entry("TRANCAT-TYPE-CD", "01"),
                            org.assertj.core.api.Assertions.entry("TRANCAT-CD", "0001"),
                            org.assertj.core.api.Assertions.entry("TRAN-CAT-BAL",
                                    FIXTURE_BALANCE_IMAGE));
        }

        @Test
        @DisplayName("the field images map is unmodifiable, so a caller cannot rewrite the record")
        void theFieldImagesMapIsUnmodifiable() {
            var images = firstFixtureRecord().fieldImages();
            assertThat(images).hasSize(4);
            org.assertj.core.api.Assertions
                    .assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put("TRANCAT-CD", "9999"));
        }

        @Test
        @DisplayName("the returned byte arrays are copies, so a caller cannot reach the record area")
        void theReturnedArraysAreCopies() {
            TranCatBalRecord record = firstFixtureRecord();
            byte[] encoded = record.encode();
            encoded[0] = (byte) '9';
            assertThat(record.trancatAcctId()).isEqualTo(1L);

            byte[] filler = record.fillerBytes();
            filler[0] = (byte) '9';
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER);

            byte[] keyBytes = record.tranCatKeyBytes();
            keyBytes[0] = (byte) '9';
            assertThat(record.tranCatKeyImage()).isEqualTo("00000000001010001");
        }
    }

    @Nested
    @DisplayName("Construction and rejection")
    class Construction {

        @Test
        @DisplayName("newInstance allocates 50 bytes and initialises the four named items")
        void newInstanceInitialisesTheNamedItems() {
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII);
            assertThat(record.rawImage())
                    .isEqualTo(image("00000000000", "  ", "0000", "0000000000{", " ".repeat(22)));
            assertThat(record.tranCatBal()).isEqualByComparingTo("0.00");
            assertThat(record.encode()).hasSize(50);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aWrongWidthRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode("0".repeat(49), ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(new byte[51], ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that needs a value")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode((String) null, ASCII))
                    .withMessageContaining("50-character");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode("0".repeat(50), null))
                    .withMessageContaining("never a platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.newInstance(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> firstFixtureRecord().tranCatKey(null))
                    .withMessageContaining("TRAN-CAT-KEY");
            assertThatNullPointerException()
                    .isThrownBy(() -> firstFixtureRecord().tranCatBal(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> firstFixtureRecord().addToTranCatBal(null))
                    .withMessageContaining("ADD ... TO TRAN-CAT-BAL");
        }

        @Test
        @DisplayName("the same bytes read under EBCDIC are a different record, not a coincidence")
        void theCodePageIsHonoured() {
            TranCatBalRecord ascii = firstFixtureRecord();
            byte[] ebcdic = ascii.rawImage().getBytes(EBCDIC);
            TranCatBalRecord read = TranCatBalRecord.decode(ebcdic, EBCDIC);

            assertThat(read.charset()).isEqualTo(EBCDIC);
            assertThat(read.trancatAcctId()).isEqualTo(1L);
            assertThat(read.tranCatBal()).isEqualByComparingTo("0.00");
            assertThat(read.encode()).isNotEqualTo(ascii.encode());
            assertThat(read.rawImage()).isEqualTo(ascii.rawImage());
        }
    }

    @Nested
    @DisplayName("Mutators - the two COBOL paths, statement for statement")
    class Mutators {

        @Test
        @DisplayName("each named item is written through its own PICTURE's move rule")
        void eachItemIsWrittenThroughItsOwnPicture() {
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII)
                    .trancatAcctId(42L)
                    .trancatTypeCd("1")
                    .trancatCd(7);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000042");
            assertThat(record.trancatTypeCd()).isEqualTo("1 ");
            assertThat(record.trancatCdImage()).isEqualTo("0007");
        }

        @Test
        @DisplayName("tranCatKey writes all three components, as CBTRN02C:505-507 does in sequence")
        void theKeyGroupIsWrittenInOneCall() {
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII)
                    .tranCatKey(new TranCatKey(11L, "02", 3));
            assertThat(record.tranCatKeyImage()).isEqualTo("00000000011" + "02" + "0003");
            assertThat(record.tranCatKey()).isEqualTo(new TranCatKey(11L, "02", 3));
        }

        @Test
        @DisplayName("a balance store truncates toward zero at scale 2, because ROUNDED never appears")
        void aBalanceStoreTruncatesTowardZero() {
            // ROUNDED appears zero times in all 28 programs, so the receiver truncates. HALF_UP would
            // give 1.24 and -1.24 here, and that would be wrong money in the interest calculation.
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("1.239")).tranCatBal())
                    .isEqualByComparingTo("1.23");
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("-1.239")).tranCatBal())
                    .isEqualByComparingTo("-1.23");
        }

        @Test
        @DisplayName("a positive and a negative balance store as their zoned overpunch images")
        void theSignIsOverpunchedIntoTheTrailingByte() {
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("123.45")).tranCatBalImage())
                    .isEqualTo("0000001234E");
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("-123.45")).tranCatBalImage())
                    .isEqualTo("0000001234N");
            // Eleven characters either way: the sign occupies no byte of its own.
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("-123.45")).tranCatBalImage()).hasSize(11);
        }

        @Test
        @DisplayName("integer digits beyond the ninth are discarded silently, as COBOL discards them")
        void integerOverflowWrapsRatherThanThrowing() {
            // No ON SIZE ERROR phrase exists anywhere in this codebase, so there is nothing for the
            // condition to raise and COBOL keeps the receiver's low-order digits.
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("1234567890.12"));
            assertThat(record.tranCatBal()).isEqualByComparingTo("234567890.12");
            assertThat(record.tranCatBalImage()).hasSize(11);
        }

        @Test
        @DisplayName("ADD ... TO TRAN-CAT-BAL on the create path: the augend is the initialised zero")
        void addOnTheCreatePath() {
            // app/cbl/CBTRN02C.cbl:504 INITIALIZE then :508 ADD DALYTRAN-AMT TO TRAN-CAT-BAL, so the
            // result is the amount itself.
            TranCatBalRecord created = TranCatBalRecord.newInstance(ASCII)
                    .initialize()
                    .addToTranCatBal(new BigDecimal("125.75"));
            assertThat(created.tranCatBal()).isEqualByComparingTo("125.75");
        }

        @Test
        @DisplayName("ADD ... TO TRAN-CAT-BAL on the update path: the augend is the balance just read")
        void addOnTheUpdatePath() {
            // app/cbl/CBTRN02C.cbl:474 READ, :527 ADD, :528 REWRITE. The record read here carries
            // +0.00, so a second addition is what distinguishes accumulation from replacement.
            TranCatBalRecord read = firstFixtureRecord()
                    .addToTranCatBal(new BigDecimal("10.01"))
                    .addToTranCatBal(new BigDecimal("0.99"));
            assertThat(read.tranCatBal()).isEqualByComparingTo("11.00");
            // The REWRITE sends the whole area back, so the reserved bytes must have survived both
            // additions untouched.
            assertThat(read.fillerImage()).isEqualTo(FIXTURE_FILLER);
            assertThat(read.encode()).hasSize(50);
        }

        @Test
        @DisplayName("a negative addend subtracts, and the stored sign follows the result")
        void aNegativeAddendSubtracts() {
            TranCatBalRecord record = firstFixtureRecord()
                    .addToTranCatBal(new BigDecimal("-5.50"));
            assertThat(record.tranCatBal()).isEqualByComparingTo("-5.50");
            assertThat(record.tranCatBalIsZero()).isFalse();
        }

        @Test
        @DisplayName("tranCatBalIsZero answers by sign, so 0.00 and 0 are both zero")
        void zeroIsDecidedBySignNotByEquals() {
            // BigDecimal.equals compares scale as well as value and would call 0.00 and 0 unequal;
            // signum does not. Both states of the predicate are driven, which is what the branch bar
            // requires and what a caller reaching for equals would get wrong.
            assertThat(firstFixtureRecord().tranCatBalIsZero()).isTrue();
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(BigDecimal.ZERO).tranCatBalIsZero()).isTrue();
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("0.001")).tranCatBalIsZero())
                    .as("0.001 truncates to 0.00 at the receiver's scale, so it stores as zero")
                    .isTrue();
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("0.01")).tranCatBalIsZero()).isFalse();
            assertThat(TranCatBalRecord.newInstance(ASCII)
                    .tranCatBal(new BigDecimal("-0.01")).tranCatBalIsZero()).isFalse();
        }

        @Test
        @DisplayName("INITIALIZE sets the four named items and leaves FILLER exactly as it was")
        void initializeSkipsFillerExactlyAsCobolDoes() {
            // The behaviour worth being careful about. INITIALIZE without REPLACING skips FILLER, and
            // in 2700-A-CREATE-TCATBAL-REC the WRITE at :510 sends those reserved bytes to the dataset.
            // Blanking them would be a 22-byte parity difference per created record.
            TranCatBalRecord record = firstFixtureRecord()
                    .trancatAcctId(99L)
                    .trancatTypeCd("ZZ")
                    .trancatCd(9999)
                    .tranCatBal(new BigDecimal("77.77"))
                    .initialize();

            assertThat(record.rawImage())
                    .isEqualTo(image("00000000000", "  ", "0000", "0000000000{", FIXTURE_FILLER));
            assertThat(record.fillerImage())
                    .as("the 22 reserved bytes arrived as zeros and must still be zeros")
                    .isEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("every mutator returns this, so a create path chains as the COBOL sequences it")
        void everyMutatorReturnsThis() {
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII);
            assertThat(record.trancatAcctId(1L)).isSameAs(record);
            assertThat(record.trancatTypeCd("01")).isSameAs(record);
            assertThat(record.trancatCd(1)).isSameAs(record);
            assertThat(record.tranCatBal(BigDecimal.ONE)).isSameAs(record);
            assertThat(record.addToTranCatBal(BigDecimal.ONE)).isSameAs(record);
            assertThat(record.tranCatKey(new TranCatKey(1L, "01", 1))).isSameAs(record);
            assertThat(record.initialize()).isSameAs(record);
        }

        @Test
        @DisplayName("a negative key component has no representation in PIC 9 and is rejected")
        void aNegativeKeyComponentIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.newInstance(ASCII).trancatAcctId(-1L));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.newInstance(ASCII).trancatCd(-1));
        }
    }

    @Nested
    @DisplayName("Value semantics - the comparison the dataset itself makes")
    class ValueSemantics {

        @Test
        @DisplayName("two records holding the same 50 bytes under the same code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            String row = fixtureRows().get(0);
            assertThat(TranCatBalRecord.decode(row, ASCII))
                    .isEqualTo(TranCatBalRecord.decode(row, ASCII))
                    .hasSameHashCodeAs(TranCatBalRecord.decode(row, ASCII));
        }

        @Test
        @DisplayName("a record equals itself without comparing bytes at all")
        void aRecordEqualsItself() {
            TranCatBalRecord record = firstFixtureRecord();
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCatBalRecord is equal to one")
        void anotherTypeIsNeverEqual() {
            TranCatBalRecord record = firstFixtureRecord();
            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("00000000001010001")).isFalse();
            assertThat(record.equals(record.tranCatKey())).isFalse();
        }

        @Test
        @DisplayName("identical bytes read under a different code page are NOT the same record")
        void sameBytesUnderADifferentCodePageAreNotEqual() {
            // The comparison is bytes AND code page, and this case isolates the second half: the two
            // records hold byte-for-byte identical areas, so only the charset can distinguish them. It
            // must, because the same 50 bytes denote different values under IBM037 than under US-ASCII.
            //
            // Reading ASCII digits under IBM037 works here precisely because this type decodes lazily
            // from its record area: identity compares bytes and code page and never decodes a field, so
            // it is usable on a record whose spans would not decode - which is the state a diagnostic is
            // most often wanted in. Its sibling TranCategoryRecord decodes eagerly and would reject the
            // same pairing outright.
            byte[] row = fixtureRows().get(0).getBytes(ASCII);
            TranCatBalRecord underAscii = TranCatBalRecord.decode(row, ASCII);
            TranCatBalRecord underEbcdic = TranCatBalRecord.decode(row, EBCDIC);

            assertThat(underAscii.encode()).isEqualTo(underEbcdic.encode());
            assertThat(underAscii).isNotEqualTo(underEbcdic);
            assertThat(underEbcdic).isNotEqualTo(underAscii);
        }

        @Test
        @DisplayName("a difference in the reserved FILLER is never mistaken for equality")
        void aFillerDifferenceIsNotEqual() {
            // Two records whose named fields agree but whose reserved bytes differ are not
            // interchangeable: writing one where the other belongs is a real 22-byte difference in the
            // dataset, and the comparison takes the FILLER in for exactly that reason.
            String zeros = fixtureRows().get(0);
            String spaces = image("00000000001", "01", "0001", FIXTURE_BALANCE_IMAGE, " ".repeat(22));
            TranCatBalRecord fromDataset = TranCatBalRecord.decode(zeros, ASCII);
            TranCatBalRecord rebuilt = TranCatBalRecord.decode(spaces, ASCII);

            assertThat(fromDataset.fieldImages()).isEqualTo(rebuilt.fieldImages());
            assertThat(fromDataset).isNotEqualTo(rebuilt);
        }

        @Test
        @DisplayName("comparing bytes means equals never throws on a record that will not decode")
        void equalsNeverThrowsOnUndecodableBytes() {
            // A row whose numeric span holds letters cannot be decoded, and a diagnostic is most wanted
            // exactly then. Byte comparison is what keeps identity usable in that state.
            String corrupt = image("ABCDEFGHIJK", "01", "0001", FIXTURE_BALANCE_IMAGE,
                    FIXTURE_FILLER);
            TranCatBalRecord record = TranCatBalRecord.decode(corrupt, ASCII);
            assertThat(record).isEqualTo(TranCatBalRecord.decode(corrupt, ASCII));
            assertThatIllegalArgumentException().isThrownBy(record::trancatAcctId);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering - what it withholds, and what stays diagnosable")
    class Rendering {

        @Test
        @DisplayName("the account key is masked and the balance is omitted entirely")
        void theKeyIsMaskedAndTheBalanceOmitted() {
            // This record is one account's balance for one transaction category, so rendering both put
            // an attributable financial position into any log line that touched a TCATBALF row - and
            // nothing in the COBOL asked for that, because COBOL has no toString.
            String rendered = firstFixtureRecord().toString();

            assertThat(rendered).startsWith("TRAN-CAT-BAL-RECORD[").endsWith("]");
            assertThat(rendered)
                    .as("the eleven-digit account identifier must not be published in full")
                    .doesNotContain("00000000001")
                    .contains("TRANCAT-ACCT-ID=*******0001");
            assertThat(rendered)
                    .as("a balance has no safely-revealable part, and its width is already in its "
                            + "PICTURE, so even a digit count would disclose the magnitude")
                    .doesNotContain(FIXTURE_BALANCE_IMAGE)
                    .contains("TRAN-CAT-BAL=<omitted>");
            assertThat(rendered)
                    .as("the type and category identify a kind of activity, not a person, and they "
                            + "are what an interest-calculation parity failure is read from")
                    .contains("TRANCAT-TYPE-CD=01")
                    .contains("TRANCAT-CD=0001");
            assertThat(rendered)
                    .as("FILLER is reserved pad: whether it is blank matters, its content does not")
                    .doesNotContain(FIXTURE_FILLER)
                    .contains("FILLER=[text len=22]");
        }

        @Test
        @DisplayName("a non-zero balance is withheld just as a zero one is")
        void anyBalanceIsWithheld() {
            // A guard that only withheld the fixture's zero balance would publish every real one.
            String rendered = TranCatBalRecord.newInstance(ASCII)
                    .trancatAcctId(12345678901L)
                    .tranCatBal(new BigDecimal("-987654321.99"))
                    .toString();
            assertThat(rendered)
                    .doesNotContain("987654321")
                    .doesNotContain("9876543219")
                    .contains("TRAN-CAT-BAL=<omitted>")
                    .contains("TRANCAT-ACCT-ID=*******8901");
        }

        @Test
        @DisplayName("the masked key keeps the field's stored width, so a wrong width is still visible")
        void theMaskKeepsTheStoredWidth() {
            String rendered = firstFixtureRecord().toString();
            int maskedStart = rendered.indexOf("TRANCAT-ACCT-ID=") + "TRANCAT-ACCT-ID=".length();
            String maskedValue = rendered.substring(maskedStart, rendered.indexOf(',', maskedStart));
            assertThat(maskedValue)
                    .hasSize(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH)
                    .startsWith("*".repeat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH - 4));
        }

        @Test
        @DisplayName("a blank FILLER is reported as blank rather than as a width")
        void aBlankFillerIsReportedAsBlank() {
            assertThat(TranCatBalRecord.newInstance(ASCII).toString())
                    .as("a built record's FILLER is spaces, and that difference from the dataset's "
                            + "zeros is worth seeing")
                    .contains("FILLER=[blank]");
        }

        @Test
        @DisplayName("a control character in a rendered span cannot forge a second log line")
        void theRenderingCannotForgeALogLine() {
            // TRANCAT-TYPE-CD is PIC X(02), read straight out of the dataset, so it can hold CR or LF.
            // Rendered raw it appends a log line of its own choosing - CWE-117.
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII).trancatTypeCd("\r\n");
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
            assertThat(rendered.lines()).hasSize(1);
            assertThat(rendered).contains("TRANCAT-TYPE-CD=X'0D'X'0A'");
            // Lossless and rendering-only: the stored bytes still say exactly what was there.
            assertThat(record.trancatTypeCd()).isEqualTo("\r\n");
        }

        @Test
        @DisplayName("the rendering is deterministic and survives bytes that will not decode")
        void theRenderingIsDeterministicAndSafeOnCorruptBytes() {
            String corrupt = image("ABCDEFGHIJK", "01", "0001", "ZZZZZZZZZZZ", FIXTURE_FILLER);
            TranCatBalRecord record = TranCatBalRecord.decode(corrupt, ASCII);

            // Built from raw spans, so it renders where a decode would throw - which is precisely when
            // a diagnostic is wanted. No clock, no identity hash, no locale-dependent formatting.
            assertThat(record.toString())
                    .isEqualTo(TranCatBalRecord.decode(corrupt, ASCII).toString())
                    .doesNotContain("@")
                    .contains("TRAN-CAT-BAL=<omitted>")
                    .doesNotContain("ZZZZZZZZZZZ")
                    .doesNotContain("ABCDEFGHIJK");
        }

        @Test
        @DisplayName("every value the rendering withholds is still reachable by name")
        void withheldValuesRemainReachableByName() {
            // The distinction the whole policy rests on: a value a caller asked for by name is returned
            // in full, and only disclosure that happens because something rendered the object is
            // withdrawn. The parity harness reads through these accessors.
            TranCatBalRecord record = firstFixtureRecord();
            assertThat(record.trancatAcctId()).isEqualTo(1L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000001");
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.tranCatBal()).isEqualByComparingTo("0.00");
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.rawImage()).isEqualTo(fixtureRows().get(0));
            assertThat(record.fieldImages()).containsEntry("TRAN-CAT-BAL", FIXTURE_BALANCE_IMAGE);
        }
    }

    @Nested
    @DisplayName("Structural guards - properties of the type itself (gates G22, G44, G53)")
    class StructuralGuards {

        /**
         * The fields this class actually declares, with the coverage agent's contribution removed.
         *
         * <p>JaCoCo adds a {@code private static transient synthetic boolean[] $jacocoData} probe array
         * to every instrumented class. It is not final and it is not this class's state, so a bare walk
         * over {@code getDeclaredFields()} would fail the no-mutable-static-state guard under
         * {@code verify} while passing under a plain {@code test} run - a guard that only holds when
         * coverage is switched off is worse than none.
         *
         * @return the declared fields, instrumentation artefacts excluded
         */
        private static List<Field> declaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : TranCatBalRecord.class.getDeclaredFields()) {
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
            // Every PIC 9...V... field is a BigDecimal at the declared scale. A double cannot represent
            // a decimal fraction exactly, so one here would silently change money.
            for (Field field : declaredFields()) {
                assertThat(field.getType()).isNotIn(double.class, float.class, Double.class,
                        Float.class);
            }
            for (Method method : TranCatBalRecord.class.getDeclaredMethods()) {
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
            // Reaching the dataset is JDBC over the record image; there is no entity, no table and no
            // schema to map to, and an annotation here would be the first step towards inventing one.
            assertThat(TranCatBalRecord.class.getAnnotations()).isEmpty();
            for (Field field : declaredFields()) {
                assertThat(field.getAnnotations())
                        .as("field %s must carry no mapping annotation", field.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no mutable static state, so the type is safe to share across threads (G53)")
        void noMutableStaticState() {
            for (Field field : declaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the record area is private and final, and every constructor is private")
        void theRecordAreaIsPrivate() {
            // The 50-byte area is the single source of truth and is never handed out except as a copy,
            // which is what keeps a field value and the record's bytes from ever disagreeing.
            for (Field field : declaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (var constructor : TranCatBalRecord.class.getDeclaredConstructors()) {
                assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()))
                        .as("every entry point states its charset explicitly, so %s must be private",
                                constructor)
                        .isTrue();
            }
        }
    }
}
