package com.vsergeychik.carddemo.statement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link Stm03CustomerRecord}, the {@code CUSTOMER-RECORD} of {@code app/cpy/CUSTREC.cpy} as
 * {@code CBSTM03A} and {@code CBSTM03B} use it.
 *
 * <h2>Why this type exists separately from {@code CustomerRecord}</h2>
 * {@code CUSTREC.cpy} and {@code CVCUS01Y.cpy} declare near-identical 500-byte layouts, but the
 * date-of-birth field is named {@code CUST-DOB-YYYYMMDD} here and {@code CUST-DOB-YYYY-MM-DD} there.
 * Collapsing the two would lose a field name that field-for-field diffing keys on, so both are modelled.
 *
 * <h2>What these tests hold in place</h2>
 * Two corrections converge in this class and both are asserted here rather than described.
 *
 * <p>A component reaches storage through the receiver's own {@code MOVE} rule at
 * <strong>construction</strong> time, so the accessors, {@code equals}, {@code hashCode} and the encoded
 * bytes all observe one width-constrained state. Previously only the encode path applied the rule, so a
 * record could compare unequal to another that encoded identically.
 *
 * <p>{@code CUST-ID} and {@code CUST-SSN} are {@code PIC 9(09)}, which is nine digits, so their numeric
 * views are {@code int}. A {@code long} there advertises nineteen digits of Java state the field cannot
 * hold.
 *
 * <h2>The group-move tolerance, and why it is deliberate</h2>
 * {@code app/cbl/CBSTM03A.CBL:388} populates the whole record with
 * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD} - a <strong>group</strong> alphanumeric move, which
 * ignores the elementary pictures underneath and copies bytes. A blank {@code CUSTFILE} record therefore
 * arrives as 500 spaces, numeric spans included. Applying the numeric move rule to such an image would
 * make a record the legacy program handles perfectly well refuse to construct, so a non-digit image in a
 * numeric span is received as bytes. The numeric <em>views</em> still reject it, which is where the
 * complaint belongs.
 */
@DisplayName("Stm03CustomerRecord - CUSTOMER-RECORD of CUSTREC.cpy")
class Stm03CustomerRecordTest {

    /** The code page of the ASCII fixtures, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * A record built from unpadded, natural-width text - the shape a caller writes by hand, and the one
     * that exercises the receiver rules on every component.
     */
    private static Stm03CustomerRecord natural() {
        return new Stm03CustomerRecord("000000001",
                "John",
                "Q",
                "Public",
                "1 Main Street",
                "Apt 2",
                "Springfield",
                "IL",
                "USA",
                "62701",
                "(555)555-0100",
                "(555)555-0101",
                "020973888",
                "IL-DL-9988776655",
                "1980-01-15",
                "9876543210",
                "Y",
                "750");
    }

    @Nested
    @DisplayName("The receiver applies its own MOVE rule at construction")
    class Receiver {

        @Test
        @DisplayName("a short PIC X value is right-space-padded to its declared width")
        void aShortAlphanumericIsPadded() {
            Stm03CustomerRecord record = natural();

            assertThat(record.custFirstName()).isEqualTo("John" + " ".repeat(21)).hasSize(25);
            assertThat(record.custMiddleName()).isEqualTo("Q" + " ".repeat(24)).hasSize(25);
            assertThat(record.custAddrStateCd()).isEqualTo("IL").hasSize(2);
            assertThat(record.custAddrCountryCd()).isEqualTo("USA").hasSize(3);
            assertThat(record.custPriCardHolderInd()).isEqualTo("Y").hasSize(1);
            assertThat(record.custGovtIssuedId()).hasSize(20).startsWith("IL-DL-9988776655");
        }

        @Test
        @DisplayName("an over-wide PIC X value truncates on the RIGHT")
        void anOverWideAlphanumericTruncatesRight() {
            // PIC X fills from its leftmost position and discards the overflow.
            Stm03CustomerRecord record = new Stm03CustomerRecord("000000001",
                    "A".repeat(30), "Q", "Public", "1 Main Street", "Apt 2", "Springfield",
                    "Illinois", "United States", "62701", "(555)555-0100", "(555)555-0101",
                    "020973888", "IL-DL-9988776655", "1980-01-15", "9876543210", "Y", "750");

            assertThat(record.custFirstName()).isEqualTo("A".repeat(25));
            assertThat(record.custAddrStateCd()).isEqualTo("Il");
            assertThat(record.custAddrCountryCd()).isEqualTo("Uni");
        }

        @Test
        @DisplayName("an over-wide numeric value truncates on the LEFT, keeping the low-order digits")
        void anOverWideNumericTruncatesLeft() {
            // A numeric receiver aligns on its implied decimal point, so the HIGH-order digits are the
            // ones lost. This is the opposite direction from PIC X and is the single most common source
            // of a silent MOVE defect.
            Stm03CustomerRecord record = new Stm03CustomerRecord("1234567890",
                    "John", "Q", "Public", "1 Main Street", "Apt 2", "Springfield", "IL", "USA",
                    "62701", "(555)555-0100", "(555)555-0101", "020973888", "IL-DL-9988776655",
                    "1980-01-15", "9876543210", "Y", "1850");

            assertThat(record.custId()).isEqualTo("234567890");
            assertThat(record.custFicoCreditScore()).isEqualTo("850");
        }

        @Test
        @DisplayName("a short numeric value is LEFT-zero-filled")
        void aShortNumericIsLeftZeroFilled() {
            Stm03CustomerRecord record = new Stm03CustomerRecord("42",
                    "John", "Q", "Public", "1 Main Street", "Apt 2", "Springfield", "IL", "USA",
                    "62701", "(555)555-0100", "(555)555-0101", "7", "IL-DL-9988776655",
                    "1980-01-15", "9876543210", "Y", "8");

            assertThat(record.custId()).isEqualTo("000000042");
            assertThat(record.custSsn()).isEqualTo("000000007");
            assertThat(record.custFicoCreditScore()).isEqualTo("008");
        }

        @Test
        @DisplayName("every observation agrees: accessor, equality, hash and encoded bytes")
        void everyObservationAgrees() {
            // The correction itself. Before it, only encode() applied the width rule, so these two - one
            // written naturally and one written at full width - had different components and compared
            // unequal while encoding to identical bytes.
            Stm03CustomerRecord written = natural();
            Stm03CustomerRecord alreadyPadded = new Stm03CustomerRecord("000000001",
                    "John" + " ".repeat(21), "Q" + " ".repeat(24), "Public" + " ".repeat(19),
                    "1 Main Street" + " ".repeat(37), "Apt 2" + " ".repeat(45),
                    "Springfield" + " ".repeat(39), "IL", "USA", "62701     ",
                    "(555)555-0100  ", "(555)555-0101  ", "020973888",
                    "IL-DL-9988776655    ", "1980-01-15", "9876543210", "Y", "750");

            assertThat(written).isEqualTo(alreadyPadded);
            assertThat(written).hasSameHashCodeAs(alreadyPadded);
            assertThat(written.custFirstName()).isEqualTo(alreadyPadded.custFirstName());
            assertThat(written.encode(ASCII)).isEqualTo(alreadyPadded.encode(ASCII));
        }

        @Test
        @DisplayName("an empty string and spaces are indistinguishable, as they are in COBOL")
        void anEmptyStringAndSpacesAreIndistinguishable() {
            Stm03CustomerRecord empty = new Stm03CustomerRecord("000000001",
                    "", "", "", "", "", "", "", "", "", "", "", "020973888", "", "", "", "", "750");

            assertThat(empty.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(empty.custAddrLine1()).isEqualTo(" ".repeat(50));
            assertThat(empty.custPriCardHolderInd()).isEqualTo(" ");
            assertThat(empty.custDobYyyymmdd()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("null is refused, naming the field and saying what to pass instead")
        void nullIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(null,
                            "John", "Q", "Public", "1", "2", "3", "IL", "USA", "62701", "p1", "p2",
                            "020973888", "id", "1980-01-15", "eft", "Y", "750"))
                    .withMessageContainingAll("CUST-ID", "spaces or an empty string");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord("000000001",
                            "John", "Q", "Public", "1", "2", "3", "IL", "USA", "62701", "p1", "p2",
                            "020973888", "id", "1980-01-15", "eft", "Y", null))
                    .withMessageContaining("CUST-FICO-CREDIT-SCORE");
        }
    }

    @Nested
    @DisplayName("The group-move tolerance: bytes the elementary pictures never saw")
    class GroupMoveTolerance {

        @Test
        @DisplayName("an all-spaces numeric span constructs, because a group MOVE can produce one")
        void anAllSpacesNumericSpanConstructs() {
            // CBSTM03A.CBL:388 moves 1000 bytes into the 500-byte CUSTOMER-RECORD as one alphanumeric
            // group move. Rejecting this would fail a record the legacy program handles.
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThat(blankByGroupMove.custId()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custFicoCreditScore()).isEqualTo("   ");
        }

        @Test
        @DisplayName("such a record still round-trips to the very same 500 bytes")
        void suchARecordStillRoundTrips() {
            byte[] spaces = " ".repeat(500).getBytes(ASCII);

            assertThat(Stm03CustomerRecord.decode(spaces, ASCII).encode(ASCII)).isEqualTo(spaces);
        }

        @Test
        @DisplayName("the numeric VIEW still refuses it - the complaint belongs there, not at construction")
        void theNumericViewStillRefusesIt() {
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custIdValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custSsnValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custFicoCreditScoreValue(ASCII));
        }

        @Test
        @DisplayName("an EMPTY numeric image becomes spaces, not zeros")
        void anEmptyNumericImageBecomesSpaces() {
            // An empty string is not a run of digits, so the PIC X rule applies and the span blanks. It
            // must NOT zero-fill: "000000000" is a real customer id of zero, while spaces are the absence
            // of one, and a group MOVE is the only thing that produces either. Keeping them distinct is
            // what lets the numeric view below refuse the blank while accepting the zero.
            Stm03CustomerRecord record = new Stm03CustomerRecord("",
                    "John", "Q", "Public", "1", "2", "3", "IL", "USA", "62701", "p1", "p2",
                    "", "id", "1980-01-15", "eft", "Y", "");

            assertThat(record.custId()).isEqualTo(" ".repeat(9));
            assertThat(record.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(record.custFicoCreditScore()).isEqualTo("   ");
            assertThatIllegalArgumentException().isThrownBy(() -> record.custIdValue(ASCII));
        }

        @Test
        @DisplayName("a partly numeric image in a numeric span is received as bytes, not renumbered")
        void aPartlyNumericImageIsReceivedAsBytes() {
            // Not all digits, so the PIC X rule applies and the bytes survive untouched. Left-truncating
            // this would silently rewrite storage the program can still read out.
            Stm03CustomerRecord record = new Stm03CustomerRecord("12 45678X",
                    "John", "Q", "Public", "1", "2", "3", "IL", "USA", "62701", "p1", "p2",
                    "020973888", "id", "1980-01-15", "eft", "Y", "750");

            assertThat(record.custId()).isEqualTo("12 45678X");
        }
    }

    @Nested
    @DisplayName("Numeric views are int, because PIC 9(09) is nine digits")
    class NumericViews {

        @ParameterizedTest(name = "{0} reads back as {1}")
        @DisplayName("a nine-digit image reads back through an int view")
        @CsvSource({
            "000000001, 1",
            "000000042, 42",
            "020973888, 20973888",
            "999999999, 999999999",
        })
        void aNineDigitImageReadsBackAsInt(String image, int expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(image,
                    "John", "Q", "Public", "1", "2", "3", "IL", "USA", "62701", "p1", "p2",
                    image, "id", "1980-01-15", "eft", "Y", "750");

            assertThat(record.custIdValue(ASCII)).isEqualTo(expected);
            assertThat(record.custSsnValue(ASCII)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the widest PIC 9(09) value fits an int with room to spare")
        void theWidestValueFitsAnInt() {
            assertThat(999_999_999).isLessThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("the numeric views are declared int, not long")
        void theNumericViewsAreDeclaredInt() throws Exception {
            assertThat(Stm03CustomerRecord.class.getMethod("custIdValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custSsnValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custFicoCreditScoreValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
        }

        @Test
        @DisplayName("the raw image keeps the leading zero the int view cannot")
        void theRawImageKeepsItsLeadingZero() {
            // A real fixture SSN. Sliced from the image it reads 020-97-3888; sliced from the integer
            // 20973888 it would read 209-73-888.
            Stm03CustomerRecord record = natural();

            assertThat(record.custSsn()).isEqualTo("020973888");
            assertThat(record.custSsnValue(ASCII)).isEqualTo(20973888);
        }

        @Test
        @DisplayName("the FICO score reads back through its own int view")
        void theFicoScoreReadsBack() {
            assertThat(natural().custFicoCreditScoreValue(ASCII)).isEqualTo(750);
        }
    }

    @Nested
    @DisplayName("Serialisation")
    class Serialisation {

        @Test
        @DisplayName("the record is 500 bytes with a 168-byte blank FILLER")
        void theRecordIsFiveHundredBytes() {
            byte[] image = natural().encode(ASCII);

            assertThat(image).hasSize(Stm03CustomerRecord.RECORD_LENGTH);
            assertThat(new String(image, ASCII)
                    .substring(Stm03CustomerRecord.FILLER_OFFSET))
                    .as("FILLER must be emitted as spaces or every downstream offset breaks")
                    .isEqualTo(" ".repeat(Stm03CustomerRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("decode then encode is byte-identical under both code pages")
        void decodeThenEncodeIsByteIdentical() {
            for (Charset charset : new Charset[] {ASCII, EBCDIC}) {
                byte[] image = natural().encode(charset);

                assertThat(Stm03CustomerRecord.decode(image, charset).encode(charset))
                        .as("round trip under %s", charset.name())
                        .isEqualTo(image);
                assertThat(Stm03CustomerRecord.decode(image, charset)).isEqualTo(natural());
            }
        }

        @Test
        @DisplayName("the same record encodes to different bytes under the two code pages")
        void theCodePageIsTheCallers() {
            assertThat(natural().encode(EBCDIC))
                    .hasSize(500)
                    .isNotEqualTo(natural().encode(ASCII));
        }

        @Test
        @DisplayName("blank() zero-fills the numeric spans and blanks the rest")
        void blankZeroFillsTheNumericSpans() {
            // Distinct from the group-move blank above: this is the INITIALIZE-style state built from the
            // layout, where each span is padded according to its own picture.
            Stm03CustomerRecord blank = Stm03CustomerRecord.blank(ASCII);

            assertThat(blank.custId()).isEqualTo("000000000");
            assertThat(blank.custSsn()).isEqualTo("000000000");
            assertThat(blank.custFicoCreditScore()).isEqualTo("000");
            assertThat(blank.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(blank.custIdValue(ASCII)).isZero();
            assertThat(blank.encode(ASCII)).hasSize(500);
        }

        @Test
        @DisplayName("a wrong-length image is group-MOVEd to 500, not refused")
        void aWrongLengthImageIsGroupMoved() {
            // Deliberate, and it is the whole reason this type is reachable from CBSTM03A at all.
            // CBSTM03A.CBL:388 is MOVE WS-M03B-FLDT TO CUSTOMER-RECORD - a 1000-byte PIC X(1000) source
            // into a 500-byte group receiver - so the record is ALWAYS arrived at by an alphanumeric
            // group move that pads on the right when short and truncates on the right when long.
            // Refusing either would refuse the only way the legacy program populates this record.
            Stm03CustomerRecord shortImage = Stm03CustomerRecord.decode("000000001", ASCII);
            assertThat(shortImage.custId()).isEqualTo("000000001");
            assertThat(shortImage.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(shortImage.encode(ASCII)).hasSize(500);

            String overLong = "X".repeat(500) + "IGNORED TAIL";
            String reEmitted = new String(
                    Stm03CustomerRecord.decode(overLong, ASCII).encode(ASCII), ASCII);

            assertThat(reEmitted)
                    .as("the overflow past byte 500 is discarded")
                    .hasSize(500);
            assertThat(reEmitted.substring(0, Stm03CustomerRecord.FILLER_OFFSET))
                    .as("every byte of every named field survives the move")
                    .isEqualTo("X".repeat(Stm03CustomerRecord.FILLER_OFFSET));
            assertThat(reEmitted.substring(Stm03CustomerRecord.FILLER_OFFSET))
                    .as("FILLER is not a component of this record, so it is re-emitted as spaces - "
                            + "which is what the copybook declares it to hold and what gate G21 "
                            + "requires; a round trip is therefore byte-identical for any record "
                            + "whose FILLER is already blank, which is every real one")
                    .isEqualTo(" ".repeat(Stm03CustomerRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("a null image is refused, pointing at blank(Charset) instead")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((String) null, ASCII))
                    .withMessageContaining("blank(Charset)");
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode(new byte[500], null));
        }

        @Test
        @DisplayName("the DOB field is named CUST-DOB-YYYYMMDD, which is what keeps this type separate")
        void theDobFieldKeepsItsCustrecName() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.name()).isEqualTo("CUST-DOB-YYYYMMDD");
            assertThat(natural().fieldImages(ASCII)).containsKey("CUST-DOB-YYYYMMDD");
            assertThat(natural().spanImage(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, ASCII))
                    .isEqualTo("1980-01-15");
        }
    }
}
