package com.cardemo.model.dto;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import java.util.Objects;

/**
 * Immutable result carrier emitted by the daily-transaction posting pipeline's
 * validation/mapping {@code ItemProcessor} and consumed by the posting
 * {@code ItemWriter}s, reproducing the routing decision that the COBOL batch
 * program {@code app/cbl/CBTRN02C.cbl} (Daily Transaction Posting) makes after
 * its {@code 1500-VALIDATE-TRAN} cascade.
 *
 * <h2>Why this type exists (cross-folder coordination contract)</h2>
 * <p>In {@code CBTRN02C} the main read loop validates each daily-transaction
 * record and then branches: when {@code WS-VALIDATION-FAIL-REASON = 0} it
 * performs {@code 2000-POST-TRANSACTION} (which maps the record and then updates
 * the category balance, the account and the transaction file); otherwise it
 * performs {@code 2500-WRITE-REJECT-REC}, which writes the original record plus
 * the validation trailer to the daily-reject file and <strong>continues</strong>
 * (the job sets {@code RETURN-CODE = 4} only at end-of-job, never abends on a
 * business reject). That single branch is reproduced in the migrated Spring
 * Batch chunk as: a processor that <em>validates and maps</em>, and writers that
 * <em>route and persist</em>. This record is the hand-off between them.</p>
 *
 * <p>The carrier is deliberately defined <strong>once</strong>, here in
 * {@code com.cardemo.model.dto}, and shared by
 * {@code com.cardemo.batch.processors} (producer &mdash;
 * {@code TransactionPostingProcessor}) and {@code com.cardemo.batch.writers}
 * /{@code com.cardemo.batch.jobs} (consumers). It is intentionally <em>not</em>
 * owned by the processor package, and must not be duplicated there: a single
 * shared type keeps the producer and the writer in lock-step on the exact set of
 * records the writer needs to perform the {@code 2700}/{@code 2800}/{@code 2900}
 * persistence without re-reading.</p>
 *
 * <h2>Component contract</h2>
 * <ul>
 *   <li>{@link #transaction()} &mdash; the posted {@link Transaction} produced by
 *       {@code 2000-POST-TRANSACTION}. Populated <strong>only on the accepted
 *       path</strong> ({@link #rejectCode()} == {@link RejectCode#NONE}); it is
 *       {@code null} when the record was rejected.</li>
 *   <li>{@link #rejectCode()} &mdash; the validation outcome. {@link RejectCode#NONE}
 *       when the transaction is accepted for posting; otherwise the failing reason
 *       ({@link RejectCode#INVALID_CARD_NUMBER 100},
 *       {@link RejectCode#ACCOUNT_NOT_FOUND 101},
 *       {@link RejectCode#OVERLIMIT_TRANSACTION 102} or
 *       {@link RejectCode#TRANSACTION_AFTER_EXPIRATION 103}). Never {@code null}.</li>
 *   <li>{@link #originalTransaction()} &mdash; the raw input
 *       {@link DailyTransaction}. Always present, on both the accept and reject
 *       paths, because the reject writer rebuilds the 350-byte reject record plus
 *       the 80-byte validation trailer ({@code 2500-WRITE-REJECT-REC},
 *       {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}) from it. Never
 *       {@code null}.</li>
 *   <li>{@link #account()} &mdash; the {@link Account} resolved during validation
 *       ({@code 1500-B-LOOKUP-ACCT}), passed through so the writer can apply the
 *       cycle-credit/debit and balance update ({@code 2800-UPDATE-ACCOUNT-REC})
 *       <em>without re-reading</em>, matching the COBOL reuse of the in-memory
 *       {@code ACCOUNT-RECORD}. May be {@code null} when the reject occurred
 *       before the account was resolved (codes {@code 100}/{@code 101}).</li>
 *   <li>{@link #crossReference()} &mdash; the {@link CardCrossReference} resolved
 *       in {@code 1500-A-LOOKUP-XREF}, passed through so the writer can key the
 *       category-balance update ({@code 2700-UPDATE-TCATBAL}, which uses
 *       {@code XREF-ACCT-ID}) without re-reading. May be {@code null} when the
 *       reject occurred before the cross-reference was resolved (code
 *       {@code 100}).</li>
 * </ul>
 *
 * <h2>Immutability and thread-safety</h2>
 * <p>As a {@code record} this carrier is shallowly immutable: its references are
 * {@code final} and it exposes no setters, so it is safe to pass between Spring
 * Batch threads. (The referenced JPA entities are themselves mutable; the
 * writers, not this carrier, own any subsequent mutation/persistence of them.)
 * The component order &mdash; {@code (transaction, rejectCode,
 * originalTransaction, account, crossReference)} &mdash; is the stable
 * construction contract used by the processor.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @param transaction         the mapped posted transaction; {@code null} on a reject
 * @param rejectCode          the validation outcome; never {@code null}
 *                            ({@link RejectCode#NONE} == accepted)
 * @param originalTransaction the raw daily-transaction input; never {@code null}
 * @param account             the resolved account, or {@code null} if not yet
 *                            resolved at reject time
 * @param crossReference      the resolved card cross-reference, or {@code null} if
 *                            not yet resolved at reject time
 * @see RejectCode
 * @see Transaction
 * @see DailyTransaction
 */
public record PostedTransactionResult(
        Transaction transaction,
        RejectCode rejectCode,
        DailyTransaction originalTransaction,
        Account account,
        CardCrossReference crossReference) {

    /**
     * Compact canonical constructor enforcing the two invariants that hold on
     * <em>every</em> path through {@code CBTRN02C}'s validate-then-route branch:
     * the validation outcome ({@link #rejectCode()}) and the raw input record
     * ({@link #originalTransaction()}) are always present. The remaining
     * components ({@link #transaction()}, {@link #account()},
     * {@link #crossReference()}) are legitimately {@code null} on the early-reject
     * paths and are therefore not constrained here.
     *
     * @throws NullPointerException if {@code rejectCode} or
     *                              {@code originalTransaction} is {@code null}
     */
    public PostedTransactionResult {
        Objects.requireNonNull(rejectCode,
                "rejectCode must not be null (use RejectCode.NONE for an accepted transaction)");
        Objects.requireNonNull(originalTransaction,
                "originalTransaction must not be null (required to build the reject record downstream)");
    }

    /**
     * Indicates that the transaction passed every validation and should be posted.
     *
     * <p>Mirrors the COBOL {@code IF WS-VALIDATION-FAIL-REASON = 0} branch
     * (CBTRN02C line 211) that routes a record to {@code 2000-POST-TRANSACTION}.</p>
     *
     * @return {@code true} when {@link #rejectCode()} is {@link RejectCode#NONE}
     */
    public boolean isAccepted() {
        return rejectCode == RejectCode.NONE;
    }

    /**
     * Indicates that the transaction failed validation and should be written to
     * the reject sink rather than posted.
     *
     * <p>Mirrors the COBOL {@code ELSE} branch (CBTRN02C lines 213-215) that
     * increments {@code WS-REJECT-COUNT} and performs
     * {@code 2500-WRITE-REJECT-REC}.</p>
     *
     * @return {@code true} for any non-{@link RejectCode#NONE} outcome
     */
    public boolean isRejected() {
        return rejectCode.isRejection();
    }
}
