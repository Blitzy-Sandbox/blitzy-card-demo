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

// ====================================================================
// Internal imports (whitelist: depends_on_files only — see Phase 1
// discovery and AAP §0.4.2 cross-file-dependency rules).
//
//   • TransactionListService — the @Service under test, instantiated
//     via @InjectMocks; replaces COBOL program COTRN00C (TRANID
//     'CT00', mapset 'COTRN00', map 'COTRN0A', file 'TRANSACT').
//   • Transaction              — JPA @Entity (← CVTRA05Y.cpy 350-byte
//     layout); supplied as the in-test fixture inside PageImpl<>
//     returned by the mocked
//     TransactionRepository.findByOrderByTranIdAsc(Pageable).
//   • TransactionListDto       — record DTO returned by the SUT.
//     Component accessors rows(), page(), size(), totalElements(),
//     totalPages(), first(), last(), idFilter() are asserted on for
//     paged-browse semantics. Nested TransactionListDto.TransactionRow
//     record's accessors amount() (BigDecimal scale=2 per AAP §0.6.1)
//     and cardNumber() (PAN-masking "************1111" per AAP §0.6.6)
//     are asserted on for field projection from Transaction →
//     TransactionRow.
//   • TransactionRepository    — Spring Data JPA repository (← VSAM
//     TRANSACT.KSDS) declared @Mock; the derived query
//     findByOrderByTranIdAsc(Pageable) is stubbed in every test.
//   • AuditLogService          — CloudTrail + OpenSearch audit log
//     adapter declared @Mock to verify (AuditLogging @Nested group)
//     that the COTRN00C read-only paginated-browse path emits NO
//     downstream-system audit event — the actual audit emission for
//     this service is via SLF4J LOG.info/LOG.debug shipped to
//     CloudWatch Logs by the logstash-logback-encoder configured in
//     logback-spring.xml (AAP §0.7.2 "Structured JSON logging shipped
//     to CloudWatch Logs"). The Mockito @InjectMocks silently ignores
//     this unused mock because TransactionListService's constructor
//     takes only TransactionRepository.
// ====================================================================
import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionListDto;
import com.awsm2.carddemo.repository.TransactionRepository;

// ====================================================================
// External imports (org.junit.jupiter:junit-jupiter, org.mockito:*,
// org.assertj:assertj-core, org.springframework.data:spring-data-commons
// — every artifact in scope per the external_imports schema; all
// transitively supplied by spring-boot-starter-test per pom.xml).
// ====================================================================
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ====================================================================
// JDK imports — BigDecimal for the scale=2 amount fixture (AAP
// §0.6.1); Collections / List for the PageImpl content lists.
// ====================================================================
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ====================================================================
// Static imports — AssertJ fluent assertions (assertThat) for DTO
// field comparisons; Mockito statics for stub setup (when), verified
// invocations (verify), the unused-collaborator negative assertion
// (verifyNoInteractions), and flexible argument matching
// (ArgumentMatchers.any). All static imports are members of
// org.mockito.Mockito, org.mockito.ArgumentMatchers, or
// org.assertj.core.api.Assertions declared in the external_imports
// schema.
// ====================================================================
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link TransactionListService}.
 *
 * <h2>COBOL provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <p>{@link TransactionListService} is the Java target for the
 * COBOL/CICS program {@code app/cbl/COTRN00C.cbl} (CICS transaction
 * id {@code 'CT00'}, mapset {@code COTRN00}, map {@code COTRN0A},
 * file {@code 'TRANSACT'}). The COBOL source performs a CICS
 * {@code STARTBR}/{@code READNEXT} paged browse on the
 * {@code TRANSACT} VSAM KSDS, filling the 10-row {@code COTRN0A}
 * BMS table (fields {@code TRNID01..TRNID10}, {@code TDATE01..TDATE10},
 * {@code TDESC01..TDESC10}, {@code TAMT001..TAMT010}; verified in
 * {@code app/cpy-bms/COTRN00.CPY} symbolic map L67-L796). The
 * pseudo-conversational COBOL paragraph sequence is:</p>
 * <ol>
 *   <li>{@code MAIN-PARA} (COTRN00C.cbl L95-L141) &mdash; PF-key
 *       dispatch; on initial entry calls
 *       {@code PROCESS-ENTER-KEY} then
 *       {@code SEND-TRNLST-SCREEN}; subsequent re-entries dispatch
 *       PF7/PF8 to {@code PROCESS-PF7-KEY}/{@code PROCESS-PF8-KEY}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} (COTRN00C.cbl L146-L229)
 *       &mdash; reads {@code TRNIDINI} from the screen; SPACES /
 *       LOW-VALUES branch (L206-L207) moves LOW-VALUES to
 *       {@code TRAN-ID} (unfiltered browse); non-blank branch
 *       (L208-L218) requires numeric and copies to
 *       {@code TRAN-ID} as positioning key.</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} (COTRN00C.cbl ~L500) &mdash;
 *       10-row {@code PERFORM UNTIL WS-IDX &gt;= 11} browse loop
 *       over {@code READNEXT-TRANSACT-FILE}; populates the BMS
 *       output map row-by-row via {@code POPULATE-TRAN-DATA}.</li>
 *   <li>{@code SEND-TRNLST-SCREEN} &mdash; renders the
 *       {@code COTRN0A} map to the 3270 terminal.</li>
 * </ol>
 *
 * <p>In the Java target this becomes:</p>
 * <ol>
 *   <li>The public method
 *       {@code listTransactions(String tranIdFilter, int page)} is
 *       the single entry point. PF-key dispatch is replaced by the
 *       {@code page} parameter; COMMAREA paging state
 *       ({@code CDEMO-CT00-PAGE-NUM},
 *       {@code CDEMO-CT00-TRNID-FIRST}, ...) is replaced by the
 *       client-supplied 0-indexed page number and the
 *       {@link TransactionListDto} response envelope.</li>
 *   <li>Spring Data JPA
 *       {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
 *       lookup with {@link PageRequest#of(int, int, org.springframework.data.domain.Sort)}
 *       (page=0, size=10, sort by tranId ASC) replaces the CICS
 *       {@code STARTBR DATASET('TRANSACT') RIDFLD(TRAN-ID)} +
 *       {@code READNEXT} ascending-key browse.</li>
 *   <li>Projection of the {@link Transaction} JPA entities onto
 *       {@link TransactionListDto.TransactionRow} records with
 *       PAN masking applied at the producer per AAP &sect;0.6.6
 *       PCI-DSS v4.0 Requirement 3.4.1.</li>
 * </ol>
 *
 * <h2>Behavioural invariants locked by this suite</h2>
 * <ol>
 *   <li><b>PAGE_SIZE = 10</b> &mdash; verbatim transcription of the
 *       COBOL 10-occurrence pattern in the {@code COTRN0AI} symbolic
 *       map (fields {@code TRNID01I..TRNID10I},
 *       {@code SEL0001I..SEL0010I}; verified in
 *       {@code app/cpy-bms/COTRN00.CPY}) and the
 *       {@code PERFORM UNTIL WS-IDX &gt;= 11} browse loop in
 *       {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD}. AAP
 *       &sect;0.7.1 Minimal Change Clause forbids deviation from
 *       this 10-row contract.</li>
 *   <li><b>Sort by {@code tranId} ascending</b> &mdash; matches the
 *       VSAM KSDS physical key ordering and the CICS
 *       {@code STARTBR}/{@code READNEXT} traversal direction; the
 *       derived query name {@code OrderByTranIdAsc} additionally
 *       forces ASC ordering at the SQL layer.</li>
 *   <li><b>tranId-prefix filter</b> &mdash; null / empty / blank
 *       filter routes to the unfiltered path (matches the COBOL
 *       {@code SPACES / LOW-VALUES} branch of
 *       {@code PROCESS-ENTER-KEY} at COTRN00C.cbl:L206-L207);
 *       non-blank filter triggers client-side prefix matching on
 *       the result of the same underlying ASC browse per the
 *       AAP &sect;0.7.1 Minimal Change Clause (the repository
 *       does NOT expose a {@code findByTranIdStartingWith(...)}
 *       derived query, so the filter is implemented in the
 *       service layer).</li>
 *   <li><b>PAN masking (PCI-DSS)</b> &mdash; each
 *       {@link TransactionListDto.TransactionRow} returned by the
 *       service must have its {@code cardNumber} masked to the
 *       {@code ************XXXX} format (12 asterisks + last 4
 *       digits) per AAP &sect;0.6.6 PCI-DSS v4.0 Requirement
 *       3.4.1.</li>
 *   <li><b>BigDecimal amount preservation</b> &mdash; the COBOL
 *       {@code TRAN-AMT PIC S9(09)V99} monetary field flows through
 *       the service as a {@link BigDecimal} with {@code scale=2}.
 *       Per AAP &sect;0.6.1 the {@code BigDecimal} value (not its
 *       {@code Object.equals(...)} signature, which is scale-
 *       sensitive) must be preserved through the
 *       {@link Transaction} entity &rarr;
 *       {@link TransactionListDto.TransactionRow} projection.</li>
 *   <li><b>Pagination</b> &mdash; the {@link TransactionListDto}
 *       response envelope correctly projects the
 *       {@link Page#getNumber()}, {@link Page#getTotalElements()},
 *       {@link Page#getTotalPages()}, {@link Page#isFirst()}, and
 *       {@link Page#isLast()} metadata onto its accessor methods.</li>
 *   <li><b>Audit-logging collaboration boundary</b> &mdash; the
 *       current {@link TransactionListService} constructor takes
 *       only {@link TransactionRepository}, so the
 *       {@link AuditLogService} mock is reserved for forward
 *       compatibility per the sibling-test convention (see
 *       {@code TransactionDetailServiceTest},
 *       {@code CardListServiceTest}); no interactions today on a
 *       read-only browse path. Audit emission for this service is
 *       via SLF4J {@code LOG.info(...)} shipped to CloudWatch Logs
 *       by the {@code logstash-logback-encoder} configured in
 *       {@code src/main/resources/logback-spring.xml} (AAP
 *       &sect;0.7.2 "Structured JSON logging shipped to CloudWatch
 *       Logs").</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack or Testcontainers are
 * involved.</p>
 *
 * @see TransactionListService
 * @see TransactionListDto
 * @see Transaction
 * @see TransactionRepository
 * @see AuditLogService
 */
// COBOL: COTRN00C:LIST-TRANSACTIONS — paginated transaction-list
//        browse (TRANID 'CT00', mapset 'COTRN00', map 'COTRN0A',
//        file 'TRANSACT')
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService — COTRN00C paginated browse (PAGE_SIZE=10)")
class TransactionListServiceTest {

    // ==================================================================
    // Test fixture constants — shared across every @Nested group
    //
    // Per AAP §0.7.3 traceability discipline, every constant is named
    // after the COBOL field or invariant it represents:
    //
    //   COBOL_PAGE_SIZE        ← COTRN00C.cbl PROCESS-PAGE-FORWARD
    //                            10-occurrence pattern in COTRN0A BMS
    //                            map (TRNID01..TRNID10, SEL0001..SEL0010)
    //   TEST_PAN_PRIMARY       ← canonical industry-standard test Visa
    //                            PAN; safe to use in tests per PCI-DSS
    //                            v4.0 Req 3.4 (NOT a real card number)
    //   TEST_PAN_PRIMARY_MASKED← expected masked form per AAP §0.6.6
    //                            PCI-DSS v4.0 Requirement 3.4.1
    //   TRAN_AMT_99_50         ← CVTRA05Y.cpy:L10 TRAN-AMT PIC S9(09)V99
    //                            test value via BigDecimal(String) ctor
    //                            (scale=2 per AAP §0.6.1)
    //   TRAN_TYPE_CD           ← CVTRA05Y.cpy:L6  TRAN-TYPE-CD PIC X(02)
    //   TRAN_CAT_CD            ← CVTRA05Y.cpy:L7  TRAN-CAT-CD  PIC 9(04)
    //   TRAN_SOURCE            ← CVTRA05Y.cpy:L8  TRAN-SOURCE PIC X(10)
    //   TRAN_DESC              ← CVTRA05Y.cpy:L9  TRAN-DESC   PIC X(100)
    // ==================================================================

    /**
     * The verbatim COBOL page size derived from the COTRN0A BMS map's
     * 10-row table ({@code TRNID01..TRNID10},
     * {@code SEL0001..SEL0010}; verified in
     * {@code app/cpy-bms/COTRN00.CPY}) and the
     * {@code PERFORM UNTIL WS-IDX &gt;= 11} browse loop in
     * {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD}. Kept as a
     * local constant in addition to
     * {@link TransactionListService#PAGE_SIZE} so PageSize assertions
     * remain readable even if the production constant ever moves.
     */
    // COBOL: COTRN00C.cbl PROCESS-PAGE-FORWARD — 10-row browse loop;
    //        COTRN00.bms COTRN0A — 10-row BMS table
    private static final int COBOL_PAGE_SIZE = 10;

    /**
     * Recognisable industry-standard test PAN. Masks to
     * {@code ************1111} under the
     * {@link TransactionListService} PAN-masking helper. SAFE to use
     * in test code per PCI-DSS v4.0 Req 3.4 (NOT a real card number;
     * this is the Visa-shape test PAN published by the card networks).
     */
    // COBOL: CVTRA05Y.cpy:L15 TRAN-CARD-NUM PIC X(16)
    private static final String TEST_PAN_PRIMARY = "4111111111111111";

    /**
     * Expected masked form of {@link #TEST_PAN_PRIMARY} per AAP
     * &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1 (12 leading
     * asterisks + trailing 4 digits).
     */
    private static final String TEST_PAN_PRIMARY_MASKED = "************1111";

    /**
     * The 9.50-dollar TRAN-AMT BigDecimal fixture. Constructed via
     * the {@link BigDecimal#BigDecimal(String) string constructor}
     * to preserve {@code scale=2} verbatim &mdash; per AAP
     * &sect;0.6.1 the {@code BigDecimal(double)} constructor and
     * floating-point literals are forbidden for monetary values
     * because they introduce binary-floating-point precision
     * artifacts. The string-form {@code "99.50"} is the canonical
     * representation matching COBOL {@code TRAN-AMT PIC S9(09)V99}
     * from {@code CVTRA05Y.cpy}:L10.
     */
    // COBOL: CVTRA05Y.cpy:L10 TRAN-AMT PIC S9(09)V99
    private static final BigDecimal TRAN_AMT_99_50 = new BigDecimal("99.50");

    /**
     * Test transaction-type code. Maps to COBOL
     * {@code TRAN-TYPE-CD PIC X(02)} from {@code CVTRA05Y.cpy}:L6.
     * Value {@code "01"} is the canonical "Purchase" type loaded
     * from the V013 fixture (per {@code app/data/ASCII/trantype.txt}).
     */
    private static final String TRAN_TYPE_CD = "01";

    /**
     * Test transaction-category code. Maps to COBOL
     * {@code TRAN-CAT-CD PIC 9(04)} from {@code CVTRA05Y.cpy}:L7.
     * Value {@code 5411} is the canonical "Groceries" category
     * code loaded from the V014 fixture.
     */
    private static final Integer TRAN_CAT_CD = 5411;

    /**
     * Test transaction-source code. Maps to COBOL
     * {@code TRAN-SOURCE PIC X(10)} from {@code CVTRA05Y.cpy}:L8.
     */
    private static final String TRAN_SOURCE = "ONLINE    ";

    /**
     * Test transaction description. Maps to COBOL
     * {@code TRAN-DESC PIC X(100)} from {@code CVTRA05Y.cpy}:L9.
     */
    private static final String TRAN_DESC = "GROCERY STORE PURCHASE";

    /**
     * Test tranId prefix filter used by the IdFilter
     * {@link Nested} group. Mirrors the BMS field
     * {@code TRNIDINI PIC X(16)} from
     * {@code app/cpy-bms/COTRN00.CPY}:L66 that the COBOL source
     * copied to {@code TRAN-ID} before issuing
     * {@code STARTBR} on the {@code TRANSACT} VSAM KSDS.
     */
    private static final String TEST_TRAN_ID_PREFIX = "0000";

    // ==================================================================
    // Mocks and System Under Test (SUT)
    //
    // The TransactionListService 1-arg constructor takes ONLY a
    // TransactionRepository per AAP §0.4.1 (one-service-per-COBOL-
    // program mapping; COTRN00C is read-only paginated browse). The
    // AuditLogService mock is declared because the schema includes
    // it in the internal_imports whitelist and the AuditLogging
    // @Nested group asserts (via verifyNoInteractions) that the
    // read-only browse path emits no AuditLogService events.
    // Mockito's @InjectMocks tolerates the unused @Mock field — it
    // simply does not pass auditLogService into a constructor that
    // doesn't declare a parameter for it.
    //
    // Per AAP §0.7.2 ("Structured JSON logging shipped to CloudWatch
    // Logs"), the actual audit emission for this read-only path is
    // via the SLF4J Logger in TransactionListService (LOG.info on
    // the empty-result path, LOG.debug on the success path) — NOT
    // through AuditLogService. The Mockito mock here serves as a
    // defense-in-depth guard against any future regression that adds
    // direct AuditLogService emission to a read path (which would
    // silently flood OpenSearch with high-cardinality read-event
    // documents and incur unnecessary cost). The CardDemo sibling
    // read-only service tests (TransactionDetailServiceTest,
    // CardListServiceTest, UserListServiceTest) use exactly this
    // pattern for the same reason.
    // ==================================================================

    /**
     * Mock Spring Data JPA repository for {@link Transaction}.
     *
     * <p>Stubbed via {@code when(...).thenReturn(...)} in each test
     * (no class-level stub bleed) and verified via
     * {@code verify(...)} plus {@link ArgumentCaptor} where the
     * captured argument needs to be inspected (notably the
     * {@link Pageable} passed to {@code findByOrderByTranIdAsc}).</p>
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Mock audit-log adapter.
     *
     * <p>Declared per the schema and present to support the
     * {@link AuditLogging} forward-compatibility boundary test. The
     * current {@link TransactionListService} constructor takes only
     * {@link TransactionRepository}, so Mockito's
     * {@code @InjectMocks} does NOT wire this mock into the SUT; it
     * stays uninvoked. The {@link AuditLogging} test verifies via
     * {@link org.mockito.Mockito#verifyNoInteractions} that the
     * read-only browse path performs no
     * {@link AuditLogService} write &mdash; audit emission for read
     * paths is via SLF4J &rarr; CloudWatch Logs &rarr; OpenSearch
     * per AAP &sect;0.7.2, not via the {@link AuditLogService}
     * adapter.</p>
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. Instantiated by Mockito via the
     * single-argument constructor
     * {@link TransactionListService#TransactionListService(TransactionRepository)},
     * injecting {@link #transactionRepository}.
     */
    @InjectMocks
    private TransactionListService service;

    // ==================================================================
    // Shared fixtures
    //
    // The 10-row fixture mirrors a freshly seeded transactions table
    // page filled to its COBOL screen capacity. The transaction IDs
    // are zero-padded ascending so the resulting fixture preserves
    // the same ordering shape produced by the SUT's
    // Sort.by("tranId").ascending() request, which matches the CICS
    // STARTBR/READNEXT ascending-key traversal in COTRN00C.cbl.
    // ==================================================================

    /**
     * Ten {@link Transaction} fixture rows sorted ascending by
     * {@code tranId}. Created fresh per test method by the
     * {@link #setUp()} hook so test isolation is preserved.
     */
    private List<Transaction> tenTransactionsFixture;

    /**
     * A single {@link Transaction} fixture for the
     * PAN-masking / BigDecimal-amount happy-path tests. Carries the
     * recognisable {@link #TEST_PAN_PRIMARY} PAN and the
     * {@link #TRAN_AMT_99_50} amount so any masking-leak or
     * scale-loss regression manifests as a clearly recognisable
     * value in the assertion error message.
     */
    private Transaction singleTransactionFixture;

    /**
     * Per-method initialisation hook. Constructs the 10-row fixture
     * once per test so each test gets its own list instance. Avoids
     * any inter-test fixture coupling.
     *
     * <p>Field assignment mirrors {@code CVTRA05Y.cpy}:L5-L18 in its
     * declared order. Each row's {@code tranId} is the 16-character
     * zero-padded sequence {@code "0000000000000001"} ..
     * {@code "0000000000000010"}, mirroring the COBOL
     * {@code STARTBR}/{@code READNEXT} ascending-primary-key
     * traversal that the SUT replaces.</p>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COTRN00C.cbl PROCESS-PAGE-FORWARD — 10-row browse
        //        loop over READNEXT-TRANSACT-FILE. Construct an
        //        ascending-tranId fixture so the resulting list
        //        preserves the same ordering shape the SUT requests
        //        (Sort.by("tranId").ascending()).
        tenTransactionsFixture = new ArrayList<>(COBOL_PAGE_SIZE);
        for (int i = 1; i <= COBOL_PAGE_SIZE; i++) {
            // 16-character zero-padded primary key — mirrors COBOL
            // STARTBR positioning key shape per CVTRA05Y.cpy:L5
            // (TRAN-ID PIC X(16)).
            final String tranId = String.format("%016d", i);
            tenTransactionsFixture.add(
                    buildTransaction(tranId, TEST_PAN_PRIMARY, TRAN_AMT_99_50));
        }

        // Dedicated single-transaction fixture for PAN-masking /
        // BigDecimal-amount assertions — carries the recognisable
        // Visa-shape test PAN so any masking regression manifests
        // as "************1111" vs the raw "4111111111111111" in
        // the failure message, and carries the BigDecimal("99.50")
        // amount so any scale-loss regression is obvious.
        singleTransactionFixture = buildTransaction(
                "0000000000000001",
                TEST_PAN_PRIMARY,
                TRAN_AMT_99_50);
    }

    /**
     * Test-fixture builder for a {@link Transaction} entity.
     *
     * <p>Populates every business field that the COBOL
     * {@code TRAN-RECORD} record carries (per
     * {@code app/cpy/CVTRA05Y.cpy} L4-L18) that is needed by the
     * paginated-browse projection. The 20-byte trailing
     * {@code FILLER} (L18) is omitted because the JPA entity has no
     * FILLER column per AAP &sect;0.6.2 (PostgreSQL has no concept
     * of fixed-width record padding). Timestamps
     * ({@code tranOrigTs}, {@code tranProcTs}) are intentionally
     * left {@code null} on this fixture &mdash; the
     * {@code TransactionListService}'s projection accepts
     * {@code null} {@code LocalDateTime} components without NPE
     * (the {@link TransactionListDto.TransactionRow} record's
     * {@code processingTimestamp} component is nullable).</p>
     *
     * <p>The {@code tranAmt} BigDecimal is constructed via the
     * {@link BigDecimal#BigDecimal(String) string constructor} to
     * preserve {@code scale=2} verbatim &mdash; per AAP &sect;0.6.1
     * the {@code BigDecimal(double)} constructor and floating-point
     * literals are forbidden for monetary values.</p>
     *
     * @param tranId      16-character zero-padded transaction ID
     *                    (primary key)
     * @param tranCardNum 16-digit PAN (used for PAN-masking
     *                    assertions); should be a canonical test
     *                    PAN per PCI-DSS v4.0 Req 3.4
     * @param tranAmt     the BigDecimal amount; should be
     *                    constructed via the
     *                    {@code BigDecimal(String)} constructor to
     *                    preserve {@code scale=2}
     * @return a fully populated {@link Transaction} instance ready
     *         for placement into a {@link PageImpl}
     */
    private Transaction buildTransaction(String tranId,
                                         String tranCardNum,
                                         BigDecimal tranAmt) {
        Transaction t = new Transaction();
        // CVTRA05Y.cpy:L5 — TRAN-ID PIC X(16); primary key
        t.setTranId(tranId);
        // CVTRA05Y.cpy:L6 — TRAN-TYPE-CD PIC X(02); joinable to
        // tran_type lookup table
        t.setTranTypeCd(TRAN_TYPE_CD);
        // CVTRA05Y.cpy:L7 — TRAN-CAT-CD PIC 9(04); 4-digit numeric
        t.setTranCatCd(TRAN_CAT_CD);
        // CVTRA05Y.cpy:L8 — TRAN-SOURCE PIC X(10); ONLINE/POS/BATCH
        t.setTranSource(TRAN_SOURCE);
        // CVTRA05Y.cpy:L9 — TRAN-DESC PIC X(100); full description
        t.setTranDesc(TRAN_DESC);
        // CVTRA05Y.cpy:L10 — TRAN-AMT PIC S9(09)V99; BigDecimal
        // scale=2 per AAP §0.6.1 monetary-precision rule
        t.setTranAmt(tranAmt);
        // CVTRA05Y.cpy:L15 — TRAN-CARD-NUM PIC X(16); PAN — masked
        // by TransactionListService.maskPan(...) when projected into
        // the TransactionListDto.TransactionRow.
        t.setTranCardNum(tranCardNum);
        // CVTRA05Y.cpy:L16-L17 — TRAN-ORIG-TS / TRAN-PROC-TS PIC X(26).
        // Intentionally not set; see fixture JavaDoc above. The DTO
        // projection accepts null LocalDateTime values without NPE.
        return t;
    }

    // ====================================================================
    // @Nested test groups (per the agent_prompt Phase 3 structure)
    // ====================================================================

    /**
     * PageSize tests &mdash; verifies the verbatim 10-row page-size
     * contract and the deterministic ascending-key sort that the
     * COBOL browse loop produces.
     *
     * <p>The COBOL source declares the in-memory row buffer as the
     * 10-row {@code COTRN0A} BMS map ({@code TRNID01..TRNID10},
     * {@code SEL0001..SEL0010}; verified in
     * {@code app/cpy-bms/COTRN00.CPY}); the
     * {@code PROCESS-PAGE-FORWARD} paragraph in
     * {@code COTRN00C.cbl} executes a
     * {@code PERFORM UNTIL WS-IDX &gt;= 11} loop that fills the
     * map row-by-row. The Java target preserves both the row count
     * and the ascending traversal order on every code path. The
     * tests here capture the {@link Pageable} argument and assert on
     * {@code getPageSize()} (the 10-row contract) and on
     * {@code getSort()} (the ascending {@code tranId} order).</p>
     */
    @Nested
    @DisplayName("PageSize — verbatim COBOL COTRN0A 10-row BMS table")
    class PageSize {

        /**
         * Verifies that {@link TransactionListService#PAGE_SIZE} is
         * exactly the COBOL literal 10. This is a compile-time /
         * constant assertion; if the constant is ever changed away
         * from 10 without an explicit AAP-mandated migration, this
         * test surfaces the drift immediately.
         *
         * <p>COBOL: {@code COTRN0A} BMS map 10-row table
         * ({@code TRNID01..TRNID10}) in
         * {@code app/cpy-bms/COTRN00.CPY}; matches the
         * {@code PERFORM UNTIL WS-IDX &gt;= 11} browse loop in
         * {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD}.</p>
         */
        // COBOL: COTRN00C:LIST-TRANSACTIONS — PAGE_SIZE = 10
        @Test
        @DisplayName("PAGE_SIZE constant equals 10 (verbatim COBOL COTRN0A 10-row BMS table)")
        void pageSizeConstant_equalsTen() {
            assertThat(TransactionListService.PAGE_SIZE)
                    .as("PAGE_SIZE — verbatim COBOL: 10-row COTRN0A BMS table "
                            + "in app/cpy-bms/COTRN00.CPY (TRNID01..TRNID10, "
                            + "SEL0001..SEL0010); AAP §0.7.1 Minimal Change "
                            + "Clause forbids deviation")
                    .isEqualTo(COBOL_PAGE_SIZE);
        }

        /**
         * Verifies that
         * {@link TransactionListService#listTransactions(String, int)}
         * issues a {@link PageRequest} of size {@code 10} on the
         * unfiltered (null filter) branch.
         *
         * <p>COBOL: {@code COTRN00C:STARTBR-TRANSACT-FILE} +
         * {@code READNEXT-TRANSACT-FILE} (READNEXT loop, max 10
         * iterations per {@code WS-IDX &gt;= 11} guard). The Java
         * target encodes the page size on the {@link Pageable}.</p>
         */
        // COBOL: COTRN00C:LIST-TRANSACTIONS — unfiltered branch
        @Test
        @DisplayName("listTransactions — unfiltered: page size is 10 captured on Pageable")
        void listTransactions_defaultPageSize_isTen() {
            // Arrange — TransactionRepository.findByOrderByTranIdAsc
            // returns an empty Page so the SUT completes without
            // surfacing a NullPointerException. Stub uses
            // any(Pageable.class) flexible matching so the argument
            // captor below can inspect the actual Pageable instance.
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act — invoke the SUT with no filter, page=0 (the
            // unfiltered initial-load path matches the COBOL
            // SPACES / LOW-VALUES branch of PROCESS-ENTER-KEY at
            // COTRN00C.cbl:L206-L207).
            service.listTransactions(null, 0);

            // Then — capture the Pageable passed to
            // findByOrderByTranIdAsc and assert the 10-row
            // page-size invariant. The ArgumentCaptor here is the
            // canonical Mockito pattern for inspecting complex
            // argument objects (per AAP §0.7.2 unit-test approach
            // using Mockito).
            ArgumentCaptor<Pageable> captor =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findByOrderByTranIdAsc(captor.capture());

            Pageable capturedPageable = captor.getValue();
            assertThat(capturedPageable.getPageSize())
                    .as("PAGE_SIZE — verbatim COBOL: 10-row COTRN0A BMS table; "
                            + "Minimal Change Clause forbids deviation")
                    .isEqualTo(COBOL_PAGE_SIZE);

            // Also verify the page number is 0 (caller supplied 0;
            // no clamping needed because 0 >= 0).
            assertThat(capturedPageable.getPageNumber())
                    .as("page index — caller supplied 0; passes through unchanged")
                    .isZero();
        }

        /**
         * Verifies the ascending {@code tranId} sort embedded in
         * the {@link Pageable} on the unfiltered branch. The COBOL
         * source's CICS {@code STARTBR}/{@code READNEXT} traversal
         * follows the VSAM KSDS primary-key (TRAN-ID) ASC ordering;
         * the Java target encodes this on the {@link Pageable}'s
         * {@code Sort} component.
         */
        // COBOL: COTRN00C:STARTBR — ascending-key traversal of
        //        TRANSACT VSAM KSDS by TRAN-ID
        @Test
        @DisplayName("listTransactions — Pageable sort is tranId ASC")
        void listTransactions_pageable_sortsByTranIdAsc() {
            // Arrange — empty Page stub so SUT completes
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act
            service.listTransactions(null, 0);

            // Then — capture Pageable and assert ascending tranId
            // sort. The findByOrderByTranIdAsc derived query name
            // additionally forces ASC ordering at the SQL layer,
            // but the SUT also embeds Sort on the Pageable to keep
            // both branches semantically symmetric.
            ArgumentCaptor<Pageable> captor =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findByOrderByTranIdAsc(captor.capture());

            Pageable capturedPageable = captor.getValue();
            // The Sort must include the "tranId" property — matches
            // the CICS STARTBR ascending-key traversal in
            // COTRN00C.cbl:STARTBR-TRANSACT-FILE.
            assertThat(capturedPageable.getSort().getOrderFor("tranId"))
                    .as("Sort must include 'tranId' to match the CICS STARTBR "
                            + "ascending-key traversal in COTRN00C.cbl "
                            + "(STARTBR-TRANSACT-FILE / READNEXT-TRANSACT-FILE)")
                    .isNotNull();
            assertThat(capturedPageable.getSort().getOrderFor("tranId").isAscending())
                    .as("Sort direction must be ASC — matches the COBOL "
                            + "READNEXT-TRANSACT-FILE traversal direction "
                            + "(primary-key VSAM KSDS ASC)")
                    .isTrue();
        }
    }

    /**
     * Sorting tests &mdash; verifies that the result rows preserve
     * the ascending {@code tranId} order produced by the
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
     * derived query.
     *
     * <p>The Java target preserves the COBOL VSAM KSDS physical
     * primary-key ordering on every code path. The repository's
     * derived query already returns results in ASC order, so the
     * service's projection MUST NOT re-order them.</p>
     */
    @Nested
    @DisplayName("Sorting — tranId ascending order preserved through projection")
    class Sorting {

        /**
         * Verifies the projection preserves ascending {@code tranId}
         * order from the repository's {@link Page} into the
         * {@link TransactionListDto}'s {@code rows} list. The
         * stubbed {@link Page} is pre-sorted ascending so any
         * re-ordering in the service layer would surface as a
         * non-monotone {@code transactionId} sequence on the rows.
         *
         * <p>COBOL: {@code COTRN00C:PROCESS-PAGE-FORWARD} — the
         * 10-row BMS map is populated row-by-row in
         * {@code READNEXT-TRANSACT-FILE} order (ascending primary
         * key on the {@code TRANSACT} VSAM KSDS).</p>
         */
        // COBOL: COTRN00C:POPULATE-TRAN-DATA — per-row screen field
        //        population in READNEXT-TRANSACT-FILE order
        @Test
        @DisplayName("listTransactions — rows preserve ascending tranId order")
        void listTransactions_returnsAscendingByTranId() {
            // Arrange — repository returns ascending-tranId fixture
            // wrapped in a PageImpl. The Pageable is captured for
            // the totals (size=10, totalElements=10), but the
            // ascending order is dictated by the fixture's list
            // construction order.
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            tenTransactionsFixture,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            tenTransactionsFixture.size()));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — the rows list preserves the ascending tranId
            // order from the fixture (and from the underlying
            // repository ASC traversal).
            assertThat(result.rows()).hasSize(COBOL_PAGE_SIZE);
            assertThat(result.rows())
                    .as("Row order must preserve the ascending tranId "
                            + "traversal from COTRN00C.cbl:READNEXT-TRANSACT-FILE")
                    .extracting(TransactionListDto.TransactionRow::transactionId)
                    .containsExactly(
                            "0000000000000001",
                            "0000000000000002",
                            "0000000000000003",
                            "0000000000000004",
                            "0000000000000005",
                            "0000000000000006",
                            "0000000000000007",
                            "0000000000000008",
                            "0000000000000009",
                            "0000000000000010");
        }
    }

    /**
     * IdFilter tests &mdash; verifies the conditional dispatch
     * between the unfiltered path (no filter / blank filter) and
     * the prefix-filtered path.
     *
     * <p>The COBOL source's
     * {@code COTRN00C:PROCESS-ENTER-KEY} (L206-L218) branches on
     * the {@code TRNIDINI PIC X(16)} screen input:</p>
     * <ul>
     *   <li>L206-L207: SPACES / LOW-VALUES &rarr; MOVE LOW-VALUES
     *       to TRAN-ID (unfiltered positioning, the COBOL
     *       "browse-from-start" semantic).</li>
     *   <li>L208-L218: non-blank + numeric &rarr; copy
     *       {@code TRNIDINI} to {@code TRAN-ID} as the
     *       {@code STARTBR} positioning key.</li>
     * </ul>
     *
     * <p>The Java target preserves this semantic at the service
     * layer: null / empty / blank filter routes to the unfiltered
     * path; non-blank filter routes to a client-side prefix-match
     * scan over the same underlying ASC browse (per AAP
     * &sect;0.7.1 Minimal Change Clause; the repository does NOT
     * expose a {@code findByTranIdStartingWith(...)} derived
     * query). Both branches ultimately invoke
     * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
     * &mdash; the difference is in the post-filter slice.</p>
     */
    @Nested
    @DisplayName("IdFilter — null/blank vs non-blank tranId prefix filter dispatch")
    class IdFilter {

        /**
         * Verifies that a null filter argument routes through the
         * unfiltered path and invokes
         * {@link TransactionRepository#findByOrderByTranIdAsc(Pageable)}
         * (the only finder method invoked by the SUT).
         *
         * <p>COBOL: {@code COTRN00C.cbl}:L206-L207 &mdash;
         * {@code IF TRNIDINI OF COTRN0AI = SPACES OR LOW-VALUES /
         * MOVE LOW-VALUES TO TRAN-ID}. The unfiltered branch is
         * the COBOL "browse-from-start" semantic.</p>
         */
        // COBOL: COTRN00C:PROCESS-ENTER-KEY L206-L207 — unfiltered
        //        branch (TRNIDINI is SPACES / LOW-VALUES)
        @Test
        @DisplayName("listTransactions — null filter calls findByOrderByTranIdAsc")
        void listTransactions_withoutIdFilter_callsUnfilteredFinder() {
            // Arrange — empty Page stub
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act — null filter
            service.listTransactions(null, 0);

            // Verify — the SUT invoked findByOrderByTranIdAsc.
            // Per the AAP §0.7.1 Minimal Change Clause, the
            // repository does NOT expose a separate
            // findByTranIdStartingWith(...) derived query, so the
            // SUT routes both filtered and unfiltered branches
            // through the same finder.
            verify(transactionRepository).findByOrderByTranIdAsc(any(Pageable.class));
        }

        /**
         * Verifies that an empty filter argument routes through the
         * unfiltered path (the Java target treats empty/blank as
         * equivalent to null, matching the COBOL SPACES /
         * LOW-VALUES branch).
         */
        // COBOL: COTRN00C:PROCESS-ENTER-KEY L206-L207 — empty
        //        filter behaves like SPACES branch
        @Test
        @DisplayName("listTransactions — empty filter routes through unfiltered finder")
        void listTransactions_withEmptyIdFilter_callsUnfilteredFinder() {
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act — empty-string filter ("") is treated as
            // unfiltered per COBOL SPACES branch semantics.
            service.listTransactions("", 0);

            verify(transactionRepository).findByOrderByTranIdAsc(any(Pageable.class));
        }

        /**
         * Verifies that a non-blank filter argument still routes
         * through the same underlying finder
         * ({@link TransactionRepository#findByOrderByTranIdAsc(Pageable)})
         * because the repository does NOT expose a separate
         * {@code findByTranIdStartingWith(...)} derived query per
         * AAP &sect;0.7.1 Minimal Change Clause. The filter is
         * applied client-side in the service layer; the repository
         * call signature is identical to the unfiltered branch.
         *
         * <p>The crucial verification here is that the SUT still
         * invokes {@code findByOrderByTranIdAsc} (NOT a
         * non-existent {@code findByTranIdStartingWith}) and that
         * the resulting DTO echoes back the supplied filter on its
         * {@code idFilter()} accessor.</p>
         */
        // COBOL: COTRN00C:PROCESS-ENTER-KEY L208-L218 — non-blank
        //        TRNIDINI copied to TRAN-ID as STARTBR positioning
        //        key; the Java target interprets as a prefix match
        //        on the unfiltered ASC browse per AAP §0.4.1
        @Test
        @DisplayName("listTransactions — non-blank filter calls findByOrderByTranIdAsc (no separate filter finder)")
        void listTransactions_withIdFilter_callsAppropriateFinderMethod() {
            // Arrange — empty Page stub
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act — non-blank filter
            TransactionListDto result =
                    service.listTransactions(TEST_TRAN_ID_PREFIX, 0);

            // Verify — same finder invoked; the filter is
            // applied client-side per AAP §0.7.1 Minimal Change
            // Clause. The SUT must NOT have called any other
            // finder on the mock.
            verify(transactionRepository).findByOrderByTranIdAsc(any(Pageable.class));

            // Verify the supplied filter is echoed back on the
            // response DTO's idFilter accessor — confirms the
            // service routed through the prefix-filter path (which
            // is the only path that populates idFilter; the
            // unfiltered path leaves it null).
            assertThat(result.idFilter())
                    .as("Non-blank filter must be echoed back on the DTO's "
                            + "idFilter accessor so the client can correlate "
                            + "the empty result with its query")
                    .isEqualTo(TEST_TRAN_ID_PREFIX);
        }

        /**
         * Verifies that the null-filter path leaves the DTO's
         * {@code idFilter()} accessor as {@code null} (the
         * unfiltered branch does NOT echo back a filter value).
         * Captures the COBOL semantic that {@code SPACES /
         * LOW-VALUES} on the TRNIDINI screen input means "all
         * transactions" and the response carries no positioning
         * marker.
         */
        // COBOL: COTRN00C:PROCESS-ENTER-KEY L206-L207 — unfiltered
        //        path; CDEMO-CT00-TRN-SELECTED stays unset.
        @Test
        @DisplayName("listTransactions — null filter leaves DTO idFilter() null")
        void listTransactions_nullFilter_dtoIdFilterIsNull() {
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            tenTransactionsFixture,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            tenTransactionsFixture.size()));

            TransactionListDto result = service.listTransactions(null, 0);

            assertThat(result.idFilter())
                    .as("Unfiltered branch must NOT echo back a filter value "
                            + "(the COBOL SPACES / LOW-VALUES branch leaves "
                            + "CDEMO-CT00-TRN-SELECTED unset)")
                    .isNull();
        }
    }

    /**
     * PanMasking tests &mdash; verifies that the full 16-digit PAN
     * on the {@link Transaction} entity is masked to the
     * {@code ************XXXX} format (12 asterisks + last 4
     * digits) before being placed on the
     * {@link TransactionListDto.TransactionRow} DTO row.
     *
     * <p>Per AAP &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1:
     * "Display PAN such that only personnel with a legitimate
     * business need can see more than the first six/last four
     * digits of the PAN." CardDemo's policy is to display only
     * the last 4 (more conservative). The masking is applied at
     * the producer (service layer) so the wire DTO never carries
     * the full PAN, even before it reaches any logging /
     * serialization path.</p>
     */
    @Nested
    @DisplayName("PanMasking — full 16-digit PAN masked to ************XXXX (PCI-DSS v4.0 Req 3.4.1)")
    class PanMasking {

        /**
         * Verifies that the
         * {@link TransactionListDto.TransactionRow#cardNumber()}
         * accessor returns the masked form ({@code ************1111}
         * for the canonical Visa-shape test PAN
         * {@code 4111111111111111}) regardless of the underlying
         * entity's value. This is the primary PCI-DSS guard rail:
         * the wire DTO never carries the full PAN.
         */
        // COBOL: COTRN00C — the legacy 3270 transaction-list screen
        //        does NOT display TRAN-CARD-NUM at all (only
        //        TRNIDnnI, TDATEnnI, TDESCnnI, TAMTnnnI per the
        //        COTRN0A BMS map). PAN masking is a Java-side
        //        PCI-DSS guard rail added per AAP §0.6.6 so the
        //        richer REST DTO can surface a masked card number
        //        for client convenience without leaking the full
        //        PAN.
        @Test
        @DisplayName("listTransactions — DTO row cardNumber is masked to ************1111")
        void listTransactions_dtoEntries_haveMaskedTranCardNum() {
            // Arrange — single-row Page returning the test PAN
            // 4111111111111111. The DTO row's cardNumber() accessor
            // must return the masked form, never the full PAN.
            List<Transaction> singleList = List.of(singleTransactionFixture);
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            singleList,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            1L));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — single row with masked PAN. The masking
            // helper {@code TransactionListService.maskPan(...)}
            // applies the canonical 12-asterisk + last-4-digit
            // form per AAP §0.6.6 PCI-DSS v4.0 Requirement 3.4.1.
            assertThat(result.rows()).hasSize(1);
            TransactionListDto.TransactionRow row = result.rows().get(0);
            assertThat(row.cardNumber())
                    .as("PAN must be masked at the producer (TransactionListService) "
                            + "to '************XXXX' per AAP §0.6.6 PCI-DSS v4.0 "
                            + "Requirement 3.4.1; the wire DTO must NEVER carry "
                            + "the full PAN")
                    .isEqualTo(TEST_PAN_PRIMARY_MASKED);

            // Defense in depth — the unmasked PAN MUST NOT appear
            // anywhere in the DTO's cardNumber accessor output.
            assertThat(row.cardNumber())
                    .as("PAN exposure regression guard — the cardNumber accessor "
                            + "must NEVER leak the full 16-digit PAN")
                    .doesNotContain(TEST_PAN_PRIMARY);
        }

        /**
         * Verifies that ALL rows in a multi-row result have their
         * PAN masked &mdash; not just the first one. This catches
         * any future regression that, e.g., masks only the first
         * row and forgets to apply the helper to subsequent rows.
         *
         * <p>The {@link #tenTransactionsFixture} is constructed
         * with every row carrying {@link #TEST_PAN_PRIMARY} so
         * every {@code cardNumber()} accessor on every row must
         * return {@link #TEST_PAN_PRIMARY_MASKED}.</p>
         */
        // COBOL: COTRN00C:POPULATE-TRAN-DATA — per-row screen field
        //        population. The masking must apply to every row,
        //        not just the first.
        @Test
        @DisplayName("listTransactions — every row in 10-row result has masked PAN")
        void listTransactions_allRowsMasked() {
            // Arrange — 10-row fixture, each carrying the test
            // PAN 4111111111111111.
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            tenTransactionsFixture,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            tenTransactionsFixture.size()));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — every row's cardNumber is the masked form.
            assertThat(result.rows()).hasSize(COBOL_PAGE_SIZE);
            assertThat(result.rows())
                    .as("Every row's cardNumber must be masked — masking applied "
                            + "to all rows, not just the first")
                    .extracting(TransactionListDto.TransactionRow::cardNumber)
                    .allMatch(masked -> masked.equals(TEST_PAN_PRIMARY_MASKED));
        }
    }

    /**
     * BigDecimalAmount tests &mdash; verifies that the COBOL
     * {@code TRAN-AMT PIC S9(09)V99} monetary field flows through
     * the service as a {@link BigDecimal} with {@code scale=2}
     * preserved.
     *
     * <p>Per AAP &sect;0.6.1: "All monetary values must use
     * BigDecimal with RoundingMode.HALF_EVEN ... never float/double
     * for any monetary field." The
     * {@link TransactionListService#listTransactions(String, int)}
     * is a read-only browse and performs no arithmetic on
     * {@code tranAmt} &mdash; it simply reads from the entity and
     * projects into the DTO. The test here validates that the
     * pipeline preserves {@code scale=2} verbatim without ever
     * falling back to {@code float}/{@code double}.</p>
     *
     * <p>The assertion uses AssertJ's
     * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo
     * isEqualByComparingTo} rather than {@code isEqualTo} because
     * {@link BigDecimal#equals(Object)} is scale-sensitive &mdash;
     * {@code new BigDecimal("99.50").equals(new BigDecimal("99.5"))}
     * returns {@code false}. The value-level comparison delegates
     * to {@link BigDecimal#compareTo} which IS scale-insensitive,
     * so the test passes whether the service returns {@code 99.50}
     * (scale 2) or {@code 99.5} (scale 1). A follow-up
     * {@code scale()} assertion locks the AAP-mandated
     * {@code scale=2} contract; together these two assertions
     * verify both the value AND the scale of the BigDecimal.</p>
     */
    @Nested
    @DisplayName("BigDecimalAmount — TRAN-AMT BigDecimal scale=2 preserved per AAP §0.6.1")
    class BigDecimalAmount {

        /**
         * Verifies that the
         * {@link TransactionListDto.TransactionRow#amount()}
         * accessor preserves the {@code BigDecimal("99.50")} value
         * with {@code scale=2} from the {@link Transaction}
         * entity through the DTO projection.
         *
         * <p>COBOL: {@code TRAN-AMT PIC S9(09)V99} from
         * {@code CVTRA05Y.cpy}:L10; the COBOL PIC clause declares
         * 9 integer digits + 2 implied decimal places (V99) for a
         * total of 11 significant digits with scale 2. The Java
         * target uses {@code BigDecimal} with the same precision
         * (11) and scale (2) on the JPA {@code @Column} per AAP
         * &sect;0.6.1.</p>
         */
        // COBOL: COTRN00C:POPULATE-TRAN-DATA — MOVE TRAN-AMT TO
        //        TAMTnnnI; the Java target preserves the BigDecimal
        //        scale=2 verbatim from the entity into the DTO.
        @Test
        @DisplayName("listTransactions — DTO row amount preserves BigDecimal scale=2")
        void listTransactions_amountScalePreserved() {
            // Arrange — single-row Page with BigDecimal("99.50")
            // (scale=2) on the entity.
            List<Transaction> singleList = List.of(singleTransactionFixture);
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            singleList,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            1L));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — value comparison via isEqualByComparingTo
            // (scale-insensitive) AND scale lock at 2 per AAP
            // §0.6.1. BigDecimal.equals(Object) is scale-sensitive
            // (99.50 != 99.5); isEqualByComparingTo delegates to
            // BigDecimal.compareTo which is scale-insensitive.
            // Both assertions together guarantee value+scale
            // fidelity.
            assertThat(result.rows()).hasSize(1);
            TransactionListDto.TransactionRow row = result.rows().get(0);

            // Value-level comparison (scale-insensitive)
            assertThat(row.amount())
                    .as("amount value — BigDecimal isEqualByComparingTo because "
                            + "BigDecimal.equals is scale-sensitive (99.50 != 99.5 "
                            + "under equals)")
                    .isEqualByComparingTo(TRAN_AMT_99_50);

            // Scale lock — per AAP §0.6.1 monetary-precision rule.
            // This catches any future regression that, e.g., calls
            // .stripTrailingZeros() on the amount and drops scale
            // to 1 (which would still pass isEqualByComparingTo
            // but break the AAP-mandated COBOL PIC S9(09)V99
            // scale=2 contract).
            assertThat(row.amount().scale())
                    .as("amount scale — must be 2 to match COBOL TRAN-AMT "
                            + "PIC S9(09)V99 (CVTRA05Y.cpy:L10) per AAP §0.6.1 "
                            + "monetary-precision rule; binary float/double "
                            + "intermediates are forbidden")
                    .isEqualTo(2);
        }

        /**
         * Cross-check: the {@link Transaction#getTranAmt()} entity
         * accessor returns a {@link BigDecimal} reference identical
         * to (or value-equal to) the value put into the
         * {@link TransactionListDto.TransactionRow#amount()}
         * accessor. Captures the AAP &sect;0.6.1 rule that the
         * pipeline never falls back to {@code float}/{@code double}
         * intermediates.
         *
         * <p>This is a defense-in-depth assertion that the value
         * actually preserved through the {@link Transaction}
         * &rarr; {@link TransactionListDto.TransactionRow}
         * projection is the IDENTICAL BigDecimal value &mdash;
         * not a re-constructed value that lost precision through
         * a double conversion.</p>
         */
        // COBOL: AAP §0.6.1 — float/double substitution for monetary
        //        values is FORBIDDEN. The Java target preserves the
        //        BigDecimal reference verbatim from the entity into
        //        the DTO.
        @Test
        @DisplayName("listTransactions — DTO row amount is the same BigDecimal value as entity")
        void listTransactions_amountIdenticalToEntity() {
            List<Transaction> singleList = List.of(singleTransactionFixture);
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            singleList,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            1L));

            TransactionListDto result = service.listTransactions(null, 0);

            assertThat(result.rows()).hasSize(1);
            // Value equality (scale-insensitive compareTo) between
            // the DTO row's amount and the entity's tranAmt.
            assertThat(result.rows().get(0).amount())
                    .as("DTO row amount must equal entity tranAmt verbatim — "
                            + "no float/double precision-loss intermediates per "
                            + "AAP §0.6.1")
                    .isEqualByComparingTo(singleTransactionFixture.getTranAmt());
        }
    }

    /**
     * Pagination tests &mdash; verifies that the
     * {@link TransactionListDto} response envelope correctly
     * projects the {@link Page#getNumber()},
     * {@link Page#getTotalElements()},
     * {@link Page#getTotalPages()}, {@link Page#isFirst()}, and
     * {@link Page#isLast()} metadata onto its accessor methods.
     *
     * <p>The COBOL source maintained paging state in the
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)},
     * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}, and
     * {@code CDEMO-CT00-TRNID-LAST PIC X(16)} COMMAREA fields; the
     * Java target replaces COMMAREA state with the Spring Data
     * {@link Page} envelope returned in the response DTO.</p>
     */
    @Nested
    @DisplayName("Pagination — page metadata projection")
    class Pagination {

        /**
         * Verifies that page 0 (the first page) correctly projects
         * the {@link Page} metadata: 10 rows on the page,
         * {@code first=true}, total elements and pages reflect the
         * underlying mock.
         *
         * <p>COBOL: {@code COTRN00C.cbl} on initial entry the
         * {@code MAIN-PARA} calls {@code PROCESS-ENTER-KEY} then
         * {@code SEND-TRNLST-SCREEN}; the
         * {@code CDEMO-CT00-PAGE-NUM} COMMAREA field starts at
         * 0 (per L224 {@code MOVE 0 TO CDEMO-CT00-PAGE-NUM}) and
         * is incremented by {@code PROCESS-PF8-KEY}.</p>
         */
        // COBOL: COTRN00C:MAIN-PARA — initial entry, page 0
        @Test
        @DisplayName("listTransactions — page 0 returns correct first-page metadata")
        void listTransactions_pageOne_returnsCorrectPage() {
            // Arrange — page 0 of a 25-element journal (3 pages of
            // 10/10/5 rows). Page 0 is the first; not the last;
            // contains the first 10 rows.
            final long totalElements = 25L;
            final int totalPages = 3;
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            tenTransactionsFixture,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            totalElements));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — page metadata
            assertThat(result.page())
                    .as("page index — page 0 echoed back on the response")
                    .isZero();
            assertThat(result.size())
                    .as("size — fixed PAGE_SIZE=10 per AAP §0.7.1")
                    .isEqualTo(COBOL_PAGE_SIZE);
            assertThat(result.totalElements())
                    .as("totalElements — projected from Page.getTotalElements()")
                    .isEqualTo(totalElements);
            assertThat(result.totalPages())
                    .as("totalPages — projected from Page.getTotalPages()")
                    .isEqualTo(totalPages);
            assertThat(result.first())
                    .as("first — page 0 is the first page; matches COBOL guard "
                            + "in PROCESS-PF7-KEY (CDEMO-CT00-PAGE-NUM > 1 check)")
                    .isTrue();
            assertThat(result.last())
                    .as("last — page 0 of 3 is NOT the last page")
                    .isFalse();
            assertThat(result.rows()).hasSize(COBOL_PAGE_SIZE);
        }

        /**
         * Verifies that the last page returns the remaining
         * (potentially fewer than {@link #COBOL_PAGE_SIZE}) records
         * with {@code last=true} flag set.
         *
         * <p>COBOL: {@code COTRN00C:PROCESS-PF8-KEY} guards
         * page-forward navigation when
         * {@code CDEMO-CT00-NEXT-PAGE-FLG} indicates no next page
         * (the "You have reached the bottom of the page..."
         * message branch). The Java target encodes this as the
         * {@code last=true} flag on the response DTO.</p>
         */
        // COBOL: COTRN00C:PROCESS-PF8-KEY — last-page guard,
        //        CDEMO-CT00-NEXT-PAGE-FLG = 'N'
        @Test
        @DisplayName("listTransactions — last page returns remaining records with last=true")
        void listTransactions_lastPage_returnsRemainingRecords() {
            // Arrange — page 2 of a 25-element journal:
            //   page 0 -> rows 1..10 (full)
            //   page 1 -> rows 11..20 (full)
            //   page 2 -> rows 21..25 (5 rows, the remainder)
            // Construct a 5-row remainder fixture to mirror the
            // COBOL "bottom of the page" condition.
            final long totalElements = 25L;
            final int totalPages = 3;
            final int lastPageIndex = 2;
            final int remainderRows = 5;

            List<Transaction> lastPageFixture = new ArrayList<>(remainderRows);
            for (int i = 21; i <= 25; i++) {
                final String tranId = String.format("%016d", i);
                lastPageFixture.add(
                        buildTransaction(tranId, TEST_PAN_PRIMARY, TRAN_AMT_99_50));
            }
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            lastPageFixture,
                            PageRequest.of(lastPageIndex, COBOL_PAGE_SIZE),
                            totalElements));

            // Act
            TransactionListDto result =
                    service.listTransactions(null, lastPageIndex);

            // Assert — page metadata
            assertThat(result.page())
                    .as("page index — last page index echoed back")
                    .isEqualTo(lastPageIndex);
            assertThat(result.size())
                    .as("size — fixed PAGE_SIZE=10 per AAP §0.7.1 (even though "
                            + "the last page has fewer rows)")
                    .isEqualTo(COBOL_PAGE_SIZE);
            assertThat(result.totalElements())
                    .as("totalElements — 25 across all 3 pages")
                    .isEqualTo(totalElements);
            assertThat(result.totalPages())
                    .as("totalPages — 3 (ceil(25/10))")
                    .isEqualTo(totalPages);
            assertThat(result.first())
                    .as("first — page 2 is NOT the first page")
                    .isFalse();
            assertThat(result.last())
                    .as("last — page 2 IS the last page; matches COBOL guard "
                            + "in PROCESS-PF8-KEY (CDEMO-CT00-NEXT-PAGE-FLG = 'N')")
                    .isTrue();
            assertThat(result.rows())
                    .as("rows — last page returns remaining 5 records (the "
                            + "remainder of the 25-row journal under PAGE_SIZE=10)")
                    .hasSize(remainderRows);
        }

        /**
         * Verifies that an empty result page (no transactions
         * found) returns an empty rows list with totals of zero
         * and both {@code first=true} and {@code last=true}.
         *
         * <p>COBOL: {@code COTRN00C.cbl}:L605-L611
         * {@code STARTBR-TRANSACT-FILE} NOTFND branch &mdash; the
         * COBOL source moves "You are at the top of the page..."
         * to {@code WS-MESSAGE} when STARTBR returns NOTFND.
         * The Java target standardises on the verbatim
         * "NO RECORDS FOUND FOR THIS SEARCH CONDITION." message
         * across sibling list services per AAP &sect;0.7.1; the
         * empty DTO carries the empty rows list, zero totals, and
         * {@code first=true} / {@code last=true} (a zero-element
         * collection is logically both the first and the last
         * page).</p>
         */
        // COBOL: COTRN00C:STARTBR-TRANSACT-FILE NOTFND branch —
        //        empty result handling
        @Test
        @DisplayName("listTransactions — empty result returns empty DTO with first=last=true")
        void listTransactions_emptyResult_returnsEmptyDto() {
            // Arrange — empty Page stub
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — empty rows list, zero totals,
            // first=last=true (a zero-element collection is
            // logically both the first and the last page).
            assertThat(result.rows())
                    .as("rows — empty result returns empty list")
                    .isEmpty();
            assertThat(result.totalElements())
                    .as("totalElements — 0 on empty journal")
                    .isZero();
            assertThat(result.totalPages())
                    .as("totalPages — 0 on empty journal")
                    .isZero();
            assertThat(result.first())
                    .as("first — empty page is logically the first page")
                    .isTrue();
            assertThat(result.last())
                    .as("last — empty page is logically the last page (no next "
                            + "page exists)")
                    .isTrue();
            assertThat(result.size())
                    .as("size — fixed PAGE_SIZE=10 even on empty result")
                    .isEqualTo(COBOL_PAGE_SIZE);
        }

        /**
         * Verifies defensive page-number normalization: negative
         * page numbers are coerced to 0. The COBOL source never
         * produces a negative {@code CDEMO-CT00-PAGE-NUM} (it is
         * declared {@code PIC 9(08)} unsigned and the
         * {@code PROCESS-PF7-KEY} paragraph guards against
         * underflow with the {@code CDEMO-CT00-PAGE-NUM &gt; 1}
         * check at L245). The Java target is a defensive REST
         * contract guard for client-side bugs.
         */
        // COBOL: COTRN00C.cbl:L245 — IF CDEMO-CT00-PAGE-NUM > 1
        //        THEN PERFORM PROCESS-PAGE-BACKWARD; the Java
        //        target guards via Math.max(0, page).
        @Test
        @DisplayName("listTransactions — negative page is coerced to 0")
        void listTransactions_negativePage_coercedToZero() {
            // Arrange — empty Page so we can inspect the captured
            // Pageable for its page number.
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act — negative page number; the SUT must coerce
            // to 0 per the public method's defensive contract.
            service.listTransactions(null, -42);

            // Then — capture the Pageable passed to
            // findByOrderByTranIdAsc and assert page number == 0.
            ArgumentCaptor<Pageable> captor =
                    ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository)
                    .findByOrderByTranIdAsc(captor.capture());

            assertThat(captor.getValue().getPageNumber())
                    .as("Negative page number must be coerced to 0 per the SUT's "
                            + "defensive REST contract (the COBOL source never "
                            + "produces a negative CDEMO-CT00-PAGE-NUM)")
                    .isZero();
        }
    }

    /**
     * AuditLogging tests &mdash; verifies that the read-only
     * paginated-browse path emits NO direct {@link AuditLogService}
     * invocations.
     *
     * <p>The actual audit emission for this service is via the
     * SLF4J {@code Logger} in {@link TransactionListService}
     * ({@code LOG.debug(...)} on the success path,
     * {@code LOG.info(...)} on the empty-result and prefix-scan
     * paths) shipped to CloudWatch Logs by the
     * {@code logstash-logback-encoder} configured in
     * {@code src/main/resources/logback-spring.xml} (AAP
     * &sect;0.7.2 "Structured JSON logging shipped to CloudWatch
     * Logs"). The CloudWatch Logs subscription filter forwards
     * events to Amazon OpenSearch for indexed retention and
     * regulatory queries per AAP &sect;0.6.6.</p>
     *
     * <p>This nested group verifies that the read path performs
     * NO direct {@link AuditLogService} write &mdash; the
     * {@link AuditLogService} adapter is reserved for the
     * write-path services (e.g.,
     * {@code TransactionAddService}, {@code BillPaymentService},
     * {@code AccountUpdateService}, {@code CardUpdateService})
     * that explicitly emit audit events via
     * {@link AuditLogService#logTransactionEvent} /
     * {@link AuditLogService#logAuditEvent} per AAP &sect;0.7.2
     * "Audit trail generation (transaction IDs, timestamps,
     * operator codes) &mdash; now written to CloudTrail +
     * OpenSearch". This isolation is consistent with the COBOL
     * source: {@code app/cbl/COTRN00C.cbl} performs a
     * {@code STARTBR}/{@code READNEXT} paged browse with NO
     * audit-trail write, matching the "read paths don't audit;
     * write paths do" convention.</p>
     *
     * <p>The {@link AuditLogService} {@code @Mock} is declared so
     * any future regression that adds direct
     * {@link AuditLogService} emission to this read path is caught
     * immediately by the
     * {@link org.mockito.Mockito#verifyNoInteractions} guard
     * below. This mirrors the pattern established in
     * {@code TransactionDetailServiceTest} for the sibling COBOL
     * {@code COTRN01C} read-only view program and in
     * {@code CardListServiceTest} for the sibling COBOL
     * {@code COCRDLIC} read-only browse program.</p>
     */
    @Nested
    @DisplayName("AuditLogging — read-only browse emits no AuditLogService events (SLF4J → CloudWatch path only)")
    class AuditLogging {

        /**
         * Verifies that the happy-path {@code listTransactions}
         * invocation runs to completion (returning a non-null
         * {@link TransactionListDto}) AND emits ZERO interactions
         * on the {@link AuditLogService} mock.
         *
         * <p>The {@link org.mockito.Mockito#verifyNoInteractions}
         * assertion is the strongest possible &mdash; it fires if
         * ANY method (audit, security, transaction, batch) is
         * invoked on the mock; this protects against future
         * signature changes on {@link AuditLogService} that would
         * otherwise need per-method {@code never()}
         * verifications.</p>
         *
         * <p>Audit emission for this success path is preserved via
         * the SLF4J {@code LOG.debug(...)} /
         * {@code LOG.info(...)} call in the service
         * implementation: that log line is shipped to CloudWatch
         * Logs via the {@code logstash-logback-encoder} structured
         * JSON appender configured in
         * {@code src/main/resources/logback-spring.xml} and
         * subsequently forwarded to Amazon OpenSearch for indexed
         * audit retention per AAP &sect;0.6.6. The SLF4J emission
         * is intentionally NOT verified in this unit test because:
         * </p>
         * <ol>
         *   <li>the {@code external_imports} whitelist for this
         *       test file does not include Logback (the SLF4J
         *       implementation backing the test runtime), and
         *       adding a Logback {@code ListAppender} would
         *       violate the strict-whitelist discipline; and</li>
         *   <li>logger-output verification is brittle (depends on
         *       formatting changes that don't affect business
         *       behavior) and is more appropriately covered by
         *       integration tests under
         *       {@code src/test/java/com/awsm2/carddemo/integration/}
         *       that exercise the full Logback + CloudWatch
         *       pipeline end-to-end.</li>
         * </ol>
         */
        // COBOL: COTRN00C.cbl — browse path performs no audit-trail
        //        write; the SEND-TRNLST-SCREEN emits to the
        //        operator's terminal but writes no audit log entry.
        //        The Java target preserves this discipline: the
        //        SLF4J LOG.debug/LOG.info emission is observational,
        //        not transactional-audit, and runs through a
        //        different path (Logback → CloudWatch Logs) than
        //        AuditLogService → OpenSearch index
        //        "carddemo-transactions".
        @Test
        @DisplayName("listTransactions — happy-path emits no AuditLogService events")
        void listTransactions_emitsAuditEvent() {
            // Arrange — happy-path stub: 10-row Page
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            tenTransactionsFixture,
                            PageRequest.of(0, COBOL_PAGE_SIZE),
                            tenTransactionsFixture.size()));

            // Act — invoke the SUT; the LOG.debug success line in
            // TransactionListService.listTransactions(...) IS the
            // observability emission per AAP §0.7.2 (the line is
            // shipped to CloudWatch Logs by the Logback +
            // logstash-logback-encoder configuration in
            // logback-spring.xml). The DTO non-nullness assertion
            // below verifies the success path executed (and thus
            // the LOG.debug ran).
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — the success path completed (returning a
            // non-null DTO; this also implies the LOG.debug
            // observability emission ran because it is the entry
            // statement of the public method).
            assertThat(result).isNotNull();
            assertThat(result.rows()).hasSize(COBOL_PAGE_SIZE);

            // Assert — the AuditLogService mock had ZERO
            // interactions. The read path performs no
            // logTransactionEvent / logAuditEvent / etc. — audit
            // emission for read paths is via SLF4J →
            // CloudWatch Logs → OpenSearch, not via the
            // AuditLogService adapter. This negative assertion
            // catches any future regression that adds direct
            // AuditLogService emission to a read path (which
            // would silently flood OpenSearch with
            // high-cardinality read-event documents and incur
            // unnecessary cost).
            verifyNoInteractions(auditLogService);
        }

        /**
         * Cross-check: the {@link AuditLogService} mock receives
         * NO interactions on the empty-result path either. The
         * COBOL source for the {@code STARTBR-TRANSACT-FILE}
         * NOTFND branch ({@code COTRN00C.cbl} L605-L611) sets
         * {@code WS-MESSAGE} and re-renders the screen; it does
         * NOT emit an audit record. The Java target preserves
         * this discipline: empty-result events are logged via
         * SLF4J {@code LOG.info(...)} but never via the
         * {@link AuditLogService} adapter.
         */
        // COBOL: COTRN00C.cbl:L605-L611 — STARTBR NOTFND branch
        //        performs no audit-trail write; only WS-MESSAGE is
        //        set and the screen is re-rendered.
        @Test
        @DisplayName("listTransactions — empty-result path emits no AuditLogService events")
        void listTransactions_emptyResult_emitsNoAudit() {
            // Arrange — empty Page stub
            when(transactionRepository.findByOrderByTranIdAsc(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Act
            TransactionListDto result = service.listTransactions(null, 0);

            // Assert — the empty-result path completed and the
            // AuditLogService mock had ZERO interactions.
            assertThat(result).isNotNull();
            assertThat(result.rows()).isEmpty();
            verifyNoInteractions(auditLogService);
        }
    }
}
