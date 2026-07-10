package com.carddemo.batch;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 integration test — proves the batch buckets are <strong>versioned</strong> so a re-run's fixed
 * object key ({@code dalyrejs.dat}, {@code tranrept.dat}, {@code statements.*}) maps to a
 * <em>new S3 object version</em> rather than silently overwriting the prior one (QA finding
 * <strong>F1</strong>; AAP decision-log <strong>D-003</strong> "GDG generations map to versioned S3
 * objects", &sect;0.7.7 / &sect;0.8.5).
 *
 * <p><strong>The defect (before the fix).</strong> No code, config, or infrastructure enabled S3
 * bucket versioning, so {@code get-bucket-versioning} returned empty on all three buckets, objects
 * carried {@code VersionId=null}, and a re-run overwrote the previous generation (version count
 * stayed 1). The GDG&nbsp;&rarr;&nbsp;versioned-object mapping was unrealized.</p>
 *
 * <p><strong>The fix.</strong> Two coordinated changes make versioning real everywhere:</p>
 * <ul>
 *   <li><em>Production / local:</em> the startup {@code AwsResourceProvisioner}
 *       ({@code @Profile("!test")}, D-026) enables versioning on every batch bucket — covered by
 *       {@code AwsResourceProvisionerIT}.</li>
 *   <li><em>Integration tests:</em> that provisioner is inert under the {@code test} profile, so
 *       {@link AbstractBatchIntegrationTest#createBucket(String)} now enables versioning when it
 *       self-provisions — exercised by this test on the real {@link #BUCKET_OUTPUT}.</li>
 * </ul>
 *
 * <p>This test asserts, at runtime against LocalStack, that (1) the self-provisioned bucket reports
 * {@code Versioning=ENABLED}; (2) uploading the same fixed key twice retains <em>two</em> distinct,
 * non-null versions (never the QA-observed single {@code VersionId=null}); and (3) both generations'
 * bytes remain independently retrievable by version id — i.e. the prior generation genuinely
 * survives the re-run.</p>
 *
 * @see AbstractBatchIntegrationTest#createBucket(String)
 * @see com.carddemo.config.AwsResourceProvisioner
 * @see com.carddemo.config.AwsResourceProvisionerIT
 */
@DisplayName("S3 bucket versioning IT — GDG generation -> versioned object (F1, D-003/D-026)")
class S3BucketVersioningIT extends AbstractBatchIntegrationTest {

    /** A fixed object key, exactly as the posting writer uses (a batch re-run reuses the same key). */
    private static final String OBJECT_KEY = "dalyrejs.dat";

    /** The first "GDG generation" written by an initial run. */
    private static final byte[] GENERATION_1 =
            "GDG generation +1 (first run)\n".getBytes(StandardCharsets.UTF_8);

    /** The second "GDG generation" written by a re-run to the same key. */
    private static final byte[] GENERATION_2 =
            "GDG generation +2 (second run)\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        // createBucket now enables versioning (F1); this mirrors the prod AwsResourceProvisioner.
        createBucket(BUCKET_OUTPUT);
    }

    @AfterEach
    void tearDown() {
        // The version-aware teardown removes every version + delete marker, then the bucket.
        deleteBucketRecursively(BUCKET_OUTPUT);
    }

    @Test
    @DisplayName("createBucket enables versioning; re-uploading a fixed key retains both generations as distinct versions")
    void versioningEnabled_reuploadRetainsPriorGeneration() {
        // 1) The self-provisioned bucket must report versioning ENABLED (QA saw an empty status).
        final BucketVersioningStatus status =
                s3Client.getBucketVersioning(request -> request.bucket(BUCKET_OUTPUT)).status();
        assertThat(status)
                .as("createBucket must enable versioning so GDG generations map to S3 object versions (F1)")
                .isEqualTo(BucketVersioningStatus.ENABLED);

        // 2) Upload the SAME fixed key twice, exactly as a batch re-run would (dalyrejs.dat is fixed).
        putObject(BUCKET_OUTPUT, OBJECT_KEY, GENERATION_1);
        putObject(BUCKET_OUTPUT, OBJECT_KEY, GENERATION_2);

        // 3) Both generations must be retained as two distinct, non-null versions (no overwrite).
        final List<ObjectVersion> versions =
                s3Client.listObjectVersions(request -> request.bucket(BUCKET_OUTPUT).prefix(OBJECT_KEY))
                        .versions().stream()
                        .filter(version -> OBJECT_KEY.equals(version.key()))
                        .toList();
        assertThat(versions)
                .as("re-uploading %s must create a NEW version, preserving the prior generation (F1)", OBJECT_KEY)
                .hasSize(2);
        assertThat(versions).extracting(ObjectVersion::versionId)
                .as("each retained generation must carry a distinct, non-null VersionId (QA observed VersionId=null)")
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(versions).filteredOn(ObjectVersion::isLatest)
                .as("exactly one version is the current (latest) generation")
                .hasSize(1);

        // 4) Each version's bytes are independently retrievable and preserved (true generation retention).
        final ObjectVersion latest = versions.stream()
                .filter(ObjectVersion::isLatest).findFirst().orElseThrow();
        final ObjectVersion prior = versions.stream()
                .filter(version -> !version.isLatest()).findFirst().orElseThrow();
        final byte[] latestBytes = s3Client.getObjectAsBytes(
                request -> request.bucket(BUCKET_OUTPUT).key(OBJECT_KEY).versionId(latest.versionId()))
                .asByteArray();
        final byte[] priorBytes = s3Client.getObjectAsBytes(
                request -> request.bucket(BUCKET_OUTPUT).key(OBJECT_KEY).versionId(prior.versionId()))
                .asByteArray();
        assertThat(latestBytes)
                .as("the latest version must hold the second generation")
                .isEqualTo(GENERATION_2);
        assertThat(priorBytes)
                .as("the prior generation must survive the re-run (GDG retention, not overwrite)")
                .isEqualTo(GENERATION_1);
    }
}
