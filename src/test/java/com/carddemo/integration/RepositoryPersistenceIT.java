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
package com.carddemo.integration;

import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises the CP2 Spring Data JPA persistence layer
 * against a real PostgreSQL 16 instance provisioned by Testcontainers.
 *
 * <p>Flyway applies the {@code V1}/{@code V2}/{@code V3} migrations and Hibernate
 * runs with {@code ddl-auto=validate}, so the Spring context starts only when
 * every {@code @Entity} mapping agrees with the migrated schema. This proves the
 * schema-validation contract that compilation alone cannot — including the
 * fixed-width {@code CHAR} JDBC bindings on the composite keys (for example
 * {@code DisclosureGroupId.acctGroupId}/{@code tranTypeCd}) — and that the
 * derived finder queries parse and execute against the live schema and its
 * secondary indexes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Repository & schema-validation IT — PostgreSQL 16 + Flyway + ddl-auto=validate")
class RepositoryPersistenceIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("context starts under ddl-auto=validate: every entity mapping agrees with the Flyway schema")
    void contextLoadsAndValidatesSchema() {
        assertThat(accountRepository).isNotNull();
        assertThat(cardRepository).isNotNull();
        assertThat(cardXrefRepository).isNotNull();
        assertThat(customerRepository).isNotNull();
        assertThat(dailyTransactionRepository).isNotNull();
        assertThat(transactionTypeRepository).isNotNull();
        assertThat(userRepository).isNotNull();
        assertThat(entityManager).isNotNull();
    }

    @Test
    @DisplayName("all seven repositories execute count queries against the seeded schema")
    void allRepositoriesQueryable() {
        assertThat(accountRepository.count()).isPositive();
        assertThat(cardRepository.count()).isPositive();
        assertThat(cardXrefRepository.count()).isPositive();
        assertThat(customerRepository.count()).isPositive();
        assertThat(dailyTransactionRepository.count()).isPositive();
        assertThat(transactionTypeRepository.count()).isPositive();
        assertThat(userRepository.count()).isPositive();
    }

    @Test
    @DisplayName("CardRepository.findByCardAcctId derived query parses and returns matching rows (idx_cards_acct_id)")
    void cardDerivedQueryByAcctId() {
        Card sample = cardRepository.findAll().get(0);
        Long acctId = sample.getCardAcctId();

        List<Card> matches = cardRepository.findByCardAcctId(acctId);

        assertThat(matches).isNotEmpty();
        assertThat(matches).allMatch(card -> acctId.equals(card.getCardAcctId()));

        assertThat(cardRepository.findByCardAcctId(99_999_999_999L)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("CardXrefRepository.findByXrefAcctId derived query parses and returns matching rows (idx_card_xref_acct_id)")
    void cardXrefDerivedQueryByAcctId() {
        CardXref sample = cardXrefRepository.findAll().get(0);
        Long acctId = sample.getXrefAcctId();

        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(acctId);

        assertThat(matches).isNotEmpty();
        assertThat(matches).allMatch(xref -> acctId.equals(xref.getXrefAcctId()));

        assertThat(cardXrefRepository.findByXrefAcctId(99_999_999_999L)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("DisclosureGroup round-trips through its fixed-width CHAR composite key")
    void disclosureGroupCharCompositeKeyRoundTrip() {
        DisclosureGroupId id = new DisclosureGroupId("ITTEST0001", "IT", 9999);
        DisclosureGroup group = new DisclosureGroup(id, new BigDecimal("12.34"));

        entityManager.persistAndFlush(group);
        entityManager.clear();

        DisclosureGroup loaded = entityManager.find(DisclosureGroup.class, id);
        assertThat(loaded).as("CHAR composite key must round-trip via a real SELECT").isNotNull();
        assertThat(loaded.getId().getAcctGroupId()).isEqualTo("ITTEST0001");
        assertThat(loaded.getId().getTranTypeCd()).isEqualTo("IT");
        assertThat(loaded.getId().getTranCatCd()).isEqualTo(9999);
        assertThat(loaded.getDisIntRate()).isEqualByComparingTo("12.34");
    }
}
