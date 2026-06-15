package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Immutable response payload for the account-view (inquiry) endpoint
 * {@code GET /api/accounts/{id}}.
 *
 * <p>Mirrors the output/display fields of the {@code COACTVW} BMS symbolic map,
 * combining account (ACCTDAT), customer (CUSTDAT), and card cross-reference
 * (CXACAIX) data into a single read-only contract returned by
 * {@code AccountViewService} (originating program {@code COACTVWC}). Monetary
 * amounts are modeled with {@link java.math.BigDecimal} at scale 2 to preserve
 * the COBOL fixed-point precision of the {@code PIC S9(10)V99} balance fields;
 * every remaining field is a {@link String} to preserve the exact display
 * contract of the symbolic map. Screen-chrome fields (transaction name, titles,
 * date, time, program name) are intentionally excluded. Source commit 27d6c6f.</p>
 */
public record AccountViewResponse(
        String accountId,
        String accountStatus,
        String openDate,
        BigDecimal creditLimit,
        String expirationDate,
        BigDecimal cashCreditLimit,
        String reissueDate,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        String accountGroupId,
        BigDecimal currentCycleDebit,
        String customerId,
        String ssn,
        String dateOfBirth,
        String ficoScore,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String stateCode,
        String addressLine2,
        String zipCode,
        String city,
        String countryCode,
        String phoneNumber1,
        String governmentIssuedId,
        String phoneNumber2,
        String eftAccountId,
        String primaryCardHolderIndicator,
        String infoMessage,
        String errorMessage) {
}
