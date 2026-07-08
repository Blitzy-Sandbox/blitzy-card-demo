package com.carddemo.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable, structured transfer object representing a single enriched transaction
 * detail row of the CardDemo transaction detail report (batch job
 * {@code TransactionReportJob}).
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This record is
 * the Java translation of the {@code TRANSACTION-DETAIL-REPORT} group produced by the
 * {@code 1120-WRITE-DETAIL} paragraph of the legacy batch program {@code CBTRN03C.CBL}. The
 * report layout it carries is defined by copybook {@code CVTRA07Y} (the {@code TRAN-REPORT-*}
 * fields). Each component below corresponds one-to-one to a {@code MOVE ... TO TRAN-REPORT-*}
 * statement in {@code 1120-WRITE-DETAIL}:</p>
 *
 * <pre>
 *   MOVE TRAN-ID            TO TRAN-REPORT-TRANS-ID    -&gt; transactionId
 *   MOVE XREF-ACCT-ID       TO TRAN-REPORT-ACCOUNT-ID  -&gt; accountId
 *   MOVE TRAN-TYPE-CD       TO TRAN-REPORT-TYPE-CD     -&gt; typeCode
 *   MOVE TRAN-TYPE-DESC     TO TRAN-REPORT-TYPE-DESC   -&gt; typeDescription
 *   MOVE TRAN-CAT-CD        TO TRAN-REPORT-CAT-CD      -&gt; categoryCode
 *   MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC    -&gt; categoryDescription
 *   MOVE TRAN-SOURCE        TO TRAN-REPORT-SOURCE      -&gt; source
 *   MOVE TRAN-AMT           TO TRAN-REPORT-AMT         -&gt; amount
 * </pre>
 *
 * <p><strong>Why structured (not a pre-formatted string).</strong> The downstream writer
 * ({@code TransactionReportItemWriter}) must reproduce the COBOL control-break totalling logic —
 * the page total ({@code WS-PAGE-TOTAL}), the per-account total ({@code WS-ACCOUNT-TOTAL}, broken
 * whenever {@link #accountId()} changes) and the grand total ({@code WS-GRAND-TOTAL}) — and must
 * emit fixed 133-byte report records ({@code TRANREPT}, {@code LRECL=133}). Both responsibilities
 * require the numeric {@link #amount()} and the {@link #accountId()} to remain first-class, typed
 * values rather than pre-rendered text.</p>
 *
 * <p><strong>Decimal fidelity (AAP §0.8.2).</strong> {@link #amount()} mirrors the COBOL
 * {@code TRAN-AMT PIC S9(09)V99} field (signed, two fractional digits) and is therefore modelled
 * as a {@link BigDecimal} — never {@code double} or {@code float}. The canonical constructor
 * defensively normalizes the value to scale {@code 2} using {@link RoundingMode#HALF_UP}, so a
 * constructed instance always reports {@code amount().scale() == 2}, preserving the COBOL packed
 * decimal precision and rounding behaviour at every boundary.</p>
 *
 * <p><strong>Immutability.</strong> As a {@code record}, every component is {@code final}, there
 * are no setters, and the compiler-generated accessors, {@code equals}, {@code hashCode} and
 * {@code toString} complete the value-type contract. Instances are safe to share across the batch
 * reader/processor/writer chunk boundary without defensive copying.</p>
 *
 * @param transactionId       the 16-character transaction identifier
 *                            ({@code TRAN-ID} &rarr; {@code TRAN-REPORT-TRANS-ID}).
 * @param accountId           the owning account id resolved through the CardXref lookup
 *                            ({@code XREF-ACCT-ID} &rarr; {@code TRAN-REPORT-ACCOUNT-ID}); the
 *                            control-break key for per-account totalling.
 * @param typeCode            the transaction type code
 *                            ({@code TRAN-TYPE-CD} &rarr; {@code TRAN-REPORT-TYPE-CD}).
 * @param typeDescription     the transaction type description, sourced from
 *                            {@code TransactionType.getTranTypeDesc()}
 *                            ({@code TRAN-TYPE-DESC} &rarr; {@code TRAN-REPORT-TYPE-DESC}).
 * @param categoryCode        the transaction category code
 *                            ({@code TRAN-CAT-CD} &rarr; {@code TRAN-REPORT-CAT-CD}).
 * @param categoryDescription the transaction category description, sourced from
 *                            {@code TransactionCategoryType.getTranCatTypeDesc()}
 *                            ({@code TRAN-CAT-TYPE-DESC} &rarr; {@code TRAN-REPORT-CAT-DESC}).
 * @param source              the transaction source
 *                            ({@code TRAN-SOURCE} &rarr; {@code TRAN-REPORT-SOURCE}).
 * @param amount              the transaction amount, always normalized to scale {@code 2}
 *                            ({@code TRAN-AMT PIC S9(09)V99} &rarr; {@code TRAN-REPORT-AMT}); must
 *                            not be {@code null}.
 */
public record ReportDetailLine(
        String transactionId,
        Long accountId,
        String typeCode,
        String typeDescription,
        Integer categoryCode,
        String categoryDescription,
        String source,
        BigDecimal amount) {

    /**
     * Canonical constructor enforcing the decimal-precision invariant of the COBOL
     * {@code TRAN-AMT PIC S9(09)V99} field.
     *
     * <p>{@code amount} is mandatory because it feeds the page, account and grand totals computed
     * by {@code TransactionReportItemWriter}; a {@code null} would corrupt those running sums, so
     * it is rejected eagerly. The value is then defensively re-scaled to two fractional digits
     * using {@link RoundingMode#HALF_UP}, guaranteeing byte-equivalent monetary formatting on the
     * fixed-width report line and reproducing COBOL rounding at the point of construction
     * (AAP §0.8.2).</p>
     *
     * @throws NullPointerException if {@code amount} is {@code null}.
     */
    public ReportDetailLine {
        Objects.requireNonNull(amount, "amount must not be null");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }
}
