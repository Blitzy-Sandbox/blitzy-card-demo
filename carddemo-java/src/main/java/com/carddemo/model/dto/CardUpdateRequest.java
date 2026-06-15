package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the card-update operation.
 *
 * <p>Bound to {@code PUT /api/cards/{cardNumber}} by
 * {@code com.carddemo.controller.CardController} and consumed by
 * {@code com.carddemo.service.card.CardUpdateService} — the Java re-platforming of
 * the COBOL/CICS online program {@code COCRDUPC}, which performs the optimistic
 * (before/after image) card update. This record mirrors the
 * <strong>input/editable</strong> fields of the {@code COCRDUP} BMS symbolic map
 * ({@code CCRDUPAI}). Screen chrome (title/date/time/program-name), the
 * informational and error message fields ({@code INFOMSG}/{@code ERRMSG}), and the
 * PF-key legend ({@code FKEYS}) are intentionally excluded because they are
 * response- or presentation-only concerns.</p>
 *
 * <p>Every component is a {@link String} that preserves the original fixed-width
 * BMS field length, enforced with {@link Size}. The {@code accountId} and
 * {@code cardNumber} components additionally require a non-blank, all-numeric
 * value via {@link NotBlank} and {@link Pattern}, mirroring the COBOL field-edit
 * semantics captured in {@code CSSETATY} (blank/invalid input is rejected). This
 * screen carries no monetary values, so no {@code BigDecimal} field is present.</p>
 *
 * <p>Lineage (reference only — the COBOL source is not copied): original
 * repository commit {@code 27d6c6f}; primary symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}; field-edit semantics {@code app/cpy/CSSETATY.cpy}.</p>
 *
 * @param accountId       account identifier (BMS {@code ACCTSID}, {@code PIC X(11)}); required, 1–11 digits.
 * @param cardNumber      card number (BMS {@code CARDSID}, {@code PIC X(16)}); required, 1–16 digits.
 * @param nameOnCard      embossed cardholder name (BMS {@code CRDNAME}, {@code PIC X(50)}).
 * @param cardStatus      single-character card status code (BMS {@code CRDSTCD}, {@code PIC X(1)}).
 * @param expirationMonth expiration month (BMS {@code EXPMON}, {@code PIC X(2)}).
 * @param expirationYear  expiration year (BMS {@code EXPYEAR}, {@code PIC X(4)}).
 * @param expirationDay   expiration day (BMS {@code EXPDAY}, {@code PIC X(2)}).
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
