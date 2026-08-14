/*
 * ******************************************************************
 * Program     : RepositoryHarnessSeedStateTest.java
 * Component   : Integration test tier, resident at
 *               src/test/java/com/cardemo/integration/repository
 * Application : CardDemo
 * Type        : JUnit 5 integration test - Testcontainers PostgreSQL 16,
 *               real Spring context, Failsafe bound by path
 * Function    : The second of two sibling subclasses that together prove
 *               the repository harness holds ONE container lifecycle.
 *               Runs after RepositoryHarnessLifecycleTest under the
 *               alphabetical order the build pins, and asserts that the
 *               container identifier and mapped port the first sibling
 *               observed are unchanged, that the connection is still
 *               live, and that the V3 seed row counts are exactly what
 *               the harness documents. A per-class container lifecycle
 *               would have stopped and restarted the container between
 *               the two classes, so these assertions are what make that
 *               defect fail loudly instead of intermittently.
 * Source      : app/data/ASCII/** (the nine fixtures V3 seeds from -
 *               50 accounts, 50 cards, 50 cross references, 50
 *               customers, 300 daily transactions, 51 disclosure
 *               groups, 50 category balances, 18 categories, 7 types,
 *               10 users), app/jcl/DUSRSECJ.jcl (the 10 inline users),
 *               app/catlg/LISTCAT.txt, app/cbl/CBACT04C.cbl:1-21
 *               @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Second half of the two-class proof that the repository tier owns exactly one container lifecycle.
 *
 * <h2>What it does</h2>
 *
 * <p>It extends {@link AbstractRepositoryIntegrationTest} exactly as {@link RepositoryHarnessLifecycleTest}
 * does, declaring no container, no context and no connection property of its own, so both classes present
 * the identical Spring context key and Spring is free to reuse the cached context. Because the build pins
 * {@code runOrder} to alphabetical, this class runs second, and that ordering is what gives its central
 * assertion meaning: the container identifier and mapped port recorded by the first sibling must still be
 * the ones this class is talking to.
 *
 * <p>With a class-level {@code @Container} lifecycle they would not be. The extension stops a shared static
 * container in the first class's {@code afterAll} and starts it again for this class, which yields a new
 * container identifier and a new ephemeral mapped port, while the cached datasource keeps addressing the
 * old one. That produces either a connection failure here or a silent divergence between the port the pool
 * holds and the port the container listens on. Asserting the recorded identity is what converts that from
 * an intermittent, order-dependent mystery into an immediate, named failure.
 *
 * <p>Having proved the continuity, it also asserts the seed state the harness documents, because a shared
 * container is only useful if what it contains is known: the ten exact {@code V3} row counts, including the
 * deliberately empty transaction relation that the posting job is what fills.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true verify} runs it with the tier;
 * {@code ./mvnw -B -ntp -Dit.test='RepositoryHarness*Test' verify} runs the pair alone, which is the form
 * that demonstrates the continuity, because a single class can never observe it. A reachable Docker socket
 * is a prerequisite.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>None of its own; every setting is the harness's. No host name, port, database name, user name,
 * password or JDBC URL appears here, and no system property or environment variable is read.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The recorded-identity assertion fails.</em> A per-class container lifecycle was reintroduced.
 *       Remove the annotation from {@link AbstractRepositoryIntegrationTest#POSTGRES}; do not relax this
 *       assertion, which is the only thing that detects it.</li>
 *   <li><em>A seed count assertion fails.</em> {@code V3__seed_data.sql} changed, or a sibling test
 *       committed rows instead of letting the class-level transaction roll back. The counts come from the
 *       nine frozen fixtures and are the contract.</li>
 *   <li><em>Every test fails with a container or Docker error.</em> There is no reachable Docker socket.</li>
 * </ul>
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>Every method reads. Nothing is written to the database, so the class-level rollback has nothing to
 * undo, and no state of any kind is left behind for a later class.
 */
@DisplayName("Repository harness: one container lifecycle for the hierarchy - second sibling")
class RepositoryHarnessSeedStateTest extends AbstractRepositoryIntegrationTest {

    /** The datasource Spring bound from the shared container's injected connection details. */
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the container the first sibling observed is the very same one, on the very same port")
    void theContainerIsTheOneTheFirstSiblingObserved() {
        // The whole point of two classes. Selecting only this class leaves nothing recorded, so the
        // observation is assumed rather than asserted - a single-class run cannot prove continuity and must
        // not pretend to.
        assumeThat(HarnessLifecycleRecord.containerId())
                .as("run the pair together for this proof to mean anything")
                .isNotNull();

        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(POSTGRES.getContainerId())
                .as("a per-class @Container lifecycle would have stopped and replaced the container "
                        + "between the two sibling classes")
                .isEqualTo(HarnessLifecycleRecord.containerId());
        assertThat(POSTGRES.getFirstMappedPort())
                .as("and would have republished it on a new ephemeral port that the cached Hikari pool "
                        + "would not be addressing")
                .isEqualTo(HarnessLifecycleRecord.mappedPort());
    }

    @Test
    @DisplayName("the cached datasource still resolves, and resolves to the recorded port")
    void theCachedDatasourceStillResolves() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5))
                    .as("a restarted container is what makes this fail in the second class only")
                    .isTrue();
            assertThat(connection.getMetaData().getURL())
                    .contains(String.valueOf(POSTGRES.getFirstMappedPort()));
        }
    }

    @ParameterizedTest(name = "{0} holds exactly {1} seeded rows")
    @CsvSource({
        "account,                       50",
        "card,                          50",
        "card_cross_reference,          50",
        "customer,                      50",
        "daily_transaction,             300",
        "disclosure_group,              51",
        "transaction_category_balance,  50",
        "transaction_category,          18",
        "transaction_type,              7",
        "user_security,                 10",
    })
    @DisplayName("every V3 seed count is exactly what the nine frozen fixtures carry")
    void everySeedCountIsExact(final String relation, final int expected) throws SQLException {
        assertThat(countRows(relation)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the transaction relation is deliberately empty: the posting job is what fills it")
    void theTransactionRelationIsDeliberatelyEmpty() throws SQLException {
        assertThat(countRows("\"transaction\""))
                .as("V3 seeds no transaction row, so a non-zero count here means a sibling committed")
                .isZero();
    }

    @Test
    @DisplayName("the ten seeded credentials are BCrypt digests, never the plaintext the JCL carries")
    void theTenSeededCredentialsAreBcryptDigests() throws SQLException {
        // app/jcl/DUSRSECJ.jcl:35-44 gives all ten users one shared literal plaintext value. Storing that
        // value is what Rule 1 Clause D forbids - the clause names tests explicitly - and what the security
        // gate asserts against, so the digest shape is checked rather than assumed.
        //
        // The check is POSITIVE and never compares against the plaintext. The source value is eight
        // characters; a BCrypt digest at the pinned strength of 10 is sixty and carries a version tag, a cost
        // factor and a radix-64 salt and digest. Satisfying that envelope is structurally incompatible with
        // holding the plaintext, so no comparison against it is needed - and writing the plaintext down in
        // order to make one would itself be the leak the check exists to prevent. Asserted through a derived
        // boolean so that no failure message can echo a digest.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet digests = statement.executeQuery(
                        "SELECT sec_usr_pwd FROM user_security ORDER BY sec_usr_id")) {
            int rows = 0;
            while (digests.next()) {
                final String digest = digests.getString(1);
                assertThat(digest.length()).as("digest width at row %d", rows).isEqualTo(60);
                assertThat(digest.matches("^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$"))
                        .as("BCrypt strength-10 envelope at row %d; the stored value is credential material "
                                + "and is deliberately not reproduced in this message", rows)
                        .isTrue();
                rows++;
            }

            assertThat(rows).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("the harness fixture reader still resolves through the strict delegation in this class too")
    void theHarnessFixtureReaderStillResolves() {
        assertThat(readFixture("tcatbal.txt")).hasSize(50);
        assertThat(readFixture("trantype.txt")).hasSize(7);
        assertThat(readFixture("trancatg.txt")).hasSize(18);
    }

    /**
     * Counts the rows of one relation through the injected datasource.
     *
     * @param relation the relation name, already quoted where the identifier requires it
     * @return the row count
     * @throws SQLException if the count cannot be issued, which is reported rather than swallowed
     */
    private long countRows(final String relation) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet counted = statement.executeQuery("SELECT COUNT(*) FROM " + relation)) {
            assertThat(counted.next()).isTrue();
            return counted.getLong(1);
        }
    }
}
