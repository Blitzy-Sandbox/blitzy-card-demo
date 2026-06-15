/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Spring Batch metadata-schema PROVISIONING regression test (QA FINAL 7 F-1)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test infrastructure with NO COBOL source equivalent. It
 *  guards the JCL→Spring Batch substrate (AAP §0.7.6) that realises the frozen
 *  AWS CardDemo batch estate at commit SHA 27d6c6f; the COBOL/JCL sources are
 *  read-only reference and are NEVER copied here. Base package is com.cardemo
 *  (decision D-006 — deliberately NOT com.carddemo), matching the pom groupId.
 * ============================================================================
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Production-settings regression test proving the Spring Batch metadata schema is provisioned by
 * <strong>Flyway</strong> — closing QA Checkpoint FINAL&nbsp;7 finding <strong>F-1</strong>.
 *
 * <h2>What F-1 was</h2>
 * <p>The application runs with {@code spring.batch.jdbc.initialize-schema=never} and
 * {@code spring.jpa.hibernate.ddl-auto=validate} (both in {@code application.yml}), so neither Spring
 * Batch's own initializer nor Hibernate ever creates the {@code BATCH_*} metadata tables. Before this
 * fix, no Flyway migration created them either, so the very first job launch failed at runtime with
 * {@code org.postgresql.util.PSQLException: ERROR: relation "batch_job_instance" does not exist}
 * (thrown from {@code SimpleJobRepository.isJobInstanceExists}). The entire batch IT suite masked the
 * gap by overriding {@code initialize-schema=always}; that masking has been removed, and the schema is
 * now owned by Flyway in {@code db/migration/V5__batch_metadata.sql} (the canonical PostgreSQL
 * {@code schema-postgresql.sql} from {@code spring-batch-core}, copied verbatim).</p>
 *
 * <h2>Why this test cannot regress silently</h2>
 * <p>This IT extends {@link AbstractBatchJobIT} and therefore inherits the <em>identical</em> context
 * configuration the production application uses — most importantly it adds <strong>no</strong>
 * {@code spring.batch.jdbc.initialize-schema} override, so the inherited production value
 * {@code never} is in effect (the {@code test} profile defines no {@code spring.batch} block). If the
 * {@code BATCH_*} tables were ever again absent, the inherited {@code @BeforeEach}
 * {@code clearJobRepository()} would fail before any assertion ran, and {@link #interestCalculationJob}
 * could not launch — exactly reproducing the original F-1 symptom. The explicit assertions below make
 * the guarantee self-documenting rather than incidental.</p>
 *
 * <p>Per the {@code @SpringBootTest} + {@code @ActiveProfiles("test")} bootstrap on the base class,
 * Flyway provisions the production schema (V1→V2→V3→V4→V5) inside the throwaway Testcontainers
 * PostgreSQL before the context is ready, so the batch tables exist for every test here.</p>
 *
 * @see AbstractBatchJobIT
 * @see com.cardemo.config.BatchConfig
 * @see com.cardemo.batch.jobs.InterestCalculationJob
 */
@Import(BatchMetadataSchemaIT.SecurityCorsTestConfig.class)
@DisplayName("Spring Batch metadata schema provisioning — Flyway V5 (QA FINAL 7 F-1 regression)")
class BatchMetadataSchemaIT extends AbstractBatchJobIT {

    /**
     * The six Spring Batch metadata tables (PostgreSQL folds the unquoted DDL identifiers to
     * lower-case), expected to exist after Flyway applies {@code V5__batch_metadata.sql}.
     */
    private static final List<String> EXPECTED_BATCH_TABLES = List.of(
            "batch_job_instance",
            "batch_job_execution",
            "batch_job_execution_params",
            "batch_job_execution_context",
            "batch_step_execution",
            "batch_step_execution_context");

    /** The three Spring Batch sequences expected after Flyway applies {@code V5__batch_metadata.sql}. */
    private static final List<String> EXPECTED_BATCH_SEQUENCES = List.of(
            "batch_job_seq",
            "batch_job_execution_seq",
            "batch_step_execution_seq");

    /**
     * The interest-calculation job ({@code INTCALC.jcl} //STEP15 EXEC PGM=CBACT04C). Autowired by bean
     * name to disambiguate the six {@code Job} beans in the context. This is the same job named in the
     * QA F-1 reproduction ({@code SPRING_BATCH_JOB_NAME=interestCalculationJob}); it reads only
     * Flyway-seeded reference data (TCATBAL / DISCGRP / ACCOUNT), so it completes without any extra S3
     * input staging beyond the base-class {@code @BeforeAll} AWS provisioning.
     */
    @Autowired
    private Job interestCalculationJob;

    /**
     * Asserts that every {@code BATCH_*} table and sequence exists in the running PostgreSQL schema and
     * was created by <strong>Flyway</strong> (verified via {@code flyway_schema_history}) — not by
     * Spring Batch's own initializer (which is {@code never} here) nor by Hibernate (which is
     * {@code validate} here). This is the direct inverse of the original F-1 evidence
     * ({@code psql \dt} showed no {@code batch_*} tables and {@code SELECT * FROM batch_job_instance}
     * raised {@code relation "batch_job_instance" does not exist}).
     */
    @Test
    @DisplayName("Flyway V5 provisions all 6 BATCH_* tables + 3 sequences (production initialize-schema=never)")
    void flywayProvisionsBatchMetadataSchema() throws SQLException {
        final List<String> existingTables = new ArrayList<>();
        final List<String> existingSequences = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            for (final String table : EXPECTED_BATCH_TABLES) {
                if (tableExists(connection, table)) {
                    existingTables.add(table);
                }
            }
            for (final String sequence : EXPECTED_BATCH_SEQUENCES) {
                if (sequenceExists(connection, sequence)) {
                    existingSequences.add(sequence);
                }
            }

            // All six tables and three sequences must be present — the precise inverse of the F-1 symptom.
            assertThat(existingTables)
                    .as("Spring Batch metadata TABLES must be Flyway-provisioned (V5__batch_metadata.sql)")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_BATCH_TABLES);
            assertThat(existingSequences)
                    .as("Spring Batch metadata SEQUENCES must be Flyway-provisioned (V5__batch_metadata.sql)")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_BATCH_SEQUENCES);

            // Prove FLYWAY (not the batch initializer) owns the schema: V5 must be recorded as applied.
            assertThat(flywayVersionApplied(connection, "5"))
                    .as("flyway_schema_history must record V5 as successfully applied")
                    .isTrue();
        }
    }

    /**
     * Launches {@link #interestCalculationJob} under the production settings (no
     * {@code initialize-schema} override) and asserts it reaches {@link BatchStatus#COMPLETED}. Before
     * the F-1 fix this launch failed with {@code relation "batch_job_instance" does not exist}; a green
     * {@code COMPLETED} here proves the Flyway-owned batch repository is fully functional at runtime.
     */
    @Test
    @DisplayName("A real job launches & COMPLETES against the Flyway-owned batch repository (no F-1 PSQLException)")
    void batchJobLaunchesAgainstFlywayOwnedRepository() throws Exception {
        final JobExecution execution = launchJob(
                interestCalculationJob,
                uniqueParams(builder -> builder.addString("parmDate", "2022071800")));

        assertThat(execution.getStatus())
                .as("interestCalculationJob must COMPLETE — proving BATCH_JOB_INSTANCE et al. exist at runtime")
                .isEqualTo(BatchStatus.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Raw-JDBC schema-introspection helpers (read-only; no entity dependency,
    // because the BATCH_* tables are intentionally NOT JPA-mapped).
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if a base table with the given name exists in the {@code public} schema,
     * queried against {@code pg_catalog.pg_tables} (whose schema/name columns are {@code schemaname} /
     * {@code tablename}).
     *
     * @param connection an open JDBC connection (caller owns its lifecycle).
     * @param tableName  the table name to look for (matched case-insensitively, lower-cased here).
     * @return whether exactly one matching table exists in schema {@code public}.
     */
    private boolean tableExists(final Connection connection, final String tableName) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public' AND tablename = ?";
        return countMatches(connection, sql, tableName) == 1;
    }

    /**
     * Returns {@code true} if a sequence with the given name exists in the {@code public} schema,
     * queried against {@code pg_catalog.pg_sequences} (whose schema/name columns are {@code schemaname} /
     * {@code sequencename}).
     *
     * @param connection   an open JDBC connection (caller owns its lifecycle).
     * @param sequenceName the sequence name to look for (matched case-insensitively, lower-cased here).
     * @return whether exactly one matching sequence exists in schema {@code public}.
     */
    private boolean sequenceExists(final Connection connection, final String sequenceName) throws SQLException {
        final String sql =
                "SELECT COUNT(*) FROM pg_catalog.pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
        return countMatches(connection, sql, sequenceName) == 1;
    }

    /**
     * Executes a single-column {@code COUNT(*)} query whose one bind parameter is the (lower-cased)
     * relation name, and returns the count. The SQL text is a fixed, code-supplied literal (never user
     * input) and the relation name is bound, not concatenated — so there is no injection surface.
     *
     * @param connection   an open JDBC connection (caller owns its lifecycle).
     * @param sql          the {@code COUNT(*)} SQL with a single {@code ?} for the relation name.
     * @param relationName the relation name to bind (lower-cased for PostgreSQL's folded identifiers).
     * @return the integer count returned by the query (0 when absent).
     */
    private int countMatches(final Connection connection, final String sql, final String relationName)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, relationName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Returns {@code true} if Flyway's history table records the given migration version as a
     * successfully-applied migration — proving the {@code BATCH_*} schema is owned by Flyway.
     *
     * @param connection an open JDBC connection (caller owns its lifecycle).
     * @param version    the Flyway version string to look for (for example {@code "5"}).
     * @return whether {@code flyway_schema_history} has a successful row for that version.
     */
    private boolean flywayVersionApplied(final Connection connection, final String version) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = true";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) >= 1;
            }
        }
    }

    /**
     * Supplies the {@link CorsConfigurationSource} bean that production {@code SecurityConfig}'s CORS
     * DSL requires. Under {@code webEnvironment=NONE} the auto-configured web CORS source is absent, so
     * this {@code @TestConfiguration} publishes an explicit (empty) {@link UrlBasedCorsConfigurationSource}
     * — behaviourally inert for this non-web batch test and identical to the sibling batch ITs. It is a
     * {@code @TestConfiguration} (excluded from the application component scan) imported explicitly above.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty CORS source (no mappings) satisfying the security filter chain's CORS DSL
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}
