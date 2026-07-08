package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing the <strong>card cross-reference</strong> record that links a
 * physical card number to its owning customer and account.
 *
 * <p>This entity is a field-by-field translation of the COBOL copybook
 * {@code app/cpy/CVACT03Y.cpy} ({@code CARD-XREF-RECORD}, record length {@code 50},
 * source commit SHA {@code 27d6c6f}). In the legacy AWS CardDemo mainframe application the
 * underlying dataset was a VSAM KSDS keyed on the card number, with an alternate index
 * (AIX/PATH) over the account id; together they drove the
 * <em>card&nbsp;&rarr;&nbsp;account&nbsp;&rarr;&nbsp;customer</em> navigation used across the
 * online and batch tiers.</p>
 *
 * <h2>COBOL &rarr; Java field mapping</h2>
 * <pre>
 *   05 XREF-CARD-NUM  PIC X(16)  -&gt; {@link #xrefCardNum} (String, natural key)
 *   05 XREF-CUST-ID   PIC 9(09)  -&gt; {@link #xrefCustId}  (Long)
 *   05 XREF-ACCT-ID   PIC 9(11)  -&gt; {@link #xrefAcctId}  (Long)
 *   05 FILLER         PIC X(14)  -&gt; (intentionally not mapped &ndash; trailing padding)
 * </pre>
 *
 * <p>Byte-origin sanity check: {@code 16 + 9 + 11 + 14 = 50}, matching the copybook's
 * declared record length. The trailing {@code FILLER PIC X(14)} carries no business data and
 * is deliberately omitted from the persistent model.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>{@link #xrefCardNum} is the natural primary key (VARCHAR(16)); there is no surrogate
 *       key and therefore no {@code @GeneratedValue}.</li>
 *   <li>{@link #xrefCustId} and {@link #xrefAcctId} are plain scalar {@code BIGINT} columns.
 *       They are intentionally <em>not</em> modelled as JPA relationships to preserve the flat,
 *       cross-reference semantics of the original record and to avoid expanding the documented
 *       feature scope.</li>
 *   <li>Unlike the {@code Account} and {@code Card} entities, this record has no optimistic-lock
 *       version column, so there is no {@code @Version} field.</li>
 *   <li>The legacy AIX-by-account lookup is reproduced by the companion repository as a derived
 *       query (for example {@code findByXrefAcctId(...)}); it is deliberately absent from this
 *       entity, which carries state only.</li>
 * </ul>
 *
 * <p>The physical column contract below is authoritative and must remain aligned with the
 * Flyway migration {@code db/migration/V1__schema.sql} under a {@code ddl-auto: validate}
 * configuration:</p>
 * <pre>
 *   CREATE TABLE card_xref (
 *       xref_card_num VARCHAR(16) NOT NULL,
 *       xref_cust_id  BIGINT,
 *       xref_acct_id  BIGINT,
 *       PRIMARY KEY (xref_card_num)
 *   );
 * </pre>
 */
@Entity
@Table(name = "card_xref")
public class CardXref {

    /**
     * Card number &ndash; the 16-character natural primary key of the cross-reference record.
     *
     * <p>Origin: {@code XREF-CARD-NUM PIC X(16)}. Stored as {@code VARCHAR(16) NOT NULL}.</p>
     */
    @Id
    @Column(name = "xref_card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Customer identifier associated with the card.
     *
     * <p>Origin: {@code XREF-CUST-ID PIC 9(09)}. Modelled as {@link Long} and stored as
     * {@code BIGINT}. This is a plain scalar reference value, not a JPA relationship.</p>
     */
    @Column(name = "xref_cust_id")
    private Long xrefCustId;

    /**
     * Account identifier associated with the card.
     *
     * <p>Origin: {@code XREF-ACCT-ID PIC 9(11)}. Modelled as {@link Long} and stored as
     * {@code BIGINT}. This is a plain scalar reference value, not a JPA relationship; the
     * legacy VSAM alternate index over this field is reproduced as a derived repository query.</p>
     */
    @Column(name = "xref_acct_id")
    private Long xrefAcctId;

    /**
     * Default no-argument constructor required by the JPA specification for entity
     * instantiation via reflection.
     */
    public CardXref() {
        // Intentionally empty: JPA-managed entities require a public/protected no-arg constructor.
    }

    /**
     * Returns the card number (natural primary key).
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number (natural primary key).
     *
     * @param xrefCardNum the 16-character card number to assign
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the associated customer identifier.
     *
     * @return the customer id, or {@code null} if unset
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the associated customer identifier.
     *
     * @param xrefCustId the customer id to assign
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the associated account identifier.
     *
     * @return the account id, or {@code null} if unset
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the associated account identifier.
     *
     * @param xrefAcctId the account id to assign
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }
}
