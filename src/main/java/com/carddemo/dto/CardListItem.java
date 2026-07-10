package com.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable data-transfer object representing a single <em>card list row</em> --
 * one element of the paginated {@code CardListResponse}.
 *
 * <p>This DTO mirrors one repeated row of the legacy CardDemo <em>Card List</em>
 * screen (BMS mapset {@code COCRDLI}, online transaction {@code CCLI}). The
 * legacy map renders up to seven rows per page via the repeated symbolic-map
 * group {@code ACCTNOn}/{@code CRDNUMn}/{@code CRDSTSn} ({@code n = 1..7}); each
 * populated row is materialised as one {@code CardListItem}. Row values are
 * sourced from the {@code CARD-RECORD} layout (copybook {@code CVACT02Y}) linked
 * to its owning account through the card cross-reference ({@code CARD-XREF}).</p>
 *
 * <h2>Source field mapping</h2>
 * <table>
 *   <caption>COBOL source-to-Java field mapping</caption>
 *   <thead>
 *     <tr><th>Java component</th><th>BMS field</th><th>{@code CARD-RECORD} field (PIC)</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code accountId}</td><td>{@code ACCTNOn} X(11)</td><td>{@code CARD-ACCT-ID} 9(11)</td></tr>
 *     <tr><td>{@code cardNumber}</td><td>{@code CRDNUMn} X(16)</td><td>{@code CARD-NUM} X(16)</td></tr>
 *     <tr><td>{@code activeStatus}</td><td>{@code CRDSTSn} X(1)</td><td>{@code CARD-ACTIVE-STATUS} X(01)</td></tr>
 *   </tbody>
 * </table>
 *
 * <h2>Design contract</h2>
 * <ul>
 *   <li><strong>String identifiers.</strong> {@code accountId} and
 *       {@code cardNumber} are modelled as {@link String} (never numeric) so the
 *       original fixed-width layout and any significant leading zeros are
 *       preserved byte-for-byte, maintaining interface-contract parity with the
 *       legacy record formats.</li>
 *   <li><strong>PAN masking (security).</strong> The {@code cardNumber} carried
 *       by this element is <em>always a masked Primary Account Number</em>,
 *       exposing only the last four digits (for example {@code ************1234}).
 *       Masking is <em>enforced by the canonical constructor</em>: any supplied
 *       value is reduced to its last four digits, so a full unmasked PAN can never
 *       be stored, serialized, or surfaced through the record's generated
 *       {@code toString()}. Masking is idempotent, so an already-masked value is
 *       carried through unchanged.</li>
 *   <li><strong>Stateless &amp; immutable.</strong> As a Java {@code record} the
 *       type is a thread-safe, side-effect-free value holder carrying no business
 *       logic and performing no mutation of the supplied values.</li>
 * </ul>
 *
 * @param accountId    the account identifier that owns the card, preserved as
 *                     text to retain fixed width and leading zeros; up to eleven
 *                     numeric digits ({@code CARD-ACCT-ID} 9(11))
 * @param cardNumber   the <strong>masked</strong> card number showing only the
 *                     last four digits (for example {@code ************1234});
 *                     never a full PAN; up to sixteen characters
 *                     ({@code CARD-NUM} X(16))
 * @param activeStatus the single-character card active-status flag
 *                     ({@code Y} = active, {@code N} = inactive);
 *                     ({@code CARD-ACTIVE-STATUS} X(01))
 */
public record CardListItem(

        @Size(max = 11)
        @Pattern(regexp = "\\d{1,11}")
        String accountId,

        @Size(max = 16)
        String cardNumber,

        @Size(max = 1)
        String activeStatus) {

    /** Number of trailing PAN digits left visible when masking. */
    private static final int VISIBLE_PAN_DIGITS = 4;

    /**
     * Canonical (compact) constructor that <strong>enforces PAN masking</strong>.
     *
     * <p>{@code cardNumber} is always reduced to its last four digits via
     * {@link #maskCardNumber(String)}, so a full unmasked Primary Account Number
     * can never be stored in — and therefore never serialized from or printed by
     * the generated {@code toString()} of — this response row. Masking is
     * idempotent, so a value that is already masked is carried through unchanged.
     * The other components are accepted verbatim.</p>
     */
    public CardListItem {
        cardNumber = maskCardNumber(cardNumber);
    }

    /**
     * Masks a card number so that only the final four digits remain visible,
     * replacing every earlier character with {@code '*'}. Surrounding whitespace
     * from fixed-width padding is stripped first; {@code null} is returned
     * unchanged and a value of four or fewer characters is returned unchanged
     * (there is nothing additional to conceal).
     *
     * @param cardNumber the card number to mask (may be {@code null})
     * @return the masked card number, or the original value when there is nothing to mask
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        String normalized = cardNumber.strip();
        int length = normalized.length();
        if (length <= VISIBLE_PAN_DIGITS) {
            return normalized;
        }
        return "*".repeat(length - VISIBLE_PAN_DIGITS)
                + normalized.substring(length - VISIBLE_PAN_DIGITS);
    }
}
