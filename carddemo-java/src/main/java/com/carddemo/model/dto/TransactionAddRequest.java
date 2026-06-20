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
 * to match its originating BMS field width, and the constraints reproduce the
 * {@code COTRN02C VALIDATE-INPUT-DATA-FIELDS} edits exactly: the type code, category code, and
 * merchant id are required and numeric; the source, description, merchant name, and merchant
 * city are required; the origin and process dates are required and formatted {@code YYYY-MM-DD};
 * the merchant ZIP is required (the source applies no numeric edit to it); and the confirmation
 * flag is optional and, when present, must be {@code Y} or {@code N} — an empty value means
 * "not yet confirmed" and drives the two-step confirm prompt, so it is deliberately not
 * {@code @NotBlank}.</p>
 *
 * @param accountId    target account identifier (COBOL {@code ACTIDIN}, X(11)); required, numeric 1-11 digits
 * @param cardNumber   card number (COBOL {@code CARDNIN}, X(16)); required, numeric 1-16 digits
 * @param typeCode     transaction type code (COBOL {@code TTYPCD}, X(2)); required, numeric
 * @param categoryCode transaction category code (COBOL {@code TCATCD}, X(4)); required, numeric
 * @param source       transaction source (COBOL {@code TRNSRC}, X(10)); required
 * @param description  transaction description (COBOL {@code TDESC}, X(60)); required
 * @param amount       transaction amount (COBOL {@code TRNAMT} / {@code TRAN-AMT PIC S9(9)V99}); required
 * @param originDate   transaction origin date (COBOL {@code TORIGDT}, X(10)); required, {@code YYYY-MM-DD}
 * @param processDate  transaction process date (COBOL {@code TPROCDT}, X(10)); required, {@code YYYY-MM-DD}
 * @param merchantId   merchant identifier (COBOL {@code MID}, X(9)); required, numeric
 * @param merchantName merchant name (COBOL {@code MNAME}, X(30)); required
 * @param merchantCity merchant city (COBOL {@code MCITY}, X(25)); required
 * @param merchantZip  merchant ZIP code (COBOL {@code MZIP}, X(10)); required (no numeric edit in source)
 * @param confirm      confirmation flag for the two-step add flow (COBOL {@code CONFIRM}, X(1));
 *                     optional, {@code Y} or {@code N} when present
 */
public record TransactionAddRequest(
        @NotBlank @Pattern(regexp = "\\d{1,11}") @Size(max = 11) String accountId,
        @NotBlank @Pattern(regexp = "\\d{1,16}") @Size(max = 16) String cardNumber,
        @NotBlank @Pattern(regexp = "\\d{1,2}") @Size(max = 2) String typeCode,
        @NotBlank @Pattern(regexp = "\\d{1,4}") @Size(max = 4) String categoryCode,
        @NotBlank @Size(max = 10) String source,
        @NotBlank @Size(max = 60) String description,
        @NotNull @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") @Size(max = 10) String originDate,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") @Size(max = 10) String processDate,
        @NotBlank @Pattern(regexp = "\\d{1,9}") @Size(max = 9) String merchantId,
        @NotBlank @Size(max = 30) String merchantName,
        @NotBlank @Size(max = 25) String merchantCity,
        @NotBlank @Size(max = 10) String merchantZip,
        @Pattern(regexp = "[YyNn]?") @Size(max = 1) String confirm) {
}
