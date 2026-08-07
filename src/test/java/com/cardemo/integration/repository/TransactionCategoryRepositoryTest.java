/*
 * ******************************************************************
 * Program     : TransactionCategoryRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Testcontainers PostgreSQL 16)
 * Function    : Exercises the TRANCATG cluster replacement against a real
 *               PostgreSQL engine - the six-byte composite key through
 *               Hibernate, the eighteen seeded reference rows, the inbound
 *               foreign key to transaction_type, and the primary-key column
 *               order that reproduces the legacy VSAM browse.
 * Source      : app/cpy/CVTRA04Y.cpy (TRAN-CAT-KEY 6 B, RECLN 60) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:1475 (KEYLEN 6, AVGLRECL 60) @ 7756d89
 * Source      : app/jcl/TRANCATG.jcl (KEYS(6 0), RECORDSIZE(60 60)) @ 7756d89
 * Source      : app/cbl/CBTRN03C.cbl (sole consumer of the layout) @ 7756d89
 * Source      : app/data/ASCII/trancatg.txt (18 rows of 60 bytes) @ 7756d89
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

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.TransactionCategoryRepository;

import jakarta.persistence.PersistenceException;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that {@link TransactionCategoryRepository} reproduces the {@code TRANCATG} VSAM KSDS cluster
 * against a real PostgreSQL 16 engine, and that its six-byte composite key behaves through Hibernate the
 * way the legacy key behaved through VSAM.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the first composite-key test in the tier and the second node of the schema's foreign-key
 * graph: {@code transaction_category} depends on {@code transaction_type} and is itself depended upon by
 * {@code "transaction"}, {@code transaction_category_balance} and {@code disclosure_group}. It asserts only
 * what a database can prove - that a freshly constructed identifier round-trips to the right row, that the
 * primary-key column order yields the legacy browse order, and that the two constraints PostgreSQL actually
 * carries are enforced. Everything upstream of the persistence seam is asserted elsewhere; see
 * <em>Scope boundaries</em> below.
 *
 * <p>The layout is {@code app/cpy/CVTRA04Y.cpy}, whose header at {@code :L2} reads
 * {@code Data-structure for transaction category type (RECLN = 60)} and whose body at {@code :L4-L9}
 * declares {@code 01 TRAN-CAT-RECORD} containing {@code 05 TRAN-CAT-KEY} at {@code :L5} over
 * {@code 10 TRAN-TYPE-CD PIC X(02)} at {@code :L6} and {@code 10 TRAN-CAT-CD PIC 9(04)} at {@code :L7},
 * then {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)} at {@code :L8} and {@code 05 FILLER PIC X(04)} at
 * {@code :L9}. The arithmetic closes exactly: {@code 2 + 4 = 6} key bytes and {@code 6 + 50 + 4 = 60}.
 *
 * <p>Three independent sources corroborate that geometry, which is why the numbers below are asserted as
 * facts rather than as expectations:
 *
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L1440} names the cluster
 *       {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}; {@code :L1475} reads
 *       {@code KEYLEN-----------------6     AVGLRECL--------------60}; {@code :L1476} reads
 *       {@code RKP--------------------0     MAXLRECL--------------60}, so the key is the record prefix;
 *       {@code :L1477} reports the cluster {@code UNIQUE} and {@code INDEXED} with no {@code NONUNIQKEY}
 *       line anywhere in the block; and {@code :L1482} reports {@code REC-TOTAL-------------18}.</li>
 *   <li>{@code app/jcl/TRANCATG.jcl} provisions it with {@code KEYS(6 0)} and
 *       {@code RECORDSIZE(60 60)}.</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} is 1098 bytes of eighteen 60-character records plus eighteen
 *       line feeds. Its first record is {@code 010001Regular Sales Draft} and its last is
 *       {@code 070001Sales draft credit adjustment}.</li>
 * </ul>
 *
 * <p><strong>The table is batch-only, and that is evidence rather than an assumption.</strong>
 * {@code app/csd/CARDDEMO.CSD} carries exactly eight {@code DEFINE FILE} entries - {@code ACCTDAT},
 * {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and
 * {@code USRSEC} - and the string {@code TRANCATG} occurs in it zero times. There is therefore no online
 * file definition, no transaction, no endpoint and no controller CRUD over this table, so nothing in this
 * class asserts a REST surface. The single consumer in the whole corpus is
 * {@code app/cbl/CBTRN03C.cbl}, which reads the category description to render report lines twenty to a
 * page ({@code app/cbl/CBTRN03C.cbl:L127-L137} declares {@code WS-PAGE-SIZE ... VALUE 20}). The negative
 * evidence is equally decisive: {@code app/cbl/CBTRN02C.cbl} performs three keyed reads - the
 * cross-reference by card number, the account by identifier, and the category balance by composite key -
 * and never reads {@code TRANCATG} or {@code TRANTYPE} at all.
 *
 * <h2>The TRAN-CAT-KEY name collision</h2>
 *
 * <p>The COBOL group name {@code TRAN-CAT-KEY} is declared twice in the corpus over two entirely
 * different keys, and confusing them is the defining hazard of this file:
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA04Y.cpy:L5} - <strong>this</strong> key: 6 bytes, two components,
 *       {@code TRAN-} field prefix, record {@code TRAN-CAT-RECORD} at {@code RECLN = 60}, cluster
 *       {@code TRANCATG}, {@code KEYLEN 6}. Java type {@link TransactionCategoryId}, whose accessors are
 *       spelled {@code getTranTypeCd()} and {@code getTranCatCd()}.</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy:L5} - a <strong>different</strong> key: 17 bytes, three components,
 *       {@code TRANCAT-} field prefix, record {@code TRAN-CAT-BAL-RECORD} at {@code RECLN = 50}, cluster
 *       {@code TCATBALF}, {@code KEYLEN 17}. Java type {@code TransactionCategoryBalanceId}, whose
 *       accessors are spelled {@code getTypeCd()} and {@code getCatCd()}.</li>
 * </ul>
 *
 * <p>The two Java types share no base class, no interface and no helper, and neither substitutes for the
 * other. The accessor spellings are deliberately inconsistent across the three composite-key classes and
 * must not be harmonised. This class never references
 * {@code TransactionCategoryBalanceId}; the table it keys is owned by its own test.
 *
 * <h2>The intentional 01/0005 gap - do not "fix" it</h2>
 *
 * <p>Eighteen category combinations are seeded here. {@code app/data/ASCII/discgrp.txt} seeds seventeen
 * rows whose account group identifier is the literal {@code DEFAULT}. The arithmetic
 * {@code 18 - 17 = 1} leaves exactly one category with no default interest rate: {@code 01} / {@code 0005},
 * whose description is {@code Interest Amount}. That is intentional and correct.
 * {@code 01}/{@code 0005} is the interest job's <em>output</em> category - the literal pair
 * {@code app/cbl/CBACT04C.cbl} stamps onto every transaction it generates - and never an input to a rate
 * lookup, so it needs no disclosure-group row. Adding a {@code 010005 DEFAULT} row anywhere would be
 * fabrication, and asserting that every category has a matching disclosure group would fail against a
 * faithful seed. This class therefore documents the gap and asserts neither.
 *
 * <h2>The CHAR(50) blank-pad policy, stated once and applied throughout</h2>
 *
 * <p>{@code tran_cat_type_desc} is created as {@code CHAR(50)} and mapped with
 * {@code JdbcTypeCode(SqlTypes.CHAR)}, so PostgreSQL blank-pads it and the JDBC text output for
 * {@code bpchar} carries that padding through to the mapped {@code String}. Measured on the seeded row
 * {@code 010001}: {@code octet_length} is 50 and {@code pg_column_size} is 51, while the SQL
 * {@code length()} function reports 19 only because it and the {@code bpchar}-to-{@code text} cast both
 * discard trailing blanks.
 *
 * <p><strong>Policy: this class compares against the full fifty-character blank-padded form and never
 * trims.</strong> Every expectation is widened by {@link #padToDescriptionWidth(String)}, and the padded
 * width is asserted rather than assumed, so the {@code CHAR(50)} contract is proved by the same
 * assertions that check the content. Trimming would have been the alternative and is not used, because a
 * trim would silently pass against a {@code VARCHAR} column and so would stop detecting a change of column
 * type.
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li>Whole build, exactly as continuous integration runs it: {@code ./mvnw clean verify}.</li>
 *   <li><strong>This class is bound to Failsafe, not Surefire.</strong> The root {@code pom.xml}
 *       configures {@code maven-failsafe-plugin} 3.5.4 with the includes
 *       {@code **}{@code /integration/}{@code **}{@code /*Test.java} and the {@code e2e} equivalent, even
 *       though these classes keep the {@code Test} suffix, while {@code maven-surefire-plugin} 3.5.4
 *       excludes {@code **}{@code /integration/}{@code **} outright. It therefore runs at
 *       {@code integration-test} and is verified at {@code verify}. <strong>Do not rename or relocate this
 *       class.</strong> Moved one package up it would match neither include set and would be collected by
 *       neither plugin - a green build in which this file silently never runs, which is the worst
 *       available failure mode because it produces no error and no output.</li>
 *   <li><strong>A reachable Docker socket is a hard prerequisite.</strong> The superclass starts a real
 *       PostgreSQL container, so without a daemon the context cannot refresh and every method here errors
 *       out. Docker Engine and {@code docker compose} are present in this environment and were re-checked
 *       at execution; the older claim that no container runtime exists is stale.</li>
 *   <li>When the host has no {@code java} or {@code mvn} on the path, run Maven through the pinned image
 *       instead: {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q
 *       verify}.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>All of it is inherited from {@link AbstractRepositoryIntegrationTest} and none of it is redeclared
 * here. That class owns the single digest-pinned PostgreSQL 16 container together with its
 * {@code ServiceConnection}, the {@code SpringBootTest} context, the {@code test} profile, the
 * {@code Testcontainers} extension, the class-level {@code Transactional} rollback that isolates every
 * method, the {@code DynamicPropertySource} registrations, the fixed UTC clock published as the context's
 * primary {@code java.time.Clock}, and the flat-classpath fixture reader. This subclass declares no
 * container, no {@code SpringBootTest}, no {@code DynamicPropertySource} and no {@code static} field of any
 * kind.
 *
 * <p>The profile settings this class depends on: Hibernate {@code ddl-auto: validate}, so a context that
 * starts at all has already proved the mapping matches the DDL; {@code open-in-view: false};
 * {@code show-sql: false}; the JDBC time zone {@code UTC}; {@code spring.batch.job.enabled: false}; and
 * Flyway with {@code validate-on-migrate: true}, {@code clean-disabled: true}, {@code out-of-order: false}
 * and {@code baseline-on-migrate: false}. There are exactly three migrations -
 * {@code V1__create_schema.sql}, {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} - and the
 * {@code BATCH_*} tables come from {@code spring.batch.jdbc.initialize-schema} rather than from a fourth
 * migration.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><strong>Every method errors before its first assertion.</strong> The Docker socket is
 *       unreachable. Start the daemon; nothing in this tier can substitute for it.</li>
 *   <li><strong>Testcontainers coordinates fail to resolve.</strong> The library is
 *       pinned to 2.0.3, which renamed every module artefact. Two remedies are required together and both
 *       are already present in the root {@code pom.xml}: the managed version is overridden through the
 *       {@code testcontainers.version} property - never by importing a second bill of materials, because
 *       Spring Boot 3.5.11 already imports the 1.x one and a competing import resolves in an
 *       order-dependent way - and only the prefixed artefact identifiers {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter} are used, because the bare {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} identifiers do not exist at 2.0.3. Overriding without
 *       renaming resolves artefacts that are not published; renaming without overriding resolves the wrong
 *       version. Repair the build descriptor, never this file.</li>
 *   <li><strong>The build fails on a warning.</strong> {@code maven-compiler-plugin} runs at release 25
 *       with {@code -Xlint:all -Werror} and {@code failOnWarning}, so a single unused import is fatal.
 *       Note also that {@code -Xlint} includes {@code dangling-doc-comments}: the provenance banner above
 *       is a plain block comment for exactly that reason, and turning it into a documentation comment would
 *       break the build.</li>
 *   <li><strong>The context aborts on a JDBC type-code mismatch.</strong> {@code validate} compares type
 *       codes, so an {@code Integer} over {@code NUMERIC(4)} can fail where {@code INTEGER} passes; the key
 *       class pins {@code columnDefinition = "numeric(4)"} to settle it. If this recurs, the fix belongs
 *       upstream in {@code V1__create_schema.sql} or in the entity or key class. Never widen a column
 *       arbitrarily and never patch the assertion.</li>
 *   <li><strong>An assertion fails on the wrong identifier type.</strong> Confirm the test is using
 *       {@link TransactionCategoryId} and not {@code TransactionCategoryBalanceId}; see the collision
 *       section above.</li>
 *   <li><strong>The foreign-key assertion looks wrong.</strong> The parent column is {@code tran_type},
 *       not {@code tran_type_cd}. {@code app/cpy/CVTRA03Y.cpy:L5} declares the field as
 *       {@code TRAN-TYPE}, the only such spelling in the corpus, so the two sides of
 *       {@code fk09_category_type} are named asymmetrically by design.</li>
 *   <li><strong>A confusing null dereference while reading a fixture.</strong> The name must be the bare
 *       classpath-root name of one of the nine catalogued fixtures. The shared reader rejects an unknown
 *       name loudly rather than returning nothing, which is what keeps a typo from becoming a failure far
 *       from its cause.</li>
 * </ol>
 *
 * <h2>Scope boundaries: what this tier deliberately does not assert</h2>
 *
 * <ul>
 *   <li>No {@code Version} column. {@code transaction_category} has none; the optimistic-lock column
 *       exists on exactly four entities, and this is not one of them.</li>
 *   <li>No index. {@code V2__create_indexes.sql} creates exactly three non-unique B-tree indexes, over
 *       {@code card(card_acct_id)}, {@code card_cross_reference(xref_acct_id)} and
 *       {@code "transaction"(tran_proc_ts)}. None is on this table and no fourth is asserted.</li>
 *   <li>No check constraint. {@code V1} declares exactly five and none is on this table. Confirmed
 *       against the live catalogue: this table carries exactly two constraints,
 *       {@code pk_transaction_category} and {@code fk09_category_type}.</li>
 *   <li>No mapped association to {@code TransactionType}, and no expectation of
 *       {@code AttributeOverride}, {@code MapsId} or {@code IdClass}. The relationship is a
 *       database-level constraint only; asserting a mapped association would break parity.</li>
 *   <li>No outbound foreign keys. The three constraints that point <em>at</em> this table belong to the
 *       tests that own {@code "transaction"}, {@code transaction_category_balance} and
 *       {@code disclosure_group}.</li>
 *   <li>No key-class unit suite. Equality symmetry and transitivity, hash stability, cross-type
 *       inequality against {@code TransactionCategoryBalanceId}, the serialisation identifier and the
 *       full construction-guard matrix are owned by {@code com.cardemo.unit.model} and are not repeated
 *       here. Where this class relies on the identifier's value semantics it does so as a tool for a
 *       persistence assertion, never as the subject of one.</li>
 * </ul>
 *
 * <h2>Evidence gaps, stated plainly</h2>
 *
 * <ol>
 *   <li><strong>The end-to-end parity baseline is Not available.</strong> No captured mainframe output
 *       exists anywhere in this repository; searching for expected, baseline and golden artefacts, for
 *       {@code .out} and system-output captures, and for the reject, report, statement and HTML dataset
 *       names returns dataset <em>definition</em> job control only and zero captured data. What is needed
 *       is a captured 430-byte reject dataset together with the resulting transaction, account and
 *       category-balance images from a real posting run at a known input state, which would corroborate the
 *       source-derived expectation rather than replace it. This class still creates no baseline file and
 *       fabricates no expected bytes, and a baseline generated from this Java implementation would still be
 *       circular. But the further claim an earlier revision made - that "two models over these same fixtures
 *       disagree on the reject count, which proves the count is model-sensitive and not an oracle" - is
 *       <strong>withdrawn</strong>. {@code 2800-UPDATE-ACCOUNT-REC} ends in {@code REWRITE FD-ACCTFILE-REC}
 *       at {@code app/cbl/CBTRN02C.cbl:561}, and a VSAM {@code REWRITE} replaces the record in the cluster,
 *       so the next {@code READ} of that key returns the mutated accumulators: the stateless reading is a
 *       misreading rather than a second model. Exactly one faithful model exists and
 *       {@code com.cardemo.e2e.PostingParityOracle} re-derives it into
 *       {@code src/test/resources/expected/posttran}. The reason no posting outcome is asserted <em>here</em>
 *       is scope - this class owns a reference table, not the posting job.</li>
 *   <li><strong>The file-unavailable status is Not available.</strong> The literal file status for a
 *       closed file occurs zero times across {@code app/cbl}, as does the equivalent condition name, so
 *       there is no source behaviour to reproduce and no test for it is invented here.</li>
 *   <li><strong>A provoked duplicate-key violation is unreachable through this repository, so uniqueness
 *       is proved differently.</strong> The entity carries an assigned {@code EmbeddedId} and no version
 *       attribute, so Spring Data treats every instance as already persistent and routes
 *       {@code save} through {@code merge}. On a key that exists, {@code merge} selects the row and issues
 *       an update; only on an absent key does it insert. No {@code persist} seam is exposed - the
 *       superclass keeps its persistence context private and publishes only
 *       {@link AbstractRepositoryIntegrationTest#flushAndClear()} - and mutating a managed entity's
 *       identifier to force a collision is undefined behaviour that would make the assertion
 *       non-deterministic. Uniqueness is therefore proved by what the database does demonstrate: a write
 *       carrying an already-seeded key collapses onto the same single row rather than adding a
 *       nineteenth, and the eighteen seeded keys are eighteen distinct values. Reaching a raw duplicate
 *       insert would need a {@code persist}-capable seam that this tier deliberately does not offer.</li>
 *   <li><strong>An over-wide type code cannot reach the column, so the width contract is proved from the
 *       other side.</strong> {@code tran_type_cd} is created as {@code VARCHAR(2)} at
 *       {@code V1__create_schema.sql} - the parent {@code transaction_type.tran_type} is the
 *       {@code CHAR(2)} of the pair - and a three-character value would indeed overflow it. It never
 *       arrives: {@link TransactionCategoryId} refuses to construct such a value at all, and that guard
 *       is owned and exercised by {@code com.cardemo.unit.model.TransactionCategoryIdTest}, so repeating
 *       it here would duplicate another tier's assertion rather than add one. This class instead proves
 *       the width positively at the persistence seam - every seeded code round-trips at exactly two
 *       characters, carried verbatim with its leading zero intact, which an integer mapping would have
 *       destroyed.</li>
 * </ol>
 *
 * <p>One further observation, recorded because it looks like a defect and is not. The
 * trailing {@code FILLER} in this fixture is zero-filled - four literal {@code 0} characters - as it is in
 * the disclosure-group, category-balance and transaction-type fixtures, whereas the account, customer,
 * card and daily-transaction fixtures space-fill theirs. Nothing here reads those bytes either way, and
 * the consequence is only that this fixture has no trailing-space run for an editor to strip. The
 * no-edit discipline over {@code app/} and over the copied fixtures is uniform regardless.
 *
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see AbstractRepositoryIntegrationTest
 */
@DisplayName("TransactionCategoryRepository against PostgreSQL 16 - the six-byte TRAN-CAT-KEY of CVTRA04Y")
class TransactionCategoryRepositoryTest extends AbstractRepositoryIntegrationTest {

    /*
     * WHY THESE CONTRACT VALUES ARE INSTANCE FIELDS AND NOT static final CONSTANTS.
     *
     * Every one of them would conventionally be a private static final constant. They are deliberately
     * plain instance fields because this class documents, as an invariant it shares with its superclass,
     * that it declares no static field of any kind: the only static state in the tier is the one shared
     * container the superclass owns, and keeping it that way is what stops a second, competing piece of
     * JVM-wide state from appearing here. They are final, are assigned from literals, and are never
     * mutated, so they carry none of the global mutable state Rule 1 clause B rules out. Please do not
     * "promote" them to static.
     */

    /** Rows the frozen fixture carries, corroborated by {@code app/catlg/LISTCAT.txt:L1482}. */
    private final int seededRowCount = 18;

    /** Rows in the parent table, from {@code app/data/ASCII/trantype.txt} at 427 bytes of 60. */
    private final int transactionTypeRowCount = 7;

    /** Record width from {@code app/cpy/CVTRA04Y.cpy:L2} and {@code app/catlg/LISTCAT.txt:L1475}. */
    private final int recordLength = 60;

    /** Composite key width: {@code X(02)} plus {@code 9(04)}, the {@code KEYLEN 6} of the catalogue. */
    private final int compositeKeyLength = 6;

    /** Width of {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA04Y.cpy:L6}. */
    private final int typeCodeLength = 2;

    /** Width of {@code TRAN-CAT-TYPE-DESC PIC X(50)} at {@code app/cpy/CVTRA04Y.cpy:L8}. */
    private final int descriptionLength = 50;

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Renders a row's composite key back into the six-character form the frozen fixture stores it in, so
     * that a value read from PostgreSQL can be compared against fixture bytes directly.
     *
     * <p>The rendering is {@code TRAN-TYPE-CD} verbatim followed by {@code TRAN-CAT-CD} as four
     * zero-padded digits, which is exactly the byte order {@code app/cpy/CVTRA04Y.cpy:L6-L7} declares and
     * exactly what {@code app/catlg/LISTCAT.txt:L1476} places at relative key position zero. The padding
     * is what makes the comparison meaningful: {@code PIC 9(04)} is a fixed-width display field, so the
     * fixture holds {@code 0001} where the mapped {@link Integer} holds {@code 1}.
     *
     * <p>{@link Locale#ROOT} is passed explicitly rather than relying on the default locale, because
     * {@code %d} otherwise renders through the ambient locale's digit set and the assertion would depend
     * on the machine running the build.
     *
     * @param category a row read from the repository; must not be {@code null} and must carry a populated
     *                 identifier, which every persisted row does
     * @return the six-character key image, for example {@code 010001}; never {@code null}
     */
    private String renderCompositeKey(final TransactionCategory category) {
        final TransactionCategoryId key = category.getId();
        return key.getTranTypeCd() + String.format(Locale.ROOT, "%04d", key.getTranCatCd());
    }

    /**
     * Widens an expected description to the full fifty characters of the {@code CHAR(50)} column.
     *
     * <p>This is the single place the blank-pad policy documented on the class is applied. PostgreSQL
     * stores {@code tran_cat_type_desc} blank-padded and the mapped {@code String} carries that padding,
     * so an expectation written as a bare literal would never match. Widening the expectation rather than
     * trimming the actual value keeps the width itself under test.
     *
     * @param text the unpadded description exactly as {@code app/data/ASCII/trancatg.txt} spells it; must
     *             not be {@code null} and must not exceed the fifty characters
     *             {@code TRAN-CAT-TYPE-DESC PIC X(50)} can hold
     * @return {@code text} followed by enough spaces to reach fifty characters; never {@code null}
     * @throws IllegalArgumentException if {@code text} is longer than the source field can hold, which
     *                                  would mean the expectation itself contradicts
     *                                  {@code app/cpy/CVTRA04Y.cpy:L8}
     */
    private String padToDescriptionWidth(final String text) {
        if (text.length() > descriptionLength) {
            throw new IllegalArgumentException("An expected description cannot exceed the "
                    + descriptionLength + " characters TRAN-CAT-TYPE-DESC PIC X(50) holds at "
                    + "app/cpy/CVTRA04Y.cpy:L8, but this one is " + text.length() + ": [" + text + "]");
        }
        return text + " ".repeat(descriptionLength - text.length());
    }

    /**
     * Reads the six-character key prefixes out of a fixture's records, in file order and without altering
     * a byte.
     *
     * <p>{@code app/catlg/LISTCAT.txt:L1476} reports the relative key position as zero, so the key is the
     * record prefix and a substring from the front is the whole of it. No record is trimmed, normalised or
     * re-encoded, because these are fixed-width images in which a trailing space would be data.
     *
     * @param resourceName bare classpath-root name of one of the nine catalogued fixtures
     * @param width        number of leading characters the key occupies in that fixture
     * @return the key images in file order; never {@code null}
     */
    private List<String> fixtureKeyPrefixes(final String resourceName, final int width) {
        return readFixture(resourceName).stream()
                .map(record -> record.substring(0, width))
                .toList();
    }

    /** The eighteen seeded rows, the fixture that produced them and the parent they depend on. */
    @Nested
    @DisplayName("the seeded reference rows and the frozen fixtures behind them")
    final class SeededReferenceRows {

        @Test
        @DisplayName("the context starts, which proves all three migrations applied and the mapping validates")
        void theContextStartsAndTheMappingValidates() {
            assertThat(transactionCategoryRepository)
                    .as("an injected repository proves Flyway applied V1, V2 and V3 and that Hibernate "
                            + "ddl-auto=validate accepted the CVTRA04Y mapping against the real DDL")
                    .isNotNull();
        }

        @Test
        @DisplayName("the seed loaded exactly the eighteen categories the frozen fixture carries")
        void theSeedLoadedExactlyEighteenRows() {
            assertThat(transactionCategoryRepository.count())
                    .as("app/data/ASCII/trancatg.txt is 1098 bytes of 60-character records plus 18 line "
                            + "feeds, so 18 rows, and app/catlg/LISTCAT.txt:L1482 independently reports "
                            + "REC-TOTAL 18 for AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS")
                    .isEqualTo(seededRowCount);
        }

        @Test
        @DisplayName("every fixture record is exactly sixty characters, the RECLN the copybook header states")
        void everyFixtureRecordIsSixtyCharactersWide() {
            final List<String> fixture = readFixture("trancatg.txt");

            assertThat(fixture)
                    .as("app/cpy/CVTRA04Y.cpy:L2 states RECLN = 60 and app/catlg/LISTCAT.txt:L1475 reads "
                            + "AVGLRECL 60, with app/jcl/TRANCATG.jcl provisioning RECORDSIZE(60 60)")
                    .hasSize(seededRowCount)
                    .allSatisfy(record -> assertThat(record).hasSize(recordLength));
        }

        @Test
        @DisplayName("the eighteen fixture key prefixes are exactly the eighteen keys now in the table")
        void theFixtureKeyPrefixesAreExactlyTheKeysInTheTable() {
            final Set<String> fixtureKeys =
                    Set.copyOf(fixtureKeyPrefixes("trancatg.txt", compositeKeyLength));
            final Set<String> tableKeys = transactionCategoryRepository.findAll().stream()
                    .map(TransactionCategoryRepositoryTest.this::renderCompositeKey)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(fixtureKeys)
                    .as("the six-byte key is the record prefix per app/catlg/LISTCAT.txt:L1476 RKP 0, so "
                            + "18 records yield 18 distinct key images")
                    .hasSize(seededRowCount);
            assertThat(tableKeys)
                    .as("V3__seed_data.sql decoded app/data/ASCII/trancatg.txt without gaining, losing or "
                            + "altering a key; the first is 010001 and the last is 070001")
                    .isEqualTo(fixtureKeys);
        }

        @Test
        @DisplayName("every type code used is one of the seven transaction types, which is what makes the "
                + "foreign key satisfiable")
        void everyTypeCodeUsedIsOneOfTheSevenTransactionTypes() {
            final Set<String> parentKeys =
                    Set.copyOf(fixtureKeyPrefixes("trantype.txt", typeCodeLength));
            final Set<String> typeCodesUsed =
                    Set.copyOf(fixtureKeyPrefixes("trancatg.txt", typeCodeLength));

            assertThat(parentKeys)
                    .as("app/data/ASCII/trantype.txt is 427 bytes of 60-character records plus 7 line "
                            + "feeds, so 7 rows, keyed by TRAN-TYPE PIC X(02) at app/cpy/CVTRA03Y.cpy:L5")
                    .hasSize(transactionTypeRowCount);
            assertThat(typeCodesUsed)
                    .as("the fixture-level proof that fk09_category_type is satisfiable: every "
                            + "TRAN-TYPE-CD in trancatg.txt is a TRAN-TYPE in trantype.txt, so the seed "
                            + "can load in the order transaction_type then transaction_category")
                    .isSubsetOf(parentKeys);
        }
    }

    /**
     * The part of the composite-key contract that only a database can prove: that a value-equal identifier
     * built from scratch resolves the right row through Hibernate.
     */
    @Nested
    @DisplayName("the six-byte composite key resolved through Hibernate")
    final class CompositeKeyRoundTrip {

        @Test
        @DisplayName("a freshly built (\"01\", 1) identifier resolves the first seeded category")
        void aFreshlyBuiltFirstIdentifierResolvesItsRow() {
            final Optional<TransactionCategory> located =
                    transactionCategoryRepository.findById(new TransactionCategoryId("01", 1));

            assertThat(located)
                    .as("app/data/ASCII/trancatg.txt line 1 reads 010001Regular Sales Draft, so the 2 and "
                            + "4 split of TRAN-TYPE-CD and TRAN-CAT-CD must resolve it; the identifier is "
                            + "constructed here rather than read off a row, which is what proves its value "
                            + "semantics are wired through the provider")
                    .isPresent();
            assertThat(located.orElseThrow().getCategoryDescription())
                    .as("TRAN-CAT-TYPE-DESC PIC X(50) over a CHAR(50) column, compared against the full "
                            + "blank-padded form per the policy on this class")
                    .isEqualTo(padToDescriptionWidth("Regular Sales Draft"));
        }

        @Test
        @DisplayName("a freshly built (\"07\", 1) identifier resolves the last seeded category")
        void aFreshlyBuiltLastIdentifierResolvesItsRow() {
            final Optional<TransactionCategory> located =
                    transactionCategoryRepository.findById(new TransactionCategoryId("07", 1));

            assertThat(located)
                    .as("app/data/ASCII/trancatg.txt line 18, the last record, reads "
                            + "070001Sales draft credit adjustment")
                    .isPresent();
            assertThat(located.orElseThrow().getCategoryDescription())
                    .as("the highest key in the cluster, resolved by the same two-component identifier")
                    .isEqualTo(padToDescriptionWidth("Sales draft credit adjustment"));
        }

        @Test
        @DisplayName("a composite key outside the seeded space resolves to nothing rather than throwing")
        void anAbsentCompositeKeyResolvesToEmpty() {
            final TransactionCategoryId absent = new TransactionCategoryId("01", 9999);

            assertThatCode(() -> transactionCategoryRepository.findById(absent))
                    .as("a keyed miss is a control path in the source rather than an error - CBTRN03C "
                            + "treats the not-found status on this cluster as data it can report on - so "
                            + "the finder must not raise")
                    .doesNotThrowAnyException();
            assertThat(transactionCategoryRepository.findById(absent))
                    .as("9999 is the largest value TRAN-CAT-CD PIC 9(04) can hold and is inside the "
                            + "identifier's domain, yet no such row is seeded, so the result is an empty "
                            + "Optional and never a default-valued row")
                    .isEmpty();
        }

        @Test
        @DisplayName("the description arrives blank-padded to the full fifty characters of its CHAR(50) column")
        void theDescriptionArrivesBlankPaddedToFifty() {
            final TransactionCategory interestAmount = transactionCategoryRepository
                    .findById(new TransactionCategoryId("01", 5))
                    .orElseThrow();

            assertThat(interestAmount.getCategoryDescription())
                    .as("PostgreSQL blank-pads CHAR(50) and the bpchar JDBC output carries the padding "
                            + "through, measured as octet_length 50 on this very row; the width is "
                            + "asserted rather than assumed so that a change of column type is caught")
                    .hasSize(descriptionLength)
                    .startsWith("Interest Amount")
                    .isEqualTo(padToDescriptionWidth("Interest Amount"));
        }

        @Test
        @DisplayName("every type code round-trips at exactly two characters with its leading zero intact")
        void everyTypeCodeRoundTripsAtExactlyTwoCharacters() {
            final List<TransactionCategory> all = transactionCategoryRepository.findAll();

            assertThat(all)
                    .as("the width contract is only meaningful over the whole seeded key space, so all 18 "
                            + "rows of app/data/ASCII/trancatg.txt must be in hand before it is checked")
                    .hasSize(seededRowCount);
            assertThat(all)
                    .as("TRAN-TYPE-CD PIC X(02) at app/cpy/CVTRA04Y.cpy:L6 over a VARCHAR(2) column: the "
                            + "width contract proved from the reachable side, since the identifier refuses "
                            + "to construct a wider value at all")
                    .allSatisfy(category -> assertThat(category.getId().getTranTypeCd())
                            .hasSize(typeCodeLength));
            assertThat(all)
                    .extracting(category -> category.getId().getTranTypeCd())
                    .as("the leading zero survives the round trip: 01 and 07 are two-character text, not "
                            + "the integers 1 and 7, which is what a numeric mapping would have destroyed")
                    .contains("01", "07");
        }
    }

    /**
     * The two constraints PostgreSQL genuinely carries on this table, and nothing else. Each method here
     * provokes hostile input at the persistence boundary rather than asserting a happy path.
     */
    @Nested
    @DisplayName("the constraints only a real PostgreSQL engine can enforce")
    final class EnforcedConstraints {

        @Test
        @DisplayName("an unknown transaction type code is refused by fk09_category_type, whose parent "
                + "column is tran_type and not tran_type_cd")
        void anUnknownTypeCodeIsRefusedByTheForeignKey() {
            final TransactionCategory orphan = new TransactionCategory(
                    new TransactionCategoryId("99", 1),
                    padToDescriptionWidth("Category of a type that does not exist"));

            transactionCategoryRepository.save(orphan);

            assertThatThrownBy(TransactionCategoryRepositoryTest.this::flushAndClear)
                    .as("V1__create_schema.sql declares CONSTRAINT fk09_category_type FOREIGN KEY "
                            + "(tran_type_cd) REFERENCES transaction_type (tran_type), the ninth of ten "
                            + "foreign keys. Note the asymmetric column names: the child column is "
                            + "tran_type_cd but the parent column is tran_type, because "
                            + "app/cpy/CVTRA03Y.cpy:L5 declares the field as TRAN-TYPE - the one such "
                            + "spelling in the corpus. The type code 99 is not among the seven codes "
                            + "app/data/ASCII/trantype.txt carries, so the insert must be refused")
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("transaction_category")
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk09_category_type");
        }

        @Test
        @DisplayName("the six-byte composite key identifies exactly one row, so a write carrying a seeded "
                + "key collapses onto it instead of adding a nineteenth")
        void theCompositeKeyIdentifiesExactlyOneRow() {
            final String rewritten = padToDescriptionWidth("Regular Sales Draft, rewritten");

            transactionCategoryRepository
                    .save(new TransactionCategory(new TransactionCategoryId("01", 1), rewritten));
            flushAndClear();

            assertThat(transactionCategoryRepository.count())
                    .as("pk_transaction_category over (tran_type_cd, tran_cat_cd) is genuinely unique - "
                            + "this is the base cluster, not an alternate index, and "
                            + "app/catlg/LISTCAT.txt:L1477 carries the UNIQUE attribute with no "
                            + "NONUNIQKEY line anywhere in the TRANCATG block - so a write on an existing "
                            + "key cannot produce a second row")
                    .isEqualTo(seededRowCount);

            final Optional<TransactionCategory> reread =
                    transactionCategoryRepository.findById(new TransactionCategoryId("01", 1));

            assertThat(reread)
                    .as("the one row the key identifies is still there after the flush and clear")
                    .isPresent();
            assertThat(reread.orElseThrow().getCategoryDescription())
                    .as("the write landed on that same row: the description is the rewritten one, read "
                            + "back from the database rather than from the identity map")
                    .isEqualTo(rewritten);
        }

        @Test
        @DisplayName("the eighteen seeded composite keys are eighteen distinct values, none repeated")
        void theSeededCompositeKeysAreAllDistinct() {
            final List<TransactionCategoryId> keys = transactionCategoryRepository.findAll().stream()
                    .map(TransactionCategory::getId)
                    .toList();

            assertThat(keys)
                    .as("the structural half of the uniqueness proof: a unique primary key cannot hold a "
                            + "repeated (tran_type_cd, tran_cat_cd) pair, and the seeded table does not")
                    .hasSize(seededRowCount)
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * Component-order fidelity, proved by ordering. These are the assertions that show the primary-key
     * column order matches COBOL field order and therefore reproduces the legacy VSAM key sequence.
     *
     * <p>The repository is a bare {@code JpaRepository} and exposes no ordered finder of its own, so the
     * order is requested through {@code Sort} rather than through an invented derived query. The property
     * path runs through the embedded identifier - {@code id.tranTypeCd} then {@code id.tranCatCd} - which
     * are the identifier's own attribute names and not the column names.
     */
    @Nested
    @DisplayName("the primary-key column order that reproduces the VSAM browse")
    final class VsamKeyOrder {

        @Test
        @DisplayName("the ordered read runs from 01/0001 to 07/0001 and reproduces the fixture's own "
                + "record order exactly")
        void theOrderedReadReproducesTheFixtureRecordOrder() {
            final List<TransactionCategory> ordered = transactionCategoryRepository
                    .findAll(Sort.by(Sort.Direction.ASC, "id.tranTypeCd", "id.tranCatCd"));

            assertThat(ordered)
                    .as("the ordered read must return the whole cluster, all 18 records of "
                            + "app/data/ASCII/trancatg.txt, before its first and last keys mean anything")
                    .hasSize(seededRowCount);
            assertThat(renderCompositeKey(ordered.getFirst()))
                    .as("app/data/ASCII/trancatg.txt line 1 reads 010001Regular Sales Draft, so the lowest "
                            + "key in the cluster is 010001")
                    .isEqualTo("010001");
            assertThat(renderCompositeKey(ordered.getLast()))
                    .as("app/data/ASCII/trancatg.txt line 18 reads 070001Sales draft credit adjustment, so "
                            + "the highest key in the cluster is 070001")
                    .isEqualTo("070001");
            assertThat(ordered.stream()
                    .map(TransactionCategoryRepositoryTest.this::renderCompositeKey)
                    .toList())
                    .as("the decisive proof that pk_transaction_category names its columns in the order "
                            + "app/cpy/CVTRA04Y.cpy:L6-L7 declares the fields: ordering by tran_type_cd "
                            + "then tran_cat_cd reproduces, record for record, the sequence the frozen "
                            + "VSAM-ordered fixture is already stored in")
                    .isEqualTo(fixtureKeyPrefixes("trancatg.txt", compositeKeyLength));
        }

        @Test
        @DisplayName("the per-type partition is exactly 5, 3, 3, 3, 1, 2, 1 across the seven type codes")
        void thePerTypePartitionMatchesTheFixture() {
            final Map<String, Long> partition = transactionCategoryRepository.findAll().stream()
                    .collect(Collectors.groupingBy(category -> category.getId().getTranTypeCd(),
                            Collectors.counting()));

            assertThat(partition)
                    .as("app/data/ASCII/trancatg.txt groups its 18 records as 5 of type 01, 3 of 02, 3 of "
                            + "03, 3 of 04, 1 of 05, 2 of 06 and 1 of 07, summing to 18; a Map comparison "
                            + "is used because it is insensitive to iteration order and so is "
                            + "deterministic")
                    .isEqualTo(Map.of("01", 5L, "02", 3L, "03", 3L, "04", 3L, "05", 1L, "06", 2L,
                            "07", 1L));
        }

        @Test
        @DisplayName("the zero-padded four-digit category code makes byte order and numeric order the "
                + "same, which is why an Integer mapping is safe here")
        void byteOrderAndNumericOrderAgree() {
            final List<TransactionCategory> ordered = transactionCategoryRepository
                    .findAll(Sort.by(Sort.Direction.ASC, "id.tranTypeCd", "id.tranCatCd"));

            assertThat(ordered)
                    .extracting(TransactionCategoryRepositoryTest.this::renderCompositeKey)
                    .as("TRAN-CAT-CD PIC 9(04) is stored as fixed-width unsigned display digits, so the "
                            + "lexicographic order of the six-byte VSAM key image is the same order as the "
                            + "numeric ordering the database applied; were it not, mapping the component "
                            + "to Integer would have changed the browse sequence the batch readers rely on")
                    .isSorted();
            assertThat(ordered.stream()
                    .map(TransactionCategory::getId)
                    .filter(key -> "01".equals(key.getTranTypeCd()))
                    .map(TransactionCategoryId::getTranCatCd)
                    .toList())
                    .as("within type 01 the fixture runs 010001 through 010005, so the decoded category "
                            + "codes are 1 to 5 ascending with no gap - the numeric half of the same fact")
                    .containsExactly(1, 2, 3, 4, 5);
        }
    }

    /**
     * The catalogue reader the metadata contract below is asserted through.
     *
     * <p>Injected rather than constructed so that it is bound to the very container the harness started and
     * the migrations were applied to. It issues catalogue reads only and mutates nothing.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The complete PostgreSQL metadata contract for the {@code transaction_category} table.
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
     * <p>For {@code transaction_category} that is three columns, the two-part composite key of the catalogue's KEYLEN 6, and fk09 onto the transaction type - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVTRA04Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the transaction category table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "transaction_category");
    }

}
