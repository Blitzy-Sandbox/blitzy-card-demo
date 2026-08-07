/*
 * ******************************************************************
 * Program     : AccountViewResponse.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : The public HTTP body for the account read: the account
 *               and its non-protected customer attributes, with every
 *               high-risk personal identifier withheld and the sealed
 *               as-displayed snapshot attached so a stateless update
 *               can be confirmed.
 * Source      : app/cbl/COACTVWC.cbl (941 lines) over mapset COACTVW,
 *               app/cpy-bms/COACTVW.CPY:17-240 (37 input fields)
 *               @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L669-L756 (ACUP-OLD-DETAILS, the
 *               snapshot 9700-CHECK-CHANGE-IN-REC compares against)
 *               @ 7756d89
 * Source      : app/cpy/CVCUS01Y.cpy (500-byte CUSTOMER-RECORD:
 *               CUST-SSN PIC 9(09), CUST-DOB-YYYY-MM-DD,
 *               CUST-GOVT-ISSUED-ID, CUST-EFT-ACCOUNT-ID,
 *               CUST-PHONE-NUM-1/2 - all withheld here) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.dto;

import java.util.List;
import java.util.Objects;

/**
 * One account, as an HTTP response.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the wire type for the account read. It is deliberately not {@link AccountDto}: that record is the
 * faithful thirty-seven-field transcription of mapset {@code COACTVW}, and nine of those thirty-seven are
 * high-risk personal data that its own documentation marks "never emitted" - a statement the record itself
 * cannot enforce, because Jackson serialises every component of a record. Enforcement therefore belongs to a
 * separate response type, which is this one: the values are not withheld by annotation, they are
 * <b>structurally absent</b>.</p>
 *
 * <h2>Withheld, and not recoverable from anything here</h2>
 *
 * <p>Nine values of the legacy projection have no component on this type: the social security number
 * ({@code CUST-SSN PIC 9(09)}), the date of birth ({@code CUST-DOB-YYYY-MM-DD}), the first, middle and last
 * names, both telephone numbers, the government-issued identifier and the electronic-funds account identifier.
 * A 3270 terminal in a card-operations centre could display them; an HTTP response body is logged by proxies,
 * cached by clients and captured by browser tooling, so it may not.</p>
 *
 * <p>The withholding applies to the <b>display</b> components. A caller that legitimately needs those values
 * for some other purpose needs an operation designed for it, with its own authorisation, and none exists in
 * this scope.</p>
 *
 * <h2>The as-displayed group, carried verbatim</h2>
 *
 * <p>{@link #oldDetails} is the one place a protected value does appear, and it is there because
 * transformation Rule 7 requires it. {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:669} lived in
 * {@code WS-THIS-PROGCOMMAREA} at {@code :652} between the two turns of the pseudo-conversation, and a
 * stateless server has nowhere to put it, so the matching {@code PUT} carries it in its request body and this
 * read is what supplies it. {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4109-4193} compares all
 * twenty-nine of its values against the live record, so every one of them has to be here.</p>
 *
 * <p>It is carried as the group itself rather than as flat components, and that is not a stylistic choice.
 * The comparison is representation-sensitive in ways a caller cannot be expected to reconstruct: the date of
 * birth is compared at live offsets {@code 1}, {@code 6} and {@code 9} against snapshot offsets {@code 1},
 * {@code 5} and {@code 7}, because the live record is dash-separated and the snapshot is not
 * ({@code :L4174-L4179}); the account group identifier is folded to lower case on both sides while the
 * customer name and address fields are folded to upper case and the remainder are not folded at all. A caller
 * that assembled the group from flat display fields would get the date of birth wrong on every request.
 * Echoing this component back unaltered is therefore the whole contract, and the type is the very type the
 * {@code PUT} binds, so there is no shape to translate.</p>
 *
 * <p>What that costs is stated rather than hidden: the group is personal data on an HTTP response. The
 * mitigations in force are the general ones - transport is the deployment's concern, the logging configuration
 * masks the social security number and credentials in every log event, and no handler in this scope reinstates
 * binding messages that could echo a submitted value. Encryption at rest for personally identifiable data is
 * recorded as deferred hardening rather than claimed.</p>
 *
 * <h2>Money is text, exactly as the source rendered it</h2>
 *
 * <p>The four money members carry the legacy display renderings, not JSON numbers, because the source's
 * pictures are the contract and a cycle debit legitimately holds negative values that are never normalised.
 * Every one of them originates from a {@code PIC S9(10)V99} field and is handled as a scaled decimal
 * throughout the application - no binary floating type appears on any path that produced them.</p>
 *
 * <h2>Inputs, outputs, side effects, failure modes</h2>
 *
 * <p><b>Inputs.</b> Built by {@link #of}, from a service-produced {@link AccountDto} and the as-displayed
 * group the update service fetched.
 * <b>Outputs.</b> Serialised by Jackson; every component is a JSON property and there are no others.
 * <b>Side effects.</b> None. <b>Failure modes.</b> A null projection is a wiring defect and raises
 * {@link NullPointerException}.</p>
 *
 * @param accountId the eleven-character account identifier, {@code ACCTSIDI PIC X(11)}
 * @param accountStatus the one-character active status, {@code ACSTTUSI PIC X(1)}; relayed as text because the
 *     map declares a character
 * @param openDate the open date as text, {@code ADTOPENI PIC X(10)}
 * @param expiryDate the expiry date as text, {@code AEXPDTI PIC X(10)}
 * @param reissueDate the reissue date as text, {@code AREISDTI PIC X(10)}
 * @param creditLimit the credit limit as display text, {@code ACRDLIMI PIC X(15)}
 * @param cashCreditLimit the cash credit limit as display text, {@code ACSHLIMI PIC X(15)}
 * @param currentBalance the current balance as display text, {@code ACURBALI PIC X(15)}
 * @param currentCycleCredit the current cycle credit as display text, {@code ACRCYCRI PIC X(15)}
 * @param currentCycleDebit the current cycle debit as display text, {@code ACRCYDBI PIC X(15)}; legitimately
 *     negative and never normalised to an absolute value, because the posting logic accumulates negative
 *     amounts here and the over-limit arithmetic subtracts it
 * @param accountGroupId the disclosure group identifier, {@code AADDGRPI PIC X(10)}
 * @param customerId the nine-character customer identifier, {@code ACSTNUMI PIC X(9)}
 * @param customerFicoScore the FICO credit score as text, {@code ACSTFCOI PIC X(3)}
 * @param addressLine1 the first address line, {@code ACSADL1I PIC X(50)}
 * @param addressLine2 the second address line, {@code ACSADL2I PIC X(50)}
 * @param addressCity the city, which the map declares after the postal code and which is in fact address
 *     line 3, {@code ACSCITYI PIC X(50)}
 * @param addressStateCode the two-character state code, {@code ACSSTTEI PIC X(2)}
 * @param addressZip the postal code, five bytes on this map into which the ten-byte customer field is
 *     truncated by the legacy move, {@code ACSZIPCI PIC X(5)}
 * @param addressCountryCode the three-character country code, {@code ACSCTRYI PIC X(3)}
 * @param primaryCardHolderIndicator the primary card holder indicator, a raw one-character code with no
 *     enumerated counterpart, {@code ACSPFLGI PIC X(1)}
 * @param informationMessage {@code INFOMSGI PIC X(45)}, the byte-exact screen literal; may be null
 * @param errorMessage {@code ERRMSGI PIC X(78)}, on the same terms; may be null
 * @param oldDetails the {@code ACUP-OLD-DETAILS} group of {@code app/cbl/COACTUPC.cbl:669} exactly as the
 *     read projected it, to be echoed back unaltered as the {@code oldDetails} member of the matching
 *     update's request body. Never null on a successful read
 */
public record AccountViewResponse(
        String accountId,
        String accountStatus,
        String openDate,
        String expiryDate,
        String reissueDate,
        String creditLimit,
        String cashCreditLimit,
        String currentBalance,
        String currentCycleCredit,
        String currentCycleDebit,
        String accountGroupId,
        String customerId,
        String customerFicoScore,
        String addressLine1,
        String addressLine2,
        String addressCity,
        String addressStateCode,
        String addressZip,
        String addressCountryCode,
        String primaryCardHolderIndicator,
        String informationMessage,
        String errorMessage,
        AccountUpdateRequest.OldDetails oldDetails) {

    /**
     * The names of the legacy projection's components that this type must never carry.
     *
     * <p>Published so the contract is machine-checkable rather than merely documented: a test asserts that no
     * component of this record bears any of these names, so adding one back would fail the build rather than
     * quietly widen the response.</p>
     */
    public static final List<String> WITHHELD_COMPONENTS = List.of(
            "customerSsn",
            "customerDateOfBirth",
            "customerFirstName",
            "customerMiddleName",
            "customerLastName",
            "phoneNumber1",
            "phoneNumber2",
            "governmentIssuedId",
            "eftAccountId");

    /**
     * Projects a service-produced account onto its response form, withholding the nine protected display
     * values and attaching the as-displayed group the matching update must echo.
     *
     * <p>The group is relayed by reference and is neither copied nor rewritten, because the caller returns it
     * unaltered and the comparison it feeds is byte-sensitive.</p>
     *
     * @param projection the thirty-seven-field projection the account-view service produced; must not be null
     * @param oldDetails the as-displayed group for a subsequent update; may be null only when the read could
     *     not produce one, in which case the client will be unable to update and the reason belongs in the log
     *     rather than in this body
     * @return the response; never null
     * @throws NullPointerException if {@code projection} is null
     */
    public static AccountViewResponse of(final AccountDto projection,
                                         final AccountUpdateRequest.OldDetails oldDetails) {
        Objects.requireNonNull(projection, "projection must not be null");
        return new AccountViewResponse(
                projection.accountId(),
                projection.accountStatus(),
                projection.openDate(),
                projection.expiryDate(),
                projection.reissueDate(),
                projection.creditLimit(),
                projection.cashCreditLimit(),
                projection.currentBalance(),
                projection.currentCycleCredit(),
                projection.currentCycleDebit(),
                projection.accountGroupId(),
                projection.customerId(),
                projection.customerFicoScore(),
                projection.addressLine1(),
                projection.addressLine2(),
                projection.addressCity(),
                projection.addressStateCode(),
                projection.addressZip(),
                projection.addressCountryCode(),
                projection.primaryCardHolderIndicator(),
                projection.informationMessage(),
                projection.errorMessage(),
                oldDetails);
    }
}
