package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request payload for the Card Update transaction (CCUP).
 *
 * <p>This record is the idiomatic Spring translation of the input fields of the
 * {@code COCRDUP} BMS map (symbolic copybook {@code app/cpy-bms/COCRDUP.CPY}) as
 * processed by the legacy {@code COCRDUPC} program. It carries the mutable
 * attributes of a card whose canonical persistent shape is the {@code CARD-RECORD}
 * copybook ({@code app/cpy/CVACT02Y.cpy}, record length 150). The Jakarta Bean
 * Validation constraints reproduce the BMS field edits and the fixed-width picture
 * clauses of the copybook, so the REST boundary rejects the same malformed input the
 * 3270 screen rejected — preserving the interface contract of the migrated
 * transaction.</p>
 *
 * <p>The card verification value ({@code CARD-CVV-CD}) is intentionally excluded: it
 * is not an input field on the {@code COCRDUP} screen and is therefore never mutated
 * through this request.</p>
 *
 * <p>{@code version} preserves the read-then-rewrite optimistic-concurrency pattern
 * of {@code COCRDUPC}: the JPA {@code @Version} value is read when the card is viewed
 * and echoed back on update, so a concurrent modification surfaces as an optimistic
 * lock conflict (mapped to an HTTP 409 response by the service/controller layer).
 * The request is fully stateless — the CICS pseudo-conversational {@code COMMAREA} is
 * not retained server-side (see AAP &sect;0.8.4).</p>
 *
 * @param accountId      the 11-digit account identifier owning the card
 *                       ({@code ACCTSID} / {@code CARD-ACCT-ID}); required, digits only.
 * @param cardNumber     the 16-digit card number and record key
 *                       ({@code CARDSID} / {@code CARD-NUM}); required, digits only.
 * @param embossedName   the name embossed on the card
 *                       ({@code CRDNAME} / {@code CARD-EMBOSSED-NAME}); optional, up to
 *                       50 characters.
 * @param activeStatus   the single-character active flag
 *                       ({@code CRDSTCD} / {@code CARD-ACTIVE-STATUS}); optional,
 *                       {@code "Y"} or {@code "N"}.
 * @param expirationDate the full card expiration date, composed on the screen from the
 *                       {@code EXPMON} / {@code EXPYEAR} / {@code EXPDAY} fields and
 *                       persisted as {@code CARD-EXPIRAION-DATE}; supplied here as a
 *                       single ISO-8601 ({@code yyyy-MM-dd}) date.
 * @param version        the JPA {@code @Version} value read during card view and echoed
 *                       back to enforce optimistic locking on rewrite; {@code null} when
 *                       not yet known.
 */
public record CardUpdateRequest(

        @NotBlank(message = "accountId is required")
        @Size(max = 11, message = "accountId must not exceed 11 characters")
        @Pattern(regexp = "\\d{1,11}", message = "accountId must be 1-11 digits")
        String accountId,

        @NotBlank(message = "cardNumber is required")
        @Size(max = 16, message = "cardNumber must not exceed 16 characters")
        @Pattern(regexp = "\\d{1,16}", message = "cardNumber must be 1-16 digits")
        String cardNumber,

        @Size(max = 50, message = "embossedName must not exceed 50 characters")
        String embossedName,

        @Size(max = 1, message = "activeStatus must be a single character")
        @Pattern(regexp = "[YN]", message = "activeStatus must be 'Y' or 'N'")
        String activeStatus,

        LocalDate expirationDate,

        Long version
) {
}
