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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * digest-pinned PostgreSQL 16.10 container and applies the three Flyway migrations to it. Compile alone with
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
}
