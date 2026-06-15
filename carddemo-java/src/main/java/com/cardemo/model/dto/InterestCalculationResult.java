package com.cardemo.model.dto;

import com.cardemo.model.entity.Transaction;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable result carrier emitted by the interest-calculation pipeline's
 * per-record {@code ItemProcessor} and consumed by the
 * {@code com.cardemo.batch.writers} (which persists the generated interest
 * transaction) and {@code com.cardemo.batch.jobs} (which performs the
 * per-account balance roll-up) layers, reproducing the work the COBOL batch
 * program {@code app/cbl/CBACT04C.cbl} (Interest Calculator) performs for each
 * {@code TRAN-CAT-BAL-RECORD} it browses.
 *
 * <h2>Why this type exists (cross-folder coordination contract)</h2>
 * <p>{@code CBACT04C} reads the {@code TCATBAL} (transaction-category-balance)
 * KSDS sequentially. For each row it resolves the disclosure-group interest rate
 * (with a {@code DEFAULT} fallback), and &mdash; only when the rate is non-zero
 * &mdash; computes the monthly interest ({@code 1300-COMPUTE-INTEREST}), writes
 * an interest {@link Transaction} ({@code 1300-B-WRITE-TX}), and accumulates the
 * amount into a per-account total ({@code WS-TOTAL-INT}). At every account
 * boundary and at end-of-file it performs {@code 1050-UPDATE-ACCOUNT}, which adds
 * {@code WS-TOTAL-INT} to {@code ACCT-CURR-BAL}, zeroes the cycle credit/debit and
 * rewrites the account record.</p>
 *
 * <p>In the migrated, chunk-oriented Spring Batch step that single COBOL loop is
 * split across components: a <em>processor</em> that computes the per-row interest
 * and builds the interest transaction, and <em>writer/job</em> components that
 * persist the transaction and apply the per-account roll-up. Because a
 * chunk-oriented {@code ItemProcessor} cannot observe account boundaries across
 * items, the {@code 1050-UPDATE-ACCOUNT} roll-up is intentionally <strong>not</strong>
 * performed by the processor; instead the processor emits this carrier per row,
 * exposing the {@link #accountId()} and {@link #monthlyInterest()} the writer/job
 * needs to sum interest per account and apply it to {@code ACCT-CURR-BAL}.</p>
 *
 * <p>The carrier is deliberately defined <strong>once</strong>, here in
 * {@code com.cardemo.model.dto}, and shared by the producer
 * ({@code com.cardemo.batch.processors.InterestCalculationProcessor}) and the
 * consumers ({@code com.cardemo.batch.writers}/{@code com.cardemo.batch.jobs}). It
 * is intentionally <em>not</em> owned by the processor package and must not be
 * duplicated there: a single shared type keeps producer and consumers in lock-step
 * on the exact set of values the account roll-up needs &mdash; mirroring the
 * sibling {@link PostedTransactionResult} carrier used by the daily-posting
 * pipeline.</p>
 *
 * <h2>Component contract</h2>
 * <ul>
 *   <li>{@link #interestTransaction()} &mdash; the interest {@link Transaction}
 *       produced by {@code 1300-B-WRITE-TX}. Populated <strong>only when interest
 *       was applied</strong> ({@link #interestApplied()} is {@code true}, i.e. the
 *       resolved {@code DIS-INT-RATE} was non-zero); it is {@code null} on the
 *       zero-rate path, where COBOL processes the row but writes no interest
 *       transaction.</li>
 *   <li>{@link #accountId()} &mdash; the owning account id
 *       ({@code TRANCAT-ACCT-ID}, i.e. the row's {@code id.acctId}). Always present
 *       (on both the interest and zero-rate paths) so the account-boundary roll-up
 *       ({@code 1050-UPDATE-ACCOUNT}) still sees every processed row. Never
 *       {@code null}.</li>
 *   <li>{@link #monthlyInterest()} &mdash; {@code WS-MONTHLY-INT} for this row, a
 *       {@link BigDecimal} of scale&nbsp;2 ({@code PIC S9(09)V99}). It is
 *       {@link BigDecimal#ZERO} on the zero-rate path. Never {@code null}.</li>
 *   <li>{@link #interestApplied()} &mdash; {@code true} only when a non-zero
 *       {@code DIS-INT-RATE} produced an interest transaction; {@code false} on the
 *       zero-rate path.</li>
 * </ul>
 *
 * <h2>Immutability and thread-safety</h2>
 * <p>As a {@code record} this carrier is shallowly immutable: its references are
 * {@code final} and it exposes no setters, so it is safe to pass between Spring
 * Batch threads. (The referenced {@link Transaction} entity is itself mutable; the
 * writer, not this carrier, owns any subsequent mutation/persistence of it.) The
 * component order &mdash; {@code (interestTransaction, accountId, monthlyInterest,
 * interestApplied)} &mdash; is the stable construction contract used by the
 * processor.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository (AAP &sect;0.7.2).</p>
 *
 * @param interestTransaction the generated interest transaction; {@code null} when
 *                            the rate was zero and no transaction was produced
 * @param accountId           the owning account id ({@code TRANCAT-ACCT-ID}); never
 *                            {@code null}
 * @param monthlyInterest     the computed monthly interest ({@code WS-MONTHLY-INT},
 *                            scale&nbsp;2); {@link BigDecimal#ZERO} on the zero-rate
 *                            path; never {@code null}
 * @param interestApplied     {@code true} only when a non-zero rate produced a
 *                            transaction
 * @see Transaction
 * @see PostedTransactionResult
 */
public record InterestCalculationResult(
        Transaction interestTransaction,
        Long accountId,
        BigDecimal monthlyInterest,
        boolean interestApplied) {

    /**
     * Compact canonical constructor enforcing the two invariants that hold on
     * <em>every</em> path through {@code CBACT04C}'s per-row processing: the owning
     * {@link #accountId()} and the {@link #monthlyInterest()} amount are always
     * present (the latter being {@link BigDecimal#ZERO} on the zero-rate path). The
     * {@link #interestTransaction()} is legitimately {@code null} on the zero-rate
     * path and is therefore not constrained here.
     *
     * @throws NullPointerException if {@code accountId} or {@code monthlyInterest}
     *                              is {@code null}
     */
    public InterestCalculationResult {
        Objects.requireNonNull(accountId,
                "accountId must not be null (every processed row belongs to an account for the 1050 roll-up)");
        Objects.requireNonNull(monthlyInterest,
                "monthlyInterest must not be null (use BigDecimal.ZERO on the zero-rate path)");
    }
}
