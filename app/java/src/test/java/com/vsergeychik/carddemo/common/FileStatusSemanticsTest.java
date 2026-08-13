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
 * Tests for {@link FileStatus}, which unifies the batch two-character {@code FILE STATUS} vocabulary with
 * the CICS {@code RESP} vocabulary.
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
        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({"00, 0000", "10, 0010", "22, 0022", "23, 0023", "35, 0035", "04, 0004"})
        void numericStatusesTakeTheElsePath(String status, String expected) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(expected)
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        @Test
        @DisplayName("a first byte of '9' takes the IF path even though the status is numeric")
        void aLeadingNineTakesTheIfPath() {
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

        @ParameterizedTest(name = "RESP {0} -> \"{1}\"")
        @CsvSource({"0, 00", "20, 10", "13, 23", "14, 22", "15, 22"})
        void respConvertsToItsBatchStatus(int resp, String expected) {
            assertThat(FileStatus.batchStatusOfCicsResp(resp)).contains(expected);
        }

        @ParameterizedTest
        @ValueSource(ints = {16, 19, 22, 12, 99})
        void anUnmappedRespHasNoBatchStatus(int resp) {
            assertThat(FileStatus.batchStatusOfCicsResp(resp)).isEmpty();
        }

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
