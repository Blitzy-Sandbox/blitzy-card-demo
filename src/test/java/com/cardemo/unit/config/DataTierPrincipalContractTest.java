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
 * <p><strong>What this tier proves, and what it deliberately leaves to another tier.</strong> Neither control
 * has a bean to interrogate: the lock ceiling is a session setting carried as a driver startup option, and the
 * runtime principal is a pair of credentials bound from the environment. What this tier can prove is that the
 * shipped configuration <em>text</em> indirects both to the environment and supplies no committed credential -
 * the same shape {@code JwtTokenLifetimeContractTest} uses for the signing key.
 *
 * <p>What it cannot prove is that the ceiling is <em>in effect</em>. That distinction is not academic, and an
 * earlier revision of this class understated it by describing a running-context test as proving "only that the
 * context started under whatever values the test environment happened to carry". A running context proves
 * considerably more than that: it can read {@code lock_timeout} back out of a pooled session and it can hold two
 * sessions open and contend a row. Both are now done, in
 * {@code src/test/java/com/cardemo/integration/repository/RepositorySchemaAndFinderIntegrationTest.java} -
 * {@code theShippedLockCeilingIsInEffectOnAPooledSession} reads the value out of the live session and asserts it
 * survives a rollback, and {@code aContendedRowFailsWithinTheCeilingRatherThanStalling} locks a row in one
 * session, contends it from a second, and asserts the refusal arrives with SQL state {@code 55P03} inside the
 * ceiling. Measured: with the shipped ceiling the contending session is refused after 5.02 s; with the ceiling
 * changed to 1500 ms it is refused after 1.523 s; with it set to {@code 0} both of those tests fail rather than
 * hanging.
 *
 * <p>The division of labour is therefore explicit rather than implied, and it is stated here because the
 * failure mode it guards against is a reader taking a passing text assertion for a passing behaviour. This tier
 * owns <em>what the configuration says</em>. That tier owns <em>what the database does</em>. Neither is
 * sufficient alone: a correct value delivered by the wrong mechanism passes here and fails there, and a
 * database that happens to be configured correctly by some other route passes there and fails here.
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

    /**
     * The configuration half of the bounded wait: what the shipped profiles say.
     *
     * <p>Named for what it asserts rather than for the behaviour it supports. The behaviour - that a contended
     * row actually fails fast - is asserted against a running database by the two methods named in this class's
     * documentation, because no reading of a text file can establish it.
     */
    @Nested
    @DisplayName("The lock ceiling is written into the shipped configuration, and delivered by a mechanism that works")
    final class BoundedLockWait {

        @Test
        @DisplayName("the base profile pushes a lock ceiling onto every pooled connection")
        void theBaseProfileSetsALockTimeoutOnEveryConnection() throws IOException {
            final String live = liveYaml(profile("application.yml"));

            assertThat(live)
                    .as("the ceiling must reach every pooled connection, so it is a driver-level startup "
                            + "option rather than a statement issued after the connection exists. This "
                            + "assertion is about the TEXT; that the option is in effect is asserted "
                            + "against a live session by theShippedLockCeilingIsInEffectOnAPooledSession "
                            + "in RepositorySchemaAndFinderIntegrationTest")
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
                            duration of the competing transaction. That measurement is now re-runnable \
                            rather than historical: theShippedLockCeilingIsInEffectOnAPooledSession in \
                            RepositorySchemaAndFinderIntegrationTest reads the ceiling back after an \
                            explicit BEGIN and ROLLBACK, which is the assertion a transactional delivery \
                            mechanism fails. Do not reintroduce it.""")
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
        @DisplayName("the base profile binds the runtime role outright, with no fallback and no committed secret")
        void theBaseProfileBindsTheRuntimeRole() throws IOException {
            final String live = liveYaml(profile("application.yml"));

            assertThat(live)
                    .as("the request-serving connection must name the DML-only role, and only that role")
                    .contains("username: ${" + APP_USER_VARIABLE + "}")
                    .contains("password: ${" + APP_PASSWORD_VARIABLE + "}");
        }

        @ParameterizedTest(name = "{0} requires its role variables outright")
        @ValueSource(strings = {"application.yml", "application-local.yml", "application-prod.yml"})
        @DisplayName("no profile that binds a role can fall back to the bootstrap superuser")
        void noProfileFallsBackToTheBootstrapRole(final String resourceName) throws IOException {
            final String live = liveYaml(profile(resourceName));

            assertThat(live)
                    .as("""
                            A ':' inside any of the four placeholders reintroduces exactly the defect this \
                            closes. The base and local profiles once read \
                            ${CARDDEMO_DB_APP_USER:${POSTGRES_USER}} as a transition aid, and while that \
                            existed an ABSENT variable - the default state of every deployment not yet told \
                            about these roles - silently reconnected the runtime as the cluster bootstrap \
                            SUPERUSER. Nothing reported it, because falling back is indistinguishable from \
                            succeeding. %s must fail instead.""", resourceName)
                    .doesNotContain(APP_USER_VARIABLE + ":")
                    .doesNotContain(APP_PASSWORD_VARIABLE + ":")
                    .doesNotContain(MIGRATION_USER_VARIABLE + ":")
                    .doesNotContain(MIGRATION_PASSWORD_VARIABLE + ":");
            assertThat(live)
                    .as("the bootstrap pair is a SUPERUSER credential and no profile may read it for any "
                            + "purpose; %s composes the URL from POSTGRES_HOST, POSTGRES_PORT and "
                            + "POSTGRES_DB alone", resourceName)
                    .doesNotContain("${POSTGRES_USER")
                    .doesNotContain("${POSTGRES_PASSWORD");
        }

        @Test
        @DisplayName("production requires all four values outright, so it cannot silently run as the superuser")
        void productionSuppliesNoFallbackAtAll() throws IOException {
            assertThat(liveYaml(profile("application-prod.yml")))
                    .as("a production start must fail rather than fall back to the bootstrap superuser")
                    .contains("username: ${" + APP_USER_VARIABLE + "}")
                    .contains("password: ${" + APP_PASSWORD_VARIABLE + "}")
                    .contains("user: ${" + MIGRATION_USER_VARIABLE + "}")
                    .contains("password: ${" + MIGRATION_PASSWORD_VARIABLE + "}");
        }

        @Test
        @DisplayName("Flyway binds the separate DDL-owning role wherever it is bound at all")
        void flywayBindsTheMigrationRole() throws IOException {
            assertThat(liveYaml(profile("application-local.yml")))
                    .as("the only principal that may reshape the schema must be the one that migrates it")
                    .contains("user: ${" + MIGRATION_USER_VARIABLE + "}")
                    .contains("password: ${" + MIGRATION_PASSWORD_VARIABLE + "}");
        }

        @Test
        @DisplayName("the local profile inherits the runtime credentials rather than restating them")
        void theLocalProfileInheritsTheRuntimeCredentials() throws IOException {
            final String live = liveYaml(profile("application-local.yml"));

            assertThat(live)
                    .as("""
                            This is the profile docker-compose.yml activates, so whatever it declares decides \
                            the local principal whatever the base says - which is why its two fallbacks, not \
                            the base's, were what actually connected every local run as the superuser. \
                            Inheriting the base's bare placeholders is the control: restating them could only \
                            weaken it, and repeating them identically would be duplication that can drift.""")
                    .doesNotContain("username:")
                    .doesNotContain(APP_PASSWORD_VARIABLE);
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

        /**
         * The provisioning script itself, with its explanatory comments removed.
         *
         * <p>The block documents at length the fail-open shape it replaced - the {@code :-} defaults, the
         * blank-password check and the {@code exit 0} that followed it - so an assertion made against the raw
         * text would be satisfied, or defeated, by prose. Only the executable lines survive this filter.
         *
         * @return the script's live shell and SQL lines
         * @throws IOException if the Compose definition cannot be read
         */
        private String provisioningScript() throws IOException {
            final String content = compose();
            final int start = content.indexOf("postgres-least-privilege-roles:");
            assertThat(start).as("the provisioning config block must be present").isNotNegative();
            return content.substring(start).lines()
                    .filter(line -> !line.stripLeading().startsWith("#"))
                    // "-- " is an SQL comment; "--set" and "--no-psqlrc" are psql options and must survive,
                    // because an option is exactly what these assertions are looking for.
                    .filter(line -> !line.stripLeading().startsWith("-- "))
                    .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
                            StringBuilder::append)
                    .toString();
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

        @ParameterizedTest(name = "{0} is required with a :? guard, not defaulted")
        @ValueSource(strings = {
            "CARDDEMO_DB_APP_USER", "CARDDEMO_DB_APP_PASSWORD",
            "CARDDEMO_DB_MIGRATION_USER", "CARDDEMO_DB_MIGRATION_PASSWORD"})
        @DisplayName("all four role variables are guarded, so an absent or empty value stops `compose config`")
        void allFourRoleVariablesAreGuarded(final String variable) throws IOException {
            // The `services:` region only. Compose interpolates these four names exactly twice each, on the
            // application service and on the database service, and the `:-` prohibition below belongs to that
            // region alone: the provisioning script uses POSIX `${name:-}` expansions deliberately, so that
            // `set -u` cannot abort before the guard has had a chance to name the missing variable.
            final String compose = compose();
            final String services = compose.substring(
                    compose.indexOf("\nservices:"), compose.indexOf("\nconfigs:"));

            assertThat(services)
                    .as("""
                            Compose's `:-` treats an EMPTY value as unset, so the earlier \
                            ${%s:-...} form resolved a blank variable to the bootstrap \
                            POSTGRES_USER/POSTGRES_PASSWORD pair and started a stack that looked healthy while \
                            serving every request as the cluster superuser. `:?` turns the same condition into \
                            a named failure out of `docker compose config`, before a container exists.""",
                            variable)
                    .contains("${" + variable + ":?");
            assertThat(services)
                    .as("a `:-` default on %s would reinstate the fail-open path", variable)
                    .doesNotContain("${" + variable + ":-");
        }

        @Test
        @DisplayName("the application service is never handed the bootstrap superuser credential")
        void theApplicationServiceReceivesNoBootstrapCredential() throws IOException {
            final String content = compose();
            final int start = content.indexOf("\n  app:");
            final int end = content.indexOf("\n  postgres:");
            assertThat(start).as("the app service must be present").isNotNegative();
            assertThat(end).as("the postgres service must follow it").isGreaterThan(start);
            final String appService = content.substring(start, end).lines()
                    .filter(line -> !line.stripLeading().startsWith("#"))
                    .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
                            StringBuilder::append)
                    .toString();

            assertThat(appService)
                    .as("""
                            No profile the application activates reads POSTGRES_USER or POSTGRES_PASSWORD any \
                            longer, so passing them into this container would hand it a cluster SUPERUSER \
                            credential it has no use for. A credential that never arrives cannot be bound, \
                            logged or leaked. The database service still receives the pair, because the image \
                            itself needs it to initialise the cluster.""")
                    .doesNotContain("POSTGRES_USER")
                    .doesNotContain("POSTGRES_PASSWORD");
        }

        @Test
        @DisplayName("provisioning fails closed: it exits non-zero rather than leaving the superuser in place")
        void provisioningFailsClosedOnAMissingValue() throws IOException {
            final String script = provisioningScript();

            assertThat(script)
                    .as("""
                            The earlier script defaulted both role names, treated a blank password as a reason \
                            to `exit 0` with an explanatory log line, and so reported success for a run that \
                            had left the bootstrap SUPERUSER serving every request. Measured against the \
                            delivered script: with CARDDEMO_DB_APP_PASSWORD absent the container now exits 1 \
                            and names the missing variable, and initialisation aborts.""")
                    .contains("exit 1")
                    .doesNotContain("exit 0")
                    .doesNotContain("carddemo_app")
                    .doesNotContain("carddemo_migrator");
        }

        @Test
        @DisplayName("neither role password is passed as a psql argument, where argv would expose it")
        void neitherPasswordReachesProcessArgv() throws IOException {
            final String script = provisioningScript();

            assertThat(script)
                    .as("""
                            A --set assignment puts the value in psql's argv, which /proc/<pid>/cmdline and \
                            `ps` expose to anything else running in the container for as long as psql lives. \
                            \\getenv imports the same value from the environment psql already inherits, so it \
                            never appears on a command line. Verified against a provisioned container: the \
                            stored SCRAM-SHA-256 verifier for each role is the one derived from the imported \
                            password, so the import is real rather than an unsubstituted literal.""")
                    .doesNotContain("--set app_password")
                    .doesNotContain("--set migration_password")
                    .doesNotContain("--set app_user")
                    .doesNotContain("--set migration_user")
                    .contains("\\getenv app_password " + APP_PASSWORD_VARIABLE)
                    .contains("\\getenv migration_password " + MIGRATION_PASSWORD_VARIABLE);
            assertThat(script)
                    .as("both values are dropped once they have been used - the psql variables so that a \\set "
                            + "listing cannot print them, and the environment copies so that nothing the "
                            + "entrypoint runs afterwards inherits them")
                    .contains("\\unset app_password")
                    .contains("\\unset migration_password")
                    .contains("unset " + APP_PASSWORD_VARIABLE + " " + MIGRATION_PASSWORD_VARIABLE);
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

    /**
     * One spelling of each role principal, across every layer that names one.
     *
     * <p>The defect these assertions close was a SPLIT PAIR. The app service defaulted both role
     * names to {@code ${POSTGRES_USER:-carddemo}} - the cluster bootstrap SUPERUSER - while the
     * postgres service and the {@code postgres-least-privilege-roles} config defaulted them to
     * {@code carddemo_app} and {@code carddemo_migrator}, and the app service took its passwords from
     * {@code POSTGRES_PASSWORD} while the provisioning step skipped both roles when the dedicated
     * passwords were absent. A start that supplied nothing therefore created two least-privilege
     * roles that nothing then used, and served every request as the superuser - with the entire
     * least-privilege block apparently in place. The name was read from one source and the password
     * from another, so the pair could disagree about which role was meant.
     *
     * <p>Every assertion here reads the TEXT of the committed files rather than a running stack,
     * because the defect lived in the DEFAULTS - the values that apply precisely when nobody supplied
     * anything, which is the one case no deployment exercises on purpose.
     */
    @Nested
    @DisplayName("One spelling of each database role, across app, postgres, the role script and CI")
    final class OneSpellingOfEachRole {

        /** The four variables that name and authenticate the two dedicated roles. */
        private final List<String> roleVariables = List.of(
                APP_USER_VARIABLE, APP_PASSWORD_VARIABLE, MIGRATION_USER_VARIABLE, MIGRATION_PASSWORD_VARIABLE);

        /**
         * Reads the Compose definition.
         *
         * @return the committed Compose text
         * @throws IOException if it cannot be read
         */
        private String compose() throws IOException {
            return repositoryFile("docker-compose.yml");
        }

        /**
         * Reads the continuous-integration workflow.
         *
         * @return the committed workflow text
         * @throws IOException if it cannot be read
         */
        private String workflow() throws IOException {
            return repositoryFile(".github/workflows/build.yml");
        }

        /**
         * Extracts one top-level service block, from its key to the next key at the same indent.
         *
         * @param service the service name, such as {@code app}
         * @return that service's block, comments included
         * @throws IOException if the Compose definition cannot be read
         */
        private String serviceBlock(final String service) throws IOException {
            final String content = compose();
            final int start = content.indexOf("\n  " + service + ":\n");
            assertThat(start).as("the %s service must be declared", service).isNotNegative();
            final Matcher next = Pattern.compile("^  [a-zA-Z0-9_-]+:", Pattern.MULTILINE)
                    .matcher(content);
            final int end = next.find(start + service.length() + 4) ? next.start() : content.length();
            return content.substring(start, end);
        }

        /**
         * Reads the right-hand side of one environment entry inside a service block.
         *
         * @param block the service block
         * @param variable the environment variable name
         * @return the interpolation expression exactly as committed
         */
        private String expression(final String block, final String variable) {
            final Matcher matcher = Pattern.compile(
                    "^\\s*" + Pattern.quote(variable) + ": (.*)$", Pattern.MULTILINE).matcher(block);
            assertThat(matcher.find()).as("%s must be set on this service", variable).isTrue();
            return matcher.group(1).strip();
        }

        @Test
        @DisplayName("the app and postgres services spell all four role expressions identically")
        void theTwoServicesSpellEveryRoleExpressionIdentically() throws IOException {
            final String app = serviceBlock("app");
            final String database = serviceBlock("postgres");

            for (final String variable : roleVariables) {
                assertThat(expression(app, variable))
                        .as("""
                                %s must be spelled identically on both services. The role the                                 provisioning script creates and the role the application                                 authenticates as are then read from one variable with one default,                                 so they cannot diverge - which is what they did.""", variable)
                        .isEqualTo(expression(database, variable));
            }
        }

        @Test
        @DisplayName("no role expression can resolve to the bootstrap superuser")
        void noRoleExpressionCanResolveToTheBootstrapSuperuser() throws IOException {
            final Matcher fallback = Pattern.compile(
                    Pattern.quote("CARDDEMO_DB_") + "(?:APP|MIGRATION)_(?:USER|PASSWORD)"
                            + ":-\\$\\{POSTGRES_").matcher(liveYaml(compose()));

            assertThat(fallback.find())
                    .as("""
                            POSTGRES_USER is created by the postgres image as a cluster SUPERUSER, and a                             superuser ignores every grant, every row-level policy and every column                             privilege. A dedicated role that falls back to it is not a dedicated role.""")
                    .isFalse();
        }

        @Test
        @DisplayName("both role passwords stop the stack rather than defaulting to anything")
        void bothRolePasswordsStopTheStackRatherThanDefaulting() throws IOException {
            for (final String service : List.of("app", "postgres")) {
                final String block = serviceBlock(service);
                for (final String variable : List.of(APP_PASSWORD_VARIABLE, MIGRATION_PASSWORD_VARIABLE)) {
                    assertThat(expression(block, variable))
                            .as("""
                                    %s on the %s service must carry Compose's `:?` guard, like                                     POSTGRES_PASSWORD and the signing key. A default here is what lets a                                     name arrive without its password.""", variable, service)
                            .contains(":?");
                }
            }
        }

        @Test
        @DisplayName("the provisioning script creates the very name the application authenticates with")
        void theProvisioningScriptCreatesTheNameTheApplicationAuthenticatesWith() throws IOException {
            final String content = compose();

            // The services guard all four names with `:?` and carry no `:-` default, so each name has
            // exactly ONE source and the role that gets created cannot differ from the role the
            // application authenticates as. What has to be asserted is therefore that the script reads
            // the SAME variable rather than a default of its own: an earlier revision defaulted the two
            // names inside the script while the app service defaulted them to POSTGRES_USER, so the
            // provisioning step created two roles nobody used and every request ran as the superuser.
            for (final String variable : List.of(APP_USER_VARIABLE, MIGRATION_USER_VARIABLE)) {
                assertThat(Pattern.compile("\\\\getenv\\s+\\w+\\s+" + Pattern.quote(variable))
                                .matcher(content)
                                .find())
                        .as("""
                                the script must bind %s through psql's \\getenv - which also keeps the \
                                value out of process argv - rather than deriving the name from a literal \
                                of its own""", variable)
                        .isTrue();

                final Matcher literalDefault = Pattern.compile(
                        Pattern.quote(variable) + ":-([^}\n]+)}").matcher(content);
                while (literalDefault.find()) {
                    assertThat(literalDefault.group(1).strip())
                            .as("""
                                    a non-empty `:-` default for %s inside the provisioning config would \
                                    override the single guarded source the services read, which is how a \
                                    role came to be created under one name and authenticated under \
                                    another""", variable)
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("the provisioning script refuses rather than skipping when a password is absent")
        void theProvisioningScriptRefusesRatherThanSkipping() throws IOException {
            final String content = compose();

            assertThat(content)
                    .as("""
                            Skipping left the roles uncreated while the application connected as the                             superuser - the exact outcome the script exists to prevent - and reported it                             only on stderr during initialisation, where nobody reads it.""")
                    .contains("refusing to initialise")
                    .doesNotContain("exit 0");
        }

        @Test
        @DisplayName("continuous integration exports both role names beside both generated passwords")
        void continuousIntegrationExportsBothNamesBesideBothPasswords() throws IOException {
            assertThat(workflow())
                    .as("""
                            The workflow generated both role passwords and exported neither name, so its                             rendered topology took the app service's superuser default. Names are not                             secret, so they belong in the plain block beside the generated values.""")
                    .contains("generated " + APP_PASSWORD_VARIABLE)
                    .contains("generated " + MIGRATION_PASSWORD_VARIABLE)
                    .contains(APP_USER_VARIABLE + "=carddemo_app")
                    .contains(MIGRATION_USER_VARIABLE + "=carddemo_migrator");
        }

        @Test
        @DisplayName("continuous integration validates the rendered contract, not just the committed text")
        void continuousIntegrationValidatesTheRenderedContract() throws IOException {
            final String content = workflow();

            assertThat(content)
                    .as("""
                            Interpolation decides the outcome, so the assertion has to be made on the                             output of `docker compose config` rather than on the file - a default is                             invisible until it is resolved.""")
                    .contains("configs.postgres-least-privilege-roles")
                    .contains("to match the rendered service value")
                    .contains("a dedicated role distinct ");
            for (final String variable : roleVariables) {
                assertThat(content)
                        .as("the rendered check must cover %s", variable)
                        .contains(variable);
            }
        }

        @Test
        @DisplayName("the template documents all four names and ships neither password with a value")
        void theTemplateShipsNeitherPasswordWithAValue() throws IOException {
            final String template = repositoryFile(".env.example");

            for (final String variable : roleVariables) {
                assertThat(template)
                        .as("%s must be documented so a reader knows it exists", variable)
                        .contains(variable + "=");
            }
            for (final String variable : List.of(APP_PASSWORD_VARIABLE, MIGRATION_PASSWORD_VARIABLE)) {
                final Matcher assignment = Pattern.compile(
                        "^" + Pattern.quote(variable) + "=(.*)$", Pattern.MULTILINE).matcher(template);
                assertThat(assignment.find()).as("%s must be assigned", variable).isTrue();
                assertThat(assignment.group(1))
                        .as("""
                                a committed value for %s is a committed credential, and a predictable                                 one is worse than none: it is the value a reader copies.""", variable)
                        .isEmpty();
            }
        }
    }
}
