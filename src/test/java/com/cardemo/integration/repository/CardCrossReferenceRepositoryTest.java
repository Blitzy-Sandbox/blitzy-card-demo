/*
 * ******************************************************************
 * Program     : CardCrossReferenceRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier, Failsafe bound
 * Function    : Proves the CARDXREF.VSAM.KSDS replacement table
 *               card_cross_reference against a real PostgreSQL 16. Asserts
 *               the 36-of-50 byte field contract, the NON-UNIQUE alternate
 *               index at zero-based AXRKP 25 (one-based record byte 26),
 *               both of the table's foreign keys, the deliberate ABSENCE of
 *               a card foreign key, primary-key uniqueness on the base key,
 *               the CHAR(16) width boundary, and the zero-orphan property
 *               of the frozen ASCII fixtures.
 * Source      : app/cpy/CVACT03Y.cpy (XREF-CARD-NUM X(16) + XREF-CUST-ID
 *               9(09) + XREF-ACCT-ID 9(11) = 36 modelled of 50 catalogued
 *               bytes, FILLER X(14) unmodelled),
 *               app/catlg/LISTCAT.txt:365 and :403-:405 (cluster KEYLEN 16
 *               / AVGLRECL 50 / RKP 0, base key unique) and :455,:466,:472,
 *               :480-:488 (AIX KEYLEN 11 / RKP 5 / AXRKP 25 / UPGRADE /
 *               NONUNIQKEY) and :351 (PATH),
 *               app/jcl/XREFFILE.jcl:72-77 (DEFINE ALTERNATEINDEX
 *               KEYS(11,25) NONUNIQUEKEY) and :90-92 (DEFINE PATH),
 *               app/csd/CARDDEMO.CSD:37,:39 (FILE CCXREF) and :63-65
 *               (FILE CXACAIX),
 *               app/cbl/CBTRN02C.cbl:383 (keyed base read, reject 100),
 *               app/cbl/CBACT04C.cbl:393-413 (alternate-index read, abend
 *               on miss), app/cbl/CBACT03C.cbl (read-only reader),
 *               app/cbl/COACTVWC.cbl and app/cbl/COBIL00C.cbl:211
 *               (CXACAIX consumers, cited not asserted),
 *               app/data/ASCII/cardxref.txt (50 x 36 = 1,850 bytes),
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
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;

/**
 * Integration coverage for {@link CardCrossReferenceRepository} and the {@code card_cross_reference} table
 * that replaces the {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} cluster, exercised against a real
 * PostgreSQL 16 supplied by {@link AbstractRepositoryIntegrationTest}.
 *
 * <h2>What it does</h2>
 *
 * <p>The cross reference is the card-to-customer-to-account bridge of the legacy system, and it is the only
 * table in the schema that carries <strong>two</strong> foreign keys while carrying <strong>no</strong>
 * {@code version} column. Those two facts, plus a <em>non-unique</em> alternate key at an offset that differs
 * from the card table's, are what this class exists to pin down. Everything asserted below was verified by
 * direct inspection at {@code 7756d89}; nothing is inferred from prose.
 *
 * <h3>The field contract - 36 modelled bytes inside a 50-byte slot</h3>
 *
 * <p>{@code app/cpy/CVACT03Y.cpy} declares four fields, and the one-based record offsets follow from their
 * PIC clauses:
 *
 * <ul>
 *   <li>{@code XREF-CARD-NUM PIC X(16)} at {@code :5}, bytes <strong>1-16</strong>, becoming
 *       {@code xref_card_num CHAR(16)} and the primary key.</li>
 *   <li>{@code XREF-CUST-ID PIC 9(09)} at {@code :6}, bytes <strong>17-25</strong>, becoming
 *       {@code xref_cust_id NUMERIC(9)} - a plain scalar, never a mapped association.</li>
 *   <li>{@code XREF-ACCT-ID PIC 9(11)} at {@code :7}, bytes <strong>26-36</strong>, becoming
 *       {@code xref_acct_id NUMERIC(11)} - the alternate-index target, and again a plain scalar.</li>
 *   <li>{@code FILLER PIC X(14)} at {@code :8}, bytes <strong>37-50</strong>.</li>
 * </ul>
 *
 * <p>16 + 9 + 11 = <strong>36</strong> modelled bytes, and 36 + 14 = 50, which is the {@code AVGLRECL 50}
 * the catalogue records at {@code app/catlg/LISTCAT.txt:403}. <strong>The 14-byte FILLER is deliberately not
 * modelled as a column</strong>, and the frozen fixture proves the decision rather than merely permitting it:
 * {@code app/data/ASCII/cardxref.txt} is 1,850 bytes for 50 records, which is 50 x 37 once the line feed is
 * counted, so its records are <strong>36 columns wide, not 50</strong>, with a maximum trailing-space run of
 * zero. The filler is simply absent from the data. {@link Geometry#theTableCarriesExactlyThreeBusinessColumns()}
 * and {@link FixtureCorroboration#theFixtureIsFiftyRecordsOfThirtySixColumnsAscending()} assert both halves.
 *
 * <h3>The headline: the alternate key is NON-UNIQUE, at zero-based AXRKP 25</h3>
 *
 * <p>The catalogue's alternate-index block must be read as a whole, because two adjacent lines look
 * contradictory and only one of them speaks to key uniqueness:
 *
 * <ul>
 *   <li>{@code :455} the {@code AIX} entry header, {@code :466} {@code UPGRADE}, {@code :472} its DATA
 *       component, {@code :480} the association back to the AIX, {@code :482} {@code KEYLEN 11}.</li>
 *   <li>{@code :485} {@code RKP 5} - <strong>at :485 and not :483</strong>, because {@code :483-:484} are an
 *       IDCAMS page break inside the block, which is exactly the sort of line-spacing difference that makes a
 *       locator copied from the card AIX wrong here.</li>
 *   <li>{@code :486} <strong>{@code AXRKP 25}</strong>.</li>
 *   <li>{@code :487} {@code ... RECOVERY UNIQUE NOERASE INDEXED ...} - this {@code UNIQUE} is the
 *       <strong>dataset-name</strong> attribute and says nothing whatever about key uniqueness.</li>
 *   <li>{@code :488} {@code ... SPANNED NONUNIQKEY ...} - <strong>this is the token that governs.</strong></li>
 * </ul>
 *
 * <p>Misreading {@code :487} as a uniqueness declaration is the single most costly error available in this
 * file, so the reading is corroborated independently rather than argued: the IDCAMS definition at
 * {@code app/jcl/XREFFILE.jcl:74-75} declares {@code KEYS(11,25)} followed by
 * {@code NONUNIQUEKEY} in as many words. The index is therefore non-unique on two separate authorities, and
 * consequently <strong>nothing here asserts that the account-id finder yields a single row, that any of the
 * three alternate indexes is unique, or that {@code card_cross_reference(xref_acct_id)} carries a unique
 * index</strong>. {@link AlternateKey} instead proves multiplicity <em>positively</em>.
 *
 * <h3>Base-of-offset discipline: zero-based AXRKP against one-based record bytes</h3>
 *
 * <p>{@code AXRKP} is <strong>zero-based</strong> while record-byte prose is <strong>one-based</strong>, so
 * offsets are written throughout as "AXRKP 25 (zero-based) = record byte 26 (one-based)" and never
 * abbreviated to a bare number. The two are consistent twice over: the catalogue's {@code AXRKP 25}, and the
 * copybook arithmetic that puts {@code XREF-ACCT-ID} at bytes 26-36. {@code V1__create_schema.sql:800} states
 * the same equivalence in its own comment.
 *
 * <p><strong>The two account-id alternate keys sit at different offsets and must never be conflated:</strong>
 *
 * <ul>
 *   <li>{@code CARDDATA.VSAM.AIX} - {@code AXRKP 16} ({@code LISTCAT.txt:283}) = record byte 17, where
 *       {@code CVACT02Y.cpy}'s {@code CARD-ACCT-ID} begins.</li>
 *   <li>{@code CARDXREF.VSAM.AIX} - {@code AXRKP 25} ({@code LISTCAT.txt:486}) = record byte 26, where
 *       {@code CVACT03Y.cpy}'s {@code XREF-ACCT-ID} begins.</li>
 * </ul>
 *
 * <p>Because the card index sits at {@code AXRKP 16} and the cross-reference index at {@code AXRKP 25}, a
 * reader carrying one offset across to the other lands inside {@code XREF-CUST-ID} and attributes the
 * alternate key to the wrong field. The catalogue and {@code XREFFILE.jcl} are the authorities this class
 * reads, and every offset here is written with its base.
 *
 * <h3>Base key unique, alternate key not - both halves are asserted</h3>
 *
 * <p>The two are independent and are proved separately. The <em>base</em> cluster at
 * {@code LISTCAT.txt:405} reads {@code SHROPTNS(2,3) RECOVERY UNIQUE ERASE INDEXED ...} with
 * <strong>no {@code NONUNIQKEY} token anywhere</strong>, so its 16-byte key at {@code RKP 0} genuinely is
 * unique, which {@code pk_card_cross_reference} reproduces and
 * {@link Constraints#aDuplicateBaseKeyViolatesThePrimaryKey()} provokes. The <em>alternate</em> key is
 * non-unique per {@code :488}, which {@link AlternateKey} proves by storing a second row against one account.
 * A test that asserted uniqueness on both, or on neither, would be wrong in one direction or the other.
 *
 * <h3>No {@code version} column on this table</h3>
 *
 * <p>{@code version BIGINT NOT NULL} appears on exactly <strong>four</strong> entities - {@code account},
 * {@code card}, {@code customer} and {@code "transaction"} - and {@code card_cross_reference} is
 * <strong>not</strong> one of them. Nothing here asserts optimistic locking, and
 * {@link Geometry#theColumnsAreTheCobolNamesAndNothingElse()} asserts the column's <em>absence</em> instead.
 * An optimistic-lock assertion would not merely fail, it would fail confusingly, because
 * {@code spring.jpa.hibernate.ddl-auto: validate} would already have rejected the mapping at context
 * startup.
 *
 * <h3>Two foreign keys, and the third that deliberately does not exist</h3>
 *
 * <p>{@code V1__create_schema.sql:808-817} declares {@code fk02_xref_customer}
 * ({@code xref_cust_id -> customer.cust_id}) and {@code fk03_xref_account}
 * ({@code xref_acct_id -> account.acct_id}): two of the migration's exactly ten foreign keys, and the only
 * pair on one table. <strong>There is no foreign key from {@code xref_card_num} to {@code card.card_num}</strong>,
 * and that absence is a deliberate schema shape rather than an omission, so it is asserted as a positive
 * behaviour - {@link Constraints#aCardNumberAbsentFromTheCardTableStillPersists()} stores such a row and
 * expects it to succeed.
 *
 * <h3>The zero-orphan fixture property, explicitly labelled as such</h3>
 *
 * <p>Measured across the frozen fixtures rather than assumed: every one of the 50 {@code xref_cust_id}
 * values appears among the 50 customer keys in {@code custdata.txt}, every {@code xref_acct_id} appears among
 * the 50 account keys in {@code acctdata.txt}, and every {@code xref_card_num} appears among the 50 card keys
 * in {@code carddata.txt} - three counts of zero orphans. The 50 account ids are also distinct, so the
 * fixture is effectively one cross reference per account.
 *
 * <p><strong>That one-to-one shape is a FIXTURE PROPERTY, not a schema invariant</strong>, and the
 * distinction is load bearing: the schema permits many cross references per account, which is precisely what
 * {@code NONUNIQKEY} means and what {@link AlternateKey} demonstrates. Every assertion that relies on the
 * fixture shape says so in its own description.
 *
 * <h3>Why the zero-orphan property is worth asserting: reject-code reachability</h3>
 *
 * <p>Recorded here because it explains the assertion above, and tested nowhere in this file. Because the
 * fixtures have no orphans, two of the five reject codes of {@code app/cbl/CBTRN02C.cbl} cannot be reached
 * over the seeded data at all:
 *
 * <table border="1">
 *   <caption>Reject-code reachability over the seeded fixtures</caption>
 *   <tr><th>Code</th><th>Literal description</th><th>Reachable?</th><th>Why</th></tr>
 *   <tr><td>100</td><td>{@code INVALID CARD NUMBER FOUND}</td><td>No</td>
 *       <td>no orphan card number in {@code cardxref.txt}</td></tr>
 *   <tr><td>101</td><td>{@code ACCOUNT RECORD NOT FOUND}</td><td>No</td>
 *       <td>no orphan account id</td></tr>
 *   <tr><td>102</td><td>{@code OVERLIMIT TRANSACTION}</td><td><strong>Yes - the only one</strong></td>
 *       <td>seeded credit limits are exceeded by seeded amounts</td></tr>
 *   <tr><td>103</td><td>{@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}</td><td>No</td>
 *       <td>the earliest seeded expiry is later than the fixtures' processing timestamp</td></tr>
 * </table>
 *
 * <p>The posting job therefore ends with return code 4, which {@code CBTRN02C.cbl:229-231} sets if and only
 * if the reject count exceeds zero. Reaching 100, 101, 103 or the 102-then-103 fall-through needs
 * <em>synthetic</em> rows, so no fixture may be edited to make them reachable and
 * <strong>no reject-engine behaviour is asserted here</strong> - that belongs to
 * {@code com.cardemo.unit.batch} and {@code com.cardemo.integration.batch}.
 *
 * <h3>The cross-reference-miss asymmetry - cited, deliberately not implemented</h3>
 *
 * <p>One condition, "no cross-reference record", has two different legacy outcomes, and the difference is
 * the reason a repository must not decide it:
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl:383} - a miss on the <em>keyed base</em> read yields reject code
 *       <strong>100</strong>, a business outcome and not an exception.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:393-413} - a miss on the <em>alternate-index</em> read displays a
 *       message at {@code :397}, then falls straight into the standard I/O guard and reaches
 *       {@code PERFORM 9999-ABEND-PROGRAM} at {@code :411}, so <strong>the job abends</strong>. Java must
 *       translate that empty lookup into a fatal exception; implementing it as a skip would be a
 * </li>
 * </ul>
 *
 * <p>Neither outcome is implemented or asserted at this tier. The obligation here is narrower and is
 * discharged by {@link SeedAndMapping#anAbsentBaseKeyResolvesToAnEmptyOptional()} and
 * {@link AlternateKey#anAccountWithNoCrossReferenceYieldsEmptyAndNeverThrows()}: the repository returns an
 * empty {@link Optional} and an empty {@link List} and never throws. Choosing between reject code and abend
 * is {@code com.cardemo.service.shared.FileStatusMapper}'s single responsibility, tested with its owner.
 *
 * <h3>Scope: one concern, and what is deliberately left to its owner</h3>
 *
 * <p>This class asserts persistence, mapping, catalogue-metadata and constraint behaviour, and nothing else.
 * The account-view lookup chain of {@code app/cbl/COACTVWC.cbl}, the bill-payment lookup at
 * {@code app/cbl/COBIL00C.cbl:211} ({@code READ-CXACAIX-FILE}), the read-only sequential reader
 * {@code app/cbl/CBACT03C.cbl} - whose verb inventory is {@code OPEN}, {@code READ} and {@code CLOSE} with
 * no write verb anywhere, measured - and the posting and interest paths are all cited above and asserted in
 * {@code com.cardemo.unit.service}, {@code com.cardemo.unit.batch} and
 * {@code com.cardemo.integration.batch}. Re-testing them here would duplicate their owners and blur this
 * class's contract.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and run the whole gate with {@code ./mvnw clean verify}. Compile this tree alone, which is the
 * quickest check that the file still satisfies the compiler settings, with {@code ./mvnw -q test-compile}.
 *
 * <p><strong>This class is bound to Failsafe, not Surefire, and the binding is purely by path.</strong>
 * {@code maven-failsafe-plugin} 3.5.4 includes {@code **}{@code /integration/}{@code **}{@code /*Test.java}
 * and runs it at {@code integration-test} and {@code verify}, even though the class keeps the {@code Test}
 * suffix; {@code maven-surefire-plugin} 3.5.4 includes {@code **}{@code /*Test.java} generally but
 * <strong>explicitly excludes</strong> {@code **}{@code /integration/}{@code **} and
 * {@code **}{@code /e2e/}{@code **}. <strong>Do not rename or relocate this class.</strong> Moved up to the
 * {@code integration} level, to {@code com.cardemo}, to {@code com}, or into {@code src/test/java} directly,
 * it matches neither include set, is collected by neither plugin and simply never runs - a green build, both
 * plugins reporting success, no error and no output. That is the worst failure mode available here, because
 * nothing about it looks like a failure.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a real PostgreSQL 16
 * container plus the Testcontainers reaper, so this tier cannot run without a container runtime, and an
 * absent runtime must be reported as a blocker rather than recorded as a pass. Where a host JDK is not
 * provisioned the identical build runs in the pinned image, and produces the same result because every
 * plugin and every non-managed dependency is pinned to an exact version:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration of its own, performs no environment-variable or system-property read,
 * writes no system property, and contains no host, port, database name, user name, password or JDBC URL. The
 * connection is injected by the harness, and a literal would defeat that injection.
 *
 * <ul>
 *   <li><strong>Profile {@code test}</strong> and <strong>PostgreSQL 16</strong>, both owned by
 *       {@link AbstractRepositoryIntegrationTest}, which pins the image by digest rather than by tag and
 *       chooses the Debian build over {@code alpine} so that glibc text collation - and therefore every
 *       {@code ORDER BY} over a character column, including the ascending card-number order asserted here -
 *       matches what ships.</li>
 *   <li><strong>{@code spring.jpa.hibernate.ddl-auto: validate}</strong>, so the column names, SQL types,
 *       precisions and nullability asserted below are already enforced at context startup;
 *       {@code spring.jpa.open-in-view: false}; {@code spring.jpa.show-sql: false}, which is part of the
 *       secret-hygiene control rather than a preference, since SQL logging here would echo key values;
 *       Hibernate's JDBC time zone {@code UTC}.</li>
 *   <li><strong>Exactly three Flyway migrations</strong> - {@code V1__create_schema.sql},
 *       {@code V2__create_indexes.sql}, {@code V3__seed_data.sql} - with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and {@code baseline-on-migrate: false}.
 *       The {@code BATCH_*} tables come from {@code spring.batch.jdbc.initialize-schema}, never from a fourth
 *       migration, and none is added here.</li>
 *   <li><strong>Time is fixed and UTC</strong> at {@code 2022-06-10T19:27:53Z}, available from the harness's
 *       injected clock. Nothing in this class reads the ambient clock, and nothing needs to: every assertion
 *       is over the 50 frozen records plus rows this class stores itself.</li>
 *   <li><strong>The {@code CHAR} blank-pad policy, stated once and applied everywhere.</strong> Every key
 *       value is supplied at <em>exactly</em> 16 characters, taken from the fixture's own columns 1-16 or
 *       constructed as a 16-digit synthetic value, so no padding, trimming or normalisation happens on
 *       either side of the boundary and a value read back compares equal to the value written. PostgreSQL
 *       blank-pads {@code character(16)} to width on read and tolerates <em>trailing-space</em> overflow on
 *       write, which is why the width assertion in
 *       {@link Constraints#anOverLengthBaseKeyIsRejectedByTheDatabase()} uses a seventeenth character that
 *       is <strong>not</strong> a space - a trailing space would be silently accepted and would prove
 *       nothing.</li>
 *   <li><strong>Isolation is transactional rollback only.</strong> The harness declares class-level
 *       {@code @Transactional}, so every row stored below disappears when the method ends. There is
 *       deliberately no {@code @Sql}, no {@code @DirtiesContext}, no {@code TRUNCATE} and no
 *       {@code deleteAll()}: each would either fight that mechanism or destroy the seed the sibling classes
 *       rely on. Insert order respects the foreign keys - the parents this table needs, {@code account} and
 *       {@code customer}, are already seeded, so every row stored here reuses existing parent ids.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test fails to start with a container or Docker error.</em> No reachable Docker socket. This
 *       tier cannot be made to pass without one.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or a 1.x version is resolved.</em> The
 *       most consequential trap of the migration, whose remedy has two halves that are both
 *       required: pin {@code 2.0.3} by <em>overriding the version property the Spring Boot parent manages</em>
 *       rather than importing a second bill of materials, because two competing imports resolve in an
 *       ordering-dependent way that can silently select the parent-managed 1.x line; and use only the
 *       <em>prefixed</em> coordinates {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter}, because the bare 1.x ids
 *       {@code postgresql}, {@code localstack} and {@code junit-jupiter} are not published on 2.x. Overriding
 *       without renaming resolves artefacts that do not exist; renaming without overriding resolves the wrong
 *       version. <strong>Verified: both halves are already in place in the root {@code pom.xml}</strong>,
 *       which is root-owned and must not be edited from here.</li>
 *   <li><em>The build fails on something that looks trivial.</em> Compilation runs {@code -Xlint:all} with
 *       {@code -Werror} and that reaches test compilation, so a single unused import, a raw type, an
 *       unchecked cast or a deprecation is an error and not a warning. Reproduce with
 *       {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a column name.</em> {@code validate}
 *       compares type codes, so a {@code Long} over {@code NUMERIC(9)} or {@code NUMERIC(11)} can fail where
 *       {@code BIGINT} passes. <strong>The fix is upstream</strong>, in {@code V1__create_schema.sql} or in
 *       the entity mapping; never widen a column to silence it and never patch this test.</li>
 *
 *   <li><em>An assertion about uniqueness fails, or a reviewer expects one.</em> Re-read
 *       {@code LISTCAT.txt:487} against {@code :488}. The {@code UNIQUE} on {@code :487} is the dataset-name
 *       attribute; {@code NONUNIQKEY} on {@code :488} is the key attribute and governs, and
 *       {@code XREFFILE.jcl:75} says {@code NONUNIQUEKEY} outright.</li>
 *   <li><em>An offset does not line up.</em> Check which alternate index is meant. The cross reference is
 *       {@code AXRKP 25} = byte 26; {@code AXRKP 16} = byte 17 belongs to the <em>card</em> table. Check also
 *       that {@code RKP 5} was read from {@code :485} and not from {@code :483}, which is a page-break line
 *       in this block.</li>
 *   <li><em>A query or mapping fails on an unknown column.</em> The column is {@code xref_acct_id}, not
 *       {@code acct_id}; likewise {@code xref_cust_id} and {@code xref_card_num}. Naming it {@code acct_id}
 *       is a hazard because it reads naturally and is wrong.</li>
 *   <li><em>A fixture assertion fails on width.</em> {@code cardxref.txt} records are <strong>36</strong>
 *       columns, not the catalogued 50; the unmodelled 14-byte {@code FILLER} is absent from the data.</li>
 *   <li><em>A mapping or lock assertion fails looking for {@code version}.</em> There is no {@code version}
 *       column on this table, by design.</li>
 *   <li><em>A fixture stream is null and the failure surfaces far from its cause.</em> The harness's fixture
 *       reader fails immediately and names the resource, which is what turns the canonical
 *       {@code dalytran.txt}-for-{@code dailytran.txt} misspelling into a clear message instead of a
 *       confusing null.</li>
 *   <li><em>A later assertion in a provoking test fails with "current transaction is aborted".</em>
 *       PostgreSQL aborts the enclosing transaction on the first failed statement, which is why each
 *       constraint provocation below is the <strong>last</strong> database action in its own method and why
 *       those provocations are not combined.</li>
 * </ul>
 *
 * <h2>What this file asserts, and the evidence each assertion rests on</h2>
 *
 * <p>Every expectation below was measured here rather than transcribed, which is what keeps it from drifting.
 *
 * <ul>
 *   <li><strong>The anchor row's expected values are derived, not written down.</strong> The fixture's columns
 *       17-25 read {@code 000000050} and {@code V3__seed_data.sql:837} seeds {@code (<key>, 50, 50)}.
 *       {@link SeedAndMapping#theFirstSeededRecordRoundTripsThroughItsKey()} <em>derives</em> both expected
 *       values from the frozen fixture and then pins them, so the assertion cannot silently drift and the
 *       value is machine-checked rather than asserted from prose.</li>
 *   <li><strong>Non-uniqueness is proved at the database, not through a list finder.</strong>
 *       {@link CardCrossReferenceRepository} exposes
 *       {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc(Long)}, which returns an
 *       {@link Optional} and models the legacy <em>single keyed read</em> through the CXACAIX path
 *       ({@code CBACT04C.cbl:394-395}, {@code COBIL00C.cbl:211}), plus a keyset finder. Adding a list finder
 *       that no production caller invokes would introduce exactly the dead code Rule 1 Clause B forbids, so
 *       {@link AlternateKey} proves non-uniqueness where it is actually decided: a second row against one
 *       account is stored successfully, a parameterised ordered query returns both rows ascending,
 *       {@code pg_index.indisunique} is false, and {@code findFirst...} is shown to return the
 *       <em>lowest</em> of the two, which demonstrates it is a first-of-many read and not a uniqueness
 *       claim. That is stronger evidence than a list finder's cardinality would have been, because it tests
 *       the database rather than the query method.</li>
 *   <li><strong>The index name asserted is the one the migration emits.</strong>
 *       {@code V2__create_indexes.sql:434} creates
 *       <strong>{@code idx_card_cross_reference_acct_id}</strong>, and the migration governs.</li>
 *   <li><strong>The return-code rule is cited at its verified locator.</strong> Return code 4 is set at
 *       {@code CBTRN02C.cbl:229-231}, and that locator is used throughout.</li>
 *   <li><strong>The alternate-key offset is always written base-qualified</strong> - {@code AXRKP 25}
 *       zero-based, record byte 26 one-based - for the reason described above.</li>
 *   <li><strong>The coverage plugin is pinned at {@code 0.8.12}.</strong> The pinned version is the one the
 *       build resolves and the one referred to here.</li>
 *   <li><strong>The {@code TRANSACT} alternate-index path has no CSD {@code DEFINE FILE} entry</strong>,
 *       confirming it is batch-only; the cross reference by contrast has both {@code CCXREF}
 *       ({@code CARDDEMO.CSD:37},{@code :39}) and {@code CXACAIX} ({@code :63-65}), two of exactly eight
 *       file definitions.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are stated as <strong>"Not available"</strong> rather than filled in, because inventing
 * either would manufacture a false oracle.
 *
 * <ol>
 *   <li><strong>The end-to-end boundary parity baseline is Not available.</strong> No captured legacy output
 *       exists anywhere in this repository; searches across expected, baseline, golden, {@code .out} and
 *       system-output name patterns, and across the reject, report, statement and HTML dataset names, return
 *       only dataset <em>definition</em> members and no captured data. What is needed to close it: a captured
 *       430-byte {@code DALYREJS} reject dataset from a real {@code POSTTRAN} run at a known input state,
 *       together with the resulting {@code TRANSACT}, {@code ACCTDATA} and {@code TCATBALF} images.
 *       Therefore <strong>this class creates no baseline file and fabricates no expected bytes</strong>;
 *       generating a baseline from this implementation and asserting against it would be circular, and
 *       hand-simulating the posting program is equally inadmissible - two defensible models of it over these
 *       same fixtures disagree, 13 rejects against 38, and a figure that moves with the model is not an
 *       oracle.</li>
 *   <li><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> Across the 28
 *       programs of {@code app/cbl} the literal file status {@code '35'} occurs <strong>0</strong> times and
 *       {@code DFHRESP(NOTOPEN)} <strong>0</strong> times. The condition is specification-derived only, so no
 *       test for it is invented here and no parity claim is made for it.</li>
 * </ol>
 *
 * <h2>Assertion-output discipline, and why actual values are so often booleans and counts</h2>
 *
 * <p>Rule 1 Clause D forbids secrets in code, logs <em>and tests</em>, and card numbers are the
 * PII-adjacent value this table is keyed on. Two consequences shape the code below. No card number appears
 * in any description, message or log. And where an assertion would otherwise hand a card number to the
 * assertion library as its <em>actual</em> value - which is printed verbatim on failure - the value is
 * reduced first to a boolean, a count or an identifier, so that a failure cannot emit a key. That is why
 * ordering is asserted through a predicate rather than through a sorted-list matcher, and why constraint
 * names are matched to a boolean rather than asserted as substrings of a driver message: PostgreSQL's
 * duplicate-key detail line quotes the offending key, so a failed substring assertion on that message would
 * print it. The same reasoning is why {@link CardCrossReference#toString()} omits the card number, and this
 * class relies on that.
 */
@DisplayName("CARDXREF.VSAM.KSDS against real PostgreSQL 16: 36-of-50 geometry, the NON-UNIQUE alternate key "
        + "at AXRKP 25, both foreign keys and the absent card foreign key")
class CardCrossReferenceRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test, replacing the CICS files CCXREF and CXACAIX with one Spring Data bean. */
    @Autowired
    private CardCrossReferenceRepository repository;

    /** Reads catalogue metadata - index uniqueness, column names, constraint names - that no entity models. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Used to {@code persist} rows and to run one parameterised ordered query.
     *
     * <p>Declared here rather than inherited because the harness keeps its own persistence context private.
     * Both resolve to the same transaction-bound {@code EntityManager}, so a row stored through this field is
     * flushed by the harness's {@code flushAndClear()} and rolled back with the method like any other.
     *
     * <p>{@code persist} rather than {@code save} is deliberate and load bearing for
     * {@link Constraints#aDuplicateBaseKeyViolatesThePrimaryKey()}. The identifier is assigned rather than
     * generated and the entity carries no {@code version}, so Spring Data judges an entity with a non-null
     * identifier to be already persistent and issues a <em>merge</em>; a merge of a duplicate key updates the
     * existing row and no constraint ever fires, so the test would pass while proving nothing.
     * {@code persist} forces the {@code INSERT} that the primary key can reject.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The frozen cross-reference fixture, 50 records of exactly 36 columns, read by flat classpath name and
     * byte-for-byte unmodified.
     *
     * @return the fixture's records in file order; never {@code null} and never trimmed
     */
    private List<String> crossReferenceFixture() {
        return readFixture("cardxref.txt");
    }

    /**
     * Every cross reference for one account, ascending by base key - the multi-row read the non-unique
     * alternate index permits and {@code findFirst...} deliberately does not expose.
     *
     * <p>The account identifier is <strong>bound as a parameter</strong> rather than concatenated into the
     * query text, which is the same control Rule 1 Clause D applies to the native metadata queries below.
     *
     * @param accountId the account identifier to match against {@code xref_acct_id}
     * @return the matching rows ordered by card number ascending; empty when the account has none
     */
    private List<CardCrossReference> crossReferencesForAccount(long accountId) {
        return entityManager
                .createQuery("select x from CardCrossReference x where x.accountId = :accountId "
                        + "order by x.cardNumber asc", CardCrossReference.class)
                .setParameter("accountId", accountId)
                .getResultList();
    }

    /**
     * The base key at record bytes 1-16, per {@code app/cpy/CVACT03Y.cpy:5} {@code XREF-CARD-NUM PIC X(16)}.
     *
     * @param record one fixture record, exactly 36 columns wide
     * @return the 16-character key, never widened, padded or trimmed
     */
    private static String baseKeyOf(String record) {
        return record.substring(0, 16);
    }

    /**
     * The customer identifier at record bytes 17-25, per {@code app/cpy/CVACT03Y.cpy:6}
     * {@code XREF-CUST-ID PIC 9(09)}.
     *
     * @param record one fixture record, exactly 36 columns wide
     * @return the decoded identifier; the field is unsigned, so no overpunch decode applies
     */
    private static long customerIdOf(String record) {
        return Long.parseLong(record.substring(16, 25));
    }

    /**
     * The account identifier at record bytes 26-36, per {@code app/cpy/CVACT03Y.cpy:7}
     * {@code XREF-ACCT-ID PIC 9(11)} - the field the alternate index at zero-based {@code AXRKP 25} covers.
     *
     * @param record one fixture record, exactly 36 columns wide
     * @return the decoded identifier; the field is unsigned, so no overpunch decode applies
     */
    private static long accountIdOf(String record) {
        return Long.parseLong(record.substring(25, 36));
    }

    /**
     * Whether the values are in strictly ascending order.
     *
     * <p>Reduced to a boolean on purpose: the values compared here are card numbers, and an assertion that
     * took the list itself as its actual value would print every one of them on failure.
     *
     * @param values the values to inspect, in the order the database returned them
     * @return {@code true} when each value is strictly greater than the one before it, including for the
     *         empty and single-element cases
     */
    private static boolean isStrictlyAscending(List<String> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every record is exactly the given width.
     *
     * @param records the fixture records to measure
     * @param width   the expected column count, 36 for this fixture rather than the catalogued 50
     * @return {@code true} only when every record matches exactly
     */
    private static boolean everyRecordHasWidth(List<String> records, int width) {
        for (String record : records) {
            if (record.length() != width) {
                return false;
            }
        }
        return true;
    }

    /**
     * The distinct values of one fixed-width column, in first-seen order.
     *
     * @param records    the fixture records to read
     * @param beginIndex zero-based inclusive start of the column
     * @param endIndex   zero-based exclusive end of the column
     * @return the distinct values; insertion-ordered so that the result is deterministic
     */
    private static Set<String> columnValues(List<String> records, int beginIndex, int endIndex) {
        Set<String> values = new LinkedHashSet<>();
        for (String record : records) {
            values.add(record.substring(beginIndex, endIndex));
        }
        return values;
    }

    /**
     * How many records carry a column value that is absent from the given parent key set - that is, how many
     * orphans there are.
     *
     * <p>A count rather than the offending values, so that a failure cannot print a key.
     *
     * @param records    the child records to inspect
     * @param beginIndex zero-based inclusive start of the referencing column
     * @param endIndex   zero-based exclusive end of the referencing column
     * @param parentKeys the parent key values the column must be drawn from
     * @return the orphan count, expected to be zero for all three parents of this fixture
     */
    private static int countOrphans(List<String> records, int beginIndex, int endIndex, Set<String> parentKeys) {
        int orphans = 0;
        for (String record : records) {
            if (!parentKeys.contains(record.substring(beginIndex, endIndex))) {
                orphans++;
            }
        }
        return orphans;
    }

    /**
     * Whether the thrown exception, or anything in its cause chain, names the given token.
     *
     * <p>Matching on the constraint name is what proves <em>which</em> rule fired, and returning a boolean is
     * what keeps the key out of the output: PostgreSQL's duplicate-key detail line quotes the offending key
     * value, so a substring assertion against that message would print it on failure. Walking the chain at
     * all is what proves the root cause was preserved rather than swallowed.
     *
     * @param thrown the exception to inspect; may be {@code null}, which yields {@code false}
     * @param token  the constraint name or SQL fragment to look for
     * @return {@code true} when any message in the chain contains the token
     */
    private static boolean causeChainMentions(Throwable thrown, String token) {
        Throwable current = thrown;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(token)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * The SQL state of the first {@link SQLException} in the cause chain.
     *
     * <p>Asserting the state rather than the message makes the width contract independent of driver wording,
     * and keeps the offending value out of the output.
     *
     * @param thrown the exception to inspect; may be {@code null}
     * @return the SQL state, or empty when the chain carries no {@link SQLException}
     */
    private static Optional<String> sqlStateOf(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return Optional.ofNullable(sqlException.getSQLState());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }

    /** The seed loaded by {@code V3__seed_data.sql}, and the mapping of the three modelled fields. */
    @Nested
    @DisplayName("Seed and field mapping: 50 records of CVACT03Y through the 16-byte base key")
    class SeedAndMapping {

        @Test
        @DisplayName("the seed loaded exactly the fifty records the fixture and the catalogue agree on")
        void theSeedLoadedExactlyFiftyRecords() {
            assertThat(repository.count())
                    .as("V3__seed_data.sql:834-838 loads app/data/ASCII/cardxref.txt, whose 1,850 bytes are "
                            + "50 records x 36 columns plus a line feed each; app/catlg/LISTCAT.txt:408 "
                            + "reports REC-TOTAL 50 for the cluster and :490 the same for the AIX")
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("the first seeded record round-trips through its key with the customer and account ids "
                + "the fixture columns carry")
        void theFirstSeededRecordRoundTripsThroughItsKey() {
            String firstRecord = crossReferenceFixture().get(0);

            // Derived from the frozen fixture rather than typed in, so the expectation cannot drift from the
            // bytes, and so no key literal appears in this file. The class Javadoc explains why the
            // expectation is derived at all: the nine-digit field is easy to read eight digits wide.
            assertThat(customerIdOf(firstRecord))
                    .as("XREF-CUST-ID at app/cpy/CVACT03Y.cpy:6, record bytes 17-25; fixture columns 17-25 "
                            + "read 000000050 and V3__seed_data.sql:837 seeds 50, so all nine digits have to "
                            + "be decoded")
                    .isEqualTo(50L);
            assertThat(accountIdOf(firstRecord))
                    .as("XREF-ACCT-ID at app/cpy/CVACT03Y.cpy:7, record bytes 26-36, which is zero-based "
                            + "AXRKP 25 per app/catlg/LISTCAT.txt:486 and V1__create_schema.sql:800")
                    .isEqualTo(50L);

            Optional<CardCrossReference> stored = repository.findById(baseKeyOf(firstRecord));

            assertThat(stored)
                    .as("the 16-byte base key at RKP 0 (app/catlg/LISTCAT.txt:403-404) resolves the record; "
                            + "the key is supplied at exactly CHAR(16) width so no blank padding is involved")
                    .isPresent();
            assertThat(stored.get().getCustomerId().longValue())
                    .as("xref_cust_id NUMERIC(9) carries the value the fixture column carries")
                    .isEqualTo(customerIdOf(firstRecord));
            assertThat(stored.get().getAccountId().longValue())
                    .as("xref_acct_id NUMERIC(11) carries the value the fixture column carries")
                    .isEqualTo(accountIdOf(firstRecord));
        }

        @Test
        @DisplayName("an absent sixteen-character base key resolves to an empty Optional and never throws")
        void anAbsentBaseKeyResolvesToAnEmptyOptional() {
            Optional<CardCrossReference> stored = repository.findById("9999999999999999");

            assertThat(stored)
                    .as("This is the decisive semantic boundary of the whole tier. The same 'no record' "
                            + "condition has two legacy outcomes - reject code 100 at app/cbl/CBTRN02C.cbl:383 "
                            + "on the keyed base read, and an abend at app/cbl/CBACT04C.cbl:393-413 via "
                            + "PERFORM 9999-ABEND-PROGRAM on the alternate-index read - so the repository must "
                            + "decide neither. It returns empty and does not throw; the translation belongs to "
                            + "com.cardemo.service.shared.FileStatusMapper and is tested with its owner")
                    .isEmpty();
        }
    }

    /**
     * The headline group: the alternate key at zero-based {@code AXRKP 25} is <strong>non-unique</strong>, and
     * that is proved by storing a second row against one account rather than by inspecting a return type.
     */
    @Nested
    @DisplayName("CARDXREF.VSAM.AIX at AXRKP 25 (= record byte 26): the alternate key is NON-UNIQUE")
    class AlternateKey {

        @Test
        @DisplayName("a second cross reference for one account is stored, and both are returned ascending - "
                + "the alternate key is multi-valued")
        void theAlternateKeyIsMultiValued() {
            String firstRecord = crossReferenceFixture().get(0);
            long accountId = accountIdOf(firstRecord);
            long customerId = customerIdOf(firstRecord);
            int seeded = crossReferencesForAccount(accountId).size();

            // A second row for the SAME account, with a distinct base key and a parent that exists. A unique
            // alternate index would reject this INSERT outright, so the fact that it succeeds is the positive
            // proof of non-uniqueness. The key is a clearly synthetic 16-digit value, not a real card number.
            entityManager.persist(new CardCrossReference("9999999999999999", customerId, accountId));
            flushAndClear();

            List<CardCrossReference> forAccount = crossReferencesForAccount(accountId);

            assertThat(forAccount.size())
                    .as("app/catlg/LISTCAT.txt:488 reads SPANNED NONUNIQKEY and that token governs; the "
                            + "UNIQUE on :487 is the DATASET-NAME attribute and says nothing about key "
                            + "uniqueness. app/jcl/XREFFILE.jcl:74-75 confirms it independently with "
                            + "KEYS(11,25) followed by NONUNIQUEKEY. The key is at :486 AXRKP 25 (zero-based) "
                            + "= record byte 26 (one-based), which is where XREF-ACCT-ID begins - not the "
                            + "card table's AXRKP 16 (:283) = byte 17")
                    .isGreaterThanOrEqualTo(2);
            assertThat(isStrictlyAscending(forAccount.stream().map(CardCrossReference::getCardNumber).toList()))
                    .as("ordered by base key ascending, the order the CXACAIX path browse yields; asserted "
                            + "through a predicate so that a failure cannot print a key")
                    .isTrue();
            assertThat(forAccount.size())
                    .as("exactly one seeded row plus the one stored here. The one-cross-reference-per-account "
                            + "shape is a FIXTURE PROPERTY of app/data/ASCII/cardxref.txt, whose 50 account "
                            + "ids are distinct - it is NOT a schema invariant, which is precisely what the "
                            + "assertion above demonstrates")
                    .isEqualTo(seeded + 1);
        }

        @Test
        @DisplayName("the keyed read returns the lowest base key of many, which is a first-of-many read and "
                + "not a uniqueness claim")
        void theKeyedReadReturnsTheLowestBaseKeyOfMany() {
            String firstRecord = crossReferenceFixture().get(0);
            long accountId = accountIdOf(firstRecord);
            long customerId = customerIdOf(firstRecord);
            entityManager.persist(new CardCrossReference("9999999999999998", customerId, accountId));
            flushAndClear();

            Optional<CardCrossReference> keyedRead =
                    repository.findFirstByAccountIdOrderByCardNumberAsc(accountId);

            assertThat(keyedRead)
                    .as("the CXACAIX single keyed read of app/cbl/CBACT04C.cbl:394-395 (READ ... KEY IS "
                            + "FD-XREF-ACCT-ID) and app/cbl/COBIL00C.cbl:211 (READ-CXACAIX-FILE) resolves")
                    .isPresent();
            assertThat(keyedRead.get().getCardNumber().equals(baseKeyOf(firstRecord)))
                    .as("it yields the LOWEST base key even though two rows now match, which is what makes "
                            + "findFirst...OrderByCardNumberAsc a first-of-many read modelling one keyed READ "
                            + "through the path - it asserts nothing about key uniqueness, and per "
                            + "app/catlg/LISTCAT.txt:488 it must not")
                    .isTrue();
        }

        @Test
        @DisplayName("an account with no cross reference yields an empty Optional and an empty list, never "
                + "null and never an exception")
        void anAccountWithNoCrossReferenceYieldsEmptyAndNeverThrows() {
            long unreferencedAccountId = 99_999_999_999L;

            Optional<CardCrossReference> keyedRead =
                    repository.findFirstByAccountIdOrderByCardNumberAsc(unreferencedAccountId);
            List<CardCrossReference> browse = crossReferencesForAccount(unreferencedAccountId);

            assertThat(keyedRead)
                    .as("the empty-result boundary condition; the repository reports absence and leaves the "
                            + "choice between reject code 100 (app/cbl/CBTRN02C.cbl:383) and abend "
                            + "(app/cbl/CBACT04C.cbl:393-413) to FileStatusMapper")
                    .isEmpty();
            assertThat(browse)
                    .as("an EMPTY LIST, explicitly not null - the multi-row form of the same boundary")
                    .isNotNull()
                    .isEmpty();
        }
    }

    /** The 36-of-50 geometry as the database actually declares it, plus the two indexes and no others. */
    @Nested
    @DisplayName("Geometry and catalogue metadata: three columns, two indexes, no version, no filler")
    class Geometry {

        @Test
        @DisplayName("the columns are the COBOL names, and acct_id, cust_id, card_num and version do not exist")
        void theColumnsAreTheCobolNamesAndNothingElse() {
            List<String> columns = jdbcTemplate.queryForList("""
                    SELECT column_name
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                     ORDER BY ordinal_position
                    """, String.class, "card_cross_reference");

            assertThat(columns)
                    .as("V1__create_schema.sql:795-802 names the columns after the copybook fields of "
                            + "app/cpy/CVACT03Y.cpy:5-7, in copybook order")
                    .containsExactly("xref_card_num", "xref_cust_id", "xref_acct_id");
            assertThat(columns)
                    .as("the unprefixed spellings are a naming hazard because they read "
                            + "naturally and are wrong - the alternate-index target is xref_acct_id, never "
                            + "acct_id. 'version' is absent because this table is not one of the four that "
                            + "carry it (account, card, customer, \"transaction\"), so no optimistic-locking "
                            + "assertion is possible or attempted here")
                    .doesNotContain("acct_id", "cust_id", "card_num", "version");
        }

        @Test
        @DisplayName("exactly three business columns, each NOT NULL, with the widths the PIC clauses imply")
        void theTableCarriesExactlyThreeBusinessColumns() {
            List<Map<String, Object>> declared = jdbcTemplate.queryForList("""
                    SELECT column_name, data_type, character_maximum_length, numeric_precision, is_nullable
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = ?
                     ORDER BY ordinal_position
                    """, "card_cross_reference");

            assertThat(declared.size())
                    .as("16 + 9 + 11 = 36 modelled bytes of the 50 the catalogue records at "
                            + "app/catlg/LISTCAT.txt:403. The FILLER X(14) at app/cpy/CVACT03Y.cpy:8, record "
                            + "bytes 37-50, is deliberately NOT modelled as a column, and there is no "
                            + "version column either - so three columns and no fourth")
                    .isEqualTo(3);

            Map<String, Object> baseKey = declared.get(0);
            assertThat(baseKey.get("data_type"))
                    .as("XREF-CARD-NUM PIC X(16) becomes fixed-width CHAR, not VARCHAR")
                    .isEqualTo("character");
            assertThat(((Number) baseKey.get("character_maximum_length")).intValue())
                    .as("the CHAR(16) width contract of app/cpy/CVACT03Y.cpy:5")
                    .isEqualTo(16);
            assertThat(((Number) declared.get(1).get("numeric_precision")).intValue())
                    .as("XREF-CUST-ID PIC 9(09) becomes NUMERIC(9)")
                    .isEqualTo(9);
            assertThat(((Number) declared.get(2).get("numeric_precision")).intValue())
                    .as("XREF-ACCT-ID PIC 9(11) becomes NUMERIC(11)")
                    .isEqualTo(11);
            assertThat(declared.stream().map(column -> column.get("is_nullable")).toList())
                    .as("every column of V1 is NOT NULL, this table's three included")
                    .containsExactly("NO", "NO", "NO");
        }

        @Test
        @DisplayName("the alternate-index replacement exists on xref_acct_id and indisunique is FALSE")
        void theAlternateIndexExistsOnTheAccountColumnAndIsNotUnique() {
            List<Boolean> unique = jdbcTemplate.queryForList("""
                    SELECT i.indisunique
                      FROM pg_index i
                      JOIN pg_class ix ON ix.oid = i.indexrelid
                      JOIN pg_class t ON t.oid = i.indrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                       AND ix.relname = ?
                    """, Boolean.class, "card_cross_reference", "idx_card_cross_reference_acct_id");

            assertThat(unique.size())
                    .as("V2__create_indexes.sql:434-435 creates idx_card_cross_reference_acct_id USING btree "
                            + "(xref_acct_id), and that emitted name is the one this assertion pins")
                    .isEqualTo(1);
            assertThat(unique.get(0))
                    .as("app/catlg/LISTCAT.txt:488 SPANNED NONUNIQKEY governs and :487's UNIQUE is the "
                            + "dataset-name attribute; app/jcl/XREFFILE.jcl:75 NONUNIQUEKEY confirms it. The "
                            + "index covers :486 AXRKP 25 (zero-based) = record byte 26 (one-based)")
                    .isFalse();

            List<String> indexedColumns = jdbcTemplate.queryForList("""
                    SELECT a.attname
                      FROM pg_index i
                      JOIN pg_class ix ON ix.oid = i.indexrelid
                      JOIN pg_class t ON t.oid = i.indrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                       AND ix.relname = ?
                     ORDER BY a.attnum
                    """, String.class, "card_cross_reference", "idx_card_cross_reference_acct_id");

            assertThat(indexedColumns)
                    .as("the index sits on the account column at record byte 26, the field CXACAIX keys on")
                    .containsExactly("xref_acct_id");
        }

        @Test
        @DisplayName("no unique index beyond the primary key, and no third index on this table")
        void theTableCarriesNoUniqueIndexBeyondThePrimaryKey() {
            Long nonPrimaryUnique = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                      FROM pg_index i
                      JOIN pg_class ix ON ix.oid = i.indexrelid
                      JOIN pg_class t ON t.oid = i.indrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                       AND i.indisunique
                       AND NOT i.indisprimary
                    """, Long.class, "card_cross_reference");

            assertThat(nonPrimaryUnique)
                    .as("V2__create_indexes.sql contains 3 CREATE INDEX, 0 CREATE UNIQUE INDEX and 0 "
                            + "CONCURRENTLY; a legacy alternate key is non-unique, so the only unique index "
                            + "on this table is the one the primary key implies")
                    .isZero();

            List<String> everyIndex = jdbcTemplate.queryForList("""
                    SELECT ix.relname
                      FROM pg_index i
                      JOIN pg_class ix ON ix.oid = i.indexrelid
                      JOIN pg_class t ON t.oid = i.indrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                     ORDER BY ix.relname
                    """, String.class, "card_cross_reference");

            assertThat(everyIndex)
                    .as("the base cluster's key at app/catlg/LISTCAT.txt:403-405 (KEYLEN 16, RKP 0, and no "
                            + "NONUNIQKEY token anywhere) plus the one alternate index of :455-:488 - two "
                            + "indexes in total, so there is no third on this table")
                    .containsExactly("idx_card_cross_reference_acct_id", "pk_card_cross_reference");
        }

        @Test
        @DisplayName("no check constraint on this table, because none of V1's five applies to it")
        void theTableCarriesNoCheckConstraint() {
            Long checks = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                       AND c.contype = 'c'
                    """, Long.class, "card_cross_reference");

            assertThat(checks)
                    .as("V1 declares exactly five check constraints - the two status flags, the primary "
                            + "card-holder indicator, the user type and the nine-digit social security "
                            + "number - and none of the five is on card_cross_reference. Counted from "
                            + "pg_constraint with contype 'c' rather than from information_schema, which "
                            + "synthesises an IS NOT NULL entry per column and would report three")
                    .isZero();
        }
    }

    /**
     * Constraint enforcement at the persistence boundary, with hostile input. Each provocation is the last
     * database action in its own method, because PostgreSQL aborts the enclosing transaction on the first
     * failed statement and any later query would then fail for the wrong reason.
     */
    @Nested
    @DisplayName("Constraints: both foreign keys, the absent card foreign key, the base key and CHAR(16)")
    class Constraints {

        @Test
        @DisplayName("exactly two foreign keys, on the customer and account columns and not on the base key")
        void exactlyTwoForeignKeysAndNoneOnTheBaseKey() {
            List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList("""
                    SELECT c.conname, a.attname
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                      JOIN pg_namespace n ON n.oid = t.relnamespace
                      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
                     WHERE n.nspname = current_schema()
                       AND t.relname = ?
                       AND c.contype = 'f'
                     ORDER BY c.conname
                    """, "card_cross_reference");

            assertThat(foreignKeys.stream().map(row -> row.get("conname")).toList())
                    .as("V1__create_schema.sql:808-809 and :816-817 - two of the migration's exactly ten "
                            + "foreign keys, and the only pair carried by one table")
                    .containsExactly("fk02_xref_customer", "fk03_xref_account");
            assertThat(foreignKeys.stream().map(row -> row.get("attname")).toList())
                    .as("they constrain the customer and account columns only. There is deliberately NO "
                            + "foreign key from xref_card_num to card.card_num, which is why xref_card_num "
                            + "does not appear here")
                    .containsExactly("xref_cust_id", "xref_acct_id");
        }

        @Test
        @DisplayName("an unknown customer id is refused by fk02_xref_customer, message and cause preserved")
        void anUnknownCustomerIdViolatesTheCustomerForeignKey() {
            long knownAccountId = accountIdOf(crossReferenceFixture().get(0));
            long unknownCustomerId = 999_999_999L;
            entityManager.persist(
                    new CardCrossReference("9999999999999997", unknownCustomerId, knownAccountId));

            Throwable thrown = catchThrowable(CardCrossReferenceRepositoryTest.this::flushAndClear);

            assertThat(thrown)
                    .as("the customer reached through the cross reference must resolve - it is the bridge the "
                            + "account-view chain of app/cbl/COACTVWC.cbl walks. The identifier is inside "
                            + "PIC 9(09) range, so the entity accepts it and the database is what refuses it")
                    .isInstanceOf(PersistenceException.class);
            assertThat(thrown.getCause())
                    .as("the root cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(causeChainMentions(thrown, "fk02_xref_customer"))
                    .as("the violated constraint is named in the cause chain, per "
                            + "V1__create_schema.sql:808-809. Matched to a boolean because the driver's "
                            + "detail line quotes column values")
                    .isTrue();
        }

        @Test
        @DisplayName("an unknown account id is refused by fk03_xref_account, message and cause preserved")
        void anUnknownAccountIdViolatesTheAccountForeignKey() {
            long knownCustomerId = customerIdOf(crossReferenceFixture().get(0));
            long unknownAccountId = 99_999_999_999L;
            entityManager.persist(
                    new CardCrossReference("9999999999999996", knownCustomerId, unknownAccountId));

            Throwable thrown = catchThrowable(CardCrossReferenceRepositoryTest.this::flushAndClear);

            assertThat(thrown)
                    .as("the account reached through the cross reference must resolve; app/cbl/CBTRN02C.cbl "
                            + ":394-395 moves XREF-ACCT-ID into the account key and its miss path assigns "
                            + "reject code 101. The identifier is inside PIC 9(11) range, so the entity "
                            + "accepts it and the database is what refuses it")
                    .isInstanceOf(PersistenceException.class);
            assertThat(thrown.getCause())
                    .as("the root cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(causeChainMentions(thrown, "fk03_xref_account"))
                    .as("the violated constraint is named in the cause chain, per "
                            + "V1__create_schema.sql:816-817")
                    .isTrue();
        }

        @Test
        @DisplayName("a base key absent from the card table still persists, because there is no card "
                + "foreign key")
        void aCardNumberAbsentFromTheCardTableStillPersists() {
            String firstRecord = crossReferenceFixture().get(0);
            String unknownToTheCardTable = "9999999999999995";
            Long presentInCardTable = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM card WHERE card_num = ?
                    """, Long.class, unknownToTheCardTable);
            assertThat(presentInCardTable)
                    .as("the precondition: this synthetic key is absent from the card table, so the row "
                            + "below would be rejected if a card foreign key existed")
                    .isZero();

            entityManager.persist(new CardCrossReference(
                    unknownToTheCardTable, customerIdOf(firstRecord), accountIdOf(firstRecord)));
            flushAndClear();

            assertThat(repository.findById(unknownToTheCardTable))
                    .as("it persists. V1 carries exactly ten foreign keys and none of them constrains "
                            + "xref_card_num, so a cross reference may name a card the card table does not "
                            + "hold. That is the deliberate schema shape and not an omission - the seeded "
                            + "fixtures happen to have zero orphan card numbers, but that is a FIXTURE "
                            + "PROPERTY and the schema does not enforce it")
                    .isPresent();
        }

        @Test
        @DisplayName("a duplicate base key is refused by the primary key, message and cause preserved")
        void aDuplicateBaseKeyViolatesThePrimaryKey() {
            String firstRecord = crossReferenceFixture().get(0);
            // A synthetic key is duplicated rather than a seeded one, so that the driver's duplicate-key
            // detail line - which quotes the offending value - cannot carry a real card number.
            String duplicated = "9999999999999994";
            entityManager.persist(new CardCrossReference(
                    duplicated, customerIdOf(firstRecord), accountIdOf(firstRecord)));
            flushAndClear();
            entityManager.persist(new CardCrossReference(
                    duplicated, customerIdOf(firstRecord), accountIdOf(firstRecord)));

            Throwable thrown = catchThrowable(CardCrossReferenceRepositoryTest.this::flushAndClear);

            assertThat(thrown)
                    .as("The BASE key is unique while the ALTERNATE key is not, and both halves matter. "
                            + "app/catlg/LISTCAT.txt:405 shows the base cluster as SHROPTNS(2,3) RECOVERY "
                            + "UNIQUE ERASE INDEXED with NO NONUNIQKEY token, so its 16-byte key at RKP 0 "
                            + "genuinely is unique - unlike the alternate key at :488")
                    .isInstanceOf(PersistenceException.class);
            assertThat(thrown.getCause())
                    .as("the root cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(causeChainMentions(thrown, "pk_card_cross_reference"))
                    .as("the violated constraint is named in the cause chain, per "
                            + "V1__create_schema.sql:803")
                    .isTrue();
        }

        @Test
        @DisplayName("an over-length base key is refused at the database boundary by the CHAR(16) width")
        void anOverLengthBaseKeyIsRejectedByTheDatabase() {
            String firstRecord = crossReferenceFixture().get(0);
            // Seventeen characters whose last is NOT a space. PostgreSQL silently tolerates trailing-space
            // overflow on character(n), so a padded value would prove nothing about the declared width.
            String overLength = "99999999999999993";
            assertThat(overLength.length())
                    .as("the provoking value is one character wider than XREF-CARD-NUM PIC X(16)")
                    .isEqualTo(17);

            // A parameterised native INSERT, because the entity's own width guard rejects an over-length
            // value before it can reach the database and this assertion is about the column, not the guard.
            Throwable thrown = catchThrowable(() -> jdbcTemplate.update("""
                    INSERT INTO card_cross_reference (xref_card_num, xref_cust_id, xref_acct_id)
                    VALUES (?, ?, ?)
                    """, overLength, customerIdOf(firstRecord), accountIdOf(firstRecord)));

            assertThat(thrown)
                    .as("xref_card_num is CHAR(16), so the column itself refuses a seventeenth non-blank "
                            + "character; app/cpy/CVACT03Y.cpy:5 and app/catlg/LISTCAT.txt:403 KEYLEN 16")
                    .isInstanceOf(DataAccessException.class);
            assertThat(thrown.getCause())
                    .as("the root cause is preserved rather than swallowed")
                    .isNotNull();
            assertThat(sqlStateOf(thrown))
                    .as("PostgreSQL 22001, string_data_right_truncation - asserted by SQL state rather than "
                            + "by message text so that the contract is independent of driver wording and no "
                            + "value is printed")
                    .contains("22001");
        }
    }

    /**
     * Corroboration against the frozen ASCII fixtures, which are read by flat classpath name and never
     * copied, trimmed, normalised or edited.
     */
    @Nested
    @DisplayName("Fixture corroboration: 50 x 36 ascending, and zero orphans against all three parents")
    class FixtureCorroboration {

        @Test
        @DisplayName("the fixture is fifty records of exactly thirty-six columns, ascending by base key")
        void theFixtureIsFiftyRecordsOfThirtySixColumnsAscending() {
            List<String> fixture = crossReferenceFixture();

            assertThat(fixture.size())
                    .as("app/data/ASCII/cardxref.txt is 1,850 bytes = 50 records x (36 columns + 1 line feed)")
                    .isEqualTo(50);
            assertThat(everyRecordHasWidth(fixture, 36))
                    .as("36 columns, NOT the 50 the catalogue records at app/catlg/LISTCAT.txt:403. The "
                            + "difference is the FILLER X(14) of app/cpy/CVACT03Y.cpy:8 at record bytes "
                            + "37-50, which is absent from the data and unmodelled in the schema; the "
                            + "fixture's maximum trailing-space run is zero, so nothing was trimmed away")
                    .isTrue();
            assertThat(isStrictlyAscending(fixture.stream().map(
                            CardCrossReferenceRepositoryTest::baseKeyOf).toList()))
                    .as("the records are in ascending base-key order, which is the KSDS key sequence at "
                            + "app/catlg/LISTCAT.txt:404 RKP 0; asserted through a predicate so that a "
                            + "failure cannot print a key")
                    .isTrue();
        }

        @Test
        @DisplayName("the seed reproduces the fixture record for record, with no mismatch")
        void theSeedReproducesTheFixtureRecordForRecord() {
            List<String> fixture = crossReferenceFixture();
            int mismatches = 0;
            for (String record : fixture) {
                Optional<CardCrossReference> stored = repository.findById(baseKeyOf(record));
                if (stored.isEmpty()
                        || stored.get().getCustomerId().longValue() != customerIdOf(record)
                        || stored.get().getAccountId().longValue() != accountIdOf(record)) {
                    mismatches++;
                }
            }

            assertThat(mismatches)
                    .as("every one of the 50 frozen records resolves through its 16-byte key and carries the "
                            + "customer id from columns 17-25 and the account id from columns 26-36; counted "
                            + "rather than listed so that a failure cannot print a key")
                    .isZero();
        }

        @Test
        @DisplayName("zero orphans against customer, account and card - a fixture property, not a constraint")
        void theFixtureHasZeroOrphansAgainstAllThreeParents() {
            List<String> fixture = crossReferenceFixture();
            Set<String> customerKeys = columnValues(readFixture("custdata.txt"), 0, 9);
            Set<String> accountKeys = columnValues(readFixture("acctdata.txt"), 0, 11);
            Set<String> cardKeys = columnValues(readFixture("carddata.txt"), 0, 16);

            assertThat(customerKeys.size()).as("custdata.txt carries 50 distinct customer keys").isEqualTo(50);
            assertThat(accountKeys.size()).as("acctdata.txt carries 50 distinct account keys").isEqualTo(50);
            assertThat(cardKeys.size()).as("carddata.txt carries 50 distinct card keys").isEqualTo(50);

            assertThat(countOrphans(fixture, 16, 25, customerKeys))
                    .as("every xref_cust_id is present among the customer keys. FIXTURE PROPERTY, not a "
                            + "schema invariant - fk02_xref_customer is what enforces it at run time")
                    .isZero();
            assertThat(countOrphans(fixture, 25, 36, accountKeys))
                    .as("every xref_acct_id is present among the account keys. FIXTURE PROPERTY; "
                            + "fk03_xref_account enforces it at run time. With zero orphan card numbers this "
                            + "is also why reject codes 100 and 101 of app/cbl/CBTRN02C.cbl are unreachable "
                            + "over the seeded data - see the reachability table in the class documentation")
                    .isZero();
            assertThat(countOrphans(fixture, 0, 16, cardKeys))
                    .as("every xref_card_num is present among the card keys. This one is a FIXTURE PROPERTY "
                            + "ONLY, with nothing enforcing it: there is no foreign key from xref_card_num to "
                            + "card.card_num, as the constraint inventory above shows")
                    .isZero();
            assertThat(columnValues(fixture, 25, 36).size())
                    .as("the 50 account ids are distinct, so the fixture holds one cross reference per "
                            + "account. FIXTURE PROPERTY ONLY - the alternate key is NON-UNIQUE per "
                            + "app/catlg/LISTCAT.txt:488 and the schema permits many, which the alternate-key "
                            + "group proves by storing a second one")
                    .isEqualTo(50);
        }
    }
}
