package com.carddemo.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/**
 * Immutable data-transfer object representing a single <em>transaction list
 * row</em> as displayed on the legacy CardDemo Transaction List screen (BMS map
 * {@code COTRN00}, CICS transaction {@code CT00}).
 *
 * <p>Each instance mirrors one repeated row of the {@code COTRN00} symbolic map
 * (the {@code TRNIDnn} / {@code TDATEnn} / {@code TDESCnn} / {@code TAMTnnn}
 * group, rows {@code 01}&ndash;{@code 10}) and is one element of the paginated
 * {@code TransactionListResponse}. The underlying data originates from the VSAM
 * {@code TRAN-RECORD} layout defined by copybook {@code CVTRA05Y} (record
 * length 350).</p>
 *
 * <p>Field-level notes preserving legacy behavior:</p>
 * <ul>
 *   <li>{@code transactionId} preserves the fixed 16-character
 *       {@code TRAN-ID} / {@code TRNIDnn} width.</li>
 *   <li>{@code transactionDate} is intentionally kept as a {@link String} to
 *       preserve the exact 8-character screen date column (the {@code MM/DD/YY}
 *       form produced by {@code COTRN00C}'s {@code POPULATE-TRAN-DATA} from
 *       {@code TRAN-ORIG-TS}, for example {@code 06/15/24}); it is a display value
 *       and is deliberately <em>not</em> parsed into a {@code java.time.LocalDate}
 *       here.</li>
 *   <li>{@code description} carries the 26-character screen truncation of the
 *       100-character {@code TRAN-DESC} field shown on the list row.</li>
 *   <li>{@code amount} maps the packed-decimal {@code TRAN-AMT}
 *       ({@code PIC S9(09)V99}) to a {@link BigDecimal} with scale 2. Financial
 *       values never use {@code float}/{@code double}. The producing service is
 *       responsible for normalizing the value via
 *       {@code setScale(2, RoundingMode.HALF_UP)} before constructing this
 *       DTO.</li>
 * </ul>
 *
 * <p>This is a stateless, immutable holder; it carries no behavior beyond the
 * accessors generated for the record components.</p>
 *
 * @param transactionId   the 16-character transaction identifier
 *                        ({@code TRAN-ID} / {@code TRNIDnn}, {@code PIC X(16)})
 * @param transactionDate the 8-character display date column
 *                        ({@code TDATEnn}, {@code PIC X(8)}) in {@code MM/DD/YY}
 *                        form, derived from {@code TRAN-ORIG-TS}
 * @param description     the 26-character transaction description shown on the
 *                        list row ({@code TDESCnn}, a truncation of
 *                        {@code TRAN-DESC PIC X(100)})
 * @param amount          the transaction amount ({@code TRAN-AMT
 *                        PIC S9(09)V99}) as a {@link BigDecimal} of scale 2
 */
public record TransactionListItem(

        @Size(max = 16)
        String transactionId,

        @Size(max = 8)
        String transactionDate,

        @Size(max = 26)
        String description,

        @Digits(integer = 9, fraction = 2)
        BigDecimal amount) {
}
