package com.carddemo.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * REST data-transfer objects for the transaction list, detail, and add flows.
 *
 * <p>Field contracts are derived byte-accurately from the BMS symbolic maps
 * {@code COTRN00}, {@code COTRN01}, and {@code COTRN02} (AWS CardDemo) at source
 * commit {@code 27d6c6f}. The maximum sizes mirror the {@code PIC X(n)} widths
 * of the underlying symbolic map fields, monetary fields map to
 * {@link java.math.BigDecimal} with two-decimal scale, and the digit patterns
 * mirror the numeric-only screen fields.</p>
 *
 * <ul>
 *   <li>{@link ListResponse}/{@link TransactionSummary} &larr; {@code COTRN00} (CT00 / {@code COTRN00C}),
 *       consumed by {@code GET /api/transactions}.</li>
 *   <li>{@link Detail} &larr; {@code COTRN01} (CT01 / {@code COTRN01C}),
 *       consumed by {@code GET /api/transactions/{id}}.</li>
 *   <li>{@link AddRequest} &larr; {@code COTRN02} (CT02 / {@code COTRN02C}),
 *       consumed by {@code POST /api/transactions}.</li>
 * </ul>
 */
public final class TransactionDto {

    private TransactionDto() {
    }

    /**
     * One row of the transaction list. Derived from the {@code COTRN00} repeated
     * row fields {@code TRNIDnn X(16)}, {@code TDATEnn X(8)},
     * {@code TDESCnn X(26)}, and {@code TAMTnnn X(12)} at commit {@code 27d6c6f}.
     * The row-select field {@code SELnnnn} is excluded.
     *
     * @param transactionId the transaction identifier ({@code TRNIDnn}, max 16)
     * @param date          the transaction date ({@code TDATEnn}, max 8)
     * @param description   the transaction description ({@code TDESCnn}, max 26)
     * @param amount        the transaction amount ({@code TAMTnnn}); two-decimal scale
     */
    public record TransactionSummary(
            @Size(max = 16) String transactionId,
            @Size(max = 8) String date,
            @Size(max = 26) String description,
            @Digits(integer = 10, fraction = 2) BigDecimal amount
    ) {
    }

    /**
     * Transaction list response. Derived from the {@code COTRN00} screen fields
     * {@code PAGENUM X(8)} and {@code TRNIDIN X(16)} together with up to ten
     * repeated rows at commit {@code 27d6c6f}.
     *
     * @param pageNumber          the current page number ({@code PAGENUM}, max 8)
     * @param transactionIdFilter the transaction-id filter ({@code TRNIDIN}, max 16)
     * @param transactions        the rows on the current page (up to ten)
     */
    public record ListResponse(
            @Size(max = 8) String pageNumber,
            @Size(max = 16) String transactionIdFilter,
            List<@Valid TransactionSummary> transactions
    ) {
    }

    /**
     * Transaction detail response. Derived from the {@code COTRN01} display
     * fields {@code TRNID X(16)}, {@code CARDNUM X(16)}, {@code TTYPCD X(2)},
     * {@code TCATCD X(4)}, {@code TRNSRC X(10)}, {@code TDESC X(60)},
     * {@code TRNAMT X(12)}, {@code TORIGDT X(10)}, {@code TPROCDT X(10)},
     * {@code MID X(9)}, {@code MNAME X(30)}, {@code MCITY X(25)}, and
     * {@code MZIP X(10)} at commit {@code 27d6c6f}.
     *
     * @param transactionId   the transaction identifier ({@code TRNID}, max 16)
     * @param cardNumber      the card number ({@code CARDNUM}, max 16 digits)
     * @param transactionType the two-character transaction type code ({@code TTYPCD})
     * @param categoryCode    the transaction category code ({@code TCATCD}, max 4 digits)
     * @param source          the transaction source ({@code TRNSRC}, max 10)
     * @param description     the transaction description ({@code TDESC}, max 60)
     * @param amount          the transaction amount ({@code TRNAMT}); two-decimal scale
     * @param originDate      the origination date ({@code TORIGDT}, max 10)
     * @param processDate     the processing date ({@code TPROCDT}, max 10)
     * @param merchantId      the merchant identifier ({@code MID}, max 9 digits)
     * @param merchantName    the merchant name ({@code MNAME}, max 30)
     * @param merchantCity    the merchant city ({@code MCITY}, max 25)
     * @param merchantZip     the merchant ZIP code ({@code MZIP}, max 10)
     */
    public record Detail(
            @Size(max = 16) String transactionId,
            @Size(max = 16) @Pattern(regexp = "\\d{0,16}") String cardNumber,
            @Size(max = 2) @Pattern(regexp = "\\d{2}") String transactionType,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String categoryCode,
            @Size(max = 10) String source,
            @Size(max = 60) String description,
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 10) String originDate,
            @Size(max = 10) String processDate,
            @Size(max = 9) @Pattern(regexp = "\\d{0,9}") String merchantId,
            @Size(max = 30) String merchantName,
            @Size(max = 25) String merchantCity,
            @Size(max = 10) String merchantZip
    ) {
    }

    /**
     * Transaction add request body. Derived from the {@code COTRN02} input
     * fields {@code ACTIDIN X(11)}, {@code CARDNIN X(16)}, {@code TTYPCD X(2)},
     * {@code TCATCD X(4)}, {@code TRNSRC X(10)}, {@code TDESC X(60)},
     * {@code TRNAMT X(12)}, {@code TORIGDT X(10)}, {@code TPROCDT X(10)},
     * {@code MID X(9)}, {@code MNAME X(30)}, {@code MCITY X(25)},
     * {@code MZIP X(10)}, and {@code CONFIRM X(1)} at commit {@code 27d6c6f}.
     *
     * @param accountId    the account identifier ({@code ACTIDIN}, max 11 digits, required)
     * @param cardNumber   the card number ({@code CARDNIN}, max 16 digits)
     * @param typeCode     the two-character transaction type code ({@code TTYPCD})
     * @param categoryCode the transaction category code ({@code TCATCD}, max 4 digits)
     * @param source       the transaction source ({@code TRNSRC}, max 10)
     * @param description  the transaction description ({@code TDESC}, max 60)
     * @param amount       the transaction amount ({@code TRNAMT}); two-decimal scale
     * @param originDate   the origination date ({@code TORIGDT}, max 10)
     * @param processDate  the processing date ({@code TPROCDT}, max 10)
     * @param merchantId   the merchant identifier ({@code MID}, max 9 digits)
     * @param merchantName the merchant name ({@code MNAME}, max 30)
     * @param merchantCity the merchant city ({@code MCITY}, max 25)
     * @param merchantZip  the merchant ZIP code ({@code MZIP}, max 10)
     * @param confirm      the add-confirmation flag ({@code CONFIRM}, Y/N)
     */
    public record AddRequest(
            @NotBlank @Size(max = 11) @Pattern(regexp = "\\d{1,11}") String accountId,
            @Size(max = 16) @Pattern(regexp = "\\d{0,16}") String cardNumber,
            @Size(max = 2) @Pattern(regexp = "\\d{2}") String typeCode,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String categoryCode,
            @Size(max = 10) String source,
            @Size(max = 60) String description,
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 10) String originDate,
            @Size(max = 10) String processDate,
            @Size(max = 9) @Pattern(regexp = "\\d{0,9}") String merchantId,
            @Size(max = 30) String merchantName,
            @Size(max = 25) String merchantCity,
            @Size(max = 10) String merchantZip,
            @Size(max = 1) @Pattern(regexp = "[YyNn]?") String confirm
    ) {
    }
}
