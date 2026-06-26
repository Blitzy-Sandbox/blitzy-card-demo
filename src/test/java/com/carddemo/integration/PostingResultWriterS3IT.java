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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.carddemo.batch.processor.DailyTransactionRecordImage;
import com.carddemo.batch.processor.PostingResult;
import com.carddemo.batch.writer.PostedTransactionWriter;
import com.carddemo.batch.writer.PostingResultWriter;
import com.carddemo.batch.writer.RejectTransactionWriter;
import com.carddemo.config.AwsConfig;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.RejectReasonCode;
import com.carddemo.repository.TransactionRepository;
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
import org.mockito.Mockito;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * End-to-end LocalStack integration test proving the CP4 reject side-channel fix
 * (CRITICAL finding) through the real {@link PostingResultWriter} composite
 * (Validation Gate 5).
 *
 * <p>A mixed chunk of {@link PostingResult} values &mdash; one
 * {@link PostingResult.Posted} and two {@link PostingResult.Rejected} carrying a
 * real reconstructed 350-byte {@code CVTRA06Y} image &mdash; is routed through the
 * composite. The test asserts the reject arm reaches the real
 * {@link RejectTransactionWriter} and lands a single versioned object in the
 * output bucket whose bytes round-trip exactly to the 430-byte {@code DALYREJS}
 * contract, and that the posted arm reaches the {@link PostedTransactionWriter}
 * (verified through its repository). It creates and tears down its own bucket and
 * objects (no live AWS, no shared state).</p>
 */
@Testcontainers
@DisplayName("PostingResultWriter S3 IT — composite reject side-channel (Gate 5)")
class PostingResultWriterS3IT {

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
    private Map<RejectReasonCode, Counter> rejectedCounters;
    private Counter processedCounter;
    private TransactionRepository transactionRepository;

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
        rejectedCounters = new EnumMap<>(RejectReasonCode.class);
        for (RejectReasonCode reason : RejectReasonCode.values()) {
            if (reason == RejectReasonCode.NONE) {
                continue;
            }
            rejectedCounters.put(reason, Counter.builder("carddemo.batch.records.rejected")
                    .tag("reason", String.valueOf(reason.getCode()))
                    .register(registry));
        }
        processedCounter = Counter.builder("carddemo.batch.records.processed").register(registry);

        transactionRepository = Mockito.mock(TransactionRepository.class);
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

    private DailyTransaction sampleDailyTransaction() {
        return new DailyTransaction(
                "0000000000683580", "01", 1, "POS TERM", "Purchase at Abshire-Lowe",
                new BigDecimal("504.77"), 800000000L, "Abshire-Lowe", "North Enoshaven",
                "72112", "4859452612877065", "2022-06-10 19:27:53.000000", "");
    }

    @Test
    @DisplayName("mixed chunk: rejects land byte-exact in S3 and posted reaches the repository")
    void mixedChunkRoutesBothArms() throws Exception {
        DailyTransaction daily = sampleDailyTransaction();
        String image = DailyTransactionRecordImage.render(daily);
        assertThat(image).hasSize(350);

        PostedTransactionWriter postedWriter =
                new PostedTransactionWriter(transactionRepository, processedCounter);
        RejectTransactionWriter rejectWriter =
                new RejectTransactionWriter(s3Template, properties, rejectedCounters, 100L);
        PostingResultWriter composite = new PostingResultWriter(postedWriter, rejectWriter);

        Transaction posted = Mockito.mock(Transaction.class);
        Chunk<PostingResult> chunk = new Chunk<>();
        chunk.add(new PostingResult.Posted(posted));
        chunk.add(new PostingResult.Rejected(image, RejectReasonCode.OVER_CREDIT_LIMIT));
        chunk.add(new PostingResult.Rejected(image, RejectReasonCode.ACCOUNT_EXPIRED));

        composite.open(new ExecutionContext());
        composite.write(chunk);
        composite.close();

        // Reject arm: one versioned object, two 431-byte framed records, byte-exact.
        List<S3Object> objects = listRejectObjects();
        assertThat(objects).hasSize(1);
        String key = objects.get(0).key();
        assertThat(key).startsWith(PREFIX);

        byte[] content = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asByteArray();
        assertThat(content).hasSize(2 * FRAMED_WIDTH);

        String firstRecord = new String(content, 0, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(firstRecord.substring(0, 350)).isEqualTo(image);
        assertThat(firstRecord.substring(350, 354)).isEqualTo("0102"); // OVER_CREDIT_LIMIT
        assertThat(content[RECORD_WIDTH]).isEqualTo((byte) '\n');

        String secondRecord = new String(content, FRAMED_WIDTH, RECORD_WIDTH, StandardCharsets.US_ASCII);
        assertThat(secondRecord.substring(350, 354)).isEqualTo("0103"); // ACCOUNT_EXPIRED

        // Rejected metric owned by the writer increments once per durable reject.
        assertThat(rejectedCounters.get(RejectReasonCode.OVER_CREDIT_LIMIT).count()).isEqualTo(1.0d);
        assertThat(rejectedCounters.get(RejectReasonCode.ACCOUNT_EXPIRED).count()).isEqualTo(1.0d);

        // Posted arm: routed to the repository, processed metric incremented once.
        verify(transactionRepository).saveAll(any());
        assertThat(processedCounter.count()).isEqualTo(1.0d);
    }
}
