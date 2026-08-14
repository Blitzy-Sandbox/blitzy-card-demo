/*
 * ******************************************************************
 * Program     : TransactionRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 test - repository integration tier
 * Function    : Proves the persistence contract of TransactionRepository
 *               against a real PostgreSQL 16: the deliberately empty
 *               seed and the first-identifier-1 consequence it forces,
 *               the descending browse that replaces MOVE HIGH-VALUES /
 *               STARTBR / READPREV / ENDBR, the deliberately retained
 *               identifier race, the third and last alternate index
 *               (non-unique, on the processing timestamp), the two
 *               fixed-width text timestamps, the signed eleven-digit
 *               amount, the three foreign keys and the optimistic
 *               locking counter.
 * Source      : app/cpy/CVTRA05Y.cpy:5-18 (TRAN-RECORD, RECLN 350,
 *               offset map 1-350), app/catlg/LISTCAT.txt:3593 (base
 *               cluster KEYLEN 16 AVGLRECL 350) and :3672-3678 (AIX
 *               KEYLEN 26 / AXRKP 304 / SPANNED NONUNIQKEY),
 *               app/jcl/TRANFILE.jcl:82-85 and app/jcl/TRANIDX.jcl:25-28
 *               (DEFINE ALTERNATEINDEX KEYS(26 304) NONUNIQUEKEY),
 *               app/cbl/COTRN02C.cbl:442-451 and :688-689,
 *               app/cbl/COBIL00C.cbl:212-217 and :487-488,
 *               app/cbl/CBACT04C.cbl:1-21 (banner convention) and
 *               :474-484, app/proc/TRANREPT.prc:38-46,
 *               app/csd/CARDDEMO.CSD:76-77, CONTRIBUTING.md:33-34,
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Integration coverage for {@link TransactionRepository}, a {@code JpaRepository<Transaction, String>},
 * against the real PostgreSQL 16 engine the shared harness starts.
 *
 * <h2>1. What it does</h2>
 *
 * <p>This is the third and last of the three alternate-index tests, and it is the only one whose table is
 * <strong>seeded empty</strong>. Everything below follows from that one fact, so it is stated first.
 *
 * <h3>The empty seed is the primary assertion, not an edge case</h3>
 *
 * <p>{@code V3__seed_data.sql} carries exactly ten {@code INSERT} statements and none of them targets
 * {@code "transaction"}; the migration labels that block "SEEDED EMPTY, DELIBERATELY" and its own census
 * records {@code "transaction" 0 no fixture exists}. There is no {@code transaction.txt} among the nine
 * frozen {@code app/data/ASCII} fixtures and none is expected, so
 * {@link AbstractRepositoryIntegrationTest#readFixture(String)} refuses the name outright - which is itself
 * asserted below. The row counts a test may rely on are therefore {@code account} 50, {@code card} 50,
 * {@code card_cross_reference} 50, {@code customer} 50, {@code daily_transaction} 300,
 * {@code disclosure_group} 51, {@code transaction_category_balance} 50, {@code transaction_category} 18,
 * {@code transaction_type} 7, {@code user_security} 10, and {@code "transaction"} <strong>0</strong>.
 *
 * <p>The consequence is a behavioural contract rather than a curiosity. On a freshly migrated database the
 * descending top-one identifier query returns nothing, and both online programs that generate a transaction
 * identifier handle that case explicitly: {@code app/cbl/COBIL00C.cbl:487-488} and
 * {@code app/cbl/COTRN02C.cbl:688-689} each read
 * {@code WHEN DFHRESP(ENDFILE) / MOVE ZEROS TO TRAN-ID}, and the next statement adds one. <strong>So the
 * first generated identifier is 1, and that is the default state of the system.</strong>
 *
 * <h3>The field contract, {@code app/cpy/CVTRA05Y.cpy:5-18}</h3>
 *
 * <p>The copybook header at {@code :2} states {@code RECLN = 350}. The fourteen fields and their one-based
 * byte ranges are: {@code TRAN-ID PIC X(16)} 1-16, {@code TRAN-TYPE-CD PIC X(02)} 17-18,
 * {@code TRAN-CAT-CD PIC 9(04)} 19-22, {@code TRAN-SOURCE PIC X(10)} 23-32,
 * {@code TRAN-DESC PIC X(100)} 33-132, {@code TRAN-AMT PIC S9(09)V99} 133-143,
 * {@code TRAN-MERCHANT-ID PIC 9(09)} 144-152, {@code TRAN-MERCHANT-NAME PIC X(50)} 153-202,
 * {@code TRAN-MERCHANT-CITY PIC X(50)} 203-252, {@code TRAN-MERCHANT-ZIP PIC X(10)} 253-262,
 * <strong>{@code TRAN-CARD-NUM PIC X(16)} 263-278</strong>,
 * <strong>{@code TRAN-ORIG-TS PIC X(26)} 279-304</strong>,
 * <strong>{@code TRAN-PROC-TS PIC X(26)} 305-330</strong>, and {@code FILLER PIC X(20)} 331-350.
 *
 * <p>The map is load-bearing rather than decorative: it is what fixes the alternate-index offset. It is
 * corroborated independently by the DFSORT symbol definitions at {@code app/proc/TRANREPT.prc:39-40}, which
 * declare {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}.
 *
 * <h3>The third alternate index: {@code AXRKP 304}, and why it is <em>not</em> unique</h3>
 *
 * <p>{@code app/catlg/LISTCAT.txt} carries the physical specification. The base cluster entry opens at
 * {@code :3555}; its attributes are {@code KEYLEN 16  AVGLRECL 350} at {@code :3593}, {@code RKP 0} at
 * {@code :3594}, and the attribute line at {@code :3595} carries <em>no</em> {@code NONUNIQKEY}. The
 * alternate index entry opens at {@code :3645}, is marked {@code UPGRADE} at {@code :3656}, associates
 * itself at {@code :3672}, and declares {@code KEYLEN 26} at {@code :3674}, {@code RKP 5} at {@code :3675}
 * and <strong>{@code AXRKP 304} at {@code :3676}</strong>. Its {@code PATH} is at {@code :3541} and its
 * {@code REC-TOTAL} is 39. <strong>The base key IS unique while the alternate key IS NOT.</strong>
 *
 * <p>Two reading traps sit next to each other and both are easy to fall into.
 *
 * <ul>
 *   <li><strong>{@code AXRKP} is zero-based while record-byte prose is one-based.</strong>
 *       {@code AXRKP 304} is therefore record byte <strong>305</strong>, exactly where
 *       {@code TRAN-PROC-TS} begins, and {@code KEYLEN 26} matches its {@code X(26)} width. Proven three
 *       times independently: by the catalogue, by copybook arithmetic over the map above, and by
 *       {@code app/jcl/TRANFILE.jcl:84} and {@code app/jcl/TRANIDX.jcl:27}, which both write
 *       {@code KEYS(26 304)}.</li>
 *   <li><strong>The {@code UNIQUE} token on {@code app/catlg/LISTCAT.txt:3677} is the dataset-name
 *       attribute, not key uniqueness.</strong> Key uniqueness is governed by {@code SPANNED NONUNIQKEY}
 *       on the next line, {@code :3678}, and by {@code NONUNIQUEKEY} at
 *       {@code app/jcl/TRANFILE.jcl:85} and {@code app/jcl/TRANIDX.jcl:28}. Across the whole catalogue
 *       {@code NONUNIQKEY} appears three times - {@code :285}, {@code :488} and {@code :3678}, one per
 *       alternate index - against zero occurrences of {@code UNIQUEKEY}. Many transactions share a
 *       processing timestamp, so the finder over it is multi-valued and the index over it is non-unique.
 *       Asserting uniqueness on any of the three alternate indexes would break parity.</li>
 *   </ul>
 *
 * <h3>Three facts established here from the members and the migration</h3>
 *
 * <ol>
 *   <li>{@code app/jcl/TRANIDX.jcl:25-28} defines the <em>TRANSACT</em> alternate index, identically to
 *       {@code app/jcl/TRANFILE.jcl:82-85}, so the transaction alternate index is defined by <em>both</em>
 *       members. The card alternate index comes from {@code app/jcl/CARDFILE.jcl} instead.</li>
 *   <li>The index emitted by {@code V2__create_indexes.sql} is named {@code idx_transaction_proc_ts}. The
 *       three names the migration writes are {@code idx_card_acct_id},
 *       {@code idx_card_cross_reference_acct_id} and {@code idx_transaction_proc_ts}. <em>The migration
 *       governs</em>, and it is what this test asserts.</li>
 *   <li>The transaction list page size of 10 is evidenced by the loop bounds at {@code :290}
 *       ({@code UNTIL WS-IDX > 10}), {@code :297} ({@code UNTIL WS-IDX >= 11}), {@code :344}, {@code :349}
 *       ({@code MOVE 10 TO WS-IDX}) and {@code :351}, corroborated by the ten generated row fields
 *       {@code TRNID01} to {@code TRNID10} in {@code app/cpy-bms/COTRN00.CPY} - and not by
 *       {@code app/cbl/COTRN00C.cbl:65-68}, which is the pagination <em>state</em> block
 *       ({@code CDEMO-CT00-TRNID-FIRST}, {@code -LAST}, {@code -PAGE-NUM}, {@code -NEXT-PAGE-FLG}). The
 *       value is carried by the property {@code carddemo.pagination.transaction-list-page-size} and is never
 *       a literal in production code, so no literal 10 is asserted here either.</li>
 *   </ol>
 *
 * <p>A further observation: the alternate index {@code PATH} at
 * {@code app/catlg/LISTCAT.txt:3541} has no {@code DEFINE FILE} entry in {@code app/csd/CARDDEMO.CSD},
 * which holds exactly eight of them. The base cluster {@code TRANSACT} is one of the eight, at
 * {@code app/csd/CARDDEMO.CSD:76-77}, so the alternate index is <strong>batch-only</strong>: its sole
 * consumer is the report sort at {@code app/proc/TRANREPT.prc:STEP05R} feeding
 * {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h3>What this class deliberately does not assert</h3>
 *
 * <p>One concern per test class. This tier asserts persistence, mapping, index and constraint behaviour and
 * nothing else, so the following are cited for provenance and asserted elsewhere: the identifier generator
 * itself and the two numeric parsers - {@code app/cbl/COTRN02C.cbl:456-457} uses
 * {@code FUNCTION NUMVAL-C} for the amount alone while {@code :204} and {@code :218} use plain
 * {@code FUNCTION NUMVAL} for the account identifier and the card number, over the edited mask
 * {@code PIC +99999999.99} at {@code :59} and the signed field {@code PIC S9(9)V99} at {@code :58} - which
 * belong to {@code com.cardemo.unit.service}; the bill-payment full-balance semantics
 * ({@code app/cbl/COBIL00C.cbl:193} captures the balance, {@code :198} rejects at or below zero,
 * {@code :224} moves the <em>entire</em> balance into the amount and {@code :234} drives the balance to
 * zero); the reject engine and its return code 4, set if and only if the reject count exceeds zero at
 * {@code app/cbl/CBTRN02C.cbl:229-231}; the report control break, which triggers on the <em>card number</em>
 * held in {@code WS-CURR-CARD-NUM} at {@code app/cbl/CBTRN03C.cbl:137} under a label reading "Account
 * Total", twenty lines to a page per {@code :131-132}; the statement projection at
 * {@code app/jcl/CREASTMT.JCL:STEP010}, which truncates two bytes of the processing timestamp; and the
 * combine job's duplicate-load failure. The interest job's identifier generator is likewise only cited:
 * {@code app/cbl/CBACT04C.cbl:474} increments a <strong>global</strong> suffix counter that is never reset
 * per account and {@code :476-480} concatenates the ten-character date parameter with it, so because the
 * date leads, generated identifiers are numerically large and <strong>dominate the descending browse once
 * an interest run has occurred</strong>; its fixed literals are {@code '01'} at {@code :482}, {@code '05'}
 * at {@code :483} and the source literal {@code 'System'} at {@code :484}.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. This class is bound to <strong>Failsafe</strong> and runs at the
 * {@code integration-test} and {@code verify} phases: {@code maven-failsafe-plugin} 3.5.4 includes
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} even though the class keeps the
 * {@code Test} suffix, while {@code maven-surefire-plugin} 3.5.4 owns {@code **}{@code /unit/}{@code **}
 * and explicitly excludes this tree. <strong>Do not rename or relocate this class.</strong> Moved up to the
 * {@code integration} package, to {@code com.cardemo}, or into {@code src/test/java} directly, it matches
 * neither include set, is collected by neither plugin and simply never runs - a green build, two plugins
 * reporting success, no error and no output. Compile this tree alone with {@code ./mvnw -q test-compile}.
 *
 * <p><strong>A reachable Docker socket is a prerequisite</strong>, because the harness starts a real
 * PostgreSQL 16 container and a reaper alongside it. Where a host JDK is not provisioned the identical
 * build runs in the pinned image with the repository mounted, and produces the same result because every
 * plugin and every non-managed dependency version is pinned:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>3. Key configs and defaults</h2>
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong>, PostgreSQL <strong>16.14 pinned by image digest</strong> on
 *       Debian with glibc rather than the musl variant, and the connection injected from the container.
 *       This class names no host, port, database, user, password or JDBC URL, performs no
 *       environment-variable or system-property read and no system-property write, and reaches no live
 *       service edge of any kind. All of that is inherited; see
 *       {@link AbstractRepositoryIntegrationTest}.</li>
 *   <li><strong>Time comes from {@link AbstractRepositoryIntegrationTest#fixedClock()}</strong>, fixed at
 *       {@code 2022-06-10T19:27:53Z} in UTC. The ambient clock is never read - no current-instant,
 *       current-date or current-date-time factory, no millisecond counter, no no-argument date
 *       constructor - and every formatter is built with {@link java.util.Locale#ROOT}.</li>
 *   <li><strong>Schema handling</strong>: {@code ddl-auto: validate}, {@code open-in-view: false},
 *       {@code show-sql: false}, Hibernate JDBC time zone UTC, {@code spring.batch.job.enabled: false}.
 *       Flyway runs {@code validate-on-migrate: true}, {@code clean-disabled: true},
 *       {@code out-of-order: false}, {@code baseline-on-migrate: false} over
 *       <strong>exactly three</strong> migrations; the {@code BATCH_*} tables and the three sequences that
 *       come with them are created by {@code spring.batch.jdbc.initialize-schema}, never by a fourth
 *       migration.</li>
 *   <li><strong>The table name is emitted double-quoted lowercase as {@code "transaction"}</strong> by both
 *       {@code V1__create_schema.sql} and {@code V2__create_indexes.sql}, because {@code transaction} is a
 *       reserved word. Every native and metadata query below quotes it identically; an unquoted
 *       {@code transaction} fails outright or resolves wrongly.</li>
 *   <li><strong>Money policy</strong>: {@link java.math.BigDecimal} throughout with
 *       {@link java.math.RoundingMode#HALF_EVEN} where rounding is needed, equality by
 *       {@code compareTo} and never by {@code equals}, and no {@code float} or {@code double} anywhere.
 *       Three precision tiers exist and only one of them applies here: {@code PIC S9(10)V99} is
 *       {@code NUMERIC(12,2)} for account money, <strong>{@code PIC S9(09)V99} is {@code NUMERIC(11,2)}
 *       and that is this table's {@code TRAN-AMT}</strong>, and {@code PIC S9(04)V99} is
 *       {@code NUMERIC(6,2)} for the disclosure rate.</li>
 *   <li><strong>{@code CHAR} blank-pad policy, stated once and applied uniformly.</strong> Every text
 *       column here is fixed-width {@code CHAR(n)}, and PostgreSQL stores and returns such a value padded
 *       with blanks to the full declared width - measured, not assumed:
 *       {@code octet_length('POS TERM'::char(10))} is 10 while {@code length(...)} reports 8, because the
 *       server's {@code length} function ignores trailing blanks and the driver does not. So a value read
 *       back through JPA is <em>always</em> exactly {@code n} characters. Assertions here therefore check
 *       the width against the declared column width and compare the stripped value against the logical
 *       one, rather than pretending the padding is absent. Trailing blanks are data in a fixed-width
 *       record, so nothing is trimmed on the way in.</li>
 *   <li><strong>Pagination</strong>: the transaction list page size is carried by
 *       {@code carddemo.pagination.transaction-list-page-size} and is 10. The keyset browse below uses an
 *       explicit window size of its own so that it asserts the paging mechanism rather than restating a
 *       configured value.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test aborts with a container or Docker error.</em> No reachable Docker socket. State the
 *       blocker rather than asserting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or a 1.x version is resolved.</em>
 *       fatal, and the remedy has two halves that are both required. Testcontainers is
 *       pinned to exactly {@code 2.0.3} by <em>overriding the version property the Spring Boot parent
 *       manages</em> - never by importing a second bill of materials, because two competing imports resolve
 *       in an ordering-dependent way that can silently select the parent-managed 1.x line - and only the
 *       <em>prefixed</em> module coordinates exist on the 2.x line, namely {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. The bare 1.x identifiers {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} are not published there. Overriding without renaming
 *       resolves artefacts that do not exist; renaming without overriding resolves the wrong version. Both
 *       halves are already in place in the root {@code pom.xml}, which is root-owned and must not be edited
 *       from here.</li>
 *   <li><em>The build fails on something trivial-looking.</em> Compilation runs {@code -Xlint:all} with
 *       {@code -Werror} and {@code failOnWarning}, and that reaches test compilation, so a single unused
 *       import is a build failure rather than a warning.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a column name.</em> {@code validate}
 *       compares type codes, so a {@code Long} over {@code NUMERIC(9)} can fail where {@code BIGINT}
 *       passes, an {@code Integer} over {@code NUMERIC(4)} where {@code INTEGER} or {@code SMALLINT}
 *       passes, and a {@code BigDecimal} over {@code NUMERIC(11,2)} must match exactly. <strong>The fix is
 *       upstream</strong>, in {@code V1__create_schema.sql} or in the entity mapping. Never widen a column
 *       to silence it and never patch this test.</li>
 *   <li><em>A native query fails with a syntax error near {@code transaction}.</em> The identifier was left
 *       unquoted. It must be written {@code "transaction"}.</li>
 *   <li><em>An assertion insists the alternate index is unique, or types the timestamp finder as
 *       {@link java.util.Optional}.</em> Both are misreadings of
 *       {@code app/catlg/LISTCAT.txt:3677} against {@code :3678}. So are mapping {@code tran_source} to an
 *       enum, mapping either timestamp as a temporal type, introducing a sequence, identity,
 *       {@code @GeneratedValue}, retry loop or upsert for the identifier, adding a non-negative constraint
 *       to {@code tran_amt}, and adding a merchant-ZIP validator.</li>
 *   <li><em>A timestamp assertion fails by a few trailing digits.</em> The value was formatted at
 *       millisecond or nanosecond precision. The batch rendering is
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}: <strong>two hundredths-of-a-second digits then the four
 *       literal characters {@code 0000}</strong>, summing to 26. This matters, because a
 *       wrong width silently breaks every parity comparison downstream.</li>
 *   <li><em>An amount assertion fails although the numbers look identical.</em>
 *       {@code BigDecimal.equals} compares scale as well as value, so {@code 1.0} and {@code 1.00} are
 *       unequal. Use {@code isEqualByComparingTo}.</li>
 *   <li><em>A test looks for a transaction fixture.</em> There is none, deliberately, and the harness
 *       fixture reader refuses the name. Do not create one.</li>
 *   <li><em>The empty-table assertion fails only when the suite is run whole.</em> A row leaked. Isolation
 *       comes solely from the base class's per-test transactional rollback; this class adds no
 *       {@code @Sql}, no {@code @DirtiesContext}, no {@code TRUNCATE} and no {@code deleteAll()}, opens no
 *       second connection and starts no second thread, and it must stay that way. This matters more here
 *       than anywhere else in the tier, because a leaked row would make "the table is empty" order
 *       dependent and would turn a correct suite red for the wrong reason.</li>
 *   <li><em>A provoked violation is followed by "current transaction is aborted".</em> A failed statement
 *       aborts the PostgreSQL transaction block, so each provocation is deliberately the last database
 *       operation in its test method. One provocation per test.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are recorded as <strong>"Not available"</strong> rather than filled in, because inventing
 * either would manufacture a false oracle. The prohibition binds hardest on this class, since
 * {@code "transaction"} is precisely the table a fabricated baseline would populate.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity expectation exists; what is Not available is a captured z/OS run to
 *       corroborate it.</strong> {@code src/test/resources/parity/gate1/} holds the frozen program's own output --
 *       {@code TRANSACT.expected}, {@code ACCTDATA.expected}, {@code TCATBALF.expected}, {@code DALYREJS.expected}
 *       and {@code CBTRN02C.sysout.expected} -- derived by compiling {@code app/cbl/CBTRN02C.cbl} unmodified and
 *       running it against the frozen fixtures, with the harness and the derivation recorded beside them in
 *       {@code PROVENANCE.properties}. What would
 *       be needed is a captured 430-byte reject dataset from a real posting run at a known input state,
 *       together with the resulting transaction, account and category-balance images. Until that exists:
 *       create no baseline file, fabricate no expected bytes, and do not generate a baseline by running
 *       this implementation and asserting against its own output, which is circular. Hand-simulating the
 *       posting program is equally inadmissible and demonstrably so - two defensible models of it over
 *       these same fixtures disagree, 13 rejects against 38, and a figure that moves with the model is not
 *       an oracle. Any legitimate baseline would have to respect the record lengths the corpus declares:
 *       reject 430, being 350 plus an 80-byte trailer; report line 133; statement text 80; statement HTML
 *       80 at {@code app/jcl/CREASTMT.JCL:69} but 100 at {@code :94}, a legacy defect to log and never
 *       fix; and the transaction image 350.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> Across
 *       the 28 programs of {@code app/cbl} the literal file status {@code '35'} occurs zero times and
 *       {@code DFHRESP(NOTOPEN)} zero times. The condition is specification-derived only, so no test for
 *       it is invented here and no parity claim is made for it.</p></li>
 *   </ol>
 *
 * <h2>Side effects and isolation</h2>
 *
 * <p>Every row this class writes it writes itself, because the table starts empty, and every write happens
 * inside the base class's per-test transaction and is rolled back when the method ends. Nothing is
 * committed, nothing is shared between methods, and no member of this class is {@code static}.
 */
final class TransactionRepositoryTest extends AbstractRepositoryIntegrationTest {

    /**
     * The repository under test, injected rather than constructed so that the proxy, its exception
     * translation and its derived-query implementations are the ones production uses.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Used for two things only: parameter-bound catalogue and metadata queries, and parameter-bound native
     * inserts that deliberately bypass the entity's own width and range guards so that the
     * <em>database</em> boundary is what a boundary assertion exercises. It joins the same transaction the
     * test method runs in, so its writes roll back with everything else.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The relation name, written once in the double-quoted lowercase form the migrations emit.
     *
     * <p>It is an instance field rather than a {@code static final} constant because this tier documents
     * that the harness holds the only {@code static} member in the hierarchy. It is used as a
     * <em>bound parameter</em> to catalogue queries, never interpolated into SQL text; where the name has
     * to appear inside a statement it is written {@code "transaction"} in the statement itself.
     */
    private final String relationName = "transaction";

    /** Declared width of {@code TRAN-ID} and {@code TRAN-CARD-NUM}, {@code PIC X(16)}. */
    private final int identifierWidth = 16;

    /** Declared width of {@code TRAN-TYPE-CD}, {@code PIC X(02)}. */
    private final int typeCodeWidth = 2;

    /** Declared width of {@code TRAN-SOURCE}, {@code PIC X(10)}, and of {@code TRAN-MERCHANT-ZIP}. */
    private final int sourceWidth = 10;

    /** Declared width of {@code TRAN-DESC}, {@code PIC X(100)}. */
    private final int descriptionWidth = 100;

    /** Declared width of both timestamps, {@code PIC X(26)} - {@code AXRKP 304} keys on the second. */
    private final int timestampWidth = 26;

    /** Declared precision of {@code TRAN-AMT}: {@code PIC S9(09)V99} is {@code NUMERIC(11,2)}. */
    private final int amountPrecision = 11;

    /** Declared scale of {@code TRAN-AMT}, and of every money column in the corpus. */
    private final int amountScale = 2;

    /**
     * The index {@code V2__create_indexes.sql} creates over {@code tran_proc_ts}, non-unique and B-tree.
     *
     * <p>The name is the one the migration emits, which is the name asserted throughout this class.
     */
    private final String alternateIndexName = "idx_transaction_proc_ts";

    /** The primary key {@code V1__create_schema.sql} declares over {@code tran_id}. */
    private final String primaryKeyName = "pk_transaction";

    /**
     * A sixteen-character identifier that no seeded card carries, used to provoke the card foreign key.
     *
     * <p>It is an all-zero synthetic sentinel rather than card data: it occurs zero times in
     * {@code carddata.txt} and zero times in {@code V3__seed_data.sql}, which is asserted below before it
     * is relied on. Using a synthetic value is also what keeps real card data out of the constraint
     * violation message the database returns, since PostgreSQL echoes the offending key in its
     * {@code DETAIL} line.
     */
    private final String absentCardNumber = "0000000000000000";

    /** A two-character type code absent from the seven {@code trantype.txt} rows. */
    private final String absentTypeCode = "99";

    /** A category code that appears against no type code in the eighteen {@code trancatg.txt} rows. */
    private final int absentCategoryCode = 9999;

    /**
     * The merchant name literal {@code app/cbl/COBIL00C.cbl:227} moves into {@code TRAN-MERCHANT-NAME}.
     */
    private final String merchantName = "BILL PAYMENT";

    /**
     * The merchant city literal {@code app/cbl/COBIL00C.cbl:228} moves into {@code TRAN-MERCHANT-CITY}.
     */
    private final String merchantCity = "N/A";

    /**
     * A deliberately non-canonical merchant postal code, of the shape the frozen staging fixture carries.
     *
     * <p>{@code dailytran.txt} holds 300 distinct merchant postal codes in mixed formats, so the column
     * carries no format constraint and must not acquire one: adding a validator would be a
     * Ten characters wide, which is exactly {@code PIC X(10)}.
     */
    private final String nonCanonicalMerchantZip = "53200-7529";

    /**
     * The merchant identifier every one of the 300 staging fixture rows carries, {@code PIC 9(09)}.
     */
    private final long merchantId = 800_000_000L;

    /**
     * The transaction source the 250 point-of-sale staging rows carry, at fixture columns 23 to 32.
     *
     * <p>It is a plain {@code String} and never an enum constant. Mapping {@code tran_source} to
     * {@code com.cardemo.model.enums.TransactionSource} would break parity, because the
     * corpus emits at least three different values into the same ten-byte field and an enum would reject
     * or mistranslate whichever it did not know.
     */
    private final String pointOfSaleSource = "POS TERM";

    /**
     * The literal {@code app/cbl/CBACT04C.cbl:484} moves into {@code TRAN-SOURCE} for generated interest
     * transactions, and the second value the source column must accept as plain text.
     */
    private final String systemSource = "System";

    /**
     * The description literal {@code app/cbl/COBIL00C.cbl:223} moves into {@code TRAN-DESC}.
     */
    private final String description = "BILL PAYMENT - ONLINE";

    /**
     * The parameter-bound native insert used by every database-boundary provocation.
     *
     * <p>It names {@code "transaction"} in the double-quoted lowercase form the migrations emit and binds
     * every value, so no fragment of it is assembled by string concatenation. It is a field rather than a
     * local so that the single spelling of the statement is shared by all of its callers.
     */
    private final String nativeInsert = """
            INSERT INTO "transaction" (
              tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
              tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip,
              tran_card_num, tran_orig_ts, tran_proc_ts, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;

    /**
     * The state a freshly migrated database is actually in, asserted first because everything follows it.
     */
    @Nested
    @DisplayName("the default state: V3 seeds this table with zero rows, deliberately")
    final class EmptyTableDefaultState {

        @Test
        @DisplayName("DEFAULT STATE - the table is empty, because the posting job is what fills it")
        void theTableIsEmptyOnAFreshlyMigratedDatabase() {
            assertThat(transactionRepository.count())
                    .as("V3__seed_data.sql carries ten INSERT statements and none targets \"transaction\"; "
                            + "its own census records the relation as 0 rows with no fixture. This is the "
                            + "default state of the system, not an edge case")
                    .isZero();
        }

        @Test
        @DisplayName("DEFAULT STATE - the descending browse is empty, so the first generated id is 1")
        void theDescendingBrowseIsEmptyWhichMakesTheFirstIdentifierOne() {
            final Optional<Transaction> highest = transactionRepository
                    .findFirstByOrderByTransactionIdDesc();

            assertThat(highest)
                    .as("this finder replaces MOVE HIGH-VALUES / STARTBR / READPREV / ENDBR at "
                            + "COTRN02C.cbl:444-447 and COBIL00C.cbl:212-215. Both programs handle the "
                            + "empty file explicitly - COBIL00C.cbl:487-488 and COTRN02C.cbl:688-689 read "
                            + "WHEN DFHRESP(ENDFILE) / MOVE ZEROS TO TRAN-ID, and the next statement adds "
                            + "one - so an empty browse is what makes the first generated identifier 1")
                    .isEmpty();
        }

        @Test
        @DisplayName("DEFAULT STATE - a keyed read of the empty table resolves to nothing and does not throw")
        void aKeyedReadOfTheEmptyTableResolvesToNothing() {
            assertThat(transactionRepository.findById(identifier(1L)))
                    .as("the FILE STATUS '23' equivalent is an empty Optional, not an exception; the "
                            + "typed exception is raised by the service layer that interprets it")
                    .isEmpty();
        }

        @Test
        @DisplayName("there is no transaction fixture, and the harness reader refuses the name outright")
        void noTransactionFixtureExists() {
            assertThatThrownBy(() -> readFixture("transaction.txt"))
                    .as("the nine frozen app/data/ASCII fixtures do not include one for this table and "
                            + "none may be created: the absence is the parity contract. The reader fails "
                            + "immediately and names the resource rather than returning an empty list, so "
                            + "a mistaken name cannot surface far from its cause. Nothing is wrapped here, "
                            + "so there is no root cause to preserve - the census rejects the name before "
                            + "any I/O is attempted")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction.txt")
                    .hasMessageContaining("nine");
        }
    }

    /**
     * The descending browse, the padded ordering that makes it correct, and the race it deliberately keeps.
     */
    @Nested
    @DisplayName("the descending browse that replaces READPREV, and the identifier race it preserves")
    final class DescendingBrowseAndIdentifierRace {

        @Test
        @DisplayName("the finder returns the greatest identifier once rows exist")
        void theFinderReturnsTheGreatestIdentifier() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(2L), cardNumber, pointOfSaleSource,
                    new BigDecimal("56.78"), batchTimestamp(0L)));
            flushAndClear();

            assertThat(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .get()
                    .extracting(Transaction::getTransactionId)
                    .as("READPREV from HIGH-VALUES lands on the greatest key, and adding one to it is the "
                            + "whole of the legacy generator at COTRN02C.cbl:448-449")
                    .isEqualTo(identifier(2L));
        }

        @Test
        @DisplayName("zero padding is what makes a CHAR(16) descending sort agree with numeric order")
        void paddedLexicographicOrderEqualsNumericOrder() {
            final String cardNumber = seededCardNumbers().getFirst();
            final String nine = identifier(9L);
            final String ten = identifier(10L);
            transactionRepository.save(transaction(nine, cardNumber, pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(0L)));
            transactionRepository.save(transaction(ten, cardNumber, pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(0L)));
            flushAndClear();

            assertThat(nine.compareTo(ten))
                    .as("padded to PIC X(16) the two identifiers compare as %s against %s, so text order "
                            + "and numeric order coincide. Unpadded they would not: \"10\" sorts before "
                            + "\"9\", and the generator would then reuse an identifier", nine, ten)
                    .isNegative();
            assertThat(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .get()
                    .extracting(Transaction::getTransactionId)
                    .as("this is the case that would break under an unpadded comparison, so it is asserted "
                            + "rather than assumed")
                    .isEqualTo(ten);
        }

        @Test
        @DisplayName("tran_id has no column default: the key is allocated by the application, not the engine")
        void theIdentifierColumnHasNoDefault() {
            final List<String> defaults = jdbcTemplate.queryForList("""
                    SELECT column_default
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name = ?
                    """, String.class, relationName, "tran_id");

            assertThat(defaults)
                    .as("V1__create_schema.sql declares tran_id CHAR(16) NOT NULL, so the catalogue "
                            + "must return exactly one row for it")
                    .hasSize(1);
            assertThat(defaults.getFirst())
                    .as("a default would be a database-side allocation, and the legacy generator is a "
                            + "descending browse plus one. CVTRA05Y.cpy:5 declares TRAN-ID as a data field, "
                            + "not a generated one")
                    .isNull();
        }

        @Test
        @DisplayName("no sequence and no identity is attached to the table, so no generator was substituted")
        void noSequenceIsAttachedToTheTable() {
            final Long attached = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                      FROM pg_class sequences
                      JOIN pg_depend dependency
                        ON dependency.objid = sequences.oid
                       AND dependency.classid = 'pg_class'::regclass
                      JOIN pg_class tables
                        ON tables.oid = dependency.refobjid
                     WHERE sequences.relkind = 'S'
                       AND tables.relname = ?
                       AND tables.relnamespace = current_schema()::regnamespace
                    """, Long.class, relationName);

            assertThat(attached)
                    .as("substituting a database sequence would change every generated value and break "
                            + "comparison against the legacy baseline - forbidden. The count is scoped to "
                            + "this table on purpose: the schema does hold sequences, but they belong to "
                            + "the framework's own BATCH_* tables")
                    .isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT pg_get_serial_sequence(?, ?)", String.class,
                    "\"" + relationName + "\"", "tran_id"))
                    .as("no serial or identity generator is attached to the key column either")
                    .isNull();
        }

        @Test
        @DisplayName("PRESERVED RACE - a duplicate identifier surfaces as a primary key violation")
        void aDuplicateIdentifierSurfacesAsAPrimaryKeyViolation() {
            final String cardNumber = seededCardNumbers().getFirst();
            final String reused = identifier(1L);
            transactionRepository.save(transaction(reused, cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();
            final Transaction collision = transaction(reused, cardNumber, systemSource,
                    new BigDecimal("56.78"), batchTimestamp(0L));

            assertThatThrownBy(() -> transactionRepository.saveAndFlush(collision))
                    .as("the legacy browse-then-add-one idiom at COTRN02C.cbl:444-451 and "
                            + "COBIL00C.cbl:212-217 is inherently racy, and the race is kept rather than "
                            + "engineered away: substituting a serialised generator would change the "
                            + "generated values and forfeit baseline comparison. The chosen remedy is to "
                            + "let the primary key surface the collision, which the service layer maps to "
                            + "a duplicate-record condition. Held as DL-PP-04 in DECISION_LOG.md. "
                            + "The base key IS "
                            + "unique - LISTCAT.txt:3595 carries no NONUNIQKEY - while the alternate key "
                            + "is not")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(primaryKeyName)
                    .rootCause()
                    .as("the driver's own exception is preserved as the root cause rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining(primaryKeyName);
        }

        @Test
        @DisplayName("PRESERVED RACE - the REAL driver's collision classifies as a duplicate, not as a "
                + "generic integrity failure")
        void theRealCollisionClassifiesAsADuplicate() {
            // Finding F-8, against the real driver rather than a mock. AAP section 0.7.4.1 requires the
            // collision to be answered as the duplicate condition, and the write sites now recognise it by
            // SQLSTATE 23505 rather than by exception subtype - which is not a property of the persistence
            // layer, varying by provider, by dialect and by whether the insert was batched. This asserts the
            // state really is reachable from what the repository throws, which no mock can establish.
            final String cardNumber = seededCardNumbers().getFirst();
            final String reused = identifier(2L);
            transactionRepository.save(transaction(reused, cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();
            final Transaction collision = transaction(reused, cardNumber, systemSource,
                    new BigDecimal("56.78"), batchTimestamp(0L));

            final Throwable refused = catchThrowable(() -> transactionRepository.saveAndFlush(collision));

            assertThat(FileStatusMapper.classifyStoreFailure(refused))
                    .as("the walk has to find SQLSTATE %s in whatever chain the provider actually built; if "
                            + "this fails, every duplicate becomes a store failure and the prescribed 409 "
                            + "becomes a 502", FileStatusMapper.SQLSTATE_UNIQUE_VIOLATION)
                    .isEqualTo(FileStatusMapper.StoreFailureKind.DUPLICATE_KEY);
        }
    }

    /**
     * The third and last alternate index: {@code AXRKP 304}, non-unique, on the processing timestamp.
     */
    @Nested
    @DisplayName("TRANSACT.VSAM.AIX - KEYLEN 26 at AXRKP 304, NONUNIQKEY, on tran_proc_ts")
    final class ThirdAlternateIndexOnProcessingTimestamp {

        @Test
        @DisplayName("the finder is multi-valued, because the alternate key is NOT unique")
        void theFinderIsMultiValuedForASharedProcessingTimestamp() {
            final List<String> cardNumbers = seededCardNumbers();
            final String sharedTimestamp = batchTimestamp(0L);
            transactionRepository.save(transaction(identifier(1L), cardNumbers.get(0), pointOfSaleSource,
                    new BigDecimal("12.34"), sharedTimestamp));
            transactionRepository.save(transaction(identifier(2L), cardNumbers.get(1), systemSource,
                    new BigDecimal("56.78"), sharedTimestamp));
            flushAndClear();

            final Slice<Transaction> found = transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            datePrefix(0L), datePrefix(1L), PageRequest.of(0, 20));

            assertThat(found.getContent())
                    .as("LISTCAT.txt:3676 AXRKP 304 = record byte 305, which is where TRAN-PROC-TS "
                            + "begins, and LISTCAT.txt:3674 KEYLEN 26 matches its PIC X(26) width. "
                            + "LISTCAT.txt:3678 then declares SPANNED NONUNIQKEY on that alternate index, "
                            + "and TRANFILE.jcl:85 and TRANIDX.jcl:28 both declare NONUNIQUEKEY. Beware "
                            + "LISTCAT.txt:3677: the UNIQUE token there is the dataset-name attribute, not "
                            + "key uniqueness. Many transactions share a processing timestamp, so the "
                            + "finder returns a Slice and never an Optional - typing it as single-valued "
                            + "would break parity")
                    .hasSizeGreaterThanOrEqualTo(2)
                    .extracting(Transaction::getTransactionId)
                    .containsExactly(identifier(1L), identifier(2L));
        }

        @Test
        @DisplayName("the range is inclusive at both ends of the ten-character prefix")
        void theRangeIsInclusiveAtBothEndsOfTheTenCharacterPrefix() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(2L), cardNumber, pointOfSaleSource,
                    new BigDecimal("2.00"), batchTimestamp(1L)));
            flushAndClear();

            final Slice<Transaction> singleDay = transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            datePrefix(0L), datePrefix(1L), PageRequest.of(0, 20));
            final Slice<Transaction> bothDays = transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            datePrefix(0L), datePrefix(2L), PageRequest.of(0, 20));

            assertThat(singleDay.getContent())
                    .as("TRANREPT.prc:45-46 filters with INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,"
                            + "AND,TRAN-PROC-DT,LE,PARM-END-DATE) - GE and LE, so both ends are "
                            + "inclusive on the ten-character date. Over a CHAR(26) column that is "
                            + "expressed as a half-open range whose exclusive upper bound is the day after "
                            + "the inclusive end date, which is why a bound equal to the stored prefix "
                            + "still includes the row even though the stored value is strictly longer")
                    .extracting(Transaction::getTransactionId)
                    .containsExactly(identifier(1L));
            assertThat(bothDays.getContent())
                    .as("widening the end date by one day brings the second row in, which is the other "
                            + "half of inclusivity at the upper end")
                    .extracting(Transaction::getTransactionId)
                    .containsExactly(identifier(1L), identifier(2L));
        }

        @Test
        @DisplayName("a row one day outside either bound is excluded")
        void aRowOneDayOutsideEitherBoundIsExcluded() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(-1L)));
            transactionRepository.save(transaction(identifier(2L), cardNumber, pointOfSaleSource,
                    new BigDecimal("2.00"), batchTimestamp(1L)));
            flushAndClear();

            assertThat(transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            datePrefix(0L), datePrefix(1L), PageRequest.of(0, 20))
                    .getContent())
                    .as("the day before fails the GE bound and the day after fails the LE bound. The "
                            + "comparison is on text over the first ten characters of a CHAR(26) column, "
                            + "exactly as TRANREPT.prc:40 declares TRAN-PROC-DT,305,10,CH - nothing is "
                            + "parsed to a temporal type to build the predicate")
                    .isEmpty();
        }

        @Test
        @DisplayName("results come back ordered by card number ascending, as STEP05R sorts them")
        void resultsAreOrderedByCardNumberAscending() {
            final List<String> cardNumbers = seededCardNumbers();
            final String sharedTimestamp = batchTimestamp(0L);
            transactionRepository.save(transaction(identifier(1L), cardNumbers.get(2), pointOfSaleSource,
                    new BigDecimal("1.00"), sharedTimestamp));
            transactionRepository.save(transaction(identifier(2L), cardNumbers.get(1), pointOfSaleSource,
                    new BigDecimal("2.00"), sharedTimestamp));
            transactionRepository.save(transaction(identifier(3L), cardNumbers.get(0), pointOfSaleSource,
                    new BigDecimal("3.00"), sharedTimestamp));
            flushAndClear();

            final List<String> returned = new ArrayList<>();
            transactionRepository
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            datePrefix(0L), datePrefix(1L), PageRequest.of(0, 20))
                    .forEach(row -> returned.add(row.getCardNumber()));

            assertThat(returned)
                    .as("TRANREPT.prc:44 sorts SORT FIELDS=(TRAN-CARD-NUM,A) over the field its SYMNAMES "
                            + "place at byte 263 for sixteen characters, so ascending card order is part "
                            + "of the finder's contract and not an accident of insertion order. The three "
                            + "rows were written in descending card order on purpose")
                    .hasSize(3)
                    .isSorted();
        }

        @Test
        @DisplayName("the index over tran_proc_ts exists and indisunique is FALSE")
        void theIndexOverTheProcessingTimestampIsNotUnique() {
            final List<Boolean> unique = jdbcTemplate.queryForList("""
                    SELECT indexes.indisunique
                      FROM pg_index indexes
                      JOIN pg_class index_relation ON index_relation.oid = indexes.indexrelid
                      JOIN pg_class table_relation ON table_relation.oid = indexes.indrelid
                     WHERE table_relation.relname = ?
                       AND table_relation.relnamespace = current_schema()::regnamespace
                       AND index_relation.relname = ?
                    """, Boolean.class, relationName, alternateIndexName);

            assertThat(unique)
                    .as("V2__create_indexes.sql emits this index name, and the migration governs")
                    .hasSize(1);
            assertThat(unique.getFirst())
                    .as("LISTCAT.txt:3676 AXRKP 304 = record byte 305; :3678 NONUNIQKEY. A legacy "
                            + "alternate key is non-unique - three NONUNIQKEY occurrences in the catalogue "
                            + "at :285, :488 and :3678 against zero of UNIQUEKEY - so asserting uniqueness "
                            + "on any of the three alternate indexes would break parity")
                    .isFalse();
        }

        @Test
        @DisplayName("the primary key is the only unique index, and there is no fourth index")
        void thePrimaryKeyIsTheOnlyUniqueIndexOnTheTable() {
            final List<String> unique = jdbcTemplate.queryForList("""
                    SELECT index_relation.relname
                      FROM pg_index indexes
                      JOIN pg_class index_relation ON index_relation.oid = indexes.indexrelid
                      JOIN pg_class table_relation ON table_relation.oid = indexes.indrelid
                     WHERE table_relation.relname = ?
                       AND table_relation.relnamespace = current_schema()::regnamespace
                       AND indexes.indisunique
                     ORDER BY index_relation.relname
                    """, String.class, relationName);
            final List<String> all = jdbcTemplate.queryForList("""
                    SELECT index_relation.relname
                      FROM pg_index indexes
                      JOIN pg_class index_relation ON index_relation.oid = indexes.indexrelid
                      JOIN pg_class table_relation ON table_relation.oid = indexes.indrelid
                      JOIN pg_am access_method ON access_method.oid = index_relation.relam
                     WHERE table_relation.relname = ?
                       AND table_relation.relnamespace = current_schema()::regnamespace
                       AND access_method.amname = 'btree'
                     ORDER BY index_relation.relname
                    """, String.class, relationName);

            assertThat(unique)
                    .as("the base cluster key is unique - LISTCAT.txt:3593-3595 gives KEYLEN 16 at RKP 0 "
                            + "with no NONUNIQKEY - and the primary key is the only expression of that")
                    .containsExactly(primaryKeyName);
            assertThat(all)
                    .as("V2 creates exactly three B-tree indexes across three tables, one per catalogued "
                            + "alternate index, so this table carries exactly two: its primary key and its "
                            + "one alternate-index replacement. There is no fourth index and none may be "
                            + "added")
                    .containsExactly(alternateIndexName, primaryKeyName);
        }
    }

    /**
     * The two timestamps, which are fixed-width text and never a temporal type.
     */
    @Nested
    @DisplayName("TRAN-ORIG-TS and TRAN-PROC-TS are CHAR(26) text, never a temporal type")
    final class FixedWidthTextTimestamps {

        @Test
        @DisplayName("both timestamp columns are character types of width twenty-six")
        void bothTimestampColumnsAreCharacterOfWidthTwentySix() {
            final List<String> types = jdbcTemplate.queryForList("""
                    SELECT data_type
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name IN (?, ?)
                     ORDER BY column_name
                    """, String.class, relationName, "tran_orig_ts", "tran_proc_ts");
            final List<Integer> widths = jdbcTemplate.queryForList("""
                    SELECT character_maximum_length
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name IN (?, ?)
                     ORDER BY column_name
                    """, Integer.class, relationName, "tran_orig_ts", "tran_proc_ts");

            assertThat(types)
                    .as("three incompatible producers write these columns - the batch form "
                            + "yyyy-MM-dd-HH.mm.ss.SS0000, the online form yyyy-MM-dd HH:mm:ss.000000 from "
                            + "COBIL00C.cbl:230-232, and pure pass-through - and the frozen staging fixture "
                            + "carries twenty-six blanks in all 300 of its processing-timestamp positions. "
                            + "No temporal type can hold twenty-six blanks, so text is the only faithful "
                            + "mapping; typing either column as a timestamp would break parity")
                    .containsExactly("character", "character");
            assertThat(widths)
                    .as("CVTRA05Y.cpy:16-17 declares both as PIC X(26), and KEYLEN 26 on the alternate "
                            + "index at LISTCAT.txt:3674 matches the second of them exactly")
                    .containsExactly(timestampWidth, timestampWidth);
        }

        @Test
        @DisplayName("a generated batch timestamp is hundredths plus four literal zeros, twenty-six wide")
        void aGeneratedBatchTimestampIsHundredthsPlusFourZeros() {
            final String generated = batchTimestamp(0L);

            assertThat(generated)
                    .as("the shape is yyyy-MM-dd-HH.mm.ss. then two hundredths digits then the literal "
                            + "0000 that CBTRN02C.cbl:701 moves into DB2-REST PIC X(04). Formatting at "
                            + "millisecond or nanosecond precision changes the width and silently breaks "
                            + "every downstream parity comparison")
                    .hasSize(timestampWidth)
                    .endsWith("0000")
                    .matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}");
            assertThat(generated)
                    .as("the instant is the harness's fixed clock, never the ambient one, so this value is "
                            + "the same on every machine and in every locale")
                    .startsWith("2022-06-10-19.27.53");
        }

        @Test
        @DisplayName("a generated value round-trips at exactly twenty-six characters")
        void aGeneratedTimestampRoundTripsAtTwentySixCharacters() {
            final String cardNumber = seededCardNumbers().getFirst();
            final String processedAt = batchTimestamp(0L);
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), processedAt));
            flushAndClear();

            final Transaction reloaded = transactionRepository.findById(identifier(1L)).orElseThrow();

            assertThat(reloaded.getProcTs())
                    .as("the alternate-index key survives the round trip byte for byte")
                    .hasSize(timestampWidth)
                    .isEqualTo(processedAt);
            assertThat(reloaded.getOrigTs())
                    .as("the originating timestamp was written in the online form, and the column accepts "
                            + "it unchanged because it is text rather than a parsed instant")
                    .hasSize(timestampWidth)
                    .isEqualTo(onlineTimestamp());
        }

        @Test
        @DisplayName("twenty-six blanks persist, which is what no temporal type could hold")
        void twentySixBlanksPersist() {
            final String cardNumber = seededCardNumbers().getFirst();
            final String blankTimestamp = " ".repeat(timestampWidth);
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), blankTimestamp));
            flushAndClear();

            assertThat(transactionRepository.findById(identifier(1L)).orElseThrow().getProcTs())
                    .as("the frozen staging fixture holds one distinct processing-timestamp value across "
                            + "all 300 rows, and it is twenty-six blanks. This is the decisive negative "
                            + "evidence for the text mapping, so it is asserted rather than described")
                    .hasSize(timestampWidth)
                    .isBlank();
        }

        @Test
        @DisplayName("a twenty-seven character timestamp is refused at the database boundary")
        void anOverLengthTimestampIsRefusedAtTheDatabaseBoundary() {
            final String cardNumber = seededCardNumbers().getFirst();
            final String tooLong = batchTimestamp(0L) + "9";

            assertThatThrownBy(() -> insertNativeRow(identifier(1L), seededTypeCodeOfFirstPair(),
                    seededCategoryCodeOfFirstPair(), new BigDecimal("12.34"), cardNumber, tooLong,
                    batchTimestamp(0L)))
                    .as("the entity guards the width too, but this asserts the guard the database itself "
                            + "applies, reached with a parameter-bound native statement so that no entity "
                            + "check can mask it. A twenty-seven character value is one past PIC X(26)")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("character(" + timestampWidth + ")")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining("too long");
        }
    }

    /**
     * The signed eleven-digit amount, whose sign and precision are both parity contracts.
     */
    @Nested
    @DisplayName("TRAN-AMT is PIC S9(09)V99, so NUMERIC(11,2) and signed")
    final class SignedElevenDigitAmount {

        @Test
        @DisplayName("the column is NUMERIC(11,2), which is neither the 12,2 nor the 6,2 tier")
        void theColumnIsNumericElevenTwo() {
            final List<Integer> precision = jdbcTemplate.queryForList("""
                    SELECT numeric_precision
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name = ?
                    """, Integer.class, relationName, "tran_amt");
            final List<Integer> scale = jdbcTemplate.queryForList("""
                    SELECT numeric_scale
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                       AND column_name = ?
                    """, Integer.class, relationName, "tran_amt");

            assertThat(precision)
                    .as("nine signed integer digits plus two decimals is eleven, not the twelve of the "
                            + "account money tier PIC S9(10)V99 and not the six of the disclosure rate tier "
                            + "PIC S9(04)V99")
                    .containsExactly(amountPrecision);
            assertThat(scale)
                    .as("CVTRA05Y.cpy:10 fixes the scale at two")
                    .containsExactly(amountScale);
        }

        @Test
        @DisplayName("a negative amount round-trips with its sign intact")
        void aNegativeAmountRoundTripsWithItsSign() {
            final String cardNumber = seededCardNumbers().getFirst();
            final BigDecimal negative = new BigDecimal("-998.33");
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    negative, batchTimestamp(0L)));
            flushAndClear();

            assertThat(transactionRepository.findById(identifier(1L)).orElseThrow().getAmount())
                    .as("the field is signed and the frozen staging fixture carries 50 negative amounts "
                            + "among its 300 rows, with no zero-amount row at all. "
                            + "CBTRN02C.cbl:548-552 adds a negative amount to the current-cycle DEBIT "
                            + "accumulator, which is exactly why the over-limit formula subtracts that "
                            + "accumulator - so any absolute-value normalisation, or a non-negative check "
                            + "constraint, would break parity. Compared with compareTo rather than equals, "
                            + "because equals also compares scale")
                    .isEqualByComparingTo(negative);
        }

        @Test
        @DisplayName("both extremes nine signed integer digits can hold round-trip exactly")
        void bothRepresentableExtremesRoundTrip() {
            final List<String> cardNumbers = seededCardNumbers();
            final BigDecimal largest = new BigDecimal("999999999.99");
            transactionRepository.save(transaction(identifier(1L), cardNumbers.get(0), pointOfSaleSource,
                    largest, batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(2L), cardNumbers.get(1), systemSource,
                    largest.negate(), batchTimestamp(0L)));
            flushAndClear();

            assertThat(transactionRepository.findById(identifier(1L)).orElseThrow().getAmount())
                    .as("the upper bound of PIC S9(09)V99")
                    .isEqualByComparingTo(largest);
            assertThat(transactionRepository.findById(identifier(2L)).orElseThrow().getAmount())
                    .as("and its exact negation, because the picture clause is signed")
                    .isEqualByComparingTo(largest.negate());
        }

        @Test
        @DisplayName("an amount one integer digit too wide is refused at the database boundary")
        void anOverPrecisionAmountIsRefusedAtTheDatabaseBoundary() {
            final String cardNumber = seededCardNumbers().getFirst();
            final BigDecimal tooWide = new BigDecimal("1234567890.00");

            assertThatThrownBy(() -> insertNativeRow(identifier(1L), seededTypeCodeOfFirstPair(),
                    seededCategoryCodeOfFirstPair(), tooWide, cardNumber, onlineTimestamp(),
                    batchTimestamp(0L)))
                    .as("ten integer digits is precision twelve, one tier above this column. The engine "
                            + "refuses it rather than truncating, which is what keeps a widened value from "
                            + "reaching a parity comparison unnoticed")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed, and it names the "
                            + "precision and scale it enforced")
                    .isNotNull()
                    .hasMessageContaining("numeric field overflow");
        }
    }

    /**
     * The three foreign keys this table carries, three of the ten {@code V1__create_schema.sql} declares.
     */
    @Nested
    @DisplayName("the three foreign keys: card, transaction type, and the type-and-category pair")
    final class TheThreeForeignKeys {

        @Test
        @DisplayName("every foreign key value used here is drawn from the frozen fixtures")
        void everyForeignKeyValueIsDrawnFromTheFrozenFixtures() {
            final List<String> cardNumbers = seededCardNumbers();
            final List<String> typeCodes = seededTypeCodes();
            final List<String> pairs = seededTypeAndCategoryPairs();

            assertThat(cardNumbers)
                    .as("carddata.txt carries fifty 150-byte records and CVACT02Y.cpy:5 places CARD-NUM at "
                            + "bytes 1-16; V3 loads exactly those values into card, which is what makes "
                            + "them satisfiable for FK04. The fixture is read by flat classpath name and is "
                            + "never copied, trimmed, normalised or edited")
                    .hasSize(50)
                    .doesNotHaveDuplicates()
                    .allSatisfy(cardNumber -> assertThat(cardNumber).hasSize(identifierWidth));
            assertThat(typeCodes)
                    .as("trantype.txt carries seven 60-byte records and CVTRA03Y.cpy:5 places TRAN-TYPE at "
                            + "bytes 1-2")
                    .hasSize(7)
                    .doesNotHaveDuplicates()
                    .doesNotContain(absentTypeCode);
            assertThat(pairs)
                    .as("trancatg.txt carries eighteen 60-byte records and CVTRA04Y.cpy:5-7 places the "
                            + "six-byte TRAN-CAT-KEY at bytes 1-6")
                    .hasSize(18)
                    .doesNotHaveDuplicates()
                    .doesNotContain(seededTypeCodeOfFirstPair() + "/" + absentCategoryCode);
            assertThat(cardNumbers)
                    .as("the synthetic sentinel used to provoke FK04 is deliberately not card data, which "
                            + "is what keeps real card data out of the DETAIL line PostgreSQL echoes")
                    .doesNotContain(absentCardNumber);
        }

        @Test
        @DisplayName("FK04 - an unknown card number is refused, with message and root cause")
        void anUnknownCardNumberIsRefused() {
            assertThatThrownBy(() -> insertNativeRow(identifier(1L), seededTypeCodeOfFirstPair(),
                    seededCategoryCodeOfFirstPair(), new BigDecimal("12.34"), absentCardNumber,
                    onlineTimestamp(), batchTimestamp(0L)))
                    .as("FK04 of ten. CVTRA05Y.cpy:15 carries the card key inside the transaction record, "
                            + "so a posted transaction must name an existing card. The type and category "
                            + "are drawn from the fixtures so that this is the only constraint violated, "
                            + "which is what makes the assertion on the constraint name deterministic")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk04_transaction_card")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining("fk04_transaction_card");
        }

        @Test
        @DisplayName("FK05 - the parent column is tran_type, not tran_type_cd: the corpus's one asymmetry")
        void theTransactionTypeForeignKeyNamesTheAsymmetricParentColumn() {
            final List<String> parentColumns = jdbcTemplate.queryForList("""
                    SELECT parent.column_name
                      FROM information_schema.table_constraints constraints
                      JOIN information_schema.constraint_column_usage parent
                        ON parent.constraint_name = constraints.constraint_name
                       AND parent.constraint_schema = constraints.constraint_schema
                     WHERE constraints.constraint_schema = current_schema()
                       AND constraints.constraint_name = ?
                    """, String.class, "fk05_transaction_type");
            final List<String> childColumns = jdbcTemplate.queryForList("""
                    SELECT child.column_name
                      FROM information_schema.table_constraints constraints
                      JOIN information_schema.key_column_usage child
                        ON child.constraint_name = constraints.constraint_name
                       AND child.constraint_schema = constraints.constraint_schema
                     WHERE constraints.constraint_schema = current_schema()
                       AND constraints.constraint_name = ?
                     ORDER BY child.ordinal_position
                    """, String.class, "fk05_transaction_type");

            assertThat(childColumns)
                    .as("the child column carries the -CD suffix every other code column in the corpus "
                            + "carries")
                    .containsExactly("tran_type_cd");
            assertThat(parentColumns)
                    .as("the parent column does not, because CVTRA03Y.cpy:5 declares the field as "
                            + "TRAN-TYPE - the one exception in the corpus. Assuming symmetry here produces "
                            + "a constraint that will not create")
                    .containsExactly("tran_type");
        }

        @Test
        @DisplayName("FK05 - an unknown type code is refused, with message and root cause")
        void anUnknownTypeCodeIsRefused() {
            assertThatThrownBy(() -> insertNativeRow(identifier(1L), absentTypeCode,
                    seededCategoryCodeOfFirstPair(), new BigDecimal("12.34"),
                    seededCardNumbers().getFirst(), onlineTimestamp(), batchTimestamp(0L)))
                    .as("FK05 of ten. A type-only violation is unreachable by construction, and that is a "
                            + "schema fact rather than a limitation of this test: FK09 forces every "
                            + "transaction_category row's type to exist in transaction_type, so a type "
                            + "absent from the parent is necessarily absent from the pair table too and "
                            + "FK06 is violated at the same time. Asserting whichever of the two the engine "
                            + "reports first would depend on constraint-trigger firing order, which is not "
                            + "a documented guarantee, so the constraint name is asserted as one of the two "
                            + "and FK05's exact shape is asserted separately from the catalogue")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("violates foreign key constraint")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed, and it names the "
                            + "referential constraint the type code broke")
                    .isNotNull()
                    .satisfies(rootCause -> assertThat(rootCause.getMessage())
                            .containsAnyOf("fk05_transaction_type", "fk06_transaction_category"));
        }

        @Test
        @DisplayName("FK06 - an unknown type-and-category pair is refused, with message and root cause")
        void anUnknownTypeAndCategoryPairIsRefused() {
            assertThatThrownBy(() -> insertNativeRow(identifier(1L), seededTypeCodeOfFirstPair(),
                    absentCategoryCode, new BigDecimal("12.34"), seededCardNumbers().getFirst(),
                    onlineTimestamp(), batchTimestamp(0L)))
                    .as("FK06 of ten, over the composite key CVTRA04Y.cpy:5-7 declares. The type code is "
                            + "drawn from the fixtures so FK05 is satisfied and only the pair fails, which "
                            + "is what isolates this constraint deterministically")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk06_transaction_category")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining("fk06_transaction_category");
        }
    }

    /**
     * The optimistic-locking counter, one of exactly four in the schema.
     */
    @Nested
    @DisplayName("the version column, one of exactly four in the schema")
    final class OptimisticLockingCounter {

        @Test
        @DisplayName("the counter starts at zero and increments on a real update")
        void theCounterStartsAtZeroAndIncrementsOnUpdate() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();

            final Transaction inserted = transactionRepository.findById(identifier(1L)).orElseThrow();
            assertThat(inserted.getVersion())
                    .as("V1 declares version BIGINT NOT NULL on exactly four tables - account, card, "
                            + "customer and \"transaction\" - and the provider seeds it on insert")
                    .isZero();

            inserted.setAmount(new BigDecimal("56.78"));
            transactionRepository.save(inserted);
            flushAndClear();

            assertThat(transactionRepository.findById(identifier(1L)).orElseThrow().getVersion())
                    .as("the increment is applied by the flush, so the row is re-read from the database "
                            + "rather than from the first-level cache")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a stale-version write fails with an optimistic locking failure, message and cause")
        void aStaleVersionWriteFails() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();
            final Transaction current = transactionRepository.findById(identifier(1L)).orElseThrow();
            current.setAmount(new BigDecimal("56.78"));
            transactionRepository.save(current);
            flushAndClear();

            final Transaction stale = transaction(identifier(1L), cardNumber, systemSource,
                    new BigDecimal("99.99"), batchTimestamp(0L));
            stale.setVersion(0L);

            assertThatThrownBy(() -> transactionRepository.saveAndFlush(stale))
                    .as("the store-level guard is necessary but is deliberately not the whole of the "
                            + "concurrency design: a version counter detects that a row changed, whereas "
                            + "the legacy account-update program compares business field values against a "
                            + "snapshot. That second layer belongs to the service tier; this asserts the "
                            + "first")
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("Transaction")
                    .cause()
                    .as("the provider's own stale-state exception is preserved as the cause rather than "
                            + "swallowed")
                    .isNotNull();
        }
    }

    /**
     * The whole 350-byte record, round-tripped through the mapping the migrations validate.
     */
    @Nested
    @DisplayName("the fourteen mapped properties of the 350-byte record round-trip at their declared widths")
    final class FullRowRoundTrip {

        @Test
        @DisplayName("every fixed-width text property comes back at exactly its declared width")
        void everyFixedWidthPropertyComesBackAtItsDeclaredWidth() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();

            final Transaction reloaded = transactionRepository.findById(identifier(1L)).orElseThrow();

            assertThat(reloaded.getTransactionId()).hasSize(identifierWidth);
            assertThat(reloaded.getCardNumber())
                    .as("PIC X(16) at bytes 263-278, which is where TRANREPT.prc:39 places "
                            + "TRAN-CARD-NUM,263,16,ZD. The value is never written to a message")
                    .hasSize(identifierWidth);
            assertThat(reloaded.getTypeCode()).hasSize(typeCodeWidth);
            assertThat(reloaded.getMerchantName())
                    .as("PIC X(50) at bytes 153-202, blank-padded to the full width on the way back")
                    .hasSize(50)
                    .startsWith(merchantName);
            assertThat(reloaded.getMerchantCity())
                    .as("PIC X(50) at bytes 203-252")
                    .hasSize(50)
                    .startsWith(merchantCity);
            assertThat(reloaded.getDescription())
                    .as("PIC X(100) at bytes 33-132. PostgreSQL pads a CHAR(n) value to n and the driver "
                            + "returns the padding, so the stripped value is compared against the logical "
                            + "one rather than pretending the padding is absent")
                    .hasSize(descriptionWidth);
            assertThat(reloaded.getDescription().strip()).isEqualTo(description);
        }

        @Test
        @DisplayName("the source column is plain text and accepts both producers' values")
        void theSourceColumnIsPlainTextAndAcceptsBothProducers() {
            final List<String> cardNumbers = seededCardNumbers();
            transactionRepository.save(transaction(identifier(1L), cardNumbers.get(0), pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(2L), cardNumbers.get(1), systemSource,
                    new BigDecimal("56.78"), batchTimestamp(0L)));
            flushAndClear();

            final Transaction fromTerminal = transactionRepository.findById(identifier(1L)).orElseThrow();
            final Transaction fromInterestRun = transactionRepository.findById(identifier(2L)).orElseThrow();

            assertThat(fromTerminal.getTransactionSource())
                    .as("the 250 point-of-sale rows of the frozen staging fixture carry this value at "
                            + "columns 23-32, and COBIL00C.cbl:222 moves the same literal")
                    .hasSize(sourceWidth);
            assertThat(fromTerminal.getTransactionSource().strip()).isEqualTo(pointOfSaleSource);
            assertThat(fromInterestRun.getTransactionSource())
                    .as("CBACT04C.cbl:484 moves a different literal into the very same ten-byte field, and "
                            + "the fixture carries a third value in its remaining 50 rows. An enum mapping "
                            + "would reject or mistranslate whichever value it did not know, so "
                            + "tran_source is a String and mapping it to the enum would break parity")
                    .hasSize(sourceWidth);
            assertThat(fromInterestRun.getTransactionSource().strip()).isEqualTo(systemSource);
        }

        @Test
        @DisplayName("a non-canonical merchant postal code persists unchanged, because there is no validator")
        void aNonCanonicalMerchantPostalCodePersists() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();

            assertThat(transactionRepository.findById(identifier(1L)).orElseThrow().getMerchantZip())
                    .as("the frozen staging fixture carries 300 distinct merchant postal codes in mixed "
                            + "formats, so PIC X(10) at bytes 253-262 is unvalidated text. Adding a format "
                            + "constraint would reject data the source accepts - forbidden")
                    .hasSize(sourceWidth)
                    .isEqualTo(nonCanonicalMerchantZip);
        }

        @Test
        @DisplayName("the numeric non-money properties round-trip as an Integer and a Long")
        void theNumericNonMoneyPropertiesRoundTrip() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("12.34"), batchTimestamp(0L)));
            flushAndClear();

            final Transaction reloaded = transactionRepository.findById(identifier(1L)).orElseThrow();

            assertThat(reloaded.getCategoryCode())
                    .as("TRAN-CAT-CD PIC 9(04) is NUMERIC(4) and maps to an Integer; the pinned JDBC type "
                            + "code is what forces the parent column of transaction_category to be NUMERIC "
                            + "as well")
                    .isEqualTo(seededCategoryCodeOfFirstPair());
            assertThat(reloaded.getMerchantId())
                    .as("TRAN-MERCHANT-ID PIC 9(09) is NUMERIC(9) and maps to a Long; all 300 rows of the "
                            + "frozen staging fixture carry this same value")
                    .isEqualTo(merchantId);
        }

        @Test
        @DisplayName("a seventeen-character identifier is refused at the database boundary")
        void anOverLengthIdentifierIsRefusedAtTheDatabaseBoundary() {
            assertThatThrownBy(() -> insertNativeRow(identifier(1L) + "7", seededTypeCodeOfFirstPair(),
                    seededCategoryCodeOfFirstPair(), new BigDecimal("12.34"),
                    seededCardNumbers().getFirst(), onlineTimestamp(), batchTimestamp(0L)))
                    .as("one character past PIC X(16). The seventeenth character is deliberately not a "
                            + "blank: PostgreSQL discards trailing blanks when fitting a value into "
                            + "CHAR(n), so a padded over-length value would be accepted and would prove "
                            + "nothing")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("character(" + identifierWidth + ")")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining("too long");
        }

        @Test
        @DisplayName("a three-character type code is refused at the database boundary")
        void anOverLengthTypeCodeIsRefusedAtTheDatabaseBoundary() {
            assertThatThrownBy(() -> insertNativeRow(identifier(1L),
                    seededTypeCodeOfFirstPair() + "9", seededCategoryCodeOfFirstPair(),
                    new BigDecimal("12.34"), seededCardNumbers().getFirst(), onlineTimestamp(),
                    batchTimestamp(0L)))
                    .as("one character past PIC X(02). The width is refused before the referential check "
                            + "is ever reached, which is why this asserts a width failure and not a "
                            + "foreign key one")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("character(" + typeCodeWidth + ")")
                    .rootCause()
                    .as("the driver's exception is preserved rather than swallowed")
                    .isNotNull()
                    .hasMessageContaining("too long");
        }
    }

    /**
     * The keyset browse the transaction list screen pages with, forwards and backwards.
     */
    @Nested
    @DisplayName("the keyset browse that replaces STARTBR with READNEXT and READPREV")
    final class KeysetPagedBrowse {

        @Test
        @DisplayName("forward and backward windows are complementary and do not overlap")
        void forwardAndBackwardWindowsAreComplementaryAndDoNotOverlap() {
            final List<String> cardNumbers = seededCardNumbers();
            final int rowCount = 6;
            final int windowSize = 3;
            for (int ordinal = 1; ordinal <= rowCount; ordinal++) {
                transactionRepository.save(transaction(identifier(ordinal),
                        cardNumbers.get(ordinal % cardNumbers.size()), pointOfSaleSource,
                        new BigDecimal(ordinal + ".00"), batchTimestamp(0L)));
            }
            flushAndClear();

            final List<String> firstWindow = new ArrayList<>();
            transactionRepository
                    .findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(identifier(1L),
                            PageRequest.of(0, windowSize))
                    .forEach(row -> firstWindow.add(row.getTransactionId()));
            final List<String> nextWindow = new ArrayList<>();
            transactionRepository
                    .findByTransactionIdGreaterThanOrderByTransactionIdAsc(firstWindow.getLast(),
                            PageRequest.of(0, windowSize))
                    .forEach(row -> nextWindow.add(row.getTransactionId()));
            final List<String> backWindow = new ArrayList<>();
            transactionRepository
                    .findByTransactionIdLessThanOrderByTransactionIdDesc(nextWindow.getFirst(),
                            PageRequest.of(0, windowSize))
                    .forEach(row -> backWindow.add(row.getTransactionId()));

            assertThat(firstWindow)
                    .as("the inclusive forward finder reopens a browse at a remembered key, which is how "
                            + "the screen redisplays the page it was already showing")
                    .containsExactly(identifier(1L), identifier(2L), identifier(3L));
            assertThat(nextWindow)
                    .as("the exclusive forward finder advances past the last key of the previous window, "
                            + "so the two windows are complementary rather than overlapping")
                    .containsExactly(identifier(4L), identifier(5L), identifier(6L));
            assertThat(nextWindow).doesNotContainAnyElementsOf(firstWindow);
            assertThat(backWindow)
                    .as("the backward finder walks descending from the first key of the current window, "
                            + "which is READPREV, and it lands exactly on the previous window reversed")
                    .containsExactly(identifier(3L), identifier(2L), identifier(1L));
        }

        @Test
        @DisplayName("a window past the end is empty rather than an error, which is how a reader stops")
        void aWindowPastTheEndIsEmpty() {
            final String cardNumber = seededCardNumbers().getFirst();
            transactionRepository.save(transaction(identifier(1L), cardNumber, pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(0L)));
            flushAndClear();

            assertThat(transactionRepository
                    .findByTransactionIdGreaterThanOrderByTransactionIdAsc(identifier(1L),
                            PageRequest.of(0, 10))
                    .getContent())
                    .as("an exhausted browse is the FILE STATUS '10' equivalent: an empty window, not an "
                            + "exception")
                    .isEmpty();
        }

        @Test
        @DisplayName("the statement order finder walks card number then identifier, ascending")
        void theStatementOrderFinderWalksCardThenIdentifier() {
            final List<String> cardNumbers = seededCardNumbers();
            transactionRepository.save(transaction(identifier(1L), cardNumbers.get(1), pointOfSaleSource,
                    new BigDecimal("1.00"), batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(2L), cardNumbers.get(0), pointOfSaleSource,
                    new BigDecimal("2.00"), batchTimestamp(0L)));
            transactionRepository.save(transaction(identifier(3L), cardNumbers.get(1), pointOfSaleSource,
                    new BigDecimal("3.00"), batchTimestamp(0L)));
            flushAndClear();

            final List<Transaction> ordered = transactionRepository.findStatementOrderAfter(
                    identifier(0L), identifier(0L), PageRequest.of(0, 10));

            assertThat(ordered)
                    .as("the statement sort at CREASTMT.JCL:STEP010 orders on card number then transaction "
                            + "identifier, so the finder that replaces it must be stable under both keys. "
                            + "Card numbers are fixed-width digit strings, so text order and numeric order "
                            + "coincide here as they do for identifiers")
                    .hasSize(3);
            assertThat(ordered.stream().map(Transaction::getCardNumber).toList()).isSorted();
            assertThat(ordered.getFirst().getTransactionId()).isEqualTo(identifier(2L));
            assertThat(ordered.get(1).getTransactionId()).isEqualTo(identifier(1L));
            assertThat(ordered.get(2).getTransactionId()).isEqualTo(identifier(3L));
        }
    }

    /**
     * Renders a sixteen-digit, zero-padded transaction identifier from an ordinal.
     *
     * <p>Zero padding to the full {@code PIC X(16)} width is not cosmetic. It is what makes a
     * {@code CHAR(16)} descending sort reproduce the VSAM {@code READPREV} the two online programs use:
     * over fixed-width digit strings, lexicographic order and numeric order coincide. Unpadded, they do
     * not - {@code "10"} sorts before {@code "9"} - which is the divergence the ordering test below
     * exercises deliberately.
     *
     * @param ordinal the numeric value to render; assumed non-negative and within sixteen digits, which
     *                every call site satisfies with a small literal
     * @return exactly {@code identifierWidth} characters, all digits
     */
    private String identifier(final long ordinal) {
        return String.format(Locale.ROOT, "%016d", ordinal);
    }

    /**
     * Renders the batch timestamp layout from the harness's fixed clock.
     *
     * <p>The layout is {@code yyyy-MM-dd-HH.mm.ss.SS0000}: a dash-separated date, a dash, a dotted time,
     * then <strong>two hundredths-of-a-second digits</strong> followed by the four literal characters
     * {@code 0000} that {@code app/cbl/CBTRN02C.cbl:701} moves into {@code DB2-REST PIC X(04)}. That is
     * hundredths, not milliseconds and not nanoseconds, and it sums to exactly {@code timestampWidth}.
     *
     * <p>The instant comes from {@link AbstractRepositoryIntegrationTest#fixedClock()} and never from the
     * ambient clock, and the formatter is built with {@link Locale#ROOT} in
     * {@link ZoneOffset#UTC}, so the rendering is identical on every machine and in every locale.
     *
     * @param dayOffset whole days to shift the fixed instant by, so that a test can place a row inside or
     *                  outside a date range without reading a moving clock
     * @return exactly {@code timestampWidth} characters
     */
    private String batchTimestamp(final long dayOffset) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(fixedClock().instant().plusSeconds(dayOffset * 86_400L))
                + "0000";
    }

    /**
     * Renders the online timestamp layout from the harness's fixed clock.
     *
     * <p>The layout is {@code yyyy-MM-dd HH:mm:ss.000000}: a space separator, a colon-separated time and
     * six literal zeros, summing to exactly {@code timestampWidth}. It is the third of the three
     * incompatible producers that write this pair of columns, and it is the exact rendering the frozen
     * staging fixture carries in all 300 of its originating-timestamp positions.
     * {@code app/cbl/COBIL00C.cbl:230-232} generates one such value and moves it into
     * <em>both</em> {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}.
     *
     * @return exactly {@code timestampWidth} characters
     */
    private String onlineTimestamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(fixedClock().instant())
                + ".000000";
    }

    /**
     * The ten-character date prefix of a rendered timestamp, shifted by whole days.
     *
     * <p>This is the value the range finder binds, and it is deliberately built by rendering a date rather
     * than by parsing a stored timestamp: the comparison the finder performs is on <em>text</em>, over the
     * first ten characters of a {@code CHAR(26)} column, exactly as
     * {@code app/proc/TRANREPT.prc:40} declares {@code TRAN-PROC-DT,305,10,CH}. Nothing here is converted
     * to a temporal type to build a predicate.
     *
     * @param dayOffset whole days to shift the fixed instant by
     * @return exactly ten characters in {@code yyyy-MM-dd} form
     */
    private String datePrefix(final long dayOffset) {
        return batchTimestamp(dayOffset).substring(0, 10);
    }

    /**
     * The sixteen-character card numbers the frozen {@code carddata.txt} fixture carries, in file order.
     *
     * <p>{@code app/cpy/CVACT02Y.cpy:5} places {@code CARD-NUM PIC X(16)} at bytes 1 to 16 of the
     * 150-byte record, and {@code V3__seed_data.sql} loads exactly those values into {@code card}, which is
     * what makes them satisfiable values for this table's card foreign key. The fixture is read through the
     * harness's strict reader by flat classpath name and is neither copied, trimmed, normalised nor edited.
     *
     * <p>The returned values are never written to a log, an assertion description or a message.
     *
     * @return fifty card numbers, each exactly {@code identifierWidth} characters
     */
    private List<String> seededCardNumbers() {
        final List<String> cardNumbers = new ArrayList<>();
        for (final String record : readFixture("carddata.txt")) {
            cardNumbers.add(record.substring(0, identifierWidth));
        }
        return cardNumbers;
    }

    /**
     * The two-character type codes the frozen {@code trantype.txt} fixture carries, in file order.
     *
     * <p>{@code app/cpy/CVTRA03Y.cpy:5} declares the field {@code TRAN-TYPE PIC X(02)} at bytes 1 to 2 of
     * the 60-byte record. Note the name: the parent column is {@code tran_type} while this table's child
     * column is {@code tran_type_cd}, an asymmetry the foreign key test asserts explicitly.
     *
     * @return seven type codes, each exactly {@code typeCodeWidth} characters
     */
    private List<String> seededTypeCodes() {
        final List<String> typeCodes = new ArrayList<>();
        for (final String record : readFixture("trantype.txt")) {
            typeCodes.add(record.substring(0, typeCodeWidth));
        }
        return typeCodes;
    }

    /**
     * The type-and-category pairs the frozen {@code trancatg.txt} fixture carries, rendered for comparison.
     *
     * <p>{@code app/cpy/CVTRA04Y.cpy:5-7} declares {@code TRAN-CAT-KEY} as {@code TRAN-TYPE-CD PIC X(02)}
     * at bytes 1 to 2 followed by {@code TRAN-CAT-CD PIC 9(04)} at bytes 3 to 6, so the key is six bytes
     * wide. The category component is rendered from its parsed {@code int} rather than from the raw
     * characters, which is what lets a pair be compared against the {@code NUMERIC(4)} value this table
     * actually stores.
     *
     * @return eighteen pairs, each in the form {@code typeCode/categoryCode}
     */
    private List<String> seededTypeAndCategoryPairs() {
        final List<String> pairs = new ArrayList<>();
        for (final String record : readFixture("trancatg.txt")) {
            final String typeCode = record.substring(0, typeCodeWidth);
            final int categoryCode = Integer.parseInt(record.substring(typeCodeWidth, 6));
            pairs.add(typeCode + "/" + categoryCode);
        }
        return pairs;
    }

    /**
     * The type code of the first frozen category pair, guaranteed to satisfy both type foreign keys.
     *
     * <p>It is read from the fixture rather than written as a literal so that the value under test is the
     * value the seed actually loaded, and so that a change to the frozen data surfaces here rather than as
     * an unexplained referential failure.
     *
     * @return exactly {@code typeCodeWidth} characters
     */
    private String seededTypeCodeOfFirstPair() {
        return seededTypeAndCategoryPairs().getFirst().substring(0, typeCodeWidth);
    }

    /**
     * The category code of the first frozen category pair, as the {@code NUMERIC(4)} value it maps to.
     *
     * @return a category code the seed loaded against {@link #seededTypeCodeOfFirstPair()}
     */
    private int seededCategoryCodeOfFirstPair() {
        final String pair = seededTypeAndCategoryPairs().getFirst();
        return Integer.parseInt(pair.substring(pair.indexOf('/') + 1));
    }

    /**
     * Builds a fully populated, unsaved {@link Transaction} whose foreign keys are all satisfiable.
     *
     * <p>Every value that is not a parameter comes from a documented corpus literal, so a row built here is
     * a realistic posted transaction rather than filler. The type code and category code are the first pair
     * the frozen category fixture carries, which the seed guarantees exists in {@code transaction_category}
     * and, through it, in {@code transaction_type}.
     *
     * <p>The entity's own constructor guards width, scale and range, so this factory cannot be used to
     * provoke a database-level boundary; {@link #insertNativeRow} exists for that.
     *
     * @param transactionId  sixteen-character identifier, the natural key - never generated by the database
     * @param cardNumber     an existing sixteen-character card number
     * @param source         the ten-byte source text, plain text and never an enum constant
     * @param amount         the signed amount, whose sign must survive the round trip unaltered
     * @param processedAt    the twenty-six character processing timestamp, the alternate-index key
     * @return a transient entity, never {@code null}
     */
    private Transaction transaction(final String transactionId,
                                    final String cardNumber,
                                    final String source,
                                    final BigDecimal amount,
                                    final String processedAt) {
        return new Transaction(transactionId, seededTypeCodeOfFirstPair(),
                seededCategoryCodeOfFirstPair(), source, description, amount, merchantId, merchantName,
                merchantCity, nonCanonicalMerchantZip, cardNumber, onlineTimestamp(), processedAt);
    }

    /**
     * Inserts one row with a parameter-bound native statement, bypassing the entity's guards on purpose.
     *
     * <p>Two of this class's contracts can only be reached this way. First, the {@code CHAR} width and
     * {@code NUMERIC} precision limits are enforced by <em>PostgreSQL</em>, and the entity refuses an
     * over-wide or over-precise value before it ever reaches the driver, so a database-boundary assertion
     * has to go round the entity. Second, the foreign keys are enforced by the database, so provoking one
     * needs a statement the database actually receives.
     *
     * <p>Every value is bound; the only literal in the statement is the {@code version} seed of zero, which
     * is the value the optimistic-locking column starts at. The relation is quoted as {@code "transaction"}
     * in the statement text and no part of the statement is concatenated.
     *
     * <p><strong>Side effect and ordering.</strong> A statement that violates a constraint aborts the
     * enclosing PostgreSQL transaction block, so a provoking call must be the last database operation in
     * its test method. Nothing is committed: the write, or the failure, is discarded when the test's
     * transaction rolls back.
     *
     * @param transactionId the identifier to bind, deliberately allowed to exceed sixteen characters
     * @param typeCode      the type code to bind, deliberately allowed to exceed two characters
     * @param categoryCode  the category code to bind
     * @param amount        the amount to bind, deliberately allowed to exceed {@code NUMERIC(11,2)}
     * @param cardNumber    the card number to bind, deliberately allowed to name no existing card
     * @param originatedAt  the originating timestamp to bind, deliberately allowed to exceed twenty-six
     * @param processedAt   the processing timestamp to bind
     * @throws org.springframework.dao.DataAccessException translated from the driver's own exception, with
     *         the database's message and the driver exception preserved as the cause, whenever the bound
     *         values violate a width, precision, key or referential constraint - which is the intended
     *         outcome at every provoking call site
     */
    private void insertNativeRow(final String transactionId,
                                 final String typeCode,
                                 final int categoryCode,
                                 final BigDecimal amount,
                                 final String cardNumber,
                                 final String originatedAt,
                                 final String processedAt) {
        jdbcTemplate.update(nativeInsert, transactionId, typeCode, categoryCode, pointOfSaleSource,
                description, amount, merchantId, merchantName, merchantCity, nonCanonicalMerchantZip,
                cardNumber, originatedAt, processedAt);
    }

    /**
     * The complete PostgreSQL metadata contract for the {@code transaction} table.
     *
     * <p>Why this delegates rather than restating the facets, and why asserting whichever columns the
     * behavioural tests happen to touch would state no contract at all, is recorded once on
     * {@link SchemaMetadataMatrix}, which declares every facet and asserts the live catalogue against
     * it by exact equality on ordered lists.
     *
     * <p>For {@code transaction} that is fourteen columns whose widths sum to the 350-byte record, the sixteen-character key, the three foreign keys fk04 to fk06, the version column, and the non-unique processing-timestamp index that replaces TRANSACT.VSAM.AIX - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVTRA05Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the transaction table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "transaction");
    }

}
