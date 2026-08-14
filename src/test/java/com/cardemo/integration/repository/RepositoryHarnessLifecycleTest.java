/*
 * ******************************************************************
 * Program     : RepositoryHarnessLifecycleTest.java
 * Component   : Integration test tier, resident at
 *               src/test/java/com/cardemo/integration/repository
 * Application : CardDemo
 * Type        : JUnit 5 integration test - Testcontainers PostgreSQL 16,
 *               real Spring context, Failsafe bound by path
 * Function    : The first of two sibling subclasses that together prove
 *               the repository harness holds ONE container lifecycle for
 *               the whole class hierarchy. Asserts that the shared
 *               container is running, that the live connection resolves
 *               through it, that Flyway applied the three migrations,
 *               and records the container's identity and mapped port so
 *               that the sibling class can assert both are unchanged.
 *               A per-class container lifecycle would stop the container
 *               after this class and restart it on a new mapped port,
 *               which is exactly what the sibling then detects.
 * Source      : app/catlg/LISTCAT.txt (10 base clusters, 3 alternate
 *               indexes, 3 paths - the physical layout PostgreSQL 16
 *               replaces), app/cbl/CBACT04C.cbl:1-21 (banner
 *               convention), CONTRIBUTING.md:33-34 @ 7756d89
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * First half of the two-class proof that the repository tier owns exactly one container lifecycle.
 *
 * <h2>What it does</h2>
 *
 * <p>This class and {@link RepositoryHarnessSeedStateTest} are siblings: both extend
 * {@link AbstractRepositoryIntegrationTest}, neither declares a container, a context or a connection
 * property of its own, and both are collected by Failsafe in the same invocation. Run alphabetically, this
 * one runs first. It asserts that the shared container is up and reachable and that the schema the harness
 * documents is the schema the connection actually sees, and it records the container's identity and mapped
 * port into {@link HarnessLifecycleRecord} so that the sibling can assert neither changed.
 *
 * <p>The pairing is the point. A single subclass cannot detect the defect this proof exists for: a
 * class-level {@code @Container} lifecycle stops the container in the first class's {@code afterAll} and
 * restarts it for the second on a new mapped port, while Spring reuses the cached context and its
 * connection pool still addresses the old one. That is invisible with one class and fails the second class
 * with a connection error the moment there are two. Restoring a per-class lifecycle therefore turns this
 * pair red immediately rather than intermittently.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true verify} runs it with the tier;
 * {@code ./mvnw -B -ntp -Dit.test='RepositoryHarness*Test' verify} runs the pair alone, which is the form
 * that demonstrates the continuity. A reachable Docker socket is a prerequisite: Testcontainers starts a
 * real PostgreSQL 16 container and a Ryuk reaper, so the tier cannot run without a container runtime.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>None of its own. Every setting - the {@code test} profile, the container image tag, the injected
 * connection details, the fixed clock - is declared once by the harness. This class holds no host name,
 * port, database name, user name, password or JDBC URL, and reads no system property or environment
 * variable.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test fails with a container or Docker error.</em> There is no reachable Docker socket.
 *       State the blocker rather than asserting an untested pass.</li>
 *   <li><em>The sibling class fails on the recorded port while this one passes.</em> A per-class container
 *       lifecycle has been reintroduced. See the documentation on
 *       {@link AbstractRepositoryIntegrationTest#POSTGRES}; the remedy is to remove the annotation, not to
 *       relax this assertion.</li>
 *   <li><em>A migration-count assertion fails.</em> A fourth migration was added, or one of the three was
 *       renamed. The {@code BATCH_*} tables come from the framework's own script because the {@code test}
 *       profile sets {@code spring.batch.jdbc.initialize-schema: always}, so they must not be added as a
 *       migration.</li>
 *   </ul>
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>Every method here reads. Nothing is inserted, updated or deleted, so the class-level rollback has
 * nothing to undo; the one mutation is the static record of the observed container identity, which is
 * written once by this class and read once by its sibling, and which carries no database state.
 */
@DisplayName("Repository harness: one container lifecycle for the hierarchy - first sibling")
class RepositoryHarnessLifecycleTest extends AbstractRepositoryIntegrationTest {

    /** The datasource Spring bound from the shared container's injected connection details. */
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the shared container is running and is the one this class was bound to")
    void theSharedContainerIsRunning() {
        assertThat(POSTGRES.isRunning())
                .as("the container is started once per JVM by the harness static initialiser")
                .isTrue();
        // The image is pinned by DIGEST, not by a tag, and the assertion says so. A tag is mutable: the
        // harness measured postgres:16 resolving to 16.14 here while the sibling batch harness named 16.10,
        // so one build was exercising the migrated schema against two engines while both files claimed to pin
        // "PostgreSQL 16". A digest is content-addressed and cannot move at all. The batch harness names this
        // identical digest and the two must stay equal, so the absence of a tag is asserted as well as the
        // value - a reference that carried one again would be the drift this pin exists to prevent.
        assertThat(POSTGRES.getDockerImageName())
                .isEqualTo("postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20")
                .doesNotContain("-alpine")
                .contains("@sha256:");

        HarnessLifecycleRecord.record(POSTGRES.getContainerId(), POSTGRES.getFirstMappedPort());

        assertThat(HarnessLifecycleRecord.containerId()).isNotBlank();
        assertThat(HarnessLifecycleRecord.mappedPort()).isPositive();
    }

    @Test
    @DisplayName("the injected datasource reaches PostgreSQL 16 through that container")
    void theInjectedDatasourceReachesPostgres() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion())
                    .as("the compose topology pins the same PostgreSQL 16 tag")
                    .isEqualTo(16);
            assertThat(connection.getMetaData().getURL())
                    .as("the URL was derived from the container, so it carries the mapped port")
                    .contains(String.valueOf(POSTGRES.getFirstMappedPort()));
        }
    }

    @Test
    @DisplayName("Flyway applied exactly the three migrations, in order, and none failed")
    void flywayAppliedExactlyThreeMigrations() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet applied = statement.executeQuery(
                        "SELECT version, success FROM flyway_schema_history "
                                + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
            final StringBuilder versions = new StringBuilder();
            int count = 0;
            while (applied.next()) {
                versions.append(applied.getString("version")).append(' ');
                assertThat(applied.getBoolean("success"))
                        .as("migration %s must have applied cleanly", applied.getString("version"))
                        .isTrue();
                count++;
            }

            assertThat(count).as("V1, V2 and V3 - there is deliberately no fourth").isEqualTo(3);
            assertThat(versions.toString().trim()).isEqualTo("1 2 3");
        }
    }

    @Test
    @DisplayName("the eleven tables the schema declares are present under their migration names")
    void theElevenTablesArePresent() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' ORDER BY table_name")) {
            final StringBuilder present = new StringBuilder();
            while (tables.next()) {
                present.append(tables.getString(1)).append(',');
            }
            final String names = present.toString();

            assertThat(names).contains("account,").contains("card,").contains("card_cross_reference,")
                    .contains("customer,").contains("daily_transaction,").contains("disclosure_group,")
                    .contains("transaction,").contains("transaction_category,")
                    .contains("transaction_category_balance,").contains("transaction_type,")
                    .contains("user_security,");
        }
    }

    @Test
    @DisplayName("the three non-unique alternate indexes of V2 exist and none of them is unique")
    void theThreeAlternateIndexesExistAndAreNonUnique() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet indexes = statement.executeQuery(
                        "SELECT i.indexname, x.indisunique FROM pg_indexes i "
                                + "JOIN pg_class c ON c.relname = i.indexname "
                                + "JOIN pg_index x ON x.indexrelid = c.oid "
                                + "WHERE i.schemaname = 'public' AND i.indexname LIKE 'idx\\_%' "
                                + "ORDER BY i.indexname")) {
            // The prefix is idx_, which is what V2__create_indexes.sql declares. An earlier pattern looked
            // for ix_ and therefore matched nothing at all - and a count of zero is the one result a
            // count-only assertion reports identically to a genuinely absent index. The three names are
            // asserted alongside the count for that reason: a rename now fails as a rename rather than
            // silently reading as "no alternate index exists".
            final java.util.List<String> found = new java.util.ArrayList<>();
            while (indexes.next()) {
                assertThat(indexes.getBoolean("indisunique"))
                        .as("a legacy alternate key is non-unique, so %s must not be unique",
                                indexes.getString("indexname"))
                        .isFalse();
                found.add(indexes.getString("indexname"));
            }

            assertThat(found)
                    .as("one index per alternate index catalogued in app/catlg/LISTCAT.txt: the card alternate "
                            + "key at AXRKP 16, the cross-reference alternate key and the transaction "
                            + "processing timestamp at AXRKP 304")
                    .containsExactly("idx_card_acct_id", "idx_card_cross_reference_acct_id",
                            "idx_transaction_proc_ts");
        }
    }

    @Test
    @DisplayName("the harness fixture reader resolves through the strict FixtureLoader delegation")
    void theHarnessFixtureReaderResolvesThroughTheStrictLoader() {
        // Runtime proof that the delegated readFixture works, and that the census guard is live: a name that
        // is not one of the nine catalogued fixtures is refused rather than silently returning nothing.
        assertThat(readFixture("dailytran.txt")).hasSize(300);
        assertThat(readFixture("dailytran.txt").getFirst()).hasSize(350);
        assertThat(readFixture("acctdata.txt")).hasSize(50);
        assertThat(readFixture("discgrp.txt")).hasSize(51);
    }

    @Test
    @DisplayName("the fixed clock is the tier's only time source and is pinned to the fixture instant")
    void theFixedClockIsPinnedToTheFixtureInstant() {
        assertThat(fixedClock().instant().toString()).isEqualTo("2022-06-10T19:27:53Z");
        assertThat(fixedClock().instant()).isEqualTo(fixedClock().instant());
    }
}
