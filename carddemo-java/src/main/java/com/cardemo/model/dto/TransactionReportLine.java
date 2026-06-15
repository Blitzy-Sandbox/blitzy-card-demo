package com.cardemo.model.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable detail-row carrier emitted by the transaction-report pipeline's
 * per-record {@code ItemProcessor}
 * ({@code com.cardemo.batch.processors.TransactionReportProcessor}) and consumed
 * by the {@code com.cardemo.batch.writers} report writer (which formats detail
 * lines plus page/account/grand totals and writes them to S3) and/or
 * {@code com.cardemo.batch.jobs}, reproducing the printed detail line the COBOL
 * batch program {@code app/cbl/CBTRN03C.cbl} (Transaction Detail Report) builds
 * in its {@code 1120-WRITE-DETAIL} paragraph.
 *
 * <h2>Why this type exists (cross-folder coordination contract)</h2>
 * <p>{@code CBTRN03C} browses the {@code TRANSACT} file sequentially and, for
 * every transaction whose processing date falls within the requested
 * {@code [WS-START-DATE, WS-END-DATE]} window, enriches the row with three keyed
 * lookups &mdash; the owning account id ({@code 1500-A-LOOKUP-XREF}), the
 * transaction-type description ({@code 1500-B-LOOKUP-TRANTYPE}) and the
 * transaction-category description ({@code 1500-C-LOOKUP-TRANCATG}) &mdash; and
 * then writes a formatted detail line ({@code 1120-WRITE-DETAIL}). In the
 * migrated, chunk-oriented Spring Batch step that single COBOL loop is split
 * across components: a <em>processor</em> that filters by date and performs the
 * per-row enrichment, and <em>writer/job</em> components that format the line
 * and maintain the page, account and grand totals.</p>
 *
 * <p>Because a chunk-oriented {@code ItemProcessor} processes one item in
 * isolation and cannot observe the cross-record state COBOL keeps in
 * {@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL} and {@code WS-GRAND-TOTAL}, the
 * totals and pagination are intentionally <strong>not</strong> computed by the
 * processor; instead the processor emits this carrier per qualifying row,
 * exposing exactly the eight detail-line fields the writer needs to render the
 * row and to accumulate the per-page subtotal, the per-account (card-number
 * break) subtotal and the end-of-file grand total.</p>
 *
 * <p>The carrier is deliberately defined <strong>once</strong>, here in
 * {@code com.cardemo.model.dto}, and shared by the producer
 * ({@code com.cardemo.batch.processors.TransactionReportProcessor}) and the
 * consumers ({@code com.cardemo.batch.writers}/{@code com.cardemo.batch.jobs}).
 * It is intentionally <em>not</em> owned by the processor package and must not be
 * duplicated there: a single shared type keeps producer and consumer in lock-step
 * on the exact set of values the report needs &mdash; mirroring the sibling
 * {@link InterestCalculationResult} and {@link PostedTransactionResult} carriers
 * used by the interest-calculation and daily-posting pipelines. The integrating
 * writer/job agent should reuse this type rather than redefining it.</p>
 *
 * <h2>Detail-line field contract ({@code CBTRN03C} {@code 1120-WRITE-DETAIL})</h2>
 * <p>The component list and order reproduce the COBOL detail layout exactly
 * (paragraph {@code 1120-WRITE-DETAIL} of {@code CBTRN03C}):</p>
 * <ul>
 *   <li>{@link #tranId()} &mdash; {@code TRAN-ID} ({@code PIC X(16)}); the
 *       16-character transaction identifier. Never {@code null}.</li>
 *   <li>{@link #accountId()} &mdash; {@code XREF-ACCT-ID} resolved from the card
 *       cross-reference ({@code 1500-A-LOOKUP-XREF}); the owning account id and
 *       the key on which the writer takes its per-account subtotal break. This is
 *       <strong>not</strong> a transaction field &mdash; it comes from the XREF
 *       lookup. Never {@code null}.</li>
 *   <li>{@link #tranTypeCd()} &mdash; {@code TRAN-TYPE-CD} ({@code PIC X(02)}); the
 *       two-character transaction-type code.</li>
 *   <li>{@link #tranTypeDesc()} &mdash; {@code TRAN-TYPE-DESC} ({@code PIC X(50)})
 *       resolved from {@code TRANTYPE} ({@code 1500-B-LOOKUP-TRANTYPE}).</li>
 *   <li>{@link #tranCatCd()} &mdash; {@code TRAN-CAT-CD} ({@code PIC 9(04)}); the
 *       four-digit numeric category code.</li>
 *   <li>{@link #tranCatTypeDesc()} &mdash; {@code TRAN-CAT-TYPE-DESC}
 *       ({@code PIC X(50)}) resolved from {@code TRANCATG}
 *       ({@code 1500-C-LOOKUP-TRANCATG}).</li>
 *   <li>{@link #tranSource()} &mdash; {@code TRAN-SOURCE} ({@code PIC X(10)}); the
 *       ten-character provenance token, kept as a raw {@link String} (no enum
 *       interpretation), matching the {@code Transaction} entity contract.</li>
 *   <li>{@link #tranAmt()} &mdash; {@code TRAN-AMT} ({@code PIC S9(09)V99}); the
 *       signed monetary amount, a {@link BigDecimal} of scale&nbsp;2. This is the
 *       value the writer sums into the page, account and grand totals.
 *       <strong>No {@code float}/{@code double}</strong> is used anywhere
 *       (AAP &sect;0.7.3); compare with {@link BigDecimal#compareTo(BigDecimal)},
 *       never {@link BigDecimal#equals(Object)}. Never {@code null}.</li>
 * </ul>
 *
 * <h2>Immutability and thread-safety</h2>
 * <p>As a {@code record} this carrier is shallowly immutable: its components are
 * {@code final} and it exposes no setters, so it is safe to pass between Spring
 * Batch threads. The component order &mdash; {@code (tranId, accountId,
 * tranTypeCd, tranTypeDesc, tranCatCd, tranCatTypeDesc, tranSource, tranAmt)}
 * &mdash; is the stable construction contract used by the processor and mirrors
 * the COBOL {@code 1120-WRITE-DETAIL} field order.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository (AAP &sect;0.7.2).</p>
 *
 * @param tranId          the transaction id ({@code TRAN-ID}); never {@code null}
 * @param accountId       the owning account id ({@code XREF-ACCT-ID} from the XREF
 *                        lookup, not a transaction field); never {@code null}
 * @param tranTypeCd      the transaction-type code ({@code TRAN-TYPE-CD})
 * @param tranTypeDesc    the transaction-type description ({@code TRAN-TYPE-DESC})
 * @param tranCatCd       the transaction-category code ({@code TRAN-CAT-CD})
 * @param tranCatTypeDesc the transaction-category description
 *                        ({@code TRAN-CAT-TYPE-DESC})
 * @param tranSource      the transaction source ({@code TRAN-SOURCE})
 * @param tranAmt         the transaction amount ({@code TRAN-AMT}, scale&nbsp;2);
 *                        never {@code null}
 * @see InterestCalculationResult
 * @see PostedTransactionResult
 */
public record TransactionReportLine(
        String tranId,
        Long accountId,
        String tranTypeCd,
        String tranTypeDesc,
        Integer tranCatCd,
        String tranCatTypeDesc,
        String tranSource,
        BigDecimal tranAmt) {

    /**
     * Compact canonical constructor enforcing the three invariants that hold for
     * <em>every</em> detail line {@code CBTRN03C} writes: the {@link #tranId()}
     * (the row's identity), the {@link #accountId()} (the key on which the writer
     * takes its per-account subtotal break) and the {@link #tranAmt()} (the value
     * summed into the page, account and grand totals) are always present. The
     * remaining fields (the two codes, the two descriptions and the source token)
     * are rendered as-is by the writer &mdash; a blank value renders blank, exactly
     * as COBOL renders spaces &mdash; and are therefore not constrained here.
     *
     * @throws NullPointerException if {@code tranId}, {@code accountId} or
     *                              {@code tranAmt} is {@code null}
     */
    public TransactionReportLine {
        Objects.requireNonNull(tranId,
                "tranId must not be null (every detail line carries TRAN-ID)");
        Objects.requireNonNull(accountId,
                "accountId must not be null (XREF-ACCT-ID drives the per-account subtotal break)");
        Objects.requireNonNull(tranAmt,
                "tranAmt must not be null (TRAN-AMT is summed into page/account/grand totals)");
    }
}
