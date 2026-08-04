/*
 * ******************************************************************
 * Program     : CardRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Testcontainers PostgreSQL 16)
 * Function    : Exercises the CARDDATA cluster replacement against a real
 *               PostgreSQL engine. Proves the one fact about this cluster
 *               that is easiest to get wrong and most expensive to get
 *               wrong: CARDDATA.VSAM.AIX is a NON-UNIQUE alternate index,
 *               so the account-based finder is multi-valued while the
 *               card-number primary key is single-valued. Also pins the
 *               preserved CARD-EXPIRAION-DATE misspelling, the deliberate
 *               absence of any card-verification column or constraint,
 *               the check constraint, the foreign key, the primary key,
 *               the fixed-width CHAR contracts and the optimistic-lock
 *               version column.
 * Source      : app/cpy/CVACT02Y.cpy (key 16, RECLN 150; CARD-ACCT-ID at
 *               L6 bytes 17-27; CARD-EXPIRAION-DATE misspelled at L9),
 *               app/catlg/LISTCAT.txt:202 (cluster KEYLEN 16 AVGLRECL
 *               150) and :281-285 (AIX KEYLEN 11 / RKP 5 / AXRKP 16 /
 *               dataset-name UNIQUE at :284 / NONUNIQKEY at :285),
 *               :150 (PATH), :164 (cluster entry), :287 (REC-TOTAL 50),
 *               app/jcl/CARDFILE.jcl:83-88 (DEFINE ALTERNATEINDEX,
 *               KEYS(11 16), NONUNIQUEKEY) and :100-102 (DEFINE PATH),
 *               app/csd/CARDDEMO.CSD:13-14 (CARDAIX) and :25-26
 *               (CARDDAT), app/cbl/COCRDLIC.cbl:176-178 (page size 7)
 *               and :1158,:1209,:1306,:1334 (browse DUPREC fall-through),
 *               app/cbl/COCRDUPC.cbl:91,:861,:863 (status domain),
 *               app/cbl/COCRDSLC.cbl, app/cbl/CBACT02C.cbl (read-only),
 *               app/data/ASCII/carddata.txt (50 rows of 150 bytes),
 *               app/data/ASCII/acctdata.txt (50 rows of 300 bytes),
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Integration test for {@link CardRepository} against a real PostgreSQL 16 engine.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It asserts the persistence, mapping, index and constraint behaviour of the {@code card} table that
 * replaces the {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} cluster. It asserts nothing about services,
 * controllers or batch steps: the list and filter paths of {@code app/cbl/COCRDLIC.cbl}, the detail path of
 * {@code app/cbl/COCRDSLC.cbl}, the change-detection comparison of {@code app/cbl/COCRDUPC.cbl} and the
 * read-only sequential reader {@code app/cbl/CBACT02C.cbl} (whose entire verb inventory is {@code OPEN
 * INPUT} at :120, {@code READ} at :93 and {@code CLOSE} at :138 - no {@code WRITE}, {@code REWRITE} or
 * {@code DELETE} anywhere) are cited here and exercised in {@code com.cardemo.unit.service} and
 * {@code com.cardemo.integration.batch}. One concern per class.
 *
 * <p><strong>The alternate index is NON-UNIQUE, and the catalogue says so on a different line from the one
 * that looks like it says otherwise.</strong> This is the single most dangerous misreading available in the
 * whole corpus, so it is stated in full. {@code app/catlg/LISTCAT.txt} describes
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} across a block that begins at :254 with the {@code AIX} entry
 * header, records {@code UPGRADE} at :265, and then gives the DATA-component attributes at :281-:285 -
 * {@code KEYLEN 11} at :281, {@code RKP 5} at :282 and {@code AXRKP 16} at :283. Two lines then follow that
 * must be read together:
 *
 * <ul>
 *   <li>:284 reads {@code SHROPTNS(1,3) RECOVERY UNIQUE NOERASE INDEXED NOWRITECHK UNORDERED NOREUSE}.
 *       The {@code UNIQUE} token on that line is the <em>dataset-name</em> attribute - it says the data set
 *       occupies a volume space of its own. <strong>It says nothing whatever about key uniqueness.</strong></li>
 *   <li>:285 reads {@code SPANNED NONUNIQKEY}. <strong>That</strong> is the attribute that governs key
 *       uniqueness, and it says the alternate key is NOT unique.</li>
 * </ul>
 *
 * <p>Three further pieces of evidence agree, independently of the catalogue. First, the IDCAMS job that
 * builds the index spells it out in words: {@code app/jcl/CARDFILE.jcl:83} opens
 * {@code DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX)}, :85 gives {@code KEYS(11 16)},
 * and <strong>:86 reads {@code NONUNIQUEKEY}</strong>, with {@code UPGRADE} at :87 and
 * {@code RECORDSIZE(150,150)} at :88; the matching {@code DEFINE PATH} runs :100-:102 and is what
 * {@code app/csd/CARDDEMO.CSD:13-14} opens as the CICS file {@code CARDAIX}, alongside {@code CARDDAT} over
 * the base cluster at :25-:26. Second, the online program that browses through that path treats a
 * duplicate-key condition as a normal outcome: every one of the four
 * {@code EVALUATE WS-RESP-CD / WHEN DFHRESP(NORMAL) / WHEN DFHRESP(DUPREC)} fall-throughs in
 * {@code app/cbl/COCRDLIC.cbl} - at :1158 and :1209 after {@code EXEC CICS READNEXT}, and at :1306 and
 * :1334 after {@code EXEC CICS READPREV} - accepts {@code DUPREC} on exactly the same branch as
 * {@code NORMAL}, which is only sensible when duplicates are expected. Third, that program pages
 * <strong>seven</strong> rows at a time ({@code app/cbl/COCRDLIC.cbl:176-178}, where
 * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP} carries {@code VALUE 7}) precisely because one account may
 * hold several cards; a single-valued relation would need no window at all.
 *
 * <p>Consequently this class asserts multiplicity <em>positively</em>: it inserts a second card for an
 * account that already has one and requires {@link CardRepository#findByAccountIdOrderByCardNumberAsc} to
 * return at least two rows. That assertion is the whole point of the file. The finder is
 * {@code Page<Card> findByAccountIdOrderByCardNumberAsc(Long, Pageable)} and nothing here treats it as
 * though it were single-valued.
 *
 * <p><strong>The base key IS unique; the alternate key IS NOT.</strong> The two are asserted in the same
 * file deliberately, because the contrast is the contract. {@code app/catlg/LISTCAT.txt:202} gives the base
 * cluster {@code KEYLEN 16 AVGLRECL 150}, :203 gives {@code RKP 0}, and :204 carries the dataset-name
 * {@code UNIQUE} token with <strong>no {@code NONUNIQKEY} anywhere in the block</strong> - so
 * {@code pk_card} on {@code card_num} is genuinely unique and a duplicate card number must be rejected,
 * while {@code idx_card_acct_id} on {@code card_acct_id} must not be unique.
 *
 * <p><strong>Always state the base of an offset.</strong> {@code AXRKP} is zero-based and record-byte prose
 * is one-based, so {@code AXRKP 16} (zero-based) is record byte 17 (one-based) - which is exactly where
 * {@code app/cpy/CVACT02Y.cpy:L6} places {@code CARD-ACCT-ID PIC 9(11)}, at bytes 17-27. The offset is
 * therefore proved three times over and by three independent artefacts: the catalogue's {@code AXRKP 16},
 * the copybook's field arithmetic, and the IDCAMS {@code KEYS(11 16)}. The full one-based map of the
 * 150-byte record is {@code CARD-NUM} 1-16, {@code CARD-ACCT-ID} 17-27, {@code CARD-CVV-CD} 28-30,
 * {@code CARD-EMBOSSED-NAME} 31-80, {@code CARD-EXPIRAION-DATE} 81-90, {@code CARD-ACTIVE-STATUS} 91 and
 * {@code FILLER} 92-150.
 *
 * <p><strong>Do not conflate the two account-id alternate keys.</strong> The other one sits at a different
 * offset: {@code CARDXREF.VSAM.AIX} carries {@code AXRKP 25} at {@code app/catlg/LISTCAT.txt:486}, which is
 * one-based byte 26, matching {@code XREF-ACCT-ID} at bytes 26-36 in {@code app/cpy/CVACT03Y.cpy}. Both
 * indexes are non-unique and both are over an account identifier, and they are still eleven bytes apart.
 *
 * <p><strong>The one-card-per-account shape of the seeded data is a FIXTURE PROPERTY, never an
 * invariant.</strong> All fifty rows of {@code app/data/ASCII/carddata.txt} carry fifty distinct account
 * identifiers, so the seeded data happens to look one-to-one. The schema permits many cards per account and
 * this file proves that it does. The fixture shape is asserted only as a fixture shape, and every assertion
 * message that touches it says so.
 *
 * <p><strong>The two hazards this file exists to guard.</strong> Both matter, because
 * either one silently produces a system that looks correct and is not:
 *
 * <ul>
 *   <li><strong>misreading key uniqueness.</strong> Taking {@code UNIQUE} on
 *       {@code app/catlg/LISTCAT.txt:284} for key uniqueness yields a unique index on
 *       {@code card(card_acct_id)}, a single-valued finder and an {@code Optional} return type. Nothing fails
 *       against the seeded data, because the seed happens to hold one card per account; the defect surfaces
 *       only when a real account holds a second card, at which point the write is refused. Read
 *       {@code NONUNIQKEY} on :285 as the governing attribute, keep the index non-unique, keep the finder
 *       returning a {@code Page}, and assert multiplicity positively as
 *       {@code theAccountFinderIsMultiValued} does.</li>
 *   <li><strong>"correcting" the preserved misspelling.</strong> Renaming
 *       {@code card_expiraion_date} to the correct spelling makes the entity and the Flyway schema disagree,
 *       and because {@code ddl-auto} is {@code validate} in every profile the whole tier fails to start.
 *       Preserve {@code app/cpy/CVACT02Y.cpy:L9} verbatim in both the column and the property, and keep
 *       {@code theMisspelledColumnIsTheRealOne} in place to catch a future rename.</li>
 * </ul>
 *
 * <p><strong>Four facts this file establishes from the migration and the corpus</strong>, each measured here
 * rather than transcribed:
 *
 * <ul>
 *   <li><strong>High, RESOLVED.</strong> {@code card_cvv_cd} <em>is</em> modelled, and an earlier revision of
 *       this file asserted the opposite. The plan described a seventh mapped property {@code cvvCode} over a
 *       {@code CHAR(3)} column with a round-trip assertion on the seeded value, and that is what the system
 *       now has: {@code V1__create_schema.sql} declares {@code card_cvv_cd CHAR(3) NOT NULL},
 *       {@code V3__seed_data.sql} loads bytes 28-30 of all fifty fixture rows, and {@link Card} maps a
 *       private field for it behind a six-argument constructor. The earlier omission was a field-contract
 *       break rather than a hardening measure - {@code app/cpy/CVACT02Y.cpy:L7} declares the field inside the
 *       authoritative 150-byte record - and asserting its absence institutionalised the break. What is
 *       withheld is the read path, not the storage: the entity publishes no accessor that returns the value,
 *       only {@code matchesVerificationValue(String)}, so this file proves the round trip through that
 *       comparison and additionally proves the leading zero survived the load on exactly eight of the fifty
 *       rows, which is what a numeric mapping would have destroyed.</li>
 *   <li><strong>Medium.</strong> The three index names in the plan are not the names in the migration. The
 *       real ones are {@code idx_card_acct_id}, {@code idx_card_cross_reference_acct_id} and
 *       {@code idx_transaction_proc_ts}. The index assertions below are written against the table and the
 *       column, so they hold whatever the index is called, and the real name is asserted as well.</li>
 *   <li><strong>Medium.</strong> The plan cited {@code DUPKEY} sites in {@code app/cbl/COCRDUPC.cbl} at
 *       :1158, :1209, :1306 and :1334. {@code COCRDUPC.cbl} contains no {@code DUPKEY} and no
 *       {@code DUPREC} occurrence at all; those four line numbers are the {@code DFHRESP(DUPREC)} sites in
 *       {@code app/cbl/COCRDLIC.cbl}, which is a materially better citation because they are browse
 *       fall-throughs through the non-unique path. Corpus-wide, {@code DFHRESP(DUPKEY)} occurs three times
 *       ({@code COTRN02C.cbl:735}, {@code COUSR01C.cbl:260}, {@code COBIL00C.cbl:533}), {@code DUPREC}
 *       seven times, and the literal {@code '22'} not once. All are cited, none is asserted.</li>
 *   <li><strong>Medium.</strong> The plan mapped {@code app/jcl/TRANIDX.jcl} to {@code CardRepository};
 *       that member defines the {@code TRANSACT} alternate index. The card index is defined by
 *       {@code app/jcl/CARDFILE.jcl:83-88} with its path at :100-:102.</li>
 *   <li><strong>Low.</strong> The plan warned that the root {@code .editorconfig} whitespace cleanup carves
 *       out only {@code *.md}. It also carves out {@code src/test/resources/**.txt}, so both frozen fixture
 *       copies are protected from the 150-to-91 collapse. The hazard is still real for any other tool and
 *       is repeated under troubleshooting below.</li>
 * </ul>
 *
 * <p><strong>Two disclosures, per Clause F's requirement to say plainly when information is missing.</strong>
 *
 * <ul>
 *   <li><strong>The end-to-end parity baseline is Not available.</strong> No captured mainframe output
 *       exists anywhere in this repository - searching for expected, baseline, golden, {@code .out},
 *       sysout, {@code DALYREJS}, {@code TRANREPT}, {@code STMTFILE} and {@code HTMLFILE} artefacts returns
 *       only dataset-<em>definition</em> job-control members and no captured data. What would be needed is a
 *       captured 430-byte {@code DALYREJS} reject data set together with the resulting {@code TRANSACT},
 *       {@code ACCTDATA} and {@code TCATBALF} images from a real {@code POSTTRAN} run at a known input
 *       state. This file therefore creates no baseline file, fabricates no expected bytes, and derives no
 *       baseline from Java - which would be circular.</li>
 *   <li><strong>File status {@code '35'} (file unavailable) is Not available.</strong> The literal
 *       {@code '35'} occurs zero times and {@code DFHRESP(NOTOPEN)} occurs zero times across
 *       {@code app/cbl}, so there is no source behaviour to reproduce and no test for it is invented here.</li>
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify} - or, unchanged, the command the continuous integration workflow runs.
 * This class is bound to <strong>Failsafe</strong>, not Surefire: the root {@code pom.xml} gives Failsafe
 * the includes {@code **}{@code /integration/}{@code **}{@code /*Test.java} and
 * {@code **}{@code /e2e/}{@code **}{@code /*Test.java} and gives Surefire the matching excludes, so this
 * class executes at {@code integration-test} and is verified at {@code verify} even though its name ends in
 * {@code Test}. <strong>Its path is load-bearing.</strong> Moved up one package, to
 * {@code com/cardemo/integration}, {@code com/cardemo}, {@code com} or the source root, it matches neither
 * plugin's include set and is collected by neither - a green build in which both plugins report success and
 * this file silently never runs. Do not rename or relocate it, and confirm after any build that the
 * Failsafe report names it.
 *
 * <p><strong>A reachable Docker socket is a prerequisite of this tier</strong>, because
 * {@link AbstractRepositoryIntegrationTest} starts a real PostgreSQL 16 container through Testcontainers.
 * Java 25 and Maven 3.9.11 are what the enforcer rules require; where a host lacks them the pinned
 * container image {@code maven:3.9.11-eclipse-temurin-25} runs the same build unchanged. Re-check the
 * toolchain at execution rather than trusting any recorded claim about it.
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p>The {@code test} profile, activated by the base class. PostgreSQL 16 comes from the base class's single
 * digest-pinned container, connected through {@code @ServiceConnection}, which is why no JDBC URL, host,
 * port, database name, user name or password literal appears anywhere in this file. Time comes only from the
 * base class's injected fixed clock; nothing here reads an ambient clock, which matters more than it looks
 * because expiry dates are stored as text and any "not expired" assertion would be time-dependent - so no
 * such assertion is written. Hibernate runs with {@code ddl-auto: validate}, {@code open-in-view: false},
 * {@code show-sql: false} and a JDBC time zone of UTC. Flyway applies exactly three migrations with
 * {@code validate-on-migrate: true}, {@code clean-disabled: true}, {@code out-of-order: false} and
 * {@code baseline-on-migrate: false}; the {@code BATCH_*} tables come from
 * {@code spring.batch.jdbc.initialize-schema} and never from a fourth migration. The legacy card window of
 * seven rows is carried in production by {@code carddemo.pagination.card-list-page-size}, whose value is
 * {@code 7}; this file uses a {@code PageRequest} of size seven to exercise the window and deliberately does
 * not assert that any literal seven is hardcoded, nor read the property, because a repository has no notion
 * of a default page size.
 *
 * <p><strong>The {@code CHAR} blank-pad policy, stated once and applied uniformly.</strong> PostgreSQL
 * blank-pads {@code character(n)}, so a value read back from {@code card_embossed_name CHAR(50)} is always
 * fifty characters wide however short the name is. This file compares against the <em>padded</em> form and
 * never trims: it asserts declared widths and stored widths, and where a shorter logical value is needed it
 * is derived from the frozen fixture rather than typed in. The three columns whose logical values happen to
 * fill their declaration exactly - {@code card_num CHAR(16)}, {@code card_expiraion_date CHAR(10)} and
 * {@code card_active_status CHAR(1)} - are unaffected either way.
 *
 * <p>Isolation is transactional rollback inherited from the base class and nothing else. There is no
 * {@code @Sql}, no {@code @DirtiesContext}, no {@code TRUNCATE} and no {@code deleteAll()}. That matters
 * here specifically because {@code card} is both a child of {@code account} and a parent of
 * {@code "transaction"}, so the extra row this file inserts has to disappear cleanly; rollback is what makes
 * it disappear. This class declares no container, no {@code @SpringBootTest}, no
 * {@code @DynamicPropertySource} and <strong>no static field of any kind</strong> - the base class holds the
 * tier's only one and documents that no subclass may add another.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Every test errors before any assertion runs.</strong> Almost always an absent or
 *       unreachable Docker socket. The container cannot start, so the context cannot refresh.</li>
 *   <li><strong>A Testcontainers artefact fails to resolve.</strong> The pinned line is 2.0.3, where every
 *       module was renamed: only {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter} exist. The bare
 *       {@code postgresql}, {@code localstack} and {@code junit-jupiter} identifiers under
 *       {@code org.testcontainers} do not exist at 2.0.3, and Spring Boot 3.5.11 independently manages a
 *       1.x version, so the remedy has two halves that are both required: override the managed version
 *       through a property rather than importing a second bill of materials, and use only the prefixed
 *       coordinates. Overriding without renaming resolves artefacts that do not exist; renaming without
 *       overriding resolves the wrong version. Both halves live in the root {@code pom.xml}, which this
 *       file must not edit - report it with that two-part fix instead.</li>
 *   <li><strong>The build fails on an unused import or a stray warning.</strong> Compilation runs with
 *       {@code -Xlint:all} and {@code -Werror} at release 25. One unused import is a build failure, not a
 *       warning.</li>
 *   <li><strong>The context fails to refresh reporting a schema mismatch.</strong> {@code ddl-auto} is
 *       {@code validate}, and Hibernate 6 compares JDBC type codes, so a {@code Long} over
 *       {@code NUMERIC(11)} can fail where {@code BIGINT} passes. The fix is upstream in
 *       {@code V1__create_schema.sql} or in the entity. Never widen a column to make a message go away and
 *       never patch this test to work around it.</li>
 *   <li><strong>Someone asserts that the account-based finder returns one row.</strong> They read
 *       {@code UNIQUE} on {@code app/catlg/LISTCAT.txt:284} as key uniqueness. It is the dataset-name
 *       attribute; :285 is the line that governs, and it says {@code NONUNIQKEY}.</li>
 *   <li><strong>Someone "corrects" {@code card_expiraion_date}.</strong> The missing second {@code T} is in
 *       the frozen source at {@code app/cpy/CVACT02Y.cpy:L9}, and the same misspelling appears at
 *       {@code app/cpy/CVACT01Y.cpy:L11}. Because {@code ddl-auto} is {@code validate} in every profile, a
 *       corrected spelling does not fail one assertion - it fails context refresh for the whole tier.</li>
 *   <li><strong>Someone types the finder as {@code Optional<Card>} or adds a card-verification
 *       constraint.</strong> Neither exists; both are Blockers.</li>
 *   <li><strong>Someone runs an overpunch decoder over {@code carddata.txt}.</strong> It has no signed
 *       numeric field and contains neither {@code &#123;} nor {@code &#125;}. Overpunch decoding belongs to
 *       {@code acctdata}, the {@code custdata} money fields, {@code dailytran}, {@code discgrp} and
 *       {@code tcatbal}.</li>
 *   <li><strong>A fixture name is misspelled.</strong> The base class's reader rejects an uncatalogued name
 *       loudly, by name, rather than returning an empty list that would surface later as a confusing null
 *       dereference.</li>
 *   <li><strong>A whitespace-tidying tool truncates a fixture.</strong> Trailing spaces are data in a
 *       fixed-width record: trimming collapses {@code carddata.txt} from 150 columns to 91 and every
 *       position-based read after the first short row is then wrong. Never copy, trim, normalise or edit
 *       either fixture.</li>
 *   <li><strong>An assertion fails and prints a card number.</strong> It should not be possible, and if it
 *       becomes possible it is a defect in the assertion, not in the code under test. Every assertion here
 *       that touches a card number, a card-verification value or an embossed name reduces it to a boolean or
 *       a count first, so a failure prints only a locator-bearing description. That is the same reason
 *       {@link Card#toString()} omits all three.</li>
 * </ul>
 *
 * @see CardRepository
 * @see Card
 * @see AbstractRepositoryIntegrationTest
 */
@DisplayName("CardRepository against PostgreSQL 16 - the NON-UNIQUE alternate index and the card contract")
class CardRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test, the eleven-interface tier's replacement for the {@code CARDDAT} file. */
    @Autowired
    private CardRepository cardRepository;

    /**
     * The number of rows {@code V3__seed_data.sql} loads into {@code card} from
     * {@code app/data/ASCII/carddata.txt}, which is 7,550 bytes of 150-byte records.
     */
    private static final long SEEDED_ROW_COUNT = 50L;

    /**
     * A synthetic three-character verification value whose leading zero is the point of it: that is the shape
     * eight of the fifty fixture rows carry and the shape a numeric column would corrupt. It is not a value
     * taken from the fixture, so no seeded datum reaches an assertion message or a failed statement's text.
     */
    private static final String SYNTHETIC_VERIFICATION_VALUE = "007";

    /**
     * Used only for catalogue metadata queries and for the one insert that has to bypass the entity.
     *
     * <p>Every statement issued through it below is parameterised: no value is ever concatenated into SQL,
     * which is the same control that keeps shell injection out of a shell command.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Returns the fifty frozen card records, each exactly as stored and 150 characters wide.
     *
     * @return the records of {@code app/data/ASCII/carddata.txt}, untrimmed, in file order
     */
    private List<String> cardFixture() {
        return readFixture("carddata.txt");
    }

    /**
     * Returns the card number of the first frozen card record, columns 1 to 16.
     *
     * <p>It is read from the fixture rather than typed in so that no card number literal exists anywhere in
     * this file. The value is used only as a lookup key and is never placed in a message.
     *
     * @return the sixteen-character primary key of the first seeded card
     */
    private String seededCardNumber() {
        return cardFixture().get(0).substring(0, 16);
    }

    /**
     * Loads the first seeded card through the repository.
     *
     * @return the managed {@link Card} whose key is {@link #seededCardNumber()}
     * @throws java.util.NoSuchElementException if the seed did not load, which would mean {@code V3} failed
     */
    private Card seededCard() {
        return cardRepository.findById(seededCardNumber()).orElseThrow();
    }

    /**
     * Returns the account identifier the first frozen card record names, columns 17 to 27.
     *
     * <p>It is read from the fixture rather than from the database so that the tests which only need an
     * account known to own a card do not have to load a row of personal-adjacent data to get one.
     *
     * @return the owning account identifier of the first seeded card
     */
    private long seededAccountId() {
        return Long.parseLong(cardFixture().get(0).substring(16, 27));
    }

    /**
     * Builds a synthetic sixteen-character key that cannot collide with the seeded fifty.
     *
     * <p>It is assembled from a repeated digit rather than written out, so this file contains no sixteen-digit
     * literal that could be mistaken for a real card number. The seeded values run from a key beginning
     * {@code 05} to one beginning {@code 98}, so an all-nines key is outside the seeded set; the tests that
     * use it assert that emptiness first rather than assuming it.
     *
     * @return a sixteen-character key guaranteed absent from the seed
     */
    private String syntheticCardNumber() {
        return "9".repeat(16);
    }

    /**
     * An account identifier deliberately outside the seeded range of 1 to 50.
     *
     * <p>It is the largest value {@code CARD-ACCT-ID PIC 9(11)} can represent, so it passes the entity's own
     * range guard and reaches the database - which is what makes it usable both as an account that owns no
     * card and as a foreign-key violation.
     *
     * @return an eleven-digit account identifier that is not present in {@code account}
     */
    private long unseededAccountId() {
        return 99_999_999_999L;
    }

    /**
     * Reports whether the given values are in non-descending order.
     *
     * <p>It exists so that ordering can be asserted as a boolean. The AssertJ collection forms that check
     * sortedness print the whole collection when they fail, and these collections hold card numbers, so a
     * failing ordering assertion would write card numbers into the build log.
     *
     * @param values the values to inspect, in the order the query returned them; must not be {@code null}
     * @return {@code true} if every element is greater than or equal to its predecessor, including when the
     *         list is empty or holds one element
     */
    private boolean isAscending(List<String> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks a cause chain to its end.
     *
     * @param thrown the exception to walk; must not be {@code null}
     * @return the deepest cause, or {@code thrown} itself when it has none
     */
    private Throwable rootCauseOf(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Reports whether any message in a cause chain names the given database object.
     *
     * <p>The match is deliberately reduced to a boolean rather than performed with a message assertion.
     * Hibernate renders bound literals into its exception text, so a card number and an embossed name appear
     * verbatim in the messages these tests provoke, and the optimistic-lock message carries the card number
     * as the entity identifier. Asserting on the message directly would print all of it on failure.
     *
     * @param thrown the exception whose chain is searched; must not be {@code null}
     * @param objectName the constraint or index name expected to appear; must not be {@code null}
     * @return {@code true} if {@code objectName} occurs in the message of {@code thrown} or of any of its
     *         causes
     */
    private boolean chainNames(Throwable thrown, String objectName) {
        Throwable current = thrown;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(objectName)) {
                return true;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return false;
    }

    /**
     * Reports whether a cause chain preserves a distinct root cause rather than swallowing it.
     *
     * @param thrown the exception to inspect; must not be {@code null}
     * @return {@code true} if {@code thrown} has at least one cause, that cause carries a message, and the
     *         deepest cause is not {@code thrown} itself
     */
    private boolean preservesRootCause(Throwable thrown) {
        Throwable root = rootCauseOf(thrown);
        return thrown.getCause() != null && root != thrown && root.getMessage() != null
                && !root.getMessage().isBlank();
    }

    /**
     * Counts the rows currently in {@code card} through the repository.
     *
     * @return the row count
     */
    private long cardCount() {
        return cardRepository.count();
    }

    /**
     * Builds a card that is valid in every respect, for a test to persist as-is or to then make invalid.
     *
     * <p><strong>Its embossed name and expiry date are deliberately synthetic rather than copied from a
     * seeded row, and that choice is load-bearing.</strong> Every row this file writes may end up inside a
     * failed statement, and when a statement fails the persistence provider logs it at error level with the
     * bound literals rendered into the text - which would put a seeded holder name, and the seeded card
     * number alongside it, into the build log. Feeding these writes values that are not personal data
     * removes the exposure at its source rather than relying on a downstream masking rule to catch it. The
     * name is obviously not a person's; the expiry date is a legal ten-character value, and it need be
     * nothing more, because no range check, no calendar check and no not-expired rule exists in the source
     * or in the schema.
     *
     * @param cardNumber the sixteen-character key to use; must not be {@code null}
     * @param accountId the owning account identifier
     * @return a transient {@link Card} whose every column satisfies the schema
     */
    private Card newCard(String cardNumber, long accountId) {
        return new Card(cardNumber, accountId, SYNTHETIC_VERIFICATION_VALUE,
                "CARDDEMO SYNTHETIC HOLDER", "2027-01-31", "Y");
    }

    /**
     * Persists one valid synthetic card and detaches it, so that later statements act on a row that carries
     * no personal data.
     *
     * @param cardNumber the sixteen-character key to use; must not be {@code null}
     * @param accountId the owning account identifier, which must name a seeded account or the foreign key
     *                  will refuse the insert
     */
    private void persistSyntheticCard(String cardNumber, long accountId) {
        cardRepository.saveAndFlush(newCard(cardNumber, accountId));
        flushAndClear();
    }

    /**
     * Asserts that a provoked write failed in the expected way, without ever printing the failure text.
     *
     * <p>Every check is reduced to a boolean or to a class name first. Asserting the exception object or its
     * message directly would be simpler and is not an option here: the provider renders bound literals into
     * its message, so the duplicate-key, check-constraint and foreign-key failures all carry a card number
     * and an embossed name verbatim, and the optimistic-lock failure carries the card number as the entity
     * identifier. A failing assertion would then write them into the build log, which Clause D forbids in
     * tests as firmly as anywhere else.
     *
     * <p>All three properties are asserted, not just the type: the exception is of the expected type, its
     * cause chain names the specific database object that refused the write, and a distinct root cause is
     * preserved rather than swallowed.
     *
     * @param thrown the exception captured from the write; may be {@code null}, which fails the first
     *               assertion with a description rather than a null-pointer failure later
     * @param expectedType the type the write is required to fail with; must not be {@code null}
     * @param objectName the constraint, index or diagnostic phrase the failure must name; must not be
     *                   {@code null}
     * @param evidence the source-locator description explaining why the write must fail; must not be
     *                 {@code null}
     */
    private void assertWriteRefused(Throwable thrown, Class<? extends Throwable> expectedType,
            String objectName, String evidence) {
        assertThat(thrown)
                .as("the write must be refused. %s", evidence)
                .isNotNull();
        assertThat(expectedType.isInstance(thrown))
                .as("the refusal must surface as %s, but the actual type was %s. %s",
                        expectedType.getSimpleName(), thrown.getClass().getName(), evidence)
                .isTrue();
        assertThat(chainNames(thrown, objectName))
                .as("the failure must name '%s' somewhere in its cause chain, so a caller can tell which "
                        + "rule refused the write. The match is a boolean because the message embeds bound "
                        + "values. %s", objectName, evidence)
                .isTrue();
        assertThat(preservesRootCause(thrown))
                .as("a distinct root cause carrying a message must be preserved rather than swallowed. %s",
                        evidence)
                .isTrue();
    }

    /**
     * The reason this file exists: {@code CARDDATA.VSAM.AIX} is a non-unique alternate index, and the schema
     * must reproduce that faithfully.
     */
    @Nested
    @DisplayName("CARDDATA.VSAM.AIX is NON-UNIQUE - LISTCAT.txt:285 NONUNIQKEY, not :284's dataset UNIQUE")
    class AlternateIndexIsNonUnique {

        @Test
        @DisplayName("a second card on an account that already has one makes the finder return two rows")
        void theAccountFinderIsMultiValued() {
            long accountId = seededAccountId();
            long rowsBefore = cardCount();

            Page<Card> before = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    accountId, PageRequest.of(0, 7));
            assertThat(before.getTotalElements())
                    .as("account %d holds exactly one seeded card; that is a fixture property of "
                            + "app/data/ASCII/carddata.txt, not a schema constraint", accountId)
                    .isEqualTo(1L);

            persistSyntheticCard(syntheticCardNumber(), accountId);

            Page<Card> after = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    accountId, PageRequest.of(0, 7));

            assertThat(after.getTotalElements())
                    .as("app/catlg/LISTCAT.txt:285 SPANNED NONUNIQKEY makes CARDDATA.VSAM.AIX non-unique, "
                            + "so account %d must be able to hold more than one card. The UNIQUE token on "
                            + ":284 is the dataset-name attribute and does NOT govern key uniqueness. "
                            + "app/jcl/CARDFILE.jcl:86 NONUNIQUEKEY says the same in words, and "
                            + "app/cbl/COCRDLIC.cbl pages 7 rows (:176-178) because one account holds many",
                            accountId)
                    .isGreaterThanOrEqualTo(2L);
            assertThat(after.getContent())
                    .as("both rows for account %d must come back on the first page of the legacy window",
                            accountId)
                    .hasSize(2);
            assertThat(cardCount())
                    .as("the inserted row is the only new one; it is discarded when this test's transaction "
                            + "rolls back, which is why no @Sql, TRUNCATE or deleteAll is used")
                    .isEqualTo(rowsBefore + 1L);
        }

        @Test
        @DisplayName("the multi-valued result comes back in ascending card-number order")
        void theAccountFinderOrdersByCardNumberAscending() {
            long accountId = seededAccountId();
            persistSyntheticCard(syntheticCardNumber(), accountId);

            List<String> keys = new ArrayList<>();
            for (Card card : cardRepository.findByAccountIdOrderByCardNumberAsc(
                    accountId, PageRequest.of(0, 7))) {
                keys.add(card.getCardNumber());
            }

            assertThat(keys)
                    .as("account %d must now report two cards", accountId)
                    .hasSize(2);
            assertThat(isAscending(keys))
                    .as("findByAccountIdOrderByCardNumberAsc names its ordering in the method name, so the "
                            + "browse order of the CARDAIX path (app/csd/CARDDEMO.CSD:13-14) is guaranteed "
                            + "by the interface. Keys are compared as a boolean so a failure cannot print "
                            + "them")
                    .isTrue();
        }

        @Test
        @DisplayName("the index on card(card_acct_id) exists and indisunique is FALSE")
        void theIndexOverTheAlternateKeyIsNotUnique() {
            Boolean unique = jdbcTemplate.queryForObject(
                    "SELECT i.indisunique FROM pg_index i "
                            + "JOIN pg_class c ON c.oid = i.indexrelid "
                            + "JOIN pg_class t ON t.oid = i.indrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? AND i.indisprimary = ? "
                            + "AND pg_get_indexdef(i.indexrelid) LIKE ?",
                    Boolean.class, "card", Boolean.FALSE, "%(card_acct_id)");

            assertThat(unique)
                    .as("exactly one non-primary index must cover card(card_acct_id): it replaces "
                            + "CARDDATA.VSAM.AIX, whose KEYLEN 11 sits at AXRKP 16 zero-based, which is "
                            + "record byte 17 one-based (app/catlg/LISTCAT.txt:281-283)")
                    .isNotNull();
            assertThat(unique)
                    .as("app/catlg/LISTCAT.txt:285 NONUNIQKEY and app/jcl/CARDFILE.jcl:86 NONUNIQUEKEY both "
                            + "make this key non-unique; a unique index here would reject the second card "
                            + "the browse in app/cbl/COCRDLIC.cbl:1158 exists to page through")
                    .isFalse();
        }

        @Test
        @DisplayName("no unique index exists on card other than the primary key")
        void theOnlyUniqueIndexOnCardIsThePrimaryKey() {
            Long nonPrimaryUnique = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_index i "
                            + "JOIN pg_class t ON t.oid = i.indrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? "
                            + "AND i.indisunique = ? AND i.indisprimary = ?",
                    Long.class, "card", Boolean.TRUE, Boolean.FALSE);

            assertThat(nonPrimaryUnique)
                    .as("V2__create_indexes.sql issues three plain CREATE INDEX statements and no CREATE "
                            + "UNIQUE INDEX at all, so the only unique index on card must be pk_card")
                    .isZero();

            Long primary = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_index i "
                            + "JOIN pg_class t ON t.oid = i.indrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? "
                            + "AND i.indisunique = ? AND i.indisprimary = ?",
                    Long.class, "card", Boolean.TRUE, Boolean.TRUE);

            assertThat(primary)
                    .as("the base cluster at app/catlg/LISTCAT.txt:202-204 carries no NONUNIQKEY, so the "
                            + "16-byte card number IS a unique key even though the 11-byte alternate key is "
                            + "not")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the index carries the name V2 gave it, and there is no fourth index on card")
        void theIndexIsNamedAndCountedExactly() {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT c.relname FROM pg_index i "
                            + "JOIN pg_class c ON c.oid = i.indexrelid "
                            + "JOIN pg_class t ON t.oid = i.indrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? ORDER BY c.relname",
                    String.class, "card");

            assertThat(names)
                    .as("card carries the primary-key index and the one alternate-key index and nothing "
                            + "else; V2__create_indexes.sql creates exactly three indexes across three "
                            + "different tables and a fourth is forbidden")
                    .containsExactly("idx_card_acct_id", "pk_card");
        }

        @Test
        @DisplayName("an account with no cards yields an empty page, not null and not an exception")
        void anAccountWithNoCardsYieldsAnEmptyPage() {
            Page<Card> page = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    unseededAccountId(), PageRequest.of(0, 7));

            assertThat(page)
                    .as("a paged finder returns an empty page rather than null; account %d is outside the "
                            + "seeded range of 1 to 50 and therefore owns no card",
                            unseededAccountId())
                    .isNotNull();
            assertThat(page.isEmpty())
                    .as("the browse equivalent of an immediate end-of-file is an empty page, never an "
                            + "exception")
                    .isTrue();
            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getContent()).isEmpty();
        }

        @Test
        @DisplayName("the legacy card window of seven bounds the page, and the second page is empty")
        void theLegacyWindowOfSevenBoundsThePage() {
            long accountId = seededAccountId();
            persistSyntheticCard(syntheticCardNumber(), accountId);

            Page<Card> first = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    accountId, PageRequest.of(0, 7));
            Page<Card> second = cardRepository.findByAccountIdOrderByCardNumberAsc(
                    accountId, PageRequest.of(1, 7));

            assertThat(first.getSize())
                    .as("the window size comes from the caller. Production carries the legacy value in "
                            + "carddemo.pagination.card-list-page-size, whose origin is "
                            + "app/cbl/COCRDLIC.cbl:176-178 WS-MAX-SCREEN-LINES VALUE 7; the repository "
                            + "itself has no notion of a default page size and none is asserted")
                    .isEqualTo(7);
            assertThat(first.getContent().size())
                    .as("two rows fit inside a window of seven, so the first page holds them both")
                    .isEqualTo(2);
            assertThat(first.hasNext())
                    .as("two rows inside a window of seven leave no next page")
                    .isFalse();
            assertThat(second.getContent())
                    .as("a page past the end is empty rather than an error, which is how a browse stops")
                    .isEmpty();
        }

        @Test
        @DisplayName("the fixture's one-card-per-account shape is a fixture property, never an invariant")
        void theOneToOneShapeIsOnlyAFixtureProperty() {
            List<String> records = cardFixture();
            Set<String> accountIds = new LinkedHashSet<>();
            for (String record : records) {
                accountIds.add(record.substring(16, 27));
            }

            assertThat(records)
                    .as("app/data/ASCII/carddata.txt carries fifty records, corroborating REC-TOTAL 50 on "
                            + "the alternate index at app/catlg/LISTCAT.txt:287")
                    .hasSize(50);
            assertThat(accountIds)
                    .as("FIXTURE PROPERTY ONLY: the fifty seeded cards happen to name fifty distinct "
                            + "account identifiers, so the seeded data looks one-to-one. It is NOT a schema "
                            + "constraint and must never be asserted as one - NONUNIQKEY at "
                            + "app/catlg/LISTCAT.txt:285 permits many cards per account, and "
                            + "theAccountFinderIsMultiValued proves this schema permits it")
                    .hasSize(50);
        }
    }

    /**
     * The seed {@code V3} wrote, the round trip through the sixteen-byte key, and corroboration against the
     * frozen fixtures.
     */
    @Nested
    @DisplayName("The seeded fifty rows, the keyed round trip and the frozen fixtures")
    class SeedAndRoundTrip {

        @Test
        @DisplayName("V3 seeded exactly the fifty rows the fixture carries")
        void theSeedIsExactlyFiftyRows() {
            assertThat(cardCount())
                    .as("V3__seed_data.sql loads app/data/ASCII/carddata.txt, which is 7550 bytes of fifty "
                            + "150-byte records plus fifty line feeds; app/catlg/LISTCAT.txt:287 records "
                            + "REC-TOTAL 50 for the alternate index over the same cluster")
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("the sixteen-character key round-trips every mapped column of the first seeded card")
        void theKeyedReadReturnsTheMappedColumns() {
            Optional<Card> found = cardRepository.findById(seededCardNumber());

            assertThat(found)
                    .as("the first record of app/data/ASCII/carddata.txt must resolve through pk_card; the "
                            + "key is read from the fixture and never named in a message")
                    .isPresent();

            Card card = found.orElseThrow();
            assertThat(card.getAccountId())
                    .as("CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6, columns 17-27 of the first "
                            + "fixture record, mapped as a plain scalar Long over NUMERIC(11) and never as "
                            + "an association")
                    .isEqualTo(50L);
            assertThat(card.getExpiraionDate())
                    .as("CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9, columns 81-90; the "
                            + "missing second T is the frozen source spelling and is preserved")
                    .isEqualTo("2023-03-09");
            assertThat(card.getActiveStatus())
                    .as("CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10, column 91; all fifty "
                            + "fixture rows carry Y")
                    .isEqualTo("Y");
            assertThat(card.getVersion())
                    .as("V3 seeds the optimistic-lock counter at zero on all four versioned tables")
                    .isEqualTo(0L);
            assertThat(card.getCardNumber().length())
                    .as("CARD-NUM PIC X(16) over card_num CHAR(16); the width is asserted, the value is not "
                            + "printed")
                    .isEqualTo(16);
            assertThat(card.getEmbossedName().length())
                    .as("PostgreSQL blank-pads character(50), so CARD-EMBOSSED-NAME always reads back at "
                            + "its declared width. This file compares against the padded form and never "
                            + "trims; the value itself is personal-adjacent and is never asserted or logged")
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("an absent sixteen-character key resolves to an empty Optional rather than throwing")
        void anAbsentKeyResolvesToEmpty() {
            Optional<Card> found = cardRepository.findById(syntheticCardNumber());

            assertThat(found)
                    .as("a keyed read that finds nothing is the file status 23 path, which maps to an empty "
                            + "Optional here and to RecordNotFoundException only where a caller treats "
                            + "absence as an error; the synthetic all-nines key is outside the seeded set")
                    .isEmpty();
        }

        @Test
        @DisplayName("the frozen fixture is fifty records of exactly 150 characters, untrimmed")
        void theFixtureGeometryIsIntact() {
            List<String> records = cardFixture();

            assertThat(records)
                    .as("app/data/ASCII/carddata.txt census: fifty records")
                    .hasSize(50);

            Set<Integer> widths = new LinkedHashSet<>();
            for (String record : records) {
                widths.add(record.length());
            }

            assertThat(widths)
                    .as("every record is exactly 150 characters, matching AVGLRECL 150 at "
                            + "app/catlg/LISTCAT.txt:202 and RECORDSIZE(150,150) at app/jcl/CARDFILE.jcl:88. "
                            + "Trailing spaces are data: a whitespace trim would collapse this file to 91 "
                            + "columns, the width of the declared fields without FILLER X(59), and every "
                            + "position-based read after the first short row would then be wrong")
                    .containsExactly(150);
        }

        @Test
        @DisplayName("the fixture's key column holds exactly the fifty card_num values in the table")
        void theFixtureKeysAreTheTableKeys() {
            Set<String> fixtureKeys = new LinkedHashSet<>();
            for (String record : cardFixture()) {
                fixtureKeys.add(record.substring(0, 16));
            }
            Set<String> tableKeys = new LinkedHashSet<>(
                    jdbcTemplate.queryForList("SELECT card_num FROM card", String.class));

            assertThat(fixtureKeys)
                    .as("columns 1-16 of app/data/ASCII/carddata.txt are CARD-NUM PIC X(16), fifty distinct "
                            + "values")
                    .hasSize(50);
            assertThat(tableKeys)
                    .as("card_num CHAR(16) is exactly sixteen characters wide, so no blank padding "
                            + "distinguishes the stored value from the fixture prefix")
                    .hasSize(50);

            Set<String> onlyInFixture = new LinkedHashSet<>(fixtureKeys);
            onlyInFixture.removeAll(tableKeys);
            Set<String> onlyInTable = new LinkedHashSet<>(tableKeys);
            onlyInTable.removeAll(fixtureKeys);

            assertThat(onlyInFixture.size())
                    .as("every fixture key must have been seeded; the count of missing keys is asserted "
                            + "rather than the keys themselves so that a failure cannot print a card number")
                    .isZero();
            assertThat(onlyInTable.size())
                    .as("no key may exist in the table that the fixture does not carry")
                    .isZero();
        }

        @Test
        @DisplayName("every fixture account id is a seeded account, which is why the foreign key is satisfiable")
        void theFixtureAccountIdsAreAllSeededAccounts() {
            Set<Long> cardAccountIds = new LinkedHashSet<>();
            for (String record : cardFixture()) {
                cardAccountIds.add(Long.parseLong(record.substring(16, 27)));
            }
            Set<Long> fixtureAccountIds = new LinkedHashSet<>();
            for (String record : readFixture("acctdata.txt")) {
                fixtureAccountIds.add(Long.parseLong(record.substring(0, 11)));
            }

            assertThat(fixtureAccountIds)
                    .as("app/data/ASCII/acctdata.txt carries fifty records of 300 bytes whose columns 1-11 "
                            + "are ACCT-ID PIC 9(11), matching KEYLEN 11 for ACCTDATA")
                    .hasSize(50);
            assertThat(fixtureAccountIds)
                    .as("the seeded accounts are the contiguous range 1 to 50")
                    .contains(1L, 50L);
            assertThat(fixtureAccountIds.containsAll(cardAccountIds))
                    .as("columns 17-27 of every carddata record - CARD-ACCT-ID, the alternate key at AXRKP "
                            + "16 zero-based, record byte 17 one-based - must name an account that "
                            + "acctdata.txt also carries. That is the fixture-level proof that "
                            + "fk01_card_account is satisfiable by the seed rather than merely declared")
                    .isTrue();
        }
    }

    /**
     * The column set itself: the preserved misspelling, the text expiry date and the deliberately absent
     * card-verification column.
     */
    @Nested
    @DisplayName("The column contract - the preserved misspelling and the confined card-verification field")
    class ColumnContract {

        @Test
        @DisplayName("the column is card_expiraion_date, and card_expiration_date does not exist")
        void theMisspelledColumnIsTheRealOne() {
            Long misspelled = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Long.class, "card", "card_expiraion_date");
            Long corrected = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Long.class, "card", "card_expiration_date");

            assertThat(misspelled)
                    .as("app/cpy/CVACT02Y.cpy:L9 spells the field CARD-EXPIRAION-DATE, without the second "
                            + "T, and the schema preserves that verbatim. The same misspelling appears at "
                            + "app/cpy/CVACT01Y.cpy:L11 as ACCT-EXPIRAION-DATE")
                    .isEqualTo(1L);
            assertThat(corrected)
                    .as("a corrected spelling must not exist. Because ddl-auto is validate in every profile, "
                            + "correcting it does not fail one assertion - it fails application-context "
                            + "refresh for this whole tier")
                    .isZero();
        }

        @Test
        @DisplayName("the expiry date is fixed-width text, never a date type, and never parsed into one")
        void theExpiryDateIsText() {
            String dataType = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    String.class, "card", "card_expiraion_date");
            Integer width = jdbcTemplate.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Integer.class, "card", "card_expiraion_date");

            assertThat(dataType)
                    .as("CARD-EXPIRAION-DATE is PIC X(10), a character field, so it maps to character(10) "
                            + "and to a Java String. It is never a date type and is never parsed into "
                            + "LocalDate, consistent with app/cbl/COACTUPC.cbl, which compares dates as "
                            + "substrings at offsets 1, 6 and 9 rather than as whole values")
                    .isEqualTo("character");
            assertThat(width)
                    .as("ten characters, columns 81-90 of the 150-byte record")
                    .isEqualTo(10);

            String stored = seededCard().getExpiraionDate();
            assertThat(stored)
                    .as("the stored value is a String in yyyy-MM-dd shape. No range check, no not-expired "
                            + "check and no date arithmetic is asserted anywhere: the source carries none, "
                            + "and any such assertion would depend on the wall clock this tier deliberately "
                            + "does not read")
                    .hasSize(10)
                    .matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("card_cvv_cd is CHAR(3) NOT NULL and every seeded row round-trips its three bytes")
        void theCardVerificationColumnIsFixedWidthAndPopulated() {
            Map<String, Object> declared = jdbcTemplate.queryForMap(
                    "SELECT data_type, character_maximum_length, is_nullable "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    "card", "card_cvv_cd");

            assertThat(declared.get("data_type"))
                    .as("CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 sits inside the authoritative "
                            + "150-byte record, so the column exists. It is CHARACTER, not numeric: 8 of the "
                            + "50 fixture rows lead with a zero and a numeric column would store them one "
                            + "digit short, which no assertion elsewhere would catch")
                    .isEqualTo("character");
            assertThat(declared.get("character_maximum_length"))
                    .as("exactly three characters, per the picture clause")
                    .isEqualTo(Integer.valueOf(3));
            assertThat(declared.get("is_nullable"))
                    .as("every column on this table is NOT NULL, this one included")
                    .isEqualTo("NO");

            Long populated = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM card WHERE card_cvv_cd ~ ?", Long.class, "^[0-9]{3}$");
            assertThat(populated)
                    .as("V3__seed_data.sql loads bytes 28-30 of all fifty rows of "
                            + "app/data/ASCII/carddata.txt, and PIC 9(03) means all three characters are "
                            + "digits in every row. The values themselves are never selected into an "
                            + "assertion message: the shape is asserted, not the datum")
                    .isEqualTo(SEEDED_ROW_COUNT);

            Long leadingZero = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM card WHERE card_cvv_cd LIKE ?", Long.class, "0%");
            assertThat(leadingZero)
                    .as("and the leading zeros survived the load, which is the round trip the CHAR(3) "
                            + "declaration exists to guarantee - eight rows, exactly as the fixture has")
                    .isEqualTo(8L);
        }

        @Test
        @DisplayName("the entity persists the verification value without publishing any read path for it")
        void theVerificationValueIsWrittenAndComparedButNeverReadBack() {
            persistSyntheticCard(syntheticCardNumber(), seededAccountId());

            Card reloaded = cardRepository.findById(syntheticCardNumber()).orElseThrow();

            assertThat(reloaded.matchesVerificationValue(SYNTHETIC_VERIFICATION_VALUE))
                    .as("the three characters survive the write and the read unchanged, leading zero "
                            + "included. This is asserted through the boolean comparison because the entity "
                            + "publishes no accessor that returns the value - the round trip is provable "
                            + "without the value ever being surrendered")
                    .isTrue();
            assertThat(reloaded.matchesVerificationValue("7"))
                    .as("and the numerically equal shorter form does not match, which is what proves the "
                            + "column stored characters rather than a parsed number")
                    .isFalse();

            assertThat(Card.class.getMethods())
                    .as("no bean-style accessor names the field, so no serializer, reflective mapper or "
                            + "response body can obtain it from a loaded entity")
                    .noneMatch(method -> {
                        String lower = method.getName().toLowerCase(Locale.ROOT);
                        return lower.equals("getcvvcode") || lower.equals("setcvvcode");
                    });
            assertThat(reloaded.toString().toLowerCase(Locale.ROOT))
                    .as("and the diagnostic rendering does not name or contain it")
                    .doesNotContain("cvv")
                    .doesNotContain(SYNTHETIC_VERIFICATION_VALUE);
        }

        @Test
        @DisplayName("no card-verification validation exists anywhere, and the fixture bytes prove why")
        void noCardVerificationValidationExists() {
            Long constraintsNamingIt = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_constraint "
                            + "WHERE conrelid = ?::regclass AND pg_get_constraintdef(oid) LIKE ?",
                    Long.class, "card", "%cvv%");

            assertThat(constraintsNamingIt)
                    .as("there is ZERO card-verification validation anywhere in app/cbl - no 88-level, no "
                            + "range test, no digits test - so no constraint may mention it. Adding one "
                            + "would invent a rule the source does not have")
                    .isZero();

            int leadingZeroValues = 0;
            for (String record : cardFixture()) {
                if (record.charAt(27) == '0') {
                    leadingZeroValues++;
                }
            }

            assertThat(leadingZeroValues)
                    .as("columns 28-30 of app/data/ASCII/carddata.txt carry the three CARD-CVV-CD bytes and "
                            + "eight of the fifty begin with a zero, which is why the column is CHAR(3) over "
                            + "a Java String: a numeric mapping would have destroyed the leading zero on "
                            + "those eight rows and nothing would have reported it")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the table declares exactly the seven modelled columns, every one NOT NULL")
        void theColumnSetIsExactlyTheModelledSet() {
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? "
                            + "ORDER BY ordinal_position",
                    String.class, "card");

            assertThat(columns)
                    .as("91 modelled bytes as 16 + 11 + 3 + 50 + 10 + 1, plus the version counter. Only "
                            + "FILLER X(59) at app/cpy/CVACT02Y.cpy:L11 is unmodelled, and it is what makes "
                            + "the frozen record up to 150")
                    .containsExactly("card_num", "card_acct_id", "card_cvv_cd", "card_embossed_name",
                            "card_expiraion_date", "card_active_status", "version");

            Long nullable = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND is_nullable = ?",
                    Long.class, "card", "YES");

            assertThat(nullable)
                    .as("a fixed-width COBOL record has no concept of a null field, so every column of "
                            + "V1__create_schema.sql is NOT NULL")
                    .isZero();
        }

        @Test
        @DisplayName("every declared width matches the PIC clause it came from")
        void theDeclaredWidthsMatchThePicClauses() {
            assertThat(characterWidthOf("card_num"))
                    .as("CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5, bytes 1-16, and KEYLEN 16 at "
                            + "app/catlg/LISTCAT.txt:202 with RKP 0 at :203")
                    .isEqualTo(16);
            assertThat(characterWidthOf("card_embossed_name"))
                    .as("CARD-EMBOSSED-NAME PIC X(50) at app/cpy/CVACT02Y.cpy:L8, bytes 31-80")
                    .isEqualTo(50);
            assertThat(characterWidthOf("card_expiraion_date"))
                    .as("CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9, bytes 81-90")
                    .isEqualTo(10);
            assertThat(characterWidthOf("card_active_status"))
                    .as("CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10, byte 91")
                    .isEqualTo(1);

            Integer precision = jdbcTemplate.queryForObject(
                    "SELECT numeric_precision FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Integer.class, "card", "card_acct_id");

            assertThat(precision)
                    .as("CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6, bytes 17-27, as NUMERIC(11) - "
                            + "eleven digits, which is also KEYLEN 11 on the alternate index at "
                            + "app/catlg/LISTCAT.txt:281")
                    .isEqualTo(11);
        }

        /**
         * Reads one column's declared character width from the catalogue.
         *
         * @param columnName the column to inspect; must be a column of {@code card}
         * @return the declared {@code character_maximum_length}, or {@code null} for a non-character column
         */
        private Integer characterWidthOf(String columnName) {
            return jdbcTemplate.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Integer.class, "card", columnName);
        }
    }

    /**
     * Hostile input at the persistence boundary: a duplicate key, an out-of-domain status, an orphan account
     * reference and an over-wide value.
     *
     * <p>Each provoked violation is the last database interaction of its test method, because a failed
     * statement aborts the PostgreSQL transaction and anything issued afterwards would fail for the wrong
     * reason. That is also why the accept path and the reject path of the check constraint are two methods
     * rather than one.
     */
    @Nested
    @DisplayName("The constraint contract - pk_card, ck_card_active_status, fk01_card_account and CHAR widths")
    class ConstraintContract {

        @Test
        @DisplayName("card carries exactly one check constraint, one foreign key and one primary key")
        void theConstraintCensusIsExact() {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass ORDER BY conname",
                    String.class, "card");

            assertThat(names)
                    .as("V1__create_schema.sql declares exactly five check constraints across the eleven "
                            + "tables and exactly ten foreign keys; card holds check 2 of 5 and foreign key "
                            + "1 of 10. There is no sixth check and no second key of either kind here")
                    .containsExactly("ck_card_active_status", "fk01_card_account", "pk_card");

            Long checks = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = ?",
                    Long.class, "card", "c");

            assertThat(checks)
                    .as("the only domain rule on card is the yes-or-no status; adding another would invent a "
                            + "rule the source does not carry")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a duplicate card number is rejected, because the base key IS unique")
        void aDuplicateCardNumberIsRejected() {
            long accountId = seededAccountId();
            persistSyntheticCard(syntheticCardNumber(), accountId);

            assertThat(cardRepository.findById(syntheticCardNumber()))
                    .as("the first row under the synthetic key must be present before a second is attempted, "
                            + "so that the refusal below can only be about uniqueness")
                    .isPresent();
            flushAndClear();

            Card duplicate = newCard(syntheticCardNumber(), accountId);
            Throwable thrown = catchThrowable(() -> cardRepository.saveAndFlush(duplicate));

            assertWriteRefused(thrown, DataIntegrityViolationException.class, "pk_card",
                    "The base cluster at app/catlg/LISTCAT.txt:202-204 carries the dataset-name UNIQUE token "
                            + "and no NONUNIQKEY anywhere, so the sixteen-byte card number is a genuinely "
                            + "unique key and pk_card must refuse a second row under it. This is the exact "
                            + "opposite of the eleven-byte alternate key, which IS non-unique per :285; the "
                            + "two must never be confused.");
        }

        @Test
        @DisplayName("both values of the yes-or-no status domain persist")
        void bothLegalStatusValuesPersist() {
            Card card = seededCard();

            card.setActiveStatus("N");
            cardRepository.saveAndFlush(card);
            flushAndClear();
            assertThat(seededCard().getActiveStatus())
                    .as("app/cbl/COCRDUPC.cbl:91 declares 88 FLG-YES-NO-VALID VALUES 'Y', 'N'; the value is "
                            + "moved to that flag at :861 and tested at :863, so both members of the domain "
                            + "must round-trip")
                    .isEqualTo("N");

            Card reloaded = seededCard();
            reloaded.setActiveStatus("Y");
            cardRepository.saveAndFlush(reloaded);
            flushAndClear();
            assertThat(seededCard().getActiveStatus())
                    .as("byte 91 of all fifty rows of app/data/ASCII/carddata.txt is Y, the value the seed "
                            + "loads")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("a status outside the yes-or-no domain is rejected by ck_card_active_status")
        void aThirdStatusValueIsRejected() {
            persistSyntheticCard(syntheticCardNumber(), seededAccountId());
            Card card = cardRepository.findById(syntheticCardNumber()).orElseThrow();
            card.setActiveStatus("X");

            Throwable thrown = catchThrowable(() -> cardRepository.saveAndFlush(card));

            assertWriteRefused(thrown, DataIntegrityViolationException.class, "ck_card_active_status",
                    "The domain is exactly Y and N, from 88 FLG-YES-NO-VALID at app/cbl/COCRDUPC.cbl:91 as "
                            + "applied at :861 and tested at :863, so check 2 of 5 must refuse a third value "
                            + "at the database and not merely inside a Java guard.");
        }

        @Test
        @DisplayName("a card on an account that does not exist is rejected by fk01_card_account")
        void anOrphanAccountReferenceIsRejected() {
            Card orphan = newCard(syntheticCardNumber(), unseededAccountId());

            Throwable thrown = catchThrowable(() -> cardRepository.saveAndFlush(orphan));

            assertWriteRefused(thrown, DataIntegrityViolationException.class, "fk01_card_account",
                    "Foreign key 1 of 10 is card.card_acct_id to account.acct_id. Its evidence is "
                            + "app/cpy/CVACT02Y.cpy:L6, which carries the account key inside the card "
                            + "record, and app/cbl/COACTVWC.cbl:691, which performs the keyed read of the "
                            + "card file by account identifier through the CARDAIX path. Account "
                            + unseededAccountId() + " is outside the seeded range of 1 to 50, so the "
                            + "reference is an orphan and must be refused.");
        }

        @Test
        @DisplayName("an over-wide card number is refused by the entity and again by character(16)")
        void theFixedWidthContractIsEnforcedTwice() {
            long accountId = seededAccountId();
            Card template = newCard(syntheticCardNumber(), accountId);
            String embossedName = template.getEmbossedName();
            String expiraionDate = template.getExpiraionDate();
            String tooWide = "9".repeat(17);

            assertThatIllegalArgumentException()
                    .as("the entity refuses an over-wide value before any statement is sent, naming the "
                            + "originating COBOL field, so a caller learns which fixed-width contract it "
                            + "broke")
                    .isThrownBy(() -> new Card(tooWide, accountId, "007", embossedName, expiraionDate, "Y"))
                    .withMessageContaining("CARD-NUM")
                    .withMessageContaining("16");

            Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                    "INSERT INTO card (card_num, card_acct_id, card_embossed_name, "
                            + "card_expiraion_date, card_active_status, version) VALUES (?, ?, ?, ?, ?, ?)",
                    tooWide, accountId, embossedName, expiraionDate, "Y", 0L));

            assertWriteRefused(thrown, DataIntegrityViolationException.class, "character(16)",
                    "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5 becomes card_num CHAR(16), and the "
                            + "database must enforce that width independently of the entity - a declared "
                            + "width only the entity checks is not a schema contract. The insert is "
                            + "parameterised and deliberately bypasses the entity so that the engine, not "
                            + "the mapping, is what refuses the value.");
        }
    }

    /**
     * The optimistic-lock counter the card update conversation depends on.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl} performs its own field-by-field change-detection comparison, mirroring
     * the account-update pattern, so a version counter is necessary but not sufficient on its own: it detects
     * <em>that</em> a row changed, whereas the source detects <em>which fields</em> changed. This class
     * asserts the store-level guard only; the business-level comparison belongs to
     * {@code com.cardemo.unit.service}. The four legacy {@code DFHRESP(DUPREC)} sites the card corpus
     * carries - {@code app/cbl/COCRDLIC.cbl:1158}, {@code :1209}, {@code :1306} and {@code :1334} - are
     * cited, not asserted, and no test is invented for the literal {@code '22'}, which occurs nowhere in
     * {@code app/cbl}.
     */
    @Nested
    @DisplayName("Optimistic locking - card is one of exactly four tables carrying a version column")
    class OptimisticLocking {

        @Test
        @DisplayName("the version column exists as BIGINT NOT NULL and is one of exactly four")
        void theVersionColumnIsDeclaredAsExpected() {
            String dataType = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    String.class, "card", "version");

            assertThat(dataType)
                    .as("the counter is a plain 64-bit integer, mapped by the entity's @Version property")
                    .isEqualTo("bigint");

            List<String> versioned = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND column_name = ? "
                            + "AND table_name IN (?, ?, ?, ?) ORDER BY table_name",
                    String.class, "version", "account", "card", "customer", "transaction");

            assertThat(versioned)
                    .as("exactly four business tables carry an optimistic-lock counter - account, card, "
                            + "customer and the transaction table, which both V1 and V2 emit double-quoted "
                            + "in lower case")
                    .containsExactly("account", "card", "customer", "transaction");
        }

        @Test
        @DisplayName("the version increments on a real update, observed after a flush and a reload")
        void theVersionIncrementsOnUpdate() {
            Card card = seededCard();
            Long before = card.getVersion();

            assertThat(before)
                    .as("the seed loads every versioned row at zero")
                    .isEqualTo(0L);

            card.setActiveStatus("N");
            cardRepository.saveAndFlush(card);
            flushAndClear();

            assertThat(seededCard().getVersion())
                    .as("the increment is applied by the flush, and clearing the persistence context makes "
                            + "the reload a real round trip to the database rather than an identity-map hit")
                    .isEqualTo(before + 1L);
        }

        @Test
        @DisplayName("a write carrying a stale version fails with an optimistic-locking failure")
        void aStaleVersionWriteIsRejected() {
            long accountId = seededAccountId();
            persistSyntheticCard(syntheticCardNumber(), accountId);

            Card card = cardRepository.findById(syntheticCardNumber()).orElseThrow();
            Long staleVersion = card.getVersion();
            card.setActiveStatus("N");
            cardRepository.saveAndFlush(card);
            flushAndClear();

            assertThat(cardRepository.findById(syntheticCardNumber()).orElseThrow().getVersion())
                    .as("the row must have moved on before a write carrying the earlier version is attempted")
                    .isEqualTo(staleVersion + 1L);

            Card stale = newCard(syntheticCardNumber(), accountId);
            stale.setVersion(staleVersion);

            Throwable thrown = catchThrowable(() -> cardRepository.saveAndFlush(stale));

            assertWriteRefused(thrown, ObjectOptimisticLockingFailureException.class,
                    "Row was updated or deleted by another transaction",
                    "A detached write carrying the version the caller was last shown is exactly the "
                            + "stateless-snapshot situation the card and account update conversations face, "
                            + "and it must be refused rather than silently overwriting the newer row. No "
                            + "second thread and no second connection is used to arrange it, so the outcome "
                            + "is deterministic on every run.");
        }
    }
}
