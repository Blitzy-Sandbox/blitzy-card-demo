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
 *
 * <p>The trailing {@code version} component exposes the JPA {@code @Version}
 * optimistic-lock token of the underlying account record (the Java equivalent of the
 * CICS {@code READ UPDATE} before-image). It is echoed from the last read so a client
 * can complete the documented read&rarr;modify&rarr;write cycle and recover from a
 * {@code 409 Conflict} by re-fetching the current version (see {@code api-contracts.md}
 * &sect;1.8 / &sect;404). Only read responses carry it; per &sect;452 the update
 * response intentionally does not echo {@code version}.</p>
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
        String errorMessage,
        Long version) {
}
