package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Account-view (inquiry) response returned by {@code GET /api/accounts/{id}}.
 *
 * <p>Produced by {@code com.carddemo.service.account.AccountViewService} — the
 * Java migration of the {@code COACTVWC} online program, which joins the
 * {@code ACCTDAT}, {@code CUSTDAT}, and {@code CXACAIX} datasets. This record
 * mirrors the account and customer display fields of the {@code COACTVW} BMS
 * symbolic map (source commit {@code 27d6c6f}). Screen-chrome fields
 * ({@code TRNNAME}, {@code TITLE01}, {@code TITLE02}, {@code CURDATE},
 * {@code CURTIME}, {@code PGMNAME}) are intentionally excluded.
 *
 * <p>The five monetary components are {@link BigDecimal} (scale 2) to preserve
 * the exact fixed-point precision of the originating {@code PIC S9(10)V99}
 * balance fields. All remaining components are {@link String} to preserve the
 * exact display contract; identifiers and dates are carried verbatim and any
 * date-string validation is performed by {@code DateValidationService}.
 *
 * @param accountId                  account identifier (COACTVW {@code ACCTSID})
 * @param accountStatus              account active status flag (COACTVW {@code ACSTTUS})
 * @param openDate                   account open date (COACTVW {@code ADTOPEN})
 * @param creditLimit                total credit limit (COACTVW {@code ACRDLIM})
 * @param expirationDate             card expiration date (COACTVW {@code AEXPDT})
 * @param cashCreditLimit            cash credit limit (COACTVW {@code ACSHLIM})
 * @param reissueDate                card reissue date (COACTVW {@code AREISDT})
 * @param currentBalance             current account balance (COACTVW {@code ACURBAL})
 * @param currentCycleCredit         current cycle credit total (COACTVW {@code ACRCYCR})
 * @param accountGroupId             account group identifier (COACTVW {@code AADDGRP})
 * @param currentCycleDebit          current cycle debit total (COACTVW {@code ACRCYDB})
 * @param customerId                 customer identifier (COACTVW {@code ACSTNUM})
 * @param ssn                        customer social-security number (COACTVW {@code ACSTSSN})
 * @param dateOfBirth                customer date of birth (COACTVW {@code ACSTDOB})
 * @param ficoScore                  customer FICO credit score (COACTVW {@code ACSTFCO})
 * @param firstName                  customer first name (COACTVW {@code ACSFNAM})
 * @param middleName                 customer middle name (COACTVW {@code ACSMNAM})
 * @param lastName                   customer last name (COACTVW {@code ACSLNAM})
 * @param addressLine1               customer address line 1 (COACTVW {@code ACSADL1})
 * @param stateCode                  customer state code (COACTVW {@code ACSSTTE})
 * @param addressLine2               customer address line 2 (COACTVW {@code ACSADL2})
 * @param zipCode                    customer ZIP code (COACTVW {@code ACSZIPC})
 * @param city                       customer city (COACTVW {@code ACSCITY})
 * @param countryCode                customer country code (COACTVW {@code ACSCTRY})
 * @param phoneNumber1               customer primary phone number (COACTVW {@code ACSPHN1})
 * @param governmentIssuedId         customer government-issued identifier (COACTVW {@code ACSGOVT})
 * @param phoneNumber2               customer secondary phone number (COACTVW {@code ACSPHN2})
 * @param eftAccountId               customer EFT account identifier (COACTVW {@code ACSEFTC})
 * @param primaryCardHolderIndicator primary card-holder indicator (COACTVW {@code ACSPFLG})
 * @param version                    optimistic-locking token (the JPA
 *                                    {@code @Version} of the {@code Account}
 *                                    entity) the client echoes on a subsequent
 *                                    {@link AccountUpdateRequest} so the update
 *                                    service can detect concurrent modification
 *                                    (AAP &sect;0.8.4); has no BMS equivalent
 * @param infoMessage                informational message line (COACTVW {@code INFOMSG})
 * @param errorMessage               error message line (COACTVW {@code ERRMSG})
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
        Long version,
        String infoMessage,
        String errorMessage) {
}
