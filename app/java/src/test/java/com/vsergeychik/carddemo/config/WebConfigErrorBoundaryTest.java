package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.CobolErrorResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Tests for the error boundary in {@link WebConfig.CobolErrorHandler}, and for the configuration that
 * makes it the <em>only</em> boundary.
 *
 * <h2>What is being protected</h2>
 * The width and shape guards throughout this module - in {@link FixedWidthCodec}, in the record models
 * and in the request DTOs - are handed the value they are judging. That is unavoidable: a guard cannot
 * decide that seventeen characters will not fit a {@code PIC X(16)} field without holding the
 * seventeen characters. What is avoidable is those characters reaching an HTTP response body, because
 * the values flowing through these spans are card numbers, account identifiers, government-issued
 * identifiers and EFT account numbers.
 *
 * <p>Two things together prevent it, and both are asserted here. First, no guard message contains the
 * value any more - it names the field, its declared width and the category of the failure. Second,
 * {@code application.yml} publishes none of the framework's own error attributes, so an exception this
 * advice never chose to answer cannot have its text published by the fallback error page instead.
 *
 * <h2>Why the second half needs testing too</h2>
 * A sanitised message is one edit away from being unsanitised, and a guard added next year will not
 * know about this rule. Turning the framework attributes off is the barrier that does not depend on
 * every future author remembering, which is why the shipped configuration is asserted rather than
 * assumed - and asserted against the real {@code application.yml} on the classpath rather than a
 * copy of its values.
 *
 * <p>Plain JUnit 5: no application context, no {@code MockMvc}, no servlet container. Every handler
 * has a package-private static builder for exactly this reason.
 */
@DisplayName("WebConfig error boundary - a failure is reported without echoing what failed")
class WebConfigErrorBoundaryTest {

    /** A card number shaped like a real one, used to prove it never appears in a message. */
    private static final String PAN = "4111111111111111";

    /** The advice under test. It is stateless, so one instance serves every case. */
    private static final CobolErrorHandler HANDLER = new CobolErrorHandler();

    /** A codec over the fixture code page, for driving the guards that used to echo their input. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * Builds an unreadable-body failure whose cause carries a Jackson mapping path.
     *
     * @param fieldNames the field names to place on the path, outermost first
     * @return the failure, with a cause Jackson would have produced
     */
    private static HttpMessageNotReadableException unreadableAt(final String... fieldNames) {
        JsonMappingException mapping =
                JsonMappingException.from((JsonParser) null, "unexpected token at " + PAN);
        // prependPath builds the path from the inside out, so walk the names backwards to end up
        // with the caller's order.
        for (int index = fieldNames.length - 1; index >= 0; index--) {
            mapping.prependPath(Object.class, fieldNames[index]);
        }
        return new HttpMessageNotReadableException("JSON parse error: " + PAN, mapping,
                mock(HttpInputMessage.class));
    }

    /**
     * Reads one property from the real {@code application.yml} on the test classpath.
     *
     * @param key the property name
     * @return the configured value, or {@code null} when the key is absent
     * @throws IOException if the resource cannot be read
     */
    private static Object shippedProperty(final String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        return sources.get(0).getProperty(key);
    }

    @Nested
    @DisplayName("The shipped configuration publishes no framework error attribute")
    class ShippedConfiguration {

        @ParameterizedTest(name = "server.error.{0} is never")
        @ValueSource(strings = {"include-message", "include-binding-errors", "include-stacktrace"})
        @DisplayName("All three error attributes are off, so no exception text is ever published")
        void allThreeErrorAttributesAreOff(final String attribute) throws IOException {
            assertThat(shippedProperty("server.error." + attribute))
                    .as("server.error.%s must stay 'never': the framework page would otherwise "
                            + "publish exception text this advice never chose to publish", attribute)
                    .hasToString("never");
        }

        @Test
        @DisplayName("The test profile inherits them rather than relaxing them for tests")
        void theTestProfileInheritsThem() throws IOException {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application-test.yml", new ClassPathResource("application-test.yml"));

            // The test profile must not carry its own server.error.* values at all: overriding them
            // would mean tests exercise a different error boundary from the one that ships.
            assertThat(sources).isNotEmpty();
            assertThat(sources.get(0).getProperty("server.error.include-message")).isNull();
            assertThat(sources.get(0).getProperty("server.error.include-binding-errors")).isNull();
            assertThat(sources.get(0).getProperty("server.error.include-stacktrace")).isNull();
        }
    }

    @Nested
    @DisplayName("An unreadable request body reports the field, never the payload")
    class UnreadableBody {

        @Test
        @DisplayName("It reports 400 and names each field on Jackson's mapping path")
        void itNamesEachFieldOnThePath() {
            // The one envelope a client is answered with: 400, the reason phrase, the fixed
            // malformed-request detail, and one entry per named field. The field names travel to the
            // caller as well as to the log, which is what makes a MALFORMED_REQUEST answer actionable;
            // what still never travels is anything Jackson quoted from the payload.
            ResponseEntity<CobolErrorResponse> response =
                    new CobolErrorHandler().handleUnreadableRequestBody(unreadableAt("cardNumber"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code())
                    .isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_CODE);
            assertThat(response.getBody().error())
                    .isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE);
            assertThat(response.getBody().fieldErrors()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.field()).isEqualTo("cardNumber");
                        assertThat(entry.message()).isEqualTo("could not be read from the request "
                                + "body");
                    });
        }

        @Test
        @DisplayName("Jackson's own message is never copied, so the payload fragment stays out")
        void jacksonsOwnMessageIsNeverCopied() {
            HttpMessageNotReadableException unreadable = unreadableAt("cardNumber");

            // Both the exception and its cause quote the payload; neither reaches the body.
            assertThat(unreadable.getMessage()).contains(PAN);
            CobolErrorResponse body = CobolErrorHandler.malformedRequestResponse(unreadable);

            assertThat(body.toString()).doesNotContain(PAN);
            assertThat(body.fieldErrors()).noneSatisfy(entry ->
                    assertThat(entry.message()).contains(PAN));
        }

        @Test
        @DisplayName("Several fields are reported, ordered by name for a deterministic body")
        void severalFieldsAreOrderedByName() {
            CobolErrorResponse body = CobolErrorHandler.malformedRequestResponse(
                    unreadableAt("expiryDate", "cardNumber", "accountId"));

            assertThat(body.fieldErrors()).extracting(
                    WebConfig.CobolErrorHandler.FieldMessage::field)
                    .containsExactly("accountId", "cardNumber", "expiryDate");
        }

        @Test
        @DisplayName("A body that is not JSON at all names no field, which is the honest answer")
        void aBodyThatIsNotJsonNamesNoField() {
            HttpMessageNotReadableException notJson = new HttpMessageNotReadableException(
                    "Required request body is missing " + PAN, new IOException("truncated"),
                    mock(HttpInputMessage.class));

            CobolErrorResponse body = CobolErrorHandler.malformedRequestResponse(notJson);

            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.error()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(body.toString()).doesNotContain(PAN);
        }

        @Test
        @DisplayName("A path element whose field name is blank contributes nothing either")
        void aPathElementWhoseFieldNameIsBlankContributesNothing() {
            HttpMessageNotReadableException blankNamed = unreadableAt("   ");

            // A blank name would render as an entry a caller cannot act on, so it is dropped for the
            // same reason an array index is: there is no field to report.
            assertThat(CobolErrorHandler.malformedRequestResponse(blankNamed).fieldErrors())
                    .isEmpty();
        }

        @Test
        @DisplayName("A blank name is dropped while its usable siblings are still reported")
        void aBlankNameIsDroppedWhileSiblingsAreReported() {
            CobolErrorResponse body =
                    CobolErrorHandler.malformedRequestResponse(unreadableAt("cardNumber", " "));

            assertThat(body.fieldErrors()).extracting(
                    WebConfig.CobolErrorHandler.FieldMessage::field)
                    .containsExactly("cardNumber");
        }

        @Test
        @DisplayName("A path element naming no field - an array index - contributes nothing")
        void aPathElementNamingNoFieldContributesNothing() {
            JsonMappingException mapping = JsonMappingException.from((JsonParser) null, "boom");
            mapping.prependPath(new JsonMappingException.Reference(List.of(), 3));
            HttpMessageNotReadableException indexed = new HttpMessageNotReadableException(
                    "JSON parse error", mapping, mock(HttpInputMessage.class));

            assertThat(CobolErrorHandler.malformedRequestResponse(indexed).fieldErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("A parameter of the wrong type reports neither its value nor its Java type")
    class TypeMismatch {

        @Test
        @DisplayName("The MVC subclass takes the same fixed answer as any other conversion failure")
        void theSubclassTakesTheFixedAnswer() {
            // MethodArgumentTypeMismatchException extends TypeMismatchException, and there is exactly
            // ONE handler for the family. There used to be a narrower one for the MVC subclass, and
            // because closest-match resolution preferred it, a caller who put a word where a number
            // belonged was told the field is "not a valid Long" - the internal Java type of a screen
            // field, published to an unauthenticated caller. The narrower handler is gone, so the
            // subclass and the superclass now answer identically.
            MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
                    PAN, Long.class, "acctId", null, new NumberFormatException(PAN));

            ResponseEntity<CobolErrorResponse> response = HANDLER.handleTypeMismatch(mismatch);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.TYPE_MISMATCH_MESSAGE);
        }

        @Test
        @DisplayName("Neither the rejected value nor the required type is echoed")
        void neitherTheValueNorTheTypeIsEchoed() {
            MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
                    PAN, Long.class, "acctId", null, new NumberFormatException(PAN));

            assertThat(mismatch.getValue()).isEqualTo(PAN);
            assertThat(HANDLER.handleTypeMismatch(mismatch).getBody().toString())
                    .doesNotContain(PAN)
                    .doesNotContain("Long")
                    .doesNotContain("acctId");
        }

        @Test
        @DisplayName("An absent required type needs no degraded wording, because no type is read")
        void anAbsentRequiredTypeNeedsNoSpecialCase() {
            MethodArgumentTypeMismatchException untyped = new MethodArgumentTypeMismatchException(
                    PAN, null, "cardNum", null, new IllegalStateException("no converter"));

            assertThat(HANDLER.handleTypeMismatch(untyped).getBody().detail())
                    .isEqualTo(CobolErrorHandler.TYPE_MISMATCH_MESSAGE);
        }
    }

    @Nested
    @DisplayName("A value a domain guard rejected is answered without the value")
    class RejectedValue {

        @Test
        @DisplayName("It reports 400 - the caller supplied the value, so it is not a server fault")
        void itReportsBadRequest() {
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleRejectedValue(
                    new IllegalArgumentException("A value of 17 character(s) exceeds " + PAN));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error())
                    .isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(response.getBody().detail())
                    .contains("does not fit the COBOL picture")
                    .contains("not echoed");
        }

        @Test
        @DisplayName("A guard anywhere in the module is covered, not only the sanitised ones")
        void aGuardAnywhereInTheModuleIsCovered() {
            // This is the barrier that does not depend on every guard in the module having been
            // reworded, and it is why the sanitising is defence in depth rather than the fix. Even a
            // guard whose message still quotes the value it judged - and several do, in packages this
            // checkpoint did not touch - cannot get that value into a response body, because the
            // handler never reads the message.
            IllegalArgumentException stillQuotesItsInput =
                    new IllegalArgumentException("CARD-NUM is PIC 9(16), so it cannot hold " + PAN);

            ResponseEntity<CobolErrorResponse> response =
                    HANDLER.handleRejectedValue(stillQuotesItsInput);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().toString()).doesNotContain(PAN);
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.rejectedValueResponse().detail());
        }

        @Test
        @DisplayName("A screen value a RECEIVE MAP could not have carried names the member, because "
                + "\"a field does not fit\" is unactionable on a 54-field screen")
        void aScreenInputRefusalNamesTheMember() {
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleRejectedValue(
                    ScreenInputRejectedException.unrepresentable("ACSLNAM", "ACSLNAMI",
                            StandardCharsets.US_ASCII, 0x00D1));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(CobolErrorHandler.REJECTED_VALUE_CODE);
            assertThat(response.getBody().fieldErrors()).singleElement()
                    .satisfies(field -> {
                        assertThat(field.field()).isEqualTo("ACSLNAM");
                        assertThat(field.message()).contains("U+00D1").contains("US-ASCII");
                    });
            assertThat(response.getBody().detail()).contains("ACSLNAM").contains("U+00D1");
        }

        @Test
        @DisplayName("It is the one IllegalArgumentException whose message is published, and it is safe "
                + "to publish because the type is final with two value-free factories")
        void theScreenInputMessageIsSafeToPublishBecauseTheTypeIsSealedShut() {
            assertThat(java.lang.reflect.Modifier.isFinal(ScreenInputRejectedException.class
                    .getModifiers()))
                    .as("a non-final subtype could word its message with the value in it")
                    .isTrue();
            for (java.lang.reflect.Constructor<?> constructor
                    : ScreenInputRejectedException.class.getDeclaredConstructors()) {
                assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()))
                        .as("every constructor must be private so the factories are the only producers")
                        .isTrue();
            }
            // And the value genuinely does not reach the body, even for a member whose span holds a PAN.
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleRejectedValue(
                    ScreenInputRejectedException.inconsistentCommarea("commArea.oldDetails.cardid",
                            "the sixteen digits of the fetched card number"));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().toString()).doesNotContain(PAN);
        }

        @Test
        @DisplayName("An ordinary rejected value still says nothing about the value and names no field, "
                + "so the narrower branch has not widened the open-ended family")
        void anOrdinaryRejectedValueStillNamesNoField() {
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleRejectedValue(
                    new IllegalArgumentException("CARD-NUM cannot hold " + PAN));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors()).isEmpty();
            assertThat(response.getBody().detail())
                    .isEqualTo(CobolErrorHandler.rejectedValueResponse().detail());
        }

        @Test
        @DisplayName("A state fault quoting a dataset name is covered by the same barrier")
        void aStateFaultQuotingADatasetNameIsCovered() {
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleInternalState(
                    new IllegalStateException("carddemo.datasets.CCXREF.dsname is "
                            + "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().toString())
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("carddemo.datasets");
        }

        @Test
        @DisplayName("The body cannot carry the exception's text, whatever the exception says")
        void theBodyCannotCarryTheExceptionsText() {
            // The builder takes no argument at all, so there is no path by which a guard added later
            // could widen this response by wording its message differently.
            CobolErrorResponse first = CobolErrorHandler.rejectedValueResponse();
            CobolErrorResponse second = CobolErrorHandler.rejectedValueResponse();

            assertThat(first).isEqualTo(second);
            assertThat(first.toString()).doesNotContain(PAN);
        }

        @Test
        @DisplayName("A state fault reports 500, because the caller did not cause it")
        void aStateFaultReportsInternalServerError() {
            ResponseEntity<CobolErrorResponse> response = HANDLER.handleInternalState(
                    new IllegalStateException("Dataset binding for DD name 'CCXREF' is absent"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            assertThat(response.getBody().detail()).contains("not in a state to serve")
                    .contains("server log");
        }

        @Test
        @DisplayName("The state fault body names neither the dataset nor the configuration key")
        void theStateFaultBodyNamesNoDataset() {
            assertThat(CobolErrorHandler.internalStateResponse().toString())
                    .doesNotContain("CCXREF")
                    .doesNotContain("carddemo.datasets");
        }
    }

    @Nested
    @DisplayName("The guards themselves no longer hold the value in their message - F15")
    class GuardsDoNotEchoValues {

        @Test
        @DisplayName("CardXrefRecord reports an over-wide XREF-CARD-NUM by length, not by content")
        void cardXrefRecordReportsAnOverWideKeyByLength() {
            String seventeen = PAN + "7";

            String message = catchMessage(() -> new CardXrefRecord(seventeen, 1, 1L));

            assertThat(message).contains("XREF-CARD-NUM was given 17 character(s)")
                    .contains("PIC X(16)")
                    .doesNotContain(seventeen)
                    .doesNotContain(PAN);
        }

        @Test
        @DisplayName("CardXrefRecord reports an unstorable identifier without naming its value")
        void cardXrefRecordReportsAnUnstorableIdentifierWithoutItsValue() {
            long tooWide = 999_999_999_999L;

            assertThat(catchMessage(() -> new CardXrefRecord(PAN, 1, tooWide)))
                    .contains("needing more than 11 digit(s)")
                    .doesNotContain(String.valueOf(tooWide));
            assertThat(catchMessage(() -> new CardXrefRecord(PAN, -1, 1L)))
                    .contains("cannot hold a negative value")
                    .doesNotContain("-1");
        }

        @Test
        @DisplayName("TranRecord reports a mis-sized TRAN-AMT image by width, not by content")
        void tranRecordReportsAMisSizedAmountByWidth() {
            TranRecord record = new TranRecord(StandardCharsets.US_ASCII);
            String tooShort = "0000012345";

            assertThat(catchMessage(() -> record.writeTranAmtImage(tooShort)))
                    .contains("A TRAN-AMT image of 10 character(s)")
                    .doesNotContain("'" + tooShort + "'");
        }

        @Test
        @DisplayName("A commarea item reports its own name and width, not the value that overflowed")
        void aCommareaItemReportsItsNameAndWidth() {
            String overWideName = "A".repeat(51);

            String message = catchMessage(() -> new CardDetails(DetailGroup.NEW,
                    "00000000001", PAN, "123", overWideName, "2026", "12", "31", "Y"));

            assertThat(message).contains("A value of 51 character(s)")
                    .contains("PIC X(50)")
                    .doesNotContain(overWideName);
        }

        @Test
        @DisplayName("A record item reports the span it overflowed, not the value that overflowed it")
        void aRecordItemReportsTheSpanItOverflowed() {
            String overWideEmbossed = "B".repeat(51);

            String message = catchMessage(() -> new CardUpdateRecord(PAN, 1L, 123,
                    overWideEmbossed, "20261231", "Y"));

            assertThat(message).contains("A value of 51 character(s)")
                    .doesNotContain(overWideEmbossed);
        }

        @ParameterizedTest(name = "a numeric guard handed \"{0}\" does not echo it")
        @ValueSource(strings = {"12A4", "4111111111111111X", "  99"})
        @DisplayName("A non-digit is reported by position, and the image is not echoed")
        void aNonDigitIsReportedByPosition(final String malformed) {
            String message = catchMessage(() -> CODEC.decodePic9(malformed));

            assertThat(message).contains("not a digit").doesNotContain(malformed);
        }

        @Test
        @DisplayName("An over-long numeric image is reported by digit count, not by value")
        void anOverLongNumericImageIsReportedByDigitCount() {
            String nineteenDigits = "9".repeat(19);

            assertThat(catchMessage(() -> CODEC.decodePic9(nineteenDigits)))
                    .contains("19 digit(s)")
                    .doesNotContain(nineteenDigits);
        }

        @Test
        @DisplayName("An out-of-int-range image is reported without the value it denotes")
        void anOutOfRangeImageIsReportedWithoutItsValue() {
            String tooLarge = "9999999999";

            assertThat(catchMessage(() -> CODEC.decodePic9AsInt(tooLarge)))
                    .contains("exceeds Integer.MAX_VALUE")
                    .doesNotContain(tooLarge);
        }

        @Test
        @DisplayName("A bad sign overpunch is reported by category, not by the offending character")
        void aBadSignOverpunchIsReportedByCategory() {
            String badSign = "0000012345*";

            assertThat(catchMessage(() -> CODEC.decodeSignedScaled(badSign, 2)))
                    .contains("neither a digit nor a sign overpunch character")
                    .doesNotContain(badSign);
        }

        @Test
        @DisplayName("A scale wider than the image is reported without the image")
        void aScaleWiderThanTheImageIsReportedWithoutTheImage() {
            String image = "1234";

            assertThat(catchMessage(() -> CODEC.decodeSignedScaled(image, 9)))
                    .contains("Declared scale 9")
                    .doesNotContain("'" + image + "'");
        }

        /**
         * Runs an operation expected to fail and returns the failure's message.
         *
         * @param operation the operation to run
         * @return the message of whatever it threw
         */
        private static String catchMessage(final Runnable operation) {
            try {
                operation.run();
            } catch (RuntimeException expected) {
                return String.valueOf(expected.getMessage());
            }
            throw new AssertionError("The guard under test accepted a value it should have refused");
        }
    }
}
