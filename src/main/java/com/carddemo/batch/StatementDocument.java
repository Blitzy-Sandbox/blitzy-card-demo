package com.carddemo.batch;

import java.util.List;
import java.util.Objects;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;

/**
 * Immutable, batch-internal transfer object carrying ONE fully-assembled
 * account/card statement between the statement-generation processor and writer.
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This record is
 * the Java aggregate produced by the {@code 5000-CREATE-STATEMENT} paragraph of the legacy batch
 * program {@code CBSTM03A.CBL}. In the mainframe application {@code CBSTM03A} iterates the card
 * cross-reference file and, for each card, joins the {@code CUSTREC} customer record, the
 * {@code CVACT01Y} account record, the card, and the transactions returned by the file-access
 * subprogram {@code CBSTM03B.CBL} (migrated to {@code StatementFileService}), then emits the
 * assembled statement in <em>two</em> parallel output formats. This record holds everything the
 * writer needs to reproduce both:</p>
 * <ul>
 *   <li><strong>Plain text</strong> — COBOL {@code STMT-FILE} ({@code FD-STMTFILE-REC PIC X(80)},
 *       {@code LRECL=80}), delimited by the {@code START OF STATEMENT} / {@code END OF STATEMENT}
 *       banner lines ({@code ST-LINE0} &hellip; {@code ST-LINE15}); and</li>
 *   <li><strong>HTML</strong> — COBOL {@code HTML-FILE} ({@code FD-HTMLFILE-REC PIC X(100)},
 *       {@code LRECL=100}), the {@code <html><table>} layout written by the
 *       {@code 5100-WRITE-HTML-*} paragraphs.</li>
 * </ul>
 *
 * <p><strong>Why this type exists.</strong> Spring Batch needs a single typed item to flow from an
 * {@code ItemProcessor} ({@code StatementProcessor}) to an {@code ItemWriter}
 * ({@code StatementItemWriter}). The per-transaction line is already modelled by
 * {@link StatementTransactionDto} (migrated from the {@code COSTM01 TRNX-RECORD} layout), but there
 * is no aggregate for the whole statement, so this batch-internal record supplies it. It is a pure
 * data holder: it deliberately holds <em>no</em> repository or service references. The heavy
 * assembly (fetching account, customer, card and transactions) is performed upstream by
 * {@code StatementProcessor} via the constructor-injected {@code StatementFileService} bean — the
 * AAP &sect;0.4.3 mandate that the legacy {@code CALL 'CBSTM03B'} linkage becomes Spring dependency
 * injection.</p>
 *
 * <p><strong>Structured and rendered data are both retained.</strong> The record keeps the
 * structured inputs ({@link #transactionLines} plus the {@link #cardXref}, {@link #account},
 * {@link #customer} and {@link #card} entities) alongside the pre-rendered {@link #textLines} and
 * {@link #htmlLines}. Rendering is expected to happen in the processor so the writer stays
 * I/O-only, but retaining the structured fields gives a downstream writer the flexibility to
 * re-render if required. The writer pads or truncates each rendered line to the fixed record
 * length of its format ({@code 80} for text, {@code 100} for HTML) and uses {@link #cardNumber()}
 * to name the destination S3 object key (versioned S3 objects replace the legacy GDG statement
 * generations, AAP &sect;0.8.5).</p>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.8.2).</strong> This aggregate carries no monetary value
 * of its own. All financial amounts live inside {@link StatementTransactionDto#amount()} as a
 * {@link java.math.BigDecimal} of scale {@code 2}; {@code float}/{@code double} are never used for
 * money anywhere in the statement pipeline.</p>
 *
 * <p><strong>Immutability and thread-safety.</strong> As a {@code record} every component is
 * {@code final} and there are no setters. The compact canonical constructor rejects a {@code null}
 * driving {@link #cardXref} and defensively wraps the three collections with {@link List#copyOf}
 * (mapping {@code null} to an empty list), so the three list components are deeply immutable and
 * reject {@code null} elements. The resulting instance is safe to share across batch worker threads
 * without additional synchronization.</p>
 *
 * @param cardXref         the driving card cross-reference row
 *                         (card&nbsp;&rarr;&nbsp;account&nbsp;&rarr;&nbsp;customer navigation
 *                         key); must not be {@code null}. Origin: {@code CVACT03Y CARD-XREF-RECORD}
 *                         iterated by {@code CBSTM03A}.
 * @param account          the account whose balance and identifiers head the statement
 *                         ({@code ACCT-ID}, {@code ACCT-CURR-BAL}); origin {@code CVACT01Y}.
 * @param customer         the account holder whose name, address and FICO score populate the
 *                         statement header; origin {@code CUSTREC}.
 * @param card             the physical card the statement is generated for; origin
 *                         {@code CVACT02Y}.
 * @param transactionLines the per-card transaction lines assembled by
 *                         {@code StatementFileService.buildStatementLines(cardNumber)}; never
 *                         {@code null} after construction (a {@code null} argument becomes an empty
 *                         list). Each element maps to a {@code COSTM01 TRNX-RECORD}.
 * @param textLines        the pre-rendered plain-text statement lines (each {@code <= 80}
 *                         characters; the writer pads/truncates to {@code LRECL=80}); never
 *                         {@code null} after construction.
 * @param htmlLines        the pre-rendered HTML statement lines (each {@code <= 100} characters;
 *                         the writer pads/truncates to {@code LRECL=100}); never {@code null} after
 *                         construction.
 */
public record StatementDocument(
        CardXref cardXref,
        Account account,
        Customer customer,
        Card card,
        List<StatementTransactionDto> transactionLines,
        List<String> textLines,
        List<String> htmlLines) {

    /**
     * Compact canonical constructor enforcing this record's non-null and deep-immutability
     * invariants.
     *
     * <p>The driving {@link #cardXref} is mandatory: it supplies the statement's card&nbsp;&rarr;
     * account&nbsp;&rarr;customer navigation key and the S3 object name via {@link #cardNumber()},
     * so a {@code null} would leave the document unaddressable and is rejected eagerly. The three
     * list components are defensively re-wrapped with {@link List#copyOf} to guarantee the record
     * cannot be mutated through a caller-retained reference; because {@link List#copyOf} rejects a
     * {@code null} argument, each list is first coalesced to {@link List#of()} (an empty immutable
     * list) so that {@code null} is accepted and normalized to "no lines" rather than throwing.
     * {@link List#copyOf} additionally rejects {@code null} elements, which is desirable — a
     * statement must never contain a {@code null} transaction or rendered line. The remaining
     * entity components ({@link #account}, {@link #customer}, {@link #card}) are accepted verbatim;
     * they may legitimately be {@code null} while a statement is partially assembled.</p>
     *
     * @throws NullPointerException if {@code cardXref} is {@code null}, or if any element of a
     *                              supplied list is {@code null}.
     */
    public StatementDocument {
        Objects.requireNonNull(cardXref, "cardXref must not be null");
        transactionLines = (transactionLines == null) ? List.of() : List.copyOf(transactionLines);
        textLines = (textLines == null) ? List.of() : List.copyOf(textLines);
        htmlLines = (htmlLines == null) ? List.of() : List.copyOf(htmlLines);
    }

    /**
     * Returns the statement's card number, taken from the driving cross-reference row.
     *
     * <p>This convenience accessor delegates to {@link CardXref#getXrefCardNum()} (COBOL
     * {@code XREF-CARD-NUM PIC X(16)}). The statement writer uses it to derive the destination S3
     * object key for the generated statement, mirroring the per-card statement datasets produced
     * by {@code CBSTM03A}.</p>
     *
     * @return the 16-character card number of this statement; never {@code null} because
     *         {@link #cardXref} is a required, non-null component (though the underlying field may
     *         itself be {@code null} only if the cross-reference row was constructed without a card
     *         number).
     */
    public String cardNumber() {
        return cardXref.getXrefCardNum();
    }
}
