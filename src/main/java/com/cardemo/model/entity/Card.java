package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL {@code CARD-RECORD} (150 bytes) from
 * {@code app/cpy/CVACT02Y.cpy} to the PostgreSQL {@code card} table.
 *
 * <p>Each card record belongs to an account (logical foreign key via
 * {@code cardAcctId}). This entity is central to the credit-card management
 * subsystem, supporting paginated browsing, single-record detail views,
 * and optimistic-concurrency-controlled updates.</p>
 *
 * <h3>Source COBOL Structure (CVACT02Y.cpy)</h3>
 * <pre>
 * 01 CARD-RECORD.
 *     05  CARD-NUM               PIC X(16).   — Primary key (card number)
 *     05  CARD-ACCT-ID           PIC 9(11).   — FK to Account (logical)
 *     05  CARD-CVV-CD            PIC 9(03).   — Card verification value
 *     05  CARD-EMBOSSED-NAME     PIC X(50).   — Cardholder embossed name
 *     05  CARD-EXPIRAION-DATE    PIC X(10).   — Expiration date (COBOL typo corrected)
 *     05  CARD-ACTIVE-STATUS     PIC X(01).   — Active status flag ('Y'/'N')
 *     05  FILLER                 PIC X(59).   — Not mapped
 * </pre>
 *
 * <h3>COBOL Usage Context</h3>
 * <ul>
 *   <li>COCRDLIC.cbl — Card list: paginated browse (7 rows/page), account/card range filtering</li>
 *   <li>COCRDSLC.cbl — Card detail: single card read by CARD-NUM</li>
 *   <li>COCRDUPC.cbl — Card update: optimistic concurrency via before/after image comparison
 *       (mapped to JPA {@code @Version})</li>
 *   <li>CBACT02C.cbl — Card file reader utility batch program</li>
 * </ul>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>{@code PIC 9(nn)} fields ({@code cardAcctId}, {@code cardCvvCd}) are mapped to
 *       {@code String} to preserve leading zeros — COBOL numeric-display semantics.</li>
 *   <li>{@code CARD-EXPIRAION-DATE PIC X(10)} is mapped to {@code java.time.LocalDate},
 *       correcting the original COBOL typo "EXPIRAION" → "Exp".</li>
 *   <li>The 59-byte FILLER is not mapped — it has no business meaning.</li>
 *   <li>{@code @Version} provides optimistic locking equivalent to COCRDUPC.cbl's
 *       before/after record image comparison pattern.</li>
 *   <li>No {@code @ManyToOne} annotation on {@code cardAcctId}: the COBOL application
 *       uses logical relationships without VSAM referential integrity; this design
 *       mirrors that simplicity while still enabling JPA repository join queries.</li>
 * </ul>
 *
 * @see com.cardemo.repository.CardRepository
 */
@Entity
@Table(name = "cards")
public class Card {

    // -----------------------------------------------------------------------
    // Primary Key
    // -----------------------------------------------------------------------

    /**
     * Card number — primary key.
     * Maps COBOL {@code CARD-NUM PIC X(16)}.
     * 16-character string representing the credit card number used as the
     * unique identifier for all card operations (list, detail, update).
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    // -----------------------------------------------------------------------
    // Fields — Exact COBOL PIC Mapping
    // -----------------------------------------------------------------------

    /**
     * Account identifier — logical foreign key to the Account entity.
     * Maps COBOL {@code CARD-ACCT-ID PIC 9(11)}.
     * Stored as {@code String} (not numeric type) to preserve leading zeros,
     * matching the COBOL numeric-display format. COBOL VSAM does not enforce
     * referential integrity; this is a logical relationship only.
     */
    @Column(name = "card_acct_id", length = 11)
    private String cardAcctId;

    /**
     * Card verification value (CVV) code.
     * Maps COBOL {@code CARD-CVV-CD PIC 9(03)}.
     * Stored as {@code String} to preserve leading zeros (e.g., "001").
     * In the COBOL application this is a 3-digit numeric-display field.
     */
    @Column(name = "card_cvv_cd", length = 3)
    private String cardCvvCd;

    /**
     * Cardholder embossed name — the name printed on the physical card.
     * Maps COBOL {@code CARD-EMBOSSED-NAME PIC X(50)}.
     * 50-character alphanumeric string.
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date.
     * Maps COBOL {@code CARD-EXPIRAION-DATE PIC X(10)} — note the original
     * COBOL field name contains a typo ("EXPIRAION"). The Java field name
     * corrects this to {@code cardExpDate}.
     *
     * <p>The COBOL representation is a 10-character string (e.g., "2025-12-31").
     * In Java, this is mapped to {@link LocalDate} for proper temporal operations
     * including expiration checks in batch validation (CBTRN02C reject code 103).</p>
     */
    @Column(name = "expiration_date")
    private LocalDate cardExpDate;

    /**
     * Card active status flag.
     * Maps COBOL {@code CARD-ACTIVE-STATUS PIC X(01)}.
     * Single character: {@code 'Y'} for active, {@code 'N'} for inactive.
     * Used by batch validation (CBTRN02C) to reject transactions on inactive cards.
     */
    @Column(name = "active_status", columnDefinition = "CHAR(1)")
    @JdbcTypeCode(Types.CHAR)
    private String cardActiveStatus;

    // -----------------------------------------------------------------------
    // Optimistic Locking — @Version
    // -----------------------------------------------------------------------

    /**
     * JPA optimistic locking version field.
     * Provides automatic concurrent-modification detection equivalent to
     * COCRDUPC.cbl's before/after record image comparison pattern.
     *
     * <p>When two concurrent requests attempt to update the same card record,
     * the second update will receive an {@code OptimisticLockException}
     * because the version will have been incremented by the first update.
     * This preserves the COBOL concurrency-control semantics without
     * explicit programmatic comparison of field values.</p>
     */
    @Version
    @Column(name = "version")
    private Integer version;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-args constructor required by JPA specification.
     * Hibernate and other JPA providers use this constructor to instantiate
     * entity instances when loading data from the database.
     */
    public Card() {
        // JPA-required no-args constructor
    }

    /**
     * All-args constructor for programmatic entity creation.
     * Initializes all business fields; the {@code version} field is managed
     * by JPA and is not included in this constructor.
     *
     * @param cardNum          the 16-character card number (primary key)
     * @param cardAcctId       the 11-character account identifier (logical FK)
     * @param cardCvvCd        the 3-character CVV code
     * @param cardEmbossedName the cardholder embossed name (up to 50 characters)
     * @param cardExpDate      the card expiration date
     * @param cardActiveStatus the active status flag ('Y' or 'N')
     */
    public Card(String cardNum, String cardAcctId, String cardCvvCd,
                String cardEmbossedName, LocalDate cardExpDate,
                String cardActiveStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpDate = cardExpDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the card number (primary key).
     *
     * @return the 16-character card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number (primary key).
     *
     * @param cardNum the 16-character card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the account identifier.
     * This is a logical foreign key to the Account entity's
     * {@code acctId} field.
     *
     * @return the 11-character account ID
     */
    public String getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param cardAcctId the 11-character account ID
     */
    public void setCardAcctId(String cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification value (CVV) code.
     *
     * @return the 3-character CVV code (leading zeros preserved)
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the card verification value (CVV) code.
     *
     * @param cardCvvCd the 3-character CVV code
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the cardholder embossed name.
     *
     * @return the embossed name (up to 50 characters)
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the cardholder embossed name.
     *
     * @param cardEmbossedName the embossed name (up to 50 characters)
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * @return the expiration date as a {@link LocalDate}
     */
    public LocalDate getCardExpDate() {
        return cardExpDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param cardExpDate the expiration date
     */
    public void setCardExpDate(LocalDate cardExpDate) {
        this.cardExpDate = cardExpDate;
    }

    /**
     * Returns the card active status flag.
     *
     * @return {@code "Y"} if active, {@code "N"} if inactive
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the card active status flag.
     *
     * @param cardActiveStatus the status flag ({@code "Y"} or {@code "N"})
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the JPA optimistic locking version.
     * This value is managed automatically by Hibernate — it should not be
     * set manually in normal application code.
     *
     * @return the current version number, or {@code null} for new entities
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * Sets the JPA optimistic locking version.
     * Provided for framework use and testing. Application code should
     * not normally call this method — the JPA provider manages the version.
     *
     * @param version the version number
     */
    public void setVersion(Integer version) {
        this.version = version;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this entity with another object for equality based on the
     * primary key ({@code cardNum}). This follows the JPA best practice
     * of using the business/natural key for entity identity comparison.
     *
     * <p>The {@code version} field is intentionally excluded from equality
     * to ensure consistent behavior across different persistence contexts
     * and entity states (transient, managed, detached).</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same card record
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Card card = (Card) o;
        return Objects.equals(cardNum, card.cardNum);
    }

    /**
     * Returns a hash code based on the primary key ({@code cardNum}).
     * Consistent with {@link #equals(Object)} — only the primary key field
     * participates in the hash computation.
     *
     * @return hash code derived from the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Returns a string representation of this card entity including all
     * mapped fields for debugging and logging purposes.
     *
     * <p>Note: In production logging, consider masking the card number
     * (show last 4 digits only). This {@code toString()} provides full
     * field visibility for development and troubleshooting.</p>
     *
     * @return a descriptive string with all card fields
     */
    @Override
    public String toString() {
        return "Card{" +
                "cardNum='" + cardNum + '\'' +
                ", cardAcctId='" + cardAcctId + '\'' +
                ", cardCvvCd='" + cardCvvCd + '\'' +
                ", cardEmbossedName='" + cardEmbossedName + '\'' +
                ", cardExpDate=" + cardExpDate +
                ", cardActiveStatus='" + cardActiveStatus + '\'' +
                ", version=" + version +
                '}';
    }
}
