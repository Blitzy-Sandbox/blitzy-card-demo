package com.carddemo.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.Size;

/**
 * Response payload for the Card Detail View screen (online transaction {@code CCDL}).
 *
 * <p>This DTO is the idiomatic Spring translation of the legacy 3270 BMS symbolic map
 * {@code COCRDSL} ({@code app/cpy-bms/COCRDSL.CPY}), which the online program
 * {@code COCRDSLC} populates from the VSAM {@code CARD-RECORD} layout
 * ({@code app/cpy/CVACT02Y.cpy}). It carries the read-only card attributes rendered to
 * the user, together with the JPA optimistic-lock {@code version} echo consumed on the
 * subsequent Card Update round-trip (see AAP &sect;0.8.4).</p>
 *
 * <p><strong>Security invariants</strong></p>
 * <ul>
 *   <li><strong>PAN masking</strong> &mdash; the Primary Account Number is always stored
 *       masked so that only the last four characters remain visible; the full card number
 *       is never emitted in a response (AAP &sect;0.3.2). Masking is applied by the
 *       canonical constructor and is idempotent (re-masking an already-masked value yields
 *       the same value), so serialization/deserialization round-trips remain safe.</li>
 *   <li><strong>No CVV</strong> &mdash; the card verification value ({@code CARD-CVV-CD},
 *       {@code PIC 9(03)}) is intentionally absent. It is a secret, it is not shown on the
 *       {@code COCRDSL} screen, and it must never appear in any API payload.</li>
 * </ul>
 *
 * <p>The type is an immutable {@code record}; it holds no mutable state and is therefore
 * stateless and thread-safe. No monetary values are present on the card view, so no
 * floating-point types are used.</p>
 *
 * @param accountId      the 11-digit account identifier ({@code CARD-ACCT-ID} / screen
 *                       field {@code ACCTSID})
 * @param cardNumber     the 16-character card number ({@code CARD-NUM} / screen field
 *                       {@code CARDSID}), always masked to reveal only the last four
 *                       characters with the original width preserved
 * @param embossedName   the embossed card-holder name ({@code CARD-EMBOSSED-NAME} / screen
 *                       field {@code CRDNAME})
 * @param activeStatus   the single-character active-status flag ({@code CARD-ACTIVE-STATUS}
 *                       / screen field {@code CRDSTCD}); {@code Y} (active) or {@code N}
 *                       (inactive)
 * @param expirationDate the card expiration date ({@code CARD-EXPIRAION-DATE}; note the
 *                       legacy copybook misspelling) modelled as an ISO-8601
 *                       {@link LocalDate}; the legacy screen renders it as month
 *                       ({@code EXPMON}) plus year ({@code EXPYEAR})
 * @param version        the JPA {@code @Version} optimistic-lock value echoed back for the
 *                       Card Update round-trip; may be {@code null} for a read that carries
 *                       no version
 */
public record CardViewResponse(

        @Size(max = 11)
        String accountId,

        @Size(max = 16)
        String cardNumber,

        @Size(max = 50)
        String embossedName,

        @Size(max = 1)
        String activeStatus,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate expirationDate,

        Long version
) {

    /** Number of trailing card-number characters that remain visible after masking. */
    private static final int VISIBLE_PAN_CHARACTERS = 4;

    /** Character substituted for each concealed card-number character. */
    private static final char MASK_CHARACTER = '*';

    /**
     * Canonical constructor that enforces the PAN-masking security invariant.
     *
     * <p>The supplied {@code cardNumber} is passed through {@link #maskCardNumber(String)}
     * so that only the last {@value #VISIBLE_PAN_CHARACTERS} characters remain visible while
     * the overall width is preserved. Because masking only ever replaces leading characters
     * with the mask character, applying it to a value that is already masked produces an
     * identical result; the operation is therefore idempotent and safe to apply on every
     * construction path, including deserialization.</p>
     */
    public CardViewResponse {
        cardNumber = maskCardNumber(cardNumber);
    }

    /**
     * Masks a card number so that only its last {@value #VISIBLE_PAN_CHARACTERS} characters
     * remain visible, preserving the original width.
     *
     * <p>Masking rules:</p>
     * <ul>
     *   <li>a {@code null} input is returned unchanged;</li>
     *   <li>an input whose length does not exceed {@value #VISIBLE_PAN_CHARACTERS} is masked
     *       in its entirety, so a short value can never be exposed in full;</li>
     *   <li>otherwise every character except the trailing {@value #VISIBLE_PAN_CHARACTERS}
     *       is replaced with {@code '}{@value #MASK_CHARACTER}{@code '}.</li>
     * </ul>
     *
     * @param rawCardNumber the card number to mask; may be {@code null}
     * @return the masked card number, or {@code null} when {@code rawCardNumber} is
     *         {@code null}
     */
    private static String maskCardNumber(String rawCardNumber) {
        if (rawCardNumber == null) {
            return null;
        }
        int length = rawCardNumber.length();
        if (length <= VISIBLE_PAN_CHARACTERS) {
            return String.valueOf(MASK_CHARACTER).repeat(length);
        }
        int maskedLength = length - VISIBLE_PAN_CHARACTERS;
        return String.valueOf(MASK_CHARACTER).repeat(maskedLength)
                + rawCardNumber.substring(maskedLength);
    }
}
