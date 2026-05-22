/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.adapter;

import com.awsm2.carddemo.dto.ReportLineDto;
import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AWS S3 output adapter for CardDemo batch output files.
 *
 * <p><b>Replaces:</b> COBOL sequential {@code WRITE} statements against
 * PS/GDG datasets &mdash; namely DALYREJS, SYSTRAN, TRANREPT,
 * TRANSACT.BKUP, STMTFILE, and HTMLFILE &mdash; with versioned Amazon S3
 * objects encrypted at rest using SSE-KMS with a customer-managed key.
 * This adapter is the <i>only</i> place in
 * {@code src/main/java/com/awsm2/carddemo/} that invokes the AWS SDK v2
 * S3 client directly, per AAP &sect;0.7.1 ("Isolate all AWS service
 * integrations in dedicated adapter classes &mdash; never inline AWS SDK
 * calls in business logic").</p>
 *
 * <h2>Source-to-target mapping</h2>
 * <p>The following table documents the JCL DD &rarr; S3 object-key mapping
 * implemented by this adapter. Each row corresponds to one of the five
 * public methods exposed below.</p>
 * <table>
 *   <caption>JCL DD allocation &rarr; S3 object-key mapping</caption>
 *   <thead>
 *     <tr>
 *       <th>JCL DD</th><th>LRECL</th><th>COBOL writer</th>
 *       <th>Adapter method</th><th>S3 key prefix</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>DALYREJS (POSTTRAN.jcl lines 34-38)</td>
 *       <td>430</td>
 *       <td>{@code CBTRN02C} 2500-WRITE-REJECT-REC</td>
 *       <td>{@link #writeRejection(String, String)}</td>
 *       <td>{@code dalyrejs/yyyy/MM/dd/&lt;id&gt;.rejs}</td>
 *     </tr>
 *     <tr>
 *       <td>SYSTRAN/TRANSACT (INTCALC.jcl lines 37-41)</td>
 *       <td>350</td>
 *       <td>{@code CBACT04C} 1300-B-WRITE-TX</td>
 *       <td>{@link #writeSysTran(String, String)}</td>
 *       <td>{@code systran/yyyy/MM/dd/&lt;id&gt;.tran}</td>
 *     </tr>
 *     <tr>
 *       <td>TRANREPT (TRANREPT.jcl lines 76-80)</td>
 *       <td>133</td>
 *       <td>{@code CBTRN03C} 1110-/1120- writers</td>
 *       <td>{@link #writeReport(String, byte[])}</td>
 *       <td>{@code tranrept/yyyy/MM/dd/&lt;id&gt;.rpt}</td>
 *     </tr>
 *     <tr>
 *       <td>STMTFILE / HTMLFILE (CREASTMT.JCL lines 87-96)</td>
 *       <td>80 / 100</td>
 *       <td>{@code CBSTM03A} statement writers</td>
 *       <td>{@link #writeReport(String, byte[])}</td>
 *       <td>{@code tranrept/yyyy/MM/dd/&lt;id&gt;.&#123;rpt|html&#125;}</td>
 *     </tr>
 *     <tr>
 *       <td>TRANSACT.BKUP (TRANREPT.jcl lines 29-33)</td>
 *       <td>350</td>
 *       <td>DFSORT REPRO (utility)</td>
 *       <td>{@link #copyTransactionBackup(String, byte[])}</td>
 *       <td>{@code transact-bkup/yyyy/MM/dd/&lt;gen&gt;.bkup}</td>
 *     </tr>
 *     <tr>
 *       <td>Report streaming (Spring Batch ItemWriter chunks)</td>
 *       <td>variable</td>
 *       <td>n/a &mdash; convenience for {@link ReportLineDto} lists</td>
 *       <td>{@link #writeReportLines(String, String, List)}</td>
 *       <td>{@code &lt;reportType&gt;/yyyy/MM/dd/&lt;id&gt;.rpt}</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>GDG &rarr; S3 versioning (AAP &sect;0.6.2)</h2>
 * <p>The COBOL source uses Generation Data Group (GDG) semantics:
 * {@code (+1)} for the next-generation write, {@code (0)} for the current
 * generation, {@code (-1)} for the previous generation. In the target
 * stack, these semantics map to S3 object versioning &mdash; each call
 * to a write method emits one versioned object on a bucket with
 * Versioning enabled (configured in
 * {@code infrastructure/terraform/s3.tf}). Retrieval semantics are:</p>
 * <ul>
 *   <li>GDG {@code (0)} &rarr; S3 {@code GetObject} without {@code VersionId}
 *       returns the latest version</li>
 *   <li>GDG {@code (-1)}, {@code (-2)}, etc. &rarr; S3
 *       {@code ListObjectVersions} + {@code GetObject} with explicit
 *       {@code VersionId}</li>
 *   <li>GDG cleanup &rarr; S3 Lifecycle Policies (transition to Glacier
 *       after N days, expire after M days) configured in Terraform
 *       &mdash; <i>not</i> in application code</li>
 * </ul>
 *
 * <h2>Security invariants (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 * <ul>
 *   <li>Every PUT carries {@link ServerSideEncryption#AWS_KMS} +
 *       {@code ssekmsKeyId(${KMS_KEY_ARN})}. A missing or blank KMS ARN
 *       fails the call with a typed {@link CardDemoException}
 *       (reason code {@link #REASON_CODE_KMS_KEY_MISSING}) rather than
 *       silently writing an unencrypted object. <b>NEVER</b> use SSE-S3
 *       in this adapter.</li>
 *   <li>Bucket and key arguments are validated for null/blank and
 *       forbidden characters (newlines, leading slashes) before any SDK
 *       call.</li>
 *   <li>No public-read ACLs are ever set; the SDK default ACL
 *       (bucket-owner) is used unchanged. Public access is blocked at
 *       the bucket level via Terraform.</li>
 *   <li>Object content is never logged; only object metadata (bucket,
 *       key, size, eTag, version ID) is emitted via SLF4J. PCI-DSS log
 *       hygiene (AAP &sect;0.6.6).</li>
 *   <li>Every PUT carries object metadata documenting the COBOL
 *       provenance ({@code cobol-source}, {@code jcl-dd}, optional
 *       {@code batch-run-id}). This is operational traceability &mdash;
 *       not PCI-protected data.</li>
 * </ul>
 *
 * <h2>Configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.aws.s3.output-bucket} &mdash; bound to
 *       {@code ${S3_OUTPUT_BUCKET}} environment variable</li>
 *   <li>{@code carddemo.aws.kms.key-arn} &mdash; bound to
 *       {@code ${KMS_KEY_ARN}} environment variable</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@link S3Client} from AWS SDK v2 is thread-safe and intended to be
 * shared across the application. This adapter holds a single client
 * reference and is itself stateless beyond the injected configuration
 * values. It is therefore safe to inject as a singleton {@code @Service}
 * bean into multi-threaded callers (Spring Batch chunk writers, parallel
 * step executors, etc.).</p>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig#s3Client()
 * @see com.awsm2.carddemo.dto.ReportLineDto
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
// Replaces: COBOL sequential WRITE to PS/GDG datasets
// (DALYREJS, SYSTRAN, TRANREPT, TRANSACT.BKUP, STMTFILE, HTMLFILE).
@Service
public class S3OutputService {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(S3OutputService.class);

    /**
     * Date-partition formatter for S3 object keys. The {@code yyyy/MM/dd}
     * layout enables S3 Lifecycle Policies and Athena-style date-prefix
     * scans per AAP &sect;0.7.2 ("S3 lifecycle policies enforce regulatory
     * data retention periods on batch output files").
     */
    private static final DateTimeFormatter DATE_PARTITION =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** Reason code for runtime S3 PUT failures (transport / service errors). */
    static final String REASON_CODE_PUT_ERROR = "S3_PUT_ERROR";

    /** Reason code for missing / blank KMS key ARN at write time. */
    static final String REASON_CODE_KMS_KEY_MISSING = "S3_KMS_KEY_MISSING";

    /** Reason code for missing / blank S3 output bucket name at write time. */
    static final String REASON_CODE_BUCKET_MISSING = "S3_BUCKET_MISSING";

    /** S3 user-metadata key recording the COBOL source paragraph for traceability. */
    static final String METADATA_COBOL_SOURCE = "cobol-source";

    /** S3 user-metadata key recording the original JCL DD name. */
    static final String METADATA_JCL_DD = "jcl-dd";

    /** S3 user-metadata key recording the Step Functions / Batch run identifier. */
    static final String METADATA_BATCH_RUN_ID = "batch-run-id";

    /** Content type used for COBOL fixed-width sequential text lines. */
    private static final String CONTENT_TYPE_TEXT_ASCII = "text/plain; charset=US-ASCII";

    /** Content type used for the HTML statement variant emitted by CBSTM03A. */
    private static final String CONTENT_TYPE_HTML_ASCII = "text/html; charset=US-ASCII";

    /** Content type used for binary backup payloads (TRANSACT.BKUP). */
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    /**
     * The shared SDK v2 {@link S3Client} produced by
     * {@code com.awsm2.carddemo.config.AwsSdkConfig#s3Client()}. Held
     * {@code final} to make the constructor's immutability contract
     * explicit &mdash; Spring's DI container sets this once at startup and
     * never reassigns it.
     */
    private final S3Client s3Client;

    /**
     * S3 output bucket configured via {@code carddemo.aws.s3.output-bucket}
     * which itself resolves from the {@code ${S3_OUTPUT_BUCKET}} environment
     * variable (AAP &sect;0.7.2 required-env-vars list). All sequential-output
     * replacement writes target this single bucket; the per-stream
     * separation is handled via key-prefix partitioning ({@code dalyrejs/},
     * {@code systran/}, {@code tranrept/}, {@code transact-bkup/}).
     */
    private final String outputBucket;

    /**
     * KMS customer-managed key ARN used for SSE-KMS on every PUT. Sourced
     * from {@code carddemo.aws.kms.key-arn}; in the {@code local} profile
     * this value may be a fake LocalStack ARN, and the LocalStack KMS
     * provider may or may not honor it depending on the LocalStack edition.
     * Production overlays MUST supply a real CMK ARN sourced from AWS
     * Secrets Manager / Parameter Store (AAP &sect;0.7.1, &sect;0.7.2).
     */
    private final String kmsCmkArn;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Constructor injection only &mdash; per AAP &sect;0.3.3 Dependency
     * Injection pattern. Spring's container supplies the {@link S3Client}
     * bean and resolves the {@code carddemo.aws.s3.output-bucket} and
     * {@code carddemo.aws.kms.key-arn} properties at startup.
     *
     * @param s3Client     the shared SDK v2 client bean; never {@code null}
     * @param outputBucket the configured output bucket name; may be blank
     *                     during {@code local} profile startup (validated
     *                     at call time to surface the misconfiguration
     *                     loudly rather than silently picking a default
     *                     bucket)
     * @param kmsCmkArn    the configured CMK ARN; may be blank in local
     *                     profile, in which case PUT calls will fail with
     *                     a typed exception rather than silently writing
     *                     unencrypted objects
     * @throws NullPointerException if {@code s3Client} is {@code null}
     */
    public S3OutputService(
            S3Client s3Client,
            @Value("${carddemo.aws.s3.output-bucket:}") String outputBucket,
            @Value("${carddemo.aws.kms.key-arn:}") String kmsCmkArn) {
        // Replaces: WRITE TO DALYREJS / SYSTRAN / TRANREPT / STMTFILE
        // sequential writes — no constructor-time AWS API calls, pure wiring.
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        // Empty defaults are permitted at construction time so the bean can
        // wire successfully under the `local` profile (LocalStack may ignore
        // SSE-KMS); PUT operations validate both at call time so a missing
        // ARN fails the call rather than silently writing unencrypted
        // objects.
        this.outputBucket = (outputBucket == null) ? "" : outputBucket.trim();
        this.kmsCmkArn = (kmsCmkArn == null) ? "" : kmsCmkArn.trim();
    }

    // =======================================================================
    // Public API — schema-mandated methods (AAP §0.4.1 exports schema)
    // =======================================================================

    /**
     * Writes a single rejection record to the {@code dalyrejs/} S3 prefix.
     *
     * <p>The rejection record format per {@code CBTRN02C.cbl} is a 350-byte
     * transaction record concatenated with an 80-byte trailer
     * ({@code VALIDATION-TRAILER-REC.REJECT-REASON-CD} +
     * {@code VALIDATION-TRAILER-REC.REJECT-REASON-DESC}). Reject codes
     * 100&ndash;109 are preserved verbatim per AAP &sect;0.7.2 ("Error codes
     * and condition handling surfaced to downstream consumers must be
     * preserved verbatim"). The caller (typically
     * {@code TransactionPostingService}) is responsible for formatting the
     * full 430-byte record to byte-identical layout before invoking this
     * method; this adapter writes the record verbatim with no
     * transformation.</p>
     *
     * <p>Each call creates a new versioned S3 object &mdash; the bucket is
     * versioning-enabled (Terraform) so multiple rejection records per
     * batch are preserved as separate versions of the same key. Production
     * callers using Spring Batch typically batch many records into a
     * single ItemWriter chunk and call this method once per chunk with the
     * concatenated record buffer.</p>
     *
     * @param batchRunId unique batch execution identifier (typically the
     *                   Step Functions execution ARN suffix); used both as
     *                   the S3 key sequence and as the
     *                   {@code x-amz-meta-batch-run-id} metadata header.
     *                   Must not be {@code null} or blank
     * @param record     pre-formatted rejection record (the caller has
     *                   already formatted the trailer); must not be
     *                   {@code null}. A trailing {@code \n} is appended by
     *                   this method so downstream UNIX/Linux S3 readers
     *                   can split the output line-by-line
     * @throws IllegalArgumentException if {@code batchRunId} is null/blank
     *                                  or {@code record} is null
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING},
     *         {@link #REASON_CODE_BUCKET_MISSING}, or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    // Replaces: CBTRN02C 2500-WRITE-REJECT-REC + POSTTRAN.jcl DALYREJS DD allocation
    public void writeRejection(String batchRunId, String record) {
        validateBatchRunId(batchRunId);
        Objects.requireNonNull(record, "record must not be null");
        String key = buildKey("dalyrejs", batchRunId, "rejs");
        Map<String, String> metadata = buildMetadata(
                "CBTRN02C.2500-WRITE-REJECT-REC", "DALYREJS", batchRunId);
        appendLineToObject(outputBucket, key, record, CONTENT_TYPE_TEXT_ASCII, metadata);
    }

    /**
     * Writes a single system-generated transaction line to the
     * {@code systran/} S3 prefix.
     *
     * <p>System transactions are generated by {@code CBACT04C.cbl} during
     * interest calculation: for each account-category balance that earns
     * non-zero interest, the program issues
     * {@code PERFORM 1300-B-WRITE-TX}, which executes
     * {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} against the
     * {@code SYSTRAN} DD ({@code INTCALC.jcl} lines 37-41). The output is
     * a 350-byte transaction record matching the {@code CVTRA05Y.cpy}
     * layout, ready for ingestion by the next end-of-day step
     * ({@code COMBTRAN.jcl}).</p>
     *
     * <p>The caller (typically {@code InterestCalculationService} or its
     * Spring Batch ItemWriter) is responsible for formatting the
     * transaction line to byte-identical 350-byte layout before invoking
     * this method.</p>
     *
     * @param batchRunId      unique batch execution identifier; must not
     *                        be {@code null} or blank
     * @param transactionLine pre-formatted 350-byte transaction record;
     *                        must not be {@code null}
     * @throws IllegalArgumentException if arguments are invalid
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING},
     *         {@link #REASON_CODE_BUCKET_MISSING}, or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    // Replaces: CBACT04C 1300-B-WRITE-TX + INTCALC.jcl TRANSACT (SYSTRAN) DD allocation
    public void writeSysTran(String batchRunId, String transactionLine) {
        validateBatchRunId(batchRunId);
        Objects.requireNonNull(transactionLine, "transactionLine must not be null");
        String key = buildKey("systran", batchRunId, "tran");
        Map<String, String> metadata = buildMetadata(
                "CBACT04C.1300-B-WRITE-TX", "SYSTRAN", batchRunId);
        appendLineToObject(outputBucket, key, transactionLine, CONTENT_TYPE_TEXT_ASCII, metadata);
    }

    /**
     * Writes a complete report payload as a single versioned S3 object
     * under the {@code tranrept/} key prefix.
     *
     * <p>Used by the transaction-report writer ({@code CBTRN03C.cbl}
     * &mdash; LRECL=133) and the statement generator
     * ({@code CBSTM03A.CBL} &mdash; LRECL=80 for text, LRECL=100 for HTML).
     * The entire report content is written as one S3 object &mdash; this
     * preserves byte-identical regulatory output per AAP &sect;0.7.2
     * ("must remain identical byte-for-byte to the COBOL source's
     * output"). The caller assembles the complete report buffer (with
     * proper trailing newlines per LRECL) before invoking this method;
     * this adapter writes the bytes verbatim.</p>
     *
     * <p>Content-type detection: if {@code reportId} ends with
     * {@code .html} (case-insensitive), the object is tagged as
     * {@code text/html; charset=US-ASCII} and gets a {@code .html}
     * extension; otherwise it is tagged as {@code text/plain;
     * charset=US-ASCII} with a {@code .rpt} extension. This branching
     * mirrors the {@code STMTFILE} vs {@code HTMLFILE} DD allocation
     * split in {@code CREASTMT.JCL} lines 87-96.</p>
     *
     * @param reportId    report identifier (date + report type + sequence,
     *                   optionally suffixed with {@code .html} to select
     *                   the HTML content type)
     * @param reportBytes complete report content as bytes; must not be
     *                   {@code null} or empty
     * @throws IllegalArgumentException if {@code reportBytes} is null/empty
     *                                  or {@code reportId} is null/blank
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING},
     *         {@link #REASON_CODE_BUCKET_MISSING}, or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    // Replaces: CBTRN03C TRANREPT WRITE + CBSTM03A STMTFILE/HTMLFILE WRITE
    public void writeReport(String reportId, byte[] reportBytes) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId must not be null or blank");
        }
        if (reportBytes == null || reportBytes.length == 0) {
            throw new IllegalArgumentException("reportBytes must not be null or empty");
        }
        boolean isHtml = reportId.toLowerCase().endsWith(".html");
        String contentType = isHtml ? CONTENT_TYPE_HTML_ASCII : CONTENT_TYPE_TEXT_ASCII;
        String extension = isHtml ? "html" : "rpt";
        // Strip any trailing .html / .rpt extension from the id so the
        // buildKey result does not emit duplicate ".html.html" suffixes.
        String idForKey = stripKnownExtension(reportId);
        String key = buildKey("tranrept", idForKey, extension);
        Map<String, String> metadata = buildMetadata(
                isHtml ? "CBSTM03A.HTMLFILE-WRITE" : "CBTRN03C.TRANREPT-WRITE",
                isHtml ? "HTMLFILE" : "TRANREPT",
                null);
        putObject(outputBucket, key, reportBytes, contentType, metadata);
    }

    /**
     * Writes a TRANSACT.BKUP generation payload to the
     * {@code transact-bkup/} S3 prefix.
     *
     * <p>In the COBOL source, {@code TRANREPT.jcl} step {@code STEP05R}
     * invokes the {@code REPROC} JCL procedure (IDCAMS {@code REPRO}) to
     * back up the {@code TRANSACT VSAM KSDS} cluster to the
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} GDG generation. In the
     * target stack, that operation becomes a Spring Batch step that
     * streams the current {@code Transaction} JPA table to a byte array
     * (one transaction record per line, 350 bytes each per
     * {@code CVTRA05Y.cpy}) and invokes this method with the assembled
     * payload. The {@code (+1)} GDG generation semantics map to S3 object
     * versioning (AAP &sect;0.6.2); per-generation retention is enforced
     * by S3 Lifecycle Policies configured in
     * {@code infrastructure/terraform/s3.tf}.</p>
     *
     * @param generation timestamp or sequence used in the S3 key (replaces
     *                   the {@code (+1)} generation suffix); must not be
     *                   {@code null} or blank
     * @param payload    serialized backup content; must not be {@code null}
     *                   (zero-length is rejected to mirror the COBOL
     *                   behavior of producing a populated backup object)
     * @throws IllegalArgumentException if arguments are invalid
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING},
     *         {@link #REASON_CODE_BUCKET_MISSING}, or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    // Replaces: TRANREPT.jcl STEP05R IDCAMS REPRO to TRANSACT.BKUP(+1)
    public void copyTransactionBackup(String generation, byte[] payload) {
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException("generation must not be null or blank");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be null or empty");
        }
        String key = buildKey("transact-bkup", generation, "bkup");
        Map<String, String> metadata = buildMetadata(
                "TRANREPT.jcl.STEP05R.IDCAMS-REPRO", "TRANSACT.BKUP", generation);
        putObject(outputBucket, key, payload, CONTENT_TYPE_OCTET_STREAM, metadata);
    }

    /**
     * Streams a list of {@link ReportLineDto} rows into a single versioned
     * S3 object under the supplied {@code reportType} prefix.
     *
     * <p>This convenience wrapper is used by
     * {@code StatementGenerationService} (port of {@code CBSTM03A.CBL} /
     * {@code CBSTM03B.CBL}) and {@code TransactionReportService} (port of
     * {@code CBTRN03C.cbl}) when their callers prefer to operate on
     * strongly-typed {@link ReportLineDto} records rather than
     * pre-formatted byte payloads. The adapter formats each DTO into a
     * deterministic, fixed-position printable line (driven by the DTO's
     * {@code lineType} discriminator), concatenates the lines with LF
     * terminators, and emits the assembled buffer as a single S3 object.
     * The format respects the COBOL display-edit conventions from
     * {@code CVTRA07Y.cpy} (HEADER, DETAIL, PAGE_TOTAL, ACCOUNT_TOTAL,
     * GRAND_TOTAL, SEPARATOR variants), though it does <i>not</i> attempt
     * to reproduce byte-for-byte the printer-control characters of the
     * original FBA-printed mainframe output &mdash; that level of fidelity
     * is the responsibility of the producer service when calling
     * {@link #writeReport(String, byte[])} with a pre-formatted byte
     * array.</p>
     *
     * <p>Per AAP &sect;0.4.1, {@link ReportLineDto} is the streaming output
     * DTO consumed by this adapter when writing report files to S3.</p>
     *
     * @param batchRunId batch execution identifier; must not be
     *                   {@code null} or blank
     * @param reportType S3 key-prefix discriminator
     *                   (e.g. {@code "statement"}, {@code "transaction-report"});
     *                   must not be {@code null} or blank
     * @param lines      report line DTOs in output order; must not be
     *                   {@code null} or empty
     * @throws IllegalArgumentException if any argument is invalid
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING},
     *         {@link #REASON_CODE_BUCKET_MISSING}, or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    // Replaces: CBTRN03C 1110-/1120- writers + CBSTM03A statement writers when
    // caller passes strongly-typed ReportLineDto rows (per AAP §0.4.1 dto inventory).
    public void writeReportLines(String batchRunId, String reportType, List<ReportLineDto> lines) {
        validateBatchRunId(batchRunId);
        if (reportType == null || reportType.isBlank()) {
            throw new IllegalArgumentException("reportType must not be null or blank");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be null or empty");
        }
        // Estimate buffer size: 133 bytes per line + LF = 134 (matches
        // COBOL TRANREPT LRECL=133).
        StringBuilder sb = new StringBuilder(lines.size() * 134);
        for (ReportLineDto line : lines) {
            if (line == null) {
                throw new IllegalArgumentException("lines must not contain null entries");
            }
            sb.append(formatReportLine(line)).append('\n');
        }
        String key = buildKey(reportType, batchRunId, "rpt");
        Map<String, String> metadata = buildMetadata(
                "ReportLineDto.formatReportLine", "TRANREPT", batchRunId);
        putObject(outputBucket, key, sb.toString().getBytes(StandardCharsets.US_ASCII),
                CONTENT_TYPE_TEXT_ASCII, metadata);
    }

    // =======================================================================
    // Helper methods
    // =======================================================================

    /**
     * Build a date-partitioned S3 key matching the DD-name conventions
     * documented in the class-level Javadoc.
     *
     * <p>Format: {@code "{prefix}/yyyy/MM/dd/{id}.{ext}"}. The date
     * partitioning enables S3 Lifecycle Policies per AAP &sect;0.7.2 ("S3
     * lifecycle policies enforce regulatory data retention periods on
     * batch output files").</p>
     *
     * @param prefix the S3 key prefix (e.g. {@code "dalyrejs"},
     *               {@code "systran"})
     * @param id     the per-object identifier (typically a batch run id)
     * @param ext    the file extension without leading dot (e.g.
     *               {@code "rejs"}, {@code "tran"}, {@code "rpt"},
     *               {@code "bkup"})
     * @return the assembled S3 object key
     */
    private String buildKey(String prefix, String id, String ext) {
        return prefix + "/" + DATE_PARTITION.format(LocalDate.now()) + "/" + id + "." + ext;
    }

    /**
     * Strips any trailing {@code .html} / {@code .rpt} / {@code .txt}
     * extension from the supplied identifier so that
     * {@link #buildKey(String, String, String)} does not emit duplicate
     * extension suffixes like {@code statement.html.html}.
     *
     * @param id the raw identifier as supplied by the caller
     * @return the identifier with any known trailing extension removed
     */
    private static String stripKnownExtension(String id) {
        String lower = id.toLowerCase();
        if (lower.endsWith(".html")) {
            return id.substring(0, id.length() - 5);
        }
        if (lower.endsWith(".rpt") || lower.endsWith(".txt")) {
            return id.substring(0, id.length() - 4);
        }
        return id;
    }

    /**
     * Builds an S3 user-metadata map carrying COBOL provenance fields per
     * AAP &sect;0.7.3 ("Document all COBOL-to-Java translations with inline
     * comments referencing the original COBOL paragraph/section name").
     * Putting these as S3 object metadata surfaces the provenance into
     * audit tools (Athena, Macie, OpenSearch) without requiring the
     * consumer to parse application logs.
     *
     * @param cobolSource the COBOL program + paragraph identifier (e.g.
     *                    {@code "CBTRN02C.2500-WRITE-REJECT-REC"})
     * @param jclDd       the original JCL DD allocation name (e.g.
     *                    {@code "DALYREJS"})
     * @param batchRunId  the batch execution identifier; may be {@code null}
     *                    if not applicable
     * @return an immutable map of S3 user-metadata key-value pairs
     */
    private static Map<String, String> buildMetadata(String cobolSource, String jclDd, String batchRunId) {
        Map<String, String> metadata = new HashMap<>(4);
        metadata.put(METADATA_COBOL_SOURCE, cobolSource);
        metadata.put(METADATA_JCL_DD, jclDd);
        if (batchRunId != null && !batchRunId.isBlank()) {
            metadata.put(METADATA_BATCH_RUN_ID, batchRunId);
        }
        return metadata;
    }

    /**
     * Single-line write: each call creates a new versioned S3 object with
     * the supplied content followed by a trailing LF. For high-volume
     * streaming, callers should batch records and call
     * {@link #putObject(String, String, byte[], String, Map)} directly
     * with the assembled byte array (S3 does not support true "append").
     *
     * @param bucket      target S3 bucket
     * @param key         target S3 object key
     * @param line        the line content (LF added by this method)
     * @param contentType MIME content type
     * @param metadata    S3 user-metadata to attach to the object
     */
    private void appendLineToObject(String bucket, String key, String line,
                                    String contentType, Map<String, String> metadata) {
        byte[] bytes = (line + '\n').getBytes(StandardCharsets.US_ASCII);
        putObject(bucket, key, bytes, contentType, metadata);
    }

    /**
     * Central S3 PUT method with SSE-KMS encryption applied to every call.
     *
     * <p>Per AAP &sect;0.7.1, every write to S3 must be encrypted with
     * SSE-KMS using a customer-managed key &mdash; <b>NEVER</b> SSE-S3,
     * <b>NEVER</b> unencrypted. The CMK ARN is fetched from
     * {@code carddemo.aws.kms.key-arn} (which itself resolves to the
     * {@code ${KMS_KEY_ARN}} environment variable per AAP &sect;0.7.2). A
     * missing or blank ARN fails the call with a typed
     * {@link CardDemoException} rather than silently producing an
     * unencrypted object.</p>
     *
     * <p>Returns the {@link PutObjectResponse} so callers (or other helper
     * methods within this class) can inspect the ETag / version ID for
     * downstream traceability.</p>
     *
     * @param bucket      target S3 bucket; non-blank
     * @param key         target S3 key; non-blank
     * @param bytes       payload bytes; non-null
     * @param contentType MIME content type; may be null/blank (SDK default
     *                    used)
     * @param metadata    S3 user-metadata; may be null or empty
     * @return the SDK {@link PutObjectResponse} on success
     * @throws IllegalArgumentException on validation failure
     * @throws CardDemoException on KMS/bucket misconfiguration or S3 error
     */
    // AAP §0.7.1: SSE-KMS with customer-managed CMK from ${KMS_KEY_ARN} — never SSE-S3
    private PutObjectResponse putObject(String bucket, String key, byte[] bytes,
                                        String contentType, Map<String, String> metadata) {
        validateBucket(bucket);
        validateKey(key);
        Objects.requireNonNull(bytes, "bytes must not be null");
        validateKmsKeyArn();

        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength((long) bytes.length)
                // AAP §0.6.6 & §0.7.1 — SSE-KMS on every PUT. The CMK ARN
                // is the customer-managed key declared in
                // infrastructure/terraform/kms.tf and referenced by
                // carddemo.aws.kms.key-arn.
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(kmsCmkArn);
        if (contentType != null && !contentType.isBlank()) {
            builder.contentType(contentType);
        }
        if (metadata != null && !metadata.isEmpty()) {
            builder.metadata(metadata);
        }
        PutObjectRequest request = builder.build();

        try {
            PutObjectResponse response = s3Client.putObject(request,
                    RequestBody.fromBytes(bytes));
            // PCI-DSS-safe logging: metadata only — never the content body
            // (per AAP §0.6.6 — no plaintext financial data in logs).
            LOG.info("S3 PUT OK bucket={} key={} bytes={} eTag={} versionId={}",
                    bucket, key, bytes.length,
                    response.eTag(), response.versionId());
            return response;
        } catch (S3Exception e) {
            LOG.error("S3 PUT FAILED bucket={} key={} bytes={} cause={}",
                    bucket, key, bytes.length,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e);
            // Per AAP §0.7.1, propagate the typed exception so Spring Batch
            // ItemWriter / AWS Batch retry policy can handle it. We wrap in
            // CardDemoException to preserve the reason code on the bus,
            // while keeping the original S3Exception as the cause for
            // diagnostic stack traces in OpenSearch.
            throw new CardDemoException(
                    REASON_CODE_PUT_ERROR,
                    "Failed to PUT object s3://" + bucket + "/" + key,
                    e);
        }
    }

    /**
     * Formats a {@link ReportLineDto} into a single printable text line,
     * driven by the DTO's {@code lineType} discriminator. The output
     * format follows the COBOL {@code CVTRA07Y.cpy} layout family
     * (HEADER, DETAIL, PAGE_TOTAL, ACCOUNT_TOTAL, GRAND_TOTAL, SEPARATOR)
     * but is intentionally not byte-for-byte equivalent &mdash; for
     * regulator-grade byte fidelity callers should pre-format the report
     * buffer and call {@link #writeReport(String, byte[])} instead.
     *
     * <p>BigDecimal amounts are rendered with {@code toPlainString()} to
     * avoid scientific notation, consistent with the application's
     * {@code spring.jackson.serialization.write-bigdecimal-as-plain=true}
     * setting (AAP &sect;0.6.1). Null amounts on lines that do not carry
     * one are rendered as an empty string.</p>
     *
     * @param dto the line DTO; must not be null
     * @return the formatted line (without trailing newline)
     */
    private static String formatReportLine(ReportLineDto dto) {
        String lineType = dto.lineType() == null ? "" : dto.lineType().trim().toUpperCase();
        switch (lineType) {
            case "HEADER":
                return formatHeaderLine(dto);
            case "DETAIL":
                return formatDetailLine(dto);
            case "PAGE_TOTAL":
            case "ACCOUNT_TOTAL":
            case "GRAND_TOTAL":
                return formatTotalLine(dto);
            case "SEPARATOR":
                // CVTRA07Y.cpy: TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.
                return repeatChar('-', 133);
            default:
                // Unknown discriminator — emit the record toString for
                // diagnostics so the line is not silently dropped.
                return dto.toString();
        }
    }

    /**
     * Formats a {@code HEADER} line per CVTRA07Y.cpy REPORT-NAME-HEADER.
     */
    private static String formatHeaderLine(ReportLineDto dto) {
        StringBuilder sb = new StringBuilder(133);
        sb.append(safe(dto.reportName())).append(' ');
        sb.append("Date Range: ");
        sb.append(safe(dto.startDate() == null ? null : dto.startDate().toString()));
        sb.append(" to ");
        sb.append(safe(dto.endDate() == null ? null : dto.endDate().toString()));
        if (dto.pageNumber() != null) {
            sb.append(" Page ").append(dto.pageNumber());
        }
        return sb.toString();
    }

    /**
     * Formats a {@code DETAIL} line per CVTRA07Y.cpy
     * TRANSACTION-DETAIL-REPORT.
     */
    private static String formatDetailLine(ReportLineDto dto) {
        StringBuilder sb = new StringBuilder(133);
        sb.append(safe(dto.accountId() == null ? null : dto.accountId().toString())).append('|');
        if (dto.maskedCardNumber() != null) {
            sb.append(dto.maskedCardNumber()).append('|');
        }
        sb.append(safe(dto.transactionDate() == null ? null : dto.transactionDate().toString())).append('|');
        sb.append(safe(dto.transactionType())).append('|');
        sb.append(safe(dto.transactionCategory() == null ? null : dto.transactionCategory().toString())).append('|');
        sb.append(safe(dto.transactionSource())).append('|');
        sb.append(safe(dto.description())).append('|');
        sb.append(formatAmount(dto.amount()));
        return sb.toString();
    }

    /**
     * Formats a TOTAL line (PAGE_TOTAL / ACCOUNT_TOTAL / GRAND_TOTAL) per
     * CVTRA07Y.cpy.
     */
    private static String formatTotalLine(ReportLineDto dto) {
        StringBuilder sb = new StringBuilder(133);
        sb.append(safe(dto.totalLabel())).append(' ');
        if (dto.accountId() != null) {
            sb.append(dto.accountId()).append(' ');
        }
        sb.append(formatAmount(dto.amount()));
        return sb.toString();
    }

    /**
     * Renders a monetary amount as a plain decimal string, respecting the
     * AAP &sect;0.6.1 mandate of {@code BigDecimal} with {@code scale=2}.
     * Null amounts return empty string.
     */
    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        return amount.toPlainString();
    }

    /** Null-safe string accessor used by line formatters. */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Repeats a single character {@code count} times; used to emit the
     * SEPARATOR line variant per CVTRA07Y.cpy TRANSACTION-HEADER-2.
     */
    private static String repeatChar(char c, int count) {
        char[] buf = new char[count];
        for (int i = 0; i < count; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }

    /**
     * Validates the bucket name. Per AWS bucket naming rules (3-63
     * characters, lowercase alphanumeric + hyphen), the application
     * enforces a conservative non-empty + no-newline check at the adapter
     * boundary; the SDK enforces full S3 naming rules server-side.
     *
     * @param bucket the bucket name
     * @throws IllegalArgumentException on null/blank/newline
     * @throws CardDemoException with {@link #REASON_CODE_BUCKET_MISSING}
     *         when the configured output bucket is blank
     */
    private static void validateBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new CardDemoException(
                    REASON_CODE_BUCKET_MISSING,
                    "carddemo.aws.s3.output-bucket is not configured — refusing to write");
        }
        if (bucket.indexOf('\n') >= 0 || bucket.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bucket must not contain newlines");
        }
    }

    /**
     * Validates the object key. Rejects null/blank, leading slashes
     * (which AWS treats as a literal slash character &mdash; usually a
     * caller bug), and embedded newlines (always a caller bug).
     *
     * @param key the object key
     * @throws IllegalArgumentException on validation failure
     */
    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null/blank");
        }
        if (key.charAt(0) == '/') {
            throw new IllegalArgumentException(
                    "key must not start with '/' — caller likely mistook the path syntax");
        }
        if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("key must not contain newlines");
        }
    }

    /**
     * Validates that a non-blank KMS CMK ARN is configured. Fails the
     * call loudly with a typed exception rather than silently producing
     * an unencrypted object &mdash; per AAP &sect;0.7.1 ("Encrypt all S3
     * buckets with SSE-KMS"), there is no acceptable production scenario
     * in which an object is written without SSE-KMS.
     *
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING}
     */
    private void validateKmsKeyArn() {
        if (kmsCmkArn == null || kmsCmkArn.isBlank()) {
            throw new CardDemoException(
                    REASON_CODE_KMS_KEY_MISSING,
                    "carddemo.aws.kms.key-arn is not configured — refusing to write S3 object without SSE-KMS");
        }
    }

    /**
     * Validates that {@code batchRunId} is non-null and non-blank. Used
     * by every public write method that uses the batch run id as both a
     * key fragment and an S3 metadata tag.
     */
    private static void validateBatchRunId(String batchRunId) {
        if (batchRunId == null || batchRunId.isBlank()) {
            throw new IllegalArgumentException("batchRunId must not be null or blank");
        }
    }
}
