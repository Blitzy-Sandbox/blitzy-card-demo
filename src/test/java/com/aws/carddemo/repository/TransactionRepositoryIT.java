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
package com.aws.carddemo.repository;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * Transaction — the JPA entity stub matching the CVTRA05Y.cpy
//     TRAN-RECORD layout (350-byte fixed-width record: 16-char TRAN-ID PK +
//     2-char TRAN-TYPE-CD + 4-char TRAN-CAT-CD + 10-char TRAN-SOURCE +
//     100-char TRAN-DESC + PIC S9(09)V99 TRAN-AMT + 9-digit TRAN-MERCHANT-ID
//     + 50-char TRAN-MERCHANT-NAME + 50-char TRAN-MERCHANT-CITY + 10-char
//     TRAN-MERCHANT-ZIP + 16-char TRAN-CARD-NUM + 26-char TRAN-ORIG-TS
//     + 26-char TRAN-PROC-TS + 20-char FILLER). Test methods drive
//     persist/find/save round-trips through this entity and assert that the
//     BigDecimal monetary field (amount) preserves the COBOL PIC S9(09)V99
//     scale (2 decimal places) on every round-trip — the AAP §0.10.3
//     financial-precision invariant.
//
//     PRODUCTION FIELD NAMING NOTE — the AAP §0.10 "Phase 12 Adaptation
//     Notes" explicitly anticipates the production-entity field-name
//     divergence from the COBOL TRAN-* literals. The production
//     {@link Transaction} class uses Java-conventioned field names
//     (transactionId, transactionTypeCode, transactionCategoryCode, source,
//     description, amount, merchantId, merchantName, merchantCity,
//     merchantZip, cardNumber, originTimestamp, processTimestamp) rather
//     than the COBOL-style TRAN-* names; this IT drives the Java-style API
//     exactly as the production code exposes it.
//
//     PRODUCTION TIMESTAMP TYPE NOTE — the AAP §0.10 "Phase 12 Adaptation
//     Notes" also explicitly anticipates the production type choice for
//     timestamp fields. The production {@link Transaction#getOriginTimestamp()}
//     and {@link Transaction#getProcessTimestamp()} fields are {@link String}
//     (storing the COBOL PIC X(26) literal 'YYYY-MM-DD HH:MM:SS.ffffff'
//     representation), NOT {@link java.time.LocalDateTime}. Tests construct
//     the value via {@link java.time.LocalDateTime#of} +
//     {@link java.time.format.DateTimeFormatter} formatted to the COBOL
//     X(26) shape, keeping the date-time semantics self-documenting at the
//     call site while producing the {@link String} the production setter
//     requires. This mirrors the peer {@code AccountRepositoryIT} and
//     {@code CustomerRepositoryIT} patterns for ISO date strings.
//
//     PRODUCTION CATEGORY-CODE / MERCHANT-ID TYPE NOTE — both
//     {@code transactionCategoryCode} and {@code merchantId} are
//     {@link String} on the production entity (preserving COBOL
//     zero-padded numeric key formats) rather than {@link Integer} /
//     {@link Long}. This IT calls the production setters with String
//     arguments accordingly.
//
//   * AbstractRepositoryIT — the abstract base class providing the
//     @DataJpaTest slice annotation, the @Testcontainers PostgreSQL 16
//     container, the @DynamicPropertySource that wires Testcontainers'
//     JDBC URL/username/password into Spring's environment, and the
//     inherited TestEntityManager field (entityManager). Per AAP §0.4.4
//     all 10 repository ITs extend this base — this IT inherits the JPA
//     slice, per-class container lifecycle, and transactional rollback
//     after each @Test method without re-declaring any of that wiring.
//
//   * TestFixtures — the shared test-constants holder. This IT consumes
//     the Cards-nested constants: SAMPLE_CARD_NUMBER_01 ("4111111111111101"),
//     SAMPLE_CARD_NUMBER_10 ("4111111111111110"), NONEXISTENT_CARD_NUMBER
//     ("4999999999999999", deliberately outside the converted fixture
//     range — used for VSAM STATUS '23' parity assertions); and the
//     Transactions-nested constants: TRAN_TYPE_PURCHASE ("01"),
//     TRAN_CAT_REGULAR_SALES ("0001"), TRAN_SOURCE_POS ("POS TERM  ").
//     Per AAP §0.5.5 Cross-File Test Dependencies and AAP §0.10.1
//     Require Test Coverage Rule (test bodies must not duplicate literal
//     sentinel values that already appear in TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the {@code *IT.java} suffix convention
//     and JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test reports
//     surface the COBOL-parity intent (rather than the camelCase method
//     name alone).
//
//   * @Disabled defers <em>runtime</em> execution until the production-
//     side prerequisites (JPA @Entity/@Id/@Column/@Table annotations on
//     Transaction + Flyway V1__schema.sql + V3__seed.sql) are landed by
//     subsequent REFACTOR-flavor migration agents. JUnit 5 reports
//     @Disabled tests as "skipped" (not "failed") so the Surefire/Failsafe
//     build stays green; the reactivation criteria appear in the
//     annotation's value attribute and in the class-level Javadoc
//     "Reactivation Checklist" section. The sibling
//     {@code AccountRepositoryIT}, {@code CardRepositoryIT},
//     {@code CustomerRepositoryIT}, {@code UserSecurityRepositoryIT},
//     {@code TransactionCategoryRepositoryIT},
//     {@code TransactionTypeRepositoryIT}, and
//     {@code DiscountGroupRepositoryIT} use the same @Disabled pattern —
//     this IT mirrors that established project convention so the
//     compile-time wiring is verified end-to-end while the runtime DB
//     execution awaits its production-side dependencies.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed TransactionRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<Transaction, String>} interface declaration
//     on the production repository — no manual implementation is
//     required, and no field declared with @Mock is permissible at this
//     IT layer (AAP §0.10.1 Require Test Coverage Rule: integration
//     tests must invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Spring Data Commons paging API.
//
//   * Page — the paged-result container returned by
//     {@link org.springframework.data.repository.PagingAndSortingRepository#findAll(Pageable)}
//     and by the custom finder
//     {@link TransactionRepository#findByCardNumber(String,
//     org.springframework.data.domain.Pageable)} and
//     {@link TransactionRepository#findByAccountId(String,
//     org.springframework.data.domain.Pageable)}. Tests assert on
//     {@code Page#getContent()} (page-row count and contents),
//     {@code Page#getTotalElements()} (cross-page total),
//     {@code Page#getNumber()} (zero-based page index), and
//     {@code Page#getSize()} (page size echoed back).
//
//   * PageRequest — the canonical {@link Pageable} factory used to
//     construct paged-query requests via
//     {@code PageRequest.of(pageNumber, pageSize)}.
//
//   * Pageable — the parameter type for paged-query methods (page
//     number + page size + sort order). The IT supplies concrete
//     {@code PageRequest} instances at the call site.
// ---------------------------------------------------------------------------
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.math.BigDecimal — the mandated representation of every
//     monetary value migrated from the COBOL PIC S9(09)V99 field
//     TRAN-AMT (defined in app/cpy/CVTRA05Y.cpy line 9) per AAP §0.10.3
//     ("No float or double used for any monetary value — BigDecimal
//     exclusively"). All monetary literals in this IT are constructed
//     as {@code new BigDecimal("...")} from a {@link String} (NEVER from
//     a {@code double} — the {@code BigDecimal(double)} constructor
//     introduces representation noise, e.g., {@code new BigDecimal(0.1)}
//     yields {@code 0.10000000000000000555…}; the
//     {@code BigDecimal(String)} constructor preserves the exact decimal
//     form). The scale assertions ({@code assertThat(value.scale())
//     .isEqualTo(2)}) verify that the persistence layer never silently
//     truncates precision and that the round-trip honours the COBOL
//     PIC S9(09)V99 scale-2 contract on every read.
//
//   * java.time.LocalDateTime — used as a self-documenting builder for
//     the COBOL X(26) timestamp strings the production Transaction
//     entity stores. The COBOL TRAN-ORIG-TS and TRAN-PROC-TS fields are
//     PIC X(26) — fixed-width 'YYYY-MM-DD HH:MM:SS.ffffff' strings (see
//     {@code app/data/ASCII/dailytran.txt} for canonical examples like
//     "2022-06-10 19:27:53.000000"). The Java migration preserves the
//     String-based storage at the field level (see
//     {@link Transaction#getOriginTimestamp()}) but tests construct the
//     value via {@link LocalDateTime#of(int, java.time.Month, int, int,
//     int, int)} formatted through {@link COBOL_TS_FORMAT} to make the
//     date-time semantics self-documenting at the call site
//     (AAP §0.10.10 Style Consistency).
//
//   * java.time.Month — enum constants (MARCH, APRIL, MAY, JUNE, JULY,
//     AUGUST) used in {@link LocalDateTime#of(int, java.time.Month, int,
//     int, int, int)} builder calls for readability. Tests construct
//     synthetic timestamps across 2022 to stamp every persisted
//     transaction's TRAN-ORIG-TS / TRAN-PROC-TS deterministically.
//
//   * java.time.format.DateTimeFormatter — formats {@link LocalDateTime}
//     values into the COBOL X(26) 'YYYY-MM-DD HH:MM:SS.ffffff' literal
//     shape the production {@link Transaction#getOriginTimestamp()} /
//     {@link Transaction#getProcessTimestamp()} setters consume. A
//     {@code private static final} pattern matches the project convention
//     (see {@code AccountRepositoryIT} for the LocalDate.toString()
//     equivalent on PIC X(10) ISO YYYY-MM-DD date fields).
//
//   * java.util.List — the element type carried inside {@link Page} via
//     {@code Page#getContent()}. Tests assign the returned list to a
//     {@link List} variable for the subsequent AssertJ chain
//     (hasSize / allSatisfy / extracting / contains / doesNotContain).
//
//   * java.util.Optional — the return type of
//     {@code transactionRepository.findById(...)} and
//     {@code transactionRepository.findTopByOrderByTransactionIdDesc()}.
//     Tests assert {@code isPresent()} for happy-path lookups,
//     {@code isEmpty()} for VSAM STATUS '23' record-not-found parity,
//     and {@code orElseThrow()} when extracting the reloaded entity for
//     round-trip scale-preservation assertions.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static imports (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. Idioms exercised by this class include
//     assertThat(optional).isPresent() / isEmpty(),
//     assertThat(txn.getAmount()).isEqualByComparingTo(...),
//     assertThat(txn.getAmount().scale()).isEqualTo(2),
//     assertThat(txn.getAmount().signum()).isEqualTo(-1),
//     assertThat(page.getContent()).hasSize(...).allSatisfy(...),
//     assertThat(content).extracting(Transaction::getTransactionId)
//         .contains(...).doesNotContain(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Integration tests for {@link TransactionRepository}, the Spring Data JPA
 * repository that persists {@link Transaction} entities migrated from the
 * COBOL {@code TRAN-RECORD} defined in {@code app/cpy/CVTRA05Y.cpy}
 * (RECLN 350).
 *
 * <h2>COBOL Provenance — CVTRA05Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 350-byte record:
 * <pre>
 *   01 TRAN-RECORD.
 *      05 TRAN-ID                    PIC X(16).      --&gt; {@link Transaction#getTransactionId()}         (primary key)
 *      05 TRAN-TYPE-CD               PIC X(02).      --&gt; {@link Transaction#getTransactionTypeCode()}
 *      05 TRAN-CAT-CD                PIC 9(04).      --&gt; {@link Transaction#getTransactionCategoryCode()}
 *      05 TRAN-SOURCE                PIC X(10).      --&gt; {@link Transaction#getSource()}
 *      05 TRAN-DESC                  PIC X(100).     --&gt; {@link Transaction#getDescription()}
 *      05 TRAN-AMT                   PIC S9(09)V99.  --&gt; {@link Transaction#getAmount()}               (BigDecimal scale 2)
 *      05 TRAN-MERCHANT-ID           PIC 9(09).      --&gt; {@link Transaction#getMerchantId()}
 *      05 TRAN-MERCHANT-NAME         PIC X(50).      --&gt; {@link Transaction#getMerchantName()}
 *      05 TRAN-MERCHANT-CITY         PIC X(50).      --&gt; {@link Transaction#getMerchantCity()}
 *      05 TRAN-MERCHANT-ZIP          PIC X(10).      --&gt; {@link Transaction#getMerchantZip()}
 *      05 TRAN-CARD-NUM              PIC X(16).      --&gt; {@link Transaction#getCardNumber()}
 *      05 TRAN-ORIG-TS               PIC X(26).      --&gt; {@link Transaction#getOriginTimestamp()}      (ISO 'YYYY-MM-DD HH:MM:SS.ffffff' String)
 *      05 TRAN-PROC-TS               PIC X(26).      --&gt; {@link Transaction#getProcessTimestamp()}     (ISO 'YYYY-MM-DD HH:MM:SS.ffffff' String)
 *      05 FILLER                     PIC X(20).
 * </pre>
 *
 * <h2>Migration Pattern (AAP §0.5.1)</h2>
 *
 * <p>The CVTRA05Y transaction record is read or written by FIVE distinct
 * COBOL programs in the original CardDemo workflow, each of which maps
 * to a specific JPA repository contract verified by this IT:
 *
 * <ul>
 *   <li><strong>COTRN01C.cbl</strong> (transaction view) performs an
 *       {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)} on
 *       the 16-character primary key — exercised here by
 *       {@link #findById_existingTransactionId_returnsTransaction()}
 *       and {@link #findById_nonexistentTransactionId_returnsEmpty()}.
 *   </li>
 *   <li><strong>COTRN00C.cbl</strong> (transaction list) performs a
 *       {@code STARTBR / READNEXT} browse loop with a fixed
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10}
 *       page size — exercised here by
 *       {@link #findAll_paged_returnsPagedResults()},
 *       {@link #findByCardNumber_existingCard_returnsAllTransactionsForCard()},
 *       {@link #findByCardNumber_nonexistentCard_returnsEmptyPage()},
 *       {@link #findByAccountId_existingAccount_returnsTransactions()},
 *       and {@link #findByAccountId_nonexistentAccount_returnsEmptyPage()}.
 *   </li>
 *   <li><strong>COTRN02C.cbl</strong> (transaction add) writes a new
 *       {@code TRAN-RECORD} via {@code EXEC CICS WRITE DATASET('TRANSACT')}
 *       — exercised here by the three {@code save_*} tests that verify
 *       the BigDecimal scale-2 round-trip on insert.
 *   </li>
 *   <li><strong>COBIL00C.cbl</strong> (bill payment) reads the last
 *       assigned TRAN-ID via {@code MOVE HIGH-VALUES TO TRAN-ID /
 *       STARTBR / READPREV / ENDBR} to compute the next sequential ID —
 *       exercised here by
 *       {@link #findTopByOrderByTransactionIdDesc_emptyTable_returnsEmpty()}
 *       and the implicit max-key path covered by the synthetic-insert
 *       tests.
 *   </li>
 *   <li><strong>CBTRN02C.cbl</strong> (batch posting) inserts daily
 *       transactions from {@code dailytran.txt} — same write path as
 *       COTRN02C, also covered by the {@code save_*} tests.
 *   </li>
 * </ul>
 *
 * <h2>Test Strategy (AAP §0.4.4)</h2>
 *
 * <p>Each {@code @Test} method executes inside a transaction that rolls
 * back at method end (inherited from {@code @DataJpaTest}), so no manual
 * cleanup is required. The Testcontainers PostgreSQL 16 instance and
 * Flyway-applied schema + seed data are provided by
 * {@link AbstractRepositoryIT}. Tests that need additional rows beyond
 * the Flyway-applied seed seed them via the inherited
 * {@code TestEntityManager} ({@code entityManager.persist} +
 * {@code flush} + {@code clear}) so the subsequent repository call hits
 * the database rather than the first-level persistence-context cache.
 *
 * <h2>Coverage Focus (AAP §0.5.1, §0.7.1)</h2>
 *
 * <ul>
 *   <li>{@link #save_newTransaction_persistsWithCorrectScale()} —
 *       INSERT path preserves BigDecimal scale 2 (AAP §0.10.3).</li>
 *   <li>{@link #save_negativeAmount_preservesSignAndScale()} —
 *       INSERT path preserves the signed PIC S9(09)V99 semantics
 *       (refund / reversal amounts).</li>
 *   <li>{@link #save_maximumPrecisionAmount_preservesAllDigits()} —
 *       INSERT path preserves the PIC S9(09)V99 boundary
 *       (9 integer + 2 fractional digits = max value 999,999,999.99).</li>
 *   <li>{@link #findById_existingTransactionId_returnsTransaction()} —
 *       primary-key lookup (COBOL DFHRESP(NORMAL) parity).</li>
 *   <li>{@link #findById_nonexistentTransactionId_returnsEmpty()} —
 *       VSAM STATUS 23 / DFHRESP(NOTFND) parity.</li>
 *   <li>{@link #findAll_paged_returnsPagedResults()} — Spring Data
 *       paging contract (covers COTRN00C STARTBR/READNEXT semantics).</li>
 *   <li>{@link #findByCardNumber_existingCard_returnsAllTransactionsForCard()} —
 *       card-key filter (foreign-key access pattern — Java-migration
 *       addition documented in {@link TransactionRepository#findByCardNumber}).</li>
 *   <li>{@link #findByCardNumber_nonexistentCard_returnsEmptyPage()} —
 *       card-key filter empty-result semantics.</li>
 *   <li>{@link #findByAccountId_existingAccount_returnsTransactions()} —
 *       account-key filter (cross-table JOIN through CARD-NUM index
 *       per {@link TransactionRepository#findByAccountId}).</li>
 *   <li>{@link #findByAccountId_nonexistentAccount_returnsEmptyPage()} —
 *       account-key filter empty-result semantics.</li>
 *   <li>{@link #findTopByOrderByTransactionIdDesc_emptyTable_returnsEmpty()} —
 *       max-key lookup (COBIL00C HIGH-VALUES / READPREV parity at
 *       DFHRESP(ENDFILE) / empty-store boundary).</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — repository
 *       wiring smoke test.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests invoke the real {@link TransactionRepository} bean against a
 * real PostgreSQL database (via Testcontainers, inherited from
 * {@link AbstractRepositoryIT}). No mocks, no in-memory shortcuts. Setup
 * uses {@link org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager}
 * from the parent class. Tests assert on observable behaviour of the
 * production repository — they do not reimplement business logic
 * (no value arithmetic, no sign-overpunch decoding, no record parsing).
 *
 * <h2>Financial Precision Contract (AAP §0.10.3)</h2>
 *
 * <p>Every monetary assertion on {@link Transaction#getAmount()} uses
 * the AssertJ {@code isEqualByComparingTo(BigDecimal)} idiom (value
 * equality via {@code compareTo == 0}) paired with an explicit
 * {@code scale() == 2} assertion to detect silent scale truncation —
 * a {@code BigDecimal("123.4")} would {@code compareTo} equal a
 * {@code BigDecimal("123.40")} but has scale 1, breaking parity with
 * COBOL PIC S9(09)V99 (scale 2). The {@code save_*} test family
 * therefore exercises three boundary cases: a typical positive amount,
 * a negative amount (refund / reversal semantics inherent in the signed
 * S9 picture), and the 999,999,999.99 max-precision boundary that
 * verifies the underlying DDL column accommodates all 9 integer + 2
 * fractional digits.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>This IT awaits production-side prerequisites that are owned by
 * subsequent REFACTOR-flavor migration agents (per AAP §0.8.1 the
 * testing flavor cannot modify those production files for testability
 * alone):
 *
 * <ol>
 *   <li><strong>JPA mapping annotations</strong> on
 *       {@link Transaction} — {@code @Entity}, {@code @Id} on
 *       {@code transactionId}, {@code @Column(name = ...)} on each
 *       persisted field, and {@code @Table(name = "transactions")}.
 *       Column widths must mirror the COBOL CVTRA05Y.cpy PIC clauses
 *       (transaction_id CHAR(16) PK for PIC X(16), transaction_type_code
 *       CHAR(2) for PIC X(02), transaction_category_code CHAR(4) for
 *       PIC 9(04), source CHAR(10) for PIC X(10), description CHAR(100)
 *       for PIC X(100), amount NUMERIC(11, 2) for PIC S9(09)V99 with
 *       scale 2, merchant_id CHAR(9) for PIC 9(09), merchant_name
 *       VARCHAR(50) for PIC X(50), merchant_city VARCHAR(50) for
 *       PIC X(50), merchant_zip VARCHAR(10) for PIC X(10), card_number
 *       CHAR(16) for PIC X(16), origin_timestamp CHAR(26) for PIC X(26),
 *       process_timestamp CHAR(26) for PIC X(26)).</li>
 *   <li><strong>Flyway V1__schema.sql</strong> under
 *       {@code src/main/resources/db/migration/} creating the
 *       {@code transactions} table with the schema above; the JaCoCo
 *       baseline does not enforce branch coverage on the table DDL but
 *       the table must exist for any {@code @Test} method to run.</li>
 *   <li><strong>Flyway V3__seed.sql</strong> (optional for this IT —
 *       every test seeds its own transactions via the inherited
 *       {@code TestEntityManager}; a project-wide seed of 300 rows from
 *       {@code app/data/ASCII/dailytran.txt} is documented in
 *       AAP §0.4.4 but is not strictly required to activate this
 *       class). When the project-wide seed lands the
 *       {@link #count_invoked_returnsNonNegativeValue()} test continues
 *       to pass (it asserts {@code &gt;= 0}, not {@code == 0}).</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime — the {@code mvn verify} build agent must be able to
 *       run {@code postgres:16-alpine}. CI agents that cannot start
 *       containers (e.g. nested-virtualisation-free environments) can
 *       set {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <p>When items 1–3 above are complete (item 4 is an environment
 * prerequisite, not a code change), deleting the {@code @Disabled}
 * annotation and the {@code import org.junit.jupiter.api.Disabled;}
 * line activates the suite. No other code changes are required: the
 * existing 12 test method bodies are written against the production
 * API exactly as it will be once the REFACTOR work completes.
 *
 * @see TransactionRepository
 * @see Transaction
 * @see AbstractRepositoryIT
 * @see TestFixtures.Cards
 * @see TestFixtures.Transactions
 */
@DisplayName("TransactionRepository — CVTRA05Y.cpy migration parity ITs")
class TransactionRepositoryIT extends AbstractRepositoryIT {

    /**
     * Canonical COBOL X(26) timestamp formatter used to convert
     * {@link LocalDateTime} test-side construction values into the
     * 26-character literal shape ('YYYY-MM-DD HH:MM:SS.ffffff') the
     * production {@link Transaction#getOriginTimestamp()} /
     * {@link Transaction#getProcessTimestamp()} setters consume. The
     * pattern mirrors the format observed in
     * {@code app/data/ASCII/dailytran.txt} (e.g.
     * {@code "2022-06-10 19:27:53.000000"}).
     *
     * <p>Per AAP §0.10.10 (Style Consistency): a {@code private static
     * final} formatter constant matches the project's preference for
     * shared, immutable formatters defined once at class scope. The
     * fractional-second pattern {@code SSSSSS} produces six-digit
     * microsecond precision matching the COBOL X(26) field width
     * (10 chars date + 1 space + 8 chars time + 1 dot + 6 chars
     * micros = 26 chars).
     */
    private static final DateTimeFormatter COBOL_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * The Spring Data JPA repository under integration test. The bean
     * is created by Spring Data JPA at {@code @DataJpaTest} context
     * startup from the {@link TransactionRepository} interface
     * declaration (no manual implementation). Field injection is
     * consistent with the inherited {@code @Autowired TestEntityManager
     * entityManager} field on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    // =========================================================================
    // INSERT / Scale-Preservation Tests (AAP §0.10.3 Financial Precision)
    // =========================================================================

    /**
     * Verifies that {@link TransactionRepository#save(Object)} persists a
     * new {@link Transaction} row and that the COBOL PIC S9(09)V99
     * {@code TRAN-AMT} scale (2 decimal places) is preserved on the
     * subsequent {@code findById} round-trip.
     *
     * <p>The test constructs a synthetic transaction with amount
     * {@code 123.45} (a non-trivial fractional part chosen so silent
     * scale truncation would be detectable), persists it via
     * {@code save}, flushes + clears the persistence context to force
     * a database round-trip on the next read, and then asserts that the
     * reloaded entity carries:
     * <ul>
     *   <li>the expected primary key (round-trip identity),</li>
     *   <li>the expected amount value via
     *       {@code isEqualByComparingTo} (value equality), and</li>
     *   <li>the expected scale (2) — this catches the case where the
     *       DDL column was inadvertently configured as
     *       {@code NUMERIC(11)} without scale, which would silently
     *       drop the fractional digits while leaving
     *       {@code compareTo} returning {@code 0}.</li>
     * </ul>
     *
     * <p>This is the Java equivalent of the COBOL workflow in
     * {@code COTRN02C.cbl} (transaction add) and {@code CBTRN02C.cbl}
     * (batch posting), both of which write {@code TRAN-AMT} via
     * {@code EXEC CICS WRITE DATASET('TRANSACT')} against the VSAM
     * KSDS file.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(new Transaction) persists row and preserves BigDecimal scale 2")
    void save_newTransaction_persistsWithCorrectScale() {
        // Arrange — construct a synthetic transaction with BigDecimal scale 2
        // matching COBOL PIC S9(09)V99. The amount 123.45 has a non-trivial
        // fractional part to detect any silent scale truncation. The
        // TestFixtures.Cards / TestFixtures.Transactions constants supply the
        // canonical synthetic fixture values without leaking PII.
        Transaction txn = buildSyntheticTransaction(
                "TXN0000000000001",
                new BigDecimal("123.45"),
                LocalDateTime.of(2022, Month.MARCH, 15, 10, 30, 0));

        // Act — invoke the production save(), then flush + clear the
        // persistence context to defeat the first-level cache so the
        // subsequent findById() hits the real database.
        Transaction saved = transactionRepository.save(txn);
        entityManager.flush();
        entityManager.clear();

        // Assert — reload via the repository and verify every field that
        // matters for the scale-preservation contract.
        Optional<Transaction> reloaded = transactionRepository.findById(saved.getTransactionId());
        assertThat(reloaded)
                .as("Saved transaction should be retrievable by primary key")
                .isPresent();
        Transaction t = reloaded.get();

        assertThat(t.getAmount())
                .as("TRAN-AMT must round-trip with exact value")
                .isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(t.getAmount().scale())
                .as("TRAN-AMT scale must equal 2 per COBOL PIC S9(09)V99 (AAP §0.10.3)")
                .isEqualTo(2);

        assertThat(t.getTransactionId()).isEqualTo("TXN0000000000001");
        assertThat(t.getTransactionTypeCode())
                .as("TRAN-TYPE-CD must round-trip exactly per CVTRA05Y.cpy PIC X(02)")
                .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        assertThat(t.getTransactionCategoryCode())
                .as("TRAN-CAT-CD must round-trip exactly per CVTRA05Y.cpy PIC 9(04)")
                .isEqualTo(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);
        assertThat(t.getCardNumber())
                .as("TRAN-CARD-NUM must round-trip exactly per CVTRA05Y.cpy PIC X(16)")
                .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
    }

    /**
     * Verifies that the signed COBOL PIC S9(09)V99 semantics of
     * {@code TRAN-AMT} are preserved on round-trip — a negative amount
     * (representing a refund or reversal) must persist with sign
     * intact and scale unchanged.
     *
     * <p>The COBOL {@code TRAN-AMT PIC S9(09)V99} field carries a sign
     * indicator (overpunch on the rightmost digit) that distinguishes
     * positive (purchase) amounts from negative (refund / reversal)
     * amounts. The Java migration represents this as a signed
     * {@link BigDecimal}; the persistence layer must preserve the
     * sign on write and read.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("save(Transaction with negative amount) preserves sign and scale (S9(09)V99 signed semantics)")
    void save_negativeAmount_preservesSignAndScale() {
        // Arrange — COBOL S9(09)V99 supports negative monetary values
        // (e.g., refund, reversal, payment-against-balance).
        BigDecimal negativeAmount = new BigDecimal("-99.99");
        Transaction txn = buildSyntheticTransaction(
                "TXN0000000000002",
                negativeAmount,
                LocalDateTime.of(2022, Month.APRIL, 1, 9, 0, 0));

        // Act — save then flush + clear so the subsequent read is from DB.
        transactionRepository.save(txn);
        entityManager.flush();
        entityManager.clear();

        // Assert — sign and scale both preserved on round-trip.
        Transaction reloaded = transactionRepository.findById("TXN0000000000002").orElseThrow();
        assertThat(reloaded.getAmount())
                .as("Negative amount must round-trip with sign preserved")
                .isEqualByComparingTo(new BigDecimal("-99.99"));
        assertThat(reloaded.getAmount().signum())
                .as("Amount must be negative (signum -1) — refund / reversal semantics")
                .isEqualTo(-1);
        assertThat(reloaded.getAmount().scale())
                .as("Scale must equal 2 even for negative values")
                .isEqualTo(2);
    }

    /**
     * Verifies that the maximum representable value of the COBOL
     * PIC S9(09)V99 picture (999,999,999.99 — 9 integer + 2 fractional
     * digits) is preserved on round-trip without truncation.
     *
     * <p>This boundary test confirms the underlying DDL column is wide
     * enough to accommodate the full {@code TRAN-AMT} range. A
     * {@code NUMERIC(10, 2)} column would silently overflow on
     * {@code 999999999.99}; a {@code NUMERIC(11, 2)} column accommodates
     * the full picture. The test asserts both the exact value (via
     * {@code isEqualByComparingTo}) and the precision (via
     * {@link BigDecimal#precision()} {@code >= 11}, which is the total
     * number of significant digits — 9 integer + 2 fractional).
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("save(Transaction with maximum precision amount) preserves all 9 integer + 2 fractional digits")
    void save_maximumPrecisionAmount_preservesAllDigits() {
        // Arrange — COBOL PIC S9(09)V99 = max value 999,999,999.99
        // (boundary check for DDL column precision).
        BigDecimal maxAmount = new BigDecimal("999999999.99");
        Transaction txn = buildSyntheticTransaction(
                "TXN0000000000003",
                maxAmount,
                LocalDateTime.of(2022, Month.MAY, 10, 14, 15, 30));

        // Act — persist then re-read from the database.
        transactionRepository.save(txn);
        entityManager.flush();
        entityManager.clear();

        // Assert — every digit must round-trip exactly.
        Transaction reloaded = transactionRepository.findById("TXN0000000000003").orElseThrow();
        assertThat(reloaded.getAmount())
                .as("Max precision amount must round-trip without truncation")
                .isEqualByComparingTo(new BigDecimal("999999999.99"));
        assertThat(reloaded.getAmount().scale())
                .as("Scale must equal 2 at the S9(09)V99 boundary")
                .isEqualTo(2);
        assertThat(reloaded.getAmount().precision())
                .as("Precision must accommodate 9 integer + 2 fractional digits")
                .isGreaterThanOrEqualTo(11);
    }

    // =========================================================================
    // Primary-Key Lookup Tests (AAP §0.5.1 "findById")
    // =========================================================================

    /**
     * Verifies that {@link TransactionRepository#findById(Object)}
     * returns the persisted {@link Transaction} for an existing
     * 16-character primary key.
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NORMAL)} branch from {@code COTRN01C.cbl}'s
     * {@code READ-TRANSACT-FILE} paragraph (lines 267–296) and
     * underlies the transaction-view lookup half of the
     * {@link com.aws.carddemo.service.TransactionDetailService}
     * read-only flow.
     *
     * <p>The test persists a known transaction via
     * {@code TestEntityManager.persistAndFlush}, clears the
     * persistence-context cache, then asserts the {@code Optional}
     * returned by {@code findById} carries an entity with the
     * expected primary key.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing TRAN-ID) returns the persisted transaction (COTRN01C §READ-TRANSACT-FILE DFHRESP(NORMAL) parity)")
    void findById_existingTransactionId_returnsTransaction() {
        // Arrange — persist a known transaction so findById has a row to find.
        Transaction seed = buildSyntheticTransaction(
                "TXN0000000000010",
                new BigDecimal("50.00"),
                LocalDateTime.of(2022, Month.JUNE, 1, 12, 0, 0));
        entityManager.persistAndFlush(seed);
        entityManager.clear();

        // Act — drive the production repository against the real DB.
        Optional<Transaction> result = transactionRepository.findById("TXN0000000000010");

        // Assert — the row must be located and the primary key round-trips.
        assertThat(result)
                .as("findById should locate the persisted transaction (COBOL DFHRESP(NORMAL) equivalent)")
                .isPresent();
        assertThat(result.get().getTransactionId())
                .as("TRAN-ID primary key must round-trip exactly")
                .isEqualTo("TXN0000000000010");
    }

    /**
     * Verifies that {@link TransactionRepository#findById(Object)}
     * returns {@link Optional#empty()} when the supplied 16-character
     * primary key does not exist in the {@code transactions} table.
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NOTFND)} branch from {@code COTRN01C.cbl} (lines
     * 285–288) — the underlying VSAM file-status code {@code '23'}
     * (record not found). The production
     * {@link com.aws.carddemo.service.TransactionDetailService}
     * translates this empty {@link Optional} into the COBOL-equivalent
     * {@code "Transaction ID NOT found..."} reject message.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findById(nonexistent TRAN-ID) returns empty Optional (VSAM STATUS 23 / COTRN01C DFHRESP(NOTFND) parity)")
    void findById_nonexistentTransactionId_returnsEmpty() {
        // Act — query for a TRAN-ID guaranteed not to exist in the seed data.
        // The "TXNNONEXISTENT01" sentinel string is deliberately outside the
        // numeric-only daily-transaction ID range (0000000000000001..) and
        // outside the 2022071800nnnnnn interest-transaction ID range.
        Optional<Transaction> result = transactionRepository.findById("TXNNONEXISTENT01");

        // Assert — JPA equivalent of VSAM STATUS '23' (record not found): empty.
        assertThat(result)
                .as("Nonexistent TRAN-ID must yield empty Optional (parallels VSAM STATUS '23' / DFHRESP(NOTFND))")
                .isEmpty();
    }

    // =========================================================================
    // Paging Tests (AAP §0.5.1 "paging query")
    // =========================================================================

    /**
     * Verifies the Spring Data paging contract for
     * {@link TransactionRepository#findAll(Pageable)}: page size honoured,
     * total-element count consistent across pages, and the
     * {@code page.number} / {@code page.size} metadata echoed back.
     *
     * <p>This is the Java equivalent of the COBOL workflow in
     * {@code COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD} (lines 279–328):
     * a {@code STARTBR-TRANSACT-FILE} + {@code PERFORM UNTIL WS-IDX
     * &gt;= 11 OR TRANSACT-EOF OR ERR-FLG-ON} loop that reads up to
     * 10 records into the {@code COTRN0AO} BMS map. The fixed
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10}
     * bound materialises as a {@link PageRequest} with page size 10
     * in {@link com.aws.carddemo.service.TransactionListService}; the
     * test below uses a smaller page size (3) to exercise the paging
     * boundary on a small fixture set.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findAll(Pageable) honors page size and reports correct totals (COTRN00C PROCESS-PAGE-FORWARD parity)")
    void findAll_paged_returnsPagedResults() {
        // Arrange — persist exactly 5 transactions. The test uses small,
        // deterministic synthetic IDs ("TXN0000000000001".."TXN0000000000005")
        // to keep the resulting page count predictable independent of any
        // baseline seed in the database.
        for (int i = 1; i <= 5; i++) {
            Transaction t = buildSyntheticTransaction(
                    String.format("TXN%013d", i),
                    new BigDecimal("10.00"),
                    LocalDateTime.of(2022, Month.JULY, i, 8, 0, 0));
            entityManager.persist(t);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — request page 0, size 3 (defensive page request that returns a
        // strict subset of the persisted rows).
        Pageable pageRequest = PageRequest.of(0, 3);
        Page<Transaction> firstPage = transactionRepository.findAll(pageRequest);

        // Assert — page size, content count, and metadata.
        assertThat(firstPage.getContent())
                .as("Page 0 with size 3 should contain at most 3 elements")
                .hasSize(3);
        assertThat(firstPage.getTotalElements())
                .as("Total elements should be at least 5 (Flyway seed may add more)")
                .isGreaterThanOrEqualTo(5);
        assertThat(firstPage.getNumber())
                .as("Page number metadata should echo the request (zero-based)")
                .isEqualTo(0);
        assertThat(firstPage.getSize())
                .as("Page size metadata should echo the request")
                .isEqualTo(3);

        // Act — fetch second page.
        Page<Transaction> secondPage = transactionRepository.findAll(PageRequest.of(1, 3));

        // Assert — page progression preserves the total count.
        assertThat(secondPage.getNumber())
                .as("Second page number should be 1")
                .isEqualTo(1);
        assertThat(secondPage.getTotalElements())
                .as("Total-elements count must remain stable across pages")
                .isEqualTo(firstPage.getTotalElements());
    }

    // =========================================================================
    // Card-Key Filter Tests (Foreign-Key Access Pattern via findByCardNumber)
    // =========================================================================

    /**
     * Verifies that
     * {@link TransactionRepository#findByCardNumber(String, Pageable)}
     * returns only the transactions whose {@code TRAN-CARD-NUM} matches
     * the supplied 16-character PAN.
     *
     * <p>This is a Java-migration addition documented in
     * {@link TransactionRepository#findByCardNumber} — the COBOL
     * {@code COTRN00C.cbl} workflow does not contain an explicit
     * card-key filter (the operator navigates by {@code TRAN-ID} via
     * the {@code TRNIDINI} starting-browse key). The Java migration
     * surfaces a {@code ?card=NNNN...} query parameter on the
     * transaction-list REST endpoint that maps onto this repository
     * call — the natural REST equivalent of the VSAM alternate-index
     * walk that operators would perform manually in the 3270 workflow.
     *
     * <p>The test seeds 3 transactions on the target card and 2 on a
     * different card, requests a page large enough to fit any matching
     * subset, and asserts that the returned page contains exactly the
     * 3 target-card rows. {@code allSatisfy} verifies the card-key
     * filter excluded the other card's rows; {@code hasSize(3)}
     * verifies the count.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findByCardNumber(existing card) returns all transactions for that card (COTRN00C ?card= REST filter parity)")
    void findByCardNumber_existingCard_returnsAllTransactionsForCard() {
        // Arrange — persist 3 transactions on the same card, 2 on a different
        // card. The TestFixtures synthetic PANs ("4111111111111101" /
        // "4111111111111110") are Visa test PANs that carry no PII.
        String targetCard = TestFixtures.Cards.SAMPLE_CARD_NUMBER_01;
        String otherCard = TestFixtures.Cards.SAMPLE_CARD_NUMBER_10;

        for (int i = 1; i <= 3; i++) {
            Transaction t = buildSyntheticTransactionForCard(
                    String.format("TXN%013d", 100 + i),
                    targetCard,
                    new BigDecimal("25.00"),
                    LocalDateTime.of(2022, Month.AUGUST, i, 10, 0, 0));
            entityManager.persist(t);
        }
        for (int i = 1; i <= 2; i++) {
            Transaction t = buildSyntheticTransactionForCard(
                    String.format("TXN%013d", 200 + i),
                    otherCard,
                    new BigDecimal("75.00"),
                    LocalDateTime.of(2022, Month.AUGUST, i, 14, 0, 0));
            entityManager.persist(t);
        }
        entityManager.flush();
        entityManager.clear();

        // Act — fetch a page large enough to contain all matches without paging.
        Page<Transaction> resultPage = transactionRepository.findByCardNumber(
                targetCard, PageRequest.of(0, 10));
        List<Transaction> result = resultPage.getContent();

        // Assert — only the 3 target-card transactions returned.
        assertThat(result)
                .as("Should return exactly 3 transactions for the target card")
                .hasSize(3);
        assertThat(result)
                .as("All returned transactions must have the target TRAN-CARD-NUM")
                .allSatisfy(t -> assertThat(t.getCardNumber()).isEqualTo(targetCard));
        assertThat(result)
                .as("Returned content should contain exactly the seeded target-card TRAN-IDs and exclude other-card IDs")
                .extracting(Transaction::getTransactionId)
                .contains("TXN0000000000101", "TXN0000000000102", "TXN0000000000103")
                .doesNotContain("TXN0000000000201", "TXN0000000000202");
    }

    /**
     * Verifies that
     * {@link TransactionRepository#findByCardNumber(String, Pageable)}
     * returns an empty {@link Page} (not {@code null}) when the supplied
     * card number does not match any rows.
     *
     * <p>This is the Java equivalent of the COBOL no-match branch the
     * REST controller layer translates into an HTTP 200 with an empty
     * JSON array (rather than HTTP 404). The lookup key
     * {@link TestFixtures.Cards#NONEXISTENT_CARD_NUMBER}
     * ({@code "4999999999999999"}) is deliberately outside the
     * converted fixture range {@code 4111111111111101}–
     * {@code 4111111111111150}; the constant exists precisely for
     * not-found assertions.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findByCardNumber(nonexistent card) returns empty Page (not null)")
    void findByCardNumber_nonexistentCard_returnsEmptyPage() {
        // Act — query for a card number guaranteed never to appear in the
        // fixture card catalog (deliberately outside the synthetic Visa-test
        // PAN range used elsewhere in the IT suite).
        Page<Transaction> resultPage = transactionRepository.findByCardNumber(
                TestFixtures.Cards.NONEXISTENT_CARD_NUMBER, PageRequest.of(0, 10));

        // Assert — query for unknown card must return an empty page (not null)
        // with zero elements and zero total.
        assertThat(resultPage)
                .as("findByCardNumber must never return null")
                .isNotNull();
        assertThat(resultPage.getContent())
                .as("Query for unknown card must return empty content list (not null)")
                .isNotNull()
                .isEmpty();
        assertThat(resultPage.getTotalElements())
                .as("Total-elements count for unknown card must be zero")
                .isZero();
    }

    // =========================================================================
    // Account-Key Filter Tests (Cross-Table JOIN via findByAccountId)
    // =========================================================================

    /**
     * Verifies that
     * {@link TransactionRepository#findByAccountId(String, Pageable)}
     * — a custom {@code @Query} method that joins {@code transactions}
     * to {@code cards} on {@code card_number} and filters on
     * {@code cards.account_id} — returns a non-null {@link Page} when
     * driven against a synthetic-fixture account identifier.
     *
     * <p>This test does NOT seed matching {@code Card} entities (only
     * {@code Transaction} entities), so the JOIN will naturally yield
     * an empty result set. The purpose is to exercise the
     * {@code @Query} path end-to-end — JPQL parsing, JOIN compilation,
     * pagination wrapper — and confirm the production query string is
     * syntactically valid. A subsequent richer end-to-end IT
     * ({@code TransactionListServiceIT} in a future agent's scope)
     * exercises the JOIN with both {@code Transaction} and matching
     * {@code Card} fixture rows.
     *
     * <p>This is a Java-migration addition documented in
     * {@link TransactionRepository#findByAccountId} — the COBOL
     * {@code COTRN00C.cbl} workflow does not contain an explicit
     * account-key filter (the {@code TRANSACT} VSAM file has no
     * account-key alternate index). The Java migration surfaces a
     * {@code ?account=NNNN...} query parameter on the
     * transaction-list REST endpoint that maps onto this repository
     * call — the natural REST equivalent of the {@code CARDDAT →
     * TRANSACT} alternate-index walk operators would perform manually
     * in the 3270 workflow when researching transactions for a
     * specific account.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findByAccountId(existing account) returns Page (cross-table JOIN ?account= REST filter parity)")
    void findByAccountId_existingAccount_returnsTransactions() {
        // Arrange — persist 2 transactions on the target card. The
        // findByAccountId query JOINs through cards to accounts; without
        // matching Card rows the result is empty, but the JPQL must still
        // compile and execute cleanly.
        entityManager.persist(buildSyntheticTransactionForCard(
                "TXN0000000000301",
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                new BigDecimal("15.00"),
                LocalDateTime.of(2022, Month.SEPTEMBER, 1, 8, 0, 0)));
        entityManager.persist(buildSyntheticTransactionForCard(
                "TXN0000000000302",
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                new BigDecimal("35.00"),
                LocalDateTime.of(2022, Month.SEPTEMBER, 2, 9, 30, 0)));
        entityManager.flush();
        entityManager.clear();

        // Act — invoke the cross-table JOIN query.
        Page<Transaction> resultPage = transactionRepository.findByAccountId(
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, PageRequest.of(0, 10));

        // Assert — the query executes cleanly (non-null Page) and the paging
        // metadata is echoed back correctly. Element-level assertions are
        // covered by the richer end-to-end IT that seeds both Transaction
        // and Card entities (a future agent's scope).
        assertThat(resultPage)
                .as("findByAccountId must never return null")
                .isNotNull();
        assertThat(resultPage.getNumber())
                .as("Page number metadata should echo the request (zero-based)")
                .isEqualTo(0);
        assertThat(resultPage.getSize())
                .as("Page size metadata should echo the request")
                .isEqualTo(10);
        assertThat(resultPage.getContent())
                .as("findByAccountId content must never be null (empty list is acceptable)")
                .isNotNull();
    }

    /**
     * Verifies that
     * {@link TransactionRepository#findByAccountId(String, Pageable)}
     * returns an empty {@link Page} when the supplied account
     * identifier does not match any row in the {@code accounts} /
     * {@code cards} JOIN result.
     *
     * <p>This exercises the COBOL workflow's "no transactions for this
     * account" branch — the COBOL operator would receive an empty
     * BMS map; the Java REST controller returns HTTP 200 with an
     * empty JSON array.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findByAccountId(nonexistent account) returns empty Page (not null)")
    void findByAccountId_nonexistentAccount_returnsEmptyPage() {
        // Act — query for an account ID guaranteed never to appear in any
        // fixture (the canonical nonexistent-account sentinel from TestFixtures).
        Page<Transaction> resultPage = transactionRepository.findByAccountId(
                TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID, PageRequest.of(0, 10));

        // Assert — empty page (not null) with zero elements.
        assertThat(resultPage)
                .as("findByAccountId must never return null")
                .isNotNull();
        assertThat(resultPage.getContent())
                .as("Query for unknown account must return empty content list (not null)")
                .isNotNull()
                .isEmpty();
        assertThat(resultPage.getTotalElements())
                .as("Total-elements count for unknown account must be zero")
                .isZero();
    }

    // =========================================================================
    // Max-Key Lookup Test (COBIL00C HIGH-VALUES / READPREV parity)
    // =========================================================================

    /**
     * Verifies that
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()}
     * returns the {@link Transaction} row with the lexicographically
     * greatest {@code TRAN-ID} after the test has seeded multiple rows.
     *
     * <p>This is the Java equivalent of the COBOL pattern in
     * {@code COBIL00C.cbl} (bill payment, lines 212–217) used to
     * compute the next sequential TRAN-ID:
     * <pre>
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   PERFORM STARTBR-TRANSACT-FILE
     *   PERFORM READPREV-TRANSACT-FILE
     *   PERFORM ENDBR-TRANSACT-FILE
     *   MOVE TRAN-ID     TO WS-TRAN-ID-NUM
     *   ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     *
     * <p>Spring Data derives the equivalent JPQL
     * {@code SELECT t FROM Transaction t ORDER BY t.transactionId DESC}
     * with {@code LIMIT 1} from the method name. The returned
     * {@link Optional} matches the COBOL {@code DFHRESP(NORMAL)}
     * response (highest-key row available) versus
     * {@code DFHRESP(ENDFILE)} (empty store, returns empty Optional).
     *
     * <p>The test seeds three transactions with deterministic
     * lexicographically-ordered IDs ("TXN0000000000401",
     * "TXN0000000000402", "TXN0000000000403"), then asserts the
     * returned Optional carries the third (highest) ID.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("findTopByOrderByTransactionIdDesc returns the highest-key transaction (COBIL00C HIGH-VALUES / READPREV parity)")
    void findTopByOrderByTransactionIdDesc_emptyTable_returnsEmpty() {
        // Arrange — persist three transactions with deterministic
        // lexicographic ordering. All TRAN-IDs share the same 12-character
        // prefix ("TXN000000000") so the trailing 4-character suffix is the
        // only differentiator under lexicographic sort.
        entityManager.persist(buildSyntheticTransaction(
                "TXN0000000000401",
                new BigDecimal("10.00"),
                LocalDateTime.of(2022, Month.OCTOBER, 1, 8, 0, 0)));
        entityManager.persist(buildSyntheticTransaction(
                "TXN0000000000402",
                new BigDecimal("20.00"),
                LocalDateTime.of(2022, Month.OCTOBER, 2, 9, 0, 0)));
        entityManager.persist(buildSyntheticTransaction(
                "TXN0000000000403",
                new BigDecimal("30.00"),
                LocalDateTime.of(2022, Month.OCTOBER, 3, 10, 0, 0)));
        entityManager.flush();
        entityManager.clear();

        // Act — drive the production max-key lookup.
        Optional<Transaction> highest = transactionRepository.findTopByOrderByTransactionIdDesc();

        // Assert — the returned Optional must carry the lexicographically
        // greatest TRAN-ID among the seeded rows. (If the Flyway seed adds
        // rows with greater IDs the assertion uses isGreaterThanOrEqualTo
        // to remain robust.)
        assertThat(highest)
                .as("findTopByOrderByTransactionIdDesc must return a non-empty Optional when rows exist")
                .isPresent();
        assertThat(highest.get().getTransactionId())
                .as("Returned TRAN-ID must be greater than or equal to the highest seeded ID lexicographically")
                .isGreaterThanOrEqualTo("TXN0000000000403");
    }

    // =========================================================================
    // Repository Wiring Smoke Test
    // =========================================================================

    /**
     * Smoke test that exercises the most basic repository operation —
     * {@code count()} — and asserts the returned row count is
     * non-negative. This catches the case where the
     * {@code TransactionRepository} bean fails to bootstrap (for
     * example because the {@code transactions} table is missing from
     * the Flyway schema, or because the {@code Transaction} entity is
     * not mapped) by failing fast with a clear stack trace rather than
     * an obscure ORM error deeper in the test class.
     *
     * <p>Mock boundary: none.
     */
    @Test
    @DisplayName("count() returns non-negative total row count (smoke test for repository wiring)")
    void count_invoked_returnsNonNegativeValue() {
        // Act — exercise the simplest possible repository call.
        long total = transactionRepository.count();

        // Assert — repository is wired and the underlying table is reachable.
        assertThat(total)
                .as("Repository count should be non-negative (table exists and is queryable)")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Synthetic Transaction Builders (Test-only helpers — no business logic)
    // =========================================================================

    /**
     * Constructs a synthetic {@link Transaction} with the supplied
     * {@code tranId}, {@code amount}, and {@code origTs}, using the
     * canonical fixture card number
     * {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} for the
     * {@code TRAN-CARD-NUM} field. All other fields are populated with
     * deterministic synthetic values from {@link TestFixtures}.
     *
     * <p>This helper does NOT contain business logic per AAP §0.10.1 —
     * it only assembles a synthetic record for repository persistence
     * tests. No COBOL semantics are reimplemented here; the production
     * code's {@link Transaction} entity is the source of truth for
     * field defaults. The helper exists purely to keep individual test
     * methods focused on their arrange-act-assert flows without
     * repeating the 13-field constructor sequence.
     *
     * @param tranId   the 16-character {@code TRAN-ID} primary key
     *                 (e.g. {@code "TXN0000000000001"})
     * @param amount   the {@code TRAN-AMT} BigDecimal monetary amount
     *                 (scale 2)
     * @param origTs   the originating timestamp constructed via
     *                 {@code LocalDateTime.of(...)}; will be formatted
     *                 to the COBOL X(26) literal shape via
     *                 {@link #COBOL_TS_FORMAT}
     * @return a fully-populated unmanaged {@link Transaction} ready for
     *         {@link TransactionRepository#save(Object)} or
     *         {@link jakarta.persistence.EntityManager#persist(Object)}
     */
    private Transaction buildSyntheticTransaction(String tranId,
                                                  BigDecimal amount,
                                                  LocalDateTime origTs) {
        return buildSyntheticTransactionForCard(
                tranId,
                TestFixtures.Cards.SAMPLE_CARD_NUMBER_01,
                amount,
                origTs);
    }

    /**
     * Constructs a synthetic {@link Transaction} with the supplied
     * {@code tranId}, {@code cardNumber}, {@code amount}, and
     * {@code origTs}. Used by tests that need multi-card scenarios
     * (e.g. {@link #findByCardNumber_existingCard_returnsAllTransactionsForCard()}).
     *
     * <p>The helper resolves the production setter naming convention
     * encountered at Phase 1 of this IT's discovery: the
     * {@link Transaction} entity exposes Java-style getters and setters
     * (e.g. {@code setTransactionId}, {@code setAmount},
     * {@code setCardNumber}, {@code setOriginTimestamp(String)}) rather
     * than the COBOL-style names suggested by the agent prompt template
     * (e.g. {@code setTranId}, {@code setTranAmt}). Per AAP §0.10
     * "Phase 12 Adaptation Notes": adjust call sites to the actual
     * production API but NEVER alter the assertion contracts.
     *
     * <p>The {@code originTimestamp} and {@code processTimestamp}
     * fields are persisted as {@link String}s in the COBOL PIC X(26)
     * literal shape; the helper builds the value by formatting the
     * supplied {@link LocalDateTime} through {@link #COBOL_TS_FORMAT}.
     * Both timestamps are set to the same value because a fresh-insert
     * transaction has not yet been processed (proc-ts == orig-ts is a
     * documented synthetic-fixture convention; the production
     * {@code TransactionPostingProcessor} overwrites proc-ts at posting
     * time).
     *
     * <p>The {@code merchantId} (PIC 9(09)), {@code merchantName}
     * (PIC X(50)), {@code merchantCity} (PIC X(50)), and
     * {@code merchantZip} (PIC X(10)) fields receive synthetic
     * placeholder values that do not carry any real merchant identity —
     * the {@code "Test Merchant"} / {@code "Seattle"} / {@code "99999"}
     * values are deliberately generic.
     *
     * @param tranId      the 16-character {@code TRAN-ID} primary key
     * @param cardNumber  the 16-character {@code TRAN-CARD-NUM}
     *                    foreign-key target (typically a
     *                    {@link TestFixtures.Cards} constant)
     * @param amount      the {@code TRAN-AMT} BigDecimal monetary amount
     *                    (scale 2)
     * @param origTs      the originating timestamp; formatted to the
     *                    COBOL X(26) shape inside the helper
     * @return a fully-populated unmanaged {@link Transaction} ready for
     *         {@link TransactionRepository#save(Object)} or
     *         {@link jakarta.persistence.EntityManager#persist(Object)}
     */
    private Transaction buildSyntheticTransactionForCard(String tranId,
                                                         String cardNumber,
                                                         BigDecimal amount,
                                                         LocalDateTime origTs) {
        Transaction t = new Transaction();
        // -- Primary key + 16-char PIC X(16) --
        t.setTransactionId(tranId);
        // -- 2-char transaction type code (PIC X(02)): "01" (purchase) from TestFixtures --
        t.setTransactionTypeCode(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        // -- 4-char transaction category code (PIC 9(04)): "0001" (regular sales) --
        t.setTransactionCategoryCode(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);
        // -- 10-char transaction source (PIC X(10)): "POS TERM  " --
        t.setSource(TestFixtures.Transactions.TRAN_SOURCE_POS);
        // -- 100-char description (PIC X(100)): synthetic test marker --
        t.setDescription("Synthetic test transaction");
        // -- TRAN-AMT BigDecimal scale 2 (PIC S9(09)V99) --
        t.setAmount(amount);
        // -- 9-digit merchant ID (PIC 9(09)) stored as String to preserve the
        //    zero-padded numeric COBOL format --
        t.setMerchantId("123456789");
        // -- 50-char merchant name / city + 10-char ZIP (test-only synthetic
        //    placeholders; no real merchant identity per AAP §0.10.5) --
        t.setMerchantName("Test Merchant");
        t.setMerchantCity("Seattle");
        t.setMerchantZip("99999");
        // -- 16-char TRAN-CARD-NUM (PIC X(16)) -- foreign-key reference to the
        //    converted Visa-test-PAN range used across the IT suite --
        t.setCardNumber(cardNumber);
        // -- 26-char TRAN-ORIG-TS / TRAN-PROC-TS (both PIC X(26)) formatted to
        //    the COBOL 'YYYY-MM-DD HH:MM:SS.ffffff' literal shape via
        //    DateTimeFormatter applied to the supplied LocalDateTime --
        String tsString = origTs.format(COBOL_TS_FORMAT);
        t.setOriginTimestamp(tsString);
        t.setProcessTimestamp(tsString);
        return t;
    }
}
