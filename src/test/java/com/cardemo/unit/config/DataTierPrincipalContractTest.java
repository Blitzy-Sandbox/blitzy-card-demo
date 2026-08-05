/*
 * ******************************************************************
 * Program     : DataTierPrincipalContractTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test
 * Function    : Verifies the two data-tier controls that the online
 *               update paths depend on: a BOUNDED WAIT for a row lock,
 *               so a contended row fails into the authored lock
 *               outcome instead of stalling, and a LEAST-PRIVILEGE
 *               runtime principal, so the request-serving connection
 *               is not a cluster superuser. Both are configuration
 *               only, so this tier asserts the configuration text that
 *               the runtime binds rather than a bean.
 * Source      : app/cbl/COACTUPC.cbl:3894-3916 (READ ... UPDATE on the
 *               account and the customer, each with a defined answer
 *               for a lock it cannot take) and :517-520 (the 88-level
 *               literals COULD-NOT-LOCK-ACCT-FOR-UPDATE and
 *               COULD-NOT-LOCK-CUST-FOR-UPDATE those answers project)
 *               @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (the ten VSAM clusters the
 *               runtime role is scoped to, and nothing wider) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the data-tier half of the account-update contract, which lives entirely in configuration.
 *
 * <p><strong>Why a text-level assertion rather than a bean-level one.</strong> Neither control has a bean to
 * interrogate. The lock ceiling is a session setting pushed through the pool's connection-initialisation
 * statement, and the runtime principal is a pair of credentials bound from the environment. A test that started
 * a context would prove only that the context started under whatever values the test environment happened to
 * carry; the property worth protecting is that the shipped configuration text indirects both to the
 * environment and supplies no committed credential. That is exactly the shape
 * {@code JwtTokenLifetimeContractTest} already uses for the signing key, so this class follows it.
 *
 * <h2>The bounded wait — why it is required at all</h2>
 *
 * <p>{@code app/cbl/COACTUPC.cbl:3894-3916} reads the account and then the customer {@code FOR UPDATE}, and each
 * read has a defined answer for a lock it cannot take: the 88-level literals at {@code :517-520}, which the REST
 * surface projects as {@code 423 LOCKED} and {@code 409 CONFLICT}. Reaching either answer requires the attempt
 * to <em>fail</em>. PostgreSQL waits forever by default, so those two authored outcomes were unreachable and a
 * row held elsewhere stalled the request instead — at {@code maximum-pool-size: 10} a handful of stalled writers
 * exhausted the pool.
 *
 * <p>The JPA hint {@code jakarta.persistence.lock.timeout} does <strong>not</strong> close this on PostgreSQL:
 * the dialect cannot emit {@code FOR UPDATE WAIT n}, the engine offering only {@code NOWAIT} and
 * {@code SKIP LOCKED}, so the hint is accepted and silently ignored. {@code SET lock_timeout} is therefore the
 * only lever, and because it is a per-session setting every pooled connection must receive it — hence
 * {@code connection-init-sql}.
 *
 * <h2>What is deliberately NOT asserted</h2>
 *
 * <ul>
 *   <li><strong>A {@code statement_timeout}.</strong> Its absence is asserted as a requirement, not overlooked.
 *       It would cap the batch stream, whose steps legitimately run long.</li>
 *   <li><strong>Pool sizing.</strong> Connection-pool tuning is explicitly out of scope for this migration and
 *       recorded as residual risk. The ceiling asserted here is a correctness control, not tuning.</li>
 *   <li><strong>{@code application-test.yml}.</strong> That profile takes its credentials from Testcontainers
 *       through {@code @ServiceConnection}, which supersedes {@code spring.datasource.*} entirely, so a role
 *       binding there would name a role the container never created. Its silence is asserted below.</li>
 * </ul>
 */
@DisplayName("The data-tier contract: a bounded wait for a row lock, and a runtime principal that is not a superuser")
final class DataTierPrincipalContractTest {

    /** The four profiles that make up the published configuration surface. */
    private static final List<String> PROFILES =
            List.of("application.yml", "application-local.yml", "application-test.yml", "application-prod.yml");

    /** The environment variable carrying the lock ceiling, in milliseconds. */
    private static final String LOCK_TIMEOUT_VARIABLE = "POSTGRES_LOCK_TIMEOUT_MS";

    /** The runtime (DML-only) role name variable. */
    private static final String APP_USER_VARIABLE = "CARDDEMO_DB_APP_USER";

    /** The runtime role's password variable. */
    private static final String APP_PASSWORD_VARIABLE = "CARDDEMO_DB_APP_PASSWORD";

    /** The migration (DDL-owning) role name variable, bound to Flyway alone. */
    private static final String MIGRATION_USER_VARIABLE = "CARDDEMO_DB_MIGRATION_USER";

    /** The migration role's password variable. */
    private static final String MIGRATION_PASSWORD_VARIABLE = "CARDDEMO_DB_MIGRATION_PASSWORD";

    /**
     * Reads one profile from the test classpath, which is the same text the runtime binds.
     *
     * @param resourceName the profile file name
     * @return the profile contents, never {@code null}
     * @throws IOException if the resource cannot be read, which is itself a failure worth surfacing
     */
    private static String profile(final String resourceName) throws IOException {
        try (InputStream stream =
                DataTierPrincipalContractTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(stream).as("%s must be on the classpath", resourceName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Strips whole-line comments so an assertion about configuration cannot be satisfied by prose.
     *
     * <p>These profiles carry long explanatory blocks that name the very keys under test, so a naive
     * {@code contains} would pass on a commented mention. Only live YAML survives this filter.
     *
     * @param yaml the raw profile text
     * @return the same text with every comment-only line removed
     */
    private static String liveYaml(final String yaml) {
        return yaml.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /**
     * Reads a repository file that is not on the classpath, such as the Compose definition.
     *
     * @param relativePath the repository-root-relative path
     * @return the file contents, never {@code null}
     * @throws IOException if the file cannot be read
     */
    private static String repositoryFile(final String relativePath) throws IOException {
        final Path path = Path.of(relativePath);
        assertThat(Files.exists(path)).as("%s must exist", relativePath).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("A contended row fails fast instead of stalling")
    final class BoundedLockWait {

        @Test
        @DisplayName("the base profile pushes a lock ceiling onto every pooled connection")
        void theBaseProfileSetsALockTimeoutOnEveryConnection() throws IOException {
            final String live = liveYaml(profile("application.yml"));

            assertThat(live)
                    .as("the ceiling must reach every pooled connection, so it is a driver-level startup "
                            + "option rather than a statement issued after the connection exists")
                    .contains("options: -c lock_timeout=${" + LOCK_TIMEOUT_VARIABLE);
        }

        @Test
        @DisplayName("the ceiling is NOT delivered by connection-init-sql, which a rollback would silently undo")
        void theCeilingIsNotDeliveredByAnInitStatement() throws IOException {
            assertThat(liveYaml(profile("application.yml")))
                    .as("""
                            PostgreSQL's SET is TRANSACTIONAL. Hikari runs connection-init-sql on a \
                            connection whose auto-commit is already off, so the server sees \
                            BEGIN / SET lock_timeout / ROLLBACK and the rollback reverts the value to 0 - \
                            wait forever, which is the defect this control exists to remove. Measured \
                            directly: with connection-init-sql a contended write blocked for the full \
                            duration of the competing transaction. Do not reintroduce it.""")
                    .doesNotContain("connection-init-sql");
        }

        @Test
        @DisplayName("the ceiling is environment-indirected and its default is a positive number of milliseconds")
        void theDefaultIsPositive() throws IOException {
            final Matcher matcher = Pattern
                    .compile("options: -c lock_timeout=\\$\\{" + LOCK_TIMEOUT_VARIABLE + ":(\\d+)}")
                    .matcher(liveYaml(profile("application.yml")));

            assertThat(matcher.find())
                    .as("the ceiling must carry a numeric in-profile default so an unset variable is still bounded")
                    .isTrue();
            assertThat(Integer.parseInt(matcher.group(1)))
                    .as("zero means wait forever in PostgreSQL and would restore the defect exactly")
                    .isPositive();
        }

        @ParameterizedTest(name = "{0} sets no statement_timeout")
        @ValueSource(strings = {
            "application.yml", "application-local.yml", "application-test.yml", "application-prod.yml"})
        @DisplayName("no profile caps statement duration, which would truncate the batch stream")
        void noProfileSetsAStatementTimeout(final String resourceName) throws IOException {
            assertThat(liveYaml(profile(resourceName)))
                    .as("a statement_timeout would abort long-running batch steps that are legitimately long; "
                            + "the row-lock ceiling is deliberately the only bound introduced")
                    .doesNotContain("statement_timeout");
        }

        @Test
        @DisplayName("the ceiling is documented in the environment template")
        void theCeilingIsDocumented() throws IOException {
            assertThat(repositoryFile(".env.example"))
                    .as("Rule 1 Clause E requires key configuration and its default to be documented")
                    .contains(LOCK_TIMEOUT_VARIABLE + "=");
        }
    }

    @Nested
    @DisplayName("The request-serving principal is scoped, and separate from the one that migrates")
    final class LeastPrivilegeRuntimePrincipal {

        @Test
        @DisplayName("the base profile binds the runtime role, with a transition fallback and no committed secret")
        void theBaseProfileBindsTheRuntimeRole() throws IOException {
            final String live = liveYaml(profile("application.yml"));

            assertThat(live)
                    .as("the request-serving connection must be able to use a DML-only role")
                    .contains("username: ${" + APP_USER_VARIABLE + ":${POSTGRES_USER}}")
                    .contains("password: ${" + APP_PASSWORD_VARIABLE + ":${POSTGRES_PASSWORD}}");
            assertThat(live)
                    .as("the fallback must change WHICH variable is read, never whether one is required, so no "
                            + "literal default may terminate the chain")
                    .doesNotContain("${POSTGRES_PASSWORD:");
        }

        @Test
        @DisplayName("production requires all four values outright, so it cannot silently run as the superuser")
        void productionSuppliesNoFallbackAtAll() throws IOException {
            final String live = liveYaml(profile("application-prod.yml"));

            assertThat(live)
                    .as("a production start must fail rather than fall back to the bootstrap superuser")
                    .contains("username: ${" + APP_USER_VARIABLE + "}")
                    .contains("password: ${" + APP_PASSWORD_VARIABLE + "}")
                    .contains("user: ${" + MIGRATION_USER_VARIABLE + "}")
                    .contains("password: ${" + MIGRATION_PASSWORD_VARIABLE + "}");
            assertThat(live)
                    .as("no default and no fallback: a ':' inside any of the four placeholders would reintroduce one")
                    .doesNotContain(APP_USER_VARIABLE + ":")
                    .doesNotContain(APP_PASSWORD_VARIABLE + ":")
                    .doesNotContain(MIGRATION_USER_VARIABLE + ":")
                    .doesNotContain(MIGRATION_PASSWORD_VARIABLE + ":");
        }

        @Test
        @DisplayName("Flyway binds the separate DDL-owning role wherever it is bound at all")
        void flywayBindsTheMigrationRole() throws IOException {
            assertThat(liveYaml(profile("application-local.yml")))
                    .as("the only principal that may reshape the schema must be the one that migrates it")
                    .contains("user: ${" + MIGRATION_USER_VARIABLE + ":${POSTGRES_USER:carddemo}}")
                    .contains("password: ${" + MIGRATION_PASSWORD_VARIABLE + ":${POSTGRES_PASSWORD}}");
        }

        @Test
        @DisplayName("the BASE profile deliberately leaves spring.flyway.user unbound")
        void theBaseProfileLeavesFlywayUserUnbound() throws IOException {
            assertThat(liveYaml(profile("application.yml")))
                    .as("""
                            Boot's FlywayAutoConfiguration derives a SEPARATE DataSource as soon as \
                            spring.flyway.user is non-null, and the guard is a null check rather than a \
                            hasText check - so it cannot be neutralised by setting the value empty. Binding \
                            it in the base would therefore reach application-test.yml, whose credentials come \
                            from Testcontainers via @ServiceConnection, and send Flyway at a role the \
                            container never created. It is bound per-profile instead, deliberately.""")
                    .doesNotContain("user: ${" + MIGRATION_USER_VARIABLE);
        }

        @ParameterizedTest(name = "{0} binds no database role")
        @ValueSource(strings = {"application-test.yml"})
        @DisplayName("the Testcontainers profile binds no role of its own")
        void theTestProfileBindsNoRole(final String resourceName) throws IOException {
            final String live = liveYaml(profile(resourceName));

            assertThat(live)
                    .as("@ServiceConnection supersedes spring.datasource.*; a role named here would not exist "
                            + "in the container")
                    .doesNotContain(APP_USER_VARIABLE)
                    .doesNotContain(MIGRATION_USER_VARIABLE);
        }

        @ParameterizedTest(name = "{0} carries no inline database credential")
        @ValueSource(strings = {
            "application.yml", "application-local.yml", "application-test.yml", "application-prod.yml"})
        @DisplayName("no profile hardcodes a role password")
        void noProfileHardcodesACredential(final String resourceName) throws IOException {
            final Matcher matcher = Pattern.compile("^\\s*password:\\s*(?!\\$\\{)(\\S+)\\s*$", Pattern.MULTILINE)
                    .matcher(liveYaml(profile(resourceName)));

            assertThat(matcher.find())
                    .as("every password must be environment-indirected; %s declares the literal %s",
                            resourceName, matcher.reset().find() ? matcher.group(1) : "")
                    .isFalse();
        }

        @Test
        @DisplayName("all four role variables are documented in the environment template")
        void allFourRoleVariablesAreDocumented() throws IOException {
            assertThat(repositoryFile(".env.example"))
                    .as("Rule 1 Clause D and E: the grant split is only actionable if its inputs are documented")
                    .contains(APP_USER_VARIABLE + "=")
                    .contains(APP_PASSWORD_VARIABLE + "=")
                    .contains(MIGRATION_USER_VARIABLE + "=")
                    .contains(MIGRATION_PASSWORD_VARIABLE + "=");
        }
    }

    @Nested
    @DisplayName("The provisioning step creates roles and grants, and nothing Flyway owns")
    final class RoleProvisioning {

        /** The Compose definition, which carries the provisioning script as an inline config. */
        private String compose() throws IOException {
            return repositoryFile("docker-compose.yml");
        }

        @Test
        @DisplayName("the roles config is mounted read-only into the database's initialisation directory")
        void theConfigIsMountedReadOnly() throws IOException {
            assertThat(compose())
                    .as("the script must be delivered as an inline config so no new artefact enters the tree")
                    .contains("postgres-least-privilege-roles:")
                    .contains("target: /docker-entrypoint-initdb.d/10-least-privilege-roles.sh")
                    .contains("mode: 0555");
        }

        @Test
        @DisplayName("the runtime role is created with every dangerous attribute explicitly negated")
        void theRuntimeRoleNegatesEveryDangerousAttribute() throws IOException {
            assertThat(compose())
                    .as("NOSUPERUSER is the one that matters most: a superuser ignores every grant, every "
                            + "row-level policy and every column privilege")
                    .contains("NOSUPERUSER")
                    .contains("NOCREATEDB")
                    .contains("NOCREATEROLE")
                    .contains("NOBYPASSRLS")
                    .contains("NOREPLICATION");
        }

        @Test
        @DisplayName("the script never creates a table, an index or a row, all of which belong to Flyway")
        void theScriptDoesNotBypassFlyway() throws IOException {
            final String compose = compose().toUpperCase(java.util.Locale.ROOT);

            assertThat(compose)
                    .as("""
                            Roles and privileges are CLUSTER-level objects outside Flyway's remit, and Flyway \
                            could not own them even in principle because it cannot create the role it connects \
                            as. Schema objects are the opposite: they belong to the three migrations, and this \
                            script must not mount ad hoc SQL that bypasses them.""")
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("CREATE INDEX")
                    .doesNotContain("INSERT INTO")
                    .doesNotContain("DROP TABLE");
        }

        @Test
        @DisplayName("every shell expansion inside the inline config is escaped against Compose interpolation")
        void everyShellExpansionIsEscaped() throws IOException {
            final String content = compose();
            final int start = content.indexOf("postgres-least-privilege-roles:");
            assertThat(start).as("the config block must be present").isNotNegative();
            final String block = content.substring(start);

            // A single '$' would be consumed by Compose: it would both break the script and bake the
            // resolved secret into `docker compose config` output. Every one must be doubled.
            final Matcher unescaped = Pattern.compile("(?<!\\$)\\$(?!\\$)").matcher(block);
            assertThat(unescaped.find())
                    .as("an unescaped '$' in a Compose config body is interpolated by Compose, not by the shell")
                    .isFalse();
        }
    }
}
