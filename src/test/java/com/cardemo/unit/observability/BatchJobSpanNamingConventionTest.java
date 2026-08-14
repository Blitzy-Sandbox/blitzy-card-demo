/*
 ******************************************************************
 * Program     : BatchJobSpanNamingConventionTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test (Surefire tier)
 * Function    : Holds the batch span name to a form Micrometer
 *               Tracing's own toLowerHyphen transform leaves intact,
 *               holds the metric tags to the exact all-capitals job
 *               name, and holds every job builder in the tree to
 *               registering the convention. QA finding B-12.
 * Source      : app/jcl/POSTTRAN.jcl:L23, app/jcl/INTCALC.jcl:L22,
 *               app/jcl/COMBTRAN.jcl:L22, app/jcl/CREASTMT.JCL:L22 and
 *               app/proc/TRANREPT.prc:L21 @ 7756d89 (the five stage job
 *               names, all upper case because all are JCL member names)
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89
 *               (9910-DISPLAY-IO-STATUS - the corpus's only
 *               instrumentation, so every span here is new capability)
 ******************************************************************
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
 ******************************************************************
 */

package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.observability.BatchJobSpanNamingConvention;
import io.micrometer.tracing.internal.SpanNameUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.observability.BatchJobContext;
import org.springframework.batch.core.observability.DefaultBatchJobObservationConvention;

/**
 * The span name every batch job publishes, and the registration that makes it take effect.
 *
 * <p>QA finding B-12. The framework hands the ALL-CAPS job name to Micrometer Tracing as the observation's
 * contextual name, and {@code TracingObservationHandler.getSpanName} passes that through
 * {@link SpanNameUtil#toLowerHyphen(String)}, which inserts a hyphen before every upper-case character. The
 * measured consequence was an operations list holding {@code p-o-s-t-t-r-a-n} and
 * {@code c-a-r-d-d-e-m-o--p-i-p-e-l-i-n-e}: the runs were traced, and no operator searching by job name could
 * find them.
 *
 * <p>The decisive assertion here is not that the name looks nicer. It is that the name is a
 * <strong>fixed point</strong> of the very transform that mangled it, asserted by calling that transform
 * rather than by restating what it does. A future Micrometer that hyphenates something else would fail this
 * test rather than quietly reintroduce the defect.
 */
@DisplayName("Batch span naming - a name the tracing transform leaves intact, on every job (B-12)")
class BatchJobSpanNamingConventionTest {

    /** The repository root, which the build pins as the working directory for both test plugins. */
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    /** Every job name the tree registers, all upper case because all are JCL member names. */
    private static final List<String> JOB_NAMES = List.of(
            "POSTTRAN", "INTCALC", "COMBTRAN", "CREASTMT", "TRANREPT", "CARDDEMO-PIPELINE",
            "datasetVerificationJob");

    /**
     * The span name, and its invariance under the transform that produced the defect.
     */
    @Nested
    @DisplayName("1. The contextual name survives toLowerHyphen unchanged")
    class ContextualName {

        @Test
        @DisplayName("every job name becomes a prefixed lower-case name that the transform leaves alone")
        void everyJobNameIsAFixedPointOfTheTracingTransform() {
            for (final String jobName : JOB_NAMES) {
                final String contextualName =
                        BatchJobSpanNamingConvention.INSTANCE.getContextualName(contextFor(jobName));

                assertThat(contextualName)
                        .as("the name is prefixed so all seven jobs sort adjacently in an operations list")
                        .startsWith(BatchJobSpanNamingConvention.SPAN_NAME_PREFIX)
                        .endsWith(jobName.toLowerCase(java.util.Locale.ROOT));
                assertThat(SpanNameUtil.toLowerHyphen(contextualName))
                        .as("and it is a fixed point of Micrometer's own transform, asserted by calling that "
                                + "transform rather than by restating it. %s alone became %s, which is "
                                + "finding B-12", jobName, SpanNameUtil.toLowerHyphen(jobName))
                        .isEqualTo(contextualName);
            }
        }

        @Test
        @DisplayName("the premise: the bare job name really is mangled, so this test is not vacuous")
        void theBareJobNameIsMangledByTheTransform() {
            assertThat(SpanNameUtil.toLowerHyphen("POSTTRAN"))
                    .as("if this ever stops hyphenating, the convention is no longer needed and the "
                            + "assertions above would pass for a reason that no longer holds")
                    .isNotEqualTo("posttran")
                    .contains("-");
            assertThat(SpanNameUtil.toLowerHyphen("CARDDEMO-PIPELINE"))
                    .as("the orchestrator's own hyphen makes its mangled form doubly unsearchable, which is "
                            + "why the QA found it under neither spelling it tried")
                    .isNotEqualTo("carddemo-pipeline");
        }

        @Test
        @DisplayName("a null context is refused rather than yielding an unnamed span")
        void aNullContextIsRefused() {
            assertThatNullPointerException()
                    .as("a span named after nothing is indistinguishable from the defect this class closes")
                    .isThrownBy(() -> BatchJobSpanNamingConvention.INSTANCE.getContextualName(null));
        }
    }

    /**
     * The parts of the observation this convention must leave exactly as they were.
     */
    @Nested
    @DisplayName("2. The metric contract is untouched: same observation name, same tags, same values")
    class InheritedContract {

        @Test
        @DisplayName("the observation name is still spring.batch.job, so no meter series is renamed")
        void theObservationNameIsUnchanged() {
            assertThat(BatchJobSpanNamingConvention.INSTANCE.getName())
                    .as("the observation name is what the meter series is called; renaming it would empty "
                            + "every dashboard panel and scrape rule that queries it")
                    .isEqualTo(new DefaultBatchJobObservationConvention().getName());
        }

        @Test
        @DisplayName("the low-cardinality tags still carry the exact all-capitals job name")
        void theTagsStillCarryTheExactJobName() {
            for (final String jobName : JOB_NAMES) {
                final BatchJobContext context = contextFor(jobName);

                assertThat(BatchJobSpanNamingConvention.INSTANCE.getLowCardinalityKeyValues(context))
                        .as("the span name is for a human reading a trace; the tag is the machine-readable "
                                + "identity and must keep the job's own spelling, %s", jobName)
                        .isEqualTo(new DefaultBatchJobObservationConvention()
                                .getLowCardinalityKeyValues(context))
                        .anySatisfy(keyValue -> assertThat(keyValue.getValue()).isEqualTo(jobName));
            }
        }
    }

    /**
     * The registration, which is what makes any of the above reach a span.
     */
    @Nested
    @DisplayName("3. Every job builder in the tree registers the convention")
    class Registration {

        @Test
        @DisplayName("every new JobBuilder(...) is accompanied by an observationConvention(...) call")
        void noJobBuilderOmitsTheConvention() {
            final List<String> builders = new ArrayList<>();
            final List<String> withoutConvention = new ArrayList<>();
            for (final Path source : mainSources()) {
                final String text = read(source);
                if (!text.contains("return new JobBuilder(")) {
                    continue;
                }
                builders.add(source.getFileName().toString());
                if (!text.contains(".observationConvention(BatchJobSpanNamingConvention.INSTANCE)")) {
                    withoutConvention.add(source.getFileName().toString());
                }
            }

            assertThat(builders)
                    .as("the tree must hold the seven job builders this assertion is about, or it passes "
                            + "vacuously. Resolved: %s", builders)
                    .hasSize(7);
            assertThat(withoutConvention)
                    .as("a job builder without the convention publishes a hyphenated span name again, and "
                            + "nothing else in the build would notice. Resolved: %s", withoutConvention)
                    .isEmpty();
        }
    }

    /**
     * Builds a batch job observation context for a job name, exactly as the framework would.
     *
     * @param jobName the job name
     * @return the context, never {@code null}
     */
    private static BatchJobContext contextFor(final String jobName) {
        final JobExecution execution =
                new JobExecution(new JobInstance(1L, jobName), 1L, new JobParameters());
        return new BatchJobContext(execution);
    }

    /**
     * Every Java source in the production tree.
     *
     * @return the source paths, never {@code null}
     */
    private static List<Path> mainSources() {
        final Path base = ROOT.resolve("src/main/java");
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Cannot walk " + base, unreadable);
        }
    }

    /**
     * Reads one source file as text.
     *
     * @param source the file to read
     * @return its content, never {@code null}
     */
    private static String read(final Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + source, unreadable);
        }
    }
}
