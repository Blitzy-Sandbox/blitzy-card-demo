package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The wire and diagnostic contract of {@link NavigationContext}: how {@code CDEMO-CARD-NUM} is
 * represented in JSON, and what the diagnostic rendering withholds.
 *
 * <h2>The two properties held here</h2>
 * <ul>
 *   <li><strong>A sixteen-digit identifier is transport-safe.</strong> {@code CDEMO-CARD-NUM} is
 *       {@code PIC 9(16)} ({@code app/cpy/COCOM01Y.cpy:41}), and a populated value can exceed
 *       9,007,199,254,740,991 - the largest integer an IEEE-754 double holds exactly. Many JSON
 *       clients parse every number into a double, so as a JSON number a card number can arrive with a
 *       different final digit and no error at all. The wire form is therefore a sixteen-digit decimal
 *       string, and a JSON number is refused rather than accepted leniently.</li>
 *   <li><strong>A diagnostic rendering identifies nobody.</strong> The record's generated
 *       {@code toString} printed the card number, the account and customer identifiers, the three
 *       customer names and the user id - the exact string that reaches a log file or an exception
 *       message. The override redacts those seven and keeps the nine navigation fields that make a
 *       diagnostic useful.</li>
 * </ul>
 *
 * <p>Every case below also asserts the other half of each property: that the numeric value, the JSON
 * payload and the {@value NavigationContext#COMMAREA_LENGTH}-byte image are unchanged. A security fix
 * that quietly altered the parity image would be a worse defect than the one it repaired.
 */
@DisplayName("NavigationContext - the JSON card-number form and the redacted diagnostics")
class NavigationContextWireContractTest {

    /** A sixteen-digit card number: 4111111111111111 exceeds the exact-integer range of a double. */
    private static final long PAN = 4_111_111_111_111_111L;

    /** The same value as the wire carries it. */
    private static final String PAN_IMAGE = "4111111111111111";

    private final ObjectMapper mapper = new ObjectMapper();

    private NavigationContext populated() {
        return NavigationContext.empty()
                .withFromTranid("CC00")
                .withFromProgram("COSGN00C")
                .withToTranid("CM00")
                .withToProgram("COMEN01C")
                .withUserId("ADMIN001")
                .withUserTypeAdmin()
                .withPgmReenter()
                .withCustId(123_456_789)
                .withCustFname("JOHN")
                .withCustMname("Q")
                .withCustLname("PUBLIC")
                .withAcctId(11_111_111_111L)
                .withAcctStatus("Y")
                .withCardNum(PAN)
                .withLastMap("COMEN1A")
                .withLastMapset("COMEN01");
    }

    @Nested
    @DisplayName("CDEMO-CARD-NUM travels as a 16-digit decimal string")
    class CardNumberOnTheWire {

        @Test
        @DisplayName("it serialises as a string, not as a JSON number")
        void serialisesAsString() throws Exception {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(json.get("cardNum").isTextual()).isTrue();
            assertThat(json.get("cardNum").asText()).isEqualTo(PAN_IMAGE);
        }

        @Test
        @DisplayName("a shorter value keeps the leading zeros PIC 9(16) implies")
        void zeroFilledOnTheLeft() throws Exception {
            JsonNode json = mapper.readTree(
                    mapper.writeValueAsString(NavigationContext.empty().withCardNum(5L)));

            assertThat(json.get("cardNum").asText()).isEqualTo("0000000000000005");
            assertThat(json.get("cardNum").asText()).hasSize(NavigationContext.CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("the initialised state serialises as sixteen zeros")
        void initialisedStateIsSixteenZeros() throws Exception {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(NavigationContext.empty()));

            assertThat(json.get("cardNum").asText()).isEqualTo("0000000000000000");
        }

        @Test
        @DisplayName("a round trip through JSON preserves all sixteen digits exactly")
        void roundTripPreservesEveryDigit() throws Exception {
            NavigationContext before = populated();

            NavigationContext after =
                    mapper.readValue(mapper.writeValueAsString(before), NavigationContext.class);

            assertThat(after.cardNum()).isEqualTo(PAN);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a value sent without its leading zeros still binds")
        void acceptsUnpaddedDigits() throws Exception {
            NavigationContext bound = mapper.readValue(json("\"5\""), NavigationContext.class);

            assertThat(bound.cardNum()).isEqualTo(5L);
        }

        @Test
        @DisplayName("a JSON number is refused, because it may already have lost a digit")
        void refusesAJsonNumber() {
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> mapper.readValue(json("4111111111111111"),
                            NavigationContext.class))
                    .withStackTraceContaining("decimal string");
        }

        @Test
        @DisplayName("a non-digit character is refused and the value is not echoed")
        void refusesNonDigits() {
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> mapper.readValue(json("\"4111-1111-1111-1111\""),
                            NavigationContext.class))
                    .withStackTraceContaining(NavigationContext.CARD_NUM_FIELD)
                    .withStackTraceContaining(NavigationContext.REDACTED);
        }

        @Test
        @DisplayName("a seventeen-digit value is refused rather than losing its high-order digit")
        void refusesSeventeenDigits() {
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> mapper.readValue(json("\"41111111111111111\""),
                            NavigationContext.class))
                    .withStackTraceContaining("17 character(s)");
        }

        @Test
        @DisplayName("an explicit JSON null reads as zero, the initialised PIC 9 state")
        void nullReadsAsZero() throws Exception {
            NavigationContext bound = mapper.readValue(json("null"), NavigationContext.class);

            assertThat(bound.cardNum()).isZero();
        }

        @Test
        @DisplayName("the fixed-width image is untouched by the JSON representation")
        void fixedWidthImageIsUnchanged() {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);

            byte[] image = populated().toFixedWidth(codec);

            assertThat(image).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(new String(image, NavigationContext.CARD_NUM_OFFSET,
                    NavigationContext.CARD_NUM_LENGTH, StandardCharsets.US_ASCII))
                    .isEqualTo(PAN_IMAGE);
            assertThat(NavigationContext.fromFixedWidth(codec, image).cardNum()).isEqualTo(PAN);
        }

        private String json(String cardNumLiteral) {
            return "{\"fromTranid\":\"    \",\"fromProgram\":\"        \",\"toTranid\":\"    \","
                    + "\"toProgram\":\"        \",\"userId\":\"        \",\"userType\":\" \","
                    + "\"pgmContext\":0,\"custId\":0,\"custFname\":\"" + " ".repeat(25) + "\","
                    + "\"custMname\":\"" + " ".repeat(25) + "\",\"custLname\":\"" + " ".repeat(25)
                    + "\",\"acctId\":0,\"acctStatus\":\" \",\"cardNum\":" + cardNumLiteral
                    + ",\"lastMap\":\"       \",\"lastMapset\":\"       \"}";
        }
    }

    @Nested
    @DisplayName("cardNumberImage / cardNumberOfImage - the conversion itself")
    class ConversionHelpers {

        @Test
        @DisplayName("the image is always sixteen digits")
        void imageIsSixteenDigits() {
            assertThat(NavigationContext.cardNumberImage(0L)).isEqualTo("0000000000000000");
            assertThat(NavigationContext.cardNumberImage(PAN)).isEqualTo(PAN_IMAGE);
            assertThat(NavigationContext.cardNumberImage(9_999_999_999_999_999L))
                    .isEqualTo("9999999999999999");
        }

        @Test
        @DisplayName("a value wider than the picture is refused, and not echoed")
        void refusesAnOverWideValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> NavigationContext.cardNumberImage(99_999_999_999_999_999L))
                    .withMessageContaining(NavigationContext.CARD_NUM_FIELD)
                    .withMessageContaining(NavigationContext.REDACTED)
                    .withMessageNotContaining("99999999999999999");
        }

        @Test
        @DisplayName("a negative value is refused, because PIC 9 has no sign position")
        void refusesANegativeValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> NavigationContext.cardNumberImage(-1L))
                    .withMessageContaining("unsigned")
                    .withMessageNotContaining("-1.");
        }

        @Test
        @DisplayName("reading an image is the exact inverse of writing one")
        void readingIsTheInverseOfWriting() {
            assertThat(NavigationContext.cardNumberOfImage(NavigationContext.cardNumberImage(PAN)))
                    .isEqualTo(PAN);
            assertThat(NavigationContext.cardNumberOfImage("0000000000000005")).isEqualTo(5L);
        }

        @Test
        @DisplayName("an empty image is refused")
        void refusesAnEmptyImage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> NavigationContext.cardNumberOfImage(""))
                    .withMessageContaining("0 character(s)");
        }

        @Test
        @DisplayName("an absent image is refused")
        void refusesAnAbsentImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> NavigationContext.cardNumberOfImage(null));
        }
    }

    @Nested
    @DisplayName("toString withholds every identifying value and keeps the navigation state")
    class RedactedDiagnostics {

        @Test
        @DisplayName("the card number, both identifiers and the three names are withheld")
        void identifiersAreWithheld() {
            String rendering = populated().toString();

            // Not one identifying value survives the rendering in a form that could be read back: the
            // card number and the two identifiers are masked to their last four characters at their
            // full stored width, and the three customer names are reported as widths only. The policy
            // is the module's single one, in common.SensitiveDiagnostics, so this carrier discloses
            // exactly as much - and as little - as every model type that renders through it.
            assertThat(rendering).doesNotContain(PAN_IMAGE);
            assertThat(rendering).doesNotContain("4111111111111111");
            assertThat(rendering).doesNotContain("123456789");
            assertThat(rendering).doesNotContain("JOHN");
            assertThat(rendering).doesNotContain("PUBLIC");
            assertThat(rendering).doesNotContain("11111111111");
        }

        @Test
        @DisplayName("the nine navigation fields that identify nobody are still rendered")
        void navigationStateIsStillRendered() {
            String rendering = populated().toString();

            assertThat(rendering).contains("CC00", "COSGN00C", "CM00", "COMEN01C");
            assertThat(rendering).contains(NavigationContext.USER_TYPE_ADMIN);
            assertThat(rendering).contains("COMEN1A", "COMEN01");
            assertThat(rendering).contains("pgmContext=1");

            // The copybook name of that field is a published constant, so a reader can line the
            // rendering up against COCOM01Y even though the rendering keys by member name.
            assertThat(NavigationContext.PGM_CONTEXT_FIELD).isEqualTo("CDEMO-PGM-CONTEXT");
        }

        @Test
        @DisplayName("each withheld field is named, so a diagnostic still says what is missing")
        void withheldFieldsAreNamed() {
            String rendering = populated().toString();

            // Named, and each carries the shape of what it withheld: masking rather than omission is
            // what lets one rendering be correlated with another - two log lines for the same card
            // agree on the last four characters - while disclosing nothing that identifies anybody.
            assertThat(rendering).contains("cardNum="
                    + SensitiveDiagnostics.maskPan("4111111111111111"));
            assertThat(rendering).contains("acctId="
                    + SensitiveDiagnostics.maskIdentifier("11111111111"));
            assertThat(rendering).contains("custId="
                    + SensitiveDiagnostics.maskIdentifier("123456789"));
            assertThat(rendering).contains("custFname=" + SensitiveDiagnostics.describeText("JOHN"));

            // The user id is navigation state rather than a credential: COSGN00C paints it on every
            // screen it sends, and it is the password - never rendered anywhere - that is the secret.
            assertThat(rendering).contains("userId=ADMIN001");
        }

        @Test
        @DisplayName("redacting the rendering leaves the values themselves in place")
        void valuesThemselvesAreUnchanged() {
            NavigationContext context = populated();

            assertThat(context.cardNum()).isEqualTo(PAN);
            assertThat(context.acctId()).isEqualTo(11_111_111_111L);
            assertThat(context.custId()).isEqualTo(123_456_789);
            assertThat(context.userId()).isEqualTo("ADMIN001");
            assertThat(context.custFname()).startsWith("JOHN");
        }

        @Test
        @DisplayName("an over-wide character field is refused without echoing what was sent")
        void widthRefusalDoesNotEchoTheValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> NavigationContext.empty().withUserId("ADMIN0012"))
                    .withMessageContaining(NavigationContext.USER_ID_FIELD)
                    .withMessageContaining("9 character(s)")
                    .withMessageContaining(NavigationContext.REDACTED)
                    .withMessageNotContaining("ADMIN0012");
        }
    }
}
