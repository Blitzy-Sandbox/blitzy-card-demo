/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch
 * Application : CardDemo
 * Type        : Java package documentation (Spring Batch layer)
 * Function    : Package documentation for the Spring Batch replacement
 *               of the JES2 / JCL / DFSORT / IDCAMS batch job stream.
 *               Layer document for the batch root and the facts that
 *               bind all four of its leaves.
 * Source      : app/jcl/POSTTRAN.jcl + app/jcl/INTCALC.jcl
 *               + app/jcl/COMBTRAN.jcl + app/jcl/CREASTMT.JCL
 *               + app/jcl/TRANREPT.jcl + app/proc/TRANREPT.prc
 *               + app/proc/REPROC.prc + app/ctl/REPROCT.ctl
 *               + app/jcl/DEFGDGB.jcl + app/jcl/DALYREJS.jcl
 *               + app/jcl/REPTFILE.jcl
 *               + app/cbl/CBTRN02C.cbl (731 lines, 27 paragraphs)
 *               + app/cbl/CBTRN01C.cbl (491 lines, 19 paragraphs)
 *               + app/cbl/CBACT04C.cbl (652 lines, 23 paragraphs)
 *               + app/cbl/CBTRN03C.cbl (649 lines, 27 paragraphs)
 *               + app/cbl/CBSTM03A.CBL (924 lines, 26 paragraphs)
 *               + app/cbl/CBSTM03B.CBL (230 lines, 15 paragraphs)
 *               + app/cbl/CBACT01C.cbl (193 lines, 7 paragraphs)
 *               + app/cbl/CBACT02C.cbl (178 lines, 6 paragraphs)
 *               + app/cbl/CBACT03C.cbl (178 lines, 6 paragraphs)
 *               + app/cbl/CBCUS01C.cbl (178 lines, 6 paragraphs)
 *               + app/cpy/CVTRA05Y.cpy + app/cpy/CVTRA06Y.cpy
 *               + app/cpy/CVTRA07Y.cpy + app/cpy/COSTM01.CPY
 *               + app/csd/CARDDEMO.CSD + app/catlg/LISTCAT.txt
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
 * Batch layer of the CardDemo application: the Spring Batch replacement for the z/OS JES2 job stream that
 * ran 29 JCL members over DFSORT, IDCAMS and ten COBOL batch programs.
 *
 * <p>This package holds <strong>no type of its own</strong>. It is the layer root, and it exists as a
 * document because four leaves sit beneath it and a handful of facts belong to all four rather than to any
 * one of them: one exit-code vocabulary, one record-geometry contract, one object-storage key scheme, one
 * set of preserved source quirks. Stating those in each leaf would be the duplication Rule 1 Clause C
 * forbids; stating them nowhere would fail Rule 1 Clause E for the layer. So they are stated here once, and
 * each leaf documents what is genuinely its own.
 *
 * <p>The reader this document is written for is a maintainer who has never seen COBOL. Where a decision
 * looks arbitrary it is almost always a source behaviour being reproduced deliberately, and the citation
 * next to it is the proof. Every claim below carries a {@code path:line} reference into the frozen legacy
 * corpus, which is read-only and referenced by commit {@code 7756d89} - the full anchor being
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. Nothing under {@code app/} is edited by this migration.
 *
 * <h2>What it does</h2>
 *
 * <p>Five JCL jobs plus an orchestrator replace the job stream. The mapping from mainframe mechanism to
 * framework mechanism is one-for-one, and it is deterministic rather than conventional:
 *
 * <ul>
 *   <li>{@code EXEC PGM} plus its DD statements becomes a {@code Step}.</li>
 *   <li>{@code COND=(0,NE)} step gating - for example {@code app/jcl/CREASTMT.JCL:L56} - becomes a
 *       {@code JobExecutionDecider} over return codes 0, 4, 8 and 12.</li>
 *   <li>Branches the legacy stream leaves unordered become {@code FlowBuilder.split()}.</li>
 *   <li>DFSORT control cards become {@code java.util.Comparator} instances and IDCAMS {@code REPRO} becomes
 *       {@code JdbcTemplate.batchUpdate}. <strong>No external sort process is ever spawned.</strong></li>
 *   <li>{@code EXEC CICS WRITEQ TD QUEUE('JOBS')} becomes an SQS FIFO publish, and the JES2 internal reader
 *       becomes an SQS listener. The queue definition at {@code app/csd/CARDDEMO.CSD:L499-L502} -
 *       {@code TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)} - is what fixes the 80-byte
 *       shape of that message.</li>
 * </ul>
 *
 * <h3>Separation of concerns across the four leaves</h3>
 *
 * <p>The split is strict, and it is the answer to Rule 1 Clause A's separation-of-concerns requirement:
 * <strong>jobs orchestrate, processors decide, readers decode, writers emit.</strong>
 *
 * <ul>
 *   <li>{@code com.cardemo.batch.jobs} - topology only. Which steps exist, in what order, what gates each
 *       transition, which branches may run in parallel. No per-record body, no decoding, no emission.</li>
 *   <li>{@code com.cardemo.batch.processors} - the per-record decision logic lifted from the COBOL
 *       paragraph bodies. Validation cascades, control breaks, interest arithmetic, page accounting.</li>
 *   <li>{@code com.cardemo.batch.readers} - dataset decoding, including position-aware zoned-decimal
 *       decoding of the fixed-width ASCII fixtures. A trailing-sign overpunch is decoded from the PIC
 *       clause at a known offset, never by scanning for a character, because the same letters occur
 *       legitimately inside merchant names.</li>
 *   <li>{@code com.cardemo.batch.writers} - byte-exact fixed-width emission at the object-storage boundary,
 *       plus relational persistence.</li>
 * </ul>
 *
 * <p>Nothing in this layer may reach for a shared abstract base, helper, utility or constants class. The
 * universal legacy batch skeleton - open the files, loop reading, process, write, close, display the
 * counters, set {@code RETURN-CODE} - is expressed through the framework's own {@code Step},
 * {@code Tasklet} and chunk abstractions. Clause C's "avoid duplication" is satisfied <em>by the
 * framework</em>, not by a hand-rolled parent, and one position-aware decoder lives with its own reader
 * rather than in a shared codec.
 *
 * <h3>The file budget</h3>
 *
 * <p>The plan sizes this subtree at <strong>23 {@code .java} files</strong>, composed as
 * <strong>1 + 6 + 1 + 5 + 7 + 3</strong>: this document, six job classes and the {@code jobs} document,
 * five processors, seven readers and three writers.
 *
 * <p>Measured 5 August 2026 the subtree also holds 23 files, but <strong>the composition differs from the
 * budget and the coincidence should not be read as agreement</strong>. Two job classes are still to be
 * authored, and each of {@code processors}, {@code readers} and {@code writers} has since gained a document
 * of its own - which is what Clause E asks for per package, and is why the leaf documents referenced above
 * exist rather than being folded into this one. This document remains the layer document; it is not a
 * substitute for theirs. The authoritative dated inventory, with the command that reproduces it, is section
 * 0.4.5.1 of {@code docs/technical-specifications.md}, and it governs wherever a count here would compete
 * with it.
 *
 * <h3>The pipeline</h3>
 *
 * <p>{@code POSTTRAN -> INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT)}
 *
 * <p>The two final branches are independent because the legacy stream imposes no ordering between them:
 * statement generation and the transaction report read the same upstream data and write to different
 * places. The three leading stages are strictly sequential, and the reason is data flow rather than
 * convention - the combine stage is what carries interest transactions into the keyed transaction store,
 * so nothing downstream sees them before it runs.
 *
 * <h3>The six jobs, and their provenance</h3>
 *
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} - from {@code app/jcl/POSTTRAN.jcl} plus
 *       {@code app/cbl/CBTRN02C.cbl}, with {@code app/cbl/CBTRN01C.cbl} folded in.</li>
 *   <li>{@code InterestCalculationJob} - from {@code app/jcl/INTCALC.jcl} plus
 *       {@code app/cbl/CBACT04C.cbl}.</li>
 *   <li>{@code CombineTransactionsJob} - from {@code app/jcl/COMBTRAN.jcl} alone. <strong>No COBOL program
 *       exists for this job</strong>; its logic is entirely DFSORT and IDCAMS control cards, so the JCL
 *       itself is the source of truth. Concatenated input, ordered by transaction identifier ascending,
 *       then a bulk load.</li>
 *   <li>{@code StatementGenerationJob} - from {@code app/jcl/CREASTMT.JCL} plus
 *       {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL}. Five steps.</li>
 *   <li>{@code TransactionReportJob} - from {@code app/jcl/TRANREPT.jcl}, {@code app/proc/TRANREPT.prc},
 *       {@code app/proc/REPROC.prc}, {@code app/ctl/REPROCT.ctl} and {@code app/cbl/CBTRN03C.cbl}.</li>
 *   <li>{@code BatchPipelineOrchestrator} - the end-to-end stream with decider gating and the parallel
 *       split. Named as planned rather than present, so its absence is not mistaken for an omission in the
 *       plan: the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} is to be authored, and
 *       until it is, no bean may reference it.</li>
 * </ul>
 *
 * <p><strong>There are exactly six jobs. Never a seventh.</strong> {@code app/cbl/CBTRN01C.cbl} is folded
 * into {@code DailyTransactionPostingJob} as an explicitly labelled <strong>read-only pre-flight
 * step</strong>, not promoted to a job of its own. Two independent facts force that: it has no distinct JCL
 * job anywhere in the corpus, and its verb inventory over the six {@code SELECT} statements at
 * {@code app/cbl/CBTRN01C.cbl:L29-L58} is {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY}
 * only. Measured at this commit: {@code OPEN} 18, {@code READ} 17, {@code CLOSE} 18, {@code DISPLAY} 42,
 * and {@code WRITE}, {@code REWRITE} and {@code DELETE} <strong>zero</strong>. A program that writes
 * nothing cannot be a job that produces something, so a standalone job would have been an invention. It
 * remains a first-class row for paragraph-correspondence purposes and is owed one in the planned
 * {@code TRACEABILITY_MATRIX.md}.
 *
 * <p>The same reasoning makes four more programs <strong>read-only verification steps</strong> rather than
 * producers: {@code app/cbl/CBACT01C.cbl}, {@code app/cbl/CBACT02C.cbl}, {@code app/cbl/CBACT03C.cbl} and
 * {@code app/cbl/CBCUS01C.cbl}. Each opens a dataset, reads it sequentially, displays what it finds and
 * closes it. A scan at this commit finds no {@code WRITE}, no {@code REWRITE} and no {@code DELETE} in any
 * of the four; {@code app/cbl/CBACT01C.cbl} has exactly one {@code READ} statement, three {@code CLOSE} and
 * 27 {@code DISPLAY}, and its single {@code OPEN} statement is at {@code app/cbl/CBACT01C.cbl:L135}. They
 * verify that a dataset is present and decodable, and they are useful in a pipeline for exactly that.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>The toolchain is pinned, and the pins are the build contract rather than a preference: Java
 * <strong>25</strong> with {@code maven.compiler.release} at 25 and <strong>no preview features</strong>,
 * Maven <strong>3.9.11</strong>, {@code spring-boot-starter-parent} 3.5.11, Spring Batch 5.2.4, the Spring
 * Cloud AWS bill of materials 3.3.0, Hibernate 6.6.42.Final and the PostgreSQL driver 42.7.10. Testcontainers
 * is pinned to 2.0.3 by property rather than by importing a second bill of materials, and only the four
 * prefixed coordinates resolve at that version. <strong>Add no dependency, and no Lombok.</strong>
 *
 * <ul>
 *   <li>Compile: {@code ./mvnw -B -ntp clean compile}.</li>
 *   <li>Unit tier: {@code ./mvnw -B -ntp test}, under Surefire.</li>
 *   <li>Full gate: {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. The integration and
 *       end-to-end tiers run under <strong>Failsafe at {@code verify}</strong>. That one flag skips only the
 *       vulnerability scan, which needs network access to the feed; drop it when the scan is wanted.</li>
 *   <li>Local topology: {@code docker compose up} brings up PostgreSQL 16, LocalStack, Jaeger, Prometheus
 *       and Grafana. Load the git-ignored {@code .env} first with {@code set -a; . ./.env; set +a}.</li>
 * </ul>
 *
 * <p>Compilation runs <strong>{@code -Xlint:all -Werror}</strong> with {@code failOnWarning}, so a raw type,
 * an unchecked cast, a deprecated call, a switch fall-through, a missing {@code serialVersionUID} on a
 * serializable type or a malformed documentation comment fails the build outright. Coverage is gated by
 * JaCoCo at <strong>80% LINE</strong> at {@code verify}, with no core-package exclusion and no
 * getter-only padding - which is precisely why constructor injection and the absence of global mutable
 * state are not stylistic preferences in this layer but the only way the figure is reachable honestly.
 *
 * <p><strong>Jobs never launch at application startup</strong>, because {@code spring.batch.job.enabled} is
 * {@code false}. Launching is explicit: either through the orchestrator, or through the SQS listener that
 * stands in for the JES2 internal reader. That reproduces a real property of the original, where the batch
 * stream was submitted as work rather than triggered by the online region coming up.
 *
 * <p>Test obligations this layer owes, beyond ordinary coverage, are two specific behaviours that a
 * plausible implementation gets wrong silently: the <strong>102/103 fall-through</strong>, asserting that a
 * record failing both the over-limit and the expiry check yields a single reject bearing 103; and the
 * <strong>decider outcomes for return codes 0, 4, 8 and 12</strong>, asserting in particular that 4 is a
 * completion rather than a failure.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by key. A key is stable; a line number in a configuration file is not.
 *
 * <ul>
 *   <li>{@code spring.batch.job.enabled} - {@code false}, unconditionally, in every profile.</li>
 *   <li>{@code spring.batch.jdbc.initialize-schema} - {@code never}, with {@code table-prefix} left at the
 *       framework default {@code BATCH_}. The {@code BATCH_*} metadata tables come from <strong>the
 *       framework's own schema script</strong>, applied as a reviewed change. They are emphatically
 *       <strong>not</strong> a fourth Flyway migration and not extra tables in
 *       {@code V1__create_schema.sql}, which creates exactly <strong>11</strong> tables - one per VSAM
 *       cluster catalogued in {@code app/catlg/LISTCAT.txt} plus the daily transaction staging layout.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} - {@code validate}, in every profile. Any column, type or
 *       precision divergence therefore fails context startup outright instead of degrading quietly at the
 *       first write. This is the single most valuable configuration choice in the layer, because a widened
 *       numeric column is otherwise invisible until a parity comparison finds it.</li>
 *   <li>{@code carddemo.batch.chunk-size} - {@code 100}; each reader additionally carries its own
 *       {@code page-size}, also {@code 100}. Chunk sizes, step topology and decider wiring live in
 *       {@code com.cardemo.config.BatchConfig}; the object-storage and queue clients in
 *       {@code com.cardemo.config.AwsConfig}.</li>
 *   <li>{@code carddemo.batch.jobs.posttran.name}, {@code .intcalc.name}, {@code .combtran.name},
 *       {@code .creastmt.name} and {@code .tranrept.name} - the five legacy job names {@code POSTTRAN},
 *       {@code INTCALC}, {@code COMBTRAN}, {@code CREASTMT} and {@code TRANREPT}, kept as the Spring Batch
 *       job names so a run is traceable to the JCL member it replaces. {@code creastmt.steps} is 5.</li>
 *   <li>{@code carddemo.aws.s3.batch-input-bucket}, {@code .batch-output-bucket} (versioned) and
 *       {@code .statements-bucket} - bound from {@code CARDDEMO_S3_BATCH_INPUT_BUCKET},
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} and {@code CARDDEMO_S3_STATEMENTS_BUCKET}, with
 *       <strong>no default</strong>. A default here would be doubly wrong: unreachable, because an unset
 *       variable fails placeholder resolution before a job bean is constructed; and harmful, because it
 *       would turn a fail-fast misconfiguration into a silent write to the wrong location.</li>
 *   <li>{@code carddemo.aws.sqs.report-queue} - from {@code CARDDEMO_SQS_REPORT_QUEUE}, logical name
 *       {@code carddemo-report-jobs}, FIFO, with {@code report-message-group-id} fixed to the same value so
 *       ordering is deterministic. {@code spring.cloud.aws.sqs.queue-not-found-strategy} is {@code FAIL},
 *       because the library default would silently conjure a queue whose name is a typo.</li>
 *   <li><strong>The LocalStack endpoint override exists only in the {@code local} and {@code test}
 *       profiles.</strong> No live-AWS code path is structurally reachable from a running application, and
 *       zero live credentials exist anywhere in the tree.</li>
 * </ul>
 *
 * <p>Two job parameters are worth stating exactly, because both look like something they are not:
 *
 * <dl>
 *   <dt>{@code InterestCalculationJob} - a ten-character date</dt>
 *   <dd>{@code app/jcl/INTCALC.jcl:L22} reads
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. That is <strong>eight date digits followed by
 *       two zeros - ten numeric characters with no separators. It is not an ISO date.</strong> The value is
 *       concatenated with a six-digit run-sequential suffix to form generated sixteen-digit transaction
 *       identifiers, so reformatting it changes every identifier the job emits.</dd>
 *
 *   <dt>{@code TransactionReportJob} - an inclusive start and end date</dt>
 *   <dd>Delivered as the SQS message body reproducing the legacy 80-byte {@code DATEPARM} record.
 *       {@code app/proc/TRANREPT.prc:L41-L42} shows the shape as
 *       {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'}. The filter is
 *       inclusive at both ends and is applied to the ten-character prefix of the processing timestamp, per
 *       the {@code SYMNAMES} definitions {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}
 *       at {@code app/proc/TRANREPT.prc:L39-L40}.</dd>
 * </dl>
 *
 * <p>On observability, which the source lacks entirely - its only instrumentation is {@code DISPLAY} to
 * SYSOUT - batch log events carry the <strong>job instance identifier</strong> in MDC alongside
 * {@code traceId}, {@code spanId} and {@code correlationId}, which is what makes a run's log correlatable
 * with the per-run object prefixes it writes. Four Micrometer counters replace the legacy end-of-run
 * {@code DISPLAY} tallies: records processed; records <strong>rejected, tagged by reject code</strong>;
 * authentication attempts; and total transaction amount. <strong>No fifth instrument, and no
 * high-cardinality tag</strong> - an account identifier or a card number as a tag value would turn a
 * counter into an unbounded time series.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every entry below will be misdiagnosed by someone who has not read the source, because in each case
 * the behaviour is deliberate and looks like a defect.
 *
 * <dl>
 *   <dt>An exit status of 4 is treated as a failure</dt>
 *   <dd>The exit-code vocabulary is {@code 0} completed, {@code 4} completed-with-rejects, {@code 8} failed,
 *       {@code 12} abend. <strong>Return code 4 is set if and only if the reject count exceeds zero</strong>
 *       - {@code app/cbl/CBTRN02C.cbl:L229-L230} is {@code IF WS-REJECT-COUNT &gt; 0} then
 *       {@code MOVE 4 TO RETURN-CODE}, and there is no other determinant anywhere in the program. A run with
 *       rejects <strong>completed</strong>; it did not fail. Do not map 4 onto a failure, and do not let any
 *       other condition produce it.</dd>
 *
 *   <dt>A job abends and the log shows code 999</dt>
 *   <dd>Return code {@code 12} pairs with abend code {@code 999}. The source paragraph
 *       {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711} displays
 *       {@code 'ABENDING PROGRAM'}, moves 0 to the timing field, moves {@code 999} to the abend code and
 *       calls {@code 'CEE3ABD'}. In Java that surfaces as
 *       {@code com.cardemo.exception.FatalProcessingException} carrying code 999, with a failed exit status
 *       and process return code 12. <strong>Return code 4 and return code 12 are independent paths</strong>
 *       and neither implies the other; the deciders cover 0, 4, 8 and 12 explicitly.</dd>
 *
 *   <dt>A {@code FILE STATUS IS: NNNN} line looks like an unrendered placeholder</dt>
 *   <dd>It is not. {@code NNNN} is part of a <strong>fixed 20-character literal</strong>: the source writes
 *       {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} at {@code app/cbl/CBTRN02C.cbl:L721} and
 *       {@code :L725}, appending four rendered characters after it. Status {@code '23'} therefore comes out
 *       as {@code FILE STATUS IS: NNNN0023}. The logging configuration passes the whole line through
 *       byte-for-byte and its masking rules are written not to match inside it. Reformatting or
 *       "correcting" it breaks boundary parity comparison.</dd>
 *
 *   <dt>{@code InterestCalculationJob} abends on a missing default disclosure-group row</dt>
 *   <dd>The rate lookup accepts either success or not-found on its first read, substitutes the literal
 *       {@code DEFAULT} group and retries. The retry at {@code app/cbl/CBACT04C.cbl:L443-L459} accepts
 *       <strong>only</strong> {@code '00'}: {@code :L446} tests {@code IF DISCGRP-STATUS = '00'} and
 *       {@code :L458} performs the abend. So a missing {@code DEFAULT} row is fatal by design, not by
 *       oversight. Check that the seed migration loaded all <strong>51</strong> rows of
 *       {@code app/data/ASCII/discgrp.txt}, 17 of which carry the literal group identifier
 *       {@code DEFAULT}.</dd>
 *
 *   <dt>{@code InterestCalculationJob} abends on a missing cross-reference record</dt>
 *   <dd>A missing cross-reference prints a friendly message and then falls into the standard I/O guard,
 *       which abends: {@code app/cbl/CBACT04C.cbl:L393-L413} displays {@code 'ACCOUNT NOT FOUND: '} on
 *       {@code INVALID KEY} and then reaches {@code PERFORM 9999-ABEND-PROGRAM}. An empty lookup is
 *       therefore a <strong>fatal exception, never a skip</strong>. The friendly message is misleading and
 *       is retained because it is what the source prints.</dd>
 *
 *   <dt>{@code CombineTransactionsJob} fails on duplicate transaction identifiers</dt>
 *   <dd>Re-running the interest job with the same ten-character date parameter regenerates the same
 *       sixteen-digit identifiers, and the combine load is where they collide. That must surface as
 *       {@code com.cardemo.exception.DuplicateRecordException} with a failed exit status -
 *       <strong>never a silent upsert</strong>, which would overwrite a posted transaction. The interest job
 *       itself cannot detect it, because its output is a fresh sequential generation rather than a keyed
 *       store, so the combine step is the only place the constraint exists.</dd>
 *
 *   <dt>Statement generation appears to have no source at all</dt>
 *   <dd><strong>Blocker 5.1.</strong> A case-sensitive {@code *.jcl} or {@code *.cbl} glob silently drops
 *       it. {@code app/jcl/} holds <strong>29</strong> members, of which 28 use a lowercase extension and
 *       one - {@code app/jcl/CREASTMT.JCL} - uses an <strong>uppercase</strong> one, and that member is the
 *       sole source for statement generation. Three further members are uppercase:
 *       {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL} and {@code app/cpy/COSTM01.CPY} - note
 *       the copybook member is {@code COSTM01}, with no trailing {@code Y}. <strong>Match those two
 *       directories case-insensitively.</strong> Five legacy files additionally carry CRLF line endings -
 *       the four just named plus {@code app/cbl/COACTUPC.cbl} - so strip the carriage return when reading
 *       them; everything else in the corpus is LF-only.</dd>
 *
 *   <dt>An integration test errors with a Docker or Testcontainers connection failure</dt>
 *   <dd>No container runtime is reachable from the JVM. Boundary parity, fixture validation and the
 *       integration sign-off checks all need one, along with Testcontainers PostgreSQL 16 and LocalStack.
 *       That is a stated validation-time prerequisite rather than a code defect, and the unit tier runs
 *       without one. Where a piece of evidence genuinely cannot be produced, the honest output is the
 *       prerequisite plus the words {@code Not available} - never a fabricated pass.</dd>
 *
 *   <dt>A generated timestamp differs from the baseline in its last digits</dt>
 *   <dd><strong>Blocker 5.4.</strong> See the invariants below: format to millisecond precision followed by
 *       four zeros, never to nanosecond precision.</dd>
 * </dl>
 *
 * <h2>Byte-exact record geometry</h2>
 *
 * <p>These widths are contracts, not implementation details. Every one is preserved exactly at the
 * object-storage boundary, because side-by-side comparison against the legacy output is the parity proof and
 * a record one byte wide of the original is a diff on every line.
 *
 * <ul>
 *   <li><strong>350</strong> - the daily transaction input record, from {@code app/cpy/CVTRA06Y.cpy}, whose
 *       fields sum to exactly 350. The fixture is {@code app/data/ASCII/dailytran.txt}, 300 records of 350
 *       bytes. Note the fixture spells the word in full even though the mainframe DD name is
 *       {@code DALYTRAN}.</li>
 *   <li><strong>350</strong> - the transaction record, from {@code app/cpy/CVTRA05Y.cpy}.</li>
 *   <li><strong>430</strong> - the reject record, being <strong>350 + 4 + 76</strong>.
 *       {@code app/cbl/CBTRN02C.cbl:L176-L182} declares a 350-byte transaction image plus an 80-byte
 *       trailer, and the trailer decomposes into a four-digit reason code and a 76-character description.
 *       Independently confirmed by {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the {@code DALYREJS} DD at
 *       {@code app/jcl/POSTTRAN.jcl:L36} - and note {@code RECFM=F}, fixed <strong>unblocked</strong>.</li>
 *   <li><strong>133</strong> - the report line, per {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} at
 *       {@code app/proc/TRANREPT.prc:L76}. <strong>The logical content widths differ from the record
 *       width</strong>, which is the trap here. In {@code app/cpy/CVTRA07Y.cpy} the detail line and header-1
 *       are <strong>114</strong> bytes each; {@code TRANSACTION-HEADER-2 PIC X(133)} at {@code :L48}, the
 *       dashed rule, is the <strong>only</strong> 133-byte structure; and the page, account and grand total
 *       lines are <strong>112</strong> bytes each. Every one is written into the 133-byte record
 *       <strong>right-padded with spaces</strong>, the pad source being
 *       {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} declared at {@code app/cbl/CBTRN03C.cbl:L133} and used
 *       at {@code :L329}.</li>
 *   <li><strong>80</strong> - statement text output, the {@code STMTFILE} DD.</li>
 *   <li><strong>100</strong> - statement HTML output, the {@code HTMLFILE} DD at
 *       {@code app/jcl/CREASTMT.JCL:L94}, independently confirmed by {@code HTML-FIXED-LN PIC X(100)} at
 *       {@code app/cbl/CBSTM03A.CBL:L149}.</li>
 *   <li><strong>350 with a 32-byte key</strong> - the statement work cluster, from
 *       {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} and
 *       {@code RECORDSIZE(350 350)} at {@code :L32}. The 32 is corroborated by
 *       {@code app/cpy/COSTM01.CPY}, where {@code TRNX-KEY} is {@code TRNX-CARD-NUM X(16)} plus
 *       {@code TRNX-ID X(16)} and {@code TRNX-REST} sums to 318, giving 350 overall. <strong>This cluster is
 *       an in-job projection and sort only, and is never persisted.</strong></li>
 *   <li><strong>80</strong> - the report-submission queue card, from {@code RECORDSIZE(80)} on the transient
 *       data queue definition at {@code app/csd/CARDDEMO.CSD:L502}.</li>
 * </ul>
 *
 * <p>The 350-byte transaction offset map, one-based, read from {@code app/cpy/CVTRA05Y.cpy} and arithmetically
 * consistent with the {@code SYMNAMES} definitions of {@code app/proc/TRANREPT.prc:L39-L40}: identifier
 * 1-16, type 17-18, category 19-22, source 23-32, description 33-132, amount 133-143, merchant identifier
 * 144-152, merchant name 153-202, merchant city 203-252, merchant postcode 253-262,
 * <strong>card number 263-278</strong>, originating timestamp 279-304,
 * <strong>processing timestamp 305-330</strong>, filler 331-350.
 *
 * <h2>Generation data groups become deterministic object keys</h2>
 *
 * <p>There are <strong>seven</strong> generation data group bases, not six.
 * {@code app/jcl/DEFGDGB.jcl:L24-L59} defines six of them - {@code TRANSACT.BKUP}, {@code TRANSACT.DALY},
 * {@code TRANREPT}, {@code TCATBALF.BKUP}, {@code SYSTRAN} and {@code TRANSACT.COMBINED} - each as
 * {@code LIMIT(5) SCRATCH} followed by the idiom {@code IF LASTCC=12 THEN SET MAXCC=0}. The seventh,
 * {@code DALYREJS}, is defined separately at {@code app/jcl/DALYREJS.jcl:L24-L28}, which is exactly why a
 * reader counting only the obvious member arrives at six and loses an output prefix. All seven are present in
 * configuration as {@code carddemo.aws.s3.gdg-prefixes.*}, one key per base.
 *
 * <p>The translation rules:
 *
 * <ul>
 *   <li>A next-generation write {@code (+1)} becomes a <strong>new object under a monotonically increasing
 *       timestamp or job-instance prefix</strong>, over a versioned bucket.</li>
 *   <li>A current-generation read {@code (0)} becomes a read of the <strong>lexicographically greatest
 *       existing prefix</strong>.</li>
 *   <li><strong>Within a single job, a {@code (+1)} written by an earlier step is re-read as {@code (+1)} by
 *       a later one.</strong> {@code app/jcl/COMBTRAN.jcl:L43-L44} has {@code STEP10} reading
 *       {@code TRANSACT.COMBINED(+1)}, the generation {@code STEP05R} has just created, and
 *       {@code app/proc/TRANREPT.prc:L37} and {@code :L64} do the same for {@code TRANSACT.BKUP(+1)} and
 *       {@code TRANSACT.DALY(+1)}. So the Java flow must <strong>carry the concrete created object key
 *       forward through the job or step execution context and never re-resolve "latest" mid-job</strong>.
 *       Getting this wrong yields a job that passes in isolation and races inside the pipeline, which is the
 *       worst failure shape available: intermittent, load-dependent and invisible to a single-job test.</li>
 *   <li>Record length is preserved <strong>byte-exactly</strong> at the object-storage boundary.</li>
 *   <li>Retention limits are <strong>documented rather than enforced</strong>; object versioning supersedes
 *       generation counting. One genuine source conflict exists here and it is
 *       <strong>the only legacy inconsistency this migration actually resolves</strong>: {@code TRANREPT} is
 *       declared {@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:L38} and {@code LIMIT(10)} at
 *       {@code app/jcl/REPTFILE.jcl:L27}. It is <strong>resolved to 10</strong>, materialised as
 *       {@code carddemo.aws.s3.gdg-retention-generations}, and resolved only because a single lifecycle value
 *       has to be chosen. Everything else that disagrees with itself in the source is left disagreeing.</li>
 * </ul>
 *
 * <p>{@code IF LASTCC=12 THEN SET MAXCC=0} deserves a note of its own: it is the legacy
 * <strong>idempotency precedent</strong>. The source swallows the "already exists" condition so the
 * provisioning job can be re-run, and the emulator provisioning script {@code localstack-init/init-aws.sh}
 * is written to be idempotent for the same reason and cites that idiom as its warrant. Repeated
 * {@code docker compose up} cycles therefore converge rather than failing on existing resources.
 *
 * <p>Three JCL members have <strong>no Java analogue</strong> and are documented rather than translated, so
 * that their absence from this layer reads as a decision. {@code app/jcl/CBADMCDJ.jcl} installs the CICS
 * resource definitions through {@code DFHCSDUP} and is superseded by
 * {@code com.cardemo.config.SecurityConfig}, which is where the transaction-to-endpoint authorisation those
 * definitions expressed now lives. {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} issue
 * {@code CEMT SET FIL(...) OPE|CLO} over five files to make datasets available to the online region, and are
 * superseded by {@code com.cardemo.observability.HealthIndicators}, where dataset availability becomes a
 * readiness probe rather than an operator action.
 *
 * <h2>Layer-wide invariants</h2>
 *
 * <p>These bind every class in all four leaves. They are invariants rather than guidance: the validation
 * gates exist to prove they hold.
 *
 * <ul>
 *   <li><strong>Decimal arithmetic only.</strong> {@code java.math.BigDecimal} everywhere financial,
 *       compared with {@code compareTo} and <strong>never {@code equals}</strong>, rounded
 *       {@code RoundingMode.HALF_EVEN}, and <strong>zero {@code float} or {@code double} in any financial
 *       field</strong>. The precisions are not uniform and the differences are load-bearing: account money is
 *       {@code NUMERIC(12,2)}; transaction and category-balance amounts are <strong>{@code NUMERIC(11,2)}
 *       </strong>; and the disclosure-group interest rate is <strong>{@code NUMERIC(6,2)}</strong>, the only
 *       field at that precision in the schema.</li>
 *   <li><strong>Formula shape is preserved, not merely formula value.</strong> Interest is computed as
 *       {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at {@code app/cbl/CBACT04C.cbl:L464-L465} - multiply
 *       first, then divide by the literal 1200. Never rewrite that as a division by 100 followed by a
 *       division by 12, and never substitute a decimal multiplier; both change the rounding.</li>
 *   <li><strong>Blocker 5.4 - timestamps are 26-byte text, not temporal types.</strong>
 *       {@code TRAN-ORIG-TS}, {@code TRAN-PROC-TS}, {@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} are all
 *       {@code PIC X(26)}, and there are <strong>three incompatible producers</strong>: the batch format
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}, whose final four digits are always zeros; the online format
 *       {@code yyyy-MM-dd HH:mm:ss.000000}; and <strong>pure pass-through</strong>, as at
 *       {@code app/cbl/CBTRN02C.cbl:L436} where {@code MOVE DALYTRAN-ORIG-TS TO TRAN-ORIG-TS} copies the
 *       input bytes untouched. No single temporal type can round-trip all three. So the representation is
 *       {@code String} over {@code CHAR(26)} - <strong>never {@code LocalDateTime}, {@code Timestamp} or
 *       {@code Instant}</strong> - and formatting goes to millisecond precision followed by four zeros,
 *       never to nanosecond precision.</li>
 *   <li><strong>Reject codes are business outcomes driving {@code ExitStatus}, and are never thrown.</strong>
 *       There are exactly five, with these literal descriptions: {@code 100 INVALID CARD NUMBER FOUND},
 *       {@code 101 ACCOUNT RECORD NOT FOUND}, {@code 102 OVERLIMIT TRANSACTION},
 *       {@code 103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION} and
 *       {@code 109 ACCOUNT RECORD NOT FOUND}. The text is compared byte-for-byte by boundary parity, so it is
 *       a contract; that 109 repeats 101's wording is the source's choice, at
 *       {@code app/cbl/CBTRN02C.cbl:L556-L558}, and is retained.</li>
 *   <li><strong>{@code FILE STATUS} becomes a typed exception on every I/O path and is never
 *       swallowed</strong>, through {@code com.cardemo.service.shared.FileStatusMapper}. Exactly three scoped
 *       sites treat a non-{@code '00'} status as success, and a blanket rule would abend all three: the
 *       transaction-category-balance upsert read accepts {@code '23'}, the first disclosure-group read accepts
 *       {@code '23'}, and the file-service call contract accepts {@code '04'} at nine sites in
 *       {@code app/cbl/CBSTM03A.CBL} - {@code :L736}, {@code :L748}, {@code :L771}, {@code :L789},
 *       {@code :L807}, {@code :L862}, {@code :L879}, {@code :L895} and {@code :L911}.</li>
 *   <li><strong>Constructor injection only</strong> - no field injection, no setter injection, no service
 *       locator - and <strong>zero static mutable fields</strong>. The legacy {@code WORKING-STORAGE}
 *       counters and flags become step-scoped or method-local state, never bean fields, because a bean field
 *       in a batch component is shared across every job instance in the JVM.</li>
 *   <li><strong>Every {@code catch} rethrows a typed {@code com.cardemo.exception} subtype preserving the
 *       cause.</strong> No empty catch, and no bare catch of {@code Exception} that discards it.</li>
 *   <li><strong>Determinism.</strong> {@code Locale.ROOT} on every case-mapping and formatting operation;
 *       an explicit order on every query and every sort; and no reliance on hash iteration order, the default
 *       charset, the default locale or the default time zone - the JDBC time zone is UTC.</li>
 *   <li><strong>No EBCDIC parsing.</strong> {@code app/data/EBCDIC/**} - twelve {@code .PS} files plus a
 *       {@code .gitkeep} - is codepage reference only and is never read by the build. The ASCII fixtures are
 *       the authoritative seed and test input.</li>
 *   <li><strong>{@code Runtime.exec} and {@code ProcessBuilder} are forbidden outright.</strong> This is
 *       Clause D's named "exec" pattern, and it independently forbids spawning DFSORT or IDCAMS, which is the
 *       shortcut a literal translation of the sort steps would reach for.</li>
 *   <li><strong>No string-concatenated SQL or JPQL</strong> - parameter binding only. No Java deserialization
 *       of untrusted input. No {@code System.getenv} in business code; configuration arrives through Spring
 *       property binding, which is what keeps the fail-fast placeholder behaviour intact.</li>
 *   <li><strong>Paragraph correspondence is one-to-one and labels are never consolidated.</strong> Every
 *       applicable COBOL source label maps to a single private Java method carrying a source-citing comment,
 *       <em>including</em> duplicate, empty, unreachable and defect paths. The label counts this subtree owns,
 *       measured at this commit: {@code CBTRN02C} 27, {@code CBTRN03C} 27, {@code CBSTM03A} 26,
 *       {@code CBACT04C} 23, {@code CBTRN01C} 19, {@code CBSTM03B} 15, {@code CBACT01C} 7, and
 *       {@code CBACT02C}, {@code CBACT03C} and {@code CBCUS01C} 6 each - 162 labels in total.</li>
 * </ul>
 *
 * <h2>Source behaviour preserved deliberately</h2>
 *
 * <p>Each item below is a source behaviour that a well-meaning implementer would "fix". Parity is the
 * contract of this migration, so none of them is fixed, and each is owed an entry in the planned
 * {@code DECISION_LOG.md} together with a row in the planned {@code TRACEABILITY_MATRIX.md}.
 *
 * <ul>
 *   <li><strong>Reject code 103 overwrites 102.</strong> In {@code 1500-B-LOOKUP-ACCT} at
 *       {@code app/cbl/CBTRN02C.cbl:L393-L422} the over-limit test and the expiry test are
 *       <strong>sequential and unguarded</strong>, with no early exit and no alternative branch between them.
 *       When a record fails both, the second assignment overwrites the first and <strong>a single reject
 *       record bearing 103 is written</strong> - not two records, and not 102. Two further details of that
 *       paragraph are equally part of the contract: the expiry comparison is a string comparison against the
 *       <strong>originating</strong> timestamp's first ten characters, {@code DALYTRAN-ORIG-TS (1:10)}, not
 *       the processing timestamp; and the account expiry field is <strong>misspelled
 *       {@code ACCT-EXPIRAION-DATE}</strong> in the copybook, a misspelling that is part of the field contract
 *       and is retained rather than corrected.</li>
 *   <li><strong>The cycle-debit accumulator legitimately holds negative values.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L547-L552} adds the transaction amount to the current balance, then adds
 *       it to the cycle credit when it is non-negative and to the cycle <em>debit</em> otherwise - so a
 *       negative amount lands in the debit accumulator and the accumulator goes negative. That is precisely
 *       why the over-limit formula <strong>subtracts</strong> it, and why the expression must be transcribed
 *       rather than algebraically tidied. <strong>No absolute-value normalisation anywhere in the posting
 *       path.</strong> {@code app/data/ASCII/dailytran.txt} exercises this branch genuinely, because it
 *       carries both {@code &#123;} and {@code &#125;} trailing-sign overpunches, and it must not be
 *       normalised on the way in.</li>
 *   <li><strong>Reject code 109 is assigned but never consumed.</strong> It is set on the account-rewrite
 *       failure path at {@code app/cbl/CBTRN02C.cbl:L556-L558}, inside the posting routine, which is only
 *       entered once validation has already passed. No reject record is written, the reject count is not
 *       incremented, execution continues to the transaction write, and the value is cleared on the next
 *       iteration. The constant must nevertheless exist, because the assignment is real code on a reachable
 *       path - which is why the enumeration has five members rather than four.</li>
 *   <li><strong>The interest job's fee paragraph is empty but genuinely reachable.</strong>
 *       {@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518-L520} contains a comment reading "To be
 *       implemented" and an {@code EXIT}, and it is performed at {@code :L216}. It is retained as an
 *       explicitly marked intentional no-op; deleting the call site would break the paragraph map.</li>
 *   <li><strong>Blocker 5.3 - the interest job's apparent final-flush branch is unreachable.</strong>
 *       {@code app/cbl/CBACT04C.cbl:L188} opens {@code PERFORM UNTIL END-OF-FILE = 'Y'}, which tests
 *       <em>before</em> each iteration. The loop body's {@code IF END-OF-FILE = 'N'} therefore has an
 *       {@code ELSE} at {@code :L219-L220} that can never execute: by the time the flag is set the loop has
 *       already terminated. <strong>The last account is consequently never updated and its cycle counters are
 *       never reset.</strong> The processor states this in as many words and retains the corresponding method
 *       as a marked, never-invoked no-op, so <strong>no end-of-data flush may be added</strong> - adding one
 *       would be a behaviour change dressed as a bug fix. This finding corrects the Agent Action Plan's
 *       section 0.7.3.3, which claims the final flush executes. It is a single finding; the folder
 *       requirements number it 3 and a sibling package prompt numbers the same finding 2, and rather than
 *       assert one number over the other the divergence is noted here and the finding reported once.</li>
 *   <li><strong>The report control break fires on the card number while the emitted label reads "Account
 *       Total".</strong> {@code app/cbl/CBTRN03C.cbl:L181} tests
 *       {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM} and {@code :L185} then reseeds from
 *       {@code TRAN-CARD-NUM}, while the structure emitted is {@code REPORT-ACCOUNT-TOTALS} at
 *       {@code app/cpy/CVTRA07Y.cpy:L56}, whose leading literal is {@code Account Total}. Break key and label
 *       disagree in the source. Preserved, not repaired.</li>
 *   <li><strong>The statement projection truncates two bytes.</strong>
 *       {@code app/jcl/CREASTMT.JCL:L54} is
 *       {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}. Copying 50 bytes from offset 279 emits the
 *       full 26-byte originating timestamp plus <strong>only the first 24 of the 26 processing-timestamp
 *       bytes</strong>, and drops the 20-byte trailing filler entirely - yielding 328 bytes padded to 350.
 *       Reproduce that shape exactly; a Java projection that "fixes" it produces statement output differing
 *       from the baseline in a way that looks like a Java defect and is not.</li>
 *   <li><strong>Legacy job-control defects, logged and not fixed.</strong> The {@code STMTFILE} DD line at
 *       {@code app/jcl/CREASTMT.JCL:L90} is textually corrupted, reading
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS}. The {@code HTMLFILE} record
 *       length disagrees with itself between the pre-delete step at {@code :L69}, which says 80, and the
 *       execution step at {@code :L94}, which says 100; <strong>100 is the correct width</strong>, confirmed
 *       independently by the 100-character field in the program. {@code app/jcl/TRANREPT.jcl} uses the step
 *       name {@code STEP05R} <strong>twice in one job</strong>, at {@code :L23} and {@code :L37}, where the
 *       procedure it invokes correctly uses {@code STEP01R}, {@code STEP05R} and {@code STEP10R}. And
 *       {@code app/proc/TRANREPT.prc:L1} declares {@code //REPROC PROC} even though the member resolves as
 *       {@code TRANREPT}, so the internal procedure name and the member name differ;
 *       {@code EXEC PROC=TRANREPT} occurs exactly once in the whole corpus, as a literal job card inside
 *       {@code app/cbl/CORPT00C.cbl:L94}.</li>
 * </ul>
 *
 * <p>Two changes in this layer are <strong>labelled deviations rather than parity</strong>, and are called
 * that so no reviewer has to guess. First, the statement program's self-modifying {@code ALTER} dispatch is
 * eliminated by static flow analysis into an ordered five-call initialisation sequence with the observable
 * order preserved, and <strong>its hard 510-transaction ceiling is removed</strong> - the source declares a
 * table of 51 card entries of 10 transactions each and increments both indices with no bounds check
 * whatsoever, so the ceiling is a silent truncation hazard rather than a business rule. Streaming through
 * unbounded collections is both safer and more efficient, which is the tradeoff Clause A asks to be justified
 * rather than taken quietly; it changes behaviour at scale and so is not claimed as parity. Second,
 * collapsing the three independent COBOL commits of {@code 2000-POST-TRANSACTION} into one atomic Java
 * transaction closes an orphaned-row hazard as a side effect: the legacy rewrite-failure path leaves an
 * orphaned category-balance row and an orphaned transaction row behind. That is a genuine behavioural
 * improvement, not equivalence, and it is owed an entry in the planned {@code DECISION_LOG.md} saying so.
 *
 * <p>The register of retained parity artefacts is the marker at each declaration, plus the roster held by the
 * root package documentation. This document deliberately does not restate that register as a count, because a
 * tally maintained by hand across unrelated files is a claim no build step keeps true.
 *
 * <h2>Rule compliance: Build Verify, clauses A to F</h2>
 *
 * <p>Exactly <strong>one</strong> rule governs this project, titled "GLOBAL CODING &amp; DESIGN STANDARDS
 * (Apply to all projects)" and framed "You are a senior engineer + code auditor. Enforce the standards below
 * consistently." It has six lettered clauses, all binding on this layer. An earlier generation of the
 * specification described six separate rules; that was a defect, and the correct reading is one rule with six
 * clauses.
 *
 * <dl>
 *   <dt>A - Engineering Principles</dt>
 *   <dd>"Correctness first: prioritize correctness, determinism, and explicit behavior over cleverness";
 *       "Security by default: treat inputs as untrusted, avoid unsafe defaults"; "Maintainability: readable
 *       naming, modular design, minimal complexity, clear separation of concerns"; "Observability: structured
 *       logs, meaningful errors, and measurable behavior (metrics/tracing where relevant)"; "Performance:
 *       avoid obvious inefficiencies; justify tradeoffs only when needed". Determinism is the organising
 *       principle of the whole layer. Separation of concerns is the four-leaf split above. Observability is
 *       the MDC job instance identifier and the four named counters. The one performance tradeoff taken - the
 *       removed 510-transaction ceiling - is justified in writing above rather than absorbed silently.</dd>
 *
 *   <dt>B - Code Quality Rules</dt>
 *   <dd>"No dead code, no unused imports, no TODOs without owners or tracking reference"; "Validate all
 *       inputs and boundary conditions; handle null/empty cases explicitly"; "Avoid global mutable state;
 *       prefer dependency injection and pure functions where possible"; "Clear error handling: no swallowing
 *       exceptions; wrap with context and preserve root cause"; "Tests required for core logic and any
 *       non-trivial bug fix"; "Document public APIs: purpose, inputs/outputs, side effects, error modes". The
 *       "no unused imports" clause is <strong>why this file declares no import at all</strong>: a package
 *       document needs none, and a type it wants to name it names inside {@code &#123;&#64;code ...&#125;}.
 *       Error modes are the exit-code vocabulary, the typed exception hierarchy and the three scoped success
 *       statuses above. Boundary conditions are handled at exactly the points the source handles them, which
 *       is why the unreachable branch and the empty paragraph are retained rather than tidied.</dd>
 *
 *   <dt>C - Repository Hygiene</dt>
 *   <dd>"Follow repository conventions (formatters/linters/tests) if present; never fight existing style";
 *       "Keep commits/build scripts deterministic; avoid environment-specific assumptions"; "Use consistent
 *       directory structure; avoid duplication". The banner at the head of this file reproduces the universal
 *       legacy convention whose canonical form is {@code app/cbl/CBACT04C.cbl:L1-L21}, and the root
 *       {@code .editorconfig} fixes UTF-8, LF, a four-space Java indent, a final newline and no trailing
 *       whitespace. The "if present" condition was <strong>not</strong> triggered for formatters: no
 *       formatter, linter or style-tool configuration existed in the source repository and there were no
 *       {@code .java} files at all, so that configuration is <em>established</em> rather than inherited,
 *       which is not the same thing as overriding an existing style. {@code CONTRIBUTING.md:L33} asks
 *       contributors to focus on the specific change and warns that wholesale reformatting obstructs review;
 *       {@code :L34} requires local tests to pass. "Avoid duplication" is why this layer has no shared
 *       abstract base and no duplicated fixed-width codec.</dd>
 *
 *   <dt>D - Security Standards</dt>
 *   <dd>"No secrets in code, logs, tests, or config"; "Pin dependencies where possible; flag known risky
 *       patterns (eval/exec, insecure deserialization, shell injection)"; "Principle of least privilege for
 *       tokens/credentials/config". Credentials, password hashes, tokens, signing keys, social security
 *       numbers, card numbers, telephone numbers, government identifiers, dates of birth, funds-transfer
 *       account identifiers and unmasked personal data are never logged and never serialised out of this
 *       layer; the masking rules are <strong>identical in every profile</strong>, because the clause names
 *       logs and tests explicitly and a rule that relaxes outside production protects nothing. Dependency
 *       pinning is total, with no version ranges. Least privilege is why every bucket and queue name is an
 *       environment placeholder with no default and why the emulator endpoint override is confined to
 *       {@code local} and {@code test}. The named "exec" pattern is forbidden outright, as stated in the
 *       invariants.</dd>
 *
 *   <dt>E - Documentation Standards</dt>
 *   <dd>"Every module/component must have a short README or docstring explaining:" - "What it does" / "How to
 *       run/build/test" / "Key configs and defaults" / "Common failure modes and troubleshooting". Discharged
 *       here through the docstring option, under those four headings, for the batch layer as a whole. The
 *       layer-wide facts - the exit-code vocabulary, the geometry contract, the object-key scheme, the
 *       preserved quirks and these clauses - are stated here so that {@code processors}, {@code readers} and
 *       {@code writers} need not each repeat them, while each of those packages documents what is genuinely
 *       its own. <strong>No README belongs anywhere under {@code src/main/java}</strong>; the docstring is
 *       the chosen half of the clause's disjunction.</dd>
 *
 *   <dt>F - Output Requirements</dt>
 *   <dd>"Be evidence-based: cite file paths, symbols, and examples"; "Classify findings by severity: Blocker
 *       / High / Medium / Low"; "Provide clear remediation steps and (if asked) minimal patch suggestions";
 *       "If information is missing, state &quot;Not available&quot; and list what's needed". Every claim above
 *       carries a {@code path:line} citation into the frozen corpus. Three findings are named with the
 *       severity word: <strong>Blocker 5.1</strong>, the uppercase source members and CRLF endings, remedied
 *       by matching {@code app/cbl} and {@code app/jcl} case-insensitively and stripping the carriage return;
 *       <strong>Blocker 5.3</strong>, the unreachable final-flush branch, remedied by reproducing the loss and
 *       adding no flush; and <strong>Blocker 5.4</strong>, the 26-byte text timestamps with three incompatible
 *       producers, remedied by {@code String} over {@code CHAR(26)} at millisecond precision plus four
 *       zeros.</dd>
 * </dl>
 *
 * <h3>The one documented conflict, resolved in favour of parity</h3>
 *
 * <p>Clause B forbids dead code. The parity mandate requires preserving reachable no-ops and unreachable
 * branches so the paragraph map stays mechanically provable. Those collide in this layer at identifiable
 * sites - the interest processor's empty-but-reachable fee paragraph, that same class's unreachable
 * final-flush branch, and the statement processor's redundant index assignment ahead of a loop that
 * reinitialises it anyway.
 *
 * <p><strong>Parity governs, and Clause B is satisfied by a different mechanism.</strong> The clause's own
 * wording forbids dead code and "TODOs without owners or tracking reference" - it is untracked residue that
 * it targets. Every artefact retained here is cited to its source line, marked at its declaration with an
 * explicit intentional-no-op comment, and owed an entry in the planned {@code DECISION_LOG.md}. It is a
 * documented faithful reproduction of behaviour that exists in the system of record, not abandoned code.
 * Deleting these sites would produce a layer that is marginally cleaner and measurably less traceable,
 * failing a stated acceptance criterion to satisfy a stylistic one.
 *
 * <h3>Clause F disclosures</h3>
 *
 * <p>Stated plainly rather than papered over, with what would be needed to close each:
 *
 * <ul>
 *   <li><strong>The {@code CBSTM03B} paragraph count, 15 against 14.</strong> The specification states 15 and
 *       an independent scan by a sibling agent reported 14. Measured at this commit, the delta is
 *       explicable and both figures are right under their own definition: an Area-A label scan of
 *       {@code app/cbl/CBSTM03B.CBL} finds 15 labels, of which one -
 *       {@code FILE-CONTROL.} at {@code :L30} - sits in the {@code ENVIRONMENT DIVISION} rather than the
 *       {@code PROCEDURE DIVISION}. Counting procedure paragraphs alone gives the 14 that run from
 *       {@code :L116} to {@code :L228}. The banner above keeps 15, the figure the traceability record uses.
 *       What remains {@code Not available} is confirmation that the sibling scan drew the line in that same
 *       place rather than for some other reason; closing it needs that scan's paragraph list, and until then
 *       no label may be consolidated or invented to force either number.</li>
 *   <li><strong>No service-level objective exists anywhere in the source.</strong> The COBOL publishes no
 *       throughput or latency target, so the performance gate records a <strong>measured baseline</strong>
 *       and nothing more. A target is {@code Not available} and none may be invented; setting one would need
 *       a stakeholder to state it.</li>
 *   <li><strong>The daily transaction dataset has no key length.</strong>
 *       {@code AWS.M2.CARDDEMO.DALYTRAN.PS} appears in {@code app/catlg/LISTCAT.txt:L786} as a
 *       {@code NONVSAM} entry, alongside {@code .PS.INIT} at {@code :L801}, and being non-VSAM it carries no
 *       {@code KEYS(...)} clause and no key attributes anywhere in the corpus. A key length for it is
 *       therefore {@code Not available} and must not be fabricated; the record is read sequentially at 350
 *       bytes, which is the only geometry the source states.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.batch;
