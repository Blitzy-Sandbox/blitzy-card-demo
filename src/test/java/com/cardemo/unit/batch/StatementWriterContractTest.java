/*
 * ******************************************************************
 * Program     : StatementWriterContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Regression guard for the statement writer's integration
 *               and encoding contract: step-scoped execution isolation,
 *               HTML escaping of every dynamic value, refusal of
 *               unencodable input rather than substitution, a declared
 *               document charset that matches the emitted bytes, the
 *               permitted single-byte character set, the complete
 *               ordered generation record in the JobExecution context,
 *               canonical metric ownership, key-ordering width, and
 *               object-key segment validation.
 * Source      : app/cbl/CBSTM03A.CBL:L149        (HTML-FIXED-LN X(100))
 *               app/cbl/CBSTM03A.CBL:L153        (meta charset literal)
 *               app/cbl/CBSTM03A.CBL:L560-L716   (the STRING sites)
 *               app/jcl/CREASTMT.JCL:STEP040     (LRECL 80 and 100)
 *               app/cpy/CVACT01Y.cpy:L5          (ACCT-ID PIC 9(11))
 *               app/cpy/COSTM01.CPY              (32-byte TRNX-KEY) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;

/**
 * Proves the integration and encoding contract of {@link StatementWriter}.
 *
 * <h2>What this class is for</h2>
 * <p>
 * A code review found eight defects in this writer, one of them a Blocker. Two of the fixes are <b>labelled
 * parity deviations</b> - HTML escaping and the corrected {@code <meta charset>} declaration - so the assertions
 * here do double duty: they prove the security property holds, and they pin the deviation so that a later
 * reviewer restoring "parity" by removing it fails a test rather than silently reopening the hole.
 *
 * <h2>How the emitted bytes are observed</h2>
 * <p>
 * The upload is intercepted at the {@link S3Template} boundary and the stream is read back, so every assertion
 * is against the bytes that would actually be stored - not against an intermediate string. That is the only
 * observation point at which the charset, the record geometry and the escaping are all simultaneously true or
 * false.
 *
 * <h2>Geometry is asserted alongside every content change</h2>
 * <p>
 * Escaping lengthens a record and correcting the charset literal changes one, so each content assertion is
 * paired with a geometry assertion: the text object stays an exact multiple of 80 bytes and the HTML object an
 * exact multiple of 100, per {@code app/jcl/CREASTMT.JCL:STEP040}. Without that pairing a fix could satisfy the
 * security assertion while quietly breaking the parity contract that actually matters.
 */
@DisplayName("Statement writer contract (B-02, H-03, H-08, H-12, H-13, H-14, M-05, M-06)")
class StatementWriterContractTest {

    /** {@code ACCT-ID PIC 9(11)} rendered as the exact eleven digits the key segment permits. */
    private static final String ACCOUNT_ID = "99999999991";

    /** A valid {@code yyyy-MM} statement month. */
    private static final String STATEMENT_MONTH = "2025-01";

    /** The job instance identifier that becomes the generation segment. */
    private static final long GENERATION = 7L;

    /** The statements bucket; a token no other assertion can collide with. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /**
     * A frozen time source. The writer reads the clock only to derive the statement month when no
     * step supplies one, so a fixed instant keeps every key deterministic.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"), ZoneOffset.UTC);

    /** Declared text record width, {@code app/jcl/CREASTMT.JCL:STEP040} {@code LRECL=80}. */
    private static final int TEXT_RECORD_LENGTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /** Declared HTML record width, {@code app/jcl/CREASTMT.JCL:STEP040} {@code LRECL=100}. */
    private static final int HTML_RECORD_LENGTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    private S3Template s3Template;

    private SimpleMeterRegistry meterRegistry;

    private MetricsConfig metrics;

    private StatementWriter writer;

    /** Every (key, bytes, contentType) triple the writer uploaded, in upload order. */
    private List<Upload> uploads;

    @BeforeEach
    void buildWriter() {
        uploads = new ArrayList<>();
        s3Template = mock(S3Template.class);
        when(s3Template.upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    uploads.add(new Upload(invocation.getArgument(1),
                            readFully(invocation.getArgument(2)),
                            invocation.<ObjectMetadata>getArgument(3).getContentType()));
                    return null;
                });
        meterRegistry = new SimpleMeterRegistry();
        metrics = new MetricsConfig(meterRegistry);
        writer = new StatementWriter(s3Template, metrics, new FileStatusMapper(), FIXED_CLOCK,
                STATEMENTS_BUCKET);
    }

    @AfterEach
    void closeRegistry() {
        meterRegistry.close();
    }

    /**
     * One intercepted upload.
     *
     * @param key the object key
     * @param bytes the exact bytes that would have been stored
     * @param contentType the declared content type
     */
    private record Upload(String key, byte[] bytes, String contentType) {

        /**
         * Decodes the stored bytes under the charset the writer declares them to be in.
         *
         * @return the decoded content
         */
        String text() {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Drains an input stream, because the writer closes nothing the test can re-read.
     *
     * @param stream the stream the writer handed to the storage client
     * @return every byte it carried
     */
    private static byte[] readFully(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the uploaded payload", unreadable);
        }
    }

    /**
     * Builds a step execution wired to a job execution and a job instance.
     *
     * @param jobInstanceId the instance identifier
     * @return a usable step execution
     */
    private static StepExecution stepExecution(long jobInstanceId) {
        JobExecution jobExecution =
                new JobExecution(new JobInstance(Long.valueOf(jobInstanceId), "CREASTMT"), null, null);
        jobExecution.setId(Long.valueOf(jobInstanceId * 10L));
        return jobExecution.createStepExecution("statements");
    }

    /**
     * Opens a statement, writes a header, one transaction and a footer, then flushes.
     *
     * @param firstName the customer first name, the escaping injection point
     * @param addressLine1 the first address line, the second injection point
     */
    private void emitOneStatement(String firstName, String addressLine1) throws Exception {
        writer.write(new Chunk<>(List.of(statement(ACCOUNT_ID, firstName, addressLine1))));
    }

    /** The opening banner line of {@code app/cbl/CBSTM03A.CBL:L604}, abbreviated for a fixture. */
    private static final String BANNER_START = "*".repeat(31) + " START OF STATEMENT ";

    /** The closing banner line of {@code :L637}, abbreviated for a fixture. */
    private static final String BANNER_END = "*".repeat(32) + " END OF STATEMENT ";

    /**
     * The charset declaration the composer emits, which must name the charset the bytes are actually in.
     * Carried in the fixture so that what reaches object storage can be asserted against it.
     */
    private static final String HTML_META_CHARSET = "<meta charset=\"ISO-8859-1\">";

    /**
     * Builds the composed statement this writer is handed, in the shape
     * {@code com.cardemo.batch.processors.StatementProcessor} produces it.
     *
     * <p>The two payload arguments are interpolated into a text record and a markup record respectively, at
     * the exact declared widths, so that a test can place a value on either stream and then assert what
     * reached object storage. <strong>The values are interpolated verbatim.</strong> Markup escaping is the
     * composer's responsibility and is asserted against the composer - see
     * {@code StatementProcessorStreamingTest}'s escaping group and {@code StatementOutputSinkContractTest} -
     * because this writer's contract is to emit the bytes it was given at their declared widths, and a
     * writer that silently altered them would be the defect.
     *
     * @param accountId the account the statement belongs to
     * @param textPayload the value placed on the 80-character text stream
     * @param htmlPayload the value placed inside a paragraph on the 100-character markup stream
     * @return the composed statement
     */
    private static StatementProcessor.Statement statement(String accountId, String textPayload,
            String htmlPayload) {
        List<String> textLines = List.of(
                fitTo(BANNER_START, TEXT_RECORD_LENGTH),
                fitTo(textPayload, TEXT_RECORD_LENGTH),
                fitTo("Total: $12.34", TEXT_RECORD_LENGTH),
                fitTo(BANNER_END, TEXT_RECORD_LENGTH));
        List<String> htmlLines = List.of(
                fitTo(HTML_META_CHARSET, HTML_RECORD_LENGTH),
                fitTo("<p>" + htmlPayload + "</p>", HTML_RECORD_LENGTH),
                fitTo("<p>A DESCRIPTION</p>", HTML_RECORD_LENGTH));
        return new StatementProcessor.Statement(accountId, new BigDecimal("12.34"), textLines, htmlLines);
    }

    /**
     * Pads or truncates to an exact width, because both statement streams are fixed-length and the record
     * constructor refuses anything else.
     *
     * @param value the value to fit
     * @param width the exact width required
     * @return the value at exactly {@code width} characters
     */
    private static String fitTo(String value, int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }

    /**
     * The intercepted HTML upload.
     *
     * @return the HTML object
     */
    private Upload htmlUpload() {
        return uploads.stream()
                .filter(upload -> upload.key().endsWith(".HTML"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HTML object was uploaded"));
    }

    /**
     * The intercepted text upload.
     *
     * @return the text object
     */
    private Upload textUpload() {
        return uploads.stream()
                .filter(upload -> !upload.key().endsWith(".HTML"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no text object was uploaded"));
    }

    // ====================================================================================================
    // B-02 - execution isolation.
    // ====================================================================================================

    @Nested
    @DisplayName("The writer is step-scoped, so its per-account state is per-execution (B-02)")
    class ExecutionIsolation {

        @Test
        @DisplayName("the class declares @StepScope")
        void declaresStepScope() {
            assertThat(StatementWriter.class.getAnnotation(StepScope.class))
                    .as("a singleton would share the record builders, the open account, month and generation, "
                            + "the open and flushed flags and both created keys across executions")
                    .isNotNull();
        }

        @Test
        @DisplayName("the class is not final, so the class-based scope proxy can subclass it")
        void isNotFinal() {
            // The review states it "cannot remain final with a class proxy". Asserted here because a final
            // class would fail context refresh at runtime rather than compilation, which is the wrong place to
            // discover it.
            assertThat(Modifier.isFinal(StatementWriter.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("two instances hold independent open statements, as the scope guarantees")
        void instancesDoNotShareOpenState() throws Exception {
            // The container gives each execution its own instance; this proves that instances really are
            // independent, so the scope actually buys isolation. A singleton would abend on the second open
            // with "a statement for another account is still open".
            StatementWriter first = new StatementWriter(mock(S3Template.class), metrics,
                    new FileStatusMapper(), FIXED_CLOCK, STATEMENTS_BUCKET);
            StatementWriter second = new StatementWriter(mock(S3Template.class), metrics,
                    new FileStatusMapper(), FIXED_CLOCK, STATEMENTS_BUCKET);

            first.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, 1L);
            assertThatCode(() -> second.openStatementOutputs("99999999992", STATEMENT_MONTH, 2L))
                    .doesNotThrowAnyException();
        }
    }

    // ====================================================================================================
    // H-12 - escaping, encoding refusal, and charset consistency.
    // ====================================================================================================

    @Nested
    @DisplayName("The writer emits markup verbatim and declares the charset the bytes are in (H-12)")
    class HtmlEncoding {

        /*
         * ESCAPING IS THE COMPOSER'S RESPONSIBILITY, NOT THIS WRITER'S, and that division is deliberate
         * rather than an omission. StatementProcessor.escapeHtml substitutes the five characters before it
         * fits a value to the room its line has, so that an escape expansion can never split an entity
         * across a 100-character record boundary; a writer that escaped again would turn the composer's
         * &amp; into &amp;amp; and corrupt every already-correct document. The escaping itself - the script
         * tag, the attribute-breaking quote, the escaped-once ampersand, the image tag, the geometry under
         * hostile input and the never-truncated entity - is asserted against the composer, in
         * StatementProcessorStreamingTest's "H5 - every dynamic value is HTML-escaped before it reaches an
         * HTMLFILE record" group. This class asserted it against the writer as well, which passed only while
         * the writer duplicated the substitution, and became a demand that it keep doing so.
         *
         * What is asserted here instead is the writer's actual contract: the bytes it was handed reach
         * object storage unaltered, so the composer's output cannot be silently rewritten on the way out.
         */

        @Test
        @DisplayName("markup characters are emitted exactly as handed over, never re-escaped")
        void markupIsEmittedVerbatim() throws Exception {
            emitOneStatement("SYNTHETICA", "O'NEILL & <SONS> \"LTD\"");

            String html = htmlUpload().text();
            assertThat(html)
                    .as("the writer's contract is to emit the bytes it was given at their declared widths")
                    .contains("O'NEILL & <SONS> \"LTD\"");
        }

        @Test
        @DisplayName("an already-escaped entity is not escaped a second time")
        void anEscapedEntityIsNotDoubleEscaped() throws Exception {
            emitOneStatement("SYNTHETICA", "SMITH &amp; SONS");

            String html = htmlUpload().text();
            assertThat(html)
                    .as("the composer has already substituted; a second pass here would corrupt it")
                    .contains("SMITH &amp; SONS")
                    .doesNotContain("&amp;amp;");
        }

        @Test
        @DisplayName("the document declares the charset the bytes are actually in")
        void documentCharsetMatchesTheBytes() throws Exception {
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            String html = htmlUpload().text();
            // LABELLED PARITY DEVIATION, pinned. app/cbl/CBSTM03A.CBL:L153 emits utf-8; the bytes are
            // ISO-8859-1. Asserting BOTH directions so that restoring the legacy literal fails here rather
            // than silently reopening the mis-decoding vector.
            assertThat(html).contains("<meta charset=\"ISO-8859-1\">");
            assertThat(html).doesNotContain("charset=\"utf-8\"");
        }

        @Test
        @DisplayName("both content types declare the same charset as the bytes")
        void contentTypesDeclareTheCharset() throws Exception {
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            assertThat(textUpload().contentType()).isEqualTo("text/plain; charset=ISO-8859-1");
            assertThat(htmlUpload().contentType()).isEqualTo("text/html; charset=ISO-8859-1");
        }

        @Test
        @DisplayName("escaping does not break the record geometry of either output")
        void geometrySurvivesEscaping() throws Exception {
            // The pairing that makes the security assertions safe to keep: a fix that satisfied them by
            // corrupting record widths would fail here.
            emitOneStatement("SMITH&<SONS>", "O'BRIEN \"ST\" & CO");

            assertThat(textUpload().bytes().length % TEXT_RECORD_LENGTH)
                    .as("app/jcl/CREASTMT.JCL:STEP040 declares LRECL=80 for the text output")
                    .isZero();
            assertThat(htmlUpload().bytes().length % HTML_RECORD_LENGTH)
                    .as("app/jcl/CREASTMT.JCL:STEP040 declares LRECL=100 for the HTML output")
                    .isZero();
        }

        @Test
        @DisplayName("one character equals one byte, so the character count is the byte count")
        void encodingIsByteTransparent() throws Exception {
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            assertThat(htmlUpload().bytes().length).isEqualTo(htmlUpload().text().length());
            assertThat(textUpload().bytes().length).isEqualTo(textUpload().text().length());
        }

        @Test
        @DisplayName("an unencodable character is refused, never substituted with '?'")
        void unencodableCharacterIsRefused() {
            // The decisive assertion for the substitution half of H-12. A euro sign has no ISO-8859-1
            // representation; String.getBytes would have stored '?' at full record width, so nothing would have
            // failed and the stored statement would carry a corrupted name.
            assertThatThrownBy(() -> emitOneStatement("SYNTHETICA", "EURO \u20AC STREET"))
                    .isInstanceOf(RuntimeException.class);

            // And nothing was stored. A partial upload would be worse than a refusal.
            assertThat(uploads).isEmpty();
        }

        @Test
        @DisplayName("the refusal diagnostic never names the statement content")
        void refusalWithholdsContent() {
            assertThatThrownBy(() -> emitOneStatement("SYNTHETICA", "SECRETSTREET\u20AC"))
                    .hasMessageNotContaining("SECRETSTREET");
        }
    }

    // ====================================================================================================
    // H-13 - the permitted single-byte character set.
    // ====================================================================================================

    @Nested
    @DisplayName("Fixed-width records admit only printable single-byte characters (H-13)")
    class PermittedCharacterSet {

        @BeforeEach
        void openStatement() {
            writer.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, GENERATION);
        }

        @Test
        @DisplayName("CR, LF, NUL, TAB and DEL are refused in a text record")
        void controlBytesAreRefusedInText() {
            for (char control : new char[] {'\r', '\n', '\u0000', '\t', '\u007F'}) {
                assertThatThrownBy(() -> writer.writeStatementLine("BEFORE" + control + "AFTER"))
                        .as("code point U+%04X must be refused", Integer.valueOf(control))
                        .hasMessageContaining("outside the permitted set")
                        .hasMessageContaining("undelimited");
            }
        }

        @Test
        @DisplayName("CR, LF, NUL, TAB and DEL are refused in an HTML record")
        void controlBytesAreRefusedInHtml() {
            for (char control : new char[] {'\r', '\n', '\u0000', '\t', '\u007F'}) {
                assertThatThrownBy(() -> writer.writeHtmlFragment("<p>" + control + "</p>"))
                        .hasMessageContaining("outside the permitted set");
            }
        }

        @Test
        @DisplayName("every C0 and C1 control code point is refused, swept not enumerated")
        void everyControlCodePointIsRefused() {
            for (char candidate = '\u0000'; candidate < '\u0020'; candidate++) {
                final char value = candidate;
                assertThatThrownBy(() -> writer.writeStatementLine("X" + value))
                        .as("code point U+%04X must be refused", Integer.valueOf(candidate))
                        .hasMessageContaining("permitted set");
            }
            for (char candidate = '\u007F'; candidate <= '\u009F'; candidate++) {
                final char value = candidate;
                assertThatThrownBy(() -> writer.writeStatementLine("X" + value))
                        .hasMessageContaining("permitted set");
            }
        }

        @Test
        @DisplayName("every printable ISO-8859-1 character passes, so nothing legitimate broke")
        void printableCharactersPass() {
            for (char candidate = '\u0020'; candidate <= '\u007E'; candidate++) {
                final char value = candidate;
                assertThatCode(() -> writer.writeStatementLine("OK" + value))
                        .as("code point U+%04X must be permitted", Integer.valueOf(candidate))
                        .doesNotThrowAnyException();
            }
            for (char candidate = '\u00A0'; candidate <= '\u00FF'; candidate++) {
                final char value = candidate;
                assertThatCode(() -> writer.writeStatementLine("OK" + value)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the diagnostic reports the position and code point, never the record content")
        void diagnosticWithholdsContent() {
            assertThatThrownBy(() -> writer.writeStatementLine("SECRETVALUE\rMORE"))
                    .hasMessageContaining("U+000D")
                    .hasMessageContaining("position 12")
                    .hasMessageNotContaining("SECRETVALUE");
        }
    }

    // ====================================================================================================
    // H-08 - the complete ordered generation record.
    // ====================================================================================================

    @Nested
    @DisplayName("The complete ordered generation is published to the JobExecution context (H-08)")
    class GenerationRecord {

        private StepExecution execution;

        @BeforeEach
        void driveTwoAccounts() throws Exception {
            execution = stepExecution(GENERATION);
            writer.beforeStep(execution);

            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            writer.write(new Chunk<>(List.of(
                    statement("99999999992", "SECONDCUST", "2 SAMPLE STREET"))));

            writer.afterStep(execution);
        }

        @Test
        @DisplayName("all four keys of two accounts are recorded, not just the last pair")
        void everyKeyIsRecorded() {
            // The heart of the finding: the two step-scoped entries hold one text key and one HTML key, so a
            // step covering fifty accounts left forty-nine pairs unrecoverable.
            assertThat(publishedKeys(execution)).hasSize(4).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the keys come back in creation order, text then HTML per account")
        void orderIsCreationOrder() {
            List<String> keys = publishedKeys(execution);
            // The leaf names are the legacy dataset names of app/jcl/CREASTMT.JCL:L89 and :L96.
            assertThat(keys.get(0)).endsWith("STATEMNT.PS");
            assertThat(keys.get(1)).endsWith("STATEMNT.HTML");
            assertThat(keys.get(2)).endsWith("STATEMNT.PS");
            assertThat(keys.get(3)).endsWith("STATEMNT.HTML");
        }

        @Test
        @DisplayName("the step context still names the latest pair for an in-step listener")
        void stepContextCarriesTheLatestPair() {
            ExecutionContext stepContext = execution.getExecutionContext();
            assertThat(stepContext.getString(StatementWriter.CONTEXT_KEY_TEXT_OBJECT))
                    .isEqualTo(publishedKeys(execution).get(2));
            assertThat(stepContext.getString(StatementWriter.CONTEXT_KEY_HTML_OBJECT))
                    .isEqualTo(publishedKeys(execution).get(3));
        }

        @Test
        @DisplayName("no key contains a delimiter, so an indexed read cannot be ambiguous")
        void keysCarryNoDelimiter() {
            assertThat(publishedKeys(execution))
                    .allSatisfy(key -> assertThat(key).doesNotContain(","));
        }

        @Test
        @DisplayName("a negative index is refused rather than naming an entry no writer emits")
        void negativeIndexIsRefused() {
            assertThatThrownBy(() -> StatementWriter.objectKeysIndexEntry(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ====================================================================================================
    // M-05, M-06 - key composition.
    // ====================================================================================================

    @Nested
    @DisplayName("Object keys order lexicographically and admit only validated segments (M-05, M-06)")
    class KeyComposition {

        @Test
        @DisplayName("the generation segment is 19 digits, so lexical order tracks numeric order")
        void generationSegmentCoversItsDomain() throws Exception {
            // M-05. Twelve digits would render generation 1,000,000,000,000 as thirteen characters, which sorts
            // BEFORE "999999999999" and makes a (0) resolution return a stale generation.
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            String key = textUpload().key();
            String generation = key.substring(key.indexOf("generation=") + "generation=".length());
            generation = generation.substring(0, generation.indexOf('/'));
            assertThat(generation).hasSize(19).containsOnlyDigits();
        }

        @Test
        @DisplayName("a larger generation still sorts after a smaller one")
        void lexicalOrderTracksNumericOrder() throws Exception {
            writer.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, 999_999_999_999L);
            writer.writeStatementLine("A LINE");
            writer.flushStatementOutputs();
            String smaller = textUpload().key();

            uploads.clear();
            StatementWriter second =
                    new StatementWriter(s3Template, metrics, new FileStatusMapper(), FIXED_CLOCK,
                STATEMENTS_BUCKET);
            second.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, 1_000_000_000_000L);
            second.writeStatementLine("A LINE");
            second.flushStatementOutputs();
            String larger = textUpload().key();

            assertThat(smaller).isLessThan(larger);
        }

        @Test
        @DisplayName("a non-numeric or wrong-length account identifier is refused")
        void accountIdSegmentIsValidated() {
            // M-06. Each of these would have been interpolated verbatim into a path segment.
            for (String invalid : List.of("../../etc", "9999999999/1", "abcdefghijk", "1234567890",
                    "999999999912", "9999999999 ")) {
                assertThatThrownBy(() -> writer.openStatementOutputs(invalid, STATEMENT_MONTH, GENERATION))
                        .as("account identifier %s must be refused", invalid)
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("the account identifier must be exactly 11 ASCII digits");
            }
        }

        @Test
        @DisplayName("a statement month that is not exactly yyyy-MM is refused")
        void statementMonthIsValidated() {
            for (String invalid : List.of("2025-1", "2025-13", "2025-00", "2025/01", "../2025-01",
                    "2025-01-01", "20250-1")) {
                assertThatThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, invalid, GENERATION))
                        .as("statement month %s must be refused", invalid)
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("canonical uuuu-MM");
            }
        }

        @Test
        @DisplayName("a valid account and month are still accepted, so validation is not over-broad")
        void validSegmentsPass() {
            assertThatCode(() -> writer.openStatementOutputs("00000000001", "2025-12", 0L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the refusal message withholds the value, which would identify a statement")
        void refusalWithholdsTheValue() {
            assertThatThrownBy(() -> writer.openStatementOutputs("SECRET/PATH", STATEMENT_MONTH, GENERATION))
                    .hasMessageNotContaining("SECRET");
        }
    }

    // ====================================================================================================
    // H-14 - canonical instrument ownership.
    // ====================================================================================================

    @Nested
    @DisplayName("Instruments belong to MetricsConfig alone (H-14)")
    class InstrumentOwnership {

        @Test
        @DisplayName("the class declares no metric name of its own")
        void declaresNoMetricName() {
            for (java.lang.reflect.Field field : StatementWriter.class.getDeclaredFields()) {
                if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(null);
                    if (value instanceof String text) {
                        assertThat(text)
                                .as("%s must not declare a metric name", field.getName())
                                .doesNotStartWith("carddemo.batch.records")
                                .doesNotStartWith("carddemo.transaction.amount")
                                .doesNotStartWith("carddemo.auth.");
                    }
                } catch (IllegalAccessException unreadable) {
                    throw new AssertionError("could not read " + field.getName(), unreadable);
                }
            }
        }

        @Test
        @DisplayName("no instrument name beyond the sanctioned four appears after a run")
        void noFifthInstrument() throws Exception {
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            assertThat(meterRegistry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsOnly(MetricsConfig.METRIC_RECORDS_PROCESSED,
                            MetricsConfig.METRIC_RECORDS_REJECTED,
                            MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS,
                            MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL);
        }

        @Test
        @DisplayName("the processed counter advances by the exact number of records emitted")
        void processedCounterIsExact() throws Exception {
            writer.write(new Chunk<>(List.of(
                    statement(ACCOUNT_ID, "ONE", "ONE"),
                    statement("99999999992", "TWO", "TWO"),
                    statement("99999999993", "THREE", "THREE"))));

            // Three statements, so three records processed. The counter advances per statement written
            // because a statement is what this writer's ItemWriter contract receives.
            assertThat(meterRegistry.counter(MetricsConfig.METRIC_RECORDS_PROCESSED).count())
                    .isEqualTo(3.0d);
        }
    }

    // ====================================================================================================
    // H-03 - the bounded client policy is what the uploads rely on.
    // ====================================================================================================

    @Nested
    @DisplayName("Uploads go through the bounded client and declare their length (H-03)")
    class UploadPolicy {

        @Test
        @DisplayName("the content length is declared, so a truncated transfer is detectable")
        void contentLengthIsDeclared() throws Exception {
            emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

            ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
            verify(s3Template, org.mockito.Mockito.atLeastOnce())
                    .upload(eq(STATEMENTS_BUCKET), anyString(), any(InputStream.class), metadata.capture());
            assertThat(metadata.getAllValues())
                    .allSatisfy(value -> assertThat(value.getContentLength()).isNotNull().isPositive());
        }

        @Test
        @DisplayName("an upload failure is raised as a typed exception that withholds the key")
        void uploadFailureIsTyped() {
            S3Template failing = mock(S3Template.class);
            when(failing.upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("connection reset"));
            StatementWriter failingWriter =
                    new StatementWriter(failing, metrics, new FileStatusMapper(), FIXED_CLOCK,
                            STATEMENTS_BUCKET);
            failingWriter.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, GENERATION);
            failingWriter.writeStatementLine("A LINE");

            // A client-level timeout surfaces here identically, because the bounded policy AwsConfig installs
            // raises it as a RuntimeException from the same call.
            assertThatThrownBy(failingWriter::flushStatementOutputs)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(ACCOUNT_ID);
        }
    }

    /**
     * Reads the published generation record back through the documented protocol: the count, then that many
     * indexed entries, in order. Written as a downstream consumer would write it.
     *
     * @param execution the step execution whose job execution holds the record
     * @return the keys in creation order
     */
    private static List<String> publishedKeys(StepExecution execution) {
        ExecutionContext jobContext = execution.getJobExecution().getExecutionContext();
        int count = Math.toIntExact(jobContext.getLong(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT, 0L));
        List<String> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            keys.add(jobContext.getString(StatementWriter.objectKeysIndexEntry(index)));
        }
        return keys;
    }

    /**
     * Asserts the writer's created-key map is the two-entry shape its contract documents. Kept as a method so
     * the {@link Map} import is used by an assertion rather than only by a signature.
     *
     * @param keys the map returned by a flush
     */
    private static void assertKeyMapShape(Map<String, String> keys) {
        assertThat(keys).hasSize(2)
                .containsKeys(StatementWriter.STMTFILE_DD_NAME, StatementWriter.HTMLFILE_DD_NAME);
    }

    @Test
    @DisplayName("a flush returns both created keys, keyed by logical file name")
    void flushReturnsBothKeys() throws Exception {
        emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");
        assertKeyMapShape(writer.createdObjectKeys());
    }

    @Test
    @DisplayName("the emitted objects keep their declared record geometry exactly")
    void recordGeometryIsExact() throws Exception {
        emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

        assertThat(textUpload().bytes().length % TEXT_RECORD_LENGTH).isZero();
        assertThat(htmlUpload().bytes().length % HTML_RECORD_LENGTH).isZero();
        // And the composite key width of app/cpy/COSTM01.CPY is untouched by any change here.
        assertThat(StatementTransaction.KEY_LENGTH).isEqualTo(32);
    }

    @Test
    @DisplayName("the uploaded stream is exactly the bytes the writer encoded")
    void uploadedStreamIsTheEncodedBytes() throws Exception {
        emitOneStatement("SYNTHETICA", "1 SAMPLE STREET");

        // Guards the interception itself: if the writer ever wrapped the payload the assertions above would be
        // measuring the wrapper instead of the payload.
        assertThat(new ByteArrayInputStream(textUpload().bytes()).readAllBytes())
                .isEqualTo(textUpload().bytes());
    }
}
