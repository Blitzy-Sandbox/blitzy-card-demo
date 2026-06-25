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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

/**
 * Spring Batch infrastructure configuration for the CardDemo batch pipeline.
 *
 * <p>Registers {@link BatchTuningProperties}, the typed source of the
 * externalized {@code carddemo.batch} tuning values. Job and step definitions
 * live under {@code com.carddemo.batch}.</p>
 */
@Configuration
@EnableConfigurationProperties(BatchConfig.BatchTuningProperties.class)
public class BatchConfig {

    /**
     * Type-safe batch tuning bound from the {@code carddemo.batch} configuration
     * namespace (see {@code application.yml}).
     */
    @ConfigurationProperties(prefix = "carddemo.batch")
    public static class BatchTuningProperties {

        /**
         * Chunk-oriented commit interval used by the batch readers, processors and
         * writers. Binds {@code carddemo.batch.chunk-size}; defaults to {@code 100}.
         */
        private int chunkSize = 100;

        /**
         * Interest-calculation tuning bound from {@code carddemo.batch.interest}.
         */
        private final Interest interest = new Interest();

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public Interest getInterest() {
            return interest;
        }

        /**
         * Interest-calculation batch tuning bound from
         * {@code carddemo.batch.interest}.
         */
        public static class Interest {

            /**
             * Default run date supplied as the interest-calculation job parameter
             * when none is provided at launch. Binds
             * {@code carddemo.batch.interest.default-run-date} (ISO
             * {@code yyyy-MM-dd}).
             */
            private LocalDate defaultRunDate;

            public LocalDate getDefaultRunDate() {
                return defaultRunDate;
            }

            public void setDefaultRunDate(LocalDate defaultRunDate) {
                this.defaultRunDate = defaultRunDate;
            }
        }
    }
}
