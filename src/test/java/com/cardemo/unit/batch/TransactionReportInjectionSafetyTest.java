/*
 * ******************************************************************
 * Program     : TransactionReportInjectionSafetyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (fixed-width injection contract)
 * Function    : Prove that neither of the two places a value reaches
 *               the 133-byte TRANREPT record - the processor's PIC X(n)
 *               MOVE and the step writer's record framing - admits a
 *               byte a fixed block record may not carry, that the
 *               refusal happens BEFORE padding and BEFORE truncation so
 *               a control byte cannot be silently cut away, that
 *               printable Latin-1 is still admitted so the permitted
 *               set is not narrowed, and that the diagnostic names the
 *               position and code point and never the value.
 * Source      : app/proc/TRANREPT.prc:L76      (DCB=(LRECL=133,RECFM=FB)
 *                 - fixed blocks, so a consumer finds boundaries by
 *                 counting bytes and an embedded line feed forges one)
 *               app/cbl/CBTRN03C.cbl:L354      (DISPLAY 'ERROR WRITING
 *                 REPTFILE', the abend message both guards carry)
 *               app/cbl/CBTRN03C.cbl:L361-L374 (1120-WRITE-DETAIL, the
 *                 MOVEs whose receiving fields are guarded)
 *               app/cpy/CVTRA07Y.cpy:L15-L31   (the detail line layout)
 *                                                          @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor.ReportLines;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ExecutionContext;

/**
 * The permitted-byte contract of the two places a value becomes part of a {@code TRANREPT} record.
 *
 * <h2>What this proves, and why the record stays well formed either way</h2>
 *
 * <p><strong>Finding M-09, severity High.</strong> {@code TransactionReportProcessor.fixedWidth} padded and
 * truncated but validated nothing, and {@code TransactionReportJob}'s step writer admitted every code point
 * through {@code 0xFF}. Between them, a carriage return, a line feed, a NUL or any other control byte reaching
 * either from a database column travelled straight into the report.
 *
 * <p>{@code app/proc/TRANREPT.prc:L76} declares {@code DCB=(LRECL=133,RECFM=FB)} - fixed blocks with no
 * delimiter - so a consumer finds record boundaries by counting bytes. An embedded line feed therefore does
 * <em>not</em> corrupt the object, and that is exactly what makes it dangerous: the object stays 133 bytes per
 * record and still parses, while any reader that splits on newlines - a shell pipeline, a spreadsheet import,
 * a log viewer, a downstream loader - sees two records where the report has one, with attacker-chosen content
 * after the forged boundary. Truncation is not a defence either, because a control byte inside the retained
 * prefix survives it.
 *
 * <h2>The four properties asserted</h2>
 *
 * <ol>
 *   <li><strong>Both sites refuse.</strong> The processor guard and the writer guard are independent layers
 *       over the same record, and a value can reach the writer from a line the processor did not build, so
 *       each is driven separately rather than one standing in for the other.</li>
 *   <li><strong>The refusal precedes padding and truncation.</strong> A control byte beyond a receiving
 *       field's declared width would otherwise be cut away silently, which would make the same value
 *       acceptable in one column and rejected in another - and would leave a wider field unprotected.</li>
 *   <li><strong>Printable Latin-1 is still admitted.</strong> The permitted set is printable ASCII plus
 *       printable Latin-1, which is what every other fixed-width writer in the tree applies. Narrowing it to
 *       ASCII would be a behaviour change dressed as a fix.</li>
 *   <li><strong>The diagnostic names the position and the code point, never the value.</strong> These are
 *       cardholder-bearing fields; a diagnostic is not a licence to emit them.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=TransactionReportInjectionSafetyTest test}. No container, no database and
 * no profile.
 */
@DisplayName("TRANREPT permitted bytes - neither the PIC X(n) MOVE nor the record framing admits a control byte")
class TransactionReportInjectionSafetyTest {

    /** {@code WS-START-DATE}, the inclusive lower bound of the driven period. */
    private static final String START_DATE = "2022-06-01";

    /** {@code WS-END-DATE}, the inclusive upper bound of the driven period. */
    private static final String END_DATE = "2022-06-30";

    /** A processing timestamp comfortably inside the period. */
    private static final String IN_PERIOD_PROC_TS = "2022-06-15-12.00.00.000000";

    /** {@code TRAN-CARD-NUM}, sixteen characters. */
    private static final String CARD = "4000000000000001";

    /** The account the card resolves to. */
    private static final long ACCOUNT = 11L;

    /** {@code TRAN-TYPE-CD}, two characters. */
    private static final String TYPE_CD = "01";

    /** {@code TRAN-CAT-CD}, four digits. */
    private static final int CAT_CD = 5;

    /** {@code app/proc/TRANREPT.prc:L76} - the fixed block record length. */
    private static final int LINE_LENGTH = 133;

    /** {@code app/cpy/CVTRA07Y.cpy:L22} - the receiving width of the type description. */
    private static final int TYPE_DESC_WIDTH = 15;

    /** {@code DISPLAY 'ERROR WRITING REPTFILE'}, {@code app/cbl/CBTRN03C.cbl:L354}. */
    private static final String ERROR_WRITING_REPTFILE = "ERROR WRITING REPTFILE";

    /** The single-byte charset the report is encoded in. */
    private static final java.nio.charset.Charset FIXED_WIDTH = StandardCharsets.ISO_8859_1;

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    private final CardCrossReferenceRepository cardCrossReferenceRepository =
            mock(CardCrossReferenceRepository.class);

    private final TransactionTypeRepository transactionTypeRepository =
            mock(TransactionTypeRepository.class);

    private final TransactionCategoryRepository transactionCategoryRepository =
            mock(TransactionCategoryRepository.class);

    /** The processor under test in the first group. */
    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        this.processor = new TransactionReportProcessor(transactionRepository,
                cardCrossReferenceRepository, transactionTypeRepository, transactionCategoryRepository,
                new FileStatusMapper(), START_DATE, END_DATE);
    }

    /**
     * Stubs both reference tables and the cross reference so a record resolves end to end.
     *
     * @param typeDescription the {@code TRAN-TYPE-DESC} the type table holds
     */
    private void stubReferenceData(final String typeDescription) {
        when(transactionTypeRepository.findAll())
                .thenReturn(List.of(new TransactionType(TYPE_CD, typeDescription)));
        when(transactionCategoryRepository.findAll())
                .thenReturn(List.of(new TransactionCategory(
                        new TransactionCategoryId(TYPE_CD, CAT_CD), "Regular Sales Draft")));
        when(cardCrossReferenceRepository.findById(CARD))
                .thenReturn(Optional.of(new CardCrossReference(CARD, 123456789L, ACCOUNT)));
    }

    /**
     * Builds a report-eligible transaction whose {@code TRAN-SOURCE} carries a chosen value.
     *
     * <p>{@code TRAN-SOURCE} is {@code PIC X(10)} and the entity validates only its width, so it is the
     * shortest honest route from a stored column to the guarded {@code MOVE}.
     *
     * @param transactionSource the sending item, at most ten characters
     * @return a valid transaction inside the reporting period
     */
    private static Transaction transactionWithSource(final String transactionSource) {
        return new Transaction("0000000000683580", TYPE_CD, CAT_CD, transactionSource,
                "PURCHASE AT MERCHANT", new BigDecimal("10.00"), 123456789L, "SAMPLE MERCHANT",
                "SAMPLE CITY", "12345", CARD, "2022-06-15-11.00.00.000000", IN_PERIOD_PROC_TS);
    }

    /**
     * Asserts that driving one record abends on the permitted-byte guard, naming a position and a code point.
     *
     * @param record the record to drive
     * @param expectedPosition the one-based position within the sending item
     * @param expectedCodePoint the refused code point
     */
    private void assertRefused(
            final Transaction record, final int expectedPosition, final int expectedCodePoint) {

        assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(() -> processor.process(record))
                .satisfies(abend -> {
                    assertThat(abend.getAbendReason())
                            .as("app/proc/TRANREPT.prc:L76 - RECFM=FB has no delimiter, so a control byte "
                                    + "forges a record boundary for any reader that splits on newlines")
                            .isEqualTo("a fixed block report record cannot carry the character at position "
                                    + expectedPosition + " of the sending item (code point "
                                    + expectedCodePoint + ")");
                    assertThat(abend.getAbendMessage())
                            .as("app/cbl/CBTRN03C.cbl:L354 - the report write failure text, which is the "
                                    + "message every other abend in the processor carries")
                            .isEqualTo(ERROR_WRITING_REPTFILE);
                    assertThat(abend.getAbendCode())
                            .as("app/cbl/CBTRN03C.cbl:L629 - MOVE 999 TO ABCODE")
                            .isEqualTo("0999");
                });
    }

    /**
     * The processor's {@code PIC X(n)} {@code MOVE} refuses before it pads or truncates.
     */
    @Nested
    @DisplayName("the PIC X(n) MOVE of the processor refuses before padding and before truncation")
    class TheMoveIsGuarded {

        /**
         * Creates the {@code MOVE} group.
         *
         * <p>Declared explicitly because JUnit builds one instance per test method and the enclosing instance
         * supplies every collaborator, so the body has nothing to do.
         */
        TheMoveIsGuarded() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        @Test
        @DisplayName("a LINE FEED in a stored column is refused by position and code point")
        void aLineFeedIsRefused() {
            stubReferenceData("Purchase");

            // TRAN-SOURCE is the fourth guarded MOVE of 1120-WRITE-DETAIL, so the position reported is the
            // position within the SENDING ITEM and not within the assembled line - which is what makes the
            // diagnostic point at the column an operator has to go and fix.
            assertRefused(transactionWithSource("AB\nCD"), 3, 10);
        }

        @Test
        @DisplayName("a CARRIAGE RETURN is refused the same way, so a CRLF pair cannot slip through half way")
        void aCarriageReturnIsRefused() {
            stubReferenceData("Purchase");

            assertRefused(transactionWithSource("AB\rCD"), 3, 13);
        }

        @ParameterizedTest
        @ValueSource(ints = {0x00, 0x07, 0x09, 0x0B, 0x0C, 0x1B, 0x1F, 0x7F, 0x80, 0x85, 0x9F})
        @DisplayName("every other non-printable single byte is refused too, so the set is a policy and not a "
                + "list of the two characters that happen to break a text reader")
        void everyOtherNonPrintableByteIsRefused(final int codePoint) {
            stubReferenceData("Purchase");

            // 0x00 through 0x1F are the C0 controls, 0x7F is DELETE, and 0x80 through 0x9F are the C1
            // controls - which include 0x85 NEXT LINE, a record separator in the EBCDIC lineage this data
            // came from and therefore a boundary forger for exactly the same reason a line feed is.
            assertRefused(transactionWithSource("AB" + (char) codePoint + "CD"), 3, codePoint);
        }

        @Test
        @DisplayName("a control byte BEYOND the receiving field's width is still refused, so truncation is "
                + "never a silent sanitiser")
        void aControlByteBeyondTheReceivingWidthIsStillRefused() {
            // TRAN-TYPE-DESC is PIC X(50) and app/cpy/CVTRA07Y.cpy:L22 receives it into fifteen characters,
            // so this line feed at position 31 is cut away by the MOVE itself. Validating after truncation
            // would therefore have accepted this row while rejecting the identical byte in a narrower field -
            // an inconsistency an attacker chooses the field to exploit.
            final String description = "Purchase".repeat(3) + "\nremainder";
            assertThat(description.indexOf('\n'))
                    .as("the offending byte must genuinely sit beyond the receiving width for this to prove "
                            + "anything")
                    .isGreaterThan(TYPE_DESC_WIDTH);
            stubReferenceData(description);

            assertRefused(transactionWithSource("POS TERM"), description.indexOf('\n') + 1, 10);
        }

        @Test
        @DisplayName("the diagnostic carries no fragment of the offending value, because these fields are "
                + "cardholder bearing")
        void theDiagnosticCarriesNoFragmentOfTheValue() {
            stubReferenceData("Purchase");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transactionWithSource("ZQ\nXW")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .as("a position and a code point are enough to locate the defect; the value "
                                        + "would put report content into a log line")
                                .doesNotContain("ZQ")
                                .doesNotContain("XW");
                        assertThat(abend.getMessage()).doesNotContain("ZQ").doesNotContain("XW");
                    });
        }

        @Test
        @DisplayName("PRINTABLE LATIN-1 is admitted, so the permitted set is not quietly narrowed to ASCII")
        void printableLatinOneIsAdmitted() {
            stubReferenceData("Purchase");

            final ReportLines emitted = processor.process(transactionWithSource("CAF\u00c9 \u00dcBER"));

            assertThat(emitted).isNotNull();
            assertThat(emitted.lines())
                    .as("app/proc/TRANREPT.prc:L76 - every emitted record is exactly 133 characters, and a "
                            + "single byte above the ASCII range occupies exactly one of them")
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).hasSize(LINE_LENGTH));
            assertThat(String.join("", emitted.lines()).getBytes(FIXED_WIDTH))
                    .hasSize(emitted.lines().size() * LINE_LENGTH);
        }

        @Test
        @DisplayName("an ordinary record still passes, so the guard rejects bytes rather than records")
        void anOrdinaryRecordStillPasses() {
            stubReferenceData("Purchase");

            final ReportLines emitted = processor.process(transactionWithSource("POS TERM  "));

            assertThat(emitted).isNotNull();
            assertThat(emitted.lines()).allSatisfy(line -> assertThat(line).hasSize(LINE_LENGTH));
        }
    }

    /**
     * The step writer's record framing refuses the same set, independently of the processor.
     */
    @Nested
    @DisplayName("the step writer's record framing refuses the same set, on the UNPADDED line")
    class TheFramingIsGuarded {

        /** Everything the framing handed to the destination stream. */
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        /** The writer instance whose framing is driven. */
        private final Object writer;

        /** The framing method, which takes its destination as a parameter. */
        private final Method framing;

        /**
         * Builds the job's private per-step writer and resolves its framing method.
         *
         * <p>The writer is a {@code private static final} nested class with a private constructor, which is
         * correct - nothing outside the job may create one. Reaching it reflectively is what lets this suite
         * drive the framing guard directly, and driving it directly is necessary twice over: the processor
         * guard abends first on every functional route, so an end-to-end test can never reach this layer at
         * all; and {@code ReportLines} refuses any group whose lines are not exactly
         * {@value #LINE_LENGTH} characters, so the short-line and overlong-line cases cannot be expressed
         * through the chunk contract either.
         *
         * <p>{@code writeLine} takes its destination as a parameter, so the recording stream is passed in
         * directly rather than through the writer's own buffered stream - which means every assertion about
         * what did or did not reach the object is made on unbuffered bytes and cannot pass vacuously.
         *
         * @throws Exception if the class, its constructor or its framing method cannot be found
         */
        TheFramingIsGuarded() throws Exception {
            final Class<?> type = Class.forName(
                    "com.cardemo.batch.jobs.TransactionReportJob$FixedWidthReportItemWriter");
            final Constructor<?> constructor = type.getDeclaredConstructor(
                    S3Operations.class, FileStatusMapper.class, String.class, String.class);
            constructor.setAccessible(true);
            this.writer = constructor.newInstance(mock(S3Operations.class), new FileStatusMapper(),
                    "carddemo-batch-output", "gdg/tranrept/generation=1/TRANREPT");
            this.framing = type.getDeclaredMethod("writeLine", OutputStream.class, String.class);
            this.framing.setAccessible(true);
        }

        /**
         * Frames one line into the recording stream, unwrapping the reflective wrapper.
         *
         * @param line the line to frame
         */
        private void frame(final String line) {
            try {
                framing.invoke(writer, received, line);
            } catch (final InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(wrapped.getCause());
            } catch (final ReflectiveOperationException reflection) {
                throw new IllegalStateException(reflection);
            }
        }

        /**
         * Pads a literal to the fixed block record length.
         *
         * @param value the literal
         * @return exactly {@value #LINE_LENGTH} characters
         */
        private String padded(final String value) {
            return value + " ".repeat(LINE_LENGTH - value.length());
        }

        @Test
        @DisplayName("a clean 133-character line is framed as exactly 133 bytes")
        void aCleanLineIsFramed() {
            frame(padded("TRANSACTION REPORT"));

            assertThat(received.toByteArray()).hasSize(LINE_LENGTH);
        }

        @Test
        @DisplayName("a LINE FEED is refused and nothing reaches the destination, so no partial record is "
                + "framed")
        void aLineFeedIsRefusedAndNothingIsWritten() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> frame(padded("TOTAL\nINJECTED")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("REPORT CHARACTER INVALID");
                        assertThat(abend.getAbendMessage())
                                .as("position and code point only - the line carries cardholder-bearing "
                                        + "fields")
                                .isEqualTo("TRANREPT LINE CANNOT CARRY THE CHARACTER AT POSITION 6 "
                                        + "(CODE POINT 10)")
                                .doesNotContain("INJECTED");
                    });

            assertThat(received.toByteArray())
                    .as("the guard runs before the encode and the write, so a refused line leaves the "
                            + "destination exactly as it was")
                    .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {0x00, 0x0D, 0x1F, 0x7F, 0x85, 0x9F})
        @DisplayName("the same permitted set applies here, so the two layers cannot disagree about a byte")
        void theSamePermittedSetApplies(final int codePoint) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> frame(padded("X" + (char) codePoint)))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .isEqualTo("TRANREPT LINE CANNOT CARRY THE CHARACTER AT POSITION 2 "
                                    + "(CODE POINT " + codePoint + ")"));

            assertThat(received.toByteArray()).isEmpty();
        }

        @Test
        @DisplayName("the check runs on the UNPADDED line, so a short line is validated over its own length "
                + "and the padding it receives can never shift a reported position")
        void theCheckRunsOnTheUnpaddedLine() {
            // Padding happens after the check and only ever appends blanks, so validating the padded form
            // would report the same position here - but it would also mean the check ran over 133 characters
            // for every three-character line, and a guard that examines bytes the caller never supplied is a
            // guard whose diagnostic the caller cannot act on.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> frame("AB\u0001"))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .isEqualTo("TRANREPT LINE CANNOT CARRY THE CHARACTER AT POSITION 3 "
                                    + "(CODE POINT 1)"));

            assertThat(received.toByteArray()).isEmpty();
        }

        @Test
        @DisplayName("PRINTABLE LATIN-1 is admitted here too, and still frames to exactly 133 bytes")
        void printableLatinOneIsAdmitted() {
            frame(padded("CAF\u00c9 \u00dcBER \u00ff"));

            assertThat(received.toByteArray())
                    .as("ISO-8859-1 is a single-byte encoding, so a character above the ASCII range does not "
                            + "widen the record")
                    .hasSize(LINE_LENGTH);
        }

        @Test
        @DisplayName("a line longer than the record length is still refused first, so the permitted-byte "
                + "guard was inserted after the length guard rather than in place of it")
        void anOverlongLineIsStillRefusedFirst() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> frame("X".repeat(LINE_LENGTH + 1)))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo("REPORT LINE TOO LONG"));
        }

        @Test
        @DisplayName("the guard is wired into the real chunk contract, not merely present on a private "
                + "method: an injected group abends through write() and commits no object")
        void theGuardIsWiredIntoTheChunkContract() throws Exception {
            final ByteArrayOutputStream committed = new ByteArrayOutputStream();
            final S3Resource resource = mock(S3Resource.class);
            Mockito.doNothing().when(resource).setObjectMetadata(any(ObjectMetadata.class));
            Mockito.doReturn(committed).when(resource).getOutputStream();
            final S3Operations objectStorage = mock(S3Operations.class);
            when(objectStorage.createResource(eq("carddemo-batch-output"), anyString()))
                    .thenReturn(resource);

            final Class<?> type = Class.forName(
                    "com.cardemo.batch.jobs.TransactionReportJob$FixedWidthReportItemWriter");
            final Constructor<?> constructor = type.getDeclaredConstructor(
                    S3Operations.class, FileStatusMapper.class, String.class, String.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            final ItemStreamWriter<ReportLines> wired = (ItemStreamWriter<ReportLines>) constructor
                    .newInstance(objectStorage, new FileStatusMapper(), "carddemo-batch-output",
                            "gdg/tranrept/generation=1/TRANREPT");
            wired.open(new ExecutionContext());

            final Chunk<ReportLines> chunk = new Chunk<>(
                    List.of(new ReportLines(List.of(padded("TOTAL\nINJECTED")))));
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> wired.write(chunk))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo("REPORT CHARACTER INVALID"));

            wired.close();
            assertThat(committed.toByteArray())
                    .as("the whole chunk is abandoned, so the generation carries nothing from a group one of "
                            + "whose lines was refused")
                    .isEmpty();
        }
    }

    /**
     * Neither group can pass vacuously, because both guards are asserted to exist.
     *
     * @throws Exception if either guarded member cannot be found
     */
    @Test
    @DisplayName("both guards exist, so neither group can pass vacuously")
    void bothGuardsExist() throws Exception {
        assertThat(TransactionReportProcessor.class.getDeclaredMethod(
                        "requirePermittedBytes", String.class))
                .as("finding M-09 named the processor's fixedWidth as one of the two sites; if this method "
                        + "is ever removed the padding path is unguarded again and this suite must fail")
                .isNotNull();
        final Class<?> writerType = Class.forName(
                "com.cardemo.batch.jobs.TransactionReportJob$FixedWidthReportItemWriter");
        assertThat(writerType.getDeclaredMethod("writeLine", OutputStream.class, String.class))
                .as("and the framing site, which no functional route can reach because the processor guard "
                        + "abends first")
                .isNotNull();
    }

    /**
     * Guards against a silent reflective drift in the constructor this suite calls.
     *
     * @throws Exception if the writer class cannot be found
     */
    @Test
    @DisplayName("the reflective writer construction matches the real constructor, so a signature change "
            + "fails loudly here rather than silently skipping the framing group")
    void theReflectiveConstructionMatchesTheRealConstructor() throws Exception {
        final Class<?> writerType = Class.forName(
                "com.cardemo.batch.jobs.TransactionReportJob$FixedWidthReportItemWriter");
        assertThat(writerType.getDeclaredConstructors())
                .hasSize(1)
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(S3Operations.class, FileStatusMapper.class, String.class,
                                String.class));
    }
}
