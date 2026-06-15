package com.cardemo.unit.batch.writers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.AccountStatement;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

/**
 * Fast, fully-mocked unit test for {@link StatementWriter} &mdash; the Spring Batch
 * {@code ItemWriter<AccountStatement>} that reproduces the <em>output side</em> of the legacy AWS
 * CardDemo statement-generation program {@code app/cbl/CBSTM03A.CBL}: the dual-format, one-statement-
 * per-account emission of a plain-text rendering ({@code STMT-FILE}, {@code FD-STMTFILE-REC PIC X(80)})
 * and an HTML rendering ({@code HTML-FILE}, {@code FD-HTMLFILE-REC PIC X(100)}), redirected from the two
 * shared sequential datasets to one pair of S3 objects per account.
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source is <strong>read-only reference</strong> at the frozen baseline commit SHA
 * {@code 27d6c6f}; it is never copied here and is referenced only by SHA and paragraph locator. Per the
 * Minimal Change Clause (AAP &sect;0.7.1) and the external-interface-contract preservation requirement
 * (AAP &sect;0.7.2), these tests assert that the writer persists the processor-built bodies
 * <strong>verbatim</strong> (byte-for-byte) and does not reformat, re-wrap, trim, or pad them. The base
 * package is {@code com.cardemo} (decision D-006).</p>
 *
 * <h2>The behaviours this test locks down</h2>
 * <ol>
 *   <li><strong>Dual-format emission</strong>: exactly two uploads per statement &mdash; a {@code .txt}
 *       ({@code text/plain}) and a {@code .html} ({@code text/html}) &mdash; to the config-resolved
 *       {@code carddemo-statements} bucket.</li>
 *   <li><strong>Deterministic, generation-prefixed keys</strong>:
 *       {@code statements/<yyyyMMdd>/<accountId>.txt} and {@code .html}, the {@code yyyyMMdd} taken from
 *       the injected {@link Clock} (idempotent &mdash; a retry overwrites).</li>
 *   <li><strong>Verbatim bodies + documented content types</strong>: the uploaded bytes equal the input
 *       text/HTML bodies exactly (UTF-8), and the {@link ObjectMetadata} carries the documented content
 *       type and the exact content length.</li>
 *   <li><strong>Defensive routing</strong>: a {@code null} item is skipped; an empty chunk is a no-op.</li>
 *   <li><strong>Write-error parity</strong>: an S3 failure propagates so the step fails (COBOL
 *       {@code ABEND}-on-write-error).</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>Pure unit test: {@code @ExtendWith(MockitoExtension.class)} with a mocked {@link S3Template}, a real
 * {@link AwsConfig.AwsResourceProperties} (its defaults expose the {@code carddemo-statements} bucket) and
 * a fixed {@link Clock} so the generation prefix ({@code 20240115}) is deterministic. No Spring context,
 * database, AWS, or Testcontainers. Mockito runs in default {@code STRICT_STUBS}; each test stubs only
 * what its path consumes. The {@link S3Template} mock does not consume the captured {@link InputStream},
 * so each captured stream is read back in full to assert the body was written verbatim.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementWriter — CBSTM03A STMTFILE/HTMLFILE output → dual S3 objects (SHA 27d6c6f)")
class StatementWriterTest {

    /** A recognisable 16-digit card number ({@code XREF-CARD-NUM}); not part of the key in this design. */
    private static final String CARD_NUM = "4111111111111111";

    /** Dedicated statements bucket exposed by the real {@link AwsConfig.AwsResourceProperties} defaults. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /**
     * Fixed instant ({@code 2024-01-15T10:20:30Z}) so the {@code yyyyMMdd} generation prefix of the S3
     * keys is deterministic ({@code 20240115}).
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-15T10:20:30Z"), ZoneOffset.UTC);

    @Mock
    private S3Template s3Template;

    /** Real (not mocked) name holder; defaults expose getS3().getStatementsBucket() == carddemo-statements. */
    private final AwsConfig.AwsResourceProperties awsProps = new AwsConfig.AwsResourceProperties();

    private StatementWriter writer;

    @BeforeEach
    void setUp() {
        writer = new StatementWriter(s3Template, awsProps, FIXED_CLOCK);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static AccountStatement statement(final long accountId, final String textBody, final String htmlBody) {
        return new AccountStatement(accountId, CARD_NUM, textBody, htmlBody, BigDecimal.ZERO);
    }

    /** Reads a captured upload stream fully as UTF-8 so the body can be asserted verbatim. */
    private static String readUtf8(final InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dual-format emission + deterministic keys + verbatim bodies
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Dual-format emission (one .txt + one .html per account)")
    class DualFormatEmission {

        @Test
        @DisplayName("one statement → exactly two uploads to carddemo-statements with the documented keys")
        void oneStatementTwoUploadsWithDeterministicKeys() {
            final AccountStatement stmt = statement(12345678901L, "PLAIN-TEXT-BODY", "<html>BODY</html>");

            writer.write(Chunk.of(stmt));

            final ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(s3Template, times(2)).upload(bucketCaptor.capture(), keyCaptor.capture(),
                    any(InputStream.class), any(ObjectMetadata.class));

            // Both objects go to the config-resolved dedicated statements bucket (never hardcoded).
            assertThat(bucketCaptor.getAllValues()).containsExactly(STATEMENTS_BUCKET, STATEMENTS_BUCKET);
            // Text first then HTML; generation prefix 20240115 from the fixed clock; keyed by account id.
            assertThat(keyCaptor.getAllValues()).containsExactly(
                    "statements/20240115/12345678901.txt",
                    "statements/20240115/12345678901.html");
        }

        @Test
        @DisplayName("uploaded bytes equal the input bodies exactly and carry the documented content types")
        void bodiesWrittenVerbatimWithContentTypes() {
            // Deliberately awkward content (fixed-width padding, newlines, markup) to prove no reformatting.
            final String textBody = "STATEMENT SUMMARY                     \n"
                    + "ACCT: 00012345678   BALANCE:        123.45\n";
            final String htmlBody = "<!DOCTYPE html>\n<html><body><pre>STATEMENT  </pre></body></html>\n";
            final AccountStatement stmt = statement(12345678901L, textBody, htmlBody);

            writer.write(Chunk.of(stmt));

            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
            final ArgumentCaptor<ObjectMetadata> metaCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
            verify(s3Template, times(2)).upload(any(String.class), keyCaptor.capture(),
                    streamCaptor.capture(), metaCaptor.capture());

            final List<String> keys = keyCaptor.getAllValues();
            final List<InputStream> streams = streamCaptor.getAllValues();
            final List<ObjectMetadata> metas = metaCaptor.getAllValues();

            // Index 0 = text object.
            assertThat(keys.get(0)).endsWith(".txt");
            assertThat(readUtf8(streams.get(0))).isEqualTo(textBody);
            assertThat(metas.get(0).getContentType()).isEqualTo("text/plain");
            assertThat(metas.get(0).getContentLength())
                    .isEqualTo((long) textBody.getBytes(StandardCharsets.UTF_8).length);

            // Index 1 = HTML object.
            assertThat(keys.get(1)).endsWith(".html");
            assertThat(readUtf8(streams.get(1))).isEqualTo(htmlBody);
            assertThat(metas.get(1).getContentType()).isEqualTo("text/html");
            assertThat(metas.get(1).getContentLength())
                    .isEqualTo((long) htmlBody.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Multiple statements in a chunk
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Multiple statements per chunk")
    class MultipleStatements {

        @Test
        @DisplayName("two statements → four uploads with per-account keys")
        void twoStatementsFourUploads() {
            final AccountStatement first = statement(1L, "T1", "<h1>1</h1>");
            final AccountStatement second = statement(2L, "T2", "<h1>2</h1>");

            writer.write(Chunk.of(first, second));

            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(s3Template, times(4)).upload(any(String.class), keyCaptor.capture(),
                    any(InputStream.class), any(ObjectMetadata.class));

            assertThat(keyCaptor.getAllValues()).containsExactly(
                    "statements/20240115/1.txt",
                    "statements/20240115/1.html",
                    "statements/20240115/2.txt",
                    "statements/20240115/2.html");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Defensive routing
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Defensive routing")
    class DefensiveRouting {

        @Test
        @DisplayName("a null item is skipped — only the valid statement is emitted")
        void nullItemSkipped() {
            final AccountStatement valid = statement(7L, "TEXT", "<p>html</p>");

            writer.write(Chunk.of(valid, null));

            // Only the valid statement's two objects are written; the null produces no upload.
            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(s3Template, times(2)).upload(any(String.class), keyCaptor.capture(),
                    any(InputStream.class), any(ObjectMetadata.class));
            assertThat(keyCaptor.getAllValues()).containsExactly(
                    "statements/20240115/7.txt",
                    "statements/20240115/7.html");
        }

        @Test
        @DisplayName("an empty chunk performs no upload")
        void emptyChunkNoOp() {
            writer.write(new Chunk<AccountStatement>());

            verifyNoInteractions(s3Template);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Write-error parity (COBOL ABEND on write error)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Write-error parity")
    class WriteErrorParity {

        @Test
        @DisplayName("an S3 upload failure propagates so the step fails (COBOL ABEND parity)")
        void s3FailurePropagates() {
            final AccountStatement stmt = statement(9L, "TEXT", "<p>html</p>");
            when(s3Template.upload(any(String.class), any(String.class),
                    any(InputStream.class), any(ObjectMetadata.class)))
                    .thenThrow(new RuntimeException("S3 unavailable"));

            assertThatThrownBy(() -> writer.write(Chunk.of(stmt)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("S3 unavailable");

            // Fails fast on the first (text) upload; the HTML upload is never attempted.
            verify(s3Template, times(1)).upload(any(String.class), any(String.class),
                    any(InputStream.class), any(ObjectMetadata.class));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Distinct-bucket contract — dedicated statements bucket, NEVER the batch-output bucket
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Distinct-bucket routing (carddemo-statements, never carddemo-batch-output)")
    class DistinctBucketContract {

        /**
         * Guards the dedicated-bucket contract (AAP &sect;0.7.2): both renderings are routed to the
         * config-resolved statements bucket ({@code carddemo-statements}) and <strong>never</strong> to
         * the {@code carddemo-batch-output} bucket that the transaction/reject writers use. The writer
         * reads the destination from
         * {@link AwsConfig.AwsResourceProperties#getS3()}{@code .getStatementsBucket()} (never a hardcoded
         * literal), so this test also documents that the two buckets are genuinely distinct in
         * configuration &mdash; protecting against a regression that mis-points the statement output at
         * the shared batch-output bucket. The COBOL {@code STMTFILE}/{@code HTMLFILE} datasets were
         * physically separate from the daily-posting output, so a distinct sink preserves that separation
         * (Minimal Change Clause, AAP &sect;0.7.1).
         */
        @Test
        @DisplayName("write_routesToStatementsBucketNotBatchOutput: both puts → statements bucket, never batch-output")
        void write_routesToStatementsBucketNotBatchOutput() {
            final String statementsBucket = awsProps.getS3().getStatementsBucket();
            final String batchOutputBucket = awsProps.getS3().getBatchOutputBucket();
            // The two buckets are a genuinely distinct contract, not aliases (config-regression guard).
            assertThat(statementsBucket).isEqualTo(STATEMENTS_BUCKET).isNotEqualTo(batchOutputBucket);

            writer.write(Chunk.of(statement(11111111111L, "TXT-BODY-VERBATIM", "<html>HTML-BODY-VERBATIM</html>")));

            // Capture the destination bucket of every upload (one .txt + one .html).
            final ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
            verify(s3Template, times(2)).upload(bucketCaptor.capture(), any(String.class),
                    any(InputStream.class), any(ObjectMetadata.class));

            // Positive: every upload targets the dedicated, config-resolved statements bucket.
            assertThat(bucketCaptor.getAllValues())
                    .hasSize(2)
                    .containsOnly(statementsBucket)
                    .doesNotContain(batchOutputBucket);

            // Negative guard: the writer NEVER uploads to the batch-output bucket.
            verify(s3Template, never()).upload(eq(batchOutputBucket), any(String.class),
                    any(InputStream.class), any(ObjectMetadata.class));
        }
    }
}
