package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response body for {@code GET /api/transactions/{id}}.
 *
 * <p>Mirrors the display fields of the {@code COTRN01} transaction-detail BMS
 * symbolic map. Produced by {@code TransactionDetailService} (the migrated
 * {@code COTRN01C} keyed-detail program) and serialized to JSON by
 * {@code TransactionController}. The map's screen-chrome and PF-key legend
 * fields ({@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
 * {@code TITLE02}, {@code CURTIME}) are intentionally excluded from this
 * contract. Source lineage: AWS CardDemo commit {@code 27d6c6f}.</p>
 *
 * <p>Per the migration decimal-precision rule, the monetary {@code amount}
 * (originating from {@code TRAN-AMT PIC S9(9)V99}) is represented as a
 * {@link java.math.BigDecimal} with scale 2 to preserve exact fixed-point
 * value; every other field maps to {@code String}.</p>
 *
 * @param transactionIdInput echoed key the caller supplied ({@code TRNIDIN}, {@code PIC X(16)})
 * @param transactionId      transaction identifier ({@code TRNID}, {@code PIC X(16)})
 * @param cardNumber         card number associated with the transaction ({@code CARDNUM}, {@code PIC X(16)})
 * @param typeCode           transaction type code ({@code TTYPCD}, {@code PIC X(2)})
 * @param categoryCode       transaction category code ({@code TCATCD}, {@code PIC X(4)})
 * @param source             transaction source ({@code TRNSRC}, {@code PIC X(10)})
 * @param description        transaction description ({@code TDESC}, {@code PIC X(60)})
 * @param amount             transaction amount ({@code TRNAMT}; {@code TRAN-AMT PIC S9(9)V99}, scale 2)
 * @param originDate         origination date ({@code TORIGDT}, {@code PIC X(10)})
 * @param processDate        processing date ({@code TPROCDT}, {@code PIC X(10)})
 * @param merchantId         merchant identifier ({@code MID}, {@code PIC X(9)})
 * @param merchantName       merchant name ({@code MNAME}, {@code PIC X(30)})
 * @param merchantCity       merchant city ({@code MCITY}, {@code PIC X(25)})
 * @param merchantZip        merchant ZIP code ({@code MZIP}, {@code PIC X(10)})
 * @param errorMessage       error/status message echoed to the caller ({@code ERRMSG}, {@code PIC X(78)})
 */
public record TransactionDetailResponse(
        String transactionIdInput,
        String transactionId,
        String cardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        BigDecimal amount,
        String originDate,
        String processDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String errorMessage) {
}
