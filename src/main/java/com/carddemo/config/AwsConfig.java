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
package com.carddemo.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * AWS resource configuration for the CardDemo migration.
 *
 * <p>Registers {@link AwsResourceProperties}, the typed source of the externalized
 * {@code carddemo.aws} resource names (the three S3 buckets, the SQS FIFO report
 * queue, and the SNS notification topic) bound from {@code application.yml}. Services
 * and batch components inject {@link AwsResourceProperties} so every resource name has
 * a single source of truth and is never hardcoded at the call site.</p>
 *
 * <p>The {@code S3Client}, {@code SqsAsyncClient} and {@code SnsClient} (plus their
 * {@code S3Template} / {@code SqsTemplate} / {@code SnsTemplate}) are auto-configured
 * by Spring Cloud AWS from the {@code spring.cloud.aws.*} properties — region,
 * credentials, and the LocalStack endpoint / S3 path-style overrides supplied by the
 * {@code local} and {@code test} profiles; this class declares none of those beans.</p>
 */
@Configuration
@EnableConfigurationProperties(AwsConfig.AwsResourceProperties.class)
public class AwsConfig {

    /**
     * Type-safe AWS resource names bound from the {@code carddemo.aws} configuration
     * namespace (see {@code application.yml}). Each name is validated as non-blank at
     * startup so a missing or empty resource name fails fast.
     */
    @Validated
    @ConfigurationProperties(prefix = "carddemo.aws")
    public static class AwsResourceProperties {

        /**
         * S3 bucket names bound from {@code carddemo.aws.s3}.
         */
        @Valid
        private final S3 s3 = new S3();

        /**
         * SQS queue names bound from {@code carddemo.aws.sqs}.
         */
        @Valid
        private final Sqs sqs = new Sqs();

        /**
         * SNS topic names bound from {@code carddemo.aws.sns}.
         */
        @Valid
        private final Sns sns = new Sns();

        public S3 getS3() {
            return s3;
        }

        public Sqs getSqs() {
            return sqs;
        }

        public Sns getSns() {
            return sns;
        }

        /**
         * S3 bucket names that preserve the input / output / statement separation of
         * the batch pipeline.
         */
        public static class S3 {

            /**
             * Bucket holding batch input objects. Binds
             * {@code carddemo.aws.s3.input-bucket}.
             */
            @NotBlank
            private String inputBucket;

            /**
             * Bucket holding batch output objects. Binds
             * {@code carddemo.aws.s3.output-bucket}.
             */
            @NotBlank
            private String outputBucket;

            /**
             * Bucket holding generated statement objects. Binds
             * {@code carddemo.aws.s3.statement-bucket}.
             */
            @NotBlank
            private String statementBucket;

            public String getInputBucket() {
                return inputBucket;
            }

            public void setInputBucket(String inputBucket) {
                this.inputBucket = inputBucket;
            }

            public String getOutputBucket() {
                return outputBucket;
            }

            public void setOutputBucket(String outputBucket) {
                this.outputBucket = outputBucket;
            }

            public String getStatementBucket() {
                return statementBucket;
            }

            public void setStatementBucket(String statementBucket) {
                this.statementBucket = statementBucket;
            }
        }

        /**
         * SQS queue names used by the asynchronous report-submission flow.
         */
        public static class Sqs {

            /**
             * FIFO queue carrying report-submission requests. Binds
             * {@code carddemo.aws.sqs.report-queue}.
             */
            @NotBlank
            private String reportQueue;

            public String getReportQueue() {
                return reportQueue;
            }

            public void setReportQueue(String reportQueue) {
                this.reportQueue = reportQueue;
            }
        }

        /**
         * SNS topic names used for notification fan-out.
         */
        public static class Sns {

            /**
             * Topic used for report / notification fan-out. Binds
             * {@code carddemo.aws.sns.topic}.
             */
            @NotBlank
            private String topic;

            public String getTopic() {
                return topic;
            }

            public void setTopic(String topic) {
                this.topic = topic;
            }
        }
    }
}
