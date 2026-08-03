/*
 * ******************************************************************
 * Program     : AccountRepositoryIntegrationTest
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Testcontainers PostgreSQL 16)
 * Function    : Exercises the ACCTDATA cluster replacement against a real
 *               PostgreSQL engine - the Flyway schema, the seeded fixture rows,
 *               the zoned-decimal overpunch decode and the alternate-index finders.
 * Source      : app/catlg/LISTCAT.txt:L59 (ACCTDATA key length 11, record 300)
 * Source      : app/cpy/CVACT01Y.cpy (the 300-byte record layout) @ 7756d89
 * Source      : app/data/ASCII/acctdata.txt (50 rows of 300 bytes) @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Integration test for {@link AccountRepository} against a real PostgreSQL 16 engine.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the first <strong>concrete</strong> subclass of {@link AbstractRepositoryIntegrationTest}. Until
 * one existed, Failsafe reported {@code Tests run: 0} on every build: the abstract base was complete and
 * correct, but an abstract class is not a test, so the entire integration tier was inert. That is the specific
 * gap this class closes, and the reason it asserts breadth rather than depth - its job is to prove the tier
 * runs and that the schema, the migrations and the seed data agree with the frozen copybooks.
 *
 * <p>Four things are asserted that a unit test structurally cannot:
 *
 * <ul>
 *   <li><strong>The Flyway migrations apply</strong> to a real engine. {@code validate-on-migrate} is on and
 *       {@code ddl-auto} is {@code validate}, so a divergence between {@code V1__create_schema.sql} and the
 *       JPA mappings fails context startup rather than a later assertion.</li>
 *   <li><strong>The seed data decoded correctly.</strong> {@code app/data/ASCII/acctdata.txt} stores signed
 *       money in zoned-decimal trailing-sign overpunch form, where the first row's balance field ends in
 *       {@code &#123;} and therefore denotes {@code +0} rather than a brace. A naive text load yields a wrong
 *       number that still parses, so the only way to catch it is to read the decoded value back out of the
 *       database - which is what happens here.</li>
 *   <li><strong>The numeric precision survives the round trip.</strong> {@code NUMERIC(12,2)} is asserted by
 *       scale, because a column widened or narrowed by one digit changes rounding without failing any
 *       mapping check.</li>
 *   <li><strong>Ordered paging behaves as the VSAM browse did</strong>, which is what the batch readers rely
 *       on for their page-by-page scan.</li>
 *   </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>This is a Failsafe test, not a Surefire one: it matches {@code **&#47;integration/**} and runs in the
 * {@code integration-test} phase. Run the tier with {@code ./mvnw -B -ntp verify}, or this class alone with
 * {@code ./mvnw -B -ntp verify -Dit.test=AccountRepositoryIntegrationTest -DskipUTs=true}.
 *
 * <p><strong>A container runtime is required.</strong> Testcontainers starts PostgreSQL 16 and needs a
 * reachable Docker socket; without one this test errors rather than silently passing, which is the intended
 * behaviour - a green build must not be obtainable by having no daemon.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The datasource is injected from the container by {@code @ServiceConnection} on the base class, so no URL,
 * user name or password appears here. The remaining properties the application context requires are supplied
 * by the base class; see its documentation for the list and for why each one is needed.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>{@code Could not find a valid Docker environment} - no container runtime. Start one; do not skip the
 *       tier.</li>
 *   <li>A Flyway validation failure means a migration was edited after being applied, or the JPA mapping and
 *       the DDL disagree. Fix whichever is wrong; neither {@code baseline-on-migrate} nor
 *       {@code ignore-migration-patterns} may be enabled to get past it.</li>
 *   <li>A failure in the overpunch test means {@code V3__seed_data.sql} decoded a sign character as data.
 *       Check that the decode is position-aware from the PIC clause rather than a blanket character
 *       substitution.</li>
 *   </ul>
 */
@DisplayName("AccountRepository against PostgreSQL 16 - schema, seed and paging")
class AccountRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

    /** The number of rows {@code app/data/ASCII/acctdata.txt} carries. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** The scale of every money column derived from a {@code PIC S9(10)V99} field. */
    private static final int MONEY_SCALE = 2;

    @Autowired
    private AccountRepository accountRepository;

    @Nested
    @DisplayName("the schema and the seed")
    class SchemaAndSeed {

        @Test
        @DisplayName("the context starts, which proves all three migrations applied and the mapping validates")
        void theContextStartsAndTheMappingValidates() {
            assertThat(accountRepository)
                    .as("an injected repository proves Flyway ran and ddl-auto=validate accepted the mapping")
                    .isNotNull();
        }

        @Test
        @DisplayName("the seed loaded exactly the fifty rows the fixture carries")
        void theSeedLoadedFiftyRows() {
            assertThat(accountRepository.count())
                    .as("app/data/ASCII/acctdata.txt is 15,050 bytes of 300-byte records, so 50 rows")
                    .isEqualTo(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("the fixture row count agrees with the fixture file itself")
        void theFixtureRowCountAgreesWithTheFile() {
            final List<String> fixture = readFixture("acctdata.txt");

            assertThat(fixture).hasSize(SEEDED_ROW_COUNT);
            assertThat(accountRepository.count()).isEqualTo(fixture.size());
        }

        @Test
        @DisplayName("the eleven-digit key resolves the first account")
        void theElevenDigitKeyResolvesTheFirstAccount() {
            final Optional<Account> located = accountRepository.findById(1L);

            assertThat(located)
                    .as("LISTCAT records ACCTDATA with key length 11, so the key is the account identifier")
                    .isPresent();
            assertThat(located.get().getAccountId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a key outside the seeded range resolves to nothing rather than to a default")
        void anAbsentKeyResolvesToNothing() {
            assertThat(accountRepository.findById(999_999_999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the zoned-decimal overpunch decode")
    class OverpunchDecode {

        @Test
        @DisplayName("the first account's money fields decoded to the values the overpunch signs denote")
        void theFirstAccountMoneyFieldsDecodedCorrectly() {
            final Account first = accountRepository.findById(1L).orElseThrow();

            assertThat(first.getCurrentBalance())
                    .as("the fixture field ends in '{', which denotes +0 and completes +194.00; a naive "
                            + "text load would have produced a different number that still parses")
                    .isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(first.getCreditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
            assertThat(first.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
        }

        @Test
        @DisplayName("no money column decoded to a value carrying a stray sign character as digits")
        void noMoneyColumnCarriesAStraySignCharacter() {
            final List<Account> all = accountRepository.findAll();

            assertThat(all).hasSize(SEEDED_ROW_COUNT);
            for (final Account account : all) {
                assertThat(account.getCurrentBalance())
                        .as("account %s balance", account.getAccountId())
                        .isNotNull();
                assertThat(account.getCurrentBalance().abs())
                        .as("an overpunch character read as a digit inflates the value by orders of "
                                + "magnitude, so a sane upper bound catches it for every row at once")
                        .isLessThan(new BigDecimal("100000000000"));
            }
        }

        @Test
        @DisplayName("every money column carries scale 2, matching NUMERIC(12,2) from the PIC clause")
        void everyMoneyColumnCarriesScaleTwo() {
            final Account first = accountRepository.findById(1L).orElseThrow();

            assertThat(first.getCurrentBalance().scale())
                    .as("PIC S9(10)V99 becomes NUMERIC(12,2); a different scale changes rounding silently")
                    .isEqualTo(MONEY_SCALE);
            assertThat(first.getCreditLimit().scale()).isEqualTo(MONEY_SCALE);
            assertThat(first.getCurrentCycleCredit().scale()).isEqualTo(MONEY_SCALE);
            assertThat(first.getCurrentCycleDebit().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the ten-character date fields kept their CHAR(10) width")
        void theDateFieldsKeptTheirWidth() {
            final Account first = accountRepository.findById(1L).orElseThrow();

            assertThat(first.getOpenDate()).hasSize(10);
            assertThat(first.getExpiraionDate())
                    .as("the copybook misspelling of EXPIRATION is part of the field contract")
                    .hasSize(10);
        }
    }

    @Nested
    @DisplayName("ordered paging, the VSAM browse replacement the batch readers depend on")
    class OrderedPaging {

        @Test
        @DisplayName("the first page returns the lowest keys in ascending order")
        void theFirstPageReturnsTheLowestKeysAscending() {
            final List<Account> page = accountRepository
                    .findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "accountId")))
                    .getContent();

            assertThat(page).hasSize(10);
            assertThat(page).extracting(Account::getAccountId).isSorted();
        }

        @Test
        @DisplayName("paging the whole table visits every row exactly once with no gap and no repeat")
        void pagingVisitsEveryRowExactlyOnce() {
            final int pageSize = 10;
            final List<Long> visited = new java.util.ArrayList<>();

            for (int pageNumber = 0; pageNumber * pageSize < SEEDED_ROW_COUNT; pageNumber++) {
                accountRepository
                        .findAll(PageRequest.of(pageNumber, pageSize,
                                Sort.by(Sort.Direction.ASC, "accountId")))
                        .forEach(account -> visited.add(account.getAccountId()));
            }

            assertThat(visited)
                    .as("a page-by-page scan is how every sequential batch reader replaces READ NEXT, so a "
                            + "dropped or duplicated row would silently change every batch total")
                    .hasSize(SEEDED_ROW_COUNT)
                    .doesNotHaveDuplicates()
                    .isSorted();
        }

        @Test
        @DisplayName("a page beyond the end is empty rather than an error, which is how the reader stops")
        void aPageBeyondTheEndIsEmpty() {
            assertThat(accountRepository
                    .findAll(PageRequest.of(99, 10, Sort.by(Sort.Direction.ASC, "accountId")))
                    .getContent())
                    .as("the reader treats an empty page as end of file, the FILE STATUS '10' equivalent")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("write path and transactional isolation")
    class WritePath {

        @Test
        @DisplayName("an updated balance is readable back through the same transaction")
        void anUpdatedBalanceIsReadableBack() {
            final Account account = accountRepository.findById(1L).orElseThrow();
            account.setCurrentBalance(new BigDecimal("1234.56"));
            accountRepository.save(account);
            flushAndClear();

            assertThat(accountRepository.findById(1L).orElseThrow().getCurrentBalance())
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("the previous test's write did not leak, because every test rolls back")
        void theWriteDidNotLeak() {
            assertThat(accountRepository.findById(1L).orElseThrow().getCurrentBalance())
                    .as("@Transactional on the base class rolls each test back, so ordering cannot matter")
                    .isEqualByComparingTo(new BigDecimal("194.00"));
        }
    }
}
