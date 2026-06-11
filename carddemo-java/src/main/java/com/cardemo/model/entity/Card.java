package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo card record onto the PostgreSQL
 * {@code cards} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the VSAM KSDS
 * dataset {@code CARDDAT}, whose fixed 150-byte record layout is defined by the
 * COBOL copybook {@code app/cpy/CVACT02Y.cpy} ({@code 01 CARD-RECORD}, record
 * length 150). On the mainframe this record was read and updated by the online
 * programs {@code COCRDLIC} (card list / account-filtered browse),
 * {@code COCRDSLC} (single keyed card detail) and {@code COCRDUPC} (card
 * update), and was read by the batch program {@code CBACT02C}. The 16-character
 * card number declared here is the primary key, and the {@link #cardAcctId}
 * column holds the owning account identifier as a plain scalar value, exactly as
 * the flat VSAM record physically stored it.</p>
 *
 * <h2>Original COBOL layout (CVACT02Y.cpy &mdash; RECLN 150)</h2>
 * <pre>{@code
 * 01  CARD-RECORD.
 *     05  CARD-NUM                 PIC X(16).
 *     05  CARD-ACCT-ID             PIC 9(11).
 *     05  CARD-CVV-CD              PIC 9(03).
 *     05  CARD-EMBOSSED-NAME       PIC X(50).
 *     05  CARD-EXPIRAION-DATE      PIC X(10).
 *     05  CARD-ACTIVE-STATUS       PIC X(01).
 *     05  FILLER                   PIC X(59).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code CARDDAT} cluster (16-byte key) becomes a relational table whose
 *       primary key is {@link #cardNum}. Keyed reads/updates served by CICS file
 *       control are replaced by a Spring Data {@code CardRepository}
 *       ({@code JpaRepository<Card, String>}).</li>
 *   <li><strong>Card number (PAN) &rarr; {@link String}.</strong>
 *       {@code CARD-NUM PIC X(16)} is the 16-digit Primary Account Number. It is
 *       kept as a {@link String} (not a numeric type) so leading characters are
 *       preserved exactly and the value matches the keyed VSAM access performed
 *       by {@code COCRDSLC}. It is the table's sole {@code @Id}.</li>
 *   <li><strong>Owning account id &rarr; scalar {@link Long}, not an
 *       association.</strong> {@code CARD-ACCT-ID PIC 9(11)} is mapped to a plain
 *       {@link Long} column {@code account_id} rather than a JPA
 *       {@code @ManyToOne} reference to {@code Account}. This deliberately
 *       mirrors the flat COBOL/VSAM record (the card physically stored the
 *       account id) and keeps this entity free of any compile-time coupling to
 *       {@code Account}. The downstream {@code CardRepository} exposes a derived
 *       query {@code findByCardAcctId(Long)} that powers {@code COCRDLIC}'s
 *       account-filtered card browse. The relationship to {@code accounts} is a
 *       <em>logical</em> foreign key enforced in the Flyway {@code V1} DDL, not
 *       via an object reference.</li>
 *   <li><strong>CVV &rarr; {@link String}.</strong> {@code CARD-CVV-CD PIC 9(03)}
 *       is held as a 3-character {@link String} so leading zeros are preserved
 *       (a CVV of {@code 007} must never collapse to {@code 7}). It is never
 *       logged (see {@link #toString()}).</li>
 *   <li><strong>Active-status flag &rarr; {@link String}, not an enum.</strong>
 *       {@code CARD-ACTIVE-STATUS PIC X(01)} (typically {@code 'Y'} or
 *       {@code 'N'}) is modelled as a single-character {@link String}. AAP §0.4.1
 *       loosely mentions an "active-status enum", but no such enum exists in
 *       {@code model.enums} (only {@code UserType}, {@code FileStatus},
 *       {@code RejectCode} and {@code TransactionSource}); per the Minimal Change
 *       Clause no out-of-scope enum is invented and the single-character contract
 *       is kept for exact parity.</li>
 *   <li><strong>Expiration date &rarr; {@link String}.</strong>
 *       {@code CARD-EXPIRAION-DATE PIC X(10)} (the COBOL field name misspells
 *       "expiration"; the Java field uses the correct spelling while the
 *       underlying contract is unchanged) is a fixed 10-character text date
 *       ({@code YYYY-MM-DD}) preserved as a {@link String} for byte-level
 *       external-interface fidelity (AAP §0.7.2). Date <em>validation</em> is a
 *       separate concern handled by {@code service.shared.DateValidationService}
 *       (using {@code LocalDate}), not by this persistence entity.</li>
 *   <li><strong>Read-update snapshot comparison &rarr; {@code @Version}.</strong>
 *       {@code COCRDUPC} re-read the card record and compared the before-image to
 *       the stored row to detect concurrent modification prior to its
 *       {@code REWRITE}. That snapshot comparison is replaced by the JPA
 *       {@link Version} column {@link #version}; concurrent modification then
 *       surfaces as an {@code OptimisticLockException}, which the card-update
 *       service maps to a typed concurrency exception. Per AAP §0.7.5,
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card}; {@code Card} is the second (and last) optimistic-locking
 *       entity. There is no corresponding COBOL field for {@link #version}.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(59)} is reserved padding that pads the record to its
 *       150-byte length; it carries no business data and is intentionally
 *       <strong>not</strong> mapped to a column. The 150-byte record length
 *       (16 + 11 + 3 + 50 + 10 + 1 + 59) is documented here for external-contract
 *       reference only.</li>
 * </ul>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code card_number},
 * {@code account_id}, {@code cvv_code}, {@code embossed_name},
 * {@code expiration_date}, {@code active_status} and {@code version} &mdash; are
 * authoritative. The Flyway {@code V1__create_schema.sql} {@code cards} DDL must
 * declare {@code card_number} as the {@code VARCHAR(16)} primary key,
 * {@code account_id} as {@code BIGINT} with a logical foreign key to
 * {@code accounts.account_id}, {@code cvv_code} as {@code VARCHAR(3)},
 * {@code embossed_name} as {@code VARCHAR(50)}, {@code expiration_date} as
 * {@code VARCHAR(10)}, {@code active_status} as {@code VARCHAR(1)} (or
 * {@code CHAR(1)}) and {@code version} as {@code BIGINT}. The {@code V3} seed
 * (derived from {@code app/data/ASCII/carddata.txt}, 150-byte records) and the
 * {@code CardRepository} must align with these names and lengths.</p>
 *
 * <p>Per the Minimal Change Clause this entity is a pure persistence type: it
 * declares exactly the six mapped record fields plus the {@code @Version}
 * column, carries no Jakarta Bean Validation annotations (input validation lives
 * in the request DTO layer, AAP §0.4.2), and models no JPA associations &mdash;
 * the foreign key to {@code accounts} is the plain scalar column
 * {@link #cardAcctId}, mirroring the original VSAM keyed-access pattern (no
 * {@code @ManyToOne}).</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see Account
 */
@Entity
@Table(name = "cards")
public class Card {

    /**
     * Primary key &mdash; the 16-digit card number (Primary Account Number).
     *
     * <p>Migrated from {@code CARD-NUM PIC X(16)}. Kept as a {@link String}
     * (PostgreSQL {@code VARCHAR(16)}) rather than a numeric type so that any
     * leading characters are preserved exactly and the value matches the keyed
     * VSAM access performed by {@code COCRDSLC}. The downstream repository is
     * therefore {@code JpaRepository<Card, String>}.</p>
     */
    // CARD-NUM PIC X(16) -> 16-char card number (PAN) primary key -> String(16) (VARCHAR(16))
    @Id
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNum;

    /**
     * Owning account identifier &mdash; a scalar foreign key, not an association.
     *
     * <p>Migrated from {@code CARD-ACCT-ID PIC 9(11)}: an eleven-digit unsigned
     * integer, mapped to {@link Long} (PostgreSQL {@code BIGINT}) because eleven
     * digits exceed {@code Integer}'s ~2.1-billion ceiling and to stay
     * type-consistent with {@code Account.acctId}. This is a <em>logical</em>
     * foreign key to {@code accounts.account_id} (scalar, per the VSAM contract);
     * it is deliberately <strong>not</strong> a {@code @ManyToOne} reference, so
     * this entity imports nothing from {@code Account}. The
     * {@code CardRepository} exposes {@code findByCardAcctId(Long)} for
     * {@code COCRDLIC}'s account-filtered browse.</p>
     */
    // CARD-ACCT-ID PIC 9(11) -> 11-digit owning account id -> Long (BIGINT); scalar FK to accounts.account_id (NOT @ManyToOne)
    @Column(name = "account_id", nullable = false)
    private Long cardAcctId;

    /**
     * Card verification value (CVV).
     *
     * <p>Migrated from {@code CARD-CVV-CD PIC 9(03)}: a three-digit code held as a
     * {@link String} (PostgreSQL {@code VARCHAR(3)}) so leading zeros are
     * preserved exactly &mdash; a CVV of {@code 007} must not become {@code 7}.
     * This value is sensitive and is deliberately excluded from
     * {@link #toString()}.</p>
     */
    // CARD-CVV-CD PIC 9(03) -> 3-digit CVV; String preserves leading zeros (007 != 7) -> String(3)
    @Column(name = "cvv_code", length = 3)
    private String cardCvvCd;

    /**
     * Cardholder name as embossed on the physical card.
     *
     * <p>Migrated from {@code CARD-EMBOSSED-NAME PIC X(50)}: a fixed 50-character
     * alphanumeric field, modelled as a {@link String} of length fifty
     * (PostgreSQL {@code VARCHAR(50)}).</p>
     */
    // CARD-EMBOSSED-NAME PIC X(50) -> 50-char embossed cardholder name -> String(50)
    @Column(name = "embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date.
     *
     * <p>Migrated from {@code CARD-EXPIRAION-DATE PIC X(10)} (the COBOL field name
     * misspells "expiration"; the Java field uses the correct spelling while the
     * underlying contract is unchanged). A fixed 10-character text date
     * ({@code YYYY-MM-DD}) preserved as a {@link String} (PostgreSQL
     * {@code VARCHAR(10)}) for byte-level external-interface fidelity; no
     * {@code LocalDate} conversion is performed here.</p>
     */
    // CARD-EXPIRAION-DATE PIC X(10) -> fixed 10-char text date (YYYY-MM-DD) -> String(10)
    @Column(name = "expiration_date", length = 10)
    private String cardExpirationDate;

    /**
     * Card active-status flag.
     *
     * <p>Migrated from {@code CARD-ACTIVE-STATUS PIC X(01)}: a single character
     * (typically {@code 'Y'} or {@code 'N'}), modelled as a {@link String} of
     * length one (PostgreSQL {@code VARCHAR(1)}/{@code CHAR(1)}). AAP §0.4.1
     * loosely mentions an "active-status enum", but no such enum exists in
     * {@code model.enums}; per the Minimal Change Clause no out-of-scope enum is
     * invented and the single-character contract is kept for exact parity.</p>
     */
    // CARD-ACTIVE-STATUS PIC X(01) -> single-character flag ('Y'/'N'); no enum -> String(1)
    @Column(name = "active_status", length = 1)
    private String cardActiveStatus;

    /**
     * Optimistic-locking version counter.
     *
     * <p>This field has <strong>no</strong> COBOL counterpart. It is the JPA
     * replacement for the read-update snapshot comparison that {@code COCRDUPC}
     * performed before its {@code REWRITE} (AAP §0.7.5). Hibernate increments the
     * {@code version} column on each update and raises an
     * {@code OptimisticLockException} when a stale copy is written, preserving
     * the original concurrency safeguard with declarative JPA semantics.
     * {@code Card} is the second (and last) entity to carry {@code @Version},
     * alongside {@code Account}.</p>
     */
    // (no COBOL field) -> JPA optimistic-locking replacement for COCRDUPC snapshot compare
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the entity reflectively before populating its fields.
     */
    public Card() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the primary-key card number ({@code CARD-NUM}).
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the primary-key card number ({@code CARD-NUM}).
     *
     * @param cardNum the 16-character card number to set
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the owning account identifier ({@code CARD-ACCT-ID}).
     *
     * <p>This is a scalar foreign key to {@code accounts.account_id}; it is not a
     * JPA association.</p>
     *
     * @return the owning account identifier, or {@code null} if unset
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the owning account identifier ({@code CARD-ACCT-ID}).
     *
     * @param cardAcctId the owning account identifier to set
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification value ({@code CARD-CVV-CD}).
     *
     * <p>Returned as a {@link String} so leading zeros are preserved. This value
     * is sensitive; do not write it to logs.</p>
     *
     * @return the 3-character CVV, or {@code null} if unset
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the card verification value ({@code CARD-CVV-CD}).
     *
     * @param cardCvvCd the 3-character CVV to set
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the embossed cardholder name ({@code CARD-EMBOSSED-NAME}).
     *
     * @return the embossed cardholder name, or {@code null} if unset
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name ({@code CARD-EMBOSSED-NAME}).
     *
     * @param cardEmbossedName the embossed cardholder name to set
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date ({@code CARD-EXPIRAION-DATE}) as text.
     *
     * @return the 10-character expiration date, or {@code null} if unset
     */
    public String getCardExpirationDate() {
        return cardExpirationDate;
    }

    /**
     * Sets the card expiration date ({@code CARD-EXPIRAION-DATE}) as text.
     *
     * @param cardExpirationDate the 10-character expiration date to set
     */
    public void setCardExpirationDate(String cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    /**
     * Returns the active-status flag ({@code CARD-ACTIVE-STATUS}).
     *
     * @return the single-character active-status flag, or {@code null} if unset
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the active-status flag ({@code CARD-ACTIVE-STATUS}).
     *
     * @param cardActiveStatus the single-character active-status flag to set
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * <p>This value is managed by the JPA provider; application code typically
     * does not set it directly. A setter is nonetheless provided so the field is
     * a complete JavaBean property.</p>
     *
     * @return the version counter, or {@code null} for a not-yet-persisted entity
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * <p>Normally managed by the JPA provider; exposed for completeness and for
     * use in tests or detached-entity merges.</p>
     *
     * @param version the version counter to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Identity-based equality keyed on the card number ({@link #cardNum}).
     *
     * <p>Two {@code Card} instances are equal when they are of the exact same
     * class and share the same {@link #cardNum}. The primary key alone defines
     * entity identity; mutable business columns are deliberately excluded so
     * equality stays stable across updates. Exact-class comparison (rather than
     * {@code instanceof}) is used so a proxy/subclass is not treated as equal to
     * a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Card} with an equal card number
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
     * Hash code derived solely from {@link #cardNum}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Diagnostic representation that intentionally protects cardholder data. The
     * card number (PAN) is masked to its last four digits, and the sensitive
     * verification value ({@link #cardCvvCd}) and the cardholder's embossed name
     * ({@link #cardEmbossedName}, personally identifiable information) are
     * <strong>excluded entirely</strong>. Only the masked PAN, owning account id,
     * expiration date, active-status flag and optimistic-locking version are
     * included, so this value is safe to emit to logs.
     *
     * @return a human-readable, non-sensitive description of this card
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + maskedCardNumber() + '\''
                + ", cardAcctId=" + cardAcctId
                + ", cardExpirationDate='" + cardExpirationDate + '\''
                + ", cardActiveStatus='" + cardActiveStatus + '\''
                + ", version=" + version
                + '}';
    }

    /**
     * Masks the card number (PAN) for safe inclusion in {@link #toString()},
     * revealing at most the final four digits and replacing every preceding
     * character with {@code '*'}. A {@code null} value yields {@code "null"}, and
     * a value of four or fewer characters is fully masked so no digits leak.
     *
     * @return the masked card number suitable for logging
     */
    private String maskedCardNumber() {
        if (cardNum == null) {
            return "null";
        }
        int length = cardNum.length();
        if (length <= 4) {
            // Too short to reveal a last-four suffix safely; mask completely.
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + cardNum.substring(length - 4);
    }
}

