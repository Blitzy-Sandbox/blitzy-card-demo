package com.cardemo.model.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object for Card list, detail, and update API payloads.
 *
 * <p>Consolidates fields from three COBOL BMS symbolic maps:
 * <ul>
 *   <li>COCRDLI.CPY (CCRDLIAI): Card list browse screen — 7 rows/page with
 *       ACCTNOnI PIC X(11), CRDNUMnI PIC X(16), CRDSTSnI PIC X(1) per row</li>
 *   <li>COCRDSL.CPY (CCRDSLAI): Card detail screen — ACCTSIDI PIC X(11),
 *       CARDSIDI PIC X(16), CRDNAMEI PIC X(50), CRDSTCDI PIC X(1),
 *       EXPMONI PIC X(2), EXPYEARI PIC X(4)</li>
 *   <li>COCRDUP.CPY (CCRDUPAI): Card update screen — same as detail plus
 *       EXPDAYI PIC X(2) for day component</li>
 * </ul>
 *
 * <p>Maps to CVACT02Y.cpy CARD-RECORD layout (150-byte VSAM record):
 * <ul>
 *   <li>CARD-NUM PIC X(16) → cardNum</li>
 *   <li>CARD-ACCT-ID PIC 9(11) → cardAcctId</li>
 *   <li>CARD-CVV-CD PIC 9(03) → cardCvvCd</li>
 *   <li>CARD-EMBOSSED-NAME PIC X(50) → cardEmbossedName</li>
 *   <li>CARD-EXPIRAION-DATE PIC X(10) → cardExpDate</li>
 *   <li>CARD-ACTIVE-STATUS PIC X(01) → cardActiveStatus</li>
 * </ul>
 *
 * <p>Used by {@code CardController} GET/PUT {@code /api/cards/*} endpoints.
 * The COBOL programs COCRDLIC.cbl, COCRDSLC.cbl, and COCRDUPC.cbl
 * interact with CARDDAT and ACCTDAT VSAM datasets.
 *
 * <p><strong>Security notes (PCI compliance):</strong>
 * <ul>
 *   <li>cardNum is masked in {@link #toString()} to show only the last 4 digits</li>
 *   <li>cardCvvCd is excluded from {@link #toString()} entirely</li>
 * </ul>
 */
public class CardDto {

    /**
     * Card number — 16-digit card identifier.
     * Maps to CARD-NUM PIC X(16) in CVACT02Y.cpy and CARDSIDI PIC X(16)
     * in the BMS symbolic maps. Stored as String to preserve leading zeros
     * in numeric card identifiers (COBOL PIC X allows alphanumeric content).
     */
    @Size(max = 16)
    private String cardNum;

    /**
     * Account ID — 11-digit account identifier (foreign key to Account entity).
     * Maps to CARD-ACCT-ID PIC 9(11) in CVACT02Y.cpy and ACCTSIDI PIC X(11)
     * in the BMS symbolic maps. Stored as String to preserve leading zeros
     * in numeric account identifiers.
     */
    @Size(max = 11)
    private String cardAcctId;

    /**
     * Embossed name — cardholder name as printed on the physical card.
     * Maps to CARD-EMBOSSED-NAME PIC X(50) in CVACT02Y.cpy and
     * CRDNAMEI PIC X(50) in the BMS symbolic maps.
     */
    @Size(max = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date.
     * Composed from separate COBOL BMS screen fields:
     * <ul>
     *   <li>EXPMONI PIC X(2) — expiry month (detail and update screens)</li>
     *   <li>EXPDAYI PIC X(2) — expiry day (update screen only, from COCRDUP.CPY)</li>
     *   <li>EXPYEARI PIC X(4) — expiry year (detail and update screens)</li>
     * </ul>
     * Combined into a single {@link LocalDate} for the REST API.
     * Maps to CARD-EXPIRAION-DATE PIC X(10) in CVACT02Y.cpy.
     */
    private LocalDate cardExpDate;

    /**
     * Card active status code — single character status indicator.
     * Maps to CARD-ACTIVE-STATUS PIC X(01) in CVACT02Y.cpy and
     * CRDSTCDI PIC X(1) in the BMS symbolic maps.
     * Typical values: 'Y' = active, 'N' = inactive.
     */
    @Size(max = 1)
    private String cardActiveStatus;

    /**
     * Card Verification Value (CVV) code.
     * Maps to CARD-CVV-CD PIC 9(03) in CVACT02Y.cpy.
     * Not displayed on BMS terminal screens for security; included in the DTO
     * for API-level create and update operations. Max length is 4 to accommodate
     * both 3-digit CVV and 4-digit CID formats.
     * Excluded from {@link #toString()} output for PCI compliance.
     */
    @Size(max = 4)
    private String cardCvvCd;

    /**
     * JPA optimistic locking version field.
     * Exposes the {@code @Version} field from the Card entity to API consumers,
     * enabling optimistic concurrency control through the REST API (AAP §0.8.4).
     * Clients must include the version from the GET response in PUT requests;
     * a version mismatch triggers HTTP 409 Conflict (maps to COCRDUPC.cbl
     * paragraph 9300-CHECK-CHANGE-IN-REC snapshot comparison).
     */
    private Integer version;

    /**
     * Default no-args constructor required for framework deserialization
     * (Jackson JSON binding, Spring MVC request body mapping).
     */
    public CardDto() {
    }

    /**
     * All-args constructor for programmatic construction of CardDto instances.
     *
     * @param cardNum          card number, up to 16 characters
     *                         (maps to CARD-NUM PIC X(16))
     * @param cardAcctId       account identifier, up to 11 characters
     *                         (maps to CARD-ACCT-ID PIC 9(11))
     * @param cardEmbossedName embossed cardholder name, up to 50 characters
     *                         (maps to CARD-EMBOSSED-NAME PIC X(50))
     * @param cardExpDate      card expiration date
     *                         (composed from EXPMONI/EXPDAYI/EXPYEARI BMS fields)
     * @param cardActiveStatus active status code, single character
     *                         (maps to CARD-ACTIVE-STATUS PIC X(01))
     * @param cardCvvCd        CVV code, up to 4 characters
     *                         (maps to CARD-CVV-CD PIC 9(03))
     */
    public CardDto(String cardNum, String cardAcctId, String cardEmbossedName,
                   LocalDate cardExpDate, String cardActiveStatus, String cardCvvCd) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpDate = cardExpDate;
        this.cardActiveStatus = cardActiveStatus;
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the card number.
     *
     * @return card number string, up to 16 characters
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number.
     *
     * @param cardNum card number, up to 16 characters
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the account identifier.
     *
     * @return account ID string, up to 11 characters
     */
    public String getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param cardAcctId account identifier, up to 11 characters
     */
    public void setCardAcctId(String cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the embossed cardholder name.
     *
     * @return embossed name string, up to 50 characters
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name.
     *
     * @param cardEmbossedName embossed name, up to 50 characters
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * @return card expiration date as {@link LocalDate}
     */
    public LocalDate getCardExpDate() {
        return cardExpDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param cardExpDate card expiration date
     */
    public void setCardExpDate(LocalDate cardExpDate) {
        this.cardExpDate = cardExpDate;
    }

    /**
     * Returns the card active status code.
     *
     * @return active status code, single character ('Y' or 'N')
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the card active status code.
     *
     * @param cardActiveStatus active status code, single character
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the CVV code.
     *
     * @return CVV code string, up to 4 characters
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the CVV code.
     *
     * @param cardCvvCd CVV code, up to 4 characters
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the JPA optimistic locking version.
     *
     * @return current version number, or {@code null} if not yet persisted
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * Sets the JPA optimistic locking version.
     *
     * @param version version number from a previous GET response
     */
    public void setVersion(Integer version) {
        this.version = version;
    }

    /**
     * Returns a string representation of this CardDto with PCI-compliant masking.
     *
     * <p>Security considerations:
     * <ul>
     *   <li>cardNum is masked to show only the last 4 digits
     *       (e.g., "4111111111111111" → "************1111")</li>
     *   <li>cardCvvCd is completely excluded from the output</li>
     * </ul>
     *
     * @return formatted string with card identification fields
     */
    @Override
    public String toString() {
        return "CardDto{" +
                "cardNum='" + maskCardNumber(cardNum) + '\'' +
                ", cardAcctId='" + cardAcctId + '\'' +
                ", cardEmbossedName='" + cardEmbossedName + '\'' +
                ", cardExpDate=" + cardExpDate +
                ", cardActiveStatus='" + cardActiveStatus + '\'' +
                '}';
    }

    /**
     * Masks a card number for secure logging and display purposes.
     * Shows only the last 4 digits, replacing all preceding characters
     * with asterisks. Handles null input and short values safely.
     *
     * @param number the card number to mask
     * @return masked card number string; "null" if input is null,
     *         fully masked if input is 4 characters or fewer
     */
    private static String maskCardNumber(String number) {
        if (number == null) {
            return "null";
        }
        int length = number.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + number.substring(length - 4);
    }
}
