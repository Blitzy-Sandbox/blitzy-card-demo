/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch.jobs
 * Application : CardDemo
 * Type        : Java Package Documentation
 * Function    : Spring Batch Job, Step and Flow topology replacing the
 *               JES2 / JCL / DFSORT / IDCAMS job stream.
 * Source      : app/jcl/POSTTRAN.jcl + app/cbl/CBTRN02C.cbl (731 lines,
 *               27 paragraphs) + app/cbl/CBTRN01C.cbl (491 lines,
 *               19 paragraphs); app/jcl/INTCALC.jcl
 *               + app/cbl/CBACT04C.cbl (652 lines, 23 paragraphs);
 *               app/jcl/COMBTRAN.jcl (52 lines, no COBOL program);
 *               app/jcl/CREASTMT.JCL (97 lines)
 *               + app/cbl/CBSTM03A.CBL (924 lines, 26 paragraphs)
 *               + app/cbl/CBSTM03B.CBL (230 lines, 15 labels);
 *               app/jcl/TRANREPT.jcl + app/proc/TRANREPT.prc
 *               + app/proc/REPROC.prc + app/ctl/REPROCT.ctl
 *               + app/cbl/CBTRN03C.cbl (649 lines, 27 paragraphs)
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
 * Job topology for the CardDemo batch tier: the six Spring Batch job definitions that replace the
 * JES2-scheduled JCL job stream, and nothing else.
 *
 * <p>This package holds <strong>exactly six</strong> types - one per legacy job, plus one orchestrator - and
 * no seventh. It owns which jobs exist, which steps each is built from, what gates the transition between
 * them, and which branches may proceed in parallel. Per-record behaviour belongs to
 * {@code com.cardemo.batch.processors}; the surrounding input and output belong to
 * {@code com.cardemo.batch.readers} and {@code com.cardemo.batch.writers}. Facts that bind the whole layer
 * rather than any one leaf are stated once in {@code com.cardemo.batch}, so what follows documents the jobs.
 *
 * <h2>What it does</h2>
 *
 * <p>Each job carries the verified size of the source it replaces, measured at commit 7756d89 with carriage
 * returns stripped. Paragraph counts include the unlabelled mainline that follows {@code PROCEDURE DIVISION}.
 *
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} - from {@code app/jcl/POSTTRAN.jcl}, whose
 *       {@code //STEP15 EXEC PGM=CBTRN02C} at {@code :L23} names six data DD statements, plus
 *       {@code app/cbl/CBTRN02C.cbl} (731 lines, 27 paragraphs) and {@code app/cbl/CBTRN01C.cbl}
 *       (491 lines, 19 paragraphs).</li>
 *   <li>{@code InterestCalculationJob} - from {@code app/jcl/INTCALC.jcl}, whose
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} at {@code :L22} supplies the date parameter,
 *       plus {@code app/cbl/CBACT04C.cbl} (652 lines, 23 paragraphs).</li>
 *   <li>{@code CombineTransactionsJob} - from {@code app/jcl/COMBTRAN.jcl} (52 lines) and nothing else.</li>
 *   <li>{@code StatementGenerationJob} - from {@code app/jcl/CREASTMT.JCL} (97 lines, five steps) plus
 *       {@code app/cbl/CBSTM03A.CBL} (924 lines, 26 paragraphs) and {@code app/cbl/CBSTM03B.CBL}
 *       (230 lines, 15 labels).</li>
 *   <li>{@code TransactionReportJob} - from the five-member report chain {@code app/jcl/TRANREPT.jcl}
 *       (84 lines), {@code app/proc/TRANREPT.prc} (82 lines), {@code app/proc/REPROC.prc} (32 lines) and
 *       {@code app/ctl/REPROCT.ctl} (15 lines), plus {@code app/cbl/CBTRN03C.cbl} (649 lines,
 *       27 paragraphs).</li>
 *   <li>{@code BatchPipelineOrchestrator} - from the overall JCL job stream. It composes the five jobs above
 *       into one end-to-end pipeline and owns the gating between them.</li>
 * </ul>
 *
 * <h3>Pipeline order</h3>
 *
 * <p>{@code POSTTRAN -> INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT)}. The first three run strictly in
 * sequence. The last two are composed with {@code FlowBuilder.split()} and run in parallel, because the
 * legacy stream imposes no ordering between statement generation and the transaction report - neither reads
 * what the other writes. The parallelism reproduces an absence of ordering in the source; it is not an
 * optimisation layered on top of it.
 *
 * <h3>CBTRN01C is a pre-flight step, never a seventh job</h3>
 *
 * <p>{@code app/cbl/CBTRN01C.cbl} is folded into {@code DailyTransactionPostingJob} as an explicitly
 * labelled <strong>read-only pre-flight step</strong>. Two independent facts force that choice. Its verb
 * inventory across the six {@code SELECT} statements at {@code app/cbl/CBTRN01C.cbl:L29}-{@code :L60} is
 * {@code OPEN} 18, {@code READ} 17, {@code CLOSE} 18 and {@code DISPLAY} 42, with {@code WRITE},
 * {@code REWRITE} and {@code DELETE} all <strong>zero</strong> - it cannot mutate anything. And no member of
 * {@code app/jcl/} executes it, so a standalone job would be an invention rather than a translation. Folding a
 * program into a step is not the same as dropping it, so it still gets its own rows in
 * {@code TRACEABILITY_MATRIX.md}, and it has them: <strong>36 {@code TM-CBTRN01C-*} rows</strong> are mapped
 * there. An earlier revision said the row was "still owed in the planned" matrix; the matrix has since been
 * authored at the repository root and the obligation is discharged, so that claim is withdrawn. Reproduce with
 * {@code grep -c 'TM-CBTRN01C' TRACEABILITY_MATRIX.md}.
 *
 * <h3>CombineTransactionsJob has no COBOL program at all</h3>
 *
 * <p>No program in {@code app/cbl/} implements it. Its logic is entirely DFSORT and IDCAMS control cards, so
 * {@code app/jcl/COMBTRAN.jcl} <strong>is</strong> the Java source of truth rather than a wrapper around one.
 * Two steps: a sort of the concatenated transaction backup and interest-generated generations by transaction
 * identifier ascending, then a bulk load into the transaction table.
 *
 * <h3>Utility programs become library calls, not processes</h3>
 *
 * <ul>
 *   <li>DFSORT sort specifications become in-process ordering, never an external utility. AAP section 0.4.3
 *       nominates "Comparator plus repository ordering" as the replacement, and both halves are used, chosen by
 *       whether the records being ordered are already relational. {@code CombineTransactionsJob} orders object
 *       images with a {@code java.util.Comparator}, because its {@code SORTIN} is two concatenated generations
 *       that no relation holds. {@code TransactionReportJob} orders through the repository, because its
 *       {@code SORTIN} is a verbatim copy of the transaction relation - {@code app/ctl/REPROCT.ctl:L15} is
 *       {@code REPRO} with no selection - so an indexed {@code ORDER BY} produces the same permutation without
 *       holding the population in heap. That second choice is finding <strong>F-012</strong>, where the report
 *       sort materialised the whole generation into a {@code List<byte[]>} and sorted it in memory with no
 *       bound; the ordering is identical and the memory is one page.</li>
 *   <li>IDCAMS {@code REPRO} becomes {@code JdbcTemplate.batchUpdate}.</li>
 *   <li>JCL {@code COND=(0,NE)} step gating becomes a {@code JobExecutionDecider}.</li>
 * </ul>
 *
 * <p><strong>No external sort process is spawned.</strong> {@code Runtime.exec} and {@code ProcessBuilder}
 * are forbidden outright in this package. Shelling out to a sort utility would reintroduce the shell
 * injection surface Rule 1 Clause D names as a risky pattern, and would make the build depend on a binary
 * that is not a pinned dependency.
 *
 * <h3>Three JCL members deliberately have no job here</h3>
 *
 * <ul>
 *   <li>{@code app/jcl/CBADMCDJ.jcl} - {@code //STEP1   EXEC PGM=DFHCSDUP} at {@code :L27} installs the CICS
 *       resource definitions. Superseded by {@code com.cardemo.config.SecurityConfig}, which derives
 *       endpoint authorisation from those same definitions.</li>
 *   <li>{@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} - {@code CEMT SET FIL(...) OPE} and the
 *       matching {@code CLO} over exactly five files at {@code :L26}-{@code :L30} in each. Superseded by
 *       {@code com.cardemo.observability.HealthIndicators}: availability is now reported continuously rather
 *       than toggled by an operator job.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>The full gate is {@code ./mvnw -q verify}. In practice this tier is built with
 * {@code ./mvnw -B -ntp clean verify}, where the flag skips only the OWASP scan,
 * which needs the vulnerability feed; drop it when the scan is wanted. Compile alone with
 * {@code ./mvnw -B -ntp -DskipTests compile}. Compilation runs {@code -Xlint:all -Werror} with
 * {@code failOnWarning} at release 25, and {@code verify} additionally runs the {@code doclint-gate}
 * execution of {@code maven-javadoc-plugin} with {@code doclint} set to {@code all}, {@code show} at
 * {@code private} and {@code failOnWarnings} true - so a malformed or incomplete doc comment in this package
 * fails the build exactly as a compiler warning does.
 *
 * <p>Where no JDK 25 toolchain is on the path, the same compile runs inside the pinned image:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile}.
 * Prerequisites are stated as capabilities rather than host paths, so nothing here assumes a given machine.
 *
 * <p><strong>No job in this package auto-runs at startup</strong>, because {@code spring.batch.job.enabled}
 * is {@code false}. Launching is always explicit, by one of exactly two routes: programmatically through
 * {@code BatchPipelineOrchestrator}, or from the SQS listener that replaces the JES2 internal reader. That
 * queue is the translation of {@code DEFINE TDQUEUE(JOBS)} at
 * {@code app/csd/CARDDEMO.CSD:L499}-{@code :L505}, whose {@code TYPE(EXTRA) DDNAME(INREADER)} at
 * {@code :L501}, {@code RECORDSIZE(80)} at {@code :L502} and
 * {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)} at {@code :L503} together fix the
 * 80-byte fixed-format parameter card that the message body reproduces.
 *
 * <p>Tests occupy three locations:
 *
 * <ul>
 *   <li>{@code src/test/java/com/cardemo/unit/batch} - Surefire, no container required.</li>
 *   <li>{@code src/test/java/com/cardemo/integration/batch} - Failsafe, against Testcontainers
 *       PostgreSQL 16.</li>
 *   <li>{@code src/test/java/com/cardemo/integration/aws} - Failsafe, against LocalStack.</li>
 * </ul>
 *
 * <p><strong>Named test obligation.</strong> Rule 1 Clause B requires tests for core logic, and decider
 * behaviour is core logic here: the mapping of return codes 0, 4, 8 and 12 onto completed,
 * completed-with-rejects, failed and abend outcomes must be asserted for all four values, not only for the
 * success path. Return code 4 in particular has to be asserted as a <em>non-failure</em>.
 *
 * <p>The local topology is one command: {@code docker compose up} brings up PostgreSQL 16, LocalStack,
 * Jaeger, Prometheus and Grafana.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every key below is owned by {@code src/main/resources/application.yml} and its three profile documents.
 * Configuration is cited by key, and credentials by variable name only - never by value.
 *
 * <h3>Framework wiring</h3>
 *
 * <ul>
 *   <li>{@code spring.batch.job.enabled} - {@code false} in the base profile and again in {@code prod}.</li>
 *   <li>{@code spring.batch.jdbc.initialize-schema} - {@code never} in the base profile and in {@code prod},
 *       {@code always} in {@code local} and {@code test}. The {@code BATCH_*} metadata tables come from the
 *       framework's own PostgreSQL script. They are <strong>never</strong> a fourth Flyway migration and
 *       never extra tables in {@code V1__create_schema.sql}, which creates exactly 11 tables and is asserted
 *       at that count by a validation gate.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} - {@code validate}, so any divergence between the entity
 *       mapping and the Flyway schema fails context startup outright instead of being silently repaired.</li>
 * </ul>
 *
 * <h3>Job parameters</h3>
 *
 * <ul>
 *   <li><strong>The interest date parameter is a ten-character value, not an ISO date.</strong> Eight date
 *       digits followed by two zeros, with no separators: {@code PARM='2022071800'} at
 *       {@code app/jcl/INTCALC.jcl:L22}, received into {@code 05  PARM-DATE           PIC X(10)} at
 *       {@code app/cbl/CBACT04C.cbl:L178}. It is concatenated into generated transaction identifiers, so
 *       reformatting it as a dash-separated date changes the keys that get written.</li>
 *   <li><strong>The report date range is inclusive at both ends.</strong>
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} at
 *       {@code app/proc/TRANREPT.prc:L45}-{@code :L46}, over {@code PARM-START-DATE,C'2022-01-01'} at
 *       {@code :L41} and {@code PARM-END-DATE,C'2022-07-06'} at {@code :L42}. Both bounds arrive in the SQS
 *       message body, reproducing the legacy 80-byte {@code DATEPARM} card.</li>
 * </ul>
 *
 * <h3>Chunk, window and job-identity keys</h3>
 *
 * <p>These bind from the {@code carddemo.batch.*} namespace, each carrying a documented default:
 * {@code carddemo.batch.chunk-size} and {@code carddemo.batch.dataset-window-size}, both {@code 100}; the
 * per-reader page sizes {@code carddemo.batch.account-reader.page-size},
 * {@code carddemo.batch.card-reader.page-size},
 * {@code carddemo.batch.card-cross-reference-reader.page-size},
 * {@code carddemo.batch.customer-reader.page-size},
 * {@code carddemo.batch.daily-transaction-reader.page-size} and
 * {@code carddemo.batch.transaction-backup-reader.page-size}, all {@code 100}; and the job identities
 * {@code carddemo.batch.jobs.posttran.name} through {@code carddemo.batch.jobs.tranrept.name}, defaulting to
 * {@code POSTTRAN}, {@code INTCALC}, {@code COMBTRAN}, {@code CREASTMT} and {@code TRANREPT} - all five
 * bound, each by its own job class - together with {@code carddemo.batch.jobs.pipeline.name} at
 * {@code CARDDEMO-PIPELINE} for the orchestrator, which is not one of the five JCL members. That namespace
 * is the one authoritative spelling for every job name, and it carries nothing besides them: a per-job
 * {@code .enabled} flag and a {@code carddemo.batch.jobs.creastmt.steps} count were declared and bound by
 * nothing, so they are withdrawn rather than given a binder. The statement job has five steps because
 * {@code app/jcl/CREASTMT.JCL} has five, and the five stages are constructor dependencies of
 * {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator}, so neither value could have been settable.
 * The step count is asserted against {@code StatementGenerationJob.STEP_COUNT}, and finding CFG-002 is
 * the record of the withdrawal.
 *
 * <p>Job <em>tuning</em> lives one level up, under {@code carddemo.batch.<id>.*} rather than under
 * {@code jobs}: the per-job keys {@code carddemo.batch.posttran.chunk-size}, {@code .intcalc.chunk-size},
 * {@code .tranrept.chunk-size}, {@code .combtran.chunk-size} and {@code .creastmt.chunk-size}, each
 * {@code 100} and each resolving through {@code carddemo.batch.chunk-size} before its own literal. Two run
 * bounds, {@code carddemo.batch.combtran.max-records-per-run} and
 * {@code carddemo.batch.creastmt.max-work-records}, were declared alongside them and are withdrawn: each was
 * read by a constant that no longer exists, because the combine sort streams to a staging file instead of
 * accumulating the generation and the statement work file's size is reported into the job execution context
 * instead of being capped. Whether a job runs is governed by {@code spring.batch.job.enabled} plus an
 * explicit launch, never by a key in either namespace.
 *
 * <h3>Object storage and queue</h3>
 *
 * <p>The namespace carries the {@code .aws.} segment - {@code carddemo.aws.s3.*} and
 * {@code carddemo.aws.sqs.*}, not {@code carddemo.s3.*}. Three buckets and one queue, each resolved from an
 * environment variable with <strong>no committed default</strong>:
 *
 * <ul>
 *   <li>{@code carddemo.aws.s3.batch-input-bucket} from {@code CARDDEMO_S3_BATCH_INPUT_BUCKET}.</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} from {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}.</li>
 *   <li>{@code carddemo.aws.s3.statements-bucket} from {@code CARDDEMO_S3_STATEMENTS_BUCKET}.</li>
 *   <li>{@code carddemo.aws.sqs.report-queue} from {@code CARDDEMO_SQS_REPORT_QUEUE}. Its logical name is
 *       {@code carddemo-report-jobs} and it is FIFO - see
 *       {@code carddemo.aws.sqs.report-queue-logical-name} and
 *       {@code carddemo.aws.sqs.report-message-group-id}.</li>
 * </ul>
 *
 * <p>Note the {@code BATCH_} segment in the first two variable names; a name without it resolves to nothing.
 * Because these properties carry no default, an unset variable aborts context refresh before a job bean is
 * constructed, which is the intended fail-fast behaviour rather than a defect to paper over with a literal.
 * Values arrive only as resolved Spring properties, never by reading the process environment directly.
 *
 * <p><strong>Least privilege.</strong> {@code spring.cloud.aws.s3.endpoint} and its SQS and SNS counterparts
 * are declared in every profile as the placeholder {@code AWS_ENDPOINT_URL}, and only
 * {@code application-local.yml} embeds a default at all - the LocalStack address on port 4566. The base,
 * {@code test} and {@code prod} documents supply no default, so no committed endpoint could reach a live
 * account and <strong>zero live credentials exist anywhere in the tree</strong>.
 *
 * <h3>Byte-exact record geometry</h3>
 *
 * <p>These lengths are contracts that the parity comparison asserts, not incidental buffer sizes:
 *
 * <ul>
 *   <li><strong>350</strong> - the daily transaction input and the transaction record.</li>
 *   <li><strong>430</strong> - the reject record: exactly 350 data bytes plus an 80-byte trailer carrying a
 *       four-digit reason code and a 76-character description. Declared as
 *       {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl:L36}.</li>
 *   <li><strong>133</strong> - the report line, {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} at
 *       {@code app/proc/TRANREPT.prc:L76}.</li>
 *   <li><strong>80</strong> - the statement text output, {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at
 *       {@code app/jcl/CREASTMT.JCL:L89}.</li>
 *   <li><strong>100</strong> - the statement HTML output, {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at
 *       {@code app/jcl/CREASTMT.JCL:L94}.</li>
 *   <li><strong>80</strong> - the queue parameter card, {@code RECORDSIZE(80)} at
 *       {@code app/csd/CARDDEMO.CSD:L502}.</li>
 *   <li><strong>350 with a 32-byte key</strong> - the statement work cluster, {@code KEYS(32 0)} at
 *       {@code app/jcl/CREASTMT.JCL:L30} and {@code RECORDSIZE(350 350)} at {@code :L32}.</li>
 * </ul>
 *
 * <h3>Generation data groups become deterministic object keys</h3>
 *
 * <p>A {@code (+1)} write becomes a new object under a monotonically increasing timestamp or job-instance
 * prefix over a <strong>versioned</strong> bucket; a {@code (0)} read becomes the lexicographically greatest
 * existing prefix. Six of the seven legacy bases map to prefixes under
 * {@code carddemo.aws.s3.gdg-prefixes.*}: {@code transact-bkup}, {@code transact-daly}, {@code tranrept},
 * {@code systran} and {@code transact-combined} from {@code app/jcl/DEFGDGB.jcl:L24}-{@code :L59}, and
 * {@code daly-rejs} from {@code app/jcl/DALYREJS.jcl:L24}-{@code :L28}. <strong>The seventh,
 * {@code AWS.M2.CARDDEMO.TCATBALF.BKUP} of {@code app/jcl/DEFGDGB.jcl:L43}, has no profile key, because no
 * class in this package writes it.</strong> Its producer is {@code app/jcl/PRTCATBL.jcl} - three steps and no
 * COBOL program, REPROing the category-balance cluster into {@code TCATBALF.BKUP(+1)} at {@code :L29}-{@code
 * :L39} and sorting that generation into {@code TCATBALF.REPT} at {@code :L43}-{@code :L63} - and neither
 * output is produced here. The base is still provisioned in the object store by
 * {@code localstack-init/init-aws.sh} under the {@code gdg/tcatbalf-bkup} prefix, so the seven-base layout is
 * complete; what is absent is a key for a prefix no application code addresses.
 *
 * <p><strong>Within a single job, a {@code (+1)} written by an earlier step is re-read as {@code (+1)} by a
 * later step.</strong> {@code DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} appears at
 * {@code app/jcl/COMBTRAN.jcl:L43}-{@code :L44}, and {@code TRANSACT.BKUP(+1)} at
 * {@code app/proc/TRANREPT.prc:L37} is read alongside {@code TRANSACT.DALY(+1)} at {@code :L64}. The concrete
 * key that was created is therefore published into the job or step execution context and read back from it,
 * never re-resolved as the latest object mid-job: re-resolution is a different operation, and under
 * concurrency a different object.
 *
 * <p>Retention is <strong>documented, not enforced</strong>. Object versioning supersedes generation limits
 * and this package applies no lifecycle rule. The source disagrees with itself about the report group -
 * {@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:L38} against {@code LIMIT(10)} at
 * {@code app/jcl/REPTFILE.jcl:L27} - and that conflict is <strong>resolved to 10</strong>, recorded as
 * {@code carddemo.aws.s3.gdg-retention-generations}. It is the only legacy inconsistency this migration
 * resolves rather than reproduces, because a single lifecycle value has to be chosen.
 *
 * <h3>Observability</h3>
 *
 * <p>Batch log events carry the <strong>job instance identifier</strong> in the mapped diagnostic context as
 * {@code jobInstanceId}, alongside {@code correlationId}, {@code traceId} and {@code spanId}. That is what
 * makes a per-run object prefix and the log lines describing it correlatable after the fact; the legacy tier
 * had no instrumentation beyond {@code DISPLAY} to SYSOUT.
 *
 * <p>{@code com.cardemo.observability.MetricsConfig} owns <strong>exactly four</strong> Micrometer counters
 * and this package adds no fifth instrument: {@code carddemo.batch.records.processed},
 * {@code carddemo.batch.records.rejected} tagged by reject code under the tag key {@code reject.code},
 * {@code carddemo.auth.attempts} and {@code carddemo.transaction.amount.total}. The tag key is spelled with
 * a dot - {@code reject-code} is not registered, and a dashboard querying that spelling returns nothing.
 *
 * <p><strong>Which jobs in this package advance the processed counter, and which must not.</strong>
 * {@code carddemo.batch.records.processed} is defined as the {@code DALYTRAN} population that
 * {@code app/cbl/CBTRN02C.cbl:L236} displays as {@code TRANSACTIONS PROCESSED}, so only
 * {@code DailyTransactionPostingJob} contributes here - once per rejected record, with
 * {@code com.cardemo.batch.writers.TransactionWriter} supplying the posted ones, the two together being
 * exactly {@code WS-TRANSACTION-COUNT}. {@code CombineTransactionsJob} and {@code TransactionReportJob} both
 * used to increment it as well, which is finding <strong>F-010</strong>: they read rows that population had
 * already counted, and because the counter is untagged there was no dimension along which a query could
 * subtract the duplicates back out. Both now publish their per-run volumes as execution-context entries and
 * neither takes {@code MetricsConfig} at all, so the constructor signature is the guard rather than a
 * convention. {@code StatementGenerationJob} and {@code InterestCalculationJob} likewise hold no reference to
 * it.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>An exit status of 4 is treated as a failure</dt>
 *   <dd><strong>Return code 4 is not a failure.</strong> It means the reject count exceeded zero and nothing
 *       else: {@code IF WS-REJECT-COUNT > 0} then {@code MOVE 4 TO RETURN-CODE} at
 *       {@code app/cbl/CBTRN02C.cbl:L229}-{@code :L231}. That statement at {@code :L230} is the only
 *       numeric-literal {@code RETURN-CODE} assignment in the entire 19,254-line corpus, so no other
 *       condition may produce a 4. Return code 8 is a genuine failure. Return code 12 is an abend, paired
 *       with abend code 999 - {@code MOVE 999 TO ABCODE} then {@code CALL 'CEE3ABD'} in
 *       {@code 9999-ABEND-PROGRAM} at {@code :L707}-{@code :L711} - surfacing as
 *       {@code com.cardemo.exception.FatalProcessingException}. <strong>Return code 4 and return code 12 are
 *       independent paths</strong>: reject codes are business outcomes that drive {@code ExitStatus} and are
 *       never thrown, so a run may complete with 4 having raised nothing at all.</dd>
 *
 *   <dt>InterestCalculationJob abends naming the disclosure group</dt>
 *   <dd>The default-rate retry accepts only {@code '00'} - {@code IF  DISCGRP-STATUS  = '00'} at
 *       {@code app/cbl/CBACT04C.cbl:L446} - so a missing {@code DEFAULT} row is fatal rather than a
 *       fallback of last resort. Remediation: confirm {@code V3__seed_data.sql} loaded all 17
 *       {@code DEFAULT} rows from {@code app/data/ASCII/discgrp.txt}, which holds 17 of its 51 rows under
 *       that group identifier.</dd>
 *
 *   <dt>InterestCalculationJob abends on a card cross-reference lookup</dt>
 *   <dd>Expected, and <strong>never a skip</strong>. {@code 1110-GET-XREF-DATA} at
 *       {@code app/cbl/CBACT04C.cbl:L393}-{@code :L413} prints a friendly message and then falls into the
 *       standard {@code '00'} guard, reaching {@code PERFORM 9999-ABEND-PROGRAM} at {@code :L411}. An empty
 *       lookup is a fatal error; skipping the record would silently under-report interest.</dd>
 *
 *   <dt>The combine load fails on a duplicate transaction identifier</dt>
 *   <dd>Correct behaviour. It surfaces as {@code com.cardemo.exception.DuplicateRecordException} with a
 *       failed exit status - never a silent upsert, never a database sequence, never a retry. This is where
 *       the interest job's duplicate exposure materialises: {@code InterestCalculationJob} writes a fresh
 *       sequential generation and performs no duplicate detection of its own, so a repeated ten-character
 *       date parameter yields colliding identifiers that only the load can detect.</dd>
 *
 *   <dt>Statement generation appears to have no source</dt>
 *   <dd>A case-sensitive {@code *.jcl} glob deleted it from the build. {@code app/jcl/} holds
 *       <strong>29</strong> members: 28 with a lowercase {@code .jcl} extension and {@code CREASTMT.JCL}
 *       with an uppercase one, and that member is the sole source for {@code StatementGenerationJob}.
 *       {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL} and {@code app/cpy/COSTM01.CPY} are
 *       uppercase too - and that copybook member is named {@code COSTM01}, with no trailing letter. Those
 *       three plus {@code CREASTMT.JCL} are also CRLF-terminated, so strip carriage returns on read.</dd>
 *
 *   <dt>A container-dependent validation gate cannot be evidenced</dt>
 *   <dd>Gates 1, 4 and 8 require a container runtime, for Testcontainers and for {@code docker compose}.
 *       Where none is reachable the evidence artefact records the literal {@code Not available} together
 *       with the prerequisite - a running Docker daemon with an accessible socket - and
 *       <strong>never a fabricated pass</strong>, per Rule 1 Clause F. The unit tier and Gates 2, 6 and 7
 *       need no container and are therefore the first evidence produced.</dd>
 * </dl>
 *
 * <h3>Artefacts that look like defects and are not</h3>
 *
 * <p>Four constructs are reproduced deliberately. Each is cited here at its own locator, marked intentional
 * at its site, and tracked for the {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}. Only
 * the first belongs to the bounded retained-for-parity register enumerated in {@code com.cardemo}; the other
 * three are documented source behaviour at their own locators rather than register entries:
 *
 * <ul>
 *   <li>{@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518}-{@code :L520} is empty apart from a
 *       comment reading that it is to be implemented, yet it is genuinely <strong>reachable</strong>,
 *       performed at {@code :L216}. Both the call site and the empty body are retained.</li>
 *   <li>The apparent final flush at {@code app/cbl/CBACT04C.cbl:L219}-{@code :L220} is
 *       <strong>unreachable</strong>. {@code END-OF-FILE} is {@code PIC X(01) VALUE 'N'} at {@code :L137}
 *       and only ever holds {@code 'N'} or {@code 'Y'}; the loop guard
 *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} admits the body only while the field is not
 *       {@code 'Y'}, so the test at {@code :L189} is always true and its {@code ELSE} arm is never taken.
 *       The last account's accumulated interest is consequently never flushed in the source.
 *       <strong>Do not add a final flush</strong> to correct it: that would change output the parity
 *       comparison is measured against.</li>
 *   <li>{@code MOVE 1 TO CR-JMP} at {@code app/cbl/CBSTM03A.CBL:L324} is redundant, the index being
 *       re-initialised by the loop that follows it.</li>
 *   <li>{@code EXIT.} at {@code app/cbl/CBSTM03A.CBL:L816} is <strong>unreachable</strong>, following the
 *       unconditional {@code GO TO 1000-MAINLINE.} at {@code :L815}.</li>
 * </ul>
 *
 * <p><strong>The one documented conflict.</strong> Rule 1 Clause B forbids dead code; behavioural parity
 * requires preserving these no-ops - two of them unreachable - so the paragraph map stays mechanically
 * provable and Gate 7 can verify it. <strong>Parity governs.</strong> The clause forbids <em>untracked</em>
 * dead code and deferred work without an owner or tracking reference, and every artefact above is cited at
 * its locator, marked intentional where it appears, and tracked for the {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}. Deleting a call site to satisfy a stylistic rule would fail a stated
 * acceptance criterion, which is the worse trade.
 *
 * <h3>Legacy job-control defects: logged, not fixed</h3>
 *
 * <ul>
 *   <li>The {@code STMTFILE} DD continuation at {@code app/jcl/CREASTMT.JCL:L90} is
 *       <strong>corrupted</strong> - a {@code SPACE=} parameter with fragments of a {@code DCB=} clause and
 *       of a dataset name overwritten into it.</li>
 *   <li>The {@code HTMLFILE} record length disagrees with itself: {@code LRECL=80} at
 *       {@code app/jcl/CREASTMT.JCL:L69} in the pre-delete step against {@code LRECL=100} at {@code :L94}
 *       in the execution step. <strong>100 is correct</strong>, confirmed independently by
 *       {@code 05  HTML-FIXED-LN        PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149}.</li>
 *   <li>The step name {@code STEP05R} is <strong>declared twice</strong> within one job, at
 *       {@code app/jcl/TRANREPT.jcl:L23} and again at {@code :L37}. That matters rather than being merely
 *       untidy: the online submission deck emits a step-qualified override,
 *       {@code "//STEP05R.SYMNAMES DD *"} at {@code app/cbl/CORPT00C.cbl:L98}, whose target is therefore
 *       ambiguous.</li>
 * </ul>
 *
 * <h3>Two labelled deviations</h3>
 *
 * <p>These are behavioural improvements rather than parity, and are labelled as such here rather than
 * presented as equivalence. Each is owed an entry in the {@code DECISION_LOG.md} saying so:
 *
 * <ul>
 *   <li><strong>Atomicity.</strong> {@code app/cbl/CBTRN02C.cbl} commits the transaction-category-balance
 *       upsert, the account update and the transaction insert as three independent commits.
 *       {@code DailyTransactionPostingJob} makes them one transactional unit, closing the legacy hazard in
 *       which a rewrite failure leaves an orphaned category-balance row and an orphaned transaction row.</li>
 *   <li><strong>Capacity.</strong> {@code app/cbl/CBSTM03A.CBL} holds its working set in a fixed table of 51
 *       card entries by 10 transactions each - a hard ceiling of 510 transactions per run, with both indices
 *       incremented under no bounds check at all. {@code StatementGenerationJob} streams instead, removing a
 *       silent overrun. The legacy ceiling is stated here as the historical capacity limit and owed a row in
 *       the {@code TRACEABILITY_MATRIX.md}. <strong>No authored ceiling replaces it.</strong> Two did - a
 *       configured run bound and a per-card-group bound - and finding BAT-002 removed both, because a refusal
 *       at an invented threshold is a business rule the corpus does not contain. Run size is bounded by the
 *       input: every stage streams one record at a time.</li>
 * </ul>
 *
 * <h2>Package-level constraints</h2>
 *
 * <ul>
 *   <li><strong>Exactly seven files.</strong> The six job types above plus this document. A seventh job type
 *       is forbidden, as is any listener, tasklet-only helper, partitioner, scheduler, mapper, abstract base
 *       or template-method class, helper, utility or constants file - the template-method duty is discharged
 *       by the framework itself. No README or Markdown file belongs here; Rule 1 Clause E is satisfied by
 *       this docstring.</li>
 *   <li><strong>Topology only.</strong> Per-record bodies, readers and writers live in their own
 *       packages.</li>
 *   <li><strong>No external process and no unmanaged input.</strong> The process-spawning prohibition stated
 *       above is absolute, and so is any direct JVM termination call, which would bypass
 *       {@code ExitStatus}. Job parameters and properties are validated at the boundary, with null and empty
 *       handled explicitly rather than defaulted silently.</li>
 *   <li><strong>The framework's batch auto-configuration is left in place.</strong> The opt-in annotation
 *       that would switch it off is deliberately not declared anywhere, because it is what supplies the job
 *       repository this package relies on.</li>
 *   <li><strong>No {@code COND} gate reimplemented as an exception, and no reject outcome as a
 *       throw.</strong></li>
 *   <li><strong>The legacy corpus is frozen.</strong> Nothing under {@code app/} or {@code samples/} is
 *       created, edited, moved, renamed, reformatted or deleted; those trees are simultaneously the parity
 *       oracle, the field-contract source and the traceability anchor, and they lose all three roles the
 *       moment one is edited. {@code app/data/EBCDIC/} is never read or parsed.</li>
 * </ul>
 *
 * <p>Conventions here are <strong>established rather than inherited</strong>: no formatter or linter
 * configuration existed at commit 7756d89 and the tree held zero {@code .java} files, so Rule 1 Clause C's
 * "if present" condition is not triggered for tooling. What <em>is</em> inherited is the Apache-2.0
 * provenance banner above, universal across the legacy corpus, and the guidance in
 * {@code CONTRIBUTING.md:L33} to focus a change rather than reformat around it, with {@code :L34} requiring
 * that local tests pass.
 */

package com.cardemo.batch.jobs;
