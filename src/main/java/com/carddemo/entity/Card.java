package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;

/**
 * JPA persistent entity representing a single credit {@code card}.
 *
 * <p>This entity is a faithful, field-by-field translation of the legacy COBOL
 * copybook {@code app/cpy/CVACT02Y.cpy} ({@code CARD-RECORD}, record length
 * {@code 150}) captured at source commit SHA {@code 27d6c6f}. Each COBOL
 * {@code PIC} clause is mapped to the narrowest Java type that preserves the
 * original value domain:</p>
 *
 * <pre>
 *   CARD-NUM            PIC X(16)  -&gt; cardNum            (VARCHAR(16), natural &#64;Id)
 *   CARD-ACCT-ID        PIC 9(11)  -&gt; cardAcctId         (BIGINT)
 *   CARD-CVV-CD         PIC 9(03)  -&gt; cardCvvCd          (INTEGER)
 *   CARD-EMBOSSED-NAME  PIC X(50)  -&gt; cardEmbossedName   (VARCHAR(50))
 *   CARD-EXPIRAION-DATE PIC X(10)  -&gt; cardExpirationDate (DATE)
 *   CARD-ACTIVE-STATUS  PIC X(01)  -&gt; cardActiveStatus   (VARCHAR(1))
 *   FILLER              PIC X(59)  -&gt; (not mapped; trailing fixed-width padding)
 * </pre>
 *
 * <p>Byte-origin sanity: {@code 16 + 11 + 3 + 50 + 10 + 1 + FILLER(59) = 150},
 * matching the copybook's declared record length.</p>
 *
 * <p>The entity backs three online transactions migrated from CICS/BMS:
 * Card List (CCLI / {@code COCRDLIC}), Card View (CCDL / {@code COCRDSLC}) and
 * Card Update (CCUP / {@code COCRDUPC}). Because {@code COCRDUPC} implements a
 * read-then-rewrite optimistic-concurrency pattern, the entity carries a
 * {@link Version &#64;Version} column so that a concurrent modification is
 * detected and surfaced as an optimistic-lock failure &mdash; preserving the
 * legacy "record changed, please retry" behavior.</p>
 *
 * <p>Mapping notes:</p>
 * <ul>
 *   <li>{@code cardNum} is the natural primary key; it is assigned by the
 *       caller and therefore declares no {@code @GeneratedValue}.</li>
 *   <li>{@code cardAcctId} is intentionally a plain scalar column rather than a
 *       {@code @ManyToOne} association to {@code Account}: the migration
 *       preserves the legacy record shape without introducing new relational
 *       navigation (no scope expansion). Alternate-index lookups by account id
 *       (mirroring the VSAM AIX) are provided as derived queries on the
 *       repository, not on this entity.</li>
 *   <li>The COBOL field name misspelling {@code CARD-EXPIRAION-DATE} is
 *       normalized to {@code cardExpirationDate} / {@code card_expiration_date};
 *       the deviation is recorded in the traceability matrix.</li>
 *   <li>{@code version} is a database-internal optimistic-lock column and is
 *       not part of the fixed 150-byte legacy record layout.</li>
 * </ul>
 *
 * <p>The physical column contract below is authoritative and must match the
 * Flyway migration {@code V1__schema.sql} exactly (the runtime uses
 * {@code spring.jpa.hibernate.ddl-auto=validate}):</p>
 *
 * <pre>
 *   CREATE TABLE card (
 *       card_num             VARCHAR(16) NOT NULL,
 *       card_acct_id         BIGINT,
 *       card_cvv_cd          INTEGER,
 *       card_embossed_name   VARCHAR(50),
 *       card_expiration_date DATE,
 *       card_active_status   VARCHAR(1),
 *       version              BIGINT,
 *       PRIMARY KEY (card_num)
 *   );
 * </pre>
 */
@Entity
@Table(name = "card")
public class Card {

    /**
     * Card number &mdash; the natural primary key.
     *
     * <p>Origin: {@code CARD-NUM PIC X(16)}. A caller-assigned 16-character
     * identifier; there is no surrogate key and hence no
     * {@code @GeneratedValue}.</p>
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Owning account identifier.
     *
     * <p>Origin: {@code CARD-ACCT-ID PIC 9(11)} &rarr; {@code BIGINT}. Modeled
     * as a plain scalar (not a JPA association) to preserve the legacy record
     * shape.</p>
     */
    @Column(name = "card_acct_id")
    private Long cardAcctId;

    /**
     * Card verification value (CVV) code.
     *
     * <p>Origin: {@code CARD-CVV-CD PIC 9(03)} &rarr; {@code INTEGER}.</p>
     */
    @Column(name = "card_cvv_cd")
    private Integer cardCvvCd;

    /**
     * Cardholder name as embossed on the physical card.
     *
     * <p>Origin: {@code CARD-EMBOSSED-NAME PIC X(50)} &rarr; {@code VARCHAR(50)}.</p>
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date.
     *
     * <p>Origin: {@code CARD-EXPIRAION-DATE PIC X(10)} (sic) &rarr; {@code DATE}.
     * The COBOL stored this as a 10-character string; it is represented here as
     * a {@link LocalDate} for type-safe date handling.</p>
     */
    @Column(name = "card_expiration_date")
    private LocalDate cardExpirationDate;

    /**
     * Active status flag for the card.
     *
     * <p>Origin: {@code CARD-ACTIVE-STATUS PIC X(01)} &rarr; {@code VARCHAR(1)}
     * (single-character indicator, e.g. {@code "Y"}/{@code "N"}).</p>
     */
    @Column(name = "card_active_status", length = 1)
    private String cardActiveStatus;

    /**
     * Optimistic-locking version counter managed by the persistence provider.
     *
     * <p>Not part of the legacy 150-byte record. Enables the read-then-rewrite
     * concurrency semantics required by the Card Update transaction
     * ({@code COCRDUPC}).</p>
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * No-argument constructor required by the JPA specification for entity
     * instantiation.
     */
    public Card() {
        // Intentionally empty: JPA instantiates the entity via reflection and
        // populates its state through field/property access.
    }

    /**
     * Returns the card number (primary key).
     *
     * @return the 16-character card number, or {@code null} if unset
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
     * Returns the owning account identifier.
     *
     * @return the account id, or {@code null} if unset
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the owning account identifier.
     *
     * @param cardAcctId the account id
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the CVV code.
     *
     * @return the CVV code, or {@code null} if unset
     */
    public Integer getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the CVV code.
     *
     * @param cardCvvCd the CVV code
     */
    public void setCardCvvCd(Integer cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the embossed cardholder name.
     *
     * @return the embossed name, or {@code null} if unset
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name.
     *
     * @param cardEmbossedName the embossed name (up to 50 characters)
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * @return the expiration date, or {@code null} if unset
     */
    public LocalDate getCardExpirationDate() {
        return cardExpirationDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param cardExpirationDate the expiration date
     */
    public void setCardExpirationDate(LocalDate cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    /**
     * Returns the active status flag.
     *
     * @return the single-character active status, or {@code null} if unset
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the active status flag.
     *
     * @param cardActiveStatus the single-character active status
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the version value managed by the persistence provider, or
     *         {@code null} before the entity is first persisted
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * <p>Normally managed by the persistence provider; a setter is provided for
     * detached-entity merge scenarios and test fixtures.</p>
     *
     * @param version the version value
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
