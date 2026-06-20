package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Transaction-add response/confirmation returned by {@code POST /api/transactions}.
 *
 * <p>Produced by {@code TransactionAddService} (re-platformed from the COBOL online
 * program {@code COTRN02C}) after a transaction is created. The DTO mirrors the
 * {@code COTRN02} BMS symbolic-map output view: it echoes the submitted transaction
 * fields and adds the service-generated {@code transactionId} (assigned via the
 * COBOL browse-to-end + increment sequence) together with an {@code errorMessage}
 * for redisplay. Screen chrome, PF-key legends, and attribute bytes from the map are
 * intentionally excluded. Lineage: AWS CardDemo source commit {@code 27d6c6f}.
 *
 * <p>Monetary fields use {@link java.math.BigDecimal} (scale 2) to preserve the exact
 * fixed-point precision of the originating COBOL {@code PIC} clause; floating-point
 * types are never used for money.
 *
 * @param transactionId auto-generated transaction identifier assigned by the service
 *                      (COTRN02C browse-to-end + increment; absent from the add request)
 * @param accountId     account identifier (COTRN02 {@code ACTIDIN}, 11 chars)
 * @param cardNumber    card number (COTRN02 {@code CARDNIN}, 16 chars)
 * @param typeCode      transaction type code (COTRN02 {@code TTYPCD}, 2 chars)
 * @param categoryCode  transaction category code (COTRN02 {@code TCATCD}, 4 chars)
 * @param source        transaction source (COTRN02 {@code TRNSRC}, 10 chars)
 * @param description   transaction description (COTRN02 {@code TDESC}, 60 chars)
 * @param amount        transaction amount, scale 2 (COTRN02 {@code TRNAMT})
 * @param originDate    transaction origination date (COTRN02 {@code TORIGDT}, 10 chars)
 * @param processDate   transaction processing date (COTRN02 {@code TPROCDT}, 10 chars)
 * @param merchantId    merchant identifier (COTRN02 {@code MID}, 9 chars)
 * @param merchantName  merchant name (COTRN02 {@code MNAME}, 30 chars)
 * @param merchantCity  merchant city (COTRN02 {@code MCITY}, 25 chars)
 * @param merchantZip   merchant ZIP code (COTRN02 {@code MZIP}, 10 chars)
 * @param confirm       confirmation flag (COTRN02 {@code CONFIRM}, 1 char)
 * @param errorMessage  error/status message for redisplay (COTRN02 {@code ERRMSG}, 78 chars)
 */
public record TransactionAddResponse(
        String transactionId,
        String accountId,
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
        String confirm,
        String errorMessage) {
}
