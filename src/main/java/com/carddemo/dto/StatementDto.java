package com.carddemo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Statement reporting line mirroring the legacy {@code TRNX-RECORD} layout from
 * {@code app/cpy/COSTM01.CPY} @ {@code 27d6c6f}.
 */
public record StatementDto(
        @Size(max = 16) @Pattern(regexp = "\\d{0,16}") String cardNumber,
        @Size(max = 16) String transactionId,
        @Size(max = 2) @Pattern(regexp = "\\d{2}") String transactionType,
        @Size(max = 4) @Pattern(regexp = "\\d{1,4}") String categoryCode,
        @Size(max = 10) String source,
        @Size(max = 100) String description,
        @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Size(max = 9) @Pattern(regexp = "\\d{1,9}") String merchantId,
        @Size(max = 50) String merchantName,
        @Size(max = 50) String merchantCity,
        @Size(max = 10) String merchantZip,
        @Size(max = 26) String originTimestamp,
        @Size(max = 26) String processTimestamp
) {
}
