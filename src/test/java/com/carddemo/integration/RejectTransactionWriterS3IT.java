/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.integration;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.carddemo.batch.writer.RejectTransactionWriter;
import com.carddemo.batch.writer.RejectTransactionWriter.RejectedTransaction;
import com.carddemo.config.AwsConfig;
import com.carddemo.enums.RejectReasonCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.Jackson2JsonS3ObjectConverter;
import io.awspring.cloud.s3.PropertiesS3ObjectContentTypeResolver;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack integration test for {@link RejectTransactionWriter} (Validation Gate 5).
 *
 * <p>Exercises the real S3 upload contract against a Testcontainers LocalStack S3 service:
 * the writer streams its accumulated 430-byte reject records to a single versioned object in
 * the output bucket, and the test reads the object back to assert byte-exact round-trip and
 * the GDG {@code (+1)} never-overwrite semantics. The test creates and tears down its own
 * bucket and objects (no live AWS, no shared state).
 */
@Testcontainers
@DisplayName("RejectTransactionWriter S3 IT — LocalStack round-trip (Gate 5)")
class RejectTransactionWriterS3IT {

    private static final String BUCKET = "carddemo-batch-output";
    private static final String PREFIX = "rejects/dalyrejs/";
    private static final int RECORD_WIDTH = 430;
    private static final int FRAMED_WIDTH = RECORD_WIDTH + 1; // 430 + LF

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices("s3");

    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private S3Template s3Template;
    private AwsConfig.AwsResourceProperties properties;
    private Map<RejectReasonCode, Counter> counters;

    @BeforeEach
    void setUp() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
        Region region = Region.of(LOCALSTACK.getRegion());

        s3Client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3Presigner = S3Presigner.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .build();
        s3Template = new S3Template(
                s3Client,
                new InMemoryBufferingS3OutputStreamProvider(s3Client, new PropertiesS3ObjectContentTypeResolver()),
                new Jackson2JsonS3ObjectConverter(new ObjectMapper()),
                s3Presigner);

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        properties = new AwsConfig.AwsResourceProperties();
        properties.getS3().setOutputBucket(BUCKET);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        counters = new EnumMap<>(RejectReasonCode.class);
        for (RejectReasonCode reason : RejectReasonCode.values()) {
            if (reason == RejectReasonCode.NONE) {
                continue;
            }
            counters.put(reason, Counter.builder("carddemo.batch.records.rejected")
                    .tag("reason", String.valueOf(reason.getCode()))
                    .register(registry));
        }
    }

    @AfterEach
    void tearDown() {
        try {
            for (S3Object object : listRejectObjects()) {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(object.key()).build());
            }
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
        } finally {
            if (s3Presigner != null) {
                s3Presigner.close();
            }
            if (s3Client != null) {
                s3Client.close();
            }
        }
    }

    private List<S3Object> listRejectObjects() {
        return s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).prefix(PREFIX).build()).contents();
    }

    @Test
    @DisplayName("uploads one versioned object whose bytes round-trip exactly (3 rejects → 3×431 bytes)")
    void uploadsSingleObjectWithExactBytes() throws Exception {
        String image = "D".repeat(350);

        RejectTransactionWriter writer = new RejectTransactionWriter(s3Template, properties, counters, 100L);
        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of(
                new RejectedTransaction(image, RejectReasonCode.OVER_CREDIT_LIMIT),
                new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND),
                new RejectedTransaction(image, RejectReasonCode.ACCOUNT_EXPIRED))));
        writer.close();

        List<S3Object> objects = listRejectObjects();
        assertThat(objects).hasSize(1);
        String key = objects.get(0).key();
        assertThat(key).startsWith(PREFIX);

        byte[] content = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asByteArray();

        assertThat(content).hasSize(3 * FRAMED_WIDTH);
        assertThat(content.length % FRAMED_WIDTH).isZero();

        String firstRecord = new String(content, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(firstRecord.substring(0, 350)).isEqualTo(image);
        assertThat(firstRecord.substring(350, 354)).isEqualTo("0102");
        assertThat(content[RECORD_WIDTH]).isEqualTo((byte) '\n');

        assertThat(counters.get(RejectReasonCode.OVER_CREDIT_LIMIT).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("distinct runs create distinct objects (GDG +1 never overwrites a prior generation)")
    void distinctRunsCreateDistinctObjects() throws Exception {
        String image = "E".repeat(350);

        for (long jobExecutionId : new long[] {201L, 202L}) {
            RejectTransactionWriter writer =
                    new RejectTransactionWriter(s3Template, properties, counters, jobExecutionId);
            writer.open(new ExecutionContext());
            writer.write(new Chunk<>(List.of(
                    new RejectedTransaction(image, RejectReasonCode.CARD_NOT_FOUND))));
            writer.close();
        }

        List<S3Object> objects = listRejectObjects();
        assertThat(objects).hasSize(2);
        assertThat(objects.get(0).key()).isNotEqualTo(objects.get(1).key());
    }

    @Test
    @DisplayName("zero rejects → no object is created (no empty GDG generation)")
    void zeroRejectsCreatesNoObject() throws Exception {
        RejectTransactionWriter writer = new RejectTransactionWriter(s3Template, properties, counters, 300L);
        writer.open(new ExecutionContext());
        writer.write(new Chunk<>(List.of()));
        writer.close();

        assertThat(listRejectObjects()).isEmpty();
    }
}
