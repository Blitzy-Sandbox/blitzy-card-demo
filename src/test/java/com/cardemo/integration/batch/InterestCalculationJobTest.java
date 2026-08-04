/*
 * ******************************************************************
 * Component   : InterestCalculationJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11,
 *               Testcontainers 2.0.3, PostgreSQL 16, LocalStack)
 * Function    : Proves the migrated interest calculator reproduces
 *               CBACT04C where only an assembled job over a real
 *               database and object store can show it: the
 *               account-update boundary the control break at :196
 *               produces, what a flush does when it fires - interest
 *               posted and both cycle accumulators reset to zero -
 *               the two-stage disclosure-group fallback whose
 *               first miss is a normal control path and whose second
 *               miss is fatal, the zero-rate skip, the abend on a
 *               missing cross reference, and the fresh 350-byte
 *               SYSTRAN generation that receives every emitted
 *               record while the transaction cluster receives none.
 * Source      : app/jcl/INTCALC.jcl:22 (EXEC PGM=CBACT04C,
 *               PARM='2022071800'), app/cbl/CBACT04C.cbl:186-222,
 *               :219-220, :309, :340, :350-370, :393-413, :415-440,
 *               :443-460, :462-470, :473-516, :518-520, :631,
 *               app/cpy/CVTRA01Y.cpy, app/cpy/CVTRA02Y.cpy,
 *               app/cpy/CVACT01Y.cpy, app/catlg/LISTCAT.txt,
 *               app/data/ASCII/tcatbal.txt,
 *               app/data/ASCII/discgrp.txt,
 *               app/data/ASCII/acctdata.txt,
 *               app/data/ASCII/trancatg.txt,
 *               app/data/ASCII/cardxref.txt, CONTRIBUTING.md:33-34
 *               @ 7756d89
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
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Integration test for the assembled interest calculation job, covering the five behaviours of
 * {@code app/cbl/CBACT04C.cbl} that are invisible to a unit test and that a plausible-looking
 * implementation gets wrong.
 *
 * <h2>What it does</h2>
 *
 * <p>Every assertion here is a property of the <em>assembled</em> topology - the job, its step, its ordered
 * reader, its processor and its object-store writer - measured against a real PostgreSQL 16 schema and a real
 * object store. Five behaviours are pinned, each with its verified source locator.
 *
 * <ul>
 *   <li><strong>The account-update boundary, {@code app/cbl/CBACT04C.cbl:186-222}.</strong>
 *       {@code :188 PERFORM UNTIL END-OF-FILE = 'Y'} tests before each iteration, so the outer
 *       {@code :189 IF END-OF-FILE = 'N'} is always true inside the body and its {@code ELSE} at
 *       {@code :219-220} pairs with it at column 16, while the inner {@code :191 IF} and its {@code END-IF}
 *       at {@code :218} stand at column 20. The inner {@code IF} has no {@code ELSE}, so when the read at
 *       {@code :190} sets end of file - the single {@code MOVE 'Y' TO END-OF-FILE} in the program, at
 *       {@code :340} - control falls straight to the loop test. {@code 1050-UPDATE-ACCOUNT} is reached from
 *       {@code :196}, the control-break arm that flushes the account that has just completed, so it fires
 *       once per completed account: over the fifty accounts of {@code app/data/ASCII/tcatbal.txt} that is
 *       49 flushes plus the account still in progress when the browse ends. This test measures that boundary
 *       against the assembled job and, in the same run, measures what a flush does - the account written,
 *       its accumulated interest added to {@code ACCT-CURR-BAL} and both cycle accumulators zeroed. The one
 *       authoritative record of every prose-versus-corpus variance in this tree, with its severity and its
 *       remediation, is the register in {@code src/main/java/com/cardemo/package-info.java}; no assertion
 *       message here restates it.</li>
 *   <li><strong>The cycle reset at {@code app/cbl/CBACT04C.cbl:350-370}.</strong> The flush adds the
 *       accumulated interest to the balance and then zeroes <em>both</em> cycle accumulators before the
 *       rewrite. Dropping that half breaks nothing until the next posting cycle, where the over-limit
 *       temporary balance is computed by subtracting the debit accumulator.</li>
 *   <li><strong>The two-stage rate fallback, {@code :415-440} then {@code :443-460}.</strong> Stage one
 *       accepts {@code '00'} or {@code '23'}; only {@code '23'} substitutes the group identifier at
 *       {@code :437} - a seven-character literal moved into a {@code PIC X(10)} field, so the effective key is
 *       {@code DEFAULT} followed by three blanks - and performs stage two. Stage two reads with no
 *       {@code INVALID KEY} clause and accepts {@code '00'} alone, so a second miss abends. The fallback is
 *       the <em>normal</em> path here rather than an edge case: {@code ACCT-GROUP-ID} is ten blanks on all
 *       fifty records of {@code app/data/ASCII/acctdata.txt}, so stage one misses for every account.</li>
 *   <li><strong>The zero-rate skip, {@code :214-217}.</strong> A zero rate emits no transaction, accumulates
 *       nothing, and skips the fee paragraph. The shipped category-balance fixture carries the pair
 *       {@code 010001} on all fifty rows, whose {@code DEFAULT} rate is non-zero, so the skip branch is
 *       unreachable without a synthetic row - which this test inserts and removes.</li>
 *   <li><strong>The generation output, {@code :309 OPEN OUTPUT TRANSACT-FILE}.</strong> The output file is
 *       declared with sequential organisation and {@code app/jcl/INTCALC.jcl:37-41} allocates a brand-new
 *       generation of {@code SYSTRAN} at {@code LRECL=350} on every run, so generated interest reaches the
 *       keyed transaction cluster only later, through the combine job. Here the records must all land in the
 *       generation and none in the table.</li>
 * </ul>
 *
 * <p><strong>Deliberately not asserted here, and why.</strong> Three things are owned elsewhere and
 * re-asserting them would be duplication rather than coverage. The rate formula's rounding behaviour belongs
 * to {@code com.cardemo.unit.batch.InterestCalculationProcessorTest}; only the end-to-end posted amount is
 * checked here. The parameter validator's rejection arms, the job bean's identity and the duplicate-instance
 * refusal belong to {@code com.cardemo.integration.batch.InterestCalculationJobIntegrationTest}. The ordered
 * browse and the amount field's zoned-decimal encoding belong to
 * {@code com.cardemo.integration.repository.TransactionCategoryBalanceRepositoryTest} and
 * {@code com.cardemo.unit.batch.TransactionWriterTest} respectively; what is asserted here instead is that
 * the job <em>consumed</em> rows in account order, which is a job-level fact neither of those can show.
 * {@code 1400-COMPUTE-FEES} at {@code :518-520} is an empty but genuinely reachable paragraph retained for
 * control-flow parity in the production processor; it produces no effect, so no effect is asserted for it and
 * its presence is not treated as a defect. The four-character status line of
 * {@code 9910-DISPLAY-IO-STATUS} is owned by {@code com.cardemo.model.enums.FileStatus} and its prefix
 * constant is deliberately <em>not</em> restated anywhere in this file; the abend assertions check the
 * expanded status that reaches the abend payload instead.
 *
 * <p><strong>Two things are stated as unavailable rather than approximated.</strong> First, the
 * boundary-parity baseline is <em>Not available</em>. No captured legacy output exists anywhere in this
 * repository to compare a run against - a search for expected-output, baseline, golden, {@code .out} and
 * {@code sysout} artefacts finds only dataset <em>definition</em> job control and no recorded data. What
 * would be needed is one captured run of {@code app/cbl/CBACT04C.cbl} over a known input state, taken from
 * the system of record. A baseline regenerated from this implementation would show only that the
 * implementation agrees with itself, so none is written here and no expected bytes are invented. Second, the
 * unavailable-file status {@code '35'} is <em>Not available</em> as a behaviour to pin: neither that literal
 * nor the response code it corresponds to occurs anywhere in the COBOL corpus, so there is no source
 * semantics to reproduce and no test for it is invented.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and run everything with the pinned wrapper: {@code ./mvnw clean verify}. Compile alone with
 * {@code ./mvnw -q test-compile}.
 *
 * <p><strong>Failsafe collects this tier, and the path is load-bearing.</strong> Failsafe 3.5.4 is bound to
 * {@code src/test/java/com/cardemo/integration/**} and runs it at {@code integration-test} and
 * {@code verify}, even though the class keeps the {@code Test} suffix; Surefire is bound to
 * {@code .../unit/**} and excludes this tree. A class moved out of {@code integration/**} matches neither
 * include set, is collected by neither plugin, and silently never runs while the build stays green. Do not
 * rename or relocate this class.
 *
 * <p><strong>A reachable container runtime is a hard prerequisite.</strong> The parent harness starts one
 * PostgreSQL 16 container and one LocalStack container, applies the three Flyway migrations and provisions
 * the buckets and the FIFO queue. Where no daemon or socket is available the correct report is that the gate
 * is blocked, together with the prerequisite - never an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test} is active. No datasource address, no credential and no cloud endpoint appears in
 *       this file: the parent binds all of them from the running containers. There is no ambient-environment
 *       lookup and no read or write of a JVM-wide system property here.</li>
 *   <li>Container images are the parent's: PostgreSQL 16 by digest and {@code localstack/localstack:4.14.0}.
 *       This class declares no container of its own.</li>
 *   <li>Time comes from the parent's injected clock, pinned to {@code 2022-06-10T19:27:53Z} in UTC. The
 *       expected 26-character timestamp is derived from it rather than written out.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}, so every launch here is explicit.</li>
 *   <li>The three Flyway migrations are a precondition: they own the schema, the three non-unique alternate
 *       indexes and the seed data this test measures against - fifty accounts, fifty category balances,
 *       fifty-one disclosure groups, eighteen transaction categories and an empty transaction table.</li>
 *   <li>The one job parameter is {@code parmDate}, carrying the ten characters
 *       {@code app/jcl/INTCALC.jcl:22} supplies. It is eight date digits followed by two zeros and is
 *       <em>not</em> an ISO date; it is concatenated into every generated transaction identifier.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Container startup fails, or the whole tier is skipped</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run.</dd>
 *
 *   <dt>{@code Could not resolve dependencies ... org.testcontainers:localstack:2.0.3}</dt>
 *   <dd>The Testcontainers 2.x line renamed every module artefact and the bare {@code postgresql},
 *       {@code localstack} and {@code junit-jupiter} ids do not exist at 2.0.3. The remedy has two halves and
 *       both are required: pin the managed version by overriding the version property, never by importing a
 *       second bill of materials, and use only the four prefixed coordinates. Both halves are already in
 *       place in the build descriptor; neither may be removed.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}</dt>
 *   <dd>Compilation escalates every warning to an error, so one raw type or one deprecated call fails the
 *       build. An unused import is not among them, so that prohibition is review-enforced rather than
 *       compiler-enforced: every import in this file is used.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. Every
 *       method here that launches carries it, because Spring Batch refuses job repository work inside an
 *       existing transaction and commits each chunk on its own transaction regardless.</dd>
 *
 *   <dt>The run abends with {@code ERROR READING DEFAULT DISCLOSURE GROUP}</dt>
 *   <dd>A required {@code DEFAULT} rate row is missing. That is the contract, not a defect: stage two accepts
 *       {@code '00'} alone. One test here removes such a row deliberately and restores it; if the message
 *       appears in another test, the restore of a previous run did not complete.</dd>
 *
 *   <dt>A balance assertion fails although the number looks right</dt>
 *   <dd>An equality matcher was used on a {@code java.math.BigDecimal}. A
 *       {@code NUMERIC(n,2)} column always reads back at scale 2, and
 *       {@code new BigDecimal("1.25").equals(new BigDecimal("1.250"))} is {@code false}. Every money
 *       assertion here compares by value.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be: one instance per test method, no shared mutable state, no second
 * thread and no second connection. <strong>This class declares no {@code static} field of any kind</strong> -
 * the parent permits exactly two in this package and both are its containers - so every constant below is an
 * immutable instance field initialised at its declaration.
 */
@DisplayName("Interest calculation job: the control-break flush boundary, the fatal second rate miss, and the "
        + "350-byte SYSTRAN generation")
class InterestCalculationJobTest extends AbstractBatchIntegrationTest {

    /** The assembled job under test, injected by the bean name its configuration registers. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Reads and seeds account state through the same finder the processor's control break uses. */
    @Autowired
    private AccountRepository accountRepository;

    /** Exercises both stages of the rate resolution, and removes and restores a rate row. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Inserts and removes the synthetic zero-rate row the shipped fixture cannot supply. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** Reads the card number the generated record must carry, and removes and restores a row. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Counts committed rows in the transaction table, which no repository in this test's dependency set
     * reaches.
     *
     * <p>Reads only, and with one constant statement carrying no parameter at all. <strong>Writes must not go
     * through this object.</strong> The connection pool is configured with auto-commit switched off, so a
     * write issued here outside a transaction reports its affected-row count and is then rolled back when the
     * connection returns to the pool - silently, because the count is returned before the rollback happens.
     * Every write in this class therefore goes through a repository, whose methods carry their own
     * transaction and genuinely commit.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Reads the emitted generation objects back byte for byte. */
    @Autowired
    private S3Client s3Client;

    /** The output bucket, bound from the same key the job binds rather than named here. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** The generation prefix, bound from the same key the job binds, with the job's own default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.systran:gdg/systran}")
    private String systranPrefix;

    /** The ten-character {@code PARM-DATE} of {@code app/jcl/INTCALC.jcl:22}: eight date digits then two zeros. */
    private final String parmDate = "2022071800";

    /** Name of the job's one parameter, as {@code app/cbl/CBACT04C.cbl:178} declares {@code PARM-DATE}. */
    private final String parmDateParameter = "parmDate";

    /** Rows in {@code app/data/ASCII/tcatbal.txt}, one per account, so also the distinct account count. */
    private final long seededAccountCount = 50L;

    /** First account in the key-ordered browse, and therefore the first flushed. */
    private final long firstSeededAccountId = 1L;

    /** Last account in the key-ordered browse - the one the unreachable {@code ELSE} would have flushed. */
    private final long lastSeededAccountId = 50L;

    /** {@code TRANCAT-TYPE-CD} on all fifty seeded category balances, and the generated type code. */
    private final String interestTypeCode = "01";

    /** {@code TRANCAT-CD} on all fifty seeded category balances, whose {@code DEFAULT} rate is non-zero. */
    private final Integer interestLookupCategory = Integer.valueOf(1);

    /** A pair whose {@code DEFAULT} rate is {@code +0.00} in {@code app/data/ASCII/discgrp.txt}. */
    private final String zeroRateTypeCode = "02";

    /** Category of that zero-rate pair; {@code 020001} is a real row of {@code app/data/ASCII/trancatg.txt}. */
    private final Integer zeroRateCategory = Integer.valueOf(1);

    /** The bare seven-character form of the group identifier {@code app/cbl/CBACT04C.cbl:437} moves. */
    private final String bareDefaultGroupId = "DEFAULT";

    /**
     * A sibling rate family in {@code app/data/ASCII/discgrp.txt} that also carries a {@code 01/0001} row.
     *
     * <p>It exists in this test only to be left alone. Because it is present and non-empty, a rate lookup
     * that collapsed the two stages into one query would resolve a rate even with the {@code DEFAULT} row
     * removed, so its survival is what makes the abend test discriminating rather than merely red.
     */
    private final String storedSiblingGroupId = "A000000000";

    /** A rate the fixture gives no {@code 010001} row, so an applied rate proves which row supplied it. */
    private final BigDecimal probeInterestRate = new BigDecimal("12.00");

    /** A non-zero category balance, so that the computed interest is observable at all. */
    private final BigDecimal probeCategoryBalance = new BigDecimal("100.00");

    /** A cycle credit no fixture record carries, so its survival is unambiguous evidence of a missed flush. */
    private final BigDecimal staleCycleCredit = new BigDecimal("77.77");

    /** A cycle debit distinct from the credit, so a transposed reset cannot pass. */
    private final BigDecimal staleCycleDebit = new BigDecimal("33.33");

    /** The literal divisor of {@code app/cbl/CBACT04C.cbl:465}; never rewritten as a two-step division. */
    private final BigDecimal monthlyInterestDivisor = new BigDecimal("1200");

    /** Record length of {@code app/cpy/CVTRA05Y.cpy}, and the {@code LRECL} of {@code SYSTRAN}. */
    private final int recordLength = 350;

    /** Zero-padding width of both numeric segments of a generation key, so lexical order is creation order. */
    private final int keyNumberWidth = 19;

    /**
     * The headline boundary: which accounts {@code 1050-UPDATE-ACCOUNT} writes, and what it writes.
     *
     * <p>Purpose: measure the account-update boundary the control break at {@code app/cbl/CBACT04C.cbl:196}
     * produces against the assembled job, and, in the same run, measure the three mutations a flush applies
     * at {@code :352-354} - the accumulated interest added to {@code ACCT-CURR-BAL}, and <em>both</em> cycle
     * accumulators reset to zero. Inputs: the fifty seeded category balances, each given a non-zero balance
     * so interest exists at all, and all fifty accounts given non-zero cycle accumulators so a missing reset
     * is visible. Output: none. Side effects: the run commits, and the parent's reset hook restores every
     * money column from the frozen fixtures afterwards. Error modes: a failure on the flushed population
     * means the break key or the flush body changed; a failure on the counted boundary means the reachability
     * of the two {@code PERFORM 1050-UPDATE-ACCOUNT} sites changed.
     *
     * <p><strong>Why the seeding is necessary rather than incidental.</strong> The shipped fixtures carry
     * {@code +0.00} in every category balance and in both cycle accumulators of every account, so over the
     * untouched seed a flushed account and an unwritten one are byte-identical and the boundary is
     * unobservable. Giving the accumulators values no fixture record carries is what turns "was this account
     * flushed?" into a question the database can answer.
     */
    @Test
    @DisplayName("1. every account the control break at CBACT04C.cbl:196 completes is flushed with its "
            + "interest posted and both cycle accumulators zeroed, and the boundary is the browse's last "
            + "account 00000000050")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyCompletedAccountIsFlushedAndTheBoundaryIsTheBrowsesLastAccount() {
        seedStaleCycleAccumulators();
        seedCategoryBalances(probeCategoryBalance);
        final Map<Long, BigDecimal> balancesBefore = committedBalancesByAccount();
        assertThat(balancesBefore)
                .as("app/data/ASCII/acctdata.txt seeds exactly fifty accounts")
                .hasSize((int) seededAccountCount);

        final JobExecution execution = launchInterestCalculation();
        assertRunCompletedWithoutAbend(execution);

        int flushed = 0;
        int unflushed = 0;
        for (final Account account : accountRepository.findAll()) {
            final long accountId = account.getAccountId().longValue();
            if (accountId == lastSeededAccountId) {
                unflushed++;
                assertThat(account.getCurrentCycleCredit())
                        .as("account %d is the last row of the key-ordered browse, so no successor row "
                                + "raises the control break at CBACT04C.cbl:194 that would flush it",
                                accountId)
                        .isEqualByComparingTo(staleCycleCredit);
                assertThat(account.getCurrentCycleDebit())
                        .as("ACCT-CURR-CYC-DEBIT of account %d for the same reason; the two values differ "
                                + "so a transposed reset cannot pass this assertion", accountId)
                        .isEqualByComparingTo(staleCycleDebit);
                assertThat(account.getCurrentBalance())
                        .as("and its balance is therefore what it was before the run")
                        .isEqualByComparingTo(balancesBefore.get(account.getAccountId()));
            } else {
                flushed++;
                assertThat(account.getCurrentBalance())
                        .as("ADD WS-TOTAL-INT TO ACCT-CURR-BAL at CBACT04C.cbl:352 for account %d, so a "
                                + "flushed account has strictly more balance than it started the run with",
                                accountId)
                        .isGreaterThan(balancesBefore.get(account.getAccountId()));
                assertThat(account.getCurrentCycleCredit())
                        .as("account %d is flushed at the break to its successor, so MOVE 0 TO "
                                + "ACCT-CURR-CYC-CREDIT at CBACT04C.cbl:353 has run", accountId)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(account.getCurrentCycleDebit())
                        .as("and MOVE 0 TO ACCT-CURR-CYC-DEBIT at CBACT04C.cbl:354, the other half")
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        assertThat(flushed)
                .as("the flush is reached from the control break at CBACT04C.cbl:196, which fires once for "
                        + "every account a successor row completes; over %d distinct accounts in one "
                        + "key-ordered browse that is %d flushes", seededAccountCount, seededAccountCount - 1L)
                .isEqualTo((int) (seededAccountCount - 1L));
        assertThat(unflushed)
                .as("and the boundary is the single account still in progress when the browse ends, which "
                        + "is the last account in key order and no other")
                .isEqualTo(1);
    }

    /**
     * What a flush actually does: interest posted, and <em>both</em> cycle accumulators zeroed.
     *
     * <p>Purpose: pin all three mutations of {@code 1050-UPDATE-ACCOUNT} at
     * {@code app/cbl/CBACT04C.cbl:352-354} on every account that is flushed, with the posted amount checked
     * against the rate the fixture actually holds rather than against a hand-typed number. Inputs: as test
     * one. Output: none. Side effects: the run commits and is restored by the parent's reset hook. Error
     * modes: a failure on the balance means the accumulation or the formula changed; a failure on an
     * accumulator means the reset half was dropped, which would not otherwise surface until a later posting
     * cycle computed its over-limit test from a stale accumulator.
     */
    @Test
    @DisplayName("2. every flushed account has its accumulated interest posted and both cycle accumulators "
            + "zeroed, per CBACT04C.cbl:352-354")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyFlushedAccountHasInterestPostedAndBothCycleAccumulatorsZeroed() {
        seedStaleCycleAccumulators();
        seedCategoryBalances(probeCategoryBalance);
        final BigDecimal appliedRate = defaultFamilyRate(interestTypeCode, interestLookupCategory);
        final BigDecimal expectedInterest = expectedMonthlyInterest(probeCategoryBalance, appliedRate);
        assertThat(expectedInterest)
                .as("the probe balance and the seeded rate must yield a non-zero posting, or the assertion "
                        + "below would hold even if nothing were posted")
                .isGreaterThan(BigDecimal.ZERO);
        final Map<Long, BigDecimal> balancesBefore = committedBalancesByAccount();

        final JobExecution execution = launchInterestCalculation();
        assertRunCompletedWithoutAbend(execution);

        for (final Account account : accountRepository.findAll()) {
            if (account.getAccountId().longValue() == lastSeededAccountId) {
                continue;
            }
            assertThat(account.getCurrentBalance())
                    .as("ADD WS-TOTAL-INT TO ACCT-CURR-BAL at CBACT04C.cbl:352 for account %s, whose single "
                            + "category-balance row contributed one month's interest", account.getAccountId())
                    .isEqualByComparingTo(balancesBefore.get(account.getAccountId()).add(expectedInterest));
            assertThat(account.getCurrentCycleCredit())
                    .as("MOVE 0 TO ACCT-CURR-CYC-CREDIT at CBACT04C.cbl:353")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getCurrentCycleDebit())
                    .as("MOVE 0 TO ACCT-CURR-CYC-DEBIT at CBACT04C.cbl:354; dropping this half breaks the "
                            + "over-limit arithmetic of the next posting cycle rather than this run")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * The fallback is the normal path, and the rate every account receives comes from the {@code DEFAULT}
     * family specifically.
     *
     * <p>Purpose: prove three things at once that a collapsed single-query rate lookup would also appear to
     * satisfy. First, that stage one genuinely misses: the probe is built from the group identifier the
     * account row actually holds, not from an invented blank literal, and {@code ACCT-GROUP-ID} is ten blanks
     * on all fifty records of {@code app/data/ASCII/acctdata.txt}. Second, that the bare seven-character form
     * {@code app/cbl/CBACT04C.cbl:437} moves into a {@code PIC X(10)} field and the blank-padded form the
     * table stores resolve the same row - the {@code CHAR(10)} blank-insensitivity the whole fallback rests
     * on - asserted without padding anything in this test. Third, and this is the part a single query cannot
     * fake, that the applied rate is the one on the {@code DEFAULT} row: the row's rate is changed to a value
     * no {@code 010001} row in any of the three families carries, and the interest the job posts moves with
     * it.
     *
     * <p>Inputs: the seeded rate table and category balances. Output: none. Side effects: the
     * {@code DEFAULT/01/0001} rate is changed and restored in a {@code finally} block, and the run commits.
     * Error modes: if the posted interest still tracks the original rate, the lookup is reading some other
     * row - a sibling family, a cached value, or a collapsed query - and the fallback is not the two-stage
     * mechanism the source describes.
     */
    @Test
    @DisplayName("3. the blank account group falls back to the DEFAULT family for every account, and the "
            + "bare and padded forms of the group identifier resolve the same CHAR(10) row")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theDefaultFamilyResolvesTheRateForEveryAccount() {
        final Optional<Account> firstAccount =
                accountRepository.findById(Long.valueOf(firstSeededAccountId));
        assertThat(firstAccount)
                .as("account %d is seeded by V3__seed_data.sql from app/data/ASCII/acctdata.txt",
                        firstSeededAccountId)
                .isPresent();
        final String storedGroupId = firstAccount.orElseThrow().getGroupId();
        assertThat(storedGroupId)
                .as("ACCT-GROUP-ID is blank on every seeded account, which is what makes the fallback the "
                        + "normal path rather than an edge case")
                .isBlank();
        assertThat(disclosureGroupRepository.findById(
                        new DisclosureGroupId(storedGroupId, interestTypeCode, interestLookupCategory)))
                .as("stage one at CBACT04C.cbl:416 reads with the account's own group identifier and must "
                        + "miss, yielding the '23' that :436 turns into the retry")
                .isEmpty();

        final Optional<DisclosureGroup> paddedProbe = disclosureGroupRepository
                .findDefaultGroupRate(interestTypeCode, interestLookupCategory);
        final Optional<DisclosureGroup> bareProbe = disclosureGroupRepository.findById(
                new DisclosureGroupId(bareDefaultGroupId, interestTypeCode, interestLookupCategory));
        assertThat(paddedProbe)
                .as("the production stage-two finder carries the blank-padded literal internally")
                .isPresent();
        assertThat(bareProbe)
                .as("MOVE 'DEFAULT' into PIC X(10) yields DEFAULT followed by three blanks, and a CHAR(10) "
                        + "comparison ignores trailing blanks, so the bare form must resolve the same row; "
                        + "if it does not, the column type is wrong upstream and no padding here would fix it")
                .isPresent();
        final DisclosureGroup seededRow = bareProbe.orElseThrow();
        final BigDecimal seededRate = seededRow.getInterestRate();
        assertThat(seededRate)
                .as("both probes must find one row, so their rates are the same value")
                .isEqualByComparingTo(paddedProbe.orElseThrow().getInterestRate());
        assertThat(seededRate)
                .as("app/data/ASCII/discgrp.txt:18 reads DEFAULT   01000100150{, a non-zero rate, which is "
                        + "why the shipped fixture never exercises the zero-rate skip")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(probeInterestRate)
                .as("the probe rate must differ from the seeded one, or the mutation below would prove "
                        + "nothing about which row supplied the applied rate")
                .isNotEqualByComparingTo(seededRate);

        try {
            disclosureGroupRepository.save(new DisclosureGroup(seededRow.getId(), probeInterestRate));
            seedCategoryBalances(probeCategoryBalance);
            final BigDecimal expectedInterest =
                    expectedMonthlyInterest(probeCategoryBalance, probeInterestRate);
            final Map<Long, BigDecimal> balancesBefore = committedBalancesByAccount();

            final JobExecution execution = launchInterestCalculation();
            assertRunCompletedWithoutAbend(execution);

            int accountsResolvedThroughTheDefaultFamily = 0;
            for (final Account account : accountRepository.findAll()) {
                if (account.getAccountId().longValue() == lastSeededAccountId) {
                    continue;
                }
                accountsResolvedThroughTheDefaultFamily++;
                assertThat(account.getCurrentBalance())
                        .as("the interest posted to account %s must track the DEFAULT row's rate, because "
                                + "that row is the only one stage two at CBACT04C.cbl:444 reads",
                                account.getAccountId())
                        .isEqualByComparingTo(
                                balancesBefore.get(account.getAccountId()).add(expectedInterest));
            }
            assertThat(accountsResolvedThroughTheDefaultFamily)
                    .as("every account the run flushes resolved its rate through the DEFAULT family")
                    .isEqualTo((int) (seededAccountCount - 1L));
        } finally {
            disclosureGroupRepository.save(new DisclosureGroup(seededRow.getId(), seededRate));
        }
    }

    /**
     * Stage two accepts {@code '00'} alone, so a second miss is fatal rather than a zero rate.
     *
     * <p>Purpose: pin the asymmetry between the two stages. Stage one at {@code app/cbl/CBACT04C.cbl:422}
     * accepts {@code '00'} or {@code '23'}; stage two at {@code :444} reads with no {@code INVALID KEY}
     * clause and {@code :446} accepts {@code '00'} only, so {@code :458} reaches
     * {@code 9999-ABEND-PROGRAM}. Inputs: the seeded rate table with the one required {@code DEFAULT} row
     * removed. Output: none. Side effects: the row is removed and restored in a {@code finally} block.
     * Error modes: a run that completes, that treats the missing row as a zero rate, or that finds a rate
     * anyway - the last of which is why the two sibling families are asserted to be still present. They are:
     * a lookup collapsed into one query with an {@code OR}, a {@code UNION} or a {@code COALESCE} would find
     * {@code A000000000/01/0001} and post interest, and this test is what makes that indistinguishable
     * implementation fail.
     *
     * <p>The abend payload is asserted in full - culprit, reason, message and the four-character expanded
     * status - because the exception type alone would pass for any of several unrelated failures.
     */
    @Test
    @DisplayName("4. removing the one required DEFAULT rate row abends the run with the CBACT04C payload, "
            + "even though the sibling rate families are untouched")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void removingTheRequiredDefaultRateRowAbendsTheRun() {
        final Optional<DisclosureGroup> required = disclosureGroupRepository.findById(
                new DisclosureGroupId(bareDefaultGroupId, interestTypeCode, interestLookupCategory));
        assertThat(required)
                .as("the row this test removes must exist first, or its removal would prove nothing")
                .isPresent();
        final DisclosureGroup seededRow = required.orElseThrow();
        final DisclosureGroupId seededKey = seededRow.getId();
        final BigDecimal seededRate = seededRow.getInterestRate();

        try {
            disclosureGroupRepository.delete(seededRow);
            assertThat(disclosureGroupRepository.findById(
                            new DisclosureGroupId(storedSiblingGroupId, interestTypeCode,
                                    interestLookupCategory)))
                    .as("the sibling family's 01/0001 row is deliberately left in place; a rate lookup "
                            + "collapsed into a single query would find it and the run would succeed")
                    .isPresent();
            seedCategoryBalances(probeCategoryBalance);

            final JobExecution execution = launchInterestCalculation();

            assertThat(execution.getStatus())
                    .as("stage two admits '00' alone, so a record-not-found status reaches "
                            + "9999-ABEND-PROGRAM rather than yielding a zero rate")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .as("the flow routes every step outcome through its decider, which classifies a typed "
                            + "abend as its own exit code rather than as a plain step failure; collapsing "
                            + "the two would make return code 12 indistinguishable from return code 8, and "
                            + "an abend is the only failure surface CBACT04C has")
                    .isEqualTo("ABEND")
                    .isNotEqualTo(ExitStatus.FAILED.getExitCode());

            final Optional<FatalProcessingException> abend = firstAbend(execution);
            assertThat(abend)
                    .as("the failure must be the typed abend, not an untyped framework error; recorded "
                            + "failures were %s", execution.getAllFailureExceptions())
                    .isPresent();
            final FatalProcessingException fatal = abend.orElseThrow();
            assertThat(fatal.getAbendCulprit())
                    .as("ABEND-CULPRIT names the program the abend is attributed to")
                    .isEqualTo("CBACT04C");
            assertThat(fatal.getAbendReason())
                    .as("ABEND-REASON carries the DISPLAY literal of CBACT04C.cbl:455 verbatim")
                    .isEqualTo("ERROR READING DEFAULT DISCLOSURE GROUP");
            assertThat(fatal.getAbendMessage())
                    .as("the operator-facing message opens with the same legacy literal and carries the "
                            + "four-character status expansion that 9910-DISPLAY-IO-STATUS renders")
                    .startsWith("ERROR READING DEFAULT DISCLOSURE GROUP")
                    .contains("0023");
            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("MOVE 999 TO ABCODE at CBACT04C.cbl:631, immediately before CALL 'CEE3ABD'")
                    .isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("the abend surfaces to the operating system as process return code 12")
                    .isEqualTo(12);

            assertThat(committedTransactionRowCount())
                    .as("an abended run posts nothing anywhere")
                    .isZero();
            assertThat(publishedGenerationKeys(execution))
                    .as("the abend precedes the first chunk write, so no generation object is created")
                    .isEmpty();
        } finally {
            disclosureGroupRepository.save(new DisclosureGroup(seededKey, seededRate));
        }
    }

    /**
     * A zero rate skips the row: nothing emitted, nothing accumulated.
     *
     * <p>Purpose: exercise the one branch of {@code app/cbl/CBACT04C.cbl:214-217} that the shipped fixtures
     * cannot reach. Every one of the fifty seeded category balances carries the pair {@code 010001}, whose
     * {@code DEFAULT} rate is non-zero, so the non-zero arm runs on every row and the skip
     * arm runs on none. A synthetic row for a pair whose {@code DEFAULT} rate really is {@code +0.00} is
     * therefore required, and {@code 020001} is such a pair - and is a genuine row of
     * {@code app/data/ASCII/trancatg.txt}, so the referential constraint is satisfied without inventing a
     * category.
     *
     * <p>Inputs: the seeded population plus one synthetic category-balance row for the first account. Output:
     * none. Side effects: the synthetic row is inserted and removed in a {@code finally} block; the run
     * commits. Error modes: a run that emits fifty-one records has stopped skipping; a first account whose
     * balance moved by more than one month's interest on its non-zero row has accumulated the zero-rate row
     * as well, which {@code :214} forbids.
     *
     * <p>The first account is chosen deliberately: it then holds two rows, {@code 01/0001} followed by
     * {@code 02/0001}, which are adjacent in the key-ordered browse. Both belong to one control-break group,
     * so the accumulated total for that account isolates the zero-rate row's contribution exactly.
     *
     * <p><strong>A zero rate is a legitimate value, not a missing one.</strong> Seven of the seventeen
     * {@code DEFAULT} rows carry it, and no constraint forbids it, so nothing here treats it as an error.
     */
    @Test
    @DisplayName("5. a zero DEFAULT rate skips the row entirely: no record emitted and nothing accumulated, "
            + "per CBACT04C.cbl:214-217")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aZeroRateRowIsSkippedWithNoRecordEmittedAndNothingAccumulated() {
        assertThat(defaultFamilyRate(zeroRateTypeCode, zeroRateCategory))
                .as("the DEFAULT rate for %s/%s is +0.00 in app/data/ASCII/discgrp.txt, and a zero rate is a "
                        + "real value rather than an absent one", zeroRateTypeCode, zeroRateCategory)
                .isEqualByComparingTo(BigDecimal.ZERO);

        final TransactionCategoryBalanceId syntheticKey = new TransactionCategoryBalanceId(
                Long.valueOf(firstSeededAccountId), zeroRateTypeCode, zeroRateCategory);
        try {
            seedCategoryBalances(probeCategoryBalance);
            categoryBalanceRepository.save(
                    new TransactionCategoryBalance(syntheticKey, probeCategoryBalance));

            final BigDecimal appliedRate = defaultFamilyRate(interestTypeCode, interestLookupCategory);
            final BigDecimal expectedInterest =
                    expectedMonthlyInterest(probeCategoryBalance, appliedRate);
            final Map<Long, BigDecimal> balancesBefore = committedBalancesByAccount();

            final JobExecution execution = launchInterestCalculation();
            assertRunCompletedWithoutAbend(execution);

            final StepExecution step = singleStep(execution);
            assertThat(step.getReadCount())
                    .as("the browse now yields the fifty seeded rows plus the synthetic one")
                    .isEqualTo(seededAccountCount + 1L);
            assertThat(step.getFilterCount())
                    .as("the zero-rate row is filtered rather than written; CBACT04C.cbl:214 emits no "
                            + "transaction at all for it")
                    .isEqualTo(1L);
            assertThat(step.getWriteCount())
                    .as("the fifty non-zero rows still emit one interest record each")
                    .isEqualTo(seededAccountCount);

            assertThat(accountRepository.findById(Long.valueOf(firstSeededAccountId)).orElseThrow()
                            .getCurrentBalance())
                    .as("account %d holds both rows, and the zero-rate one accumulated nothing, so its "
                            + "balance moved by exactly one month's interest on its non-zero row",
                            firstSeededAccountId)
                    .isEqualByComparingTo(
                            balancesBefore.get(Long.valueOf(firstSeededAccountId)).add(expectedInterest));
        } finally {
            categoryBalanceRepository.findById(syntheticKey).ifPresent(categoryBalanceRepository::delete);
        }
    }

    /**
     * A missing cross reference abends the run; it is never skipped.
     *
     * <p>Purpose: preserve an asymmetry that is easy to smooth over. At
     * {@code app/cbl/CBACT04C.cbl:393-413} the {@code INVALID KEY} clause only issues a {@code DISPLAY}, and
     * control then falls into the standard guard, which sets the failure result and reaches
     * {@code 9999-ABEND-PROGRAM}. The friendly message is therefore not a recovery - the job abends. The
     * contrast is with the daily posting program, where a missing cross reference on a keyed base read is a
     * business outcome carrying a reject code; both sides of that asymmetry are real and both must survive.
     *
     * <p>Inputs: the seeded population with the first account's cross-reference row removed. Output: none.
     * Side effects: the row is removed and restored in a {@code finally} block. Error modes: a completed run
     * with forty-nine records emitted would mean the miss was treated as a skip, which is the specific
     * implementation this test exists to reject.
     */
    @Test
    @DisplayName("6. a missing cross-reference row abends the run rather than skipping the account, per "
            + "CBACT04C.cbl:393-413")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aMissingCrossReferenceAbendsRatherThanSkippingTheAccount() {
        final Optional<CardCrossReference> seeded = cardCrossReferenceRepository
                .findFirstByAccountIdOrderByCardNumberAsc(Long.valueOf(firstSeededAccountId));
        assertThat(seeded)
                .as("app/data/ASCII/cardxref.txt seeds one cross reference per account, including the first")
                .isPresent();
        final CardCrossReference seededRow = seeded.orElseThrow();
        final String cardNumber = seededRow.getCardNumber();
        final Long customerId = seededRow.getCustomerId();
        final Long accountId = seededRow.getAccountId();

        try {
            cardCrossReferenceRepository.delete(seededRow);
            seedCategoryBalances(probeCategoryBalance);

            final JobExecution execution = launchInterestCalculation();

            assertThat(execution.getStatus())
                    .as("the guard following the INVALID KEY display reaches 9999-ABEND-PROGRAM")
                    .isEqualTo(BatchStatus.FAILED);
            final Optional<FatalProcessingException> abend = firstAbend(execution);
            assertThat(abend)
                    .as("the miss must surface as the typed abend; recorded failures were %s",
                            execution.getAllFailureExceptions())
                    .isPresent();
            final FatalProcessingException fatal = abend.orElseThrow();
            assertThat(fatal.getAbendCulprit()).isEqualTo("CBACT04C");
            assertThat(fatal.getAbendReason())
                    .as("ABEND-REASON carries the DISPLAY literal of CBACT04C.cbl:408 verbatim")
                    .isEqualTo("ERROR READING XREF FILE");
            assertThat(fatal.getAbendMessage())
                    .as("the message carries the not-found literal of CBACT04C.cbl:397")
                    .startsWith("ACCOUNT NOT FOUND: ");

            assertThat(committedTransactionRowCount())
                    .as("nothing is posted by an abended run")
                    .isZero();
            assertThat(publishedGenerationKeys(execution))
                    .as("a skip would have carried on and emitted the remaining accounts' records; an abend "
                            + "emits none, and the first account is first in the browse")
                    .isEmpty();
        } finally {
            cardCrossReferenceRepository.save(
                    new CardCrossReference(cardNumber, customerId, accountId));
        }
    }

    /**
     * The generation receives every emitted record at exactly 350 bytes, and the transaction cluster receives
     * none.
     *
     * <p>Purpose: prove the output boundary. {@code app/cbl/CBACT04C.cbl:309} opens the transaction file for
     * output with sequential organisation, and {@code app/jcl/INTCALC.jcl:37-41} allocates a brand-new
     * generation of {@code SYSTRAN} with {@code RECFM=F} and {@code LRECL=350} on every run, so generated
     * interest reaches the keyed cluster only later through the combine job. Two things follow and both are
     * asserted: the record count is conserved at the object-store boundary, byte for byte; and the table is
     * still empty afterwards. If the job ever wrote to the table directly, every unit test would still pass
     * and the divergence would surface only as colliding keys in a downstream job.
     *
     * <p>Inputs: the seeded population with non-zero category balances. Output: none. Side effects: the run
     * commits and the parent's reset hook empties the buckets afterwards. Error modes: a payload whose length
     * is not a whole number of records means the fixed-width geometry was lost; a record total that
     * disagrees with the step's write count means records were dropped or duplicated at the boundary.
     *
     * <p>The key shape is asserted too, because a relative generation reference becomes a deterministic key
     * rather than a catalogue entry: each key sits under the configured prefix followed by this run's job
     * instance identifier, and the keys ascend lexicographically. Both numeric segments are zero padded to
     * nineteen digits, the widest a signed 64-bit value needs, which is what makes lexicographic order and
     * creation order the same thing - and therefore what lets a later {@code (0)} read resolve the newest
     * generation.
     */
    @Test
    @DisplayName("7. the SYSTRAN generation holds every emitted record at 350 bytes under an ascending "
            + "per-instance prefix, and the transaction table stays empty")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theGenerationHoldsEveryEmittedRecordAtThreeHundredAndFiftyBytes() {
        seedCategoryBalances(probeCategoryBalance);

        final JobExecution execution = launchInterestCalculation();
        assertRunCompletedWithoutAbend(execution);

        final StepExecution step = singleStep(execution);
        assertThat(step.getWriteCount())
                .as("one interest record per non-zero category-balance row")
                .isEqualTo(seededAccountCount);

        final List<String> keys = publishedGenerationKeys(execution);
        assertThat(keys)
                .as("the run publishes the concrete keys it created, so a consumer never has to re-resolve "
                        + "a relative generation and race another run")
                .isNotEmpty();
        final String expectedPrefix = systranPrefix + "/" + zeroPadded(
                execution.getJobInstance().getInstanceId());
        assertThat(keys)
                .as("every key belongs to this run's generation under the configured GDG prefix")
                .allSatisfy(key -> assertThat(key).startsWith(expectedPrefix));
        assertThat(keys)
                .as("nineteen-digit zero padding makes lexicographic order the same as creation order")
                .isSorted();

        long recordsInGeneration = 0L;
        for (final String key : keys) {
            final byte[] payload = generationPayload(key);
            assertThat(payload)
                    .as("a generation object with no bytes would mean the write path ran and emitted nothing")
                    .isNotEmpty();
            assertThat(payload.length % recordLength)
                    .as("RECFM=F with LRECL=350 makes every object a whole number of records; a consumer "
                            + "finds boundaries by counting bytes and by nothing else")
                    .isZero();
            recordsInGeneration += payload.length / recordLength;
        }
        assertThat(recordsInGeneration)
                .as("every record the step wrote reached the generation, and none was added or lost there")
                .isEqualTo(step.getWriteCount());

        assertThat(committedTransactionRowCount())
                .as("OPEN OUTPUT at CBACT04C.cbl:309 makes the fresh sequential generation the only sink; "
                        + "interest reaches the keyed cluster later, through the combine job")
                .isZero();
    }

    /**
     * Every emitted record carries the synthetic interest shape of {@code 1300-B-WRITE-TX}, in ascending
     * account order.
     *
     * <p>Purpose: check the record the source builds at {@code app/cbl/CBACT04C.cbl:473-516} field by field,
     * at the offsets {@code app/cpy/CVTRA05Y.cpy} declares, and use the account identifier those records
     * carry to prove the job <em>consumed</em> its input in key order. The second half matters because the
     * account-level control break tests the account identifier alone, which is correct only because the
     * identifier leads the seventeen-byte composite key of a sequentially browsed file. An unordered read
     * would split and merge accounts silently, producing wrong interest with no error anywhere - so the
     * ordering is not a preference but a precondition, and this is the only place it can be observed from
     * the job's own output.
     *
     * <p>Inputs: the seeded population with non-zero category balances. Output: none. Side effects: the run
     * commits. Error modes: a transaction identifier out of sequence means the suffix counter was reset per
     * account rather than kept run-sequential; an account identifier that does not exceed its predecessor
     * means the browse lost its ordering.
     *
     * <p>The amount field is checked for shape only - ten zoned digits and a trailing overpunch position -
     * and not decoded. Its encoding is owned by the transaction writer's own unit test, and the posted value
     * is already proved end to end through the account balances. Re-implementing the overpunch table here
     * would test this test rather than the code.
     */
    @Test
    @DisplayName("8. every emitted record carries the 1300-B-WRITE-TX shape, and the account identifiers "
            + "ascend, which is the job-level proof that the browse was key-ordered")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theEmittedRecordsCarryTheSyntheticShapeInAscendingAccountOrder() {
        seedCategoryBalances(probeCategoryBalance);

        final JobExecution execution = launchInterestCalculation();
        assertRunCompletedWithoutAbend(execution);

        final List<String> records = generationRecords(execution);
        assertThat(records)
                .as("one record per non-zero category-balance row")
                .hasSize((int) seededAccountCount);

        final String expectedTimestamp = expectedGeneratedTimestamp();
        long previousAccountId = 0L;
        int ordinal = 0;
        for (final String record : records) {
            ordinal++;
            assertThat(record)
                    .as("CVTRA05Y declares exactly %d bytes", Integer.valueOf(recordLength))
                    .hasSize(recordLength);

            assertThat(field(record, 1, 16))
                    .as("TRAN-ID is PARM-DATE concatenated with a six-digit suffix that CBACT04C.cbl:474 "
                            + "increments globally, never per account, so identifiers are run-sequential")
                    .isEqualTo(parmDate + String.format(Locale.ROOT, "%06d", Integer.valueOf(ordinal)));
            assertThat(field(record, 17, 2))
                    .as("MOVE '01' TO TRAN-TYPE-CD at CBACT04C.cbl:482")
                    .isEqualTo(interestTypeCode);
            assertThat(field(record, 19, 4))
                    .as("MOVE '05' TO TRAN-CAT-CD at CBACT04C.cbl:483, rendered as an unsigned four-digit "
                            + "zoned field")
                    .isEqualTo("0005");
            assertThat(field(record, 23, 10))
                    .as("MOVE 'System' TO TRAN-SOURCE at CBACT04C.cbl:484, right-padded to its picture width")
                    .isEqualTo("System    ");

            final String description = field(record, 33, 100);
            assertThat(description)
                    .as("STRING 'Int. for a/c ', ACCT-ID INTO TRAN-DESC at CBACT04C.cbl:485-488")
                    .startsWith("Int. for a/c ");
            final String renderedAccountId = field(record, 46, 11);
            assertThat(renderedAccountId)
                    .as("ACCT-ID is an eleven-digit zoned field, so the description carries it zero padded")
                    .containsOnlyDigits();
            assertThat(description.substring(24))
                    .as("the remainder of the hundred-byte description is blank, not truncated")
                    .isBlank();

            final String amount = field(record, 133, 11);
            assertThat(amount.substring(0, 10))
                    .as("TRAN-AMT is PIC S9(09)V99: ten zoned digits then a sign overpunch position, whose "
                            + "encoding is asserted by the writer's own unit test rather than restated here")
                    .containsOnlyDigits();
            assertThat(field(record, 144, 9))
                    .as("MOVE 0 TO TRAN-MERCHANT-ID at CBACT04C.cbl:491")
                    .isEqualTo("000000000");
            assertThat(field(record, 153, 50))
                    .as("MOVE SPACES TO TRAN-MERCHANT-NAME at CBACT04C.cbl:492")
                    .isBlank();
            assertThat(field(record, 203, 50))
                    .as("MOVE SPACES TO TRAN-MERCHANT-CITY at CBACT04C.cbl:493")
                    .isBlank();
            assertThat(field(record, 253, 10))
                    .as("MOVE SPACES TO TRAN-MERCHANT-ZIP at CBACT04C.cbl:494")
                    .isBlank();

            final long accountId = Long.parseLong(renderedAccountId);
            final Optional<CardCrossReference> crossReference = cardCrossReferenceRepository
                    .findFirstByAccountIdOrderByCardNumberAsc(Long.valueOf(accountId));
            assertThat(crossReference)
                    .as("the record's account must have the cross reference the run read")
                    .isPresent();
            assertThat(field(record, 263, 16))
                    .as("MOVE XREF-CARD-NUM TO TRAN-CARD-NUM at CBACT04C.cbl:495")
                    .isEqualTo(crossReference.orElseThrow().getCardNumber());

            assertThat(field(record, 279, 26))
                    .as("the timestamp is generated once and moved into TRAN-ORIG-TS at CBACT04C.cbl:497, "
                            + "at millisecond precision followed by four literal zeros")
                    .isEqualTo(expectedTimestamp);
            assertThat(field(record, 305, 26))
                    .as("the same value is moved into TRAN-PROC-TS at CBACT04C.cbl:498, so the two agree")
                    .isEqualTo(expectedTimestamp);
            assertThat(field(record, 331, 20))
                    .as("the twenty-byte trailing FILLER is part of the record; without it the image is 330 "
                            + "bytes and every downstream offset shifts")
                    .isBlank();

            assertThat(accountId)
                    .as("the browse is ordered by account, then type, then category, which is the only "
                            + "reason a break on the account identifier alone groups an account's rows")
                    .isGreaterThan(previousAccountId);
            previousAccountId = accountId;
        }

        assertThat(previousAccountId)
                .as("the last record belongs to the last account of the browse, which is precisely the "
                        + "account whose own flush never happens")
                .isEqualTo(lastSeededAccountId);
    }

    // =================================================================================================
    // Helpers. Each exists because two or more tests need it; none hides an assertion a test should own,
    // and none holds state.
    // =================================================================================================

    /**
     * Launches the job under test with the parameter {@code app/jcl/INTCALC.jcl:22} supplies.
     *
     * <p>Inputs: none; the parameter value is the fixed ten-character one. Output: the completed or failed
     * execution. Side effects: the run commits, outside this tier's rollback scope. Error modes: the parent's
     * launch helper refuses if a transaction is active or if the identifying run parameter is missing, and it
     * stamps that parameter itself so each test method is its own job instance.
     *
     * @return the resulting execution, never {@code null}
     */
    private JobExecution launchInterestCalculation() {
        return launchJob(interestCalculationJob, runIdParameters(Map.of(parmDateParameter, parmDate)));
    }

    /**
     * Asserts a run finished normally and recorded no abend.
     *
     * <p>The abend check is not redundant beside the status check. {@code CBACT04C} sets no return code of its
     * own - the only numeric return-code assignment in the corpus belongs to the posting program - so an
     * abend is this job's single failure surface, and asserting its absence is what makes "completed" mean
     * "completed cleanly".
     *
     * @param execution the execution to check, supplied by the calling test
     */
    private void assertRunCompletedWithoutAbend(final JobExecution execution) {
        assertThat(firstAbend(execution))
                .as("the run recorded an abend; failures were %s", execution.getAllFailureExceptions())
                .isEmpty();
        assertThat(execution.getStatus())
                .as("failures were %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("a clean run carries the completed exit code, not the completed-with-rejects one, which "
                        + "this job can never produce because it has no reject path")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Returns the run's single step execution.
     *
     * <p>The job is one step wrapped in a flow, so exactly one step execution exists on a run that reached
     * the step at all. Asserting that here rather than in each caller keeps the read counts a caller asserts
     * from being taken off an arbitrary member of a set.
     *
     * @param execution the execution to read
     * @return its one step execution, never {@code null}
     */
    private StepExecution singleStep(final JobExecution execution) {
        assertThat(execution.getStepExecutions())
                .as("the interest job is a single step wrapped in a flow")
                .hasSize(1);
        return execution.getStepExecutions().iterator().next();
    }

    /**
     * Finds the typed abend on a run, following each recorded failure's whole cause chain.
     *
     * <p>The chain is walked rather than the top-level exception inspected because the framework may wrap what
     * a processor threw, and a test that only looked at the outermost type would report a genuine abend as an
     * untyped failure. Returning an {@code Optional} rather than throwing lets a caller assert presence or
     * absence with the same helper, which is how both outcomes stay exercised.
     *
     * @param execution the execution to search
     * @return the first abend found in execution order, or empty if the run recorded none
     */
    private Optional<FatalProcessingException> firstAbend(final JobExecution execution) {
        for (final Throwable failure : execution.getAllFailureExceptions()) {
            for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
                if (candidate instanceof FatalProcessingException fatal) {
                    return Optional.of(fatal);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Gives every seeded account a non-zero billing cycle, so that a missing reset is observable.
     *
     * <p>The two values differ from each other and neither appears in {@code app/data/ASCII/acctdata.txt},
     * where both accumulators are {@code +0.00} on all fifty records. Without this the flushed and un-flushed
     * accounts are indistinguishable and the boundary this class exists to pin cannot be seen.
     *
     * <p>The write goes through the repository rather than through a statement of its own, because the
     * connection pool runs with auto-commit switched off: a bare statement outside a transaction would report
     * fifty rows affected and then be rolled back when its connection returned to the pool, and the test
     * would fail much later with an assertion that looked like a defect in the job. The repository's own
     * transaction commits. The parent's reset hook restores both columns from the frozen fixture afterwards.
     */
    private void seedStaleCycleAccumulators() {
        final List<Account> accounts = accountRepository.findAll();
        for (final Account account : accounts) {
            account.setCurrentCycleCredit(staleCycleCredit);
            account.setCurrentCycleDebit(staleCycleDebit);
        }
        assertThat(accountRepository.saveAll(accounts))
                .as("all fifty seeded accounts must start from a non-zero cycle")
                .hasSize((int) seededAccountCount);
    }

    /**
     * Gives every seeded category balance the same non-zero balance.
     *
     * <p>{@code app/data/ASCII/tcatbal.txt} carries {@code +0.00} in all fifty balances, so over the untouched
     * seed the computed interest is zero for every row and no balance assertion could distinguish a correct
     * calculation from none at all. Called before any synthetic row is inserted, so the affected count is
     * always the seeded fifty.
     *
     * <p>As with the cycle accumulators, the write goes through the repository so that it commits; see that
     * method for why a bare statement would not.
     *
     * @param balance the balance to set on every seeded row
     */
    private void seedCategoryBalances(final BigDecimal balance) {
        final List<TransactionCategoryBalance> balances = categoryBalanceRepository.findAll();
        for (final TransactionCategoryBalance categoryBalance : balances) {
            categoryBalance.setBalance(balance);
        }
        assertThat(categoryBalanceRepository.saveAll(balances))
                .as("all fifty seeded category balances must be non-zero for the interest to be observable")
                .hasSize((int) seededAccountCount);
    }

    /**
     * Reads every account's committed balance, keyed by account identifier.
     *
     * <p>The map is sorted rather than hashed so that nothing in this class can come to depend on iteration
     * order.
     *
     * @return the committed balances, one entry per seeded account
     */
    private Map<Long, BigDecimal> committedBalancesByAccount() {
        final Map<Long, BigDecimal> balances = new TreeMap<>();
        for (final Account account : accountRepository.findAll()) {
            balances.put(account.getAccountId(), account.getCurrentBalance());
        }
        return balances;
    }

    /**
     * Counts committed rows in the transaction table.
     *
     * <p>The table is quoted because {@code transaction} is a reserved word, and the count is read directly
     * rather than through a repository because a job's writes are committed outside this tier's rollback
     * scope. The statement carries no parameter at all.
     *
     * @return the row count, never negative
     */
    private long committedTransactionRowCount() {
        return Objects.requireNonNull(
                        jdbcTemplate.queryForObject("SELECT count(*) FROM \"transaction\"", Long.class),
                        "a count query cannot return null")
                .longValue();
    }

    /**
     * Resolves a rate through the production stage-two finder, the same call the processor makes.
     *
     * @param typeCode     the transaction type code of the pair
     * @param categoryCode the transaction category code of the pair
     * @return the rate on the {@code DEFAULT} row for that pair
     */
    private BigDecimal defaultFamilyRate(final String typeCode, final Integer categoryCode) {
        final Optional<DisclosureGroup> fallback =
                disclosureGroupRepository.findDefaultGroupRate(typeCode, categoryCode);
        assertThat(fallback)
                .as("app/data/ASCII/discgrp.txt seeds seventeen DEFAULT rows, and %s/%s must be one of them",
                        typeCode, categoryCode)
                .isPresent();
        return fallback.orElseThrow().getInterestRate();
    }

    /**
     * Applies the formula of {@code app/cbl/CBACT04C.cbl:464-465} exactly as the source writes it.
     *
     * <p>Multiplication first, then a single division by the literal {@code 1200}, rounded half to even at two
     * decimals. It is deliberately not rewritten as a division by one hundred followed by one by twelve, and
     * no decimal multiplier is substituted: either change alters the rounding, and this method exists so that
     * the expected value in a test is derived the same way the code under test derives the actual one.
     *
     * @param categoryBalance the row's balance
     * @param interestRate    the resolved annual rate
     * @return one month's interest at two decimals
     */
    private BigDecimal expectedMonthlyInterest(final BigDecimal categoryBalance,
            final BigDecimal interestRate) {

        return categoryBalance.multiply(interestRate)
                .divide(monthlyInterestDivisor, 2, RoundingMode.HALF_EVEN);
    }

    /**
     * Builds the 26-character timestamp {@code Z-GET-DB2-FORMAT-TIMESTAMP} produces, from the injected clock.
     *
     * <p>The source assembles the value from the current date and time and then moves four literal zeros into
     * its final positions, so the rendering is millisecond precision followed by {@code 0000} - never
     * nanosecond precision, which would be six digits of real fraction and a different string. The instant
     * comes from the parent's fixed clock, so nothing here reads a moving clock, and the formatter is given
     * {@code java.util.Locale#ROOT} explicitly rather than inheriting a platform default.
     *
     * @return the expected timestamp, exactly 26 characters
     */
    private String expectedGeneratedTimestamp() {
        return LocalDateTime.ofInstant(fixedInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT)) + "0000";
    }

    /**
     * Reads back the object keys a run published, in creation order.
     *
     * <p>The run records a count and then one indexed entry per key, which is the protocol that replaces the
     * catalogue entry a closing {@code DISP=(NEW,CATLG,DELETE)} dataset would have created. Reading the count
     * and then that many indexed entries is how a consumer is meant to resolve the generation - as opposed to
     * re-resolving "the latest", which could pick up a generation another run produced.
     *
     * @param execution the execution whose generation to read
     * @return the keys in creation order, empty if the run created none
     */
    private List<String> publishedGenerationKeys(final JobExecution execution) {
        final ExecutionContext context = execution.getExecutionContext();
        final int published = (int) context.getLong(
                InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, 0L);
        final List<String> keys = new ArrayList<>(published);
        for (int index = 0; index < published; index++) {
            keys.add(context.getString(InterestCalculationJob.SYSTRAN_GENERATION_KEYS_INDEX_PREFIX
                    + Integer.toString(index)));
        }
        return keys;
    }

    /**
     * Fetches one generation object's bytes exactly as they were stored.
     *
     * @param objectKey the key to fetch
     * @return the payload, never {@code null}
     */
    private byte[] generationPayload(final String objectKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(objectKey)
                        .build())
                .asByteArray();
    }

    /**
     * Splits every generation object of a run into fixed-width records, in creation order.
     *
     * <p>The stream is unblocked and undelimited, so boundaries are found by counting bytes and by nothing
     * else. Decoding uses a single-byte charset so that one byte is one character and an offset in the decoded
     * string is the same offset as in the payload. Any trailing bytes that do not complete a record are not
     * silently absorbed: they simply do not form a record, so the record total falls short of the step's write
     * count and the geometry test fails on that comparison.
     *
     * @param execution the execution whose generation to read
     * @return the records in order, each exactly the declared record length
     */
    private List<String> generationRecords(final JobExecution execution) {
        final List<String> records = new ArrayList<>();
        for (final String objectKey : publishedGenerationKeys(execution)) {
            final String image = new String(generationPayload(objectKey), StandardCharsets.ISO_8859_1);
            final int wholeRecords = image.length() / recordLength;
            for (int index = 0; index < wholeRecords; index++) {
                records.add(image.substring(index * recordLength, (index + 1) * recordLength));
            }
        }
        return records;
    }

    /**
     * Extracts one fixed-width field from a record by its one-based copybook offset.
     *
     * <p>Offsets are quoted one-based throughout, exactly as {@code app/cpy/CVTRA05Y.cpy} numbers them, so a
     * caller can read an assertion against the copybook without translating.
     *
     * @param record        the record image
     * @param oneBasedStart the field's first column, counting from one
     * @param width         the field's width in bytes
     * @return the field's contents, trailing spaces intact
     */
    private String field(final String record, final int oneBasedStart, final int width) {
        final int zeroBasedStart = oneBasedStart - 1;
        return record.substring(zeroBasedStart, zeroBasedStart + width);
    }

    /**
     * Renders a numeric key segment at the padding width that makes lexicographic order numeric order.
     *
     * @param value the value to render
     * @return the zero-padded rendering
     */
    private String zeroPadded(final long value) {
        return String.format(Locale.ROOT, "%0" + Integer.toString(keyNumberWidth) + "d",
                Long.valueOf(value));
    }
}
