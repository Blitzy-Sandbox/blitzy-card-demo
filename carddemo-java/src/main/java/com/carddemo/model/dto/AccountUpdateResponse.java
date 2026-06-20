package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response payload for the account-update screen, returned by
 * {@code AccountController} on {@code PUT /api/accounts/{id}} once
 * {@code AccountUpdateService} has committed the {@code @Transactional}
 * dual update of the account and customer records.
 *
 * <p>This DTO mirrors the <em>output / redisplay</em> view of the
 * {@code COACTUP} BMS symbolic map (the {@code CACTUPAO} redefinition): after a
 * successful or failed update the legacy CICS program redisplayed every account
 * and customer field together with an informational or error message. The REST
 * contract reproduces that behavior so callers can confirm the persisted state
 * (or re-render the form when an update is rejected) without an extra read.
 *
 * <p>Field-structure rules carried over from the BMS contract:
 * <ul>
 *   <li>The opening date, expiration date, reissue date, date-of-birth, SSN,
 *       and both phone numbers remain <strong>split</strong> into their
 *       individual parts exactly as the 3270 map presented them; the parts are
 *       never merged, preserving byte-for-byte interface parity.</li>
 *   <li>The five monetary amounts (credit limit, cash credit limit, current
 *       balance, current cycle credit, and current cycle debit) are
 *       {@link java.math.BigDecimal} with scale 2 to preserve the exact COBOL
 *       {@code PIC S9(10)V99} fixed-point precision; primitive numeric types
 *       are intentionally never used for these amounts.</li>
 *   <li>All remaining fields are {@link String}, matching the alphanumeric
 *       {@code PIC X(n)} display fields of the symbolic map.</li>
 *   <li>{@code infoMessage} and {@code errorMessage} carry the {@code INFOMSG}
 *       and {@code ERRMSG} feedback lines; exactly one is typically populated
 *       per response.</li>
 *   <li>{@code version} echoes the persisted JPA {@code @Version} of the
 *       {@code Account} entity so the client can submit it on the next
 *       {@link AccountUpdateRequest}, enabling stateless optimistic-locking
 *       (AAP &sect;0.8.4); it has no BMS-map equivalent.</li>
 * </ul>
 *
 * <p>Screen chrome (titles, program/transaction name, current date/time) and
 * PF-key legend fields from the BMS map are deliberately excluded, as they are
 * presentation concerns with no place in a stateless JSON contract.
 *
 * <p>Traceability: derived from {@code app/cpy-bms/COACTUP.CPY} at source
 * repository commit {@code 27d6c6f}. The COBOL source is referenced for lineage
 * only and is not copied into this project.
 */
public record AccountUpdateResponse(
        String accountId,
        String accountStatus,
        String openYear, String openMonth, String openDay,
        BigDecimal creditLimit,
        String expirationYear, String expirationMonth, String expirationDay,
        BigDecimal cashCreditLimit,
        String reissueYear, String reissueMonth, String reissueDay,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        String accountGroupId,
        BigDecimal currentCycleDebit,
        String customerId,
        String ssnPart1, String ssnPart2, String ssnPart3,
        String dobYear, String dobMonth, String dobDay,
        String ficoScore,
        String firstName, String middleName, String lastName,
        String addressLine1, String stateCode, String addressLine2,
        String zipCode, String city, String countryCode,
        String phone1Area, String phone1Prefix, String phone1Line,
        String governmentIssuedId,
        String phone2Area, String phone2Prefix, String phone2Line,
        String eftAccountId,
        String primaryCardHolderIndicator,
        Long version,
        String infoMessage,
        String errorMessage) {
}
