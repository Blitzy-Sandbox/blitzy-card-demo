package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.MetaDataInstanceFactory;

import io.awspring.cloud.s3.S3Template;

import com.carddemo.entity.CardXref;

/**
 * Pure Mockito unit tests for {@link StatementItemWriter}, the dual-output fixed-width
 * {@code ItemStreamWriter} that is writer&nbsp;#2 of&nbsp;3 in the CardDemo statement-generation
 * stage ({@code StatementJob}).
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}, full HEAD
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}).</strong> {@link StatementItemWriter} is the Java
 * realization of the dual-file emission performed by the legacy batch program {@code CBSTM03A.CBL}
 * (launched by {@code CREASTMT.JCL} step {@code STEP040}), whose {@code CALL 'CBSTM03B'} file access
 * is migrated to {@code StatementFileService}. {@code CBSTM03A} declares two parallel sequential
 * output files and writes every card's statement to both:</p>
 * <pre>
 *   FD  STMT-FILE.  01  FD-STMTFILE-REC  PIC X(80).     (CREASTMT.JCL STMTFILE DD LRECL=80,  RECFM=FB)
 *   FD  HTML-FILE.  01  FD-HTMLFILE-REC  PIC X(100).    (CREASTMT.JCL HTMLFILE DD LRECL=100, RECFM=FB)
 * </pre>
 * <p>The mainframe program {@code OPEN OUTPUT STMT-FILE HTML-FILE} once, issues
 * {@code WRITE FD-STMTFILE-REC FROM ...} / {@code WRITE FD-HTMLFILE-REC FROM ...} per card, then
 * {@code CLOSE STMT-FILE HTML-FILE} once. The migrated writer mirrors that lifecycle with
 * {@code open}/{@code write}/{@code close} and ships the two aggregate files to the S3 bucket
 * {@code carddemo-batch-statements} at the stable keys {@code statements.ps} (text) and
 * {@code statements.html} (HTML), the versioned-S3 replacement for the legacy GDG generations
 * (AAP&nbsp;&sect;0.7.7,&nbsp;&sect;0.8.5).</p>
 *
 * <h2>What these tests pin (the fixed-width interface contract — AAP Gate&nbsp;1/5)</h2>
 * <ol>
 *   <li><strong>Exact record widths.</strong> Every plain-text record is exactly
 *       {@value StatementItemWriter#TEXT_RECORD_LENGTH} characters and every HTML record exactly
 *       {@value StatementItemWriter#HTML_RECORD_LENGTH} characters — short lines right-padded with
 *       spaces, long lines truncated. Over/under-width records would break any downstream fixed-block
 *       ({@code RECFM=FB}) consumer, so this exact-width proof is the unit precursor to the LocalStack
 *       S3 verification performed by the statement integration test.</li>
 *   <li><strong>Two S3 objects, exact coordinates.</strong> Exactly two uploads occur, to bucket
 *       {@code carddemo-batch-statements} at keys {@code statements.ps} then {@code statements.html},
 *       asserted via {@link ArgumentCaptor}.</li>
 *   <li><strong>Concatenation order.</strong> Multiple statement documents accumulate into the same
 *       two objects in input (card/cross-reference) order, preserving the sequential emission order
 *       of {@code CBSTM03A}.</li>
 *   <li><strong>Byte contract.</strong> Records are encoded {@link StandardCharsets#US_ASCII US-ASCII}
 *       (one byte per character) and separated by a single {@code '\n'} (LF) delimiter, matching the
 *       production serialization exactly.</li>
 * </ol>
 *
 * <p><strong>Isolation.</strong> The suite loads <em>no</em> Spring context and touches no real S3,
 * Docker, Testcontainers or live AWS: the {@link S3Template} collaborator is a Mockito {@code @Mock}
 * whose {@code upload} invocations are captured and whose payloads are decoded from the captured
 * {@link InputStream}s. It runs in milliseconds and feeds the Gate&nbsp;8 (&ge;80%) JaCoCo line
 * coverage. It compiles warning-free under {@code -Xlint:all} (Gate&nbsp;2). No COBOL source is
 * reproduced here; design rationale lives in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>On {@code @StepScope}.</strong> {@link StatementItemWriter} is annotated
 * {@code @Component @StepScope}, but step scoping only affects how <em>Spring</em> instantiates the
 * bean. These tests construct the writer directly with {@code new}, which bypasses proxy scoping
 * entirely; the writer reads no step-scoped SpEL ({@code #{stepExecution...}}/{@code #{jobParameters...}})
 * and declares no {@code @BeforeStep} listener callback, so no {@code MetaDataInstanceFactory}
 * {@code StepExecution} scaffolding is required to exercise its {@code ItemStream} contract.</p>
 *
 * @see StatementItemWriter
 * @see StatementDocument
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementItemWriter — CBSTM03A dual 80/100-char statement emission (SHA 27d6c6f)")
class StatementItemWriterTest {

    /** Destination bucket for both statement objects (AAP &sect;0.7.7). */
    private static final String BUCKET = "carddemo-batch-statements";

    /** Stable object key for the plain-text ({@code LRECL=80}) statement file. */
    private static final String TEXT_KEY = "statements.ps";

    /** Stable object key for the HTML ({@code LRECL=100}) statement file. */
    private static final String HTML_KEY = "statements.html";

    /**
     * Fixed plain-text record width, sourced from the production constant so the test is pinned to
     * the writer's own contract rather than a duplicated literal (value {@code 80}).
     */
    private static final int TEXT_WIDTH = StatementItemWriter.TEXT_RECORD_LENGTH;

    /**
     * Fixed HTML record width, sourced from the production constant (value {@code 100}).
     */
    private static final int HTML_WIDTH = StatementItemWriter.HTML_RECORD_LENGTH;

    /** Distinct 16-digit card numbers used to build documents in a deterministic order. */
    private static final String CARD_1 = "4111111111111111";
    private static final String CARD_2 = "4222222222222222";
    private static final String CARD_3 = "4000000000000003";

    /** The single-byte LF record delimiter the writer emits after every record. */
    private static final char LF = '\n';

    /** Mocked Spring Cloud AWS S3 facade; its {@code upload} calls are captured, never executed. */
    @Mock
    private S3Template s3Template;

    /** Freshly constructed and {@code open}ed writer under test (one per test method). */
    private StatementItemWriter writer;

    /**
     * A fresh {@link StepExecution} passed to {@link StatementItemWriter#afterStep(StepExecution)},
     * where the terminal serialize + dual S3 upload now lives (QA finding <strong>F4</strong>,
     * decision {@code D-027}). Its status is set to {@link BatchStatus#COMPLETED} by {@link #finish()}
     * before finalizing because {@code afterStep} only performs the upload on the success path.
     */
    private StepExecution stepExecution;

    /**
     * Constructs a fresh writer with the canonical bucket/keys and opens it, mirroring the Spring
     * Batch {@code ItemStream} lifecycle ({@code open} precedes {@code write}/{@code afterStep}). A new
     * instance per test guarantees independent, non-leaking buffers.
     */
    @BeforeEach
    void setUp() {
        writer = new StatementItemWriter(s3Template, BUCKET, TEXT_KEY, HTML_KEY);
        writer.open(new ExecutionContext());
        stepExecution = MetaDataInstanceFactory.createStepExecution();
    }

    /**
     * Finalizes the step the way Spring Batch does on the success path: marks the step
     * {@link BatchStatus#COMPLETED} and invokes {@link StatementItemWriter#afterStep(StepExecution)},
     * which serializes both buffers and performs the two S3 uploads (relocated out of {@code close()}
     * per F4 / {@code D-027}).
     */
    private void finish() {
        stepExecution.setStatus(BatchStatus.COMPLETED);
        writer.afterStep(stepExecution);
    }

    // ------------------------------------------------------------------
    // Builders / helpers (Phase 1 scaffolding).
    // ------------------------------------------------------------------

    /**
     * Builds a {@link StatementDocument} for {@link #CARD_1} carrying the supplied pre-rendered text
     * and HTML lines. Account/customer/card and per-transaction lines are irrelevant to the writer's
     * fixed-width formatting and are left {@code null}/empty; only the required non-null
     * {@link CardXref} is populated.
     *
     * @param textLines the plain-text lines (deliberately mixed length in tests)
     * @param htmlLines the HTML lines (deliberately mixed length in tests)
     * @return an immutable statement document ready to feed to the writer
     */
    private static StatementDocument statementDoc(List<String> textLines, List<String> htmlLines) {
        return statementDoc(CARD_1, textLines, htmlLines);
    }

    /**
     * Builds a {@link StatementDocument} for a specific card number.
     *
     * @param cardNumber the 16-character card number for the driving cross-reference row
     * @param textLines  the plain-text lines
     * @param htmlLines  the HTML lines
     * @return an immutable statement document ready to feed to the writer
     */
    private static StatementDocument statementDoc(
            String cardNumber, List<String> textLines, List<String> htmlLines) {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(cardNumber);
        return new StatementDocument(xref, null, null, null, null, textLines, htmlLines);
    }

    /**
     * Writes the given documents to the writer as a single chunk and finalizes the step, driving the
     * full {@code write} &rarr; {@code afterStep} (upload) path (F4 / {@code D-027}: the upload moved
     * out of {@code close()}).
     *
     * @param docs the statement documents to emit in order
     */
    private void writeAndClose(StatementDocument... docs) {
        writer.write(new Chunk<>(List.of(docs)));
        finish();
    }

    /**
     * Reference re-implementation of the writer's {@code padOrTrim}: right-pads short values with
     * spaces and truncates long ones to {@code width}. Used to compute the <em>expected</em>
     * fixed-width form independently of production, so an equality assertion validates both padding
     * and ordering.
     *
     * @param value the source line ({@code null} treated as empty)
     * @param width the target fixed width
     * @return a string of length exactly {@code width}
     */
    private static String padOrTrim(String value, int width) {
        String s = (value == null) ? "" : value;
        if (s.length() == width) {
            return s;
        }
        if (s.length() > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /**
     * Fully reads a captured upload {@link InputStream}. The captured stream is a
     * {@code ByteArrayInputStream} that the mock never consumed and whose {@code close()} is a no-op,
     * so it still yields the complete payload.
     *
     * @param in the captured stream
     * @return the full payload bytes
     */
    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            // Unreachable for ByteArrayInputStream; surfaced defensively rather than swallowed.
            throw new UncheckedIOException("failed to read captured upload stream", e);
        }
    }

    /**
     * Decodes a serialized object payload into its fixed-width records, asserting the exact US-ASCII
     * + trailing-LF serialization contract.
     *
     * @param payload the uploaded object bytes
     * @return the ordered records with the trailing delimiter removed; empty for an empty payload
     */
    private static List<String> decodeRecords(byte[] payload) {
        String text = new String(payload, StandardCharsets.US_ASCII);
        if (text.isEmpty()) {
            return List.of();
        }
        assertThat(text)
                .as("every fixed-width record (including the last) must be LF-terminated")
                .endsWith(String.valueOf(LF));
        // Split keeping trailing empties; the final element is the empty remainder after the last LF.
        String[] parts = text.split(String.valueOf(LF), -1);
        return List.copyOf(Arrays.asList(parts).subList(0, parts.length - 1));
    }

    /**
     * Captures both S3 uploads produced by a {@code close()} and returns their coordinates plus
     * decoded payloads. Records are mapped to text/HTML by object key so per-format assertions are
     * independent of upload ordering (ordering itself is asserted separately).
     *
     * @return a snapshot of the two captured uploads
     */
    private CapturedUploads capture() {
        ArgumentCaptor<String> bucketCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> bodyCap = ArgumentCaptor.forClass(InputStream.class);

        verify(s3Template, times(2)).upload(bucketCap.capture(), keyCap.capture(), bodyCap.capture());

        List<String> keys = keyCap.getAllValues();
        List<InputStream> bodies = bodyCap.getAllValues();
        byte[] textBytes = new byte[0];
        byte[] htmlBytes = new byte[0];
        for (int i = 0; i < keys.size(); i++) {
            byte[] payload = readAll(bodies.get(i));
            if (TEXT_KEY.equals(keys.get(i))) {
                textBytes = payload;
            } else if (HTML_KEY.equals(keys.get(i))) {
                htmlBytes = payload;
            }
        }
        return new CapturedUploads(
                bucketCap.getAllValues(), keys,
                textBytes, htmlBytes,
                decodeRecords(textBytes), decodeRecords(htmlBytes));
    }

    // ------------------------------------------------------------------
    // Fixed-width record contract (the values are the interface, Gate 5).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("record widths are fixed at 80 (text) and 100 (HTML), matching PIC X(80)/X(100)")
    void recordWidthsAreFixedAt80And100() {
        // These literals are the byte-parity contract: CBSTM03A FD-STMTFILE-REC PIC X(80) /
        // FD-HTMLFILE-REC PIC X(100), and CREASTMT.JCL STMTFILE LRECL=80 / HTMLFILE LRECL=100.
        assertThat(StatementItemWriter.TEXT_RECORD_LENGTH).isEqualTo(80);
        assertThat(StatementItemWriter.HTML_RECORD_LENGTH).isEqualTo(100);
    }

    // ------------------------------------------------------------------
    // Phase 2 — text stream: every record is EXACTLY 80 characters.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("text stream: every statements.ps record is exactly 80 chars (pad / trim / exact)")
    void textStreamPadsAndTrimsEveryRecordToExactly80() {
        String shortLine = "ACCOUNT SUMMARY";          // 15 chars  -> right-padded to 80
        String exactLine = "X".repeat(TEXT_WIDTH);      // 80 chars  -> unchanged
        String longLine = "Y".repeat(95);               // 95 chars  -> truncated to 80
        String emptyLine = "";                          // 0 chars   -> 80 spaces

        writeAndClose(statementDoc(
                List.of(shortLine, exactLine, longLine, emptyLine),
                List.of("<html>")));

        CapturedUploads up = capture();

        // Contract 1: EVERY text record is exactly 80 characters — no exceptions.
        assertThat(up.textRecords())
                .as("every plain-text record must equal LRECL=80")
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record).hasSize(TEXT_WIDTH));

        // Contract 2: the specific pad/trim behaviour per line.
        assertThat(up.textRecords()).containsExactly(
                padOrTrim(shortLine, TEXT_WIDTH),   // short: content then trailing spaces
                exactLine,                          // exact: unchanged
                longLine.substring(0, TEXT_WIDTH),  // long: first 80 chars only
                " ".repeat(TEXT_WIDTH));            // empty: all spaces

        // Contract 3: padding uses ASCII spaces on the right, content is left-justified.
        assertThat(up.textRecords().get(0))
                .startsWith(shortLine)
                .endsWith(" ")
                .isEqualTo(shortLine + " ".repeat(TEXT_WIDTH - shortLine.length()));
        // Contract 4: truncation drops the overflow (nothing beyond position 80 survives).
        assertThat(up.textRecords().get(2)).isEqualTo("Y".repeat(TEXT_WIDTH));
    }

    // ------------------------------------------------------------------
    // Phase 3 — HTML stream: every record is EXACTLY 100 characters.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HTML stream: every statements.html record is exactly 100 chars (pad / trim / exact)")
    void htmlStreamPadsAndTrimsEveryRecordToExactly100() {
        String shortLine = "<tr><td>balance</td></tr>";  // 25 chars -> right-padded to 100
        String exactLine = "Z".repeat(HTML_WIDTH);        // 100 chars -> unchanged
        String longLine = "W".repeat(130);                // 130 chars -> truncated to 100
        String emptyLine = "";                            // 0 chars   -> 100 spaces

        writeAndClose(statementDoc(
                List.of("some text"),
                List.of(shortLine, exactLine, longLine, emptyLine)));

        CapturedUploads up = capture();

        // Contract 1: EVERY HTML record is exactly 100 characters.
        assertThat(up.htmlRecords())
                .as("every HTML record must equal LRECL=100")
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record).hasSize(HTML_WIDTH));

        // Contract 2: per-line pad/trim behaviour.
        assertThat(up.htmlRecords()).containsExactly(
                padOrTrim(shortLine, HTML_WIDTH),
                exactLine,
                longLine.substring(0, HTML_WIDTH),
                " ".repeat(HTML_WIDTH));

        assertThat(up.htmlRecords().get(0))
                .startsWith(shortLine)
                .isEqualTo(shortLine + " ".repeat(HTML_WIDTH - shortLine.length()));
        assertThat(up.htmlRecords().get(2)).isEqualTo("W".repeat(HTML_WIDTH));
    }

    // ------------------------------------------------------------------
    // Phase 4 — S3 targets: two objects at exact bucket + keys, in order.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("close() uploads exactly two objects: carddemo-batch-statements/{statements.ps, statements.html}")
    void closeUploadsExactlyTwoObjectsToExactBucketAndKeys() {
        writeAndClose(statementDoc(List.of("line"), List.of("<td>")));

        ArgumentCaptor<String> bucketCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> bodyCap = ArgumentCaptor.forClass(InputStream.class);

        // Exactly two uploads — no more, no fewer.
        verify(s3Template, times(2)).upload(bucketCap.capture(), keyCap.capture(), bodyCap.capture());
        verifyNoMoreInteractions(s3Template);

        // Both go to the single statements bucket ...
        assertThat(bucketCap.getAllValues()).containsExactly(BUCKET, BUCKET);
        // ... at the two exact keys, text (.ps) FIRST then HTML — mirroring CBSTM03A's
        // CLOSE STMT-FILE HTML-FILE ordering.
        assertThat(keyCap.getAllValues()).containsExactly(TEXT_KEY, HTML_KEY);
        assertThat(bodyCap.getAllValues()).hasSize(2).doesNotContainNull();
    }

    @Test
    @DisplayName("multiple documents concatenate into the SAME two objects in input (card) order")
    void multipleDocumentsConcatenateIntoTheSameTwoObjectsInOrder() {
        StatementDocument first = statementDoc(CARD_1, List.of("A-text-1", "A-text-2"), List.of("A-html"));
        StatementDocument second = statementDoc(CARD_2, List.of("B-text"), List.of("B-html-1", "B-html-2"));
        StatementDocument third = statementDoc(CARD_3, List.of("C-text"), List.of("C-html"));

        // Three documents, one chunk, one close.
        writeAndClose(first, second, third);

        CapturedUploads up = capture();

        // Still exactly the two aggregate objects (not one-per-card).
        assertThat(up.keys()).containsExactly(TEXT_KEY, HTML_KEY);

        // Text records are the per-document text lines, padded, concatenated in card order.
        assertThat(up.textRecords()).containsExactly(
                padOrTrim("A-text-1", TEXT_WIDTH),
                padOrTrim("A-text-2", TEXT_WIDTH),
                padOrTrim("B-text", TEXT_WIDTH),
                padOrTrim("C-text", TEXT_WIDTH));

        // HTML records likewise, in the same card order.
        assertThat(up.htmlRecords()).containsExactly(
                padOrTrim("A-html", HTML_WIDTH),
                padOrTrim("B-html-1", HTML_WIDTH),
                padOrTrim("B-html-2", HTML_WIDTH),
                padOrTrim("C-html", HTML_WIDTH));
    }

    @Test
    @DisplayName("documents written across multiple chunks accumulate into the same objects, in order")
    void documentsAcrossMultipleChunksAccumulateInOrder() {
        // Two separate write() calls (two chunks) before a single close(): the buffers must span
        // chunk boundaries, preserving global order — CBSTM03A concatenates every card's statement
        // into the two files regardless of read batching.
        writer.write(new Chunk<>(List.of(statementDoc(CARD_1, List.of("chunk1"), List.of("h1")))));
        writer.write(new Chunk<>(List.of(statementDoc(CARD_2, List.of("chunk2"), List.of("h2")))));
        finish();

        CapturedUploads up = capture();

        assertThat(up.textRecords()).containsExactly(
                padOrTrim("chunk1", TEXT_WIDTH),
                padOrTrim("chunk2", TEXT_WIDTH));
        assertThat(up.htmlRecords()).containsExactly(
                padOrTrim("h1", HTML_WIDTH),
                padOrTrim("h2", HTML_WIDTH));
    }

    // ------------------------------------------------------------------
    // Byte contract — US-ASCII (one byte per char) + single LF delimiter.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payload is US-ASCII (1 byte/char) with each record terminated by a single LF")
    void payloadIsUsAsciiWithSingleLfPerRecord() {
        writeAndClose(statementDoc(List.of("HELLO"), List.of("WORLD")));

        CapturedUploads up = capture();

        // One text record: 80 content bytes + exactly one LF = 81 bytes (US-ASCII => 1 byte/char).
        assertThat(up.textBytes()).hasSize(TEXT_WIDTH + 1);
        assertThat(up.textBytes()[TEXT_WIDTH]).isEqualTo((byte) LF);
        // One HTML record: 100 content bytes + one LF = 101 bytes.
        assertThat(up.htmlBytes()).hasSize(HTML_WIDTH + 1);
        assertThat(up.htmlBytes()[HTML_WIDTH]).isEqualTo((byte) LF);

        // Decoding with the production charset round-trips the padded content exactly.
        assertThat(new String(up.textBytes(), StandardCharsets.US_ASCII))
                .isEqualTo(padOrTrim("HELLO", TEXT_WIDTH) + LF);
        assertThat(new String(up.htmlBytes(), StandardCharsets.US_ASCII))
                .isEqualTo(padOrTrim("WORLD", HTML_WIDTH) + LF);
    }

    // ------------------------------------------------------------------
    // Lifecycle edge cases — idempotency, empty / null chunks, empty run.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("afterStep is idempotent: a second afterStep does not re-upload")
    void secondCloseDoesNotReupload() {
        writer.write(new Chunk<>(List.of(statementDoc(List.of("only"), List.of("only")))));

        finish();
        // A second finalize must be a no-op (the writer guards on its `closed` flag, F4 / D-027).
        writer.afterStep(stepExecution);

        // Still exactly two uploads in total across both afterStep calls.
        verify(s3Template, times(2)).upload(anyString(), anyString(), any(InputStream.class));
        verifyNoMoreInteractions(s3Template);
    }

    @Test
    @DisplayName("F4: a statement S3 upload failure fails the step (FAILED + ExitStatus.FAILED), "
            + "never a false success")
    void uploadFailureFailsStep() {
        writer.write(new Chunk<>(List.of(statementDoc(List.of("only"), List.of("only")))));
        doThrow(new RuntimeException("simulated S3 outage"))
                .when(s3Template).upload(anyString(), anyString(), any(InputStream.class));

        // The chunk phase completed, so the step optimistically holds COMPLETED at afterStep entry.
        stepExecution.setStatus(BatchStatus.COMPLETED);
        final ExitStatus exit = writer.afterStep(stepExecution);

        // The upload failure must flip the step to FAILED (D-027) rather than being swallowed.
        assertThat(exit).isEqualTo(ExitStatus.FAILED);
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecution.getFailureExceptions()).isNotEmpty();
    }

    @Test
    @DisplayName("empty and null chunks contribute nothing, yet both objects are still (re)created")
    void emptyAndNullChunksAreNoOpsButBothObjectsAreStillWritten() {
        writer.write(new Chunk<StatementDocument>());   // empty chunk
        Chunk<StatementDocument> nullChunk = null;
        writer.write(nullChunk);                        // null chunk
        finish();

        CapturedUploads up = capture();

        // Both objects are deterministically written even with zero statements (CBSTM03A always
        // produces both files); their payloads are empty.
        assertThat(up.keys()).containsExactly(TEXT_KEY, HTML_KEY);
        assertThat(up.buckets()).containsExactly(BUCKET, BUCKET);
        assertThat(up.textRecords()).isEmpty();
        assertThat(up.htmlRecords()).isEmpty();
        assertThat(up.textBytes()).isEmpty();
        assertThat(up.htmlBytes()).isEmpty();
    }

    @Test
    @DisplayName("a run with no documents still uploads both (empty) objects")
    void closeWithNoDocumentsStillWritesBothObjects() {
        finish(); // no write() at all

        CapturedUploads up = capture();

        assertThat(up.keys()).containsExactly(TEXT_KEY, HTML_KEY);
        assertThat(up.textRecords()).isEmpty();
        assertThat(up.htmlRecords()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Package-private buffer accessors — widths, order, immutability, reset.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getTextBuffer()/getHtmlBuffer() expose ordered fixed-width records before close()")
    void bufferAccessorsExposeOrderedFixedWidthRecordsBeforeClose() {
        writer.write(new Chunk<>(List.of(
                statementDoc(CARD_1, List.of("t1", "t2"), List.of("h1")),
                statementDoc(CARD_2, List.of("t3"), List.of("h2", "h3")))));

        // Inspect BEFORE close() — close() clears the buffers.
        assertThat(writer.getTextBuffer())
                .allSatisfy(record -> assertThat(record).hasSize(TEXT_WIDTH))
                .containsExactly(
                        padOrTrim("t1", TEXT_WIDTH),
                        padOrTrim("t2", TEXT_WIDTH),
                        padOrTrim("t3", TEXT_WIDTH));
        assertThat(writer.getHtmlBuffer())
                .allSatisfy(record -> assertThat(record).hasSize(HTML_WIDTH))
                .containsExactly(
                        padOrTrim("h1", HTML_WIDTH),
                        padOrTrim("h2", HTML_WIDTH),
                        padOrTrim("h3", HTML_WIDTH));

        // The views are unmodifiable snapshots, not mutable internals.
        assertThatThrownBy(() -> writer.getTextBuffer().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> writer.getHtmlBuffer().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("afterStep clears the buffers after uploading (no state leaks to a subsequent run)")
    void closeClearsBuffers() {
        writer.write(new Chunk<>(List.of(statementDoc(List.of("t"), List.of("h")))));
        finish();

        assertThat(writer.getTextBuffer()).isEmpty();
        assertThat(writer.getHtmlBuffer()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Constructor guards — fail fast on invalid collaborators / coordinates.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects a null S3Template")
    void constructorRejectsNullS3Template() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatementItemWriter(null, BUCKET, TEXT_KEY, HTML_KEY))
                .withMessageContaining("s3Template");
    }

    @Test
    @DisplayName("constructor rejects a blank bucket / key coordinate")
    void constructorRejectsBlankCoordinates() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatementItemWriter(s3Template, "   ", TEXT_KEY, HTML_KEY))
                .withMessageContaining("statementsBucket");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatementItemWriter(s3Template, BUCKET, "", HTML_KEY))
                .withMessageContaining("textObjectKey");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatementItemWriter(s3Template, BUCKET, TEXT_KEY, "  "))
                .withMessageContaining("htmlObjectKey");
    }

    /**
     * Immutable holder for the two captured statement uploads.
     *
     * @param buckets     the bucket argument of each upload, in invocation order
     * @param keys        the object key of each upload, in invocation order
     * @param textBytes   raw payload of the {@code statements.ps} object
     * @param htmlBytes   raw payload of the {@code statements.html} object
     * @param textRecords decoded fixed-width text records
     * @param htmlRecords decoded fixed-width HTML records
     */
    private record CapturedUploads(
            List<String> buckets,
            List<String> keys,
            byte[] textBytes,
            byte[] htmlBytes,
            List<String> textRecords,
            List<String> htmlRecords) {
    }
}
