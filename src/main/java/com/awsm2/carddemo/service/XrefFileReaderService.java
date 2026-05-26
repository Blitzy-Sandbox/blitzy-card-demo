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
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
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
 * Card cross-reference file reader &mdash; sequential scan over the
 * {@code card_xref} relational table emitting one structured log line per
 * record. The Java target for the COBOL batch program
 * {@code app/cbl/CBACT03C.cbl}
 * (&quot;Read and print account cross reference data file.&quot;).
 *
 * <h2>COBOL Source Provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT03C.cbl} (179 lines,
 *       frozen reference) &mdash; batch reader; declares
 *       {@code SELECT XREFFILE-FILE ASSIGN TO XREFFILE ORGANIZATION IS
 *       INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-XREF-CARD-NUM}
 *       (16-byte primary key) at L29&ndash;L33. The PROCEDURE DIVISION
 *       at L70&ndash;L87 executes:
 *       <pre>
 *       DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
 *       PERFORM 0000-XREFFILE-OPEN
 *       PERFORM UNTIL END-OF-FILE = 'Y'
 *           IF END-OF-FILE = 'N'
 *               PERFORM 1000-XREFFILE-GET-NEXT
 *               IF END-OF-FILE = 'N'
 *                   DISPLAY CARD-XREF-RECORD
 *               END-IF
 *           END-IF
 *       END-PERFORM
 *       PERFORM 9000-XREFFILE-CLOSE
 *       DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'
 *       GOBACK
 *       </pre>
 *       Unlike sibling batch readers ({@code CBACT01C}, {@code CBACT02C},
 *       {@code CBCUS01C}), CBACT03C has <strong>no separate
 *       {@code 1100-DISPLAY-...}-RECORD paragraph</strong> &mdash; the
 *       50-byte {@code CARD-XREF-RECORD} is emitted as a single
 *       {@code DISPLAY} statement at L78 and again inside
 *       {@code 1000-XREFFILE-GET-NEXT} at L96 (the latter being a
 *       successful-read echo).</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT03Y.cpy} defines
 *       {@code CARD-XREF-RECORD} (50 bytes total) with three business
 *       fields plus a trailing 14-byte {@code FILLER PIC X(14)}:
 *       <ul>
 *         <li>{@code XREF-CARD-NUM PIC X(16)} &mdash; 16-byte primary
 *             key (PAN, cardholder data &mdash; PCI-DSS sensitive);
 *             stored as {@link String} in the
 *             {@link CardCrossReference#getXrefCardNum() xrefCardNum}
 *             field</li>
 *         <li>{@code XREF-CUST-ID PIC 9(09)} &mdash; 9-digit unsigned
 *             customer FK; stored as {@link Long} in
 *             {@link CardCrossReference#getXrefCustId() xrefCustId}</li>
 *         <li>{@code XREF-ACCT-ID PIC 9(11)} &mdash; 11-digit unsigned
 *             account FK; stored as {@link Long} in
 *             {@link CardCrossReference#getXrefAcctId() xrefAcctId}</li>
 *         <li>{@code FILLER PIC X(14)} &mdash; trailing pad, omitted
 *             from the relational schema (PostgreSQL has no concept of
 *             fixed-width records)</li>
 *       </ul></li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} &mdash; KEYS(16 0),
 *       RECORDSIZE(50 50), INDEXED (per {@code app/jcl/XREFFILE.jcl}
 *       L39&ndash;L52 and {@code app/catlg/LISTCAT.txt}). An alternate
 *       index ({@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} on
 *       {@code XREF-ACCT-ID}, KEYS(11 25), NONUNIQUEKEY, UPGRADE,
 *       referenced under the CICS symbolic name {@code CXACAIX}) also
 *       exists on this cluster but is <strong>not used by this
 *       service</strong> &mdash; CBACT03C performs only the primary-key
 *       sequential scan; AIX-driven lookups are exposed via
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)} and
 *       used by {@code AccountViewService},
 *       {@code TransactionAddService}, and
 *       {@code TransactionPostingService}.</li>
 *   <li><b>JCL invocation:</b> {@code app/jcl/READXREF.jcl} (demo
 *       inspection job) &mdash; replaced operationally by AWS Batch
 *       ad-hoc job invocation per AAP &sect;0.4.1 ("Demo/inspection
 *       JCLs &mdash; replaced operationally by Spring Boot Actuator
 *       endpoints + AWS Batch ad-hoc jobs").</li>
 * </ul>
 *
 * <h2>COBOL Paragraph &harr; Java Method Mapping</h2>
 * <table>
 *   <caption>CBACT03C.cbl &harr; XrefFileReaderService</caption>
 *   <tr><th>COBOL paragraph / line</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L70&ndash;L87)</td>
 *       <td>{@link #readAndDisplayAll()}</td></tr>
 *   <tr><td>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'}
 *       (L71)</td>
 *       <td>{@code LOG.info("START OF EXECUTION OF PROGRAM CBACT03C")}</td></tr>
 *   <tr><td>{@code 0000-XREFFILE-OPEN} (L118&ndash;L134)</td>
 *       <td>Spring Data JPA implicit DB connection acquisition (no
 *           explicit open required &mdash; {@code @Transactional}
 *           manages the JDBC connection lifecycle)</td></tr>
 *   <tr><td>{@code 1000-XREFFILE-GET-NEXT} (L92&ndash;L116)</td>
 *       <td>{@link CardCrossReferenceRepository#findAll(Sort)} +
 *           iteration over the materialized {@link List}</td></tr>
 *   <tr><td>{@code DISPLAY CARD-XREF-RECORD} (L78 + L96)</td>
 *       <td>{@link #displayXrefRecord(CardCrossReference)} (SLF4J INFO,
 *           PAN masked to last-4 per PCI-DSS Req 3.3 / AAP &sect;0.6.6)</td></tr>
 *   <tr><td>{@code 9000-XREFFILE-CLOSE} (L136&ndash;L152)</td>
 *       <td>Spring transaction-manager auto-close at method exit
 *           (HikariCP returns the connection to the pool)</td></tr>
 *   <tr><td>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'}
 *       (L85)</td>
 *       <td>{@code LOG.info("END OF EXECUTION OF PROGRAM CBACT03C")}</td></tr>
 *   <tr><td>{@code GOBACK} (L87)</td>
 *       <td>{@code return new ReadResult(...)}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} (L154&ndash;L158)</td>
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
 *       {@code CBACT03C} &mdash; never combined with another COBOL
 *       program's logic.</li>
 *   <li><b>Constructor injection only:</b> both dependencies
 *       ({@link CardCrossReferenceRepository}, {@link AuditLogService})
 *       are declared {@code final} and assigned via the canonical
 *       constructor. No {@code @Autowired} field injection per AAP
 *       &sect;0.7.1.</li>
 *   <li><b>Read-only transaction:</b> {@link #readAndDisplayAll()}
 *       is annotated {@code @Transactional(readOnly = true)} so
 *       PostgreSQL applies read-only-tuning optimisations and
 *       Hibernate skips dirty-checking overhead. Matches the COBOL
 *       {@code OPEN INPUT XREFFILE-FILE} semantic (input-only access
 *       at L120).</li>
 *   <li><b>Sequential VSAM order preserved:</b> the
 *       {@code findAll(Sort.by("xrefCardNum"))} call sorts ascending
 *       on the primary key, reproducing the COBOL
 *       {@code ACCESS MODE IS SEQUENTIAL} on
 *       {@code RECORD KEY FD-XREF-CARD-NUM} semantic byte-for-byte.
 *       Ensures the Java output stream matches the COBOL output
 *       stream row-for-row for golden-output diffing during the
 *       parallel-run validation window per AAP &sect;0.6.2 /
 *       &sect;0.7.2.</li>
 *   <li><b>{@link AtomicLong} counter:</b> the running record counter
 *       uses {@link AtomicLong} (rather than a primitive {@code long})
 *       to provide a stream-friendly mutation idiom and match the
 *       semantics of a COBOL counter variable that could be mutated
 *       from nested paragraphs. Required by the agent_prompt's
 *       external_imports schema for this service.</li>
 *   <li><b>PCI-DSS PAN masking:</b> the {@link #displayXrefRecord}
 *       helper masks the 16-character {@code XREF-CARD-NUM} to its
 *       last-4 digits before emission. This is a <strong>mandatory
 *       deviation</strong> from the verbatim COBOL DISPLAY behaviour
 *       (CBACT03C emits the full 50-byte record to SYSOUT). PCI-DSS
 *       Req 3.3 forbids logging the full PAN; AAP &sect;0.6.6
 *       enumerates this requirement as a cross-cutting concern.
 *       Macie continuously scans CloudWatch Logs for PAN-like
 *       sequences as a defense-in-depth control.</li>
 *   <li><b>Structured logging via SLF4J:</b> The COBOL {@code DISPLAY}
 *       statements emit to SYSOUT (mainframe operator console). The
 *       Java target emits to SLF4J &rarr; Logback &rarr;
 *       logstash-logback-encoder &rarr; CloudWatch Logs per AAP
 *       &sect;0.6.6.</li>
 *   <li><b>{@link AuditLogService#logBatchJobLifecycle}:</b> a
 *       structured batch-reader completion event is emitted to
 *       OpenSearch + CloudWatch upon successful scan completion. The
 *       payload carries the COBOL program ID ({@code "CBACT03C"}),
 *       entity name ({@code "CARD_XREF"}), and processed record
 *       count. Operators monitor this event for batch-job heartbeat
 *       and record-count parity against the COBOL source during the
 *       parallel-run validation window. The agent_prompt's suggested
 *       {@code logBatchReaderCompleted(String, String, long)} method
 *       does <strong>not exist</strong> on the actual
 *       {@code AuditLogService} class; the closest-matching method
 *       with a stable, schema-canonical signature
 *       {@code logBatchJobLifecycle(String, String, String, Long,
 *       Map, String)} is used instead &mdash; this matches the
 *       resolution adopted by sibling readers
 *       {@code AccountFileReaderService} / {@code CardFileReaderService}
 *       / {@code CustomerFileReaderService} and is consistent with the
 *       internal_imports schema purpose narrative that nominates
 *       {@code logBatchJobLifecycle} as the canonical emission target
 *       for this service.</li>
 *   <li><b>No AWS SDK calls inline:</b> per AAP &sect;0.7.1 all AWS
 *       integration is encapsulated in
 *       {@code com.awsm2.carddemo.adapter} adapters; this service
 *       only invokes {@link AuditLogService} as a Spring bean and
 *       never touches OpenSearch / CloudWatch / S3 / KMS clients
 *       directly.</li>
 *   <li><b>No {@code CEE3ABD} equivalent:</b> the COBOL
 *       {@code 9999-ABEND-PROGRAM} paragraph (L154&ndash;L158) calls
 *       {@code CEE3ABD} to terminate the JVM on any I/O error. The
 *       Java target instead lets the exception propagate to the
 *       caller after emitting a best-effort {@code FAILED} audit
 *       event &mdash; aligning with Spring Batch step failure
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
 * @see CardCrossReference
 * @see CardCrossReferenceRepository
 * @see AuditLogService
 */
// COBOL: CBACT03C — sequential reader for the CARDXREF VSAM KSDS cluster
//        (AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS). Translates the OPEN/READ/CLOSE
//        loop in app/cbl/CBACT03C.cbl L70–L87 to a Spring Data JPA
//        findAll(Sort) scan with structured SLF4J emission per record.
@Service
public class XrefFileReaderService {

    // =========================================================================
    // Constants — mirror the COBOL source verbatim per AAP §0.7.3
    // "Inline traceability comments" rule.
    // =========================================================================

    /**
     * SLF4J logger used to emit COBOL-equivalent {@code DISPLAY}
     * output lines. Per AAP &sect;0.6.6 Logback is configured with
     * {@code logstash-logback-encoder} so each {@code LOG.info(...)}
     * call lands in CloudWatch Logs as a structured JSON document
     * (message + level + thread + logger + MDC trace fields).
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(XrefFileReaderService.class);

    /**
     * Identifier of the source COBOL program that this service
     * translates. Used verbatim in the SLF4J log lines (matching the
     * COBOL {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'}
     * / {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'}
     * pattern at L71 and L85) and in the structured audit event
     * emitted to {@link AuditLogService#logBatchJobLifecycle}.
     *
     * <p>Mandated by AAP &sect;0.7.3 ("Inline traceability comments:
     * every translated paragraph carries a
     * {@code // COBOL: <PROGRAM>:<PARAGRAPH>} comment").</p>
     */
    private static final String COBOL_PROGRAM_ID = "CBACT03C";

    /**
     * Business entity name corresponding to the COBOL
     * {@code CARD-XREF-RECORD} layout ({@code app/cpy/CVACT03Y.cpy}).
     * Emitted in the structured audit payload so operators can
     * filter the OpenSearch / CloudWatch documents by entity during
     * the parallel-run validation window.
     */
    private static final String ENTITY_NAME = "CARD_XREF";

    /**
     * VSAM cluster name (preserved verbatim from
     * {@code app/jcl/XREFFILE.jcl}) emitted in the audit payload for
     * cross-referencing against legacy operational dashboards. The
     * cluster does not exist on the AWS target (it has been replaced
     * by the {@code card_xref} PostgreSQL table per V004 Flyway
     * migration); the literal is retained as documentation, not as
     * a live identifier.
     */
    private static final String VSAM_CLUSTER =
            "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

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
     * Spring Data JPA {@link Sort} field name used by the SUT to
     * preserve the COBOL {@code ACCESS MODE SEQUENTIAL} on
     * {@code RECORD KEY FD-XREF-CARD-NUM} semantic. The
     * {@link CardCrossReference} entity's {@code xrefCardNum} property
     * maps to the {@code xref_card_num} relational column &mdash; the
     * primary key of the {@code card_xref} table (V004 Flyway
     * migration) &mdash; ascending order on this field is identical
     * to the VSAM KSDS key sequence on
     * {@code XREF-CARD-NUM PIC X(16)}.
     *
     * <p>Declared as a constant so any future refactor that needs to
     * change the sort key (e.g. for batch parallelism) does so in
     * exactly one place. The constant value MUST be the Java entity
     * field name ({@code xrefCardNum}) &mdash; Spring Data JPA's
     * {@link Sort#by(String...)} resolves names against the JPA
     * metamodel, NOT against the database column.</p>
     */
    private static final String SORT_FIELD_XREF_CARD_NUM = "xrefCardNum";

    /**
     * Mask token used when a PAN is {@code null} or shorter than 4
     * characters &mdash; emitted in lieu of the masked PAN to keep
     * log output readable while not leaking any cardholder data.
     */
    private static final String MASKED_PAN_FALLBACK = "************";

    /**
     * Per-record terminator banner emitted after each DISPLAY block.
     * Reproduced verbatim from the COBOL convention used across all
     * batch readers ({@code CBACT01C}, {@code CBACT02C},
     * {@code CBCUS01C}, and {@code CBACT03C}'s sibling DISPLAY
     * paragraphs) so a downstream log-parsing tool can split the
     * output stream into per-record blocks.
     */
    private static final String RECORD_TERMINATOR =
            "-------------------------------------------------";

    /**
     * Audit payload key under which the COBOL program identifier is
     * emitted. Stable across all batch-reader services so dashboards
     * can pivot on {@code program_id} uniformly.
     */
    private static final String PAYLOAD_PROGRAM_ID_KEY = "program_id";

    /**
     * Audit payload key under which the logical entity name is
     * emitted.
     */
    private static final String PAYLOAD_ENTITY_NAME_KEY = "entity_name";

    /**
     * Audit payload key under which the processed record count is
     * emitted.
     */
    private static final String PAYLOAD_RECORD_COUNT_KEY = "record_count";

    /**
     * Audit payload key under which the legacy VSAM cluster
     * identifier is emitted.
     */
    private static final String PAYLOAD_VSAM_CLUSTER_KEY = "vsam_cluster";

    // =========================================================================
    // Injected collaborators — constructor-injected, final, non-null guarded.
    // =========================================================================

    /**
     * Spring Data JPA repository for the {@link CardCrossReference}
     * entity. Constructor-injected; replaces the COBOL
     * {@code SELECT XREFFILE-FILE ASSIGN TO XREFFILE} file handle
     * plus the implicit {@code OPEN INPUT / READ NEXT / CLOSE}
     * lifecycle of the VSAM KSDS sequential reader. The repository's
     * inherited {@link CardCrossReferenceRepository#findAll(Sort)
     * findAll(Sort)} method performs the sequential scan ordered by
     * the primary-key column, preserving the COBOL
     * {@code ACCESS MODE SEQUENTIAL} on
     * {@code RECORD KEY FD-XREF-CARD-NUM} key-sequence guarantee.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * CloudTrail + OpenSearch audit-log adapter. Constructor-injected;
     * used to emit the batch-reader lifecycle event at scan
     * completion (or failure). Per AAP &sect;0.7.1 this is one of the
     * eight dedicated AWS adapter classes that isolate AWS SDK calls
     * from the business-logic services &mdash; this service never
     * touches OpenSearch / CloudWatch clients directly.
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
     * @param cardCrossReferenceRepository the Spring Data JPA
     *                                     repository for the
     *                                     {@link CardCrossReference}
     *                                     entity; must not be
     *                                     {@code null}
     * @param auditLogService              the audit-log adapter bean;
     *                                     must not be {@code null}
     */
    public XrefFileReaderService(CardCrossReferenceRepository cardCrossReferenceRepository,
                                 AuditLogService auditLogService) {
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    // =========================================================================
    // Public API — the schema-required CBACT03C entry point.
    // =========================================================================

    /**
     * Performs the sequential scan over the {@code card_xref} table
     * emitting one structured log line per record. The complete Java
     * translation of the COBOL {@code CBACT03C} PROCEDURE DIVISION
     * (L70&ndash;L87). Returns a {@link ReadResult} carrying the
     * COBOL program identifier and the count of records processed
     * for downstream verification, audit, and parallel-run parity
     * checks.
     *
     * <p>Execution sequence (mirroring COBOL paragraph order):</p>
     * <ol>
     *   <li>Emit the COBOL-verbatim START marker
     *       ({@code 'START OF EXECUTION OF PROGRAM CBACT03C'},
     *       COBOL L71).</li>
     *   <li>Capture {@code startMillis} so the audit emission can
     *       report scan duration (operational metric beyond what the
     *       COBOL source captured, but useful for SLA monitoring per
     *       AAP &sect;0.7.2 NFR-performance).</li>
     *   <li>Issue a single
     *       {@link CardCrossReferenceRepository#findAll(Sort)} call
     *       sorted by {@code xrefCardNum} ascending &mdash; preserves
     *       the COBOL {@code ACCESS MODE SEQUENTIAL} on
     *       {@code RECORD KEY FD-XREF-CARD-NUM} semantic.</li>
     *   <li>Iterate the materialised result list. For each
     *       {@link CardCrossReference}, invoke
     *       {@link #displayXrefRecord(CardCrossReference)} (the Java
     *       equivalent of COBOL {@code DISPLAY CARD-XREF-RECORD} at
     *       L78 and L96) and increment the running counter.</li>
     *   <li>Emit the COBOL-verbatim END marker
     *       ({@code 'END OF EXECUTION OF PROGRAM CBACT03C'},
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
     *         ({@code "CBACT03C"}) and the number of cross-reference
     *         rows iterated; the {@code recordCount} is always
     *         non-negative
     * @throws RuntimeException if the repository call fails (e.g.
     *         database connectivity loss); propagated to the caller
     *         after a best-effort {@code FAILED} audit emission
     */
    @Transactional(readOnly = true)
    public ReadResult readAndDisplayAll() {
        // COBOL: CBACT03C.cbl L71 — DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
        LOG.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

        // Capture start time so the audit emission carries scan
        // duration as an operational metric (beyond what the COBOL
        // source reported, but useful for SLA monitoring of the
        // batch reader against the existing mainframe runtime
        // baseline per AAP §0.7.2 NFR-performance).
        final long startMillis = System.currentTimeMillis();

        // Use AtomicLong for the running record counter — semantically
        // matches a COBOL counter variable (which could be mutated
        // from nested paragraphs) and is required by the
        // external_imports schema entry for this service. The atomic
        // semantics are not strictly required here (the scan loop is
        // single-threaded) but the contract preserves the option to
        // refactor to a parallel Stream later without revisiting the
        // counter.
        final AtomicLong count = new AtomicLong(0L);

        // Execution-correlation ID for the audit emission. Generated
        // fresh per execution so each invocation can be correlated
        // across OpenSearch documents and CloudWatch alarms even
        // when run multiple times within the same JVM (e.g. from a
        // Spring Batch JobLauncherTestUtils harness or unit-test
        // suite).
        final String executionId = UUID.randomUUID().toString();

        try {
            // COBOL: CBACT03C:0000-XREFFILE-OPEN (L118–L134) —
            // handled by Spring's transactional connection
            // acquisition; no explicit OPEN required because
            // @Transactional manages the JDBC connection lifecycle.
            //
            // COBOL: ACCESS MODE SEQUENTIAL on RECORD KEY FD-XREF-CARD-NUM
            // — preserve XREF-CARD-NUM ascending order via
            // Sort.by("xrefCardNum") so the Java scan emits rows in
            // the same order as the COBOL VSAM KSDS sequential read.
            // This is required for golden-output diffing during the
            // parallel-run validation window per AAP §0.6.2.
            //
            // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L74–L81) wraps
            //        PERFORM 1000-XREFFILE-GET-NEXT (L92–L116):
            //            READ XREFFILE-FILE INTO CARD-XREF-RECORD
            // Java equivalent: a single findAll(Sort) call returns
            // the entire result set materialised into a List; the
            // for-each loop replaces the PERFORM UNTIL loop. The
            // demo-scale CARDXREF cluster (a few hundred rows in the
            // golden fixture under app/data/ASCII/cardxref.txt)
            // makes the pre-fetch acceptable; for production-scale
            // deployments a Spring Batch RepositoryItemReader with
            // chunk-oriented processing would be substituted while
            // preserving the externally-observable behaviour.
            final List<CardCrossReference> all =
                    cardCrossReferenceRepository.findAll(Sort.by(SORT_FIELD_XREF_CARD_NUM));

            for (CardCrossReference xref : all) {
                // COBOL: CBACT03C:1000-XREFFILE-GET-NEXT (L92–L116) +
                // DISPLAY CARD-XREF-RECORD (L78, L96) — emit the
                // per-row structured log line.
                displayXrefRecord(xref);
                count.incrementAndGet();
            }

            // COBOL: CBACT03C:9000-XREFFILE-CLOSE (L136–L152) —
            // handled automatically by Spring's transaction manager
            // at method exit (the JDBC connection is returned to the
            // HikariCP pool).
            //
            // COBOL: CBACT03C.cbl L85 — DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'
            LOG.info("END OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

            final long total = count.get();
            final long durationMillis = System.currentTimeMillis() - startMillis;

            // Emit the structured batch-reader completion event to
            // OpenSearch + CloudWatch so operators can monitor scan
            // heartbeat, record-count parity against the COBOL
            // source, and SLA timing. Per AAP §0.6.6 audit failures
            // are non-blocking — the AuditLogService catches and
            // logs any transport errors without re-throwing.
            //
            // Per AAP §0.7.3 escape-hatch rule 9: the agent_prompt's
            // suggested logBatchReaderCompleted method does NOT exist
            // on AuditLogService; the closest-matching method
            // logBatchJobLifecycle is used per the schema's
            // internal_imports purpose narrative, mirroring the
            // resolution adopted by sibling readers
            // AccountFileReaderService / CardFileReaderService /
            // CustomerFileReaderService.
            auditLogService.logBatchJobLifecycle(
                    COBOL_PROGRAM_ID,
                    executionId,
                    STATUS_COMPLETED,
                    durationMillis,
                    buildAuditPayload(total),
                    null);

            // COBOL: CBACT03C.cbl L87 — GOBACK
            // Java equivalent: return the ReadResult carrying the
            // COBOL program ID and the processed record count for
            // programmatic verification by tests, batch schedulers,
            // and parallel-run parity-diff tooling.
            return new ReadResult(COBOL_PROGRAM_ID, total);
        } catch (RuntimeException ex) {
            // COBOL: CBACT03C:9999-ABEND-PROGRAM (L154–L158) — in
            // the source CBACT03C terminates the JVM (CEE3ABD) on
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


    // =========================================================================
    // Private helpers — record display, PAN masking, audit payload builder.
    // =========================================================================

    /**
     * Emits the per-record SLF4J INFO log lines corresponding to the
     * COBOL {@code DISPLAY CARD-XREF-RECORD} statement
     * ({@code app/cbl/CBACT03C.cbl} L78 and L96). The COBOL source
     * emits the entire 50-byte {@code CARD-XREF-RECORD} as a single
     * {@code DISPLAY} (there is no separate
     * {@code 1100-DISPLAY-...}-RECORD paragraph in this program); the
     * Java target decomposes the record into three labelled lines
     * (one per business field) so that operators reading the
     * CloudWatch Logs JSON output can pivot per-field, while
     * preserving record-level legibility via the
     * {@link #RECORD_TERMINATOR} banner.
     *
     * <p><b>PCI-DSS Req 3.3 / AAP &sect;0.6.6 PAN masking</b> &mdash;
     * the {@code XREF-CARD-NUM} field is cardholder data (CHD) per
     * PCI-DSS v4.0. The 16-character primary key value is therefore
     * <strong>masked</strong> to its last-4 digits via
     * {@link #maskPan(String)} before emission. This is a
     * <strong>mandatory deviation</strong> from the verbatim COBOL
     * DISPLAY behaviour (CBACT03C emits the full 50-byte record to
     * SYSOUT including the unmasked PAN); the Minimal Change Clause
     * (AAP &sect;0.7.3) explicitly accommodates this deviation
     * because the COBOL source pre-dates PCI-DSS v4.0 enforcement
     * and the platform now sits in scope for PCI-DSS. Amazon Macie
     * continuously scans CloudWatch Logs for PAN-like sequences as
     * a defense-in-depth control.</p>
     *
     * <p>Field-to-getter mapping:</p>
     * <ol>
     *   <li>{@code XREF-CARD-NUM PIC X(16)} &rarr;
     *       {@link CardCrossReference#getXrefCardNum()} &mdash;
     *       PAN, masked to last-4 before emission</li>
     *   <li>{@code XREF-CUST-ID PIC 9(09)} &rarr;
     *       {@link CardCrossReference#getXrefCustId()} &mdash;
     *       9-digit customer FK, emitted verbatim</li>
     *   <li>{@code XREF-ACCT-ID PIC 9(11)} &rarr;
     *       {@link CardCrossReference#getXrefAcctId()} &mdash;
     *       11-digit account FK, emitted verbatim</li>
     * </ol>
     *
     * <p>The trailing {@code FILLER PIC X(14)} (CVACT03Y.cpy L8) is
     * intentionally omitted &mdash; the COBOL FILLER is a record-pad
     * byte sequence with no business meaning and no relational
     * counterpart.</p>
     *
     * <p>Defensive guard: if {@code xref} is {@code null} (which
     * cannot happen in production because
     * {@link CardCrossReferenceRepository#findAll(Sort)} never
     * returns a list containing {@code null} elements, but may
     * occur in mocked unit tests), a single
     * {@code "CARD-XREF-RECORD: (null record)"} marker is logged
     * and the method returns without dereferencing the argument.</p>
     *
     * @param xref the cross-reference entity to log; may be
     *             {@code null} (defensive)
     */
    private void displayXrefRecord(CardCrossReference xref) {
        // COBOL: CBACT03C — DISPLAY CARD-XREF-RECORD (L78, L96)
        // emits the full 50-byte CVACT03Y record to SYSOUT in a
        // single statement. The Java equivalent decomposes the
        // record into per-field labelled lines so OpenSearch
        // dashboards can pivot on each field independently, while
        // applying the mandatory PCI-DSS Req 3.3 / AAP §0.6.6 PAN
        // mask on XREF-CARD-NUM.
        if (xref == null) {
            // Defensive — Spring Data JPA never returns null elements
            // in a List, but a unit test or mocked repository might.
            LOG.info("CARD-XREF-RECORD: (null record)");
            return;
        }
        // COBOL: CVACT03Y.cpy L5 — 05 XREF-CARD-NUM PIC X(16).
        // PCI-DSS Req 3.3 / AAP §0.6.6: mask to last-4 before
        // emission. NEVER log the raw PAN value.
        LOG.info("XREF-CARD-NUM           :{}", maskPan(xref.getXrefCardNum()));
        // COBOL: CVACT03Y.cpy L6 — 05 XREF-CUST-ID PIC 9(09).
        LOG.info("XREF-CUST-ID            :{}", xref.getXrefCustId());
        // COBOL: CVACT03Y.cpy L7 — 05 XREF-ACCT-ID PIC 9(11).
        LOG.info("XREF-ACCT-ID            :{}", xref.getXrefAcctId());
        // Record-terminator banner — preserved verbatim from sibling
        // readers' COBOL conventions so a downstream log-parsing tool
        // can split the output stream into per-record blocks.
        LOG.info(RECORD_TERMINATOR);
    }

    /**
     * Masks a 16-character PAN to its last-4 digits, replacing the
     * leading 12 characters with asterisks. Used by
     * {@link #displayXrefRecord(CardCrossReference)} to comply with
     * PCI-DSS Req 3.3 ("PAN, at minimum, render unreadable anywhere
     * it is stored or displayed") per AAP &sect;0.6.6.
     *
     * <p>Defensive contract:</p>
     * <ul>
     *   <li>{@code null} or empty input &rarr;
     *       {@link #MASKED_PAN_FALLBACK} (12 asterisks) so log
     *       output remains readable without any value to mask.</li>
     *   <li>Input shorter than 4 characters &rarr;
     *       {@link #MASKED_PAN_FALLBACK}; revealing 1&ndash;3
     *       characters of an already-short input would defeat the
     *       masking purpose.</li>
     *   <li>Input 4 characters or longer &rarr;
     *       {@code "************" + last 4 characters}. The mask
     *       prefix is exactly 12 asterisks to maintain a constant
     *       16-character output width when the input is a full
     *       16-digit PAN (matching the COBOL
     *       {@code XREF-CARD-NUM PIC X(16)} declaration).</li>
     * </ul>
     *
     * <p>This method is declared {@code static} because it carries
     * no instance state &mdash; the mask is purely a function of the
     * input. The {@code static} declaration also enables
     * white-box unit testing without instantiating the service.</p>
     *
     * @param pan the cardholder-data string; may be {@code null}
     * @return a masked PAN safe for log emission; never {@code null}
     */
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return MASKED_PAN_FALLBACK;
        }
        // Last-4 mask: 12 asterisks + last 4 characters preserves a
        // constant 16-character output width for the canonical
        // 16-digit PAN expected from CVACT03Y XREF-CARD-NUM PIC X(16).
        return MASKED_PAN_FALLBACK + pan.substring(pan.length() - 4);
    }

    /**
     * Builds the structured audit-event payload for the
     * {@link AuditLogService#logBatchJobLifecycle} emission. The
     * payload carries the COBOL program identifier, the logical
     * entity name (mapped to the underlying VSAM cluster
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}), the processed
     * record count, and the legacy VSAM cluster identifier for
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
     * <p>The method is declared {@code static} because it carries
     * no instance state &mdash; the payload is a pure function of
     * the supplied record count and the class constants.</p>
     *
     * @param recordCount the number of cross-reference rows
     *                    processed by the scan; always non-negative
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
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_PROGRAM_ID_KEY, COBOL_PROGRAM_ID);
        payload.put(PAYLOAD_ENTITY_NAME_KEY, ENTITY_NAME);
        payload.put(PAYLOAD_RECORD_COUNT_KEY, recordCount);
        payload.put(PAYLOAD_VSAM_CLUSTER_KEY, VSAM_CLUSTER);
        return payload;
    }

    // =========================================================================
    // Public nested type — ReadResult record exported per the schema.
    // =========================================================================

    /**
     * Result envelope returned by {@link #readAndDisplayAll()}.
     * Carries the COBOL program identifier ({@code "CBACT03C"}) and
     * the number of {@link CardCrossReference} rows processed during
     * the sequential scan. Designed to be consumed by tests and
     * batch-orchestration callers (e.g., a future Spring Batch
     * {@code Tasklet} wrapping this service).
     *
     * <p>Implemented as a Java {@code record} so the type is
     * immutable, the canonical constructor and accessors are
     * generated by the compiler, and the type-equality contract is
     * deterministic. Records were introduced in Java 16 and are
     * available under the Java 17+ target per AAP &sect;0.5.1.</p>
     *
     * <p>The companion
     * {@link CardCrossReferenceRepository#count()} method (inherited
     * from {@code JpaRepository}) provides a lighter-weight row-count
     * check; this record is returned only by the full
     * DISPLAY-paragraph scan so its {@code recordCount} is the exact
     * number of rows for which
     * {@link #displayXrefRecord(CardCrossReference)} was successfully
     * invoked.</p>
     *
     * @param programId   the COBOL program identifier
     *                    ({@code "CBACT03C"}) preserved verbatim
     *                    for traceability per AAP &sect;0.7.3
     * @param recordCount the number of cross-reference rows
     *                    processed by the scan; always non-negative
     */
    public record ReadResult(String programId, long recordCount) {
    }
}

