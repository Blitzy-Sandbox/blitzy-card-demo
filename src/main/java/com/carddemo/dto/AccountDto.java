/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Account data-transfer contracts for the REST account flows.
 *
 * <p>Derived byte-accurately from the BMS symbolic maps {@code COACTVW.CPY}
 * (view) and {@code COACTUP.CPY} (update) and their mapsets {@code COACTVW.bms}
 * and {@code COACTUP.bms} of the AWS CardDemo application at source commit
 * {@code 27d6c6f}.</p>
 *
 * <p>{@link ViewResponse} is the response body for {@code GET /api/accounts/{id}}
 * (CICS transaction {@code CAVW} / program {@code COACTVWC}); it presents dates,
 * SSN and phone numbers as full single fields exactly as the view map does.
 * {@link UpdateRequest} is the request body for {@code PUT /api/accounts/{id}}
 * (CICS transaction {@code CAUP} / program {@code COACTUPC}); it presents those
 * same values as the discrete year/month/day, SSN and phone segments exactly as
 * the update map does. The two shapes intentionally differ in segmentation so
 * that each map's external field contract is preserved verbatim.</p>
 *
 * <p>This type carries the field contract only; transaction orchestration,
 * optimistic-locking version checks and date/segment assembly are the
 * responsibility of the controller and service layers.</p>
 */
public final class AccountDto {

    private AccountDto() {
    }

    /**
     * Response body for {@code GET /api/accounts/{id}}, mapped from the
     * {@code COACTVW} view map ({@code CACTVWAI}) at commit {@code 27d6c6f}.
     * Monetary amounts are {@link BigDecimal} values scaled to two fraction
     * digits; the informational and error message fields of the source map are
     * intentionally excluded and surfaced through the global exception handler.
     */
    public record ViewResponse(
            @Size(max = 11) @Pattern(regexp = "\\d{1,11}") String accountId,
            @Size(max = 1) String accountStatus,
            @Size(max = 10) String openDate,
            @Digits(integer = 10, fraction = 2) BigDecimal creditLimit,
            @Size(max = 10) String expirationDate,
            @Digits(integer = 10, fraction = 2) BigDecimal cashCreditLimit,
            @Size(max = 10) String reissueDate,
            @Digits(integer = 10, fraction = 2) BigDecimal currentBalance,
            @Digits(integer = 10, fraction = 2) BigDecimal currentCycleCredit,
            @Size(max = 10) String accountGroupId,
            @Digits(integer = 10, fraction = 2) BigDecimal currentCycleDebit,
            @Size(max = 9) @Pattern(regexp = "\\d{1,9}") String customerId,
            @Size(max = 12) String ssn,
            @Size(max = 10) String dateOfBirth,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String ficoScore,
            @Size(max = 25) String firstName,
            @Size(max = 25) String middleName,
            @Size(max = 25) String lastName,
            @Size(max = 50) String addressLine1,
            @Size(max = 2) String state,
            @Size(max = 50) String addressLine2,
            @Size(max = 5) String zipCode,
            @Size(max = 50) String city,
            @Size(max = 3) String country,
            @Size(max = 13) String phone1,
            @Size(max = 20) String governmentId,
            @Size(max = 13) String phone2,
            @Size(max = 10) String eftAccountId,
            @Size(max = 1) String primaryCardHolder) {
    }

    /**
     * Request body for {@code PUT /api/accounts/{id}}, mapped from the
     * {@code COACTUP} update map ({@code CACTUPAI}) at commit {@code 27d6c6f}.
     * Open, expiry, reissue and date-of-birth values are carried as discrete
     * year/month/day segments, the SSN as three 3/2/4 segments, and each phone
     * number as 3/3/4 area/prefix/line segments, preserving the update map's
     * field contract exactly. Monetary amounts are {@link BigDecimal} values
     * scaled to two fraction digits; the informational, error message and
     * function-key fields of the source map are intentionally excluded.
     */
    public record UpdateRequest(
            @NotBlank @Size(max = 11) @Pattern(regexp = "\\d{1,11}") String accountId,
            @Size(max = 1) String accountStatus,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String openYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String openMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String openDay,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String expiryYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String expiryMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String expiryDay,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String reissueYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String reissueMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String reissueDay,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String dobYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String dobMonth,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String dobDay,
            @Digits(integer = 10, fraction = 2) BigDecimal creditLimit,
            @Digits(integer = 10, fraction = 2) BigDecimal cashCreditLimit,
            @Digits(integer = 10, fraction = 2) BigDecimal currentBalance,
            @Digits(integer = 10, fraction = 2) BigDecimal currentCycleCredit,
            @Size(max = 10) String accountGroupId,
            @Digits(integer = 10, fraction = 2) BigDecimal currentCycleDebit,
            @Size(max = 9) @Pattern(regexp = "\\d{1,9}") String customerId,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String ssnPart1,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String ssnPart2,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String ssnPart3,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String ficoScore,
            @Size(max = 25) String firstName,
            @Size(max = 25) String middleName,
            @Size(max = 25) String lastName,
            @Size(max = 50) String addressLine1,
            @Size(max = 2) String state,
            @Size(max = 50) String addressLine2,
            @Size(max = 5) String zipCode,
            @Size(max = 50) String city,
            @Size(max = 3) String country,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String phone1Area,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String phone1Prefix,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String phone1Line,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String phone2Area,
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String phone2Prefix,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String phone2Line,
            @Size(max = 20) String governmentId,
            @Size(max = 10) String eftAccountId,
            @Size(max = 1) String primaryCardHolder) {
    }
}
