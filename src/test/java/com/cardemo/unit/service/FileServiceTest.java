/*
 * ******************************************************************
 * Program     : FileServiceTest.java
 * Component   : FileServiceTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test (Surefire tier, pure JVM)
 * Function    : Verifies the third collapse rule: the whole of the
 *               CBSTM03B call contract as one DD-name-keyed bean.
 *               Covers the twelve implemented cells of the four-file
 *               by six-operation matrix, the twelve that are
 *               structurally unreachable, both latent defects the
 *               source carries, the key and key-length boundary, the
 *               fourteen paragraph labels as fourteen methods, and
 *               the fact that neither a payload nor a key can reach a
 *               string representation.
 * Source      : app/cbl/CBSTM03B.CBL:30      (FILE-CONTROL.)
 *               app/cbl/CBSTM03B.CBL:31-53   (4 SELECT: 2 SEQ, 2 RANDOM)
 *               app/cbl/CBSTM03B.CBL:58-78   (the four FD record layouts)
 *               app/cbl/CBSTM03B.CBL:100-112 (LK-M03B-AREA, 1040 bytes)
 *               app/cbl/CBSTM03B.CBL:114     (PROCEDURE DIVISION USING)
 *               app/cbl/CBSTM03B.CBL:116-131 (0000-START, 9999-GOBACK)
 *               app/cbl/CBSTM03B.CBL:133-229 (the four handlers)
 *               app/cbl/CBSTM03A.CBL:71-83   (caller-side shared area)
 *               app/cbl/CBSTM03A.CBL:347-364 (the caller's read idiom)
 *               app/cbl/CBSTM03A.CBL:736     (open: 00 OR 04)
 *               app/cbl/CBSTM03A.CBL:379     (keyed get: 00 only)
 *               app/cpy/COSTM01.CPY          (32-byte TRNX-KEY) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link FileService}, the Java replacement for the
 * {@code CALL 'CBSTM03B' USING WS-M03B-AREA} file-access subprogram contract.
 *
 * <h2>What it does</h2>
 *
 * <p>Asserts the whole of the {@code CBSTM03B} boundary as behaviour rather than as prose. Every assertion is
 * anchored to a locator in {@code app/cbl/CBSTM03B.CBL} or its sole caller {@code app/cbl/CBSTM03A.CBL}, both
 * read case-sensitively with the carriage return stripped. The two latent defects the source carries are
 * asserted as behaviour precisely because a later well-meaning change that "fixed" either one would break
 * parity, and only a test can catch that.
 *
 * <p>The verified locators this class pins, all at commit {@code 7756d89}:
 *
 * <ul>
 *   <li>{@code CBSTM03B.CBL:L30} - {@code FILE-CONTROL.}, which maps to the four dataset bindings rather than
 *       to a method, and is the fifteenth Area-A label that reconciles 15 against 14.</li>
 *   <li>{@code CBSTM03B.CBL:L31-L53} - the four {@code SELECT} clauses: two {@code SEQUENTIAL} and two
 *       {@code RANDOM}, the asymmetry that decides which read form each dataset implements.</li>
 *   <li>{@code CBSTM03B.CBL:L58-L78} - the four {@code FD} layouts, widths 350, 50, 500 and 300.</li>
 *   <li>{@code CBSTM03B.CBL:L100-L112} - {@code LK-M03B-AREA}, 8 + 1 + 2 + 25 + 4 + 1000 = 1040 bytes.</li>
 *   <li>{@code CBSTM03B.CBL:L114} - {@code PROCEDURE DIVISION USING LK-M03B-AREA}, exactly one parameter.</li>
 *   <li>{@code CBSTM03B.CBL:L116} and {@code L118} - {@code 0000-START.} and its {@code EVALUATE
 *       LK-M03B-DD}.</li>
 *   <li>{@code CBSTM03B.CBL:L127-L128} and {@code L130-L131} - {@code WHEN OTHER GO TO 9999-GOBACK}, and the
 *       bare {@code GOBACK.} that assigns no return code. Defect B.</li>
 *   <li>{@code CBSTM03B.CBL:L133}, {@code L141}, {@code L147}, {@code L152} - {@code TRNXFILE}: open, plain
 *       read, close, then the status epilogue.</li>
 *   <li>{@code CBSTM03B.CBL:L157}, {@code L165}, {@code L171}, {@code L176} - {@code XREFFILE}, same
 *       shape.</li>
 *   <li>{@code CBSTM03B.CBL:L181}, {@code L189-L190}, {@code L196}, {@code L200-L203} - {@code CUSTFILE}:
 *       open, keyed read by reference modification, close, epilogue and terminator.</li>
 *   <li>{@code CBSTM03B.CBL:L206}, {@code L214-L215}, {@code L221}, {@code L225-L228} - {@code ACCTFILE}, same
 *       shape, but on a numeric record key.</li>
 *   </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is bound to <strong>Surefire</strong>, not Failsafe: the root {@code pom.xml} includes
 * {@code **}{@code /*Test.java} and excludes only the {@code integration} and {@code e2e} trees, so a class
 * placed anywhere outside {@code src/test/java/com/cardemo/unit/**} would match neither plugin's include set
 * and would silently never run - a green build with the class recorded as uncovered. Run it with:
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test -Dtest=FileServiceTest} for this class alone.</li>
 *   <li>{@code ./mvnw -B -ntp test} for the whole unit tier.</li>
 *   <li>{@code ./mvnw -B -ntp clean verify} for the gated build, which adds the
 *       JaCoCo line-coverage floor. The skip property is hyphenated, not dotted.</li>
 *   </ul>
 *
 * <p>Test compilation runs under {@code -Xlint:all -Werror} at {@code release 25}, so any warning
 * {@code javac} emits - a raw type, an unchecked cast, a dangling documentation comment - is a build
 * failure rather than a warning. Two things are <strong>not</strong> covered by it: an unused import, for
 * which {@code javac} 25 publishes no lint key, and a doclint complaint, because no Javadoc plugin is bound
 * in {@code pom.xml}. Both are enforced separately, by review and by the explicit doclint command.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure JVM.</strong> No container, no Spring context, no database, no network. The bean is
 *       constructed directly, which is also the fastest way to prove the zero-binding case.</li>
 *   <li><strong>Mockito strict stubs</strong> ({@link org.mockito.quality.Strictness#STRICT_STUBS}) for the
 *       {@link FileStatusMapper} collaborator wherever delegation is the thing being proved, so an unused
 *       stubbing fails the test instead of passing silently. Everything else uses the recording
 *       {@code FakeDataset}, because proving that an unimplemented cell performs <em>no</em> input or output
 *       needs a counter, not a mock.</li>
 *   <li><strong>No clock and no fixture loader.</strong> Neither is imported: nothing here reads the wall
 *       clock, the default locale or the default zone, and no test resource is loaded. Every value is a
 *       literal, so the suite is order-independent and machine-independent.</li>
 *   <li><strong>No personal data.</strong> Keys and records are obviously synthetic - repeated single
 *       characters and sequential digits - never a plausible social security number, card number or date of
 *       birth. The payload buffer and the key are never placed in an assertion message.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A lowercase glob drops the source.</strong> {@code CBSTM03B.CBL} and {@code CBSTM03A.CBL} use
 *       an uppercase extension, so {@code app/cbl/*.cbl} silently matches neither and the contract appears to
 *       have no source at all. Match {@code app/cbl/**} case-insensitively.</li>
 *   <li><strong>Not stripping the carriage return drifts every citation.</strong> Both files are CRLF. Count
 *       lines through {@code tr -d '\r'} or every locator above moves.</li>
 *   <li><strong>Four labels sit three lines earlier than a reader may expect.</strong> The
 *       {@code PROCEDURE DIVISION}
 *       header is at L114 not L117, {@code 0000-START.} at L116 not L119, the {@code EVALUATE} at L118 not
 *       L121, and {@code GO TO 9999-GOBACK.} at L128 not L131. The other eleven labels match exactly, so this
 *       is not a systematic offset and nothing should be shifted wholesale.</li>
 *   <li><strong>Collapsing the epilogue and terminator pairs loses defect A.</strong> {@code 1900-EXIT.} is
 *       not a terminator: its body moves the file status into the return code, and it is reached whether or
 *       not any input or output happened. {@code 1999-EXIT.} is the terminator. The same split recurs at
 *       2900/2999, 3900/3999 and 4900/4999, giving eight distinct methods, not four.</li>
 *   <li><strong>Assuming a 24-cell matrix.</strong> Six operations are declared but only three are implemented
 *       per dataset, and the sets differ: the write and the rewrite exist nowhere, the plain read is absent
 *       from the two random datasets, and the keyed read is absent from the two sequential ones. Twelve cells
 *       are reachable, twelve are not.</li>
 *   <li><strong>Expecting an abend from the subprogram itself.</strong> {@code CBSTM03B} declares no
 *       {@code ABCODE} and calls no abend service, so it is outside the abend-999 contract. It reports a
 *       status and returns; interpreting that status is the caller's job.</li>
 *   </ul>
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

    /**
     * INTENTIONAL PRESERVATION of a source defect - not a bug in this code. Defect A is the silent stale-status
     * fall-through: for a valid DD with an unimplemented operation none of the handler's three guarded blocks
     * fires, so control reaches the epilogue, which unconditionally moves the file's status into the return code
     * ({@code app/cbl/CBSTM03B.CBL:L152}, {@code L176}, {@code L201}, {@code L226}). No input or output
     * happened, so the caller receives the file's stale prior status and no error is signalled. It is reproduced
     * rather than repaired because behavioural parity is the contract, and it is cited to the source lines
     * above so that a later change which "fixes" it fails these tests rather than passing quietly.
     */
    @Nested
    @DisplayName("2. The twelve unimplemented cells perform no input or output - defect A")
    class UnimplementedCells {

        /**
         * The pairs no handler in {@code app/cbl/CBSTM03B.CBL} implements: the write and the rewrite for every
         * dataset, plus the read form each dataset's access mode excludes.
         *
         * <p><strong>Count reconciliation: twelve, not eight.</strong> {@code M03B-WRITE} and
         * {@code M03B-REWRITE} are referenced by no
         * handler at all, which is eight cells, and additionally {@code M03B-READ} is absent from
         * {@code CUSTFILE} and {@code ACCTFILE} while {@code M03B-READ-K} is absent from {@code TRNXFILE} and
         * {@code XREFFILE}, which is four more. <strong>Twelve is the arithmetic the handler bodies support</strong>,
         * and twenty-four less the twelve implemented cells is twelve, so all twelve are asserted here.
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

            serviceOver(binding).executeInLegacyParityMode(
                    FileService.FileServiceRequest.raw(dd.ddName(), operation, "", 0));

            assertThat(binding.totalCalls()).isZero();
        }

        @ParameterizedTest(name = "{0} with {1} publishes the unset register")
        @MethodSource("unimplementedCells")
        @DisplayName("on a fresh service the epilogue publishes the unset status, not a success")
        void anUnimplementedCellPublishesTheUnsetRegister(final FileService.Dd dd,
                final FileService.Operation operation) {
            final FakeDataset binding = new FakeDataset(dd);

            final FileService.FileServiceResult result = serviceOver(binding)
                    .executeInLegacyParityMode(FileService.FileServiceRequest.raw(dd.ddName(), operation, "", 0));

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
                    .executeInLegacyParityMode(
                            FileService.FileServiceRequest.of(dd, FileService.Operation.OPEN))
                    .returnCode();
            final FileService.FileServiceResult stale = service.executeInLegacyParityMode(FileService.FileServiceRequest
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
                    .executeInLegacyParityMode(FileService.FileServiceRequest.raw(FileService.Dd.CUSTFILE.ddName(),
                            FileService.Operation.READ, "", 0));

            assertThat(result.payload()).isEqualTo(" ".repeat(FileService.PAYLOAD_WIDTH));
        }

        @Test
        @DisplayName("each status register is independent, so one dataset cannot leak into another")
        void statusRegistersAreIndependentPerDataset() {
            final Map<FileService.Dd, FakeDataset> bindings = allBindings();
            bindings.get(FileService.Dd.TRNXFILE).openStatus("35");
            final FileService service = serviceOver(bindings);

            service.executeInLegacyParityMode(FileService.FileServiceRequest.of(FileService.Dd.TRNXFILE,
                    FileService.Operation.OPEN));
            final FileService.FileServiceResult otherDataset = service.executeInLegacyParityMode(
                    FileService.FileServiceRequest
                            .raw(FileService.Dd.XREFFILE.ddName(), FileService.Operation.WRITE, "", 0));

            assertThat(otherDataset.returnCode()).isEqualTo(UNSET);
        }
    }

    /**
     * The fail-closed contract of the default public adapter, and the reason it is not a deviation.
     *
     * <p>{@code FileService.execute} refuses the two cells the source leaves unimplemented, while
     * {@code executeInLegacyParityMode} reproduces the source's behaviour byte for byte. Both halves are
     * asserted here, side by side, because the value of the split is the contrast: the same request answers
     * one way through the safe adapter and the other way through the parity mode.
     *
     * <p><strong>Why refusing is faithful rather than a repair.</strong> In the corpus both paths are
     * unreachable: the single caller {@code app/cbl/CBSTM03A.CBL} sets DD names from its own literals and
     * pairs each with an operation that dataset implements, so no live execution selects an unknown name or an
     * unimplemented cell. In Java {@code FileServiceRequest} takes an arbitrary DD-name string, so the same
     * paths ARE reachable - reproducing them on the default adapter would manufacture a live fail-open path
     * the source never had, and would contradict the invariant that a file status becomes a typed exception on
     * every I/O path and is never swallowed.
     */
    @Nested
    @DisplayName("2b. The default adapter fails closed on both defects, and says so")
    class FailClosedDefaultAdapter {

        @ParameterizedTest(name = "an unknown DD [{0}] is refused rather than answered")
        @ValueSource(strings = {"NOSUCHDD", "trnxfile", "        ", "", "TRNXFIL", "ACCTFIL2"})
        @DisplayName("defect B is refused: an unknown DD name abends instead of reporting the pre-set")
        void unknownDdIsRefused(final String ddName) {
            final FileService service = serviceOver(allBindings());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(
                            FileService.FileServiceRequest.raw(ddName, FileService.Operation.READ, "", 0)))
                    .withMessageContaining("is not one of")
                    .withMessageContaining("TRNXFILE")
                    .satisfies(abend -> assertThat(abend.getAbendCulprit()).isEqualTo("CBSTM03B"));
        }

        @Test
        @DisplayName("defect A is refused: an unimplemented operation abends instead of republishing a stale "
                + "status")
        void unimplementedCellIsRefused() {
            final FileService service = serviceOver(allBindings());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(FileService.FileServiceRequest
                            .raw("ACCTFILE", FileService.Operation.WRITE, "", 0)))
                    .withMessageContaining("does not implement operation")
                    .withMessageContaining("ACCTFILE")
                    .satisfies(abend -> assertThat(abend.getAbendCulprit()).isEqualTo("CBSTM03B"));
        }

        @Test
        @DisplayName("every one of the twelve unimplemented cells is refused, not just the write")
        void allTwelveUnimplementedCellsAreRefused() {
            final FileService service = serviceOver(allBindings());
            int refused = 0;

            for (final FileService.Dd dd : FileService.Dd.values()) {
                for (final FileService.Operation operation : FileService.Operation.values()) {
                    if (service.supports(dd, operation)) {
                        continue;
                    }
                    refused++;
                    assertThatExceptionOfType(FatalProcessingException.class)
                            .as("%s with '%s' must be refused", dd.ddName(), operation.code())
                            .isThrownBy(() -> service.execute(FileService.FileServiceRequest
                                    .raw(dd.ddName(), operation, "", 0)));
                }
            }

            assertThat(refused)
                    .as("four datasets by six operations is twenty-four cells, twelve implemented")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the refusal message points the caller at the parity mode rather than at nothing")
        void refusalNamesTheAlternative() {
            final FileService service = serviceOver(allBindings());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(
                            FileService.FileServiceRequest.raw("NOSUCHDD", FileService.Operation.READ, "", 0)))
                    .withMessageContaining("executeInLegacyParityMode");
        }

        @Test
        @DisplayName("an unknown DD name is control-encoded before it enters the abend message")
        void refusalEncodesTheUntrustedName() {
            final FileService service = serviceOver(allBindings());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(FileService.FileServiceRequest
                            .raw("A\r\nBOGUS", FileService.Operation.READ, "", 0)))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .as("a DD name carrying CR/LF must not be able to forge a second log record")
                            .doesNotContain("\r")
                            .doesNotContain("\n")
                            .contains("\\u000d"));
        }

        @Test
        @DisplayName("the same request answers differently through the parity mode, which is the whole point")
        void parityModeStillReproducesTheDefect() {
            final FileService service = serviceOver(allBindings());
            final FileService.FileServiceRequest request =
                    FileService.FileServiceRequest.raw("NOSUCHDD", FileService.Operation.READ, "", 0);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.execute(request));
            assertThat(service.executeInLegacyParityMode(request).returnCode())
                    .as("MOVE ZERO into PIC X(02) leaves 00, and the subprogram never overwrites it")
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("a dispatchable cell is unaffected: the safe adapter still reports the status")
        void dispatchableCellIsUnaffected() {
            final Map<FileService.Dd, FakeDataset> bindings = allBindings();
            final FileService service = serviceOver(bindings);

            assertThat(service.execute(FileService.FileServiceRequest
                    .raw("ACCTFILE", FileService.Operation.OPEN, "", 0)).returnCode())
                    .as("refusing the unimplemented cells must not change any implemented one")
                    .isEqualTo("00");
        }
    }

    /**
     * INTENTIONAL PRESERVATION of a source defect - not a bug in this code. Defect B is the unknown-DD success
     * report: {@code WHEN OTHER GO TO 9999-GOBACK.} ({@code app/cbl/CBSTM03B.CBL:L127-L128}) reaches a paragraph
     * whose whole body is a bare {@code GOBACK.} ({@code L130-L131}). It performs no input or output and never
     * assigns the return code, so because every caller pre-sets it to zero
     * ({@code app/cbl/CBSTM03A.CBL:L349}) the caller observes success with an all-spaces payload. Reproduced
     * rather than repaired for parity, and asserted here so that repairing it cannot pass unnoticed. Note the
     * scope limit: {@code CBSTM03B} declares no {@code ABCODE}, so this sits outside the abend-999 contract and the
     * defect must not be generalised into one.
     */
    @Nested
    @DisplayName("3. An unknown DD name reports success with a blank payload - defect B")
    class UnknownDdName {

        @ParameterizedTest(name = "DD [{0}] is not recognised")
        @ValueSource(strings = {"NOSUCHDD", "trnxfile", "        ", "", "TRNXFIL", "ACCTFIL2"})
        @DisplayName("defect B: WHEN OTHER assigns no return code, so the pre-set survives as success")
        void anUnknownDdNameReportsSuccess(final String ddName) {
            final Map<FileService.Dd, FakeDataset> bindings = allBindings();
            final FileService service = serviceOver(bindings);

            final FileService.FileServiceResult result = service.executeInLegacyParityMode(
                    FileService.FileServiceRequest.raw(ddName, FileService.Operation.READ, "", 0));

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

            final FileService.FileServiceResult result =
                    service.executeInLegacyParityMode(new FileService.FileServiceRequest(
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
        @DisplayName("a blanket 25-byte key selects a different record from the length-aware key")
        void aBlanketTwentyFiveByteKeySelectsADifferentRecord() {
            final String searchField = "123456789";
            final FakeDataset lengthAware = new FakeDataset(FileService.Dd.CUSTFILE);
            lengthAware.stubKeyedRead(searchField,
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'W')));

            serviceOver(lengthAware).execute(FileService.FileServiceRequest
                    .keyed(FileService.Dd.CUSTFILE, searchField, searchField.length()));

            assertThat(lengthAware.lastKey())
                    .as("COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID at app/cbl/CBSTM03A.CBL:L374 yields "
                            + "9, so MOVE LK-M03B-KEY (1:9) at app/cbl/CBSTM03B.CBL:L189 selects exactly the "
                            + "nine character customer identifier")
                    .isEqualTo(searchField)
                    .hasSize(FileService.Dd.CUSTFILE.keyWidth());

            final FakeDataset blanket = new FakeDataset(FileService.Dd.CUSTFILE);
            final FileService blanketService = serviceOver(blanket);
            final FileService.FileServiceRequest wholeField = FileService.FileServiceRequest
                    .keyed(FileService.Dd.CUSTFILE, searchField, FileService.KEY_WIDTH);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("taking the whole 25 byte field instead of the computed length hands the dataset a "
                            + "25 character key where its record key is 9. On the mainframe that silently "
                            + "reads the wrong record; here it is refused outright, which is the only "
                            + "difference and a deliberate one.")
                    .isThrownBy(() -> blanketService.execute(wholeField))
                    .withMessageContaining("record key width");

            assertThat(blanket.totalCalls())
                    .as("and the refusal happens before any input or output, so no wrong record is ever read")
                    .isZero();
            assertThat(blanket.lastKey())
                    .as("the blanket key therefore selects no record at all, where the length-aware key "
                            + "selected one")
                    .isNull();
        }

        @Test
        @DisplayName("no abend message ever contains the key value")
        void noAbendMessageContainsTheKeyValue() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(binding);
            final String secretKey = "9999900000777";
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

        /**
         * INTENTIONAL RETENTION - not dead code. All six codes declared at
         * {@code app/cbl/CBSTM03B.CBL:L103-L108} are kept even though only twelve of the twenty-four cells are
         * reachable and no handler implements {@code 'W'} or {@code 'Z'} at all. The declared contract is what
         * the caller writes into {@code LK-M03B-OPER}, so narrowing the enumeration would narrow the boundary
         * below its source. The retention is cited to the locator above and pinned by the assertion below.
         */
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

        /**
         * INTENTIONAL RETENTION - not dead code. Proves the gap is total rather than per-dataset. The root
         * cause is that every {@code OPEN} in the subprogram is an {@code OPEN INPUT}
         * ({@code app/cbl/CBSTM03B.CBL:L136}, {@code L160}, {@code L184}, {@code L209}), so the subprogram is
         * strictly read-only and no write path exists to implement. The two codes are kept declared rather than
         * deleted for parity with {@code L107-L108}. Exposing a write capability this boundary never had would
         * widen the contract beyond the source, which is what this assertion prevents.
         */
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

            service.executeInLegacyParityMode(FileService.FileServiceRequest
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
            serviceOver().executeInLegacyParityMode(FileService.FileServiceRequest
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
    @DisplayName("11. Injectability, asserted without standing up a container")
    class Injectability {

        @Test
        @DisplayName("exactly one constructor, which is what makes a zero-binding context startable")
        void exactlyOneConstructorKeepsAZeroBindingContextStartable() {
            assertThat(FileService.class.getDeclaredConstructors())
                    .as("a framework substitutes an empty collection for an unsatisfied collection argument "
                            + "ONLY when the class has a single constructor. Adding a second one would make "
                            + "every context that has not yet contributed a Dataset binding fail to start. "
                            + "Asserted by reflection rather than by starting a container, because this tier "
                            + "is pure JVM.")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the sole constructor takes the mapper and a collection, so bindings can arrive empty")
        void theSoleConstructorTakesTheMapperAndACollection() {
            final Class<?>[] parameters = FileService.class.getDeclaredConstructors()[0].getParameterTypes();

            assertThat(parameters)
                    .as("constructor injection only: no setter, no field injection, no static mutable state")
                    .containsExactly(FileStatusMapper.class, List.class);
        }

        @Test
        @DisplayName("a service built with no binding at all constructs and reports every DD unbound")
        void aServiceWithNoBindingConstructsAndReportsEveryDdUnbound() {
            final FileService service = new FileService(new FileStatusMapper(), List.of());

            for (final FileService.Dd dd : FileService.Dd.values()) {
                assertThat(service.isBound(dd))
                        .as("%s must report unbound rather than provoking a failure at construction",
                                dd.ddName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a partially bound service dispatches to what it has and reports the rest unbound")
        void aPartiallyBoundServiceDispatchesToWhatItHas() {
            final FakeDataset cust = new FakeDataset(FileService.Dd.CUSTFILE);
            final FakeDataset acct = new FakeDataset(FileService.Dd.ACCTFILE);
            final FileService service = serviceOver(cust, acct);

            assertThat(service.isBound(FileService.Dd.CUSTFILE)).isTrue();
            assertThat(service.isBound(FileService.Dd.ACCTFILE)).isTrue();
            assertThat(service.isBound(FileService.Dd.TRNXFILE))
                    .as("the partially-built state is legitimate, not an error")
                    .isFalse();

            service.open(FileService.Dd.CUSTFILE);

            assertThat(cust.openCount()).isOne();
            assertThat(acct.totalCalls()).isZero();
        }

        @Test
        @DisplayName("both defects survive on an instance holding only some of the four bindings")
        void bothDefectsHoldOnAPartiallyBoundInstance() {
            final FileService service = serviceOver(new FakeDataset(FileService.Dd.ACCTFILE));

            assertThat(service.executeInLegacyParityMode(FileService.FileServiceRequest
                    .of(FileService.Dd.ACCTFILE, FileService.Operation.OPEN)).returnCode())
                    .isEqualTo(OK);
            assertThat(service.executeInLegacyParityMode(FileService.FileServiceRequest
                    .raw(FileService.Dd.ACCTFILE.ddName(), FileService.Operation.WRITE, "", 0))
                    .returnCode()).as("defect A republishes the open's status").isEqualTo(OK);

            final FileService.FileServiceResult unknown = service.executeInLegacyParityMode(
                    FileService.FileServiceRequest.raw("NOSUCHDD", FileService.Operation.READ, "", 0));

            assertThat(unknown.returnCode()).as("defect B").isEqualTo(OK);
            assertThat(unknown.payload()).isEqualTo(" ".repeat(FileService.PAYLOAD_WIDTH));
        }
    }

    @Nested
    @DisplayName("12. The fourteen PROCEDURE DIVISION labels as fourteen methods")
    class ParagraphMap {

        /**
         * The fourteen {@code PROCEDURE DIVISION} paragraph labels of {@code app/cbl/CBSTM03B.CBL}, paired
         * with the private method each one maps to, in source order.
         *
         * <p>The line numbers are the verified ones, produced by scanning Area A with the carriage return
         * stripped. Five of the specification's locators are three too high; these are not.
         *
         * @return one argument triple per paragraph: label, verified line, Java method name
         */
        static Stream<Arguments> paragraphs() {
            return Stream.of(
                    Arguments.of("0000-START", 116, "dispatch"),
                    Arguments.of("9999-GOBACK", 130, "goback"),
                    Arguments.of("1000-TRNXFILE-PROC", 133, "trnxfileProc"),
                    Arguments.of("1900-EXIT", 151, "trnxfileStatusEpilogue"),
                    Arguments.of("1999-EXIT", 154, "trnxfileTerminator"),
                    Arguments.of("2000-XREFFILE-PROC", 157, "xreffileProc"),
                    Arguments.of("2900-EXIT", 175, "xreffileStatusEpilogue"),
                    Arguments.of("2999-EXIT", 178, "xreffileTerminator"),
                    Arguments.of("3000-CUSTFILE-PROC", 181, "custfileProc"),
                    Arguments.of("3900-EXIT", 200, "custfileStatusEpilogue"),
                    Arguments.of("3999-EXIT", 203, "custfileTerminator"),
                    Arguments.of("4000-ACCTFILE-PROC", 206, "acctfileProc"),
                    Arguments.of("4900-EXIT", 225, "acctfileStatusEpilogue"),
                    Arguments.of("4999-EXIT", 228, "acctfileTerminator"));
        }

        /**
         * The declared method names of the class under test, resolved once per test.
         *
         * @return every declared method name, including the private ones
         */
        private static List<String> declaredMethodNames() {
            return Arrays.stream(FileService.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
        }

        @ParameterizedTest(name = "{0} at L{1} maps to {2}()")
        @MethodSource("paragraphs")
        @DisplayName("every one of the fourteen labels has its own private method")
        void everyParagraphHasItsOwnMethod(final String label, final int line, final String method) {
            assertThat(declaredMethodNames())
                    .as("paragraph %s at app/cbl/CBSTM03B.CBL:L%d must map one-to-one onto %s(), because the "
                            + "coverage gate is provable only if the correspondence is mechanical", label,
                            line, method)
                    .contains(method);
        }

        @Test
        @DisplayName("the label set is exactly fourteen, which is what reconciles 15 Area-A labels against 14")
        void theLabelSetIsExactlyFourteen() {
            assertThat(paragraphs().toList())
                    .as("the Area-A scan of app/cbl/CBSTM03B.CBL yields 15 labels: FILE-CONTROL. at L30, "
                            + "which is ENVIRONMENT DIVISION and maps to the four dataset bindings rather "
                            + "than to a method, plus these 14 PROCEDURE DIVISION labels. The reconciliation "
                            + "is resolved, not unavailable.")
                    .hasSize(14);
        }

        @Test
        @DisplayName("the fourteen verified line numbers are the ones the Area-A scan produces")
        void theFourteenVerifiedLineNumbersAreTheScannedOnes() {
            final List<Integer> lines = paragraphs()
                    .map(arguments -> (Integer) arguments.get()[1])
                    .toList();

            assertThat(lines)
                    .as("tr -d '\\r' < app/cbl/CBSTM03B.CBL | grep -nE '^ {7}[A-Z0-9][A-Z0-9-]*\\.[ ]*$' "
                            + "yields 30 for FILE-CONTROL. and then exactly these fourteen. Without the "
                            + "carriage return stripped every one of them drifts.")
                    .containsExactly(116, 130, 133, 151, 154, 157, 175, 178, 181, 200, 203, 206, 225, 228)
                    .isSorted();
        }

        @Test
        @DisplayName("the epilogue and the terminator are eight distinct methods, never four")
        void theEpilogueAndTerminatorPairsAreEightDistinctMethods() {
            // INTENTIONAL RETENTION - not duplication. Each nnnn-900-EXIT epilogue and its nnnn-999-EXIT
            // terminator stay separate methods because they do different work: the epilogue body moves the
            // file status into the return code and runs whether or not any input or output happened, which is
            // the mechanism of defect A, while the terminator body is a bare EXIT. Collapsing the pair into
            // one method would erase defect A silently, which is what this assertion prevents.
            final List<String> epilogues = List.of("trnxfileStatusEpilogue", "xreffileStatusEpilogue",
                    "custfileStatusEpilogue", "acctfileStatusEpilogue");
            final List<String> terminators = List.of("trnxfileTerminator", "xreffileTerminator",
                    "custfileTerminator", "acctfileTerminator");

            assertThat(declaredMethodNames())
                    .as("1900-EXIT is NOT a terminator: its body is MOVE TRNXFILE-STATUS TO LK-M03B-RC at "
                            + "app/cbl/CBSTM03B.CBL:L152, and it runs whether or not any input or output "
                            + "happened - which is defect A. 1999-EXIT at L154 is the terminator, body EXIT. "
                            + "The same split recurs at 2900/2999, 3900/3999 and 4900/4999. Collapsing a "
                            + "pair would delete defect A.")
                    .containsAll(epilogues)
                    .containsAll(terminators);

            assertThat(epilogues).doesNotContainAnyElementsOf(terminators);
            assertThat(Stream.concat(epilogues.stream(), terminators.stream()).distinct().toList())
                    .as("four epilogues plus four terminators, all distinct")
                    .hasSize(8);
        }

        @ParameterizedTest(name = "{0} contributes a dataset binding rather than a paragraph method")
        @EnumSource(FileService.Dd.class)
        @DisplayName("FILE-CONTROL. at L30 maps to the four dataset bindings, not to a method")
        void fileControlMapsToTheFourDatasetBindings(final FileService.Dd dd) {
            final FakeDataset binding = new FakeDataset(dd);
            final FileService service = serviceOver(binding);

            assertThat(service.isBound(dd))
                    .as("the SELECT for %s at app/cbl/CBSTM03B.CBL:L31-L53 becomes an injected binding, "
                            + "which is why FILE-CONTROL. is the one Area-A label with no method", dd.ddName())
                    .isTrue();
            assertThat(dd.accessMode())
                    .as("and the binding carries the ACCESS MODE that decides its read form")
                    .isNotNull();
        }

        @Test
        @DisplayName("no paragraph name survives as a public method: the whole contract is the shared area")
        void noParagraphNameSurvivesAsAPublicMethod() {
            final List<String> publicNames = Arrays.stream(FileService.class.getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
            final List<String> paragraphMethods = paragraphs()
                    .map(arguments -> (String) arguments.get()[2])
                    .toList();

            assertThat(publicNames)
                    .as("PROCEDURE DIVISION USING LK-M03B-AREA at app/cbl/CBSTM03B.CBL:L114 takes one "
                            + "parameter and exposes one entry point, so every paragraph stays private")
                    .doesNotContainAnyElementsOf(paragraphMethods);
        }
    }

    @Nested
    @DisplayName("13. CBSTM03B carries no abend of its own, and no write of any kind")
    class NoAbendAndNoWrite {

        @Test
        @DisplayName("no return code makes execute throw: the subprogram declares no ABCODE")
        void noReturnCodeMakesExecuteThrow() {
            for (final String status : List.of(OK, SECONDARY, EOF, NOT_FOUND, "22", "35", "90", "97", "99")) {
                final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
                binding.openStatus(status);
                final FileService service = serviceOver(binding);

                assertThat(service.execute(FileService.FileServiceRequest
                        .of(FileService.Dd.TRNXFILE, FileService.Operation.OPEN)).returnCode())
                        .as("grep -ci 'ABCODE|CEE3ABD|ABEND' app/cbl/CBSTM03B.CBL is 0, so the subprogram "
                                + "moves a FILE STATUS into LK-M03B-RC and returns. Status [%s] must "
                                + "surface as a return code, never as an abend from this boundary.", status)
                        .isEqualTo(status);
            }
        }

        @Test
        @DisplayName("the abends this class does raise are contract breaches, not status interpretations")
        void theAbendsRaisedAreContractBreachesNotStatusInterpretations() {
            final FileService unbound = new FileService(new FileStatusMapper(), List.of());

            final FatalProcessingException abend = catchAbend(() -> unbound.execute(
                    FileService.FileServiceRequest.of(FileService.Dd.CUSTFILE, FileService.Operation.OPEN)));

            assertThat(abend.getAbendCulprit()).startsWith("CBSTM03B");
            assertThat(abend.getAbendCode())
                    .as("an absent binding is a wiring fault, so it carries no abend code: the 999 of "
                            + "app/cbl/CBTRN02C.cbl belongs to the batch programs that declare it, and "
                            + "CBSTM03B is outside that contract")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
        }

        @Test
        @DisplayName("the dataset contract declares no write and no rewrite at all")
        void theDatasetContractDeclaresNoWriteAndNoRewrite() {
            final List<String> operations = Arrays.stream(FileService.Dataset.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();

            assertThat(operations)
                    .as("every OPEN in app/cbl/CBSTM03B.CBL is OPEN INPUT - L136, L160, L184, L209 - and the "
                            + "program contains no WRITE, REWRITE or DELETE verb anywhere, so no write "
                            + "capability may be exposed here either")
                    .containsExactlyInAnyOrder("dd", "openInput", "close", "readNext", "readByKey")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("write"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("delete"));
        }

        @Test
        @DisplayName("the only open the contract offers is an input open, named as such")
        void theOnlyOpenOfferedIsAnInputOpen() {
            assertThat(Arrays.stream(FileService.Dataset.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("open"))
                    .toList())
                    .as("OPEN INPUT is the only form the source uses, so openInput is the only form offered")
                    .containsExactly("openInput");
        }

        @Test
        @DisplayName("an abend raised here carries no cause, because nothing was caught to be swallowed")
        void anAbendRaisedHereCarriesNoCauseBecauseNothingWasCaught() {
            final FileService service = serviceOver(new FakeDataset(FileService.Dd.ACCTFILE));
            final FileService.FileServiceRequest badLength = FileService.FileServiceRequest
                    .keyed(FileService.Dd.ACCTFILE, "12345678901", 0);

            final FatalProcessingException abend = catchAbend(() -> service.execute(badLength));

            assertThat(abend.getCause())
                    .as("this boundary detects the breach itself rather than catching one, so there is no "
                            + "root cause to preserve and none is fabricated. Asserting null is what proves "
                            + "no exception was swallowed on the way here.")
                    .isNull();
            assertThat(abend.getAbendReason())
                    .as("context travels in the four abend work-area fields of app/cpy/CSMSG02Y.cpy instead")
                    .isNotBlank();
            assertThat(abend.getAbendMessage()).isNotBlank();
            assertThat(abend.getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("where a cause does exist the shared owner preserves it, rather than replacing it")
        void whereACauseExistsTheSharedOwnerPreservesIt() {
            final FileStatusMapper mapper = new FileStatusMapper();
            final IllegalStateException root = new IllegalStateException("simulated driver failure");

            final Throwable thrown = catchThrowable(
                    () -> mapper.requireSuccess("35", "CUSTFILE", "OPEN", root));

            assertThat(thrown)
                    .as("clause B requires that context be added without losing the root cause")
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("CUSTFILE")
                    .hasCause(root);
            assertThat(thrown.getCause())
                    .as("the very same instance, not a copy and not a replacement")
                    .isSameAs(root);
        }
    }

    @Nested
    @DisplayName("14. Return-code interpretation is delegated, never re-implemented")
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.STRICT_STUBS)
    class DelegationToTheStatusMapper {

        /**
         * The collaborator that owns every return-code decision, mocked under strict stubs so that an
         * unnecessary stubbing fails the test rather than passing unnoticed.
         */
        @Mock
        private FileStatusMapper mapper;

        @Test
        @DisplayName("open delegates to the scoped guard, passing the DD name and the operation")
        void openDelegatesToTheScopedGuard() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.openStatus(SECONDARY);

            new FileService(mapper, List.of(binding)).open(FileService.Dd.TRNXFILE);

            Mockito.verify(mapper).requireFileServiceSuccess(SECONDARY, "TRNXFILE", "OPEN");
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("close delegates to the scoped guard with its own operation name")
        void closeDelegatesToTheScopedGuard() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.closeStatus(OK);

            new FileService(mapper, List.of(binding)).close(FileService.Dd.XREFFILE);

            Mockito.verify(mapper).requireFileServiceSuccess(OK, "XREFFILE", "CLOSE");
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("the priming read delegates to the lenient guard, which is the one that takes 04")
        void thePrimingReadDelegatesToTheLenientGuard() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.queueSequentialRead(FileService.DatasetRead.of(SECONDARY,
                    recordFor(FileService.Dd.TRNXFILE, 'T')));

            final String payload = new FileService(mapper, List.of(binding))
                    .readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE);

            assertThat(payload).hasSize(FileService.PAYLOAD_WIDTH);
            Mockito.verify(mapper).requireFileServiceSuccess(SECONDARY, "TRNXFILE", "READ");
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("the get-next read delegates to the end-of-file aware guard instead")
        void theGetNextReadDelegatesToTheEndOfFileAwareGuard() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.XREFFILE);
            binding.queueSequentialRead(FileService.DatasetRead.withoutRecord(EOF));
            Mockito.when(mapper.requireFileServiceSuccessOrEndOfFile(EOF, "XREFFILE", "READ"))
                    .thenReturn(true);

            final Optional<String> record = new FileService(mapper, List.of(binding))
                    .readNext(FileService.Dd.XREFFILE);

            assertThat(record)
                    .as("the mapper reported end of file, so the loop terminates without an exception")
                    .isEmpty();
            Mockito.verify(mapper).requireFileServiceSuccessOrEndOfFile(EOF, "XREFFILE", "READ");
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("the keyed read delegates too, and the extracted key never reaches the mapper")
        void theKeyedReadDelegatesAndTheKeyNeverReachesTheMapper() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.CUSTFILE);
            binding.stubKeyedRead("CCCCCCCCC",
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'C')));

            final String payload = new FileService(mapper, List.of(binding))
                    .readByKey(FileService.Dd.CUSTFILE, "CCCCCCCCC", 9);

            assertThat(payload).hasSize(FileService.PAYLOAD_WIDTH);
            Mockito.verify(mapper).requireFileServiceSuccessOrEndOfFile(OK, "CUSTFILE", "READ_K");
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("the status rendering is never re-implemented here: only the mapper is asked")
        void theStatusRenderingIsNeverReImplementedHere() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            binding.openStatus("97");

            new FileService(mapper, List.of(binding)).open(FileService.Dd.ACCTFILE);

            Mockito.verify(mapper).requireFileServiceSuccess("97", "ACCTFILE", "OPEN");
            Mockito.verify(mapper, Mockito.never()).displayIoStatus(Mockito.anyString());
            Mockito.verifyNoMoreInteractions(mapper);
        }

        @Test
        @DisplayName("a mocked guard cannot abend, which proves the decision is not taken here")
        void aMockedGuardCannotAbendWhichProvesTheDecisionIsNotTakenHere() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.openStatus("35");

            final FileService service = new FileService(mapper, List.of(binding));

            assertThat(service.isBound(FileService.Dd.TRNXFILE)).isTrue();
            service.open(FileService.Dd.TRNXFILE);

            assertThat(binding.openCount())
                    .as("status 35 would abend through the real mapper. That it does not abend through a "
                            + "mock is the proof that FileService holds no copy of the status map: it "
                            + "forwards the code and lets the single owner decide.")
                    .isOne();
            Mockito.verify(mapper).requireFileServiceSuccess("35", "TRNXFILE", "OPEN");
        }

        /**
         * Proves that the dataset verb is issued before the guard that interprets its status, for all four
         * operations, using one transcript that both collaborators write into.
         *
         * <h4>Why a transcript and not two separate orderings</h4>
         *
         * <p>The claim is about the boundary <em>between</em> two collaborators: the dataset performs the I/O
         * and only then is its return code handed to the guard. An {@code InOrder} over the mapper alone
         * cannot express that. It can only say that one mapper call preceded another mapper call - which is
         * true of an implementation that consulted the guard first and read afterwards, and true of one that
         * never read at all. The dataset side of every pair has to be observable in the same sequence as the
         * guard side, or the assertion is about something other than what it is named for.
         *
         * <p>{@link FakeDataset} is a hand-written fake rather than a Mockito mock, deliberately, because the
         * four operations have stateful behaviour - a sequential read consumes a queued record - that a stub
         * would have to re-express. It therefore cannot join an {@code InOrder}. The remedy is a shared event
         * recorder: the fake appends one entry per verb and the mapper is stubbed with an answer that appends
         * one entry per guard, both into the same list, so the resulting transcript is the interleaving
         * itself. An implementation that consulted the guard before performing the verb would produce
         * {@code GUARD:OPEN, DATASET:OPEN} and fail on the first pair.
         *
         * <p>All four operations are covered in one method on purpose: the ordering is one contract of the
         * dispatch, not four independent ones, and asserting the whole transcript at once also proves that
         * nothing extra is interleaved between a verb and its guard.
         */
        @Test
        @DisplayName("the dataset verb runs before the guard that reads its status, for all four operations")
        void theGuardRunsAfterTheInputOrOutputNeverBeforeIt() {
            // Two bindings, because the matrix is access-mode specific: XREFFILE is SEQUENTIAL and
            // implements only the sequential read, CUSTFILE is RANDOM and implements only the keyed read.
            // Covering both in one transcript is what proves the ordering holds for all four verbs rather
            // than only for the pair one access mode happens to offer.
            final List<String> transcript = new ArrayList<>();
            final FakeDataset sequential = new FakeDataset(FileService.Dd.XREFFILE);
            sequential.recordInto(transcript);
            sequential.openStatus(OK);
            sequential.closeStatus(OK);
            sequential.queueSequentialRead(
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.XREFFILE, 'X')));
            final FakeDataset keyed = new FakeDataset(FileService.Dd.CUSTFILE);
            keyed.recordInto(transcript);
            keyed.openStatus(OK);
            keyed.closeStatus(OK);
            keyed.stubKeyedRead("CCCCCCCCC",
                    FileService.DatasetRead.of(OK, recordFor(FileService.Dd.CUSTFILE, 'C')));
            Mockito.doAnswer(invocation -> {
                transcript.add("GUARD:" + invocation.getArgument(2));
                return null;
            }).when(mapper).requireFileServiceSuccess(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString());
            Mockito.doAnswer(invocation -> {
                transcript.add("GUARD:" + invocation.getArgument(2));
                return false;
            }).when(mapper).requireFileServiceSuccessOrEndOfFile(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString());

            final FileService service = new FileService(mapper, List.of(sequential, keyed));
            service.open(FileService.Dd.XREFFILE);
            service.readNext(FileService.Dd.XREFFILE);
            service.close(FileService.Dd.XREFFILE);
            service.open(FileService.Dd.CUSTFILE);
            service.readByKey(FileService.Dd.CUSTFILE, "CCCCCCCCC", 9);
            service.close(FileService.Dd.CUSTFILE);

            assertThat(transcript)
                    .as("each verb is followed by its own guard, and nothing sits between the two")
                    .containsExactly(
                            "DATASET:OPEN", "GUARD:OPEN",
                            // The sequential guard is labelled READ, matching the single sequential cell of
                            // the matrix; only the keyed guard carries the _K discriminator.
                            "DATASET:READ_N", "GUARD:READ",
                            "DATASET:CLOSE", "GUARD:CLOSE",
                            "DATASET:OPEN", "GUARD:OPEN",
                            "DATASET:READ_K", "GUARD:READ_K",
                            "DATASET:CLOSE", "GUARD:CLOSE");
            // And the mapper really was the thing consulted, with the status the dataset returned.
            final InOrder order = Mockito.inOrder(mapper);
            order.verify(mapper).requireFileServiceSuccess(OK, "XREFFILE", "OPEN");
            order.verify(mapper).requireFileServiceSuccessOrEndOfFile(OK, "XREFFILE", "READ");
            order.verify(mapper).requireFileServiceSuccess(OK, "XREFFILE", "CLOSE");
            order.verify(mapper).requireFileServiceSuccess(OK, "CUSTFILE", "OPEN");
            order.verify(mapper).requireFileServiceSuccessOrEndOfFile(OK, "CUSTFILE", "READ_K");
            order.verify(mapper).requireFileServiceSuccess(OK, "CUSTFILE", "CLOSE");
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("a guard that abends does so with the verb already performed, never instead of it")
        void aGuardThatAbendsDoesSoWithTheVerbAlreadyPerformed() {
            // The failing case of the same boundary. When the guard rejects a status, the dataset operation
            // has already happened - the status could not exist otherwise - so the transcript must still show
            // the verb before the guard, and the count on the fake must show the verb was issued exactly once.
            final List<String> transcript = new ArrayList<>();
            final FakeDataset binding = new FakeDataset(FileService.Dd.ACCTFILE);
            binding.recordInto(transcript);
            binding.openStatus("97");
            Mockito.doAnswer(invocation -> {
                transcript.add("GUARD:" + invocation.getArgument(2));
                throw new FatalProcessingException(null, "CBSTM03B", null, "unexpected status");
            }).when(mapper).requireFileServiceSuccess(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString());

            final FileService service = new FileService(mapper, List.of(binding));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.open(FileService.Dd.ACCTFILE));
            assertThat(transcript).containsExactly("DATASET:OPEN", "GUARD:OPEN");
            assertThat(binding.openCount())
                    .as("the verb was issued once and the guard rejected its result, not the reverse")
                    .isOne();
        }

        @Test
        @DisplayName("execute asks the mapper nothing at all: interpretation is the caller's job")
        void executeAsksTheMapperNothingAtAll() {
            final FakeDataset binding = new FakeDataset(FileService.Dd.TRNXFILE);
            binding.openStatus("90");

            final FileService.FileServiceResult result = new FileService(mapper, List.of(binding))
                    .execute(FileService.FileServiceRequest
                            .of(FileService.Dd.TRNXFILE, FileService.Operation.OPEN));

            assertThat(result.returnCode()).isEqualTo("90");
            Mockito.verifyNoInteractions(mapper);
        }
    }

    @Nested
    @DisplayName("15. The 04 carve-out is scoped to this boundary and nowhere else")
    class SecondaryStatusCarveOut {

        /**
         * The real mapper, because the point of this section is what the shared owner actually decides.
         */
        private final FileStatusMapper mapper = new FileStatusMapper();

        @Test
        @DisplayName("04 is accepted on the CBSTM03B-scoped guard")
        void secondaryStatusIsAcceptedOnTheScopedGuard() {
            assertThatNoException()
                    .as("nine sites in app/cbl/CBSTM03A.CBL accept '00' OR '04' - L736, L748, L771, L789, "
                            + "L807, L862, L879, L895, L911 - and every one of them is a CBSTM03B call site")
                    .isThrownBy(() -> mapper.requireFileServiceSuccess(SECONDARY, "TRNXFILE", "OPEN"));
        }

        @Test
        @DisplayName("04 is rejected on the general guard, so the carve-out cannot leak")
        void secondaryStatusIsRejectedOnTheGeneralGuard() {
            assertThatExceptionOfType(RuntimeException.class)
                    .as("the general FILE STATUS map has no '04' entry. Adding one would silently accept a "
                                    + "malformed status on every I/O path in the corpus, which is why the "
                                    + "carve-out is exposed through a narrowly named method instead.")
                    .isThrownBy(() -> mapper.requireSuccess(SECONDARY, "ACCTDAT", "READ"));
        }

        @Test
        @DisplayName("04 is not a success on the general sequential-read classification either")
        void secondaryStatusIsNotASuccessOnTheGeneralSequentialRead() {
            // '10' is the only non-success the sequential-read classification tolerates, and it maps to
            // APPL_EOF. '04' is not a second one: it classifies as a failure, exactly as every status
            // outside the general map does.
            assertThat(mapper.applResultForSequentialRead(SECONDARY))
                    .as("'04' is reachable only through the two file-service methods")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("the same 04 travels the two paths to opposite outcomes, from one shared owner")
        void theSameSecondaryStatusTravelsTwoPathsToOppositeOutcomes() {
            assertThatNoException()
                    .isThrownBy(() -> mapper.requireFileServiceSuccess(SECONDARY, "XREFFILE", "READ"));
            assertThatExceptionOfType(RuntimeException.class)
                    .as("one owner, two scopes: this is what 'delegate, do not duplicate' buys")
                    .isThrownBy(() -> mapper.requireSuccess(SECONDARY, "XREFFILE", "READ"));
        }

        @Test
        @DisplayName("00 is accepted by both paths, so the split is about 04 alone")
        void successIsAcceptedByBothPaths() {
            assertThatNoException()
                    .isThrownBy(() -> mapper.requireFileServiceSuccess(OK, "CUSTFILE", "OPEN"));
            assertThatNoException()
                    .isThrownBy(() -> mapper.requireSuccess(OK, "CUSTDAT", "READ"));
        }

        @Test
        @DisplayName("the get-next guard rejects 04 while the priming guard accepts it")
        void theGetNextGuardRejectsWhatThePrimingGuardAccepts() {
            assertThatNoException()
                    .as("app/cbl/CBSTM03A.CBL:L748, the priming read, accepts 00 OR 04")
                    .isThrownBy(() -> mapper.requireFileServiceSuccess(SECONDARY, "TRNXFILE", "READ"));
            assertThatExceptionOfType(RuntimeException.class)
                    .as("app/cbl/CBSTM03A.CBL:L353, the get-next read, accepts 00 and 10 only. The two read "
                            + "guards differ on 04 and that difference is behaviour.")
                    .isThrownBy(() -> mapper.requireFileServiceSuccessOrEndOfFile(
                            SECONDARY, "TRNXFILE", "READ"));
        }

        @Test
        @DisplayName("10 terminates a read loop through the scoped guard without throwing")
        void endOfFileTerminatesAReadLoopWithoutThrowing() {
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile(EOF, "XREFFILE", "READ"))
                    .as("app/cbl/CBSTM03A.CBL:L356-L357 sets END-OF-FILE rather than abending")
                    .isTrue();
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile(OK, "XREFFILE", "READ"))
                    .as("and a successful read is not end of file")
                    .isFalse();
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
         * Optional shared transcript this fake appends one entry to per verb, or {@code null} when the
         * calling test is not asserting an ordering.
         *
         * <p>It exists because the claim "the dataset verb runs before the guard that reads its status" spans
         * two collaborators, and this fake cannot join a Mockito {@code InOrder}: it is hand written rather
         * than stubbed, because the four operations have stateful behaviour that a stub would have to
         * re-express. Writing into a list the mapper's answer also writes into makes the interleaving of the
         * two sides directly observable. Left {@code null} by default so that no existing test pays for it.
         */
        private List<String> transcript;

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
            record("OPEN");
            return openStatus;
        }

        @Override
        public String close() {
            closeCount++;
            record("CLOSE");
            return closeStatus;
        }

        @Override
        public FileService.DatasetRead readNext() {
            readNextCount++;
            record("READ_N");
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
            record("READ_K");
            if (nullRead) {
                return null;
            }
            return keyedReads.getOrDefault(recordKey, keyedDefault);
        }

        /**
         * Directs this fake to append one entry per verb into a shared transcript.
         *
         * @param sink the list both this fake and the stubbed status mapper append into; must not be
         *             {@code null}
         */
        void recordInto(final List<String> sink) {
            this.transcript = java.util.Objects.requireNonNull(sink, "sink");
        }

        /**
         * Appends one verb entry to the transcript, if one was supplied.
         *
         * <p>The prefix is what makes a transcript entry attributable to the dataset rather than to the guard,
         * so the two sides of the boundary can never be confused for one another.
         *
         * @param operation the operation just performed
         */
        private void record(final String operation) {
            if (transcript != null) {
                transcript.add("DATASET:" + operation);
            }
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
