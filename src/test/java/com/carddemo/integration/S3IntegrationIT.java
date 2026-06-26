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
package com.carddemo.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LocalStack integration test for the <strong>AWS S3 object-storage bridge</strong>
 * (Validation Gate&nbsp;5 + the LocalStack Verification rule).
 *
 * <h2>What legacy behaviour this proves</h2>
 * The mainframe staged its batch artifacts in <em>Generation Data Groups</em> (GDGs). The
 * frozen source JCL defines those bases:
 * <ul>
 *   <li>{@code app/jcl/DEFGDGB.jcl} — six GDG bases (TRANSACT.BKUP, TRANSACT.DALY,
 *       TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED), each {@code LIMIT(5)};</li>
 *   <li>{@code app/jcl/REPTFILE.jcl} — the {@code TRANREPT} report GDG, {@code LIMIT(10)};</li>
 *   <li>{@code app/jcl/DALYREJS.jcl} — the {@code DALYREJS} reject GDG, {@code LIMIT(5)}.</li>
 * </ul>
 * The migration collapses those GDG bases into <strong>three versioned S3 buckets</strong>
 * — {@code carddemo-batch-input}, {@code carddemo-batch-output} and
 * {@code carddemo-statements} — preserving the input / output / statement separation of
 * the batch pipeline. A GDG {@code (+1)} <em>generation</em> (a new, never-overwritten copy
 * of the dataset) maps to a new <em>object version</em> in S3; the {@code LIMIT(n)}
 * retention maps to S3 object-version retention. This IT exercises that contract end-to-end
 * against a real S3 implementation (LocalStack) so the GDG→S3 bridge is verified, not
 * assumed.
 *
 * <h2>How it runs</h2>
 * The class extends {@link AbstractIntegrationIT}, which brings up a single shared
 * LocalStack container (S3 / SQS / SNS) via Testcontainers and is already annotated
 * {@code @SpringBootTest} / {@code @ActiveProfiles("test")} / {@code @Testcontainers}; this
 * subclass therefore adds no container or profile annotations of its own. Every S3 call goes
 * through the base {@link AbstractIntegrationIT#newS3Client()} factory, which targets
 * LocalStack with path-style addressing and the dummy {@code test}/{@code test} credentials,
 * so there is <strong>zero live-AWS dependency and no real credential</strong> anywhere in
 * this suite.
 *
 * <h2>Resource ownership and isolation</h2>
 * Bucket names are never hardcoded — they are read from the injected, config-bound
 * {@code AwsConfig.AwsResourceProperties} through the base accessors
 * {@link AbstractIntegrationIT#inputBucket()}, {@link AbstractIntegrationIT#outputBucket()}
 * and {@link AbstractIntegrationIT#statementBucket()}. {@link #provisionCanonicalAwsResources()}
 * (idempotent) guarantees the three canonical buckets exist before each test. Every test
 * creates uniquely-named objects (or a uniquely-named bucket) and removes them in a
 * {@code finally} block and/or {@link #tearDown()}, so the tests leave no residue for sibling
 * ITs or the {@code gates/} suite and are independently re-runnable.
 */
@DisplayName("S3 object-storage bridge IT — LocalStack (GDG→S3, Gate 5)")
public class S3IntegrationIT extends AbstractIntegrationIT {

    /** Common key prefix for the throwaway objects this IT writes to the canonical buckets. */
    private static final String IT_KEY_PREFIX = "it/";

    /**
     * Fixed record width of the legacy daily transaction report (feature F-022 — 133-byte
     * print line). Used by {@link #largeFixedWidthObjectRoundTrip()} to assert that S3 keeps a
     * multi-record fixed-width report byte-exact, which the batch report writer relies on.
     */
    private static final int REPORT_RECORD_WIDTH = 133;

    /**
     * Name of the unique, versioning-enabled bucket created by
     * {@link #versionedObjectsMimicGdgGenerations()}. Stored as instance state (set
     * <em>before</em> the bucket is created) so {@link #tearDown()} can always reclaim it —
     * including all object versions and delete markers — even if the test fails part-way.
     * {@code null} when no versioned bucket is in play for the current test.
     */
    private String versionedBucket;

    /**
     * Ensures the three canonical buckets (and the rest of the canonical AWS resources) exist
     * before each test. The base helper is idempotent, so repeated invocation across the suite
     * is safe and cheap; it mirrors the provisioning that {@code localstack-init/init-aws.sh}
     * performs for the running application, keeping the test contract aligned with production.
     */
    @BeforeEach
    void provisionCanonicalBuckets() {
        provisionCanonicalAwsResources();
    }

    /**
     * Restores a clean S3 state after each test: empties the three canonical buckets and, if a
     * test created a unique versioned bucket, deletes all of its versions / delete markers and
     * then the bucket itself. The shared LocalStack container is never torn down here.
     */
    @AfterEach
    void tearDown() {
        emptyBucket(inputBucket());
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
        if (versionedBucket != null) {
            deleteVersionedBucketQuietly(versionedBucket);
            versionedBucket = null;
        }
    }

    /**
     * Gate&nbsp;5 / GDG-base parity: the three canonical buckets that replace the GDG bases are
     * provisioned and visible. Confirms the base provisioning (and, by parity, the
     * {@code init-aws.sh} provisioning) created exactly the buckets the application config
     * names.
     */
    @Test
    @DisplayName("the three canonical GDG-replacement buckets exist")
    void allThreeCanonicalBucketsExist() {
        try (S3Client s3 = newS3Client()) {
            List<String> bucketNames = s3.listBuckets().buckets().stream()
                    .map(Bucket::name)
                    .toList();

            assertThat(bucketNames)
                    .contains(inputBucket(), outputBucket(), statementBucket());
        }
    }

    /**
     * Path-style addressing round-trip: an object written to the output bucket is read back
     * byte-for-byte. LocalStack only supports path-style S3 addressing, and the base client
     * factory enables it ({@code forcePathStyle(true)}); this test fails fast if that ever
     * regresses.
     */
    @Test
    @DisplayName("put/get round-trips bytes exactly using path-style addressing")
    void putGetRoundTripWithPathStyleAddressing() {
        final String bucket = outputBucket();
        final String key = IT_KEY_PREFIX + "sample-" + UUID.randomUUID() + ".txt";
        final byte[] body =
                "CardDemo S3 path-style round-trip payload".getBytes(StandardCharsets.US_ASCII);

        try (S3Client s3 = newS3Client()) {
            try {
                s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(body));

                ResponseBytes<GetObjectResponse> roundTrip = s3.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build());

                assertThat(roundTrip.asByteArray()).isEqualTo(body);
                assertThat(new String(roundTrip.asByteArray(), StandardCharsets.US_ASCII))
                        .isEqualTo(new String(body, StandardCharsets.US_ASCII));
            } finally {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            }
        }
    }

    /**
     * Listing parity: every key written under a unique prefix is returned by
     * {@code listObjectsV2}. This is the access pattern the batch readers/writers use to
     * enumerate staged generations within a bucket.
     */
    @Test
    @DisplayName("listObjectsV2 returns every key written under a prefix")
    void listObjectsReturnsWrittenKeys() {
        final String bucket = outputBucket();
        final String prefix = IT_KEY_PREFIX + "list-" + UUID.randomUUID() + "/";
        final List<String> keys = List.of(
                prefix + "generation-001.dat",
                prefix + "generation-002.dat",
                prefix + "generation-003.dat");

        try (S3Client s3 = newS3Client()) {
            try {
                for (String key : keys) {
                    s3.putObject(
                            PutObjectRequest.builder().bucket(bucket).key(key).build(),
                            RequestBody.fromString("payload for " + key, StandardCharsets.US_ASCII));
                }

                ListObjectsV2Response listing = s3.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
                List<String> returnedKeys = listing.contents().stream()
                        .map(S3Object::key)
                        .toList();

                assertThat(returnedKeys).containsExactlyInAnyOrderElementsOf(keys);
            } finally {
                for (String key : keys) {
                    s3.deleteObject(
                            DeleteObjectRequest.builder().bucket(bucket).key(key).build());
                }
            }
        }
    }

    /**
     * GDG generation parity: the AAP maps GDG {@code (+1)} generations (each a new,
     * never-overwritten copy of a dataset) to <em>versioned S3 objects</em>. This test
     * creates a unique, versioning-enabled bucket, writes the same key twice with different
     * bodies, and proves that S3 retains both as distinct versions whose bytes can each be
     * fetched back by version id — exactly the retention behaviour a GDG provides.
     *
     * <p>The canonical buckets are not versioning-enabled by the base provisioning, so this
     * test owns a private bucket and reclaims it (all versions + delete markers + the bucket)
     * in {@link #tearDown()}.
     */
    @Test
    @DisplayName("versioned objects mimic GDG (+1) generation retention")
    void versionedObjectsMimicGdgGenerations() {
        // Set the field BEFORE creating the bucket so tearDown always reclaims it, even on a
        // mid-test failure. Bucket names must be lowercase and <= 63 chars (this is ~58).
        versionedBucket = "carddemo-it-versioned-" + UUID.randomUUID();
        final String key = "gdg/transact-backup.dat";
        final byte[] generationOne =
                "GENERATION-0001 transaction backup".getBytes(StandardCharsets.US_ASCII);
        final byte[] generationTwo =
                "GENERATION-0002 transaction backup".getBytes(StandardCharsets.US_ASCII);

        try (S3Client s3 = newS3Client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(versionedBucket).build());
            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(versionedBucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());

            // Versioning must actually be ENABLED for generation semantics to hold.
            assertThat(s3.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(versionedBucket)
                    .build()).status())
                    .isEqualTo(BucketVersioningStatus.ENABLED);

            // Two writes to the SAME key == two GDG generations.
            String versionIdOne = s3.putObject(
                    PutObjectRequest.builder().bucket(versionedBucket).key(key).build(),
                    RequestBody.fromBytes(generationOne)).versionId();
            String versionIdTwo = s3.putObject(
                    PutObjectRequest.builder().bucket(versionedBucket).key(key).build(),
                    RequestBody.fromBytes(generationTwo)).versionId();

            ListObjectVersionsResponse versions = s3.listObjectVersions(
                    ListObjectVersionsRequest.builder().bucket(versionedBucket).prefix(key).build());
            List<String> versionIds = versions.versions().stream()
                    .map(ObjectVersion::versionId)
                    .toList();

            assertThat(versionIds)
                    .hasSizeGreaterThanOrEqualTo(2)
                    .doesNotHaveDuplicates()
                    .contains(versionIdOne, versionIdTwo);

            // Each retained generation is independently retrievable, byte-for-byte.
            byte[] fetchedOne = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(versionedBucket).key(key).versionId(versionIdOne).build()).asByteArray();
            byte[] fetchedTwo = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(versionedBucket).key(key).versionId(versionIdTwo).build()).asByteArray();

            assertThat(fetchedOne).isEqualTo(generationOne);
            assertThat(fetchedTwo).isEqualTo(generationTwo);
        }
    }

    /**
     * Deletion parity: once an object is deleted from a (non-versioned) canonical bucket, a
     * subsequent {@code getObject} fails with {@link NoSuchKeyException}. This is the status
     * the {@code FileStatusMapper} treats as VSAM {@code FILE STATUS 23} (record-not-found),
     * so the contract must surface a typed not-found rather than an empty body.
     */
    @Test
    @DisplayName("a deleted object is gone (getObject throws NoSuchKeyException)")
    void objectDeletedIsGone() {
        final String bucket = outputBucket();
        final String key = IT_KEY_PREFIX + "ephemeral-" + UUID.randomUUID() + ".txt";

        try (S3Client s3 = newS3Client()) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString("temporary", StandardCharsets.US_ASCII));
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

            GetObjectRequest getDeleted =
                    GetObjectRequest.builder().bucket(bucket).key(key).build();
            assertThatThrownBy(() -> s3.getObjectAsBytes(getDeleted))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    /**
     * Fixed-width fidelity: a multi-record fixed-width report (the legacy 133-byte print line,
     * feature F-022) round-trips through S3 byte-for-byte with every record boundary intact.
     * This reinforces the batch report writer's S3 contract — S3 must not re-encode, trim, or
     * re-wrap fixed-width records, or the byte-equivalence gates (Gates&nbsp;1 and&nbsp;4) would
     * fail.
     */
    @Test
    @DisplayName("a multi-record 133-byte fixed-width report round-trips with width intact")
    void largeFixedWidthObjectRoundTrip() {
        final String bucket = outputBucket();
        final String key = IT_KEY_PREFIX + "report-" + UUID.randomUUID() + ".txt";
        final int recordCount = 25;

        StringBuilder report = new StringBuilder(REPORT_RECORD_WIDTH * recordCount);
        for (int i = 0; i < recordCount; i++) {
            String content = "CARDDEMO TRANSACTION DETAIL REPORT - RECORD " + (i + 1);
            report.append(toFixedWidth(content, REPORT_RECORD_WIDTH));
        }
        final byte[] body = report.toString().getBytes(StandardCharsets.US_ASCII);
        // Guard the test's own fixture: the body must be an exact multiple of the record width.
        assertThat(body).hasSize(REPORT_RECORD_WIDTH * recordCount);

        try (S3Client s3 = newS3Client()) {
            try {
                s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(body));

                byte[] fetched = s3.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();

                assertThat(fetched).hasSize(REPORT_RECORD_WIDTH * recordCount);
                assertThat(fetched.length % REPORT_RECORD_WIDTH).isZero();
                assertThat(fetched).isEqualTo(body);

                // Every record boundary survives the round-trip exactly.
                String text = new String(fetched, StandardCharsets.US_ASCII);
                for (int i = 0; i < recordCount; i++) {
                    String record = text.substring(
                            i * REPORT_RECORD_WIDTH, (i + 1) * REPORT_RECORD_WIDTH);
                    assertThat(record).hasSize(REPORT_RECORD_WIDTH);
                }
            } finally {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            }
        }
    }

    /**
     * Right-pads (or truncates) {@code value} to exactly {@code width} characters using spaces,
     * reproducing the fixed-width, space-filled field layout of the legacy print records.
     *
     * @param value the record content
     * @param width the target fixed width
     * @return a string of exactly {@code width} characters
     */
    private static String toFixedWidth(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Deletes a versioning-enabled bucket and everything in it: first every object version and
     * delete marker (a versioned bucket cannot be deleted while any version remains), then the
     * bucket itself. Tolerates an absent bucket so it is safe to call from {@link #tearDown()}
     * even when the bucket was never created.
     *
     * @param bucket the unique test bucket to reclaim
     */
    private static void deleteVersionedBucketQuietly(String bucket) {
        try (S3Client s3 = newS3Client()) {
            ListObjectVersionsResponse versions = s3.listObjectVersions(
                    ListObjectVersionsRequest.builder().bucket(bucket).build());
            for (ObjectVersion version : versions.versions()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(version.key()).versionId(version.versionId()).build());
            }
            for (DeleteMarkerEntry marker : versions.deleteMarkers()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(marker.key()).versionId(marker.versionId()).build());
            }
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException absent) {
            // Bucket was never created (or already removed) — nothing to reclaim.
        }
    }

}
