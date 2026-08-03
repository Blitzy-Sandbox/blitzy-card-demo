/*
 * ******************************************************************
 * Program     : AwsConfigLocalOnlyGuardTest.java
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Proves the fail-fast contract of com.cardemo.config.AwsConfig -
 *               the no-live-AWS endpoint and credential guard, and the strict
 *               FIFO queue-name rule.
 *               Derived from app/csd/CARDDEMO.CSD:L499-L503
 *               (DEFINE TDQUEUE(JOBS) ... RECORDSIZE(80) RECORDFORMAT(FIXED)
 *               DISPOSITION(MOD)) and app/jcl/DEFGDGB.jcl, whose seven
 *               generation data group bases become the S3 layout the class
 *               configures.
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.config.AwsConfig;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the two fail-fast guards {@link AwsConfig} applies in its constructor.
 *
 * <p><strong>What these tests are for.</strong> Both guards exist to make a misconfiguration impossible to
 * run rather than merely unlikely, and a guard exercised only by hand is a guard that quietly stops working.
 * Each test below drives the constructor with one bad value against an otherwise-valid configuration, so a
 * failure names exactly which condition stopped being enforced.
 *
 * <p><strong>Why the constructor and not a Spring context.</strong> The guards must run <em>before</em> any
 * client is built, and the constructor is where that ordering is established: the bean methods that create the
 * S3, SQS and SNS templates cannot execute until it returns. Constructing the class directly asserts the
 * ordering property itself rather than a consequence of it, needs no container, and keeps this tier free of
 * any real endpoint or credential.
 *
 * <p><strong>No offending value is ever asserted to appear in a message.</strong> Every assertion checks for
 * the property key and the reason, because the class deliberately withholds values from its diagnostics. Two
 * tests assert that withholding explicitly. Note that the message legitimately <em>names</em> the
 * credential properties, so an assertion must be written against the value that was supplied rather than
 * against a word such as "secret" which the property name itself contains.
 */
@DisplayName("AwsConfig: the no-live-AWS guard and the strict FIFO queue rule")
final class AwsConfigLocalOnlyGuardTest {

    private static final String REGION = "us-east-1";
    private static final String INPUT_BUCKET = "carddemo-batch-input";
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";
    private static final String STATEMENTS_BUCKET = "carddemo-statements";
    private static final String LOGICAL_QUEUE = "carddemo-report-jobs";
    private static final String PHYSICAL_QUEUE = LOGICAL_QUEUE + ".fifo";
    private static final String TOPIC = "carddemo-notifications";
    private static final String LOCAL_ENDPOINT = "http://localhost:4566";
    private static final String TEST_KEY = "test";

    /**
     * Builds the class with every value valid except those passed, so each test varies exactly one thing.
     *
     * @param s3Endpoint  object-storage endpoint
     * @param sqsEndpoint queue endpoint
     * @param snsEndpoint notification endpoint
     * @param accessKey   static access key
     * @param secretKey   static secret key
     * @param physical    physical queue name
     * @return a constructed instance, when the configuration is accepted
     */
    private static AwsConfig build(
            final String s3Endpoint,
            final String sqsEndpoint,
            final String snsEndpoint,
            final String accessKey,
            final String secretKey,
            final String physical) {

        return new AwsConfig(REGION, INPUT_BUCKET, OUTPUT_BUCKET, STATEMENTS_BUCKET, physical, LOGICAL_QUEUE,
                TOPIC, QueueNotFoundStrategy.FAIL, s3Endpoint, sqsEndpoint, snsEndpoint, "",
                accessKey, secretKey);
    }

    /**
     * Builds with every value valid.
     *
     * @return a constructed instance
     */
    private static AwsConfig valid() {
        return build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT, TEST_KEY, TEST_KEY, PHYSICAL_QUEUE);
    }

    @Test
    @DisplayName("an emulator-only configuration is accepted")
    void theLocalOnlyConfigurationIsAccepted() {
        assertThatCode(AwsConfigLocalOnlyGuardTest::valid).doesNotThrowAnyException();
        assertThat(valid()).isNotNull();
    }

    @Nested
    @DisplayName("the endpoint allowlist")
    class Endpoints {

        @ParameterizedTest(name = "a permitted emulator endpoint is accepted: {0}")
        @ValueSource(strings = {
            "http://localhost:4566",
            "http://127.0.0.1:4566",
            "http://localhost.localstack.cloud:4566",
            "http://localstack:4566",
            "http://carddemo-localstack:4566",
            "http://host.docker.internal:4566",
            "https://localhost:4566",
            "http://localhost:32769"})
        void permittedEndpointsAreAccepted(final String endpoint) {
            assertThatCode(() -> build(endpoint, endpoint, endpoint, TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a real AWS host is refused, naming the approved-host rule")
        void aLiveHostIsRefused() {
            assertThatThrownBy(() -> build("https://s3.us-east-1.amazonaws.com:443",
                    LOCAL_ENDPOINT, LOCAL_ENDPOINT, TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint")
                    .hasMessageContaining("does not name one of the approved local emulator hosts");
        }

        @Test
        @DisplayName("an absent endpoint is refused rather than defaulted")
        void anAbsentEndpointIsRefused() {
            assertThatThrownBy(() -> build("", LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint");
        }

        @Test
        @DisplayName("an endpoint with no explicit port is refused")
        void aPortlessEndpointIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, "http://localhost", LOCAL_ENDPOINT,
                    TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sqs.endpoint")
                    .hasMessageContaining("explicit port");
        }

        @Test
        @DisplayName("a non-HTTP scheme is refused")
        void aForeignSchemeIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, "ftp://localhost:4566",
                    TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sns.endpoint");
        }

        @Test
        @DisplayName("credentials embedded in the URL are refused without echoing the value")
        void userInformationIsRefusedAndNotEchoed() {
            assertThatThrownBy(() -> build("http://uniqueuser:uniquepassphrase@localhost:4566",
                    LOCAL_ENDPOINT, LOCAL_ENDPOINT, TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("embeds userinfo before the host")
                    .hasMessageNotContaining("uniqueuser")
                    .hasMessageNotContaining("uniquepassphrase");
        }

        @Test
        @DisplayName("all three endpoints are guarded, not just the first")
        void allThreeEndpointsAreGuarded() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, "https://sqs.eu-west-1.amazonaws.com:443",
                    LOCAL_ENDPOINT, TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .hasMessageContaining("spring.cloud.aws.sqs.endpoint");
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "https://sns.eu-west-1.amazonaws.com:443", TEST_KEY, TEST_KEY, PHYSICAL_QUEUE))
                    .hasMessageContaining("spring.cloud.aws.sns.endpoint");
        }
    }

    @Nested
    @DisplayName("the credential guard")
    class Credentials {

        @ParameterizedTest(name = "a live-shaped access key is refused: {0}")
        @ValueSource(strings = {"AKIAIOSFODNN7EXAMPLE", "ASIAIOSFODNN7EXAMPLE"})
        void aLiveShapedAccessKeyIsRefused(final String key) {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    key, TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.credentials.access-key");
        }

        @Test
        @DisplayName("an absent access key is refused, because omission reopens the provider chain")
        void anAbsentAccessKeyIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    "", TEST_KEY, PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.credentials.access-key")
                    .hasMessageContaining("default provider chain");
        }

        @Test
        @DisplayName("a blank secret key is refused too")
        void aBlankSecretKeyIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    TEST_KEY, "   ", PHYSICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.credentials.secret-key");
        }
    }

    @Nested
    @DisplayName("the strict FIFO queue-name rule")
    class QueueNames {

        @Test
        @DisplayName("the unsuffixed logical name is refused, because a message group needs a FIFO queue")
        void theUnsuffixedNameIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    TEST_KEY, TEST_KEY, LOGICAL_QUEUE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue")
                    .hasMessageContaining(".fifo");
        }

        @Test
        @DisplayName("an unrelated queue name is refused even when it is suffixed")
        void anUnrelatedSuffixedNameIsRefused() {
            assertThatThrownBy(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    TEST_KEY, TEST_KEY, "someone-elses-queue.fifo"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue-logical-name");
        }

        @Test
        @DisplayName("only the logical name plus the suffix is accepted")
        void onlyTheDerivedNameIsAccepted() {
            assertThatCode(() -> build(LOCAL_ENDPOINT, LOCAL_ENDPOINT, LOCAL_ENDPOINT,
                    TEST_KEY, TEST_KEY, LOGICAL_QUEUE + ".fifo")).doesNotThrowAnyException();
        }
    }
}
