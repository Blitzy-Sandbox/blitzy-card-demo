package com.carddemo.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Immutable request payload for the account-update operation
 * ({@code PUT /api/accounts/{id}}).
 *
 * <p>This DTO is the JSON body accepted by {@code AccountController} and
 * consumed by {@code AccountUpdateService}, the Java re-platforming of the
 * CICS online program {@code COACTUPC} (the {@code @Transactional} dual
 * {@code ACCTDAT}+{@code CUSTDAT} update guarded by {@code @Version} optimistic
 * locking). It mirrors the <strong>input/editable</strong> fields of the
 * {@code COACTUP} BMS symbolic map (copybook {@code app/cpy-bms/COACTUP.CPY},
 * group {@code CACTUPAI}; source commit {@code 27d6c6f}).
 *
 * <p><strong>Split-field contract.</strong> Unlike the account <em>view</em>
 * map ({@code COACTVW}), the update map decomposes each date into year, month,
 * and day parts, the SSN into three parts, and each telephone number into area,
 * prefix, and line parts. That exact split-field shape is the binding external
 * interface contract and is preserved here component-for-component; the parts
 * are deliberately <em>not</em> merged into composite fields.
 *
 * <p><strong>Field lengths.</strong> Every {@code String} component carries a
 * {@link Size} whose {@code max} equals the byte length of the corresponding
 * BMS {@code PIC X(n)} field exactly, preserving the fixed-width record
 * contract. The account identifier additionally requires a non-blank, all-digit
 * value of up to eleven digits.
 *
 * <p><strong>Monetary precision.</strong> The five money fields
 * ({@code creditLimit}, {@code cashCreditLimit}, {@code currentBalance},
 * {@code currentCycleCredit}, {@code currentCycleDebit}) are modelled with
 * {@link java.math.BigDecimal} and constrained to {@link Digits} with
 * {@code integer = 10, fraction = 2}. Per the decimal-precision rule
 * (AAP &sect;0.8.2) fixed-point COBOL money is never represented with
 * {@code float}/{@code double}; the two-fraction-digit {@code @Digits} bound
 * encodes the {@code PIC ...V99} scale-2 semantics of the source amounts.
 *
 * <p>The field-edit and attribute semantics of {@code app/cpy/CSSETATY.cpy}
 * (which flags fields in error and marks blanks on the 3270 screen) are
 * expressed here as the Jakarta Bean Validation constraints applied to each
 * component; the constraints are evaluated by the controller via {@code @Valid}
 * before the service layer is invoked.
 *
 * <p>This is a plain, immutable data carrier (a {@code record}) with no
 * persistence concerns: it intentionally references no JPA or entity types and
 * exposes only the editable update contract.
 *
 * @param accountId                  account identifier ({@code ACCTSID},
 *                                    {@code PIC X(11)}); required, all digits
 * @param accountStatus              active-status flag ({@code ACSTTUS},
 *                                    {@code PIC X(1)})
 * @param openYear                   account open date year part
 *                                    ({@code OPNYEAR}, {@code PIC X(4)})
 * @param openMonth                  account open date month part
 *                                    ({@code OPNMON}, {@code PIC X(2)})
 * @param openDay                    account open date day part
 *                                    ({@code OPNDAY}, {@code PIC X(2)})
 * @param creditLimit                total credit limit amount
 *                                    ({@code ACRDLIM}, {@code PIC X(15)} money)
 * @param expirationYear             account expiry date year part
 *                                    ({@code EXPYEAR}, {@code PIC X(4)})
 * @param expirationMonth            account expiry date month part
 *                                    ({@code EXPMON}, {@code PIC X(2)})
 * @param expirationDay              account expiry date day part
 *                                    ({@code EXPDAY}, {@code PIC X(2)})
 * @param cashCreditLimit            cash credit limit amount
 *                                    ({@code ACSHLIM}, {@code PIC X(15)} money)
 * @param reissueYear                card reissue date year part
 *                                    ({@code RISYEAR}, {@code PIC X(4)})
 * @param reissueMonth               card reissue date month part
 *                                    ({@code RISMON}, {@code PIC X(2)})
 * @param reissueDay                 card reissue date day part
 *                                    ({@code RISDAY}, {@code PIC X(2)})
 * @param currentBalance             current account balance amount
 *                                    ({@code ACURBAL}, {@code PIC X(15)} money)
 * @param currentCycleCredit         current cycle credit amount
 *                                    ({@code ACRCYCR}, {@code PIC X(15)} money)
 * @param accountGroupId             account group identifier
 *                                    ({@code AADDGRP}, {@code PIC X(10)})
 * @param currentCycleDebit          current cycle debit amount
 *                                    ({@code ACRCYDB}, {@code PIC X(15)} money)
 * @param customerId                 owning customer identifier
 *                                    ({@code ACSTNUM}, {@code PIC X(9)})
 * @param ssnPart1                   SSN area part ({@code ACTSSN1},
 *                                    {@code PIC X(3)})
 * @param ssnPart2                   SSN group part ({@code ACTSSN2},
 *                                    {@code PIC X(2)})
 * @param ssnPart3                   SSN serial part ({@code ACTSSN3},
 *                                    {@code PIC X(4)})
 * @param dobYear                    date-of-birth year part ({@code DOBYEAR},
 *                                    {@code PIC X(4)})
 * @param dobMonth                   date-of-birth month part ({@code DOBMON},
 *                                    {@code PIC X(2)})
 * @param dobDay                     date-of-birth day part ({@code DOBDAY},
 *                                    {@code PIC X(2)})
 * @param ficoScore                  FICO credit score ({@code ACSTFCO},
 *                                    {@code PIC X(3)})
 * @param firstName                  customer first name ({@code ACSFNAM},
 *                                    {@code PIC X(25)})
 * @param middleName                 customer middle name ({@code ACSMNAM},
 *                                    {@code PIC X(25)})
 * @param lastName                   customer last name ({@code ACSLNAM},
 *                                    {@code PIC X(25)})
 * @param addressLine1               address line 1 ({@code ACSADL1},
 *                                    {@code PIC X(50)})
 * @param stateCode                  state code ({@code ACSSTTE},
 *                                    {@code PIC X(2)})
 * @param addressLine2               address line 2 ({@code ACSADL2},
 *                                    {@code PIC X(50)})
 * @param zipCode                    postal ZIP code ({@code ACSZIPC},
 *                                    {@code PIC X(5)})
 * @param city                       city name ({@code ACSCITY},
 *                                    {@code PIC X(50)})
 * @param countryCode                country code ({@code ACSCTRY},
 *                                    {@code PIC X(3)})
 * @param phone1Area                 primary phone area part ({@code ACSPH1A},
 *                                    {@code PIC X(3)})
 * @param phone1Prefix               primary phone prefix part ({@code ACSPH1B},
 *                                    {@code PIC X(3)})
 * @param phone1Line                 primary phone line part ({@code ACSPH1C},
 *                                    {@code PIC X(4)})
 * @param governmentIssuedId         government-issued identifier
 *                                    ({@code ACSGOVT}, {@code PIC X(20)})
 * @param phone2Area                 secondary phone area part ({@code ACSPH2A},
 *                                    {@code PIC X(3)})
 * @param phone2Prefix               secondary phone prefix part
 *                                    ({@code ACSPH2B}, {@code PIC X(3)})
 * @param phone2Line                 secondary phone line part ({@code ACSPH2C},
 *                                    {@code PIC X(4)})
 * @param eftAccountId               EFT account identifier ({@code ACSEFTC},
 *                                    {@code PIC X(10)})
 * @param primaryCardHolderIndicator primary card-holder flag ({@code ACSPFLG},
 *                                    {@code PIC X(1)})
 */
public record AccountUpdateRequest(

        // --- Account identification and status ---
        @NotBlank @Pattern(regexp = "\\d{1,11}") @Size(max = 11) String accountId,
        @Size(max = 1) String accountStatus,

        // --- Account open date (split year/month/day) ---
        @Size(max = 4) String openYear,
        @Size(max = 2) String openMonth,
        @Size(max = 2) String openDay,

        // --- Credit limit (money) ---
        @Digits(integer = 10, fraction = 2) BigDecimal creditLimit,

        // --- Account expiry date (split year/month/day) ---
        @Size(max = 4) String expirationYear,
        @Size(max = 2) String expirationMonth,
        @Size(max = 2) String expirationDay,

        // --- Cash credit limit (money) ---
        @Digits(integer = 10, fraction = 2) BigDecimal cashCreditLimit,

        // --- Card reissue date (split year/month/day) ---
        @Size(max = 4) String reissueYear,
        @Size(max = 2) String reissueMonth,
        @Size(max = 2) String reissueDay,

        // --- Balances and cycle amounts (money); account group interleaved per BMS order ---
        @Digits(integer = 10, fraction = 2) BigDecimal currentBalance,
        @Digits(integer = 10, fraction = 2) BigDecimal currentCycleCredit,
        @Size(max = 10) String accountGroupId,
        @Digits(integer = 10, fraction = 2) BigDecimal currentCycleDebit,

        // --- Customer identification ---
        @Size(max = 9) String customerId,

        // --- SSN (split area/group/serial parts) ---
        @Size(max = 3) String ssnPart1,
        @Size(max = 2) String ssnPart2,
        @Size(max = 4) String ssnPart3,

        // --- Date of birth (split year/month/day) ---
        @Size(max = 4) String dobYear,
        @Size(max = 2) String dobMonth,
        @Size(max = 2) String dobDay,

        // --- Credit score ---
        @Size(max = 3) String ficoScore,

        // --- Customer name ---
        @Size(max = 25) String firstName,
        @Size(max = 25) String middleName,
        @Size(max = 25) String lastName,

        // --- Customer address ---
        @Size(max = 50) String addressLine1,
        @Size(max = 2) String stateCode,
        @Size(max = 50) String addressLine2,
        @Size(max = 5) String zipCode,
        @Size(max = 50) String city,
        @Size(max = 3) String countryCode,

        // --- Primary phone (split area/prefix/line parts) ---
        @Size(max = 3) String phone1Area,
        @Size(max = 3) String phone1Prefix,
        @Size(max = 4) String phone1Line,

        // --- Government-issued identifier ---
        @Size(max = 20) String governmentIssuedId,

        // --- Secondary phone (split area/prefix/line parts) ---
        @Size(max = 3) String phone2Area,
        @Size(max = 3) String phone2Prefix,
        @Size(max = 4) String phone2Line,

        // --- EFT account and primary card-holder flag ---
        @Size(max = 10) String eftAccountId,
        @Size(max = 1) String primaryCardHolderIndicator) {
}
