package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link FileStatus}, the single status vocabulary shared by the batch {@code FILE STATUS}
 * tests and the online CICS {@code RESP} tests.
 */
@DisplayName("FileStatus - one status vocabulary for batch FILE STATUS and CICS RESP")
class FileStatusTest {
    private static final String EXPECTED_DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    private static final int EXPECTED_PREFIX_LENGTH = 20;

    private static final int EXPECTED_DISPLAY_LINE_LENGTH = 24;

    private static final List<String> UNENUMERATED_STATUSES = List.of("99", "37", "35", "9A");

    @Nested
    @DisplayName("Batch FILE STATUS literals")
    class BatchStatusLiterals {
        @Test
        @DisplayName("'00' is success - the status every OPEN, READ, WRITE and CLOSE guard tests")
        void okIsZeroZero() {
            assertThat(FileStatus.OK).isEqualTo("00");
        }

        @Test
        @DisplayName("'04' is a record-length conflict - a SUCCESSFUL read of a non-conforming record")
        void recordLengthConflictIsZeroFour() {
            assertThat(FileStatus.RECORD_LENGTH_CONFLICT).isEqualTo("04")
                    .hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("'04' is not folded into success, because three sites in one program abend on it")
        void recordLengthConflictIsNotSuccess() {
            assertThat(FileStatus.isOk(FileStatus.RECORD_LENGTH_CONFLICT)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(FileStatus.RECORD_LENGTH_CONFLICT)).isFalse();
            assertThat(FileStatus.RECORD_LENGTH_CONFLICT).isNotEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("'10' is end of file - the status that moves 16 into APPL-RESULT")
        void endOfFileIsOneZero() {
            assertThat(FileStatus.END_OF_FILE).isEqualTo("10");
        }

        @Test
        @DisplayName("'22' is duplicate key, even though no batch program compares against it")
        void duplicateIsTwoTwo() {
            assertThat(FileStatus.DUPLICATE).isEqualTo("22");
        }

        @Test
        @DisplayName("'23' is record not found - and is a success path in two programs")
        void notFoundIsTwoThree() {
            assertThat(FileStatus.NOT_FOUND).isEqualTo("23");
        }

        @ParameterizedTest(name = "\"{0}\" is exactly two characters")
        @ValueSource(strings = {"00", "04", "10", "22", "23"})
        @DisplayName("every status literal is exactly two characters, matching the PIC X pair")
        void everyLiteralIsExactlyTwoCharacters(String literal) {
            assertThat(literal).hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("the five literals are the exact set, with no duplicates among them")
        void theFiveLiteralsAreDistinct() {
            Set<String> literals = new LinkedHashSet<>(
                    List.of(FileStatus.OK, FileStatus.RECORD_LENGTH_CONFLICT, FileStatus.END_OF_FILE,
                            FileStatus.DUPLICATE, FileStatus.NOT_FOUND));

            assertThat(literals).hasSize(5).containsExactly("00", "04", "10", "22", "23");
        }

        @Test
        @DisplayName("a status is a String and never an int, because an int loses the field width")
        void statusesAreStringsAndNeverIntegers() {
            assertThat(Integer.parseInt(FileStatus.OK)).isZero();
            assertThat(String.valueOf(Integer.parseInt(FileStatus.OK))).isEqualTo("0").hasSize(1);
            assertThat(FileStatus.OK).hasSize(FileStatus.STATUS_LENGTH).isNotEqualTo("0");
        }

        @Test
        @DisplayName("STATUS_LENGTH is 2, fixed by the two PIC X items")
        void statusLengthIsTwo() {
            assertThat(FileStatus.STATUS_LENGTH).isEqualTo(2);
        }

        @Test
        @DisplayName("STATUS_IMAGE_LENGTH is 4, fixed by PIC 9 followed by PIC 999")
        void statusImageLengthIsFour() {
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("outcomeOfStatus - classifying a batch status, WHEN OTHER included")
    class OutcomeClassification {
        @ParameterizedTest(name = "\"{0}\" classifies as {1}")
        @CsvSource({
            "00, OK",
            "10, END_OF_FILE",
            "23, NOT_FOUND",
            "22, DUPLICATE",
            "99, OTHER",
            "37, OTHER",
            "35, OTHER",
            "9A, OTHER",
            "04, OTHER",
        })
        @DisplayName("each status maps to its outcome, and everything unenumerated maps to OTHER")
        void statusMapsToOutcome(String status, Outcome expected) {
            assertThat(FileStatus.outcomeOfStatus(status)).isSameAs(expected);
        }

        @ParameterizedTest(name = "\"{0}\" is neither OK nor END_OF_FILE nor NOT_FOUND nor DUPLICATE")
        @MethodSource("unenumeratedStatuses")
        @DisplayName("an unenumerated status is not quietly folded into one of the named outcomes")
        void unenumeratedStatusIsNotFoldedIntoANamedOutcome(String status) {
            assertThat(FileStatus.outcomeOfStatus(status))
                    .isSameAs(Outcome.OTHER)
                    .isNotIn(Outcome.OK, Outcome.END_OF_FILE, Outcome.NOT_FOUND, Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("all five outcomes are reachable from a two-character status")
        void allFiveOutcomesAreReachable() {
            Set<Outcome> reached = new LinkedHashSet<>(List.of(
                    FileStatus.outcomeOfStatus(FileStatus.OK),
                    FileStatus.outcomeOfStatus(FileStatus.END_OF_FILE),
                    FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND),
                    FileStatus.outcomeOfStatus(FileStatus.DUPLICATE),
                    FileStatus.outcomeOfStatus("99")));

            assertThat(reached).containsExactly(Outcome.OK, Outcome.END_OF_FILE, Outcome.NOT_FOUND,
                    Outcome.DUPLICATE, Outcome.OTHER);
        }

        @Test
        @DisplayName("the outcome vocabulary is exactly five constants, in declaration order")
        void thereAreExactlyFiveOutcomes() {
            assertThat(Outcome.values()).containsExactly(Outcome.OK, Outcome.END_OF_FILE,
                    Outcome.NOT_FOUND, Outcome.DUPLICATE, Outcome.OTHER);
        }

        @ParameterizedTest(name = "{0}.batchStatus() is non-null")
        @EnumSource(Outcome.class)
        @DisplayName("batchStatus() never returns null, for any outcome including OTHER")
        void batchStatusIsNeverNull(Outcome outcome) {
            assertThat(outcome.batchStatus()).isNotNull();
        }

        @ParameterizedTest(name = "{0}.batchStatus() is \"{1}\" and round-trips")
        @CsvSource({"OK, 00", "END_OF_FILE, 10", "NOT_FOUND, 23", "DUPLICATE, 22"})
        @DisplayName("the four enumerated outcomes round-trip back to their two-character status")
        void enumeratedOutcomesRoundTrip(Outcome outcome, String status) {
            assertThat(outcome.batchStatus()).contains(status);
            assertThat(FileStatus.outcomeOfStatus(status)).isSameAs(outcome);
        }

        @Test
        @DisplayName("OTHER carries no single status, because it stands for every unlisted one")
        void otherCarriesNoSingleStatus() {
            Optional<String> otherStatus = Outcome.OTHER.batchStatus();

            assertThat(otherStatus).isEmpty();
        }

        private static Stream<Arguments> unenumeratedStatuses() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("The caller branch shape from CBSTM03A - ok, eof, everything else abends")
    class CallerBranchShape {
        private static final String CONTINUE = "CONTINUE";

        private static final String SET_END_OF_FILE = "SET-END-OF-FILE";

        private static final String ABEND = "ABEND";

        @ParameterizedTest(name = "three-armed dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            "10, SET-END-OF-FILE",
            "23, ABEND",
            "22, ABEND",
            "99, ABEND",
            "37, ABEND",
        })
        @DisplayName("the three-armed EVALUATE at CBSTM03A.CBL:353-362 is expressible verbatim")
        void threeArmedDispatchMatchesTheCobol(String returnCode, String expectedAction) {
            assertThat(threeArmedDispatch(returnCode)).isEqualTo(expectedAction);
        }

        @ParameterizedTest(name = "two-armed dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            "10, ABEND",
            "23, ABEND",
            "99, ABEND",
        })
        @DisplayName("the two-armed EVALUATE at CBSTM03A.CBL:379-386 and :403-410 also holds")
        void twoArmedDispatchMatchesTheCobol(String returnCode, String expectedAction) {
            assertThat(twoArmedDispatch(returnCode)).isEqualTo(expectedAction);
        }

        @ParameterizedTest(name = "compound '00' OR '23' dispatch on \"{0}\" performs {1}")
        @CsvSource({
            "00, CONTINUE",
            "23, CONTINUE",
            "10, ABEND",
            "22, ABEND",
            "99, ABEND",
        })
        @DisplayName("the compound guard at CBACT04C.cbl:422 and CBTRN02C.cbl:481 also holds")
        void compoundDispatchMatchesTheCobol(String status, String expectedAction) {
            assertThat(compoundDispatch(status)).isEqualTo(expectedAction);
        }

        @Test
        @DisplayName("the three-armed switch needs no default arm, so no outcome can go unhandled")
        void theThreeArmedSwitchIsExhaustiveWithoutADefault() {
            assertThat(Arrays.stream(Outcome.values()).map(this::actionForOutcome).toList())
                    .containsExactly(CONTINUE, SET_END_OF_FILE, ABEND, ABEND, ABEND);
        }

        private String threeArmedDispatch(String returnCode) {
            return actionForOutcome(FileStatus.outcomeOfStatus(returnCode));
        }

        private String actionForOutcome(Outcome outcome) {
            return switch (outcome) {
                case OK -> CONTINUE;
                case END_OF_FILE -> SET_END_OF_FILE;
                case NOT_FOUND, DUPLICATE, OTHER -> ABEND;
            };
        }

        private String twoArmedDispatch(String returnCode) {
            return FileStatus.isOk(returnCode) ? CONTINUE : ABEND;
        }

        private String compoundDispatch(String status) {
            return FileStatus.isOkOrNotFound(status) ? CONTINUE : ABEND;
        }
    }

    @Nested
    @DisplayName("Predicates - one per status test the COBOL performs, driven from both sides")
    class Predicates {
        @ParameterizedTest(name = "isOk(\"{0}\") is {1}")
        @CsvSource({"00, true", "10, false", "22, false", "23, false", "99, false"})
        @DisplayName("isOk is true only for '00'")
        void isOkIsTrueOnlyForSuccess(String status, boolean expected) {
            assertThat(FileStatus.isOk(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isRecordLengthConflict(\"{0}\") is {1}")
        @CsvSource({"04, true", "00, false", "10, false", "22, false", "23, false", "99, false"})
        @DisplayName("isRecordLengthConflict is true only for '04'")
        void isRecordLengthConflictIsTrueOnlyForZeroFour(String status, boolean expected) {
            assertThat(FileStatus.isRecordLengthConflict(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isEndOfFile(\"{0}\") is {1}")
        @CsvSource({"10, true", "00, false", "22, false", "23, false", "99, false"})
        @DisplayName("isEndOfFile is true only for '10'")
        void isEndOfFileIsTrueOnlyForEndOfFile(String status, boolean expected) {
            assertThat(FileStatus.isEndOfFile(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isNotFound(\"{0}\") is {1}")
        @CsvSource({"23, true", "00, false", "10, false", "22, false", "99, false"})
        @DisplayName("isNotFound is true only for '23'")
        void isNotFoundIsTrueOnlyForNotFound(String status, boolean expected) {
            assertThat(FileStatus.isNotFound(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isDuplicate(\"{0}\") is {1}")
        @CsvSource({"22, true", "00, false", "10, false", "23, false", "99, false"})
        @DisplayName("isDuplicate is true only for '22'")
        void isDuplicateIsTrueOnlyForDuplicate(String status, boolean expected) {
            assertThat(FileStatus.isDuplicate(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "isOkOrNotFound(\"{0}\") is {1}")
        @CsvSource({
            "00, true",
            "23, true",
            "10, false",
            "22, false",
            "99, false",
            "04, false",
        })
        @DisplayName("isOkOrNotFound reproduces IF <FILE>-STATUS = '00' OR '23' exactly")
        void isOkOrNotFoundReproducesTheCompoundCondition(String status, boolean expected) {
            assertThat(FileStatus.isOkOrNotFound(status)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "exactly one predicate matches \"{0}\"")
        @ValueSource(strings = {"00", "04", "10", "22", "23"})
        @DisplayName("the five predicates are mutually exclusive over the five recognised statuses")
        void thePredicatesAreMutuallyExclusive(String status) {
            List<Boolean> matches = List.of(
                    FileStatus.isOk(status),
                    FileStatus.isRecordLengthConflict(status),
                    FileStatus.isEndOfFile(status),
                    FileStatus.isNotFound(status),
                    FileStatus.isDuplicate(status));

            assertThat(matches).containsOnlyOnce(Boolean.TRUE);
        }

        @ParameterizedTest(name = "no predicate matches \"{0}\"")
        @MethodSource("unenumeratedStatusValues")
        @DisplayName("no predicate matches an unenumerated status, so none of them is a catch-all")
        void noPredicateMatchesAnUnenumeratedStatus(String status) {
            assertThat(FileStatus.isOk(status)).isFalse();
            assertThat(FileStatus.isRecordLengthConflict(status)).isFalse();
            assertThat(FileStatus.isEndOfFile(status)).isFalse();
            assertThat(FileStatus.isNotFound(status)).isFalse();
            assertThat(FileStatus.isDuplicate(status)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(status)).isFalse();
        }

        private static Stream<Arguments> unenumeratedStatusValues() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("APPL-RESULT condition names, in both states")
    class ApplResultConditions {
        @Test
        @DisplayName("88 APPL-AOK VALUE 0")
        void applAokIsZero() {
            assertThat(FileStatus.APPL_AOK).isZero();
        }

        @Test
        @DisplayName("88 APPL-EOF VALUE 16")
        void applEofIsSixteen() {
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }

        @Test
        @DisplayName("the two condition values are distinct, so a result cannot satisfy both")
        void theTwoConditionValuesAreDistinct() {
            assertThat(FileStatus.APPL_AOK).isNotEqualTo(FileStatus.APPL_EOF);
        }

        @ParameterizedTest(name = "APPL-RESULT {0}: APPL-AOK={1}, APPL-EOF={2}")
        @CsvSource({
            "0,  true,  false",
            "16, false, true",
            "8,  false, false",
            "12, false, false",
            "4,  false, false",
        })
        @DisplayName("each condition name is driven true and false, and 8, 12 and 4 satisfy neither")
        void bothConditionNamesAreDrivenBothWays(int applResult, boolean aok, boolean eof) {
            assertThat(applResult == FileStatus.APPL_AOK).isEqualTo(aok);
            assertThat(applResult == FileStatus.APPL_EOF).isEqualTo(eof);
        }

        @ParameterizedTest(name = "status \"{0}\" derives APPL-RESULT {1}")
        @CsvSource({
            "00, 0",
            "10, 16",
            "23, 12",
            "22, 12",
            "99, 12",
        })
        @DisplayName("'10' is what links the file status to APPL-EOF, and '00' to APPL-AOK")
        void statusDerivesTheApplResultValue(String status, int expectedApplResult) {
            assertThat(applResultOf(status)).isEqualTo(expectedApplResult);
        }

        @Test
        @DisplayName("APPL-AOK shares the numeric value of DFHRESP(NORMAL) yet is a different notion")
        void applAokSharesNormalsValueButNotItsMeaning() {
            assertThat(FileStatus.APPL_AOK).isEqualTo(FileStatus.NORMAL);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isSameAs(Outcome.OK);
        }

        @Test
        @DisplayName("APPL-EOF shares the numeric value of DFHRESP(INVREQ) yet classifies apart")
        void applEofSharesInvreqsValueButNotItsMeaning() {
            assertThat(FileStatus.APPL_EOF).isEqualTo(FileStatus.INVREQ);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.END_OF_FILE))
                    .isSameAs(Outcome.END_OF_FILE);
        }

        private int applResultOf(String status) {
            if (FileStatus.isOk(status)) {
                return FileStatus.APPL_AOK;
            }
            if (FileStatus.isEndOfFile(status)) {
                return FileStatus.APPL_EOF;
            }
            return 12;
        }
    }

    @Nested
    @DisplayName("CICS RESP constants - the online half of the vocabulary")
    class CicsResponseConstants {
        private static final List<Integer> ALL_RESPONSES = List.of(
                FileStatus.NORMAL, FileStatus.NOTFND, FileStatus.DUPREC, FileStatus.DUPKEY,
                FileStatus.INVREQ, FileStatus.NOTOPEN, FileStatus.ENDFILE, FileStatus.LENGERR);

        @Test
        @DisplayName("the eight documented DFHRESP values are carried exactly")
        void theEightResponseValuesAreCarriedExactly() {
            assertThat(FileStatus.NORMAL).isEqualTo(0);
            assertThat(FileStatus.NOTFND).isEqualTo(13);
            assertThat(FileStatus.DUPREC).isEqualTo(14);
            assertThat(FileStatus.DUPKEY).isEqualTo(15);
            assertThat(FileStatus.INVREQ).isEqualTo(16);
            assertThat(FileStatus.NOTOPEN).isEqualTo(19);
            assertThat(FileStatus.ENDFILE).isEqualTo(20);
            assertThat(FileStatus.LENGERR).isEqualTo(22);
        }

        @Test
        @DisplayName("all eight responses are pairwise distinct - asserted by set size, not by eye")
        void allEightResponsesArePairwiseDistinct() {
            Set<Integer> distinct = new LinkedHashSet<>(ALL_RESPONSES);

            assertThat(ALL_RESPONSES).hasSize(8);
            assertThat(distinct).hasSize(8);
        }

        @Test
        @DisplayName("LENGERR's numeric 22 collides with DUPLICATE's text \"22\" and must not be confused")
        void lengerrValueCollidesWithDuplicateTextOnly() {
            assertThat(String.valueOf(FileStatus.LENGERR)).isEqualTo(FileStatus.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.LENGERR)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.DUPLICATE)).isSameAs(Outcome.DUPLICATE);
        }

        @ParameterizedTest(name = "response {0} classifies without throwing")
        @MethodSource("everyResponse")
        @DisplayName("every published response classifies to a non-null outcome")
        void everyResponseClassifiesToANonNullOutcome(int cicsResp) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isNotNull();
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isNotNull();
        }

        private static Stream<Arguments> everyResponse() {
            return ALL_RESPONSES.stream().map(Arguments::of);
        }

        @Test
        @DisplayName("RESP_NOT_REPORTED is -1, and so cannot collide with any DFHRESP value")
        void respNotReportedCannotCollideWithAnyResponse() {
            assertThat(FileStatus.RESP_NOT_REPORTED).isEqualTo(-1).isNegative();
            assertThat(ALL_RESPONSES).allSatisfy(response -> assertThat(response).isNotNegative());
            assertThat(ALL_RESPONSES).doesNotContain(FileStatus.RESP_NOT_REPORTED);
            assertThat(FileStatus.RESP_NOT_REPORTED).isNotEqualTo(FileStatus.NORMAL);
        }

        @ParameterizedTest(name = "respReported({0}) is true")
        @MethodSource("everyResponse")
        @DisplayName("respReported is true for every real response, including NORMAL and NOTFND")
        void respReportedIsTrueForEveryRealResponse(int cicsResp) {
            assertThat(FileStatus.respReported(cicsResp)).isTrue();
        }

        @Test
        @DisplayName("respReported is false only for the sentinel")
        void respReportedIsFalseOnlyForTheSentinel() {
            assertThat(FileStatus.respReported(FileStatus.RESP_NOT_REPORTED)).isFalse();
            assertThat(FileStatus.respReported(FileStatus.NORMAL)).isTrue();
            assertThat(FileStatus.respReported(0)).isTrue();
            assertThat(FileStatus.respReported(-2))
                    .as("another negative is not the sentinel; only -1 is")
                    .isTrue();
            assertThat(FileStatus.respReported(Integer.MAX_VALUE)).isTrue();
            assertThat(FileStatus.respReported(Integer.MIN_VALUE)).isTrue();
        }

        @ParameterizedTest(name = "respNotReportedImage({0}) is {0} asterisks")
        @ValueSource(ints = {1, 4, 9, 18})
        @DisplayName("the image is exactly as wide as asked, and holds no digit")
        void theImageIsWidthPreservingAndNotNumeric(int digits) {
            String image = FileStatus.respNotReportedImage(digits);

            assertThat(image).hasSize(digits);
            assertThat(image.chars()).allMatch(character -> character == '*');
            assertThat(image.chars().anyMatch(Character::isDigit))
                    .as("no reader can mistake the image for a value")
                    .isFalse();
        }

        @Test
        @DisplayName("the nine-digit image lines up with a rendered PIC S9(09) operand")
        void theNineDigitImageMatchesADisplayedOperandsWidth() {
            assertThat(FileStatus.respNotReportedImage(9))
                    .isEqualTo("*********")
                    .hasSameSizeAs("000000000");
        }

        @ParameterizedTest(name = "respNotReportedImage({0}) is refused")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a width below one digit is refused, because no such field exists")
        void aWidthBelowOneDigitIsRefused(int digits) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.respNotReportedImage(digits))
                    .withMessageContaining("at least one digit position");
        }
    }

    @Nested
    @DisplayName("The collapse onto one vocabulary - the entire reason the class exists")
    class VocabularyCollapse {
        @Test
        @DisplayName("each response classifies to the outcome the documented mapping names")
        void eachResponseClassifiesAsDocumented() {
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isSameAs(Outcome.OK);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.ENDFILE))
                    .isSameAs(Outcome.END_OF_FILE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND)).isSameAs(Outcome.NOT_FOUND);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPREC)).isSameAs(Outcome.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPKEY)).isSameAs(Outcome.DUPLICATE);
        }

        @ParameterizedTest(name = "response {0} and status \"{1}\" reach the same outcome")
        @CsvSource({
            "0,  00",
            "20, 10",
            "13, 23",
            "14, 22",
            "15, 22",
        })
        @DisplayName("a batch status and its CICS response are indistinguishable to the caller")
        void batchAndOnlineReachTheSameOutcome(int cicsResp, String batchStatus) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp))
                    .isSameAs(FileStatus.outcomeOfStatus(batchStatus));
        }

        @ParameterizedTest(name = "response {0} has no batch equivalent and classifies as OTHER")
        @CsvSource({
            "16",
            "19",
            "22",
            "99",
            "-1",
        })
        @DisplayName("INVREQ, NOTOPEN, LENGERR and anything unknown drive the caller to abend")
        void responsesWithoutABatchEquivalentClassifyAsOther(int cicsResp) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isSameAs(Outcome.OTHER);
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isEmpty();
        }

        @Test
        @DisplayName("batchStatusOfCicsResp translates the five mappable responses")
        void batchStatusOfCicsRespTranslatesTheMappableResponses() {
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NORMAL))
                    .contains(FileStatus.OK);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.ENDFILE))
                    .contains(FileStatus.END_OF_FILE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NOTFND))
                    .contains(FileStatus.NOT_FOUND);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPREC))
                    .contains(FileStatus.DUPLICATE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPKEY))
                    .contains(FileStatus.DUPLICATE);
        }

        @Test
        @DisplayName("cicsRespOfBatchStatus translates only the three one-to-one statuses")
        void cicsRespOfBatchStatusTranslatesTheOneToOneStatuses() {
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.OK))
                    .hasValue(FileStatus.NORMAL);
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.END_OF_FILE))
                    .hasValue(FileStatus.ENDFILE);
            assertThat(FileStatus.cicsRespOfBatchStatus(FileStatus.NOT_FOUND))
                    .hasValue(FileStatus.NOTFND);
        }

        @Test
        @DisplayName("'22' has no single CICS response, because DUPREC and DUPKEY both map onto it")
        void duplicateHasNoUnambiguousReverseMapping() {
            OptionalInt reverse = FileStatus.cicsRespOfBatchStatus(FileStatus.DUPLICATE);

            assertThat(reverse).isEmpty();
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPREC))
                    .isEqualTo(FileStatus.batchStatusOfCicsResp(FileStatus.DUPKEY));
        }

        @ParameterizedTest(name = "unenumerated status \"{0}\" has no CICS response")
        @MethodSource("unenumeratedStatusValues")
        @DisplayName("an unenumerated status has no CICS response, rather than a fabricated one")
        void unenumeratedStatusHasNoCicsResponse(String status) {
            assertThat(FileStatus.cicsRespOfBatchStatus(status)).isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\" round-trips through both conversions")
        @ValueSource(strings = {"00", "10", "23"})
        @DisplayName("the three one-to-one statuses survive a full round trip unchanged")
        void theOneToOneStatusesRoundTrip(String status) {
            OptionalInt response = FileStatus.cicsRespOfBatchStatus(status);

            assertThat(response).isPresent();
            assertThat(FileStatus.batchStatusOfCicsResp(response.getAsInt())).contains(status);
        }

        private static Stream<Arguments> unenumeratedStatusValues() {
            return UNENUMERATED_STATUSES.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("toStatusImage, numeric branch - the ELSE arm of 9910-DISPLAY-IO-STATUS")
    class StatusImageNumericBranch {
        @ParameterizedTest(name = "\"{0}\" renders as \"{1}\"")
        @CsvSource({
            "00, 0000",
            "10, 0010",
            "22, 0022",
            "23, 0023",
            "37, 0037",
            "89, 0089",
        })
        @DisplayName("a numeric status not starting with '9' renders as \"00\" plus the status")
        void numericStatusRendersWithTwoLeadingZeros(String status, String expectedImage) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(expectedImage);
        }

        @ParameterizedTest(name = "\"{0}\" renders exactly four characters starting \"00\"")
        @ValueSource(strings = {"00", "10", "22", "23", "37", "89"})
        @DisplayName("the numeric branch always emits four characters, the first two of them zeros")
        void numericBranchAlwaysEmitsFourCharacters(String status) {
            String image = FileStatus.toStatusImage(status);

            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image).startsWith("00").endsWith(status);
            assertThat(image).isEqualTo("00" + status);
        }

        @ParameterizedTest(name = "both overloads agree for \"{0}\"")
        @ValueSource(strings = {"00", "10", "22", "23"})
        @DisplayName("the String overload and the char-pair overload agree")
        void bothOverloadsAgree(String status) {
            assertThat(FileStatus.toStatusImage(status))
                    .isEqualTo(FileStatus.toStatusImage(status.charAt(0), status.charAt(1)));
        }
    }

    @Nested
    @DisplayName("toStatusImage, extended branch - both operands of the IF condition")
    class StatusImageExtendedBranch {
        @ParameterizedTest(name = "operand (i): {0} renders as \"{3}\"")
        @MethodSource("nonNumericCases")
        @DisplayName("operand (i) - a non-numeric status takes the extended branch")
        void nonNumericStatusTakesTheExtendedBranch(String label, char stat1, char stat2,
                String expectedImage) {
            assertThat(label).isNotBlank();
            String image = FileStatus.toStatusImage(stat1, stat2);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image.charAt(0)).isEqualTo(stat1);
            assertThat(image).isNotEqualTo("00" + stat1 + stat2);
        }

        @ParameterizedTest(name = "operand (ii): numeric \"{0}\" renders as \"{1}\", not \"00{0}\"")
        @CsvSource({
            "90, 9048",
            "91, 9049",
            "93, 9051",
            "97, 9055",
            "99, 9057",
        })
        @DisplayName("operand (ii) - a numeric status starting '9' still takes the extended branch")
        void numericNineStatusTakesTheExtendedBranchThroughTheSecondOperand(
                String status, String expectedImage) {
            assertThat(status).matches("[0-9]{2}");

            String image = FileStatus.toStatusImage(status);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(image).startsWith("9");
            assertThat(image).isNotEqualTo("00" + status);
        }

        @ParameterizedTest(name = "raw byte {0} after '9' renders as \"{3}\"")
        @MethodSource("rawSecondByteCases")
        @DisplayName("the second byte is read as an unsigned value in 0..255, never as a signed one")
        void theSecondByteIsReadAsAnUnsignedValue(String label, char stat2, int expectedValue,
                String expectedImage) {
            assertThat(label).isNotBlank();
            String image = FileStatus.toStatusImage('9', stat2);

            assertThat(image).isEqualTo(expectedImage);
            assertThat(image).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(Integer.parseInt(image.substring(1))).isEqualTo(expectedValue);
        }

        @Test
        @DisplayName("only the low-order eight bits matter, matching a single-byte PIC X")
        void onlyTheLowOrderEightBitsOfTheSecondByteMatter() {
            assertThat(FileStatus.toStatusImage('9', (char) 0x0141)).isEqualTo("9065");
            assertThat(FileStatus.toStatusImage('9', (char) 0x0041)).isEqualTo("9065");
        }

        @Test
        @DisplayName("the char-pair overload accepts any byte pair without a width guard")
        void theCharPairOverloadNeedsNoGuard() {
            assertThatCode(() -> FileStatus.toStatusImage((char) 0x00, (char) 0xFF))
                    .doesNotThrowAnyException();
            assertThat(FileStatus.toStatusImage((char) 0x00, (char) 0xFF))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        private static Stream<Arguments> nonNumericCases() {
            return Stream.of(
                    Arguments.of("'A' then 'B'", 'A', 'B', "A066"),
                    Arguments.of("'0' then 'A'", '0', 'A', "0065"),
                    Arguments.of("'1' then space", '1', ' ', "1032"),
                    Arguments.of("space then '5'", ' ', '5', " 053"),
                    Arguments.of("'X' then 'Y'", 'X', 'Y', "X089"),
                    Arguments.of("'9' then 'Z'", '9', 'Z', "9090"),
                    Arguments.of("0x00 then 0x00", (char) 0x00, (char) 0x00, "\u0000" + "000"));
        }

        private static Stream<Arguments> rawSecondByteCases() {
            return Stream.of(
                    Arguments.of("0x00", (char) 0x00, 0, "9000"),
                    Arguments.of("0x0A", (char) 0x0A, 10, "9010"),
                    Arguments.of("0x7F", (char) 0x7F, 127, "9127"),
                    Arguments.of("0x80", (char) 0x80, 128, "9128"),
                    Arguments.of("0xFF", (char) 0xFF, 255, "9255"));
        }
    }

    @Nested
    @DisplayName("toDisplayLine - the literal NNNN survives, byte for byte")
    class DisplayLine {
        @Test
        @DisplayName("the prefix is the COBOL literal exactly, ending in four literal N characters")
        void thePrefixIsTheCobolLiteralExactly() {
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo(EXPECTED_DISPLAY_PREFIX);
            assertThat(FileStatus.DISPLAY_PREFIX).hasSize(EXPECTED_PREFIX_LENGTH);
            assertThat(FileStatus.DISPLAY_PREFIX).endsWith("NNNN");
        }

        @Test
        @DisplayName("there is exactly one space after the colon, and no run of two spaces anywhere")
        void thereIsExactlyOneSpaceAfterTheColon() {
            assertThat(FileStatus.DISPLAY_PREFIX).contains("IS: N");
            assertThat(FileStatus.DISPLAY_PREFIX).doesNotContain("  ");
            assertThat(FileStatus.DISPLAY_PREFIX.chars().filter(c -> c == ' ').count()).isEqualTo(3);
        }

        @ParameterizedTest(name = "toDisplayLine(\"{0}\") is \"{1}\"")
        @CsvSource({
            "00, 'FILE STATUS IS: NNNN0000'",
            "10, 'FILE STATUS IS: NNNN0010'",
            "22, 'FILE STATUS IS: NNNN0022'",
            "23, 'FILE STATUS IS: NNNN0023'",
        })
        @DisplayName("the composed line keeps NNNN and appends the image, byte for byte")
        void theComposedLineKeepsTheLiteralAndAppendsTheImage(String status, String expectedLine) {
            String line = FileStatus.toDisplayLine(status);

            assertThat(line).isEqualTo(expectedLine);
            assertThat(line).hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
            assertThat(line).isEqualTo(FileStatus.DISPLAY_PREFIX + FileStatus.toStatusImage(status));
        }

        @ParameterizedTest(name = "toDisplayLine(\"{0}\") still contains the literal NNNN")
        @ValueSource(strings = {"00", "10", "22", "23", "37"})
        @DisplayName("NNNN is never substituted away, and the digits never appear in its place")
        void theLiteralIsNeverSubstituted(String status) {
            String line = FileStatus.toDisplayLine(status);

            assertThat(line).contains("NNNN").startsWith(EXPECTED_DISPLAY_PREFIX);
            assertThat(line).isNotEqualTo("FILE STATUS IS: " + FileStatus.toStatusImage(status));
        }

        @Test
        @DisplayName("the line is byte-identical in US-ASCII, not merely equal as a String")
        void theLineIsByteIdenticalInAscii() {
            byte[] actual = FileStatus.toDisplayLine("10").getBytes(StandardCharsets.US_ASCII);
            byte[] expected = "FILE STATUS IS: NNNN0010".getBytes(StandardCharsets.US_ASCII);

            assertThat(actual).containsExactly(expected);
            assertThat(actual).hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }

        @Test
        @DisplayName("the extended branch composes the same way, prefix then four-character image")
        void theExtendedBranchComposesTheSameWay() {
            assertThat(FileStatus.toDisplayLine('9', (char) 0x0A))
                    .isEqualTo("FILE STATUS IS: NNNN9010")
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
            assertThat(FileStatus.toDisplayLine('A', 'B'))
                    .isEqualTo("FILE STATUS IS: NNNNA066")
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }

        @ParameterizedTest(name = "both toDisplayLine overloads agree for \"{0}\"")
        @ValueSource(strings = {"00", "10", "22", "23", "90", "9A"})
        @DisplayName("the String overload and the char-pair overload compose identically")
        void bothDisplayLineOverloadsAgree(String status) {
            assertThat(FileStatus.toDisplayLine(status))
                    .isEqualTo(FileStatus.toDisplayLine(status.charAt(0), status.charAt(1)));
        }

        @ParameterizedTest(name = "every line for \"{0}\" is 24 characters")
        @ValueSource(strings = {"00", "10", "22", "23", "37", "90", "99", "9A", "AB"})
        @DisplayName("every line is 20 prefix characters plus a 4-character image, whichever branch")
        void everyLineIsTwentyPlusFourCharacters(String status) {
            assertThat(FileStatus.toStatusImage(status)).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(FileStatus.toDisplayLine(status))
                    .hasSize(EXPECTED_PREFIX_LENGTH + FileStatus.STATUS_IMAGE_LENGTH)
                    .hasSize(EXPECTED_DISPLAY_LINE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Guards - a FILE STATUS is two bytes by declaration, so anything else is a defect")
    class Guards {
        @ParameterizedTest(name = "{0} rejects null")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status rejects null with a diagnostic message")
        void everyStatusAcceptingMethodRejectsNull(String methodName, Consumer<String> invocation) {
            assertThatNullPointerException()
                    .as(methodName)
                    .isThrownBy(() -> invocation.accept(null))
                    .withMessageContaining("two-byte");
        }

        @ParameterizedTest(name = "{0} rejects a status of the wrong width")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status rejects a value that is not two characters")
        void everyStatusAcceptingMethodRejectsTheWrongWidth(String methodName,
                Consumer<String> invocation) {
            assertThatIllegalArgumentException()
                    .as(methodName + " with one character")
                    .isThrownBy(() -> invocation.accept("0"));
            assertThatIllegalArgumentException()
                    .as(methodName + " with three characters")
                    .isThrownBy(() -> invocation.accept("000"));
        }

        @ParameterizedTest(name = "{0} accepts a well-formed two-character status")
        @MethodSource("statusAcceptingMethods")
        @DisplayName("every method that takes a status accepts a well-formed one")
        void everyStatusAcceptingMethodAcceptsAWellFormedStatus(String methodName,
                Consumer<String> invocation) {
            assertThatCode(() -> invocation.accept(FileStatus.OK))
                    .as(methodName)
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "\"{0}\" is rejected because it is not two characters")
        @ValueSource(strings = {"", "0", "000", "0000", "00 ", " 00"})
        @DisplayName("the width guard is exact: empty, one, three, four and padded values all fail")
        void theWidthGuardIsExact(String malformed) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.toStatusImage(malformed));
        }

        @Test
        @DisplayName("the width message names the offending length and the offending value")
        void theWidthMessageNamesTheLengthAndTheValue() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FileStatus.outcomeOfStatus("000"))
                    .withMessageContaining("exactly 2")
                    .withMessageContaining("was 3")
                    .withMessageContaining("000");
        }

        @Test
        @DisplayName("the null message points at the two-byte COBOL declaration")
        void theNullMessagePointsAtTheDeclaration() {
            assertThatNullPointerException()
                    .isThrownBy(() -> FileStatus.isOk(null))
                    .withMessageContaining("IO-STAT1")
                    .withMessageContaining("IO-STAT2");
        }

        private static Stream<Arguments> statusAcceptingMethods() {
            return Stream.of(
                    Arguments.of("toStatusImage", (Consumer<String>) FileStatus::toStatusImage),
                    Arguments.of("toDisplayLine", (Consumer<String>) FileStatus::toDisplayLine),
                    Arguments.of("isOk", (Consumer<String>) FileStatus::isOk),
                    Arguments.of("isEndOfFile", (Consumer<String>) FileStatus::isEndOfFile),
                    Arguments.of("isNotFound", (Consumer<String>) FileStatus::isNotFound),
                    Arguments.of("isDuplicate", (Consumer<String>) FileStatus::isDuplicate),
                    Arguments.of("isOkOrNotFound", (Consumer<String>) FileStatus::isOkOrNotFound),
                    Arguments.of("outcomeOfStatus", (Consumer<String>) FileStatus::outcomeOfStatus),
                    Arguments.of("cicsRespOfBatchStatus",
                            (Consumer<String>) FileStatus::cicsRespOfBatchStatus));
        }
    }

    @Nested
    @DisplayName("Class shape - immutable, stateless, non-instantiable and a graph root")
    class ClassShapeAndImmutability {
        private static final List<String> PUBLISHED_CONSTANT_NAMES = List.of(
                "OK", "RECORD_LENGTH_CONFLICT", "END_OF_FILE", "DUPLICATE", "NOT_FOUND",
                "OPEN_MODE_CONFLICT", "STATUS_LENGTH",
                "STATUS_IMAGE_LENGTH", "NORMAL", "NOTFND", "DUPREC", "DUPKEY", "INVREQ", "NOTOPEN",
                "ENDFILE", "LENGERR", "NO_REASON_CODE", "RESP_NOT_REPORTED", "APPL_AOK", "APPL_EOF",
                "DISPLAY_PREFIX");

        @Test
        @DisplayName("the class is final, so no subclass can add state to it")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(FileStatus.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the sole constructor is private, and its body holds no state")
        void theSoleConstructorIsPrivate() throws ReflectiveOperationException {
            assertThat(FileStatus.class.getDeclaredConstructors()).hasSize(1);

            Constructor<FileStatus> constructor = FileStatus.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }

        @Test
        @DisplayName("every published constant is public static final, verified reflectively")
        void everyPublishedConstantIsPublicStaticFinal() {
            List<Field> publicFields = Arrays.stream(FileStatus.class.getFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(publicFields)
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrderElementsOf(PUBLISHED_CONSTANT_NAMES);
            assertThat(publicFields).allSatisfy(field -> {
                assertThat(Modifier.isPublic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            });
        }

        @Test
        @DisplayName("there is no mutable state at all: every field is static final and none is an array")
        void thereIsNoMutableState() {
            List<Field> declaredFields = Arrays.stream(FileStatus.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredFields).isNotEmpty();
            assertThat(declaredFields).allSatisfy(field -> {
                assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(field.getType().isArray()).as(field.getName()).isFalse();
            });
        }

        @Test
        @DisplayName("the Outcome enum is immutable too: every declared field is final")
        void theOutcomeEnumIsImmutable() {
            List<Field> declaredFields = Arrays.stream(Outcome.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredFields).isNotEmpty();
            assertThat(declaredFields).allSatisfy(field ->
                    assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue());
        }

        @Test
        @DisplayName("the published API mentions no other application type, so the graph stays acyclic")
        void thePublishedApiMentionsNoOtherApplicationType() {
            Field[] declaredFields = FileStatus.class.getDeclaredFields();
            Method[] declaredMethods = FileStatus.class.getDeclaredMethods();

            List<String> applicationTypes = Stream.concat(
                            Arrays.stream(declaredFields)
                                    .filter(field -> !field.isSynthetic())
                                    .map(Field::getType),
                            Arrays.stream(declaredMethods)
                                    .filter(method -> !method.isSynthetic())
                                    .flatMap(method -> Stream.concat(
                                            Stream.of(method.getReturnType()),
                                            Arrays.stream(method.getParameterTypes()))))
                    .map(Class::getName)
                    .filter(name -> name.startsWith("com.vsergeychik.carddemo"))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(applicationTypes)
                    .containsExactly("com.vsergeychik.carddemo.common.FileStatus$Outcome");
        }

        @Test
        @DisplayName("both conversion results are value types, so no caller can be handed null")
        void bothConversionResultsAreValueTypes() {
            Optional<String> absentBatchStatus =
                    FileStatus.batchStatusOfCicsResp(FileStatus.NOTOPEN);
            OptionalInt absentResponse = FileStatus.cicsRespOfBatchStatus(FileStatus.DUPLICATE);

            assertThat(absentBatchStatus).isNotNull().isEmpty();
            assertThat(absentResponse).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("toStatusImage narrows both PIC X operands - F11")
    class StatusCharacterNarrowing {
        @Test
        @DisplayName("a wide first operand classifies and renders as its low-order byte")
        void aWideFirstOperandIsNarrowed() {
            assertThat(FileStatus.toStatusImage((char) 0x0130, '0'))
                    .isEqualTo(FileStatus.toStatusImage('0', '0'))
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("a wide second operand classifies and renders as its low-order byte")
        void aWideSecondOperandIsNarrowed() {
            assertThat(FileStatus.toStatusImage('0', (char) 0x0130))
                    .isEqualTo(FileStatus.toStatusImage('0', '0'))
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("a wide operand whose low byte is '9' still selects the '9' arm")
        void aWideOperandWhoseLowByteIsNineSelectsTheNineArm() {
            assertThat(FileStatus.toStatusImage((char) 0x0139, (char) 0x000A))
                    .isEqualTo(FileStatus.toStatusImage('9', (char) 0x000A))
                    .isEqualTo("9010");
        }

        @Test
        @DisplayName("the non-numeric arm renders the narrowed first byte, not the wide char")
        void theNonNumericArmRendersTheNarrowedFirstByte() {
            assertThat(FileStatus.toStatusImage((char) 0x0141, '\u0000'))
                    .isEqualTo("A000")
                    .doesNotContain(String.valueOf((char) 0x0141));
        }

        @Test
        @DisplayName("the highest byte value reads as 255, never as -1")
        void theHighestByteValueReadsAsUnsigned() {
            assertThat(FileStatus.toStatusImage('9', (char) 0x00FF)).isEqualTo("9255");
            assertThat(FileStatus.toStatusImage('9', (char) 0x01FF)).isEqualTo("9255");
        }

        @Test
        @DisplayName("toDisplayLine inherits the narrowing through toStatusImage")
        void toDisplayLineInheritsTheNarrowing() {
            assertThat(FileStatus.toDisplayLine((char) 0x0130, '0'))
                    .isEqualTo(FileStatus.toDisplayLine('0', '0'));
        }
    }
}
