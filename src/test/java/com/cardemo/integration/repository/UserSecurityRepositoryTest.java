/*
 * ******************************************************************
 * Program     : UserSecurityRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier
 * Function    : Proves the user security table's persistence contract
 *               against a real PostgreSQL 16 provisioned by
 *               Testcontainers: the ten seeded users are held only as
 *               BCrypt strength-10 digests, the user class is stored as
 *               the single characters A and U through an
 *               AttributeConverter rather than an enumerated mapping,
 *               the table carries exactly five columns, one CHECK
 *               constraint, zero foreign keys, no index beyond its
 *               primary key and no version column, and the user list
 *               pages ten rows in ascending key order. Every credential
 *               assertion is made on a PROPERTY of the stored value;
 *               this file writes no password, echoes no digest and
 *               names no secret of any kind.
 * Source      : app/cpy/CSUSR01Y.cpy:17-23 (RECLN 80, key 8, offsets
 *               1-8/9-28/29-48/49-56/57/58-80, the last of them a
 *               NAMED filler),
 *               app/catlg/LISTCAT.txt:3846 (CLUSTER) and :3883
 *               (KEYLEN 8 / AVGLRECL 80),
 *               app/jcl/DUSRSECJ.jcl:35-44 (ten inline seed rows) and
 *               :64-66 (DEFINE CLUSTER, KEYS(8,0), RECORDSIZE(80,80)),
 *               app/cpy/COCOM01Y.cpy:26-28 (the A and U 88-levels),
 *               app/cbl/COSGN00C.cbl:227 (the type move whose value
 *               routes to the admin or the main menu at :230),
 *               app/cbl/COUSR00C.cbl:57 (OCCURS 10 TIMES, the user
 *               list page size), app/cbl/COUSR03C.cbl (read-confirm-
 *               delete carrying no self-delete guard),
 *               app/csd/CARDDEMO.CSD:88-89 (one of exactly eight
 *               online DEFINE FILE entries) @ 7756d89
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

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration coverage for {@link UserSecurityRepository} against a real PostgreSQL 16, and the one class
 * in this tier whose subject matter is credential material.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves the persistence contract of {@code user_security}, the table that replaces the VSAM cluster
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}. Six things are asserted and nothing else is: that the ten
 * seeded credentials are stored only as BCrypt digests at the pinned strength, that the user class travels
 * to the column as a single character through an attribute converter, that the table's shape is exactly the
 * shape the migrations declare, that each of its constraints is genuinely enforced by the engine, that the
 * legacy user-list browse order and page size survive as an ordered paged finder, and that the locked read
 * replacing {@code EXEC CICS READ ... UPDATE} genuinely takes a row lock in the engine rather than merely
 * carrying an annotation.
 *
 * <h3>The field contract, from the frozen source</h3>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy} carries the Apache banner at {@code :1-16} and the record itself at
 * {@code :17-23}. The 1-based offset map closes exactly on the catalogued record length:
 *
 * <ul>
 *   <li>{@code SEC-USR-ID PIC X(08)} at {@code :18}, bytes <strong>1-8</strong>, also the cluster key;</li>
 *   <li>{@code SEC-USR-FNAME PIC X(20)} at {@code :19}, bytes <strong>9-28</strong>;</li>
 *   <li>{@code SEC-USR-LNAME PIC X(20)} at {@code :20}, bytes <strong>29-48</strong>;</li>
 *   <li>{@code SEC-USR-PWD PIC X(08)} at {@code :21}, bytes <strong>49-56</strong>;</li>
 *   <li>{@code SEC-USR-TYPE PIC X(01)} at {@code :22}, byte <strong>57</strong>;</li>
 *   <li>{@code SEC-USR-FILLER PIC X(23)} at {@code :23}, bytes <strong>58-80</strong>.</li>
 *   </ul>
 *
 * <p>{@code 8 + 20 + 20 + 8 + 1 + 23 = 80}, the catalogued length. The trailing item is a
 * <strong>named</strong> filler, which is unusual in this corpus - every other record layout uses an
 * anonymous {@code FILLER} - and is recorded here as a source anomaly. A name
 * does not make it data: it is not mapped to a column, and this class asserts that the table has exactly
 * five columns and that no {@code sec_usr_filler} column exists.
 *
 * <h3>Where this file takes the geometry from, and why those locators</h3>
 *
 * <p>The {@code USRSEC} cluster is catalogued as well as defined in JCL, and the catalogue is what this file
 * reads, because it carries the geometry directly:
 * {@code app/catlg/LISTCAT.txt:3846} reads {@code CLUSTER ------- AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}, and
 * its geometry is at {@code :3883}, {@code KEYLEN-----------------8     AVGLRECL--------------80}. The
 * width is therefore doubly grounded, because {@code app/jcl/DUSRSECJ.jcl:64-66} independently declares
 * {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)}, {@code KEYS(8,0)} and
 * {@code RECORDSIZE(80,80)}. Two further corroborations sit in the same catalogue entry: {@code :3885}
 * carries {@code UNIQUE} with no {@code NONUNIQKEY}, so the primary key genuinely is unique and this table
 * has no alternate index at all, and {@code :3888} reports {@code REC-TOTAL 10}, the physical row count
 * that the seed reproduces.
 *
 * <p>Two nearby lines, {@code :3881} and {@code :3913}, are the wrong place to read that geometry from.
 * Both are {@code CLUSTER--AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} <em>back-references</em>,
 * emitted inside the {@code DATA} component listing that begins at {@code :3873} and the {@code INDEX}
 * component listing that begins at {@code :3906}. They name the cluster; they do not carry the key length
 * or the record length. The correct pair is {@code :3846} and {@code :3883}, which is what this file cites.
 *
 * <h3>The ten seeded users, and the one value that is never written down</h3>
 *
 * <p>There is no {@code usrsec.txt} fixture. The ten records exist only as inline {@code SYSUT1 DD *} card
 * images at {@code app/jcl/DUSRSECJ.jcl:35-44}, fed through {@code IEBGENER} into the cluster defined at
 * {@code :62-71}. Five are administrators of type {@code A} - {@code ADMIN001} MARGARET GOLD,
 * {@code ADMIN002} RUSSELL RUSSELL, {@code ADMIN003} RAYMOND WHITMORE, {@code ADMIN004} EMMANUEL CASGRAIN,
 * {@code ADMIN005} GRANVILLE LACHAPELLE - and five are standard users of type {@code U} - {@code USER0001}
 * LAWRENCE THOMAS, {@code USER0002} AJITH KUMAR, {@code USER0003} LAURITZ ALME, {@code USER0004} AVERARDO
 * MAZZI, {@code USER0005} LEE TING.
 *
 * <p><strong>All ten carry one shared eight-character literal plaintext value in {@code SEC-USR-PWD},
 * bytes 49-56 of the frozen card images. That value appears nowhere in this file, in any form.</strong> It
 * is referred to only as <em>the single shared literal plaintext value recorded at
 * {@code app/jcl/DUSRSECJ.jcl:35-44}</em> - not in code, not in a comment, not in Javadoc, not in a
 * variable name, not in an assertion description, and not spelled out obliquely. Writing it would be a
 * forbidden under Rule 1 Clause D, which names {@code tests} explicitly.
 *
 * <p>That prohibition rules out the assertion a reader might expect. A "the stored value does not equal the
 * plaintext" check would require writing the plaintext in order to compare against it, and writing it
 * <em>is</em> the violation - the check would create the leak it was meant to detect. This class therefore
 * proves the same property <strong>positively and more strongly</strong>: every stored value satisfies the
 * BCrypt envelope and is exactly sixty characters long, which is structurally incompatible with an
 * eight-character plaintext, so no comparison against the plaintext is needed or made. It also asserts the
 * ten digests are pairwise distinct, which is the cleanest available evidence that per-row salting really
 * happened rather than one constant being copied ten times.
 *
 * <h3>The credential column, widened on purpose</h3>
 *
 * <p>{@code sec_usr_pwd} is declared {@code VARCHAR(60)} while its source field is {@code PIC X(08)}. This
 * is the <strong>one</strong> place in this package where a column width deliberately departs from the byte
 * layout, and the deviation is security-mandated rather than incidental: the column no longer stores a
 * password, it stores a BCrypt digest, and a digest is sixty characters. {@code VARCHAR} and not
 * {@code CHAR}, because a {@code CHAR(60)} column would blank-pad a value that is already exactly sixty
 * characters and would pad a shorter one into looking well formed. The source's eight bytes still count
 * towards the eighty-byte record arithmetic above, because that arithmetic describes the frozen record and
 * not the table.
 *
 * <h3>The user class is converted, never enumerated</h3>
 *
 * <p>{@code app/cpy/COCOM01Y.cpy:26-28} declares {@code CDEMO-USER-TYPE PIC X(01)} with exactly two
 * condition names, {@code VALUE 'A'} and {@code VALUE 'U'}, and {@code app/cbl/COSGN00C.cbl:227} moves
 * {@code SEC-USR-TYPE} straight into that field, which is what makes the two share one domain; {@code :230}
 * then tests the administrator condition to choose between the admin and the main menu. The column
 * therefore holds {@code A} or {@code U} and nothing else.
 *
 * <p>{@link UserSecurity} maps it with a nested {@code AttributeConverter}, not with an enumerated mapping,
 * and this class asserts the stored character directly because that is the only assertion able to catch the
 * mistake. A string-enumerated mapping would try to write {@code ADMIN} and {@code USER} into a
 * {@code CHAR(1)}; an ordinal one would write {@code 0} and {@code 1}. Either breaks parity.
 *
 * <h3>Zero foreign keys, no index, no version column</h3>
 *
 * <p>None of the migration's ten foreign keys touches this table, in either direction. In the source a user
 * is not linked to an account, a card or a customer, so inventing such a link would be a
 * The three non-unique B-tree indexes that replace the catalogue's three
 * alternate indexes are all on other tables, and this cluster has no alternate index to replace. The
 * {@code version} column exists on exactly four tables and this is not one of them; the ten inline card
 * images carry no counter, so there is nothing for optimistic locking to reproduce.
 *
 * <h3>The absent self-delete guard is preserved, not repaired</h3>
 *
 * <p>{@code app/cbl/COUSR03C.cbl} deletes a user without ever comparing the target identifier against the
 * signed-on one: a census of that 359-line program finds <strong>zero</strong> occurrences of
 * {@code CDEMO-USER-ID}, and the delete is issued unconditionally at {@code :307}. A signed-on
 * administrator can therefore delete their own row. That is a preserved legacy quirk, and adding the guard
 * would be a behaviour change and is forbidden. Accordingly this class asserts only that deleting
 * an existing row succeeds, and never that a self-delete is refused.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone with
 * {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe by its path, and the binding is fragile in a way that fails
 * silently.</strong> {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code
 * **}{@code /*Test.java} and runs it at {@code integration-test} and {@code verify}, even though the class
 * keeps the {@code Test} suffix; {@code maven-surefire-plugin} 3.5.4 explicitly <em>excludes</em>
 * {@code **}{@code /integration/}{@code **}. A class moved up to the {@code integration} level, to
 * {@code com.cardemo}, or into {@code src/test/java} directly matches neither include set, is collected by
 * neither plugin and never runs: the build stays green, both plugins report success, and nothing anywhere
 * says a test disappeared. <strong>For this file that failure mode is a security regression</strong>,
 * because the security gate's "every password is hashed at the pinned strength" evidence is produced here -
 * a silently uncollected class would let the gate pass with no evidence behind it. Do not rename or
 * relocate this class, and confirm the Failsafe report actually names it.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a real PostgreSQL 16
 * container and a reaper container. Where a host toolchain is not provisioned the identical build runs
 * inside the pinned image, and produces the same result because every plugin and every non-managed
 * dependency version is pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads exactly one property and hardcodes no environment fact. It contains no database host,
 * port, database name, user name, password or JDBC URL, no cloud endpoint, no token and no signing key; the
 * connection is injected from the container by the harness, and a literal would defeat that injection.
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, activated by the harness. Its Flyway placeholder
 *       {@code seeddemousers} is {@code true}, which is what makes the ten rows present; the base profile
 *       and the production profile both set it {@code false}, so this count is a property of this profile
 *       rather than of the migration alone.</li>
 *   <li><strong>PostgreSQL 16</strong>, pinned by image digest in the harness rather than by tag, and the
 *       Debian rather than the Alpine variant so that text collation - which this class relies on when it
 *       asserts ascending key order - matches what ships.</li>
 *   <li><strong>The injected fixed UTC clock</strong> is available from the harness and the ambient clock is
 *       never read. This class needs no instant of its own, because nothing in this table is temporal:
 *       there is no date, no timestamp and no version counter among its five columns.</li>
 *   <li><strong>{@code spring.jpa.hibernate.ddl-auto: validate}</strong>, so the entity-to-column contract
 *       is enforced at context startup rather than discovered at query time;
 *       {@code spring.jpa.open-in-view: false}; and <strong>{@code spring.jpa.show-sql: false}, which is
 *       part of this file's credential control rather than a preference</strong> - echoed SQL would put a
 *       digest into the build log, and the masking rules in {@code logback-spring.xml} cover the same
 *       ground. Neither may be defeated to debug a failure here.</li>
 *   <li><strong>Exactly three Flyway migrations</strong>, with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and {@code baseline-on-migrate: false},
 *       and never {@code ignore-migration-patterns} or {@code continue-on-error}. The {@code BATCH_*}
 *       tables come from {@code spring.batch.jdbc.initialize-schema}, never from a fourth migration.
 *       Hibernate's JDBC time zone is {@code UTC} and {@code spring.batch.job.enabled} is {@code false}.</li>
 *   <li><strong>{@code carddemo.pagination.user-list-page-size}</strong> is the only property this class
 *       reads. Its value is ten, from {@code app/cbl/COUSR00C.cbl:57}, and it is injected rather than
 *       written as a literal so that the page size under test is the one the production service receives.</li>
 *   <li><strong>BCrypt strength exactly 10</strong> is the pinned work factor and is asserted, not assumed.
 *       It is never lowered to make this class run faster; that would break parity.</li>
 *   <li><strong>The {@code CHAR} blank-pad policy, stated once and applied throughout.</strong> A
 *       {@code CHAR(n)} column returns its value blank-padded to {@code n} and is never trimmed, so
 *       {@code sec_usr_fname} and {@code sec_usr_lname} come back as twenty characters and the eight-character
 *       identifiers come back unpadded because they already fill their column. {@code sec_usr_pwd} is
 *       {@code VARCHAR} and is never padded. This was settled by measurement rather than by inference,
 *       because PostgreSQL's own {@code length()} and text coercion silently strip trailing blanks from a
 *       fixed-width value and would have suggested the opposite.</li>
 *   <li><strong>The token signing key</strong> resolves from the environment with no default and fails fast
 *       in every profile. No signing-key literal appears here or in the harness; the harness generates an
 *       ephemeral key per context for startup, and this class issues no token and asserts nothing about
 *       one.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test fails to start with a container or Docker error.</em> There is no reachable Docker
 *       socket. State the blocker rather than reporting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version resolves.</em> This is
 *       the most consequential trap of the migration and its remedy has two halves that are
 *       both required. Pin the version by <em>overriding the property the Spring Boot parent manages</em>,
 *       never by importing a second bill of materials, because two competing imports resolve in an
 *       ordering-dependent way that can silently select the parent-managed 1.x line; and use only the
 *       <em>prefixed</em> module coordinates that exist on the 2.x line, {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. Overriding without renaming resolves artefacts that are not
 *       published; renaming without overriding resolves the wrong version. Both halves are already in the
 *       root {@code pom.xml}, which is root-owned and must not be edited from here.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with {@code -Xlint:all}
 *       and {@code -Werror} and that reaches test compilation, so a single unused import is a build
 *       failure, not a warning.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than on a column name.</em> {@code validate}
 *       compares type codes, so a {@code CHAR(1)} under a converter to {@code String} must match exactly and
 *       {@code VARCHAR(60)} must not be declared as {@code CHAR(60)} on the entity. <strong>The fix is
 *       upstream</strong>, in {@code V1__create_schema.sql} or in the mapping; never widen a column to
 *       silence it and never patch this test.</li>
 *   <li><em>The stored user class reads {@code ADMIN} or {@code 0} instead of {@code A}.</em> An enumerated
 *       mapping has replaced the converter. That is the defect the raw-character
 *       assertions in this class exist to catch.</li>
 *   <li><em>A fixture cannot be found for this table.</em> None exists, and none may be created; see the
 *       third disclosure below.</li>
 *   <li><em>A digest assertion fails intermittently.</em> An exact digest value is being asserted
 *       somewhere. BCrypt salts randomly by design, so only structural properties are deterministic; this
 *       class asserts shape, length, cost and distinctness, never a value.</li>
 *   <li><em>A case-folding assertion behaves differently on one machine.</em> A case operation ran without
 *       an explicit locale. Sign-on upper-cases both the identifier and the password, and a Turkish-locale
 *       fold would corrupt an {@code i}; every case operation in this tier passes {@code Locale.ROOT}. This
 *       class performs none, which is the strongest form of the same guarantee.</li>
 *   <li><em>{@code count()} is not ten, or the single-page paging assertions fail.</em> A row leaked from a
 *       sibling test. Isolation comes solely from the harness's per-method transactional rollback, which is
 *       why this class adds no schema script, no truncation, no bulk delete and no context-dirtying
 *       marker.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Three items are stated as <strong>"Not available"</strong> rather than filled with an invention,
 * because each would otherwise become a false oracle.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured legacy
 *       output exists anywhere in this repository; searches across expected, baseline, golden,
 *       system-output and {@code .out} name patterns, and across the reject, report, statement and HTML
 *       dataset names, return only dataset <em>definition</em> members and no captured data. What is needed
 *       is a captured 430-byte reject dataset from a real posting run at a known input state, together with
 *       the resulting transaction, account and category-balance images. Until that exists: create no
 *       baseline file, fabricate no expected bytes, and do not generate a baseline by running this
 *       implementation and asserting against its own output, which is circular. Hand-simulation is equally
 *       inadmissible and demonstrably so, since two defensible models of the posting program over the same
 *       fixtures disagree - thirteen rejects against thirty-eight - and a figure that moves with the model
 *       is not an oracle. Any legitimate baseline would have to respect the record lengths 430 for a
 *       reject, 133 for a report line, 350 for a transaction image, and 80 for statement text with the
 *       statement HTML declared 80 in one step of its job and 100 in another, a legacy defect to log and
 *       never to fix.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> A census
 *       across the twenty-eight programs of {@code app/cbl} finds the literal file status {@code '35'}
 *       <strong>zero</strong> times and {@code DFHRESP(NOTOPEN)} <strong>zero</strong> times. The condition
 *       is specification-derived only, so no test for it is invented here and no parity claim is made for
 *       it.</p></li>
 *   <li><p><strong>Fixture-based corroboration of this table is Not available.</strong> There is no
 *       {@code usrsec.txt} among the nine frozen ASCII fixtures and no captured {@code USRSEC} dataset
 *       image. The only seed evidence is the inline card block at {@code app/jcl/DUSRSECJ.jcl:35-44}, and
 *       that block is credential-bearing, so it must not be transcribed into a test resource - which is
 *       precisely why no fixture may be manufactured for this table. What would be needed to close the gap
 *       is a de-identified eighty-byte {@code USRSEC} extract carrying hashed rather than plaintext
 *       credentials. Until then the seeded state comes solely from {@code V3__seed_data.sql}, and this
 *       class reads no fixture at all.</p></li>
 *   </ol>
 *
 * <h2>Scope: cited here, asserted elsewhere</h2>
 *
 * <p>One concern per class. This class asserts persistence, mapping, conversion, constraint and paging
 * behaviour, and deliberately re-tests none of the following, each of which belongs to a named owner:
 * sign-on, including its upper-casing of <em>both</em> the identifier and the password at
 * {@code app/cbl/COSGN00C.cbl:132} and {@code :135}; BCrypt verification itself; token issuance and the
 * claim mapping of {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE}; role-based endpoint authorisation;
 * and the field-validation order of the user add, update and delete programs at
 * {@code app/cbl/COUSR01C.cbl}, {@code app/cbl/COUSR02C.cbl} and {@code app/cbl/COUSR03C.cbl}. In
 * particular no plaintext candidate is ever constructed to verify a digest, and {@link UserSecurity} is not
 * a security principal - it implements no user-details contract, and this class references none.
 *
 * <h2>Three shapes this file reads off the destination contract</h2>
 *
 * <p>Each was taken from the code it asserts against rather than from prose, because assuming any of them
 * would have produced code that did not compile or an assertion that could not hold.
 * First, the entity's name properties are {@code secUsrFname} and
 * {@code secUsrLname}, not {@code firstName} and {@code lastName}. Second, the paged finder returns a
 * {@code Slice} and not a {@code Page}, so a total-element count cannot come from it and is taken instead
 * from the inherited page-returning finder and from {@code count()}. Third, the migration's three index
 * names are {@code idx_card_acct_id}, {@code idx_card_cross_reference_acct_id} and
 * {@code idx_transaction_proc_ts}; that is immaterial to this table, which carries none of them, but it is
 * recorded so the next reader does not assert a name that does not exist.
 */
@DisplayName("UserSecurityRepository against PostgreSQL 16 - credential storage, mapping and constraints")
final class UserSecurityRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test, the single door onto {@code user_security}. */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /**
     * Used for the parameterised catalogue reads and for the deliberately malformed inserts that the
     * entity's own write-path guards would otherwise refuse before the engine ever saw them.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The user-list page size, injected from {@code carddemo.pagination.user-list-page-size} exactly as the
     * production service receives it, so that no page-size literal appears in this file. Its value is ten,
     * from {@code OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl:57}.
     */
    @Value("${carddemo.pagination.user-list-page-size}")
    private int userListPageSize;

    /**
     * Returns the regular expression a stored credential must match: a BCrypt version tag of {@code 2a},
     * {@code 2b} or {@code 2y}, a cost factor of exactly {@code 10}, and fifty-three further characters of
     * salt and digest drawn from BCrypt's radix-64 alphabet, for sixty characters in total.
     *
     * <p>The version tag is written as a character class rather than as three spelled-out prefixes, and the
     * set is the one the verifier accepts rather than a conventional one: the historical fourth tag is
     * excluded because the encoder can neither produce nor verify it, so a value carrying it could never
     * authenticate anybody. Twenty-two characters of salt plus thirty-one of digest is fifty-three, and
     * seven characters of version and cost bring the total to the sixty the column is declared to hold.
     *
     * <p>It is a method rather than a field because this class declares no {@code static} member of any
     * kind, and a pure function returning a constant expression is the narrowest way to hold a shared value
     * without introducing state. Callers that evaluate it in a loop compile it once into a local.
     *
     * @return the anchored pattern, never {@code null}
     */
    private String bcryptStrength10Shape() {
        return "^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$";
    }

    /**
     * Composes a well formed, sixty-character BCrypt-shaped value that is <strong>not</strong> derived from
     * any password, real or seeded.
     *
     * <p>This is how a row is built when one is needed. Two alternatives were rejected. Hashing a value at
     * run time would work but makes the result non-deterministic, because BCrypt salts randomly by design,
     * so nothing about the produced value could be asserted beyond its shape - and it would also mean this
     * class held a plaintext to hash. Copying a seeded digest out of the database and reusing it would put
     * real credential material into a row whose insertion is <em>expected to fail</em>, and PostgreSQL
     * echoes the entire failing row in the detail of a constraint violation, so the digest would reach the
     * build log by the shortest possible route. A synthetic value avoids both: it satisfies every structural
     * rule the column and the entity impose, it says what it is in its own text, and it verifies nothing.
     *
     * @param discriminator exactly two characters from BCrypt's radix-64 alphabet, so that several equally
     *                      well formed but distinct values can be composed without a random source
     * @return a sixty-character value matching {@link #bcryptStrength10Shape()}, never {@code null}
     * @throws IllegalArgumentException if {@code discriminator} is {@code null} or is not exactly two
     *                                  characters, since any other length would compose a value of the
     *                                  wrong width and the resulting failure would look like a defect in the
     *                                  column rather than in the caller
     */
    private String syntheticBcryptDigest(final String discriminator) {
        if (discriminator == null || discriminator.length() != 2) {
            throw new IllegalArgumentException("the discriminator must be exactly two characters drawn from "
                    + "BCrypt's radix-64 alphabet so that the composed value is exactly 60 characters");
        }
        // 7 characters of version and cost, 22 of salt, 29 + 2 of digest: 60 in total.
        return "$2a$10$" + "SyntheticSaltForTests." + "SyntheticDigestNotARealDigest" + discriminator;
    }

    /**
     * Renders the value a {@code CHAR(width)} column returns for {@code value}, which is the value followed
     * by enough spaces to fill the declared width.
     *
     * <p>This is the blank-pad policy stated once on the class, applied. A fixed-width column pads on
     * storage and the driver hands the padding back, so an assertion comparing an unpadded expectation
     * against a padded actual fails for a reason that has nothing to do with the data. Padding the
     * expectation rather than trimming the actual is deliberate: trimming would discard the very property
     * being asserted, and in a fixed-width record a trailing space is data.
     *
     * @param value the unpadded value, never {@code null}
     * @param width the declared column width, which must be at least the length of {@code value}
     * @return {@code value} padded on the right with spaces to exactly {@code width} characters
     * @throws IllegalArgumentException if {@code value} is longer than {@code width}, which would mean the
     *                                  expectation itself contradicts the column it is written against
     */
    private String blankPadded(final String value, final int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("a CHAR(" + width + ") column cannot hold a value of "
                    + value.length() + " characters, so this expectation contradicts the schema");
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Returns the fixed, fully parameterised statement used for every deliberately malformed insert.
     *
     * <p>All five values are bind parameters and the statement text is a constant. Building it by
     * concatenation would be the shell-injection pattern Rule 1 Clause D names, transposed to SQL, and in
     * this file it would be credential-adjacent as well; there is therefore no code path here that composes
     * SQL from a value.
     *
     * <p>A native statement is used rather than the repository because {@link UserSecurity} validates
     * eagerly: its constructor, its setters and its pre-write callback all refuse a null, an over-length or
     * a malformed value before a statement is ever built, and its user-class property is an enumeration that
     * cannot express an out-of-domain character at all. Those guards are correct and are not weakened; they
     * simply mean the <em>database</em> boundary has to be reached directly for the engine's own enforcement
     * to be observable.
     *
     * @return the insert statement with five placeholders, never {@code null}
     */
    private String userSecurityInsert() {
        return "INSERT INTO user_security (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd,"
                + " sec_usr_type) VALUES (?, ?, ?, ?, ?)";
    }

    /**
     * Reads the raw character stored in {@code sec_usr_type} for one identifier, through a parameterised
     * query, without materialising an entity.
     *
     * <p>Going round the mapping is the point. Reading the property back would exercise the converter in
     * both directions and prove nothing about what reached the column, so a mistaken enumerated mapping
     * would round-trip cleanly and pass. Only the raw value can distinguish {@code A} from {@code ADMIN} and
     * from {@code 0}.
     *
     * @param userId the eight-character identifier to read, never {@code null}
     * @return the stored character, which for a {@code CHAR(1)} column is a one-character string
     */
    private String storedUserTypeCharacter(final String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT sec_usr_type FROM user_security WHERE sec_usr_id = ?", String.class, userId);
    }

    /**
     * Returns the column names of {@code user_security} in declaration order, read from the catalogue
     * through a parameterised query with the schema taken from the connection rather than assumed.
     *
     * @return the column names in ordinal position order, never {@code null}
     */
    private List<String> userSecurityColumnNames() {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ?"
                        + " ORDER BY ordinal_position",
                String.class, "user_security");
    }

    /**
     * Returns the names of the check constraints declared on {@code user_security}.
     *
     * <p>The statement is fixed and the table name is the only bind parameter. The constraint-kind code is a
     * SQL literal rather than a parameter for two reasons: the catalogue column has an internal
     * single-character type that does not compare cleanly against a driver-typed string, and a literal keeps
     * this method's statement constant, which is the property that makes the whole file free of composed SQL.
     * The three sibling methods below repeat the shape rather than share it through a composed fragment,
     * which is a deliberate trade of three short constants against one concatenation.
     *
     * @return the check-constraint names on this table, never {@code null}
     */
    private List<String> checkConstraintNames() {
        return jdbcTemplate.queryForList(
                "SELECT con.conname FROM pg_constraint con"
                        + " JOIN pg_class rel ON rel.oid = con.conrelid"
                        + " JOIN pg_namespace ns ON ns.oid = rel.relnamespace"
                        + " WHERE ns.nspname = current_schema() AND rel.relname = ?"
                        + " AND con.contype = 'c' ORDER BY con.conname",
                String.class, "user_security");
    }

    /**
     * Counts the foreign keys declared <em>on</em> {@code user_security}, that is, the links it owns.
     *
     * @return the outbound foreign-key count, which the source's record layout puts at zero
     */
    private Long outboundForeignKeyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint con"
                        + " JOIN pg_class rel ON rel.oid = con.conrelid"
                        + " JOIN pg_namespace ns ON ns.oid = rel.relnamespace"
                        + " WHERE ns.nspname = current_schema() AND rel.relname = ? AND con.contype = 'f'",
                Long.class, "user_security");
    }

    /**
     * Counts the foreign keys declared elsewhere that <em>point at</em> {@code user_security}.
     *
     * <p>Both directions are counted because a link invented from either side would break the zero-foreign-key
     * claim equally, and an inbound one is the easier of the two to add by accident - a later table gaining a
     * "created by" column would introduce it without this table's own definition changing at all.
     *
     * @return the inbound foreign-key count, which the source's record layout puts at zero
     */
    private Long inboundForeignKeyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint con"
                        + " JOIN pg_class rel ON rel.oid = con.confrelid"
                        + " JOIN pg_namespace ns ON ns.oid = rel.relnamespace"
                        + " WHERE ns.nspname = current_schema() AND rel.relname = ? AND con.contype = 'f'",
                Long.class, "user_security");
    }

    /**
     * Returns the names of every index on {@code user_security}, through a parameterised catalogue query.
     *
     * @return the index names in name order, never {@code null}
     */
    private List<String> indexNames() {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes"
                        + " WHERE schemaname = current_schema() AND tablename = ? ORDER BY indexname",
                String.class, "user_security");
    }

    /**
     * Returns the distinct relation-level lock modes the <em>current</em> transaction holds on
     * {@code user_security}, read from the engine's own lock table.
     *
     * <p>This is what makes the locked read observable rather than merely asserted. PostgreSQL takes
     * {@code AccessShareLock} on a table for a plain {@code SELECT} and {@code RowShareLock} for a
     * {@code SELECT ... FOR UPDATE}; the two are distinct entries, so the presence of the second is direct
     * evidence that {@code FOR UPDATE} reached the engine. A test that only checked the returned row would
     * pass against a finder carrying no lock annotation at all, which is precisely the defect this group
     * exists to catch.
     *
     * <p>Three properties make the read deterministic. It is scoped to {@code pg_backend_pid()}, so a lock
     * held by a sibling container connection cannot satisfy it. It reads only {@code locktype = 'relation'},
     * so the transient {@code tuple} entry a <em>waiting</em> transaction would show cannot appear and no
     * second connection is needed. And it runs on the same connection the repository used, which holds
     * because this harness's transaction manager derives its {@code DataSource} from the entity-manager
     * factory - the same sharing every raw catalogue read in this class already relies on.
     *
     * @return the distinct lock modes in name order, empty when this transaction holds none, never
     *         {@code null}
     */
    private List<String> relationLockModesHeldByThisTransaction() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT l.mode FROM pg_locks l"
                        + " JOIN pg_class rel ON rel.oid = l.relation"
                        + " JOIN pg_namespace ns ON ns.oid = rel.relnamespace"
                        + " WHERE l.pid = pg_backend_pid() AND l.locktype = 'relation'"
                        + " AND ns.nspname = current_schema() AND rel.relname = ? ORDER BY l.mode",
                String.class, "user_security");
    }

    /**
     * The credential contract: what {@code sec_usr_pwd} holds, how wide it is, and what may never leak.
     *
     * <p>Every assertion in this group is either numeric or boolean, and each carries a description naming
     * the user identifier and the property that failed. That is a deliberate constraint rather than a style
     * choice: an assertion written in the natural way, comparing a stored value against an expectation,
     * prints the <em>actual</em> value when it fails - so a failing digest assertion would write a real
     * credential digest into the build log at exactly the moment somebody is reading it. Asserting a derived
     * number or a derived boolean makes a failure message structurally incapable of carrying the value.
     */
    @Nested
    @DisplayName("the credential column holds BCrypt strength-10 digests and nothing else")
    class CredentialStorage {

        @Test
        @DisplayName("all ten seeded credentials satisfy the BCrypt envelope at exactly sixty characters")
        void everySeededCredentialSatisfiesTheBcryptEnvelope() {
            final String shape = bcryptStrength10Shape();
            final List<UserSecurity> users = userSecurityRepository.findAll();

            assertThat(users)
                    .as("app/jcl/DUSRSECJ.jcl:35-44 carries ten inline card images, corroborated by "
                            + "REC-TOTAL 10 at app/catlg/LISTCAT.txt:3888")
                    .hasSize(10);

            for (final UserSecurity user : users) {
                final String digest = user.getPasswordHash();

                assertThat(digest.length())
                        .as("sec_usr_pwd for %s must be exactly 60 characters, the width of a BCrypt "
                                + "digest; the source field SEC-USR-PWD PIC X(08) at "
                                + "app/cpy/CSUSR01Y.cpy:21 held eight characters of plaintext and the "
                                + "column was widened precisely so that it cannot", user.getSecUsrId())
                        .isEqualTo(60);

                assertThat(digest.matches(shape))
                        .as("sec_usr_pwd for %s must satisfy the BCrypt envelope - a 2a, 2b or 2y version "
                                + "tag, a cost factor, then 22 characters of salt and 31 of digest from "
                                + "the radix-64 alphabet. The stored value is credential material and is "
                                + "deliberately not reproduced in this message", user.getSecUsrId())
                        .isTrue();

                assertThat(digest.matches("^\\$2[aby]\\$10\\$.*"))
                        .as("sec_usr_pwd for %s must carry cost factor exactly 10, the strength this "
                                + "migration pins; a digest at any other work factor would still verify "
                                + "but is not the artefact this column holds", user.getSecUsrId())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the ten digests are pairwise distinct, which proves real per-row salting")
        void theTenSeededDigestsArePairwiseDistinct() {
            final List<String> digests = userSecurityRepository.findAll().stream()
                    .map(UserSecurity::getPasswordHash)
                    .toList();

            // Counted rather than compared. AssertJ's duplicate-detection assertions name the offending
            // elements when they fail, which for this column would print real digests; a distinct count is a
            // number, so its failure message cannot.
            assertThat(digests.stream().distinct().count())
                    .as("all ten source rows share one plaintext value, so ten identical digests would "
                            + "mean one constant was copied rather than each row being hashed with its own "
                            + "salt - the strongest available evidence that hashing actually happened")
                    .isEqualTo(10L);
        }

        @Test
        @DisplayName("sec_usr_pwd is VARCHAR(60), the deliberate widening from the PIC X(08) source field")
        void theCredentialColumnIsVarcharSixty() {
            final String dataType = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?"
                            + " AND column_name = ?",
                    String.class, "user_security", "sec_usr_pwd");
            final Integer width = jdbcTemplate.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?"
                            + " AND column_name = ?",
                    Integer.class, "user_security", "sec_usr_pwd");

            assertThat(dataType)
                    .as("a BCrypt digest is exactly 60 characters, so CHAR(60) would blank-pad a shorter "
                            + "value into looking well formed; VARCHAR is what makes a wrong width visible")
                    .isEqualTo("character varying");
            assertThat(width)
                    .as("60, not the 8 of SEC-USR-PWD PIC X(08) at app/cpy/CSUSR01Y.cpy:21. This is the one "
                            + "column in this table whose width departs from the byte layout, and the "
                            + "departure is security-mandated: the column stores a digest, not a password")
                    .isEqualTo(60);
        }

        @Test
        @DisplayName("the widened column accepts a full sixty-character digest, which is the positive proof")
        void theCredentialColumnAcceptsAFullSixtyCharacterDigest() {
            userSecurityRepository.save(new UserSecurity("ZTEST001", blankPadded("SYNTHETIC", 20),
                    blankPadded("ROW", 20), syntheticBcryptDigest("Zz"), UserType.ADMIN));
            flushAndClear();

            final Optional<UserSecurity> reread = userSecurityRepository.findById("ZTEST001");

            assertThat(reread)
                    .as("the row written above must be readable back inside this transaction")
                    .isPresent();
            assertThat(reread.orElseThrow().getPasswordHash().length())
                    .as("a VARCHAR(60) column must return the digest at its full width and must not pad "
                            + "or truncate it")
                    .isEqualTo(60);
        }

        @Test
        @DisplayName("toString exposes the identifier and the user class only, never a digest or a name")
        void toStringExposesOnlyTheIdentifierAndTheUserClass() {
            final UserSecurity user = userSecurityRepository.findById("ADMIN001").orElseThrow();
            final String rendered = user.toString();

            // The negative, value-free assertions run FIRST and on purpose. Until they have passed, the
            // rendering is not known to be safe to print, and every assertion below prints it on failure.
            assertThat(rendered.contains(user.getPasswordHash()))
                    .as("the diagnostic rendering of the credential entity must never carry the digest")
                    .isFalse();
            assertThat(rendered.contains(user.getSecUsrFname().strip()))
                    .as("nor the first name: an identifier is enough to diagnose a row, and a narrower "
                            + "rendering is what keeps user material out of a log line")
                    .isFalse();
            assertThat(rendered.contains(user.getSecUsrLname().strip()))
                    .as("nor the last name, for the same reason")
                    .isFalse();

            assertThat(rendered)
                    .as("the rendering is now known to carry neither a digest nor a name, so printing it "
                            + "here is safe; it must still identify the row and its user class")
                    .contains("ADMIN001")
                    .contains(UserType.ADMIN.name());
        }
    }

    /**
     * The seed contract: the ten rows {@code V3__seed_data.sql} writes, and the single-row reads over them.
     *
     * <p>The ten identifiers, names and user classes come from the inline card images at
     * {@code app/jcl/DUSRSECJ.jcl:35-44}. The eight-character plaintext each of those images also carries in
     * bytes 49-56 is not reproduced anywhere, and no assertion here needs it.
     */
    @Nested
    @DisplayName("the ten-row seed, and the keyed reads over it")
    class SeedContract {

        @Test
        @DisplayName("the seed carries exactly ten rows, which the catalogue's own REC-TOTAL corroborates")
        void theSeedCarriesExactlyTenRows() {
            assertThat(userSecurityRepository.count())
                    .as("ten card images at app/jcl/DUSRSECJ.jcl:35-44, REC-TOTAL 10 at "
                            + "app/catlg/LISTCAT.txt:3888, and the test profile's Flyway placeholder "
                            + "seeddemousers set true - the base and production profiles set it false, so "
                            + "this count is a property of this profile as much as of the migration")
                    .isEqualTo(10L);
        }

        @Test
        @DisplayName("five administrators and five standard users, under the exact ten seeded identifiers")
        void fiveAdministratorsAndFiveStandardUsers() {
            final List<UserSecurity> users = userSecurityRepository.findAll();

            final List<String> administrators = users.stream()
                    .filter(user -> user.getSecUsrType() == UserType.ADMIN)
                    .map(UserSecurity::getSecUsrId)
                    .sorted()
                    .toList();
            final List<String> standardUsers = users.stream()
                    .filter(user -> user.getSecUsrType() == UserType.USER)
                    .map(UserSecurity::getSecUsrId)
                    .sorted()
                    .toList();

            assertThat(administrators)
                    .as("app/jcl/DUSRSECJ.jcl:35-39 carries five rows of type A")
                    .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");
            assertThat(standardUsers)
                    .as("app/jcl/DUSRSECJ.jcl:40-44 carries five rows of type U")
                    .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
        }

        @ParameterizedTest(name = "{0} reads back as {1} {2}, user class {3}")
        @CsvSource({
            "ADMIN001, MARGARET,  GOLD,       ADMIN",
            "ADMIN002, RUSSELL,   RUSSELL,    ADMIN",
            "ADMIN003, RAYMOND,   WHITMORE,   ADMIN",
            "ADMIN004, EMMANUEL,  CASGRAIN,   ADMIN",
            "ADMIN005, GRANVILLE, LACHAPELLE, ADMIN",
            "USER0001, LAWRENCE,  THOMAS,     USER",
            "USER0002, AJITH,     KUMAR,      USER",
            "USER0003, LAURITZ,   ALME,       USER",
            "USER0004, AVERARDO,  MAZZI,      USER",
            "USER0005, LEE,       TING,       USER",
        })
        @DisplayName("every seeded identifier round-trips with its names blank-padded to the CHAR(20) width")
        void everySeededIdentifierRoundTripsWithItsNames(final String userId, final String firstName,
                final String lastName, final UserType expectedType) {

            final UserSecurity user = userSecurityRepository.findById(userId).orElseThrow();

            assertThat(user.getSecUsrId())
                    .as("SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy:18 is also the cluster key, KEYS(8,0) "
                            + "at app/jcl/DUSRSECJ.jcl:65 and KEYLEN 8 at app/catlg/LISTCAT.txt:3883, so an "
                            + "eight-character identifier fills CHAR(8) with no padding left to add")
                    .isEqualTo(userId);
            assertThat(user.getSecUsrFname())
                    .as("SEC-USR-FNAME PIC X(20) at app/cpy/CSUSR01Y.cpy:19: a CHAR(20) column returns its "
                            + "value blank-padded to twenty and is never trimmed")
                    .isEqualTo(blankPadded(firstName, 20));
            assertThat(user.getSecUsrLname())
                    .as("SEC-USR-LNAME PIC X(20) at app/cpy/CSUSR01Y.cpy:20, under the same pad policy")
                    .isEqualTo(blankPadded(lastName, 20));
            assertThat(user.getSecUsrType())
                    .as("SEC-USR-TYPE PIC X(01) at app/cpy/CSUSR01Y.cpy:22, whose domain "
                            + "app/cpy/COCOM01Y.cpy:26-28 closes to A and U")
                    .isEqualTo(expectedType);
        }

        @Test
        @DisplayName("an identifier outside the seed resolves to empty rather than throwing")
        void anAbsentIdentifierResolvesToEmpty() {
            final Optional<UserSecurity> absent = userSecurityRepository.findById("NOSUCHID");

            assertThat(absent)
                    .as("the empty case is the sign-on failure path and a control path rather than an "
                            + "error, so it must not throw")
                    .isEmpty();
            assertThat(userSecurityRepository.existsById("NOSUCHID"))
                    .as("existence must agree with retrieval; two answers from the same key would make "
                            + "either of them useless")
                    .isFalse();
            assertThat(userSecurityRepository.existsById("ADMIN001"))
                    .as("and must agree for a key that is present, so that the assertion above is not "
                            + "passing merely because the check always reports absence")
                    .isTrue();
        }
    }

    /**
     * The user-class mapping: an attribute converter, never an enumerated mapping.
     *
     * <p>Each test here reads the <strong>raw stored character</strong> through a parameterised query rather
     * than reading the property back, and that choice is the whole value of the group. A round trip through
     * the mapping would pass whichever mapping were in place, because the same mechanism that wrote the value
     * reads it: a string-enumerated mapping would write {@code ADMIN} and read {@code ADMIN} back quite
     * happily. Only the raw value can tell {@code A} from {@code ADMIN} and from {@code 0}, so only the raw
     * value can catch the defect.
     */
    @Nested
    @DisplayName("the user class converts to a single character, never to a constant name or an ordinal")
    class UserClassConversion {

        @Test
        @DisplayName("an administrator row stores the single character A")
        void anAdministratorRowStoresTheSingleCharacterA() {
            userSecurityRepository.save(new UserSecurity("ZTEST010", blankPadded("SYNTHETIC", 20),
                    blankPadded("ROW", 20), syntheticBcryptDigest("Aa"), UserType.ADMIN));
            flushAndClear();

            assertThat(storedUserTypeCharacter("ZTEST010"))
                    .as("app/cpy/COCOM01Y.cpy:27 declares 88 CDEMO-USRTYP-ADMIN VALUE 'A', and "
                            + "app/cbl/COSGN00C.cbl:227 moves SEC-USR-TYPE straight into that field. A "
                            + "value of ADMIN here would mean a string-enumerated mapping and a value of 0 "
                            + "an ordinal one; both are wrong")
                    .isEqualTo("A");
            assertThat(userSecurityRepository.findById("ZTEST010").orElseThrow().getSecUsrType())
                    .as("and the stored character must convert back to the constant, so the mapping is "
                            + "proved in both directions rather than only on the way out")
                    .isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("a standard user row stores the single character U")
        void aStandardUserRowStoresTheSingleCharacterU() {
            userSecurityRepository.save(new UserSecurity("ZTEST011", blankPadded("SYNTHETIC", 20),
                    blankPadded("ROW", 20), syntheticBcryptDigest("Uu"), UserType.USER));
            flushAndClear();

            assertThat(storedUserTypeCharacter("ZTEST011"))
                    .as("app/cpy/COCOM01Y.cpy:28 declares 88 CDEMO-USRTYP-USER VALUE 'U'; the ELSE branch "
                            + "at app/cbl/COSGN00C.cbl:235 is the one this value selects")
                    .isEqualTo("U");
            assertThat(userSecurityRepository.findById("ZTEST011").orElseThrow().getSecUsrType())
                    .as("and it must convert back to the standard-user constant")
                    .isEqualTo(UserType.USER);
        }

        @Test
        @DisplayName("across the whole seed the stored user class is exactly the two source characters")
        void theStoredUserClassIsExactlyTheTwoSourceCharacters() {
            final List<String> distinctStored = jdbcTemplate.queryForList(
                    "SELECT DISTINCT sec_usr_type FROM user_security ORDER BY sec_usr_type", String.class);

            assertThat(distinctStored)
                    .as("app/cpy/COCOM01Y.cpy:26-28 declares PIC X(01) with exactly two condition names, so "
                            + "the whole seed must resolve to exactly two distinct stored characters and to "
                            + "these two")
                    .containsExactly("A", "U");
        }
    }

    /**
     * The table's shape, read from the live catalogue rather than from the migration text.
     *
     * <p>Reading the catalogue is deliberate. The migration file states an intention; the catalogue states
     * what the engine actually built, and only the second can contradict the first. Every query here is
     * parameterised on the table name and takes its schema from the connection, so nothing in this group
     * assumes which schema or which database the container happened to create.
     */
    @Nested
    @DisplayName("the table's shape: five columns, one check, no key, no index, no version")
    class SchemaShape {

        @Test
        @DisplayName("exactly five columns, in the declaration order of the copybook")
        void exactlyFiveColumnsInCopybookOrder() {
            assertThat(userSecurityColumnNames())
                    .as("the five mapped items of app/cpy/CSUSR01Y.cpy:18-22, in that order")
                    .containsExactly("sec_usr_id", "sec_usr_fname", "sec_usr_lname", "sec_usr_pwd",
                            "sec_usr_type");
        }

        @Test
        @DisplayName("the named filler is not a column, and neither is a version or a credential-named one")
        void theNamedFillerIsNotAColumn() {
            final List<String> columns = userSecurityColumnNames();

            assertThat(columns)
                    .as("SEC-USR-FILLER PIC X(23) at app/cpy/CSUSR01Y.cpy:23 is a NAMED filler, which is "
                            + "unusual in this corpus and is recorded as a source anomaly. A "
                            + "name does not make it data: its only function is to pad the record to the "
                            + "catalogued 80 bytes, so it is deliberately unmapped")
                    .doesNotContain("sec_usr_filler");
            assertThat(columns)
                    .as("the optimistic-locking column exists on exactly four tables and this is not one of "
                            + "them; the ten inline card images carry no counter, so there is nothing for it "
                            + "to reproduce. Hibernate's validate mode is the second guard here, because a "
                            + "version mapping over a column that does not exist fails context startup")
                    .doesNotContain("version");
            assertThat(columns)
                    .as("and no column may be named after a credential in plaintext terms. The single "
                            + "credential column is sec_usr_pwd, transcribed from SEC-USR-PWD at "
                            + "app/cpy/CSUSR01Y.cpy:21, and it holds a digest; a column whose name announced "
                            + "a stored secret would mean the storage decision had been reversed")
                    .noneMatch(name -> name.contains("plaintext") || name.contains("password"));
        }

        @ParameterizedTest(name = "{0} is {1}({2}) and NOT NULL")
        @CsvSource({
            "sec_usr_id,    character,         8",
            "sec_usr_fname, character,         20",
            "sec_usr_lname, character,         20",
            "sec_usr_pwd,   character varying, 60",
            "sec_usr_type,  character,         1",
        })
        @DisplayName("every column carries the width the PIC clause declares and is NOT NULL")
        void everyColumnCarriesItsDeclaredWidthAndIsNotNull(final String columnName, final String dataType,
                final int width) {

            final String actualType = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?"
                            + " AND column_name = ?",
                    String.class, "user_security", columnName);
            final Integer actualWidth = jdbcTemplate.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?"
                            + " AND column_name = ?",
                    Integer.class, "user_security", columnName);
            final String nullability = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?"
                            + " AND column_name = ?",
                    String.class, "user_security", columnName);

            assertThat(actualType)
                    .as("%s: COBOL has fixed-width character items, so every column but the credential is "
                            + "CHAR; the credential is the documented widening", columnName)
                    .isEqualTo(dataType);
            assertThat(actualWidth)
                    .as("%s: widths come from app/cpy/CSUSR01Y.cpy:18-22, except sec_usr_pwd which is "
                            + "widened from 8 to 60 to hold a digest", columnName)
                    .isEqualTo(width);
            assertThat(nullability)
                    .as("%s: COBOL has no null - a PIC X(n) item always holds its declared width - so every "
                            + "column of this table is NOT NULL", columnName)
                    .isEqualTo("NO");
        }

        @Test
        @DisplayName("the user-class check is the only check constraint on this table")
        void theUserClassCheckIsTheOnlyCheckOnThisTable() {
            assertThat(checkConstraintNames())
                    .as("this is check 4 of the migration's 5. Its domain comes from "
                            + "app/cpy/COCOM01Y.cpy:26-28 and the chain to this column is made by "
                            + "app/cbl/COSGN00C.cbl:227. The other four are on the account, card and "
                            + "customer tables, and a sixth here would exceed the schema's stated budget - "
                            + "the credential's shape rule lives on the entity's pre-write callback instead")
                    .containsExactly("ck_user_security_type");
        }

        @Test
        @DisplayName("the table has no foreign key, inbound or outbound")
        void theTableHasNoForeignKeyInEitherDirection() {
            assertThat(outboundForeignKeyCount())
                    .as("none of the migration's ten foreign keys is declared on this table. In the source a "
                            + "user is not linked to an account, a card or a customer, so inventing such a "
                            + "link would break parity")
                    .isZero();
            assertThat(inboundForeignKeyCount())
                    .as("and none points at it either, which is the direction that is easier to introduce by "
                            + "accident from a later table")
                    .isZero();
        }

        @Test
        @DisplayName("the table carries no index beyond its primary key")
        void theTableCarriesNoIndexBeyondItsPrimaryKey() {
            assertThat(indexNames())
                    .as("the migration creates exactly three non-unique B-tree indexes, one per alternate "
                            + "index in the catalogue, and all three are on other tables. This cluster's "
                            + "attribute line at app/catlg/LISTCAT.txt:3885 carries UNIQUE with no "
                            + "NONUNIQKEY and it has no alternate index, so there is nothing here to replace")
                    .containsExactly("pk_user_security");
        }
    }

    /**
     * Enforcement: each declared constraint refused a value that violates it, proved by provoking it.
     *
     * <p>A constraint that exists in the catalogue but is not enforced would pass every assertion in
     * {@link SchemaShape} and protect nothing, so each one is provoked here with hostile input - an
     * out-of-domain user class, a duplicate key, an over-wide value, a null.
     *
     * <p>Two constraints shape how these tests are written. First, <strong>one violation per test
     * method</strong>: a failed statement aborts the enclosing PostgreSQL transaction, so nothing further can
     * be read or written in the same method, and the surrounding rollback is what cleans up. Second,
     * <strong>assertions match constraint and type names, never the message as a whole</strong>, because
     * PostgreSQL echoes the entire failing row in the detail of a violation. Every provoked row therefore
     * carries only a synthetic digest, so even that echo can never carry credential material.
     */
    @Nested
    @DisplayName("each declared constraint is enforced, with the root cause preserved")
    class ConstraintEnforcement {

        @Test
        @DisplayName("a user class outside A and U is refused by the check constraint")
        void aUserClassOutsideTheSourceDomainIsRefused() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(),
                    "ZTEST030", blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20),
                    syntheticBcryptDigest("Cc"), "Z"));

            assertThat(thrown)
                    .as("app/cpy/COCOM01Y.cpy:26-28 declares only A and U, and the domain is enforced at "
                            + "app/cbl/COSGN00C.cbl:227 where the stored field is moved into the field "
                            + "carrying those condition names. A native insert is used because the entity's "
                            + "property is an enumeration and cannot express a value outside the domain")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the constraint, so that a reader knows which rule fired")
                    .contains("ck_user_security_type");
            assertThat(thrown.getCause())
                    .as("the driver's own exception must be preserved as the cause rather than swallowed or "
                            + "replaced, which is what keeps the SQL state and position available")
                    .isNotNull();
        }

        @Test
        @DisplayName("a duplicate identifier is refused by the primary key")
        void aDuplicateIdentifierIsRefused() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(),
                    "ADMIN001", blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20),
                    syntheticBcryptDigest("Dd"), "A"));

            assertThat(thrown)
                    .as("this is the mechanism by which the legacy add path's duplicate-key handling "
                            + "surfaces: app/cbl/COUSR01C.cbl writes a new user and must be told when the "
                            + "key already exists. A native insert is used because saving an entity under an "
                            + "existing key merges rather than inserts, so it could never collide")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the primary key by its constraint name")
                    .contains("pk_user_security");
            assertThat(thrown.getCause())
                    .as("with the root cause preserved")
                    .isNotNull();
        }

        @Test
        @DisplayName("a nine-character identifier is refused at the database boundary")
        void anOverLongIdentifierIsRefused() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(),
                    "ZTEST0311", blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20),
                    syntheticBcryptDigest("Ee"), "A"));

            assertThat(thrown)
                    .as("SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy:18 and KEYS(8,0) at "
                            + "app/jcl/DUSRSECJ.jcl:65 both fix eight characters, and the 80-byte record "
                            + "cannot carry a ninth. A data exception is translated by SQL-state class, so "
                            + "the assertion is made at the root translated type")
                    .isInstanceOf(DataAccessException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the width that was exceeded")
                    .contains("character(8)");
            assertThat(thrown.getCause())
                    .as("with the root cause preserved")
                    .isNotNull();
        }

        @Test
        @DisplayName("a twenty-one-character first name is refused at the database boundary")
        void anOverLongFirstNameIsRefused() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(),
                    "ZTEST032", "A".repeat(21), blankPadded("ROW", 20),
                    syntheticBcryptDigest("Ff"), "A"));

            assertThat(thrown)
                    .as("SEC-USR-FNAME PIC X(20) at app/cpy/CSUSR01Y.cpy:19 fixes twenty characters")
                    .isInstanceOf(DataAccessException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the width that was exceeded")
                    .contains("character(20)");
            assertThat(thrown.getCause())
                    .as("with the root cause preserved")
                    .isNotNull();
        }

        @Test
        @DisplayName("a two-character user class is refused at the database boundary")
        void anOverLongUserClassIsRefused() {
            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(),
                    "ZTEST033", blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20),
                    syntheticBcryptDigest("Gg"), "AU"));

            assertThat(thrown)
                    .as("SEC-USR-TYPE PIC X(01) at app/cpy/CSUSR01Y.cpy:22 is a single byte, byte 57 of the "
                            + "record; the width refuses a second character before the check constraint even "
                            + "gets to judge the domain")
                    .isInstanceOf(DataAccessException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the width that was exceeded")
                    .contains("character(1)");
            assertThat(thrown.getCause())
                    .as("with the root cause preserved")
                    .isNotNull();
        }

        @ParameterizedTest(name = "a null {0} is refused")
        @CsvSource({
            "sec_usr_id,    1",
            "sec_usr_fname, 2",
            "sec_usr_lname, 3",
            "sec_usr_pwd,   4",
            "sec_usr_type,  5",
        })
        @DisplayName("every column is NOT NULL, the credential column explicitly included")
        void everyColumnIsNotNull(final String columnName, final int position) {
            // One fixed statement with five placeholders; only the argument array varies, so the null moves
            // without any SQL being composed from a value.
            final Object[] values = {
                "ZTEST034", blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20),
                syntheticBcryptDigest("Hh"), "A",
            };
            values[position - 1] = null;

            final Throwable thrown = catchThrowable(() -> jdbcTemplate.update(userSecurityInsert(), values));

            assertThat(thrown)
                    .as("COBOL has no null - a PIC X(n) item always holds its declared width - so every one "
                            + "of the five columns is NOT NULL. A null credential in particular must be "
                            + "impossible, because a row with no digest could never be authenticated against "
                            + "and would sit in the table looking valid")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getMessage())
                    .as("the failure must name the column %s and the rule that fired", columnName)
                    .contains(columnName)
                    .contains("not-null");
            assertThat(thrown.getCause())
                    .as("with the root cause preserved")
                    .isNotNull();
        }

        @Test
        @DisplayName("the entity refuses an over-long identifier before a statement is ever built")
        void theEntityRefusesAnOverLongIdentifierBeforeAnyStatement() {
            final Throwable thrown = catchThrowable(() -> new UserSecurity("ZTEST0351",
                    blankPadded("SYNTHETIC", 20), blankPadded("ROW", 20), syntheticBcryptDigest("Ii"),
                    UserType.ADMIN));

            assertThat(thrown)
                    .as("defence in depth, asserted once: the width the database refuses above is also "
                            + "refused on the way in, so the failure names the property at the call site "
                            + "that caused it instead of surfacing from the driver much later")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("and the message must name the property and the length received")
                    .contains("secUsrId")
                    .doesNotContain("ZTEST0351");
        }
    }

    /**
     * The paged, ordered read that replaces the legacy user-list browse.
     *
     * <p>The page size is injected from {@code carddemo.pagination.user-list-page-size} and is never written
     * into a request as a literal, so the size under test is the size the production service receives.
     *
     * <p>The seed is ten rows and the page size is ten, so one page holds the whole table. That coincidence
     * is worth asserting from both ends rather than trusting: a first page holding ten rows would also be
     * produced by a broken finder that ignored paging entirely, which is why {@code hasNext()} and the empty
     * second page are asserted too.
     */
    @Nested
    @DisplayName("the ordered paged finder that replaces the user-list browse")
    class PagingContract {

        @Test
        @DisplayName("the configured page size reproduces the legacy OCCURS count")
        void theConfiguredPageSizeReproducesTheLegacyOccursCount() {
            assertThat(userListPageSize)
                    .as("app/cbl/COUSR00C.cbl:57 declares 02 USER-REC OCCURS 10 TIMES, which fixes the "
                            + "number of rows the user-list screen paints. This is the one assertion that "
                            + "compares the configured number against the source it transcribes; every "
                            + "request below is built from the injected value, never from a literal, so a "
                            + "drift in configuration surfaces here rather than silently changing which "
                            + "rows a client receives")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the first page holds the whole seed in ascending key order and reports no next page")
        void theFirstPageHoldsTheWholeSeedInAscendingKeyOrder() {
            final Slice<UserSecurity> firstPage =
                    userSecurityRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(0, userListPageSize));

            final List<String> identifiers = firstPage.getContent().stream()
                    .map(UserSecurity::getSecUsrId)
                    .toList();

            assertThat(identifiers)
                    .as("ascending key order is what the legacy browse walked, and because the identifiers "
                            + "are fixed-width the lexicographic order is the VSAM byte order - so the five "
                            + "administrators precede the five standard users")
                    .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
            assertThat(firstPage.hasNext())
                    .as("the first page must not be reported as having a successor when it already holds "
                            + "every row; a finder that fetched one row too many would say otherwise")
                    .isFalse();
            assertThat(firstPage.isFirst())
                    .as("and it must be reported as the first page, which is where the legacy screen opens")
                    .isTrue();
        }

        @Test
        @DisplayName("the second page is empty rather than an error, which is how a caller stops")
        void theSecondPageIsEmpty() {
            final Slice<UserSecurity> secondPage =
                    userSecurityRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(1, userListPageSize));

            assertThat(secondPage.getContent())
                    .as("a page beyond the end returns nothing rather than throwing, which is the boundary "
                            + "case a paging caller relies on to terminate")
                    .isEmpty();
            assertThat(secondPage.hasNext())
                    .as("and reports no further page after it")
                    .isFalse();
        }

        @Test
        @DisplayName("the total element count is ten, taken from the inherited page-returning finder")
        void theTotalElementCountIsTen() {
            // The declared finder returns a Slice, which deliberately carries no total: a slice answers
            // "is there more" with one extra row rather than with a second count query. The total therefore
            // comes from the inherited page-returning finder, which is equally part of this repository's
            // public surface.
            final Page<UserSecurity> firstPage = userSecurityRepository.findAll(
                    PageRequest.of(0, userListPageSize, Sort.by(Sort.Direction.ASC, "secUsrId")));

            assertThat(firstPage.getTotalElements())
                    .as("ten seeded rows, so ten total elements")
                    .isEqualTo(10L);
            assertThat(firstPage.getTotalPages())
                    .as("and one page, because the page size holds the whole table")
                    .isEqualTo(1);
            assertThat(firstPage.getContent().stream().map(UserSecurity::getSecUsrId).toList())
                    .as("the inherited finder must order ascending too when asked to, which is a second and "
                            + "independent path to the same browse order")
                    .isSorted();
        }

        @Test
        @DisplayName("the engine's collation agrees with byte order, so ADMIN001 precedes USER0001")
        void theEngineCollationAgreesWithByteOrder() {
            final List<String> engineOrder = jdbcTemplate.queryForList(
                    "SELECT sec_usr_id FROM user_security ORDER BY sec_usr_id", String.class);

            assertThat(engineOrder)
                    .as("the identifiers are fixed-width CHAR(8), so lexicographic order is the VSAM byte "
                            + "order the legacy browse produced. This asserts the container's collation "
                            + "agrees with that ordering rather than assuming it - the reason the harness "
                            + "pins a glibc image rather than a musl one")
                    .isEqualTo(engineOrder.stream().sorted().toList());
            assertThat(engineOrder)
                    .as("and the administrators sort ahead of the standard users, which is the order the "
                            + "legacy screen opened on")
                    .startsWith("ADMIN001")
                    .endsWith("USER0005");
        }
    }

    /**
     * The locked read that replaces {@code EXEC CICS READ ... UPDATE} on both mutating paths.
     *
     * <p>{@code app/cbl/COUSR02C.cbl} reads the row at {@code :322} with {@code UPDATE} at {@code :328} and
     * rewrites it at {@code :360}; {@code app/cbl/COUSR03C.cbl} does the same at {@code :269}, {@code :275}
     * and deletes at {@code :307}. In both the row is held from the read until the unit of work ends, and the
     * queue is served by {@code UPDATEMODEL(LOCKING)} at {@code app/csd/CARDDEMO.CSD:88-89}.
     * {@link UserSecurityRepository#findByIdForUpdate(String)} is the Java form of that read, and this group
     * is the only place the mechanism is observable end to end.
     *
     * <p>Why this group cannot be replaced by a unit test. A unit test can assert that the annotation is
     * present, and one does; it cannot assert that the provider renders {@code for update} for
     * <em>this</em> entity, that the statement parses, or that the engine actually takes the lock. A
     * {@code @Lock} query that is well formed at the mapping level still fails at run time when the entity
     * carries a mapping the dialect cannot lock, so the annotation and its effect are two separate claims
     * and both are asserted - the second one here, against a real engine.
     *
     * <p>Lock <em>contention</em> is deliberately not demonstrated, exactly as the sibling account and
     * customer groups decline to demonstrate it. A second connection blocking on the first would make the
     * outcome a race and the assertion non-deterministic; the engine's own lock table answers the question
     * that matters without one.
     *
     * <p>This group adds no version column and asserts none. The table has none, the schema-shape group
     * above asserts it has none, and a pessimistic lock is what makes that absence sound: the row cannot be
     * read by a second writer between this transaction's read and its write, so there is no window for the
     * lost update a version column would otherwise be needed to detect.
     */
    @Nested
    @DisplayName("the locked read that replaces READ ... UPDATE on the mutating paths")
    class LockedReadForUpdate {

        @Test
        @DisplayName("the locked read returns the row, managed, with every field identical to the plain read")
        void theLockedReadReturnsTheRowManaged() {
            // Ordered deliberately: the locked read runs first, so the instance in the persistence context
            // is the one it produced. A plain read afterwards is answered from the identity map, and only a
            // managed instance can be returned that way - which is what proves the locked read attached it.
            final Optional<UserSecurity> locked = userSecurityRepository.findByIdForUpdate("ADMIN001");

            assertThat(locked)
                    .as("the read at app/cbl/COUSR02C.cbl:322-328 finds the row before it rewrites it, so "
                            + "the locked form must resolve the same seeded identifier the plain finder does")
                    .isPresent();

            final UserSecurity lockedUser = locked.orElseThrow();
            assertThat(lockedUser.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(lockedUser.getSecUsrFname())
                    .as("the lock changes when the row may be written, never what it contains, so the "
                            + "CHAR(20) blank padding is returned exactly as the plain read returns it")
                    .isEqualTo(blankPadded("MARGARET", 20));
            assertThat(lockedUser.getSecUsrType()).isEqualTo(UserType.ADMIN);
            assertThat(userSecurityRepository.findById("ADMIN001").orElseThrow())
                    .as("the same persistence context returns the identical instance, which it can only do "
                            + "for a managed entity - so the locked read attached it rather than returning a "
                            + "detached projection the update path could not then rewrite")
                    .isSameAs(lockedUser);
        }

        @Test
        @DisplayName("the engine records a RowShareLock, which only SELECT ... FOR UPDATE takes")
        void theEngineRecordsARowShareLock() {
            userSecurityRepository.findByIdForUpdate("ADMIN002");

            assertThat(relationLockModesHeldByThisTransaction())
                    .as("PostgreSQL takes ROW SHARE on the table for SELECT ... FOR UPDATE and ACCESS SHARE "
                            + "for a plain SELECT. The RowShareLock entry is therefore direct evidence that "
                            + "the FOR UPDATE clause reached the engine, which no assertion on the returned "
                            + "row could establish")
                    .contains("RowShareLock");
        }

        @Test
        @DisplayName("a plain read records no RowShareLock, so the assertion above is a real oracle")
        void aPlainReadRecordsNoRowShareLock() {
            userSecurityRepository.findById("ADMIN002");

            assertThat(relationLockModesHeldByThisTransaction())
                    .as("the control for the test above. Without it, a lock table that reported "
                            + "RowShareLock for every read - or a query that matched too loosely - would let "
                            + "the positive assertion pass against a finder carrying no lock at all")
                    .doesNotContain("RowShareLock")
                    .as("and the plain read is genuinely observable, so the negative result above is the "
                            + "absence of a lock rather than the absence of a working query")
                    .contains("AccessShareLock");
        }

        @Test
        @DisplayName("the locked read is what the update path rewrites through, in one unit of work")
        void theLockedReadIsWhatTheUpdatePathRewritesThrough() {
            final UserSecurity held = userSecurityRepository.findByIdForUpdate("USER0001").orElseThrow();
            held.setSecUsrFname(blankPadded("LAWRENCED", 20));
            userSecurityRepository.saveAndFlush(held);
            flushAndClear();

            assertThat(userSecurityRepository.findById("USER0001").orElseThrow().getSecUsrFname())
                    .as("app/cbl/COUSR02C.cbl rewrites at :360 the very record it read for update at :322, "
                            + "so the locked instance must be the one the rewrite carries; the surrounding "
                            + "transaction rolls back, so the seed is intact for every sibling test")
                    .isEqualTo(blankPadded("LAWRENCED", 20));
            assertThat(relationLockModesHeldByThisTransaction())
                    .as("and the lock is still held after the write, because it is released at the end of "
                            + "the unit of work rather than at the end of the statement - which is the whole "
                            + "point of holding it from the read")
                    .contains("RowShareLock");
        }

        @Test
        @DisplayName("the locked read is what the delete path removes, in one unit of work")
        void theLockedReadIsWhatTheDeletePathRemoves() {
            final UserSecurity held = userSecurityRepository.findByIdForUpdate("USER0002").orElseThrow();
            userSecurityRepository.delete(held);
            userSecurityRepository.flush();
            flushAndClear();

            assertThat(userSecurityRepository.existsById("USER0002"))
                    .as("app/cbl/COUSR03C.cbl deletes at :307 the record it read for update at :269, and no "
                            + "self-delete guard exists there or here - the absence is a preserved legacy "
                            + "quirk and adding one would be a behaviour change")
                    .isFalse();
            assertThat(userSecurityRepository.count())
                    .as("the row count falls by exactly one; the surrounding transaction rolls back, so the "
                            + "seed is intact for every sibling test")
                    .isEqualTo(9L);
        }

        @Test
        @DisplayName("the locked read on an absent identifier resolves to empty rather than throwing")
        void theLockedReadOnAnAbsentIdentifierResolvesToEmpty() {
            assertThat(userSecurityRepository.findByIdForUpdate("NOSUCHID"))
                    .as("a locked read of a row that does not exist has nothing to lock. The source treats "
                            + "the same case as a control path - app/cbl/COUSR02C.cbl reports 'User ID NOT "
                            + "found' rather than abending - so the absence is reported, never raised")
                    .isEmpty();
        }
    }

    /**
     * Deletion, and the guard the source does not have.
     *
     * <p>{@code app/cbl/COUSR03C.cbl} never compares the target identifier against the signed-on one: a
     * census of that 359-line program finds <strong>zero</strong> occurrences of {@code CDEMO-USER-ID}, and
     * the delete at {@code :307} is unconditional. A signed-on administrator can therefore delete their own
     * row. That is a preserved legacy quirk, cited to its locator rather than repaired.
     *
     * <p>This group therefore asserts only that a delete of an existing row succeeds. <strong>It does not
     * assert that a self-delete is refused, and no such guard may be added</strong> - adding one would be a
     * behaviour change and is forbidden. The repository is guard-free by construction, having
     * no notion of who is signed on, so there is nothing here to weaken; the prohibition is recorded so that
     * a later reader does not mistake the absence of the assertion for an oversight.
     */
    @Nested
    @DisplayName("deletion, which the source performs with no self-delete guard")
    class DeletionHasNoGuard {

        @Test
        @DisplayName("deleting an existing row succeeds, exactly as the guard-free source path does")
        void deletingAnExistingRowSucceeds() {
            userSecurityRepository.deleteById("ADMIN001");
            flushAndClear();

            assertThat(userSecurityRepository.existsById("ADMIN001"))
                    .as("the read-confirm-delete chain of app/cbl/COUSR03C.cbl issues its delete at :307 "
                            + "with no precondition beyond the row existing")
                    .isFalse();
            assertThat(userSecurityRepository.count())
                    .as("and the row count falls by exactly one; the surrounding transaction rolls back, so "
                            + "the seed is intact for every sibling test")
                    .isEqualTo(9L);
        }
    }
}
