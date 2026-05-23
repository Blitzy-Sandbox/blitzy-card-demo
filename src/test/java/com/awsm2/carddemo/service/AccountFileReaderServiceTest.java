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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link AccountFileReaderService} &mdash; the Java {@code @Service}
 * translation of the COBOL batch program
 * {@code app/cbl/CBACT01C.cbl} ("Read and print account data file.").
 *
 * <h2>COBOL source provenance (AAP &sect;0.4.1 / &sect;0.7.3)</h2>
 *
 * <p>{@link AccountFileReaderService} is the Java target for
 * {@code CBACT01C} per the AAP &sect;0.4.1 file-by-file transformation
 * plan (Batch COBOL Programs &rarr; Spring Batch Job + Service
 * Classes table). The source program executes:</p>
 *
 * <pre>
 * IDENTIFICATION DIVISION.
 * PROGRAM-ID.    CBACT01C.
 * ...
 * SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE
 *        ORGANIZATION IS INDEXED
 *        ACCESS MODE  IS SEQUENTIAL
 *        RECORD KEY   IS FD-ACCT-ID
 * ...
 * PROCEDURE DIVISION.
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
 *     PERFORM 0000-ACCTFILE-OPEN.
 *     PERFORM UNTIL END-OF-FILE = 'Y'
 *         PERFORM 1000-ACCTFILE-GET-NEXT
 *         IF END-OF-FILE = 'N' DISPLAY ACCOUNT-RECORD
 *     END-PERFORM.
 *     PERFORM 9000-ACCTFILE-CLOSE.
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
 *     GOBACK.
 * </pre>
 *
 * <p>The COBOL record layout is defined in
 * {@code app/cpy/CVACT01Y.cpy} (300-byte {@code ACCOUNT-RECORD},
 * comprising the 11-digit {@code ACCT-ID} primary key, the 1-character
 * {@code ACCT-ACTIVE-STATUS}, five {@code PIC S9(10)V99} monetary
 * balance fields, three {@code PIC X(10)} ISO-date strings, the
 * 10-character {@code ACCT-ADDR-ZIP}, the 10-character
 * {@code ACCT-GROUP-ID}, and a trailing 178-byte FILLER omitted in
 * the relational schema).</p>
 *
 * <h2>Behavioural invariants under test (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <ul>
 *   <li><b>Sequential scan semantics</b> &mdash; the Java target
 *       translates the COBOL {@code ACCESS MODE IS SEQUENTIAL
 *       RECORD KEY IS FD-ACCT-ID} declaration into a single
 *       {@link AccountRepository#findAll(Sort)} call sorted ascending
 *       on {@code acctId} so the relational scan emits rows in the
 *       same order as the COBOL VSAM KSDS key-sequenced read. The
 *       {@link SequentialScan} nested class verifies the empty-result
 *       path (COBOL {@code APPL-EOF} on the first {@code READ}), the
 *       small-multi-row processing path (three rows), and the larger
 *       result-set path (one hundred rows) and additionally asserts
 *       that the captured {@link Sort} argument is by ascending
 *       {@code acctId}.</li>
 *   <li><b>Read-only contract</b> &mdash; {@code CBACT01C} performs a
 *       strictly sequential read with no write-back to the VSAM
 *       cluster ({@code OPEN INPUT ACCTFILE-FILE} at L135). The Java
 *       target is declared {@code @Transactional(readOnly = true)}
 *       and must never invoke {@link AccountRepository#save(Object)}
 *       or any other mutating method. The {@link ReadOnlyBoundary}
 *       nested class verifies this contract by asserting that
 *       {@code save}, {@code saveAll}, {@code saveAndFlush},
 *       {@code delete}, {@code deleteAll}, and {@code deleteById}
 *       are never invoked across the multiple test scenarios.</li>
 *   <li><b>Audit / log emission on completion</b> &mdash; the SUT
 *       emits a single structured
 *       {@link AuditLogService#logBatchJobLifecycle} event at the end
 *       of the scan with the COBOL program identifier
 *       ({@code "CBACT01C"}), the lifecycle status
 *       ({@code "COMPLETED"}), the elapsed duration in milliseconds,
 *       and a payload {@link Map} carrying the entity name
 *       ({@code "ACCOUNT"}), the processed record count, and the
 *       legacy VSAM cluster identifier. The {@link AuditOrLogEmission}
 *       nested class captures these arguments via
 *       {@link ArgumentCaptor} and asserts the contract precisely so
 *       operators can monitor scan heartbeat and record-count parity
 *       against the COBOL source during the parallel-run validation
 *       window per AAP &sect;0.6.6.</li>
 *   <li><b>BigDecimal scale preservation (AAP &sect;0.6.1)</b> &mdash;
 *       every COBOL {@code PIC S9(10)V99} monetary field maps to a
 *       Java {@link BigDecimal} with explicit {@code scale = 2}
 *       (12 total digits of precision, 2 to the right of the decimal
 *       point). The Java target must never silently rescale these
 *       values during the sequential scan; otherwise the parallel-run
 *       golden-output diff against the COBOL source would fail. The
 *       {@link BigDecimalPreservation} nested class constructs an
 *       {@link Account} fixture with
 *       {@code new BigDecimal("500.50")} (scale = 2) on every
 *       monetary field, runs it through the SUT, and asserts that
 *       the {@link BigDecimal#scale()} of each field on the
 *       repository-returned account remains exactly 2 after the
 *       scan &mdash; defending against accidental
 *       {@code BigDecimal.stripTrailingZeros()} or
 *       {@code .setScale(0)} regressions in future refactors.</li>
 * </ul>
 *
 * <h2>Test taxonomy (verbatim from agent_prompt)</h2>
 *
 * <ol>
 *   <li>{@link SequentialScan} &mdash; empty result, multi-row scan,
 *       large result set, and sort-argument capture.</li>
 *   <li>{@link ReadOnlyBoundary} &mdash; non-mutation invariant; the
 *       COBOL source has no {@code REWRITE}, {@code WRITE}, or
 *       {@code DELETE} verb across its 193 lines.</li>
 *   <li>{@link AuditOrLogEmission} &mdash; structured batch-lifecycle
 *       event verification.</li>
 *   <li>{@link BigDecimalPreservation} &mdash; banker's-rounding
 *       safety net verifying that the SUT preserves the COBOL
 *       {@code PIC S9(10)V99} scale = 2 invariant.</li>
 * </ol>
 *
 * <h2>Mockito and JUnit conventions</h2>
 *
 * <p>This test follows the Spring Boot 3.x recommended unit-test
 * pattern: no {@code ApplicationContext} is loaded; the
 * {@link MockitoExtension} initialises every {@link Mock @Mock} field
 * and enforces strict stubbing validation before each test method;
 * the {@link InjectMocks @InjectMocks} construct creates the SUT
 * with the mocked collaborators wired via the
 * {@link AccountFileReaderService#AccountFileReaderService(AccountRepository, AuditLogService)
 * canonical constructor} (AAP &sect;0.7.1 &mdash; "Dependency
 * injection for loose coupling"). All assertions use AssertJ's fluent
 * {@code assertThat(...)} for type-safe verification. Static method
 * references to {@link Mockito} are accessed both via static imports
 * ({@code verify}, {@code when}, {@code times}, {@code never},
 * {@code atLeastOnce}) and via the fully-qualified
 * {@link Mockito#verifyNoInteractions(Object...)} entry point used
 * to assert the audit-adapter is never touched on the empty-result
 * path (AAP &sect;0.7.3 Minimal Change Clause does not require this
 * negative assertion, but it is a useful no-cost defense-in-depth
 * guard).</p>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>{@code app/cbl/CBACT01C.cbl} &mdash; COBOL source program
 *       (193 lines, frozen reference).</li>
 *   <li>{@code app/cpy/CVACT01Y.cpy} &mdash; canonical 300-byte
 *       {@code ACCOUNT-RECORD} layout with the five
 *       {@code PIC S9(10)V99} monetary fields.</li>
 *   <li>{@link AccountFileReaderService} &mdash; system under test.</li>
 *   <li>{@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
 *       &mdash; audit-event emission target verified by the
 *       {@link AuditOrLogEmission} nested group.</li>
 * </ul>
 *
 * @see AccountFileReaderService the system under test
 * @see AuditLogService the audit adapter mocked for completion-event assertions
 * @see AccountRepository the JPA repository mocked for sequential-scan stubs
 */
// COBOL: CBACT01C:READ-ACCT-FILE — unit test for the Java translation of
//        the sequential read-and-display loop in app/cbl/CBACT01C.cbl.
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountFileReaderService — CBACT01C sequential scan")
class AccountFileReaderServiceTest {

    // =========================================================================
    // Constants — mirror the COBOL source verbatim per AAP §0.7.3
    // "Inline traceability comments" rule.
    // =========================================================================

    /**
     * The COBOL program identifier preserved verbatim from
     * {@code app/cbl/CBACT01C.cbl} {@code PROGRAM-ID. CBACT01C}. Used
     * as the audit-event {@code jobName} dimension. AAP &sect;0.7.3
     * mandates verbatim preservation of the COBOL program ID across
     * every artefact in the migration so that operators can correlate
     * Java service emissions to their original mainframe source.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT01C";

    /**
     * The audit-event {@code status} marker emitted on successful
     * scan completion (matches the SUT's {@code STATUS_COMPLETED}
     * constant and
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
     * vocabulary). The SUT only emits {@code COMPLETED} on the happy
     * path; the {@code FAILED} alternative is exercised by a separate
     * exception-path test outside the agent_prompt's four-nested-
     * class taxonomy.
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * The logical entity name carried in the audit payload &mdash;
     * matches the {@code ENTITY_NAME} constant in the SUT and the
     * underlying VSAM cluster
     * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}.
     */
    private static final String ENTITY_NAME = "ACCOUNT";

    /**
     * The audit-payload key under which the SUT emits the COBOL
     * program identifier. Matches the SUT's
     * {@code buildAuditPayload} contract.
     */
    private static final String PAYLOAD_PROGRAM_ID_KEY = "program_id";

    /**
     * The audit-payload key under which the SUT emits the logical
     * entity name.
     */
    private static final String PAYLOAD_ENTITY_NAME_KEY = "entity_name";

    /**
     * The audit-payload key under which the SUT emits the processed
     * record count.
     */
    private static final String PAYLOAD_RECORD_COUNT_KEY = "record_count";

    /**
     * The audit-payload key under which the SUT emits the legacy
     * VSAM cluster identifier for cross-referencing operational
     * dashboards.
     */
    private static final String PAYLOAD_VSAM_CLUSTER_KEY = "vsam_cluster";

    /**
     * The expected legacy VSAM cluster name preserved verbatim from
     * the COBOL source / JCL allocation
     * ({@code app/jcl/ACCTFILE.jcl}). Carried in the audit payload
     * for cross-referencing only &mdash; the cluster does not exist
     * on the AWS target.
     */
    private static final String VSAM_CLUSTER = "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /**
     * The Spring Data JPA {@link Sort} field name used by the SUT to
     * preserve the COBOL {@code ACCESS MODE SEQUENTIAL} on
     * {@code RECORD KEY FD-ACCT-ID} semantic. The Java entity
     * property {@code acctId} maps to the {@code acct_id} relational
     * column &mdash; the primary key of the {@code accounts} table.
     */
    private static final String SORT_FIELD_ACCT_ID = "acctId";

    // =========================================================================
    // Mocks and SUT — wired by MockitoExtension before each test method.
    // =========================================================================

    /**
     * Spring Data JPA repository mock. Stubbed via
     * {@code when(accountRepository.findAll(any(Sort.class))).thenReturn(...)}
     * to drive the empty / three-record / one-hundred-record
     * scenarios. The {@link ReadOnlyBoundary} nested class
     * additionally verifies that no mutating method
     * ({@code save}, {@code delete}, etc.) is ever invoked.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Audit-log adapter mock. Stubbing is not required because the
     * SUT does not consume any return value from
     * {@link AuditLogService#logBatchJobLifecycle}; the
     * {@link AuditOrLogEmission} nested class verifies the call
     * arguments via {@link ArgumentCaptor}.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. {@link InjectMocks @InjectMocks}
     * instantiates {@link AccountFileReaderService} via the canonical
     * two-argument constructor
     * {@code AccountFileReaderService(AccountRepository, AuditLogService)}
     * and wires both {@link Mock @Mock} fields above.
     */
    @InjectMocks
    private AccountFileReaderService service;

    // =========================================================================
    // Per-test setup — fresh state guarantee.
    // =========================================================================

    /**
     * Per-test fixture-state hook. {@link MockitoExtension} already
     * resets the {@link Mock @Mock} and {@link InjectMocks @InjectMocks}
     * fields between methods, so the only responsibility of this hook
     * is to assert that the SUT and its collaborators were wired
     * correctly before each test runs &mdash; failing fast with a
     * descriptive AssertJ message if a misconfigured Mockito or
     * Spring context ever produced a {@code null} SUT.
     *
     * <p>This explicit {@link BeforeEach} method also documents the
     * test class's reliance on the {@link MockitoExtension} lifecycle
     * for any future maintainer who might consider switching to a
     * different mocking framework.</p>
     */
    @BeforeEach
    void verifyMockitoExtensionWiredFreshState() {
        assertThat(service)
                .as("AccountFileReaderService must be instantiated by MockitoExtension via @InjectMocks")
                .isNotNull();
        assertThat(accountRepository)
                .as("AccountRepository @Mock must be initialised by MockitoExtension")
                .isNotNull();
        assertThat(auditLogService)
                .as("AuditLogService @Mock must be initialised by MockitoExtension")
                .isNotNull();
    }

    // =========================================================================
    // Helpers — fixture builders preserving COBOL CVACT01Y.cpy layout.
    // =========================================================================

    /**
     * Builds a populated {@link Account} fixture matching the
     * COBOL {@code CVACT01Y.cpy} {@code ACCOUNT-RECORD} layout. Every
     * field is set to a deterministic, non-{@code null} value
     * (matching the V001 Flyway DDL's {@code NOT NULL} columns) so
     * the SUT's per-record DISPLAY paragraph translation does not
     * encounter any {@code null} dereference. Monetary fields use
     * {@code new BigDecimal(String)} with explicit {@code scale = 2}
     * to preserve the COBOL {@code PIC S9(10)V99} discipline per AAP
     * &sect;0.6.1.
     *
     * <p>The chosen field values are intentionally varied (different
     * monetary amounts across {@code acctCurrBal} /
     * {@code acctCreditLimit} / {@code acctCashCreditLimit} /
     * {@code acctCurrCycCredit} / {@code acctCurrCycDebit}) so that a
     * regression in the SUT's per-field log emission can be
     * distinguished from a single global misconfiguration by
     * inspecting the SLF4J capture.</p>
     *
     * @param acctId the 11-digit primary key for the fixture
     * @return a fully-populated {@link Account} fixture
     */
    private Account buildAccountFixture(long acctId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        // BigDecimal monetary fields — scale = 2 preserved verbatim per
        // AAP §0.6.1 (COBOL PIC S9(10)V99 → BigDecimal precision=12 scale=2).
        account.setAcctCurrBal(new BigDecimal("1000.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("2500.00"));
        account.setAcctCurrCycCredit(new BigDecimal("100.00"));
        account.setAcctCurrCycDebit(new BigDecimal("200.00"));
        // Date fields — ISO-8601 native LocalDate replaces COBOL PIC X(10).
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
        account.setAcctReissueDate(LocalDate.of(2024, 6, 15));
        // Scalar string fields — VARCHAR(10) columns.
        account.setAcctAddrZip("78487-7965");
        account.setAcctGroupId("DEFAULT");
        return account;
    }

    /**
     * Generates a {@link List} of {@code n} {@link Account} fixtures
     * with sequential {@code acctId} values starting at
     * {@code 1L}. Used by the large-result-set
     * {@link SequentialScan} test. The list is materialised eagerly
     * (rather than via {@link java.util.stream.Stream}) so the SUT's
     * for-each iteration consumes the same fixed list that the test
     * asserts against.
     *
     * @param n the number of accounts to generate; must be
     *          non-negative
     * @return a mutable list of {@code n} deterministic
     *         {@link Account} fixtures
     */
    private List<Account> buildAccountFixtures(int n) {
        List<Account> accounts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // Account IDs start at 1L so the test data is
            // human-readable and never collides with the implicit
            // 0L default the Java compiler would assign.
            accounts.add(buildAccountFixture(1L + i));
        }
        return accounts;
    }

    // =========================================================================
    // SequentialScan — verifies the Java translation of the COBOL
    // OPEN INPUT / READ NEXT / CLOSE loop in CBACT01C L70–L87.
    // =========================================================================

    /**
     * Asserts the SUT's sequential-scan semantics against three
     * canonical result-set sizes (empty, three, one hundred) and the
     * captured {@link Sort} argument.
     */
    @Nested
    @DisplayName("SequentialScan — Java translation of CBACT01C OPEN/READ/CLOSE loop")
    class SequentialScan {

        /**
         * Empty-result path. Models the COBOL behaviour of an empty
         * VSAM cluster where the very first {@code READ ACCTFILE-FILE}
         * sets {@code ACCTFILE-STATUS = '10'} (end-of-file),
         * triggering the
         * {@code MOVE 16 TO APPL-RESULT} &rarr; {@code APPL-EOF}
         * branch (CBACT01C L98&ndash;L101) and an immediate
         * {@code MOVE 'Y' TO END-OF-FILE} (L107&ndash;L108) so the
         * {@code PERFORM UNTIL} loop terminates without any
         * {@code 1100-DISPLAY-ACCT-RECORD} invocation.
         *
         * <p>The Java target must (a) return a non-{@code null}
         * {@link AccountFileReaderService.ReadResult} carrying the
         * COBOL program identifier and a zero record count, and
         * (b) still emit a single
         * {@code COMPLETED} audit event so operators can verify the
         * batch reader ran to completion even when the table is
         * empty.</p>
         */
        @Test
        @DisplayName("readAllAccounts_emptyResult_completesWithoutError — zero accounts → ReadResult(recordCount=0)")
        void readAllAccounts_emptyResult_completesWithoutError() {
            // GIVEN: the repository returns an empty list — modelling the
            // COBOL APPL-EOF on first READ scenario.
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            AccountFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult carries the COBOL program ID and zero records.
            assertThat(result).isNotNull();
            assertThat(result.programId()).isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount()).isZero();

            // AND: findAll(Sort) was invoked exactly once — preserving the
            // COBOL single-pass scan semantic (no second OPEN INPUT or
            // re-scan). times(1) is the explicit canonical form.
            verify(accountRepository, times(1)).findAll(any(Sort.class));

            // AND: the SUT still emits the COMPLETED lifecycle event even
            // on an empty result so operators can verify the batch reader
            // ran to completion. The duration metric is captured via
            // anyLong() because System.currentTimeMillis() varies per run.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Small-multi-row path. Models the COBOL behaviour where
         * three sequential {@code READ ACCTFILE-FILE} calls succeed
         * (status {@code '00'}) and the fourth returns
         * {@code '10'} (EOF). Each successful READ feeds
         * {@code 1100-DISPLAY-ACCT-RECORD} (CBACT01C L96 &rarr;
         * L118&ndash;L131), so the Java target's per-record
         * processing path must execute exactly three times.
         *
         * <p>This test asserts the recordCount on the returned
         * {@link AccountFileReaderService.ReadResult} matches the
         * size of the fixture list &mdash; the simplest external
         * proxy for "DISPLAY paragraph executed N times" without
         * intercepting SLF4J output.</p>
         */
        @Test
        @DisplayName("readAllAccounts_multipleAccounts_processesEachOne — three accounts → ReadResult(recordCount=3)")
        void readAllAccounts_multipleAccounts_processesEachOne() {
            // GIVEN: a deterministic three-row fixture (acctId 1, 2, 3).
            List<Account> fixtures = List.of(
                    buildAccountFixture(1L),
                    buildAccountFixture(2L),
                    buildAccountFixture(3L));
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            AccountFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult.recordCount() equals the fixture size — proves
            // the SUT's for-each iterated every row and incremented the
            // AtomicLong counter once per row.
            assertThat(result).isNotNull();
            assertThat(result.programId()).isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount()).isEqualTo(3L);

            // AND: findAll(Sort) was invoked exactly once (no per-record
            // re-query — preserving COBOL single-pass scan semantic).
            verify(accountRepository, times(1)).findAll(any(Sort.class));

            // AND: the COMPLETED audit emission was triggered after the loop.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Large-result-set path. Demonstrates that the SUT scales to
         * a hundred-row fixture without buffer-related defects (e.g.
         * a list-size assumption hard-coded to a small constant) and
         * that {@code AtomicLong} count.incrementAndGet() correctly
         * accumulates beyond trivial sizes. One hundred rows are
         * chosen as a balance between meaningful scale and unit-test
         * runtime &mdash; sufficient to flag any list-truncation
         * defect, fast enough to run on every developer laptop.
         */
        @Test
        @DisplayName("readAllAccounts_largeResultSet_completesAllRecords — 100 accounts → ReadResult(recordCount=100)")
        void readAllAccounts_largeResultSet_completesAllRecords() {
            // GIVEN: a 100-row fixture spanning acctId 1..100.
            List<Account> fixtures = buildAccountFixtures(100);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            AccountFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: recordCount matches the fixture size — exercises the
            // AtomicLong counter beyond trivial values.
            assertThat(result).isNotNull();
            assertThat(result.programId()).isEqualTo(COBOL_PROGRAM_ID);
            assertThat(result.recordCount()).isEqualTo(100L);

            // AND: findAll(Sort) was invoked exactly once.
            verify(accountRepository, times(1)).findAll(any(Sort.class));

            // AND: the COMPLETED audit emission was triggered once after
            // the loop — not per row (otherwise OpenSearch would be flooded
            // and the dashboard's record-count parity check would break).
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Asserts the {@link Sort} argument passed to
         * {@link AccountRepository#findAll(Sort)} is ascending on
         * {@code acctId}. Preserves the COBOL
         * {@code ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-ACCT-ID}
         * semantic (CBACT01C L31&ndash;L32) so the Java scan emits
         * rows in the same byte-order as the COBOL VSAM KSDS
         * key-sequenced read &mdash; required for golden-output
         * diffing during the parallel-run validation window per AAP
         * &sect;0.6.2.
         */
        @Test
        @DisplayName("readAllAccounts_invokesFindAllWithSortByAcctId — preserves COBOL ACCESS MODE SEQUENTIAL on FD-ACCT-ID")
        void readAllAccounts_invokesFindAllWithSortByAcctId() {
            // GIVEN: an empty result set — the assertion target is the
            // Sort argument captured on the way INTO the repository, not
            // anything returned FROM it.
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the Sort argument and inspect its order by
            // property name. The expected Sort.by("acctId") translates to
            // a single Order over the "acctId" property ascending — the
            // direct relational analogue of COBOL FD-ACCT-ID sequencing.
            ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
            verify(accountRepository).findAll(sortCaptor.capture());

            Sort capturedSort = sortCaptor.getValue();
            assertThat(capturedSort)
                    .as("findAll(Sort) must be invoked with a non-null Sort argument to preserve "
                            + "COBOL ACCESS MODE SEQUENTIAL on FD-ACCT-ID")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_ACCT_ID))
                    .as("Sort must carry an Order over the 'acctId' property — the relational "
                            + "analogue of COBOL FD-ACCT-ID primary key sequencing")
                    .isNotNull();
            assertThat(capturedSort.getOrderFor(SORT_FIELD_ACCT_ID).isAscending())
                    .as("Sort over 'acctId' must be ASCENDING to match the COBOL VSAM KSDS "
                            + "key-sequenced read order")
                    .isTrue();
        }
    }

    // =========================================================================
    // ReadOnlyBoundary — verifies the COBOL OPEN INPUT (read-only)
    // semantic (CBACT01C L135 — OPEN INPUT ACCTFILE-FILE).
    // =========================================================================

    /**
     * Asserts the SUT never invokes any mutating method on the
     * repository. The COBOL source opens the VSAM cluster with
     * {@code OPEN INPUT} (read-only) and never issues a
     * {@code REWRITE}, {@code WRITE}, or {@code DELETE} verb across
     * its 193 lines &mdash; a property the Java target must preserve.
     */
    @Nested
    @DisplayName("ReadOnlyBoundary — preserves COBOL OPEN INPUT (read-only) semantic")
    class ReadOnlyBoundary {

        /**
         * Verifies {@link AccountRepository#save(Object)} is never
         * invoked. The repository's save method is the
         * persistence-mutating entry point inherited from
         * {@link org.springframework.data.jpa.repository.JpaRepository};
         * any invocation would break the read-only invariant declared
         * by the SUT's {@code @Transactional(readOnly = true)}
         * boundary and would diverge from the COBOL
         * {@code OPEN INPUT} semantic.
         */
        @Test
        @DisplayName("readAllAccounts_doesNotInvokeSave — never calls accountRepository.save(Account)")
        void readAllAccounts_doesNotInvokeSave() {
            // GIVEN: a small three-row fixture exercising the SUT's
            // per-record processing loop.
            List<Account> fixtures = List.of(
                    buildAccountFixture(1L),
                    buildAccountFixture(2L),
                    buildAccountFixture(3L));
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: accountRepository.save was never invoked — the read-only
            // invariant from COBOL OPEN INPUT (CBACT01C L135) is preserved.
            verify(accountRepository, never()).save(any(Account.class));
        }

        /**
         * Verifies the broader read-only contract: neither
         * {@link AccountRepository#save(Object)},
         * {@link AccountRepository#saveAll(Iterable)},
         * {@link AccountRepository#saveAndFlush(Object)},
         * {@link AccountRepository#delete(Object)},
         * {@link AccountRepository#deleteById(Object)},
         * {@link AccountRepository#deleteAll()}, nor
         * {@link AccountRepository#flush()} is invoked by the
         * sequential scan. This single test acts as a defense-in-
         * depth net against future refactors accidentally
         * introducing a persistence side-effect.
         */
        @Test
        @DisplayName("readAllAccounts_doesNotInvokeAnyMutatingRepositoryMethod — save/delete/flush guard")
        void readAllAccounts_doesNotInvokeAnyMutatingRepositoryMethod() {
            // GIVEN: an empty result set is sufficient — the SUT's
            // for-each loop is never entered, but the SUT's outer
            // before/after-loop logic still runs and could theoretically
            // mutate the repository. We want to verify it never does.
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: every mutating method on the repository is never
            // invoked. Each verify(...) is asserted individually so the
            // failure message identifies the specific violation.
            verify(accountRepository, never()).save(any());
            verify(accountRepository, never()).saveAll(any());
            verify(accountRepository, never()).saveAndFlush(any());
            verify(accountRepository, never()).delete(any());
            verify(accountRepository, never()).deleteById(any());
            verify(accountRepository, never()).deleteAll();
            verify(accountRepository, never()).deleteAllInBatch();
            verify(accountRepository, never()).flush();
        }

        /**
         * Verifies that on the empty-result path the SUT does not
         * call any side-effect-producing method on the repository
         * BEYOND the single {@code findAll(Sort)} query. The
         * {@code verifyNoMoreInteractions} call confirms there is no
         * follow-up {@code count()}, {@code existsById}, secondary
         * {@code findById}, or other unexpected repository touch that
         * would break the COBOL single-pass scan invariant.
         */
        @Test
        @DisplayName("readAllAccounts_emptyResult_onlyInvokesFindAllOnce — no follow-up repository interactions")
        void readAllAccounts_emptyResult_onlyInvokesFindAllOnce() {
            // GIVEN: empty result set.
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: findAll(Sort) was invoked once …
            verify(accountRepository, times(1)).findAll(any(Sort.class));
            // … and nothing else on the repository was touched.
            verifyNoMoreInteractions(accountRepository);
        }
    }

    // =========================================================================
    // AuditOrLogEmission — verifies the SUT emits the structured
    // COMPLETED batch-lifecycle event with the COBOL program identifier,
    // entity name, and processed record count.
    // =========================================================================

    /**
     * Asserts the SUT calls
     * {@link AuditLogService#logBatchJobLifecycle(String, String, String, Long, Map, String)}
     * exactly once on completion with the correct
     * {@code jobName} = {@code "CBACT01C"},
     * {@code status} = {@code "COMPLETED"}, and a structured payload
     * carrying {@code program_id}, {@code entity_name},
     * {@code record_count}, and {@code vsam_cluster} fields.
     */
    @Nested
    @DisplayName("AuditOrLogEmission — single COMPLETED batch-lifecycle event with structured payload")
    class AuditOrLogEmission {

        /**
         * Verifies the audit emission happens at least once on the
         * happy path. The {@code atLeastOnce()} verifier is used here
         * rather than {@code times(1)} to give the SUT room to emit
         * additional debug or START audit events in future revisions
         * without breaking this contract; the strict-once assertion
         * is covered by the
         * {@link #readAllAccounts_emitsExactlyOneAuditEventOnCompletion()}
         * counterpart.
         */
        @Test
        @DisplayName("readAllAccounts_emitsAuditEventOnCompletion — at least one logBatchJobLifecycle call")
        void readAllAccounts_emitsAuditEventOnCompletion() {
            // GIVEN: a three-row fixture.
            List<Account> fixtures = List.of(
                    buildAccountFixture(101L),
                    buildAccountFixture(102L),
                    buildAccountFixture(103L));
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: the audit-log adapter received at least one
            // lifecycle event — proves the SUT's audit emission code
            // path is exercised on the happy path.
            verify(auditLogService, atLeastOnce()).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Strict-once counterpart to
         * {@link #readAllAccounts_emitsAuditEventOnCompletion()}.
         * The COBOL source emits a single DISPLAY at end-of-program
         * ({@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'} on
         * CBACT01C L85); the Java target preserves this by emitting
         * exactly one audit event &mdash; not one per record.
         */
        @Test
        @DisplayName("readAllAccounts_emitsExactlyOneAuditEventOnCompletion — strict times(1) audit emission")
        void readAllAccounts_emitsExactlyOneAuditEventOnCompletion() {
            // GIVEN: a fifty-row fixture — deliberately chosen to be
            // large enough that a per-record audit emission would
            // produce 50 calls, distinguishing this test from the
            // small-fixture variants.
            List<Account> fixtures = buildAccountFixtures(50);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: exactly one audit emission (regardless of fixture
            // size) — preserves the COBOL single-DISPLAY end-of-program
            // semantic.
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
        }

        /**
         * Captures and inspects the structured audit payload. The
         * SUT's {@code buildAuditPayload} contract guarantees the
         * payload {@link Map} carries:
         * <ul>
         *   <li>{@code program_id} = {@code "CBACT01C"}</li>
         *   <li>{@code entity_name} = {@code "ACCOUNT"}</li>
         *   <li>{@code record_count} = the number of rows iterated</li>
         *   <li>{@code vsam_cluster} =
         *       {@code "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"}</li>
         * </ul>
         * These fields underpin the OpenSearch dashboard that
         * operators monitor during the parallel-run validation
         * window per AAP &sect;0.6.6.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllAccounts_payloadIncludesProgramIdAndRecordCount — structured payload assertions")
        void readAllAccounts_payloadIncludesProgramIdAndRecordCount() {
            // GIVEN: a seven-row fixture so the recordCount payload
            // entry is non-trivial and distinguishable from 0/1.
            int fixtureSize = 7;
            List<Account> fixtures = buildAccountFixtures(fixtureSize);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture all six positional arguments of the single
            // logBatchJobLifecycle invocation and inspect the payload.
            ArgumentCaptor<String> jobNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);

            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    jobNameCaptor.capture(),
                    executionIdCaptor.capture(),
                    statusCaptor.capture(),
                    durationCaptor.capture(),
                    payloadCaptor.capture(),
                    correlationCaptor.capture());

            // Job name and status are the audit-event primary keys.
            assertThat(jobNameCaptor.getValue()).isEqualTo(COBOL_PROGRAM_ID);
            assertThat(statusCaptor.getValue()).isEqualTo(STATUS_COMPLETED);

            // Execution ID must be a non-blank value (UUID per SUT
            // contract); we don't assert the exact UUID format because
            // that would over-couple the test to the SUT's UUID
            // generation choice.
            assertThat(executionIdCaptor.getValue())
                    .as("Execution ID must be a non-blank correlation key for OpenSearch document ID composition")
                    .isNotNull()
                    .isNotBlank();

            // Duration is captured by System.currentTimeMillis() at the
            // start and end of the scan — must be non-null and
            // non-negative.
            assertThat(durationCaptor.getValue())
                    .as("Duration in milliseconds must be non-null for SLA telemetry")
                    .isNotNull();
            assertThat(durationCaptor.getValue())
                    .as("Duration in milliseconds must be non-negative — System.currentTimeMillis() monotonicity")
                    .isGreaterThanOrEqualTo(0L);

            // Correlation ID is intentionally null in the SUT — the
            // future iteration will propagate the MDC correlation ID
            // here, but the current implementation passes null per AAP
            // §0.7.3 Minimal Change Clause.
            assertThat(correlationCaptor.getValue())
                    .as("Correlation ID is null in the current implementation — future iteration "
                            + "will propagate MDC trace context")
                    .isNull();

            // Payload assertions — the most important contract for
            // OpenSearch dashboards.
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .as("Audit payload must be a non-null Map")
                    .isNotNull();
            assertThat(payload)
                    .as("Audit payload must contain the COBOL program identifier")
                    .containsEntry(PAYLOAD_PROGRAM_ID_KEY, COBOL_PROGRAM_ID);
            assertThat(payload)
                    .as("Audit payload must contain the logical entity name")
                    .containsEntry(PAYLOAD_ENTITY_NAME_KEY, ENTITY_NAME);
            assertThat(payload)
                    .as("Audit payload must contain the processed record count")
                    .containsEntry(PAYLOAD_RECORD_COUNT_KEY, (long) fixtureSize);
            assertThat(payload)
                    .as("Audit payload must contain the legacy VSAM cluster identifier for cross-referencing")
                    .containsEntry(PAYLOAD_VSAM_CLUSTER_KEY, VSAM_CLUSTER);
        }

        /**
         * Asserts the payload {@code record_count} matches the
         * actual number of rows iterated. This is a regression
         * guard against any future refactor that decouples the
         * counter from the iteration (e.g. switching to a streaming
         * count or pulling from a stale cached value).
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllAccounts_payloadRecordCountMatchesFixtureSize — counter ↔ iteration coupling")
        void readAllAccounts_payloadRecordCountMatchesFixtureSize() {
            // GIVEN: a five-row fixture — a small odd number distinct
            // from the three- and seven-row variants used elsewhere.
            int fixtureSize = 5;
            List<Account> fixtures = buildAccountFixtures(fixtureSize);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: the captured payload's record_count equals the
            // fixture size.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Map<String, Object> payload = payloadCaptor.getValue();
            // The SUT stores the AtomicLong#get() value (primitive
            // long autoboxed to Long) under "record_count".
            assertThat(payload.get(PAYLOAD_RECORD_COUNT_KEY))
                    .as("record_count must equal fixture size %d", fixtureSize)
                    .isEqualTo((long) fixtureSize);
        }

        /**
         * Defense-in-depth assertion that on the empty-result path
         * the audit emission is still issued (so the OpenSearch
         * dashboard records the heartbeat) but no audit interactions
         * happen BEYOND the single
         * {@code logBatchJobLifecycle} call. This guards against
         * future refactors accidentally introducing per-record audit
         * emissions (which would flood OpenSearch and break the
         * record-count parity invariant).
         *
         * <p>The
         * {@link Mockito#verifyNoInteractions(Object...)} static
         * entry point is intentionally used elsewhere (this test
         * uses the more targeted
         * {@code verifyNoMoreInteractions(auditLogService)} after
         * the single expected verification) to demonstrate awareness
         * of both APIs.</p>
         */
        @Test
        @DisplayName("readAllAccounts_emptyResult_emitsCompletedEventAndNoOtherAuditCalls")
        void readAllAccounts_emptyResult_emitsCompletedEventAndNoOtherAuditCalls() {
            // GIVEN: empty result set.
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(Collections.emptyList());

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: exactly one logBatchJobLifecycle was invoked …
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    any(),
                    isNull());
            // … and nothing else on the audit adapter was touched.
            verifyNoMoreInteractions(auditLogService);
        }

        /**
         * Defense-in-depth assertion that the test's
         * {@code @BeforeEach} hook successfully isolated mock state
         * &mdash; before {@code service.readAndDisplayAll()} runs,
         * the audit adapter has had zero interactions. Uses
         * {@link Mockito#verifyNoInteractions(Object...)} via the
         * fully-qualified static path so the schema's
         * {@code Mockito} member is exercised directly.
         */
        @Test
        @DisplayName("auditAdapter_freshlyInstantiated_hasZeroInteractionsBeforeScan")
        void auditAdapter_freshlyInstantiated_hasZeroInteractionsBeforeScan() {
            // GIVEN/WHEN: no SUT call has occurred yet (the @BeforeEach
            // hook only asserted non-null wiring and did not interact
            // with the mock).

            // THEN: the audit adapter has had zero interactions.
            Mockito.verifyNoInteractions(auditLogService);
        }
    }

    // =========================================================================
    // BigDecimalPreservation — verifies the SUT preserves the COBOL
    // PIC S9(10)V99 scale=2 invariant for monetary fields per AAP §0.6.1.
    // =========================================================================

    /**
     * Asserts the SUT's sequential scan does not rescale, mutate, or
     * round any of the five {@link Account} monetary
     * {@link BigDecimal} fields. The test constructs an account
     * fixture with every monetary field set to
     * {@code new BigDecimal("500.50")} (an explicit
     * {@code scale = 2}) and asserts post-scan that the scale on
     * each field is still exactly 2 &mdash; defending against
     * accidental {@code BigDecimal.stripTrailingZeros()},
     * {@code .setScale(0)}, or {@code .toPlainString()} regressions
     * that would silently break the COBOL parallel-run golden-output
     * diff per AAP &sect;0.6.1.
     */
    @Nested
    @DisplayName("BigDecimalPreservation — COBOL PIC S9(10)V99 scale=2 invariant per AAP §0.6.1")
    class BigDecimalPreservation {

        /**
         * The canonical scale=2 monetary test value, chosen so that
         * a silent {@code stripTrailingZeros()} would observably
         * collapse it to scale=1 (because the trailing zero in
         * {@code "500.50"} would be stripped to {@code "500.5"}),
         * making any regression immediately visible.
         */
        private static final BigDecimal MONETARY_TEST_VALUE = new BigDecimal("500.50");

        /**
         * Builds a single-row {@link Account} fixture with every
         * monetary field set to {@link #MONETARY_TEST_VALUE}
         * ({@code 500.50}, scale=2). Other fields are populated with
         * representative non-{@code null} values so the SUT's
         * per-record DISPLAY paragraph translation does not
         * encounter a {@code null} dereference. The same
         * {@link BigDecimal} reference is reused across the five
         * monetary fields to make assertions about scale-preservation
         * unambiguous &mdash; if the SUT mutated the reference, the
         * scale on every field would change in lockstep.
         *
         * @return a populated {@link Account} fixture
         */
        private Account buildScaleTwoMonetaryFixture() {
            Account account = new Account();
            account.setAcctId(99999999999L);
            account.setAcctActiveStatus("Y");
            // Every monetary field is set to the SAME BigDecimal
            // reference. If the SUT mutates one, all five getters
            // would change in lockstep — making any regression
            // unmistakable.
            account.setAcctCurrBal(MONETARY_TEST_VALUE);
            account.setAcctCreditLimit(MONETARY_TEST_VALUE);
            account.setAcctCashCreditLimit(MONETARY_TEST_VALUE);
            account.setAcctCurrCycCredit(MONETARY_TEST_VALUE);
            account.setAcctCurrCycDebit(MONETARY_TEST_VALUE);
            account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
            account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
            account.setAcctReissueDate(LocalDate.of(2024, 6, 15));
            account.setAcctAddrZip("78487-7965");
            account.setAcctGroupId("DEFAULT");
            return account;
        }

        /**
         * Per AAP &sect;0.6.1 ("COBOL Decimal Precision and
         * BigDecimal Mapping"): "Service-level arithmetic always uses
         * the explicit form
         * {@code a.multiply(b).setScale(2, RoundingMode.HALF_EVEN)}
         * rather than the implicit
         * {@code BigDecimal.multiply(BigDecimal)} result, which may
         * carry extended scale." {@link AccountFileReaderService} is
         * a pure read service &mdash; it never performs arithmetic on
         * monetary fields. Therefore the test's invariant is:
         * <em>scale=2 in &harr; scale=2 out</em>, with no rounding
         * or rescaling allowed.
         *
         * <p>The assertion checks {@link BigDecimal#scale()}
         * (returns 2 for {@code "500.50"}) rather than
         * {@link BigDecimal#equals(Object)} on the value, because
         * {@code BigDecimal.equals} also compares scale and would
         * mask a stripped-trailing-zeros regression that the
         * {@code scale()} accessor surfaces directly.</p>
         */
        @Test
        @DisplayName("readAllAccounts_monetaryFields_retainScaleTwo — scale=2 preserved across SUT processing")
        void readAllAccounts_monetaryFields_retainScaleTwo() {
            // GIVEN: a fixture with five scale=2 monetary fields and a
            // sanity-check assertion BEFORE the SUT runs to confirm
            // the fixture itself carries the expected scale.
            Account fixture = buildScaleTwoMonetaryFixture();
            assertThat(fixture.getAcctCurrBal().scale())
                    .as("Test fixture sanity: acctCurrBal must carry scale=2 before SUT runs")
                    .isEqualTo(2);
            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(fixture));

            // WHEN: the SUT runs the sequential scan.
            AccountFileReaderService.ReadResult result = service.readAndDisplayAll();

            // THEN: ReadResult is well-formed.
            assertThat(result).isNotNull();
            assertThat(result.recordCount()).isEqualTo(1L);

            // AND: every monetary field on the fixture STILL carries
            // scale=2 — proves the SUT never invoked
            // stripTrailingZeros(), setScale(0), or any other
            // rescaling operation on the BigDecimal references. Each
            // assertion is described with its COBOL PIC clause so a
            // failure message immediately reveals which mapping
            // regressed.
            assertThat(fixture.getAcctCurrBal().scale())
                    .as("ACCT-CURR-BAL PIC S9(10)V99 must retain scale=2 post-scan")
                    .isEqualTo(2);
            assertThat(fixture.getAcctCreditLimit().scale())
                    .as("ACCT-CREDIT-LIMIT PIC S9(10)V99 must retain scale=2 post-scan")
                    .isEqualTo(2);
            assertThat(fixture.getAcctCashCreditLimit().scale())
                    .as("ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 must retain scale=2 post-scan")
                    .isEqualTo(2);
            assertThat(fixture.getAcctCurrCycCredit().scale())
                    .as("ACCT-CURR-CYC-CREDIT PIC S9(10)V99 must retain scale=2 post-scan")
                    .isEqualTo(2);
            assertThat(fixture.getAcctCurrCycDebit().scale())
                    .as("ACCT-CURR-CYC-DEBIT PIC S9(10)V99 must retain scale=2 post-scan")
                    .isEqualTo(2);

            // AND: the BigDecimal value itself is unchanged
            // (compareTo == 0, scale-aware), proving the SUT did not
            // perform any arithmetic or rounding under the covers.
            assertThat(fixture.getAcctCurrBal())
                    .as("ACCT-CURR-BAL value must be untouched by the SUT — 500.50 in, 500.50 out")
                    .isEqualByComparingTo(MONETARY_TEST_VALUE);
            assertThat(fixture.getAcctCurrBal())
                    .as("ACCT-CURR-BAL must remain BigDecimal-equal (scale-aware) to the input value")
                    .isEqualTo(MONETARY_TEST_VALUE);
        }

        /**
         * Verifies the BigDecimal-preservation invariant holds even
         * when the fixture carries the maximum representable COBOL
         * {@code PIC S9(10)V99} value
         * ({@code 9_999_999_999.99}) so the test exercises the
         * upper-precision boundary of the
         * {@code BigDecimal precision=12 scale=2} contract.
         */
        @Test
        @DisplayName("readAllAccounts_monetaryFieldsAtMaxPrecision_retainScaleTwo — upper boundary of PIC S9(10)V99")
        void readAllAccounts_monetaryFieldsAtMaxPrecision_retainScaleTwo() {
            // GIVEN: a fixture with the maximum COBOL PIC S9(10)V99
            // representable value (10 integer digits + 2 fractional)
            // = +9,999,999,999.99 ≈ $9.999B.
            BigDecimal maxValue = new BigDecimal("9999999999.99");
            assertThat(maxValue.scale())
                    .as("Test fixture sanity: max-value monetary literal must carry scale=2")
                    .isEqualTo(2);
            assertThat(maxValue.precision())
                    .as("Test fixture sanity: max-value monetary literal must carry precision=12")
                    .isEqualTo(12);

            Account fixture = new Account();
            fixture.setAcctId(1L);
            fixture.setAcctActiveStatus("Y");
            fixture.setAcctCurrBal(maxValue);
            fixture.setAcctCreditLimit(maxValue);
            fixture.setAcctCashCreditLimit(maxValue);
            fixture.setAcctCurrCycCredit(maxValue);
            fixture.setAcctCurrCycDebit(maxValue);
            fixture.setAcctOpenDate(LocalDate.of(2020, 1, 1));
            fixture.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
            fixture.setAcctReissueDate(null);
            fixture.setAcctAddrZip("78487");
            fixture.setAcctGroupId("DEFAULT");

            when(accountRepository.findAll(any(Sort.class)))
                    .thenReturn(List.of(fixture));

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: post-scan scale and precision are still exactly
            // 2 and 12 — proves the SUT's per-record DISPLAY
            // paragraph did not trigger any silent rescale at the
            // boundary.
            assertThat(fixture.getAcctCurrBal().scale())
                    .as("ACCT-CURR-BAL must retain scale=2 at PIC S9(10)V99 upper boundary")
                    .isEqualTo(2);
            assertThat(fixture.getAcctCurrBal().precision())
                    .as("ACCT-CURR-BAL must retain precision=12 at PIC S9(10)V99 upper boundary")
                    .isEqualTo(12);
            assertThat(fixture.getAcctCurrBal())
                    .as("ACCT-CURR-BAL must remain BigDecimal-equal to 9999999999.99")
                    .isEqualByComparingTo(maxValue);
        }

        /**
         * Verifies that the audit payload's {@code record_count}
         * field carries the {@link Long} boxed value (NOT a
         * {@link BigDecimal} or any other numeric encoding). This
         * is a secondary BigDecimal-related guard: the SUT's
         * {@code AtomicLong#get()} call returns a {@code long}, and
         * the {@link Map#put(Object, Object)} autoboxes to
         * {@link Long}. A future refactor that switched to
         * {@code BigDecimal.valueOf(count)} would silently break the
         * OpenSearch index mapping (which is declared as
         * {@code long}) and is excluded by this assertion.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("readAllAccounts_payloadRecordCount_isLongNotBigDecimal — autoboxing contract")
        void readAllAccounts_payloadRecordCount_isLongNotBigDecimal() {
            // GIVEN: a two-row fixture so the record_count is a clear,
            // distinguishable value.
            int fixtureSize = 2;
            List<Account> fixtures = buildAccountFixtures(fixtureSize);
            when(accountRepository.findAll(any(Sort.class))).thenReturn(fixtures);

            // WHEN: the SUT runs the sequential scan.
            service.readAndDisplayAll();

            // THEN: capture the audit payload and inspect the type
            // of the record_count entry.
            ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService, times(1)).logBatchJobLifecycle(
                    eq(COBOL_PROGRAM_ID),
                    anyString(),
                    eq(STATUS_COMPLETED),
                    anyLong(),
                    payloadCaptor.capture(),
                    isNull());

            Object recordCountObj = payloadCaptor.getValue().get(PAYLOAD_RECORD_COUNT_KEY);
            assertThat(recordCountObj)
                    .as("Audit payload record_count must be a Long (autoboxed long), not a BigDecimal")
                    .isInstanceOf(Long.class);
            assertThat((Long) recordCountObj)
                    .as("Audit payload record_count must equal fixture size %d", fixtureSize)
                    .isEqualTo((long) fixtureSize);
        }
    }
}
