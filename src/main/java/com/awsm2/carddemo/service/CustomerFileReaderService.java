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
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.repository.CustomerRepository;
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
 * Customer file reader &mdash; sequential scan over the {@code customers}
 * table emitting one structured log line per record. The Java target for
 * the COBOL batch program {@code app/cbl/CBCUS01C.cbl}
 * (&quot;Read and print customer data file.&quot;).
 *
 * <h2>COBOL Source Provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBCUS01C.cbl} &mdash;
 *       batch reader; declares
 *       {@code SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE ORGANIZATION IS
 *       INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CUST-ID}
 *       (9-digit primary key). The PROCEDURE DIVISION (L70&ndash;L87)
 *       executes:
 *       <pre>
 *       DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
 *       PERFORM 0000-CUSTFILE-OPEN
 *       PERFORM UNTIL END-OF-FILE = 'Y'
 *           PERFORM 1000-CUSTFILE-GET-NEXT
 *           IF END-OF-FILE = 'N' DISPLAY CUSTOMER-RECORD
 *       END-PERFORM
 *       PERFORM 9000-CUSTFILE-CLOSE
 *       DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
 *       GOBACK
 *       </pre></li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVCUS01Y.cpy} (canonical)
 *       and {@code app/cpy/CUSTREC.cpy} (variant, identical 500-byte
 *       layout) define {@code CUSTOMER-RECORD} (500 bytes) with 18
 *       business fields plus {@code FILLER PIC X(168)}. Includes
 *       {@code CUST-ID} ({@code PIC 9(09)}), {@code CUST-FIRST-NAME}
 *       /{@code MIDDLE-NAME}/{@code LAST-NAME} ({@code PIC X(25)}),
 *       address lines, {@code CUST-SSN} ({@code PIC 9(09)}; PII),
 *       {@code CUST-GOVT-ISSUED-ID} ({@code PIC X(20)}; PII),
 *       {@code CUST-DOB-YYYY-MM-DD} ({@code PIC X(10)}; PII),
 *       phones, EFT routing, primary-cardholder indicator, and
 *       FICO score.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} &mdash; KEYS(9 0),
 *       RECORDSIZE(500 500), INDEXED (per
 *       {@code app/jcl/CUSTFILE.jcl} L46&ndash;L59 and
 *       {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>JCL invocation:</b> {@code app/jcl/READCUST.jcl} (demo
 *       inspection job) &mdash; replaced operationally by AWS Batch
 *       ad-hoc job invocation per AAP &sect;0.4.1.</li>
 * </ul>
 *
 * <h2>COBOL Paragraph &harr; Java Method Mapping</h2>
 * <table>
 *   <caption>CBCUS01C.cbl &harr; CustomerFileReaderService</caption>
 *   <tr><th>COBOL paragraph / line</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L70&ndash;L87)</td>
 *       <td>{@link #readAndDisplayAll()}</td></tr>
 *   <tr><td>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'}
 *       (L71)</td>
 *       <td>{@code LOG.info("START OF EXECUTION OF PROGRAM CBCUS01C")}</td></tr>
 *   <tr><td>{@code 0000-CUSTFILE-OPEN} (L118&ndash;L134)</td>
 *       <td>Spring Data JPA implicit DB connection acquisition (no
 *           explicit open required &mdash; JPA manages the JDBC
 *           connection lifecycle)</td></tr>
 *   <tr><td>{@code 1000-CUSTFILE-GET-NEXT} (L92&ndash;L116)</td>
 *       <td>{@link CustomerRepository#findAll(Sort)} +
 *           iteration over the materialized {@link List}</td></tr>
 *   <tr><td>{@code DISPLAY CUSTOMER-RECORD} (L78 + L96)</td>
 *       <td>{@link #displayCustomerRecord(Customer)} &mdash; per-row
 *           structured log line with PCI-DSS-mandated PII masking</td></tr>
 *   <tr><td>{@code 9000-CUSTFILE-CLOSE} (L136&ndash;L152)</td>
 *       <td>(automatic by Spring at transaction commit /
 *           connection release)</td></tr>
 *   <tr><td>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'}
 *       (L85)</td>
 *       <td>{@code LOG.info("END OF EXECUTION OF PROGRAM CBCUS01C")} +
 *           {@link AuditLogService} batch lifecycle event emission</td></tr>
 *   <tr><td>{@code Z-ABEND-PROGRAM} (L154&ndash;L158)</td>
 *       <td>Spring/Hibernate exception propagation (no explicit
 *           {@code CEE3ABD} call) &mdash; runtime exceptions
 *           propagate up to the caller; Spring's transaction manager
 *           rolls back the read-only transaction context</td></tr>
 * </table>
 *
 * <h2>PCI-DSS / PII Discipline (AAP &sect;0.6.6 &amp; &sect;0.7.1)</h2>
 * <p>The COBOL source emits {@code DISPLAY CUSTOMER-RECORD} which dumps
 * the entire 500-byte record (including the raw SSN, government-issued
 * ID, full DOB, phone numbers, and full mailing address) to SYSOUT. This
 * was acceptable on z/OS where SYSOUT was routed to JES held output
 * accessible only to authorized operators. In the cloud-native target
 * (AAP &sect;0.6.6 mandates that <em>"No plaintext card/account data in
 * logs &mdash; enforced via CloudWatch log filters + Macie S3
 * scanning"</em>) every PII field must be masked or omitted before it
 * reaches CloudWatch Logs or OpenSearch.</p>
 *
 * <p>This service therefore applies the following MANDATORY
 * (non-negotiable) PII protections to every per-record log emission:</p>
 * <ul>
 *   <li><b>SSN</b> &mdash; masked to {@code "***-**-NNNN"} (last 4
 *       digits only) via {@link #maskSsn(Long)}. Never logged in
 *       plaintext.</li>
 *   <li><b>Date of birth</b> &mdash; <b>OMITTED</b> entirely from log
 *       lines. DOB combined with name + ZIP is PII under HIPAA and
 *       multiple state privacy laws.</li>
 *   <li><b>Phone numbers</b> &mdash; <b>OMITTED</b> entirely from log
 *       lines. Direct contact numbers are PII.</li>
 *   <li><b>Government-issued ID</b> &mdash; <b>OMITTED</b> entirely
 *       from log lines (driver's license, passport, state ID).</li>
 *   <li><b>Full address</b> &mdash; <b>OMITTED</b> from log lines.
 *       Only the state code is retained for operational filtering.</li>
 *   <li><b>Email</b> &mdash; not present in {@code CVCUS01Y.cpy} so
 *       no action required, but as a forward compatibility note,
 *       email would be PII and would be omitted.</li>
 * </ul>
 *
 * <p>This is a deliberate, AAP-approved security upgrade over the
 * COBOL source per AAP &sect;0.6.6 (<em>"No plaintext card/account
 * data in logs"</em>) and AAP &sect;0.7.1 (<em>"Preserve all existing
 * functionality"</em>, exempted for PII per the explicit PCI-DSS
 * carve-out).</p>
 *
 * <h2>Sequential Order Preservation (AAP &sect;0.7.1 Minimal Change)</h2>
 * <p>The COBOL source declares
 * {@code ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CUST-ID}, which
 * causes VSAM to return rows in primary-key (CUST-ID) ascending order.
 * To preserve this semantic, the Java target uses
 * {@link Sort#by(String...)} keyed on {@code custId} so the relational
 * scan emits rows in identical order. This guarantees parallel-run
 * byte-level parity of {@code DISPLAY} output ordering between the
 * COBOL source and the Java target during the cutover validation
 * window.</p>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li><b>Pre-fetch via {@code findAll(Sort)}:</b> the agent_prompt
 *       explicitly specifies pre-fetch via
 *       {@link CustomerRepository#findAll(Sort)} returning
 *       {@code List<Customer>}. Demo scale (a few hundred customer
 *       rows in the golden fixture) makes this acceptable. Per the
 *       agent_prompt note: <em>"For very large customer tables,
 *       consider streaming via {@code Stream<Customer>} and
 *       {@code @QueryHints} for cursor-based fetching. Demo scale is
 *       small; pre-fetch via {@code findAll} is acceptable."</em></li>
 *   <li><b>Read-only transaction:</b>
 *       {@code @Transactional(readOnly = true)} on
 *       {@link #readAndDisplayAll()} enables PostgreSQL read-only
 *       optimizations (no XID acquisition, no WAL writes) and
 *       documents intent for code review.</li>
 *   <li><b>Constructor injection only:</b> both collaborators
 *       ({@link CustomerRepository}, {@link AuditLogService}) are
 *       injected through the constructor as {@code final} fields per
 *       AAP &sect;0.7.1 (<em>"Dependency injection for loose
 *       coupling"</em>) and the agent_prompt's explicit prohibition
 *       of {@code @Autowired} field injection.</li>
 *   <li><b>Audit emission via
 *       {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}:</b>
 *       a structured batch-reader completion event is emitted to
 *       OpenSearch + CloudWatch upon successful scan completion. The
 *       payload carries the COBOL program ID
 *       ({@code "CBCUS01C"}), entity name ({@code "CUSTOMER"}), and
 *       processed record count. Operators monitor this event for
 *       batch-job heartbeat and record-count parity against the
 *       COBOL source during the parallel-run validation window.</li>
 *   <li><b>No AWS SDK calls inline:</b> per AAP &sect;0.7.1 all AWS
 *       integration is encapsulated in
 *       {@code com.awsm2.carddemo.adapter} adapters; this service
 *       only invokes {@link AuditLogService} as a Spring bean and
 *       never touches OpenSearch / CloudWatch / S3 / KMS clients
 *       directly.</li>
 * </ul>
 *
 * <h2>Layered Architecture (AAP &sect;0.3.3)</h2>
 * <p>This class lives in the {@code service} layer between the
 * {@code repository} layer (Spring Data JPA) and any future
 * controller / scheduler / Step Functions task invocation. It depends
 * on the repository abstraction and the audit adapter abstraction
 * only &mdash; never on persistence implementations (Hibernate,
 * JDBC) or AWS SDK clients directly.</p>
 *
 * @see com.awsm2.carddemo.domain.Customer the JPA entity emitted by
 *      this reader
 * @see com.awsm2.carddemo.repository.CustomerRepository the data-access
 *      port
 * @see com.awsm2.carddemo.adapter.AuditLogService the audit-event
 *      emission port
 */
// Replaces: COBOL batch program app/cbl/CBCUS01C.cbl (Read and Print Customer Data File)
// Replaces: VSAM cluster AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS sequential reader
// Replaces: JCL job app/jcl/READCUST.jcl (operationally; AWS Batch ad-hoc job invocation)
@Service
public class CustomerFileReaderService {

    /**
     * SLF4J logger emitting the COBOL-equivalent START / END markers,
     * per-record {@code CUSTOMER-RECORD} lines (with PCI-DSS-mandated
     * masking), and any operational diagnostics. Routed via Logback +
     * {@code logstash-logback-encoder} to CloudWatch Logs in
     * structured JSON per AAP &sect;0.6.6.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerFileReaderService.class);

    /**
     * COBOL program identifier preserved verbatim for traceability
     * (AAP &sect;0.7.3 refactor discipline &mdash; <em>"Inline
     * traceability comments: every translated paragraph carries a
     * {@code // COBOL: <PROGRAM>:<PARAGRAPH>} comment"</em>). Used as
     * the audit event's {@code jobName} dimension and embedded in the
     * START / END log markers.
     */
    private static final String COBOL_PROGRAM_ID = "CBCUS01C";

    /**
     * Logical entity name corresponding to the COBOL VSAM cluster
     * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}. Used as a payload
     * field in the audit event so OpenSearch dashboards can filter
     * batch lifecycle records by underlying entity / table.
     */
    private static final String ENTITY_NAME = "CUSTOMER";

    /**
     * Audit-event status marker emitted on successful scan completion
     * (mirrors the Spring Batch / AWS Batch lifecycle vocabulary
     * documented in {@link AuditLogService#logBatchJobLifecycle}).
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * Audit-event status marker emitted on scan failure. Used by the
     * exception path so operators can alarm on batch-reader failures
     * via CloudWatch metric dimensions.
     */
    private static final String STATUS_FAILED = "FAILED";

    /**
     * Sort key used to preserve the COBOL VSAM sequential-access
     * semantic (ACCESS MODE IS SEQUENTIAL on RECORD KEY FD-CUST-ID).
     * The Customer entity's {@code custId} field maps to the
     * {@code cust_id} column (V003 migration), which is the primary
     * key &mdash; ascending order on this field is identical to the
     * VSAM KSDS key sequence.
     */
    private static final String SORT_FIELD_CUST_ID = "custId";

    /**
     * The Spring Data JPA repository for the {@link Customer} entity.
     * Used exclusively to issue {@link CustomerRepository#findAll(Sort)}
     * for the sequential scan. No other repository method is needed by
     * the {@code CBCUS01C} translation per AAP &sect;0.4.1.
     */
    private final CustomerRepository customerRepository;

    /**
     * Audit-event adapter (OpenSearch + CloudWatch) used to emit a
     * structured batch-reader completion / failure event at the end
     * of the scan. The adapter encapsulates all AWS SDK calls so this
     * service has no direct dependency on AWS clients per AAP
     * &sect;0.7.1 (<em>"never inline AWS SDK calls in business
     * logic"</em>).
     */
    private final AuditLogService auditLogService;

    /**
     * Constructor injection per AAP &sect;0.7.1 (<em>"Dependency
     * injection for loose coupling"</em>). Both collaborators must be
     * non-{@code null} &mdash; Spring DI normally guarantees this, but
     * the explicit {@link Objects#requireNonNull(Object, String)}
     * guard protects unit tests, ad-hoc instantiations, and
     * bean-definition errors.
     *
     * @param customerRepository the customer JPA repository bean;
     *                           must not be {@code null}
     * @param auditLogService    the audit-log adapter bean; must not
     *                           be {@code null}
     */
    public CustomerFileReaderService(CustomerRepository customerRepository,
                                     AuditLogService auditLogService) {
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Performs the sequential scan over the {@code customers} table
     * emitting one structured log line per record. The complete Java
     * translation of the COBOL {@code CBCUS01C} PROCEDURE DIVISION
     * (L70&ndash;L87). Returns a {@link ReadResult} carrying the
     * COBOL program identifier and the count of records processed
     * for downstream verification, audit, and parallel-run parity
     * checks.
     *
     * <p>Execution sequence (mirroring COBOL paragraph order):</p>
     * <ol>
     *   <li>Emit the COBOL-verbatim START marker
     *       ({@code 'START OF EXECUTION OF PROGRAM CBCUS01C'},
     *       COBOL L71).</li>
     *   <li>Capture {@code startMillis} so the audit emission can
     *       report scan duration (operational metric beyond what the
     *       COBOL source captured, but useful for SLA monitoring).</li>
     *   <li>Issue a single
     *       {@link CustomerRepository#findAll(Sort)} call sorted by
     *       {@code custId} ascending &mdash; preserves the COBOL
     *       ACCESS MODE SEQUENTIAL on RECORD KEY FD-CUST-ID
     *       semantic.</li>
     *   <li>Iterate the materialised result list. For each customer,
     *       invoke {@link #displayCustomerRecord(Customer)} (the
     *       Java equivalent of COBOL {@code DISPLAY CUSTOMER-RECORD}
     *       on L78 / L96) and increment the running counter.</li>
     *   <li>Emit the COBOL-verbatim END marker
     *       ({@code 'END OF EXECUTION OF PROGRAM CBCUS01C'},
     *       COBOL L85).</li>
     *   <li>Emit a structured batch-lifecycle audit event with the
     *       COBOL program ID, entity name, and processed record
     *       count via {@link AuditLogService#logBatchJobLifecycle}
     *       so operators can monitor scan heartbeat, record-count
     *       parity against the COBOL source, and SLA timing.</li>
     *   <li>Return a {@link ReadResult} for programmatic
     *       consumption (e.g., by tests or batch-orchestration
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
     *         ({@code "CBCUS01C"}) and the number of customer rows
     *         iterated
     * @throws RuntimeException if the repository call fails (e.g.
     *         database connectivity loss); propagated to the caller
     *         after a best-effort {@code FAILED} audit emission
     */
    @Transactional(readOnly = true)
    public ReadResult readAndDisplayAll() {
        // COBOL: CBCUS01C.cbl L71 — DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
        LOG.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

        // Capture start time so the audit emission carries scan
        // duration as an operational metric (beyond what the COBOL
        // source reported but useful for SLA monitoring).
        final long startMillis = System.currentTimeMillis();

        // Use AtomicLong for the counter — semantically matches a
        // COBOL counter variable (which could be mutated in nested
        // paragraphs) and is idiomatic for use inside lambda /
        // Stream contexts if this method evolves to use them later.
        final AtomicLong count = new AtomicLong(0L);

        // Execution-correlation ID for the audit emission. Generated
        // fresh per execution so each invocation can be correlated
        // across OpenSearch documents and CloudWatch alarms even when
        // run multiple times within the same JVM.
        final String executionId = UUID.randomUUID().toString();

        try {
            // COBOL: 0000-CUSTFILE-OPEN (L118–L134) — handled by
            // Spring's transactional connection acquisition; no
            // explicit OPEN required because @Transactional manages
            // the JDBC connection lifecycle.
            //
            // COBOL: ACCESS MODE SEQUENTIAL on RECORD KEY FD-CUST-ID
            // — preserve CUST-ID ascending order via Sort.by("custId")
            // so the Java scan emits rows in the same order as the
            // COBOL VSAM KSDS sequential read.
            //
            // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L74–L81) wraps
            //        PERFORM 1000-CUSTFILE-GET-NEXT (L92–L116):
            //            READ CUSTFILE-FILE INTO CUSTOMER-RECORD
            // Java equivalent: a single findAll(Sort) call returns
            // the entire result set materialised into memory; the
            // for-each loop replaces the PERFORM UNTIL loop. Demo
            // scale (golden fixture is small) makes this acceptable
            // per the agent_prompt's "pre-fetch via findAll is
            // acceptable" guidance.
            final List<Customer> customers = customerRepository.findAll(Sort.by(SORT_FIELD_CUST_ID));

            for (Customer customer : customers) {
                // COBOL: DISPLAY CUSTOMER-RECORD (L78 + L96) — emit
                // the per-row structured log line (with mandatory
                // PCI-DSS PII masking per AAP §0.6.6).
                displayCustomerRecord(customer);
                count.incrementAndGet();
            }

            // COBOL: 9000-CUSTFILE-CLOSE (L136–L152) — handled
            // automatically by Spring's transaction manager at
            // method exit (connection returned to the HikariCP
            // pool).
            //
            // COBOL: L85 — DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
            LOG.info("END OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

            final long total = count.get();
            final long durationMillis = System.currentTimeMillis() - startMillis;

            // Emit the structured batch-reader completion event to
            // OpenSearch + CloudWatch so operators can monitor scan
            // heartbeat, record-count parity against the COBOL
            // source, and SLA timing. Per AAP §0.6.6 audit failures
            // are non-blocking — the AuditLogService catches and
            // logs any transport errors without re-throwing.
            auditLogService.logBatchJobLifecycle(
                    COBOL_PROGRAM_ID,
                    executionId,
                    STATUS_COMPLETED,
                    durationMillis,
                    buildAuditPayload(total),
                    null);

            // COBOL: GOBACK (L87) — Java method return; the
            // ReadResult carries the COBOL program ID and the
            // processed record count for programmatic verification
            // by tests, batch schedulers, and parallel-run
            // parity-diff tooling.
            return new ReadResult(COBOL_PROGRAM_ID, total);
        } catch (RuntimeException ex) {
            // COBOL: Z-ABEND-PROGRAM (L154–L158) — in the source
            // CBCUS01C terminates the JVM (CEE3ABD) on any READ
            // error. The Java target instead propagates the
            // exception to the caller after a best-effort FAILED
            // audit emission, allowing the caller (Spring Batch
            // step, AWS Batch container, ad-hoc test, etc.) to
            // decide on retry / abort semantics.
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
     * Emits a single per-record structured log line corresponding to
     * the COBOL {@code DISPLAY CUSTOMER-RECORD} statement at
     * {@code CBCUS01C.cbl} L78 and L96. The COBOL source emits the
     * entire 500-byte record (including all PII fields); this Java
     * target emits only the non-PII subset that is safe for
     * CloudWatch Logs / OpenSearch persistence per the AAP
     * &sect;0.6.6 PCI-DSS discipline:
     *
     * <ul>
     *   <li>{@code custId} &mdash; non-PII (operational identifier);
     *       emitted verbatim.</li>
     *   <li>{@code custFirstName}, {@code custLastName} &mdash;
     *       emitted verbatim. The COBOL source emits these as part
     *       of the full record; the Java target preserves them so
     *       operators can visually verify scan output during the
     *       parallel-run window.</li>
     *   <li>{@code custSsn} &mdash; <b>MASKED</b> to
     *       {@code "***-**-NNNN"} (last 4 digits) via
     *       {@link #maskSsn(Long)} per AAP &sect;0.6.6.</li>
     *   <li>{@code custAddrStateCd} &mdash; emitted verbatim
     *       (state code alone is not PII).</li>
     * </ul>
     *
     * <p>The following PII fields are <b>OMITTED ENTIRELY</b> from
     * the log line even though they are present in the COBOL source
     * {@code DISPLAY}:</p>
     * <ul>
     *   <li>{@code custMiddleName} &mdash; redundant when first +
     *       last are present.</li>
     *   <li>{@code custAddrLine1} / {@code Line2} / {@code Line3}
     *       &mdash; full street address is PII when combined with
     *       name.</li>
     *   <li>{@code custAddrCountryCd}, {@code custAddrZip} &mdash;
     *       country / ZIP triangulates identity when combined with
     *       name + DOB; conservatively omitted.</li>
     *   <li>{@code custPhoneNum1} / {@code custPhoneNum2} &mdash;
     *       direct contact numbers are PII.</li>
     *   <li>{@code custGovtIssuedId} &mdash; driver's license /
     *       passport / state ID; PII subject to the same masking
     *       rules as SSN.</li>
     *   <li>{@code custDobYyyyMmDd} &mdash; date of birth combined
     *       with name + ZIP is PII under HIPAA and multiple state
     *       privacy laws.</li>
     *   <li>{@code custEftAccountId} &mdash; bank-routing
     *       identifier is sensitive financial data.</li>
     *   <li>{@code custFicoCreditScore} &mdash; sensitive financial
     *       data; redacted at this layer for consistency with the
     *       upstream Logback patterns.</li>
     *   <li>{@code custPriCardHolderInd} &mdash; non-PII but
     *       omitted to keep log line concise; available through
     *       {@link Customer#getCustPriCardHolderInd()} for callers
     *       that explicitly need it.</li>
     * </ul>
     *
     * <p>Defensive guard: if {@code customer} is {@code null} (which
     * cannot happen in production because
     * {@link CustomerRepository#findAll(Sort)} never returns a list
     * containing {@code null} elements, but may occur in mocked
     * unit tests), a single {@code "(null record)"} marker is logged
     * and the method returns without dereferencing the argument.</p>
     *
     * @param customer the customer entity to log; may be
     *                 {@code null} (defensive)
     */
    private void displayCustomerRecord(Customer customer) {
        // COBOL: DISPLAY CUSTOMER-RECORD (entire 500-byte CVCUS01Y record)
        // PCI-DSS (AAP §0.6.6): mask SSN; omit DOB, phones, address, govt-ID.
        if (customer == null) {
            // Defensive — Spring Data JPA never returns null elements in
            // a List, but a unit test or mocked repository might.
            LOG.info("CUSTOMER-RECORD: (null record)");
            return;
        }
        LOG.info("CUSTOMER-RECORD: custId={} firstName={} lastName={} ssn={} state={}",
                customer.getCustId(),
                customer.getCustFirstName(),
                customer.getCustLastName(),
                maskSsn(customer.getCustSsn()),
                customer.getCustAddrStateCd());
        // COBOL DISPLAY emits a record terminator (mainframe SYSOUT
        // implicit newline + form-feed); we emit a visual separator
        // so the output is easy to read in CloudWatch Logs Insights
        // and other log-viewer tools that present records as a
        // continuous stream.
        LOG.info("-------------------------------------------------");
    }

    /**
     * Builds the structured audit-event payload for the
     * {@link AuditLogService#logBatchJobLifecycle} emission. The
     * payload carries the entity name (mapped to the underlying
     * VSAM cluster {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}), the
     * processed record count, and the COBOL program identifier for
     * cross-referencing against operational dashboards.
     *
     * <p>The payload contains <b>no PII</b> &mdash; only operational
     * metadata. {@link AuditLogService#sanitizePayload(Map)} would
     * additionally remove sensitive keys and mask PAN-like
     * sequences as a defense-in-depth measure if any caller error
     * inadvertently introduced them, but this method intentionally
     * builds the payload from constants and the integer record
     * count only.</p>
     *
     * @param recordCount the number of customer rows processed by
     *                    the scan
     * @return a mutable {@link Map} carrying the audit-event
     *         payload &mdash; safe to pass to
     *         {@link AuditLogService#logBatchJobLifecycle} which
     *         will sanitize and merge it into the audit document
     */
    private static Map<String, Object> buildAuditPayload(long recordCount) {
        // LinkedHashMap preserves insertion order so the OpenSearch
        // dashboard sees fields in a stable, human-friendly sequence
        // (program → entity → record_count → vsam_cluster) when the
        // document is rendered. This is a usability nicety that
        // does not affect OpenSearch's inverted index.
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("program_id", COBOL_PROGRAM_ID);
        payload.put("entity_name", ENTITY_NAME);
        payload.put("record_count", recordCount);
        payload.put("vsam_cluster", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS");
        return payload;
    }

    /**
     * Masks a 9-digit Social Security Number to its last 4 digits in
     * the canonical {@code "***-**-NNNN"} format. Returns
     * {@code "***-**-****"} if the input is {@code null} (defensive
     * default so the log line never emits a literal {@code "null"}
     * in a context that suggests SSN absence).
     *
     * <p>This is a one-way redaction &mdash; the original SSN
     * cannot be recovered from the masked output. Used exclusively
     * by {@link #displayCustomerRecord(Customer)} for log emission;
     * the persistence layer always uses the raw
     * {@link Customer#getCustSsn()} value.</p>
     *
     * <p>The format string {@code "%09d"} pads with leading zeros
     * to preserve SSNs that start with one or more zero digits
     * (e.g., {@code 001234567}, which {@link Long#toString()} alone
     * would render as {@code "1234567"} and lose the leading
     * zeros). Mirrors the masking logic in
     * {@link Customer#toString()} for consistency.</p>
     *
     * @param ssn the raw 9-digit SSN value as a {@link Long} (the
     *            JPA type for the {@code NUMERIC(9)} column); may
     *            be {@code null}
     * @return the masked SSN string in {@code "***-**-NNNN"}
     *         format; {@code "***-**-****"} for a {@code null}
     *         input
     */
    private static String maskSsn(Long ssn) {
        if (ssn == null) {
            return "***-**-****";
        }
        // Pad with leading zeros to exactly 9 digits so SSNs that
        // begin with one or more zeros are preserved (e.g., the SSN
        // 001234567 would otherwise lose its leading zero through
        // Long.toString()).
        final String digits = String.format("%09d", ssn);
        // Substring(length - 4) extracts the trailing 4 digits.
        return "***-**-" + digits.substring(digits.length() - 4);
    }

    /**
     * Result envelope returned by {@link #readAndDisplayAll()}.
     * Carries the COBOL program identifier ({@code "CBCUS01C"}) and
     * the number of {@link Customer} rows processed during the
     * sequential scan. Designed to be consumed by tests and
     * batch-orchestration callers (e.g., a future Spring Batch
     * {@code Tasklet} wrapping this service).
     *
     * <p>Implemented as a Java {@code record} so the type is
     * immutable, the canonical constructor and accessors are
     * generated by the compiler, and the type-equality contract is
     * deterministic. Records introduced in Java 16 and are
     * available under the Java 17+ target per AAP &sect;0.5.1.</p>
     *
     * @param programId   the COBOL program identifier
     *                    ({@code "CBCUS01C"}) preserved verbatim
     *                    for traceability
     * @param recordCount the number of customer rows processed by
     *                    the scan; always non-negative
     */
    public record ReadResult(String programId, long recordCount) {
    }
}
