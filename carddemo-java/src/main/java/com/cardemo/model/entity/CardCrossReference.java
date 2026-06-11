package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo card cross-reference record onto the
 * PostgreSQL {@code card_cross_references} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the VSAM KSDS
 * dataset {@code CARDXREF}, whose fixed 50-byte record layout is defined by the
 * COBOL copybook {@code app/cpy/CVACT03Y.cpy} ({@code 01 CARD-XREF-RECORD},
 * record length 50). It is the small but operationally critical <em>junction</em>
 * record that links a card to both its owning customer and its owning account. On
 * the mainframe this record was read by the online programs {@code COACTVWC}
 * (account view), {@code COCRDLIC} (card list / account-filtered browse) and
 * {@code COTRN02C} (add transaction), and by the batch programs {@code CBACT03C}
 * (cross-reference file read), {@code CBTRN02C} (daily transaction posting) and
 * {@code CBSTM03A} (statement generation).</p>
 *
 * <p>Two access paths existed in VSAM and both are preserved by the relational
 * mapping. The primary KSDS key is the 16-character card number
 * ({@code XREF-CARD-NUM}), which yields the card&nbsp;&rarr;&nbsp;(customer,
 * account) resolution and becomes this entity's sole {@code @Id}. The alternate
 * index {@code CXACAIX} is keyed on the account id ({@code XREF-ACCT-ID}) and
 * yields the reverse account&nbsp;&rarr;&nbsp;cards resolution used by the
 * account-filtered browses in {@code COACTVWC}/{@code COCRDLIC}/{@code COTRN02C}.
 * That alternate-index access path becomes the derived repository query
 * {@code CardCrossReferenceRepository.findByXrefAcctId(Long)} (a
 * {@code JpaRepository<CardCrossReference, String>}), backed by a secondary index
 * created on {@code account_id} in the Flyway {@code V2} migration (AAP §0.4.1).</p>
 *
 * <h2>Original COBOL layout (CVACT03Y.cpy &mdash; RECLN 50)</h2>
 * <pre>{@code
 * 01  CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM            PIC X(16).
 *     05  XREF-CUST-ID             PIC 9(09).
 *     05  XREF-ACCT-ID             PIC 9(11).
 *     05  FILLER                   PIC X(14).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code CARDXREF} cluster (16-byte key) becomes a relational table whose
 *       primary key is {@link #xrefCardNum}. Keyed reads served by CICS file
 *       control are replaced by a Spring Data {@code CardCrossReferenceRepository}
 *       ({@code JpaRepository<CardCrossReference, String>}).</li>
 *   <li><strong>Card number (PAN) &rarr; {@link String} primary key.</strong>
 *       {@code XREF-CARD-NUM PIC X(16)} is the 16-digit Primary Account Number. It
 *       is kept as a {@link String} (PostgreSQL {@code VARCHAR(16)}), not a numeric
 *       type, so leading characters are preserved exactly and the value matches the
 *       keyed VSAM access and {@code Card.cardNum}. It is the table's sole
 *       {@code @Id}.</li>
 *   <li><strong>Customer id &rarr; scalar {@link Long}, not an association.</strong>
 *       {@code XREF-CUST-ID PIC 9(09)} is an unsigned nine-digit identifier mapped
 *       to {@link Long} (PostgreSQL {@code BIGINT}), consistent with
 *       {@code Customer.custId}. It is modelled as a plain scalar column
 *       ({@code customer_id}) rather than a JPA {@code @ManyToOne} reference to
 *       {@code Customer}: this deliberately mirrors the flat COBOL/VSAM record (the
 *       cross-reference physically stored the customer id) and keeps this entity
 *       free of any compile-time coupling to {@code Customer}. The relationship to
 *       {@code customers} is a <em>logical</em> foreign key enforced in the DDL,
 *       not via an object reference.</li>
 *   <li><strong>Account id &rarr; scalar {@link Long}; the {@code CXACAIX}
 *       alternate-index column.</strong> {@code XREF-ACCT-ID PIC 9(11)} is an
 *       unsigned eleven-digit identifier mapped to {@link Long} (PostgreSQL
 *       {@code BIGINT}, because eleven digits exceed {@code Integer}'s
 *       ~2.1-billion ceiling) and column {@code account_id}, type-consistent with
 *       {@code Account.acctId}. This is the column on which the VSAM alternate
 *       index {@code CXACAIX} was keyed; it is the single most important contract
 *       on this entity. It is therefore the target of the derived query
 *       {@code findByXrefAcctId(Long)} and is backed by a dedicated secondary
 *       index in the Flyway {@code V2} migration so that account&rarr;cards lookups
 *       remain index-served. Like the customer id it is a <em>logical</em> foreign
 *       key (to {@code accounts.account_id}) and is deliberately
 *       <strong>not</strong> a {@code @ManyToOne} reference.</li>
 *   <li><strong>No optimistic locking.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the two read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). The cross-reference record is never updated in place
 *       through a read-update snapshot comparison, so this entity intentionally
 *       carries <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(14)} is reserved padding that pads the record to its
 *       50-byte length ({@code 16 + 9 + 11 + 14 = 50}); it carries no business
 *       data and is intentionally <strong>not</strong> mapped to a column. The
 *       50-byte record length is documented here for external-contract reference
 *       only (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code card_number} (primary
 * key), {@code customer_id} and {@code account_id} &mdash; are authoritative for
 * the data layer. The Flyway {@code V1__create_schema.sql} cross-reference table
 * must declare {@code card_number} as the {@code VARCHAR(16)} primary key,
 * {@code customer_id} as {@code BIGINT} (logical foreign key to
 * {@code customers.customer_id}) and {@code account_id} as {@code BIGINT} (logical
 * foreign key to {@code accounts.account_id}); the {@code V2} migration adds a
 * secondary index on {@code account_id} to back the {@code CXACAIX} alternate-index
 * query; and the {@code V3} seed (loaded from {@code app/data/ASCII/cardxref.txt})
 * must align with these names and lengths.</p>
 *
 * <p>Per the Minimal Change Clause this entity is a pure persistence type: it
 * declares exactly the three mapped record fields, carries <strong>no</strong>
 * {@code @Version} column, carries no Jakarta Bean Validation annotations (input
 * validation lives in the request DTO layer, AAP §0.4.2), and models no JPA
 * associations &mdash; both foreign keys are plain scalar {@link Long} columns,
 * mirroring the original VSAM keyed-access pattern (no {@code @ManyToOne}).</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository.</p>
 *
 * @see Card
 * @see Account
 * @see Customer
 */
@Entity
@Table(name = "card_xref")
public class CardCrossReference {

    /**
     * Primary key &mdash; the 16-digit card number (Primary Account Number) that
     * links this cross-reference to a single card.
     *
     * <p>Migrated from {@code XREF-CARD-NUM PIC X(16)}. Kept as a {@link String}
     * (PostgreSQL {@code VARCHAR(16)}) rather than a numeric type so that any
     * leading characters are preserved exactly and the value matches both the keyed
     * VSAM access and {@code Card.cardNum}. The downstream repository is therefore
     * {@code JpaRepository<CardCrossReference, String>}.</p>
     */
    // XREF-CARD-NUM PIC X(16) -> 16-char card number (PAN), primary VSAM key -> String(16) (VARCHAR(16)) @Id
    @Id
    @Column(name = "card_number", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Owning customer identifier &mdash; a scalar foreign key, not an association.
     *
     * <p>Migrated from {@code XREF-CUST-ID PIC 9(09)}: an unsigned nine-digit
     * identifier, mapped to {@link Long} (PostgreSQL {@code BIGINT}) for
     * consistency with {@code Customer.custId}. This is a <em>logical</em> foreign
     * key to {@code customers.customer_id} (scalar, per the flat VSAM contract); it
     * is deliberately <strong>not</strong> a {@code @ManyToOne} reference, so this
     * entity imports nothing from {@code Customer}.</p>
     */
    // XREF-CUST-ID PIC 9(09) -> 9-digit owning customer id -> Long (BIGINT); scalar FK to customers.customer_id (NOT @ManyToOne)
    @Column(name = "customer_id", nullable = false)
    private Long xrefCustId;

    /**
     * Owning account identifier &mdash; a scalar foreign key and the
     * {@code CXACAIX} alternate-index column.
     *
     * <p>Migrated from {@code XREF-ACCT-ID PIC 9(11)}: an unsigned eleven-digit
     * identifier, mapped to {@link Long} (PostgreSQL {@code BIGINT}) because eleven
     * digits exceed {@code Integer}'s ~2.1-billion ceiling and to stay
     * type-consistent with {@code Account.acctId}. This is a <em>logical</em>
     * foreign key to {@code accounts.account_id} and is deliberately
     * <strong>not</strong> a {@code @ManyToOne} reference.</p>
     *
     * <p><strong>{@code XREF-ACCT-ID} = {@code CXACAIX} alternate index &rarr;
     * {@code findByXrefAcctId}.</strong> On the mainframe the {@code CARDXREF}
     * cluster carried the alternate index {@code CXACAIX} keyed on this field,
     * enabling the account&rarr;cards lookup used by {@code COACTVWC},
     * {@code COCRDLIC} and {@code COTRN02C}. The
     * {@code CardCrossReferenceRepository} therefore exposes a derived query
     * {@code findByXrefAcctId(Long)} (and/or {@code findAllByXrefAcctId}) over this
     * {@code account_id} column, backed by a dedicated secondary index created in
     * the Flyway {@code V2} migration so the reverse lookup remains index-served.</p>
     */
    // XREF-ACCT-ID PIC 9(11) -> 11-digit owning account id -> Long (BIGINT); scalar FK to accounts.account_id; CXACAIX alternate-index column -> findByXrefAcctId (secondary index in Flyway V2)
    @Column(name = "account_id", nullable = false)
    private Long xrefAcctId;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate) to
     * instantiate the entity reflectively before populating its fields.
     */
    public CardCrossReference() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the primary-key card number ({@code XREF-CARD-NUM}).
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the primary-key card number ({@code XREF-CARD-NUM}).
     *
     * @param xrefCardNum the 16-character card number to set
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the owning customer identifier ({@code XREF-CUST-ID}).
     *
     * <p>This is a scalar foreign key to {@code customers.customer_id}; it is not a
     * JPA association.</p>
     *
     * @return the owning customer identifier, or {@code null} if unset
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the owning customer identifier ({@code XREF-CUST-ID}).
     *
     * @param xrefCustId the owning customer identifier to set
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the owning account identifier ({@code XREF-ACCT-ID}).
     *
     * <p>This is a scalar foreign key to {@code accounts.account_id} and the
     * {@code CXACAIX} alternate-index column; it is not a JPA association. The
     * repository's {@code findByXrefAcctId(Long)} query selects on this value.</p>
     *
     * @return the owning account identifier, or {@code null} if unset
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the owning account identifier ({@code XREF-ACCT-ID}).
     *
     * @param xrefAcctId the owning account identifier to set
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Identity-based equality keyed on the card number ({@link #xrefCardNum}).
     *
     * <p>Two {@code CardCrossReference} instances are equal when they are of the
     * exact same class and share the same {@link #xrefCardNum}. The primary key
     * alone defines entity identity; the mutable id columns are deliberately
     * excluded so equality stays stable. Exact-class comparison (rather than
     * {@code instanceof}) is used so a proxy/subclass is not treated as equal to a
     * different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CardCrossReference} with an
     *         equal card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CardCrossReference that = (CardCrossReference) o;
        return Objects.equals(xrefCardNum, that.xrefCardNum);
    }

    /**
     * Hash code derived solely from {@link #xrefCardNum}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * Diagnostic representation that intentionally protects cardholder data. The
     * card number (PAN) is masked to its last four digits via
     * {@link #maskedCardNumber()}; only the masked PAN and the two non-sensitive
     * owning identifiers are included, so this value is safe to emit to logs.
     *
     * @return a human-readable, non-sensitive description of this cross-reference
     */
    @Override
    public String toString() {
        return "CardCrossReference{"
                + "xrefCardNum='" + maskedCardNumber() + '\''
                + ", xrefCustId=" + xrefCustId
                + ", xrefAcctId=" + xrefAcctId
                + '}';
    }

    /**
     * Masks the card number (PAN) for safe inclusion in {@link #toString()},
     * revealing at most the final four digits and replacing every preceding
     * character with {@code '*'}. A {@code null} value yields {@code "null"}, and a
     * value of four or fewer characters is fully masked so no digits leak.
     *
     * @return the masked card number suitable for logging
     */
    private String maskedCardNumber() {
        if (xrefCardNum == null) {
            return "null";
        }
        int length = xrefCardNum.length();
        if (length <= 4) {
            // Too short to reveal a last-four suffix safely; mask completely.
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + xrefCardNum.substring(length - 4);
    }
}
