/*
 * ******************************************************************
 * Program     : FileServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the third collapse rule: the whole of the
 *               CBSTM03B call contract as one DD-name-keyed bean.
 *               Covers the twelve implemented cells of the four-file
 *               by six-operation matrix, the eight that are
 *               structurally unreachable, both latent defects the
 *               source carries, the key and key-length boundary, and
 *               the fact that neither a payload nor a key can reach a
 *               string representation.
 * Source      : app/cbl/CBSTM03B.CBL:30-53   (FILE-CONTROL, 2 SEQ + 2 RANDOM)
 *               app/cbl/CBSTM03B.CBL:58-78   (the four FD record layouts)
 *               app/cbl/CBSTM03B.CBL:100-112 (LK-M03B-AREA, 1040 bytes)
 *               app/cbl/CBSTM03B.CBL:116-131 (0000-START, 9999-GOBACK)
 *               app/cbl/CBSTM03B.CBL:133-229 (the four handlers)
 *               app/cbl/CBSTM03A.CBL:347-364 (the caller's read idiom)
 *               app/cbl/CBSTM03A.CBL:736     (open: 00 OR 04)
 *               app/cbl/CBSTM03A.CBL:379     (keyed get: 00 only) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Unit tests for the statement file service.
 *
 * <p>Every assertion is anchored to a locator in {@code app/cbl/CBSTM03B.CBL} or its caller
 * {@code app/cbl/CBSTM03A.CBL}, both read case-sensitively with the carriage return stripped. The two defects
 * the source carries are asserted as behaviour rather than merely documented, because a later well-meaning
 * change that "fixes" either one would break parity, and only a test can catch that.
 */
@DisplayName("FileService: the CBSTM03B call contract as one DD-keyed bean")
class FileServiceTest {

    /**
     * The nominal success status.
     */
    private static final String OK = "00";

    /**
     * The secondary success status, accepted at nine sites in the caller and nowhere else in the corpus.
     */
    private static final String SECONDARY = "04";

    /**
     * End of file.
     */
    private static final String EOF = "10";

    /**
     * Record not found.
     */
    private static final String NOT_FOUND = "23";

    /**
     * The status a register holds before any input or output has set one.
     */
    private static final String UNSET = "  ";

    /**
     * Builds a service over a fake binding for every DD.
     *
     * @param bindings the bindings to expose
     * @return the service under test
     */
    private static FileService serviceOver(final FileService.Dataset... bindings) {
        return new FileService(new FileStatusMapper(), List.of(bindings));
    }

    /**
     * Builds a service over every binding in a DD-keyed map.
     *
     * @param bindings the bindings to expose
     * @return the service under test
     */
    private static FileService serviceOver(final Map<FileService.Dd, FakeDataset> bindings) {
        final List<FileService.Dataset> contributed = new ArrayList<>(bindings.values());
        return new FileService(new FileStatusMapper(), contributed);
    }

    /**
     * Runs an action that must abend and returns the abend, so that its four work-area fields can be asserted
     * without relying on a varargs consumer overload.
     *
     * @param action the action expected to abend
     * @return the abend the action threw
     */
    private static FatalProcessingException catchAbend(final Runnable action) {
        try {
            action.run();
        } catch (final FatalProcessingException abend) {
            return abend;
        }
        throw new AssertionError("the action was expected to abend but completed normally");
    }

    /**
     * Builds a fake binding for every one of the four DDs, each answering successfully.
     *
     * @return the bindings, keyed by DD for convenient assertion
     */
    private static Map<FileService.Dd, FakeDataset> allBindings() {
        final Map<FileService.Dd, FakeDataset> bindings = new EnumMap<>(FileService.Dd.class);
        for (final FileService.Dd dd : FileService.Dd.values()) {
            bindings.put(dd, new FakeDataset(dd));
        }
        return bindings;
    }

    /**
     * Produces a record of exactly the width the DD's FD declares.
     *
     * @param dd the dataset whose geometry applies
     * @param fill the character to repeat
     * @return fixed width text of exactly {@code dd.recordWidth()} characters
     */
    private static String recordFor(final FileService.Dd dd, final char fill) {
        return String.valueOf(fill).repeat(dd.recordWidth());
    }

    @Nested
    @DisplayName("1. The twelve implemented cells of the four-by-six matrix")
    class ImplementedCells {

        /**
         * The twelve pairs the handler bodies of {@code app/cbl/CBSTM03B.CBL} actually implement.
         *
         * @return one argument pair per implemented cell
         */
        static Stream<Arguments> implementedCells() {
            final List<Arguments> cells = new ArrayList<>();
            for (final FileService.Dd dd : FileService.Dd.values()) {
                cells.add(Arguments.of(dd, FileService.Operation.OPEN));
                cells.add(Arguments.of(dd, FileService.Operation.CLOSE));
                cells.add(Arguments.of(dd, dd.accessMode().readOperation()));
            }
            return cells.stream();
        }

        @ParameterizedTest(name = "{0} implements {1}")
        @MethodSource("implementedCells")
        @DisplayName("supports reports exactly twelve implemented pairs")
        void supportsReportsTheImplementedCells(final FileService.Dd dd,
                final FileService.Operation operation) {
            assertThat(serviceOver().supports(dd, operation)).isTrue();
        }

        @Test
        @DisplayName("the matrix is twelve of twenty-four, three operations per dataset")
        void theMatrixIsTwelveOfTwentyFour() {
            final FileService service = serviceOver();
            long implemented = 0;
            for (final FileService.Dd dd : FileService.Dd.values()) {
                long perDataset = 0;
                for (final FileService.Operation operation : FileService.Operation.values()) {
                    if (service.supports(dd, operation)) {
                        perDataset++;
                    }
                }
                assertThat(perDataset).as("operations implemented by %s", dd).isEqualTo(3);
                implemented += perDataset;
            }
            assertThat(implemented).isEqualTo(12);
            assertThat(FileService.Dd.values().length * FileService.Operation.values().length).isEqualTo(24);
        }

        @ParameterizedTest(name = "{0} performs a real open")
        @EnumSource(FileService.Dd.class)
        @DisplayName("OPEN reaches the binding and publishes its status - L136, L160, L184, L209")
        void openReachesTheBinding(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            final FileService.FileServiceResult result =
                    serviceOver(binding).execute(FileService.FileServiceRequest.of(dd,
                            FileService.Operation.OPEN));

            assertThat(binding.openCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(OK);
        }

        @ParameterizedTest(name = "{0} performs a real close")
        @EnumSource(FileService.Dd.class)
        @DisplayName("CLOSE reaches the binding and publishes its status - L147, L171, L196, L221")
        void closeReachesTheBinding(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            final FileService.FileServiceResult result =
                    serviceOver(binding).execute(FileService.FileServiceRequest.of(dd,
                            FileService.Operation.CLOSE));

            assertThat(binding.closeCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(OK);
        }

        @ParameterizedTest(name = "{0} performs a real sequential read")
        @EnumSource(value = FileService.Dd.class, names = {"TRNXFILE", "XREFFILE"})
        @DisplayName("the two sequential datasets implement the plain read - L141, L165")
        void sequentialDatasetsImplementThePlainRead(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            binding.queueSequentialRead(FileService.DatasetRead.of(OK, recordFor(dd, 'A')));

            final FileService.FileServiceResult result =
                    serviceOver(binding).execute(FileService.FileServiceRequest.of(dd,
                            FileService.Operation.READ));

            assertThat(binding.readNextCount()).isEqualTo(1);
            assertThat(binding.readByKeyCount()).isZero();
            assertThat(result.returnCode()).isEqualTo(OK);
            assertThat(result.record(dd)).isEqualTo(recordFor(dd, 'A'));
        }

        @ParameterizedTest(name = "{0} performs a real keyed read")
        @EnumSource(value = FileService.Dd.class, names = {"CUSTFILE", "ACCTFILE"})
        @DisplayName("the two random datasets implement the keyed read - L189-L190, L214-L215")
        void randomDatasetsImplementTheKeyedRead(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            final String key = "7".repeat(dd.keyWidth());
            binding.stubKeyedRead(key, FileService.DatasetRead.of(OK, recordFor(dd, 'B')));

            final FileService.FileServiceResult result = serviceOver(binding)
                    .execute(FileService.FileServiceRequest.keyed(dd, key, dd.keyWidth()));

            assertThat(binding.readByKeyCount()).isEqualTo(1);
            assertThat(binding.readNextCount()).isZero();
            assertThat(binding.lastKey()).isEqualTo(key);
            assertThat(result.record(dd)).isEqualTo(recordFor(dd, 'B'));
        }
    }

    @Nested
    @DisplayName("2. The twelve unimplemented cells perform no input or output - defect A")
    class UnimplementedCells {

        /**
         * The pairs no handler in {@code app/cbl/CBSTM03B.CBL} implements: the write and the rewrite for every
         * dataset, plus the read form each dataset's access mode excludes.
         *
         * <p><strong>Count reconciliation.</strong> The specification states eight unimplemented cells in two
         * places, yet its own matrix states twelve of twenty-four implemented, and twenty-four less twelve is
         * twelve. The source settles it: {@code M03B-WRITE} and {@code M03B-REWRITE} are referenced by no
         * handler at all, which is eight cells, and additionally {@code M03B-READ} is absent from
         * {@code CUSTFILE} and {@code ACCTFILE} while {@code M03B-READ-K} is absent from {@code TRNXFILE} and
         * {@code XREFFILE}, which is four more. <strong>Twelve is the arithmetic the handler bodies support</strong>,
         * so all twelve are asserted here - a superset of the eight the specification names. Severity: Medium,
         * citation accuracy rather than behaviour.
         *
         * @return one argument pair per unimplemented cell
         */
        static Stream<Arguments> unimplementedCells() {
            final List<Arguments> cells = new ArrayList<>();
            for (final FileService.Dd dd : FileService.Dd.values()) {
                cells.add(Arguments.of(dd, FileService.Operation.WRITE));
                cells.add(Arguments.of(dd, FileService.Operation.REWRITE));
            }
            cells.add(Arguments.of(FileService.Dd.TRNXFILE, FileService.Operation.READ_K));
            cells.add(Arguments.of(FileService.Dd.XREFFILE, FileService.Operation.READ_K));
            cells.add(Arguments.of(FileService.Dd.CUSTFILE, FileService.Operation.READ));
            cells.add(Arguments.of(FileService.Dd.ACCTFILE, FileService.Operation.READ));
            return cells.stream();
        }

        @ParameterizedTest(name = "{0} does not implement {1}")
        @MethodSource("unimplementedCells")
        @DisplayName("supports reports all twelve unimplemented pairs as unimplemented")
        void supportsReportsTheUnimplementedCells(final FileService.Dd dd,
                final FileService.Operation operation) {
            assertThat(serviceOver().supports(dd, operation)).isFalse();
        }

        @ParameterizedTest(name = "{0} with {1} performs no input or output")
        @MethodSource("unimplementedCells")
        @DisplayName("no IF fires, so the binding is never touched")
        void anUnimplementedCellTouchesNothing(final FileService.Dd dd,
                final FileService.Operation operation) {
            final FakeDataset binding = new FakeDataset(dd);

            serviceOver(binding).execute(FileService.FileServiceRequest.raw(dd.ddName(), operation, "", 0));

            assertThat(binding.totalCalls()).isZero();
        }

        @ParameterizedTest(name = "{0} with {1} publishes the unset register")
        @MethodSource("unimplementedCells")
        @DisplayName("on a fresh service the epilogue publishes the unset status, not a success")
        void anUnimplementedCellPublishesTheUnsetRegister(final FileService.Dd dd,
                final FileService.Operation operation) {
            final FakeDataset binding = new FakeDataset(dd);

            final FileService.FileServiceResult result = serviceOver(binding)
                    .execute(FileService.FileServiceRequest.raw(dd.ddName(), operation, "", 0));

            assertThat(result.returnCode()).isEqualTo(UNSET);
        }

        @ParameterizedTest(name = "{0} leaks its previous status")
        @EnumSource(FileService.Dd.class)
        @DisplayName("defect A: an unimplemented operation republishes the previous call's status")
        void anUnimplementedCellRepublishesTheStaleStatus(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            binding.openStatus(NOT_FOUND);
            final FileService service = serviceOver(binding);

            final String fromRealCall = service
                    .execute(FileService.FileServiceRequest.of(dd, FileService.Operation.OPEN)).returnCode();
            final FileService.FileServiceResult stale = service.execute(FileService.FileServiceRequest
                    .raw(dd.ddName(), FileService.Operation.WRITE, "", 0));

            assertThat(fromRealCall).isEqualTo(NOT_FOUND);
            assertThat(binding.totalCalls()).as("the write performed no input or output").isEqualTo(1);
            assertThat(stale.returnCode())
                    .as("the status register survived and was republished unchanged")
                    .isEqualTo(NOT_FOUND);
        }

        @Test
        @DisplayName("the unimplemented cells number twelve, of which eight are the write and the rewrite")
        void theUnimplementedCellsNumberTwelve() {
            final List<Arguments> cells = unimplementedCells().toList();
            final long writeAndRewrite = cells.stream()
                    .filter(cell -> cell.get()[1] == FileService.Operation.WRITE
                            || cell.get()[1] == FileService.Operation.REWRITE)
                    .count();

            assertThat(cells).hasSize(12);
            assertThat(writeAndRewrite).isEqualTo(8);
            assertThat(cells).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("defect A: the payload is left exactly as the caller pre-set it")
        void anUnimplementedCellLeavesThePayloadAlone() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);

            final FileService.FileServiceResult result = serviceOver(binding)
                    .execute(FileService.FileServiceRequest.raw(FileService.Dd.CUSTFILE.ddName(),
                            FileService.Operation.READ, "", 0));

            assertThat(result.payload()).isEqualTo(" ".repeat(FileService.PAYLOAD_WIDTH));
        }

        @Test
        @DisplayName("each status register is independent, so one dataset cannot leak into another")
        void statusRegistersAreIndependentPerDataset() {
            final Map<FileService.Dd, FakeDataset> bindings = allBindings();
            bindings.get(FileService.Dd.TRNXFILE).openStatus("35");
            final FileService service = serviceOver(bindings);

            service.execute(FileService.FileServiceRequest.of(FileService.Dd.TRNXFILE,
                    FileService.Operation.OPEN));
            final FileService.FileServiceResult otherDataset = service.execute(FileService.FileServiceRequest
                    .raw(FileService.Dd.XREFFILE.ddName(), FileService.Operation.WRITE, "", 0));

            assertThat(otherDataset.returnCode()).isEqualTo(UNSET);
        }
    }

    @Nested
    @DisplayName("3. An unknown DD name reports success with a blank payload - defect B")
    class UnknownDdName {

        @ParameterizedTest(name = "DD [{0}] is not recognised")
        @ValueSource(strings = {"NOSUCHDD", "trnxfile", "        ", "", "TRNXFIL", "ACCTFIL2"})
        @DisplayName("defect B: WHEN OTHER assigns no return code, so the pre-set survives as success")
        void anUnknownDdNameReportsSuccess(final String ddName) {
            final Map<FileService.Dd, FakeDataset> bindings = allBindings();
            final FileService service = serviceOver(bindings);

            final FileService.FileServiceResult result = service.execute(FileService.FileServiceRequest
                    .raw(ddName, FileService.Operation.READ, "", 0));

            assertThat(result.returnCode())
                    .as("MOVE ZERO into PIC X(02) leaves 00, and the subprogram never overwrites it")
                    .isEqualTo(OK);
            assertThat(result.payload()).isEqualTo(" ".repeat(FileService.PAYLOAD_WIDTH));
            assertThat(bindings.values().stream().mapToInt(FakeDataset::totalCalls).sum())
                    .as("no dataset was touched")
                    .isZero();
        }

        @Test
        @DisplayName("the pre-set return code really is what surfaces, whatever it was")
        void thePresetReturnCodeIsWhatSurfaces() {
            final FileService service = serviceOver();

            final FileService.FileServiceResult result = service.execute(new FileService.FileServiceRequest(
                    "NOSUCHDD", FileService.Operation.READ, "", 0, "77",
                    "x".repeat(FileService.PAYLOAD_WIDTH)));

            assertThat(result.returnCode()).isEqualTo("77");
            assertThat(result.payload()).isEqualTo("x".repeat(FileService.PAYLOAD_WIDTH));
        }

        @Test
        @DisplayName("a trailing ninth character is truncated away, so the name still resolves")
        void aNinthCharacterIsTruncatedByThePictureClause() {
            assertThat(FileService.Dd.fromDdName("TRNXFILE ")).contains(FileService.Dd.TRNXFILE);
            assertThat(FileService.Dd.fromDdName("TRNXFILEX")).contains(FileService.Dd.TRNXFILE);
        }

        @ParameterizedTest(name = "{0} resolves from its own name")
        @EnumSource(FileService.Dd.class)
        @DisplayName("every DD resolves from its eight character name, case-sensitively")
        void everyDdResolvesFromItsName(final FileService.Dd dd) {
            assertThat(FileService.Dd.fromDdName(dd.ddName())).contains(dd);
            assertThat(FileService.Dd.fromDdName(dd.ddName().toLowerCase(Locale.ROOT))).isEmpty();
        }

        @Test
        @DisplayName("resolution rejects null rather than treating it as an unknown name")
        void resolutionRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> FileService.Dd.fromDdName(null));
        }
    }

    @Nested
    @DisplayName("4. The key and key-length boundary")
    class KeyBoundary {

        @ParameterizedTest(name = "key length {0} is rejected")
        @ValueSource(ints = {0, -1, -9999, 26, 9999})
        @DisplayName("a length outside 1 to 25 abends instead of relying on undefined reference modification")
        void anOutOfRangeKeyLengthAbends(final int keyLength) {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            final FileService service = serviceOver(binding);
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.keyed(FileService.Dd.CUSTFILE, "123456789", keyLength);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request))
                    .withMessageContaining("CUSTFILE")
                    .withMessageContaining(String.valueOf(keyLength));
            assertThat(binding.totalCalls()).isZero();
        }

        @Test
        @DisplayName("the boundary is inclusive at 1 and at 25")
        void theBoundaryIsInclusive() {
            assertThat(FileService.MINIMUM_KEY_LENGTH).isEqualTo(1);
            assertThat(FileService.KEY_WIDTH).isEqualTo(25);
        }

        @Test
        @DisplayName("a ten digit ACCTFILE key is rejected: FD-ACCT-ID is PIC 9(11)")
        void aTenDigitAccountKeyIsRejected() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.keyed(FileService.Dd.ACCTFILE, "0123456789", 10);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request))
                    .withMessageContaining("ACCTFILE")
                    .withMessageContaining("11");
            assertThat(binding.totalCalls()).isZero();
        }

        @Test
        @DisplayName("a non-digit ACCTFILE key is rejected even at the right width")
        void aNonNumericAccountKeyIsRejected() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.keyed(FileService.Dd.ACCTFILE, "0000000000A", 11);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request))
                    .withMessageContaining("ASCII digits");
            assertThat(binding.totalCalls()).isZero();
        }

        @Test
        @DisplayName("a non-ASCII digit is rejected, unlike Character.isDigit which would accept it")
        void aNonAsciiDigitIsRejected() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);
            final String arabicIndicDigits = "\u0660".repeat(11);
            final FileService.FileServiceRequest request = FileService.FileServiceRequest
                    .keyed(FileService.Dd.ACCTFILE, arabicIndicDigits, 11);

            assertThat(Character.isDigit(arabicIndicDigits.charAt(0))).isTrue();
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request));
        }

        @Test
        @DisplayName("a nine character CUSTFILE key of any characters is accepted: FD-CUST-ID is PIC X(09)")
        void anAlphanumericCustomerKeyIsAccepted() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            binding.stubKeyedRead("ABC-12345",
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'C')));

            final FileService.FileServiceResult result = serviceOver(binding).execute(
                    FileService.FileServiceRequest.keyed(FileService.Dd.CUSTFILE, "ABC-12345", 9));

            assertThat(result.returnCode()).isEqualTo(OK);
        }

        @Test
        @DisplayName("a key length that is in range but not the record key width is rejected")
        void aMismatchedKeyWidthIsRejected() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            final FileService service = serviceOver(binding);
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.keyed(FileService.Dd.CUSTFILE, "12345678901234", 14);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request))
                    .withMessageContaining("record key width");
        }

        @Test
        @DisplayName("only the leading keyLength characters are taken, as reference modification does")
        void onlyTheLeadingCharactersAreTaken() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            binding.stubKeyedRead("123456789",
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'D')));

            serviceOver(binding).execute(FileService.FileServiceRequest
                    .keyed(FileService.Dd.CUSTFILE, "123456789ZZZZZZZZ", 9));

            assertThat(binding.lastKey()).isEqualTo("123456789");
        }

        @Test
        @DisplayName("no abend message ever contains the key value")
        void noAbendMessageContainsTheKeyValue() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);
            final String secretKey = "4111111111111";
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.keyed(FileService.Dd.ACCTFILE, secretKey, 13);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request))
                    .withMessageNotContaining(secretKey);
        }

        @Test
        @DisplayName("a key length is not validated for an open, a close or a sequential read")
        void aKeyLengthIsIgnoredWhereTheSourceIgnoresIt() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.TRNXFILE, 'E')));
            final FileService service = serviceOver(binding);

            assertThat(service.execute(FileService.FileServiceRequest
                    .raw(FileService.Dd.TRNXFILE.ddName(), FileService.Operation.OPEN, "", 0)).returnCode())
                    .isEqualTo(OK);
            assertThat(service.execute(FileService.FileServiceRequest
                    .raw(FileService.Dd.TRNXFILE.ddName(), FileService.Operation.READ, "", -5)).returnCode())
                    .isEqualTo(OK);
        }
    }

    @Nested
    @DisplayName("5. The return-code guards, delegated to FileStatusMapper")
    class ReturnCodeGuards {

        @ParameterizedTest(name = "open accepts {0}")
        @ValueSource(strings = {OK, SECONDARY})
        @DisplayName("open and close accept 00 or 04 - the nine sites at app/cbl/CBSTM03A.CBL:736 onward")
        void openAndCloseAcceptTheSecondaryStatus(final String status) {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.openStatus(status);
            binding.closeStatus(status);
            final FileService service = serviceOver(binding);

            service.open(FileService.Dd.TRNXFILE);
            service.close(FileService.Dd.TRNXFILE);

            assertThat(binding.openCount()).isEqualTo(1);
            assertThat(binding.closeCount()).isEqualTo(1);
        }

        @ParameterizedTest(name = "open rejects {0}")
        @ValueSource(strings = {EOF, NOT_FOUND, "22", "35", "90", "ZZ"})
        @DisplayName("open abends on anything else, end of file included")
        void openAbendsOnAnythingElse(final String status) {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.openStatus(status);
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.open(FileService.Dd.TRNXFILE))
                    .withMessageContaining("TRNXFILE");
        }

        @Test
        @DisplayName("the priming read accepts 04 - app/cbl/CBSTM03A.CBL:748")
        void thePrimingReadAcceptsTheSecondaryStatus() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(SECONDARY, recordFor(FileService.Dd.TRNXFILE, 'F')));

            final String payload = serviceOver(binding)
                    .readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE);

            assertThat(payload).hasSize(FileService.PAYLOAD_WIDTH);
        }

        @Test
        @DisplayName("the get-next read rejects 04 - app/cbl/CBSTM03A.CBL:353 accepts 00 and 10 only")
        void theGetNextReadRejectsTheSecondaryStatus() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(SECONDARY, recordFor(FileService.Dd.XREFFILE, 'G')));
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.readNext(FileService.Dd.XREFFILE));
        }

        @Test
        @DisplayName("10 terminates a sequential read loop without throwing")
        void endOfFileTerminatesTheLoopWithoutThrowing() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.XREFFILE, '1')));
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.XREFFILE, '2')));
            binding.queueSequentialRead(FileService.DatasetRead.withoutRecord(EOF));
            final FileService service = serviceOver(binding);

            final List<String> read = new ArrayList<>();
            for (Optional<String> next = service.readNext(FileService.Dd.XREFFILE); next.isPresent();
                    next = service.readNext(FileService.Dd.XREFFILE)) {
                read.add(next.get());
            }

            assertThat(read).hasSize(2);
            assertThat(binding.readNextCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a keyed read abends on 10, because L379 and L403 carry no WHEN '10' branch")
        void aKeyedReadAbendsOnEndOfFile() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            binding.keyedDefault(FileService.DatasetRead.withoutRecord(EOF));
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.readByKey(FileService.Dd.ACCTFILE, "0".repeat(11), 11))
                    .withMessageContaining("keyed read");
        }

        @Test
        @DisplayName("a keyed read abends on 23, so a missing account stops the run")
        void aKeyedReadAbendsOnRecordNotFound() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            binding.keyedDefault(FileService.DatasetRead.withoutRecord(NOT_FOUND));
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.readByKey(FileService.Dd.CUSTFILE, "000000001", 9));
        }

        @Test
        @DisplayName("a keyed read returns the record directly when the key is present")
        void aKeyedReadReturnsTheRecord() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            binding.stubKeyedRead("000000001",
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'H')));

            final String payload = serviceOver(binding)
                    .readByKey(FileService.Dd.CUSTFILE, "000000001", 9);

            assertThat(payload).hasSize(FileService.PAYLOAD_WIDTH);
            assertThat(payload).startsWith("H");
        }

        @Test
        @DisplayName("a sequential read of a random dataset is refused before any input or output")
        void aSequentialReadOfARandomDatasetIsRefused() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.readNext(FileService.Dd.ACCTFILE))
                    .withMessageContaining("readByKey");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.readAcceptingSecondaryStatus(FileService.Dd.ACCTFILE));
            assertThat(binding.totalCalls()).isZero();
        }

        @Test
        @DisplayName("a keyed read of a sequential dataset is refused before any input or output")
        void aKeyedReadOfASequentialDatasetIsRefused() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.readByKey(FileService.Dd.TRNXFILE, "0".repeat(16), 16))
                    .withMessageContaining("readNext");
            assertThat(binding.totalCalls()).isZero();
        }

        @Test
        @DisplayName("every guarded entry point rejects a null dataset")
        void everyGuardedEntryPointRejectsNull() {
            final FileService service = serviceOver();

            assertThatNullPointerException().isThrownBy(() -> service.open(null));
            assertThatNullPointerException().isThrownBy(() -> service.close(null));
            assertThatNullPointerException().isThrownBy(() -> service.readNext(null));
            assertThatNullPointerException().isThrownBy(() -> service.readAcceptingSecondaryStatus(null));
            assertThatNullPointerException().isThrownBy(() -> service.readByKey(null, "0", 1));
            assertThatNullPointerException().isThrownBy(() -> service.isBound(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.supports(null, FileService.Operation.OPEN));
            assertThatNullPointerException().isThrownBy(() -> service.supports(FileService.Dd.TRNXFILE, null));
            assertThatNullPointerException().isThrownBy(() -> service.execute(null));
        }
    }

    @Nested
    @DisplayName("6. Payload and record geometry")
    class Geometry {

        @Test
        @DisplayName("the payload is always exactly 1000 characters, right padded with spaces")
        void thePayloadIsAlwaysExactlyOneThousandCharacters() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.XREFFILE, 'Z')));

            final FileService.FileServiceResult result =
                    serviceOver(binding).execute(FileService.FileServiceRequest.of(FileService.Dd.XREFFILE,
                            FileService.Operation.READ));

            assertThat(result.payload()).hasSize(1000);
            assertThat(result.payload()).hasSize(FileService.PAYLOAD_WIDTH);
            assertThat(result.payload().substring(50))
                    .as("everything past the 50 byte record is buffer filler")
                    .isEqualTo(" ".repeat(950));
        }

        @ParameterizedTest(name = "{0} round-trips its record width byte-exactly")
        @EnumSource(FileService.Dd.class)
        @DisplayName("all four record widths round-trip byte-exactly - 350, 50, 500, 300")
        void everyRecordWidthRoundTripsByteExactly(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            final String record = recordFor(dd, 'R');
            if (dd.accessMode() == FileService.AccessMode.SEQUENTIAL) {
                binding.queueSequentialRead(FileService.DatasetRead.of(OK, record));
            } else {
                binding.keyedDefault(FileService.DatasetRead.of(OK, record));
            }
            final String key = dd.numericKey() ? "0".repeat(dd.keyWidth()) : "K".repeat(dd.keyWidth());
            final FileService.FileServiceRequest request =
                    dd.accessMode() == FileService.AccessMode.SEQUENTIAL
                            ? FileService.FileServiceRequest.of(dd, FileService.Operation.READ)
                            : FileService.FileServiceRequest.keyed(dd, key, dd.keyWidth());

            final FileService.FileServiceResult result = serviceOver(binding).execute(request);

            assertThat(result.record(dd)).isEqualTo(record).hasSize(dd.recordWidth());
        }

        @Test
        @DisplayName("the declared widths match the FD layouts at app/cbl/CBSTM03B.CBL:58-78")
        void theDeclaredWidthsMatchTheSource() {
            assertThat(FileService.Dd.TRNXFILE.keyWidth()).isEqualTo(32);
            assertThat(FileService.Dd.TRNXFILE.dataWidth()).isEqualTo(318);
            assertThat(FileService.Dd.TRNXFILE.recordWidth()).isEqualTo(350);

            assertThat(FileService.Dd.XREFFILE.keyWidth()).isEqualTo(16);
            assertThat(FileService.Dd.XREFFILE.dataWidth()).isEqualTo(34);
            assertThat(FileService.Dd.XREFFILE.recordWidth()).isEqualTo(50);

            assertThat(FileService.Dd.CUSTFILE.keyWidth()).isEqualTo(9);
            assertThat(FileService.Dd.CUSTFILE.dataWidth()).isEqualTo(491);
            assertThat(FileService.Dd.CUSTFILE.recordWidth()).isEqualTo(500);

            assertThat(FileService.Dd.ACCTFILE.keyWidth()).isEqualTo(11);
            assertThat(FileService.Dd.ACCTFILE.dataWidth()).isEqualTo(289);
            assertThat(FileService.Dd.ACCTFILE.recordWidth()).isEqualTo(300);
        }

        @Test
        @DisplayName("a record longer than the buffer is truncated, as a COBOL move would truncate it")
        void anOverlongRecordIsTruncated() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.queueSequentialRead(FileService.DatasetRead.of(OK, "Q".repeat(1500)));

            final FileService.FileServiceResult result = serviceOver(binding)
                    .execute(FileService.FileServiceRequest.of(FileService.Dd.TRNXFILE,
                            FileService.Operation.READ));

            assertThat(result.payload()).hasSize(FileService.PAYLOAD_WIDTH).isEqualTo("Q".repeat(1000));
        }

        @Test
        @DisplayName("the shared area is 1040 bytes: 8 plus 1 plus 2 plus 25 plus 4 plus 1000")
        void theSharedAreaIsOneThousandAndFortyBytes() {
            assertThat(FileService.DD_NAME_WIDTH
                    + FileService.OPERATION_WIDTH
                    + FileStatus.STATUS_CODE_LENGTH
                    + FileService.KEY_WIDTH
                    + FileService.KEY_LENGTH_FIELD_DIGITS
                    + FileService.PAYLOAD_WIDTH).isEqualTo(FileService.SHARED_AREA_WIDTH);
            assertThat(FileService.SHARED_AREA_WIDTH).isEqualTo(1040);
        }

        @Test
        @DisplayName("the pre-set constants are the values MOVE ZERO and MOVE SPACES produce")
        void thePresetConstantsMatchTheCallerIdiom() {
            assertThat(FileService.PRESET_RETURN_CODE).isEqualTo("00");
            assertThat(FileService.PRESET_PAYLOAD).isEqualTo(" ".repeat(1000));
            assertThat(FileService.UNSET_STATUS).isEqualTo("  ");
        }

        @Test
        @DisplayName("the record accessor rejects a null dataset")
        void theRecordAccessorRejectsNull() {
            final FileService.FileServiceResult result = new FileService.FileServiceResult(OK, "");
            assertThatNullPointerException().isThrownBy(() -> result.record(null));
        }
    }

    @Nested
    @DisplayName("7. The shared-area contract as types")
    class SharedAreaContract {

        @Test
        @DisplayName("all six operation codes are retained, including the two no handler implements")
        void allSixOperationCodesAreRetained() {
            assertThat(FileService.Operation.values()).hasSize(6);
            assertThat(FileService.Operation.OPEN.code()).isEqualTo('O');
            assertThat(FileService.Operation.CLOSE.code()).isEqualTo('C');
            assertThat(FileService.Operation.READ.code()).isEqualTo('R');
            assertThat(FileService.Operation.READ_K.code()).isEqualTo('K');
            assertThat(FileService.Operation.WRITE.code()).isEqualTo('W');
            assertThat(FileService.Operation.REWRITE.code()).isEqualTo('Z');
        }

        @Test
        @DisplayName("the write and the rewrite are implemented by no dataset at all")
        void theWriteAndRewriteAreImplementedByNoDataset() {
            final FileService service = serviceOver();
            for (final FileService.Dd dd : FileService.Dd.values()) {
                assertThat(service.supports(dd, FileService.Operation.WRITE)).isFalse();
                assertThat(service.supports(dd, FileService.Operation.REWRITE)).isFalse();
            }
        }

        @Test
        @DisplayName("the four DD names are exactly eight characters, matching PIC X(08)")
        void theFourDdNamesAreEightCharacters() {
            assertThat(FileService.Dd.values()).hasSize(4);
            for (final FileService.Dd dd : FileService.Dd.values()) {
                assertThat(dd.ddName()).hasSize(FileService.DD_NAME_WIDTH).isEqualTo(dd.name());
            }
        }

        @Test
        @DisplayName("two datasets are sequential and two are random - L33, L39, L45, L51")
        void twoAreSequentialAndTwoAreRandom() {
            assertThat(FileService.Dd.TRNXFILE.accessMode()).isEqualTo(FileService.AccessMode.SEQUENTIAL);
            assertThat(FileService.Dd.XREFFILE.accessMode()).isEqualTo(FileService.AccessMode.SEQUENTIAL);
            assertThat(FileService.Dd.CUSTFILE.accessMode()).isEqualTo(FileService.AccessMode.RANDOM);
            assertThat(FileService.Dd.ACCTFILE.accessMode()).isEqualTo(FileService.AccessMode.RANDOM);
            assertThat(FileService.AccessMode.SEQUENTIAL.readOperation())
                    .isEqualTo(FileService.Operation.READ);
            assertThat(FileService.AccessMode.RANDOM.readOperation())
                    .isEqualTo(FileService.Operation.READ_K);
        }

        @Test
        @DisplayName("only ACCTFILE has a numeric record key - FD-ACCT-ID PIC 9(11) at L77")
        void onlyTheAccountKeyIsNumeric() {
            assertThat(FileService.Dd.ACCTFILE.numericKey()).isTrue();
            assertThat(FileService.Dd.TRNXFILE.numericKey()).isFalse();
            assertThat(FileService.Dd.XREFFILE.numericKey()).isFalse();
            assertThat(FileService.Dd.CUSTFILE.numericKey()).isFalse();
        }

        @Test
        @DisplayName("a request fits every text component to its picture clause")
        void aRequestFitsEveryComponentToItsPictureClause() {
            final FileService.FileServiceRequest request = new FileService.FileServiceRequest(
                    "AB", FileService.Operation.OPEN, "XY", 2, "1", "abc");

            assertThat(request.ddName()).hasSize(FileService.DD_NAME_WIDTH).isEqualTo("AB      ");
            assertThat(request.key()).hasSize(FileService.KEY_WIDTH);
            assertThat(request.presetReturnCode()).hasSize(FileStatus.STATUS_CODE_LENGTH).isEqualTo("1 ");
            assertThat(request.presetPayload()).hasSize(FileService.PAYLOAD_WIDTH).startsWith("abc ");
        }

        @Test
        @DisplayName("a request rejects every null component")
        void aRequestRejectsEveryNullComponent() {
            assertThatNullPointerException().isThrownBy(() -> new FileService.FileServiceRequest(
                    null, FileService.Operation.OPEN, "", 0, OK, ""));
            assertThatNullPointerException().isThrownBy(() -> new FileService.FileServiceRequest(
                    "TRNXFILE", null, "", 0, OK, ""));
            assertThatNullPointerException().isThrownBy(() -> new FileService.FileServiceRequest(
                    "TRNXFILE", FileService.Operation.OPEN, null, 0, OK, ""));
            assertThatNullPointerException().isThrownBy(() -> new FileService.FileServiceRequest(
                    "TRNXFILE", FileService.Operation.OPEN, "", 0, null, ""));
            assertThatNullPointerException().isThrownBy(() -> new FileService.FileServiceRequest(
                    "TRNXFILE", FileService.Operation.OPEN, "", 0, OK, null));
            assertThatNullPointerException().isThrownBy(() -> FileService.FileServiceRequest
                    .of(null, FileService.Operation.OPEN));
            assertThatNullPointerException().isThrownBy(() -> FileService.FileServiceRequest
                    .keyed(null, "", 0));
        }

        @Test
        @DisplayName("a result fits its two components and rejects their nulls")
        void aResultFitsItsComponents() {
            final FileService.FileServiceResult result = new FileService.FileServiceResult("0", "a");

            assertThat(result.returnCode()).hasSize(FileStatus.STATUS_CODE_LENGTH);
            assertThat(result.payload()).hasSize(FileService.PAYLOAD_WIDTH);
            assertThatNullPointerException()
                    .isThrownBy(() -> new FileService.FileServiceResult(null, ""));
            assertThatNullPointerException()
                    .isThrownBy(() -> new FileService.FileServiceResult(OK, null));
        }

        @Test
        @DisplayName("a read result distinguishes a record from its absence")
        void aReadResultDistinguishesARecordFromItsAbsence() {
            final FileService.DatasetRead withRecord = FileService.DatasetRead.of(OK, "data");
            final FileService.DatasetRead withoutRecord = FileService.DatasetRead.withoutRecord(EOF);

            assertThat(withRecord.hasRecord()).isTrue();
            assertThat(withRecord.record()).isEqualTo("data");
            assertThat(withoutRecord.hasRecord()).isFalse();
            assertThat(withoutRecord.record()).isNull();
            assertThat(withoutRecord.status()).isEqualTo(EOF);
            assertThatNullPointerException().isThrownBy(() -> FileService.DatasetRead.of(null, "d"));
            assertThatNullPointerException().isThrownBy(() -> FileService.DatasetRead.of(OK, null));
            assertThatNullPointerException().isThrownBy(() -> FileService.DatasetRead.withoutRecord(null));
        }
    }

    @Nested
    @DisplayName("8. Personal data never reaches a string representation")
    class NoPersonalDataInDiagnostics {

        /**
         * A canary standing in for a card number, kept synthetic so that no fixture here resembles a real
         * credential while still proving the value cannot escape through any representation.
         */
        private static final String CARD_CANARY = "CARD-CANARY-NEVER-RENDER";

        @Test
        @DisplayName("a result discloses its return code and the payload's length, never its content")
        void aResultNeverDisclosesThePayload() {
            final FileService.FileServiceResult result =
                    new FileService.FileServiceResult(OK, CARD_CANARY + "SENSITIVE");

            assertThat(result.toString())
                    .doesNotContain(CARD_CANARY)
                    .doesNotContain("SENSITIVE")
                    .contains("returnCode=00")
                    .contains("redacted 1000 chars");
        }

        @Test
        @DisplayName("a request discloses the key's length, never the key")
        void aRequestNeverDisclosesTheKey() {
            final FileService.FileServiceRequest request = FileService.FileServiceRequest
                    .keyed(FileService.Dd.ACCTFILE, CARD_CANARY, 11);

            assertThat(request.toString())
                    .doesNotContain(CARD_CANARY)
                    .contains("ddName=ACCTFILE")
                    .contains("operation=READ_K")
                    .contains("keyLength=11")
                    .contains("redacted 25 chars")
                    .contains("redacted 1000 chars");
        }

        @Test
        @DisplayName("a read result discloses its status and the record's length, never the record")
        void aReadResultNeverDisclosesTheRecord() {
            assertThat(FileService.DatasetRead.of(OK, CARD_CANARY).toString())
                    .doesNotContain(CARD_CANARY)
                    .contains("status=00")
                    .contains("redacted " + CARD_CANARY.length() + " chars");
            assertThat(FileService.DatasetRead.withoutRecord(EOF).toString())
                    .contains("status=10")
                    .contains("(none)");
        }
    }

    @Nested
    @DisplayName("9. Binding resolution and contract breaches")
    class BindingResolution {

        @Test
        @DisplayName("a context with no bindings still constructs, and reports every DD unbound")
        void aContextWithNoBindingsStillConstructs() {
            final FileService service = serviceOver();

            for (final FileService.Dd dd : FileService.Dd.values()) {
                assertThat(service.isBound(dd)).isFalse();
            }
        }

        @Test
        @DisplayName("driving an unbound DD abends naming it, rather than failing at construction")
        void drivingAnUnboundDdAbends() {
            final FileService service = serviceOver();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.open(FileService.Dd.CUSTFILE))
                    .withMessageContaining("CUSTFILE")
                    .withMessageContaining("no dataset binding");
        }

        @Test
        @DisplayName("a contributed binding is reported bound and is the one dispatched to")
        void aContributedBindingIsDispatchedTo() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            final FileService service = serviceOver(binding);

            assertThat(service.isBound(FileService.Dd.CUSTFILE)).isTrue();
            assertThat(service.isBound(FileService.Dd.ACCTFILE)).isFalse();
            service.open(FileService.Dd.CUSTFILE);
            assertThat(binding.openCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("two bindings for one DD are refused, because dispatch would become order-dependent")
        void duplicateBindingsAreRefused() {
            final List<FileService.Dataset> duplicates = List.of(
                    new FakeDataset(FileService.Dd.CUSTFILE), new FakeDataset(FileService.Dd.CUSTFILE));
            final FileStatusMapper mapper = new FileStatusMapper();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileService(mapper, duplicates))
                    .withMessageContaining("CUSTFILE");
        }

        @Test
        @DisplayName("the constructor rejects a null mapper, a null list, a null element and a null DD")
        void theConstructorRejectsItsNulls() {
            final FileStatusMapper mapper = new FileStatusMapper();
            final List<FileService.Dataset> withNullElement = Arrays.asList((FileService.Dataset) null);
            final List<FileService.Dataset> withNullDd = List.of(new FakeDataset(null));

            assertThatNullPointerException().isThrownBy(() -> new FileService(null, List.of()));
            assertThatNullPointerException().isThrownBy(() -> new FileService(mapper, null));
            assertThatNullPointerException().isThrownBy(() -> new FileService(mapper, withNullElement));
            assertThatNullPointerException().isThrownBy(() -> new FileService(mapper, withNullDd));
        }

        @Test
        @DisplayName("a binding that returns a malformed status abends here, not later at the guard")
        void aMalformedStatusAbendsImmediately() {
            final FakeDataset tooLong = new FakeDataset(FileService.Dd.TRNXFILE);
            tooLong.openStatus("000");
            final FileService longService = serviceOver(tooLong);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> longService.open(FileService.Dd.TRNXFILE))
                    .withMessageContaining("not the required 2 characters");
        }

        @Test
        @DisplayName("a binding that returns no read result at all abends naming the operation")
        void aMissingReadResultAbends() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.returnNullRead();
            final FileService service = serviceOver(binding);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.readNext(FileService.Dd.XREFFILE))
                    .withMessageContaining("returned no result at all");
        }

        @Test
        @DisplayName("every abend names CBSTM03B as the culprit, not its caller")
        void everyAbendNamesTheSubprogramAsCulprit() {
            final FileService service = serviceOver();

            final FatalProcessingException abend =
                    catchAbend(() -> service.open(FileService.Dd.TRNXFILE));

            assertThat(abend.getAbendCulprit()).isEqualTo("CBSTM03B");
            assertThat(abend.getAbendCode()).isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
            assertThat(abend.getAbendReason()).isNotBlank();
            assertThat(abend.getMessage()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("10. The one log statement, and what it must never carry")
    class TheOneLogStatement {

        /**
         * A canary planted in the record image. It is a synthetic marker rather than a realistic identifier
         * precisely so that no test fixture in this repository ever resembles a real credential, while still
         * proving that record content cannot reach a log line.
         */
        private static final String RECORD_CANARY = "PII-CANARY-DO-NOT-LOG";

        /**
         * An eleven digit account key, which is exactly what {@code FD-ACCT-ID PIC 9(11)} accepts and exactly
         * the kind of value that must never be logged.
         */
        private static final String ACCOUNT_KEY = "12345678901";

        /**
         * The service logger, reconfigured to debug so the guarded statement actually runs.
         */
        private Logger serviceLogger;

        /**
         * The level the logger carried before this test changed it.
         */
        private Level originalLevel;

        /**
         * The appender collecting the emitted events.
         */
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            this.serviceLogger = (Logger) LoggerFactory.getLogger(FileService.class);
            this.originalLevel = this.serviceLogger.getLevel();
            this.appender = new ListAppender<>();
            this.appender.start();
            this.serviceLogger.addAppender(this.appender);
            this.serviceLogger.setLevel(Level.DEBUG);
        }

        @AfterEach
        void detachAppender() {
            this.serviceLogger.detachAppender(this.appender);
            this.serviceLogger.setLevel(this.originalLevel);
            this.appender.stop();
        }

        /**
         * Drives one successful keyed read of {@code ACCTFILE} carrying a deliberately sensitive key and
         * record, which is the highest-exposure call this bean can make.
         *
         * @return the single event that call emitted
         */
        private ILoggingEvent readWithSensitiveData() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            binding.stubKeyedRead(ACCOUNT_KEY, FileService.DatasetRead.of(OK,
                    padToRecord(RECORD_CANARY, FileService.Dd.ACCTFILE)));

            serviceOver(binding).readByKey(FileService.Dd.ACCTFILE, ACCOUNT_KEY, 11);

            final List<ILoggingEvent> traced = eventsAt(Level.DEBUG);
            assertThat(traced).as("exactly one per-call log statement exists in this bean").hasSize(1);
            return traced.get(0);
        }

        /**
         * Selects the captured events at one level, so that the constructor's one-time readiness line at info
         * cannot be mistaken for a per-call trace.
         *
         * @param level the level to select
         * @return the matching events in logged order
         */
        private List<ILoggingEvent> eventsAt(final Level level) {
            return appender.list.stream().filter(event -> event.getLevel() == level).toList();
        }

        /**
         * Pads text out to a dataset's record width.
         *
         * @param text the leading content
         * @param dd the dataset whose geometry applies
         * @return text of exactly {@code dd.recordWidth()} characters
         */
        private String padToRecord(final String text, final FileService.Dd dd) {
            return text + " ".repeat(dd.recordWidth() - text.length());
        }

        @Test
        @DisplayName("the call is traced at debug with the DD, the operation code and the return code")
        void theCallIsTracedAtDebug() {
            final ILoggingEvent event = readWithSensitiveData();

            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("dd=ACCTFILE")
                    .contains("operation=K")
                    .contains("returnCode=00");
        }

        @Test
        @DisplayName("clause D: neither the key nor the record reaches the log line or its arguments")
        void neitherTheKeyNorTheRecordIsLogged() {
            final ILoggingEvent event = readWithSensitiveData();

            assertThat(event.getFormattedMessage())
                    .doesNotContain(ACCOUNT_KEY)
                    .doesNotContain(RECORD_CANARY)
                    .doesNotContain("CANARY");
            for (final Object argument : event.getArgumentArray()) {
                assertThat(String.valueOf(argument))
                        .doesNotContain(ACCOUNT_KEY)
                        .doesNotContain(RECORD_CANARY);
            }
        }

        @Test
        @DisplayName("the log line is short: it never carries a thousand character payload")
        void theLogLineNeverCarriesThePayload() {
            final ILoggingEvent event = readWithSensitiveData();

            assertThat(event.getFormattedMessage().length()).isLessThan(120);
        }

        @Test
        @DisplayName("an unimplemented cell is traced too, republishing the stale status - defect A")
        void anUnimplementedCellIsTracedWithItsStaleStatus() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            final FileService service = serviceOver(binding);

            service.execute(FileService.FileServiceRequest
                    .raw(FileService.Dd.CUSTFILE.ddName(), FileService.Operation.WRITE, "", 0));

            assertThat(eventsAt(Level.DEBUG)).hasSize(1);
            assertThat(eventsAt(Level.DEBUG).get(0).getFormattedMessage())
                    .contains("dd=CUSTFILE")
                    .contains("operation=W")
                    .contains("returnCode=" + UNSET);
        }

        @Test
        @DisplayName("an unknown DD is traced with the pre-set success it silently reports - defect B")
        void anUnknownDdIsTracedWithItsFalseSuccess() {
            serviceOver().execute(FileService.FileServiceRequest
                    .raw("NOSUCHDD", FileService.Operation.READ, "", 0));

            assertThat(eventsAt(Level.DEBUG)).hasSize(1);
            assertThat(eventsAt(Level.DEBUG).get(0).getFormattedMessage())
                    .contains("dd=NOSUCHDD")
                    .contains("returnCode=00");
        }

        @Test
        @DisplayName("nothing is logged when debug is off, so the guard really guards")
        void nothingIsLoggedWhenDebugIsOff() {
            serviceLogger.setLevel(Level.INFO);
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.XREFFILE, 'L')));

            serviceOver(binding).readNext(FileService.Dd.XREFFILE);

            assertThat(eventsAt(Level.DEBUG)).isEmpty();
        }

        @Test
        @DisplayName("construction reports how many of the four bindings arrived, and nothing else")
        void constructionReportsTheBindingCount() {
            serviceOver(new FakeDataset(FileService.Dd.CUSTFILE), new FakeDataset(FileService.Dd.ACCTFILE));

            final List<ILoggingEvent> readiness = eventsAt(Level.INFO);
            assertThat(readiness).hasSize(1);
            assertThat(readiness.get(0).getFormattedMessage())
                    .contains("CBSTM03B file service ready")
                    .contains("2 of 4");
        }
    }

    @Nested
    @DisplayName("11. The bean wires in a live Spring container, bindings or not")
    class SpringWiring {

        @Test
        @DisplayName("the context starts with no dataset binding contributed at all")
        void theContextStartsWithNoBindings() {
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(NoBindings.class)) {
                final FileService service = context.getBean(FileService.class);

                for (final FileService.Dd dd : FileService.Dd.values()) {
                    assertThat(service.isBound(dd)).isFalse();
                }
            }
        }

        @Test
        @DisplayName("exactly one constructor: this is what makes a zero-binding context startable")
        void exactlyOneConstructorKeepsAZeroBindingContextStartable() {
            assertThat(FileService.class.getDeclaredConstructors())
                    .as("Spring's ConstructorResolver substitutes an empty collection for an unsatisfied "
                            + "collection argument ONLY when the class has a single constructor. Adding a "
                            + "second one would make every context that has not yet contributed a Dataset "
                            + "bean fail to start, which is exactly the state of the tree while the batch "
                            + "bindings are still being built.")
                    .hasSize(1);
        }

        @Test
        @DisplayName("contributed bindings are injected, the bean is a singleton, and it is not proxied")
        void contributedBindingsAreInjectedIntoASingleton() {
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(TwoBindings.class)) {
                final String[] names = context.getBeanNamesForType(FileService.class);
                final FileService service = context.getBean(FileService.class);

                assertThat(names).hasSize(1);
                assertThat(context.getBean(names[0])).isExactlyInstanceOf(FileService.class);
                assertThat(context.getBean(FileService.class)).isSameAs(service);
                assertThat(service.isBound(FileService.Dd.CUSTFILE)).isTrue();
                assertThat(service.isBound(FileService.Dd.ACCTFILE)).isTrue();
                assertThat(service.isBound(FileService.Dd.TRNXFILE)).isFalse();
            }
        }

        @Test
        @DisplayName("dispatch, defect A and defect B all hold inside the Spring-managed instance")
        void bothDefectsHoldUnderSpring() {
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(TwoBindings.class)) {
                final FileService service = context.getBean(FileService.class);

                assertThat(service.execute(FileService.FileServiceRequest
                        .of(FileService.Dd.ACCTFILE, FileService.Operation.OPEN)).returnCode())
                        .isEqualTo(OK);
                assertThat(service.execute(FileService.FileServiceRequest
                        .raw(FileService.Dd.ACCTFILE.ddName(), FileService.Operation.WRITE, "", 0))
                        .returnCode()).as("defect A republishes the open's status").isEqualTo(OK);

                final FileService.FileServiceResult unknown = service.execute(FileService.FileServiceRequest
                        .raw("NOSUCHDD", FileService.Operation.READ, "", 0));
                assertThat(unknown.returnCode()).as("defect B").isEqualTo(OK);
                assertThat(unknown.payload()).isEqualTo(" ".repeat(FileService.PAYLOAD_WIDTH));
            }
        }
    }

    /**
     * A context that imports the bean under test and its collaborator, contributing no dataset binding, so
     * that Spring's own constructor resolution is exercised rather than a hand-built argument list.
     */
    @Configuration
    @Import({FileStatusMapper.class, FileService.class})
    static class NoBindings {
    }

    /**
     * The same context with two of the four datasets bound, which is the partially-built state the tree is in
     * while the remaining bindings are still being written.
     */
    @Configuration
    @Import({FileStatusMapper.class, FileService.class})
    static class TwoBindings {

        /**
         * Contributes the customer dataset binding.
         *
         * @return a binding for {@code CUSTFILE}
         */
        @Bean
        FileService.Dataset custBinding() {
            return new FakeDataset(FileService.Dd.CUSTFILE);
        }

        /**
         * Contributes the account dataset binding.
         *
         * @return a binding for {@code ACCTFILE}
         */
        @Bean
        FileService.Dataset acctBinding() {
            return new FakeDataset(FileService.Dd.ACCTFILE);
        }
    }

    /**
     * A dataset binding under the test's control: the Java stand-in for one {@code SELECT} of
     * {@code FILE-CONTROL.}, recording what it was asked to do so the tests can prove that an unimplemented
     * cell asks it nothing at all.
     */
    private static final class FakeDataset implements FileService.Dataset {

        /**
         * The DD this binding answers for, deliberately nullable so the constructor guard can be tested.
         */
        private final FileService.Dd dd;

        /**
         * Queued sequential read outcomes, consumed in order.
         */
        private final Deque<FileService.DatasetRead> sequentialReads = new ArrayDeque<>();

        /**
         * Keyed read outcomes by exact key.
         */
        private final Map<String, FileService.DatasetRead> keyedReads = new LinkedHashMap<>();

        /**
         * The status an open reports.
         */
        private String openStatus = OK;

        /**
         * The status a close reports.
         */
        private String closeStatus = OK;

        /**
         * The outcome a keyed read reports for an unstubbed key.
         */
        private FileService.DatasetRead keyedDefault = FileService.DatasetRead.withoutRecord(NOT_FOUND);

        /**
         * Whether a read should breach the contract by returning nothing.
         */
        private boolean nullRead;

        /**
         * How many opens were performed.
         */
        private int openCount;

        /**
         * How many closes were performed.
         */
        private int closeCount;

        /**
         * How many sequential reads were performed.
         */
        private int readNextCount;

        /**
         * How many keyed reads were performed.
         */
        private int readByKeyCount;

        /**
         * The key the most recent keyed read received.
         */
        private String lastKey;

        /**
         * Creates a binding for one DD.
         *
         * @param dd the DD to answer for, or {@code null} to exercise the constructor guard
         */
        FakeDataset(final FileService.Dd dd) {
            this.dd = dd;
        }

        @Override
        public FileService.Dd dd() {
            return dd;
        }

        @Override
        public String openInput() {
            openCount++;
            return openStatus;
        }

        @Override
        public String close() {
            closeCount++;
            return closeStatus;
        }

        @Override
        public FileService.DatasetRead readNext() {
            readNextCount++;
            if (nullRead) {
                return null;
            }
            return sequentialReads.isEmpty()
                    ? FileService.DatasetRead.withoutRecord(EOF)
                    : sequentialReads.removeFirst();
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            readByKeyCount++;
            lastKey = recordKey;
            if (nullRead) {
                return null;
            }
            return keyedReads.getOrDefault(recordKey, keyedDefault);
        }

        /**
         * Sets the status an open reports.
         *
         * @param status the status to report
         */
        void openStatus(final String status) {
            this.openStatus = status;
        }

        /**
         * Sets the status a close reports.
         *
         * @param status the status to report
         */
        void closeStatus(final String status) {
            this.closeStatus = status;
        }

        /**
         * Queues one sequential read outcome.
         *
         * @param read the outcome to queue
         */
        void queueSequentialRead(final FileService.DatasetRead read) {
            sequentialReads.addLast(read);
        }

        /**
         * Stubs a keyed read outcome for an exact key.
         *
         * @param key the key to match
         * @param read the outcome to report
         */
        void stubKeyedRead(final String key, final FileService.DatasetRead read) {
            keyedReads.put(key, read);
        }

        /**
         * Sets the outcome an unstubbed keyed read reports.
         *
         * @param read the outcome to report
         */
        void keyedDefault(final FileService.DatasetRead read) {
            this.keyedDefault = read;
        }

        /**
         * Makes both read forms breach the contract by returning nothing.
         */
        void returnNullRead() {
            this.nullRead = true;
        }

        /**
         * Returns how many opens were performed.
         *
         * @return the open count
         */
        int openCount() {
            return openCount;
        }

        /**
         * Returns how many closes were performed.
         *
         * @return the close count
         */
        int closeCount() {
            return closeCount;
        }

        /**
         * Returns how many sequential reads were performed.
         *
         * @return the sequential read count
         */
        int readNextCount() {
            return readNextCount;
        }

        /**
         * Returns how many keyed reads were performed.
         *
         * @return the keyed read count
         */
        int readByKeyCount() {
            return readByKeyCount;
        }

        /**
         * Returns the key the most recent keyed read received.
         *
         * @return the last key, or {@code null} when no keyed read has happened
         */
        String lastKey() {
            return lastKey;
        }

        /**
         * Returns how many operations of any kind were performed, which must be zero for every cell the
         * source leaves unimplemented.
         *
         * @return the total operation count
         */
        int totalCalls() {
            return openCount + closeCount + readNextCount + readByKeyCount;
        }
    }
}
