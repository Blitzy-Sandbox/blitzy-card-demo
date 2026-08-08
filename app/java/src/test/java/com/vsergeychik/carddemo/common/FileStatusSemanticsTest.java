package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link FileStatus}, which unifies the batch two-character {@code FILE STATUS} vocabulary
 * with the CICS {@code RESP} vocabulary.
 *
 * <p>The most intricate behaviour under test is the status-image construction that reproduces
 * {@code 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBACT01C.cbl:L176-L189}. The COBOL takes two paths:
 *
 * <pre>{@code
 * IF  IO-STATUS NOT NUMERIC
 * OR  IO-STAT1 = '9'
 *     MOVE IO-STAT1 TO IO-STATUS-04(1:1)
 *     MOVE 0        TO TWO-BYTES-BINARY
 *     MOVE IO-STAT2 TO TWO-BYTES-RIGHT
 *     MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
 *     DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
 * ELSE
 *     MOVE '0000' TO IO-STATUS-04
 *     MOVE IO-STATUS TO IO-STATUS-04(3:2)
 *     DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
 * END-IF
 * }</pre>
 *
 * <p>Zeroing the two-byte binary and then overwriting only its low-order byte leaves that byte's
 * <em>unsigned</em> value, which a {@code PIC 999} receiver then renders as three zero-padded digits.
 * The {@code 0xFF} case below is the assertion that pins that down: it must read as 255, never as -1.
 */
@DisplayName("FileStatus - the batch FILE STATUS and CICS RESP vocabularies")
class FileStatusSemanticsTest {

    @Nested
    @DisplayName("Constants - the statuses and RESP codes the COBOL actually tests")
    class Constants {

        @Test
        @DisplayName("the batch statuses are the two-character literals from the source")
        void theBatchStatusesMatchTheSource() {
            assertThat(FileStatus.OK).isEqualTo("00").hasSize(FileStatus.STATUS_LENGTH);
            assertThat(FileStatus.END_OF_FILE).isEqualTo("10");
            assertThat(FileStatus.DUPLICATE).isEqualTo("22");
            assertThat(FileStatus.NOT_FOUND).isEqualTo("23");
            assertThat(FileStatus.STATUS_LENGTH).isEqualTo(2);
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
        }

        @Test
        @DisplayName("the CICS RESP values are the architected condition numbers")
        void theCicsRespValuesAreArchitected() {
            assertThat(FileStatus.NORMAL).isZero();
            assertThat(FileStatus.NOTFND).isEqualTo(13);
            assertThat(FileStatus.DUPREC).isEqualTo(14);
            assertThat(FileStatus.DUPKEY).isEqualTo(15);
            assertThat(FileStatus.INVREQ).isEqualTo(16);
            assertThat(FileStatus.NOTOPEN).isEqualTo(19);
            assertThat(FileStatus.ENDFILE).isEqualTo(20);
            assertThat(FileStatus.LENGERR).isEqualTo(22);
        }

        @Test
        @DisplayName("APPL-AOK and APPL-EOF match their 88-level values")
        void theApplResultConditionsMatch() {
            assertThat(FileStatus.APPL_AOK).isZero();
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }

        @Test
        @DisplayName("the display prefix is byte-exact to the COBOL literal")
        void theDisplayPrefixIsByteExact() {
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }
    }

    @Nested
    @DisplayName("toStatusImage() - reproducing 9910-DISPLAY-IO-STATUS")
    class StatusImage {

        /**
         * The ELSE path: a fully numeric status whose first byte is not {@code '9'} is rendered as
         * {@code '00'} followed by the two status characters.
         *
         * @param status   the two-character status
         * @param expected the four-character image
         */
        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({"00, 0000", "10, 0010", "22, 0022", "23, 0023", "35, 0035", "04, 0004"})
        void numericStatusesTakeTheElsePath(String status, String expected) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(expected)
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        @Test
        @DisplayName("a first byte of '9' takes the IF path even though the status is numeric")
        void aLeadingNineTakesTheIfPath() {
            // '7' is EBCDIC-independent here: the Java char '7' is 0x37 = 55 decimal.
            assertThat(FileStatus.toStatusImage('9', '7')).isEqualTo("9055");
            assertThat(FileStatus.toStatusImage("90")).isEqualTo("9048");
        }

        @Test
        @DisplayName("a non-numeric byte takes the IF path")
        void aNonNumericByteTakesTheIfPath() {
            assertThat(FileStatus.toStatusImage('A', '0')).isEqualTo("A048");
            assertThat(FileStatus.toStatusImage('0', 'A')).isEqualTo("0065");
        }

        @Test
        @DisplayName("the low-order byte is unsigned: 0xFF reads as 255, never as -1")
        void theLowOrderByteIsUnsigned() {
            assertThat(FileStatus.toStatusImage('9', (char) 0xFF)).isEqualTo("9255");
            assertThat(FileStatus.toStatusImage('9', (char) 0x00)).isEqualTo("9000");
            assertThat(FileStatus.toStatusImage('9', (char) 0x09)).isEqualTo("9009");
            assertThat(FileStatus.toStatusImage('9', (char) 0x64)).isEqualTo("9100");
        }

        /**
         * The digit test is a closed range, so both boundaries and both neighbours are driven.
         * {@code '/'} is one below {@code '0'} and {@code ':'} one above {@code '9'}.
         */
        @Test
        @DisplayName("the digit test excludes the characters either side of '0' and '9'")
        void theDigitTestIsAClosedRange() {
            assertThat(FileStatus.toStatusImage('0', '9')).isEqualTo("0009");
            assertThat(FileStatus.toStatusImage('/', '0')).isEqualTo("/048");
            assertThat(FileStatus.toStatusImage('0', ':')).isEqualTo("0058");
            assertThat(FileStatus.toStatusImage('8', '8')).isEqualTo("0088");
        }

        @Test
        @DisplayName("toDisplayLine() prefixes the image with the COBOL DISPLAY literal")
        void toDisplayLinePrefixesTheImage() {
            assertThat(FileStatus.toDisplayLine("23"))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine('9', '7'))
                    .isEqualTo("FILE STATUS IS: NNNN9055");
        }
    }

    @Nested
    @DisplayName("Predicates - one per status test the COBOL performs")
    class Predicates {

        @Test
        @DisplayName("each predicate accepts only its own status")
        void eachPredicateAcceptsOnlyItsOwnStatus() {
            assertThat(FileStatus.isOk("00")).isTrue();
            assertThat(FileStatus.isOk("10")).isFalse();
            assertThat(FileStatus.isEndOfFile("10")).isTrue();
            assertThat(FileStatus.isEndOfFile("00")).isFalse();
            assertThat(FileStatus.isNotFound("23")).isTrue();
            assertThat(FileStatus.isNotFound("22")).isFalse();
            assertThat(FileStatus.isDuplicate("22")).isTrue();
            assertThat(FileStatus.isDuplicate("23")).isFalse();
        }

        /**
         * The two programs that treat a missing record as a normal outcome need to accept
         * {@code '00'} or {@code '23'} without accepting every other failure alongside them, so both
         * accepting arms and the rejecting arm are driven.
         */
        @Test
        @DisplayName("isOkOrNotFound() accepts via either arm and rejects everything else")
        void isOkOrNotFoundAcceptsViaEitherArm() {
            assertThat(FileStatus.isOkOrNotFound("00")).isTrue();
            assertThat(FileStatus.isOkOrNotFound("23")).isTrue();
            assertThat(FileStatus.isOkOrNotFound("10")).isFalse();
            assertThat(FileStatus.isOkOrNotFound("22")).isFalse();
        }
    }

    @Nested
    @DisplayName("Conversions - the EVALUATE order preserved as an if-chain")
    class Conversions {

        /**
         * Every arm of the status-to-outcome chain plus the {@code WHEN OTHER} default.
         *
         * @param status   the batch status
         * @param expected the outcome it maps to
         */
        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
            "00, OK",
            "10, END_OF_FILE",
            "23, NOT_FOUND",
            "22, DUPLICATE",
            "35, OTHER",
            "97, OTHER",
        })
        void everyStatusMapsToItsOutcome(String status, Outcome expected) {
            assertThat(FileStatus.outcomeOfStatus(status)).isEqualTo(expected);
        }

        /**
         * Every arm of the RESP-to-outcome chain, including both duplicate conditions that collapse
         * onto one outcome, and the default.
         *
         * @param resp     the CICS RESP value
         * @param expected the outcome it maps to
         */
        @ParameterizedTest(name = "RESP {0} -> {1}")
        @CsvSource({
            "0, OK",
            "20, END_OF_FILE",
            "13, NOT_FOUND",
            "14, DUPLICATE",
            "15, DUPLICATE",
            "16, OTHER",
            "19, OTHER",
            "22, OTHER",
        })
        void everyRespMapsToItsOutcome(int resp, Outcome expected) {
            assertThat(FileStatus.outcomeOfCicsResp(resp)).isEqualTo(expected);
        }

        /**
         * The RESP-to-batch-status conversion, which is partial: a RESP with no batch equivalent
         * yields an empty result rather than a fabricated status.
         *
         * @param resp     the CICS RESP value
         * @param expected the batch status it maps to
         */
        @ParameterizedTest(name = "RESP {0} -> \"{1}\"")
        @CsvSource({"0, 00", "20, 10", "13, 23", "14, 22", "15, 22"})
        void respConvertsToItsBatchStatus(int resp, String expected) {
            assertThat(FileStatus.batchStatusOfCicsResp(resp)).contains(expected);
        }

        /**
         * A RESP outside the mapped set has no batch equivalent and must not be invented.
         *
         * @param resp the unmapped CICS RESP value
         */
        @ParameterizedTest
        @ValueSource(ints = {16, 19, 22, 12, 99})
        void anUnmappedRespHasNoBatchStatus(int resp) {
            assertThat(FileStatus.batchStatusOfCicsResp(resp)).isEmpty();
        }

        /**
         * The reverse conversion, also partial: a duplicate maps onto two distinct CICS conditions,
         * so it deliberately has no single RESP.
         *
         * @param status   the batch status
         * @param expected the RESP it maps to
         */
        @ParameterizedTest(name = "\"{0}\" -> RESP {1}")
        @CsvSource({"00, 0", "10, 20", "23, 13"})
        void batchStatusConvertsToItsResp(String status, int expected) {
            assertThat(FileStatus.cicsRespOfBatchStatus(status)).hasValue(expected);
        }

        @Test
        @DisplayName("a duplicate has no single RESP, because DUPREC and DUPKEY are distinct")
        void aDuplicateHasNoSingleResp() {
            assertThat(FileStatus.cicsRespOfBatchStatus("22")).isEmpty();
            assertThat(FileStatus.cicsRespOfBatchStatus("35")).isEmpty();
        }

        @Test
        @DisplayName("the two conversions round-trip where a mapping exists in both directions")
        void theConversionsRoundTrip() {
            for (String status : new String[] {"00", "10", "23"}) {
                int resp = FileStatus.cicsRespOfBatchStatus(status).orElseThrow();
                assertThat(FileStatus.batchStatusOfCicsResp(resp)).contains(status);
                assertThat(FileStatus.outcomeOfCicsResp(resp))
                        .isEqualTo(FileStatus.outcomeOfStatus(status));
            }
        }
    }

    @Nested
    @DisplayName("Outcome - the shared vocabulary")
    class OutcomeVocabulary {

        @Test
        @DisplayName("the four mapped outcomes carry their batch status; OTHER carries none")
        void mappedOutcomesCarryTheirBatchStatus() {
            assertThat(Outcome.OK.batchStatus()).contains(FileStatus.OK);
            assertThat(Outcome.END_OF_FILE.batchStatus()).contains(FileStatus.END_OF_FILE);
            assertThat(Outcome.NOT_FOUND.batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(Outcome.DUPLICATE.batchStatus()).contains(FileStatus.DUPLICATE);
            assertThat(Outcome.OTHER.batchStatus()).isEmpty();
        }

        /**
         * Whatever an outcome reports as its batch status must map back to that same outcome, so the
         * two vocabularies cannot drift apart.
         *
         * @param outcome the outcome to check
         */
        @ParameterizedTest
        @EnumSource(Outcome.class)
        void anOutcomesBatchStatusMapsBackToIt(Outcome outcome) {
            outcome.batchStatus().ifPresent(
                    status -> assertThat(FileStatus.outcomeOfStatus(status)).isEqualTo(outcome));
        }

        @Test
        @DisplayName("there are exactly five outcomes, so a caller's switch can be exhaustive")
        void thereAreExactlyFiveOutcomes() {
            assertThat(Outcome.values()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("Argument guards and non-instantiability")
    class Guards {

        @Test
        @DisplayName("a null status is rejected, because FILE STATUS is a two-byte field")
        void aNullStatusIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isOk(null))
                    .withMessageContaining("must not be null");
            assertThatNullPointerException().isThrownBy(() -> FileStatus.outcomeOfStatus(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.toStatusImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> FileStatus.cicsRespOfBatchStatus(null));
        }

        /**
         * Anything other than exactly two characters is rejected: the field is
         * {@code 05 IO-STAT1 PIC X. 05 IO-STAT2 PIC X.} and nothing else can be a valid image.
         *
         * @param status the wrongly sized status
         */
        @ParameterizedTest
        @ValueSource(strings = {"", "0", "000", "0000", "  1"})
        void aWronglySizedStatusIsRejected(String status) {
            assertThatIllegalArgumentException().isThrownBy(() -> FileStatus.isOk(status))
                    .withMessageContaining("exactly 2 characters");
        }

        @Test
        @DisplayName("a two-character status is accepted whatever its content")
        void anyTwoCharacterStatusIsAccepted() {
            assertThat(FileStatus.outcomeOfStatus("  ")).isEqualTo(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfStatus("ZZ")).isEqualTo(Outcome.OTHER);
        }

        /**
         * The class is a {@code final} namespace whose only constructor is private, so no caller can
         * obtain an instance.
         *
         * <p>Recorded rather than corrected: this constructor is <strong>intentionally empty</strong>,
         * whereas the sibling holders {@code CobolDecimal}, {@code BmsAttributes} and
         * {@code FieldAttributeSetter} all throw an {@link AssertionError} so that reflective
         * instantiation fails loudly. The difference is cosmetic - the private modifier is what
         * actually prevents instantiation, and the class carries no state for an instance to corrupt -
         * so this test asserts the behaviour the source has rather than the behaviour its siblings
         * have.
         */
        @Test
        @DisplayName("it is a final namespace whose sole constructor is private")
        void itIsNotInstantiableByAnyCaller() throws ReflectiveOperationException {
            assertThat(Modifier.isFinal(FileStatus.class.getModifiers())).isTrue();
            assertThat(FileStatus.class.getDeclaredConstructors()).hasSize(1);

            Constructor<FileStatus> constructor = FileStatus.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }
}
