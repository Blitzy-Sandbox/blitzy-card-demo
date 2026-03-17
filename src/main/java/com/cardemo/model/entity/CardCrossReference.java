package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the COBOL CARD-XREF-RECORD (50 bytes) from CVACT03Y.cpy
 * to the PostgreSQL {@code card_xref} table.
 *
 * <p>This entity provides the card-to-customer-to-account cross-reference mapping,
 * critical for resolving which account a given card number belongs to. The COBOL
 * application defines a VSAM Alternate Index (AIX) named CXACAIX on the
 * XREF-ACCT-ID field, enabling account-based card lookups. In JPA, this alternate
 * index is represented by a database index on {@code xref_acct_id} and accessed
 * via the repository method {@code findByXrefAcctId(String)}.</p>
 *
 * <h3>Source COBOL Structure (CVACT03Y.cpy)</h3>
 * <pre>
 * 01 CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM     PIC X(16).   — Primary key (card number)
 *     05  XREF-CUST-ID      PIC 9(09).   — FK to Customer (logical)
 *     05  XREF-ACCT-ID      PIC 9(11).   — FK to Account (logical, has AIX)
 *     05  FILLER            PIC X(14).   — Not mapped
 * </pre>
 *
 * <h3>COBOL Usage Context</h3>
 * <ul>
 *   <li>COCRDLIC.cbl — Card list: reads CXACAIX to list all cards for an account</li>
 *   <li>COCRDUPC.cbl — Card update: reads CARDXREF to resolve card-to-account</li>
 *   <li>COACTVWC.cbl — Account view: reads CXACAIX to find cards for account</li>
 *   <li>CBTRN02C.cbl — Batch posting: validates card via XREF-FILE READ</li>
 *   <li>CBACT03C.cbl — Cross-reference file reader utility</li>
 * </ul>
 *
 * @see com.cardemo.repository.CardCrossReferenceRepository
 */
@Entity
@Table(
    name = "card_cross_references",
    indexes = {
        @Index(name = "idx_xref_acct_id", columnList = "account_id")
    }
)
public class CardCrossReference {

    /**
     * Card number — primary key.
     * Maps COBOL XREF-CARD-NUM PIC X(16).
     * 16-character string representing the credit card number used as the
     * unique identifier for cross-reference lookups.
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Customer identifier — logical foreign key to the Customer entity.
     * Maps COBOL XREF-CUST-ID PIC 9(09).
     * Stored as String (not Integer) to preserve leading zeros, matching
     * the COBOL numeric display format. COBOL VSAM does not enforce
     * referential integrity; this is a logical relationship only.
     */
    @Column(name = "cust_id", length = 9)
    private String xrefCustId;

    /**
     * Account identifier — logical foreign key to the Account entity.
     * Maps COBOL XREF-ACCT-ID PIC 9(11).
     * This field has a VSAM Alternate Index (CXACAIX) in the COBOL application,
     * enabling account-based card lookups. The corresponding database index
     * {@code idx_xref_acct_id} is declared on the {@code @Table} annotation.
     * Stored as String to preserve leading zeros per COBOL PIC 9(11) format.
     */
    @Column(name = "account_id", length = 11)
    private String xrefAcctId;

    // FILLER PIC X(14) is intentionally NOT mapped — padding bytes only.

    /**
     * No-argument constructor required by JPA specification.
     * Hibernate and other JPA providers use this constructor when instantiating
     * entities from database result sets.
     */
    public CardCrossReference() {
        // JPA-required default constructor
    }

    /**
     * All-arguments constructor for programmatic entity creation.
     *
     * @param xrefCardNum the 16-character card number (primary key)
     * @param xrefCustId  the 9-character customer identifier (logical FK to Customer)
     * @param xrefAcctId  the 11-character account identifier (logical FK to Account, indexed)
     */
    public CardCrossReference(String xrefCardNum, String xrefCustId, String xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns the card number (primary key).
     *
     * @return the 16-character card number
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number (primary key).
     *
     * @param xrefCardNum the 16-character card number
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the customer identifier.
     *
     * @return the 9-character customer ID (logical FK to Customer)
     */
    public String getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer identifier.
     *
     * @param xrefCustId the 9-character customer ID
     */
    public void setXrefCustId(String xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the account identifier.
     * This field is indexed (CXACAIX alternate index equivalent) for efficient
     * account-based card lookups.
     *
     * @return the 11-character account ID (logical FK to Account)
     */
    public String getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account identifier.
     *
     * @param xrefAcctId the 11-character account ID
     */
    public void setXrefAcctId(String xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Compares this entity with another object for equality based on the
     * primary key ({@code xrefCardNum}). This follows the JPA best practice
     * of using the business/natural key for entity identity comparison.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same cross-reference record
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
     * Returns a hash code based on the primary key ({@code xrefCardNum}).
     * Consistent with {@link #equals(Object)} — only the primary key field
     * participates in the hash computation.
     *
     * @return hash code derived from the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * Returns a string representation of this cross-reference record,
     * including all mapped fields for debugging and logging purposes.
     *
     * @return a descriptive string with card number, customer ID, and account ID
     */
    @Override
    public String toString() {
        return "CardCrossReference{" +
                "xrefCardNum='" + xrefCardNum + '\'' +
                ", xrefCustId='" + xrefCustId + '\'' +
                ", xrefAcctId='" + xrefAcctId + '\'' +
                '}';
    }
}
