/*
 ******************************************************************************
 * Program     : RepositorySchemaAndFinderIntegrationTest
 * Application : CardDemo
 * Type        : Java integration test (JUnit 5, Failsafe tier)
 * Function    : Proves against a real PostgreSQL 16 instance what a mocked
 *               repository cannot: that every derived finder name resolves to
 *               valid SQL, that the three alternate-index replacements exist and
 *               are non-unique, that the optimistic-lock columns sit on exactly
 *               the four entities the source needs them on, and that the Flyway
 *               seed reproduces the catalogued row counts.
 * Source      : app/catlg/LISTCAT.txt @ 7756d89 - cluster key lengths and the
 *               three alternate indexes the derived finders replace
 * Source      : app/data/ASCII/*.txt @ 7756d89 - the nine fixtures whose row
 *               counts V3__seed_data.sql reproduces
 * Source      : app/jcl/DUSRSECJ.jcl @ 7756d89 - the ten in-stream user records
 * Source      : app/cbl/COACTUPC.cbl:3888-4105 @ 7756d89 - the READ ... UPDATE
 *               pair the pessimistic finders replace
 ******************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************
 */
package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.repository.UserSecurityRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The repository tier's concrete assertions, executed by Failsafe against the containerised PostgreSQL 16
 * instance the {@link AbstractRepositoryIntegrationTest} parent starts.
 *
 * <h2>What it does, and why a unit test cannot</h2>
 *
 * <p>Every repository in this project is a Spring Data interface with no body. A Mockito double for one
 * proves only that the double was configured: it cannot fail when a derived finder is misnamed, when a
 * property path does not exist on the entity, when a hand-written {@code @Query} is invalid SQL, or when an
 * entity mapping disagrees with the deployed column type. All four of those are startup-time or query-time
 * failures that need a real database and a real context, which is precisely what this tier supplies.
 *
 * <p>The derived finders asserted here matter disproportionately because they are the replacements for the
 * three VSAM alternate indexes and for the sequential browses, and because the keyset finders were
 * introduced to remove page-number {@code OFFSET} scans. A keyset finder that returns the wrong window is
 * indistinguishable from a correct one when the repository is mocked, since the mock returns whatever the
 * stub was told to return. Here the rows come from the Flyway seed and the ordering comes from PostgreSQL.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run with {@code ./mvnw -B clean verify}, which executes this class in the {@code integration-test}
 * phase. A reachable Docker daemon is a hard prerequisite: the parent starts a
 * digest-pinned PostgreSQL 16.14 container and applies the three Flyway migrations to it. Compile alone with
 * {@code ./mvnw -q test-compile}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None of its own. The profile is {@code test} and the datasource properties are registered by the
 * parent from the container, so this class names no host, port, user or password. It adds no {@code static}
 * field, which the parent forbids.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Context fails to refresh before any assertion runs</dt>
 *   <dd>An entity mapping disagrees with the migration. {@code ddl-auto} is {@code validate}, so this is
 *       the intended loud failure; the message names the column and the two types.</dd>
 *   <dt>A row-count assertion fails</dt>
 *   <dd>{@code V3__seed_data.sql} changed. The counts here are the ones the parent documents as reliable;
 *       correct the migration or the parent's contract, not this assertion, until the fixture row count in
 *       {@code app/data/ASCII} is known to have changed.</dd>
 *   <dt>An index assertion fails</dt>
 *   <dd>{@code V2__create_indexes.sql} changed. A legacy alternate key is non-unique, so a unique index
 *       here would be a parity defect rather than an improvement.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be. JUnit instantiates one instance per test method, each method
 * runs inside the parent's transaction and is rolled back, and no state is shared between methods.
 */
@DisplayName("Repository tier against real PostgreSQL 16: schema contract and derived finders")
class RepositorySchemaAndFinderIntegrationTest extends AbstractRepositoryIntegrationTest {

    /** How many rows a keyset window requests, mirroring the readers' chunk-sized fetch. */
    private static final int WINDOW = 7;

    /** Account repository under test, carrying the keyset finder and the pessimistic finder. */
    @Autowired
    private AccountRepository accountRepository;

    /** Customer repository under test, carrying the keyset finder and the pessimistic finder. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Card repository under test, carrying the CARDAIX replacement and two ordered finders. */
    @Autowired
    private CardRepository cardRepository;

    /** Cross-reference repository under test, carrying the CXACAIX single-read replacement. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Transaction repository under test; its table is deliberately seeded empty. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Staging repository for the 300-row daily transaction fixture. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** Composite-key repository for the 50 seeded category balances. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Composite-key repository for the 51 seeded disclosure groups. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Composite-key repository for the 18 seeded transaction categories. */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /** Single-column-key repository for the 7 seeded transaction types. */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /** Repository for the ten in-stream users seeded from the legacy IEBGENER step. */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /** Reads catalogue metadata, which no repository exposes and no entity models. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The pool itself, needed by the two lock-contention tests and by nothing else here.
     *
     * <p>Those two take connections directly rather than through {@code JdbcTemplate} because contention
     * requires <em>two</em> sessions held open at once, which a template that borrows and returns per
     * statement cannot express. Taking them from this bean rather than opening raw JDBC connections is what
     * makes the assertion about the shipped configuration: these carry the same driver startup options the
     * application's own connections carry.
     */
    @Autowired
    private DataSource dataSource;

    /** The Flyway seed reproduces the fixture row counts the catalogue and the fixtures agree on. */
    @Nested
    @DisplayName("V3 seed: the row counts the nine ASCII fixtures and DUSRSECJ.jcl imply")
    class SeededRowCounts {

        @Test
        @DisplayName("every seeded table carries exactly the fixture's row count")
        void seededCountsMatchTheFixtures() {
            assertThat(accountRepository.count()).as("acctdata.txt is 50 records of 300 bytes").isEqualTo(50L);
            assertThat(cardRepository.count()).as("carddata.txt is 50 records of 150 bytes").isEqualTo(50L);
            assertThat(cardCrossReferenceRepository.count()).as("cardxref.txt is 50 records").isEqualTo(50L);
            assertThat(customerRepository.count()).as("custdata.txt is 50 records of 500 bytes").isEqualTo(50L);
            assertThat(dailyTransactionRepository.count())
                    .as("dailytran.txt is 300 records of 350 bytes - the Gate 1 fixture")
                    .isEqualTo(300L);
            assertThat(disclosureGroupRepository.count()).as("discgrp.txt is 51 records").isEqualTo(51L);
            assertThat(transactionCategoryBalanceRepository.count()).as("tcatbal.txt is 50").isEqualTo(50L);
            assertThat(transactionCategoryRepository.count()).as("trancatg.txt is 18").isEqualTo(18L);
            assertThat(transactionTypeRepository.count()).as("trantype.txt is 7").isEqualTo(7L);
            assertThat(userSecurityRepository.count())
                    .as("five administrators and five standard users, in-stream in DUSRSECJ.jcl")
                    .isEqualTo(10L);
        }

        @Test
        @DisplayName("the transaction table is seeded empty, because the posting job is what fills it")
        void transactionTableIsDeliberatelyEmpty() {
            assertThat(transactionRepository.count()).isZero();
            assertThat(transactionRepository.findFirstByOrderByTransactionIdDesc()).isEmpty();
        }

        @Test
        @DisplayName("no seeded credential is stored in the legacy plaintext form")
        void everySeededPasswordIsHashed() {
            List<String> stored = jdbcTemplate.queryForList(
                    "SELECT sec_usr_pwd FROM user_security", String.class);

            assertThat(stored).hasSize(10);
            // Proved POSITIVELY rather than by comparison against the plaintext. The single shared literal
            // plaintext value recorded at app/jcl/DUSRSECJ.jcl:35-44 is eight characters; a BCrypt digest at
            // the pinned strength is sixty and carries a version tag, a cost factor of 10 and a radix-64
            // salt and digest. Satisfying that envelope is structurally incompatible with holding the
            // plaintext, so the weaker "does not equal the plaintext" check is unnecessary - and writing the
            // plaintext in order to make it is itself what Rule 1 Clause D forbids, which names tests
            // explicitly. Asserted through a derived boolean so that no failure message can echo a digest.
            assertThat(stored).allMatch(value -> value.matches("^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$"));
        }
    }

    /**
     * The keyset finders that replaced page-number {@code OFFSET} scans in the four sequential readers.
     * Each is exercised against seeded rows so that the ordering and the exclusive lower bound come from
     * PostgreSQL rather than from a stub.
     */
    @Nested
    @DisplayName("Keyset finders: the OFFSET replacements, ordered and bounded by the database")
    class KeysetFinders {

        @Test
        @DisplayName("the account keyset finder walks the whole table in ascending key order with no gap or repeat")
        void accountKeysetFinderPagesTheWholeTable() {
            List<Long> walked = new ArrayList<>();
            long cursor = -1L;
            while (true) {
                List<Account> window = accountRepository
                        .findByAccountIdGreaterThanOrderByAccountIdAsc(
                                Long.valueOf(cursor), PageRequest.ofSize(WINDOW));
                if (window.isEmpty()) {
                    break;
                }
                assertThat(window).hasSizeLessThanOrEqualTo(WINDOW);
                for (Account account : window) {
                    walked.add(account.getAccountId());
                }
                cursor = window.get(window.size() - 1).getAccountId().longValue();
            }

            List<Long> expected = accountRepository.findAll().stream()
                    .map(Account::getAccountId).sorted().toList();
            assertThat(walked).hasSize(50).isEqualTo(expected).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the account keyset bound is exclusive, so resuming from a key never re-emits it")
        void accountKeysetBoundIsExclusive() {
            List<Long> ordered = accountRepository.findAll().stream()
                    .map(Account::getAccountId).sorted().toList();
            Long third = ordered.get(2);

            List<Account> resumed = accountRepository.findByAccountIdGreaterThanOrderByAccountIdAsc(
                    third, PageRequest.ofSize(WINDOW));

            assertThat(resumed).isNotEmpty();
            assertThat(resumed.get(0).getAccountId()).isEqualTo(ordered.get(3));
            assertThat(resumed).noneMatch(account -> account.getAccountId().equals(third));
        }

        @Test
        @DisplayName("the customer keyset finder orders ascending and excludes its bound")
        void customerKeysetFinderIsOrderedAndExclusive() {
            List<Long> ordered = customerRepository.findAll().stream()
                    .map(Customer::getCustomerId).sorted().toList();

            List<Customer> fromStart = customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    Long.valueOf(-1L), PageRequest.ofSize(WINDOW));
            assertThat(fromStart).isNotEmpty();
            assertThat(fromStart.get(0).getCustomerId()).isEqualTo(ordered.get(0));

            List<Customer> resumed = customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    ordered.get(0), PageRequest.ofSize(WINDOW));
            assertThat(resumed.get(0).getCustomerId()).isEqualTo(ordered.get(1));
        }

        @Test
        @DisplayName("the card keyset finder seeds from the empty string, which precedes every CHAR(16) value")
        void cardKeysetFinderSeedsFromEmptyString() {
            List<String> ordered = cardRepository.findAll().stream()
                    .map(Card::getCardNumber).sorted().toList();

            List<Card> fromStart = cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    "", PageRequest.ofSize(WINDOW));

            assertThat(fromStart).isNotEmpty();
            assertThat(fromStart.get(0).getCardNumber()).isEqualTo(ordered.get(0));
            assertThat(fromStart).hasSizeLessThanOrEqualTo(WINDOW);
        }

        @Test
        @DisplayName("the cross-reference keyset finder walks all 50 rows in card-number order")
        void crossReferenceKeysetFinderWalksEveryRow() {
            List<String> walked = new ArrayList<>();
            String cursor = "";
            while (true) {
                List<CardCrossReference> window = cardCrossReferenceRepository
                        .findByCardNumberGreaterThanOrderByCardNumberAsc(cursor, PageRequest.ofSize(WINDOW));
                if (window.isEmpty()) {
                    break;
                }
                for (CardCrossReference reference : window) {
                    walked.add(reference.getCardNumber());
                }
                cursor = window.get(window.size() - 1).getCardNumber();
            }

            assertThat(walked).hasSize(50).doesNotHaveDuplicates().isSorted();
        }
    }

    /** The three VSAM alternate indexes, as derived finders and as physical B-tree indexes. */
    @Nested
    @DisplayName("Alternate index replacements: CARDAIX, CXACAIX and the processing-timestamp index")
    class AlternateIndexReplacements {

        @Test
        @DisplayName("CXACAIX is a single keyed read returning at most one row, never a browse")
        void crossReferenceByAccountReturnsTheLowestCardNumber() {
            CardCrossReference any = cardCrossReferenceRepository.findAll().get(0);

            Optional<CardCrossReference> found =
                    cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(any.getAccountId());

            assertThat(found).isPresent();
            List<String> everyCardForThatAccount = cardCrossReferenceRepository.findAll().stream()
                    .filter(reference -> reference.getAccountId().equals(any.getAccountId()))
                    .map(CardCrossReference::getCardNumber)
                    .sorted()
                    .toList();
            assertThat(found.get().getCardNumber()).isEqualTo(everyCardForThatAccount.get(0));
        }

        @Test
        @DisplayName("an account with no cross-reference row yields an empty Optional, not an exception")
        void crossReferenceByUnknownAccountIsEmpty() {
            assertThat(cardCrossReferenceRepository
                    .findFirstByAccountIdOrderByCardNumberAsc(Long.valueOf(99_999_999_999L)))
                    .isEmpty();
        }

        @Test
        @DisplayName("CARDAIX is genuinely browsed, so its finder pages and counts")
        void cardsByAccountArePagedInCardNumberOrder() {
            Card seed = cardRepository.findAll().get(0);

            var page = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    seed.getAccountId(), PageRequest.of(0, WINDOW));

            assertThat(page.getContent()).isNotEmpty();
            assertThat(page.getContent()).extracting(Card::getCardNumber).isSorted();
            assertThat(page.getContent()).allMatch(card -> card.getAccountId().equals(seed.getAccountId()));
        }

        @Test
        @DisplayName("V2 creates exactly the three documented non-unique B-tree indexes and no unique one")
        void theThreeAlternateIndexesExistAndAreNonUnique() {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() "
                            + "AND indexname IN ('idx_card_acct_id', 'idx_card_cross_reference_acct_id', "
                            + "'idx_transaction_proc_ts') ORDER BY indexname",
                    String.class);

            assertThat(names).containsExactly(
                    "idx_card_acct_id", "idx_card_cross_reference_acct_id", "idx_transaction_proc_ts");

            Long uniqueCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                            + "WHERE c.relname IN ('idx_card_acct_id', 'idx_card_cross_reference_acct_id', "
                            + "'idx_transaction_proc_ts') AND i.indisunique",
                    Long.class);
            assertThat(uniqueCount).as("a legacy alternate key is non-unique").isZero();
        }
    }

    /** The pessimistic and optimistic locking the dual-dataset write depends on. */
    @Nested
    @DisplayName("Locking: the READ ... UPDATE replacements and the version columns")
    class Locking {

        @Test
        @DisplayName("the account for-update finder executes as valid SQL and returns the row")
        void accountForUpdateFinderResolves() {
            Long id = accountRepository.findAll().get(0).getAccountId();

            Optional<Account> locked = accountRepository.findByIdForUpdate(id);

            assertThat(locked).isPresent();
            assertThat(locked.get().getAccountId()).isEqualTo(id);
        }

        @Test
        @DisplayName("the customer for-update finder executes as valid SQL and returns the row")
        void customerForUpdateFinderResolves() {
            Long id = customerRepository.findAll().get(0).getCustomerId();

            assertThat(customerRepository.findByIdForUpdate(id))
                    .isPresent()
                    .get()
                    .extracting(Customer::getCustomerId)
                    .isEqualTo(id);
        }

        @Test
        @DisplayName("the card for-update finder executes as valid SQL and returns the row")
        void cardForUpdateFinderResolves() {
            String cardNumber = cardRepository.findAll().get(0).getCardNumber();

            assertThat(cardRepository.findByIdForUpdate(cardNumber)).isPresent();
        }

        @Test
        @DisplayName("a for-update finder on an absent key is empty rather than throwing")
        void forUpdateOnAbsentKeyIsEmpty() {
            assertThat(accountRepository.findByIdForUpdate(Long.valueOf(99_999_999_999L))).isEmpty();
        }

        @Test
        @DisplayName("among the eleven business tables, exactly four carry an optimistic-lock version column")
        void versionColumnSitsOnExactlyFourBusinessTables() {
            // Scoped to the eleven tables V1 creates, deliberately. Framework-owned tables also declare a
            // column named "version" with an unrelated meaning: Spring Batch's batch_job_execution,
            // batch_job_instance and batch_step_execution, created by the framework's own script because the
            // test profile sets spring.batch.jdbc.initialize-schema to always, and Flyway's
            // flyway_schema_history. An unfiltered census therefore returns eight tables and says nothing
            // about the entity mappings, which is what this assertion is actually about.
            List<String> businessTables = List.of("account", "card", "card_cross_reference", "customer",
                    "daily_transaction", "disclosure_group", "transaction", "transaction_category",
                    "transaction_category_balance", "transaction_type", "user_security");

            List<String> withVersion = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND column_name = 'version' "
                            + "AND table_name = ANY (?) ORDER BY table_name",
                    String.class,
                    // Cast to Object so the array is ONE bind parameter for "= ANY (?)" rather than being
                    // spread across the varargs positions, which javac warns about as an inexact-type
                    // non-varargs call and -Werror turns into a build failure.
                    (Object) businessTables.toArray(new String[0]));

            assertThat(withVersion)
                    .as("COACTUPC rewrites account and customer in one unit of work; the card and "
                            + "transaction rows are the other two the source updates in place")
                    .containsExactly("account", "card", "customer", "transaction");
        }

        /**
         * The shipped lock ceiling actually reaches a pooled session, and survives a rollback.
         *
         * <p><strong>Why this test exists.</strong> The ceiling is asserted at the configuration tier by
         * {@code src/test/java/com/cardemo/unit/config/DataTierPrincipalContractTest.java}, which reads the
         * profile text. That tier can prove the option is <em>written</em>; only this tier can prove it is
         * <em>in effect</em>. The difference is not academic: the profile carries a long comment recording
         * that the obvious delivery mechanism, {@code connection-init-sql}, is written correctly and is
         * measurably useless, because PostgreSQL's {@code SET} is transactional and Hikari runs the
         * statement inside a transaction that is then rolled back. A configuration test cannot tell those
         * two spellings apart. This one reads the value out of the running session.
         *
         * <p>The ceiling is read rather than restated, so the assertion is that whatever the profile ships
         * is positive and present - {@code 0} is PostgreSQL's spelling of "wait forever" and is exactly the
         * defect the control exists to remove, so it fails here rather than being reported as a value.
         *
         * <p>The rollback half reproduces the profile's own claim, which is otherwise a claim about a
         * measurement nobody can re-run: after an explicit {@code BEGIN} and {@code ROLLBACK} the session
         * must still report the ceiling. Had the setting been delivered by a statement inside a
         * transaction, this is the assertion that would catch it.
         *
         * @throws SQLException if the session cannot be interrogated, which is itself a failure
         */
        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("the shipped lock ceiling is in effect on a pooled session and survives a rollback")
        void theShippedLockCeilingIsInEffectOnAPooledSession() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                final int ceilingMillis = sessionLockTimeoutMillis(connection);

                assertThat(ceilingMillis)
                        .as("lock_timeout read out of the running session with "
                                + "SELECT setting FROM pg_settings. Zero is PostgreSQL's spelling of WAIT "
                                + "FOREVER: it is what the engine defaults to, what connection-init-sql "
                                + "silently leaves behind, and the precise defect the two authored lock "
                                + "outcomes of app/cbl/COACTUPC.cbl:517-520 are unreachable without")
                        .isPositive();

                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
                connection.rollback();
                connection.setAutoCommit(true);

                assertThat(sessionLockTimeoutMillis(connection))
                        .as("the ceiling must survive a rollback. PostgreSQL's SET is transactional, so a "
                                + "ceiling delivered by a statement inside a transaction reverts to 0 here "
                                + "while every configuration-text assertion still passes. Delivered as a "
                                + "driver startup option it is applied before any transaction exists and "
                                + "cannot be reverted")
                        .isEqualTo(ceilingMillis);
            }
        }

        /**
         * A row already locked elsewhere fails <strong>within the ceiling</strong> instead of stalling.
         *
         * <p>This is the behaviour the whole control exists for, and until now nothing executed it: the
         * claim was carried by a configuration-text assertion, which cannot distinguish a bounded wait from
         * an unbounded one. Two sessions are held open at once here - the first holds a row lock, the second
         * asks for the same row - and the second's failure is measured.
         *
         * <p><strong>Three things are asserted and each rules out a different wrong outcome.</strong> The
         * SQL state must be {@code 55P03}, {@code lock_not_available}, which is what the ceiling raises and
         * what Hibernate maps to a lock-timeout and Spring to {@code CannotAcquireLockException} - the
         * family {@code AccountUpdateService} turns into the authored lock outcome. The wait must be at
         * least most of the ceiling, which rules out {@code NOWAIT} or {@code SKIP LOCKED} answering
         * immediately for an unrelated reason. And it must be bounded well below the point where a caller
         * would call it a stall, which is the defect itself.
         *
         * <p>No row is modified: both sessions take a shared-nothing {@code SELECT ... FOR UPDATE} and both
         * roll back. Cleanup is symmetric and runs whatever the outcome, because a leaked row lock would
         * make every later test in the tier wait five seconds and then fail in a way that pointed nowhere
         * near here. The test declares no transaction of its own for the same reason it takes its own
         * connections: the harness's rollback-per-test would otherwise own one of the two sessions.
         *
         * @throws SQLException if either session cannot be established, which is itself a failure
         */
        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("a row locked by another session fails with 55P03 inside the ceiling, and does not stall")
        void aContendedRowFailsWithinTheCeilingRatherThanStalling() throws SQLException {
            final Long contestedAccount = accountRepository.findAll().get(0).getAccountId();

            try (Connection holder = dataSource.getConnection();
                    Connection contender = dataSource.getConnection()) {
                final int ceilingMillis = sessionLockTimeoutMillis(contender);
                assertThat(ceilingMillis)
                        .as("the contending session must carry a ceiling, or this test would hang rather "
                                + "than fail and would prove the opposite of what it claims")
                        .isPositive();

                holder.setAutoCommit(false);
                contender.setAutoCommit(false);
                try {
                    lockAccountRow(holder, contestedAccount);

                    final long startedAt = System.nanoTime();
                    final SQLException refusal = catchLockRefusal(contender, contestedAccount);
                    final Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

                    // Asserted as a boolean rather than on the object: SQLException implements
                    // Iterable<Throwable>, so assertThat(refusal) is ambiguous between the iterable and the
                    // object overload and does not compile.
                    assertThat(refusal != null)
                            .as("the second session must be refused. If it succeeded, the first session's "
                                    + "FOR UPDATE did not take a row lock and this test proves nothing")
                            .isTrue();
                    assertThat(refusal.getSQLState())
                            .as("55P03 lock_not_available is what an expired lock_timeout raises, and it is "
                                    + "the state Hibernate maps to LockTimeoutException and Spring to "
                                    + "CannotAcquireLockException - the family the account-update path "
                                    + "turns into COULD-NOT-LOCK-ACCT-FOR-UPDATE. Waited %s",
                                    waited)
                            .isEqualTo("55P03");
                    assertThat(waited.toMillis())
                            .as("the refusal must come from the ceiling expiring, not from an immediate "
                                    + "NOWAIT-style answer that would have refused a row nobody held. "
                                    + "Ceiling %d ms",
                                    Integer.valueOf(ceilingMillis))
                            .isGreaterThanOrEqualTo(ceilingMillis - (ceilingMillis / 5));
                    assertThat(waited.toMillis())
                            .as("and it must be BOUNDED: an unbounded wait is the defect, and it is what "
                                    + "the engine does by default. Ceiling %d ms",
                                    Integer.valueOf(ceilingMillis))
                            .isLessThan(ceilingMillis * 4L);
                } finally {
                    contender.rollback();
                    holder.rollback();
                }
            }
        }

        /**
         * Reads {@code lock_timeout} out of one live session, in milliseconds.
         *
         * <p>{@code pg_settings.setting} is used rather than {@code SHOW lock_timeout} because the former
         * reports the value in the setting's own unit as a bare number, while the latter renders it with a
         * unit suffix that changes shape with magnitude - {@code 5s} for five thousand milliseconds - and
         * would need parsing rules this assertion has no business owning.
         *
         * @param connection the session to interrogate; must be open
         * @return the session's ceiling in milliseconds, {@code 0} meaning wait forever
         * @throws SQLException if the query cannot be executed
         */
        private int sessionLockTimeoutMillis(final Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement();
                    java.sql.ResultSet result = statement
                            .executeQuery("SELECT setting FROM pg_settings WHERE name = 'lock_timeout'")) {
                assertThat(result.next()).as("pg_settings must report lock_timeout").isTrue();
                return Integer.parseInt(result.getString(1));
            }
        }

        /**
         * Takes a row lock on one account in the given session, leaving the transaction open.
         *
         * @param connection the session that will hold the lock; must have auto-commit off
         * @param accountId  the row to lock
         * @throws SQLException if the lock cannot be taken, which means the test cannot proceed
         */
        private void lockAccountRow(final Connection connection, final Long accountId) throws SQLException {
            try (PreparedStatement statement = connection
                    .prepareStatement("SELECT acct_id FROM account WHERE acct_id = ? FOR UPDATE")) {
                statement.setLong(1, accountId.longValue());
                try (java.sql.ResultSet result = statement.executeQuery()) {
                    assertThat(result.next())
                            .as("the row this test contends over must exist before it is contended")
                            .isTrue();
                }
            }
        }

        /**
         * Attempts the same row lock in a second session and returns the refusal it produced.
         *
         * <p>Returns the exception rather than letting it propagate, so the caller can measure how long the
         * attempt took and assert on the state - and so that a <em>successful</em> attempt is reportable as
         * {@code null} rather than passing silently, which would be the vacuous outcome.
         *
         * @param connection the contending session; must have auto-commit off
         * @param accountId  the row already locked elsewhere
         * @return the refusal, or {@code null} if the lock was unexpectedly granted
         */
        private SQLException catchLockRefusal(final Connection connection, final Long accountId) {
            try (PreparedStatement statement = connection
                    .prepareStatement("SELECT acct_id FROM account WHERE acct_id = ? FOR UPDATE")) {
                statement.setLong(1, accountId.longValue());
                try (java.sql.ResultSet result = statement.executeQuery()) {
                    result.next();
                }
                return null;
            } catch (final SQLException refused) {
                return refused;
            }
        }

        @Test
        @DisplayName("all eleven business tables exist, so no entity is mapped to a missing table")
        void allElevenBusinessTablesExist() {
            Long present = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                            + "AND table_name = ANY (?)",
                    Long.class,
                    // Cast for the same reason as above: one array-valued bind parameter, not eleven.
                    (Object) new String[] {"account", "card", "card_cross_reference", "customer",
                        "daily_transaction", "disclosure_group", "transaction", "transaction_category",
                        "transaction_category_balance", "transaction_type", "user_security"});

            assertThat(present).isEqualTo(11L);
        }
    }

    /** The three composite VSAM keys, loaded by embedded identifier against real rows. */
    @Nested
    @DisplayName("Composite keys: the three clusters whose key spans more than one field")
    class CompositeKeys {

        @Test
        @DisplayName("a category balance loads by its three-part embedded identifier")
        void categoryBalanceLoadsByEmbeddedId() {
            TransactionCategoryBalance seeded = transactionCategoryBalanceRepository.findAll().get(0);
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                    seeded.getId().getAccountId(), seeded.getId().getTypeCd(), seeded.getId().getCatCd());

            assertThat(transactionCategoryBalanceRepository.findById(id)).isPresent();
        }

        @Test
        @DisplayName("a disclosure group loads by its three-part embedded identifier")
        void disclosureGroupLoadsByEmbeddedId() {
            DisclosureGroup seeded = disclosureGroupRepository.findAll().get(0);
            DisclosureGroupId id = new DisclosureGroupId(seeded.getId().getAccountGroupId(),
                    seeded.getId().getTranTypeCd(), seeded.getId().getTranCatCd());

            assertThat(disclosureGroupRepository.findById(id)).isPresent();
        }

        @Test
        @DisplayName("the DEFAULT disclosure group exists, which the interest job's fallback requires")
        void defaultDisclosureGroupIsSeeded() {
            long defaults = disclosureGroupRepository.findAll().stream()
                    .filter(group -> "DEFAULT".equals(group.getId().getAccountGroupId().trim()))
                    .count();

            assertThat(defaults)
                    .as("a missing DEFAULT row abends the interest job at CBACT04C:L446")
                    .isPositive();
        }

        @Test
        @DisplayName("a transaction category loads by its two-part embedded identifier")
        void transactionCategoryLoadsByEmbeddedId() {
            TransactionCategory seeded = transactionCategoryRepository.findAll().get(0);
            TransactionCategoryId id = new TransactionCategoryId(
                    seeded.getId().getTranTypeCd(), seeded.getId().getTranCatCd());

            assertThat(transactionCategoryRepository.findById(id)).isPresent();
        }
    }

    /** Ordered browse replacements that must return their window in key order. */
    @Nested
    @DisplayName("Ordered browse replacements: the Slice-returning finders")
    class OrderedBrowses {

        @Test
        @DisplayName("the user browse returns a bounded window in identifier order")
        void userBrowseIsOrderedAndBounded() {
            var slice = userSecurityRepository.findAllByOrderBySecUsrIdAsc(PageRequest.ofSize(4));

            assertThat(slice.getContent()).hasSize(4);
            assertThat(slice.hasNext()).as("ten users, four requested").isTrue();
        }

        @Test
        @DisplayName("the card browse returns a bounded window in card-number order")
        void cardBrowseIsOrderedAndBounded() {
            var slice = cardRepository.findAllByOrderByCardNumberAsc(PageRequest.ofSize(WINDOW));

            assertThat(slice.getContent()).hasSize(WINDOW);
            assertThat(slice.getContent()).extracting(Card::getCardNumber).isSorted();
        }

        @Test
        @DisplayName("the category balance browse orders by all three key parts")
        void categoryBalanceBrowseIsOrdered() {
            var slice = transactionCategoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(PageRequest.ofSize(10));

            assertThat(slice.getContent()).hasSize(10);
            assertThat(slice.getContent())
                    .extracting(balance -> balance.getId().getAccountId())
                    .isSorted();
        }

        @Test
        @DisplayName("the daily transaction browse orders by ingest sequence over all 300 rows")
        void dailyTransactionBrowseIsOrdered() {
            var slice = dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(PageRequest.ofSize(25));

            assertThat(slice.getContent()).hasSize(25);
            assertThat(slice.hasNext()).as("300 seeded rows, 25 requested").isTrue();
        }
    }

    /**
     * The eleven business tables, supplied to the metadata matrix as one parameterized case each.
     *
     * <p>Delegates to {@link SchemaMetadataMatrix#businessTables()} rather than restating the list, so the
     * set of tables asserted and the set of tables declared cannot diverge. Declared {@code static} because
     * a {@code @MethodSource} factory must be, and on the outer class because a nested class may not hold
     * one.
     *
     * @return the table names, never empty
     */
    static List<String> businessTables() {
        return SchemaMetadataMatrix.businessTables();
    }

    /**
     * The authoritative metadata contract for the whole schema, driven table by table.
     *
     * <p><strong>Finding, severity High, RESOLVED.</strong> This class previously asserted three metadata
     * facts - that the eleven tables exist, that exactly three non-unique alternate indexes exist, and that
     * exactly four tables carry a version column - and each of those is true and none of them is a contract.
     * None could detect type-compatible drift: a widened {@code CHAR}, a lost decimal scale, a reordered
     * composite key, a retargeted foreign key or a dropped check constraint would all have left this class,
     * and the whole tier, green. For a migration whose contract is that every width comes from a frozen
     * picture clause, that was the tier's largest blind spot.
     *
     * <p><em>Remediation, applied:</em> {@link SchemaMetadataMatrix} declares every facet of every column,
     * key, index and constraint once, and the parameterized test below drives it across all eleven tables.
     * The three earlier assertions are kept rather than replaced - they say something the matrix does not,
     * namely that the counts are three and four <em>schema-wide</em> - and every CRUD, finder, locking and
     * composite-key test in this class remains a separate behavioural layer. A metadata contract and a
     * behavioural contract fail for different reasons and reading which one broke is the point.
     */
    @Nested
    @DisplayName("Metadata matrix: the complete column, key, index and constraint contract for all 11 tables")
    class MetadataMatrix {

        /**
         * Every facet of one table's catalogue metadata matches its declared contract.
         *
         * <p>One case per table, so a failure names the table in the report rather than burying it in a loop.
         * The assertions themselves live in {@link SchemaMetadataMatrix#assertTableMatches} so that the
         * per-table repository tests hold the identical contract instead of a paraphrase of it.
         *
         * @param table the business table under test, supplied by {@link #businessTables()}
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.integration.repository.RepositorySchemaAndFinderIntegrationTest"
                + "#businessTables")
        @DisplayName("every column, key, index and constraint matches the declared contract")
        void everyFacetMatchesTheDeclaredContract(final String table) {
            SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, table);
        }

        /**
         * The matrix covers the whole schema and nothing beyond it, so no table can be quietly exempted.
         *
         * <p>Without this, a twelfth table could be added with no contract and the parameterized test above
         * would simply not run for it - a gap that produces no failure and no output, which is the worst
         * shape a gap can take.
         */
        @Test
        @DisplayName("the matrix declares a contract for exactly the eleven business tables, no more and no "
                + "fewer")
        void theMatrixCoversExactlyTheBusinessTables() {
            List<String> declared = SchemaMetadataMatrix.businessTables();

            List<String> present = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' "
                            + "AND table_name NOT LIKE 'batch%' AND table_name <> 'flyway_schema_history' "
                            + "ORDER BY table_name",
                    String.class);

            assertThat(declared)
                    .as("the declared set and the live set must agree exactly: a table with no contract is "
                            + "an unasserted table, and a contract with no table is a stale one")
                    .containsExactlyInAnyOrderElementsOf(present);
        }

        /**
         * The schema carries exactly eighty-seven business columns, every one of them {@code NOT NULL}.
         *
         * <p>The per-table assertion establishes that no column of <em>that</em> table is nullable; this one
         * establishes the total, so a column dropped from one table cannot be balanced by one added to
         * another. Eighty-eight is a measured figure, not a target: it became eighty-eight when
         * {@code card_cvv_cd} was added, the {@code CARD-CVV-CD PIC 9(03)} of {@code app/cpy/CVACT02Y.cpy}
         * having been absent from the schema while the copybook reserves three bytes for it.
         */
        @Test
        @DisplayName("the eleven tables carry exactly 88 columns and not one of them is nullable")
        void theSchemaCarriesEightyEightNotNullColumns() {
            Long total = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns c "
                            + "JOIN information_schema.tables t ON t.table_name = c.table_name "
                            + "AND t.table_schema = c.table_schema "
                            + "WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE' "
                            + "AND c.table_name NOT LIKE 'batch%' "
                            + "AND c.table_name <> 'flyway_schema_history'",
                    Long.class);

            Long nullable = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns c "
                            + "JOIN information_schema.tables t ON t.table_name = c.table_name "
                            + "AND t.table_schema = c.table_schema "
                            + "WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE' "
                            + "AND c.table_name NOT LIKE 'batch%' "
                            + "AND c.table_name <> 'flyway_schema_history' AND c.is_nullable = 'YES'",
                    Long.class);

            assertThat(total)
                    .as("the sum of the eleven per-table column contracts, asserted as a total so that a "
                            + "column moved between tables cannot cancel out")
                    .isEqualTo(88L);
            assertThat(nullable)
                    .as("a COBOL record has no absent field: a fixed-width field is spaces or zeros, never "
                            + "nothing, so NOT NULL on every column is the field contract and not a "
                            + "preference")
                    .isZero();
        }

        /**
         * The schema declares exactly ten foreign keys, named {@code fk01} through {@code fk10}.
         *
         * <p>The per-table contracts name each one; this asserts the census, so a key deleted from one table
         * cannot be hidden by a key added to another.
         */
        @Test
        @DisplayName("the schema declares exactly the ten named foreign keys fk01 through fk10")
        void theSchemaDeclaresExactlyTenNamedForeignKeys() {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT con.conname FROM pg_constraint con "
                            + "JOIN pg_class cl ON cl.oid = con.conrelid "
                            + "WHERE con.contype = 'f' "
                            + "AND cl.relnamespace = current_schema()::regnamespace "
                            + "AND cl.relname NOT LIKE 'batch%' ORDER BY con.conname",
                    String.class);

            assertThat(names)
                    .as("ten referential rules replace the ten the VSAM clusters had no way to declare")
                    .containsExactly("fk01_card_account", "fk02_xref_customer", "fk03_xref_account",
                            "fk04_transaction_card", "fk05_transaction_type", "fk06_transaction_category",
                            "fk07_tcatbal_account", "fk08_tcatbal_category", "fk09_category_type",
                            "fk10_discgrp_category");
        }

        /**
         * The schema declares exactly five named check constraints and no unique constraint at all.
         */
        @Test
        @DisplayName("the schema declares exactly five check constraints and zero unique constraints")
        void theSchemaDeclaresFiveChecksAndNoUniqueConstraints() {
            List<String> checks = jdbcTemplate.queryForList(
                    "SELECT con.conname FROM pg_constraint con "
                            + "JOIN pg_class cl ON cl.oid = con.conrelid "
                            + "WHERE con.contype = 'c' "
                            + "AND cl.relnamespace = current_schema()::regnamespace "
                            + "AND cl.relname NOT LIKE 'batch%' "
                            + "AND con.conname NOT LIKE '%\\_not\\_null' ORDER BY con.conname",
                    String.class);

            Long uniqueConstraints = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.table_constraints "
                            + "WHERE table_schema = current_schema() AND constraint_type = 'UNIQUE' "
                            + "AND table_name NOT LIKE 'batch%'",
                    Long.class);

            assertThat(checks)
                    .as("each one carries an 88-level value set from a copybook, so a lost constraint means "
                            + "a value the source could never hold becomes storable")
                    .containsExactly("ck_account_active_status", "ck_card_active_status",
                            "ck_customer_pri_card_holder_ind", "ck_customer_ssn_numeric",
                            "ck_user_security_type");
            assertThat(uniqueConstraints)
                    .as("a VSAM alternate key is non-unique, so a unique constraint anywhere here would "
                            + "refuse rows the legacy system accepts")
                    .isZero();
        }
    }

}
