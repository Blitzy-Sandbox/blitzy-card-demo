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
package com.aws.carddemo.config;

import com.aws.carddemo.service.ReportJobDispatcher;
import com.aws.carddemo.service.ReportJobHandle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

/**
 * Application-wide infrastructure beans for the CardDemo COBOL-to-Java
 * migration. Provides the {@link Clock} seam and the {@link ReportJobDispatcher}
 * placeholder bean that the service layer's controller bean graph requires for
 * Spring to refresh the full application context.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The migrated service classes follow the constructor-injection pattern
 * with {@link Clock} as a deterministic timestamp seam (per AAP §0.10.9
 * "test execution independence" &mdash; production code reads
 * {@code clock.instant()} instead of {@code Instant.now()}, which lets tests
 * inject a fixed {@link Clock} for deterministic timestamp assertions).
 * Without a production {@link Clock} bean Spring cannot resolve those
 * constructor parameters and the application context refresh fails at
 * startup with {@code NoSuchBeanDefinitionException}.
 *
 * <p>{@link ReportJobDispatcher} is the Java replacement for the mainframe
 * Transient Data Queue submission flow in {@code app/cbl/CORPT00C.cbl}
 * ({@code WIRTE-JOBSUB-TDQ} paragraph). The migration's
 * {@code ReportSubmissionService} accepts a {@code ReportJobDispatcher} as a
 * constructor parameter and delegates the "submit this report job"
 * responsibility to it. The interface has no production implementation yet
 * (the actual SQS-/Lambda-/Spring-Batch-backed implementation will land in a
 * subsequent migration step); this class supplies a synchronous in-memory
 * placeholder that satisfies the bean graph at context refresh and surfaces
 * an opaque {@link ReportJobHandle} carrying a {@link UUID#randomUUID()}
 * identifier &mdash; sufficient to let the controllers compile and the
 * JobITs' full-context load succeed.
 *
 * <h2>Test profile override semantics</h2>
 *
 * <p>Several JobITs declare a nested {@code @TestConfiguration FixedClockTestConfig}
 * that contributes a {@code @Bean Clock} named {@code "clock"} fixed at
 * {@code 2024-01-15T00:00:00Z} for deterministic timestamp assertions. Spring
 * autowiring resolves a {@code Clock} constructor parameter via:
 *
 * <ol>
 *   <li>type match &mdash; both production and test beans are
 *       {@link Clock}, so the candidate set is ambiguous initially;</li>
 *   <li>bean-name-vs-parameter-name match &mdash; the service constructor
 *       parameter is named {@code clock}; the test bean is named
 *       {@code clock} (matches); the production bean is named
 *       {@code systemClock} (does NOT match); so the test bean wins.</li>
 * </ol>
 *
 * <p>For JobITs that do NOT import the {@code FixedClockTestConfig}, the
 * production {@code systemClock} bean below is the unique {@link Clock} in
 * the context and resolves the constructor parameter unambiguously by type.
 *
 * <p>The {@link ConditionalOnMissingBean} annotation on
 * {@link #systemClock()} provides an additional belt-and-braces safety net:
 * if any other configuration class already contributes a {@link Clock} bean,
 * this one yields, preserving the principle of single source of truth.
 *
 * <h2>Authority</h2>
 *
 * <p>QA Report for Checkpoint 8 (the report that triggered this work)
 * implicitly requires this class because the service constructor
 * dependencies on {@link Clock} (in {@code AuthenticationService},
 * {@code AccountUpdateService}, {@code BillPaymentService},
 * {@code TransactionAddService}, {@code CardDetailService},
 * {@code CardUpdateService}, {@code ReportSubmissionService}) and on
 * {@link ReportJobDispatcher} (in {@code ReportSubmissionService}) must
 * resolve before the {@code @SpringBootTest}-driven JobIT context refresh
 * can succeed. AAP §0.10.9 mandates the {@link Clock} seam for test
 * execution independence.
 */
@Configuration
public class ApplicationConfig {

    /**
     * Returns the default system-UTC {@link Clock} used by production runtime
     * code. The bean name is {@code systemClock} (not {@code clock}) so that
     * a test-profile {@code @TestConfiguration} contributing a bean named
     * {@code clock} (the standard convention for fixed-clock test beans)
     * resolves first via Spring's bean-name-vs-parameter-name matching when
     * a service constructor parameter named {@code clock} is autowired.
     *
     * <p>{@link Clock#systemUTC()} (rather than {@link Clock#systemDefaultZone()})
     * is the deliberate choice because the migrated financial-transaction code
     * paths assume UTC instants (matching the COBOL system clock convention on
     * the originating z/OS LPAR, which is set to UTC for global card-processing
     * compliance). A {@link Clock} backed by the JVM default zone would
     * silently introduce timezone drift on hosts whose default zone differs
     * from UTC &mdash; a class of regression the immutable-boundary contract
     * (AAP §0.10.4) explicitly forbids.
     *
     * @return a {@link Clock} that always reports the current instant in UTC
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Returns a synchronous in-memory placeholder for the
     * {@link ReportJobDispatcher} interface. The real dispatcher (which will
     * delegate to Spring Batch's {@code JobLauncher}, Amazon SQS, or AWS
     * Lambda depending on the deployment topology) lands in a subsequent
     * migration step; this placeholder exists solely so the bean graph that
     * controls the {@code @SpringBootTest} full-context load in the batch
     * Job ITs is complete.
     *
     * <p>The returned implementation:
     *
     * <ul>
     *   <li>does NOT persist anything &mdash; the
     *       {@link com.aws.carddemo.service.ReportJobParameters} parameter is
     *       inspected for null-safety only;</li>
     *   <li>returns a fresh {@link ReportJobHandle} carrying a
     *       {@link UUID#randomUUID()}-derived identifier so callers receive a
     *       valid (if opaque) handle they can stash in their response;</li>
     *   <li>is annotated with {@link ConditionalOnMissingBean} so a
     *       production deployment can register a real
     *       {@link ReportJobDispatcher} bean (e.g., an SQS-backed
     *       implementation) and this placeholder yields without further
     *       configuration changes.</li>
     * </ul>
     *
     * <p>This bean does NOT exercise any business logic that the test suite
     * verifies for parity with the COBOL baseline &mdash; the
     * {@code CORPT00C} migration's behaviour is checked by
     * {@code ReportSubmissionServiceTest} and
     * {@code ReportControllerTest}, both of which mock
     * {@link ReportJobDispatcher} directly rather than depending on this
     * placeholder bean (AAP §0.10.1 Require Test Coverage rule: external
     * boundaries are mocked at the boundary, not at the production-bean
     * level).
     *
     * @return a placeholder {@link ReportJobDispatcher} that returns a fresh
     *         UUID-derived {@link ReportJobHandle} on every dispatch call
     */
    @Bean
    @ConditionalOnMissingBean(ReportJobDispatcher.class)
    public ReportJobDispatcher reportJobDispatcher() {
        return parameters -> {
            // Null-safety guard: throw an explicit NullPointerException with a
            // descriptive message rather than letting the lambda's auto-unboxing
            // produce a generic NPE. The migrated COBOL CORPT00C code path
            // never invokes WIRTE-JOBSUB-TDQ with a null parameter object; any
            // production caller that does is signalling a programming error
            // worth surfacing loudly.
            if (parameters == null) {
                throw new NullPointerException(
                        "ReportJobParameters must not be null when calling ReportJobDispatcher.dispatch");
            }
            // Return a fresh opaque handle. The UUID is unique per call and
            // suffices to let downstream code log / display the submission
            // even though the placeholder dispatcher does not persist anything.
            return ReportJobHandle.of(UUID.randomUUID().toString());
        };
    }
}
