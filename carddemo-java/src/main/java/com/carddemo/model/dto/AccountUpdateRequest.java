package com.carddemo.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request payload for the account-update operation, accepted as the JSON body of
 * {@code PUT /api/accounts/{id}} by {@code com.carddemo.controller.AccountController}
 * and consumed by {@code com.carddemo.service.account.AccountUpdateService}
 * (the transactional dual ACCTDAT + CUSTDAT update with optimistic locking).
 *
 * <p>This DTO mirrors the <em>editable input</em> fields of the {@code COACTUP}
 * BMS symbolic map. Unlike the account-<em>view</em> contract, the update map
 * presents dates, the SSN, and phone numbers as <strong>split part fields</strong>
 * (year / month / day; SSN area / group / serial; phone area / prefix / line); the
 * split contract is preserved verbatim here so the wire format matches the 3270
 * input layout one-to-one.</p>
 *
 * <p>Field semantics:</p>
 * <ul>
 *   <li>The five monetary fields ({@code creditLimit}, {@code cashCreditLimit},
 *       {@code currentBalance}, {@code currentCycleCredit}, {@code currentCycleDebit})
 *       are modelled as {@link java.math.BigDecimal} with two-decimal scale enforced
 *       by {@link Digits} ({@code integer = 10, fraction = 2}), preserving the exact
 *       fixed-point precision of the originating COBOL PIC clauses; floating-point
 *       types are intentionally avoided.</li>
 *   <li>All remaining fields are {@link String} values whose {@link Size} maximum
 *       length matches the corresponding BMS field length exactly.</li>
 *   <li>{@code accountId} is required and must be a 1–11 digit numeric identifier.</li>
 *   <li>{@code version} is the optimistic-concurrency token the client must echo
 *       from the most recent account read. {@code AccountUpdateService} compares it
 *       against the persisted {@code Account.version} ({@code @Version}) before the
 *       transactional dual ACCTDAT + CUSTDAT update, reproducing the {@code COACTUPC}
 *       before/after-image check that guards the sole {@code SYNCPOINT ROLLBACK}. A
 *       mismatch raises {@code ConcurrencyException}, surfaced as HTTP
 *       {@code 409 Conflict} (AAP §0.8.4; {@code docs/api-contracts.md} §1.8).</li>
 * </ul>
 *
 * <p>Screen chrome (transaction name, titles, current date/time, program name),
 * server-generated messages (info / error), and PF-key legend fields from the BMS
 * map are deliberately excluded; they are output-only concerns carried on the
 * response contract rather than on this request.</p>
 *
 * <p>Lineage: derived (not copied) from the AWS CardDemo COBOL source at commit
 * {@code 27d6c6f} — {@code app/cpy-bms/COACTUP.CPY} (field layout) and
 * {@code app/cpy/CSSETATY.cpy} (field-edit/attribute semantics that motivate the
 * Jakarta Bean Validation constraints below).</p>
 *
 * @param version optimistic-concurrency token echoed from the last read (maps to {@code Account.version}); required.
 */
public record AccountUpdateRequest(
        @NotNull Long version,
        @NotBlank @Pattern(regexp = "\\d{1,11}") @Size(max = 11) String accountId,
        @Size(max = 1) String accountStatus,
        @Size(max = 4) String openYear,
        @Size(max = 2) String openMonth,
        @Size(max = 2) String openDay,
        @Digits(integer = 10, fraction = 2) BigDecimal creditLimit,
        @Size(max = 4) String expirationYear,
        @Size(max = 2) String expirationMonth,
        @Size(max = 2) String expirationDay,
        @Digits(integer = 10, fraction = 2) BigDecimal cashCreditLimit,
        @Size(max = 4) String reissueYear,
        @Size(max = 2) String reissueMonth,
        @Size(max = 2) String reissueDay,
        @Digits(integer = 10, fraction = 2) BigDecimal currentBalance,
        @Digits(integer = 10, fraction = 2) BigDecimal currentCycleCredit,
        @Size(max = 10) String accountGroupId,
        @Digits(integer = 10, fraction = 2) BigDecimal currentCycleDebit,
        @Size(max = 9) String customerId,
        @Size(max = 3) String ssnPart1,
        @Size(max = 2) String ssnPart2,
        @Size(max = 4) String ssnPart3,
        @Size(max = 4) String dobYear,
        @Size(max = 2) String dobMonth,
        @Size(max = 2) String dobDay,
        @Size(max = 3) String ficoScore,
        @Size(max = 25) String firstName,
        @Size(max = 25) String middleName,
        @Size(max = 25) String lastName,
        @Size(max = 50) String addressLine1,
        @Size(max = 2) String stateCode,
        @Size(max = 50) String addressLine2,
        @Size(max = 5) String zipCode,
        @Size(max = 50) String city,
        @Size(max = 3) String countryCode,
        @Size(max = 3) String phone1Area,
        @Size(max = 3) String phone1Prefix,
        @Size(max = 4) String phone1Line,
        @Size(max = 20) String governmentIssuedId,
        @Size(max = 3) String phone2Area,
        @Size(max = 3) String phone2Prefix,
        @Size(max = 4) String phone2Line,
        @Size(max = 10) String eftAccountId,
        @Size(max = 1) String primaryCardHolderIndicator) {
}
