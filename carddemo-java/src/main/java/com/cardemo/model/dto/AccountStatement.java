package com.cardemo.model.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable result carrier emitted by the statement-generation pipeline's
 * assembly {@code ItemProcessor} and consumed by the statement
 * {@code ItemWriter}, reproducing the dual-format (plain-text + HTML) account
 * statement that the COBOL batch program {@code app/cbl/CBSTM03A.CBL}
 * (Statement Generator) produces for each card cross-reference.
 *
 * <h2>Why this type exists (cross-folder coordination contract)</h2>
 * <p>In {@code CBSTM03A} the main read loop ({@code 1000-MAINLINE}) walks the
 * {@code CARDXREF} file, joins each cross-reference to its customer
 * ({@code 2000-CUSTFILE-GET}) and account ({@code 3000-ACCTFILE-GET}), and then
 * assembles <em>two</em> outputs in lock-step: a fixed-width plain-text
 * statement written to {@code STMT-FILE} (an {@code FD} record of
 * {@code PIC X(80)}) and an HTML statement written to {@code HTML-FILE} (an
 * {@code FD} record of {@code PIC X(100)}). The migrated Spring Batch chunk
 * splits that single COBOL program into a <em>processor</em> that assembles both
 * bodies and a <em>writer</em> that persists them to their respective sinks (S3
 * objects in the target, per AAP &sect;0.4.1). This record is the hand-off
 * between them.</p>
 *
 * <p>The carrier is deliberately defined <strong>once</strong>, here in
 * {@code com.cardemo.model.dto}, and shared by
 * {@code com.cardemo.batch.processors} (producer &mdash;
 * {@code StatementProcessor}) and {@code com.cardemo.batch.writers} (consumer
 * &mdash; {@code StatementWriter}). It is intentionally <em>not</em> owned by the
 * processor package and must not be duplicated there: a single shared type keeps
 * the producer and the writer in lock-step on the exact set of values the writer
 * needs to emit the text and HTML statement objects without re-assembling them.
 * This mirrors the established pattern of {@link PostedTransactionResult} for the
 * daily-posting pipeline.</p>
 *
 * <h2>Relationship to the COBOL working storage</h2>
 * <p>The legacy program holds the in-memory transaction row in the
 * "altered reporting layout" copybook {@code COSTM01} ({@code 01 TRNX-RECORD}),
 * and accumulates the per-statement expense running total in
 * {@code WS-TOTAL-AMT} ({@code PIC S9(9)V99}). The {@link #totalExpense}
 * component carries that running total; the writer &mdash; not this carrier
 * &mdash; decides the destination object keys / file names for the two bodies.</p>
 *
 * <h2>Component contract</h2>
 * <ul>
 *   <li>{@link #accountId()} &mdash; the statement account id ({@code ACCT-ID},
 *       COBOL {@code PIC 9(11)}). Never {@code null}.</li>
 *   <li>{@link #cardNumber()} &mdash; the 16-digit card number
 *       ({@code XREF-CARD-NUM}) whose transactions populate this statement.
 *       Never {@code null}.</li>
 *   <li>{@link #textBody()} &mdash; the assembled plain-text statement: the
 *       {@code STMT-FILE} lines (each an 80-column {@code PIC X(80)} record)
 *       joined with {@code '\n'}. Never {@code null}; never empty (the banner,
 *       name/address and basic-details sections are always present, even for an
 *       account with no transactions).</li>
 *   <li>{@link #htmlBody()} &mdash; the assembled HTML statement: a complete
 *       {@code <!DOCTYPE html>} document mirroring the {@code HTML-FILE} output.
 *       Never {@code null}; never empty.</li>
 *   <li>{@link #totalExpense()} &mdash; the running transaction total
 *       ({@code WS-TOTAL-AMT}, {@code PIC S9(9)V99}) accumulated across the
 *       card's transactions. {@link BigDecimal#ZERO} when the account has no
 *       transactions. Never {@code null}.</li>
 * </ul>
 *
 * <h2>Immutability and thread-safety</h2>
 * <p>As a {@code record} this carrier is shallowly immutable: its references are
 * {@code final} and it exposes no setters, so it is safe to pass between Spring
 * Batch threads. The component order &mdash; {@code (accountId, cardNumber,
 * textBody, htmlBody, totalExpense)} &mdash; is the stable construction contract
 * used by {@code StatementProcessor}.</p>
 *
 * <p><strong>Decimal precision (AAP &sect;0.7.3).</strong> {@link #totalExpense}
 * is a {@link BigDecimal} &mdash; never {@code float}/{@code double} &mdash;
 * preserving the exact scale of the COBOL packed-decimal {@code WS-TOTAL-AMT}.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @param accountId    the statement account id ({@code ACCT-ID}); never {@code null}
 * @param cardNumber   the 16-digit card number ({@code XREF-CARD-NUM}); never
 *                     {@code null}
 * @param textBody     the assembled plain-text statement (80-column lines joined
 *                     by {@code '\n'}); never {@code null}
 * @param htmlBody     the assembled HTML statement document; never {@code null}
 * @param totalExpense the running transaction total ({@code WS-TOTAL-AMT});
 *                     {@link BigDecimal#ZERO} when there are no transactions;
 *                     never {@code null}
 * @see PostedTransactionResult
 */
public record AccountStatement(
        Long accountId,
        String cardNumber,
        String textBody,
        String htmlBody,
        BigDecimal totalExpense) {

    /**
     * Compact canonical constructor enforcing the invariants that hold on
     * <em>every</em> statement {@code CBSTM03A} produces: one statement is
     * emitted per card cross-reference (never skipped), so the account id, card
     * number and both rendered bodies are always present. The running total is
     * defaulted to {@link BigDecimal#ZERO} when a caller passes {@code null},
     * matching the COBOL {@code MOVE 0 TO WS-TOTAL-AMT} initialization for an
     * account with no transactions.
     *
     * @throws NullPointerException if {@code accountId}, {@code cardNumber},
     *                              {@code textBody} or {@code htmlBody} is
     *                              {@code null}
     */
    public AccountStatement {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(textBody, "textBody must not be null");
        Objects.requireNonNull(htmlBody, "htmlBody must not be null");
        if (totalExpense == null) {
            // COBOL initializes WS-TOTAL-AMT to zero before each statement loop.
            totalExpense = BigDecimal.ZERO;
        }
    }
}
