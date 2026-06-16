package com.carddemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Integration test for the S3 wiring (com.carddemo.config.AwsConfig S3Client) against LocalStack.
 * Re-platforms the GDG generation datasets (app/jcl/DEFGDGB.jcl, commit 27d6c6f, REFERENCE ONLY)
 * to S3 versioned objects: provisions the batch buckets, round-trips a fixture object, and asserts
 * the stored object layout (Gate 5 S3 object-layout contract).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3IntegrationIT extends AbstractAwsLocalStackIT {

    private static final String OBJECT_KEY = "s3-integration-it/transaction-backup-0001.dat";
    private static final byte[] FIXTURE_CONTENT =
            "DALY0000000001CARDDEMO TRANSACTION BACKUP GENERATION".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private S3Client s3Client;

    @BeforeAll
    void ensureBuckets() {
        ensureBucket(BUCKET_INPUT);
        ensureBucket(BUCKET_OUTPUT);
        ensureBucket(BUCKET_STATEMENTS);
    }

    @AfterAll
    void removeTestObject() {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET_INPUT)
                .key(OBJECT_KEY)
                .build());
    }

    @Test
    void batchBucketsExistAndAreAccessible() {
        assertThatCode(() -> s3Client.headBucket(HeadBucketRequest.builder().bucket(BUCKET_INPUT).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> s3Client.headBucket(HeadBucketRequest.builder().bucket(BUCKET_OUTPUT).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> s3Client.headBucket(HeadBucketRequest.builder().bucket(BUCKET_STATEMENTS).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void uploadedObjectIsRetrievableWithIdenticalLayout() {
        long expectedLength = FIXTURE_CONTENT.length;

        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(BUCKET_INPUT)
                        .key(OBJECT_KEY)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(FIXTURE_CONTENT));

        ResponseBytes<GetObjectResponse> stored = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(BUCKET_INPUT)
                .key(OBJECT_KEY)
                .build());

        assertThat(stored.asByteArray()).isEqualTo(FIXTURE_CONTENT);
        assertThat(stored.response().contentLength()).isEqualTo(expectedLength);

        ListObjectsV2Response listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET_INPUT)
                .prefix("s3-integration-it/")
                .build());
        assertThat(listing.contents()).extracting(S3Object::key).contains(OBJECT_KEY);
    }

    private void ensureBucket(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
    }
}
