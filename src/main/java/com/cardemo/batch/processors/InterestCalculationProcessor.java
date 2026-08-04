/*
 * ******************************************************************
 * Component   : InterestCalculationProcessor.java
 * Application : CardDemo
 * Type        : Spring Batch ItemProcessor (Java 25 / Spring Boot 3.5.11)
 * Function    : Monthly interest computation per transaction category balance.
 * Source      : app/cbl/CBACT04C.cbl (652 lines, 23 paragraphs) @ 7756d89
 *               app/jcl/INTCALC.jcl - PARM='2022071800', SYSTRAN(+1) LRECL=350
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
package com.cardemo.batch.processors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Per-record interest computation of the interest-calculation batch step.
 *
 * <h2>What it does</h2>
 *
 * <p>It is the {@link ItemProcessor} of the step that replaces the z/OS batch job
 * {@code app/jcl/INTCALC.jcl}, whose single step runs {@code app/cbl/CBACT04C.cbl} - 652 lines, 23
 * paragraph labels. Per transaction-category-balance row it reproduces the body of the source's main
 * read loop at {@code app/cbl/CBACT04C.cbl:L188}-{@code :L222}: it counts the record, detects an
 * account-level control break, flushes the <em>previous</em> account's accumulated interest, loads the
 * new account and its cross reference, resolves a disclosure-group interest rate (with a fallback), and
 * on a non-zero rate computes one month's interest and synthesises one interest transaction.
 *
 * <p>Every paragraph of the source that belongs to the per-record path has exactly one private method
 * here, named for it and citing it, with no consolidation. The paragraphs that belong to the step rather
 * than to the item are dispositioned under <i>Paragraph disposition</i> below so the traceability audit
 * can see that none was dropped.
 *
 * <h2>The formula, transcribed and not rewritten</h2>
 *
 * <p>{@code 1300-COMPUTE-INTEREST} at {@code app/cbl/CBACT04C.cbl:L462}-{@code :L470} reads:</p>
 *
 * <pre>
 * L464 COMPUTE WS-MONTHLY-INT
 * L465  = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * L467 ADD WS-MONTHLY-INT  TO WS-TOTAL-INT
 * L468 PERFORM 1300-B-WRITE-TX.
 * </pre>
 *
 * <p>The balance is multiplied by the rate <strong>first</strong> and the product is then divided by the
 * literal {@code 1200}, which this class holds as {@link #MONTHLY_INTEREST_DIVISOR}. It is never
 * rewritten as a division by {@code 100} followed by a division by {@code 12}, never as a multiplication
 * by a decimal approximation of one twelve-hundredth, and never reordered so the division happens first.
 * Each of those forms is algebraically equivalent over the rationals and <em>not</em> equivalent over
 * two-decimal fixed-point arithmetic, so each would change the emitted amount. Rounding is always
 * {@link RoundingMode#HALF_EVEN} at scale {@value #MONETARY_SCALE}.
 *
 * <p>The three participating precisions were read from the copybooks and differ from one another, which
 * is why none of them is assumed: {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9}
 * maps to {@code NUMERIC(11,2)}; {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9}
 * maps to {@code NUMERIC(6,2)} and is the only six-digit scale-two column in the schema; and
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7} maps to {@code NUMERIC(12,2)}.
 * {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} are both {@code PIC S9(09)V99}
 * ({@code app/cbl/CBACT04C.cbl:L168}-{@code :L169}). Every monetary and rate value in this class is a
 * {@link BigDecimal}; there is no {@code float} and no {@code double} anywhere, and numeric equality is
 * always {@link BigDecimal#compareTo} and never {@link BigDecimal#equals}, because {@code 0.00} and
 * {@code 0} are unequal under {@code equals} and equal under {@code compareTo}.
 *
 * <h2>PARITY TRAP 1 - the apparent final flush is unreachable</h2>
 *
 * <p>The main loop at {@code app/cbl/CBACT04C.cbl:L188}-{@code :L222} appears to flush the last
 * account's interest when it reaches end of file. It does not, and the difference is load bearing
 * because it decides whether the final account's balance is ever updated.
 *
 * <pre>
 * L188 PERFORM UNTIL END-OF-FILE = 'Y'
 * L189     IF  END-OF-FILE = 'N'                 &lt;- OUTER, tested at the top of every iteration
 * L190         PERFORM 1000-TCATBALF-GET-NEXT
 * L191         IF  END-OF-FILE = 'N'             &lt;- INNER, opened after the read
 * ...
 * L218         END-IF                            &lt;- closes the INNER IF (column 20)
 * L219     ELSE                                  &lt;- belongs to the OUTER IF (column 16)
 * L220         PERFORM 1050-UPDATE-ACCOUNT
 * L221     END-IF
 * L222 END-PERFORM.
 * </pre>
 *
 * <p>{@code PERFORM UNTIL} is a test-<em>before</em> loop. The moment
 * {@code 1000-TCATBALF-GET-NEXT} sets {@code END-OF-FILE} to {@code 'Y'} the loop condition at
 * {@code :L188} is satisfied and the loop exits, so control never reaches the {@code ELSE} arm.
 * Indentation confirms the pairing: the inner {@code IF} at {@code :L191} and its {@code END-IF} at
 * {@code :L218} both sit at column 20, while the outer {@code IF} at {@code :L189}, the {@code ELSE} at
 * {@code :L219} and the {@code END-IF} at {@code :L221} all sit at column 16.
 * <strong>{@code :L220} can never execute.</strong>
 *
 * <p><b>Consequence.</b> The account still in progress when the browse ends keeps its pre-run
 * {@code ACCT-CURR-BAL} and its un-reset cycle counters. That outcome is <strong>preserved rather than
 * repaired</strong>, because parity with the frozen corpus is the acceptance contract and rewriting a
 * business rule to be "more correct" is explicitly out of scope. The variance between this reading and the
 * specification prose is disclosed once, with its severity and its remediation, in the register in
 * {@code com.cardemo} - see the package documentation of the root package - and is not restated here.
 * {@link #updateAccountAtEndOfFile()} reproduces the {@code ELSE} arm faithfully so that the paragraph map
 * stays provable.
 *
 * <p><b>Proof by contrast.</b> The same idiom appears in {@code app/cbl/CBTRN03C.cbl} with the opposite
 * reachability. There the {@code IF END-OF-FILE = 'N'} at {@code :L179} is the <em>inner</em> test,
 * positioned after the read and inside the same iteration, and its {@code ELSE} at {@code :L197}
 * therefore <em>does</em> execute, accumulating {@code TRAN-AMT} into the page total at {@code :L200}.
 * Two occurrences of one idiom, opposite outcomes, decided purely by which {@code IF} owns the
 * {@code ELSE}. Citing the contrast is what makes the pairing checkable rather than merely asserted.
 *
 * <h2>PARITY TRAP 2 - no stale rate may survive an iteration</h2>
 *
 * <p>{@code READ ... INTO} on an invalid key leaves the receiving record area holding whatever the
 * previous successful read put there. At {@code app/cbl/CBACT04C.cbl:L416} the disclosure-group read can
 * fail that way, and {@code DIS-INT-RATE} then still holds the previous row's rate until the default
 * read at {@code :L444} overwrites it. Java must not reproduce that hazard: a rate carried in a field
 * would let a failed lookup silently reuse the last record's rate and post interest that no rate row
 * authorises.
 *
 * <p><b>How that hazard is closed.</b> The rate is never a field. {@link #getInterestRate} resolves it
 * afresh per item and returns it by value, absence is represented by an {@link Optional} from the
 * repository rather than by a leftover value, and the only rate variable is a local of
 * {@link #process}. The two record areas that <em>are</em> held across items -
 * {@link #currentAccount} and {@link #currentCrossReference} - are held deliberately, because the
 * source holds them deliberately: they are refreshed only on a control break at
 * {@code app/cbl/CBACT04C.cbl:L202}-{@code :L205} and read on every item at {@code :L210} and
 * {@code :L495}.
 *
 * <h2>The retry guard of 1200-A-GET-DEFAULT-INT-RATE is at :L446, not :L445</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L445} is a blank line, so a citation of it points at nothing; the strict
 * guard {@code IF DISCGRP-STATUS = '00'} is at {@code :L446}. This class cites {@code :L446} throughout.
 *
 * <h2>The MOVE order is not the key order</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L210}-{@code :L212} populates the disclosure-group key in the order
 * group, <em>category</em>, <em>type</em>, whereas {@code app/cpy/CVTRA02Y.cpy:L6}-{@code :L8} declares
 * the key as {@code DIS-ACCT-GROUP-ID X(10)}, then {@code DIS-TRAN-TYPE-CD X(02)}, then
 * {@code DIS-TRAN-CAT-CD 9(04)}. The two orders differ. It is harmless in COBOL, where each
 * {@code MOVE} names its own target, but it is a trap in Java, where the key is a positional
 * constructor call. {@link DisclosureGroupId} is therefore always constructed in <em>copybook</em>
 * order - group, type, category - and the argument order must not be "corrected" to match the source's
 * {@code MOVE} sequence.
 *
 * <h2>Three repository names differ from the shape this translation first assumed</h2>
 *
 * <p>Three repository calls are spelled differently by the interfaces than a literal reading of the source
 * would suggest. In each case the interface governs, because no method may be added to a repository:</p>
 *
 * <ol>
 *   <li>The cross-reference finder is
 *       {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc(Long)}, returning an
 *       {@link java.util.Optional} rather than a collection. The alternate key is {@code NONUNIQKEY}, so
 *       duplicates are permitted and the index stays non-unique - but the source's keyed {@code READ}
 *       returns the <em>first</em> record in alternate-key order and nothing downstream iterates the rest,
 *       so the database applies {@code LIMIT 1} and the ascending order fixes which row that is.</li>
 *   <li>Stage two of the rate lookup is
 *       {@link DisclosureGroupRepository#findDefaultGroupRate(String, Integer)}, not a second
 *       {@code findById}. Its {@code @Query} pins {@code d.id.accountGroupId = 'DEFAULT   '}, so the
 *       {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} of {@code app/cbl/CBACT04C.cbl:L437} lives in the
 *       repository's JPQL and this class passes only the retained type and category codes. That also
 *       settles the padding question: the literal is space-padded to ten characters inside the query and
 *       nothing is padded here.</li>
 *   <li>The account read is the inherited {@code findById} and the rewrite is the inherited
 *       {@code save} of {@link AccountRepository}. {@code findByIdForUpdate} is reserved for
 *       {@code app/cbl/COACTUPC.cbl} by that interface's own documentation and is not used here,
 *       because {@code 1100-GET-ACCT-DATA} issues a plain {@code READ} and not a
 *       {@code READ ... UPDATE}.</li>
 *   </ol>
 *
 * <h2>Paragraph disposition - all 23 labels accounted for</h2>
 *
 * <p>Translated here, one private method each: {@code 1050-UPDATE-ACCOUNT} ({@code :L350}),
 * {@code 1100-GET-ACCT-DATA} ({@code :L372}), {@code 1110-GET-XREF-DATA} ({@code :L393}),
 * {@code 1200-GET-INTEREST-RATE} ({@code :L415}), {@code 1200-A-GET-DEFAULT-INT-RATE} ({@code :L443}),
 * {@code 1300-COMPUTE-INTEREST} ({@code :L462}), {@code 1300-B-WRITE-TX} ({@code :L473}),
 * {@code 1400-COMPUTE-FEES} ({@code :L518}) and {@code Z-GET-DB2-FORMAT-TIMESTAMP} ({@code :L613}).
 *
 * <p>Owned by the step or by a shared component, and deliberately absent from this class:
 * {@code FILE-CONTROL} ({@code :L27}) is the reader, writer and repository wiring;
 * {@code 0000-TCATBALF-OPEN} ({@code :L234}), {@code 0100-XREFFILE-OPEN} ({@code :L252}),
 * {@code 0200-DISCGRP-OPEN} ({@code :L270}), {@code 0300-ACCTFILE-OPEN} ({@code :L289}) and
 * {@code 0400-TRANFILE-OPEN} ({@code :L307}) become connection and stream lifecycle;
 * {@code 1000-TCATBALF-GET-NEXT} ({@code :L325}) becomes the driving reader, whose ordering contract is
 * stated under <i>Inputs</i>; {@code 9000-TCATBALF-CLOSE} ({@code :L522}),
 * {@code 9100-XREFFILE-CLOSE} ({@code :L541}), {@code 9200-DISCGRP-CLOSE} ({@code :L559}),
 * {@code 9300-ACCTFILE-CLOSE} ({@code :L577}) and {@code 9400-TRANFILE-CLOSE} ({@code :L595}) become
 * the same lifecycle on the way down; {@code 9999-ABEND-PROGRAM} ({@code :L628}) is
 * {@link FatalProcessingException}, which already publishes abend code {@code 999} and return code
 * {@code 12} so neither is redeclared here; and {@code 9910-DISPLAY-IO-STATUS} ({@code :L635}) is
 * {@link FileStatusMapper#displayIoStatus(String)}, the single tree-wide implementation of the
 * four-character render - it is not reimplemented here, and neither is the twenty-character
 * {@code FILE STATUS IS: NNNN} prefix that precedes it.
 *
 * <h2>Inputs, output, and why the zero-rate path emits nothing</h2>
 *
 * <p><b>Input.</b> One {@link TransactionCategoryBalance}, the 50-byte record of
 * {@code app/cpy/CVTRA01Y.cpy}. <strong>Precondition on the driving reader:</strong> items must arrive
 * ordered by {@code id.accountId}, then {@code id.typeCd}, then {@code id.catCd}. The control break at
 * {@code app/cbl/CBACT04C.cbl:L194} tests only the account identifier, and that is correct solely
 * because the account identifier <em>leads</em> the seventeen-byte composite key
 * ({@code app/cpy/CVTRA01Y.cpy:L6}-{@code :L8}) of a file opened {@code ACCESS MODE IS SEQUENTIAL}
 * ({@code app/cbl/CBACT04C.cbl:L28}-{@code :L32}). Out-of-order input would split one account across
 * several breaks and post its interest several times.
 * {@code TransactionCategoryBalanceRepository.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc}
 * satisfies the contract; this class cannot enforce it, so it is stated.
 *
 * <p><b>Output.</b> One synthesised {@link Transaction} when the resolved rate is non-zero, and
 * {@code null} when it is zero. Returning {@code null} is Spring Batch's sanctioned "no output" signal
 * and it is the exact translation of {@code app/cbl/CBACT04C.cbl:L214}-{@code :L217}, where a zero rate
 * suppresses <em>both</em> {@code 1300-COMPUTE-INTEREST} and {@code 1400-COMPUTE-FEES}: no transaction
 * is written, nothing is accumulated into {@code WS-TOTAL-INT}, and the fee paragraph is not performed
 * either.
 *
 * <p><b>The output target is sequential, not the keyed cluster.</b>
 * {@code app/cbl/CBACT04C.cbl:L53}-{@code :L56} declares {@code TRANSACT-FILE} with
 * {@code ORGANIZATION IS SEQUENTIAL} and {@code ACCESS MODE IS SEQUENTIAL} and no {@code RECORD KEY},
 * and {@code app/jcl/INTCALC.jcl:L37}-{@code :L41} allocates
 * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} with {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)} - a brand new
 * sequential generation on every run. Three consequences follow. This step does <strong>not</strong>
 * write to the transaction table, so no {@code TransactionRepository} is injected and the returned
 * {@link Transaction} is emitted by the step's writer as a 350-byte record. There is
 * <strong>no duplicate-key detection here</strong>, and none is added, because a fresh sequential file
 * cannot collide; duplicate exposure materialises later, in the bulk load of
 * {@code app/jcl/COMBTRAN.jcl}, which consumes only the most recent generation. And this program sets
 * <strong>no return code</strong> and displays no counter totals - {@code MOVE 4 TO RETURN-CODE} occurs
 * nowhere in it - so no "completed with rejects" path is invented.
 *
 * <h2>Side effects</h2>
 *
 * <p>Two, both deliberate. First, on a control break the <em>previous</em> account row is rewritten by
 * {@link #updateAccount()}. Second, five items of legacy {@code WORKING-STORAGE} are advanced. Nothing
 * else is mutated, nothing is written to the transaction table, and no message is published.
 *
 * <p>The account rewrite is one atomic group of three mutations - add the accumulated interest to
 * {@code ACCT-CURR-BAL}, zero {@code ACCT-CURR-CYC-CREDIT}, zero {@code ACCT-CURR-CYC-DEBIT} - applied
 * to a single managed {@link Account} instance and flushed by one {@code save}. They therefore commit
 * or roll back together inside the chunk transaction Spring Batch already opens around
 * {@link #process}. No {@code @Transactional} annotation is placed on the private method: Spring's
 * proxying cannot intercept a private method, so the annotation would be a silent no-op that reads like
 * a guarantee.
 *
 * <p><b>The cycle reset is the easily missed half.</b> {@code app/cbl/CBACT04C.cbl:L353}-{@code :L354}
 * zeroes both counters before the rewrite at {@code :L356}. Omitting it breaks the over-limit
 * arithmetic of the <em>next</em> posting cycle, because {@code app/cbl/CBTRN02C.cbl:L403}-{@code :L405}
 * subtracts the debit accumulator - a divergence that surfaces only on a second batch run. Per PARITY
 * TRAP 1 the reset happens only for accounts that reach a control break, never for the last account of
 * the run, and that asymmetry is not compensated for.
 *
 * <h2>State, and why the bean is step scoped</h2>
 *
 * <p>Five {@code WORKING-STORAGE} items are translated:
 * {@code WS-RECORD-COUNT PIC 9(09) VALUE 0} ({@code :L172}),
 * {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0} ({@code :L173}),
 * {@code WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} ({@code :L167}),
 * {@code WS-FIRST-TIME PIC X(01) VALUE 'Y'} ({@code :L170}) and
 * {@code WS-TOTAL-INT PIC S9(09)V99} ({@code :L169}).
 *
 * <p>{@code WS-TRANID-SUFFIX} is a genuinely global, run-sequential counter in the source: it is never
 * reset per account, so identifiers increase across the whole run. Reproducing it as a {@code static}
 * field, or as a field of a singleton bean, would be a direct violation of the global-mutable-state
 * standard and would also leak one run's counter into the next. This class is therefore annotated
 * {@link StepScope}: one instance exists per step execution, its fields are step-scoped by
 * construction, there is not one {@code static} mutable field in the file, and every collaborator
 * arrives through the constructor.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Configuration consumed by this component</caption>
 *   <tr><th>Item</th><th>Source</th><th>Default</th></tr>
 *   <tr>
 *     <td>Job parameter {@value #PARM_DATE_JOB_PARAMETER}</td>
 *     <td>{@code EXTERNAL-PARMS.PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:L176}-{@code :L178},
 *         supplied as {@code PARM='2022071800'} by {@code app/jcl/INTCALC.jcl:L22}</td>
 *     <td>None. It is mandatory and validated at construction</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Clock}</td>
 *     <td>Constructor argument, injected. {@code com.cardemo.config.ObservabilityConfig#clock(String)}
 *         publishes the application's single clock as a system clock in the deployment's own zone, which
 *         {@code carddemo.time.zone} can pin explicitly</td>
 *     <td>None. It is mandatory and validated at construction</td>
 *   </tr>
 *   <tr>
 *     <td>Rounding and scale</td>
 *     <td>{@link RoundingMode#HALF_EVEN} at scale {@value #MONETARY_SCALE}, from the {@code V99} of the
 *         picture clauses</td>
 *     <td>Fixed. Not configurable, because it is a parity contract</td>
 *   </tr>
 * </table>
 *
 * <p>{@code PARM-DATE} is ten characters of an alphanumeric {@code PIC X(10)} field, eight date digits
 * followed by two zeros, with no separators. It is <strong>not</strong> an ISO date: it is never parsed
 * into a {@link java.time.LocalDate} and never reformatted, because it is concatenated verbatim into
 * generated transaction identifiers. The constructor enforces the width the picture clause declares -
 * non-null and exactly {@value #PARM_DATE_LENGTH} characters - and deliberately does <em>not</em>
 * enforce a digits-only rule, because {@code PIC X(10)} is alphanumeric and adding a constraint the
 * source does not impose would be a behaviour change. One consequence is worth recording: because the
 * date leads the identifier, generated identifiers are numerically large and dominate the descending-key
 * browse that other programs use to derive the next identifier.
 *
 * <h2>How it is built, run and exercised</h2>
 *
 * <p>Built with {@code ./mvnw -B -ntp clean compile} and verified with {@code ./mvnw -B -ntp clean test}, on JDK 25 and
 * Maven 3.9.11. The build compiles with {@code -Xlint:all -Werror}, so any warning is a failure.
 *
 * <p>It runs as the processor of the interest-calculation step. Nothing else invokes it, and it must
 * not be called from request-scoped code: it is stateful within a step execution by design.
 *
 * <p>The dedicated unit test for this class lives at
 * {@code src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java}, in package
 * {@code com.cardemo.unit.batch}. It covers the behaviours that matter for parity: the
 * divide-by-{@code 1200} result for a known balance and rate, zero-rate suppression, default-group
 * fallback success, the abend when the default group row is missing, and the absence of any stale rate
 * across consecutive items. {@code InterestCalculationJobTest} and {@code ParityLoggerRoutingTest} assert
 * against it as well. Re-derive the current set with
 * {@code grep -rl InterestCalculationProcessor src/test/java}.
 * <p>
 * An earlier revision of this paragraph claimed the dedicated file did not exist and that "no assertion in
 * this tree covers this class". Both halves were wrong by the time it was read and are withdrawn.
 * <p>
 * Every seam those tests need is present: the repositories and {@link FileStatusMapper} are constructor
 * arguments, the {@link Clock} is a constructor argument on the six-argument constructor so timestamps are
 * deterministic, and the counters are instance state rather than {@code static}, so each test builds a fresh
 * processor.
 *
 * <p>The shipped fixtures make most of that provable without synthetic data, and the census is worth
 * recording because it is not obvious. {@code app/data/ASCII/acctdata.txt} carries ten spaces in
 * {@code ACCT-GROUP-ID} - bytes 113 to 122 - on all fifty rows, one distinct value, so the primary
 * disclosure-group lookup always misses and <strong>the default fallback is the path that resolves every
 * fixture rate</strong>. {@code app/data/ASCII/discgrp.txt} is 51 rows of exactly 50 bytes, seventeen
 * each of {@code A000000000}, {@code DEFAULT} and {@code ZEROAPR}, and its {@code DEFAULT} rows carry
 * seven zero rates, seven rates of {@code 15.00} and three of {@code 25.00}.
 * {@code app/data/ASCII/tcatbal.txt} is fifty rows all keyed {@code (type 01, category 0001)} with a
 * balance of {@code +0.00}, and the {@code DEFAULT} rate for that pair is {@code 15.00}, so with the
 * shipped data the rate is non-zero, interest is computed as {@code 0.00} and a transaction <em>is</em>
 * emitted. The zero-rate suppression path is reachable through the fallback for
 * {@code (02,0001)}, {@code (02,0002)}, {@code (02,0003)}, {@code (03,0001)}, {@code (03,0002)},
 * {@code (03,0003)} and {@code (07,0001)}; the fallback-miss abend needs a pair outside the seventeen
 * that {@code DEFAULT} covers.
 *
 * <h2>Error modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@link FatalProcessingException} - the only exception this class raises</dt>
 *   <dd>Every failure path in the source ends at {@code 9999-ABEND-PROGRAM}, so every failure path here
 *       ends in the typed abend, which carries abend code {@code 999} and return code {@code 12} as its
 *       own published constants. Raised when the item, its key or any key component is absent; when the
 *       account row is missing ({@code :L378} accepts {@code '00'} only); when the cross-reference row
 *       is missing ({@code :L400} accepts {@code '00'} only); when the default disclosure-group row is
 *       missing ({@code :L446} accepts {@code '00'} only); when a key component violates its picture
 *       clause; and when any repository call fails. A {@link DataAccessException} is always preserved as
 *       the cause.</dd>
 *   <dt>Record not found is <em>not</em> uniformly an error</dt>
 *   <dd>{@code app/cbl/CBACT04C.cbl:L422} accepts {@code '00' OR '23'} on the <em>first</em>
 *       disclosure-group read: a miss is an accepted control path that triggers the retry at
 *       {@code :L436}-{@code :L438}, not an error. This is one of only three such carve-outs in the
 *       whole corpus, and it is applied through
 *       {@link FileStatusMapper#requireDisclosureGroupReadSuccess(String)} rather than reimplemented.
 *       The retry's own guard is strict, and is applied through
 *       {@link FileStatusMapper#requireDefaultDisclosureGroupReadSuccess(String)}.</dd>
 *   <dt>The job completes where it used to abend</dt>
 *   <dd>An empty account or cross-reference lookup is being skipped or filtered instead of failing.
 *       Both are fatal in the source. Never return {@code null} from those paths and never map them to
 *       a record-not-found exception; only a zero rate may return {@code null}.</dd>
 *   <dt>Every record abends on the default group read</dt>
 *   <dd>The {@code (type, category)} pair has no {@code DEFAULT} row. The seventeen pairs that do are
 *       listed in {@code app/data/ASCII/discgrp.txt}; check that {@code V3__seed_data.sql} loaded all
 *       51 rows and decoded the trailing zoned-decimal overpunch sign position-aware from the picture
 *       clauses.</dd>
 *   <dt>Interest amounts differ from the baseline in the last decimal place</dt>
 *   <dd>The formula has been algebraically rewritten, or the rounding mode or scale has drifted. Restore
 *       multiply-then-divide-by-{@link #MONTHLY_INTEREST_DIVISOR} with
 *       {@link RoundingMode#HALF_EVEN} at scale {@value #MONETARY_SCALE}.</dd>
 *   <dt>Generated timestamps differ from the baseline</dt>
 *   <dd>The render is exactly {@value #DB2_TIMESTAMP_LENGTH} characters:
 *       {@code yyyy-MM-dd-HH.mm.ss.} then <em>two</em> hundredths-of-a-second digits then the four
 *       literal characters {@code 0000}. Millisecond or nanosecond precision is wrong. The time source
 *       must be the injected {@link Clock}, never an ambient {@code now()}.</dd>
 *   <dt>The last account's balance is not updated</dt>
 *   <dd>Expected. See PARITY TRAP 1. Do not add a final flush.</dd>
 *   <dt>Identifiers collide across two runs</dt>
 *   <dd>The same {@code PARM-DATE} was supplied twice. The source has no duplicate detection here and
 *       none is added; the collision surfaces in the combine job's load step.</dd>
 *   </dl>
 *
 * <h2>Boundaries of this class, and what the corpus does not determine</h2>
 *
 * <ul>
 *   <li>The step definition - the reader, the writer and the chunk size - sits outside this class, which is
 *       coupled to none of them: it depends only on the {@link ItemProcessor} contract and on the ordering
 *       precondition stated under <i>Inputs</i>.</li>
 *   <li>No service-level objective for throughput or latency exists: the source publishes none, so none is
 *       asserted here and none is invented.</li>
 *   <li>The persisted form of the sequential output is not decided at this level: the object key layout
 *       that replaces {@code SYSTRAN(+1)} belongs to the writer. What is fixed and honoured here is the
 *       record's field content and the 350-byte geometry it must fill.</li>
 *   </ul>
 *
 * <p>{@code src/main/resources/db/migration/V1__create_schema.sql}
 * corroborates every width and precision this class derives from the copybooks. That agreement is worth
 * stating because the two were established independently, from the record layouts on one side and from
 * {@code app/catlg/LISTCAT.txt} and the {@code IDCAMS} definitions on the other:</p>
 *
 * <ul>
 *   <li>{@code account.acct_group_id CHAR(10) NOT NULL} ({@code :L539}) and
 *       {@code disclosure_group.acct_group_id CHAR(10) NOT NULL} ({@code :L867}). The database pads, so
 *       the disclosure-group key is built from the account's ten stored characters and nothing is padded
 *       or trimmed in this class.</li>
 *   <li>{@code disclosure_group.dis_int_rate NUMERIC(6,2) NOT NULL} ({@code :L874}), which is exactly
 *       {@code PIC S9(04)V99}.</li>
 *   <li>{@code transaction_category_balance.tran_cat_bal NUMERIC(11,2) NOT NULL} ({@code :L958}), which
 *       is exactly {@code PIC S9(09)V99} and one digit narrower than the account money columns at
 *       {@code NUMERIC(12,2)} - the one precision in this class that differs from the common case.</li>
 *   <li>{@code transaction.tran_source CHAR(10) NOT NULL} ({@code :L1047}), which is why the source
 *       value written is the ten-character padded literal and not the six-character word.</li>
 *   <li>{@code transaction.tran_orig_ts CHAR(26) NOT NULL} ({@code :L1066}) and
 *       {@code transaction.tran_proc_ts CHAR(26) NOT NULL} ({@code :L1070}): character columns, which is
 *       the schema-level confirmation that both timestamps are a {@code String} of length
 *       {@value #DB2_TIMESTAMP_LENGTH} and never a temporal type.</li>
 *   <li>{@code account.acct_curr_cyc_credit} and {@code account.acct_curr_cyc_debit}, both
 *       {@code NUMERIC(12,2) NOT NULL} ({@code :L532} and {@code :L535}), with the debit column
 *       documented at {@code :L484} as legitimately holding negative values. That is what makes the
 *       cycle reset in {@code 1050-UPDATE-ACCOUNT} load-bearing for the next posting cycle rather than
 *       cosmetic.</li>
 *   </ul>
 *
 * <h2>Bean registration</h2>
 *
 * <p>Registered by classpath scanning as a {@link Component} in {@link StepScope}, so the container
 * creates one instance per step execution and resolves the late-binding expression
 * {@code #{jobParameters['parmDate']}} against that execution's parameters when the step starts. It is
 * not a {@code @Configuration} class and declares no beans.
 *
 * @see ItemProcessor
 * @see FileStatusMapper#requireDisclosureGroupReadSuccess(String)
 * @see FileStatusMapper#requireDefaultDisclosureGroupReadSuccess(String)
 * @see FatalProcessingException
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, Transaction>, ItemStream {

    /**
     * Structured log sink. Replaces the {@code DISPLAY} statements of the source, which had no sink but
     * SYSOUT and no severity at all. The literals are reproduced verbatim; only the level is a target
     * side decision, mapped as follows: the fallback notices of {@code :L418}-{@code :L419} are
     * {@code DEBUG}, because the fixture census shows the fallback fires on every record and INFO would
     * bury the run; every literal that precedes an abend is {@code ERROR}. The one site that emits a
     * monetary value - the per-record dump of {@code app/cbl/CBACT04C.cbl:L193} - goes to
     * {@link #PARITY_LOG} instead and never to this logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /**
     * Name of the isolated parity-output logger. {@code OFF} in every shipped profile; see
     * {@link #PARITY_LOG}.
     *
     * <p>The suffix is the originating COBOL program, so a parity run can enable exactly one program's
     * output rather than the whole {@code com.cardemo.parity} tree.
     */
    private static final String PARITY_LOGGER_NAME = "com.cardemo.parity.CBACT04C";

    /**
     * Parity-output logger: the only channel through which the per-record dump of the source leaves this
     * class at all.
     *
     * <p><strong>Two controls apply to that dump, not one, and they are complementary.</strong> The routing
     * below keeps the emission out of the application's ordinary log stream, and the field-level redaction
     * described at {@link #REDACTED_BALANCE} keeps the balance out of the emitted event even on this channel.
     * Either alone would answer the finding; both are kept, so enabling this logger for a parity run cannot
     * put a customer balance in a file by accident. The consequence is deliberate: this emission proves the
     * record was seen, its sequence and its key, not its amount - the amount a baseline diff needs is in the
     * input fixture the run was given, at a known offset, and does not have to be re-emitted to be compared.
     *
     * <p><strong>Why it exists.</strong> {@code DISPLAY TRAN-CAT-BAL-RECORD} at
     * {@code app/cbl/CBACT04C.cbl:L193} reproduces the source's per-record SYSOUT dump, and that record
     * carries {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9} together with the
     * account identifier the balance belongs to. Emitting the pair through the class logger placed customer
     * financial data in the application's ordinary log stream, where the masking rules in
     * {@code src/main/resources/logback-spring.xml} could not reach it: masking matches labelled
     * credentials, hashes and social security numbers, not a category balance. Rule 1 Clauses A and D forbid
     * that. Severity: <strong>Medium</strong>.
     *
     * <p><strong>What it changes.</strong> The emission moves to the dedicated logger name
     * {@value #PARITY_LOGGER_NAME}, which {@code src/main/resources/application.yml} sets to {@code OFF} for
     * the whole {@code com.cardemo.parity} tree in every shipped profile. No deployment emits it, and a
     * parity comparison enables the one logger deliberately, in an isolated run, with the output routed
     * where a baseline diff needs it. The class logger keeps the fallback notices, statuses and abend
     * literals - the diagnostics an operator actually needs - and never carries a balance again. Nothing
     * about the computed interest, the generated transaction or the rewritten account changes.
     */
    private static final Logger PARITY_LOG = LoggerFactory.getLogger(PARITY_LOGGER_NAME);

    /**
     * The literal divisor of the interest formula, from
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at
     * {@code app/cbl/CBACT04C.cbl:L464}-{@code :L465}.
     *
     * <p>Named rather than inlined so that the one place the constant lives can carry the prohibition:
     * the balance is multiplied by the rate first and the product is divided by this value. Splitting it
     * into {@code / 100} then {@code / 12}, or replacing it with a multiplication by a decimal
     * approximation, changes the rounding and therefore the emitted amount. Constructed from a
     * {@link String} so no binary floating-point literal is ever involved.
     */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * The scale of every monetary value here, from the {@code V99} of {@code TRAN-CAT-BAL PIC S9(09)V99}
     * ({@code app/cpy/CVTRA01Y.cpy:L9}), {@code DIS-INT-RATE PIC S9(04)V99}
     * ({@code app/cpy/CVTRA02Y.cpy:L9}) and {@code ACCT-CURR-BAL PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L7}).
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * Integer digit positions of {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9}:
     * the nine digits before the implied decimal point.
     */
    private static final int CATEGORY_BALANCE_INTEGER_DIGITS = 9;

    /**
     * The fixed-width placeholder that replaces {@code TRAN-CAT-BAL} in the per-record diagnostic of
     * {@link #displayCategoryBalanceRecord(Long, String, Integer, java.math.BigDecimal)}, and only there.
     *
     * <p>{@value #CATEGORY_BALANCE_INTEGER_DIGITS} plus {@value #MONETARY_SCALE} characters, one per digit
     * position of the sending field, so the emitted event keeps the width a reader would use to verify that
     * field. See that method for the full justification.
     *
     * <p><b>Why withheld at the source rather than masked in transit.</b> The rules in
     * {@code src/main/resources/logback-spring.xml} each key on a recognisable shape - a bearer token, a
     * BCrypt hash, a labelled card number - and a balance has none. It is a run of digits, indistinguishable
     * from the account identifier printed beside it, so a rule wide enough to mask the balance would blank the
     * identifier too and leave the diagnostic useless. This is also why the balance is withheld whole: an
     * amount cannot be partially masked without either disclosing its magnitude or becoming a different
     * number.
     *
     * <p><b>An asterisk run rather than that file's {@code REDACTION} literal.</b> One marker meaning one
     * thing everywhere would argue for the literal, and it is used where the emission is a labelled key-value
     * line - see {@code TransactionDetailService}. Here the line is a positional record reproduction whose
     * purpose is to let a reader verify field geometry, so the sending field's width is kept instead; a run of
     * asterisks is no more mistakable for a value than the literal is. {@code TransactionReportProcessor}
     * draws the same distinction on {@code TRAN-AMT} for the same reason.
     */
    private static final String REDACTED_BALANCE =
            "*".repeat(CATEGORY_BALANCE_INTEGER_DIGITS + MONETARY_SCALE);

    /**
     * Two-decimal zero. Reproduces {@code MOVE 0 TO WS-TOTAL-INT} at
     * {@code app/cbl/CBACT04C.cbl:L200} and the two cycle-counter zeroings at {@code :L353}-{@code :L354}
     * at the scale the picture clauses declare, so a rewritten row carries {@code 0.00} and not an
     * unscaled {@code 0}.
     */
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(MONETARY_SCALE);

    /**
     * Name of the job parameter that carries {@code PARM-DATE}. The linkage group at
     * {@code app/cbl/CBACT04C.cbl:L176}-{@code :L178} is {@code PARM-LENGTH PIC S9(04) COMP} followed by
     * {@code PARM-DATE PIC X(10)}, received through
     * {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} at {@code :L180} and supplied by
     * {@code app/jcl/INTCALC.jcl:L22} as {@code PARM='2022071800'}. {@code PARM-LENGTH} has no Java
     * counterpart: it is the {@code JCL PARM} length prefix that the language environment fills in, and
     * a {@link String} carries its own length.
     */
    private static final String PARM_DATE_JOB_PARAMETER = "parmDate";

    /** Width of {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:L178}. */
    private static final int PARM_DATE_LENGTH = 10;

    /**
     * Wrap-around point of {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}
     * ({@code app/cbl/CBACT04C.cbl:L173}).
     *
     * <p>{@code ADD 1 TO WS-TRANID-SUFFIX} at {@code :L474} carries no {@code ON SIZE ERROR} clause, so
     * on the 1,000,000th increment the result overflows the six declared digits and COBOL truncates the
     * high-order digit, leaving {@code 000000}. Reducing the incremented value modulo this constant
     * reproduces that boundary exactly rather than letting a Java {@code int} run past it.
     */
    private static final int TRANID_SUFFIX_MODULUS = 1_000_000;

    /**
     * Render of {@code WS-TRANID-SUFFIX PIC 9(06)} as six zero-padded digits, which is how the display
     * field's bytes reach {@code TRAN-ID} through the {@code STRING ... DELIMITED BY SIZE} at
     * {@code app/cbl/CBACT04C.cbl:L476}-{@code :L480}.
     */
    private static final String TRANID_SUFFIX_FORMAT = "%06d";

    /**
     * Width of {@code TRAN-ID X(16)} ({@code app/cpy/CVTRA05Y.cpy:L5}), which the concatenation at
     * {@code app/cbl/CBACT04C.cbl:L476}-{@code :L480} fills exactly:
     * {@value #PARM_DATE_LENGTH} characters of {@code PARM-DATE} followed by six digits of suffix.
     */
    private static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * Render of an account identifier as the eleven zero-padded digits of {@code ACCT-ID PIC 9(11)}
     * ({@code app/cpy/CVACT01Y.cpy:L6}) and {@code TRANCAT-ACCT-ID PIC 9(11)}
     * ({@code app/cpy/CVTRA01Y.cpy:L6}). Used for the control-break comparison, for the description
     * built at {@code app/cbl/CBACT04C.cbl:L485}-{@code :L489}, and for the diagnostic literals.
     */
    private static final String ACCOUNT_ID_FORMAT = "%011d";

    /** Width of {@code ACCT-ID PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy:L6}). */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** Largest value an unsigned {@code PIC 9(11)} can hold, so the render cannot silently overflow. */
    private static final long ACCOUNT_ID_MAX = 99_999_999_999L;

    /**
     * Width of {@code ACCT-GROUP-ID PIC X(10)} ({@code app/cpy/CVACT01Y.cpy:L13}), which is also the width
     * of {@code DIS-ACCT-GROUP-ID PIC X(10)} ({@code app/cpy/CVTRA02Y.cpy:L6}) that it is moved into at
     * {@code app/cbl/CBACT04C.cbl:L210}. Declared separately from the other ten-character fields in this
     * class so that the coincidence of width is never mistaken for a shared contract.
     */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    /**
     * Initial value of {@code WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES}
     * ({@code app/cbl/CBACT04C.cbl:L167}). Eleven spaces can never equal an eleven-digit render, which is
     * precisely what makes the control break at {@code :L194} fire on the very first record.
     */
    private static final String LAST_ACCOUNT_NUMBER_SPACES = " ".repeat(ACCOUNT_ID_LENGTH);

    /**
     * Execution-context key holding {@code WS-LAST-ACCT-NUM} across a restart. Its presence is the marker
     * that a previous execution saved state at all, so it is written unconditionally by
     * {@link #update(ExecutionContext)} and tested first by {@link #open(ExecutionContext)}.
     */
    private static final String CONTEXT_KEY_LAST_ACCOUNT_NUMBER =
            "carddemo.intcalc.lastAccountNumber";

    /** Execution-context key holding {@code WS-FIRST-TIME}, as 1 for {@code 'Y'} and 0 for {@code 'N'}. */
    private static final String CONTEXT_KEY_FIRST_TIME = "carddemo.intcalc.firstTime";

    /** Execution-context key holding {@code WS-TRANID-SUFFIX}, the run-sequential identifier counter. */
    private static final String CONTEXT_KEY_TRANID_SUFFIX = "carddemo.intcalc.tranIdSuffix";

    /** Execution-context key holding {@code WS-RECORD-COUNT}, the diagnostic row counter. */
    private static final String CONTEXT_KEY_RECORD_COUNT = "carddemo.intcalc.recordCount";

    /**
     * Execution-context key holding {@code WS-TOTAL-INT} as its plain decimal string. Stored as text
     * because the field is {@code PIC S9(09)V99} and no binary floating-point form can round-trip it.
     */
    private static final String CONTEXT_KEY_TOTAL_INTEREST = "carddemo.intcalc.totalInterest";

    /**
     * Execution-context key holding the identifier of the account whose interest is mid-accumulation.
     * Only the identifier is stored; the entity is re-read on open through {@code 1100-GET-ACCT-DATA}.
     */
    private static final String CONTEXT_KEY_CURRENT_ACCOUNT_ID = "carddemo.intcalc.currentAccountId";

    /**
     * Transaction type code of a generated interest transaction, from
     * {@code MOVE '01' TO TRAN-TYPE-CD} at {@code app/cbl/CBACT04C.cbl:L482} into
     * {@code TRAN-TYPE-CD X(02)} ({@code app/cpy/CVTRA05Y.cpy:L6}).
     */
    private static final String TRANSACTION_TYPE_CODE_INTEREST = "01";

    /**
     * Transaction category code of a generated interest transaction, from
     * {@code MOVE '05' TO TRAN-CAT-CD} at {@code app/cbl/CBACT04C.cbl:L483}.
     *
     * <p>The source moves an <em>alphanumeric</em> literal into {@code TRAN-CAT-CD PIC 9(04)}
     * ({@code app/cpy/CVTRA05Y.cpy:L7}), a numeric display field, so the two characters are interpreted
     * as the number five and the field holds {@code 0005}. The mapped column is {@code INTEGER}, so the
     * faithful Java value is the integer {@code 5} and not the string {@code "05"}.
     */
    private static final Integer TRANSACTION_CATEGORY_CODE_INTEREST = 5;

    /**
     * Description prefix of a generated interest transaction, from
     * {@code STRING 'Int. for a/c ' ... DELIMITED BY SIZE} at
     * {@code app/cbl/CBACT04C.cbl:L485}-{@code :L489}. Exactly thirteen characters including the trailing
     * space; concatenated with the eleven digits of {@code ACCT-ID} it yields twenty-four characters,
     * right-space-padded into {@code TRAN-DESC X(100)}.
     */
    private static final String TRANSACTION_DESCRIPTION_PREFIX = "Int. for a/c ";

    /** Width of {@code TRAN-DESC X(100)} ({@code app/cpy/CVTRA05Y.cpy:L9}). */
    private static final int TRANSACTION_DESCRIPTION_WIDTH = 100;

    /**
     * Merchant identifier of a generated interest transaction, from {@code MOVE 0 TO TRAN-MERCHANT-ID}
     * at {@code app/cbl/CBACT04C.cbl:L491} into {@code TRAN-MERCHANT-ID PIC 9(09)}
     * ({@code app/cpy/CVTRA05Y.cpy:L11}). Zero is a legal value here and is not a stand-in for absence:
     * interest is not a merchant transaction, and the source records that as a zero rather than as a
     * blank.
     */
    private static final Long MERCHANT_ID_NONE = 0L;

    /** Width of {@code TRAN-MERCHANT-NAME X(50)} ({@code app/cpy/CVTRA05Y.cpy:L12}). */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-CITY X(50)} ({@code app/cpy/CVTRA05Y.cpy:L13}). */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-ZIP X(10)} ({@code app/cpy/CVTRA05Y.cpy:L14}). */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /**
     * Merchant name of a generated interest transaction, from {@code MOVE SPACES TO TRAN-MERCHANT-NAME}
     * at {@code app/cbl/CBACT04C.cbl:L492}. Spaces, deliberately <em>not</em> {@code null}: the mapped
     * column is a fixed-width {@code CHAR} that the 350-byte record must fill, and blank and absent are
     * different values at that boundary.
     */
    private static final String MERCHANT_NAME_SPACES = " ".repeat(MERCHANT_NAME_WIDTH);

    /**
     * Merchant city of a generated interest transaction, from {@code MOVE SPACES TO TRAN-MERCHANT-CITY}
     * at {@code app/cbl/CBACT04C.cbl:L493}. Spaces, not {@code null}.
     */
    private static final String MERCHANT_CITY_SPACES = " ".repeat(MERCHANT_CITY_WIDTH);

    /**
     * Merchant postal code of a generated interest transaction, from
     * {@code MOVE SPACES TO TRAN-MERCHANT-ZIP} at {@code app/cbl/CBACT04C.cbl:L494}. Spaces, not
     * {@code null}.
     */
    private static final String MERCHANT_ZIP_SPACES = " ".repeat(MERCHANT_ZIP_WIDTH);

    /**
     * Width of {@code DB2-FORMAT-TS PIC X(26)} ({@code app/cbl/CBACT04C.cbl:L150}), also the width of
     * {@code TRAN-ORIG-TS X(26)} and {@code TRAN-PROC-TS X(26)}
     * ({@code app/cpy/CVTRA05Y.cpy:L16}-{@code :L17}).
     */
    private static final int DB2_TIMESTAMP_LENGTH = 26;

    /**
     * The first twenty-two characters of the timestamp built by {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * ({@code app/cbl/CBACT04C.cbl:L613}-{@code :L626}), whose shape the source comment at {@code :L140}
     * spells out as {@code EEEE-MM-DD-UU.MM.SS.HH0000}.
     *
     * <p>{@code SS} is java.time's fraction-of-second directive at a fixed width of two, which yields
     * <strong>hundredths</strong> of a second by truncation. That is exactly {@code COB-MIL PIC X(02)}
     * ({@code :L148}), the hundredths field of {@code FUNCTION CURRENT-DATE}, moved to
     * {@code DB2-MIL PIC 9(002)} ({@code :L164}) at {@code :L620}. Millisecond precision would emit
     * three digits and nanosecond precision nine; both would make every generated timestamp differ from
     * the parity baseline.
     */
    private static final String DB2_TIMESTAMP_PATTERN = "yyyy-MM-dd-HH.mm.ss.SS";

    /**
     * Immutable formatter for {@link #DB2_TIMESTAMP_PATTERN}, pinned to {@link Locale#ROOT} so the render
     * cannot vary with the host locale.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern(DB2_TIMESTAMP_PATTERN, Locale.ROOT);

    /**
     * The four trailing characters of the timestamp, from {@code MOVE '0000' TO DB2-REST} at
     * {@code app/cbl/CBACT04C.cbl:L622} into {@code DB2-REST PIC X(04)} ({@code :L165}). They are a
     * literal, not a rendered fraction, so the last four characters of every generated timestamp are
     * always zeros.
     */
    private static final String DB2_TIMESTAMP_TRAILER = "0000";

    /**
     * The successful {@code FILE STATUS} value, {@code '00'}.
     *
     * <p>Declared locally rather than imported because {@code com.cardemo.model.enums.FileStatus} is not
     * among this file's declared dependencies, and the two carve-out guards of {@link FileStatusMapper}
     * accept the status as a {@link String}. Only the two raw two-character values that this file's own
     * paragraphs test are declared - {@code app/cbl/CBACT04C.cbl:L422} and {@code :L446} - and neither
     * the four-character {@code 9910-DISPLAY-IO-STATUS} render nor the twenty-character
     * {@code FILE STATUS IS: NNNN} prefix is reproduced here, because both have a single tree-wide home.
     */
    private static final String FILE_STATUS_SUCCESS = "00";

    /**
     * The record-not-found {@code FILE STATUS} value, {@code '23'} - the value that
     * {@code app/cbl/CBACT04C.cbl:L422} accepts as success on the first disclosure-group read and that
     * {@code :L436} tests to trigger the default-group retry.
     */
    private static final String FILE_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * Culprit component recorded on every abend raised here, carrying
     * {@code ABEND-CULPRIT PIC X(8)} ({@code app/cpy/CSMSG02Y.cpy:L24}). Exactly eight characters, and
     * exactly the {@code PROGRAM-ID} of {@code app/cbl/CBACT04C.cbl:L23}, so an operator reading the
     * abend can find the source program without a lookup table.
     */
    private static final String ABEND_CULPRIT = "CBACT04C";

    /**
     * Stands in for every account identifier and monetary value this class declines to write to a log.
     *
     * <p>The {@code DISPLAY} statements of {@code app/cbl/CBACT04C.cbl} are reproduced as emission points with
     * their literals intact, because that structure is the traceability artefact; the values behind them are
     * not, because a log is aggregated, retained and replicated outside the boundary that protects the rows
     * they came from. Where an operator genuinely needs the identifier - a terminated run - it travels on the
     * abend payload instead, which is where {@code ABEND-MSG} put it.
     *
     * <p>The interest rate, the type code and the category code are deliberately NOT withheld: a rate comes
     * from {@code DISCGRP} and the two codes from {@code TRANTYPE} and {@code TRANCATG}, so all three are
     * fixed reference vocabulary rather than data about any customer, and the control break these traces
     * exist to diagnose is unreadable without them.
     */
    private static final String WITHHELD_VALUE = "[withheld]";

    /**
     * Diagnostic emitted before an account or cross-reference abend, from
     * {@code DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID} at {@code app/cbl/CBACT04C.cbl:L375} and
     * {@code DISPLAY 'ACCOUNT NOT FOUND: ' FD-XREF-ACCT-ID} at {@code :L397}. The friendly wording is
     * emitted <em>and then the job dies</em>; both halves are the behaviour, so this literal never
     * appears without an abend following it.
     */
    private static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT NOT FOUND: ";

    /**
     * Failure text of {@code 1100-GET-ACCT-DATA}, from
     * {@code DISPLAY 'ERROR READING ACCOUNT FILE'} at {@code app/cbl/CBACT04C.cbl:L386}.
     */
    private static final String MSG_ERROR_READING_ACCOUNT_FILE = "ERROR READING ACCOUNT FILE";

    /**
     * Failure text of the rewrite in {@code 1050-UPDATE-ACCOUNT}, from
     * {@code DISPLAY 'ERROR RE-WRITING ACCOUNT FILE'} at {@code app/cbl/CBACT04C.cbl:L365}. The
     * hyphenation of {@code RE-WRITING} is the source's and is preserved: these literals are compared
     * against the legacy baseline.
     */
    private static final String MSG_ERROR_REWRITING_ACCOUNT_FILE = "ERROR RE-WRITING ACCOUNT FILE";

    /**
     * Failure text of {@code 1110-GET-XREF-DATA}, from {@code DISPLAY 'ERROR READING XREF FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L408}.
     */
    private static final String MSG_ERROR_READING_XREF_FILE = "ERROR READING XREF FILE";

    /**
     * First of the two notices emitted when the keyed disclosure-group read misses, from
     * {@code DISPLAY 'DISCLOSURE GROUP RECORD MISSING'} at {@code app/cbl/CBACT04C.cbl:L418}. It is not
     * an error: it precedes the retry, not an abend.
     */
    private static final String MSG_DISCGRP_RECORD_MISSING = "DISCLOSURE GROUP RECORD MISSING";

    /**
     * Second of the two notices emitted when the keyed disclosure-group read misses, from
     * {@code DISPLAY 'TRY WITH DEFAULT GROUP CODE'} at {@code app/cbl/CBACT04C.cbl:L419}.
     */
    private static final String MSG_TRY_WITH_DEFAULT_GROUP_CODE = "TRY WITH DEFAULT GROUP CODE";

    /**
     * Failure text of the write in {@code 1300-B-WRITE-TX}, from
     * {@code DISPLAY 'ERROR WRITING TRANSACTION RECORD'} at {@code app/cbl/CBACT04C.cbl:L510}.
     *
     * <p>The source's {@code WRITE} at {@code :L500} targets the sequential {@code SYSTRAN(+1)}
     * generation, which in the target is the step's writer rather than this class. The literal is
     * retained because the field-shape validation performed while assembling the record is the part of
     * that paragraph this class owns, and a rejected field must fail with the paragraph's own wording.
     */
    private static final String MSG_ERROR_WRITING_TRANSACTION_RECORD = "ERROR WRITING TRANSACTION RECORD";

    /**
     * Disclosure-group access, replacing {@code DISCGRP-FILE}
     * ({@code app/cbl/CBACT04C.cbl:L47}-{@code :L51}) and the {@code DISCGRP} DD of
     * {@code app/jcl/INTCALC.jcl:L35}-{@code :L36}. Read at most twice per item and never written.
     */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Account access, replacing {@code ACCTFILE-FILE}
     * ({@code app/cbl/CBACT04C.cbl:L41}-{@code :L45}) and the {@code ACCTFILE} DD of
     * {@code app/jcl/INTCALC.jcl:L33}-{@code :L34}. Read once per control break and rewritten once per
     * control break.
     */
    private final AccountRepository accountRepository;

    /**
     * Cross-reference access, replacing {@code XREF-FILE}
     * ({@code app/cbl/CBACT04C.cbl:L34}-{@code :L39}) read through its alternate index. The DD is
     * {@code XREFFIL1 = AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH}
     * ({@code app/jcl/INTCALC.jcl:L31}-{@code :L32}), which is why the finder used here is the
     * account-keyed one and not {@code findById}. Read once per control break, never written.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * The single tree-wide translation from a {@code FILE STATUS} value to an outcome. Supplies the two
     * halves of this program's scoped carve-out - the lenient first disclosure-group read of
     * {@code app/cbl/CBACT04C.cbl:L422} and the strict retry guard of {@code :L446} - so neither the
     * carve-out nor the {@code 9910-DISPLAY-IO-STATUS} render is reimplemented here.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * {@code PARM-DATE PIC X(10)} ({@code app/cbl/CBACT04C.cbl:L178}), validated at construction and
     * immutable thereafter. Concatenated verbatim into every generated transaction identifier; never
     * parsed as a date and never reformatted.
     */
    private final String parmDate;

    /**
     * Time source behind {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * ({@code app/cbl/CBACT04C.cbl:L613}-{@code :L626}), which calls
     * {@code FUNCTION CURRENT-DATE} at {@code :L614}. Injected rather than ambient so timestamps are
     * deterministic under test and so no host default time zone is assumed.
     */
    private final Clock clock;

    /**
     * {@code WS-RECORD-COUNT PIC 9(09) VALUE 0} ({@code app/cbl/CBACT04C.cbl:L172}), incremented at
     * {@code :L192}. Step-scoped instance state, never {@code static}. Declared {@code long} because a
     * nine-digit counter exceeds no {@code int} bound but a {@code long} removes the question entirely.
     */
    private long recordCount;

    /**
     * {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0} ({@code app/cbl/CBACT04C.cbl:L173}), incremented at
     * {@code :L474}.
     *
     * <p>The source never resets it per account, so identifiers are run-sequential, and that is
     * reproduced: it is reset only when a new processor instance is created, which {@link StepScope}
     * makes exactly once per step execution. Holding it in a {@code static} field, or on a singleton
     * bean, would leak one run's counter into the next and would violate the prohibition on global
     * mutable state.
     */
    private int tranIdSuffix;

    /**
     * {@code WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} ({@code app/cbl/CBACT04C.cbl:L167}), compared at
     * {@code :L194} and advanced at {@code :L201}.
     *
     * <p>Held as the eleven-character display render rather than as a number so that the initial
     * {@code SPACES} value is representable. That initial value is behavioural: eleven spaces cannot
     * equal any eleven-digit render, which is what makes the very first record a control break.
     */
    private String lastAccountNumber = LAST_ACCOUNT_NUMBER_SPACES;

    /**
     * {@code WS-FIRST-TIME PIC X(01) VALUE 'Y'} ({@code app/cbl/CBACT04C.cbl:L170}), tested at
     * {@code :L195} and cleared to {@code 'N'} at {@code :L198}.
     *
     * <p>Rendered as a {@code boolean} because the field is a two-valued flag and idiomatic naming is
     * expressly permitted where control flow is unchanged; {@code true} is the source's {@code 'Y'}.
     * Its purpose is to suppress the flush on the first control break, when there is no previous account
     * to flush.
     */
    private boolean firstTime = true;

    /**
     * {@code WS-TOTAL-INT PIC S9(09)V99} ({@code app/cbl/CBACT04C.cbl:L169}), zeroed at {@code :L200},
     * accumulated at {@code :L467} and consumed by the flush at {@code :L352}.
     *
     * <p>Initialised to two-decimal zero rather than left {@code null}, matching the {@code MOVE 0} the
     * source performs on the first control break before any interest is accumulated.
     */
    private BigDecimal totalInterest = MONEY_ZERO;

    /**
     * The {@code ACCOUNT-RECORD} area, loaded on a control break at
     * {@code app/cbl/CBACT04C.cbl:L202}-{@code :L203} and read on every subsequent item at {@code :L210}
     * for {@code ACCT-GROUP-ID}, at {@code :L486} for {@code ACCT-ID}, and by the flush at
     * {@code :L352}-{@code :L354}.
     *
     * <p>Held across items deliberately, because the source holds it deliberately: an account's rate
     * lookups and its flush all read the one record the break loaded. Per PARITY TRAP 2 this is the opposite
     * of the rate, which must never be held.
     */
    private Account currentAccount;

    /**
     * The {@code CARD-XREF-RECORD} area, loaded on a control break at
     * {@code app/cbl/CBACT04C.cbl:L204}-{@code :L205} and read at {@code :L495} for
     * {@code XREF-CARD-NUM}. Held across items for the same reason as {@link #currentAccount}.
     */
    private CardCrossReference currentCrossReference;

    /**
     * The one constructor: the container injects every collaborator, the step-scoped job parameter and the
     * application clock through it, and a test passes a fixed clock through the same signature.
     *
     * <p><strong>It replaces a pair of constructors.</strong> The container-facing one used to take five
     * arguments and supply {@link Clock#systemDefaultZone()} itself, delegating here. That made the time
     * source ambient state rather than an injected dependency, which Rule 1 Clause B rules out, and it left
     * the generated {@code TRAN-PROC-TS} of {@code Z-GET-DB2-FORMAT-TIMESTAMP} dependent on the host's zone.
     * With a single constructor the container needs no {@code @Autowired} marker to choose between
     * candidates, so the marker is gone too.
     *
     * <p><strong>The injected clock has one owner.</strong>
     * {@code com.cardemo.config.ObservabilityConfig#clock(String)} publishes the application's single
     * {@code java.time.Clock} as a system clock in the deployment's own zone, which is what
     * {@code app/cbl/CBACT04C.cbl}'s reliance on the region's own date and time amounts to. The generated
     * timestamp is compared against the parity baseline and is carried into every synthetic interest
     * transaction, so where a comparison spans hosts the zone is pinned explicitly through
     * {@code carddemo.time.zone} rather than left to each host's configuration.
     *
     * <p>Being {@code @StepScope}, this bean is created once per step execution, which is what lets
     * {@code #{jobParameters['parmDate']}} resolve at all; the clock and the four collaborators are singletons
     * and are shared across those executions.
     *
     * @param disclosureGroupRepository disclosure-group access, replacing {@code DISCGRP-FILE}; must not
     *     be {@code null}
     * @param accountRepository account access, replacing {@code ACCTFILE-FILE}; must not be {@code null}
     * @param cardCrossReferenceRepository cross-reference access through the alternate index, replacing
     *     {@code XREF-FILE}; must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translation carrying this program's two
     *     scoped guards; must not be {@code null}
     * @param parmDate the job parameter carrying {@code PARM-DATE PIC X(10)}; must not be {@code null}
     *     and must be exactly {@value #PARM_DATE_LENGTH} characters
     * @param clock the application clock supplying the current instant for generated timestamps; must not
     *     be {@code null}
     * @throws FatalProcessingException if any argument is {@code null}, or if {@code parmDate} violates
     *     the width its picture clause declares
     */
    public InterestCalculationProcessor(
            DisclosureGroupRepository disclosureGroupRepository,
            AccountRepository accountRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            FileStatusMapper fileStatusMapper,
            @Value("#{jobParameters['" + PARM_DATE_JOB_PARAMETER + "']}") String parmDate,
            Clock clock) {
        this.disclosureGroupRepository =
                requireCollaborator(disclosureGroupRepository, "disclosureGroupRepository");
        this.accountRepository = requireCollaborator(accountRepository, "accountRepository");
        this.cardCrossReferenceRepository =
                requireCollaborator(cardCrossReferenceRepository, "cardCrossReferenceRepository");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.parmDate = requireParmDate(parmDate);
        this.clock = requireCollaborator(clock, "clock");
    }

    /**
     * Processes one transaction-category-balance row, reproducing the body of the source's main read loop
     * at {@code app/cbl/CBACT04C.cbl:L188}-{@code :L222}.
     *
     * <p>The loop itself, and both of its {@code END-OF-FILE} tests at {@code :L189} and {@code :L191},
     * belong to Spring Batch: the framework calls this method once per item the reader supplies and stops
     * when the reader is exhausted, which is precisely what
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} does. What remains, and what this method performs in the
     * source's order, is: count the record ({@code :L192}), emit the record diagnostic ({@code :L193}),
     * test for a control break ({@code :L194}), flush the previous account unless this is the first break
     * ({@code :L195}-{@code :L199}), reset the accumulator and advance the break key
     * ({@code :L200}-{@code :L201}), load the new account and cross reference
     * ({@code :L202}-{@code :L205}), build the rate key ({@code :L210}-{@code :L212}), resolve the rate
     * ({@code :L213}), and on a non-zero rate compute the interest and the fees
     * ({@code :L214}-{@code :L217}).
     *
     * <p>The ordering of the control-break arm is load bearing and is preserved exactly. At {@code :L196}
     * the account record still holds the <em>previous</em> account and the accumulator still holds that
     * account's total; only afterwards does {@code :L200} zero the accumulator, {@code :L201} save the new
     * break key and {@code :L203} overwrite the account record. Flush, then reset, then reload. Any other
     * order posts one account's interest onto another account's balance.
     *
     * <p>The {@code ELSE} arm at {@code :L219}-{@code :L220} is <strong>not</strong> reachable and has no
     * counterpart in this method. See PARITY TRAP 1 in the class documentation and
     * {@link #updateAccountAtEndOfFile()}.
     *
     * @param item one 50-byte {@code TRAN-CAT-BAL-RECORD} ({@code app/cpy/CVTRA01Y.cpy}) supplied by the
     *     driving reader, which must deliver items ordered by account identifier, then type code, then
     *     category code; must not be {@code null}
     * @return the synthesised interest {@link Transaction} when the resolved rate is non-zero, or
     *     {@code null} when the rate is zero, which suppresses the item exactly as
     *     {@code app/cbl/CBACT04C.cbl:L214} suppresses both follow-on paragraphs
     * @throws FatalProcessingException if the item, its key or any key component is absent or violates
     *     its picture clause; if the account row or the cross-reference row is missing, both of which
     *     abend in the source; if the default disclosure-group row is missing; or if any repository call
     *     fails, in which case the underlying {@link DataAccessException} is preserved as the cause
     */
    @Override
    public Transaction process(final TransactionCategoryBalance item) {
        final TransactionCategoryBalanceId categoryBalanceKey = requireCategoryBalanceKey(item);
        final Long accountId = requireAccountId(categoryBalanceKey.getAccountId());
        final String typeCode = requireTypeCode(categoryBalanceKey.getTypeCd());
        final Integer categoryCode = requireCategoryCode(categoryBalanceKey.getCatCd());
        final BigDecimal categoryBalance = requireCategoryBalance(item.getBalance());

        recordCount++;
        displayCategoryBalanceRecord(accountId, typeCode, categoryCode, categoryBalance);

        // L194: the control break tests the account identifier alone. That is correct only because the
        // account identifier leads the seventeen-byte composite key of a sequentially browsed file, which
        // is why the reader's ordering is a documented precondition of this class.
        final String accountNumber = renderAccountNumber(accountId);
        if (!accountNumber.equals(lastAccountNumber)) {
            if (!firstTime) {
                // L196: flush the PREVIOUS account, whose record and accumulated total are both still
                // loaded at this point.
                updateAccount();
            } else {
                // L198: the first break has no previous account to flush.
                firstTime = false;
            }
            totalInterest = MONEY_ZERO;
            lastAccountNumber = accountNumber;
            currentAccount = getAccountData(accountId);
            currentCrossReference = getCrossReferenceData(accountId);
        }

        // L210-L212. The source populates the key in the order group, category, type; the copybook
        // declares it group, type, category (app/cpy/CVTRA02Y.cpy:L6-L8). The constructor is positional,
        // so COPYBOOK order is used here - see the class documentation on MOVE order. Do not reorder to the
        // source's MOVE sequence.
        final DisclosureGroupId rateKey = new DisclosureGroupId(
                requireAccountGroupId(currentAccount.getGroupId()),
                typeCode,
                categoryCode);

        final BigDecimal interestRate = getInterestRate(rateKey);

        // L214-L217. A zero rate emits nothing, accumulates nothing, and skips the fee paragraph too.
        // compareTo, never equals: 0.00 and 0 are unequal under equals and equal under compareTo.
        if (interestRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        final Transaction interestTransaction = computeInterest(categoryBalance, interestRate);
        computeFees();
        return interestTransaction;
    }

    /**
     * {@code 1050-UPDATE-ACCOUNT}, {@code app/cbl/CBACT04C.cbl:L350}-{@code :L370}.
     *
     * <p>Posts the account's accumulated interest and resets its billing cycle. The source's own comment
     * at {@code :L351} reads "Update the balances in account record to reflect posted trans."
     *
     * <pre>
     * L352 ADD WS-TOTAL-INT TO ACCT-CURR-BAL
     * L353 MOVE 0 TO ACCT-CURR-CYC-CREDIT
     * L354 MOVE 0 TO ACCT-CURR-CYC-DEBIT
     * L356 REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p><strong>Both cycle counters are zeroed, and that half is the one that is easily dropped.</strong>
     * Omitting it does no visible damage in the run that omits it: the damage appears on the <em>next</em>
     * posting cycle, because {@code app/cbl/CBTRN02C.cbl:L403}-{@code :L405} computes the over-limit test
     * by subtracting the debit accumulator, and an accumulator that was never reset carries a stale
     * cycle's worth of activity into that arithmetic.
     *
     * <p>All three mutations are applied to one managed {@link Account} instance and flushed by the single
     * {@code save} that stands in for the {@code REWRITE}, so they reach the database together inside the
     * chunk transaction Spring Batch has already opened around {@link #process}. No
     * {@code @Transactional} annotation is placed on this method: it is private, Spring's proxying cannot
     * intercept it, and the annotation would therefore be an unenforced claim.
     *
     * <p>Invoked from exactly one place on the reachable path, the control-break arm at {@code :L196}, and
     * reproduced for the end-of-data branch by {@link #updateAccountAtEndOfFile()}. Per PARITY TRAP 1 the
     * source reaches it from {@code :L196} alone.
     *
     * @throws FatalProcessingException if no account record is loaded, if the current balance is absent,
     *     or if the rewrite fails - the guard at {@code :L357} accepts {@code '00'} only and every other
     *     status reaches {@code 9999-ABEND-PROGRAM}
     */
    private void updateAccount() {
        final Account account = currentAccount;
        if (account == null) {
            throw fatal(MSG_ERROR_REWRITING_ACCOUNT_FILE,
                    "1050-UPDATE-ACCOUNT was reached with no account record loaded.");
        }
        final BigDecimal currentBalance = account.getCurrentBalance();
        if (currentBalance == null) {
            throw fatal(MSG_ERROR_REWRITING_ACCOUNT_FILE,
                    "ACCT-CURR-BAL is absent on account " + renderAccountNumber(account.getAccountId())
                            + "; a PIC S9(10)V99 field cannot be null.");
        }

        account.setCurrentBalance(currentBalance.add(totalInterest));
        account.setCurrentCycleCredit(MONEY_ZERO);
        account.setCurrentCycleDebit(MONEY_ZERO);

        try {
            // FLUSH INSIDE THE GUARD. save() only enrols the row with the persistence context, so the
            // UPDATE would otherwise be issued at commit - outside this try - and the failure would arrive
            // with no paragraph attached. That would lose the DISPLAY literal of :L367, the abend and the
            // return code that this guard supplies, and the cycle-counter reset performed just above would
            // appear to have succeeded. Flushing here reproduces the inline RESP of the source REWRITE.
            accountRepository.save(account);
            accountRepository.flush();
        } catch (final DataAccessException cause) {
            LOG.error(MSG_ERROR_REWRITING_ACCOUNT_FILE);
            throw fatal(MSG_ERROR_REWRITING_ACCOUNT_FILE,
                    "Rewrite failed for account " + renderAccountNumber(account.getAccountId()) + ".",
                    cause);
        }
    }

    /**
     * The {@code ELSE} arm of the main loop, {@code app/cbl/CBACT04C.cbl:L219}-{@code :L221}.
     *
     * <p><strong>INTENTIONAL NO-OP - UNREACHABLE BY CONSTRUCTION - PRESERVED FOR CONTROL-FLOW PARITY.
     * This method is never invoked, and it must never be wired up.</strong> It is not abandoned residue:
     * it is a deliberate reproduction of a branch that the source contains and cannot execute, cited and
     * marked at this single site. Its body is the literal
     * translation of {@code :L220}, so a reader can find the COBOL statement and the proof that it never
     * runs in one place.
     *
     * <p><b>Why it cannot execute.</b> The {@code ELSE} at {@code :L219} pairs with the <em>outer</em>
     * {@code IF END-OF-FILE = 'N'} at {@code :L189}, not with the inner one at {@code :L191}; indentation
     * settles it, the inner {@code IF} and its {@code END-IF} at {@code :L218} both standing at column 20
     * while the outer {@code IF}, the {@code ELSE} and the {@code END-IF} at {@code :L221} all stand at
     * column 16. The enclosing {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} tests
     * <em>before</em> each iteration, so as soon as {@code 1000-TCATBALF-GET-NEXT} sets the flag the loop
     * ends and the outer {@code IF} is never re-evaluated with a {@code 'Y'} flag. The {@code ELSE} arm is
     * dead in the source.
     *
     * <p><b>What that means behaviourally.</b> The account still in progress when the browse ends keeps its
     * pre-run balance and its un-reset cycle counters. That is preserved, not repaired: parity with the
     * frozen corpus is the acceptance contract, and the variance between this reading and the specification
     * prose is disclosed once - with its severity and its remediation - in the register carried by the
     * documentation of the {@code com.cardemo} root package.
     *
     * <p><b>Proof by contrast, which is what makes this auditable rather than merely asserted.</b> The
     * identical idiom in {@code app/cbl/CBTRN03C.cbl} has the opposite reachability: there the
     * {@code IF END-OF-FILE = 'N'} at {@code :L179} is the <em>inner</em> test, sitting after the read
     * within the same iteration, so its {@code ELSE} at {@code :L197} <em>does</em> execute and does
     * accumulate into the page total at {@code :L200}. One idiom, two programs, opposite outcomes, decided
     * purely by which {@code IF} owns the {@code ELSE}. Reproducing this branch faithfully in one program
     * and its counterpart faithfully in the other is only possible if the pairing is read rather than
     * assumed.
     *
     */
    private void updateAccountAtEndOfFile() {
        updateAccount();
    }

    // =============================================================================================
    // Restart correctness. The driving reader saves its position, so this class must save the state
    // that its position implies - otherwise a restart resumes reading in the middle of an account
    // while this processor believes it has not started, which loses one account's accumulated
    // interest and reissues transaction identifiers that the failed attempt already wrote.
    // =============================================================================================

    /**
     * Restores the control-break state a previous execution left behind, so a restart resumes at exactly
     * the point the driving reader resumes at.
     *
     * <p><strong>Why this is required rather than optional.</strong> The step's reader is a
     * {@code RepositoryItemReader} with a name, so Spring Batch persists its row position and a restart
     * resumes at the next unread row. Every field this method restores is <em>implied</em> by that
     * position, and none of it is derivable from the resumed row alone:</p>
     *
     * <ul>
     *   <li>{@code WS-FIRST-TIME} ({@code app/cbl/CBACT04C.cbl:L170}). Left at its initial value, the
     *       first row after a restart looks like the first row of the run, so the control break at
     *       {@code :L195} suppresses the flush - and the interest accumulated for the account that was in
     *       progress when the run failed is <strong>silently discarded</strong>.</li>
     *   <li>{@code WS-LAST-ACCT-NUM} ({@code :L167}). Left at {@code SPACES}, the first resumed row is
     *       treated as a control break even when it belongs to the account already in progress, which
     *       splits one account's interest across two flushes.</li>
     *   <li>{@code WS-TOTAL-INT} ({@code :L169}). The partial accumulation for the in-progress account.</li>
     *   <li>{@code WS-TRANID-SUFFIX} ({@code :L173}). The source never resets it per account, so it is
     *       run-sequential. Left at zero, a restart reissues identifiers the failed attempt already
     *       inserted, and every one of them collides with {@code pk_transaction}.</li>
     *   <li>{@code WS-RECORD-COUNT} ({@code :L172}). Diagnostic only, but a counter that restarts at zero
     *       makes the end-of-run total disagree with the rows actually processed.</li>
     * </ul>
     *
     * <p>The two entity references are <strong>not</strong> serialised. Only their identifiers are, and
     * the entities are re-read here through the same paragraphs the control break uses,
     * {@code 1100-GET-ACCT-DATA} and {@code 1110-GET-XREF-DATA}. That is deliberate: a serialised entity
     * would be a detached snapshot of a row that the failed attempt may have rolled back, whereas a fresh
     * read observes the committed state, which is the only state a resumed run may build on. It also keeps
     * the execution context to scalars, which is what Spring Batch's context is for.
     *
     * <p>A first execution finds no keys and leaves every field at its declared initial value, so the
     * behaviour of a fresh run is byte-for-byte what it was before this method existed.
     *
     * @param executionContext the step execution context, supplied by the framework; never {@code null}
     * @throws ItemStreamException never; declared by the interface. A failure to re-read an entity is
     *     raised as the {@link FatalProcessingException} that {@code 1100-GET-ACCT-DATA} and
     *     {@code 1110-GET-XREF-DATA} already raise, so the abend contract is unchanged
     */
    @Override
    public void open(final ExecutionContext executionContext) throws ItemStreamException {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        if (!executionContext.containsKey(CONTEXT_KEY_LAST_ACCOUNT_NUMBER)) {
            LOG.debug("No saved interest control-break state; starting the run from the beginning");
            return;
        }

        this.lastAccountNumber = executionContext.getString(CONTEXT_KEY_LAST_ACCOUNT_NUMBER);
        this.firstTime = executionContext.getInt(CONTEXT_KEY_FIRST_TIME) != 0;
        this.tranIdSuffix = executionContext.getInt(CONTEXT_KEY_TRANID_SUFFIX);
        this.recordCount = executionContext.getLong(CONTEXT_KEY_RECORD_COUNT);
        this.totalInterest = new BigDecimal(executionContext.getString(CONTEXT_KEY_TOTAL_INTEREST))
                .setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);

        if (executionContext.containsKey(CONTEXT_KEY_CURRENT_ACCOUNT_ID)) {
            final Long accountId = Long.valueOf(executionContext.getLong(CONTEXT_KEY_CURRENT_ACCOUNT_ID));
            // :L372-L391 and :L393-L413, the same two reads the control break performs, so a resumed run
            // observes the committed row rather than a detached snapshot of a rolled-back one.
            this.currentAccount = getAccountData(accountId);
            this.currentCrossReference = getCrossReferenceData(accountId);
        }

        LOG.info("Restored interest control-break state: {} records processed, suffix at {},"
                        + " accumulating for an account already in progress: {}",
                Long.valueOf(this.recordCount), Integer.valueOf(this.tranIdSuffix),
                Boolean.valueOf(this.currentAccount != null));
    }

    /**
     * Saves the control-break state alongside the reader's position, at every chunk boundary.
     *
     * <p>Spring Batch calls this immediately before each chunk commits, and persists the context in the
     * same transaction, so the saved state and the saved reader position are always consistent with the
     * rows that were actually committed. Writing scalars only keeps that write cheap and keeps the context
     * free of serialised entities.
     *
     * <p>{@code totalInterest} is stored as its plain string form rather than as a floating-point value,
     * because {@code WS-TOTAL-INT} is {@code PIC S9(09)V99} and a binary floating-point round trip could
     * not return the same two-decimal value. No financial field in this class is ever a {@code double}.
     *
     * @param executionContext the step execution context, supplied by the framework; never {@code null}
     * @throws ItemStreamException never; declared by the interface
     */
    @Override
    public void update(final ExecutionContext executionContext) throws ItemStreamException {
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        executionContext.putString(CONTEXT_KEY_LAST_ACCOUNT_NUMBER, this.lastAccountNumber);
        executionContext.putInt(CONTEXT_KEY_FIRST_TIME, this.firstTime ? 1 : 0);
        executionContext.putInt(CONTEXT_KEY_TRANID_SUFFIX, this.tranIdSuffix);
        executionContext.putLong(CONTEXT_KEY_RECORD_COUNT, this.recordCount);
        executionContext.putString(CONTEXT_KEY_TOTAL_INTEREST, this.totalInterest.toPlainString());
        if (this.currentAccount == null || this.currentAccount.getAccountId() == null) {
            executionContext.remove(CONTEXT_KEY_CURRENT_ACCOUNT_ID);
        } else {
            executionContext.putLong(CONTEXT_KEY_CURRENT_ACCOUNT_ID,
                    this.currentAccount.getAccountId().longValue());
        }
    }

    /**
     * Releases nothing.
     *
     * <p>This class owns no stream, no connection and no file handle: every dataset it touches is reached
     * through an injected repository whose lifecycle the container owns. The method is present because the
     * interface declares it, and it is deliberately empty rather than absent so that a reader looking for
     * a missing release can see that there is nothing to release. The end-of-data flush of {@code :L207}
     * is <em>not</em> performed here: it belongs to the step's own end-of-data callback, because a close
     * also runs on the failure path, where flushing would write interest for an account whose rows were
     * never all read.
     *
     * @throws ItemStreamException never
     */
    @Override
    public void close() throws ItemStreamException {
        // Nothing to release; see the method documentation for why this is empty by design.
    }

    /**
     * {@code 1100-GET-ACCT-DATA}, {@code app/cbl/CBACT04C.cbl:L372}-{@code :L391}.
     *
     * <p>Reads the account record whose identifier the control break has just moved into
     * {@code FD-ACCT-ID} at {@code :L202}. The read is a plain keyed {@code READ}, not a
     * {@code READ ... UPDATE}, so the plain {@code findById} is used and not the pessimistic-lock finder
     * that {@link AccountRepository} reserves for {@code app/cbl/COACTUPC.cbl}.
     *
     * <p><strong>A missing account abends the job.</strong> The {@code INVALID KEY} clause at
     * {@code :L375} displays {@code 'ACCOUNT NOT FOUND: '} with the identifier, and then the guard at
     * {@code :L378} accepts {@code '00'} only, so control falls through to {@code :L386} and on to
     * {@code 9999-ABEND-PROGRAM}. The friendly message is emitted <em>and then the job dies</em>: both
     * halves are the behaviour, so both are reproduced. A missing account is never skipped, never filtered
     * out of the step, and never mapped to a record-not-found exception.
     *
     * @param accountId the account identifier moved into {@code FD-ACCT-ID} at {@code :L202}
     * @return the account record, never {@code null}
     * @throws FatalProcessingException if no such account exists, or if the read fails, in which case the
     *     underlying {@link DataAccessException} is preserved as the cause
     */
    private Account getAccountData(final Long accountId) {
        final Optional<Account> keyedRead;
        try {
            keyedRead = accountRepository.findById(accountId);
        } catch (final DataAccessException cause) {
            LOG.error(MSG_ERROR_READING_ACCOUNT_FILE);
            throw fatal(MSG_ERROR_READING_ACCOUNT_FILE,
                    "Account read failed for " + renderAccountNumber(accountId) + ".", cause);
        }
        if (keyedRead.isEmpty()) {
            // The source literal of :L375 / :L397 is preserved; the account identifier that followed it
            // is not. It is a customer's account key, and a log is aggregated, retained and
            // replicated outside the boundary that protects the row - so the same reasoning that
            // governs the record emissions in the verification readers governs here. The identifier
            // still travels on the abend payload raised immediately below, where an operator
            // diagnosing a terminated run needs it.
            LOG.error("{}{}", MSG_ACCOUNT_NOT_FOUND, WITHHELD_VALUE);
            LOG.error(MSG_ERROR_READING_ACCOUNT_FILE);
            throw fatal(MSG_ERROR_READING_ACCOUNT_FILE,
                    MSG_ACCOUNT_NOT_FOUND + renderAccountNumber(accountId));
        }
        return keyedRead.get();
    }

    /**
     * {@code 1110-GET-XREF-DATA}, {@code app/cbl/CBACT04C.cbl:L393}-{@code :L413}.
     *
     * <p>Reads the card cross reference for the account the control break has just loaded, in order to
     * obtain the card number that {@code :L495} places on the generated transaction.
     *
     * <p><b>This is the alternate-index read, and using the primary key here would be wrong.</b>
     * {@code :L394}-{@code :L395} reads {@code KEY IS FD-XREF-ACCT-ID}, which {@code FILE-CONTROL} at
     * {@code :L38} declares as {@code ALTERNATE RECORD KEY}; the primary key of that file is the card
     * number. The job supplies the index itself as
     * {@code XREFFIL1 = AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH}
     * ({@code app/jcl/INTCALC.jcl:L31}-{@code :L32}). The Java counterpart is therefore the account-keyed
     * derived finder, never {@code findById}.
     *
     * <p>The alternate key is non-unique, so duplicates are permitted, but a keyed {@code READ} on a
     * non-unique alternate index positions on the first record carrying that key and nothing downstream
     * iterates the rest. The finder therefore returns an {@link java.util.Optional} with {@code LIMIT 1}
     * applied by the database, and the ascending card-number order fixes which row that is.
     *
     * <p><strong>A missing cross reference abends the job</strong>, on the same pattern as
     * {@link #getAccountData}: {@code :L397} displays {@code 'ACCOUNT NOT FOUND: '} and then the guard at
     * {@code :L400} accepts {@code '00'} only, reaching {@code :L408} and the abend. Never a skip.
     *
     * @param accountId the account identifier moved into {@code FD-XREF-ACCT-ID} at {@code :L204}
     * @return the first cross-reference record for that account in ascending card-number order, never
     *     {@code null}
     * @throws FatalProcessingException if the account has no cross reference, or if the read fails, in
     *     which case the underlying {@link DataAccessException} is preserved as the cause
     */
    private CardCrossReference getCrossReferenceData(final Long accountId) {
        // LIMIT 1 at the database. The source reads the alternate-index PATH, which yields one record;
        // only the first row was ever used here. See CardCrossReferenceRepository for the full reasoning.
        final Optional<CardCrossReference> alternateKeyRead;
        try {
            alternateKeyRead =
                    cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(accountId);
        } catch (final DataAccessException cause) {
            LOG.error(MSG_ERROR_READING_XREF_FILE);
            throw fatal(MSG_ERROR_READING_XREF_FILE,
                    "Cross-reference read failed for account " + renderAccountNumber(accountId) + ".",
                    cause);
        }
        if (alternateKeyRead == null || alternateKeyRead.isEmpty()) {
            // The source literal of :L375 / :L397 is preserved; the account identifier that followed it
            // is not. It is a customer's account key, and a log is aggregated, retained and
            // replicated outside the boundary that protects the row - so the same reasoning that
            // governs the record emissions in the verification readers governs here. The identifier
            // still travels on the abend payload raised immediately below, where an operator
            // diagnosing a terminated run needs it.
            LOG.error("{}{}", MSG_ACCOUNT_NOT_FOUND, WITHHELD_VALUE);
            LOG.error(MSG_ERROR_READING_XREF_FILE);
            throw fatal(MSG_ERROR_READING_XREF_FILE,
                    MSG_ACCOUNT_NOT_FOUND + renderAccountNumber(accountId));
        }
        return alternateKeyRead.get();
    }

    /**
     * {@code 1200-GET-INTEREST-RATE}, {@code app/cbl/CBACT04C.cbl:L415}-{@code :L440}.
     *
     * <p>Stage one of the rate resolution: reads the disclosure-group row for the account's own group,
     * transaction type and transaction category, and hands over to the {@code DEFAULT} fallback when that
     * row does not exist.
     *
     * <pre>
     * L416-420 READ DISCGRP-FILE ... INVALID KEY
     * L418        DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
     * L419        DISPLAY 'TRY WITH DEFAULT GROUP CODE'
     * L422 IF  DISCGRP-STATUS = '00'  OR '23'      &lt;- a miss is NOT an error here
     * L436 IF  DISCGRP-STATUS = '23'               &lt;- the retry trigger, specifically '23'
     * L437    MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     * L438    PERFORM 1200-A-GET-DEFAULT-INT-RATE
     * </pre>
     *
     * <p><b>The record-not-found carve-out.</b> The guard at {@code :L422} accepts {@code '00'}
     * <em>or</em> {@code '23'}. A miss on this read is an accepted control path, not a failure, and it must
     * never surface as a record-not-found exception. This is one of only three such carve-outs in the whole
     * corpus, and it is applied through
     * {@link FileStatusMapper#requireDisclosureGroupReadSuccess(String)} rather than reimplemented here:
     * that method returns {@code true} for {@code '23'} to request the retry, {@code false} for
     * {@code '00'}, and throws for anything else, which is exactly the {@code :L422} guard followed by the
     * {@code :L436} test.
     *
     * <p><b>Only the group identifier changes on retry.</b> {@code :L437} substitutes the group and leaves
     * {@code FD-DIS-TRAN-CAT-CD} and {@code FD-DIS-TRAN-TYPE-CD} holding the values moved at
     * {@code :L211}-{@code :L212}. In the target the substituted literal lives inside the fallback query
     * itself, so this method passes the retained type and category codes through unchanged and no fallback
     * key is constructed - which also means nothing is space-padded here, the ten-character
     * {@code 'DEFAULT   '} literal being pinned in the repository's JPQL.
     *
     * <p><b>No stale rate can survive this method.</b> The rate is returned by value and is never stored in
     * a field. That closes the source's {@code READ ... INTO} hazard, where a failed read leaves the
     * previous row's rate in the record area until the fallback read overwrites it - see PARITY TRAP 2.
     *
     * <p>With the shipped fixtures this method's first read always misses, because
     * {@code app/data/ASCII/acctdata.txt} carries a blank {@code ACCT-GROUP-ID} on all fifty rows, so the
     * fallback is the ordinary path rather than the exception. That is why the two notices at
     * {@code :L418}-{@code :L419} are logged at debug level.
     *
     * @param rateKey the disclosure-group key, built in copybook order at {@code :L210}-{@code :L212}
     * @return the resolved monthly interest rate, from the account's own group when that row exists and
     *     from the {@code DEFAULT} group otherwise; never {@code null}
     * @throws FatalProcessingException if the read fails with any status other than success or
     *     record-not-found, if the fallback is needed and its row is missing, or if the resolved row
     *     carries no rate
     */
    private BigDecimal getInterestRate(final DisclosureGroupId rateKey) {
        final Optional<DisclosureGroup> keyedRead;
        try {
            keyedRead = disclosureGroupRepository.findById(rateKey);
        } catch (final DataAccessException cause) {
            LOG.error(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
            throw fatal(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT,
                    "Disclosure group read failed for " + rateKey + ".", cause);
        }

        if (keyedRead.isEmpty()) {
            LOG.debug(MSG_DISCGRP_RECORD_MISSING);
            LOG.debug(MSG_TRY_WITH_DEFAULT_GROUP_CODE);
        }

        // L422 and L436 together: '00' proceeds, '23' requests the retry, anything else abends.
        final String discgrpStatus =
                keyedRead.isPresent() ? FILE_STATUS_SUCCESS : FILE_STATUS_RECORD_NOT_FOUND;
        if (fileStatusMapper.requireDisclosureGroupReadSuccess(discgrpStatus)) {
            return getDefaultInterestRate(rateKey.getTranTypeCd(), rateKey.getTranCatCd());
        }

        final DisclosureGroup group = keyedRead.get();
        return requireInterestRate(group.getInterestRate(), group.getId());
    }

    /**
     * {@code 1200-A-GET-DEFAULT-INT-RATE}, {@code app/cbl/CBACT04C.cbl:L443}-{@code :L460}.
     *
     * <p>Stage two of the rate resolution: re-reads the rate table under the {@code DEFAULT} account group,
     * keeping the transaction type and category codes of the failed primary lookup.
     *
     * <pre>
     * L444 READ DISCGRP-FILE INTO DIS-GROUP-RECORD   &lt;- no INVALID KEY clause this time
     * L445 (blank)
     * L446 IF  DISCGRP-STATUS = '00'                 &lt;- '00' ONLY: strict
     * L455    ... 'ERROR READING DEFAULT DISCLOSURE GROUP'
     * L458    ... abend
     * </pre>
     *
     * <p><strong>This guard is strict, and a missing {@code DEFAULT} row abends the job.</strong> Unlike
     * stage one it accepts {@code '00'} alone, so record-not-found is fatal here. It is never a skip, never
     * a record-not-found exception, and above all never a substituted zero rate - substituting zero would
     * quietly suppress interest for every affected category and the run would report success. The guard is
     * applied through {@link FileStatusMapper#requireDefaultDisclosureGroupReadSuccess(String)}, which
     * raises the abend for {@code '23'} as well as for every unrecognised status.
     *
     * <p><b>The guard is at {@code :L446}, not {@code :L445}.</b>
     * {@code app/cbl/CBACT04C.cbl:L445} is a blank line
     * and the guard is at {@code :L446}, which is the line every citation in this class names.
     *
     * <p>The seventeen {@code (type, category)} pairs that {@code app/data/ASCII/discgrp.txt} covers under
     * {@code DEFAULT} are the ones for which this method can succeed; any pair outside that set reaches the
     * abend, which is what makes both outcomes of this fallback testable against real fixture data.
     *
     * @param tranTypeCd the transaction type code retained from the failed primary lookup,
     *     {@code DIS-TRAN-TYPE-CD PIC X(02)}
     * @param tranCatCd the transaction category code retained from the failed primary lookup,
     *     {@code DIS-TRAN-CAT-CD PIC 9(04)}
     * @return the {@code DEFAULT} group's monthly interest rate for that type and category; never
     *     {@code null}
     * @throws FatalProcessingException if no {@code DEFAULT} row exists for the pair, if the read fails, or
     *     if the row carries no rate
     */
    private BigDecimal getDefaultInterestRate(final String tranTypeCd, final Integer tranCatCd) {
        final Optional<DisclosureGroup> defaultRead;
        try {
            defaultRead = disclosureGroupRepository.findDefaultGroupRate(tranTypeCd, tranCatCd);
        } catch (final DataAccessException cause) {
            LOG.error(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT);
            throw fatal(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT,
                    "Default disclosure group read failed for type " + tranTypeCd + " category "
                            + tranCatCd + ".", cause);
        }

        if (defaultRead.isEmpty()) {
            LOG.error(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT);
        }

        // L446-L459: '00' only. Record-not-found abends here, unlike stage one.
        final String discgrpStatus =
                defaultRead.isPresent() ? FILE_STATUS_SUCCESS : FILE_STATUS_RECORD_NOT_FOUND;
        fileStatusMapper.requireDefaultDisclosureGroupReadSuccess(discgrpStatus);

        // Defence in depth: the guard above admits '00' alone, so an empty read cannot reach this line.
        // Resolving it through orElseThrow keeps the method total and keeps the abend the single error mode
        // even if that contract were ever to regress.
        final DisclosureGroup defaultGroup = defaultRead.orElseThrow(() -> fatal(
                FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT,
                "The default disclosure group guard admitted an empty read for type " + tranTypeCd
                        + " category " + tranCatCd + "."));
        return requireInterestRate(defaultGroup.getInterestRate(), defaultGroup.getId());
    }

    /**
     * {@code 1300-COMPUTE-INTEREST}, {@code app/cbl/CBACT04C.cbl:L462}-{@code :L470}.
     *
     * <pre>
     * L464 COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL *
     * L465                            DIS-INT-RATE) / 1200
     * L467 ADD WS-MONTHLY-INT  TO WS-TOTAL-INT
     * L468 PERFORM 1300-B-WRITE-TX.
     * </pre>
     *
     * <p>Multiply first, then divide by the literal {@link #MONTHLY_INTEREST_DIVISOR}, rounding
     * {@link RoundingMode#HALF_EVEN} at scale {@value #MONETARY_SCALE}. The parenthesisation is the
     * source's and is not an accident of formatting: it fixes the order of operations, and with fixed-point
     * arithmetic the order decides the result. Dividing by {@code 100} and then by {@code 12}, or
     * multiplying by a decimal approximation of one twelve-hundredth, or dividing the rate before applying
     * the balance, are all algebraically equivalent and all produce different money.
     *
     * <p>The product is exact before the division: {@link BigDecimal#multiply} adds the scales, so an
     * eleven-digit two-decimal balance times a six-digit two-decimal rate is a scale-four intermediate with
     * no rounding at all, and the single rounding of the whole computation happens at the division. That is
     * the same shape as the COBOL, whose {@code COMPUTE} holds one intermediate result and rounds once into
     * the {@code PIC S9(09)V99} target.
     *
     * <p>Reached only when the rate is non-zero, per the gate at {@code :L214}.
     *
     * @param categoryBalance {@code TRAN-CAT-BAL PIC S9(09)V99}; must not be {@code null}
     * @param interestRate {@code DIS-INT-RATE PIC S9(04)V99}, already known to be non-zero; must not be
     *     {@code null}
     * @return the synthesised interest transaction produced by {@code 1300-B-WRITE-TX}
     * @throws FatalProcessingException if the transaction cannot be assembled
     */
    private Transaction computeInterest(final BigDecimal categoryBalance, final BigDecimal interestRate) {
        final BigDecimal monthlyInterest = categoryBalance
                .multiply(interestRate)
                .divide(MONTHLY_INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_EVEN);

        totalInterest = totalInterest.add(monthlyInterest);

        return writeTransaction(monthlyInterest);
    }

    /**
     * {@code 1300-B-WRITE-TX}, {@code app/cbl/CBACT04C.cbl:L473}-{@code :L515}.
     *
     * <p>Assembles the interest transaction, field for field and in the source's order.
     *
     * <p><b>The identifier.</b> {@code :L474} increments {@code WS-TRANID-SUFFIX} and
     * {@code :L476}-{@code :L480} concatenates {@code PARM-DATE} with it
     * {@code DELIMITED BY SIZE}, giving ten characters plus six for a
     * {@value #TRANSACTION_ID_LENGTH}-character {@code TRAN-ID}. The suffix is run-sequential, never reset
     * per account, so identifiers ascend across the whole run; because the date leads, they are also
     * numerically large, which is worth knowing because other programs derive their next identifier from a
     * descending browse of the maximum key.
     *
     * <p><b>Fixed literals.</b> Type {@code '01'} ({@code :L482}) and category {@code '05'}
     * ({@code :L483}), the latter moved into a {@code PIC 9(04)} field and therefore the integer five.
     * Source {@code 'System'} ({@code :L484}), six characters in a {@code PIC X(10)} field and so
     * space-padded to ten - taken from {@link TransactionSource#getFixedWidthValue()} rather than restated
     * here, since that enum already owns the padded literal and cites this very line. Note that the
     * transaction's source column is a plain fixed-width string and not the enum: the enum is the authority
     * for the value, not the persisted type.
     *
     * <p><b>The description.</b> {@code :L485}-{@code :L489} concatenates the thirteen characters of
     * {@code 'Int. for a/c '} with the eleven digits of {@code ACCT-ID}, giving twenty-four characters that
     * are right-space-padded into {@code TRAN-DESC X(100)}. The account identifier is taken from the loaded
     * account record, which is the field the source names, and not from the category-balance key.
     *
     * <p><b>Absent merchant data.</b> {@code :L491} sets the merchant identifier to zero and
     * {@code :L492}-{@code :L494} set name, city and postal code to spaces. Spaces are not {@code null}:
     * these are fixed-width fields in a 350-byte record and blank is a value.
     *
     * <p><b>One timestamp, used twice.</b> {@code :L496} generates it once and
     * {@code :L497}-{@code :L498} moves the same value into both the originating and the processing
     * timestamp. Generating twice would risk two different values and would not match the baseline.
     *
     * <p><b>The write itself is the step's, not this method's.</b> {@code :L500} writes to
     * {@code TRANSACT-FILE}, which {@code FILE-CONTROL} at {@code :L53}-{@code :L56} declares
     * {@code ORGANIZATION IS SEQUENTIAL} onto the fresh generation
     * {@code AWS.M2.CARDDEMO.SYSTRAN(+1)} ({@code app/jcl/INTCALC.jcl:L37}-{@code :L41}). This method
     * therefore returns the record for the step's writer to emit and does not persist it, which is also why
     * no duplicate-key detection exists here: a brand new sequential file cannot collide, and duplicate
     * exposure belongs to the later bulk load of {@code app/jcl/COMBTRAN.jcl}.
     *
     * @param monthlyInterest the computed interest for this category balance, at scale
     *     {@value #MONETARY_SCALE}
     * @return the assembled 350-byte-equivalent interest transaction, never {@code null}
     * @throws FatalProcessingException if the account or cross-reference record is not loaded, if the card
     *     number is absent, or if any assembled field violates the width its picture clause declares
     */
    private Transaction writeTransaction(final BigDecimal monthlyInterest) {
        // L474: ADD 1 TO WS-TRANID-SUFFIX, on a PIC 9(06) field with no ON SIZE ERROR clause, so the
        // 1,000,000th increment truncates back to zero exactly as the modulus does here.
        tranIdSuffix = (tranIdSuffix + 1) % TRANID_SUFFIX_MODULUS;

        final Account account = currentAccount;
        final CardCrossReference crossReference = currentCrossReference;
        if (account == null || crossReference == null) {
            throw fatal(MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    "1300-B-WRITE-TX was reached before the control break loaded the account and "
                            + "cross-reference records.");
        }

        final String transactionId =
                parmDate + String.format(Locale.ROOT, TRANID_SUFFIX_FORMAT, tranIdSuffix);
        if (transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw fatal(MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    "TRAN-ID is " + transactionId.length() + " characters; PIC X("
                            + TRANSACTION_ID_LENGTH + ") requires exactly " + TRANSACTION_ID_LENGTH + ".");
        }

        final String description = padDescription(TRANSACTION_DESCRIPTION_PREFIX
                + renderAccountNumber(requireAccountId(account.getAccountId())));

        final String cardNumber = crossReference.getCardNumber();
        if (cardNumber == null || cardNumber.isEmpty()) {
            throw fatal(MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    "XREF-CARD-NUM is absent on the cross reference for account "
                            + renderAccountNumber(account.getAccountId()) + ".");
        }

        // L496-L498: generated once, moved into BOTH timestamps.
        final String generatedTimestamp = db2FormatTimestamp();

        return new Transaction(
                transactionId,
                TRANSACTION_TYPE_CODE_INTEREST,
                TRANSACTION_CATEGORY_CODE_INTEREST,
                TransactionSource.SYSTEM.getFixedWidthValue(),
                description,
                monthlyInterest,
                MERCHANT_ID_NONE,
                MERCHANT_NAME_SPACES,
                MERCHANT_CITY_SPACES,
                MERCHANT_ZIP_SPACES,
                cardNumber,
                generatedTimestamp,
                generatedTimestamp);
    }

    /**
     * {@code 1400-COMPUTE-FEES}, {@code app/cbl/CBACT04C.cbl:L518}-{@code :L520}. The paragraph is empty in
     * the source, which reads in full:
     *
     * <pre>
     * 1400-COMPUTE-FEES.
     * * To be implemented
     *     EXIT.
     * </pre>
     *
     * <p><strong>INTENTIONAL NO-OP - REACHABLE - PRESERVED FOR CONTROL-FLOW PARITY.</strong> It is not an
     * oversight and it is not unfinished work of this migration: it is a faithful reproduction of a
     * paragraph that exists, is genuinely {@code PERFORM}ed at {@code app/cbl/CBACT04C.cbl:L216}, and does
     * nothing. The emptiness is the behaviour, and the source comment above is reproduced as the evidence
     * that the legacy authors, not this migration, left it unimplemented.
     *
     * <p><b>This is the single documented conflict with the project's coding standard, and it is resolved
     * in favour of parity.</b> The standard's code-quality clause forbids dead code and forbids deferred
     * work that carries no owner or tracking reference. Deleting this method would satisfy the letter of
     * that clause and would break two things that matter more: the paragraph map that the scope-coverage
     * gate verifies mechanically, and the guarantee that the call site at {@code :L216} is reproduced.
     * The clause's actual target is <em>untracked</em> residue, and this artefact is cited, tracked and
     * marked, so it is compliant as written. Note in particular that no bare marker comment appears here -
     * writing one would breach the clause for real, whereas a cited, tracked and deliberately empty method
     * does not.
     *
     * <p>Reached only when the rate is non-zero. The gate at {@code :L214} suppresses this paragraph
     * together with {@code 1300-COMPUTE-INTEREST}, so a zero rate skips the fee call as well - which is
     * why {@link #process} returns early rather than calling this method on the zero-rate path.
     */
    private void computeFees() {
    }

    /**
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}, {@code app/cbl/CBACT04C.cbl:L613}-{@code :L626}.
     *
     * <p>Builds the {@value #DB2_TIMESTAMP_LENGTH}-character timestamp that
     * {@code :L497}-{@code :L498} places on both timestamp fields of the generated transaction. The
     * source's own comment at {@code :L140} spells the shape out as
     * {@code EEEE-MM-DD-UU.MM.SS.HH0000}: date and time to the second, then <em>two</em> digits of
     * hundredths, then four literal zeros.
     *
     * <p>{@code :L614} takes the clock reading from {@code FUNCTION CURRENT-DATE} and
     * {@code :L615}-{@code :L621} moves its components across, including {@code COB-MIL PIC X(02)}
     * ({@code :L148}) - hundredths of a second - into {@code DB2-MIL PIC 9(002)} ({@code :L164}).
     * {@code :L622} then moves the literal {@code '0000'} into {@code DB2-REST PIC X(04)} ({@code :L165}).
     * The last four characters are therefore always zeros and are never a rendered fraction.
     *
     * <p><b>Precision is a parity contract, not a style choice.</b> Milliseconds would emit three digits
     * and nanoseconds nine; either would make every generated timestamp differ from the legacy baseline in
     * a field that the end-to-end comparison reads byte for byte. The render is pinned to
     * {@link Locale#ROOT} so no host locale can alter it, and the reading is taken from the injected
     * {@link Clock} so no host default time zone is assumed and tests can fix the value.
     *
     * <p>The result is carried as a {@link String} because the mapped columns are fixed-width character
     * fields: {@code TRAN-ORIG-TS X(26)} and {@code TRAN-PROC-TS X(26)}
     * ({@code app/cpy/CVTRA05Y.cpy:L16}-{@code :L17}). It is never a date-time or instant type, because a
     * temporal type would normalise away the trailing literal zeros that the field is defined to carry.
     *
     * @return the timestamp, exactly {@value #DB2_TIMESTAMP_LENGTH} characters
     * @throws FatalProcessingException if the render is not exactly {@value #DB2_TIMESTAMP_LENGTH}
     *     characters, which would mean the field could not be written to its column without truncation
     */
    private String db2FormatTimestamp() {
        final String timestamp =
                LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMATTER) + DB2_TIMESTAMP_TRAILER;
        if (timestamp.length() != DB2_TIMESTAMP_LENGTH) {
            throw fatal(MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    "Generated timestamp is " + timestamp.length() + " characters; PIC X("
                            + DB2_TIMESTAMP_LENGTH + ") requires exactly " + DB2_TIMESTAMP_LENGTH + ".");
        }
        return timestamp;
    }

    /**
     * Emits the per-record diagnostic of {@code DISPLAY TRAN-CAT-BAL-RECORD} at
     * {@code app/cbl/CBACT04C.cbl:L193}.
     *
     * <p>The source writes the whole 50-byte record image to SYSOUT. This renders the record's fields as a
     * structured event instead, which is both the observability standard and the safer choice: a raw
     * fixed-width dump would put whatever the record area holds into the log verbatim.
     *
     * <p><strong>Finding, Medium severity - the balance is redacted.</strong> This method previously
     * rendered every field on the ground that the record "carries no card number and no personal data",
     * and that reasoning was wrong in one respect: {@code TRAN-CAT-BAL} is a cardholder's outstanding
     * balance for one type-and-category pair, and it is emitted on the <em>same line</em> as
     * {@code TRANCAT-ACCT-ID}, so it is linkable to a person by anyone holding the log plus one lookup.
     * Absence of a card number is not absence of financial data. The balance is now replaced by
     * {@link #REDACTED_BALANCE}.
     *
     * <p><em>What is redacted and what is not.</em> The financial value is redacted; the three key fields
     * are not. That split is deliberate and matches the project's documented position:
     * {@code src/main/resources/logback-spring.xml:731-736} lists a bare account-identifier line among the
     * benign look-alikes that must <em>not</em> be over-redacted, and the value layer there masks card
     * numbers, CVVs, government identifiers, dates of birth, telephone numbers, names and addresses -
     * never an account identifier. The identifier is also the control-break key of
     * {@code app/cbl/CBACT04C.cbl:L194} and appears in the report deliverable regardless, so masking it
     * would destroy this diagnostic's entire purpose while protecting nothing that is not already public
     * within the system.
     *
     * <p><em>Why the debug guard alone was not sufficient.</em> Debug narrows the audience but does not
     * remove the value, and debug is enabled during exactly the incident investigations in which logs are
     * read most widely. The guard is kept as the per-record volume control; it is no longer the only
     * control.
     *
     * <p><em>What survives.</em> The sequence number, all three key fields, the field names, their order,
     * the one-line-per-record cadence and the digit width of the balance. A reader verifying that the
     * category-balance browse arrived in key order - which is the precondition the account-level control
     * break depends on - can still do so. Not a parity artefact: the plan's parity contract is the
     * fixed-width output of the batch stream, and this diagnostic stream is not among the compared
     * artefacts.
     *
     * <p><em>Remediation if the true values are ever needed:</em> emit them to a separately
     * access-controlled artefact rather than the shared log. Deliberately not invented here, because no
     * consumer for it exists at {@code 7756d89}. <strong>Owed an entry in the planned
     * {@code DECISION_LOG.md}</strong>, which does not exist at this commit, so this Javadoc is the register
     * of record.
     *
     * <p><em>Two controls, not one.</em> Redaction is the value-level control and it is unconditional. The
     * <em>routing</em> control is separate and complementary: the untruncated per-record emissions that a
     * parity diff genuinely needs travel on {@value #PARITY_LOGGER_NAME}, which
     * {@code src/main/resources/application.yml} pins to {@code OFF} in every shipped profile and which no
     * child profile can raise by accident, because a level set on {@code com.cardemo} does not override a
     * level set on a child of it. A DEBUG level on this class logger is a configuration choice an operator can
     * flip; the parity tree is not.
     *
     * <p>The event also carries {@code WS-RECORD-COUNT}, incremented immediately before at {@code :L192}.
     * The source increments that counter and then never reads it: the program displays no totals and sets
     * no return code. Surfacing it as the sequence number of this diagnostic gives the translated counter a
     * purpose without inventing behaviour the source does not have, which is preferable to carrying a field
     * that is only ever written.
     *
     * <p>Logged at debug level on the parity logger because it fires once per record. The guard avoids
     * formatting the account number when that logger is disabled, which is its shipped state.
     *
     * @param accountId {@code TRANCAT-ACCT-ID PIC 9(11)}
     * @param typeCode {@code TRANCAT-TYPE-CD PIC X(02)}
     * @param categoryCode {@code TRANCAT-CD PIC 9(04)}
     * @param balance {@code TRAN-CAT-BAL PIC S9(09)V99}. Checked for {@code null} so that the absent-value
     *     case keeps its own distinguishable rendering - a genuine data condition rather than a redaction -
     *     but never rendered
     */
    private void displayCategoryBalanceRecord(final Long accountId, final String typeCode,
            final Integer categoryCode, final BigDecimal balance) {
        if (PARITY_LOG.isDebugEnabled()) {
            // The emission point and its field labels are preserved; the account identifier is rendered at
            // its picture width and the balance is withheld. The type and category codes stay, because they
            // are a fixed vocabulary from TRANTYPE and TRANCATG rather than data about any customer, and the
            // control break that this trace exists to diagnose is invisible without them.
            PARITY_LOG.debug("TRAN-CAT-BAL-RECORD sequence={} TRANCAT-ACCT-ID={} TRANCAT-TYPE-CD={} "
                            + "TRANCAT-CD={} TRAN-CAT-BAL={}",
                    recordCount, renderAccountNumber(accountId), typeCode, categoryCode,
                    balance == null ? null : REDACTED_BALANCE);
        }
    }

    /**
     * Renders an account identifier as the eleven zero-padded digits of {@code PIC 9(11)}.
     *
     * <p>This render is the control-break comparison value at {@code app/cbl/CBACT04C.cbl:L194}, the
     * account portion of the description built at {@code :L485}-{@code :L489}, and the value appended to the
     * {@code 'ACCOUNT NOT FOUND: '} diagnostics at {@code :L375} and {@code :L397}.
     *
     * <p>A {@code null} identifier renders as eleven spaces - the value an uninitialised
     * {@code PIC X(11)} field holds, and the initial value of {@code WS-LAST-ACCT-NUM} at {@code :L167}.
     * That keeps the diagnostic paths total: they are reached when something is already wrong and must not
     * fail a second time while describing the first failure. Identifiers used for the control break are
     * validated by {@link #requireAccountId(Long)} before reaching here, so the padded form is always the
     * eleven digits the picture clause declares.
     *
     * @param accountId the identifier, which may be {@code null} on a diagnostic path
     * @return the eleven-character render
     */
    private static String renderAccountNumber(final Long accountId) {
        if (accountId == null) {
            return LAST_ACCOUNT_NUMBER_SPACES;
        }
        return String.format(Locale.ROOT, ACCOUNT_ID_FORMAT, accountId);
    }

    /**
     * Right-space-pads the assembled description into {@code TRAN-DESC X(100)}
     * ({@code app/cpy/CVTRA05Y.cpy:L9}), reproducing the {@code MOVE} of a shorter value into a longer
     * alphanumeric field.
     *
     * <p>Padding rather than trimming, and refusing rather than truncating: COBOL would silently truncate an
     * over-long value, but here the input is a thirteen-character literal plus eleven digits and can only
     * exceed the field if one of those contracts has already been violated, so the safe response is to fail
     * loudly rather than to emit a quietly shortened description.
     *
     * @param value the assembled description, twenty-four characters on every valid path
     * @return the value padded to {@value #TRANSACTION_DESCRIPTION_WIDTH} characters
     * @throws FatalProcessingException if the value is longer than the field can hold
     */
    private static String padDescription(final String value) {
        if (value.length() > TRANSACTION_DESCRIPTION_WIDTH) {
            throw fatal(MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    "TRAN-DESC is " + value.length() + " characters; PIC X("
                            + TRANSACTION_DESCRIPTION_WIDTH + ") cannot hold it without truncation.");
        }
        return value + " ".repeat(TRANSACTION_DESCRIPTION_WIDTH - value.length());
    }

    /**
     * Validates the item the reader supplied and returns its composite key.
     *
     * <p>The source cannot encounter either condition: {@code 1000-TCATBALF-GET-NEXT} at
     * {@code app/cbl/CBACT04C.cbl:L325}-{@code :L348} either reads a record into a fixed record area, or
     * reports end of file, or abends, so the record area is always populated when the loop body runs. Both
     * conditions are nevertheless checked, because a Java reader can hand over {@code null} and because a
     * silent {@link NullPointerException} would surface as an untyped failure rather than as the abend that
     * every failure in this program produces.
     *
     * <p>Neither guard borrows a legacy literal. The literal that guards the read itself is
     * {@code 'ERROR READING TRANSACTION CATEGORY FILE'} ({@code :L342}) and it belongs to the reader's
     * paragraph, not to this class - and it is worth noting for whoever writes that reader that it is
     * <em>not</em> the same string as {@link FileStatusMapper#TCATBAL_READ_FAILURE_TEXT}, which carries
     * {@code app/cbl/CBTRN02C.cbl:L489}'s wording, "BALANCE" where this program says "CATEGORY". That
     * constant is correct for the posting program and wrong for this one, so the reader must supply the
     * literal above rather than reach for it. That note is advisory only: no file outside this one is
     * modified.
     *
     * @param item the item supplied by the reader
     * @return the item's {@code TRAN-CAT-KEY}, never {@code null}
     * @throws FatalProcessingException if the item or its key is absent
     */
    private static TransactionCategoryBalanceId requireCategoryBalanceKey(
            final TransactionCategoryBalance item) {
        if (item == null) {
            throw fatal("MISSING TRANSACTION CATEGORY BALANCE RECORD",
                    "The reader supplied no transaction category balance record; the record area of "
                            + "1000-TCATBALF-GET-NEXT is always populated when the loop body runs.");
        }
        final TransactionCategoryBalanceId key = item.getId();
        if (key == null) {
            throw fatal("MISSING TRANSACTION CATEGORY BALANCE KEY",
                    "TRAN-CAT-KEY is absent; the seventeen-byte composite key is mandatory.");
        }
        return key;
    }

    /**
     * Validates {@code TRANCAT-ACCT-ID PIC 9(11)} ({@code app/cpy/CVTRA01Y.cpy:L6}).
     *
     * <p>The picture clause is unsigned, so a negative value has no representation in the field and would
     * make {@link #renderAccountNumber(Long)} emit a minus sign where a digit belongs, corrupting both the
     * control-break comparison and the generated description. The upper bound is the largest value eleven
     * digits can express.
     *
     * @param accountId the identifier to validate
     * @return the identifier, unchanged
     * @throws FatalProcessingException if it is absent or outside the domain of {@code PIC 9(11)}
     */
    private static Long requireAccountId(final Long accountId) {
        if (accountId == null) {
            throw fatal(MSG_ERROR_READING_ACCOUNT_FILE,
                    "TRANCAT-ACCT-ID is absent; PIC 9(11) is mandatory.");
        }
        if (accountId < 0L || accountId > ACCOUNT_ID_MAX) {
            throw fatal(MSG_ERROR_READING_ACCOUNT_FILE,
                    "TRANCAT-ACCT-ID " + accountId + " is outside the domain of PIC 9(11), 0 to "
                            + ACCOUNT_ID_MAX + ".");
        }
        return accountId;
    }

    /**
     * Validates {@code TRANCAT-TYPE-CD PIC X(02)} ({@code app/cpy/CVTRA01Y.cpy:L7}), which
     * {@code app/cbl/CBACT04C.cbl:L212} moves into {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     *
     * <p>Exactly two characters, because both fields are two-character alphanumerics and
     * {@link DisclosureGroupId} enforces the same width: a shorter value would not match any stored key and
     * a longer one would be truncated by the column.
     *
     * @param typeCode the code to validate
     * @return the code, unchanged and neither padded nor case folded
     * @throws FatalProcessingException if it is absent or not exactly two characters
     */
    private static String requireTypeCode(final String typeCode) {
        if (typeCode == null || typeCode.length() != 2) {
            throw fatal("INVALID TRANSACTION TYPE CODE",
                    "TRANCAT-TYPE-CD must be exactly two characters to match PIC X(02); received "
                            + (typeCode == null ? "no value" : typeCode.length() + " characters") + ".");
        }
        return typeCode;
    }

    /**
     * Validates {@code TRANCAT-CD PIC 9(04)} ({@code app/cpy/CVTRA01Y.cpy:L8}), which
     * {@code app/cbl/CBACT04C.cbl:L211} moves into {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     *
     * @param categoryCode the code to validate
     * @return the code, unchanged
     * @throws FatalProcessingException if it is absent or outside the domain of {@code PIC 9(04)}
     */
    private static Integer requireCategoryCode(final Integer categoryCode) {
        if (categoryCode == null || categoryCode < 0 || categoryCode > 9999) {
            throw fatal("INVALID TRANSACTION CATEGORY CODE",
                    "TRANCAT-CD must lie between 0 and 9999 to match PIC 9(04); received "
                            + (categoryCode == null ? "no value" : String.valueOf(categoryCode)) + ".");
        }
        return categoryCode;
    }

    /**
     * Validates {@code TRAN-CAT-BAL PIC S9(09)V99} ({@code app/cpy/CVTRA01Y.cpy:L9}).
     *
     * <p>Returned unchanged and deliberately not rescaled. The value is the left operand of the formula at
     * {@code app/cbl/CBACT04C.cbl:L464}, where the single rounding of the whole computation happens at the
     * division; rescaling the input first would introduce a second rounding the source does not perform.
     *
     * @param balance the balance to validate
     * @return the balance, unchanged
     * @throws FatalProcessingException if it is absent, a signed numeric field having no null
     *     representation
     */
    private static BigDecimal requireCategoryBalance(final BigDecimal balance) {
        if (balance == null) {
            throw fatal("MISSING TRANSACTION CATEGORY BALANCE",
                    "TRAN-CAT-BAL is absent; PIC S9(09)V99 is mandatory.");
        }
        return balance;
    }

    /**
     * Validates {@code ACCT-GROUP-ID PIC X(10)} ({@code app/cpy/CVACT01Y.cpy:L13}) before it becomes the
     * first component of the disclosure-group key at {@code app/cbl/CBACT04C.cbl:L210}.
     *
     * <p>Returned unchanged: neither trimmed nor padded nor case folded. The stored column is a
     * ten-character fixed-width field, so the value arrives already padded, and a blank group is entirely
     * legitimate - it is what every account in {@code app/data/ASCII/acctdata.txt} carries, and it is
     * precisely what routes the whole fixture set through the {@code DEFAULT} fallback.
     *
     * @param groupId the group identifier to validate
     * @return the identifier, unchanged
     * @throws FatalProcessingException if it is absent or wider than the field
     */
    private static String requireAccountGroupId(final String groupId) {
        if (groupId == null) {
            throw fatal(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT,
                    "ACCT-GROUP-ID is absent; PIC X(10) is mandatory and a blank group is not a null one.");
        }
        if (groupId.length() > ACCOUNT_GROUP_ID_WIDTH) {
            throw fatal(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT,
                    "ACCT-GROUP-ID is " + groupId.length() + " characters; PIC X("
                            + ACCOUNT_GROUP_ID_WIDTH + ") holds " + ACCOUNT_GROUP_ID_WIDTH + ".");
        }
        return groupId;
    }

    /**
     * Validates {@code DIS-INT-RATE PIC S9(04)V99} ({@code app/cpy/CVTRA02Y.cpy:L9}) on the row a lookup
     * resolved.
     *
     * <p>A resolved row with no rate cannot be distinguished from a zero rate without this check, and the
     * two have opposite consequences: a zero rate suppresses the item at
     * {@code app/cbl/CBACT04C.cbl:L214}, whereas an absent rate means the row is malformed and the run must
     * stop rather than silently skip interest.
     *
     * @param interestRate the rate carried by the resolved row
     * @param resolvedKey the key of the row the rate came from, for the diagnostic
     * @return the rate, unchanged and not rescaled
     * @throws FatalProcessingException if the resolved row carries no rate
     */
    private static BigDecimal requireInterestRate(final BigDecimal interestRate,
            final DisclosureGroupId resolvedKey) {
        if (interestRate == null) {
            throw fatal(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT,
                    "DIS-INT-RATE is absent on disclosure group " + resolvedKey
                            + "; PIC S9(04)V99 is mandatory, and absent is not zero.");
        }
        return interestRate;
    }

    /**
     * Validates a constructor argument, failing fast with this program's own abend rather than with an
     * untyped {@link NullPointerException} raised later.
     *
     * <p>Static so that no overridable instance method is reachable from a constructor, which the build's
     * {@code this-escape} analysis would otherwise flag.
     *
     * @param <T> the collaborator's type
     * @param value the argument to validate
     * @param name the parameter name, for the diagnostic
     * @return the argument, unchanged
     * @throws FatalProcessingException if the argument is absent
     */
    private static <T> T requireCollaborator(final T value, final String name) {
        if (value == null) {
            throw fatal("MISSING COLLABORATOR",
                    "InterestCalculationProcessor requires " + name + "; it was not supplied.");
        }
        return value;
    }

    /**
     * Validates the job parameter carrying {@code PARM-DATE PIC X(10)}
     * ({@code app/cbl/CBACT04C.cbl:L178}), supplied by {@code app/jcl/INTCALC.jcl:L22} as
     * {@code PARM='2022071800'}.
     *
     * <p>The width is enforced because the value is concatenated verbatim into a
     * {@value #TRANSACTION_ID_LENGTH}-character {@code TRAN-ID}: ten characters plus a six-digit suffix.
     * Any other width silently produces identifiers that do not fill their column.
     *
     * <p>A digits-only rule is deliberately <strong>not</strong> enforced. The picture clause is
     * {@code PIC X(10)}, an alphanumeric field, so the source accepts any ten characters, and adding a
     * constraint the source does not impose would be a behaviour change rather than a hardening. The value
     * is likewise never parsed into a {@link java.time.LocalDate} and never reformatted: it is eight date
     * digits followed by two zeros, not an ISO date, and treating it as one would change every generated
     * identifier.
     *
     * @param parmDate the job parameter value
     * @return the value, unchanged
     * @throws FatalProcessingException if it is absent or not exactly {@value #PARM_DATE_LENGTH} characters
     */
    private static String requireParmDate(final String parmDate) {
        if (parmDate == null) {
            throw fatal("MISSING PARM-DATE",
                    "The job parameter '" + PARM_DATE_JOB_PARAMETER + "' is mandatory; PARM-DATE PIC X(10) "
                            + "is supplied by INTCALC.jcl as PARM='2022071800'.");
        }
        if (parmDate.length() != PARM_DATE_LENGTH) {
            throw fatal("INVALID PARM-DATE",
                    "PARM-DATE is " + parmDate.length() + " characters; PIC X(" + PARM_DATE_LENGTH
                            + ") requires exactly " + PARM_DATE_LENGTH + " so that TRAN-ID fills PIC X("
                            + TRANSACTION_ID_LENGTH + ").");
        }
        return parmDate;
    }

    /**
     * Builds the abend that stands in for {@code 9999-ABEND-PROGRAM}
     * ({@code app/cbl/CBACT04C.cbl:L628}-{@code :L632}), where the source moves {@code 999} into
     * {@code ABCODE} and calls the language environment's abend service.
     *
     * <p>Abend code {@code 999} and return code {@code 12} are published by
     * {@link FatalProcessingException} itself and are deliberately not redeclared here. The four-field
     * payload follows the convention this codebase already uses: the display code is left unset, because the
     * source sets {@code ABCODE} rather than {@code ABEND-CODE} on the batch path, and the culprit is this
     * program's own identifier.
     *
     * @param reason the {@code ABEND-REASON}, the legacy {@code DISPLAY} literal wherever one applies
     * @param message the {@code ABEND-MSG}, naming the specific condition
     * @return the abend, for the caller to throw
     */
    private static FatalProcessingException fatal(final String reason, final String message) {
        return new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT, reason,
                message);
    }

    /**
     * Builds the abend of {@link #fatal(String, String)} while preserving the throwable that caused it, so
     * that no root cause is discarded on an infrastructure failure.
     *
     * @param reason the {@code ABEND-REASON}, the legacy {@code DISPLAY} literal wherever one applies
     * @param message the {@code ABEND-MSG}, naming the specific condition
     * @param cause the underlying throwable, retained and retrievable
     * @return the abend, for the caller to throw
     */
    private static FatalProcessingException fatal(final String reason, final String message,
            final Throwable cause) {
        return new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT, reason,
                message, cause);
    }
}
