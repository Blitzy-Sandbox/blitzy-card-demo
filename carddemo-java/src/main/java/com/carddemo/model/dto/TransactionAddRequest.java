package com.carddemo.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transactions} (transaction add).
 *
 * <p>Immutable DTO mirroring the <em>input/editable</em> fields of the {@code COTRN02}
 * BMS symbolic map ({@code app/cpy-bms/COTRN02.CPY}); consumed by
 * {@code com.carddemo.service.transaction.TransactionAddService} (migrated from COBOL
 * program {@code COTRN02C}, source commit {@code 27d6c6f}). Field-edit semantics from
 * {@code app/cpy/CSSETATY.cpy} are expressed here as Jakarta Bean Validation constraints.</p>
 *
 * <p>Screen chrome, the response-only {@code ERRMSG} field, and PF-key/attribute fields are
 * intentionally excluded. The transaction identifier is <strong>not</strong> an input: the
 * service auto-generates it (browse-to-end then increment, Factory pattern), so this contract
 * carries no transaction-id component.</p>
 *
 * <p>The monetary {@code amount} (COBOL {@code TRAN-AMT PIC S9(9)V99}) is modeled as
 * {@link java.math.BigDecimal} to preserve exact fixed-point decimal precision; floating-point
 * types are prohibited for financial values. Every {@code String} component is length-bounded
 * to match its originating BMS field width.</p>
 *
 * @param accountId    target account identifier (COBOL {@code ACTIDIN}, X(11)); numeric, 1-11 digits
 * @param cardNumber   card number (COBOL {@code CARDNIN}, X(16)); numeric, 1-16 digits
 * @param typeCode     transaction type code (COBOL {@code TTYPCD}, X(2))
 * @param categoryCode transaction category code (COBOL {@code TCATCD}, X(4))
 * @param source       transaction source (COBOL {@code TRNSRC}, X(10))
 * @param description  transaction description (COBOL {@code TDESC}, X(60))
 * @param amount       transaction amount (COBOL {@code TRNAMT} / {@code TRAN-AMT PIC S9(9)V99}); required
 * @param originDate   transaction origin date (COBOL {@code TORIGDT}, X(10))
 * @param processDate  transaction process date (COBOL {@code TPROCDT}, X(10))
 * @param merchantId   merchant identifier (COBOL {@code MID}, X(9))
 * @param merchantName merchant name (COBOL {@code MNAME}, X(30))
 * @param merchantCity merchant city (COBOL {@code MCITY}, X(25))
 * @param merchantZip  merchant ZIP code (COBOL {@code MZIP}, X(10))
 * @param confirm      confirmation flag for the two-step add flow (COBOL {@code CONFIRM}, X(1))
 */
public record TransactionAddRequest(
        @NotBlank @Pattern(regexp = "\\d{1,11}") @Size(max = 11) String accountId,
        @NotBlank @Pattern(regexp = "\\d{1,16}") @Size(max = 16) String cardNumber,
        @Size(max = 2) String typeCode,
        @Size(max = 4) String categoryCode,
        @Size(max = 10) String source,
        @Size(max = 60) String description,
        @NotNull @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Size(max = 10) String originDate,
        @Size(max = 10) String processDate,
        @Size(max = 9) String merchantId,
        @Size(max = 30) String merchantName,
        @Size(max = 25) String merchantCity,
        @Size(max = 10) String merchantZip,
        @Size(max = 1) String confirm) {
}
