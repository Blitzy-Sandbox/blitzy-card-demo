/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch.processors
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Spring Batch item-processor layer)
 * Function    : The per-record bodies of the batch programs. This is
 *               where the business rules of the batch corpus live, one
 *               private method per COBOL paragraph, and where the
 *               preserved legacy quirks are reproduced rather than
 *               repaired: the unguarded 102/103 fall-through, the
 *               control break labelled "Account Total" that breaks on
 *               the card number, the end-of-data double count, and the
 *               empty but reachable fee paragraph.
 * Source      : app/cbl/CBTRN02C.cbl:L370-L422 (1500-VALIDATE-TRAN and
 *               its two lookup paragraphs; codes 100, 101, 102, 103)
 *               @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L424-L465 (2000-POST-TRANSACTION -
 *               three independent commits in the source) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L467-L500 (2700-UPDATE-TCATBAL -
 *               '00' OR '23' both accepted) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L545-L560 (2800-UPDATE-ACCOUNT-REC
 *               - code 109, and the sign branch that must not be
 *               normalised) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L462-L470 (balance * rate / 1200)
 *               @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L518-L520 (1400-COMPUTE-FEES -
 *               empty, and performed at :L216) @ 7756d89
 * Source      : app/cbl/CBTRN03C.cbl:L127-L137 (WS-PAGE-SIZE 20),
 *               :L181-L188 (the control break) @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L296-L314, :L726-L728, :L815
 *               (the ALTER dispatch, resolved to sequential setup)
 *               @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L225-L233 (the 510-entry ceiling
 *               with no bounds check) @ 7756d89
 * Source      : app/jcl/COMBTRAN.jcl:STEP05R (SORT over a concatenated
 *               input; NO COBOL PROGRAM EXISTS for this job) @ 7756d89
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

/**
 * The per-record logic of the batch stream: five {@code ItemProcessor} implementations carrying the business
 * rules of the batch corpus, one private method per COBOL paragraph.
 *
 * <p>This is the package where parity is hardest and where it matters most, because almost every rule here has
 * a plausible-looking Java translation that behaves differently. Four legacy quirks are
 * <strong>reproduced rather than repaired</strong>, and one legacy hazard is
 * <strong>removed and labelled as a deviation</strong> rather than absorbed silently. Both kinds are set out
 * below, because a reader who does not know which is which will "fix" the first kind.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.batch.processors.TransactionPostingProcessor} - the validation cascade and posting
 *       body of {@code app/cbl/CBTRN02C.cbl}.</li>
 *   <li>{@link com.cardemo.batch.processors.InterestCalculationProcessor} - the interest computation,
 *       default-group fallback and synthetic transaction construction of {@code app/cbl/CBACT04C.cbl}.</li>
 *   <li>{@link com.cardemo.batch.processors.TransactionReportProcessor} - the page and control-break logic of
 *       {@code app/cbl/CBTRN03C.cbl}, twenty lines to a page.</li>
 *   <li>{@link com.cardemo.batch.processors.StatementProcessor} - the per-account aggregation and dual-format
 *       emission of {@code app/cbl/CBSTM03A.CBL}.</li>
 *   <li>{@link com.cardemo.batch.processors.TransactionCombineProcessor} - the merge and ordering semantics of
 *       {@code app/jcl/COMBTRAN.jcl:STEP05R}, for which <strong>no COBOL program exists at all</strong>: the
 *       JCL sort and {@code REPRO} cards are the source of truth.</li>
 *   </ul>
 *
 * <h3>Preserved quirks - do not repair these</h3>
 *
 * <p><strong>Reject code 103 overwrites 102, and only one reject record is written.</strong>
 * {@code app/cbl/CBTRN02C.cbl:L393-L422} computes a temporary balance as the current-cycle credit
 * <em>minus</em> the current-cycle debit <em>plus</em> the transaction amount and assigns code 102 when the
 * credit limit is below it; it then <strong>immediately</strong>, with no alternative branch and no early exit,
 * compares the account expiry against the transaction's <em>originating</em> timestamp and assigns 103. When
 * both conditions fail, 103 replaces 102. Guarding the second check, or emitting two reject records, diverges.
 * The over-limit expression must also be transcribed exactly: the debit accumulator legitimately holds
 * negative values, which is precisely why subtracting it is correct.
 *
 * <p><strong>The report control break fires on the card number under a label reading "Account Total".</strong>
 * {@code app/cbl/CBTRN03C.cbl:L181-L188} breaks on {@code TRAN-CARD-NUM}; the emitted line says
 * {@code Account Total}. The label is wrong in the source and is reproduced wrong.
 *
 * <p><strong>The end-of-data double count.</strong> COBOL's failing {@code READ} leaves the previous record in
 * the buffer, so the closing {@code ADD} at {@code app/cbl/CBTRN03C.cbl:L200-L201} adds the last record's
 * amount a <em>second</em> time, inflating the closing page total and the grand total; and because no final
 * account-total block is performed, the last card group never gets an {@code Account Total} line at all. Both
 * are reproduced. Neither the addition is deduplicated nor a flush added.
 *
 * <p><strong>The empty fee paragraph is retained.</strong> {@code app/cbl/CBACT04C.cbl:L518-L520} is a comment
 * and an exit, and it is <strong>genuinely reachable</strong> - performed at {@code :L216}. It is kept as an
 * empty private method with an explicit intentional-no-op marker, its locator, its reachability proof and an
 * acknowledgement that it is owed an entry in the {@code DECISION_LOG.md}. This is the single place in
 * the tree where Rule 1 Clause B's prohibition on dead code yields to the parity mandate, on the reading that
 * the clause forbids <em>untracked</em> dead code; deleting the call site would break the paragraph map the
 * scope-coverage gate verifies.
 *
 * <p><strong>Reject code 109 is assigned on a path that can never write a reject record.</strong> It is set in
 * {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}, which runs only after validation
 * has already passed, so no reject is written, the count does not move, and the value is cleared on the next
 * iteration. It nevertheless exists as one of exactly five constants, because the assignment is real code on a
 * reachable path.
 *
 * <h3>Labelled deviations - these are not parity</h3>
 *
 * <p><strong>Three commits become one transaction.</strong>
 * {@code app/cbl/CBTRN02C.cbl:L424-L465} commits the category-balance upsert, the account update and the
 * transaction insert independently, so the code-109 rewrite-failure path leaves an orphaned category-balance
 * row and an orphaned transaction row behind. One atomic Java unit closes that hazard as a side effect. That
 * is a genuine behavioural improvement and is therefore <strong>labelled</strong>, not presented as
 * equivalence.
 *
 * <p><strong>The statement table's hard ceiling is removed.</strong>
 * {@code app/cbl/CBSTM03A.CBL:L225-L233} declares 51 card entries of 10 transactions - 510 - and the building
 * loop increments both indices with <strong>no bounds check whatsoever</strong>, so a 511th transaction overran
 * storage silently. Java uses unbounded collections, which removes the corruption. <strong>No authored ceiling
 * takes its place.</strong> {@code StatementProcessor} enforced two - one per run, one per card group - and
 * finding BAT-002 removed both: neither has a source, and a refusal at an invented threshold reproduces
 * neither the legacy number nor the legacy behaviour. What bounds the resident set is the control break itself:
 * the processor advances one card group at a time, so one card's transactions are held rather than the run's,
 * and a run that crosses the legacy per-card capacity logs one WARN naming {@code :L228} instead of failing.
 *
 * <p><strong>The self-modifying dispatch is gone, and its order is not.</strong>
 * {@code app/cbl/CBSTM03A.CBL:L296-L314} rewrites the target of an unconditional branch at
 * {@code :L726-L728} before taking it, but every state transition is hard-coded in a handler's tail, so the
 * machine is deterministic and collapses to five ordered initialisation calls followed by the mainline at
 * {@code :L815}. The keyed handler map belongs at the file-access layer, where the subprogram's four-file by
 * six-operation matrix genuinely varies, and not here.
 *
 * <h3>Two formulas and one status rule that must be transcribed exactly</h3>
 *
 * <p>Interest is {@code balance * rate / 1200} at {@code app/cbl/CBACT04C.cbl:L462-L470} - multiply first, then
 * divide by the literal 1200 with two-decimal half-even rounding. Never a division by 100 followed by one by
 * 12, and never a decimal multiplier: either changes the rounding and therefore the emitted amount.
 *
 * <p>The posting sign branch adds the amount to the current balance and then to the current-cycle
 * <em>credit</em> if it is non-negative and to the current-cycle <em>debit</em> otherwise
 * ({@code app/cbl/CBTRN02C.cbl:L547-L552}). A negative amount is added to the debit accumulator, so that
 * accumulator holds negative values. <strong>No absolute-value normalisation is permitted anywhere on this
 * path</strong>, and the reference fixture genuinely exercises it: {@code app/data/ASCII/dailytran.txt} carries
 * both {@code &#123;} and {@code &#125;} overpunch signs.
 *
 * <p>A record-not-found status is an <strong>accepted control path</strong>, not an error, at exactly two sites
 * in this package's logic: the category-balance upsert at {@code app/cbl/CBTRN02C.cbl:L467-L500}, which accepts
 * {@code '00'} or {@code '23'} before dispatching to create or rewrite, and the disclosure-group rate lookup,
 * which accepts either before substituting the literal default group and retrying - where the retry accepts
 * only success, so a missing default row abends. Everywhere else a not-found status is an error, so a blanket
 * mapping would abend both of these paths.
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify}. Maven 3.9.11 from the pinned wrapper, Java
 *       {@code [25,)} enforced, {@code release} 25, {@code -Xlint:all -Werror failOnWarning}.</li>
 *   <li><strong>Run.</strong> These are {@code ItemProcessor} implementations and are never invoked directly;
 *       a step supplies the items. <strong>Four of the five are beans and one is not.</strong> The four carry
 *       {@code @Component}, two of them with {@code @StepScope} declared on themselves, and each is injected
 *       as a {@code @Bean Step} method parameter by the job that drives it, so resolving a step-scoped one
 *       outside a step context fails by design. The fifth, {@code TransactionReportProcessor}, carries neither
 *       annotation: {@code com.cardemo.batch.jobs.TransactionReportJob} constructs it inside each STEP10R
 *       tasklet body and is its sole owner. That is finding <strong>F-008</strong> - the class was previously
 *       annotated while the job built it with {@code new}, so the bean definition was resolved by nothing and
 *       the annotations documented a lifecycle that never ran.</li>
 *   <li><strong>Test.</strong> Unit tests belong in {@code src/test/java/com/cardemo/unit/batch}.
 *       {@code TransactionCombineProcessorTest} and {@code TransactionCombineProcessorCoverageTest} cover the
 *       combine processor; {@code TransactionReportProcessorScopeIsolationTest} proves the report processor's
 *       single ownership and its per-execution isolation across interleaved and concurrent executions, and
 *       asserts that neither it nor {@code TransactionBackupReader} holds static mutable state, which is what
 *       makes construction per execution a complete guarantee; {@code ParityLoggerRoutingTest}
 *       proves that no monetary value from this package reaches the application log stream.
 *       <strong>Measured 4 August 2026:</strong> every processor in this package now has a unit test class of
 *       its own - {@code TransactionPostingProcessorTest}, {@code InterestCalculationProcessorTest},
 *       {@code StatementProcessorTest} and {@code StatementProcessorStreamingTest} alongside the combine and
 *       report suites already named. <strong>An earlier revision of this document recorded the posting,
 *       interest and statement processors as having no unit test class</strong>; that statement was true when
 *       written and is withdrawn here rather than quietly overwritten. The assertions it listed as owed are
 *       the ones those classes now make: the 102/103 fall-through emitting a single record bearing 103; the
 *       exact over-limit expression; a negative amount reaching the debit accumulator unnormalised; interest
 *       computed by the literal-1200 form; the default-group fallback succeeding and a missing default row
 *       abending; twenty lines to a page; the control break firing on the card number; the end-of-data double
 *       count; and the retention bound failing rather than truncating.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an 80 percent LINE floor on the merged bundle at
 *       {@code verify} with {@code haltOnFailure} and no exclusions for this package.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}:
 *       OpenJDK and {@code javac} 25.0.3, Maven 3.9.11, Docker Engine 29.7.0 with {@code docker compose}
 *       v5.3.1. Readings, not requirements.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>No record ceiling, and no property for one.</strong>
 *       {@code carddemo.batch.statement-processor.max-transactions-per-run} was published here and is gone
 *       with the code that read it - finding BAT-002. {@code StatementProcessor} takes exactly one
 *       constructor argument, its {@code FileService}, and nothing about a run's size is configurable. Cost
 *       per resident entry is unchanged and worth knowing: one projected record is 350 characters, on the
 *       order of 800 bytes retained, and a parity run over the 300 records of
 *       {@code app/data/ASCII/dailytran.txt} holds one card group at a time - a few kilobytes.</li>
 *   <li>Report window dates arrive as <strong>job parameters</strong> - {@code startDate} and {@code endDate},
 *       ten characters each - replacing the {@code DATEPARM} control input of {@code app/jcl/TRANREPT.jcl}.
 *       The owning step reads them from the job parameters and passes them to the constructor, which validates
 *       them, so one instance carries one window and construction happens once per execution. A per-run value
 *       in a property file would be shared by two concurrent runs.</li>
 *   <li>The interest date is likewise a job parameter: ten numeric characters, eight date digits then two
 *       zeros, concatenated into generated identifiers and never reformatted.</li>
 *   <li>{@code carddemo.decimal.rounding-mode: HALF_EVEN}, {@code monetary-scale: 2} and
 *       {@code interest-divisor: 1200}. Three precisions exist and are not interchangeable: account money
 *       fields are {@code NUMERIC(12,2)}, the transaction amount and the category balance are
 *       {@code NUMERIC(11,2)} - eleven digits, not twelve - and the disclosure rate is
 *       {@code NUMERIC(6,2)}.</li>
 *   <li>{@code com.cardemo.parity} is {@code OFF} in every profile. The three parity {@code DISPLAY}
 *       emissions this package makes - a transaction record, {@code TRAN-AMT} and {@code WS-PAGE-TOTAL}, and a
 *       category balance - travel on that isolated tree and are never enabled in a deployment.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: two reject records for one input, or a reject bearing 102 where the baseline says
 *       103.</strong> Cause: the expiry check was guarded, or an early exit was added between the two checks.
 *       <em>Remediation:</em> restore the unguarded sequential form. <strong>Severity: High</strong> - it is a
 *       byte-level parity break in the reject stream.</p></li>
 *   <li><p><strong>Symptom: over-limit rejects appear or disappear against the baseline.</strong> Cause: the
 *       temporary-balance expression was algebraically rewritten, or the amount was normalised with an absolute
 *       value. <em>Remediation:</em> transcribe the expression as written and leave the sign alone.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: interest differs from the baseline in the last penny.</strong> Cause: the divisor
 *       was split, or a decimal multiplier substituted, or the rounding mode changed.
 *       <em>Remediation:</em> restore {@code balance * rate / 1200} with half-even rounding at scale 2.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: the job abends on a record whose category balance does not yet exist.</strong>
 *       Cause: the not-found status was mapped to an exception at the upsert site. <em>Remediation:</em> accept
 *       {@code '00'} or {@code '23'} there. <strong>Severity: Blocker</strong> - the posting run cannot
 *       complete.</p></li>
 *   <li><p><strong>Symptom: the statement run fails with a retention-bound message.</strong> Cause: the input
 *       exceeded the configured bound. <em>Remediation:</em> this is the bound working; investigate the input
 *       first, and lower the bound to fit the heap if the input is legitimate. Do not raise it above what the
 *       heap holds. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: statement output differs from the baseline in the processing timestamp.</strong>
 *       Cause: the upstream projection's two-byte truncation was "fixed". The statement sort copies fifty bytes
 *       from offset 279, which is the whole originating timestamp plus only the <em>first twenty-four</em> of
 *       the twenty-six processing-timestamp bytes, and drops the trailing filler entirely.
 *       <em>Remediation:</em> reproduce the truncation. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a generated timestamp differs from the baseline in its final digits.</strong>
 *       Cause: nanosecond precision. The legacy generator produces 26 characters whose <strong>final four
 *       digits are always zeros</strong>. <em>Remediation:</em> format to hundredths-of-a-second precision
 *       followed by four literal zeros - not milliseconds, which would need a seventh fraction character.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: two overlapping report executions produce mixed totals.</strong> Cause: a
 *       {@code @StepScope} annotation was removed, or per-run state moved to a static or singleton field.
 *       <em>Remediation:</em> restore the scope; the report processor carries nine mutable accumulators and one
 *       instance per execution is what keeps them apart. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a monetary value appears in the application log.</strong> Cause: a parity
 *       {@code DISPLAY} was routed to a class logger. <em>Remediation:</em> route it to
 *       {@code com.cardemo.parity.<PROGRAM>}. <strong>Severity: Medium</strong>, and a Rule 1 Clause D
 *       violation.</p></li>
 *   </ol>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>One private method per COBOL paragraph, never consolidated</strong>, each carrying a Javadoc
 *       citation to its source label. That correspondence is what makes the scope-coverage gate provable by
 *       inspection.</li>
 *   <li><strong>No I/O.</strong> A processor transforms; reading belongs to {@code com.cardemo.batch.readers}
 *       and writing to {@code com.cardemo.batch.writers}. Repository lookups that the source performs inline -
 *       the cross-reference, type and category reads - are the exception the source itself dictates.</li>
 *   <li><strong>No {@code float} or {@code double} on any financial path</strong>, and equality by
 *       {@code compareTo} rather than {@code equals}, because {@code equals} distinguishes {@code 1.0} from
 *       {@code 1.00}.</li>
 *   <li><strong>Reject codes are business outcomes.</strong> There are exactly five - 100, 101, 102, 103 and
 *       109 - each carrying its exact literal description. They are never thrown.</li>
 *   <li><strong>Every legacy quirk is reproduced or labelled, never silently corrected</strong>, and every
 *       retained no-op is marked at its own declaration with its locator, a reachability proof, an explicit
 *       intentional-no-op marker and an acknowledgement that it is owed an entry in the
 *       {@code DECISION_LOG.md}. No file in this tree keeps a global tally of them.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run
 *       time; those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.batch.processors;
