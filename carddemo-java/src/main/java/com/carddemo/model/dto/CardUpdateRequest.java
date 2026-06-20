package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * <p>Every display component is a {@link String}, preserving the fixed-width
 * alphanumeric ({@code PIC X(n)}) layout of the originating BMS fields; no
 * monetary values appear on this screen. The sole non-string component is the
 * {@code version} optimistic-lock token (see below). The Jakarta Bean
 * Validation constraints reproduce the field-edit semantics of {@code COCRDUPC}
 * exactly: the account and card identifiers are required and numeric; the
 * embossed name is required and may contain only letters and spaces
 * ({@code 1230-EDIT-NAME}); the card status is required and must be {@code Y} or
 * {@code N} ({@code 1240-EDIT-CARDSTATUS}); the expiry month is required and must
 * be {@code 1}-{@code 12} ({@code 1250-EDIT-EXPIRY-MON}); and the expiry year is
 * required and must fall in {@code 1950}-{@code 2099}
 * ({@code 1260-EDIT-EXPIRY-YEAR}). The expiry <em>day</em> deliberately carries
 * only a length bound because {@code COCRDUPC} has no day edit paragraph; adding
 * a stricter constraint would exceed the source's validation semantics.</p>
 *
 * <p><strong>Optimistic concurrency.</strong> The stateless REST contract cannot
 * hold the CICS before/after record image that {@code COCRDUPC} kept in the
 * {@code COMMAREA}, so the client echoes the {@code version} it last read (from
 * {@link CardDetailResponse} or {@link CardUpdateResponse});
 * {@code CardUpdateService} compares it against the persisted JPA
 * {@code @Version} of the {@code Card} entity and rejects a stale update,
 * reproducing the source's optimistic-locking guard (AAP &sect;0.8.4). The token
 * is {@code @NotNull} — a client may never omit it.</p>
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
 *                        ({@code CRDNAME PIC X(50)} of {@code COCRDUP}); required,
 *                        letters and spaces only ({@code 1230-EDIT-NAME})
 * @param cardStatus      single-character active/inactive status code
 *                        ({@code CRDSTCD PIC X(1)} of {@code COCRDUP}); required,
 *                        {@code Y} or {@code N} ({@code 1240-EDIT-CARDSTATUS})
 * @param expirationMonth two-digit expiry month
 *                        ({@code EXPMON PIC X(2)} of {@code COCRDUP}); required,
 *                        {@code 1}-{@code 12} ({@code 1250-EDIT-EXPIRY-MON})
 * @param expirationYear  four-digit expiry year
 *                        ({@code EXPYEAR PIC X(4)} of {@code COCRDUP}); required,
 *                        {@code 1950}-{@code 2099} ({@code 1260-EDIT-EXPIRY-YEAR})
 * @param expirationDay   two-digit expiry day
 *                        ({@code EXPDAY PIC X(2)} of {@code COCRDUP}); length-bound
 *                        only — {@code COCRDUPC} has no day edit paragraph
 * @param version         optimistic-locking token mirroring the JPA
 *                        {@code @Version} of the {@code Card} entity; the client
 *                        MUST echo the value it last read so the update service
 *                        can detect a concurrent modification. Required
 *                        ({@code @NotNull}); has no BMS equivalent
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

        // Required; letters and spaces only (COCRDUPC 1230-EDIT-NAME).
        @NotBlank
        @Pattern(regexp = "[A-Za-z ]+")
        @Size(max = 50)
        String nameOnCard,

        // Required; Y or N (COCRDUPC 1240-EDIT-CARDSTATUS).
        @NotBlank
        @Pattern(regexp = "[YN]")
        @Size(max = 1)
        String cardStatus,

        // Required; 1-12, optional leading zero (COCRDUPC 1250-EDIT-EXPIRY-MON).
        @NotBlank
        @Pattern(regexp = "(0?[1-9]|1[0-2])")
        @Size(max = 2)
        String expirationMonth,

        // Required; 1950-2099 (COCRDUPC 1260-EDIT-EXPIRY-YEAR).
        @NotBlank
        @Pattern(regexp = "(19[5-9][0-9]|20[0-9][0-9])")
        @Size(max = 4)
        String expirationYear,

        // Length-bound only: COCRDUPC has no day edit paragraph.
        @Size(max = 2)
        String expirationDay,

        // Optimistic-lock token (mirrors Card JPA @Version; no BMS field).
        @NotNull
        Long version) {
}
