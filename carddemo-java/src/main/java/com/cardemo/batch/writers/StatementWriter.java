package com.cardemo.batch.writers;

import com.cardemo.config.AwsConfig;
import com.cardemo.model.dto.AccountStatement;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that persists the dual-format account statements of the Statement
 * Generation job &mdash; the Java translation of the <strong>output side</strong> of the legacy AWS
 * CardDemo batch program {@code app/cbl/CBSTM03A.CBL} (the Statement Generator).
 *
 * <h2>COBOL provenance (read-only reference, never copied)</h2>
 * <p>In {@code CBSTM03A} the main read loop ({@code 1000-MAINLINE}) walks the {@code CARDXREF} file
 * and, for <em>each</em> card cross-reference / account, assembles and writes <strong>two</strong>
 * outputs in lock-step:</p>
 * <ul>
 *   <li>a fixed-width <strong>plain-text</strong> statement written to {@code STMT-FILE}
 *       (FILE-CONTROL {@code SELECT STMT-FILE ASSIGN TO STMTFILE}; FD record
 *       {@code FD-STMTFILE-REC PIC X(80)}), built by {@code 5000-CREATE-STATEMENT} via the
 *       {@code WRITE FD-STMTFILE-REC FROM ST-LINE0..15} sequence and {@code 6000-WRITE-TRANS}; and</li>
 *   <li>a fixed-width <strong>HTML</strong> statement written to {@code HTML-FILE}
 *       ({@code SELECT HTML-FILE ASSIGN TO HTMLFILE}; FD record {@code FD-HTMLFILE-REC PIC X(100)}),
 *       built by {@code 5100-WRITE-HTML-HEADER} / {@code 5200-WRITE-HTML-NMADBS} and
 *       {@code 6000-WRITE-TRANS} via {@code WRITE FD-HTMLFILE-REC FROM HTML-LINES}.</li>
 * </ul>
 * <p>The legacy program opens both files once ({@code OPEN OUTPUT STMT-FILE HTML-FILE}), appends every
 * account's records to the two shared sequential datasets, and closes them once
 * ({@code CLOSE STMT-FILE HTML-FILE}). Traceability is to the frozen COBOL baseline at commit SHA
 * {@code 27d6c6f} only; the COBOL source is read-only reference material and is <strong>never
 * copied</strong> into this repository.</p>
 *
 * <h2>Division of responsibility (what this writer does NOT do)</h2>
 * <p>The migrated Spring Batch chunk splits the single COBOL program into a <em>processor</em> and a
 * <em>writer</em>. The {@code StatementProcessor} (a {@code batch/processors} component) owns all
 * <strong>formatting</strong>: it reads {@code XREF}&rarr;{@code CUST}&rarr;{@code ACCT}&rarr;
 * transactions, accumulates the per-account total ({@code WS-TOTAL-AMT}), and assembles the complete
 * text body ({@code ST-LINE*}) and HTML body ({@code HTML-LINES}) into an immutable
 * {@link AccountStatement} carrier. This {@code StatementWriter} is purely the
 * <strong>emission/persistence</strong> side: it receives the already-formatted bodies (one
 * {@link AccountStatement} per account/card) and writes them out unchanged. It deliberately performs
 * <strong>no</strong> statement layout, <strong>no</strong> account/customer/transaction reads, and
 * calls <strong>no</strong> file-service; it imports nothing from {@code com.cardemo.batch.processors},
 * {@code .readers}, or {@code .jobs} (folder layering rule).</p>
 *
 * <h2>Technology substitution (AAP &sect;0.7.1 / &sect;0.1.2 / &sect;0.7.7 &mdash; documented at the
 * point of change)</h2>
 * <ul>
 *   <li><strong>Sequential {@code STMTFILE} (PS, 80-char) {@code WRITE FD-STMTFILE-REC}</strong>
 *       &rarr; one <strong>S3 object</strong> per account carrying the verbatim text body
 *       ({@code text/plain}).</li>
 *   <li><strong>Sequential {@code HTMLFILE} (PS, 100-char) {@code WRITE FD-HTMLFILE-REC}</strong>
 *       &rarr; one <strong>S3 object</strong> per account carrying the verbatim HTML body
 *       ({@code text/html}).</li>
 *   <li><strong>{@code OPEN OUTPUT ... / CLOSE ...} dataset lifecycle</strong> &rarr; per-item
 *       {@link S3Template} {@code upload(...)} calls; S3 needs no explicit open/close handshake, so the
 *       single-open/single-close COBOL bracket has no analogue and is intentionally omitted.</li>
 * </ul>
 * <p>Because the COBOL appended every statement to one shared file, statements were not individually
 * addressable. In the target, each account's text and HTML renderings are written to their own,
 * deterministically named S3 objects in the dedicated statements bucket so they become individually
 * addressable while preserving the dual-format, one-statement-per-account behaviour exactly.</p>
 *
 * <h2>External interface contract (AAP &sect;0.7.2)</h2>
 * <p>The statement text and HTML <em>content and format</em> &mdash; the lines and their widths as
 * produced by {@code StatementProcessor} &mdash; are preserved <strong>exactly</strong>. This writer
 * does not reformat, re-wrap, trim, pad, or otherwise alter the bodies: each body is serialized to
 * bytes verbatim using a single deterministic charset ({@link StandardCharsets#UTF_8}) and uploaded
 * unchanged. The content types are fixed and documented: {@code text/plain} for the text rendering and
 * {@code text/html} for the HTML rendering.</p>
 *
 * <h2>S3 destination (config-resolved, LocalStack-verifiable, zero live AWS)</h2>
 * <p>The destination bucket is resolved from configuration
 * ({@link AwsConfig.AwsResourceProperties#getS3()}{@code .getStatementsBucket()} &rarr;
 * {@code carddemo-statements}) and is <strong>never</strong> hardcoded; the dedicated statements bucket
 * is used (not the batch-output bucket). Uploads go through the Spring Cloud AWS
 * auto-configured {@link S3Template}, so the endpoint can only ever resolve to LocalStack (zero live
 * AWS, no hand-built client, no endpoint/region literals here). Two objects are written per statement
 * under deterministic, generation-prefixed keys:</p>
 * <pre>
 *   text: statements/&lt;generation&gt;/&lt;accountId&gt;.txt
 *   html: statements/&lt;generation&gt;/&lt;accountId&gt;.html
 * </pre>
 * <p>The {@code <generation>} segment ({@code yyyyMMdd}, derived from the injectable {@link Clock})
 * emulates a GDG generation bucket and makes the keys stable for a given processing day, so a Spring
 * Batch retry of the same chunk re-emits the same keys and <strong>overwrites</strong> rather than
 * duplicating (S3 is not transactional; the idempotent key yields at-least-once, last-write-wins
 * semantics). An orchestrator-supplied business date or job-instance id could be substituted for the
 * generation segment in future without changing this contract.</p>
 *
 * <h2>Data protection &mdash; private statement-bucket access model (sensitive PII)</h2>
 * <p>The objects written here are <strong>account statements containing customer and account financial
 * PII</strong> (customer name and address, account id, balances, and the per-account transaction
 * detail assembled upstream by {@code StatementProcessor}). The {@code carddemo-statements} bucket is a
 * <strong>private, non-public</strong> data store and MUST be provisioned accordingly. This writer sets
 * only per-object metadata (content type and length); <em>bucket-level access control is an
 * infrastructure/provisioning concern</em>, owned outside this class, and the required posture is:</p>
 * <ul>
 *   <li><strong>Block all public access</strong> &mdash; an S3 <em>public-access-block</em> with all
 *       four flags enabled ({@code BlockPublicAcls}, {@code IgnorePublicAcls}, {@code BlockPublicPolicy},
 *       {@code RestrictPublicBuckets}); no public ACL or bucket policy may ever expose these objects.</li>
 *   <li><strong>Encryption at rest</strong> &mdash; default server-side encryption enabled on the
 *       bucket (SSE-S3 {@code AES256} at minimum, SSE-KMS with a customer-managed key preferred in
 *       production), so statement content is encrypted without relying on per-request headers.</li>
 *   <li><strong>Least-privilege access</strong> &mdash; only the batch application's IAM principal may
 *       write, and only explicitly authorized principals may read; no broad/wildcard grants.</li>
 *   <li><strong>Encryption in transit</strong> &mdash; TLS-only access (e.g. an {@code aws:SecureTransport}
 *       deny on non-TLS requests) in production.</li>
 * </ul>
 * <p><strong>Local vs. production.</strong> For local development the public-access-block and default
 * encryption are applied to {@code carddemo-statements} by {@code localstack-init/init-aws.sh} when the
 * bucket is created, so the LocalStack-backed runtime mirrors the private posture (verification only,
 * zero live AWS). In a real deployment these controls MUST be enforced by the infrastructure-as-code
 * that provisions the bucket (public-access-block, default SSE/KMS, bucket policy, and least-privilege
 * IAM) &mdash; the application deliberately does not, and must not, manage bucket-level security at
 * runtime. The {@code *IT} integration tests create their own test-owned buckets via Testcontainers/
 * LocalStack and tear them down, so they neither read from nor depend on a publicly accessible bucket.</p>
 *
 * <h2>Error handling (COBOL write-error parity)</h2>
 * <p>If an upload fails, the {@link S3Template} exception is allowed to propagate so the Spring Batch
 * step fails, mirroring the {@code ABEND}-on-write-error behaviour of the COBOL sequential
 * {@code WRITE}. No exception is swallowed and no partial-success state is reported as success.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The writer is stateless apart from its injected, immutable collaborators (the {@link S3Template},
 * the {@link AwsConfig.AwsResourceProperties} name holder, and a {@link Clock}); all mutable state is
 * confined to {@link #write(Chunk)} locals, so a single Spring-managed singleton is safe to share
 * across batch threads.</p>
 *
 * @see AccountStatement
 * @see ItemWriter
 * @see S3Template
 * @see AwsConfig.AwsResourceProperties
 */
@Component("statementWriter")
public class StatementWriter implements ItemWriter<AccountStatement> {

    /**
     * Generation-prefix date pattern for the S3 statement keys ({@code yyyyMMdd}), emulating a GDG
     * generation bucket so keys are stable per processing day and a retry overwrites rather than
     * duplicates.
     */
    private static final DateTimeFormatter S3_GENERATION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Fixed key prefix under which generated statements are stored (PS {@code STMTFILE}/{@code HTMLFILE} &rarr; S3). */
    private static final String S3_KEY_PREFIX = "statements/";

    /** Suffix for the plain-text statement object (COBOL {@code STMT-FILE}, {@code FD-STMTFILE-REC PIC X(80)}). */
    private static final String TEXT_OBJECT_SUFFIX = ".txt";

    /** Suffix for the HTML statement object (COBOL {@code HTML-FILE}, {@code FD-HTMLFILE-REC PIC X(100)}). */
    private static final String HTML_OBJECT_SUFFIX = ".html";

    /** Content type of the plain-text statement object; fixed and documented (AAP &sect;0.7.2). */
    private static final String TEXT_CONTENT_TYPE = "text/plain";

    /** Content type of the HTML statement object; fixed and documented (AAP &sect;0.7.2). */
    private static final String HTML_CONTENT_TYPE = "text/html";

    /**
     * Spring Cloud AWS S3 abstraction used to persist both statement renderings. Auto-configured by
     * {@code spring-cloud-aws-starter-s3} from {@code spring.cloud.aws.*}; never hand-built here, so the
     * endpoint can only ever resolve to LocalStack (zero live AWS).
     */
    private final S3Template s3Template;

    /**
     * Strongly-typed holder of the application-owned AWS resource <em>names</em>. The destination bucket
     * is read from {@code getS3().getStatementsBucket()} ({@code carddemo-statements}); the bucket name
     * is therefore resolved from configuration and never hardcoded (AAP &sect;0.7.7).
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Clock backing the {@code yyyyMMdd} generation prefix of the S3 keys. Defaults to the system zone in
     * production and is injectable so tests pin a fixed instant and assert deterministic keys. This is the
     * {@code java.time} replacement for the implicit system clock the legacy batch run derived its
     * generation from.
     */
    private final Clock clock;

    /**
     * Production constructor used by Spring for component injection. Uses the system-default-zone
     * {@link Clock} so the {@code yyyyMMdd} generation prefix is stamped from the current date.
     *
     * @param s3Template            the auto-configured S3 template used for the statement uploads; must
     *                              not be {@code null}
     * @param awsResourceProperties the AWS resource-name holder (statements-bucket source); must not be
     *                              {@code null}
     */
    @Autowired
    public StatementWriter(final S3Template s3Template,
                           final AwsConfig.AwsResourceProperties awsResourceProperties) {
        this(s3Template, awsResourceProperties, Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor allowing a fixed {@link Clock} so the {@code yyyyMMdd} generation prefix
     * of the S3 keys is deterministic. Behaves identically to the production constructor in every other
     * respect.
     *
     * @param s3Template            the S3 template used for the statement uploads; must not be {@code null}
     * @param awsResourceProperties the AWS resource-name holder; must not be {@code null}
     * @param clock                 the clock used for the S3 key generation prefix; must not be {@code null}
     */
    public StatementWriter(final S3Template s3Template,
                           final AwsConfig.AwsResourceProperties awsResourceProperties,
                           final Clock clock) {
        this.s3Template = s3Template;
        this.awsResourceProperties = awsResourceProperties;
        this.clock = clock;
    }

    /**
     * Writes every statement in the chunk to S3, reproducing the COBOL {@code 1000-MAINLINE} behaviour of
     * emitting both a text and an HTML rendering for each account &mdash; redirected from the two shared
     * sequential datasets ({@code STMT-FILE}/{@code HTML-FILE}) to one pair of S3 objects per account.
     *
     * <p>The destination bucket and the {@code yyyyMMdd} generation prefix are resolved once for the
     * chunk (they are constant across it). Each non-{@code null} item is then persisted via
     * {@link #putStatement(String, String, AccountStatement)}. A {@code null} item is skipped defensively
     * (a {@code null} cannot reach here on the normal path because a Spring Batch {@code ItemProcessor}
     * returning {@code null} filters the item upstream); the defensive skip ensures a misrouted
     * {@code null} can neither raise a {@link NullPointerException} nor emit an empty object.</p>
     *
     * <p>If any upload fails, the {@link S3Template} exception propagates so the step fails (COBOL
     * write-error {@code ABEND} parity); no partial state is reported as success.</p>
     *
     * @param chunk the chunk of assembled statements supplied by Spring Batch; never {@code null}
     */
    @Override
    public void write(final Chunk<? extends AccountStatement> chunk) {
        // Bucket and generation prefix are constant for the whole chunk; resolve them once.
        final String bucket = awsResourceProperties.getS3().getStatementsBucket();
        final String generation = LocalDate.now(clock).format(S3_GENERATION_DATE);

        for (final AccountStatement statement : chunk) {
            // Defensive: a null cannot arrive on the normal path (a processor returning null filters the
            // item upstream); skipping it avoids emitting an empty object or raising an NPE.
            if (statement == null) {
                continue;
            }
            putStatement(bucket, generation, statement);
        }
    }

    /**
     * Persists a single account's dual-format statement: the text body to a {@code .txt} object and the
     * HTML body to a {@code .html} object, both under the same generation-prefixed key stem. This is the
     * direct counterpart of the COBOL per-account {@code WRITE FD-STMTFILE-REC} /
     * {@code WRITE FD-HTMLFILE-REC} pairing, redirected to S3.
     *
     * <p>The bodies are written verbatim (AAP &sect;0.7.2): the text and HTML content produced by
     * {@code StatementProcessor} is not reformatted, re-wrapped, trimmed, or padded here. The
     * {@link AccountStatement} record guarantees a non-{@code null} account id, text body, and HTML body
     * via its canonical constructor, so the account id renders cleanly to a numeric key segment.</p>
     *
     * @param bucket     the config-resolved statements bucket ({@code carddemo-statements})
     * @param generation the {@code yyyyMMdd} generation prefix shared by both objects
     * @param statement  the assembled statement to persist; never {@code null}
     */
    // COBOL: CBSTM03A STMTFILE/HTMLFILE output (5000-CREATE-STATEMENT / 5100 / 5200 / 6000-WRITE-TRANS).
    private void putStatement(final String bucket, final String generation, final AccountStatement statement) {
        final String keyStem = S3_KEY_PREFIX + generation + "/" + statement.accountId();
        final String textKey = keyStem + TEXT_OBJECT_SUFFIX;
        final String htmlKey = keyStem + HTML_OBJECT_SUFFIX;

        // STMT-FILE (PIC X(80)) -> text/plain object, written verbatim.
        uploadBody(bucket, textKey, statement.textBody(), TEXT_CONTENT_TYPE);
        // HTML-FILE (PIC X(100)) -> text/html object, written verbatim.
        uploadBody(bucket, htmlKey, statement.htmlBody(), HTML_CONTENT_TYPE);
    }

    /**
     * Uploads a single statement body to S3 verbatim under the given key and content type.
     *
     * <p>The body is serialized with a single deterministic charset ({@link StandardCharsets#UTF_8}) and
     * uploaded through the auto-configured {@link S3Template} (so the endpoint resolves only to
     * LocalStack). The {@link ObjectMetadata} carries the documented content type and the exact content
     * length of the serialized payload. The {@link ByteArrayInputStream} is backed by the in-memory
     * {@code byte[]} and holds no external resource, so it needs no explicit close; {@code S3Template}
     * fully consumes it during the upload. Any failure propagates (COBOL write-error {@code ABEND}
     * parity).</p>
     *
     * @param bucket      the destination bucket (config-resolved; never hardcoded)
     * @param key         the deterministic, generation-prefixed object key
     * @param body        the statement body to write verbatim (never {@code null} per the
     *                    {@link AccountStatement} contract)
     * @param contentType the fixed content type for the object ({@code text/plain} or {@code text/html})
     */
    private void uploadBody(final String bucket, final String key, final String body, final String contentType) {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        final ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(contentType)
                .contentLength((long) payload.length)
                .build();
        s3Template.upload(bucket, key, new ByteArrayInputStream(payload), metadata);
    }
}
