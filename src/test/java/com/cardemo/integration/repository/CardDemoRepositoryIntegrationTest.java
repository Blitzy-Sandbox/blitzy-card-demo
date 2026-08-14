/*
 * ****************************************************************************
 * Test        : CardDemoRepositoryIntegrationTest
 * Application : CardDemo
 * Type        : Java integration test - persistence tier
 * Function    : Exercise all eleven repositories against a real PostgreSQL 16
 *               instance, so that the mappings, the composite keys and the
 *               three alternate-index finders are proved by SQL the database
 *               actually executed rather than by a mocked repository.
 * Source      : app/catlg/LISTCAT.txt          - key lengths and record sizes
 *               app/catlg/LISTCAT.txt:L281-L284 - CARDDATA.VSAM.AIX, AXRKP 16
 *               app/csd/CARDDEMO.CSD           - the eight online files, which
 *                                                is why TCATBALF, DISCGRP,
 *                                                TRANCATG and TRANTYPE are
 *                                                batch-only
 *               src/main/resources/db/migration/V1__create_schema.sql
 *               src/main/resources/db/migration/V2__create_indexes.sql
 *               src/main/resources/db/migration/V3__seed_data.sql
 * ****************************************************************************
 *
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
 */

package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.CardCrossReference;
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
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Integration tests for the persistence tier.
 *
 * <p>A review recorded that Failsafe reported "No tests to run": both abstract integration bases existed and
 * neither had a concrete subclass, so the whole integration tier was configured, wired and never executed.
 * This class is one of the two subclasses that make the tier real, and it is deliberately broad rather than
 * deep - its job is to prove that every repository resolves against schema the database actually applied.
 *
 * <p><strong>What only an integration test can establish.</strong> A mocked repository proves that a service
 * calls the method it meant to call. It cannot prove the method exists in a form Spring Data can derive, that
 * the property path in its name matches the entity, that the column it targets exists with a compatible type,
 * or that a composite {@code @EmbeddedId} maps to the key the catalogue declares. Each of those is a startup
 * or first-execution failure in production and is invisible to the unit tier, which is precisely why the tier
 * that catches them must not be one that never runs.
 *
 * <p><strong>The derived finders under test are the three VSAM alternate indexes.</strong>
 * {@code CARDDATA.VSAM.AIX} has {@code KEYLEN 11, RKP 5, AXRKP 16}, placing its alternate key at byte 16 of
 * the base record; {@code CARDXREF.VSAM.AIX} indexes the cross reference the same way; and
 * {@code TRANSACT.VSAM.AIX} has {@code KEYLEN 26, AXRKP 304}, placing it on the processing timestamp. Each
 * became a derived finder plus a non-unique B-tree index in {@code V2__create_indexes.sql}, and a derived
 * finder that does not resolve is a context-startup failure - so exercising all three here is what turns the
 * migration's three {@code CREATE INDEX} statements into a checked claim.
 *
 * <p><strong>Read-only by construction.</strong> The base class is {@code @Transactional}, so every method
 * rolls back and the seeded rows are left exactly as {@code V3__seed_data.sql} wrote them. Assertions are
 * therefore expressed as invariants over the seed - counts, ordering, key shape - rather than over rows this
 * class inserts, which keeps any two methods independent of execution order.
 */
@DisplayName("Repository tier - all eleven repositories against a real PostgreSQL 16")
final class CardDemoRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

    /** Seeded row counts, from {@code V3__seed_data.sql}. */
    private static final int SEEDED_ACCOUNTS = 50;

    /** Seeded card rows. */
    private static final int SEEDED_CARDS = 50;

    /** Seeded customer rows. */
    private static final int SEEDED_CUSTOMERS = 50;

    /** Seeded cross-reference rows. */
    private static final int SEEDED_XREFS = 50;

    /** Seeded daily-transaction staging rows. */
    private static final int SEEDED_DAILY_TRANSACTIONS = 300;

    /** Seeded user rows: five administrators and five standard users. */
    private static final int SEEDED_USERS = 10;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /** Every repository is wired, which is itself the first thing that can fail. */
    @Nested
    @DisplayName("all eleven repositories are wired and resolve against the applied schema")
    final class AllRepositoriesResolve {

        @Test
        @DisplayName("the eleven beans are present, one per VSAM cluster in the catalogue")
        void everyRepositoryIsInjected() {
            assertThat(new Object[] {
                accountRepository, cardRepository, cardCrossReferenceRepository, customerRepository,
                transactionRepository, dailyTransactionRepository, transactionCategoryBalanceRepository,
                disclosureGroupRepository, transactionTypeRepository, transactionCategoryRepository,
                userSecurityRepository,
            })
                    .as("the catalogue lists exactly ten base clusters and USRSEC is defined in JCL, giving "
                            + "eleven repositories. A missing bean here is a context-startup failure that "
                            + "the unit tier cannot see, because it mocks these interfaces")
                    .hasSize(11)
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("the seeded row counts are what V3__seed_data.sql wrote")
        void seededCountsMatchTheMigration() {
            assertThat(accountRepository.count()).isEqualTo(SEEDED_ACCOUNTS);
            assertThat(cardRepository.count()).isEqualTo(SEEDED_CARDS);
            assertThat(customerRepository.count()).isEqualTo(SEEDED_CUSTOMERS);
            assertThat(cardCrossReferenceRepository.count()).isEqualTo(SEEDED_XREFS);
            assertThat(dailyTransactionRepository.count()).isEqualTo(SEEDED_DAILY_TRANSACTIONS);
            assertThat(userSecurityRepository.count()).isEqualTo(SEEDED_USERS);
        }

        @Test
        @DisplayName("the transaction table is seeded empty, because the posting job is what fills it")
        void transactionTableIsSeededEmpty() {
            assertThat(transactionRepository.count())
                    .as("V3 seeds no transaction row: the daily posting job writes them from the staging "
                            + "table, so a non-zero count here would mean the seed had pre-empted the very "
                            + "behaviour the batch tier exists to prove")
                    .isZero();
        }

        @Test
        @DisplayName("the four batch-only reference tables are populated")
        void batchOnlyReferenceTablesArePopulated() {
            assertThat(transactionCategoryBalanceRepository.count())
                    .as("TCATBALF has no CICS file definition, which is the evidence that it is batch-only")
                    .isPositive();
            assertThat(disclosureGroupRepository.count()).isPositive();
            assertThat(transactionTypeRepository.count()).isPositive();
            assertThat(transactionCategoryRepository.count()).isPositive();
        }
    }

    /** The three alternate indexes, as derived finders executing real SQL. */
    @Nested
    @DisplayName("the three VSAM alternate indexes, as derived finders")
    final class AlternateIndexFinders {

        @Test
        @DisplayName("CARDDATA.VSAM.AIX - cards by account, ordered by card number")
        void cardsByAccount() {
            final Long accountId = accountRepository
                    .findAll(PageRequest.of(0, 1))
                    .getContent()
                    .getFirst()
                    .getAccountId();

            final var page = cardRepository
                    .findByAccountIdOrderByCardNumberAsc(accountId, PageRequest.of(0, 7));

            assertThat(page)
                    .as("the alternate key sits at byte 16 of the 150-byte base record and became a "
                            + "non-unique B-tree index. A derived finder whose property path did not match "
                            + "the entity would have failed context startup, so reaching this assertion is "
                            + "itself part of the proof")
                    .isNotNull();
            assertThat(page.getContent())
                    .allSatisfy(card -> assertThat(card.getAccountId()).isEqualTo(accountId));
            assertThat(page.getSize())
                    .as("the card list page size is 7, from app/cbl/COCRDLIC.cbl:L177")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("CARDXREF.VSAM.AIX - the cross reference for an account resolves through the path")
        void crossReferencesByAccount() {
            final Long accountId = accountRepository
                    .findAll(PageRequest.of(0, 1))
                    .getContent()
                    .getFirst()
                    .getAccountId();

            final var row = cardCrossReferenceRepository
                    .findFirstByAccountIdOrderByCardNumberAsc(accountId);

            assertThat(row)
                    .as("every seeded account carries a cross reference, and CBACT04C:L393-L413 abends when "
                            + "one is missing, so an empty result here would be a seed defect rather than a "
                            + "tolerated outcome")
                    .isPresent();
            assertThat(row.orElseThrow().getAccountId()).isEqualTo(accountId);
            // The finder is derived as First...OrderByCardNumberAsc, so the engine sorts and then stops at
            // one row - the counterpart of EXEC CICS READ against the path, which yields the first record
            // carrying the alternate key rather than a set. The alternate key stays non-unique, and
            // OrderByCardNumberAsc is what makes "the first row" deterministic when duplicates exist.
            // Asserting isSorted over a single row would assert nothing, so the expectation is computed
            // from the whole seeded set instead: what the ordering clause promises is the lowest card
            // number on the account.
            final String lowestCardNumber = cardCrossReferenceRepository.findAll().stream()
                    .filter(candidate -> accountId.equals(candidate.getAccountId()))
                    .map(CardCrossReference::getCardNumber)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            assertThat(row.orElseThrow().getCardNumber())
                    .as("the ordering is part of the finder's name and therefore part of its contract")
                    .isEqualTo(lowestCardNumber);
        }

        @Test
        @DisplayName("TRANSACT.VSAM.AIX - the processing-timestamp range finder resolves and orders by card")
        void transactionsByProcessingTimestampRange() {
            final var slice = transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            "2022-01-01", "2023-01-01", PageRequest.of(0, 20));

            assertThat(slice)
                    .as("KEYLEN 26 at AXRKP 304 places this index on the processing timestamp. The "
                            + "transaction table is seeded empty, so the row set is empty - what this "
                            + "asserts is that the query itself is derivable and executable, which is the "
                            + "failure mode a mocked repository cannot reach")
                    .isNotNull();
            assertThat(slice.getContent()).isEmpty();
        }
    }

    /** Composite keys, which only a real mapping can validate. */
    @Nested
    @DisplayName("the three composite keys map to the catalogued key lengths")
    final class CompositeKeys {

        @Test
        @DisplayName("TCATBALF's three-part key orders by account, then type, then category")
        void categoryBalanceKeyOrdering() {
            final var slice = transactionCategoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(PageRequest.of(0, 10));

            assertThat(slice.getContent())
                    .as("the key is account plus type plus category, 17 bytes in the catalogue. The "
                            + "ordering is what makes the interest job's account-level control break "
                            + "correct, so it is asserted rather than assumed")
                    .isNotEmpty();
            assertThat(slice.getContent().stream()
                    .map(row -> row.getId().getAccountId())
                    .toList())
                    .isSorted();
        }

        @Test
        @DisplayName("DISCGRP's default-group fallback query resolves")
        void disclosureGroupDefaultFallbackResolves() {
            final var found = disclosureGroupRepository.findDefaultGroupRate("01", 1);

            assertThat(found)
                    .as("the fallback substitutes the literal DEFAULT group identifier and retries; "
                            + "app/data/ASCII/discgrp.txt seeds 17 DEFAULT rows, so this must be a "
                            + "resolvable query whether or not this particular pair is among them")
                    .isNotNull();
        }

        @Test
        @DisplayName("TRANCATG's two-part key is readable through its composite identifier")
        void transactionCategoryCompositeKey() {
            final var all = transactionCategoryRepository.findAll();

            assertThat(all).isNotEmpty();
            assertThat(all.getFirst().getId())
                    .as("a six-byte key of type plus category, per the catalogue")
                    .isNotNull();
        }
    }

    /** Ordering and paging contracts the online screens depend on. */
    @Nested
    @DisplayName("the paging and ordering contracts the screens depend on")
    final class PagingContracts {

        @Test
        @DisplayName("the descending-key browse used for identifier generation resolves")
        void descendingBrowseResolves() {
            final var highest = transactionRepository.findFirstByOrderByTransactionIdDesc();

            assertThat(highest)
                    .as("this replaces move-high-values, STARTBR, READPREV, ENDBR. On an empty table it is "
                            + "empty, which is exactly the case that makes the first generated identifier "
                            + "1 rather than a failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("the user list pages ten rows at a time, ordered by identifier")
        void userListPagesTen() {
            final var page = userSecurityRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(0, 10));

            assertThat(page.getContent())
                    .as("the user list page size is 10, from app/cbl/COUSR00C.cbl:L57")
                    .hasSize(10);
            assertThat(page.getContent().stream().map(row -> row.getSecUsrId()).toList()).isSorted();
        }

        @Test
        @DisplayName("the staging table reads in ingest order, which is the file order the reader needs")
        void stagingTableReadsInIngestOrder() {
            final var slice = dailyTransactionRepository
                    .findAllByOrderByIngestSequenceAsc(PageRequest.of(0, 25));

            assertThat(slice.getContent()).hasSize(25);
            assertThat(slice.getContent().stream().map(row -> row.getIngestSequence()).toList())
                    .as("the posting job must see the 300 fixture records in the order the file carried "
                            + "them, because the legacy reader was sequential")
                    .isSorted();
        }

        @Test
        @DisplayName("the card list pages seven rows at a time across the whole table")
        void cardListPagesSeven() {
            final var page = cardRepository.findAllByOrderByCardNumberAsc(PageRequest.of(0, 7));

            assertThat(page.getContent()).hasSize(7);
            assertThat(page.getContent().stream().map(card -> card.getCardNumber()).toList()).isSorted();
        }
    }

    /** The locking finders, which exist to reproduce READ ... UPDATE. */
    @Nested
    @DisplayName("the locking finders reproduce READ ... UPDATE")
    final class LockingFinders {

        @Test
        @DisplayName("the account locking finder returns the row and is executable inside a transaction")
        void accountLockingFinderExecutes() {
            final Long accountId = accountRepository
                    .findAll(PageRequest.of(0, 1))
                    .getContent()
                    .getFirst()
                    .getAccountId();

            assertThat(accountRepository.findByIdForUpdate(accountId))
                    .as("SELECT ... FOR UPDATE can only be issued inside a transaction, and the base class "
                            + "is @Transactional. Outside one the provider raises instead of locking, so "
                            + "this assertion also pins the base class's transactional contract")
                    .isPresent();
        }

        @Test
        @DisplayName("the customer locking finder pairs with it, since one unit of work writes both")
        void customerLockingFinderExecutes() {
            final Long customerId = customerRepository
                    .findAll(PageRequest.of(0, 1))
                    .getContent()
                    .getFirst()
                    .getCustomerId();

            assertThat(customerRepository.findByIdForUpdate(customerId))
                    .as("the account update writes an account row and a customer row in one unit of work, "
                            + "so both locking finders must work or the atomicity claim is unprovable")
                    .isPresent();
        }
    }
}
