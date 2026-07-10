package com.carddemo.batch;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.carddemo.exception.FileProcessingException;
import com.carddemo.service.InterestCalculationService;

/**
 * Chunk-step {@link ItemProcessor} for the CardDemo interest-calculation stage &mdash; processor
 * #2 of 5 in the batch pipeline, wired into {@code InterestCalculationJob} (JCL step
 * {@code INTCALC}).
 *
 * <p><strong>COBOL lineage (reference-only, source commit SHA {@code 27d6c6f}).</strong> This
 * processor is the {@code reader &rarr; processor &rarr; writer} seam for the legacy batch
 * interest calculator {@code app/cbl/CBACT04C.cbl}, launched by {@code app/jcl/INTCALC.jcl}
 * ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}). {@code CBACT04C} is a single monolithic
 * {@code PERFORM UNTIL END-OF-FILE} loop that scans the transaction-category-balance file
 * ({@code TCATBAL}) in ascending account-id order and drives a <em>control break</em> on the
 * account id:</p>
 *
 * <pre>
 *   PERFORM UNTIL END-OF-FILE = 'Y'
 *       PERFORM 1000-TCATBALF-GET-NEXT
 *       IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM         (control break — new account)
 *           IF WS-FIRST-TIME NOT = 'Y'
 *               PERFORM 1050-UPDATE-ACCOUNT               (post accrued interest to the prior account)
 *           END-IF
 *           MOVE 0 TO WS-TOTAL-INT
 *           MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
 *           PERFORM 1100-GET-ACCT-DATA                    (read the account master)
 *           PERFORM 1110-GET-XREF-DATA                    (resolve the card cross-reference)
 *       END-IF
 *       PERFORM 1200-GET-INTEREST-RATE                    (resolve the disclosure-group rate)
 *       IF DIS-INT-RATE NOT = 0
 *           PERFORM 1300-COMPUTE-INTEREST                 (monthly interest + write interest tx)
 *           PERFORM 1400-COMPUTE-FEES
 *       END-IF
 *   END-PERFORM
 *   ...PERFORM 1050-UPDATE-ACCOUNT                        (post the final account after EOF)
 * </pre>
 *
 * <p>The net effect of that control break is that {@code CBACT04C} performs its per-account
 * interest work exactly once for <em>each distinct account that owns at least one {@code TCATBAL}
 * row</em>. In the modular target that read loop belongs to the Spring Batch layer:
 * {@code InterestAccountItemReader} reproduces the driving set precisely (the distinct account ids
 * present in the transaction-category-balance table, ascending, one {@link Long} per item), and
 * this processor is invoked once per emitted account id.</p>
 *
 * <h2>Thin orchestrator ({@code CALL} &rarr; bean delegation, AAP &sect;0.4.3, &sect;0.5.1)</h2>
 * <p>Following the COBOL {@code CALL} &rarr; Spring bean mandate, the entire per-account interest
 * computation &mdash; reading the account's transaction-category balances, resolving the
 * disclosure-group rate ({@code 1200-GET-INTEREST-RATE}), computing the monthly interest
 * ({@code 1300-COMPUTE-INTEREST}), writing one interest transaction per category (type
 * {@code '01'}, category {@code 5}, source {@code 'System'}), zeroing the current-cycle
 * credit/debit accumulators, and rewriting the account balance ({@code 1050-UPDATE-ACCOUNT})
 * &mdash; already lives in
 * {@link InterestCalculationService#applyInterestToAccount(Long)}. That method is annotated
 * {@code @Transactional(rollbackFor = Exception.class)} and performs <em>all</em> persistence
 * itself. This processor is therefore a deliberately thin orchestrator: it delegates one account
 * at a time and re-implements none of the interest arithmetic or repository access. Extracting the
 * business logic into the service (rather than the processor) keeps it independently
 * unit-testable and reusable by the online tier.</p>
 *
 * <h2>Identity return contract</h2>
 * <p>{@link #process(Long)} returns the same account id it was handed. In Spring Batch a
 * {@code null} return value <em>filters</em> the item out of the chunk; returning the id keeps the
 * item non-{@code null} so it is counted and passed to the writer, giving an accurate processed
 * count. The service is the single source of truth for persistence, so the step's writer is a
 * lightweight logging / no-op writer wired inline in {@code InterestCalculationJob}: it must
 * <strong>not</strong> re-save the account (doing so would collide with the account's
 * {@link jakarta.persistence.Version @Version} optimistic-lock column and double-persist the
 * interest). The writer choice is a job-configuration concern; this class only guarantees a
 * non-{@code null}, already-persisted item flows downstream.</p>
 *
 * <h2>Decimal fidelity (AAP G2, &sect;0.8.2)</h2>
 * <p>The interest total returned by the service is a {@link BigDecimal} at scale&nbsp;2, matching
 * the COBOL {@code PIC S9(n)V99} pictures and the {@code NUMERIC(p,2)} columns. No {@code float}
 * or {@code double} appears anywhere in this class; the total is only logged, never recomputed or
 * narrowed to a binary floating-point type.</p>
 *
 * <h2>PARM handling</h2>
 * <p>The JCL {@code PARM='2022071800'} (the processing date) maps to the property
 * {@code carddemo.batch.interest.processing-date}. If the per-account computation needs that date
 * it is read inside {@link InterestCalculationService} or supplied as a job parameter by
 * {@code InterestCalculationJob}; this processor does not own or read the parameter.</p>
 *
 * <h2>Fault handling</h2>
 * <p>Genuine faults propagate to fail the chunk: a missing account or card cross-reference
 * surfaces from the service as a {@code ResourceNotFoundException} (the COBOL {@code INVALID KEY}
 * path), and an optimistic-lock conflict surfaces as its typed conflict exception &mdash; both
 * carry their own HTTP semantics and are intentionally <em>not</em> caught or re-wrapped here. The
 * one condition this processor detects directly is an out-of-contract {@code null} account id: the
 * reader guarantees a non-{@code null} id (the {@code trancat_acct_id} key is {@code BIGINT NOT
 * NULL}), so a {@code null} item is a corrupt-input / contract-violation fault of the same class as
 * the COBOL {@code 9999-ABEND-PROGRAM} paragraph. It is raised as a {@link FileProcessingException}
 * (never {@code System.exit}), consistent with the abend-class fault handling used across the
 * migrated batch tier.</p>
 *
 * <p>The component is stateless (its only field is the injected, immutable service) and therefore
 * thread-safe. Structured logging carries only the account id and the interest total; the
 * per-execution {@code correlationId} MDC entry is established by {@code BatchCorrelationIdListener}
 * and rendered by {@code logback-spring.xml}, so this class emits log lines without managing the
 * MDC itself.</p>
 *
 * @see InterestCalculationService#applyInterestToAccount(Long)
 * @see InterestAccountItemReader
 * @see FileProcessingException
 */
@Component
public final class InterestCalculationProcessor implements ItemProcessor<Long, Long> {

    /**
     * Structured logger. Emits only the account id and the interest total (a scale-2
     * {@link BigDecimal}); the {@code correlationId} MDC entry is contributed by
     * {@code BatchCorrelationIdListener}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /**
     * The interest-calculation domain service that performs the entire per-account computation and
     * all persistence. This processor holds it as its sole collaborator and delegates to it once
     * per account (constructor-injected, immutable).
     */
    private final InterestCalculationService interestCalculationService;

    /**
     * Creates the processor with its sole collaborator, the interest-calculation service.
     *
     * <p>Spring injects the dependency through this single constructor (so no {@code @Autowired}
     * annotation is required) and it is stored in a {@code final} field, making the processor
     * effectively immutable and safe for the pooled batch step thread. This constructor-injection
     * wiring is the idiomatic replacement for the static COBOL {@code CALL} linkage of the legacy
     * batch program (AAP&nbsp;&sect;0.4.3).</p>
     *
     * @param interestCalculationService the service encapsulating the per-account interest
     *                                   computation and persistence; must not be {@code null}
     * @throws NullPointerException if {@code interestCalculationService} is {@code null}
     */
    public InterestCalculationProcessor(InterestCalculationService interestCalculationService) {
        this.interestCalculationService = Objects.requireNonNull(
                interestCalculationService, "interestCalculationService must not be null");
    }

    /**
     * Applies monthly interest to a single account by delegating to
     * {@link InterestCalculationService#applyInterestToAccount(Long)}, reproducing one iteration of
     * the {@code CBACT04C} per-account control break.
     *
     * <p>The service self-persists the generated interest transactions and the updated account
     * within its own {@code @Transactional(rollbackFor = Exception.class)} boundary and returns the
     * total interest applied (a scale-2 {@link BigDecimal}, {@code 0.00} when no category carried a
     * non-zero rate). This method performs no persistence of its own; it logs the outcome at
     * {@code DEBUG} and returns the account id unchanged.</p>
     *
     * <p>The account id is returned (never {@code null}) as an identity carrier so the chunk item
     * is counted and handed to the step's no-op/logging writer; returning {@code null} would filter
     * the item out and skew the processed count. Faults from the service (for example a missing
     * account or an optimistic-lock conflict) propagate unchanged to fail the chunk.</p>
     *
     * @param acctId the identifier of the account to process, emitted by
     *               {@code InterestAccountItemReader} ({@code TRANCAT-ACCT-ID} / {@code ACCT-ID});
     *               guaranteed non-{@code null} by the reader's {@code BIGINT NOT NULL} key
     * @return the same {@code acctId}, never {@code null}
     * @throws FileProcessingException if {@code acctId} is {@code null} &mdash; an out-of-contract
     *                                 corrupt-input fault of the COBOL {@code 9999-ABEND-PROGRAM}
     *                                 class (never terminates the JVM)
     */
    @Override
    public Long process(Long acctId) {
        // The reader emits a non-null account id (trancat_acct_id is BIGINT NOT NULL). A null item
        // is therefore an out-of-contract, corrupt-input fault equivalent to the COBOL abend path;
        // fail fast rather than passing null to the service.
        if (acctId == null) {
            throw new FileProcessingException(
                    "InterestCalculationProcessor received a null account id; the interest-calculation"
                            + " reader must emit a non-null TRANCAT-ACCT-ID (CBACT04C 9999-ABEND-PROGRAM)");
        }

        // CALL -> bean delegation (AAP §0.4.3): the service owns the entire per-account computation
        // and persistence within its own transaction. The returned total is a scale-2 BigDecimal.
        BigDecimal totalInterest = interestCalculationService.applyInterestToAccount(acctId);

        // Structured DEBUG log; parameterised so the message is only built when DEBUG is enabled.
        // Only the account id and the interest total are logged (no card numbers), and the total is
        // kept as a BigDecimal — never narrowed to double/float (decimal fidelity, AAP §0.8.2).
        LOG.debug("Applied interest to account {}: total interest {}", acctId, totalInterest);

        // Identity return: keep the item non-null so it is counted and written by the step's
        // no-op/logging writer. The service is the single persistence source of truth, so the
        // writer must not re-save the account (job-config concern; avoids @Version double-persist).
        return acctId;
    }
}
