package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Immutable response payload for the transaction-detail online flow.
 *
 * <p>This DTO is the JSON body returned by {@code GET /api/transactions/{id}} on
 * {@code com.carddemo.controller.TransactionController} and is assembled by
 * {@code com.carddemo.service.transaction.TransactionDetailService}. It mirrors the display
 * fields of the {@code COTRN01} BMS symbolic map ({@code app/cpy-bms/COTRN01.CPY}) used by the
 * online transaction-detail program {@code COTRN01C} from source commit {@code 27d6c6f}
 * ({@code CardDemo_v1.0-15-g27d6c6f-68}); the COBOL lineage is reference only and no COBOL is
 * copied into the target.</p>
 *
 * <p>Only the user-facing data fields of the symbolic map are carried across the migration.
 * Screen chrome (screen titles, program name, current date and time) and the PF-key, length,
 * and attribute fields of the original 3270 map have no equivalent in the stateless REST
 * contract and are intentionally omitted. The {@code errorMessage} component conveys the
 * screen's {@code ERRMSG} feedback line for validation or not-found messaging.</p>
 *
 * <p>Every textual field maps to a fixed-width alphanumeric {@code PIC X(n)} clause and is
 * represented as a {@link String}. The monetary {@code amount} field maps to {@code TRNAMT}
 * (originating from {@code TRAN-AMT PIC S9(9)V99}) and is represented as a
 * {@link java.math.BigDecimal} carrying a scale of {@code 2} to preserve exact fixed-point
 * decimal precision; floating-point types are never used for monetary values.</p>
 *
 * @param transactionIdInput the echoed transaction-id key as supplied on the request
 *                           ({@code TRNIDIN}, {@code PIC X(16)})
 * @param transactionId      the resolved transaction identifier ({@code TRNID}, {@code PIC X(16)})
 * @param cardNumber         the card number associated with the transaction
 *                           ({@code CARDNUM}, {@code PIC X(16)})
 * @param typeCode           the transaction type code ({@code TTYPCD}, {@code PIC X(2)})
 * @param categoryCode       the transaction category code ({@code TCATCD}, {@code PIC X(4)})
 * @param source             the transaction source classification
 *                           ({@code TRNSRC}, {@code PIC X(10)})
 * @param description        the transaction description ({@code TDESC}, {@code PIC X(60)})
 * @param amount             the transaction amount as a fixed-point monetary value with scale 2
 *                           ({@code TRNAMT}, from {@code TRAN-AMT PIC S9(9)V99})
 * @param originDate         the transaction origination date ({@code TORIGDT}, {@code PIC X(10)})
 * @param processDate        the transaction processing date ({@code TPROCDT}, {@code PIC X(10)})
 * @param merchantId         the merchant identifier ({@code MID}, {@code PIC X(9)})
 * @param merchantName       the merchant name ({@code MNAME}, {@code PIC X(30)})
 * @param merchantCity       the merchant city ({@code MCITY}, {@code PIC X(25)})
 * @param merchantZip        the merchant postal code ({@code MZIP}, {@code PIC X(10)})
 * @param errorMessage       the screen error or feedback message
 *                           ({@code ERRMSG}, {@code PIC X(78)})
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
