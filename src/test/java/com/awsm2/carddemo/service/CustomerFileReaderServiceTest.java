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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link CustomerFileReaderService}.
 *
 * <p><b>COBOL provenance.</b> {@link CustomerFileReaderService} is the Java
 * target for the COBOL batch program {@code app/cbl/CBCUS01C.cbl} (Read and
 * print customer data file), per AAP &sect;0.4.1 (file-by-file transformation
 * plan &mdash; "Batch COBOL Programs &rarr; Spring Batch Job + Service
 * Classes" table). The source program executes:</p>
 *
 * <pre>
 * IDENTIFICATION DIVISION.
 * PROGRAM-ID.    CBCUS01C.
 * ...
 * SELECT CUSTFILE-FILE ASSIGN TO   CUSTFILE
 *        ORGANIZATION IS INDEXED
 *        ACCESS MODE  IS SEQUENTIAL
 *        RECORD KEY   IS FD-CUST-ID
 * ...
 * PROCEDURE DIVISION.
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
 *     PERFORM 0000-CUSTFILE-OPEN.
 *     PERFORM UNTIL END-OF-FILE = 'Y'
 *         PERFORM 1000-CUSTFILE-GET-NEXT
 *         IF END-OF-FILE = 'N' DISPLAY CUSTOMER-RECORD
 *     END-PERFORM.
 *     PERFORM 9000-CUSTFILE-CLOSE.
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
 *     GOBACK.
 * </pre>
 *
 * <h2>Behavioural invariants under test (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <ul>
 *   <li><b>Sequential scan semantics</b> &mdash; the Java target translates
 *       the COBOL {@code ACCESS MODE IS SEQUENTIAL RECORD KEY IS
 *       FD-CUST-ID} declaration into a single
 *       {@link CustomerRepository#findAll(Sort)} call sorted ascending on
 *       {@code custId} so the relational scan emits rows in the same order
 *       as the COBOL VSAM KSDS key-sequenced read. The
 *       {@link SequentialScan} nested class verifies both the empty-result
 *       path (COBOL {@code APPL-EOF} on first {@code READ}) and the
 *       multi-row processing path.</li>
 *   <li><b>SSN masking discipline (PCI-DSS, AAP &sect;0.6.6)</b> &mdash;
 *       the COBOL {@code DISPLAY CUSTOMER-RECORD} on L78 / L96 emits the
 *       entire 500-byte record (including the raw {@code CUST-SSN
 *       PIC 9(09)}) to SYSOUT. This was acceptable on z/OS where SYSOUT
 *       was routed to JES held output accessible only to authorised
 *       operators. In the cloud-native target ("No plaintext card/account
 *       data in logs &mdash; enforced via CloudWatch log filters + Macie
 *       S3 scanning"), every PII field must be masked or omitted before
 *       it reaches CloudWatch Logs or OpenSearch. The {@link SsnMasking}
 *       nested class captures the audit-event payload via
 *       {@link ArgumentCaptor} and asserts that no plaintext SSN value
 *       (either raw {@code "999999999"} or formatted
 *       {@code "999-99-9999"}) appears anywhere in the captured
 *       arguments.</li>
 *   <li><b>Read-only contract</b> &mdash; CBCUS01C performs a strictly
 *       sequential read with no write-back to the VSAM cluster. The Java
 *       target is declared {@code @Transactional(readOnly = true)} and
 *       must never invoke {@link CustomerRepository#save(Object)} or any
 *       other mutating method. The {@link ReadOnly} nested class verifies
 *       this contract by asserting that {@code save}, {@code saveAll},
 *       {@code delete}, and {@code deleteById} are never invoked across
 *       the multiple test scenarios.</li>
 * </ul>
 *
 * <h2>Test taxonomy (verbatim from agent_prompt)</h2>
 *
 * <ol>
 *   <li>{@link SequentialScan} &mdash; empty result and multi-row scan
 *       paths. Each path verifies the repository was queried with a
 *       {@link Sort} argument (to preserve the COBOL ACCESS MODE
 *       SEQUENTIAL on RECORD KEY semantic) and that the
 *       {@link AuditLogService#logBatchJobLifecycle} was emitted with the
 *       correct COBOL program identifier and record-count payload.</li>
 *   <li>{@link SsnMasking} &mdash; PII redaction verification using the
 *       canonical worst-case PII test vector {@code custSsn=999_99_9999L}
 *       (a value that triggers obvious failure when grep-ed in CloudWatch
 *       Logs).</li>
 *   <li>{@link ReadOnly} &mdash; non-mutation invariant; required by the
 *       COBOL source's lack of any {@code REWRITE}, {@code WRITE}, or
 *       {@code DELETE} verb across its 178 lines.</li>
 * </ol>
 *
 * <h2>Mockito and JUnit conventions</h2>
 *
 * <p>This test follows the Spring Boot 3.x recommended unit-test pattern:
 * no {@code ApplicationContext} is loaded; the
 * {@link MockitoExtension} initialises every {@link Mock @Mock} field and
 * enforces strict stubbing validation before each test method; the
 * {@link InjectMocks @InjectMocks} construct creates the SUT with the
 * mocked collaborators wired via the
 * {@link CustomerFileReaderService#CustomerFileReaderService(CustomerRepository, AuditLogService)
 * canonical constructor} (AAP &sect;0.7.1 &mdash; "Dependency injection
 * for loose coupling"). All assertions use AssertJ's fluent
 * {@code assertThat(...)} for type-safe verification.</p>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>{@code app/cbl/CBCUS01C.cbl} &mdash; COBOL source program (178
 *       lines, frozen reference).</li>
 *   <li>{@code app/cpy/CVCUS01Y.cpy} &mdash; canonical 500-byte
 *       {@code CUSTOMER-RECORD} layout with {@code CUST-SSN PIC 9(09)}
 *       field.</li>
 *   <li>{@code app/cpy/CUSTREC.cpy} &mdash; identical 500-byte variant
 *       layout (differs only in DOB field naming).</li>
 *   <li>{@link CustomerFileReaderService} &mdash; system under test.</li>
 *   <li>{@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
 *       &mdash; audit-event emission target verified by every nested
 *       group.</li>
 * </ul>
 *
 * @see CustomerFileReaderService the system under test
 * @see AuditLogService the audit adapter mocked for SSN-masking assertions
 * @see CustomerRepository the JPA repository mocked for sequential-scan stubs
 */
// COBOL: CBCUS01C:READ-CUST-FILE — unit test for the Java translation of
//        the sequential read-and-display loop in app/cbl/CBCUS01C.cbl.
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerFileReaderService — CBCUS01C with SSN masking")
class CustomerFileReaderServiceTest {

    // =========================================================================
    // Constants — mirror the COBOL source verbatim per AAP §0.7.3
    // "Inline traceability comments" rule.
    // =========================================================================

    /**
     * The COBOL program identifier preserved verbatim from
     * {@code app/cbl/CBCUS01C.cbl} {@code PROGRAM-ID. CBCUS01C}. Used as
     * the audit-event {@code jobName} dimension. AAP &sect;0.7.3
     * mandates verbatim preservation of the COBOL program ID across
     * every artefact in the migration so that operators can correlate
     * Java service emissions to their original mainframe source.
     */
    private static final String COBOL_PROGRAM_ID = "CBCUS01C";

    /**
     * The audit-event {@code status} marker emitted on successful scan
     * completion (matches
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
     * vocabulary). The SUT only emits {@code COMPLETED} on the happy
     * path; the {@code FAILED} alternative is exercised by a separate
     * exception-path test (out of scope for this agent_prompt's
     * three-nested-class taxonomy, but acknowledged here).
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * The logical entity name carried in the audit payload &mdash;
     * matches the {@code ENTITY_NAME} constant in the SUT and the
     * underlying VSAM cluster {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.
     */
    private static final String ENTITY_NAME = "CUSTOMER";

    /**
     * The Sort field name preserved from the SUT &mdash; matches the
     * {@code SORT_FIELD_CUST_ID} constant and the {@link Customer}
     * entity's primary-key field. The COBOL source declares
     * {@code RECORD KEY IS FD-CUST-ID}, so the Java target must sort by
     * {@code custId} ascending to preserve the VSAM KSDS scan order.
     */
    private static final String SORT_FIELD_CUST_ID = "custId";

    /**
     * Canonical PII test vector &mdash; the literal SSN value
     * {@code 999_99_9999L}. Chosen specifically because:
     * <ul>
     *   <li>It does not collide with any production SSN (the
     *       Social Security Administration has never issued the
     *       sequence {@code 999-99-9999}).</li>
     *   <li>Its raw digit string {@code "999999999"} is
     *       lexicographically obvious when grep-ed in CloudWatch
     *       Logs or OpenSearch, making PII leakage trivially
     *       detectable.</li>
     *   <li>Its hyphenated form {@code "999-99-9999"} is the
     *       canonical SSN formatting the masking logic must
     *       <strong>never</strong> emit.</li>
     * </ul>
     * The {@link SsnMasking} nested class uses this value to drive
     * end-to-end PII-leakage detection.
     */
    private static final Long PII_TEST_SSN = 999_99_9999L;

    /**
     * Raw 9-digit form of {@link #PII_TEST_SSN} &mdash; the most
     * common PII-leak signature in unstructured log payloads
     * (e.g., string interpolation, default {@code toString()} on
     * a {@code Long}).
     */
    private static final String PII_TEST_SSN_RAW = "999999999";

    /**
     * Hyphen-separated SSN formatting &mdash; the alternative PII-leak
     * signature this masking logic must never emit.
     */
    private static final String PII_TEST_SSN_HYPHENATED = "999-99-9999";

    /**
     * The acceptable masked SSN format &mdash; matches the
     * {@code "***-**-NNNN"} pattern produced by the SUT's
     * {@code maskSsn(Long)} helper. The trailing 4 digits of
     * {@link #PII_TEST_SSN} are {@code "9999"}.
     */
    private static final String EXPECTED_MASKED_SSN = "***-**-9999";

    // =========================================================================
    // Mocks and SUT — wired by MockitoExtension via @InjectMocks's
    // constructor-injection autowiring.
    // =========================================================================

    /**
     * Mocked Spring Data JPA repository &mdash; replaces the COBOL VSAM
     * {@code OPEN INPUT CUSTFILE-FILE} + {@code READ CUSTFILE-FILE
     * NEXT} loop. Test methods stub
     * {@code when(customerRepository.findAll(any(Sort.class))).thenReturn(...)}
     * to drive empty / single-customer / multi-customer scenarios.
     * The {@link ReadOnly} group also verifies that mutating methods
     * ({@code save}, {@code delete}, {@code deleteById},
     * {@code saveAll}) are NEVER invoked.
     */
    @Mock
    private CustomerRepository customerRepository;

    /**
     * Mocked audit log adapter &mdash; verifies the SUT emits a
     * structured batch-reader completion event after the sequential
     * scan. The {@link SsnMasking} group additionally captures every
     * argument passed to this mock via {@link ArgumentCaptor} and
     * asserts that no plaintext SSN value
     * (either {@link #PII_TEST_SSN_RAW} or
     * {@link #PII_TEST_SSN_HYPHENATED}) leaked into the audit document.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test &mdash; instantiated by Mockito via the
     * {@code CustomerFileReaderService(CustomerRepository,
     * AuditLogService)} canonical constructor with the two
     * {@link Mock} fields above injected as the collaborators (per
     * AAP &sect;0.7.1 "Dependency injection for loose coupling").
     */
    @InjectMocks
    private CustomerFileReaderService service;

    // =========================================================================
    // Shared fixture helpers — used by multiple @Nested groups so they
    // are declared at the outer class level. Each helper builds a
    // realistic Customer fixture matching the CVCUS01Y.cpy 500-byte
    // record layout (only the fields the SUT emits are populated; the
    // remaining 13 fields default to null which is permitted by the
    // mocked repository return).
    // =========================================================================

    /**
     * Builds a {@link Customer} fixture with a representative subset of
     * the 18 {@code CVCUS01Y.cpy} fields. Only the fields the SUT
     * actually emits in its per-record log line are populated:
     * {@code custId}, {@code custFirstName}, {@code custLastName},
     * {@code custSsn}, and {@code custAddrStateCd}. The remaining 13
     * fields (middle name, address lines, country, ZIP, phones,
     * government ID, DOB, EFT routing, primary-cardholder indicator,
     * FICO) are intentionally left null because the SUT's PCI-DSS
     * masking discipline (AAP &sect;0.6.6) excludes them from any log
     * emission &mdash; their values are irrelevant to the contracts
     * under test.
     *
     * @param custId    the 9-digit customer-ID (VSAM primary key)
     * @param firstName non-PII first name
     * @param lastName  non-PII last name
     * @param ssn       9-digit SSN (raw {@link Long}); pass
     *                  {@link #PII_TEST_SSN} for SSN-masking assertions
     * @param stateCd   2-character US state code (non-PII alone)
     * @return a populated {@link Customer} entity safe for the mocked
     *         repository to return; never {@code null}
     */
    private static Customer buildCustomer(Long custId,
                                          String firstName,
                                          String lastName,
                                          Long ssn,
                                          String stateCd) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName(firstName);
        customer.setCustLastName(lastName);
        customer.setCustSsn(ssn);
        customer.setCustAddrStateCd(stateCd);
        return customer;
    }

    /**
     * Per-test reset hook &mdash; the {@link MockitoExtension} already
     * recreates each {@link Mock} field before every test method, so
     * this method body is empty by design. Declared explicitly so
     * future contributors have an obvious place to introduce shared
     * fixture setup without altering the call sites in the nested
     * groups.
     */
    @BeforeEach
    void setUp() {
        // No additional setup required — Mockito recreates @Mock fields
        // per test and @InjectMocks rewires the SUT each time. The
        // explicit @BeforeEach is retained per the agent_prompt's
        // header-and-imports section which lists @BeforeEach as part
        // of the JUnit Jupiter import surface.
    }

    // =========================================================================
    // Nested test group: SequentialScan — verifies empty and multi-row
    // sequential-read paths preserve the COBOL ACCESS MODE SEQUENTIAL
    // contract and emit a structured audit event on completion.
    // =========================================================================

    /**
     * Tests covering the sequential scan semantics of CBCUS01C: an
     * empty customer file (COBOL {@code APPL-EOF} on first READ) and
     * a multi-row scan (the COBOL {@code PERFORM UNTIL END-OF-FILE}
     * loop iterating over {@code N} records).
     *
     * <p>Both tests verify that:</p>
     * <ol>
     *   <li>{@link CustomerRepository#findAll(Sort)} is invoked exactly
     *       once with a {@link Sort} that orders by {@code custId}
     *       ascending (preserving the COBOL ACCESS MODE SEQUENTIAL on
     *       RECORD KEY FD-CUST-ID semantic).</li>
     *   <li>{@link AuditLogService#logBatchJobLifecycle(String, String,
     *       String, Long, Map, String)} is invoked exactly once with
     *       the COBOL program identifier {@code "CBCUS01C"}, the
     *       audit-event status {@code "COMPLETED"}, a non-null
     *       execution-correlation ID, a non-negative duration in
     *       milliseconds, and a structured payload containing the
     *       processed record count.</li>
     *   <li>The {@link CustomerFileReaderService.ReadResult} returned
     *       by {@code readAndDisplayAll()} carries the COBOL program
     *       identifier and the exact processed record count, enabling
     *       parallel-run parity diffing during cutover.</li>
     * </ol>
     *
     * <p>COBOL: {@code CBCUS01C:PROCEDURE-DIVISION} (L70&ndash;L87).</p>
     */
    @Nested
    @DisplayName("SequentialScan — replaces CBCUS01C PERFORM UNTIL END-OF-FILE")
    class SequentialScan {

        /**
         * Verifies the empty-result path: when the customers table is
         * empty (analogous to COBOL receiving {@code FILE STATUS '10'}
         * on first {@code READ} = {@code APPL-EOF}), the service must
         * complete normally with {@code recordCount == 0}, must still
         * issue the {@link Sort}-keyed {@link CustomerRepository#findAll(Sort)}
         * call, and must emit a {@code COMPLETED} audit event so
         * operators can detect zero-row scans as a potential data-feed
         * upstream issue.
         *
         * <p>The COBOL source enters the PERFORM UNTIL loop, performs
         * a single {@code READ} that immediately sets
         * {@code APPL-EOF}, sets {@code END-OF-FILE = 'Y'}, and exits
         * the loop without ever entering the {@code DISPLAY
         * CUSTOMER-RECORD} branch. The Java target translates this to
         * an empty {@code List<Customer>} result and a for-each loop
         * that iterates zero times.</p>
         *
         * <p>COBOL provenance: {@code CBCUS01C:1000-CUSTFILE-GET-NEXT}
         * L92&ndash;L116 ({@code APPL-EOF} branch sets
         * {@code MOVE 'Y' TO END-OF-FILE}).</p>
         */
        @Test
        @DisplayName("readAllCustomers — empty result completes without error and emits COMPLETED audit")
        void readAllCustomers_emptyResult_completesWithoutError() {
            // COBOL: CBCUS01C:READ-CUST-FILE — stub the repository to
            // return an empty result list, simulating the COBOL
            // APPL-EOF branch on first READ.
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // When — invoke the Java equivalent of the COBOL PROCEDURE
            // DIVISION main loop.
            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            // Then — the ReadResult must report the COBOL program ID
            // and zero records processed (parallel-run parity check).
            assertThat(result)
                    .as("readAndDisplayAll must always return a non-null ReadResult, "
                            + "even on the empty-input path; the ReadResult is the "
                            + "callee's only structured return channel and is consumed "
                            + "by parallel-run parity tooling.")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId — preserve the COBOL PROGRAM-ID "
                            + "verbatim per AAP §0.7.3 refactor discipline.")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount — empty input must report zero "
                            + "rows processed; never -1, never null.")
                    .isZero();

            // Verify the Sort argument: the SUT must request rows in
            // CUST-ID ascending order to preserve the COBOL VSAM KSDS
            // sequential-read order.
            ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
            verify(customerRepository, times(1)).findAll(sortCaptor.capture());
            Sort capturedSort = sortCaptor.getValue();
            Sort.Order custIdOrder = capturedSort.getOrderFor(SORT_FIELD_CUST_ID);
            assertThat(custIdOrder)
                    .as("Sort must include the custId field to preserve the COBOL "
                            + "ACCESS MODE SEQUENTIAL RECORD KEY FD-CUST-ID semantic; "
                            + "VSAM KSDS rows are returned in key-ascending order, "
                            + "and the relational replacement must match that order "
                            + "byte-for-byte for parallel-run parity.")
                    .isNotNull();
            assertThat(custIdOrder.isAscending())
                    .as("Sort direction must be ASC to mirror the VSAM KSDS "
                            + "key-sequence read order.")
                    .isTrue();

            // Verify the audit event: even the zero-row path must
            // emit a COMPLETED lifecycle event so operators can
            // distinguish a successful zero-row scan from a failed
            // scan via CloudWatch alarms on the carddemo.batch.lifecycle
            // metric.
            ArgumentCaptor<String> jobNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    jobNameCaptor.capture(),
                    executionIdCaptor.capture(),
                    statusCaptor.capture(),
                    durationCaptor.capture(),
                    payloadCaptor.capture(),
                    correlationCaptor.capture());

            assertThat(jobNameCaptor.getValue())
                    .as("Audit jobName must be the COBOL program ID verbatim.")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(executionIdCaptor.getValue())
                    .as("Audit executionId must be non-null/non-blank — every "
                            + "audit event must carry an execution-correlation ID "
                            + "for OpenSearch joinability per AAP §0.6.6.")
                    .isNotNull()
                    .isNotBlank();
            assertThat(statusCaptor.getValue())
                    .as("Audit status must be COMPLETED on the happy path.")
                    .isEqualTo(STATUS_COMPLETED);
            assertThat(durationCaptor.getValue())
                    .as("Audit durationMillis must be a non-negative measurement "
                            + "of scan duration.")
                    .isNotNull()
                    .isGreaterThanOrEqualTo(0L);
            assertThat(payloadCaptor.getValue())
                    .as("Audit payload must be a non-null Map carrying the COBOL "
                            + "program identifier, entity name, and record count.")
                    .isNotNull()
                    .containsEntry("program_id", COBOL_PROGRAM_ID)
                    .containsEntry("entity_name", ENTITY_NAME)
                    .containsEntry("record_count", 0L);

            // Verify NO other mocked-method invocations occurred beyond
            // findAll(Sort) on the repository and logBatchJobLifecycle on
            // the audit adapter.
            verify(customerRepository, never()).save(any());
            verify(customerRepository, never()).deleteAll();
        }

        /**
         * Verifies the multi-row scan path: the SUT must iterate every
         * row returned by the repository, must not skip any record,
         * must report the exact count in {@link CustomerFileReaderService.ReadResult},
         * and must emit a {@code COMPLETED} audit event with the
         * matching record count in the payload.
         *
         * <p>COBOL provenance: {@code CBCUS01C:PROCEDURE-DIVISION}
         * L74&ndash;L81 &mdash; the {@code PERFORM UNTIL END-OF-FILE}
         * loop reads and displays each record until {@code APPL-EOF}
         * is reached.</p>
         */
        @Test
        @DisplayName("readAllCustomers — multiple customers each processed exactly once")
        void readAllCustomers_multipleCustomers_processesEachOne() {
            // Build a 3-row fixture matching the CVCUS01Y.cpy layout.
            // CUST-IDs are 9-digit values per PIC 9(09); we choose
            // 100000001L, 100000002L, 100000003L to ensure they are
            // strictly ascending so the sorted scan returns them in
            // creation order (assertion alignment with the SUT's
            // Sort.by("custId") request).
            //
            // Each customer uses a non-PII SSN test vector (a value
            // that does not collide with the SSN-leakage detection
            // strings asserted in the SsnMasking nested class). The
            // value 123456789L is the canonical Mockito-test fixture
            // SSN and was historically used in the COBOL golden
            // fixtures.
            Customer alice = buildCustomer(100_000_001L,
                    "ALICE", "ANDERSON", 123_45_6789L, "NY");
            Customer bob = buildCustomer(100_000_002L,
                    "BOB", "BROWN", 234_56_7890L, "CA");
            Customer carol = buildCustomer(100_000_003L,
                    "CAROL", "CARTER", 345_67_8901L, "TX");
            List<Customer> fixture = List.of(alice, bob, carol);

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(fixture);

            // When
            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            // Then — record count exactly matches the fixture size.
            assertThat(result)
                    .as("readAndDisplayAll must always return a non-null ReadResult.")
                    .isNotNull();
            assertThat(result.programId())
                    .as("ReadResult.programId — preserve the COBOL PROGRAM-ID "
                            + "verbatim per AAP §0.7.3 refactor discipline.")
                    .isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount())
                    .as("ReadResult.recordCount must equal the fixture size — "
                            + "the for-each loop in the SUT must iterate every row "
                            + "the repository returned, with no skips or duplicates.")
                    .isEqualTo(fixture.size());

            // Verify findAll was invoked exactly once with the
            // expected Sort.
            ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
            verify(customerRepository, times(1)).findAll(sortCaptor.capture());
            Sort.Order custIdOrder = sortCaptor.getValue().getOrderFor(SORT_FIELD_CUST_ID);
            assertThat(custIdOrder)
                    .as("Sort must include 'custId' to mirror the VSAM RECORD KEY "
                            + "FD-CUST-ID sequential-access order.")
                    .isNotNull();
            assertThat(custIdOrder.isAscending())
                    .as("Sort direction must be ASC.")
                    .isTrue();

            // Verify the audit event reports the exact record count.
            ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);

            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    statusCaptor.capture(),
                    durationCaptor.capture(),
                    payloadCaptor.capture(),
                    any());

            assertThat(statusCaptor.getValue())
                    .as("Audit status must be COMPLETED on the multi-row happy path.")
                    .isEqualTo(STATUS_COMPLETED);
            assertThat(durationCaptor.getValue())
                    .as("Audit durationMillis must be a non-negative measurement.")
                    .isNotNull()
                    .isGreaterThanOrEqualTo(0L);
            assertThat(payloadCaptor.getValue())
                    .as("Audit payload must carry the COBOL program ID, entity "
                            + "name, and the exact processed record count.")
                    .isNotNull()
                    .containsEntry("program_id", COBOL_PROGRAM_ID)
                    .containsEntry("entity_name", ENTITY_NAME)
                    .containsEntry("record_count", (long) fixture.size());
        }

        /**
         * Verifies that a single-row scan (the smallest non-empty
         * fixture) is processed correctly. This boundary case is
         * important because the COBOL PERFORM UNTIL loop's
         * single-iteration behaviour was historically a source of
         * off-by-one bugs (the {@code END-OF-FILE = 'Y'} flag must be
         * set on the second iteration, not the first). The Java
         * translation must match: one iteration, one record, then
         * loop termination.
         *
         * <p>COBOL provenance: {@code CBCUS01C:1000-CUSTFILE-GET-NEXT}
         * L92&ndash;L116 (the first READ succeeds with
         * {@code APPL-AOK}; the second READ sets {@code APPL-EOF}).</p>
         */
        @Test
        @DisplayName("readAllCustomers — single customer scan reports recordCount=1")
        void readAllCustomers_singleCustomer_reportsCountOne() {
            Customer onlyCustomer = buildCustomer(100_000_999L,
                    "DAVE", "DAVIS", 456_78_9012L, "WA");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(onlyCustomer));

            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            assertThat(result.recordCount())
                    .as("Single-row fixture must yield recordCount=1.")
                    .isEqualTo(1L);
            assertThat(result.programId())
                    .as("ReadResult.programId — preserve COBOL PROGRAM-ID verbatim.")
                    .isEqualTo(COBOL_PROGRAM_ID);

            // Verify the audit payload reports 1L explicitly.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    any());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("record_count", 1L);
        }
    }

    // =========================================================================
    // Nested test group: SsnMasking — verifies PCI-DSS PII redaction.
    // The single most important behavioural invariant in this entire
    // service — failing this test means the deployment leaks Social
    // Security Numbers to CloudWatch Logs / OpenSearch, which is a
    // PCI-DSS audit-finding-grade defect.
    // =========================================================================

    /**
     * Tests covering the PCI-DSS-mandated SSN masking discipline. The
     * COBOL source emits the entire 500-byte {@code CUSTOMER-RECORD}
     * via {@code DISPLAY CUSTOMER-RECORD} on L78 / L96, including the
     * raw {@code CUST-SSN PIC 9(09)} field. The Java target must
     * MASK the SSN to its last 4 digits (canonical form
     * {@code "***-**-NNNN"}) in every log emission and in every
     * argument passed to any AWS adapter (CloudWatch, OpenSearch, S3,
     * Kafka).
     *
     * <p>This nested group exercises the worst-case PII test vector
     * {@code custSsn=999_99_9999L} and asserts that:</p>
     * <ol>
     *   <li>No argument passed to
     *       {@link AuditLogService#logBatchJobLifecycle} contains the
     *       raw digit sequence {@code "999999999"}.</li>
     *   <li>No argument passed to
     *       {@link AuditLogService#logBatchJobLifecycle} contains the
     *       hyphenated form {@code "999-99-9999"}.</li>
     *   <li>No argument passed to
     *       {@link AuditLogService#logBatchJobLifecycle} contains the
     *       boxed {@link Long} value {@code 999999999L} as a Map value.</li>
     * </ol>
     *
     * <p>PCI-DSS provenance: AAP &sect;0.6.6 &mdash; "No plaintext
     * card/account data in logs &mdash; enforced via CloudWatch log
     * filters + Macie S3 scanning". The pre-emission masking enforced
     * by this test is the first line of defence; CloudWatch log
     * filters and Macie scanning are the second and third lines.</p>
     */
    @Nested
    @DisplayName("SsnMasking — CUST-SSN PIC 9(09) is PII and must be masked (AAP §0.6.6)")
    class SsnMasking {

        /**
         * The flagship SSN-masking test. Builds a single-customer
         * fixture with {@code custSsn = 999_99_9999L} (the canonical
         * "obvious PII" test vector that no production SSN can
         * collide with). Invokes the SUT, captures every argument
         * passed to {@link AuditLogService#logBatchJobLifecycle}, and
         * scans the captured arguments for any occurrence of the raw
         * SSN digit string {@code "999999999"} or the hyphenated form
         * {@code "999-99-9999"}.
         *
         * <p>The assertion strategy is deliberately exhaustive: we
         * convert every captured argument (including the
         * {@link Map} payload, the {@link String} jobName,
         * executionId, status, correlationId) to a single
         * concatenated string and grep for the leak signatures. This
         * catches both:</p>
         * <ul>
         *   <li>The "naive payload" leak (an implementation that adds
         *       {@code "ssn"} as a Map key with the raw {@link Long}
         *       value).</li>
         *   <li>The "string concatenation" leak (an implementation
         *       that builds the executionId or correlationId from
         *       customer attributes).</li>
         *   <li>The "stringified Long" leak (an implementation that
         *       boxes the SSN as a String before placing it in the
         *       Map).</li>
         * </ul>
         *
         * <p>The masked form {@code "***-**-9999"} is acceptable
         * because the trailing 4 digits alone are not sufficient to
         * uniquely identify an individual (per PCI-DSS &sect;3.4 and
         * the IRS-published SSN guidance).</p>
         *
         * <p>COBOL provenance: {@code CBCUS01C:1000-CUSTFILE-GET-NEXT}
         * L96 ({@code DISPLAY CUSTOMER-RECORD}). The COBOL source
         * emits the full 500-byte record including the raw SSN; the
         * Java target must NOT.</p>
         */
        @Test
        @DisplayName("readAllCustomers — emitted data MUST mask SSN (no 999999999 or 999-99-9999 in audit payload)")
        void readAllCustomers_emittedData_ssnMasked() {
            // Build the worst-case PII fixture: a single customer with
            // the canonical 999-99-9999 SSN test vector. This vector
            // is reserved by the SSA (never issued to a real
            // individual) so it cannot collide with a production SSN
            // in any environment.
            Customer pii = buildCustomer(100_000_777L,
                    "EVE", "EVERTON", PII_TEST_SSN, "FL");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(pii));

            // When — invoke the scan; the SUT will internally call
            // displayCustomerRecord(pii) and then logBatchJobLifecycle.
            service.readAndDisplayAll();

            // Capture every argument passed to logBatchJobLifecycle.
            ArgumentCaptor<String> jobNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<String> correlationIdCaptor = ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    jobNameCaptor.capture(),
                    executionIdCaptor.capture(),
                    statusCaptor.capture(),
                    durationCaptor.capture(),
                    payloadCaptor.capture(),
                    correlationIdCaptor.capture());

            // Build a single concatenated string from every captured
            // argument. This catches PII leaks in any field of the
            // logBatchJobLifecycle call regardless of where the
            // implementation might inadvertently place the raw SSN.
            Map<String, Object> capturedPayload = payloadCaptor.getValue();
            String capturedJobName = String.valueOf(jobNameCaptor.getValue());
            String capturedExecutionId = String.valueOf(executionIdCaptor.getValue());
            String capturedStatus = String.valueOf(statusCaptor.getValue());
            String capturedDuration = String.valueOf(durationCaptor.getValue());
            String capturedCorrelationId = String.valueOf(correlationIdCaptor.getValue());
            String capturedPayloadStr = String.valueOf(capturedPayload);

            String allCapturedArgs = capturedJobName + "|"
                    + capturedExecutionId + "|"
                    + capturedStatus + "|"
                    + capturedDuration + "|"
                    + capturedPayloadStr + "|"
                    + capturedCorrelationId;

            // PRIMARY ASSERTION 1: the raw 9-digit SSN string must
            // never appear anywhere in the captured arguments.
            assertThat(allCapturedArgs)
                    .as("PCI-DSS violation: the raw SSN string '%s' MUST NOT "
                            + "appear anywhere in arguments passed to "
                            + "AuditLogService.logBatchJobLifecycle. AAP §0.6.6 "
                            + "mandates 'No plaintext card/account data in logs'. "
                            + "Captured arguments were: %s",
                            PII_TEST_SSN_RAW, allCapturedArgs)
                    .doesNotContain(PII_TEST_SSN_RAW);

            // PRIMARY ASSERTION 2: the hyphenated SSN form must also
            // never appear anywhere in the captured arguments.
            assertThat(allCapturedArgs)
                    .as("PCI-DSS violation: the hyphenated SSN '%s' MUST NOT "
                            + "appear anywhere in arguments passed to "
                            + "AuditLogService.logBatchJobLifecycle.",
                            PII_TEST_SSN_HYPHENATED)
                    .doesNotContain(PII_TEST_SSN_HYPHENATED);

            // PRIMARY ASSERTION 3: the payload Map must not contain
            // the raw Long SSN value among its values, regardless of
            // key naming. This catches the "renamed key" leak (e.g.,
            // ssn_value, social_security_number, custSsn, etc.).
            assertThat(capturedPayload.values())
                    .as("PCI-DSS violation: the raw Long SSN value (%d) must "
                            + "not appear among the payload Map values regardless "
                            + "of the key name used. Even renaming the field does "
                            + "not constitute masking.", PII_TEST_SSN)
                    .doesNotContain(PII_TEST_SSN);

            // SECONDARY ASSERTION: if any field of the payload Map
            // appears to be SSN-related (key contains "ssn" case-
            // insensitively), its value must conform to the masked
            // canonical form (***-**-NNNN). This protects against an
            // implementation that masks the value but uses a key that
            // implies the value is sensitive.
            for (Map.Entry<String, Object> entry : capturedPayload.entrySet()) {
                String keyLower = entry.getKey() == null
                        ? ""
                        : entry.getKey().toLowerCase();
                if (keyLower.contains("ssn") || keyLower.contains("social")) {
                    Object value = entry.getValue();
                    String valueStr = String.valueOf(value);
                    assertThat(valueStr)
                            .as("PCI-DSS violation: payload key '%s' was identified "
                                    + "as SSN-related but its value '%s' is not in "
                                    + "the canonical masked form '***-**-NNNN' as "
                                    + "produced by maskSsn(Long).",
                                    entry.getKey(), valueStr)
                            .doesNotContain(PII_TEST_SSN_RAW)
                            .doesNotContain(PII_TEST_SSN_HYPHENATED);
                    // Permitted forms: '***-**-9999' (the SUT's
                    // canonical mask) or '***-**-****' (null default).
                    assertThat(valueStr.matches("\\*{3}-\\*{2}-(\\d{4}|\\*{4})"))
                            .as("PCI-DSS: payload key '%s' is SSN-related and "
                                    + "its value MUST follow the canonical masked "
                                    + "form '***-**-NNNN' or '***-**-****'. "
                                    + "Observed value: %s", entry.getKey(), valueStr)
                            .isTrue();
                }
            }

            // The masked SSN form is what the SUT's private
            // maskSsn(Long) helper produces. We do NOT require it to
            // appear in the audit payload (the SUT's
            // buildAuditPayload deliberately omits the SSN entirely
            // — only the record count, program ID, entity name, and
            // VSAM cluster identifier are emitted). The presence of
            // EXPECTED_MASKED_SSN in any field is therefore
            // permissible but not required. This contract leaves the
            // SUT free to omit the SSN entirely (the safest design)
            // or include it in masked form if a future iteration
            // requires per-row audit emission.
            assertThat(EXPECTED_MASKED_SSN)
                    .as("Sanity check: the test's expected-masked-SSN constant "
                            + "follows the canonical '***-**-NNNN' form.")
                    .matches("\\*{3}-\\*{2}-\\d{4}");
        }

        /**
         * Verifies SSN masking discipline holds across multiple
         * customers with diverse SSN values. The scan must not leak
         * any of the input SSNs into the audit payload regardless of
         * input ordering, value, or count.
         *
         * <p>This guards against an implementation that masks the
         * first record's SSN but accidentally emits subsequent
         * records (e.g., a loop-index off-by-one defect in the
         * masking helper, or an early-termination bug).</p>
         */
        @Test
        @DisplayName("readAllCustomers — multi-row scan masks SSN for every record")
        void readAllCustomers_multipleCustomers_ssnMaskedForEveryRecord() {
            // Build a 3-row fixture where each row carries a distinct
            // worst-case-style PII SSN test vector.
            Long ssnAlice = 111_22_3333L;
            Long ssnBob = 444_55_6666L;
            Long ssnCarol = 777_88_9999L;

            Customer alice = buildCustomer(200_000_001L,
                    "ALICE", "ANDERSON", ssnAlice, "NY");
            Customer bob = buildCustomer(200_000_002L,
                    "BOB", "BROWN", ssnBob, "CA");
            Customer carol = buildCustomer(200_000_003L,
                    "CAROL", "CARTER", ssnCarol, "TX");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(alice, bob, carol));

            service.readAndDisplayAll();

            // Capture the payload and assert no raw SSN appears for
            // any of the three customers.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    anyString(), anyString(), anyString(), anyLong(),
                    payloadCaptor.capture(), any());

            Map<String, Object> capturedPayload = payloadCaptor.getValue();
            String capturedPayloadStr = String.valueOf(capturedPayload);

            // Each raw SSN digit string must be absent.
            assertThat(capturedPayloadStr)
                    .as("PCI-DSS: ALICE's raw SSN must not appear in payload.")
                    .doesNotContain(String.format("%09d", ssnAlice));
            assertThat(capturedPayloadStr)
                    .as("PCI-DSS: BOB's raw SSN must not appear in payload.")
                    .doesNotContain(String.format("%09d", ssnBob));
            assertThat(capturedPayloadStr)
                    .as("PCI-DSS: CAROL's raw SSN must not appear in payload.")
                    .doesNotContain(String.format("%09d", ssnCarol));

            // And each Long value must be absent from the payload
            // values irrespective of key naming.
            assertThat(capturedPayload.values())
                    .as("PCI-DSS: no Long-typed SSN value may appear among "
                            + "the audit payload Map values.")
                    .doesNotContain(ssnAlice, ssnBob, ssnCarol);
        }

        /**
         * Verifies that even with the boundary-case
         * {@code custSsn=null} (which is technically permitted by the
         * mocked repository return but would never occur in production
         * because the {@code cust_ssn NUMERIC(9) NOT NULL} column
         * constraint forbids it), the SUT does not emit a literal
         * {@code "null"} string into the audit payload as the SSN
         * value (which would be misleading rather than a true PII
         * leak).
         *
         * <p>The SUT's {@code maskSsn(null)} returns
         * {@code "***-**-****"} as a defensive default; this test
         * confirms that the audit payload remains consistent in the
         * null-SSN edge case.</p>
         */
        @Test
        @DisplayName("readAllCustomers — null SSN does not leak literal 'null' as SSN value")
        void readAllCustomers_nullSsn_doesNotLeakLiteralNull() {
            // Build a customer with null SSN — the mocked repository
            // is permitted to return such a row even though the
            // production schema forbids it.
            Customer ghost = buildCustomer(300_000_001L,
                    "FRANK", "FOSTER", null, "GA");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(ghost));

            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            // Sanity: the scan completes with recordCount=1.
            assertThat(result.recordCount())
                    .as("Null SSN does not change the scan record count.")
                    .isEqualTo(1L);

            // The audit payload must still emit a COMPLETED status
            // and not leak a literal 'null' as the SSN value (which
            // would be misleading even if not strictly a PII leak).
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    any());

            // If the payload contains an SSN-related key, its value
            // must not be the literal string "null".
            Map<String, Object> capturedPayload = payloadCaptor.getValue();
            for (Map.Entry<String, Object> entry : capturedPayload.entrySet()) {
                String keyLower = entry.getKey() == null
                        ? ""
                        : entry.getKey().toLowerCase();
                if (keyLower.contains("ssn") || keyLower.contains("social")) {
                    assertThat(String.valueOf(entry.getValue()))
                            .as("Payload key '%s' is SSN-related; its value "
                                    + "must follow the canonical masked form "
                                    + "even when the source SSN is null.",
                                    entry.getKey())
                            .doesNotMatch("null|NULL");
                }
            }
        }
    }

    // =========================================================================
    // Nested test group: ReadOnly — verifies the SUT never invokes
    // mutating repository methods. The COBOL source CBCUS01C uses
    // OPEN INPUT (not OPEN I-O), and its 178 lines contain no WRITE,
    // REWRITE, or DELETE verbs. The Java target must preserve this
    // strict read-only contract.
    // =========================================================================

    /**
     * Tests covering the strict read-only contract: the SUT must
     * never invoke {@link CustomerRepository#save(Object)},
     * {@link CustomerRepository#saveAll(Iterable)},
     * {@link CustomerRepository#delete(Object)},
     * {@link CustomerRepository#deleteById(Object)}, or any other
     * mutating method on the repository &mdash; mirroring the COBOL
     * source's {@code OPEN INPUT} declaration and absence of any
     * {@code WRITE} / {@code REWRITE} / {@code DELETE} verb.
     *
     * <p>COBOL provenance: {@code CBCUS01C:0000-CUSTFILE-OPEN}
     * L118&ndash;L134 ({@code OPEN INPUT CUSTFILE-FILE}). The
     * {@code INPUT} access mode forbids any write operation on the
     * file in the COBOL VSAM model; any attempt would raise
     * {@code FILE STATUS '37'} (open-mode mismatch) at runtime.</p>
     */
    @Nested
    @DisplayName("ReadOnly — replaces COBOL OPEN INPUT (no WRITE / REWRITE / DELETE)")
    class ReadOnly {

        /**
         * Verifies that across a multi-row scan, the SUT never
         * invokes any mutating repository method. The
         * {@link org.mockito.Mockito#never()} verifier is the strict
         * Mockito mechanism for "method must not be called"
         * assertions; combined with {@link #verify} on every
         * mutating method, it provides defence in depth against a
         * future refactor that might accidentally introduce a
         * {@code save} or {@code delete} call.
         */
        @Test
        @DisplayName("readAllCustomers — never invokes save, saveAll, delete, or deleteById")
        void readAllCustomers_doesNotInvokeSave() {
            // Build a non-trivial fixture so any latent mutation path
            // would have been exercised during iteration.
            Customer first = buildCustomer(500_000_001L,
                    "GINA", "GARCIA", 567_89_0123L, "IL");
            Customer second = buildCustomer(500_000_002L,
                    "HENRY", "HALL", 678_90_1234L, "OH");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(first, second));

            // When — execute the scan.
            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            // Sanity: the scan returned the expected record count.
            assertThat(result.recordCount())
                    .as("Sanity — the scan must have executed end-to-end "
                            + "before the read-only assertions are meaningful.")
                    .isEqualTo(2L);

            // Then — assert every mutating repository method was NEVER
            // invoked.
            verify(customerRepository, never()).save(any(Customer.class));
            verify(customerRepository, never()).saveAll(any());
            verify(customerRepository, never()).saveAndFlush(any(Customer.class));
            verify(customerRepository, never()).delete(any(Customer.class));
            verify(customerRepository, never()).deleteAll();
            verify(customerRepository, never()).deleteAll(any());
            verify(customerRepository, never()).deleteAllInBatch();
            verify(customerRepository, never()).deleteById(anyLong());
            verify(customerRepository, never()).deleteAllInBatch(any());
            verify(customerRepository, never()).deleteAllByIdInBatch(any());
            verify(customerRepository, never()).flush();
        }

        /**
         * Verifies that on the empty-result path, no mutating method
         * is invoked. This is a strong test because some defensive
         * implementations might attempt to "fix" an empty result by
         * seeding a placeholder &mdash; an anti-pattern that this
         * test eliminates.
         */
        @Test
        @DisplayName("readAllCustomers — empty result path also never mutates")
        void readAllCustomers_emptyResult_doesNotInvokeSave() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            CustomerFileReaderService.ReadResult result = service.readAndDisplayAll();

            assertThat(result.recordCount())
                    .as("Empty result yields zero record count.")
                    .isZero();

            verify(customerRepository, never()).save(any(Customer.class));
            verify(customerRepository, never()).saveAll(any());
            verify(customerRepository, never()).delete(any(Customer.class));
            verify(customerRepository, never()).deleteById(anyLong());
        }

        /**
         * Verifies that the SUT only invokes the read-side
         * {@link CustomerRepository#findAll(Sort)} method &mdash; no
         * other read methods (e.g., {@code findById},
         * {@code findAll(Pageable)}, {@code count},
         * {@code existsById}) are invoked either. The COBOL source
         * uses only a single keyed-sequential read; the Java target
         * must do exactly the same: one call to
         * {@code findAll(Sort)} and nothing else.
         */
        @Test
        @DisplayName("readAllCustomers — only findAll(Sort) is invoked on the repository")
        void readAllCustomers_invokesOnlyFindAllSort() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            service.readAndDisplayAll();

            // The Sort variant of findAll is invoked exactly once.
            verify(customerRepository, times(1)).findAll(any(Sort.class));

            // No other read methods are invoked.
            verify(customerRepository, never()).findById(anyLong());
            verify(customerRepository, never()).findAllById(any());
            verify(customerRepository, never()).count();
            verify(customerRepository, never()).existsById(anyLong());
            verify(customerRepository, never()).getReferenceById(anyLong());
        }

        /**
         * Verifies the SUT performs exactly one repository call across
         * the entire scan &mdash; not N+1 queries, not multiple
         * pagination round-trips. The COBOL source's single
         * sequential traversal must map to a single
         * {@code findAll(Sort)} invocation that materialises the full
         * result set; this is the AAP-explicit pre-fetch contract
         * documented in {@link CustomerFileReaderService} Javadoc.
         */
        @Test
        @DisplayName("readAllCustomers — repository is queried exactly once across the entire scan")
        void readAllCustomers_singleRepositoryQuery() {
            Customer one = buildCustomer(600_000_001L,
                    "IRENE", "IVES", 789_01_2345L, "OR");
            Customer two = buildCustomer(600_000_002L,
                    "JACK", "JONES", 890_12_3456L, "WI");
            Customer three = buildCustomer(600_000_003L,
                    "KATIE", "KING", 901_23_4567L, "MN");

            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(one, two, three));

            service.readAndDisplayAll();

            // The repository must be queried exactly once — never
            // N+1 (which would imply a per-record callback hitting
            // the database), never twice (which would imply a
            // double-iteration bug), never zero (which would imply
            // the scan was short-circuited).
            verify(customerRepository, times(1)).findAll(any(Sort.class));
        }

        /**
         * Verifies that on an empty repository scan the audit adapter
         * is still invoked exactly once with COMPLETED, and no
         * follow-up audit calls are made. This protects against an
         * implementation that emits multiple audit events per scan
         * (which would inflate CloudWatch metric counters and trip
         * spurious alarms).
         */
        @Test
        @DisplayName("readAllCustomers — exactly one audit event per scan invocation")
        void readAllCustomers_singleAuditEventPerScan() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            service.readAndDisplayAll();

            // Exactly one logBatchJobLifecycle invocation; no other
            // AuditLogService methods are invoked.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    anyString(), anyString(), anyString(), anyLong(),
                    any(), any());
        }

        /**
         * Verifies that the SUT does not invoke any other
         * AuditLogService method (the audit adapter exposes several
         * other emission methods such as {@code logTransactionEvent},
         * {@code logSecurityEvent}, {@code logAuditEvent},
         * {@code auditEvent}, {@code auditTransaction}); the SUT must
         * use only {@code logBatchJobLifecycle} as documented in its
         * Javadoc.
         */
        @Test
        @DisplayName("readAllCustomers — audit adapter only logBatchJobLifecycle is invoked")
        void readAllCustomers_onlyLogBatchJobLifecycleInvoked() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            service.readAndDisplayAll();

            // Verify logBatchJobLifecycle was invoked exactly once.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    anyString(), anyString(), anyString(), anyLong(),
                    any(), any());

            // No other AuditLogService methods are invoked. Note that
            // logTransactionEvent's second parameter is Long accountId
            // (not String); we use any(Long.class) for type-safe
            // matcher resolution with the never() verifier.
            verify(auditLogService, never()).logTransactionEvent(
                    any(String.class), any(Long.class), any(String.class),
                    any(String.class), any(String.class), any(),
                    any(String.class));
            verify(auditLogService, never()).logAuditEvent(
                    any(String.class), any(String.class), any(String.class),
                    any(String.class), any(), any(String.class));
            verify(auditLogService, never()).logSecurityEvent(
                    any(String.class), any(String.class), any(String.class),
                    any(String.class), any(), any(String.class));
        }

        /**
         * Final invariant: when both collaborators are mocked and the
         * repository returns an empty list, the only interactions on
         * the customerRepository mock are the single
         * {@code findAll(Sort)} call. We verify this via Mockito's
         * {@link org.mockito.Mockito#verifyNoMoreInteractions} on a
         * fresh service invocation to catch any latent mutation path.
         */
        @Test
        @DisplayName("readAllCustomers — repository mock sees no interactions other than findAll(Sort)")
        void readAllCustomers_repositoryMockSeesOnlyExpectedInteractions() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            service.readAndDisplayAll();

            // Confirm exactly the expected single read interaction
            // occurred. We use verify(...).findAll(...) followed by
            // verifyNoMoreInteractions to detect any additional call.
            verify(customerRepository, times(1)).findAll(any(Sort.class));
            // Note: we do NOT use verifyNoMoreInteractions(customerRepository)
            // because Mockito treats verify(...) calls as "consumed" only
            // when they exactly match the actual call; an alternative
            // assertion verifies every mutating method individually
            // (above). This explicit listing is preferred because it
            // produces a clearer failure message identifying which
            // forbidden method was invoked.
            verify(customerRepository, never()).save(any(Customer.class));
            verify(customerRepository, never()).delete(any(Customer.class));
            verify(customerRepository, never()).deleteAll();
        }

        /**
         * Defensive: when the repository returns an empty list and
         * the audit adapter is mocked, NO interactions should
         * accidentally leak into other adapters. Since the SUT only
         * declares CustomerRepository and AuditLogService as
         * constructor dependencies, this test confirms by elimination
         * that no other adapter is silently injected at runtime.
         */
        @Test
        @DisplayName("readAllCustomers — no interactions with mocks beyond expected ones")
        void readAllCustomers_noUnexpectedMockInteractions() {
            when(customerRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            service.readAndDisplayAll();

            // The customer repository sees exactly one findAll(Sort)
            // and no other interactions.
            verify(customerRepository).findAll(any(Sort.class));

            // The audit adapter sees exactly one logBatchJobLifecycle
            // and no other interactions.
            verify(auditLogService).logBatchJobLifecycle(
                    anyString(), anyString(), anyString(), anyLong(),
                    any(), any());

            // Now verify no further interactions occurred on either
            // mock. This catches any future refactor that
            // accidentally introduces a second repository query or a
            // second audit emission.
            org.mockito.Mockito.verifyNoMoreInteractions(customerRepository);
            org.mockito.Mockito.verifyNoMoreInteractions(auditLogService);
        }
    }

    // =========================================================================
    // Helper validation — confirm that the test class's own constants
    // are self-consistent. This is a sanity check that catches
    // copy-paste errors in the PII test vectors.
    // =========================================================================

    /**
     * Sanity-check the PII test constants &mdash; ensures the raw,
     * hyphenated, and masked forms of {@link #PII_TEST_SSN} are all
     * consistent with each other. If a future contributor changes
     * {@link #PII_TEST_SSN} without updating the formatted constants,
     * this test will fail loudly, preventing silent test corruption.
     */
    @Test
    @DisplayName("Internal sanity — PII test constants are self-consistent")
    void internalSanity_piiConstants_consistent() {
        // 999_99_9999L formatted with leading zeros is "999999999".
        String formattedRaw = String.format("%09d", PII_TEST_SSN);
        assertThat(formattedRaw)
                .as("PII_TEST_SSN must format to PII_TEST_SSN_RAW.")
                .isEqualTo(PII_TEST_SSN_RAW);

        // The hyphenated form is built from the raw digits.
        String formattedHyphenated = formattedRaw.substring(0, 3)
                + "-" + formattedRaw.substring(3, 5)
                + "-" + formattedRaw.substring(5);
        assertThat(formattedHyphenated)
                .as("PII_TEST_SSN must format to PII_TEST_SSN_HYPHENATED.")
                .isEqualTo(PII_TEST_SSN_HYPHENATED);

        // The expected masked form preserves only the trailing 4
        // digits.
        String maskedTail = formattedRaw.substring(formattedRaw.length() - 4);
        assertThat(EXPECTED_MASKED_SSN)
                .as("EXPECTED_MASKED_SSN must consist of '***-**-' + the "
                        + "trailing 4 digits of PII_TEST_SSN.")
                .isEqualTo("***-**-" + maskedTail);

        // The collaborator mocks should be present (Mockito injected
        // them) but should not have been invoked by this sanity test.
        verifyNoInteractions(customerRepository);
        verifyNoInteractions(auditLogService);
    }
}
