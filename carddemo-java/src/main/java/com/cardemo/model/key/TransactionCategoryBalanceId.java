package com.cardemo.model.key;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code TransactionCategoryBalance} entity.
 *
 * <p>This {@link Embeddable} value type is the Java 25 / Spring Data JPA
 * replacement for the multi-field VSAM KSDS key that the legacy AWS CardDemo
 * mainframe application used to address rows of the transaction-category-balance
 * dataset {@code TCATBALF}. It is migrated from the COBOL copybook
 * {@code app/cpy/CVTRA01Y.cpy}, specifically the {@code TRAN-CAT-KEY} group
 * nested inside {@code TRAN-CAT-BAL-RECORD} (record length 50):</p>
 *
 * <pre>{@code
 * 05  TRAN-CAT-KEY.
 *    10 TRANCAT-ACCT-ID   PIC 9(11).
 *    10 TRANCAT-TYPE-CD   PIC X(02).
 *    10 TRANCAT-CD        PIC 9(04).
 * }</pre>
 *
 * <h2>VSAM KSDS key &rarr; JPA composite key substitution</h2>
 * <p>On the mainframe, {@code TCATBALF} was a keyed (KSDS) VSAM cluster whose
 * primary key was the concatenation of the three {@code TRAN-CAT-KEY}
 * sub-fields, read positionally as a single contiguous byte string. The
 * relational target replaces that physical concatenated key with an explicit,
 * typed composite key: this class is declared on the owning entity as
 * {@code @EmbeddedId private TransactionCategoryBalanceId id;}, and Hibernate
 * maps each component to its own column. This is the technology-substitution
 * point for the dataset's access path; per the migration's
 * <strong>Minimal Change Clause</strong> the key's field order, lengths and
 * semantics are preserved <strong>exactly</strong>, no business logic is added,
 * and no field beyond the three original key components is introduced.</p>
 *
 * <h2>Scope of this key class</h2>
 * <p>Only the three {@code TRAN-CAT-KEY} components live here. The record's
 * remaining, non-key fields &mdash; {@code TRAN-CAT-BAL PIC S9(09)V99} (a
 * signed decimal balance) and the trailing {@code FILLER PIC X(22)} &mdash; are
 * deliberately <strong>excluded</strong>; they belong to the
 * {@code TransactionCategoryBalance} entity, not to its identifier. Because
 * none of the three key components carries decimal positions, this class uses
 * only exact integral and character types and intentionally contains
 * <strong>no</strong> {@code float}, {@code double} or {@code BigDecimal}.</p>
 *
 * <h2>Design contract</h2>
 * <ul>
 *   <li><strong>Mutable POJO, not a {@code record}.</strong> A classic JPA
 *       composite-key class with a public no-argument constructor and
 *       getters/setters is used rather than a Java {@code record}. Hibernate
 *       ORM instantiates an {@code @EmbeddedId} via its no-arg constructor and
 *       populates fields reflectively; records (which lack a no-arg
 *       constructor) introduce {@code @EmbeddedId} edge cases and are therefore
 *       avoided.</li>
 *   <li><strong>{@link Serializable}.</strong> JPA requires every primary-key
 *       type to be serializable; an explicit {@code serialVersionUID} pins the
 *       serialized form.</li>
 *   <li><strong>Value equality.</strong> {@link #equals(Object)} and
 *       {@link #hashCode()} cover all three components so the key behaves
 *       correctly as a map key and inside the Hibernate persistence context.</li>
 *   <li><strong>No validation annotations.</strong> Jakarta Bean Validation
 *       constraints (for example {@code @NotNull}/{@code @Size}) are
 *       intentionally absent here; input validation is the responsibility of
 *       the request DTO layer, keeping this a pure persistence key type.</li>
 * </ul>
 *
 * <h2>Column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code account_id},
 * {@code type_code} and {@code category_code} &mdash; are the authoritative
 * physical column names for the composite key. The {@code TransactionCategoryBalance}
 * entity's {@code transaction_category_balances} table mapping and the Flyway
 * {@code V1__create_schema.sql} DDL must use these exact names, with
 * {@code account_id} as {@code BIGINT}, {@code category_code} as {@code INTEGER}
 * and {@code type_code} as {@code CHAR}/{@code VARCHAR(2)}.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see java.io.Serializable
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    /**
     * Serialization version identifier. JPA mandates that a primary-key type be
     * {@link Serializable}; declaring an explicit {@code serialVersionUID} pins
     * the serialized form of this key.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account identifier component of the key.
     *
     * <p>Migrated from {@code TRANCAT-ACCT-ID PIC 9(11)} &mdash; an 11-digit
     * unsigned integer. Eleven digits exceed the ~2.1-billion (10-digit)
     * ceiling of {@code Integer}, so {@link Long} is required. This also matches
     * the {@code Account} entity primary key, which is likewise {@link Long}
     * (from {@code ACCT-ID PIC 9(11)} in {@code CVACT01Y.cpy}), keeping the
     * cross-reference between balance rows and accounts type-consistent.</p>
     */
    // TRANCAT-ACCT-ID PIC 9(11) -> 11-digit unsigned integer -> Long
    @Column(name = "account_id", nullable = false)
    private Long acctId;

    /**
     * Transaction-type-code component of the key.
     *
     * <p>Migrated from {@code TRANCAT-TYPE-CD PIC X(02)} &mdash; a fixed-length
     * 2-character alphanumeric field, modelled as a {@link String} of length 2.</p>
     */
    // TRANCAT-TYPE-CD PIC X(02) -> fixed-length 2-char alphanumeric -> String(2)
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction-category-code component of the key.
     *
     * <p>Migrated from {@code TRANCAT-CD PIC 9(04)} &mdash; a 4-digit integer
     * (maximum value 9999). This fits comfortably within an {@link Integer},
     * the appropriate exact integral type for the value range.</p>
     */
    // TRANCAT-CD PIC 9(04) -> 4-digit integer (max 9999) -> Integer
    @Column(name = "category_code", nullable = false)
    private Integer catCode;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the embedded identifier reflectively.
     */
    public TransactionCategoryBalanceId() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Constructs a fully-populated composite key.
     *
     * <p>Parameter order mirrors the COBOL {@code TRAN-CAT-KEY} field order
     * exactly: account id, then transaction-type code, then category code.</p>
     *
     * @param acctId   account identifier ({@code TRANCAT-ACCT-ID})
     * @param typeCode transaction-type code ({@code TRANCAT-TYPE-CD})
     * @param catCode  transaction-category code ({@code TRANCAT-CD})
     */
    public TransactionCategoryBalanceId(Long acctId, String typeCode, Integer catCode) {
        this.acctId = acctId;
        this.typeCode = typeCode;
        this.catCode = catCode;
    }

    /**
     * Returns the account-identifier component ({@code TRANCAT-ACCT-ID}).
     *
     * @return the account identifier, or {@code null} if unset
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the account-identifier component ({@code TRANCAT-ACCT-ID}).
     *
     * @param acctId the account identifier to set
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the transaction-type-code component ({@code TRANCAT-TYPE-CD}).
     *
     * @return the 2-character transaction-type code, or {@code null} if unset
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction-type-code component ({@code TRANCAT-TYPE-CD}).
     *
     * @param typeCode the 2-character transaction-type code to set
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction-category-code component ({@code TRANCAT-CD}).
     *
     * @return the transaction-category code, or {@code null} if unset
     */
    public Integer getCatCode() {
        return catCode;
    }

    /**
     * Sets the transaction-category-code component ({@code TRANCAT-CD}).
     *
     * @param catCode the transaction-category code to set
     */
    public void setCatCode(Integer catCode) {
        this.catCode = catCode;
    }

    /**
     * Value-based equality across all three key components.
     *
     * <p>Two instances are equal only when {@code o} is exactly a
     * {@code TransactionCategoryBalanceId} and its {@code acctId},
     * {@code typeCode} and {@code catCode} all match. Exact-class comparison
     * (rather than {@code instanceof}) is used because this is an identity type:
     * a subtype must not be considered equal to its base key.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an equal composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
        return Objects.equals(acctId, that.acctId)
                && Objects.equals(typeCode, that.typeCode)
                && Objects.equals(catCode, that.catCode);
    }

    /**
     * Hash code derived from all three key components, consistent with
     * {@link #equals(Object)}.
     *
     * @return the composite hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId, typeCode, catCode);
    }

    /**
     * Diagnostic representation including all three key components.
     *
     * @return a human-readable description of this composite key
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId{"
                + "acctId=" + acctId
                + ", typeCode='" + typeCode + '\''
                + ", catCode=" + catCode
                + '}';
    }
}
