/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier
 * Function    : Proves the persistence contract of the transaction
 *               category balance table against a real PostgreSQL 16:
 *               the three-part composite key, the NUMERIC(11,2)
 *               precision tier, the signed balance, the key-ordered
 *               browse the interest job's account control break
 *               depends on, and the read-then-branch upsert seam whose
 *               "record not found" status the posting job accepts as a
 *               create path rather than an error.
 * Source      : app/cpy/CVTRA01Y.cpy:5-10 (TRAN-CAT-KEY 17 B, RECLN 50,
 *               TRAN-CAT-BAL PIC S9(09)V99),
 *               app/cpy/CVTRA04Y.cpy:5 (the colliding 6-byte
 *               TRAN-CAT-KEY of a different cluster),
 *               app/catlg/LISTCAT.txt:1371 (KEYLEN 17 AVGLRECL 50) and
 *               :1373 (UNIQUE, no NONUNIQKEY),
 *               app/jcl/TCATBALF.jcl (DEFINE CLUSTER KEYS(17 0)
 *               RECORDSIZE(50 50)),
 *               app/cbl/CBTRN02C.cbl:467-542 (2700-UPDATE-TCATBAL, the
 *               '23'-accepted read and both additive branches),
 *               app/cbl/CBACT04C.cbl:188-222 (the key-ordered browse
 *               and account-level control break),
 *               app/csd/CARDDEMO.CSD (zero TCATBALF entries),
 *               app/data/ASCII/tcatbal.txt @ 7756d89
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

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.TransactionCategoryBalanceRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for {@link TransactionCategoryBalanceRepository} against a real PostgreSQL 16 supplied by
 * {@link AbstractRepositoryIntegrationTest}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>{@code transaction_category_balance} replaces the VSAM cluster
 * {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}. It is unusual among the eleven tables in being read and written
 * by two batch programs with opposite access patterns, and both patterns are asserted here at the repository
 * seam and nowhere else.
 *
 * <h3>The field contract</h3>
 *
 * <p>From {@code app/cpy/CVTRA01Y.cpy:5-10}, read verbatim at {@code 7756d89}:
 * {@code 05 TRAN-CAT-KEY} at {@code :L5} groups {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code :L6},
 * {@code TRANCAT-TYPE-CD PIC X(02)} at {@code :L7} and {@code TRANCAT-CD PIC 9(04)} at {@code :L8}, followed
 * by {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code :L9} and {@code FILLER PIC X(22)} at {@code :L10}. The key
 * is therefore 11 + 2 + 4 = <strong>17</strong> bytes and the record is 17 + 11 + 22 = <strong>50</strong>,
 * which is exactly what {@code app/catlg/LISTCAT.txt:1371} reports as {@code KEYLEN 17  AVGLRECL 50} and what
 * {@code app/jcl/TCATBALF.jcl} defines as {@code KEYS(17 0)} and {@code RECORDSIZE(50 50)}.
 *
 * <p><strong>The third key member is spelled {@code TRANCAT-CD}, not {@code TRANCAT-CAT-CD}</strong>
 * ({@code CVTRA01Y.cpy:L8}). This matters, because the near-miss name is the one a reader
 * expects and citing it would make the traceability claim unverifiable.
 *
 * <h3>The {@code TRAN-CAT-KEY} name collision</h3>
 *
 * <p>The group name {@code TRAN-CAT-KEY} is declared in <em>two</em> copybooks with two different shapes:
 * {@code CVTRA01Y.cpy:L5} is 17 bytes over three components and belongs to this table, while
 * {@code CVTRA04Y.cpy:L5} is 6 bytes over two components and belongs to {@code transaction_category}.
 * {@link TransactionCategoryBalanceId} and {@code TransactionCategoryId} are consequently two distinct types
 * with no shared base class, no shared interface and no shared abstraction, and this test touches only the
 * former.
 *
 * <p>The accessor spellings differ deliberately and are not harmonised.
 * {@link TransactionCategoryBalanceId} exposes {@code accountId}, {@code typeCd} and {@code catCd};
 * {@code TransactionCategoryId} and {@code DisclosureGroupId} expose {@code tranTypeCd} and
 * {@code tranCatCd}. Each class is used with its own spelling verbatim.
 *
 * <h3>The read-then-branch upsert, and the accepted "not found" status</h3>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} paragraph {@code 2700-UPDATE-TCATBAL} at {@code :L467-L501} moves the
 * three key components at {@code :L469-L471}, clears its create flag at {@code :L473}, and reads the record
 * at {@code :L474-L479} with an {@code INVALID KEY} branch that displays
 * {@code 'TCATBAL record not found for key : '} and sets the flag to {@code 'Y'}. The guard at
 * <strong>{@code :L481}</strong> then reads {@code IF TCATBALF-STATUS = '00' OR '23'}, so
 * <strong>file status {@code '23'} - record not found - is an accepted control path here, not an
 * error</strong>. That tolerance exists at exactly three sites in the whole
 * corpus and nowhere else: this read; the disclosure-group first read at {@code app/cbl/CBACT04C.cbl:L422},
 * which likewise accepts {@code '00' OR '23'}; and the file-service call sites of {@code CBSTM03B}, which
 * accept {@code '00' OR '04'}. A blanket status-to-exception rule would abend all three, which is why
 * {@code com.cardemo.service.shared.FileStatusMapper} is aware of the exceptions.
 *
 * <p>The dispatch at {@code :L495-L499} then selects one of two branches, and
 * <strong>both branches add</strong>: the create branch at {@code :L503-L524} initialises the record, moves
 * the three key components and performs {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at {@code :L508} before its
 * {@code WRITE} at {@code :L510}; the update branch at {@code :L526-L542} performs the identical
 * {@code ADD} at {@code :L527} before its {@code REWRITE} at {@code :L528}.
 * <strong>Neither branch replaces the balance.</strong> The tolerance for
 * {@code '23'} is confined to the initial read: the {@code WRITE} guard at {@code :L512} and the
 * {@code REWRITE} guard at {@code :L530} each accept {@code '00'} and nothing else.
 *
 * <p>There is a semantic tension between the two halves of that design which is worth stating, because it
 * explains why the legacy code cannot be simplified. The primary key really is unique -
 * {@code LISTCAT.txt:1373} shows {@code UNIQUE} with no {@code NONUNIQKEY} - so a blind {@code WRITE} of an
 * existing key would fail. The read-then-branch is therefore not redundant caution; it is the only way to
 * reach either verb safely, and the {@code '23'} tolerance is what makes the create leg reachable at all.
 *
 * <h3>The key-ordered browse</h3>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L188-L222} browses this file sequentially in key order.
 * {@code :L188} opens {@code PERFORM UNTIL END-OF-FILE = 'Y'}, {@code :L190} performs
 * {@code 1000-TCATBALF-GET-NEXT}, and <strong>{@code :L194} breaks on
 * {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}</strong>, flushing the previous account at {@code :L196}
 * and resetting the running total at {@code :L200}. That control break is correct only because the key leads
 * with the account identifier, so every row of one account is <em>contiguous</em> in key order. An unordered
 * read would fire the break mid-account and produce wrong interest for every account <em>with no error and
 * no diagnostic</em>, which is why the ordering assertion below is critical.
 *
 * <h3>Boundaries: what is cited here and asserted elsewhere</h3>
 *
 * <p>This class asserts persistence, mapping, ordering and constraint behaviour. It deliberately asserts
 * none of the following, each of which is owned by another tier and is cited only so that the omission is
 * visible rather than accidental.
 *
 * <ul>
 *   <li>The upsert <em>dispatch</em> itself. Selecting the create or update branch belongs to
 *       {@code com.cardemo.batch.processors.TransactionPostingProcessor} and the batch writers, and is
 *       asserted by {@code unit/batch} and {@code integration/batch}. What is asserted here is only that the
 *       two paths are distinguishable at this seam, by the presence or absence of the row.</li>
 *   <li>The control break, the interest formula, the zero-rate skip, and {@code 1400-COMPUTE-FEES} at
 *       {@code CBACT04C.cbl:518-520} - an empty yet genuinely reachable paragraph, performed at
 *       {@code :216}. It is {@code NOOP-CBACT04C-1400} in the locator-keyed register of
 *       {@code DL-CR-01}, whose rows all live in {@code com.cardemo.batch.processors} or
 *       {@code com.cardemo.model.enums}; <strong>this package carries no dead-code exemption
 *       whatsoever</strong>. Note in passing that
 *       {@code CBACT04C.cbl:219-220} is unreachable as written, being the {@code ELSE} of the
 *       {@code IF END-OF-FILE = 'N'} at {@code :189} inside the {@code PERFORM UNTIL END-OF-FILE = 'Y'} at
 *       {@code :188}. This matters, and a batch-tier parity question rather than one for
 *       this seam.</li>
 *   <li>The reject engine, the exit code and the atomicity of the three posting writes.
 *       {@code CBTRN02C.cbl:2000-POST-TRANSACTION} at {@code :L424-L444} performs the category-balance
 *       upsert, the account update and the transaction insert at {@code :L440-L442} as three independent
 *       legacy commits, which become one Java transaction; return code 4 is set at {@code :L229-L230} if and
 *       only if the reject count exceeds zero. Reject code {@code 109}, assigned at {@code :L556} on the
 *       account-rewrite failure path <em>inside the already-validated posting routine</em> and cleared on
 *       the next iteration at {@code :L208}, is never consumed as a reject - and it is precisely the path
 *       that in the legacy system leaves an orphaned category-balance row alongside an orphaned transaction
 *       row, because those three writes committed separately. The Java transaction boundary closes that
 *       hazard, which is a deliberate deviation rather than parity. It is described here, in the docstring of
 *       a file it governs, where it cannot drift from the code. Cited,
 *       not tested: cross-write atomicity is {@code integration/batch}'s.</li>
 *   <li>The key class's own contract - equality, {@code hashCode}, the 17-byte width sum,
 *       {@code serialVersionUID}, and inequality against {@code TransactionCategoryId} - which is owned by
 *       {@code com.cardemo.unit.model.TransactionCategoryBalanceIdTest}. The one part that belongs here is
 *       that the contract is wired correctly <em>through Hibernate's identity map</em>, which is what a
 *       {@code findById} by a freshly constructed key proves.</li>
 *   <li>Any {@code version} column, any index, any check constraint, any mapped association and any REST
 *       surface, because this table has none of them. {@code version BIGINT} exists on exactly four entities
 *       ({@code account}, {@code card}, {@code customer}, {@code "transaction"}); {@code V2} creates exactly
 *       three non-unique B-tree indexes and none on this table - <strong>the ordered browse is served by the
 *       primary key itself, which is why no fourth index is needed and none may be added "helpfully"</strong>;
 *       {@code V1} carries exactly five check constraints and none on this table; and
 *       {@code app/csd/CARDDEMO.CSD} holds exactly eight {@code DEFINE FILE} entries with
 *       <strong>{@code TCATBALF} appearing zero times</strong>, which is the evidence that this is a
 *       batch-only dataset with no online definition and therefore no controller.</li>
 *   </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Run the whole gate with {@code ./mvnw clean verify}. Compile only, which is the quickest check that the
 * zero-warning settings are still satisfied, with {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe by path, not by name.</strong> {@code maven-failsafe-plugin}
 * 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java} and runs it at
 * {@code integration-test} and {@code verify}; {@code maven-surefire-plugin} 3.5.4 explicitly excludes that
 * tree. Moving this file up to {@code com/cardemo/integration}, to {@code com/cardemo}, or into
 * {@code src/test/java} directly would match <em>neither</em> include set, so it would be collected by
 * neither plugin and would simply never run - a green build, two plugins reporting success, and no error and
 * no output to notice. Do not rename or relocate it.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a real PostgreSQL 16
 * container plus the Ryuk reaper, so this tier cannot run without a container runtime, and an absent socket
 * must be reported as a blocker rather than papered over as a pass. Where no host JDK is provisioned the
 * identical build runs in the pinned image:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}. At the time
 * of writing the host does provide Docker Engine, a JDK 25 and Maven 3.9.11 directly, so the container
 * indirection is a fallback rather than the norm - re-check rather than assume.
 *
 * <h2>3. Key configs and defaults</h2>
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, PostgreSQL 16 pinned <em>by digest</em> on Debian/glibc, and
 *       the datasource injected by {@code @ServiceConnection} - all three owned by the base class. This file
 *       declares no container, no {@code @SpringBootTest}, no {@code @DynamicPropertySource} and
 *       <strong>no {@code static} field of any kind</strong>. Its constants are instance-final, which is
 *       what keeps global mutable state out of the tier.</li>
 *   <li><strong>Time comes only from the harness's injected fixed clock</strong>, never from the ambient
 *       one. This class in fact reads no clock at all: every assertion is over the 50 fixed seeded rows or
 *       over values it supplies itself, so there is no instant to observe.</li>
 *   <li><strong>Schema handling</strong>: {@code ddl-auto: validate}, {@code open-in-view: false},
 *       {@code show-sql: false}, Hibernate JDBC time zone UTC, {@code spring.batch.job.enabled: false}.
 *       Exactly three Flyway migrations apply, with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and {@code baseline-on-migrate: false};
 *       the {@code BATCH_*} tables come from {@code spring.batch.jdbc.initialize-schema} and never from a
 *       fourth migration.</li>
 *   <li><strong>Money comparison policy: {@code compareTo}, never {@code equals}.</strong> PostgreSQL
 *       returns {@code NUMERIC(11,2)} at scale 2, so {@code new BigDecimal("0.00").equals(BigDecimal.ZERO)}
 *       is {@code false} while {@code compareTo} is {@code 0}. Every balance assertion below therefore uses
 *       {@code isEqualByComparingTo} or an explicit {@code compareTo}.
 * There is no {@code float} and no {@code double} anywhere in this file.</li>
 *   <li><strong>The three precision tiers are distinct and this table is the middle one.</strong>
 *       {@code PIC S9(10)V99} maps to {@code NUMERIC(12,2)} (the five account money columns);
 *       <strong>{@code PIC S9(09)V99} maps to {@code NUMERIC(11,2)}</strong> - {@code TRAN-CAT-BAL} here,
 *       plus {@code TRAN-AMT} and {@code DALYTRAN-AMT}; and {@code PIC S9(04)V99} maps to
 *       {@code NUMERIC(6,2)} ({@code DIS-INT-RATE}). Using 12,2 here is forbidden, and it
 *       is checked below against the live catalogue rather than asserted from the migration text.</li>
 *   <li><strong>Character width policy.</strong> {@code tran_type_cd} is
 *       <strong>{@code VARCHAR(2)}, not {@code CHAR(2)}</strong> - the {@code tran_type_cd} column of
 *       {@code V1__create_schema.sql}, with
 *       the reason recorded at {@code :433-437}: the embedded key component is plain text with no pinned
 *       type code. This <em>corrects</em> the working note that described the column as {@code CHAR(2)} and
 *       asked for a blank-pad policy; the honest policy is its opposite and is stated once here for the
 *       whole file. <strong>No blank padding occurs at either direction of the boundary.</strong> A
 *       two-character value is stored and returned as exactly those two characters, so no trimming or
 *       unpadding is ever applied on read and none may be introduced; a three-character value is refused
 *       outright rather than silently truncated. The fixture agrees: {@code tcatbal.txt} carries
 *       {@code '01'} in columns 12-13 with no padding to remove.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Everything fails to start with a Docker or container error.</em> No reachable Docker socket.
 *       State the blocker; do not assert an untested pass.</li>
 *   <li><em>A Testcontainers artefact will not resolve, or 1.x is resolved instead of 2.0.3.</em> The
 *       most consequential trap of the migration, whose remedy has two halves that are both
 *       required: pin 2.0.3 by <em>overriding the parent-managed version property</em> rather than importing
 *       a second bill of materials, and use only the <em>prefixed</em> coordinates
 *       {@code testcontainers}, {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}, because the bare 1.x identifiers do not exist on the 2.x line.
 *       Overriding without renaming resolves artefacts that were never published; renaming without
 *       overriding resolves the wrong version. Both halves are already correct in the root {@code pom.xml},
 *       which is root-owned and is not edited from here.</li>
 *   <li><em>The build fails on something that looks cosmetic.</em> Compilation runs {@code -Xlint:all} with
 *       {@code -Werror} and that reaches test compilation, so a single unused import, raw type, unchecked
 *       cast or deprecation is a build failure rather than a warning.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a column name.</em> {@code validate}
 *       compares type codes, so a {@code Long} over {@code NUMERIC(11)} can fail where {@code BIGINT}
 *       passes, an {@code Integer} over {@code NUMERIC(4)} where {@code INTEGER} passes, and a
 *       {@code BigDecimal} must match {@code NUMERIC(11,2)} exactly. <strong>The fix is upstream</strong> in
 *       {@code V1__create_schema.sql} or in the entity or key class. Never widen a column to silence it and
 *       never patch this test.</li>
 *   <li><em>A key does not resolve, or resolves the wrong row.</em> Almost always
 *       {@link TransactionCategoryBalanceId} confused with {@code TransactionCategoryId}, or
 *       {@code typeCd}/{@code catCd} written where {@code tranTypeCd}/{@code tranCatCd} was required or the
 *       reverse. The two key types are not interchangeable and neither are their accessors.</li>
 *   <li><em>A balance assertion fails although the numbers look identical.</em> {@code BigDecimal.equals}
 *       compared a scale-2 value from the database against a literal at another scale. Use
 *       {@code compareTo}.</li>
 *   <li><em>Balances drift upward and never fall.</em> Absolute-value normalisation was applied somewhere on
 *       the posting path. {@code PIC S9(09)V99} is signed and negative amounts must reduce the balance;
 *       there is no {@code abs()} and no non-negative constraint anywhere, and adding either is a
 * </li>
 *   <li><em>An update overwrote a balance instead of accumulating it.</em> Both legacy branches
 *       {@code ADD}; neither assigns. See {@code :L508} and {@code :L527}.</li>
 *   <li><em>"Record not found" was treated as an error.</em> {@code :L481} accepts {@code '00' OR '23'}. An
 *       absent key must yield an empty {@link Optional}, not an exception.</li>
 *   <li><em>Interest is wrong for every account yet the job reports success.</em> The browse lost its
 *       ordering, so the control break fired mid-account. Restore the three-component ordering; do not add
 *       an index and do not add an end-of-data flush.</li>
 *   <li><em>A fixture read yields {@code null} and fails far from its cause.</em> The fixtures are flat
 *       children of {@code src/test/resources} addressed by bare name, and the harness's reader fails
 *       immediately naming the resource rather than returning an empty list.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are recorded as <strong>"Not available"</strong> rather than filled in, because inventing
 * either would manufacture a false oracle.
 *
 * <ol>
 *   <li><strong>The end-to-end boundary parity expectation exists; what is Not available is a captured z/OS run to
 *       corroborate it.</strong> {@code src/test/resources/parity/gate1/} holds the frozen program's own output --
 *       {@code TRANSACT.expected}, {@code ACCTDATA.expected}, {@code TCATBALF.expected}, {@code DALYREJS.expected}
 *       and {@code CBTRN02C.sysout.expected} -- derived by compiling {@code app/cbl/CBTRN02C.cbl} unmodified and
 *       running it against the frozen fixtures, with the harness and the derivation recorded beside them in
 *       {@code PROVENANCE.properties}. What would be needed is a captured
 *       430-byte reject dataset from a real posting run at a known input state, together with the resulting
 *       transaction, account and category-balance images. Until that exists: <strong>zero baseline files are
 *       created here</strong>, no expected bytes are fabricated, and no baseline is generated by running
 *       this implementation and asserting against its own output, which would be circular. Hand-simulating
 *       the posting program is equally inadmissible and demonstrably so - two defensible models over these
 *       same fixtures disagree, one yielding 13 rejects and the other 38 - and it is doubly inadmissible
 *       here, because a stateful model changes every category balance it touches.</li>
 *   <li><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> Across the 28
 *       programs of {@code app/cbl} the literal file status {@code '35'} occurs <strong>0</strong> times and
 *       {@code DFHRESP(NOTOPEN)} <strong>0</strong> times, so that condition is specification-derived only.
 *       No test for it is invented here and no parity claim is made for it.</li>
 *   </ol>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see AbstractRepositoryIntegrationTest
 */
@DisplayName("TransactionCategoryBalanceRepository against PostgreSQL 16 - key, precision, order and upsert")
class TransactionCategoryBalanceRepositoryTest extends AbstractRepositoryIntegrationTest {

    /**
     * Rows {@code V3__seed_data.sql} loads into this table, decoded from the 50 records of
     * {@code app/data/ASCII/tcatbal.txt}.
     */
    private final int seededRowCount = 50;

    /** The single transaction type code every seeded row carries, from {@code tcatbal.txt} columns 12-13. */
    private final String seededTypeCd = "01";

    /** The single category code every seeded row carries, from {@code tcatbal.txt} columns 14-17. */
    private final int seededCatCd = 1;

    /**
     * A type and category pair that exists in {@code transaction_category} but on no seeded balance row, so a
     * key built from it is genuinely absent and satisfies both foreign keys once written. {@code 070001} is
     * the last of the 18 pairs in {@code app/data/ASCII/trancatg.txt}.
     */
    private final String absentTypeCd = "07";

    /** Category code paired with {@link #absentTypeCd}; together they form the {@code 070001} pair. */
    private final int absentCatCd = 1;

    /**
     * Parameterised insert used only where a Java-side guard would otherwise stop a value before the
     * database could refuse it, and where the database's own refusal is the thing under test. Every value is
     * bound, never concatenated.
     */
    private final String insertSql = """
            INSERT INTO transaction_category_balance
                (acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal)
            VALUES (?, ?, ?, ?)
            """;

    /** Parameterised catalogue probe for a numeric column's declared precision and scale. */
    private final String numericMetadataSql = """
            SELECT data_type, numeric_precision, numeric_scale
              FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND table_name = ?
               AND column_name = ?
            """;

    /** Parameterised catalogue probe for a character column's declared type and maximum length. */
    private final String characterMetadataSql = """
            SELECT data_type, character_maximum_length
              FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND table_name = ?
               AND column_name = ?
            """;

    @Autowired
    private TransactionCategoryBalanceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Total order over the composite key in COBOL component order: account identifier, then type code, then
     * category code.
     *
     * <p>String comparison is {@link String#compareTo}, which orders by UTF-16 code unit and is therefore
     * independent of locale and of the ambient default. That matters because the database sorts
     * {@code tran_type_cd} under its own collation, and the harness pins a glibc image by digest precisely
     * so that the two agree run after run.
     *
     * @return a comparator matching the ordering the repository's browse promises
     */
    private Comparator<TransactionCategoryBalance> keyOrder() {
        return Comparator
                .comparing((TransactionCategoryBalance row) -> row.getId().getAccountId())
                .thenComparing(row -> row.getId().getTypeCd())
                .thenComparing(row -> row.getId().getCatCd());
    }

    /**
     * Renders a composite key the way {@code CBTRN02C.cbl:L476-L477} displays {@code FD-TRAN-CAT-KEY}: the
     * eleven-digit account identifier, the two-character type code and the four-digit category code
     * concatenated with no separator, giving the 17 bytes {@code LISTCAT.txt:1371} catalogues.
     *
     * <p>Used only to make an assertion message name the row it is talking about, which is what turns a
     * failure into a diagnosis.
     *
     * @param id the key to render; must not be {@code null}
     * @return the 17-character key image
     */
    private String renderKey(TransactionCategoryBalanceId id) {
        return "%011d%s%04d".formatted(id.getAccountId(), id.getTypeCd(), id.getCatCd());
    }

    /**
     * Reads the whole table through the repository's key-ordered browse in a single chunk.
     *
     * <p>The browse is {@code Slice}-based rather than list-based because it replaces a sequential VSAM read
     * that the interest job consumes one record at a time. Asking for one chunk wider than the table is what
     * lets a single assertion cover every seeded row.
     *
     * @param expectedRows the number of rows the caller expects, used as the chunk width
     * @return the rows in {@code accountId}, {@code typeCd}, {@code catCd} order
     */
    private List<TransactionCategoryBalance> browseAll(int expectedRows) {
        Slice<TransactionCategoryBalance> slice =
                repository.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(PageRequest.ofSize(expectedRows));
        assertThat(slice.hasNext())
                .as("a chunk as wide as the table exhausts the browse; CBACT04C.cbl:188 loops "
                        + "PERFORM UNTIL END-OF-FILE = 'Y'")
                .isFalse();
        return slice.getContent();
    }

    /**
     * Walks the whole table through the key-ordered browse in fixed-width chunks and concatenates them,
     * reproducing the way the interest job actually consumes the file: one record at a time until end of
     * data, never as one materialised list.
     *
     * <p>This is the stronger form of the ordering guarantee. A query can be ordered <em>within</em> a chunk
     * and still tear across a chunk boundary, and a torn boundary is indistinguishable from correct data at
     * the row level - it shows up only as a control break firing twice for one account.
     *
     * @param chunkSize rows per chunk; must be positive
     * @return every row, in the order the successive chunks delivered them
     */
    private List<TransactionCategoryBalance> browseInChunks(int chunkSize) {
        List<TransactionCategoryBalance> walked = new ArrayList<>();
        int chunkIndex = 0;
        boolean more = true;
        while (more) {
            Slice<TransactionCategoryBalance> slice =
                    repository.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(
                            PageRequest.of(chunkIndex, chunkSize));
            walked.addAll(slice.getContent());
            more = slice.hasNext();
            chunkIndex++;
        }
        return walked;
    }

    /**
     * Builds an unsaved row from literal key components and a decimal literal, deliberately going through
     * {@link TransactionCategoryBalanceId}'s <em>public all-components constructor</em>.
     *
     * <p>That constructor is what makes the create leg of the legacy upsert expressible at all: it builds an
     * identifier for a row that <em>is not yet stored</em>, which is exactly the situation
     * {@code CBTRN02C.cbl:L505-L507} is in when it moves the three key components into a freshly initialised
     * record before its {@code WRITE}. The key type imposes no existence-dependent behaviour.
     *
     * <p>The balance arrives as a {@link BigDecimal} built from a {@link String}, never from a
     * {@code double}, so the value carries the scale it is written with and no binary rounding occurs.
     *
     * @param accountId account identifier, mapping {@code TRANCAT-ACCT-ID PIC 9(11)}
     * @param typeCd    two-character transaction type code, mapping {@code TRANCAT-TYPE-CD PIC X(02)}
     * @param catCd     category code, mapping {@code TRANCAT-CD PIC 9(04)}
     * @param balance   plain decimal text for {@code TRAN-CAT-BAL PIC S9(09)V99}, at most scale 2
     * @return a new, unmanaged entity ready to be saved
     */
    private TransactionCategoryBalance newRow(Long accountId, String typeCd, int catCd, String balance) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(accountId, typeCd, catCd), new BigDecimal(balance));
    }

    /**
     * Extracts one fixed-width field from a frozen fixture record by absolute, one-based column positions.
     *
     * <p>Position-aware extraction is the only correct way to read these records: the fields are located by
     * offset, so a trim or a split would silently shift every field after the first short row. Nothing is
     * trimmed, normalised or re-encoded here, and the fixture itself is never modified.
     *
     * @param record     one record exactly as the harness read it
     * @param fromColumn first column of the field, one-based and inclusive
     * @param toColumn   last column of the field, one-based and inclusive
     * @return the field's characters exactly as stored
     */
    private String fixtureField(String record, int fromColumn, int toColumn) {
        return record.substring(fromColumn - 1, toColumn);
    }

    /**
     * Decodes one zoned-decimal field whose trailing character carries both its last digit and its sign,
     * driven by the {@code PIC} clause rather than by a text substitution.
     *
     * <p>The overpunch alphabet is {@code '{'} for {@code +0}, {@code 'A'}-{@code 'I'} for {@code +1}
     * through {@code +9}, {@code '}'} for {@code -0} and {@code 'J'}-{@code 'R'} for {@code -1} through
     * {@code -9}. Decoding must stay positional: those same letters occur legitimately inside the text
     * fields of other fixtures, so a global replacement would corrupt them.
     *
     * <p>The authority for the decode is {@code V3__seed_data.sql}, which performs it once at seed time;
     * this method exists only to corroborate the seeded result against the frozen bytes.
     *
     * @param field        the digits-with-overpunch characters, most significant first
     * @param decimalDigits how many of the trailing digits sit after the implied decimal point
     * @return the signed decimal the field denotes, at exactly {@code decimalDigits} scale
     * @throws IllegalArgumentException if the trailing character is not a member of the overpunch alphabet,
     *                                  which would mean the fixture or the column offsets had changed
     */
    private BigDecimal decodeOverpunch(String field, int decimalDigits) {
        int lastIndex = field.length() - 1;
        char overpunch = field.charAt(lastIndex);
        String leadingDigits = field.substring(0, lastIndex);

        int finalDigit;
        boolean negative;
        if (overpunch == '{') {
            finalDigit = 0;
            negative = false;
        } else if (overpunch == '}') {
            finalDigit = 0;
            negative = true;
        } else if (overpunch >= 'A' && overpunch <= 'I') {
            finalDigit = overpunch - 'A' + 1;
            negative = false;
        } else if (overpunch >= 'J' && overpunch <= 'R') {
            finalDigit = overpunch - 'J' + 1;
            negative = true;
        } else {
            throw new IllegalArgumentException("Overpunch character '" + overpunch + "' in field [" + field
                    + "] is outside the zoned-decimal alphabet {, A-I, }, J-R. Either the fixture changed or "
                    + "the column offsets taken from app/cpy/CVTRA01Y.cpy no longer match it.");
        }

        BigDecimal unscaled = new BigDecimal(leadingDigits + finalDigit);
        BigDecimal signed = negative ? unscaled.negate() : unscaled;
        return signed.movePointLeft(decimalDigits);
    }

    /** The seeded population: what {@code V3} loaded, and that it loaded it once per account. */
    @Nested
    @DisplayName("The seeded population decoded from app/data/ASCII/tcatbal.txt")
    class SeedAndCardinality {

        @Test
        @DisplayName("exactly fifty rows are seeded, one per record of the frozen fixture")
        void exactlyFiftyRowsAreSeeded() {
            assertThat(repository.count())
                    .as("V3__seed_data.sql loads one row per record of app/data/ASCII/tcatbal.txt, "
                            + "which is 2550 bytes of 50 records at width 50")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("every seeded row carries the same type and category pair, so only the account varies")
        void everySeededRowCarriesTheSameTypeAndCategory() {
            List<TransactionCategoryBalance> rows = repository.findAll();

            assertThat(rows).hasSize(seededRowCount);
            assertThat(rows)
                    .as("tcatbal.txt columns 12-17 hold the constant pair 010001 on all 50 records, so "
                            + "TRANCAT-TYPE-CD (CVTRA01Y.cpy:L7) and TRANCAT-CD (CVTRA01Y.cpy:L8) do not vary")
                    .allSatisfy(row -> {
                        assertThat(row.getId().getTypeCd()).isEqualTo(seededTypeCd);
                        assertThat(row.getId().getCatCd()).isEqualTo(seededCatCd);
                    });
        }

        @Test
        @DisplayName("the fifty account identifiers are distinct and run one through fifty")
        void theAccountIdentifiersAreDistinctAndContiguous() {
            List<Long> accountIds = repository.findAll().stream()
                    .map(row -> row.getId().getAccountId())
                    .toList();

            assertThat(accountIds)
                    .as("one balance row per seeded account; TRANCAT-ACCT-ID is PIC 9(11) at CVTRA01Y.cpy:L6")
                    .doesNotHaveDuplicates()
                    .hasSize(seededRowCount);
            assertThat(Set.copyOf(accountIds))
                    .containsExactlyInAnyOrderElementsOf(
                            LongStream.rangeClosed(1L, seededRowCount).boxed().toList());
        }

        @Test
        @DisplayName("a zero balance is a real value, not a missing one")
        void aZeroBalanceIsARealValueRatherThanNull() {
            assertThat(repository.findAll())
                    .as("all 50 seeded balances decode from the eleven-character field 0000000000{ to +0.00; "
                            + "tran_cat_bal is NOT NULL, so a zero must arrive as 0.00 and never as null")
                    .allSatisfy(row -> {
                        assertThat(row.getBalance()).isNotNull();
                        assertThat(row.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
                    });
        }
    }

    /**
     * The composite key through Hibernate's identity map: the one part of the key contract that belongs to
     * this tier rather than to {@code com.cardemo.unit.model.TransactionCategoryBalanceIdTest}.
     */
    @Nested
    @DisplayName("The three-part composite key, resolved through Hibernate")
    class CompositeKeyRoundTrip {

        @Test
        @DisplayName("a freshly constructed key resolves the first seeded row, proving equals and hashCode wire up")
        void aFreshlyConstructedKeyResolvesTheFirstRow() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd);

            Optional<TransactionCategoryBalance> found = repository.findById(id);

            assertThat(found)
                    .as("key %s built from literals, not copied off a loaded row, must resolve through the "
                            + "identity map; components are accountId/typeCd/catCd per CVTRA01Y.cpy:L6-L8",
                            renderKey(id))
                    .isPresent();
            assertThat(found.orElseThrow().getBalance())
                    .as("compared with compareTo, never equals: PostgreSQL returns NUMERIC(11,2) at scale 2")
                    .isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("a freshly constructed key resolves the last seeded row too")
        void aFreshlyConstructedKeyResolvesTheLastRow() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId((long) seededRowCount, seededTypeCd, seededCatCd);

            Optional<TransactionCategoryBalance> found = repository.findById(id);

            assertThat(found).as("key %s is the last of the 50 seeded rows", renderKey(id)).isPresent();
            assertThat(found.orElseThrow().getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(found.orElseThrow().getId().getAccountId()).isEqualTo((long) seededRowCount);
        }

        @Test
        @DisplayName("an absent key yields an empty Optional and does not throw")
        void anAbsentKeyYieldsAnEmptyOptional() {
            TransactionCategoryBalanceId absent =
                    new TransactionCategoryBalanceId(1L, absentTypeCd, absentCatCd);

            assertThat(repository.findById(absent))
                    .as("CBTRN02C.cbl:481 accepts TCATBALF-STATUS '00' OR '23', so a not-found read at "
                            + "key %s is an accepted control path and must not raise", renderKey(absent))
                    .isEmpty();
            assertThat(repository.existsById(absent))
                    .as("the same absence seen through existsById; this is the flag CBTRN02C.cbl:473-478 "
                            + "records as WS-CREATE-TRANCAT-REC")
                    .isFalse();
        }

        @Test
        @DisplayName("changing any single key component changes which row is found")
        void changingAnyComponentChangesTheRowFound() {
            TransactionCategoryBalanceId first =
                    new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd);
            TransactionCategoryBalanceId second =
                    new TransactionCategoryBalanceId(2L, seededTypeCd, seededCatCd);

            assertThat(repository.findById(first).orElseThrow().getId().getAccountId()).isEqualTo(1L);
            assertThat(repository.findById(second).orElseThrow().getId().getAccountId()).isEqualTo(2L);
            assertThat(repository.findById(new TransactionCategoryBalanceId(1L, absentTypeCd, seededCatCd)))
                    .as("same account, different type code: a different key, and one that is not seeded")
                    .isEmpty();
            assertThat(repository.findById(new TransactionCategoryBalanceId(1L, seededTypeCd, 2)))
                    .as("same account and type, different category code: again a different, unseeded key")
                    .isEmpty();
        }
    }

    /**
     * The key-ordered browse, critical because the interest job's account-level
     * control break at {@code app/cbl/CBACT04C.cbl:L194} is correct only while this ordering holds, and a
     * loss of ordering produces wrong interest silently.
     */
    @Nested
    @DisplayName("The key-ordered browse the interest job's control break depends on")
    class OrderedBrowse {

        @Test
        @DisplayName("all fifty seeded rows arrive in composite-key order")
        void allSeededRowsArriveInKeyOrder() {
            List<TransactionCategoryBalance> rows = browseAll(seededRowCount);

            assertThat(rows).hasSize(seededRowCount);
            assertThat(rows)
                    .as("CBACT04C.cbl:188-222 reads this file sequentially in key order; the ordering is "
                            + "by TRANCAT-ACCT-ID, then TRANCAT-TYPE-CD, then TRANCAT-CD")
                    .isSortedAccordingTo(keyOrder());
        }

        @Test
        @DisplayName("the ordering holds across all three components once one account spans several pairs")
        void theOrderingHoldsAcrossAllThreeComponents() {
            // Give account 1 four extra rows spanning three type codes and three category codes, so that the
            // second and third key components genuinely participate in the ordering rather than being
            // constant. Every pair used exists in transaction_category, so both foreign keys stay satisfied.
            // These rows roll back with the test's transaction.
            repository.save(newRow(1L, "01", 3, "3.00"));
            repository.save(newRow(1L, "01", 2, "2.00"));
            repository.save(newRow(1L, "02", 1, "4.00"));
            repository.save(newRow(1L, absentTypeCd, absentCatCd, "5.00"));
            flushAndClear();

            List<TransactionCategoryBalance> rows = browseAll(seededRowCount + 4);

            assertThat(rows).hasSize(seededRowCount + 4);
            assertThat(rows)
                    .as("with one account holding ('01',1) ('01',2) ('01',3) ('02',1) ('07',1) the browse must "
                            + "order by type code before category code, exactly as TRAN-CAT-KEY is composed "
                            + "at CVTRA01Y.cpy:L5-L8")
                    .isSortedAccordingTo(keyOrder());

            List<String> accountOneKeys = rows.stream()
                    .filter(row -> row.getId().getAccountId().longValue() == 1L)
                    .map(row -> row.getId().getTypeCd() + "%04d".formatted(row.getId().getCatCd()))
                    .toList();
            assertThat(accountOneKeys)
                    .as("the five rows of account 1 in the order the browse delivered them")
                    .containsExactly("010001", "010002", "010003", "020001", "070001");
        }

        @Test
        @DisplayName("every account's rows form one unbroken run, which is what the control break relies on")
        void everyAccountFormsOneUnbrokenRun() {
            repository.save(newRow(1L, "01", 2, "2.00"));
            repository.save(newRow(1L, "02", 1, "4.00"));
            repository.save(newRow(25L, "01", 2, "6.00"));
            repository.save(newRow((long) seededRowCount, "03", 1, "7.00"));
            flushAndClear();

            List<TransactionCategoryBalance> rows = browseAll(seededRowCount + 4);
            assertThat(rows).hasSize(seededRowCount + 4);

            // Walk the sequence recording each account the moment it first appears. If any account's rows are
            // split by another account's, that account is seen a second time and the set rejects it. This is
            // the precise property CBACT04C.cbl:194 depends on and precisely what an unordered read destroys.
            Set<Long> runsStarted = new LinkedHashSet<>();
            Long currentAccount = null;
            for (TransactionCategoryBalance row : rows) {
                Long accountId = row.getId().getAccountId();
                if (!accountId.equals(currentAccount)) {
                    assertThat(runsStarted.add(accountId))
                            .as("account %d began a second run at key %s, so CBACT04C.cbl:194 "
                                    + "IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM would flush it twice and "
                                    + "halve its interest", accountId, renderKey(row.getId()))
                            .isTrue();
                    currentAccount = accountId;
                }
            }
            assertThat(runsStarted)
                    .as("one run per account across the 50 seeded accounts, with four of them widened")
                    .hasSize(seededRowCount);
        }

        @Test
        @DisplayName("chunked traversal preserves the ordering across chunk boundaries")
        void chunkedTraversalPreservesOrderingAcrossBoundaries() {
            // The interest job consumes this file one record at a time, so the browse is read in chunks in
            // production. A query can be ordered within a chunk and still tear at a boundary, and a torn
            // boundary looks like valid data row by row - it surfaces only as a control break firing twice.
            List<TransactionCategoryBalance> walked = browseInChunks(7);

            assertThat(walked).hasSize(seededRowCount);
            assertThat(walked)
                    .as("50 rows walked in chunks of 7, which straddles eight boundaries, must still be "
                            + "globally ordered by all three key components")
                    .isSortedAccordingTo(keyOrder());
            assertThat(walked.stream().map(row -> row.getId().getAccountId()).toList())
                    .as("and must visit each account exactly once, in ascending order")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the browse is served by the primary key, so no extra index exists or is needed")
        void theBrowseIsServedByThePrimaryKey() {
            List<String> indexNames = jdbcTemplate.queryForList("""
                    SELECT indexname
                      FROM pg_indexes
                     WHERE schemaname = current_schema()
                       AND tablename = ?
                    """, String.class, "transaction_category_balance");

            assertThat(indexNames)
                    .as("V2__create_indexes.sql creates exactly three non-unique B-tree indexes and none on "
                            + "this table; the key-ordered browse is served by the primary key itself, which "
                            + "is why no fourth index is needed and none may be added")
                    .containsExactly("pk_transaction_category_balance");
        }
    }

    /**
     * The create leg of {@code 2700-UPDATE-TCATBAL}: the branch reached when the initial read returns file
     * status {@code '23'}.
     *
     * <p>Only the <em>seam</em> is asserted here. Choosing between the two legs is
     * {@code TransactionPostingProcessor}'s decision and is asserted by {@code unit/batch} and
     * {@code integration/batch}; what this tier owns is that the seam offers the branch a truthful,
     * non-throwing answer about whether the row exists.
     */
    @Nested
    @DisplayName("The upsert create leg: file status '23' is an accepted control path, not an error")
    class UpsertCreateLeg {

        @Test
        @DisplayName("an absent key reports absent rather than raising, which is what selects the create leg")
        void anAbsentKeyReportsAbsentRatherThanRaising() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(1L, absentTypeCd, absentCatCd);

            // CBTRN02C.cbl:474-479 READ ... INVALID KEY sets WS-CREATE-TRANCAT-REC to 'Y' and :481 then
            // accepts TCATBALF-STATUS '00' OR '23'. The Java equivalent of that acceptance is that the read
            // returns an empty result instead of throwing.
            assertThat(repository.findById(id))
                    .as("CBTRN02C.cbl:481 accepts '00' OR '23', so key %s must read back empty", renderKey(id))
                    .isEmpty();
            assertThat(repository.existsById(id)).isFalse();
        }

        @Test
        @DisplayName("the create leg writes a row whose balance equals the amount added to a zero start")
        void theCreateLegWritesTheAmountOntoAZeroStart() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(1L, absentTypeCd, absentCatCd);
            BigDecimal amount = new BigDecimal("123.45");

            assertThat(repository.existsById(id))
                    .as("precondition: the row is absent, so the create leg is the one selected")
                    .isFalse();

            // CBTRN02C.cbl:504-508 INITIALIZE TRAN-CAT-BAL-RECORD, move the three key components, then
            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL. Adding to a zero-initialised balance yields the amount.
            repository.save(new TransactionCategoryBalance(id, new BigDecimal("0.00").add(amount)));
            flushAndClear();

            assertThat(repository.existsById(id))
                    .as("after the create leg the same key that selected it must now be present, which is "
                            + "what makes the two legs distinguishable at this seam")
                    .isTrue();
            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .as("CBTRN02C.cbl:508 ADDs onto an INITIALIZEd record, so the stored balance is the "
                            + "amount itself; compared with compareTo, never equals")
                    .isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("the created row joins the key-ordered browse in its correct position")
        void theCreatedRowJoinsTheBrowseInOrder() {
            repository.save(newRow(1L, absentTypeCd, absentCatCd, "10.00"));
            flushAndClear();

            List<TransactionCategoryBalance> rows = browseAll(seededRowCount + 1);

            assertThat(rows).hasSize(seededRowCount + 1);
            assertThat(rows)
                    .as("a row created through the '23' leg must take its place in key order, or the interest "
                            + "job's control break would see account 1 twice")
                    .isSortedAccordingTo(keyOrder());
            assertThat(rows.get(1).getId().getTypeCd())
                    .as("('01',1) then ('07',1) for account 1, so the new row is second overall")
                    .isEqualTo(absentTypeCd);
        }
    }

    /**
     * The update leg of {@code 2700-UPDATE-TCATBAL}: {@code :L527} performs
     * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} and {@code :L528} rewrites. It accumulates; it never assigns.
     */
    @Nested
    @DisplayName("The upsert update leg: the balance accumulates and is never replaced")
    class UpsertUpdateLeg {

        @Test
        @DisplayName("adding an amount to a seeded row yields the sum, not the amount")
        void addingAnAmountYieldsTheSum() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd);
            BigDecimal opening = repository.findById(id).orElseThrow().getBalance();
            BigDecimal amount = new BigDecimal("250.75");

            TransactionCategoryBalance managed = repository.findById(id).orElseThrow();
            managed.setBalance(managed.getBalance().add(amount));
            flushAndClear();

            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .as("CBTRN02C.cbl:527 ADD DALYTRAN-AMT TO TRAN-CAT-BAL; the result is opening + amount, "
                            + "never the amount on its own")
                    .isEqualByComparingTo(opening.add(amount));
        }

        @Test
        @DisplayName("two successive amounts accumulate, so neither replaces the other")
        void successiveAmountsAccumulate() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(2L, seededTypeCd, seededCatCd);
            BigDecimal opening = repository.findById(id).orElseThrow().getBalance();
            BigDecimal first = new BigDecimal("100.00");
            BigDecimal second = new BigDecimal("-40.50");

            TransactionCategoryBalance managed = repository.findById(id).orElseThrow();
            managed.setBalance(managed.getBalance().add(first));
            flushAndClear();
            TransactionCategoryBalance reread = repository.findById(id).orElseThrow();
            reread.setBalance(reread.getBalance().add(second));
            flushAndClear();

            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .as("the second amount is negative, and a negative amount must reduce the running "
                            + "balance rather than be normalised; there is no abs() on this path")
                    .isEqualByComparingTo(opening.add(first).add(second));
        }

        @Test
        @DisplayName("the update leg changes one row only and leaves the population size untouched")
        void theUpdateLegChangesOneRowOnly() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(3L, seededTypeCd, seededCatCd);

            TransactionCategoryBalance managed = repository.findById(id).orElseThrow();
            managed.setBalance(managed.getBalance().add(new BigDecimal("7.77")));
            flushAndClear();

            assertThat(repository.count())
                    .as("CBTRN02C.cbl:528 REWRITEs an existing record; a rewrite must not insert, which is "
                            + "the whole reason :495-499 dispatches on WS-CREATE-TRANCAT-REC")
                    .isEqualTo(seededRowCount);
            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .isEqualByComparingTo(new BigDecimal("7.77"));
            assertThat(repository.findById(new TransactionCategoryBalanceId(4L, seededTypeCd, seededCatCd))
                    .orElseThrow().getBalance())
                    .as("the neighbouring account is untouched")
                    .isEqualByComparingTo(new BigDecimal("0.00"));
        }
    }

    /**
     * The signed balance. {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} - the {@code S} is load-bearing.
     *
     * <p>{@code app/data/ASCII/dailytran.txt} carries genuinely negative amounts among its 300 records, so
     * the posting upsert legitimately drives a category balance negative. Any absolute-value normalisation,
     * or any non-negative check constraint, is forbidden.
     */
    @Nested
    @DisplayName("The signed balance: PIC S9(09)V99 keeps its sign end to end")
    class SignedBalance {

        @Test
        @DisplayName("a negative balance round-trips with its sign intact")
        void aNegativeBalanceRoundTripsWithItsSign() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(5L, absentTypeCd, absentCatCd);
            BigDecimal negative = new BigDecimal("-998.33");

            repository.save(new TransactionCategoryBalance(id, negative));
            flushAndClear();

            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .as("the S of PIC S9(09)V99 is load-bearing: no abs(), no normalisation, and no "
                            + "non-negative CHECK exists on tran_cat_bal")
                    .isEqualByComparingTo(negative);
            assertThat(repository.findById(id).orElseThrow().getBalance().signum())
                    .as("stored strictly below zero, not merely close to it")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("a zero balance persists as zero rather than as null")
        void aZeroBalancePersistsAsZero() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(6L, absentTypeCd, absentCatCd);

            repository.save(new TransactionCategoryBalance(id, new BigDecimal("0.00")));
            flushAndClear();

            BigDecimal stored = repository.findById(id).orElseThrow().getBalance();
            assertThat(stored).as("tran_cat_bal is NOT NULL, so zero is a value and not an absence")
                    .isNotNull();
            assertThat(stored).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(stored.signum()).isZero();
        }

        @Test
        @DisplayName("both extremes of the nine signed integer digits round-trip exactly")
        void bothExtremesRoundTripExactly() {
            BigDecimal largest = new BigDecimal("999999999.99");
            BigDecimal smallest = largest.negate();
            TransactionCategoryBalanceId high =
                    new TransactionCategoryBalanceId(7L, absentTypeCd, absentCatCd);
            TransactionCategoryBalanceId low =
                    new TransactionCategoryBalanceId(8L, absentTypeCd, absentCatCd);

            repository.save(new TransactionCategoryBalance(high, largest));
            repository.save(new TransactionCategoryBalance(low, smallest));
            flushAndClear();

            assertThat(repository.findById(high).orElseThrow().getBalance())
                    .as("nine integer digits and two decimals is the widest value PIC S9(09)V99 holds, and "
                            + "NUMERIC(11,2) must store it without rounding")
                    .isEqualByComparingTo(largest);
            assertThat(repository.findById(low).orElseThrow().getBalance())
                    .as("and the same magnitude negative")
                    .isEqualByComparingTo(smallest);
        }

        @Test
        @DisplayName("the stored scale is exactly two, which is why compareTo and not equals is used")
        void theStoredScaleIsExactlyTwo() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(9L, absentTypeCd, absentCatCd);

            repository.save(new TransactionCategoryBalance(id, new BigDecimal("12.5")));
            flushAndClear();

            BigDecimal stored = repository.findById(id).orElseThrow().getBalance();
            assertThat(stored.scale())
                    .as("NUMERIC(11,2) always returns scale 2, so a scale-1 literal comes back as scale 2 - "
                            + "which is exactly the BigDecimal.equals trap this file avoids throughout")
                    .isEqualTo(2);
            assertThat(stored).isEqualByComparingTo(new BigDecimal("12.5"));
            assertThat(stored.compareTo(new BigDecimal("12.50")))
                    .as("compareTo sees the two as equal where equals would not")
                    .isZero();
        }
    }

    /**
     * The {@code NUMERIC(11,2)} precision tier, read from the live catalogue rather than from the migration
     * text, and separated explicitly from the two tiers it is most often confused with.
     */
    @Nested
    @DisplayName("The NUMERIC(11,2) precision tier, distinguished from 12,2 and 6,2")
    class PrecisionTier {

        @Test
        @DisplayName("tran_cat_bal is declared numeric with precision eleven and scale two")
        void theBalanceColumnIsElevenTwo() {
            Map<String, Object> column = jdbcTemplate.queryForMap(
                    numericMetadataSql, "transaction_category_balance", "tran_cat_bal");

            assertThat(column.get("data_type")).isEqualTo("numeric");
            assertThat(column.get("numeric_precision"))
                    .as("TRAN-CAT-BAL is PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9, so 9 + 2 = 11 digits; "
                            + "using the account tier's 12 here would break parity")
                    .isEqualTo(11);
            assertThat(column.get("numeric_scale"))
                    .as("the V of PIC S9(09)V99 places two digits after the implied point")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the neighbouring tiers really are different, so eleven-two is not a coincidence")
        void theNeighbouringTiersAreDifferent() {
            Map<String, Object> accountTier =
                    jdbcTemplate.queryForMap(numericMetadataSql, "account", "acct_curr_bal");
            Map<String, Object> rateTier =
                    jdbcTemplate.queryForMap(numericMetadataSql, "disclosure_group", "dis_int_rate");

            assertThat(accountTier.get("numeric_precision"))
                    .as("ACCT-CURR-BAL is PIC S9(10)V99, so the five account money columns are NUMERIC(12,2)")
                    .isEqualTo(12);
            assertThat(rateTier.get("numeric_precision"))
                    .as("DIS-INT-RATE is PIC S9(04)V99, so the rate column is NUMERIC(6,2)")
                    .isEqualTo(6);
            assertThat(accountTier.get("numeric_scale")).isEqualTo(2);
            assertThat(rateTier.get("numeric_scale")).isEqualTo(2);
        }

        @Test
        @DisplayName("a value at the declared precision round-trips through the repository unchanged")
        void aValueAtTheDeclaredPrecisionRoundTrips() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(10L, absentTypeCd, absentCatCd);
            BigDecimal atTheLimit = new BigDecimal("987654321.99");

            repository.save(new TransactionCategoryBalance(id, atTheLimit));
            flushAndClear();

            assertThat(repository.findById(id).orElseThrow().getBalance())
                    .as("eleven significant digits is precisely what NUMERIC(11,2) holds, so nothing may be "
                            + "rounded away at the boundary")
                    .isEqualByComparingTo(atTheLimit);
        }

        @Test
        @DisplayName("the entity refuses an over-range balance in Java, before the database is reached")
        void theEntityRefusesAnOverRangeBalance() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(11L, absentTypeCd, absentCatCd);

            // Defence in depth: this is the originating throw, so it has no cause to preserve. The database's
            // own refusal of the same magnitude is asserted separately below, where a cause does exist.
            assertThatThrownBy(() -> new TransactionCategoryBalance(id, new BigDecimal("1000000000.00")))
                    .as("ten integer digits exceeds PIC S9(09)V99; the guard must name the field and the tier")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRAN-CAT-BAL")
                    .hasMessageContaining("999999999.99")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the entity refuses a balance carrying more than two decimal digits")
        void theEntityRefusesAnOverScaleBalance() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(11L, absentTypeCd, absentCatCd);

            assertThatThrownBy(() -> new TransactionCategoryBalance(id, new BigDecimal("1.005")))
                    .as("a third decimal digit must be rescaled deliberately with RoundingMode.HALF_EVEN "
                            + "rather than silently rounded by the column")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRAN-CAT-BAL")
                    .hasMessageContaining("HALF_EVEN")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the database refuses an over-precision balance, reporting precision eleven scale two")
        void theDatabaseRefusesAnOverPrecisionBalance() {
            // The entity guard above stops this magnitude in Java, so reaching the column at all requires a
            // parameterised native insert. Every value is bound; nothing is concatenated into the statement.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    insertSql, 12L, absentTypeCd, absentCatCd, new BigDecimal("1000000000.00")))
                    .as("NUMERIC(11,2) must reject a value needing ten integer digits")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("numeric field overflow")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("precision 11, scale 2");
        }
    }

    /**
     * Primary-key uniqueness. {@code app/catlg/LISTCAT.txt:1373} shows the base cluster as {@code UNIQUE}
     * with no {@code NONUNIQKEY}, so this is a genuine contract rather than an assumption.
     */
    @Nested
    @DisplayName("Composite primary-key uniqueness, and why it forces the read-then-branch")
    class KeyUniqueness {

        @Test
        @DisplayName("a second row on an existing composite key is refused, naming the primary key")
        void aDuplicateCompositeKeyIsRefused() {
            // A duplicate cannot be provoked through the repository: the identifier is assigned rather than
            // generated, so Spring Data treats the entity as non-new and merges it, which becomes an UPDATE
            // and violates nothing. Reaching the constraint therefore requires a parameterised native insert.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    insertSql, 1L, seededTypeCd, seededCatCd, new BigDecimal("5.00")))
                    .as("LISTCAT.txt:1373 declares the cluster UNIQUE with no NONUNIQKEY, so (acct_id, "
                            + "tran_type_cd, tran_cat_cd) admits exactly one row")
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("pk_transaction_category_balance")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key value violates unique constraint");
        }

        @Test
        @DisplayName("that uniqueness is exactly what makes the legacy read-then-branch necessary")
        void uniquenessIsWhatForcesTheReadThenBranch() {
            TransactionCategoryBalanceId seeded =
                    new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd);
            TransactionCategoryBalanceId absent =
                    new TransactionCategoryBalanceId(1L, absentTypeCd, absentCatCd);

            // The two legs of 2700-UPDATE-TCATBAL are selected by exactly this one bit of information, and
            // the seam must report it without raising in either direction. A blind WRITE would fail on the
            // first key because the primary key is unique; a blind REWRITE would fail on the second because
            // there is nothing to rewrite. That is why :474-479 reads first and :481 tolerates '23'.
            assertThat(repository.existsById(seeded))
                    .as("present, so CBTRN02C.cbl:498 takes 2700-B-UPDATE-TCATBAL-REC")
                    .isTrue();
            assertThat(repository.existsById(absent))
                    .as("absent, so CBTRN02C.cbl:496 takes 2700-A-CREATE-TCATBAL-REC")
                    .isFalse();
        }
    }

    /**
     * The two foreign keys, asserted separately. They are numbers 7 and 8 of the exactly ten that {@code V1}
     * creates.
     */
    @Nested
    @DisplayName("Both foreign keys are enforced, and each is asserted on its own")
    class ForeignKeys {

        @Test
        @DisplayName("an unknown account identifier is refused by fk07_tcatbal_account")
        void anUnknownAccountIsRefused() {
            // 99999999999 is the largest value PIC 9(11) can express, so it passes the key class's own range
            // guard and reaches the database, which is what makes this a database-level assertion.
            TransactionCategoryBalance orphan = newRow(99999999999L, seededTypeCd, seededCatCd, "1.00");

            assertThatThrownBy(() -> {
                repository.save(orphan);
                flushAndClear();
            })
                    .as("CBACT04C.cbl:202 moves TRANCAT-ACCT-ID into the account key and reads the account, "
                            + "so a balance row without an account has no meaning")
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("transaction_category_balance")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk07_tcatbal_account");
        }

        @Test
        @DisplayName("an unknown type and category pair is refused by fk08_tcatbal_category")
        void anUnknownTypeAndCategoryPairIsRefused() {
            // 9999 is the largest value PIC 9(04) can express, so it too clears the key class's range guard.
            TransactionCategoryBalance orphan = newRow(1L, seededTypeCd, 9999, "1.00");

            assertThatThrownBy(() -> {
                repository.save(orphan);
                flushAndClear();
            })
                    .as("CBTRN02C.cbl:470-471 keys this dataset from DALYTRAN-TYPE-CD and DALYTRAN-CAT-CD, "
                            + "so the pair must name a category that exists")
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("transaction_category_balance")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk08_tcatbal_category");
        }

        @Test
        @DisplayName("a row satisfying both keys is accepted, so neither constraint is vacuous")
        void aRowSatisfyingBothKeysIsAccepted() {
            TransactionCategoryBalanceId id =
                    new TransactionCategoryBalanceId(13L, absentTypeCd, absentCatCd);

            repository.save(new TransactionCategoryBalance(id, new BigDecimal("1.00")));
            flushAndClear();

            assertThat(repository.findById(id))
                    .as("account 13 is seeded and the pair 070001 is one of the 18 in trancatg.txt, so both "
                            + "foreign keys are satisfiable and the two refusals above are meaningful")
                    .isPresent();
        }
    }

    /**
     * The character width contract on {@code tran_type_cd}. The column is {@code VARCHAR(2)}, so it neither
     * pads nor truncates: see the character width policy in this class's Javadoc.
     */
    @Nested
    @DisplayName("The two-character type code: refused when wider, and never blank padded")
    class TypeCodeWidthContract {

        @Test
        @DisplayName("the column is declared character varying with a maximum length of two")
        void theColumnIsVarcharTwo() {
            Map<String, Object> column = jdbcTemplate.queryForMap(
                    characterMetadataSql, "transaction_category_balance", "tran_type_cd");

            assertThat(column.get("data_type"))
                    .as("V1__create_schema.sql declares tran_type_cd VARCHAR(2) and not CHAR(2), for "
                            + "the reason "
                            + "recorded at V1:433-437; there is therefore no blank padding in either direction")
                    .isEqualTo("character varying");
            assertThat(column.get("character_maximum_length"))
                    .as("TRANCAT-TYPE-CD is PIC X(02) at app/cpy/CVTRA01Y.cpy:L7")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the key class refuses a three-character type code before the database is reached")
        void theKeyClassRefusesAThreeCharacterTypeCode() {
            assertThatThrownBy(() -> new TransactionCategoryBalanceId(1L, "011", seededCatCd))
                    .as("PIC X(02) is exactly two characters, so the key class refuses a third; this is the "
                            + "originating throw and so carries no cause")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRANCAT-TYPE-CD")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the database refuses a three-character type code as well, rather than truncating it")
        void theDatabaseRefusesAThreeCharacterTypeCode() {
            // The key guard stops this in Java, so proving the column's own behaviour needs a parameterised
            // native insert. The distinction matters: silent truncation would turn '011' into '01' and post
            // the amount to the wrong category.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    insertSql, 14L, "011", absentCatCd, new BigDecimal("1.00")))
                    .as("VARCHAR(2) must reject an over-length value outright, never truncate it")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("value too long")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("character varying(2)");
        }

        @Test
        @DisplayName("a two-character code round-trips as exactly those two characters, unpadded")
        void aTwoCharacterCodeRoundTripsUnpadded() {
            String stored = repository
                    .findById(new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd))
                    .orElseThrow()
                    .getId()
                    .getTypeCd();

            assertThat(stored)
                    .as("VARCHAR(2) returns what was written, so no trimming or unpadding is applied on read "
                            + "and none may be introduced")
                    .isEqualTo(seededTypeCd)
                    .hasSize(2);
        }
    }

    /**
     * Corroboration against the frozen fixtures, read by bare classpath name through the harness's reader.
     *
     * <p>The decode itself is {@code V3__seed_data.sql}'s responsibility; what is checked here is that the
     * rows in the table agree with the bytes on disk. Nothing is copied, trimmed, normalised or edited: the
     * three files consumed are byte-for-byte the frozen {@code app/data/ASCII} datasets.
     *
     * <p>One fixture detail is worth recording because it looks like an inconsistency and is not. The filler
     * of {@code tcatbal.txt}, {@code discgrp.txt}, {@code trancatg.txt} and {@code trantype.txt} is
     * <em>zero</em>-filled, whereas {@code acctdata.txt}, {@code custdata.txt}, {@code carddata.txt} and
     * {@code dailytran.txt} are <em>space</em>-filled - which is why {@code tcatbal.txt} has no trailing
     * space run at all. The no-edit discipline is identical either way.
     */
    @Nested
    @DisplayName("Corroboration against the frozen fixtures, read without copying or trimming")
    class FixtureCorroboration {

        @Test
        @DisplayName("tcatbal.txt holds fifty records of exactly fifty characters each")
        void theFixtureGeometryIsFiftyByFifty() {
            List<String> records = readFixture("tcatbal.txt");

            assertThat(records)
                    .as("2550 bytes is 50 records of 50 characters plus one line feed each")
                    .hasSize(seededRowCount);
            assertThat(records)
                    .as("11 + 2 + 4 key bytes then 11 balance bytes then 22 filler bytes is the RECLN 50 of "
                            + "app/cpy/CVTRA01Y.cpy, matching AVGLRECL 50 at app/catlg/LISTCAT.txt:1371")
                    .allSatisfy(record -> assertThat(record).hasSize(50));
        }

        @Test
        @DisplayName("every record carries the same thirty-nine character suffix, so only the account varies")
        void everyRecordCarriesTheSameSuffix() {
            List<String> suffixes = readFixture("tcatbal.txt").stream()
                    .map(record -> fixtureField(record, 12, 50))
                    .distinct()
                    .toList();

            assertThat(suffixes)
                    .as("columns 12-50 are constant across all 50 records: the pair 010001, the balance "
                            + "0000000000{ and 22 zero filler bytes")
                    .containsExactly("0100010000000000{0000000000000000000000");
        }

        @Test
        @DisplayName("the type and category columns read 010001 on every record")
        void theTypeAndCategoryColumnsAreConstant() {
            assertThat(readFixture("tcatbal.txt"))
                    .allSatisfy(record -> {
                        assertThat(fixtureField(record, 12, 13))
                                .as("TRANCAT-TYPE-CD PIC X(02) at app/cpy/CVTRA01Y.cpy:L7")
                                .isEqualTo(seededTypeCd);
                        assertThat(fixtureField(record, 14, 17))
                                .as("TRANCAT-CD PIC 9(04) at app/cpy/CVTRA01Y.cpy:L8, zero padded to four")
                                .isEqualTo("0001");
                    });
        }

        @Test
        @DisplayName("the balance field is eleven characters wide, which is the proof of the 11,2 tier")
        void theBalanceFieldIsElevenCharactersWide() {
            List<String> balanceFields = readFixture("tcatbal.txt").stream()
                    .map(record -> fixtureField(record, 18, 28))
                    .distinct()
                    .toList();

            assertThat(balanceFields)
                    .as("PIC S9(09)V99 is 9 + 2 = 11 digits, so the overpunch field is ELEVEN characters and "
                            + "not the twelve the PIC S9(10)V99 account money fields occupy - this is the "
                            + "fixture-level proof that the column is NUMERIC(11,2) and not NUMERIC(12,2)")
                    .containsExactly("0000000000{");
            assertThat(balanceFields.get(0)).hasSize(11);
        }

        @Test
        @DisplayName("the overpunch decodes to positive zero, matching every seeded balance in the table")
        void theOverpunchDecodesToPositiveZero() {
            BigDecimal decoded = decodeOverpunch(
                    fixtureField(readFixture("tcatbal.txt").get(0), 18, 28), 2);

            assertThat(decoded)
                    .as("'{' denotes +0 in the zoned-decimal alphabet, so 0000000000{ is +0.00; decoding is "
                            + "position-aware from the PIC clause and never a global text replacement")
                    .isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(decoded.signum()).as("positive zero, not negative zero").isZero();
            assertThat(repository.findById(new TransactionCategoryBalanceId(1L, seededTypeCd, seededCatCd))
                    .orElseThrow().getBalance())
                    .as("and the seeded row agrees with the bytes V3__seed_data.sql decoded it from")
                    .isEqualByComparingTo(decoded);
        }

        @Test
        @DisplayName("the table's account identifiers are a subset of the account fixture's keys")
        void theAccountIdentifiersAreASubsetOfTheAccountFixture() {
            Set<String> accountKeys = new LinkedHashSet<>(readFixture("acctdata.txt").stream()
                    .map(record -> fixtureField(record, 1, 11))
                    .toList());
            List<String> balanceKeys = readFixture("tcatbal.txt").stream()
                    .map(record -> fixtureField(record, 1, 11))
                    .toList();

            assertThat(accountKeys).as("acctdata.txt carries 50 distinct eleven-digit keys").hasSize(50);
            assertThat(accountKeys)
                    .as("every TRANCAT-ACCT-ID names an account that exists, which is why "
                            + "fk07_tcatbal_account is satisfiable at seed time")
                    .containsAll(balanceKeys);
        }

        @Test
        @DisplayName("the pair 010001 is one of the eighteen the category fixture carries")
        void theTypeAndCategoryPairExistsInTheCategoryFixture() {
            List<String> categoryPairs = readFixture("trancatg.txt").stream()
                    .map(record -> fixtureField(record, 1, 6))
                    .toList();

            assertThat(categoryPairs).as("trancatg.txt seeds 18 type and category pairs").hasSize(18);
            assertThat(categoryPairs)
                    .as("the constant pair every balance row carries must be one of them, which is why "
                            + "fk08_tcatbal_category is satisfiable at seed time")
                    .contains(seededTypeCd + "0001");
            assertThat(categoryPairs)
                    .as("and so must the pair this test class uses for its own unseeded keys")
                    .contains(absentTypeCd + "0001");
        }

        @Test
        @DisplayName("the fixture's filler is zero filled, so the record has no trailing space run to trim")
        void theFillerIsZeroFilledRatherThanSpaceFilled() {
            assertThat(readFixture("tcatbal.txt"))
                    .allSatisfy(record -> {
                        assertThat(fixtureField(record, 29, 50))
                                .as("FILLER PIC X(22) at app/cpy/CVTRA01Y.cpy:L10, zero filled in this fixture")
                                .isEqualTo("0".repeat(22));
                        assertThat(record).doesNotContain(" ");
                    });
        }
    }

    /**
     * The complete PostgreSQL metadata contract for the {@code transaction_category_balance} table.
     *
     * <p>Why this delegates rather than restating the facets, and why asserting whichever columns the
     * behavioural tests happen to touch would state no contract at all, is recorded once on
     * {@link SchemaMetadataMatrix}, which declares every facet and asserts the live catalogue against
     * it by exact equality on ordered lists.
     *
     * <p>For {@code transaction_category_balance} that is four columns, the three-part composite key whose 17-character span is what makes an account-level control break work, the NUMERIC(11,2) balance, and fk07 and fk08 - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVTRA01Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the transaction category balance table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "transaction_category_balance");
    }

}
