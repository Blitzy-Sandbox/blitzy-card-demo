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

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * Asserts the nine persistence-contract invariants, and is the one piece of executable behaviour this
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
