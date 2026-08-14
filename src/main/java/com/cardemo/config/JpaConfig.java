/*
 * ******************************************************************
 * Program     : JpaConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (persistence layer)
 * Function    : Relational substrate configuration - entity scanning,
 *               naming strategy and transaction management replacing
 *               the VSAM catalogue and the IDCAMS DEFINE CLUSTER jobs.
 * Source      : app/catlg/LISTCAT.txt (3,956 lines; 10 base clusters,
 *               3 alternate indexes, 3 matching paths)
 *               + app/jcl/DUSRSECJ.jcl (USRSEC KEYS(8,0) RECORDSIZE(80,80))
 *               + app/cpy/CVTRA01Y.cpy + app/cpy/CVTRA02Y.cpy
 *               + app/cpy/CVTRA05Y.cpy (field precisions)
 *               + app/cbl/COACTUPC.cbl (4,236 lines; snapshot comparison,
 *                 asymmetric SYNCPOINT ROLLBACK)
 *               + app/cbl/CBTRN02C.cbl (731 lines; three-write posting unit)
 *               @ 7756d89
 * Replaces    : the VSAM KSDS clusters, alternate indexes and paths, and
 *               the 12 IDCAMS DEFINE CLUSTER provisioning jobs
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
package com.cardemo.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Persistence-layer configuration: the startup guard that proves the mechanisms enforcing field-contract
 * parity are actually armed before any request or batch step runs.
 *
 * <h2>What it does</h2>
 *
 * <p>It declares exactly one bean, {@link #persistenceContractGuard()}, which invokes
 * {@link #verifyPersistenceContract()} during context refresh. That method asserts nine configuration
 * invariants and throws {@link IllegalStateException} on the first violation, so a misconfigured application
 * refuses to start rather than starting and quietly reshaping the schema.
 *
 * <p>The guard exists because the enforcement mechanism it protects is itself only a property.
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in the base profile, and that single value is
 * what turns a divergence between an entity mapping and the migration-owned schema into a deterministic
 * startup failure. Nothing in the framework stops a profile, an environment override or a command-line
 * argument from changing it to {@code update} or {@code create}, at which point the mapping silently becomes
 * authoritative over the copybook-derived column widths and parity is lost with no error reported. The same
 * argument applies to {@code spring.flyway.clean-disabled}, which is destructive when false and must stay
 * true in every profile including test.
 *
 * <h2>What it deliberately does not declare</h2>
 *
 * <p>Each omission is a decision, because configuration that has no effect is dead code that merely looks
 * authoritative.
 *
 * <ul>
 *   <li><strong>No {@code @EnableJpaRepositories}, {@code @EntityScan} or
 *       {@code @EnableTransactionManagement}.</strong> The entities sit in {@code com.cardemo.model.entity}
 *       and the repositories in {@code com.cardemo.repository}, both beneath the {@code com.cardemo} base
 *       package that {@code com.cardemo.CardDemoApplication} establishes, so auto-configuration discovers
 *       both and each annotation would restate a default without changing a resolved bean.</li>
 *   <li><strong>No physical naming strategy bean.</strong> A naming strategy resolves only <em>implicit</em>
 *       names, and there are none: all eleven entities name their table with {@code @Table(name = ...)} and
 *       every persistent field names its column with {@code @Column(name = ...)}, precisely so that nothing
 *       sits between the copybook and the column. One would be inert today and would start deriving names
 *       the day a {@code @Column} was omitted.</li>
 *   <li><strong>No second data source, entity manager factory, transaction manager or transaction
 *       template.</strong> Auto-configuration supplies all four from the resolved properties; a second would
 *       add an ambiguity needing a primary marker, for no gain.</li>
 *   <li><strong>No schema, table, index, constraint or key definition.</strong> The schema is owned by
 *       {@code src/main/resources/db/migration} and by nothing else; the column widths and precisions derive
 *       from the copybooks in {@code app/cpy} and the catalogued key and record lengths in
 *       {@code app/catlg/LISTCAT.txt}. Restating any of it here would create a second place to change it.</li>
 *   <li><strong>No fourth migration.</strong> Exactly three exist. The Spring Batch metadata tables come
 *       from the framework's own schema script by way of {@code spring.batch.jdbc.initialize-schema}, so
 *       adding them as a migration would break the gate that counts eleven tables in the first one.</li>
 *   <li><strong>No clock, object-store client, security filter chain, observability registry or web
 *       binding.</strong> Those belong to the sibling configuration classes; this one is scoped to
 *       persistence.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every key is <strong>owned by</strong> {@code src/main/resources/application.yml} and its profile
 * siblings. This class reads and asserts them and defines none of them, so no value can be changed here. In
 * six of the nine cases the value this application needs is <em>not</em> the framework default, which is why
 * an override is dangerous and why the guard exists.
 *
 * <dl>
 *   <dt>{@code spring.jpa.hibernate.ddl-auto} - required {@code validate}</dt>
 *   <dd>Turns a missing table, a missing column or a changed column type into a startup failure. It does
 *       <em>not</em> cover numeric precision or scale. {@code create}, {@code create-drop} and
 *       {@code update} are each forbidden: every one lets the provider reshape a table away from the
 *       copybook record layout silently.</dd>
 *   <dt>{@code spring.jpa.open-in-view} - required {@code false}</dt>
 *   <dd>The framework default is {@code true}, which holds a connection open for the whole request and hides
 *       lazy access behind the view layer.</dd>
 *   <dt>{@code spring.jpa.show-sql} - required {@code false}</dt>
 *   <dd>Statement and bind-parameter logging would expose the customer social security number and the stored
 *       password hashes of the seeded users. The provider's statement and bind loggers are additionally held
 *       at warning level, so raising the provider package to debug still cannot print a bound value.</dd>
 *   <dt>{@code spring.jpa.properties.hibernate.jdbc.time_zone} - required {@code UTC}</dt>
 *   <dd>Sessions run in coordinated universal time so a server's local zone can never shift a stored value.
 *       This affects genuine temporal columns only; the two 26-byte timestamp columns are {@code CHAR} data
 *       carried as text, which the key's name misleadingly suggests otherwise about.</dd>
 *   <dt>{@code spring.flyway.enabled} - required {@code true}</dt>
 *   <dd>With migration disabled there is no schema at all, and {@code validate} then fails with a confusing
 *       missing-table diagnostic instead of the real cause.</dd>
 *   <dt>{@code spring.flyway.baseline-on-migrate} - required {@code false}</dt>
 *   <dd>Baselining would silently adopt an unknown pre-existing schema as the starting point, which defeats
 *       the field-contract argument entirely.</dd>
 *   <dt>{@code spring.flyway.validate-on-migrate} - required {@code true}</dt>
 *   <dd>Detects a checksum change to an already-applied migration - the signal that history was edited
 *       rather than appended to.</dd>
 *   <dt>{@code spring.flyway.clean-disabled} - required {@code true}</dt>
 *   <dd>The operation it disables destroys every object in the schema, in every profile including test.</dd>
 *   <dt>{@code spring.flyway.out-of-order} - required {@code false}</dt>
 *   <dd>Ordered application is load-bearing: the third migration seeds rows that rely on the indexes the
 *       second creates.</dd>
 *   <dt>{@code spring.datasource.username} / {@code .password} and {@code spring.flyway.user} /
 *       {@code .password} - required to be resolvable and non-blank <em>where they are in force</em></dt>
 *   <dd>Asserted by {@link #verifyRuntimeCredentials(String, String, java.util.function.UnaryOperator)}
 *       rather than
 *       by the contract guard above, because the failure they produce is diagnostic rather than structural:
 *       the values are bound bare from {@code CARDDEMO_DB_APP_*} and {@code CARDDEMO_DB_MIGRATION_*} with no
 *       fallback, and the lenient {@code @ConfigurationProperties} binder passes an unset variable through as
 *       the literal text {@code ${CARDDEMO_DB_APP_USER}}, which PostgreSQL then reports as a failed
 *       authentication for a role of that name. Two exemptions are part of the contract: the two datasource
 *       keys are not in force when a {@link JdbcConnectionDetails} bean supersedes them, which is how the
 *       Testcontainers profile runs, and an ABSENT Flyway credential is valid because the base profile leaves
 *       it unbound deliberately.</dd>
 *   <dt>{@code spring.flyway.locations} and {@code spring.batch.jdbc.initialize-schema}</dt>
 *   <dd><strong>Documented, deliberately not asserted.</strong> The first is list-typed, so its YAML shape
 *       may legitimately be a scalar or a sequence and a scalar-only assertion would reject a valid
 *       sequence. The second is legitimately profile-dependent - {@code never} in the base and production
 *       profiles, {@code always} in local and test - so no single value is correct everywhere. Asserting
 *       either would put a false-positive class into a guard whose whole value is that it never cries
 *       wolf.</dd>
 *   </dl>
 *
 * <p>Owned elsewhere and not this class's to police: statement batching and insert/update ordering on the
 * provider, the migration history table name and encoding, the isolation used when the batch metadata tables
 * are created, and the fact that jobs do not auto-launch. The data-source coordinates arrive entirely through
 * environment-indirected properties; no connection string, user name or password is written, logged or quoted
 * anywhere in this class.
 *
 * <h2>How to build, run and test</h2>
 *
 * <ul>
 *   <li>Compile: {@code ./mvnw -B -ntp clean compile}. All lint categories are enabled and warnings are
 *       escalated to errors.</li>
 *   <li>Unit suite: {@code ./mvnw -B -ntp test}. {@link #verifyPersistenceContract()} is unit-testable with
 *       no container and no Spring context: construct this class with the nine string arguments and invoke
 *       it.</li>
 *   <li>Full verification: {@code ./mvnw -B -ntp clean verify}, which adds the integration tier and the
 *       coverage and vulnerability gates.</li>
 *   <li>Runtime dependencies: {@code docker compose up -d}. The migrations take ownership of an empty
 *       schema on first boot, so the database must not be pre-migrated by hand.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails with a message from {@link #verifyPersistenceContract()} naming a property</dt>
 *   <dd>That property was overridden to a value the contract forbids. The message states the key, the value
 *       found and the value required. Restore the required value rather than relaxing the guard; if the value
 *       came from an environment override or a command-line argument, that override is the defect.</dd>
 *   <dt>Startup fails with "Database credential not usable" naming a variable</dt>
 *   <dd>That environment variable is unset, or exported empty. Set it and restart. The message names the
 *       property, the variable and the remedy and never echoes a value. Before this guard existed the same
 *       condition surfaced as {@code FATAL: password authentication failed for user
 *       "${CARDDEMO_DB_APP_USER}"} wrapped in a message about the Spring Batch metadata table being
 *       unreadable by the runtime role - two true sentences, neither naming the cause. Under
 *       {@code docker compose} the condition cannot arise: the compose file guards all four with
 *       Compose's {@code :?} so the container is never created.</dd>
 *   <dt>Startup fails saying a property is not configured</dt>
 *   <dd>The base configuration file was not on the classpath, or the key was removed from it - most often a
 *       narrowly sliced test that loaded a property source without the base profile.</dd>
 *   <dt>The provider reports a missing table, missing column or wrong column type at startup</dt>
 *   <dd>The validate mechanism working as designed: an entity mapping and the migrated schema have diverged.
 *       Decide which is wrong by reading the copybook the field derives from, then fix that one.
 *       <strong>Never switch the mapping mode to update or create to make the message go away.</strong></dd>
 *   <dt>A money value rounds or overflows unexpectedly and startup reported nothing</dt>
 *   <dd>Suspect a precision or scale divergence, which validate does not catch. The common instance is an
 *       eleven-and-two field given the twelve-and-two of the account money columns; ordinary values fit in
 *       both and only a boundary value separates them. Compare the entity's declared precision against the
 *       schema migration and against the PIC clause in the copybook, in that order.</dd>
 *   <dt>Migration fails with a checksum mismatch</dt>
 *   <dd>An already-applied migration was edited. Migrations are append-only once applied: revert the edit and
 *       add a new one, or recreate the database from empty in a disposable local environment.</dd>
 *   <dt>An update silently does nothing, or reports a conflict on every attempt</dt>
 *   <dd>The business-level snapshot comparison rather than the version column - both layers are required, and
 *       the comparison is owned by {@code com.cardemo.service.account.AccountUpdateService}. A conflict on
 *       <em>every</em> request is the signature of a whole-string date-of-birth comparison where the source
 *       compares components at differing offsets.</dd>
 *   </dl>
 *
 * <h2>Deferred hardening and residual risk</h2>
 *
 * <p>Disclosed rather than silently absorbed. None is implemented and none is a defect - each is a scoped-out
 * decision with a stated consequence: <strong>connection-pool tuning</strong> (the pool ships at its
 * defaults; exhaustion under an unmeasured load would present as latency rather than an error),
 * <strong>table partitioning</strong> (the transaction table grows without bound, and partitioning needs a
 * retention policy the source does not state), <strong>read replicas</strong> (one data source serves reads
 * and writes, which is the simplest arrangement that keeps the single-transaction atomicity the source
 * requires), and <strong>encryption at rest for personally identifiable data</strong> (the customer row
 * carries a social security number and a date of birth in clear columns, mirroring the source layout;
 * column encryption would change the stored representation and therefore the parity comparison).
 *
 * <p><strong>No performance target is asserted.</strong> The source corpus publishes no service-level
 * objective for throughput, latency or concurrency, so none is stated here and none may be invented; the
 * project records a measured baseline instead.
 *
 * <p>One catalogue entry is deliberately not modelled: {@code app/jcl/DEFCUST.jcl:L35-L38} defines an orphan
 * cluster {@code AWS.CUSTDATA.CLUSTER} with {@code KEYS(10 0)} and {@code RECORDSIZE(500 500)} that
 * <strong>no program opens</strong>. The customer row derives from the catalogued {@code CUSTDATA} cluster,
 * key 9 and record 500, so this one's absence from the schema is a decision rather than an omission.
 */
@Configuration
public class JpaConfig {

    /** Property key: the schema management mode the provider applies at startup. */
    private static final String KEY_DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    /** Property key: whether a persistence session stays open for the whole web request. */
    private static final String KEY_OPEN_IN_VIEW = "spring.jpa.open-in-view";

    /** Property key: whether the provider echoes generated statements. */
    private static final String KEY_SHOW_SQL = "spring.jpa.show-sql";

    /** Property key: the time zone the provider uses for temporal binding. */
    private static final String KEY_HIBERNATE_TIME_ZONE = "spring.jpa.properties.hibernate.jdbc.time_zone";

    /** Property key: whether schema migration runs at all. */
    private static final String KEY_FLYWAY_ENABLED = "spring.flyway.enabled";

    /** Property key: whether an unknown pre-existing schema is adopted as a baseline. */
    private static final String KEY_FLYWAY_BASELINE_ON_MIGRATE = "spring.flyway.baseline-on-migrate";

    /** Property key: whether applied migrations are checksum-validated on every start. */
    private static final String KEY_FLYWAY_VALIDATE_ON_MIGRATE = "spring.flyway.validate-on-migrate";

    /** Property key: whether the destructive schema-clean operation is refused. */
    private static final String KEY_FLYWAY_CLEAN_DISABLED = "spring.flyway.clean-disabled";

    /** Property key: whether a migration may be applied after a higher-numbered one. */
    private static final String KEY_FLYWAY_OUT_OF_ORDER = "spring.flyway.out-of-order";

    /** Property key: the runtime principal's user name, bound from the environment in every profile. */
    private static final String KEY_DATASOURCE_USERNAME = "spring.datasource.username";

    /** Property key: the runtime principal's password. */
    private static final String KEY_DATASOURCE_PASSWORD = "spring.datasource.password";

    /** Property key: the migration principal's user name, bound per profile rather than in the base. */
    private static final String KEY_FLYWAY_USER = "spring.flyway.user";

    /** Property key: the migration principal's password. */
    private static final String KEY_FLYWAY_PASSWORD = "spring.flyway.password";

    /** Environment variable the runtime principal's user name is bound from. */
    private static final String VARIABLE_APP_USER = "CARDDEMO_DB_APP_USER";

    /** Environment variable the runtime principal's password is bound from. */
    private static final String VARIABLE_APP_PASSWORD = "CARDDEMO_DB_APP_PASSWORD";

    /** Environment variable the migration principal's user name is bound from. */
    private static final String VARIABLE_MIGRATION_USER = "CARDDEMO_DB_MIGRATION_USER";

    /** Environment variable the migration principal's password is bound from. */
    private static final String VARIABLE_MIGRATION_PASSWORD = "CARDDEMO_DB_MIGRATION_PASSWORD";

    /**
     * The opening delimiter of an unresolved property placeholder.
     *
     * <p>Its presence in a bound value is the whole signature of the defect this guard exists for: Boot's
     * {@code @ConfigurationProperties} binder resolves placeholders <em>leniently</em>, so an unset variable
     * does not fail the binding - it leaves the literal text {@code ${CARDDEMO_DB_APP_USER}} in the value and
     * that text is then sent to PostgreSQL as a role name.
     */
    private static final String UNRESOLVED_PLACEHOLDER_PREFIX = "${";

    /**
     * The only schema management mode this application tolerates. The migrations own the schema and the
     * provider's role is to disagree loudly, never to reshape a table away from its copybook layout.
     */
    private static final String REQUIRED_DDL_AUTO = "validate";

    /** The only provider time zone this application tolerates. */
    private static final String REQUIRED_TIME_ZONE = "UTC";

    /** Lower-cased literal for a true boolean, compared under {@link Locale#ROOT}. */
    private static final String TRUE_LITERAL = "true";

    /** Lower-cased literal for a false boolean, compared under {@link Locale#ROOT}. */
    private static final String FALSE_LITERAL = "false";

    /** Structured logger; the one bound value it emits is the validated contract summary. */
    private static final Logger LOG = LoggerFactory.getLogger(JpaConfig.class);

    /**
     * Bound value of {@code spring.jpa.hibernate.ddl-auto}, required to be
     * {@value #REQUIRED_DDL_AUTO}: this application never generates schema, because
     * {@code V1__create_schema.sql} owns it and the entities are checked against what it created.
     */
    private final String ddlAuto;

    /**
     * Bound value of {@code spring.jpa.properties.hibernate.jdbc.time_zone}, required to be
     * {@value #REQUIRED_TIME_ZONE} so that a 26-character timestamp reads identically wherever the
     * container runs.
     */
    private final String hibernateTimeZone;

    /** Bound value of {@code spring.jpa.open-in-view}, required to be false: no session outlives a service call. */
    private final boolean openInView;

    /** Bound value of {@code spring.jpa.show-sql}, required to be false: statement text is not log content. */
    private final boolean showSql;

    /** Bound value of {@code spring.flyway.enabled}; the three migrations are the only schema authority. */
    private final boolean flywayEnabled;

    /**
     * Bound value of {@code spring.flyway.baseline-on-migrate}, required to be false so an unmigrated
     * database is reported rather than silently adopted as a baseline.
     */
    private final boolean flywayBaselineOnMigrate;

    /**
     * Bound value of {@code spring.flyway.validate-on-migrate}, required to be true so a checksum drift in
     * an already-applied migration fails startup.
     */
    private final boolean flywayValidateOnMigrate;

    /** Bound value of {@code spring.flyway.clean-disabled}, required to be true: no path may drop the schema. */
    private final boolean flywayCleanDisabled;

    /**
     * Bound value of {@code spring.flyway.out-of-order}, required to be false so the three migrations can
     * only ever apply in their declared V1, V2, V3 order.
     */
    private final boolean flywayOutOfOrder;

    /**
     * Binds the nine persistence-contract properties and validates that each one is present and, where it is
     * a flag, parseable. Values are resolved by property binding alone; no environment variable is read
     * directly and no host path is consulted, so the resolved configuration is identical on every machine
     * that supplies the same property sources.
     *
     * <p>Each parameter is declared with an empty fallback rather than no fallback on purpose. Without a
     * fallback an absent key fails placeholder resolution with a generic message that names the placeholder
     * but explains nothing; with one, the absent key arrives here as an empty string and this constructor
     * raises a message that names the key, states that it is not configured, and says which file owns it.
     * That is the difference between a diagnosable failure and a puzzling one, and it is why the empty
     * fallback is not a way of tolerating absence - absence still stops the application, one frame earlier
     * and far more clearly.
     *
     * <p>Presence and parseability are checked here because that is where the value enters the object.
     * Whether a present, well-formed value is <em>permitted</em> is a separate question, decided by
     * {@link #verifyPersistenceContract()}: reading configuration and enforcing policy are different
     * concerns and are kept apart deliberately.
     *
     * <p>Only private static helpers are invoked, so no partially initialised instance is ever exposed to an
     * overridable method - a real consideration because a configuration class cannot be final.
     *
     * @param ddlAutoProperty                 raw value of {@code spring.jpa.hibernate.ddl-auto}
     * @param openInViewProperty              raw value of {@code spring.jpa.open-in-view}
     * @param showSqlProperty                 raw value of {@code spring.jpa.show-sql}
     * @param hibernateTimeZoneProperty       raw value of
     *                                        {@code spring.jpa.properties.hibernate.jdbc.time_zone}
     * @param flywayEnabledProperty           raw value of {@code spring.flyway.enabled}
     * @param flywayBaselineOnMigrateProperty raw value of {@code spring.flyway.baseline-on-migrate}
     * @param flywayValidateOnMigrateProperty raw value of {@code spring.flyway.validate-on-migrate}
     * @param flywayCleanDisabledProperty     raw value of {@code spring.flyway.clean-disabled}
     * @param flywayOutOfOrderProperty        raw value of {@code spring.flyway.out-of-order}
     * @throws IllegalStateException if any of the nine is absent, blank, or - for the seven flags - is
     *                               neither {@code true} nor {@code false} when compared under
     *                               {@link Locale#ROOT}
     */
    public JpaConfig(
            @Value("${" + KEY_DDL_AUTO + ":}") final String ddlAutoProperty,
            @Value("${" + KEY_OPEN_IN_VIEW + ":}") final String openInViewProperty,
            @Value("${" + KEY_SHOW_SQL + ":}") final String showSqlProperty,
            @Value("${" + KEY_HIBERNATE_TIME_ZONE + ":}") final String hibernateTimeZoneProperty,
            @Value("${" + KEY_FLYWAY_ENABLED + ":}") final String flywayEnabledProperty,
            @Value("${" + KEY_FLYWAY_BASELINE_ON_MIGRATE + ":}") final String flywayBaselineOnMigrateProperty,
            @Value("${" + KEY_FLYWAY_VALIDATE_ON_MIGRATE + ":}") final String flywayValidateOnMigrateProperty,
            @Value("${" + KEY_FLYWAY_CLEAN_DISABLED + ":}") final String flywayCleanDisabledProperty,
            @Value("${" + KEY_FLYWAY_OUT_OF_ORDER + ":}") final String flywayOutOfOrderProperty) {

        this.ddlAuto = requireConfigured(KEY_DDL_AUTO, ddlAutoProperty);
        this.hibernateTimeZone = requireConfigured(KEY_HIBERNATE_TIME_ZONE, hibernateTimeZoneProperty);
        this.openInView = requireFlag(KEY_OPEN_IN_VIEW, openInViewProperty);
        this.showSql = requireFlag(KEY_SHOW_SQL, showSqlProperty);
        this.flywayEnabled = requireFlag(KEY_FLYWAY_ENABLED, flywayEnabledProperty);
        this.flywayBaselineOnMigrate =
                requireFlag(KEY_FLYWAY_BASELINE_ON_MIGRATE, flywayBaselineOnMigrateProperty);
        this.flywayValidateOnMigrate =
                requireFlag(KEY_FLYWAY_VALIDATE_ON_MIGRATE, flywayValidateOnMigrateProperty);
        this.flywayCleanDisabled = requireFlag(KEY_FLYWAY_CLEAN_DISABLED, flywayCleanDisabledProperty);
        this.flywayOutOfOrder = requireFlag(KEY_FLYWAY_OUT_OF_ORDER, flywayOutOfOrderProperty);
    }

    /**
     * Asserts the nine persistence-contract invariants, one of the two pieces of executable behaviour this
     * class contributes.
     *
     * <p>Inputs are the nine values bound by the constructor; there are no parameters and no other state is
     * read. The only side effect on success is a single informational log record naming every verified value,
     * which exists so that an operator reading a startup log can see what the substrate was actually
     * configured with rather than inferring it. Every value in that record is a configuration flag - a mode
     * name, a time-zone identifier or a boolean - and none is a credential, a connection coordinate or a
     * bound statement parameter, so the record is safe to emit at an ordinary logging level.
     *
     * <p>The checks run in a fixed order, from the mapping mode outward, so that the first message an
     * operator sees is the most consequential violation rather than whichever check happened to run first.
     * Each raises {@link IllegalStateException} with the key, the value found, the value required and the
     * reason the invariant exists. The exception is unchecked and is intentionally not one of the project's
     * own exception types: those model the translation of legacy file-status outcomes on data-access paths,
     * whereas this is a configuration defect detected before any data path exists, and it belongs to the
     * container's bean-initialisation failure channel where the framework will wrap it with the bean name and
     * the full context. Nothing is caught here, so no cause is ever discarded.
     *
     * <p>This method is idempotent and free of external interaction: it neither opens a connection nor reads
     * a file, which is what makes it callable directly from a unit test with plain string arguments.
     *
     * @throws IllegalStateException on the first invariant that does not hold
     */
    public void verifyPersistenceContract() {
        requireValue(KEY_DDL_AUTO, this.ddlAuto, REQUIRED_DDL_AUTO,
                "the migrations own the schema and the provider must reject a divergence, never reshape a "
                        + "table away from its copybook record layout");
        requireValue(KEY_HIBERNATE_TIME_ZONE, this.hibernateTimeZone, REQUIRED_TIME_ZONE,
                "temporal binding must not depend on the host time zone");
        requireState(KEY_OPEN_IN_VIEW, this.openInView, false,
                "a session must not stay open for the whole request, holding a connection and hiding lazy "
                        + "access behind the view layer");
        requireState(KEY_SHOW_SQL, this.showSql, false,
                "statement and bind-parameter logging would expose the customer social security number and "
                        + "the stored password hashes of the seeded users");
        requireState(KEY_FLYWAY_ENABLED, this.flywayEnabled, true,
                "with migration disabled there is no schema for the provider to validate against");
        requireState(KEY_FLYWAY_BASELINE_ON_MIGRATE, this.flywayBaselineOnMigrate, false,
                "baselining would silently adopt an unknown pre-existing schema as the starting point");
        requireState(KEY_FLYWAY_VALIDATE_ON_MIGRATE, this.flywayValidateOnMigrate, true,
                "an edit to an already-applied migration must be reported, not applied");
        requireState(KEY_FLYWAY_CLEAN_DISABLED, this.flywayCleanDisabled, true,
                "the operation it disables destroys every object in the schema and must stay refused in "
                        + "every profile, test included");
        requireState(KEY_FLYWAY_OUT_OF_ORDER, this.flywayOutOfOrder, false,
                "ordered application is load-bearing: the seed migration relies on the indexes the "
                        + "preceding one creates");

        LOG.info("Persistence contract verified: {}={} {}={} {}={} {}={} {}={} {}={} {}={} {}={} {}={}",
                KEY_DDL_AUTO, this.ddlAuto,
                KEY_HIBERNATE_TIME_ZONE, this.hibernateTimeZone,
                KEY_OPEN_IN_VIEW, this.openInView,
                KEY_SHOW_SQL, this.showSql,
                KEY_FLYWAY_ENABLED, this.flywayEnabled,
                KEY_FLYWAY_BASELINE_ON_MIGRATE, this.flywayBaselineOnMigrate,
                KEY_FLYWAY_VALIDATE_ON_MIGRATE, this.flywayValidateOnMigrate,
                KEY_FLYWAY_CLEAN_DISABLED, this.flywayCleanDisabled,
                KEY_FLYWAY_OUT_OF_ORDER, this.flywayOutOfOrder);
    }

    /**
     * Registers the persistence-contract guard as a singleton so that
     * {@link #verifyPersistenceContract()} runs during context refresh.
     *
     * <p>Returned as an {@link InitializingBean} because that gives the earliest deterministic hook the
     * container offers for a plain bean: the container invokes it as part of creating the singleton, before
     * any request can be served and before any batch step can be launched. In practice the bean definitions
     * contributed by this class are registered ahead of the auto-configured persistence beans, so the guard
     * normally reports first and an operator sees the precise cause rather than a downstream symptom.
     *
     * <p>That ordering is a diagnostic convenience and is deliberately <strong>not</strong> load-bearing: no
     * bean ordering is declared and none is relied upon. If the provider's own schema validation were to run
     * first it would raise its own failure, the application would still refuse to start, and the guard would
     * still report on the next attempt. Correctness therefore does not depend on which check fires first -
     * only the quality of the first message does.
     *
     * @return the guard, which verifies the contract when the container initialises it and has no other
     *         behaviour and no state of its own
     */
    @Bean
    public InitializingBean persistenceContractGuard() {
        return this::verifyPersistenceContract;
    }

    /**
     * Registers the runtime-credential guard so that an unusable database credential is reported as itself.
     *
     * <p><strong>The defect this closes.</strong> {@code application.yml} binds
     * {@code spring.datasource.username} and {@code .password} bare, with no fallback, so that an
     * unconfigured deployment cannot silently reconnect as the cluster superuser - that part works and is
     * deliberate. What did not work was the diagnosis. Boot's {@code @ConfigurationProperties} binder
     * resolves placeholders leniently, so with {@code CARDDEMO_DB_APP_USER} unset the literal text
     * {@code ${CARDDEMO_DB_APP_USER}} was passed through as a role name and the operator saw
     * {@code FATAL: password authentication failed for user "${CARDDEMO_DB_APP_USER}"} wrapped in a message
     * about the Spring Batch metadata table being missing or unreadable by the runtime role. Both sentences
     * are true and neither names the cause, and the headline one sends the reader to look at privileges.
     *
     * <p>A blank value is worse than the literal and was measured to be so. With
     * {@code CARDDEMO_DB_APP_USER} exported empty the driver substitutes the operating-system user name,
     * which in the delivered image is {@code carddemo} - the same name as the cluster BOOTSTRAP role. The
     * attempt was refused only because the password did not match, so an empty variable is one credential
     * away from the superuser connection the bare binding exists to prevent. That is why blank is rejected
     * here rather than left to the database.
     *
     * <p><strong>Why this is a {@link BeanPostProcessor} on the data source and not an ordinary bean.</strong>
     * An {@link InitializingBean} guard was written first and measured: it never ran. Component-scanned
     * configuration classes are registered in class-name order, so {@code BatchConfig}'s metadata
     * precondition is created before anything {@code JpaConfig} contributes, and the operator still saw the
     * batch-metadata message. Post-processing the {@code DataSource} instead is ordering-correct by
     * construction rather than by luck: Flyway, the persistence provider and the batch metadata check all
     * need that bean, so this runs before the first of them can open a connection, whichever it is.
     *
     * <p><strong>And it checks the credential that will actually be used, not the property.</strong> That
     * distinction is what removes the need for any exemption. {@code application-test.yml} runs against
     * Testcontainers through {@code @ServiceConnection}, which contributes a {@link JdbcConnectionDetails}
     * bean that supersedes {@code spring.datasource.url}, {@code .username} and {@code .password} outright -
     * so the base placeholders beneath them are irrelevant rather than wrong, and a guard reading the
     * properties would have failed every integration test. The pool's own configured user name is the value
     * the database will see in every profile, so reading it is both simpler and truthful. The two Flyway
     * keys are read from the environment instead, because Boot derives their data source internally rather
     * than publishing it as a bean, and an ABSENT Flyway credential is valid - the base profile leaves
     * {@code spring.flyway.user} unbound deliberately, since Boot derives a second {@code DataSource} the
     * moment it is non-null.
     *
     * <p>Declared {@code static} so that registering it cannot force this configuration class to be
     * instantiated before the post-processor is needed, which is the framework's documented requirement for
     * a {@code BeanPostProcessor} declared in a configuration class.
     *
     * @param environment the resolved environment, read for the two Flyway credential keys and nothing else
     * @return the guard, which validates the credentials as the pool is created
     */
    @Bean
    public static BeanPostProcessor runtimeCredentialGuard(final Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(final Object bean, final String beanName) {
                if (bean instanceof HikariDataSource pool) {
                    verifyRuntimeCredentials(pool.getUsername(), pool.getPassword(), environment::getProperty);
                }
                return bean;
            }
        };
    }

    /**
     * Asserts that every database credential this deployment will actually use is resolvable and non-blank.
     *
     * <p>Takes the two effective values and a lookup function rather than a {@link Environment} and a pool,
     * so that it is unit-testable with plain arguments exactly as {@link #verifyPersistenceContract()} is.
     * A lookup that throws {@link IllegalArgumentException} models the strict resolver's behaviour for an
     * unset variable; one returning text containing {@value #UNRESOLVED_PLACEHOLDER_PREFIX} models the
     * lenient binder's.
     *
     * <p>The conditions are distinguished because their remedies differ: a value may be absent, which for
     * the Flyway pair is legitimate; unresolvable, which means the variable is unset; still carrying a
     * placeholder, which means something resolved it leniently; or present and blank, which means the
     * variable is exported empty. <strong>No value is ever echoed</strong> - two of the four are passwords -
     * and the exception carries the property key, the variable name and the remedy only.
     *
     * @param effectiveUsername the user name the connection pool is configured with, which is what the
     *                          database will see; may be {@code null} when nothing bound it
     * @param effectivePassword the password the pool is configured with; may be {@code null}
     * @param boundValues       resolves a property key to its bound value, returning {@code null} when the
     *                          key is not declared and throwing {@link IllegalArgumentException} when a
     *                          placeholder in it cannot be resolved
     * @throws IllegalStateException on the first credential that is unusable
     */
    public static void verifyRuntimeCredentials(final String effectiveUsername,
            final String effectivePassword, final UnaryOperator<String> boundValues) {
        requireCredential(key -> effectiveUsername, KEY_DATASOURCE_USERNAME, VARIABLE_APP_USER, true);
        requireCredential(key -> effectivePassword, KEY_DATASOURCE_PASSWORD, VARIABLE_APP_PASSWORD, true);

        // Absent is valid for these two and only these two: the base profile leaves spring.flyway.user
        // unbound on purpose, because Boot derives a second DataSource as soon as it is non-null.
        requireCredential(boundValues, KEY_FLYWAY_USER, VARIABLE_MIGRATION_USER, false);
        requireCredential(boundValues, KEY_FLYWAY_PASSWORD, VARIABLE_MIGRATION_PASSWORD, false);

        LOG.info("Runtime credentials verified: the pool's configured principal and, where bound, the "
                + "migration principal both resolve to non-blank values; neither is echoed");
    }

    /**
     * Validates one credential property, naming the environment variable it is bound from.
     *
     * @param boundValues resolves the property key to its bound value
     * @param key         the property key, quoted verbatim in the message
     * @param variable    the environment variable the shipped profiles bind that key from
     * @param required    whether an absent value is itself a defect
     * @throws IllegalStateException if the value cannot be resolved, still carries a placeholder, is blank,
     *                               or is absent while required
     */
    private static void requireCredential(final UnaryOperator<String> boundValues, final String key,
            final String variable, final boolean required) {
        final String value;
        try {
            value = boundValues.apply(key);
        } catch (final IllegalArgumentException unresolved) {
            throw credentialRejected(key, variable,
                    "could not be resolved, because the environment variable it is bound from is not set",
                    unresolved);
        }

        if (value == null) {
            if (required) {
                // Both an unset and an exported-empty variable arrive here as null, because the pool
                // records no value for either, so the message names both rather than guessing which.
                throw credentialRejected(key, variable,
                        "resolved to no value at all, which is what both an unset variable and one exported "
                                + "empty produce",
                        null);
            }
            LOG.debug("Runtime credentials: {} is unbound, which this profile does so deliberately", key);
            return;
        }
        if (value.contains(UNRESOLVED_PLACEHOLDER_PREFIX)) {
            throw credentialRejected(key, variable,
                    "is bound to an unresolved property placeholder rather than to a credential, which is "
                            + "what a leniently-resolved binding leaves behind when the variable is unset",
                    null);
        }
        if (value.isBlank()) {
            throw credentialRejected(key, variable,
                    "is bound to a value that is empty or whitespace only, which PostgreSQL will refuse at "
                            + "authentication",
                    null);
        }
    }

    /**
     * Builds the one credential failure message, so every rejection path words the remedy identically.
     *
     * <p>Names the property key, the environment variable and the remedy, and stops there: no part of any
     * value appears, because two of the four are passwords and a length or a prefix is itself a lead. The
     * exception is unchecked and deliberately not one of the project's own types, for the same reason
     * {@link #verifyPersistenceContract()} gives: this is a configuration defect detected before any data
     * path exists.
     *
     * @param key      the property key
     * @param variable the environment variable that key is bound from
     * @param defect   the condition observed, phrased to complete "the property ... {defect}"
     * @param cause    the underlying resolution failure, or {@code null} when there was none to preserve
     * @return the exception to throw
     */
    private static IllegalStateException credentialRejected(final String key, final String variable,
            final String defect, final Throwable cause) {
        final String message = "Database credential not usable: property '" + key + "' " + defect
                + ". Set environment variable " + variable + " to the value this deployment's role uses "
                + "and restart. There is deliberately no default and no fallback: falling back to "
                + "POSTGRES_USER would reconnect the application as the cluster superuser, which is "
                + "indistinguishable from succeeding. The value itself is never logged.";
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }

    /**
     * Returns the trimmed value of a required property, rejecting an absent or blank one.
     *
     * @param key      the property key, used verbatim in the failure message so the message and the binding
     *                 cannot drift apart
     * @param rawValue the bound value, which is an empty string when the key is absent
     * @return the value with surrounding whitespace removed
     * @throws IllegalStateException if the value is {@code null} or contains only whitespace
     */
    private static String requireConfigured(final String key, final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is not configured. It is owned by "
                            + "src/main/resources/application.yml and must be present in every profile.");
        }
        return rawValue.trim();
    }

    /**
     * Returns the value of a required flag, accepting only an exact {@code true} or {@code false}.
     *
     * <p>The platform's own lenient parse is deliberately avoided: it maps every unrecognised value to
     * {@code false}, so a typo would silently disarm an invariant instead of reporting it. Case folding uses
     * {@link Locale#ROOT} rather than the default locale, so the result cannot vary with the host's regional
     * settings.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param rawValue the bound value, which is an empty string when the key is absent
     * @return {@code true} or {@code false} as written
     * @throws IllegalStateException if the value is absent, blank, or is anything other than {@code true} or
     *                               {@code false}
     */
    private static boolean requireFlag(final String key, final String rawValue) {
        final String normalised = requireConfigured(key, rawValue).toLowerCase(Locale.ROOT);
        if (TRUE_LITERAL.equals(normalised)) {
            return true;
        }
        if (FALSE_LITERAL.equals(normalised)) {
            return false;
        }
        throw new IllegalStateException(
                "Persistence contract violated: " + key + " is '" + rawValue.trim() + "', which is neither "
                        + TRUE_LITERAL + " nor " + FALSE_LITERAL + ". A flag that cannot be read cannot be "
                        + "enforced, so it is rejected rather than assumed.");
    }

    /**
     * Asserts that a textual property holds the one value the contract permits.
     *
     * <p>Comparison is case-insensitive, normalised through {@link Locale#ROOT}, because the settings
     * concerned are themselves interpreted case-insensitively downstream; rejecting a differently cased but
     * behaviourally identical value would be a false positive, and a guard that raises those gets weakened
     * rather than heeded.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param actual   the configured value, already known to be present and trimmed
     * @param required the only permitted value
     * @param reason   why the invariant exists, quoted into the failure message so the message explains
     *                 itself without reference to this file
     * @throws IllegalStateException if the configured value differs from the required one
     */
    private static void requireValue(final String key, final String actual, final String required,
            final String reason) {
        if (!required.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is '" + actual + "' but must be '" + required
                            + "', because " + reason + ". Restore the required value rather than relaxing "
                            + "this check.");
        }
    }

    /**
     * Asserts that a flag property holds the one state the contract permits.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param actual   the configured state
     * @param required the only permitted state
     * @param reason   why the invariant exists, quoted into the failure message so the message explains
     *                 itself without reference to this file
     * @throws IllegalStateException if the configured state differs from the required one
     */
    private static void requireState(final String key, final boolean actual, final boolean required,
            final String reason) {
        if (actual != required) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is " + actual + " but must be " + required
                            + ", because " + reason + ". Restore the required value rather than relaxing "
                            + "this check.");
        }
    }
}
