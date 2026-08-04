/*
 * ******************************************************************
 * Program     : TransactionTypeRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier
 * Function    : Proves the relational replacement of the VSAM KSDS
 *               cluster AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS against a
 *               real PostgreSQL 16 engine. Asserts the seven seeded
 *               reference rows, the two character primary key and its
 *               uniqueness, the fifty character blank padded
 *               description, the two character width contract at both
 *               the entity and the column boundary, and the single
 *               naming divergence of the whole copybook corpus, by
 *               which this cluster's key field is TRAN-TYPE and not
 *               TRAN-TYPE-CD. Batch only reference data: the cluster
 *               carries no CICS file definition, so no online screen
 *               program ever opens it.
 * Source      : app/cpy/CVTRA03Y.cpy:L5 (TRAN-TYPE PIC X(02), RECLN 60),
 *               app/catlg/LISTCAT.txt:3779 (KEYLEN 2, AVGLRECL 60),
 *               app/jcl/TRANTYPE.jcl:L40-L41 (KEYS(2 0), RECORDSIZE(60 60)),
 *               app/cbl/CBTRN03C.cbl:L189, L495 (the sole consumer),
 *               app/data/ASCII/trantype.txt (427 B, 7 rows x 60),
 *               app/csd/CARDDEMO.CSD (zero TRANTYPE entries),
 *               app/cbl/CBACT04C.cbl:L1-L21 (banner convention),
 *               CONTRIBUTING.md:L33-L34, NOTICE, LICENSE @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cardemo.model.entity.TransactionType;
import com.cardemo.repository.TransactionTypeRepository;

/**
 * {@link TransactionTypeRepository} against a real PostgreSQL 16 engine: the seven seeded rows, the
 * two character key, the fifty character description and the corpus's one naming divergence.
 *
 * <h2>What it does</h2>
 *
 * <p>This class owns the {@code transaction_type} table in the repository integration tier. That table
 * is the relational replacement for the VSAM KSDS cluster {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS},
 * and it is the <strong>root of the schema's foreign-key graph</strong>: two foreign keys point at it,
 * from {@code "transaction".tran_type_cd} and from {@code transaction_category.tran_type_cd}, and it
 * points at nothing. Those two inbound keys are asserted by the classes that own <em>those</em> tables,
 * never here, because an entity is the wrong place to assert a neighbour's constraint.
 *
 * <p>Every figure below was read from the frozen corpus at {@code 7756d89} rather than inherited from
 * prose, and each carries the locator that proves it:
 *
 * <ul>
 *   <li><strong>{@code app/cpy/CVTRA03Y.cpy}</strong> is the record layout. Its header comment at
 *       {@code :L2} reads "Data-structure for transaction type (RECLN = 60)"; the group item at
 *       {@code :L4} is {@code 01 TRAN-TYPE-RECORD}; and the three elementary items are
 *       {@code TRAN-TYPE PIC X(02)} at {@code :L5}, {@code TRAN-TYPE-DESC PIC X(50)} at {@code :L6}
 *       and {@code FILLER PIC X(08)} at {@code :L7}. Bytes 1-2, 3-52 and 53-60 of a 60 byte record.</li>
 *   <li><strong>{@code app/catlg/LISTCAT.txt}</strong> corroborates the geometry physically. The
 *       cluster entry header is at {@code :L3742}, and the DATA-component attribute line at
 *       {@code :L3779} reports {@code KEYLEN 2} with {@code AVGLRECL 60}; the line below it,
 *       {@code :L3780}, adds {@code RKP 0} and {@code MAXLRECL 60}, so the key is the record prefix
 *       and the length is exact rather than merely averaged. The statistics block reports
 *       {@code REC-TOTAL 7}. <em>The line number matters.</em> {@code :L1475} also reports an
 *       {@code AVGLRECL 60} but with {@code KEYLEN 6}, because it describes {@code TRANCATG}; citing
 *       it here would point at the wrong dataset.</li>
 *   <li><strong>{@code app/jcl/TRANTYPE.jcl}</strong> states the same two facts a third time, as
 *       exact values: {@code DEFINE CLUSTER} at {@code :L36} with {@code KEYS(2 0)} at {@code :L40}
 *       and {@code RECORDSIZE(60 60)} at {@code :L41}.</li>
 *   <li><strong>{@code app/cbl/CBTRN03C.cbl} is the only program in the 28 program corpus that opens
 *       this dataset</strong>, which is why its access pattern is the only one worth reproducing. It
 *       moves the code off a transaction record at {@code :L189}, performs
 *       {@code 1500-B-LOOKUP-TRANTYPE} at {@code :L190}, and that paragraph at {@code :L494} issues a
 *       single keyed {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at {@code :L495}. An unmatched
 *       code is <em>diagnosed</em> at {@code :L497} rather than fatal, so an empty lookup is a data
 *       condition and not a mapping defect. The negative evidence is equally decisive:
 *       {@code app/cbl/CBTRN02C.cbl} performs exactly three keyed reads - the cross reference at
 *       {@code :L383}, the account at {@code :L395} and the category balance at {@code :L474} - and
 *       never reads this dataset, which is why it has no place in the posting path.</li>
 *   <li><strong>{@code app/csd/CARDDEMO.CSD} contains exactly eight {@code DEFINE FILE} entries</strong>
 *       - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT},
 *       {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - and {@code TRANTYPE} occurs
 *       <strong>zero</strong> times. That absence is the evidence that this is a batch-only dataset,
 *       so this class asserts no REST endpoint, no controller and no service surface for it: there is
 *       none to assert.</li>
 *   </ul>
 *
 * <h2>High: the key field is {@code TRAN-TYPE}, not {@code TRAN-TYPE-CD}</h2>
 *
 * <p>{@code app/cpy/CVTRA03Y.cpy:L5} is the <strong>unique exception in the entire copybook
 * corpus</strong>. Every sibling layout spells the same two character value with the {@code -CD}
 * suffix - {@code app/cpy/CVTRA04Y.cpy:L6} and {@code app/cpy/CVTRA05Y.cpy:L6} declare
 * {@code TRAN-TYPE-CD}, {@code app/cpy/CVTRA02Y.cpy:L7} the same, and
 * {@code app/cpy/CVTRA01Y.cpy:L7} the prefixed {@code TRANCAT-TYPE-CD} - so {@code tran_type_cd} is
 * the natural guess and it is wrong. The column is {@code tran_type}.
 *
 * <p>The severity is <strong>High</strong> rather than cosmetic because of how it fails.
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, so a wrong column name
 * does not produce a failing assertion in one test: it aborts the application context refresh, and
 * every test in the tier then errors on a message about a missing column. The remedy is always
 * upstream - correct {@code V1__create_schema.sql} or the entity mapping - and never to rename an
 * assertion to match a wrong schema. {@link #theKeyColumnIsTranTypeAndNoTranTypeCdColumnExists()}
 * asserts the divergence explicitly rather than leaving it implied by a context that happened to
 * start, and its failure message carries the locator.
 *
 * <h2>The blank-pad policy this class applies, stated once</h2>
 *
 * <p>{@code tran_type_desc} is {@code CHAR(50)}, and the two engines disagree about trailing blanks.
 * PostgreSQL ignores them when comparing, so {@code tran_type_desc = 'Purchase'} matches; the JDBC
 * driver does not, and returns a {@code bpchar} blank padded to its declared width, so in Java a
 * loaded description is a 50 character string and is not equal to {@code "Purchase"}.
 *
 * <p><strong>This class asserts against the full 50 character blank padded form, and never trims.</strong>
 * The choice is deliberate and is the stronger of the two the assignment permits: it asserts the text
 * <em>and</em> the fixed-width geometry in one comparison, and the padded value is the faithful
 * {@code PIC X(50)} image that the fixed-width writers re-emit. Trimming would assert the text while
 * silently discarding the geometry, which is the half of the contract a byte-level parity comparison
 * actually depends on. The expected values are therefore built by
 * {@link #blankPaddedToDescriptionWidth(String)} from the unpadded literal, so the padding is visible
 * in the code rather than pasted into it as 42 invisible spaces.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone, which is the
 * fastest check that the file still satisfies the zero-warning compiler settings, with
 * {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe, not Surefire, and the binding is purely by path.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java}
 * and runs at {@code integration-test} and {@code verify} even though this class keeps the {@code Test}
 * suffix; {@code maven-surefire-plugin} 3.5.4 includes {@code **}{@code /*Test.java} but
 * <strong>explicitly excludes</strong> {@code **}{@code /integration/}{@code **}. Moving this class up
 * to the {@code integration} package, to {@code com.cardemo}, or into {@code src/test/java} directly
 * would match <em>neither</em> include set, so it would be collected by neither plugin and would
 * simply never run - a green build, both plugins reporting success, and no error and no output to
 * notice. Do not rename or relocate it.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a real
 * PostgreSQL 16 container plus the Testcontainers reaper, so this tier cannot run without a container
 * runtime, and where one is absent the correct response is to report the blocker rather than to assert
 * an untested pass. At the time of writing the runtime is present and the host toolchain is too -
 * Docker Engine 29.7.0 on {@code /var/run/docker.sock}, JDK 25.0.3 and Maven 3.9.11 - so
 * {@code ./mvnw} runs directly. Where a host JDK is not provisioned the identical build runs in the
 * pinned image, and produces the same result because every plugin and every non-managed dependency is
 * pinned to an exact version:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration and declares none. It performs no environment-variable read, no
 * system-property read and no system-property write, and it contains no host, port, database name,
 * user name, password, JDBC URL or service endpoint of any kind. Everything it needs is inherited from
 * {@link AbstractRepositoryIntegrationTest}, which is the tier's <em>only</em> declaration of the
 * container, the Spring context and the connection properties:
 *
 * <ul>
 *   <li>Profile {@code test}; PostgreSQL 16.14 pinned <em>by digest</em> rather than by tag, on Debian
 *       rather than Alpine so that glibc text collation - which the ordered reads depend on - matches
 *       what ships;</li>
 *   <li>the connection injected by {@code @ServiceConnection}, which is why no datasource literal
 *       appears anywhere in this package;</li>
 *   <li>{@code ddl-auto: validate}, {@code open-in-view: false}, {@code show-sql: false}, Hibernate's
 *       JDBC time zone {@code UTC};</li>
 *   <li>exactly three Flyway migrations with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and
 *       {@code baseline-on-migrate: false}. The {@code BATCH_*} tables come from
 *       {@code spring.batch.jdbc.initialize-schema} and are never a fourth migration;</li>
 *   <li>{@code spring.batch.job.enabled: false}, so starting this context launches no batch job;</li>
 *   <li>the injected fixed UTC clock, from {@code fixedClock()}. Nothing in this file reads the
 *       ambient clock, and nothing in it needs a clock at all: reference data carries no timestamp.
 *       Every assertion here is over seven fixed rows, so there is no wall-clock, locale, time-zone,
 *       random or iteration-order input to any outcome.</li>
 *   </ul>
 *
 * <p>Class-level isolation is transactional rollback, inherited from the harness, so the rows this
 * class writes in {@link #aStoredDescriptionIsBlankPaddedToFiftyCharactersOnReadBack()} and the two
 * constraint tests never become visible to a sibling and the seeded count stays exactly seven for
 * everyone. This class declares no container, no {@code @SpringBootTest}, no
 * {@code @DynamicPropertySource} and <strong>no static field of any kind</strong>: the harness
 * documents its single static container as the one deliberate exception for the whole hierarchy, and
 * adding a second static field to a subclass is what reintroduces the shared mutable state that
 * exception was carefully scoped to avoid.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test here errors before any assertion runs, with a container or Docker message.</em>
 *       No reachable Docker socket. Start one; do not weaken the tier to run without it.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or a 1.x version is resolved.</em> The
 *       <strong>Blocker</strong>-severity trap of the migration, whose remedy has two halves that are
 *       both required. Half one: the version is pinned to exactly {@code 2.0.3} by
 *       <em>overriding the version property the Spring Boot parent manages</em>, never by importing a
 *       second bill of materials, because two competing imports resolve in an ordering-dependent way
 *       that can silently select the parent-managed 1.x line. Half two: only the <em>prefixed</em>
 *       module coordinates exist on 2.x - {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter} - because the bare 1.x ids {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} are not published there. Overriding without
 *       renaming resolves artefacts that do not exist; renaming without overriding resolves the wrong
 *       version. Both halves are already in place in the root {@code pom.xml}, which is root-owned and
 *       must not be edited from here; if either is ever undone, report it as a Blocker with this
 *       two-part remediation rather than patching a test.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that reaches test
 *       compilation, so a single unused import, a raw type or a deprecation is a build failure rather
 *       than console noise. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails naming a missing column {@code tran_type}.</em> Almost always the
 *       {@code TRAN-TYPE-CD} harmonisation described above. Fix the migration or the mapping; the
 *       copybook is authoritative.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a name.</em> {@code validate}
 *       compares type codes, so a {@code Long} over {@code NUMERIC(11)} can fail where {@code BIGINT}
 *       passes. <strong>The fix is upstream</strong>, in {@code V1__create_schema.sql} or the entity.
 *       Never widen a column to silence it and never patch the test. Severity
 *       <strong>Medium</strong>.</li>
 *   <li><em>A description comparison fails on what looks like identical text.</em> The loaded value is
 *       blank padded to 50 characters by design. Compare against the padded form, as this class does,
 *       or push the comparison into SQL where trailing blanks are ignored.</li>
 *   <li><em>A fixture read fails with a message naming the resource.</em> The fixtures are flat direct
 *       children of {@code src/test/resources} and are addressed by bare name, so a misspelling is a
 *       compile-clean mistake that fails only when opened. The harness's reader therefore fails
 *       immediately and names the resource instead of returning an empty list, which is what keeps the
 *       canonical {@code dalytran.txt}-for-{@code dailytran.txt} typo from surfacing as a confusing
 *       {@code NullPointerException} far from its cause.</li>
 *   </ul>
 *
 * <h2>Deliberately not asserted here, each with its reason</h2>
 *
 * <ul>
 *   <li><strong>No enum mapping.</strong> {@link TransactionType} is a plain entity with two mapped
 *       properties, no {@code @Enumerated} and no {@code @Convert}. It has no relationship whatever to
 *       {@code com.cardemo.model.enums.TransactionSource}, which models {@code TRAN-SOURCE PIC X(10)}
 *       - the {@code 'System'} literal at {@code app/cbl/CBACT04C.cbl:L484} plus the online sources -
 *       and is a different concept entirely. Conflating the two would be a Blocker.</li>
 *   <li><strong>No optimistic-locking assertion.</strong> {@code transaction_type} carries no
 *       {@code version} column; {@code @Version} is on exactly four entities, {@code Account},
 *       {@code Card}, {@code Customer} and {@code Transaction}. This is reference data loaded once, so
 *       there is no concurrent update to lose.</li>
 *   <li><strong>No index assertion.</strong> {@code V2__create_indexes.sql} creates exactly three
 *       non-unique B-tree indexes, one per catalogued alternate index, on {@code card(card_acct_id)},
 *       {@code card_cross_reference(xref_acct_id)} and {@code "transaction"(tran_proc_ts)}. None is on
 *       this table and there is no fourth. Nor does this class assert that any of the three is unique,
 *       because a legacy alternate key is not.</li>
 *   <li><strong>No sixth check constraint.</strong> {@code V1} creates exactly five, on
 *       {@code acct_active_status}, {@code card_active_status}, {@code cust_pri_card_holder_ind},
 *       {@code sec_usr_type} and the nine-digit {@code cust_ssn}. None constrains this table's
 *       description domain, and inventing one would assert a rule the source does not have.</li>
 *   <li><strong>No processor or service behaviour.</strong> One concern per class: this tier asserts
 *       persistence, mapping and constraint behaviour. Entity, key and enum unit tests belong to
 *       {@code com.cardemo.unit.model} and processor tests to {@code com.cardemo.unit.batch}.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are recorded as <strong>Not available</strong> rather than filled with an invention,
 * because a fabricated oracle is worse than an acknowledged gap.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured
 *       legacy output exists anywhere in this repository: searches across the expected, baseline,
 *       golden, {@code .out} and system-output name patterns, and across the reject, report, statement
 *       and HTML dataset names, return only dataset <em>definition</em> members such as
 *       {@code app/jcl/DALYREJS.jcl} and {@code app/jcl/TRANREPT.jcl}, and no captured data at all.
 *       What is needed to close it is a captured 430-byte {@code DALYREJS} reject dataset from a real
 *       {@code POSTTRAN} run at a known input state, together with the resulting {@code TRANSACT},
 *       {@code ACCTDATA} and {@code TCATBALF} images. Until that exists: no baseline file is created
 *       here, no expected bytes are fabricated, and a baseline generated by running this
 *       implementation against its own output is circular and forbidden. Hand-simulating the posting
 *       program is equally inadmissible and demonstrably so, since two defensible models of it over
 *       these same fixtures disagree - 13 rejects against 38 - and a figure that moves with the model
 *       is not an oracle. This class sidesteps the gap honestly: {@code app/data/ASCII/trantype.txt} is
 *       used as <em>corroboration</em> of the seed, which is a frozen input it genuinely is, and never
 *       as an expected-output baseline, which it is not.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong>
 *       Across the 28 programs of {@code app/cbl} the literal file status {@code '35'} occurs
 *       <strong>0</strong> times and {@code DFHRESP(NOTOPEN)} <strong>0</strong> times; the responses
 *       the corpus actually tests are {@code NORMAL}, {@code NOTFND}, {@code ENDFILE},
 *       {@code DUPREC} and {@code DUPKEY}. The condition is specification-derived only, so no test for
 *       it is invented here and no parity claim is made for it.</p></li>
 *   </ol>
 *
 * <p>One further divergence is disclosed rather than absorbed: the coverage plugin is pinned at
 * {@code 0.8.12} while a prior record cites {@code 0.8.14}. <strong>The pinned version governs</strong>,
 * the drift is <strong>Medium</strong>, and the remedy is to record it rather than to advance the pin
 * unilaterally from a test.
 *
 * @see TransactionType
 * @see TransactionTypeRepository
 * @see AbstractRepositoryIntegrationTest
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
@DisplayName("TransactionTypeRepository against PostgreSQL 16: TRAN-TYPE, the seven seeded rows and the key contract")
final class TransactionTypeRepositoryTest extends AbstractRepositoryIntegrationTest {

    /**
     * Width of {@code TRAN-TYPE PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy:L5}, carried through as the
     * {@code CHAR(2)} key column and corroborated by {@code KEYLEN 2} on the DATA-component attribute
     * line at {@code app/catlg/LISTCAT.txt:L3779} and by {@code KEYS(2 0)} at
     * {@code app/jcl/TRANTYPE.jcl:L40}.
     *
     * <p>An instance field rather than a {@code static} one, deliberately: the harness documents its
     * single static container as the one exception for the whole hierarchy and forbids any further
     * static field in a subclass. A {@code final} instance field costs nothing here and keeps that
     * invariant checkable by inspection.
     */
    private final int typeCodeWidth = 2;

    /**
     * Width of {@code TRAN-TYPE-DESC PIC X(50)} at {@code app/cpy/CVTRA03Y.cpy:L6}, carried through as
     * the {@code CHAR(50)} description column, which is what blank pads every loaded value to exactly
     * this length.
     */
    private final int typeDescriptionWidth = 50;

    /**
     * Total width of the {@code TRAN-TYPE-RECORD} layout: {@code 2 + 50 + 8 = 60}. Stated by the
     * copybook header comment at {@code app/cpy/CVTRA03Y.cpy:L2}, by {@code AVGLRECL 60} and
     * {@code MAXLRECL 60} at {@code app/catlg/LISTCAT.txt:L3779-L3780}, and exactly by
     * {@code RECORDSIZE(60 60)} at {@code app/jcl/TRANTYPE.jcl:L41}.
     */
    private final int recordWidth = 60;

    /**
     * The seeded row count: {@code V3__seed_data.sql} loads exactly seven rows, which is the
     * {@code REC-TOTAL 7} the catalogue reports for this cluster and the seven records that
     * {@code app/data/ASCII/trantype.txt} carries in its 427 bytes.
     */
    private final long seededRowCount = 7L;

    /** The repository under test, injected by constructor so the field can be {@code final}. */
    private final TransactionTypeRepository repository;

    /**
     * Used only for the three statements that have to reach past the object mapping: the
     * {@code information_schema} column-name proof, and the two writes that provoke a real constraint.
     * Every statement it issues binds its parameters, and none concatenates a value into SQL.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Sole constructor, with both collaborators injected by Spring's test context.
     *
     * <p>Constructor injection rather than field injection, so that both collaborators are
     * {@code final} and cannot be reassigned by a later edit - which is the strongest available
     * reading of "avoid global mutable state; prefer dependency injection". It resolves through the
     * Spring test framework's parameter resolver because the constructor carries
     * {@link Autowired}; nothing else here needs wiring, since the container, the context, the
     * connection properties, the fixed clock and the fixture reader are all inherited from
     * {@link AbstractRepositoryIntegrationTest}.
     *
     * <p>It assigns fields and calls nothing overridable, so it publishes no partially built instance;
     * the class is {@code final} in any case.
     *
     * @param repository   the repository under test, supplied by Spring Data; never {@code null}
     * @param jdbcTemplate the template used for the column-name proof and the constraint provocations;
     *                     never {@code null}, and bound to the same transaction as the repository
     */
    @Autowired
    TransactionTypeRepositoryTest(final TransactionTypeRepository repository,
            final JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The seeded cardinality, which is the single strongest guard that the seed applied in full and
     * that no sibling leaked a row into the table.
     */
    @Test
    @DisplayName("the seed loaded exactly seven rows, the REC-TOTAL the catalogue reports")
    void seededCardinalityIsExactlySeven() {
        assertThat(repository.count())
                .as("V3__seed_data.sql loads app/data/ASCII/trantype.txt, 427 bytes of 7 records of "
                        + "60 characters, and app/catlg/LISTCAT.txt reports REC-TOTAL 7 for this "
                        + "cluster; neither more nor fewer rows is admissible")
                .isEqualTo(seededRowCount);
    }

    /**
     * A keyed read of every one of the seven codes, which is the exact access pattern
     * {@code app/cbl/CBTRN03C.cbl:L495} uses, asserted against the full blank padded description.
     */
    @Test
    @DisplayName("all seven codes round-trip by key with their exact CHAR(50) blank-padded descriptions")
    void everySeededKeyRoundTripsWithItsExactBlankPaddedDescription() {
        final Map<String, String> expected = expectedTypeDescriptionsInKeyOrder();

        assertThat(expected.keySet())
                .as("the expectation itself must carry all seven codes, or a missing row would pass "
                        + "unnoticed")
                .hasSize(Math.toIntExact(seededRowCount));

        for (final Map.Entry<String, String> row : expected.entrySet()) {
            final Optional<TransactionType> loaded = repository.findById(row.getKey());

            assertThat(loaded)
                    .as("app/data/ASCII/trantype.txt seeds transaction type " + row.getKey())
                    .isPresent();
            assertThat(loaded.get().getTypeCode())
                    .as("every seeded code is exactly two digits, so the CHAR(2) key is fully "
                            + "occupied and is never blank padded")
                    .isEqualTo(row.getKey())
                    .hasSize(typeCodeWidth);
            assertThat(loaded.get().getTypeDescription())
                    .as("TRAN-TYPE-DESC PIC X(50) at app/cpy/CVTRA03Y.cpy:L6 comes back blank padded "
                            + "to its declared width and is deliberately not trimmed, because the "
                            + "padding is the fixed-width geometry rather than noise")
                    .isEqualTo(blankPaddedToDescriptionWidth(row.getValue()))
                    .hasSize(typeDescriptionWidth);
        }
    }

    /**
     * The absent-key boundary case: an unmatched code is a data condition in the source, so it must
     * resolve to an empty result rather than to an exception or to a default row.
     */
    @Test
    @DisplayName("an absent key resolves to an empty Optional rather than throwing")
    void anAbsentKeyResolvesToAnEmptyOptionalRatherThanThrowing() {
        final Optional<TransactionType> loaded = repository.findById("99");

        assertThat(loaded)
                .as("the seed carries only 01 through 07, and app/cbl/CBTRN03C.cbl:L497 diagnoses an "
                        + "unmatched code with INVALID TRANSACTION TYPE rather than abending, so an "
                        + "empty result is the faithful outcome and not an error")
                .isEmpty();
    }

    /** The existence predicate over both sides of the same boundary. */
    @Test
    @DisplayName("existsById separates a seeded key from an absent one")
    void existsByIdDistinguishesASeededKeyFromAnAbsentOne() {
        assertThat(repository.existsById("01"))
                .as("01 is the first record of app/data/ASCII/trantype.txt")
                .isTrue();
        assertThat(repository.existsById("99"))
                .as("99 lies outside the seeded range 01 to 07")
                .isFalse();
    }

    /**
     * The explicit proof of the corpus's one naming divergence.
     *
     * <p>A started context already implies it, because {@code ddl-auto: validate} would have aborted
     * the refresh on a wrong column name, but an implication that lives in a framework setting is not
     * an assertion a reader can find. This states it outright, and states the negative half too: no
     * {@code tran_type_cd} column exists on this table.
     */
    @Test
    @DisplayName("the key column is tran_type and no tran_type_cd column exists")
    void theKeyColumnIsTranTypeAndNoTranTypeCdColumnExists() {
        // The table name is a bound parameter, not a concatenated literal; the only concatenation in
        // the statement joins fixed source lines and interpolates no value.
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "ORDER BY column_name",
                String.class,
                "transaction_type");

        assertThat(columns)
                .as("app/cpy/CVTRA03Y.cpy:L5 declares TRAN-TYPE, not TRAN-TYPE-CD: it is the one "
                        + "layout in the corpus that omits the -CD suffix that CVTRA04Y.cpy:L6, "
                        + "CVTRA05Y.cpy:L6 and CVTRA02Y.cpy:L7 all carry, so the column is tran_type "
                        + "and the underscored form of TRAN-TYPE-CD must be absent")
                .contains("tran_type")
                .doesNotContain("tran_type_cd")
                .containsExactly("tran_type", "tran_type_desc");
    }

    /**
     * The ordered full read, taken through the inherited {@code findAll(Sort)}.
     *
     * <p>{@link TransactionTypeRepository} declares no method of its own and in particular no
     * {@code findAllByOrderByTypeCodeAsc()}; its own documentation records that omission deliberately
     * and directs an ordered read through the inherited overload instead. This test therefore invents
     * nothing: it exercises the surface the interface actually offers.
     */
    @Test
    @DisplayName("the inherited ordered read returns the seven codes in ascending key order")
    void theOrderedReadReturnsTheSevenCodesInAscendingKeyOrder() {
        final List<String> codes = new ArrayList<>();

        for (final TransactionType type : repository.findAll(Sort.by(Sort.Direction.ASC, "typeCode"))) {
            codes.add(type.getTypeCode());
        }

        assertThat(codes)
                .as("RKP 0 at app/catlg/LISTCAT.txt:L3780 puts the two-byte key at the record prefix, "
                        + "so ascending key order is the legacy browse order the batch reader saw")
                .containsExactlyElementsOf(expectedTypeDescriptionsInKeyOrder().keySet());
    }

    /**
     * The primary key is genuinely unique, and a second row bearing an existing code is refused.
     *
     * <p>This is legitimate here and is <em>not</em> the alternate-index case. The catalogue shows this
     * base cluster at {@code app/catlg/LISTCAT.txt:L3779-L3781} with {@code UNIQUE} and with
     * <strong>no {@code NONUNIQKEY} line</strong>; that keyword appears only three times in the whole
     * listing, once for each of the three alternate indexes, none of which belongs to this cluster. So
     * uniqueness may be asserted for this key, while it may never be asserted for any of those three.
     */
    @Test
    @DisplayName("a duplicate primary key is refused by the pk_transaction_type constraint")
    void aDuplicatePrimaryKeyIsRejectedWithAConstraintViolation() {
        // Issued through JdbcTemplate rather than through the repository, and the reason is a measured
        // one rather than a preference: SimpleJpaRepository.save() decides newness from the identifier,
        // and for a String key with no version property "not null" means "not new", so save() calls
        // EntityManager.merge(). Merge would load the existing 01 row and UPDATE it, provoking no
        // violation whatever and leaving this test silently green while asserting nothing. A
        // parameterised INSERT reaches the constraint directly, and JDBC reports it at once, so no
        // flush is needed. The violation is the last statement issued in this method on purpose:
        // PostgreSQL aborts the surrounding transaction once a constraint fires, so any further
        // statement here would fail for an unrelated reason and confuse the diagnosis.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transaction_type (tran_type, tran_type_desc) VALUES (?, ?)",
                "01",
                blankPaddedToDescriptionWidth("Duplicate of the first seeded code")))
                .as("V1__create_schema.sql names the constraint pk_transaction_type over tran_type, "
                        + "which is the KEYLEN 2 the DATA-component attribute line at "
                        + "app/catlg/LISTCAT.txt:L3779 reports")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_transaction_type")
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint");
    }

    /**
     * The two character width contract as the entity enforces it.
     *
     * <p>The entity carries no bean-validation annotation at all - deliberately, since both columns
     * must accept a blank but non-null value - and guards the picture-clause width itself instead. So
     * the rejection asserted here is that guard, naming the COBOL field it derives from, and not a
     * framework constraint.
     */
    @Test
    @DisplayName("the two-character width contract is enforced at the entity boundary")
    void theTwoCharacterWidthContractIsEnforcedAtTheEntityBoundary() {
        final String overLongCode = "999";

        assertThat(overLongCode.length())
                .as("the probe has to exceed TRAN-TYPE PIC X(02) at app/cpy/CVTRA03Y.cpy:L5 to be a "
                        + "boundary case at all")
                .isGreaterThan(typeCodeWidth);

        assertThatThrownBy(() -> new TransactionType(overLongCode,
                blankPaddedToDescriptionWidth("Over-long code")))
                .as("the entity rejects a value wider than its picture clause and names that clause "
                        + "when it does, so the failure is diagnosable without reading the schema")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRAN-TYPE PIC X(02)")
                .hasMessageContaining("must be at most 2 characters but was 3")
                .hasNoCause();
    }

    /**
     * The same width contract as the {@code CHAR(2)} column itself enforces it, which is the database
     * boundary the entity guard above would otherwise hide.
     */
    @Test
    @DisplayName("the CHAR(2) column itself refuses a three-character code")
    void theTwoCharacterWidthContractIsEnforcedByTheCharColumn() {
        // Reached through a parameterised INSERT precisely because the entity guard intercepts an
        // over-long code before it can leave the object layer: without this statement the column's own
        // declared width would never actually be exercised, and a schema that had drifted to CHAR(3)
        // would still pass every other test in this class. All three characters are non-blank on
        // purpose, because PostgreSQL truncates trailing blanks for CHAR(n) rather than refusing them
        // and would otherwise accept the row. As above, this is the method's last statement.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transaction_type (tran_type, tran_type_desc) VALUES (?, ?)",
                "999",
                blankPaddedToDescriptionWidth("Over-long code")))
                .as("V1__create_schema.sql declares tran_type CHAR(2) from TRAN-TYPE PIC X(02) at "
                        + "app/cpy/CVTRA03Y.cpy:L5, corroborated by KEYS(2 0) at "
                        + "app/jcl/TRANTYPE.jcl:L40 and KEYLEN 2 at app/catlg/LISTCAT.txt:L3779")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .hasMessageContaining("value too long for type character(2)");
    }

    /**
     * The blank padding asserted directly, on a value this test wrote rather than one the seed
     * supplied, so that the padding is demonstrably the column's doing.
     */
    @Test
    @DisplayName("a written description comes back blank-padded to fifty characters")
    void aStoredDescriptionIsBlankPaddedToFiftyCharactersOnReadBack() {
        final String provisionalCode = "99";
        final String unpaddedDescription = "Provisional";

        repository.save(new TransactionType(provisionalCode, unpaddedDescription));

        // Forces the INSERT to the database and detaches every managed entity, so the read below is a
        // genuine round trip through the CHAR(50) column instead of an identity-map hit that would
        // hand back the very unpadded string this test just supplied - which would make the assertion
        // vacuous. Nothing is committed: the surrounding transaction is rolled back when the method
        // ends, so this row never becomes visible to a sibling and the seeded count stays seven.
        flushAndClear();

        final Optional<TransactionType> loaded = repository.findById(provisionalCode);

        assertThat(loaded)
                .as("the row written above is readable inside the same transaction")
                .isPresent();
        assertThat(loaded.get().getTypeDescription())
                .as("the CHAR(50) column pads on read, which is why every description assertion in "
                        + "this class compares against the padded form rather than trimming it away")
                .hasSize(typeDescriptionWidth)
                .isEqualTo(blankPaddedToDescriptionWidth(unpaddedDescription))
                .startsWith(unpaddedDescription);
    }

    /**
     * Corroboration against the frozen fixture, byte for byte.
     *
     * <p>The fixture is used as <em>corroboration of an input</em>, which is what it genuinely is, and
     * never as an expected-output baseline, which it is not and which this repository does not contain
     * for any job. It is read by bare classpath name through the harness's reader, which applies the
     * frozen census, and it is neither copied, trimmed, normalised nor edited: every field here is
     * located by absolute column position, so a trailing space is data.
     */
    @Test
    @DisplayName("the frozen fixture corroborates the seeded rows byte for byte")
    void theFrozenFixtureCorroboratesTheSeededRows() {
        final List<String> fixtureRecords = readFixture("trantype.txt");

        assertThat(fixtureRecords)
                .as("app/data/ASCII/trantype.txt is 427 bytes: 7 records of 60 characters plus one "
                        + "line feed each, matching the REC-TOTAL 7 the catalogue reports")
                .hasSize(Math.toIntExact(seededRowCount));

        final List<String> fixtureCodes = new ArrayList<>();

        for (final String fixtureRecord : fixtureRecords) {
            assertThat(fixtureRecord)
                    .as("RECLN 60 at app/cpy/CVTRA03Y.cpy:L2 and RECORDSIZE(60 60) at "
                            + "app/jcl/TRANTYPE.jcl:L41")
                    .hasSize(recordWidth);

            final String fixtureCode = fixtureRecord.substring(0, typeCodeWidth);
            final String fixtureDescription =
                    fixtureRecord.substring(typeCodeWidth, typeCodeWidth + typeDescriptionWidth);
            final String fixtureFiller =
                    fixtureRecord.substring(typeCodeWidth + typeDescriptionWidth);

            fixtureCodes.add(fixtureCode);

            assertThat(fixtureFiller)
                    .as("FILLER PIC X(08) at app/cpy/CVTRA03Y.cpy:L7 occupies bytes 53 to 60 and is "
                            + "zero filled in this fixture rather than space filled as it is in "
                            + "acctdata.txt, which is why a loader must take a fixed substring and "
                            + "must not trim; the entity deliberately does not map it")
                    .isEqualTo("00000000");

            final Optional<TransactionType> seeded = repository.findById(fixtureCode);

            assertThat(seeded)
                    .as("every record of the frozen fixture reached the table through "
                            + "V3__seed_data.sql")
                    .isPresent();
            assertThat(seeded.get().getTypeDescription())
                    .as("bytes 3 to 52 of the frozen record equal the stored CHAR(50) value exactly, "
                            + "which is what proves the seed is byte faithful to the fixture rather "
                            + "than merely similar to it")
                    .isEqualTo(fixtureDescription);
        }

        final List<String> storedCodes = new ArrayList<>();

        for (final TransactionType type : repository.findAll(Sort.by(Sort.Direction.ASC, "typeCode"))) {
            storedCodes.add(type.getTypeCode());
        }

        assertThat(storedCodes)
                .as("the two-character key prefixes of the fixture are exactly the tran_type values "
                        + "in the table, in the same ascending order, so neither side carries a row "
                        + "the other lacks")
                .containsExactlyElementsOf(fixtureCodes);
    }

    /**
     * The seven seeded rows in ascending key order, as code to <em>unpadded</em> description.
     *
     * <p>Transcribed from {@code app/data/ASCII/trantype.txt}, whose seven records read {@code 01}
     * Purchase, {@code 02} Payment, {@code 03} Credit, {@code 04} Authorization, {@code 05} Refund,
     * {@code 06} Reversal and {@code 07} Adjustment. The descriptions are held unpadded and are padded
     * on use by {@link #blankPaddedToDescriptionWidth(String)}, so that the {@code CHAR(50)} geometry
     * is expressed in code rather than pasted in as invisible trailing spaces that no reviewer could
     * count.
     *
     * <p>A fresh insertion-ordered map is built on every call and none is retained, so no state is
     * shared between test methods. The ordering is load bearing: it is what
     * {@link #theOrderedReadReturnsTheSevenCodesInAscendingKeyOrder()} compares against.
     *
     * @return a new, insertion-ordered map of the seven codes to their unpadded descriptions; never
     *         {@code null} and never empty
     */
    private Map<String, String> expectedTypeDescriptionsInKeyOrder() {
        final Map<String, String> expected = new LinkedHashMap<>();
        expected.put("01", "Purchase");
        expected.put("02", "Payment");
        expected.put("03", "Credit");
        expected.put("04", "Authorization");
        expected.put("05", "Refund");
        expected.put("06", "Reversal");
        expected.put("07", "Adjustment");
        return expected;
    }

    /**
     * Blank pads a description to the {@code CHAR(50)} width, which is the form the column returns.
     *
     * <p>Padding rather than trimming is this class's stated policy: it asserts the text and the
     * fixed-width geometry together, and the padded value is the faithful {@code PIC X(50)} image from
     * {@code app/cpy/CVTRA03Y.cpy:L6}.
     *
     * @param unpadded the description as written in the fixture, without its trailing blanks; must be
     *                 no wider than the declared 50 characters
     * @return {@code unpadded} followed by exactly enough spaces to reach 50 characters, so a value
     *         already 50 wide is returned unchanged
     * @throws IllegalArgumentException if {@code unpadded} is wider than the declared column width,
     *                                  which would mean the expectation itself had drifted from the
     *                                  picture clause; the message names the value and both widths
     */
    private String blankPaddedToDescriptionWidth(final String unpadded) {
        if (unpadded.length() > typeDescriptionWidth) {
            throw new IllegalArgumentException("The value '" + unpadded + "' is " + unpadded.length()
                    + " characters and cannot be padded to the " + typeDescriptionWidth
                    + " character width of TRAN-TYPE-DESC PIC X(50) at app/cpy/CVTRA03Y.cpy:L6");
        }
        return unpadded + " ".repeat(typeDescriptionWidth - unpadded.length());
    }

    /**
     * The complete PostgreSQL metadata contract for the {@code transaction_type} table.
     *
     * <p><strong>Finding, severity High, RESOLVED.</strong> This class asserted whichever columns its
     * behavioural tests happened to touch, and every one of those assertions was true and none of them was a
     * contract. A widened character column, a lost decimal scale, a reordered composite key, a retargeted
     * foreign key or a dropped check constraint would all have left this class green - and Hibernate's
     * {@code ddl-auto: validate} would not have caught any of them either, because it compares type
     * <em>compatibility</em> and not geometry. For a migration whose contract is that every width comes from
     * a frozen picture clause, that was the gap that mattered most.
     *
     * <p><em>Remediation, applied:</em> {@link SchemaMetadataMatrix} declares every facet once and asserts
     * the live catalogue against it by exact equality on ordered lists, so a missing facet and an extra facet
     * both fail. Delegating rather than restating is deliberate: the shared schema test drives the identical
     * contract over all eleven tables, and a paraphrase here could agree with the schema while disagreeing
     * with the authority.
     *
     * <p>For {@code transaction_type} that is two columns, the two-character key of the catalogue's KEYLEN 2, and no foreign key at all because it is a root reference table - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVTRA03Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the transaction type table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "transaction_type");
    }

}
