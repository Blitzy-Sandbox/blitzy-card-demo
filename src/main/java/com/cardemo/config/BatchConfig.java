/*
 * ******************************************************************
 * Program     : BatchConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (batch layer)
 * Function    : Registers the batch collaborators that cannot register
 *               themselves, and documents the topology of the five stage
 *               pipeline the job classes declare. The bean groups are the
 *               four repository backed dataset bindings that stand in for
 *               the CBSTM03B file access subprogram's four DD names, the
 *               framework metadata initialiser and its readiness proof, and
 *               the queue listener that replaces the JES2 internal reader.
 *               No listener bean and no runner: the six Job beans, the step
 *               scoped report processor, the chunk sizes and the
 *               JobExecutionDecider are NOT registered here either - see the
 *               class documentation for where each one lives and why.
 * Source      : app/jcl/POSTTRAN.jcl (45 lines; CBTRN02C at :L23 with no
 *                 COND, DALYREJS LRECL=430 at :L34-L38)
 *               + app/jcl/INTCALC.jcl (44 lines; CBACT04C at :L22 with
 *                 PARM='2022071800', SYSTRAN LRECL=350 at :L37-L41)
 *               + app/jcl/COMBTRAN.jcl (52 lines; SORT at :L22 over a
 *                 concatenated SORTIN, then IDCAMS REPRO at :L41-L48)
 *               + app/jcl/CREASTMT.JCL (97 lines, CRLF; 5 steps, the only
 *                 three COND=(0,NE) gates in the corpus at :L56, :L66 and
 *                 :L79, KEYS(32 0) at :L30, OUTREC projection at :L54)
 *               + app/jcl/TRANREPT.jcl (84 lines) + app/proc/TRANREPT.prc
 *                 (82 lines) + app/proc/REPROC.prc (32 lines)
 *               + app/ctl/REPROCT.ctl (15 lines; one REPRO control card)
 *               + app/cbl/CBTRN02C.cbl:L227-L231 (RC 4 iff the reject
 *                 count exceeds zero) and :L707-L711 (abend code 999)
 *               + app/cbl/CBTRN01C.cbl:L29-L58 (six SELECT statements, no
 *                 WRITE, REWRITE or DELETE anywhere: a step, not a job)
 *               + app/cbl/CBACT04C.cbl:L188-L222 (the control break and
 *                 its end of file flush) and :L518-L520 with :L216 (the
 *                 reachable empty paragraph the topology must still reach)
 *               + app/cbl/CBTRN03C.cbl:L127-L137 (WS-REPORT-VARS, the six
 *                 per-run state items that forbid a singleton)
 *               + app/cbl/CBSTM03B.CBL:L58-L97 (the four DD names, their
 *                 access modes, key widths and FILE STATUS groups)
 *               + app/cbl/CBSTM03A.CBL:L71-L83 (the CALL contract whose
 *                 WS-M03B-FLDT carries the record image) and :L225-L233
 *                 (the 51 by 10 table whose 510 ceiling streaming removes)
 *               + app/cpy/CSMSG02Y.cpy:L21-L29, internally titled
 *                 CABENDD.CPY (the four abend work areas, carrying the
 *                 COBOL sequence numbers 001200 through 002000)
 *               + app/cpy/CVACT01Y.cpy + app/cpy/CVCUS01Y.cpy
 *                 + app/cpy/CVACT03Y.cpy + app/cpy/COSTM01.CPY (the four
 *                 record layouts the bindings render)
 *               @ 7756d89
 * Replaces    : JES2 initiators, the JCL EXEC PGM step definitions of the
 *               batch stream, DFSORT, IDCAMS REPRO, COND=(0,NE) step
 *               gating and the static CALL 'CBSTM03B' linkage
 * Note        : app/jcl/CREASTMT.JCL uses an UPPERCASE extension and is the
 *               only uppercase member of app/jcl - a case sensitive *.jcl
 *               glob drops it and statement generation disappears from
 *               scope entirely. Every pattern over that directory must be
 *               matched case insensitively.
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
package com.cardemo.config;

import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.shared.FileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import io.awspring.cloud.s3.S3Operations;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.repository.dao.AbstractJdbcBatchMetadataDao;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.boot.autoconfigure.batch.BatchDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.batch.BatchProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

/**
 * Wiring for the batch tier: the four dataset bindings that give the {@code CBSTM03B} translation something
 * to read, and the job instance diagnostic context that batch events are labelled with.
 *
 * <p>This class is the configuration seam of the five stage pipeline
 * {@code POSTTRAN -> INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT)}. It owns the shared infrastructure the
 * jobs consume; it deliberately owns none of their identity. The section below on what this class does
 * <strong>not</strong> declare is as load bearing as the one on what it does, because
 * {@code spring.main.allow-bean-definition-overriding} is {@code false} and a bean declared in two places is
 * a startup failure rather than a redundancy.
 *
 * <h2>What it does</h2>
 *
 * <p>Two groups of beans, both of which exist because the collaborators concerned cannot correctly register
 * themselves.
 *
 * <ol>
 *   <li><strong>{@code TransactionReportProcessor} is deliberately NOT declared here, and is not a bean at
 *       all.</strong> It carries the six {@code WS-REPORT-VARS} items of
 *       {@code app/cbl/CBTRN03C.cbl:L127-L137} - the line counter, three running totals, the control-break
 *       card number and the first-time flag - so a singleton instance would carry one report's pagination and
 *       totals into the next, and it needs the two reporting dates that
 *       {@code app/proc/TRANREPT.prc:L60-L70} supplies through {@code SYMNAMES}. Both requirements are met by
 *       {@code com.cardemo.batch.jobs.TransactionReportJob}, which constructs one instance inside each
 *       STEP10R tasklet body and passes the two dates it read from the job parameters. That job is the type's
 *       sole owner and the class carries no {@code @Component} and no {@code @StepScope} - finding
 *       <strong>F-008</strong>: annotating the class as a step-scoped component publishes a bean definition
 *       no production path resolves. A {@code @Bean} factory here would derive the
 *       bean name {@code transactionReportProcessor}, and under
 *       {@code spring.main.allow-bean-definition-overriding: false} that collides with any component
 *       definition; the collision is gone with the annotation, but the factory is still not reinstated,
 *       because it would move the constructor's {@code DATEPARM} validation behind a scoped proxy where a
 *       rejected pair surfaces as a {@code BeanCreationException} rather than as the step's own
 *       {@code FatalProcessingException}. The processor is directly constructible from a unit test for the
 *       same reason it is directly constructed in production: its constructor takes plain values.</li>
 *   <li><strong>The four {@link FileService.Dataset} bindings.</strong> {@code CBSTM03A} reaches all of its
 *       input through {@code CALL 'CBSTM03B' USING WS-M03B-AREA} with a DD-name selector
 *       ({@code app/cbl/CBSTM03A.CBL:L71-L83}), and {@code CBSTM03B} declares exactly four files
 *       ({@code app/cbl/CBSTM03B.CBL:L58-L78}). {@code FileService} is the translation of the subprogram
 *       and owns the dispatch, the status registers and the guards; it deliberately owns no data access.
 *       These four beans are the data access, one per DD, each backed by the repository that replaced the
 *       corresponding VSAM cluster. Without them the service dispatches into nothing and the statement
 *       processor fails on its first operation.</li>
 *   </ol>
 *
 * <p><strong>There is no listener bean of any kind in this class</strong> - no
 * {@code JobExecutionListener}, {@code StepExecutionListener}, {@code ItemReadListener},
 * {@code SkipListener} or {@code ChunkListener} - and that is a decision rather than an omission. A listener
 * bean is never applied to a job implicitly: neither the batch framework nor the Boot auto-configuration
 * collects them, so one takes effect only where a job builder registers it. A {@code JobExecutionListener}
 * declared here and registered nowhere stood in this file and was removed as finding M-01, because a bean
 * that appears to guarantee the {@code jobInstanceId} on batch events while never running is worse than no
 * bean at all. Each of the six job classes registers its own listener on its own job, and each establishes
 * the diagnostic context through
 * {@link com.cardemo.observability.CorrelationIdFilter#enterBatchScope(long, String)} so that the key names
 * and the park-and-restore lifecycle still have exactly one definition.
 * The same removal is recorded as finding CFG-003, severity Medium, from the configuration review: a bean
 * nothing registers is dead configuration that reads as though it were the contract, which is worse than
 * its absence. The key itself, and the log-injection guard on its value, remain owned by
 * {@code com.cardemo.observability.CorrelationIdFilter}.
 *
 * <h2>What this class does NOT declare, and where each one lives</h2>
 *
 * <p>Five things a reader might expect here are declared elsewhere, and each absence is a decision rather
 * than an omission.
 *
 * <dl>
 *   <dt>The six {@code Job} beans</dt>
 *   <dd>{@code com.cardemo.batch.jobs.DailyTransactionPostingJob},
 *       {@code com.cardemo.batch.jobs.InterestCalculationJob},
 *       {@code com.cardemo.batch.jobs.CombineTransactionsJob},
 *       {@code com.cardemo.batch.jobs.StatementGenerationJob},
 *       {@code com.cardemo.batch.jobs.TransactionReportJob} and
 *       {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} are each a {@code @Configuration} class
 *       declaring its own {@code Job}, and each is the designated definition site for it. That is why this
 *       class declares none.
 *       <p><strong>Conflict resolution, stated so it is not rediscovered by debugging:</strong>
 *       {@code spring.main.allow-bean-definition-overriding} is {@code false}, so a duplicate {@code Job}
 *       bean definition throws {@code BeanDefinitionOverrideException} at startup rather than silently
 *       shadowing. If that happens, the correct remedy is to remove the duplicate from {@code BatchConfig},
 *       <strong>not</strong> from the job class, because the job classes are the designated definition
 *       sites.</p></dd>
 *   <dt>The step topology and the chunk sizes</dt>
 *   <dd>Each job declares its own {@code Step} and {@code Flow} beans under its own bean name constants, and
 *       binds its own chunk size through a two level fallback described under the configuration table below.
 *       A chunk size is a commit interval and a tunable, never a parity contract: no program in
 *       {@code app/cbl} commits per record.</dd>
 *   <dt>The {@code JobExecutionDecider}</dt>
 *   <dd>Declared by the jobs that need one, for the reason developed under the gating section below: the
 *       source's step gating is not where a reader would guess.</dd>
 *   <dt>The per-execution report processor and its backup reader</dt>
 *   <dd>See the block comment before the bean methods. {@code TransactionReportProcessor} and
 *       {@code TransactionBackupReader} are not beans: {@code TransactionReportJob} constructs both, once
 *       per step execution, and is their single owner.</dd>
 *   <dt>Cloud clients, persistence and log masking</dt>
 *   <dd>{@code com.cardemo.config.AwsConfig} constructs the object store, queue and topic clients and owns
 *       every bucket, queue and topic binding. {@code com.cardemo.config.JpaConfig} owns the persistence and
 *       migration settings. {@code com.cardemo.observability.MetricsConfig} owns the four named counters.
 *       {@code src/main/resources/logback-spring.xml} owns secret masking, profile invariantly. None of the
 *       four is redeclared here, and no dependency is added here: version pinning is discharged by the root
 *       {@code pom.xml}.</dd>
 *   </dl>
 *
 * <h2>Exactly six jobs, and never a seventh</h2>
 *
 * <p>The pipeline is {@code POSTTRAN}, then {@code INTCALC}, then {@code COMBTRAN}, then the two independent
 * terminal branches {@code CREASTMT} and {@code TRANREPT} composed with a split, plus the orchestrator that
 * composes the whole stream. Two groups of programs look like candidates for a job of their own and are not.
 *
 * <ul>
 *   <li><strong>{@code CBTRN01C} is a step, never a job.</strong> {@code app/cbl/CBTRN01C.cbl} is 491 lines
 *       and read only: it declares six {@code SELECT} statements at {@code :L29-L58} and its complete verb
 *       inventory is {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY}, with no {@code WRITE},
 *       {@code REWRITE} or {@code DELETE} anywhere in it. <strong>No member of {@code app/jcl} executes
 *       it</strong>, so it has no distinct job to be translated from, and inventing one would be inventing
 *       control flow. It contributes pre-flight validation and diagnostic logic as an explicitly labelled
 *       read-only step inside the posting job.</li>
 *   <li><strong>The four simple readers are verification steps.</strong> {@code app/cbl/CBACT01C.cbl} (193
 *       lines), {@code CBACT02C.cbl} (178), {@code CBACT03C.cbl} (178) and {@code CBCUS01C.cbl} (178) have a
 *       verb inventory of {@code OPEN}, {@code READ} and {@code CLOSE} only. Their JCL members are
 *       {@code READACCT}, {@code READCARD}, {@code READXREF} and {@code READCUST}. They become read-only
 *       verification steps wired through the four readers in {@code com.cardemo.batch.readers}, not four
 *       more jobs: the four steps and the single submission that composes them are declared by
 *       {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, which owns step topology, and each writes
 *       nothing at all.</li>
 *   </ul>
 *
 * <h2>{@code COND=(0,NE)} gating: three sites, all inside one member</h2>
 *
 * <p>This is narrower than the phrase "COND gating" suggests, and the difference decides where a decider may
 * legitimately appear. {@code COND=(0,NE)} occurs at exactly three sites in the whole corpus, all of them in
 * {@code app/jcl/CREASTMT.JCL}: {@code STEP020} at {@code :L56}, {@code STEP030} at {@code :L66} and
 * {@code STEP040} at {@code :L79}. {@code DELDEF01} at {@code :L22} and {@code STEP010} at {@code :L44}
 * carry none, and {@code app/jcl/POSTTRAN.jcl}, {@code app/jcl/INTCALC.jcl}, {@code app/jcl/COMBTRAN.jcl}
 * and {@code app/jcl/TRANREPT.jcl} contain no {@code COND} at all.
 *
 * <p><strong>The consequence is load bearing.</strong> Gating derived from {@code COND} exists only
 * <em>within</em> the statement generation job. Everywhere else the gating is <em>between</em> jobs and
 * belongs to the orchestrator. A decider added to a job whose source carries no {@code COND} would be
 * invented control flow, so none is.
 *
 * <h2>The return code mapping, and why two of its outcomes must not be conflated</h2>
 *
 * <p>Return codes 0, 4, 8 and 12 map onto completed, completed-with-rejects, failed and abend. The mapping
 * is exhaustive and every unmatched value has a defined behaviour; there is no silent default to continue.
 *
 * <ul>
 *   <li><strong>Return code 4 is set if and only if the reject count exceeds zero.</strong> The per-record
 *       loop of {@code app/cbl/CBTRN02C.cbl} runs at {@code :L202-L234}, and {@code :L227-L231} is the sole
 *       determinant: after every file closes, the processed and rejected counts are displayed and
 *       {@code MOVE 4 TO RETURN-CODE} executes exactly when {@code WS-REJECT-COUNT} is greater than zero.
 *       There is no second condition, no threshold, no percentage and no warning band, and none may be
 *       added.</li>
 *   <li><strong>An abend is code 999 with return code 12.</strong> {@code 9999-ABEND-PROGRAM} at
 *       {@code app/cbl/CBTRN02C.cbl:L707-L711} displays an abend message, zeroes a timing field, moves 999
 *       into the abend code and calls the language environment abend service. It surfaces as
 *       {@code com.cardemo.exception.FatalProcessingException}, whose field set comes from
 *       {@code app/cpy/CSMSG02Y.cpy:L21-L29} - a copybook internally titled
 *       {@code CABENDD.CPY}, carrying the COBOL sequence numbers 001200 through 002000, and holding
 *       {@code ABEND-CODE X(4)}, {@code ABEND-CULPRIT X(8)},
 *       {@code ABEND-REASON X(50)} and {@code ABEND-MSG X(72)}. It is <strong>not</strong> a message
 *       copybook, and reading it as one loses the abend field set.</li>
 *   <li><strong>Return code 4 and return code 12 are independent paths.</strong> A run with rejects is a
 *       completed run with a distinguishable exit status; a run that abends is a failure. Conflating them
 *       makes a routine reject look like an outage, and letting a reject drive a failure makes the pipeline
 *       stop on data the source accepted.</li>
 *   <li><strong>Reject codes are business outcomes and are never thrown.</strong>
 *       {@code com.cardemo.model.enums.RejectCode} is a closed enumeration of exactly five constants - 100,
 *       101, 102, 103 and 109 - each carrying its exact literal description. They drive an exit status.</li>
 *   </ul>
 *
 * <h2>DFSORT and IDCAMS become in-process Java, and no external process is spawned</h2>
 *
 * <p>{@code java.util.Comparator} replaces DFSORT and {@code JdbcTemplate.batchUpdate} replaces IDCAMS
 * {@code REPRO}. Nothing shells out: {@code Runtime.exec} and {@code ProcessBuilder} appear nowhere in the
 * batch tier, which is both an architectural requirement and an independent rule obligation, since the rule
 * set names the {@code exec} family among the risky patterns to flag. A comparator must also be total and
 * stable so that a sort is reproducible run to run, and stateless so that it is safe to share. For the same
 * reason no untrusted payload is ever deserialised as Java - an object store payload and a queue message
 * body are parsed, never handed to an {@code ObjectInputStream} - and no SQL or JPQL is assembled by string
 * concatenation.
 *
 * <p>Three sort specifications are reproduced.
 *
 * <ul>
 *   <li>The report sort, {@code app/jcl/TRANREPT.jcl:L41-L48} and {@code app/proc/TRANREPT.prc:L39-L46}:
 *       card number ascending, with an <strong>inclusive</strong> string date range over the ten character
 *       prefix of the processing timestamp. The symbols place the card number at offset 263 for sixteen
 *       zoned-decimal characters and the processing date at offset 305 for ten characters.</li>
 *   <li>The combine sort, {@code app/jcl/COMBTRAN.jcl:L28-L30}: transaction identifier ascending over a
 *       <strong>concatenated</strong> input of two generations, the transaction backup at {@code :L24} and
 *       the interest output at {@code :L26}.</li>
 *   <li>The statement sort, {@code app/jcl/CREASTMT.JCL:L53-L54}: card number then identifier, plus a record
 *       projection.</li>
 *   </ul>
 *
 * <p><strong>The statement projection silently truncates two bytes, and that is reproduced rather than
 * corrected.</strong> {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} emits a sixteen byte card number,
 * then 262 bytes of the record head, then fifty bytes taken from offset 279 - which is the whole twenty-six
 * byte originating timestamp plus only the <em>first twenty-four</em> of the twenty-six processing timestamp
 * bytes, and drops the twenty byte trailing filler entirely. The projected processing timestamp therefore
 * arrives as a twenty-four character value padded to twenty-six. Repairing it would change the emitted bytes
 * and break the parity comparison against the frozen corpus, so it is held as {@code DL-PP-06} in
 * {@code DECISION_LOG.md} instead.
 *
 * <p>Record geometry is a byte contract and not a preference, because the parity comparison is made on the
 * emitted bytes: a wrong length is a wrong output even when every field value is right. The five lengths are
 * 430 for a reject record - 350 data bytes plus an 80 byte trailer of a four digit reason code and a 76
 * character description, {@code app/cbl/CBTRN02C.cbl:L176-L182} confirmed by
 * {@code app/jcl/POSTTRAN.jcl:L36} - then 350 for a transaction or interest record, 133 for a report line,
 * 100 for a markup statement line and 80 for a text statement line.
 *
 * <h2>Batch metadata tables come from the framework, and never from a fourth migration</h2>
 *
 * <p>The {@code BATCH_*} metadata tables are created by the framework's own schema script through
 * {@code spring.batch.jdbc.initialize-schema}, which the local and test profiles set to {@code always} and
 * the production profile keeps at {@code never} under the least privilege standard. There are exactly three
 * migrations and this class adds no fourth, nor any batch metadata to the first: a validation gate asserts
 * that the first migration creates exactly eleven tables, so adding metadata there fails it.
 *
 * <p><b>The script runs as the DDL-owning role, and the result is verified against the runtime one.</b>
 * {@link #batchMetadataInitializer(DataSource, BatchProperties, Environment, String, String)} replaces Boot's own
 * initialiser for two reasons that a deployed topology proved rather than predicted: Boot runs the script
 * through the runtime DataSource, which is deliberately bound to a DML-only role that cannot create a table,
 * and Boot's settings carry {@code continueOnError=true}, so every refusal was silent and the container
 * started healthy with no metadata schema and therefore no launchable job. That bean runs the same framework
 * script as the same role Flyway uses, then proves through the runtime DataSource that all six tables are
 * readable by the principal that will use them, and refuses to start when any is not.
 *
 * <p>{@code spring.batch.job.enabled} is {@code false}, so jobs do not run at startup. Launching is explicit,
 * through the two paths the mainframe had. One is an operator submitting a deck, which is the property-gated
 * runner in {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}. The other is the online tier writing to
 * the {@code JOBS} data queue for the JES2 internal reader, driven by {@code EXEC CICS WRITEQ TD
 * QUEUE('JOBS')} in {@code app/cbl/CORPT00C.cbl}, and <strong>that one is declared in this class</strong> -
 * see {@link ReportJobQueueListener}. This class still declares no runner, no scheduler and nothing annotated
 * to fire on startup: a queue listener fires when a message arrives, which is the opposite of firing on boot.
 *
 * <h2>The record-image contract, and why these bindings render fixed-width text</h2>
 *
 * <p>{@code CBSTM03B} returns a record by moving it into {@code WS-M03B-FLDT PIC X(1000)}; the caller then
 * moves that area into a copybook-shaped group and reads fields by position. The relational substrate has
 * columns rather than positions, so each binding renders its entity back into the legacy record image and
 * {@code StatementProcessor} parses it exactly as {@code CBSTM03A} did. That round trip is not ceremony: it
 * is the contract, and it is what keeps the field widths, the zoned-decimal signs and the 32-byte composite
 * key of {@code app/cpy/COSTM01.CPY} observable rather than implicit. The widths are taken from the
 * copybooks and asserted by {@link FileService.Dd#recordWidth()}, so a drift is a startup failure rather
 * than a silently short record.
 *
 * <p>The signed money fields of {@code app/cpy/CVACT01Y.cpy} are encoded through
 * {@link StatementProcessor#encodeZonedDecimal(BigDecimal, int)}, the published inverse of the decoder that
 * consumes them. The table is therefore stated once, in the class that owns both directions, rather than
 * copied here.
 *
 * <h2>Ordering is a precondition, not a preference</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L417-L419} abandons its scan as soon as a stored card number exceeds the
 * one it is looking for. That early exit is only correct while both sequences ascend by card number, which
 * {@code app/jcl/CREASTMT.JCL:L53} guarantees for the transaction stream and the {@code XREF-CARD-NUM}
 * record key guarantees for the cross-reference stream. Both sequential bindings therefore read in that
 * order and do so through <strong>keyset</strong> windows rather than page-number offsets, so a full-run
 * scan costs one index seek per window instead of re-reading every preceding row.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile} - {@code -Xlint:all -Werror} with {@code failOnWarning}, so
 * a warning fails the build. Test with {@code ./mvnw -B clean test}; the full gate, including the JaCoCo
 * line floor and the Failsafe integration tier, is {@code ./mvnw -B clean verify}. The integration tier
 * needs a reachable Docker socket because it starts PostgreSQL and the AWS emulator through Testcontainers.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every key is reached through Spring property binding. This class calls {@code System.getenv} nowhere,
 * assumes no absolute host path, and relies on no default charset, locale or time zone.
 *
 * <table border="1">
 *   <caption>Configuration this class binds directly</caption>
 *   <tr><th>Key</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code carddemo.batch.dataset-window-size}</td><td>{@value #DEFAULT_WINDOW_SIZE}</td>
 *       <td>Rows fetched per keyset window by the two sequential bindings. Affects memory and round trips
 *           only; it cannot affect the emitted output, because the order is fixed independently of it. Must
 *           be positive: a non-positive value is refused by name rather than defaulted silently</td></tr>
 *   <tr><td>{@code carddemo.aws.s3.batch-output-bucket}</td><td><strong>none</strong> - an unset value
 *       fails fast</td>
 *       <td>The bucket the statement sort step publishes its projected work object to, and the same key
 *           {@code StatementGenerationJob} publishes it under. Deliberately without an inline default so an
 *           unconfigured environment cannot silently read from somewhere no operator named</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>Configuration this class honours but does not bind or redeclare</caption>
 *   <tr><th>Key</th><th>Value in force</th><th>Why it matters here</th></tr>
 *   <tr><td>{@code spring.main.allow-bean-definition-overriding}</td><td>{@code false}</td>
 *       <td>Makes a duplicate bean definition a startup failure. It is the reason the ownership rules above
 *           are rules rather than preferences</td></tr>
 *   <tr><td>{@code spring.batch.job.enabled}</td><td>{@code false} (framework default {@code true})</td>
 *       <td>Jobs do not auto-launch. Not a defect: launching is explicit</td></tr>
 *   <tr><td>{@code spring.batch.jdbc.initialize-schema}</td>
 *       <td>{@code never} at base and in production, {@code always} in local and test</td>
 *       <td>Creates the {@code BATCH_*} metadata tables from the framework's own script. No migration
 *           creates them</td></tr>
 *   <tr><td>{@code carddemo.batch.chunk-size}</td><td>{@code 100}</td>
 *       <td>The middle level of each job's two level chunk-size fallback. Each job reads its own
 *           {@code carddemo.batch.<id>.chunk-size} first, falls back to this, and only then to its own
 *           constant. Giving a per-job key a value in a profile resolves the first level and stops this
 *           global one reaching that job, which is why the per-job keys are named in the profile without
 *           values</td></tr>
 *   <tr><td>{@code carddemo.batch.jobs.<id>.name}</td>
 *       <td>the JCL member name: {@code POSTTRAN}, {@code INTCALC}, {@code COMBTRAN}, {@code CREASTMT},
 *           {@code TRANREPT}</td>
 *       <td>Bound by each job class, not here, so the identity has one owner</td></tr>
 * </table>
 *
 * <p>Two run parameters are deliberately <strong>not</strong> properties. The interest date, which
 * {@code app/jcl/INTCALC.jcl:L22} passes as {@code PARM='2022071800'}, is a job parameter: it is ten numeric
 * characters - eight date digits followed by two zeros, with no separators - and is <em>not</em> an ISO date,
 * because {@code app/cbl/CBACT04C.cbl:L175-L181} receives it as a linkage parameter and concatenates it into
 * generated transaction identifiers. The report period bounds, which
 * {@code app/jcl/TRANREPT.jcl:L43-L44} supplies as {@code PARM-START-DATE} and {@code PARM-END-DATE}, are job
 * parameters for the same reason, and are also the body of the queue message that reproduces the 80 byte
 * parameter record.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails with "no dataset binding is registered for DD ..."</dt>
 *   <dd>{@code FileService} verified its bindings at wiring time and one of the four was missing. Either
 *       this class was not scanned - a sliced test that imported only some configuration - or a bean method
 *       below was removed. All four are mandatory; the service refuses to start half-bound rather than
 *       abend in the middle of a statement run.</dd>
 *   <dt>Startup fails with "two dataset bindings claim DD ..."</dt>
 *   <dd>A second binding for the same DD was declared elsewhere. Remove that one: dispatch would otherwise
 *       depend on bean ordering.</dd>
 *   <dt>No {@code transactionReportProcessor} bean can be found in the context</dt>
 *   <dd>Expected, and not a defect in this class. {@code TransactionReportProcessor} is not a bean at all:
 *       {@code com.cardemo.batch.jobs.TransactionReportJob} constructs it inside each STEP10R execution with
 *       the two dates it read from the job parameters. Inject the job, not the processor. A unit test
 *       constructs it directly, which is what production does too.</dd>
 *   <dt>A statement run reports a card group as missing although rows exist</dt>
 *   <dd>The two sequential streams disagreed on order. Check that nothing reordered the statement sort:
 *       the transaction stream must be card-then-identifier ascending and the cross-reference stream must
 *       be card ascending, or the source's early exit ends the scan too soon.</dd>
 *   <dt>Startup fails with {@code BeanDefinitionOverrideException} naming a job bean</dt>
 *   <dd>Two definitions of the same {@code Job} exist and {@code spring.main.allow-bean-definition-overriding}
 *       is {@code false}. Remove the duplicate from {@code BatchConfig}, <strong>not</strong> from the job
 *       class: the six classes in {@code com.cardemo.batch.jobs} are the designated definition sites. The
 *       same applies to a step, a flow or a processor whose derived bean name collides with a
 *       {@code @Component}.</dd>
 *   <dt>Startup fails naming a missing or unreadable {@code BATCH_*} table</dt>
 *   <dd>This is {@link #batchMetadataInitializer(DataSource, BatchProperties, Environment, String, String)}
 *       refusing to start an application that could not launch a job, which is the deliberate replacement for
 *       the silence that preceded it. Either the DDL role could not create the schema - check that the
 *       migration role and its password reached the application and that the role holds {@code CREATE} on the
 *       schema - or {@code spring.batch.jdbc.initialize-schema} is {@code never}, which is deliberate at base
 *       and in production, and nobody has applied the framework's own script. Do <strong>not</strong> add a
 *       migration for it: the migration set is closed at three and a gate asserts the first one creates
 *       exactly eleven tables.</dd>
 *   <dt>No job runs at startup and no error appears</dt>
 *   <dd>Expected and by design: {@code spring.batch.job.enabled} is {@code false}. Launch either by
 *       submitting one - {@code java -jar carddemo.jar --spring.main.web-application-type=none
 *       --spring.batch.job.enabled=true --spring.batch.job.name=CARDDEMO-PIPELINE} plus the three date
 *       parameters as {@code name=value} arguments, which is the operator
 *       path - or by publishing a report submission to the queue
 *       {@link ReportJobQueueListener} drains. Adding an unconditional runner, a scheduler or a startup hook
 *       to "fix" this reintroduces the behaviour the flag exists to suppress: every job running on every
 *       boot, including on the boot of a container whose job is to serve HTTP.</dd>
 *   <dt>A report submission returns success and no report job ever runs</dt>
 *   <dd>The listener is not running. It exists only when {@value #KEY_REPORT_QUEUE} is set, so check that
 *       first; then check the log for the {@code ERROR} line naming the submission, because a payload that
 *       cannot be bound or a period the report job refuses is logged and consumed rather than returned to the
 *       queue. The reason that message is consumed rather than retried is on
 *       {@link ReportJobQueueListener}.</dd>
 *   <dt>The combine load step fails on a duplicate key</dt>
 *   <dd>The interest run was repeated with the same date parameter, so it minted colliding transaction
 *       identifiers: {@code app/cbl/CBACT04C.cbl} concatenates the ten character date with a run-sequential
 *       suffix, and {@code app/jcl/COMBTRAN.jcl:L48} then loads the combined generation into the transaction
 *       cluster. Surfacing it as a duplicate-record failure is correct; a silent upsert would hide a repeated
 *       run and is never the remedy. Re-run the interest job with the intended date.</dd>
 *   <dt>A batch log line carries no {@code jobInstanceId}</dt>
 *   <dd>The job was launched without a {@code JobExecutionListener} registered on it. A listener bean is not
 *       applied to a job implicitly - neither the batch framework nor the Boot auto-configuration collects
 *       listener beans - so it takes effect only where a job builder registers it. Each of the six job
 *       classes in {@code com.cardemo.batch.jobs} registers its own; a job composed outside those six must
 *       register one too, and should establish the context through
 *       {@link com.cardemo.observability.CorrelationIdFilter#enterBatchScope(long, String)} and release it
 *       through {@link com.cardemo.observability.CorrelationIdFilter#exitBatchScope()} in a {@code finally}
 *       rather than putting the key by hand (findings M-01 and CFG-003).</dd>
 *   </dl>
 *
 * <h2>Findings this class carries, by severity</h2>
 *
 * <dl>
 *   <dt>Blocker</dt>
 *   <dd>"Correcting" the two byte truncation of the statement projection, or matching {@code app/jcl} with a
 *       case sensitive {@code *.jcl} pattern. The first changes the emitted bytes and fails the parity
 *       comparison; the second drops {@code app/jcl/CREASTMT.JCL} and removes statement generation from
 *       scope with no error raised anywhere.</dd>
 *   <dt>High</dt>
 *   <dd>A seventh job; promoting {@code CBTRN01C} or any of the four simple readers to a job; a fourth
 *       migration or batch metadata in the first; a diagnostic context key left behind on a pooled thread;
 *       and conflating return code 4 with return code 12.</dd>
 *   <dt>Medium</dt>
 *   <dd>Two legacy naming defects that are left as they are, because repairing either would change behaviour
 *       the parity comparison measures. {@code app/jcl/TRANREPT.jcl} names two different steps
 *       {@code STEP05R}, at {@code :L23} and {@code :L37}, which {@code app/proc/TRANREPT.prc:L21} shows was
 *       meant to be {@code STEP01R}. And the markup statement record length is 80 in the pre-delete step at
 *       {@code app/jcl/CREASTMT.JCL:L69} but 100 in the execution step at {@code :L94}; the 100 is
 *       authoritative, independently confirmed by the hundred character field at
 *       {@code app/cbl/CBSTM03A.CBL:L149}.</dd>
 *   <dt>Medium</dt>
 *   <dd>{@code carddemo.batch.jobs.<id>.enabled} and {@code carddemo.batch.jobs.creastmt.steps} are
 *       <strong>not declared in any profile</strong>, and must not be: a key bound by nothing - no
 *       {@code @Value}, no {@code @ConfigurationProperties}, no {@code Environment} lookup - lets an operator
 *       reading {@code enabled: true} reasonably believe a job can be switched off from a profile when it
 *       cannot. Giving them a consumer is not the alternative, because binding them
 *       into a bean nothing consumes would be dead code and Rule 1 Clause B forbids dead configuration as
 *       well. The five-step structure of CREASTMT is likewise not configuration: it is fixed by
 *       {@code app/jcl/CREASTMT.JCL}, which declares exactly five steps, and this class builds those five
 *       from the JCL rather than from a count. Separately, every job name is bound under one parent and one
 *       spelling: {@link com.cardemo.batch.jobs.TransactionReportJob} reads
 *       {@code carddemo.batch.jobs.tranrept.name} and
 *       {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator} reads
 *       {@code carddemo.batch.jobs.pipeline.name}. A second spelling such as
 *       {@code carddemo.batch.tranrept.name} would leave the declared key inert - editing it would change
 *       nothing, with no error to say so. Every key any of these beans reads is declared in
 *       {@code application.yml}.
 *       The only switch that genuinely exists is the framework's own {@code spring.batch.job.name},
 *       matched against {@code Job.getName()}, which is why every job is named after its JCL member; a
 *       second switch in this namespace would be two switches for one decision, and no second binding is
 *       declared here, because that is exactly the drift the profile warns against.
 *       A per-job enable flag could not be honoured either: the five stages are constructor
 *       dependencies of {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator}, so a conditionally
 *       absent job would fail the context rather than skip a stage.</dd>
 *   <dt>Low</dt>
 *   <dd>{@code app/proc/TRANREPT.prc:L1} and {@code app/proc/REPROC.prc:L1} both declare
 *       {@code //REPROC PROC}, so the internal procedure name of the first differs from the member name that
 *       {@code EXEC PROC=TRANREPT} resolves. And {@code app/jcl/CREASTMT.JCL:L90} is a corrupted DD line,
 *       carrying fragments of a {@code DCB} and a data set name spliced into a {@code SPACE} parameter. Both
 *       are left exactly as found.</dd>
 *   </dl>
 *
 * <p>Each preserved defect above is held in {@code DECISION_LOG.md} under a {@code DL-LD-*}
 * identifier - {@code DL-LD-01} for the corrupted data-definition line and {@code DL-LD-02} for the
 * eighty-versus-one-hundred record-length disagreement - and each source paragraph named in this class
 * has its row in {@code TRACEABILITY_MATRIX.md}. Both registers are authored at the repository root.
 *
 * <h2>Not available</h2>
 *
 * <p>Four things a reader may look for are genuinely absent from the source, and are stated as absent rather
 * than filled in with an invention.
 *
 * <ul>
 *   <li><strong>{@code app/jcl/COMBTRAN.jcl} has no COBOL program.</strong> Its logic is entirely SORT and
 *       IDCAMS control cards, so the JCL itself is the source of truth for that job. What would be needed to
 *       remove this gap is a program that does not exist.</li>
 *   <li><strong>{@code CBTRN01C} has no distinct JCL job.</strong> No member of {@code app/jcl} executes it,
 *       which is why it is a step of the posting job rather than a job.</li>
 *   <li><strong>{@code app/jcl/CBADMCDJ.jcl}, {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl}
 *       have no Java analogue.</strong> The first installs the CICS resource definitions and is superseded
 *       by {@code com.cardemo.config.SecurityConfig}; the latter two manage online data set availability and
 *       are superseded by {@code com.cardemo.observability.HealthIndicators}. None of the three contributes a
 *       step here.</li>
 *   <li><strong>No throughput or latency objective exists anywhere in the source.</strong> The corpus
 *       publishes no service level, so the performance gate records a measured baseline and not a target. No
 *       chunk size service level and no throughput threshold is invented here, and the chunk sizes are
 *       tunables rather than contracts.</li>
 *   </ul>
 *
 * <h2>A labelled deviation, not parity</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L225-L233} declares the statement working set as a fixed table of 51 card
 * entries holding ten transactions each - a hard ceiling of 510 - and the building loop increments both
 * indices with no bounds check whatsoever, so a 511th transaction overran storage silently. The Java
 * translation streams instead, which removes the silent corruption but is a behaviour change at scale and is
 * therefore labelled as a deviation rather than presented as equivalence. The replacement bound fails loudly
 * instead of truncating. This is held as {@code DL-DV-03} in {@code DECISION_LOG.md}, and the legacy
 * ceiling has its row in {@code TRACEABILITY_MATRIX.md} as the historical capacity limit.
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>This class holds one immutable {@code int} field, the window size, assigned once by the constructor
 * from configuration. The two random-access bindings are stateless. The two sequential bindings hold a
 * cursor, which is what a sequential read is; every method that touches it is {@code synchronized} and
 * {@code openInput} resets it, so a second run starts from the beginning exactly as a second
 * {@code OPEN INPUT} does. That mirrors the source faithfully: {@code CBSTM03B} is a single load module
 * whose {@code WORKING-STORAGE} survives between calls, so the legacy program supported one statement run
 * at a time and so does this wiring. Two concurrent statement runs are outside the source's contract and
 * are not supported.
 */
@Configuration
public class BatchConfig {

    /**
     * Default number of rows a sequential binding fetches per keyset window.
     *
     * <p>Chosen to match the chunk size the batch profile already uses for the interest job, so that a
     * dataset window and a chunk are the same order of magnitude and neither dominates the other.
     */
    private static final int DEFAULT_WINDOW_SIZE = 100;

    /** Configuration key for the window size, so the spelling exists exactly once. */
    private static final String KEY_WINDOW_SIZE = "carddemo.batch.dataset-window-size";

    /**
     * The FIFO queue the report submission listener drains, deliberately without a default.
     *
     * <p>Named rather than defaulted because a guessed queue name is worse than no listener: it would bind
     * successfully, drain nothing, and report itself healthy. Every profile that has a queue sets it.
     */
    private static final String KEY_REPORT_QUEUE = "carddemo.aws.sqs.report-queue";

    /**
     * The framework's own operator-submission property, read here so the queue listener can stand down.
     *
     * <p>{@code spring.batch.job.name} is what an operator sets to submit one job:
     * {@code JobLauncherApplicationRunner} compares it with {@code Job.getName()} and launches the match. It
     * is a framework property rather than one this application declares, and that is the point - finding
     * CFG-001, severity High, removed the authored {@code carddemo.batch.launch} runner that used to occupy
     * this role, so the signal "this process is a batch submission" now comes from the launcher that actually
     * performs the submission rather than from a property only this codebase understood.
     *
     * <p>What it is used for here is a negative condition. When an operator has submitted a job, this
     * application is a batch submission and not the online tier, so it must not drain the queue - see the
     * commentary above the listener for both reasons that matters.
     */
    private static final String KEY_LAUNCH_JOB = "spring.batch.job.name";

    /**
     * Property that withdraws the report-queue consumer from a context that must not compete for messages.
     *
     * <p>Defaults to enabled, so every ordinary deployment consumes the queue exactly as the JES2 internal
     * reader consumed {@code TDQUEUE(JOBS)}. It exists for one situation: a context that asserts what the
     * <em>producer</em> put on the queue has to be the only reader, and a consumer racing it turns a
     * deterministic assertion into a timing accident. {@code application-test.yml} therefore sets this to
     * {@code false}, which is why the shared integration harness can inspect published messages directly.
     *
     * <p>This is the same address-space separation that {@value #KEY_LAUNCH_JOB} expresses: on the mainframe
     * the submitting region and the reading initiator are different address spaces, and only one of them
     * drains the queue.
     */
    private static final String KEY_REPORT_QUEUE_LISTENER_ENABLED =
            "carddemo.batch.report-queue-listener.enabled";

    /**
     * The listener container's identifier, so it can be found by name in a running context.
     *
     * <p>An explicit identifier rather than a generated one because there must be exactly <em>one</em>
     * consumer of this queue - a second would let two executions race for one submission - and a stable name
     * is what makes that assertable at runtime and in a test.
     */
    private static final String REPORT_QUEUE_LISTENER_ID = "carddemoReportJobsListener";

    /**
     * How long one receive waits for a message before returning empty, in seconds, as the annotation
     * attribute requires it.
     *
     * <p><b>It must stay strictly below the queue client's per-attempt
     * deadline, and the library's default does not.</b> {@code AwsConfig} bounds every queue call at a
     * ten-second {@code apiCallAttemptTimeout} inside a thirty-second {@code apiCallTimeout}, deliberately, so
     * that an unreachable emulator surfaces as a bounded failure rather than a blocked thread. Spring Cloud
     * AWS defaults a listener's poll to <em>ten</em> seconds, which is the same value: every long poll over an
     * idle queue then races that per-attempt deadline, is aborted, retried twice more and finally fails
     * the whole call, so an idle deployment logs
     * {@code ApiCallTimeoutException: Client execution did not complete before the specified timeout
     * configuration: 30000 millis} at {@code ERROR} once every thirty seconds, forever - measured at 371
     * occurrences in one afternoon of an otherwise healthy container. An error that an idle system emits on
     * a timer trains an operator to ignore the log, which is the whole cost of it.
     *
     * <p>Five seconds leaves half the per-attempt budget as margin, so a receive over an idle queue completes
     * and returns empty on its first attempt. The alternative - widening the client's deadlines to fit a longer
     * poll - was rejected because those deadlines guard every publish and the health probe too, and loosening
     * them to accommodate the consumer would trade a real property for a cosmetic one. The cost is one empty
     * receive every five seconds instead of every thirty; against the LocalStack endpoint this topology targets
     * that is free, and no live cloud path is structurally reachable.
     *
     * <p><b>The relationship is the contract, not the number.</b> A deployment that raises the client's
     * per-attempt deadline may raise this to match, but this value must never reach it. {@code BatchConfigTest}
     * asserts the inequality against {@code AwsConfig}'s own constant rather than against a literal, so the two
     * cannot drift apart silently.
     */
    private static final String REPORT_QUEUE_POLL_TIMEOUT_SECONDS = "5";

    /**
     * How long a received submission stays invisible to any other receive, in seconds.
     *
     * <p><strong>Declaring the poll wait alone is not enough.</strong> Without this value the
     * queue's own default of thirty seconds governs how long a received message stays hidden. This listener
     * does not merely read a message - it launches the report job and waits for it, and that job backs up the
     * transaction cluster, sorts a whole generation and writes the 133-byte report of
     * {@code app/proc/TRANREPT.prc:L74-L78}. Thirty seconds is far shorter than that, so a submission becomes
     * visible again while its own execution is still running and is received a second time: with the
     * acknowledgement defect of finding C-02 also present, that produces either a competing execution or a
     * silently discarded duplicate.
     *
     * <p>Nine hundred seconds is the processing window, and the same value
     * {@code localstack-init/init-aws.sh} provisions as the queue's {@code VisibilityTimeout} - stated in both
     * places on purpose, because the queue attribute governs a delivery this listener did not make and the
     * annotation governs the ones it does. A run that legitimately needs longer extends its own window through
     * the {@link io.awspring.cloud.sqs.listener.Visibility} handle rather than having this value raised, which
     * is why the listener takes that handle as an argument.
     */
    private static final String REPORT_QUEUE_VISIBILITY_SECONDS = "900";

    /**
     * The visibility window, as an integer, for the extension the listener performs before it launches.
     *
     * <p>Derived from {@link #REPORT_QUEUE_VISIBILITY_SECONDS} rather than restated, so the annotation value
     * and the extension can never disagree.
     */
    private static final int REPORT_QUEUE_VISIBILITY = Integer.parseInt(REPORT_QUEUE_VISIBILITY_SECONDS);

    /**
     * The bean name of the job every message on the report queue names.
     *
     * <p>The literal is repeated here because the declaring constant in
     * {@code com.cardemo.batch.jobs.TransactionReportJob} is private to that class. The two must agree, and
     * the qualifier below is where a disagreement surfaces - as a startup failure naming this string, not as
     * a message that silently launches nothing.
     */
    private static final String TRANSACTION_REPORT_JOB_BEAN_NAME = "transactionReportJob";

    /**
     * The submission's report name, carried as an identifying job parameter.
     *
     * <p>{@code WS-REPORT-NAME} at {@code app/cbl/CORPT00C.cbl:L58}, one of {@code Monthly}, {@code Yearly}
     * or {@code Custom}. Identifying because the source's job card carried it and because two submissions
     * differing only in period name are two submissions.
     */
    private static final String REPORT_NAME_JOB_PARAMETER = "reportName";

    /**
     * The submission's identity, carried as an identifying job parameter, and the whole idempotency mechanism.
     *
     * <p>This is what makes one queue message one job instance no matter how many times the queue delivers
     * it. It has no counterpart in the source because JES2 read each card deck once; a queue with
     * at-least-once delivery needs the key the mainframe did not.
     */
    private static final String SUBMISSION_ID_JOB_PARAMETER = "submissionId";

    /**
     * Property key holding the material the queue envelope code is verified against.
     *
     * <p>The same key {@code com.cardemo.service.report.ReportSubmissionService} signs with, so producer and
     * consumer cannot be configured apart. It is the application signing key, from which
     * {@link ReportSubmissionService.JobSubmissionEnvelope} derives a single-purpose key; see that type for
     * why derivation rather than reuse, and for why there is no unsigned mode.
     *
     * <p>Finding SEC-001. The configured value is bound into {@link #reportJobQueueListener} as a method
     * parameter and consumed there; no bean field holds it, so it does not outlive the context refresh in a
     * form a heap dump could recover.
     */
    private static final String KEY_ENVELOPE_SIGNING_KEY = "carddemo.security.jwt.signing-key";

    /**
     * Diagnostic-context key carrying the producer's W3C trace context through a launch.
     *
     * <p>Deliberately not {@code traceId} or {@code spanId}: those are owned by the tracing bridge, and
     * writing them by hand would overwrite real span identity with a value copied from a message. The whole
     * traceparent is carried instead, under its own name, so the hop is correlatable without being
     * misattributed.
     */
    private static final String MDC_KEY_TRACE_PARENT = "traceParent";

    /**
     * Longest propagated header value this consumer will place in the diagnostic context.
     *
     * <p>A W3C traceparent is 55 characters and a correlation identifier is a UUID at 36, so 128 accepts
     * every legitimate value with room to spare while refusing the unbounded string that a hostile publisher
     * would use to flood a log sink. Finding M-03.
     */
    private static final int MAX_PROPAGATED_HEADER_LENGTH = 128;

    /** Lowest code point a propagated header may carry, {@code '!'}: excludes every control character. */
    private static final char MIN_PROPAGATED_HEADER_CHAR = '\u0021';

    /** Highest code point a propagated header may carry, {@code '~'}: excludes DEL and everything above. */
    private static final char MAX_PROPAGATED_HEADER_CHAR = '\u007E';

    /**
     * Stands in for a submission identifier when the message carries neither of the two candidates.
     *
     * <p>A literal rather than a generated value, deliberately: a generated one would make every redelivery
     * a new job instance, turning an unidentifiable message into repeated executions. This value makes them
     * collapse onto one instance instead, which is the safer failure and is visible in the log.
     */
    private static final String UNIDENTIFIED_SUBMISSION = "unidentified-submission";

    /**
     * The digest that derives the logged delivery identifier from the transport one, {@value}.
     *
     * <p><strong>Finding C-02, severity Blocker.</strong> Required of every conforming Java runtime by the
     * platform specification, so resolving it cannot fail on a supported runtime. See
     * {@code ReportJobQueueListener.mintedDeliveryId(String)} for why a digest rather than the value itself.
     */
    private static final String DELIVERY_ID_DIGEST_ALGORITHM = "SHA-256";

    /**
     * How many hexadecimal characters of that digest the log carries, {@value}.
     *
     * <p>Eight bytes rendered as sixteen characters: short enough to read in a log line, wide enough that a
     * collision between two concurrently-live deliveries is not a consideration.
     */
    private static final int DELIVERY_ID_HEX_LENGTH = 16;

    /**
     * Structured logger, the sole diagnostic channel of this class.
     *
     * <p>Nothing here writes to the process output streams: the legacy {@code DISPLAY} statements this system
     * replaces become log events so that they carry the trace, span and correlation identifiers the batch
     * tier propagates. Used only by the {@code TRNXFILE} binding, which is the one binding whose input can be
     * absent or truncated independently of the database.
     */
    private static final Logger LOG = LoggerFactory.getLogger(BatchConfig.class);

    /**
     * The character-comparison origin for a keyset scan. The empty string precedes every non-empty key, so
     * a first window requested with it starts at the beginning of the sequence.
     */
    private static final String SCAN_ORIGIN = "";

    /**
     * The {@code '00'} status every successful operation returns, taken from the enum that owns the
     * vocabulary rather than written as a literal.
     */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.code().orElseThrow();

    /** The {@code '10'} status a sequential read returns at end of file. */
    private static final String STATUS_END_OF_FILE = FileStatus.END_OF_FILE.code().orElseThrow();

    /** The {@code '23'} status a keyed read returns when the key is absent. */
    private static final String STATUS_RECORD_NOT_FOUND = FileStatus.RECORD_NOT_FOUND.code().orElseThrow();

    /**
     * File status {@code '35'}: the DD could not be opened.
     *
     * <p>Used by the {@code TRNXFILE} binding when the projected work object cannot be reached. {@code '35'}
     * is precisely "the file is not available" in the status vocabulary, which is what an absent or unreadable
     * work cluster is.
     */
    private static final String STATUS_FILE_UNAVAILABLE = FileStatus.FILE_UNAVAILABLE.code().orElseThrow();

    /**
     * A synthesised {@code '9x'} status for a physical failure against the object store.
     *
     * <p>The {@code '9'} first byte is the {@code FILE STATUS} family for a physical or logical I/O error, and
     * the trailing {@code 0} completes the two-character token. It is the same token
     * {@code StatementGenerationJob} synthesises for an object-store failure, so a diagnostic rendered from
     * either place reads identically.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /**
     * Charset of the projected work object: one byte is one character, so a copybook offset is a Java index.
     *
     * <p>{@code ISO-8859-1} for the same reason {@code StatementGenerationJob} writes the object with it - a
     * variable-width encoding would decode any byte above 0x7F to more than one character and shift every
     * offset after it, and the platform default is not reproducible across hosts.
     */
    private static final Charset PROJECTED_RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Rows fetched per keyset window by the two sequential bindings. Always at least one. */
    private final int windowSize;

    /**
     * The six framework metadata tables, unprefixed, in the order the framework's own script creates them.
     *
     * <p>Every one of them is required before any job can be launched or restarted: the instance and
     * execution tables carry identity, the parameter table carries the values a restart must match, and the
     * two context tables carry the generation manifests the stages hand to one another. A deployment missing
     * any of them cannot run the stream at all, which is why {@link #verifyMetadataReachable(DataSource,
     * String)} refuses to start rather than leaving the absence to be discovered by the first launch.
     */
    private static final List<String> METADATA_TABLE_SUFFIXES = List.of(
            "JOB_INSTANCE",
            "JOB_EXECUTION",
            "JOB_EXECUTION_PARAMS",
            "JOB_EXECUTION_CONTEXT",
            "STEP_EXECUTION",
            "STEP_EXECUTION_CONTEXT");

    /**
     * The three framework metadata SEQUENCES, unprefixed, as
     * {@code org/springframework/batch/core/schema-postgresql.sql} creates them.
     *
     * <p>Finding BAT-001, severity High. A readiness check covering the six
     * tables and nothing else leaves the way a job actually starts unverified: every identity in this
     * schema comes from a sequence, not from a serial column, so a deployment whose tables are all present
     * and readable still fails on the <em>first</em> launch if a sequence is missing or if the runtime role
     * holds no {@code USAGE} on it. That is precisely the class of failure this check exists to convert into a
     * refusal to start.
     *
     * <p>The framework script creates them unquoted, so PostgreSQL folds each name to lower case; the probes
     * below pass them as SQL identifiers rather than as literals, which folds identically.
     */
    private static final List<String> METADATA_SEQUENCE_SUFFIXES = List.of(
            "JOB_SEQ",
            "JOB_EXECUTION_SEQ",
            "STEP_EXECUTION_SEQ");

    /**
     * The table privileges a launch or a restart genuinely exercises, each verified separately.
     *
     * <p>{@code SELECT} because a restart reads the previous execution and its parameters; {@code INSERT}
     * because a launch writes an instance, an execution and a step execution; {@code UPDATE} because every
     * commit interval rewrites the step execution's counters and the job execution context. A role holding
     * {@code SELECT} alone passes the existence probe and then fails on the first launch, which is the gap
     * this closes.
     *
     * <p>{@code DELETE} is deliberately <em>not</em> required. {@code docker-compose.yml} grants it, because
     * an administrative purge of old executions needs it, but no launch and no restart deletes a metadata row,
     * so requiring it here would refuse to start a deployment that is entirely able to run the stream.
     *
     * <p>Each privilege is checked in its own call because PostgreSQL's {@code has_table_privilege} treats a
     * comma-separated list as {@code ANY} rather than {@code ALL} - a role holding only {@code SELECT} would
     * satisfy {@code 'SELECT, INSERT, UPDATE'} and the check would pass while the launch still failed.
     */
    private static final List<String> REQUIRED_TABLE_PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE");

    /**
     * The sequence privileges the identity generators exercise, each verified separately.
     *
     * <p>{@code USAGE} is what {@code nextval} needs and is the one a grant most often omits; {@code SELECT}
     * and {@code UPDATE} are granted alongside it by {@code docker-compose.yml} and are required by
     * {@code currval} and by a sequence reset respectively. Listed separately for the same {@code ANY}-versus-
     * {@code ALL} reason as the table privileges above.
     */
    private static final List<String> REQUIRED_SEQUENCE_PRIVILEGES = List.of("USAGE", "SELECT", "UPDATE");

    /** The database product name, as reported by the driver, whose privilege functions the probes use. */
    private static final String POSTGRESQL_PRODUCT_NAME = "PostgreSQL";

    /**
     * Creates the configuration class.
     *
     * @param windowSize rows per keyset window, from {@value #KEY_WINDOW_SIZE}, defaulting to
     *     {@value #DEFAULT_WINDOW_SIZE}
     * @throws IllegalArgumentException if the window size is not positive, because a non-positive window
     *     would either fetch nothing and report a premature end of file or spin forever
     */
    public BatchConfig(
            @Value("${" + KEY_WINDOW_SIZE + ":" + DEFAULT_WINDOW_SIZE + "}") final int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException(KEY_WINDOW_SIZE + " must be positive but was " + windowSize);
        }
        this.windowSize = windowSize;
    }

    // THE FRAMEWORK METADATA SCHEMA: CREATED BY THE DDL-OWNING ROLE, USED BY THE DML-ONLY ROLE.
    //
    // Finding, severity High, RESOLVED here. The deployed topology had NO BATCH_* tables at all and said
    // nothing about it. Spring Boot's own BatchDataSourceScriptDatabaseInitializer runs the framework's
    // schema script through the RUNTIME DataSource, which docker-compose.yml binds to the DML-only role
    // carddemo_app; that role has no CREATE on schema public, deliberately - the inline role script REVOKEs
    // CREATE from PUBLIC and grants it to carddemo_migrator alone - so every CREATE TABLE was refused. The
    // refusal was invisible because Boot hardcodes continueOnError=true on those settings, so the container
    // started healthy, served requests, and could not launch or restart a single job: no job instance table,
    // no execution table, no execution context, and therefore no generation manifest to hand between stages.
    //
    // The fix keeps BOTH halves of the least-privilege split rather than trading one for the other. The
    // framework still owns its own schema - there is no fourth Flyway migration and no extra table in
    // V1__create_schema.sql, whose exact eleven-table shape a validation gate asserts - but the script now
    // runs as the DDL-owning role, exactly as Flyway's migrations do, and the runtime role keeps DML only.
    // No grant has to be added for that to work: docker-compose.yml already declares
    // ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE,
    // DELETE ON TABLES TO carddemo_app, which is what its own comment means by "the Spring Batch metadata
    // tables" and what makes tables created LATER by that role usable by the runtime.
    //
    // The second half of the fix is that absence is no longer silent. A deployment that reaches the end of
    // initialisation without the six tables being readable by the runtime role now FAILS TO START, with a
    // message naming the missing table, the role and the property that governs creation. A batch tier that
    // cannot launch anything is not a healthy application, and it must not be discovered by the first
    // operator who tries.

    /**
     * The framework metadata initialiser, bound to the DDL-owning role and verified against the runtime one.
     *
     * <p><b>What it does.</b> Two things, in order. It runs the framework's own
     * {@code org/springframework/batch/core/schema-postgresql.sql} - never a Flyway migration - through a
     * connection that is permitted to create tables, honouring {@code spring.batch.jdbc.initialize-schema}
     * exactly as Boot's own initialiser would. Then it proves, through the <em>runtime</em>
     * {@link DataSource}, that all six metadata tables are readable by the role that will actually use them,
     * and refuses to start if any is not.
     *
     * <p><b>Why it replaces Boot's.</b> Boot declares its initialiser
     * {@code @ConditionalOnMissingBean(BatchDataSourceScriptDatabaseInitializer.class)}, so declaring one
     * here takes over without disabling any auto-configuration. Boot's version has two properties this
     * deployment cannot live with: it uses the runtime DataSource, which is deliberately privilege-starved,
     * and its settings carry {@code continueOnError=true}, which turns a permission failure into silence.
     *
     * <p><b>Key configuration and defaults.</b> {@code spring.batch.jdbc.initialize-schema} governs whether
     * the script runs at all - {@code never} in the base and production profiles, {@code always} in
     * {@code local} and {@code test} - and this bean honours it unchanged, including {@code never}, where it
     * still performs the verification so that a DBA-provisioned schema is proven rather than assumed.
     * {@code spring.batch.jdbc.table-prefix} defaults to the framework's {@code BATCH_} and is not
     * overridden anywhere. {@code spring.flyway.user} and {@code spring.flyway.password} name the DDL-owning
     * role; when either is absent the runtime DataSource is used, which is the single-role case every
     * Testcontainers profile runs and the fallback an existing volume keeps working under.
     * {@code spring.flyway.url} is honoured when set and otherwise inherited from
     * {@code spring.datasource.url}, matching how Boot derives Flyway's own connection.
     *
     * <p><b>Why the URL arrives through {@link Environment} rather than a third {@code @Value}.</b> A
     * {@code @Value} argument is resolved when this bean is created, unconditionally, whether or not the
     * value is ever used - and the URL is used only on the two-role branch. That eagerness broke every
     * Testcontainers-backed context. Those profiles configure no {@code spring.flyway.user}, so the URL is
     * irrelevant to them, but {@code application.yml:581} declares
     * {@code spring.datasource.url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT:5432}/${POSTGRES_DB}}
     * with <b>no default for {@code POSTGRES_HOST}</b>, and a Testcontainers datasource is contributed as a
     * {@code ConnectionDetails} bean rather than by overriding that property. Resolving the fallback chain
     * therefore raised {@code PlaceholderResolutionException} for a value the branch would have discarded,
     * failing context startup for the whole integration tier. Reading the property inside the branch that
     * needs it keeps the {@code local} and {@code prod} inheritance behaviour - neither profile sets
     * {@code spring.flyway.url}, so both genuinely depend on the fallback - while leaving a single-role
     * profile free of a property it does not define.
     *
     * <p><b>Failure modes and troubleshooting.</b> A startup failure naming a missing metadata table means
     * either that the DDL role could not create it - check that {@code CARDDEMO_DB_MIGRATION_USER} and its
     * password reached the application, and that the role holds {@code CREATE} on the schema - or that
     * {@code initialize-schema} is {@code never} and nobody has applied the framework script. A startup
     * failure naming a table that exists means the runtime role lacks {@code SELECT} on it, which the
     * {@code ALTER DEFAULT PRIVILEGES} declarations in {@code docker-compose.yml} exist to prevent.
     *
     * @param dataSource the runtime, DML-only DataSource the job repository will use; never {@code null}
     * @param batchProperties the framework's own batch properties, supplying the initialisation mode, the
     *     table prefix and the optional platform override
     * @param environment the property source the DDL connection URL is read from, on the two-role branch
     *     only; never {@code null}
     * @param migrationUser the DDL-owning role, {@code spring.flyway.user}; blank in the single-role case
     * @param migrationPassword the DDL-owning role's password, {@code spring.flyway.password}; blank in the
     *     single-role case
     * @return the initialiser, never {@code null}
     * @throws IllegalStateException if the configured table prefix is not a plain SQL identifier, or if any
     *     metadata table is missing or unreadable by the runtime role once initialisation has finished
     */
    @Bean
    public BatchDataSourceScriptDatabaseInitializer batchMetadataInitializer(
            final DataSource dataSource,
            final BatchProperties batchProperties,
            final Environment environment,
            @Value("${spring.flyway.user:}") final String migrationUser,
            @Value("${spring.flyway.password:}") final String migrationPassword) {

        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(batchProperties, "batchProperties must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        final String tablePrefix = requireIdentifier(resolveTablePrefix(batchProperties));
        final DataSource ddlDataSource =
                metadataDdlDataSource(dataSource, environment, migrationUser, migrationPassword);

        // An anonymous subclass rather than a second bean: the verification has to run AFTER the script and
        // BEFORE anything that depends on database initialisation, and initializeDatabase() is exactly that
        // point. A separate runner would have to re-establish the ordering the framework already gives here,
        // and a runner is forbidden in this class for the unrelated reason that it must never launch a job.
        return new BatchDataSourceScriptDatabaseInitializer(ddlDataSource, batchProperties.getJdbc()) {

            @Override
            public boolean initializeDatabase() {
                final boolean applied = super.initializeDatabase();
                verifyMetadataReachable(dataSource, tablePrefix);
                return applied;
            }
        };
    }

    /**
     * Resolves the metadata table prefix the way the job repository itself resolves it.
     *
     * <p>{@code spring.batch.jdbc.table-prefix} carries no default in the framework's own properties class -
     * the field is left {@code null} - and the {@code BATCH_} default lives one layer down, in
     * {@link AbstractJdbcBatchMetadataDao#DEFAULT_TABLE_PREFIX}, applied only when the property has text.
     * Reading the property directly therefore yields an empty prefix on every deployment that does not set
     * it, which is every deployment in this repository: {@code application.yml} leaves it at the default
     * deliberately so the schema matches the framework's published script unmodified.
     *
     * <p>The consequence of getting this wrong is not a cosmetic one, which is why it is resolved here rather
     * than inline. An empty prefix makes the verification probe ask for {@code JOB_INSTANCE}, a table that
     * exists in no deployment, so a correctly provisioned schema would be reported as missing and startup
     * would fail naming a table nobody was ever meant to create. The constant is referenced rather than
     * copied so that the prefix this class probes for and the prefix the job repository writes to cannot
     * drift apart.
     *
     * @param batchProperties the framework's own batch properties
     * @return the configured prefix when one has text, and the framework default otherwise; never
     *     {@code null}
     */
    private static String resolveTablePrefix(final BatchProperties batchProperties) {
        final String configured = batchProperties.getJdbc().getTablePrefix();
        return StringUtils.hasText(configured)
                ? configured
                : AbstractJdbcBatchMetadataDao.DEFAULT_TABLE_PREFIX;
    }

    /**
     * Chooses the connection the metadata script runs under: the DDL-owning role when one is configured.
     *
     * <p>A {@link DriverManagerDataSource} rather than a pooled one, deliberately. This connection is used
     * once, during startup, for a handful of {@code CREATE} statements; a second Hikari pool would live for
     * the lifetime of the application holding idle connections nothing will ever use again, and closing one
     * from a {@code @Bean} method would mean owning a lifecycle this class has no reason to own.
     *
     * <p>The role is tested <b>before</b> the URL is read, and that order is load-bearing rather than
     * incidental. A single-role profile defines no {@code spring.flyway.user}, so it returns here without
     * ever touching {@code spring.datasource.url} - which under Testcontainers still holds the unresolvable
     * {@code ${POSTGRES_HOST}} template from {@code application.yml:581}. Reading the URL first, as an
     * eagerly resolved {@code @Value} argument once did, failed those contexts on a value this branch
     * discards.
     *
     * @param runtimeDataSource the runtime DataSource, used when no separate DDL role is configured
     * @param environment the property source the DDL connection URL is read from, only once a DDL role is
     *     known to be configured
     * @param user the DDL-owning role; blank falls back to the runtime DataSource
     * @param password the DDL-owning role's password
     * @return the DataSource the metadata script runs under, never {@code null}
     */
    private static DataSource metadataDdlDataSource(final DataSource runtimeDataSource,
            final Environment environment, final String user, final String password) {

        if (user == null || user.isBlank()) {
            LOG.debug("No separate migration role is configured, so the framework metadata script runs"
                    + " through the runtime DataSource; this is the single-role case");
            return runtimeDataSource;
        }
        final String url = environment.getProperty(
                "spring.flyway.url",
                environment.getProperty("spring.datasource.url", ""));
        if (url.isBlank()) {
            LOG.debug("A migration role is configured but neither spring.flyway.url nor"
                    + " spring.datasource.url carries a value, so the framework metadata script runs"
                    + " through the runtime DataSource");
            return runtimeDataSource;
        }
        final DriverManagerDataSource ddlDataSource = new DriverManagerDataSource(url, user, password);
        LOG.info("The Spring Batch metadata script will run as the DDL-owning role {}, exactly as the Flyway"
                + " migrations do; the runtime role keeps DML only", user);
        return ddlDataSource;
    }

    /**
     * Proves every metadata object a launch touches is present, and that the runtime role may mutate it.
     *
     * <p>Finding BAT-001, severity High. Three probes, in widening order, all through the
     * runtime {@link DataSource} because that is the principal whose access decides whether a job can run: a
     * table the DDL role created but the runtime role cannot write is as unusable as one that was never
     * created, and only a probe under the runtime credentials tells the two apart from a refusal to start.
     *
     * <ol>
     *   <li><b>The six tables exist and are readable.</b> The predicate is deliberately {@code where 1 = 0}:
     *       it proves existence and {@code SELECT} without reading a row, so the check costs nothing on a
     *       table holding a million executions and cannot log or retain any execution content.</li>
     *   <li><b>The runtime role holds {@link #REQUIRED_TABLE_PRIVILEGES} on each of those tables.</b> This is
     *       the half that was missing. A role with {@code SELECT} and nothing else passes probe one and then
     *       fails on the first {@code INSERT} of a job instance - a failure that arrives at launch time, in a
     *       batch window, rather than at startup where it belongs.</li>
     *   <li><b>The three sequences exist and the role holds {@link #REQUIRED_SEQUENCE_PRIVILEGES} on
     *       them.</b> Every identity in this schema comes from a sequence, so a missing {@code USAGE} grant
     *       breaks every launch while leaving all six tables looking perfectly healthy. The privilege call
     *       raises on a sequence that does not exist, so it proves existence and access in one statement.</li>
     * </ol>
     *
     * <p><b>Probes two and three are PostgreSQL-specific and are skipped elsewhere</b>, with a warning naming
     * the product that was found. {@code has_table_privilege} and {@code has_sequence_privilege} are
     * PostgreSQL functions; this deployment is PostgreSQL in every profile, including the Testcontainers
     * tier, so the skip is a guard against a future substrate rather than a live path. Skipping is the honest
     * behaviour there: refusing to start on an engine whose privilege model this code cannot read would
     * convert an unverifiable claim into an outage, and the existence probe still runs.
     *
     * <p>Object names are composed rather than bound because SQL has no parameter form for an identifier.
     * That is safe here for a stated reason rather than by assumption: the prefix has already been checked by
     * {@link #requireIdentifier(String)} to be a plain SQL identifier, and every suffix is a compile-time
     * constant in {@link #METADATA_TABLE_SUFFIXES} or {@link #METADATA_SEQUENCE_SUFFIXES}. The privilege
     * probes go further and bind the object name as a <em>literal</em> string parameter, which the two
     * functions parse as an identifier, so nothing is interpolated into those statements at all.
     *
     * @param dataSource the runtime, DML-only DataSource
     * @param tablePrefix the validated table prefix, {@code BATCH_} unless a deployment overrides it
     * @throws IllegalStateException if any object is missing, unreadable, or not mutable by the runtime role
     */
    private static void verifyMetadataReachable(final DataSource dataSource, final String tablePrefix) {
        final JdbcTemplate probe = new JdbcTemplate(dataSource);
        for (final String suffix : METADATA_TABLE_SUFFIXES) {
            final String table = tablePrefix + suffix;
            try {
                probe.execute("select 1 from " + table + " where 1 = 0");
            } catch (final DataAccessException unreachable) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "The Spring Batch metadata table %s is missing or unreadable by the runtime role, so"
                                + " no job could be launched or restarted and every generation handoff"
                                + " between pipeline stages would be lost. The framework owns this schema"
                                + " through spring.batch.jdbc.initialize-schema (never in the base and"
                                + " production profiles, always in local and test) and it must run as the"
                                + " role named by spring.flyway.user, because the runtime role holds DML"
                                + " only. Provision the six %s tables with"
                                + " org/springframework/batch/core/schema-postgresql.sql under that role,"
                                + " then grant the runtime role SELECT, INSERT, UPDATE and DELETE on them.",
                        table, tablePrefix), unreachable);
            }
        }
        LOG.info("All {} Spring Batch metadata tables under the prefix {} are present and readable by the"
                + " runtime role", Integer.valueOf(METADATA_TABLE_SUFFIXES.size()), tablePrefix);

        if (!isPostgreSql(probe)) {
            return;
        }
        for (final String suffix : METADATA_TABLE_SUFFIXES) {
            for (final String privilege : REQUIRED_TABLE_PRIVILEGES) {
                requirePrivilege(probe, "has_table_privilege", "table", tablePrefix + suffix, privilege,
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public");
            }
        }
        for (final String suffix : METADATA_SEQUENCE_SUFFIXES) {
            for (final String privilege : REQUIRED_SEQUENCE_PRIVILEGES) {
                requirePrivilege(probe, "has_sequence_privilege", "sequence", tablePrefix + suffix, privilege,
                        "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public");
            }
        }
        LOG.info("The runtime role holds {} on all {} metadata tables and {} on all {} metadata sequences"
                        + " under the prefix {}; the batch tier can be launched and restarted",
                REQUIRED_TABLE_PRIVILEGES, Integer.valueOf(METADATA_TABLE_SUFFIXES.size()),
                REQUIRED_SEQUENCE_PRIVILEGES, Integer.valueOf(METADATA_SEQUENCE_SUFFIXES.size()),
                tablePrefix);
    }

    /**
     * Reports whether the runtime connection is PostgreSQL, so the privilege probes can be skipped elsewhere.
     *
     * <p>The driver's own product name is asked rather than a property, because a property states what a
     * deployment intended and this decides which SQL dialect may be sent.
     *
     * <p>A connection that supplies no {@link DatabaseMetaData} at all - which a stubbed or minimal
     * {@link DataSource} does - is treated as an engine that did not name itself, and takes the same skip
     * path as a non-PostgreSQL product. Raising there would convert "this driver declines to describe
     * itself" into a refusal to start, which is a strictly worse outcome than performing the existence probe
     * and naming the skip in the log.
     *
     * @param probe the template over the runtime DataSource
     * @return {@code true} when the driver reports PostgreSQL
     * @throws IllegalStateException if the connection itself cannot be obtained, which means the runtime role
     *     cannot reach the database and nothing else in this check could have succeeded either
     */
    private static boolean isPostgreSql(final JdbcTemplate probe) {
        final String product;
        try {
            final ConnectionCallback<String> productName = connection -> {
                final DatabaseMetaData metaData = connection.getMetaData();
                return metaData == null ? null : metaData.getDatabaseProductName();
            };
            product = probe.execute(productName);
        } catch (final DataAccessException unreachable) {
            throw new IllegalStateException("The runtime role could not open a connection to read the"
                    + " database product name, so the Spring Batch metadata privileges cannot be verified."
                    + " Check that CARDDEMO_DB_APP_USER and its password reached the application.",
                    unreachable);
        }
        if (POSTGRESQL_PRODUCT_NAME.equalsIgnoreCase(product)) {
            return true;
        }
        LOG.warn("The database reports itself as {} rather than {}, so the Spring Batch metadata PRIVILEGE"
                        + " checks have been skipped: has_table_privilege and has_sequence_privilege are"
                        + " PostgreSQL functions. The {} metadata tables have been proven present and"
                        + " readable, but a missing INSERT, UPDATE or sequence USAGE grant will now surface"
                        + " at the first launch rather than here",
                product == null ? "an engine that did not name itself" : product, POSTGRESQL_PRODUCT_NAME,
                Integer.valueOf(METADATA_TABLE_SUFFIXES.size()));
        return false;
    }

    /**
     * Proves the runtime role holds one privilege on one metadata object, or fails startup naming both.
     *
     * <p>The object name is bound as a parameter, and PostgreSQL's privilege functions parse that string as an
     * identifier - so the unquoted upper-case name the framework's script creates folds to the same lower-case
     * relation the check asks about, and nothing is interpolated into the statement.
     *
     * <p>A {@code null} answer is treated as a failure rather than as absence of information: the functions
     * return {@code null} only when the current user cannot be resolved, which is not a state in which a
     * privilege may be assumed.
     *
     * @param probe the template over the runtime DataSource
     * @param function the PostgreSQL privilege function to call
     * @param objectKind the word for the object, used in the failure message
     * @param objectName the fully prefixed object name
     * @param privilege the single privilege to require
     * @param remediation the grant statement that fixes it, named in the failure message
     * @throws IllegalStateException if the privilege is absent, or if the check itself cannot be performed -
     *     which for a sequence includes the object not existing at all
     */
    private static void requirePrivilege(final JdbcTemplate probe, final String function,
            final String objectKind, final String objectName, final String privilege,
            final String remediation) {

        final Boolean held;
        try {
            held = probe.queryForObject("select " + function + "(?, ?)", Boolean.class, objectName, privilege);
        } catch (final DataAccessException unreachable) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "The Spring Batch metadata %s %s could not be checked for %s by the runtime role, which"
                            + " for a sequence means it does not exist: the framework's own"
                            + " org/springframework/batch/core/schema-postgresql.sql creates BATCH_JOB_SEQ,"
                            + " BATCH_JOB_EXECUTION_SEQ and BATCH_STEP_EXECUTION_SEQ alongside the six"
                            + " tables, and every identity in this schema comes from one of them. Apply that"
                            + " script as the role named by spring.flyway.user, then '%s TO the runtime"
                            + " role'.",
                    objectKind, objectName, privilege, remediation), unreachable);
        }
        if (!Boolean.TRUE.equals(held)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "The runtime role does not hold %s on the Spring Batch metadata %s %s. Existence and"
                            + " SELECT are not enough: a launch inserts a job instance, an execution and a"
                            + " step execution, every commit interval updates them, and each identity is"
                            + " drawn from a sequence - so this deployment would start cleanly and then fail"
                            + " on its first job, inside a batch window. Run '%s TO the runtime role' as the"
                            + " role that owns the schema; docker-compose.yml declares exactly that grant,"
                            + " together with the ALTER DEFAULT PRIVILEGES that keeps it true for objects the"
                            + " migration role creates later.",
                    privilege, objectKind, objectName, remediation));
        }
    }

    /**
     * Refuses a table prefix that is not a plain SQL identifier.
     *
     * <p>Nothing in this repository overrides the framework default, so this guard exists for the deployment
     * that one day does: a prefix carrying a quote, a semicolon or whitespace would be composed into the
     * probe statement above, and refusing it at startup is the one place that can be prevented rather than
     * detected.
     *
     * @param prefix the configured prefix, permitted to be empty but not {@code null}
     * @return the prefix unchanged
     * @throws IllegalStateException if the prefix contains anything but ASCII letters, digits or underscores
     */
    private static String requireIdentifier(final String prefix) {
        final String candidate = prefix == null ? "" : prefix;
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            final boolean permitted = character == '_'
                    || (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z');
            if (!permitted) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "spring.batch.jdbc.table-prefix must be a plain SQL identifier but '%s' carries the"
                                + " character '%s' at position %d",
                        candidate, Character.toString(character), Integer.valueOf(index + 1)));
            }
        }
        return candidate;
    }

    // THE QUEUE LISTENER THAT REPLACES THE JES2 INTERNAL READER.
    //
    // Finding, severity High, RESOLVED here. Three surfaces in this tree stated that a listener replaced the
    // JES2 internal reader - this class's own javadoc, application.yml's batch.job.enabled comment, and
    // com.cardemo.config.AwsConfig - and no listener existed anywhere: zero @SqsListener declarations, zero
    // MessageListenerContainer beans. com.cardemo.service.report.ReportSubmissionService published a typed
    // message to carddemo-report-jobs.fifo on every report submission and nothing ever drained it, so the
    // online half of app/cbl/CORPT00C.cbl worked and the batch half it exists to trigger did not. Every
    // submission accumulated in the queue until its retention expired.
    //
    // WHY THE LISTENER LIVES HERE, which is worth stating because three file schemas disagreed about it.
    // AwsConfig's schema says the consumer belongs to BatchPipelineOrchestrator and must not be declared in
    // AwsConfig. BatchPipelineOrchestrator's schema says the opposite - "do NOT declare an @SqsListener in
    // this file" - and its own contract asserts zero of them. ReportSubmissionService's schema says the bean
    // publishes only and that "the consumer is the batch side". Two of the three point at the batch side and
    // the third refuses it in itself, so it lands in the batch layer's configuration: this class, which owns
    // the shared infrastructure the jobs consume and whose javadoc already named this listener as one of the
    // two launch paths. The divergence from AwsConfig's pointer is deliberate and recorded rather than left
    // to be inferred from the absence of a listener there.
    //
    // WHY IT IS A NESTED CLASS rather than a method on this one. An @SqsListener method needs the launcher
    // and the job as collaborators, and this class's constructor takes a single window size that a large
    // number of slice tests construct directly. Widening that constructor to carry batch-launch
    // collaborators would make every one of those tests supply things they have no interest in. A nested
    // holder takes them through its own constructor instead, and adds no file to a package inventory that is
    // closed.
    //
    // WHY IT IS CONDITIONAL, on two properties rather than one.
    //
    // On the queue name, because a deployment that configures no queue has nothing to drain and an
    // @SqsListener bound to an unresolvable placeholder fails startup rather than degrading. That property is
    // set in every profile that has a queue and unset in the slice tests, which is also what keeps this bean
    // out of the bean inventories those tests pin.
    //
    // And on the ABSENCE of spring.batch.job.name, because an operator submission must not also be a queue
    // consumer. That is not a workaround for an inconvenience; it is the same separation JES2 and CICS had as
    // two address spaces, and dropping it breaks two things at once. A polling container holds non-daemon
    // threads, so a submission that succeeds never ends - the job finishes, the runner returns, and the
    // process sits there polling, which is the opposite of a submitted job freeing its initiator. And a
    // submitted batch process that also drained the queue would be a SECOND consumer alongside the online
    // tier, so one report submission could be launched twice, by two processes, from one message - exactly
    // the race the single-consumer contract exists to prevent.

    /**
     * The consumer that replaces the JES2 internal reader, drained from the queue that replaces {@code JOBS}.
     *
     * <p><b>What it does.</b> Binds one listener to the FIFO queue named by {@value #KEY_REPORT_QUEUE} and
     * launches {@code transactionReportJob} for each submission, carrying the report name and the two
     * ten-character dates the message holds. That is the whole of the mainframe path it replaces:
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at {@code app/cbl/CORPT00C.cbl:L517-L523} wrote eighty-byte
     * job cards to an extrapartition queue whose {@code DDNAME(INREADER)} was the JES2 internal reader, and
     * JES2 read the deck and initiated the job.
     *
     * <p><b>Inputs and outputs.</b> Input is one JSON message per submission, published by
     * {@code com.cardemo.service.report.ReportSubmissionService}. Output is a launched job execution, or a
     * logged decision not to launch. Nothing is returned to the queue and no reply is published.
     *
     * <p><b>Key configuration and defaults.</b> {@value #KEY_REPORT_QUEUE} names the queue and has no
     * default - a deployment without it has no listener at all rather than a listener on a guessed name.
     * {@code spring.batch.job.enabled} stays {@code false}: this path launches deliberately, and the
     * framework's launch-everything-on-boot behaviour would defeat the point of a queue.
     * {@value #KEY_REPORT_QUEUE_LISTENER_ENABLED} defaults to {@code true} and withdraws this listener when
     * set to {@code false}; {@value #KEY_LAUNCH_JOB} withdraws it too, because a submitted batch process must
     * not become a second reader of the queue that submitted work to it.
     *
     * <p><b>Exactly one consumer, and why that has to be enforced rather than assumed.</b> A FIFO message is
     * delivered to one reader. Any context holding a second reader turns "the message was published with
     * these dates" into a race, because whichever reader wins removes the message from the other's view. Two
     * situations make that concrete and both are handled by the condition above: a batch submission process,
     * which exists to run one job and must not drain the queue feeding the server, and a test context that
     * asserts producer-side behaviour by reading the queue itself. The latter is why
     * {@code application-test.yml} disables this listener - the shared integration harness publishes and then
     * inspects, so it must be the only reader.
     *
     * @param jobLauncher the container's launcher
     * @param transactionReportJob the report job this queue's messages name; the bean whose name is
     *     {@code transactionReportJob}, declared by {@code com.cardemo.batch.jobs.TransactionReportJob}
     * @param objectMapper the application object mapper, so the message is bound with the strict settings
     *     the base profile pins rather than with a mapper this class configures
     * @param clock the application clock, against which a submission's validity window is measured
     * @param envelopeSigningKey the application signing key, from {@value #KEY_ENVELOPE_SIGNING_KEY}, from
     *     which the single-purpose key that authenticates a submission is derived; there is no unsigned mode.
     *     It is consumed here and handed to the envelope, and neither this method nor the listener retains it
     * @return the listener holder, never {@code null}
     */
    @Bean
    @ConditionalOnExpression("'${" + KEY_REPORT_QUEUE + ":}' != '' and '${" + KEY_LAUNCH_JOB + ":}' == ''"
            + " and '${" + KEY_REPORT_QUEUE_LISTENER_ENABLED + ":true}' != 'false'")
    public ReportJobQueueListener reportJobQueueListener(
            final JobLauncher jobLauncher,
            @Qualifier(TRANSACTION_REPORT_JOB_BEAN_NAME) final Job transactionReportJob,
            final ObjectMapper objectMapper,
            final Clock clock,
            @Value("${" + KEY_ENVELOPE_SIGNING_KEY + "}") final String envelopeSigningKey) {

        // Finding SEC-001. The key is turned into a derived authenticator HERE and the listener never sees the
        // configured value, so no bean holds the application signing key in an unwipeable String field.
        return new ReportJobQueueListener(jobLauncher, transactionReportJob, objectMapper, clock,
                new ReportSubmissionService.JobSubmissionEnvelope(envelopeSigningKey));
    }

    /**
     * The JES2 internal reader, as one listener over the queue that replaced the {@code JOBS} data queue.
     *
     * <p><b>What it does.</b> Receives a report submission, turns it into job parameters, and launches the
     * report job exactly once per submission however many times the queue delivers the message.
     *
     * <p><b>How the once-only guarantee works,</b> because it is the part that is easy to get wrong. The
     * producer mints one {@code MessageDeduplicationId} per submission, and that identifier is carried into
     * the job parameters as an identifying value. Spring Batch derives job instance identity from the
     * identifying parameters, so a redelivery of the same message resolves to the <em>same</em> job instance
     * and the launcher refuses it - {@link JobInstanceAlreadyCompleteException} when the first attempt
     * finished, {@link JobExecutionAlreadyRunningException} when it is still running. Both refusals are
     * caught and logged as what they are: the queue's at-least-once delivery meeting an exactly-once
     * consumer. A FIFO queue redelivers whenever the visibility window expires before acknowledgement, so
     * this is an ordinary event and not an error.
     *
     * <p><b>How a submission is authenticated,</b> which is the other half of the same identifier's job.
     * Every message carries a code in {@value ReportSubmissionService.JobSubmissionEnvelope#SIGNATURE_HEADER}
     * that binds the payload, the deduplication identifier and a validity window together, and this listener
     * refuses anything the code does not cover - see
     * {@link ReportSubmissionService.JobSubmissionEnvelope#verify}. Binding the identifier is what stops the
     * same captured body being re-published under a fresh identifier to spawn a second job instance, and
     * binding the window is what stops an indefinite replay of the original; both were open before finding
     * SEC-002 was resolved. An exact replay inside the window still verifies, because it is the queue
     * redelivering and the once-only guarantee above already handles it.
     *
     * <p><b>Failure modes and troubleshooting.</b> A payload that cannot be bound, or that carries a date the
     * job's own validator refuses, is logged at {@code ERROR} and <em>consumed</em>. That choice is
     * deliberate and is the opposite of what a service with a dead-letter queue should do: this topology
     * declares none, so a rethrow would return the message to the queue, redeliver it, fail identically, and
     * occupy the listener forever - a poison message would stop every later submission behind it, because a
     * FIFO message group is ordered. Consuming it keeps the queue moving and leaves the evidence in the log.
     * A submission that never produces a job execution therefore has its reason in the log at {@code ERROR};
     * a submission that produces none and logs nothing means the listener is not running, which
     * {@value #KEY_REPORT_QUEUE} governs.
     */
    public static final class ReportJobQueueListener {

        /** The container's launcher, the only path to a job execution this class uses. */
        private final JobLauncher jobLauncher;

        /** The report job named by every message this queue carries. */
        private final Job transactionReportJob;

        /** The application object mapper, carrying the base profile's strict binding settings. */
        private final ObjectMapper objectMapper;

        /**
         * The time source a submission's validity window is measured against.
         *
         * <p>Injected rather than ambient for the same reason every other clock in this application is: a
         * freshness check read from the host would be unassertable under test, and finding SEC-002's remedy is
         * only a remedy if the window it enforces can be shown to close.
         */
        private final Clock clock;

        /**
         * The authenticator a presented code is verified against.
         *
         * <p><strong>Finding SEC-001, severity High, RESOLVED here.</strong> This field replaces a
         * {@code String} that held the application signing key for the life of the listener. The key is read
         * once, in {@link BatchConfig#reportJobQueueListener}, handed to
         * {@link ReportSubmissionService.JobSubmissionEnvelope} which derives a single-purpose key and destroys
         * its temporaries, and never reaches this class in configured form. Never logged and never rendered.
         */
        private final ReportSubmissionService.JobSubmissionEnvelope envelope;

        /**
         * Creates the listener.
         *
         * @param jobLauncher the container's launcher; never {@code null}
         * @param transactionReportJob the report job; never {@code null}
         * @param objectMapper the application object mapper; never {@code null}
         * @param clock the application clock; never {@code null}
         * @param envelope the authenticator built from the application signing key; never {@code null}
         */
        private ReportJobQueueListener(final JobLauncher jobLauncher, final Job transactionReportJob,
                final ObjectMapper objectMapper, final Clock clock,
                final ReportSubmissionService.JobSubmissionEnvelope envelope) {

            this.jobLauncher = Objects.requireNonNull(jobLauncher, "jobLauncher must not be null");
            this.transactionReportJob =
                    Objects.requireNonNull(transactionReportJob, "transactionReportJob must not be null");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            this.envelope = Objects.requireNonNull(envelope, "envelope must not be null");
        }

        /**
         * Drains one report submission and launches the report job for it.
         *
         * <p>The payload is taken as text and bound here rather than declared as the record type, so that a
         * binding failure is this method's to handle. Declaring the record would move the failure into the
         * framework's conversion step, where the only available outcomes are acknowledge-everything or
         * redeliver-forever, and neither is right for a topology with no dead-letter queue.
         *
         * <p><b>Three decisions this method makes, and why each is what it is.</b>
         *
         * <p><b>Finding M-11 - the message is authenticated before it is acted on.</b> The body is bound
         * first, into a record of three strings whose own constructor refuses anything but the three source
         * report names and two dates of {@code yyyy-MM-dd} shape, and then its envelope code is verified
         * against a key derived from the application signing key - together with the deduplication identifier
         * the transport delivered and the window the code was issued for, which is finding SEC-002's remedy.
         * Binding before verifying is safe and is not
         * an ordering mistake: the converter contract declared by {@code com.cardemo.config.AwsConfig} means
         * no caller-selected type is ever resolved, so binding cannot instantiate anything but this record,
         * and the record's constructor is itself a validator. Nothing is launched, persisted or logged as a
         * submission until the code matches.
         *
         * <p><b>Finding M-03 - the producer's diagnostic context is restored for the launch.</b> The publisher
         * carries a correlation identifier and a W3C trace context as message headers; both are validated for
         * shape here - bounded length, visible ASCII only - and placed in the logging context around the
         * launch, then removed in a {@code finally} so they cannot leak onto the container's pooled thread and
         * label the next submission. Restoring them is what makes one report run traceable from the request
         * that submitted it, which is the whole point of publishing them.
         *
         * <p><b>Finding C-02 - only a terminal success is acknowledged.</b> The framework acknowledges a
         * message when this method returns normally, so returning after a launch whose execution ended
         * {@code FAILED} or {@code STOPPED} deleted the submission and left no record of it on the queue. The
         * outcome is therefore inspected, and a non-successful terminal status <em>throws</em>, which leaves
         * the message on the queue to be redelivered once its visibility window elapses. The same applies to a
         * delivery that arrives while an earlier one is still running: it is not a duplicate to discard, it is
         * a delivery that is simply early, so it is returned rather than consumed.
         *
         * <p>Two failures are still <em>consumed</em>, deliberately, and the distinction is between a message
         * that might succeed later and one that never can. A body that cannot be bound, an envelope code that
         * does not verify, and parameters the job refuses are all permanently unrunnable: redelivering them
         * would fail identically forever, and because this topology declares no dead-letter target and a
         * message group is ordered, one such message would stall every later submission behind it. Those are
         * logged at {@code ERROR} with their reason and dropped. Everything that could succeed on a later
         * attempt is returned to the queue.
         *
         * @param payload the raw JSON body, exactly as published
         * @param headers the message headers, carrying the envelope code, the propagated diagnostic context,
         *     the deduplication identifier that makes the launch idempotent, and the framework's own message
         *     identifier as a fallback
         * @param visibility the handle to this message's own invisibility window, used to restart the window
         *     at the moment processing actually begins rather than relying on what remained of it after the
         *     receive; tolerated as {@code null} so a directly-invoked test need not supply one
         * @throws IllegalStateException when the submission must be redelivered rather than acknowledged -
         *     a launch that ended unsuccessfully, or a delivery that overtook a still-running execution
         */
        @SqsListener(queueNames = "${" + KEY_REPORT_QUEUE + "}", id = REPORT_QUEUE_LISTENER_ID,
                pollTimeoutSeconds = REPORT_QUEUE_POLL_TIMEOUT_SECONDS,
                messageVisibilitySeconds = REPORT_QUEUE_VISIBILITY_SECONDS)
        public void drainReportJobQueue(final String payload,
                @Headers final Map<String, Object> headers,
                final Visibility visibility) {

            final String submissionId = submissionIdentifier(headers);
            // FINDING C-02, severity BLOCKER. Every record below names the delivery by this locally minted
            // identifier rather than by the transport's own. See mintedDeliveryId(String) for why.
            final String deliveryId = mintedDeliveryId(submissionId);
            final JobSubmissionMessage submission;
            try {
                submission = this.objectMapper.readValue(payload, JobSubmissionMessage.class);
            } catch (final ValueInstantiationException outOfContract) {
                // The body was well-formed JSON and JobSubmissionMessage's own constructor refused the values:
                // a field was absent, the report name was outside the three periods the screen offers, or a
                // date was not the ten-character dashed form the source's parameter cards carry. Reported
                // separately from a parse failure because the two mean different things to an operator - this
                // one says a publisher is not honouring the published contract, and the mapper wraps a
                // constructor's own exception in this type rather than letting it propagate.
                //
                // Discarded on the same terms as a parse failure, and for the same reason: no dead-letter
                // target exists, so returning the message would stall its FIFO group. Neither the exception's
                // message nor its cause is logged - the wrapped message names the offending field and can quote
                // the value, which is exactly what this remediation withholds.
                LOG.error("Report job delivery {} carried values outside the published submission contract and"
                                + " has been discarded, reason {}. The three report periods and the"
                                + " ten-character parameter dates are fixed by app/cbl/CORPT00C.cbl, so a"
                                + " delivery that fails this check did not come from this application's"
                                + " submission surface.",
                        deliveryId, outOfContract.getClass().getSimpleName());
                return;
            } catch (final JsonProcessingException | IllegalArgumentException malformed) {
                // Consumed, not rethrown. See the failure-modes paragraph on this class: there is no
                // dead-letter queue, and a FIFO group is ordered, so returning this message would block
                // every submission behind it indefinitely. IllegalArgumentException is caught alongside the
                // binding failure because the record's own constructor raises it directly for a report name
                // outside the closed set of app/cbl/CORPT00C.cbl:L214, :L240 and :L433 and for a date of the
                // wrong shape - finding M-12 - and such a message is as permanently unrunnable as an
                // unparseable one.
                //
                // FINDING C-02, severity BLOCKER. The payload length is not logged either, and the binding
                // exception is no longer passed as the record's cause. A Jackson binding failure quotes the
                // offending token and its surrounding context in its message, so attaching it published the
                // very bytes this remediation withholds. The exception's TYPE names the failure class, which
                // is what an operator acts on; the bytes stay on the queue's own retention.
                LOG.error("Report job delivery {} could not be bound and has been discarded, reason {}; the"
                                + " queue that replaces app/csd/CARDDEMO.CSD DEFINE TDQUEUE(JOBS) has no"
                                + " dead-letter target, so returning it would stall every later submission"
                                + " in the same FIFO group.",
                        deliveryId, malformed.getClass().getSimpleName());
                return;
            }

            // FINDING SEC-002, severity HIGH. The code is verified against the submission identifier the
            // transport delivered and against its own validity window, not against the payload alone. That
            // closes two replays the previous check admitted: the same captured body and code re-published
            // under a FRESH identifier - which would have produced a second job instance, because the
            // identifier is an identifying job parameter - and an indefinite replay of the original, which was
            // previously bounded only by whatever the job repository still remembered.
            final ReportSubmissionService.JobSubmissionEnvelope.Verification verification =
                    this.envelope.verify(submission, submissionId,
                            headers == null ? null
                                    : headers.get(
                                            ReportSubmissionService.JobSubmissionEnvelope.SIGNATURE_HEADER),
                            this.clock.instant());
            if (!verification.verified()) {
                // Consumed. A submission that fails this check will never pass it - a wrong code stays wrong
                // and an expired window does not reopen - so redelivering it would stall the group; and no
                // part of the message is echoed, because every field of a message that failed verification is
                // attacker-controlled. The OUTCOME is logged, which is this application's own classification
                // and not input: an operator needs to tell a stale submission from a forged one, and those are
                // different incidents.
                LOG.error("Report job delivery {} carries no valid envelope code and has been discarded"
                                + " without launching anything, outcome {}. The code proves a submission was"
                                + " published by this application, for this delivery identifier, within its"
                                + " validity window - which is the only control that distinguishes one"
                                + " principal from another on an emulator queue that enforces no"
                                + " authorisation of its own.", deliveryId, verification);
                return;
            }

            final JobParameters parameters = new JobParametersBuilder()
                    .addString(TransactionReportProcessor.START_DATE_JOB_PARAMETER, submission.startDate())
                    .addString(TransactionReportProcessor.END_DATE_JOB_PARAMETER, submission.endDate())
                    .addString(REPORT_NAME_JOB_PARAMETER, submission.reportName())
                    .addString(SUBMISSION_ID_JOB_PARAMETER, submissionId)
                    .toJobParameters();

            // Finding M-02. The window is extended immediately before the launch rather than relied on from
            // the receive, so the full processing interval is measured from the moment work actually starts.
            // A failure to extend is logged and the launch proceeds: the annotation already applied the same
            // window at receive time, so the extension is a refresh rather than the only guard, and refusing
            // to run a validated submission over it would be the worse outcome.
            extendVisibility(visibility, deliveryId);

            final Map<String, String> restored = restoreDiagnosticContext(headers, deliveryId);
            try {
                launchAndInspect(deliveryId, parameters);
            } finally {
                releaseDiagnosticContext(restored);
            }
        }

        /**
         * Refreshes the message's invisibility window to cover the whole processing interval.
         *
         * <p>Finding M-02. The handle is optional in the framework's argument resolution, so a {@code null} is
         * tolerated: a caller invoking this method directly - every unit test does - has no queue behind it and
         * nothing to extend.
         *
         * @param visibility the framework's handle on this message's window, possibly {@code null}
         * @param deliveryId the derived, non-disclosing identifier that names this delivery in the diagnostic
         */
        private static void extendVisibility(final Visibility visibility, final String deliveryId) {
            if (visibility == null) {
                return;
            }
            try {
                visibility.changeTo(REPORT_QUEUE_VISIBILITY);
            } catch (final RuntimeException refused) {
                LOG.warn("Report job delivery {} could not have its visibility window extended to {}"
                                + " seconds; the window applied at receive time still stands, so the launch"
                                + " proceeds", deliveryId, Integer.valueOf(REPORT_QUEUE_VISIBILITY),
                        refused);
            }
        }

        /**
         * Launches the report job and turns its terminal outcome into an acknowledgement decision.
         *
         * <p>Split out from the listener method so that the decision is one readable sequence rather than a
         * tail of catch blocks: the launch, the terminal status, and then the two dispositions.
         *
         * @param deliveryId the derived, non-disclosing identifier every record of this delivery is named
         *     by; see {@link #mintedDeliveryId(String)}
         * @param parameters the identifying parameters built from the submission, which carry the transport
         *     identifier as the idempotency key without ever logging it
         * @throws IllegalStateException when the message must be redelivered rather than acknowledged
         */
        private void launchAndInspect(final String deliveryId, final JobParameters parameters) {

            try {
                final JobExecution execution = this.jobLauncher.run(this.transactionReportJob, parameters);
                // The JOBS queue is named without parentheses or quotes deliberately: the log masking rules
                // treat a quoted value in parentheses as a possible credential assignment and redact it, so
                // the source-citing form EXEC CICS WRITEQ TD QUEUE('JOBS') would reach the log with its
                // literal replaced. The locator carries the same information and survives masking intact.
                //
                // FINDING C-02, severity BLOCKER. The report name and the two dates used to be interpolated
                // here. They are bound from a queue body, so before JobSubmissionMessage screened them a
                // publisher could put a card number in the name or a date of birth in a date and have it
                // written at INFO. They are screened now AND they are still not logged, because two
                // independent controls are what a confidentiality boundary needs: the execution identifier
                // below is the key into the job repository, where the parameters of the run are recorded under
                // the repository's own access control rather than in a log stream.
                LOG.info("Report job delivery {} launched {} as execution {}, replacing the JES2 internal"
                                + " reader fed by the EXEC CICS WRITEQ TD to the JOBS queue at"
                                + " app/cbl/CORPT00C.cbl:L517-L523. The period is recorded as job parameters"
                                + " of that execution and is deliberately not repeated here.",
                        deliveryId, this.transactionReportJob.getName(), execution.getId());
                requireTerminalSuccess(execution, deliveryId);
            } catch (final JobInstanceAlreadyCompleteException alreadyDone) {
                LOG.info("Report job delivery {} names a submission already processed to completion, so this"
                                + " delivery is a redelivery and no second execution has been started."
                                + " The queue guarantees at-least-once delivery; the deduplication"
                                + " identifier carried as an identifying job parameter is what makes this"
                                + " consumer exactly-once.", deliveryId);
            } catch (final JobExecutionAlreadyRunningException stillRunning) {
                // Finding C-02. NOT consumed. An execution that is still running has not yet succeeded or
                // failed, so acknowledging this delivery would delete the only record that the submission
                // was made while its outcome was still unknown. Returning it means the queue redelivers it
                // after the visibility window, by which time the running execution has a terminal status and
                // this consumer reaches either the already-complete arm above or a genuine retry.
                throw new IllegalStateException("Report job delivery " + deliveryId + " overtook an"
                        + " execution that is still running; the delivery is returned to the queue rather"
                        + " than acknowledged, so the submission survives until an outcome exists",
                        stillRunning);
            } catch (final JobParametersInvalidException refused) {
                // FINDING C-02, severity BLOCKER. refused.getMessage() named the offending parameter VALUE,
                // and the exception was attached as the record's cause, so the value reached the log twice.
                // The reason code below is the exception type; the two validators disagreeing is a code defect
                // an operator reports rather than a value they inspect.
                LOG.error("Report job delivery {} carried parameters the {} job refuses and has been"
                                + " discarded, reason {}. The period is validated on submission by"
                                + " com.cardemo.service.report.ReportSubmissionService and again by"
                                + " JobSubmissionMessage, so a delivery that reaches here and is refused"
                                + " indicates those validators and the job's own validator disagree.",
                        deliveryId, this.transactionReportJob.getName(),
                        refused.getClass().getSimpleName());
            } catch (final JobRestartException refused) {
                LOG.error("Report job delivery {} names a job instance that cannot be restarted and has"
                                + " been discarded, reason {}.", deliveryId,
                        refused.getClass().getSimpleName());
            }
        }

        /**
         * Refuses to acknowledge a submission whose execution did not finish successfully.
         *
         * <p>Finding C-02. The launcher returns the execution rather than throwing when a step fails, so a
         * method that ignored the returned object treated a failed report run as a delivered one. Both the
         * batch status and the exit code are consulted: a step that ends with the {@code FAILED} exit code
         * without an exception leaves the batch status successful, which is exactly how this application's
         * own deciders report return code 8.
         *
         * <p>The recorded failures are attached as the cause of the raised exception, so nothing is lost:
         * the reason the run failed travels with the reason the message was not acknowledged.
         *
         * @param execution the finished execution
         * @param deliveryId the derived, non-disclosing identifier that names this delivery in the diagnostic
         * @throws IllegalStateException if the execution did not end in success
         */
        private static void requireTerminalSuccess(final JobExecution execution, final String deliveryId) {
            final boolean unsuccessfulStatus = execution.getStatus().isUnsuccessful();
            final String exitCode = execution.getExitStatus() == null
                    ? ExitStatus.UNKNOWN.getExitCode()
                    : execution.getExitStatus().getExitCode();
            final boolean unsuccessfulExit = ExitStatus.FAILED.getExitCode().equals(exitCode)
                    || ExitStatus.UNKNOWN.getExitCode().equals(exitCode);
            if (!unsuccessfulStatus && !unsuccessfulExit) {
                return;
            }

            final IllegalStateException redeliver = new IllegalStateException("Report job delivery "
                    + deliveryId + " ended with batch status " + execution.getStatus() + " and exit code "
                    + exitCode + "; the delivery is returned to the queue rather than acknowledged, because"
                    + " acknowledging it would delete the submission that produced no report");
            execution.getAllFailureExceptions().forEach(redeliver::addSuppressed);
            LOG.error("Report job delivery {} did not complete successfully and has NOT been"
                    + " acknowledged", deliveryId, redeliver);
            throw redeliver;
        }

        /**
         * Validates and installs the producer's diagnostic context for the launch scope.
         *
         * <p>Finding M-03. Both values are untrusted input, so each is accepted only when it is short enough
         * to be a plausible identifier and contains nothing but visible ASCII - which excludes every control
         * character and therefore every attempt to forge a log record through the logging context. A value
         * that fails either test is dropped silently rather than reported: it is not an error in the
         * submission, and reporting it would put the rejected bytes into the log the check exists to protect.
         *
         * @param headers the message headers, possibly {@code null}
         * @param deliveryId the derived, non-disclosing delivery identifier, always installed so that every
         *     line of a launch is attributable even when the producer propagated nothing. Finding C-02: the
         *     fallback is the derived value rather than the publisher's own, because an entry in the logging
         *     context is published on every record written inside the scope
         * @return the entries this call installed, so they can be removed again; never {@code null}
         */
        private static Map<String, String> restoreDiagnosticContext(final Map<String, Object> headers,
                final String deliveryId) {

            final Map<String, String> installed = new LinkedHashMap<>();
            installed.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID,
                    propagatable(headers, CorrelationIdFilter.CORRELATION_ID_HEADER)
                            .orElse(deliveryId));
            propagatable(headers, CorrelationIdFilter.TRACE_PARENT_HEADER)
                    .ifPresent(traceParent -> installed.put(MDC_KEY_TRACE_PARENT, traceParent));
            installed.forEach(MDC::put);
            return installed;
        }

        /**
         * Removes exactly the entries {@link #restoreDiagnosticContext(Map, String)} installed.
         *
         * <p>Removed rather than cleared: the container's threads are pooled and reused, so clearing the whole
         * context would discard entries another scope owns, and leaving these behind would label the next
         * submission with this one's identity.
         *
         * @param installed the entries to remove; never {@code null}
         */
        private static void releaseDiagnosticContext(final Map<String, String> installed) {
            installed.keySet().forEach(MDC::remove);
        }

        /**
         * Reads one header and accepts it only when it is a plausible, log-safe identifier.
         *
         * @param headers the message headers, possibly {@code null}
         * @param name the header to read
         * @return the value when it is non-blank, no longer than
         *     {@value BatchConfig#MAX_PROPAGATED_HEADER_LENGTH} characters and entirely visible ASCII,
         *     otherwise empty
         */
        private static Optional<String> propagatable(final Map<String, Object> headers, final String name) {
            if (headers == null) {
                return Optional.empty();
            }
            final Object value = headers.get(name);
            if (value == null) {
                return Optional.empty();
            }
            final String rendered = value.toString();
            if (rendered.isBlank() || rendered.length() > MAX_PROPAGATED_HEADER_LENGTH) {
                return Optional.empty();
            }
            for (int index = 0; index < rendered.length(); index++) {
                final char character = rendered.charAt(index);
                if (character < MIN_PROPAGATED_HEADER_CHAR || character > MAX_PROPAGATED_HEADER_CHAR) {
                    return Optional.empty();
                }
            }
            return Optional.of(rendered);
        }

        /**
         * Derives the identifier this listener logs a delivery by.
         *
         * <p><strong>Finding C-02, severity Blocker.</strong> No record this listener writes may name
         * the delivery by {@link #submissionIdentifier(Map)}, which is the queue's deduplication identifier -
         * a value the <em>publisher</em> sets. A publisher is not necessarily this application's submission
         * surface, so that value is untrusted free text: a card number, a password, a customer name or a
         * government identifier placed in it would be written straight to the log, past a masking layer that
         * redacts only labelled values.
         *
         * <p>The remedy is not to validate the transport identifier - it has no grammar this application owns -
         * but to stop publishing it. What is logged instead is a value derived from it by a one-way function,
         * rendered as fixed-width lowercase hexadecimal. That keeps every property an operator needs from an
         * identifier: it is stable across redeliveries of one submission, because the input is; it is different
         * for different submissions; and it is joinable across the several records one delivery produces. What
         * it does not keep is the ability to read the input back out of it.
         *
         * <p>{@code SHA-256} is chosen because it is present in every JRE, so this cannot fail to resolve, and
         * the first eight bytes are kept because sixteen hexadecimal characters is short enough to read in a log
         * line while leaving collision between two concurrently-live deliveries a non-consideration. It is a
         * <em>diagnostic</em> digest, not a security primitive: nothing authenticates or authorises on it.
         *
         * <p>The transport identifier itself is untouched in its load-bearing role - it remains the identifying
         * job parameter that makes this consumer exactly-once - because that value never reaches a log stream.
         *
         * @param submissionId the transport identifier, never {@code null}
         * @return sixteen lowercase hexadecimal characters, never {@code null}
         */
        private static String mintedDeliveryId(final String submissionId) {
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(DELIVERY_ID_DIGEST_ALGORITHM);
            } catch (final NoSuchAlgorithmException impossible) {
                // Every conforming Java runtime provides SHA-256, so this cannot happen. It is not swallowed:
                // a diagnostic identifier that cannot be derived means the runtime is not the one this
                // application is specified against, which must surface rather than degrade to logging the
                // untrusted value.
                throw new IllegalStateException(
                        DELIVERY_ID_DIGEST_ALGORITHM + " is required by the Java platform specification",
                        impossible);
            }
            final byte[] hashed = digest.digest(submissionId.getBytes(StandardCharsets.UTF_8));
            final StringBuilder rendered = new StringBuilder(DELIVERY_ID_HEX_LENGTH);
            for (int index = 0; index < DELIVERY_ID_HEX_LENGTH / 2; index++) {
                rendered.append(String.format(Locale.ROOT, "%02x", Byte.valueOf(hashed[index])));
            }
            return rendered.toString();
        }

        /**
         * Chooses the identifier that makes one submission one job instance.
         *
         * <p>The deduplication identifier is preferred because the producer mints exactly one per
         * submission and the queue carries it unchanged through every redelivery, which is precisely the
         * property an idempotency key needs. The framework's own message identifier is the fallback for a
         * non-FIFO queue, where no deduplication identifier exists; it is stable across redeliveries of one
         * message too, so the guarantee survives, and the reason it is not the first choice is that a
         * transport-level retry of the <em>publish</em> would produce two message identifiers for one
         * submission where the deduplication identifier produces one.
         *
         * @param headers the message headers; may be {@code null}
         * @return a non-blank identifier, never {@code null}
         */
        private static String submissionIdentifier(final Map<String, Object> headers) {
            if (headers == null) {
                return UNIDENTIFIED_SUBMISSION;
            }
            final Object deduplicationId =
                    headers.get(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_DEDUPLICATION_ID_HEADER);
            if (deduplicationId != null && !deduplicationId.toString().isBlank()) {
                return deduplicationId.toString();
            }
            final Object messageId = headers.get(MessageHeaders.ID);
            if (messageId != null && !messageId.toString().isBlank()) {
                return messageId.toString();
            }
            return UNIDENTIFIED_SUBMISSION;
        }
    }

    // THERE IS DELIBERATELY NO PROCESSOR, READER OR WRITER FACTORY IN THIS CLASS.
    //
    // A @Bean @StepScope transactionReportProcessor factory stood here and has been removed. It could not
    // coexist with the component it built: at the time, com.cardemo.batch.processors.TransactionReportProcessor
    // was annotated @Component @StepScope, whose default bean name is "transactionReportProcessor" - the same
    // name this factory method carried. spring.main.allow-bean-definition-overriding is false in the base
    // profile, so the pair was a startup failure rather than a harmless redundancy, and the arity of the
    // processor's constructor had moved on from the call this factory made.
    //
    // That name collision is now gone, because those annotations are gone: under finding F-008 both
    // TransactionReportProcessor and TransactionBackupReader lost @Component and @StepScope, since their only
    // owner - com.cardemo.batch.jobs.TransactionReportJob - always constructed them with new and never
    // resolved the bean. The factory is still NOT reinstated, and the reason has outlasted the collision. The
    // job's steps are tasklets that drive open/read/update/close by hand to reproduce the six-paragraph
    // lifecycle of app/cbl/CBTRN03C.cbl:L163-L212, so there is no chunk-oriented slot for the container to
    // fill; one scoped definition could not serve both reader call sites, because STEP01R reads the cluster
    // and STEP10R reads a generation; and construction validates, so behind a scoped proxy a rejected
    // DATEPARM pair or a malformed generation handoff would arrive as a BeanCreationException at first method
    // call instead of as the typed CardDemoException the failing step reports and acts on.
    //
    // Every OTHER reader, processor and writer under batch/** is registered by @Component on the class -
    // thirteen of them: six readers, four processors and three writers - and each is injected as a @Bean Step
    // method parameter by the job that drives it, so the container enforces the step scope those thirteen
    // declare. The two exceptions are the pair named above, whose ownership is documented on their own
    // classes and on TransactionReportJob rather than left to inference. What this class owns is the
    // FileService.Dataset bindings below, which have no component to annotate because they are one binding
    // per DD name rather than one class per role.


    /**
     * The {@code TRNXFILE} binding: the projected work cluster of {@code app/jcl/CREASTMT.JCL:STEP010} and
     * {@code STEP020}, which {@code STEP040} reads at {@code :L83}.
     *
     * <p>It takes the object store rather than the {@code TRANSACT} repository, because the DD is bound to the
     * projected work cluster and not to the sort's input - see {@link TrnxFileDataset} for the finding this
     * resolves.
     *
     * @param objectStorage the object store holding the projected object
     * @param workBucket the bucket the sort step writes the projected object to, the same property
     *     {@code StatementGenerationJob} publishes it under
     * @return the sequential binding for {@link FileService.Dd#TRNXFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset trnxFileDataset(final S3Operations objectStorage,
            @Value("${carddemo.aws.s3.batch-output-bucket}") final String workBucket) {
        return new TrnxFileDataset(objectStorage, workBucket);
    }

    /**
     * The {@code XREFFILE} binding: the cross-reference cluster read in record-key order.
     *
     * @param cardCrossReferenceRepository the {@code CARDXREF} cluster
     * @return the sequential binding for {@link FileService.Dd#XREFFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset xrefFileDataset(
            final CardCrossReferenceRepository cardCrossReferenceRepository) {
        return new XrefFileDataset(cardCrossReferenceRepository, windowSize);
    }

    /**
     * The {@code CUSTFILE} binding: keyed reads of the customer cluster.
     *
     * @param customerRepository the {@code CUSTDATA} cluster
     * @return the random-access binding for {@link FileService.Dd#CUSTFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset custFileDataset(final CustomerRepository customerRepository) {
        return new CustFileDataset(customerRepository);
    }

    // F-020: THE FOUR READ-ONLY DATASET VERIFICATION STEPS.
    //
    // Finding, severity High. app/cbl/CBACT01C.cbl (193 lines), CBACT02C.cbl (178),
    // CBACT03C.cbl (178) and CBCUS01C.cbl (178) each have a verb inventory of OPEN, READ and CLOSE only -
    // no WRITE, no REWRITE, no DELETE anywhere - and each has its own JCL member: app/jcl/READACCT.jcl,
    // READCARD.jcl, READXREF.jcl and READCUST.jcl. The four readers that translate them
    // (com.cardemo.batch.readers.AccountReader, CardReader, CardCrossReferenceReader, CustomerReader) are
    // fully tested, but a reader no Step consumes is a production component unreachable in a deployed
    // application, and the four JCL members would have no executable counterpart. That is
    // both an F-020 gap and, because such readers are reachable from nothing, dead code under Rule 1
    // clause B.
    //
    // WHY THE STEPS LIVE HERE AND NOT IN batch/jobs. The specification is explicit that these become
    // "read-only verification steps wired through the four readers ... NOT four more jobs", and the batch
    // package inventory is a fixed shape a gate asserts: six jobs, five processors, seven readers, three
    // writers. Four new files under batch/jobs would break that shape and would also invent four jobs the
    // specification rules out. The steps are therefore declared here, in the configuration seam that
    // already owns the batch tier's shared wiring, and ONE composed job gives them an execution vehicle -
    // because a Step cannot be launched on its own, and a step nothing can launch is exactly the
    // unreachability being fixed.
    //
    // WHY THERE IS NO PROCESSOR AND NO PERSISTING WRITER. The four programs read and DISPLAY; they compute
    // nothing and store nothing. Each reader already emits the per-record diagnostic its program does, so
    // the only thing left for the step to own is the count - which is what the source's own end-of-run
    // DISPLAY reports. VerificationWriter therefore counts and publishes, and holds no repository, no
    // object-store client and no EntityManager, so the read-only property is structural rather than
    // asserted: there is nothing present that could write.
    //
    // ONE DELIBERATE DIVERGENCE, and it goes the other way from a parity break. CBACT02C DISPLAYs the whole
    // CARD-RECORD, which carries CARD-NUM PIC X(16), and CBCUS01C DISPLAYs the whole CUSTOMER-RECORD, which
    // carries CUST-SSN. Reproducing those two DISPLAY statements literally would publish a primary account
    // number and a national identifier to the log on every row, which Rule 1 clause D forbids outright. The
    // readers already resolve this the same way, by emitting an identifier-only projection, and these steps
    // add nothing to it.

    /**
     * Bean name of the {@code READACCT} verification step, from {@code app/jcl/READACCT.jcl}.
     */
    public static final String READ_ACCOUNT_STEP_BEAN_NAME = "datasetVerificationReadAccountStep";

    /**
     * Bean name of the {@code READCARD} verification step, from {@code app/jcl/READCARD.jcl}.
     */
    public static final String READ_CARD_STEP_BEAN_NAME = "datasetVerificationReadCardStep";

    /**
     * Bean name of the {@code READXREF} verification step, from {@code app/jcl/READXREF.jcl}.
     */
    public static final String READ_CROSS_REFERENCE_STEP_BEAN_NAME =
            "datasetVerificationReadCrossReferenceStep";

    /**
     * Bean name of the {@code READCUST} verification step, from {@code app/jcl/READCUST.jcl}.
     */
    public static final String READ_CUSTOMER_STEP_BEAN_NAME = "datasetVerificationReadCustomerStep";

    /**
     * Bean name of the job that runs the four verification steps in the running order of their JCL members.
     */
    public static final String DATASET_VERIFICATION_JOB_BEAN_NAME = "datasetVerificationJob";

    /**
     * Suffix of the step execution context entry each verification step publishes its row count under.
     *
     * <p>Published rather than merely logged so an integration test, and an operator reading the metadata
     * tables, can read the count a step observed without parsing a log line. The full key is the step's bean
     * name followed by this suffix, so four steps in one job cannot overwrite one another's count.
     */
    public static final String VERIFIED_ROW_COUNT_SUFFIX = ".rowsVerified";

    /**
     * {@code app/jcl/READACCT.jcl} {@code STEP05 EXEC PGM=CBACT01C}: the account cluster, read end to end.
     *
     * <p>Chunk-oriented rather than a tasklet so the framework owns the read loop, exactly as it does for
     * every other reader in this tier, and so a restart re-enters at the reader's own checkpoint rather than
     * at the top of the file.
     *
     * @param accountReader the {@code ACCTFILE} sequential reader, injected by type because the readers
     *     package declares exactly one; must not be {@code null}
     * @param jobRepository the metadata store the step records against; must not be {@code null}
     * @param transactionManager the manager the chunk boundary commits against; must not be {@code null}
     * @return the verification step, never {@code null}
     */
    @Bean(READ_ACCOUNT_STEP_BEAN_NAME)
    public Step datasetVerificationReadAccountStep(final AccountReader accountReader,
            final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {

        return verificationStep(READ_ACCOUNT_STEP_BEAN_NAME, "ACCTFILE", "CBACT01C",
                Objects.requireNonNull(accountReader, "accountReader must not be null"),
                jobRepository, transactionManager);
    }

    /**
     * {@code app/jcl/READCARD.jcl} {@code STEP05 EXEC PGM=CBACT02C}: the card cluster, read end to end.
     *
     * @param cardReader the {@code CARDFILE} sequential reader; must not be {@code null}
     * @param jobRepository the metadata store the step records against; must not be {@code null}
     * @param transactionManager the manager the chunk boundary commits against; must not be {@code null}
     * @return the verification step, never {@code null}
     */
    @Bean(READ_CARD_STEP_BEAN_NAME)
    public Step datasetVerificationReadCardStep(final CardReader cardReader,
            final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {

        return verificationStep(READ_CARD_STEP_BEAN_NAME, "CARDFILE", "CBACT02C",
                Objects.requireNonNull(cardReader, "cardReader must not be null"),
                jobRepository, transactionManager);
    }

    /**
     * {@code app/jcl/READXREF.jcl} {@code STEP05 EXEC PGM=CBACT03C}: the cross-reference, read end to end.
     *
     * @param cardCrossReferenceReader the {@code XREFFILE} sequential reader; must not be {@code null}
     * @param jobRepository the metadata store the step records against; must not be {@code null}
     * @param transactionManager the manager the chunk boundary commits against; must not be {@code null}
     * @return the verification step, never {@code null}
     */
    @Bean(READ_CROSS_REFERENCE_STEP_BEAN_NAME)
    public Step datasetVerificationReadCrossReferenceStep(
            final CardCrossReferenceReader cardCrossReferenceReader,
            final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {

        return verificationStep(READ_CROSS_REFERENCE_STEP_BEAN_NAME, "XREFFILE", "CBACT03C",
                Objects.requireNonNull(cardCrossReferenceReader,
                        "cardCrossReferenceReader must not be null"),
                jobRepository, transactionManager);
    }

    /**
     * {@code app/jcl/READCUST.jcl} {@code STEP05 EXEC PGM=CBCUS01C}: the customer cluster, read end to end.
     *
     * @param customerReader the {@code CUSTFILE} sequential reader; must not be {@code null}
     * @param jobRepository the metadata store the step records against; must not be {@code null}
     * @param transactionManager the manager the chunk boundary commits against; must not be {@code null}
     * @return the verification step, never {@code null}
     */
    @Bean(READ_CUSTOMER_STEP_BEAN_NAME)
    public Step datasetVerificationReadCustomerStep(final CustomerReader customerReader,
            final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {

        return verificationStep(READ_CUSTOMER_STEP_BEAN_NAME, "CUSTFILE", "CBCUS01C",
                Objects.requireNonNull(customerReader, "customerReader must not be null"),
                jobRepository, transactionManager);
    }

    /**
     * The job that runs the four verification steps, in the running order of their JCL members.
     *
     * <p>One job rather than four, for the reason the section comment above gives: the specification maps the
     * four programs onto <em>steps</em> and rules out four more jobs, but a step with no job cannot be
     * launched, and a step nothing can launch is the unreachability this closes. Sequential rather than a
     * split, because the four members are four separate submissions on the legacy side and nothing in the
     * corpus declares them concurrent - a split would be invented concurrency.
     *
     * <p>Unconditional {@code next(...)} chaining rather than gated transitions, and that is a deliberate
     * reading of the source rather than a simplification: none of the four members carries a {@code COND}
     * parameter, so there is no step gating to translate. A failing step fails the job, which is the
     * framework's default and the same outcome a non-zero completion code produces on the legacy side.
     *
     * <p>Takes no job parameter. None of the four members passes one - no {@code PARM}, no
     * {@code SYMNAMES}, no {@code DATEPARM} - so a validator here would demand input the source does not
     * supply. An operator submission therefore needs only the job name.
     *
     * <p><b>The diagnostic-context listener is a nested object, not an injected bean.</b> A
     * {@link JobExecutionListener} bean is applied to a job by nothing implicitly - neither the batch
     * framework nor the Boot auto-configuration collects them - so an injected shared listener would be
     * dead wiring that reads as assurance, which is findings M-01 and CFG-003. This job therefore registers
     * {@link DatasetVerificationJobListener} on its own {@code JobBuilder}, exactly as every job in
     * {@code com.cardemo.batch.jobs} does, and that registration is the one that takes effect.
     *
     * @param datasetVerificationReadAccountStep the {@code READACCT} step, injected by bean name so the job
     *     cannot bind to some other assignable step
     * @param datasetVerificationReadCardStep the {@code READCARD} step
     * @param datasetVerificationReadCrossReferenceStep the {@code READXREF} step
     * @param datasetVerificationReadCustomerStep the {@code READCUST} step
     * @param jobRepository the metadata store the job records against; must not be {@code null}
     * @return the verification job, never {@code null}
     */
    @Bean(DATASET_VERIFICATION_JOB_BEAN_NAME)
    public Job datasetVerificationJob(
            @Qualifier(READ_ACCOUNT_STEP_BEAN_NAME) final Step datasetVerificationReadAccountStep,
            @Qualifier(READ_CARD_STEP_BEAN_NAME) final Step datasetVerificationReadCardStep,
            @Qualifier(READ_CROSS_REFERENCE_STEP_BEAN_NAME)
            final Step datasetVerificationReadCrossReferenceStep,
            @Qualifier(READ_CUSTOMER_STEP_BEAN_NAME) final Step datasetVerificationReadCustomerStep,
            final JobRepository jobRepository) {

        return new JobBuilder(DATASET_VERIFICATION_JOB_BEAN_NAME,
                Objects.requireNonNull(jobRepository, "jobRepository must not be null"))
                .listener(new DatasetVerificationJobListener())
                .start(Objects.requireNonNull(datasetVerificationReadAccountStep,
                        "datasetVerificationReadAccountStep must not be null"))
                .next(Objects.requireNonNull(datasetVerificationReadCardStep,
                        "datasetVerificationReadCardStep must not be null"))
                .next(Objects.requireNonNull(datasetVerificationReadCrossReferenceStep,
                        "datasetVerificationReadCrossReferenceStep must not be null"))
                .next(Objects.requireNonNull(datasetVerificationReadCustomerStep,
                        "datasetVerificationReadCustomerStep must not be null"))
                .build();
    }

    /**
     * Builds one verification step: read the whole dataset, count it, write nothing.
     *
     * <p>Shared by the four factories above because the four legacy programs share one shape exactly - open,
     * loop read, close, display the count, set the return code - and writing the builder out four times
     * would make four places where that shape could drift.
     *
     * @param <T> the entity the reader emits
     * @param stepName the step's bean name, which is also the prefix of its published count entry
     * @param logicalFile the DD name the program's {@code ASSIGN TO} clause names, for the diagnostics
     * @param program the COBOL program this step translates, for the diagnostics
     * @param reader the sequential reader; must not be {@code null}
     * @param jobRepository the metadata store; must not be {@code null}
     * @param transactionManager the chunk transaction manager; must not be {@code null}
     * @return the step, never {@code null}
     */
    private <T> Step verificationStep(final String stepName, final String logicalFile,
            final String program, final ItemStreamReader<T> reader,
            final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {

        Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        final VerificationWriter<T> writer = new VerificationWriter<>(stepName, logicalFile, program);
        return new StepBuilder(stepName, jobRepository)
                .<T, T>chunk(windowSize, transactionManager)
                .reader(reader)
                .writer(writer)
                .listener((StepExecutionListener) writer)
                .build();
    }

    /**
     * Counts the rows a verification step read, publishes the total and reports it, and stores nothing.
     *
     * <p>This is the Java counterpart of the end-of-run {@code DISPLAY} the four programs share, and of
     * nothing else. It holds no repository, no object-store client and no {@code EntityManager}, which is
     * what makes the read-only guarantee of {@code app/cbl/CBACT01C.cbl} and its three siblings structural:
     * the step has no collaborator through which a write could reach the substrate, so the guarantee does not
     * depend on this class choosing not to exercise one.
     *
     * @param <T> the entity the step's reader emits
     */
    private static final class VerificationWriter<T>
            implements ItemWriter<T>, StepExecutionListener {

        /** The owning step's bean name, which prefixes the published count entry. */
        private final String stepName;

        /** The DD name the translated program's {@code ASSIGN TO} clause names. */
        private final String logicalFile;

        /** The COBOL program this step translates. */
        private final String program;

        /** Rows seen so far in this step execution. */
        private long rowsVerified;

        /**
         * Creates the writer for one step.
         *
         * @param stepName the owning step's bean name; must not be {@code null}
         * @param logicalFile the DD name; must not be {@code null}
         * @param program the translated program; must not be {@code null}
         */
        private VerificationWriter(final String stepName, final String logicalFile, final String program) {
            this.stepName = Objects.requireNonNull(stepName, "stepName must not be null");
            this.logicalFile = Objects.requireNonNull(logicalFile, "logicalFile must not be null");
            this.program = Objects.requireNonNull(program, "program must not be null");
        }

        /**
         * Resets the count so a restart or a second execution does not inherit the previous one's total.
         *
         * @param stepExecution the starting step execution; may be {@code null} outside a step
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            rowsVerified = 0L;
            LOG.info("START OF EXECUTION OF PROGRAM {} ({} verification)", program, logicalFile);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Counts and does nothing else. The chunk is not iterated for content: the readers already emit
         * whatever per-record diagnostic their programs emit, and re-emitting one here would double it.
         *
         * @param chunk the rows in this chunk; never {@code null}
         */
        @Override
        public void write(final Chunk<? extends T> chunk) {
            rowsVerified += chunk.size();
        }

        /**
         * Publishes the total and reports it, reproducing the end-of-run {@code DISPLAY}.
         *
         * @param stepExecution the finishing step execution; may be {@code null} outside a step
         * @return {@code null}, so the framework keeps the exit status it derived from the step's own outcome
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            if (stepExecution != null) {
                stepExecution.getExecutionContext()
                        .putLong(stepName + VERIFIED_ROW_COUNT_SUFFIX, rowsVerified);
            }
            LOG.info("END OF EXECUTION OF PROGRAM {} ({} verification); rowsVerified={}",
                    program, logicalFile, Long.valueOf(rowsVerified));
            return null;
        }
    }

    /**
     * Opens and closes the batch diagnostic scope for the dataset verification job.
     *
     * <p><b>Why the job carries a nested listener rather than an injected bean.</b> A
     * {@link JobExecutionListener} is applied to a job by nothing implicitly: neither the batch framework nor
     * the Boot auto-configuration collects listener beans, so the only registration that takes effect is
     * {@code JobBuilder.listener(...)}. A shared bean declared in this class and registered by no job builder
     * was dead wiring that read as assurance, and it was removed - findings <b>M-01</b> and <b>CFG-003</b>.
     * This is the replacement, and it is a plain object rather than a bean for exactly the same reason.
     *
     * <p><b>What it publishes, and what it deliberately does not.</b> It publishes the job instance
     * identifier, because that value is what correlates a run's records with the objects the run produced,
     * and a correlation identifier only when no outer scope already owns one. It publishes no trace or span
     * identifier: this job opens no span, so an identifier minted here would name a trace no backend holds.
     * The four steps own every per-program message, so this listener emits none of its own.
     *
     * <p><b>Park and restore, not put and clear.</b> The values it displaces are remembered by
     * {@link CorrelationIdFilter#enterBatchScope(long, String)} on the thread that displaced them, and
     * {@link CorrelationIdFilter#exitBatchScope()} runs in a {@code finally}, so a pooled thread is never
     * left labelled with a finished run's identity.
     */
    private static final class DatasetVerificationJobListener implements JobExecutionListener {

        /** The correlation identifier prefix, so a run's records are recognisable as this job's. */
        private static final String CORRELATION_ID_PREFIX = "dataset-verification-";

        /** Creates the listener. Stateless: every value it needs comes from the execution. */
        private DatasetVerificationJobListener() {
            // No state, by construction.
        }

        /**
         * {@inheritDoc}
         *
         * @param jobExecution the starting verification execution
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            CorrelationIdFilter.enterBatchScope(instanceIdOf(jobExecution),
                    CORRELATION_ID_PREFIX
                            + (jobExecution.getId() == null ? "0" : jobExecution.getId().toString()));
        }

        /**
         * {@inheritDoc}
         *
         * <p>Closes the scope in a {@code finally}, so the displaced values are put back whatever else
         * happened during the run.
         *
         * @param jobExecution the finishing verification execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                LOG.info("END OF EXECUTION OF {} - the four read-only members of app/jcl ran with exit"
                        + " status {}", DATASET_VERIFICATION_JOB_BEAN_NAME,
                        jobExecution.getExitStatus().getExitCode());
            } finally {
                CorrelationIdFilter.exitBatchScope();
            }
        }

        /**
         * The job instance identifier, or zero when the execution carries no instance.
         *
         * @param jobExecution the verification execution
         * @return the instance identifier
         */
        private static long instanceIdOf(final JobExecution jobExecution) {
            return jobExecution.getJobInstance() == null
                    ? 0L
                    : jobExecution.getJobInstance().getInstanceId();
        }
    }

    /**
     * The {@code ACCTFILE} binding: keyed reads of the account cluster.
     *
     * @param accountRepository the {@code ACCTDATA} cluster
     * @return the random-access binding for {@link FileService.Dd#ACCTFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset acctFileDataset(final AccountRepository accountRepository) {
        return new AcctFileDataset(accountRepository);
    }

    // FINDING M-01, severity Medium. A @Bean JobExecutionListener named jobInstanceMdcListener stood here
    // and has been removed, together with the private jobInstanceIdOf helper it was the only caller of.
    //
    // It was dead wiring that read as assurance. Neither Spring Batch nor the Boot auto-configuration
    // collects JobExecutionListener beans - a listener takes effect only where a job builder registers it -
    // and no job builder anywhere in com.cardemo.batch.jobs registered this one. Its own documentation said
    // so, and then described itself as "the shared declaration the diagnostic context contract names",
    // which is the part that made it harmful rather than merely inert: a reader auditing whether batch
    // events carry a jobInstanceId would have found a bean that appeared to guarantee it, and a unit test
    // invoking that bean directly proved behaviour that no production run ever executed.
    //
    // What actually populates the key is each of the six job classes' own JobExecutionListener, registered
    // on its own job, because each additionally emits the DISPLAY messages of the program it translates and
    // those differ per job. Every one of them establishes the entry through
    // CorrelationIdFilter.enterBatchScope(long, String) or CorrelationIdFilter.propagateJobInstanceId(String)
    // rather than through a literal, so the key name still has exactly one definition and the log-injection
    // guard on the value is still applied in exactly one place - which is the whole property this bean was
    // said to provide. See CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID for that contract, and the
    // troubleshooting entry above for what to check when a batch log line carries no jobInstanceId.

    // Record rendering. Field widths come from the copybooks named on each method, never from a
    // measurement of the data, so a short or wide value is a startup or read failure rather than a
    // silently misaligned record.

    /** {@code XREF-CUST-ID PIC 9(09)}, {@code app/cpy/CVACT03Y.cpy:L6}. */
    private static final int CUSTOMER_ID_DIGITS = 9;

    /** {@code XREF-ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** {@code XREF-CARD-NUM PIC X(16)}, {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME}, {@code CUST-LAST-NAME}, each {@code PIC X(25)}. */
    private static final int NAME_PART_WIDTH = 25;

    /** {@code CUST-ADDR-LINE-1} through {@code -3}, each {@code PIC X(50)}. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private static final int STATE_CODE_WIDTH = 2;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private static final int COUNTRY_CODE_WIDTH = 3;

    /** {@code CUST-ADDR-ZIP} and {@code ACCT-ADDR-ZIP}, both {@code PIC X(10)}. */
    private static final int ZIP_WIDTH = 10;

    /** {@code CUST-PHONE-NUM-1} and {@code -2}, both {@code PIC X(15)}. */
    private static final int PHONE_WIDTH = 15;

    /** {@code CUST-SSN PIC 9(09)}. */
    private static final int SSN_WIDTH = 9;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final int GOVT_ID_WIDTH = 20;

    /** {@code CUST-DOB-YYYY-MM-DD} and the three {@code ACCT-*-DATE} fields, all {@code PIC X(10)}. */
    private static final int DATE_WIDTH = 10;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private static final int EFT_ACCOUNT_WIDTH = 10;

    /** {@code CUST-PRI-CARD-HOLDER-IND} and {@code ACCT-ACTIVE-STATUS}, both {@code PIC X(01)}. */
    private static final int INDICATOR_WIDTH = 1;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    private static final int FICO_SCORE_DIGITS = 3;

    /** {@code ACCT-CURR-BAL} and its four siblings, all {@code PIC S9(10)V99} - twelve characters. */
    private static final int MONEY_FIELD_WIDTH = 12;

    /** {@code ACCT-GROUP-ID PIC X(10)}. */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * Fits a value to an exact character width, right-padding with spaces and truncating on the right.
     *
     * <p>This is what a COBOL {@code MOVE} of an alphanumeric item into a shorter or longer one does, and it
     * is applied uniformly so that no field can silently shift the ones after it.
     *
     * @param value the value, {@code null} rendering as all spaces
     * @param width the exact width required
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String fit(final String value, final int width) {
        final String source = value == null ? "" : value;
        if (source.length() == width) {
            return source;
        }
        if (source.length() > width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Renders an unsigned integral value as zero-padded digits, which is what {@code PIC 9(n)} holds.
     *
     * @param value the value, {@code null} rendering as zero
     * @param width the exact number of digits
     * @return exactly {@code width} digits, never {@code null}
     */
    private static String digits(final Number value, final int width) {
        final long magnitude = value == null ? 0L : Math.abs(value.longValue());
        final String rendered = Long.toString(magnitude);
        if (rendered.length() >= width) {
            return rendered.substring(rendered.length() - width);
        }
        return "0".repeat(width - rendered.length()) + rendered;
    }

    /**
     * Renders a value already known to be digits as zero-padded digits of an exact width.
     *
     * <p>Used for {@code CUST-SSN} and {@code CUST-FICO-CREDIT-SCORE}, both of which the entity holds as
     * text because their leading zeros are significant.
     *
     * @param value the digit string, {@code null} or blank rendering as all zeros
     * @param width the exact number of digits
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String digitText(final String value, final int width) {
        final String source = value == null ? "" : value.strip();
        if (source.isEmpty()) {
            return "0".repeat(width);
        }
        if (source.length() >= width) {
            return source.substring(source.length() - width);
        }
        return "0".repeat(width - source.length()) + source;
    }

    /**
     * Asserts that a rendered record is exactly the width its DD declares.
     *
     * <p>A record of the wrong width is the one failure that would look like working code: the caller
     * slices by position, so every field after the drift would be read from the wrong offset.
     *
     * @param record the rendered record
     * @param dd the DD the record belongs to
     * @return the record, unchanged
     * @throws IllegalStateException if the width is wrong, which is a defect in the renderer above rather
     *     than a data condition
     */
    private static String requireRecordWidth(final String record, final FileService.Dd dd) {
        if (record.length() != dd.recordWidth()) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "the rendered %s record is %d characters but the DD declares exactly %d",
                    dd.ddName(), Integer.valueOf(record.length()), Integer.valueOf(dd.recordWidth())));
        }
        return record;
    }

    /**
     * The sorted transaction stream that {@code CBSTM03A} reads through its {@code TRNXFILE} DD.
     *
     * <p><b>Finding, severity Blocker - remediated by this class: the projected object is authoritative.</b>
     * {@code app/jcl/CREASTMT.JCL:L83} binds {@code TRNXFILE} to
     * {@code AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS} - the <em>work cluster</em> that {@code STEP010} projected and
     * sorted at {@code :L44-L54} and that {@code STEP020} loaded at {@code :L56-L61}. It does <b>not</b> bind
     * it to {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, which is {@code STEP010}'s {@code SORTIN} at
     * {@code :L45} and is not referenced by {@code STEP040} at all.
     *
     * <p>This binding previously re-queried the live transaction relation, ordered and projected per record,
     * at emission time. Two things followed. The emission read a relation that could have changed since the
     * sort, whereas the source reads a frozen copy - so {@code STEP010}'s ascending precondition, which
     * {@code app/cbl/CBSTM03A.CBL:L419} relies on for its early-exit lookup, was re-established by a second
     * query rather than inherited from the object it was proven against. And the same unindexed two-key
     * ordering was paid for <em>twice</em> per run, once by the sort and again by the emission, which is the
     * separate High finding about repeated re-sorting; reading the object pays it once, exactly as one
     * {@code SORT} step does.
     *
     * <p>It now streams the object {@code STEP020} validated, taking the concrete key the sort published, so
     * the record {@code STEP040} sees is byte-for-byte the record {@code STEP010} wrote - including the
     * {@code OUTREC} projection's two-byte truncation of the processing timestamp and its dropped trailing
     * filler, which are applied once, by the producer, and never recomputed here.
     *
     * <p>Records are read one at a time from an open stream rather than by materialising the object, so the
     * heap cost is one record and not one run.
     */
    private static final class TrnxFileDataset implements FileService.Dataset {

        /** The object store holding the projected work object. */
        private final S3Operations objectStorage;

        /** The bucket the projected work object was written to. */
        private final String workBucket;

        /** The open stream over the projected object, or {@code null} when the DD is closed. */
        private InputStream records;

        /** The key currently open, named in every read diagnostic so a failure identifies the object. */
        private String openKey;

        /**
         * Creates the binding.
         *
         * @param objectStorage the object store; must not be {@code null}
         * @param workBucket the bucket the projected object lives in; must not be {@code null}
         */
        private TrnxFileDataset(final S3Operations objectStorage, final String workBucket) {
            this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
            this.workBucket = Objects.requireNonNull(workBucket, "workBucket must not be null");
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.TRNXFILE;
        }

        /**
         * Opens the projected object the sort step published.
         *
         * <p>The key is taken from the job execution context rather than resolved as "the latest object",
         * because a concurrent run would otherwise be handed the wrong generation. It is read through the
         * step scope holder rather than injected, because this binding is a singleton: {@code FileService}
         * indexes every binding by {@link FileService.Dd} in its constructor, so a step-scoped proxy would be
         * asked for its DD outside any step and fail at startup.
         *
         * @return {@link #STATUS_SUCCESS} when the object is open, {@link #STATUS_FILE_UNAVAILABLE} when the
         *     handoff is absent or the object cannot be opened
         */
        @Override
        public synchronized String openInput() {
            closeQuietly();
            final String key = publishedWorkObjectKey();
            if (key == null) {
                LOG.error("TRNXFILE cannot be opened: no step published {} into the job execution context, "
                        + "so app/jcl/CREASTMT.JCL:L83's work cluster does not exist for this run",
                        StatementGenerationJob.WORK_OBJECT_KEY_CONTEXT_ENTRY);
                return STATUS_FILE_UNAVAILABLE;
            }
            try {
                records = objectStorage.download(workBucket, key).getInputStream();
                openKey = key;
                return STATUS_SUCCESS;
            } catch (final IOException | RuntimeException cause) {
                LOG.error("TRNXFILE cannot be opened over the projected object: reason={}",
                        cause.getClass().getSimpleName());
                closeQuietly();
                return STATUS_FILE_UNAVAILABLE;
            }
        }

        @Override
        public synchronized String close() {
            closeQuietly();
            return STATUS_SUCCESS;
        }

        /**
         * Returns the next projected record, or end of file.
         *
         * <p>A short final record is a truncated object and is reported as a physical error rather than
         * treated as end of file: the DD declares fixed-length records, so a partial one means the producer
         * or the transfer failed, and reading it as a whole record would slice every field from the wrong
         * offset.
         *
         * @return the record, end of file, or a physical I/O error
         */
        @Override
        public synchronized FileService.DatasetRead readNext() {
            if (records == null) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            final int width = FileService.Dd.TRNXFILE.recordWidth();
            final byte[] buffer = new byte[width];
            final int read;
            try {
                read = records.readNBytes(buffer, 0, width);
            } catch (final IOException cause) {
                LOG.error("TRNXFILE read failed over projected object {}: reason={}", openKey,
                        cause.getClass().getSimpleName());
                return FileService.DatasetRead.withoutRecord(STATUS_PHYSICAL_IO_ERROR);
            }
            if (read == 0) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            if (read < width) {
                LOG.error("TRNXFILE is truncated: the final record of projected object {} is {} bytes but "
                        + "app/jcl/CREASTMT.JCL:L32 declares exactly {}", openKey, Integer.valueOf(read),
                        Integer.valueOf(width));
                return FileService.DatasetRead.withoutRecord(STATUS_PHYSICAL_IO_ERROR);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(new String(buffer, PROJECTED_RECORD_CHARSET),
                            FileService.Dd.TRNXFILE));
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            throw new UnsupportedOperationException("TRNXFILE is opened for sequential access only; "
                    + "app/cbl/CBSTM03B.CBL:L61-L63 declares ORGANIZATION SEQUENTIAL for it, so a keyed "
                    + "read has no counterpart. The service refuses the operation before reaching here.");
        }

        /**
         * Reads the concrete key the sort step published, from the job execution context of the running step.
         *
         * @return the key, or {@code null} when no step is in scope or none was published
         */
        private static String publishedWorkObjectKey() {
            final StepContext stepContext = StepSynchronizationManager.getContext();
            if (stepContext == null) {
                return null;
            }
            final ExecutionContext jobContext = stepContext.getStepExecution().getJobExecution()
                    .getExecutionContext();
            return jobContext.containsKey(StatementGenerationJob.WORK_OBJECT_KEY_CONTEXT_ENTRY)
                    ? jobContext.getString(StatementGenerationJob.WORK_OBJECT_KEY_CONTEXT_ENTRY)
                    : null;
        }

        /** Closes the stream if one is open, discarding a close failure that nothing can act on. */
        private void closeQuietly() {
            final InputStream open = records;
            records = null;
            openKey = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (final IOException cause) {
                LOG.warn("TRNXFILE stream did not close cleanly: reason={}",
                        cause.getClass().getSimpleName());
            }
        }
    }

    /**
     * The cross-reference cluster that drives the whole statement run, read through {@code XREFFILE}.
     *
     * <p>Sequential in record-key order, which for {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} is card
     * number ascending. The record is the 36 populated bytes of {@code app/cpy/CVACT03Y.cpy} followed by
     * the 14-byte filler that brings it to the catalogued 50.
     */
    private static final class XrefFileDataset implements FileService.Dataset {

        /** The {@code CARDXREF} cluster. */
        private final CardCrossReferenceRepository cardCrossReferenceRepository;

        /** Rows fetched per keyset window. */
        private final int windowSize;

        /** The rows of the current window that have not been returned yet. */
        private final Deque<CardCrossReference> window = new ArrayDeque<>();

        /** Card number of the last row returned, the keyset position. */
        private String lastCardNumber = SCAN_ORIGIN;

        /** Whether the underlying sequence has been exhausted. */
        private boolean exhausted;

        /**
         * Creates the binding.
         *
         * @param cardCrossReferenceRepository the {@code CARDXREF} cluster; must not be {@code null}
         * @param windowSize rows per keyset window; already validated positive by the enclosing class
         */
        private XrefFileDataset(final CardCrossReferenceRepository cardCrossReferenceRepository,
                final int windowSize) {
            this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                    "cardCrossReferenceRepository must not be null");
            this.windowSize = windowSize;
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.XREFFILE;
        }

        @Override
        public synchronized String openInput() {
            window.clear();
            lastCardNumber = SCAN_ORIGIN;
            exhausted = false;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized String close() {
            window.clear();
            exhausted = true;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized FileService.DatasetRead readNext() {
            if (window.isEmpty() && !exhausted) {
                final List<CardCrossReference> next = cardCrossReferenceRepository
                        .findByCardNumberGreaterThanOrderByCardNumberAsc(lastCardNumber,
                                PageRequest.ofSize(windowSize));
                if (next.isEmpty()) {
                    exhausted = true;
                } else {
                    window.addAll(next);
                }
            }
            final CardCrossReference row = window.pollFirst();
            if (row == null) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            lastCardNumber = row.getCardNumber();
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderCrossReference(row), FileService.Dd.XREFFILE));
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            throw new UnsupportedOperationException("XREFFILE is opened for sequential access only; "
                    + "app/cbl/CBSTM03B.CBL:L67-L68 declares ORGANIZATION SEQUENTIAL for it, so a keyed "
                    + "read has no counterpart. The service refuses the operation before reaching here.");
        }

        /**
         * Renders one cross-reference row in the {@code app/cpy/CVACT03Y.cpy} layout.
         *
         * @param row the row just read, never {@code null}
         * @return exactly 50 characters: 16 + 9 + 11 populated, then the 14-byte filler
         */
        private static String renderCrossReference(final CardCrossReference row) {
            final String populated = fit(row.getCardNumber(), CARD_NUMBER_WIDTH)
                    + digits(row.getCustomerId(), CUSTOMER_ID_DIGITS)
                    + digits(row.getAccountId(), ACCOUNT_ID_DIGITS);
            return fit(populated, FileService.Dd.XREFFILE.recordWidth());
        }
    }

    /**
     * The customer cluster, read by key through {@code CUSTFILE}.
     *
     * <p>Random access: {@code app/cbl/CBSTM03B.CBL:L71-L74} declares {@code ACCESS MODE IS RANDOM} with
     * {@code FD-CUST-ID} as the record key, and {@code app/cbl/CBSTM03A.CBL:L369-L392} reads it once per
     * cross-reference row. Stateless, because a keyed read carries no position.
     */
    private static final class CustFileDataset implements FileService.Dataset {

        /** The {@code CUSTDATA} cluster. */
        private final CustomerRepository customerRepository;

        /**
         * Creates the binding.
         *
         * @param customerRepository the {@code CUSTDATA} cluster; must not be {@code null}
         */
        private CustFileDataset(final CustomerRepository customerRepository) {
            this.customerRepository = Objects.requireNonNull(customerRepository,
                    "customerRepository must not be null");
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.CUSTFILE;
        }

        @Override
        public String openInput() {
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            throw new UnsupportedOperationException("CUSTFILE is opened for random access only; "
                    + "app/cbl/CBSTM03B.CBL:L71-L74 declares ACCESS MODE IS RANDOM for it, so a "
                    + "sequential read has no counterpart. The service refuses the operation before "
                    + "reaching here.");
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            Objects.requireNonNull(recordKey, "recordKey must not be null");
            final Optional<Customer> found = customerRepository.findById(Long.valueOf(recordKey.strip()));
            if (found.isEmpty()) {
                return FileService.DatasetRead.withoutRecord(STATUS_RECORD_NOT_FOUND);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderCustomer(found.get()), FileService.Dd.CUSTFILE));
        }

        /**
         * Renders one customer row in the {@code app/cpy/CVCUS01Y.cpy} layout.
         *
         * <p>The eighteen named items total 332 characters and the copybook's {@code FILLER PIC X(168)}
         * brings the record to the catalogued 500.
         *
         * @param customer the row just read, never {@code null}
         * @return exactly 500 characters
         */
        private static String renderCustomer(final Customer customer) {
            final StringBuilder record = new StringBuilder(FileService.Dd.CUSTFILE.recordWidth());
            record.append(digits(customer.getCustomerId(), CUSTOMER_ID_DIGITS));
            record.append(fit(customer.getFirstName(), NAME_PART_WIDTH));
            record.append(fit(customer.getMiddleName(), NAME_PART_WIDTH));
            record.append(fit(customer.getLastName(), NAME_PART_WIDTH));
            record.append(fit(customer.getAddressLine1(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressLine2(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressLine3(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressStateCode(), STATE_CODE_WIDTH));
            record.append(fit(customer.getAddressCountryCode(), COUNTRY_CODE_WIDTH));
            record.append(fit(customer.getAddressZip(), ZIP_WIDTH));
            record.append(fit(customer.getPhoneNumber1(), PHONE_WIDTH));
            record.append(fit(customer.getPhoneNumber2(), PHONE_WIDTH));
            record.append(digitText(customer.getSsn(), SSN_WIDTH));
            record.append(fit(customer.getGovernmentIssuedId(), GOVT_ID_WIDTH));
            record.append(fit(customer.getDateOfBirth(), DATE_WIDTH));
            record.append(fit(customer.getEftAccountId(), EFT_ACCOUNT_WIDTH));
            record.append(fit(customer.getPrimaryCardHolderIndicator(), INDICATOR_WIDTH));
            record.append(digitText(customer.getFicoCreditScore(), FICO_SCORE_DIGITS));
            return fit(record.toString(), FileService.Dd.CUSTFILE.recordWidth());
        }
    }

    /**
     * The account cluster, read by key through {@code ACCTFILE}.
     *
     * <p>Random access with a <strong>numeric</strong> record key: {@code app/cbl/CBSTM03B.CBL:L77}
     * declares {@code FD-ACCT-ID PIC 9(11)}, which is why {@link FileService.Dd#ACCTFILE} reports
     * {@code numericKey()} and the service validates the key as eleven digits before it arrives here.
     * Stateless, because a keyed read carries no position.
     */
    private static final class AcctFileDataset implements FileService.Dataset {

        /** The {@code ACCTDATA} cluster. */
        private final AccountRepository accountRepository;

        /**
         * Creates the binding.
         *
         * @param accountRepository the {@code ACCTDATA} cluster; must not be {@code null}
         */
        private AcctFileDataset(final AccountRepository accountRepository) {
            this.accountRepository = Objects.requireNonNull(accountRepository,
                    "accountRepository must not be null");
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.ACCTFILE;
        }

        @Override
        public String openInput() {
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            throw new UnsupportedOperationException("ACCTFILE is opened for random access only; "
                    + "app/cbl/CBSTM03B.CBL:L77-L78 declares ACCESS MODE IS RANDOM for it, so a "
                    + "sequential read has no counterpart. The service refuses the operation before "
                    + "reaching here.");
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            Objects.requireNonNull(recordKey, "recordKey must not be null");
            final Optional<Account> found = accountRepository.findById(Long.valueOf(recordKey.strip()));
            if (found.isEmpty()) {
                return FileService.DatasetRead.withoutRecord(STATUS_RECORD_NOT_FOUND);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderAccount(found.get()), FileService.Dd.ACCTFILE));
        }

        /**
         * Renders one account row in the {@code app/cpy/CVACT01Y.cpy} layout.
         *
         * <p>The twelve named items total 122 characters and the copybook's {@code FILLER PIC X(178)}
         * brings the record to the catalogued 300. The five {@code PIC S9(10)V99} money fields are zoned
         * decimal with a trailing overpunch sign, encoded through the published inverse of the decoder that
         * reads them back, so no sign convention is restated here.
         *
         * @param account the row just read, never {@code null}
         * @return exactly 300 characters
         */
        private static String renderAccount(final Account account) {
            final StringBuilder record = new StringBuilder(FileService.Dd.ACCTFILE.recordWidth());
            record.append(digits(account.getAccountId(), ACCOUNT_ID_DIGITS));
            record.append(fit(account.getActiveStatus(), INDICATOR_WIDTH));
            record.append(money(account.getCurrentBalance()));
            record.append(money(account.getCreditLimit()));
            record.append(money(account.getCashCreditLimit()));
            record.append(fit(account.getOpenDate(), DATE_WIDTH));
            record.append(fit(account.getExpiraionDate(), DATE_WIDTH));
            record.append(fit(account.getReissueDate(), DATE_WIDTH));
            record.append(money(account.getCurrentCycleCredit()));
            record.append(money(account.getCurrentCycleDebit()));
            record.append(fit(account.getAddressZip(), ZIP_WIDTH));
            record.append(fit(account.getGroupId(), GROUP_ID_WIDTH));
            return fit(record.toString(), FileService.Dd.ACCTFILE.recordWidth());
        }

        /**
         * Encodes one {@code PIC S9(10)V99} money field.
         *
         * @param value the amount, {@code null} encoding as positive zero
         * @return exactly {@value #MONEY_FIELD_WIDTH} characters
         */
        private static String money(final BigDecimal value) {
            return StatementProcessor.encodeZonedDecimal(value, MONEY_FIELD_WIDTH);
        }
    }
}
