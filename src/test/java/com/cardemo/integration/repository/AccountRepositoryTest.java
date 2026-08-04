/*
 * ******************************************************************
 * Program     : AccountRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier
 * Function    : Proves that AccountRepository and the Account entity
 *               reproduce the ACCTDAT field contract against a real
 *               PostgreSQL 16 engine: the fifty seeded rows decoded from
 *               the frozen zoned-decimal fixture, the deliberately
 *               misspelled expiry column, the legitimately negative
 *               cycle-debit accumulator, the universally blank account
 *               group identifier, the non-numeric postal code, the two
 *               concurrency guards, the primary key, the single check
 *               constraint this table carries, and the character and
 *               numeric width boundaries. Asserts persistence, mapping
 *               and constraint behaviour only; every service, batch and
 *               screen rule is cited and left to its own tier.
 * Source      : app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, key 11, RECLN 300;
 *               ACCT-EXPIRAION-DATE misspelled at :11),
 *               app/catlg/LISTCAT.txt:59 (KEYLEN 11, AVGLRECL 300) and
 *               :61 (UNIQUE, no NONUNIQKEY),
 *               app/jcl/ACCTFILE.jcl (KEYS(11 0), RECORDSIZE(300 300)),
 *               app/csd/CARDDEMO.CSD:1-2 (CICS FILE ACCTDAT),
 *               app/cbl/COACTUPC.cbl (9600-WRITE-PROCESSING at :3888,
 *               9700-CHECK-CHANGE-IN-REC at :4109-:4193, :193 status
 *               domain), app/cbl/COACTVWC.cbl, app/cbl/CBTRN02C.cbl
 *               (:403-:410 over-limit, :545-:560 signed accumulator),
 *               app/cbl/CBACT04C.cbl (:350-:370 cycle reset, :436-:438
 *               DEFAULT fallback), app/cbl/CBACT01C.cbl,
 *               app/cbl/COBIL00C.cbl (:224, :234),
 *               app/data/ASCII/acctdata.txt (50 x 300),
 *               CONTRIBUTING.md:33-34, NOTICE, LICENSE @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;

/**
 * Integration coverage for {@link AccountRepository} and the {@code account} table it maps, executed against
 * a real PostgreSQL 16 engine supplied by {@link AbstractRepositoryIntegrationTest}.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code account} is the replacement for the VSAM cluster {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS},
 * whose physical specification is catalogued at {@code app/catlg/LISTCAT.txt:59} as {@code KEYLEN 11} and
 * {@code AVGLRECL 300} and re-stated by the defining utility job {@code app/jcl/ACCTFILE.jcl} as
 * {@code KEYS(11 0)} and {@code RECORDSIZE(300 300)}. It is exposed online as the CICS file {@code ACCTDAT}
 * ({@code app/csd/CARDDEMO.CSD:1-2}), one of exactly eight file definitions in the resource group, and it is
 * the most heavily referenced record in the corpus: {@code COACTUPC}, {@code COACTVWC}, {@code COBIL00C},
 * {@code CBTRN02C}, {@code CBACT04C}, {@code CBACT01C} and {@code CBSTM03A} all read or rewrite it. It is
 * also the foreign-key root of the schema - {@code card}, {@code card_cross_reference} and
 * {@code transaction_category_balance} each reference it, and it references nothing - which is why a row
 * inserted by a test here needs no parent row.
 *
 * <p>The scope is deliberately one concern: <strong>persistence, mapping and constraint behaviour</strong>.
 * Everything asserted below is a property of the entity, the repository or the database. The rules that
 * merely <em>use</em> this table are cited so the reader can find them and are asserted nowhere in this
 * file, because duplicating them here would test the same rule twice and pin it in the wrong tier.
 *
 * <h3>The field contract, and the four preserved legacy quirks it carries</h3>
 *
 * <p>All thirteen mapped properties derive from {@code app/cpy/CVACT01Y.cpy}, whose thirteen declarations
 * sum to the catalogued 300 bytes as 122 declared plus a 178-byte {@code FILLER}. Four of them are quirks
 * that a well-meaning implementation would silently repair, so each has an explicit test rather than only a
 * comment. Rule 1 Clause B requires a test for any non-trivial bug fix; preserving a defect on purpose is
 * the same obligation pointing the other way.
 *
 * <ol>
 *   <li><p><strong>The expiry column is misspelled, and the misspelling is the contract. Severity
 *       High.</strong> {@code app/cpy/CVACT01Y.cpy:11} declares {@code ACCT-EXPIRAION-DATE} - "EXPIRAION",
 *       missing the second {@code T}. The property is therefore {@code expiraionDate} and the column
 *       {@code acct_expiraion_date}. The spelling is not incidental: {@code app/cpy/CVACT02Y.cpy:9} repeats
 *       it as {@code CARD-EXPIRAION-DATE}, and {@code app/cbl/CBTRN02C.cbl:414} consumes it directly in the
 *       posting validation, {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. "Correcting" it does
 *       not produce a failed assertion, which is what makes it dangerous - because
 *       {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, it aborts context
 *       startup for the whole tier instead. {@link PreservedMisspelling} asserts the real column exists and
 *       the corrected spelling does not, so the reason is stated in a failure message rather than inferred
 *       from a startup stack trace.</li>
 *   <li><p><strong>The cycle-debit accumulator is signed and legitimately negative. Asserting otherwise is
 *       a Blocker.</strong> {@code app/cbl/CBTRN02C.cbl:545-560} ({@code 2800-UPDATE-ACCOUNT-REC}) adds the
 *       transaction amount to the balance, then adds it to the cycle <em>credit</em> when it is
 *       non-negative and to the cycle <em>debit</em> otherwise - so a negative amount is added to the debit
 *       accumulator and the accumulator holds negative values. That is precisely why the over-limit test at
 *       {@code :403-:410} <em>subtracts</em> it: the temporary balance is cycle credit minus cycle debit
 *       plus transaction amount. Normalising the sign, or adding a non-negative check constraint, would
 *       break that arithmetic. There is no such constraint in {@code V1}, this file contains no
 *       absolute-value call, and {@link SignedCycleDebitAccumulator} asserts positively that a negative
 *       value round-trips with its sign. The formula itself belongs to
 *       {@code unit/batch/TransactionPostingProcessorTest} and is not re-implemented here.</li>
 *   <li><p><strong>The account group identifier is blank on every seeded row, and blankness is permitted.
 *       Severity High.</strong> Bytes 113 to 122 of all fifty fixture records are spaces, and {@code V1}
 *       declares the column {@code NOT NULL} with no non-blank check, no bean-validation constraint and no
 *       {@code DEFAULT} literal. The causal consequence is worth naming because it inverts an easy
 *       assumption: since every account resolves to a blank group, the disclosure-group lookup in
 *       {@code app/cbl/CBACT04C.cbl:436-438} - which substitutes the literal {@code 'DEFAULT'} when the
 *       keyed read returns file status {@code '23'} - is the <em>normal</em> path for interest
 *       calculation rather than an edge case. That fallback is asserted by the interest tier, not here.</li>
 *   <li><p><strong>The postal code is non-numeric on every seeded row. Severity High.</strong> Bytes 103 to
 *       112 are the literal {@code A000000000} on all fifty records. {@code ACCT-ADDR-ZIP} is
 *       {@code PIC X(10)}, a character field, so the value is valid data and not corruption. There is no
 *       digits-only constraint and adding one is a Blocker; {@link NonNumericPostalCode} asserts both that
 *       the fixture literal survived the seed and that a further non-numeric value persists.</li>
 *   </ol>
 *
 * <h3>Dates are text, never {@code LocalDate}</h3>
 *
 * <p>{@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} and {@code ACCT-REISSUE-DATE} are each
 * {@code PIC X(10)} and map to {@code CHAR(10)} columns and {@link String} properties. The proof is
 * behavioural rather than typographical: {@code 9700-CHECK-CHANGE-IN-REC}
 * ({@code app/cbl/COACTUPC.cbl:4109-4193}) never compares a date as a whole string. It compares
 * {@code (1:4)} against a snapshot year, {@code (6:2)} against a month and {@code (9:2)} against a day, for
 * all three dates. Those offsets are only meaningful if the stored form is {@code yyyy-MM-dd} with dashes at
 * positions 5 and 8, and a {@code LocalDate} mapping would destroy the substring contract outright.
 * {@link DatesAreText} asserts the shape at exactly those offsets and asserts the catalogue reports a
 * character type; it parses nothing into a temporal type.
 *
 * <h3>Money is decimal, and equality is by comparison</h3>
 *
 * <p>All five monetary properties are {@code PIC S9(10)V99}, hence {@code NUMERIC(12,2)} and
 * {@link BigDecimal}, with {@code RoundingMode.HALF_EVEN} wherever a rescale is required. Two neighbouring
 * precisions exist elsewhere in the schema and are easy to conflate: {@code S9(09)V99} becomes
 * {@code NUMERIC(11,2)} for transaction and category-balance amounts, and {@code S9(04)V99} becomes
 * {@code NUMERIC(6,2)} for the disclosure interest rate. Neither appears on this table.
 *
 * <p><strong>Every monetary comparison here is by {@code compareTo} and never by {@code equals}. Severity
 * Medium if reversed.</strong> {@code new BigDecimal("194.00").equals(new BigDecimal("194.0"))} is
 * {@code false} while their {@code compareTo} is {@code 0}, and a {@code NUMERIC(12,2)} column returns
 * scale 2 regardless of the scale that was written, so an assertion phrased with {@code equals} - which is
 * what AssertJ's plain {@code isEqualTo} uses on a {@code BigDecimal} - is a latent scale trap that passes
 * until someone writes an unscaled literal. This file uses {@code isEqualByComparingTo} for scalars and
 * {@link Comparator#comparing} over the natural order for extremes, both of which route through
 * {@code compareTo}. No {@code float} or {@code double} appears anywhere in it, in any assertion, variable,
 * cast or literal.
 *
 * <h3>Concurrency is two layers, and the store-level layer alone is insufficient</h3>
 *
 * <p>{@code account} is one of exactly four tables carrying {@code version BIGINT}, with {@code card},
 * {@code customer} and {@code "transaction"}. {@link OptimisticLocking} asserts that the counter increments
 * on a real update and that a stale write is refused.
 *
 * <p><strong>That is only half of the mechanism, and the missing half is load-bearing.</strong> A version
 * counter detects <em>that</em> a row changed. The source detects <em>which business fields</em> changed:
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4193}) evaluates twelve account
 * predicates against a snapshot captured when the screen was first populated, and abandons the write on any
 * mismatch. The two guarantees differ observably - a concurrent write that set a field back to its original
 * value passes the source's check and fails a version check - so both layers are required and neither
 * substitutes for the other. The field-by-field comparison, including the deliberate case asymmetry whereby
 * the group identifier is compared through {@code FUNCTION LOWER-CASE} at {@code :4139-:4140} while the
 * customer text fields are compared through {@code FUNCTION UPPER-CASE}, lives in
 * {@code com.cardemo.service.account.AccountUpdateService} and is asserted by {@code unit/service}. It is
 * neither implemented nor asserted here.
 *
 * <p>{@link PessimisticRead} covers the other guard, {@link AccountRepository#findByIdForUpdate(Long)},
 * which replaces the {@code READ ... UPDATE} at {@code app/cbl/COACTUPC.cbl:3894-3906} whose failure exit
 * is at {@code :3910-:3916}. It asserts that the locked read returns the row as a managed instance and
 * nothing more. <strong>No second thread and no second connection is used to demonstrate contention</strong>,
 * because a contention race is not deterministic and Rule 1 Clause A puts determinism first; a passing
 * flaky test is worse than an absent one.
 *
 * <h3>Rules cited here and asserted elsewhere</h3>
 *
 * <p>Named so that the boundary is explicit rather than implied: the seven-step write sequence beginning
 * {@code app/cbl/COACTUPC.cbl:3888}; the asymmetric rollback, where {@code SYNCPOINT ROLLBACK} fires on the
 * customer rewrite failure at {@code :4098-:4102} but not on the account rewrite failure at
 * {@code :4079-:4080}, which one transactional scope reproduces without any conditional logic; the
 * over-limit formula at {@code app/cbl/CBTRN02C.cbl:403-410}; the interest cycle reset at
 * {@code app/cbl/CBACT04C.cbl:350-370}, which zeroes both accumulators before the rewrite; and bill payment
 * driving the balance to exactly zero by paying the whole balance at {@code app/cbl/COBIL00C.cbl:224} and
 * {@code :234}. All of them belong to {@code unit/service} and {@code unit/batch}.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone with
 * {@code ./mvnw -q test-compile}, which is the fastest check that the zero-warning settings are still
 * satisfied.
 *
 * <p><strong>This class is bound to Failsafe by its path, and the binding is silent when broken.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java}
 * and runs it at {@code integration-test} and {@code verify} even though the name keeps the {@code Test}
 * suffix; {@code maven-surefire-plugin} 3.5.4 owns {@code **}{@code /unit/}{@code **} and explicitly
 * excludes this tree. A class moved up to {@code com.cardemo.integration}, to {@code com.cardemo}, or
 * directly into {@code src/test/java} matches neither include set, is collected by neither plugin and simply
 * never runs - the build stays green, both plugins report success and there is no error and no output to
 * notice. Do not rename or relocate this file. After a run, confirm the class is named in
 * {@code target/failsafe-reports}; a passing build that never executed it proves nothing.
 *
 * <p><strong>A reachable Docker socket is a prerequisite of this tier</strong>, because the harness starts a
 * real PostgreSQL 16 container and a reaper container. Where a host JDK is not provisioned the identical
 * build runs in the pinned image, and produces the same result because every plugin and every non-managed
 * dependency version is pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration. It contains no database host, port, database name, user name,
 * password or JDBC URL, performs no environment-variable read and no system-property read or write, and
 * declares no container, no context annotation, no property source and <strong>no {@code static} field of
 * any kind</strong>. All of that belongs to {@link AbstractRepositoryIntegrationTest} and is inherited.
 *
 * <ul>
 *   <li>Profile {@code test}; the engine is PostgreSQL 16 pinned by image digest in the harness, on the
 *       Debian rather than the Alpine variant so that text collation matches what ships.</li>
 *   <li><strong>No clock is read at all</strong> - not the ambient one, and not even the harness's injected
 *       fixed one. Every assertion here is against the fifty fixed seeded rows, the frozen fixture bytes or
 *       a literal date string, so there is no instant to obtain and reading one would be dead weight. The
 *       stronger property is what is actually claimed: no current instant, no current date, no millisecond
 *       counter and no {@code java.util.Date} appears in this file. That matters more than usual on this
 *       table, because the three date fields are compared as text and any assertion phrased against "today"
 *       would fail on day, month and year boundaries. Should a future test here genuinely need an instant,
 *       the harness's fixed {@code 2022-06-10T19:27:53Z} UTC clock is the tier's only sanctioned source and
 *       must be used instead of the ambient one.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate}, {@code spring.jpa.open-in-view: false},
 *       {@code spring.jpa.show-sql: false}, Hibernate JDBC time zone {@code UTC}.</li>
 *   <li>Flyway applies exactly three migrations - {@code V1__create_schema.sql},
 *       {@code V2__create_indexes.sql}, {@code V3__seed_data.sql} - with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and {@code baseline-on-migrate: false}.
 *       The {@code BATCH_*} tables come from {@code spring.batch.jdbc.initialize-schema}, not from a fourth
 *       migration, and no fourth migration may be added.</li>
 *   <li>Money comparison policy: {@code compareTo}, never {@code equals}. Character comparison policy:
 *       {@code CHAR(n)} is blank-padded on read and strips <em>trailing</em> blanks on write, so a
 *       ten-character column returns ten characters and a value with trailing spaces is accepted while a
 *       value with a non-blank overflow is refused. {@link WidthAndPrecisionBoundaries} asserts both
 *       halves.</li>
 *   <li>Isolation is transactional rollback, inherited from the harness. There is no {@code @Sql}, no
 *       {@code TRUNCATE}, no {@code deleteAll} and no context-dirtying annotation here, and none may be
 *       added: three foreign keys point at this table, so a committed test row would be visible to, and
 *       could be referenced by, a sibling class.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test aborts before running, with a container or Docker error.</em> No reachable Docker
 *       socket. State the blocker rather than reporting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version resolves.</em> The
 *       <strong>Blocker</strong>-severity trap of the migration, whose remedy has two halves that are both
 *       required: pin 2.0.3 by overriding the version property the Spring Boot parent manages, never by
 *       importing a second bill of materials, because two competing imports resolve in an ordering-dependent
 *       way that can silently select the parent-managed 1.x line; and use only the prefixed module
 *       coordinates {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter}, because the bare 1.x
 *       identifiers do not exist on the 2.x line. Overriding without renaming resolves artefacts that are
 *       not published; renaming without overriding resolves the wrong version. Both halves are already in
 *       place in the root {@code pom.xml}, which is root-owned and must not be edited from here.</li>
 *   <li><em>The build fails on something trivial.</em> Compilation runs with {@code -Xlint:all} and
 *       {@code -Werror} and {@code failOnWarning}, and that reaches test compilation, so a single unused
 *       import, a raw type, an unchecked cast or a deprecation is a build failure rather than a warning.
 *       Note in particular that handing an array to a varargs {@code Object...} bind-parameter list warns as
 *       an inexact-type invocation; every query here binds scalars for that reason.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a column name.</em> {@code validate}
 *       compares type codes, so a {@code Long} over {@code NUMERIC(11)} can fail where {@code BIGINT}
 *       passes. <strong>The fix is upstream</strong>, in {@code V1__create_schema.sql} or in the entity
 *       mapping. Never widen a column to silence it and never patch this test. Severity
 *       <strong>Medium</strong>.</li>
 *   <li><em>Context startup fails naming {@code acct_expiraion_date}.</em> Someone corrected the spelling in
 *       one of the entity, the migration or the seed but not all three. Restore the source spelling from
 *       {@code app/cpy/CVACT01Y.cpy:11}; it is deliberate.</li>
 *   <li><em>A monetary assertion fails on a value that looks identical.</em> A scale mismatch compared with
 *       {@code equals}. Use {@code isEqualByComparingTo}.</li>
 *   <li><em>An assertion demands a non-negative {@code acct_curr_cyc_debit}.</em> Delete it. The column is
 *       signed by design; see the second quirk above. Severity <strong>Blocker</strong>.</li>
 *   <li><em>A fixture read returns nothing, or fails far from its cause.</em> The harness resolves fixtures
 *       by bare classpath-root name against a census, so a wrong name fails immediately and names the
 *       resource. The canonical mistake is spelling the daily transaction fixture after the mainframe
 *       dataset {@code DALYTRAN}; the file is {@code dailytran.txt}. This class reads only
 *       {@code acctdata.txt}.</li>
 *   <li><em>Fixture records are shorter than 300 characters.</em> A trailing-whitespace cleanup ran over
 *       {@code src/test/resources/acctdata.txt}. Every record ends in a 188-space run - a 10-space group
 *       identifier followed by the 178-byte {@code FILLER} - so trimming collapses 300 columns to 112 and
 *       every position-based read after it is wrong. The repository's {@code .editorconfig} exempts only
 *       Markdown from that cleanup, so it does not protect this file; the fixture must never be trimmed,
 *       normalised, re-encoded, copied or edited.</li>
 *   <li><em>A later assertion in the same method fails with "current transaction is aborted".</em>
 *       PostgreSQL aborts the whole transaction on the first failed statement, and Hibernate marks the
 *       persistence context rollback-only after a failed flush. Each test below therefore provokes at most
 *       one violation and provokes it as its last action.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are stated plainly as <strong>Not available</strong> rather than filled in, because
 * inventing either would manufacture a false oracle and Rule 1 Clause F requires the gap to be named.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured legacy
 *       output exists anywhere in this repository; searches across expected, baseline, golden, {@code .out}
 *       and system-output name patterns, and across the reject, report, statement and HTML dataset names,
 *       return only dataset <em>definition</em> members and no captured data. Closing it would need a
 *       captured 430-byte reject dataset from a real posting run at a known input state, together with the
 *       resulting transaction, account and category-balance images. Until that exists: this file creates no
 *       baseline file and fabricates no expected bytes. Generating a baseline by running this
 *       implementation and asserting against its own output is circular and forbidden, and hand-simulating
 *       the posting program is inadmissible - two defensible models of it over these same fixtures
 *       disagree, at 13 rejects against 38, and a figure that moves with the model is not an
 *       oracle.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> Across
 *       the 28 programs of {@code app/cbl} the literal file status {@code '35'} occurs <strong>0</strong>
 *       times and {@code DFHRESP(NOTOPEN)} occurs <strong>0</strong> times. The condition is
 *       specification-derived only, so no test for it is invented here and no parity claim is made for
 *       it.</p></li>
 *   </ol>
 *
 * <h2>Side effects and thread safety</h2>
 *
 * <p>Each test writes only inside its own transaction, which the harness rolls back, so nothing this class
 * does is visible to any other test or to another connection. No test starts a thread, opens a connection or
 * commits. The only mutable state is the two injected collaborators, both of which are transaction-aware
 * proxies supplied per test instance.
 */
@DisplayName("AccountRepository against PostgreSQL 16: the ACCTDAT field contract, its constraints and its "
        + "four preserved quirks")
class AccountRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test, over the eleven-digit key catalogued at {@code LISTCAT.txt:59}. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Reads catalogue metadata and writes below the entity, neither of which the repository can express.
     *
     * <p>Two jobs only. Catalogue metadata is how the misspelled column name and the character type of the
     * three date columns are asserted as facts about the database rather than as facts about the Java
     * mapping. Writing below the entity is how the {@code CHAR} and {@code NUMERIC} boundaries are reached
     * at all: the entity guards its own widths and precisions and refuses an over-long or over-precise value
     * before the database ever sees it, so proving that PostgreSQL enforces the same limits - and that it
     * treats a trailing blank differently from a non-blank overflow - requires going past the guard. Every
     * statement issued through it binds its parameters; no value is concatenated into SQL.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Builds a valid, unsaved account shaped like a seeded row, for a caller-chosen key.
     *
     * <p>Shaped like a seeded row deliberately: the status is {@code 'Y'}, both cycle accumulators are zero,
     * the postal code is the fixture's {@code A000000000} and the group identifier is ten spaces, which is
     * what all fifty rows of {@code app/data/ASCII/acctdata.txt} carry. A test that cares about one field
     * sets that field and inherits a realistic row for the rest. All three dates are supplied in the
     * {@code yyyy-MM-dd} form the substring comparison at {@code app/cbl/COACTUPC.cbl:4109-4193} requires,
     * and all five monetary values at scale 2, so nothing here relies on a guard being lenient.
     *
     * <p>Keys are chosen by the caller in the eleven-digit space above the seeded range 1 to 50, so a new
     * row can never collide with the seed except where a test is deliberately provoking that collision.
     *
     * @param accountId the eleven-digit key to assign, within the {@code PIC 9(11)} domain
     * @return a new unsaved {@link Account} whose {@code version} is {@code null}, which is what makes
     *         Spring Data treat it as new and issue an insert rather than a merge
     */
    private Account seedShapedAccount(long accountId) {
        return new Account(
                accountId,
                "Y",
                new BigDecimal("194.00"),
                new BigDecimal("2020.00"),
                new BigDecimal("1020.00"),
                "2014-11-20",
                "2025-05-20",
                "2025-05-20",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "A000000000",
                "          ");
    }

    /**
     * Counts the columns of table {@code account} carrying the given name, through a bound query.
     *
     * @param columnName the column name to look for; compared exactly, so case matters
     * @return {@code 1} when the column exists and {@code 0} when it does not, never negative
     */
    private long accountColumnCount(String columnName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Long.class,
                "account",
                columnName);
        return count == null ? 0L : count;
    }

    /**
     * Returns the catalogue's data type for a column of table {@code account}, through a bound query.
     *
     * @param columnName the column to describe; must exist, or the query yields no row
     * @return the {@code information_schema} type name, for example {@code character} or {@code numeric}
     */
    private String accountColumnDataType(String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class,
                "account",
                columnName);
    }

    /**
     * Asserts that a date property carries the dashed ten-character shape the source's substring comparison
     * depends on.
     *
     * <p>The three offsets are exactly the ones {@code 9700-CHECK-CHANGE-IN-REC} uses at
     * {@code app/cbl/COACTUPC.cbl:4109-4193}: {@code (1:4)} for the year, {@code (6:2)} for the month and
     * {@code (9:2)} for the day, which in zero-based Java are {@code [0,4)}, {@code [5,7)} and {@code [8,10)}
     * with dashes at index 4 and index 7. Nothing is parsed into a temporal type, because the stored form is
     * text and asserting it as text is the point.
     *
     * @param value    the date property value to check; must be the raw stored string
     * @param property the property name, used so a failure says which of the three dates broke
     */
    private void assertDashedTenCharacterDate(String value, String property) {
        assertThat(value)
                .as(property + " is PIC X(10) text, compared as (1:4)/(6:2)/(9:2) substrings at "
                        + "COACTUPC.cbl:4109-4193, so it must be exactly ten characters")
                .hasSize(10);
        assertThat(value.substring(0, 4))
                .as(property + " year segment, the (1:4) comparison")
                .containsOnlyDigits();
        assertThat(value.charAt(4))
                .as(property + " needs a dash at position 5 for the (6:2) month comparison to land")
                .isEqualTo('-');
        assertThat(value.substring(5, 7))
                .as(property + " month segment, the (6:2) comparison")
                .containsOnlyDigits();
        assertThat(value.charAt(7))
                .as(property + " needs a dash at position 8 for the (9:2) day comparison to land")
                .isEqualTo('-');
        assertThat(value.substring(8, 10))
                .as(property + " day segment, the (9:2) comparison")
                .containsOnlyDigits();
    }

    /**
     * The fifty rows {@code V3} decoded from the frozen fixture, and the anchors that prove the decode was
     * position-aware rather than a text substitution.
     */
    @Nested
    @DisplayName("The seed: fifty rows decoded from acctdata.txt by PIC position")
    class SeededRows {

        @Test
        @DisplayName("the seed loaded exactly the fifty records the fixture carries")
        void theSeedLoadedExactlyFiftyRows() {
            assertThat(accountRepository.count())
                    .as("app/data/ASCII/acctdata.txt is 15050 bytes of 50 records at 300 columns plus a "
                            + "line feed each, and LISTCAT.txt:64 reports REC-TOTAL 50 for the cluster")
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("the first account round-trips with the values its overpunch signs denote")
        void theFirstAccountRoundTripsWithTheDecodedValues() {
            Account first = accountRepository.findById(1L).orElseThrow();

            assertThat(first.getAccountId())
                    .as("ACCT-ID PIC 9(11) at bytes 1-11 of acctdata.txt record 1 is 00000000001")
                    .isEqualTo(1L);
            assertThat(first.getActiveStatus())
                    .as("ACCT-ACTIVE-STATUS PIC X(01) at byte 12")
                    .isEqualTo("Y");
            assertThat(first.getCurrentBalance())
                    .as("ACCT-CURR-BAL at bytes 13-24 is 00000001940{ and the trailing { overpunch is +0, "
                            + "so the value is +194.00")
                    .isEqualByComparingTo("194.00");
            assertThat(first.getCreditLimit())
                    .as("ACCT-CREDIT-LIMIT at bytes 25-36 is 00000020200{, so +2020.00")
                    .isEqualByComparingTo("2020.00");
            assertThat(first.getCashCreditLimit())
                    .as("ACCT-CASH-CREDIT-LIMIT at bytes 37-48 is 00000010200{, so +1020.00")
                    .isEqualByComparingTo("1020.00");
            assertThat(first.getExpiraionDate())
                    .as("ACCT-EXPIRAION-DATE (sic, CVACT01Y.cpy:11) at bytes 59-68")
                    .isEqualTo("2025-05-20");
            assertThat(first.getOpenDate())
                    .as("ACCT-OPEN-DATE at bytes 49-58")
                    .isEqualTo("2014-11-20");
            assertThat(first.getReissueDate())
                    .as("ACCT-REISSUE-DATE at bytes 69-78")
                    .isEqualTo("2025-05-20");
        }

        @Test
        @DisplayName("the last account round-trips too, so the decode held across the whole fixture")
        void theLastAccountRoundTripsWithTheDecodedValues() {
            Account last = accountRepository.findById(50L).orElseThrow();

            assertThat(last.getCurrentBalance())
                    .as("record 50 of acctdata.txt carries +492.00 as its current balance")
                    .isEqualByComparingTo("492.00");
            assertThat(last.getCreditLimit())
                    .as("record 50 carries +6169.00 as its credit limit")
                    .isEqualByComparingTo("6169.00");
        }

        @Test
        @DisplayName("the credit-limit extremes are the fixture's own, which a mis-decode would not preserve")
        void theCreditLimitExtremesMatchTheFixture() {
            List<Account> all = accountRepository.findAll();

            Account lowest = all.stream()
                    .min(Comparator.comparing(Account::getCreditLimit))
                    .orElseThrow();
            Account highest = all.stream()
                    .max(Comparator.comparing(Account::getCreditLimit))
                    .orElseThrow();

            // Comparator.comparing over a Comparable routes through compareTo, never equals, so the extremes
            // are chosen by numeric value rather than by representation.
            assertThat(lowest.getCreditLimit())
                    .as("the smallest credit limit across the 50 fixture records is +120.00")
                    .isEqualByComparingTo("120.00");
            assertThat(lowest.getAccountId())
                    .as("and it belongs to account 30")
                    .isEqualTo(30L);
            assertThat(highest.getCreditLimit())
                    .as("the largest credit limit across the 50 fixture records is +9750.00")
                    .isEqualByComparingTo("9750.00");
            assertThat(highest.getAccountId())
                    .as("and it belongs to account 39")
                    .isEqualTo(39L);
        }

        @Test
        @DisplayName("the earliest expiry date is the fixture's minimum, compared as text")
        void theEarliestExpiryDateIsTheFixtureMinimum() {
            // A yyyy-MM-dd string sorts chronologically under natural order, which is exactly why the source
            // can compare these dates as text at CBTRN02C.cbl:414 without converting them.
            String earliest = accountRepository.findAll().stream()
                    .map(Account::getExpiraionDate)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();

            assertThat(earliest)
                    .as("the minimum ACCT-EXPIRAION-DATE across the 50 fixture records")
                    .isEqualTo("2023-01-06");
        }

        @Test
        @DisplayName("both cycle accumulators are zero on every seeded row, as the fixture has them")
        void bothCycleAccumulatorsAreZeroOnEverySeededRow() {
            assertThat(accountRepository.findAll())
                    .hasSize(50)
                    .allSatisfy(account -> {
                        assertThat(account.getCurrentCycleCredit())
                                .as("ACCT-CURR-CYC-CREDIT at bytes 79-90 is 00000000000{ on all 50 records")
                                .isEqualByComparingTo("0.00");
                        assertThat(account.getCurrentCycleDebit())
                                .as("ACCT-CURR-CYC-DEBIT at bytes 91-102 is 00000000000{ on all 50 records")
                                .isEqualByComparingTo("0.00");
                    });
        }

        @Test
        @DisplayName("a key outside the seeded range resolves to empty rather than throwing or defaulting")
        void anAbsentKeyResolvesToEmpty() {
            assertThat(accountRepository.findById(999_999L))
                    .as("the seed covers keys 1 to 50 only, and an absent key is an empty Optional, not an "
                            + "exception and not a zero-valued account")
                    .isEmpty();
        }
    }

    /**
     * The misspelled expiry column. Severity <strong>High</strong>: correcting it aborts context startup for
     * the whole tier rather than failing one assertion, so the reason is asserted explicitly here.
     */
    @Nested
    @DisplayName("The preserved misspelling: ACCT-EXPIRAION-DATE, missing its second T")
    class PreservedMisspelling {

        @Test
        @DisplayName("the misspelled column is the one that exists in the database")
        void theMisspelledColumnIsTheRealOne() {
            assertThat(accountColumnCount("acct_expiraion_date"))
                    .as("CVACT01Y.cpy:11 spells the field ACCT-EXPIRAION-DATE (sic), so the column is "
                            + "acct_expiraion_date; the spelling is the frozen field contract, is echoed by "
                            + "CVACT02Y.cpy:9 as CARD-EXPIRAION-DATE, and is consumed verbatim by "
                            + "CBTRN02C.cbl:414")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the correctly spelled column does not exist, and must never be introduced")
        void theCorrectlySpelledColumnDoesNotExist() {
            assertThat(accountColumnCount("acct_expiration_date"))
                    .as("a column named acct_expiration_date would mean the source misspelling at "
                            + "CVACT01Y.cpy:11 was 'corrected'; because ddl-auto is validate in every "
                            + "profile that does not fail an assertion, it fails context startup for the "
                            + "entire tier")
                    .isZero();
        }

        @Test
        @DisplayName("the entity maps the misspelled column, so the property round-trips through it")
        void theEntityMapsTheMisspelledColumn() {
            String throughTheEntity = accountRepository.findById(1L).orElseThrow().getExpiraionDate();
            String throughTheColumn = jdbcTemplate.queryForObject(
                    "SELECT acct_expiraion_date FROM account WHERE acct_id = ?", String.class, 1L);

            assertThat(throughTheEntity)
                    .as("the expiraionDate property and the acct_expiraion_date column are the same datum, "
                            + "which is what makes the CBTRN02C.cbl:414 expiry comparison reproducible")
                    .isEqualTo(throughTheColumn);
        }
    }

    /**
     * The universally blank account group identifier. Severity <strong>High</strong>, because a well-meaning
     * non-blank constraint would reject all fifty seeded rows.
     */
    @Nested
    @DisplayName("The blank group identifier: ten spaces on every row, and permitted")
    class BlankGroupIdentifier {

        @Test
        @DisplayName("every seeded group identifier is ten blank characters")
        void everySeededGroupIdentifierIsBlank() {
            assertThat(accountRepository.findAll())
                    .hasSize(50)
                    .allSatisfy(account -> assertThat(account.getGroupId())
                            .as("ACCT-GROUP-ID PIC X(10) at bytes 113-122 is ten spaces on all 50 records "
                                    + "of acctdata.txt, and CHAR(10) returns the full padded width")
                            .hasSize(10)
                            .isBlank());
        }

        @Test
        @DisplayName("a blank group identifier persists, because no constraint forbids it")
        void aBlankGroupIdentifierPersists() {
            Account blankGroup = seedShapedAccount(90_000_000_001L);
            blankGroup.setGroupId("          ");

            accountRepository.saveAndFlush(blankGroup);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_001L).orElseThrow().getGroupId())
                    .as("V1 declares acct_group_id NOT NULL with no non-blank check, no bean-validation "
                            + "constraint and no DEFAULT literal; blankness is data. It is also why the "
                            + "disclosure-group DEFAULT substitution at CBACT04C.cbl:436-438 is the normal "
                            + "interest path rather than an edge case - that fallback is asserted by the "
                            + "interest tier, not here")
                    .hasSize(10)
                    .isBlank();
        }
    }

    /**
     * The non-numeric postal code. Severity <strong>High</strong>: a digits-only constraint would reject all
     * fifty seeded rows, and adding one is a Blocker.
     */
    @Nested
    @DisplayName("The non-numeric postal code: the literal A000000000 on every row")
    class NonNumericPostalCode {

        @Test
        @DisplayName("every seeded postal code is the fixture's non-numeric literal")
        void everySeededPostalCodeIsTheFixtureLiteral() {
            assertThat(accountRepository.findAll())
                    .hasSize(50)
                    .allSatisfy(account -> assertThat(account.getAddressZip())
                            .as("ACCT-ADDR-ZIP PIC X(10) at bytes 103-112 is the literal A000000000 on all "
                                    + "50 records; the field is character, so a leading letter is valid data "
                                    + "rather than corruption")
                            .isEqualTo("A000000000"));
        }

        @Test
        @DisplayName("a further non-numeric postal code persists, because no digits-only rule exists")
        void aNonNumericPostalCodePersists() {
            Account letteredZip = seedShapedAccount(90_000_000_002L);
            letteredZip.setAddressZip("ZZ99999999");

            accountRepository.saveAndFlush(letteredZip);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_002L).orElseThrow().getAddressZip())
                    .as("PIC X(10) maps to CHAR(10) with no digits-only check in V1; asserting one would "
                            + "reject the seed itself")
                    .isEqualTo("ZZ99999999");
        }
    }


    /**
     * The signed cycle-debit accumulator. Asserting non-negativity here would be a <strong>Blocker</strong>,
     * because the over-limit arithmetic depends on the sign being preserved.
     */
    @Nested
    @DisplayName("The signed cycle-debit accumulator: negative values are correct, not corrupt")
    class SignedCycleDebitAccumulator {

        @Test
        @DisplayName("a negative cycle debit persists and round-trips with its sign intact")
        void aNegativeCycleDebitRoundTripsWithItsSign() {
            Account debited = seedShapedAccount(90_000_000_003L);
            debited.setCurrentCycleDebit(new BigDecimal("-250.75"));

            accountRepository.saveAndFlush(debited);
            flushAndClear();

            BigDecimal reloaded =
                    accountRepository.findById(90_000_000_003L).orElseThrow().getCurrentCycleDebit();

            assertThat(reloaded)
                    .as("CBTRN02C.cbl:548-551 adds the transaction amount to ACCT-CURR-CYC-CREDIT when it is "
                            + "non-negative and to ACCT-CURR-CYC-DEBIT otherwise, so a negative amount lands "
                            + "in the debit accumulator and the accumulator legitimately holds negative "
                            + "values. That is why the over-limit test at CBTRN02C.cbl:403-405 subtracts it. "
                            + "No absolute value is taken here and V1 carries no non-negative check on the "
                            + "column")
                    .isEqualByComparingTo("-250.75");
            assertThat(reloaded.signum())
                    .as("the sign itself is the datum under test, so it is asserted separately from the "
                            + "magnitude")
                    .isNegative();
        }

        @Test
        @DisplayName("a negative cycle debit survives the column's declared scale without losing precision")
        void aNegativeCycleDebitKeepsItsScale() {
            Account debited = seedShapedAccount(90_000_000_004L);
            debited.setCurrentCycleDebit(new BigDecimal("-0.01"));

            accountRepository.saveAndFlush(debited);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_004L).orElseThrow().getCurrentCycleDebit())
                    .as("the smallest representable negative amount in NUMERIC(12,2) must not be rounded to "
                            + "zero, because a lost cent changes the over-limit outcome")
                    .isEqualByComparingTo("-0.01");
        }
    }

    /**
     * Store-level optimistic concurrency, which is one of the two required layers and is not sufficient on
     * its own. The business-level layer is named in the class documentation and asserted by
     * {@code unit/service}.
     */
    @Nested
    @DisplayName("Optimistic locking: the version counter, and why it is only half the guard")
    class OptimisticLocking {

        @Test
        @DisplayName("the version counter increments on a real update")
        void theVersionCounterIncrementsOnAnUpdate() {
            Account before = accountRepository.findById(1L).orElseThrow();
            assertThat(before.getVersion())
                    .as("V3 seeds every account row with version 0")
                    .isEqualTo(0L);

            before.setReissueDate("2026-01-31");
            flushAndClear();

            assertThat(accountRepository.findById(1L).orElseThrow().getVersion())
                    .as("account is one of exactly four tables carrying version BIGINT, with card, customer "
                            + "and \"transaction\"; the flush applies the increment and the clear makes the "
                            + "re-read a real round trip rather than an identity-map hit")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a stale-version write is refused, with its message and its root cause preserved")
        void aStaleVersionWriteIsRefused() {
            Account managed = accountRepository.findById(1L).orElseThrow();
            assertThat(managed.getVersion()).isEqualTo(0L);

            // Move the row on underneath the managed instance. This is a bound statement on the very same
            // transaction and connection: no second thread and no second connection is involved, so the
            // outcome is deterministic on every run rather than dependent on a race being won.
            int rowsBumped = jdbcTemplate.update(
                    "UPDATE account SET version = version + 1 WHERE acct_id = ?", 1L);
            assertThat(rowsBumped).isEqualTo(1);

            managed.setReissueDate("2026-02-28");

            // Written through the repository rather than through a bare flush so that Spring Data's
            // exception translation applies and the failure arrives as the framework's optimistic-locking
            // type. This is the last action in the method: PostgreSQL aborts the transaction on the failed
            // statement, so nothing may follow it.
            assertThatExceptionOfType(OptimisticLockingFailureException.class)
                    .as("the pending update carries version 0 while the row now holds version 1, so the "
                            + "version-qualified UPDATE matches no row. This is the store-level guard "
                            + "replacing the READ ... UPDATE at COACTUPC.cbl:3894-3906; it is necessary and "
                            + "NOT sufficient, because 9700-CHECK-CHANGE-IN-REC at COACTUPC.cbl:4109-4193 "
                            + "compares twelve account fields against the snapshot the screen was populated "
                            + "from, which detects which fields changed rather than merely that the row did")
                    .isThrownBy(() -> accountRepository.saveAndFlush(managed))
                    // The message is asserted on its two load-bearing fragments rather than in full. The
                    // predicate fragment proves the UPDATE really was version-qualified, which is the whole
                    // mechanism, and the row count proves it matched nothing. Both come from the provider,
                    // so this asserts observed behaviour rather than a hoped-for wording.
                    .withMessageContaining("where acct_id=? and version=?")
                    .withMessageContaining("actual row count: 0")
                    // The provider's own exception is preserved as the cause rather than being swallowed and
                    // replaced, so the root cause is still diagnosable from the top-level failure.
                    .havingCause()
                    .withMessageContaining("actual row count: 0");
        }
    }

    /**
     * The pessimistic read that replaces {@code READ ... UPDATE}. Deliberately asserts only that the query
     * works and returns a managed instance.
     */
    @Nested
    @DisplayName("Pessimistic read for update: the READ ... UPDATE replacement")
    class PessimisticRead {

        @Test
        @DisplayName("the locked read returns the row, as a managed instance")
        void theLockedReadReturnsTheRowManaged() {
            // Ordered deliberately: the locked read runs first, so the instance in the persistence context
            // is the one it produced. A plain read afterwards is answered from the identity map, and only a
            // managed instance can be returned that way - which is what proves the locked read attached it.
            Optional<Account> locked = accountRepository.findByIdForUpdate(1L);

            assertThat(locked)
                    .as("findByIdForUpdate carries @Lock(PESSIMISTIC_WRITE), replacing the EXEC CICS READ "
                            + "... UPDATE at COACTUPC.cbl:3894-3906 whose failure exit is at :3910-:3916. "
                            + "Lock contention is deliberately NOT demonstrated: a second thread or a second "
                            + "connection would make the outcome a race, and determinism comes first")
                    .isPresent();

            Account lockedAccount = locked.orElseThrow();
            assertThat(lockedAccount.getAccountId()).isEqualTo(1L);
            assertThat(lockedAccount.getCurrentBalance())
                    .as("the locked read returns the same row the plain finder does")
                    .isEqualByComparingTo("194.00");
            assertThat(accountRepository.findById(1L).orElseThrow())
                    .as("the same persistence context returns the identical instance, which it can only do "
                            + "for a managed entity")
                    .isSameAs(lockedAccount);
        }

        @Test
        @DisplayName("the locked read on an absent key returns empty rather than throwing")
        void theLockedReadOnAnAbsentKeyReturnsEmpty() {
            assertThat(accountRepository.findByIdForUpdate(999_999L))
                    .as("a locked read of a row that does not exist has nothing to lock, and the absence is "
                            + "reported rather than raised")
                    .isEmpty();
        }
    }

    /**
     * Primary-key uniqueness, which is a legitimate assertion for this table specifically.
     */
    @Nested
    @DisplayName("Primary key: unique, unlike the three alternate indexes")
    class PrimaryKeyUniqueness {

        @Test
        @DisplayName("a second row bearing an existing key is refused, with message and root cause")
        void aDuplicateKeyIsRefused() {
            // Settle the persistence context first, so the duplicate insert below contends with a row that is
            // genuinely in the database rather than with an identity-map entry. Without this, a stray managed
            // instance could turn the insert into a merge and the constraint would never be exercised.
            flushAndClear();

            // version is null on a newly constructed account, which is how Spring Data decides the entity is
            // new and issues an insert rather than a merge - so this is a genuine duplicate insert.
            Account duplicate = seedShapedAccount(1L);

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("LISTCAT.txt:61 shows the base cluster as UNIQUE with no NONUNIQKEY line, so the "
                            + "eleven-byte key genuinely is unique and V1 enforces it as pk_account. The "
                            + "three alternate indexes are the opposite case - a legacy alternate key is "
                            + "non-unique, and V2 creates all three as non-unique B-trees - so uniqueness "
                            + "may be asserted here and must not be asserted for those")
                    .isThrownBy(() -> accountRepository.saveAndFlush(duplicate))
                    .withMessageContaining("pk_account")
                    .havingCause()
                    .withMessageContaining("pk_account");
        }

        @Test
        @DisplayName("a distinct key inserts cleanly, so the refusal above is about the key and nothing else")
        void aDistinctKeyInsertsCleanly() {
            Account fresh = seedShapedAccount(90_000_000_005L);

            accountRepository.saveAndFlush(fresh);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_005L))
                    .as("account references no other table, so a new row needs no parent; the three foreign "
                            + "keys that mention account point at it, from card, card_cross_reference and "
                            + "transaction_category_balance")
                    .isPresent();
        }
    }


    /**
     * The single check constraint this table carries, which is check 1 of the 5 in {@code V1}.
     */
    @Nested
    @DisplayName("Check constraint 1 of 5: acct_active_status IN ('Y','N')")
    class ActiveStatusCheckConstraint {

        @Test
        @DisplayName("both permitted status values persist")
        void bothPermittedStatusValuesPersist() {
            Account active = seedShapedAccount(90_000_000_006L);
            Account closed = seedShapedAccount(90_000_000_007L);
            closed.setActiveStatus("N");

            accountRepository.saveAll(List.of(active, closed));
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_006L).orElseThrow().getActiveStatus())
                    .as("COACTUPC.cbl:193 declares 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N' under "
                            + "WS-EDIT-ACCT-STATUS, which is the domain V1 encodes as "
                            + "ck_account_active_status")
                    .isEqualTo("Y");
            assertThat(accountRepository.findById(90_000_000_007L).orElseThrow().getActiveStatus())
                    .as("'N' is in the domain even though all 50 fixture records happen to carry 'Y', so it "
                            + "can only be proved by an insert rather than from the seed")
                    .isEqualTo("N");
        }

        @Test
        @DisplayName("a third status value is refused by the constraint, with message and root cause")
        void aThirdStatusValueIsRefused() {
            Account invalidStatus = seedShapedAccount(90_000_000_008L);
            // One character, so the entity's own width guard passes it and the database is the thing that
            // decides. Provoked last: the failed statement aborts the transaction.
            invalidStatus.setActiveStatus("X");

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("the domain is exactly 'Y' and 'N'. Note the trap alongside it: COACTUPC.cbl:78 "
                            + "declares 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N' inside WS-EDIT-YES-NO, but "
                            + "that is a generic reusable validator work field rather than a fourth Y/N "
                            + "column, so no extra constraint may be derived from it. V1 carries exactly 5 "
                            + "check constraints and there is no sixth")
                    .isThrownBy(() -> accountRepository.saveAndFlush(invalidStatus))
                    .withMessageContaining("ck_account_active_status")
                    .havingCause()
                    .withMessageContaining("ck_account_active_status");
        }
    }

    /**
     * The character and numeric boundaries, which are enforced at two layers. The entity guards its own
     * declared widths and precisions, and PostgreSQL guards the column; both are asserted, because only the
     * second is reachable once the first has refused the value.
     */
    @Nested
    @DisplayName("Width and precision: the entity guard, and the database behind it")
    class WidthAndPrecisionBoundaries {

        @Test
        @DisplayName("an over-length status is refused by the entity before it reaches the database")
        void anOverLengthStatusIsRefusedByTheEntity() {
            Account account = seedShapedAccount(90_000_000_009L);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("ACCT-ACTIVE-STATUS is PIC X(01) and maps to CHAR(1), so two characters cannot be a "
                            + "valid value; the entity refuses it and names the PIC clause")
                    .isThrownBy(() -> account.setActiveStatus("YN"))
                    .withMessageContaining("ACCT-ACTIVE-STATUS PIC X(01)");
        }

        @Test
        @DisplayName("an over-length status is refused by the database when bound below the entity")
        void anOverLengthStatusIsRefusedByTheDatabase() {
            // Going below the entity guard is the only way to reach the column itself, and it is the column
            // that has to be right: the guard could be removed tomorrow and the data would still be safe.
            assertThatExceptionOfType(DataAccessException.class)
                    .as("PostgreSQL refuses a non-blank overflow of CHAR(1) in its own right, independently "
                            + "of the entity guard")
                    .isThrownBy(() -> jdbcTemplate.update(
                            "UPDATE account SET acct_active_status = ? WHERE acct_id = ?", "YN", 1L))
                    .withMessageContaining("character(1)")
                    .havingCause()
                    .withMessageContaining("character(1)");
        }

        @Test
        @DisplayName("a trailing blank is accepted by CHAR, which is the blank-pad policy rather than a leak")
        void aTrailingBlankIsAcceptedByChar() {
            // The distinction only exists below the entity guard, which rejects on length alone. It is worth
            // asserting because it is the reason a CHAR(10) read returns ten characters: the type pads on
            // read and strips trailing blanks on write.
            int updated = jdbcTemplate.update(
                    "UPDATE account SET acct_active_status = ? WHERE acct_id = ?", "Y ", 1L);
            assertThat(updated).isEqualTo(1);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT acct_active_status FROM account WHERE acct_id = ?", String.class, 1L))
                    .as("CHAR(n) strips trailing blanks on write, so 'Y ' stores as 'Y'; only a non-blank "
                            + "overflow is an error")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("an over-length group identifier is refused by the entity")
        void anOverLengthGroupIdentifierIsRefusedByTheEntity() {
            Account account = seedShapedAccount(90_000_000_010L);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("ACCT-GROUP-ID is PIC X(10), so eleven characters cannot be a valid value")
                    .isThrownBy(() -> account.setGroupId("ELEVENCHARS"))
                    .withMessageContaining("ACCT-GROUP-ID PIC X(10)");
        }

        @Test
        @DisplayName("an over-length group identifier is refused by the database when bound below the entity")
        void anOverLengthGroupIdentifierIsRefusedByTheDatabase() {
            assertThatExceptionOfType(DataAccessException.class)
                    .as("CHAR(10) refuses a non-blank eleventh character at the column, which is what "
                            + "preserves the 10-byte field width of bytes 113-122")
                    .isThrownBy(() -> jdbcTemplate.update(
                            "UPDATE account SET acct_group_id = ? WHERE acct_id = ?", "ELEVENCHARS", 1L))
                    .withMessageContaining("character(10)")
                    .havingCause()
                    .withMessageContaining("character(10)");
        }

        @Test
        @DisplayName("a value at the declared NUMERIC(12,2) precision round-trips exactly")
        void aValueAtTheDeclaredPrecisionRoundTripsExactly() {
            Account atTheLimit = seedShapedAccount(90_000_000_011L);
            // Ten signed integer digits and two decimals is exactly what PIC S9(10)V99 can hold.
            atTheLimit.setCurrentBalance(new BigDecimal("9999999999.99"));

            accountRepository.saveAndFlush(atTheLimit);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_011L).orElseThrow().getCurrentBalance())
                    .as("the largest value PIC S9(10)V99 can represent must survive the round trip without "
                            + "rounding, and it is compared by compareTo so that a scale difference is not "
                            + "mistaken for a value difference")
                    .isEqualByComparingTo("9999999999.99");
        }

        @Test
        @DisplayName("the negative extreme of the declared precision round-trips exactly too")
        void theNegativeExtremeRoundTripsExactly() {
            Account atTheLimit = seedShapedAccount(90_000_000_012L);
            atTheLimit.setCurrentBalance(new BigDecimal("-9999999999.99"));

            accountRepository.saveAndFlush(atTheLimit);
            flushAndClear();

            assertThat(accountRepository.findById(90_000_000_012L).orElseThrow().getCurrentBalance())
                    .as("the S in PIC S9(10)V99 is a signed domain, and a balance is legitimately negative")
                    .isEqualByComparingTo("-9999999999.99");
        }

        @Test
        @DisplayName("a scale beyond two is refused by the entity rather than silently rounded")
        void aScaleBeyondTwoIsRefusedByTheEntity() {
            Account account = seedShapedAccount(90_000_000_013L);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("V99 is two decimal places. Refusing a third explicitly is what keeps rounding "
                            + "deliberate and HALF_EVEN, instead of letting NUMERIC(12,2) round half away "
                            + "from zero on the way in")
                    .isThrownBy(() -> account.setCurrentBalance(new BigDecimal("1.005")))
                    .withMessageContaining("at most 2 decimal digits");
        }

        @Test
        @DisplayName("a value beyond the declared precision is refused by the entity")
        void aValueBeyondTheDeclaredPrecisionIsRefusedByTheEntity() {
            Account account = seedShapedAccount(90_000_000_014L);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("eleven integer digits exceed PIC S9(10)V99 and would not fit NUMERIC(12,2) either")
                    .isThrownBy(() -> account.setCurrentBalance(new BigDecimal("10000000000.00")))
                    .withMessageContaining("9999999999.99");
        }

        @Test
        @DisplayName("a value beyond the declared precision is refused by the database when bound below it")
        void aValueBeyondTheDeclaredPrecisionIsRefusedByTheDatabase() {
            assertThatExceptionOfType(DataAccessException.class)
                    .as("NUMERIC(12,2) holds ten integer digits, so an eleven-digit integer part overflows "
                            + "the column itself and not merely the guard")
                    .isThrownBy(() -> jdbcTemplate.update(
                            "UPDATE account SET acct_curr_bal = ? WHERE acct_id = ?",
                            new BigDecimal("99999999999.99"), 1L))
                    .withMessageContaining("overflow")
                    .havingCause()
                    .withMessageContaining("overflow");
        }
    }

    /**
     * The three date columns, which are text and must stay text.
     */
    @Nested
    @DisplayName("Dates are text: PIC X(10) compared as (1:4)/(6:2)/(9:2) substrings")
    class DatesAreText {

        @Test
        @DisplayName("the catalogue reports all three date columns as character, not as a date type")
        void allThreeDateColumnsAreCharacterTyped() {
            assertThat(accountColumnDataType("acct_open_date"))
                    .as("ACCT-OPEN-DATE is PIC X(10)")
                    .isEqualTo("character");
            assertThat(accountColumnDataType("acct_expiraion_date"))
                    .as("ACCT-EXPIRAION-DATE (sic) is PIC X(10); mapping it to a date type would also lose "
                            + "the text comparison CBTRN02C.cbl:414 performs against a timestamp prefix")
                    .isEqualTo("character");
            assertThat(accountColumnDataType("acct_reissue_date"))
                    .as("ACCT-REISSUE-DATE is PIC X(10)")
                    .isEqualTo("character");
        }

        @Test
        @DisplayName("every seeded row carries the dashed shape at the offsets the source compares")
        void everySeededRowCarriesTheDashedShape() {
            assertThat(accountRepository.findAll())
                    .hasSize(50)
                    .allSatisfy(account -> {
                        assertDashedTenCharacterDate(account.getOpenDate(), "ACCT-OPEN-DATE");
                        assertDashedTenCharacterDate(account.getExpiraionDate(), "ACCT-EXPIRAION-DATE");
                        assertDashedTenCharacterDate(account.getReissueDate(), "ACCT-REISSUE-DATE");
                    });
        }

        @Test
        @DisplayName("the first account's expiry decomposes into the year, month and day the source reads")
        void theFirstAccountExpiryDecomposesIntoItsThreeSegments() {
            String expiry = accountRepository.findById(1L).orElseThrow().getExpiraionDate();

            // 9700-CHECK-CHANGE-IN-REC compares ACCT-EXPIRAION-DATE(1:4), (6:2) and (9:2) against three
            // discrete snapshot fields rather than comparing the date as one string. These are those three
            // segments, and asserting them is what pins the stored representation.
            assertThat(expiry.substring(0, 4)).as("the (1:4) year segment").isEqualTo("2025");
            assertThat(expiry.substring(5, 7)).as("the (6:2) month segment").isEqualTo("05");
            assertThat(expiry.substring(8, 10)).as("the (9:2) day segment").isEqualTo("20");
        }
    }

    /**
     * Corroboration against the frozen fixture, read by bare classpath name and never copied or altered.
     */
    @Nested
    @DisplayName("Corroboration against the frozen fixture: acctdata.txt at 50 x 300")
    class FixtureCorroboration {

        @Test
        @DisplayName("the fixture holds fifty records of exactly three hundred characters")
        void theFixtureHoldsFiftyRecordsOfThreeHundredCharacters() {
            List<String> records = readFixture("acctdata.txt");

            assertThat(records)
                    .as("15050 bytes is 50 records of 300 columns plus one line feed each, matching "
                            + "AVGLRECL 300 at LISTCAT.txt:59 and RECORDSIZE(300 300) in ACCTFILE.jcl")
                    .hasSize(50)
                    .allSatisfy(record -> assertThat(record)
                            .as("every record ends in a 188-space run - a 10-space ACCT-GROUP-ID followed by "
                                    + "the 178-byte FILLER - so a trailing-whitespace cleanup would collapse "
                                    + "300 columns to 112 and every position-based read after it would be "
                                    + "wrong")
                            .hasSize(300));
        }

        @Test
        @DisplayName("the fixture's eleven-character key prefixes are the keys now in the table")
        void theFixtureKeyPrefixesAreTheSeededKeys() {
            List<Long> fromFixture = readFixture("acctdata.txt").stream()
                    .map(record -> Long.valueOf(record.substring(0, 11)))
                    .sorted()
                    .toList();
            List<Long> fromTable = accountRepository.findAll().stream()
                    .map(Account::getAccountId)
                    .sorted()
                    .toList();

            // Both sides are sorted, so the comparison cannot depend on the order the database returns rows.
            assertThat(fromTable)
                    .as("ACCT-ID occupies bytes 1-11 with RKP 0 at LISTCAT.txt:60, so the record's leading "
                            + "eleven characters are its key")
                    .isEqualTo(fromFixture);
        }

        @Test
        @DisplayName("the fixture's postal-code columns are the non-numeric literal on every record")
        void theFixturePostalCodeColumnsAreTheLiteral() {
            assertThat(readFixture("acctdata.txt"))
                    .allSatisfy(record -> assertThat(record.substring(102, 112))
                            .as("ACCT-ADDR-ZIP occupies bytes 103-112, which is [102,112) zero-based")
                            .isEqualTo("A000000000"));
        }

        @Test
        @DisplayName("the fixture's group-identifier columns are blank on every record")
        void theFixtureGroupIdentifierColumnsAreBlank() {
            assertThat(readFixture("acctdata.txt"))
                    .allSatisfy(record -> assertThat(record.substring(112, 122))
                            .as("ACCT-GROUP-ID occupies bytes 113-122, which is [112,122) zero-based, and is "
                                    + "ten spaces on every record")
                            .hasSize(10)
                            .isBlank());
        }

        @Test
        @DisplayName("the fixture's decoded money columns agree with the rows the seed produced")
        void theFixtureMoneyColumnsAgreeWithTheSeededRows() {
            String firstRecord = readFixture("acctdata.txt").get(0);
            Account firstRow = accountRepository.findById(1L).orElseThrow();

            // A single narrowly scoped positional check, on one documented field, purely to corroborate that
            // the seed decoded this position rather than some other one. The decode itself belongs to
            // V3__seed_data.sql; reproducing it here would test this test rather than the seed.
            assertThat(firstRecord.substring(12, 24))
                    .as("ACCT-CURR-BAL occupies bytes 13-24, twelve characters wide, and record 1 carries "
                            + "00000001940{ where the trailing { overpunch denotes +0")
                    .isEqualTo("00000001940{");
            assertThat(firstRow.getCurrentBalance())
                    .as("so the seeded value is +194.00, which is what the table must hold")
                    .isEqualByComparingTo("194.00");
        }
    }


    /**
     * The complete PostgreSQL metadata contract for the {@code account} table.
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
     * <p>For {@code account} that is thirteen columns, the eleven-digit key of app/catlg/LISTCAT.txt:L59, five NUMERIC(12,2) money columns, the version column COACTUPC's dual-dataset rewrite depends on, and the Y/N status check - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVACT01Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the account table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "account");
    }

}
