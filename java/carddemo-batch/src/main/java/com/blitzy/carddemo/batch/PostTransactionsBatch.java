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
package com.blitzy.carddemo.batch;

import com.blitzy.carddemo.application.transaction.CbTrn02C;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-Java batch driver for the {@code POSTTRAN} JCL flow
 * ({@code app/jcl/POSTTRAN.jcl}). This driver is the {@code carddemo-batch}
 * orchestration-layer wrapper around the {@link CbTrn02C} use case (translated
 * from the COBOL program {@code app/cbl/CBTRN02C.cbl}, "Daily transaction
 * posting engine").
 *
 * <h2>Source lineage</h2>
 *
 * <h3>JCL — {@code app/jcl/POSTTRAN.jcl}</h3>
 * The originating JCL job runs a single step {@code STEP15} that invokes
 * {@code PGM=CBTRN02C} with the following DD allocations:
 * <ul>
 *   <li><b>TRANFILE</b> — {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS},
 *       {@code DISP=SHR}; OUTPUT side for newly posted transactions
 *       (sequential WRITE via {@link TransactionRepository}).</li>
 *   <li><b>DALYTRAN</b> — {@code AWS.M2.CARDDEMO.DALYTRAN.PS},
 *       {@code DISP=SHR}; INPUT sequential stream of pending daily
 *       transactions consumed via
 *       {@link DailyTransactionRepository#streamSequential()}.</li>
 *   <li><b>XREFFILE</b> — {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS},
 *       {@code DISP=SHR}; INPUT random read by card number via
 *       {@link CardXrefRepository#findByCardNumber(String)} (paragraph
 *       {@code 1500-A-LOOKUP-XREF}).</li>
 *   <li><b>DALYREJS</b> — {@code AWS.M2.CARDDEMO.DALYREJS(+1)},
 *       {@code DISP=(NEW,CATLG,DELETE)}, {@code RECFM=F}, {@code LRECL=430};
 *       OUTPUT new generation of the GDG that holds REJECT-RECORD
 *       entries (350 bytes data + 80 bytes trailer = 430 bytes total).
 *       Writes are routed through
 *       {@link DailyTransactionRepository#appendReject(
 *       com.blitzy.carddemo.domain.record.DalyTranRecord, int, String)}.
 *       The {@code (+1)} generation rolling is the responsibility of the
 *       sibling {@code DailyRejectsBatch} driver — this driver writes
 *       through the port and does not manage generation versions
 *       directly.</li>
 *   <li><b>ACCTFILE</b> — {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS},
 *       {@code DISP=SHR}; opened {@code I-O} by CBTRN02C so the program can
 *       READ then REWRITE during posting (paragraphs
 *       {@code 1500-B-LOOKUP-ACCT} and {@code 2800-UPDATE-ACCOUNT-REC}).</li>
 *   <li><b>TCATBALF</b> — {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS},
 *       {@code DISP=SHR}; opened {@code I-O} by CBTRN02C — paragraph
 *       {@code 2700-UPDATE-TCATBAL} reads then either {@code REWRITE}s
 *       (existing key) or {@code WRITE}s (new key) the
 *       transaction-category balance.</li>
 * </ul>
 * The JCL passes <strong>no {@code PARM}</strong>; the COBOL program uses
 * its own system date for date-sensitive logic. In the Java translation the
 * processing date is read from the bound {@link BatchRunContext#BATCH_CTX}.
 *
 * <h3>COBOL — {@code app/cbl/CBTRN02C.cbl}</h3>
 * The translated use case ({@link CbTrn02C}) implements the full posting
 * engine and exposes
 * <ul>
 *   <li>{@link CbTrn02C#run()} — opens the five datasets (paragraphs
 *       {@code 0000-DALYTRAN-OPEN} through {@code 0500-TCATBALF-OPEN}),
 *       drives the main posting loop (paragraphs {@code 1000} →
 *       {@code 1500} → {@code 2000} / {@code 2500} →
 *       {@code 2700-2900}), and closes them (paragraphs
 *       {@code 9000-DALYTRAN-CLOSE} through
 *       {@code 9500-TCATBALF-CLOSE}). It sets
 *       {@code WS-REJECT-COUNT &gt; 0 ⇒ MOVE 4 TO RETURN-CODE} at the end
 *       of the loop ({@code app/cbl/CBTRN02C.cbl:L230}).</li>
 *   <li>{@link CbTrn02C#returnCode()} — exposes the COBOL {@code RETURN-CODE}
 *       value (0 = success, {@value CbTrn02C#RETURN_CODE_WITH_REJECTS} =
 *       some records were rejected).</li>
 * </ul>
 *
 * <h2>Plain-Java batch driver (AAP §0.6.12 architectural override)</h2>
 * <p>This driver is intentionally <strong>not</strong> a Spring Batch
 * {@code Step} or {@code Tasklet}. Per AAP §0.6.12 the previously documented
 * Spring Boot / Spring Batch architecture in {@code docs/technical-specifications.md}
 * is REPLACED by plain Java drivers with constructor injection and
 * {@code ScopedValue} propagation. This class therefore has no
 * framework annotations, no {@code @Service}/{@code @Component}, no
 * {@code @StepScope}/{@code @JobScope}, no {@code ItemReader}/{@code ItemWriter},
 * and no XML/JavaConfig wiring.
 *
 * <h2>Sequential processing — NO virtual-thread fan-out</h2>
 * <p>CBTRN02C performs ordering-sensitive I-O mode updates on the
 * {@code ACCTFILE} (REWRITE per posted transaction) and {@code TCATBALF}
 * (READ-then-WRITE-or-REWRITE upsert per posted transaction) VSAM KSDS
 * datasets. Two transactions against the same account or the same
 * (acctId, typeCd, catCd) category-balance key MUST be applied in the
 * order they appear in DALYTRAN, because the second update reads the
 * post-first balance from the dataset. Reordering would change the
 * resulting balances and therefore the observable byte-for-byte output of
 * both {@code ACCTFILE} and {@code TCATBALF}, violating the byte-fidelity
 * mandate of AAP §0.1.3.
 *
 * <p>For this reason this driver:
 * <ul>
 *   <li>Does NOT use {@code java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()}.</li>
 *   <li>Does NOT use any platform-thread pool, fork-join pool, or parallel
 *       stream.</li>
 *   <li>Delegates the entire posting work to a single sequential call to
 *       {@link CbTrn02C#run()}.</li>
 * </ul>
 * Virtual-thread fan-out is appropriate for sibling batches whose per-record
 * work is provably independent (per AAP §0.6.6); it is forbidden HERE.
 *
 * <h2>{@link BatchRunContext} consumption</h2>
 * <p>{@link #execute()} reads the active {@code BatchRunContext} from
 * {@code BatchRunContext.BATCH_CTX} (the JEP 506 {@link ScopedValue}
 * finalized in Java 25). The caller — typically the composition root
 * {@code carddemo-app/PostTransactionsApp} — MUST establish the binding via
 * {@code ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(() ->
 * postTransactionsBatch.execute())} or the convenience helper
 * {@link BatchRunContext#runWith(BatchRunContext, Runnable)}. If the
 * binding is not present at the start of {@link #execute()}, the method
 * throws {@link IllegalStateException} with a diagnostic message rather
 * than proceeding with a missing run context (this avoids silently
 * producing output that cannot be correlated with a batch run identity).
 *
 * <p>The run identity is propagated downstream automatically: any virtual
 * thread {@link CbTrn02C#run()} (or its callees) might create inherits
 * the binding from the calling thread's scope per JEP 506 semantics.
 *
 * <h2>Exit code semantics</h2>
 * <p>The COBOL {@code RETURN-CODE} convention (preserved verbatim per AAP
 * §0.7.1) is:
 * <ul>
 *   <li><b>0</b> — success; no records were rejected.</li>
 *   <li><b>{@value CbTrn02C#RETURN_CODE_WITH_REJECTS}</b> — partial
 *       success; at least one DALYTRAN record was rejected. The reject
 *       records were appended to DALYREJS via the
 *       {@link DailyTransactionRepository#appendReject(
 *       com.blitzy.carddemo.domain.record.DalyTranRecord, int, String)}
 *       port; the JCL job convention treats this as a warning and
 *       proceeds to subsequent steps.</li>
 *   <li>Any other code — hard failure. The COBOL program does not
 *       distinguish further exit codes; failures during
 *       {@link CbTrn02C#run()} surface as an
 *       {@link com.blitzy.carddemo.application.AbendException} or
 *       {@link RuntimeException} which propagates out of this driver and
 *       must be handled by the calling composition root.</li>
 * </ul>
 *
 * <h2>Hexagonal ports (AAP §0.3.6)</h2>
 * <p>Six dependencies are accepted by the constructor; all are
 * <strong>ports</strong> (domain-defined interfaces from
 * {@code carddemo-domain.port}) or the use-case class itself, never
 * concrete adapters. The composition root in {@code carddemo-app} selects
 * either the file-backed implementation ({@code carddemo-adapter-file}) or
 * an optional JDBC implementation ({@code carddemo-adapter-db}) at startup
 * and passes them in. The use case ({@link CbTrn02C}) is itself
 * constructor-injected with the same five repository ports; this driver
 * accepts them as separate parameters in addition to the use-case
 * instance so the composition root sees an explicit list of every port
 * that POSTTRAN exercises (matches the {@code POSTTRAN.jcl} DD inventory
 * one-for-one).
 *
 * <h2>Thread safety</h2>
 * <p>Instances of this class are immutable after construction (all fields
 * are {@code final}) but the underlying repositories and use case may
 * not be re-entrant. A single {@code PostTransactionsBatch} instance MUST
 * NOT be invoked concurrently from multiple threads; one
 * {@link #execute()} call per JCL job is the expected usage pattern.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP §0.2.1 — supplementary files mandate</li>
 *   <li>AAP §0.3.1 — {@code carddemo-batch} module layout</li>
 *   <li>AAP §0.3.6 — hexagonal architecture diagram (ports, not adapters)</li>
 *   <li>AAP §0.4.1 — {@code PostTransactionsApp} listing</li>
 *   <li>AAP §0.6.6 — virtual-thread fan-out rule and
 *       {@code ScopedValue} pattern</li>
 *   <li>AAP §0.6.12 — Spring Batch architectural override</li>
 *   <li>AAP §0.7.1 — refactor discipline (preserve exit codes verbatim)</li>
 *   <li>AAP §0.7.3 — mandated Java 25 features
 *       ({@link ScopedValue}, {@code java.time}, records, sealed types)</li>
 *   <li>AAP §0.7.4 — forbidden features (no {@link ThreadLocal}, no
 *       preview JEPs, no Spring, no Lombok, no {@code double}/{@code float})</li>
 * </ul>
 *
 * @see CbTrn02C
 * @see BatchRunContext
 * @see DailyTransactionRepository
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBTRN02C",
        sourcePath = "app/cbl/CBTRN02C.cbl",
        translationDate = "2026-05-25",
        notes = "POSTTRAN JCL flow (app/jcl/POSTTRAN.jcl STEP15 EXEC PGM=CBTRN02C). "
                + "Thin plain-Java batch driver per AAP §0.6.12 — wraps a single "
                + "sequential call to CbTrn02C.run() with BatchRunContext binding "
                + "checks, timing, and structured logging. SEQUENTIAL PROCESSING "
                + "ONLY: TCATBAL/ACCOUNT I-O updates are ordering-sensitive per "
                + "AAP §0.6.6; virtual-thread fan-out FORBIDDEN here."
)
public final class PostTransactionsBatch {

    // -----------------------------------------------------------------------
    // Static fields
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for batch lifecycle events. Used to emit:
     * <ul>
     *   <li>INFO at the start of {@link #execute()} — runId,
     *       processingDate, tenant from the bound
     *       {@link BatchRunContext}.</li>
     *   <li>INFO at the end of {@link #execute()} — exit code and elapsed
     *       wall-clock duration.</li>
     *   <li>WARN when the exit code is non-zero — signals to operators
     *       that at least one DALYTRAN record was rejected (the COBOL
     *       {@code MOVE 4 TO RETURN-CODE} pathway).</li>
     * </ul>
     * The concrete logger implementation (logback-classic per AAP §0.5.1)
     * is supplied by the composition root in {@code carddemo-app}; this
     * module depends only on the SLF4J API per the {@code carddemo-batch}
     * POM. {@code System.out} and {@code java.util.logging.Logger} are
     * explicitly forbidden per AAP §0.7.4 forbidden-patterns list.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionsBatch.class);

    // -----------------------------------------------------------------------
    // Instance fields — constructor-injected; all final and never null
    // -----------------------------------------------------------------------

    /**
     * Port for the {@code DALYTRAN} sequential input stream (COBOL
     * {@code DALYTRAN-FILE}) and the {@code DALYREJS} sequential reject
     * output (COBOL {@code DALYREJS-FILE}). {@link CbTrn02C}'s
     * {@code 1000-DALYTRAN-GET-NEXT} paragraph reads through
     * {@link DailyTransactionRepository#streamSequential()}; its
     * {@code 2500-WRITE-REJECT-REC} paragraph writes through
     * {@link DailyTransactionRepository#appendReject(
     * com.blitzy.carddemo.domain.record.DalyTranRecord, int, String)}.
     *
     * <p>This driver receives the port purely so the composition root has
     * an explicit DD-by-DD inventory in front of it; this field is not
     * itself read inside {@link #execute()} because the use case already
     * owns it (see the constructor of {@link CbTrn02C}). The field's
     * non-nullness is enforced by {@link Objects#requireNonNull(Object,
     * String)} in the constructor.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Port for the {@code XREFFILE} VSAM KSDS + alternate index
     * ({@code app/cpy/CVACT03Y.cpy}). {@link CbTrn02C}'s
     * {@code 1500-A-LOOKUP-XREF} paragraph performs the card-number to
     * account-id translation through
     * {@link CardXrefRepository#findByCardNumber(String)} prior to
     * account validation and posting. Held for the same composition-root-
     * visibility reason as {@link #dailyTransactionRepository}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Port for the {@code ACCTFILE} VSAM KSDS ({@code app/cpy/CVACT01Y.cpy},
     * 300-byte records, 11-digit ACCT-ID key). Opened in I-O mode by
     * CBTRN02C: {@code 1500-B-LOOKUP-ACCT} reads, the post-validation
     * overlimit and expiration checks run against the in-memory copy, and
     * {@code 2800-UPDATE-ACCOUNT-REC} REWRITEs with the updated current
     * balance and cycle credit/debit counters. Held for the same
     * composition-root-visibility reason as {@link #dailyTransactionRepository}.
     */
    private final AccountRepository accountRepository;

    /**
     * Port for the {@code TRANFILE} VSAM KSDS ({@code app/cpy/CVTRA05Y.cpy},
     * 350-byte transaction records, TRAN-ID key). CBTRN02C's
     * {@code 2900-WRITE-TRANSACTION-FILE} paragraph appends each newly
     * posted transaction via {@link TransactionRepository#save(
     * com.blitzy.carddemo.domain.record.TranRecord)}. Held for the same
     * composition-root-visibility reason as {@link #dailyTransactionRepository}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Port for the {@code TCATBALF} VSAM KSDS ({@code app/cpy/CVTRA01Y.cpy},
     * 50-byte records, composite 17-byte key = ACCT-ID PIC 9(11) +
     * TYPE-CD PIC X(02) + CAT-CD PIC 9(04)). Opened in I-O mode by
     * CBTRN02C: {@code 2700-UPDATE-TCATBAL} reads, then
     * {@code 2700-A-CREATE-TCATBAL-REC} writes a new record OR
     * {@code 2700-B-UPDATE-TCATBAL-REC} REWRITEs the existing record
     * with the cumulative category balance. Held for the same
     * composition-root-visibility reason as {@link #dailyTransactionRepository}.
     */
    private final TransactionCategoryBalanceRepository tranCatBalRepository;

    /**
     * The translated CBTRN02C posting-engine use case. This driver is a
     * thin wrapper that delegates the actual posting work to a single
     * sequential {@link CbTrn02C#run()} call (no per-record orchestration
     * happens at the batch layer; that is the use case's responsibility).
     * The exit code is then read from {@link CbTrn02C#returnCode()} after
     * {@code run()} returns.
     *
     * <p>The use case is itself constructor-injected with the same five
     * repository ports above; this driver does NOT re-wire them — the
     * composition root constructs both the use case and this driver,
     * passing the same port instances to both.
     */
    private final CbTrn02C cbTrn02C;

    // -----------------------------------------------------------------------
    // Constructor — constructor injection per AAP §0.3.6
    // -----------------------------------------------------------------------

    /**
     * Constructs a {@code PostTransactionsBatch} with the six dependencies
     * required to express the {@code POSTTRAN} JCL flow (five repository
     * ports — one per DD in {@code app/jcl/POSTTRAN.jcl} STEP15 — plus
     * the {@link CbTrn02C} use case).
     *
     * <p>All parameters are validated for non-nullness via
     * {@link Objects#requireNonNull(Object, String)}; a null argument
     * produces a {@link NullPointerException} whose message names the
     * offending parameter for fast diagnostic feedback at the composition
     * root.
     *
     * @param dailyTransactionRepository      port for DALYTRAN sequential
     *                                        reads and DALYREJS sequential
     *                                        writes; never {@code null}
     * @param cardXrefRepository              port for XREFFILE random reads
     *                                        by card number; never
     *                                        {@code null}
     * @param accountRepository               port for ACCTFILE I-O random
     *                                        read + REWRITE; never
     *                                        {@code null}
     * @param transactionRepository           port for TRANSACT sequential
     *                                        writes of newly posted
     *                                        transactions; never
     *                                        {@code null}
     * @param tranCatBalRepository            port for TCATBALF I-O random
     *                                        read + WRITE/REWRITE upsert;
     *                                        never {@code null}
     * @param cbTrn02C                        translated CBTRN02C posting
     *                                        engine use case to delegate
     *                                        the actual posting work to;
     *                                        never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PostTransactionsBatch(
            DailyTransactionRepository dailyTransactionRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionCategoryBalanceRepository tranCatBalRepository,
            CbTrn02C cbTrn02C) {
        this.dailyTransactionRepository = Objects.requireNonNull(
                dailyTransactionRepository, "dailyTransactionRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.tranCatBalRepository = Objects.requireNonNull(
                tranCatBalRepository, "tranCatBalRepository");
        this.cbTrn02C = Objects.requireNonNull(cbTrn02C, "cbTrn02C");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Runs the POSTTRAN batch flow once. This method:
     * <ol>
     *   <li>Verifies that {@link BatchRunContext#BATCH_CTX} is bound in
     *       the current thread's scope; throws
     *       {@link IllegalStateException} with a diagnostic message if
     *       not. The caller MUST establish the binding via
     *       {@code ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(...)}
     *       or {@link BatchRunContext#runWith(BatchRunContext, Runnable)}
     *       before invoking this method.</li>
     *   <li>Logs the start of the run at INFO level with {@code runId},
     *       {@code processingDate}, and {@code tenant} from the bound
     *       context, so operators and log-aggregation tooling can
     *       correlate the run with its identity.</li>
     *   <li>Captures the wall-clock start time via
     *       {@link Instant#now()}.</li>
     *   <li>Delegates the actual posting work to {@link CbTrn02C#run()}.
     *       This single call drives the entire COBOL PROCEDURE DIVISION:
     *       OPEN all five datasets, loop over DALYTRAN with per-record
     *       validation and posting (or rejection), CLOSE all five
     *       datasets, and set the COBOL {@code RETURN-CODE}. The call
     *       is <strong>sequential</strong> — no fan-out, no parallelism
     *       — to preserve the order-sensitive I-O updates on
     *       {@code ACCTFILE} and {@code TCATBALF}.</li>
     *   <li>Reads the exit code from {@link CbTrn02C#returnCode()}; this
     *       is either {@code 0} (success) or
     *       {@value CbTrn02C#RETURN_CODE_WITH_REJECTS} (some records
     *       rejected).</li>
     *   <li>Computes the elapsed {@link Duration} from the captured start
     *       time and the current {@link Instant#now()}.</li>
     *   <li>Logs the end of the run at INFO level with exit code and
     *       elapsed duration; if the exit code is non-zero, also logs
     *       at WARN level so the rejection condition is visible at the
     *       coarsest log level commonly enabled in production.</li>
     *   <li>Returns the exit code to the caller (the composition root in
     *       {@code carddemo-app/PostTransactionsApp} typically passes it
     *       to {@link System#exit(int)} so the JCL-equivalent shell
     *       script can branch on {@code RC=4} vs {@code RC=0}).</li>
     * </ol>
     *
     * <p>Note that the {@link CbTrn02C#run()} method is declared
     * {@code void} (it stores the COBOL RETURN-CODE in an instance field);
     * the exit code is therefore retrieved via the separate
     * {@link CbTrn02C#returnCode()} accessor after {@code run()} returns
     * normally. This two-call pattern is intentional: it preserves the
     * COBOL semantics where {@code RETURN-CODE} is a separate field set
     * by the program just before STOP RUN, and it lets the use case also
     * expose {@link CbTrn02C#transactionCount()} and
     * {@link CbTrn02C#rejectCount()} for callers that want richer
     * post-run metrics.
     *
     * <p>Any exception thrown by {@link CbTrn02C#run()} (typically
     * {@link com.blitzy.carddemo.application.AbendException} for a hard
     * COBOL ABEND-equivalent failure, or {@link RuntimeException} for a
     * dataset-level I/O failure) propagates out of this method
     * unchanged. The caller is responsible for surfacing the exception
     * to its process exit handler; this driver does not catch or wrap
     * exceptions because doing so would obscure the failure surface and
     * lose the original stack trace.
     *
     * @return the COBOL {@code RETURN-CODE}: {@code 0} for success, or
     *         {@value CbTrn02C#RETURN_CODE_WITH_REJECTS} when at least
     *         one DALYTRAN record was rejected (per AAP §0.7.1
     *         "preserve return codes exactly")
     * @throws IllegalStateException if {@link BatchRunContext#BATCH_CTX}
     *                               is not bound in the calling thread's
     *                               scope at the time of the call
     */
    public int execute() {
        // Step 1: Verify the BatchRunContext is bound. We do not use the
        // BatchRunContext.current() convenience helper directly here so we
        // can produce a more specific diagnostic message that names this
        // class and instructs the caller exactly how to bind the context.
        if (!BatchRunContext.BATCH_CTX.isBound()) {
            throw new IllegalStateException(
                    "PostTransactionsBatch.execute() requires BatchRunContext.BATCH_CTX to be bound");
        }
        BatchRunContext ctx = BatchRunContext.BATCH_CTX.get();
        // Defensive: ScopedValue.get() on a bound key should never return
        // null, but guarding the LOG.info call below against an
        // unexpected null protects against a misuse of ScopedValue's
        // raw API (e.g., binding null via reflection) without changing
        // the observable behavior on the happy path.
        Objects.requireNonNull(ctx, "BatchRunContext.BATCH_CTX bound value");

        // Step 2: Capture local snapshots of the three context fields.
        // BatchRunContext is a record so the accessors are pure (no
        // side effects); storing them in locals makes the start-of-run
        // log statement and downstream code easier to read and
        // simplifies the SLF4J parameterised log call.
        String runId = ctx.runId();
        LocalDate processingDate = ctx.processingDate();
        String tenant = ctx.tenant();

        // Step 3: Log the start of the run at INFO so operators and
        // log-aggregation tooling can correlate the run with its
        // identity. The SLF4J {} placeholders avoid string concatenation
        // when INFO is disabled (it is normally enabled in production,
        // but defensive parameterisation is cheap and idiomatic).
        LOG.info("POSTTRAN start: runId={}, processingDate={}, tenant={}",
                runId, processingDate, tenant);

        // Step 4: Capture the wall-clock start time. Instant.now() reads
        // from the system clock; we use it for elapsed-time computation
        // only, not for any business-logic date/time decisions (those
        // are driven by ctx.processingDate()).
        Instant start = Instant.now();

        // Step 5: Delegate to the use case. CbTrn02C.run() is sequential
        // by design (see §"Sequential processing" in the class Javadoc);
        // we do NOT wrap this call in any virtual-thread executor or
        // parallel construct. Any AbendException or RuntimeException
        // propagates out of execute() unchanged so the caller's exit
        // handler sees the original failure stack.
        cbTrn02C.run();

        // Step 6: Read the COBOL RETURN-CODE from the use case. This is
        // a separate accessor because CbTrn02C.run() is declared void
        // (mirroring the COBOL PROCEDURE DIVISION which sets RETURN-CODE
        // as a side effect, then STOP RUNs); the returnCode() accessor
        // gives us the value the COBOL program would have placed in
        // RETURN-CODE just before STOP RUN.
        int exitCode = cbTrn02C.returnCode();

        // Step 7: Compute elapsed wall-clock time for the completion log
        // line. Duration.between() handles the Instant arithmetic; we
        // call Instant.now() exactly once here so the end-of-run log
        // line reflects a consistent timestamp.
        Duration elapsed = Duration.between(start, Instant.now());

        // Step 8: Emit the end-of-run INFO log line. The exit code and
        // elapsed duration are the two pieces of information operators
        // need to know "did the job succeed and how long did it take";
        // additional metrics (processed count, reject count) are
        // available on the use case for callers that want them.
        LOG.info("POSTTRAN end: exitCode={}, elapsed={}", exitCode, elapsed);

        // Step 9: If the COBOL program would have set RETURN-CODE = 4
        // (rejects observed), additionally emit a WARN line so the
        // condition is visible at the coarsest log level commonly
        // enabled in production. WARN is the right level (not ERROR)
        // because the COBOL convention treats RC=4 as a warning that
        // subsequent JCL steps proceed past; the job did not fail.
        if (exitCode != 0) {
            LOG.warn("POSTTRAN completed with rejects: returnCode={}", exitCode);
        }

        // Step 10: Return the exit code so the composition root can
        // pass it to System.exit() or its equivalent. Per AAP §0.7.1
        // ("preserve return codes exactly") this value MUST be the
        // same value the COBOL program would have set.
        return exitCode;
    }
}
