package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response body for the account-update screen, returned by the account REST
 * controller on {@code PUT /api/accounts/{id}} once the account-update service has
 * committed the transactional account-and-customer update.
 *
 * <p>This DTO mirrors the <em>output / redisplay</em> view of the {@code COACTUP}
 * BMS symbolic map (COBOL lineage preserved by reference to source commit
 * {@code 27d6c6f}; the original copybook is never copied into this project). It
 * carries the same split account and customer field layout used on the inbound
 * request — calendar date, Social-Security-number, and phone-number values are kept
 * as discrete parts rather than merged — and additionally exposes the screen's two
 * operator-message fields, {@code infoMessage} and {@code errorMessage}, which
 * convey confirmation text or validation feedback when the screen is redisplayed.</p>
 *
 * <p>The five monetary components are modelled with {@link java.math.BigDecimal}
 * (scale 2) to preserve the exact fixed-point precision of the originating COBOL
 * {@code PIC} clauses; floating-point types are deliberately avoided. Every other
 * component is a {@link String}, reflecting the fixed-width character fields of the
 * symbolic map. As a server-produced response, this record declares no
 * bean-validation constraints, no JPA/persistence mapping, and no screen-chrome or
 * PF-key (function-key) fields.</p>
 *
 * <p>Being a Java {@code record}, the type is immutable and value-based: it provides
 * a canonical constructor, public accessor methods for every component, and
 * structural {@code equals}, {@code hashCode}, and {@code toString} implementations.</p>
 *
 * @param accountId                  account identifier (&larr; {@code ACCTSID})
 * @param accountStatus             account status flag (&larr; {@code ACSTTUS})
 * @param openYear                  account open-date year part (&larr; {@code OPNYEAR})
 * @param openMonth                 account open-date month part (&larr; {@code OPNMON})
 * @param openDay                   account open-date day part (&larr; {@code OPNDAY})
 * @param creditLimit               total credit limit, scale 2 (&larr; {@code ACRDLIM})
 * @param expirationYear            card expiration-date year part (&larr; {@code EXPYEAR})
 * @param expirationMonth           card expiration-date month part (&larr; {@code EXPMON})
 * @param expirationDay             card expiration-date day part (&larr; {@code EXPDAY})
 * @param cashCreditLimit           cash advance credit limit, scale 2 (&larr; {@code ACSHLIM})
 * @param reissueYear               card reissue-date year part (&larr; {@code RISYEAR})
 * @param reissueMonth              card reissue-date month part (&larr; {@code RISMON})
 * @param reissueDay                card reissue-date day part (&larr; {@code RISDAY})
 * @param currentBalance            current account balance, scale 2 (&larr; {@code ACURBAL})
 * @param currentCycleCredit        current cycle credit total, scale 2 (&larr; {@code ACRCYCR})
 * @param accountGroupId            account group identifier (&larr; {@code AADDGRP})
 * @param currentCycleDebit         current cycle debit total, scale 2 (&larr; {@code ACRCYDB})
 * @param customerId                customer identifier (&larr; {@code ACSTNUM})
 * @param ssnPart1                  Social-Security-number area part (&larr; {@code ACTSSN1})
 * @param ssnPart2                  Social-Security-number group part (&larr; {@code ACTSSN2})
 * @param ssnPart3                  Social-Security-number serial part (&larr; {@code ACTSSN3})
 * @param dobYear                   date-of-birth year part (&larr; {@code DOBYEAR})
 * @param dobMonth                  date-of-birth month part (&larr; {@code DOBMON})
 * @param dobDay                    date-of-birth day part (&larr; {@code DOBDAY})
 * @param ficoScore                 customer FICO credit score (&larr; {@code ACSTFCO})
 * @param firstName                 customer first name (&larr; {@code ACSFNAM})
 * @param middleName                customer middle name (&larr; {@code ACSMNAM})
 * @param lastName                  customer last name (&larr; {@code ACSLNAM})
 * @param addressLine1              customer address line 1 (&larr; {@code ACSADL1})
 * @param stateCode                 customer state code (&larr; {@code ACSSTTE})
 * @param addressLine2              customer address line 2 (&larr; {@code ACSADL2})
 * @param zipCode                   customer ZIP code (&larr; {@code ACSZIPC})
 * @param city                      customer city (&larr; {@code ACSCITY})
 * @param countryCode               customer country code (&larr; {@code ACSCTRY})
 * @param phone1Area                primary phone area-code part (&larr; {@code ACSPH1A})
 * @param phone1Prefix             primary phone prefix part (&larr; {@code ACSPH1B})
 * @param phone1Line               primary phone line-number part (&larr; {@code ACSPH1C})
 * @param governmentIssuedId        government-issued identification (&larr; {@code ACSGOVT})
 * @param phone2Area                secondary phone area-code part (&larr; {@code ACSPH2A})
 * @param phone2Prefix             secondary phone prefix part (&larr; {@code ACSPH2B})
 * @param phone2Line               secondary phone line-number part (&larr; {@code ACSPH2C})
 * @param eftAccountId              EFT account identifier (&larr; {@code ACSEFTC})
 * @param primaryCardHolderIndicator primary card-holder indicator (&larr; {@code ACSPFLG})
 * @param infoMessage               informational/confirmation message (&larr; {@code INFOMSG})
 * @param errorMessage              validation/error message (&larr; {@code ERRMSG})
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
        String infoMessage,
        String errorMessage) {
}
