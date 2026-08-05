/*
 * ******************************************************************
 * Program     : BatchConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (batch layer)
 * Function    : Registers the batch collaborators that cannot register
 *               themselves, and documents the topology of the five stage
 *               pipeline the job classes declare. Two bean groups: the four
 *               repository backed dataset bindings that stand in for the
 *               CBSTM03B file access subprogram's four DD names, and the
 *               job instance MDC contribution that batch events carry
 *               because CorrelationIdFilter is HTTP scoped. The six Job
 *               beans, the step scoped report processor, the chunk sizes
 *               and the JobExecutionDecider are NOT registered here - see
 *               the class documentation for where each one lives and why.
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
 *                 count exceeds zero) and :L707-L710 (abend code 999)
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
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.FileService;
import io.awspring.cloud.s3.S3Operations;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.PageRequest;

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
 *   <li><strong>{@code TransactionReportProcessor} is deliberately NOT declared here.</strong> It carries
 *       the six {@code WS-REPORT-VARS} items of {@code app/cbl/CBTRN03C.cbl:L127-L137} - the line counter,
 *       three running totals, the control-break card number and the first-time flag - so a singleton
 *       instance would carry one report's pagination and totals into the next, and it needs the two
 *       reporting dates that {@code app/proc/TRANREPT.prc:L60-L70} supplies through {@code SYMNAMES}. Both
 *       requirements are met on the class itself: it is annotated {@code @Component @StepScope} and binds
 *       the dates with {@code @Value("#{jobParameters[...]}")}. A {@code @Bean} factory here would derive
 *       the same bean name, {@code transactionReportProcessor}, and
 *       {@code spring.main.allow-bean-definition-overriding} is {@code false}, so the pair would abort
 *       startup rather than be a harmless redundancy. The processor stays directly constructible from a
 *       unit test because its constructor takes plain strings whatever binds them.</li>
 *   <li><strong>The four {@link FileService.Dataset} bindings.</strong> {@code CBSTM03A} reaches all of its
 *       input through {@code CALL 'CBSTM03B' USING WS-M03B-AREA} with a DD-name selector
 *       ({@code app/cbl/CBSTM03A.CBL:L71-L83}), and {@code CBSTM03B} declares exactly four files
 *       ({@code app/cbl/CBSTM03B.CBL:L58-L78}). {@code FileService} is the translation of the subprogram
 *       and owns the dispatch, the status registers and the guards; it deliberately owns no data access.
 *       These four beans are the data access, one per DD, each backed by the repository that replaced the
 *       corresponding VSAM cluster. Without them the service dispatches into nothing and the statement
 *       processor fails on its first operation.</li>
 *   <li><strong>The job instance diagnostic context contribution,</strong>
 *       {@link #jobInstanceMdcListener()}. {@code com.cardemo.observability.CorrelationIdFilter} is HTTP
 *       scoped and batch execution never passes through it, so the {@code jobInstanceId} key that batch
 *       events carry alongside {@code correlationId}, {@code traceId} and {@code spanId} has to be
 *       established by the batch layer. The observability package is deliberately not permitted a listener
 *       of its own, so the shared declaration belongs here. It is one bean and there is no second listener
 *       of any kind in this class: no {@code StepExecutionListener}, no {@code ItemReadListener}, no
 *       {@code SkipListener} and no {@code ChunkListener}, because no cited source behaviour asks for
 *       one.</li>
 *   </ol>
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
 *   <dt>The step scoped report processor</dt>
 *   <dd>See the block comment before the bean methods. {@code TransactionReportProcessor} carries
 *       {@code @Component @StepScope} itself and a factory here would derive a colliding bean name.</dd>
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
 *       more jobs.</li>
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
 *       {@code app/cbl/CBTRN02C.cbl:L707-L710} displays an abend message, zeroes a timing field, moves 999
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
 * and break the parity comparison against the frozen corpus, so it is owed an entry in the planned
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
 * <p>{@code spring.batch.job.enabled} is {@code false}, so jobs do not run at startup. Launching is
 * explicit: the orchestrator, or the queue listener that replaces the JES2 internal reader driven by
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} in {@code app/cbl/CORPT00C.cbl}. This class therefore declares no
 * runner, no scheduler and nothing annotated to fire on startup.
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
 *   <dt>The report processor bean cannot be created outside a step</dt>
 *   <dd>Expected, and not a defect in this class. {@code TransactionReportProcessor} is
 *       {@code @Component @StepScope} and its two dates come from job parameters, so it resolves only
 *       inside a running step. A unit test constructs it directly instead, which is why its constructor
 *       takes plain strings.</dd>
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
 *   <dt>A job fails on a missing {@code BATCH_JOB_INSTANCE} or {@code BATCH_STEP_EXECUTION} table</dt>
 *   <dd>{@code spring.batch.jdbc.initialize-schema} is {@code never} in the active profile, which is
 *       deliberate at base and in production. Run under the local or test profile, or provision the
 *       framework's own schema script out of band. Do <strong>not</strong> add a migration for it: the
 *       migration set is closed at three and a gate asserts the first one creates exactly eleven
 *       tables.</dd>
 *   <dt>No job runs at startup and no error appears</dt>
 *   <dd>Expected and by design: {@code spring.batch.job.enabled} is {@code false}. Launch through the
 *       orchestrator or by publishing to the report queue that replaces the JES2 internal reader. Adding a
 *       runner, a scheduler or a startup hook to "fix" this reintroduces the behaviour the flag exists to
 *       suppress - every job running on every boot.</dd>
 *   <dt>The combine load step fails on a duplicate key</dt>
 *   <dd>The interest run was repeated with the same date parameter, so it minted colliding transaction
 *       identifiers: {@code app/cbl/CBACT04C.cbl} concatenates the ten character date with a run-sequential
 *       suffix, and {@code app/jcl/COMBTRAN.jcl:L48} then loads the combined generation into the transaction
 *       cluster. Surfacing it as a duplicate-record failure is correct; a silent upsert would hide a repeated
 *       run and is never the remedy. Re-run the interest job with the intended date.</dd>
 *   <dt>A batch log line carries no {@code jobInstanceId}</dt>
 *   <dd>The job was launched without the listener of {@link #jobInstanceMdcListener()} or an equivalent
 *       registered on it. A listener bean is not applied to a job implicitly - neither the batch framework
 *       nor the Boot auto-configuration collects listener beans - so it takes effect only where a job builder
 *       registers it.</dd>
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
 *       {@code app/cbl/CBSTM03A.CBL:L149}. Also medium: {@code carddemo.batch.jobs.<id>.enabled},
 *       {@code carddemo.batch.jobs.creastmt.steps} and {@code carddemo.batch.jobs.tranrept.name} are
 *       declared in the profile but bound by nothing, the last because the report job binds
 *       {@code carddemo.batch.tranrept.name} instead. The remedy is to give them a consumer or withdraw
 *       them, and in either case to keep the one spelling; introducing a second binding here would create
 *       exactly the drift the profile warns against, and binding them into a bean nothing consumes would be
 *       dead code.</dd>
 *   <dt>Low</dt>
 *   <dd>{@code app/proc/TRANREPT.prc:L1} and {@code app/proc/REPROC.prc:L1} both declare
 *       {@code //REPROC PROC}, so the internal procedure name of the first differs from the member name that
 *       {@code EXEC PROC=TRANREPT} resolves. And {@code app/jcl/CREASTMT.JCL:L90} is a corrupted DD line,
 *       carrying fragments of a {@code DCB} and a data set name spliced into a {@code SPACE} parameter. Both
 *       are left exactly as found.</dd>
 *   </dl>
 *
 * <p>Each preserved defect above is owed an entry in the planned {@code DECISION_LOG.md}, and each source
 * paragraph named in this class is owed a row in the planned {@code TRACEABILITY_MATRIX.md}. Neither register
 * exists at this commit, so the obligation is stated rather than the fact.
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
 * instead of truncating. This is owed an entry in the planned {@code DECISION_LOG.md}, and the legacy ceiling
 * is owed a row in the planned {@code TRACEABILITY_MATRIX.md} as the historical capacity limit.
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

    // =============================================================================================
    // THERE IS DELIBERATELY NO PROCESSOR, READER OR WRITER FACTORY IN THIS CLASS.
    //
    // A @Bean @StepScope transactionReportProcessor factory stood here and has been removed. It could not
    // coexist with the component it built: com.cardemo.batch.processors.TransactionReportProcessor is
    // annotated @Component @StepScope, whose default bean name is "transactionReportProcessor" - the same
    // name this factory method carried. spring.main.allow-bean-definition-overriding is false in the base
    // profile, so the pair was a startup failure rather than a harmless redundancy, and the arity of the
    // processor's constructor had moved on from the call this factory made.
    //
    // The component annotation is what survives, for two reasons that outlast this one class. Every reader,
    // processor and writer under batch/** is registered by @Component on the class - twelve of them - so a
    // factory here would be the single exception a reader has to notice. And the scope is a property of the
    // component rather than of whoever wires it: a step-scoped bean cannot be injected into a singleton
    // without a proxy, so the container enforces the isolation contract that a factory in a configuration
    // class merely asserts. What this class owns is the FileService.Dataset bindings below, which have no
    // component to annotate because they are one binding per DD name rather than one class per role.
    // =============================================================================================


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

    /**
     * The job instance contribution to the diagnostic context, and the only listener this class declares.
     *
     * <p><strong>Purpose.</strong> Batch events must be labelled with the job instance identifier alongside
     * the {@code correlationId}, {@code traceId} and {@code spanId} trio, because that identifier is what
     * makes a run's logs correlatable with its output objects: the batch writers derive their object-storage
     * key prefixes from the same value, and those deterministic per-run prefixes are what replace the
     * generation data group bases of the frozen job stream.
     * {@code com.cardemo.observability.CorrelationIdFilter} is HTTP scoped and batch execution never reaches
     * it, and the observability package is deliberately not permitted a listener of its own, so the shared
     * declaration belongs here.
     *
     * <p><strong>The key is reused, never re-spelled.</strong> The value is written through
     * {@code CorrelationIdFilter.propagateJobInstanceId(String)}, so the key name has exactly one definition
     * in the application and the log-injection guard on the value is applied in exactly one place. Nothing
     * else is put into the diagnostic context here: no credential, token, hash, card number, government
     * identifier or any other sensitive value, and the masking in
     * {@code src/main/resources/logback-spring.xml} is neither duplicated nor weakened.
     *
     * <p><strong>Inputs and outputs.</strong> Takes no argument and returns a stateless singleton. It holds no
     * field, no counter and no thread local, which is why one instance may be registered on any number of
     * jobs running concurrently.
     *
     * <p><strong>Side effects.</strong> Mutates exactly one diagnostic context entry on the thread the job
     * runs on, and leaves every other entry untouched.
     *
     * <p><strong>Why an anonymous class and not a lambda.</strong>
     * {@code org.springframework.batch.core.JobExecutionListener} declares two methods and both are
     * {@code default}, so it has no abstract method and is not a functional interface: a lambda for it does
     * not compile. An anonymous class is the closest available form and, being declared inside this file, adds
     * no file to the package.
     *
     * <p><strong>Registration is explicit, and that is worth knowing before debugging an absent key.</strong>
     * Neither the batch framework nor the Boot auto-configuration collects {@code JobExecutionListener} beans,
     * so a listener takes effect only where a job builder registers it. Each of the six job classes registers
     * its own nested listener, because each additionally emits the {@code DISPLAY} messages of the program it
     * translates and those messages differ per job; every one of them writes this same key through the same
     * helper rather than through a literal. This bean is the shared declaration the diagnostic context
     * contract names, and the registration point for a job composed without a listener of its own.
     *
     * <p><strong>Error modes.</strong> A diagnostic aid must never fail the job it is labelling, so an
     * execution that carries no job instance - which a malformed or partially constructed execution can - is
     * skipped rather than raising. The clearing call in the after-job callback is the <em>first</em> statement
     * of that callback, so no earlier statement can throw and leave the entry behind on a pooled thread; a
     * leaked entry would mislabel an unrelated later run on the same thread. Clearing is also narrowed to what
     * this listener established: if the entry holds some other value when the job ends, that value belongs to
     * an enclosing scope and is put back rather than discarded.
     *
     * @return the stateless job instance diagnostic context listener, never {@code null}
     */
    @Bean
    public JobExecutionListener jobInstanceMdcListener() {
        return new JobExecutionListener() {

            @Override
            public void beforeJob(final JobExecution jobExecution) {
                final String jobInstanceId = jobInstanceIdOf(jobExecution);
                if (jobInstanceId != null) {
                    CorrelationIdFilter.propagateJobInstanceId(jobInstanceId);
                }
            }

            @Override
            public void afterJob(final JobExecution jobExecution) {
                // Clear first, unconditionally, so nothing above can throw and leave the entry behind.
                final String held = CorrelationIdFilter.propagateJobInstanceId(null);
                final String jobInstanceId = jobInstanceIdOf(jobExecution);
                if (held != null && !held.equals(jobInstanceId)) {
                    // The entry was not the one this listener established, so an enclosing scope owns it.
                    CorrelationIdFilter.propagateJobInstanceId(held);
                }
            }
        };
    }

    /**
     * The diagnostic context value for an execution, or {@code null} when there is none to publish.
     *
     * <p>Rendered with {@link Long#toString(long)} because that is exactly the grammar
     * {@code CorrelationIdFilter.propagateJobInstanceId(String)} accepts - an optionally negative run of
     * decimal digits - so the value can never be rejected by the guard that protects the log format.
     *
     * @param jobExecution the execution being labelled, which may be {@code null}
     * @return the decimal job instance identifier, or {@code null} if the execution or its instance is absent
     */
    private static String jobInstanceIdOf(final JobExecution jobExecution) {
        if (jobExecution == null || jobExecution.getJobInstance() == null) {
            return null;
        }
        return Long.toString(jobExecution.getJobInstance().getInstanceId());
    }

    // =============================================================================================
    // Record rendering. Field widths come from the copybooks named on each method, never from a
    // measurement of the data, so a short or wide value is a startup or read failure rather than a
    // silently misaligned record.
    // =============================================================================================

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
