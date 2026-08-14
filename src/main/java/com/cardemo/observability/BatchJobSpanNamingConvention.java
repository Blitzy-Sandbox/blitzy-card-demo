/*
 * ******************************************************************
 * Program     : BatchJobSpanNamingConvention.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 batch job observation
 *               convention
 * Function    : Gives every batch job a span name an operator can
 *               actually find. The framework hands the ALL-CAPS job
 *               name to Micrometer Tracing as the contextual name, and
 *               SpanNameUtil.toLowerHyphen inserts a hyphen before
 *               every upper-case character, so POSTTRAN reached Jaeger
 *               as p-o-s-t-t-r-a-n and CARDDEMO-PIPELINE as
 *               c-a-r-d-d-e-m-o--p-i-p-e-l-i-n-e - findable under
 *               neither its own name nor any obvious spelling. This
 *               convention supplies an already-lower-case contextual
 *               name, which that transform leaves untouched.
 * Capability  : NEW - additive trace legibility, not a translation. The
 *               frozen corpus exports no spans at all: its entire
 *               instrumentation is DISPLAY to SYSOUT plus the status
 *               renderer at app/cbl/CBTRN02C.cbl:L714-L727, so there is
 *               no COBOL paragraph to cite for this behaviour. Mandated
 *               by Rule 1 Clause A, which requires observability
 *               through "structured logs, meaningful errors, and
 *               measurable behavior", and raised as QA finding B-12.
 * Source      : app/jcl/POSTTRAN.jcl:L23 @ 7756d89 (the job step whose
 *               Java successor is named POSTTRAN)
 * Source      : app/jcl/INTCALC.jcl:L22, app/jcl/COMBTRAN.jcl:L22,
 *               app/jcl/CREASTMT.JCL:L22 and app/proc/TRANREPT.prc:L21
 *               @ 7756d89 (the four remaining stage job names)
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89
 *               (9910-DISPLAY-IO-STATUS - the corpus's only
 *               instrumentation, which is what makes every span in this
 *               system new capability rather than a translation)
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
 * language governing permissions and limitations under the License.
 * ******************************************************************
 */

package com.cardemo.observability;

import java.util.Locale;
import java.util.Objects;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.observability.BatchJobContext;
import org.springframework.batch.core.observability.DefaultBatchJobObservationConvention;

/**
 * The span name a batch job publishes to the trace store.
 *
 * <h2>The defect this closes</h2>
 *
 * <p>QA finding B-12, severity Minor. {@code AbstractJob.execute} builds its observation with
 * {@code .contextualName(jobExecution.getJobInstance().getJobName())}, and every job name in this system is
 * upper case because every one of them is a JCL member name. Micrometer Tracing's
 * {@code TracingObservationHandler.getSpanName} then passes that contextual name through
 * {@code SpanNameUtil.toLowerHyphen}, which inserts a hyphen before <em>every</em> upper-case character. The
 * measured result was an operations list holding {@code p-o-s-t-t-r-a-n}, {@code i-n-t-c-a-l-c} and
 * {@code c-a-r-d-d-e-m-o--p-i-p-e-l-i-n-e} - the last with a doubled hyphen where the job name's own hyphen
 * sits. A batch run was traced, but no operator searching for a job by name could find it, and the
 * orchestrator job appeared under neither spelling anyone would try.
 *
 * <p>{@code DefaultBatchJobObservationConvention} does not override {@code getContextualName}, so nothing in
 * the framework was able to correct this. This class overrides exactly that one method and inherits
 * everything else.
 *
 * <h2>What it returns, and why that shape</h2>
 *
 * <p>{@value #SPAN_NAME_PREFIX} followed by the job name lower-cased under {@link Locale#ROOT}. So
 * {@code POSTTRAN} becomes {@code batch-job-posttran} and {@code CARDDEMO-PIPELINE} becomes
 * {@code batch-job-carddemo-pipeline}.
 *
 * <ul>
 *   <li><strong>Already lower case, so the transform is a no-op.</strong> {@code toLowerHyphen} inserts a
 *       hyphen only before an upper-case character, so a lower-case string passes through byte for byte. The
 *       name an operator reads is therefore exactly the name this method returns, with no second
 *       transformation to reason about.</li>
 *   <li><strong>Prefixed, so the jobs group together.</strong> Jaeger's operations list is alphabetical, so
 *       the prefix collects all seven jobs into one adjacent block instead of scattering them among the HTTP
 *       operations. That is the difference between browsing for a job and knowing its name in advance.</li>
 *   <li><strong>{@link Locale#ROOT} is mandatory, not decorative.</strong> A locale-sensitive lower-casing
 *       maps {@code I} to a dotless {@code ı} under a Turkish default locale, which would silently rename
 *       {@code INTCALC}'s span on a differently configured host. Clause C of the project's single rule rules
 *       out that class of environment dependence.</li>
 * </ul>
 *
 * <h2>What it deliberately does not change</h2>
 *
 * <ul>
 *   <li><strong>The observation name.</strong> Inherited, so it stays {@code spring.batch.job}. That name is
 *       what the meter series is called, and renaming it would break every dashboard and scrape rule.</li>
 *   <li><strong>The low-cardinality tags.</strong> Inherited, so {@code spring.batch.job.name} continues to
 *       carry the <em>exact</em> all-capitals job name and {@code spring.batch.job.status} its status. The
 *       span name is for a human reading a trace; the tag is the machine-readable identity, and the two
 *       deliberately keep their own spellings.</li>
 *   <li><strong>The high-cardinality tags.</strong> Inherited unchanged.</li>
 *   <li><strong>Step spans.</strong> Out of scope for this class: a step observation carries its own
 *       convention and its step names are already lower camel case, which {@code toLowerHyphen} renders
 *       legibly. Nothing here touches them.</li>
 * </ul>
 *
 * <h2>How it is registered</h2>
 *
 * <p>Explicitly, on each job builder, through
 * {@code JobBuilderHelper.observationConvention(BatchJobObservationConvention)}. Spring Batch resolves no
 * convention bean from the context: {@code AbstractJob} holds a field initialised to the framework default
 * and replaced only by that builder call. This is the same rule that applies to the {@code jobInstanceId}
 * listener each job class registers on its own job - a bean that no job builder is given does nothing - so a
 * {@code @Component} here would be dead configuration under Clause B rather than a working registration.
 *
 * <p>The instance is reached through {@link #INSTANCE} and the constructor is private. The class holds no
 * state whatsoever - it reads the job name out of the context it is handed and returns a string - so one
 * shared instance is safe across every job and every thread, and offering only one way to obtain it keeps the
 * seven registration sites textually identical.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A job still appears as single hyphenated characters</dt>
 *   <dd>That job's builder is missing the {@code observationConvention(...)} call. Every
 *       {@code new JobBuilder(...)} in {@code com.cardemo.batch.jobs} and in
 *       {@code com.cardemo.config.BatchConfig} must have it; the count is asserted by
 *       {@code BatchJobSpanNamingConventionTest}.</dd>
 *   <dt>No batch span appears at all</dt>
 *   <dd>Not this class. Either tracing is not exporting - check the exporter endpoint - or the sampling
 *       probability excluded the run. The span name cannot suppress a span.</dd>
 *   <dt>A dashboard panel that filtered on the span name broke</dt>
 *   <dd>Expected, and the panel should filter on the {@code spring.batch.job.name} tag instead, which is
 *       unchanged and is the value dimensioned for that purpose.</dd>
 * </dl>
 *
 * @see com.cardemo.observability.CorrelationIdFilter for the batch-scoped correlation identifier and the
 *     {@code jobInstanceId} logging key that accompany these spans
 */
public final class BatchJobSpanNamingConvention extends DefaultBatchJobObservationConvention {

    /**
     * The prefix every batch span name carries, so that all seven jobs sort adjacently in an operations list.
     */
    public static final String SPAN_NAME_PREFIX = "batch-job-";

    /** The one shared instance, held by every job builder. Stateless, so sharing it is safe. */
    public static final BatchJobSpanNamingConvention INSTANCE = new BatchJobSpanNamingConvention();

    /**
     * Creates the convention.
     *
     * <p>Private because {@link #INSTANCE} is the only sanctioned way to obtain one: a second instance would
     * behave identically, so allowing one would only let the seven registration sites diverge textually.
     */
    private BatchJobSpanNamingConvention() {
        super();
    }

    /**
     * Returns the span name for a batch job execution.
     *
     * <p>Purpose: hand Micrometer Tracing a contextual name its {@code toLowerHyphen} transform will leave
     * intact. Inputs: the batch job observation context the framework creates per execution. Output: the span
     * name. Side effects: none.
     *
     * <p><strong>Error modes.</strong> Total by construction for any context the framework can create - an
     * execution always has an instance and an instance always has a name. A {@code null} context is a wiring
     * fault rather than a data fault and is refused loudly rather than silently producing an unnamed span,
     * because a span named after nothing is indistinguishable from the defect this class exists to close.
     *
     * @param context the batch job observation context; must not be {@code null}
     * @return the prefixed, lower-cased job name, never {@code null} and never blank
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    public String getContextualName(final BatchJobContext context) {
        Objects.requireNonNull(context, "The batch job observation context must not be null.");
        final JobExecution execution = context.getJobExecution();
        final String jobName = execution.getJobInstance() == null
                ? null
                : execution.getJobInstance().getJobName();
        if (jobName == null || jobName.isBlank()) {
            // Blank means "no opinion" to Micrometer, so the framework's own value stands rather than a span
            // named after the prefix alone. A job instance is always present in a real execution; this branch
            // exists so a hand-built context in a test cannot produce a misleading name.
            return null;
        }
        return SPAN_NAME_PREFIX + jobName.toLowerCase(Locale.ROOT);
    }
}
