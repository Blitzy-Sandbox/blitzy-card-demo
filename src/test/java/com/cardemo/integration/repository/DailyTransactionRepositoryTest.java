/*
 * ******************************************************************
 * Program     : DailyTransactionRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 test - repository integration tier, Failsafe
 * Function    : Proves the daily-transaction staging table persists raw,
 *               unvalidated file content. Asserts that the table carries
 *               no foreign key, no index beyond its primary key, no
 *               version column and no check constraint; that a staged
 *               row naming a card number no card row carries is loaded
 *               rather than refused; that both 26-character timestamp
 *               columns are text and accept 26 blanks; that a negative
 *               amount keeps its sign; and that the transaction source
 *               is plain text rather than an enumerated value.
 * Source      : app/cpy/CVTRA06Y.cpy (RECLN 350, the DALYTRAN- prefixed
 *               twin of app/cpy/CVTRA05Y.cpy),
 *               app/jcl/POSTTRAN.jcl:L30-L31 (the DALYTRAN DD card and
 *               its DSN=AWS.M2.CARDDEMO.DALYTRAN.PS),
 *               app/jcl/TRANFILE.jcl:L70 (the initial-load companion
 *               AWS.M2.CARDDEMO.DALYTRAN.PS.INIT),
 *               app/cbl/CBTRN02C.cbl:L370-L378 (validation cascade),
 *               :L380-L392 (reject 100), :L393-L422 (rejects 101, 102,
 *               103), :L545-L560 (the sign branch and reject 109),
 *               :L176-L182 (the 430-byte reject geometry),
 *               app/cbl/CBTRN01C.cbl:L29-L58 (read-only pre-flight),
 *               app/data/ASCII/dailytran.txt (300 records of 350 bytes),
 *               app/catlg/LISTCAT.txt:L786 (the non-VSAM entry),
 *               app/csd/CARDDEMO.CSD (batch-only proof),
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration coverage for {@link DailyTransactionRepository} and the {@code daily_transaction} staging
 * table, run against a real PostgreSQL 16 supplied by {@link AbstractRepositoryIntegrationTest}.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the only one of the eleven tables whose whole purpose is to hold content that may be wrong.
 * The other ten model VSAM clusters that the online programs read after validating; this one models the
 * physical sequential dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, which the daily posting job reads
 * <em>before</em> validating. Everything asserted below follows from that single fact, so the assertions
 * are mostly about what the table refuses to constrain rather than about what it enforces.
 *
 * <h3>The zero-foreign-key contract, and why it is mandatory rather than incidental</h3>
 *
 * <p>{@code V1__create_schema.sql} declares ten foreign keys, named {@code fk01_card_account} through
 * {@code fk10_discgrp_category}, and {@code daily_transaction} is party to none of them - neither as child
 * nor as parent. That is a deliberate design constraint, and the corpus is what makes it one.
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} validates each staged row inside the application and writes a reject
 * record when a referent is missing. {@code 1500-VALIDATE-TRAN} at {@code :L370-L378} performs the
 * cross-reference lookup, then performs the account lookup only while the reason code is still zero, and
 * closes with the literal comment {@code ADD MORE VALIDATIONS HERE}: exactly two lookup paragraphs, no
 * more. {@code 1500-A-LOOKUP-XREF} at {@code :L380-L392} assigns reject code <strong>100</strong>,
 * {@code INVALID CARD NUMBER FOUND}, at {@code :L385} when the card number is unknown.
 * {@code 1500-B-LOOKUP-ACCT} at {@code :L393-L422} assigns <strong>101</strong>,
 * {@code ACCOUNT RECORD NOT FOUND}, at {@code :L397} when the account is missing; otherwise it computes a
 * temporary balance at {@code :L403-L405} and assigns <strong>102</strong>, {@code OVERLIMIT TRANSACTION},
 * at {@code :L410}, then immediately and without a guard compares the account expiry against the
 * originating timestamp at {@code :L414} and assigns <strong>103</strong> at {@code :L417}.
 *
 * <p><strong>A foreign key on this table would make reject codes 100 and 101 unreachable.</strong> The row
 * would be refused at load time instead of being loaded and then rejected by the engine, which retires two
 * of the five reject outcomes and with them the reject record the job exists to produce.
 * {@link ZeroForeignKeyContract} therefore asserts the absence twice over: once
 * behaviourally, by persisting a row whose card number no {@code card} row and no
 * {@code card_cross_reference} row carries, and once by metadata sweep over {@code pg_constraint}.
 *
 * <p>Two related absences are asserted in the same group and are equally deliberate. There is no
 * {@code version} column, because optimistic locking has no meaning on a table that is loaded once and read
 * once - the four tables that do carry one are {@code account}, {@code card}, {@code customer} and
 * {@code "transaction"}. And there is no check constraint, because a staging row's business content is the
 * posting job's judgement to make; {@code V1} spends its entire check budget of five elsewhere.
 *
 * <h3>What this tier deliberately leaves to another</h3>
 *
 * <p>Nothing below re-tests the reject engine. The two-paragraph cascade, the five reject constants, the
 * preserved quirk that the unguarded sequence at {@code :L403-L420} lets <strong>103 overwrite 102</strong>
 * so that a single reject bearing 103 is written, the expiry comparison being a string comparison against
 * the first ten characters of the <em>originating</em> timestamp through the misspelled
 * {@code ACCT-EXPIRAION-DATE} field whose misspelling is part of the contract, the posting order at
 * {@code :L424-L465}, the sign branch at {@code :L545-L560}, reject code 109 at {@code :L556} which is
 * assigned on the account-rewrite failure path inside the already-validated posting routine and so is never
 * consumed as a reject outcome, the rule that return code 4 is set if and only if the reject count exceeds
 * zero at {@code :L229-L231}, the abend contract at {@code :L707-L711} with abend code 999, and the
 * four-character status renderer at {@code :L714-L731} all belong to the batch tiers. So does the read-only
 * pre-flight derived from {@code app/cbl/CBTRN01C.cbl:L29-L58}, whose verb inventory over six
 * {@code SELECT} statements is {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} with zero
 * {@code WRITE}, {@code REWRITE} and {@code DELETE}. They are cited here because they are why the schema
 * looks the way it does, and asserted here not at all.
 *
 * <h3>Record geometry: 350 in, 430 out</h3>
 *
 * <p>The staged record is <strong>350 bytes</strong>, declared by {@code app/cpy/CVTRA06Y.cpy:L2} and laid
 * out at {@code :L5-L18}. Offsets are one-based and inclusive, matching COBOL reference modification:
 *
 * <pre>
 * DALYTRAN-ID          1-16     DALYTRAN-MERCHANT-NAME  153-202
 * DALYTRAN-TYPE-CD    17-18     DALYTRAN-MERCHANT-CITY  203-252
 * DALYTRAN-CAT-CD     19-22     DALYTRAN-MERCHANT-ZIP   253-262
 * DALYTRAN-SOURCE     23-32     DALYTRAN-CARD-NUM       263-278
 * DALYTRAN-DESC       33-132    DALYTRAN-ORIG-TS        279-304
 * DALYTRAN-AMT       133-143    DALYTRAN-PROC-TS        305-330
 * DALYTRAN-MERCHANT-ID 144-152  FILLER                  331-350
 * </pre>
 *
 * <p><strong>The 430-byte figure belongs to the reject output, not to this table</strong>, and conflating
 * the two is the easy mistake. {@code REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L176-L178} is
 * {@code PIC X(350)} of transaction image plus {@code PIC X(80)} of trailer, and the trailer at
 * {@code :L180-L182} is a four-digit reason code plus a 76-character description: 350 + 80 = 430, written
 * at {@code :L442-L465}. Nothing here asserts 430. The staged input is 350 and only 350.
 *
 * <h3>Provenance: this dataset has no cluster and no CICS definition, so neither is cited</h3>
 *
 * <p>{@code DALYTRAN} is physical sequential, not a VSAM KSDS, and the catalogue listing records it as a
 * non-VSAM entry at {@code app/catlg/LISTCAT.txt:L786} with its initial-load companion at {@code :L801}. A
 * non-VSAM entry carries no key length, no relative key position and no record size, and the catalogue
 * totals at {@code :L3938-L3946} report {@code CLUSTER 10} while naming ten other datasets. Neither is
 * there a CICS resource definition: {@code app/csd/CARDDEMO.CSD} holds exactly eight {@code DEFINE FILE}
 * entries - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT},
 * {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - and {@code DALYTRAN} occurs in it zero times,
 * which is the positive proof that this is a batch-only dataset.
 *
 * <p><strong>The citations of record are therefore the job control</strong>: the {@code DALYTRAN} DD card
 * at {@code app/jcl/POSTTRAN.jcl:L30} with its dataset name at {@code :L31}, and the initial-load
 * companion at {@code app/jcl/TRANFILE.jcl:L70}. A catalogue line number quoted for this table would be
 * fabricated evidence, so none is quoted.
 *
 * <h3>Two shapes this file reads off the shipped contract</h3>
 *
 * <p>Both were measured against the shipped dependencies rather than assumed, and both change what an
 * assertion here may say. This matters, since each would otherwise have produced a test
 * that could not compile.
 *
 * <ol>
 *   <li><strong>The primary key is the ingestion ordinal, not {@code DALYTRAN-ID}.</strong>
 *       {@link DailyTransaction} keys on {@code ingest_seq NUMERIC(9)} through the constraint
 *       {@code pk_daily_transaction}, and {@code dalytran_id CHAR(16)} is an ordinary column carrying no
 *       uniqueness at all. That follows from the source: both programs that open this file declare it
 *       {@code ORGANIZATION IS SEQUENTIAL} and {@code ACCESS MODE IS SEQUENTIAL}, so a flat file may
 *       legitimately repeat an identifier, and the ordinal models {@code WS-TRANSACTION-COUNT PIC 9(09)}
 *       declared at {@code app/cbl/CBTRN02C.cbl:L185} and incremented once per accepted read at
 *       {@code :L206}. The shipped fixture cannot detect a mistake here, because its 300 identifiers happen
 *       to be distinct and already ascending - which is exactly why
 *       {@link DatabaseBoundaryConstraints#repeatedTransactionIdIsAccepted()} asserts the acceptance
 *       explicitly and the uniqueness assertion is aimed at the real key.</li>
 *   <li><strong>The declared finder is {@code findAllByOrderByIngestSequenceAsc(Pageable)}</strong>,
 *       returning a {@link Slice}. It is the repository's only declared method, and the ordering is in the
 *       method name so a caller cannot omit it. There is deliberately no whole-file list form, because a
 *       result set bounded only by the size of the staging table would materialise every row at once, and
 *       there is deliberately no finder by card number, account, amount, source, timestamp or merchant,
 *       because the source performs no keyed lookup against this dataset. Requesting one would be a
 *       defect, so nothing below references a finder that is not declared.</li>
 *   </ol>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone, which is the cheapest
 * check that the file still satisfies the compiler settings, with {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe by its path, and the binding is fragile.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java}
 * and runs it at {@code integration-test} and {@code verify}, even though the class keeps the {@code Test}
 * suffix; {@code maven-surefire-plugin} 3.5.4 owns {@code **}{@code /unit/}{@code **} and explicitly
 * excludes the integration tree. A class moved up to {@code com.cardemo.integration}, to
 * {@code com.cardemo}, or into the root of {@code src/test/java} matches neither include set, is collected
 * by neither plugin, and simply never runs - with a green build, two plugins reporting success and no
 * output to notice. Do not rename or relocate this class.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a real PostgreSQL 16
 * container plus a reaper container, so this tier cannot run without a container runtime, and a run without
 * one must be reported as a blocked prerequisite rather than as a pass. Where a host JDK is not on the
 * path, the identical build runs inside the pinned image with the repository mounted, and produces the same
 * result because every plugin and every non-managed dependency version is pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, and PostgreSQL 16 pinned by image digest, both owned by the
 *       harness. This class declares no container, no context annotation, no property source and no
 *       {@code static} field of any kind, because the harness owns all four and a second copy of any of
 *       them is a second lifecycle to diverge from.</li>
 *   <li><strong>Time comes from {@link AbstractRepositoryIntegrationTest#fixedClock()}</strong>, fixed at
 *       {@code 2022-06-10T19:27:53Z} in UTC, which is the one originating instant the frozen fixture
 *       carries. The ambient clock is never read. Any formatting uses {@link Locale#ROOT}.</li>
 *   <li><strong>Money is compared with {@link BigDecimal#compareTo}, never with
 *       {@link BigDecimal#equals}</strong>, because equality on that type includes scale and
 *       {@code 504.77} stored as {@code NUMERIC(11,2)} need not carry the scale a literal was written
 *       with. No binary floating-point type is named anywhere in this file - the words themselves are kept
 *       out of it so that a token sweep for them comes back empty - and no absolute value is ever taken.</li>
 *   <li><strong>Three precision tiers exist and this table sits in the middle one.</strong>
 *       {@code PIC S9(10)V99} maps to {@code NUMERIC(12,2)} for account money, {@code PIC S9(09)V99} to
 *       {@code NUMERIC(11,2)} for {@code DALYTRAN-AMT}, and {@code PIC S9(04)V99} to {@code NUMERIC(6,2)}
 *       for a disclosure rate. Precision 11 and scale 2 are confirmed by metadata query rather than
 *       assumed.</li>
 *   <li><strong>The {@code CHAR} blank-pad policy, stated once and applied throughout.</strong> PostgreSQL
 *       stores {@code character(n)} blank-padded to {@code n} and returns it that way, so a value written
 *       as {@code "01"} into {@code CHAR(2)} reads back as {@code "01"} and a value written as 26 spaces
 *       into {@code CHAR(26)} reads back as 26 spaces. Every expected value in this file is therefore
 *       written at its declared width, and a reloaded value is asserted at that width. Two consequences
 *       are worth naming: SQL {@code length()} does not count trailing blanks on that type while
 *       {@code octet_length()} does, and an over-length value whose excess is entirely blanks is silently
 *       truncated rather than refused - which is why every width test below overflows with non-blank
 *       characters.</li>
 *   <li><strong>The fixture is read by its flat classpath name {@code dailytran.txt}</strong>, with the
 *       word "daily" spelled in full. The mainframe DD name and dataset spell it {@code DALYTRAN}, so the
 *       abbreviated spelling is the natural guess and no such resource can be resolved, so the whole class
 *       fails on a resource that never loads. Nothing here copies,
 *       trims, normalises or edits a fixture.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test fails to start with a container or Docker error.</em> There is no reachable Docker
 *       socket; see the prerequisite above.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version is selected.</em> The
 *       most consequential trap of the whole migration, whose remedy has two halves that are
 *       both required. Half one: pin Testcontainers to exactly {@code 2.0.3} by overriding the version
 *       property the Spring Boot parent manages, never by importing a second bill of materials, because two
 *       competing imports resolve in an ordering-dependent way that can silently select the parent-managed
 *       1.x line. Half two: use only the prefixed module coordinates {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}, because the bare 1.x identifiers are not published on the 2.x
 *       line. Overriding without renaming resolves artefacts nobody published; renaming without overriding
 *       resolves the wrong version. Both halves are already in place in the root build descriptor, which is
 *       root-owned and must not be edited from here.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs with {@code -Xlint:all}
 *       and {@code -Werror}, and that reaches test compilation, so a single unused import, a raw type or a
 *       deprecation is an error rather than a warning. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than on a column name.</em> Hibernate runs
 *       with {@code ddl-auto: validate}, which compares type codes, so a {@code Long} mapped over
 *       {@code NUMERIC(9)} can fail where {@code BIGINT} passes. <strong>The fix is upstream</strong>, in
 *       the schema migration or in the entity mapping. Never widen a column to silence it and never patch
 *       this test.</li>
 *   <li><em>A position-based read of the fixture returns the wrong field.</em> A trailing-whitespace
 *       cleanup has been applied to it. Every record ends with the 20-byte {@code FILLER}, the longest
 *       trailing run is 46 spaces, and trimming would collapse a 350-byte record to 304 and misalign every
 *       read after the first short row. The repository's editor configuration anticipates exactly this and
 *       turns its trailing-whitespace rule off for {@code src/test/resources/**.txt}, alongside the same
 *       carve-out for Markdown, so an editor honouring that file will leave a fixture alone - but a tool
 *       that ignores it, or a hand edit, still can. This matters, because the damage is
 *       silent: the file still parses and only the column arithmetic is wrong.</li>
 *   <li><em>The source column will not bind, or an accepted value is refused.</em> It has been mapped to an
 *       enumerated type. {@code dalytran_source} is {@code CHAR(10)} text: the fixture carries only
 *       {@code POS TERM} on 250 rows and {@code OPERATOR} on 50, while the interest program writes the
 *       literal {@code System} at {@code app/cbl/CBACT04C.cbl:L484}.</li>
 *
 *   <li><em>A timestamp will not parse.</em> It has been mapped as a temporal type. Both columns are
 *       {@code CHAR(26)} text and one of them is entirely blank on all 300 seeded rows; see
 *       {@link FieldContractRoundTrip}.</li>
 *   <li><em>An amount comes back positive when the fixture carried a debit.</em> An absolute value has been
 *       taken, or a non-negative constraint has been added. Both break parity: 50
 *       of the 300 seeded rows are negative, and {@code app/cbl/CBTRN02C.cbl:L547-L552} adds the amount to
 *       the current-cycle credit when it is non-negative and to the current-cycle <em>debit</em> otherwise,
 *       so the debit accumulator legitimately holds negative values - which is precisely why the over-limit
 *       formula at {@code :L403-L405} subtracts it. That formula is never algebraically rewritten.</li>
 *   <li><em>An amount comparison fails on two values that print the same.</em> {@link BigDecimal#equals}
 *       has been used where {@link BigDecimal#compareTo} was needed.</li>
 *   <li><em>The seeded row count is not 300.</em> A row leaked from another test method. Isolation comes
 *       solely from the harness's per-method transactional rollback, which is why nothing here truncates,
 *       deletes, seeds by script or dirties the context.</li>
 *   </ul>
 *
 * <h2>Two disclosures, per Rule 1 Clause F</h2>
 *
 * <p><strong>First: the boundary-parity baseline is Not available.</strong> No captured mainframe output is
 * held anywhere in this repository; a sweep for expected, baseline and golden artefacts, for {@code .out}
 * and system-output captures, and for the reject, report and statement datasets by name returns only
 * dataset <em>definition</em> members and zero captured data. What would be needed is a captured 430-byte
 * reject dataset together with the resulting transaction, account and category-balance images from a real
 * posting run at a known input state. Until that exists, this class creates no baseline artefact,
 * fabricates no expected bytes, and asserts <strong>no reject count and no posted count</strong>. A
 * Java-generated baseline would be circular, and a hand-simulated one is not an oracle: a stateless model
 * over these fixtures yields a different reject total from a stateful one, which proves the number is
 * model-sensitive rather than authoritative. The prohibition binds hardest here, because this is the input
 * table of the very pipeline such a baseline would describe.
 *
 * <p><strong>Second: file status {@code '35'}, file unavailable, is Not available.</strong> A census across
 * the 28 programs of {@code app/cbl} finds that literal zero times and {@code DFHRESP(NOTOPEN)} zero times;
 * the response conditions the corpus actually tests are {@code NORMAL}, {@code NOTFND}, {@code ENDFILE},
 * {@code DUPREC} and {@code DUPKEY}. The condition is specification-derived only, so no test for it is
 * invented here.
 *
 * @see DailyTransactionRepository
 * @see DailyTransaction
 * @see AbstractRepositoryIntegrationTest
 */
@DisplayName("DailyTransactionRepository: the unkeyed, unconstrained staging table for untrusted input")
class DailyTransactionRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test: sequential staging access, one declared finder, nothing speculative. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * Used for two things only: parameterised catalogue queries that assert what the schema does
     * <em>not</em> declare, and parameterised inserts that provoke a constraint at the database boundary
     * rather than at the entity boundary. Every statement binds its values; none is assembled by
     * concatenation.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Sole constructor, invoked by the test framework.
     *
     * <p>Declared explicitly so the class advertises no wider construction surface than it needs. There is
     * nothing to do here: the repository and the template are injected after construction, and the
     * container is started by the harness before any instance exists.
     */
    DailyTransactionRepositoryTest() {
        super();
    }

    /**
     * Returns an ingestion ordinal that no seeded row occupies.
     *
     * <p>The seed fills 1 through 300 densely, and {@code ingest_seq} is {@code NUMERIC(9)}, so the domain
     * runs to 999,999,999. Offsetting well past the seed keeps a row this class inserts from colliding with
     * one the migration loaded, which matters because a collision would surface as a primary-key violation
     * in a test that was asserting something else entirely.
     *
     * @param offset a small distinguishing number, unique within a single test method
     * @return the ordinal to insert at, never {@code null}
     */
    private Long syntheticOrdinal(final int offset) {
        return Long.valueOf(900_000L + offset);
    }

    /**
     * Returns a sixteen-character card number that the frozen fixtures never carry.
     *
     * <p>Sixteen nines is not a card number in any scheme - it fails the checksum every issuer applies - so
     * it discloses nothing while still occupying the full declared width of {@code DALYTRAN-CARD-NUM}.
     * Width matters here: a shorter value would be blank-padded by the column and would then no longer
     * exercise the sixteen-byte field the record allocates.
     *
     * @return sixteen digit characters, never {@code null}
     */
    private String unknownCardNumber() {
        return "9999999999999999";
    }

    /**
     * Right-pads test data to the declared width of a fixed-width column.
     *
     * <p>Every expected value in this class is written at its column's declared width, because
     * {@code character(n)} is blank-padded on storage and returns the padded form. Padding here rather than
     * at each call site keeps the widths in one place and makes a mismatch a loud failure instead of a
     * silent one.
     *
     * @param value the unpadded value, never {@code null}
     * @param width the declared column width in characters
     * @return {@code value} followed by enough spaces to reach {@code width}
     * @throws IllegalArgumentException if {@code value} is already wider than {@code width}, which would
     *                                  mean the test data itself contradicts the record layout
     */
    private String padTo(final String value, final int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("test data is wider than its column: " + value.length()
                    + " characters supplied for a " + width + "-character field");
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Returns a run of spaces of the requested width.
     *
     * <p>Used for the processing timestamp, whose only value across all 300 seeded rows is 26 spaces.
     *
     * @param width how many spaces
     * @return exactly {@code width} space characters
     */
    private String blanks(final int width) {
        return " ".repeat(width);
    }

    /**
     * Renders the harness's fixed instant in the shape the online producer writes.
     *
     * <p>This is one of the three mutually incompatible producers that share the two 26-character columns,
     * and it is the shape the frozen fixture carries in bytes 279 to 304 of all 300 records: a space
     * separator and six fractional digits. It is derived from
     * {@link AbstractRepositoryIntegrationTest#fixedClock()} rather than written as a literal, so the value
     * this class expects and the value the context's own beans would produce cannot drift apart, and no
     * assertion here reads the ambient clock.
     *
     * @return a 26-character timestamp text, never {@code null}
     */
    private String onlineProducerTimestamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(fixedClock().instant());
    }

    /**
     * Renders the harness's fixed instant in the shape the batch producer writes.
     *
     * <p>The second of the three producers, and the one most often got wrong. The format comment at
     * {@code app/cbl/CBTRN02C.cbl:L149} reads {@code EEEE-MM-DD-UU.MM.SS.HH0000}: a dash before the hour,
     * dots between the time parts, <strong>hundredths</strong> of a second, and then four literal zero
     * characters. The fractional field is two digits wide because {@code DB2-MIL} is declared
     * {@code PIC 9(002)}, so nanosecond precision is not merely unnecessary here, it is wrong.
     *
     * @return a 26-character timestamp text, never {@code null}
     */
    private String batchProducerTimestamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(fixedClock().instant()) + "0000";
    }

    /**
     * Builds a fully populated staging row, every field at its declared width.
     *
     * <p>The invariant values are taken from the frozen fixture so the row is representative rather than
     * arbitrary: category code 1, merchant identifier 800000000 - the single value all 300 records carry -
     * and a type code of {@code 01}. The parameters are the fields whose variation the tests below care
     * about.
     *
     * @param ordinal             the ingestion ordinal, which is the primary key
     * @param transactionId       {@code DALYTRAN-ID}, sixteen characters
     * @param source              {@code DALYTRAN-SOURCE}, padded here to its ten characters
     * @param amount              {@code DALYTRAN-AMT}, signed, at most two decimal digits
     * @param merchantZip         {@code DALYTRAN-MERCHANT-ZIP}, padded here to its ten characters
     * @param cardNumber          {@code DALYTRAN-CARD-NUM}, sixteen characters
     * @param processingTimestamp {@code DALYTRAN-PROC-TS}, twenty-six characters, possibly all blank
     * @return a transient staging row, never {@code null}
     */
    private DailyTransaction stagingRow(final Long ordinal,
                                        final String transactionId,
                                        final String source,
                                        final BigDecimal amount,
                                        final String merchantZip,
                                        final String cardNumber,
                                        final String processingTimestamp) {
        return new DailyTransaction(
                ordinal,
                transactionId,
                "01",
                Integer.valueOf(1),
                padTo(source, 10),
                padTo("Purchase at a merchant this table never judges", 100),
                amount,
                Long.valueOf(800_000_000L),
                padTo("A merchant name at its declared width", 50),
                padTo("A merchant city at its declared width", 50),
                padTo(merchantZip, 10),
                cardNumber,
                onlineProducerTimestamp(),
                processingTimestamp);
    }

    /**
     * Inserts a staged row through plain JDBC so that a constraint is evaluated by PostgreSQL rather than by
     * the entity.
     *
     * <p><strong>Why this bypasses the entity deliberately.</strong> {@link DailyTransaction} guards its own
     * widths and ranges in its constructor and in every mutator, so a value that violates the record layout
     * never reaches the database through the repository. That guard is correct and is asserted separately,
     * but it also means the repository cannot be used to prove that the <em>column</em> enforces its width.
     * Going through JDBC for exactly those cases is what makes the database-boundary assertions real.
     *
     * <p>Every value is bound as a parameter and the statement text is a constant, so no value is ever
     * concatenated into SQL.
     *
     * @param ordinal             the ingestion ordinal to insert at
     * @param transactionId       the value for {@code dalytran_id}
     * @param typeCode            the value for {@code dalytran_type_cd}
     * @param originatingTimestamp the value for {@code dalytran_orig_ts}
     * @param amount              the value for {@code dalytran_amt}
     */
    private void insertStagedRow(final Long ordinal,
                                 final String transactionId,
                                 final String typeCode,
                                 final String originatingTimestamp,
                                 final BigDecimal amount) {
        jdbcTemplate.update("""
                INSERT INTO daily_transaction (
                    ingest_seq, dalytran_id, dalytran_type_cd, dalytran_cat_cd,
                    dalytran_source, dalytran_desc, dalytran_amt, dalytran_merchant_id,
                    dalytran_merchant_name, dalytran_merchant_city, dalytran_merchant_zip,
                    dalytran_card_num, dalytran_orig_ts, dalytran_proc_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ordinal,
                transactionId,
                typeCode,
                Integer.valueOf(1),
                padTo("POS TERM", 10),
                padTo("Purchase at a merchant this table never judges", 100),
                amount,
                Long.valueOf(800_000_000L),
                padTo("A merchant name at its declared width", 50),
                padTo("A merchant city at its declared width", 50),
                padTo("72112", 10),
                unknownCardNumber(),
                originatingTimestamp,
                blanks(26));
    }

    /**
     * Inserts a staged row that omits one {@code NOT NULL} business column.
     *
     * <p>Omitting the column rather than binding {@code null} to it reaches the same constraint by a route
     * that needs no type hint for a null parameter, so the statement stays fully parameterised and the
     * failure is unambiguously the column's own {@code NOT NULL} rather than a driver complaint.
     *
     * @param ordinal the ingestion ordinal to insert at
     */
    private void insertStagedRowWithoutDescription(final Long ordinal) {
        jdbcTemplate.update("""
                INSERT INTO daily_transaction (
                    ingest_seq, dalytran_id, dalytran_type_cd, dalytran_cat_cd,
                    dalytran_source, dalytran_amt, dalytran_merchant_id,
                    dalytran_merchant_name, dalytran_merchant_city, dalytran_merchant_zip,
                    dalytran_card_num, dalytran_orig_ts, dalytran_proc_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ordinal,
                "0000000000000001",
                "01",
                Integer.valueOf(1),
                padTo("POS TERM", 10),
                new BigDecimal("1.00"),
                Long.valueOf(800_000_000L),
                padTo("A merchant name at its declared width", 50),
                padTo("A merchant city at its declared width", 50),
                padTo("72112", 10),
                unknownCardNumber(),
                onlineProducerTimestamp(),
                blanks(26));
    }

    /**
     * Counts the foreign keys that touch a table in either direction.
     *
     * <p>Both directions are counted on purpose. A constraint whose child is the table is an outbound
     * reference that would refuse a staged row naming a missing referent; a constraint whose parent is the
     * table is an inbound reference that would stop a staged row being discarded. Either one would change
     * what the staging table means, so the assertion has to be that neither exists.
     *
     * @param tableName the table to examine, bound as a parameter
     * @return the number of foreign-key constraints naming that table as child or as parent
     */
    private Long foreignKeyCount(final String tableName) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint fk
                  JOIN pg_class child ON child.oid = fk.conrelid
                  JOIN pg_class parent ON parent.oid = fk.confrelid
                  JOIN pg_namespace ns ON ns.oid = child.relnamespace
                 WHERE fk.contype = 'f'
                   AND ns.nspname = current_schema()
                   AND (child.relname = ? OR parent.relname = ?)
                """, Long.class, tableName, tableName);
    }

    /**
     * Counts the check constraints declared on a table.
     *
     * <p>On PostgreSQL 16 a {@code NOT NULL} is an attribute rather than a catalogue constraint, so this
     * count sees only real {@code CHECK} clauses. That is what makes "zero checks on a table whose fourteen
     * columns are all {@code NOT NULL}" a coherent claim rather than a contradiction.
     *
     * @param tableName the table to examine, bound as a parameter
     * @return the number of check constraints on that table
     */
    private Long checkConstraintCount(final String tableName) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint ck
                  JOIN pg_class rel ON rel.oid = ck.conrelid
                  JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                 WHERE ck.contype = 'c'
                   AND ns.nspname = current_schema()
                   AND rel.relname = ?
                """, Long.class, tableName);
    }

    /**
     * Lists the indexes on a table, in name order.
     *
     * @param tableName the table to examine, bound as a parameter
     * @return every index name on that table, ordered, possibly empty, never {@code null}
     */
    private List<String> indexNames(final String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND tablename = ?
                 ORDER BY indexname
                """, String.class, tableName);
    }

    /**
     * Counts how many columns of a table carry a given name.
     *
     * <p>Phrased as a count rather than as a lookup so that the answer for a column the table has no
     * business carrying is zero rather than an exception.
     *
     * @param tableName  the table to examine, bound as a parameter
     * @param columnName the column to look for, bound as a parameter
     * @return one when the column is declared, zero when it is not
     */
    private Long columnCount(final String tableName, final String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                   AND column_name = ?
                """, Long.class, tableName, columnName);
    }

    /**
     * Reads the declared type of a single column.
     *
     * <p>One query returning the whole descriptor, rather than one query per property, because the type,
     * the character width, the numeric precision and the numeric scale are asserted together and the column
     * is the same column each time.
     *
     * @param tableName  the table to examine, bound as a parameter
     * @param columnName the column to describe, bound as a parameter
     * @return the catalogue row for that column, keyed by catalogue column name
     */
    private Map<String, Object> columnMetadata(final String tableName, final String columnName) {
        return jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                   AND column_name = ?
                """, tableName, columnName);
    }

    /**
     * Counts the staged rows carrying a given {@code DALYTRAN-ID}.
     *
     * <p>This is a diagnostic read rather than a repository capability. No finder by identifier exists on
     * {@link DailyTransactionRepository} and none may be added, because the source performs no keyed lookup
     * against this dataset; asking the catalogue directly is how the non-uniqueness of the column is
     * demonstrated without inventing a finder to demonstrate it with.
     *
     * @param transactionId the identifier to count, bound as a parameter
     * @return how many staged rows carry it
     */
    private Long rowsCarryingTransactionId(final String transactionId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM daily_transaction WHERE dalytran_id = ?
                """, Long.class, transactionId);
    }

    /**
     * Reads back the amount stored at one ordinal.
     *
     * @param ordinal the ingestion ordinal, bound as a parameter
     * @return the stored amount exactly as the column returns it
     */
    private BigDecimal storedAmount(final Long ordinal) {
        return jdbcTemplate.queryForObject("""
                SELECT dalytran_amt FROM daily_transaction WHERE ingest_seq = ?
                """, BigDecimal.class, ordinal);
    }

    /**
     * Extracts one fixed-width field from a fixture record.
     *
     * <p>Offsets are one-based and inclusive, matching COBOL reference modification and the offset map in
     * this class's documentation, so a field can be read straight from the copybook without translating
     * indices by hand at each call site.
     *
     * @param record the 350-character fixture record, exactly as stored
     * @param from   the one-based first column of the field
     * @param to     the one-based last column of the field, inclusive
     * @return the field's characters, including any padding, never trimmed
     */
    private String field(final String record, final int from, final int to) {
        return record.substring(from - 1, to);
    }

    /**
     * Walks the whole staging table once, in file order, and returns every row.
     *
     * <p>One traversal, page by page, driven by the slice's own report of whether more data follows - which
     * is the direct analogue of {@code PERFORM UNTIL END-OF-FILE = 'Y'} at
     * {@code app/cbl/CBTRN02C.cbl:L202-L219}. Several assertions are then made against the one result,
     * rather than reloading 300 rows once per assertion.
     *
     * @param chunkSize how many rows to request per page
     * @return every staged row, in ascending ingestion-ordinal order
     */
    private List<DailyTransaction> traverseOnce(final int chunkSize) {
        final List<DailyTransaction> all = new ArrayList<>();
        int page = 0;
        boolean more = true;
        while (more) {
            final Slice<DailyTransaction> slice =
                    dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(PageRequest.of(page, chunkSize));
            for (final DailyTransaction row : slice) {
                all.add(row);
            }
            more = slice.hasNext();
            page++;
        }
        return all;
    }

    /**
     * The four deliberate absences: no foreign key, no index beyond the primary key, no version column and
     * no check constraint.
     *
     * <p>Each metadata sweep is paired with a non-vacuity check against a table that <em>does</em> declare
     * the thing being looked for, so that a zero here can never be an artefact of a query that finds
     * nothing anywhere.
     */
    @Nested
    @DisplayName("The zero-constraint contract that keeps the reject engine reachable")
    final class ZeroForeignKeyContract {

        /**
         * Establishes that the card number the next test stages really is unknown to the seeded data.
         *
         * <p>Without this, the persistence assertion could pass for the wrong reason - a card number that
         * happened to be present would be persisted whether a foreign key existed or not.
         */
        @Test
        @DisplayName("the premise: the staged card number is carried by no card row and no cross-reference row")
        void thePremiseHolds() {
            final Long cards = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM card WHERE card_num = ?
                    """, Long.class, unknownCardNumber());
            final Long crossReferences = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM card_cross_reference WHERE xref_card_num = ?
                    """, Long.class, unknownCardNumber());

            assertThat(cards)
                    .as("the staged card number must resolve to no card row, or the next test proves nothing")
                    .isZero();
            assertThat(crossReferences)
                    .as("it must resolve to no cross-reference row either, since the account is reached "
                            + "through the cross-reference and CBTRN02C.cbl:393-422 is what looks it up")
                    .isZero();
        }

        /**
         * The headline assertion of this class.
         *
         * <p>A staged row whose referents are missing is exactly the input the reject engine exists to
         * classify, so the staging table has to accept it. If this fails, a foreign key has been added and
         * two of the five reject outcomes have become unreachable.
         */
        @Test
        @DisplayName("a staged row whose card number resolves to nothing is persisted, not refused")
        void unknownCardAndAccountRowPersists() {
            final Long ordinal = syntheticOrdinal(1);
            final DailyTransaction staged = stagingRow(ordinal, "0000000000000001", "POS TERM",
                    new BigDecimal("504.77"), "72112", unknownCardNumber(), blanks(26));

            dailyTransactionRepository.save(staged);
            flushAndClear();

            final Optional<DailyTransaction> reloaded = dailyTransactionRepository.findById(ordinal);

            assertThat(reloaded)
                    .as("CBTRN02C.cbl:380-392 assigns reject 100 INVALID CARD NUMBER FOUND and "
                            + "CBTRN02C.cbl:393-422 assigns reject 101 ACCOUNT RECORD NOT FOUND; both are "
                            + "reached only by loading a row whose referents are missing and rejecting it "
                            + "inside the application. A foreign key on daily_transaction would refuse the "
                            + "row at load time and make both codes unreachable, which disables the reject "
                            + "engine outright")
                    .isPresent();

            final DailyTransaction row = reloaded.orElseThrow();
            assertThat(row.getCardNumber())
                    .as("the sixteen-character field is stored at its declared width")
                    .hasSize(16);
            assertThat(row.getTransactionId()).isEqualTo("0000000000000001");
            assertThat(row.getAmount())
                    .as("money is compared by compareTo, never by equals, because equality on BigDecimal "
                            + "includes scale")
                    .isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(dailyTransactionRepository.count())
                    .as("the row really landed: 300 seeded rows plus this one")
                    .isEqualTo(301L);
        }

        /**
         * The positive, machine-checkable form of the prohibition.
         */
        @Test
        @DisplayName("no foreign key touches the staging table, as child or as parent")
        void noForeignKeyTouchesTheStagingTable() {
            assertThat(foreignKeyCount("card"))
                    .as("the sweep must be able to find a foreign key at all: card is both the child of "
                            + "fk01_card_account and the parent of fk04_transaction_card")
                    .isPositive();

            assertThat(foreignKeyCount("daily_transaction"))
                    .as("V1 declares ten foreign keys, fk01_card_account through fk10_discgrp_category, and "
                            + "the staging table is party to none of them. Adding one is forbidden: the "
                            + "reject codes assigned at CBTRN02C.cbl:385 and :397 would never be reached")
                    .isZero();
        }

        /**
         * The staging table is read front to back exactly once, so there is nothing for a secondary index to
         * accelerate.
         */
        @Test
        @DisplayName("the only index is the primary key, so all three alternate-index replacements are elsewhere")
        void theOnlyIndexIsThePrimaryKey() {
            assertThat(indexNames("card"))
                    .as("the sweep must be able to see a secondary index at all")
                    .contains("idx_card_acct_id");

            assertThat(indexNames("daily_transaction"))
                    .as("V2 creates exactly three non-unique B-tree indexes, one per alternate index in the "
                            + "catalogue, and none of them is on this table. A sequential read front to back "
                            + "has nothing for a secondary index to accelerate")
                    .containsExactly("pk_daily_transaction");
        }

        /**
         * Optimistic locking has no subject on a table that is loaded once and read once.
         */
        @Test
        @DisplayName("there is no version column, because a staged row is written once and never updated")
        void thereIsNoVersionColumn() {
            assertThat(columnCount("account", "version"))
                    .as("the sweep must be able to see a version column at all")
                    .isEqualTo(1L);

            assertThat(columnCount("daily_transaction", "version"))
                    .as("version BIGINT NOT NULL exists on exactly four tables - account, card, customer "
                            + "and transaction - and this is not one of them. Nothing rewrites a staged row, "
                            + "so there is no concurrent update for a counter to detect")
                    .isZero();
        }

        /**
         * A staged row's business content is the posting job's judgement, not the schema's.
         */
        @Test
        @DisplayName("there is no check constraint, because judging content is the posting job's job")
        void thereIsNoCheckConstraint() {
            assertThat(checkConstraintCount("user_security"))
                    .as("the sweep must be able to see a check constraint at all: user_security carries "
                            + "ck_user_security_type")
                    .isPositive();

            assertThat(checkConstraintCount("daily_transaction"))
                    .as("V1 spends its entire budget of five check constraints on the account, card, "
                            + "customer and user tables. A sixth here would pre-empt the validation cascade "
                            + "at CBTRN02C.cbl:370-378, which is the only place a staged row may be judged")
                    .isZero();
        }
    }

    /**
     * The one access pattern the source has: open, read forward to end of file, close.
     *
     * <p>Nothing here exercises a finder by card number, account, amount, source, timestamp or merchant,
     * because no such finder is declared and none may be. The legacy program reads this dataset
     * sequentially, front to back, once.
     */
    @Nested
    @DisplayName("Sequential read: the only access pattern the source has")
    final class SequentialReadContract {

        /**
         * The largest seed in the schema, and the figure every other assertion in this class is measured
         * against.
         */
        @Test
        @DisplayName("the seeded row count is exactly 300, the largest seed in the schema")
        void countIsExactlyThreeHundred() {
            assertThat(dailyTransactionRepository.count())
                    .as("the seed loads app/data/ASCII/dailytran.txt in full: 300 records of 350 bytes. A "
                            + "different number here means either the seed changed or a row leaked from "
                            + "another test method, since isolation is by transactional rollback alone")
                    .isEqualTo(300L);
        }

        /**
         * One traversal, several properties.
         *
         * <p>The ordinal is the primary key, so the ordering it imposes is total: chunk boundaries are
         * stable and no row can be skipped or repeated across them. That is asserted directly by comparing
         * the walk against the dense sequence 1 through 300.
         */
        @Test
        @DisplayName("one forward traversal returns all 300 rows exactly once, in dense ascending ordinal order")
        void oneTraversalReadsEveryRowOnceInOrder() {
            final List<DailyTransaction> walked = traverseOnce(50);
            final List<Long> ordinals = new ArrayList<>();
            for (final DailyTransaction row : walked) {
                ordinals.add(row.getIngestSequence());
            }

            assertThat(ordinals)
                    .as("the ordinal models WS-TRANSACTION-COUNT PIC 9(09), declared at "
                            + "CBTRN02C.cbl:185 and incremented once per accepted read at :206, so it is "
                            + "one-based, dense and in file order. Because it is also the primary key the "
                            + "ordering is total, which is what makes paging safe")
                    .containsExactlyElementsOf(LongStream.rangeClosed(1L, 300L).boxed().toList());
        }

        /**
         * Six pages of fifty, and the sixth reports no successor.
         *
         * <p>Complementary, non-overlapping windows in one ascending order: this is the shape a chunk-oriented
         * reader depends on, and a slice reports only whether more data follows rather than paying for a
         * count on every chunk.
         */
        @Test
        @DisplayName("forward paging yields complementary, non-overlapping windows in the same ascending order")
        void forwardPagingYieldsComplementaryWindows() {
            final Slice<DailyTransaction> first =
                    dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(PageRequest.of(0, 50));
            final Slice<DailyTransaction> second =
                    dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(PageRequest.of(1, 50));

            final List<Long> firstWindow = new ArrayList<>();
            for (final DailyTransaction row : first) {
                firstWindow.add(row.getIngestSequence());
            }
            final List<Long> secondWindow = new ArrayList<>();
            for (final DailyTransaction row : second) {
                secondWindow.add(row.getIngestSequence());
            }

            assertThat(first.hasNext())
                    .as("more data follows the first window, which is the end-of-data question the loop at "
                            + "CBTRN02C.cbl:202-219 asks")
                    .isTrue();
            assertThat(firstWindow).hasSize(50).isSorted();
            assertThat(secondWindow).hasSize(50).isSorted();
            assertThat(secondWindow)
                    .as("the windows must not overlap, or a chunk-oriented reader would post a row twice")
                    .doesNotContainAnyElementsOf(firstWindow);
            assertThat(secondWindow.get(0))
                    .as("and they must be contiguous in the one ascending order")
                    .isGreaterThan(firstWindow.get(firstWindow.size() - 1));
        }

        /**
         * End of data is a signal, not a failure - the counterpart of file status {@code '10'}.
         */
        @Test
        @DisplayName("a page past the end is the end-of-data signal rather than an error")
        void pastTheEndIsAnEmptySlice() {
            final Slice<DailyTransaction> beyond =
                    dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(PageRequest.of(100, 50));

            assertThat(beyond.getContent())
                    .as("file status '10' at CBTRN02C.cbl:345-369 sets the end-of-file flag and terminates "
                            + "the loop rather than failing, and an empty slice is its counterpart")
                    .isEmpty();
            assertThat(beyond.hasNext()).isFalse();
        }

        /**
         * An absent key is an empty answer, never an exception.
         */
        @Test
        @DisplayName("an absent ordinal yields an empty optional and throws nothing")
        void findByIdAbsentIsEmpty() {
            final Long absent = syntheticOrdinal(999);

            assertThatCode(() -> dailyTransactionRepository.findById(absent))
                    .as("a miss is an ordinary outcome on a staging read, not a fault")
                    .doesNotThrowAnyException();
            assertThat(dailyTransactionRepository.findById(absent)).isEmpty();
        }

        /**
         * Why the fixed width of {@code DALYTRAN-ID} is load-bearing even though it is not the key.
         *
         * <p>The identifiers are zero-padded fixed-width digits, so byte order equals numeric order - which
         * is what let the legacy file be browsed in key sequence at all. The contrast with the unpadded forms
         * is asserted alongside, because that is the failure a lost pad character would produce.
         */
        @Test
        @DisplayName("a zero-padded identifier keeps byte order equal to numeric order across the round trip")
        void paddedIdentifiersSortAsNumbers() {
            final Long lower = syntheticOrdinal(2);
            final Long higher = syntheticOrdinal(3);
            dailyTransactionRepository.save(stagingRow(lower, "0000000000000009", "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), blanks(26)));
            dailyTransactionRepository.save(stagingRow(higher, "0000000000000010", "POS TERM",
                    new BigDecimal("2.00"), "72112", unknownCardNumber(), blanks(26)));
            flushAndClear();

            final String nine = dailyTransactionRepository.findById(lower).orElseThrow().getTransactionId();
            final String ten = dailyTransactionRepository.findById(higher).orElseThrow().getTransactionId();

            assertThat(nine).hasSize(16);
            assertThat(ten).hasSize(16);
            assertThat(nine.compareTo(ten))
                    .as("padded to sixteen characters, the smaller number sorts first, which is why a "
                            + "byte-ordered browse over this field behaved numerically")
                    .isNegative();
            assertThat(nine.replaceFirst("^0+", "").compareTo(ten.replaceFirst("^0+", "")))
                    .as("strip the leading zeros off the very same persisted values and the order reverses - "
                            + "9 sorts after 10 - which is exactly the defect a lost pad character would "
                            + "introduce, and why no reader here trims a fixed-width field")
                    .isPositive();
        }
    }

    /**
     * The thirteen copybook fields: their types, their widths and their values across a round trip.
     *
     * <p>Three mappings would each break parity if made, and each is asserted against
     * here: a temporal type on either timestamp, an enumerated type on the source, and any normalisation of
     * the amount's sign.
     */
    @Nested
    @DisplayName("Field contract: text where the copybook says text, signed decimal where it says signed")
    final class FieldContractRoundTrip {

        /**
         * Every field at its declared width, out and back.
         */
        @Test
        @DisplayName("a fully populated row round-trips at every declared width")
        void fullyPopulatedRowRoundTrips() {
            final Long ordinal = syntheticOrdinal(4);
            dailyTransactionRepository.save(stagingRow(ordinal, "0000000000683580", "POS TERM",
                    new BigDecimal("504.77"), "72112", unknownCardNumber(), blanks(26)));
            flushAndClear();

            final DailyTransaction row = dailyTransactionRepository.findById(ordinal).orElseThrow();

            assertThat(row.getIngestSequence()).isEqualTo(ordinal);
            assertThat(row.getTransactionId()).hasSize(16).isEqualTo("0000000000683580");
            assertThat(row.getTypeCode()).hasSize(2).isEqualTo("01");
            assertThat(row.getCategoryCode()).isEqualTo(1);
            assertThat(row.getTransactionSource()).hasSize(10).isEqualTo(padTo("POS TERM", 10));
            assertThat(row.getDescription()).hasSize(100);
            assertThat(row.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(row.getMerchantId())
                    .as("the single merchant identifier all 300 fixture records carry")
                    .isEqualTo(800_000_000L);
            assertThat(row.getMerchantName()).hasSize(50);
            assertThat(row.getMerchantCity()).hasSize(50);
            assertThat(row.getMerchantZip()).hasSize(10).isEqualTo(padTo("72112", 10));
            assertThat(row.getCardNumber()).hasSize(16);
            assertThat(row.getOrigTs()).hasSize(26).isEqualTo(onlineProducerTimestamp());
            assertThat(row.getProcTs()).hasSize(26).isBlank();
        }

        /**
         * The decisive negative evidence against a temporal type.
         *
         * <p>Twenty-six blanks are not a parseable date and time in any format, so a column that has to hold
         * them cannot be temporal. The value survives because {@code character(26)} stores it blank-padded
         * and returns it that way.
         */
        @Test
        @DisplayName("a 26-blank processing timestamp survives the round trip as 26 blanks")
        void blankProcessingTimestampSurvives() {
            final Long ordinal = syntheticOrdinal(5);
            dailyTransactionRepository.save(stagingRow(ordinal, "0000000000000001", "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), blanks(26)));
            flushAndClear();

            final DailyTransaction row = dailyTransactionRepository.findById(ordinal).orElseThrow();

            assertThat(row.getProcTs())
                    .as("bytes 305 to 330 of all 300 fixture records hold one value: 26 spaces. A temporal "
                            + "column could not hold it, so DALYTRAN-PROC-TS PIC X(26) maps to CHAR(26) text "
                            + "and this table is the strongest proof of that rule in the corpus")
                    .isNotNull()
                    .hasSize(26)
                    .isBlank();
            assertThat(row.getOrigTs())
                    .as("the originating stamp on the same row is populated text of the same width, so the "
                            + "blank is a value rather than a missing column")
                    .hasSize(26)
                    .isNotBlank();
        }

        /**
         * Three producers, one width, no common parse.
         *
         * <p>Text is the only representation that can carry all three, which is the whole argument for
         * {@code CHAR(26)}. Every value here is derived from the harness's fixed clock, so the assertion is
         * deterministic and no wall clock is read.
         */
        @Test
        @DisplayName("all three timestamp producers are 26 characters wide and none is interconvertible")
        void threeProducersOneWidthNoCommonParse() {
            final String online = onlineProducerTimestamp();
            final String batch = batchProducerTimestamp();
            final String passThrough = blanks(26);

            assertThat(online).hasSize(26).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(batch)
                    .as("the batch producer writes a dash before the hour, dots between the time parts, and "
                            + "hundredths followed by four literal zeros - never nanoseconds")
                    .hasSize(26)
                    .isEqualTo("2022-06-10-19.27.53.000000")
                    .endsWith("0000");
            assertThat(passThrough).hasSize(26).isBlank();
            assertThat(online)
                    .as("the same instant renders differently under the two populated producers, so no "
                            + "single temporal format could round-trip both, let alone the blank third")
                    .isNotEqualTo(batch);
        }

        /**
         * The column types themselves, read from the catalogue.
         */
        @Test
        @DisplayName("both timestamp columns are declared character(26), so neither is temporal")
        void bothTimestampColumnsAreText() {
            assertThat(columnMetadata("daily_transaction", "dalytran_orig_ts"))
                    .as("DALYTRAN-ORIG-TS PIC X(26) is character data, not a timestamp")
                    .containsEntry("data_type", "character")
                    .containsEntry("character_maximum_length", 26);
            assertThat(columnMetadata("daily_transaction", "dalytran_proc_ts"))
                    .as("DALYTRAN-PROC-TS PIC X(26) likewise, and it is the one that must hold 26 blanks")
                    .containsEntry("data_type", "character")
                    .containsEntry("character_maximum_length", 26);
        }

        /**
         * The sign is data, and normalising it would change the arithmetic downstream.
         */
        @Test
        @DisplayName("a negative amount round-trips with its sign intact")
        void negativeAmountKeepsItsSign() {
            final Long ordinal = syntheticOrdinal(6);
            dailyTransactionRepository.save(stagingRow(ordinal, "0000000001774260", "OPERATOR",
                    new BigDecimal("-919.00"), "53378", unknownCardNumber(), blanks(26)));
            flushAndClear();

            final BigDecimal reloaded = dailyTransactionRepository.findById(ordinal).orElseThrow().getAmount();

            assertThat(reloaded)
                    .as("CBTRN02C.cbl:547-552 adds the amount to the current-cycle credit when it is "
                            + "non-negative and to the current-cycle debit otherwise, so the debit "
                            + "accumulator legitimately holds negative values - which is exactly why the "
                            + "over-limit formula at :403-405 subtracts it. Taking an absolute value or "
                            + "adding a non-negative constraint is forbidden")
                    .isNegative()
                    .isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(reloaded.compareTo(BigDecimal.ZERO))
                    .as("compared by compareTo, the persisted value is strictly less than zero")
                    .isNegative();
        }

        /**
         * The middle of the three precision tiers, confirmed rather than assumed.
         */
        @Test
        @DisplayName("the amount column is NUMERIC(11,2), from PIC S9(09)V99")
        void amountIsPrecisionElevenScaleTwo() {
            assertThat(columnMetadata("daily_transaction", "dalytran_amt"))
                    .as("DALYTRAN-AMT PIC S9(09)V99 is nine integer digits and two decimals. Account money "
                            + "is S9(10)V99 and maps to NUMERIC(12,2); a disclosure rate is S9(04)V99 and "
                            + "maps to NUMERIC(6,2). Getting this tier wrong silently truncates or widens")
                    .containsEntry("data_type", "numeric")
                    .containsEntry("numeric_precision", 11)
                    .containsEntry("numeric_scale", 2);
        }

        /**
         * Plain text, because the accepted values come from three producers that do not agree.
         */
        @Test
        @DisplayName("the source column is character(10) text and accepts POS TERM, OPERATOR and System alike")
        void sourceIsPlainTextNotAnEnumeration() {
            dailyTransactionRepository.save(stagingRow(syntheticOrdinal(7), "0000000000000001", "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), blanks(26)));
            dailyTransactionRepository.save(stagingRow(syntheticOrdinal(8), "0000000000000002", "OPERATOR",
                    new BigDecimal("2.00"), "72112", unknownCardNumber(), blanks(26)));
            dailyTransactionRepository.save(stagingRow(syntheticOrdinal(9), "0000000000000003", "System",
                    new BigDecimal("3.00"), "72112", unknownCardNumber(), blanks(26)));
            flushAndClear();

            assertThat(dailyTransactionRepository.findById(syntheticOrdinal(7)).orElseThrow()
                    .getTransactionSource())
                    .as("the value 250 of the 300 fixture records carry")
                    .isEqualTo(padTo("POS TERM", 10));
            assertThat(dailyTransactionRepository.findById(syntheticOrdinal(8)).orElseThrow()
                    .getTransactionSource())
                    .as("the value the other 50 carry")
                    .isEqualTo(padTo("OPERATOR", 10));
            assertThat(dailyTransactionRepository.findById(syntheticOrdinal(9)).orElseThrow()
                    .getTransactionSource())
                    .as("and the literal app/cbl/CBACT04C.cbl:L484 writes for a generated interest "
                            + "transaction. Three producers, three unrelated values: mapping this column to "
                            + "an enumerated type would refuse or mistranslate accepted input, forbidden")
                    .isEqualTo(padTo("System", 10));

            assertThat(columnMetadata("daily_transaction", "dalytran_source"))
                    .containsEntry("data_type", "character")
                    .containsEntry("character_maximum_length", 10);
        }

        /**
         * The postal code carries 300 distinct formats across the fixture and is validated nowhere.
         */
        @Test
        @DisplayName("a hyphenated merchant postal code persists unvalidated")
        void heterogeneousMerchantZipPersists() {
            final Long ordinal = syntheticOrdinal(10);
            dailyTransactionRepository.save(stagingRow(ordinal, "0000000996722787", "POS TERM",
                    new BigDecimal("603.22"), "53200-7529", unknownCardNumber(), blanks(26)));
            flushAndClear();

            assertThat(dailyTransactionRepository.findById(ordinal).orElseThrow().getMerchantZip())
                    .as("the fixture's 300 records carry 300 distinct postal codes in mixed formats, five "
                            + "digits and hyphenated nine alike, and the last record carries this one. A "
                            + "format constraint here would refuse real input: forbidden")
                    .isEqualTo("53200-7529")
                    .hasSize(10);
        }

        /**
         * One census over the seeded rows, asserting what the seed actually loaded.
         *
         * <p>A single traversal answers every question below, rather than reloading 300 rows once per
         * assertion. <strong>No overpunch decoding happens here</strong>: the sign census is taken from the
         * persisted decimal values, because decoding the fixture's trailing overpunch character is the seed
         * migration's responsibility and duplicating it here would test this file against itself.
         */
        @Test
        @DisplayName("one census over all 300 seeded rows: timestamps, sign split, source split, merchant id")
        void oneCensusOverTheSeededRows() {
            final List<DailyTransaction> rows = traverseOnce(100);
            assertThat(rows).hasSize(300);

            int negative = 0;
            int positive = 0;
            int zero = 0;
            int posTerm = 0;
            int operator = 0;
            BigDecimal lowest = rows.get(0).getAmount();
            BigDecimal highest = rows.get(0).getAmount();
            for (final DailyTransaction row : rows) {
                assertThat(row.getProcTs()).hasSize(26).isBlank();
                assertThat(row.getOrigTs()).isEqualTo(onlineProducerTimestamp());
                assertThat(row.getMerchantId()).isEqualTo(800_000_000L);
                assertThat(row.getTransactionId()).hasSize(16).containsOnlyDigits();
                assertThat(row.getCardNumber()).hasSize(16).containsOnlyDigits();
                assertThat(row.getMerchantZip()).hasSize(10);

                final int sign = row.getAmount().compareTo(BigDecimal.ZERO);
                if (sign < 0) {
                    negative++;
                } else if (sign > 0) {
                    positive++;
                } else {
                    zero++;
                }
                if (padTo("POS TERM", 10).equals(row.getTransactionSource())) {
                    posTerm++;
                } else if (padTo("OPERATOR", 10).equals(row.getTransactionSource())) {
                    operator++;
                }
                if (row.getAmount().compareTo(lowest) < 0) {
                    lowest = row.getAmount();
                }
                if (row.getAmount().compareTo(highest) > 0) {
                    highest = row.getAmount();
                }
            }

            assertThat(negative)
                    .as("50 of the 300 records carry a debit, which is what exercises the cycle-debit branch "
                            + "at CBTRN02C.cbl:547-552; a fixture normalised to non-negative would never "
                            + "reach it")
                    .isEqualTo(50);
            assertThat(positive).isEqualTo(250);
            assertThat(zero)
                    .as("no record carries a zero amount, so the sign branch is always decided")
                    .isZero();
            assertThat(posTerm).isEqualTo(250);
            assertThat(operator).isEqualTo(50);
            assertThat(posTerm + operator)
                    .as("the source column carries exactly two distinct values across the seed")
                    .isEqualTo(300);
            assertThat(lowest)
                    .as("the seeded amount range, sign preserved end to end")
                    .isEqualByComparingTo(new BigDecimal("-998.33"));
            assertThat(highest).isEqualByComparingTo(new BigDecimal("999.77"));
        }
    }

    /**
     * What the database refuses, and what it deliberately does not.
     *
     * <p>Each assertion checks the message <em>and</em> the cause, so a failure names the constraint that
     * fired and still carries the driver's own exception underneath it rather than a summary of it. Matching
     * on constraint names rather than on row values is also what keeps a card number out of any message.
     *
     * <p>Every case here reaches PostgreSQL through a parameterised statement rather than through the
     * repository, because the entity guards its own widths and ranges first; the last test in the group
     * asserts that guard so the two layers are both visible.
     */
    @Nested
    @DisplayName("The database boundary: what the columns enforce, and what the missing key does not")
    final class DatabaseBoundaryConstraints {

        /**
         * The real primary key is the ordinal, and it is genuinely unique.
         */
        @Test
        @DisplayName("a duplicate ingestion ordinal is refused by the primary key, with message and cause")
        void duplicateIngestSequenceIsRejected() {
            assertThatThrownBy(() -> insertStagedRow(Long.valueOf(1L), "0000000000000001", "01",
                    onlineProducerTimestamp(), new BigDecimal("1.00")))
                    .as("ingest_seq 1 is already seeded, and pk_daily_transaction is the one uniqueness this "
                            + "table declares")
                    .isInstanceOf(DuplicateKeyException.class)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("pk_daily_transaction")
                    .cause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("pk_daily_transaction");
        }

        /**
         * The counterpart, and the one the shipped fixture cannot detect.
         *
         * <p>{@code DALYTRAN-ID} looks like the key of the master transaction record it feeds, so promoting
         * it to a unique column is the intuitive mistake - and the fixture's 300 identifiers are distinct, so
         * every fixture-derived test would still pass while real input was being refused. The acceptance is
         * therefore asserted directly.
         */
        @Test
        @DisplayName("a repeated DALYTRAN-ID is accepted, because the staged dataset has no key at all")
        void repeatedTransactionIdIsAccepted() {
            final String repeated = "0000000000000042";
            dailyTransactionRepository.save(stagingRow(syntheticOrdinal(11), repeated, "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), blanks(26)));
            dailyTransactionRepository.save(stagingRow(syntheticOrdinal(12), repeated, "OPERATOR",
                    new BigDecimal("-2.00"), "72112", unknownCardNumber(), blanks(26)));

            assertThatCode(this::flushBothRows)
                    .as("both programs that open this dataset declare it ORGANIZATION IS SEQUENTIAL and "
                            + "ACCESS MODE IS SEQUENTIAL, and a physical sequential file has no key, so it "
                            + "may legitimately carry the same identifier twice and the source posts both "
                            + "records without complaint")
                    .doesNotThrowAnyException();

            assertThat(rowsCarryingTransactionId(repeated))
                    .as("two staged rows share one identifier and both are present")
                    .isEqualTo(2L);
            assertThat(dailyTransactionRepository.findById(syntheticOrdinal(11)).orElseThrow())
                    .as("and they remain distinct rows, because equality is keyed on the ordinal")
                    .isNotEqualTo(dailyTransactionRepository.findById(syntheticOrdinal(12)).orElseThrow());
        }

        /**
         * Flushes the two rows staged by {@link #repeatedTransactionIdIsAccepted()}.
         *
         * <p>Extracted so the assertion can name the flush as the thing that must not throw; inlining it
         * would leave the pending inserts to be flushed by the following query, where a failure would be
         * attributed to the wrong statement.
         */
        private void flushBothRows() {
            flushAndClear();
        }

        /**
         * Every one of the fourteen columns is {@code NOT NULL}.
         */
        @Test
        @DisplayName("omitting a business column is refused by NOT NULL, with message and cause")
        void nullBusinessColumnIsRejected() {
            assertThatThrownBy(() -> insertStagedRowWithoutDescription(syntheticOrdinal(13)))
                    .as("a fixed-width record has no concept of an absent field: every column of this table "
                            + "is NOT NULL, so a missing description is a defect rather than a blank")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("dalytran_desc")
                    .cause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("dalytran_desc")
                    .hasMessageContaining("not-null");
        }

        /**
         * The column width is enforced by PostgreSQL when the excess is not blank.
         *
         * <p>The excess must be non-blank: a value overflowing {@code character(n)} with trailing spaces
         * alone is silently truncated rather than refused, which is a documented property of the type and the
         * reason each case below overflows with a real character.
         *
         * @param column   the column being overflowed, named for the failure message only
         * @param width    the column's declared width, which the error text quotes
         * @param overLong a value one character too wide, its excess non-blank
         */
        @ParameterizedTest(name = "{0} overflowing character({1}) is refused")
        @CsvSource({
            "dalytran_id,      16, 99999999999999999",
            "dalytran_type_cd,  2, 019",
            "dalytran_orig_ts, 26, 2022-06-10 19:27:53.0000000"
        })
        @DisplayName("an over-length value whose excess is not blank is refused by the column width")
        void overLengthCharacterValueIsRejected(final String column, final int width, final String overLong) {
            final String identifier = "dalytran_id".equals(column) ? overLong : "0000000000000001";
            final String typeCode = "dalytran_type_cd".equals(column) ? overLong : "01";
            final String originating =
                    "dalytran_orig_ts".equals(column) ? overLong : onlineProducerTimestamp();

            assertThat(overLong)
                    .as("the case is only meaningful if the value really is one character too wide for %s",
                            column)
                    .hasSize(width + 1);

            assertThatThrownBy(() -> insertStagedRow(syntheticOrdinal(14), identifier, typeCode,
                    originating, new BigDecimal("1.00")))
                    .as("the 350-byte record allocates a fixed number of bytes to %s, and the column "
                            + "enforces it", column)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("value too long for type character(" + width + ")")
                    .cause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("value too long for type character(" + width + ")");
        }

        /**
         * The entity refuses the same overflow one layer earlier, and says why.
         *
         * <p>Both layers are wanted. The column is the last defence and is proved above; the entity is the
         * first, and it is what turns a database error at flush time into an immediate, field-named failure
         * at construction time. Neither substitutes for the other.
         */
        @Test
        @DisplayName("the entity guards refuse the same over-length value before the database is reached")
        void entityGuardsRefuseBeforeTheDatabase() {
            assertThatThrownBy(() -> stagingRow(syntheticOrdinal(15), "99999999999999999", "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), blanks(26)))
                    .as("the guard names the Java property and the COBOL field it maps to, so the failure "
                            + "points at the record layout rather than at the column")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("transactionId")
                    .hasMessageContaining("DALYTRAN-ID PIC X(16)")
                    .hasMessageContaining("at most 16 characters");

            assertThatThrownBy(() -> stagingRow(syntheticOrdinal(16), "0000000000000001", "POS TERM",
                    new BigDecimal("1.00"), "72112", unknownCardNumber(), null))
                    .as("null is refused too, because the column is NOT NULL and a blank is a value rather "
                            + "than an absence")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("procTs")
                    .hasMessageContaining("DALYTRAN-PROC-TS PIC X(26)");
        }

        /**
         * Why the entity refuses a third decimal digit instead of letting the column round it.
         *
         * <p>The hazard is demonstrated as well as guarded against: the same value inserted straight into the
         * column is rounded to two decimals without complaint, which is a silent change to a monetary amount.
         * That is precisely what the guard exists to prevent, and why rescaling is the caller's explicit
         * decision with {@code RoundingMode.HALF_EVEN} rather than the column's implicit one.
         */
        @Test
        @DisplayName("an over-precision amount is refused by the entity, because the column would round it")
        void overPrecisionAmountIsRefusedRatherThanRounded() {
            final BigDecimal threeDecimals = new BigDecimal("1.005");

            assertThatThrownBy(() -> stagingRow(syntheticOrdinal(17), "0000000000000001", "POS TERM",
                    threeDecimals, "72112", unknownCardNumber(), blanks(26)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amount")
                    .hasMessageContaining("DALYTRAN-AMT PIC S9(09)V99")
                    .hasMessageContaining("scale");

            final Long ordinal = syntheticOrdinal(18);
            insertStagedRow(ordinal, "0000000000000001", "01", onlineProducerTimestamp(), threeDecimals);
            final BigDecimal stored = storedAmount(ordinal);

            assertThat(stored)
                    .as("V99 declares exactly two decimal positions, so NUMERIC(11,2) quietly rescales a "
                            + "third digit away - a silent change to a monetary amount, which is the whole "
                            + "reason the entity refuses the value instead of passing it through")
                    .isNotNull();
            assertThat(stored.scale()).isEqualTo(2);
            assertThat(stored.compareTo(threeDecimals))
                    .as("the stored value is no longer the submitted one")
                    .isNotZero();
        }
    }

    /**
     * The frozen fixture's own geometry, and its agreement with what the seed loaded.
     *
     * <p>This matters to this tier and not only to the model tier, because the fixture is the authority for
     * what the seed was supposed to produce. Reading it here is what turns "the seeded row looks plausible"
     * into "the seeded row matches the byte image it came from".
     */
    @Nested
    @DisplayName("Fixture geometry, and the seeded rows measured against it")
    final class FixtureGeometry {

        /**
         * Three hundred records of three hundred and fifty characters, and a trailing run long enough to
         * matter.
         */
        @Test
        @DisplayName("300 records of 350 characters, no carriage return, and a 46-character trailing run")
        void theFixtureGeometryIsIntact() {
            final List<String> records = readFixture("dailytran.txt");

            assertThat(records)
                    .as("the frozen census: 300 records, and the fixture loader refuses a resource that no "
                            + "longer matches it")
                    .hasSize(300);

            int longestTrailingRun = 0;
            for (final String record : records) {
                assertThat(record)
                        .as("one distinct record width, because every field is located by absolute column")
                        .hasSize(350);
                assertThat(record)
                        .as("the fixture is line-feed terminated throughout, with no carriage return")
                        .doesNotContain("\r");
                assertThat(field(record, 331, 350))
                        .as("the 20-byte FILLER at CVTRA06Y.cpy:L18 pads the record to its declared length "
                                + "and is inert on every row")
                        .isEqualTo(blanks(20));

                int run = 0;
                while (run < record.length() && record.charAt(record.length() - 1 - run) == ' ') {
                    run++;
                }
                longestTrailingRun = Math.max(longestTrailingRun, run);
            }

            assertThat(records.size() * (350 + 1))
                    .as("300 records of 350 characters plus one line feed each is 105,300 bytes, which is "
                            + "the invariant the loader checks the file size against")
                    .isEqualTo(105_300);
            assertThat(longestTrailingRun)
                    .as("the longest trailing run is 46 characters, so a trailing-whitespace cleanup would "
                            + "collapse a 350-character record to 304 and misalign every position-based read "
                            + "after it. No fixture is copied, trimmed or edited here")
                    .isEqualTo(46);
            assertThat(350 - longestTrailingRun).isEqualTo(304);
        }

        /**
         * The seeded rows against the byte image they came from, decode-free.
         *
         * <p>Only the fields whose text is carried through unchanged are compared. The amount is excluded on
         * purpose: its sign is a trailing overpunch character, decoding it is the seed migration's
         * responsibility, and repeating the decode here would test this file against its own arithmetic
         * instead of against the source. The card number is compared by shape rather than by value, so no
         * card number can reach an assertion message.
         */
        @Test
        @DisplayName("the fixture's own columns agree with the seeded row at the same ordinal")
        void theFixtureAgreesWithTheSeededRows() {
            final List<String> records = readFixture("dailytran.txt");
            final List<DailyTransaction> rows = traverseOnce(100);

            assertThat(rows).hasSize(records.size());

            for (final int index : List.of(0, 1, 299)) {
                final String record = records.get(index);
                final DailyTransaction row = rows.get(index);

                assertThat(row.getIngestSequence())
                        .as("the ordinal is the record's one-based position in the file")
                        .isEqualTo(Long.valueOf(index + 1L));
                assertThat(row.getTransactionId())
                        .as("DALYTRAN-ID occupies bytes 1 to 16 of record %s", index + 1)
                        .isEqualTo(field(record, 1, 16));
                assertThat(row.getTypeCode()).isEqualTo(field(record, 17, 18));
                assertThat(row.getCategoryCode())
                        .as("DALYTRAN-CAT-CD is four display digits at bytes 19 to 22")
                        .isEqualTo(Integer.valueOf(field(record, 19, 22)));
                assertThat(row.getTransactionSource())
                        .as("DALYTRAN-SOURCE occupies bytes 23 to 32 and is carried through as text")
                        .isEqualTo(field(record, 23, 32));
                assertThat(row.getDescription()).isEqualTo(field(record, 33, 132));
                assertThat(field(record, 133, 143))
                        .as("DALYTRAN-AMT occupies eleven bytes, whose trailing character is the zoned "
                                + "overpunch sign. Its width is asserted here; its value is decoded by the "
                                + "seed migration and never by this tier")
                        .hasSize(11);
                assertThat(row.getMerchantId())
                        .isEqualTo(Long.valueOf(field(record, 144, 152)));
                assertThat(row.getMerchantName()).isEqualTo(field(record, 153, 202));
                assertThat(row.getMerchantCity()).isEqualTo(field(record, 203, 252));
                assertThat(row.getMerchantZip()).isEqualTo(field(record, 253, 262));
                assertThat(row.getCardNumber())
                        .as("compared by shape rather than by value, so no card number reaches a message")
                        .hasSize(field(record, 263, 278).length());
                assertThat(row.getOrigTs())
                        .as("DALYTRAN-ORIG-TS occupies bytes 279 to 304 and is one distinct value "
                                + "throughout")
                        .isEqualTo(field(record, 279, 304));
                assertThat(row.getProcTs())
                        .as("DALYTRAN-PROC-TS occupies bytes 305 to 330 and is 26 blanks throughout")
                        .isEqualTo(field(record, 305, 330))
                        .isBlank();
            }
        }
    }
}
