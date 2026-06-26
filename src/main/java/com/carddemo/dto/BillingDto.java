package com.carddemo.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bill-payment request and response data transfer objects for the
 * {@code POST /api/billing/pay} endpoint.
 *
 * <p>Field contract derived byte-accurately from BMS mapset {@code COBIL00.bms}
 * and symbolic map copybook {@code COBIL00.CPY} (CICS transaction {@code CB00},
 * program {@code COBIL00C}) at source commit {@code 27d6c6f}. Only the three
 * business fields are modeled: {@code ACTIDIN} (account id input),
 * {@code CURBAL} (current balance display) and {@code CONFIRM} (Y/N flag).
 * Header chrome, the {@code ERRMSG} field and BMS map mechanics are intentionally
 * not modeled.</p>
 */
public final class BillingDto {

    private BillingDto() {
        // Non-instantiable container grouping the nested request/response records.
    }

    /**
     * Request body for {@code POST /api/billing/pay}.
     *
     * @param accountId account identifier from BMS field {@code ACTIDIN}
     *                  ({@code PIC X(11)}); kept as a {@code String} to preserve
     *                  any leading zeros
     * @param confirm   Y/N payment confirmation flag from BMS field
     *                  {@code CONFIRM} ({@code PIC X(1)})
     */
    public record PayRequest(
            // Constraints are intentionally relaxed (no @NotBlank, no exact-format
            // @Pattern) so an empty account id or an out-of-range confirm flag
            // reaches BillingService.payBill, which emits the byte-exact COBOL
            // COBIL00C field messages ("Acct ID can NOT be empty...",
            // "Invalid value. Valid values are (Y/N)...") in the legacy evaluation
            // order rather than a generic bean-validation message (AAP 0.7.1.1,
            // mirroring DECISION_LOG D-056/D-062).
            @Size(max = 11)
            @Pattern(regexp = "\\d{0,11}")
            String accountId,

            @Size(max = 1)
            String confirm
    ) {
    }

    /**
     * Response body for {@code POST /api/billing/pay}.
     *
     * @param accountId      echo of the paid account identifier from BMS field
     *                       {@code ACTIDIN} ({@code PIC X(11)})
     * @param currentBalance current account balance from BMS display field
     *                       {@code CURBAL} ({@code PIC X(14)}), realizing legacy
     *                       {@code ACCT-CURR-BAL PIC S9(10)V99}; a
     *                       {@code BigDecimal} carrying a scale of 2
     * @param confirm        echo of the confirmation flag from BMS field
     *                       {@code CONFIRM} ({@code PIC X(1)})
     * @param transactionId  identifier of the payment transaction written by a
     *                       successful payment ({@code TRAN-ID PIC X(16)});
     *                       {@code null} — and therefore omitted from the JSON
     *                       response — when no payment is written (the
     *                       confirm = {@code N} cancel path)
     * @param confirmationMessage the byte-exact COBOL success banner emitted by
     *                       {@code COBIL00C} after a successful payment
     *                       ({@code "Payment successful.  Your Transaction ID is
     *                       <id>."}); {@code null} — and therefore omitted from
     *                       the JSON response — on the cancel path
     */
    public record PayResponse(
            @Size(max = 11)
            @Pattern(regexp = "\\d{1,11}")
            String accountId,

            @Digits(integer = 10, fraction = 2)
            BigDecimal currentBalance,

            @Size(max = 1)
            String confirm,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Size(max = 16)
            String transactionId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            String confirmationMessage
    ) {
    }
}
