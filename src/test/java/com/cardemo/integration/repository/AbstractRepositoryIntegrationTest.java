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

import java.net.InetAddress;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cardemo.unit.model.FixtureLoader;

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
 *       index in the catalogue. The names emitted by the migration are {@code idx_card_acct_id},
 *       {@code idx_card_cross_reference_acct_id} and {@code idx_transaction_proc_ts}. The count of
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
 *   </ul>
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
 * system-property read and no system-property write, and it contains no database host, port, database name,
 * user name, password or JDBC URL. Those are not stylistic omissions: the connection is injected, and a
 * literal would defeat the injection.
 *
 * <p>The one class of literal it does carry is the loopback AWS endpoint registered by
 * {@link #registerApplicationProperties(DynamicPropertyRegistry)}, and that is the opposite of a leak. The base
 * profile makes an approved emulator endpoint mandatory in every profile so that no configuration can resolve a
 * real service edge, and a tier that refreshes the full context must therefore name one. A loopback literal is
 * the narrowest thing it can name.
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, activated by {@link org.springframework.test.context.ActiveProfiles}.
 *       {@code src/main/resources/application-test.yml} deliberately declares no datasource URL, host,
 *       port, user name or password, and this class mirrors that discipline exactly.</li>
 *   <li><strong>Connection injection by {@code @ServiceConnection}</strong>. Spring Boot derives the URL,
 *       user name and password from the running container, so no <em>datasource</em> property string and no
 *       literal appears here.
 *       <p><strong>An earlier revision of this paragraph claimed that no {@code @DynamicPropertySource}
 *       method was needed because "there is no property left for one to supply". That claim was wrong and
 *       is withdrawn.</strong> It reasoned only about the datasource, but {@code @SpringBootTest} loads the
 *       <em>whole</em> application context, and that context requires several properties the datasource
 *       injection knows nothing about. Measured, not assumed: the first concrete subclass failed context
 *       startup on an unresolvable token-signing-key placeholder, and the emulator binding guard in
 *       {@code AwsConfig} then requires a resolved, allow-listed endpoint and static credentials for each of
 *       S3, SQS and SNS. Those, together with the three bucket names, the report queue and the notification
 *       topic - each of which {@code application.yml} maps to an environment variable carrying no default -
 *       are supplied by {@link #registerApplicationProperties(DynamicPropertyRegistry)} below, which
 *       registers no datasource property at all.</li>
 *   <li><strong>Container image pinned by digest</strong> to
 *       {@code postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20}, which is
 *       PostgreSQL 16.14 on Debian 13 with glibc 2.41. A digest, not a tag, and the Debian image, not
 *       {@code alpine}; the field documentation gives both reasons and names the sibling tier that uses the
 *       identical digest.</li>
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
 *   </ul>
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
 *       tier even though the failure has nothing to do with persistence. <strong>For anything other than
 *       the clock, the fix is upstream</strong>, in the {@code com.cardemo.config} class that owns the
 *       missing bean: a bean defined in the test tree diverges from the one production will use, so the
 *       tier would then be validating a context that never ships. Narrowing the annotation to silence it is
 *       equally wrong, because it would retire the schema validation that is the whole point of starting a
 *       context here.
 *       <p>{@link java.time.Clock} is the one documented exception, and it is declared by
 *       {@link FixedClockTestConfiguration} for two reasons stated there in full: the main configuration
 *       publishes no {@code Clock} bean at all - a recorded finding owned by another boundary - so without
 *       it no subclass of this harness can start a context and the schema validation never runs; and a test
 *       tier must in any case pin its own clock, because an assertion that reads the ambient clock fails
 *       intermittently on second, day, month and year boundaries. The published instant is the same one
 *       {@link #fixedClock()} returns and the same one the sibling batch harness publishes.</li>
 *   <li><em>A fixture stream is null at run time and the failure surfaces far from its cause.</em> The
 *       daily transaction fixture is named {@code dailytran.txt}, with "daily" spelled in full. The
 *       mainframe data definition name and dataset are {@code DALYTRAN}, so {@code dalytran.txt} is the
 *       natural guess and is wrong; it compiles cleanly and fails only when opened. {@link #readFixture}
 *       therefore fails immediately and names the resource it could not find, rather than returning an
 *       empty list.</li>
 *   <li><em>A test passes alone and fails in a suite, or vice versa.</em> Data written by a sibling test
 *       leaked. Every test method here runs inside a transaction that is rolled back; a subclass that
 *       commits deliberately, or that starts its own thread to write, steps outside that guarantee.</li>
 *   <li><em>One class in the tier passes when run alone but cannot connect when run after a sibling.</em>
 *       A per-class container lifecycle has been reintroduced - almost always by adding
 *       {@code org.testcontainers.junit.jupiter.Container} to {@link #POSTGRES}, which makes the extension
 *       stop the container after each concrete subclass and restart it on a new mapped port while Spring
 *       keeps the cached datasource pointed at the old one. The container is started once per JVM by the
 *       static initialiser on {@link #POSTGRES} and must never be annotated or stopped; the field
 *       documentation sets out why, and the two sibling subclasses in this package exist to make a
 *       regression here fail immediately rather than intermittently.</li>
 *   </ul>
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
 *   </ol>
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
@Import(AbstractRepositoryIntegrationTest.FixedClockTestConfiguration.class)
public abstract class AbstractRepositoryIntegrationTest {

    /**
     * The one PostgreSQL 16 container for the entire tier, started once per JVM, and the only {@code static}
     * member this class is permitted to hold.
     *
     * <p><strong>Why it is static, and why that is not a violation of "avoid global mutable state".</strong>
     * Restarting a database engine and reapplying three migrations once per test method would cost minutes
     * across the tier and would buy nothing, because the isolation that matters is isolation of
     * <em>data</em>, not of the engine. This field is therefore the single deliberate, documented exception
     * to the no-global-mutable-state rule, and it is a narrow one: it is {@code final}, it is fully
     * constructed and started before any test observes it, it is never reassigned, and it exposes no
     * test-visible mutable state that one test could use to perturb another. Data isolation is provided
     * separately and completely by transactional rollback; see {@link #flushAndClear()}. No other
     * {@code static} field of any kind may be added to this class or to any subclass.
     *
     * <p><strong>Why it carries no {@code org.testcontainers.junit.jupiter.Container} annotation, and why
     * that omission is load-bearing rather than an oversight.</strong> The JUnit 5 Testcontainers extension
     * treats a shared {@code static @Container} as a <em>class-level</em> resource: it starts it in
     * {@code beforeAll} and stops it in {@code afterAll} <em>of every concrete subclass</em>. With eleven
     * subclasses that is eleven start-stop cycles of one field. Spring, meanwhile, caches the application
     * context across sibling classes whose context key is identical - which every subclass of this harness
     * has, because the annotations that form the key are all declared here. The two lifecycles then
     * disagree: the first subclass stops the container in its {@code afterAll}, the extension restarts it
     * for the second subclass on a <em>new mapped port</em>, and the second subclass runs against the
     * cached datasource whose Hikari pool still addresses the port that no longer exists. The symptom is a
     * connection failure in whichever class happens to run second, which looks like a defect in that class
     * and is not one, and which does not reproduce when that class is run alone.
     *
     * <p>The remedy is this pair: start the container from a static initialiser, which runs exactly once
     * per class loader, and never stop it. Nothing stops it because nothing needs to - the Testcontainers
     * Ryuk reaper container removes it when the JVM exits, which is the sanctioned singleton-container
     * lifecycle. The alternatives were considered and rejected on the merits.
     * {@code org.springframework.test.annotation.DirtiesContext} would rebuild the whole context for every
     * class and pay a far larger cost to fix a smaller problem; forcing a distinct context key per subclass
     * cannot be enforced from a base class, so it would fail silently the first time a subclass omitted the
     * marker. {@link #POSTGRES} is therefore started below, and
     * {@code com.cardemo.integration.repository.RepositoryHarnessLifecycleTest} and
     * {@code com.cardemo.integration.repository.RepositoryHarnessSeedStateTest} are two sibling subclasses
     * that exist to prove the continuity holds when both run in one invocation.
     *
     * <p>The class-level {@code org.testcontainers.junit.jupiter.Testcontainers} annotation is kept
     * deliberately even though no field here uses it: it registers the extension so that a subclass may
     * still declare its own {@code @Container} field where a genuinely per-class lifecycle is what that
     * subclass wants. This is the same arrangement the sibling batch harness uses, so the two tiers agree
     * rather than each solving the problem its own way.
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
     *
     * <p><strong>Why the image is pinned by digest, and why it is not the {@code alpine} variant.</strong>
     * This field previously named {@code postgres:16.10-alpine}, on the reasoning that it matched the tag the
     * compose topology uses for the developer database. Both halves of that were wrong for a tier whose
     * contract is reproducing behaviour exactly.
     *
     * <p>The variant matters because Alpine is built on musl and Debian on glibc, and <em>text collation
     * ordering differs between the two</em>. This tier exercises repositories whose derived and explicit
     * queries carry {@code ORDER BY} over character columns - card numbers, identifiers, names - and the
     * legacy browse order those queries reproduce is a parity contract, not a preference. Asserting sort
     * order against musl collation and shipping against glibc would let a genuine ordering divergence pass
     * here and appear in production, which is precisely the class of defect this tier exists to catch. The
     * sibling batch harness had already reached that conclusion and refused Alpine for the same reason;
     * disagreeing with it meant the two tiers were testing on different engines.
     *
     * <p>The digest matters because a tag is a mutable pointer. Even an apparently exact tag is only a name
     * the registry may republish, and the sibling tier's {@code postgres:16} was measured resolving to 16.14
     * while this one named 16.10 - so "the same PostgreSQL 16" was two different engines in one build. A
     * digest is content-addressed and cannot move, so the engine under test is now the same one on every run,
     * on every machine and after any registry change.
     *
     * <p><strong>Why the digest moved from 16.10 to 16.14.</strong> PostgreSQL 16.10 carried
     * CVE-2026-6479, CVE-2026-6473 and CVE-2026-2006. The compose topology's database was upgraded to close
     * them, and pinning a knowingly-vulnerable engine here as well would have left the same defect in the
     * tier that runs on every build and in continuous integration. The substitution preserves every invariant
     * this comment states: the same Debian 13 (trixie) base rather than Alpine, the SAME GLIBC 2.41 - so text
     * collation ordering, and therefore the legacy browse order this tier asserts, is unchanged - and the
     * identical digest in all three harnesses. Both the version and the libc were confirmed by inspecting the
     * pulled image, not inferred from the tag.
     *
     * <p>The digest below resolves to PostgreSQL 16.14 on Debian GNU/Linux 13 (trixie) with GLIBC 2.41,
     * verified by inspecting the pulled image rather than inferred from its name.
     * {@code asCompatibleSubstituteFor} is required because a digest reference carries no tag, so the
     * library cannot otherwise recognise it as the PostgreSQL image its wait strategy and JDBC URL builder
     * expect.
     *
     * <p><strong>Why the patch level is 16.14 and not the 16.10 this field first pinned. Finding, severity
     * High - raised against the original pin and remediated here.</strong> A digest delivers immutability,
     * not currency: it froze the engine under test, which is what determinism needs, but it froze the
     * <em>patch level</em> with it, so the pin aged silently and no part of the build would ever say so.
     * 16.14 closes eleven advisories that 16.10 is exposed to, the most severe being {@code CVE-2026-6473}
     * at CVSS v3.1 <strong>8.8</strong> - above the {@code failBuildOnCVSS=7} threshold the dependency scan
     * enforces on the Maven graph, which never sees container images. The reference therefore stays a digest
     * and its patch level is advanced deliberately, so currency is a recorded decision rather than a
     * registry accident. The reasoning is set out in full on the corresponding field of
     * {@code com.cardemo.integration.batch.AbstractBatchIntegrationTest}; it is summarised rather than
     * repeated here because the two pins must stay equal and a single record avoids the two explanations
     * drifting apart.
     *
     * <p><strong>The identical digest is named by
     * {@code com.cardemo.integration.batch.AbstractBatchIntegrationTest}, and the two must stay equal.</strong>
     * It is written out in both places rather than shared through a constant because each harness documents,
     * as a deliberate invariant, that no further {@code static} field may be added to it - so a shared
     * constant could not live in either without weakening a rule that exists to keep global mutable state out
     * of the tiers - and because neither tier may own the other's substrate. The duplication is safe in a way
     * a duplicated <em>name</em> would not be: a digest is self-verifying, so if the two ever diverge they
     * name two visibly different immutable images rather than silently resolving to different content under
     * one label.
     *
     * <p><strong>{@code --locale=C} is byte ordering, and this tier depended on it without asking for it.</strong>
     * The fixed-width keys inherited from the copybooks are compared as characters everywhere in this tree -
     * {@code TRAN-ID}, {@code TRAN-CARD-NUM} and the ten-character prefix of {@code TRAN-PROC-TS} among them -
     * and {@code com.cardemo.repository.TransactionRepository} pushes both an {@code ORDER BY} and a range
     * predicate over those columns into SQL. Under a locale-dependent collation the database reorders
     * punctuation and those comparisons stop agreeing with {@code String.compareTo}, so a container without
     * this argument tests different semantics from the ones the application ships with.
     * {@code docker-compose.yml} initialises with {@code --encoding=UTF8 --locale=C} and states that reason in
     * place; the three end-to-end suites and {@code com.cardemo.integration.aws.AbstractAwsIntegrationTest}
     * already carried it, and this tier and the repository tier did not - which made them the two places a
     * collation-sensitive regression could pass. Added under finding <strong>F-012</strong>, whose fix moves the
     * card-number ordering and the processing-date range into the query this tier exercises.
     *
     * <p>The value is written inline rather than held in a constant on purpose: this package documents a hard
     * budget of exactly two static fields, both of them containers, and a third would widen a rule that exists
     * to keep global state out of the tier.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName
                    .parse("postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20")
                    .asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C");

    /*
     * The JVM-singleton start. It runs once, when this class is initialised, which is before any subclass
     * instance exists and before Spring resolves the connection details off the container, and it is never
     * paired with a stop. See the field documentation above for why a class-level @Container lifecycle is
     * wrong here and what the failure looks like when it is used.
     */
    static {
        POSTGRES.start();
    }

    /**
     * Supplies every property this tier cannot inherit, so that starting a context does not depend on how the
     * machine running the build happens to be configured.
     *
     * <p><strong>Why this exists at all.</strong> {@code @ServiceConnection} covers the datasource and
     * nothing else, but {@link org.springframework.boot.test.context.SpringBootTest} is declared with no
     * {@code classes} attribute - so that Hibernate's {@code validate} checks the real entity set - and
     * therefore refreshes the <em>whole</em> application context. Guards that fail fast by design then demand
     * configuration the datasource injection knows nothing about, and each requirement below was established
     * by measurement rather than by anticipation: the first concrete subclass aborted refresh on an
     * unresolvable token-signing-key placeholder, and the cloud configuration then refused to build a client
     * without a resolved, allow-listed endpoint and static credentials for each of S3, SQS and SNS.
     *
     * <ul>
     *   <li><strong>The token signing key.</strong> {@code application.yml} binds
     *       {@code carddemo.security.jwt.signing-key} to an environment variable with <em>no default</em>,
     *       deliberately, so that a deployment cannot boot with a key an attacker already knows. That posture
     *       is correct and is not weakened here: {@code application-test.yml} states plainly that the abort
     *       is intended behaviour proving the fail-fast contract, and directs a test to supply the value from
     *       its own registry rather than from a committed default, because a test-only default in a profile
     *       would reintroduce exactly the High-severity hardcoded-key defect Rule 1 Clause D names. Until
     *       this registration existed no subclass could refresh a context, which is why the tier reported
     *       zero tests while appearing complete. Reading it from the environment instead is not an option:
     *       the continuous integration workflow exports only {@code NVD_API_KEY}, so a build depending on an
     *       exported key would pass locally and fail there.</li>
     *   <li><strong>The three cloud endpoints and the static credentials.</strong> The cloud configuration
     *       <em>rejects an absent endpoint override</em> rather than treating it as a neutral default,
     *       because an absent override is precisely what makes the SDK fall back to standard endpoint
     *       resolution and reach live AWS, which the Agent Action Plan forbids outright at sections 0.3.2 and
     *       0.8.4. Registering the credentials as well is what stops an ambient credential chain on the build
     *       host from being picked up by accident.</li>
     *   <li><strong>The three bucket names, the report queue and the notification topic.</strong> Each is
     *       mapped by {@code application.yml} to an environment variable with no default, so each aborts a
     *       refresh on a machine that has not exported it. Registering them here makes the tier independent
     *       of the ambient environment, which is the environment-specific assumption Clause C rules out.</li>
     * </ul>
     *
     * <p><strong>Why no emulator container is started here.</strong> This tier asserts persistence behaviour
     * against PostgreSQL and makes no S3, SQS or SNS call. The guard validates the <em>binding</em> at
     * startup - it parses the endpoint and checks its scheme, host and port against the allow-list - and
     * opens no socket, so an allow-listed loopback address satisfies it without a second container. Starting
     * the emulator for eleven repository classes that never call it would add minutes to every build and
     * prove nothing; the tier that does exercise those services starts it itself and overrides all three
     * endpoints with its own container's mapped address, so nothing registered here constrains it.
     *
     * <p><strong>Why none of these values is a leak, and why the port is stated.</strong> The endpoint host
     * comes from {@link java.net.InetAddress#getLoopbackAddress()} rather than from a typed-in host name, so
     * the class still names no routable address. An earlier revision supplied no port at all and reasoned
     * that the guard constrained only the scheme and the host; that is no longer true and the reasoning is
     * withdrawn. The guard now refuses an endpoint that relies on a scheme default, because an endpoint with
     * no port would not reach the emulator even when its host is correct, so the emulator's published port is
     * stated explicitly. The credentials are the emulator's own well-known placeholders. The bucket, queue
     * and topic names are the same logical names {@code localstack-init/init-aws.sh} provisions, and none is
     * secret. The signing key is synthetic, is past the 32-byte minimum the HS256 signer enforces, and says
     * what it is in its own text so that no deployment could mistake it for real key material; it never
     * leaves the test JVM, because this tier issues no token and asserts nothing about one.
     *
     * @param registry the registry Spring's test context supplies for late-bound properties; never
     *                 {@code null}
     */
    @DynamicPropertySource
    static void registerApplicationProperties(final DynamicPropertyRegistry registry) {

        // A deliberately unroutable but allow-listed endpoint: parsed and accepted by the cloud binding
        // guard, never connected to. The port is the emulator's published 4566 and is stated explicitly
        // because the guard refuses an endpoint that relies on a scheme default. It is a local constant
        // rather than a static field, because this class documents that no further static field may be
        // added to it.
        final String emulatorEndpoint =
                "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":4566";

        registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
        registry.add("spring.cloud.aws.credentials.access-key", () -> "test");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "test");
        registry.add("spring.cloud.aws.s3.endpoint", () -> emulatorEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", () -> emulatorEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", () -> emulatorEndpoint);

        registry.add("carddemo.aws.s3.batch-input-bucket", () -> "carddemo-batch-input");
        registry.add("carddemo.aws.s3.batch-output-bucket", () -> "carddemo-batch-output");
        registry.add("carddemo.aws.s3.statements-bucket", () -> "carddemo-statements");
        registry.add("carddemo.aws.sqs.report-queue", () -> "carddemo-report-jobs.fifo");
        registry.add("carddemo.aws.sns.notification-topic", () -> "carddemo-notifications");

        // Generated fresh for this context from a cryptographically secure source, held only in this local,
        // and never written to a file, a log line or an assertion message. Registered rather than committed to
        // a profile so that the production fail-fast on an absent key stays intact and stays tested, and
        // generated rather than declared so that no key material is committed at all. It is resolved once and
        // captured, because the registry may invoke the supplier more than once and a key that changed
        // between resolutions would break every token round-trip.
        final String ephemeralSigningKey = generateEphemeralSigningKey();
        registry.add("carddemo.security.jwt.signing-key", () -> ephemeralSigningKey);
    }

    /**
     * Generates a single-use token signing key for this context.
     *
     * <p><strong>Why generated rather than declared.</strong> Rule 1 Clause D forbids secrets in code, in
     * configuration and <em>in tests</em>, with no carve-out for material that happens to be synthetic. A
     * literal key in a test file is still committed key material: indexable, copyable into a deployment, and
     * an example of the very pattern the clause exists to stop. Generating it removes the class of problem
     * instead of declaring one instance of it safe. The value lives only in memory for the lifetime of the
     * context, so there is nothing to leak, rotate or review.
     *
     * <p><strong>Strength.</strong> Thirty-two bytes of entropy is what {@code MacAlgorithm.HS256} requires
     * and what {@code src/main/java/com/cardemo/security/JwtTokenProvider} enforces on the bound value; the
     * encoding widens that to forty-three characters, so the length guard passes with room to spare. URL-safe
     * unpadded encoding is used so the value survives property binding without escaping.
     *
     * <p><strong>Error modes.</strong> None this method can produce. {@link SecureRandom} is seeded by the
     * platform and the encoder cannot fail on a fixed-length input.
     *
     * @return a freshly generated key, never {@code null}, never logged and never persisted
     */
    private static String generateEphemeralSigningKey() {
        // 32 bytes == the HS256 minimum the token provider enforces on whatever this harness registers.
        final byte[] keyMaterial = new byte[32];
        new SecureRandom().nextBytes(keyMaterial);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial);
    }

    /**
     * The fixed clock the whole application context is running on, injected back rather than rebuilt.
     *
     * <p>It is the bean {@link FixedClockTestConfiguration} publishes, not a second instance constructed
     * here, and that distinction is what makes an assertion against it meaningful: a subclass comparing a
     * persisted or rendered timestamp against this clock is comparing against the very object the production
     * beans in the context consumed, so the two cannot drift apart. Before the context published a clock at
     * all this field was a plain instance field, which meant the tier <em>documented</em> a fixed time source
     * while every bean in the context read the ambient one.
     *
     * <p>The instant {@code 2022-06-10T19:27:53Z} is not arbitrary and is not a placeholder: it is the one
     * distinct originating timestamp carried by the frozen daily transaction fixture, which renders it in
     * all 300 rows as {@code 2022-06-10 19:27:53.000000} across columns 279 to 304. Choosing it means a
     * value derived from this clock can be compared against fixture bytes directly. It is also the instant
     * the unit tier and the batch integration tier already standardised on, so all three agree rather than
     * drifting.
     */
    @Autowired
    private Clock fixedClock;

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
     * Testcontainers extension before any instance exists; the clock and the persistence context are both
     * injected by the Spring test framework after construction. A subclass therefore needs no constructor of
     * its own, and must not attempt to obtain a repository, read the clock or query the database from one,
     * because injection has not yet happened at that point.
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
     * <p>It is the same instance the context's own beans received, because {@link FixedClockTestConfiguration}
     * publishes it as the primary {@code java.time.Clock} and this class injects it back rather than building
     * a copy. A subclass therefore needs no other time source, and the production beans under test cannot be
     * reading a different one.
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
     * <p><strong>The read is delegated to {@link FixtureLoader}, which is the tier's single fixture
     * authority, and this method holds no parser of its own.</strong> That matters for more than tidiness.
     * A second reader here would be a second, weaker set of guards: it would tolerate a fixture converted
     * to CRLF, a fixture whose terminating line feed had been dropped, a fixture re-encoded outside 7-bit
     * ASCII, a short record produced by a whitespace trim, and a record count that no longer matches the
     * frozen census - and it would hand every one of those to a parity assertion as though it were the
     * legacy byte image. {@code FixtureLoader} refuses all six, and it is itself proved by
     * {@code com.cardemo.unit.model.FixtureLoaderTest}, so the guarantee this method offers is an executed
     * one rather than an asserted one. The name is resolved through the {@link FixtureLoader.Fixture}
     * census, which is what supplies the record width the strict parse needs and what makes the
     * {@code dalytran.txt} misspelling fail immediately, by name, instead of at some later offset.
     *
     * @param resourceName bare classpath-root name of the fixture, for example {@code "dailytran.txt"};
     *                     must be non-{@code null}, not blank, and one of the nine catalogued names
     * @return an immutable list of the file's records in file order, each exactly as stored including any
     *         trailing spaces, and excluding the empty tail produced by the terminating line feed
     * @throws NullPointerException     if {@code resourceName} is {@code null}
     * @throws IllegalArgumentException if {@code resourceName} is blank
     * @throws IllegalStateException    if the name is not one of the nine catalogued fixtures, if no such
     *                                  classpath resource exists, if reading it fails, or if the resource
     *                                  no longer matches its frozen census or byte-level invariants. The
     *                                  message names the resource in every case and the underlying failure
     *                                  is preserved as the cause rather than swallowed. Failing loudly is
     *                                  deliberate: the canonical mistake is spelling the daily transaction
     *                                  fixture {@code dalytran.txt} after the mainframe dataset name
     *                                  instead of {@code dailytran.txt}, and returning an empty list for
     *                                  that would turn a typo into a confusing failure far from its cause
     */
    protected final List<String> readFixture(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName must not be null");
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        FixtureLoader.Fixture catalogued = null;
        for (FixtureLoader.Fixture candidate : FixtureLoader.Fixture.values()) {
            if (candidate.resourceName().equals(resourceName)) {
                catalogued = candidate;
            }
        }
        if (catalogued == null) {
            throw new IllegalStateException("Fixture '" + resourceName + "' is not one of the nine "
                    + "catalogued app/data/ASCII fixtures. They are flat direct children of "
                    + "src/test/resources; note that the daily transaction fixture is named "
                    + "'dailytran.txt', not 'dalytran.txt'.");
        }
        try {
            return FixtureLoader.load(catalogued).records();
        } catch (IllegalArgumentException | UncheckedIOException cause) {
            throw new IllegalStateException("Failed to read fixture '" + resourceName
                    + "' from the classpath root.", cause);
        }
    }

    /**
     * Publishes the fixed clock this tier's Spring context runs on.
     *
     * <p><strong>Why it has to be a bean rather than a field.</strong> This harness starts a full
     * {@link org.springframework.boot.test.context.SpringBootTest} context, so every production bean that
     * takes a {@code java.time.Clock} by constructor is instantiated for real. The application publishes one
     * such bean - {@code com.cardemo.config.ObservabilityConfig} declares it - and without
     * this configuration the eleven repository tests would run against a <em>moving</em> clock while this
     * class advertised a fixed one. Registering it here makes the advertised time source the actual one.
     *
     * <p>The bean is marked {@code @Primary}, and carries a name of its own rather than the production
     * spelling, for a single reason: {@code spring.main.allow-bean-definition-overriding} is {@code false} in
     * this application, so a name that collided with a production bean would fail the context refresh
     * outright. Being primary means this fixed clock wins wherever the type is injected, which is exactly the
     * purpose. It is the same mechanism, the same instant and the same zone that
     * {@code com.cardemo.integration.batch.AbstractBatchIntegrationTest} uses, so the two integration tiers
     * cannot disagree about "now".
     *
     * <p>{@code @Import} on a test class registers the test class itself as a configuration source, which is
     * why this nested type is imported explicitly rather than being picked up by scanning: nested
     * {@code @TestConfiguration} classes are only auto-detected when they are nested in the class that
     * <em>declares</em> the context, and every context here is declared by a subclass.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        /**
         * Creates the configuration.
         *
         * <p>Declared explicitly, and empty by design: the single bean below is stateless and there is
         * nothing to initialise. Spring instantiates this type reflectively, so the constructor is
         * package-private rather than public.
         */
        FixedClockTestConfiguration() {
            // Intentionally empty; see the constructor documentation above.
        }

        /**
         * The fixed UTC clock every time-dependent bean in this tier's context receives.
         *
         * <p>The instant is the single distinct originating timestamp of the frozen daily transaction
         * fixture, rendered in all 300 of its rows as {@code 2022-06-10 19:27:53.000000} across columns 279
         * to 304, so a value derived from this clock can be compared against fixture bytes directly. The zone
         * is {@code UTC} and is stated rather than inherited, matching both the production clock and the
         * persistence layer's own JDBC time zone, so a machine configured for any other zone renders
         * identical timestamps.
         *
         * @return the fixed clock, never {@code null}
         */
        @Bean
        @Primary
        Clock carddemoFixedTestClock() {
            return Clock.fixed(Instant.parse("2022-06-10T19:27:53Z"), ZoneOffset.UTC);
        }
    }
}
