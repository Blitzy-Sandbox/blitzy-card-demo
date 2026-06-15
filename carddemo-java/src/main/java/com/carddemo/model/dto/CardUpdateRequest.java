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
 * BMS field length, enforced with {@link Size}. The editable detail fields carry
 * {@link NotBlank} and {@link Pattern} constraints that reproduce the
 * {@code COCRDUPC} edit routines exactly: {@code 1230-EDIT-NAME} (supplied and
 * alphabetic/space only), {@code 1240-EDIT-CARDSTATUS} ({@code Y}/{@code N} via
 * {@code FLG-YES-NO-VALID}), {@code 1250-EDIT-EXPIRY-MON} ({@code VALID-MONTH}
 * {@code 1 THRU 12}), and {@code 1260-EDIT-EXPIRY-YEAR} ({@code VALID-YEAR}
 * {@code 1950 THRU 2099}). The {@code accountId} and {@code cardNumber}
 * key components require a non-blank, all-numeric value. This screen carries no
 * monetary values, so no {@code BigDecimal} field is present.</p>
 *
 * <p>{@code expirationDay} intentionally carries only a length constraint:
 * {@code COCRDUPC} has <em>no</em> day edit routine (no {@code 1270-EDIT-DAY}); the
 * input {@code EXPDAY} is moved through unvalidated. Adding a non-blank or pattern
 * constraint here would reject input the COBOL program accepts, breaking the
 * 100% behavioral-parity mandate (AAP §0.8.1).</p>
 *
 * <p>Lineage (reference only — the COBOL source is not copied): original
 * repository commit {@code 27d6c6f}; primary symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}; field-edit semantics {@code app/cbl/COCRDUPC.cbl}.</p>
 *
 * @param accountId       account identifier (BMS {@code ACCTSID}, {@code PIC X(11)}); required, 1–11 digits.
 * @param cardNumber      card number (BMS {@code CARDSID}, {@code PIC X(16)}); required, 1–16 digits.
 * @param nameOnCard      embossed cardholder name (BMS {@code CRDNAME}, {@code PIC X(50)}); required, letters and spaces only.
 * @param cardStatus      card status code (BMS {@code CRDSTCD}, {@code PIC X(1)}); required, {@code Y} or {@code N}.
 * @param expirationMonth expiration month (BMS {@code EXPMON}, {@code PIC X(2)}); required, {@code 01}–{@code 12}.
 * @param expirationYear  expiration year (BMS {@code EXPYEAR}, {@code PIC X(4)}); required, {@code 1950}–{@code 2099}.
 * @param expirationDay   expiration day (BMS {@code EXPDAY}, {@code PIC X(2)}); unvalidated in {@code COCRDUPC} (length only).
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

        @NotBlank
        @Pattern(regexp = "[A-Za-z ]+")
        @Size(max = 50)
        String nameOnCard,

        @NotBlank
        @Pattern(regexp = "[YN]")
        @Size(max = 1)
        String cardStatus,

        @NotBlank
        @Pattern(regexp = "0[1-9]|1[0-2]")
        @Size(max = 2)
        String expirationMonth,

        @NotBlank
        @Pattern(regexp = "19[5-9][0-9]|20[0-9]{2}")
        @Size(max = 4)
        String expirationYear,

        @Size(max = 2)
        String expirationDay) {
}
