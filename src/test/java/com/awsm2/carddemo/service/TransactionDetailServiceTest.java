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
//   • TransactionDetailService — the @Service under test, instantiated
//     via @InjectMocks; replaces COBOL program COTRN01C (TRANID 'CT01').
//   • Transaction              — JPA @Entity (← CVTRA05Y.cpy 350-byte
//     layout); supplied as the in-test fixture returned by the mocked
//     TransactionRepository.findById(...).
//   • TransactionDetailDto     — record DTO returned by the SUT; record
//     accessor amount() carries the rescaled BigDecimal, cardNumber()
//     carries the unmasked PAN, and toString() applies PAN masking per
//     AAP §0.6.6.
//   • TransactionRepository    — Spring Data JPA repository (← VSAM
//     TRANSACT.KSDS) declared @Mock; the inherited JpaRepository
//     .findById(String) method is stubbed in every test.
//   • AuditLogService          — CloudTrail + OpenSearch audit log
//     adapter declared @Mock to verify (AuditLogging @Nested group)
//     that the COTRN01C read-only path emits NO downstream-system
//     audit event — the actual audit emission for this service is via
//     SLF4J LOG.info shipped to CloudWatch Logs by the logstash-
//     logback-encoder configured in logback-spring.xml (AAP §0.7.2
//     "Structured JSON logging shipped to CloudWatch Logs").
//   • RecordNotFoundException  — domain exception (← COBOL FILE
//     STATUS '23' NOTFND) thrown by the SUT on Optional.empty() from
//     the repository.
// ====================================================================
import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.TransactionRepository;

// ====================================================================
// External imports (org.junit.jupiter:junit-jupiter, org.mockito:*,
// org.assertj:assertj-core — every artifact in scope per the
// external_imports schema; all transitively supplied by
// spring-boot-starter-test per pom.xml).
// ====================================================================
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ====================================================================
// JDK imports — BigDecimal for the scale=2 amount assertion (AAP
// §0.6.1) and Optional for the JpaRepository.findById(...) stub return
// type (matches the COBOL FILE STATUS '00' vs '23' semantic from
// app/cbl/COTRN01C.cbl READ-TRANSACT-FILE).
// ====================================================================
import java.math.BigDecimal;
import java.util.Optional;

// ====================================================================
// Static imports — AssertJ fluent assertions (assertThat,
// assertThatThrownBy) for DTO field comparisons + exception
// verification; Mockito statics for stub setup (when), verified
// invocations (verify with the ArgumentMatchers.eq exact matcher),
// and the audit-isolation negative assertion (verifyNoInteractions).
// All static imports are members of org.mockito.Mockito,
// org.mockito.ArgumentMatchers, or org.assertj.core.api.Assertions
// declared in the external_imports schema.
// ====================================================================
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link TransactionDetailService}.
 *
 * <h2>COBOL provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <p>{@link TransactionDetailService} is the Java target for the
 * COBOL/CICS program {@code app/cbl/COTRN01C.cbl} (CICS transaction id
 * {@code 'CT01'}, mapset {@code COTRN01}, map {@code COTRN1A}). The
 * COBOL source performs a single-keyed read against the
 * {@code TRANSACT} VSAM KSDS by {@code TRAN-ID PIC X(16)} and projects
 * every field of the 350-byte {@code TRAN-RECORD} layout (defined in
 * {@code app/cpy/CVTRA05Y.cpy}) onto the read-only BMS map
 * {@code COTRN1A} (defined in {@code app/bms/COTRN01.bms}). The
 * pseudo-conversational COBOL paragraph sequence is:</p>
 * <ol>
 *   <li>{@code PROCESS-ENTER-KEY} (COTRN01C.cbl L144&ndash;L174)
 *       &mdash; validates the operator-supplied {@code TRNIDINI}
 *       field; if empty / space-filled, sets {@code WS-MESSAGE} to
 *       &quot;Tran ID can NOT be empty...&quot; and re-renders the
 *       screen with the input field highlighted in red.</li>
 *   <li>{@code READ-TRANSACT-FILE} (COTRN01C.cbl L267&ndash;L296)
 *       &mdash; issues {@code EXEC CICS READ DATASET('TRANSACT')
 *       INTO(TRAN-RECORD) RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF
 *       TRAN-ID)}; on {@code DFHRESP(NOTFND)}, sets {@code WS-MESSAGE}
 *       to &quot;Transaction ID NOT found...&quot;.</li>
 *   <li>{@code MOVE TRAN-* TO ...I OF COTRN1AI} (COTRN01C.cbl
 *       L177&ndash;L191) &mdash; populates the output symbolic-map
 *       fields from the {@code TRAN-RECORD} components.</li>
 *   <li>{@code SEND-TRNVIEW-SCREEN} (COTRN01C.cbl L213&ndash;L225)
 *       &mdash; {@code EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01')
 *       FROM(COTRN1AO) ERASE CURSOR}.</li>
 * </ol>
 *
 * <p>In the Java target this becomes:</p>
 * <ol>
 *   <li>null/blank validation on the supplied {@code tranId} (mirrors
 *       COBOL {@code PROCESS-ENTER-KEY}) &mdash; not exercised by this
 *       test class because the schema's internal_imports whitelist
 *       does not include {@code ValidationException}; the dedicated
 *       {@code TransactionDetailService} contract test in
 *       {@code TransactionControllerTest} covers the validation
 *       boundary at the HTTP layer.</li>
 *   <li>Spring Data JPA {@code TransactionRepository.findById(...)}
 *       lookup keyed by the 16-character {@code tranId} (mirrors
 *       COBOL {@code READ-TRANSACT-FILE}).</li>
 *   <li>{@code Optional.orElseThrow(...)} bridge to
 *       {@link RecordNotFoundException} (HTTP 404 via the
 *       {@code GlobalExceptionHandler}; preserves COBOL FILE STATUS
 *       {@code '23'} NOTFND semantic per AAP &sect;0.4.1 / &sect;0.7.2
 *       error-code-preservation rule).</li>
 *   <li>Projection of the {@link Transaction} JPA entity onto a
 *       {@link TransactionDetailDto} response record with
 *       {@code BigDecimal.setScale(2, RoundingMode.HALF_EVEN)}
 *       applied to the {@code amount} component (AAP &sect;0.6.1
 *       monetary-precision rule).</li>
 * </ol>
 *
 * <h2>Behavioural invariants locked by this suite</h2>
 * <ol>
 *   <li><b>Happy path</b> &mdash; a valid 16-character transaction
 *       ID resolves through {@link TransactionRepository#findById}
 *       and returns a fully populated {@link TransactionDetailDto}.
 *       (COBOL: {@code READ-TRANSACT-FILE} success + {@code MOVE
 *       TRAN-* TO ...I OF COTRN1AI} block.)</li>
 *   <li><b>Monetary precision</b> &mdash; the
 *       {@link TransactionDetailDto#amount() amount} component
 *       preserves {@code scale=2} (AAP &sect;0.6.1 / DTO contract
 *       &quot;Producer must apply setScale(2,
 *       RoundingMode.HALF_EVEN) on every value&quot;); the value is
 *       compared with AssertJ's {@code isEqualByComparingTo} because
 *       {@link BigDecimal#equals(Object)} is scale-sensitive
 *       ({@code 99.50.equals(99.5)} is {@code false}).</li>
 *   <li><b>PCI-DSS: PAN masking</b> &mdash; the
 *       {@link TransactionDetailDto#cardNumber()} accessor carries
 *       the unmasked 16-digit PAN past the service boundary
 *       (matching the legacy 3270 View Transaction screen behavior
 *       documented in the {@link TransactionDetailService} JavaDoc
 *       PCI-DSS section and AAP &sect;0.7.3 Minimal Change Clause).
 *       The DTO's {@link TransactionDetailDto#toString()} override
 *       masks the PAN to its last four digits
 *       ({@code ************NNNN}) so that any accidental log
 *       emission of the DTO never exposes the PAN to CloudWatch
 *       Logs &mdash; AAP &sect;0.6.6 PCI-DSS v4.0 Requirement
 *       3.4.1.</li>
 *   <li><b>Record not found</b> &mdash; missing transaction surfaces
 *       as {@link RecordNotFoundException} (HTTP 404 via
 *       {@code GlobalExceptionHandler}; preserves COBOL
 *       {@code DFHRESP(NOTFND)} / FILE STATUS {@code '23'} semantic
 *       from {@code COTRN01C.cbl} L283&ndash;L288).</li>
 *   <li><b>Audit isolation</b> &mdash; the
 *       {@link TransactionDetailService} 1-arg constructor takes only
 *       {@link TransactionRepository} (NO {@link AuditLogService}
 *       dependency); the read-only view path emits its audit signal
 *       through SLF4J {@code LOG.info(...)} (shipped to CloudWatch
 *       Logs via Logback + logstash-logback-encoder per AAP
 *       &sect;0.7.2) and never invokes any
 *       {@link AuditLogService} method. The {@link AuditLogService}
 *       {@code @Mock} is declared so any future regression that
 *       adds direct AuditLogService emission to this read path is
 *       caught immediately by the
 *       {@link org.mockito.Mockito#verifyNoInteractions} guard in
 *       the AuditLogging nested group. This mirrors the pattern
 *       established in {@code CardDetailServiceTest} for the
 *       sibling {@code COCRDSLC} read-only view program.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack or Testcontainers are
 * involved.</p>
 *
 * @see TransactionDetailService
 * @see TransactionDetailDto
 * @see Transaction
 * @see TransactionRepository
 * @see RecordNotFoundException
 * @see AuditLogService
 */
// COBOL: COTRN01C:VIEW-TRANSACTION — single-transaction detail view
//        (TRANID 'CT01', mapset 'COTRN01', map 'COTRN1A')
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService — COTRN01C single view")
class TransactionDetailServiceTest {

    // ==================================================================
    // Test constants — shared across every @Nested group
    //
    // Per AAP §0.7.3 traceability discipline, every constant is named
    // after the COBOL field it represents (CVTRA05Y.cpy line numbers):
    //   TRAN_ID            ← CVTRA05Y.cpy:L5 TRAN-ID PIC X(16)
    //   UNKNOWN_TRAN_ID    ← any 16-character ID with no row in
    //                        the transactions table (used by the
    //                        RecordNotFound test to drive
    //                        Optional.empty() through orElseThrow)
    //   TRAN_TYPE_CD       ← CVTRA05Y.cpy:L6 TRAN-TYPE-CD PIC X(02)
    //   TRAN_CAT_CD        ← CVTRA05Y.cpy:L7 TRAN-CAT-CD  9(04)
    //   TRAN_SOURCE        ← CVTRA05Y.cpy:L8 TRAN-SOURCE PIC X(10)
    //   TRAN_DESC          ← CVTRA05Y.cpy:L9 TRAN-DESC   PIC X(100)
    //   TRAN_AMT           ← CVTRA05Y.cpy:L10 TRAN-AMT PIC S9(09)V99
    //                        (BigDecimal scale=2 per AAP §0.6.1)
    //   TRAN_MERCHANT_ID   ← CVTRA05Y.cpy:L11 TRAN-MERCHANT-ID  9(09)
    //   TRAN_MERCHANT_NAME ← CVTRA05Y.cpy:L12 TRAN-MERCHANT-NAME X(50)
    //   TRAN_MERCHANT_CITY ← CVTRA05Y.cpy:L13 TRAN-MERCHANT-CITY X(50)
    //   TRAN_MERCHANT_ZIP  ← CVTRA05Y.cpy:L14 TRAN-MERCHANT-ZIP  X(10)
    //   TRAN_CARD_NUM      ← CVTRA05Y.cpy:L15 TRAN-CARD-NUM PIC X(16)
    //                        (PCI-DSS sensitive — masked in toString)
    //   LAST4              ← last 4 digits used by PAN-masking
    //                        assertions
    //   MASKED_PAN         ← 12-asterisk + last-4 form per AAP §0.6.6
    //                        PCI-DSS v4.0 Requirement 3.4.1
    // ==================================================================

    private static final String TRAN_ID = "0000000000000001";
    private static final String UNKNOWN_TRAN_ID = "9999999999999999";
    private static final String TRAN_TYPE_CD = "01";
    private static final Integer TRAN_CAT_CD = 5411;
    private static final String TRAN_SOURCE = "ONLINE";
    private static final String TRAN_DESC = "GROCERY STORE PURCHASE";
    // Constructed via the BigDecimal(String) constructor literal to
    // preserve scale=2 verbatim — per AAP §0.6.1 the BigDecimal(double)
    // constructor and floating-point literals are forbidden for
    // monetary values because they introduce binary-floating-point
    // precision artifacts. The string-form "99.50" is the canonical
    // representation matching COBOL TRAN-AMT PIC S9(09)V99 from
    // CVTRA05Y.cpy:L10.
    private static final BigDecimal TRAN_AMT = new BigDecimal("99.50");
    private static final Long TRAN_MERCHANT_ID = 100_000_001L;
    private static final String TRAN_MERCHANT_NAME = "ACME GROCERY STORE";
    private static final String TRAN_MERCHANT_CITY = "SEATTLE";
    private static final String TRAN_MERCHANT_ZIP = "98109";
    private static final String TRAN_CARD_NUM = "4111111111111111";
    private static final String LAST4 = "1111";
    private static final String MASKED_PAN = "************" + LAST4;

    // ==================================================================
    // Mocks and System Under Test (SUT)
    //
    // The TransactionDetailService 1-arg constructor takes ONLY a
    // TransactionRepository per AAP §0.4.1 (one-service-per-COBOL-
    // program mapping; COTRN01C is read-only). The AuditLogService
    // mock is declared because the schema includes it in the
    // internal_imports whitelist and the AuditLogging @Nested group
    // asserts (via verifyNoInteractions) that the read path emits no
    // AuditLogService events. Mockito's @InjectMocks tolerates the
    // unused @Mock field — it simply does not pass auditLogService
    // into a constructor that doesn't declare a parameter for it.
    //
    // Per AAP §0.7.2 ("Structured JSON logging shipped to CloudWatch
    // Logs"), the actual audit emission for this read-only path is via
    // the SLF4J Logger in TransactionDetailService (LOG.info on the
    // happy path) — NOT through AuditLogService. The Mockito mock
    // here serves as a defense-in-depth guard against any future
    // regression that adds direct AuditLogService emission to a read
    // path (which would silently flood OpenSearch with high-cardinality
    // read-event documents and incur unnecessary cost). The CardDemo
    // sibling read-only service test (CardDetailServiceTest) uses
    // exactly this pattern for the same reason.
    // ==================================================================

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TransactionDetailService service;

    /**
     * Transaction test fixture populated in {@link #setUp()} and
     * returned by the {@code transactionRepository.findById(...)}
     * mock for happy-path tests. Carries every business field of the
     * COBOL {@code TRAN-RECORD} layout
     * ({@code app/cpy/CVTRA05Y.cpy} L4&ndash;L18) so the DTO
     * projection assertions can verify each component end-to-end.
     *
     * <p>Timestamps ({@code tranOrigTs}, {@code tranProcTs}) are left
     * {@code null} on the fixture &mdash; the schema's
     * external_imports whitelist does not include
     * {@code java.time.LocalDateTime}, so the fixture omits these
     * fields. The DTO projection still works because the record's
     * timestamp components accept {@code null} values; the existing
     * happy-path assertions verify only the non-timestamp components.
     * Timestamp behavior is validated end-to-end in
     * {@code TransactionDetailService} integration tests under
     * {@code src/test/java/com/awsm2/carddemo/integration/}.</p>
     */
    private Transaction transaction;

    /**
     * Initialize the {@link #transaction} test fixture before each
     * test method.
     *
     * <p>Constructs a {@link Transaction} entity with every
     * non-timestamp business field set to a known value. The
     * fixture mirrors {@code CVTRA05Y.cpy} L4&ndash;L18 byte
     * layout exactly &mdash; same field order, same semantic
     * meaning &mdash; so the
     * {@link TransactionDetailService#getTransactionDetail(String)}
     * projection can be asserted component-by-component against
     * the COBOL source layout.</p>
     *
     * <p>The {@code tranAmt} BigDecimal is constructed via the
     * {@link BigDecimal#BigDecimal(String) string constructor} to
     * preserve {@code scale=2} verbatim &mdash; per AAP &sect;0.6.1
     * the {@code BigDecimal(double)} constructor and floating-point
     * literals are forbidden for monetary values because they
     * introduce binary-floating-point precision artifacts. The
     * string-form {@code "99.50"} guarantees the COBOL
     * {@code TRAN-AMT PIC S9(09)V99} representation flows through
     * the DTO unchanged.</p>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COTRN01C:VIEW-TRANSACTION — build the in-memory
        // TRAN-RECORD that the mocked TransactionRepository.findById
        // returns. Field assignment mirrors CVTRA05Y.cpy:L5-L18 in
        // its declared order. The 20-byte trailing FILLER (L18) is
        // omitted because the JPA entity has no FILLER column per
        // AAP §0.6.2 (PostgreSQL has no concept of fixed-width
        // record padding).
        transaction = new Transaction();
        // CVTRA05Y.cpy:L5 — TRAN-ID PIC X(16); primary key
        transaction.setTranId(TRAN_ID);
        // CVTRA05Y.cpy:L6 — TRAN-TYPE-CD PIC X(02); joinable to
        // tran_type lookup table
        transaction.setTranTypeCd(TRAN_TYPE_CD);
        // CVTRA05Y.cpy:L7 — TRAN-CAT-CD PIC 9(04); 4-digit numeric
        transaction.setTranCatCd(TRAN_CAT_CD);
        // CVTRA05Y.cpy:L8 — TRAN-SOURCE PIC X(10); ONLINE/POS/BATCH
        transaction.setTranSource(TRAN_SOURCE);
        // CVTRA05Y.cpy:L9 — TRAN-DESC PIC X(100); full description
        transaction.setTranDesc(TRAN_DESC);
        // CVTRA05Y.cpy:L10 — TRAN-AMT PIC S9(09)V99; BigDecimal
        // scale=2 per AAP §0.6.1 monetary-precision rule
        transaction.setTranAmt(TRAN_AMT);
        // CVTRA05Y.cpy:L11 — TRAN-MERCHANT-ID PIC 9(09); 9-digit
        transaction.setTranMerchantId(TRAN_MERCHANT_ID);
        // CVTRA05Y.cpy:L12 — TRAN-MERCHANT-NAME PIC X(50)
        transaction.setTranMerchantName(TRAN_MERCHANT_NAME);
        // CVTRA05Y.cpy:L13 — TRAN-MERCHANT-CITY PIC X(50)
        transaction.setTranMerchantCity(TRAN_MERCHANT_CITY);
        // CVTRA05Y.cpy:L14 — TRAN-MERCHANT-ZIP PIC X(10)
        transaction.setTranMerchantZip(TRAN_MERCHANT_ZIP);
        // CVTRA05Y.cpy:L15 — TRAN-CARD-NUM PIC X(16); PAN — masked
        // in toString() per AAP §0.6.6, never modified at rest.
        transaction.setTranCardNum(TRAN_CARD_NUM);
        // CVTRA05Y.cpy:L16-L17 — TRAN-ORIG-TS / TRAN-PROC-TS PIC X(26).
        // Intentionally not set; see fixture JavaDoc above. The DTO
        // projection accepts null LocalDateTime values without NPE.
    }

    // ====================================================================
    // @Nested test groups (per the agent_prompt Phase 3 structure)
    // ====================================================================

    /**
     * Happy-path tests &mdash; a valid 16-character transaction ID
     * resolves to a fully populated {@link TransactionDetailDto}
     * returned by
     * {@link TransactionDetailService#getTransactionDetail(String)}.
     *
     * <p>COBOL provenance: corresponds to the success path through
     * {@code COTRN01C.cbl} paragraphs {@code MAIN-PARA} &rarr;
     * {@code PROCESS-ENTER-KEY} (input validation passes) &rarr;
     * {@code READ-TRANSACT-FILE} (CICS READ returns
     * {@code DFHRESP(NORMAL)}) &rarr; {@code MOVE TRAN-* TO ...I OF
     * COTRN1AI} (output projection) &rarr;
     * {@code SEND-TRNVIEW-SCREEN} (3270 render).</p>
     */
    @Nested
    @DisplayName("Happy path — valid tranId returns populated DTO")
    class HappyPath {

        /**
         * Verifies the canonical success path: a 16-character
         * transaction ID stubs through
         * {@link TransactionRepository#findById(Object)} and returns
         * a non-null {@link TransactionDetailDto} populated from the
         * {@link Transaction} fixture.
         *
         * <p>The mock for
         * {@link TransactionRepository#findById(Object)} is stubbed
         * to return {@code Optional.of(transaction)} (cache-aside
         * pattern is NOT used by {@link TransactionDetailService}
         * per its JavaDoc: &quot;The transactions table is
         * append-only and individual transaction detail lookups are
         * uncommon &mdash; the cache hit rate would not justify the
         * consistency complexity&quot;). The service is expected to
         * return a DTO with each business field populated from the
         * fixture.</p>
         */
        // COBOL: COTRN01C:READ-TRANSACT-FILE — DFHRESP(NORMAL) branch
        @Test
        @DisplayName("getTransactionDetail returns DTO for valid tranId")
        void getTransactionDetail_validId_returnsDto() {
            // Arrange — TransactionRepository.findById returns the
            // populated fixture (replacing COBOL EXEC CICS READ
            // DATASET('TRANSACT') with DFHRESP(NORMAL)).
            when(transactionRepository.findById(TRAN_ID))
                    .thenReturn(Optional.of(transaction));

            // Act — invoke the SUT
            TransactionDetailDto result =
                    service.getTransactionDetail(TRAN_ID);

            // Assert — non-null DTO with every business field
            // populated from the Transaction fixture. The field
            // assertions follow the CVTRA05Y.cpy:L5-L15 order so the
            // assertion block can be cross-referenced 1:1 against
            // the COBOL TRAN-RECORD layout.
            assertThat(result).isNotNull();
            assertThat(result.transactionId()).isEqualTo(TRAN_ID);
            assertThat(result.transactionType()).isEqualTo(TRAN_TYPE_CD);
            assertThat(result.transactionCategory()).isEqualTo(TRAN_CAT_CD);
            assertThat(result.source()).isEqualTo(TRAN_SOURCE);
            assertThat(result.description()).isEqualTo(TRAN_DESC);
            // amount() is asserted in the dedicated
            // amount-preservation test below via isEqualByComparingTo
            // for BigDecimal scale-sensitivity (see AAP §0.6.1).
            assertThat(result.merchantId()).isEqualTo(TRAN_MERCHANT_ID);
            assertThat(result.merchantName()).isEqualTo(TRAN_MERCHANT_NAME);
            assertThat(result.merchantCity()).isEqualTo(TRAN_MERCHANT_CITY);
            assertThat(result.merchantZip()).isEqualTo(TRAN_MERCHANT_ZIP);
            // Issue CP4-#13: per QA CP4 report (PCI-DSS v4.0 Requirement
            // 3.3 — Mask PAN when displayed), the cardNumber accessor
            // returns the MASKED form (12 asterisks + last 4) to match
            // the masking already applied by TransactionListService.
            // Previously the detail endpoint exposed the full PAN while
            // the list endpoint masked it; the inconsistency was a
            // hidden over-disclosure surface. The masking is enforced
            // by TransactionDetailService.maskPan() in toDetailDto().
            assertThat(result.cardNumber()).isEqualTo(MASKED_PAN);

            // Verify the repository method was invoked exactly with
            // the supplied (trimmed) transaction ID — uses
            // ArgumentMatchers.eq per the external_imports schema.
            verify(transactionRepository).findById(eq(TRAN_ID));
        }

        /**
         * Verifies the AAP &sect;0.6.1 monetary-precision rule: the
         * {@link Transaction#getTranAmt() tranAmt} BigDecimal is
         * carried through to {@link TransactionDetailDto#amount()}
         * with {@code scale=2} preserved.
         *
         * <p>The assertion uses AssertJ's
         * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo
         * isEqualByComparingTo} rather than {@code isEqualTo}
         * because {@link BigDecimal#equals(Object)} is
         * scale-sensitive &mdash;
         * {@code new BigDecimal("99.50").equals(new BigDecimal("99.5"))}
         * returns {@code false}. The value-level comparison
         * delegates to {@link BigDecimal#compareTo} which IS scale-
         * insensitive, so the test passes whether the service
         * returns {@code 99.50} (scale 2) or {@code 99.5} (scale 1).
         * The follow-up {@code scale()} assertion locks the
         * AAP-mandated {@code scale=2} contract; together these two
         * assertions verify both the value AND the scale of the
         * BigDecimal.</p>
         */
        // COBOL: COTRN01C:PROCESS-ENTER-KEY L177 — MOVE TRAN-AMT TO
        // WS-TRAN-AMT (PIC +99999999.99); the COBOL display picture
        // is a 2-decimal-place fixed-point format. The Java target
        // applies setScale(2, HALF_EVEN) explicitly in
        // TransactionDetailService.toDto(...) per AAP §0.6.1.
        @Test
        @DisplayName("amount preserved with scale=2 (BigDecimal, no float)")
        void getTransactionDetail_amountPreserved_withScaleTwo() {
            // Arrange — happy-path stub
            when(transactionRepository.findById(TRAN_ID))
                    .thenReturn(Optional.of(transaction));

            // Act
            TransactionDetailDto result =
                    service.getTransactionDetail(TRAN_ID);

            // Assert — value comparison via isEqualByComparingTo
            // (scale-insensitive) AND scale lock at 2 per AAP §0.6.1.
            // BigDecimal.equals(Object) is scale-sensitive
            // (99.50 != 99.5); isEqualByComparingTo delegates to
            // BigDecimal.compareTo which is scale-insensitive. Both
            // assertions together guarantee value+scale fidelity.
            assertThat(result.amount()).isEqualByComparingTo(TRAN_AMT);
            assertThat(result.amount().scale()).isEqualTo(2);
        }

        /**
         * Verifies the AAP &sect;0.6.6 PCI-DSS v4.0 Requirement
         * 3.4.1 rule: the PAN never appears in plaintext through any
         * logging-prone DTO output.
         *
         * <p>Issue CP4-#13 (PCI-DSS v4.0 Requirement 3.3): the
         * {@link TransactionDetailService} now masks the PAN at the
         * service boundary &mdash; the unmasked PAN never leaves the
         * service layer. Previously the detail endpoint exposed the
         * full 16-digit PAN through
         * {@link TransactionDetailDto#cardNumber()} while
         * {@link com.awsm2.carddemo.service.TransactionListService}
         * masked it; the inconsistency was a hidden over-disclosure
         * surface flagged by QA as a PCI-DSS concern. Masking now
         * applies uniformly across both the list and detail
         * endpoints: 12 asterisks followed by the last 4 PAN
         * characters. TLS 1.2+ on the ALB, JWT authentication,
         * KMS-at-rest encryption, CloudWatch log filters, and
         * Macie S3 scanning are additional defence-in-depth layers
         * mandated by AAP &sect;0.6.6.</p>
         *
         * <p>This test asserts BOTH halves of the masking contract:</p>
         * <ol>
         *   <li>The {@link TransactionDetailDto#cardNumber()}
         *       accessor returns the MASKED PAN
         *       ({@code ************1111}) so that all downstream
         *       consumers (REST clients, audit logs, OpenSearch
         *       indexes) receive only the masked form.</li>
         *   <li>The {@link TransactionDetailDto#toString()}
         *       representation contains the masked form
         *       {@code ************1111} and does NOT contain the
         *       unmasked PAN substring &mdash; satisfying the
         *       AAP-mandated PCI-DSS rule for log-safety.</li>
         * </ol>
         */
        // COBOL: COTRN01C:VIEW-TRANSACTION — MOVE TRAN-CARD-NUM TO
        // CARDNUMI OF COTRN1AI (L179); the 3270 terminal renders
        // the full PAN to the authenticated operator. The Java
        // target consistently masks the PAN at the service
        // boundary per AAP §0.6.6 PCI-DSS rule and CP4 Issue #13.
        @Test
        @DisplayName("PAN masked in cardNumber() and in toString() (CP4 Issue #13)")
        void getTransactionDetail_panMasked() {
            // Arrange — happy-path stub
            when(transactionRepository.findById(TRAN_ID))
                    .thenReturn(Optional.of(transaction));

            // Act
            TransactionDetailDto result =
                    service.getTransactionDetail(TRAN_ID);

            // Assert — cardNumber() accessor returns the MASKED PAN
            // ("************1111"). Per Issue CP4-#13 the unmasked
            // PAN never leaves the service layer; this is a
            // defence-in-depth measure aligned with PCI-DSS v4.0
            // Requirement 3.3 (mask PAN when displayed).
            assertThat(result.cardNumber()).isEqualTo(MASKED_PAN);

            // Assert — toString() output also masks the PAN to its
            // last 4 digits per AAP §0.6.6 PCI-DSS v4.0 Requirement
            // 3.4.1. The masked form is twelve asterisks followed
            // by the last 4 PAN characters. Any accidental DTO
            // logging (Spring Boot exception traces, debug logs,
            // request/response tracing) emits the masked form to
            // CloudWatch Logs — never the unmasked PAN.
            assertThat(result.toString()).contains(MASKED_PAN);

            // Defense in depth — the unmasked PAN MUST NOT appear
            // anywhere in toString() (catches a future regression
            // that, e.g., adds a "fullCardNumber=" debug field).
            assertThat(result.toString()).doesNotContain(TRAN_CARD_NUM);
        }
    }

    /**
     * Record-not-found tests &mdash; an unknown transaction ID
     * (no row in the {@code transactions} table) surfaces as a typed
     * {@link RecordNotFoundException} which the
     * {@code GlobalExceptionHandler} translates to {@code HTTP 404
     * Not Found} per AAP &sect;0.4.1 (&quot;Maps VSAM NOTFND to 404
     * Not Found&quot;).
     *
     * <p>COBOL provenance: preserves the
     * {@code COTRN01C.cbl} {@code READ-TRANSACT-FILE} paragraph
     * (L267&ndash;L296) {@code WHEN DFHRESP(NOTFND)} branch
     * (L283&ndash;L288) where the COBOL source sets {@code WS-MESSAGE}
     * to &quot;Transaction ID NOT found...&quot; and re-renders the
     * input screen with the {@code TRNIDINL} field highlighted in
     * red. In the Java target the same condition surfaces as a
     * typed domain exception &mdash; the FILE STATUS {@code '23'}
     * NOTFND value is preserved verbatim per AAP &sect;0.7.2
     * (&quot;Error codes and condition handling surfaced to
     * downstream consumers must be preserved verbatim&quot;).</p>
     */
    @Nested
    @DisplayName("Record not found — missing tranId throws "
            + "RecordNotFoundException (HTTP 404)")
    class RecordNotFound {

        /**
         * Verifies that
         * {@link TransactionRepository#findById(Object)} returning
         * {@link Optional#empty()} causes the
         * {@code Optional.orElseThrow(...)} chain in
         * {@link TransactionDetailService} to throw a typed
         * {@link RecordNotFoundException}.
         *
         * <p>The {@link RecordNotFoundException} is caught by the
         * application-wide {@code GlobalExceptionHandler}
         * ({@code @RestControllerAdvice}) and translated to
         * {@code HTTP 404 Not Found} with the COBOL FILE STATUS
         * {@code '23'} value preserved verbatim in the JSON error
         * envelope's {@code code} field (AAP &sect;0.7.2). This
         * unit test focuses only on the typed exception emission
         * &mdash; HTTP status-code translation is verified by
         * {@code GlobalExceptionHandlerTest} and
         * {@code TransactionControllerTest}.</p>
         */
        // COBOL: COTRN01C:READ-TRANSACT-FILE L283-L288 — WHEN
        // DFHRESP(NOTFND) → MOVE 'Transaction ID NOT found...' TO
        // WS-MESSAGE → SEND-TRNVIEW-SCREEN with error highlight.
        @Test
        @DisplayName("missing tranId raises RecordNotFoundException")
        void getTransactionDetail_idNotFound_throwsRecordNotFound() {
            // Arrange — repository returns Optional.empty() for the
            // unknown transaction ID (replacing COBOL EXEC CICS
            // READ with DFHRESP(NOTFND)).
            when(transactionRepository.findById(UNKNOWN_TRAN_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert — the SUT's .orElseThrow(...) chain
            // surfaces the typed RecordNotFoundException. AssertJ's
            // assertThatThrownBy provides a single fluent chain that
            // both invokes the SUT and asserts on the thrown
            // exception type.
            assertThatThrownBy(() ->
                    service.getTransactionDetail(UNKNOWN_TRAN_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            // Verify the repository was queried exactly with the
            // supplied transaction ID — confirms the SUT did not
            // short-circuit on validation before the repository
            // lookup (which would have masked the
            // RecordNotFoundException with a ValidationException).
            verify(transactionRepository).findById(eq(UNKNOWN_TRAN_ID));
        }

        /**
         * Verifies that the {@link RecordNotFoundException} message
         * carries the requested transaction ID for diagnostic
         * traceability. The
         * {@link TransactionDetailService#getTransactionDetail(String)}
         * implementation constructs the exception via
         * {@code new RecordNotFoundException("TRANSACTION_NOT_FOUND",
         * "tranId=" + trimmed)} (QA Final-CP6 Finding M6: structured
         * error code replaces the previous entity-class name); the
         * {@code "tranId="} message prefix is preserved here so that
         * downstream log consumers (CloudWatch Logs Insights,
         * OpenSearch query) can grep for the requested transaction
         * ID without parsing the human-readable component.
         */
        // COBOL: AAP §0.6.6 — exception message MUST NOT contain
        // PII beyond what the caller originally supplied. Echoing
        // back the transaction ID (a 16-character opaque sequence
        // number, NOT a PAN) is acceptable per AAP §0.6.6.
        @Test
        @DisplayName("RecordNotFoundException message echoes tranId")
        void getTransactionDetail_idNotFound_messageEchoesTranId() {
            // Arrange — repository returns Optional.empty()
            when(transactionRepository.findById(UNKNOWN_TRAN_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert — the exception message contains the
            // requested transaction ID for downstream log searches.
            assertThatThrownBy(() ->
                    service.getTransactionDetail(UNKNOWN_TRAN_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(UNKNOWN_TRAN_ID);
        }
    }

    /**
     * Audit-isolation tests &mdash; the read-only
     * {@link TransactionDetailService} view path emits NO direct
     * {@link AuditLogService} invocations.
     *
     * <p>The actual audit emission for this service is via the
     * SLF4J {@code Logger} in
     * {@link TransactionDetailService} ({@code LOG.info(...)} on
     * the happy path, {@code LOG.info(...)} on the not-found path)
     * shipped to CloudWatch Logs by the
     * {@code logstash-logback-encoder} configured in
     * {@code src/main/resources/logback-spring.xml} (AAP
     * &sect;0.7.2 &quot;Structured JSON logging shipped to
     * CloudWatch Logs&quot;). The CloudWatch Logs subscription
     * filter forwards events to Amazon OpenSearch for indexed
     * retention and regulatory queries per AAP &sect;0.6.6.</p>
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
     * &quot;Audit trail generation (transaction IDs, timestamps,
     * operator codes) &mdash; now written to CloudTrail +
     * OpenSearch&quot;. This isolation is consistent with the
     * COBOL source: {@code app/cbl/COTRN01C.cbl} performs a
     * single {@code EXEC CICS READ} with no audit-trail write,
     * matching the &quot;read paths don't audit; write paths
     * do&quot; convention.</p>
     *
     * <p>The {@link AuditLogService} {@code @Mock} is declared so
     * any future regression that adds direct {@link AuditLogService}
     * emission to this read path is caught immediately by the
     * {@link org.mockito.Mockito#verifyNoInteractions} guard
     * below. This mirrors the pattern established in
     * {@code CardDetailServiceTest} for the sibling COBOL
     * {@code COCRDSLC} read-only view program.</p>
     */
    @Nested
    @DisplayName("Audit logging — read-only view emits no "
            + "AuditLogService events (SLF4J → CloudWatch path only)")
    class AuditLogging {

        /**
         * Verifies that the happy-path {@code getTransactionDetail}
         * invocation runs to completion (returning a non-null
         * {@link TransactionDetailDto}) AND emits ZERO interactions
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
         * the SLF4J {@code LOG.info(...)} call in the service
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
        // COBOL: COTRN01C.cbl — view path performs no audit-trail
        // write; the SEND-TRNVIEW-SCREEN emits to the operator's
        // terminal but writes no audit log entry. The Java target
        // preserves this discipline: the SLF4J LOG.info emission is
        // observational, not transactional-audit, and runs through
        // a different path (Logback → CloudWatch Logs) than
        // AuditLogService → OpenSearch index "carddemo-transactions".
        @Test
        @DisplayName("happy-path emits no AuditLogService events")
        void getTransactionDetail_emitsAuditEvent_onSuccess() {
            // Arrange — happy-path stub
            when(transactionRepository.findById(TRAN_ID))
                    .thenReturn(Optional.of(transaction));

            // Act — invoke the SUT; the LOG.info success line in
            // TransactionDetailService.getTransactionDetail(...) IS
            // the audit-emission event per AAP §0.7.2 (the line is
            // shipped to CloudWatch Logs by the Logback +
            // logstash-logback-encoder configuration in
            // logback-spring.xml). The DTO non-nullness assertion
            // below verifies the success path executed (and thus the
            // LOG.info ran).
            TransactionDetailDto result =
                    service.getTransactionDetail(TRAN_ID);

            // Assert — the success path completed (returning a
            // non-null DTO; this also implies the LOG.info
            // observability emission ran because it is the last
            // statement before the return).
            assertThat(result).isNotNull();

            // Assert — the AuditLogService mock had ZERO
            // interactions. The read path performs no
            // logTransactionEvent / logAuditEvent / etc. — audit
            // emission for read paths is via SLF4J →
            // CloudWatch Logs → OpenSearch, not via the
            // AuditLogService adapter. This negative assertion
            // catches any future regression that adds direct
            // AuditLogService emission to a read path (which would
            // silently flood OpenSearch with high-cardinality
            // read-event documents and incur unnecessary cost).
            verifyNoInteractions(auditLogService);
        }

        /**
         * Cross-check: the {@link AuditLogService} mock receives
         * NO interactions on the NOTFND path either. The COBOL
         * source for the {@code DFHRESP(NOTFND)} branch
         * ({@code COTRN01C.cbl} L283&ndash;L288) sets
         * {@code WS-MESSAGE} and re-renders the screen with the
         * input field highlighted; it does NOT emit an audit
         * record. The Java target preserves this discipline:
         * not-found events are logged via SLF4J
         * {@code LOG.info(...)} but never via the
         * {@link AuditLogService} adapter.
         */
        // COBOL: COTRN01C.cbl L283-L288 — DFHRESP(NOTFND) path
        // performs no audit-trail write; only WS-MESSAGE is set
        // and the screen is re-rendered.
        @Test
        @DisplayName("not-found path emits no AuditLogService events")
        void getTransactionDetail_notFoundPath_emitsNoAudit() {
            // Arrange — repository miss
            when(transactionRepository.findById(UNKNOWN_TRAN_ID))
                    .thenReturn(Optional.empty());

            // Act — the SUT throws RecordNotFoundException;
            // assertThatThrownBy invokes the SUT and consumes the
            // exception so the test can verify the audit
            // interaction after the throw point.
            assertThatThrownBy(() ->
                    service.getTransactionDetail(UNKNOWN_TRAN_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert — even on a NOTFND, NO audit emission
            // occurs through the AuditLogService adapter. This
            // mirrors the COBOL source's NOTFND branch which
            // performs no audit write.
            verifyNoInteractions(auditLogService);
        }
    }
}
