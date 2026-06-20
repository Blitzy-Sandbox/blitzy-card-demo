package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the card-update screen.
 *
 * <p>Carries the JSON body accepted by {@code CardController} on
 * {@code PUT /api/cards/{cardNumber}} and processed by
 * {@code CardUpdateService} (the Java migration of the COBOL online program
 * {@code COCRDUPC}, which performs an optimistic-locked {@code @Version}
 * update). This record mirrors the <em>input / editable</em> fields of the
 * {@code COCRDUP} BMS symbolic map ({@code CCRDUPAI}) from source commit
 * {@code 27d6c6f}; screen chrome, the {@code INFOMSG}/{@code ERRMSG}
 * response-only fields, and the PF-key legend ({@code FKEYS}) are intentionally
 * excluded.</p>
 *
 * <p>Every component is a {@link String}, preserving the fixed-width
 * alphanumeric ({@code PIC X(n)}) layout of the originating BMS fields; no
 * monetary values appear on this screen. The Jakarta Bean Validation
 * constraints reproduce the field-edit semantics expressed by the COBOL
 * {@code CSSETATY} attribute-setting copybook (mandatory keys must be present;
 * lengths must not exceed the map definitions; the account and card identifiers
 * must be numeric).</p>
 *
 * <p>As a {@code record} this type is immutable and exposes a canonical
 * constructor (on which the validation constraints are enforced when the
 * controller annotates the argument with {@code @Valid}) together with an
 * accessor for each component.</p>
 *
 * @param accountId       11-digit account identifier
 *                        ({@code ACCTSID PIC X(11)} of {@code COCRDUP}); the
 *                        owning account key, required and numeric
 * @param cardNumber      16-digit card number
 *                        ({@code CARDSID PIC X(16)} of {@code COCRDUP}); the
 *                        card being updated, required and numeric
 * @param nameOnCard      embossed cardholder name
 *                        ({@code CRDNAME PIC X(50)} of {@code COCRDUP})
 * @param cardStatus      single-character active/inactive status code
 *                        ({@code CRDSTCD PIC X(1)} of {@code COCRDUP})
 * @param expirationMonth two-digit expiry month
 *                        ({@code EXPMON PIC X(2)} of {@code COCRDUP})
 * @param expirationYear  four-digit expiry year
 *                        ({@code EXPYEAR PIC X(4)} of {@code COCRDUP})
 * @param expirationDay   two-digit expiry day
 *                        ({@code EXPDAY PIC X(2)} of {@code COCRDUP})
 */
public record CardUpdateRequest(

        @NotBlank
        @Pattern(regexp = "\\d{1,11}")
        @Size(max = 11)
        String accountId,

        @NotBlank
        @Pattern(regexp = "\\d{1,16}")
        @Size(max = 16)
        String cardNumber,

        @Size(max = 50)
        String nameOnCard,

        @Size(max = 1)
        String cardStatus,

        @Size(max = 2)
        String expirationMonth,

        @Size(max = 4)
        String expirationYear,

        @Size(max = 2)
        String expirationDay) {
}
