package com.cardemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bill Payment Request DTO — captures the bill payment input fields from the
 * BMS symbolic map COBIL00.CPY (bill payment screen).
 *
 * <p>Used by {@code BillingController.POST /api/billing/pay} as a
 * {@code @RequestBody @Valid} parameter.
 *
 * <h3>Source COBOL Field Mapping (COBIL0AI input view):</h3>
 * <ul>
 *   <li>{@code accountId} ← ACTIDINI PIC X(11) (COBIL00.CPY line 60)
 *       — 11-character account identifier, mandatory for bill payment</li>
 *   <li>{@code confirmIndicator} ← CONFIRMI PIC X(1) (COBIL00.CPY line 72)
 *       — single character: 'Y' to confirm, 'N' to cancel; may be null/empty
 *       on initial request before the confirmation step</li>
 * </ul>
 *
 * <h3>Bill Payment Flow (from COBIL00C.cbl):</h3>
 * <ol>
 *   <li>User submits account ID (ACTIDINI)</li>
 *   <li>Program reads ACCTDAT by account ID and displays current balance</li>
 *   <li>User must enter 'Y' in CONFIRMI to confirm payment</li>
 *   <li>On confirmation, account balance is updated and a transaction record
 *       is created</li>
 * </ol>
 *
 * <p>Note: Current balance (CURBALI PIC X(14), COBIL00.CPY line 66) is an
 * output-only field returned in the response, not part of this request DTO.
 * No {@code BigDecimal} or floating-point fields are needed in this request;
 * the balance is read from the database during processing.
 */
public class BillPaymentRequest {

    /**
     * Account identifier for the bill payment.
     * Maps ACTIDINI PIC X(11) from COBIL00.CPY line 60.
     * Maximum 11 characters, must not be blank.
     */
    @NotBlank(message = "Account ID is required")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    private String accountId;

    /**
     * Confirmation indicator for the bill payment.
     * Maps CONFIRMI PIC X(1) from COBIL00.CPY line 72.
     * Single character: 'Y' to confirm payment, 'N' to cancel.
     * May be null or empty on the initial request (before the confirmation step).
     */
    @Size(max = 1, message = "Confirm indicator must be a single character")
    @Pattern(regexp = "^[YN]?$", message = "Confirm indicator must be Y or N")
    private String confirmIndicator;

    /**
     * No-args constructor required for Jackson deserialization
     * and Spring MVC request binding.
     */
    public BillPaymentRequest() {
        // Default constructor for framework use
    }

    /**
     * All-args constructor for programmatic construction and testing.
     *
     * @param accountId        the 11-character account identifier (ACTIDINI)
     * @param confirmIndicator the single-character confirmation indicator (CONFIRMI):
     *                         'Y' to confirm, 'N' to cancel, or null/empty for initial request
     */
    public BillPaymentRequest(String accountId, String confirmIndicator) {
        this.accountId = accountId;
        this.confirmIndicator = confirmIndicator;
    }

    /**
     * Returns the account identifier.
     *
     * @return the account ID (up to 11 characters), never blank when validated
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier.
     *
     * @param accountId the account ID (up to 11 characters)
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the confirmation indicator.
     *
     * @return 'Y' for confirm, 'N' for cancel, or null/empty for initial request
     */
    public String getConfirmIndicator() {
        return confirmIndicator;
    }

    /**
     * Sets the confirmation indicator.
     *
     * @param confirmIndicator 'Y' to confirm payment, 'N' to cancel,
     *                         or null/empty for initial request
     */
    public void setConfirmIndicator(String confirmIndicator) {
        this.confirmIndicator = confirmIndicator;
    }

    /**
     * Returns a string representation of this bill payment request,
     * including both the account ID and confirmation indicator fields.
     *
     * @return formatted string representation
     */
    @Override
    public String toString() {
        return "BillPaymentRequest{" +
                "accountId='" + accountId + '\'' +
                ", confirmIndicator='" + confirmIndicator + '\'' +
                '}';
    }
}
