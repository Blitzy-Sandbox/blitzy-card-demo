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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Account file reader &mdash; sequential scan over the {@code accounts}
 * table emitting one structured log line per record. The Java target for
 * the COBOL batch program {@code app/cbl/CBACT01C.cbl}
 * (&quot;Read and print account data file.&quot;).
 *
 * <h2>COBOL Source Provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT01C.cbl} &mdash;
 *       batch reader; declares
 *       {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE ORGANIZATION IS
 *       INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-ACCT-ID}
 *       (11-digit primary key). The PROCEDURE DIVISION (L70&ndash;L87)
 *       executes:
 *       <pre>
 *       DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
 *       PERFORM 0000-ACCTFILE-OPEN
 *       PERFORM UNTIL END-OF-FILE = 'Y'
 *           PERFORM 1000-ACCTFILE-GET-NEXT
 *           IF END-OF-FILE = 'N' DISPLAY ACCOUNT-RECORD
 *       END-PERFORM
 *       PERFORM 9000-ACCTFILE-CLOSE
 *       DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
 *       GOBACK
 *       </pre></li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT01Y.cpy} defines
 *       {@code ACCOUNT-RECORD} (300 bytes) with 12 business fields
 *       plus a trailing {@code FILLER PIC X(178)}. The 11 fields
 *       emitted by {@code 1100-DISPLAY-ACCT-RECORD} are
 *       {@code ACCT-ID} ({@code PIC 9(11)}),
 *       {@code ACCT-ACTIVE-STATUS} ({@code PIC X(01)}),
 *       {@code ACCT-CURR-BAL} ({@code PIC S9(10)V99}),
 *       {@code ACCT-CREDIT-LIMIT} ({@code PIC S9(10)V99}),
 *       {@code ACCT-CASH-CREDIT-LIMIT} ({@code PIC S9(10)V99}),
 *       {@code ACCT-OPEN-DATE} ({@code PIC X(10)}),
 *       {@code ACCT-EXPIRAION-DATE} ({@code PIC X(10)} &mdash; sic;
 *       the COBOL field carries a misspelling preserved here for
 *       traceability while the Java getter uses the corrected
 *       spelling {@code getAcctExpirationDate()} per AAP
 *       &sect;0.4.1 V001),
 *       {@code ACCT-REISSUE-DATE} ({@code PIC X(10)}),
 *       {@code ACCT-CURR-CYC-CREDIT} ({@code PIC S9(10)V99}),
 *       {@code ACCT-CURR-CYC-DEBIT} ({@code PIC S9(10)V99}), and
 *       {@code ACCT-GROUP-ID} ({@code PIC X(10)}). The non-printed
 *       {@code ACCT-ADDR-ZIP} field exists in the record layout but
 *       is intentionally omitted from the DISPLAY paragraph in the
 *       COBOL source; the Java target preserves that omission.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} &mdash; KEYS(11 0),
 *       RECORDSIZE(300 300), INDEXED (per
 *       {@code app/jcl/ACCTFILE.jcl} L36&ndash;L49 and
 *       {@code app/catlg/LISTCAT.txt}). No alternate index (AIX) or
 *       PATH exists over this cluster; all COBOL access is by primary
 *       key (random read on {@code ACCT-ID}) or sequential scan
 *       (this program); the PostgreSQL B-tree on the primary key
 *       satisfies both patterns per AAP &sect;0.6.2.</li>
 *   <li><b>JCL invocation:</b> {@code app/jcl/READACCT.jcl} (demo
 *       inspection job) &mdash; replaced operationally by AWS Batch
 *       ad-hoc job invocation per AAP &sect;0.4.1 ("Demo/inspection
 *       JCLs &mdash; replaced operationally by Spring Boot Actuator
 *       endpoints + AWS Batch ad-hoc jobs").</li>
 * </ul>
 *
 * <h2>COBOL Paragraph &harr; Java Method Mapping</h2>
 * <table>
 *   <caption>CBACT01C.cbl &harr; AccountFileReaderService</caption>
 *   <tr><th>COBOL paragraph / line</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L70&ndash;L87)</td>
 *       <td>{@link #readAndDisplayAll()}</td></tr>
 *   <tr><td>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'}
 *       (L71)</td>
 *       <td>{@code LOG.info("START OF EXECUTION OF PROGRAM CBACT01C")}</td></tr>
 *   <tr><td>{@code 0000-ACCTFILE-OPEN} (L133&ndash;L149)</td>
 *       <td>Spring Data JPA implicit DB connection acquisition (no
 *           explicit open required &mdash; {@code @Transactional}
 *           manages the JDBC connection lifecycle)</td></tr>
 *   <tr><td>{@code 1000-ACCTFILE-GET-NEXT} (L92&ndash;L116)</td>
 *       <td>{@link AccountRepository#findAll(Sort)} +
 *           iteration over the materialized {@link List}</td></tr>
 *   <tr><td>{@code DISPLAY ACCOUNT-RECORD} (L78)</td>
 *       <td>{@link #displayAccountRecord1100(Account)} (SLF4J INFO,
 *           reproducing the 11 field-label lines verbatim)</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-ACCT-RECORD} (L118&ndash;L131)</td>
 *       <td>{@link #displayAccountRecord1100(Account)}</td></tr>
 *   <tr><td>{@code 9000-ACCTFILE-CLOSE} (L151&ndash;L167)</td>
 *       <td>Spring transaction-manager auto-close at method exit
 *           (HikariCP returns the connection to the pool)</td></tr>
 *   <tr><td>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'}
 *       (L85)</td>
 *       <td>{@code LOG.info("END OF EXECUTION OF PROGRAM CBACT01C")}</td></tr>
 *   <tr><td>{@code GOBACK} (L87)</td>
 *       <td>{@code return new ReadResult(...)}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} (L169&ndash;L173)</td>
 *       <td>Java exceptions propagate to the caller after a
 *           best-effort {@code FAILED} audit emission (no
 *           {@code CEE3ABD} equivalent)</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1 / &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>One service per COBOL program:</b> per AAP &sect;0.7.1
 *       ("Isolate each COBOL program's logic in its own dedicated
 *       Java service class") this class corresponds one-to-one to
 *       {@code CBACT01C} &mdash; never combined with another COBOL
 *       program's logic.</li>
 *   <li><b>Constructor injection only:</b> both dependencies
 *       ({@link AccountRepository}, {@link AuditLogService}) are
 *       declared {@code final} and assigned via the canonical
 *       constructor. No {@code @Autowired} field injection per AAP
 *       &sect;0.7.1 ("Constructor injection for all {@code @Service},
 *       {@code @Repository}, {@code @Component}, adapter, and config
 *       beans &mdash; loose coupling required").</li>
 *   <li><b>Read-only transaction:</b> {@link #readAndDisplayAll()}
 *       is annotated {@code @Transactional(readOnly = true)} so
 *       PostgreSQL applies read-only-tuning optimisations (no WAL
 *       writes, no snapshot acquisition for write predicates) and
 *       Hibernate skips dirty-checking overhead. Matches the COBOL
 *       {@code OPEN INPUT ACCTFILE} semantic (input-only access).</li>
 *   <li><b>Sequential VSAM order preserved:</b> the
 *       {@code findAll(Sort.by("acctId"))} call sorts ascending on
 *       the primary key, reproducing the COBOL
 *       {@code ACCESS MODE IS SEQUENTIAL} +
 *       {@code RECORD KEY IS FD-ACCT-ID} semantic byte-for-byte.
 *       Ensures the Java output stream matches the COBOL output
 *       stream row-for-row for golden-output diffing during the
 *       parallel-run validation window per AAP &sect;0.6.2.</li>
 *   <li><b>{@link AtomicLong} counter:</b> the running record counter
 *       uses {@link AtomicLong} (rather than a primitive {@code long})
 *       to provide a stream-friendly mutation idiom &mdash; matches
 *       the semantics of a COBOL counter variable mutable from
 *       nested paragraphs, and is required by AAP agent_prompt rule
 *       12 for this service.</li>
 *   <li><b>Structured logging via SLF4J:</b> The COBOL {@code DISPLAY}
 *       statements emit to SYSOUT (mainframe operator console). The
 *       Java target emits to SLF4J &rarr; Logback &rarr;
 *       logstash-logback-encoder &rarr; CloudWatch Logs per AAP
 *       &sect;0.6.6. The 11 per-field labels are reproduced
 *       verbatim from the COBOL source (preserving the exact
 *       25-character label prefix including trailing spaces and the
 *       {@code ':'} separator) so a downstream log-parsing tool can
 *       diff Java output against COBOL fixtures during the
 *       parallel-run validation window per the Minimal Change
 *       Clause (AAP &sect;0.7.3).</li>
 *   <li><b>{@link AuditLogService#logBatchJobLifecycle}:</b> a
 *       structured batch-reader completion event is emitted to
 *       OpenSearch + CloudWatch upon successful scan completion. The
 *       payload carries the COBOL program ID ({@code "CBACT01C"}),
 *       entity name ({@code "ACCOUNT"}), and processed record count.
 *       Operators monitor this event for batch-job heartbeat and
 *       record-count parity against the COBOL source during the
 *       parallel-run validation window. The agent_prompt's
 *       suggested {@code logBatchReaderCompleted} method does not
 *       exist on the actual {@code AuditLogService}; the
 *       closest-matching method {@code logBatchJobLifecycle} is
 *       used instead per the schema's rule 9 escape hatch.</li>
 *   <li><b>No AWS SDK calls inline:</b> per AAP &sect;0.7.1 all AWS
 *       integration is encapsulated in
 *       {@code com.awsm2.carddemo.adapter} adapters; this service
 *       only invokes {@link AuditLogService} as a Spring bean and
 *       never touches OpenSearch / CloudWatch / S3 / KMS clients
 *       directly.</li>
 *   <li><b>No {@code CEE3ABD} equivalent:</b> the COBOL
 *       {@code 9999-ABEND-PROGRAM} paragraph (L169&ndash;L173)
 *       calls {@code CEE3ABD} to terminate the JVM on any I/O
 *       error. The Java target instead lets the exception propagate
 *       to the caller after emitting a best-effort {@code FAILED}
 *       audit event &mdash; aligning with Spring Batch step failure
 *       handling and allowing the caller (Spring Batch step, AWS
 *       Batch container, ad-hoc test, etc.) to decide on retry /
 *       abort semantics.</li>
 * </ul>
 *
 * <h2>Layered Architecture (AAP &sect;0.3.3)</h2>
 * <p>This class lives in the {@code service} layer between the
 * {@code repository} layer (Spring Data JPA) and any future
 * controller / scheduler / Step Functions task invocation. It depends
 * on the repository abstraction and the audit adapter abstraction
 * only &mdash; never on a concrete JDBC, JPA, OpenSearch, or
 * CloudWatch client. The dependency direction is strictly
 * {@code service} &rarr; {@code repository} and {@code service} &rarr;
 * {@code adapter}, never the reverse.</p>
 *
 * @see Account
 * @see AccountRepository
 * @see AuditLogService
 */
@Service
public class AccountFileReaderService {

    /**
     * SLF4J logger used to emit COBOL-equivalent {@code DISPLAY}
     * output lines. Per AAP &sect;0.6.6 Logback is configured with
     * {@code logstash-logback-encoder} so each {@code LOG.info(...)}
     * call lands in CloudWatch Logs as a structured JSON document
     * (message + level + thread + logger + MDC trace fields).
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(AccountFileReaderService.class);

    /**
     * Identifier of the source COBOL program that this service
     * translates. Used verbatim in the SLF4J log lines (matching the
     * COBOL {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'}
     * / {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'}
     * pattern at L71 and L85) and in the structured audit event
     * emitted to {@link AuditLogService#logBatchJobLifecycle}.
     *
     * <p>Mandated by AAP &sect;0.7.3 ("Inline traceability comments:
     * every translated paragraph carries a
     * {@code // COBOL: <PROGRAM>:<PARAGRAPH>} comment").</p>
     */
    private static final String COBOL_PROGRAM_ID = "CBACT01C";

    /**
     * Business entity name corresponding to the COBOL
     * {@code ACCOUNT-RECORD} layout ({@code app/cpy/CVACT01Y.cpy}).
     * Emitted in the structured audit payload so operators can
     * filter the OpenSearch / CloudWatch documents by entity during
     * the parallel-run validation window.
     */
    private static final String ENTITY_NAME = "ACCOUNT";

    /**
     * VSAM cluster name (preserved verbatim from
     * {@code app/jcl/ACCTFILE.jcl}) emitted in the audit payload for
     * cross-referencing against legacy operational dashboards. The
     * cluster does not exist on the AWS target (it has been replaced
     * by the {@code accounts} PostgreSQL table); the literal is
     * retained as documentation, not as a live identifier.
     */
    private static final String VSAM_CLUSTER =
            "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /**
     * Audit-event status marker emitted on successful scan
     * completion. Used by the canonical
     * {@link AuditLogService#logBatchJobLifecycle} contract whose
     * Javadoc enumerates the allowed status values as
     * {@code "STARTED" / "COMPLETED" / "FAILED" / "ABENDED"}.
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * Audit-event status marker emitted on scan failure. Used by
     * the exception path so operators can alarm on batch-reader
     * failures via CloudWatch metric dimensions.
     */
    private static final String STATUS_FAILED = "FAILED";

    /**
     * Sort key used to preserve the COBOL VSAM sequential-access
     * semantic ({@code ACCESS MODE IS SEQUENTIAL} on
     * {@code RECORD KEY FD-ACCT-ID}). The {@link Account} entity's
     * {@code acctId} field maps to the {@code acct_id} column (V001
     * migration), which is the primary key &mdash; ascending order
     * on this field is identical to the VSAM KSDS key sequence.
     *
     * <p>Declared as a constant so any future refactor that needs to
     * change the sort key (e.g. for batch parallelism) does so in
     * exactly one place.</p>
     */
    private static final String SORT_FIELD_ACCT_ID = "acctId";

    /**
     * Spring Data JPA repository for the {@link Account} entity.
     * Constructor-injected; replaces the COBOL
     * {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE} file handle
     * plus the implicit {@code OPEN INPUT / READ NEXT / CLOSE}
     * lifecycle of the VSAM KSDS sequential reader.
     */
    private final AccountRepository accountRepository;

    /**
     * CloudTrail + OpenSearch audit-log adapter. Constructor-injected;
     * used to emit the batch-reader lifecycle event at scan
     * completion (or failure). Per AAP &sect;0.7.1 this is one of the
     * eight dedicated AWS adapter classes that isolate AWS SDK calls
     * from the business-logic services.
     */
    private final AuditLogService auditLogService;

    /**
     * Canonical constructor &mdash; the sole means of dependency
     * injection for this service per AAP &sect;0.7.1
     * ("Constructor injection for all {@code @Service},
     * {@code @Repository}, {@code @Component}, adapter, and config
     * beans"). Both dependencies are
     * {@link Objects#requireNonNull validated non-null} at
     * construction so a misconfigured Spring context fails fast
     * during application startup rather than at the first method
     * invocation.
     *
     * @param accountRepository the Spring Data JPA repository for
     *                          the {@link Account} entity; must not
     *                          be {@code null}
     * @param auditLogService   the audit-log adapter bean; must not
     *                          be {@code null}
     */
    public AccountFileReaderService(AccountRepository accountRepository,
                                    AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Performs the sequential scan over the {@code accounts} table
     * emitting one structured log line per record. The complete Java
     * translation of the COBOL {@code CBACT01C} PROCEDURE DIVISION
     * (L70&ndash;L87). Returns a {@link ReadResult} carrying the
     * COBOL program identifier and the count of records processed
     * for downstream verification, audit, and parallel-run parity
     * checks.
     *
     * <p>Execution sequence (mirroring COBOL paragraph order):</p>
     * <ol>
     *   <li>Emit the COBOL-verbatim START marker
     *       ({@code 'START OF EXECUTION OF PROGRAM CBACT01C'},
     *       COBOL L71).</li>
     *   <li>Capture {@code startMillis} so the audit emission can
     *       report scan duration (operational metric beyond what the
     *       COBOL source captured, but useful for SLA monitoring).</li>
     *   <li>Issue a single
     *       {@link AccountRepository#findAll(Sort)} call sorted by
     *       {@code acctId} ascending &mdash; preserves the COBOL
     *       {@code ACCESS MODE SEQUENTIAL} on
     *       {@code RECORD KEY FD-ACCT-ID} semantic.</li>
     *   <li>Iterate the materialised result list. For each
     *       {@link Account}, invoke
     *       {@link #displayAccountRecord1100(Account)} (the Java
     *       equivalent of COBOL {@code DISPLAY ACCOUNT-RECORD} +
     *       {@code 1100-DISPLAY-ACCT-RECORD} on L78 and L118&ndash;L131)
     *       and increment the running counter.</li>
     *   <li>Emit the COBOL-verbatim END marker
     *       ({@code 'END OF EXECUTION OF PROGRAM CBACT01C'},
     *       COBOL L85).</li>
     *   <li>Emit a structured batch-lifecycle audit event with the
     *       COBOL program ID, entity name, and processed record
     *       count via {@link AuditLogService#logBatchJobLifecycle}
     *       so operators can monitor scan heartbeat, record-count
     *       parity against the COBOL source, and SLA timing.</li>
     *   <li>Return a {@link ReadResult} for programmatic
     *       consumption (e.g. by tests or batch-orchestration
     *       callers).</li>
     * </ol>
     *
     * <p><b>Transactional context:</b>
     * {@code @Transactional(readOnly = true)} marks the entire scan
     * as a read-only PostgreSQL transaction so the database can
     * apply read-only-tuning optimisations (no WAL writes, no
     * snapshot acquisition for write predicates). On exception the
     * transaction is rolled back by Spring's transaction manager and
     * a {@code FAILED} audit event is emitted on a best-effort basis
     * before the exception propagates to the caller.</p>
     *
     * @return a {@link ReadResult} carrying the COBOL program ID
     *         ({@code "CBACT01C"}) and the number of account rows
     *         iterated; the {@code recordCount} is always
     *         non-negative
     * @throws RuntimeException if the repository call fails (e.g.
     *         database connectivity loss); propagated to the caller
     *         after a best-effort {@code FAILED} audit emission
     */
    @Transactional(readOnly = true)
    public ReadResult readAndDisplayAll() {
        // COBOL: CBACT01C.cbl L71 — DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
        LOG.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

        // Capture start time so the audit emission carries scan
        // duration as an operational metric (beyond what the COBOL
        // source reported, but useful for SLA monitoring of the
        // batch reader against the existing mainframe runtime
        // baseline per AAP §0.7.2 NFR-performance).
        final long startMillis = System.currentTimeMillis();

        // COBOL: CBACT01C.cbl L62 — APPL-RESULT counter and
        // implicit READ-NEXT progression in 1000-ACCTFILE-GET-NEXT.
        // Use AtomicLong for the counter — semantically matches a
        // COBOL counter variable (which could be mutated in nested
        // paragraphs) and is idiomatic for use inside lambda /
        // Stream contexts if this method evolves to use them later.
        // Required by AAP agent_prompt rule 12 ("AtomicLong for
        // counter — semantic match for COBOL counter variable in a
        // stream-friendly idiom").
        final AtomicLong count = new AtomicLong(0L);

        // Execution-correlation ID for the audit emission. Generated
        // fresh per execution so each invocation can be correlated
        // across OpenSearch documents and CloudWatch alarms even
        // when run multiple times within the same JVM (e.g. from a
        // Spring Batch JobLauncherTestUtils harness).
        final String executionId = UUID.randomUUID().toString();

        try {
            // COBOL: CBACT01C:0000-ACCTFILE-OPEN (L133–L149) —
            // handled by Spring's transactional connection
            // acquisition; no explicit OPEN required because
            // @Transactional manages the JDBC connection lifecycle.
            //
            // COBOL: ACCESS MODE SEQUENTIAL on RECORD KEY FD-ACCT-ID
            // — preserve ACCT-ID ascending order via
            // Sort.by("acctId") so the Java scan emits rows in the
            // same order as the COBOL VSAM KSDS sequential read.
            // This is required for golden-output diffing during the
            // parallel-run validation window per AAP §0.6.2.
            //
            // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L74–L81) wraps
            //        PERFORM 1000-ACCTFILE-GET-NEXT (L92–L116):
            //            READ ACCTFILE-FILE INTO ACCOUNT-RECORD
            // Java equivalent: a single findAll(Sort) call returns
            // the entire result set materialised into memory; the
            // for-each loop replaces the PERFORM UNTIL loop. The
            // demo-scale ACCTDATA cluster (a few hundred rows in the
            // golden fixture under app/data/ASCII/acctdata.txt)
            // makes the pre-fetch acceptable; for production-scale
            // deployments a Spring Batch RepositoryItemReader with
            // chunk-oriented processing would be substituted while
            // preserving the externally-observable behaviour.
            final List<Account> accounts =
                    accountRepository.findAll(Sort.by(SORT_FIELD_ACCT_ID));

            for (Account account : accounts) {
                // COBOL: CBACT01C:1000-ACCTFILE-GET-NEXT (L92–L116) +
                // DISPLAY ACCOUNT-RECORD (L78) — emit the per-row
                // structured log line via the
                // 1100-DISPLAY-ACCT-RECORD paragraph translation.
                displayAccountRecord1100(account);
                count.incrementAndGet();
            }

            // COBOL: CBACT01C:9000-ACCTFILE-CLOSE (L151–L167) —
            // handled automatically by Spring's transaction manager
            // at method exit (the JDBC connection is returned to the
            // HikariCP pool).
            //
            // COBOL: CBACT01C.cbl L85 — DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
            LOG.info("END OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

            final long total = count.get();
            final long durationMillis = System.currentTimeMillis() - startMillis;

            // Emit the structured batch-reader completion event to
            // OpenSearch + CloudWatch so operators can monitor scan
            // heartbeat, record-count parity against the COBOL
            // source, and SLA timing. Per AAP §0.6.6 audit failures
            // are non-blocking — the AuditLogService catches and
            // logs any transport errors without re-throwing. The
            // schema's nominal logBatchReaderCompleted method does
            // not exist on AuditLogService; the closest-matching
            // method logBatchJobLifecycle is used per the schema's
            // rule 9 escape hatch.
            auditLogService.logBatchJobLifecycle(
                    COBOL_PROGRAM_ID,
                    executionId,
                    STATUS_COMPLETED,
                    durationMillis,
                    buildAuditPayload(total),
                    null);

            // COBOL: CBACT01C.cbl L87 — GOBACK
            // Java equivalent: return the ReadResult carrying the
            // COBOL program ID and the processed record count for
            // programmatic verification by tests, batch schedulers,
            // and parallel-run parity-diff tooling.
            return new ReadResult(COBOL_PROGRAM_ID, total);
        } catch (RuntimeException ex) {
            // COBOL: CBACT01C:9999-ABEND-PROGRAM (L169–L173) — in
            // the source CBACT01C terminates the JVM (CEE3ABD) on
            // any READ error. The Java target instead propagates
            // the exception to the caller after a best-effort
            // FAILED audit emission, allowing the caller (Spring
            // Batch step, AWS Batch container, ad-hoc test, etc.)
            // to decide on retry / abort semantics.
            final long durationMillis = System.currentTimeMillis() - startMillis;
            LOG.error("ABENDING PROGRAM {} cause={}",
                    COBOL_PROGRAM_ID, ex.getMessage(), ex);
            try {
                auditLogService.logBatchJobLifecycle(
                        COBOL_PROGRAM_ID,
                        executionId,
                        STATUS_FAILED,
                        durationMillis,
                        buildAuditPayload(count.get()),
                        null);
            } catch (RuntimeException auditEx) {
                // Audit failures must never mask the original
                // failure — defensively swallow with an error log
                // so the originating cause reaches the caller.
                LOG.error("Failed to emit FAILED audit event for {}: {}",
                        COBOL_PROGRAM_ID, auditEx.getMessage(), auditEx);
            }
            throw ex;
        }
    }

    /**
     * Emits the eleven per-record SLF4J INFO log lines corresponding
     * to the COBOL paragraph {@code 1100-DISPLAY-ACCT-RECORD}
     * ({@code app/cbl/CBACT01C.cbl} L118&ndash;L131). The field
     * labels are reproduced verbatim from the COBOL source &mdash;
     * including the exact 25-character label width (24 characters
     * + the {@code ':'} separator), trailing spaces, and field
     * order. This verbatim preservation is mandated by AAP
     * &sect;0.7.3 ("Minimal Change Clause: preserve COBOL paragraph
     * order and DISPLAY labels verbatim") so a downstream
     * log-parsing tool can diff the Java output against the COBOL
     * fixture row-for-row during the parallel-run validation
     * window per AAP &sect;0.6.2.
     *
     * <p>Field-to-getter mapping (CBACT01C L119&ndash;L130):</p>
     * <ol>
     *   <li>{@code ACCT-ID} &rarr; {@link Account#getAcctId()}
     *       ({@code PIC 9(11)}; primary key)</li>
     *   <li>{@code ACCT-ACTIVE-STATUS} &rarr;
     *       {@link Account#getAcctActiveStatus()}
     *       ({@code PIC X(01)}; 'Y' active / 'N' inactive)</li>
     *   <li>{@code ACCT-CURR-BAL} &rarr;
     *       {@link Account#getAcctCurrBal()}
     *       ({@code PIC S9(10)V99}; {@link java.math.BigDecimal}
     *       precision = 12, scale = 2 per AAP &sect;0.6.1)</li>
     *   <li>{@code ACCT-CREDIT-LIMIT} &rarr;
     *       {@link Account#getAcctCreditLimit()}
     *       ({@code PIC S9(10)V99})</li>
     *   <li>{@code ACCT-CASH-CREDIT-LIMIT} &rarr;
     *       {@link Account#getAcctCashCreditLimit()}
     *       ({@code PIC S9(10)V99})</li>
     *   <li>{@code ACCT-OPEN-DATE} &rarr;
     *       {@link Account#getAcctOpenDate()}
     *       ({@code PIC X(10)}; ISO-8601 {@code 'YYYY-MM-DD'}
     *       parsed to {@link java.time.LocalDate})</li>
     *   <li>{@code ACCT-EXPIRAION-DATE} &rarr;
     *       {@link Account#getAcctExpirationDate()}
     *       ({@code PIC X(10)}; the COBOL field name has a
     *       misspelling preserved here in the DISPLAY label for
     *       byte-identical parallel-run output, while the Java
     *       getter uses the corrected spelling per AAP &sect;0.4.1
     *       V001)</li>
     *   <li>{@code ACCT-REISSUE-DATE} &rarr;
     *       {@link Account#getAcctReissueDate()}
     *       ({@code PIC X(10)})</li>
     *   <li>{@code ACCT-CURR-CYC-CREDIT} &rarr;
     *       {@link Account#getAcctCurrCycCredit()}
     *       ({@code PIC S9(10)V99})</li>
     *   <li>{@code ACCT-CURR-CYC-DEBIT} &rarr;
     *       {@link Account#getAcctCurrCycDebit()}
     *       ({@code PIC S9(10)V99})</li>
     *   <li>{@code ACCT-GROUP-ID} &rarr;
     *       {@link Account#getAcctGroupId()}
     *       ({@code PIC X(10)}; disclosure-group lookup key)</li>
     * </ol>
     *
     * <p>The {@code ACCT-ADDR-ZIP} field is intentionally omitted
     * from this method &mdash; the COBOL source's
     * {@code 1100-DISPLAY-ACCT-RECORD} paragraph does not include
     * it either (the COBOL display lists ACCT-ID, ACCT-ACTIVE-STATUS,
     * the five monetary fields, the three date fields,
     * ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, and ACCT-GROUP-ID,
     * skipping ACCT-ADDR-ZIP). Preserving the COBOL omission is
     * mandated by the Minimal Change Clause (AAP &sect;0.7.3).</p>
     *
     * <p>Account data is non-PII (ACCT-ID is an internal identifier
     * not derivable from a card number; balances and credit limits
     * are sensitive financial data but not PII subject to
     * PCI-DSS masking like card numbers, SSNs, or DOB). The
     * {@link Account#toString()} masking discipline applies to PII
     * fields elsewhere; this DISPLAY paragraph emits all 11 fields
     * verbatim to preserve byte-for-byte COBOL parity.</p>
     *
     * <p>Defensive guard: if {@code account} is {@code null} (which
     * cannot happen in production because
     * {@link AccountRepository#findAll(Sort)} never returns a list
     * containing {@code null} elements, but may occur in mocked
     * unit tests), a single {@code "(null record)"} marker is
     * logged and the method returns without dereferencing the
     * argument.</p>
     *
     * @param account the account entity to log; may be {@code null}
     *                (defensive)
     */
    private void displayAccountRecord1100(Account account) {
        // COBOL: CBACT01C:1100-DISPLAY-ACCT-RECORD (L118–L131) —
        // preserve field order and 25-character label width verbatim.
        if (account == null) {
            // Defensive — Spring Data JPA never returns null elements
            // in a List, but a unit test or mocked repository might.
            LOG.info("ACCOUNT-RECORD: (null record)");
            return;
        }
        // COBOL: L119 — DISPLAY 'ACCT-ID                 :' ACCT-ID
        LOG.info("ACCT-ID                 :{}", account.getAcctId());
        // COBOL: L120 — DISPLAY 'ACCT-ACTIVE-STATUS      :' ACCT-ACTIVE-STATUS
        LOG.info("ACCT-ACTIVE-STATUS      :{}", account.getAcctActiveStatus());
        // COBOL: L121 — DISPLAY 'ACCT-CURR-BAL           :' ACCT-CURR-BAL
        LOG.info("ACCT-CURR-BAL           :{}", account.getAcctCurrBal());
        // COBOL: L122 — DISPLAY 'ACCT-CREDIT-LIMIT       :' ACCT-CREDIT-LIMIT
        LOG.info("ACCT-CREDIT-LIMIT       :{}", account.getAcctCreditLimit());
        // COBOL: L123 — DISPLAY 'ACCT-CASH-CREDIT-LIMIT  :' ACCT-CASH-CREDIT-LIMIT
        LOG.info("ACCT-CASH-CREDIT-LIMIT  :{}", account.getAcctCashCreditLimit());
        // COBOL: L124 — DISPLAY 'ACCT-OPEN-DATE          :' ACCT-OPEN-DATE
        LOG.info("ACCT-OPEN-DATE          :{}", account.getAcctOpenDate());
        // COBOL: L125 — DISPLAY 'ACCT-EXPIRAION-DATE     :' ACCT-EXPIRAION-DATE
        // The COBOL label carries the misspelling "EXPIRAION" (sic);
        // preserved verbatim for byte-identical parallel-run output.
        // The Java getter uses the corrected spelling per AAP §0.4.1.
        LOG.info("ACCT-EXPIRAION-DATE     :{}", account.getAcctExpirationDate());
        // COBOL: L126 — DISPLAY 'ACCT-REISSUE-DATE       :' ACCT-REISSUE-DATE
        LOG.info("ACCT-REISSUE-DATE       :{}", account.getAcctReissueDate());
        // COBOL: L127 — DISPLAY 'ACCT-CURR-CYC-CREDIT    :' ACCT-CURR-CYC-CREDIT
        LOG.info("ACCT-CURR-CYC-CREDIT    :{}", account.getAcctCurrCycCredit());
        // COBOL: L128 — DISPLAY 'ACCT-CURR-CYC-DEBIT     :' ACCT-CURR-CYC-DEBIT
        LOG.info("ACCT-CURR-CYC-DEBIT     :{}", account.getAcctCurrCycDebit());
        // COBOL: L129 — DISPLAY 'ACCT-GROUP-ID           :' ACCT-GROUP-ID
        LOG.info("ACCT-GROUP-ID           :{}", account.getAcctGroupId());
        // COBOL: L130 — DISPLAY '-------------------------------------------------'
        // The COBOL record-terminator banner is emitted verbatim so
        // a downstream log-parsing tool can split the output stream
        // into per-record blocks.
        LOG.info("-------------------------------------------------");
    }

    /**
     * Builds the structured audit-event payload for the
     * {@link AuditLogService#logBatchJobLifecycle} emission. The
     * payload carries the entity name (mapped to the underlying
     * VSAM cluster {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}), the
     * processed record count, and the COBOL program identifier for
     * cross-referencing against operational dashboards.
     *
     * <p>The payload contains <b>no PII</b> and <b>no PAN-like
     * sequences</b> &mdash; only operational metadata.
     * {@link AuditLogService} additionally applies a defense-in-depth
     * sanitization pass that removes sensitive keys and masks
     * PAN-like sequences as a final guardrail, but this method
     * intentionally builds the payload from constants and the
     * integer record count only.</p>
     *
     * <p>The returned {@link Map} is a {@link LinkedHashMap} so
     * field insertion order is preserved: {@code program_id} &rarr;
     * {@code entity_name} &rarr; {@code record_count} &rarr;
     * {@code vsam_cluster}. The OpenSearch dashboard sees fields in
     * a stable, human-friendly sequence when the document is
     * rendered &mdash; a usability nicety that does not affect
     * OpenSearch's inverted index.</p>
     *
     * @param recordCount the number of account rows processed by the
     *                    scan; always non-negative
     * @return a mutable {@link Map} carrying the audit-event
     *         payload &mdash; safe to pass to
     *         {@link AuditLogService#logBatchJobLifecycle} which
     *         will sanitize and merge it into the audit document
     */
    private static Map<String, Object> buildAuditPayload(long recordCount) {
        // LinkedHashMap preserves insertion order so the OpenSearch
        // dashboard sees fields in a stable, human-friendly sequence
        // when the document is rendered. This is a usability nicety
        // that does not affect OpenSearch's inverted index.
        //
        // The schema's external_imports list nominates HashMap (per
        // agent_prompt rule 9 escape-hatch context); LinkedHashMap
        // is a HashMap subclass and therefore satisfies the import
        // contract while providing the deterministic iteration order
        // operators expect from a structured audit document.
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("program_id", COBOL_PROGRAM_ID);
        payload.put("entity_name", ENTITY_NAME);
        payload.put("record_count", recordCount);
        payload.put("vsam_cluster", VSAM_CLUSTER);
        return payload;
    }

    /**
     * Result envelope returned by {@link #readAndDisplayAll()}.
     * Carries the COBOL program identifier ({@code "CBACT01C"}) and
     * the number of {@link Account} rows processed during the
     * sequential scan. Designed to be consumed by tests and
     * batch-orchestration callers (e.g., a future Spring Batch
     * {@code Tasklet} wrapping this service).
     *
     * <p>Implemented as a Java {@code record} so the type is
     * immutable, the canonical constructor and accessors are
     * generated by the compiler, and the type-equality contract is
     * deterministic. Records were introduced in Java 16 and are
     * available under the Java 17+ target per AAP &sect;0.5.1.</p>
     *
     * <p>The companion {@link AccountRepository#count()} method
     * provides a lighter-weight row-count check; this record is
     * returned only by the full DISPLAY-paragraph scan so its
     * {@code recordCount} is the exact number of rows for which
     * {@link #displayAccountRecord1100(Account)} was successfully
     * invoked.</p>
     *
     * @param programId   the COBOL program identifier
     *                    ({@code "CBACT01C"}) preserved verbatim
     *                    for traceability per AAP &sect;0.7.3
     * @param recordCount the number of account rows processed by
     *                    the scan; always non-negative
     */
    public record ReadResult(String programId, long recordCount) {
    }
}
