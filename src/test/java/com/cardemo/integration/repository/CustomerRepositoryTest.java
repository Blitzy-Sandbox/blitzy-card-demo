/*
 * ******************************************************************
 * Program     : CustomerRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 test - repository integration tier (Failsafe)
 * Function    : Proves the persistence, mapping and constraint
 *               behaviour of the customer master against a real
 *               PostgreSQL 16 engine: the fifty seeded rows, the keyed
 *               read, the two CHECK constraints the table carries, the
 *               three constraints it deliberately does NOT carry, the
 *               survival of leading zeros through three counter-
 *               intuitive character mappings, the fixed-width CHAR
 *               contract at the database boundary, and both layers of
 *               concurrency control. Asserts nothing about services,
 *               screens, lookup tables or the snapshot comparison.
 * Source      : app/cpy/CVCUS01Y.cpy (key 9, RECLN 500; CUST-SSN at
 *               :L17, CUST-DOB-YYYY-MM-DD at :L19, CUST-FICO-CREDIT-
 *               SCORE at :L22, FILLER X(168) at :L23)
 *               + app/cpy/CUSTREC.cpy (duplicate layout, differing
 *               only in the date-of-birth field name),
 *               app/catlg/LISTCAT.txt:632 (KEYLEN 9 AVGLRECL 500),
 *               app/jcl/CUSTFILE.jcl:50-51 (KEYS(9 0)
 *               RECORDSIZE(500 500)),
 *               app/csd/CARDDEMO.CSD:50,52 (CICS FILE CUSTDAT),
 *               app/cbl/COACTUPC.cbl (:130-131 SSN, :350 indicator,
 *               :754-756 and :845-849 screen-only FICO range,
 *               :3920-3942 read for update, :4079-4102 asymmetric
 *               rollback, :4109-4193 change detection),
 *               app/cbl/COACTVWC.cbl:825-841 (keyed read outcomes),
 *               app/cbl/CBCUS01C.cbl (read-only sequential scan),
 *               app/cpy/CSLKPCDY.cpy:1013 (56 valid state codes) and
 *               :1073 (62 state prefixes in the ZIP combination
 *               table), app/data/ASCII/custdata.txt @ 7756d89
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.entity.Customer;
import com.cardemo.repository.CustomerRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Integration coverage for {@link CustomerRepository} against a real PostgreSQL 16 engine.
 *
 * <h2>What it does</h2>
 *
 * <p>The customer master is the widest table in the schema and the only one carrying personally
 * identifiable information. Its source record is 500 bytes across eighteen fields plus a 168-byte
 * filler ({@code app/cpy/CVCUS01Y.cpy:L4-L23}), it maps to nineteen properties on
 * {@link Customer} once the optimistic-locking counter is added, it is the target of one foreign
 * key from {@code card_cross_reference}, and it carries the fifth and last of the five CHECK
 * constraints {@code V1__create_schema.sql} declares.
 *
 * <p>This class asserts exactly one concern: that persistence, mapping and constraint behaviour
 * are what the frozen corpus dictates. It proves four kinds of thing.
 *
 * <ul>
 *   <li><strong>The seed and the keyed read.</strong> Fifty rows, resolved by the nine-byte key,
 *       with an absent key returning an empty {@link Optional} rather than throwing - the Java
 *       form of the two outcomes {@code app/cbl/COACTVWC.cbl:L837-L841} distinguishes.</li>
 *   <li><strong>The two constraints that exist.</strong> CHECK 3 of 5,
 *       {@code ck_customer_pri_card_holder_ind}, whose domain comes from
 *       {@code 88 FLG-PRI-CARDHOLDER-ISVALID VALUES 'Y', 'N'} at
 *       {@code app/cbl/COACTUPC.cbl:L350}; and CHECK 5 of 5,
 *       {@code ck_customer_ssn_numeric}, which restores the nine-digit domain that
 *       {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17} declares and that the
 *       demotion to fixed-width character would otherwise have discarded - corroborated by the
 *       redefinition at {@code app/cbl/COACTUPC.cbl:L130-L131}.</li>
 *   <li><strong>The three constraints that deliberately do not exist</strong>, asserted
 *       positively rather than assumed. There is no credit-score range, no state-code
 *       membership and no telephone-number format constraint, and adding any of them is a
 *       <strong>Blocker</strong>. Each absence is proved twice over: from the census of the
 *       seeded rows, and by persisting a value the putative constraint would have refused.</li>
 *   <li><strong>The fixed-width character contract and both concurrency layers.</strong> Leading
 *       zeros survive; every CHAR column returns blank-padded to its declared width; the
 *       database refuses an over-length value; the version counter increments; and a stale write
 *       fails.</li>
 *   </ul>
 *
 * <h3>Personally identifiable information - the strictest constraint on this file</h3>
 *
 * <p><strong>Severity: Blocker.</strong> Rule 1 Clause D names {@code tests} explicitly, and this
 * is the table the clause exists for. {@code app/cpy/CVCUS01Y.cpy} places two telephone numbers
 * at bytes 250-279, a national identifier at 280-288, a government-issued identifier at 289-308,
 * a date of birth at 309-318 and names and postal addresses at 10-234. Four rules follow and are
 * observed without exception below.
 *
 * <ol>
 *   <li><strong>No sensitive value reaches a log, an assertion message, an AssertJ description or
 *       this documentation.</strong> Where an assertion must identify a row it identifies it by
 *       {@code cust_id} alone. {@code Customer.toString()} deliberately emits only the identifier
 *       and the version, and {@code spring.jpa.show-sql} is {@code false} in every profile with
 *       no Hibernate SQL or bind-parameter logging anywhere - that setting is a PII control on
 *       this table, not a preference.</li>
 *   <li><strong>Shape is asserted, never content.</strong> Every assertion over a sensitive
 *       column is made on a derived {@code int} count or {@code boolean}, never on the value, so
 *       that a <em>failing</em> assertion cannot print one either. That is why this class writes
 *       {@code assertThat(value.length())} and {@code assertThat(value.matches(...))} rather than
 *       the more idiomatic {@code assertThat(value).hasSize(...)}, and why it asserts on
 *       {@code list.size()} rather than passing a fixture list to {@code assertThat}. The
 *       exceptions are deliberate and narrow: identifiers, state codes, country codes and credit
 *       scores are not sensitive and are compared directly.</li>
 *   <li><strong>Only three fixture column ranges are read at all</strong> - 1-9 for the key,
 *       235-236 for the state code and 330-332 for the credit score. Columns 250-318 are never
 *       read, never derived from and never printed.</li>
 *   <li><strong>Every provoked failure is provoked on a synthetic row.</strong> A PostgreSQL
 *       check-constraint or unique-constraint message carries a {@code Detail: Failing row
 *       contains (...)} clause, so provoking one against a seeded row would put a whole seeded
 *       record into a driver message. The two failures this class provokes against seeded row 1
 *       were measured and carry no row image: the over-length refusal reports only
 *       {@code value too long for type character(n)}, and the stale-write failure reports only
 *       the entity name and identifier.</li>
 *   </ol>
 *
 * <p>{@link Customer} is deliberately not {@link java.io.Serializable}; nothing here serialises
 * it, and nothing here should be changed to make it so.
 *
 * <h3>The three character mappings that look numeric and must not be</h3>
 *
 * <p><strong>Severity: High.</strong> Three columns are {@code CHAR} and three properties are
 * {@link String}, against the grain of the picture clauses they come from, and the seeded data is
 * what makes the choice provable rather than stylistic.
 *
 * <ul>
 *   <li>{@code cust_ssn CHAR(9)} - six of the fifty seeded values begin with a zero. A numeric
 *       mapping would destroy the leading zero and the nine-digit CHECK could never hold.</li>
 *   <li>{@code cust_fico_credit_score CHAR(3)} - seven of the fifty begin with a zero and the
 *       minimum is {@code 001}.</li>
 *   <li>{@code cust_dob_yyyy_mm_dd CHAR(10)} - text, never a {@link java.time.LocalDate}. The
 *       decisive evidence is the offset asymmetry in the change detection at
 *       {@code app/cbl/COACTUPC.cbl:L4109-L4193}: the live record holds a dash-separated date so
 *       its components sit at offsets 1, 6 and 9, while the snapshot holds the same date without
 *       separators so its components sit at 1, 5 and 7. A whole-string comparison of the two
 *       would report a change on every single request. That comparison belongs to
 *       {@code com.cardemo.service.account.AccountUpdateService} and is tested by
 *       {@code com.cardemo.unit.service}; only the stored representation is asserted here.</li>
 *   </ul>
 *
 * <h3>Both concurrency layers, and why the version column is not enough</h3>
 *
 * <p>{@code customer} is one of exactly four tables carrying {@code version BIGINT}, with
 * {@code account}, {@code card} and {@code "transaction"}. A version counter detects
 * <em>that</em> a row changed. {@code app/cbl/COACTUPC.cbl} detects <em>which field values</em>
 * differ from what the operator was shown: {@code 9700-CHECK-CHANGE-IN-REC} at
 * {@code :L4109-L4193} evaluates twelve account predicates and a comparable set of customer
 * predicates against a snapshot, and the case handling is deliberately asymmetric - customer
 * name, address, state, country and government-identifier fields are compared upper-cased on
 * both sides, the account group identifier is compared <em>lower</em>-cased on both sides at
 * {@code :L4139-L4140}, and the postal code, both telephone numbers, the national identifier, the
 * electronic-funds account identifier, the primary-holder indicator and the credit score are
 * compared with no case function at all. Neither layer substitutes for the other, and the second
 * layer is a service concern: it is <strong>cited here and asserted elsewhere</strong>.
 *
 * <p>The pessimistic half is in scope. {@link CustomerRepository#findByIdForUpdate(Long)} is the
 * Java form of the {@code EXEC CICS READ ... UPDATE} at
 * {@code app/cbl/COACTUPC.cbl:L3920-L3932}, whose failure exit at {@code :L3936-L3942} sets
 * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}. Note for the reader rather than for an assertion: the
 * source's {@code EXEC CICS SYNCPOINT ROLLBACK} fires <em>only</em> on the customer rewrite
 * failure at {@code :L4098-L4102} and <em>not</em> on the account rewrite failure at
 * {@code :L4079-L4080}, because at the earlier point nothing has yet been written inside the unit
 * of work. That asymmetry is reproduced by transaction scoping in the service and is not
 * conditional logic anywhere.
 *
 * <h3>One entity for two copybooks</h3>
 *
 * <p>{@code app/cpy/CVCUS01Y.cpy} and {@code app/cpy/CUSTREC.cpy} are the same 500-byte layout
 * with identical field order and identical widths. The only substantive difference is the
 * date-of-birth field name - {@code CUST-DOB-YYYY-MM-DD} against {@code CUST-DOB-YYYYMMDD} - so
 * one entity and one repository serve both, and this class expects no second customer type. The
 * orphan cluster {@code AWS.CUSTDATA.CLUSTER} defined in {@code app/jcl/DEFCUST.jcl} with a
 * ten-byte key is opened by no program and is modelled by nothing; it is a recorded
 * <strong>Low</strong>-severity finding, not a target.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and run the whole gate with {@code ./mvnw clean verify}. To compile this tree alone,
 * which is the fastest check that the file still satisfies the compiler settings, use
 * {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe, not Surefire, and the binding is by path.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code
 * /*Test.java} and runs it at {@code integration-test} and {@code verify},
 * even though the class keeps the {@code Test} suffix; {@code maven-surefire-plugin} 3.5.4 owns
 * {@code **}{@code /unit/}{@code **} and explicitly excludes this tree. A class moved to the
 * parent {@code integration} level, to {@code com.cardemo}, or directly into
 * {@code src/test/java} matches neither include set, is collected by neither plugin, and simply
 * never runs - green build, both plugins reporting success, no error and no output. Do not rename
 * or relocate this file.
 *
 * <p><strong>A reachable Docker socket is a prerequisite</strong>, because the harness starts a
 * real PostgreSQL 16 container and a reaper container. Where a host JDK is not provisioned the
 * identical build runs inside the pinned image with the repository mounted, and produces the same
 * result because every plugin and every non-managed dependency version is pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration of its own, performs no environment-variable or
 * system-property read and no system-property write, and contains no host, port, database name,
 * user name, password, JDBC URL or cloud endpoint. Everything below is inherited from
 * {@link AbstractRepositoryIntegrationTest}, which owns the tier's only container declaration,
 * its only Spring context declaration and its only connection-property source.
 *
 * <ul>
 *   <li>Profile {@code test}; PostgreSQL 16 pinned <em>by digest</em> rather than by tag, on the
 *       Debian base rather than {@code alpine}, so that text collation cannot differ between the
 *       engine under test and the engine that ships.</li>
 *   <li>The injected fixed UTC clock is the tier's only time source. Nothing here reads the
 *       ambient clock - no current instant, no current date, no millisecond counter - because
 *       such failures cluster on second, day, month and year boundaries and cannot be reproduced
 *       on demand. This class is in fact wholly time-independent: the customer record carries no
 *       timestamp column.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate}, so the table name, every column name,
 *       every SQL type and every width asserted by the entity are checked at context startup
 *       rather than discovered at query time; {@code spring.jpa.open-in-view: false};
 *       {@code spring.jpa.show-sql: false}; Hibernate's JDBC time zone {@code UTC}.</li>
 *   <li>Flyway {@code validate-on-migrate: true}, {@code clean-disabled: true},
 *       {@code out-of-order: false}, {@code baseline-on-migrate: false}, with neither
 *       {@code ignore-migration-patterns} nor {@code continue-on-error} set. There are exactly
 *       three migrations; the {@code BATCH_*} tables come from the framework's own script through
 *       {@code spring.batch.jdbc.initialize-schema} and never from a fourth migration.</li>
 *   <li><strong>The blank-pad policy, stated once and applied everywhere: PostgreSQL blank-pads
 *       {@code CHAR(n)} and therefore always returns exactly {@code n} characters, so this class
 *       compares against the full padded form and never trims.</strong> That is the same
 *       discipline the fixed-width writers need, and it is why the seeded postal code of row 1
 *       comes back at ten characters and its first name at twenty-five. Trimming here would
 *       silently disagree with the parity contract everywhere else.</li>
 *   <li>Isolation is transactional rollback, inherited from the class-level
 *       {@code Transactional} on the harness. Every row this class writes disappears when the
 *       test method ends, which is what keeps the inbound foreign key from
 *       {@code card_cross_reference} satisfied without any cleanup. There is deliberately no
 *       {@code Sql} script, no {@code DirtiesContext}, no {@code TRUNCATE} and no
 *       {@code deleteAll}.</li>
 *   <li><strong>No static field of any kind is declared here.</strong> The harness documents its
 *       single {@code static} member - the container - as the one deliberate exception for the
 *       whole hierarchy and forbids any further one in a subclass. The verified census figures
 *       are therefore held as {@code private final} <em>instance</em> fields, each documented
 *       with the fixture columns it was measured from. That differs from two sibling classes in
 *       this package which use {@code private static final} counts; the stricter reading is
 *       applied here on purpose rather than by oversight.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test fails to start with a container or Docker error.</em> There is no
 *       reachable Docker socket. This tier cannot be made to pass without one; state the blocker
 *       rather than asserting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version resolves.</em>
 *       This is the <strong>Blocker</strong>-severity trap of the whole migration and its remedy
 *       has two halves, both required. Half one: pin Testcontainers to exactly {@code 2.0.3} by
 *       overriding the version property the Spring Boot parent manages, never by importing a
 *       second bill of materials, because two competing imports resolve in an ordering-dependent
 *       way that can silently select the parent-managed 1.x line. Half two: use only the prefixed
 *       module coordinates that exist on the 2.x line - {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter} - because the bare 1.x identifiers
 *       {@code postgresql}, {@code localstack} and {@code junit-jupiter} are not published there
 *       and fail resolution outright. Overriding without renaming resolves artefacts that do not
 *       exist; renaming without overriding resolves the wrong version. Both halves are already in
 *       place in the root {@code pom.xml}, which is root-owned and must not be edited from
 *       here.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with
 *       {@code -Xlint:all} and {@code -Werror} and that reaches test compilation, so a raw type,
 *       an unchecked cast, a deprecation or a dangling documentation comment is an error rather
 *       than a warning. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than on a column name.</em>
 *       {@code validate} compares type codes, so a {@link Long} over {@code NUMERIC(9)} can fail
 *       where {@code BIGINT} or {@code INTEGER} passes. <strong>The fix is upstream</strong>, in
 *       {@code V1__create_schema.sql} or in the entity mapping. Never widen a column to silence
 *       it and never patch this test. Severity <strong>Medium</strong>.</li>
 *   <li><em>A test fails on {@code ck_customer_ssn_numeric} for no apparent reason.</em> Every
 *       {@link Customer} constructed anywhere in this class <strong>must</strong> carry a
 *       nine-digit national identifier. A helper that left it blank, short or non-numeric would
 *       trip CHECK 5 of 5 and report a constraint that has nothing to do with the behaviour under
 *       test. {@link #syntheticCustomer} takes the value as a parameter for exactly that reason,
 *       and every call site but the two that are deliberately provoking the constraint passes a
 *       nine-digit synthetic value.</li>
 *   <li><em>A width assertion fails after an apparently harmless edit to the fixture.</em>
 *       {@code custdata.txt} is a frozen byte image in which a trailing space is data. A
 *       trailing-whitespace cleanup would collapse each record from 500 characters to 332 and
 *       every position-based read after the first short row would be wrong. The root
 *       {@code .editorconfig} carves its whitespace trimming out for {@code *.md} only and is
 *       inapplicable to this file; never copy, trim, normalise or edit the fixture.</li>
 *   <li><em>A fixture read yields {@code null} and the failure surfaces far from its cause.</em>
 *       {@link AbstractRepositoryIntegrationTest#readFixture} resolves a bare classpath-root name
 *       through a strict census and fails immediately, naming the resource, rather than returning
 *       an empty list.</li>
 *   <li><em>A provoked violation is followed by a confusing second failure in the same test.</em>
 *       PostgreSQL aborts a transaction after any failed statement, so every subsequent statement
 *       in it fails too. Each test below therefore makes its provocation the <em>last</em>
 *       database operation in the method, and provokes at most one.</li>
 *   <li><em>Someone proposes a validation annotation on the credit score or the state code.</em>
 *       A range on the score, a membership pattern or enum on the state code, a format pattern on
 *       either telephone column or on the postal code, and a fourth index on this table are each
 *       a <strong>Blocker</strong>. The tests in {@code ConstraintsDeliberatelyAbsent} exist to
 *       fail loudly the moment one is added.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are stated as <strong>Not available</strong> rather than glossed over, because
 * inventing either would manufacture a false oracle.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured
 *       legacy output exists anywhere in this repository: a search across expected, baseline,
 *       golden, {@code .out} and system-output name patterns, and across the reject, report,
 *       statement and HTML dataset names, returns only dataset <em>definition</em> members such
 *       as {@code app/jcl/DALYREJS.jcl} and {@code app/jcl/TRANREPT.jcl}, and no captured data at
 *       all. What is needed to close it is a captured 430-byte reject dataset from a real posting
 *       run at a known input state together with the resulting transaction, account and
 *       category-balance images. Until that exists: create no baseline file, fabricate no
 *       expected bytes, and do not generate a baseline by running this implementation and
 *       asserting against its own output, which is circular. Hand-simulating the posting program
 *       is equally inadmissible - two defensible models of it over these same fixtures disagree,
 *       one yielding 13 rejects and the other 38, and a figure that moves with the model is not
 *       an oracle. <strong>This class creates no baseline file and asserts against none.</strong>
 *       Its own oracle is different in kind and is legitimate: {@code custdata.txt} is a frozen
 *       input fixture, not a captured output, so comparing the loaded table against it proves the
 *       seed decoded faithfully and claims nothing about behaviour.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong>
 *       The census across the 28 programs of {@code app/cbl} finds the literal file status
 *       {@code '35'} zero times and {@code DFHRESP(NOTOPEN)} zero times; the response codes the
 *       corpus actually tests are {@code NORMAL}, {@code NOTFND}, {@code ENDFILE},
 *       {@code DUPREC} and {@code DUPKEY}. No test for that condition is invented here and no
 *       parity claim is made for it.</p></li>
 *   </ol>
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Rule 1 Clause C forbids duplication, and each of the following is proved elsewhere and is
 * cited rather than repeated: the entity's own width and null guards, which reject an over-length
 * or null value before a save could ever reach the database, proved by
 * {@code com.cardemo.unit.model.CustomerTest} and
 * {@code com.cardemo.unit.model.EntityBoundaryValidationTest} - and which is precisely why the
 * database-boundary assertions below go through a parameterised native statement rather than
 * through the entity; the ordering and exclusivity of the keyset finder and the census of the
 * three non-unique alternate indexes, proved by
 * {@code com.cardemo.integration.repository.RepositorySchemaAndFinderIntegrationTest}; the
 * copybook lookup tables, proved by {@code com.cardemo.unit.validation}; and the sign-on flow,
 * the cross-reference to account to customer view chain of {@code app/cbl/COACTVWC.cbl}, the
 * read-only sequential scan of {@code app/cbl/CBCUS01C.cbl} and the snapshot comparison, all of
 * which are service and batch concerns. There is no mapped association to
 * {@code card_cross_reference} here either: the inbound foreign key is asserted by the
 * cross-reference tier.
 *
 * @see CustomerRepository
 * @see Customer
 * @see AbstractRepositoryIntegrationTest
 */
@DisplayName("CustomerRepository against PostgreSQL 16 - the PII table, the two CHECKs it carries "
        + "and the three it deliberately lacks")
class CustomerRepositoryTest extends AbstractRepositoryIntegrationTest {

    /**
     * Rows {@code V3__seed_data.sql} loads into {@code customer}, measured from
     * {@code app/data/ASCII/custdata.txt}: 25,050 bytes of 500-byte records plus one line feed
     * each. Corroborated by {@code REC-TOTAL 50} at {@code app/catlg/LISTCAT.txt:637}.
     */
    private final int seededRowCount = 50;

    /**
     * Characters in one fixture record - {@code AVGLRECL 500} at
     * {@code app/catlg/LISTCAT.txt:632}, {@code RECORDSIZE(500 500)} at
     * {@code app/jcl/CUSTFILE.jcl:51}, and the eighteen field widths of
     * {@code app/cpy/CVCUS01Y.cpy:L5-L22} plus the 168-byte filler at {@code :L23}.
     */
    private final int recordWidth = 500;

    /**
     * Characters in the key prefix of a fixture record - {@code CUST-ID PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L5}, {@code KEYLEN 9} with {@code RKP 0} at
     * {@code app/catlg/LISTCAT.txt:632-633}, {@code KEYS(9 0)} at
     * {@code app/jcl/CUSTFILE.jcl:50}.
     */
    private final int keyWidth = 9;

    /**
     * Bytes of the 500 that the unmodelled {@code FILLER PIC X(168)} at
     * {@code app/cpy/CVCUS01Y.cpy:L23} occupies. Space-filled on all fifty fixture rows, which is
     * also the longest trailing-space run any record carries.
     */
    private final int fillerWidth = 168;

    /**
     * Seeded rows whose credit score is below 300, measured from fixture columns 330-332. Twenty-one
     * of fifty, which is what makes the absence of a range constraint provable rather than assumed.
     */
    private final int scoresBelowThreeHundred = 21;

    /**
     * Seeded national identifiers beginning with a zero, measured from fixture columns 280-288. Six
     * of fifty. A numeric mapping would reduce this to zero.
     */
    private final int leadingZeroIdentifiers = 6;

    /**
     * Seeded credit scores beginning with a zero, measured from fixture columns 330-332. Seven of
     * fifty, the smallest being {@code 001}.
     */
    private final int leadingZeroScores = 7;

    /**
     * Seeded rows whose state code is absent from the fifty-six-entry
     * {@code 88 VALID-US-STATE-CODE} table at {@code app/cpy/CSLKPCDY.cpy:L1013}, measured from
     * fixture columns 235-236. Five of fifty, carrying four distinct codes.
     */
    private final int rowsWithUnrecognisedStateCode = 5;

    /** The repository under test - the relational replacement for CICS file {@code CUSTDAT}. */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Used for exactly one purpose: to reach the database with a value the entity's own width guard
     * refuses, so that the {@code CHAR(n)} width contract can be asserted at the boundary that
     * actually enforces it. Every statement it issues is a static string with positional
     * placeholders; there is no concatenated, interpolated or otherwise input-influenced SQL
     * anywhere in this class.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Builds a customer whose every field is obviously synthetic, varying only the five values any
     * test here needs to vary.
     *
     * <p>The identifier must sit outside the seeded range 1-50 unless a duplicate is the point, and
     * must satisfy {@code CUST-ID PIC 9(09)}. <strong>The national identifier must be nine digits
     * unless CHECK 5 of 5 is the behaviour under test</strong>, because every other constraint
     * violation would otherwise be masked by that one.
     *
     * @param customerId                 the nine-digit key
     * @param nationalIdentifier         the {@code cust_ssn CHAR(9)} value; nine digits unless the
     *                                   constraint itself is under test
     * @param primaryCardHolderIndicator the {@code cust_pri_card_holder_ind CHAR(1)} value
     * @param stateCode                  the {@code cust_addr_state_cd CHAR(2)} value
     * @param creditScore                the {@code cust_fico_credit_score CHAR(3)} value
     * @param telephoneNumber            the value used for both {@code CHAR(15)} telephone columns
     * @return a transient entity whose version is null, so that Spring Data treats it as new and
     *         issues a real {@code INSERT} rather than a merge
     */
    private Customer syntheticCustomer(final long customerId,
                                       final String nationalIdentifier,
                                       final String primaryCardHolderIndicator,
                                       final String stateCode,
                                       final String creditScore,
                                       final String telephoneNumber) {
        return new Customer(Long.valueOf(customerId),
                "SYNTHETIC-FIRST",
                "SYNTHETIC-MIDDLE",
                "SYNTHETIC-LAST",
                "SYNTHETIC ADDRESS LINE ONE",
                "SYNTHETIC ADDRESS LINE TWO",
                "SYNTHETIC ADDRESS LINE THREE",
                stateCode,
                "USA",
                "00000",
                telephoneNumber,
                telephoneNumber,
                nationalIdentifier,
                "SYNTHETIC-GOVT-ID",
                "0001-01-01",
                "0000000000",
                primaryCardHolderIndicator,
                creditScore);
    }

    /**
     * Walks a cause chain to its end so that an assertion can prove the root cause was preserved
     * rather than swallowed.
     *
     * <p>The walk is bounded rather than unbounded: a malformed chain must fail an assertion, not
     * hang a build.
     *
     * @param throwable the exception to unwrap; must not be null
     * @return the deepest cause reachable within sixteen links, or {@code throwable} itself when it
     *         has no cause
     */
    private Throwable rootCauseOf(final Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; depth < 16 && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Reports whether an exception's own message contains a token, as a {@code boolean} so that a
     * failing assertion prints only {@code true} or {@code false}.
     *
     * <p>This is the PII-safe form of {@code hasMessageContaining}. A PostgreSQL constraint message
     * carries a {@code Detail: Failing row contains (...)} clause, so an assertion that printed the
     * message on failure could print a whole record. Matching is always on the <em>constraint
     * name</em>, never on the offending value.
     *
     * @param throwable the exception whose message to inspect; must not be null
     * @param token     the constraint name or diagnostic fragment expected in it
     * @return true when the message is non-null and contains the token
     */
    private boolean messageContains(final Throwable throwable, final String token) {
        final String message = throwable.getMessage();
        return message != null && message.contains(token);
    }

    /**
     * Loads all fifty seeded rows in ascending key order, which is the order the legacy browse
     * returns.
     *
     * <p>{@code app/cbl/CBCUS01C.cbl:L30-L31} declares {@code ORGANIZATION IS INDEXED} with
     * {@code ACCESS MODE IS SEQUENTIAL}, so the source scan is key-ordered; a bare
     * {@code findAll()} guarantees no order at all, and an assertion that compared a result
     * position-by-position against the fixture would then be non-deterministic. Ordering is
     * therefore always explicit here.
     *
     * @return every customer row, ascending by identifier, never null
     */
    private List<Customer> allSeededCustomersInKeyOrder() {
        return customerRepository.findAll(Sort.by(Sort.Direction.ASC, "customerId"));
    }

    @Nested
    @DisplayName("the fifty seeded rows and the nine-byte key")
    class SeedAndKeyedRead {

        @Test
        @DisplayName("the seed loaded exactly the fifty rows the frozen fixture carries")
        void theSeedLoadedExactlyFiftyRows() {
            assertThat(customerRepository.count())
                    .as("app/data/ASCII/custdata.txt is 25,050 bytes of 500-byte records, so fifty rows, "
                            + "and app/catlg/LISTCAT.txt:637 reports REC-TOTAL 50 for the same cluster")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("the nine-digit key resolves the first customer's state, country and credit score")
        void theNineDigitKeyResolvesTheFirstCustomer() {
            final Optional<Customer> found = customerRepository.findById(Long.valueOf(1L));

            assertThat(found)
                    .as("cust_id 1 is the first record of the frozen fixture, and findById is the Java "
                            + "form of the keyed EXEC CICS READ at app/cbl/COACTVWC.cbl:L826-L834")
                    .isPresent();

            final Customer customer = found.orElseThrow();

            assertThat(customer.getAddressStateCode())
                    .as("columns 235-236 of fixture row 1 - CUST-ADDR-STATE-CD PIC X(02) at "
                            + "app/cpy/CVCUS01Y.cpy:L12, mapped to cust_addr_state_cd CHAR(2)")
                    .isEqualTo("NC");
            assertThat(customer.getAddressCountryCode())
                    .as("columns 237-239 - CUST-ADDR-COUNTRY-CD PIC X(03) at app/cpy/CVCUS01Y.cpy:L13")
                    .isEqualTo("USA");
            assertThat(customer.getFicoCreditScore())
                    .as("columns 330-332 - CUST-FICO-CREDIT-SCORE PIC 9(03) at app/cpy/CVCUS01Y.cpy:L22, "
                            + "carried as CHAR(3) text so that a leading zero survives")
                    .isEqualTo("274");
            assertThat(customer.getCustomerId())
                    .as("the identifier is a Long over cust_id NUMERIC(9); nothing else about row 1 is "
                            + "named here, because every remaining column of this table is personal data")
                    .isEqualTo(Long.valueOf(1L));
        }

        @Test
        @DisplayName("a key outside the seeded range resolves to nothing rather than throwing")
        void anAbsentKeyResolvesToNothing() {
            assertThat(customerRepository.findById(Long.valueOf(999_999_999L)))
                    .as("999999999 is the largest value nine unsigned display digits can hold and is not "
                            + "seeded. app/cbl/COACTVWC.cbl:L839-L841 treats DFHRESP(NOTFND) as a control "
                            + "path rather than an abend, so the Java form is an empty Optional and the "
                            + "caller - not this repository - decides whether that is an error")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("CHECK 5 of 5 - cust_ssn is exactly nine digits")
    class NationalIdentifierCheckConstraint {

        @Test
        @DisplayName("a nine-digit value satisfies the constraint and round-trips at its full CHAR(9) width")
        void aNineDigitValuePersists() {
            final long identifier = 900_000_101L;
            final int nationalIdentifierWidth = 9;

            customerRepository.save(
                    syntheticCustomer(identifier, "000000000", "Y", "NC", "274", "(000)000-0000"));
            flushAndClear();

            final String stored = customerRepository.findById(Long.valueOf(identifier))
                    .orElseThrow()
                    .getSsn();

            assertThat(stored.length())
                    .as("cust_ssn is CHAR(9) from CUST-SSN PIC 9(09) at app/cpy/CVCUS01Y.cpy:L17; the "
                            + "value is asserted by shape rather than by content because this column is "
                            + "personal data even when the value under test is synthetic")
                    .isEqualTo(nationalIdentifierWidth);
            assertThat(stored.matches("[0-9]{9}"))
                    .as("ck_customer_ssn_numeric is CHECK (cust_ssn ~ '^[0-9]{9}$'), restoring the numeric "
                            + "domain that the demotion from PIC 9(09) to fixed-width character discarded")
                    .isTrue();
        }

        @Test
        @DisplayName("an eight-digit value is refused, because CHAR(9) blank-pads it out of the digit domain")
        void anEightDigitValueIsRefused() {
            final String constraint = "ck_customer_ssn_numeric";

            final Throwable thrown = catchThrowable(() -> {
                customerRepository.save(
                        syntheticCustomer(900_000_102L, "12345678", "Y", "NC", "274", "(000)000-0000"));
                flushAndClear();
            });

            assertThat(thrown)
                    .as("nothing enforces the domain until the INSERT is actually sent, so the flush is "
                            + "what fails; a short value is not a length error here because CHAR(9) pads "
                            + "it to nine characters, one of which is then not a digit")
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(messageContains(thrown, constraint))
                    .as("the thrown message must name CHECK 5 of 5, grounded at "
                            + "app/cpy/CVCUS01Y.cpy:L17 and app/cbl/COACTUPC.cbl:L130-L131; matched on "
                            + "the constraint name rather than on the offending value")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), constraint))
                    .as("and the preserved root cause names the same constraint")
                    .isTrue();
        }

        @Test
        @DisplayName("a nine-character value carrying a non-digit is refused by the same constraint")
        void aNonDigitValueIsRefused() {
            final String constraint = "ck_customer_ssn_numeric";

            final Throwable thrown = catchThrowable(() -> {
                customerRepository.save(
                        syntheticCustomer(900_000_103L, "12345678X", "Y", "NC", "274", "(000)000-0000"));
                flushAndClear();
            });

            assertThat(thrown)
                    .as("the second failing direction: correct width, wrong domain. The entity's own "
                            + "width guard cannot catch this one, which is why the constraint has to")
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(messageContains(thrown, constraint))
                    .as("the anchored regex rejects any non-digit anywhere in the nine characters")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), constraint))
                    .as("and the preserved root cause names the same constraint")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("CHECK 3 of 5 - cust_pri_card_holder_ind is 'Y' or 'N'")
    class PrimaryCardHolderIndicatorCheckConstraint {

        @Test
        @DisplayName("both legal indicator values persist, and 'N' can only be proved with a constructed row")
        void bothLegalIndicatorValuesPersist() {
            final long holder = 900_000_201L;
            final long nonHolder = 900_000_202L;

            customerRepository.save(syntheticCustomer(holder, "000000000", "Y", "NC", "274", "(000)000-0000"));
            customerRepository.save(
                    syntheticCustomer(nonHolder, "000000000", "N", "NC", "274", "(000)000-0000"));
            flushAndClear();

            assertThat(customerRepository.findById(Long.valueOf(holder)).orElseThrow()
                    .getPrimaryCardHolderIndicator())
                    .as("byte 329 of all fifty fixture rows is 'Y', which is what V1 cites as its "
                            + "corroboration for the domain")
                    .isEqualTo("Y");
            assertThat(customerRepository.findById(Long.valueOf(nonHolder)).orElseThrow()
                    .getPrimaryCardHolderIndicator())
                    .as("no seeded row carries 'N', so the other half of the domain from "
                            + "88 FLG-PRI-CARDHOLDER-ISVALID at app/cbl/COACTUPC.cbl:L350 is only "
                            + "provable with a constructed row")
                    .isEqualTo("N");
        }

        @Test
        @DisplayName("a third indicator value is refused by ck_customer_pri_card_holder_ind")
        void aThirdIndicatorValueIsRefused() {
            final String constraint = "ck_customer_pri_card_holder_ind";

            final Throwable thrown = catchThrowable(() -> {
                customerRepository.save(
                        syntheticCustomer(900_000_203L, "000000000", "X", "NC", "274", "(000)000-0000"));
                flushAndClear();
            });

            assertThat(thrown)
                    .as("the domain is exactly two values. 88 FLG-YES-NO-ISVALID at "
                            + "app/cbl/COACTUPC.cbl:L78 shares those two values but sits on the generic "
                            + "reusable WS-EDIT-YES-NO work field, not on a second column of this table, "
                            + "so no sixth constraint is derived from it - V1 declares exactly five")
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(messageContains(thrown, constraint))
                    .as("the thrown message must name CHECK 3 of 5, grounded at "
                            + "app/cbl/COACTUPC.cbl:L350")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), constraint))
                    .as("and the preserved root cause names the same constraint")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the three constraints this table deliberately does NOT carry")
    class ConstraintsDeliberatelyAbsent {

        @Test
        @DisplayName("twenty-one seeded scores are below 300, so no credit-score range constraint can exist")
        void twentyOneSeededScoresAreBelowThreeHundred() {
            final List<Customer> seeded = allSeededCustomersInKeyOrder();

            final long below = seeded.stream()
                    .map(Customer::getFicoCreditScore)
                    .filter(score -> Integer.parseInt(score) < 300)
                    .count();

            assertThat(seeded.size())
                    .as("the census is taken over the whole seeded table, not a sample")
                    .isEqualTo(seededRowCount);
            assertThat(below)
                    .as("app/cbl/COACTUPC.cbl:L845-L849 puts 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850 "
                            + "on ACUP-NEW-CUST-FICO-SCORE, which sits inside the SCREEN-INPUT "
                            + "ACUP-NEW-DETAILS group opened at :L757, while the symmetric old-side field "
                            + "at :L754-L756 carries no 88 level at all. The range is therefore a "
                            + "screen-input rule and never a stored-data invariant, and these twenty-one "
                            + "rows are the proof: a Min, a Max or a CHECK on this column would have made "
                            + "V3 unseedable. Adding one is a Blocker")
                    .isEqualTo(scoresBelowThreeHundred);
        }

        @Test
        @DisplayName("a score of 001 - the fixture minimum - persists, so no range guards the column")
        void aScoreBelowThreeHundredPersists() {
            final long identifier = 900_000_301L;

            customerRepository.save(syntheticCustomer(identifier, "000000000", "Y", "NC", "001", "(000)000-0000"));
            flushAndClear();

            assertThat(customerRepository.findById(Long.valueOf(identifier)).orElseThrow().getFicoCreditScore())
                    .as("001 is the smallest score the fixture carries and it round-trips unchanged, "
                            + "leading zeros intact. Cited: app/cbl/COACTUPC.cbl:L845-L849 scopes the "
                            + "300-850 range to screen input, and :L754-L756 shows the stored-side field "
                            + "carrying no range at all")
                    .isEqualTo("001");
        }

        @Test
        @DisplayName("five seeded rows carry state codes the fifty-six-entry lookup table omits")
        void fiveSeededRowsCarryUnrecognisedStateCodes() {
            final List<String> unrecognised = List.of("AP", "FM", "MH", "PW");

            final long rows = allSeededCustomersInKeyOrder().stream()
                    .map(Customer::getAddressStateCode)
                    .filter(unrecognised::contains)
                    .count();

            assertThat(rows)
                    .as("app/cpy/CSLKPCDY.cpy:L1013 declares 88 VALID-US-STATE-CODE over exactly 56 codes "
                            + "spanning :L1013-:L1069, and none of AP, FM, MH or PW appears among them. "
                            + "The same copybook's 88 VALID-US-STATE-ZIP-CD2-COMBO at :L1073 spans "
                            + ":L1073-:L1313 over 62 distinct state prefixes and does include all four, so "
                            + "the copybook's own two tables disagree about which codes are legal - a "
                            + "recorded Low-severity asymmetry, and decisive evidence that membership is a "
                            + "screen-input rule enforced by ValidationLookupService rather than a schema "
                            + "constraint. A membership CHECK, a Pattern or an enum mapping is a Blocker")
                    .isEqualTo(rowsWithUnrecognisedStateCode);
        }

        @Test
        @DisplayName("a state code outside that table persists, so no membership constraint guards the column")
        void anUnrecognisedStateCodePersists() {
            final long identifier = 900_000_302L;

            customerRepository.save(syntheticCustomer(identifier, "000000000", "Y", "PW", "274", "(000)000-0000"));
            flushAndClear();

            assertThat(customerRepository.findById(Long.valueOf(identifier)).orElseThrow().getAddressStateCode())
                    .as("PW is absent from the 56-entry table at app/cpy/CSLKPCDY.cpy:L1013 yet present in "
                            + "the ZIP combination table at :L1073 as PW96, and the database accepts it "
                            + "either way because it enforces width and nullability only")
                    .isEqualTo("PW");
        }

        @Test
        @DisplayName("a telephone number in a different format persists, because the shape is unvalidated text")
        void aDifferentlyFormattedTelephoneNumberPersists() {
            final long identifier = 900_000_303L;
            final int telephoneWidth = 15;

            customerRepository.save(
                    syntheticCustomer(identifier, "000000000", "Y", "NC", "274", "000.000.0000   "));
            flushAndClear();

            final String stored = customerRepository.findById(Long.valueOf(identifier))
                    .orElseThrow()
                    .getPhoneNumber1();

            assertThat(stored.length())
                    .as("CUST-PHONE-NUM-1 PIC X(15) at app/cpy/CVCUS01Y.cpy:L15 maps to CHAR(15)")
                    .isEqualTo(telephoneWidth);
            assertThat(stored.matches("[0-9]{3}\\.[0-9]{3}\\.[0-9]{4} {3}"))
                    .as("the stored value keeps the dotted shape it was given, trailing blanks included")
                    .isTrue();
            assertThat(stored.matches("\\([0-9]{3}\\)[0-9]{3}-[0-9]{4} {2}"))
                    .as("and it is emphatically NOT the parenthesised shape all fifty seeded rows use, "
                            + "yet the database accepted it: V1 declares no format constraint for either "
                            + "telephone column, and none may be added. Adding one is a Blocker")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the fixed-width character contract, including the leading zeros that prove it")
    class FixedWidthCharacterContract {

        @Test
        @DisplayName("six seeded national identifiers begin with a zero, which a numeric mapping would destroy")
        void sixSeededIdentifiersBeginWithZero() {
            final long leading = allSeededCustomersInKeyOrder().stream()
                    .map(Customer::getSsn)
                    .filter(value -> value.startsWith("0"))
                    .count();

            assertThat(leading)
                    .as("cust_ssn is CHAR(9) and the property is String, deliberately, even though "
                            + "app/cpy/CVCUS01Y.cpy:L17 declares CUST-SSN as PIC 9(09). Had either been "
                            + "numeric this count would be zero and ck_customer_ssn_numeric could never "
                            + "hold - which is exactly what this assertion exists to detect. Severity High")
                    .isEqualTo(leadingZeroIdentifiers);
        }

        @Test
        @DisplayName("seven seeded credit scores begin with a zero, for the same reason")
        void sevenSeededScoresBeginWithZero() {
            final long leading = allSeededCustomersInKeyOrder().stream()
                    .map(Customer::getFicoCreditScore)
                    .filter(value -> value.startsWith("0"))
                    .count();

            assertThat(leading)
                    .as("cust_fico_credit_score is CHAR(3) and the property is String, against the grain "
                            + "of CUST-FICO-CREDIT-SCORE PIC 9(03) at app/cpy/CVCUS01Y.cpy:L22. The "
                            + "smallest seeded value is 001, so a numeric mapping would have rendered it 1 "
                            + "and lost two characters of a fixed-width field. Severity High")
                    .isEqualTo(leadingZeroScores);
        }

        @Test
        @DisplayName("every stored date of birth is ten-character text in the dashed shape, never a LocalDate")
        void everyStoredDateOfBirthIsTenCharacterText() {
            final List<Customer> seeded = allSeededCustomersInKeyOrder();

            final long shaped = seeded.stream()
                    .map(Customer::getDateOfBirth)
                    .filter(value -> value.length() == 10 && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    .count();

            assertThat(shaped)
                    .as("this method compiles only because the accessor returns String: a LocalDate has no "
                            + "matches method. CUST-DOB-YYYY-MM-DD PIC X(10) at app/cpy/CVCUS01Y.cpy:L19 "
                            + "maps to cust_dob_yyyy_mm_dd CHAR(10) and is compared by the service as "
                            + "three substrings at offsets 1, 6 and 9 against a separator-free snapshot at "
                            + "1, 5 and 7 (app/cbl/COACTUPC.cbl:L4109-L4193), so the stored form must stay "
                            + "text. Counted rather than compared, because a date of birth may not appear "
                            + "in a failure message")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("both stored telephone columns are fifteen-character text in the parenthesised shape")
        void bothStoredTelephoneColumnsAreFifteenCharacterText() {
            final List<Customer> seeded = allSeededCustomersInKeyOrder();
            final String shape = "\\([0-9]{3}\\)[0-9]{3}-[0-9]{4} {2}";

            final long firstColumn = seeded.stream()
                    .map(Customer::getPhoneNumber1)
                    .filter(value -> value.matches(shape))
                    .count();
            final long secondColumn = seeded.stream()
                    .map(Customer::getPhoneNumber2)
                    .filter(value -> value.matches(shape))
                    .count();

            assertThat(firstColumn)
                    .as("all fifty seeded values of CUST-PHONE-NUM-1 (app/cpy/CVCUS01Y.cpy:L15) carry a "
                            + "parenthesised area code, three digits, a dash, four digits and two trailing "
                            + "blanks inside CHAR(15). That is a property of the data, not a rule the "
                            + "schema enforces - counted, never printed")
                    .isEqualTo(seededRowCount);
            assertThat(secondColumn)
                    .as("and CUST-PHONE-NUM-2 at :L16 is identical in shape")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("every CHAR column returns blank-padded to its declared width, and the widths sum to 500")
        void everyCharColumnReturnsBlankPaddedToItsDeclaredWidth() {
            final Customer customer = customerRepository.findById(Long.valueOf(1L)).orElseThrow();

            final int[] observed = {
                customer.getFirstName().length(),
                customer.getMiddleName().length(),
                customer.getLastName().length(),
                customer.getAddressLine1().length(),
                customer.getAddressLine2().length(),
                customer.getAddressLine3().length(),
                customer.getAddressStateCode().length(),
                customer.getAddressCountryCode().length(),
                customer.getAddressZip().length(),
                customer.getPhoneNumber1().length(),
                customer.getPhoneNumber2().length(),
                customer.getSsn().length(),
                customer.getGovernmentIssuedId().length(),
                customer.getDateOfBirth().length(),
                customer.getEftAccountId().length(),
                customer.getPrimaryCardHolderIndicator().length(),
                customer.getFicoCreditScore().length(),
            };

            assertThat(observed)
                    .as("PostgreSQL blank-pads CHAR(n), so every one of the seventeen character columns "
                            + "comes back at exactly its declared width whatever the fixture put in it. "
                            + "THE POLICY THIS CLASS APPLIES EVERYWHERE: compare against the full padded "
                            + "form and never trim, because a trailing space is data in a fixed-width "
                            + "record and the writers must be able to re-emit the original image. Widths "
                            + "from app/cpy/CVCUS01Y.cpy:L6-L22 in declaration order")
                    .containsExactly(25, 25, 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20, 10, 10, 1, 3);

            int sum = keyWidth + fillerWidth;
            for (final int width : observed) {
                sum += width;
            }

            assertThat(sum)
                    .as("the widths the live database returns, plus the nine-byte key and the unmodelled "
                            + "FILLER PIC X(168) at app/cpy/CVCUS01Y.cpy:L23, reconstruct the catalogued "
                            + "record length exactly - AVGLRECL 500 at app/catlg/LISTCAT.txt:632 and "
                            + "RECORDSIZE(500 500) at app/jcl/CUSTFILE.jcl:51")
                    .isEqualTo(recordWidth);
        }

        @Test
        @DisplayName("the database refuses an over-length state code, which no save through the entity can reach")
        void theDatabaseRefusesAnOverLengthStateCode() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                    "UPDATE customer SET cust_addr_state_cd = ? WHERE cust_id = ?", "XYZ", Long.valueOf(1L)));

            assertThat(thrown)
                    .as("the entity's own width guard refuses an over-length value before a save could "
                            + "ever reach the database - proved by com.cardemo.unit.model.CustomerTest - so "
                            + "asserting the boundary that actually enforces the CHAR(2) width requires a "
                            + "parameterised native statement. The statement is a static string with "
                            + "positional placeholders: no SQL text here can be influenced by input")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(messageContains(thrown, "character(2)"))
                    .as("the message names the column type that refused the value, from "
                            + "CUST-ADDR-STATE-CD PIC X(02) at app/cpy/CVCUS01Y.cpy:L12. It carries no row "
                            + "image at all, which is why provoking this one against seeded row 1 is safe")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), "value too long"))
                    .as("and the preserved root cause states the refusal in the engine's own terms")
                    .isTrue();
        }

        @Test
        @DisplayName("the database refuses an over-length name column on the same terms")
        void theDatabaseRefusesAnOverLengthNameColumn() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                    "UPDATE customer SET cust_last_name = ? WHERE cust_id = ?",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ", Long.valueOf(1L)));

            assertThat(thrown)
                    .as("twenty-six characters against CUST-LAST-NAME PIC X(25) at "
                            + "app/cpy/CVCUS01Y.cpy:L8. The replacement value is a synthetic alphabet "
                            + "rather than anything name-shaped, and the refusal message carries no row "
                            + "image, so nothing personal can reach a log from this test")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(messageContains(thrown, "character(25)"))
                    .as("the widest of the three name columns refuses at exactly its declared width")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), "value too long"))
                    .as("and the preserved root cause states the refusal in the engine's own terms")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("both concurrency layers - the version counter and the read for update")
    class ConcurrencyControl {

        @Test
        @DisplayName("the version column increments on a real update, flushed and reloaded")
        void theVersionColumnIncrementsOnARealUpdate() {
            final Customer before = customerRepository.findById(Long.valueOf(1L)).orElseThrow();

            assertThat(before.getVersion())
                    .as("V3 seeds version 0 on every customer row; customer is one of exactly four tables "
                            + "carrying version BIGINT, with account, card and \"transaction\"")
                    .isEqualTo(Long.valueOf(0L));

            before.setFicoCreditScore("305");
            flushAndClear();

            final Customer after = customerRepository.findById(Long.valueOf(1L)).orElseThrow();

            assertThat(after.getVersion())
                    .as("the increment is applied by the flush, and clearing afterwards is what makes this "
                            + "read a real round trip rather than an identity-map hit. The credit score is "
                            + "mutated rather than any other column precisely because it is not personal "
                            + "data, so the change can be named in a failure message")
                    .isEqualTo(Long.valueOf(1L));
            assertThat(after.getFicoCreditScore())
                    .as("and the update itself reached the row")
                    .isEqualTo("305");
        }

        @Test
        @DisplayName("a stale-version write fails with an optimistic-locking failure that preserves its cause")
        void aStaleVersionWriteFails() {
            final Customer stale = customerRepository.findById(Long.valueOf(1L)).orElseThrow();
            flushAndClear();

            assertThat(stale.getVersion())
                    .as("clearing the persistence context detaches this instance while it still holds the "
                            + "version it was loaded with")
                    .isEqualTo(Long.valueOf(0L));

            final Customer current = customerRepository.findById(Long.valueOf(1L)).orElseThrow();
            current.setFicoCreditScore("306");
            flushAndClear();

            stale.setFicoCreditScore("307");
            final Throwable thrown = catchThrowable(() -> customerRepository.save(stale));

            assertThat(thrown)
                    .as("the row is now at version 1 and the detached instance still claims 0, so the write "
                            + "is refused. No second thread and no second connection is used to produce "
                            + "this: a detached instance and a committed increment in the same transaction "
                            + "are deterministic, where a race would not be")
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(messageContains(thrown, "Customer"))
                    .as("the message identifies the entity and the row by identifier only - it carries no "
                            + "column values at all, which is why provoking this one against seeded row 1 "
                            + "is safe")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the provider-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(rootCauseOf(thrown))
                    .as("the root cause is the provider's stale-state report, translated by the repository "
                            + "proxy into Spring's data-access hierarchy. THE VERSION COLUMN IS ONLY HALF "
                            + "THE CONTRACT: it detects that a row moved, whereas "
                            + "9700-CHECK-CHANGE-IN-REC at app/cbl/COACTUPC.cbl:L4109-L4193 detects which "
                            + "field values differ from what the operator was shown, with the case "
                            + "asymmetry at :L4139-L4140. That second layer lives in "
                            + "AccountUpdateService and is asserted by the unit tier, not here")
                    .isInstanceOf(StaleObjectStateException.class);
        }

        @Test
        @DisplayName("the pessimistic read for update returns a managed instance, proved by flushing through it")
        void theReadForUpdateReturnsAManagedInstance() {
            final Optional<Customer> locked = customerRepository.findByIdForUpdate(Long.valueOf(1L));

            assertThat(locked)
                    .as("findByIdForUpdate is the Java form of the EXEC CICS READ ... UPDATE at "
                            + "app/cbl/COACTUPC.cbl:L3920-L3932, whose failure exit at :L3936-L3942 sets "
                            + "COULD-NOT-LOCK-CUST-FOR-UPDATE")
                    .isPresent();

            locked.orElseThrow().setFicoCreditScore("308");
            flushAndClear();

            final Customer reloaded = customerRepository.findById(Long.valueOf(1L)).orElseThrow();

            assertThat(reloaded.getFicoCreditScore())
                    .as("only a MANAGED instance has its mutation written by a flush, so the change "
                            + "surviving a clear and a re-read is what proves the locked row is attached to "
                            + "the persistence context of the enclosing transaction - which is what makes "
                            + "the service's field-by-field comparison possible without a lost-update "
                            + "window between the read and the write")
                    .isEqualTo("308");
            assertThat(reloaded.getVersion())
                    .as("and the optimistic counter moved with it, so the two layers compose")
                    .isEqualTo(Long.valueOf(1L));
        }
    }

    @Nested
    @DisplayName("primary-key uniqueness, which is legitimate here where alternate-index uniqueness is not")
    class PrimaryKeyUniqueness {

        @Test
        @DisplayName("a second row bearing an existing identifier is refused by pk_customer")
        void aSecondRowBearingAnExistingIdentifierIsRefused() {
            final String constraint = "pk_customer";

            final Throwable thrown = catchThrowable(() -> {
                customerRepository.save(
                        syntheticCustomer(1L, "000000000", "Y", "NC", "274", "(000)000-0000"));
                flushAndClear();
            });

            assertThat(thrown)
                    .as("a newly constructed entity has a null version, so Spring Data treats it as new "
                            + "and issues a real INSERT rather than a merge - which is what makes the "
                            + "duplicate reachable at all. Asserting uniqueness is legitimate for this key "
                            + "and only for this key: app/catlg/LISTCAT.txt:634 shows the base cluster as "
                            + "UNIQUE INDEXED with no NONUNIQKEY, whereas the three alternate indexes V2 "
                            + "creates are non-unique and no test may claim otherwise")
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(messageContains(thrown, constraint))
                    .as("the thrown message names the primary key declared by V1 over cust_id NUMERIC(9), "
                            + "the nine-byte VSAM key at RKP 0")
                    .isTrue();
            assertThat(thrown.getCause())
                    .as("the driver-level cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(messageContains(rootCauseOf(thrown), constraint))
                    .as("and the preserved root cause names the same constraint")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("corroboration against the frozen fixture, reading only its non-sensitive columns")
    class FrozenFixtureCorroboration {

        @Test
        @DisplayName("the fixture carries fifty records of exactly five hundred characters each")
        void theFixtureCarriesFiftyFiveHundredCharacterRecords() {
            final List<String> fixture = readFixture("custdata.txt");

            assertThat(fixture.size())
                    .as("read by bare classpath-root name through the harness's strict loader. Asserted on "
                            + "size rather than on the list, because a record of this fixture is personal "
                            + "data and a failing collection assertion would print all fifty of them")
                    .isEqualTo(seededRowCount);

            final long fullWidth = fixture.stream()
                    .filter(record -> record.length() == recordWidth)
                    .count();

            assertThat(fullWidth)
                    .as("every record is the catalogued 500 characters. A trailing-whitespace cleanup would "
                            + "collapse each one to 332 by removing the FILLER PIC X(168) at "
                            + "app/cpy/CVCUS01Y.cpy:L23 - the longest trailing-space run any record carries "
                            + "- and every position-based read after the first short row would then be "
                            + "wrong. The root .editorconfig carves its trimming out for *.md only, so this "
                            + "file is protected by nothing but discipline: never copy, trim, normalise or "
                            + "edit it")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("the fixture key prefixes are exactly the fifty identifiers the table carries")
        void theFixtureKeyPrefixesEqualTheSeededIdentifiers() {
            final List<String> fromFixture = readFixture("custdata.txt").stream()
                    .map(record -> record.substring(0, keyWidth))
                    .toList();

            final List<String> fromTable = allSeededCustomersInKeyOrder().stream()
                    .map(customer -> String.format(Locale.ROOT, "%09d", customer.getCustomerId()))
                    .toList();

            assertThat(fromTable)
                    .as("columns 1-9 of each record against cust_id rendered back to nine display digits. "
                            + "Locale.ROOT is explicit so that no host locale can change the rendering, and "
                            + "the table side is read in ascending key order because the fixture is in that "
                            + "order and a bare findAll guarantees none. Identifiers are the one column of "
                            + "this table that may safely appear in a failure message")
                    .isEqualTo(fromFixture);
        }

        @Test
        @DisplayName("the fixture credit-score census agrees with the table: minimum 001, maximum 793, 21 below 300")
        void theFixtureCreditScoreCensusAgreesWithTheTable() {
            final List<String> fromFixture = readFixture("custdata.txt").stream()
                    .map(record -> record.substring(329, 332))
                    .toList();

            final List<String> fromTable = allSeededCustomersInKeyOrder().stream()
                    .map(Customer::getFicoCreditScore)
                    .toList();

            assertThat(fromTable)
                    .as("columns 330-332 of each record against cust_fico_credit_score, row for row. Equal "
                            + "only because the column is CHAR(3) text: a numeric mapping would have "
                            + "rendered the seven leading-zero values differently")
                    .isEqualTo(fromFixture);
            assertThat(fromFixture.stream().min(Comparator.naturalOrder()).orElseThrow())
                    .as("zero-padded three-character scores sort lexicographically exactly as they sort "
                            + "numerically, so the minimum is provable without parsing")
                    .isEqualTo("001");
            assertThat(fromFixture.stream().max(Comparator.naturalOrder()).orElseThrow())
                    .as("and the maximum, which is well inside the 300-850 screen range while the minimum "
                            + "is far outside it")
                    .isEqualTo("793");
            assertThat(fromFixture.stream().filter(score -> Integer.parseInt(score) < 300).count())
                    .as("the fixture side of the census the table side already proved, so the absence of a "
                            + "range constraint is grounded in the frozen bytes and not only in the loaded "
                            + "rows")
                    .isEqualTo(scoresBelowThreeHundred);
        }

        @Test
        @DisplayName("the fixture state-code column carries the four codes the lookup table omits")
        void theFixtureStateCodeColumnCarriesTheFourUnrecognisedCodes() {
            final List<String> unrecognised = List.of("AP", "FM", "MH", "PW");

            final List<String> fromFixture = readFixture("custdata.txt").stream()
                    .map(record -> record.substring(234, 236))
                    .toList();

            final List<String> fromTable = allSeededCustomersInKeyOrder().stream()
                    .map(Customer::getAddressStateCode)
                    .toList();

            assertThat(fromTable)
                    .as("columns 235-236 of each record against cust_addr_state_cd, row for row. State "
                            + "codes are not personal data, so they may be compared directly")
                    .isEqualTo(fromFixture);
            assertThat(fromFixture)
                    .as("all four codes that app/cpy/CSLKPCDY.cpy:L1013 omits are actually present in the "
                            + "frozen data, so a membership constraint would have made the seed fail")
                    .contains("AP", "FM", "MH", "PW");
            assertThat(fromFixture.stream().filter(unrecognised::contains).count())
                    .as("on exactly five of the fifty rows, matching the census the table side proved")
                    .isEqualTo(rowsWithUnrecognisedStateCode);
        }
    }
}
