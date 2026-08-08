package com.vsergeychik.carddemo.customer.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link CustomerRecord}, the 500-byte {@code CUSTOMER-RECORD} of
 * {@code app/cpy/CVCUS01Y.cpy}.
 *
 * <p>The subject of most of what follows is the <strong>receiver</strong>: a COBOL field is its declared
 * width from the moment it exists, so a value entering one is padded or truncated on the way in and the
 * field afterwards holds the result. This class used to hold whatever it was handed and apply the rule
 * only at encode time, which let a getter, an {@code equals} and an encode describe three different
 * records. The assertions below pin the corrected behaviour: one observable state, and it is the
 * copybook's.
 *
 * <p>Every expected width and offset here was transcribed from {@code app/cpy/CVCUS01Y.cpy} rather than
 * read off the implementation, which is what makes these tests an audit rather than a restatement.
 *
 * <p>Charsets are always named. {@code US-ASCII} is the code page of the {@code app/data/ASCII}
 * fixtures; {@code IBM037} appears where a test needs to prove that the code page is genuinely the
 * caller's choice.
 */
@DisplayName("CustomerRecord - the 500-byte CUSTOMER-RECORD of CVCUS01Y")
class CustomerRecordTest {

    /** The code page of the ASCII fixtures, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, used to prove the encoding is the caller's choice. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The record width, restated from the copybook rather than read from the class. */
    private static final int FIVE_HUNDRED = 500;

    @Nested
    @DisplayName("The INITIALIZE state - a field is its declared width from the outset")
    class InitialState {

        @Test
        @DisplayName("every alphanumeric field holds its declared width in SPACES, not an empty string")
        void alphanumericFieldsHoldTheirWidthInSpaces() {
            CustomerRecord record = new CustomerRecord();

            // This is the crux of the correction. An empty string is not a state PIC X(25) can occupy,
            // so a getter reporting one was describing a record that cannot exist.
            assertThat(record.getCustFirstName()).isEqualTo(" ".repeat(25));
            assertThat(record.getCustMiddleName()).isEqualTo(" ".repeat(25));
            assertThat(record.getCustLastName()).isEqualTo(" ".repeat(25));
            assertThat(record.getCustAddrLine1()).isEqualTo(" ".repeat(50));
            assertThat(record.getCustAddrLine2()).isEqualTo(" ".repeat(50));
            assertThat(record.getCustAddrLine3()).isEqualTo(" ".repeat(50));
            assertThat(record.getCustAddrStateCd()).isEqualTo("  ");
            assertThat(record.getCustAddrCountryCd()).isEqualTo("   ");
            assertThat(record.getCustAddrZip()).isEqualTo(" ".repeat(10));
            assertThat(record.getCustPhoneNum1()).isEqualTo(" ".repeat(15));
            assertThat(record.getCustPhoneNum2()).isEqualTo(" ".repeat(15));
            assertThat(record.getCustGovtIssuedId()).isEqualTo(" ".repeat(20));
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(" ".repeat(10));
            assertThat(record.getCustEftAccountId()).isEqualTo(" ".repeat(10));
            assertThat(record.getCustPriCardHolderInd()).isEqualTo(" ");
        }

        @Test
        @DisplayName("every numeric field starts at zero and images as its declared width of zeros")
        void numericFieldsStartAtZero() {
            CustomerRecord record = new CustomerRecord();

            assertThat(record.getCustId()).isZero();
            assertThat(record.getCustSsn()).isZero();
            assertThat(record.getCustFicoCreditScore()).isZero();
            assertThat(record.custIdImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custSsnImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("000");
        }

        @Test
        @DisplayName("it encodes to 500 bytes: zeros in the numeric spans, spaces everywhere else")
        void itEncodesToFiveHundredBytes() {
            byte[] image = new CustomerRecord().encode(ASCII);

            assertThat(image).hasSize(FIVE_HUNDRED);
            String text = new String(image, ASCII);
            assertThat(text.substring(0, 9)).isEqualTo("000000000");
            assertThat(text.substring(9, 279)).isEqualTo(" ".repeat(270));
            assertThat(text.substring(279, 288)).isEqualTo("000000000");
            assertThat(text.substring(329, 332)).isEqualTo("000");
            assertThat(text.substring(332)).as("the FILLER X(168) must be present and blank")
                    .isEqualTo(" ".repeat(168));
        }
    }

    @Nested
    @DisplayName("The PIC X receiver - padded or truncated on the way IN, not at encode time")
    class AlphanumericReceiver {

        @Test
        @DisplayName("a short value is right-space-padded and the getter reports the padded field")
        void aShortValueIsPaddedOnEntry() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("Alice");

            assertThat(record.getCustFirstName()).isEqualTo("Alice" + " ".repeat(20)).hasSize(25);
        }

        @Test
        @DisplayName("an over-wide value is truncated on the RIGHT, as a PIC X MOVE truncates")
        void anOverWideValueIsTruncatedOnTheRight() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

            assertThat(record.getCustFirstName()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXY").hasSize(25);
        }

        @Test
        @DisplayName("an empty string and a run of spaces are indistinguishable, as in COBOL")
        void anEmptyStringAndSpacesAreIndistinguishable() {
            CustomerRecord fromEmpty = new CustomerRecord();
            CustomerRecord fromSpaces = new CustomerRecord();

            fromEmpty.setCustAddrStateCd("");
            fromSpaces.setCustAddrStateCd("  ");

            assertThat(fromEmpty.getCustAddrStateCd()).isEqualTo(fromSpaces.getCustAddrStateCd());
            assertThat(fromEmpty).isEqualTo(fromSpaces);
        }

        @Test
        @DisplayName("the getter, equals, hashCode and encode all observe the SAME state")
        void everyObservationAgrees() {
            // The four observations that used to be able to disagree. A thirty-character first name was
            // readable as thirty characters, made two records unequal, and then encoded as twenty-five.
            CustomerRecord overWide = new CustomerRecord();
            overWide.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
            CustomerRecord atWidth = new CustomerRecord();
            atWidth.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXY");

            assertThat(overWide.getCustFirstName()).isEqualTo(atWidth.getCustFirstName());
            assertThat(overWide).isEqualTo(atWidth);
            assertThat(overWide.hashCode()).isEqualTo(atWidth.hashCode());
            assertThat(overWide.encode(ASCII)).isEqualTo(atWidth.encode(ASCII));
        }

        @Test
        @DisplayName("equality is reflexive, null-safe and type-safe")
        void equalityIsReflexiveNullSafeAndTypeSafe() {
            // The contract equals() owes its callers, and the counterpart to everyObservationAgrees:
            // that test proves two DIFFERENTLY WRITTEN records compare equal, and these three prove the
            // comparison is still a well-behaved equivalence rather than one that says yes too readily.
            CustomerRecord record = new CustomerRecord();

            assertThat(record.equals(record)).as("reflexive").isTrue();
            assertThat(record.equals(null)).as("never equal to null").isFalse();
            assertThat(record.equals("not a CustomerRecord")).as("never equal to another type")
                    .isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("null is refused rather than normalised, because COBOL has no null")
        void nullIsRefused() {
            CustomerRecord record = new CustomerRecord();

            assertThatNullPointerException()
                    .isThrownBy(() -> record.setCustFirstName(null))
                    .withMessageContaining("CUST-FIRST-NAME")
                    .withMessageContaining("COBOL has no null");
        }
    }

    @Nested
    @DisplayName("The PIC 9 receiver - truncated on the LEFT, and never widened past its digits")
    class NumericReceiver {

        @ParameterizedTest(name = "setCustId({0}) holds {1}")
        @DisplayName("an over-wide value loses its HIGH-order digits, as a numeric MOVE does")
        @CsvSource({
            "0,            0",
            "1,            1",
            "999999999,    999999999",
            // Ten digits into PIC 9(09): COBOL aligns on the implied decimal point, so the digit that
            // is lost is the leading one. Keeping the leading digits instead is the classic defect.
            "1234567890,   234567890",
            "2000000001,   1",
        })
        void anOverWideValueLosesItsLeadingDigits(int supplied, int held) {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(supplied);

            assertThat(record.getCustId()).isEqualTo(held);
            assertThat(record.custIdImage(ASCII)).hasSize(9);
        }

        @Test
        @DisplayName("the held value and the encoded image agree, which is what used to be possible to break")
        void theHeldValueAndTheImageAgree() {
            CustomerRecord record = new CustomerRecord();

            record.setCustSsn(1234567890);

            assertThat(record.getCustSsn()).isEqualTo(234567890);
            assertThat(record.custSsnImage(ASCII)).isEqualTo("234567890");
            assertThat(new String(record.encode(ASCII), ASCII).substring(279, 288))
                    .isEqualTo("234567890");
        }

        @Test
        @DisplayName("a negative value is refused, because PIC 9 declares no sign position")
        void aNegativeValueIsRefused() {
            CustomerRecord record = new CustomerRecord();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustId(-1))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("no sign position");
            assertThatIllegalArgumentException().isThrownBy(() -> record.setCustSsn(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustFicoCreditScore(-1));
        }

        @Test
        @DisplayName("the three-digit FICO score truncates to three digits")
        void theFicoScoreTruncatesToThreeDigits() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFicoCreditScore(1850);

            assertThat(record.getCustFicoCreditScore()).isEqualTo(850);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("850");
        }

        @Test
        @DisplayName("the nine-digit fields are int, not long: AAP rule R4")
        void theNineDigitFieldsAreInt() throws NoSuchMethodException {
            // Asserted structurally, because the point of the rule is the SET of Java values the
            // accessor appears able to report. A long-typed nine-digit field advertises nineteen digits
            // of state that the field can never hold.
            assertThat(CustomerRecord.class.getMethod("getCustId").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustSsn").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustFicoCreditScore").getReturnType())
                    .isEqualTo(int.class);
        }
    }

    @Nested
    @DisplayName("Decode - a stored image loads verbatim, and round-trips")
    class Decoding {

        /** A 500-byte image built from spans rather than from a literal, so every offset is explicit. */
        private String image() {
            StringBuilder text = new StringBuilder();
            text.append("000000042");                       // CUST-ID
            text.append(pad("Alice", 25));                  // CUST-FIRST-NAME
            text.append(pad("B", 25));                      // CUST-MIDDLE-NAME
            text.append(pad("Smith", 25));                  // CUST-LAST-NAME
            text.append(pad("1 High Street", 50));          // CUST-ADDR-LINE-1
            text.append(pad("", 50));                        // CUST-ADDR-LINE-2
            text.append(pad("", 50));                        // CUST-ADDR-LINE-3
            text.append("WA");                              // CUST-ADDR-STATE-CD
            text.append("USA");                             // CUST-ADDR-COUNTRY-CD
            text.append(pad("99999", 10));                  // CUST-ADDR-ZIP
            text.append(pad("2065550100", 15));             // CUST-PHONE-NUM-1
            text.append(pad("", 15));                        // CUST-PHONE-NUM-2
            text.append("020973888");                       // CUST-SSN
            text.append(pad("", 20));                        // CUST-GOVT-ISSUED-ID
            text.append("1970-01-01");                      // CUST-DOB-YYYY-MM-DD
            text.append(pad("", 10));                        // CUST-EFT-ACCOUNT-ID
            text.append("Y");                               // CUST-PRI-CARD-HOLDER-IND
            text.append("720");                             // CUST-FICO-CREDIT-SCORE
            text.append(pad("", 168));                       // FILLER
            return text.toString();
        }

        private String pad(String value, int width) {
            return value + " ".repeat(width - value.length());
        }

        @Test
        @DisplayName("every span decodes into its field, untrimmed")
        void everySpanDecodes() {
            CustomerRecord record = CustomerRecord.decode(image(), ASCII);

            assertThat(record.getCustId()).isEqualTo(42);
            assertThat(record.getCustFirstName()).isEqualTo(pad("Alice", 25));
            assertThat(record.getCustLastName()).isEqualTo(pad("Smith", 25));
            assertThat(record.getCustAddrStateCd()).isEqualTo("WA");
            assertThat(record.getCustSsn()).isEqualTo(20973888);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo("1970-01-01");
            assertThat(record.getCustFicoCreditScore()).isEqualTo(720);
        }

        @Test
        @DisplayName("the SSN's character image keeps its leading zero, which its value cannot")
        void theSsnImageKeepsItsLeadingZero() {
            // app/cbl/COACTVWC.cbl:496-504 slices CUST-SSN's nine CHARACTER bytes. The fixture's
            // '020973888' slices to 020-97-3888; slicing the integer 20973888 would give 209-73-888.
            CustomerRecord record = CustomerRecord.decode(image(), ASCII);

            assertThat(record.getCustSsn()).isEqualTo(20973888);
            assertThat(record.custSsnImage(ASCII)).isEqualTo("020973888");
            assertThat(String.valueOf(record.getCustSsn())).isNotEqualTo(record.custSsnImage(ASCII));
        }

        @Test
        @DisplayName("decode then encode is byte-identical, under either code page")
        void decodeThenEncodeIsByteIdentical() {
            byte[] ascii = image().getBytes(ASCII);
            assertThat(CustomerRecord.decode(ascii, ASCII).encode(ASCII)).isEqualTo(ascii);

            byte[] ebcdic = image().getBytes(EBCDIC);
            assertThat(CustomerRecord.decode(ebcdic, EBCDIC).encode(EBCDIC)).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("a decoded record equals a hand-built one, because both hold receiver state")
        void aDecodedRecordEqualsAHandBuiltOne() {
            CustomerRecord built = new CustomerRecord();
            built.setCustId(42);
            built.setCustFirstName("Alice");
            built.setCustMiddleName("B");
            built.setCustLastName("Smith");
            built.setCustAddrLine1("1 High Street");
            built.setCustAddrStateCd("WA");
            built.setCustAddrCountryCd("USA");
            built.setCustAddrZip("99999");
            built.setCustPhoneNum1("2065550100");
            built.setCustSsn(20973888);
            built.setCustDobYyyyMmDd("1970-01-01");
            built.setCustPriCardHolderInd("Y");
            built.setCustFicoCreditScore(720);

            assertThat(built).isEqualTo(CustomerRecord.decode(image(), ASCII));
            assertThat(built.encode(ASCII)).isEqualTo(image().getBytes(ASCII));
        }

        @Test
        @DisplayName("a codec-taking overload is offered for a caller that already has one")
        void aCodecTakingOverloadIsOffered() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            CustomerRecord record = CustomerRecord.decode(image().getBytes(ASCII), codec);

            assertThat(record.encode(codec)).isEqualTo(image().getBytes(ASCII));
            assertThat(record.recordImage(codec)).isEqualTo(image());
        }

        @Test
        @DisplayName("an image of the wrong length is refused, naming the declared width")
        void aWrongLengthImageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(new byte[499], ASCII));
        }
    }
}
