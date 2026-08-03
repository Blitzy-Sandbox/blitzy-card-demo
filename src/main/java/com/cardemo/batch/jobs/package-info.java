/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch.jobs
 * Application : CardDemo
 * Type        : Java package documentation (batch job topology)
 * Function    : Documents the com.cardemo.batch.jobs package, which holds
 *               the Spring Batch Job and Step definitions replacing the
 *               JCL job stream. JCL COND gating becomes a
 *               JobExecutionDecider and the independent statement and
 *               report branches become a FlowBuilder split.
 * Source      : app/jcl/POSTTRAN.jcl + app/cbl/CBTRN02C.cbl
 *               + app/jcl/INTCALC.jcl + app/cbl/CBACT04C.cbl
 *               + app/jcl/TRANREPT.jcl + app/proc/TRANREPT.prc
 *                 + app/cbl/CBTRN03C.cbl
 *               + app/jcl/COMBTRAN.jcl (no COBOL program)
 *               + app/jcl/CREASTMT.JCL + app/cbl/CBSTM03A.CBL
 *               + app/cbl/CBTRN01C.cbl (read-only pre-flight)
 *               @ 7756d89
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

/**
 * Spring Batch {@code Job} and {@code Step} definitions that replace the CardDemo JCL job stream.
 *
 * <h2>What it does</h2>
 *
 * <p>This package owns <em>topology</em>: which steps exist, in what order they run, what gates the
 * transition between them, and which branches may proceed in parallel. Per-record behaviour lives next door
 * in {@code com.cardemo.batch.processors}, and the readers and writers that surround it in
 * {@code com.cardemo.batch.readers} and {@code com.cardemo.batch.writers}.
 *
 * <p>Three JCL mechanisms map onto three framework mechanisms, one-for-one:
 *
 * <ul>
 *   <li>{@code EXEC PGM} plus its DD statements becomes a {@code Step}.</li>
 *   <li>{@code COND=(0,NE)} step gating becomes a {@code JobExecutionDecider}, mapping return codes 0, 4, 8
 *       and 12 onto completed, completed-with-rejects, failed and abend outcomes. Return code 4 has a precise
 *       legacy meaning - it is set when and only when the reject count exceeds zero.</li>
 *   <li>Independent branches of the job stream become {@code FlowBuilder.split()}, used only where the legacy
 *       stream genuinely has no ordering dependency.</li>
 *   </ul>
 *
 * <h2>Current contents versus the target set</h2>
 *
 * <p>The Agent Action Plan specifies a five-stage pipeline plus an orchestrator - <strong>six</strong> types.
 * <strong>One exists today.</strong>
 *
 * <p>Present:
 *
 * <ul>
 *   <li>{@link com.cardemo.batch.jobs.InterestCalculationJob} from {@code app/jcl/INTCALC.jcl} and
 *       {@code app/cbl/CBACT04C.cbl}. The ten-character date parameter becomes a job parameter, and output is
 *       written as a fresh sequential generation to object storage - <strong>not</strong> to the transaction
 *       table. That distinction is easy to get wrong: the source declares its transaction output file as
 *       sequential organisation and allocates a brand-new generation on every run, so interest transactions
 *       only reach the keyed cluster later, through the combine job's sort and bulk load.</li>
 *   </ul>
 *
 * <p><strong>Planned and not yet authored</strong> - named so that their absence is not mistaken for an
 * omission in the plan:
 *
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} - <strong>Not available.</strong> From
 *       {@code app/jcl/POSTTRAN.jcl} and {@code app/cbl/CBTRN02C.cbl}. When authored it must fold
 *       {@code app/cbl/CBTRN01C.cbl} in as an explicitly labelled <strong>read-only pre-flight step</strong>:
 *       that program has no distinct JCL job and its verb inventory contains no write operation, so a
 *       standalone job for it would be an invention. Its exit status is decided solely by whether the reject
 *       count exceeded zero.</li>
 *   <li>{@code CombineTransactionsJob} - <strong>Not available.</strong> From {@code app/jcl/COMBTRAN.jcl}.
 *       <strong>No COBOL program exists for this job</strong>; its logic is entirely DFSORT and IDCAMS
 *       control cards, so the JCL is the source of truth. Concatenated input, sorted by transaction
 *       identifier ascending, then a bulk load. This is where duplicate-key exposure from a repeated interest
 *       date parameter must surface as a failure rather than a silent upsert.</li>
 *   <li>{@code StatementGenerationJob} - <strong>Not available.</strong> From {@code app/jcl/CREASTMT.JCL}
 *       (note the <strong>uppercase</strong> extension - a case-sensitive {@code *.jcl} glob silently drops
 *       this member, and it is the sole source for statement generation) plus {@code app/cbl/CBSTM03A.CBL}
 *       and {@code CBSTM03B.CBL}. Five steps, including the projection sort whose two-byte timestamp
 *       truncation must be reproduced rather than corrected.</li>
 *   <li>{@code TransactionReportJob} - <strong>Not available.</strong> From {@code app/jcl/TRANREPT.jcl},
 *       {@code app/proc/TRANREPT.prc} and {@code app/cbl/CBTRN03C.cbl}. Backup, then a filtered sort by card
 *       number with an inclusive date-range predicate, then report generation at 133 bytes per line.</li>
 *   <li>{@code BatchPipelineOrchestrator} - <strong>Not available.</strong> The end-to-end stream with
 *       decider gating and the parallel split.</li>
 *   </ul>
 *
 * <p>Volatile counts are not restated elsewhere here; the authoritative dated inventory is section 0.4.5.1 of
 * {@code docs/technical-specifications.md}.
 *
 * <h2>Transactional boundaries are parity contracts</h2>
 *
 * <p>The daily posting job commits a transaction-category-balance upsert, an account update and a transaction
 * insert <strong>together</strong>. In the source these are three independent commits; in Java they are one
 * atomic unit. That closes a real hazard - the legacy rewrite-failure path leaves an orphaned category-balance
 * row and an orphaned transaction row - and because it is a behavioural improvement rather than parity, it is
 * labelled explicitly as a deviation in {@code DECISION_LOG.md} rather than passed off as equivalence.
 *
 * <p>This atomicity is also the reason the whole application is a single deployable modular monolith rather
 * than microservices: distributing those three writes across service boundaries would require compensating
 * transactions and would forfeit parity.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B -ntp clean compile}; unit tests with {@code ./mvnw -B -ntp test}; the full
 * gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. That flag skips only the OWASP
 * vulnerability scan, which needs network access to the vulnerability feed; drop it when the scan is wanted.
 * Compilation runs {@code -Xlint:all -Werror} with {@code failOnWarning} at release 25, so a raw type,
 * unchecked cast or dangling documentation comment here fails the build; an unused import does <em>not</em>,
 * because {@code javac} 25 publishes no {@code unused} lint key.
 *
 * <p>Prerequisites are capabilities rather than paths: a JDK 25 toolchain on {@code PATH} with
 * {@code JAVA_HOME} set, and Maven from the pinned repository wrapper. Load the git-ignored {@code .env} with
 * {@code set -a; . ./.env; set +a} first.
 *
 * <p><strong>Jobs never launch at application startup</strong>, because {@code spring.batch.job.enabled} is
 * {@code false}. Launching is explicit - either through the orchestrator, or through the SQS listener that
 * replaces the JES2 internal reader driven by {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. That reproduces the
 * fact that the legacy stream was submitted rather than triggered by the online region coming up.
 *
 * <p>The integration tier for this package needs a container runtime for Testcontainers PostgreSQL and
 * LocalStack. Where a Docker daemon is unavailable those tiers cannot run, and the evidence artefacts must
 * state the prerequisite rather than assert an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by key, never by line number in {@code application.yml}.
 *
 * <ul>
 *   <li>{@code spring.batch.job.enabled} - {@code false}, unconditionally.</li>
 *   <li>{@code spring.batch.jdbc.initialize-schema} - {@code never} in the base profile. In production a DBA
 *       applies the framework's schema script once as a reviewed change; {@code always} would run DDL from
 *       the application on every start.</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} - <strong>no default</strong>, from
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. Supplying a default here would be doubly wrong: unreachable,
 *       because the property is always defined and an unset variable fails placeholder resolution before a
 *       job bean is constructed; and it would convert a fail-fast misconfiguration into a silent write to the
 *       wrong location.</li>
 *   <li>{@code carddemo.aws.s3.gdg-prefixes.*} - the object-key prefixes replacing GDG generations.</li>
 *   <li>Job names, chunk sizes and generation prefixes carry documented defaults; the bucket deliberately
 *       does not.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A job runs on every application boot</dt>
 *   <dd>{@code spring.batch.job.enabled} has been set to {@code true}. Restore {@code false}.</dd>
 *
 *   <dt>Context startup fails naming {@code carddemo.aws.s3.batch-output-bucket}</dt>
 *   <dd>{@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} is unset. This is the fail-fast path working as designed; do
 *       not add a literal default.</dd>
 *
 *   <dt>{@code JobInstanceAlreadyCompleteException} on a re-run</dt>
 *   <dd>Spring Batch identifies an instance by job name plus parameters, and the interest job's date
 *       parameter is part of that identity. Re-running the same date is a distinct concern from the
 *       duplicate-key exposure it would cause downstream in the combine load.</dd>
 *
 *   <dt>A step reports success but the reject file is missing</dt>
 *   <dd>Reject codes are business outcomes, not exceptions: they drive {@code ExitStatus} rather than being
 *       thrown. A run with rejects completes with return code 4, not a failure.</dd>
 *
 *   <dt>An exit status of 4 is treated as a failure, or a failure is treated as 4</dt>
 *   <dd>Return code 4 means, and only means, that the reject count exceeded zero. There is no other
 *       determinant, and no other condition may produce it.</dd>
 *
 *   <dt>A {@code BATCH_*} metadata table is missing</dt>
 *   <dd>{@code initialize-schema} is {@code never} by design. Apply the framework's own PostgreSQL schema
 *       script; do not switch to {@code always} to make a test pass.</dd>
 *
 *   <dt>The statement job cannot find its source</dt>
 *   <dd>{@code app/jcl/CREASTMT.JCL} uses an <strong>uppercase</strong> extension. A case-sensitive
 *       {@code *.jcl} pattern drops it and the whole feature appears to have no source.</dd>
 *
 *   <dt>An integration test errors with a Docker or Testcontainers connection failure</dt>
 *   <dd>No container runtime is reachable. That is a stated validation-time prerequisite, not a code defect;
 *       the unit tier runs without one.</dd>
 *   </dl>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li>Topology only. No per-record body, no reader and no writer implementation belongs in this package.</li>
 *   <li>No job may auto-launch, and no job may spawn an external sort process.</li>
 *   <li>No {@code COND} gate may be reimplemented as an exception, and no reject outcome as a throw.</li>
 *   <li>Transactional boundaries that group multiple writes are contracts; narrowing one to a single write
 *       reintroduces the orphaned-row hazard.</li>
 *   <li>Planned types named above must not be referenced from code until they exist - naming a bean that does
 *       not exist makes the context unbootable.</li>
 *   </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.batch.jobs;
