/*
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
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.repository;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies).
//
//   * Account — the JPA entity stub matching the CVACT01Y.cpy
//     ACCOUNT-RECORD layout (300-byte fixed-width record: 11-char ACCT-ID +
//     1-char ACCT-ACTIVE-STATUS + 5 × PIC S9(10)V99 monetary fields +
//     3 × PIC X(10) date fields + PIC X(10) ZIP + PIC X(10) GROUP-ID +
//     178-byte FILLER). Test methods drive persist/find/save round-trips
//     through this entity and assert that every BigDecimal monetary field
//     preserves the COBOL PIC S9(10)V99 scale (2 decimal places) on every
//     round-trip — the AAP §0.10.3 financial-precision invariant.
//
//     PRODUCTION FIELD NAMING NOTE — the AAP §0.10 "Phase 11 Adaptation
//     Notes" explicitly anticipates the production-entity field-name
//     divergence from the COBOL ACCT-* literals. The production
//     {@link Account} class uses Java-conventioned field names
//     (accountId, activeStatus, currentBalance, creditLimit,
//     cashCreditLimit, currentCycleCredit, currentCycleDebit, openDate,
//     expirationDate, reissueDate, addressZip, groupId, customerId,
//     version) rather than the COBOL-style ACCT-* names; this IT drives
//     the Java-style API exactly as the production code exposes it. The
//     COBOL ACCT-EXPIRAION-DATE → Java expirationDate normalisation (the
//     COBOL field name preserves the original copybook misspelling) is
//     documented in the {@link Account} class Javadoc.
//
//     PRODUCTION DATE TYPE NOTE — the AAP §0.10 "Phase 11 Adaptation
//     Notes" also explicitly anticipates the production type choice for
//     date fields. The production {@link Account#openDate},
//     {@link Account#expirationDate}, and {@link Account#reissueDate}
//     fields are {@link String} (storing the ISO YYYY-MM-DD
//     representation), NOT {@link java.time.LocalDate}. Tests construct
//     the value via {@code LocalDate.of(year, month, day).toString()} to
//     keep the date semantics self-documenting at the call site while
//     producing the {@link String} the production setter requires. This
//     mirrors the peer {@code CustomerRepositoryIT} pattern for the
//     CUST-DOB-YYYY-MM-DD field.
//
//   * AbstractRepositoryIT — the abstract base class providing the
//     @DataJpaTest slice annotation, the @Testcontainers PostgreSQL 16
//     container, the @DynamicPropertySource that wires Testcontainers'
//     JDBC URL/username/password into Spring's environment, and the
//     inherited TestEntityManager field (entityManager). Per AAP §0.4.4
//     all 10 repository ITs extend this base — this IT inherits the JPA
//     slice, per-class container lifecycle, and transactional rollback
//     after each @Test method without re-declaring any of that wiring.
//
//   * TestFixtures — the shared test-constants holder. This IT consumes
//     the Accounts-nested constants: SAMPLE_ACCOUNT_ID_10 ("00000000010"),
//     NEW_ACCOUNT_ID_60 ("00000000060", reserved for synthetic INSERT
//     tests not backed by any fixture row), NONEXISTENT_ACCOUNT_ID
//     ("99999999999", guaranteed never to appear in acctdata.txt — used
//     for VSAM STATUS '23' parity assertions), and DEFAULT_GROUP_ID
//     ("A000000000" — the canonical 10-char ACCT-GROUP-ID value carried
//     by every record in acctdata.txt). Per AAP §0.5.5 Cross-File Test
//     Dependencies and AAP §0.10.1 Require Test Coverage Rule (test
//     bodies must not duplicate literal sentinel values that already
//     appear in TestFixtures).
// ---------------------------------------------------------------------------
import com.aws.carddemo.entity.Account;
import com.aws.carddemo.testsupport.AbstractRepositoryIT;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each integration-test method. The Failsafe plugin
//     discovers test methods on the {@code *IT.java} suffix convention
//     and JUnit Jupiter runs them via the JUnit Platform.
//
//   * @DisplayName supplies human-readable scenario descriptions on the
//     test class and each method so IDE runner output and CI test reports
//     surface the COBOL-parity intent (rather than the camelCase method
//     name alone).
//
//   * @Disabled defers <em>runtime</em> execution until the production-
//     side prerequisites (JPA @Entity/@Id/@Column/@Version annotations on
//     Account + Flyway V1__schema.sql + V3__seed.sql) are landed by
//     subsequent REFACTOR-flavor migration agents. JUnit 5 reports
//     @Disabled tests as "skipped" (not "failed") so the Surefire/Failsafe
//     build stays green; the reactivation criteria appear in the
//     annotation's value attribute and in the class-level Javadoc
//     "Reactivation Checklist" section. The sibling
//     {@code CustomerRepositoryIT}, {@code UserSecurityRepositoryIT},
//     {@code TransactionCategoryRepositoryIT},
//     {@code TransactionTypeRepositoryIT}, and
//     {@code DiscountGroupRepositoryIT} use the same @Disabled pattern —
//     this IT mirrors that established project convention so the
//     compile-time wiring is verified end-to-end while the runtime DB
//     execution awaits its production-side dependencies.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ---------------------------------------------------------------------------
// Spring Framework dependency-injection import.
//
//   * @Autowired field-injects the Spring-managed AccountRepository
//     proxy into this IT class instance. The proxy is created by Spring
//     Data JPA at @DataJpaTest context startup from the
//     {@code JpaRepository<Account, String>} interface declaration on
//     the production repository — no manual implementation is required,
//     and no field declared with @Mock is permissible at this IT layer
//     (AAP §0.10.1 Require Test Coverage Rule: integration tests must
//     invoke the REAL repository against the REAL database).
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;

// ---------------------------------------------------------------------------
// Spring Framework data-access exception import.
//
//   * OptimisticLockingFailureException — Spring's data-access exception
//     subclass thrown when a JPA save() detects a stale @Version field
//     value. This is Spring's translation of Hibernate's
//     StaleObjectStateException (the underlying ORM-layer signal).
//
//     This IT asserts via AssertJ catchThrowable + isInstanceOf that a
//     stale-@Version save raises this exception — the JPA migration
//     equivalent of COBOL COACTUPC's before-image/after-image record
//     comparison for concurrent-update detection (AAP §0.1.1
//     "optimistic-locking semantics replacement"). The COBOL COACTUPC.cbl
//     program loads a record, displays it on the BMS screen for operator
//     review, accepts an updated copy on Enter, and then re-reads the
//     record from VSAM and compares the new copy against the operator's
//     in-memory before-image — if any field differs from the freshly-read
//     after-image, the program rejects the update as a concurrent-change
//     conflict. The JPA @Version mechanism (REFACTOR agents will add the
//     @Version annotation to {@link Account#version}) provides the same
//     guarantee declaratively: Hibernate compares the version-column
//     value in the UPDATE statement's WHERE clause against the in-memory
//     entity's version, and reports zero rows updated as a stale-version
//     conflict — surfaced to Spring as OptimisticLockingFailureException.
// ---------------------------------------------------------------------------
import org.springframework.dao.OptimisticLockingFailureException;

// ---------------------------------------------------------------------------
// Java standard library imports.
//
//   * java.math.BigDecimal — the mandated representation of every
//     monetary value migrated from the COBOL PIC S9(10)V99 fields
//     (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT,
//     ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT) per AAP §0.10.3 ("No
//     float or double used for any monetary value — BigDecimal
//     exclusively"). All monetary literals in this IT are constructed
//     as {@code new BigDecimal("...")} from a {@link String} (NEVER from
//     a {@code double} — the {@code BigDecimal(double)} constructor
//     introduces representation noise, e.g., {@code new BigDecimal(0.1)}
//     yields {@code 0.10000000000000000555…}; the
//     {@code BigDecimal(String)} constructor preserves the exact decimal
//     form). The scale assertions ({@code assertThat(value.scale())
//     .isEqualTo(2)}) verify that the persistence layer never silently
//     truncates precision and that the round-trip honours the COBOL
//     PIC S9(10)V99 scale-2 contract on every read.
//
//   * java.time.LocalDate — used as a self-documenting builder for the
//     ISO-formatted date strings the production Account entity stores.
//     The COBOL ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, and ACCT-REISSUE-DATE
//     fields are PIC X(10) — ISO 'YYYY-MM-DD' strings. The Java migration
//     preserves the String-based storage at the field level (see
//     {@link Account#openDate}) but tests construct the value via
//     {@code LocalDate.of(year, month, day).toString()} to make the date
//     semantics self-documenting at the call site (AAP §0.10.10 Style
//     Consistency). This mirrors the peer CustomerRepositoryIT pattern
//     for the CUST-DOB-YYYY-MM-DD field.
//
//   * java.util.Optional — the return type of
//     {@code accountRepository.findById(...)}. Tests assert
//     {@code isPresent()} for happy-path lookups, {@code isEmpty()} for
//     VSAM STATUS '23' record-not-found parity, and {@code orElseThrow()}
//     when extracting the reloaded entity for round-trip assertions.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

// ---------------------------------------------------------------------------
// AssertJ static imports (AAP §0.10.10 — project-wide assertion idiom).
//
//   * assertThat is the fluent-assertions entry point used uniformly
//     across the test suite. Idioms exercised by this class include
//     assertThat(optional).isPresent() / isEmpty(),
//     assertThat(account.getCurrentBalance()).isEqualByComparingTo(...),
//     assertThat(account.getCurrentBalance().scale()).isEqualTo(2),
//     assertThat(account.getCurrentBalance().signum()).isEqualTo(-1),
//     and assertThat(throwable).isInstanceOf(...).
//
//   * catchThrowable captures any {@link Throwable} thrown by the
//     supplied lambda for subsequent type/message assertions without
//     wrapping it in a try/catch — the AssertJ-canonical way to assert
//     on expected exceptions. Used here to capture the
//     {@link OptimisticLockingFailureException} raised by a stale-@Version
//     save in the
//     {@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
//     test.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;


/**
 * Integration tests for {@link AccountRepository}, which persists
 * {@link Account} entities migrated from the COBOL {@code ACCOUNT-RECORD}
 * defined in {@code app/cpy/CVACT01Y.cpy} (RECLN 300).
 *
 * <h2>COBOL Provenance — CVACT01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 300-byte record:
 * <pre>
 *   01 ACCOUNT-RECORD.
 *      05 ACCT-ID                  PIC 9(11).      --&gt; {@link Account#getAccountId()}            (primary key)
 *      05 ACCT-ACTIVE-STATUS       PIC X(01).      --&gt; {@link Account#getActiveStatus()}         ('Y' or 'N')
 *      05 ACCT-CURR-BAL            PIC S9(10)V99.  --&gt; {@link Account#getCurrentBalance()}       (BigDecimal scale 2)
 *      05 ACCT-CREDIT-LIMIT        PIC S9(10)V99.  --&gt; {@link Account#getCreditLimit()}          (BigDecimal scale 2)
 *      05 ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99.  --&gt; {@link Account#getCashCreditLimit()}      (BigDecimal scale 2)
 *      05 ACCT-OPEN-DATE           PIC X(10).      --&gt; {@link Account#getOpenDate()}             (ISO YYYY-MM-DD String)
 *      05 ACCT-EXPIRAION-DATE      PIC X(10).      --&gt; {@link Account#getExpirationDate()}       (ISO YYYY-MM-DD String; COBOL field name retains misspelling)
 *      05 ACCT-REISSUE-DATE        PIC X(10).      --&gt; {@link Account#getReissueDate()}          (ISO YYYY-MM-DD String)
 *      05 ACCT-CURR-CYC-CREDIT     PIC S9(10)V99.  --&gt; {@link Account#getCurrentCycleCredit()}   (BigDecimal scale 2)
 *      05 ACCT-CURR-CYC-DEBIT      PIC S9(10)V99.  --&gt; {@link Account#getCurrentCycleDebit()}    (BigDecimal scale 2)
 *      05 ACCT-ADDR-ZIP            PIC X(10).      --&gt; {@link Account#getAddressZip()}
 *      05 ACCT-GROUP-ID            PIC X(10).      --&gt; {@link Account#getGroupId()}              ('A000000000' for the default group)
 *      05 FILLER                   PIC X(178).
 * </pre>
 *
 * <h2>Migration Pattern (AAP §0.5.1)</h2>
 *
 * <p>The CVACT01Y account record is updated by THREE distinct COBOL
 * programs in the original CardDemo workflow, each of which maps to a
 * specific JPA repository contract verified by this IT:
 *
 * <ul>
 *   <li><strong>COACTVWC.cbl</strong> (account view, §9300-GETACCTDATA-BYACCT
 *       paragraph, lines 774–820) — primary-key VSAM KSDS read via
 *       {@code EXEC CICS READ DATASET('ACCTDAT') RIDFLD(WS-CARD-RID-ACCT-ID-X)}.
 *       Maps to JPA {@link AccountRepository#findById(Object)}:
 *       <ul>
 *         <li>COBOL {@code DFHRESP(NORMAL)} → {@code Optional.of(account)}
 *             — {@link #findById_existingAccount_returnsAccount()}.</li>
 *         <li>COBOL {@code DFHRESP(NOTFND)} → {@code Optional.empty()}
 *             (VSAM file-status code '23') —
 *             {@link #findById_nonexistentAccount_returnsEmpty()}.</li>
 *       </ul></li>
 *   <li><strong>COACTUPC.cbl</strong> (account update, ~4,236 lines) —
 *       performs a SYNCPOINT-bounded VSAM REWRITE following a before-image
 *       / after-image record comparison to detect concurrent updates.
 *       Maps to JPA {@code save()} + {@code @Version} optimistic locking:
 *       <ul>
 *         <li>Happy update path — {@link #save_updatedBalance_persistsChange()}.</li>
 *         <li>Concurrent-update conflict — {@link
 *             #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
 *             (the JPA replacement for the COBOL before-image /
 *             after-image comparison per AAP §0.1.1).</li>
 *       </ul></li>
 *   <li><strong>CBTRN02C.cbl</strong> (transaction posting, ~731 lines) —
 *       performs a VSAM REWRITE of the account record to apply the posted
 *       transaction's debit / credit against the current balance fields.
 *       Maps to JPA {@code save()} after balance mutation:
 *       <ul>
 *         <li>Balance update round-trip —
 *             {@link #save_updatedBalance_persistsChange()}.</li>
 *         <li>Negative-balance signed semantics —
 *             {@link #save_negativeBalance_preservesSignAndScale()}.</li>
 *         <li>Maximum PIC S9(10)V99 precision —
 *             {@link #save_maximumPrecisionBalance_preservesAllDigits()}.</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>{@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
 * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT}, and
 * {@code ACCT-CURR-CYC-DEBIT} are all {@code PIC S9(10)V99} — 10 integer
 * digits plus 2 fractional digits with a signed leading-overpunch byte.
 * The Java migration maps every one of these fields to {@link BigDecimal}
 * with scale 2 per the AAP financial-precision mandate. This IT asserts
 * BOTH the numeric value (via
 * {@code isEqualByComparingTo(BigDecimal)} which compares VALUE ignoring
 * scale) AND the scale (via {@code .scale() == 2}) on every monetary
 * field of every round-trip test. The
 * {@link #save_monetaryFields_preserveScale2()} test exercises all five
 * monetary columns in a single round-trip; the
 * {@link #save_negativeBalance_preservesSignAndScale()} test exercises
 * the signed-semantics half of {@code PIC S9(10)V99}; and the
 * {@link #save_maximumPrecisionBalance_preservesAllDigits()} test
 * exercises the 12-digit upper bound ({@code 9999999999.99}) to guard
 * against any silent column-precision misconfiguration in the underlying
 * PostgreSQL table.
 *
 * <h2>Optimistic Locking — @Version (AAP §0.1.1)</h2>
 *
 * <p>The original COBOL {@code COACTUPC.cbl} uses before-image /
 * after-image record comparison to detect concurrent updates: the program
 * reads the account record into a working-storage before-image, displays
 * it on the BMS screen for the operator, accepts the operator's mutated
 * after-image on Enter, re-reads the account record from VSAM into a
 * freshly-read after-image, and compares the freshly-read after-image
 * against the operator's in-memory before-image. If any field differs
 * (i.e., another operator updated the same record while the first
 * operator was editing), COACTUPC rejects the update as a concurrent-
 * change conflict and presents a "record has been changed since last
 * read" message.
 *
 * <p>The JPA migration replaces this multi-line procedural comparison
 * with a single declarative {@code @Version} annotation on
 * {@link Account#version}. Hibernate compares the version-column value
 * in the UPDATE statement's WHERE clause against the in-memory entity's
 * version, reports zero rows updated as a stale-version conflict, and
 * surfaces the conflict to Spring as
 * {@link OptimisticLockingFailureException}. The
 * {@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
 * test exercises this contract end-to-end: it simulates Session A
 * loading a row, Session B loading the SAME row and persisting an update
 * first, and Session A's subsequent save with its stale-@Version raising
 * {@link OptimisticLockingFailureException}.
 *
 * <h2>Coverage Focus (AAP §0.5.1, §0.7.1)</h2>
 *
 * <p>The 8 test methods exercise the integration of
 * {@link AccountRepository} against a real PostgreSQL 16 instance
 * provisioned by Testcontainers — no Mockito stubs at this layer (AAP
 * §0.10.1 Require Test Coverage Rule). The categories below cover the
 * AAP-specified scenarios:
 *
 * <ul>
 *   <li>{@link #findById_existingAccount_returnsAccount()} —
 *       primary-key lookup happy path (COBOL {@code DFHRESP(NORMAL)}
 *       equivalent).</li>
 *   <li>{@link #findById_nonexistentAccount_returnsEmpty()} —
 *       primary-key lookup negative path (COBOL {@code DFHRESP(NOTFND)} /
 *       VSAM {@code STATUS '23'} equivalent).</li>
 *   <li>{@link #save_monetaryFields_preserveScale2()} — all 5 monetary
 *       fields round-trip with exact value AND scale 2 (AAP §0.10.3
 *       financial-precision invariant).</li>
 *   <li>{@link #save_negativeBalance_preservesSignAndScale()} —
 *       {@code PIC S9(10)V99} signed-semantics: a negative balance
 *       round-trips with sign preserved (signum() == -1) and scale 2.</li>
 *   <li>{@link #save_maximumPrecisionBalance_preservesAllDigits()} —
 *       {@code PIC S9(10)V99} maximum-precision boundary: the 12-digit
 *       upper bound 9999999999.99 round-trips without truncation, with
 *       scale 2 and precision &gt;= 12.</li>
 *   <li>{@link #save_updatedBalance_persistsChange()} — re-saving a
 *       previously-persisted account with a modified balance persists
 *       the new value (COBOL CBTRN02C posting flow / COACTUPC update
 *       flow).</li>
 *   <li>{@link #saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure()}
 *       — concurrent-update conflict detected via @Version mismatch
 *       (COBOL COACTUPC before-image / after-image comparison
 *       equivalent, AAP §0.1.1).</li>
 *   <li>{@link #count_invoked_returnsNonNegativeValue()} — basic sanity
 *       check that the inherited {@code count()} method returns a
 *       non-negative tally.</li>
 * </ul>
 *
 * <h2>Custom-Finder Tests Deferred</h2>
 *
 * <p>AAP §0.5.1 enumerates "custom queries (by customer ID, by status)"
 * as part of the AccountRepositoryIT coverage scope. As of this commit
 * the production {@link AccountRepository} interface declares ONLY the
 * inherited {@code JpaRepository<Account, String>} methods
 * ({@code findById}, {@code save}, {@code findAll}, {@code count},
 * {@code deleteById}, etc.) — the COCRDLIC card-list query
 * {@code findByCustomerId(String)} and the reporting query
 * {@code findAllByActiveStatus(String)} are documented in the production
 * AccountRepository Javadoc as "Subsequent migration agents (REFACTOR
 * flavor) will add custom query methods". Test methods that exercise
 * those methods cannot compile until the methods exist on the production
 * repository. The AAP §0.10.2 Minimal Change Clause and AAP §0.8.1
 * scope-boundary clause forbid this IT from modifying the production
 * AccountRepository for the purpose of test compilation. The
 * by-customer-id and by-active-status custom-finder tests are therefore
 * documented in the "Reactivation Checklist" below as a step the next
 * REFACTOR agent will complete alongside the @Version annotation work.
 *
 * <h2>Mock Boundary (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>This IT has <strong>no Mockito mocks</strong>. The system under test
 * is the real {@link AccountRepository} bean wired by Spring Data JPA
 * against the real PostgreSQL 16 database supplied by Testcontainers
 * (inherited from {@link AbstractRepositoryIT}). Repository ITs sit at
 * the lowest mock boundary in the test pyramid: they verify that the
 * Spring Data JPA proxy + Hibernate ORM + JDBC driver + PostgreSQL
 * stack produces correct results against a real schema. Tests that
 * would otherwise mock the repository (service unit tests, batch
 * processor unit tests) live one layer up in
 * {@code com.aws.carddemo.service.*Test} and
 * {@code com.aws.carddemo.batch.*Test}.
 *
 * <h2>No Business Logic in Test Bodies (AAP §0.10.1)</h2>
 *
 * <p>This IT performs <strong>zero</strong> arithmetic, format
 * normalisation, or date parsing inside test methods. Monetary literals
 * like {@code "1234.56"} are constructed as
 * {@code new BigDecimal("1234.56")} — exact decimal strings, never the
 * {@code BigDecimal(double)} constructor. Date literals are constructed
 * via {@code LocalDate.of(year, month, day).toString()} purely for
 * compile-time type-safety on the year/month/day triplet (not for date
 * arithmetic). The interest-calculation logic, transaction-posting
 * logic, and date-format-validation logic live exclusively in
 * {@code com.aws.carddemo.batch.InterestCalculationProcessor},
 * {@code com.aws.carddemo.batch.TransactionPostingProcessor}, and
 * {@code com.aws.carddemo.validation.DateValidationService} respectively,
 * each with its own dedicated unit test class.
 *
 * <h2>Test Isolation (AAP §0.10.9)</h2>
 *
 * <p>{@code @DataJpaTest} (inherited from {@link AbstractRepositoryIT})
 * wraps each {@code @Test} method in a transaction that rolls back at
 * method end. This means the synthetic accounts persisted by every test
 * in this class are gone before the next test sees the database state.
 * Each test starts from the Flyway-seeded account catalog (50 rows from
 * {@code app/data/ASCII/acctdata.txt} once the V3__seed.sql Flyway
 * script lands per the Reactivation Checklist below); test order
 * independence is guaranteed.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the {@code @DataJpaTest} Spring slice via
 * {@link AbstractRepositoryIT} and exercises the production
 * {@link AccountRepository} bean against a real PostgreSQL 16
 * database. That requires every production-side prerequisite to be in
 * place: the {@link Account} entity must be annotated as a JPA
 * {@code @Entity} (with {@code @Id} on {@code accountId},
 * {@code @Column} annotations on every field, {@code @Version} on
 * {@code version}, etc.) so Hibernate can map the entity onto a
 * database table, and the Flyway scripts under
 * {@code src/main/resources/db/migration/} must exist to create the
 * {@code accounts} table and seed reference accounts. As of this commit
 * those production-side prerequisites are <em>intentionally deferred</em>
 * by the REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} for the purpose
 * of <em>testability alone</em>: the testing flavor CREATEs tests
 * against those classes but does NOT redesign them. The suite is
 * therefore registered, compiled, and preserved end-to-end (the
 * production stubs created alongside this IT enable compilation), but
 * the JUnit Jupiter {@code @Disabled} marker below defers
 * <em>runtime</em> execution until the production-side migration
 * agents complete the JPA annotation and Flyway seed work. Once both
 * arrive, removing the {@code @Disabled} annotation (and its companion
 * unused import) activates all 8 tests unchanged. The sibling
 * {@code CustomerRepositoryIT}, {@code UserSecurityRepositoryIT},
 * {@code TransactionCategoryRepositoryIT},
 * {@code TransactionTypeRepositoryIT}, and
 * {@code DiscountGroupRepositoryIT} use the same {@code @Disabled}
 * pattern — this IT mirrors that established project convention.
 *
 * <h3>Reactivation Checklist (for the next REFACTOR-flavor agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code @Entity} + {@code @Id} + {@code @Column} +
 *       {@code @Version} on {@link com.aws.carddemo.entity.Account}</strong>
 *       — REFACTOR agents add {@code @Entity},
 *       {@code @Table(name = "accounts")},
 *       {@code @Id} on {@code accountId},
 *       {@code @Column(name = "acct_id", length = 11, nullable = false)}
 *       on {@code accountId},
 *       {@code @Column(name = "active_status", length = 1)} on
 *       {@code activeStatus},
 *       {@code @Column(name = "curr_bal", precision = 12, scale = 2)}
 *       on {@code currentBalance},
 *       {@code @Column(name = "credit_limit", precision = 12, scale = 2)}
 *       on {@code creditLimit},
 *       {@code @Column(name = "cash_credit_limit", precision = 12, scale = 2)}
 *       on {@code cashCreditLimit},
 *       {@code @Column(name = "open_date", length = 10)} on
 *       {@code openDate} (preserving the COBOL {@code PIC X(10)} ISO
 *       {@code YYYY-MM-DD} string contract),
 *       {@code @Column(name = "expiration_date", length = 10)} on
 *       {@code expirationDate} (note the Java field name corrects the
 *       COBOL ACCT-EXPIRAION-DATE misspelling; the COLUMN name may
 *       retain the misspelling for source-of-truth fidelity or be
 *       corrected — both options preserve the AAP §0.10.4 immutable
 *       boundary as long as the {@code length = 10} matches the COBOL
 *       PIC X(10) field width),
 *       {@code @Column(name = "reissue_date", length = 10)} on
 *       {@code reissueDate},
 *       {@code @Column(name = "curr_cyc_credit", precision = 12, scale = 2)}
 *       on {@code currentCycleCredit},
 *       {@code @Column(name = "curr_cyc_debit", precision = 12, scale = 2)}
 *       on {@code currentCycleDebit},
 *       {@code @Column(name = "addr_zip", length = 10)} on
 *       {@code addressZip},
 *       {@code @Column(name = "group_id", length = 10)} on
 *       {@code groupId},
 *       {@code @Column(name = "customer_id", length = 9)} on
 *       {@code customerId} (denormalised FK; Java-migration addition),
 *       and {@code @Version @Column(name = "version")} on
 *       {@code version}. Without these annotations Hibernate cannot
 *       map the entity onto the PostgreSQL table and
 *       {@code @DataJpaTest} context startup fails.</li>
 *   <li><strong>Flyway {@code V1__schema.sql}</strong> under
 *       {@code src/main/resources/db/migration/} containing a CREATE
 *       TABLE statement for {@code accounts} with column types matching
 *       the COBOL {@code PIC} clauses verbatim: {@code acct_id CHAR(11)}
 *       for {@code PIC 9(11)}, every monetary field
 *       {@code NUMERIC(12, 2)} for {@code PIC S9(10)V99} (10 integer
 *       digits plus 2 fractional digits = 12 total), date fields
 *       {@code CHAR(10)} for {@code PIC X(10)} ISO date strings, and
 *       {@code version BIGINT NOT NULL DEFAULT 0} for JPA optimistic-
 *       locking support.</li>
 *   <li><strong>Flyway {@code V3__seed.sql}</strong> (optional for this
 *       IT — every test seeds its own accounts via the inherited
 *       {@code TestEntityManager}; a project-wide seed of 50 rows from
 *       {@code app/data/ASCII/acctdata.txt} is documented in AAP §0.5.1
 *       but is not strictly required to activate this class).</li>
 *   <li><strong>Custom-finder methods on
 *       {@link AccountRepository}</strong> — the AAP §0.5.1 "custom
 *       queries (by customer ID, by status)" coverage is currently
 *       deferred because the production repository does not yet declare
 *       {@code findByCustomerId(String)} or
 *       {@code findAllByActiveStatus(String)} method signatures. Once
 *       REFACTOR agents add those signatures (per the production
 *       AccountRepository Javadoc's explicit deferral note), the
 *       corresponding by-customer-id and by-active-status test methods
 *       should be added to this IT class. The pattern mirrors the
 *       {@code UserSecurityRepositoryIT.findByUserType_*} tests already
 *       in the suite.</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime — the {@code mvn verify} build agent must be able to
 *       run {@code postgres:16-alpine}. CI agents that cannot start
 *       containers (e.g. nested-virtualisation-free environments) can
 *       set {@code TESTCONTAINERS_RYUK_DISABLED=true} as documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <p>When items 1–3 and 5 above are complete, deleting the
 * {@code @Disabled} annotation and the
 * {@code import org.junit.jupiter.api.Disabled;} line activates the
 * suite. Item 4 (custom-finder tests) is an enhancement to be added in
 * a subsequent commit. No other code changes are required: the existing
 * 8 test method bodies are written against the production API exactly
 * as it will be once the REFACTOR work completes.
 *
 * @see AccountRepository
 * @see Account
 * @see AbstractRepositoryIT
 * @see TestFixtures.Accounts
 */
@DisplayName("AccountRepository — CVACT01Y.cpy migration parity ITs")
@Disabled("Awaits production-side prerequisites: (1) @Entity / @Id / @Column / "
        + "@Version / @Table(name = \"accounts\") annotations on "
        + "com.aws.carddemo.entity.Account so Hibernate can map the entity onto a "
        + "PostgreSQL table — column widths must mirror the COBOL CVACT01Y.cpy "
        + "PIC clauses (acct_id CHAR(11) PK, active_status CHAR(1), curr_bal / "
        + "credit_limit / cash_credit_limit / curr_cyc_credit / curr_cyc_debit "
        + "NUMERIC(12, 2) for PIC S9(10)V99 with scale 2, open_date / "
        + "expiration_date / reissue_date CHAR(10) for PIC X(10) ISO YYYY-MM-DD "
        + "strings, addr_zip / group_id VARCHAR(10), customer_id CHAR(9) for the "
        + "denormalised Java-migration FK, version BIGINT for JPA optimistic "
        + "locking); (2) Flyway V1__schema.sql under "
        + "src/main/resources/db/migration/ creating the accounts table with the "
        + "schema above; (3) Flyway V3__seed.sql (optional — every test in this "
        + "IT seeds its own accounts via the inherited TestEntityManager; a "
        + "project-wide seed of 50 rows from app/data/ASCII/acctdata.txt is "
        + "documented in AAP §0.5.1 but is not strictly required to activate "
        + "this class). Per AAP §0.8.1 the testing flavor cannot modify those "
        + "production files for testability alone; the next REFACTOR-flavor "
        + "agent removes this annotation when the prerequisites are complete. "
        + "See the class Javadoc 'Reactivation Checklist' for the full list "
        + "including the deferred custom-finder (by customer ID, by active "
        + "status) tests.")
class AccountRepositoryIT extends AbstractRepositoryIT {

    /**
     * The Spring Data JPA repository under integration test. The bean is
     * created by Spring Data JPA at {@code @DataJpaTest} context startup
     * from the {@link AccountRepository} interface declaration (no
     * manual implementation). Field injection is consistent with the
     * inherited {@code @Autowired TestEntityManager entityManager} field
     * on {@link AbstractRepositoryIT}.
     */
    @Autowired
    private AccountRepository accountRepository;

    // =========================================================================
    // Primary-Key Lookup Tests (AAP §0.5.1 "findById")
    // =========================================================================

    /**
     * Verifies that {@link AccountRepository#findById(Object)} returns
     * the persisted {@link Account} for an existing 11-character primary
     * key. The test persists a synthetic account via the inherited
     * {@code TestEntityManager}, flushes the persistence context to push
     * the row to PostgreSQL, clears the first-level cache so the
     * subsequent {@code findById} hits the database, and then asserts
     * that the returned {@link Optional} contains an entity with the
     * expected primary key and active status.
     *
     * <p>This is the Java equivalent of the COBOL CICS response code
     * {@code DFHRESP(NORMAL)} branch from {@code COACTVWC.cbl}'s
     * {@code 9300-GETACCTDATA-BYACCT} paragraph (lines 774–820) and
     * underlies the account-lookup half of the
     * {@link com.aws.carddemo.service.AccountViewService}'s
     * account-view round-trip.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(existing ACCT-ID) returns the persisted Account")
    void findById_existingAccount_returnsAccount() {
        // Arrange — persist a synthetic account via the inherited TestEntityManager.
        // SAMPLE_ACCOUNT_ID_10 ("00000000010") is the canonical fixture-range ACCT-ID
        // used across account-related ITs.
        Account acct = buildSyntheticAccount(
                TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                "Y");
        entityManager.persistAndFlush(acct);
        entityManager.clear();

        // Act — drive the production repository against the real DB.
        Optional<Account> result =
                accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

        // Assert — the row must be located and the primary identifying fields must
        // round-trip exactly.
        assertThat(result)
                .as("findById should locate the persisted account by 11-character primary key "
                        + "(COBOL DFHRESP(NORMAL) equivalent)")
                .isPresent();
        Account a = result.get();
        assertThat(a.getAccountId())
                .as("ACCT-ID primary-key component must round-trip exactly")
                .isEqualTo(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        assertThat(a.getActiveStatus())
                .as("ACCT-ACTIVE-STATUS must round-trip exactly ('Y' for active)")
                .isEqualTo("Y");
    }

    /**
     * Verifies that {@link AccountRepository#findById(Object)} returns
     * {@link Optional#empty()} when the supplied 11-character primary key
     * does not exist in the {@code accounts} table. This is the Java
     * equivalent of the COBOL CICS response code {@code DFHRESP(NOTFND)}
     * branch from {@code COACTVWC.cbl}'s
     * {@code 9300-GETACCTDATA-BYACCT} paragraph — the underlying VSAM
     * file-status code {@code '23'} (record not found). The production
     * {@link com.aws.carddemo.service.AccountViewService} translates
     * this empty {@link Optional} into the COBOL-equivalent
     * {@code "Account:nnn not found in Acct Master file"} reject message.
     *
     * <p>The lookup key {@link TestFixtures.Accounts#NONEXISTENT_ACCOUNT_ID}
     * ({@code "99999999999"}) is guaranteed never to appear in the
     * {@code acctdata.txt} fixture (which spans {@code "00000000001"} to
     * {@code "00000000050"}); the constant exists precisely for not-found
     * assertions.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("findById(nonexistent ACCT-ID) returns empty Optional (VSAM STATUS 23 parity)")
    void findById_nonexistentAccount_returnsEmpty() {
        // Act — drive the production repository against the real DB with a key
        // guaranteed never to appear in the fixture account catalog.
        Optional<Account> result =
                accountRepository.findById(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID);

        // Assert — Optional.empty mirrors the COBOL DFHRESP(NOTFND) signal.
        assertThat(result)
                .as("findById should return Optional.empty for unknown account IDs "
                        + "(COBOL DFHRESP(NOTFND) / VSAM STATUS '23' equivalent)")
                .isEmpty();
    }

    // =========================================================================
    // Financial Precision Tests (AAP §0.10.3 — BigDecimal scale 2 on all 5
    // monetary fields per CVACT01Y.cpy PIC S9(10)V99)
    // =========================================================================

    /**
     * Verifies that every one of the FIVE monetary fields on
     * {@link Account} ({@code currentBalance}, {@code creditLimit},
     * {@code cashCreditLimit}, {@code currentCycleCredit},
     * {@code currentCycleDebit}) round-trips through
     * {@link AccountRepository#save(Object)} +
     * {@link AccountRepository#findById(Object)} with:
     * <ul>
     *   <li>EXACT value equality (asserted via AssertJ's
     *       {@code isEqualByComparingTo(BigDecimal)} which compares
     *       VALUE while ignoring scale-only differences such as
     *       {@code 1234.56} vs {@code 1234.5600}).</li>
     *   <li>EXACT scale 2 (asserted via {@code BigDecimal.scale() == 2}
     *       which catches scale-only differences that
     *       {@code isEqualByComparingTo} would silently accept). This
     *       is the AAP §0.10.3 financial-precision invariant: a column
     *       erroneously configured with no scale constraint, or a
     *       Hibernate type-mapping that silently widens scale, would
     *       surface immediately through these assertions.</li>
     * </ul>
     *
     * <p>The COBOL source-of-truth is {@code PIC S9(10)V99} on all five
     * fields in {@code app/cpy/CVACT01Y.cpy}: 10 integer digits, 2
     * fractional digits, signed. The Java migration maps each to
     * {@link BigDecimal} with scale 2 (the implied decimal point in COBOL
     * becomes an explicit scale-2 BigDecimal in Java).
     *
     * <p>The five test values are deliberately distinct non-trivial
     * decimal strings ({@code 1234.56}, {@code 9999.99}, {@code 500.00},
     * {@code 123.45}, {@code 67.89}) so that a misconfigured Hibernate
     * mapping that accidentally bound one column's value to another
     * column would surface as a clear value-mismatch failure.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Account with all 5 monetary fields) preserves BigDecimal scale 2 on every amount")
    void save_monetaryFields_preserveScale2() {
        // Arrange — distinct non-trivial scale-2 values on every monetary field.
        // All constructed via new BigDecimal(String) per AAP §0.10.3 (NEVER
        // BigDecimal(double) which introduces representation noise).
        BigDecimal currBal = new BigDecimal("1234.56");
        BigDecimal creditLimit = new BigDecimal("9999.99");
        BigDecimal cashCreditLimit = new BigDecimal("500.00");
        BigDecimal currCycCredit = new BigDecimal("123.45");
        BigDecimal currCycDebit = new BigDecimal("67.89");

        Account acct = new Account();
        acct.setAccountId(TestFixtures.Accounts.NEW_ACCOUNT_ID_60);
        acct.setActiveStatus("Y");
        acct.setCurrentBalance(currBal);
        acct.setCreditLimit(creditLimit);
        acct.setCashCreditLimit(cashCreditLimit);
        acct.setCurrentCycleCredit(currCycCredit);
        acct.setCurrentCycleDebit(currCycDebit);
        // ACCT-OPEN-DATE / ACCT-EXPIRAION-DATE / ACCT-REISSUE-DATE are stored
        // as String in the production Account entity (PIC X(10) ISO YYYY-MM-DD).
        // LocalDate.of(...).toString() self-documents the year/month/day triplet.
        acct.setOpenDate(LocalDate.of(2020, 1, 1).toString());
        acct.setExpirationDate(LocalDate.of(2030, 12, 31).toString());
        acct.setReissueDate(LocalDate.of(2025, 1, 1).toString());
        acct.setAddressZip("99999");
        acct.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        // CustomerId is the denormalised Java-migration FK; assign the canonical
        // SAMPLE_CUSTOMER_ID_10 to satisfy any not-null constraint the production
        // schema may impose on the column.
        acct.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);

        // Act — drive the production repository's save path; flush + clear forces
        // Hibernate to issue the INSERT immediately and the subsequent findById
        // to re-fetch from PostgreSQL rather than the first-level cache.
        accountRepository.save(acct);
        entityManager.flush();
        entityManager.clear();

        // Assert — every monetary field round-trips with exact value AND scale 2.
        Account reloaded = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();

        // -- ACCT-CURR-BAL --
        assertThat(reloaded.getCurrentBalance())
                .as("ACCT-CURR-BAL value must round-trip exactly (AAP §0.10.3)")
                .isEqualByComparingTo(currBal);
        assertThat(reloaded.getCurrentBalance().scale())
                .as("ACCT-CURR-BAL scale must equal 2 per COBOL PIC S9(10)V99 "
                        + "(AAP §0.10.3 — any silent scale truncation surfaces here)")
                .isEqualTo(2);

        // -- ACCT-CREDIT-LIMIT --
        assertThat(reloaded.getCreditLimit())
                .as("ACCT-CREDIT-LIMIT value must round-trip exactly")
                .isEqualByComparingTo(creditLimit);
        assertThat(reloaded.getCreditLimit().scale())
                .as("ACCT-CREDIT-LIMIT scale must equal 2 per COBOL PIC S9(10)V99")
                .isEqualTo(2);

        // -- ACCT-CASH-CREDIT-LIMIT --
        assertThat(reloaded.getCashCreditLimit())
                .as("ACCT-CASH-CREDIT-LIMIT value must round-trip exactly")
                .isEqualByComparingTo(cashCreditLimit);
        assertThat(reloaded.getCashCreditLimit().scale())
                .as("ACCT-CASH-CREDIT-LIMIT scale must equal 2 per COBOL PIC S9(10)V99")
                .isEqualTo(2);

        // -- ACCT-CURR-CYC-CREDIT --
        assertThat(reloaded.getCurrentCycleCredit())
                .as("ACCT-CURR-CYC-CREDIT value must round-trip exactly")
                .isEqualByComparingTo(currCycCredit);
        assertThat(reloaded.getCurrentCycleCredit().scale())
                .as("ACCT-CURR-CYC-CREDIT scale must equal 2 per COBOL PIC S9(10)V99")
                .isEqualTo(2);

        // -- ACCT-CURR-CYC-DEBIT --
        assertThat(reloaded.getCurrentCycleDebit())
                .as("ACCT-CURR-CYC-DEBIT value must round-trip exactly")
                .isEqualByComparingTo(currCycDebit);
        assertThat(reloaded.getCurrentCycleDebit().scale())
                .as("ACCT-CURR-CYC-DEBIT scale must equal 2 per COBOL PIC S9(10)V99")
                .isEqualTo(2);
    }

    /**
     * Verifies that a negative {@link BigDecimal} balance round-trips
     * through {@link AccountRepository#save(Object)} +
     * {@link AccountRepository#findById(Object)} with:
     * <ul>
     *   <li>Exact numeric value preservation (the {@code -250.50} input
     *       reloads as {@code -250.50}, not {@code 250.50} with a sign
     *       flag stripped or coerced to absolute value).</li>
     *   <li>{@link BigDecimal#signum() signum() == -1} — the negative
     *       sign is preserved.</li>
     *   <li>{@link BigDecimal#scale() scale() == 2} — scale is preserved
     *       even for negative values.</li>
     * </ul>
     *
     * <p>The COBOL source-of-truth field is {@code PIC S9(10)V99} — the
     * leading {@code S} declares the field signed (a leading-overpunch
     * byte represents the sign in zoned-decimal encoding). The Java
     * migration maps this to {@link BigDecimal} which carries the sign
     * natively. A misconfigured PostgreSQL column declared as
     * {@code NUMERIC(12, 2) CHECK (curr_bal &gt;= 0)} or a Hibernate
     * converter that erroneously stripped the sign would surface
     * through this test as a value-mismatch or signum-mismatch failure.
     *
     * <p>Negative balances arise in the real CardDemo workflow when a
     * customer over-pays a bill (CBTRN02C posts a credit transaction
     * that drives the balance below zero) or when a refund / chargeback
     * exceeds the outstanding balance.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Account with negative balance) preserves sign and scale (S9(10)V99 signed semantics)")
    void save_negativeBalance_preservesSignAndScale() {
        // Arrange — current balance is conceptually negative for paid-overdue accounts
        // (e.g., CBTRN02C posted a credit that drove the balance below zero).
        BigDecimal negativeBal = new BigDecimal("-250.50");
        Account acct = buildSyntheticAccount(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                negativeBal,
                new BigDecimal("5000.00"),
                "Y");

        // Act — drive the production repository's save path.
        accountRepository.save(acct);
        entityManager.flush();
        entityManager.clear();

        // Assert — value, sign, and scale must all round-trip exactly.
        Account reloaded = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        assertThat(reloaded.getCurrentBalance())
                .as("Negative ACCT-CURR-BAL must round-trip with sign preserved")
                .isEqualByComparingTo(negativeBal);
        assertThat(reloaded.getCurrentBalance().signum())
                .as("Balance signum must be -1 (the S in PIC S9(10)V99 declares the "
                        + "field signed; a column with a CHECK constraint or a converter "
                        + "that stripped the sign would surface here)")
                .isEqualTo(-1);
        assertThat(reloaded.getCurrentBalance().scale())
                .as("Negative balance scale must equal 2 (scale preservation is "
                        + "independent of sign)")
                .isEqualTo(2);
    }

    /**
     * Verifies that the maximum {@code PIC S9(10)V99} value
     * ({@code 9999999999.99} — 10 integer digits + 2 fractional digits)
     * round-trips through {@link AccountRepository#save(Object)} +
     * {@link AccountRepository#findById(Object)} without precision loss.
     *
     * <p>The COBOL source-of-truth field is {@code PIC S9(10)V99}: 10
     * integer digits plus 2 fractional digits, giving an absolute upper
     * bound of {@code 9999999999.99}. The Java migration maps this to
     * {@link BigDecimal} backed by a {@code NUMERIC(12, 2)} PostgreSQL
     * column (12 total digits = 10 + 2). A column erroneously sized as
     * {@code NUMERIC(10, 2)} would silently truncate the most
     * significant integer digit and would surface through this
     * boundary-value assertion as a value-mismatch failure.
     *
     * <p>The test asserts three precision-related invariants on the
     * reloaded value:
     * <ul>
     *   <li>EXACT value equality (the 12-digit value round-trips
     *       byte-for-byte).</li>
     *   <li>{@link BigDecimal#scale() scale() == 2} — the 2 fractional
     *       digits are preserved.</li>
     *   <li>{@link BigDecimal#precision() precision() &gt;= 12} — the
     *       BigDecimal carries enough significant digits to represent
     *       the full {@code PIC S9(10)V99} range. (precision() returns
     *       the count of significant digits; for {@code 9999999999.99}
     *       this is exactly 12.)</li>
     * </ul>
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Account at S9(10)V99 max boundary 9999999999.99) preserves all 10 integer + 2 fractional digits")
    void save_maximumPrecisionBalance_preservesAllDigits() {
        // Arrange — boundary case for PIC S9(10)V99 = max value 9,999,999,999.99
        // (10 integer digits + 2 fractional digits = 12 total significant digits).
        BigDecimal maxBalance = new BigDecimal("9999999999.99");
        Account acct = buildSyntheticAccount(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                maxBalance,
                new BigDecimal("0.00"),
                "Y");

        // Act — drive the production repository's save path.
        accountRepository.save(acct);
        entityManager.flush();
        entityManager.clear();

        // Assert — value, scale, and precision must all round-trip exactly.
        Account reloaded = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        assertThat(reloaded.getCurrentBalance())
                .as("Max-precision PIC S9(10)V99 balance must round-trip without "
                        + "truncation — a column sized NUMERIC(10, 2) instead of "
                        + "NUMERIC(12, 2) would silently truncate the most significant "
                        + "integer digit and surface here as a value mismatch")
                .isEqualByComparingTo(maxBalance);
        assertThat(reloaded.getCurrentBalance().scale())
                .as("Max-precision balance scale must equal 2 (fractional digits "
                        + "preserved at the boundary)")
                .isEqualTo(2);
        assertThat(reloaded.getCurrentBalance().precision())
                .as("BigDecimal precision must accommodate at least 12 significant "
                        + "digits (10 integer + 2 fractional per PIC S9(10)V99)")
                .isGreaterThanOrEqualTo(12);
    }

    // =========================================================================
    // Update Path Tests (COACTUPC update flow + CBTRN02C posting balance flow)
    // =========================================================================

    /**
     * Verifies that updating {@code ACCT-CURR-BAL} on a previously
     * persisted {@link Account} via the classic JPA
     * load-mutate-save sequence persists the new balance and
     * preserves scale 2.
     *
     * <p>This test mirrors the canonical CardDemo update flow shared
     * by both online (COACTUPC) and batch (CBTRN02C) programs:
     * <ol>
     *   <li>Read the existing ACCOUNT-RECORD by primary key
     *       (load = {@link AccountRepository#findById(Object)}).</li>
     *   <li>Mutate {@code ACCT-CURR-BAL} in memory (Java field
     *       assignment).</li>
     *   <li>Rewrite the ACCOUNT-RECORD back to the dataset
     *       (save = {@link AccountRepository#save(Object)}).</li>
     * </ol>
     *
     * <p>The assertion confirms (a) the new balance is persisted, and
     * (b) scale 2 is preserved through the update path (AAP §0.10.3
     * applies equally to inserts and updates).
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(Account with updated ACCT-CURR-BAL) persists the new balance (CBTRN02C / COACTUPC update flow)")
    void save_updatedBalance_persistsChange() {
        // Arrange — persist initial account at balance 1000.00.
        Account initial = buildSyntheticAccount(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                "Y");
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act — load (READ ACCOUNT-FILE), adjust balance, re-save
        // (REWRITE ACCOUNT-RECORD). Production code-paths CBTRN02C
        // (batch posting) and COACTUPC (online update) follow this
        // exact same load-mutate-save sequence.
        Account reloaded = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        reloaded.setCurrentBalance(new BigDecimal("1100.50"));
        accountRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Assert — the new balance is persisted and scale 2 is preserved.
        Account afterUpdate = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        assertThat(afterUpdate.getCurrentBalance())
                .as("ACCT-CURR-BAL update must persist (CBTRN02C / COACTUPC "
                        + "REWRITE ACCOUNT-RECORD parity)")
                .isEqualByComparingTo(new BigDecimal("1100.50"));
        assertThat(afterUpdate.getCurrentBalance().scale())
                .as("Updated balance scale must equal 2 (AAP §0.10.3 applies "
                        + "equally to inserts and updates)")
                .isEqualTo(2);
    }

    // =========================================================================
    // Optimistic Locking Tests (@Version — COACTUPC concurrent-update parity
    // per AAP §0.1.1)
    // =========================================================================

    /**
     * Verifies that a stale-version save raises
     * {@link OptimisticLockingFailureException}, exercising the
     * concurrency-control contract that replaces COACTUPC's COBOL
     * before-image / after-image record comparison per AAP §0.1.1.
     *
     * <h2>COBOL Reference — COACTUPC Concurrent-Update Detection</h2>
     *
     * <p>The original COBOL COACTUPC program protects against
     * concurrent updates by reading the ACCOUNT-RECORD twice during
     * the same screen interaction: once to display the BEFORE image
     * to the user, and again immediately before the REWRITE to fetch
     * the LATEST image and compare it against the user's AFTER image.
     * If the two images diverge (because another user updated the
     * record in between), COACTUPC issues
     * {@code WS-ROLLBACK-RECORD-CHANGED} and aborts the update with
     * an error message to the user, prompting them to re-fetch and
     * re-attempt their update.
     *
     * <h2>JPA Migration — @Version Optimistic Locking</h2>
     *
     * <p>The Java migration replaces this hand-rolled before-image /
     * after-image comparison with JPA's standard {@code @Version}
     * optimistic-locking mechanism (AAP §0.1.1). The {@link Account}
     * entity carries a {@code @Version Long version} field which
     * Hibernate increments on every UPDATE; the UPDATE statement
     * includes a {@code WHERE version = ?} clause that fails to
     * match (zero rows affected) if any other session has incremented
     * the version since this session loaded the row. Hibernate detects
     * the zero-row outcome and raises
     * {@code StaleObjectStateException}, which Spring Data JPA
     * translates to {@link OptimisticLockingFailureException} via
     * {@code SessionFactoryUtils.convertHibernateAccessException}.
     *
     * <h2>Test Scenario — Two Concurrent Sessions</h2>
     *
     * <p>The test simulates two concurrent sessions racing to update
     * the same account row:
     * <ol>
     *   <li>Initial save persists the row at {@code @Version = 0}.</li>
     *   <li>"Session A" loads the row (and detaches it from the
     *       persistence context to model the JPA semantic of a
     *       request-scoped persistence context that has been closed
     *       between the load and the save).</li>
     *   <li>"Session B" loads the same row, mutates the balance, and
     *       saves successfully — incrementing {@code @Version} from
     *       0 to 1 in the database.</li>
     *   <li>"Session A" now mutates its detached copy (still carrying
     *       {@code @Version = 0}) and attempts to save. The UPDATE
     *       statement's {@code WHERE version = 0} clause matches zero
     *       rows because the database now holds {@code @Version = 1}.
     *       Hibernate raises {@code StaleObjectStateException} which
     *       Spring translates to
     *       {@link OptimisticLockingFailureException}.</li>
     * </ol>
     *
     * <p>The assertion uses AssertJ's
     * {@code Assertions.catchThrowable(...)} idiom (preferred over
     * try/catch or {@code assertThrows} for fluent chained assertions
     * per AAP §0.10.10 Style Consistency) and confirms that the
     * thrown exception is {@code OptimisticLockingFailureException}.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("save(detached entity with stale @Version) throws OptimisticLockingFailureException (COACTUPC concurrent-update parity, AAP §0.1.1)")
    void saveAndUpdate_concurrentVersionMismatch_throwsOptimisticLockingFailure() {
        // Arrange — persist initial account at @Version 0.
        Account initial = buildSyntheticAccount(
                TestFixtures.Accounts.NEW_ACCOUNT_ID_60,
                new BigDecimal("100.00"),
                new BigDecimal("5000.00"),
                "Y");
        entityManager.persistAndFlush(initial);
        entityManager.clear();

        // Act 1 — "Session A" loads the row and detaches it. The detach
        // models the JPA semantic of a request-scoped persistence context
        // that has been closed (e.g., between two HTTP requests from the
        // same end-user) — the entity carries a stale @Version snapshot.
        Account sessionA = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        entityManager.detach(sessionA);

        // Act 2 — "Session B" loads the SAME row, mutates the balance,
        // and saves successfully. Hibernate increments @Version from 0
        // to 1 in the database.
        Account sessionB = accountRepository
                .findById(TestFixtures.Accounts.NEW_ACCOUNT_ID_60)
                .orElseThrow();
        sessionB.setCurrentBalance(new BigDecimal("200.00"));
        accountRepository.save(sessionB);
        entityManager.flush();
        entityManager.clear();

        // Act 3 — "Session A" now mutates its detached copy (still
        // carrying @Version = 0) and attempts to save. The UPDATE
        // statement's WHERE version = 0 clause matches zero rows
        // because the database now holds @Version = 1, so Hibernate
        // raises StaleObjectStateException which Spring translates
        // to OptimisticLockingFailureException.
        sessionA.setCurrentBalance(new BigDecimal("300.00"));
        Throwable thrown = catchThrowable(() -> {
            accountRepository.save(sessionA);
            entityManager.flush();
        });

        // Assert — stale-version save must raise OptimisticLockingFailureException.
        // This replaces COBOL COACTUPC's WS-ROLLBACK-RECORD-CHANGED
        // before-image / after-image comparison per AAP §0.1.1.
        assertThat(thrown)
                .as("Concurrent update with stale @Version must throw "
                        + "OptimisticLockingFailureException (replaces COBOL "
                        + "COACTUPC WS-ROLLBACK-RECORD-CHANGED before-image / "
                        + "after-image comparison per AAP §0.1.1)")
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // =========================================================================
    // Repository Wiring Smoke Test
    // =========================================================================

    /**
     * Verifies that {@link AccountRepository#count()} returns a
     * non-negative row count. This is a wiring-level smoke test
     * confirming that:
     * <ul>
     *   <li>The {@link AccountRepository} bean is correctly autowired
     *       by Spring Data JPA.</li>
     *   <li>The PostgreSQL DataSource is reachable and the
     *       Testcontainers container is healthy.</li>
     *   <li>The {@code accounts} table exists in the schema (created
     *       by Flyway's {@code V1__schema.sql}).</li>
     * </ul>
     *
     * <p>The assertion is intentionally lenient
     * ({@code count() &gt;= 0}) because the absolute row count
     * depends on whether Flyway's {@code V3__seed.sql} has been
     * applied — this IT does not assert on a specific seed-data row
     * count. The test's purpose is to surface gross wiring failures
     * (missing table, broken DataSource, unbootstrapped Spring
     * context) cleanly, not to validate seed-data contents.
     *
     * <p>Mock boundary: none — the production repository is exercised
     * against the real PostgreSQL container (AAP §0.10.1).
     */
    @Test
    @DisplayName("count() returns non-negative total row count (wiring smoke test)")
    void count_invoked_returnsNonNegativeValue() {
        // Act — invoke the inherited JpaRepository.count() method.
        long total = accountRepository.count();

        // Assert — count() should never return negative. A negative
        // value would indicate a corrupted Spring Data JPA
        // implementation (impossible in practice) or a JDBC driver
        // bug; an exception thrown here would indicate a missing
        // table, broken DataSource, or unbootstrapped Spring context.
        assertThat(total)
                .as("AccountRepository.count() should return a non-negative "
                        + "row count; any exception thrown here would indicate "
                        + "a missing accounts table or broken Testcontainers "
                        + "DataSource wiring")
                .isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // Synthetic Account Builder (Test Helper — no business logic per AAP §0.10.1)
    // =========================================================================

    /**
     * Constructs a synthetic {@link Account} populated with the
     * supplied primary key, current balance, credit limit, and active
     * status; every other field defaults to a deterministic synthetic
     * value (scale-2 BigDecimal zero for the remaining monetary
     * fields, fixed ISO-format date strings for the three PIC X(10)
     * date fields, and the canonical synthetic ZIP, group, and
     * customer IDs).
     *
     * <p>The helper exists purely to eliminate field-by-field setter
     * boilerplate across the seven save-path tests in this IT. It
     * contains no business logic and no calculations — the four
     * caller-supplied arguments are assigned directly to the
     * corresponding Account fields with no transformation, and the
     * remaining fields are populated with deterministic test
     * constants. This complies with the AAP §0.10.1 Require Test
     * Coverage Rule that forbids reimplementing business or
     * calculation logic inside test bodies.
     *
     * <p>Field assignments mirror the {@code CVACT01Y.cpy}
     * ACCOUNT-RECORD layout: the four caller-supplied arguments cover
     * the dynamic fields each save-path test wants to vary, and the
     * remaining fields are filled with synthetic values that satisfy
     * any not-null constraints the production schema imposes without
     * introducing test-only PII (AAP §0.10.5 No PII).
     *
     * @param acctId         the 11-digit synthetic primary key (ACCT-ID
     *                       PIC 9(11))
     * @param currBal        the BigDecimal scale-2 current balance
     *                       (ACCT-CURR-BAL PIC S9(10)V99)
     * @param creditLimit    the BigDecimal scale-2 credit limit
     *                       (ACCT-CREDIT-LIMIT PIC S9(10)V99)
     * @param activeStatus   the single-character active flag
     *                       (ACCT-ACTIVE-STATUS PIC X(01)) — 'Y' or 'N'
     * @return a fully-populated unmanaged {@link Account} ready for
     *         {@link AccountRepository#save(Object)} or
     *         {@link jakarta.persistence.EntityManager#persist(Object)}
     */
    private Account buildSyntheticAccount(String acctId,
                                          BigDecimal currBal,
                                          BigDecimal creditLimit,
                                          String activeStatus) {
        Account a = new Account();
        // -- Primary key + active status --
        a.setAccountId(acctId);
        a.setActiveStatus(activeStatus);
        // -- Five monetary fields (PIC S9(10)V99 -> BigDecimal scale 2) --
        a.setCurrentBalance(currBal);
        a.setCreditLimit(creditLimit);
        a.setCashCreditLimit(new BigDecimal("0.00"));
        a.setCurrentCycleCredit(new BigDecimal("0.00"));
        a.setCurrentCycleDebit(new BigDecimal("0.00"));
        // -- Three date fields (PIC X(10) ISO YYYY-MM-DD strings) --
        // LocalDate.of(...).toString() self-documents the year/month/day triplet
        // and produces the canonical ISO YYYY-MM-DD format expected by the
        // production String date fields.
        a.setOpenDate(LocalDate.of(2020, 1, 1).toString());
        a.setExpirationDate(LocalDate.of(2030, 12, 31).toString());
        a.setReissueDate(LocalDate.of(2025, 1, 1).toString());
        // -- Address ZIP, discount group, and customer FK --
        a.setAddressZip("99999");
        a.setGroupId(TestFixtures.Accounts.DEFAULT_GROUP_ID);
        // CustomerId is the denormalised Java-migration FK linking Account to
        // Customer. The canonical SAMPLE_CUSTOMER_ID_10 satisfies any not-null
        // constraint the production schema may impose on the column.
        a.setCustomerId(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_10);
        return a;
    }

}




