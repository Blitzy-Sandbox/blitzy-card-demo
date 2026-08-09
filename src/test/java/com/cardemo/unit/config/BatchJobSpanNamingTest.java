/*
 * ******************************************************************
 * Program     : BatchJobSpanNamingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (batch observability)
 * Function    : Proves that every batch job bean receives a contextual
 *               name a human can read, that the value survives the
 *               tracing bridge's lower-hyphen transformation unchanged,
 *               and that nothing else about the job observation moves -
 *               in particular that the JCL member name, which is the
 *               parity anchor and the submission key, is untouched.
 * Source      : app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl,
 *               app/jcl/COMBTRAN.jcl, app/jcl/CREASTMT.JCL and
 *               app/proc/TRANREPT.prc - the five member names the jobs
 *               carry, all upper case because the members are @ 7756d89
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

import com.cardemo.config.BatchConfig;
import io.micrometer.tracing.internal.SpanNameUtil;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.observability.BatchJobContext;
import org.springframework.batch.core.observability.BatchJobObservationConvention;
import org.springframework.batch.core.observability.DefaultBatchJobObservationConvention;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Unit tests for the batch job span-naming seam declared in {@link BatchConfig}.
 *
 * <p><strong>The defect these tests pin.</strong> A job span reached the trace store as
 * {@code t-r-a-n-r-e-p-t}, one hyphen per letter, beside {@code p-o-s-t-t-r-a-n}. The chain is exact:
 * {@code AbstractJob.execute} sets the observation's contextual name to the job name verbatim, and
 * {@code io.micrometer.tracing.handler.TracingObservationHandler#getSpanName} then applies
 * {@link SpanNameUtil#toLowerHyphen(String)}, which inserts a hyphen before every upper-case character. That
 * is the right rule for a {@code CamelCase} name and a destructive one for an all-capitals name — and these
 * job names are all capitals because they are the JCL member names.
 *
 * <p><strong>What is asserted, and why each part matters.</strong> The produced value must be readable, and it
 * must be a <em>fixed point</em> of {@code toLowerHyphen}: a name that merely looks better but is still
 * transformed on the way out would fix nothing. The post-processor must reach every {@code AbstractJob} bean
 * and no other bean. The job NAME must be untouched, because it is what an operator passes to
 * {@code --spring.batch.job.name} and what {@code TRACEABILITY_MATRIX.md} cites. And the key values must
 * still come from {@link DefaultBatchJobObservationConvention}, so no tag on the framework's own batch meters
 * moves.
 *
 * <p>{@link CapturingJob} exists so the convention can be observed without reflection: it overrides the one
 * public setter the post-processor calls and records the argument. That is a stronger test than reading a
 * private field, because it exercises the same method the framework would.
 */
@DisplayName("Batch job span naming - the JCL member name stays, the span name becomes readable")
class BatchJobSpanNamingTest {

    /** The prefix the convention prepends, asserted here so a silent change to it fails this suite. */
    private static final String EXPECTED_PREFIX = "batch-job-";

    /** The bean name the post-processor is handed; it is deliberately irrelevant to the outcome. */
    private static final String ANY_BEAN_NAME = "someJobBean";

    /**
     * Runs a job bean through the post-processor and returns the convention it was given.
     *
     * @param jobName the job name, spelled as the JCL member is
     * @return the convention installed on the bean, never {@code null}
     */
    private static BatchJobObservationConvention installedConventionFor(final String jobName) {
        final BeanPostProcessor processor = BatchConfig.batchJobSpanNamePostProcessor();
        final CapturingJob job = new CapturingJob(jobName);

        final Object returned = processor.postProcessAfterInitialization(job, ANY_BEAN_NAME);

        assertThat(returned)
                .as("a bean post-processor must return the bean it was given; returning anything else would "
                        + "silently replace every job in the context")
                .isSameAs(job);
        assertThat(job.captured())
                .as("the post-processor exists to install the convention, so an AbstractJob that passes "
                        + "through it without receiving one is the defect this suite guards")
                .isNotNull();
        return job.captured();
    }

    /**
     * Builds the observation context the convention reads, for a job of the given name.
     *
     * @param jobName the job name
     * @return a context carrying a job execution with that job instance name
     */
    private static BatchJobContext contextFor(final String jobName) {
        final JobInstance instance = new JobInstance(1L, jobName);
        return new BatchJobContext(new JobExecution(instance, new JobParameters()));
    }

    @Nested
    @DisplayName("the contextual name is readable and survives the tracing bridge unchanged")
    class ContextualName {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource({
            "POSTTRAN,batch-job-posttran",
            "INTCALC,batch-job-intcalc",
            "COMBTRAN,batch-job-combtran",
            "CREASTMT,batch-job-creastmt",
            "TRANREPT,batch-job-tranrept",
        })
        @DisplayName("each JCL member name becomes one readable operation name")
        void eachMemberNameBecomesOneReadableOperationName(final String jobName, final String expected) {
            final String contextualName =
                    installedConventionFor(jobName).getContextualName(contextFor(jobName));

            assertThat(contextualName)
                    .as("the operation list in the trace store is read by a human, and %s is not readable",
                            SpanNameUtil.toLowerHyphen(jobName))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "toLowerHyphen leaves {0}''s span name alone")
        @CsvSource({"POSTTRAN", "INTCALC", "COMBTRAN", "CREASTMT", "TRANREPT"})
        @DisplayName("the produced name is a fixed point of the transformation that caused the defect")
        void theProducedNameIsAFixedPointOfTheTransformation(final String jobName) {
            final String contextualName =
                    installedConventionFor(jobName).getContextualName(contextFor(jobName));

            assertThat(SpanNameUtil.toLowerHyphen(contextualName))
                    .as("this is the whole mechanism of the fix: the tracing bridge applies toLowerHyphen to "
                            + "whatever it is given, so a value that is not already lower-hyphen would be "
                            + "mangled in exactly the way the original job name was")
                    .isEqualTo(contextualName);
        }

        @Test
        @DisplayName("the untreated job name really is mangled, so the fix is answering a real defect")
        void theUntreatedJobNameIsMangled() {
            assertThat(SpanNameUtil.toLowerHyphen("TRANREPT"))
                    .as("recorded as the measured baseline: this is what the trace store displayed before "
                            + "the convention was installed, and it is why a fix was needed at all")
                    .isEqualTo("t-r-a-n-r-e-p-t");
            assertThat(SpanNameUtil.toLowerHyphen("POSTTRAN")).isEqualTo("p-o-s-t-t-r-a-n");
        }

        @Test
        @DisplayName("a context with no job instance yields no opinion rather than a prefix-only name")
        void aContextWithNoJobInstanceYieldsNoOpinion() {
            final BatchJobObservationConvention convention = installedConventionFor("POSTTRAN");
            final BatchJobContext contextWithoutInstance =
                    new BatchJobContext(new JobExecution(1L, new JobParameters()));

            assertThat(convention.getContextualName(contextWithoutInstance))
                    .as("Micrometer applies a convention's contextual name only when it is non-blank, so "
                            + "returning null lets the framework's own value stand rather than producing a "
                            + "span named after the prefix alone")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("nothing else about the job or its observation moves")
    class NothingElseMoves {

        @Test
        @DisplayName("the job NAME is untouched, because it is the submission key and the parity anchor")
        void theJobNameIsUntouched() {
            final BeanPostProcessor processor = BatchConfig.batchJobSpanNamePostProcessor();
            final CapturingJob job = new CapturingJob("POSTTRAN");

            processor.postProcessAfterInitialization(job, ANY_BEAN_NAME);

            assertThat(job.getName())
                    .as("app/jcl/POSTTRAN.jcl is the member, --spring.batch.job.name=POSTTRAN is how an "
                            + "operator submits it, and TRACEABILITY_MATRIX.md cites it. Renaming it to suit "
                            + "a span-naming rule would break the submission to make a trace prettier")
                    .isEqualTo("POSTTRAN");
        }

        @Test
        @DisplayName("every key value still comes from the framework's own convention")
        void everyKeyValueStillComesFromTheFrameworkConvention() {
            final BatchJobObservationConvention convention = installedConventionFor("POSTTRAN");
            final BatchJobContext context = contextFor("POSTTRAN");
            final DefaultBatchJobObservationConvention framework =
                    new DefaultBatchJobObservationConvention();

            assertThat(convention.getLowCardinalityKeyValues(context))
                    .as("the tags on spring_batch_job_seconds - spring.batch.job.name among them - are the "
                            + "framework's to define, and this fix must not touch a single one")
                    .isEqualTo(framework.getLowCardinalityKeyValues(context));
            assertThat(convention.getHighCardinalityKeyValues(context))
                    .isEqualTo(framework.getHighCardinalityKeyValues(context));
            assertThat(convention.getName())
                    .as("the observation NAME is the framework's too; only the contextual name is supplied")
                    .isEqualTo(framework.getName());
        }

        @Test
        @DisplayName("the convention extends the framework's, so a future key value is inherited not missed")
        void theConventionExtendsTheFrameworkConvention() {
            assertThat(installedConventionFor("POSTTRAN"))
                    .as("reimplementing the interface instead of extending it would freeze today's key "
                            + "values, so a key the framework adds in a later release would silently vanish")
                    .isInstanceOf(DefaultBatchJobObservationConvention.class);
        }
    }

    @Nested
    @DisplayName("the post-processor reaches every job bean and nothing else")
    class Reach {

        @Test
        @DisplayName("a bean that is not a job passes through untouched")
        void aBeanThatIsNotAJobPassesThroughUntouched() {
            final BeanPostProcessor processor = BatchConfig.batchJobSpanNamePostProcessor();
            final String unrelated = "not a job";

            assertThat(processor.postProcessAfterInitialization(unrelated, "someString"))
                    .as("a post-processor runs over every singleton in the context, so it must decide by "
                            + "type and leave everything else exactly as it found it")
                    .isSameAs(unrelated);
        }

        @Test
        @DisplayName("one post-processor instance serves every job, with one shared convention")
        void onePostProcessorServesEveryJobWithOneSharedConvention() {
            final BeanPostProcessor processor = BatchConfig.batchJobSpanNamePostProcessor();
            final CapturingJob posttran = new CapturingJob("POSTTRAN");
            final CapturingJob tranrept = new CapturingJob("TRANREPT");

            processor.postProcessAfterInitialization(posttran, "dailyTransactionPostingJob");
            processor.postProcessAfterInitialization(tranrept, "transactionReportJob");

            assertThat(posttran.captured())
                    .as("the convention is stateless and derives its answer from the context it is handed, "
                            + "so one instance is correct for every job and a per-job instance would be "
                            + "allocation for nothing")
                    .isSameAs(tranrept.captured());
            assertThat(posttran.captured().getContextualName(contextFor("POSTTRAN")))
                    .isEqualTo(EXPECTED_PREFIX + "posttran");
            assertThat(tranrept.captured().getContextualName(contextFor("TRANREPT")))
                    .as("and the same instance answers differently per job, which is what proves the answer "
                            + "comes from the context rather than from construction")
                    .isEqualTo(EXPECTED_PREFIX + "tranrept");
        }

        @Test
        @DisplayName("the bean method is static, so it cannot force the configuration class to load early")
        void theBeanMethodIsStatic() throws NoSuchMethodException {
            assertThat(BatchConfig.class.getMethod("batchJobSpanNamePostProcessor").getModifiers())
                    .as("a BeanPostProcessor is instantiated before ordinary singletons; a non-static "
                            + "declaration would drag BatchConfig and its collaborators into existence with "
                            + "it, which Spring reports as a warning and which changes initialisation order")
                    .matches(modifiers -> java.lang.reflect.Modifier.isStatic(modifiers),
                            "static");
        }
    }

    /**
     * A minimal {@link AbstractJob} that records the convention the post-processor installs.
     *
     * <p>It overrides the public setter rather than exposing a private field, so the assertion exercises the
     * same call the framework makes. The three abstract members are satisfied with the narrowest possible
     * implementations: this job is never executed, and no test here calls any of them.
     */
    private static final class CapturingJob extends AbstractJob {

        /** The convention the post-processor handed this job, or {@code null} if it handed none. */
        private BatchJobObservationConvention captured;

        /**
         * @param name the job name, spelled exactly as the JCL member is
         */
        CapturingJob(final String name) {
            super(name);
        }

        @Override
        public void setObservationConvention(final BatchJobObservationConvention convention) {
            this.captured = convention;
            super.setObservationConvention(convention);
        }

        /**
         * @return the convention installed on this job, or {@code null} if none was
         */
        BatchJobObservationConvention captured() {
            return this.captured;
        }

        @Override
        public Step getStep(final String stepName) {
            return null;
        }

        @Override
        public Collection<String> getStepNames() {
            return List.of();
        }

        @Override
        protected void doExecute(final JobExecution execution) {
            throw new UnsupportedOperationException(
                    "this job exists to be post-processed, never executed; a caller reaching here has "
                            + "mistaken the fixture for a runnable job");
        }
    }
}
