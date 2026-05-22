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

import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * S3 output adapter — the sole component permitted to issue {@code PutObject}
 * requests against the CardDemo output buckets.
 *
 * <p>Per AAP &sect;0.7.1 ("Isolate all AWS service integrations in dedicated
 * adapter classes &mdash; never inline AWS SDK calls in business logic"), this
 * is the single chokepoint for every sequential-output replacement in the
 * migration. Every PUT issued by the application travels through one of the
 * three public methods exposed here, and every PUT is unconditionally
 * encrypted at rest with SSE-KMS using the customer-managed key referenced by
 * {@code carddemo.aws.kms.key-arn} (AAP &sect;0.6.6, &sect;0.7.1 — "Encrypt
 * all S3 buckets with SSE-KMS and block public access").</p>
 *
 * <h2>Replaces (AAP &sect;0.4.1)</h2>
 * <p>Replaces: DALYREJS / SYSTRAN / TRANREPT / STMTFILE sequential writes
 * (COBOL {@code WRITE} statements against {@code SELECT ... ASSIGN TO ...}
 * sequential PS / GDG datasets) in the following COBOL programs:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; daily transaction posting:
 *       {@code WRITE DALYREJS-RECORD} for the rejected-transaction stream.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest calculation:
 *       {@code WRITE TRAN-RECORD INTO SYSTRAN-FILE} for the generated interest
 *       transactions.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} &mdash; transaction reporting:
 *       {@code WRITE TRANREPT-LINE} for the printed report image.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL} and {@code CBSTM03B.CBL} &mdash;
 *       statement generation: {@code WRITE STATEMENT-LINE} for text and HTML
 *       statement outputs.</li>
 * </ul>
 * <p>GDG generation semantics ({@code (+1)} / {@code (0)}) map to S3 object
 * versioning + lifecycle policies declared in
 * {@code infrastructure/terraform/s3.tf}; this adapter does not manage
 * versioning beyond emitting one object per call.</p>
 *
 * <h2>Security invariants (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 * <ul>
 *   <li>Every PUT carries {@link ServerSideEncryption#AWS_KMS} +
 *       {@code ssekmsKeyId(carddemo.aws.kms.key-arn)}. Missing or blank KMS
 *       ARN fails the call with a typed {@link CardDemoException}.</li>
 *   <li>Bucket and key arguments are validated for null/blank and forbidden
 *       characters (newlines, leading slashes) before any SDK call.</li>
 *   <li>No public-read ACLs are ever set; the SDK default ACL (bucket-owner)
 *       is used unchanged.</li>
 *   <li>Object content is never logged; only object metadata (bucket, key,
 *       size, eTag, version ID) is emitted via SLF4J.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@link S3Client} from AWS SDK v2 is thread-safe and intended to be shared
 * across the application. This adapter holds a single client reference and is
 * itself stateless beyond the injected configuration values.</p>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig#s3Client()
 */
@Component
public class S3OutputService {

    private static final Logger LOG = LoggerFactory.getLogger(S3OutputService.class);

    /** Reason code for runtime S3 PUT failures (transport / service errors). */
    static final String REASON_CODE_PUT_ERROR = "S3_PUT_ERROR";

    /** Reason code for missing / blank KMS key ARN at write time. */
    static final String REASON_CODE_KMS_KEY_MISSING = "S3_KMS_KEY_MISSING";

    /**
     * The shared SDK v2 {@link S3Client} produced by
     * {@code com.awsm2.carddemo.config.AwsSdkConfig#s3Client()}. Held
     * {@code final} to make the constructor's immutability contract explicit
     * &mdash; Spring's DI container sets this once at startup and never
     * reassigns it.
     */
    private final S3Client s3Client;

    /**
     * KMS customer-managed key ARN used for SSE-KMS on every PUT. Sourced
     * from {@code carddemo.aws.kms.key-arn}; in the {@code local} profile
     * this value may be blank (LocalStack does not honor SSE-KMS), in which
     * case PUT calls fail with {@link #REASON_CODE_KMS_KEY_MISSING} to
     * surface the misconfiguration loudly. Production overlays MUST supply a
     * non-blank ARN sourced from AWS Secrets Manager / Parameter Store
     * (AAP &sect;0.7.1, &sect;0.7.2).
     */
    private final String kmsKeyArn;

    /**
     * Constructor injection only &mdash; per AAP &sect;0.3.3 Dependency
     * Injection pattern. Spring's container supplies the {@link S3Client}
     * bean and resolves the {@code carddemo.aws.kms.key-arn} property at
     * startup.
     *
     * @param s3Client  the shared SDK v2 client bean; never {@code null}
     * @param kmsKeyArn the configured CMK ARN; may be blank in local profile,
     *                  in which case PUT calls will fail with a typed
     *                  exception rather than silently writing unencrypted
     *                  objects
     * @throws NullPointerException if {@code s3Client} is {@code null}
     */
    public S3OutputService(S3Client s3Client,
                           @Value("${carddemo.aws.kms.key-arn:}") String kmsKeyArn) {
        // Replaces: WRITE TO DALYREJS / SYSTRAN / TRANREPT / STMTFILE sequential writes.
        // No constructor-time AWS API calls — bean wiring is pure.
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        // Empty string default is permitted at construction time so the bean
        // can wire successfully under the `local` profile (LocalStack ignores
        // SSE-KMS); PUT operations validate the ARN at call time so a missing
        // ARN fails the call rather than silently writing unencrypted objects.
        this.kmsKeyArn = (kmsKeyArn == null) ? "" : kmsKeyArn.trim();
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Writes a UTF-8 text payload (typically a multi-line COBOL-format report
     * image or rejection record line) to the supplied S3 object location.
     *
     * <p>Convenience wrapper for the common case where the upstream COBOL
     * program produced a single text record buffer (e.g., {@code DALYREJS}
     * rejection record). The content is encoded as UTF-8 and forwarded to
     * {@link #putObject(String, String, byte[], String)}.</p>
     *
     * @param bucket      target S3 bucket (e.g., {@code carddemo-rejects});
     *                    must not be {@code null} or blank
     * @param key         object key (typically including date prefix and
     *                    sequence, e.g.,
     *                    {@code rejections/2024/05/22/batch-001.txt}); must not
     *                    be {@code null} or blank
     * @param content     UTF-8 text content to write; must not be {@code null}
     *                    (zero-length is allowed for marker objects)
     * @return the SDK {@link PutObjectResponse} (containing ETag and
     *         version ID); never {@code null} on success
     * @throws IllegalArgumentException if any argument is {@code null} or
     *                                  if {@code bucket} / {@code key} are
     *                                  blank or violate the safety filters
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING} or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    public PutObjectResponse putText(String bucket, String key, String content) {
        // Replaces: WRITE DALYREJS-RECORD / WRITE TRANREPT-LINE — text payload
        Objects.requireNonNull(content, "content must not be null");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return putObject(bucket, key, bytes, "text/plain; charset=UTF-8");
    }

    /**
     * Writes a binary byte payload to the supplied S3 object location.
     *
     * <p>Used by callers that have already serialized a record buffer to
     * bytes (for example, a fixed-width COBOL report image that contains
     * non-ASCII bytes) or that need to specify an explicit content type.</p>
     *
     * <p>Every PUT issued via this method is encrypted at rest using
     * SSE-KMS with the customer-managed key {@code carddemo.aws.kms.key-arn}
     * &mdash; a missing or blank ARN fails the call with a typed
     * {@link CardDemoException} (reason code
     * {@link #REASON_CODE_KMS_KEY_MISSING}) rather than silently producing
     * an unencrypted object.</p>
     *
     * @param bucket      target S3 bucket; must not be {@code null} or blank
     * @param key         object key; must not be {@code null} or blank
     * @param content     payload bytes; must not be {@code null} (zero-length
     *                    is permitted)
     * @param contentType MIME content type for the object; may be {@code null}
     *                    or blank, in which case the SDK default is used
     * @return the SDK {@link PutObjectResponse} on success; never {@code null}
     * @throws IllegalArgumentException if {@code bucket}/{@code key} are
     *                                  {@code null}/blank or contain illegal
     *                                  characters
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING} if no CMK ARN is configured,
     *         or {@link #REASON_CODE_PUT_ERROR} on any S3 transport / service
     *         error
     */
    public PutObjectResponse putObject(String bucket,
                                       String key,
                                       byte[] content,
                                       String contentType) {
        // Replaces: WRITE record TO sequential PS / GDG datasets (CBTRN02C,
        // CBACT04C, CBTRN03C, CBSTM03A/B) — binary payload variant.
        validateBucket(bucket);
        validateKey(key);
        Objects.requireNonNull(content, "content must not be null");
        validateKmsKeyArn();

        PutObjectRequest request = buildPutRequest(bucket, key, contentType, (long) content.length);
        try {
            PutObjectResponse response = s3Client.putObject(request,
                    RequestBody.fromBytes(content));
            // PCI-DSS-safe logging: metadata only — never the content body
            // (per AAP §0.6.6 — no plaintext financial data in logs).
            LOG.info("S3 PUT OK bucket={} key={} bytes={} eTag={} versionId={}",
                    bucket, key, content.length,
                    response.eTag(), response.versionId());
            return response;
        } catch (S3Exception e) {
            LOG.error("S3 PUT FAILED bucket={} key={} bytes={} cause={}",
                    bucket, key, content.length, e.getMessage(), e);
            throw new CardDemoException(
                    REASON_CODE_PUT_ERROR,
                    "Failed to PUT object s3://" + bucket + "/" + key,
                    e);
        }
    }

    /**
     * Writes the contents of an {@link InputStream} to S3 with the supplied
     * content length and type. Used by Spring Batch writers that emit large
     * report images record by record and cannot materialize the full payload
     * in memory.
     *
     * <p>The {@code contentLength} parameter is REQUIRED by AWS SDK v2 for
     * streaming PUT &mdash; the SDK uses it to size the upload and to set
     * the {@code Content-Length} header. Callers that cannot determine the
     * length up front should buffer to a byte array and call
     * {@link #putObject(String, String, byte[], String)} instead.</p>
     *
     * @param bucket         target S3 bucket; must not be {@code null}/blank
     * @param key            object key; must not be {@code null}/blank
     * @param stream         the input stream supplying the payload bytes; must
     *                       not be {@code null}; the caller is responsible
     *                       for closing the stream (the SDK closes the inner
     *                       wrapper but not the supplied one)
     * @param contentLength  the exact length in bytes of the supplied stream;
     *                       must be &ge; 0
     * @param contentType    MIME content type; may be {@code null} or blank
     * @return the SDK {@link PutObjectResponse} on success
     * @throws IllegalArgumentException if any required arg is invalid
     * @throws CardDemoException with reason code
     *         {@link #REASON_CODE_KMS_KEY_MISSING} or
     *         {@link #REASON_CODE_PUT_ERROR}
     */
    public PutObjectResponse putStream(String bucket,
                                       String key,
                                       InputStream stream,
                                       long contentLength,
                                       String contentType) {
        // Replaces: streamed COBOL WRITE for large report images / statements.
        validateBucket(bucket);
        validateKey(key);
        Objects.requireNonNull(stream, "stream must not be null");
        if (contentLength < 0L) {
            throw new IllegalArgumentException("contentLength must be >= 0");
        }
        validateKmsKeyArn();

        PutObjectRequest request = buildPutRequest(bucket, key, contentType, contentLength);
        try {
            PutObjectResponse response = s3Client.putObject(request,
                    RequestBody.fromInputStream(stream, contentLength));
            LOG.info("S3 PUT-stream OK bucket={} key={} bytes={} eTag={} versionId={}",
                    bucket, key, contentLength,
                    response.eTag(), response.versionId());
            return response;
        } catch (S3Exception e) {
            LOG.error("S3 PUT-stream FAILED bucket={} key={} bytes={} cause={}",
                    bucket, key, contentLength, e.getMessage(), e);
            throw new CardDemoException(
                    REASON_CODE_PUT_ERROR,
                    "Failed to PUT stream object s3://" + bucket + "/" + key,
                    e);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Builds the AWS SDK v2 {@link PutObjectRequest} with all CardDemo
     * security invariants applied: SSE-KMS encryption with the configured
     * CMK ARN, explicit content length, optional content type. No public
     * ACL is set &mdash; the default bucket-owner ACL is used.
     */
    private PutObjectRequest buildPutRequest(String bucket,
                                             String key,
                                             String contentType,
                                             Long contentLength) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                // AAP §0.6.6 & §0.7.1 — SSE-KMS on every PUT. The CMK ARN
                // is the customer-managed key declared in
                // infrastructure/terraform/kms.tf and referenced by
                // carddemo.aws.kms.key-arn.
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(kmsKeyArn);
        if (contentType != null && !contentType.isBlank()) {
            builder.contentType(contentType);
        }
        if (contentLength != null && contentLength >= 0L) {
            builder.contentLength(contentLength);
        }
        return builder.build();
    }

    /**
     * Validates the bucket name. Per AWS bucket naming rules (3–63
     * characters, lowercase alphanumeric + hyphen), the application enforces
     * a conservative non-empty + no-newline check at the adapter boundary;
     * the SDK enforces full S3 naming rules server-side.
     */
    private static void validateBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be null/blank");
        }
        if (bucket.indexOf('\n') >= 0 || bucket.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bucket must not contain newlines");
        }
    }

    /**
     * Validates the object key. Rejects null/blank, leading slashes
     * (which AWS treats as a literal slash character — usually a caller
     * bug), and embedded newlines (always a caller bug).
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
     * Validates that a non-blank KMS CMK ARN is configured. Fails the call
     * loudly with a typed exception rather than silently producing an
     * unencrypted object &mdash; per AAP &sect;0.7.1 ("Encrypt all S3 buckets
     * with SSE-KMS"), there is no acceptable production scenario in which
     * an object is written without SSE-KMS.
     */
    private void validateKmsKeyArn() {
        if (kmsKeyArn == null || kmsKeyArn.isBlank()) {
            throw new CardDemoException(
                    REASON_CODE_KMS_KEY_MISSING,
                    "carddemo.aws.kms.key-arn is not configured — refusing to write S3 object without SSE-KMS",
                    null);
        }
    }
}
