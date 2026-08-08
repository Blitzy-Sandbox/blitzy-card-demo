/*
 * ******************************************************************
 * Program     : DisclosureGroupRepositoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test - repository tier
 * Function    : Proves the persistence contract of the disclosure
 *               group rate table against a real PostgreSQL 16: the
 *               16-byte composite key in COBOL component order, the
 *               NUMERIC(6,2) rate that exists nowhere else in the
 *               schema, the CHAR(10) blank padding that lets the
 *               legacy 'DEFAULT' fallback match, the two-query
 *               fallback kept as two queries, the legitimacy of a
 *               zero rate, and the four constraint boundaries the
 *               database enforces.
 * Source      : app/cpy/CVTRA02Y.cpy:5-10 (DIS-GROUP-KEY 16 B,
 *               RECLN 50, DIS-INT-RATE S9(04)V99),
 *               app/catlg/LISTCAT.txt:894-901 (:896 KEYLEN 16
 *               AVGLRECL 50, :897 RKP 0 MAXLRECL 50, :898 UNIQUE
 *               with no NONUNIQKEY, :901 REC-TOTAL 51),
 *               app/jcl/DISCGRP.jcl (DEFINE CLUSTER KEYS(16 0)
 *               RECORDSIZE(50 50), STEP15 REPRO from DISCGRP.PS),
 *               app/cbl/CBACT04C.cbl:415-460 (two-stage rate lookup,
 *               DEFAULT fallback) and :214-217 (zero-rate skip),
 *               app/csd/CARDDEMO.CSD (DISCGRP absent: batch-only),
 *               app/data/ASCII/discgrp.txt, app/data/ASCII/acctdata.txt,
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

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.repository.DisclosureGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration contract for {@link DisclosureGroupRepository}, the rate table the interest calculation job
 * reads, exercised against a real PostgreSQL 16 instance supplied by
 * {@link AbstractRepositoryIntegrationTest}.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves the persistence behaviour of one legacy VSAM cluster,
 * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}, and nothing else. The physical specification is quadruply
 * sourced and every figure below was read from the frozen corpus at {@code 7756d89} rather than inherited
 * from prose:
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA02Y.cpy:L5-L10} declares the record: {@code DIS-ACCT-GROUP-ID PIC X(10)},
 *       {@code DIS-TRAN-TYPE-CD PIC X(02)}, {@code DIS-TRAN-CAT-CD PIC 9(04)},
 *       {@code DIS-INT-RATE PIC S9(04)V99} and {@code FILLER PIC X(28)}. The key is 10 + 2 + 4 = 16 bytes
 *       and the record is 16 + 6 + 28 = 50, which the header comment at {@code :L2} states outright as
 *       {@code RECLN = 50}.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L896} reports {@code KEYLEN 16} and {@code AVGLRECL 50} for that
 *       cluster, with {@code :L897} adding {@code RKP 0} and {@code MAXLRECL 50} - so the key is the record
 *       prefix and the record is fixed length. Citing {@code :L896} exactly matters, because {@code :L202}
 *       and {@code :L403} also report {@code KEYLEN 16} for entirely different clusters.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L898} declares the cluster {@code UNIQUE INDEXED} and carries
 *       <strong>no {@code NONUNIQKEY}</strong> attribute, which is what licenses the primary-key uniqueness
 *       assertion in {@link ConstraintBoundaries}. {@code :L901} independently reports
 *       {@code REC-TOTAL 51}, corroborating the seeded row count from the catalogue rather than from the
 *       fixture alone.</li>
 *   <li>{@code app/jcl/DISCGRP.jcl} defines the cluster with {@code KEYS(16 0)} and
 *       {@code RECORDSIZE(50 50)}, then loads it by {@code REPRO} from the flat file
 *       {@code AWS.M2.CARDDEMO.DISCGRP.PS}. There is no other write path in the corpus.</li>
 *   </ul>
 *
 * <p><strong>The table is batch-only, proved by absence.</strong> {@code app/csd/CARDDEMO.CSD} holds
 * exactly eight {@code DEFINE FILE} entries - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT},
 * {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - and the string
 * {@code DISCGRP} occurs in it <strong>zero</strong> times. The cluster was never reachable from a
 * terminal, so this class asserts nothing about a controller, an endpoint or a service: the target exposes
 * 17 operations across 8 controllers and not one of them touches this table.
 *
 * <h3>The CHAR(10) blank-pad policy, which is the defining contract of this file</h3>
 *
 * <p>{@code acct_group_id} is {@code CHAR(10)}, and that fixed width is <strong>load-bearing rather than
 * incidental</strong>. {@code app/cbl/CBACT04C.cbl:L437} executes
 * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}, and a COBOL {@code MOVE} of a seven-character literal
 * into a ten-byte alphanumeric field left-justifies and blank-pads, so the key bytes the source actually
 * presents are {@code DEFAULT} followed by three blanks. {@code app/data/ASCII/discgrp.txt:L18} stores
 * precisely that image.
 *
 * <p><strong>The single policy this class applies throughout: SQL {@code CHAR} comparison ignores trailing
 * blanks, so the padded and the bare form of a group id are the same value and both are asserted to
 * resolve the same row.</strong> Two consequences follow and both are asserted rather than assumed:
 *
 * <ol>
 *   <li><strong>On the way in</strong>, a probe may be padded or bare. This was measured rather than
 *       inferred, because the outcome depends on PostgreSQL operator resolution and not on JPA: a bound
 *       parameter arrives typed {@code varchar}, and {@code bpchar = varchar} resolves to
 *       <em>{@code bpchar}</em> comparison - a query plan for such a predicate renders it as
 *       {@code acct_group_id = 'DEFAULT   '::bpchar} - which is blank-insensitive. Only an explicit
 *       {@code ::text} cast would degrade it to text comparison, at which point the padded form stops
 *       matching. No such cast exists anywhere in this tier.</li>
 *   <li><p><strong>On the way out the behaviour is asymmetric, and the asymmetry was measured rather than
 *       assumed.</strong> The column always holds the padded ten-character image, and a
 *       <em>query-derived</em> read returns it: {@code findAll}, {@code findAll(Sort)} and the explicit
 *       fallback finder all yield {@code "DEFAULT   "}. But {@code findById} returns an entity whose
 *       {@link DisclosureGroupId} <strong>echoes the key the load was issued with</strong>, because JPA
 *       populates an {@code @EmbeddedId} from the identifier the caller supplied rather than from the
 *       selected columns. Probing with {@code "DEFAULT"} therefore yields a row whose
 *       {@link DisclosureGroupId#getAccountGroupId()} is the bare seven-character string, even though the
 *       stored bytes are padded and even though it is unambiguously the same row.</p>
 *       <p>Two consequences bind callers, and
 *       {@link DefaultGroupFallback#theLoadedIdentifierEchoesTheProbeWhileTheStoredImageStaysPadded()}
 *       pins both. First, two {@link DisclosureGroupId} values differing only in trailing blanks identify
 *       one row in SQL yet are <em>not</em> {@code equals} in Java, so a Java-side set, map or equality
 *       check over identifiers is no substitute for a keyed read. Second, an identifier obtained from
 *       {@code findById} must never be treated as the stored byte image: anything emitting fixed-width
 *       output must take the value from a query-derived read or re-pad it to the declared width.
 *       <strong>Severity: Medium</strong> - it misleads rather than corrupts, and it is harmless for the
 *       interest job itself, which consumes the rate and never re-emits the group id.</p>
 *       <p>A related trap, recorded because it returns a plausible wrong answer rather than an error: SQL
 *       {@code length()} and the {@code ||} operator both strip trailing blanks from a {@code bpchar}
 *       value, so {@code length(acct_group_id)} reports 7 for the padded fallback identifier.
 *       {@code octet_length} would be needed to observe the stored width, and no assertion here relies on
 *       either.</p></li>
 *   </ol>
 *
 * <p><strong>Severity: High.</strong> Relaxing the column to a variable-width type breaks the equivalence,
 * and which of the two forms still works then depends on whether the seed happened to preserve the
 * padding - a dependency easy to introduce by accident. If either form fails,
 * <strong>the fix is upstream</strong> in {@code V1__create_schema.sql} or in
 * {@link DisclosureGroupId}; padding a probe inside a test to make it pass would hide the regression the
 * assertion exists to catch.
 *
 * <h3>The two-stage lookup, and why the fallback is the normal path rather than an edge case</h3>
 *
 * <p>{@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415-L440} reads the file at
 * {@code :L416} under an {@code INVALID KEY} clause that merely displays
 * {@code 'DISCLOSURE GROUP RECORD MISSING'} and {@code 'TRY WITH DEFAULT GROUP CODE'} at
 * {@code :L418-L419}. {@code :L422} then tests {@code IF DISCGRP-STATUS = '00' OR '23'} and treats
 * <em>both</em> as success, so only a third, unexpected status reaches the abend path at
 * {@code :L431-L434}. Finally {@code :L436} tests {@code IF DISCGRP-STATUS = '23'} and on that status
 * alone substitutes the group id at {@code :L437} and performs stage two at {@code :L438}.
 *
 * <p>{@code 1200-A-GET-DEFAULT-INT-RATE} at {@code :L443-L460} issues a second read at {@code :L444} with
 * <strong>no {@code INVALID KEY} clause at all</strong>, and {@code :L446} accepts {@code '00'} and
 * nothing else - so a not-found status now falls through to {@code :L455} and abends at {@code :L458}.
 * <strong>A first miss is therefore a routine control path and a second miss is fatal.</strong>
 *
 * <p><strong>And the fallback is the system's default state, not a rare branch.</strong> Every one of the
 * 50 seeded accounts carries a blank {@code acct_group_id} - ten spaces, verified on all 50 rows of
 * {@code app/data/ASCII/acctdata.txt} at columns 113 to 122, and re-verified against the seeded
 * {@code account} table by {@link DefaultGroupFallback#theFallbackIsTheNormalPathBecauseEveryAccountGroupIsBlank()}.
 * A blank group id matches no rate row, so stage one misses for every account in the fixture and stage two
 * is the path actually taken in normal operation. A reader who believes the fallback is exceptional will
 * mis-prioritise it, which is why the causal chain is stated here rather than left to be inferred.
 *
 * <p><strong>Blocker: the two lookups must remain two lookups.</strong> {@link DisclosureGroupRepository}
 * exposes the inherited {@code findById} for stage one and the explicit
 * {@link DisclosureGroupRepository#findDefaultGroupRate(String, java.lang.Integer)} for stage two, and
 * this class asserts that they are separate call sites returning independently. Collapsing them into one
 * query - by null-coalescing, by a set operation, by a disjunction, or by a repository-level default -
 * would erase the distinction the two paragraphs draw: a merged query cannot report <em>which</em> stage
 * produced the row, so a benign miss would become indistinguishable from a fatal one and the fatal branch
 * would become unreachable.
 *
 * <h3>The three precision tiers, of which this table owns the third alone</h3>
 *
 * <p>{@code DIS-INT-RATE PIC S9(04)V99} is four integer digits and two decimals, so the column is
 * {@code NUMERIC(6,2)}. The tiers must never be conflated, and {@link RatePrecisionContract} proves the
 * distinction from catalogue metadata rather than asserting it:
 *
 * <pre>
 * COBOL picture   SQL column      where
 * S9(10)V99       NUMERIC(12,2)   the five account money and cycle columns
 * S9(09)V99       NUMERIC(11,2)   tran_amt, dalytran_amt, tran_cat_bal
 * S9(04)V99       NUMERIC(6,2)    dis_int_rate - this table, and nothing else in the schema
 * </pre>
 *
 * <p>Money and rates are {@link BigDecimal} throughout with {@code RoundingMode.HALF_EVEN}, and
 * <strong>no approximate binary numeric type appears anywhere in this file</strong>, which a security
 * audit gate asserts globally. <strong>Rates are compared with {@code compareTo}, never with
 * {@code equals}</strong>: {@code new BigDecimal("15.00").equals(new BigDecimal("15.0"))} is
 * {@code false} while {@code compareTo} returns {@code 0}, and PostgreSQL returns this column at scale 2,
 * so an {@code equals} comparison against a differently-scaled literal fails for a reason that has nothing
 * to do with the value. Every rate assertion here uses AssertJ's
 * {@code isEqualByComparingTo}. <strong>Severity of the {@code equals} trap: Medium</strong>, because it
 * fails loudly rather than silently.
 *
 * <h3>A zero rate is a value, never an absence</h3>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L214} guards the computation with {@code IF DIS-INT-RATE NOT = 0}, and
 * both {@code :L215} and {@code :L216} sit inside that guard, so a zero rate produces no interest
 * transaction while remaining a perfectly ordinary hit. {@code BigDecimal.ZERO} must never be conflated
 * with {@link Optional#empty()}: a present zero-rate row satisfies stage one and suppresses the fallback,
 * whereas an empty result triggers it. Treating zero as absent would drive accounts into stage two that
 * the source never sends there. <strong>Blocker:</strong> no non-zero, positive-only or range check
 * constraint may be added to {@code dis_int_rate}, and none exists - {@code V1} declares exactly five
 * check constraints and none of them is on this table.
 *
 * <p>The seed makes both outcomes provable. {@code app/data/ASCII/discgrp.txt} holds
 * <strong>51 rows of 50 bytes</strong> partitioned <strong>17 / 17 / 17</strong> across exactly three
 * group identifiers - {@code A000000000} at rows 1-17, {@code DEFAULT} at rows 18-34 and {@code ZEROAPR}
 * at rows 35-51 - and <strong>seven of the seventeen fallback rows carry a zero rate</strong>:
 * {@code 02/0001-0003}, {@code 03/0001-0003} and {@code 07/0001}. Those seven are the only corpus coverage
 * of the zero-rate skip branch. All 51 rate fields end in the overpunch {@code &#123;}, so every seeded
 * rate is non-negative, yet {@code PIC S9(04)V99} is a <em>signed</em> picture and a negative rate is
 * inside the source domain - which is why {@link RatePrecisionContract} asserts that a negative rate
 * persists and why no non-negativity constraint may be introduced.
 *
 * <h3>The intentional gap at 01/0005, which must be documented and not filled</h3>
 *
 * <p>{@code app/data/ASCII/trancatg.txt} lists <strong>18</strong> type-and-category pairs. The fallback
 * group covers <strong>17</strong> of them. The arithmetic 18 minus 17 leaves exactly one category with no
 * default rate: <strong>{@code 01} / {@code 0005}, "Interest Amount"</strong>. That is deliberate -
 * {@code 01/0005} is the interest job's <em>output</em> category, the code it stamps on the transactions it
 * generates, so it is never an input to a rate lookup. In fact no seeded rate row of <em>any</em> group
 * covers it, which {@link DefaultGroupFallback} asserts.
 *
 * <p><strong>Blocker: adding a {@code 010005} fallback row anywhere would be fabrication.</strong> Equally
 * forbidden is the inverse assertion that every category has a matching rate row, because it provably does
 * not.
 *
 * <h3>Scope: one concern, and the neighbours that own the rest</h3>
 *
 * <p>This tier asserts persistence, mapping, key order and constraint behaviour - what a database can
 * prove. It deliberately asserts none of the following, each of which is cited here and owned elsewhere:
 *
 * <ul>
 *   <li><strong>The abend.</strong> {@code app/cbl/CBACT04C.cbl:L455} and {@code :L458} escalate a second
 *       miss to abend code 999 with return code 12. That, the fatal exception type and the resulting exit
 *       status belong to the interest processor's unit tests.</li>
 *   <li><strong>The interest formula.</strong> {@code :L462-L470} computes
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} - multiply first, then divide by the single literal
 *       1200 with two-decimal {@code HALF_EVEN} rounding, never as a division by 100 followed by a
 *       division by 12 and never with a decimal multiplier, because both round differently. It belongs to
 *       the batch unit tier.</li>
 *   <li><strong>The stale-rate hazard.</strong> COBOL reads into a shared record area, so after an invalid
 *       key at {@code :L416} the previous iteration's record remains in place until the stage-two read at
 *       {@code :L444} overwrites it. <strong>Severity: High</strong> as an implementation hazard, but it is
 *       a processor concern: a caller must resolve the rate freshly per row and never memoise it. Nothing
 *       here caches, and this class holds no mutable state in which a rate could survive.</li>
 *   <li><strong>Key-class algebra.</strong> Equality and hash-code contracts, the 16 / 17 / 6 key widths,
 *       {@code serialVersionUID} and cross-type inequality are owned by the model unit tests.</li>
 *   <li><strong>Version columns and indexes.</strong> {@code version BIGINT} exists on exactly four tables
 *       and not this one; {@code V2} creates exactly three non-unique B-tree indexes and none on this
 *       table. No fourth index and no optimistic-lock assertion appears here.</li>
 *   </ul>
 *
 * <p>The sibling {@code RepositorySchemaAndFinderIntegrationTest} touches this table twice, shallowly: it
 * round-trips a row whose identifier it first read back from the table, and it asserts that at least one
 * fallback row exists. Neither constructs a probe literal, so neither can prove the blank-pad equivalence,
 * the partition, the exact rates, the gap or any constraint. This class is the table's comprehensive owner
 * and the two are complementary rather than duplicative.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify} runs the whole gate. <strong>This class is bound to Failsafe, not
 * Surefire, and the binding is by path:</strong> {@code maven-failsafe-plugin} 3.5.4 includes
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} and runs it at {@code integration-test} and
 * {@code verify} even though the class keeps the {@code Test} suffix, while
 * {@code maven-surefire-plugin} 3.5.4 owns {@code **}{@code /unit/}{@code **} and explicitly excludes this
 * tree. <strong>Do not rename or relocate this class.</strong> Moved up to the {@code integration} level,
 * to {@code com.cardemo}, or into {@code src/test/java} directly, it matches neither include set, is
 * collected by neither plugin and silently never runs - a green build, both plugins reporting success, no
 * error and no output. That is the worst available failure mode, which is why the location is stated as a
 * contract.
 *
 * <p><strong>A reachable Docker socket is a prerequisite</strong>, because the harness starts a real
 * PostgreSQL 16 container and a reaper container. Verified present for this run: Docker Engine 29.7.0 with
 * {@code /var/run/docker.sock}, OpenJDK 25.0.3 and Apache Maven 3.9.11 all on the host path, so the build
 * runs directly. Where a host JDK is absent the identical build runs in the pinned image,
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify}, and
 * produces the same result because every plugin and non-managed dependency version is pinned.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>This class reads no configuration and declares none. It contains no host, port, database name, user
 * name, password, JDBC URL or cloud endpoint, performs no environment-variable or system-property read and
 * no system-property write, and declares no container, no context annotation, no property source and
 * <strong>no {@code static} field of any kind</strong> - all of which the harness owns exactly once for the
 * whole tier.
 *
 * <ul>
 *   <li>Profile {@code test}; PostgreSQL 16 pinned by image digest by the harness; the injected fixed UTC
 *       clock is the single time source and the ambient clock is never read - see
 *       {@link AbstractRepositoryIntegrationTest#fixedClock()}. No assertion here depends on the current
 *       time at all, so the suite cannot fail on a second, day, month or year boundary.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate}, so the column names, SQL types, precisions and
 *       nullabilities asserted below are additionally enforced at context startup;
 *       {@code spring.jpa.open-in-view: false} and {@code spring.jpa.show-sql: false}.</li>
 *   <li>Exactly three Flyway migrations, applied in order with {@code validate-on-migrate: true},
 *       {@code clean-disabled: true}, {@code out-of-order: false} and {@code baseline-on-migrate: false}.
 *       The {@code BATCH_*} tables come from the framework's own script via
 *       {@code spring.batch.jdbc.initialize-schema}; there is deliberately no fourth migration.
 *       {@code spring.batch.job.enabled: false}, so starting this context launches no job.</li>
 *   <li>Hibernate's JDBC time zone is {@code UTC}. The rate comparison policy is {@code compareTo}; the
 *       {@code CHAR} policy is the blank-pad rule stated above; the precision tier for this table is
 *       {@code 6,2}.</li>
 *   <li>Isolation is the harness's class-level transactional rollback. Rows written here therefore never
 *       become visible to another test, which is exactly why this class adds no SQL script, no truncation,
 *       no bulk delete and no context-dirtying marker.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every test errors before any assertion runs, with a container or Docker failure.</em> No
 *       reachable Docker socket. The tier cannot pass without one; report the blocker rather than
 *       asserting an untested pass.</li>
 *   <li><em>A Testcontainers artefact fails to resolve, or the wrong major version is selected.</em> The
 *       migration's <strong>Blocker</strong>-severity trap, whose remedy has two halves that are both
 *       required: pin 2.0.3 by overriding the version property the Spring Boot parent manages rather than
 *       importing a second bill of materials, since two competing imports resolve in an ordering-dependent
 *       way that can silently select the parent-managed 1.x line; and use only the prefixed module
 *       coordinates {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter}, because the bare 1.x
 *       identifiers do not exist on the 2.x line. Overriding without renaming resolves artefacts that are
 *       not published; renaming without overriding resolves the wrong version. Both halves were verified
 *       already present in the root {@code pom.xml}, which is root-owned and must not be edited from
 *       here.</li>
 *   <li><em>The build fails on something trivial-looking.</em> Compilation runs with {@code -Xlint:all},
 *       {@code -Werror} and {@code failOnWarning}, and that reaches test compilation, so an unused import,
 *       a raw type, an unchecked cast or a deprecation is a build failure rather than a warning.
 *       Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>Context startup fails on a JDBC type code rather than a column name.</em> {@code validate}
 *       compares type codes, so an {@code Integer} over {@code NUMERIC(4)} can fail where {@code INTEGER}
 *       passes, and a {@code BigDecimal} over {@code NUMERIC(6,2)} must match exactly.
 *       <strong>The fix is upstream</strong> in {@code V1__create_schema.sql} or in the entity or key
 *       class; never widen a column to silence it and never patch the test.
 *       <strong>Severity: Medium.</strong></li>
 *   <li><em>The bare {@code 'DEFAULT'} probe misses while the padded one hits, or the reverse.</em>
 *       {@code acct_group_id} has been relaxed from {@code CHAR(10)} to a variable-width type, or a
 *       comparison acquired an explicit {@code ::text} cast. <strong>Severity: High.</strong> Restore
 *       {@code CHAR(10)} in the migration; do not pad the probe to compensate.</li>
 *   <li><em>Interest figures diverge in the last decimal place.</em> The rate column was widened to
 *       {@code 12,2} or {@code 11,2}, or the formula was algebraically rewritten, or the rounding mode is
 *       not {@code HALF_EVEN}. <strong>Severity: Blocker</strong> for the precision, Medium for the
 *       rounding.</li>
 *   <li><em>A rate assertion fails although the value looks right.</em> {@code BigDecimal.equals} was used
 *       instead of {@code compareTo}, and the scales differ.</li>
 *   <li><em>Accounts receive no interest at all.</em> A stage-one miss is being treated as an error rather
 *       than as the fallback trigger; {@code :L422} accepts it as success. Alternatively a zero rate is
 *       being treated as a missing row.</li>
 *   <li><em>A fixture read fails with a name that is not catalogued.</em> The reader resolves bare
 *       classpath-root names against a nine-entry census and fails immediately naming the resource, rather
 *       than returning an empty list that would surface later as a confusing null.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Two items are declared <strong>"Not available"</strong> rather than filled in, because inventing
 * either would manufacture a false oracle.
 *
 * <ol>
 *   <li><p><strong>The end-to-end boundary parity expectation exists; what is Not available is a captured z/OS run to
 *       corroborate it.</strong> {@code src/test/resources/parity/gate1/} holds the frozen program's own output --
 *       {@code TRANSACT.expected}, {@code ACCTDATA.expected}, {@code TCATBALF.expected}, {@code DALYREJS.expected}
 *       and {@code CBTRN02C.sysout.expected} -- derived by compiling {@code app/cbl/CBTRN02C.cbl} unmodified and
 *       running it against the frozen fixtures, with the harness and the derivation recorded beside them in
 *       {@code PROVENANCE.properties}.
 *       What would be needed to close it: a captured 430-byte reject dataset from a real posting run at a
 *       known input state, together with the resulting transaction, account and category-balance images.
 *       Until then this class creates <strong>zero</strong> baseline files and fabricates no expected
 *       bytes; generating a baseline from this implementation and asserting against it would be circular,
 *       and hand-simulating the posting program is inadmissible because two defensible models of it over
 *       these same fixtures disagree - 13 rejects against 38 - so the figure moves with the model and is
 *       not an oracle.</p></li>
 *   <li><p><strong>Corpus grounding for the file-unavailable condition is Not available.</strong> The
 *       literal file status {@code '35'} occurs <strong>zero</strong> times across the 28 programs of
 *       {@code app/cbl} and {@code DFHRESP(NOTOPEN)} occurs <strong>zero</strong> times. For contrast,
 *       this table's own consuming program tests {@code '00'} at seventeen sites and {@code '23'} at
 *       exactly two, {@code app/cbl/CBACT04C.cbl:L422} and {@code :L436} - the two that define the
 *       fallback. The condition is specification-derived only, so no test for it is invented here.</p></li>
 *   </ol>
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>Single-threaded and single-connection by design: no test starts a thread, opens a second connection
 * or commits. Assertions are deterministic over 51 fixed rows, with no dependence on wall-clock time,
 * locale, time zone, randomness or unspecified iteration order - every multi-row assertion imposes an
 * explicit ordering. The methods that write do so inside the harness's transaction and are discarded when
 * it rolls back; the methods that provoke a constraint violation place it last, because a failed statement
 * aborts the surrounding PostgreSQL transaction and nothing may follow it.
 */
@DisplayName("DisclosureGroupRepository: the DISCGRP rate table on PostgreSQL 16")
class DisclosureGroupRepositoryTest extends AbstractRepositoryIntegrationTest {

    /** The repository under test, the sole subject of this class. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Reads catalogue metadata and drives the two insertions the entity's own guards make unreachable.
     *
     * <p>Two jobs, both of which need SQL rather than JPA. The first is column metadata - numeric precision
     * and scale - which no repository exposes and no entity models. The second is reaching the
     * <em>database</em> boundary for an over-length {@code CHAR} value and an over-precision rate:
     * {@link DisclosureGroupId} rejects a group id longer than ten characters and a type code that is not
     * exactly two, and {@link DisclosureGroup} rejects a rate whose scale exceeds two or whose magnitude
     * exceeds {@code 9999.99}, all with {@code IllegalArgumentException} raised in Java before any
     * statement is sent. Those Java guards are correct and are asserted by the model unit tier; but they
     * mean the only way to prove that <em>PostgreSQL</em> also refuses such values is to bypass the mapping
     * with a parameterised statement.
     *
     * <p>Every statement issued through this template binds its values as parameters. None concatenates a
     * value into SQL text, which is the same discipline that rules out shell and query injection.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Used only to force a genuine {@code INSERT} where {@code save} would silently do something else.
     *
     * <p>This is not a redundant copy of the harness's own persistence context, which is private and
     * exposes only {@link AbstractRepositoryIntegrationTest#flushAndClear()}; it is here for one specific
     * reason. {@code SimpleJpaRepository.save} branches on whether the entity is new, and for an
     * {@code @EmbeddedId} with no version attribute "new" means "the identifier is null". A rate row built
     * to collide with a seeded key has a non-null identifier, so {@code save} would <em>merge</em> it: a
     * select followed by an update. The duplicate-key violation would never be attempted and the
     * uniqueness assertion would pass without proving anything. {@code persist} states the intent
     * exactly - insert this row - and lets the flush surface the constraint.
     *
     * <p>It is an instance field, never {@code static}, so nothing survives between test methods.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Renders a rate row as {@code <padded group id>/<type>/<zero-padded category>} for ordering and
     * membership assertions.
     *
     * <p>The group id is left <em>padded</em> exactly as {@code CHAR(10)} returns it, and the category is
     * rendered as four zero-filled digits exactly as {@code PIC 9(04)} stores it. That is not cosmetic: with
     * both components at fixed width, lexicographic order over these strings is identical to the composite
     * key order, so an ordering assertion over the rendered form is a faithful assertion about the key. It is
     * also why an {@code Integer} mapping of {@code DIS-TRAN-CAT-CD} preserves the legacy browse sequence -
     * zero-padded unsigned digits sort the same way whether compared as text or as numbers.
     *
     * <p>{@code Locale.ROOT} is passed explicitly so that digit formatting cannot vary with the default
     * locale of the machine running the build.
     *
     * @param row a rate row loaded from the database; must not be {@code null}
     * @return the rendered key image, for example {@code "DEFAULT   /01/0001"}
     */
    private String keyImageOf(final DisclosureGroup row) {
        final DisclosureGroupId id = row.getId();
        return String.format(Locale.ROOT, "%s/%s/%04d",
                id.getAccountGroupId(), id.getTranTypeCd(), id.getTranCatCd());
    }

    /**
     * Renders a rate row as {@code <type>/<zero-padded category>=<rate>} for whole-block assertions.
     *
     * <p>The rate is rendered with {@link BigDecimal#toPlainString()}, which pins the scale the column
     * returns as well as the value. That is deliberate and complements rather than contradicts the
     * {@code compareTo} policy: individual value assertions use {@code isEqualByComparingTo} so that a
     * differently-scaled expectation still passes, whereas this rendering is used where the point is to
     * capture an entire seeded block exactly, scale included, in one comparison. No {@link BigDecimal} is
     * ever compared with {@code equals}.
     *
     * @param row a rate row loaded from the database; must not be {@code null}
     * @return the rendered image, for example {@code "01/0001=15.00"}
     */
    private String typeCategoryAndRateOf(final DisclosureGroup row) {
        final DisclosureGroupId id = row.getId();
        return String.format(Locale.ROOT, "%s/%04d=%s",
                id.getTranTypeCd(), id.getTranCatCd(), row.getInterestRate().toPlainString());
    }

    /**
     * Decodes the six-byte rate field of one {@code discgrp.txt} record by absolute position.
     *
     * <p>The offsets come from the picture clauses at {@code app/cpy/CVTRA02Y.cpy:L6-L9} and from nothing
     * else: {@code DIS-ACCT-GROUP-ID X(10)} occupies bytes 1-10, {@code DIS-TRAN-TYPE-CD X(02)} bytes 11-12,
     * {@code DIS-TRAN-CAT-CD 9(04)} bytes 13-16 and {@code DIS-INT-RATE S9(04)V99} bytes 17-22 - so the rate
     * is the half-open zero-based range {@code [16, 22)}, six characters of which the last carries the sign.
     *
     * <p>{@code S9(04)V99} is a zoned-decimal signed field whose sign is an <em>overpunch</em> on the final
     * digit: {@code &#123;} is {@code +0} and {@code A} through {@code I} are {@code +1} through {@code +9},
     * while {@code &#125;} is {@code -0} and {@code J} through {@code R} are {@code -1} through
     * {@code -9}. The decode must be driven by position, as here, and
     * <strong>must never be a global text replacement</strong>, because those same letters occur
     * legitimately inside alphanumeric fields elsewhere in the fixture set - a merchant name, for
     * instance - and a blanket substitution would corrupt them silently.
     *
     * <p>This method corroborates the seed; it does not perform it. Decoding the fixture into the table is
     * {@code V3__seed_data.sql}'s responsibility, and what the assertions here compare is that migration's
     * <em>result</em> against an independent decode of the same frozen bytes.
     *
     * <p>The offsets are local constants rather than fields because this class declares no {@code static}
     * member of any kind, and a per-instance field would suggest state that does not exist.
     *
     * @param record one 50-character record exactly as stored, never trimmed or normalised
     * @return the decoded rate at scale 2, sign included
     * @throws IllegalStateException if the final character is not one of the twenty valid overpunches, which
     *                               would mean the frozen fixture had been edited; the message names the
     *                               offending character and record so the cause is not left to be guessed
     */
    private BigDecimal decodeFixtureRate(final String record) {
        // Positions 1-9 of this table are the positive digits 0-9 and positions 10-19 the negative ones,
        // so the remainder modulo ten is the digit and the halfway point is the sign boundary.
        final String overpunchDigits = "{ABCDEFGHI}JKLMNOPQR";
        final int signBoundary = 10;
        final int impliedDecimals = 2;

        final String field = record.substring(16, 22);
        final char overpunch = field.charAt(field.length() - 1);
        final int position = overpunchDigits.indexOf(overpunch);
        if (position < 0) {
            throw new IllegalStateException("Record '" + record + "' carries '" + overpunch
                    + "' where DIS-INT-RATE PIC S9(04)V99 requires a zoned-decimal overpunch from '"
                    + overpunchDigits + "' (app/cpy/CVTRA02Y.cpy:L9)");
        }
        final String leadingDigits = field.substring(0, field.length() - 1);
        final BigDecimal magnitude =
                new BigDecimal(leadingDigits + position % signBoundary).movePointLeft(impliedDecimals);
        return position < signBoundary ? magnitude : magnitude.negate();
    }

    /**
     * Extracts the ten-byte {@code DIS-ACCT-GROUP-ID} of one {@code discgrp.txt} record.
     *
     * <p>Bytes 1-10 per {@code app/cpy/CVTRA02Y.cpy:L6}, so the zero-based range {@code [0, 10)}. The value
     * is returned <strong>exactly as stored, including its trailing blanks</strong>, because these records
     * are fixed-width and a trailing blank is data rather than noise - and because the padded image is what
     * the {@code CHAR(10)} column returns, so the fixture and the table can be compared directly.
     *
     * @param record one 50-character record exactly as stored
     * @return the ten-character group identifier, padded
     */
    private String fixtureGroupId(final String record) {
        return record.substring(0, 10);
    }

    /**
     * Extracts the type and category of one {@code discgrp.txt} record as {@code <type>/<category>}.
     *
     * <p>{@code DIS-TRAN-TYPE-CD X(02)} is bytes 11-12 and {@code DIS-TRAN-CAT-CD 9(04)} bytes 13-16 per
     * {@code app/cpy/CVTRA02Y.cpy:L7-L8}, so the zero-based ranges {@code [10, 12)} and {@code [12, 16)}.
     * The category keeps its stored zero padding, which is what makes the rendered form comparable with
     * {@link #typeCategoryAndRateOf} without either side being reformatted.
     *
     * @param record one 50-character record exactly as stored
     * @return the rendered pair, for example {@code "01/0001"}
     */
    private String fixtureTypeAndCategory(final String record) {
        return record.substring(10, 12) + "/" + record.substring(12, 16);
    }

    /**
     * Extracts the type and category of one {@code trancatg.txt} record as {@code <type>/<category>}.
     *
     * <p>That fixture is 18 records of 60 characters laid out as a two-character transaction type, a
     * four-character category, a fifty-character description and four filler characters. The pair therefore
     * occupies the zero-based ranges {@code [0, 2)} and {@code [2, 6)}, and the rendered form is directly
     * comparable with {@link #fixtureTypeAndCategory} - which is what makes the subset relation between the
     * two fixtures assertable without reformatting either.
     *
     * @param record one 60-character record exactly as stored
     * @return the rendered pair, for example {@code "01/0005"}
     */
    private String categoryFixtureTypeAndCategory(final String record) {
        return record.substring(0, 2) + "/" + record.substring(2, 6);
    }

    /**
     * Reads the declared numeric precision of one column from the catalogue.
     *
     * <p>The table and column names are <strong>bound as parameters</strong> rather than concatenated into
     * the statement text. Nothing here is attacker-supplied, so this is defence in depth rather than a fix
     * for a live hole, but the discipline is uniform: no value is ever spliced into SQL in this tier.
     * {@code current_schema()} is used instead of a hard-coded schema name so the query does not assume how
     * the container was provisioned.
     *
     * @param table  unqualified table name, for example {@code "disclosure_group"}
     * @param column column name, for example {@code "dis_int_rate"}
     * @return the declared precision, that is the total number of significant digits
     */
    private Integer numericPrecisionOf(final String table, final String column) {
        return jdbcTemplate.queryForObject(
                "SELECT numeric_precision FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    /**
     * Reads the declared numeric scale of one column from the catalogue.
     *
     * <p>Separate from {@link #numericPrecisionOf} rather than sharing one method with the metadata column
     * name as an argument, because that argument would have to be spliced into the statement text as an
     * identifier. Two short methods with fully static SQL and bound value parameters are the cost of keeping
     * that rule absolute.
     *
     * @param table  unqualified table name, for example {@code "disclosure_group"}
     * @param column column name, for example {@code "dis_int_rate"}
     * @return the declared scale, that is the number of digits to the right of the decimal point
     */
    private Integer numericScaleOf(final String table, final String column) {
        return jdbcTemplate.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    /**
     * Inserts one rate row with a parameterised statement, bypassing the entity mapping.
     *
     * <p>Used only where the mapping cannot express the value under test, which is precisely the point of
     * the two width assertions and the precision-overflow assertion: {@link DisclosureGroupId} and
     * {@link DisclosureGroup} reject those inputs in Java, so a test that went through them would prove the
     * Java guard and say nothing about the column. Every value is bound, never concatenated.
     *
     * @param groupId      candidate {@code acct_group_id}, possibly wider than the column permits
     * @param typeCode     candidate {@code tran_type_cd}, possibly wider than the column permits
     * @param categoryCode candidate {@code tran_cat_cd}
     * @param rate         candidate {@code dis_int_rate}, possibly beyond the declared precision
     */
    private void insertRateRowDirectly(final String groupId, final String typeCode,
            final int categoryCode, final BigDecimal rate) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_group "
                        + "(acct_group_id, tran_type_cd, tran_cat_cd, dis_int_rate) VALUES (?, ?, ?, ?)",
                groupId, typeCode, categoryCode, rate);
    }

    /** The shape of the seed: how many rate rows exist and how they divide across the three group ids. */
    @Nested
    @DisplayName("V3 seed: 51 rate rows partitioned 17/17/17 across three group identifiers")
    class SeededPartition {

        @Test
        @DisplayName("exactly 51 rate rows are seeded, matching the fixture and the catalogue's own total")
        void exactlyFiftyOneRatesAreSeeded() {
            assertThat(disclosureGroupRepository.count())
                    .as("app/data/ASCII/discgrp.txt holds 51 records of 50 bytes (2601 bytes including "
                            + "line feeds) and app/catlg/LISTCAT.txt:901 independently reports "
                            + "REC-TOTAL 51 for AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS")
                    .isEqualTo(51L);
        }

        @Test
        @DisplayName("the 51 rows divide 17/17/17 across exactly three padded group identifiers")
        void theRowsPartitionSeventeenEachAcrossThreeGroupIds() {
            final List<DisclosureGroup> all = disclosureGroupRepository.findAll();

            final List<String> distinctGroupIds = all.stream()
                    .map(row -> row.getId().getAccountGroupId())
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(distinctGroupIds)
                    .as("app/data/ASCII/discgrp.txt rows 1-17, 18-34 and 35-51; CHAR(10) returns each "
                            + "identifier blank-padded to its declared width, which is the image the "
                            + "fallback at app/cbl/CBACT04C.cbl:437 also presents")
                    .containsExactly("A000000000", "DEFAULT   ", "ZEROAPR   ");

            for (final String groupId : distinctGroupIds) {
                assertThat(all.stream().filter(row -> groupId.equals(row.getId().getAccountGroupId())).count())
                        .as("group '%s' must carry 17 of the 51 seeded rates", groupId)
                        .isEqualTo(17L);
            }
        }
    }

    /** Keyed reads: the composite identifier resolves a row, and a miss is empty rather than an error. */
    @Nested
    @DisplayName("Composite key: findById round-trips, and an absent key is empty rather than an error")
    class CompositeKeyRoundTrip {

        @Test
        @DisplayName("the first fixture row round-trips at +15.00")
        void theFirstFixtureRowRoundTripsAtFifteen() {
            final DisclosureGroupId id = new DisclosureGroupId("A000000000", "01", 1);

            final Optional<DisclosureGroup> found = disclosureGroupRepository.findById(id);

            assertThat(found)
                    .as("app/data/ASCII/discgrp.txt:1 reads A00000000001000100150{ - group A000000000, "
                            + "type 01, category 0001")
                    .isPresent();
            final DisclosureGroup row = found.orElseThrow();
            assertThat(row.getInterestRate())
                    .as("the six-byte rate field 00150{ decodes to +15.00, the trailing overpunch "
                            + "denoting +0; compared with compareTo because BigDecimal.equals is "
                            + "scale-sensitive and this column returns scale 2")
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(row.getId().getTranTypeCd()).isEqualTo("01");
            assertThat(row.getId().getTranCatCd()).isEqualTo(1);
        }

        @Test
        @DisplayName("the last fixture row round-trips at +0.00 from an unpadded group identifier")
        void theLastFixtureRowRoundTripsAtZeroFromAnUnpaddedGroupId() {
            final DisclosureGroupId unpadded = new DisclosureGroupId("ZEROAPR", "07", 1);

            final Optional<DisclosureGroup> found = disclosureGroupRepository.findById(unpadded);

            assertThat(found)
                    .as("app/data/ASCII/discgrp.txt:51 reads ZEROAPR   07000100000{; the seven-character "
                            + "probe matches the ten-character stored image because SQL CHAR comparison "
                            + "ignores trailing blanks - the same rule the DEFAULT fallback relies on")
                    .isPresent();
            final DisclosureGroup row = found.orElseThrow();
            assertThat(row.getInterestRate())
                    .as("00000{ decodes to +0.00, which is a value and not an absence")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.getId().getAccountGroupId())
                    .as("the loaded identifier echoes the key the load was issued with, because JPA "
                            + "populates an @EmbeddedId from the supplied identifier rather than from the "
                            + "selected columns - the stored bytes remain padded, as a query-derived read "
                            + "shows. Asserting the bare form here records the measured behaviour instead "
                            + "of the intuitive one")
                    .isEqualTo("ZEROAPR");
        }

        @Test
        @DisplayName("an absent composite key returns an empty result and does not throw")
        void anAbsentCompositeKeyReturnsEmptyRatherThanThrowing() {
            final DisclosureGroupId absent = new DisclosureGroupId("A000000000", "01", 9999);

            final Optional<DisclosureGroup> found = disclosureGroupRepository.findById(absent);

            assertThat(found)
                    .as("app/cbl/CBACT04C.cbl:422 accepts the record-not-found status as success, so a "
                            + "stage-one miss is a routine control path; translating it into an exception "
                            + "would stop the interest job posting for every account whose group is "
                            + "unlisted. Turning a miss into either the retry or a fatal abend belongs to "
                            + "FileStatusMapper and InterestCalculationProcessor, not here")
                    .isEmpty();
        }
    }

    /**
     * The defining contract: {@code CHAR(10)} padding, the fallback as the normal path, and two queries kept
     * as two queries.
     */
    @Nested
    @DisplayName("DEFAULT fallback: CHAR(10) padding, two separate lookups, and the 01/0005 gap")
    class DefaultGroupFallback {

        @Test
        @DisplayName("the padded and the bare DEFAULT literal resolve the same row")
        void bothPaddedAndBareDefaultLiteralsResolveTheSameRow() {
            final DisclosureGroupId bare = new DisclosureGroupId("DEFAULT", "01", 1);
            final DisclosureGroupId padded = new DisclosureGroupId("DEFAULT   ", "01", 1);

            final DisclosureGroup foundByBare = disclosureGroupRepository.findById(bare)
                    .orElseThrow(() -> new AssertionError(
                            "CBACT04C.cbl:437 MOVE 'DEFAULT' into an X(10) field yields 'DEFAULT   ', and "
                            + "SQL CHAR comparison ignores trailing blanks, so the bare seven-character "
                            + "literal must resolve the seeded row. A miss means acct_group_id is no longer "
                            + "CHAR(10) - fix V1__create_schema.sql, never pad the probe"));
            // Detach so the second lookup is a real round trip to the database rather than an identity-map
            // hit: the two keys are different Java values, so without this the assertion would still issue
            // two selects, but forcing the boundary makes the intent explicit.
            flushAndClear();
            final DisclosureGroup foundByPadded = disclosureGroupRepository.findById(padded)
                    .orElseThrow(() -> new AssertionError(
                            "the space-padded ten-character literal is the exact byte image the source "
                            + "presents at CBACT04C.cbl:437 and the exact image discgrp.txt:18 stores, so "
                            + "it must resolve the seeded row"));

            assertThat(foundByPadded.getInterestRate())
                    .as("discgrp.txt:18 reads DEFAULT   01000100150{, so +15.00")
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(foundByBare.getInterestRate())
                    .as("the bare probe must return that same rate, not a different row and not a "
                            + "different rate")
                    .isEqualByComparingTo(foundByPadded.getInterestRate());

            // The two entities' identifiers cannot be compared to prove they are one row, because each
            // echoes its own probe - see the class documentation. The database itself settles it: exactly
            // one row satisfies the bare and the padded predicate simultaneously, which is only possible
            // if trailing blanks are ignored and both spellings select that same row.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM disclosure_group WHERE acct_group_id = ? AND acct_group_id = ? "
                            + "AND tran_type_cd = ? AND tran_cat_cd = ?",
                    Integer.class, "DEFAULT", "DEFAULT   ", "01", 1))
                    .as("the database-level proof that CHAR(10) reproduces the COBOL padding of "
                            + "app/cbl/CBACT04C.cbl:437: one single row answers to both spellings at once")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a loaded identifier echoes the probe while the stored image stays padded")
        void theLoadedIdentifierEchoesTheProbeWhileTheStoredImageStaysPadded() {
            final DisclosureGroup viaBareProbe = disclosureGroupRepository
                    .findById(new DisclosureGroupId("DEFAULT", "01", 1)).orElseThrow();
            flushAndClear();
            final DisclosureGroup viaPaddedProbe = disclosureGroupRepository
                    .findById(new DisclosureGroupId("DEFAULT   ", "01", 1)).orElseThrow();
            final DisclosureGroup viaQuery = disclosureGroupRepository.findDefaultGroupRate("01", 1)
                    .orElseThrow();

            assertThat(viaBareProbe.getId().getAccountGroupId())
                    .as("a keyed load reflects the identifier it was given back to the caller, because JPA "
                            + "populates an @EmbeddedId from the load key rather than from the selected "
                            + "columns")
                    .isEqualTo("DEFAULT");
            assertThat(viaPaddedProbe.getId().getAccountGroupId())
                    .as("the same load with the padded spelling echoes that spelling instead")
                    .isEqualTo("DEFAULT   ");
            assertThat(viaQuery.getId().getAccountGroupId())
                    .as("only a query-derived read exposes the stored bytes, and they are padded to the "
                            + "declared CHAR(10) width")
                    .isEqualTo("DEFAULT   ");

            assertThat(viaBareProbe.getId())
                    .as("so two identifiers differing only in trailing blanks are one row in SQL yet are "
                            + "not equal in Java. A caller must therefore not substitute a Java-side set, "
                            + "map or equality check for a keyed read, and must not treat an identifier "
                            + "from a keyed load as the stored byte image: fixed-width emission has to take "
                            + "the value from a query-derived read or re-pad it. Severity Medium - it "
                            + "misleads rather than corrupts, and the interest job consumes the rate "
                            + "without ever re-emitting the group id")
                    .isNotEqualTo(viaPaddedProbe.getId());
            assertThat(viaBareProbe.getInterestRate())
                    .as("all three views are nonetheless the same row, which their common rate shows")
                    .isEqualByComparingTo(viaQuery.getInterestRate());
            assertThat(viaPaddedProbe.getInterestRate())
                    .isEqualByComparingTo(viaQuery.getInterestRate());
        }

        @Test
        @DisplayName("the explicit default finder is a second, independent call site")
        void theDefaultFinderIsASecondIndependentCallSite() {
            final Optional<DisclosureGroup> stageOne =
                    disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 1));
            final Optional<DisclosureGroup> stageTwo = disclosureGroupRepository.findDefaultGroupRate("01", 1);

            assertThat(stageOne)
                    .as("stage one, app/cbl/CBACT04C.cbl:416 READ DISCGRP-FILE by the assembled key")
                    .isPresent();
            assertThat(stageTwo)
                    .as("stage two, app/cbl/CBACT04C.cbl:444, a genuinely separate read issued only after "
                            + "the group id was substituted at :437. It must never be collapsed into stage "
                            + "one by a coalesce, a union, a disjunction or a repository-level default: a "
                            + "merged query cannot report which stage produced the row, so a benign first "
                            + "miss would become indistinguishable from a fatal second one and the fatal "
                            + "branch at :458 would be unreachable")
                    .isPresent();
            assertThat(stageTwo.orElseThrow().getId().getAccountGroupId())
                    .as("the retry substitutes the group id and only the group id - :437 touches nothing "
                            + "else, so the type and category of the failed lookup are carried through")
                    .isEqualTo("DEFAULT   ");
            assertThat(stageTwo.orElseThrow().getId().getTranTypeCd()).isEqualTo("01");
            assertThat(stageTwo.orElseThrow().getId().getTranCatCd()).isEqualTo(1);
            assertThat(stageTwo.orElseThrow().getInterestRate()).isEqualByComparingTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("the fallback is the normal path because every seeded account group is blank")
        void theFallbackIsTheNormalPathBecauseEveryAccountGroupIsBlank() {
            final Integer accountsWithBlankGroup = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM account WHERE acct_group_id = ?", Integer.class, "          ");

            assertThat(accountsWithBlankGroup)
                    .as("all 50 rows of app/data/ASCII/acctdata.txt carry ten spaces at columns 113-122, so "
                            + "app/cbl/CBACT04C.cbl:210 assembles a blank group id for every account")
                    .isEqualTo(50);

            final Optional<DisclosureGroup> stageOneForABlankGroup =
                    disclosureGroupRepository.findById(new DisclosureGroupId("          ", "01", 1));
            final Optional<DisclosureGroup> stageTwoForTheSamePair =
                    disclosureGroupRepository.findDefaultGroupRate("01", 1);

            assertThat(stageOneForABlankGroup)
                    .as("a blank group id matches no rate row, so stage one misses for every account in "
                            + "the fixture - which is exactly why :422 treats the miss as success")
                    .isEmpty();
            assertThat(stageTwoForTheSamePair)
                    .as("and the DEFAULT retry resolves, so the fallback is the path taken in normal "
                            + "operation rather than an edge case")
                    .isPresent();
        }

        @Test
        @DisplayName("the interest job's own output category 01/0005 deliberately has no rate row")
        void theInterestOutputCategoryHasNoRateRow() {
            assertThat(disclosureGroupRepository.findDefaultGroupRate("01", 5))
                    .as("app/data/ASCII/trancatg.txt lists 18 type-and-category pairs and the fallback "
                            + "group covers 17 of them, so exactly one category has no default rate: "
                            + "01/0005, \"Interest Amount\". That is deliberate - 01/0005 is the code the "
                            + "interest job stamps on the transactions it generates, never an input to a "
                            + "rate lookup. Seeding a 010005 fallback row would be fabrication")
                    .isEmpty();
            assertThat(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 5)))
                    .as("the same gap through the primary lookup, so it is a property of the data rather "
                            + "than of the query")
                    .isEmpty();

            final List<DisclosureGroup> anyGroupCoveringTheOutputCategory =
                    disclosureGroupRepository.findAll().stream()
                            .filter(row -> "01".equals(row.getId().getTranTypeCd()))
                            .filter(row -> row.getId().getTranCatCd() == 5)
                            .toList();

            assertThat(anyGroupCoveringTheOutputCategory)
                    .as("and no group at all covers it - not A000000000, not ZEROAPR either. The inverse "
                            + "assertion, that every seeded category has a matching rate row, is false and "
                            + "must never be written")
                    .isEmpty();
        }
    }

    /** A zero rate is an ordinary value that suppresses interest, never a stand-in for a missing row. */
    @Nested
    @DisplayName("Zero rate: a value that suppresses interest, not an absence")
    class ZeroRateIsAValue {

        @Test
        @DisplayName("exactly seven of the seventeen fallback rows carry a zero rate")
        void exactlySevenOfTheSeventeenFallbackRowsCarryZero() {
            final List<DisclosureGroup> fallbackRows = disclosureGroupRepository.findAll(
                    Sort.by(Sort.Order.asc("id.tranTypeCd"), Sort.Order.asc("id.tranCatCd"))).stream()
                    .filter(row -> "DEFAULT   ".equals(row.getId().getAccountGroupId()))
                    .toList();

            assertThat(fallbackRows.stream().map(row -> typeCategoryAndRateOf(row)).toList())
                    .as("the seventeen fallback rows of app/data/ASCII/discgrp.txt rows 18-34, rendered "
                            + "with the scale NUMERIC(6,2) returns; this pins which pairs are zero-rated "
                            + "rather than merely how many")
                    .containsExactly(
                            "01/0001=15.00", "01/0002=25.00", "01/0003=25.00", "01/0004=25.00",
                            "02/0001=0.00", "02/0002=0.00", "02/0003=0.00",
                            "03/0001=0.00", "03/0002=0.00", "03/0003=0.00",
                            "04/0001=15.00", "04/0002=15.00", "04/0003=15.00",
                            "05/0001=15.00",
                            "06/0001=15.00", "06/0002=15.00",
                            "07/0001=0.00");

            assertThat(fallbackRows.stream()
                    .filter(row -> row.getInterestRate().compareTo(BigDecimal.ZERO) == 0)
                    .count())
                    .as("app/cbl/CBACT04C.cbl:214 guards the computation with IF DIS-INT-RATE NOT = 0, so "
                            + "these seven rows - 02/0001-0003, 03/0001-0003 and 07/0001 - are the corpus's "
                            + "only coverage of the zero-rate skip branch at :215-:216")
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("a zero rate persists and round-trips as 0.00 rather than as null")
        void aZeroRatePersistsAndRoundTripsAsZeroNotNull() {
            final DisclosureGroupId id = new DisclosureGroupId("ZRATEPROBE", "01", 1);
            entityManager.persist(new DisclosureGroup(id, new BigDecimal("0.00")));
            flushAndClear();

            final DisclosureGroup reloaded = disclosureGroupRepository.findById(id)
                    .orElseThrow(() -> new AssertionError("a zero-rate row must persist like any other"));

            assertThat(reloaded.getInterestRate())
                    .as("BigDecimal.ZERO must never be conflated with Optional.empty(): a present "
                            + "zero-rate row satisfies stage one and suppresses the fallback, whereas an "
                            + "empty result triggers it")
                    .isNotNull()
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(reloaded.getInterestRate().scale())
                    .as("NUMERIC(6,2) returns scale 2, which is why rates are compared with compareTo and "
                            + "never with BigDecimal.equals")
                    .isEqualTo(2);
        }
    }

    /** The rate's declared precision, the tier it belongs to, and the boundary the database enforces. */
    @Nested
    @DisplayName("Rate precision: NUMERIC(6,2), distinct from the 12,2 and 11,2 tiers")
    class RatePrecisionContract {

        @Test
        @DisplayName("the rate is NUMERIC(6,2) and the only column at that precision in the schema")
        void theRateIsNumericSixTwoAndUniqueInTheSchema() {
            assertThat(numericPrecisionOf("disclosure_group", "dis_int_rate"))
                    .as("DIS-INT-RATE PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy:9 is four integer digits plus "
                            + "two decimals, so precision 6")
                    .isEqualTo(6);
            assertThat(numericScaleOf("disclosure_group", "dis_int_rate"))
                    .as("and scale 2")
                    .isEqualTo(2);

            assertThat(numericPrecisionOf("account", "acct_curr_bal"))
                    .as("the S9(10)V99 tier is NUMERIC(12,2) and must never be confused with this one")
                    .isEqualTo(12);
            assertThat(numericPrecisionOf("transaction_category_balance", "tran_cat_bal"))
                    .as("the S9(09)V99 tier is NUMERIC(11,2) and must never be confused with this one")
                    .isEqualTo(11);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                            + "AND data_type = ? AND numeric_precision = ? AND numeric_scale = ?",
                    Integer.class, "numeric", 6, 2))
                    .as("dis_int_rate is the sole NUMERIC(6,2) column in the schema, so widening it could "
                            + "not be masked by a sibling at the same precision. Severity Blocker: a wider "
                            + "column silently accepts rates the source cannot represent")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a rate at the declared precision round-trips exactly")
        void aRateAtTheDeclaredPrecisionRoundTripsExactly() {
            final DisclosureGroupId id = new DisclosureGroupId("MAXRATE", "01", 1);
            entityManager.persist(new DisclosureGroup(id, new BigDecimal("9999.99")));
            flushAndClear();

            assertThat(disclosureGroupRepository.findById(id).orElseThrow().getInterestRate())
                    .as("9999.99 is the largest magnitude four signed integer digits and two decimals can "
                            + "hold, so it must survive the round trip without rescaling")
                    .isEqualByComparingTo(new BigDecimal("9999.99"));
        }

        @Test
        @DisplayName("a negative rate persists, because PIC S9(04)V99 is a signed picture")
        void aNegativeRatePersistsBecauseThePictureIsSigned() {
            final DisclosureGroupId id = new DisclosureGroupId("NEGRATE", "01", 1);
            entityManager.persist(new DisclosureGroup(id, new BigDecimal("-15.00")));
            flushAndClear();

            assertThat(disclosureGroupRepository.findById(id).orElseThrow().getInterestRate())
                    .as("all 51 seeded rates carry the overpunch { and are therefore non-negative, but the "
                            + "picture is signed and a negative rate is inside the source domain, so the "
                            + "fixtures simply contain none. No non-negativity constraint may be added, and "
                            + "no magnitude normalisation is applied anywhere on this path")
                    .isEqualByComparingTo(new BigDecimal("-15.00"));
        }

        @Test
        @DisplayName("a rate beyond the declared precision is rejected by the database")
        void aRateBeyondTheDeclaredPrecisionIsRejectedByTheDatabase() {
            final Throwable thrown = catchThrowable(() ->
                    insertRateRowDirectly("PRECPROBE", "01", 1, new BigDecimal("10000.00")));

            assertThat(thrown)
                    .as("10000.00 needs five integer digits, one more than S9(04)V99 admits. The entity "
                            + "guards this in Java as well, which is why the statement is parameterised "
                            + "native SQL: the point is to prove PostgreSQL refuses it too")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getCause())
                    .as("the driver's exception must be preserved as the cause, never swallowed")
                    .isNotNull();
            assertThat(thrown)
                    .rootCause()
                    .hasMessageContaining("numeric field overflow");
        }
    }

    /** The four boundaries PostgreSQL itself enforces: the primary key, the foreign key and two widths. */
    @Nested
    @DisplayName("Constraints: unique composite key, the category foreign key, and the CHAR widths")
    class ConstraintBoundaries {

        @Test
        @DisplayName("a duplicate composite key is rejected, and the bare literal collides with the padded row")
        void aDuplicateCompositeKeyIsRejected() {
            entityManager.persist(new DisclosureGroup(
                    new DisclosureGroupId("DEFAULT", "01", 1), new BigDecimal("1.00")));

            final Throwable thrown = catchThrowable(() -> flushAndClear());

            assertThat(thrown)
                    .as("app/catlg/LISTCAT.txt:898 declares the cluster UNIQUE INDEXED with no NONUNIQKEY "
                            + "attribute, so the composite key genuinely is unique and a second row on the "
                            + "same key must fail. persist rather than save is deliberate: save would see a "
                            + "non-null embedded identifier, treat the row as not new and merge it, so the "
                            + "insert would never be attempted and this assertion would prove nothing")
                    .isInstanceOf(PersistenceException.class);
            assertThat(thrown.getCause())
                    .as("the constraint violation must be preserved as the cause, never swallowed")
                    .isNotNull();
            assertThat(thrown)
                    .rootCause()
                    .as("the bare seven-character literal collides with the padded stored image, which is "
                            + "the uniqueness constraint restating the CHAR blank-pad rule of "
                            + "app/cbl/CBACT04C.cbl:437 from the opposite direction")
                    .hasMessageContaining("pk_disclosure_group")
                    .hasMessageContaining("duplicate key value");
        }

        @Test
        @DisplayName("a rate for an unknown type and category pair is rejected by the foreign key")
        void anUnknownTypeAndCategoryPairIsRejectedByTheForeignKey() {
            entityManager.persist(new DisclosureGroup(
                    new DisclosureGroupId("FKPROBE", "X1", 9999), new BigDecimal("1.00")));

            final Throwable thrown = catchThrowable(() -> flushAndClear());

            assertThat(thrown)
                    .as("a rate row must describe a type and category that exists: app/cbl/CBACT04C.cbl:"
                            + "211-212 populates the lookup key from a transaction category balance, so an "
                            + "unlisted pair could never occur in the source. This is the tenth of V1's ten "
                            + "foreign keys, and it constrains the type-and-category pair only - the group "
                            + "id is deliberately unconstrained, which is why an invented group id above "
                            + "inserts happily")
                    .isInstanceOf(PersistenceException.class);
            assertThat(thrown.getCause())
                    .as("the constraint violation must be preserved as the cause, never swallowed")
                    .isNotNull();
            assertThat(thrown)
                    .rootCause()
                    .hasMessageContaining("fk10_discgrp_category")
                    .hasMessageContaining("transaction_category");
        }

        @Test
        @DisplayName("a group identifier wider than ten characters is rejected at the database boundary")
        void anOverLengthGroupIdIsRejectedAtTheDatabaseBoundary() {
            final Throwable thrown = catchThrowable(() ->
                    insertRateRowDirectly("A0000000001", "01", 1, new BigDecimal("1.00")));

            assertThat(thrown)
                    .as("DIS-ACCT-GROUP-ID is PIC X(10), so eleven non-blank characters cannot be stored. "
                            + "Note the asymmetry that makes the blank-pad rule safe: CHAR silently drops "
                            + "surplus *trailing blanks*, so only non-blank overflow is an error - which is "
                            + "exactly why a padded probe is harmless while an over-long identifier is not")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getCause())
                    .as("the driver's exception must be preserved as the cause, never swallowed")
                    .isNotNull();
            assertThat(thrown)
                    .rootCause()
                    .hasMessageContaining("character(10)");
        }

        @Test
        @DisplayName("a type code wider than two characters is rejected at the database boundary")
        void anOverLengthTypeCodeIsRejectedAtTheDatabaseBoundary() {
            final Throwable thrown = catchThrowable(() ->
                    insertRateRowDirectly("WIDETYPE", "X12", 1, new BigDecimal("1.00")));

            assertThat(thrown)
                    .as("DIS-TRAN-TYPE-CD is PIC X(02), so three non-blank characters cannot be stored. "
                            + "The key class also requires exactly two, which is why this goes through "
                            + "parameterised native SQL to reach the column itself")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getCause())
                    .as("the driver's exception must be preserved as the cause, never swallowed")
                    .isNotNull();
            assertThat(thrown)
                    .rootCause()
                    .hasMessageContaining("character(2)");
        }
    }

    /** Primary-key column order reproduces COBOL component order, proved by reading the table in key order. */
    @Nested
    @DisplayName("Component order: the primary key reproduces the VSAM browse sequence")
    class ComponentOrderFidelity {

        @Test
        @DisplayName("a key-ordered read reproduces the fixture's own record order exactly")
        void aKeyOrderedReadReproducesTheFixtureOrder() {
            final List<String> actual = disclosureGroupRepository.findAll(
                    Sort.by(Sort.Order.asc("id.accountGroupId"),
                            Sort.Order.asc("id.tranTypeCd"),
                            Sort.Order.asc("id.tranCatCd"))).stream()
                    .map(row -> keyImageOf(row))
                    .toList();

            final List<String> expected = readFixture("discgrp.txt").stream()
                    .map(record -> fixtureGroupId(record) + "/" + fixtureTypeAndCategory(record))
                    .toList();

            assertThat(actual)
                    .as("app/data/ASCII/discgrp.txt is stored in VSAM key order, so a relational read "
                            + "ordered by (acct_group_id, tran_type_cd, tran_cat_cd) must reproduce the "
                            + "file record for record. That is the database-level proof that the primary "
                            + "key's column order matches the COBOL component order of "
                            + "app/cpy/CVTRA02Y.cpy:6-8, and therefore that the legacy browse sequence "
                            + "survives the migration. It also shows why an Integer mapping of the 9(04) "
                            + "category is safe: zero-padded unsigned digits order identically as text and "
                            + "as numbers")
                    .containsExactlyElementsOf(expected);

            assertThat(actual)
                    .as("the boundaries of the 17/17/17 partition, stated explicitly so a regression names "
                            + "itself rather than surfacing as a long diff")
                    .startsWith("A000000000/01/0001")
                    .endsWith("ZEROAPR   /07/0001")
                    .containsSubsequence("A000000000/07/0001", "DEFAULT   /01/0001",
                            "DEFAULT   /07/0001", "ZEROAPR   /01/0001")
                    .isSorted();
        }
    }

    /** The frozen fixtures, read by flat classpath name and never copied, trimmed, normalised or edited. */
    @Nested
    @DisplayName("Fixture corroboration: discgrp.txt and trancatg.txt as frozen at 7756d89")
    class FixtureCorroboration {

        @Test
        @DisplayName("the fixture holds 51 records of exactly 50 characters")
        void theFixtureHoldsFiftyOneFiftyCharacterRecords() {
            final List<String> records = readFixture("discgrp.txt");

            assertThat(records)
                    .as("2601 bytes is 51 records of 50 characters plus one line feed each")
                    .hasSize(51);
            assertThat(records)
                    .as("every record is exactly 50 characters: 10 + 2 + 4 key bytes, a 6-byte rate and a "
                            + "28-byte FILLER, per app/cpy/CVTRA02Y.cpy:6-10. This fixture's FILLER is "
                            + "zero-filled rather than space-filled, so its longest trailing-blank run is "
                            + "zero and it carries no whitespace-truncation risk of its own - a Low "
                            + "observation, since the no-edit discipline is uniform regardless")
                    .allMatch(record -> record.length() == 50);
        }

        @Test
        @DisplayName("the three fixture group identifiers each carry 17 records and every rate is non-negative")
        void theThreeFixtureGroupsCarrySeventeenEachAndNoRateIsNegative() {
            final List<String> records = readFixture("discgrp.txt");

            assertThat(records.stream().map(record -> fixtureGroupId(record)).distinct().sorted().toList())
                    .as("three ten-character families, stored space-padded exactly as CHAR(10) holds them")
                    .containsExactly("A000000000", "DEFAULT   ", "ZEROAPR   ");
            for (final String groupId : List.of("A000000000", "DEFAULT   ", "ZEROAPR   ")) {
                assertThat(records.stream().filter(record -> groupId.equals(fixtureGroupId(record))).count())
                        .as("fixture group '%s' must carry 17 of the 51 records", groupId)
                        .isEqualTo(17L);
            }

            assertThat(records.stream().filter(record -> record.charAt(21) == '{').count())
                    .as("the overpunch is the last byte of the six-character rate field, so zero-based "
                            + "position 21; { denotes +0, so all 51 seeded rates are non-negative even "
                            + "though PIC S9(04)V99 is signed")
                    .isEqualTo(51L);
        }

        @Test
        @DisplayName("every seeded rate equals an independent position-aware decode of the frozen bytes")
        void everySeededRateEqualsAnIndependentDecodeOfTheFixture() {
            for (final String record : readFixture("discgrp.txt")) {
                final DisclosureGroupId id = new DisclosureGroupId(
                        fixtureGroupId(record),
                        record.substring(10, 12),
                        Integer.parseInt(record.substring(12, 16)));

                final Optional<DisclosureGroup> seeded = disclosureGroupRepository.findById(id);

                assertThat(seeded)
                        .as("fixture record '%s' must have been seeded by V3__seed_data.sql", record)
                        .isPresent();
                assertThat(seeded.orElseThrow().getInterestRate())
                        .as("V3 must decode the zoned-decimal overpunch position-aware from the picture "
                                + "clause, never by a global text replacement; record '%s'", record)
                        .isEqualByComparingTo(decodeFixtureRate(record));
            }
        }

        @Test
        @DisplayName("the fixture's seven zero-rated fallback pairs are exactly the documented seven")
        void theFixtureFallbackBlockCarriesSevenZeroRates() {
            final List<String> zeroRatedFallbackPairs = readFixture("discgrp.txt").stream()
                    .filter(record -> "DEFAULT   ".equals(fixtureGroupId(record)))
                    .filter(record -> decodeFixtureRate(record).compareTo(BigDecimal.ZERO) == 0)
                    .map(record -> fixtureTypeAndCategory(record))
                    .toList();

            assertThat(zeroRatedFallbackPairs)
                    .as("00000{ decodes to +0.00; these seven pairs are the corpus's only coverage of the "
                            + "zero-rate skip at app/cbl/CBACT04C.cbl:214-217")
                    .containsExactly("02/0001", "02/0002", "02/0003",
                            "03/0001", "03/0002", "03/0003", "07/0001");
        }

        @Test
        @DisplayName("the fallback pairs are a proper subset of the 18 categories, short by 01/0005 alone")
        void theFallbackPairsAreAProperSubsetOfTheEighteenCategories() {
            final List<String> categoryPairs = readFixture("trancatg.txt").stream()
                    .map(record -> categoryFixtureTypeAndCategory(record))
                    .toList();
            final List<String> fallbackPairs = readFixture("discgrp.txt").stream()
                    .filter(record -> "DEFAULT   ".equals(fixtureGroupId(record)))
                    .map(record -> fixtureTypeAndCategory(record))
                    .toList();

            assertThat(categoryPairs).as("app/data/ASCII/trancatg.txt holds 18 records").hasSize(18);
            assertThat(fallbackPairs).as("the fallback group covers 17 of them").hasSize(17);
            assertThat(categoryPairs)
                    .as("every fallback pair must name a real category, which is what makes the foreign "
                            + "key satisfiable at all")
                    .containsAll(fallbackPairs);

            final List<String> uncovered = categoryPairs.stream()
                    .filter(pair -> !fallbackPairs.contains(pair))
                    .toList();

            assertThat(uncovered)
                    .as("18 minus 17 leaves exactly one uncovered category, and it is the interest job's "
                            + "own output code. Seeding a fallback row for it would be fabrication")
                    .containsExactly("01/0005");

            // The description occupies bytes 7-56 of the 60-byte record, after the two-character type and
            // the four-character category, so the zero-based range [6, 56).
            final String uncoveredDescription = readFixture("trancatg.txt").stream()
                    .filter(record -> "01/0005".equals(categoryFixtureTypeAndCategory(record)))
                    .map(record -> record.substring(6, 56).strip())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("trancatg.txt must list the pair 01/0005"));

            assertThat(uncoveredDescription)
                    .as("naming it makes the omission self-evidently deliberate: it is what the interest "
                            + "job stamps on the transactions it generates, never an input to a rate lookup")
                    .isEqualTo("Interest Amount");
        }
    }

    /**
     * The complete PostgreSQL metadata contract for the {@code disclosure_group} table.
     *
     * <p>Why this delegates rather than restating the facets, and why asserting whichever columns the
     * behavioural tests happen to touch would state no contract at all, is recorded once on
     * {@link SchemaMetadataMatrix}, which declares every facet and asserts the live catalogue against
     * it by exact equality on ordered lists.
     *
     * <p>For {@code disclosure_group} that is four columns, the three-part composite key in copybook field order, the NUMERIC(6,2) interest rate that is narrower than every other money column, and fk10 onto the transaction category - every value measured from the schema the migrations
     * produce and checked against {@code app/cpy/CVTRA02Y.cpy}, never transcribed from prose.
     */
    @Test
    @DisplayName("the disclosure group table matches the complete declared metadata contract: columns, types, "
            + "widths, precision, scale, nullability, primary key, foreign keys, indexes and constraints")
    void theTableMatchesTheCompleteMetadataContract() {
        SchemaMetadataMatrix.assertTableMatches(jdbcTemplate, "disclosure_group");
    }

}
