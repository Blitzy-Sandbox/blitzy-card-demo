/*
 * ******************************************************************
 * Program     : AbstractRepositoryIntegrationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - repository integration harness
 * Function    : Single shared Testcontainers harness for the eleven
 *               repository integration tests. Starts exactly one
 *               PostgreSQL 16 container for the whole class hierarchy,
 *               lets Flyway apply the three migrations that replace the
 *               VSAM physical layout, and isolates each test method by
 *               transactional rollback rather than by rebuilding the
 *               container. Holds the tier's only container declaration,
 *               its only Spring context declaration and its only
 *               connection-property source, so no subclass repeats any
 *               of the three. Contains no assertion, no domain rule and
 *               no test method of its own.
 * Source      : app/catlg/LISTCAT.txt (authoritative physical spec: 10
 *               base clusters, 3 alternate indexes, 3 paths, 7 GDG
 *               bases, TOTAL 209 entries, self-reported at L3938-L3946),
 *               app/cbl/CBACT04C.cbl:1-21 (banner convention),
 *               CONTRIBUTING.md:33-34 (repository hygiene),
 *               NOTICE, LICENSE (Apache-2.0 terms) @ 7756d89
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
package com.cardemo.integration.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared harness for the CardDemo repository integration tier, extended by all eleven
 * {@code *RepositoryTest} classes in this package.
 *
 * <h2>What it does</h2>
 *
 * <p>A Spring Data repository interface has no logic of its own to unit test, because Spring Data writes the
 * implementation. Its only meaningful coverage is integration coverage against a real database engine: a
 * mocked repository proves that the mock was configured and nothing else. This class supplies that database
 * once, for the whole hierarchy, and supplies nothing else. It declares no assertion, encodes no business
 * rule and defines no test method, so the behaviour under test lives entirely in the subclasses.
 *
 * <p>Three things are centralised here and must not be repeated by a subclass: the container declaration,
 * the {@link org.springframework.boot.test.context.SpringBootTest} context declaration, and the source of
 * the datasource connection properties. Eleven copies of any of the three would be eleven container
 * lifecycles and eleven chances to diverge.
 *
 * <p>The engine is <strong>PostgreSQL 16</strong>, and it replaces the VSAM substrate catalogued in
 * {@code app/catlg/LISTCAT.txt}. That catalogue is the authoritative physical specification and reports its
 * own totals at {@code L3938-L3946}: 3 alternate indexes, 10 base clusters, 7 generation data group bases,
 * 3 paths, 209 entries in all. The ten clusters become the tables these tests exercise, and the three
 * alternate indexes become the three non-unique B-tree indexes created by {@code V2}.
 *
 * <h3>What the started context guarantees, and therefore what a subclass may assert</h3>
 *
 * <p>Because {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, the schema
 * contract below is enforced at context startup rather than discovered at query time. A divergence in a
 * column name, SQL type, precision or nullability fails startup outright, which is the earliest and
 * loudest failure available. Every figure was verified by direct inspection of the migrations at
 * {@code 7756d89}, with SQL comments stripped so that the counts are of real statements:
 *
 * <ul>
 *   <li><strong>Exactly three migrations</strong> under {@code src/main/resources/db/migration}, applied in
 *       order: {@code V1__create_schema.sql}, {@code V2__create_indexes.sql}, {@code V3__seed_data.sql}.
 *       There is deliberately <strong>no fourth migration</strong>: the {@code BATCH_*} tables are created
 *       by the framework's own script, because the {@code test} profile sets
 *       {@code spring.batch.jdbc.initialize-schema: always}. Do not add one.</li>
 *   <li><strong>{@code V1} creates 11 tables</strong>, every column {@code NOT NULL} (88 of them), with
 *       exactly <strong>5</strong> check constraints, exactly <strong>10</strong> foreign keys named
 *       {@code fk01_card_account} through {@code fk10_discgrp_category}, and 11 primary keys. A
 *       {@code version BIGINT NOT NULL} column appears on exactly <strong>four</strong> tables:
 *       {@code account}, {@code card}, {@code customer} and {@code "transaction"}.</li>
 *   <li><strong>{@code V2} creates exactly three non-unique B-tree indexes</strong>, one per alternate
 *       index in the catalogue. The names emitted by the migration are {@code ix_card_acct_id},
 *       {@code ix_card_cross_reference_acct_id} and {@code ix_transaction_proc_ts}. The count of
 *       {@code CREATE UNIQUE INDEX} is <strong>0</strong> and the count of {@code CONCURRENTLY} is
 *       <strong>0</strong>; a legacy alternate key is non-unique, so no subclass may assert uniqueness on
 *       any of the three.</li>
 *   <li><strong>{@code V3} seeds exact row counts</strong>, and these are the counts a subclass may rely
 *       on: {@code account} 50, {@code card} 50, {@code card_cross_reference} 50, {@code customer} 50,
 *       {@code daily_transaction} 300, {@code disclosure_group} 51,
 *       {@code transaction_category_balance} 50, {@code transaction_category} 18,
 *       {@code transaction_type} 7, {@code user_security} 10, and {@code "transaction"}
 *       <strong>0, deliberately empty</strong>, because the posting job is what fills it.</li>
 *   <li>The transaction table is emitted by {@code V1} and {@code V2} in the double-quoted lowercase form
 *       {@code "transaction"}. Any native-SQL assertion must resolve to that same lowercase identifier.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone, which is the
 * fastest check that the file still satisfies the compiler settings, with {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This tier is bound to Failsafe, not Surefire, and the binding is by path.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java}
 * and the matching {@code *Tests.java} form, together with the end-to-end tree, and runs them at the
 * {@code integration-test} and {@code verify} phases, even though these classes keep the {@code Test}
 * suffix. {@code maven-surefire-plugin} 3.5.4 owns {@code **}{@code /unit/}{@code **} and
 * <strong>explicitly excludes</strong> the integration and end-to-end trees.
 *
 * <p>The consequence is worth stating plainly because it fails silently. A class moved to the parent
 * {@code integration} level, to {@code com.cardemo}, or directly into {@code src/test/java} matches
 * <em>neither</em> plugin's include set, so it is collected by neither and simply never runs: the build
 * stays green, both plugins report success, and there is no error and no output to notice. Do not rename
 * this file, relocate this package, or introduce an intermediate package.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> Testcontainers starts a real PostgreSQL
 * 16 container and a Ryuk reaper container, so the tier cannot run without a container runtime. Where a
 * host JDK is not provisioned, the identical build runs inside the pinned image with the repository
 * mounted, and produces the same result because every plugin and every non-managed dependency version is
 * pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration of its own. It performs no environment-variable read, no
 * system-property read and no system-property write, and it contains no host name, port, database name,
 * user name, password or JDBC URL. Those are not stylistic omissions: the connection is injected, and a
 * literal would defeat the injection.
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, activated by {@link org.springframework.test.context.ActiveProfiles}.
 *       {@code src/main/resources/application-test.yml} deliberately declares no datasource URL, host,
 *       port, user name or password, and this class mirrors that discipline exactly.</li>
 *   <li><strong>Connection injection by {@code @ServiceConnection}</strong>. Spring Boot derives the URL,
 *       user name and password from the running container, so no property string and no literal appears
 *       here. This is why no {@code @DynamicPropertySource} method is needed: there is no property left
 *       for one to supply.</li>
 *   <li><strong>Container image {@code postgres:16.10-alpine}</strong>, the same PostgreSQL 16 tag the
 *       compose topology pins at {@code docker-compose.yml:51}. It is stated as an exact tag rather than a
 *       floating one so the engine under test cannot drift between runs.</li>
 *   <li><strong>Schema handling</strong>: {@code spring.jpa.hibernate.ddl-auto: validate},
 *       {@code spring.jpa.open-in-view: false}, {@code spring.jpa.show-sql: false} with no Hibernate SQL
 *       or bind-parameter logging in any profile, and Hibernate's JDBC time zone set to {@code UTC}.</li>
 *   <li><strong>Flyway</strong>: {@code enabled: true}, {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false}, {@code baseline-on-migrate: false},
 *       {@code locations: classpath:db/migration}. Neither {@code ignore-migration-patterns} nor
 *       {@code continue-on-error} is set, and neither may be.</li>
 *   <li><strong>{@code spring.batch.job.enabled: false}</strong>, so starting this context does not launch
 *       a batch job as a side effect of a repository test.</li>
 *   <li><strong>Time is fixed and UTC.</strong> See {@link #fixedClock()}; the ambient clock is never read
 *       anywhere in this package.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test in the tier fails to start, with a container or Docker error.</em> There is no
 *       reachable Docker socket. This tier cannot be made to pass without one; state the blocker rather
 *       than asserting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version is resolved.</em> This
 *       is the <strong>Blocker</strong>-severity trap of the whole migration, and its remedy has two
 *       halves that are both required. Half one: Testcontainers is pinned to exactly {@code 2.0.3} by
 *       <em>overriding the version property the Spring Boot parent manages</em>, never by importing a
 *       second bill of materials, because two competing imports resolve in an ordering-dependent way that
 *       can silently select the parent-managed 1.x line. Half two: only the <em>prefixed</em> module
 *       coordinates exist on the 2.x line, namely {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}; the bare 1.x identifiers {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} do not exist there and fail resolution outright.
 *       Overriding without renaming resolves artefacts that are not published; renaming without
 *       overriding resolves the wrong version. Both halves are already in place in the root
 *       {@code pom.xml}, which is root-owned and must not be edited from here.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with {@code -Xlint:all}
 *       and {@code -Werror}, and that reaches test compilation, so a raw type, an unchecked cast, a
 *       deprecation or a switch fall-through is an error rather than a warning. That is also why the
 *       container below is imported from {@code org.testcontainers.postgresql}: the legacy
 *       {@code org.testcontainers.containers} equivalent is deprecated at 2.0.3, and a deprecation
 *       warning here is fatal. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails on a JDBC type code, not on a column name.</em> {@code validate}
 *       compares type codes, so a {@code Long} mapped over {@code NUMERIC(11)} can fail where
 *       {@code BIGINT} passes, and an {@code Integer} over {@code NUMERIC(4)} can fail where
 *       {@code INTEGER} or {@code SMALLINT} passes. <strong>The fix is upstream</strong>, in
 *       {@code V1__create_schema.sql} or in the entity mapping. Never widen a column to silence it and
 *       never patch the test. Severity <strong>Medium</strong>.</li>
 *   <li><em>Context startup fails with an unsatisfied dependency for a collaborator bean, naming a type
 *       this class never mentions.</em> {@link org.springframework.boot.test.context.SpringBootTest} is
 *       deliberately declared with no {@code classes} attribute, so the whole application context starts
 *       and {@code validate} therefore validates against the real entity set. The cost of that choice is
 *       that a service bean whose own collaborator is not yet declared in the main configuration fails this
 *       tier even though the failure has nothing to do with persistence. <strong>The fix is upstream</strong>,
 *       in the {@code com.cardemo.config} class that owns the missing bean. Do not declare the bean in this
 *       harness or in a subclass: a bean defined in the test tree diverges from the one production will
 *       use, so the tier would then be validating a context that never ships. Narrowing the annotation to
 *       silence it is equally wrong, because it would retire the schema validation that is the whole point
 *       of starting a context here.</li>
 *   <li><em>A fixture stream is null at run time and the failure surfaces far from its cause.</em> The
 *       daily transaction fixture is named {@code dailytran.txt}, with "daily" spelled in full. The
 *       mainframe data definition name and dataset are {@code DALYTRAN}, so {@code dalytran.txt} is the
 *       natural guess and is wrong; it compiles cleanly and fails only when opened. {@link #readFixture}
 *       therefore fails immediately and names the resource it could not find, rather than returning an
 *       empty list.</li>
 *   <li><em>A test passes alone and fails in a suite, or vice versa.</em> Data written by a sibling test
 *       leaked. Every test method here runs inside a transaction that is rolled back; a subclass that
 *       commits deliberately, or that starts its own thread to write, steps outside that guarantee.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are stated as <strong>"Not available"</strong> rather than glossed over, because inventing
 * either would manufacture a false oracle.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured legacy
 *       output exists anywhere in the repository: a search across expected, baseline, golden, {@code .out}
 *       and system-output name patterns, and across the reject, report, statement and HTML dataset names,
 *       returns only dataset <em>definition</em> members such as {@code app/jcl/DALYREJS.jcl},
 *       {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc}, and a sweep for artefacts of the
 *       relevant record lengths returns nothing at all. What would be needed to close it is a captured
 *       430-byte reject dataset from a real posting run at a known input state, together with the
 *       resulting transaction, account and category-balance images. Until that exists: create no baseline
 *       file, fabricate no expected bytes, and do not generate a baseline by running this implementation
 *       and asserting against its own output, which is circular. Hand-simulating the posting program is
 *       equally inadmissible, and demonstrably so: two defensible models of it over these same fixtures
 *       disagree, a stateless single pass yielding 13 rejects against a stateful model with a fresh
 *       per-transaction account read yielding 38 rejects and 262 posted. A figure that moves with the
 *       model is not an oracle.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> The
 *       census across the 28 programs of {@code app/cbl} finds the literal file status {@code '35'}
 *       <strong>0</strong> times and {@code DFHRESP(NOTOPEN)} <strong>0</strong> times; the response
 *       codes the corpus actually tests are {@code NORMAL}, {@code NOTFND}, {@code ENDFILE},
 *       {@code DUPREC} and {@code DUPKEY}. The condition is therefore specification-derived only, so no
 *       test for it may be invented here and no parity claim is made for it.</p></li>
 * </ol>
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>{@link #readFixture} and {@link #fixedClock()} are pure with respect to this class: neither mutates
 * shared state, neither logs, and {@code fixedClock()} performs no I/O. {@link #flushAndClear} is
 * deliberately <em>not</em> pure, because forcing the persistence context to the database is its entire
 * purpose; its effects are confined to the calling test's transaction and are discarded when that
 * transaction rolls back. The single {@code static} member is the container, which is immutable after
 * start and is discussed on its own declaration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
public abstract class AbstractRepositoryIntegrationTest {

    /**
     * The one PostgreSQL 16 container for the entire tier, and the only {@code static} member this class
     * is permitted to hold.
     *
     * <p><strong>Why it is static, and why that is not a violation of "avoid global mutable state".</strong>
     * The Testcontainers JUnit 5 lifecycle distinguishes a static {@code @Container} field, started once
     * before the test class and shared, from an instance field, started and stopped around every single
     * test method. Restarting a database engine and reapplying three migrations once per test method would
     * cost minutes across the tier and would buy nothing, because the isolation that matters is isolation
     * of <em>data</em>, not of the engine. This field is therefore the single deliberate, documented
     * exception to the no-global-mutable-state rule, and it is a narrow one: it is {@code final}, it is
     * fully constructed and started before any test observes it, it is never reassigned, and it exposes no
     * test-visible mutable state that one test could use to perturb another. Data isolation is provided
     * separately and completely by transactional rollback; see {@link #flushAndClear()}. No other
     * {@code static} field of any kind may be added to this class or to any subclass.
     *
     * <p><strong>Why the declared type is not parameterised.</strong> On the pinned 2.0.3 line the
     * canonical class {@code org.testcontainers.postgresql.PostgreSQLContainer} declares no type
     * parameter, so writing {@code PostgreSQLContainer} with a wildcard argument would not compile. The
     * older self-parameterised {@code org.testcontainers.containers.PostgreSQLContainer} still ships, but
     * it is deprecated at this version and the zero-warning compile turns that deprecation into a build
     * failure, so it is not an alternative. There is no raw type here either, because a class with no type
     * parameters cannot be used raw.
     *
     * <p>{@link org.springframework.boot.testcontainers.service.connection.ServiceConnection} is what
     * connects it to the context: Spring Boot reads the URL, user name and password off this object and
     * registers them as connection details, which is why no JDBC URL, host, port, database name, user name
     * or password literal appears anywhere in this package. Flyway then applies {@code V1}, {@code V2} and
     * {@code V3} to the fresh container, so the schema under test is the schema that ships.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.10-alpine");

    /**
     * Fixed instant every test in this tier treats as "now", pinned to UTC.
     *
     * <p>The instant {@code 2022-06-10T19:27:53Z} is not arbitrary and is not a placeholder: it is the one
     * distinct originating timestamp carried by the frozen daily transaction fixture, which renders it in
     * all 300 rows as {@code 2022-06-10 19:27:53.000000} across columns 279 to 304. Choosing it means a
     * value derived from this clock can be compared against fixture bytes directly. It is also the instant
     * the unit tier already standardised on, so the two tiers agree rather than drifting.
     */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2022-06-10T19:27:53Z"), ZoneOffset.UTC);

    /**
     * Persistence context used only to implement {@link #flushAndClear()}.
     *
     * <p>It is injected here, once, rather than wired independently by each of the eleven subclasses, so
     * that there is a single place where the tier's flush semantics are defined. Spring's test-context
     * dependency injection populates it on the test instance before each method, and the injected reference
     * is a shared, transaction-aware proxy that resolves to the persistence context of whichever
     * transaction the current test method is running in.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Sole constructor, invoked implicitly by every subclass.
     *
     * <p>It is declared explicitly, and declared {@code protected} rather than left implicit, for two
     * reasons. The implicit default constructor of a public class is itself public, which would advertise
     * this type as constructible by anything; narrowing it to {@code protected} states that only a subclass
     * in this tier may construct it, which is the actual contract. And because this class exists purely to
     * be extended, its inheritance entry point is part of its documented surface rather than an
     * implementation detail.
     *
     * <p>There is deliberately nothing to do here. The container is {@code static} and is started by the
     * Testcontainers extension before any instance exists; the clock is assigned by its field initialiser;
     * and the persistence context is injected by the Spring test framework after construction. A subclass
     * therefore needs no constructor of its own, and must not attempt to obtain a repository or query the
     * database from one, because injection has not yet happened at that point.
     */
    protected AbstractRepositoryIntegrationTest() {
        // Intentionally empty: see the Javadoc above. All state is supplied by field initialisation,
        // by the Testcontainers extension, or by Spring test-context injection after construction.
    }

    /**
     * Returns the tier's fixed clock, from which every subclass must obtain the current instant.
     *
     * <p>Nothing in this package may read the ambient clock: not the current-instant, current-date or
     * current-date-time factory methods of {@code java.time}, not the millisecond counter on
     * {@code System}, and not the no-argument {@code java.util.Date} constructor. A test that reads the
     * ambient clock is not merely impure, it is intermittently wrong: such failures cluster on second, day,
     * month and year boundaries and are close to impossible to reproduce on demand. Taking time from here
     * removes the variable outright.
     *
     * @return an immutable, thread-safe {@link java.time.Clock} fixed at {@code 2022-06-10T19:27:53Z} in
     *         {@link java.time.ZoneOffset#UTC}; never {@code null}, and never a moving clock
     */
    protected final Clock fixedClock() {
        return fixedClock;
    }

    /**
     * Forces every pending change in the persistence context to the database and then detaches all managed
     * entities.
     *
     * <p>Use it when a test needs the database, rather than Hibernate's first-level cache, to be the thing
     * that answers the next query. Two cases in this tier genuinely require it. The first is asserting that
     * a constraint fires: one of the 5 check constraints, one of the 10 foreign keys or a {@code NOT NULL}
     * on one of the 88 columns is enforced by PostgreSQL, so nothing is enforced until the {@code INSERT}
     * or {@code UPDATE} is actually sent. The second is asserting an optimistic-lock bump: the
     * {@code version} column exists on {@code account}, {@code card}, {@code customer} and
     * {@code "transaction"}, and the increment is applied by the flush. Clearing afterwards is what makes
     * the following {@code findById} a real round trip instead of an identity-map hit.
     *
     * <p><strong>Side effects.</strong> The flush emits SQL inside the calling test's transaction, and the
     * clear detaches every entity the test was holding, so any reference obtained before this call is stale
     * and must be re-read. Nothing is committed: the surrounding transaction is still rolled back when the
     * test method ends, so the rows never become visible to another test.
     *
     * @throws jakarta.persistence.PersistenceException if the flush fails, which is the normal and intended
     *         outcome when a test is deliberately provoking a constraint violation; the underlying cause is
     *         preserved on the thrown exception by the persistence provider
     */
    protected final void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Reads one of the nine frozen ASCII fixtures from the classpath root and returns its records exactly as
     * stored, without altering a single byte.
     *
     * <p>The fixtures are byte-for-byte copies of the frozen {@code app/data/ASCII} datasets and are flat
     * direct children of {@code src/test/resources}, with no {@code fixtures/} or {@code data/} subfolder,
     * so the name passed here is resolved from the classpath root and must be a bare file name such as
     * {@code "dailytran.txt"}. All nine satisfy one invariant, verified at {@code 7756d89}: the file size
     * equals the row count multiplied by the record width plus one for the terminating line feed, there are
     * no carriage-return bytes anywhere, every byte is 7-bit ASCII, the final byte is a line feed, and each
     * file has exactly one distinct record width. The widths are 60 for {@code trantype.txt} (7 rows) and
     * {@code trancatg.txt} (18 rows), 50 for {@code discgrp.txt} (51 rows) and {@code tcatbal.txt}
     * (50 rows), 500 for {@code custdata.txt}, 300 for {@code acctdata.txt}, 150 for
     * {@code carddata.txt}, 36 for {@code cardxref.txt}, and 350 for {@code dailytran.txt} (300 rows).
     *
     * <p><strong>These records are fixed-width, so this method must not tidy them and does not.</strong>
     * Every field is located by absolute column position, which means a trailing space is data. Trimming
     * would silently truncate {@code acctdata} from 300 columns to 112, {@code custdata} from 500 to 332,
     * {@code carddata} from 150 to 91 and {@code dailytran} from 350 to 304, and every position-based read
     * after the first short row would then be wrong. Accordingly: the bytes are decoded with an explicit
     * US-ASCII charset rather than the platform default, the content is split on the line feed alone rather
     * than by a general line-separator rule, and no line is trimmed, normalised, re-encoded or edited. Only
     * the empty string that the terminating line feed produces after the last record is discarded, so the
     * returned size is the row count rather than the row count plus one.
     *
     * @param resourceName bare classpath-root name of the fixture, for example {@code "dailytran.txt"};
     *                     must be non-{@code null} and not blank
     * @return an immutable list of the file's records in file order, each exactly as stored including any
     *         trailing spaces, and excluding the empty tail produced by the terminating line feed
     * @throws NullPointerException     if {@code resourceName} is {@code null}
     * @throws IllegalArgumentException if {@code resourceName} is blank
     * @throws IllegalStateException    if no such classpath resource exists, or if reading it fails. The
     *                                  message names the resource in both cases, and where an
     *                                  {@link java.io.IOException} caused the failure it is preserved as
     *                                  the cause rather than swallowed. Failing loudly here is deliberate:
     *                                  the canonical mistake is spelling the daily transaction fixture
     *                                  {@code dalytran.txt} after the mainframe dataset name instead of
     *                                  {@code dailytran.txt}, and returning an empty list for that would
     *                                  turn a typo into a confusing failure far from its cause
     */
    protected final List<String> readFixture(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName must not be null");
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not found on the classpath root: '" + resourceName
                        + "'. The nine fixtures are flat direct children of src/test/resources; note that "
                        + "the daily transaction fixture is named 'dailytran.txt', not 'dalytran.txt'.");
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
            String[] records = content.split("\n", -1);
            int recordCount = records.length;
            if (recordCount > 0 && records[recordCount - 1].isEmpty()) {
                recordCount--;
            }
            return List.of(Arrays.copyOf(records, recordCount));
        } catch (IOException cause) {
            throw new IllegalStateException("Failed to read fixture '" + resourceName
                    + "' from the classpath root.", cause);
        }
    }
}
