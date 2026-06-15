package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response payload for the transaction-add (create) screen.
 *
 * <p>Returned as JSON by {@code TransactionController} for {@code POST /api/transactions} and
 * produced by {@code TransactionAddService}. The service assigns the new transaction's identifier
 * by browsing the transaction store to the end and incrementing the last key; because the add
 * request carries no identifier input, this response is where the caller first sees the
 * <strong>auto-generated {@code transactionId}</strong>.</p>
 *
 * <p>The remaining components echo the fields the caller submitted (account, card, type/category
 * codes, amount, dates, and merchant details) so the client can re-render the confirmed
 * transaction, mirroring the COBOL redisplay behaviour. The {@code confirm} flag carries the
 * screen's confirmation indicator and {@code errorMessage} carries any user-facing error text.</p>
 *
 * <p>The monetary {@code amount} is modelled as {@link java.math.BigDecimal} (scale 2) to preserve
 * the exact fixed-point decimal precision of the originating COBOL money field; floating-point
 * types are deliberately not used.</p>
 *
 * <p>Migrated from the COBOL BMS symbolic map {@code COTRN02} (online program {@code COTRN02C}).
 * Screen chrome (title lines, program name, current date/time), field attribute/length/flag
 * subfields, and PF-key legends are intentionally omitted. Source lineage: commit {@code 27d6c6f}.</p>
 *
 * @param transactionId service-generated transaction identifier surfaced to the caller
 *                      (auto-assigned by {@code COTRN02C} browse-to-end + increment; no map input field)
 * @param accountId     account number for the transaction (COTRN02 {@code ACTIDIN}, PIC X(11))
 * @param cardNumber    card number for the transaction (COTRN02 {@code CARDNIN}, PIC X(16))
 * @param typeCode      transaction type code (COTRN02 {@code TTYPCD}, PIC X(2))
 * @param categoryCode  transaction category code (COTRN02 {@code TCATCD}, PIC X(4))
 * @param source        transaction source (COTRN02 {@code TRNSRC}, PIC X(10))
 * @param description   transaction description (COTRN02 {@code TDESC}, PIC X(60))
 * @param amount        transaction amount, scale 2 (COTRN02 {@code TRNAMT}, PIC X(12), money)
 * @param originDate    transaction origination date (COTRN02 {@code TORIGDT}, PIC X(10))
 * @param processDate   transaction processing date (COTRN02 {@code TPROCDT}, PIC X(10))
 * @param merchantId    merchant identifier (COTRN02 {@code MID}, PIC X(9))
 * @param merchantName  merchant name (COTRN02 {@code MNAME}, PIC X(30))
 * @param merchantCity  merchant city (COTRN02 {@code MCITY}, PIC X(25))
 * @param merchantZip   merchant ZIP code (COTRN02 {@code MZIP}, PIC X(10))
 * @param confirm       confirmation indicator (COTRN02 {@code CONFIRM}, PIC X(1))
 * @param errorMessage  error message for the user (COTRN02 {@code ERRMSG}, PIC X(78))
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
