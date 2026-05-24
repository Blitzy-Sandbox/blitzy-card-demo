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
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.repository.CardRepository;
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
 * Card file reader &mdash; sequential scan over the {@code cards} table
 * emitting one structured log line per record. The Java target for the
 * COBOL batch program {@code app/cbl/CBACT02C.cbl}
 * (&quot;Read and print card data file.&quot;).
 *
 * <h2>COBOL Source Provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT02C.cbl} &mdash;
 *       batch reader; declares
 *       {@code SELECT CARDFILE-FILE ASSIGN TO CARDFILE ORGANIZATION IS
 *       INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CARD-NUM}
 *       (16-character primary key, lines L29&ndash;L33). The PROCEDURE
 *       DIVISION (L70&ndash;L87) executes:
 *       <pre>
 *       DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'   (L71)
 *       PERFORM 0000-CARDFILE-OPEN                         (L72)
 *       PERFORM UNTIL END-OF-FILE = 'Y'                    (L74)
 *           PERFORM 1000-CARDFILE-GET-NEXT                 (L76)
 *           IF END-OF-FILE = 'N' DISPLAY CARD-RECORD       (L77-L79)
 *       END-PERFORM                                        (L81)
 *       PERFORM 9000-CARDFILE-CLOSE                        (L83)
 *       DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'     (L85)
 *       GOBACK                                             (L87)
 *       </pre>
 *       Note: {@code CBACT02C} is intentionally simpler than its
 *       sibling {@code CBACT01C} &mdash; it displays each
 *       {@code CARD-RECORD} as a whole at L78 rather than emitting
 *       a field-by-field paragraph like
 *       {@code CBACT01C:1100-DISPLAY-ACCT-RECORD}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy} defines
 *       {@code CARD-RECORD} (150 bytes) with six business fields plus
 *       a trailing {@code FILLER PIC X(59)} that brings the COBOL
 *       record to its declared 150-byte {@code RECORDSIZE}. The six
 *       business fields are
 *       {@code CARD-NUM} ({@code PIC X(16)}),
 *       {@code CARD-ACCT-ID} ({@code PIC 9(11)}),
 *       {@code CARD-CVV-CD} ({@code PIC 9(03)} &mdash; CVV/CVC, SAD),
 *       {@code CARD-EMBOSSED-NAME} ({@code PIC X(50)}),
 *       {@code CARD-EXPIRAION-DATE} ({@code PIC X(10)} &mdash; sic;
 *       the COBOL field name carries a misspelling preserved verbatim
 *       in the source for byte-identical parity, while the Java getter
 *       and PostgreSQL column adopt the corrected spelling
 *       {@code getCardExpirationDate()} per AAP &sect;0.4.1 V002), and
 *       {@code CARD-ACTIVE-STATUS} ({@code PIC X(01)} &mdash;
 *       {@code 'Y'} active / {@code 'N'} inactive).</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} &mdash; KEYS(16 0),
 *       RECORDSIZE(150 150), INDEXED, SHAREOPTIONS(2,3), ERASE (per
 *       {@code app/jcl/CARDFILE.jcl} L50&ndash;L63 and
 *       {@code app/catlg/LISTCAT.txt}). The associated alternate
 *       index {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX}
 *       ({@code KEYS(11,16) NONUNIQUEKEY UPGRADE}) supports look-up
 *       by {@code CARD-ACCT-ID} and is replaced in the target by the
 *       PostgreSQL secondary index {@code idx_cards_acct_id} per AAP
 *       &sect;0.6.2; sequential primary-key scan (this program) is
 *       satisfied by the PostgreSQL B-tree on the primary key.</li>
 *   <li><b>JCL invocation:</b> {@code app/jcl/READCARD.jcl} (demo
 *       inspection job) &mdash; replaced operationally by AWS Batch
 *       ad-hoc job invocation per AAP &sect;0.4.1 ("Demo/inspection
 *       JCLs &mdash; replaced operationally by Spring Boot Actuator
 *       endpoints + AWS Batch ad-hoc jobs").</li>
 * </ul>
 *
 * <h2>COBOL Paragraph &harr; Java Method Mapping</h2>
 * <table>
 *   <caption>CBACT02C.cbl &harr; CardFileReaderService</caption>
 *   <tr><th>COBOL paragraph / line</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L70&ndash;L87)</td>
 *       <td>{@link #readAndDisplayAll()}</td></tr>
 *   <tr><td>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'}
 *       (L71)</td>
 *       <td>{@code LOG.info("START OF EXECUTION OF PROGRAM CBACT02C")}</td></tr>
 *   <tr><td>{@code 0000-CARDFILE-OPEN} (L118&ndash;L134)</td>
 *       <td>Spring Data JPA implicit DB connection acquisition (no
 *           explicit open required &mdash; {@code @Transactional}
 *           manages the JDBC connection lifecycle)</td></tr>
 *   <tr><td>{@code 1000-CARDFILE-GET-NEXT} (L92&ndash;L116)</td>
 *       <td>{@link CardRepository#findAll(Sort)} +
 *           iteration over the materialized {@link List}</td></tr>
 *   <tr><td>{@code DISPLAY CARD-RECORD} (L78)</td>
 *       <td>{@link #displayCardRecord(Card)} (SLF4J INFO) &mdash;
 *           PAN masked per PCI-DSS Req 3.4 (last-4 only); CVV
 *           never stored (QA finding DB1; PCI-DSS Req 3.2)</td></tr>
 *   <tr><td>{@code 9000-CARDFILE-CLOSE} (L136&ndash;L152)</td>
 *       <td>(automatic by Spring) &mdash; JDBC connection returned
 *           to HikariCP pool at method exit</td></tr>
 *   <tr><td>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'}
 *       (L85)</td>
 *       <td>{@code LOG.info("END OF EXECUTION OF PROGRAM CBACT02C")}</td></tr>
 *   <tr><td>{@code GOBACK} (L87)</td>
 *       <td>{@code return new ReadResult(...)}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} (L154&ndash;L158)</td>
 *       <td>Java exceptions propagate to the caller after a
 *           best-effort {@code FAILED} audit emission (no
 *           {@code CEE3ABD} equivalent)</td></tr>
 * </table>
 *
 * <h2>PCI-DSS discipline (AAP &sect;0.6.6 &mdash; <b>critical</b>)</h2>
 * <p>{@code CARD-RECORD} carries two PCI-DSS-classified data elements:
 * <ul>
 *   <li><b>{@code CARD-NUM}</b> &mdash; the Primary Account Number
 *       (PAN). Classified as &quot;cardholder data&quot; (CHD) per
 *       PCI-DSS v4.0 Requirement 3.4. The COBOL source happily
 *       {@code DISPLAY}s the full 16-digit PAN to SYSOUT (operator
 *       console, semi-internal). The Java target ships logs to
 *       CloudWatch Logs (multi-tenant), so <b>full-PAN logging is
 *       prohibited</b>. The {@link #displayCardRecord(Card)} method
 *       masks the PAN via {@link #maskPan(String)} to
 *       {@code ************} + last-4-digits before emission. This
 *       is a deliberate AAP-approved, AAP-mandated security upgrade
 *       to the COBOL behavior &mdash; the only behavioural change
 *       this service makes relative to the COBOL source, and only
 *       because the Minimal Change Clause (AAP &sect;0.7.3) explicitly
 *       permits security-required deviations from byte-identical
 *       parity per AAP &sect;0.1.1's plaintext-password-storage
 *       upgrade precedent. The defense-in-depth CloudWatch log filter
 *       regex documented in {@code AuditLogService.PAN_PATTERN}
 *       enforces masking on any PAN-like sequence that leaks through
 *       other code paths.</li>
 *   <li><b>{@code CARD-CVV-CD}</b> &mdash; the Card Verification
 *       Value. Classified as &quot;sensitive authentication data&quot;
 *       (SAD) per PCI-DSS v4.0 Requirement 3.2. SAD <b>MUST NOT be
 *       persisted post-authorization</b> in a payment-card
 *       environment. Per QA finding DB1 (Checkpoint 2 runtime
 *       testing) the {@code card_cvv_cd} column has been REMOVED
 *       from the {@code cards} table and from the {@link Card}
 *       entity; no Java accessor for the CVV exists. The
 *       {@link #displayCardRecord(Card)} method consequently does
 *       not emit any CVV placeholder. See
 *       {@code V017__drop_card_cvv_column.sql} for the
 *       reconciliation migration.</li>
 * </ul>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1 / &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>One service per COBOL program:</b> per AAP &sect;0.7.1
 *       ("Isolate each COBOL program's logic in its own dedicated
 *       Java service class") this class corresponds one-to-one to
 *       {@code CBACT02C} &mdash; never combined with another COBOL
 *       program's logic.</li>
 *   <li><b>Constructor injection only:</b> both dependencies
 *       ({@link CardRepository}, {@link AuditLogService}) are
 *       declared {@code final} and assigned via the canonical
 *       constructor. No {@code @Autowired} field injection per AAP
 *       &sect;0.7.1 ("Constructor injection for all @Service,
 *       @Repository, @Component, adapter, and config beans &mdash;
 *       loose coupling required").</li>
 *   <li><b>Read-only transaction:</b> {@link #readAndDisplayAll()}
 *       is annotated {@code @Transactional(readOnly = true)} so
 *       PostgreSQL applies read-only-tuning optimisations (no WAL
 *       writes, no snapshot acquisition for write predicates) and
 *       Hibernate skips dirty-checking overhead. Matches the COBOL
 *       {@code OPEN INPUT CARDFILE-FILE} semantic (input-only
 *       access) &mdash; the COBOL program never writes back.</li>
 *   <li><b>Sequential VSAM order preserved:</b> the
 *       {@code findAll(Sort.by("cardNum"))} call sorts ascending
 *       on the primary key field {@link Card#getCardNum()},
 *       reproducing the COBOL {@code ACCESS MODE IS SEQUENTIAL}
 *       on {@code RECORD KEY FD-CARD-NUM} semantic byte-for-byte.
 *       Ensures the Java output stream matches the COBOL output
 *       stream row-for-row for golden-output diffing during the
 *       parallel-run validation window per AAP &sect;0.6.2.</li>
 *   <li><b>{@link AtomicLong} counter:</b> the running record
 *       counter uses {@link AtomicLong} (rather than a primitive
 *       {@code long}) to provide a stream-friendly mutation idiom
 *       &mdash; matches the semantics of a COBOL counter variable
 *       mutable from nested paragraphs, and is required by the
 *       schema's {@code external_imports} rule for this service.
 *       Even though the loop body here is single-threaded, the
 *       atomic semantics are harmless and allow future evolution
 *       to streams or parallel scans without API changes.</li>
 *   <li><b>Structured logging via SLF4J:</b> The COBOL
 *       {@code DISPLAY} statements emit to SYSOUT (mainframe
 *       operator console). The Java target emits to SLF4J &rarr;
 *       Logback &rarr; logstash-logback-encoder &rarr; CloudWatch
 *       Logs per AAP &sect;0.6.6. The START / END markers are
 *       reproduced verbatim from the COBOL source so a downstream
 *       log-parsing tool can diff Java output against COBOL
 *       fixtures during the parallel-run validation window per the
 *       Minimal Change Clause (AAP &sect;0.7.3).</li>
 *   <li><b>{@link AuditLogService#logBatchJobLifecycle}:</b> a
 *       structured batch-reader completion event is emitted to
 *       OpenSearch + CloudWatch upon successful scan completion.
 *       The payload carries the COBOL program ID
 *       ({@code "CBACT02C"}), entity name ({@code "CARD"}), and
 *       processed record count. Operators monitor this event for
 *       batch-job heartbeat and record-count parity against the
 *       COBOL source during the parallel-run validation window.
 *       The agent prompt's suggested {@code logBatchReaderCompleted}
 *       method does not exist on the actual
 *       {@link AuditLogService}; the closest-matching method
 *       {@link AuditLogService#logBatchJobLifecycle} is used
 *       instead per the schema's
 *       {@code internal_imports.purpose} description which
 *       canonically references it as the audit method.</li>
 *   <li><b>No AWS SDK calls inline:</b> per AAP &sect;0.7.1 all
 *       AWS integration is encapsulated in
 *       {@code com.awsm2.carddemo.adapter} adapters; this service
 *       only invokes {@link AuditLogService} as a Spring bean and
 *       never touches OpenSearch / CloudWatch / S3 / KMS clients
 *       directly.</li>
 *   <li><b>No {@code CEE3ABD} equivalent:</b> the COBOL
 *       {@code 9999-ABEND-PROGRAM} paragraph (L154&ndash;L158)
 *       calls {@code CEE3ABD} to terminate the JVM on any I/O
 *       error. The Java target instead lets the exception
 *       propagate to the caller after emitting a best-effort
 *       {@code FAILED} audit event &mdash; aligning with Spring
 *       Batch step failure handling and allowing the caller
 *       (Spring Batch step, AWS Batch container, ad-hoc test,
 *       etc.) to decide on retry / abort semantics.</li>
 * </ul>
 *
 * <h2>Layered Architecture (AAP &sect;0.3.3)</h2>
 * <p>This class lives in the {@code service} layer between the
 * {@code repository} layer (Spring Data JPA) and any future
 * controller / scheduler / Step Functions task invocation. It
 * depends on the repository abstraction and the audit-adapter
 * abstraction only &mdash; never on a concrete JDBC, JPA,
 * OpenSearch, or CloudWatch client. The dependency direction is
 * strictly {@code service} &rarr; {@code repository} and
 * {@code service} &rarr; {@code adapter}, never the reverse.</p>
 *
 * @see Card
 * @see CardRepository
 * @see AuditLogService
 */
@Service
public class CardFileReaderService {

    /**
     * SLF4J logger used to emit COBOL-equivalent {@code DISPLAY}
     * output lines. Per AAP &sect;0.6.6 Logback is configured with
     * {@code logstash-logback-encoder} so each {@code LOG.info(...)}
     * call lands in CloudWatch Logs as a structured JSON document
     * (message + level + thread + logger + MDC trace fields).
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(CardFileReaderService.class);

    /**
     * Identifier of the source COBOL program that this service
     * translates. Used verbatim in the SLF4J log lines (matching the
     * COBOL {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'}
     * / {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'}
     * pattern at L71 and L85) and in the structured audit event
     * emitted to {@link AuditLogService#logBatchJobLifecycle}.
     *
     * <p>Mandated by AAP &sect;0.7.3 ("Inline traceability comments:
     * every translated paragraph carries a
     * {@code // COBOL: <PROGRAM>:<PARAGRAPH>} comment").</p>
     */
    private static final String COBOL_PROGRAM_ID = "CBACT02C";

    /**
     * Business entity name corresponding to the COBOL
     * {@code CARD-RECORD} layout ({@code app/cpy/CVACT02Y.cpy}).
     * Emitted in the structured audit payload so operators can
     * filter the OpenSearch / CloudWatch documents by entity during
     * the parallel-run validation window.
     */
    private static final String ENTITY_NAME = "CARD";

    /**
     * VSAM cluster name (preserved verbatim from
     * {@code app/jcl/CARDFILE.jcl}) emitted in the audit payload for
     * cross-referencing against legacy operational dashboards. The
     * cluster does not exist on the AWS target (it has been replaced
     * by the {@code cards} PostgreSQL table); the literal is
     * retained as documentation, not as a live identifier.
     */
    private static final String VSAM_CLUSTER =
            "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS";

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
     * {@code RECORD KEY FD-CARD-NUM}, lines L31-L32). The
     * {@link Card} entity's {@link Card#getCardNum()} field maps to
     * the {@code card_num} column (V002 Flyway migration) which is
     * the primary key &mdash; ascending order on this field is
     * identical to the VSAM KSDS key sequence per AAP &sect;0.6.2.
     *
     * <p>Declared as a constant so any future refactor that needs to
     * change the sort key (e.g. for batch parallelism) does so in
     * exactly one place.</p>
     */
    private static final String SORT_FIELD_CARD_NUM = "cardNum";

    /**
     * The literal PAN-mask prefix &mdash; twelve {@code '*'}
     * characters. Concatenated with the last four digits of the
     * original PAN to produce the PCI-DSS v4.0 Requirement 3.4
     * compliant masked form ({@code "************" + last4}).
     * Twelve mask characters preserves the visual length of a
     * 16-digit PAN (the dominant card-brand length) so masked
     * values align in log files for diffability.
     */
    private static final String PAN_MASK_PREFIX = "************";

    /**
     * Per-record separator banner emitted after each
     * {@link #displayCardRecord(Card)} invocation so a downstream
     * log-parsing tool can split the output stream into per-record
     * blocks during the parallel-run validation window per AAP
     * &sect;0.6.2. Length and content mirror the
     * {@code CBACT01C:1100-DISPLAY-ACCT-RECORD} terminator banner
     * used by {@code AccountFileReaderService} for cross-program
     * consistency.
     */
    private static final String RECORD_SEPARATOR =
            "-------------------------------------------------";

    /**
     * Minimum length of the PAN required for the
     * {@link #maskPan(String)} helper to safely extract the trailing
     * four digits. PANs shorter than this (a malformed or test
     * input) collapse to the prefix-only mask &mdash; never the
     * literal value &mdash; so the helper cannot accidentally leak
     * a short prefix that itself contains digits.
     */
    private static final int PAN_LAST4_LENGTH = 4;

    /**
     * Spring Data JPA repository for the {@link Card} entity.
     * Constructor-injected; replaces the COBOL
     * {@code SELECT CARDFILE-FILE ASSIGN TO CARDFILE} file handle
     * plus the implicit {@code OPEN INPUT / READ NEXT / CLOSE}
     * lifecycle of the VSAM KSDS sequential reader
     * ({@code app/cbl/CBACT02C.cbl} L29&ndash;L33 and
     * L70&ndash;L87).
     */
    private final CardRepository cardRepository;

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
     * ("Constructor injection for all @Service, @Repository,
     * @Component, adapter, and config beans"). Both dependencies are
     * {@link Objects#requireNonNull validated non-null} at
     * construction so a misconfigured Spring context fails fast
     * during application startup rather than at the first method
     * invocation.
     *
     * @param cardRepository  the Spring Data JPA repository for the
     *                        {@link Card} entity; must not be
     *                        {@code null}
     * @param auditLogService the audit-log adapter bean; must not be
     *                        {@code null}
     */
    public CardFileReaderService(CardRepository cardRepository,
                                 AuditLogService auditLogService) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "cardRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Performs the sequential scan over the {@code cards} table
     * emitting one structured log line per record. The complete
     * Java translation of the COBOL {@code CBACT02C} PROCEDURE
     * DIVISION (L70&ndash;L87). Returns a {@link ReadResult}
     * carrying the COBOL program identifier and the count of
     * records processed for downstream verification, audit, and
     * parallel-run parity checks.
     *
     * <p>Execution sequence (mirroring COBOL paragraph order):</p>
     * <ol>
     *   <li>Emit the COBOL-verbatim START marker
     *       ({@code 'START OF EXECUTION OF PROGRAM CBACT02C'},
     *       COBOL L71).</li>
     *   <li>Capture {@code startMillis} so the audit emission can
     *       report scan duration (operational metric beyond what
     *       the COBOL source captured, but useful for SLA
     *       monitoring against the existing mainframe baseline per
     *       AAP &sect;0.7.2 NFR-performance).</li>
     *   <li>Issue a single {@link CardRepository#findAll(Sort)}
     *       call sorted by {@code cardNum} ascending &mdash;
     *       preserves the COBOL {@code ACCESS MODE SEQUENTIAL} on
     *       {@code RECORD KEY FD-CARD-NUM} semantic per AAP
     *       &sect;0.6.2.</li>
     *   <li>Iterate the materialised result list. For each
     *       {@link Card}, invoke
     *       {@link #displayCardRecord(Card)} (the Java equivalent
     *       of COBOL {@code DISPLAY CARD-RECORD} on L78) and
     *       increment the running counter.</li>
     *   <li>Emit the COBOL-verbatim END marker
     *       ({@code 'END OF EXECUTION OF PROGRAM CBACT02C'},
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
     * transaction is rolled back by Spring's transaction manager
     * and a {@code FAILED} audit event is emitted on a best-effort
     * basis before the exception propagates to the caller.</p>
     *
     * <p><b>PCI-DSS guarantee:</b> every per-record log line emitted
     * by {@link #displayCardRecord(Card)} masks the PAN via
     * {@link #maskPan(String)}. The CVV is no longer stored on the
     * {@link Card} entity (QA finding DB1; PCI-DSS v4.0 Requirement
     * 3.2 prohibits CVV persistence post-authorization) so no CVV
     * placeholder is needed. Reviewing developers MUST NOT
     * introduce any log statement here that emits
     * {@code card.getCardNum()} unmasked, nor reintroduce any
     * CVV-related accessor.</p>
     *
     * @return a {@link ReadResult} carrying the COBOL program ID
     *         ({@code "CBACT02C"}) and the number of card rows
     *         iterated; the {@code recordCount} is always
     *         non-negative
     * @throws RuntimeException if the repository call fails (e.g.
     *         database connectivity loss); propagated to the caller
     *         after a best-effort {@code FAILED} audit emission
     */
    @Transactional(readOnly = true)
    public ReadResult readAndDisplayAll() {
        // COBOL: CBACT02C.cbl L71 — DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
        LOG.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);

        // Capture start time so the audit emission carries scan
        // duration as an operational metric (beyond what the COBOL
        // source reported, but useful for SLA monitoring of the
        // batch reader against the existing mainframe runtime
        // baseline per AAP §0.7.2 NFR-performance).
        final long startMillis = System.currentTimeMillis();

        // COBOL: CBACT02C.cbl L61–L65 — APPL-RESULT counter and
        // implicit READ-NEXT progression in 1000-CARDFILE-GET-NEXT.
        // Use AtomicLong for the counter — semantically matches a
        // COBOL counter variable (which could be mutated in nested
        // paragraphs) and is idiomatic for use inside lambda /
        // Stream contexts if this method evolves to use them later.
        // Required by the schema's external_imports rule for this
        // service ("AtomicLong rather than primitive long is the
        // idiomatic stream-friendly counter pattern even though the
        // loop here is single-threaded").
        final AtomicLong count = new AtomicLong(0L);

        // Execution-correlation ID for the audit emission. Generated
        // fresh per execution so each invocation can be correlated
        // across OpenSearch documents and CloudWatch alarms even
        // when run multiple times within the same JVM (e.g. from a
        // Spring Batch JobLauncherTestUtils harness).
        final String executionId = UUID.randomUUID().toString();

        try {
            // COBOL: CBACT02C:0000-CARDFILE-OPEN (L118–L134) —
            // handled by Spring's transactional connection
            // acquisition; no explicit OPEN required because
            // @Transactional manages the JDBC connection lifecycle.
            //
            // COBOL: ACCESS MODE SEQUENTIAL on RECORD KEY FD-CARD-NUM
            // (L31–L32) — preserve CARD-NUM ascending order via
            // Sort.by("cardNum") so the Java scan emits rows in the
            // same order as the COBOL VSAM KSDS sequential read.
            // This is required for golden-output diffing during the
            // parallel-run validation window per AAP §0.6.2.
            //
            // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L74–L81) wraps
            //        PERFORM 1000-CARDFILE-GET-NEXT (L92–L116):
            //            READ CARDFILE-FILE INTO CARD-RECORD
            // Java equivalent: a single findAll(Sort) call returns
            // the entire result set materialised into memory; the
            // for-each loop replaces the PERFORM UNTIL loop. The
            // demo-scale CARDDATA cluster (a few hundred rows in
            // the golden fixture under app/data/ASCII/carddata.txt)
            // makes the pre-fetch acceptable; for production-scale
            // deployments a Spring Batch RepositoryItemReader with
            // chunk-oriented processing would be substituted while
            // preserving the externally-observable behaviour.
            final List<Card> cards =
                    cardRepository.findAll(Sort.by(SORT_FIELD_CARD_NUM));

            for (Card card : cards) {
                // COBOL: CBACT02C:1000-CARDFILE-GET-NEXT (L92–L116) +
                // DISPLAY CARD-RECORD (L78) — emit the per-row
                // structured log line with PCI-DSS-mandated PAN
                // masking (CVV is no longer stored — QA finding DB1).
                displayCardRecord(card);
                count.incrementAndGet();
            }

            // COBOL: CBACT02C:9000-CARDFILE-CLOSE (L136–L152) —
            // handled automatically by Spring's transaction manager
            // at method exit (the JDBC connection is returned to
            // the HikariCP pool).
            //
            // COBOL: CBACT02C.cbl L85 — DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
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
            // The schema's agent_prompt nominally references a
            // logBatchReaderCompleted method which does not exist
            // on AuditLogService; the schema's internal_imports
            // purpose canonically references logBatchJobLifecycle
            // as the audit method and is used here per that
            // authoritative source.
            auditLogService.logBatchJobLifecycle(
                    COBOL_PROGRAM_ID,
                    executionId,
                    STATUS_COMPLETED,
                    durationMillis,
                    buildAuditPayload(total),
                    null);

            // COBOL: CBACT02C.cbl L87 — GOBACK
            // Java equivalent: return the ReadResult carrying the
            // COBOL program ID and the processed record count for
            // programmatic verification by tests, batch schedulers,
            // and parallel-run parity-diff tooling.
            return new ReadResult(COBOL_PROGRAM_ID, total);
        } catch (RuntimeException ex) {
            // COBOL: CBACT02C:9999-ABEND-PROGRAM (L154–L158) — in
            // the source CBACT02C terminates the JVM (CEE3ABD) on
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
     * Emits a single structured SLF4J INFO log line representing
     * the per-record DISPLAY in the COBOL paragraph (L78
     * {@code DISPLAY CARD-RECORD}). Unlike its sibling
     * {@code AccountFileReaderService} (whose COBOL source has an
     * explicit field-by-field paragraph
     * {@code 1100-DISPLAY-ACCT-RECORD}), the COBOL
     * {@code CBACT02C} program emits the entire 150-byte record in
     * a single {@code DISPLAY} statement (L78). The Java target
     * emits one structured line whose key-value layout makes the
     * fields independently parseable by downstream log-processing
     * tools.
     *
     * <h2>PCI-DSS field handling (AAP &sect;0.6.6 &mdash; mandatory)</h2>
     * <table>
     *   <caption>Per-field disclosure decision</caption>
     *   <tr><th>COBOL field</th><th>Log treatment</th><th>Rationale</th></tr>
     *   <tr>
     *     <td>{@code CARD-NUM}</td>
     *     <td>Masked via {@link #maskPan(String)} to
     *         {@code ************} + last-4-digits</td>
     *     <td>PCI-DSS v4.0 Req 3.4 (CHD masking); the only
     *         AAP-approved deviation from byte-identical COBOL
     *         parity in this service</td>
     *   </tr>
     *   <tr>
     *     <td>{@code CARD-ACCT-ID}</td>
     *     <td>Verbatim</td>
     *     <td>11-digit account identifier; not PCI-DSS data and
     *         operationally useful for audit cross-references</td>
     *   </tr>
     *   <tr>
     *     <td>{@code CARD-CVV-CD}</td>
     *     <td><b>Not stored</b> &mdash; the
     *         {@code card_cvv_cd} column was removed (QA finding
     *         DB1); no placeholder is emitted</td>
     *     <td>PCI-DSS v4.0 Req 3.2 (SAD); CVV must never be
     *         persisted post-authorization. The {@link Card} entity
     *         has no CVV accessor.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code CARD-EMBOSSED-NAME}</td>
     *     <td>Verbatim</td>
     *     <td>Cardholder name — sensitive but not PCI-DSS CHD
     *         (Track 1/2 data is separately classified). Logged
     *         to enable operations to verify rendered output
     *         against COBOL fixtures.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code CARD-EXPIRAION-DATE}</td>
     *     <td>Verbatim (corrected-spelling getter)</td>
     *     <td>ISO-8601 date; operationally useful for
     *         expiration-bucket reports.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code CARD-ACTIVE-STATUS}</td>
     *     <td>Verbatim</td>
     *     <td>{@code 'Y'}/{@code 'N'} flag; not PCI-DSS data.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code FILLER PIC X(59)}</td>
     *     <td>Omitted</td>
     *     <td>Padding bytes with no relational counterpart; the
     *         {@link Card} entity does not declare them.</td>
     *   </tr>
     * </table>
     *
     * <p>Defensive guard: if {@code card} is {@code null} (which
     * cannot happen in production because
     * {@link CardRepository#findAll(Sort)} never returns a list
     * containing {@code null} elements, but may occur in mocked
     * unit tests), a single {@code "CARD-RECORD: (null record)"}
     * marker is logged and the method returns without
     * dereferencing the argument.</p>
     *
     * @param card the card entity to log; may be {@code null}
     *             (defensive)
     */
    private void displayCardRecord(Card card) {
        // COBOL: CBACT02C:L78 — DISPLAY CARD-RECORD
        if (card == null) {
            // Defensive — Spring Data JPA never returns null
            // elements in a List, but a unit test or mocked
            // repository might.
            LOG.info("CARD-RECORD: (null record)");
            return;
        }
        // PCI-DSS (AAP §0.6.6 + QA finding DB1): mask the PAN; the
        // CVV is no longer stored on the Card entity per PCI-DSS v4.0
        // Requirement 3.2 so no CVV placeholder is needed in the log
        // line. Field order otherwise mirrors the CVACT02Y.cpy
        // declaration order (minus CARD-CVV-CD) so a downstream
        // log-parser can correlate positions with the COBOL record
        // layout.
        LOG.info("CARD-RECORD: cardNum={} acctId={} name={} expiry={} status={}",
                maskPan(card.getCardNum()),
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus());
        // COBOL: per-record separator banner — emitted for diff
        // parity with the COBOL output stream and
        // AccountFileReaderService convention.
        LOG.info(RECORD_SEPARATOR);
    }

    /**
     * Mask the Primary Account Number (PAN) to last-four digits per
     * PCI-DSS v4.0 Requirement 3.4. Returns a string of the form
     * {@code "************" + last4} for inputs of length
     * {@value #PAN_LAST4_LENGTH} or greater; returns
     * {@code "************"} alone for shorter or {@code null}
     * inputs so a malformed or test input cannot accidentally leak
     * any digits.
     *
     * <p>This helper is package-private ({@code static}) so it can
     * be exercised by ad-hoc unit tests. The mask prefix length
     * (12 characters) preserves visual length parity with the
     * dominant 16-digit PAN so masked values align in log files
     * for diffability.</p>
     *
     * <p>The implementation deliberately uses
     * {@link String#substring(int)} on a positive length-derived
     * index, so it cannot raise {@link StringIndexOutOfBoundsException}
     * for any non-null input that passes the length precondition.
     * Null and short inputs are handled before any indexing.</p>
     *
     * @param pan the PAN to mask; may be {@code null} or shorter
     *            than {@value #PAN_LAST4_LENGTH} characters
     * @return the masked PAN; never {@code null}; never includes
     *         the full input value
     */
    static String maskPan(String pan) {
        if (pan == null || pan.length() < PAN_LAST4_LENGTH) {
            // Defensive — return prefix-only mask for null or
            // unexpectedly short inputs so the helper cannot leak
            // any portion of the value. Includes the case of a
            // malformed PAN whose length differs from the COBOL
            // PIC X(16) declaration.
            return PAN_MASK_PREFIX;
        }
        return PAN_MASK_PREFIX
                + pan.substring(pan.length() - PAN_LAST4_LENGTH);
    }

    /**
     * Builds the structured audit-event payload for the
     * {@link AuditLogService#logBatchJobLifecycle} emission. The
     * payload carries the entity name (mapped to the underlying
     * VSAM cluster {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}),
     * the processed record count, and the COBOL program identifier
     * for cross-referencing against operational dashboards.
     *
     * <p>The payload contains <b>no PII</b> and <b>no PAN-like
     * sequences</b> &mdash; only operational metadata.
     * {@link AuditLogService} additionally applies a
     * defense-in-depth sanitization pass that removes sensitive
     * keys and masks PAN-like sequences as a final guardrail, but
     * this method intentionally builds the payload from constants
     * and the integer record count only.</p>
     *
     * <p>The returned {@link Map} is a {@link LinkedHashMap} so
     * field insertion order is preserved: {@code program_id} &rarr;
     * {@code entity_name} &rarr; {@code record_count} &rarr;
     * {@code vsam_cluster}. The OpenSearch dashboard sees fields in
     * a stable, human-friendly sequence when the document is
     * rendered &mdash; a usability nicety that does not affect
     * OpenSearch's inverted index.</p>
     *
     * @param recordCount the number of card rows processed by the
     *                    scan; always non-negative
     * @return a mutable {@link Map} carrying the audit-event
     *         payload &mdash; safe to pass to
     *         {@link AuditLogService#logBatchJobLifecycle} which
     *         will sanitize and merge it into the audit document
     */
    private static Map<String, Object> buildAuditPayload(long recordCount) {
        // LinkedHashMap preserves insertion order so the OpenSearch
        // dashboard sees fields in a stable, human-friendly
        // sequence when the document is rendered. Identical
        // pattern to AccountFileReaderService.buildAuditPayload.
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("program_id", COBOL_PROGRAM_ID);
        payload.put("entity_name", ENTITY_NAME);
        payload.put("record_count", recordCount);
        payload.put("vsam_cluster", VSAM_CLUSTER);
        return payload;
    }

    /**
     * Result envelope returned by {@link #readAndDisplayAll()}.
     * Carries the COBOL program identifier ({@code "CBACT02C"})
     * and the number of {@link Card} rows processed during the
     * sequential scan. Designed to be consumed by tests and
     * batch-orchestration callers (e.g., a future Spring Batch
     * {@code Tasklet} wrapping this service).
     *
     * <p>Implemented as a Java {@code record} so the type is
     * immutable, the canonical constructor and accessors are
     * generated by the compiler, and the type-equality contract
     * is deterministic. Records were introduced in Java 16 and
     * are available under the Java 17+ target per AAP &sect;0.5.1.</p>
     *
     * <p>The companion {@link CardRepository#count()} method (from
     * the parent {@code JpaRepository}) provides a lighter-weight
     * row-count check; this record is returned only by the full
     * DISPLAY-paragraph scan so its {@code recordCount} is the
     * exact number of rows for which
     * {@link #displayCardRecord(Card)} was successfully
     * invoked.</p>
     *
     * @param programId   the COBOL program identifier
     *                    ({@code "CBACT02C"}) preserved verbatim
     *                    for traceability per AAP &sect;0.7.3
     * @param recordCount the number of card rows processed by the
     *                    scan; always non-negative
     */
    public record ReadResult(String programId, long recordCount) {
    }
}
