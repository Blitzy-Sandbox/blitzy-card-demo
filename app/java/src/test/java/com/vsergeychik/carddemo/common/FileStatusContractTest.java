package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link FileStatus}, the single home of the COBOL {@code FILE STATUS} codes and their CICS
 * {@code RESP} equivalents.
 */
@DisplayName("FileStatus - COBOL FILE STATUS codes and CICS RESP equivalents")
class FileStatusContractTest {
    @Nested
    @DisplayName("Constants")
    class Constants {
        @Test
        @DisplayName("the four batch status codes are the COBOL literals")
        void batchStatusCodes() {
            assertThat(FileStatus.OK).isEqualTo("00");
            assertThat(FileStatus.END_OF_FILE).isEqualTo("10");
            assertThat(FileStatus.DUPLICATE).isEqualTo("22");
            assertThat(FileStatus.NOT_FOUND).isEqualTo("23");
        }

        @Test
        @DisplayName("the status field is two bytes and its display image is four")
        void statusWidths() {
            assertThat(FileStatus.STATUS_LENGTH).isEqualTo(2);
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
        }

        @Test
        @DisplayName("the CICS RESP values match the IBM condition numbers")
        void cicsRespValues() {
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
        @DisplayName("the APPL-RESULT sentinels match the batch programs' WORKING-STORAGE")
        void applResultSentinels() {
            assertThat(FileStatus.APPL_AOK).isZero();
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }

        @Test
        @DisplayName("the display prefix is the COBOL literal, including its NNNN placeholder")
        void displayPrefix() {
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }
    }

    @Nested
    @DisplayName("toStatusImage - the two-limb COBOL display convention")
    class StatusImage {
        @ParameterizedTest(name = "status {0} renders as {1}")
        @CsvSource({"00,0000", "10,0010", "22,0022", "23,0023", "35,0035", "89,0089"})
        @DisplayName("a wholly numeric status below 90 renders with two leading zeros")
        void numericStatusRendersWithLeadingZeros(String status, String expected) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(expected).hasSize(4);
        }

        @Test
        @DisplayName("a 9x status renders its second byte as a three-digit decimal")
        void nineSeriesStatusRendersBinarySecondByte() {
            assertThat(FileStatus.toStatusImage('9', (char) 1)).isEqualTo("9001");
            assertThat(FileStatus.toStatusImage('9', (char) 0)).isEqualTo("9000");
            assertThat(FileStatus.toStatusImage('9', (char) 255)).isEqualTo("9255");
            assertThat(FileStatus.toStatusImage('9', (char) 99)).isEqualTo("9099");
        }

        @Test
        @DisplayName("a non-numeric status also takes the binary limb")
        void nonNumericStatusTakesTheBinaryLimb() {
            assertThat(FileStatus.toStatusImage('A', (char) 5)).isEqualTo("A005");
            assertThat(FileStatus.toStatusImage('0', 'A')).hasSize(4).startsWith("0");
        }

        @Test
        @DisplayName("both digit-range guards are driven at their boundaries")
        void digitGuardBoundaries() {
            assertThat(FileStatus.toStatusImage('/', '0')).hasSize(4);
            assertThat(FileStatus.toStatusImage(':', '0')).hasSize(4);
            assertThat(FileStatus.toStatusImage('0', '/')).hasSize(4);
            assertThat(FileStatus.toStatusImage('0', ':')).hasSize(4);
            assertThat(FileStatus.toStatusImage('0', '0')).isEqualTo("0000");
            assertThat(FileStatus.toStatusImage('9', '9')).isEqualTo("9057");
        }

        @Test
        @DisplayName("the String and char overloads agree")
        void overloadsAgree() {
            assertThat(FileStatus.toStatusImage("23")).isEqualTo(FileStatus.toStatusImage('2', '3'));
        }
    }

    @Nested
    @DisplayName("toDisplayLine - the COBOL DISPLAY statement")
    class DisplayLine {
        @Test
        @DisplayName("the display line is the prefix followed by the four-character image")
        void displayLineShape() {
            assertThat(FileStatus.toDisplayLine("23")).isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine('2', '3')).isEqualTo("FILE STATUS IS: NNNN0023");
        }

        @Test
        @DisplayName("both overloads agree for a 9x status")
        void displayLineForNineSeries() {
            assertThat(FileStatus.toDisplayLine('9', (char) 1)).endsWith("9001");
        }
    }

    @Nested
    @DisplayName("Individual status predicates")
    class Predicates {
        @Test
        @DisplayName("isOk is true only for 00")
        void isOk() {
            assertThat(FileStatus.isOk("00")).isTrue();
            assertThat(FileStatus.isOk("10")).isFalse();
            assertThat(FileStatus.isOk("23")).isFalse();
        }

        @Test
        @DisplayName("isEndOfFile is true only for 10")
        void isEndOfFile() {
            assertThat(FileStatus.isEndOfFile("10")).isTrue();
            assertThat(FileStatus.isEndOfFile("00")).isFalse();
        }

        @Test
        @DisplayName("isNotFound is true only for 23")
        void isNotFound() {
            assertThat(FileStatus.isNotFound("23")).isTrue();
            assertThat(FileStatus.isNotFound("22")).isFalse();
        }

        @Test
        @DisplayName("isDuplicate is true only for 22")
        void isDuplicate() {
            assertThat(FileStatus.isDuplicate("22")).isTrue();
            assertThat(FileStatus.isDuplicate("23")).isFalse();
        }

        @Test
        @DisplayName("isOkOrNotFound accepts 00 and 23 and rejects everything else")
        void isOkOrNotFound() {
            assertThat(FileStatus.isOkOrNotFound("00")).isTrue();
            assertThat(FileStatus.isOkOrNotFound("23")).isTrue();
            assertThat(FileStatus.isOkOrNotFound("10")).isFalse();
            assertThat(FileStatus.isOkOrNotFound("22")).isFalse();
        }
    }

    @Nested
    @DisplayName("outcomeOfStatus - every discriminated outcome reachable from a batch status")
    class OutcomeOfStatus {
        @ParameterizedTest(name = "status {0} yields {1}")
        @CsvSource({"00,OK", "10,END_OF_FILE", "23,NOT_FOUND", "22,DUPLICATE", "35,OTHER",
                "99,OTHER", "AB,OTHER"})
        @DisplayName("all five outcomes are reachable, including the OTHER fall-through")
        void everyOutcomeIsReachable(String status, Outcome expected) {
            assertThat(FileStatus.outcomeOfStatus(status)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the guard chain is ordered so each specific status wins over OTHER")
        void guardChainOrder() {
            assertThat(FileStatus.outcomeOfStatus("00")).isEqualTo(Outcome.OK);
            assertThat(FileStatus.outcomeOfStatus("10")).isNotEqualTo(Outcome.OK);
        }
    }

    @Nested
    @DisplayName("outcomeOfCicsResp - the online programs' RESP form")
    class OutcomeOfCicsResp {
        @ParameterizedTest(name = "RESP {0} yields {1}")
        @CsvSource({"0,OK", "20,END_OF_FILE", "13,NOT_FOUND", "14,DUPLICATE", "15,DUPLICATE",
                "16,OTHER", "19,OTHER", "22,OTHER", "99,OTHER"})
        @DisplayName("all five outcomes are reachable, and both duplicate conditions map alike")
        void everyOutcomeIsReachable(int cicsResp, Outcome expected) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isEqualTo(expected);
        }

        @Test
        @DisplayName("DUPREC and DUPKEY both map to DUPLICATE, driving both limbs of the OR")
        void bothDuplicateConditionsMapAlike() {
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPREC)).isEqualTo(Outcome.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPKEY)).isEqualTo(Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("a RESP with no batch equivalent falls through to OTHER, not to OK")
        void unmappedRespIsOtherNotOk() {
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isEqualTo(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTOPEN)).isEqualTo(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.LENGERR)).isEqualTo(Outcome.OTHER);
        }
    }

    @Nested
    @DisplayName("Mapping between the batch and CICS forms")
    class Mapping {
        @ParameterizedTest(name = "RESP {0} maps to batch status {1}")
        @CsvSource({"0,00", "20,10", "13,23", "14,22", "15,22"})
        @DisplayName("every mapped RESP yields its batch status")
        void mappedRespYieldsBatchStatus(int cicsResp, String expected) {
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).contains(expected);
        }

        @ParameterizedTest(name = "RESP {0} has no batch equivalent")
        @ValueSource(ints = {16, 19, 22, 99, -1})
        @DisplayName("an unmapped RESP yields an absent batch status rather than a wrong one")
        void unmappedRespYieldsEmpty(int cicsResp) {
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isEmpty();
        }

        @ParameterizedTest(name = "batch status {0} maps to RESP {1}")
        @CsvSource({"00,0", "10,20", "23,13"})
        @DisplayName("every mapped batch status yields its RESP")
        void mappedBatchStatusYieldsResp(String status, int expected) {
            assertThat(FileStatus.cicsRespOfBatchStatus(status)).hasValue(expected);
        }

        @ParameterizedTest(name = "batch status {0} has no single RESP")
        @ValueSource(strings = {"22", "35", "99", "AB"})
        @DisplayName("a status with no single RESP yields an absent result - 22 is ambiguous")
        void unmappedBatchStatusYieldsEmpty(String status) {
            assertThat(FileStatus.cicsRespOfBatchStatus(status)).isEmpty();
        }

        @Test
        @DisplayName("the mapping round-trips for the three unambiguous statuses")
        void roundTrip() {
            for (int resp : new int[] {FileStatus.NORMAL, FileStatus.ENDFILE, FileStatus.NOTFND}) {
                String status = FileStatus.batchStatusOfCicsResp(resp).orElseThrow();
                assertThat(FileStatus.cicsRespOfBatchStatus(status)).hasValue(resp);
            }
        }

        @Test
        @DisplayName("the two outcome entry points agree wherever both are defined")
        void bothEntryPointsAgree() {
            for (int resp : new int[] {FileStatus.NORMAL, FileStatus.ENDFILE, FileStatus.NOTFND,
                    FileStatus.DUPREC, FileStatus.DUPKEY}) {
                String status = FileStatus.batchStatusOfCicsResp(resp).orElseThrow();
                assertThat(FileStatus.outcomeOfCicsResp(resp))
                        .as("RESP %d via status %s", resp, status)
                        .isEqualTo(FileStatus.outcomeOfStatus(status));
            }
        }
    }

    @Nested
    @DisplayName("Input guards - shared by every String entry point")
    class InputGuards {
        @Test
        @DisplayName("a null status is rejected")
        void nullStatusRejected() {
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isOk(null));
        }

        @ParameterizedTest(name = "a status of length {0} is rejected")
        @ValueSource(strings = {"", "0", "000", "0000"})
        @DisplayName("a status that is not exactly two characters is rejected")
        void wrongLengthStatusRejected(String status) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.isOk(status))
                    .withMessageContaining("exactly 2");
        }

        @Test
        @DisplayName("the guard applies to every String entry point, not just one")
        void guardAppliesEverywhere() {
            assertThatNullPointerException().isThrownBy(() -> FileStatus.toStatusImage(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.toDisplayLine(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isEndOfFile(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isNotFound(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isDuplicate(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.isOkOrNotFound(null));
            assertThatNullPointerException().isThrownBy(() -> FileStatus.outcomeOfStatus(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> FileStatus.cicsRespOfBatchStatus(null));
            assertThatIllegalArgumentException().isThrownBy(() -> FileStatus.outcomeOfStatus("123"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.cicsRespOfBatchStatus("1"));
            assertThatIllegalArgumentException().isThrownBy(() -> FileStatus.toDisplayLine("1"));
        }
    }

    @Nested
    @DisplayName("Outcome enum")
    class OutcomeEnum {
        @Test
        @DisplayName("there are exactly five outcomes")
        void fiveOutcomes() {
            assertThat(Outcome.values()).hasSize(5);
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        @DisplayName("every outcome except OTHER carries its batch status")
        void batchStatusPresence(Outcome outcome) {
            if (outcome == Outcome.OTHER) {
                assertThat(outcome.batchStatus()).isEmpty();
            } else {
                assertThat(outcome.batchStatus()).isPresent();
            }
        }

        @Test
        @DisplayName("each outcome's batch status is the matching FileStatus constant")
        void batchStatusValues() {
            assertThat(Outcome.OK.batchStatus()).contains(FileStatus.OK);
            assertThat(Outcome.END_OF_FILE.batchStatus()).contains(FileStatus.END_OF_FILE);
            assertThat(Outcome.NOT_FOUND.batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(Outcome.DUPLICATE.batchStatus()).contains(FileStatus.DUPLICATE);
            assertThat(Outcome.OTHER.batchStatus()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Class shape")
    class ClassShape {
        @Test
        @DisplayName("the holder is not instantiable outside reflection")
        void notInstantiable() throws ReflectiveOperationException {
            Constructor<FileStatus> constructor = FileStatus.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> constructor.newInstance("unexpected"));
            assertThat(constructor.newInstance()).isInstanceOf(FileStatus.class);
        }
    }
}
